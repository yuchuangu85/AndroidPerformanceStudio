package com.androidperformancestudio.desktop

import com.androidperformancestudio.application.ReportController
import com.androidperformancestudio.application.ReportData
import com.androidperformancestudio.application.ReportLoadState
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.application.ReportTab
import com.androidperformancestudio.profileanalysis.FlameCallNodeId
import com.androidperformancestudio.profileanalysis.StackChartBlockId
import com.androidperformancestudio.storage.PanelProjection
import java.io.Closeable
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

internal enum class SimpleperfWebPanel { FLAME_GRAPH, CALL_TREE, STACK_CHART }
internal enum class SimpleperfWebStatus { ACCEPTED, STALE, UNAVAILABLE, RUNNING, SUCCEEDED, FAILED, CANCELLED }

internal data class SimpleperfWebBridgeEvent(
    val protocolVersion: Int,
    val sessionId: String,
    val reportGeneration: Long,
    val pageId: String,
    val sequence: Long,
    val panel: SimpleperfWebPanel?,
    val callNodeId: String?,
    val stackBlockId: String?,
    val cancel: Boolean,
)

internal data class SimpleperfWebBridgeResponse(
    val status: SimpleperfWebStatus,
    val code: String,
    val sequence: Long,
) {
    fun json(): String = buildJsonObject {
        put("protocolVersion", 1)
        put("status", status.name.lowercase())
        put("code", code)
        put("detail", code)
        put("sequence", sequence)
    }.toString()
}

internal data class SimpleperfCanonicalWebSelection(val panel: SimpleperfWebPanel, val id: String, val label: String)
internal data class SimpleperfWebCommand(val session: SimpleperfWebBridgeSession, val event: SimpleperfWebBridgeEvent)

/** The browser submits only host-issued IDs; it cannot author report facts, source or credentials. */
internal class SimpleperfWebBridgeSession(
    val report: ReportData,
    val state: ReportState,
    val generation: Long,
    private val onCommand: (SimpleperfWebCommand) -> Boolean,
) : Closeable {
    val token: String = ByteArray(32).also(SecureRandom()::nextBytes).let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
    val sessionId: String = UUID.randomUUID().toString()
    private var pageId: String = ""
    private var sequence = 0L
    private var closed = false
    private var response = SimpleperfWebBridgeResponse(SimpleperfWebStatus.UNAVAILABLE, "E_PAGE_NOT_ATTACHED", 0)
    val selections: List<SimpleperfCanonicalWebSelection> = canonicalSelections(report)
    private val allowed = selections.map { it.panel to it.id }.toSet()

    @Synchronized
    fun attach(): String {
        check(!closed) { "E_BRIDGE_CLOSED" }
        if (pageId.isNotEmpty()) cancelCurrent()
        pageId = UUID.randomUUID().toString()
        sequence = 0
        response = SimpleperfWebBridgeResponse(SimpleperfWebStatus.UNAVAILABLE, "E_SELECTION_REQUIRED", 0)
        return buildJsonObject {
            put("protocolVersion", 1)
            put("sessionId", sessionId)
            put("reportGeneration", generation)
            put("pageId", pageId)
            put("selections", buildJsonArray {
                selections.forEach { item ->
                    add(buildJsonObject { put("panel", item.panel.name); put("id", item.id); put("label", item.label) })
                }
            })
            put("nativeFirefoxSelectionAvailable", false)
        }.toString()
    }

    fun acceptsToken(value: String?): Boolean = value != null && value.length == token.length && MessageDigest.isEqual(token.toByteArray(), value.toByteArray())

    @Synchronized
    fun submit(body: ByteArray): SimpleperfWebBridgeResponse {
        if (closed) return SimpleperfWebBridgeResponse(SimpleperfWebStatus.STALE, "E_BRIDGE_CLOSED", sequence)
        if (body.size > MAX_BRIDGE_BYTES) return SimpleperfWebBridgeResponse(SimpleperfWebStatus.UNAVAILABLE, "E_BRIDGE_LIMIT", sequence)
        val event = try { decodeEvent(body) } catch (_: Exception) {
            return SimpleperfWebBridgeResponse(SimpleperfWebStatus.UNAVAILABLE, "E_BRIDGE_PROTOCOL", sequence)
        }
        if (event.sessionId != sessionId || event.reportGeneration != generation || event.pageId != pageId || pageId.isEmpty()) {
            return SimpleperfWebBridgeResponse(SimpleperfWebStatus.STALE, "E_BRIDGE_CONTEXT", event.sequence)
        }
        if (event.sequence <= sequence) return SimpleperfWebBridgeResponse(SimpleperfWebStatus.STALE, "E_BRIDGE_SEQUENCE", event.sequence)
        if (!event.cancel) {
            val id = event.callNodeId ?: event.stackBlockId
            if (event.panel == null || id == null || event.panel to id !in allowed) {
                return SimpleperfWebBridgeResponse(SimpleperfWebStatus.UNAVAILABLE, "E_BRIDGE_NODE", event.sequence)
            }
        }
        val previousSequence = sequence
        val previousResponse = response
        sequence = event.sequence
        val accepted = SimpleperfWebBridgeResponse(if (event.cancel) SimpleperfWebStatus.CANCELLED else SimpleperfWebStatus.ACCEPTED, if (event.cancel) "E_CANCELLED" else "OK", sequence)
        response = accepted
        if (!onCommand(SimpleperfWebCommand(this, event))) {
            sequence = previousSequence
            response = previousResponse
            return SimpleperfWebBridgeResponse(SimpleperfWebStatus.UNAVAILABLE, "E_BRIDGE_BUSY", event.sequence)
        }
        return accepted
    }

    @Synchronized
    fun update(event: SimpleperfWebBridgeEvent, status: SimpleperfWebStatus, code: String = "OK") {
        if (!closed && event.pageId == pageId && event.sequence == sequence) response = SimpleperfWebBridgeResponse(status, code, sequence)
    }

    @Synchronized
    fun isCurrent(event: SimpleperfWebBridgeEvent): Boolean = !closed && event.pageId == pageId && event.sequence == sequence

    @Synchronized
    fun result(): SimpleperfWebBridgeResponse = response

    @Synchronized
    override fun close() {
        if (closed) return
        cancelCurrent()
        closed = true
        response = SimpleperfWebBridgeResponse(SimpleperfWebStatus.CANCELLED, "E_BRIDGE_CLOSED", sequence)
    }

    private fun cancelCurrent() {
        onCommand(SimpleperfWebCommand(this, SimpleperfWebBridgeEvent(1, sessionId, generation, pageId, ++sequence, null, null, null, true)))
    }
}

internal class SimpleperfWebBridgeHost : Closeable {
    private val channel = Channel<SimpleperfWebCommand>(16)
    val commands = channel.receiveAsFlow()
    private var session: SimpleperfWebBridgeSession? = null
    private var generation = 0L
    private var closed = false

    @Synchronized
    fun open(report: ReportData, state: ReportState): SimpleperfWebBridgeSession {
        check(!closed) { "E_BRIDGE_CLOSED" }
        session?.close()
        return SimpleperfWebBridgeSession(report, state, ++generation) { channel.trySend(it).isSuccess }.also { session = it }
    }

    @Synchronized
    fun invalidateUnless(state: ReportState) {
        val active = session ?: return
        val ready = (state.loadState as? ReportLoadState.Ready)?.report
        if (ready !== active.report || state.filter != active.state.filter || state.callStackQuery != active.state.callStackQuery) {
            active.close()
            session = null
        }
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        session?.close()
        session = null
        channel.close()
    }
}

/** Revalidate against the live controller and preserve its canonical filter/query objects. */
internal fun applySimpleperfWebSelection(controller: ReportController, command: SimpleperfWebCommand): ReportState? {
    if (!command.session.isCurrent(command.event)) return null
    val current = controller.state.value
    val report = (current.loadState as? ReportLoadState.Ready)?.report ?: return null
    if (report !== command.session.report || current.filter != command.session.state.filter || current.callStackQuery != command.session.state.callStackQuery) return null
    val event = command.event
    if (event.cancel) return null
    when (event.panel) {
        SimpleperfWebPanel.FLAME_GRAPH, SimpleperfWebPanel.CALL_TREE -> {
            val id = event.callNodeId?.toLongOrNull()?.let(::FlameCallNodeId) ?: return null
            if (report.flameGraph.callNodes.indexOf(id) == null) return null
            controller.selectTab(if (event.panel == SimpleperfWebPanel.FLAME_GRAPH) ReportTab.FLAME_GRAPH else ReportTab.CALL_TREE)
            controller.selectCallNode(id)
            if (controller.state.value.workspace.selections.callNodeId != id) return null
        }
        SimpleperfWebPanel.STACK_CHART -> {
            val id = event.stackBlockId?.let(::StackChartBlockId) ?: return null
            val chart = (report.stackChart as? PanelProjection.Ready)?.value ?: return null
            if (chart.blockById(id) == null) return null
            controller.selectTab(ReportTab.STACK_CHART)
            controller.selectStackChartBlock(id)
            if (controller.state.value.workspace.selections.stackChartBlockId != id) return null
        }
        null -> return null
    }
    return controller.state.value
}

private fun canonicalSelections(report: ReportData): List<SimpleperfCanonicalWebSelection> {
    val nodes = report.flameGraph.callNodes
    val result = mutableListOf<SimpleperfCanonicalWebSelection>()
    for (index in 0 until minOf(nodes.size, MAX_CANONICAL_SELECTIONS)) {
        val id = nodes.nodeIdAt(index)?.value?.toString() ?: continue
        val label = nodes.frameAt(index)?.symbolName?.take(160) ?: "Call node"
        result += SimpleperfCanonicalWebSelection(SimpleperfWebPanel.FLAME_GRAPH, id, label)
        result += SimpleperfCanonicalWebSelection(SimpleperfWebPanel.CALL_TREE, id, label)
    }
    (report.stackChart as? PanelProjection.Ready)?.value?.blocks?.take(MAX_CANONICAL_SELECTIONS)?.forEach { block ->
        result += SimpleperfCanonicalWebSelection(SimpleperfWebPanel.STACK_CHART, block.id.value, "Block ${block.depth} @ ${block.startNanos}")
    }
    return result
}

private fun decodeEvent(bytes: ByteArray): SimpleperfWebBridgeEvent {
    val root = Json.parseToJsonElement(bytes.decodeToString(throwOnInvalidSequence = true)) as? JsonObject ?: error("E_PROTOCOL")
    require(root.keys == setOf("protocolVersion", "sessionId", "reportGeneration", "pageId", "sequence", "panel", "callNodeId", "stackBlockId", "cancel"))
    fun string(key: String): String? = root[key]?.let { if (it.toString() == "null") null else it.jsonPrimitive.also { value -> require(value.isString) }.content }
    val version = root.getValue("protocolVersion").jsonPrimitive.long
    require(version == 1L)
    val cancel = root.getValue("cancel").toString().let { require(it == "true" || it == "false"); it == "true" }
    val panel = string("panel")?.let(SimpleperfWebPanel::valueOf)
    val call = string("callNodeId")
    val stack = string("stackBlockId")
    require(cancel || (panel == SimpleperfWebPanel.STACK_CHART && stack != null && call == null) || (panel != null && panel != SimpleperfWebPanel.STACK_CHART && call != null && stack == null))
    return SimpleperfWebBridgeEvent(1, requireNotNull(string("sessionId")), root.getValue("reportGeneration").jsonPrimitive.long, requireNotNull(string("pageId")), root.getValue("sequence").jsonPrimitive.long, panel, call, stack, cancel)
}

internal const val MAX_BRIDGE_BYTES = 4096
private const val MAX_CANONICAL_SELECTIONS = 2000

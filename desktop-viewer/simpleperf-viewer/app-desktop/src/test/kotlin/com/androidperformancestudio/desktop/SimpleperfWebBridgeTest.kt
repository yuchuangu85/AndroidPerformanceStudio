package com.androidperformancestudio.desktop

import com.androidperformancestudio.application.ReportController
import com.androidperformancestudio.application.ReportLoadState
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.application.ReportTab
import com.androidperformancestudio.fixtures.FirefoxFlameGraphFixtures
import java.net.Socket
import java.net.URI
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimpleperfWebBridgeTest {
    @Test
    fun `canonical flame call tree and stack selections match native evidence exactly`() = withReport { controller, state ->
        val report = (state.loadState as ReportLoadState.Ready).report
        val commands = mutableListOf<SimpleperfWebCommand>()
        SimpleperfWebBridgeSession(report, state, 1) { commands.add(it); true }.use { bridge ->
            val page = Json.parseToJsonElement(bridge.attach()) as JsonObject
            var sequence = 0L
            for (panel in SimpleperfWebPanel.entries) {
                val selection = bridge.selections.firstOrNull { it.panel == panel } ?: continue
                val response = bridge.submit(event(bridge, page, ++sequence, selection))
                assertEquals(SimpleperfWebStatus.ACCEPTED, response.status)
                val applied = assertNotNull(applySimpleperfWebSelection(controller, commands.last()))
                val native = extractSimpleperfEvidence(report, applied)
                assertEquals(1, native.size)
                assertTrue(native.single().currentSelection)
                assertEquals(when (panel) {
                    SimpleperfWebPanel.STACK_CHART -> "simpleperf:stack:${selection.id}"
                    else -> "simpleperf:call:${selection.id}"
                }, native.single().id)
                assertEquals(state.filter, applied.filter)
                assertEquals(state.callStackQuery, applied.callStackQuery)
            }
        }
    }

    @Test
    fun `unknown stale duplicate oversized and injected source events are refused`() = withReport { _, state ->
        val report = (state.loadState as ReportLoadState.Ready).report
        var calls = 0
        SimpleperfWebBridgeSession(report, state, 7) { calls++; true }.use { bridge ->
            val page = Json.parseToJsonElement(bridge.attach()) as JsonObject
            val selection = bridge.selections.first()
            val valid = event(bridge, page, 1, selection)
            assertEquals(SimpleperfWebStatus.ACCEPTED, bridge.submit(valid).status)
            assertEquals("E_BRIDGE_SEQUENCE", bridge.submit(valid).code)
            val unknown = selection.copy(id = "not-canonical")
            assertEquals("E_BRIDGE_NODE", bridge.submit(event(bridge, page, 2, unknown)).code)
            val stale = valid.decodeToString().replace(bridge.sessionId, "other-session").toByteArray()
            assertEquals("E_BRIDGE_CONTEXT", bridge.submit(stale).code)
            assertEquals("E_BRIDGE_LIMIT", bridge.submit(ByteArray(MAX_BRIDGE_BYTES + 1)).code)
            val source = valid.decodeToString().removeSuffix("}") + ",\"sourcePath\":\"/private/source\"}"
            assertEquals("E_BRIDGE_PROTOCOL", bridge.submit(source.toByteArray()).code)
            assertEquals(1, calls)
        }
    }

    @Test
    fun `page reload closes old lifecycle and stale results never override new request`() = withReport { _, state ->
        val report = (state.loadState as ReportLoadState.Ready).report
        val commands = mutableListOf<SimpleperfWebCommand>()
        val bridge = SimpleperfWebBridgeSession(report, state, 1) { commands.add(it); true }
        val oldPage = Json.parseToJsonElement(bridge.attach()) as JsonObject
        val selected = bridge.selections.first()
        bridge.submit(event(bridge, oldPage, 1, selected))
        val oldEvent = commands.last().event
        val newPage = Json.parseToJsonElement(bridge.attach()) as JsonObject
        assertTrue(commands.last().event.cancel)
        bridge.submit(event(bridge, newPage, 1, selected))
        bridge.update(oldEvent, SimpleperfWebStatus.SUCCEEDED)
        assertEquals(SimpleperfWebStatus.ACCEPTED, bridge.result().status)
        assertEquals("E_BRIDGE_CONTEXT", bridge.submit(event(bridge, oldPage, 2, selected)).code)
        bridge.close()
        assertEquals(SimpleperfWebStatus.CANCELLED, bridge.result().status)
        assertEquals("E_BRIDGE_CLOSED", bridge.submit(event(bridge, newPage, 2, selected)).code)
    }

    @Test
    fun `report close or new canonical report invalidates browser command`() = withReport { controller, state ->
        val report = (state.loadState as ReportLoadState.Ready).report
        val commands = mutableListOf<SimpleperfWebCommand>()
        val bridge = SimpleperfWebBridgeSession(report, state, 1) { commands.add(it); true }
        val page = Json.parseToJsonElement(bridge.attach()) as JsonObject
        bridge.submit(event(bridge, page, 1, bridge.selections.first()))
        controller.closeSession()
        assertNull(applySimpleperfWebSelection(controller, commands.last()))
        bridge.close()
    }

    @Test
    fun `loopback transport requires token exact host origin contenttype and bounded requests`() = withReport { _, state ->
        val report = (state.loadState as ReportLoadState.Ready).report
        val bridge = SimpleperfWebBridgeSession(report, state, 1) { true }
        val site = Files.createTempDirectory("aps-bridge-site-")
        Files.writeString(site.resolve("index.html"), "<html><body>Profiler</body></html>")
        val profile = Files.write(site.resolve("perf_data.json.gz"), byteArrayOf(1, 2, 3))
        try {
            FirefoxProfilerLocalServer(site, profile, bridge).use { server ->
                val page = server.start()
                val origin = "http://${page.authority}"
                val denied = http(page, "GET", "/aps-ai/context", emptyMap())
                assertTrue(denied.startsWith("HTTP/1.1 403"))
                val context = http(page, "GET", "/aps-ai/context", mapOf("X-APS-Bridge-Token" to bridge.token))
                assertTrue(context.startsWith("HTTP/1.1 200"))
                assertFalse(context.contains("Access-Control-Allow-Origin: *"))
                val parsed = Json.parseToJsonElement(context.substringAfter("\r\n\r\n")) as JsonObject
                val body = event(bridge, parsed, 1, bridge.selections.first()).decodeToString()
                val headers = mapOf("X-APS-Bridge-Token" to bridge.token, "Origin" to origin, "Content-Type" to "application/json")
                assertTrue(http(page, "POST", "/aps-ai/event", headers, body).contains("accepted"))
                assertTrue(http(page, "POST", "/aps-ai/event", headers + ("Origin" to "https://evil.invalid"), body).startsWith("HTTP/1.1 403"))
                assertTrue(http(page, "POST", "/aps-ai/event", headers + ("Content-Type" to "text/plain"), body).startsWith("HTTP/1.1 413"))
                assertTrue(http(page, "GET", "/aps-ai/result", headers + ("Host" to "evil.invalid")).startsWith("HTTP/1.1 403"))
                assertTrue(http(page, "POST", "/aps-ai/event", headers, " ".repeat(MAX_BRIDGE_BYTES + 1)).startsWith("HTTP/1.1 413"))
                val index = http(page, "GET", page.rawPath, emptyMap())
                assertTrue(index.contains("canonical host selection"))
                assertTrue(index.contains("Firefox canvas selection mapping is unavailable"))
                assertFalse(index.contains("apiKey"))
            }
            assertEquals(SimpleperfWebStatus.CANCELLED, bridge.result().status)
        } finally { site.toFile().deleteRecursively() }
    }

    @Test
    fun `replacement closes old session and host shutdown cannot reopen it`() = withReport { _, state ->
        val report = (state.loadState as ReportLoadState.Ready).report
        val host = SimpleperfWebBridgeHost()
        val old = host.open(report, state)
        val oldPage = Json.parseToJsonElement(old.attach()) as JsonObject
        val oldEvent = event(old, oldPage, 1, old.selections.first())
        assertEquals(SimpleperfWebStatus.ACCEPTED, old.submit(oldEvent).status)
        val next = host.open(report, state)
        assertTrue(next.generation > old.generation)
        assertEquals("E_BRIDGE_CLOSED", old.submit(oldEvent).code)
        assertEquals(SimpleperfWebStatus.CANCELLED, old.result().status)
        host.close()
        host.close()
        assertEquals(SimpleperfWebStatus.CANCELLED, next.result().status)
        assertFailsWith<IllegalStateException> { host.open(report, state) }
    }

    @Test
    fun `dispatch sees committed sequence and rejected queue restores previous state`() = withReport { _, state ->
        val report = (state.loadState as ReportLoadState.Ready).report
        var accept = true
        var currentAtDispatch = false
        val bridge = SimpleperfWebBridgeSession(report, state, 1) { command ->
            currentAtDispatch = command.session.isCurrent(command.event)
            accept
        }
        val page = Json.parseToJsonElement(bridge.attach()) as JsonObject
        val selected = bridge.selections.first()
        assertEquals(SimpleperfWebStatus.ACCEPTED, bridge.submit(event(bridge, page, 1, selected)).status)
        assertTrue(currentAtDispatch)
        val previous = bridge.result()
        accept = false
        assertEquals("E_BRIDGE_BUSY", bridge.submit(event(bridge, page, 2, selected)).code)
        assertEquals(previous, bridge.result())
        accept = true
        assertEquals(SimpleperfWebStatus.ACCEPTED, bridge.submit(event(bridge, page, 2, selected)).status)
        bridge.close()
    }

    private fun event(bridge: SimpleperfWebBridgeSession, page: JsonObject, sequence: Long, selection: SimpleperfCanonicalWebSelection): ByteArray = buildJsonObject {
        put("protocolVersion", 1); put("sessionId", bridge.sessionId); put("reportGeneration", bridge.generation)
        put("pageId", page.getValue("pageId").jsonPrimitive.content); put("sequence", sequence)
        put("panel", selection.panel.name); put("cancel", false)
        if (selection.panel == SimpleperfWebPanel.STACK_CHART) { put("callNodeId", JsonNull); put("stackBlockId", selection.id) }
        else { put("callNodeId", selection.id); put("stackBlockId", JsonNull) }
    }.toString().toByteArray()

    private fun withReport(block: suspend (ReportController, ReportState) -> Unit) = runBlocking {
        val root = Files.createTempDirectory("aps-web-bridge-fixture-")
        val controller = ReportController(selectInitialThread = true, matchFirefoxSampleWeights = true)
        try {
            controller.openSession(FirefoxFlameGraphFixtures.writeCompatibilitySession(root))
            block(controller, controller.state.value)
        } finally { controller.close(); root.toFile().deleteRecursively() }
    }

    private fun http(page: URI, method: String, path: String, headers: Map<String, String>, body: String = ""): String = Socket(page.host, page.port).use { socket ->
        socket.soTimeout = 3000
        val bytes = body.toByteArray()
        val actual = linkedMapOf("Host" to page.authority, "Accept" to "text/html", "Connection" to "close").apply { putAll(headers) }
        if (method == "POST") actual["Content-Length"] = bytes.size.toString()
        val request = "$method $path HTTP/1.1\r\n" + actual.entries.joinToString("") { "${it.key}: ${it.value}\r\n" } + "\r\n"
        socket.getOutputStream().write(request.toByteArray() + bytes)
        socket.getInputStream().readBytes().decodeToString()
    }
}

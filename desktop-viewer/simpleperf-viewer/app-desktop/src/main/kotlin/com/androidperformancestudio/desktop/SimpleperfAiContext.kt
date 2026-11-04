package com.androidperformancestudio.desktop

import com.androidperformancestudio.application.ReportData
import com.androidperformancestudio.application.ReportLoadState
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.application.ReportTab
import com.androidperformancestudio.storage.ThreadSummary
import com.androidperformancestudio.ui.UiAiContext
import com.androidperformancestudio.ui.UiAiEvidence
import com.androidperformancestudio.ui.uiAiEvidenceId
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Only a ready canonical report can supply the current global AI context. */
public fun simpleperfUiAiContext(state: ReportState): UiAiContext? {
    val report = (state.loadState as? ReportLoadState.Ready)?.report ?: return null
    return try {
        simpleperfUiAiContext(report, state)
    } catch (_: IllegalArgumentException) {
        null
    }
}

/** Shared minimized evidence for the legacy production request and global conversation entry. */
public fun simpleperfUiAiContext(
    report: ReportData,
    state: ReportState,
): UiAiContext {
    val selected = hasSimpleperfSelection(state)
    val requestedNode =
        when (state.selectedTab) {
            ReportTab.CALL_TREE, ReportTab.FLAME_GRAPH -> state.workspace.selections.callNodeId != null
            ReportTab.STACK_CHART -> state.workspace.selections.stackChartBlockId != null
            ReportTab.TOP_FUNCTIONS -> state.workspace.selections.topFunctionKey != null
            else -> false
        }
    val extracted = extractSimpleperfEvidence(report, state)
    require(extracted.isNotEmpty()) { "E_EVIDENCE_UNAVAILABLE" }
    // The extractor's report fallback must never authorize an expired selected object as summary.
    if (requestedNode) {
        require(extracted.size == 1 && !extracted.single().id.matches(Regex("simpleperf:function:[0-9]+"))) {
            "E_SELECTION_UNAVAILABLE"
        }
    }
    val id = if (requestedNode) extracted.single().id else "simpleperf:summary"
    require(id.length <= 256) { "E_EVIDENCE_ID" }
    var retained = extracted.size
    var payload: String
    do {
        payload = cpuSummary(report, state, extracted.take(retained), extracted.size - retained, selected, requestedNode).toString()
        if (payload.toByteArray(Charsets.UTF_8).size <= SIMPLEPERF_AI_MAX_PAYLOAD_BYTES) break
        retained--
    } while (retained > 0)
    require(retained > 0 && payload.toByteArray(Charsets.UTF_8).size <= SIMPLEPERF_AI_MAX_PAYLOAD_BYTES) { "E_EVIDENCE_BUDGET" }
    val fact =
        UiAiEvidence(
            id = id,
            kind = "simpleperf-hotspots",
            summary =
                if (requestedNode) {
                    extracted.single().symbolName.take(
                        MAX_SYMBOL_CHARS,
                    )
                } else {
                    "CPU hotspot summary ($retained functions)"
                },
            payloadJson = payload,
        )
    return UiAiContext("simpleperf", "Simpleperf ${state.selectedTab.name}", selected, listOf(fact))
}

internal const val SIMPLEPERF_AI_MAX_PAYLOAD_BYTES = 8 * 1024
private const val MAX_SYMBOL_CHARS = 128
private const val MAX_RESOURCE_CHARS = 64
private const val MAX_FILTER_ITEMS = 8
private const val MAX_EVENTS = 4
private const val MAX_THREADS = 3

private fun cpuSummary(
    report: ReportData,
    state: ReportState,
    hotspots: List<SimpleperfPerformanceEvidence>,
    budgetOmitted: Int,
    selected: Boolean,
    requestedNode: Boolean,
): JsonObject =
    buildJsonObject {
        put("schemaVersion", 2)
        put(
            "reportRef",
            uiAiEvidenceId(
                "cpu-report",
                report.session.directory
                    .toAbsolutePath()
                    .normalize()
                    .toString(),
            ),
        )
        put("scope", if (selected) "CURRENT_SELECTION" else "REPORT_SUMMARY")
        put("panel", state.selectedTab.name)
        put(
            "weightSemantics",
            "Statistical sample/event weights, not exact method or wall-clock durations. No blocking, ANR or jank cause is proven.",
        )
        put(
            "view",
            buildJsonObject {
                val committed = (report.stackChart as? com.androidperformancestudio.storage.PanelProjection.Ready)?.value?.committedAnalysis
                val graph =
                    if (state.selectedTab == ReportTab.STACK_CHART &&
                        requestedNode
                    ) {
                        committed ?: report.flameGraph
                    } else {
                        report.flameGraph
                    }
                val blockWithoutMetrics = state.selectedTab == ReportTab.STACK_CHART && requestedNode && committed == null
                put("weightType", if (blockWithoutMetrics) "UNKNOWN" else graph.weightType.name)
                if (!blockWithoutMetrics) put("totalWeight", graph.totalWeight)
                put("direction", graph.query.direction.name)
                put("implementation", graph.query.implementation.name)
                put("searchFiltered", graph.query.searchText.isNotBlank())
                put("transformCount", graph.query.transforms.size)
                graph.query.previewRange?.let {
                    put("previewStartNanosInclusive", it.startNanosInclusive)
                    put("previewEndNanosExclusive", it.endNanosExclusive)
                }
            },
        )
        put(
            "range",
            buildJsonObject {
                put("scope", "COMMITTED_RANGE")
                (state.filter.startNanosInclusive ?: report.overview.startNanos)?.let { put("startNanosInclusive", it) }
                (
                    state.filter.endNanosExclusive ?: report.overview.endNanosInclusive
                        ?.takeIf {
                            it < Long.MAX_VALUE
                        }?.plus(1)
                )?.let { put("endNanosExclusive", it) }
                put("sampleCount", report.overview.sampleCount)
                put("eventWeight", report.overview.totalEventWeight)
                put("threadCount", report.overview.threadCount)
                put("processCount", report.overview.processCount)
            },
        )
        val threadIds = state.filter.threadIds.sorted()
        val eventTypes = (state.filter.eventTypes.ifEmpty { report.overview.eventTypes.toSet() }).sorted()
        put(
            "filters",
            buildJsonObject {
                put("threadIds", JsonArray(threadIds.take(MAX_FILTER_ITEMS).map(::JsonPrimitive)))
                put("omittedThreadIdCount", (threadIds.size - MAX_FILTER_ITEMS).coerceAtLeast(0))
                put("eventTypes", JsonArray(eventTypes.take(MAX_EVENTS).map { JsonPrimitive(it.take(MAX_SYMBOL_CHARS)) }))
                put("eventLabelsTruncated", eventTypes.take(MAX_EVENTS).any { it.length > MAX_SYMBOL_CHARS })
                put("omittedEventTypeCount", (eventTypes.size - MAX_EVENTS).coerceAtLeast(0))
                put("mixedEvents", eventTypes.size > 1)
            },
        )
        put(
            "quality",
            buildJsonObject {
                put("scope", "SESSION")
                put("sampleCount", report.quality.sampleCount)
                put("reportedSampleCount", report.quality.reportedSampleCount)
                put("lostSampleCount", report.quality.lostSampleCount)
                put("unwindErrorSamples", report.quality.unwindErrorSamples)
                put("unknownSymbolSamples", report.quality.unknownSymbolSamples)
                put("emptyStackSamples", report.quality.emptyStackSamples)
                put("unknownRecords", report.quality.unknownRecords)
            },
        )
        put(
            "hotspots",
            JsonArray(
                hotspots.map { item ->
                    buildJsonObject {
                        val resourceName = item.resource.substringAfterLast('/').substringAfterLast('\\')
                        put("id", item.id)
                        put("symbol", item.symbolName.take(MAX_SYMBOL_CHARS))
                        put("resourceName", resourceName.take(MAX_RESOURCE_CHARS))
                        put("labelTruncated", item.symbolName.length > MAX_SYMBOL_CHARS || resourceName.length > MAX_RESOURCE_CHARS)
                        if (item.symbolName.length > MAX_SYMBOL_CHARS || resourceName.length > MAX_RESOURCE_CHARS) {
                            put("labelRef", uiAiEvidenceId("cpu-symbol", item.symbolName, item.resource))
                        }
                        put("implementation", item.implementation)
                        if (state.selectedTab == ReportTab.STACK_CHART && requestedNode) {
                            val snapshot = (report.stackChart as? com.androidperformancestudio.storage.PanelProjection.Ready)?.value
                            val block =
                                state.workspace.selections.stackChartBlockId
                                    ?.let { snapshot?.blockById(it) }
                            requireNotNull(block) { "E_SELECTION_UNAVAILABLE" }
                            put("blockWeight", block.weight)
                            put("startNanosInclusive", block.startNanos)
                            put("endNanosExclusive", block.endNanosExclusive)
                            put("scope", "SAMPLED_STACK_BLOCK_NOT_METHOD_INVOCATION")
                        } else {
                            put("inclusiveWeight", item.inclusiveWeight)
                            put("exclusiveWeight", item.exclusiveWeight)
                            put("sampleCount", item.sampleCount)
                            put("threadCount", item.threadCount)
                        }
                    }
                },
            ),
        )
        put("hotspotLimit", SIMPLEPERF_AI_HOTSPOT_LIMIT)
        put("availableTopFunctionCount", report.topFunctions.size)
        put("omittedHotspotCount", if (requestedNode) 0 else (report.topFunctions.size - hotspots.size).coerceAtLeast(0))
        put("budgetOmittedHotspotCount", budgetOmitted)
        put("hotspotCoverage", if (requestedNode) "SELECTED_OBJECT_ONLY" else "LOADED_TOP_FUNCTIONS_ONLY")
        val threads =
            report.topThreads.sortedWith(
                compareByDescending<ThreadSummary> {
                    it.totalEventCount
                }.thenBy { it.processId }.thenBy { it.threadId },
            )
        put(
            "threads",
            buildJsonObject {
                put("scope", "COMMITTED_RANGE_NOT_SELECTED_FUNCTION")
                put(
                    "items",
                    JsonArray(
                        threads.take(MAX_THREADS).map { thread ->
                            buildJsonObject {
                                put("processId", thread.processId)
                                put("threadId", thread.threadId)
                                put("role", threadRole(thread.name))
                                put("sampleCount", thread.sampleCount)
                                put("eventWeight", thread.totalEventCount)
                            }
                        },
                    ),
                )
                put("omittedCount", (threads.size - MAX_THREADS).coerceAtLeast(0))
            },
        )
    }

private fun threadRole(name: String): String =
    when {
        name.equals("main", ignoreCase = true) -> "MAIN"
        name.contains("RenderThread", ignoreCase = true) -> "RENDER"
        name.startsWith("Binder", ignoreCase = true) || name.startsWith("HwBinder", ignoreCase = true) -> "BINDER"
        name.contains("DefaultDispatcher", ignoreCase = true) || name.contains("pool-", ignoreCase = true) -> "WORKER"
        else -> "OTHER"
    }

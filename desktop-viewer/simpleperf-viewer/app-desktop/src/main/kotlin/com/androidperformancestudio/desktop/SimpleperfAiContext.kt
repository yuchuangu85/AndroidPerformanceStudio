package com.androidperformancestudio.desktop

import com.androidperformancestudio.application.ReportData
import com.androidperformancestudio.application.ReportLoadState
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.application.ReportTab
import com.androidperformancestudio.ui.UiAiContext
import com.androidperformancestudio.ui.UiAiEvidence
import kotlinx.serialization.json.JsonArray
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
public fun simpleperfUiAiContext(report: ReportData, state: ReportState): UiAiContext {
    val selected = hasSimpleperfSelection(state)
    val requestedNode = when (state.selectedTab) {
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
    val facts = extracted.map { item ->
        UiAiEvidence(
            id = item.id,
            kind = "simpleperf-hotspots",
            summary = item.symbolName.take(512),
            payloadJson = buildJsonObject {
                put("symbol", item.symbolName)
                put("resourceName", item.resource.substringAfterLast('/').substringAfterLast('\\'))
                put("implementation", item.implementation)
                put("inclusiveWeight", item.inclusiveWeight)
                put("exclusiveWeight", item.exclusiveWeight)
                put("sampleCount", item.sampleCount)
                put("threadCount", item.threadCount)
                item.startNanosInclusive?.let { put("startNanosInclusive", it) }
                item.endNanosExclusive?.let { put("endNanosExclusive", it) }
                put("threadIds", JsonArray(item.selectedThreadIds.map(::JsonPrimitive)))
                put("eventTypes", JsonArray(item.selectedEventTypes.map(::JsonPrimitive)))
                put("scope", if (selected) "CURRENT_SELECTION" else "REPORT_SUMMARY")
                put("summaryLimit", 20)
                put("availableTopFunctionCount", report.topFunctions.size)
            }.toString(),
        )
    }
    return UiAiContext("simpleperf", "Simpleperf ${state.selectedTab.name}", selected, facts)
}

package com.androidperformancestudio.desktop

import com.androidperformancestudio.application.ReportData
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.application.ReportTab
import com.androidperformancestudio.storage.PanelProjection

fun interface SimpleperfAiAnalysisClient {
    suspend fun analyze(
        report: ReportData,
        state: ReportState,
        includeSourceSnippets: Boolean,
    ): SimpleperfAiAnalysisReport
}

data class SimpleperfAiAnalysisReport(
    val model: String,
    val summary: String,
    val findings: List<SimpleperfAiFinding>,
)

data class SimpleperfAiFinding(
    val title: String,
    val explanation: String,
    val recommendation: String,
    val confidence: Float,
    val sourceCandidateIds: List<String>,
)

data class SimpleperfPerformanceEvidence(
    val id: String,
    val symbolName: String,
    val resource: String,
    val implementation: String,
    val inclusiveWeight: Long,
    val exclusiveWeight: Long,
    val sampleCount: Long,
    val threadCount: Long,
    val currentSelection: Boolean = false,
    val startNanosInclusive: Long? = null,
    val endNanosExclusive: Long? = null,
    val selectedThreadIds: List<Int> = emptyList(),
    val selectedEventTypes: List<String> = emptyList(),
)

fun extractSimpleperfEvidence(
    report: ReportData,
    state: ReportState,
    limit: Int = 20,
): List<SimpleperfPerformanceEvidence> {
    val evidence =
        selectedSimpleperfEvidence(report, state)?.let(::listOf)
            ?: report.topFunctions.take(limit).mapIndexed { index, function ->
                SimpleperfPerformanceEvidence(
                    id = "simpleperf:function:$index",
                    symbolName = function.symbolName,
                    resource = function.filePath,
                    implementation = inferImplementation(function.filePath),
                    inclusiveWeight = function.inclusiveWeight,
                    exclusiveWeight = function.exclusiveWeight,
                    sampleCount = function.sampleCount,
                    threadCount = function.threadCount,
                )
            }
    return evidence.map { item ->
        item.copy(
            currentSelection = hasSimpleperfSelection(state),
            startNanosInclusive = state.filter.startNanosInclusive,
            endNanosExclusive = state.filter.endNanosExclusive,
            selectedThreadIds = state.filter.threadIds.sorted(),
            selectedEventTypes = state.filter.eventTypes.sorted(),
        )
    }
}

fun hasSimpleperfSelection(state: ReportState): Boolean =
    when (state.selectedTab) {
        ReportTab.TOP_FUNCTIONS -> state.workspace.selections.topFunctionKey != null
        ReportTab.CALL_TREE, ReportTab.FLAME_GRAPH -> state.workspace.selections.callNodeId != null
        ReportTab.STACK_CHART -> state.workspace.selections.stackChartBlockId != null
        else -> false
    } ||
        state.filter.startNanosInclusive != null ||
        state.filter.endNanosExclusive != null ||
        state.filter.threadIds.isNotEmpty() ||
        state.filter.eventTypes.isNotEmpty()

private fun selectedSimpleperfEvidence(
    report: ReportData,
    state: ReportState,
): SimpleperfPerformanceEvidence? =
    when (state.selectedTab) {
        ReportTab.TOP_FUNCTIONS -> selectedTopFunctionEvidence(report, state)
        ReportTab.CALL_TREE, ReportTab.FLAME_GRAPH -> selectedCallNodeEvidence(report, state)
        ReportTab.STACK_CHART -> selectedStackBlockEvidence(report, state)

        else -> null
    }

private fun selectedTopFunctionEvidence(
    report: ReportData,
    state: ReportState,
): SimpleperfPerformanceEvidence? {
    val key = state.workspace.selections.topFunctionKey ?: return null
    return report.topFunctions.firstOrNull { it.symbolName == key }?.let { function ->
        SimpleperfPerformanceEvidence(
            id = "simpleperf:function:selected",
            symbolName = function.symbolName,
            resource = function.filePath,
            implementation = inferImplementation(function.filePath),
            inclusiveWeight = function.inclusiveWeight,
            exclusiveWeight = function.exclusiveWeight,
            sampleCount = function.sampleCount,
            threadCount = function.threadCount,
        )
    }
}

private fun selectedCallNodeEvidence(
    report: ReportData,
    state: ReportState,
): SimpleperfPerformanceEvidence? {
    val nodeId = state.workspace.selections.callNodeId
    val nodeIndex = nodeId?.let(report.flameGraph.callNodes::indexOf)
    val frame = nodeIndex?.let(report.flameGraph.callNodes::frameAt)
    return if (nodeId != null && nodeIndex != null && frame != null) {
        SimpleperfPerformanceEvidence(
            id = "simpleperf:call:${nodeId.value}",
            symbolName = frame.symbolName,
            resource = frame.resource,
            implementation = frame.implementation.name,
            inclusiveWeight = report.flameGraph.callNodes.inclusiveWeightAt(nodeIndex) ?: 0,
            exclusiveWeight = report.flameGraph.callNodes.selfWeightAt(nodeIndex) ?: 0,
            sampleCount = report.flameGraph.callNodes.sampleCountAt(nodeIndex) ?: 0,
            threadCount = (report.flameGraph.callNodes.threadCountAt(nodeIndex) ?: 0).toLong(),
        )
    } else {
        null
    }
}

private fun selectedStackBlockEvidence(
    report: ReportData,
    state: ReportState,
): SimpleperfPerformanceEvidence? {
    val blockId = state.workspace.selections.stackChartBlockId
    val snapshot = (report.stackChart as? PanelProjection.Ready)?.value
    val block = snapshot?.blocks?.firstOrNull { it.id == blockId }
    val frame = block?.let { snapshot.framesById[it.frameId] }
    return if (block != null && frame != null) {
        SimpleperfPerformanceEvidence(
            id = "simpleperf:stack:${block.id.value}",
            symbolName = frame.symbolName,
            resource = frame.resource,
            implementation = frame.implementation.name,
            inclusiveWeight = block.weight,
            exclusiveWeight = block.weight,
            sampleCount = 1,
            threadCount = 1,
        )
    } else {
        null
    }
}

private fun inferImplementation(resource: String): String =
    if (resource.endsWith(".so") || resource.startsWith("[kernel")) "NATIVE" else "MANAGED"

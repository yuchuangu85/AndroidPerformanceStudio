package com.androidperformancestudio.desktop

import com.androidperformancestudio.application.ReportData
import com.androidperformancestudio.application.ReportState

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
)

fun extractSimpleperfEvidence(
    report: ReportData,
    state: ReportState,
    limit: Int = 20,
): List<SimpleperfPerformanceEvidence> {
    state.workspace.selections.callNodeId?.let { nodeId ->
        val nodeIndex = report.flameGraph.callNodes.indexOf(nodeId)
        val frame = nodeIndex?.let(report.flameGraph.callNodes::frameAt)
        if (nodeIndex != null && frame != null) {
            return listOf(
                SimpleperfPerformanceEvidence(
                    id = "simpleperf:call:${nodeId.value}",
                    symbolName = frame.symbolName,
                    resource = frame.resource,
                    implementation = frame.implementation.name,
                    inclusiveWeight = report.flameGraph.callNodes.inclusiveWeightAt(nodeIndex) ?: 0,
                    exclusiveWeight = report.flameGraph.callNodes.selfWeightAt(nodeIndex) ?: 0,
                    sampleCount = report.flameGraph.callNodes.sampleCountAt(nodeIndex) ?: 0,
                    threadCount = (report.flameGraph.callNodes.threadCountAt(nodeIndex) ?: 0).toLong(),
                ),
            )
        }
    }
    val selectedFunction = state.workspace.selections.topFunctionKey
    val functions =
        selectedFunction?.let { key -> report.topFunctions.filter { it.symbolName == key } }
            ?: report.topFunctions.take(limit)
    return functions.take(limit).mapIndexed { index, function ->
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
}

private fun inferImplementation(resource: String): String =
    if (resource.endsWith(".so") || resource.startsWith("[kernel")) "NATIVE" else "MANAGED"

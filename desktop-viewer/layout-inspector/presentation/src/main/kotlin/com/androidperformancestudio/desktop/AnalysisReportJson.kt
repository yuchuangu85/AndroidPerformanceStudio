package com.androidperformancestudio.desktop

import com.androidperformancestudio.analysis.AnalysisReport
import com.androidperformancestudio.analysis.Finding
import com.androidperformancestudio.analysis.LayoutMetrics
import com.androidperformancestudio.analysis.Severity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class AnalysisReportJson(
    private val json: Json = defaultArchiveJson(),
) {
    fun encode(report: AnalysisReport): String = json.encodeToString(report.toDto())

    fun decode(content: String): AnalysisReport = json.decodeFromString<AnalysisReportDto>(content).toDomain()
}

@Serializable
private data class AnalysisReportDto(
    val metrics: LayoutMetricsDto,
    val findings: List<FindingDto>,
)

@Serializable
private data class LayoutMetricsDto(
    val nodeCount: Int,
    val maxDepth: Int,
    val widestLevel: Int,
)

@Serializable
private data class FindingDto(
    val ruleId: String,
    val severity: String,
    val nodeId: String,
    val message: String,
    val arguments: Map<String, String> = emptyMap(),
)

private fun AnalysisReport.toDto() = AnalysisReportDto(
    metrics = LayoutMetricsDto(metrics.nodeCount, metrics.maxDepth, metrics.widestLevel),
    findings = findings.map {
        FindingDto(
            ruleId = it.ruleId,
            severity = it.severity.name,
            nodeId = it.nodeId,
            message = it.message,
            arguments = it.arguments,
        )
    },
)

private fun AnalysisReportDto.toDomain() = AnalysisReport(
    metrics = LayoutMetrics(metrics.nodeCount, metrics.maxDepth, metrics.widestLevel),
    findings = findings.map {
        Finding(
            ruleId = it.ruleId,
            severity = Severity.valueOf(it.severity),
            nodeId = it.nodeId,
            message = it.message,
            arguments = it.arguments,
        )
    },
)

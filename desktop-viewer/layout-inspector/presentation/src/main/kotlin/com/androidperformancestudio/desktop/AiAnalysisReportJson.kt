package com.androidperformancestudio.desktop

import com.androidperformancestudio.analysis.AiAnalysisReport
import com.androidperformancestudio.analysis.AiFinding
import com.androidperformancestudio.analysis.Severity
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

internal class AiAnalysisReportJson(
    private val json: Json = defaultArchiveJson(),
) {
    fun encode(report: AiAnalysisReport): String = json.encodeToString(report.toDto())

    fun decode(content: String): AiAnalysisReport = json.decodeFromString<AiAnalysisReportDto>(content).toDomain()
}

@Serializable
private data class AiAnalysisReportDto(
    val model: String,
    val summary: String,
    val findings: List<AiFindingDto> = emptyList(),
)

@Serializable
private data class AiFindingDto(
    val ruleId: String,
    val severity: String,
    val nodeId: String,
    val title: String,
    val message: String,
    val recommendation: String,
    val confidence: Float,
)

private fun AiAnalysisReport.toDto() = AiAnalysisReportDto(
    model = model,
    summary = summary,
    findings = findings.map {
        AiFindingDto(
            ruleId = it.ruleId,
            severity = it.severity.name,
            nodeId = it.nodeId,
            title = it.title,
            message = it.message,
            recommendation = it.recommendation,
            confidence = it.confidence,
        )
    },
)

private fun AiAnalysisReportDto.toDomain() = AiAnalysisReport(
    model = model,
    summary = summary,
    findings = findings.map {
        AiFinding(
            ruleId = it.ruleId,
            severity = Severity.valueOf(it.severity),
            nodeId = it.nodeId,
            title = it.title,
            message = it.message,
            recommendation = it.recommendation,
            confidence = it.confidence,
        )
    },
)

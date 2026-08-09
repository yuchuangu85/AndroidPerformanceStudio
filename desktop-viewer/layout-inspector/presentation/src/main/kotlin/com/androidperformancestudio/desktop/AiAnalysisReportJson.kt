package com.androidperformancestudio.desktop

import com.androidperformancestudio.analysis.AiAnalysisReport
import com.androidperformancestudio.analysis.AiAnalysisProvenance
import com.androidperformancestudio.analysis.AiEvidenceReference
import com.androidperformancestudio.analysis.AiFinding
import com.androidperformancestudio.analysis.AiSourceCandidateReference
import com.androidperformancestudio.analysis.Severity
import kotlinx.serialization.Serializable
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
    val provenance: AiAnalysisProvenanceDto? = null,
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
    val performanceEvidenceIds: List<String> = emptyList(),
    val sourceCandidateIds: List<String> = emptyList(),
)

@Serializable
private data class AiAnalysisProvenanceDto(
    val sessionId: String,
    val provider: String,
    val scope: String,
    val promptVersion: String,
    val payloadPolicyVersion: String,
    val sourceSnapshotIds: List<String> = emptyList(),
    val buildEvidenceBundleIds: List<String> = emptyList(),
    val evidence: List<AiEvidenceReferenceDto> = emptyList(),
    val sourceCandidates: List<AiSourceCandidateReferenceDto> = emptyList(),
)

@Serializable
private data class AiEvidenceReferenceDto(
    val id: String,
    val kind: String,
    val summary: String,
    val payloadHash: String,
)

@Serializable
private data class AiSourceCandidateReferenceDto(
    val id: String,
    val relativePath: String,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val resolutionConfidence: String,
    val contentHash: String? = null,
    val workspaceId: String? = null,
    val snapshotId: String? = null,
    val providerKind: String? = null,
    val repositoryIdentity: String? = null,
    val revision: String? = null,
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
            performanceEvidenceIds = it.performanceEvidenceIds,
            sourceCandidateIds = it.sourceCandidateIds,
        )
    },
    provenance = provenance?.toDto(),
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
            performanceEvidenceIds = it.performanceEvidenceIds,
            sourceCandidateIds = it.sourceCandidateIds,
        )
    },
    provenance = provenance?.toDomain(),
)

private fun AiAnalysisProvenance.toDto() = AiAnalysisProvenanceDto(
    sessionId = sessionId,
    provider = provider,
    scope = scope,
    promptVersion = promptVersion,
    payloadPolicyVersion = payloadPolicyVersion,
    sourceSnapshotIds = sourceSnapshotIds,
    buildEvidenceBundleIds = buildEvidenceBundleIds,
    evidence = evidence.map { AiEvidenceReferenceDto(it.id, it.kind, it.summary, it.payloadHash) },
    sourceCandidates = sourceCandidates.map {
        AiSourceCandidateReferenceDto(
            it.id,
            it.relativePath,
            it.startLine,
            it.endLine,
            it.resolutionConfidence,
            it.contentHash,
            it.workspaceId,
            it.snapshotId,
            it.providerKind,
            it.repositoryIdentity,
            it.revision,
        )
    },
)

private fun AiAnalysisProvenanceDto.toDomain() = AiAnalysisProvenance(
    sessionId = sessionId,
    provider = provider,
    scope = scope,
    promptVersion = promptVersion,
    payloadPolicyVersion = payloadPolicyVersion,
    sourceSnapshotIds = sourceSnapshotIds,
    buildEvidenceBundleIds = buildEvidenceBundleIds,
    evidence = evidence.map { AiEvidenceReference(it.id, it.kind, it.summary, it.payloadHash) },
    sourceCandidates = sourceCandidates.map {
        AiSourceCandidateReference(
            it.id,
            it.relativePath,
            it.startLine,
            it.endLine,
            it.resolutionConfidence,
            it.contentHash,
            it.workspaceId,
            it.snapshotId,
            it.providerKind,
            it.repositoryIdentity,
            it.revision,
        )
    },
)

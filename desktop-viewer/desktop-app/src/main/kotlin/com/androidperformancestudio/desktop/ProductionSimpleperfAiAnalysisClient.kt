package com.androidperformancestudio.desktop

import com.androidperformancestudio.ai.AiSourceCandidate
import com.androidperformancestudio.ai.AnalysisRequest
import com.androidperformancestudio.ai.PerformanceEvidence
import com.androidperformancestudio.aiplatform.app.createBusinessContext
import com.androidperformancestudio.application.ReportData
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.source.PerformanceEvidenceId
import com.androidperformancestudio.source.SourceResolutionEvidence

internal class ProductionSimpleperfAiAnalysisClient(
    private val source: SourceWorkspaceRuntime,
    private val coordinator: DesktopAiCoordinator,
) : SimpleperfAiAnalysisClient {
    override suspend fun analyze(report: ReportData, state: ReportState, includeSourceSnippets: Boolean): SimpleperfAiAnalysisReport {
        val request = simpleperfAnalysisRequest(report, state)
        val candidates = if (includeSourceSnippets) {
            val snapshots = source.repository.workspaces().mapNotNull { it.activeSnapshotId }.toSet()
            val evidence = extractSimpleperfEvidence(report, state).mapNotNull { item ->
                item.symbolName.takeIf { it.matches(Regex("[A-Za-z_$][A-Za-z0-9_.$]*")) }?.let { symbol ->
                    SourceResolutionEvidence.TypeName(PerformanceEvidenceId(item.id), symbol.substringBefore('$'))
                }
            }
            source.resolver.resolve(snapshots, evidence).also(source::rememberCandidates).map { candidate ->
                val workspace = source.repository.workspace(candidate.location.workspaceId)
                require(workspace?.allowAiSourceUpload == true) { "E_SOURCE_UNAUTHORIZED" }
                val content = source.service.read(candidate.location).text.lineSequence().toList()
                val anchor = candidate.location.range?.startLine ?: 1
                val start = (anchor - 13).coerceAtLeast(0)
                val end = (anchor + 12).coerceAtMost(content.size)
                val snippet = content.subList(start, end).joinToString("\n")
                require(snippet.toByteArray().size <= 16_384) { "E_SOURCE_LIMIT" }
                AiSourceCandidate(candidate.id.value, candidate.location.relativePath, null, candidate.confidence.name, candidate.reasons, snippet,
                    start + 1, end, candidate.location.contentHash, candidate.indexVersion, candidate.indexComplete)
            }
        } else emptyList()
        val result = coordinator.analyze(request.copy(sourceCandidates = candidates))
        return SimpleperfAiAnalysisReport(result.model, result.summary, result.findings.map { finding ->
            SimpleperfAiFinding(finding.title, finding.explanation, finding.recommendation, finding.analysisConfidence, finding.sourceCandidateIds)
        })
    }
}

internal fun simpleperfAnalysisRequest(report: ReportData, state: ReportState): AnalysisRequest {
    val context = simpleperfUiAiContext(report, state)
    return createBusinessContext(
        context.key,
        context.title,
        context.evidence.map { PerformanceEvidence(it.id, it.kind, it.summary, it.payloadJson) },
        context.currentSelection,
    ).request
}

package com.androidperformancestudio.desktop

import com.androidperformancestudio.ai.AiSourceCandidate
import com.androidperformancestudio.ai.AnalysisRequest
import com.androidperformancestudio.ai.AnalysisScope
import com.androidperformancestudio.ai.AnalysisScopeKind
import com.androidperformancestudio.ai.AnalysisSession
import com.androidperformancestudio.ai.AnalysisSessionId
import com.androidperformancestudio.ai.AnalysisSessionStatus
import com.androidperformancestudio.ai.OpenAiAnalysisGateway
import com.androidperformancestudio.ai.OpenAiResponsesClient
import com.androidperformancestudio.ai.PerformanceEvidence
import com.androidperformancestudio.ai.ProfilerKind
import com.androidperformancestudio.application.ReportData
import com.androidperformancestudio.application.ReportState
import com.androidperformancestudio.source.PerformanceEvidenceId
import com.androidperformancestudio.source.ResolutionCandidate
import com.androidperformancestudio.source.SourceResolutionEvidence
import java.time.Instant

internal class SourceAwareSimpleperfAiAnalysisClient(
    private val runtime: SourceWorkspaceRuntime,
) : SimpleperfAiAnalysisClient {
    override suspend fun analyze(
        report: ReportData,
        state: ReportState,
        includeSourceSnippets: Boolean,
    ): SimpleperfAiAnalysisReport {
        val extracted = extractSimpleperfEvidence(report, state)
        require(extracted.isNotEmpty()) { "The current Simpleperf report has no analyzable functions" }
        val snapshots = runtime.service.workspaces.value.mapNotNull { it.activeSnapshotId }.toSet()
        val resolutionEvidence = extracted.map { item ->
            val id = PerformanceEvidenceId(item.id)
            if (item.implementation == "NATIVE") {
                SourceResolutionEvidence.NativeSymbol(
                    id = id,
                    symbolName = item.symbolName,
                    libraryPath = item.resource,
                )
            } else {
                val normalized = item.symbolName.substringBefore('(').substringBefore(" [")
                SourceResolutionEvidence.ManagedSymbol(
                    id = id,
                    className = normalized.substringBeforeLast('.', "").takeIf(String::isNotBlank),
                    methodName = normalized.substringAfterLast('.').substringAfterLast("::"),
                    resourcePath = item.resource,
                )
            }
        }
        val candidates = runtime.resolver.resolve(snapshots, resolutionEvidence)
        runtime.rememberCandidates(candidates)
        val sessionId = AnalysisSessionId.create()
        val currentSelection = extracted.any(SimpleperfPerformanceEvidence::currentSelection)
        val scope = AnalysisScope(
            kind = if (currentSelection) AnalysisScopeKind.CURRENT_SELECTION else AnalysisScopeKind.REPORT_SUMMARY,
            description =
                if (currentSelection) {
                    extracted.singleOrNull()?.symbolName ?: "Selected Simpleperf range"
                } else {
                    "Top ${extracted.size} Simpleperf hotspots"
                },
        )
        val initialSession = AnalysisSession(
            id = sessionId,
            originProfiler = ProfilerKind.SIMPLEPERF,
            scope = scope,
            model = null,
            promptVersion = PROMPT_VERSION,
            payloadPolicyVersion = PAYLOAD_POLICY_VERSION,
            sourceSnapshotIds = snapshots.map { it.value },
            buildEvidenceBundleIds = emptyList(),
            status = AnalysisSessionStatus.RUNNING,
            createdAt = Instant.now(),
            provider = "OpenAI Responses",
        )
        runtime.analysisSessions.saveSession(initialSession)
        return try {
            val result = gateway().analyze(
                AnalysisRequest(
                    sessionId = sessionId,
                    originProfiler = ProfilerKind.SIMPLEPERF,
                    scope = scope,
                    evidence = extracted.map { item ->
                        PerformanceEvidence(
                            id = item.id,
                            kind = "simpleperf-hotspot",
                            summary = "${item.symbolName}: ${item.sampleCount} samples",
                            structuredPayload = simpleperfEvidenceJson(item),
                        )
                    },
                    sourceCandidates = candidates.take(MAX_CANDIDATES).map { candidate ->
                        AiSourceCandidate(
                            id = candidate.id.value,
                            relativePath = candidate.location.relativePath,
                            symbol = null,
                            resolutionConfidence = candidate.confidence.name,
                            reasons = candidate.reasons,
                            sourceSnippet =
                                if (includeSourceSnippets && sourceUploadAllowed(candidate)) snippet(candidate) else null,
                            contentHash = candidate.location.contentHash,
                            indexVersion = candidate.indexVersion,
                            indexComplete = candidate.indexComplete,
                        )
                    },
                    promptVersion = PROMPT_VERSION,
                    payloadPolicyVersion = PAYLOAD_POLICY_VERSION,
                ),
            )
            runtime.analysisSessions.saveResult(result)
            SimpleperfAiAnalysisReport(
                model = result.model,
                summary = result.summary,
                findings = result.findings.map { finding ->
                    SimpleperfAiFinding(
                        title = finding.title,
                        explanation = finding.explanation,
                        recommendation = finding.recommendation,
                        confidence = finding.analysisConfidence,
                        sourceCandidateIds = finding.sourceCandidateIds,
                    )
                },
            )
        } catch (failure: Throwable) {
            runtime.analysisSessions.saveSession(
                initialSession.copy(
                    status = AnalysisSessionStatus.FAILED,
                    errorMessage = failure.message ?: failure::class.simpleName,
                ),
            )
            throw failure
        }
    }

    private fun gateway(): OpenAiAnalysisGateway {
        val apiKey = runtime.credential("openai:api-key")
            ?: error("Configure an OpenAI API key in global AI settings before running analysis")
        return OpenAiAnalysisGateway(OpenAiResponsesClient(apiKey, runtime.aiModel(), runtime.aiEndpoint()))
    }

    private suspend fun snippet(candidate: ResolutionCandidate): String? = runCatching {
        val lines = runtime.service.read(candidate.location).text.lineSequence().toList()
        val anchor = candidate.location.range?.startLine ?: 1
        val start = (anchor - 13).coerceAtLeast(0)
        val end = (anchor + 12).coerceAtMost(lines.size)
        lines.subList(start, end).mapIndexed { index, line -> "${start + index + 1}: $line" }.joinToString("\n")
    }.getOrNull()

    private fun sourceUploadAllowed(candidate: ResolutionCandidate): Boolean =
        runtime.service.workspaces.value.any { workspace ->
            workspace.id == candidate.location.workspaceId && workspace.allowAiSourceUpload
        }

    private companion object {
        const val PROMPT_VERSION = "simpleperf-source-v1"
        const val PAYLOAD_POLICY_VERSION = "minimal-snippets-v1"
        const val MAX_CANDIDATES = 40
    }
}

private fun simpleperfEvidenceJson(
    item: SimpleperfPerformanceEvidence,
): String {
    val threadIds = item.selectedThreadIds.joinToString(prefix = "[", postfix = "]")
    val eventTypes = item.selectedEventTypes.joinToString(prefix = "[", postfix = "]") { it.jsonString() }
    return buildString {
        append("""{"symbol":${item.symbolName.jsonString()}""")
        append(""","resource":${item.resource.jsonString()}""")
        append(""","implementation":${item.implementation.jsonString()}""")
        append(""","inclusiveWeight":${item.inclusiveWeight}""")
        append(""","exclusiveWeight":${item.exclusiveWeight}""")
        append(""","samples":${item.sampleCount},"threads":${item.threadCount}""")
        append(""","selection":{"startNanosInclusive":${item.startNanosInclusive}""")
        append(""","endNanosExclusive":${item.endNanosExclusive}""")
        append(""","threadIds":$threadIds,"eventTypes":$eventTypes}}""")
    }
}

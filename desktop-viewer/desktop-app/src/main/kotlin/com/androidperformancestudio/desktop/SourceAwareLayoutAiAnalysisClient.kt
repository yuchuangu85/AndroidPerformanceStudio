package com.androidperformancestudio.desktop

import com.androidperformancestudio.ai.AiSourceCandidate
import com.androidperformancestudio.ai.AnalysisRequest
import com.androidperformancestudio.ai.AnalysisScope
import com.androidperformancestudio.ai.AnalysisScopeKind
import com.androidperformancestudio.ai.AnalysisSession
import com.androidperformancestudio.ai.AnalysisSessionId
import com.androidperformancestudio.ai.AnalysisSessionStatus
import com.androidperformancestudio.ai.AnalysisSeverity
import com.androidperformancestudio.ai.OpenAiAnalysisGateway
import com.androidperformancestudio.ai.OpenAiResponsesClient
import com.androidperformancestudio.ai.PerformanceEvidence
import com.androidperformancestudio.ai.ProfilerKind
import com.androidperformancestudio.analysis.AiAnalysisReport
import com.androidperformancestudio.analysis.AiFinding
import com.androidperformancestudio.analysis.Severity
import com.androidperformancestudio.source.PerformanceEvidenceId
import com.androidperformancestudio.source.ResolutionCandidate
import com.androidperformancestudio.source.SourceResolutionEvidence
import java.time.Instant

internal class SourceAwareLayoutAiAnalysisClient(
    private val runtime: SourceWorkspaceRuntime,
) : AiAnalysisClient {
    override suspend fun analyze(input: AiAnalysisInput): AiAnalysisReport {
        val sessionId = AnalysisSessionId.create()
        val snapshots = runtime.service.workspaces.value.mapNotNull { it.activeSnapshotId }.toSet()
        val performanceEvidence = input.sourceEvidence.map { item ->
            PerformanceEvidence(
                id = evidenceId(item.nodeId),
                kind = "layout-node",
                summary = buildString {
                    append(item.className)
                    item.resourceName?.let { append(" · ").append(it) }
                },
                structuredPayload =
                    """{"nodeId":${item.nodeId.jsonString()},"className":${item.className.jsonString()},"resourceName":${item.resourceName.jsonNullableString()}}""",
            )
        }.ifEmpty {
            listOf(PerformanceEvidence("layout-report", "layout-report", "Layout report summary", input.json))
        }
        val resolutionEvidence = input.sourceEvidence.flatMap { item ->
            val id = PerformanceEvidenceId(evidenceId(item.nodeId))
            buildList {
                item.className.takeIf { it.isNotBlank() }?.let { className ->
                    add(SourceResolutionEvidence.TypeName(id, className.substringBefore('$')))
                }
                item.resourceName?.parseAndroidResource()?.let { (type, name) ->
                    add(SourceResolutionEvidence.AndroidResource(id, type, name))
                }
            }
        }
        val candidates = runtime.resolver.resolve(snapshots, resolutionEvidence)
        runtime.rememberCandidates(candidates)
        val aiCandidates = candidates.take(MAX_CANDIDATES).map { candidate ->
            candidate.toAiCandidate(input.includeSourceSnippets)
        }
        val scope = AnalysisScope(
            kind = if (input.selectedNodeId == null) AnalysisScopeKind.REPORT_SUMMARY else AnalysisScopeKind.CURRENT_SELECTION,
            description = input.selectedNodeId?.let { "Layout node $it" } ?: "Layout report summary",
        )
        val initialSession = AnalysisSession(
            id = sessionId,
            originProfiler = ProfilerKind.LAYOUT_INSPECTOR,
            scope = scope,
            model = null,
            promptVersion = PROMPT_VERSION,
            payloadPolicyVersion = PAYLOAD_POLICY_VERSION,
            sourceSnapshotIds = snapshots.map { it.value },
            buildEvidenceBundleIds = emptyList(),
            status = AnalysisSessionStatus.RUNNING,
            createdAt = Instant.now(),
        )
        runtime.analysisSessions.saveSession(initialSession)
        return try {
            val result = gateway().analyze(
                AnalysisRequest(
                    sessionId = sessionId,
                    originProfiler = ProfilerKind.LAYOUT_INSPECTOR,
                    scope = scope,
                    evidence = performanceEvidence,
                    sourceCandidates = aiCandidates,
                    promptVersion = PROMPT_VERSION,
                    payloadPolicyVersion = PAYLOAD_POLICY_VERSION,
                ),
            )
            runtime.analysisSessions.saveResult(result)
            AiAnalysisReport(
                model = result.model,
                summary = result.summary,
                findings = result.findings.map { finding ->
                    val nodeId = finding.performanceEvidenceIds.firstNotNullOfOrNull { id ->
                        id.removePrefix("layout:").takeIf { id.startsWith("layout:") }
                    } ?: input.selectedNodeId ?: input.sourceEvidence.firstOrNull()?.nodeId.orEmpty()
                    AiFinding(
                        ruleId = finding.id.value,
                        severity = when (finding.severity) {
                            AnalysisSeverity.INFO -> Severity.INFO
                            AnalysisSeverity.WARNING -> Severity.WARNING
                            AnalysisSeverity.ERROR -> Severity.ERROR
                        },
                        nodeId = nodeId,
                        title = finding.title,
                        message = finding.explanation,
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
        val apiKey = runtime.credential(OPENAI_CREDENTIAL_KEY)
            ?: System.getenv("OPENAI_API_KEY")
            ?: error("Configure an OpenAI API key in Source Workspaces before running analysis")
        val model = System.getenv("AGENTPERF_AI_MODEL")?.takeIf(String::isNotBlank) ?: runtime.aiModel()
        val endpoint = System.getenv("OPENAI_BASE_URL")
            ?.trimEnd('/')
            ?.takeIf(String::isNotBlank)
            ?.let { if (it.endsWith("/v1")) "$it/responses" else "$it/v1/responses" }
            ?: runtime.aiEndpoint()
        return OpenAiAnalysisGateway(OpenAiResponsesClient(apiKey, model, endpoint))
    }

    private suspend fun ResolutionCandidate.toAiCandidate(includeSnippet: Boolean): AiSourceCandidate =
        AiSourceCandidate(
            id = id.value,
            relativePath = location.relativePath,
            symbol = null,
            resolutionConfidence = confidence.name,
            reasons = reasons,
            sourceSnippet = if (includeSnippet && sourceUploadAllowed(this)) sourceSnippet(this) else null,
        )

    private fun sourceUploadAllowed(candidate: ResolutionCandidate): Boolean =
        runtime.service.workspaces.value.any { workspace ->
            workspace.id == candidate.location.workspaceId && workspace.allowAiSourceUpload
        }

    private suspend fun sourceSnippet(candidate: ResolutionCandidate): String? = runCatching {
        val content = runtime.service.read(candidate.location).text
        val lines = content.lineSequence().toList()
        val anchor = candidate.location.range?.startLine ?: 1
        val start = (anchor - SNIPPET_CONTEXT_LINES - 1).coerceAtLeast(0)
        val end = (anchor + SNIPPET_CONTEXT_LINES).coerceAtMost(lines.size)
        lines.subList(start, end).mapIndexed { index, line -> "${start + index + 1}: $line" }.joinToString("\n")
    }.getOrNull()

    private companion object {
        const val OPENAI_CREDENTIAL_KEY = "openai:api-key"
        const val PROMPT_VERSION = "layout-source-v1"
        const val PAYLOAD_POLICY_VERSION = "minimal-snippets-v1"
        const val MAX_CANDIDATES = 40
        const val SNIPPET_CONTEXT_LINES = 12
    }
}

private fun evidenceId(nodeId: String): String = "layout:$nodeId"

private fun String?.jsonNullableString(): String = this?.jsonString() ?: "null"

internal fun String.jsonString(): String = buildString(length + 2) {
    append('"')
    this@jsonString.forEach { character ->
        when (character) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(character)
        }
    }
    append('"')
}

private fun String.parseAndroidResource(): Pair<String, String>? {
    val normalized = substringAfterLast(':').removePrefix("@").removePrefix("+")
    val type = normalized.substringBefore('/', "")
    val name = normalized.substringAfter('/', "")
    return if (type.isNotBlank() && name.isNotBlank()) type to name else null
}

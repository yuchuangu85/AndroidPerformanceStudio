package com.androidperformancestudio.desktop

import com.androidperformancestudio.ai.AiAnalysisGateway
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
import com.androidperformancestudio.ai.payloadText
import com.androidperformancestudio.analysis.AiAnalysisReport
import com.androidperformancestudio.analysis.AiAnalysisProvenance
import com.androidperformancestudio.analysis.AiEvidenceReference
import com.androidperformancestudio.analysis.AiFinding
import com.androidperformancestudio.analysis.AiSourceCandidateReference
import com.androidperformancestudio.analysis.Severity
import com.androidperformancestudio.source.PerformanceEvidenceId
import com.androidperformancestudio.source.ResolutionCandidate
import com.androidperformancestudio.source.SourceProviderKind
import com.androidperformancestudio.source.SourceResolutionEvidence
import java.time.Instant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal class SourceAwareLayoutAiAnalysisClient(
    private val runtime: SourceWorkspaceRuntime,
    private val analysisGateway: AiAnalysisGateway? = null,
) : AiAnalysisClient {
    override suspend fun prepare(input: AiAnalysisInput): PreparedAiAnalysis {
        val sessionId = AnalysisSessionId.create()
        val localWorkspaces = runtime.service.workspaces.value.filter { it.config.kind == SourceProviderKind.LOCAL }
        val snapshots = localWorkspaces.mapNotNull { it.activeSnapshotId }.toSet()
        val model = runtime.aiModel()
        val endpoint = runtime.aiEndpoint()
        val performanceEvidence = input.performanceEvidence()
        val candidates = runtime.resolver.resolve(snapshots, input.resolutionEvidence())
        runtime.rememberCandidates(candidates)
        val aiCandidates = if (input.includeSourceSnippets) {
            candidates.map { candidate -> candidate.toAiCandidate() }
        } else {
            emptyList()
        }
        val scope = AnalysisScope(
            kind = if (input.selectedNodeId == null) AnalysisScopeKind.REPORT_SUMMARY else AnalysisScopeKind.CURRENT_SELECTION,
            description = input.selectedNodeId?.let { "Layout node $it" } ?: "Layout report summary",
        )
        val request = AnalysisRequest(
            sessionId = sessionId,
            originProfiler = ProfilerKind.LAYOUT_INSPECTOR,
            scope = scope,
            evidence = performanceEvidence,
            sourceCandidates = aiCandidates,
            promptVersion = PROMPT_VERSION,
            payloadPolicyVersion = PAYLOAD_POLICY_VERSION,
        )
        val payloadBytes = request.payloadText().encodeToByteArray().size
        val unauthorized = input.includeSourceSnippets && candidates.any { !sourceUploadAllowed(it) }
        val unreadable = input.includeSourceSnippets && aiCandidates.any { candidate -> candidate.sourceSnippet == null }
        val blockedReason = when {
            unauthorized -> "Source upload is not authorized for every candidate workspace"
            unreadable -> "One or more approved source snippets could not be read"
            input.omittedSourceEvidenceCount > 0 ->
                "${input.omittedSourceEvidenceCount} source evidence items exceed the report budget; narrow the scope"
            input.treeTruncated -> "Layout tree exceeds the analysis budget; narrow the scope"
            input.includeSourceSnippets && candidates.size > MAX_CANDIDATES ->
                "AI analysis has ${candidates.size} source candidates; narrow the scope below $MAX_CANDIDATES"
            payloadBytes > AI_ANALYSIS_MAX_PAYLOAD_BYTES ->
                "AI analysis payload is $payloadBytes bytes; narrow the scope below $AI_ANALYSIS_MAX_PAYLOAD_BYTES bytes"
            else -> null
        }
        val initialSession = AnalysisSession(
            id = sessionId,
            originProfiler = ProfilerKind.LAYOUT_INSPECTOR,
            scope = scope,
            model = model,
            promptVersion = PROMPT_VERSION,
            payloadPolicyVersion = PAYLOAD_POLICY_VERSION,
            sourceSnapshotIds = snapshots.map { it.value },
            buildEvidenceBundleIds = emptyList(),
            status = AnalysisSessionStatus.RUNNING,
            createdAt = Instant.now(),
            provider = "OpenAI Responses",
        )
        return PreparedAiAnalysis(
            input = input,
            manifest = AiPayloadManifest(
                scope = scope.description,
                model = model,
                sourceSnapshotIds = snapshots.map { it.value }.sorted(),
                buildEvidenceBundleIds = emptyList(),
                evidence = performanceEvidence.map {
                    AiEvidencePayloadSummary(it.id, it.kind, it.summary)
                },
                sources = aiCandidates.map { it.toPayloadSummary() },
                payloadBytes = payloadBytes,
                performanceDataOnly = !input.includeSourceSnippets,
                blockedReason = blockedReason,
            ),
        ) {
            execute(initialSession, request, input, candidates, model, endpoint)
        }
    }

    private suspend fun execute(
        initialSession: AnalysisSession,
        request: AnalysisRequest,
        input: AiAnalysisInput,
        candidates: List<ResolutionCandidate>,
        model: String,
        endpoint: String,
    ): AiAnalysisReport {
        check(request.sourceCandidates.isEmpty() || candidates.all(::sourceUploadAllowed)) {
            "Source upload authorization changed; prepare the analysis again"
        }
        runtime.analysisSessions.saveSession(initialSession)
        return try {
            runtime.analysisSessions.saveRequest(request)
            val result = (analysisGateway ?: gateway(model, endpoint)).analyze(request)
            currentCoroutineContext().ensureActive()
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
                        performanceEvidenceIds = finding.performanceEvidenceIds,
                        sourceCandidateIds = finding.sourceCandidateIds,
                    )
                },
                provenance = AiAnalysisProvenance(
                    sessionId = request.sessionId.value,
                    provider = "OpenAI Responses",
                    scope = request.scope.description,
                    promptVersion = request.promptVersion,
                    payloadPolicyVersion = request.payloadPolicyVersion,
                    sourceSnapshotIds = initialSession.sourceSnapshotIds,
                    buildEvidenceBundleIds = initialSession.buildEvidenceBundleIds,
                    evidence = runtime.analysisSessions.evidence(request.sessionId).map {
                        AiEvidenceReference(it.id, it.kind, it.summary, it.payloadHash)
                    },
                    sourceCandidates = runtime.analysisSessions.candidates(request.sessionId).map {
                        val resolved = candidates.firstOrNull { candidate -> candidate.id.value == it.id }
                        val workspace = resolved?.let { candidate ->
                            runtime.repository.workspace(candidate.location.workspaceId)
                        }
                        val snapshot = resolved?.let { candidate ->
                            runtime.repository.snapshot(candidate.location.snapshotId)
                        }
                        AiSourceCandidateReference(
                            it.id,
                            it.relativePath,
                            it.startLine,
                            it.endLine,
                            it.resolutionConfidence,
                            it.contentHash,
                            workspace?.id?.value,
                            snapshot?.id?.value,
                            workspace?.config?.kind?.name,
                            workspace?.displayName,
                            snapshot?.immutableRevision,
                        )
                    },
                ),
            )
        } catch (failure: CancellationException) {
            runtime.analysisSessions.saveSession(
                initialSession.copy(status = AnalysisSessionStatus.CANCELLED),
            )
            throw failure
        } catch (failure: Throwable) {
            runtime.analysisSessions.saveSession(
                initialSession.copy(
                    status = AnalysisSessionStatus.FAILED,
                    errorMessage = failure::class.simpleName ?: "AnalysisFailure",
                ),
            )
            throw failure
        }
    }

    private fun AiAnalysisInput.performanceEvidence(): List<PerformanceEvidence> =
        listOf(
            PerformanceEvidence(
                id = selectedNodeId?.let(::evidenceId) ?: "layout-report",
                kind = "layout-snapshot",
                summary = sourceEvidence.firstOrNull()?.let { item ->
                    buildString {
                        append(item.className)
                        item.resourceName?.let { append(" · ").append(it) }
                    }
                } ?: "Layout report summary",
                structuredPayload = json,
            ),
        )

    private fun AiAnalysisInput.resolutionEvidence(): List<SourceResolutionEvidence> =
        sourceEvidence.flatMap { item ->
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

    private fun gateway(model: String, endpoint: String): OpenAiAnalysisGateway {
        val apiKey = runtime.credential(OPENAI_CREDENTIAL_KEY)
            ?: error("Configure an OpenAI API key in Source Workspaces before running analysis")
        return OpenAiAnalysisGateway(OpenAiResponsesClient(apiKey, model, endpoint))
    }

    private suspend fun ResolutionCandidate.toAiCandidate(): AiSourceCandidate {
        val snippet = if (sourceUploadAllowed(this)) sourceSnippet(this) else null
        val snippetLines = snippet?.lineSequence()?.toList().orEmpty()
        return AiSourceCandidate(
            id = id.value,
            relativePath = location.relativePath,
            symbol = null,
            resolutionConfidence = confidence.name,
            reasons = reasons,
            sourceSnippet = snippet,
            startLine = snippetLines.firstOrNull()?.substringBefore(':')?.toIntOrNull() ?: location.range?.startLine,
            endLine = snippetLines.lastOrNull()?.substringBefore(':')?.toIntOrNull() ?: location.range?.endLine,
            contentHash = location.contentHash,
            indexVersion = indexVersion,
            indexComplete = indexComplete,
        )
    }

    private fun AiSourceCandidate.toPayloadSummary(): AiSourcePayloadSummary {
        val snippet = sourceSnippet.orEmpty()
        return AiSourcePayloadSummary(
            relativePath = relativePath,
            startLine = startLine,
            endLine = endLine,
            lineCount = snippet.lineSequence().count(),
            byteCount = snippet.encodeToByteArray().size,
            resolutionConfidence = resolutionConfidence,
            reasons = reasons,
            indexComplete = indexComplete,
        )
    }

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

package com.androidperformancestudio.ai

import java.time.Instant
import java.util.UUID

@JvmInline
public value class AnalysisSessionId(
    public val value: String,
) {
    public companion object {
        public fun create(): AnalysisSessionId = AnalysisSessionId(UUID.randomUUID().toString())
    }
}

@JvmInline
public value class AnalysisFindingId(
    public val value: String,
)

public enum class ProfilerKind {
    LAYOUT_INSPECTOR,
    SIMPLEPERF,
    MEMORY,
    TRACE,
    FRAME,
    STARTUP,
    BATTERY,
    NETWORK,
    GPU,
    BENCHMARK,
    METHOD_RECORDING,
    SOURCE_WORKSPACE,
    CHAT,
}

public enum class AnalysisScopeKind {
    CURRENT_SELECTION,
    REPORT_SUMMARY,
    CONVERSATION,
}

public enum class ReportLanguage {
    AUTO,
    ENGLISH,
    SIMPLIFIED_CHINESE,
}

public data class AnalysisScope(
    val kind: AnalysisScopeKind,
    val description: String,
)

public data class PerformanceEvidence(
    val id: String,
    val kind: String,
    val summary: String,
    val structuredPayload: String,
)

public data class AiSourceCandidate(
    val id: String,
    val relativePath: String,
    val symbol: String?,
    val resolutionConfidence: String,
    val reasons: List<String>,
    val sourceSnippet: String?,
    val startLine: Int? = null,
    val endLine: Int? = null,
    val contentHash: String? = null,
    val indexVersion: Long? = null,
    val indexComplete: Boolean? = null,
)

public data class AnalysisRequest(
    val sessionId: AnalysisSessionId,
    val originProfiler: ProfilerKind,
    val scope: AnalysisScope,
    val evidence: List<PerformanceEvidence>,
    val sourceCandidates: List<AiSourceCandidate>,
    val promptVersion: String,
    val payloadPolicyVersion: String,
    val userQuestion: String? = null,
    val conversationContext: List<AiConversationContextTurn> = emptyList(),
    val outputLanguage: ReportLanguage = ReportLanguage.AUTO,
) {
    init {
        require((originProfiler == ProfilerKind.CHAT) == (scope.kind == AnalysisScopeKind.CONVERSATION)) { "E_CHAT_CONTEXT" }
        if (originProfiler == ProfilerKind.CHAT) {
            require(evidence.isEmpty() && sourceCandidates.isEmpty()) { "E_CHAT_CONTEXT" }
        }
        require(userQuestion == null || userQuestion.length in 1..MAX_USER_QUESTION_LENGTH) { "E_QUESTION_LIMIT" }
        require(conversationContext.size <= MAX_CHAT_CONTEXT_TURNS) { "E_CONVERSATION_CONTEXT_LIMIT" }
        require(conversationContext.map { it.runId }.distinct().size == conversationContext.size) {
            "E_CONVERSATION_CONTEXT_ID"
        }
    }
}

/** A data-free conversation is not profiler evidence and cannot carry source access. */
public val AnalysisRequest.isDirectChat: Boolean
    get() =
        originProfiler == ProfilerKind.CHAT &&
            scope.kind == AnalysisScopeKind.CONVERSATION &&
            evidence.isEmpty() &&
            sourceCandidates.isEmpty()

/** Earlier redacted messages are untrusted dialogue context, not performance facts or authority. */
public data class AiConversationContextTurn(
    val runId: String,
    val question: String?,
    val answer: String,
) {
    init {
        require(runId.length in 1..MAX_CHAT_CONTEXT_ID_LENGTH) { "E_CONVERSATION_CONTEXT_ID" }
        require(question == null || question.length <= MAX_CHAT_CONTEXT_TEXT_LENGTH) { "E_CONVERSATION_CONTEXT_LIMIT" }
        require(answer.isNotBlank() && answer.length <= MAX_CHAT_CONTEXT_TEXT_LENGTH) { "E_CONVERSATION_CONTEXT_LIMIT" }
    }
}

private const val MAX_CHAT_CONTEXT_TURNS = 4
private const val MAX_CHAT_CONTEXT_TEXT_LENGTH = 2048
private const val MAX_CHAT_CONTEXT_ID_LENGTH = 128
private const val MAX_USER_QUESTION_LENGTH = 4096

public enum class AnalysisSeverity {
    INFO,
    WARNING,
    ERROR,
}

public data class AnalysisFinding(
    val id: AnalysisFindingId,
    val severity: AnalysisSeverity,
    val title: String,
    val explanation: String,
    val recommendation: String,
    val analysisConfidence: Float,
    val performanceEvidenceIds: List<String>,
    val sourceCandidateIds: List<String>,
)

public data class AnalysisResult(
    val sessionId: AnalysisSessionId,
    val model: String,
    val summary: String,
    val findings: List<AnalysisFinding>,
)

public enum class AnalysisSessionStatus {
    RUNNING,
    SUCCEEDED,
    CANCELLED,
    FAILED,
}

public data class AnalysisSession(
    val id: AnalysisSessionId,
    val originProfiler: ProfilerKind,
    val scope: AnalysisScope,
    val model: String?,
    val promptVersion: String,
    val payloadPolicyVersion: String,
    val sourceSnapshotIds: List<String>,
    val buildEvidenceBundleIds: List<String>,
    val status: AnalysisSessionStatus,
    val createdAt: Instant,
    val parentSessionId: AnalysisSessionId? = null,
    val summary: String? = null,
    val errorMessage: String? = null,
    val provider: String? = null,
)

public fun interface AiAnalysisGateway {
    public suspend fun analyze(request: AnalysisRequest): AnalysisResult
}

package com.androidperformancestudio.aiplatform.storage

import com.androidperformancestudio.ai.AnalysisFinding
import com.androidperformancestudio.ai.AnalysisFindingId
import com.androidperformancestudio.ai.AnalysisRequest
import com.androidperformancestudio.ai.AnalysisResult
import com.androidperformancestudio.ai.AnalysisSessionId
import com.androidperformancestudio.ai.AnalysisSeverity
import com.androidperformancestudio.aiplatform.model.RunStatus
import com.androidperformancestudio.aiplatform.model.requireStableId
import kotlinx.serialization.Serializable

@Serializable
public enum class ExecutionKind { RUN, CHILD_AGENT, SKILL, SCRIPT, PROVIDER, ARTIFACT }

@Serializable
public enum class ExecutionPhase { STARTED, SUCCEEDED, FAILED, CANCELLED }

/** Structured audit only: no prompt, stdout, source, credentials, arbitrary detail or response body. */
@Serializable
public data class ExecutionAuditEvent(
    val conversationId: String,
    val runId: String,
    val generation: Long,
    val sequence: Int,
    val kind: ExecutionKind,
    val phase: ExecutionPhase,
    val subjectId: String,
    val parentSubjectId: String? = null,
    val timestampEpochMillis: Long,
    val depth: Int = 0,
    val inputBytes: Long = 0,
    val outputBytes: Long = 0,
    val elapsedMillis: Long = 0,
    val errorCode: String? = null,
    val evidenceIds: List<String> = emptyList(),
    val candidateIds: List<String> = emptyList(),
    val relatedConversationId: String? = null,
    val relatedRunId: String? = null,
) {
    init {
        require(conversationId.length in 1..128 && runId.length in 1..128)
        require(generation >= 0 && sequence in 0..1023 && timestampEpochMillis >= 0)
        require(depth in 0..3 && inputBytes in 0..16_777_216 && outputBytes in 0..16_777_216 && elapsedMillis in 0..3_600_000)
        requireStableId(subjectId)
        parentSubjectId?.let(::requireStableId)
        require(errorCode == null || errorCode.matches(Regex("E_[A-Z0-9_]{1,80}")))
        require(evidenceIds.size <= 100 && candidateIds.size <= 100)
        require((evidenceIds + candidateIds).all { it.length in 1..256 })
        require((relatedConversationId == null) == (relatedRunId == null))
        if (relatedConversationId != null) {
            require(kind == ExecutionKind.CHILD_AGENT)
            require(relatedConversationId.length in 1..128 && relatedConversationId != conversationId)
            require(relatedRunId!!.length in 1..128 && relatedRunId != runId)
        }
    }
}

@Serializable
public data class StoredFinding(
    val id: String,
    val severity: String,
    val title: String,
    val explanation: String,
    val recommendation: String,
    val confidence: Float,
    val evidenceIds: List<String>,
    val candidateIds: List<String>,
) {
    init {
        require(severity in AnalysisSeverity.entries.map { it.name })
        require(id.length in 1..256 && title.length <= 512)
        require(explanation.length <= 8192 && recommendation.length <= 8192 && confidence in 0f..1f)
        require(evidenceIds.size in 1..100 && candidateIds.size <= 100)
        require((evidenceIds + candidateIds).all { it.length in 1..256 })
        require(evidenceIds.distinct().size == evidenceIds.size && candidateIds.distinct().size == candidateIds.size)
    }
}

/** Validated and redacted projection, never a raw provider response or request archive. */
@Serializable
public data class StoredAnalysisResult(
    val conversationId: String,
    val runId: String,
    val generation: Long,
    val sessionId: String,
    val model: String,
    val summary: String,
    val findings: List<StoredFinding>,
    val savedAtEpochMillis: Long,
    val questionHash: String? = null,
    val parentTurnRunId: String? = null,
    val artifactHashes: List<String> = emptyList(),
) {
    init {
        require(conversationId.length in 1..128 && runId.length in 1..128 && sessionId.length in 1..128)
        require(generation >= 0 && savedAtEpochMillis >= 0 && model.length in 1..128 && summary.length <= 16_384)
        require(findings.size <= 12 && findings.map { it.id }.distinct().size == findings.size)
        require(questionHash == null || questionHash.matches(Regex("[a-f0-9]{64}")))
        require(parentTurnRunId == null || parentTurnRunId.length in 1..128)
        require(
            artifactHashes.size <= 8 &&
                artifactHashes.distinct().size == artifactHashes.size &&
                artifactHashes.all { it.matches(Regex("[a-f0-9]{64}")) },
        )
    }

    public fun toAnalysisResult(): AnalysisResult =
        AnalysisResult(
            AnalysisSessionId(sessionId),
            model,
            summary,
            findings.map { finding ->
                AnalysisFinding(
                    AnalysisFindingId(finding.id),
                    AnalysisSeverity.valueOf(finding.severity),
                    finding.title,
                    finding.explanation,
                    finding.recommendation,
                    finding.confidence,
                    finding.evidenceIds.toList(),
                    finding.candidateIds.toList(),
                )
            },
        )
}

/** Adds persistence/history operations without weakening the reference-only conversation contract. */
public interface ConversationHistoryRepository : ConversationRepository {
    /** Implementations with chat storage commit both projections atomically before approval is exposed. */
    public fun savePrepared(
        record: com.androidperformancestudio.aiplatform.model.ConversationRecord,
        turn: StoredChatTurn,
    ) {
        save(record)
    }

    public fun chatTurns(conversationId: String): List<StoredChatTurn> = emptyList()

    public fun chatMetadata(conversationId: String): StoredChatMetadata? = null

    public fun renameConversation(
        conversationId: String,
        title: String,
    ): Unit = throw ConversationStorageException("E_CHAT_UNSUPPORTED")

    public fun pinConversation(
        conversationId: String,
        pinned: Boolean,
    ): Unit = throw ConversationStorageException("E_CHAT_UNSUPPORTED")

    public fun archiveConversation(
        conversationId: String,
        archived: Boolean,
    ): Unit = throw ConversationStorageException("E_CHAT_UNSUPPORTED")

    public fun saveResult(result: StoredAnalysisResult)

    public fun complete(
        record: com.androidperformancestudio.aiplatform.model.ConversationRecord,
        result: StoredAnalysisResult,
    )

    public fun result(
        conversationId: String,
        runId: String,
    ): StoredAnalysisResult?

    public fun appendAudit(event: ExecutionAuditEvent)

    public fun audit(
        conversationId: String,
        runId: String? = null,
    ): List<ExecutionAuditEvent>

    public fun delete(conversationId: String): Boolean

    public fun prune(
        maxConversations: Int,
        olderThanEpochMillis: Long,
    ): Int
}

/** Separate bounded display projection; ConversationRecord remains reference-only. */
public data class StoredChatTurn(
    val conversationId: String,
    val runId: String,
    val generation: Long,
    val question: String?,
    val timestampEpochMillis: Long,
    val parentTurnRunId: String? = null,
) {
    init {
        require(conversationId.length in 1..128 && runId.length in 1..128)
        require(generation >= 0 && timestampEpochMillis >= 0)
        require(parentTurnRunId == null || parentTurnRunId.length in 1..128)
        require(question == null || question.isNotBlank() && question.length <= 4096 && redactAnalysisText(question) == question)
    }
}

public data class StoredChatMetadata(
    val title: String? = null,
    val pinned: Boolean = false,
    val updatedAtEpochMillis: Long = 0,
    val archived: Boolean = false,
) {
    init {
        require(title == null || title.isNotBlank() && title.length <= 120 && redactAnalysisText(title) == title)
        require(updatedAtEpochMillis >= 0)
    }
}

public fun projectChatTurn(
    conversationId: String,
    runId: String,
    generation: Long,
    request: AnalysisRequest,
    timestampEpochMillis: Long,
    parentTurnRunId: String? = null,
): StoredChatTurn {
    val question =
        request.userQuestion?.let { value ->
            val withoutEvidence =
                request.evidence.fold(value) { text, evidence ->
                    val payload = evidence.structuredPayload
                    if (payload.isBlank() || payload == "{}") text else text.replace(payload, "[evidence omitted]")
                }
            redactSourceText(withoutEvidence, request).take(4096).trim().takeIf { it.isNotEmpty() }
        }
    return StoredChatTurn(conversationId, runId, generation, question, timestampEpochMillis, parentTurnRunId)
}

public fun projectChatTitle(title: String): String {
    require(title.isNotBlank() && title.length <= 120) { "E_CHAT_TITLE" }
    return redactAnalysisText(title).take(120).trim()
}

/** Redaction is deliberately performed before the object enters storage or an Artifact publisher. */
public fun projectAnalysisResult(
    conversationId: String,
    runId: String,
    generation: Long,
    request: AnalysisRequest,
    result: AnalysisResult,
    savedAtEpochMillis: Long,
    questionHash: String? = null,
    parentTurnRunId: String? = null,
): StoredAnalysisResult {
    require(result.sessionId == request.sessionId && result.model.length in 1..128)
    val evidence = request.evidence.map { it.id }.toSet()
    val candidates = request.sourceCandidates.map { it.id }.toSet()

    fun clean(text: String): String = redactSourceText(text, request)
    return StoredAnalysisResult(
        conversationId,
        runId,
        generation,
        result.sessionId.value,
        clean(result.model),
        clean(result.summary),
        result.findings.map { finding ->
            require(finding.performanceEvidenceIds.all(evidence::contains) && finding.sourceCandidateIds.all(candidates::contains))
            StoredFinding(
                finding.id.value,
                finding.severity.name,
                clean(finding.title),
                clean(finding.explanation),
                clean(finding.recommendation),
                finding.analysisConfidence,
                finding.performanceEvidenceIds.toList(),
                finding.sourceCandidateIds.toList(),
            )
        },
        savedAtEpochMillis,
        questionHash,
        parentTurnRunId,
    )
}

private fun redactSourceText(
    text: String,
    request: AnalysisRequest,
): String {
    val sensitive =
        request.sourceCandidates
            .mapNotNull { it.sourceSnippet }
            .flatMap { listOf(it) + it.lines().filter { line -> line.trim().length >= 8 } }
            .filter { it.isNotEmpty() }
            .distinct()
            .sortedByDescending { it.length }
    return redactAnalysisText(sensitive.fold(text) { value, snippet -> value.replace(snippet, "[source omitted]") })
}

/** Common credential-shaped values are masked even if a provider echoes them in otherwise valid prose. */
public fun redactAnalysisText(value: String): String =
    value
        .replace(Regex("(?i)\\b(?:sk|ghp|github_pat|xox[baprs])[-_][A-Za-z0-9_-]{8,}"), "[credential omitted]")
        .replace(
            Regex("(?i)(?:bearer|api[_ -]?key|access[_ -]?token|refresh[_ -]?token|password)\\s*[:= ]\\s*[\"']?[^\\s\"'<>]{4,}"),
            "[credential omitted]",
        )

internal fun requireResultTurn(
    status: RunStatus,
    matches: Boolean,
) {
    if (status != RunStatus.SUCCEEDED || !matches) throw ConversationStorageException("E_RESULT_TURN")
}

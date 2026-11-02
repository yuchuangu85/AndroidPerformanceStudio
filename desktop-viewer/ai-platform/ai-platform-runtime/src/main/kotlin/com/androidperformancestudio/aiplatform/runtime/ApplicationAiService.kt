package com.androidperformancestudio.aiplatform.runtime

import com.androidperformancestudio.ai.AnalysisRequest
import com.androidperformancestudio.ai.AnalysisResult
import com.androidperformancestudio.aiplatform.model.ApprovalRequest
import com.androidperformancestudio.aiplatform.model.ConversationRecord
import com.androidperformancestudio.aiplatform.model.Permission
import com.androidperformancestudio.aiplatform.model.ProviderEvent
import com.androidperformancestudio.aiplatform.model.RunStatus
import com.androidperformancestudio.aiplatform.storage.ConversationHistoryRepository
import com.androidperformancestudio.aiplatform.storage.ExecutionAuditEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

public data class AiApplicationState(
    val conversationId: String? = null,
    val prepared: PreparedAgentRun? = null,
    val status: RunStatus? = null,
    val result: AnalysisResult? = null,
    val timeline: List<ExecutionAuditEvent> = emptyList(),
    val artifact: ReadOnlyReportArtifact? = null,
    val publishedArtifactHash: String? = null,
    val budget: ExecutionBudgetSnapshot? = null,
    val errorCode: String? = null,
    val restored: Boolean = false,
    val lastTurnRunId: String? = null,
)

/** Shared desktop boundary for every Profiler: immutable prepare -> explicit approve -> collect. */
public class ApplicationAiService(
    private val runtime: MainAgentOrchestrator,
    private val historyRepository: ConversationHistoryRepository,
    private val scope: CoroutineScope,
) {
    private val mutableState = MutableStateFlow(AiApplicationState())
    public val state: StateFlow<AiApplicationState> = mutableState.asStateFlow()
    private val mutex = Mutex()
    private var collector: Job? = null
    private var activeRunId: String? = null
    private val completions = linkedMapOf<String, RunCompletion>()

    public suspend fun prepare(
        conversationId: String,
        analysis: AnalysisRequest,
        agentId: String,
        providerId: String,
        model: String,
        permissions: Set<Permission>,
        question: String? = null,
        parentTurnRunId: String? = null,
        parentConversationId: String? = null,
    ): PreparedAgentRun =
        mutex.withLock {
            cancelLocked()
            try {
                val prepared =
                    runtime.prepare(
                        conversationId,
                        analysis,
                        agentId,
                        providerId,
                        model,
                        permissions,
                        question,
                        parentTurnRunId,
                        parentConversationId,
                    )
                activeRunId = prepared.approval.runId
                completions[prepared.approval.runId] = RunCompletion()
                trimCompletionsLocked()
                mutableState.value = AiApplicationState(conversationId, prepared, RunStatus.AWAITING_APPROVAL)
                prepared
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                mutableState.value =
                    AiApplicationState(conversationId = conversationId, status = RunStatus.PERMANENT_FAILURE, errorCode = safeCode(failure))
                throw AgentPolicyException(safeCode(failure))
            }
        }

    public suspend fun approveAndRun(approval: ApprovalRequest): Job =
        mutex.withLock {
            check(activeRunId == approval.runId) { "E_RUN_UNAVAILABLE" }
            scope.coroutineContext.ensureActive()
            try {
                runtime.approve(approval)
            } catch (failure: AgentPolicyException) {
                if (failure.code == "E_PROVIDER_CONNECTION_CHANGED") {
                    val terminal =
                        mutableState.value.copy(
                            status = RunStatus.PERMANENT_FAILURE,
                            errorCode = failure.code,
                            lastTurnRunId = approval.runId,
                        )
                    mutableState.value = terminal
                    completeIfTerminalLocked(approval.runId, terminal)
                    activeRunId = null
                }
                throw failure
            }
            check(collector?.isActive != true) { "E_RUN_ACTIVE" }
            val runId = approval.runId
            mutableState.value = mutableState.value.copy(status = RunStatus.RUNNING, errorCode = null)
            scope
                .launch {
                    try {
                        runtime.run(runId).collect { event ->
                            mutex.withLock {
                                if (activeRunId != runId) return@withLock
                                var next = mutableState.value
                                if (event.timeline != null) {
                                    next = next.copy(timeline = (next.timeline + event.timeline).takeLast(1024), budget = event.budget)
                                }
                                if (event.skillResult?.artifact != null) next = next.copy(artifact = event.skillResult.artifact)
                                if (event.publishedArtifactHash !=
                                    null
                                ) {
                                    next = next.copy(publishedArtifactHash = event.publishedArtifactHash)
                                }
                                if (event.timeline == null) {
                                    when (val provider = event.event) {
                                        is ProviderEvent.StructuredResult -> next = next.copy(result = provider.result)
                                        ProviderEvent.Completed -> next = next.copy(status = RunStatus.SUCCEEDED, lastTurnRunId = runId)
                                        is ProviderEvent.Failure ->
                                            next =
                                                next.copy(
                                                    status =
                                                        if (provider.retryable) {
                                                            RunStatus.RETRYABLE_FAILURE
                                                        } else {
                                                            RunStatus.PERMANENT_FAILURE
                                                        },
                                                    errorCode = provider.code,
                                                )
                                        ProviderEvent.Cancelled -> next = next.copy(status = RunStatus.CANCELLED)
                                        else -> Unit
                                    }
                                }
                                mutableState.value = next
                                completeIfTerminalLocked(runId, next)
                            }
                        }
                    } catch (failure: CancellationException) {
                        withContext(NonCancellable) {
                            mutex.withLock {
                                if (activeRunId == runId) {
                                    val terminal = mutableState.value.copy(status = RunStatus.CANCELLED, lastTurnRunId = runId)
                                    mutableState.value = terminal
                                    completeIfTerminalLocked(runId, terminal)
                                }
                            }
                        }
                        throw failure
                    } catch (failure: Exception) {
                        mutex.withLock {
                            if (activeRunId == runId) {
                                val terminal =
                                    mutableState.value.copy(
                                        status = RunStatus.PERMANENT_FAILURE,
                                        errorCode = safeCode(failure),
                                        lastTurnRunId = runId,
                                    )
                                mutableState.value = terminal
                                completeIfTerminalLocked(runId, terminal)
                            }
                        }
                    }
                }.also { collector = it }
        }

    /** Await this run's retained terminal snapshot, not the conflated currently selected conversation. */
    public suspend fun awaitRun(runId: String): AiApplicationState {
        val completion =
            mutex.withLock {
                val entry = completions[runId] ?: throw AgentPolicyException("E_RUN_UNAVAILABLE")
                entry.waiters++
                entry
            }
        return try {
            completion.terminal.await()
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    completion.waiters--
                    trimCompletionsLocked()
                }
            }
        }
    }

    /** Global UI cancellation. Legacy/request owners must use the exact run overload. */
    public suspend fun cancel(): Unit = mutex.withLock { cancelLocked() }

    /** Cancelling an old/finished run never cancels the newly selected conversation. */
    public suspend fun cancel(runId: String): Unit =
        mutex.withLock {
            if (activeRunId == runId) cancelLocked()
        }

    public suspend fun newConversation(): Unit =
        mutex.withLock {
            try {
                cancelLocked()
            } finally {
                mutableState.value = AiApplicationState()
            }
        }

    private suspend fun cancelLocked() {
        val runId = activeRunId
        try {
            runId?.let { runtime.cancel(it) }
        } finally {
            collector?.cancel()
            collector = null
            activeRunId = null
            val current = mutableState.value
            val terminal =
                if (current.status?.terminal ==
                    false
                ) {
                    current.copy(status = RunStatus.CANCELLED, lastTurnRunId = runId)
                } else {
                    current
                }
            mutableState.value = terminal
            if (runId != null) completeIfTerminalLocked(runId, terminal)
        }
    }

    private fun completeIfTerminalLocked(
        runId: String,
        snapshot: AiApplicationState,
    ) {
        if (snapshot.status?.terminal == true) completions[runId]?.terminal?.complete(snapshot)
        trimCompletionsLocked()
    }

    private fun trimCompletionsLocked() {
        val completed = completions.entries.filter { it.value.terminal.isCompleted && it.value.waiters == 0 }
        completed.take((completed.size - MAX_RETAINED_COMPLETIONS).coerceAtLeast(0)).forEach { completions.remove(it.key) }
    }

    private class RunCompletion {
        val terminal = CompletableDeferred<AiApplicationState>()
        var waiters = 0
    }

    private companion object {
        const val MAX_RETAINED_COMPLETIONS = 64
    }

    /** Restore is read-only. Interrupted runs require a fresh explicit analysis, never automatic replay. */
    public suspend fun restore(conversationId: String): AiApplicationState =
        mutex.withLock {
            cancelLocked()
            val conversation = historyRepository.load(conversationId) ?: error("E_CONVERSATION_UNAVAILABLE")
            val last = conversation.turns.lastOrNull()
            val storedResult = last?.let { historyRepository.result(conversationId, it.runId) }
            val restoredResult = storedResult?.toAnalysisResult()
            AiApplicationState(
                conversationId = conversationId,
                status = last?.status?.takeIf { it.terminal } ?: RunStatus.INTERRUPTED,
                result = restoredResult,
                timeline = historyRepository.audit(conversationId, last?.runId),
                restored = true,
                publishedArtifactHash = storedResult?.artifactHashes?.firstOrNull(),
                lastTurnRunId = last?.runId,
            ).also { mutableState.value = it }
        }

    public suspend fun history(): List<ConversationRecord> = mutex.withLock { historyRepository.list(limit = 1000) }

    public suspend fun chatHistory(includeArchived: Boolean = false): List<ChatSessionSummary> =
        mutex.withLock {
            val records =
                buildList {
                    var offset = 0
                    while (true) {
                        val page = historyRepository.list(limit = 1000, offset = offset)
                        addAll(page)
                        if (page.size < 1000) break
                        offset += page.size
                    }
                }
            val recordsById = records.associateBy { it.id }
            records
                .mapNotNull { record ->
                    // Graph-owned child Agents have independent analysis sessions; user forks
                    // are validated against the same immutable parent session by prepare().
                    val parent = record.parentConversationId?.let(recordsById::get)
                    if (parent != null && parent.analysisSessionId != record.analysisSessionId) return@mapNotNull null
                    val metadata = historyRepository.chatMetadata(record.id)
                    if (!includeArchived && metadata?.archived == true) return@mapNotNull null
                    val firstQuestion = historyRepository.chatTurns(record.id).firstOrNull { it.question != null }?.question
                    val summary =
                        if (metadata?.title == null && firstQuestion == null) {
                            record.turns.firstNotNullOfOrNull {
                                historyRepository
                                    .result(
                                        record.id,
                                        it.runId,
                                    )?.summary
                                    ?.takeIf(String::isNotBlank)
                            }
                        } else {
                            null
                        }
                    ChatSessionSummary(
                        record.id,
                        metadata?.title ?: (firstQuestion ?: summary)?.take(120) ?: "Analysis session",
                        metadata?.updatedAtEpochMillis ?: 0,
                        metadata?.pinned ?: false,
                        record.turns.size,
                        record.turns.lastOrNull()?.status,
                        metadata?.archived ?: false,
                    )
                }.sortedWith(
                    compareByDescending<ChatSessionSummary> { it.pinned }
                        .thenByDescending { it.updatedAtEpochMillis }
                        .thenBy { it.id },
                )
        }

    public suspend fun transcript(conversationId: String): List<ChatTurn> =
        mutex.withLock {
            val record = historyRepository.load(conversationId) ?: return@withLock emptyList()
            val questions = historyRepository.chatTurns(conversationId).associateBy { it.runId }
            record.turns.map { turn ->
                val question = questions[turn.runId]?.takeIf { it.generation == turn.generation }
                val stored = historyRepository.result(conversationId, turn.runId)
                ChatTurn(
                    turn.runId,
                    turn.generation,
                    turn.status,
                    question?.question,
                    stored?.toAnalysisResult(),
                    question?.timestampEpochMillis ?: stored?.savedAtEpochMillis ?: 0,
                    stored?.artifactHashes?.toList() ?: emptyList(),
                )
            }
        }

    public suspend fun renameConversation(
        id: String,
        title: String,
    ): Unit =
        mutex.withLock {
            historyRepository.renameConversation(id, title)
        }

    public suspend fun pinConversation(
        id: String,
        pinned: Boolean,
    ): Unit =
        mutex.withLock {
            historyRepository.pinConversation(id, pinned)
        }

    public suspend fun archiveConversation(
        id: String,
        archived: Boolean,
    ): Unit =
        mutex.withLock {
            historyRepository.archiveConversation(id, archived)
        }

    public suspend fun delete(conversationId: String): Boolean =
        mutex.withLock {
            if (mutableState.value.conversationId == conversationId) cancelLocked()
            historyRepository.delete(conversationId).also {
                if (mutableState.value.conversationId ==
                    conversationId
                ) {
                    mutableState.value = AiApplicationState()
                }
            }
        }

    public suspend fun retain(
        maxConversations: Int,
        olderThanEpochMillis: Long,
    ): Int =
        mutex.withLock {
            historyRepository.prune(maxConversations, olderThanEpochMillis)
        }

    public suspend fun recoverInterrupted(): Int =
        mutex.withLock {
            check(collector?.isActive != true) { "E_RUN_ACTIVE" }
            historyRepository.recoverInterrupted()
        }
}

private fun safeCode(failure: Exception): String = (failure as? AgentPolicyException)?.code ?: "E_AI_SERVICE"

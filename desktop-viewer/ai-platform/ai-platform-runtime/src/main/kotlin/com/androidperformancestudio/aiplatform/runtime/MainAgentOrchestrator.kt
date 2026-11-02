package com.androidperformancestudio.aiplatform.runtime

import com.androidperformancestudio.ai.AiConversationContextTurn
import com.androidperformancestudio.ai.AnalysisRequest
import com.androidperformancestudio.ai.isDirectChat
import com.androidperformancestudio.ai.payloadText
import com.androidperformancestudio.aiplatform.model.ApprovalRequest
import com.androidperformancestudio.aiplatform.model.ConversationRecord
import com.androidperformancestudio.aiplatform.model.ConversationTurn
import com.androidperformancestudio.aiplatform.model.ExtensionKind
import com.androidperformancestudio.aiplatform.model.Permission
import com.androidperformancestudio.aiplatform.model.ProviderEvent
import com.androidperformancestudio.aiplatform.model.ProviderRunRequest
import com.androidperformancestudio.aiplatform.model.RunStatus
import com.androidperformancestudio.aiplatform.model.ScriptRuntime
import com.androidperformancestudio.aiplatform.providers.ProviderBroker
import com.androidperformancestudio.aiplatform.registry.ExtensionRegistry
import com.androidperformancestudio.aiplatform.script.ArtifactPublisher
import com.androidperformancestudio.aiplatform.script.ScriptException
import com.androidperformancestudio.aiplatform.script.ScriptRunRequest
import com.androidperformancestudio.aiplatform.script.ScriptRunResult
import com.androidperformancestudio.aiplatform.storage.ConversationHistoryRepository
import com.androidperformancestudio.aiplatform.storage.ConversationRepository
import com.androidperformancestudio.aiplatform.storage.ExecutionAuditEvent
import com.androidperformancestudio.aiplatform.storage.ExecutionKind
import com.androidperformancestudio.aiplatform.storage.ExecutionPhase
import com.androidperformancestudio.aiplatform.storage.projectAnalysisResult
import com.androidperformancestudio.aiplatform.storage.projectChatTurn
import com.androidperformancestudio.aiplatform.storage.redactAnalysisText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID

public class AgentPolicyException(
    public val code: String,
) : IllegalStateException(code)

/** A reference-only preview. The execution payload is kept privately, never reconstructed by UI. */
public data class PreparedAgentRun(
    val approval: ApprovalRequest,
    val evidenceCount: Int,
    val candidateCount: Int,
    val payloadBytes: Int,
    val providerId: String = "",
    val model: String = "",
    val conversationId: String = "",
    /** Display-only facts; executable payload remains private, with snippets/raw evidence excluded. */
    val analysisPreview: AnalysisRequest? = null,
    /** Includes provider, Skill, script and explicitly granted artifact publication invocations. */
    val plannedCallCount: Int = 0,
    val plannedScriptCount: Int = 0,
    val plannedProviderCallCount: Int = 0,
)

public data class AgentRunEvent(
    val runId: String,
    val generation: Long,
    val event: ProviderEvent,
    val timeline: ExecutionAuditEvent? = null,
    val skillResult: SkillExecutionResult? = null,
    val budget: ExecutionBudgetSnapshot? = null,
    val publishedArtifactHash: String? = null,
)

/** A frozen trusted manifest graph, one explicitly selected provider, and host-owned executors. */
public class MainAgentOrchestrator(
    private val registry: ExtensionRegistry,
    private val providers: ProviderBroker,
    private val conversations: ConversationRepository,
    private val sourceAuthorization: (AnalysisRequest) -> Boolean = { false },
    private val clock: () -> Long = System::currentTimeMillis,
    private val artifactPublisher: ArtifactPublisher? = null,
    private val scriptRunner: (suspend (ScriptRunRequest) -> ScriptRunResult)? = null,
    private val scriptSupport: (suspend (ScriptRuntime) -> Boolean)? = null,
    private val connectionRevision: (String) -> String = { "" },
) {
    private val mutex = Mutex()
    private val pending = mutableMapOf<String, PendingRun>()
    private val graphs = FrozenAgentGraph(registry)

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
            pending.values
                .filter { it.status == RunStatus.AWAITING_APPROVAL && clock() >= it.approval.expiresAtEpochMillis }
                .toList()
                .forEach { expired ->
                    cancelLocked(expired)
                    pending.remove(expired.request.runId)
                }
            policy(question == null || question.isNotBlank() && question.length <= 4096, "E_QUESTION_LIMIT")
            policy((analysis.isDirectChat || analysis.evidence.size in 1..100) && analysis.sourceCandidates.size <= 100, "E_INPUT_LIMIT")
            policy(analysis.sourceCandidates.all { it.reasons.size <= 32 }, "E_INPUT_LIMIT")
            val authorized = analysis.copy(userQuestion = question ?: analysis.userQuestion, conversationContext = emptyList()).freeze()
            val entry = registry.resolve(ExtensionKind.AGENT, agentId)
            policy(entry.usable, "E_AGENT_UNAVAILABLE")
            val manifest = entry.manifest
            policy(manifest.runtime == ScriptRuntime.NONE, "E_SANDBOX_UNAVAILABLE")
            policy(providerId in manifest.allowedProviders, "E_PROVIDER_NOT_ALLOWED")
            policy(
                authorized.originProfiler.name
                    .lowercase()
                    .replace('_', '-') in manifest.contextKinds,
                "E_CONTEXT_UNAVAILABLE",
            )
            policy(authorized.evidence.all { it.kind in manifest.evidenceKinds }, "E_EVIDENCE_KIND")
            policy(
                authorized.evidence
                    .map { it.id }
                    .distinct()
                    .size == authorized.evidence.size,
                "E_EVIDENCE_ID",
            )
            policy(
                authorized.sourceCandidates
                    .map { it.id }
                    .distinct()
                    .size == authorized.sourceCandidates.size,
                "E_CANDIDATE_ID",
            )
            val grants = permissions.toSet()
            if (authorized.isDirectChat) {
                policy(!authorized.userQuestion.isNullOrBlank(), "E_QUESTION_LIMIT")
                policy(agentId == "aps.chat", "E_CONTEXT_UNAVAILABLE")
                policy(grants.all { it == Permission.READ_EVIDENCE || it == Permission.PROVIDER_NETWORK }, "E_PERMISSION_DENIED")
            }
            policy(Permission.READ_EVIDENCE in grants && manifest.permissions.containsAll(grants), "E_PERMISSION_DENIED")
            policy(Permission.DEVICE_WRITE !in grants, "E_UNSUPPORTED_PERMISSION")
            val preparedConnectionRevision = currentConnectionRevision(providerId)
            if (authorized.sourceCandidates.isNotEmpty()) policy(Permission.READ_SOURCE_CANDIDATES in grants, "E_SOURCE_PERMISSION")
            if (authorized.sourceCandidates.any { it.sourceSnippet != null }) {
                policy(Permission.SEND_SOURCE_SNIPPETS in grants && sourceAuthorization(authorized.freeze()), "E_SOURCE_UNAUTHORIZED")
            }
            val contextHash =
                (
                    authorized.copy(userQuestion = null, conversationContext = emptyList()).payloadText() +
                        "\n" + authorized.promptVersion + "\n" + authorized.payloadPolicyVersion
                ).sha256()
            val previous = conversations.load(conversationId)
            policy(
                previous == null || previous.analysisSessionId == authorized.sessionId.value && previous.contextHash == contextHash,
                "E_CONTEXT_CHANGED",
            )
            val contextParent =
                if (parentConversationId != null) {
                    policy(parentConversationId != conversationId && previous == null, "E_PARENT_CONVERSATION")
                    val parent = conversations.load(parentConversationId) ?: failPolicy("E_PARENT_CONVERSATION")
                    policy(parent.analysisSessionId == authorized.sessionId.value && parent.contextHash == contextHash, "E_CONTEXT_CHANGED")
                    policy(
                        parentTurnRunId != null && parent.turns.any { it.runId == parentTurnRunId && it.status.terminal },
                        "E_PARENT_TURN",
                    )
                    parent
                } else {
                    if (parentTurnRunId != null) {
                        policy(previous?.turns?.any { it.runId == parentTurnRunId && it.status.terminal } == true, "E_PARENT_TURN")
                    }
                    previous
                }
            val frozen = authorized.copy(conversationContext = conversationContext(contextParent, parentTurnRunId)).freeze()
            val rawFields =
                sequenceOf(frozen.scope.description, frozen.promptVersion, frozen.payloadPolicyVersion, frozen.userQuestion.orEmpty()) +
                    frozen.evidence.asSequence().flatMap { sequenceOf(it.id, it.kind, it.summary, it.structuredPayload) } +
                    frozen.sourceCandidates.asSequence().flatMap {
                        sequenceOf(it.id, it.relativePath, it.symbol.orEmpty(), it.sourceSnippet.orEmpty()) + it.reasons.asSequence()
                    } + frozen.conversationContext.asSequence().flatMap { sequenceOf(it.runId, it.question.orEmpty(), it.answer) }
            var rawBytes = 0L
            rawFields.forEach { field ->
                rawBytes += field.length.toLong()
                policy(rawBytes <= manifest.budget.maxInputBytes, "E_INPUT_LIMIT")
            }
            val graph = graphs.freeze(entry, frozen, providerId, grants, scriptRunner != null)
            if (scriptSupport != null) {
                val runtimes =
                    graph.agents
                        .flatMap { it.skills }
                        .filter { it.scripts.isNotEmpty() }
                        .map { it.entry.manifest.runtime }
                        .distinct()
                for (runtime in runtimes) policy(scriptSupport.invoke(runtime), "E_SANDBOX_UNAVAILABLE")
            }
            val executable = graph.analysis(frozen)
            val payload =
                try {
                    executable.payloadText()
                } catch (_: Exception) {
                    failPolicy("E_INPUT_PAYLOAD")
                }
            val payloadBytes = payload.toByteArray().size
            policy(payloadBytes <= manifest.budget.maxInputBytes, "E_INPUT_LIMIT")
            policy(frozen.userQuestion == null || frozen.userQuestion!!.isNotBlank(), "E_QUESTION_LIMIT")
            policy(Permission.WRITE_ARTIFACT !in grants || artifactPublisher != null, "E_ARTIFACT_UNAVAILABLE")
            val last = previous?.turns?.lastOrNull()
            policy(last == null || last.generation < Long.MAX_VALUE, "E_GENERATION_LIMIT")
            if (last != null) {
                val old = pending[last.runId]
                if (!last.status.terminal) policy(old != null, "E_RECOVERY_REQUIRED")
                if (old != null) cancelLocked(old)
            }
            // Serial occurrences share one reservation; other active graphs cannot bypass child caps.
            val agents = graph.agents
            for (node in agents) {
                val active =
                    pending.values.count { run ->
                        !run.status.terminal &&
                            run.graph.agents.any { it.entry.key.id == node.entry.key.id }
                    }
                policy(active < node.budget.maxConcurrentRuns, "E_CONCURRENCY_LIMIT")
            }
            policy(pending.values.filter { !it.status.terminal }.sumOf { it.graph.agents.size } + agents.size <= 16, "E_CONCURRENCY_LIMIT")
            val generation = (last?.generation ?: -1) + 1
            val runId = UUID.randomUUID().toString()
            val request =
                ProviderRunRequest(
                    runId,
                    generation,
                    agentId,
                    providerId,
                    model,
                    executable,
                    grants,
                    manifest.budget,
                    connectionRevision = preparedConnectionRevision.takeIf(String::isNotEmpty),
                )
            val approval =
                ApprovalRequest(
                    id = UUID.randomUUID().toString(),
                    runId = runId,
                    sessionId = frozen.sessionId.value,
                    generation = generation,
                    payloadHash =
                        (
                            payload.sha256() + "\n" + contextHash + "\n" + graph.approvalMaterial() +
                                "\nprovider:" + providerId + "\nmodel:" + model + "\n" + grants.map { it.name }.sorted() +
                                "\nparent-turn:" + parentTurnRunId?.sha256().orEmpty() +
                                "\nparent-conversation:" + (previous?.parentConversationId ?: parentConversationId)?.sha256().orEmpty()
                        ).sha256(),
                    permissions = grants,
                    expiresAtEpochMillis = clock() + 300_000,
                )
            val run = PendingRun(conversationId, request, approval, graph, authorized, parentTurnRunId, preparedConnectionRevision)
            val current =
                conversations.load(conversationId) ?: ConversationRecord(
                    id = conversationId,
                    analysisSessionId = frozen.sessionId.value,
                    contextHash = contextHash,
                    parentConversationId = parentConversationId,
                )
            val preparedRecord = current.copy(turns = current.turns + run.turn())
            val history = conversations as? ConversationHistoryRepository
            if (history == null) {
                conversations.save(preparedRecord)
            } else {
                history.savePrepared(preparedRecord, projectChatTurn(conversationId, runId, generation, frozen, clock(), parentTurnRunId))
            }
            pending.entries.removeAll { it.value.conversationId == conversationId }
            pending[runId] = run
            PreparedAgentRun(
                approval = approval.copy(permissions = approval.permissions.toSet()),
                evidenceCount = frozen.evidence.size,
                candidateCount = frozen.sourceCandidates.size,
                payloadBytes = payloadBytes,
                providerId = providerId,
                model = model,
                conversationId = conversationId,
                analysisPreview =
                    frozen.copy(
                        evidence = frozen.evidence.map { it.copy(structuredPayload = "{}") },
                        sourceCandidates = frozen.sourceCandidates.map { it.copy(sourceSnippet = null, reasons = it.reasons.toList()) },
                    ),
                plannedCallCount = graph.callCount,
                plannedScriptCount = graph.scriptCount,
                plannedProviderCallCount = graph.agents.size,
            )
        }

    /** Trusted host/UI call after explicit confirmation. Provider/model content cannot invoke it. */
    public suspend fun approve(approval: ApprovalRequest): Unit =
        mutex.withLock {
            val run = pending[approval.runId] ?: failPolicy("E_RUN_UNAVAILABLE")
            policy(run.status == RunStatus.AWAITING_APPROVAL && run.approval == approval, "E_APPROVAL_MISMATCH")
            policy(clock() < approval.expiresAtEpochMillis, "E_APPROVAL_EXPIRED")
            try {
                verifyConnection(run)
            } catch (failure: AgentPolicyException) {
                try {
                    transition(run, RunStatus.PERMANENT_FAILURE, failure.code)
                } catch (_: Exception) {
                    // Keep the sanitized connection failure even if terminal persistence fails.
                    run.status = RunStatus.PERMANENT_FAILURE
                } finally {
                    pending.remove(approval.runId)
                }
                throw failure
            }
            run.approved = true
        }

    public fun run(runId: String): Flow<AgentRunEvent> =
        flow {
            val run =
                mutex.withLock {
                    val current = pending[runId] ?: failPolicy("E_RUN_UNAVAILABLE")
                    policy(current.status == RunStatus.AWAITING_APPROVAL && current.approved, "E_APPROVAL_REQUIRED")
                    policy(clock() < current.approval.expiresAtEpochMillis, "E_APPROVAL_EXPIRED")
                    try {
                        verify(current)
                    } catch (failure: Exception) {
                        try {
                            transition(current, RunStatus.PERMANENT_FAILURE, failure.safeCode())
                            (conversations as? ConversationHistoryRepository)?.appendAudit(
                                ExecutionAuditEvent(
                                    conversationId = current.conversationId,
                                    runId = runId,
                                    generation = current.request.generation,
                                    sequence = 0,
                                    kind = ExecutionKind.RUN,
                                    phase = ExecutionPhase.FAILED,
                                    subjectId = current.request.agentId,
                                    timestampEpochMillis = clock(),
                                    errorCode = failure.safeCode(),
                                ),
                            )
                        } finally {
                            pending.remove(runId)
                        }
                        throw failure
                    }
                    transition(current, RunStatus.RUNNING)
                    current.job = currentCoroutineContext()[Job]
                    current
                }
            val executor =
                AgentGraphExecutor(
                    run.graph,
                    run.conversationId,
                    run.request,
                    run.approval.payloadHash,
                    providers,
                    conversations,
                    artifactPublisher,
                    scriptRunner,
                    clock,
                    checkCurrent = {
                        currentCoroutineContext().ensureActive()
                        mutex.withLock {
                            policy(pending[runId] === run && run.status == RunStatus.RUNNING, "E_STALE_RUN")
                            verify(run)
                        }
                    },
                    emitEvent = { emit(it) },
                    checkConnection = { verifyConnection(run) },
                )
            try {
                val finalEvents =
                    try {
                        val result = executor.execute()
                        currentCoroutineContext().ensureActive()
                        val projection =
                            projectAnalysisResult(
                                run.conversationId,
                                runId,
                                run.request.generation,
                                run.request.analysis,
                                result,
                                clock(),
                                run.authorizationAnalysis.userQuestion?.sha256(),
                                run.parentTurnRunId,
                            ).copy(artifactHashes = listOfNotNull(executor.publishedArtifactHash))
                        val accepted = projection.toAnalysisResult()
                        mutex.withLock {
                            policy(pending[runId] === run && run.status == RunStatus.RUNNING, "E_STALE_RUN")
                            verify(run)
                            val history = conversations as? ConversationHistoryRepository
                            if (history == null) {
                                transition(run, RunStatus.SUCCEEDED, findingIds = accepted.findings.map { it.id.value })
                            } else {
                                val record = conversations.load(run.conversationId) ?: failPolicy("E_CONVERSATION_UNAVAILABLE")
                                val turn = record.turns.lastOrNull() ?: failPolicy("E_RUN_UNAVAILABLE")
                                policy(turn.runId == runId && turn.generation == run.request.generation, "E_STALE_RUN")
                                history.complete(
                                    record.copy(
                                        turns =
                                            record.turns.dropLast(1) +
                                                turn.copy(status = RunStatus.SUCCEEDED, findingIds = accepted.findings.map { it.id.value }),
                                    ),
                                    projection,
                                )
                                run.status = RunStatus.SUCCEEDED
                            }
                        }
                        executor.finish(ExecutionPhase.SUCCEEDED)
                        listOf(ProviderEvent.StructuredResult(accepted), ProviderEvent.Completed)
                    } catch (failure: CancellationException) {
                        withContext(NonCancellable) {
                            mutex.withLock {
                                try {
                                    finishIfActive(run, RunStatus.CANCELLED)
                                } catch (_: Exception) {
                                    run.status = RunStatus.CANCELLED
                                }
                            }
                            try {
                                executor.finish(ExecutionPhase.CANCELLED)
                            } catch (_: Exception) {
                                // Cancellation is independent of persistence availability.
                            }
                        }
                        throw failure
                    } catch (failure: Exception) {
                        val code = failure.safeCode()
                        val retryable = (failure as? GraphExecutionFailure)?.retryable == true
                        mutex.withLock {
                            try {
                                finishIfActive(run, if (retryable) RunStatus.RETRYABLE_FAILURE else RunStatus.PERMANENT_FAILURE, code)
                            } catch (_: Exception) {
                                run.status = RunStatus.PERMANENT_FAILURE
                            }
                        }
                        try {
                            executor.finish(ExecutionPhase.FAILED, code)
                        } catch (_: Exception) {
                            // Retain the original sanitized failure.
                        }
                        listOf(ProviderEvent.Failure(code, retryable))
                    }
                for (event in finalEvents) {
                    currentCoroutineContext().ensureActive()
                    if (!mutex.withLock { pending[runId] === run }) break
                    emit(
                        AgentRunEvent(
                            runId,
                            run.request.generation,
                            event,
                            budget = executor.ledger.snapshot(),
                            publishedArtifactHash = executor.publishedArtifactHash,
                        ),
                    )
                }
            } finally {
                withContext(NonCancellable) {
                    mutex.withLock {
                        if (!run.status.terminal) {
                            try {
                                finishIfActive(run, RunStatus.CANCELLED)
                            } catch (_: Exception) {
                                run.status = RunStatus.CANCELLED
                            }
                        }
                        run.job = null
                        if (pending[runId] === run) pending.remove(runId)
                    }
                }
            }
        }

    private fun conversationContext(
        parent: ConversationRecord?,
        parentTurnRunId: String?,
        visited: MutableSet<String> = mutableSetOf(),
    ): List<AiConversationContextTurn> {
        val history = conversations as? ConversationHistoryRepository ?: return emptyList()
        if (parent == null) return emptyList()
        policy(visited.size < 16 && visited.add(parent.id), "E_PARENT_CONVERSATION")
        val questions = history.chatTurns(parent.id).associateBy { it.runId }
        val parentGeneration = parentTurnRunId?.let { runId -> parent.turns.single { it.runId == runId }.generation }
        val local =
            parent.turns
                .asReversed()
                .asSequence()
                .filter { it.status == RunStatus.SUCCEEDED && (parentGeneration == null || it.generation <= parentGeneration) }
                .mapNotNull { turn ->
                    val result = history.result(parent.id, turn.runId) ?: return@mapNotNull null
                    policy(
                        result.conversationId == parent.id &&
                            result.runId == turn.runId &&
                            result.generation == turn.generation &&
                            result.sessionId == parent.analysisSessionId,
                        "E_CONTEXT_CHANGED",
                    )
                    val question = questions[turn.runId]?.takeIf { it.generation == turn.generation }?.question
                    val answer =
                        buildString {
                            append(result.summary)
                            result.findings.forEach { finding ->
                                append("\n")
                                append(finding.title)
                                append(": ")
                                append(finding.explanation)
                                append("\n")
                                append(finding.recommendation)
                            }
                        }
                    val boundedAnswer = redactAnalysisText(answer).take(2048)
                    if (boundedAnswer.isBlank()) return@mapNotNull null
                    AiConversationContextTurn(turn.runId, question?.let { redactAnalysisText(it).take(2048) }, boundedAnswer)
                }.take(4)
                .toList()
                .asReversed()
        val parentConversationId = parent.parentConversationId
        if (local.size == 4 || parentConversationId == null) return local
        val firstTurn = parent.turns.firstOrNull() ?: return local
        val branchAnchor =
            questions[firstTurn.runId]?.parentTurnRunId ?: history.result(parent.id, firstTurn.runId)?.parentTurnRunId
        // Legacy conversations without a saved branch anchor cannot safely inherit all parent turns.
        if (branchAnchor == null) return local
        val ancestor = conversations.load(parentConversationId) ?: failPolicy("E_PARENT_CONVERSATION")
        policy(
            ancestor.analysisSessionId == parent.analysisSessionId && ancestor.contextHash == parent.contextHash,
            "E_CONTEXT_CHANGED",
        )
        policy(ancestor.turns.any { it.runId == branchAnchor && it.status.terminal }, "E_PARENT_TURN")
        return (conversationContext(ancestor, branchAnchor, visited) + local).takeLast(4)
    }

    private fun verify(run: PendingRun) {
        verifyConnection(run)
        graphs.verify(run.graph)
        if (run.authorizationAnalysis.sourceCandidates.any { it.sourceSnippet != null }) {
            policy(sourceAuthorization(run.authorizationAnalysis.freeze()), "E_SOURCE_UNAUTHORIZED")
        }
    }

    private fun currentConnectionRevision(providerId: String): String =
        try {
            connectionRevision(providerId)
        } catch (failure: CancellationException) {
            throw failure
        } catch (_: Exception) {
            failPolicy("E_PROVIDER_CONNECTION_CHANGED")
        }

    private fun verifyConnection(run: PendingRun) {
        policy(currentConnectionRevision(run.request.providerId) == run.connectionRevision, "E_PROVIDER_CONNECTION_CHANGED")
    }

    public suspend fun cancel(runId: String): Unit = mutex.withLock { pending[runId]?.let(::cancelLocked) }

    private fun cancelLocked(run: PendingRun) {
        if (run.status.terminal) {
            run.job?.cancel(CancellationException("E_RUN_SUPERSEDED"))
            return
        }
        try {
            transition(run, RunStatus.CANCELLED)
        } finally {
            run.status = RunStatus.CANCELLED
            run.job?.cancel(CancellationException("E_RUN_CANCELLED"))
            if (run.job == null && pending[run.request.runId] === run) pending.remove(run.request.runId)
        }
    }

    private fun finishIfActive(
        run: PendingRun,
        status: RunStatus,
        code: String? = null,
    ) {
        if (pending[run.request.runId] === run && !run.status.terminal) transition(run, status, code)
    }

    private fun transition(
        run: PendingRun,
        status: RunStatus,
        code: String? = null,
        findingIds: List<String> = emptyList(),
    ) {
        try {
            val record = conversations.load(run.conversationId) ?: failPolicy("E_CONVERSATION_UNAVAILABLE")
            val current = record.turns.lastOrNull()
            policy(current?.runId == run.request.runId && current.generation == run.request.generation, "E_STALE_RUN")
            conversations.save(
                record.copy(
                    turns =
                        record.turns.dropLast(1) + checkNotNull(current).copy(status = status, errorCode = code, findingIds = findingIds),
                ),
            )
            run.status = status
        } finally {
            if (status.terminal) run.status = status
        }
    }

    private class PendingRun(
        val conversationId: String,
        val request: ProviderRunRequest,
        val approval: ApprovalRequest,
        val graph: FrozenAgent,
        val authorizationAnalysis: AnalysisRequest,
        val parentTurnRunId: String?,
        val connectionRevision: String,
        var status: RunStatus = RunStatus.AWAITING_APPROVAL,
        var approved: Boolean = false,
        var job: Job? = null,
    ) {
        fun turn() =
            ConversationTurn(
                request.runId,
                request.generation,
                status,
                request.analysis.evidence.map {
                    it.id
                },
                request.analysis.sourceCandidates.map { it.id },
            )
    }
}

private fun AnalysisRequest.freeze(): AnalysisRequest =
    copy(
        evidence = evidence.map { it.copy() },
        sourceCandidates = sourceCandidates.map { it.copy(reasons = it.reasons.toList()) },
        conversationContext = java.util.List.copyOf(conversationContext.map { it.copy() }),
    )

private fun String.sha256(): String = toByteArray().sha256()

internal fun Exception.safeCode(): String =
    when (this) {
        is AgentPolicyException -> code
        is GraphExecutionFailure -> code
        is ScriptException -> code
        else -> "E_AGENT_EXECUTION"
    }.takeIf { it.matches(Regex("E_[A-Z0-9_]{1,80}")) } ?: "E_AGENT_EXECUTION"

private fun policy(
    condition: Boolean,
    code: String,
) {
    if (!condition) failPolicy(code)
}

private fun failPolicy(code: String): Nothing = throw AgentPolicyException(code)

package com.androidperformancestudio.aiplatform.runtime

import com.androidperformancestudio.ai.AnalysisRequest
import com.androidperformancestudio.ai.AnalysisResult
import com.androidperformancestudio.ai.AnalysisSessionId
import com.androidperformancestudio.ai.payloadText
import com.androidperformancestudio.aiplatform.model.ConversationRecord
import com.androidperformancestudio.aiplatform.model.ConversationTurn
import com.androidperformancestudio.aiplatform.model.Permission
import com.androidperformancestudio.aiplatform.model.PlatformJson
import com.androidperformancestudio.aiplatform.model.ProviderEvent
import com.androidperformancestudio.aiplatform.model.ProviderRunRequest
import com.androidperformancestudio.aiplatform.model.RunStatus
import com.androidperformancestudio.aiplatform.providers.ProviderBroker
import com.androidperformancestudio.aiplatform.script.ArtifactProvenance
import com.androidperformancestudio.aiplatform.script.ArtifactPublisher
import com.androidperformancestudio.aiplatform.script.ArtifactRequest
import com.androidperformancestudio.aiplatform.script.ArtifactSection
import com.androidperformancestudio.aiplatform.script.ScriptInput
import com.androidperformancestudio.aiplatform.script.ScriptRunRequest
import com.androidperformancestudio.aiplatform.script.ScriptRunResult
import com.androidperformancestudio.aiplatform.storage.ConversationHistoryRepository
import com.androidperformancestudio.aiplatform.storage.ConversationRepository
import com.androidperformancestudio.aiplatform.storage.ExecutionAuditEvent
import com.androidperformancestudio.aiplatform.storage.ExecutionKind
import com.androidperformancestudio.aiplatform.storage.ExecutionPhase
import com.androidperformancestudio.aiplatform.storage.projectAnalysisResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

internal class GraphExecutionFailure(
    val code: String,
    val retryable: Boolean = false,
) : IllegalStateException(code)

internal class AgentGraphExecutor(
    private val graph: FrozenAgent,
    private val rootConversationId: String,
    private val rootRequest: ProviderRunRequest,
    private val approvalHash: String,
    private val providers: ProviderBroker,
    private val conversations: ConversationRepository,
    private val artifactPublisher: ArtifactPublisher?,
    private val scriptRunner: (suspend (ScriptRunRequest) -> ScriptRunResult)?,
    private val clock: () -> Long,
    private val checkCurrent: suspend () -> Unit,
    private val emitEvent: suspend (AgentRunEvent) -> Unit,
    val ledger: ExecutionBudgetLedger = ExecutionBudgetLedger(rootRequest.budget),
    private val checkConnection: () -> Unit = {},
) {
    private val history = conversations as? ConversationHistoryRepository
    private val skills = BuiltinSkillExecutor()
    private val sequences = mutableMapOf<String, Int>()
    private val children = mutableListOf<ChildRun>()
    var publishedArtifactHash: String? = null
        private set

    private data class Scope(
        val depth: Int,
        val ledger: ExecutionBudgetLedger,
    )

    private data class ChildRun(
        val node: FrozenAgent,
        val conversationId: String,
        val request: ProviderRunRequest,
        val parentId: String,
        var status: RunStatus = RunStatus.RUNNING,
    )

    private data class ExecutedAnalysis(
        val result: AnalysisResult,
        val inputHash: String,
    )

    suspend fun execute(): AnalysisResult {
        audit(ExecutionKind.RUN, ExecutionPhase.STARTED, graph.entry.key.id)
        return executeNode(graph, rootConversationId, rootRequest, listOf(Scope(0, ledger))).result
    }

    private suspend fun executeNode(
        node: FrozenAgent,
        conversationId: String,
        request: ProviderRunRequest,
        scopes: List<Scope>,
    ): ExecutedAnalysis {
        val result =
            withTimeoutOrNull(minOf(node.budget.timeoutMillis, node.budget.maxCpuMillis)) {
                checkCurrent()
                val derived = DerivedAnalysisContext(rootRequest.analysis, approvalHash)
                for (skill in node.skills.filter { it.entry.key.id != "aps.report-artifact" }) {
                    executeSkill(node, skill, request, scopes, null, derived)
                }
                for (child in node.children) {
                    checkCurrent()
                    val analysis = child.analysis(request.analysis.copy(sessionId = AnalysisSessionId.create()))
                    val childRequest =
                        request.copy(
                            runId = UUID.randomUUID().toString(),
                            generation = 0,
                            agentId = child.entry.key.id,
                            analysis = analysis,
                            grantedPermissions = child.permissions,
                            budget = child.budget,
                            approval = null,
                        )
                    val childRun = ChildRun(child, UUID.randomUUID().toString(), childRequest, node.entry.key.id)
                    conversations.save(
                        ConversationRecord(
                            id = childRun.conversationId,
                            analysisSessionId = analysis.sessionId.value,
                            contextHash =
                                (analysis.payloadText() + "\nparent-run:" + request.runId + "\n" + approvalHash)
                                    .toByteArray()
                                    .sha256(),
                            parentConversationId = conversationId,
                            turns = listOf(childRequest.turn(RunStatus.RUNNING)),
                        ),
                    )
                    children += childRun
                    childAudit(childRun, ExecutionPhase.STARTED)
                    val executed =
                        executeNode(
                            child,
                            childRun.conversationId,
                            childRequest,
                            scopes + Scope(child.depth, ExecutionBudgetLedger(child.budget)),
                        )
                    checkCurrent()
                    val projection =
                        projectAnalysisResult(
                            childRun.conversationId,
                            childRequest.runId,
                            childRequest.generation,
                            analysis,
                            derived.redact(executed.result),
                            clock(),
                        )
                    val accepted = projection.toAnalysisResult()
                    val record = conversations.load(childRun.conversationId) ?: throw AgentPolicyException("E_CONVERSATION_UNAVAILABLE")
                    if (record.turns.singleOrNull()?.runId != childRequest.runId) throw AgentPolicyException("E_STALE_RUN")
                    val completed = record.copy(turns = listOf(childRequest.turn(RunStatus.SUCCEEDED, accepted)))
                    // Live parents cannot be referenced by StoredAnalysisResult.parentTurnRunId.
                    // The child's immutable ConversationRecord links the actual immediate parent.
                    if (history == null) conversations.save(completed) else history.complete(completed, projection)
                    childRun.status = RunStatus.SUCCEEDED
                    childAudit(childRun, ExecutionPhase.SUCCEEDED)
                    derived.child(child.entry, childRequest, executed.inputHash, accepted)
                }
                checkCurrent()
                val providerRequest = request.copy(analysis = derived.augment(request.analysis))
                val payload = providerRequest.analysis.payloadText().toByteArray()
                enter(
                    scopes,
                    node.depth,
                    payload.size.toLong(),
                )
                providerAudit(node, conversationId, providerRequest, ExecutionPhase.STARTED)
                checkCurrent()
                var result: AnalysisResult? = null
                var completed = false
                var failure: ProviderEvent.Failure? = null
                checkConnection()
                providers.run(providerRequest, node.entry.manifest.allowedProviders).collect { event ->
                    checkCurrent()
                    when (event) {
                        is ProviderEvent.StructuredResult -> {
                            result = event.result
                            consume(scopes, event.result.encodedBytes())
                        }
                        is ProviderEvent.TextDelta ->
                            consume(
                                scopes,
                                event.text
                                    .toByteArray()
                                    .size
                                    .toLong(),
                            )
                        ProviderEvent.Completed -> completed = true
                        is ProviderEvent.Failure -> failure = event
                        ProviderEvent.Cancelled -> throw CancellationException("E_PROVIDER_CANCELLED")
                        is ProviderEvent.ToolCall, is ProviderEvent.ApprovalRequired -> throw AgentPolicyException("E_TOOL_UNAVAILABLE")
                        else -> Unit
                    }
                }
                checkCurrent()
                failure?.let { throw GraphExecutionFailure(it.code, it.retryable) }
                if (!completed || result == null) throw AgentPolicyException("E_PROVIDER_PROTOCOL")
                // Broker validation is per independent request/session, before either persistence or Skills.
                val accepted = checkNotNull(result)
                providerAudit(node, conversationId, request, ExecutionPhase.SUCCEEDED)
                for (skill in node.skills.filter { it.entry.key.id == "aps.report-artifact" }) {
                    executeSkill(node, skill, providerRequest, scopes, accepted, derived)
                }
                checkCurrent()
                ExecutedAnalysis(accepted, payload.sha256())
            }
        currentCoroutineContext().ensureActive()
        return result ?: throw GraphExecutionFailure(
            if (node.budget.maxCpuMillis < node.budget.timeoutMillis) "E_CPU_BUDGET" else "E_PROVIDER_TIMEOUT",
            retryable = node.budget.maxCpuMillis >= node.budget.timeoutMillis,
        )
    }

    private suspend fun executeSkill(
        node: FrozenAgent,
        skill: FrozenSkill,
        request: ProviderRunRequest,
        scopes: List<Scope>,
        result: AnalysisResult?,
        derived: DerivedAnalysisContext,
    ) {
        checkCurrent()
        val analysis = request.analysis.restrictTo(skill.permissions)
        if (skill.scripts.isNotEmpty()) {
            for ((path, _) in skill.scripts) {
                checkCurrent()
                val input =
                    ScriptInput(
                        skill.entry.manifest.inputSchema,
                        analysis.evidence.map { it.id }.toSet(),
                        analysis.sourceCandidates.map { it.id }.toSet(),
                        PlatformJson.codec.parseToJsonElement(analysis.payloadText()) as JsonObject,
                    )
                val inputBytes = PlatformJson.codec.encodeToString(input).toByteArray()
                if (inputBytes.size > skill.budget.maxInputBytes) throw AgentPolicyException("E_SCRIPT_INPUT_LIMIT")
                enter(scopes, node.depth, inputBytes.size.toLong())
                audit(ExecutionKind.SCRIPT, ExecutionPhase.STARTED, skill.entry.key.id, node.depth, node.entry.key.id)
                val runner = scriptRunner ?: throw AgentPolicyException("E_SCRIPT_UNAVAILABLE")
                val scriptRequest =
                    ScriptRunRequest(
                        skill.entry.key,
                        path,
                        PlatformJson.codec.decodeFromString<ScriptInput>(inputBytes.decodeToString()),
                        skill.permissions.toSet(),
                        skill.budget,
                    )
                try {
                    checkCurrent()
                    val checked =
                        withTimeoutOrNull(minOf(skill.budget.timeoutMillis, skill.budget.maxCpuMillis)) { runner(scriptRequest) }
                    currentCoroutineContext().ensureActive()
                    if (checked == null) throw GraphExecutionFailure("E_SCRIPT_TIMEOUT")
                    checkCurrent()
                    validateScriptResult(skill, input, inputBytes, checked)
                    val bytes =
                        maxOf(
                            checked.output
                                .toString()
                                .toByteArray()
                                .size,
                            checked.audit.stdoutBytes,
                        ).toLong() + checked.audit.stderrBytes
                    if (bytes > skill.budget.maxOutputBytes) throw AgentPolicyException("E_SCRIPT_OUTPUT_LIMIT")
                    consume(scopes, bytes)
                    derived.script(skill, path, request, inputBytes.sha256(), checked.output)
                    audit(ExecutionKind.SCRIPT, ExecutionPhase.SUCCEEDED, skill.entry.key.id, node.depth, node.entry.key.id)
                } catch (failure: Exception) {
                    withContext(NonCancellable) {
                        try {
                            audit(
                                ExecutionKind.SCRIPT,
                                if (failure is CancellationException) ExecutionPhase.CANCELLED else ExecutionPhase.FAILED,
                                skill.entry.key.id,
                                node.depth,
                                node.entry.key.id,
                                errorCode = if (failure is CancellationException) null else failure.safeCode(),
                                emit = false,
                            )
                        } catch (_: Exception) {
                            // Preserve the original failure even if terminal audit storage is unavailable.
                        }
                    }
                    throw failure
                }
            }
            return
        }
        val references =
            (
                analysis.evidence.map {
                    it.id
                } + analysis.sourceCandidates.map { it.id }
            ).sumOf { it.toByteArray().size.toLong() }
        if (references > skill.budget.maxInputBytes) throw AgentPolicyException("E_INPUT_BUDGET")
        enter(scopes, node.depth, references)
        audit(ExecutionKind.SKILL, ExecutionPhase.STARTED, skill.entry.key.id, node.depth, node.entry.key.id)
        val checked = skills.execute(skill.entry.key.id, analysis, result)
        val bytes =
            checked.artifact?.let {
                it.html
                    .toByteArray()
                    .size
                    .toLong() + it.markdown.toByteArray().size
            }
                ?: checked.numericFacts.size * 32L
        if (bytes > skill.budget.maxOutputBytes) throw AgentPolicyException("E_OUTPUT_BUDGET")
        consume(scopes, bytes)
        checkCurrent()
        audit(ExecutionKind.SKILL, ExecutionPhase.SUCCEEDED, skill.entry.key.id, node.depth, node.entry.key.id, checked)
        if (checked.artifact != null && Permission.WRITE_ARTIFACT in skill.permissions) {
            checkCurrent()
            enter(scopes, node.depth, 0)
            audit(ExecutionKind.ARTIFACT, ExecutionPhase.STARTED, skill.entry.key.id, node.depth, node.entry.key.id)
            val publisher = artifactPublisher ?: throw AgentPolicyException("E_ARTIFACT_UNAVAILABLE")
            val artifact =
                publisher.publish(
                    ArtifactRequest(
                        "Performance analysis",
                        checked.artifact.markdown.chunked(32_768).mapIndexed {
                            index,
                            text,
                            ->
                            ArtifactSection("Validated report ${index + 1}", text)
                        },
                        ArtifactProvenance(
                            sessionId = request.analysis.sessionId.value,
                            generation = request.generation,
                            agentId = request.agentId,
                            agentVersion = node.entry.key.version,
                            evidenceIds = checked.evidenceIds.toSet(),
                            candidateIds = checked.candidateIds.toSet(),
                            inputHash = approvalHash,
                        ),
                        skill.permissions,
                        analysis.evidence.map { it.id }.toSet(),
                        analysis.sourceCandidates.map { it.id }.toSet(),
                    ),
                )
            checkCurrent()
            consume(scopes, artifact.byteCount.toLong())
            if (node.depth == 0) publishedArtifactHash = artifact.hash
            audit(ExecutionKind.ARTIFACT, ExecutionPhase.SUCCEEDED, skill.entry.key.id, node.depth, node.entry.key.id)
        }
    }

    private fun enter(
        scopes: List<Scope>,
        depth: Int,
        inputBytes: Long,
    ) {
        scopes.forEach { it.ledger.enter(depth - it.depth, inputBytes) }
    }

    private fun consume(
        scopes: List<Scope>,
        bytes: Long,
    ) {
        scopes.forEach { it.ledger.consumeOutput(bytes) }
    }

    suspend fun finish(
        phase: ExecutionPhase,
        code: String? = null,
    ) {
        if (phase == ExecutionPhase.SUCCEEDED) {
            audit(ExecutionKind.RUN, phase, graph.entry.key.id)
            return
        }
        // Terminal bookkeeping cannot rely on an active/cancellable collector or a successful emit.
        withContext(NonCancellable) {
            for (child in children.filter { !it.status.terminal }) {
                try {
                    val status = if (phase == ExecutionPhase.CANCELLED) RunStatus.CANCELLED else RunStatus.PERMANENT_FAILURE
                    val record = conversations.load(child.conversationId) ?: continue
                    conversations.save(record.copy(turns = listOf(child.request.turn(status).copy(errorCode = code))))
                    child.status = status
                } catch (_: Exception) {
                    // A still-RUNNING disk record remains recoverable as INTERRUPTED, never success.
                }
                try {
                    childAudit(child, phase, code, emit = false)
                } catch (_: Exception) {
                    // Do not mask cancellation/failure when audit storage is unavailable.
                }
            }
            audit(ExecutionKind.RUN, phase, graph.entry.key.id, errorCode = code, emit = false)
        }
    }

    private suspend fun childAudit(
        child: ChildRun,
        phase: ExecutionPhase,
        code: String? = null,
        emit: Boolean = true,
    ) {
        audit(
            ExecutionKind.CHILD_AGENT,
            phase,
            child.node.entry.key.id,
            child.node.depth,
            child.parentId,
            errorCode = code,
            emit = emit,
            relatedConversationId = child.conversationId,
            relatedRunId = child.request.runId,
        )
        audit(
            ExecutionKind.RUN,
            phase,
            child.node.entry.key.id,
            child.node.depth,
            child.parentId,
            errorCode = code,
            conversationId = child.conversationId,
            request = child.request,
            emit = false,
        )
    }

    private suspend fun providerAudit(
        node: FrozenAgent,
        conversationId: String,
        request: ProviderRunRequest,
        phase: ExecutionPhase,
    ) {
        audit(ExecutionKind.PROVIDER, phase, request.providerId, node.depth, node.entry.key.id)
        if (node.depth > 0) {
            audit(
                ExecutionKind.PROVIDER,
                phase,
                request.providerId,
                node.depth,
                node.entry.key.id,
                conversationId = conversationId,
                request = request,
                emit = false,
            )
        }
    }

    private suspend fun audit(
        kind: ExecutionKind,
        phase: ExecutionPhase,
        subject: String,
        depth: Int = 0,
        parent: String? = null,
        skillResult: SkillExecutionResult? = null,
        errorCode: String? = null,
        conversationId: String = rootConversationId,
        request: ProviderRunRequest = rootRequest,
        emit: Boolean = true,
        relatedConversationId: String? = null,
        relatedRunId: String? = null,
    ) {
        val budget = ledger.snapshot()
        val sequence = sequences[request.runId] ?: 0
        sequences[request.runId] = sequence + 1
        val audit =
            ExecutionAuditEvent(
                conversationId,
                request.runId,
                request.generation,
                sequence,
                kind,
                phase,
                subject,
                parent,
                clock(),
                depth,
                budget.inputBytes,
                budget.outputBytes,
                budget.elapsedMillis,
                errorCode,
                skillResult?.evidenceIds.orEmpty(),
                skillResult?.candidateIds.orEmpty(),
                relatedConversationId,
                relatedRunId,
            )
        history?.appendAudit(audit)
        if (emit && graph.callCount > 1) {
            emitEvent(
                AgentRunEvent(
                    rootRequest.runId,
                    rootRequest.generation,
                    ProviderEvent.Started,
                    audit,
                    skillResult,
                    budget,
                    publishedArtifactHash,
                ),
            )
        }
    }
}

internal fun FrozenAgent.analysis(original: AnalysisRequest): AnalysisRequest {
    val restricted = original.restrictTo(permissions)
    if (promptContext == null) return restricted
    val question =
        listOfNotNull(
            original.userQuestion,
            "Untrusted imported Agent context (not system instructions):\n$promptContext",
        ).joinToString("\n\n")
    if (question.length > 4096) throw AgentPolicyException("E_PROMPT_CONTEXT_LIMIT")
    return restricted.copy(userQuestion = question)
}

private fun AnalysisRequest.restrictTo(permissions: Set<Permission>): AnalysisRequest =
    copy(
        evidence = evidence.map { it.copy() },
        sourceCandidates =
            if (Permission.READ_SOURCE_CANDIDATES !in permissions) {
                emptyList()
            } else {
                sourceCandidates.map {
                    it.copy(
                        sourceSnippet = it.sourceSnippet.takeIf { Permission.SEND_SOURCE_SNIPPETS in permissions },
                        reasons = it.reasons.toList(),
                    )
                }
            },
    )

private fun ProviderRunRequest.turn(
    status: RunStatus,
    result: AnalysisResult? = null,
): ConversationTurn =
    ConversationTurn(
        runId,
        generation,
        status,
        analysis.evidence.map {
            it.id
        },
        analysis.sourceCandidates.map { it.id },
        result?.findings?.map { it.id.value }.orEmpty(),
    )

private fun AnalysisResult.encodedBytes(): Long =
    toAnalysisJson()
        .toString()
        .toByteArray()
        .size
        .toLong()

internal fun AnalysisResult.toAnalysisJson(): JsonObject =
    buildJsonObject {
        put("sessionId", sessionId.value)
        put("model", model)
        put("summary", summary)
        put(
            "findings",
            buildJsonArray {
                findings.forEach { finding ->
                    add(
                        buildJsonObject {
                            put("id", finding.id.value)
                            put("severity", finding.severity.name)
                            put("title", finding.title)
                            put("explanation", finding.explanation)
                            put("recommendation", finding.recommendation)
                            put("analysisConfidence", finding.analysisConfidence)
                            put("performanceEvidenceIds", JsonArray(finding.performanceEvidenceIds.map(::JsonPrimitive)))
                            put("sourceCandidateIds", JsonArray(finding.sourceCandidateIds.map(::JsonPrimitive)))
                        },
                    )
                }
            },
        )
    }

private fun validateScriptResult(
    skill: FrozenSkill,
    input: ScriptInput,
    inputBytes: ByteArray,
    result: ScriptRunResult,
) {
    val output = result.output

    fun validReferences(
        name: String,
        allowed: Set<String>,
    ): Boolean {
        val values = output[name] as? JsonArray ?: return false
        val ids = values.map { (it as? JsonPrimitive)?.takeIf { value -> value.isString }?.content ?: return false }
        return ids.size <= 100 && ids.distinct().size == ids.size && ids.all { it in allowed }
    }
    val audit = result.audit
    if (output.keys != setOf("schema", "evidenceIds", "candidateIds", "result") ||
        (output["schema"] as? JsonPrimitive)?.content != skill.entry.manifest.outputSchema ||
        output["result"] !is JsonObject ||
        !validReferences("evidenceIds", input.evidenceIds) ||
        !validReferences("candidateIds", input.candidateIds)
    ) {
        throw AgentPolicyException("E_SCRIPT_OUTPUT_SCHEMA")
    }
    if (audit.extensionId != skill.entry.key.id ||
        audit.extensionVersion != skill.entry.key.version ||
        audit.inputHash != inputBytes.sha256() ||
        audit.runtime != skill.entry.manifest.runtime ||
        audit.exitCode != 0 ||
        audit.stdoutBytes < 0 ||
        audit.stderrBytes < 0 ||
        !audit.outputHash.matches(Regex("[a-f0-9]{64}")) ||
        !audit.stderrHash.matches(Regex("[a-f0-9]{64}"))
    ) {
        throw AgentPolicyException("E_SCRIPT_AUDIT")
    }
}

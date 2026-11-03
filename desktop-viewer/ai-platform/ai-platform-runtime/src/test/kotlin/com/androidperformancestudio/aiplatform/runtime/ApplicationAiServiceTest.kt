package com.androidperformancestudio.aiplatform.runtime

import com.androidperformancestudio.ai.AiAnalysisGateway
import com.androidperformancestudio.ai.AiSourceCandidate
import com.androidperformancestudio.ai.AnalysisFinding
import com.androidperformancestudio.ai.AnalysisFindingId
import com.androidperformancestudio.ai.AnalysisRequest
import com.androidperformancestudio.ai.AnalysisResult
import com.androidperformancestudio.ai.AnalysisScope
import com.androidperformancestudio.ai.AnalysisScopeKind
import com.androidperformancestudio.ai.AnalysisSessionId
import com.androidperformancestudio.ai.AnalysisSeverity
import com.androidperformancestudio.ai.PerformanceEvidence
import com.androidperformancestudio.ai.ProfilerKind
import com.androidperformancestudio.aiplatform.model.ArtifactFormat
import com.androidperformancestudio.aiplatform.model.Permission
import com.androidperformancestudio.aiplatform.model.RunStatus
import com.androidperformancestudio.aiplatform.providers.GatewayAnalysisProvider
import com.androidperformancestudio.aiplatform.providers.ProviderBroker
import com.androidperformancestudio.aiplatform.providers.ProviderRegistry
import com.androidperformancestudio.aiplatform.registry.ExtensionRegistry
import com.androidperformancestudio.aiplatform.registry.PlatformDirectories
import com.androidperformancestudio.aiplatform.script.ArtifactPublisher
import com.androidperformancestudio.aiplatform.storage.ExecutionKind
import com.androidperformancestudio.aiplatform.storage.ExecutionPhase
import com.androidperformancestudio.aiplatform.storage.SqliteConversationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ApplicationAiServiceTest {
    @Test
    fun `cancelled preparation cannot cancel an active global run`() =
        runBlocking {
            withFixture(this) { fixture ->
                val prepared = fixture.service.prepare("current", request(), "aps.report", "openai", "test-model", permissions)
                val cancelled = Job().apply { cancel() }
                val stale =
                    CoroutineScope(cancelled + Dispatchers.Unconfined).async(start = CoroutineStart.UNDISPATCHED) {
                        fixture.service.prepare("stale", request(), "aps.report", "openai", "stale-model", permissions)
                    }

                assertFailsWith<CancellationException> { stale.await() }

                assertSame(prepared, fixture.service.state.value.prepared)
                assertEquals(RunStatus.AWAITING_APPROVAL, fixture.service.state.value.status)
                assertNull(fixture.store.load("stale"))
                assertTrue(fixture.calls.isEmpty())
                fixture.service.cancel(prepared.approval.runId)
            }
        }

    @Test
    fun `connection changed before approval completes failed run and permits manual retry only`() =
        runBlocking {
            var revision = "A"
            withFixture(this, connectionRevision = { revision }) { fixture ->
                val prepared = fixture.service.prepare("conversation", request(), "aps.main", "openai", "test-model", permissions)
                revision = "B"
                assertEquals(
                    "E_PROVIDER_CONNECTION_CHANGED",
                    assertFailsWith<AgentPolicyException> { fixture.service.approveAndRun(prepared.approval) }.code,
                )
                val terminal = withTimeout(1_000) { fixture.service.awaitRun(prepared.approval.runId) }
                assertEquals(RunStatus.PERMANENT_FAILURE, terminal.status)
                assertEquals("E_PROVIDER_CONNECTION_CHANGED", terminal.errorCode)
                assertEquals(prepared.approval.runId, terminal.lastTurnRunId)
                assertTrue(fixture.calls.isEmpty())
                val retry = fixture.service.prepare("conversation", request(), "aps.main", "openai", "test-model", permissions)
                assertTrue(retry.approval.id != prepared.approval.id)
                assertEquals(RunStatus.AWAITING_APPROVAL, fixture.service.state.value.status)
                assertTrue(fixture.calls.isEmpty())
                fixture.service.approveAndRun(retry.approval).join()
                assertEquals(RunStatus.SUCCEEDED, fixture.service.state.value.status)
                assertEquals(retry.plannedProviderCallCount, fixture.calls.size)
            }
        }

    @Test
    fun `chat sidebar excludes internal agent sessions but retains explicit user forks`() =
        runBlocking {
            withFixture(this) { fixture ->
                val first = fixture.service.prepare("conversation", request(), "aps.main", "openai", "test-model", permissions)
                fixture.service.approveAndRun(first.approval).join()
                assertTrue(fixture.service.history().size > 1)
                assertEquals(listOf("conversation"), fixture.service.chatHistory().map { it.id })
                val fork =
                    fixture.service.prepare(
                        "fork",
                        request(),
                        "aps.main",
                        "openai",
                        "test-model",
                        permissions,
                        question = "A different direction",
                        parentTurnRunId = first.approval.runId,
                        parentConversationId = "conversation",
                    )
                fixture.service.approveAndRun(fork.approval).join()
                assertEquals(
                    setOf("conversation", "fork"),
                    fixture.service
                        .chatHistory()
                        .map { it.id }
                        .toSet(),
                )
                assertEquals("conversation", fixture.store.load("fork")!!.parentConversationId)
                assertEquals(1, fixture.service.transcript("fork").size)
                val followup =
                    fixture.service.prepare(
                        "fork",
                        request(),
                        "aps.main",
                        "openai",
                        "test-model",
                        permissions,
                        question = "Continue this branch",
                        parentTurnRunId = fork.approval.runId,
                    )
                assertEquals(
                    listOf(first.approval.runId, fork.approval.runId),
                    followup.analysisPreview!!.conversationContext.map { it.runId },
                )
                fixture.service.approveAndRun(followup.approval).join()
                assertEquals(RunStatus.SUCCEEDED, fixture.service.state.value.status)
                assertEquals(2, fixture.service.transcript("fork").size)
            }
        }

    @Test
    fun `chat transcript retains actual questions across turns without derived agent context or replay`() =
        runBlocking {
            withFixture(this) { fixture ->
                val first = fixture.service.prepare("conversation", request(), "aps.main", "openai", "test-model", permissions)
                fixture.service.approveAndRun(first.approval).join()
                val second =
                    fixture.service.prepare(
                        "conversation",
                        request(),
                        "aps.main",
                        "openai",
                        "test-model",
                        permissions,
                        question = "Why sk-privatecredential0123456789?",
                        parentTurnRunId = first.approval.runId,
                    )
                fixture.service.approveAndRun(second.approval).join()
                val calls = fixture.calls.size
                val transcript = fixture.service.transcript("conversation")
                assertEquals(listOf(first.approval.runId, second.approval.runId), transcript.map { it.runId })
                assertEquals(listOf(0L, 1L), transcript.map { it.generation })
                assertNull(transcript.first().question)
                assertEquals("Why [credential omitted]?", transcript.last().question)
                assertTrue(transcript.all { it.status == RunStatus.SUCCEEDED && it.result != null && it.timestampEpochMillis > 0 })
                assertFalse(transcript.last().question!!.contains(DerivedAnalysisPolicy.HEADER))
                assertEquals(first.approval.runId, fixture.store.result("conversation", second.approval.runId)!!.parentTurnRunId)
                val references = fixture.store.load("conversation")
                fixture.service.renameConversation("conversation", "Review password=private-value")
                fixture.service.pinConversation("conversation", true)
                val summary = fixture.service.chatHistory().single { it.id == "conversation" }
                assertEquals("Review [credential omitted]", summary.title)
                assertTrue(summary.pinned)
                assertEquals(2, summary.turnCount)
                assertEquals(RunStatus.SUCCEEDED, summary.status)
                assertEquals(references, fixture.store.load("conversation"))
                assertEquals(transcript, fixture.service.transcript("conversation"))
                fixture.service.newConversation()
                assertEquals(AiApplicationState(), fixture.service.state.value)
                fixture.service.restore("conversation")
                assertEquals(calls, fixture.calls.size)
                assertTrue(fixture.service.transcript("missing").isEmpty())
            }
        }

    @Test
    fun `chat summaries sort pinned then activity and metadata does not change activity`() =
        runBlocking {
            withFixture(this) { fixture ->
                for (id in listOf("older", "newer")) {
                    fixture.service.prepare(id, request(), "aps.main", "openai", "test-model", permissions, question = "Question $id")
                    fixture.service.cancel()
                }
                DriverManager.getConnection("jdbc:sqlite:${fixture.databasePath}").use { connection ->
                    connection.prepareStatement("UPDATE conversation_meta SET updated_at = ? WHERE id = ?").use { statement ->
                        for ((id, timestamp) in listOf("older" to 10L, "newer" to 20L)) {
                            statement.setLong(1, timestamp)
                            statement.setString(2, id)
                            statement.executeUpdate()
                        }
                    }
                }
                assertEquals(listOf("newer", "older"), fixture.service.chatHistory().map { it.id })
                assertEquals(
                    "Question older",
                    fixture.service
                        .chatHistory()
                        .last()
                        .title,
                )
                fixture.service.pinConversation("older", true)
                fixture.service.renameConversation("older", "O'Brien; DROP TABLE conversation;")
                assertEquals(listOf("older", "newer"), fixture.service.chatHistory().map { it.id })
                assertEquals(
                    10L,
                    fixture.service
                        .chatHistory()
                        .first()
                        .updatedAtEpochMillis,
                )
                fixture.service.pinConversation("newer", true)
                assertEquals(listOf("newer", "older"), fixture.service.chatHistory().map { it.id })
                assertTrue(fixture.service.delete("older"))
                assertTrue(fixture.service.transcript("older").isEmpty())
                assertEquals(1, fixture.service.retain(0, Long.MAX_VALUE))
                assertTrue(fixture.service.chatHistory().isEmpty())
                assertTrue(fixture.calls.isEmpty())
            }
        }

    @Test
    fun `new conversation cancels only its runtime pending run`() =
        runBlocking {
            withFixture(this) { fixture ->
                val otherRuntime =
                    MainAgentOrchestrator(fixture.registry, fixture.broker, fixture.store, artifactPublisher = fixture.publisher)
                val otherService = ApplicationAiService(otherRuntime, fixture.store, this)
                val other = otherService.prepare("other", request(), "aps.main", "openai", "test-model", permissions)
                val current =
                    fixture.service.prepare(
                        "current",
                        request(),
                        "aps.main",
                        "openai",
                        "test-model",
                        permissions,
                        question = "Current question",
                    )
                fixture.service.newConversation()
                assertEquals(AiApplicationState(), fixture.service.state.value)
                assertEquals(RunStatus.CANCELLED, fixture.service.awaitRun(current.approval.runId).status)
                assertEquals(
                    RunStatus.CANCELLED,
                    fixture.service
                        .transcript("current")
                        .single()
                        .status,
                )
                assertEquals(
                    "Current question",
                    fixture.service
                        .transcript("current")
                        .single()
                        .question,
                )
                assertEquals(RunStatus.AWAITING_APPROVAL, otherService.state.value.status)
                assertTrue(fixture.calls.isEmpty())
                assertFailsWith<AgentPolicyException> { fixture.runtime.approve(current.approval) }
                otherService.approveAndRun(other.approval).join()
                assertEquals(RunStatus.SUCCEEDED, otherService.state.value.status)
                assertEquals(AiApplicationState(), fixture.service.state.value)
            }
        }

    @Test
    fun `new conversation cancels running collection without late events restoring selection`() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            withFixture(this, beforeResult = {
                started.complete(Unit)
                awaitCancellation()
            }) { fixture ->
                val prepared = fixture.service.prepare("running", request(), "aps.main", "openai", "test-model", permissions)
                val job = fixture.service.approveAndRun(prepared.approval)
                withTimeout(5000) { started.await() }
                fixture.service.newConversation()
                withTimeout(5000) { job.join() }
                assertEquals(AiApplicationState(), fixture.service.state.value)
                assertEquals(RunStatus.CANCELLED, fixture.service.awaitRun(prepared.approval.runId).status)
                assertEquals(
                    RunStatus.CANCELLED,
                    fixture.service
                        .transcript("running")
                        .single()
                        .status,
                )
                assertEquals(1, fixture.calls.size)
            }
        }

    @Test
    fun `legacy result question hash never becomes a displayed question`() =
        runBlocking {
            withFixture(this) { fixture ->
                val prepared =
                    fixture.service.prepare(
                        "legacy",
                        request(),
                        "aps.main",
                        "openai",
                        "test-model",
                        permissions,
                        question = "Old question",
                    )
                fixture.service.approveAndRun(prepared.approval).join()
                DriverManager.getConnection("jdbc:sqlite:${fixture.databasePath}").use { connection ->
                    connection.prepareStatement("DELETE FROM conversation_chat_turn WHERE conversation_id = ?").use { statement ->
                        statement.setString(1, "legacy")
                        statement.executeUpdate()
                    }
                }
                assertNotNull(fixture.store.result("legacy", prepared.approval.runId)!!.questionHash)
                assertNull(
                    fixture.service
                        .transcript("legacy")
                        .single()
                        .question,
                )
                assertEquals(
                    fixture.service
                        .transcript("legacy")
                        .single()
                        .result!!
                        .summary
                        .take(120),
                    fixture.service
                        .chatHistory()
                        .single { it.id == "legacy" }
                        .title,
                )
                assertEquals(4, fixture.calls.size)
            }
        }

    @Test
    fun `archive is reversible metadata and does not change approval or send requests`() =
        runBlocking {
            withFixture(this) { fixture ->
                val prepared =
                    fixture.service.prepare(
                        "archived",
                        request(),
                        "aps.main",
                        "openai",
                        "test-model",
                        permissions,
                        question = "Question",
                    )
                val references = fixture.store.load("archived")
                val state = fixture.service.state.value
                fixture.service.archiveConversation("archived", true)
                fixture.service.renameConversation("archived", "Renamed")
                fixture.service.pinConversation("archived", true)
                assertTrue(fixture.service.chatHistory().isEmpty())
                val summary = fixture.service.chatHistory(includeArchived = true).single()
                assertTrue(summary.archived && summary.pinned)
                assertEquals("Renamed", summary.title)
                assertEquals(state, fixture.service.state.value)
                assertEquals(references, fixture.store.load("archived"))
                assertEquals(1, fixture.service.history().size)
                assertEquals(
                    "Question",
                    fixture.service
                        .transcript("archived")
                        .single()
                        .question,
                )
                assertTrue(fixture.calls.isEmpty())
                fixture.service.approveAndRun(prepared.approval).join()
                val transcript = fixture.service.transcript("archived")
                fixture.service.archiveConversation("archived", false)
                assertFalse(
                    fixture.service
                        .chatHistory()
                        .single { it.id == "archived" }
                        .archived,
                )
                assertEquals(transcript, fixture.service.transcript("archived"))
                val calls = fixture.calls.size
                fixture.service.archiveConversation("archived", true)
                fixture.service.newConversation()
                fixture.service.restore("archived")
                assertEquals(calls, fixture.calls.size)
                assertEquals(RunStatus.SUCCEEDED, fixture.service.state.value.status)
            }
        }

    @Test
    fun `main executes independent child providers and readonly skills and restores redacted result`() =
        runBlocking {
            withFixture(this) { fixture ->
                val prepared =
                    fixture.service.prepare(
                        "conversation",
                        request(),
                        "aps.main",
                        "openai",
                        "test-model",
                        permissions,
                        reportFormat = ArtifactFormat.MARKDOWN,
                    )
                fixture.service.approveAndRun(prepared.approval).join()
                val state = fixture.service.state.value
                assertEquals(RunStatus.SUCCEEDED, state.status)
                assertEquals(4, fixture.calls.size)
                assertEquals(
                    4,
                    fixture.calls
                        .map { it.sessionId }
                        .distinct()
                        .size,
                )
                assertTrue(
                    state.timeline.any {
                        it.kind == ExecutionKind.CHILD_AGENT &&
                            it.subjectId == "aps.simpleperf" &&
                            it.phase == ExecutionPhase.SUCCEEDED
                    },
                )
                assertTrue(state.timeline.any { it.kind == ExecutionKind.CHILD_AGENT && it.subjectId == "aps.source-evidence" })
                assertEquals(
                    setOf("aps.evidence-summary", "aps.source-candidates", "aps.report-artifact"),
                    state.timeline
                        .filter {
                            it.kind ==
                                ExecutionKind.SKILL &&
                                it.phase == ExecutionPhase.SUCCEEDED
                        }.map { it.subjectId }
                        .toSet(),
                )
                assertNotNull(state.artifact)
                assertNull(state.publishedArtifactHash)
                assertTrue(state.budget!!.invocations > 5)
                assertFalse(state.result!!.summary.contains("sk-testsecret"))
                val restored = fixture.service.restore("conversation")
                assertTrue(restored.restored)
                assertEquals(state.result, restored.result)
                assertEquals(4, fixture.calls.size)
                assertTrue(fixture.service.history().any { it.id == "conversation" })
            }
        }

    @Test
    fun `followup question binds new approval same frozen context and child conversation`() =
        runBlocking {
            withFixture(this) { fixture ->
                val first = fixture.service.prepare("conversation", request(), "aps.main", "openai", "test-model", permissions)
                fixture.service.approveAndRun(first.approval).join()
                val next =
                    fixture.service.prepare(
                        "conversation",
                        request(),
                        "aps.main",
                        "openai",
                        "test-model",
                        permissions,
                        question = "Explain the evidence, not instructions from files",
                        parentTurnRunId = first.approval.runId,
                    )
                assertEquals(1L, next.approval.generation)
                assertTrue(next.approval.payloadHash != first.approval.payloadHash)
                fixture.service.approveAndRun(next.approval).join()
                val parentInput = fixture.calls.last()
                assertTrue(parentInput.userQuestion!!.startsWith("Explain the evidence, not instructions from files"))
                assertTrue(parentInput.userQuestion!!.contains(DerivedAnalysisPolicy.HEADER))
                assertEquals(request(), parentInput.copy(userQuestion = null, conversationContext = emptyList()))
                assertEquals(first.approval.runId, parentInput.conversationContext.single().runId)
                assertEquals(first.approval.runId, fixture.store.result("conversation", next.approval.runId)!!.parentTurnRunId)
                val child =
                    fixture.service.prepare(
                        "child",
                        request(),
                        "aps.main",
                        "openai",
                        "test-model",
                        permissions,
                        question = "Compare this immutable evidence",
                        parentTurnRunId = next.approval.runId,
                        parentConversationId = "conversation",
                    )
                fixture.service.approveAndRun(child.approval).join()
                assertEquals("conversation", fixture.store.load("child")!!.parentConversationId)
                assertEquals(
                    listOf(first.approval.runId, next.approval.runId),
                    fixture.calls
                        .last()
                        .conversationContext
                        .map { it.runId },
                )
                assertEquals(12, fixture.calls.size)
            }
        }

    @Test
    fun `approval payload hash binds parent turn and parent conversation identities`() =
        runBlocking {
            withFixture(this) { fixture ->
                val first = fixture.service.prepare("conversation", request(), "aps.main", "openai", "test-model", permissions)
                fixture.service.approveAndRun(first.approval).join()
                val second = fixture.service.prepare("conversation", request(), "aps.main", "openai", "test-model", permissions)
                fixture.service.approveAndRun(second.approval).join()
                val question = "Explain the same immutable evidence"
                val fromFirst =
                    fixture.service.prepare(
                        "conversation",
                        request(),
                        "aps.main",
                        "openai",
                        "test-model",
                        permissions,
                        question = question,
                        parentTurnRunId = first.approval.runId,
                    )
                fixture.service.cancel()
                val fromSecond =
                    fixture.service.prepare(
                        "conversation",
                        request(),
                        "aps.main",
                        "openai",
                        "test-model",
                        permissions,
                        question = question,
                        parentTurnRunId = second.approval.runId,
                    )
                assertTrue(fromFirst.approval.payloadHash != fromSecond.approval.payloadHash)
                fixture.service.cancel()
                val child =
                    fixture.service.prepare(
                        "child",
                        request(),
                        "aps.main",
                        "openai",
                        "test-model",
                        permissions,
                        question = question,
                        parentTurnRunId = second.approval.runId,
                        parentConversationId = "conversation",
                    )
                assertTrue(fromSecond.approval.payloadHash != child.approval.payloadHash)
                fixture.service.cancel()
                assertEquals(8, fixture.calls.size)
            }
        }

    @Test
    fun `per run terminal survives sequential prepare state conflation and late await`() =
        runBlocking {
            withFixture(this) { fixture ->
                val first = fixture.service.prepare("first", request(), "aps.main", "openai", "test-model", permissions)
                val second = fixture.service.prepare("second", request(), "aps.main", "openai", "test-model", permissions)
                assertEquals("second", fixture.service.state.value.conversationId)
                assertEquals(RunStatus.AWAITING_APPROVAL, fixture.service.state.value.status)
                val oldTerminal = withTimeout(1000) { fixture.service.awaitRun(first.approval.runId) }
                assertEquals("first", oldTerminal.conversationId)
                assertEquals(RunStatus.CANCELLED, oldTerminal.status)
                fixture.service.approveAndRun(second.approval).join()
                val result = withTimeout(1000) { fixture.service.awaitRun(second.approval.runId) }
                assertEquals(RunStatus.SUCCEEDED, result.status)
                assertNotNull(result.result)
                assertEquals(4, fixture.calls.size)
            }
        }

    @Test
    fun `cancel old owner cannot cancel newly prepared run or its waiter`() =
        runBlocking {
            withFixture(this) { fixture ->
                val first = fixture.service.prepare("first", request(), "aps.main", "openai", "test-model", permissions)
                val oldWaiter =
                    async(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            fixture.service.awaitRun(first.approval.runId)
                            awaitCancellation()
                        } finally {
                            withContext(NonCancellable) { fixture.service.cancel(first.approval.runId) }
                        }
                    }
                val second = fixture.service.prepare("second", request(), "aps.main", "openai", "test-model", permissions)
                oldWaiter.cancel()
                assertFailsWith<CancellationException> { oldWaiter.await() }
                fixture.service.cancel(first.approval.runId)
                assertEquals(RunStatus.AWAITING_APPROVAL, fixture.service.state.value.status)
                assertEquals(
                    second.approval.runId,
                    fixture.service.state.value.prepared!!
                        .approval.runId,
                )
                fixture.service.approveAndRun(second.approval).join()
                assertEquals(RunStatus.SUCCEEDED, fixture.service.awaitRun(second.approval.runId).status)
                assertEquals(4, fixture.calls.size)
            }
        }

    @Test
    fun `explicit artifact grant publishes bounded inert output and deletion clears projections`() =
        runBlocking {
            withFixture(this) { fixture ->
                val prepared =
                    fixture.service.prepare(
                        "conversation",
                        request(),
                        "aps.main",
                        "openai",
                        "test-model",
                        permissions + Permission.WRITE_ARTIFACT,
                    )
                fixture.service.approveAndRun(prepared.approval).join()
                val state = fixture.service.state.value
                assertEquals(RunStatus.SUCCEEDED, state.status)
                val hash = assertNotNull(state.publishedArtifactHash)
                assertEquals(ArtifactFormat.HTML, prepared.reportFormat)
                assertEquals(ArtifactFormat.HTML, fixture.publisher.metadata(hash).format)
                val html = fixture.publisher.read(hash).decodeToString()
                assertTrue(html.contains("Content-Security-Policy"))
                assertFalse(html.contains("sk-testsecret"))
                assertEquals(hash, fixture.service.restore("conversation").publishedArtifactHash)
                assertEquals(
                    listOf(hash),
                    fixture.service
                        .transcript("conversation")
                        .single()
                        .artifactHashes,
                )
                assertEquals(prepared.approval.runId, fixture.service.state.value.lastTurnRunId)
                fixture.store
                    .list()
                    .filter { it.parentConversationId == "conversation" }
                    .forEach { fixture.service.delete(it.id) }
                assertTrue(fixture.service.delete("conversation"))
                assertNull(fixture.store.result("conversation", prepared.approval.runId))
                assertTrue(fixture.store.audit("conversation").isEmpty())
            }
        }

    @Test
    fun `report format is frozen in approval and Markdown artifacts survive history restore`() =
        runBlocking {
            withFixture(this) { fixture ->
                val html =
                    fixture.service.prepare(
                        "conversation",
                        request(),
                        "aps.report",
                        "openai",
                        "test-model",
                        permissions + Permission.WRITE_ARTIFACT,
                    )
                val markdown =
                    fixture.service.prepare(
                        "conversation",
                        request(),
                        "aps.report",
                        "openai",
                        "test-model",
                        permissions + Permission.WRITE_ARTIFACT,
                        reportFormat = ArtifactFormat.MARKDOWN,
                    )
                assertEquals(ArtifactFormat.HTML, html.reportFormat)
                assertEquals(ArtifactFormat.MARKDOWN, markdown.reportFormat)
                assertTrue(html.approval.payloadHash != markdown.approval.payloadHash)
                assertEquals(
                    "E_APPROVAL_MISMATCH",
                    assertFailsWith<AgentPolicyException> {
                        fixture.service.approveAndRun(markdown.approval.copy(payloadHash = html.approval.payloadHash))
                    }.code,
                )
                assertTrue(fixture.calls.isEmpty())

                fixture.service.approveAndRun(markdown.approval).join()

                assertEquals(RunStatus.SUCCEEDED, fixture.service.state.value.status)
                val hash = assertNotNull(fixture.service.state.value.publishedArtifactHash)
                assertEquals(ArtifactFormat.MARKDOWN, fixture.publisher.metadata(hash).format)
                val content = fixture.publisher.read(hash).decodeToString()
                assertTrue(content.startsWith("# Performance analysis"))
                assertFalse(content.contains("<!doctype html>"))
                assertFalse(content.contains("sk-testsecret"))
                assertEquals(hash, fixture.service.restore("conversation").publishedArtifactHash)
                assertEquals(
                    listOf(hash),
                    fixture.service
                        .transcript("conversation")
                        .last()
                        .artifactHashes,
                )
            }
        }

    private suspend fun withFixture(
        scope: CoroutineScope,
        beforeResult: suspend () -> Unit = {},
        connectionRevision: (String) -> String = { "" },
        block: suspend (Fixture) -> Unit,
    ) {
        val root = Files.createTempDirectory("aps-ai-application-").toRealPath()
        val directories = PlatformDirectories(root.resolve("profile"))
        directories.initialize()
        val registry = ExtensionRegistry(directories)
        registry.installBuiltins()
        val store = SqliteConversationRepository(directories.conversations.resolve("history.sqlite"))
        try {
            val calls = mutableListOf<AnalysisRequest>()
            val gateway =
                AiAnalysisGateway { analysis ->
                    calls += analysis
                    beforeResult()
                    AnalysisResult(
                        analysis.sessionId,
                        "test-model",
                        "Observed hotspot sk-testsecret0123456789",
                        listOf(
                            AnalysisFinding(
                                AnalysisFindingId("finding-1"),
                                AnalysisSeverity.WARNING,
                                "Hotspot",
                                "Evidence-bound explanation",
                                "Inspect selected call",
                                0.7f,
                                listOf("evidence-1"),
                                listOf("candidate-1"),
                            ),
                        ),
                    )
                }
            val broker = ProviderBroker(ProviderRegistry(listOf(GatewayAnalysisProvider("openai", "test-model", gateway))))
            val publisher = ArtifactPublisher(directories.artifacts)
            val runtime =
                MainAgentOrchestrator(registry, broker, store, artifactPublisher = publisher, connectionRevision = connectionRevision)
            block(
                Fixture(
                    ApplicationAiService(runtime, store, scope),
                    store,
                    calls,
                    publisher,
                    runtime,
                    registry,
                    broker,
                    directories.conversations.resolve("history.sqlite"),
                ),
            )
        } finally {
            store.close()
            root.toFile().deleteRecursively()
        }
    }

    private data class Fixture(
        val service: ApplicationAiService,
        val store: SqliteConversationRepository,
        val calls: MutableList<AnalysisRequest>,
        val publisher: ArtifactPublisher,
        val runtime: MainAgentOrchestrator,
        val registry: ExtensionRegistry,
        val broker: ProviderBroker,
        val databasePath: Path,
    )

    private fun request() =
        AnalysisRequest(
            AnalysisSessionId("analysis-1"),
            ProfilerKind.SIMPLEPERF,
            AnalysisScope(AnalysisScopeKind.CURRENT_SELECTION, "selected node"),
            listOf(PerformanceEvidence("evidence-1", "simpleperf-hotspots", "Selected node", "{\"samples\":10}")),
            listOf(AiSourceCandidate("candidate-1", "Main.kt", null, "PROBABLE", emptyList(), null, 1, 2, "a".repeat(64))),
            "v1",
            "v1",
        )

    private val permissions = setOf(Permission.READ_EVIDENCE, Permission.READ_SOURCE_CANDIDATES, Permission.PROVIDER_NETWORK)
}

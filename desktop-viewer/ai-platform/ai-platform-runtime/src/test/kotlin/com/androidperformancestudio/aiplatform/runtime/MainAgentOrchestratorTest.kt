package com.androidperformancestudio.aiplatform.runtime

import com.androidperformancestudio.ai.AiAnalysisGateway
import com.androidperformancestudio.ai.AiConversationContextTurn
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
import com.androidperformancestudio.ai.payloadText
import com.androidperformancestudio.aiplatform.model.ConversationRecord
import com.androidperformancestudio.aiplatform.model.ExtensionKind
import com.androidperformancestudio.aiplatform.model.ExtensionManifest
import com.androidperformancestudio.aiplatform.model.Permission
import com.androidperformancestudio.aiplatform.model.PlatformJson
import com.androidperformancestudio.aiplatform.model.ProviderEvent
import com.androidperformancestudio.aiplatform.model.ResourceBudget
import com.androidperformancestudio.aiplatform.model.RunStatus
import com.androidperformancestudio.aiplatform.providers.GatewayAnalysisProvider
import com.androidperformancestudio.aiplatform.providers.ProviderBroker
import com.androidperformancestudio.aiplatform.providers.ProviderRegistry
import com.androidperformancestudio.aiplatform.registry.ExtensionRegistry
import com.androidperformancestudio.aiplatform.registry.PlatformDirectories
import com.androidperformancestudio.aiplatform.storage.ConversationHistoryRepository
import com.androidperformancestudio.aiplatform.storage.ConversationRepository
import com.androidperformancestudio.aiplatform.storage.ConversationStorageException
import com.androidperformancestudio.aiplatform.storage.SqliteConversationRepository
import com.androidperformancestudio.aiplatform.storage.StoredAnalysisResult
import com.androidperformancestudio.aiplatform.storage.StoredChatTurn
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.security.MessageDigest
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MainAgentOrchestratorTest {
    private val networkPermissions = setOf(Permission.READ_EVIDENCE, Permission.PROVIDER_NETWORK)

    @Test
    fun `connection change invalidates approval before any provider call and manual retry needs new approval`() =
        runBlocking {
            var revision = "private-connection-revision-A"
            var calls = 0
            fixture(
                gateway = {
                    calls++
                    result(it)
                },
                connectionRevision = { providerId ->
                    assertEquals("fake", providerId)
                    revision
                },
            ).use { fixture ->
                val prepared = fixture.prepare()
                revision = "private-connection-revision-B"
                assertEquals(
                    "E_PROVIDER_CONNECTION_CHANGED",
                    assertFailsWith<AgentPolicyException> { fixture.runtime.approve(prepared.approval) }.code,
                )
                val failed =
                    fixture.store
                        .load("conversation-1")!!
                        .turns
                        .single()
                assertEquals(RunStatus.PERMANENT_FAILURE, failed.status)
                assertEquals("E_PROVIDER_CONNECTION_CHANGED", failed.errorCode)
                assertEquals(0, calls)
                assertFalse(prepared.toString().contains("private-connection-revision"))
                assertFalse(
                    PlatformJson.codec.encodeToString(fixture.store.load("conversation-1")!!).contains("private-connection-revision"),
                )
                revision = "private-connection-revision-A"
                assertEquals(
                    "E_RUN_UNAVAILABLE",
                    assertFailsWith<AgentPolicyException> { fixture.runtime.approve(prepared.approval) }.code,
                )
                val retry = fixture.prepare()
                assertTrue(retry.approval.id != prepared.approval.id)
                assertEquals(
                    "E_APPROVAL_REQUIRED",
                    assertFailsWith<AgentPolicyException> { fixture.runtime.run(retry.approval.runId).toList() }.code,
                )
                assertEquals(0, calls)
                fixture.runtime.approve(retry.approval)
                fixture.runtime.run(retry.approval.runId).toList()
                assertEquals(1, calls)
            }
        }

    @Test
    fun `connection change after approval blocks first provider call`() =
        runBlocking {
            var revision = "A"
            var calls = 0
            fixture(
                gateway = {
                    calls++
                    result(it)
                },
                connectionRevision = { revision },
            ).use { fixture ->
                val prepared = fixture.prepare()
                fixture.runtime.approve(prepared.approval)
                revision = "B"
                assertEquals(
                    "E_PROVIDER_CONNECTION_CHANGED",
                    assertFailsWith<AgentPolicyException> { fixture.runtime.run(prepared.approval.runId).toList() }.code,
                )
                assertEquals(0, calls)
                assertEquals(
                    RunStatus.PERMANENT_FAILURE,
                    fixture.store
                        .load("conversation-1")!!
                        .turns
                        .single()
                        .status,
                )
            }
        }

    @Test
    fun `connection revision read failure is sanitized and invalidates pending approval`() =
        runBlocking {
            var failed = false
            var calls = 0
            fixture(
                gateway = {
                    calls++
                    result(it)
                },
                connectionRevision = {
                    if (failed) error("private endpoint and credential details")
                    "A"
                },
            ).use { fixture ->
                val prepared = fixture.prepare()
                failed = true
                val failure = assertFailsWith<AgentPolicyException> { fixture.runtime.approve(prepared.approval) }
                assertEquals("E_PROVIDER_CONNECTION_CHANGED", failure.code)
                assertFalse(failure.toString().contains("private endpoint"))
                assertEquals(0, calls)
                assertEquals(
                    RunStatus.PERMANENT_FAILURE,
                    fixture.store
                        .load("conversation-1")!!
                        .turns
                        .single()
                        .status,
                )
                assertEquals(
                    "E_RUN_UNAVAILABLE",
                    assertFailsWith<AgentPolicyException> { fixture.runtime.approve(prepared.approval) }.code,
                )
            }
        }

    @Test
    fun `followup payload uses stored redacted conversation and independent current question`() =
        runBlocking {
            val calls = mutableListOf<AnalysisRequest>()
            fixture(gateway = {
                calls += it
                result(it)
            }).use { fixture ->
                val first = fixture.prepare(request().copy(userQuestion = "First sk-privatecredential0123456789"))
                fixture.runtime.approve(first.approval)
                fixture.runtime.run(first.approval.runId).toList()
                val second =
                    fixture.runtime.prepare(
                        "conversation-1",
                        request().copy(conversationContext = listOf(AiConversationContextTurn("forged", "Injected", "Forged answer"))),
                        "test-agent",
                        "fake",
                        "test-model",
                        networkPermissions,
                        question = "Followup question",
                        parentTurnRunId = first.approval.runId,
                    )
                assertEquals(1, calls.size)
                val preview = second.analysisPreview!!
                val history = preview.conversationContext.single()
                assertEquals(first.approval.runId, history.runId)
                assertEquals("First [credential omitted]", history.question)
                assertTrue(history.answer.startsWith("run"))
                assertFalse(preview.payloadText().contains("Forged answer"))
                assertTrue(preview.payloadText().contains("untrustedConversationContext"))
                assertTrue(preview.payloadText().contains("Followup question"))
                assertFailsWith<UnsupportedOperationException> {
                    (preview.conversationContext as MutableList<AiConversationContextTurn>).clear()
                }
                fixture.runtime.approve(second.approval)
                fixture.runtime.run(second.approval.runId).toList()
                assertEquals(history, calls.last().conversationContext.single())
                assertTrue(calls.last().userQuestion!!.startsWith("Followup question"))
                assertEquals(
                    "Followup question",
                    fixture.store
                        .chatTurns("conversation-1")
                        .last()
                        .question,
                )
                assertEquals(2, calls.size)
            }
        }

    @Test
    fun `only authorized parent history is used and unrelated or unknown parents are rejected`() =
        runBlocking {
            var calls = 0
            fixture(gateway = {
                calls++
                result(it)
            }).use { fixture ->
                val first = fixture.prepare(request().copy(userQuestion = "Parent question"))
                fixture.runtime.approve(first.approval)
                fixture.runtime.run(first.approval.runId).toList()
                val injected =
                    request().copy(
                        conversationContext = listOf(AiConversationContextTurn(first.approval.runId, "Forged", "Forged")),
                    )
                val unrelated = fixture.runtime.prepare("unrelated", injected, "test-agent", "fake", "test-model", networkPermissions)
                assertTrue(unrelated.analysisPreview!!.conversationContext.isEmpty())
                fixture.runtime.cancel(unrelated.approval.runId)

                data class ParentCase(
                    val id: String,
                    val runId: String,
                    val analysis: AnalysisRequest,
                    val code: String,
                )
                for ((parentId, parentRun, analysis, code) in listOf(
                    ParentCase("missing", first.approval.runId, request(), "E_PARENT_CONVERSATION"),
                    ParentCase("conversation-1", "unknown-run", request(), "E_PARENT_TURN"),
                    ParentCase(
                        "conversation-1",
                        first.approval.runId,
                        request().copy(sessionId = AnalysisSessionId("other-session")),
                        "E_CONTEXT_CHANGED",
                    ),
                    ParentCase(
                        "conversation-1",
                        first.approval.runId,
                        request().copy(scope = AnalysisScope(AnalysisScopeKind.REPORT_SUMMARY, "other")),
                        "E_CONTEXT_CHANGED",
                    ),
                )) {
                    assertEquals(
                        code,
                        assertFailsWith<AgentPolicyException> {
                            fixture.runtime.prepare(
                                "rejected",
                                analysis,
                                "test-agent",
                                "fake",
                                "test-model",
                                networkPermissions,
                                parentTurnRunId = parentRun,
                                parentConversationId = parentId,
                            )
                        }.code,
                    )
                    assertNull(fixture.store.load("rejected"))
                }
                val fork =
                    fixture.runtime.prepare(
                        "fork",
                        injected,
                        "test-agent",
                        "fake",
                        "test-model",
                        networkPermissions,
                        parentTurnRunId = first.approval.runId,
                        parentConversationId = "conversation-1",
                    )
                assertEquals(
                    "Parent question",
                    fork.analysisPreview!!
                        .conversationContext
                        .single()
                        .question,
                )
                assertEquals(1, calls)
                fixture.runtime.cancel(fork.approval.runId)
            }
        }

    @Test
    fun `conversation context keeps at most four successful saved turns bounded to chosen parent`() =
        runBlocking {
            fixture(gateway = { result(it).copy(summary = "a".repeat(3000)) }).use { fixture ->
                val successful = mutableListOf<PreparedAgentRun>()
                repeat(6) { index ->
                    val prepared = fixture.prepare(request().copy(userQuestion = "q$index " + "q".repeat(2500)))
                    fixture.runtime.approve(prepared.approval)
                    fixture.runtime.run(prepared.approval.runId).toList()
                    successful += prepared
                }
                val cancelled = fixture.prepare(request().copy(userQuestion = "Cancelled question"))
                fixture.runtime.cancel(cancelled.approval.runId)
                val next = fixture.prepare()
                val context = next.analysisPreview!!.conversationContext
                assertEquals(successful.takeLast(4).map { it.approval.runId }, context.map { it.runId })
                assertTrue(context.all { it.question!!.length <= 2048 && it.answer.length == 2048 })
                fixture.runtime.cancel(next.approval.runId)
                val fork =
                    fixture.runtime.prepare(
                        "fork",
                        request(),
                        "test-agent",
                        "fake",
                        "test-model",
                        networkPermissions,
                        parentTurnRunId = successful[1].approval.runId,
                        parentConversationId = "conversation-1",
                    )
                assertEquals(successful.take(2).map { it.approval.runId }, fork.analysisPreview!!.conversationContext.map { it.runId })
                fixture.runtime.cancel(fork.approval.runId)
            }
        }

    @Test
    fun `approval binds history snapshot and execution never rereads changed history`() =
        runBlocking {
            var changed = false
            var reads = 0
            val calls = mutableListOf<AnalysisRequest>()
            fixture(
                gateway = {
                    calls += it
                    result(it)
                },
                decorateStore = { repository ->
                    val history = repository as ConversationHistoryRepository
                    object : ConversationHistoryRepository by history {
                        override fun result(
                            conversationId: String,
                            runId: String,
                        ): StoredAnalysisResult? {
                            reads++
                            return history
                                .result(
                                    conversationId,
                                    runId,
                                )?.let { if (changed) it.copy(summary = "Changed after prepare") else it }
                        }
                    }
                },
            ).use { fixture ->
                val first = fixture.prepare(request().copy(userQuestion = "First question"))
                fixture.runtime.approve(first.approval)
                fixture.runtime.run(first.approval.runId).toList()
                val second = fixture.prepare(request().copy(userQuestion = "Second question"))
                val expected = second.analysisPreview!!.conversationContext
                val previousReads = reads
                fixture.runtime.approve(second.approval)
                changed = true
                fixture.runtime.run(second.approval.runId).toList()
                assertEquals(expected, calls.last().conversationContext)
                assertEquals(previousReads, reads)
                val third = fixture.prepare(request().copy(userQuestion = "Second question"))
                assertTrue(third.approval.payloadHash != second.approval.payloadHash)
                assertTrue(
                    third.analysisPreview!!
                        .conversationContext
                        .first()
                        .answer
                        .startsWith("Changed after prepare"),
                )
                fixture.runtime.cancel(third.approval.runId)
            }
        }

    @Test
    fun `fork retry retains exact parent anchor after cancelled first turn and bounded nested forks`() =
        runBlocking {
            fixture(gateway = { result(it) }).use { fixture ->
                val first = fixture.prepare(request().copy(userQuestion = "Original first"))
                fixture.runtime.approve(first.approval)
                fixture.runtime.run(first.approval.runId).toList()
                val later = fixture.prepare(request().copy(userQuestion = "Original later must not leak"))
                fixture.runtime.approve(later.approval)
                fixture.runtime.run(later.approval.runId).toList()
                val fork =
                    fixture.runtime.prepare(
                        "fork",
                        request(),
                        "test-agent",
                        "fake",
                        "test-model",
                        networkPermissions,
                        question = "Cancelled branch",
                        parentTurnRunId = first.approval.runId,
                        parentConversationId = "conversation-1",
                    )
                fixture.runtime.cancel(fork.approval.runId)
                val retry =
                    fixture.runtime.prepare(
                        "fork",
                        request(),
                        "test-agent",
                        "fake",
                        "test-model",
                        networkPermissions,
                        question = "Retry branch",
                        parentTurnRunId = fork.approval.runId,
                    )
                assertEquals(listOf(first.approval.runId), retry.analysisPreview!!.conversationContext.map { it.runId })
                fixture.runtime.approve(retry.approval)
                fixture.runtime.run(retry.approval.runId).toList()
                val nested =
                    fixture.runtime.prepare(
                        "nested",
                        request(),
                        "test-agent",
                        "fake",
                        "test-model",
                        networkPermissions,
                        question = "Nested branch",
                        parentTurnRunId = retry.approval.runId,
                        parentConversationId = "fork",
                    )
                assertEquals(
                    listOf(first.approval.runId, retry.approval.runId),
                    nested.analysisPreview!!.conversationContext.map { it.runId },
                )
                assertFalse(nested.analysisPreview.payloadText().contains("Original later must not leak"))
                fixture.runtime.cancel(nested.approval.runId)
            }
        }

    @Test
    fun `corrupt inherited fork ancestry cannot cycle or cross context identity`() =
        runBlocking {
            var corruption: String? = null
            var forkRunId: String? = null
            fixture(
                gateway = { result(it) },
                decorateStore = { repository ->
                    val history = repository as ConversationHistoryRepository
                    object : ConversationHistoryRepository by history {
                        override fun load(id: String): ConversationRecord? =
                            history.load(id)?.let { record ->
                                when {
                                    id == "conversation-1" && corruption == "cycle" -> record.copy(parentConversationId = "fork")
                                    id == "conversation-1" && corruption == "context" -> record.copy(contextHash = "b".repeat(64))
                                    else -> record
                                }
                            }

                        override fun chatTurns(conversationId: String): List<StoredChatTurn> =
                            history.chatTurns(conversationId).map { turn ->
                                if (conversationId == "conversation-1" && corruption == "cycle") {
                                    turn.copy(parentTurnRunId = forkRunId)
                                } else {
                                    turn
                                }
                            }
                    }
                },
            ).use { fixture ->
                val first = fixture.prepare()
                fixture.runtime.approve(first.approval)
                fixture.runtime.run(first.approval.runId).toList()
                val fork =
                    fixture.runtime.prepare(
                        "fork",
                        request(),
                        "test-agent",
                        "fake",
                        "test-model",
                        networkPermissions,
                        parentTurnRunId = first.approval.runId,
                        parentConversationId = "conversation-1",
                    )
                forkRunId = fork.approval.runId
                fixture.runtime.approve(fork.approval)
                fixture.runtime.run(fork.approval.runId).toList()
                for ((kind, code) in listOf("cycle" to "E_PARENT_CONVERSATION", "context" to "E_CONTEXT_CHANGED")) {
                    corruption = kind
                    assertEquals(
                        code,
                        assertFailsWith<AgentPolicyException> {
                            fixture.runtime.prepare(
                                "rejected",
                                request(),
                                "test-agent",
                                "fake",
                                "test-model",
                                networkPermissions,
                                parentTurnRunId = fork.approval.runId,
                                parentConversationId = "fork",
                            )
                        }.code,
                    )
                    assertNull(fixture.store.load("rejected"))
                }
            }
        }

    @Test
    fun `history text participates in input budget before saving another turn`() =
        runBlocking {
            fixture(
                gateway = { result(it).copy(summary = "a".repeat(3000)) },
                budget = ResourceBudget(maxInputBytes = 1500),
            ).use { fixture ->
                val first = fixture.prepare()
                fixture.runtime.approve(first.approval)
                fixture.runtime.run(first.approval.runId).toList()
                assertEquals("E_INPUT_LIMIT", assertFailsWith<AgentPolicyException> { fixture.prepare() }.code)
                assertEquals(
                    1,
                    fixture.store
                        .load("conversation-1")!!
                        .turns.size,
                )
            }
        }

    @Test
    fun `failed chat persistence never registers an executable pending run`() =
        runBlocking {
            var capturedRunId: String? = null
            var calls = 0
            fixture(
                gateway = {
                    calls++
                    result(it)
                },
                decorateStore = { repository ->
                    val history = repository as ConversationHistoryRepository
                    object : ConversationHistoryRepository by history {
                        override fun savePrepared(
                            record: ConversationRecord,
                            turn: StoredChatTurn,
                        ) {
                            capturedRunId = turn.runId
                            history.savePrepared(record, turn)
                        }
                    }
                },
            ).use { fixture ->
                val databasePath = fixture.root.resolve("profile/conversations/platform.sqlite")
                DriverManager.getConnection("jdbc:sqlite:$databasePath").use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute(
                            "CREATE TRIGGER fail_chat BEFORE INSERT ON conversation_chat_turn BEGIN SELECT RAISE(ABORT, 'rejected'); END",
                        )
                    }
                }
                assertFailsWith<ConversationStorageException> { fixture.prepare(request().copy(userQuestion = "Question")) }
                assertTrue(fixture.store.list().isEmpty())
                assertTrue(fixture.store.chatTurns("conversation-1").isEmpty())
                assertEquals(
                    "E_RUN_UNAVAILABLE",
                    assertFailsWith<AgentPolicyException> { fixture.runtime.run(capturedRunId!!).toList() }.code,
                )
                assertEquals(0, calls)
            }
        }

    @Test
    fun `explicit approval executes once and persists only canonical references`() =
        runBlocking {
            var calls = 0
            fixture(gateway = { request ->
                calls++
                result(request)
            }).use { fixture ->
                val prepared = fixture.prepare()
                fixture.runtime.approve(prepared.approval)
                val events = fixture.runtime.run(prepared.approval.runId).toList()
                assertEquals(1, calls)
                assertEquals(
                    listOf("run"),
                    events
                        .filter {
                            it.event is ProviderEvent.StructuredResult
                        }.map { (it.event as ProviderEvent.StructuredResult).result.summary },
                )
                assertTrue(events.all { it.runId == prepared.approval.runId && it.generation == 0L })
                val stored = fixture.store.load("conversation-1")!!
                assertEquals(RunStatus.SUCCEEDED, stored.turns.single().status)
                assertEquals(listOf("evidence-1"), stored.turns.single().evidenceIds)
                assertEquals(listOf("finding-1"), stored.turns.single().findingIds)
                assertFalse(PlatformJson.codec.encodeToString(stored).contains("expensive-method"))
                assertFailsWith<AgentPolicyException> { fixture.runtime.run(prepared.approval.runId).toList() }
                Unit
            }
        }

    @Test
    fun `missing or modified approval makes zero provider calls`() =
        runBlocking {
            var calls = 0
            fixture(gateway = {
                calls++
                result(it)
            }).use { fixture ->
                val prepared = fixture.prepare()
                assertEquals(
                    "E_APPROVAL_REQUIRED",
                    assertFailsWith<AgentPolicyException> { fixture.runtime.run(prepared.approval.runId).toList() }.code,
                )
                assertEquals(
                    "E_APPROVAL_MISMATCH",
                    assertFailsWith<AgentPolicyException> {
                        fixture.runtime.approve(prepared.approval.copy(generation = 99))
                    }.code,
                )
                assertEquals(0, calls)
            }
        }

    @Test
    fun `preview owns frozen evidence even when caller mutates original list`() =
        runBlocking {
            val evidence = mutableListOf(request().evidence.single())
            var received: AnalysisRequest? = null
            fixture(gateway = {
                received = it
                result(it)
            }).use { fixture ->
                val prepared = fixture.prepare(request().copy(evidence = evidence))
                evidence.clear()
                fixture.runtime.approve(prepared.approval)
                fixture.runtime.run(prepared.approval.runId).toList()
                assertEquals("evidence-1", received!!.evidence.single().id)
            }
        }

    @Test
    fun `new generation cancels old provider and old completion cannot overwrite it`() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            var cancelled = false
            var calls = 0
            fixture(gateway = {
                calls++
                if (calls == 1) {
                    started.complete(Unit)
                    try {
                        awaitCancellation()
                    } finally {
                        cancelled = true
                    }
                } else {
                    result(it)
                }
            }).use { fixture ->
                val first = fixture.prepare()
                fixture.runtime.approve(first.approval)
                val old = async { fixture.runtime.run(first.approval.runId).toList() }
                started.await()
                val next = fixture.prepare()
                fixture.runtime.approve(next.approval)
                val events = fixture.runtime.run(next.approval.runId).toList()
                assertFailsWith<CancellationException> { old.await() }
                assertTrue(cancelled)
                assertTrue(events.all { it.generation == 1L })
                assertEquals(
                    listOf(RunStatus.CANCELLED, RunStatus.SUCCEEDED),
                    fixture.store
                        .load("conversation-1")!!
                        .turns
                        .map { it.status },
                )
            }
        }

    @Test
    fun `outer timeout propagates cancellation while own budget reports retryable timeout`() =
        runBlocking {
            fixture(gateway = { awaitCancellation() }).use { fixture ->
                val prepared = fixture.prepare()
                fixture.runtime.approve(prepared.approval)
                assertFailsWith<CancellationException> {
                    withTimeout(25) { fixture.runtime.run(prepared.approval.runId).toList() }
                }
                assertEquals(
                    RunStatus.CANCELLED,
                    fixture.store
                        .load("conversation-1")!!
                        .turns
                        .single()
                        .status,
                )
            }
            fixture(gateway = { awaitCancellation() }, budget = ResourceBudget(timeoutMillis = 25)).use { fixture ->
                val prepared = fixture.prepare()
                fixture.runtime.approve(prepared.approval)
                val event =
                    assertIs<ProviderEvent.Failure>(
                        fixture.runtime
                            .run(prepared.approval.runId)
                            .toList()
                            .last()
                            .event,
                    )
                assertTrue(event.retryable)
                assertEquals("E_PROVIDER_TIMEOUT", event.code)
                assertEquals(
                    RunStatus.RETRYABLE_FAILURE,
                    fixture.store
                        .load("conversation-1")!!
                        .turns
                        .single()
                        .status,
                )
            }
        }

    @Test
    fun `suspended provider wait does not consume cpu budget`() =
        runBlocking {
            fixture(
                gateway = {
                    delay(500)
                    result(it)
                },
                budget = ResourceBudget(timeoutMillis = 5000, maxCpuMillis = 250),
            ).use { fixture ->
                val prepared = fixture.prepare()
                fixture.runtime.approve(prepared.approval)
                val events =
                    fixture.runtime
                        .run(prepared.approval.runId)
                        .toList()
                        .map { it.event }
                val accepted = events.filterIsInstance<ProviderEvent.StructuredResult>().single().result
                assertEquals(result(request()), accepted)
                assertEquals(ProviderEvent.Completed, events.last())
                val turn =
                    fixture.store
                        .load("conversation-1")!!
                        .turns
                        .single()
                assertEquals(RunStatus.SUCCEEDED, turn.status)
                assertNull(turn.errorCode)
                assertEquals(accepted, fixture.store.result("conversation-1", prepared.approval.runId)?.toAnalysisResult())
            }
        }

    @Test
    fun `blocking provider wait does not consume cpu budget`() =
        runBlocking {
            fixture(
                gateway = {
                    Thread.sleep(500)
                    result(it)
                },
                budget = ResourceBudget(timeoutMillis = 5000, maxCpuMillis = 250),
            ).use { fixture ->
                val prepared = fixture.prepare()
                fixture.runtime.approve(prepared.approval)
                val events =
                    fixture.runtime
                        .run(prepared.approval.runId)
                        .toList()
                        .map { it.event }
                val accepted = events.filterIsInstance<ProviderEvent.StructuredResult>().single().result
                assertEquals(result(request()), accepted)
                assertEquals(ProviderEvent.Completed, events.last())
                val turn =
                    fixture.store
                        .load("conversation-1")!!
                        .turns
                        .single()
                assertEquals(RunStatus.SUCCEEDED, turn.status)
                assertNull(turn.errorCode)
                assertEquals(accepted, fixture.store.result("conversation-1", prepared.approval.runId)?.toAnalysisResult())
            }
        }

    @Test
    fun `cpu bound provider fails permanently without accepting result`() =
        runBlocking {
            val threadCpu = ManagementFactory.getThreadMXBean()
            assumeTrue(threadCpu.isCurrentThreadCpuTimeSupported, "Current-thread CPU metering is unsupported")
            if (!threadCpu.isThreadCpuTimeEnabled) threadCpu.isThreadCpuTimeEnabled = true
            var consumedCpuNanos = 0L
            fixture(
                gateway = {
                    val startedCpu = threadCpu.currentThreadCpuTime
                    assertTrue(startedCpu >= 0, "Current-thread CPU time must be available when metering is supported")
                    val startedWall = System.nanoTime()
                    while (consumedCpuNanos <= 350_000_000L) {
                        assertTrue(System.nanoTime() - startedWall < 4_000_000_000L, "CPU work exceeded its wall-clock safeguard")
                        consumedCpuNanos = threadCpu.currentThreadCpuTime - startedCpu
                    }
                    result(it)
                },
                budget = ResourceBudget(timeoutMillis = 5000, maxCpuMillis = 250),
            ).use { fixture ->
                val prepared = fixture.prepare()
                fixture.runtime.approve(prepared.approval)
                val events =
                    fixture.runtime
                        .run(prepared.approval.runId)
                        .toList()
                        .map { it.event }
                val event = assertIs<ProviderEvent.Failure>(events.last())
                assertTrue(consumedCpuNanos > 350_000_000L)
                assertEquals("E_CPU_BUDGET", event.code)
                assertFalse(event.retryable)
                assertFalse(events.any { it is ProviderEvent.StructuredResult || it == ProviderEvent.Completed })
                val turn =
                    fixture.store
                        .load("conversation-1")!!
                        .turns
                        .single()
                assertEquals(RunStatus.PERMANENT_FAILURE, turn.status)
                assertEquals("E_CPU_BUDGET", turn.errorCode)
                assertNull(fixture.store.result("conversation-1", prepared.approval.runId))
            }
        }

    @Test
    fun `cancellation reaches provider even if disk refuses cancelled state`() =
        runBlocking {
            val started = CompletableDeferred<Unit>()
            val cancelled = CompletableDeferred<Unit>()
            fixture(gateway = {
                started.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }, failingCancellationStore = true).use { fixture ->
                val prepared = fixture.prepare()
                fixture.runtime.approve(prepared.approval)
                val running = async { fixture.runtime.run(prepared.approval.runId).toList() }
                started.await()
                assertFailsWith<IllegalStateException> { fixture.runtime.cancel(prepared.approval.runId) }
                withTimeout(1000) { cancelled.await() }
                assertFailsWith<CancellationException> { running.await() }
                assertEquals(
                    RunStatus.RUNNING,
                    fixture.store
                        .load("conversation-1")!!
                        .turns
                        .single()
                        .status,
                )
                assertEquals(1, fixture.store.recoverInterrupted())
            }
        }

    @Test
    fun `source snippet permission and live authorization are separate gates`() =
        runBlocking {
            var allowed = true
            var calls = 0
            fixture(gateway = {
                calls++
                result(it)
            }, sourceAuthorization = { allowed }).use { fixture ->
                val sourceRequest =
                    request().copy(
                        sourceCandidates =
                            listOf(
                                AiSourceCandidate("candidate-1", "Main.kt", null, "PROBABLE", emptyList(), "source-body"),
                            ),
                    )
                assertEquals("E_SOURCE_PERMISSION", assertFailsWith<AgentPolicyException> { fixture.prepare(sourceRequest) }.code)
                val permissions = networkPermissions + Permission.READ_SOURCE_CANDIDATES + Permission.SEND_SOURCE_SNIPPETS
                val prepared = fixture.runtime.prepare("conversation-1", sourceRequest, "test-agent", "fake", "test-model", permissions)
                fixture.runtime.approve(prepared.approval)
                allowed = false
                assertEquals(
                    "E_SOURCE_UNAUTHORIZED",
                    assertFailsWith<AgentPolicyException> { fixture.runtime.run(prepared.approval.runId).toList() }.code,
                )
                assertEquals(0, calls)
            }
        }

    @Test
    fun `expired approval changed context and revoked agent do not execute`() =
        runBlocking {
            var clock = 1L
            var calls = 0
            fixture(gateway = {
                calls++
                result(it)
            }, clock = { clock }).use { fixture ->
                val first = fixture.prepare()
                clock += 300_001
                assertEquals("E_APPROVAL_EXPIRED", assertFailsWith<AgentPolicyException> { fixture.runtime.approve(first.approval) }.code)
                val next = fixture.prepare()
                fixture.runtime.approve(next.approval)
                assertEquals(
                    "E_CONTEXT_CHANGED",
                    assertFailsWith<AgentPolicyException> {
                        fixture.prepare(request().copy(sessionId = AnalysisSessionId("different-session")))
                    }.code,
                )
                fixture.registry.revoke(fixture.registry.resolve(ExtensionKind.AGENT, "test-agent").key)
                assertFailsWith<IllegalStateException> { fixture.runtime.run(next.approval.runId).toList() }
                assertEquals(0, calls)
            }
        }

    @Test
    fun `failed post-provider storage load releases slot and never emits success`() =
        runBlocking {
            var storageUnavailable = false
            fixture(
                gateway = {
                    storageUnavailable = true
                    result(it)
                },
                decorateStore = { delegate ->
                    object : ConversationRepository by delegate {
                        override fun load(id: String): com.androidperformancestudio.aiplatform.model.ConversationRecord? {
                            if (storageUnavailable) error("sensitive database path")
                            return delegate.load(id)
                        }
                    }
                },
            ).use { fixture ->
                val prepared = fixture.prepare()
                fixture.runtime.approve(prepared.approval)
                val events = fixture.runtime.run(prepared.approval.runId).toList()
                assertEquals("E_AGENT_EXECUTION", assertIs<ProviderEvent.Failure>(events.single().event).code)
                assertEquals(
                    RunStatus.RUNNING,
                    fixture.store
                        .load("conversation-1")!!
                        .turns
                        .single()
                        .status,
                )
                storageUnavailable = false
                val second = fixture.runtime.prepare("conversation-2", request(), "test-agent", "fake", "test-model", networkPermissions)
                assertEquals(0L, second.approval.generation)
                fixture.runtime.cancel(second.approval.runId)
            }
        }

    @Test
    fun `cancel before collection discards payload and approval cannot be reused`() =
        runBlocking {
            fixture(gateway = { result(it) }).use { fixture ->
                val prepared = fixture.prepare()
                fixture.runtime.cancel(prepared.approval.runId)
                assertEquals("E_RUN_UNAVAILABLE", assertFailsWith<AgentPolicyException> { fixture.runtime.approve(prepared.approval) }.code)
                assertEquals(
                    RunStatus.CANCELLED,
                    fixture.store
                        .load("conversation-1")!!
                        .turns
                        .single()
                        .status,
                )
            }
        }

    private fun fixture(
        gateway: suspend (AnalysisRequest) -> AnalysisResult,
        budget: ResourceBudget = ResourceBudget(),
        sourceAuthorization: (AnalysisRequest) -> Boolean = { false },
        clock: () -> Long = System::currentTimeMillis,
        failingCancellationStore: Boolean = false,
        decorateStore: (ConversationRepository) -> ConversationRepository = { it },
        connectionRevision: (String) -> String = { "" },
    ): Fixture {
        val root = Files.createTempDirectory("aps-ai-runtime-").toRealPath()
        val directories = PlatformDirectories(root.resolve("profile"))
        directories.initialize()
        val registry = ExtensionRegistry(directories)
        val bundle = Files.createDirectory(root.resolve("bundle"))
        val prompt = "Only analyze supplied evidence."
        Files.writeString(bundle.resolve("prompt.md"), prompt)
        val manifest =
            ExtensionManifest(
                kind = ExtensionKind.AGENT,
                id = "test-agent",
                version = "1.0.0",
                name = "Test Agent",
                description = "Bounded test agent",
                entrypoint = "prompt.md",
                inputSchema = "analysis-request-v1",
                outputSchema = "analysis-result-v1",
                contextKinds = setOf("simpleperf"),
                evidenceKinds = setOf("cpu-hotspot"),
                allowedProviders = setOf("fake"),
                permissions = networkPermissions + Permission.READ_SOURCE_CANDIDATES + Permission.SEND_SOURCE_SNIPPETS,
                budget = budget,
                files =
                    mapOf(
                        "prompt.md" to
                            MessageDigest.getInstance("SHA-256").digest(prompt.toByteArray()).joinToString("") { "%02x".format(it) },
                    ),
            )
        Files.writeString(bundle.resolve("manifest.json"), PlatformJson.codec.encodeToString(manifest))
        val installed = registry.install(bundle)
        registry.trust(installed.key)
        registry.enable(installed.key)
        val store = SqliteConversationRepository(directories.root.resolve("conversations/platform.sqlite"))
        val persistence =
            if (failingCancellationStore) {
                object : ConversationRepository by store {
                    override fun save(record: com.androidperformancestudio.aiplatform.model.ConversationRecord) {
                        if (record.turns.last().status == RunStatus.CANCELLED) error("disk unavailable")
                        store.save(record)
                    }
                }
            } else {
                store
            }
        val broker = ProviderBroker(ProviderRegistry(listOf(GatewayAnalysisProvider("fake", "test-model", AiAnalysisGateway(gateway)))))
        return Fixture(
            MainAgentOrchestrator(
                registry,
                broker,
                decorateStore(persistence),
                sourceAuthorization,
                clock,
                connectionRevision = connectionRevision,
            ),
            registry,
            store,
            root,
        )
    }

    private inner class Fixture(
        val runtime: MainAgentOrchestrator,
        val registry: ExtensionRegistry,
        val store: SqliteConversationRepository,
        val root: java.nio.file.Path,
    ) : AutoCloseable {
        suspend fun prepare(analysis: AnalysisRequest = request()) =
            runtime.prepare("conversation-1", analysis, "test-agent", "fake", "test-model", networkPermissions)

        override fun close() {
            store.close()
            root.toFile().deleteRecursively()
        }
    }

    private fun request() =
        AnalysisRequest(
            AnalysisSessionId("analysis-1"),
            ProfilerKind.SIMPLEPERF,
            AnalysisScope(AnalysisScopeKind.CURRENT_SELECTION, "selected call node"),
            listOf(PerformanceEvidence("evidence-1", "cpu-hotspot", "expensive-method", "{}")),
            emptyList(),
            "prompt-v1",
            "policy-v1",
        )

    private fun result(request: AnalysisRequest) =
        AnalysisResult(
            request.sessionId,
            "test-model",
            "run",
            listOf(
                AnalysisFinding(
                    AnalysisFindingId("finding-1"),
                    AnalysisSeverity.WARNING,
                    "Hotspot",
                    "Observed",
                    "Investigate",
                    0.7f,
                    listOf("evidence-1"),
                    emptyList(),
                ),
            ),
        )
}

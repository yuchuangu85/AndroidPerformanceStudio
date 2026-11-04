package com.androidperformancestudio.aiplatform.storage

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
import com.androidperformancestudio.aiplatform.model.ConversationRecord
import com.androidperformancestudio.aiplatform.model.ConversationTurn
import com.androidperformancestudio.aiplatform.model.RunStatus
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationHistoryTest {
    @Test
    fun `completion and redacted projection are atomic and restore without source or secrets`() =
        withStore { store ->
            store.save(record())
            val projected = projection()
            val completed = record().copy(turns = listOf(turn().copy(status = RunStatus.SUCCEEDED, findingIds = listOf("finding-1"))))
            store.complete(completed, projected)
            assertEquals(projected, store.result("conversation", "run-1"))
            assertEquals(
                RunStatus.SUCCEEDED,
                store
                    .load("conversation")!!
                    .turns
                    .single()
                    .status,
            )
            assertFalse(projected.summary.contains("sk-privatecredential"))
            assertEquals(projected.toAnalysisResult().summary, projected.summary)
            assertFailsWith<ConversationStorageException> { store.saveResult(projected.copy(summary = "different")) }
        }

    @Test
    fun `invalid result rolls back succeeded turn and unknown references`() =
        withStore { store ->
            store.save(record())
            val completed = record().copy(turns = listOf(turn().copy(status = RunStatus.SUCCEEDED, findingIds = listOf("finding-1"))))
            val invalid = projection().copy(findings = projection().findings.map { it.copy(candidateIds = listOf("unknown")) })
            assertFailsWith<ConversationStorageException> { store.complete(completed, invalid) }
            assertEquals(
                RunStatus.RUNNING,
                store
                    .load("conversation")!!
                    .turns
                    .single()
                    .status,
            )
            assertNull(store.result("conversation", "run-1"))
        }

    @Test
    fun `audit is reference checked sequential immutable and deleted with history`() =
        withStore { store ->
            store.save(record())
            val event =
                ExecutionAuditEvent(
                    "conversation",
                    "run-1",
                    0,
                    0,
                    ExecutionKind.RUN,
                    ExecutionPhase.STARTED,
                    "aps.main",
                    timestampEpochMillis = 1,
                )
            store.appendAudit(event)
            store.appendAudit(event)
            assertEquals(listOf(event), store.audit("conversation"))
            assertFailsWith<ConversationStorageException> { store.appendAudit(event.copy(sequence = 2)) }
            assertFailsWith<ConversationStorageException> { store.appendAudit(event.copy(evidenceIds = listOf("unknown"))) }
            assertFailsWith<ConversationStorageException> { store.delete("conversation") }
            store.save(record().copy(turns = listOf(turn().copy(status = RunStatus.CANCELLED))))
            assertTrue(store.delete("conversation"))
            assertTrue(store.audit("conversation").isEmpty())
            assertFalse(store.delete("conversation"))
        }

    @Test
    fun `retention deletes only terminal histories and accepts explicit host policy`() =
        withStore { store ->
            store.save(record("active"))
            store.save(record("finished").copy(turns = listOf(turn().copy(status = RunStatus.CANCELLED))))
            assertEquals(1, store.prune(0, Long.MAX_VALUE))
            assertNull(store.load("finished"))
            assertEquals(
                RunStatus.RUNNING,
                store
                    .load("active")!!
                    .turns
                    .single()
                    .status,
            )
            assertFailsWith<ConversationStorageException> { store.prune(-1, 0) }
        }

    @Test
    fun `child audit stores exact run references and rejects missing or unrelated children`() =
        withStore { store ->
            store.save(record())
            store.save(record("child").copy(parentConversationId = "conversation", turns = listOf(turn().copy(runId = "child-run"))))
            store.save(record("unrelated").copy(turns = listOf(turn().copy(runId = "other-run"))))
            val event =
                ExecutionAuditEvent(
                    "conversation",
                    "run-1",
                    0,
                    0,
                    ExecutionKind.CHILD_AGENT,
                    ExecutionPhase.STARTED,
                    "imported.child",
                    timestampEpochMillis = 1,
                    depth = 1,
                    relatedConversationId = "child",
                    relatedRunId = "child-run",
                )
            store.appendAudit(event)
            assertEquals(event, store.audit("conversation").single())
            for (bad in listOf(
                event.copy(sequence = 1, relatedRunId = "missing"),
                event.copy(sequence = 1, relatedConversationId = "missing"),
                event.copy(sequence = 1, relatedConversationId = "unrelated", relatedRunId = "other-run"),
            )) {
                assertEquals("E_AUDIT_CHILD", assertFailsWith<ConversationStorageException> { store.appendAudit(bad) }.code)
            }
            assertFailsWith<IllegalArgumentException> { event.copy(relatedRunId = null) }
            assertFailsWith<IllegalArgumentException> { event.copy(kind = ExecutionKind.SCRIPT) }
        }

    @Test
    fun `nested child references follow only the actual parent chain`() =
        withStore { store ->
            store.save(record())
            store.save(record("child").copy(parentConversationId = "conversation", turns = listOf(turn().copy(runId = "child-run"))))
            store.save(record("grandchild").copy(parentConversationId = "child", turns = listOf(turn().copy(runId = "grandchild-run"))))
            val event =
                ExecutionAuditEvent(
                    "conversation",
                    "run-1",
                    0,
                    0,
                    ExecutionKind.CHILD_AGENT,
                    ExecutionPhase.STARTED,
                    "imported.grandchild",
                    timestampEpochMillis = 1,
                    depth = 2,
                    relatedConversationId = "grandchild",
                    relatedRunId = "grandchild-run",
                )
            store.appendAudit(event)
            assertEquals("grandchild-run", store.audit("conversation").single().relatedRunId)
        }

    private fun record(id: String = "conversation") =
        ConversationRecord(id = id, analysisSessionId = "session", contextHash = "a".repeat(64), turns = listOf(turn()))

    private fun turn() = ConversationTurn("run-1", 0, RunStatus.RUNNING, listOf("evidence-1"), emptyList())

    private fun projection(): StoredAnalysisResult {
        val request =
            AnalysisRequest(
                AnalysisSessionId("session"),
                ProfilerKind.SIMPLEPERF,
                AnalysisScope(AnalysisScopeKind.REPORT_SUMMARY, "report"),
                listOf(PerformanceEvidence("evidence-1", "cpu-hotspot", "fact", "{}")),
                emptyList(),
                "v1",
                "v1",
            )
        val result =
            AnalysisResult(
                request.sessionId,
                "test-model",
                "Finding sk-privatecredential0123456789",
                listOf(
                    AnalysisFinding(
                        AnalysisFindingId("finding-1"),
                        AnalysisSeverity.INFO,
                        "Bounded",
                        "Observed",
                        "Inspect",
                        0.5f,
                        listOf("evidence-1"),
                        emptyList(),
                    ),
                ),
            )
        return projectAnalysisResult("conversation", "run-1", 0, request, result, 1, "b".repeat(64))
    }

    private fun withStore(block: (SqliteConversationRepository) -> Unit) {
        val root = Files.createTempDirectory("aps-history-").toRealPath()
        try {
            SqliteConversationRepository(root.resolve("private/history.sqlite")).use(block)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

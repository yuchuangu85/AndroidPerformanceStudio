package com.androidperformancestudio.aiplatform.storage

import com.androidperformancestudio.ai.AiSourceCandidate
import com.androidperformancestudio.ai.AnalysisRequest
import com.androidperformancestudio.ai.AnalysisScope
import com.androidperformancestudio.ai.AnalysisScopeKind
import com.androidperformancestudio.ai.AnalysisSessionId
import com.androidperformancestudio.ai.PerformanceEvidence
import com.androidperformancestudio.ai.ProfilerKind
import com.androidperformancestudio.aiplatform.model.ConversationRecord
import com.androidperformancestudio.aiplatform.model.ConversationTurn
import com.androidperformancestudio.aiplatform.model.PlatformJson
import com.androidperformancestudio.aiplatform.model.RunStatus
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ChatHistoryTest {
    @Test
    fun `questions results and metadata survive reopen without changing reference records`() =
        withDatabase { path ->
            val first = record()
            val question = projectChatTurn(first.id, "run-1", 0, request("Why is this slow?"), 10)
            val result = StoredAnalysisResult(first.id, "run-1", 0, "session", "model", "Bounded result", emptyList(), 20)
            SqliteConversationRepository(path).use { store ->
                store.savePrepared(first, question)
                store.complete(first.copy(turns = listOf(first.turns.single().copy(status = RunStatus.SUCCEEDED))), result)
                val next = store.load(first.id)!!.copy(turns = store.load(first.id)!!.turns + turn("run-2", 1))
                store.savePrepared(next, projectChatTurn(first.id, "run-2", 1, request("What next?"), 30))
                store.save(next.copy(turns = next.turns.map { if (it.runId == "run-2") it.copy(status = RunStatus.CANCELLED) else it }))
                val references = store.load(first.id)
                store.archiveConversation(first.id, true)
                store.renameConversation(first.id, "Review sk-privatecredential0123456789")
                store.pinConversation(first.id, true)
                assertEquals(references, store.load(first.id))
                assertEquals(result, store.result(first.id, "run-1"))
                assertFalse(PlatformJson.codec.encodeToString(references).contains("Why is this slow?"))
            }
            SqliteConversationRepository(path).use { store ->
                assertEquals(listOf("Why is this slow?", "What next?"), store.chatTurns(first.id).map { it.question })
                assertEquals(listOf(0L, 1L), store.chatTurns(first.id).map { it.generation })
                assertEquals(listOf(10L, 30L), store.chatTurns(first.id).map { it.timestampEpochMillis })
                assertEquals("Review [credential omitted]", store.chatMetadata(first.id)!!.title)
                assertTrue(store.chatMetadata(first.id)!!.pinned)
                assertTrue(store.chatMetadata(first.id)!!.archived)
                assertEquals(result, store.result(first.id, "run-1"))
            }
        }

    @Test
    fun `legacy schema reopens with no invented question even when a question hash exists`() =
        withDatabase { path ->
            val old = record().copy(turns = listOf(turn().copy(status = RunStatus.SUCCEEDED)))
            SqliteConversationRepository(path).use { store ->
                store.save(old)
                store.saveResult(StoredAnalysisResult(old.id, "run-1", 0, "session", "model", "Legacy", emptyList(), 1, "a".repeat(64)))
            }
            DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP TABLE conversation_chat_turn")
                    statement.execute("DROP TABLE conversation_chat_meta")
                }
            }
            SqliteConversationRepository(path).use { store ->
                assertEquals(old, store.load(old.id))
                assertTrue(store.chatTurns(old.id).isEmpty())
                assertNull(store.chatMetadata(old.id)!!.title)
                assertEquals("a".repeat(64), store.result(old.id, "run-1")!!.questionHash)
            }
        }

    @Test
    fun `metadata table without archive column migrates preserving title and pin`() =
        withDatabase { path ->
            SqliteConversationRepository(path).use { store -> store.save(record()) }
            DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP TABLE conversation_chat_meta")
                    statement.execute(
                        "CREATE TABLE conversation_chat_meta(id TEXT PRIMARY KEY REFERENCES conversation(id) ON DELETE CASCADE, title TEXT, pinned INTEGER NOT NULL DEFAULT 0)",
                    )
                    statement.execute("INSERT INTO conversation_chat_meta VALUES ('conversation', 'Existing title', 1)")
                }
            }
            SqliteConversationRepository(path).use { store ->
                assertEquals("Existing title", store.chatMetadata("conversation")!!.title)
                assertTrue(store.chatMetadata("conversation")!!.pinned)
                assertFalse(store.chatMetadata("conversation")!!.archived)
                store.archiveConversation("conversation", true)
                assertTrue(store.chatMetadata("conversation")!!.archived)
                store.archiveConversation("conversation", false)
                assertFalse(store.chatMetadata("conversation")!!.archived)
            }
        }

    @Test
    fun `chat table without parent anchor column upgrades without fabricating ancestry`() =
        withDatabase { path ->
            SqliteConversationRepository(path).use { store -> store.save(record()) }
            DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP TABLE conversation_chat_turn")
                    statement.execute(
                        "CREATE TABLE conversation_chat_turn(conversation_id TEXT NOT NULL REFERENCES conversation(id) ON DELETE CASCADE, run_id TEXT NOT NULL, generation INTEGER NOT NULL, question TEXT, created_at INTEGER NOT NULL, PRIMARY KEY(conversation_id, run_id))",
                    )
                    statement.execute("INSERT INTO conversation_chat_turn VALUES ('conversation', 'run-1', 0, 'Existing question', 1)")
                }
            }
            SqliteConversationRepository(path).use { store ->
                val turn = store.chatTurns("conversation").single()
                assertEquals("Existing question", turn.question)
                assertNull(turn.parentTurnRunId)
                assertEquals(record(), store.load("conversation"))
            }
        }

    @Test
    fun `projection removes credentials snippets and raw evidence before applying the bound`() {
        val snippet = "private fun expensiveOperation() = secretValue()"
        val payload = "{\"privateTrace\":\"raw evidence\"}"
        val analysis =
            request("Explain $snippet $payload password=secret-value sk-privatecredential0123456789").copy(
                evidence = listOf(PerformanceEvidence("evidence", "cpu-hotspot", "Fact", payload)),
                sourceCandidates =
                    listOf(
                        AiSourceCandidate("candidate", "Main.kt", null, "PROBABLE", emptyList(), snippet, 1, 1, "a".repeat(64)),
                    ),
            )
        val question = projectChatTurn("conversation", "run-1", 0, analysis, 1).question!!
        assertFalse(question.contains(snippet) || question.contains(payload) || question.contains("secret-value"))
        assertFalse(question.contains("sk-privatecredential"))
        assertTrue(question.contains("[source omitted]") && question.contains("[evidence omitted]"))
        val expanded = projectChatTurn("conversation", "run-1", 0, request("sk-12345678 ".repeat(300)), 1).question!!
        assertTrue(expanded.length <= 4096)
        assertFalse(expanded.contains("sk-12345678"))
        assertTrue(projectChatTitle("sk-12345678 ".repeat(10)).length <= 120)
        assertFailsWith<IllegalArgumentException> { projectChatTitle("x".repeat(121)) }
        assertFailsWith<IllegalArgumentException> { projectChatTitle(" ") }
    }

    @Test
    fun `failed chat insert rolls back new conversation and appended turn atomically`() =
        withDatabase { path ->
            SqliteConversationRepository(path).use { store ->
                val old = record("existing").copy(turns = listOf(turn().copy(status = RunStatus.CANCELLED)))
                store.save(old)
                val oldMetadata = store.chatMetadata(old.id)
                DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute(
                            "CREATE TRIGGER fail_chat BEFORE INSERT ON conversation_chat_turn BEGIN SELECT RAISE(ABORT, 'rejected'); END",
                        )
                    }
                }
                val fresh = record()
                assertFailsWith<ConversationStorageException> {
                    store.savePrepared(fresh, projectChatTurn(fresh.id, "run-1", 0, request("New"), 1))
                }
                assertNull(store.load(fresh.id))
                assertNull(store.chatMetadata(fresh.id))
                val next = old.copy(turns = old.turns + turn("run-2", 1))
                assertFailsWith<ConversationStorageException> {
                    store.savePrepared(next, projectChatTurn(old.id, "run-2", 1, request("Next"), 2))
                }
                assertEquals(old, store.load(old.id))
                assertEquals(oldMetadata, store.chatMetadata(old.id))
                assertTrue(store.chatTurns(old.id).isEmpty())
            }
        }

    @Test
    fun `delete and prune cascade all chat tables and do not leak metadata to reused ids`() =
        withDatabase { path ->
            SqliteConversationRepository(path).use { store ->
                for (id in listOf("deleted", "pruned", "active")) {
                    val record = record(id)
                    store.savePrepared(record, projectChatTurn(id, "run-1", 0, request("Question $id"), 1))
                    store.renameConversation(id, "Named $id")
                    store.pinConversation(id, true)
                    store.archiveConversation(id, true)
                    if (id != "active") store.save(record.copy(turns = listOf(turn().copy(status = RunStatus.CANCELLED))))
                }
                assertTrue(store.delete("deleted"))
                assertEquals(1, store.prune(0, Long.MAX_VALUE))
                for (id in listOf("deleted", "pruned")) {
                    assertNull(store.load(id))
                    assertNull(store.chatMetadata(id))
                    assertTrue(store.chatTurns(id).isEmpty())
                    assertFailsWith<ConversationStorageException> { store.renameConversation(id, "Ghost") }
                    assertFailsWith<ConversationStorageException> { store.pinConversation(id, true) }
                    assertFailsWith<ConversationStorageException> { store.archiveConversation(id, true) }
                }
                DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT COUNT(*) FROM conversation_chat_turn").use { rows ->
                            rows.next()
                            assertEquals(1, rows.getInt(1))
                        }
                        statement.executeQuery("SELECT COUNT(*) FROM conversation_chat_meta").use { rows ->
                            rows.next()
                            assertEquals(1, rows.getInt(1))
                        }
                    }
                }
                store.save(record("deleted"))
                assertNull(store.chatMetadata("deleted")!!.title)
                assertFalse(store.chatMetadata("deleted")!!.pinned)
                assertFalse(store.chatMetadata("deleted")!!.archived)
                assertTrue(store.chatTurns("deleted").isEmpty())
            }
        }

    @Test
    fun `questions are immutable and bound to the actual run generation`() =
        withDatabase { path ->
            SqliteConversationRepository(path).use { store ->
                val record = record()
                val projected = projectChatTurn(record.id, "run-1", 0, request("Original"), 1)
                store.savePrepared(record, projected)
                store.savePrepared(record, projected)
                assertFailsWith<ConversationStorageException> { store.savePrepared(record, projected.copy(generation = 1)) }
                assertFailsWith<ConversationStorageException> { store.savePrepared(record, projected.copy(runId = "unrelated")) }
                assertFailsWith<ConversationStorageException> { store.savePrepared(record, projected.copy(question = "Replacement")) }
                assertEquals(listOf(projected), store.chatTurns(record.id))
            }
        }

    @Test
    fun `corrupt chat rows fail closed before exposing unbounded or unrelated questions`() =
        withDatabase { path ->
            SqliteConversationRepository(path).use { store ->
                val record = record()
                store.savePrepared(record, projectChatTurn(record.id, "run-1", 0, request("Original"), 1))
                for (sql in listOf(
                    "UPDATE conversation_chat_turn SET question = 'sk-privatecredential0123456789'",
                    "UPDATE conversation_chat_turn SET question = printf('%20000s', 'x')",
                    "UPDATE conversation_chat_turn SET generation = 1",
                    "UPDATE conversation_chat_turn SET run_id = 'unknown-run'",
                )) {
                    DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                        connection.createStatement().use { statement -> statement.execute(sql) }
                    }
                    assertFailsWith<ConversationStorageException> { store.chatTurns(record.id) }
                    DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                        connection.createStatement().use { statement ->
                            statement.execute("UPDATE conversation_chat_turn SET run_id = 'run-1', generation = 0, question = 'Original'")
                        }
                    }
                }
                assertEquals("Original", store.chatTurns(record.id).single().question)
            }
        }

    @Test
    fun `corrupt chat metadata is rejected rather than displayed or treated as a valid boolean`() =
        withDatabase { path ->
            SqliteConversationRepository(path).use { store ->
                store.save(record())
                store.renameConversation("conversation", "Original")
                for (sql in listOf(
                    "UPDATE conversation_chat_meta SET title = 'sk-privatecredential0123456789'",
                    "UPDATE conversation_chat_meta SET title = printf('%20000s', 'x')",
                    "UPDATE conversation_chat_meta SET title = ' '",
                    "UPDATE conversation_chat_meta SET pinned = 2",
                    "UPDATE conversation_chat_meta SET pinned = 'invalid'",
                    "UPDATE conversation_chat_meta SET archived = -1",
                )) {
                    DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                        connection.createStatement().use { statement -> statement.execute(sql) }
                    }
                    assertFailsWith<ConversationStorageException> { store.chatMetadata("conversation") }
                    DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                        connection.createStatement().use { statement ->
                            statement.execute("UPDATE conversation_chat_meta SET title = 'Original', pinned = 0, archived = 0")
                        }
                    }
                }
                assertEquals("Original", store.chatMetadata("conversation")!!.title)
            }
        }

    private fun record(id: String = "conversation") =
        ConversationRecord(id = id, analysisSessionId = "session", contextHash = "a".repeat(64), turns = listOf(turn()))

    private fun turn(
        runId: String = "run-1",
        generation: Long = 0,
    ) = ConversationTurn(runId, generation, RunStatus.AWAITING_APPROVAL, listOf("evidence"), emptyList())

    private fun request(question: String) =
        AnalysisRequest(
            AnalysisSessionId("session"),
            ProfilerKind.SIMPLEPERF,
            AnalysisScope(AnalysisScopeKind.REPORT_SUMMARY, "Report"),
            listOf(PerformanceEvidence("evidence", "cpu-hotspot", "Fact", "{}")),
            emptyList(),
            "v1",
            "v1",
            question,
        )

    private fun withDatabase(block: (Path) -> Unit) {
        val root = Files.createTempDirectory("aps-chat-history-").toRealPath()
        try {
            block(root.resolve("private/history.sqlite"))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

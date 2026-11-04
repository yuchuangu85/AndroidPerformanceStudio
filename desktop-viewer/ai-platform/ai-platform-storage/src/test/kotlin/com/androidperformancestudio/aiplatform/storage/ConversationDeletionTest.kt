package com.androidperformancestudio.aiplatform.storage

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

class ConversationDeletionTest {
    @Test
    fun `chat deletion removes multilevel agents and projections but preserves unrelated forks`() =
        withStore { store ->
            val root = record("root", "root-session")
            val child = record("child", "child-session", root.id)
            // Compare with the immediate parent, not the root session.
            val grandchild = record("grandchild", root.analysisSessionId, child.id)
            val leaf = record("leaf", "leaf-session", grandchild.id)
            val other = record("other", "other-session")
            val otherFork = record("other-fork", other.analysisSessionId, other.id)
            val deleted = listOf(root, child, grandchild, leaf)
            (deleted + other + otherFork).forEach { saveHistory(store, it) }

            assertTrue(store.deleteChatConversation(root.id))

            assertEquals(setOf(other.id, otherFork.id), store.list().map { it.id }.toSet())
            deleted.forEach { record ->
                assertNull(store.load(record.id))
                assertNull(store.result(record.id, "run"))
                assertNull(store.chatMetadata(record.id))
                assertTrue(store.chatTurns(record.id).isEmpty())
                assertTrue(store.audit(record.id).isEmpty())
            }
            assertEquals(listOf("a".repeat(64)), store.result(other.id, "run")!!.artifactHashes)
            assertEquals("Question", store.chatTurns(otherFork.id).single().question)
            assertFalse(store.deleteChatConversation(root.id))
        }

    @Test
    fun `fork at any planned depth blocks the entire deletion without changing history`() {
        for (parentId in listOf("root", "child", "grandchild")) {
            withStore { store ->
                val root = record("root", "root-session")
                val child = record("child", "child-session", root.id)
                val grandchild = record("grandchild", "grandchild-session", child.id)
                val tree = listOf(root, child, grandchild)
                val parent = tree.single { it.id == parentId }
                val fork = record("fork", parent.analysisSessionId, parent.id)
                (tree + fork).forEach { saveHistory(store, it) }
                val before = store.list()

                assertCode("E_CONVERSATION_HAS_CHILDREN") { store.deleteChatConversation(root.id) }

                assertEquals(before, store.list())
                before.forEach { saved ->
                    assertEquals("Summary", store.result(saved.id, "run")!!.summary)
                    assertEquals("Question", store.chatTurns(saved.id).single().question)
                    assertEquals("Title", store.chatMetadata(saved.id)!!.title)
                    assertEquals(1, store.audit(saved.id).size)
                }
            }
        }
    }

    @Test
    fun `nonterminal root or descendant rejects deletion before any record is removed`() {
        for (activeId in listOf("root", "child", "grandchild")) {
            withStore { store ->
                val tree =
                    listOf(
                        record("root", "root-session"),
                        record("child", "child-session", "root"),
                        record("grandchild", "grandchild-session", "child"),
                    ).map { record ->
                        if (record.id == activeId) {
                            record.copy(turns = listOf(record.turns.single().copy(status = RunStatus.RUNNING)))
                        } else {
                            record
                        }
                    }
                tree.forEach(store::save)
                val before = store.list()

                assertCode("E_CONVERSATION_ACTIVE") { store.deleteChatConversation("root") }

                assertEquals(before, store.list())
            }
        }
    }

    @Test
    fun `legacy delete still rejects graph children and fork deletion preserves its parent`() =
        withStore { store ->
            val root = record("root", "root-session")
            val child = record("child", "child-session", root.id)
            store.save(root)
            store.save(child)
            assertCode("E_CONVERSATION_HAS_CHILDREN") { store.delete(root.id) }
            assertEquals(setOf(root.id, child.id), store.list().map { it.id }.toSet())
            assertTrue(store.deleteChatConversation(child.id))
            val fork = record("fork", root.analysisSessionId, root.id)
            store.save(fork)
            assertTrue(store.deleteChatConversation(fork.id))
            assertEquals(root, store.load(root.id))
        }

    @Test
    fun `iterative planning handles a long chain and rejects cycles without partial deletion`() =
        withStore { store ->
            repeat(128) { index ->
                store.save(record("node-$index", "session-$index", if (index == 0) null else "node-${index - 1}"))
            }
            assertTrue(store.deleteChatConversation("node-0"))
            assertTrue(store.list().isEmpty())
            val first = record("first", "first-session", "second")
            val second = record("second", "second-session", first.id)
            store.save(first)
            store.save(second)
            assertCode("E_STORAGE_CORRUPT") { store.deleteChatConversation(first.id) }
            assertEquals(setOf(first, second), store.list().toSet())
            assertCode("E_CONVERSATION_ID") { store.deleteChatConversation("") }
        }

    private fun record(
        id: String,
        session: String,
        parent: String? = null,
    ) = ConversationRecord(
        id = id,
        analysisSessionId = session,
        contextHash = "a".repeat(64),
        parentConversationId = parent,
        turns = listOf(ConversationTurn("run", 0, RunStatus.SUCCEEDED, emptyList(), emptyList())),
    )

    private fun saveHistory(
        store: SqliteConversationRepository,
        record: ConversationRecord,
    ) {
        val prepared = record.copy(turns = listOf(record.turns.single().copy(status = RunStatus.AWAITING_APPROVAL)))
        store.savePrepared(prepared, StoredChatTurn(record.id, "run", 0, "Question", 1))
        store.complete(
            record,
            StoredAnalysisResult(
                record.id,
                "run",
                0,
                record.analysisSessionId,
                "test-model",
                "Summary",
                emptyList(),
                1,
                artifactHashes = listOf("a".repeat(64)),
            ),
        )
        store.renameConversation(record.id, "Title")
        store.pinConversation(record.id, true)
        store.appendAudit(
            ExecutionAuditEvent(record.id, "run", 0, 0, ExecutionKind.RUN, ExecutionPhase.SUCCEEDED, "aps.main", timestampEpochMillis = 1),
        )
    }

    private fun assertCode(
        code: String,
        block: () -> Unit,
    ) {
        val failure = assertFailsWith<ConversationStorageException>(block = block)
        assertEquals(code, failure.code)
        assertEquals(code, failure.message)
        assertNull(failure.cause)
    }

    private fun withStore(block: (SqliteConversationRepository) -> Unit) {
        val root = Files.createTempDirectory("aps-chat-delete-").toRealPath()
        try {
            SqliteConversationRepository(root.resolve("private/history.sqlite")).use(block)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}

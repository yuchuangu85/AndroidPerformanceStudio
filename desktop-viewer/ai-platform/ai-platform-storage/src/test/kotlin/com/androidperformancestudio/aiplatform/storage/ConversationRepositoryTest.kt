package com.androidperformancestudio.aiplatform.storage

import com.androidperformancestudio.aiplatform.model.ConversationRecord
import com.androidperformancestudio.aiplatform.model.ConversationTurn
import com.androidperformancestudio.aiplatform.model.RunStatus
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.sql.DriverManager
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConversationRepositoryTest {
    @Test
    fun `round trips reference-only records across repository reopen`() =
        withDatabase { path ->
            val expected = record()
            SqliteConversationRepository(path).use { repository ->
                assertNull(repository.load("missing"))
                repository.save(expected)
                assertEquals(expected, repository.load(expected.id))
            }
            SqliteConversationRepository(path).use { repository ->
                assertEquals(listOf(expected), repository.list())
            }
        }

    @Test
    fun `creates private directories and database on posix hosts`() =
        withDatabase { path ->
            SqliteConversationRepository(path).use { repository -> repository.save(record()) }
            if (Files.getFileAttributeView(path, PosixFileAttributeView::class.java) != null) {
                assertEquals(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(path))
                assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(path.parent))
            }
        }

    @Test
    fun `rejects mutable session context without losing previous record`() =
        withDatabase { path ->
            SqliteConversationRepository(path).use { repository ->
                val original = record()
                repository.save(original)
                listOf(
                    original.copy(analysisSessionId = "another-session"),
                    original.copy(contextHash = "b".repeat(64)),
                    original.copy(parentConversationId = "another-parent"),
                ).forEach { changed ->
                    assertCode("E_IMMUTABLE_CONTEXT") { repository.save(changed) }
                    assertEquals(original, repository.load(original.id))
                }
            }
        }

    @Test
    fun `same active run progresses but cannot regress`() =
        withDatabase { path ->
            SqliteConversationRepository(path).use { repository ->
                val queued = record()
                repository.save(queued)
                val running = queued.withStatus(RunStatus.RUNNING)
                repository.save(running)
                assertCode("E_RUN_STATE") { repository.save(queued) }
                assertEquals(running, repository.load(queued.id))
            }
        }

    @Test
    fun `terminal result cannot be replaced by a late response`() =
        withDatabase { path ->
            SqliteConversationRepository(path).use { repository ->
                val cancelled = record().withStatus(RunStatus.CANCELLED)
                repository.save(cancelled)
                repository.save(cancelled)
                assertCode("E_STALE_CONVERSATION") { repository.save(cancelled.withStatus(RunStatus.SUCCEEDED)) }
                assertCode("E_STALE_CONVERSATION") { repository.save(cancelled.withStatus(RunStatus.RUNNING)) }
                assertEquals(cancelled, repository.load(cancelled.id))
            }
        }

    @Test
    fun `appending generation prevents older complete snapshot overwrite`() =
        withDatabase { path ->
            SqliteConversationRepository(path).use { repository ->
                val first = record().withStatus(RunStatus.SUCCEEDED)
                repository.save(first)
                val second = first.copy(turns = first.turns + turn("run-2", 2))
                repository.save(second)
                assertCode("E_STALE_CONVERSATION") { repository.save(first) }
                assertEquals(second, repository.load(first.id))
            }
        }

    @Test
    fun `cross connection stale updates are rejected after writer transaction`() =
        withDatabase { path ->
            SqliteConversationRepository(path).use { first ->
                SqliteConversationRepository(path).use { second ->
                    val initial = record()
                    first.save(initial)
                    val cancelled = initial.withStatus(RunStatus.CANCELLED)
                    second.save(cancelled)
                    assertCode("E_STALE_CONVERSATION") { first.save(initial.withStatus(RunStatus.SUCCEEDED)) }
                    assertEquals(cancelled, second.load(initial.id))
                }
            }
        }

    @Test
    fun `rejects reused generation changed references and unfinished older run`() =
        withDatabase { path ->
            SqliteConversationRepository(path).use { repository ->
                val initial = record()
                repository.save(initial)
                assertCode("E_RUN_GENERATION") {
                    repository.save(
                        initial.copy(turns = listOf(initial.turns.single().copy(status = RunStatus.CANCELLED), turn("run-2", 1))),
                    )
                }
                assertCode("E_STALE_CONVERSATION") {
                    repository.save(initial.copy(turns = listOf(initial.turns.single().copy(evidenceIds = listOf("different-evidence")))))
                }
                assertCode("E_RUN_STATE") { repository.save(initial.copy(turns = initial.turns + turn("run-2", 2))) }
                assertEquals(initial, repository.load(initial.id))
            }
        }

    @Test
    fun `cancel previous run and append new generation atomically`() =
        withDatabase { path ->
            SqliteConversationRepository(path).use { repository ->
                val initial = record()
                repository.save(initial)
                val replacement = initial.copy(turns = listOf(initial.turns.single().copy(status = RunStatus.CANCELLED), turn("run-2", 2)))
                repository.save(replacement)
                assertEquals(replacement, repository.load(initial.id))
            }
        }

    @Test
    fun `recovery marks only nonterminal runs interrupted and never replays`() =
        withDatabase { path ->
            SqliteConversationRepository(path).use { repository ->
                RunStatus.entries.forEachIndexed { index, status ->
                    repository.save(record("conversation-$index").withStatus(status))
                }
                assertEquals(4, repository.recoverInterrupted())
                assertEquals(0, repository.recoverInterrupted())
                RunStatus.entries.forEachIndexed { index, status ->
                    val stored = repository.load("conversation-$index")!!.turns.single()
                    assertEquals(if (status.terminal) status else RunStatus.INTERRUPTED, stored.status)
                    assertEquals(if (status.terminal) null else "E_RUN_INTERRUPTED", stored.errorCode)
                }
            }
        }

    @Test
    fun `recovery rolls back prior updates if any stored record is corrupt`() =
        withDatabase { path ->
            SqliteConversationRepository(path).use { repository ->
                val healthy = record("a-healthy")
                repository.save(healthy)
                repository.save(record("z-corrupt"))
                replaceRaw(path, "z-corrupt", "{\"prompt\":\"private-source-secret\"}")
                assertCode("E_STORAGE_CORRUPT") { repository.recoverInterrupted() }
                assertEquals(healthy, repository.load(healthy.id))
            }
        }

    @Test
    fun `unknown persisted keys and corrupt json fail closed without echoing contents`() =
        withDatabase { path ->
            SqliteConversationRepository(path).use { repository ->
                val original = record()
                repository.save(original)
                val originalJson = rawRecord(path, original.id)
                replaceRaw(path, original.id, originalJson.dropLast(1) + ",\"apiKey\":\"private-source-secret\"}")
                val failure = assertFailsWith<ConversationStorageException> { repository.load(original.id) }
                assertEquals("E_STORAGE_CORRUPT", failure.code)
                assertFalse(failure.toString().contains("private-source-secret"))
                assertNull(failure.cause)
                replaceRaw(path, original.id, "not-json-private-source-secret")
                assertCode("E_STORAGE_CORRUPT") { repository.list() }
            }
        }

    @Test
    fun `stored identity must agree with primary key`() =
        withDatabase { path ->
            SqliteConversationRepository(path).use { repository ->
                val original = record()
                repository.save(original)
                replaceRaw(path, original.id, rawRecord(path, original.id).replace("\"${original.id}\"", "\"wrong-id\""))
                assertCode("E_STORAGE_CORRUPT") { repository.load(original.id) }
            }
        }

    @Test
    fun `oversized snapshot fails before insert`() =
        withDatabase { path ->
            SqliteConversationRepository(path).use { repository ->
                val references = (1..100).map { "evidence-$it-" + "x".repeat(230) }
                val turns =
                    (1..100).map { index ->
                        turn("run-$index", index.toLong()).copy(status = RunStatus.SUCCEEDED, evidenceIds = references)
                    }
                assertCode("E_CONVERSATION_SIZE") { repository.save(record().copy(turns = turns)) }
                assertTrue(repository.list().isEmpty())
            }
        }

    @Test
    fun `pagination and conversations remain isolated`() =
        withDatabase { path ->
            SqliteConversationRepository(path).use { repository ->
                val first = record("a")
                val second = record("b")
                repository.save(second)
                repository.save(first)
                assertEquals(listOf(first), repository.list(limit = 1))
                assertEquals(listOf(second), repository.list(limit = 1, offset = 1))
                assertCode("E_STORAGE_PAGE") { repository.list(limit = 0) }
                assertCode("E_STORAGE_PAGE") { repository.list(offset = -1) }
                assertCode("E_CONVERSATION_ID") { repository.load("") }
            }
        }

    @Test
    fun `rejects db and parent symlinks without touching targets`() =
        withDatabase { path ->
            Files.createDirectories(path.parent)
            if (Files.getFileAttributeView(path.parent, PosixFileAttributeView::class.java) != null) {
                Files.setPosixFilePermissions(path.parent, PosixFilePermissions.fromString("rwx------"))
            }
            val target = path.parent.resolve("target")
            Files.writeString(target, "leave-unchanged")
            Files.createSymbolicLink(path, target)
            assertCode("E_STORAGE_SYMLINK") { SqliteConversationRepository(path) }
            assertEquals("leave-unchanged", Files.readString(target))
            Files.delete(path)
            val linkedParent = path.parent.resolve("linked")
            Files.createSymbolicLink(linkedParent, path.parent)
            assertCode("E_STORAGE_SYMLINK") { SqliteConversationRepository(linkedParent.resolve("other.db")) }
            assertFalse(Files.exists(path.parent.resolve("other.db")))
        }

    @Test
    fun `rejects sqlite sidecar symlink on open and later calls`() =
        withDatabase { path ->
            SqliteConversationRepository(path).use { repository ->
                val target = path.parent.resolve("target")
                Files.writeString(target, "leave-unchanged")
                val sidecar = path.resolveSibling("${path.fileName}-journal")
                Files.createSymbolicLink(sidecar, target)
                assertCode("E_STORAGE_SYMLINK") { repository.save(record()) }
                assertEquals("leave-unchanged", Files.readString(target))
                Files.delete(sidecar)
            }
        }

    @Test
    fun `corrupt database and unsupported schema expose stable codes only`() =
        withDatabase { path ->
            SqliteConversationRepository(path).close()
            DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
                connection.createStatement().use { it.execute("PRAGMA user_version = 999") }
            }
            assertCode("E_STORAGE_SCHEMA") { SqliteConversationRepository(path) }
            Files.writeString(path, "raw-private-source-secret-not-a-database")
            val failure = assertFailsWith<ConversationStorageException> { SqliteConversationRepository(path) }
            assertEquals("E_STORAGE_OPEN", failure.code)
            assertNull(failure.cause)
            assertFalse(failure.toString().contains("raw-private"))
        }

    @Test
    fun `broad existing parent is rejected without permission mutation`() =
        withDatabase { path ->
            Files.createDirectories(path.parent)
            if (Files.getFileAttributeView(path.parent, PosixFileAttributeView::class.java) != null) {
                val permissions = PosixFilePermissions.fromString("rwxr-xr-x")
                Files.setPosixFilePermissions(path.parent, permissions)
                assertCode("E_STORAGE_PERMISSIONS") { SqliteConversationRepository(path) }
                assertEquals(permissions, Files.getPosixFilePermissions(path.parent))
            }
        }

    @Test
    fun `duplicate references and same status payload mutation cannot overwrite`() =
        withDatabase { path ->
            SqliteConversationRepository(path).use { repository ->
                val original = record()
                repository.save(original)
                assertCode("E_RUN_REFERENCES") {
                    repository.save(original.copy(turns = listOf(original.turns.single().copy(evidenceIds = listOf("e", "e")))))
                }
                assertCode("E_STALE_CONVERSATION") {
                    repository.save(original.copy(turns = listOf(original.turns.single().copy(findingIds = listOf("injected-finding")))))
                }
                assertEquals(original, repository.load(original.id))
            }
        }

    @Test
    fun `oversized corrupted database row is rejected before record decoding`() =
        withDatabase { path ->
            SqliteConversationRepository(path).use { repository ->
                val original = record()
                repository.save(original)
                replaceRaw(path, original.id, "x".repeat(1_048_577))
                assertCode("E_CONVERSATION_SIZE") { repository.load(original.id) }
                assertCode("E_CONVERSATION_SIZE") { repository.list() }
            }
        }

    @Test
    fun `closed repository refuses operations with stable code`() =
        withDatabase { path ->
            val repository = SqliteConversationRepository(path)
            repository.close()
            assertCode("E_STORAGE_CLOSED") { repository.load("conversation") }
        }

    private fun record(id: String = "conversation"): ConversationRecord =
        ConversationRecord(
            id = id,
            analysisSessionId = "analysis-session",
            contextHash = "a".repeat(64),
            turns = listOf(turn()),
        )

    private fun turn(
        runId: String = "run-1",
        generation: Long = 1,
    ): ConversationTurn =
        ConversationTurn(
            runId = runId,
            generation = generation,
            status = RunStatus.QUEUED,
            evidenceIds = listOf("evidence-1"),
            candidateIds = listOf("candidate-1"),
        )

    private fun ConversationRecord.withStatus(status: RunStatus): ConversationRecord =
        copy(turns = listOf(turns.single().copy(status = status)))

    private fun assertCode(
        code: String,
        block: () -> Unit,
    ) {
        assertEquals(code, assertFailsWith<ConversationStorageException>(block = block).code)
    }

    private fun replaceRaw(
        path: Path,
        id: String,
        value: String,
    ) {
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.prepareStatement("UPDATE conversation SET record = ? WHERE id = ?").use { statement ->
                statement.setString(1, value)
                statement.setString(2, id)
                assertEquals(1, statement.executeUpdate())
            }
        }
    }

    private fun rawRecord(
        path: Path,
        id: String,
    ): String =
        DriverManager.getConnection("jdbc:sqlite:$path").use { connection ->
            connection.prepareStatement("SELECT record FROM conversation WHERE id = ?").use { statement ->
                statement.setString(1, id)
                statement.executeQuery().use { rows ->
                    assertTrue(rows.next())
                    rows.getString(1)
                }
            }
        }

    private fun withDatabase(block: (Path) -> Unit) {
        // macOS exposes /var as a symlink; resolve the OS temp root, not managed symlink paths.
        val root = createTempDirectory("aps-conversation-store").toRealPath()
        try {
            block(root.resolve("conversations/history.sqlite"))
        } finally {
            Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists) }
        }
    }
}

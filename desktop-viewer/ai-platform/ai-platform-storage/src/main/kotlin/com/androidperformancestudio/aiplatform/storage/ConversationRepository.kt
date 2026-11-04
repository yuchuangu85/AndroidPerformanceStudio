package com.androidperformancestudio.aiplatform.storage

import com.androidperformancestudio.aiplatform.model.ConversationRecord
import com.androidperformancestudio.aiplatform.model.ConversationTurn
import com.androidperformancestudio.aiplatform.model.PlatformJson
import com.androidperformancestudio.aiplatform.model.RunStatus
import kotlinx.serialization.encodeToString
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.sql.Connection
import java.sql.DriverManager

public interface ConversationRepository {
    public fun save(record: ConversationRecord)

    public fun load(id: String): ConversationRecord?

    public fun list(
        limit: Int = 100,
        offset: Int = 0,
    ): List<ConversationRecord>

    /** Explicit startup recovery only; never resumes or replays external actions. */
    public fun recoverInterrupted(): Int
}

/** Stable public code only: SQL, paths and rejected data are deliberately not attached as causes. */
public class ConversationStorageException(
    public val code: String,
) : IllegalStateException(code)

/** Reference-only store. A complete immutable-context snapshot is the unit of an atomic save. */
public class SqliteConversationRepository(
    databasePath: Path,
) : ConversationHistoryRepository,
    AutoCloseable {
    private val database = databasePath.toAbsolutePath().normalize()
    private val lock = Any()
    private val connection: Connection = openDatabase(database)

    override fun save(record: ConversationRecord): Unit =
        guarded {
            val encoded = encodeRecord(record)
            val snapshot = decodeRecord(record.id, encoded)
            transaction {
                saveUnlocked(snapshot, encoded)
            }
        }

    private fun saveUnlocked(
        record: ConversationRecord,
        encoded: String,
    ) {
        val previous = read(record.id)
        if (previous == null) {
            checkStorage(countRecords() < MAX_CONVERSATIONS, "E_CONVERSATION_LIMIT")
        } else {
            validateUpdate(previous, record)
        }
        write(record.id, encoded)
    }

    override fun savePrepared(
        record: ConversationRecord,
        turn: StoredChatTurn,
    ): Unit =
        guarded {
            val encoded = encodeRecord(record)
            val frozen = decodeRecord(record.id, encoded)
            val last = frozen.turns.lastOrNull()
            checkStorage(
                frozen.id == turn.conversationId &&
                    last?.runId == turn.runId &&
                    last.generation == turn.generation &&
                    last.status == RunStatus.AWAITING_APPROVAL,
                "E_CHAT_TURN",
            )
            transaction {
                saveUnlocked(frozen, encoded)
                turn.parentTurnRunId?.let { parentRunId ->
                    val local = frozen.turns.any { it.runId == parentRunId && it.generation < turn.generation && it.status.terminal }
                    val parent =
                        if (frozen.turns.size == 1) frozen.parentConversationId?.let(::read) else null
                    checkStorage(
                        local ||
                            parent?.let { source ->
                                source.analysisSessionId == frozen.analysisSessionId &&
                                    source.contextHash == frozen.contextHash &&
                                    source.turns.any { it.runId == parentRunId && it.status.terminal }
                            } == true,
                        "E_PARENT_TURN",
                    )
                }
                val existing = chatTurnsUnlocked(frozen.id).singleOrNull { it.runId == turn.runId }
                checkStorage(existing == null || existing == turn, "E_IMMUTABLE_CHAT_TURN")
                connection
                    .prepareStatement(
                        "INSERT OR IGNORE INTO conversation_chat_turn(conversation_id, run_id, generation, question, created_at, parent_run_id) VALUES (?, ?, ?, ?, ?, ?)",
                    ).use { statement ->
                        statement.setString(1, turn.conversationId)
                        statement.setString(2, turn.runId)
                        statement.setLong(3, turn.generation)
                        statement.setString(4, turn.question)
                        statement.setLong(5, turn.timestampEpochMillis)
                        statement.setString(6, turn.parentTurnRunId)
                        statement.executeUpdate()
                    }
            }
        }

    override fun chatTurns(conversationId: String): List<StoredChatTurn> =
        guarded {
            validateId(conversationId)
            chatTurnsUnlocked(conversationId)
        }

    private fun chatTurnsUnlocked(conversationId: String): List<StoredChatTurn> {
        val canonicalTurns = read(conversationId)?.turns?.associateBy { it.runId }.orEmpty()
        return connection
            .prepareStatement(
                "SELECT run_id, generation, length(CAST(question AS BLOB)), question, created_at, parent_run_id, length(CAST(parent_run_id AS BLOB)) FROM conversation_chat_turn WHERE conversation_id = ? ORDER BY generation",
            ).use { statement ->
                statement.setString(1, conversationId)
                statement.executeQuery().use { rows ->
                    buildList {
                        while (rows.next()) {
                            checkStorage(size < 1000 && rows.getInt(3) <= 16_384 && rows.getInt(7) <= 512, "E_CHAT_LIMIT")
                            val runId = rows.getString(1)
                            val generation = rows.getLong(2)
                            checkStorage(canonicalTurns[runId]?.generation == generation, "E_CHAT_TURN")
                            add(
                                StoredChatTurn(conversationId, runId, generation, rows.getString(4), rows.getLong(5), rows.getString(6)),
                            )
                        }
                    }
                }
            }
    }

    override fun chatMetadata(conversationId: String): StoredChatMetadata? =
        guarded {
            validateId(conversationId)
            connection
                .prepareStatement(
                    "SELECT length(CAST(c.title AS BLOB)), c.title, c.pinned, m.updated_at, c.archived FROM conversation_meta m LEFT JOIN conversation_chat_meta c ON c.id = m.id WHERE m.id = ?",
                ).use { statement ->
                    statement.setString(1, conversationId)
                    statement.executeQuery().use { rows ->
                        if (rows.next()) {
                            checkStorage(rows.getInt(1) <= 480, "E_CHAT_LIMIT")
                            checkStorage(
                                rows.getString(3) in setOf(null, "0", "1") && rows.getString(5) in setOf(null, "0", "1"),
                                "E_STORAGE_CORRUPT",
                            )
                            StoredChatMetadata(
                                rows.getString(2),
                                rows.getBoolean(3),
                                rows.getLong(4),
                                rows.getBoolean(5),
                            )
                        } else {
                            null
                        }
                    }
                }
        }

    override fun renameConversation(
        conversationId: String,
        title: String,
    ): Unit =
        guarded {
            validateId(conversationId)
            checkStorage(title.isNotBlank() && title.length <= 120, "E_CHAT_TITLE")
            val projected = projectChatTitle(title)
            transaction {
                checkStorage(read(conversationId) != null, "E_CONVERSATION_UNAVAILABLE")
                connection
                    .prepareStatement(
                        "INSERT INTO conversation_chat_meta(id, title, pinned) VALUES (?, ?, 0) ON CONFLICT(id) DO UPDATE SET title=excluded.title",
                    ).use { statement ->
                        statement.setString(1, conversationId)
                        statement.setString(2, projected)
                        statement.executeUpdate()
                    }
            }
        }

    override fun pinConversation(
        conversationId: String,
        pinned: Boolean,
    ): Unit =
        guarded {
            validateId(conversationId)
            transaction {
                checkStorage(read(conversationId) != null, "E_CONVERSATION_UNAVAILABLE")
                connection
                    .prepareStatement(
                        "INSERT INTO conversation_chat_meta(id, title, pinned) VALUES (?, NULL, ?) ON CONFLICT(id) DO UPDATE SET pinned=excluded.pinned",
                    ).use { statement ->
                        statement.setString(1, conversationId)
                        statement.setBoolean(2, pinned)
                        statement.executeUpdate()
                    }
            }
        }

    override fun archiveConversation(
        conversationId: String,
        archived: Boolean,
    ): Unit =
        guarded {
            validateId(conversationId)
            transaction {
                checkStorage(read(conversationId) != null, "E_CONVERSATION_UNAVAILABLE")
                connection
                    .prepareStatement(
                        "INSERT INTO conversation_chat_meta(id, archived) VALUES (?, ?) ON CONFLICT(id) DO UPDATE SET archived=excluded.archived",
                    ).use { statement ->
                        statement.setString(1, conversationId)
                        statement.setBoolean(2, archived)
                        statement.executeUpdate()
                    }
            }
        }

    override fun load(id: String): ConversationRecord? =
        guarded {
            validateId(id)
            read(id)
        }

    override fun list(
        limit: Int,
        offset: Int,
    ): List<ConversationRecord> =
        guarded {
            checkStorage(limit in 1..MAX_PAGE_SIZE && offset in 0..MAX_CONVERSATIONS, "E_STORAGE_PAGE")
            connection
                .prepareStatement(
                    "SELECT id, length(CAST(record AS BLOB)), record FROM conversation ORDER BY id LIMIT ? OFFSET ?",
                ).use { statement ->
                    statement.setInt(1, limit)
                    statement.setInt(2, offset)
                    statement.executeQuery().use { rows ->
                        buildList {
                            while (rows.next()) {
                                checkStorage(rows.getInt(2) <= MAX_RECORD_BYTES, "E_CONVERSATION_SIZE")
                                add(decodeRecord(rows.getString(1), rows.getString(3)))
                            }
                        }
                    }
                }
        }

    override fun recoverInterrupted(): Int =
        guarded {
            transaction {
                val ids =
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT id FROM conversation ORDER BY id").use { rows ->
                            buildList {
                                while (rows.next()) {
                                    checkStorage(size < MAX_CONVERSATIONS, "E_CONVERSATION_LIMIT")
                                    add(rows.getString(1))
                                }
                            }
                        }
                    }
                var changed = 0
                ids.forEach { id ->
                    val record = read(id) ?: failStorage("E_STORAGE_CORRUPT")
                    val turns =
                        record.turns.map { turn ->
                            if (turn.status.terminal) turn else turn.copy(status = RunStatus.INTERRUPTED, errorCode = "E_RUN_INTERRUPTED")
                        }
                    if (turns != record.turns) {
                        write(id, encodeRecord(record.copy(turns = turns)))
                        changed++
                    }
                }
                changed
            }
        }

    override fun saveResult(result: StoredAnalysisResult): Unit =
        guarded {
            transaction { saveResultUnlocked(result) }
        }

    override fun complete(
        record: ConversationRecord,
        result: StoredAnalysisResult,
    ): Unit =
        guarded {
            val encoded = encodeRecord(record)
            val frozen = decodeRecord(record.id, encoded)
            transaction {
                val previous = read(frozen.id) ?: failStorage("E_CONVERSATION_UNAVAILABLE")
                validateUpdate(previous, frozen)
                checkStorage(result.conversationId == frozen.id && result.runId == frozen.turns.lastOrNull()?.runId, "E_RESULT_TURN")
                write(frozen.id, encoded)
                saveResultUnlocked(result)
            }
        }

    private fun saveResultUnlocked(result: StoredAnalysisResult) {
        val encoded = PlatformJson.codec.encodeToString(result)
        checkStorage(encoded.toByteArray(Charsets.UTF_8).size <= 262_144, "E_RESULT_LIMIT")
        val frozen = PlatformJson.codec.decodeFromString<StoredAnalysisResult>(encoded)
        run {
            val conversation = read(frozen.conversationId) ?: failStorage("E_CONVERSATION_UNAVAILABLE")
            val turn = conversation.turns.singleOrNull { it.runId == frozen.runId } ?: failStorage("E_RESULT_TURN")
            requireResultTurn(turn.status, turn.generation == frozen.generation && conversation.analysisSessionId == frozen.sessionId)
            checkStorage(turn.findingIds == frozen.findings.map { it.id }, "E_RESULT_REFERENCES")
            checkStorage(
                frozen.findings.all { finding ->
                    finding.evidenceIds.all(turn.evidenceIds::contains) && finding.candidateIds.all(turn.candidateIds::contains)
                },
                "E_RESULT_REFERENCES",
            )
            val text =
                listOf(frozen.summary, frozen.model) + frozen.findings.flatMap { listOf(it.title, it.explanation, it.recommendation) }
            checkStorage(text.all { redactAnalysisText(it) == it }, "E_RESULT_REDACTION")
            if (frozen.parentTurnRunId != null) {
                val localParent =
                    conversation.turns.any {
                        it.runId == frozen.parentTurnRunId && it.status.terminal && it.generation < turn.generation
                    }
                val forkParent =
                    if (conversation.turns.firstOrNull()?.runId == turn.runId) {
                        conversation.parentConversationId?.let { read(it) }
                    } else {
                        null
                    }
                checkStorage(
                    localParent ||
                        forkParent?.let { parent ->
                            parent.analysisSessionId == conversation.analysisSessionId &&
                                parent.contextHash == conversation.contextHash &&
                                parent.turns.any { it.runId == frozen.parentTurnRunId && it.status.terminal }
                        } == true,
                    "E_PARENT_TURN",
                )
            }
            val existing = resultUnlocked(frozen.conversationId, frozen.runId)
            checkStorage(existing == null || existing == frozen, "E_IMMUTABLE_RESULT")
            connection
                .prepareStatement(
                    "INSERT OR IGNORE INTO conversation_result(conversation_id, run_id, record) VALUES (?, ?, ?)",
                ).use { statement ->
                    statement.setString(1, frozen.conversationId)
                    statement.setString(2, frozen.runId)
                    statement.setString(3, encoded)
                    statement.executeUpdate()
                }
        }
    }

    override fun result(
        conversationId: String,
        runId: String,
    ): StoredAnalysisResult? =
        guarded {
            validateId(conversationId)
            validateId(runId)
            resultUnlocked(conversationId, runId)
        }

    private fun resultUnlocked(
        conversationId: String,
        runId: String,
    ): StoredAnalysisResult? =
        connection
            .prepareStatement(
                "SELECT length(CAST(record AS BLOB)), record FROM conversation_result WHERE conversation_id = ? AND run_id = ?",
            ).use { statement ->
                statement.setString(1, conversationId)
                statement.setString(2, runId)
                statement.executeQuery().use { rows ->
                    if (rows.next()) {
                        checkStorage(rows.getInt(1) <= 262_144, "E_RESULT_LIMIT")
                        val result = PlatformJson.codec.decodeFromString<StoredAnalysisResult>(rows.getString(2))
                        checkStorage(result.conversationId == conversationId && result.runId == runId, "E_STORAGE_CORRUPT")
                        result
                    } else {
                        null
                    }
                }
            }

    override fun appendAudit(event: ExecutionAuditEvent): Unit =
        guarded {
            val encoded = PlatformJson.codec.encodeToString(event)
            checkStorage(encoded.toByteArray(Charsets.UTF_8).size <= 65_536, "E_AUDIT_LIMIT")
            val frozen = PlatformJson.codec.decodeFromString<ExecutionAuditEvent>(encoded)
            transaction {
                val record = read(frozen.conversationId) ?: failStorage("E_CONVERSATION_UNAVAILABLE")
                val turn = record.turns.singleOrNull { it.runId == frozen.runId } ?: failStorage("E_AUDIT_RUN")
                checkStorage(
                    turn.generation == frozen.generation &&
                        frozen.evidenceIds.all(turn.evidenceIds::contains) &&
                        frozen.candidateIds.all(turn.candidateIds::contains),
                    "E_AUDIT_REFERENCES",
                )
                frozen.relatedConversationId?.let { childId ->
                    val child = read(childId) ?: failStorage("E_AUDIT_CHILD")
                    checkStorage(child.turns.any { it.runId == frozen.relatedRunId }, "E_AUDIT_CHILD")
                    var ancestor = child.parentConversationId
                    var depth = 0
                    while (ancestor != null && ancestor != record.id && depth++ < 3) {
                        ancestor = read(ancestor)?.parentConversationId
                    }
                    checkStorage(ancestor == record.id, "E_AUDIT_CHILD")
                }
                val previous = auditUnlocked(frozen.conversationId, frozen.runId)
                val same = previous.singleOrNull { it.sequence == frozen.sequence }
                checkStorage(same == null || same == frozen, "E_IMMUTABLE_AUDIT")
                checkStorage(same != null || frozen.sequence == previous.size, "E_AUDIT_SEQUENCE")
                connection
                    .prepareStatement(
                        "INSERT OR IGNORE INTO conversation_audit(conversation_id, run_id, sequence, record) VALUES (?, ?, ?, ?)",
                    ).use { statement ->
                        statement.setString(1, frozen.conversationId)
                        statement.setString(2, frozen.runId)
                        statement.setInt(3, frozen.sequence)
                        statement.setString(4, encoded)
                        statement.executeUpdate()
                    }
            }
        }

    override fun audit(
        conversationId: String,
        runId: String?,
    ): List<ExecutionAuditEvent> =
        guarded {
            validateId(conversationId)
            runId?.let(::validateId)
            auditUnlocked(conversationId, runId)
        }

    private fun auditUnlocked(
        conversationId: String,
        runId: String?,
    ): List<ExecutionAuditEvent> {
        val query =
            if (runId == null) {
                "SELECT length(CAST(record AS BLOB)), record FROM conversation_audit WHERE conversation_id = ? ORDER BY run_id, sequence LIMIT 4096"
            } else {
                "SELECT length(CAST(record AS BLOB)), record FROM conversation_audit WHERE conversation_id = ? AND run_id = ? ORDER BY sequence LIMIT 1024"
            }
        return connection.prepareStatement(query).use { statement ->
            statement.setString(1, conversationId)
            if (runId != null) statement.setString(2, runId)
            statement.executeQuery().use { rows ->
                buildList {
                    while (rows.next()) {
                        checkStorage(rows.getInt(1) <= 65_536, "E_AUDIT_LIMIT")
                        val event = PlatformJson.codec.decodeFromString<ExecutionAuditEvent>(rows.getString(2))
                        checkStorage(event.conversationId == conversationId && (runId == null || event.runId == runId), "E_STORAGE_CORRUPT")
                        add(event)
                    }
                }
            }
        }
    }

    override fun delete(conversationId: String): Boolean =
        guarded {
            validateId(conversationId)
            transaction { deleteUnlocked(conversationId) }
        }

    override fun deleteChatConversation(conversationId: String): Boolean =
        guarded {
            validateId(conversationId)
            transaction {
                val root = read(conversationId) ?: return@transaction false
                val records =
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT id, length(CAST(record AS BLOB)), record FROM conversation").use { rows ->
                            buildList {
                                while (rows.next()) {
                                    checkStorage(size < MAX_CONVERSATIONS, "E_CONVERSATION_LIMIT")
                                    checkStorage(rows.getInt(2) <= MAX_RECORD_BYTES, "E_CONVERSATION_SIZE")
                                    add(decodeRecord(rows.getString(1), rows.getString(3)))
                                }
                            }
                        }
                    }
                val children = records.groupBy { it.parentConversationId }
                val planned = mutableListOf(root)
                val visited = mutableSetOf(root.id)
                var index = 0
                // Iterative breadth-first planning is bounded by the repository record limit.
                while (index < planned.size) {
                    val parent = planned[index++]
                    for (child in children[parent.id].orEmpty()) {
                        checkStorage(child.analysisSessionId != parent.analysisSessionId, "E_CONVERSATION_HAS_CHILDREN")
                        checkStorage(visited.add(child.id), "E_STORAGE_CORRUPT")
                        checkStorage(planned.size < MAX_CONVERSATIONS, "E_CONVERSATION_LIMIT")
                        planned.add(child)
                    }
                }
                checkStorage(planned.all { record -> record.turns.all { it.status.terminal } }, "E_CONVERSATION_ACTIVE")
                planned.asReversed().forEach { record ->
                    checkStorage(deleteUnlocked(record.id), "E_STORAGE_CORRUPT")
                }
                true
            }
        }

    private fun deleteUnlocked(conversationId: String): Boolean {
        val record = read(conversationId) ?: return false
        checkStorage(record.turns.all { it.status.terminal }, "E_CONVERSATION_ACTIVE")
        checkStorage(
            !connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id, record FROM conversation").use { rows ->
                    var hasChild = false
                    while (rows.next()) {
                        val other = decodeRecord(rows.getString(1), rows.getString(2))
                        if (other.parentConversationId == conversationId) hasChild = true
                    }
                    hasChild
                }
            },
            "E_CONVERSATION_HAS_CHILDREN",
        )
        return connection.prepareStatement("DELETE FROM conversation WHERE id = ?").use { statement ->
            statement.setString(1, conversationId)
            statement.executeUpdate() == 1
        }
    }

    override fun prune(
        maxConversations: Int,
        olderThanEpochMillis: Long,
    ): Int =
        guarded {
            checkStorage(maxConversations in 0..MAX_CONVERSATIONS && olderThanEpochMillis >= 0, "E_RETENTION_POLICY")
            transaction {
                val records =
                    connection.createStatement().use { statement ->
                        statement.executeQuery("SELECT id, updated_at FROM conversation_meta ORDER BY updated_at DESC, id").use { rows ->
                            buildList {
                                while (rows.next()) {
                                    checkStorage(size < MAX_CONVERSATIONS, "E_CONVERSATION_LIMIT")
                                    add(rows.getString(1) to rows.getLong(2))
                                }
                            }
                        }
                    }
                var removed = 0
                records.forEachIndexed { index, (id, updatedAt) ->
                    if ((index >= maxConversations || updatedAt < olderThanEpochMillis) &&
                        read(id)?.turns?.all { it.status.terminal } == true
                    ) {
                        try {
                            if (deleteUnlocked(id)) removed++
                        } catch (failure: ConversationStorageException) {
                            if (failure.code != "E_CONVERSATION_HAS_CHILDREN") throw failure
                        }
                    }
                }
                removed
            }
        }

    override fun close(): Unit =
        synchronized(lock) {
            try {
                connection.close()
            } catch (_: Exception) {
                failStorage("E_STORAGE_IO")
            }
        }

    private fun read(id: String): ConversationRecord? =
        connection.prepareStatement("SELECT length(CAST(record AS BLOB)), record FROM conversation WHERE id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { rows ->
                if (rows.next()) {
                    checkStorage(rows.getInt(1) <= MAX_RECORD_BYTES, "E_CONVERSATION_SIZE")
                    decodeRecord(id, rows.getString(2))
                } else {
                    null
                }
            }
        }

    private fun write(
        id: String,
        encoded: String,
    ) {
        connection
            .prepareStatement(
                "INSERT INTO conversation(id, record) VALUES (?, ?) ON CONFLICT(id) DO UPDATE SET record=excluded.record",
            ).use { statement ->
                statement.setString(1, id)
                statement.setString(2, encoded)
                statement.executeUpdate()
            }
        connection
            .prepareStatement(
                "INSERT INTO conversation_meta(id, updated_at) VALUES (?, ?) ON CONFLICT(id) DO UPDATE SET updated_at=excluded.updated_at",
            ).use { statement ->
                statement.setString(1, id)
                statement.setLong(2, System.currentTimeMillis())
                statement.executeUpdate()
            }
    }

    private fun countRecords(): Int =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM conversation").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }

    private fun <T> transaction(block: () -> T): T {
        // Acquire the SQLite writer lock before reading, including across repository instances.
        connection.createStatement().use { it.execute("BEGIN IMMEDIATE") }
        try {
            val result = block()
            connection.createStatement().use { it.execute("COMMIT") }
            return result
        } catch (failure: Exception) {
            try {
                connection.createStatement().use { it.execute("ROLLBACK") }
            } catch (_: Exception) {
                // The original failure is sanitized by guarded; never expose driver diagnostics.
            }
            throw failure
        }
    }

    private fun <T> guarded(block: () -> T): T =
        synchronized(lock) {
            try {
                verifyDatabasePaths(database)
                checkStorage(!connection.isClosed, "E_STORAGE_CLOSED")
                block()
            } catch (failure: ConversationStorageException) {
                throw failure
            } catch (_: Exception) {
                failStorage("E_STORAGE_IO")
            }
        }

    private companion object {
        const val MAX_RECORD_BYTES = 1_048_576
        const val MAX_PAGE_SIZE = 1000
        const val MAX_CONVERSATIONS = 10_000
        val PRIVATE_DIRECTORY = PosixFilePermissions.fromString("rwx------")
        val PRIVATE_FILE = PosixFilePermissions.fromString("rw-------")

        fun openDatabase(path: Path): Connection {
            var opened: Connection? = null
            try {
                prepareDatabasePath(path)
                opened = DriverManager.getConnection("jdbc:sqlite:$path")
                opened.createStatement().use { statement ->
                    statement.execute("PRAGMA journal_mode = DELETE")
                    statement.execute("PRAGMA busy_timeout = 5000")
                    statement.execute("PRAGMA foreign_keys = ON")
                    val version =
                        statement.executeQuery("PRAGMA user_version").use { rows ->
                            rows.next()
                            rows.getInt(1)
                        }
                    checkStorage(version in 0..1, "E_STORAGE_SCHEMA")
                    statement.execute("BEGIN IMMEDIATE")
                    statement.execute(
                        "CREATE TABLE IF NOT EXISTS conversation(id TEXT PRIMARY KEY NOT NULL, record TEXT NOT NULL)",
                    )
                    statement.execute(
                        "CREATE TABLE IF NOT EXISTS conversation_meta(id TEXT PRIMARY KEY REFERENCES conversation(id) ON DELETE CASCADE, updated_at INTEGER NOT NULL)",
                    )
                    statement.execute(
                        "CREATE TABLE IF NOT EXISTS conversation_result(conversation_id TEXT NOT NULL REFERENCES conversation(id) ON DELETE CASCADE, run_id TEXT NOT NULL, record TEXT NOT NULL, PRIMARY KEY(conversation_id, run_id))",
                    )
                    statement.execute(
                        "CREATE TABLE IF NOT EXISTS conversation_audit(conversation_id TEXT NOT NULL REFERENCES conversation(id) ON DELETE CASCADE, run_id TEXT NOT NULL, sequence INTEGER NOT NULL, record TEXT NOT NULL, PRIMARY KEY(conversation_id, run_id, sequence))",
                    )
                    statement.execute(
                        "CREATE TABLE IF NOT EXISTS conversation_chat_turn(conversation_id TEXT NOT NULL REFERENCES conversation(id) ON DELETE CASCADE, run_id TEXT NOT NULL, generation INTEGER NOT NULL, question TEXT, created_at INTEGER NOT NULL, parent_run_id TEXT, PRIMARY KEY(conversation_id, run_id))",
                    )
                    statement.execute(
                        "CREATE TABLE IF NOT EXISTS conversation_chat_meta(id TEXT PRIMARY KEY NOT NULL REFERENCES conversation(id) ON DELETE CASCADE, title TEXT, pinned INTEGER NOT NULL DEFAULT 0, archived INTEGER NOT NULL DEFAULT 0)",
                    )
                    val hasArchive =
                        statement.executeQuery("PRAGMA table_info(conversation_chat_meta)").use { rows ->
                            var found = false
                            while (rows.next()) if (rows.getString("name") == "archived") found = true
                            found
                        }
                    if (!hasArchive) statement.execute("ALTER TABLE conversation_chat_meta ADD COLUMN archived INTEGER NOT NULL DEFAULT 0")
                    val hasParentRun =
                        statement.executeQuery("PRAGMA table_info(conversation_chat_turn)").use { rows ->
                            var found = false
                            while (rows.next()) if (rows.getString("name") == "parent_run_id") found = true
                            found
                        }
                    if (!hasParentRun) statement.execute("ALTER TABLE conversation_chat_turn ADD COLUMN parent_run_id TEXT")
                    statement.execute("INSERT OR IGNORE INTO conversation_meta SELECT id, 0 FROM conversation")
                    statement.execute("PRAGMA user_version = 1")
                    statement.execute("COMMIT")
                }
                return opened
            } catch (failure: Exception) {
                try {
                    opened?.close()
                } catch (_: Exception) {
                    // Do not attach SQL errors, raw payloads or path details to a public exception.
                }
                if (failure is ConversationStorageException) throw failure
                failStorage("E_STORAGE_OPEN")
            }
        }

        fun prepareDatabasePath(path: Path) {
            val parent = path.parent ?: failStorage("E_STORAGE_PATH")
            ensurePrivateDirectories(parent)
            verifyDatabasePaths(path)
            if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                if (hasPosix(parent)) {
                    Files.createFile(path, PosixFilePermissions.asFileAttribute(PRIVATE_FILE))
                } else {
                    Files.createFile(path)
                }
            }
            checkStorage(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "E_STORAGE_PATH")
            if (hasPosix(path)) {
                checkStorage(Files.getPosixFilePermissions(path) == PRIVATE_FILE, "E_STORAGE_PERMISSIONS")
            }
        }

        fun ensurePrivateDirectories(parent: Path) {
            var current = parent.root ?: failStorage("E_STORAGE_PATH")
            parent.forEach { component ->
                current = current.resolve(component)
                checkStorage(!Files.isSymbolicLink(current), "E_STORAGE_SYMLINK")
                if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                    if (hasPosix(current.parent)) {
                        Files.createDirectory(current, PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY))
                    } else {
                        Files.createDirectory(current)
                    }
                }
                checkStorage(Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS), "E_STORAGE_PATH")
            }
            // Never chmod an arbitrary pre-existing parent (for example /tmp) as a side effect.
            if (hasPosix(parent)) {
                checkStorage(Files.getPosixFilePermissions(parent) == PRIVATE_DIRECTORY, "E_STORAGE_PERMISSIONS")
            }
        }

        fun verifyDatabasePaths(path: Path) {
            var current = path.root ?: failStorage("E_STORAGE_PATH")
            path.forEach { component ->
                current = current.resolve(component)
                checkStorage(!Files.isSymbolicLink(current), "E_STORAGE_SYMLINK")
            }
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                checkStorage(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "E_STORAGE_PATH")
                if (hasPosix(path)) {
                    checkStorage(Files.getPosixFilePermissions(path) == PRIVATE_FILE, "E_STORAGE_PERMISSIONS")
                }
            }
            listOf("-journal", "-wal", "-shm").forEach { suffix ->
                val sidecar = path.resolveSibling("${path.fileName}$suffix")
                checkStorage(!Files.isSymbolicLink(sidecar), "E_STORAGE_SYMLINK")
            }
        }

        fun hasPosix(path: Path): Boolean =
            Files.getFileAttributeView(path, PosixFileAttributeView::class.java, LinkOption.NOFOLLOW_LINKS) != null

        fun encodeRecord(record: ConversationRecord): String {
            val encoded = PlatformJson.codec.encodeToString(record)
            checkStorage(encoded.toByteArray(Charsets.UTF_8).size <= MAX_RECORD_BYTES, "E_CONVERSATION_SIZE")
            // Round-trip also checks init constraints if callers supplied mutable collections.
            decodeRecord(record.id, encoded)
            return encoded
        }

        fun decodeRecord(
            id: String,
            encoded: String,
        ): ConversationRecord {
            try {
                checkStorage(encoded.toByteArray(Charsets.UTF_8).size <= MAX_RECORD_BYTES, "E_CONVERSATION_SIZE")
                val record = PlatformJson.codec.decodeFromString<ConversationRecord>(encoded)
                checkStorage(record.id == id, "E_STORAGE_CORRUPT")
                validateTurns(record.turns)
                return record
            } catch (failure: ConversationStorageException) {
                throw failure
            } catch (_: Exception) {
                failStorage("E_STORAGE_CORRUPT")
            }
        }

        fun validateTurns(turns: List<ConversationTurn>) {
            checkStorage(turns.zipWithNext().all { (older, newer) -> older.generation < newer.generation }, "E_RUN_GENERATION")
            checkStorage(turns.dropLast(1).all { it.status.terminal }, "E_RUN_STATE")
            turns.forEach { turn ->
                checkStorage(turn.evidenceIds.distinct().size == turn.evidenceIds.size, "E_RUN_REFERENCES")
                checkStorage(turn.candidateIds.distinct().size == turn.candidateIds.size, "E_RUN_REFERENCES")
                checkStorage(turn.findingIds.distinct().size == turn.findingIds.size, "E_RUN_REFERENCES")
            }
        }

        fun validateUpdate(
            previous: ConversationRecord,
            next: ConversationRecord,
        ) {
            checkStorage(
                previous.analysisSessionId == next.analysisSessionId &&
                    previous.contextHash == next.contextHash &&
                    previous.parentConversationId == next.parentConversationId,
                "E_IMMUTABLE_CONTEXT",
            )
            checkStorage(next.turns.size >= previous.turns.size, "E_STALE_CONVERSATION")
            previous.turns.forEachIndexed { index, older ->
                val newer = next.turns[index]
                checkStorage(
                    older.runId == newer.runId &&
                        older.generation == newer.generation &&
                        older.evidenceIds == newer.evidenceIds &&
                        older.candidateIds == newer.candidateIds,
                    "E_STALE_CONVERSATION",
                )
                if (older.status.terminal || older.status == newer.status || index < previous.turns.lastIndex) {
                    checkStorage(older == newer, "E_STALE_CONVERSATION")
                } else {
                    checkStorage(validTransition(older.status, newer.status), "E_RUN_STATE")
                }
            }
        }

        fun validTransition(
            previous: RunStatus,
            next: RunStatus,
        ): Boolean {
            if (previous == next || next.terminal) return true
            val activeStates = listOf(RunStatus.QUEUED, RunStatus.PREPARING, RunStatus.AWAITING_APPROVAL, RunStatus.RUNNING)
            return activeStates.indexOf(next) > activeStates.indexOf(previous)
        }

        fun validateId(id: String) {
            checkStorage(id.length in 1..128, "E_CONVERSATION_ID")
        }
    }
}

private fun checkStorage(
    condition: Boolean,
    code: String,
) {
    if (!condition) failStorage(code)
}

private fun failStorage(code: String): Nothing = throw ConversationStorageException(code)

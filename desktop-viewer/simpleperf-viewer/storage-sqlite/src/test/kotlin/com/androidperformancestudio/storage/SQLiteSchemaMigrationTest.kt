package com.androidperformancestudio.storage

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.Statement
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class SQLiteSchemaMigrationTest {
    @Test
    fun `v1 database migrates to v2 without changing sample totals`() =
        withVersionOneDatabase { path ->
            SQLiteSampleStore.open(path).use { store ->
                assertEquals(2L, store.sampleCount())
                assertEquals(2, store.schemaVersion())
            }
            assertEquals(2, pragmaUserVersion(path))
        }

    @Test
    fun `failed migration rolls back every v2 table`() =
        withInjectedMigrationFailure { connection ->
            assertFailsWith<SQLException> { SQLiteSchema.migrate(connection) }

            assertEquals(1, connection.userVersionForTest())
            V2_TABLES.forEach { table ->
                assertFalse(connection.tableExistsForTest(table), "migration left $table behind")
            }
            assertEquals(setOf("process_id", "name"), connection.columnsForTest("process"))
            assertEquals(
                setOf("thread_id", "process_id", "name"),
                connection.columnsForTest("thread"),
            )
            assertFalse("cpu_core" in connection.columnsForTest("sample"))
        }

    private fun withVersionOneDatabase(block: (Path) -> Unit) {
        val database = Files.createTempFile("aps-schema-v1-", ".sqlite")
        try {
            createVersionOneDatabase(database)
            block(database)
        } finally {
            deleteDatabase(database)
        }
    }

    private fun withInjectedMigrationFailure(block: (Connection) -> Unit) =
        withVersionOneDatabase { database ->
            DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
                block(FailingMigrationConnection(connection))
            }
        }

    private fun createVersionOneDatabase(database: Path) {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement ->
                VERSION_ONE_STATEMENTS.forEach(statement::execute)
            }
        }
    }

    private fun pragmaUserVersion(database: Path): Int =
        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use(Connection::userVersionForTest)

    private fun deleteDatabase(database: Path) {
        database.deleteIfExists()
        database.resolveSibling(database.fileName.toString() + "-shm").deleteIfExists()
        database.resolveSibling(database.fileName.toString() + "-wal").deleteIfExists()
    }

    private class FailingMigrationConnection(
        private val delegate: Connection,
    ) : Connection by delegate {
        override fun createStatement(): Statement = FailingMigrationStatement(delegate.createStatement())
    }

    private class FailingMigrationStatement(
        private val delegate: Statement,
    ) : Statement by delegate {
        override fun execute(sql: String): Boolean {
            if (sql == FAILING_STATEMENT) throw SQLException("injected v2 migration failure")
            return delegate.execute(sql)
        }
    }

    private companion object {
        const val FAILING_STATEMENT =
            "CREATE TABLE profile_counter (counter_id INTEGER PRIMARY KEY, source_id TEXT NOT NULL, " +
                "timestamp_nanos INTEGER NOT NULL, name TEXT NOT NULL, unit TEXT NOT NULL, value REAL NOT NULL)"

        val V2_TABLES =
            listOf(
                "profile_source",
                "profile_marker",
                "profile_counter",
                "profile_slice",
                "profile_screenshot",
                "clock_alignment",
            )

        val VERSION_ONE_STATEMENTS =
            listOf(
                "CREATE TABLE process (process_id INTEGER PRIMARY KEY, name TEXT)",
                "CREATE TABLE thread (thread_id INTEGER PRIMARY KEY, process_id INTEGER NOT NULL, " +
                    "name TEXT NOT NULL)",
                "CREATE TABLE event (event_id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)",
                "CREATE TABLE sample (sample_id INTEGER PRIMARY KEY, timestamp_nanos INTEGER NOT NULL, " +
                    "process_id INTEGER NOT NULL, thread_id INTEGER NOT NULL, event_id INTEGER NOT NULL, " +
                    "event_count INTEGER NOT NULL, leaf_callsite_id INTEGER, " +
                    "has_unknown_symbol INTEGER NOT NULL DEFAULT 0, empty_stack INTEGER NOT NULL DEFAULT 0, " +
                    "unwind_error_code TEXT, unwind_raw_code INTEGER, unwind_address INTEGER)",
                "INSERT INTO process(process_id, name) VALUES (100, 'example')",
                "INSERT INTO thread(thread_id, process_id, name) VALUES (101, 100, 'main')",
                "INSERT INTO event(event_id, name) VALUES (1, 'cpu-cycles')",
                "INSERT INTO sample(sample_id, timestamp_nanos, process_id, thread_id, event_id, event_count) " +
                    "VALUES (1, 10, 100, 101, 1, 3)",
                "INSERT INTO sample(sample_id, timestamp_nanos, process_id, thread_id, event_id, event_count) " +
                    "VALUES (2, 20, 100, 101, 1, 5)",
                "PRAGMA user_version=1",
            )
    }
}

private fun Connection.userVersionForTest(): Int =
    createStatement().use { statement ->
        statement.executeQuery("PRAGMA user_version").use { result ->
            check(result.next())
            result.getInt(1)
        }
    }

private fun Connection.tableExistsForTest(name: String): Boolean =
    prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?").use { statement ->
        statement.setString(1, name)
        statement.executeQuery().use { it.next() }
    }

private fun Connection.columnsForTest(table: String): Set<String> =
    createStatement().use { statement ->
        statement.executeQuery("PRAGMA table_info($table)").use { result ->
            buildSet {
                while (result.next()) add(result.getString("name"))
            }
        }
    }

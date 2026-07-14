package com.androidperformancestudio.storage

import com.androidperformancestudio.model.CanonicalProfileRecord
import com.androidperformancestudio.model.ProfileClockDomain
import com.androidperformancestudio.model.ProfileProcessFact
import com.androidperformancestudio.model.ProfileProcessKey
import com.androidperformancestudio.model.ProfileSourceFact
import com.androidperformancestudio.model.ProfileSourceId
import com.androidperformancestudio.model.ProfileSourceKind
import com.androidperformancestudio.model.ProfileThreadFact
import com.androidperformancestudio.model.ProfileThreadKey
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
import kotlin.test.assertTrue

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
    fun `colliding canonical thread does not rewrite migrated v1 thread summaries`() =
        withVersionOneDatabase { path ->
            SQLiteSampleStore.open(path).use { store ->
                val before = store.threads()
                val sourceId = ProfileSourceId("perfetto")
                val process = ProfileProcessKey(sourceId, 200)
                val thread = ProfileThreadKey(sourceId, process, 101)

                store.importCanonicalRecords(
                    sequenceOf(
                        CanonicalProfileRecord.Source(
                            ProfileSourceFact(
                                sourceId,
                                ProfileSourceKind.PERFETTO,
                                ProfileClockDomain("boottime"),
                                null,
                                null,
                            ),
                        ),
                        CanonicalProfileRecord.Process(
                            ProfileProcessFact(process, "canonical-process", null, null),
                        ),
                        CanonicalProfileRecord.Thread(
                            ProfileThreadFact(thread, "canonical-thread", null, null),
                        ),
                    ),
                )

                assertEquals(before, store.threads())
                assertEquals(listOf(ThreadSummary(100, 101, "main", 2, 8)), store.threads())
            }
        }

    @Test
    fun `v2 migration adds source-qualified identities and lossless time provenance`() =
        withVersionOneDatabase { path ->
            SQLiteSampleStore.open(path).use { store ->
                val connection = store.connection
                assertTrue(connection.tableExistsForTest("profile_process"))
                assertTrue(connection.tableExistsForTest("profile_thread"))
                assertEquals(
                    setOf(
                        "process_row_id",
                        "source_id",
                        "process_id",
                        "name",
                        "start_nanos",
                        "start_clock_domain",
                        "start_error_bound_nanos",
                        "end_nanos",
                        "end_clock_domain",
                        "end_error_bound_nanos",
                    ),
                    connection.columnsForTest("profile_process"),
                )
                assertEquals(
                    setOf(
                        "thread_row_id",
                        "source_id",
                        "process_row_id",
                        "thread_id",
                        "name",
                        "start_nanos",
                        "start_clock_domain",
                        "start_error_bound_nanos",
                        "end_nanos",
                        "end_clock_domain",
                        "end_error_bound_nanos",
                    ),
                    connection.columnsForTest("profile_thread"),
                )
                assertTrue(
                    connection.columnsForTest("sample").containsAll(
                        setOf(
                            "source_id",
                            "process_row_id",
                            "thread_row_id",
                            "clock_domain",
                            "time_error_bound_nanos",
                        ),
                    ),
                )
                assertTimeColumns(connection, "profile_marker", interval = true)
                assertTimeColumns(connection, "profile_counter", interval = false)
                assertTimeColumns(connection, "profile_slice", interval = true)
                assertTimeColumns(connection, "profile_screenshot", interval = false)
            }
        }

    @Test
    fun `v2 identity constraints reject duplicate source-qualified keys but allow reused thread ids`() =
        withVersionOneDatabase { path ->
            SQLiteSampleStore.open(path).use { store ->
                val connection = store.connection
                connection.createStatement().use { statement ->
                    statement.executeUpdate(
                        "INSERT INTO profile_source(source_id, kind, clock_domain) " +
                            "VALUES ('simpleperf', 'SIMPLEPERF', 'monotonic')",
                    )
                    statement.executeUpdate(
                        "INSERT INTO profile_process(source_id, process_id) VALUES ('simpleperf', 11)",
                    )
                    assertFailsWith<SQLException> {
                        statement.executeUpdate(
                            "INSERT INTO profile_process(source_id, process_id) VALUES ('simpleperf', 11)",
                        )
                    }
                    statement.executeUpdate(
                        "INSERT INTO profile_process(source_id, process_id) VALUES ('simpleperf', 12)",
                    )
                    statement.executeUpdate(
                        "INSERT INTO profile_thread(source_id, process_row_id, thread_id, name) " +
                            "VALUES ('simpleperf', 1, 22, 'first')",
                    )
                    statement.executeUpdate(
                        "INSERT INTO profile_thread(source_id, process_row_id, thread_id, name) " +
                            "VALUES ('simpleperf', 2, 22, 'second')",
                    )
                    assertFailsWith<SQLException> {
                        statement.executeUpdate(
                            "INSERT INTO profile_thread(source_id, process_row_id, thread_id, name) " +
                                "VALUES ('simpleperf', 1, 22, 'duplicate')",
                        )
                    }
                }
                assertEquals(2L, connection.singleLong("SELECT COUNT(*) FROM profile_thread"))
            }
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
                "timestamp_nanos INTEGER NOT NULL, clock_domain TEXT NOT NULL, " +
                "time_error_bound_nanos INTEGER NOT NULL, name TEXT NOT NULL, unit TEXT NOT NULL, " +
                "value REAL NOT NULL, FOREIGN KEY(source_id) REFERENCES profile_source(source_id))"

        val V2_TABLES =
            listOf(
                "profile_source",
                "profile_process",
                "profile_thread",
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

private fun assertTimeColumns(
    connection: Connection,
    table: String,
    interval: Boolean,
) {
    val columns = connection.columnsForTest(table)
    if (interval) {
        assertTrue(
            columns.containsAll(
                setOf(
                    "start_nanos",
                    "start_clock_domain",
                    "start_error_bound_nanos",
                    "end_nanos",
                    "end_clock_domain",
                    "end_error_bound_nanos",
                ),
            ),
        )
    } else {
        assertTrue(columns.containsAll(setOf("timestamp_nanos", "clock_domain", "time_error_bound_nanos")))
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

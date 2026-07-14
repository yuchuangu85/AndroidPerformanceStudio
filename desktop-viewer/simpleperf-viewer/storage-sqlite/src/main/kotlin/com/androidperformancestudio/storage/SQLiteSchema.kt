package com.androidperformancestudio.storage

import java.sql.Connection
import java.sql.SQLException

internal object SQLiteSchema {
    const val VERSION = 2

    fun migrate(connection: Connection) {
        val version = connection.userVersion()
        require(version <= VERSION) { "Database schema version $version is newer than supported version $VERSION" }
        if (version == 0) migrateToVersionOne(connection)
        if (connection.userVersion() == 1) migrateToVersionTwo(connection)
    }

    private fun migrateToVersionOne(connection: Connection) {
        connection.autoCommit = false
        try {
            renameLegacySampleTable(connection)
            createVersionOne(connection)
            migrateLegacySamples(connection)
            connection.createStatement().use { it.execute("PRAGMA user_version=1") }
            connection.commit()
        } catch (exception: SQLException) {
            connection.rollback()
            throw exception
        } finally {
            connection.autoCommit = true
        }
    }

    private fun migrateToVersionTwo(connection: Connection) =
        inTransaction(connection) {
            SQLiteSchemaV2.statements.forEach { sql ->
                connection.createStatement().use { it.execute(sql) }
            }
            connection.createStatement().use { it.execute("PRAGMA user_version=2") }
        }

    private inline fun inTransaction(
        connection: Connection,
        block: () -> Unit,
    ) {
        connection.autoCommit = false
        try {
            block()
            connection.commit()
        } catch (exception: SQLException) {
            connection.rollback()
            throw exception
        } finally {
            connection.autoCommit = true
        }
    }

    private fun renameLegacySampleTable(connection: Connection) {
        if (!connection.tableExists("sample")) return
        val columns = connection.columns("sample")
        if ("event_id" !in columns) {
            connection.createStatement().use { it.execute("ALTER TABLE sample RENAME TO legacy_sample") }
        }
    }

    private fun createVersionOne(connection: Connection) {
        connection.createStatement().use { statement ->
            SCHEMA_STATEMENTS.forEach(statement::execute)
        }
    }

    private fun migrateLegacySamples(connection: Connection) {
        if (!connection.tableExists("legacy_sample")) return
        connection.createStatement().use { statement ->
            LEGACY_MIGRATION_STATEMENTS.forEach(statement::execute)
            statement.execute("DROP TABLE legacy_sample")
        }
    }

    private fun Connection.userVersion(): Int =
        createStatement().use { statement ->
            statement.executeQuery("PRAGMA user_version").use { result ->
                check(result.next())
                result.getInt(1)
            }
        }

    private fun Connection.tableExists(name: String): Boolean =
        prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND name=?").use { statement ->
            statement.setString(1, name)
            statement.executeQuery().use { it.next() }
        }

    private fun Connection.columns(table: String): Set<String> =
        createStatement().use { statement ->
            statement.executeQuery("PRAGMA table_info($table)").use { result ->
                buildSet {
                    while (result.next()) add(result.getString("name"))
                }
            }
        }

    private val SCHEMA_STATEMENTS =
        listOf(
            "CREATE TABLE IF NOT EXISTS process (" +
                "process_id INTEGER PRIMARY KEY, name TEXT)",
            "CREATE TABLE IF NOT EXISTS thread (" +
                "thread_id INTEGER PRIMARY KEY, process_id INTEGER NOT NULL, name TEXT NOT NULL, " +
                "FOREIGN KEY(process_id) REFERENCES process(process_id))",
            "CREATE TABLE IF NOT EXISTS event (" +
                "event_id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)",
            "CREATE TABLE IF NOT EXISTS file (" +
                "file_id INTEGER PRIMARY KEY, path TEXT NOT NULL)",
            "CREATE TABLE IF NOT EXISTS symbol (" +
                "symbol_id INTEGER PRIMARY KEY, file_id INTEGER NOT NULL, source_symbol_id INTEGER NOT NULL, " +
                "name TEXT NOT NULL, mangled_name TEXT, " +
                "UNIQUE(file_id, source_symbol_id, name), " +
                "FOREIGN KEY(file_id) REFERENCES file(file_id))",
            "CREATE TABLE IF NOT EXISTS frame (" +
                "frame_id INTEGER PRIMARY KEY, virtual_address INTEGER NOT NULL, file_id INTEGER NOT NULL, " +
                "symbol_id INTEGER NOT NULL, execution_type TEXT NOT NULL, " +
                "UNIQUE(virtual_address, file_id, symbol_id, execution_type), " +
                "FOREIGN KEY(file_id) REFERENCES file(file_id), " +
                "FOREIGN KEY(symbol_id) REFERENCES symbol(symbol_id))",
            "CREATE TABLE IF NOT EXISTS callsite (" +
                "callsite_id INTEGER PRIMARY KEY, parent_id INTEGER, frame_id INTEGER NOT NULL, " +
                "FOREIGN KEY(parent_id) REFERENCES callsite(callsite_id), " +
                "FOREIGN KEY(frame_id) REFERENCES frame(frame_id))",
            "CREATE UNIQUE INDEX IF NOT EXISTS callsite_parent_frame " +
                "ON callsite(IFNULL(parent_id, 0), frame_id)",
            "CREATE TABLE IF NOT EXISTS sample (" +
                "sample_id INTEGER PRIMARY KEY, timestamp_nanos INTEGER NOT NULL, process_id INTEGER NOT NULL, " +
                "thread_id INTEGER NOT NULL, event_id INTEGER NOT NULL, event_count INTEGER NOT NULL, " +
                "leaf_callsite_id INTEGER, has_unknown_symbol INTEGER NOT NULL DEFAULT 0, " +
                "empty_stack INTEGER NOT NULL DEFAULT 0, unwind_error_code TEXT, unwind_raw_code INTEGER, " +
                "unwind_address INTEGER, FOREIGN KEY(process_id) REFERENCES process(process_id), " +
                "FOREIGN KEY(thread_id) REFERENCES thread(thread_id), " +
                "FOREIGN KEY(event_id) REFERENCES event(event_id), " +
                "FOREIGN KEY(leaf_callsite_id) REFERENCES callsite(callsite_id))",
            "CREATE INDEX IF NOT EXISTS sample_thread_time ON sample(thread_id, timestamp_nanos)",
            "CREATE INDEX IF NOT EXISTS sample_event_time ON sample(event_id, timestamp_nanos)",
            "CREATE INDEX IF NOT EXISTS sample_callsite ON sample(leaf_callsite_id)",
            "CREATE TABLE IF NOT EXISTS lost_situation (" +
                "lost_id INTEGER PRIMARY KEY, sample_count INTEGER NOT NULL, lost_count INTEGER NOT NULL)",
            "CREATE TABLE IF NOT EXISTS unknown_record (unknown_id INTEGER PRIMARY KEY)",
            "CREATE TABLE IF NOT EXISTS context_switch (" +
                "context_switch_id INTEGER PRIMARY KEY, thread_id INTEGER NOT NULL, " +
                "timestamp_nanos INTEGER NOT NULL, " +
                "switched_on_cpu INTEGER NOT NULL)",
            "CREATE INDEX IF NOT EXISTS context_switch_thread_time " +
                "ON context_switch(thread_id, timestamp_nanos)",
            "CREATE TABLE IF NOT EXISTS profile_metadata (" +
                "metadata_id INTEGER PRIMARY KEY CHECK(metadata_id = 1), event_types TEXT NOT NULL, " +
                "app_package_name TEXT, app_type TEXT, android_sdk_version TEXT, android_build_type TEXT, " +
                "trace_off_cpu INTEGER NOT NULL)",
        )

    private val LEGACY_MIGRATION_STATEMENTS =
        listOf(
            "INSERT OR IGNORE INTO process(process_id) SELECT DISTINCT process_id FROM legacy_sample",
            "INSERT OR IGNORE INTO thread(thread_id, process_id, name) " +
                "SELECT DISTINCT thread_id, process_id, '<unknown-thread:' || thread_id || '>' FROM legacy_sample",
            "INSERT OR IGNORE INTO event(name) SELECT DISTINCT event_type FROM legacy_sample",
            "INSERT OR IGNORE INTO file(file_id, path) VALUES (-1, '<legacy>')",
            "INSERT OR IGNORE INTO symbol(file_id, source_symbol_id, name) " +
                "SELECT -1, -1, symbol_name FROM legacy_sample GROUP BY symbol_name",
            "INSERT OR IGNORE INTO frame(virtual_address, file_id, symbol_id, execution_type) " +
                "SELECT 0, -1, symbol_id, 'NATIVE' FROM symbol WHERE file_id = -1",
            "INSERT OR IGNORE INTO callsite(parent_id, frame_id) " +
                "SELECT NULL, frame_id FROM frame WHERE file_id = -1",
            "INSERT INTO sample(timestamp_nanos, process_id, thread_id, event_id, event_count, leaf_callsite_id) " +
                "SELECT l.timestamp_nanos, l.process_id, l.thread_id, e.event_id, l.event_count, c.callsite_id " +
                "FROM legacy_sample l JOIN event e ON e.name = l.event_type " +
                "JOIN symbol sy ON sy.file_id = -1 AND sy.name = l.symbol_name " +
                "JOIN frame f ON f.file_id = -1 AND f.symbol_id = sy.symbol_id " +
                "JOIN callsite c ON c.parent_id IS NULL AND c.frame_id = f.frame_id",
        )
}

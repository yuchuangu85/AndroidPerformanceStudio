@file:Suppress("MaxLineLength")

package com.androidperformancestudio.storage

import com.androidperformancestudio.model.ProfileSample
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SQLiteLegacyReadOnlyProjectionTest {
    @Test
    fun `read only open enforces writes off and projects v1 report data without mutation`() {
        Class.forName("org.sqlite.JDBC")
        val database = Files.createTempFile("aps-legacy-read-only-", ".sqlite")
        DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
            connection.createStatement().use { statement -> VERSION_ONE_STATEMENTS.forEach(statement::execute) }
        }
        val before = sha256(database)

        SQLiteSampleStore.openReadOnly(database).use { store ->
            assertEquals(1, store.schemaVersion())
            assertFailsWith<SQLException> {
                store.importSamples(sequenceOf(ProfileSample(30, 100, 101, "cpu-cycles", "blocked", 1)))
            }
            val snapshot = store.projectCore(ProfileProjectionRequest(timelineBucketCount = 2, topFunctionLimit = 10))
            assertEquals(2, snapshot.overview.sampleCount)
            assertEquals(listOf("leaf"), snapshot.topFunctions.map { it.symbolName })
            assertEquals(2, snapshot.timeline.size)
            assertEquals(1, snapshot.callTree.size)
        }

        assertEquals(before, sha256(database))
        assertEquals(false, Files.exists(database.resolveSibling(database.fileName.toString() + "-wal")))
        assertEquals(false, Files.exists(database.resolveSibling(database.fileName.toString() + "-shm")))
    }

    private fun sha256(path: Path): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(Files.readAllBytes(path))
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        val VERSION_ONE_STATEMENTS =
            listOf(
                "CREATE TABLE process (process_id INTEGER PRIMARY KEY, name TEXT)",
                "CREATE TABLE thread (thread_id INTEGER PRIMARY KEY, process_id INTEGER NOT NULL, name TEXT NOT NULL)",
                "CREATE TABLE event (event_id INTEGER PRIMARY KEY, name TEXT NOT NULL UNIQUE)",
                "CREATE TABLE file (file_id INTEGER PRIMARY KEY, path TEXT NOT NULL)",
                "CREATE TABLE symbol (symbol_id INTEGER PRIMARY KEY, file_id INTEGER NOT NULL, source_symbol_id INTEGER NOT NULL, name TEXT NOT NULL, mangled_name TEXT)",
                "CREATE TABLE frame (frame_id INTEGER PRIMARY KEY, virtual_address INTEGER NOT NULL, file_id INTEGER NOT NULL, symbol_id INTEGER NOT NULL, execution_type TEXT NOT NULL)",
                "CREATE TABLE callsite (callsite_id INTEGER PRIMARY KEY, parent_id INTEGER, frame_id INTEGER NOT NULL)",
                "CREATE TABLE sample (sample_id INTEGER PRIMARY KEY, timestamp_nanos INTEGER NOT NULL, process_id INTEGER NOT NULL, thread_id INTEGER NOT NULL, event_id INTEGER NOT NULL, event_count INTEGER NOT NULL, leaf_callsite_id INTEGER, has_unknown_symbol INTEGER NOT NULL DEFAULT 0, empty_stack INTEGER NOT NULL DEFAULT 0, unwind_error_code TEXT, unwind_raw_code INTEGER, unwind_address INTEGER)",
                "CREATE TABLE lost_situation (lost_id INTEGER PRIMARY KEY, sample_count INTEGER NOT NULL, lost_count INTEGER NOT NULL)",
                "CREATE TABLE unknown_record (unknown_id INTEGER PRIMARY KEY)",
                "CREATE TABLE context_switch (context_switch_id INTEGER PRIMARY KEY, thread_id INTEGER NOT NULL, timestamp_nanos INTEGER NOT NULL, switched_on_cpu INTEGER NOT NULL)",
                "CREATE TABLE profile_metadata (metadata_id INTEGER PRIMARY KEY, event_types TEXT NOT NULL, trace_off_cpu INTEGER NOT NULL)",
                "INSERT INTO process VALUES (100, 'example')",
                "INSERT INTO thread VALUES (101, 100, 'main')",
                "INSERT INTO event VALUES (1, 'cpu-cycles')",
                "INSERT INTO file VALUES (1, '/example/lib.so')",
                "INSERT INTO symbol VALUES (1, 1, 1, 'leaf', NULL)",
                "INSERT INTO frame VALUES (1, 4096, 1, 1, 'NATIVE')",
                "INSERT INTO callsite VALUES (1, NULL, 1)",
                "INSERT INTO sample VALUES (1, 10, 100, 101, 1, 3, 1, 0, 0, NULL, NULL, NULL)",
                "INSERT INTO sample VALUES (2, 20, 100, 101, 1, 5, 1, 0, 0, NULL, NULL, NULL)",
                "INSERT INTO context_switch VALUES (1, 101, 15, 1)",
                "INSERT INTO profile_metadata VALUES (1, 'cpu-cycles', 1)",
                "PRAGMA user_version=1",
            )
    }
}

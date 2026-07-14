package com.androidperformancestudio.storage

import com.androidperformancestudio.model.ProfileSample
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SQLiteSampleStoreTest {
    @Test
    @Suppress("NestedBlockDepth")
    fun `creates current schema with normalized profile tables and indexes idempotently`() {
        val database = Files.createTempFile("aps-schema-", ".sqlite")
        try {
            repeat(2) {
                SQLiteSampleStore.open(database).use { store ->
                    assertEquals(2, store.schemaVersion())
                    assertEquals("wal", store.journalMode())
                }
            }

            DriverManager.getConnection("jdbc:sqlite:${database.toAbsolutePath()}").use { connection ->
                val objects =
                    connection.createStatement().use { statement ->
                        statement
                            .executeQuery(
                                "SELECT type || ':' || name FROM sqlite_master " +
                                    "WHERE name NOT LIKE 'sqlite_%' ORDER BY type, name",
                            ).use { result ->
                                buildSet {
                                    while (result.next()) add(result.getString(1))
                                }
                            }
                    }
                listOf(
                    "table:process",
                    "table:thread",
                    "table:event",
                    "table:file",
                    "table:symbol",
                    "table:frame",
                    "table:callsite",
                    "table:sample",
                    "table:lost_situation",
                    "table:unknown_record",
                    "index:sample_thread_time",
                    "index:sample_event_time",
                    "index:callsite_parent_frame",
                ).forEach { required -> assertTrue(required in objects, "missing $required") }
            }
        } finally {
            deleteDatabase(database)
        }
    }

    @Test
    fun `initializes wal schema and imports samples in bounded batches`() {
        val database = Files.createTempFile("aps-store-", ".sqlite")
        try {
            SQLiteSampleStore.open(database).use { store ->
                val samples =
                    sequence {
                        repeat(2_505) { index ->
                            yield(
                                ProfileSample(
                                    timestampNanos = index.toLong(),
                                    processId = 100,
                                    threadId = index % 4,
                                    eventType = "cpu-cycles",
                                    symbolName = if (index % 5 == 0) "renderFrame" else "runLoop",
                                    eventCount = 1,
                                ),
                            )
                        }
                    }

                val result = store.importSamples(samples, batchSize = 1_000)

                assertEquals(2_505L, result.importedSamples)
                assertEquals(3, result.committedBatches)
                assertEquals(2_505L, store.sampleCount())
                assertEquals("wal", store.journalMode())
                assertEquals(
                    listOf(
                        SymbolWeight("runLoop", 2_004L),
                        SymbolWeight("renderFrame", 501L),
                    ),
                    store.topSymbols(limit = 2),
                )
            }
        } finally {
            deleteDatabase(database)
        }
    }

    private fun deleteDatabase(database: java.nio.file.Path) {
        database.deleteIfExists()
        database.resolveSibling(database.fileName.toString() + "-shm").deleteIfExists()
        database.resolveSibling(database.fileName.toString() + "-wal").deleteIfExists()
    }
}

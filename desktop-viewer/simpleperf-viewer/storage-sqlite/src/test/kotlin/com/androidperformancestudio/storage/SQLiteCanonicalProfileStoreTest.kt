package com.androidperformancestudio.storage

import com.androidperformancestudio.model.CanonicalProfileRecord
import com.androidperformancestudio.model.NormalizedProfileRecord
import com.androidperformancestudio.model.NormalizedSample
import com.androidperformancestudio.model.ProfileCategory
import com.androidperformancestudio.model.ProfileClockDomain
import com.androidperformancestudio.model.ProfileCounterFact
import com.androidperformancestudio.model.ProfileExecutionType
import com.androidperformancestudio.model.ProfileFrame
import com.androidperformancestudio.model.ProfileMarkerFact
import com.androidperformancestudio.model.ProfileProcessFact
import com.androidperformancestudio.model.ProfileProcessKey
import com.androidperformancestudio.model.ProfileSampleFact
import com.androidperformancestudio.model.ProfileScreenshotFact
import com.androidperformancestudio.model.ProfileSliceFact
import com.androidperformancestudio.model.ProfileSourceFact
import com.androidperformancestudio.model.ProfileSourceId
import com.androidperformancestudio.model.ProfileSourceKind
import com.androidperformancestudio.model.ProfileThreadFact
import com.androidperformancestudio.model.ProfileThreadKey
import com.androidperformancestudio.model.ProfileTimePoint
import com.androidperformancestudio.model.ProfileUnwindError
import java.nio.file.Files
import java.sql.ResultSet
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Suppress("LargeClass")
class SQLiteCanonicalProfileStoreTest {
    @Test
    @Suppress("LongMethod")
    fun `canonical import persists provenance and time-series facts`() =
        withStore { store ->
            val result = store.importCanonicalRecords(canonicalFixtureRecords(), batchSize = 2)

            assertEquals(8L, result.importedRecords)
            assertEquals(1L, result.importedSamples)
            assertEquals(1, result.committedBatches)
            store.assertSingleRow(
                "SELECT source_id, kind, clock_domain, valid_from_nanos, valid_until_nanos FROM profile_source",
            ) { row ->
                assertEquals("simpleperf", row.getString("source_id"))
                assertEquals("SIMPLEPERF", row.getString("kind"))
                assertEquals("monotonic", row.getString("clock_domain"))
                assertEquals(10L, row.getLong("valid_from_nanos"))
                assertEquals(1_000L, row.getLong("valid_until_nanos"))
            }
            store.assertSingleRow(
                "SELECT process_id, name, source_id, start_nanos, end_nanos FROM process",
            ) { row ->
                assertEquals(11, row.getInt("process_id"))
                assertEquals("studio", row.getString("name"))
                assertEquals("simpleperf", row.getString("source_id"))
                assertEquals(10L, row.getLong("start_nanos"))
                assertEquals(900L, row.getLong("end_nanos"))
            }
            store.assertSingleRow(
                "SELECT thread_id, process_id, name, start_nanos, end_nanos FROM thread",
            ) { row ->
                assertEquals(22, row.getInt("thread_id"))
                assertEquals(11, row.getInt("process_id"))
                assertEquals("RenderThread", row.getString("name"))
                assertEquals(20L, row.getLong("start_nanos"))
                assertEquals(800L, row.getLong("end_nanos"))
            }
            store.assertSingleRow(
                "SELECT s.timestamp_nanos, s.process_id, s.thread_id, e.name AS event_name, " +
                    "s.event_count, s.cpu_core, s.on_cpu, s.category_name, s.subcategory_name, " +
                    "s.has_unknown_symbol, s.empty_stack, s.unwind_error_code, s.unwind_raw_code, " +
                    "s.unwind_address, s.leaf_callsite_id FROM sample s JOIN event e USING(event_id)",
            ) { row ->
                assertEquals(100L, row.getLong("timestamp_nanos"))
                assertEquals(11, row.getInt("process_id"))
                assertEquals(22, row.getInt("thread_id"))
                assertEquals("cpu-cycles", row.getString("event_name"))
                assertEquals(7L, row.getLong("event_count"))
                assertEquals(4, row.getInt("cpu_core"))
                assertEquals(1, row.getInt("on_cpu"))
                assertEquals("Native", row.getString("category_name"))
                assertEquals("System", row.getString("subcategory_name"))
                assertEquals(0, row.getInt("has_unknown_symbol"))
                assertEquals(0, row.getInt("empty_stack"))
                assertEquals("UNWIND_FAILED", row.getString("unwind_error_code"))
                assertEquals(12L, row.getLong("unwind_raw_code"))
                assertEquals(0xabcdL, row.getLong("unwind_address"))
                assertFalse(row.getObject("leaf_callsite_id") == null)
            }
            assertEquals(1L, store.frameCount())
            assertEquals(1L, store.callsiteCount())
            store.assertSingleRow(
                "SELECT source_id, thread_id, start_nanos, end_nanos, schema_name, name, payload_json " +
                    "FROM profile_marker",
            ) { row ->
                assertEquals("simpleperf", row.getString("source_id"))
                assertEquals(22, row.getInt("thread_id"))
                assertEquals(110L, row.getLong("start_nanos"))
                assertEquals(120L, row.getLong("end_nanos"))
                assertEquals("trace_event", row.getString("schema_name"))
                assertEquals("draw", row.getString("name"))
                assertEquals("{\"phase\":\"begin\"}", row.getString("payload_json"))
            }
            store.assertSingleRow(
                "SELECT source_id, timestamp_nanos, name, unit, value FROM profile_counter",
            ) { row ->
                assertEquals("simpleperf", row.getString("source_id"))
                assertEquals(130L, row.getLong("timestamp_nanos"))
                assertEquals("rss", row.getString("name"))
                assertEquals("bytes", row.getString("unit"))
                assertEquals(1_024.5, row.getDouble("value"))
            }
            store.assertSingleRow(
                "SELECT source_id, thread_id, start_nanos, end_nanos, name, category_name, " +
                    "subcategory_name FROM profile_slice",
            ) { row ->
                assertEquals("simpleperf", row.getString("source_id"))
                assertEquals(22, row.getInt("thread_id"))
                assertEquals(140L, row.getLong("start_nanos"))
                assertEquals(160L, row.getLong("end_nanos"))
                assertEquals("Binder", row.getString("name"))
                assertEquals("IPC", row.getString("category_name"))
                assertEquals("sync", row.getString("subcategory_name"))
            }
            store.assertSingleRow(
                "SELECT source_id, timestamp_nanos, artifact_path FROM profile_screenshot",
            ) { row ->
                assertEquals("simpleperf", row.getString("source_id"))
                assertEquals(170L, row.getLong("timestamp_nanos"))
                assertEquals("artifacts/frame.png", row.getString("artifact_path"))
            }
        }

    @Test
    fun `canonical and legacy samples share stack storage and count once`() =
        withStore { store ->
            val records =
                sequence {
                    yieldAll(canonicalFixtureRecords().take(4))
                    yield(CanonicalProfileRecord.Legacy(legacySample()))
                }

            val result = store.importCanonicalRecords(records, batchSize = 2)

            assertEquals(5L, result.importedRecords)
            assertEquals(2L, result.importedSamples)
            assertEquals(1, result.committedBatches)
            assertEquals(2L, store.sampleCount())
            assertEquals(1L, store.frameCount())
            assertEquals(1L, store.callsiteCount())
        }

    @Test
    fun `canonical sample attaches provenance to a process first seen by legacy data`() =
        withStore { store ->
            val fixture = canonicalFixtureRecords().toList()

            store.importCanonicalRecords(
                sequenceOf(
                    fixture[0],
                    CanonicalProfileRecord.Legacy(legacySample()),
                    fixture[3],
                ),
                batchSize = 2,
            )

            store.assertSingleRow("SELECT source_id FROM process WHERE process_id = 11") { row ->
                assertEquals("simpleperf", row.getString("source_id"))
            }
        }

    @Test
    fun `failed canonical import rolls back pending facts and restores the connection`() =
        withStore { store ->
            val failure =
                assertFailsWith<IllegalStateException> {
                    store.importCanonicalRecords(
                        sequence {
                            yield(canonicalFixtureRecords().first())
                            error("injected canonical stream failure")
                        },
                    )
                }

            assertEquals("injected canonical stream failure", failure.message)
            assertEquals(0L, store.connection.singleLong("SELECT COUNT(*) FROM profile_source"))
            assertTrue(store.connection.autoCommit)
            assertEquals(
                1L,
                store.importCanonicalRecords(sequenceOf(canonicalFixtureRecords().first())).importedRecords,
            )
        }

    private fun canonicalFixtureRecords(): Sequence<CanonicalProfileRecord> {
        val sourceId = ProfileSourceId("simpleperf")
        val process = ProfileProcessKey(sourceId, 11)
        val thread = ProfileThreadKey(sourceId, process, 22)
        return sequenceOf(
            CanonicalProfileRecord.Source(
                ProfileSourceFact(
                    id = sourceId,
                    kind = ProfileSourceKind.SIMPLEPERF,
                    clockDomain = CLOCK,
                    validFromNanos = 10,
                    validUntilNanosExclusive = 1_000,
                ),
            ),
            CanonicalProfileRecord.Process(
                ProfileProcessFact(process, "studio", time(10), time(900)),
            ),
            CanonicalProfileRecord.Thread(
                ProfileThreadFact(thread, "RenderThread", time(20), time(800)),
            ),
            CanonicalProfileRecord.Sample(
                ProfileSampleFact(
                    sourceId = sourceId,
                    time = time(100),
                    thread = thread,
                    eventType = "cpu-cycles",
                    eventCount = 7,
                    cpuCore = 4,
                    onCpu = true,
                    category = ProfileCategory("Native", "System"),
                    frames = listOf(frame()),
                    unwindError = ProfileUnwindError("UNWIND_FAILED", 12, 0xabcd),
                ),
            ),
            CanonicalProfileRecord.Marker(
                ProfileMarkerFact(
                    sourceId,
                    thread,
                    time(110),
                    time(120),
                    "trace_event",
                    "draw",
                    "{\"phase\":\"begin\"}",
                ),
            ),
            CanonicalProfileRecord.Counter(
                ProfileCounterFact(sourceId, time(130), "rss", "bytes", 1_024.5),
            ),
            CanonicalProfileRecord.Slice(
                ProfileSliceFact(sourceId, thread, time(140), time(160), "Binder", ProfileCategory("IPC", "sync")),
            ),
            CanonicalProfileRecord.Screenshot(
                ProfileScreenshotFact(sourceId, time(170), "artifacts/frame.png"),
            ),
        )
    }

    private fun legacySample(): NormalizedProfileRecord.Sample =
        NormalizedProfileRecord.Sample(
            NormalizedSample(
                timestampNanos = 200,
                processId = 11,
                threadId = 22,
                threadName = "RenderThread",
                eventType = "cpu-cycles",
                eventCount = 3,
                frames = listOf(frame()),
                unwindError = null,
            ),
        )

    private fun frame(): ProfileFrame =
        ProfileFrame(
            virtualAddress = 0x1000,
            fileId = 1,
            symbolId = 2,
            filePath = "/system/lib64/libc.so",
            symbolName = "poll",
            executionType = ProfileExecutionType.NATIVE,
        )

    private fun time(timestampNanos: Long) = ProfileTimePoint(CLOCK, timestampNanos)

    private fun withStore(block: (SQLiteSampleStore) -> Unit) {
        val database = Files.createTempFile("aps-canonical-", ".sqlite")
        try {
            SQLiteSampleStore.open(database).use(block)
        } finally {
            database.deleteIfExists()
            database.resolveSibling(database.fileName.toString() + "-shm").deleteIfExists()
            database.resolveSibling(database.fileName.toString() + "-wal").deleteIfExists()
        }
    }

    private fun SQLiteSampleStore.assertSingleRow(
        sql: String,
        assertions: (ResultSet) -> Unit,
    ) {
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { result ->
                assertTrue(result.next(), "expected one row for: $sql")
                assertions(result)
                assertFalse(result.next(), "expected only one row for: $sql")
            }
        }
    }

    private companion object {
        val CLOCK = ProfileClockDomain("monotonic")
    }
}

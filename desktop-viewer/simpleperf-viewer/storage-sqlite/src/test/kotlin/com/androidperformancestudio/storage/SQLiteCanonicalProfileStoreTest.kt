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
import com.androidperformancestudio.model.ProfileThread
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@Suppress("LargeClass", "LongMethod", "LongParameterList", "MaxLineLength", "TooManyFunctions")
class SQLiteCanonicalProfileStoreTest {
    @Test
    fun `canonical import round trips source-qualified identity and every time point`() =
        withStore { store ->
            val result = store.importCanonicalRecords(canonicalFixtureRecords(), batchSize = 2)

            assertEquals(8L, result.importedRecords)
            assertEquals(1L, result.importedSamples)
            assertEquals(4, result.committedBatches)
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
                "SELECT source_id, process_id, name, start_nanos, start_clock_domain, " +
                    "start_error_bound_nanos, end_nanos, end_clock_domain, end_error_bound_nanos " +
                    "FROM profile_process",
            ) { row ->
                assertEquals("simpleperf", row.getString("source_id"))
                assertEquals(11, row.getInt("process_id"))
                assertEquals("studio", row.getString("name"))
                assertTime(row, "start", 10, "boottime", 1)
                assertTime(row, "end", 900, "realtime", 2)
            }
            store.assertSingleRow(
                "SELECT pt.source_id, pp.process_id, pt.thread_id, pt.name, pt.start_nanos, " +
                    "pt.start_clock_domain, pt.start_error_bound_nanos, pt.end_nanos, " +
                    "pt.end_clock_domain, pt.end_error_bound_nanos FROM profile_thread pt " +
                    "JOIN profile_process pp USING(process_row_id)",
            ) { row ->
                assertEquals("simpleperf", row.getString("source_id"))
                assertEquals(11, row.getInt("process_id"))
                assertEquals(22, row.getInt("thread_id"))
                assertEquals("RenderThread", row.getString("name"))
                assertTime(row, "start", 20, "thread-clock", 3)
                assertTime(row, "end", 800, "thread-clock", 4)
            }
            store.assertSingleRow(
                "SELECT s.source_id, pp.process_id, pt.thread_id, s.timestamp_nanos, s.clock_domain, " +
                    "s.time_error_bound_nanos, e.name AS event_name, s.event_count, s.cpu_core, s.on_cpu, " +
                    "s.category_name, s.subcategory_name, s.has_unknown_symbol, s.empty_stack, " +
                    "s.unwind_error_code, s.unwind_raw_code, s.unwind_address, s.leaf_callsite_id " +
                    "FROM sample s JOIN event e USING(event_id) " +
                    "JOIN profile_process pp USING(process_row_id) JOIN profile_thread pt USING(thread_row_id)",
            ) { row ->
                assertEquals("simpleperf", row.getString("source_id"))
                assertEquals(11, row.getInt("process_id"))
                assertEquals(22, row.getInt("thread_id"))
                assertEquals(100L, row.getLong("timestamp_nanos"))
                assertEquals("cpu-clock", row.getString("clock_domain"))
                assertEquals(5L, row.getLong("time_error_bound_nanos"))
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
                "SELECT source_id, thread_row_id, start_nanos, start_clock_domain, start_error_bound_nanos, " +
                    "end_nanos, end_clock_domain, end_error_bound_nanos, schema_name, name, payload_json " +
                    "FROM profile_marker",
            ) { row ->
                assertEquals("simpleperf", row.getString("source_id"))
                assertFalse(row.getObject("thread_row_id") == null)
                assertTime(row, "start", 110, "marker-clock", 6)
                assertTime(row, "end", 120, "marker-clock", 7)
                assertEquals("trace_event", row.getString("schema_name"))
                assertEquals("draw", row.getString("name"))
                assertEquals("{\"phase\":\"begin\"}", row.getString("payload_json"))
            }
            store.assertSingleRow(
                "SELECT source_id, timestamp_nanos, clock_domain, time_error_bound_nanos, name, unit, value " +
                    "FROM profile_counter",
            ) { row ->
                assertEquals("simpleperf", row.getString("source_id"))
                assertTime(row, "time", 130, "counter-clock", 8)
                assertEquals("rss", row.getString("name"))
                assertEquals("bytes", row.getString("unit"))
                assertEquals(1_024.5, row.getDouble("value"))
            }
            store.assertSingleRow(
                "SELECT source_id, thread_row_id, start_nanos, start_clock_domain, start_error_bound_nanos, " +
                    "end_nanos, end_clock_domain, end_error_bound_nanos, name, category_name, " +
                    "subcategory_name FROM profile_slice",
            ) { row ->
                assertEquals("simpleperf", row.getString("source_id"))
                assertFalse(row.getObject("thread_row_id") == null)
                assertTime(row, "start", 140, "slice-start", 9)
                assertTime(row, "end", 160, "slice-end", 10)
                assertEquals("Binder", row.getString("name"))
                assertEquals("IPC", row.getString("category_name"))
                assertEquals("sync", row.getString("subcategory_name"))
            }
            store.assertSingleRow(
                "SELECT source_id, timestamp_nanos, clock_domain, time_error_bound_nanos, artifact_path " +
                    "FROM profile_screenshot",
            ) { row ->
                assertEquals("simpleperf", row.getString("source_id"))
                assertTime(row, "time", 170, "screenshot-clock", 11)
                assertEquals("artifacts/frame.png", row.getString("artifact_path"))
            }
        }

    @Test
    fun `source-qualified process and thread identities survive numeric collisions`() =
        withStore { store ->
            val first = identityRecords("simpleperf", processId = 11, threadId = 22, threadName = "first")
            val second = identityRecords("perfetto", processId = 11, threadId = 22, threadName = "second")

            store.importCanonicalRecords((first + second).asSequence())

            assertEquals(2L, store.count("profile_process"))
            assertEquals(2L, store.count("profile_thread"))
            assertEquals(2L, store.sampleCount())
            assertEquals(2L, store.connection.singleLong("SELECT COUNT(DISTINCT process_row_id) FROM sample"))
            assertEquals(2L, store.connection.singleLong("SELECT COUNT(DISTINCT thread_row_id) FROM sample"))
            assertEquals(2L, store.overview().processCount)
            assertEquals(2L, store.overview().threadCount)
            val timelineTracks = store.threadTimelineTracks(bucketCount = 2)
            assertEquals(2, timelineTracks.size)
            assertEquals(2, timelineTracks.map(ThreadTimelineTrack::id).toSet().size)
            assertEquals(setOf("first", "second"), timelineTracks.map(ThreadTimelineTrack::name).toSet())
        }

    @Test
    fun `same external thread id remains distinct in different processes`() =
        withStore { store ->
            val source = source("simpleperf")
            val first = identityRecords(source, processId = 11, threadId = 22, threadName = "first", includeSource = false)
            val second = identityRecords(source, processId = 12, threadId = 22, threadName = "second", includeSource = false)

            store.importCanonicalRecords(sequenceOf(CanonicalProfileRecord.Source(source)) + (first + second).asSequence())

            assertEquals(2L, store.count("profile_thread"))
            assertEquals(setOf(11 to "first", 12 to "second"), store.canonicalThreadOwners())
            assertEquals(setOf(11 to 22, 12 to 22), store.threads().map { it.processId to it.threadId }.toSet())
        }

    @Test
    fun `canonical authority repairs legacy first wrong process and rejects later downgrade`() =
        withStore { store ->
            val source = source("simpleperf")
            val process = processKey(source.id, 11)
            val thread = threadKey(process, 22)
            val records =
                sequenceOf(
                    CanonicalProfileRecord.Source(source),
                    CanonicalProfileRecord.Legacy(NormalizedProfileRecord.Thread(ProfileThread(99, 22, "legacy-first"))),
                    CanonicalProfileRecord.Process(ProfileProcessFact(process, "canonical-process", null, null)),
                    CanonicalProfileRecord.Thread(ProfileThreadFact(thread, "canonical-thread", null, null)),
                    CanonicalProfileRecord.Sample(sampleFact(thread, source.id, 100)),
                    CanonicalProfileRecord.Legacy(NormalizedProfileRecord.Thread(ProfileThread(99, 22, "legacy-later"))),
                    CanonicalProfileRecord.Legacy(legacySample(processId = 99, threadId = 22, threadName = "legacy-later")),
                )

            store.importCanonicalRecords(records)

            store.assertSingleRow(
                "SELECT pp.process_id, pp.name AS process_name, pt.name AS thread_name " +
                    "FROM profile_thread pt JOIN profile_process pp USING(process_row_id)",
            ) { row ->
                assertEquals(11, row.getInt("process_id"))
                assertEquals("canonical-process", row.getString("process_name"))
                assertEquals("canonical-thread", row.getString("thread_name"))
            }
            store.assertSingleRow(
                "SELECT pp.process_id, pt.thread_id FROM sample s JOIN profile_process pp USING(process_row_id) " +
                    "JOIN profile_thread pt USING(thread_row_id) WHERE s.source_id IS NOT NULL",
            ) { row ->
                assertEquals(11, row.getInt("process_id"))
                assertEquals(22, row.getInt("thread_id"))
            }
        }

    @Test
    fun `thread-bearing canonical facts reject inconsistent sources and roll back`() =
        withStore { store ->
            val first = source("simpleperf")
            val second = source("perfetto")
            val secondProcess = processKey(second.id, 11)
            val inconsistentThread = ProfileThreadKey(first.id, secondProcess, 22)

            assertFailsWith<IllegalArgumentException> {
                store.importCanonicalRecords(
                    sequenceOf(
                        CanonicalProfileRecord.Source(first),
                        CanonicalProfileRecord.Source(second),
                        CanonicalProfileRecord.Thread(
                            ProfileThreadFact(inconsistentThread, "bad", null, null),
                        ),
                    ),
                )
            }

            assertEquals(0L, store.count("profile_source"))
            assertTrue(store.connection.autoCommit)
        }

    @Test
    fun `sample marker and slice reject a thread from another source`() =
        listOf("sample", "marker", "slice").forEach { kind ->
            withStore { store ->
                val first = source("simpleperf")
                val second = source("perfetto")
                val foreignThread = threadKey(processKey(second.id, 11), 22)
                val mismatched =
                    when (kind) {
                        "sample" -> CanonicalProfileRecord.Sample(sampleFact(foreignThread, first.id, 10))
                        "marker" ->
                            CanonicalProfileRecord.Marker(
                                ProfileMarkerFact(first.id, foreignThread, time(10), null, "log", "bad", "{}"),
                            )
                        else ->
                            CanonicalProfileRecord.Slice(
                                ProfileSliceFact(first.id, foreignThread, time(10), time(20), "bad", null),
                            )
                    }

                assertFailsWith<IllegalArgumentException> {
                    store.importCanonicalRecords(
                        sequenceOf(CanonicalProfileRecord.Source(first), CanonicalProfileRecord.Source(second), mismatched),
                    )
                }
                assertTrue(store.connection.autoCommit)
            }
        }

    @Test
    fun `nullable canonical bindings remain null`() =
        withStore { store ->
            val source = source("simpleperf")
            val process = processKey(source.id, 11)
            val thread = threadKey(process, 22)
            store.importCanonicalRecords(
                sequenceOf(
                    CanonicalProfileRecord.Source(source),
                    CanonicalProfileRecord.Process(ProfileProcessFact(process, null, null, null)),
                    CanonicalProfileRecord.Thread(ProfileThreadFact(thread, "thread", null, null)),
                    CanonicalProfileRecord.Sample(sampleFact(thread, source.id, 10, optionalFields = false)),
                    CanonicalProfileRecord.Marker(
                        ProfileMarkerFact(source.id, null, time(20), null, "log", "point", "{}"),
                    ),
                    CanonicalProfileRecord.Slice(
                        ProfileSliceFact(source.id, null, time(30), time(40), "slice", null),
                    ),
                ),
            )

            store.assertSingleRow("SELECT name, start_nanos, end_nanos FROM profile_process") { row ->
                assertNull(row.getString("name"))
                assertNull(row.getObject("start_nanos"))
                assertNull(row.getObject("end_nanos"))
            }
            store.assertSingleRow("SELECT cpu_core, on_cpu, category_name, subcategory_name FROM sample") { row ->
                assertNull(row.getObject("cpu_core"))
                assertNull(row.getObject("on_cpu"))
                assertNull(row.getObject("category_name"))
                assertNull(row.getObject("subcategory_name"))
            }
            store.assertSingleRow("SELECT thread_row_id, end_nanos, end_clock_domain FROM profile_marker") { row ->
                assertNull(row.getObject("thread_row_id"))
                assertNull(row.getObject("end_nanos"))
                assertNull(row.getObject("end_clock_domain"))
            }
            store.assertSingleRow("SELECT thread_row_id, category_name, subcategory_name FROM profile_slice") { row ->
                assertNull(row.getObject("thread_row_id"))
                assertNull(row.getObject("category_name"))
                assertNull(row.getObject("subcategory_name"))
            }
        }

    @Test
    fun `heterogeneous non-sample records commit at the record batch bound`() =
        withStore { store ->
            val records =
                (1..5).asSequence().map { index ->
                    CanonicalProfileRecord.Source(source("source-$index"))
                }

            val result = store.importCanonicalRecords(records, batchSize = 2)

            assertEquals(5L, result.importedRecords)
            assertEquals(0L, result.importedSamples)
            assertEquals(3, result.committedBatches)
            assertEquals(5L, store.count("profile_source"))
        }

    @Test
    fun `failure after a committed record batch preserves completed batches and rolls back pending facts`() =
        withStore { store ->
            val failure =
                assertFailsWith<IllegalStateException> {
                    store.importCanonicalRecords(
                        sequence {
                            yield(CanonicalProfileRecord.Source(source("committed-1")))
                            yield(CanonicalProfileRecord.Source(source("committed-2")))
                            yield(CanonicalProfileRecord.Source(source("pending")))
                            error("injected after committed batch")
                        },
                        batchSize = 2,
                    )
                }

            assertEquals("injected after committed batch", failure.message)
            assertEquals(setOf("committed-1", "committed-2"), store.sourceIds())
            assertTrue(store.connection.autoCommit)
            assertEquals(
                1L,
                store.importCanonicalRecords(sequenceOf(CanonicalProfileRecord.Source(source("pending")))).importedRecords,
            )
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
            assertEquals(3, result.committedBatches)
            assertEquals(2L, store.sampleCount())
            assertEquals(1L, store.frameCount())
            assertEquals(1L, store.callsiteCount())
        }

    private fun canonicalFixtureRecords(): Sequence<CanonicalProfileRecord> {
        val source = source("simpleperf", ProfileSourceKind.SIMPLEPERF)
        val process = processKey(source.id, 11)
        val thread = threadKey(process, 22)
        return sequenceOf(
            CanonicalProfileRecord.Source(source),
            CanonicalProfileRecord.Process(
                ProfileProcessFact(process, "studio", time(10, "boottime", 1), time(900, "realtime", 2)),
            ),
            CanonicalProfileRecord.Thread(
                ProfileThreadFact(
                    thread,
                    "RenderThread",
                    time(20, "thread-clock", 3),
                    time(800, "thread-clock", 4),
                ),
            ),
            CanonicalProfileRecord.Sample(
                sampleFact(thread, source.id, 100, timeClock = "cpu-clock", errorBound = 5),
            ),
            CanonicalProfileRecord.Marker(
                ProfileMarkerFact(
                    source.id,
                    thread,
                    time(110, "marker-clock", 6),
                    time(120, "marker-clock", 7),
                    "trace_event",
                    "draw",
                    "{\"phase\":\"begin\"}",
                ),
            ),
            CanonicalProfileRecord.Counter(
                ProfileCounterFact(source.id, time(130, "counter-clock", 8), "rss", "bytes", 1_024.5),
            ),
            CanonicalProfileRecord.Slice(
                ProfileSliceFact(
                    source.id,
                    thread,
                    time(140, "slice-start", 9),
                    time(160, "slice-end", 10),
                    "Binder",
                    ProfileCategory("IPC", "sync"),
                ),
            ),
            CanonicalProfileRecord.Screenshot(
                ProfileScreenshotFact(source.id, time(170, "screenshot-clock", 11), "artifacts/frame.png"),
            ),
        )
    }

    private fun identityRecords(
        sourceId: String,
        processId: Int,
        threadId: Int,
        threadName: String,
    ): List<CanonicalProfileRecord> = identityRecords(source(sourceId), processId, threadId, threadName)

    private fun identityRecords(
        source: ProfileSourceFact,
        processId: Int,
        threadId: Int,
        threadName: String,
        includeSource: Boolean = true,
    ): List<CanonicalProfileRecord> {
        val process = processKey(source.id, processId)
        val thread = threadKey(process, threadId)
        return buildList {
            if (includeSource) add(CanonicalProfileRecord.Source(source))
            add(CanonicalProfileRecord.Process(ProfileProcessFact(process, "process-$processId", null, null)))
            add(CanonicalProfileRecord.Thread(ProfileThreadFact(thread, threadName, null, null)))
            add(CanonicalProfileRecord.Sample(sampleFact(thread, source.id, processId.toLong())))
        }
    }

    private fun source(
        id: String,
        kind: ProfileSourceKind = ProfileSourceKind.IMPORTED,
    ): ProfileSourceFact =
        ProfileSourceFact(
            id = ProfileSourceId(id),
            kind = kind,
            clockDomain = ProfileClockDomain("monotonic"),
            validFromNanos = 10,
            validUntilNanosExclusive = 1_000,
        )

    private fun processKey(
        sourceId: ProfileSourceId,
        processId: Int,
    ) = ProfileProcessKey(sourceId, processId)

    private fun threadKey(
        process: ProfileProcessKey,
        threadId: Int,
    ) = ProfileThreadKey(process.sourceId, process, threadId)

    private fun sampleFact(
        thread: ProfileThreadKey,
        sourceId: ProfileSourceId,
        timestampNanos: Long,
        optionalFields: Boolean = true,
        timeClock: String = "monotonic",
        errorBound: Long = 0,
    ): ProfileSampleFact =
        ProfileSampleFact(
            sourceId = sourceId,
            time = time(timestampNanos, timeClock, errorBound),
            thread = thread,
            eventType = "cpu-cycles",
            eventCount = 7,
            cpuCore = if (optionalFields) 4 else null,
            onCpu = if (optionalFields) true else null,
            category = if (optionalFields) ProfileCategory("Native", "System") else null,
            frames = listOf(frame()),
            unwindError = if (optionalFields) ProfileUnwindError("UNWIND_FAILED", 12, 0xabcd) else null,
        )

    private fun legacySample(
        processId: Int = 11,
        threadId: Int = 22,
        threadName: String = "RenderThread",
    ): NormalizedProfileRecord.Sample =
        NormalizedProfileRecord.Sample(
            NormalizedSample(
                timestampNanos = 200,
                processId = processId,
                threadId = threadId,
                threadName = threadName,
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

    private fun time(
        timestampNanos: Long,
        clockDomain: String = "monotonic",
        errorBoundNanos: Long = 0,
    ) = ProfileTimePoint(ProfileClockDomain(clockDomain), timestampNanos, errorBoundNanos)

    private fun assertTime(
        row: ResultSet,
        prefix: String,
        timestampNanos: Long,
        clockDomain: String,
        errorBoundNanos: Long,
    ) {
        val timestampColumn = if (prefix == "time") "timestamp_nanos" else "${prefix}_nanos"
        val clockColumn = if (prefix == "time") "clock_domain" else "${prefix}_clock_domain"
        val errorColumn = if (prefix == "time") "time_error_bound_nanos" else "${prefix}_error_bound_nanos"
        assertEquals(timestampNanos, row.getLong(timestampColumn))
        assertEquals(clockDomain, row.getString(clockColumn))
        assertEquals(errorBoundNanos, row.getLong(errorColumn))
    }

    private fun SQLiteSampleStore.count(table: String): Long = connection.singleLong("SELECT COUNT(*) FROM $table")

    private fun SQLiteSampleStore.sourceIds(): Set<String> =
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT source_id FROM profile_source").use { result ->
                buildSet { while (result.next()) add(result.getString(1)) }
            }
        }

    private fun SQLiteSampleStore.canonicalThreadOwners(): Set<Pair<Int, String>> =
        connection.createStatement().use { statement ->
            statement
                .executeQuery(
                    "SELECT pp.process_id, pt.name FROM profile_thread pt " +
                        "JOIN profile_process pp USING(process_row_id)",
                ).use { result ->
                    buildSet { while (result.next()) add(result.getInt(1) to result.getString(2)) }
                }
        }

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
}

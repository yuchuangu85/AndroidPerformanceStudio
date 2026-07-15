package com.androidperformancestudio.storage

import com.androidperformancestudio.profileanalysis.AnalysisTimeRange
import com.androidperformancestudio.profileanalysis.CallStackAnalysisQuery
import com.androidperformancestudio.profileanalysis.CallStackDirection
import java.nio.file.Files
import java.nio.file.Path
import java.sql.SQLException
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SQLiteProfileProjectionQueriesTest {
    @Test
    fun `flame and call tree share one filtered transformed direction projection`() =
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = false, contextSwitchNanos = null)
            store.attachTwoFrameStack()

            val snapshot =
                store.projectCore(
                    ProfileProjectionRequest(
                        query = ProfileQuery(threadIds = setOf(101)),
                        callStackAnalysis =
                            CallStackAnalysisQuery(
                                previewRange = AnalysisTimeRange(10, 11),
                                searchText = "root,leaf",
                                direction = CallStackDirection.INVERTED,
                            ),
                    ),
                )

            assertEquals(
                snapshot.callTree.map(CallTreeNode::id),
                snapshot.flameGraph.callNodes.ids
                    .toList(),
            )
            assertEquals("leaf", snapshot.callTree.single { it.parentId == null }.symbolName)
            assertEquals(3L, snapshot.flameGraph.totalWeight)
            assertEquals(false, snapshot.flameGraph.rows.startsAtBottom)

            val previewEmpty =
                store.projectCore(
                    ProfileProjectionRequest(
                        query = ProfileQuery(threadIds = setOf(101)),
                        callStackAnalysis = CallStackAnalysisQuery(previewRange = AnalysisTimeRange(11, 12)),
                    ),
                )
            assertEquals(1L, previewEmpty.overview.sampleCount)
            assertEquals(0L, previewEmpty.flameGraph.totalWeight)
        }

    @Test
    fun `projection request keeps session aggregates unfiltered from the panel query`() =
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = false, contextSwitchNanos = null)
            store.execute("INSERT INTO thread(thread_id, process_id, name) VALUES (102, 100, 'worker')")
            store.execute(
                "INSERT INTO sample(timestamp_nanos, process_id, thread_id, event_id, event_count) " +
                    "VALUES (20, 100, 102, 1, 7)",
            )

            val snapshot =
                store.projectCore(
                    ProfileProjectionRequest(
                        query = ProfileQuery(threadIds = setOf(101)),
                        timelineBucketCount = 3,
                        topFunctionLimit = 1,
                        callStackAnalysis = CallStackAnalysisQuery(direction = CallStackDirection.INVERTED),
                    ),
                )

            assertEquals(2L, snapshot.sessionOverview.sampleCount)
            assertEquals(1L, snapshot.overview.sampleCount)
            assertEquals(listOf(101, 102), snapshot.sessionThreads.map(ThreadSummary::threadId).sorted())
            assertEquals(listOf(101), snapshot.threads.map(ThreadSummary::threadId))
        }

    @Test
    fun `core projection groups tracks and preserves availability`() =
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = true, contextSwitchNanos = 11)

            val snapshot = store.projectCore(ProfileQuery(threadIds = setOf(101)))

            assertEquals(
                listOf(ProfileTrackKind.CPU_SAMPLES, ProfileTrackKind.CONTEXT_SWITCHES),
                snapshot.tracks.map(ProfileTrackSnapshot::kind),
            )
            assertEquals(
                listOf(ProfileDataAvailability.AVAILABLE, ProfileDataAvailability.AVAILABLE),
                snapshot.tracks.map(ProfileTrackSnapshot::availability),
            )
            assertEquals(1L, snapshot.overview.sampleCount)
            assertEquals(100, snapshot.tracks.first().processId)
            assertEquals(101, snapshot.tracks.first().threadId)
            assertTrue(store.connection.autoCommit)
        }

    @Test
    fun `projection distinguishes empty collected data from data not collected`() {
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = true, contextSwitchNanos = null)

            val snapshot =
                store.projectCore(
                    ProfileQuery(
                        startNanosInclusive = 20,
                        endNanosExclusive = 30,
                        threadIds = setOf(101),
                    ),
                )

            assertEquals(
                listOf(ProfileDataAvailability.EMPTY, ProfileDataAvailability.EMPTY),
                snapshot.tracks.map(ProfileTrackSnapshot::availability),
            )
        }
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = false, contextSwitchNanos = null)

            val contextSwitches =
                store
                    .projectCore(ProfileQuery(threadIds = setOf(101)))
                    .tracks
                    .single { it.kind == ProfileTrackKind.CONTEXT_SWITCHES }

            assertEquals(ProfileDataAvailability.NOT_COLLECTED, contextSwitches.availability)
            assertEquals(null, contextSwitches.startNanos)
            assertEquals(null, contextSwitches.endNanosExclusive)
        }
    }

    @Test
    fun `stored context switch evidence overrides stale collection metadata`() =
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = false, contextSwitchNanos = 11)

            val contextSwitches =
                store
                    .projectCore(ProfileQuery(threadIds = setOf(101)))
                    .tracks
                    .single { it.kind == ProfileTrackKind.CONTEXT_SWITCHES }

            assertEquals(ProfileDataAvailability.AVAILABLE, contextSwitches.availability)
            assertEquals(11, contextSwitches.startNanos)
            assertEquals(12, contextSwitches.endNanosExclusive)
        }

    @Test
    fun `projection orders source colliding canonical tracks by stable qualified identity`() =
        withStore { store ->
            store.seedCanonicalSource("z-source", "z-thread", sampleNanos = 12)
            store.seedCanonicalSource("a-source", "a-thread", sampleNanos = 10)

            val first = store.projectCore(ProfileQuery(threadIds = setOf(101)))
            val second = store.projectCore(ProfileQuery(threadIds = setOf(101)))

            assertEquals(first, second)
            assertEquals(
                listOf(
                    "cpu-samples:canonical:a-source:100:101",
                    "cpu-samples:canonical:z-source:100:101",
                    "markers:a-source:100:101",
                    "markers:z-source:100:101",
                    "counters:a-source",
                    "counters:z-source",
                    "slices:a-source:100:101",
                    "slices:z-source:100:101",
                    "screenshots:a-source",
                    "screenshots:z-source",
                ),
                first.tracks.map(ProfileTrackSnapshot::id),
            )
            assertEquals(listOf("a-thread", "z-thread"), first.threads.map(ThreadSummary::name))
            assertTrue(first.tracks.all { it.availability == ProfileDataAvailability.AVAILABLE })
        }

    @Test
    fun `canonical source named legacy cannot collide with a legacy track identity`() =
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = false, contextSwitchNanos = null)
            store.seedCanonicalSource("legacy", "canonical", sampleNanos = 12)

            assertEquals(
                listOf(
                    "cpu-samples:canonical:legacy:100:101",
                    "cpu-samples:legacy:100:101",
                ),
                store
                    .projectCore(ProfileQuery(threadIds = setOf(101)))
                    .tracks
                    .filter { it.kind == ProfileTrackKind.CPU_SAMPLES }
                    .map(ProfileTrackSnapshot::id),
            )
        }

    @Test
    fun `projection freezes caller owned query sets`() =
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = false, contextSwitchNanos = null)
            val threadIds = mutableSetOf(101)
            val eventTypes = mutableSetOf("cpu-cycles")

            val snapshot = store.projectCore(ProfileQuery(threadIds = threadIds, eventTypes = eventTypes))
            threadIds += 999
            eventTypes.clear()

            assertEquals(setOf(101), snapshot.query.threadIds)
            assertEquals(setOf("cpu-cycles"), snapshot.query.eventTypes)
        }

    @Test
    fun `interval marker and slice overlap a range while point markers retain point semantics`() =
        withStore { store ->
            store.seedCanonicalSource("source", "main", sampleNanos = 100)
            val threadRowId = store.threadRowId("source")
            store.insertMarker("source", threadRowId, startNanos = 5, endNanos = 15)
            store.insertSlice("source", threadRowId, startNanos = 5, endNanos = 15)
            store.insertMarker("source", threadRowId, startNanos = 30, endNanos = null)

            val overlapping =
                store.projectCore(
                    ProfileQuery(startNanosInclusive = 10, endNanosExclusive = 20, threadIds = setOf(101)),
                )

            assertEquals(
                ProfileDataAvailability.AVAILABLE,
                overlapping.tracks.single { it.kind == ProfileTrackKind.MARKERS }.availability,
            )
            assertEquals(
                ProfileDataAvailability.AVAILABLE,
                overlapping.tracks.single { it.kind == ProfileTrackKind.SLICES }.availability,
            )

            val pointOnly =
                store.projectCore(
                    ProfileQuery(startNanosInclusive = 30, endNanosExclusive = 31, threadIds = setOf(101)),
                )
            assertEquals(30, pointOnly.tracks.single { it.kind == ProfileTrackKind.MARKERS }.startNanos)
            assertEquals(31, pointOnly.tracks.single { it.kind == ProfileTrackKind.MARKERS }.endNanosExclusive)

            val afterPoint =
                store.projectCore(
                    ProfileQuery(startNanosInclusive = 31, endNanosExclusive = 32, threadIds = setOf(101)),
                )
            assertEquals(
                ProfileDataAvailability.EMPTY,
                afterPoint.tracks.single { it.kind == ProfileTrackKind.MARKERS }.availability,
            )
        }

    @Test
    fun `marker bounds saturate each point before selecting the track maximum`() =
        withStore { store ->
            store.seedCanonicalSource("source", "main", sampleNanos = 100)
            val threadRowId = store.threadRowId("source")
            store.insertMarker("source", threadRowId, startNanos = 10, endNanos = 20)
            store.insertMarker("source", threadRowId, startNanos = 30, endNanos = null)

            val marker =
                store
                    .projectCore(
                        ProfileQuery(startNanosInclusive = 0, endNanosExclusive = 40, threadIds = setOf(101)),
                    ).tracks
                    .single { it.kind == ProfileTrackKind.MARKERS }

            assertEquals(10, marker.startNanos)
            assertEquals(31, marker.endNanosExclusive)
        }

    @Test
    fun `top function boundary selection is deterministic before projection limit`() =
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = false, contextSwitchNanos = null)
            store.seedEqualWeightFunctions(count = 201)

            val topFunctions = store.projectCore(ProfileQuery(threadIds = setOf(101))).topFunctions

            assertEquals(200, topFunctions.size)
            assertEquals(
                (1..200).map { "/functions/${it.toString().padStart(3, '0')}.so" },
                topFunctions.map { it.filePath },
            )
        }

    @Test
    fun `projection rejects an active import transaction`() =
        withStore { store ->
            store.beginRecordImport().use {
                assertFailsWith<IllegalStateException> { store.projectCore() }
                assertTrue(!store.connection.autoCommit)
            }
            assertTrue(store.connection.autoCommit)
        }

    @Test
    fun `projection failure rolls back and restores auto commit`() =
        withStore { store ->
            store.seedLegacyProfile(traceOffCpu = false, contextSwitchNanos = null)
            store.execute("DROP TABLE profile_marker")

            assertFailsWith<SQLException> { store.projectCore() }

            assertTrue(store.connection.autoCommit)
        }

    private fun SQLiteSampleStore.seedLegacyProfile(
        traceOffCpu: Boolean,
        contextSwitchNanos: Long?,
    ) {
        execute("INSERT INTO process(process_id, name) VALUES (100, 'app')")
        execute("INSERT INTO thread(thread_id, process_id, name) VALUES (101, 100, 'main')")
        execute("INSERT INTO event(event_id, name) VALUES (1, 'cpu-cycles')")
        execute(
            "INSERT INTO sample(timestamp_nanos, process_id, thread_id, event_id, event_count) " +
                "VALUES (10, 100, 101, 1, 3)",
        )
        execute(
            "INSERT INTO profile_metadata(metadata_id, event_types, trace_off_cpu) " +
                "VALUES (1, 'cpu-cycles', ${if (traceOffCpu) 1 else 0})",
        )
        contextSwitchNanos?.let { timestamp ->
            execute(
                "INSERT INTO context_switch(thread_id, timestamp_nanos, switched_on_cpu) " +
                    "VALUES (101, $timestamp, 1)",
            )
        }
    }

    private fun SQLiteSampleStore.attachTwoFrameStack() {
        execute("INSERT INTO file(file_id, path) VALUES (1, '/lib.so')")
        execute("INSERT INTO symbol(symbol_id, file_id, source_symbol_id, name) VALUES (1, 1, 1, 'root')")
        execute("INSERT INTO symbol(symbol_id, file_id, source_symbol_id, name) VALUES (2, 1, 2, 'leaf')")
        execute(
            "INSERT INTO frame(frame_id, virtual_address, file_id, symbol_id, execution_type) " +
                "VALUES (1, 1, 1, 1, 'NATIVE')",
        )
        execute(
            "INSERT INTO frame(frame_id, virtual_address, file_id, symbol_id, execution_type) " +
                "VALUES (2, 2, 1, 2, 'NATIVE')",
        )
        execute("INSERT INTO callsite(callsite_id, parent_id, frame_id) VALUES (1, NULL, 1)")
        execute("INSERT INTO callsite(callsite_id, parent_id, frame_id) VALUES (2, 1, 2)")
        execute("UPDATE sample SET leaf_callsite_id=2")
    }

    private fun SQLiteSampleStore.seedCanonicalSource(
        sourceId: String,
        threadName: String,
        sampleNanos: Long,
    ) {
        execute(
            "INSERT INTO profile_source(source_id, kind, clock_domain) " +
                "VALUES ('$sourceId', 'SIMPLEPERF', 'monotonic')",
        )
        execute(
            "INSERT INTO profile_process(source_id, process_id, name) " +
                "VALUES ('$sourceId', 100, 'app')",
        )
        val processRowId =
            connection.singleLong("SELECT process_row_id FROM profile_process WHERE source_id='$sourceId'")
        execute(
            "INSERT INTO profile_thread(source_id, process_row_id, thread_id, name) " +
                "VALUES ('$sourceId', $processRowId, 101, '$threadName')",
        )
        val threadRowId = connection.singleLong("SELECT thread_row_id FROM profile_thread WHERE source_id='$sourceId'")
        execute("INSERT OR IGNORE INTO process(process_id, name) VALUES (100, 'app')")
        execute("INSERT OR IGNORE INTO thread(thread_id, process_id, name) VALUES (101, 100, '$threadName')")
        execute("INSERT OR IGNORE INTO event(event_id, name) VALUES (1, 'cpu-cycles')")
        execute(
            "INSERT INTO sample(timestamp_nanos, process_id, thread_id, event_id, event_count, " +
                "source_id, process_row_id, thread_row_id, clock_domain, time_error_bound_nanos) " +
                "VALUES ($sampleNanos, 100, 101, 1, 1, '$sourceId', $processRowId, $threadRowId, 'monotonic', 0)",
        )
        execute(
            "INSERT INTO profile_marker(source_id, thread_row_id, start_nanos, start_clock_domain, " +
                "start_error_bound_nanos, schema_name, name, payload_json) " +
                "VALUES ('$sourceId', $threadRowId, $sampleNanos, 'monotonic', 0, 'log', 'marker', '{}')",
        )
        execute(
            "INSERT INTO profile_counter(source_id, timestamp_nanos, clock_domain, time_error_bound_nanos, " +
                "name, unit, value) VALUES ('$sourceId', $sampleNanos, 'monotonic', 0, 'rss', 'bytes', 1.0)",
        )
        execute(
            "INSERT INTO profile_slice(source_id, thread_row_id, start_nanos, start_clock_domain, " +
                "start_error_bound_nanos, end_nanos, end_clock_domain, end_error_bound_nanos, name) " +
                "VALUES ('$sourceId', $threadRowId, $sampleNanos, 'monotonic', 0, " +
                "${sampleNanos + 1}, 'monotonic', 0, 'slice')",
        )
        execute(
            "INSERT INTO profile_screenshot(source_id, timestamp_nanos, clock_domain, " +
                "time_error_bound_nanos, artifact_path) " +
                "VALUES ('$sourceId', $sampleNanos, 'monotonic', 0, '$sourceId.png')",
        )
    }

    private fun SQLiteSampleStore.threadRowId(sourceId: String): Long =
        connection.singleLong("SELECT thread_row_id FROM profile_thread WHERE source_id='$sourceId'")

    private fun SQLiteSampleStore.insertMarker(
        sourceId: String,
        threadRowId: Long,
        startNanos: Long,
        endNanos: Long?,
    ) {
        val endColumns =
            if (endNanos == null) {
                "NULL, NULL, NULL"
            } else {
                "$endNanos, 'monotonic', 0"
            }
        execute(
            "INSERT INTO profile_marker(source_id, thread_row_id, start_nanos, start_clock_domain, " +
                "start_error_bound_nanos, end_nanos, end_clock_domain, end_error_bound_nanos, " +
                "schema_name, name, payload_json) VALUES ('$sourceId', $threadRowId, $startNanos, " +
                "'monotonic', 0, $endColumns, 'log', 'marker', '{}')",
        )
    }

    private fun SQLiteSampleStore.insertSlice(
        sourceId: String,
        threadRowId: Long,
        startNanos: Long,
        endNanos: Long,
    ) {
        execute(
            "INSERT INTO profile_slice(source_id, thread_row_id, start_nanos, start_clock_domain, " +
                "start_error_bound_nanos, end_nanos, end_clock_domain, end_error_bound_nanos, name) " +
                "VALUES ('$sourceId', $threadRowId, $startNanos, 'monotonic', 0, " +
                "$endNanos, 'monotonic', 0, 'slice')",
        )
    }

    private fun SQLiteSampleStore.seedEqualWeightFunctions(count: Int) {
        repeat(count) { offset ->
            val rowId = offset + 1
            val pathIndex = count - offset
            val path = "/functions/${pathIndex.toString().padStart(3, '0')}.so"
            execute("INSERT INTO file(file_id, path) VALUES ($rowId, '$path')")
            execute(
                "INSERT INTO symbol(symbol_id, file_id, source_symbol_id, name) " +
                    "VALUES ($rowId, $rowId, 1, 'sameFunction')",
            )
            execute(
                "INSERT INTO frame(frame_id, virtual_address, file_id, symbol_id, execution_type) " +
                    "VALUES ($rowId, $rowId, $rowId, $rowId, 'NATIVE')",
            )
            execute("INSERT INTO callsite(callsite_id, parent_id, frame_id) VALUES ($rowId, NULL, $rowId)")
            execute(
                "INSERT INTO sample(timestamp_nanos, process_id, thread_id, event_id, event_count, leaf_callsite_id) " +
                    "VALUES (${rowId + 100}, 100, 101, 1, 1, $rowId)",
            )
        }
    }

    private fun SQLiteSampleStore.execute(sql: String) {
        connection.createStatement().use { statement -> statement.execute(sql) }
    }

    private fun withStore(block: (SQLiteSampleStore) -> Unit) {
        val database = Files.createTempFile("aps-projection-", ".sqlite")
        try {
            SQLiteSampleStore.open(database).use(block)
        } finally {
            deleteDatabase(database)
        }
    }

    private fun deleteDatabase(database: Path) {
        database.deleteIfExists()
        database.resolveSibling(database.fileName.toString() + "-shm").deleteIfExists()
        database.resolveSibling(database.fileName.toString() + "-wal").deleteIfExists()
    }
}

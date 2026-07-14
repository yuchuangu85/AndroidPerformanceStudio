package com.androidperformancestudio.storage

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SQLiteProfileProjectionQueriesTest {
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

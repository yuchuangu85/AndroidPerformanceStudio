package com.androidperformancestudio.storage

import com.androidperformancestudio.profileanalysis.AnalysisTimeRange
import com.androidperformancestudio.profileanalysis.CallNodeTable
import com.androidperformancestudio.profileanalysis.CallStackAnalysisQuery
import com.androidperformancestudio.profileanalysis.CallStackWeightType
import com.androidperformancestudio.profileanalysis.StackChartSnapshot
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SQLiteFirefoxWeightProjectionTest {
    @Test
    fun `committed range keeps pre-range Gecko delta history without importing outside stacks`() =
        withStore { store ->
            store.seedTiedSamples()
            val before = store.projectCore().overview
            val snapshot =
                store.projectCore(
                    ProfileProjectionRequest(
                        query = ProfileQuery(startNanosInclusive = START + TIED_OFFSET),
                        callStackAnalysis = CallStackAnalysisQuery(weightType = CallStackWeightType.SAMPLES),
                    ),
                )
            val chart = assertIs<PanelProjection.Ready<StackChartSnapshot>>(snapshot.stackChart).value

            assertEquals(3, snapshot.overview.sampleCount)
            assertEquals(4, snapshot.sessionOverview.sampleCount)
            assertEquals(3, snapshot.flameGraph.totalWeight)
            assertEquals(11_000_000, snapshot.flameGraph.callNodes.tracedFor("threadTwo"))
            assertEquals(0, snapshot.flameGraph.callNodes.tracedFor("threadOne"))
            assertEquals(11_000_000, chart.committedAnalysis!!.callNodes.tracedFor("threadTwo"))
            assertEquals(0, chart.committedAnalysis!!.callNodes.tracedFor("threadOne"))
            assertTrue(chart.blocks.all { it.startNanos >= START + TIED_OFFSET })
            assertEquals(before, store.projectCore().overview)
            assertEquals(
                listOf(
                    START + FIRST_OFFSET,
                    START + TIED_OFFSET,
                    START + TIED_OFFSET,
                    START + TIED_OFFSET + 10_000_000,
                ),
                SQLiteFlameGraphStackQueries.load(store.connection, ProfileQuery()).stacks.map { it.timestampNanos },
            )
            assertEquals(
                listOf(7L, 7L, 7L, 7L),
                SQLiteFlameGraphStackQueries.load(store.connection, ProfileQuery()).stacks.map { it.weight },
            )
        }

    @Test
    fun `preview after committed range uses the full timing history and its own last interval`() =
        withStore { store ->
            store.seedTiedSamples()
            val snapshot =
                store.projectCore(
                    ProfileProjectionRequest(
                        query = ProfileQuery(startNanosInclusive = START + TIED_OFFSET),
                        callStackAnalysis =
                            CallStackAnalysisQuery(
                                weightType = CallStackWeightType.SAMPLES,
                                previewRange = AnalysisTimeRange(START + TIED_OFFSET, START + TIED_OFFSET + 1),
                            ),
                    ),
                )

            assertEquals(2, snapshot.flameGraph.totalWeight)
            assertEquals(1_000_000, snapshot.flameGraph.callNodes.tracedFor("threadTwo"))
            assertEquals(0, snapshot.flameGraph.callNodes.tracedFor("threadOne"))
        }

    @Test
    fun `timing source loads only sample identities and respects thread and event selection`() =
        withStore { store ->
            store.seedTiedSamples()
            store.execute("INSERT INTO event(event_id, name) VALUES (2, 'cpu-clock')")
            store.execute(
                "INSERT INTO sample(timestamp_nanos, process_id, thread_id, event_id, event_count) " +
                    "VALUES ($START, 100, 2, 2, 99)",
            )
            store.execute("PRAGMA foreign_keys=OFF")
            store.execute("DROP TABLE callsite")
            store.execute("DROP TABLE frame")
            val timing =
                SQLiteFlameGraphStackQueries.loadTimingSource(
                    store.connection,
                    ProfileQuery(threadIds = setOf(2), eventTypes = setOf("cpu-cycles")),
                )

            assertEquals(listOf(1L, 2L, 4L), timing.stacks.map { it.sampleId })
            assertEquals(setOf("legacy:2"), timing.stacks.map { it.threadKey }.toSet())
            assertTrue(timing.framesById.isEmpty())
            assertTrue(timing.stacks.all { it.frameIdsRootToLeaf.isEmpty() && it.weight == 0L })
        }

    private fun CallNodeTable.tracedFor(symbol: String): Long? =
        tracedInclusiveNanosAt((0 until size).single { frameAt(it)?.symbolName == symbol })

    private fun SQLiteSampleStore.seedTiedSamples() {
        execute("INSERT INTO process(process_id, name) VALUES (100, 'app')")
        execute("INSERT INTO thread(thread_id, process_id, name) VALUES (1, 100, 'one'), (2, 100, 'two')")
        execute("INSERT INTO event(event_id, name) VALUES (1, 'cpu-cycles')")
        execute("INSERT INTO file(file_id, path) VALUES (1, '/lib.so')")
        execute(
            "INSERT INTO symbol(symbol_id, file_id, source_symbol_id, name) " +
                "VALUES (1, 1, 1, 'threadOne'), (2, 1, 2, 'threadTwo')",
        )
        execute(
            "INSERT INTO frame(frame_id, virtual_address, file_id, symbol_id, execution_type) " +
                "VALUES (1, 1, 1, 1, 'NATIVE'), (2, 2, 1, 2, 'NATIVE')",
        )
        execute("INSERT INTO callsite(callsite_id, parent_id, frame_id) VALUES (1, NULL, 1), (2, NULL, 2)")
        execute(
            "INSERT INTO sample(" +
                "sample_id, timestamp_nanos, process_id, thread_id, event_id, event_count, leaf_callsite_id) " +
                "VALUES (1, ${START + FIRST_OFFSET}, 100, 2, 1, 7, 2), " +
                "(2, ${START + TIED_OFFSET}, 100, 2, 1, 7, 2), " +
                "(3, ${START + TIED_OFFSET}, 100, 1, 1, 7, 1), " +
                "(4, ${START + TIED_OFFSET + 10_000_000}, 100, 2, 1, 7, 2)",
        )
    }

    private fun SQLiteSampleStore.execute(sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }

    private fun withStore(block: (SQLiteSampleStore) -> Unit) {
        val database = Files.createTempFile("aps-firefox-weight-", ".sqlite")
        try {
            SQLiteSampleStore.open(database).use(block)
        } finally {
            Files.deleteIfExists(database)
            Files.deleteIfExists(database.resolveSibling("${database.fileName}-shm"))
            Files.deleteIfExists(database.resolveSibling("${database.fileName}-wal"))
        }
    }

    private companion object {
        const val START = 80_000_000_000_000L
        const val FIRST_OFFSET = 670_487L
        const val TIED_OFFSET = 1_090_098L
    }
}

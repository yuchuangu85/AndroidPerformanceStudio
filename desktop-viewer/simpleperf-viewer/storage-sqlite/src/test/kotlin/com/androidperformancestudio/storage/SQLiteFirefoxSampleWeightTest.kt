package com.androidperformancestudio.storage

import com.androidperformancestudio.model.ProfileSample
import com.androidperformancestudio.profileanalysis.AnalysisTimeRange
import com.androidperformancestudio.profileanalysis.CallStackAnalysisQuery
import com.androidperformancestudio.profileanalysis.CallStackDirection
import com.androidperformancestudio.profileanalysis.CallStackWeightType
import com.androidperformancestudio.profileanalysis.FlameGraphEmptyReason
import com.androidperformancestudio.profileanalysis.StackChartSnapshot
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SQLiteFirefoxSampleWeightTest {
    @Test
    fun `sample weights and their units propagate without rewriting canonical event weights`() =
        withStore { store, _ ->
            store.importSamples(sequenceOf(sample(0, "kept", 9_000_000), sample(10_000_000, "kept", 2_000_000)))
            CallStackDirection.entries.forEach { direction ->
                val result = store.projectCore(request(CallStackAnalysisQuery(direction = direction)))
                assertEquals(CallStackWeightType.SAMPLES, result.flameGraph.weightType)
                assertEquals(2, result.flameGraph.totalWeight)
                assertEquals(11_000_000, result.flameGraph.callNodes.tracedInclusiveNanosAt(0))
                assertEquals(11_000_000, result.overview.totalEventWeight)
                val chart = assertIs<PanelProjection.Ready<StackChartSnapshot>>(result.stackChart).value
                assertEquals(CallStackWeightType.SAMPLES, chart.committedAnalysis?.weightType)
            }
            assertEquals(11_000_000, store.projectCore(ProfileQuery()).flameGraph.totalWeight)
        }

    @Test
    fun `preview last sample uses interval rather than the next sample outside the preview`() =
        withStore { store, _ ->
            store.importSamples(sequenceOf(sample(0), sample(10_000_000), sample(100_000_000)))
            val result =
                store.projectCore(
                    request(CallStackAnalysisQuery(previewRange = AnalysisTimeRange(0, 20_000_000))),
                )
            assertEquals(2, result.flameGraph.totalWeight)
            assertEquals(11_000_000, result.flameGraph.callNodes.tracedInclusiveNanosAt(0))
            val chart = assertIs<PanelProjection.Ready<StackChartSnapshot>>(result.stackChart).value
            assertEquals(3, chart.committedAnalysis?.totalWeight)
            assertEquals(101_000_000, chart.committedAnalysis?.callNodes?.tracedInclusiveNanosAt(0))
        }

    @Test
    fun `search does not refill the time gaps of rejected samples`() =
        withStore { store, _ ->
            store.importSamples(sequenceOf(sample(0), sample(10_000_000, "excluded"), sample(100_000_000)))
            val result = store.projectCore(request(CallStackAnalysisQuery(searchText = "kept")))
            assertEquals(2, result.flameGraph.totalWeight)
            assertEquals(11_000_000, result.flameGraph.callNodes.tracedInclusiveNanosAt(0))
        }

    @Test
    fun `sample conversion preserves structured negative weight failure`() =
        withStore { store, database ->
            store.importSamples(sequenceOf(sample(0)))
            DriverManager.getConnection("jdbc:sqlite:$database").use { connection ->
                connection.createStatement().use { it.executeUpdate("UPDATE sample SET event_count=-1") }
            }
            val result = store.projectCore(request(CallStackAnalysisQuery()))
            assertEquals(FlameGraphEmptyReason.PROJECTION_FAILED, result.flameGraph.emptyReason)
            assertTrue(
                result.flameGraph.diagnosticDetails
                    .orEmpty()
                    .contains("Negative call-stack weight"),
            )
            assertEquals(CallStackWeightType.SAMPLES, result.flameGraph.weightType)
        }

    private fun request(query: CallStackAnalysisQuery) =
        ProfileProjectionRequest(callStackAnalysis = query.copy(weightType = CallStackWeightType.SAMPLES))

    private fun sample(
        timestamp: Long,
        symbol: String = "kept",
        weight: Long = 1_000_000,
    ) = ProfileSample(timestamp, 1, 1, "cpu-clock", symbol, weight)

    private fun withStore(block: (SQLiteSampleStore, Path) -> Unit) {
        val directory = Files.createTempDirectory("aps-firefox-weight-")
        val database = directory.resolve("profile.sqlite")
        try {
            SQLiteSampleStore.open(database).use { block(it, database) }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}

package com.androidperformancestudio.application

import com.androidperformancestudio.model.ProfileSample
import com.androidperformancestudio.profileanalysis.CallStackWeightType
import com.androidperformancestudio.storage.SQLiteSampleStore
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ReportControllerInitialThreadTest {
    @Test
    fun `desktop initial thread selection filters the actual first published projection`() =
        withSession { session ->
            runBlocking {
                ReportController(selectInitialThread = true, matchFirefoxSampleWeights = true).use { controller ->
                    controller.openSession(session)
                    val state = controller.state.value
                    val report = assertIs<ReportLoadState.Ready>(state.loadState).report
                    assertEquals(setOf(200), state.filter.threadIds)
                    assertEquals(2, report.overview.sampleCount)
                    assertEquals(3, report.sessionOverview.sampleCount)
                    assertEquals(2_000_000, report.overview.totalEventWeight)
                    assertEquals(CallStackWeightType.SAMPLES, report.flameGraph.weightType)
                    assertEquals(2, report.flameGraph.totalWeight)
                    assertEquals(2, report.flameGraph.callNodes.sampleCountAt(0))
                    assertEquals(21_000_000, report.flameGraph.callNodes.tracedInclusiveNanosAt(0))
                }
            }
        }

    @Test
    fun `manual all thread selection is not replaced by the opening default`() =
        withSession { session ->
            runBlocking {
                ReportController(selectInitialThread = true, matchFirefoxSampleWeights = true).use { controller ->
                    controller.openSession(session)
                    controller.updateThreads(emptySet())
                    val state = controller.state.value
                    val report = assertIs<ReportLoadState.Ready>(state.loadState).report
                    assertEquals(emptySet(), state.filter.threadIds)
                    assertEquals(3, report.overview.sampleCount)
                    assertEquals(3, report.flameGraph.totalWeight)
                    assertEquals(11_000_000, report.overview.totalEventWeight)
                }
            }
        }

    @Test
    fun `aggregate analysis API keeps event weights unless the desktop opts into sample weighting`() =
        withSession { session ->
            runBlocking {
                ReportController().use { controller ->
                    controller.openSession(session)
                    val state = controller.state.value
                    val report = assertIs<ReportLoadState.Ready>(state.loadState).report
                    assertEquals(emptySet(), state.filter.threadIds)
                    assertEquals(11_000_000, report.flameGraph.totalWeight)
                }
            }
        }

    private fun withSession(block: (Path) -> Unit) {
        val session = Files.createTempDirectory("aps-initial-thread-")
        try {
            SQLiteSampleStore.open(session.resolve("profile.sqlite")).use { store ->
                store.importSamples(
                    sequenceOf(
                        ProfileSample(0, 100, 100, "cpu-clock", "android.os.Looper.loopOnce", 9_000_000),
                        ProfileSample(10_000_000, 100, 200, "cpu-clock", "android.os.Looper.loopOnce", 1_000_000),
                        ProfileSample(30_000_000, 100, 200, "cpu-clock", "android.os.Looper.loopOnce", 1_000_000),
                    ),
                )
            }
            block(session)
        } finally {
            session.toFile().deleteRecursively()
        }
    }
}

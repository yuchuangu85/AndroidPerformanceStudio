package com.androidperformancestudio.frame.presentation

import com.androidperformancestudio.frame.analysis.FrameJankAnalyzer
import com.androidperformancestudio.frame.model.ExpectedDurationSource
import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.model.FrameSource
import com.androidperformancestudio.frame.presentation.generated.resources.Res
import com.androidperformancestudio.frame.presentation.generated.resources.cluster_range
import com.androidperformancestudio.frame.presentation.generated.resources.open_trace_in_perfetto
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FrameClusterPresentationTest {
    @Test
    fun `cluster selection targets an existing deadline miss frame only`() {
        val frames =
            listOf(
                sample(1, 20_000_000L),
                sample(2, 5_000_000L),
            )
        val analysis = FrameJankAnalyzer().analyze(frames)
        val cluster = analysis.clusters.single()

        assertEquals(1L, analysis.clusterTargetFrameId(cluster))
        assertNull(analysis.clusterTargetFrameId(cluster.copy(deadlineMissFrameIds = listOf(2L))))
        assertNull(analysis.clusterTargetFrameId(cluster.copy(deadlineMissFrameIds = listOf(99L))))
    }

    @Test
    fun `cluster workspace keeps evidence visible and routes selection through frame callback`() {
        val source =
            Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/frame/presentation/FrameProfilerScreen.kt"))
        val clusterUi = source.substringAfter("private fun ClusterList(").substringBefore("private fun Long?.formatMillis")

        assertTrue(clusterUi.contains("StudioDataTable("))
        assertTrue(clusterUi.contains("cluster.deadlineMissFrameIds.size"))
        assertTrue(clusterUi.contains("cluster.dominantReportedStage"))
        assertTrue(clusterUi.contains("onSelectFrame"))
        assertTrue(clusterUi.contains(".selectable("))
        assertEquals("Frame Range", localizedStringResource(Res.string.cluster_range, UiLanguage.ENGLISH))
        assertEquals("帧范围", localizedStringResource(Res.string.cluster_range, UiLanguage.SIMPLIFIED_CHINESE))
    }

    @Test
    fun `selected frame detail offers trace navigation only when wired`() {
        val source =
            Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/frame/presentation/FrameProfilerScreen.kt"))
        val detailUi = source.substringAfter("private fun FrameDetail(").substringBefore("private fun DetailRow(")

        assertTrue(detailUi.contains("onOpenTrace?.let { openTrace ->"))
        assertTrue(detailUi.contains("openTrace(frame.sample)"))
        assertEquals("在 Perfetto 中打开 Trace", localizedStringResource(Res.string.open_trace_in_perfetto, UiLanguage.SIMPLIFIED_CHINESE))
    }

    private fun sample(
        id: Long,
        durationNs: Long,
    ): FrameSample =
        FrameSample(
            frameId = id,
            sessionId = "session",
            source = FrameSource.GFXINFO,
            totalDurationNs = durationNs,
            expectedDurationNs = 16_666_667L,
            expectedDurationSource = ExpectedDurationSource.FRAME_INTERVAL,
        )
}

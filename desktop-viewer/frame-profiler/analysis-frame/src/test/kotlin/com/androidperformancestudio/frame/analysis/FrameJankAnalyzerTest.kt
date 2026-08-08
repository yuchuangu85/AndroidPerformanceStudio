package com.androidperformancestudio.frame.analysis

import com.androidperformancestudio.frame.model.ExpectedDurationSource
import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.model.FrameSource
import com.androidperformancestudio.frame.model.FrameStages
import kotlin.test.Test
import kotlin.test.assertEquals

class FrameJankAnalyzerTest {
    private val analyzer = FrameJankAnalyzer()

    @Test
    fun `uses each frame deadline instead of fixed 60hz threshold`() {
        val frames =
            listOf(
                frame(id = 1, durationNs = 10_000_000L, expectedNs = 16_666_667L),
                frame(id = 2, durationNs = 10_000_000L, expectedNs = 8_333_333L),
            )

        val result = analyzer.analyze(frames)

        assertEquals(FrameDeadlineVerdict.MET, result.frames.single { it.sample.frameId == 1L }.deadlineVerdict)
        assertEquals(FrameDeadlineVerdict.MISSED, result.frames.single { it.sample.frameId == 2L }.deadlineVerdict)
        assertEquals(0.5, result.summary.deadlineMissRate)
    }

    @Test
    fun `platform signal and deadline miss remain separate`() {
        val platformSmooth = frame(1, durationNs = 20_000_000L, expectedNs = 8_333_333L).copy(platformJank = false)
        val platformJank = frame(2, durationNs = 5_000_000L, expectedNs = 16_666_667L).copy(platformJank = true)

        val result = analyzer.analyze(listOf(platformSmooth, platformJank))

        assertEquals(FrameDeadlineVerdict.MISSED, result.frames[0].deadlineVerdict)
        assertEquals(false, result.frames[0].sample.platformJank)
        assertEquals(FrameDeadlineVerdict.MET, result.frames[1].deadlineVerdict)
        assertEquals(true, result.frames[1].sample.platformJank)
        assertEquals(0.5, result.summary.deadlineMissRate)
        assertEquals(0.5, result.summary.platformJankRate)
    }

    @Test
    fun `groups nearby jank frames into one cluster`() {
        val result =
            analyzer.analyze(
                listOf(
                    frame(1, 20_000_000L),
                    frame(2, 5_000_000L),
                    frame(3, 6_000_000L),
                    frame(4, 25_000_000L),
                    frame(5, 5_000_000L),
                    frame(6, 5_000_000L),
                    frame(7, 5_000_000L),
                    frame(8, 30_000_000L),
                ),
            )

        assertEquals(2, result.clusters.size)
        assertEquals(listOf(1L, 4L), result.clusters.first().deadlineMissFrameIds)
        assertEquals(listOf(8L), result.clusters.last().deadlineMissFrameIds)
    }

    @Test
    fun `reports largest stage without inferring a root cause`() {
        val result =
            analyzer.analyzeFrame(
                frame(1, 30_000_000L).copy(
                    stages = FrameStages(layoutMeasureNs = 12_000_000L, drawNs = 4_000_000L),
                ),
            )

        assertEquals("Layout/Measure", result.largestReportedStage)
        assertEquals(emptySet(), result.platformJankTypes)
    }

    private fun frame(
        id: Long,
        durationNs: Long,
        expectedNs: Long = 16_666_667L,
    ): FrameSample =
        FrameSample(
            frameId = id,
            sessionId = "session",
            source = FrameSource.GFXINFO,
            intendedVsyncNs = id * expectedNs,
            frameCompletedNs = id * expectedNs + durationNs,
            expectedDurationNs = expectedNs,
            expectedDurationSource = ExpectedDurationSource.PLATFORM_DEADLINE,
            totalDurationNs = durationNs,
        )
}

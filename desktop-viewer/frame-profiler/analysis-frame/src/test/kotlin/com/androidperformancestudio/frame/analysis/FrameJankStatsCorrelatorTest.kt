package com.androidperformancestudio.frame.analysis

import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.model.FrameSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameJankStatsCorrelatorTest {
    @Test
    fun `prefers vsync identity and enriches context`() {
        val frame =
            FrameSample(
                frameId = 7,
                sessionId = "s",
                source = FrameSource.PERFETTO,
                frameTimelineVsyncId = 42,
                intendedVsyncNs = 1_000_000,
            )
        val result =
            FrameJankStatsCorrelator()
                .correlate(
                    listOf(frame),
                    listOf(
                        JankStatsObservation(
                            frameTimelineVsyncId = 42,
                            timestampNs = 1_100_000,
                            isJank = true,
                            ruleId = "jankstats-default",
                            ruleVersion = "1.0.0",
                            fragmentName = "FeedFragment",
                            interactionState = "scroll",
                        ),
                    ),
                ).single()
        assertEquals(JankStatsMatch.VSYNC_ID, result.matchedBy)
        assertTrue(result.frame.jankStatsJank == true)
        assertEquals("FeedFragment", result.frame.fragmentName)
        assertEquals("scroll", result.frame.interactionState)
        assertEquals("jankstats-default", result.frame.jankStatsRuleId)
        assertEquals("1.0.0", result.frame.jankStatsRuleVersion)
    }

    @Test
    fun `does not reuse one observation for duplicate frame identities`() {
        val frames =
            listOf(
                FrameSample(frameId = 7, sessionId = "s", source = FrameSource.PERFETTO),
                FrameSample(frameId = 7, sessionId = "s", source = FrameSource.PERFETTO),
            )
        val results =
            FrameJankStatsCorrelator().correlate(
                frames,
                listOf(JankStatsObservation(frameId = 7, isJank = true)),
            )

        assertEquals(JankStatsMatch.FRAME_ID, results[0].matchedBy)
        assertEquals(JankStatsMatch.UNMATCHED, results[1].matchedBy)
        assertEquals(null, results[1].jankStats)
    }

    @Test
    fun `uses bounded timestamp matching only as derived evidence`() {
        val frame =
            FrameSample(
                frameId = 1,
                sessionId = "s",
                source = FrameSource.PERFETTO,
                actualVsyncNs = 10_000_000,
            )
        val matched =
            FrameJankStatsCorrelator(timestampToleranceNs = 500_000)
                .correlate(
                    listOf(frame),
                    listOf(JankStatsObservation(timestampNs = 9_750_000, isJank = false)),
                ).single()

        assertEquals(JankStatsMatch.TIMESTAMP, matched.matchedBy)
        assertEquals(250_000, matched.timeDeltaNs)
    }
}

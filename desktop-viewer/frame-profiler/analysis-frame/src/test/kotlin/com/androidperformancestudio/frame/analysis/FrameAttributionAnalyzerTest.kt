package com.androidperformancestudio.frame.analysis

import com.androidperformancestudio.frame.model.ExpectedDurationSource
import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.model.FrameSource
import kotlin.test.Test
import kotlin.test.assertEquals

class FrameAttributionAnalyzerTest {
    @Test
    fun `groups jank by activity window and UI state`() {
        val samples =
            listOf(
                sample(1, 10L, "Home", "window-1", "home"),
                sample(2, 30L, "Home", "window-1", "home"),
                sample(3, 15L, "Details", "window-2", "details"),
            )

        val result = FrameAttributionAnalyzer().analyze(samples)

        assertEquals(2, result.size)
        assertEquals("Home", result.first().activityName)
        assertEquals(1, result.first().deadlineMissFrames)
        assertEquals("details", result.last().uiState)
    }

    private fun sample(
        id: Long,
        durationNs: Long,
        activity: String,
        window: String,
        state: String,
    ) = FrameSample(
        frameId = id,
        sessionId = "session",
        source = FrameSource.JANK_STATS,
        activityName = activity,
        windowId = window,
        intendedVsyncNs = 0L,
        frameCompletedNs = durationNs,
        expectedDurationNs = 16L,
        expectedDurationSource = ExpectedDurationSource.PLATFORM_DEADLINE,
        states = mapOf("screen" to state),
    )
}

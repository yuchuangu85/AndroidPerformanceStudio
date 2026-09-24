package com.androidperformancestudio.frame.presentation

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FrameTimelineWindowTest {
    @Test
    fun `large capture uses bounded viewport and maps both canvas edges`() {
        val window = frameTimelineWindow(totalFrames = 10_001, requestedStart = 9_900, requestedCount = 500)

        assertEquals(FrameTimelineWindow(startIndex = 9_501, count = 500), window)
        assertEquals(9_501, window.indexAt(0f, 1_000f))
        assertEquals(10_000, window.indexAt(1_000f, 1_000f))
        assertEquals(9_501, window.indexAt(-10f, 1_000f))
    }

    @Test
    fun `external selection is centered without leaving capture bounds`() {
        assertEquals(FrameTimelineWindow(4_750, 500), centeredFrameTimelineWindow(10_000, 5_000, 500))
        assertEquals(FrameTimelineWindow(0, 500), centeredFrameTimelineWindow(10_000, 3, 500))
        assertEquals(FrameTimelineWindow(9_500, 500), centeredFrameTimelineWindow(10_000, 9_999, 500))
    }

    @Test
    fun `empty and invalid canvas sizes never resolve a frame`() {
        assertNull(frameTimelineWindow(0, 20, 500).indexAt(5f, 10f))
        assertNull(frameTimelineWindow(100, 0, 20).indexAt(5f, 0f))
        assertEquals(FrameTimelineWindow(0, 1), frameTimelineWindow(1, -5, 0))
    }
}

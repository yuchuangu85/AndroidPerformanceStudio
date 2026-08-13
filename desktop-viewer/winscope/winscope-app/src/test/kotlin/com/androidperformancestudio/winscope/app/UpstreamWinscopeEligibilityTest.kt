package com.androidperformancestudio.winscope.app

import com.androidperformancestudio.winscope.model.WinscopeSource
import com.androidperformancestudio.winscope.model.WinscopeTimeline
import com.androidperformancestudio.winscope.model.WinscopeTimelineEntry
import com.androidperformancestudio.winscope.model.WinscopeTraceBounds
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpstreamWinscopeEligibilityTest {
    @Test
    fun `requires parsed WindowManager or SurfaceFlinger evidence`() {
        assertFalse(canOpenInUpstreamWinscope(null))
        assertFalse(canOpenInUpstreamWinscope(timeline(WinscopeSource.SCREEN_RECORDING)))
        assertFalse(canOpenInUpstreamWinscope(timeline(WinscopeSource.WINDOW_MANAGER, entries = emptyList())))
        assertTrue(canOpenInUpstreamWinscope(timeline(WinscopeSource.WINDOW_MANAGER)))
        assertTrue(canOpenInUpstreamWinscope(timeline(WinscopeSource.SURFACE_FLINGER)))
    }

    private fun timeline(
        source: WinscopeSource,
        entries: List<WinscopeTimelineEntry> = listOf(WinscopeTimelineEntry(1, source, 1)),
    ) = WinscopeTimeline(WinscopeTraceBounds(1, 2), mapOf(source to entries))
}

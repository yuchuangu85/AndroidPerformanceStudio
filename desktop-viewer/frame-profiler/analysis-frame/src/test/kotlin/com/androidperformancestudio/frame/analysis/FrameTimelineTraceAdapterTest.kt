package com.androidperformancestudio.frame.analysis

import com.androidperformancestudio.frame.model.FrameSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FrameTimelineTraceAdapterTest {
    @Test
    fun `maps expected and actual slices with surface correlation and jank`() {
        val result =
            FrameTimelineTraceAdapter().mapFixture(
                """
                |frame_id,expected_ts,expected_dur,actual_ts,actual_dur,surface_token,layer_name,jank_type,process_name,surface_flinger_jank_type
                |42,1000,16666666,1100,18000000,7,SurfaceView,Missed deadline,com.example,Missed deadline
                |
                """.trimMargin(),
            )
        val frame = result.frames.single()
        assertEquals(FrameSource.PERFETTO, frame.source)
        assertEquals(42L, frame.frameTimelineVsyncId)
        assertEquals(18_000_000L, frame.totalDurationNs)
        assertTrue(frame.platformJank == true)
        assertTrue(FrameTimelineTraceAdapter.SURFACE_CORRELATION in result.capabilities)
        assertTrue(FrameTimelineTraceAdapter().timelineQuery(123).sql.contains("pid = 123"))
        assertTrue(frame.states["surfaceFlingerJankType"] == "Missed deadline")
    }
}

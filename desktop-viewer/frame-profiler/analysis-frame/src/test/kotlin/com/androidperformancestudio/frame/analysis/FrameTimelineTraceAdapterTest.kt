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
                |frame_id,expected_ts,expected_dur,actual_ts,actual_dur,surface_token,layer_name,jank_type,jank_tag,present_type,process_name,surface_flinger_jank_type
                |42,1000,16666666,1100,18000000,7,SurfaceView,Missed deadline,Self Jank,Late Present,com.example,Missed deadline
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
        assertEquals("Self Jank", frame.states[FRAME_TIMELINE_JANK_TAG])
        assertEquals("Late Present", frame.states[FRAME_TIMELINE_PRESENT_TYPE])
        assertTrue(FrameTimelineTraceAdapter().timelineQuery(null).sql.contains("a.jank_tag AS jank_tag"))
        assertTrue(FrameTimelineTraceAdapter().timelineQuery(null).sql.contains("a.present_type AS present_type"))
    }

    @Test
    fun `normal and stuffing frames are not reported as platform jank and missing actual evidence stays unknown`() {
        val result =
            FrameTimelineTraceAdapter().mapFixture(
                """
                |frame_id,expected_ts,expected_dur,actual_ts,actual_dur,surface_token,layer_name,jank_type,jank_tag,present_type,process_name,surface_flinger_jank_type
                |1,1000,16666666,1100,18000000,7,SurfaceView,None,No Jank,On-time Present,com.example,None
                |2,2000,16666666,2100,28000000,7,SurfaceView,Buffer Stuffing,Buffer Stuffing,Late Present,com.example,None
                |3,3000,16666666,3100,28000000,7,SurfaceView,SurfaceFlinger Stuffing,SurfaceFlinger Stuffing,Late Present,com.example,None
                |4,4000,16666666,[NULL],[NULL],7,SurfaceView,[NULL],[NULL],[NULL],com.example,[NULL]
                |
                """.trimMargin(),
            )

        assertEquals(listOf(false, false, false, null), result.frames.map { it.platformJank })
        assertEquals(
            listOf(
                FrameTimelineStatus.GOOD,
                FrameTimelineStatus.HIGH_LATENCY,
                FrameTimelineStatus.HIGH_LATENCY,
                FrameTimelineStatus.UNKNOWN,
            ),
            result.frames.map { it.frameTimelineStatus() },
        )
    }
}

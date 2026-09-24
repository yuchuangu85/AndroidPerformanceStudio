package com.androidperformancestudio.frame.app

import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.model.FrameSource
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FramePerfettoInspectionRequestTest {
    @Test
    fun `trace request retains the displayed frame identity and timeline evidence`() {
        val trace = Path.of("session.pftrace")
        val sample =
            FrameSample(
                frameId = 42L,
                sessionId = "session",
                source = FrameSource.PERFETTO,
                frameTimelineVsyncId = 123L,
                intendedVsyncNs = 456L,
            )

        val request = framePerfettoInspectionRequest(trace, sample)

        assertEquals(trace, request.traceFile)
        assertEquals(42L, request.frameId)
        assertEquals(123L, request.frameTimelineVsyncId)
        assertEquals(456L, request.intendedVsyncNs)
    }

    @Test
    fun `raw trace opening does not fabricate a frame correlation`() {
        val request = framePerfettoInspectionRequest(Path.of("session.pftrace"), null)

        assertNull(request.frameId)
        assertNull(request.frameTimelineVsyncId)
        assertNull(request.intendedVsyncNs)
    }
}

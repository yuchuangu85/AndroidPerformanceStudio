package com.androidperformancestudio.frame.app

import com.androidperformancestudio.frame.presentation.FrameProfilerState
import com.androidperformancestudio.frame.analysis.FrameJankAnalyzer
import com.androidperformancestudio.frame.model.FrameSample
import com.androidperformancestudio.frame.model.FrameSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrameAiContextTest {
    @Test
    fun `canonical selection preserves metrics missing states and rejects stale ID`() {

        val sample=FrameSample(frameId=1,sessionId="session-1",source=FrameSource.entries.first(),totalDurationNs=12345)
        val state=FrameProfilerState(analysis=FrameJankAnalyzer().analyze(listOf(sample)),selectedFrameId=1)
        val context=frameAiContext(state)!!
        assertTrue(context.currentSelection)
        assertTrue("\"durationNs\":12345" in context.evidence.single().payloadJson)
        assertTrue("\"expectedDurationNs\":null" in context.evidence.single().payloadJson)
        assertEquals(null,frameAiContext(state.copy(selectedFrameId=99)))
        assertEquals(null,frameAiContext(state.copy(isCapturing=true)))
    }
}

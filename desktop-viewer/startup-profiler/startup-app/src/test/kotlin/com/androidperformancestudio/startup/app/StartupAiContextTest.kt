package com.androidperformancestudio.startup.app

import com.androidperformancestudio.startup.presentation.StartupProfilerState
import com.androidperformancestudio.startup.analysis.StartupAnalyzer
import com.androidperformancestudio.startup.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupAiContextTest {
    @Test
    fun `canonical selection preserves metrics missing states and rejects stale ID`() {

        val run=StartupRun("run-1","session-1",1,StartupType.COLD,StartupType.UNKNOWN,PlatformLaunchMetrics(displayedTimeMs=100,fullyDrawnTimeMs=null),rawEvidence=StartupRawEvidence("secret raw output"))
        val state=StartupProfilerState(analysis=StartupAnalyzer().analyze(listOf(run)),selectedRunId="run-1")
        val context=startupAiContext(state)!!
        assertTrue(context.currentSelection)
        assertTrue("\"ttidMs\":100" in context.evidence.single().payloadJson)
        assertTrue("\"ttfdMs\":null" in context.evidence.single().payloadJson)
        assertFalse("secret raw output" in context.evidence.single().payloadJson)
        assertEquals(null,startupAiContext(state.copy(selectedRunId="stale")))
    }
}

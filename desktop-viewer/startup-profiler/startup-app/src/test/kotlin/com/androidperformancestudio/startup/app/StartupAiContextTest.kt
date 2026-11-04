package com.androidperformancestudio.startup.app

import com.androidperformancestudio.startup.presentation.StartupProfilerState
import com.androidperformancestudio.startup.analysis.StartupAnalyzer
import com.androidperformancestudio.startup.model.*
import com.androidperformancestudio.ui.boundedAiContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class StartupAiContextTest {
    @Test
    fun `report includes eight runs and explicit omissions within the shared budget`() {
        val runs = (1..24).map { iteration ->
            StartupRun(
                "run-$iteration", "session-1", iteration, StartupType.COLD, StartupType.UNKNOWN,
                PlatformLaunchMetrics(displayedTimeMs = 100, fullyDrawnTimeMs = null),
                rawEvidence = StartupRawEvidence("secret raw output"),
            )
        }
        val context = assertNotNull(startupAiContext(StartupProfilerState(analysis = StartupAnalyzer().analyze(runs))))
        val payload = context.evidence.single().payloadJson

        assertFalse(context.currentSelection)
        assertTrue("\"runCount\":24" in payload)
        assertTrue("\"includedRuns\":8" in payload)
        assertTrue("\"omittedRuns\":16" in payload)
        assertTrue("\"ttfdMs\":null" in payload)
        assertFalse("secret raw output" in payload)
        assertTrue(payload.toByteArray(Charsets.UTF_8).size <= 8 * 1024)
        assertSame(context, boundedAiContext(context))
    }

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

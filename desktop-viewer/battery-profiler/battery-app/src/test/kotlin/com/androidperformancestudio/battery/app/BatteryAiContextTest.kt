package com.androidperformancestudio.battery.app

import com.androidperformancestudio.battery.analysis.BatteryAnalysisResult
import com.androidperformancestudio.battery.model.BatteryRunDelta
import com.androidperformancestudio.battery.model.BatteryStatistics
import com.androidperformancestudio.battery.model.NetworkUsage
import com.androidperformancestudio.battery.presentation.BatteryProfilerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BatteryAiContextTest {
    @Test
    fun `battery missing counters are not reported as zero and stale runs unavailable`() {
        val run = BatteryRunDelta("run-1", "session-1", 1, 500, emptyList(), emptyList(), emptyList(), emptyList(), NetworkUsage(), emptyList(), emptyList(), emptyList(), networkCountersAvailable = false)
        val missing = BatteryStatistics(0, 1, null, null, null, null, null, null, null, null)
        val analysis = BatteryAnalysisResult(listOf(run), missing, missing, missing, missing, missing, missing, emptyList())
        val state = BatteryProfilerState(analysis = analysis, selectedRunId = run.runId)
        val context = batteryAiContext(state)!!
        assertTrue(context.currentSelection)
        assertTrue("\"durationMs\":500" in context.evidence.single().payloadJson)
        assertTrue("\"networkBytes\":null" in context.evidence.single().payloadJson)
        assertEquals(null, batteryAiContext(state.copy(selectedRunId = "stale")))
        assertEquals(null, batteryAiContext(state.copy(isRunning = true)))
    }
}

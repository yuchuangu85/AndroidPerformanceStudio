package com.androidperformancestudio.battery.app

import com.androidperformancestudio.battery.analysis.BatteryAnalysisResult
import com.androidperformancestudio.battery.model.AttributionScope
import com.androidperformancestudio.battery.model.BatteryRunDelta
import com.androidperformancestudio.battery.model.BatteryStatistics
import com.androidperformancestudio.battery.model.EnergyEstimate
import com.androidperformancestudio.battery.model.EnergyEvidenceKind
import com.androidperformancestudio.battery.model.EvidenceConfidence
import com.androidperformancestudio.battery.model.NetworkUsage
import com.androidperformancestudio.battery.presentation.BatteryProfilerState
import com.androidperformancestudio.ui.boundedAiContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class BatteryAiContextTest {
    @Test
    fun `energy rich reports and selected runs fit the budget with explicit omissions`() {
        val energy = List(20) {
            EnergyEstimate(
                component = "private-component-$it",
                energyMah = Double.MAX_VALUE,
                energyUws = Long.MAX_VALUE,
                source = EnergyEvidenceKind.HISTORY_CORRELATION,
                attributionScope = AttributionScope.SHARED_UID,
                confidence = EvidenceConfidence.UNAVAILABLE,
            )
        }
        val runs = (1..24).map { iteration ->
            BatteryRunDelta(
                "run-$iteration", "session-1", iteration, Long.MAX_VALUE,
                emptyList(), emptyList(), emptyList(), emptyList(), NetworkUsage(), energy, emptyList(), emptyList(),
                peakTemperatureCelsius = Double.MAX_VALUE,
                maxThermalStatus = Int.MAX_VALUE,
                networkCountersAvailable = false,
            )
        }
        val missing = BatteryStatistics(0, 24, null, null, null, null, null, null, null, null)
        val analysis = BatteryAnalysisResult(runs, missing, missing, missing, missing, missing, missing, emptyList())
        val state = BatteryProfilerState(analysis = analysis)
        val context = assertNotNull(batteryAiContext(state))
        val payload = context.evidence.single().payloadJson

        assertFalse(context.currentSelection)
        assertTrue("\"runCount\":24" in payload)
        assertTrue("\"includedRuns\":4" in payload)
        assertTrue("\"omittedRuns\":20" in payload)
        assertEquals(4, Regex("\"energyEntryCount\":20").findAll(payload).count())
        assertEquals(4, Regex("\"omittedEnergyEntries\":12").findAll(payload).count())
        assertTrue("\"networkBytes\":null" in payload)
        assertFalse("private-component" in payload)
        assertTrue(payload.toByteArray(Charsets.UTF_8).size <= 8 * 1024)
        assertSame(context, boundedAiContext(context))

        val selected = assertNotNull(batteryAiContext(state.copy(selectedRunId = "run-24")))
        assertTrue(selected.currentSelection)
        assertTrue("\"includedRuns\":1" in selected.evidence.single().payloadJson)
        assertTrue("\"omittedRuns\":0" in selected.evidence.single().payloadJson)
        assertSame(selected, boundedAiContext(selected))
    }

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

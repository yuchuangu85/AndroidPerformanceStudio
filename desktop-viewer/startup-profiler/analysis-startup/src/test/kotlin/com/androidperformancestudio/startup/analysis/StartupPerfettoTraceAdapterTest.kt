package com.androidperformancestudio.startup.analysis

import com.androidperformancestudio.contracts.ClockDomain
import com.androidperformancestudio.contracts.ClockMapping
import com.androidperformancestudio.startup.model.EvidenceConfidence
import com.androidperformancestudio.startup.model.StartupMilestone
import com.androidperformancestudio.startup.model.StartupMilestoneKind
import com.androidperformancestudio.startup.model.StartupPerfettoSlice
import com.androidperformancestudio.startup.model.StartupSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupPerfettoTraceAdapterTest {
    @Test
    fun `keeps root cause sources distinct and correlates only within the error bound`() {
        val adapter = StartupPerfettoTraceAdapter()
        val rows = "ts,dur,name,thread_name\n100,20,sched,main\n"
        val precise =
            ClockMapping(
                StartupPerfettoTraceAdapter.PERFETTO_TRACE_CLOCK,
                StartupPerfettoTraceAdapter.STARTUP_ELAPSED_REALTIME_CLOCK,
                100,
                100,
                1_000,
                validFromSourceNanos = 90,
                validToSourceNanos = 130,
            )
        val preciseResult = adapter.mapFixture(rows, rows, rows, rows, precise)
        assertTrue(preciseResult.correlated)
        assertTrue(preciseResult.schedulingSlices.single().name == "sched")

        val imprecise = precise.copy(errorBoundNanos = 20_000_000)
        val impreciseResult = adapter.mapFixture(rows, rows, rows, rows, imprecise)
        assertFalse(impreciseResult.correlated)
        assertTrue(impreciseResult.limitations.isNotEmpty())

        assertFalse(adapter.mapFixture(rows, rows, rows, rows, precise.copy(validToSourceNanos = 110)).correlated)
        assertFalse(
            adapter
                .mapFixture(
                    rows,
                    rows,
                    rows,
                    rows,
                    precise.copy(source = ClockDomain("host.monotonic")),
                ).correlated,
        )
        assertTrue(adapter.schedulingQuery(42).sql.contains("p.pid = 42"))
        assertTrue(adapter.wakingQuery(42).sql.contains("target.upid"))
        assertTrue(adapter.mainThreadQuery(42).sql.contains("thread_state"))
    }

    @Test
    fun `attributes mapped Perfetto evidence to adjacent startup phases`() {
        val mapping =
            ClockMapping(
                StartupPerfettoTraceAdapter.PERFETTO_TRACE_CLOCK,
                StartupPerfettoTraceAdapter.STARTUP_ELAPSED_REALTIME_CLOCK,
                sourceReferenceNanos = 100,
                targetReferenceNanos = 1_000,
                errorBoundNanos = 1_000,
                validFromSourceNanos = 100,
                validToSourceNanos = 300,
            )
        val milestones =
            listOf(
                StartupMilestone(
                    StartupMilestoneKind.PROCESS_START,
                    1_000,
                    source = StartupSource.EVENT_LOG,
                    confidence = EvidenceConfidence.EXACT,
                ),
                StartupMilestone(
                    StartupMilestoneKind.FIRST_FRAME,
                    1_100,
                    source = StartupSource.EVENT_LOG,
                    confidence = EvidenceConfidence.EXACT,
                ),
            )
        val result =
            StartupPerfettoTraceAdapter().map(
                evidence =
                    StartupPerfettoEvidenceSlices(
                        scheduling = listOf(StartupPerfettoSlice(110, 30, "sched", "main")),
                        binder = listOf(StartupPerfettoSlice(130, 20, "binder", "main")),
                        mainThread = listOf(StartupPerfettoSlice(150, 10, "S", "main")),
                        frames = listOf(StartupPerfettoSlice(170, 20, "frame")),
                        waking = listOf(StartupPerfettoSlice(125, 0, "sched_waking", "main")),
                        runQueue = listOf(StartupPerfettoSlice(140, 0, "run_queue:2")),
                    ),
                clockMapping = mapping,
                milestones = milestones,
            )

        val phase = result.phaseAttributions.single()
        assertEquals(30, phase.schedulingNs)
        assertEquals(20, phase.binderNs)
        assertEquals(10, phase.mainThreadBlockedNs)
        assertEquals(20, phase.frameNs)
        assertEquals(1, phase.wakingCount)
        assertEquals(1, phase.runQueueSamples)
    }
}

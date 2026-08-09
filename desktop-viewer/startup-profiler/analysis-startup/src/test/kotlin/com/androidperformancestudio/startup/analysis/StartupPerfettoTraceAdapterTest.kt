package com.androidperformancestudio.startup.analysis

import com.androidperformancestudio.contracts.ClockDomain
import com.androidperformancestudio.contracts.ClockMapping
import kotlin.test.Test
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
    }
}

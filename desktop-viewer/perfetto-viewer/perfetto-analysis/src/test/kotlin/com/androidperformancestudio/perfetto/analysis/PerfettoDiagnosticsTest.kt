package com.androidperformancestudio.perfetto.analysis

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PerfettoDiagnosticsTest {
    @Test
    fun `diagnostic ids are stable and unique`() {
        val ids = PerfettoDiagnostics.all.map(DiagnosticQuery::id)

        assertEquals(ids.size, ids.distinct().size)
        assertEquals(
            setOf(
                "cpu_hotspots",
                "cpu_freq_dist",
                "binder_latency",
                "frame_jank",
                "mem_counters",
                "input_latency",
                "thread_states",
                "wakeup_latency",
            ),
            ids.toSet(),
        )
    }

    @Test
    fun `standard library diagnostics declare their modules and avoid like`() {
        val byId = PerfettoDiagnostics.all.associateBy(DiagnosticQuery::id)

        assertTrue(byId.getValue("cpu_freq_dist").sql.contains("INCLUDE PERFETTO MODULE linux.cpu.frequency;"))
        assertTrue(byId.getValue("binder_latency").sql.contains("INCLUDE PERFETTO MODULE android.binder;"))
        assertTrue(byId.getValue("frame_jank").sql.contains("INCLUDE PERFETTO MODULE android.frames.per_frame_metrics;"))
        assertTrue(byId.getValue("mem_counters").sql.contains("INCLUDE PERFETTO MODULE linux.memory.process;"))
        assertTrue(byId.getValue("wakeup_latency").sql.contains("INCLUDE PERFETTO MODULE sched.runnable;"))
        PerfettoDiagnostics.all.forEach { query ->
            assertFalse(Regex("\\bLIKE\\b", RegexOption.IGNORE_CASE).containsMatchIn(query.sql), query.id)
        }
    }
}

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

    @Test
    fun `diagnostics expose pinned typed columns and render mapped rows instead of raw processor output`() {
        val diagnostic = PerfettoDiagnostics.all.first { it.id == "cpu_hotspots" }
        val traceQuery = diagnostic.typedQuery()

        assertEquals(
            listOf("thread_name", "process_name", "slice_count", "total_dur_ms"),
            traceQuery.schema.columns.map { it.name },
        )
        assertEquals(
            "thread_name | process_name | slice_count | total_dur_ms\nmain | example.app | 3 | 42",
            DiagnosticResult(
                columns = traceQuery.schema.columns.map { it.name },
                rows = listOf(DiagnosticRow(listOf("main", "example.app", "3", "42"))),
            ).toPlainText(),
        )
    }
}

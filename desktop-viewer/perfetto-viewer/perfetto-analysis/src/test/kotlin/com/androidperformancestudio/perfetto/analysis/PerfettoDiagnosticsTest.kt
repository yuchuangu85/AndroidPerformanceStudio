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
                "sched_switch",
                "sched_waking",
                "run_queue",
                "main_thread_blocked",
                "cpu_freq_dist",
                "cpu_contention",
                "binder_ipc_long_tail",
                "binder_system_server",
                "binder_latency",
                "binder_server_saturation",
                "binder_wait_chain",
                "binder_frame_alignment",
                "frame_jank",
                "frame_surface_correlation",
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

        assertTrue(byId.getValue("sched_switch").sql.contains("sched_slice"))
        assertTrue(byId.getValue("sched_waking").sql.contains("sched_waking"))
        assertTrue(byId.getValue("run_queue").sql.contains("sched_runnable_thread_count"))
        assertTrue(byId.getValue("run_queue").sql.contains("LEAD(ts) OVER"))
        assertTrue(byId.getValue("main_thread_blocked").sql.contains("is_main_thread"))
        assertTrue(byId.getValue("cpu_freq_dist").sql.contains("INCLUDE PERFETTO MODULE linux.cpu.frequency;"))
        assertTrue(byId.getValue("binder_ipc_long_tail").sql.contains("PERCENTILE"))
        assertTrue(byId.getValue("binder_system_server").sql.contains("system_server"))
        assertTrue(byId.getValue("binder_latency").sql.contains("INCLUDE PERFETTO MODULE android.binder;"))
        assertTrue(byId.getValue("binder_wait_chain").sql.contains("thread_state_dur"))
        assertTrue(byId.getValue("binder_frame_alignment").sql.contains("actual_frame_timeline_slice"))
        assertTrue(byId.getValue("binder_frame_alignment").sql.contains("binder.client_upid = actual.upid"))
        assertTrue(byId.getValue("frame_jank").sql.contains("INCLUDE PERFETTO MODULE android.frames.per_frame_metrics;"))
        assertTrue(byId.getValue("mem_counters").sql.contains("INCLUDE PERFETTO MODULE linux.memory.process;"))
        assertTrue(byId.getValue("wakeup_latency").sql.contains("INCLUDE PERFETTO MODULE sched.runnable;"))
        assertTrue(byId.getValue("cpu_contention").sql.contains("sched_previous_runnable_on_thread"))
        assertTrue(byId.getValue("binder_server_saturation").sql.contains("server_dur"))
        assertTrue(byId.getValue("binder_wait_chain").sql.contains("android_sync_binder_thread_state_by_txn"))
        assertTrue(byId.getValue("frame_surface_correlation").sql.contains("actual_frame_timeline_slice"))
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

package com.androidperformancestudio.perfetto.analysis

enum class DiagnosticCategory(
    val displayName: String,
) {
    CPU("CPU Scheduling"),
    BINDER("Binder Transactions"),
    GRAPHICS("Graphics Pipeline"),
    MEMORY("Memory"),
    INPUT("Input Latency"),
}

data class DiagnosticQuery(
    val id: String,
    val category: DiagnosticCategory,
    val title: String,
    val description: String,
    val sql: String,
)

object PerfettoDiagnostics {
    val all: List<DiagnosticQuery> =
        listOf(
            DiagnosticQuery(
                id = "cpu_hotspots",
                category = DiagnosticCategory.CPU,
                title = "CPU Scheduling Hotspots",
                description = "Threads with the most CPU scheduling time",
                sql =
                    """
                    SELECT thread.name AS thread_name, process.name AS process_name,
                           COUNT(*) AS slice_count,
                           CAST(SUM(dur) / 1e6 AS INTEGER) AS total_dur_ms
                    FROM sched_slice AS sched
                    JOIN thread ON thread.utid = sched.utid
                    LEFT JOIN process ON process.upid = thread.upid
                    WHERE sched.dur > 0
                    GROUP BY sched.utid
                    ORDER BY total_dur_ms DESC
                    LIMIT 20
                    """.trimIndent(),
            ),
            DiagnosticQuery(
                id = "cpu_freq_dist",
                category = DiagnosticCategory.CPU,
                title = "CPU Frequency Distribution",
                description = "Time spent at each CPU frequency",
                sql =
                    """
                    INCLUDE PERFETTO MODULE linux.cpu.frequency;

                    SELECT frequency.cpu AS cpu,
                           CAST(frequency.freq / 1000 AS INTEGER) AS freq_mhz,
                           COUNT(*) AS intervals,
                           ROUND(SUM(frequency.dur) / 1e9, 3) AS total_dur_s
                    FROM cpu_frequency_counters AS frequency
                    WHERE frequency.dur > 0 AND frequency.freq IS NOT NULL
                    GROUP BY frequency.cpu, frequency.freq
                    ORDER BY total_dur_s DESC
                    LIMIT 30
                    """.trimIndent(),
            ),
            DiagnosticQuery(
                id = "binder_latency",
                category = DiagnosticCategory.BINDER,
                title = "Binder Transaction Latency",
                description = "Binder calls with the highest total duration",
                sql =
                    """
                    INCLUDE PERFETTO MODULE android.binder;

                    SELECT binder.client_process AS client,
                           binder.server_process AS server,
                           COUNT(*) AS txn_count,
                           ROUND(AVG(binder.client_dur) / 1e6, 3) AS avg_dur_ms,
                           ROUND(MAX(binder.client_dur) / 1e6, 3) AS max_dur_ms
                    FROM android_binder_txns AS binder
                    WHERE binder.client_dur > 0
                    GROUP BY binder.client_upid, binder.server_upid
                    ORDER BY max_dur_ms DESC
                    LIMIT 20
                    """.trimIndent(),
            ),
            DiagnosticQuery(
                id = "frame_jank",
                category = DiagnosticCategory.GRAPHICS,
                title = "Frame Jank Detection",
                description = "Frames that missed their device-specific FrameTimeline deadline",
                sql =
                    """
                    INCLUDE PERFETTO MODULE android.frames.per_frame_metrics;
                    INCLUDE PERFETTO MODULE android.frames.timeline;

                    SELECT COALESCE(process.name, 'unknown') AS process_name,
                           COUNT(*) AS frame_count,
                           SUM(CASE WHEN stats.was_jank THEN 1 ELSE 0 END) AS janky_frames,
                           ROUND(AVG(frames.dur) / 1e6, 3) AS avg_dur_ms,
                           ROUND(MAX(frames.dur) / 1e6, 3) AS max_dur_ms,
                           ROUND(MAX(stats.overrun) / 1e6, 3) AS max_overrun_ms
                    FROM android_frame_stats AS stats
                    JOIN android_frames AS frames ON frames.frame_id = stats.frame_id
                    LEFT JOIN thread ON thread.utid = frames.ui_thread_utid
                    LEFT JOIN process ON process.upid = thread.upid
                    GROUP BY process.upid, process.name
                    ORDER BY janky_frames DESC
                    LIMIT 20
                    """.trimIndent(),
            ),
            DiagnosticQuery(
                id = "mem_counters",
                category = DiagnosticCategory.MEMORY,
                title = "Memory Usage Timeline",
                description = "Process memory counters over the trace duration",
                sql =
                    """
                    INCLUDE PERFETTO MODULE linux.memory.process;

                    SELECT memory.process_name AS process_name,
                           CAST(MIN(memory.rss) / 1024 AS INTEGER) AS min_kb,
                           CAST(MAX(memory.rss) / 1024 AS INTEGER) AS max_kb,
                           CAST(AVG(memory.rss) / 1024 AS INTEGER) AS avg_kb
                    FROM memory_rss_and_swap_per_process AS memory
                    WHERE memory.rss IS NOT NULL
                    GROUP BY memory.upid, memory.process_name
                    ORDER BY max_kb DESC
                    LIMIT 20
                    """.trimIndent(),
            ),
            DiagnosticQuery(
                id = "input_latency",
                category = DiagnosticCategory.INPUT,
                title = "Input Event Latency",
                description = "Input dispatch to app response time",
                sql =
                    """
                    SELECT COALESCE(process.name, 'unknown') AS process_name,
                           COUNT(*) AS event_count,
                           ROUND(AVG(IIF(slice.dur = -1, trace_end() - slice.ts, slice.dur)) / 1e6, 3) AS avg_dur_ms,
                           ROUND(MAX(IIF(slice.dur = -1, trace_end() - slice.ts, slice.dur)) / 1e6, 3) AS max_dur_ms
                    FROM slice AS slice
                    JOIN thread_track ON thread_track.id = slice.track_id
                    JOIN thread ON thread.utid = thread_track.utid
                    LEFT JOIN process ON process.upid = thread.upid
                    WHERE LOWER(slice.name) GLOB '*input*'
                    GROUP BY process.upid, process.name
                    ORDER BY event_count DESC
                    LIMIT 20
                    """.trimIndent(),
            ),
            DiagnosticQuery(
                id = "thread_states",
                category = DiagnosticCategory.CPU,
                title = "Thread State Breakdown",
                description = "Time spent in each scheduler state (Running/R/S/D)",
                sql =
                    """
                    SELECT thread.name AS thread_name, process.name AS process_name,
                           state.state AS scheduler_state,
                           COUNT(*) AS transitions,
                           ROUND(SUM(IIF(state.dur = -1, trace_end() - state.ts, state.dur)) / 1e6, 3) AS total_dur_ms
                    FROM thread_state AS state
                    JOIN thread ON thread.utid = state.utid
                    LEFT JOIN process ON process.upid = thread.upid
                    WHERE state.dur != 0
                    GROUP BY state.utid, state.state
                    ORDER BY total_dur_ms DESC
                    LIMIT 30
                    """.trimIndent(),
            ),
            DiagnosticQuery(
                id = "wakeup_latency",
                category = DiagnosticCategory.CPU,
                title = "Thread Wakeup Latency",
                description = "Time between waking and running on CPU",
                sql =
                    """
                    INCLUDE PERFETTO MODULE sched.runnable;

                    SELECT thread.name AS thread_name,
                           process.name AS process_name,
                           COUNT(*) AS wakeups,
                           ROUND(AVG(running.ts - runnable.ts) / 1e3, 3) AS avg_latency_us,
                           ROUND(MAX(running.ts - runnable.ts) / 1e3, 3) AS max_latency_us
                    FROM sched_previous_runnable_on_thread AS previous
                    JOIN thread_state AS running ON running.id = previous.id
                    JOIN thread_state AS runnable ON runnable.id = previous.prev_wakeup_runnable_id
                    JOIN thread ON thread.utid = running.utid
                    LEFT JOIN process ON process.upid = thread.upid
                    WHERE running.ts >= runnable.ts
                    GROUP BY running.utid
                    ORDER BY avg_latency_us DESC
                    LIMIT 20
                    """.trimIndent(),
            ),
        )

    fun queriesForCategory(category: DiagnosticCategory): List<DiagnosticQuery> = all.filter { it.category == category }
}

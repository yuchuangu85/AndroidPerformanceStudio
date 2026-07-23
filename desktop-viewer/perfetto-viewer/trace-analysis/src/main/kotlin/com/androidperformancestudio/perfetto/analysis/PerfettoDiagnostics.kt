package com.androidperformancestudio.perfetto.analysis

enum class DiagnosticCategory(val displayName: String) {
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
    val all: List<DiagnosticQuery> = listOf(
        DiagnosticQuery(
            id = "cpu_hotspots",
            category = DiagnosticCategory.CPU,
            title = "CPU Scheduling Hotspots",
            description = "Threads with the most CPU scheduling time",
            sql = """
                SELECT thread.name AS thread_name, process.name AS process_name,
                       COUNT(*) AS slice_count,
                       CAST(SUM(dur) / 1e6 AS INTEGER) AS total_dur_ms
                FROM sched_slice
                JOIN thread USING(utid)
                JOIN process USING(upid)
                WHERE dur > 0
                GROUP BY utid
                ORDER BY total_dur_ms DESC
                LIMIT 20
            """.trimIndent(),
        ),
        DiagnosticQuery(
            id = "cpu_freq_dist",
            category = DiagnosticCategory.CPU,
            title = "CPU Frequency Distribution",
            description = "Time spent at each CPU frequency",
            sql = """
                SELECT cpu, CAST(freq / 1000 AS INTEGER) AS freq_mhz,
                       COUNT(*) AS slices,
                       CAST(SUM(dur) / 1e9 AS INTEGER) AS total_dur_s
                FROM counter
                JOIN cpu_counter_track ON (counter.track_id = cpu_counter_track.id)
                WHERE cpu_counter_track.name = 'cpufreq'
                GROUP BY cpu, freq
                ORDER BY total_dur_s DESC
                LIMIT 30
            """.trimIndent(),
        ),
        DiagnosticQuery(
            id = "binder_latency",
            category = DiagnosticCategory.BINDER,
            title = "Binder Transaction Latency",
            description = "Binder calls with the highest total duration",
            sql = """
                SELECT client_process.name AS client, server_process.name AS server,
                       COUNT(*) AS txn_count,
                       CAST(AVG(dur) / 1e6 AS INTEGER) AS avg_dur_ms,
                       CAST(MAX(dur) / 1e6 AS INTEGER) AS max_dur_ms
                FROM android_binder_txns
                LEFT JOIN process client_process ON (client_process.upid = client_id)
                LEFT JOIN process server_process ON (server_process.upid = server_id)
                WHERE dur > 0
                GROUP BY client_id, server_id
                ORDER BY txn_count DESC
                LIMIT 20
            """.trimIndent(),
        ),
        DiagnosticQuery(
            id = "frame_jank",
            category = DiagnosticCategory.GRAPHICS,
            title = "Frame Jank Detection",
            description = "Frames exceeding vsync deadline (>16.67ms on 60Hz)",
            sql = """
                SELECT process.name AS process_name,
                       COUNT(*) AS frame_count,
                       COUNT(CASE WHEN dur > 16666666 THEN 1 END) AS janky_frames,
                       CAST(AVG(dur) / 1e6 AS INTEGER) AS avg_dur_ms,
                       CAST(MAX(dur) / 1e6 AS INTEGER) AS max_dur_ms
                FROM actual_frame_timeline_slice
                JOIN process USING(upid)
                GROUP BY upid
                HAVING frame_count > 10
                ORDER BY janky_frames DESC
                LIMIT 20
            """.trimIndent(),
        ),
        DiagnosticQuery(
            id = "mem_counters",
            category = DiagnosticCategory.MEMORY,
            title = "Memory Usage Timeline",
            description = "Process memory counters over the trace duration",
            sql = """
                SELECT process.name AS process_name,
                       CAST(MIN(value) / 1024 AS INTEGER) AS min_kb,
                       CAST(MAX(value) / 1024 AS INTEGER) AS max_kb,
                       CAST(AVG(value) / 1024 AS INTEGER) AS avg_kb
                FROM counter
                JOIN process_counter_track ON (counter.track_id = process_counter_track.id)
                JOIN process USING(upid)
                WHERE process_counter_track.name = 'mem.rss'
                GROUP BY upid
                ORDER BY max_kb DESC
                LIMIT 20
            """.trimIndent(),
        ),
        DiagnosticQuery(
            id = "input_latency",
            category = DiagnosticCategory.INPUT,
            title = "Input Event Latency",
            description = "Input dispatch to app response time",
            sql = """
                SELECT process.name AS process_name,
                       COUNT(*) AS event_count,
                       CAST(AVG(dur) / 1e6 AS INTEGER) AS avg_dur_ms,
                       CAST(MAX(dur) / 1e6 AS INTEGER) AS max_dur_ms
                FROM slice
                JOIN thread_track ON (slice.track_id = thread_track.id)
                JOIN thread USING(utid)
                JOIN process USING(upid)
                WHERE slice.name LIKE '%input%'
                GROUP BY upid
                ORDER BY event_count DESC
                LIMIT 20
            """.trimIndent(),
        ),
        DiagnosticQuery(
            id = "thread_states",
            category = DiagnosticCategory.CPU,
            title = "Thread State Breakdown",
            description = "Time spent in each scheduler state (R/S/D)",
            sql = """
                SELECT thread.name AS thread_name, process.name AS process_name,
                       sched_slice.end_state,
                       COUNT(*) AS transitions,
                       CAST(SUM(dur) / 1e6 AS INTEGER) AS total_dur_ms
                FROM sched_slice
                JOIN thread USING(utid)
                JOIN process USING(upid)
                WHERE dur > 0
                GROUP BY utid, end_state
                ORDER BY total_dur_ms DESC
                LIMIT 30
            """.trimIndent(),
        ),
        DiagnosticQuery(
            id = "wakeup_latency",
            category = DiagnosticCategory.CPU,
            title = "Thread Wakeup Latency",
            description = "Time between waking and running on CPU",
            sql = """
                SELECT thread.name AS thread_name,
                       COUNT(*) AS wakeups,
                       CAST(AVG(wakeup_latency_ns) / 1e3 AS INTEGER) AS avg_latency_us,
                       CAST(MAX(wakeup_latency_ns) / 1e3 AS INTEGER) AS max_latency_us
                FROM (
                    SELECT utid,
                    (next.ts - prev.ts) AS wakeup_latency_ns
                    FROM (SELECT *, LAG(ts) OVER (PARTITION BY utid ORDER BY ts) as prev_ts FROM sched_waking) sub
                    JOIN sched_slice ON (sched_slice.utid = sub.utid)
                )
                JOIN thread USING(utid)
                WHERE wakeup_latency_ns > 0
                GROUP BY utid
                ORDER BY avg_latency_us DESC
                LIMIT 20
            """.trimIndent(),
        ),
    )

    fun queriesForCategory(category: DiagnosticCategory): List<DiagnosticQuery> =
        all.filter { it.category == category }
}

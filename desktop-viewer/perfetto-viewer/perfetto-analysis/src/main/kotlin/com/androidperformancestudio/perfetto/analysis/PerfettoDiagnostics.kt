@file:Suppress("LargeClass", "SpreadOperator")

package com.androidperformancestudio.perfetto.analysis

import com.androidperformancestudio.platform.perfetto.TraceColumn
import com.androidperformancestudio.platform.perfetto.TraceQuery
import com.androidperformancestudio.platform.perfetto.TraceQuerySchema

enum class DiagnosticCategory(
    val displayName: String,
) {
    CPU("CPU Scheduling"),
    BINDER("Binder Transactions"),
    GRAPHICS("Graphics Pipeline"),
    MEMORY("Memory"),
    INPUT("Input Latency"),
    IO("I/O and SQLite"),
    RUNTIME("ART Runtime"),
    POWER("Thermal and Power"),
}

data class DiagnosticQuery(
    val id: String,
    val category: DiagnosticCategory,
    val title: String,
    val description: String,
    val columns: List<String>,
    val sql: String,
) {
    init {
        require(columns.isNotEmpty() && columns.distinct().size == columns.size) { "diagnostic columns must be unique" }
    }

    fun typedQuery(): TraceQuery<DiagnosticRow> {
        val typedColumns = columns.map { name -> TraceColumn.string(name) }
        return TraceQuery(sql, TraceQuerySchema.v57_2(*typedColumns.toTypedArray())) { row ->
            DiagnosticRow(typedColumns.map { column -> row[column] })
        }
    }
}

data class DiagnosticRow(
    val values: List<String?>,
)

data class DiagnosticResult(
    val columns: List<String>,
    val rows: List<DiagnosticRow>,
) {
    fun toPlainText(): String =
        buildList {
            add(columns.joinToString(" | "))
            rows.forEach { row -> add(row.values.joinToString(" | ") { it ?: "unknown" }) }
        }.joinToString("\n")
}

object PerfettoDiagnostics {
    val all: List<DiagnosticQuery> =
        listOf(
            DiagnosticQuery(
                id = "cpu_hotspots",
                category = DiagnosticCategory.CPU,
                title = "CPU Scheduling Hotspots",
                description = "Threads with the most CPU scheduling time",
                columns = listOf("thread_name", "process_name", "slice_count", "total_dur_ms"),
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
                id = "sched_switch",
                category = DiagnosticCategory.CPU,
                title = "sched_switch Timeline",
                description = "Running slices and their scheduler end state",
                columns = listOf("timestamp_ns", "duration_ns", "cpu", "thread_name", "process_name", "end_state"),
                sql =
                    """
                    SELECT sched.ts AS timestamp_ns,
                           sched.dur AS duration_ns,
                           sched.cpu AS cpu,
                           thread.name AS thread_name,
                           process.name AS process_name,
                           sched.end_state AS end_state
                    FROM sched_slice AS sched
                    JOIN thread ON thread.utid = sched.utid
                    LEFT JOIN process ON process.upid = thread.upid
                    WHERE sched.dur >= 0
                    ORDER BY sched.ts
                    LIMIT 2000
                    """.trimIndent(),
            ),
            DiagnosticQuery(
                id = "sched_waking",
                category = DiagnosticCategory.CPU,
                title = "sched_waking Events",
                description = "Threads woken by another thread and their target CPU",
                columns = listOf("timestamp_ns", "cpu", "target_cpu", "waker_thread", "target_thread", "success"),
                sql =
                    """
                    WITH waking AS (
                      SELECT event.id, event.ts, event.cpu, event.utid,
                             MAX(CASE WHEN args.key = 'target_cpu' THEN args.int_value END) AS target_cpu,
                             MAX(CASE WHEN args.key = 'pid' THEN args.int_value END) AS target_pid,
                             MAX(CASE WHEN args.key = 'success' THEN args.int_value END) AS success
                      FROM ftrace_event AS event
                      LEFT JOIN args ON args.arg_set_id = event.arg_set_id
                      WHERE event.name = 'sched_waking'
                      GROUP BY event.id, event.ts, event.cpu, event.utid
                    )
                    SELECT waking.ts AS timestamp_ns,
                           waking.cpu AS cpu,
                           waking.target_cpu AS target_cpu,
                           waker.name AS waker_thread,
                           target.name AS target_thread,
                           waking.success AS success
                    FROM waking
                    LEFT JOIN thread AS waker ON waker.utid = waking.utid
                    LEFT JOIN thread AS target
                      ON target.tid = waking.target_pid
                     AND (target.start_ts IS NULL OR target.start_ts <= waking.ts)
                     AND (target.end_ts IS NULL OR waking.ts < target.end_ts)
                    ORDER BY waking.ts
                    LIMIT 2000
                    """.trimIndent(),
            ),
            DiagnosticQuery(
                id = "run_queue",
                category = DiagnosticCategory.CPU,
                title = "Run Queue Pressure",
                description = "Runnable thread count over time, including high-pressure windows",
                columns = listOf("timestamp_ns", "runnable_threads", "next_timestamp_ns", "run_queue_ms"),
                sql =
                    """
                    INCLUDE PERFETTO MODULE sched.thread_level_parallelism;

                    WITH queue AS (
                      SELECT ts,
                             runnable_thread_count,
                             LEAD(ts) OVER (ORDER BY ts) AS next_ts
                      FROM sched_runnable_thread_count
                    )
                    SELECT queue.ts AS timestamp_ns,
                           queue.runnable_thread_count AS runnable_threads,
                           queue.next_ts AS next_timestamp_ns,
                           ROUND((queue.next_ts - queue.ts) / 1e6, 3) AS run_queue_ms
                    FROM queue
                    WHERE queue.runnable_thread_count > 0
                    ORDER BY queue.runnable_thread_count DESC, queue.ts
                    LIMIT 2000
                    """.trimIndent(),
            ),
            DiagnosticQuery(
                id = "main_thread_blocked",
                category = DiagnosticCategory.CPU,
                title = "Main Thread Blocking",
                description = "Main-thread sleeping and uninterruptible blocking intervals",
                columns =
                    listOf(
                        "thread_name",
                        "process_name",
                        "state",
                        "blocked_function",
                        "block_count",
                        "total_blocked_ms",
                        "max_blocked_ms",
                    ),
                sql =
                    """
                    SELECT thread.name AS thread_name,
                           process.name AS process_name,
                           state.state AS state,
                           state.blocked_function AS blocked_function,
                           COUNT(*) AS block_count,
                           ROUND(SUM(IIF(state.dur = -1, trace_end() - state.ts, state.dur)) / 1e6, 3) AS total_blocked_ms,
                           ROUND(MAX(IIF(state.dur = -1, trace_end() - state.ts, state.dur)) / 1e6, 3) AS max_blocked_ms
                    FROM thread_state AS state
                    JOIN thread ON thread.utid = state.utid
                    LEFT JOIN process ON process.upid = thread.upid
                    WHERE thread.is_main_thread = 1 AND state.state IN ('S', 'D', 'S+')
                    GROUP BY state.utid, state.state, state.blocked_function
                    ORDER BY total_blocked_ms DESC
                    LIMIT 30
                    """.trimIndent(),
            ),
            DiagnosticQuery(
                id = "cpu_freq_dist",
                category = DiagnosticCategory.CPU,
                title = "CPU Frequency Distribution",
                description = "Time spent at each CPU frequency",
                columns = listOf("cpu", "freq_mhz", "intervals", "total_dur_s"),
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
                id = "cpu_contention",
                category = DiagnosticCategory.CPU,
                title = "CPU Contention",
                description = "Threads delayed between wakeup and actual execution",
                columns = listOf("thread_name", "process_name", "contention_count", "avg_wait_ms", "max_wait_ms"),
                sql =
                    """
                    INCLUDE PERFETTO MODULE sched.runnable;

                    SELECT thread.name AS thread_name,
                           process.name AS process_name,
                           COUNT(*) AS contention_count,
                           ROUND(AVG(running.ts - runnable.ts) / 1e6, 3) AS avg_wait_ms,
                           ROUND(MAX(running.ts - runnable.ts) / 1e6, 3) AS max_wait_ms
                    FROM sched_previous_runnable_on_thread AS previous
                    JOIN thread_state AS running ON running.id = previous.id
                    JOIN thread_state AS runnable ON runnable.id = previous.prev_wakeup_runnable_id
                    JOIN thread ON thread.utid = running.utid
                    LEFT JOIN process ON process.upid = thread.upid
                    WHERE running.ts > runnable.ts
                    GROUP BY running.utid
                    ORDER BY max_wait_ms DESC
                    LIMIT 30
                    """.trimIndent(),
            ),
            DiagnosticQuery(
                id = "binder_ipc_long_tail",
                category = DiagnosticCategory.BINDER,
                title = "Binder IPC Long Tail",
                description = "Caller, server, AIDL method and long-tail Binder transaction latency",
                columns =
                    listOf(
                        "client",
                        "client_thread",
                        "server",
                        "server_thread",
                        "interface",
                        "method",
                        "is_main_thread",
                        "txn_count",
                        "p50_ms",
                        "p95_ms",
                        "max_ms",
                    ),
                sql =
                    """
                    INCLUDE PERFETTO MODULE android.binder;

                    SELECT binder.client_process AS client,
                           binder.client_thread AS client_thread,
                           binder.server_process AS server,
                           binder.server_thread AS server_thread,
                           binder.interface AS interface,
                           binder.method_name AS method,
                           binder.is_main_thread AS is_main_thread,
                           COUNT(*) AS txn_count,
                           ROUND(PERCENTILE(binder.client_dur, 50) / 1e6, 3) AS p50_ms,
                           ROUND(PERCENTILE(binder.client_dur, 95) / 1e6, 3) AS p95_ms,
                           ROUND(MAX(binder.client_dur) / 1e6, 3) AS max_ms
                    FROM android_binder_txns AS binder
                    WHERE binder.client_dur > 0
                    GROUP BY binder.client_process, binder.client_thread, binder.server_process,
                             binder.server_thread, binder.interface, binder.method_name, binder.is_main_thread
                    ORDER BY max_ms DESC
                    LIMIT 50
                    """.trimIndent(),
            ),
            DiagnosticQuery(
                id = "binder_system_server",
                category = DiagnosticCategory.BINDER,
                title = "system_server IPC",
                description = "Binder transactions crossing into system_server, including client and server time",
                columns = listOf("client", "client_thread", "interface", "method", "txn_count", "client_ms", "server_ms", "max_client_ms"),
                sql =
                    """
                    INCLUDE PERFETTO MODULE android.binder;

                    SELECT binder.client_process AS client,
                           binder.client_thread AS client_thread,
                           binder.interface AS interface,
                           binder.method_name AS method,
                           COUNT(*) AS txn_count,
                           ROUND(SUM(binder.client_dur) / 1e6, 3) AS client_ms,
                           ROUND(SUM(binder.server_dur) / 1e6, 3) AS server_ms,
                           ROUND(MAX(binder.client_dur) / 1e6, 3) AS max_client_ms
                    FROM android_binder_txns AS binder
                    WHERE lower(COALESCE(binder.server_process, '')) = 'system_server'
                    GROUP BY binder.client_process, binder.client_thread, binder.interface, binder.method_name
                    ORDER BY max_client_ms DESC
                    LIMIT 50
                    """.trimIndent(),
            ),
            DiagnosticQuery(
                id = "binder_server_saturation",
                category = DiagnosticCategory.BINDER,
                title = "Binder Server Saturation",
                description = "Server-side Binder time grouped by service process",
                columns = listOf("server", "txn_count", "total_server_ms", "avg_server_ms", "max_server_ms"),
                sql =
                    """
                    INCLUDE PERFETTO MODULE android.binder;

                    SELECT binder.server_process AS server,
                           COUNT(*) AS txn_count,
                           ROUND(SUM(binder.server_dur) / 1e6, 3) AS total_server_ms,
                           ROUND(AVG(binder.server_dur) / 1e6, 3) AS avg_server_ms,
                           ROUND(MAX(binder.server_dur) / 1e6, 3) AS max_server_ms
                    FROM android_binder_txns AS binder
                    WHERE binder.server_dur > 0
                    GROUP BY binder.server_upid, binder.server_process
                    ORDER BY total_server_ms DESC
                    LIMIT 20
                    """.trimIndent(),
            ),
            DiagnosticQuery(
                id = "binder_wait_chain",
                category = DiagnosticCategory.BINDER,
                title = "Binder Wait Chain",
                description = "Thread states observed while serving or waiting for Binder transactions",
                columns = listOf("thread_state_type", "state", "txn_count", "total_dur_ms", "max_dur_ms"),
                sql =
                    """
                    INCLUDE PERFETTO MODULE android.binder;

                    SELECT state.thread_state_type AS thread_state_type,
                           state.thread_state AS state,
                           SUM(state.thread_state_count) AS txn_count,
                           ROUND(SUM(state.thread_state_dur) / 1e6, 3) AS total_dur_ms,
                           ROUND(MAX(state.thread_state_dur) / 1e6, 3) AS max_dur_ms
                    FROM android_sync_binder_thread_state_by_txn AS state
                    WHERE state.thread_state_dur > 0
                    GROUP BY state.thread_state_type, state.thread_state
                    ORDER BY total_dur_ms DESC
                    LIMIT 30
                    """.trimIndent(),
            ),
            DiagnosticQuery(
                id = "binder_latency",
                category = DiagnosticCategory.BINDER,
                title = "Binder Transaction Latency",
                description = "Binder calls with the highest total duration",
                columns = listOf("client", "server", "txn_count", "avg_dur_ms", "max_dur_ms"),
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
                id = "binder_frame_alignment",
                category = DiagnosticCategory.GRAPHICS,
                title = "Binder / FrameTimeline Alignment",
                description = "Binder calls overlapping an app frame and its jank classification",
                columns = listOf("frame_id", "layer_name", "jank_type", "client", "server", "method", "binder_ms", "frame_overrun_ms"),
                sql =
                    """
                    INCLUDE PERFETTO MODULE android.binder;
                    INCLUDE PERFETTO MODULE android.frames.timeline;

                    SELECT actual.display_frame_token AS frame_id,
                           COALESCE(actual.layer_name, expected.layer_name) AS layer_name,
                           actual.jank_type AS jank_type,
                           binder.client_process AS client,
                           binder.server_process AS server,
                           binder.method_name AS method,
                           ROUND(binder.client_dur / 1e6, 3) AS binder_ms,
                           ROUND((actual.dur - expected.dur) / 1e6, 3) AS frame_overrun_ms
                    FROM expected_frame_timeline_slice AS expected
                    JOIN actual_frame_timeline_slice AS actual
                      ON actual.display_frame_token = expected.display_frame_token
                     AND actual.surface_frame_token IS expected.surface_frame_token
                     AND actual.upid IS expected.upid
                    JOIN android_binder_txns AS binder
                      ON binder.client_upid = actual.upid
                     AND binder.client_ts < actual.ts + actual.dur
                     AND binder.client_ts + binder.client_dur > actual.ts
                    WHERE actual.display_frame_token IS NOT NULL
                    ORDER BY frame_overrun_ms DESC, binder_ms DESC
                    LIMIT 100
                    """.trimIndent(),
            ),
            DiagnosticQuery(
                id = "frame_jank",
                category = DiagnosticCategory.GRAPHICS,
                title = "Frame Jank Detection",
                description = "Frames that missed their device-specific FrameTimeline deadline",
                columns = listOf("process_name", "frame_count", "janky_frames", "avg_dur_ms", "max_dur_ms", "max_overrun_ms"),
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
                id = "frame_surface_correlation",
                category = DiagnosticCategory.GRAPHICS,
                title = "Frame / SurfaceFlinger Correlation",
                description = "FrameTimeline jank grouped by application layer and SurfaceFlinger",
                columns = listOf("layer_name", "frame_count", "janky_frames", "surface_flinger_janky_frames", "max_dur_ms"),
                sql =
                    """
                    INCLUDE PERFETTO MODULE android.frames.timeline;

                    SELECT COALESCE(frames.layer_name, 'unknown') AS layer_name,
                           COUNT(*) AS frame_count,
                           SUM(CASE WHEN frames.jank_type IS NOT NULL AND lower(frames.jank_type) != 'on time' THEN 1 ELSE 0 END) AS janky_frames,
                           SUM(CASE WHEN lower(COALESCE(process.name, '')) GLOB '*surfaceflinger*'
                                    AND frames.jank_type IS NOT NULL
                                    AND lower(frames.jank_type) != 'on time' THEN 1 ELSE 0 END) AS surface_flinger_janky_frames,
                           ROUND(MAX(frames.dur) / 1e6, 3) AS max_dur_ms
                    FROM actual_frame_timeline_slice AS frames
                    LEFT JOIN process ON process.upid = frames.upid
                    WHERE frames.display_frame_token IS NOT NULL
                    GROUP BY frames.layer_name
                    ORDER BY janky_frames DESC, max_dur_ms DESC
                    LIMIT 30
                    """.trimIndent(),
            ),
            DiagnosticQuery(
                id = "file_io_syscalls",
                category = DiagnosticCategory.IO,
                title = "File Read / Write Syscalls",
                description = "Read, write, pread, pwrite and sync syscall events by process and thread",
                columns = listOf("operation", "process_name", "thread_name", "event_count"),
                sql =
                    """
                    SELECT event.name AS operation,
                           process.name AS process_name,
                           thread.name AS thread_name,
                           COUNT(*) AS event_count
                    FROM ftrace_event AS event
                    LEFT JOIN thread ON thread.utid = event.utid
                    LEFT JOIN process ON process.upid = thread.upid
                    WHERE event.name IN (
                      'sys_enter_read', 'sys_enter_pread64', 'sys_enter_readv',
                      'sys_enter_write', 'sys_enter_pwrite64', 'sys_enter_writev'
                    )
                    GROUP BY event.name, process.upid, thread.utid
                    ORDER BY event_count DESC
                    LIMIT 100
                    """.trimIndent(),
            ),
            DiagnosticQuery(
                id = "fsync_calls",
                category = DiagnosticCategory.IO,
                title = "fsync / fdatasync Activity",
                description = "Durability sync syscall events that can stall application threads",
                columns = listOf("process_name", "thread_name", "operation", "event_count"),
                sql =
                    """
                    SELECT process.name AS process_name,
                           thread.name AS thread_name,
                           event.name AS operation,
                           COUNT(*) AS event_count
                    FROM ftrace_event AS event
                    LEFT JOIN thread ON thread.utid = event.utid
                    LEFT JOIN process ON process.upid = thread.upid
                    WHERE event.name IN ('sys_enter_fsync', 'sys_enter_fdatasync', 'sys_enter_syncfs')
                    GROUP BY process.upid, thread.utid, event.name
                    ORDER BY event_count DESC
                    LIMIT 100
                    """.trimIndent(),
            ),
            DiagnosticQuery(
                id = "block_io_pressure",
                category = DiagnosticCategory.IO,
                title = "Block I/O Queue Pressure",
                description = "Outstanding block requests by device over time",
                columns = listOf("timestamp_ns", "device", "outstanding_operations"),
                sql =
                    """
                    INCLUDE PERFETTO MODULE linux.block_io;

                    SELECT ts AS timestamp_ns,
                           dev AS device,
                           ops_in_queue_or_device AS outstanding_operations
                    FROM linux_active_block_io_operations_by_device
                    WHERE ops_in_queue_or_device > 0
                    ORDER BY outstanding_operations DESC, ts
                    LIMIT 2000
                    """.trimIndent(),
            ),
            DiagnosticQuery(
                id = "page_faults",
                category = DiagnosticCategory.IO,
                title = "Page Fault Activity",
                description = "User and kernel page-fault events by process and thread",
                columns = listOf("process_name", "thread_name", "fault_type", "fault_count"),
                sql =
                    """
                    SELECT process.name AS process_name,
                           thread.name AS thread_name,
                           event.name AS fault_type,
                           COUNT(*) AS fault_count
                    FROM ftrace_event AS event
                    LEFT JOIN thread ON thread.utid = event.utid
                    LEFT JOIN process ON process.upid = thread.upid
                    WHERE event.name GLOB '*page_fault*'
                    GROUP BY process.upid, thread.utid, event.name
                    ORDER BY fault_count DESC
                    LIMIT 100
                    """.trimIndent(),
            ),
            DiagnosticQuery(
                id = "sqlite_activity",
                category = DiagnosticCategory.IO,
                title = "SQLite Query / Transaction Activity",
                description = "SQLite queries, transactions, WAL checkpoints and database work slices",
                columns = listOf("process_name", "thread_name", "operation", "count", "total_ms", "max_ms"),
                sql =
                    """
                    SELECT process.name AS process_name,
                           thread.name AS thread_name,
                           slice.name AS operation,
                           COUNT(*) AS count,
                           ROUND(SUM(IIF(slice.dur = -1, trace_end() - slice.ts, slice.dur)) / 1e6, 3) AS total_ms,
                           ROUND(MAX(IIF(slice.dur = -1, trace_end() - slice.ts, slice.dur)) / 1e6, 3) AS max_ms
                    FROM slice
                    JOIN thread_track ON thread_track.id = slice.track_id
                    JOIN thread ON thread.utid = thread_track.utid
                    LEFT JOIN process ON process.upid = thread.upid
                    WHERE LOWER(slice.name) GLOB '*sqlite*'
                       OR LOWER(slice.name) GLOB '*wal*checkpoint*'
                    GROUP BY process.upid, thread.utid, slice.name
                    ORDER BY total_ms DESC
                    LIMIT 100
                    """.trimIndent(),
            ),
            DiagnosticQuery(
                id = "sqlite_lock_wait",
                category = DiagnosticCategory.IO,
                title = "SQLite Lock Waits",
                description = "SQLite/database lock and busy waits observed in application slices",
                columns = listOf("process_name", "thread_name", "wait_name", "wait_count", "total_wait_ms", "max_wait_ms"),
                sql =
                    """
                    SELECT process.name AS process_name,
                           thread.name AS thread_name,
                           slice.name AS wait_name,
                           COUNT(*) AS wait_count,
                           ROUND(SUM(IIF(slice.dur = -1, trace_end() - slice.ts, slice.dur)) / 1e6, 3) AS total_wait_ms,
                           ROUND(MAX(IIF(slice.dur = -1, trace_end() - slice.ts, slice.dur)) / 1e6, 3) AS max_wait_ms
                    FROM slice
                    JOIN thread_track ON thread_track.id = slice.track_id
                    JOIN thread ON thread.utid = thread_track.utid
                    LEFT JOIN process ON process.upid = thread.upid
                    WHERE (LOWER(slice.name) GLOB '*sqlite*' OR LOWER(slice.name) GLOB '*database*')
                      AND (LOWER(slice.name) GLOB '*lock*' OR LOWER(slice.name) GLOB '*busy*')
                    GROUP BY process.upid, thread.utid, slice.name
                    ORDER BY total_wait_ms DESC
                    LIMIT 100
                    """.trimIndent(),
            ),
            DiagnosticQuery(
                id = "art_gc_events",
                category = DiagnosticCategory.RUNTIME,
                title = "ART Garbage Collection Pauses",
                description = "GC duration, CPU wait, I/O wait and reclaimed heap by process",
                columns =
                    listOf(
                        "process_name",
                        "gc_type",
                        "gc_count",
                        "total_gc_ms",
                        "max_gc_ms",
                        "reclaimed_mb",
                        "runnable_ms",
                        "io_wait_ms",
                    ),
                sql =
                    """
                    INCLUDE PERFETTO MODULE android.garbage_collection;

                    SELECT process_name,
                           gc_type,
                           COUNT(*) AS gc_count,
                           ROUND(SUM(gc_dur) / 1e6, 3) AS total_gc_ms,
                           ROUND(MAX(gc_dur) / 1e6, 3) AS max_gc_ms,
                           ROUND(SUM(reclaimed_mb), 3) AS reclaimed_mb,
                           ROUND(SUM(gc_runnable_dur) / 1e6, 3) AS runnable_ms,
                           ROUND(SUM(gc_unint_io_dur) / 1e6, 3) AS io_wait_ms
                    FROM android_garbage_collection_events
                    GROUP BY upid, process_name, gc_type
                    ORDER BY total_gc_ms DESC
                    LIMIT 100
                    """.trimIndent(),
            ),
            DiagnosticQuery(
                id = "art_allocation_churn",
                category = DiagnosticCategory.RUNTIME,
                title = "ART Allocation Churn",
                description = "Heap allocation rate, utilization and GC CPU rate by process",
                columns =
                    listOf(
                        "process_name",
                        "allocation_mb",
                        "allocation_rate_mb_s",
                        "heap_size_mb",
                        "heap_utilization",
                        "gc_cpu_rate",
                    ),
                sql =
                    """
                    INCLUDE PERFETTO MODULE android.garbage_collection;

                    SELECT process.name AS process_name,
                           ROUND(stats.heap_allocated_mb, 3) AS allocation_mb,
                           ROUND(stats.heap_allocation_rate, 3) AS allocation_rate_mb_s,
                           ROUND(stats.heap_size_mb, 3) AS heap_size_mb,
                           ROUND(stats.heap_utilization * 100, 3) AS heap_utilization,
                           ROUND(stats.gc_running_rate * 100, 3) AS gc_cpu_rate
                    FROM _android_garbage_collection_process_stats AS stats
                    LEFT JOIN process USING (upid)
                    ORDER BY allocation_rate_mb_s DESC
                    LIMIT 100
                    """.trimIndent(),
            ),
            DiagnosticQuery(
                id = "art_jit_dex2oat",
                category = DiagnosticCategory.RUNTIME,
                title = "JIT / dex2oat Activity",
                description = "JIT compilation and dex2oat slices with thread and process attribution",
                columns = listOf("process_name", "thread_name", "operation", "count", "total_ms", "max_ms"),
                sql =
                    """
                    SELECT process.name AS process_name,
                           thread.name AS thread_name,
                           slice.name AS operation,
                           COUNT(*) AS count,
                           ROUND(SUM(IIF(slice.dur = -1, trace_end() - slice.ts, slice.dur)) / 1e6, 3) AS total_ms,
                           ROUND(MAX(IIF(slice.dur = -1, trace_end() - slice.ts, slice.dur)) / 1e6, 3) AS max_ms
                    FROM slice
                    JOIN thread_track ON thread_track.id = slice.track_id
                    JOIN thread ON thread.utid = thread_track.utid
                    LEFT JOIN process ON process.upid = thread.upid
                    WHERE LOWER(slice.name) GLOB '*jit*compil*'
                       OR LOWER(process.name) GLOB '*dex2oat*'
                       OR LOWER(slice.name) GLOB '*dex2oat*'
                    GROUP BY process.upid, thread.utid, slice.name
                    ORDER BY total_ms DESC
                    LIMIT 100
                    """.trimIndent(),
            ),
            DiagnosticQuery(
                id = "art_class_loading",
                category = DiagnosticCategory.RUNTIME,
                title = "Class Loading During Startup",
                description = "ART class loading slices associated with Android startup windows",
                columns = listOf("startup_id", "class_name", "thread_name", "count", "total_ms", "max_ms"),
                sql =
                    """
                    INCLUDE PERFETTO MODULE android.startup.startups;

                    SELECT startup_id,
                           slice_name AS class_name,
                           thread_name,
                           COUNT(*) AS count,
                           ROUND(SUM(slice_dur) / 1e6, 3) AS total_ms,
                           ROUND(MAX(slice_dur) / 1e6, 3) AS max_ms
                    FROM android_class_loading_for_startup
                    GROUP BY startup_id, slice_name, thread_name
                    ORDER BY total_ms DESC
                    LIMIT 100
                    """.trimIndent(),
            ),
            DiagnosticQuery(
                id = "art_class_verification",
                category = DiagnosticCategory.RUNTIME,
                title = "Class Verification / Initialization",
                description = "Class verification, linking and initialization slices",
                columns = listOf("process_name", "thread_name", "operation", "count", "total_ms", "max_ms"),
                sql =
                    """
                    SELECT process.name AS process_name,
                           thread.name AS thread_name,
                           slice.name AS operation,
                           COUNT(*) AS count,
                           ROUND(SUM(IIF(slice.dur = -1, trace_end() - slice.ts, slice.dur)) / 1e6, 3) AS total_ms,
                           ROUND(MAX(IIF(slice.dur = -1, trace_end() - slice.ts, slice.dur)) / 1e6, 3) AS max_ms
                    FROM slice
                    JOIN thread_track ON thread_track.id = slice.track_id
                    JOIN thread ON thread.utid = thread_track.utid
                    LEFT JOIN process ON process.upid = thread.upid
                    WHERE LOWER(slice.name) GLOB '*verifyclass*'
                       OR LOWER(slice.name) GLOB '*classlinker*'
                       OR LOWER(slice.name) GLOB '*initializeclass*'
                       OR LOWER(slice.name) GLOB '*<clinit>*'
                    GROUP BY process.upid, thread.utid, slice.name
                    ORDER BY total_ms DESC
                    LIMIT 100
                    """.trimIndent(),
            ),
            DiagnosticQuery(
                id = "thermal_throttling",
                category = DiagnosticCategory.POWER,
                title = "Thermal / Throttling Signals",
                description = "Thermal zone, cooling device and throttling counters over time",
                columns = listOf("timestamp_ns", "signal", "value"),
                sql =
                    """
                    SELECT counter.ts AS timestamp_ns,
                           track.name AS signal,
                           counter.value AS value
                    FROM counter
                    JOIN counter_track AS track ON track.id = counter.track_id
                    WHERE LOWER(track.name) GLOB '*thermal*'
                       OR LOWER(track.name) GLOB '*throttl*'
                       OR LOWER(track.name) GLOB '*cooling*'
                    ORDER BY counter.ts
                    LIMIT 2000
                    """.trimIndent(),
            ),
            DiagnosticQuery(
                id = "dvfs_residency",
                category = DiagnosticCategory.POWER,
                title = "DVFS Frequency Residency",
                description = "Time and percentage spent in CPU, memory and interconnect DVFS states",
                columns = listOf("domain", "frequency", "duration_ms", "percent"),
                sql =
                    """
                    INCLUDE PERFETTO MODULE android.dvfs;

                    SELECT name AS domain,
                           value AS frequency,
                           ROUND(dur / 1e6, 3) AS duration_ms,
                           ROUND(pct, 3) AS percent
                    FROM android_dvfs_counter_residency
                    ORDER BY domain, duration_ms DESC
                    LIMIT 500
                    """.trimIndent(),
            ),
            DiagnosticQuery(
                id = "gpu_frequency_residency",
                category = DiagnosticCategory.POWER,
                title = "GPU Frequency Residency",
                description = "GPU frequency intervals and residency duration",
                columns = listOf("gpu_id", "frequency", "duration_ms", "timestamp_ns"),
                sql =
                    """
                    INCLUDE PERFETTO MODULE android.gpu.frequency;

                    SELECT gpu_id,
                           gpu_freq AS frequency,
                           ROUND(dur / 1e6, 3) AS duration_ms,
                           ts AS timestamp_ns
                    FROM android_gpu_frequency
                    WHERE dur > 0
                    ORDER BY duration_ms DESC
                    LIMIT 500
                    """.trimIndent(),
            ),
            DiagnosticQuery(
                id = "power_rails",
                category = DiagnosticCategory.POWER,
                title = "Power Rail Energy",
                description = "Hardware power rail energy delta and average power",
                columns = listOf("rail", "subsystem", "energy_uws", "average_power_mw", "duration_ms"),
                sql =
                    """
                    INCLUDE PERFETTO MODULE android.power_rails;

                    SELECT counters.power_rail_name AS rail,
                           metadata.subsystem_name AS subsystem,
                           ROUND(SUM(counters.energy_delta), 3) AS energy_uws,
                           ROUND(AVG(counters.average_power), 3) AS average_power_mw,
                           ROUND(SUM(counters.dur) / 1e6, 3) AS duration_ms
                    FROM android_power_rails_counters AS counters
                    LEFT JOIN android_power_rails_metadata AS metadata USING (track_id)
                    WHERE counters.dur > 0
                    GROUP BY counters.track_id, counters.power_rail_name, metadata.subsystem_name
                    ORDER BY energy_uws DESC
                    LIMIT 100
                    """.trimIndent(),
            ),
            DiagnosticQuery(
                id = "power_frame_alignment",
                category = DiagnosticCategory.POWER,
                title = "Power / FrameTimeline Alignment",
                description = "Power rail intervals overlapping app or display frames",
                columns = listOf("frame_id", "layer_name", "jank_type", "rail", "average_power_mw", "overlap_ms"),
                sql =
                    """
                    INCLUDE PERFETTO MODULE android.power_rails;

                    SELECT frame.display_frame_token AS frame_id,
                           frame.layer_name,
                           frame.jank_type,
                           rail.power_rail_name AS rail,
                           ROUND(rail.average_power, 3) AS average_power_mw,
                           ROUND((MIN(frame.ts + frame.dur, rail.ts + rail.dur) - MAX(frame.ts, rail.ts)) / 1e6, 3) AS overlap_ms
                    FROM actual_frame_timeline_slice AS frame
                    JOIN android_power_rails_counters AS rail
                      ON rail.ts < frame.ts + frame.dur
                     AND rail.ts + rail.dur > frame.ts
                    WHERE frame.dur > 0 AND rail.dur > 0
                    ORDER BY overlap_ms DESC, average_power_mw DESC
                    LIMIT 200
                    """.trimIndent(),
            ),
            DiagnosticQuery(
                id = "mem_counters",
                category = DiagnosticCategory.MEMORY,
                title = "Memory Usage Timeline",
                description = "Process memory counters over the trace duration",
                columns = listOf("process_name", "min_kb", "max_kb", "avg_kb"),
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
                columns = listOf("process_name", "event_count", "avg_dur_ms", "max_dur_ms"),
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
                columns = listOf("thread_name", "process_name", "scheduler_state", "transitions", "total_dur_ms"),
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
                columns = listOf("thread_name", "process_name", "wakeups", "avg_latency_us", "max_latency_us"),
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

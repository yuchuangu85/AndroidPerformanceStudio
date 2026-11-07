//! Compose perfetto-analysis diagnostic catalog, presets and result projection.
//!
//! The catalog is a direct port of
//! desktop-viewer/perfetto-viewer/perfetto-analysis/.../PerfettoDiagnostics.kt
//! and PerfettoDiagnosticPresets.kt. Every id, title, description, category,
//! declared column list/order and SQL body is identical to the Compose oracle;
//! only Rust syntax differs. SQL is fixed by this module: user input never
//! reaches it (slice search builds its SQL in crate::search with escaping).

use std::fmt;

/// Compose DiagnosticCategory; display names are UI-localized by the caller.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum DiagnosticCategory {
    Cpu,
    Binder,
    Graphics,
    Memory,
    Input,
    Io,
    Runtime,
    Power,
}

impl DiagnosticCategory {
    /// Compose DiagnosticCategory.name (the Kotlin enum constant name).
    pub fn name(self) -> &'static str {
        match self {
            Self::Cpu => "CPU",
            Self::Binder => "BINDER",
            Self::Graphics => "GRAPHICS",
            Self::Memory => "MEMORY",
            Self::Input => "INPUT",
            Self::Io => "IO",
            Self::Runtime => "RUNTIME",
            Self::Power => "POWER",
        }
    }

    /// Compose DiagnosticCategory.displayName.
    pub fn display_name(self) -> &'static str {
        match self {
            Self::Cpu => "CPU Scheduling",
            Self::Binder => "Binder Transactions",
            Self::Graphics => "Graphics Pipeline",
            Self::Memory => "Memory",
            Self::Input => "Input Latency",
            Self::Io => "I/O and SQLite",
            Self::Runtime => "ART Runtime",
            Self::Power => "Thermal and Power",
        }
    }
}

/// UI-facing category from the Compose TraceInsightCategory mapping in
/// PerfettoMainPage.kt (DiagnosticQuery.workspaceCategory). The engine
/// keeps the Compose analysis category and exposes this derived one so the UI
/// cannot drift from the oracle's per-query special cases.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub enum DiagnosticInsightCategory {
    Frame,
    Binder,
    LongTask,
    Rendering,
    Cpu,
    Memory,
    Input,
    Other,
}

const fn str_eq(left: &str, right: &str) -> bool {
    let left = left.as_bytes();
    let right = right.as_bytes();
    if left.len() != right.len() {
        return false;
    }
    let mut index = 0;
    while index < left.len() {
        if left[index] != right[index] {
            return false;
        }
        index += 1;
    }
    true
}

const fn ends_with(value: &str, suffix: &str) -> bool {
    let value = value.as_bytes();
    let suffix = suffix.as_bytes();
    if suffix.len() > value.len() {
        return false;
    }
    let offset = value.len() - suffix.len();
    let mut index = 0;
    while index < suffix.len() {
        if value[offset + index] != suffix[index] {
            return false;
        }
        index += 1;
    }
    true
}

/// Compose DiagnosticQuery.timelineTimestampColumn(), evaluated at compile time.
const fn timestamp_column(columns: &[&str]) -> Option<usize> {
    let mut index = 0;
    while index < columns.len() {
        let name = columns[index];
        if str_eq(name, "timestamp_ns") || str_eq(name, "ts") || ends_with(name, "_timestamp_ns") {
            return Some(index);
        }
        index += 1;
    }
    None
}

/// First declared column holding nanosecond durations, when the query has one.
const fn duration_column(columns: &[&str]) -> Option<usize> {
    let mut index = 0;
    while index < columns.len() {
        if str_eq(columns[index], "duration_ns") {
            return Some(index);
        }
        index += 1;
    }
    None
}

/// One fixed, read-only diagnostic query. Field order and meaning mirror the
/// Compose DiagnosticQuery; timestamp_column/duration_column are derived
/// from columns at compile time instead of being stored in Compose.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct DiagnosticQuery {
    pub id: &'static str,
    pub category: DiagnosticCategory,
    pub title: &'static str,
    pub description: &'static str,
    pub columns: &'static [&'static str],
    pub sql: &'static str,
}

impl DiagnosticQuery {
    /// Compose DiagnosticQuery.timelineTimestampColumn(): the first declared
    /// column named timestamp_ns/ts or ending in _timestamp_ns.
    pub fn timestamp_column(self) -> Option<usize> {
        timestamp_column(self.columns)
    }

    /// First declared column holding nanosecond durations, when the query has
    /// one. Compose does not model durations on DiagnosticQuery, so this is an
    /// additive engine helper for result projections.
    pub fn duration_column(self) -> Option<usize> {
        duration_column(self.columns)
    }

    /// Compose DiagnosticQuery.workspaceCategory() parity.
    pub fn insight_category(self) -> DiagnosticInsightCategory {
        match self.id {
            "frame_jank" => DiagnosticInsightCategory::Frame,
            "main_thread_blocked" => DiagnosticInsightCategory::LongTask,
            _ => match self.category {
                DiagnosticCategory::Binder => DiagnosticInsightCategory::Binder,
                DiagnosticCategory::Graphics => DiagnosticInsightCategory::Rendering,
                DiagnosticCategory::Cpu => DiagnosticInsightCategory::Cpu,
                DiagnosticCategory::Memory => DiagnosticInsightCategory::Memory,
                DiagnosticCategory::Input => DiagnosticInsightCategory::Input,
                DiagnosticCategory::Io
                | DiagnosticCategory::Runtime
                | DiagnosticCategory::Power => DiagnosticInsightCategory::Other,
            },
        }
    }
}

/// Bounded text cells exactly as the pinned CSV interface reported them.
/// None is SQL NULL ("[NULL]"); an empty cell is Some("").
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DiagnosticResult {
    pub query_id: &'static str,
    pub schema_version: &'static str,
    pub columns: Vec<String>,
    pub rows: Vec<Vec<Option<String>>>,
    pub truncated: bool,
}

impl DiagnosticResult {
    /// Compose DiagnosticResult.toPlainText().
    pub fn to_plain_text(&self) -> String {
        let mut lines = Vec::with_capacity(self.rows.len() + 1);
        lines.push(self.columns.join(" | "));
        for row in &self.rows {
            lines.push(
                row.iter()
                    .map(|value| value.as_deref().unwrap_or("unknown"))
                    .collect::<Vec<_>>()
                    .join(" | "),
            );
        }
        lines.join("\n")
    }

    pub fn row_count(&self) -> usize {
        self.rows.len()
    }
}

impl fmt::Display for DiagnosticResult {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(&self.to_plain_text())
    }
}

/// A named group of existing diagnostics the Trace Analyzer runs together.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct DiagnosticPreset {
    pub id: &'static str,
    pub title: &'static str,
    pub query_ids: &'static [&'static str],
}

impl DiagnosticPreset {
    /// Resolves the preset queries in declaration order, mirroring Compose
    /// DiagnosticPreset.queries() (unknown ids are skipped).
    pub fn queries(&self) -> impl Iterator<Item = &'static DiagnosticQuery> + '_ {
        self.query_ids.iter().filter_map(|id| diagnostic_query(id))
    }
}

/// The Compose commercial diagnostic list (PerfettoDiagnosticPresets.core).
pub const CORE_PRESET_ID: &str = "core";

/// Every Compose diagnostic, in PerfettoDiagnostics.all order.
pub static DIAGNOSTIC_QUERIES: &[DiagnosticQuery] = &[
    DiagnosticQuery {
        id: "cpu_hotspots",
        category: DiagnosticCategory::Cpu,
        title: "CPU Scheduling Hotspots",
        description: "Threads with the most CPU scheduling time",
        columns: &["thread_name", "process_name", "slice_count", "total_dur_ms"],
        sql: r#"SELECT thread.name AS thread_name, process.name AS process_name,
       COUNT(*) AS slice_count,
       CAST(SUM(dur) / 1e6 AS INTEGER) AS total_dur_ms
FROM sched_slice AS sched
JOIN thread ON thread.utid = sched.utid
LEFT JOIN process ON process.upid = thread.upid
WHERE sched.dur > 0
GROUP BY sched.utid
ORDER BY total_dur_ms DESC
LIMIT 20"#,
    },
    DiagnosticQuery {
        id: "sched_switch",
        category: DiagnosticCategory::Cpu,
        title: "sched_switch Timeline",
        description: "Running slices and their scheduler end state",
        columns: &[
            "timestamp_ns",
            "duration_ns",
            "cpu",
            "thread_name",
            "process_name",
            "end_state",
        ],
        sql: r#"SELECT sched.ts AS timestamp_ns,
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
LIMIT 2000"#,
    },
    DiagnosticQuery {
        id: "sched_waking",
        category: DiagnosticCategory::Cpu,
        title: "sched_waking Events",
        description: "Threads woken by another thread and their target CPU",
        columns: &[
            "timestamp_ns",
            "cpu",
            "target_cpu",
            "waker_thread",
            "target_thread",
            "success",
        ],
        sql: r#"WITH waking AS (
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
LIMIT 2000"#,
    },
    DiagnosticQuery {
        id: "run_queue",
        category: DiagnosticCategory::Cpu,
        title: "Run Queue Pressure",
        description: "Runnable thread count over time, including high-pressure windows",
        columns: &[
            "timestamp_ns",
            "runnable_threads",
            "next_timestamp_ns",
            "run_queue_ms",
        ],
        sql: r#"INCLUDE PERFETTO MODULE sched.thread_level_parallelism;

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
LIMIT 2000"#,
    },
    DiagnosticQuery {
        id: "main_thread_blocked",
        category: DiagnosticCategory::Cpu,
        title: "Main Thread Blocking",
        description: "Main-thread sleeping and uninterruptible blocking intervals",
        columns: &[
            "thread_name",
            "process_name",
            "state",
            "blocked_function",
            "block_count",
            "total_blocked_ms",
            "max_blocked_ms",
        ],
        sql: r#"SELECT thread.name AS thread_name,
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
LIMIT 30"#,
    },
    DiagnosticQuery {
        id: "cpu_freq_dist",
        category: DiagnosticCategory::Cpu,
        title: "CPU Frequency Distribution",
        description: "Time spent at each CPU frequency",
        columns: &["cpu", "freq_mhz", "intervals", "total_dur_s"],
        sql: r#"INCLUDE PERFETTO MODULE linux.cpu.frequency;

SELECT frequency.cpu AS cpu,
       CAST(frequency.freq / 1000 AS INTEGER) AS freq_mhz,
       COUNT(*) AS intervals,
       ROUND(SUM(frequency.dur) / 1e9, 3) AS total_dur_s
FROM cpu_frequency_counters AS frequency
WHERE frequency.dur > 0 AND frequency.freq IS NOT NULL
GROUP BY frequency.cpu, frequency.freq
ORDER BY total_dur_s DESC
LIMIT 30"#,
    },
    DiagnosticQuery {
        id: "cpu_contention",
        category: DiagnosticCategory::Cpu,
        title: "CPU Contention",
        description: "Threads delayed between wakeup and actual execution",
        columns: &[
            "thread_name",
            "process_name",
            "contention_count",
            "avg_wait_ms",
            "max_wait_ms",
        ],
        sql: r#"INCLUDE PERFETTO MODULE sched.runnable;

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
LIMIT 30"#,
    },
    DiagnosticQuery {
        id: "binder_ipc_long_tail",
        category: DiagnosticCategory::Binder,
        title: "Binder IPC Long Tail",
        description: "Caller, server, AIDL method and long-tail Binder transaction latency",
        columns: &[
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
        ],
        sql: r#"INCLUDE PERFETTO MODULE android.binder;

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
LIMIT 50"#,
    },
    DiagnosticQuery {
        id: "binder_system_server",
        category: DiagnosticCategory::Binder,
        title: "system_server IPC",
        description: "Binder transactions crossing into system_server, including client and server time",
        columns: &[
            "client",
            "client_thread",
            "interface",
            "method",
            "txn_count",
            "client_ms",
            "server_ms",
            "max_client_ms",
        ],
        sql: r#"INCLUDE PERFETTO MODULE android.binder;

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
LIMIT 50"#,
    },
    DiagnosticQuery {
        id: "binder_server_saturation",
        category: DiagnosticCategory::Binder,
        title: "Binder Server Saturation",
        description: "Server-side Binder time grouped by service process",
        columns: &[
            "server",
            "txn_count",
            "total_server_ms",
            "avg_server_ms",
            "max_server_ms",
        ],
        sql: r#"INCLUDE PERFETTO MODULE android.binder;

SELECT binder.server_process AS server,
       COUNT(*) AS txn_count,
       ROUND(SUM(binder.server_dur) / 1e6, 3) AS total_server_ms,
       ROUND(AVG(binder.server_dur) / 1e6, 3) AS avg_server_ms,
       ROUND(MAX(binder.server_dur) / 1e6, 3) AS max_server_ms
FROM android_binder_txns AS binder
WHERE binder.server_dur > 0
GROUP BY binder.server_upid, binder.server_process
ORDER BY total_server_ms DESC
LIMIT 20"#,
    },
    DiagnosticQuery {
        id: "binder_wait_chain",
        category: DiagnosticCategory::Binder,
        title: "Binder Wait Chain",
        description: "Thread states observed while serving or waiting for Binder transactions",
        columns: &[
            "thread_state_type",
            "state",
            "txn_count",
            "total_dur_ms",
            "max_dur_ms",
        ],
        sql: r#"INCLUDE PERFETTO MODULE android.binder;

SELECT state.thread_state_type AS thread_state_type,
       state.thread_state AS state,
       SUM(state.thread_state_count) AS txn_count,
       ROUND(SUM(state.thread_state_dur) / 1e6, 3) AS total_dur_ms,
       ROUND(MAX(state.thread_state_dur) / 1e6, 3) AS max_dur_ms
FROM android_sync_binder_thread_state_by_txn AS state
WHERE state.thread_state_dur > 0
GROUP BY state.thread_state_type, state.thread_state
ORDER BY total_dur_ms DESC
LIMIT 30"#,
    },
    DiagnosticQuery {
        id: "binder_latency",
        category: DiagnosticCategory::Binder,
        title: "Binder Transaction Latency",
        description: "Binder calls with the highest total duration",
        columns: &["client", "server", "txn_count", "avg_dur_ms", "max_dur_ms"],
        sql: r#"INCLUDE PERFETTO MODULE android.binder;

SELECT binder.client_process AS client,
       binder.server_process AS server,
       COUNT(*) AS txn_count,
       ROUND(AVG(binder.client_dur) / 1e6, 3) AS avg_dur_ms,
       ROUND(MAX(binder.client_dur) / 1e6, 3) AS max_dur_ms
FROM android_binder_txns AS binder
WHERE binder.client_dur > 0
GROUP BY binder.client_upid, binder.server_upid
ORDER BY max_dur_ms DESC
LIMIT 20"#,
    },
    DiagnosticQuery {
        id: "binder_frame_alignment",
        category: DiagnosticCategory::Graphics,
        title: "Binder / FrameTimeline Alignment",
        description: "Binder calls overlapping an app frame and its jank classification",
        columns: &[
            "frame_id",
            "layer_name",
            "jank_type",
            "client",
            "server",
            "method",
            "binder_ms",
            "frame_overrun_ms",
        ],
        sql: r#"INCLUDE PERFETTO MODULE android.binder;
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
LIMIT 100"#,
    },
    DiagnosticQuery {
        id: "frame_jank",
        category: DiagnosticCategory::Graphics,
        title: "Frame Jank Detection",
        description: "Frames that missed their device-specific FrameTimeline deadline",
        columns: &[
            "process_name",
            "frame_count",
            "janky_frames",
            "avg_dur_ms",
            "max_dur_ms",
            "max_overrun_ms",
        ],
        sql: r#"INCLUDE PERFETTO MODULE android.frames.per_frame_metrics;
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
LIMIT 20"#,
    },
    DiagnosticQuery {
        id: "frame_surface_correlation",
        category: DiagnosticCategory::Graphics,
        title: "Frame / SurfaceFlinger Correlation",
        description: "FrameTimeline jank grouped by application layer and SurfaceFlinger",
        columns: &[
            "layer_name",
            "frame_count",
            "janky_frames",
            "surface_flinger_janky_frames",
            "max_dur_ms",
        ],
        sql: r#"INCLUDE PERFETTO MODULE android.frames.timeline;

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
LIMIT 30"#,
    },
    DiagnosticQuery {
        id: "file_io_syscalls",
        category: DiagnosticCategory::Io,
        title: "File Read / Write Syscalls",
        description: "Read, write, pread, pwrite and sync syscall events by process and thread",
        columns: &["operation", "process_name", "thread_name", "event_count"],
        sql: r#"SELECT event.name AS operation,
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
LIMIT 100"#,
    },
    DiagnosticQuery {
        id: "fsync_calls",
        category: DiagnosticCategory::Io,
        title: "fsync / fdatasync Activity",
        description: "Durability sync syscall events that can stall application threads",
        columns: &["process_name", "thread_name", "operation", "event_count"],
        sql: r#"SELECT process.name AS process_name,
       thread.name AS thread_name,
       event.name AS operation,
       COUNT(*) AS event_count
FROM ftrace_event AS event
LEFT JOIN thread ON thread.utid = event.utid
LEFT JOIN process ON process.upid = thread.upid
WHERE event.name IN ('sys_enter_fsync', 'sys_enter_fdatasync', 'sys_enter_syncfs')
GROUP BY process.upid, thread.utid, event.name
ORDER BY event_count DESC
LIMIT 100"#,
    },
    DiagnosticQuery {
        id: "block_io_pressure",
        category: DiagnosticCategory::Io,
        title: "Block I/O Queue Pressure",
        description: "Outstanding block requests by device over time",
        columns: &["timestamp_ns", "device", "outstanding_operations"],
        sql: r#"INCLUDE PERFETTO MODULE linux.block_io;

SELECT ts AS timestamp_ns,
       dev AS device,
       ops_in_queue_or_device AS outstanding_operations
FROM linux_active_block_io_operations_by_device
WHERE ops_in_queue_or_device > 0
ORDER BY outstanding_operations DESC, ts
LIMIT 2000"#,
    },
    DiagnosticQuery {
        id: "page_faults",
        category: DiagnosticCategory::Io,
        title: "Page Fault Activity",
        description: "User and kernel page-fault events by process and thread",
        columns: &["process_name", "thread_name", "fault_type", "fault_count"],
        sql: r#"SELECT process.name AS process_name,
       thread.name AS thread_name,
       event.name AS fault_type,
       COUNT(*) AS fault_count
FROM ftrace_event AS event
LEFT JOIN thread ON thread.utid = event.utid
LEFT JOIN process ON process.upid = thread.upid
WHERE event.name GLOB '*page_fault*'
GROUP BY process.upid, thread.utid, event.name
ORDER BY fault_count DESC
LIMIT 100"#,
    },
    DiagnosticQuery {
        id: "sqlite_activity",
        category: DiagnosticCategory::Io,
        title: "SQLite Query / Transaction Activity",
        description: "SQLite queries, transactions, WAL checkpoints and database work slices",
        columns: &[
            "process_name",
            "thread_name",
            "operation",
            "count",
            "total_ms",
            "max_ms",
        ],
        sql: r#"SELECT process.name AS process_name,
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
LIMIT 100"#,
    },
    DiagnosticQuery {
        id: "sqlite_lock_wait",
        category: DiagnosticCategory::Io,
        title: "SQLite Lock Waits",
        description: "SQLite/database lock and busy waits observed in application slices",
        columns: &[
            "process_name",
            "thread_name",
            "wait_name",
            "wait_count",
            "total_wait_ms",
            "max_wait_ms",
        ],
        sql: r#"SELECT process.name AS process_name,
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
LIMIT 100"#,
    },
    DiagnosticQuery {
        id: "art_gc_events",
        category: DiagnosticCategory::Runtime,
        title: "ART Garbage Collection Pauses",
        description: "GC duration, CPU wait, I/O wait and reclaimed heap by process",
        columns: &[
            "process_name",
            "gc_type",
            "gc_count",
            "total_gc_ms",
            "max_gc_ms",
            "reclaimed_mb",
            "runnable_ms",
            "io_wait_ms",
        ],
        sql: r#"INCLUDE PERFETTO MODULE android.garbage_collection;

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
LIMIT 100"#,
    },
    DiagnosticQuery {
        id: "art_allocation_churn",
        category: DiagnosticCategory::Runtime,
        title: "ART Allocation Churn",
        description: "Heap allocation rate, utilization and GC CPU rate by process",
        columns: &[
            "process_name",
            "allocation_mb",
            "allocation_rate_mb_s",
            "heap_size_mb",
            "heap_utilization",
            "gc_cpu_rate",
        ],
        sql: r#"INCLUDE PERFETTO MODULE android.garbage_collection;

SELECT process.name AS process_name,
       ROUND(stats.heap_allocated_mb, 3) AS allocation_mb,
       ROUND(stats.heap_allocation_rate, 3) AS allocation_rate_mb_s,
       ROUND(stats.heap_size_mb, 3) AS heap_size_mb,
       ROUND(stats.heap_utilization * 100, 3) AS heap_utilization,
       ROUND(stats.gc_running_rate * 100, 3) AS gc_cpu_rate
FROM _android_garbage_collection_process_stats AS stats
LEFT JOIN process USING (upid)
ORDER BY allocation_rate_mb_s DESC
LIMIT 100"#,
    },
    DiagnosticQuery {
        id: "art_jit_dex2oat",
        category: DiagnosticCategory::Runtime,
        title: "JIT / dex2oat Activity",
        description: "JIT compilation and dex2oat slices with thread and process attribution",
        columns: &[
            "process_name",
            "thread_name",
            "operation",
            "count",
            "total_ms",
            "max_ms",
        ],
        sql: r#"SELECT process.name AS process_name,
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
LIMIT 100"#,
    },
    DiagnosticQuery {
        id: "art_class_loading",
        category: DiagnosticCategory::Runtime,
        title: "Class Loading During Startup",
        description: "ART class loading slices associated with Android startup windows",
        columns: &[
            "startup_id",
            "class_name",
            "thread_name",
            "count",
            "total_ms",
            "max_ms",
        ],
        sql: r#"INCLUDE PERFETTO MODULE android.startup.startups;

SELECT startup_id,
       slice_name AS class_name,
       thread_name,
       COUNT(*) AS count,
       ROUND(SUM(slice_dur) / 1e6, 3) AS total_ms,
       ROUND(MAX(slice_dur) / 1e6, 3) AS max_ms
FROM android_class_loading_for_startup
GROUP BY startup_id, slice_name, thread_name
ORDER BY total_ms DESC
LIMIT 100"#,
    },
    DiagnosticQuery {
        id: "art_class_verification",
        category: DiagnosticCategory::Runtime,
        title: "Class Verification / Initialization",
        description: "Class verification, linking and initialization slices",
        columns: &[
            "process_name",
            "thread_name",
            "operation",
            "count",
            "total_ms",
            "max_ms",
        ],
        sql: r#"SELECT process.name AS process_name,
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
LIMIT 100"#,
    },
    DiagnosticQuery {
        id: "thermal_throttling",
        category: DiagnosticCategory::Power,
        title: "Thermal / Throttling Signals",
        description: "Thermal zone, cooling device and throttling counters over time",
        columns: &["timestamp_ns", "signal", "value"],
        sql: r#"SELECT counter.ts AS timestamp_ns,
       track.name AS signal,
       counter.value AS value
FROM counter
JOIN counter_track AS track ON track.id = counter.track_id
WHERE LOWER(track.name) GLOB '*thermal*'
   OR LOWER(track.name) GLOB '*throttl*'
   OR LOWER(track.name) GLOB '*cooling*'
ORDER BY counter.ts
LIMIT 2000"#,
    },
    DiagnosticQuery {
        id: "dvfs_residency",
        category: DiagnosticCategory::Power,
        title: "DVFS Frequency Residency",
        description: "Time and percentage spent in CPU, memory and interconnect DVFS states",
        columns: &["domain", "frequency", "duration_ms", "percent"],
        sql: r#"INCLUDE PERFETTO MODULE android.dvfs;

SELECT name AS domain,
       value AS frequency,
       ROUND(dur / 1e6, 3) AS duration_ms,
       ROUND(pct, 3) AS percent
FROM android_dvfs_counter_residency
ORDER BY domain, duration_ms DESC
LIMIT 500"#,
    },
    DiagnosticQuery {
        id: "gpu_frequency_residency",
        category: DiagnosticCategory::Power,
        title: "GPU Frequency Residency",
        description: "GPU frequency intervals and residency duration",
        columns: &["gpu_id", "frequency", "duration_ms", "timestamp_ns"],
        sql: r#"INCLUDE PERFETTO MODULE android.gpu.frequency;

SELECT gpu_id,
       gpu_freq AS frequency,
       ROUND(dur / 1e6, 3) AS duration_ms,
       ts AS timestamp_ns
FROM android_gpu_frequency
WHERE dur > 0
ORDER BY duration_ms DESC
LIMIT 500"#,
    },
    DiagnosticQuery {
        id: "power_rails",
        category: DiagnosticCategory::Power,
        title: "Power Rail Energy",
        description: "Hardware power rail energy delta and average power",
        columns: &[
            "rail",
            "subsystem",
            "energy_uws",
            "average_power_mw",
            "duration_ms",
        ],
        sql: r#"INCLUDE PERFETTO MODULE android.power_rails;

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
LIMIT 100"#,
    },
    DiagnosticQuery {
        id: "power_frame_alignment",
        category: DiagnosticCategory::Power,
        title: "Power / FrameTimeline Alignment",
        description: "Power rail intervals overlapping app or display frames",
        columns: &[
            "frame_id",
            "layer_name",
            "jank_type",
            "rail",
            "average_power_mw",
            "overlap_ms",
        ],
        sql: r#"INCLUDE PERFETTO MODULE android.power_rails;

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
LIMIT 200"#,
    },
    DiagnosticQuery {
        id: "mem_counters",
        category: DiagnosticCategory::Memory,
        title: "Memory Usage Timeline",
        description: "Process memory counters over the trace duration",
        columns: &["process_name", "min_kb", "max_kb", "avg_kb"],
        sql: r#"INCLUDE PERFETTO MODULE linux.memory.process;

SELECT memory.process_name AS process_name,
       CAST(MIN(memory.rss) / 1024 AS INTEGER) AS min_kb,
       CAST(MAX(memory.rss) / 1024 AS INTEGER) AS max_kb,
       CAST(AVG(memory.rss) / 1024 AS INTEGER) AS avg_kb
FROM memory_rss_and_swap_per_process AS memory
WHERE memory.rss IS NOT NULL
GROUP BY memory.upid, memory.process_name
ORDER BY max_kb DESC
LIMIT 20"#,
    },
    DiagnosticQuery {
        id: "input_latency",
        category: DiagnosticCategory::Input,
        title: "Input Event Latency",
        description: "Input dispatch to app response time",
        columns: &["process_name", "event_count", "avg_dur_ms", "max_dur_ms"],
        sql: r#"SELECT COALESCE(process.name, 'unknown') AS process_name,
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
LIMIT 20"#,
    },
    DiagnosticQuery {
        id: "thread_states",
        category: DiagnosticCategory::Cpu,
        title: "Thread State Breakdown",
        description: "Time spent in each scheduler state (Running/R/S/D)",
        columns: &[
            "thread_name",
            "process_name",
            "scheduler_state",
            "transitions",
            "total_dur_ms",
        ],
        sql: r#"SELECT thread.name AS thread_name, process.name AS process_name,
       state.state AS scheduler_state,
       COUNT(*) AS transitions,
       ROUND(SUM(IIF(state.dur = -1, trace_end() - state.ts, state.dur)) / 1e6, 3) AS total_dur_ms
FROM thread_state AS state
JOIN thread ON thread.utid = state.utid
LEFT JOIN process ON process.upid = thread.upid
WHERE state.dur != 0
GROUP BY state.utid, state.state
ORDER BY total_dur_ms DESC
LIMIT 30"#,
    },
    DiagnosticQuery {
        id: "wakeup_latency",
        category: DiagnosticCategory::Cpu,
        title: "Thread Wakeup Latency",
        description: "Time between waking and running on CPU",
        columns: &[
            "thread_name",
            "process_name",
            "wakeups",
            "avg_latency_us",
            "max_latency_us",
        ],
        sql: r#"INCLUDE PERFETTO MODULE sched.runnable;

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
LIMIT 20"#,
    },
];

/// Compose PerfettoDiagnosticPresets.all, in declaration order.
pub static DIAGNOSTIC_PRESETS: &[DiagnosticPreset] = &[
    DiagnosticPreset {
        id: "core",
        title: "Core diagnostics",
        query_ids: &[
            "cpu_hotspots",
            "cpu_freq_dist",
            "binder_latency",
            "frame_jank",
            "mem_counters",
            "input_latency",
            "thread_states",
            "wakeup_latency",
        ],
    },
    DiagnosticPreset {
        id: "jank",
        title: "Jank and rendering",
        query_ids: &[
            "frame_jank",
            "frame_surface_correlation",
            "binder_frame_alignment",
            "main_thread_blocked",
            "input_latency",
        ],
    },
    DiagnosticPreset {
        id: "scheduling",
        title: "CPU scheduling",
        query_ids: &[
            "cpu_hotspots",
            "run_queue",
            "cpu_contention",
            "thread_states",
            "wakeup_latency",
            "cpu_freq_dist",
        ],
    },
    DiagnosticPreset {
        id: "binder",
        title: "Binder IPC",
        query_ids: &[
            "binder_latency",
            "binder_ipc_long_tail",
            "binder_system_server",
            "binder_server_saturation",
            "binder_wait_chain",
        ],
    },
    DiagnosticPreset {
        id: "io",
        title: "I/O and SQLite",
        query_ids: &[
            "file_io_syscalls",
            "fsync_calls",
            "block_io_pressure",
            "sqlite_activity",
            "sqlite_lock_wait",
        ],
    },
    DiagnosticPreset {
        id: "memory",
        title: "Memory and ART",
        query_ids: &[
            "mem_counters",
            "page_faults",
            "art_gc_events",
            "art_allocation_churn",
        ],
    },
    DiagnosticPreset {
        id: "power",
        title: "Thermal and power",
        query_ids: &[
            "thermal_throttling",
            "dvfs_residency",
            "gpu_frequency_residency",
            "power_rails",
        ],
    },
    DiagnosticPreset {
        id: "all",
        title: "All diagnostics",
        query_ids: &[
            "cpu_hotspots",
            "sched_switch",
            "sched_waking",
            "run_queue",
            "main_thread_blocked",
            "cpu_freq_dist",
            "cpu_contention",
            "binder_ipc_long_tail",
            "binder_system_server",
            "binder_server_saturation",
            "binder_wait_chain",
            "binder_latency",
            "binder_frame_alignment",
            "frame_jank",
            "frame_surface_correlation",
            "file_io_syscalls",
            "fsync_calls",
            "block_io_pressure",
            "page_faults",
            "sqlite_activity",
            "sqlite_lock_wait",
            "art_gc_events",
            "art_allocation_churn",
            "art_jit_dex2oat",
            "art_class_loading",
            "art_class_verification",
            "thermal_throttling",
            "dvfs_residency",
            "gpu_frequency_residency",
            "power_rails",
            "power_frame_alignment",
            "mem_counters",
            "input_latency",
            "thread_states",
            "wakeup_latency",
        ],
    },
];

/// Compose PerfettoDiagnostics.all.
pub fn diagnostic_queries() -> &'static [DiagnosticQuery] {
    DIAGNOSTIC_QUERIES
}

/// Compose PerfettoDiagnostics.all.firstOrNull { it.id == id }.
pub fn diagnostic_query(id: &str) -> Option<&'static DiagnosticQuery> {
    DIAGNOSTIC_QUERIES.iter().find(|query| query.id == id)
}

/// Compose PerfettoDiagnostics.queriesForCategory.
pub fn queries_for_category(
    category: DiagnosticCategory,
) -> impl Iterator<Item = &'static DiagnosticQuery> {
    DIAGNOSTIC_QUERIES
        .iter()
        .filter(move |query| query.category == category)
}

/// Compose PerfettoDiagnosticPresets.all.
pub fn diagnostic_presets() -> &'static [DiagnosticPreset] {
    DIAGNOSTIC_PRESETS
}

/// Compose PerfettoDiagnosticPresets.byId, which falls back to core.
pub fn diagnostic_preset(id: Option<&str>) -> &'static DiagnosticPreset {
    let fallback = DIAGNOSTIC_PRESETS
        .first()
        .expect("the core preset is always declared");
    id.and_then(|id| DIAGNOSTIC_PRESETS.iter().find(|preset| preset.id == id))
        .unwrap_or(fallback)
}

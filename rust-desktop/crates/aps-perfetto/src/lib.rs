//! Version- and checksum-pinned, read-only Trace Processor adapter.
//!
//! A TraceSession prefers one warm native session per opened trace: open starts
//! trace_processor_shell server unix ... --daemonize so the trace is parsed
//! once, diagnostic/search queries run through query --remote <session>
//! --format csv, and close/Drop runs server kill. When daemonization or remote
//! queries are unavailable, the documented bounded one-shot CSV path is used
//! and the degradation is reported through TraceSessionMode instead of being
//! hidden. Diagnostic SQL is fixed by the ported Compose catalog; the only user
//! input in SQL is the slice-search term, escaped in the search module. There
//! is no invented JSON protocol, arbitrary SQL, PATH fallback, downloader,
//! cloud connection or device command.

use aps_contracts::SourceDigest;
use aps_platform::{CancellationToken, ProcessError, ProcessRequest, ProcessRunner};
use std::fmt;
use std::fs::{File, Metadata};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Mutex, MutexGuard};
use std::time::{Duration, Instant, SystemTime};

mod csv;
pub mod diagnostics;
pub mod findings;
pub mod frame_timeline;
pub mod heap_profile;
pub mod recording;
pub mod search;

pub use diagnostics::{
    CORE_PRESET_ID, DIAGNOSTIC_PRESETS, DIAGNOSTIC_QUERIES, DiagnosticCategory,
    DiagnosticInsightCategory, DiagnosticPreset, DiagnosticQuery, DiagnosticResult,
    diagnostic_preset, diagnostic_presets, diagnostic_queries, diagnostic_query,
    queries_for_category,
};
pub use findings::{
    DiagnosticFinding, DiagnosticFindingLevel, DiagnosticMetric, DiagnosticMetricKind,
    evaluate_finding,
};
pub use search::{
    SearchHit, SliceSearchQuery, SliceSearchResult, TraceSliceSearch, decode_search_hits,
};

pub const PINNED_VERSION: &str = "v59.1";
pub const MAX_PAGE_SIZE: usize = 200;
pub const MAX_TRACE_BYTES: u64 = 8 * 1024 * 1024 * 1024;
const MAX_TOOL_BYTES: u64 = 256 * 1024 * 1024;
const MAX_OUTPUT_BYTES: usize = 4 * 1024 * 1024;
const MAX_STDERR_BYTES: usize = 64 * 1024;
/// Hard ceiling on one diagnostic page. Declared SQL already limits rows, so a
/// full page is reported as truncated (more rows could exist upstream).
pub const MAX_DIAGNOSTIC_ROWS: usize = 2000;
/// Per-cell preview bound for diagnostic text cells.
pub const MAX_DIAGNOSTIC_CELL_CHARS: usize = 4096;
const QUERY_TIMEOUT: Duration = Duration::from_secs(120);
const WARM_START_TIMEOUT: Duration = Duration::from_secs(300);
const WARM_KILL_TIMEOUT: Duration = Duration::from_secs(5);
/// Explicit idle reaper for the daemonized server. A crashed host process
/// cannot run Drop, so the pinned tool reaps an orphaned session after this
/// idle period (the daemon arms the clock at last activity).
const WARM_IDLE_TIMEOUT: &str = "30m";

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PerfettoError {
    UnsupportedHost,
    ToolMissing,
    ToolChecksumMismatch,
    ToolVersionMismatch,
    InvalidTrace,
    SourceChanged,
    InvalidPage,
    ProcessFailed,
    Cancelled,
    Timeout,
    OutputLimit,
    InvalidResult,
    SessionClosed,
}
impl fmt::Display for PerfettoError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::UnsupportedHost => "This host has no pinned Trace Processor artifact",
            Self::ToolMissing => {
                "Pinned Trace Processor is missing; no PATH or download fallback is used"
            }
            Self::ToolChecksumMismatch => {
                "Trace Processor checksum does not match the pinned artifact"
            }
            Self::ToolVersionMismatch => "Trace Processor must report Perfetto v59.1",
            Self::InvalidTrace => "Select a regular local trace file within the 8 GiB limit",
            Self::SourceChanged => "Selected trace changed; import it again",
            Self::InvalidPage => "Trace page must contain 1 to 200 rows",
            Self::ProcessFailed => "Trace Processor failed; raw tool output is not displayed",
            Self::Cancelled => "Trace analysis was cancelled",
            Self::Timeout => "Trace Processor timed out",
            Self::OutputLimit => "Trace result exceeds the bounded output budget",
            Self::InvalidResult => "Trace Processor returned an invalid result schema",
            Self::SessionClosed => "Trace session is closed",
        })
    }
}
impl std::error::Error for PerfettoError {}
impl From<ProcessError> for PerfettoError {
    fn from(error: ProcessError) -> Self {
        match error {
            ProcessError::Cancelled => Self::Cancelled,
            ProcessError::Timeout | ProcessError::OutputNotClosed => Self::Timeout,
            ProcessError::OutputLimit(_) => Self::OutputLimit,
            _ => Self::ProcessFailed,
        }
    }
}

/// No public executable path: only a verified binary can produce this token.
#[derive(Clone)]
pub struct TraceProcessor {
    path: PathBuf,
    digest: SourceDigest,
}

impl TraceProcessor {
    /// Explicit source-checkout location; never searches environment or PATH.
    pub fn from_repository(
        repository_root: &Path,
        cancel: &CancellationToken,
    ) -> Result<Self, PerfettoError> {
        Self::from_verified_path(
            &repository_root
                .join("build/perfetto-tools")
                .join(executable_name()),
            cancel,
        )
    }
    /// The resource directory belongs to the caller's independently packaged app.
    /// This does not treat the Kotlin app's resource directory as Rust packaging.
    pub fn from_packaged_resources(
        resources: &Path,
        cancel: &CancellationToken,
    ) -> Result<Self, PerfettoError> {
        Self::from_verified_path(
            &resources.join("perfetto-tools").join(executable_name()),
            cancel,
        )
    }
    /// Explicit paths are allowed only when bytes match the host's pinned hash.
    pub fn from_verified_path(
        path: &Path,
        cancel: &CancellationToken,
    ) -> Result<Self, PerfettoError> {
        check_cancel(cancel)?;
        let expected = pinned_host_sha256()?;
        let path = path
            .canonicalize()
            .map_err(|_| PerfettoError::ToolMissing)?;
        let digest = SourceDigest::hash_file(&path, MAX_TOOL_BYTES)
            .map_err(|_| PerfettoError::ToolMissing)?;
        if digest.to_hex() != expected {
            return Err(PerfettoError::ToolChecksumMismatch);
        }
        check_cancel(cancel)?;
        let mut request = ProcessRequest::new(&path).arg("--version");
        request.env_clear = true;
        request.timeout = Duration::from_secs(5);
        request.stdout_limit = 4096;
        request.stderr_limit = 4096;
        let output = ProcessRunner.run(&request, cancel)?;
        let stdout =
            std::str::from_utf8(&output.stdout).map_err(|_| PerfettoError::ToolVersionMismatch)?;
        let stderr =
            std::str::from_utf8(&output.stderr).map_err(|_| PerfettoError::ToolVersionMismatch)?;
        if !output.status.success()
            || !stdout
                .lines()
                .chain(stderr.lines())
                .any(|line| line.starts_with("Perfetto v59.1-"))
        {
            return Err(PerfettoError::ToolVersionMismatch);
        }
        Ok(Self { path, digest })
    }
    pub fn version(&self) -> &'static str {
        PINNED_VERSION
    }
    pub fn sha256(&self) -> SourceDigest {
        self.digest
    }

    fn check(&self, cancel: &CancellationToken) -> Result<(), PerfettoError> {
        check_cancel(cancel)?;
        let digest = SourceDigest::hash_file(&self.path, MAX_TOOL_BYTES)
            .map_err(|_| PerfettoError::ToolMissing)?;
        check_cancel(cancel)?;
        if digest != self.digest {
            return Err(PerfettoError::ToolChecksumMismatch);
        }
        Ok(())
    }
}

fn executable_name() -> &'static str {
    if cfg!(windows) {
        "trace_processor_shell.exe"
    } else {
        "trace_processor_shell"
    }
}

/// SHA-256 values from desktop-viewer/platform-perfetto/trace-processor-manifest.json.
/// Keep source checks in tests when updating the canonical pinned manifest.
pub fn pinned_host_sha256() -> Result<&'static str, PerfettoError> {
    match (std::env::consts::OS, std::env::consts::ARCH) {
        ("macos", "aarch64") => {
            Ok("8488edbefadfc4be152cda1d42d9bfcede8b47638295eb85eb3bad8cd148fdf4")
        }
        ("macos", "x86_64") => {
            Ok("df38039845244c353c399994748599345cf08f740491b5832e7feb9a4dbcc476")
        }
        ("linux", "x86_64") => {
            Ok("2434129a3af4dc9e2034a476f7b76447b490975213e301769310d3bd0d5d6458")
        }
        ("linux", "aarch64") => {
            Ok("a08e4836171c87be3ba5834eef3489aa8803476d35f292ed7feaccae252747d0")
        }
        ("windows", "x86_64") => {
            Ok("3acc5aca1c764541033a82ba3bb0a47524b5c3c0aa194eea8d9a7b0cdb346bdc")
        }
        _ => Err(PerfettoError::UnsupportedHost),
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum QueryPreset {
    TraceBounds,
    Processes,
    Threads,
    Slices,
    CpuScheduling,
    GpuFrequency,
    GpuMemory,
    GpuRenderStages,
}
impl QueryPreset {
    pub fn title(self) -> &'static str {
        match self {
            Self::TraceBounds => "Trace bounds",
            Self::Processes => "Processes",
            Self::Threads => "Threads",
            Self::Slices => "Slices",
            Self::CpuScheduling => "CPU scheduling",
            Self::GpuFrequency => "GPU frequency",
            Self::GpuMemory => "GPU memory",
            Self::GpuRenderStages => "GPU render stages",
        }
    }
    fn columns(self) -> &'static [&'static str] {
        match self {
            Self::TraceBounds => &["start_ts", "end_ts"],
            Self::Processes => &["upid", "pid", "name"],
            Self::Threads => &["utid", "tid", "upid", "name"],
            Self::Slices => &["id", "ts", "dur", "track_id", "name"],
            Self::CpuScheduling => &["id", "ts", "dur", "cpu", "utid"],
            Self::GpuFrequency => &["gpu_id", "frequency", "duration_ms", "timestamp_ns"],
            Self::GpuMemory => &["ts", "dur", "memory_bytes"],
            Self::GpuRenderStages => &["id", "ts", "dur", "track_id", "name"],
        }
    }
    fn name_column(self) -> Option<usize> {
        match self {
            Self::Processes => Some(2),
            Self::Threads => Some(3),
            Self::Slices => Some(4),
            Self::GpuRenderStages => Some(4),
            _ => None,
        }
    }
    fn sql(self, offset: usize, limit: usize) -> String {
        // Hex text avoids upstream CSV's unescaped quotes/newlines. Decode only
        // this declared column after CSV parsing; all SQL is fixed by this enum.
        let text = "CASE WHEN name IS NULL THEN NULL ELSE hex(CAST(substr(name,1,4096) AS BLOB)) END AS name";
        let body = match self {
            Self::TraceBounds => "SELECT start_ts,end_ts FROM trace_bounds".to_owned(),
            Self::Processes => format!("SELECT upid,pid,{text} FROM process ORDER BY upid"),
            Self::Threads => format!("SELECT utid,tid,upid,{text} FROM thread ORDER BY utid"),
            Self::Slices => format!("SELECT id,ts,dur,track_id,{text} FROM slice ORDER BY id"),
            Self::CpuScheduling => "SELECT id,ts,dur,cpu,utid FROM sched ORDER BY id".to_owned(),
            Self::GpuFrequency => "SELECT gpu_id, gpu_freq AS frequency, ROUND(dur / 1e6, 3) AS duration_ms, ts AS timestamp_ns FROM gpu_frequency ORDER BY duration_ms DESC".to_owned(),
            Self::GpuMemory => "SELECT ts, dur, value AS memory_bytes FROM counter JOIN gpu_counter_track ON counter.track_id = gpu_counter_track.id WHERE gpu_counter_track.type = 'gpu_memory' ORDER BY ts".to_owned(),
            Self::GpuRenderStages => format!("SELECT id,ts,dur,track_id,{text} FROM gpu_slice ORDER BY id"),
        };
        format!("{body} LIMIT {} OFFSET {offset}", limit + 1)
    }
}

#[derive(Clone, Debug)]
pub struct QueryPage {
    pub preset: QueryPreset,
    pub source_digest: SourceDigest,
    pub columns: Vec<String>,
    pub rows: Vec<Vec<Option<String>>>,
    pub offset: usize,
    pub has_more: bool,
    /// Text columns are explicit bounded previews, not complete trace text.
    pub text_preview_characters: usize,
}

#[derive(PartialEq, Eq)]
struct SourceStamp {
    length: u64,
    modified: SystemTime,
}
impl SourceStamp {
    fn read(path: &Path) -> Result<Self, PerfettoError> {
        let meta: Metadata = File::open(path)
            .and_then(|file| file.metadata())
            .map_err(|_| PerfettoError::InvalidTrace)?;
        if !meta.is_file() || meta.len() > MAX_TRACE_BYTES {
            return Err(PerfettoError::InvalidTrace);
        }
        Ok(Self {
            length: meta.len(),
            modified: meta.modified().map_err(|_| PerfettoError::InvalidTrace)?,
        })
    }
}

/// How diagnostics/search reach the native processor.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TraceSessionMode {
    /// One daemonized server parsed the trace; queries use query --remote.
    Warm,
    /// Daemonization or the warm server is unavailable; bounded one-shot CSV
    /// queries are used and the caller can see the degradation here.
    OneShotFallback,
    /// close() ran (or the session was dropped); further queries fail closed.
    Closed,
}

impl TraceSessionMode {
    pub fn is_warm(self) -> bool {
        matches!(self, Self::Warm)
    }
}

enum WarmState {
    Warm(String),
    /// The daemon was started but the remote path failed; the name is retained
    /// so Drop still reaps the server, while queries use one-shot mode.
    WarmLost(String),
    OneShot,
    Closed,
}

/// Opened trace plus its verified tool and per-session warm server.
///
/// Send + Sync: the app owns Arc<TraceSession> and queries it from a background
/// executor. Warm and one-shot operations are serialized by the native server
/// or by independent processes; the session only locks short-lived state.
pub struct TraceSession {
    tool: TraceProcessor,
    path: PathBuf,
    source: SourceStamp,
    digest: SourceDigest,
    cache: Mutex<Vec<CachedPage>>,
    warm: Mutex<WarmState>,
}

const MAX_CACHED_PAGES: usize = 8;
const MAX_CACHED_BYTES: usize = 2 * 1024 * 1024;

struct CachedPage {
    preset: QueryPreset,
    offset: usize,
    limit: usize,
    page: QueryPage,
    bytes: usize,
}

struct RawTable {
    columns: Vec<String>,
    rows: Vec<Vec<String>>,
}

impl TraceSession {
    /// Opens the selected trace and starts one warm server. If daemonization is
    /// unavailable the trace is verified through the one-shot path and the
    /// session enters TraceSessionMode::OneShotFallback. An unreadable,
    /// oversized, mutated or unparseable trace fails closed here.
    /// SHA-256 scanning is synchronous; cancellation is checked before/after it.
    pub fn open(
        tool: TraceProcessor,
        selected_trace: &Path,
        cancel: &CancellationToken,
    ) -> Result<Self, PerfettoError> {
        check_cancel(cancel)?;
        let path = selected_trace
            .canonicalize()
            .map_err(|_| PerfettoError::InvalidTrace)?;
        let source = SourceStamp::read(&path)?;
        let digest = SourceDigest::hash_file(&path, MAX_TRACE_BYTES)
            .map_err(|_| PerfettoError::InvalidTrace)?;
        check_cancel(cancel)?;
        let session = Self {
            tool,
            path,
            source,
            digest,
            cache: Mutex::new(Vec::new()),
            warm: Mutex::new(WarmState::OneShot),
        };
        session.check_source()?;
        session.start_warm(cancel)?;
        Ok(session)
    }
    pub fn source_digest(&self) -> SourceDigest {
        self.digest
    }

    pub fn mode(&self) -> TraceSessionMode {
        match &*lock_mutex(&self.warm) {
            WarmState::Warm(_) => TraceSessionMode::Warm,
            WarmState::WarmLost(_) | WarmState::OneShot => TraceSessionMode::OneShotFallback,
            WarmState::Closed => TraceSessionMode::Closed,
        }
    }

    pub fn is_warm(&self) -> bool {
        self.mode().is_warm()
    }

    /// The daemon session name while one is still owned, for diagnostics/tests.
    pub fn warm_session_name(&self) -> Option<String> {
        match &*lock_mutex(&self.warm) {
            WarmState::Warm(name) | WarmState::WarmLost(name) => Some(name.clone()),
            _ => None,
        }
    }

    /// Kills the owned daemon (if any) and makes further queries fail closed.
    /// Idempotent; called automatically from Drop.
    pub fn close(&self) {
        let name = {
            let mut warm = lock_mutex(&self.warm);
            match std::mem::replace(&mut *warm, WarmState::Closed) {
                WarmState::Warm(name) | WarmState::WarmLost(name) => Some(name),
                _ => None,
            }
        };
        if let Some(name) = name {
            self.kill_server(&name);
        }
    }

    /// Existing bounded preset page API used by aps-gpu; now served by the warm
    /// server when available and by the one-shot CSV fallback otherwise.
    pub fn query(
        &self,
        preset: QueryPreset,
        offset: usize,
        limit: usize,
        cancel: &CancellationToken,
    ) -> Result<QueryPage, PerfettoError> {
        if limit == 0
            || limit > MAX_PAGE_SIZE
            || offset > i64::MAX as usize
            || offset.checked_add(limit).is_none()
        {
            return Err(PerfettoError::InvalidPage);
        }
        self.prepare(cancel)?;
        if let Some(page) = self.cached_page(preset, offset, limit) {
            return Ok(page);
        }
        let sql = preset.sql(offset, limit);
        let RawTable {
            columns,
            rows: raw_rows,
        } = self.run_sql(&sql, preset.columns(), limit + 1, cancel)?;
        let has_more = raw_rows.len() > limit;
        let mut rows = Vec::with_capacity(raw_rows.len().min(limit));
        for row in raw_rows.into_iter().take(limit) {
            let mut row: Vec<Option<String>> = row
                .into_iter()
                .map(|value| if value == "[NULL]" { None } else { Some(value) })
                .collect();
            if let Some(index) = preset.name_column()
                && let Some(value) = &mut row[index]
            {
                *value = decode_hex(value)?;
            }
            rows.push(row);
        }
        let page = QueryPage {
            preset,
            source_digest: self.digest,
            columns,
            rows,
            offset,
            has_more,
            text_preview_characters: 4096,
        };
        self.cache_page(page.clone(), limit);
        Ok(page)
    }

    /// Runs one catalog diagnostic against the warm session (or the documented
    /// one-shot fallback) and projects a bounded, schema-validated result.
    pub fn diagnostic(
        &self,
        query: &DiagnosticQuery,
        cancel: &CancellationToken,
    ) -> Result<DiagnosticResult, PerfettoError> {
        self.prepare(cancel)?;
        let RawTable {
            columns,
            rows: raw_rows,
        } = self.run_sql(query.sql, query.columns, MAX_DIAGNOSTIC_ROWS, cancel)?;
        let mut truncated = raw_rows.len() >= MAX_DIAGNOSTIC_ROWS;
        let mut rows = Vec::with_capacity(raw_rows.len().min(MAX_DIAGNOSTIC_ROWS));
        let mut cells_truncated = false;
        for row in raw_rows.into_iter().take(MAX_DIAGNOSTIC_ROWS) {
            let row = row
                .into_iter()
                .map(|cell| {
                    if cell == "[NULL]" {
                        None
                    } else {
                        let (value, cut) = clamp_cell(cell);
                        cells_truncated |= cut;
                        Some(value)
                    }
                })
                .collect();
            rows.push(row);
        }
        truncated |= cells_truncated;
        Ok(DiagnosticResult {
            query_id: query.id,
            schema_version: PINNED_VERSION,
            columns,
            rows,
            truncated,
        })
    }

    /// Android 12+ FrameTimeline rows for the Frame Analyzer. The fixed SQL and
    /// schema above reject anything but a validated positive process id; rows
    /// past MAX_FRAME_TIMELINE_ROWS fail closed with OutputLimit.
    pub fn frame_timeline_rows(
        &self,
        process_id: Option<i32>,
        cancel: &CancellationToken,
    ) -> Result<Vec<frame_timeline::FrameTimelineRow>, PerfettoError> {
        if process_id.is_some_and(|pid| pid <= 0) {
            return Err(PerfettoError::InvalidPage);
        }
        self.prepare(cancel)?;
        let sql = frame_timeline::sql(process_id);
        let RawTable { rows, .. } = self.run_sql(
            &sql,
            frame_timeline::COLUMNS,
            frame_timeline::MAX_FRAME_TIMELINE_ROWS,
            cancel,
        )?;
        if rows.len() >= frame_timeline::MAX_FRAME_TIMELINE_ROWS {
            return Err(PerfettoError::OutputLimit);
        }
        frame_timeline::decode(rows)
    }

    /// Compose TraceSliceSearch + hit projection. Blank terms yield no hits.
    /// limit must be 1..=RESULT_LIMIT; the term is escaped, never interpolated
    /// raw. Rows past limit are never returned.
    pub fn search_slices(
        &self,
        term: &str,
        limit: usize,
        cancel: &CancellationToken,
    ) -> Result<Vec<SearchHit>, PerfettoError> {
        Ok(self.search_slices_bounded(term, limit, cancel)?.hits)
    }

    /// Same search with the bounded-result flag the caller can surface.
    pub fn search_slices_bounded(
        &self,
        term: &str,
        limit: usize,
        cancel: &CancellationToken,
    ) -> Result<SliceSearchResult, PerfettoError> {
        if limit == 0 || limit > search::RESULT_LIMIT {
            return Err(PerfettoError::InvalidPage);
        }
        let Some(query) = TraceSliceSearch::query_with_limit(term, limit) else {
            return Ok(SliceSearchResult {
                term: String::new(),
                hits: Vec::new(),
                truncated: false,
            });
        };
        self.prepare(cancel)?;
        let RawTable { rows: raw_rows, .. } =
            self.run_sql(&query.sql, query.columns, limit, cancel)?;
        let mut truncated = raw_rows.len() >= limit;
        let mut cells_truncated = false;
        let rows: Vec<Vec<Option<String>>> = raw_rows
            .into_iter()
            .take(limit)
            .map(|row| {
                row.into_iter()
                    .map(|cell| {
                        if cell == "[NULL]" {
                            None
                        } else {
                            let (value, cut) = clamp_cell(cell);
                            cells_truncated |= cut;
                            Some(value)
                        }
                    })
                    .collect()
            })
            .collect();
        truncated |= cells_truncated;
        Ok(SliceSearchResult {
            term: query.term,
            hits: decode_search_hits(&rows),
            truncated,
        })
    }

    fn prepare(&self, cancel: &CancellationToken) -> Result<(), PerfettoError> {
        if matches!(*lock_mutex(&self.warm), WarmState::Closed) {
            return Err(PerfettoError::SessionClosed);
        }
        self.tool.check(cancel)?;
        self.check_source()
    }

    fn warm_query_name(&self) -> Option<String> {
        match &*lock_mutex(&self.warm) {
            WarmState::Warm(name) => Some(name.clone()),
            _ => None,
        }
    }

    fn mark_warm_lost(&self) {
        let mut warm = lock_mutex(&self.warm);
        if let WarmState::Warm(name) = &*warm {
            let name = name.clone();
            *warm = WarmState::WarmLost(name);
        }
    }

    /// Dispatch through the warm server when one is owned, then the one-shot
    /// fallback. A failed non-cancelled warm query is reported as a degraded
    /// session and retried once through the one-shot path; it is not silently
    /// retried on every later query.
    fn run_sql(
        &self,
        sql: &str,
        expected: &[&str],
        max_data_rows: usize,
        cancel: &CancellationToken,
    ) -> Result<RawTable, PerfettoError> {
        if let Some(name) = self.warm_query_name() {
            match self.run_remote_query(&name, sql, expected, max_data_rows, cancel) {
                Ok(table) => {
                    self.check_source()?;
                    return Ok(table);
                }
                Err(PerfettoError::Cancelled) => return Err(PerfettoError::Cancelled),
                Err(PerfettoError::Timeout) => return Err(PerfettoError::Timeout),
                Err(PerfettoError::OutputLimit) => return Err(PerfettoError::OutputLimit),
                // Other remote failures are retried once through one-shot. Only
                // a successful fallback proves the remote path (not the SQL)
                // is broken and the session is degraded; a SQL-level error
                // that fails both paths keeps the warm server usable.
                Err(_) => {}
            }
        }
        let table = self.run_one_shot_query(sql, expected, max_data_rows, cancel)?;
        if self.warm_session_name().is_some() && self.is_warm() {
            self.mark_warm_lost();
        }
        self.check_source()?;
        Ok(table)
    }

    fn run_remote_query(
        &self,
        name: &str,
        sql: &str,
        expected: &[&str],
        max_data_rows: usize,
        cancel: &CancellationToken,
    ) -> Result<RawTable, PerfettoError> {
        let mut request = ProcessRequest::new(&self.tool.path)
            .args([
                "query",
                "--remote",
                name,
                "--format",
                "csv",
                "--no-agent",
                "--quiet",
            ])
            .arg(sql);
        request.env_clear = true;
        request.timeout = QUERY_TIMEOUT;
        request.stdout_limit = MAX_OUTPUT_BYTES;
        request.stderr_limit = MAX_STDERR_BYTES;
        let output = ProcessRunner.run(&request, cancel)?;
        if !output.status.success() {
            return Err(PerfettoError::ProcessFailed);
        }
        Self::parse_table(&output.stdout, expected, max_data_rows)
    }

    fn run_one_shot_query(
        &self,
        sql: &str,
        expected: &[&str],
        max_data_rows: usize,
        cancel: &CancellationToken,
    ) -> Result<RawTable, PerfettoError> {
        let mut request = ProcessRequest::new(&self.tool.path)
            .args(["query", "--format", "csv", "--no-agent", "--quiet"])
            .arg(&self.path)
            .arg(sql);
        request.env_clear = true;
        request.timeout = QUERY_TIMEOUT;
        request.stdout_limit = MAX_OUTPUT_BYTES;
        request.stderr_limit = MAX_STDERR_BYTES;
        let output = ProcessRunner.run(&request, cancel)?;
        if !output.status.success() {
            return Err(PerfettoError::ProcessFailed);
        }
        Self::parse_table(&output.stdout, expected, max_data_rows)
    }

    fn parse_table(
        stdout: &[u8],
        expected: &[&str],
        max_data_rows: usize,
    ) -> Result<RawTable, PerfettoError> {
        let mut values = csv::parse(stdout, max_data_rows + 2)?;
        if values.is_empty() {
            return Err(PerfettoError::InvalidResult);
        }
        let columns = values.remove(0);
        if columns.len() != expected.len()
            || columns
                .iter()
                .zip(expected.iter())
                .any(|(actual, declared)| actual != declared)
        {
            return Err(PerfettoError::InvalidResult);
        }
        if values.iter().any(|row| row.len() != columns.len()) {
            return Err(PerfettoError::InvalidResult);
        }
        Ok(RawTable {
            columns,
            rows: values,
        })
    }

    fn start_warm(&self, cancel: &CancellationToken) -> Result<(), PerfettoError> {
        let name = warm_session_name(self.digest);
        let mut request = ProcessRequest::new(&self.tool.path)
            .args([
                "server",
                "unix",
                "--name",
                name.as_str(),
                "--daemonize",
                "--quiet",
                "--idle-timeout",
                WARM_IDLE_TIMEOUT,
            ])
            .arg(&self.path);
        request.env_clear = true;
        request.timeout = WARM_START_TIMEOUT;
        request.stdout_limit = 8 * 1024;
        request.stderr_limit = MAX_STDERR_BYTES;
        match ProcessRunner.run(&request, cancel) {
            Ok(output) if output.status.success() => {
                // Bind ownership before the fallible post-start checks so a
                // failed/cancelled open can never leak the daemon.
                *lock_mutex(&self.warm) = WarmState::Warm(name);
                if let Err(error) = self.check_source() {
                    self.close();
                    return Err(error);
                }
                if cancel.is_cancelled() {
                    self.close();
                    return Err(PerfettoError::Cancelled);
                }
                Ok(())
            }
            Ok(_) => {
                // Daemonization/remote sessions are unavailable on this host
                // (for example a non-POSIX --daemonize build). Verify the trace
                // through the documented one-shot path before accepting
                // fallback so an invalid trace still fails closed.
                self.kill_server(&name);
                self.verify_one_shot(cancel)?;
                *lock_mutex(&self.warm) = WarmState::OneShot;
                Ok(())
            }
            Err(ProcessError::Cancelled) => {
                self.kill_server(&name);
                Err(PerfettoError::Cancelled)
            }
            Err(error) => {
                self.kill_server(&name);
                Err(PerfettoError::from(error))
            }
        }
    }

    /// One bounded one-shot query that only proves the pinned tool can load the
    /// trace. Used only when warm startup is unavailable.
    fn verify_one_shot(&self, cancel: &CancellationToken) -> Result<(), PerfettoError> {
        let mut request = ProcessRequest::new(&self.tool.path)
            .args(["query", "--format", "csv", "--no-agent", "--quiet"])
            .arg(&self.path)
            .arg("SELECT 1 AS ok");
        request.env_clear = true;
        request.timeout = QUERY_TIMEOUT;
        request.stdout_limit = 8 * 1024;
        request.stderr_limit = MAX_STDERR_BYTES;
        match ProcessRunner.run(&request, cancel) {
            Ok(output) if output.status.success() => Ok(()),
            Ok(_) => Err(PerfettoError::InvalidTrace),
            Err(ProcessError::Cancelled) => Err(PerfettoError::Cancelled),
            Err(error) => Err(PerfettoError::from(error)),
        }
    }

    /// Best-effort stop of exactly one named session; never displays raw output.
    /// Bounded wait: server kill signals the daemon but does not wait for it, so
    /// the owned socket is polled briefly to make Drop observably complete. If
    /// the pinned tool's session directory differs, the wait is a no-op and the
    /// 30-minute idle reaper remains the safety net.
    fn kill_server(&self, name: &str) {
        let cancel = CancellationToken::new();
        let mut request = ProcessRequest::new(&self.tool.path).args(["server", "kill", name]);
        request.env_clear = true;
        request.timeout = WARM_KILL_TIMEOUT;
        request.stdout_limit = 4 * 1024;
        request.stderr_limit = 4 * 1024;
        let stopped = ProcessRunner
            .run(&request, &cancel)
            .map(|output| output.status.success())
            .unwrap_or(false);
        if stopped {
            wait_for_session_exit(name);
        }
    }

    fn cached_page(&self, preset: QueryPreset, offset: usize, limit: usize) -> Option<QueryPage> {
        let mut cache = lock_mutex(&self.cache);
        let index = cache.iter().position(|entry| {
            entry.preset == preset && entry.offset == offset && entry.limit == limit
        })?;
        let entry = cache.remove(index);
        let page = entry.page.clone();
        cache.push(entry);
        Some(page)
    }

    fn cache_page(&self, page: QueryPage, limit: usize) {
        let bytes = page.columns.iter().map(String::len).sum::<usize>()
            + page
                .rows
                .iter()
                .flat_map(|row| row.iter())
                .map(|value| value.as_ref().map_or(0, String::len))
                .sum::<usize>();
        if bytes > MAX_CACHED_BYTES {
            return;
        }
        let mut cache = lock_mutex(&self.cache);
        cache.retain(|entry| {
            !(entry.preset == page.preset && entry.offset == page.offset && entry.limit == limit)
        });
        cache.push(CachedPage {
            preset: page.preset,
            offset: page.offset,
            limit,
            page,
            bytes,
        });
        while cache.len() > MAX_CACHED_PAGES || cache_bytes(&cache) > MAX_CACHED_BYTES {
            cache.remove(0);
        }
    }

    fn check_source(&self) -> Result<(), PerfettoError> {
        if SourceStamp::read(&self.path).map_err(|_| PerfettoError::SourceChanged)? != self.source {
            lock_mutex(&self.cache).clear();
            return Err(PerfettoError::SourceChanged);
        }
        Ok(())
    }
}

impl Drop for TraceSession {
    fn drop(&mut self) {
        self.close();
    }
}

/// The verified checkout starts client processes with env_clear, so the pinned
/// tool resolves its session directory to /tmp/perfetto on the supported POSIX
/// hosts. This only verifies cleanup after server kill; it never creates paths.
#[cfg(unix)]
fn wait_for_session_exit(name: &str) {
    let socket = Path::new("/tmp/perfetto").join(format!("{name}.sock"));
    let deadline = Instant::now() + Duration::from_secs(2);
    while socket.exists() && Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(20));
    }
}

#[cfg(not(unix))]
fn wait_for_session_exit(_name: &str) {}

fn cache_bytes(cache: &[CachedPage]) -> usize {
    cache.iter().map(|entry| entry.bytes).sum()
}

static WARM_SESSION_COUNTER: AtomicU64 = AtomicU64::new(1);

/// Unique, shell-safe session name for this process/trace pair. The pinned
/// session-name grammar is [A-Za-z0-9][A-Za-z0-9_-]* with at most 64 characters.
fn warm_session_name(digest: SourceDigest) -> String {
    let counter = WARM_SESSION_COUNTER.fetch_add(1, Ordering::Relaxed);
    let hex = digest.to_hex();
    format!(
        "aps_{}_{}_{}",
        std::process::id(),
        counter,
        &hex[..8.min(hex.len())]
    )
}

fn lock_mutex<T>(mutex: &Mutex<T>) -> MutexGuard<'_, T> {
    mutex
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
}

fn check_cancel(cancel: &CancellationToken) -> Result<(), PerfettoError> {
    if cancel.is_cancelled() {
        Err(PerfettoError::Cancelled)
    } else {
        Ok(())
    }
}

/// Bounds a text preview at a UTF-8 character boundary.
fn clamp_cell(value: String) -> (String, bool) {
    match value.char_indices().nth(MAX_DIAGNOSTIC_CELL_CHARS) {
        Some((index, _)) => {
            let mut value = value;
            value.truncate(index);
            (value, true)
        }
        None => (value, false),
    }
}

fn decode_hex(value: &str) -> Result<String, PerfettoError> {
    if !value.len().is_multiple_of(2) || value.len() > 4096 * 4 * 2 {
        return Err(PerfettoError::InvalidResult);
    }
    let mut bytes = Vec::with_capacity(value.len() / 2);
    for pair in value.as_bytes().as_chunks::<2>().0 {
        let pair = std::str::from_utf8(pair).map_err(|_| PerfettoError::InvalidResult)?;
        bytes.push(u8::from_str_radix(pair, 16).map_err(|_| PerfettoError::InvalidResult)?);
    }
    String::from_utf8(bytes).map_err(|_| PerfettoError::InvalidResult)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn assert_send_sync_static<T: Send + Sync + 'static>() {}

    #[test]
    fn pinned_hash_matches_the_canonical_manifest() {
        let manifest = include_str!(
            "../../../../desktop-viewer/platform-perfetto/trace-processor-manifest.json"
        );
        if let Ok(hash) = pinned_host_sha256() {
            assert!(manifest.contains(hash));
            assert!(manifest.contains(PINNED_VERSION));
        }
    }

    #[test]
    fn trace_session_is_send_sync_static() {
        assert_send_sync_static::<TraceSession>();
        assert_send_sync_static::<DiagnosticQuery>();
        assert_send_sync_static::<DiagnosticResult>();
        assert_send_sync_static::<DiagnosticFinding>();
        assert_send_sync_static::<SearchHit>();
        assert_send_sync_static::<PerfettoError>();
    }

    #[test]
    fn presets_are_read_only_and_have_fixed_ordered_pages() {
        for preset in [
            QueryPreset::TraceBounds,
            QueryPreset::Processes,
            QueryPreset::Threads,
            QueryPreset::Slices,
            QueryPreset::CpuScheduling,
            QueryPreset::GpuFrequency,
            QueryPreset::GpuMemory,
            QueryPreset::GpuRenderStages,
        ] {
            let sql = preset.sql(400, 200);
            assert!(sql.starts_with("SELECT "));
            assert!(sql.ends_with("LIMIT 201 OFFSET 400"));
            assert!(!sql.contains(';'));
            assert!(!sql.contains("READ_FILE"));
            assert!(!preset.columns().is_empty());
        }
    }

    #[test]
    fn text_transport_does_not_confuse_null_quotes_or_unicode() {
        assert_eq!(decode_hex("5B4E554C4C5D").unwrap(), "[NULL]");
        assert_eq!(decode_hex("6122620A").unwrap(), "a\"b\n");
        assert_eq!(decode_hex("E4B8ADE69687").unwrap(), "中文");
        for value in ["0", "GG", "FF"] {
            assert!(decode_hex(value).is_err());
        }
    }

    #[test]
    fn cell_previews_are_bounded_at_a_character_boundary() {
        let (value, truncated) = clamp_cell("ab".to_owned());
        assert_eq!(value, "ab");
        assert!(!truncated);
        let long = "中".repeat(MAX_DIAGNOSTIC_CELL_CHARS + 3);
        let (value, truncated) = clamp_cell(long);
        assert!(truncated);
        assert_eq!(value.chars().count(), MAX_DIAGNOSTIC_CELL_CHARS);
    }

    #[test]
    fn cancellation_prevents_tool_resolution_or_execution() {
        let cancellation = CancellationToken::new();
        cancellation.cancel();
        assert!(matches!(
            TraceProcessor::from_verified_path(Path::new("missing-trace-processor"), &cancellation),
            Err(PerfettoError::Cancelled)
        ));
    }

    #[cfg(unix)]
    mod warm {
        use super::*;
        use std::fs;
        use std::os::unix::fs::PermissionsExt;

        struct Fixture {
            dir: PathBuf,
        }

        impl Fixture {
            fn new(label: &str, mode: &str) -> (Self, TraceProcessor, PathBuf, PathBuf) {
                let dir = std::env::temp_dir().join(format!(
                    "aps-perfetto-test-{}-{}-{}",
                    std::process::id(),
                    label,
                    std::time::SystemTime::now()
                        .duration_since(std::time::UNIX_EPOCH)
                        .map(|duration| duration.as_nanos())
                        .unwrap_or(0)
                ));
                fs::create_dir_all(&dir).expect("temp dir");
                let state = dir.join("state");
                let trace = dir.join("trace.pftrace");
                fs::write(&trace, b"fake trace bytes").expect("trace");
                let script = dir.join("trace_processor_shell");
                let body = format!(
                    "#!/bin/sh\nSTATE='{}'\nMODE='{}'\nif [ \"$1\" = \"server\" ] && [ \"$2\" = \"unix\" ]; then\n  if [ \"$MODE\" = \"nowarm\" ]; then echo 'daemonize unsupported' >&2; exit 1; fi\n  shift 2\n  NAME=''\n  while [ $# -gt 0 ]; do\n    case \"$1\" in\n      --name) NAME=\"$2\"; shift 2 ;;\n      --idle-timeout) shift 2 ;;\n      *) shift ;;\n    esac\n  done\n  printf '%s' \"$NAME\" > \"$STATE\"\n  echo \"perfetto-session pid=1 session=$NAME socket-path=/tmp/$NAME.sock\"\n  exit 0\nfi\nif [ \"$1\" = \"server\" ] && [ \"$2\" = \"kill\" ]; then\n  /bin/rm -f \"$STATE\"\n  exit 0\nfi\nif [ \"$1\" = \"query\" ]; then\n  if [ \"$MODE\" = \"lost\" ] && [ \"$2\" = \"--remote\" ]; then\n    echo 'No live session at fake' >&2\n    exit 1\n  fi\n  if [ \"$MODE\" = \"bothfail\" ]; then\n    echo 'sql failed' >&2\n    exit 1\n  fi\n  SQL=''\n  for ARG in \"$@\"; do SQL=\"$ARG\"; done\n  case \"$SQL\" in\n    *'AS ok'*) printf '\"ok\"\\n1\\n' ;;\n    *'slice_count'*) printf '\"thread_name\",\"process_name\",\"slice_count\",\"total_dur_ms\"\\n\"main\",\"app\",\"3\",\"42\"\\n' ;;\n    *'AS slice_name'*) printf '\"timestamp_ns\",\"duration_ns\",\"slice_name\",\"thread_name\",\"process_name\"\\n10,5,\"draw\",\"main\",\"app\"\\n' ;;\n    *'FROM slice ORDER BY id'*) printf '\"id\",\"ts\",\"dur\",\"track_id\",\"name\"\\n1,2,3,4,\"6d61696e\"\\n' ;;\n    *) printf '\"value\"\\n1\\n' ;;\n  esac\n  exit 0\nfi\nexit 1\n",
                    state.display(),
                    mode
                );
                fs::write(&script, body).expect("script");
                let mut permissions = fs::metadata(&script).expect("metadata").permissions();
                permissions.set_mode(0o755);
                fs::set_permissions(&script, permissions).expect("chmod");
                let digest = SourceDigest::hash_file(&script, MAX_TOOL_BYTES).expect("digest");
                let tool = TraceProcessor {
                    path: script.canonicalize().expect("canonical"),
                    digest,
                };
                (Self { dir }, tool, trace, state)
            }
        }

        impl Drop for Fixture {
            fn drop(&mut self) {
                let _ = fs::remove_dir_all(&self.dir);
            }
        }

        fn open(
            fixture: &(Fixture, TraceProcessor, PathBuf, PathBuf),
            label: &str,
        ) -> Result<TraceSession, PerfettoError> {
            let _ = label;
            TraceSession::open(fixture.1.clone(), &fixture.2, &CancellationToken::new())
        }

        #[test]
        fn warm_open_serves_diagnostics_search_presets_and_drop_kills() {
            let fixture = Fixture::new("warm", "warm");
            let session = open(&fixture, "warm").expect("open");
            assert!(session.is_warm());
            assert_eq!(session.mode(), TraceSessionMode::Warm);
            let name = session.warm_session_name().expect("session name");
            assert!(fixture.3.exists(), "daemon state file");
            assert!(name.starts_with("aps_"));

            let query = diagnostic_query("cpu_hotspots").expect("query");
            let result = session
                .diagnostic(query, &CancellationToken::new())
                .expect("diagnostic");
            assert_eq!(result.query_id, "cpu_hotspots");
            assert_eq!(result.schema_version, PINNED_VERSION);
            assert_eq!(result.rows.len(), 1);
            assert_eq!(result.rows[0][0].as_deref(), Some("main"));
            assert!(!result.truncated);

            let hits = session
                .search_slices("draw", 50, &CancellationToken::new())
                .expect("search");
            assert_eq!(hits.len(), 1);
            assert_eq!(hits[0].timestamp_nanos, 10);
            assert_eq!(hits[0].thread_label, "main · app");

            let page = session
                .query(QueryPreset::Slices, 0, 10, &CancellationToken::new())
                .expect("preset");
            assert_eq!(page.preset, QueryPreset::Slices);
            assert_eq!(page.rows.len(), 1);
            assert_eq!(page.rows[0][4].as_deref(), Some("main"));
            assert!(!page.has_more);

            drop(session);
            assert!(!fixture.3.exists(), "Drop must stop the warm server");
        }

        #[test]
        fn daemonization_unavailable_degrades_to_verified_one_shot() {
            let fixture = Fixture::new("fallback", "nowarm");
            let session = open(&fixture, "fallback").expect("open");
            assert!(!session.is_warm());
            assert_eq!(session.mode(), TraceSessionMode::OneShotFallback);
            assert!(session.warm_session_name().is_none());
            let query = diagnostic_query("cpu_hotspots").expect("query");
            let result = session
                .diagnostic(query, &CancellationToken::new())
                .expect("diagnostic");
            assert_eq!(result.rows.len(), 1);
            assert!(!fixture.3.exists());
        }

        #[test]
        fn lost_warm_server_falls_back_and_keeps_cleanup_ownership() {
            let fixture = Fixture::new("lost", "lost");
            let session = open(&fixture, "lost").expect("open");
            assert!(session.is_warm());
            let query = diagnostic_query("cpu_hotspots").expect("query");
            let result = session
                .diagnostic(query, &CancellationToken::new())
                .expect("diagnostic");
            assert_eq!(result.rows.len(), 1);
            assert!(!session.is_warm());
            assert_eq!(session.mode(), TraceSessionMode::OneShotFallback);
            assert!(fixture.3.exists(), "name must be retained for Drop cleanup");
            drop(session);
            assert!(!fixture.3.exists(), "Drop must stop the lost session too");
        }

        #[test]
        fn sql_level_errors_keep_the_warm_server_usable() {
            let fixture = Fixture::new("bothfail", "bothfail");
            let session = open(&fixture, "bothfail").expect("open");
            assert!(session.is_warm());
            let query = diagnostic_query("cpu_hotspots").expect("query");
            assert_eq!(
                session.diagnostic(query, &CancellationToken::new()),
                Err(PerfettoError::ProcessFailed)
            );
            // Both the warm and one-shot paths failed with this SQL, so the
            // warm session itself is not degraded.
            assert!(session.is_warm());
            assert_eq!(session.mode(), TraceSessionMode::Warm);
        }

        #[test]
        fn closed_session_fails_closed() {
            let fixture = Fixture::new("closed", "warm");
            let session = open(&fixture, "closed").expect("open");
            session.close();
            assert_eq!(session.mode(), TraceSessionMode::Closed);
            let query = diagnostic_query("cpu_hotspots").expect("query");
            assert_eq!(
                session.diagnostic(query, &CancellationToken::new()),
                Err(PerfettoError::SessionClosed)
            );
        }

        #[test]
        fn invalid_limit_is_rejected_for_search() {
            let fixture = Fixture::new("limits", "warm");
            let session = open(&fixture, "limits").expect("open");
            let cancel = CancellationToken::new();
            assert_eq!(
                session.search_slices("x", 0, &cancel),
                Err(PerfettoError::InvalidPage)
            );
            assert_eq!(
                session.search_slices("x", search::RESULT_LIMIT + 1, &cancel),
                Err(PerfettoError::InvalidPage)
            );
            assert_eq!(
                session
                    .search_slices("   ", 10, &cancel)
                    .expect("blank term"),
                Vec::new()
            );
        }
    }
}

//! Version- and checksum-pinned, read-only Trace Processor CLI adapter.
//!
//! One-shot queries deliberately use the documented v59.1 CSV interface. There
//! is no invented JSON protocol, arbitrary SQL, PATH fallback, downloader, cloud
//! connection, device command or daemon. Each query reparses the trace; warm RPC
//! context reuse is an explicit future performance/parity gap.

use aps_contracts::SourceDigest;
use aps_platform::{CancellationToken, ProcessError, ProcessRequest, ProcessRunner};
use std::fmt;
use std::fs::{File, Metadata};
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use std::time::{Duration, SystemTime};

mod csv;
pub const PINNED_VERSION: &str = "v59.1";
pub const MAX_PAGE_SIZE: usize = 200;
pub const MAX_TRACE_BYTES: u64 = 8 * 1024 * 1024 * 1024;
const MAX_TOOL_BYTES: u64 = 256 * 1024 * 1024;
const MAX_OUTPUT_BYTES: usize = 4 * 1024 * 1024;

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

/// Immutable selected input plus its verified tool. Not a loaded/warm TP context.
pub struct TraceSession {
    tool: TraceProcessor,
    path: PathBuf,
    source: SourceStamp,
    digest: SourceDigest,
    cache: Mutex<Vec<CachedPage>>,
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
impl TraceSession {
    /// Does not parse the trace yet; queries invoke the real native processor.
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
        };
        session.check_source()?;
        Ok(session)
    }
    pub fn source_digest(&self) -> SourceDigest {
        self.digest
    }

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
        self.tool.check(cancel)?;
        self.check_source()?;
        if let Some(page) = self.cached_page(preset, offset, limit) {
            return Ok(page);
        }
        let sql = preset.sql(offset, limit);
        let mut request = ProcessRequest::new(&self.tool.path)
            .args(["query", "--format", "csv", "--no-agent", "--quiet"])
            .arg(&self.path)
            .arg(sql);
        request.env_clear = true;
        request.timeout = Duration::from_secs(120);
        request.stdout_limit = MAX_OUTPUT_BYTES;
        request.stderr_limit = 64 * 1024;
        let output = ProcessRunner.run(&request, cancel)?;
        if !output.status.success() {
            return Err(PerfettoError::ProcessFailed);
        }
        self.check_source()?;
        let mut values = csv::parse(&output.stdout, limit + 2)?;
        if values.is_empty() {
            return Err(PerfettoError::InvalidResult);
        }
        let columns = values.remove(0);
        if columns != preset.columns() || values.iter().any(|row| row.len() != columns.len()) {
            return Err(PerfettoError::InvalidResult);
        }
        let has_more = values.len() > limit;
        values.truncate(limit);
        let mut rows = Vec::with_capacity(values.len());
        for row in values {
            let mut row: Vec<_> = row
                .into_iter()
                .map(|value| if value == "[NULL]" { None } else { Some(value) })
                .collect();
            if let Some(index) = preset.name_column() {
                if let Some(value) = &mut row[index] {
                    *value = decode_hex(value)?;
                }
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

    fn cached_page(&self, preset: QueryPreset, offset: usize, limit: usize) -> Option<QueryPage> {
        let mut cache = self.cache.lock().ok()?;
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
        let Ok(mut cache) = self.cache.lock() else {
            return;
        };
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
            if let Ok(mut cache) = self.cache.lock() {
                cache.clear();
            }
            return Err(PerfettoError::SourceChanged);
        }
        Ok(())
    }
}

fn cache_bytes(cache: &[CachedPage]) -> usize {
    cache.iter().map(|entry| entry.bytes).sum()
}

fn check_cancel(cancel: &CancellationToken) -> Result<(), PerfettoError> {
    if cancel.is_cancelled() {
        Err(PerfettoError::Cancelled)
    } else {
        Ok(())
    }
}
fn decode_hex(value: &str) -> Result<String, PerfettoError> {
    if value.len() % 2 != 0 || value.len() > 4096 * 4 * 2 {
        return Err(PerfettoError::InvalidResult);
    }
    let mut bytes = Vec::with_capacity(value.len() / 2);
    for pair in value.as_bytes().chunks_exact(2) {
        let pair = std::str::from_utf8(pair).map_err(|_| PerfettoError::InvalidResult)?;
        bytes.push(u8::from_str_radix(pair, 16).map_err(|_| PerfettoError::InvalidResult)?);
    }
    String::from_utf8(bytes).map_err(|_| PerfettoError::InvalidResult)
}

#[cfg(test)]
mod tests {
    use super::*;

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
    fn cancellation_prevents_tool_resolution_or_execution() {
        let cancellation = CancellationToken::new();
        cancellation.cancel();
        assert!(matches!(
            TraceProcessor::from_verified_path(Path::new("missing-trace-processor"), &cancellation),
            Err(PerfettoError::Cancelled)
        ));
    }
}

//! Bounded, redacted HAR import and explicitly observed Agent snapshots.
//!
//! Raw entry frames exist only transiently inside the parser/adapter. Structured
//! request identity is redacted before retention: scheme, host and port survive,
//! while userinfo, paths, query values, cookies, bodies and headers (except
//! content metadata) are removed. Whole-session queries retain an index of
//! bounded, redacted entries; per-entry phase and header evidence is parsed on
//! demand and never persisted by this adapter.

mod observed;
mod redact;
pub use observed::{
    OBSERVED_MINIMIZATION_POLICY, ObservedCapture, ObservedConcurrency, ObservedOrigin,
    ObservedOutcome, ObservedPhase, ObservedPhaseKind, ObservedPhaseSummary, ObservedRequest,
};
pub use redact::{
    INVALID_URL, MAX_HEADER_NAME_CHARS, MAX_HEADER_VALUE_CHARS, MAX_HEADERS,
    MAX_REDACTED_URL_CHARS, REDACTED_PATH, REDACTED_VALUE, format_rfc3339_ms, parse_rfc3339_ms,
    redact_headers, redact_url, redact_url_with,
};

use aps_contracts::SourceDigest;
use aps_har_engine::json::Parser;
use aps_platform::CancellationToken;
use std::collections::BTreeMap;
use std::fmt;
use std::fs::{File, Metadata};
use std::io::{self, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::{Arc, Mutex};
use std::time::SystemTime;

pub const MAX_PAGE_SIZE: usize = 200;
pub const MAX_FILE_BYTES: u64 = 2 * 1024 * 1024 * 1024;
pub const MAX_QUERY_BYTES: usize = 128;
pub const MAX_PROTOCOL_CHARS: usize = 32;
pub const MAX_PHASES: usize = 12;
pub const MAX_DOMAINS: usize = 2_000;
pub const ACTIVITY_BUCKETS: usize = 24;
/// Structured redaction replaces the former fully minimized URL constant.
pub const MINIMIZATION_POLICY: &str = "aps-network-redacted-v2";
const MAX_FRAME_BYTES: usize = 2 * 1024 * 1024;
const MAX_CACHED_PAGES: usize = 8;
const MAX_CACHED_BYTES: usize = 512 * 1024;

#[derive(Clone, Copy, Debug)]
pub struct ImportLimits {
    pub max_file_bytes: u64,
    pub max_entries: u64,
}

impl Default for ImportLimits {
    fn default() -> Self {
        Self {
            max_file_bytes: 512 * 1024 * 1024,
            max_entries: 100_000,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum NetworkError {
    InvalidLimit,
    UnreadableFile,
    FileTooLarge,
    SourceChanged,
    Cancelled,
    InvalidHar,
    InvalidPage,
    InvalidQuery,
}

impl fmt::Display for NetworkError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::InvalidLimit => "HAR import limit is invalid",
            Self::UnreadableFile => "Cannot read the selected HAR file",
            Self::FileTooLarge => "HAR file exceeds the import byte limit",
            Self::SourceChanged => "Selected HAR file changed; import it again",
            Self::Cancelled => "HAR import was cancelled",
            Self::InvalidHar => "HAR data is invalid or exceeds parser limits",
            Self::InvalidPage => "HAR page must contain 1 to 200 entries",
            Self::InvalidQuery => {
                "Network query is invalid or unsupported by the selected evidence source"
            }
        })
    }
}
impl std::error::Error for NetworkError {}

#[derive(Debug)]
pub struct ByteTotals {
    pub known_count: u64,
    pub unknown_count: u64,
    pub total_bytes: u64,
}
impl From<aps_har_engine::ByteSummary> for ByteTotals {
    fn from(value: aps_har_engine::ByteSummary) -> Self {
        Self {
            known_count: value.known_count,
            unknown_count: value.unknown_count,
            total_bytes: value.total_bytes,
        }
    }
}

#[derive(Debug)]
pub struct HarSummary {
    pub har_version: String,
    pub entry_count: u64,
    pub status_families: BTreeMap<String, u64>,
    pub request_bytes: ByteTotals,
    pub response_bytes: ByteTotals,
    pub decoded_response_bytes: ByteTotals,
    pub mean_duration_ms: Option<f64>,
    pub max_duration_ms: Option<f64>,
    pub valid_duration_count: u64,
    pub missing_duration_count: u64,
    pub invalid_duration_count: u64,
    pub minimization_policy: &'static str,
    pub source_digest: SourceDigest,
}

/// Terminal state of one call. HAR import can establish completed, failed or
/// incomplete evidence; Agent observations add cancelled and in-flight states.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum EntryOutcome {
    Completed,
    Failed,
    Cancelled,
    Incomplete,
    InFlight,
}
impl EntryOutcome {
    pub const fn label(self) -> &'static str {
        match self {
            Self::Completed => "completed",
            Self::Failed => "failed",
            Self::Cancelled => "cancelled",
            Self::Incomplete => "incomplete",
            Self::InFlight => "in-flight",
        }
    }
}

/// Connection reuse inferred from the absence of DNS/CONNECT/TLS evidence.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ConnectionUse {
    New,
    Reused,
    Unknown,
}
impl ConnectionUse {
    pub const fn name(self) -> &'static str {
        match self {
            Self::New => "NEW",
            Self::Reused => "REUSED",
            Self::Unknown => "UNKNOWN",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum PhaseKind {
    DispatcherQueue,
    ProxySelect,
    Dns,
    Connect,
    Tls,
    RequestHeaders,
    RequestBody,
    ServerWait,
    ResponseHeaders,
    ResponseBody,
    ConnectionHeld,
    Total,
}
impl PhaseKind {
    pub const ALL: [Self; MAX_PHASES] = [
        Self::DispatcherQueue,
        Self::ProxySelect,
        Self::Dns,
        Self::Connect,
        Self::Tls,
        Self::RequestHeaders,
        Self::RequestBody,
        Self::ServerWait,
        Self::ResponseHeaders,
        Self::ResponseBody,
        Self::ConnectionHeld,
        Self::Total,
    ];
    pub const fn name(self) -> &'static str {
        match self {
            Self::DispatcherQueue => "DISPATCHER_QUEUE",
            Self::ProxySelect => "PROXY_SELECT",
            Self::Dns => "DNS",
            Self::Connect => "CONNECT",
            Self::Tls => "TLS",
            Self::RequestHeaders => "REQUEST_HEADERS",
            Self::RequestBody => "REQUEST_BODY",
            Self::ServerWait => "SERVER_WAIT",
            Self::ResponseHeaders => "RESPONSE_HEADERS",
            Self::ResponseBody => "RESPONSE_BODY",
            Self::ConnectionHeld => "CONNECTION_HELD",
            Self::Total => "TOTAL",
        }
    }
    fn position(self) -> usize {
        Self::ALL
            .iter()
            .position(|kind| *kind == self)
            .expect("phase kind is part of ALL")
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PhaseAvailability {
    Value,
    NotApplicable,
    Unavailable,
    Invalid,
}
impl PhaseAvailability {
    pub const fn name(self) -> &'static str {
        match self {
            Self::Value => "VALUE",
            Self::NotApplicable => "NOT_APPLICABLE",
            Self::Unavailable => "UNAVAILABLE",
            Self::Invalid => "INVALID",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PhaseConfidence {
    Exact,
    Derived,
    Inferred,
    Approximated,
    Partial,
    Unknown,
}
impl PhaseConfidence {
    pub const fn name(self) -> &'static str {
        match self {
            Self::Exact => "EXACT",
            Self::Derived => "DERIVED",
            Self::Inferred => "INFERRED",
            Self::Approximated => "APPROXIMATED",
            Self::Partial => "PARTIAL",
            Self::Unknown => "UNKNOWN",
        }
    }
}

#[derive(Clone, Debug, PartialEq)]
pub struct EntryPhase {
    pub kind: PhaseKind,
    pub duration_ms: Option<f64>,
    pub availability: PhaseAvailability,
    pub confidence: PhaseConfidence,
    pub parent: Option<PhaseKind>,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub struct EntryDetail {
    pub request_headers: Vec<(String, String)>,
    pub response_headers: Vec<(String, String)>,
    pub phases: Vec<EntryPhase>,
    /// Bounded failure type; never a raw error message.
    pub failure_type: Option<String>,
    pub tls_version: Option<String>,
    pub cipher_suite: Option<String>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct PhaseDurationSummary {
    pub kind: PhaseKind,
    pub sample_count: u64,
    pub missing_count: u64,
    pub median_ms: Option<f64>,
    pub p95_ms: Option<f64>,
}

/// Whole-session analysis derived from the same redacted entries the table
/// shows. Timings describe the recorded evidence; they are not a coverage claim.
#[derive(Clone, Debug, Default, PartialEq)]
pub struct NetworkAnalysis {
    pub completed_count: u64,
    pub failed_count: u64,
    pub cancelled_count: u64,
    pub incomplete_count: u64,
    pub in_flight_count: u64,
    pub new_connection_count: u64,
    pub reused_connection_count: u64,
    pub unknown_connection_count: u64,
    pub p90_ms: Option<f64>,
    pub phase_summaries: Vec<PhaseDurationSummary>,
    pub invalid_timing_count: u64,
    pub started_at_ms: Option<i64>,
    pub ended_at_ms: Option<i64>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DomainSummary {
    pub domain: Option<String>,
    pub call_count: u64,
    pub failure_count: u64,
    /// Available only when every counted call reports both byte directions.
    pub transferred_bytes: Option<u64>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct NetworkActivity {
    pub origin_ns: u64,
    pub duration_ns: u64,
    pub timed_calls: u64,
    pub omitted_calls: u64,
    pub incomplete_byte_calls: u64,
    /// Bytes assigned to the bucket where the call ended; None means one of the
    /// calls in that bucket has an unknown byte count.
    pub buckets: Vec<Option<u64>>,
    pub peak_concurrency: usize,
    pub average_concurrency: Option<f64>,
}

/// Structured request identity retained by the index. Never contains userinfo,
/// paths, query values, headers, cookies or bodies.
#[derive(Clone, Debug, PartialEq)]
pub struct NetworkEntry {
    pub index: u64,
    pub method: &'static str,
    pub status: u16,
    pub duration_ms: Option<f64>,
    pub request_bytes: Option<u64>,
    pub response_bytes: Option<u64>,
    /// Redacted URL: scheme, host, optional port, path placeholder and redacted
    /// query values.
    pub redacted_url: String,
    pub protocol: Option<String>,
    pub outcome: EntryOutcome,
    pub connection_use: ConnectionUse,
    /// Absolute producer timestamp for HAR entries; None for Agent entries.
    pub started_at_ms: Option<i64>,
}

/// HTTP response classes, not network call outcomes. A 4xx/5xx response does
/// not establish a transport failure, and status zero remains unknown.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum StatusFilter {
    #[default]
    All,
    Success2xx3xx,
    ClientError4xx,
    ServerError5xx,
    Unknown,
}

/// Call outcome filter, independent of HTTP status.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum OutcomeFilter {
    #[default]
    All,
    Completed,
    Failed,
    Cancelled,
    Incomplete,
    InFlight,
}
impl OutcomeFilter {
    fn matches(self, outcome: EntryOutcome) -> bool {
        matches!(
            (self, outcome),
            (Self::All, _)
                | (Self::Completed, EntryOutcome::Completed)
                | (Self::Failed, EntryOutcome::Failed)
                | (Self::Cancelled, EntryOutcome::Cancelled)
                | (Self::Incomplete, EntryOutcome::Incomplete)
                | (Self::InFlight, EntryOutcome::InFlight)
        )
    }
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum EntrySort {
    #[default]
    Entry,
    DurationDescending,
    ResponseBytesDescending,
}

/// Filters apply to the entire selected session before paging. Search inspects
/// redacted presentation fields only.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct EntryQuery {
    pub status: StatusFilter,
    pub outcome: OutcomeFilter,
    pub sort: EntrySort,
    pub method: Option<String>,
    pub search: String,
}

impl EntryQuery {
    fn validate(&self) -> Result<(), NetworkError> {
        if self.search.len() > MAX_QUERY_BYTES
            || self.method.as_deref().is_some_and(|method| {
                !matches!(
                    method,
                    "GET"
                        | "POST"
                        | "PUT"
                        | "PATCH"
                        | "DELETE"
                        | "HEAD"
                        | "OPTIONS"
                        | "CONNECT"
                        | "TRACE"
                        | "OTHER"
                )
            })
        {
            return Err(NetworkError::InvalidQuery);
        }
        Ok(())
    }

    fn matches(&self, entry: &NetworkEntry, search: &str) -> bool {
        let status_matches = match self.status {
            StatusFilter::All => true,
            StatusFilter::Success2xx3xx => (200..400).contains(&entry.status),
            StatusFilter::ClientError4xx => (400..500).contains(&entry.status),
            StatusFilter::ServerError5xx => (500..600).contains(&entry.status),
            StatusFilter::Unknown => entry.status == 0,
        };
        if !status_matches
            || !self.outcome.matches(entry.outcome)
            || self
                .method
                .as_deref()
                .is_some_and(|method| method != entry.method)
        {
            return false;
        }
        search.is_empty()
            || entry.method.to_ascii_lowercase().contains(search)
            || entry.redacted_url.to_ascii_lowercase().contains(search)
            || entry
                .protocol
                .as_deref()
                .is_some_and(|protocol| protocol.to_ascii_lowercase().contains(search))
            || entry.outcome.label().contains(search)
            || entry
                .connection_use
                .name()
                .to_ascii_lowercase()
                .contains(search)
            || entry.index.to_string().contains(search)
            || entry.status.to_string().contains(search)
            || entry
                .duration_ms
                .is_some_and(|value| value.to_string().contains(search))
            || entry
                .request_bytes
                .is_some_and(|value| value.to_string().contains(search))
            || entry
                .response_bytes
                .is_some_and(|value| value.to_string().contains(search))
    }
}

#[derive(Clone, Debug)]
pub struct EntryPage {
    pub offset: usize,
    pub total: u64,
    pub rows: Vec<NetworkEntry>,
    pub minimization_policy: &'static str,
    pub source_digest: SourceDigest,
}

/// Duration statistics derived from HAR producer timing values. These values
/// describe the timing fields present in the imported artifact only; they do
/// not establish event coverage or prove that all network activity was observed.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct DurationPercentiles {
    pub sample_count: u64,
    pub missing_count: u64,
    pub median_ms: Option<f64>,
    pub p90_ms: Option<f64>,
    pub p95_ms: Option<f64>,
}

#[derive(PartialEq, Eq)]
struct SourceStamp {
    length: u64,
    modified: SystemTime,
    created: Option<SystemTime>,
    #[cfg(unix)]
    identity: (u64, u64, i64, i64),
}
impl SourceStamp {
    fn from_metadata(meta: Metadata) -> Result<Self, NetworkError> {
        if !meta.is_file() {
            return Err(NetworkError::UnreadableFile);
        }
        Ok(Self {
            length: meta.len(),
            modified: meta.modified().map_err(|_| NetworkError::UnreadableFile)?,
            created: meta.created().ok(),
            #[cfg(unix)]
            identity: {
                use std::os::unix::fs::MetadataExt;
                (meta.dev(), meta.ino(), meta.ctime(), meta.ctime_nsec())
            },
        })
    }
}

/// A selected HAR file or an explicit, immutable Agent observation. Retains only
/// redacted evidence. File metadata detects mutation; an observed-source digest
/// identifies its redacted snapshot, never the original wire stream.
pub struct HarSession {
    path: PathBuf,
    source: Option<SourceStamp>,
    observed: Option<Arc<ObservedCapture>>,
    limits: ImportLimits,
    summary: HarSummary,
    cache: Mutex<Vec<CachedPage>>,
    index: Mutex<Option<Arc<Vec<NetworkEntry>>>>,
    analysis: Mutex<Option<Arc<NetworkAnalysis>>>,
}

struct CachedPage {
    offset: usize,
    limit: usize,
    page: EntryPage,
    bytes: usize,
}

impl HarSession {
    /// Blocking: schedule on a background executor. The source is opened read-only.
    pub fn open(
        path: &Path,
        limits: ImportLimits,
        cancel: &CancellationToken,
    ) -> Result<Self, NetworkError> {
        if limits.max_file_bytes == 0
            || limits.max_file_bytes > MAX_FILE_BYTES
            || limits.max_entries == 0
            || limits.max_entries > aps_har_engine::MAX_ENTRIES
        {
            return Err(NetworkError::InvalidLimit);
        }
        if cancel.is_cancelled() {
            return Err(NetworkError::Cancelled);
        }
        let path = path
            .canonicalize()
            .map_err(|_| NetworkError::UnreadableFile)?;
        let mut file = File::open(&path).map_err(|_| NetworkError::UnreadableFile)?;
        let source =
            SourceStamp::from_metadata(file.metadata().map_err(|_| NetworkError::UnreadableFile)?)?;
        if source.length > limits.max_file_bytes {
            return Err(NetworkError::FileTooLarge);
        }
        // Hash, rewind and parse the same descriptor. Hashing a fresh path
        // handle could bind descriptor A's summary to replacement B's digest.
        let source_digest =
            hash_open_source(&path, &mut file, &source, limits.max_file_bytes, cancel)?;
        let raw = aps_har_engine::summarize_with_entry_limit(
            CheckedReader::new(&mut file, limits.max_file_bytes, cancel),
            limits.max_entries,
        )
        .map_err(|_| {
            if cancel.is_cancelled() {
                NetworkError::Cancelled
            } else {
                NetworkError::InvalidHar
            }
        })?;
        if hash_open_source(&path, &mut file, &source, limits.max_file_bytes, cancel)?
            != source_digest
        {
            return Err(NetworkError::SourceChanged);
        }
        let summary = HarSummary {
            har_version: raw.har_version,
            entry_count: raw.entry_count,
            status_families: raw.status_families,
            request_bytes: raw.request_bytes.into(),
            response_bytes: raw.response_bytes.into(),
            decoded_response_bytes: raw.decoded_response_bytes.into(),
            mean_duration_ms: raw.durations.mean_ms,
            max_duration_ms: raw.durations.max_ms,
            valid_duration_count: raw.durations.sample_count,
            missing_duration_count: raw.durations.missing_count,
            invalid_duration_count: raw.durations.invalid_count,
            minimization_policy: MINIMIZATION_POLICY,
            source_digest,
        };
        let session = Self {
            path,
            source: Some(source),
            observed: None,
            limits,
            summary,
            cache: Mutex::new(Vec::new()),
            index: Mutex::new(None),
            analysis: Mutex::new(None),
        };
        session.check_source()?;
        if cancel.is_cancelled() {
            return Err(NetworkError::Cancelled);
        }
        Ok(session)
    }

    pub fn summary(&self) -> &HarSummary {
        &self.summary
    }

    /// An explicitly observed, immutable memory projection, not a HAR file.
    /// The digest identifies the redacted snapshot, never the original wire
    /// stream. No raw traffic or filesystem path is fabricated for this source.
    pub fn from_observed(capture: ObservedCapture) -> Result<Self, NetworkError> {
        let (summary, entries) = capture.project()?;
        Ok(Self {
            path: PathBuf::new(),
            source: None,
            observed: Some(Arc::new(capture)),
            limits: ImportLimits::default(),
            summary,
            cache: Mutex::new(Vec::new()),
            index: Mutex::new(Some(Arc::new(entries))),
            analysis: Mutex::new(None),
        })
    }

    pub fn observed(&self) -> Option<&ObservedCapture> {
        self.observed.as_deref()
    }

    /// Explicit whole-session export boundary. Only redacted entries reach the
    /// visitor; the source is checked before and after the complete traversal.
    /// Callers must discard their output if this returns an error.
    pub fn visit_entries(
        &self,
        cancel: &CancellationToken,
        mut visitor: impl FnMut(&NetworkEntry) -> Result<(), String>,
    ) -> Result<(), String> {
        let check = || -> Result<(), String> {
            if cancel.is_cancelled() {
                return Err(NetworkError::Cancelled.to_string());
            }
            self.check_source().map_err(|error| error.to_string())?;
            self.check_digest(cancel).map_err(|error| error.to_string())
        };
        check()?;
        let entries = self
            .minimized_index(cancel)
            .map_err(|error| error.to_string())?;
        for entry in entries.iter() {
            if cancel.is_cancelled() {
                return Err(NetworkError::Cancelled.to_string());
            }
            visitor(entry)?;
        }
        check()
    }

    /// Paged entries for the selected source. HAR pages share the same redacted
    /// whole-session index used by filtering, so source identity is verified
    /// before returning any page.
    pub fn entries(
        &self,
        offset: usize,
        limit: usize,
        cancel: &CancellationToken,
    ) -> Result<EntryPage, NetworkError> {
        if cancel.is_cancelled() {
            return Err(NetworkError::Cancelled);
        }
        self.check_source()?;
        self.check_digest(cancel)?;
        if limit > 0
            && limit <= MAX_PAGE_SIZE
            && offset.checked_add(limit).is_some()
            && let Some(page) = self.cached_page(offset, limit)
        {
            return Ok(page);
        }
        self.query_entries(offset, limit, &EntryQuery::default(), cancel)
    }

    /// Blocking whole-session query. Only a bounded page leaves the adapter;
    /// the lazy index contains at most ImportLimits::max_entries redacted
    /// entries. Equal values sort by original index and missing values sort
    /// after known values. Source identity is revalidated even when the
    /// minimized index is already cached.
    pub fn query_entries(
        &self,
        offset: usize,
        limit: usize,
        query: &EntryQuery,
        cancel: &CancellationToken,
    ) -> Result<EntryPage, NetworkError> {
        if limit == 0 || limit > MAX_PAGE_SIZE || offset.checked_add(limit).is_none() {
            return Err(NetworkError::InvalidPage);
        }
        query.validate()?;
        if cancel.is_cancelled() {
            return Err(NetworkError::Cancelled);
        }
        self.check_source()?;
        self.check_digest(cancel)?;
        let index = self.minimized_index(cancel)?;
        let search = query.search.trim().to_ascii_lowercase();
        let mut matches: Vec<&NetworkEntry> = index
            .iter()
            .filter(|entry| query.matches(entry, &search))
            .collect();
        matches.sort_unstable_by(|left, right| {
            let value_order = match query.sort {
                EntrySort::Entry => std::cmp::Ordering::Equal,
                EntrySort::DurationDescending => match (left.duration_ms, right.duration_ms) {
                    (Some(left), Some(right)) => right.total_cmp(&left),
                    (Some(_), None) => std::cmp::Ordering::Less,
                    (None, Some(_)) => std::cmp::Ordering::Greater,
                    (None, None) => std::cmp::Ordering::Equal,
                },
                EntrySort::ResponseBytesDescending => {
                    match (left.response_bytes, right.response_bytes) {
                        (Some(left), Some(right)) => right.cmp(&left),
                        (Some(_), None) => std::cmp::Ordering::Less,
                        (None, Some(_)) => std::cmp::Ordering::Greater,
                        (None, None) => std::cmp::Ordering::Equal,
                    }
                }
            };
            value_order.then_with(|| left.index.cmp(&right.index))
        });
        let total = matches.len() as u64;
        let rows = matches
            .into_iter()
            .skip(offset)
            .take(limit)
            .cloned()
            .collect();
        self.check_source()?;
        self.check_digest(cancel)?;
        if cancel.is_cancelled() {
            return Err(NetworkError::Cancelled);
        }
        let page = EntryPage {
            offset,
            total,
            rows,
            minimization_policy: self.summary.minimization_policy,
            source_digest: self.summary.source_digest,
        };
        if query == &EntryQuery::default() {
            self.cache_page(page.clone(), limit);
        }
        Ok(page)
    }

    /// Parses and returns phase/header evidence for one already-indexed entry.
    /// Nothing from this call is persisted.
    pub fn entry_detail(
        &self,
        index: u64,
        cancel: &CancellationToken,
    ) -> Result<Option<EntryDetail>, NetworkError> {
        self.check_digest(cancel)?;
        if let Some(capture) = &self.observed {
            let Some(request) = capture.request(index) else {
                return Ok(None);
            };
            return Ok(Some(observed_detail(request, capture.start_monotonic_ns)));
        }
        let mut requested = None;
        self.stream_frames(cancel, |frame_index, bytes| {
            if frame_index == index {
                requested = Some(detail_from_frame(bytes).map_err(|_| {
                    io::Error::new(io::ErrorKind::InvalidData, "invalid HAR entry")
                })?);
            }
            Ok(())
        })?;
        Ok(requested)
    }

    /// Whole-session detail traversal for exports. The source is verified before
    /// and after; callers must discard partial output on error.
    pub fn visit_details(
        &self,
        cancel: &CancellationToken,
        mut visitor: impl FnMut(&NetworkEntry, &EntryDetail) -> Result<(), String>,
    ) -> Result<(), String> {
        if let Some(capture) = &self.observed {
            let entries = self
                .minimized_index(cancel)
                .map_err(|error| error.to_string())?;
            for entry in entries.iter() {
                if cancel.is_cancelled() {
                    return Err(NetworkError::Cancelled.to_string());
                }
                let Some(request) = capture.request(entry.index) else {
                    continue;
                };
                visitor(entry, &observed_detail(request, capture.start_monotonic_ns))?;
            }
            return Ok(());
        }
        if cancel.is_cancelled() {
            return Err(NetworkError::Cancelled.to_string());
        }
        self.check_source().map_err(|error| error.to_string())?;
        self.check_digest(cancel)
            .map_err(|error| error.to_string())?;
        let entries = self
            .minimized_index(cancel)
            .map_err(|error| error.to_string())?;
        self.stream_frames(cancel, |index, bytes| -> io::Result<()> {
            let Some(entry) = entries.get(index as usize) else {
                return Ok(());
            };
            let detail = detail_from_frame(bytes)
                .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid HAR entry"))?;
            visitor(entry, &detail).map_err(io::Error::other)
        })
        .map_err(|error| error.to_string())?;
        self.check_source().map_err(|error| error.to_string())?;
        self.check_digest(cancel)
            .map_err(|error| error.to_string())?;
        Ok(())
    }

    /// Whole-session analysis. Cached per immutable source snapshot.
    pub fn analysis(&self, cancel: &CancellationToken) -> Result<NetworkAnalysis, NetworkError> {
        self.check_source()?;
        self.check_digest(cancel)?;
        if let Some(analysis) = self
            .analysis
            .lock()
            .map_err(|_| NetworkError::InvalidHar)?
            .clone()
        {
            return Ok((*analysis).clone());
        }
        let index = self.minimized_index(cancel)?;
        let mut analysis = NetworkAnalysis::default();
        let mut durations = Vec::with_capacity(index.len());
        let mut phase_values: Vec<Vec<f64>> = vec![Vec::new(); MAX_PHASES];
        let mut phase_missing = [0_u64; MAX_PHASES];
        let mut invalid_timing_count = 0_u64;
        for (position, entry) in index.iter().enumerate() {
            if position % 256 == 0 && cancel.is_cancelled() {
                return Err(NetworkError::Cancelled);
            }
            match entry.outcome {
                EntryOutcome::Completed => analysis.completed_count += 1,
                EntryOutcome::Failed => analysis.failed_count += 1,
                EntryOutcome::Cancelled => analysis.cancelled_count += 1,
                EntryOutcome::Incomplete => analysis.incomplete_count += 1,
                EntryOutcome::InFlight => analysis.in_flight_count += 1,
            }
            match entry.connection_use {
                ConnectionUse::New => analysis.new_connection_count += 1,
                ConnectionUse::Reused => analysis.reused_connection_count += 1,
                ConnectionUse::Unknown => analysis.unknown_connection_count += 1,
            }
            if let Some(duration) = entry
                .duration_ms
                .filter(|value| value.is_finite() && *value >= 0.0)
            {
                durations.push(duration);
            }
            if let Some(start) = entry.started_at_ms {
                analysis.started_at_ms = Some(
                    analysis
                        .started_at_ms
                        .map_or(start, |current| current.min(start)),
                );
                if let Some(duration) = entry.duration_ms {
                    let end = start.saturating_add((duration * 1_000_000.0) as i64);
                    analysis.ended_at_ms =
                        Some(analysis.ended_at_ms.map_or(end, |current| current.max(end)));
                }
            }
        }
        if let Some(capture) = &self.observed {
            for request in &capture.requests {
                for phase in &request.phases {
                    let slot = observed_phase_kind(phase.kind).position();
                    match phase.duration_ms() {
                        Some(duration) => phase_values[slot].push(duration),
                        None => phase_missing[slot] += 1,
                    }
                }
                match (request.start_ns, request.end_ns) {
                    (Some(start), Some(end)) if end >= start => phase_values
                        [PhaseKind::Total.position()]
                    .push((end - start) as f64 / 1_000_000.),
                    _ => phase_missing[PhaseKind::Total.position()] += 1,
                }
            }
        } else {
            self.stream_frames(cancel, |_, bytes| {
                let phases = phases_from_frame(bytes)
                    .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "invalid HAR entry"))?;
                for phase in phases {
                    let slot = phase.kind.position();
                    match phase.duration_ms {
                        Some(duration) if phase.availability == PhaseAvailability::Value => {
                            phase_values[slot].push(duration)
                        }
                        _ => phase_missing[slot] += 1,
                    }
                    if phase.availability == PhaseAvailability::Invalid {
                        invalid_timing_count += 1;
                    }
                }
                Ok(())
            })?;
        }
        durations.sort_by(f64::total_cmp);
        analysis.p90_ms = percentile(&durations, 0.90);
        analysis.invalid_timing_count = invalid_timing_count;
        analysis.phase_summaries = PhaseKind::ALL
            .into_iter()
            .filter_map(|kind| {
                let slot = kind.position();
                let mut values = std::mem::take(&mut phase_values[slot]);
                if values.is_empty() && phase_missing[slot] == 0 {
                    return None;
                }
                values.sort_by(f64::total_cmp);
                Some(PhaseDurationSummary {
                    kind,
                    sample_count: values.len() as u64,
                    missing_count: phase_missing[slot],
                    median_ms: percentile(&values, 0.50),
                    p95_ms: percentile(&values, 0.95),
                })
            })
            .collect();
        let analysis = Arc::new(analysis);
        *self.analysis.lock().map_err(|_| NetworkError::InvalidHar)? = Some(analysis.clone());
        Ok((*analysis).clone())
    }

    /// Grouped redacted domains for the filtered session, sorted by call count.
    pub fn domains(
        &self,
        query: &EntryQuery,
        limit: usize,
        cancel: &CancellationToken,
    ) -> Result<Vec<DomainSummary>, NetworkError> {
        if limit == 0 || limit > MAX_DOMAINS {
            return Err(NetworkError::InvalidQuery);
        }
        self.check_source()?;
        self.check_digest(cancel)?;
        let index = self.minimized_index(cancel)?;
        let search = query.search.trim().to_ascii_lowercase();
        let mut domains: BTreeMap<Option<String>, DomainSummary> = BTreeMap::new();
        for (position, entry) in index.iter().enumerate() {
            if position % 256 == 0 && cancel.is_cancelled() {
                return Err(NetworkError::Cancelled);
            }
            if !query.matches(entry, &search) {
                continue;
            }
            let domain = authority_host(&entry.redacted_url);
            let summary = domains.entry(domain.clone()).or_insert(DomainSummary {
                domain,
                call_count: 0,
                failure_count: 0,
                transferred_bytes: Some(0),
            });
            summary.call_count += 1;
            if entry.outcome == EntryOutcome::Failed {
                summary.failure_count += 1;
            }
            summary.transferred_bytes = match (
                summary.transferred_bytes,
                entry.request_bytes,
                entry.response_bytes,
            ) {
                (Some(total), Some(request), Some(response)) => {
                    Some(total.saturating_add(request).saturating_add(response))
                }
                _ => None,
            };
        }
        let mut rows: Vec<_> = domains.into_values().collect();
        rows.sort_by(|left, right| {
            right
                .call_count
                .cmp(&left.call_count)
                .then_with(|| left.domain.cmp(&right.domain))
        });
        rows.truncate(limit);
        Ok(rows)
    }

    /// Completion-bucketed byte activity plus call concurrency. Buckets contain
    /// completion bytes, not measured instantaneous throughput.
    pub fn activity(
        &self,
        bucket_count: usize,
        cancel: &CancellationToken,
    ) -> Result<Option<NetworkActivity>, NetworkError> {
        if bucket_count == 0 || bucket_count > 256 {
            return Err(NetworkError::InvalidQuery);
        }
        self.check_source()?;
        self.check_digest(cancel)?;
        let index = self.minimized_index(cancel)?;
        let observed_start = self
            .observed
            .as_ref()
            .map(|capture| capture.start_monotonic_ns);
        let har_origin = index.iter().filter_map(|entry| entry.started_at_ms).min();
        let mut timed: Vec<(u64, u64, Option<u64>)> = Vec::new();
        let mut omitted = 0_u64;
        for (position, entry) in index.iter().enumerate() {
            if position % 256 == 0 && cancel.is_cancelled() {
                return Err(NetworkError::Cancelled);
            }
            let interval = if let Some(capture) = &self.observed {
                let Some(request) = capture.request(entry.index) else {
                    omitted += 1;
                    continue;
                };
                match (request.start_ns, request.end_ns) {
                    (Some(start), Some(end)) if end >= start => Some((
                        start.saturating_sub(observed_start.unwrap_or(0)),
                        end.saturating_sub(observed_start.unwrap_or(0)),
                    )),
                    _ => None,
                }
            } else if let (Some(start), Some(duration)) = (entry.started_at_ms, entry.duration_ms) {
                let origin = har_origin.unwrap_or(start);
                let start_ns = (start.saturating_sub(origin) as u64).saturating_mul(1_000_000);
                let end_ns = start_ns.saturating_add((duration * 1_000_000.0) as u64);
                Some((start_ns, end_ns))
            } else {
                None
            };
            match interval {
                Some((start, end)) => timed.push((
                    start,
                    end,
                    match (entry.request_bytes, entry.response_bytes) {
                        (Some(request), Some(response)) => Some(request.saturating_add(response)),
                        _ => None,
                    },
                )),
                None => omitted += 1,
            }
        }
        if timed.is_empty() {
            return Ok(None);
        }
        let origin = timed.iter().map(|(start, _, _)| *start).min().unwrap_or(0);
        let end = timed.iter().map(|(_, end, _)| *end).max().unwrap_or(origin);
        let duration = end.saturating_sub(origin).max(1);
        let mut buckets: Vec<Option<u64>> = vec![Some(0); bucket_count];
        let mut incomplete = 0_u64;
        let mut events = Vec::with_capacity(timed.len() * 2);
        for (start, call_end, bytes) in &timed {
            let position = (((*call_end - origin) as f64 / duration as f64) * bucket_count as f64)
                .floor() as usize;
            let position = position.min(bucket_count - 1);
            match bytes {
                Some(bytes) => {
                    buckets[position] = Some(buckets[position].unwrap_or(0).saturating_add(*bytes))
                }
                None => {
                    buckets[position] = None;
                    incomplete += 1;
                }
            }
            events.push((*start, 1_i8));
            events.push((*call_end, -1_i8));
        }
        events.sort_unstable();
        let mut active = 0_usize;
        let mut peak = 0_usize;
        let mut weighted = 0_f64;
        let mut last = events.first().map(|(time, _)| *time).unwrap_or(0);
        for (time, delta) in events {
            weighted += active as f64 * (time.saturating_sub(last)) as f64;
            if delta < 0 {
                active = active.saturating_sub(1);
            } else {
                active += 1;
            }
            peak = peak.max(active);
            last = time;
        }
        Ok(Some(NetworkActivity {
            origin_ns: origin,
            duration_ns: duration,
            timed_calls: timed.len() as u64,
            omitted_calls: omitted,
            incomplete_byte_calls: incomplete,
            buckets,
            peak_concurrency: peak,
            average_concurrency: Some(weighted / duration as f64),
        }))
    }

    fn stream_frames(
        &self,
        cancel: &CancellationToken,
        mut visitor: impl FnMut(u64, &[u8]) -> io::Result<()>,
    ) -> Result<(), NetworkError> {
        if self.observed.is_some() {
            return Ok(());
        }
        let mut file = self.open_validated_source()?;
        self.check_open_digest(&mut file, cancel)?;
        let mut sink = FrameSink::new(self.summary.entry_count, cancel, &mut visitor);
        aps_har_engine::stream_entries(
            CheckedReader::new(&mut file, self.limits.max_file_bytes, cancel),
            &mut sink,
        )
        .map_err(|_| {
            if cancel.is_cancelled() {
                NetworkError::Cancelled
            } else {
                NetworkError::InvalidHar
            }
        })?;
        if !sink.complete {
            return Err(NetworkError::InvalidHar);
        }
        self.check_open_digest(&mut file, cancel)?;
        Ok(())
    }

    /// Computes median, p90 and p95 from the same validated redacted index used
    /// by filtered pages. Quantiles use linear interpolation at (n - 1) * p.
    pub fn duration_percentiles(
        &self,
        cancel: &CancellationToken,
    ) -> Result<DurationPercentiles, NetworkError> {
        if cancel.is_cancelled() {
            return Err(NetworkError::Cancelled);
        }
        self.check_source()?;
        self.check_digest(cancel)?;
        let index = self.minimized_index(cancel)?;
        let mut values = Vec::new();
        let mut missing_count = 0_u64;
        for (position, entry) in index.iter().enumerate() {
            if position % 256 == 0 && cancel.is_cancelled() {
                return Err(NetworkError::Cancelled);
            }
            match entry
                .duration_ms
                .filter(|value| value.is_finite() && *value >= 0.0)
            {
                Some(value) => values.push(value),
                None => missing_count += 1,
            }
        }
        values.sort_by(f64::total_cmp);
        let percentiles = DurationPercentiles {
            sample_count: values.len() as u64,
            missing_count,
            median_ms: percentile(&values, 0.50),
            p90_ms: percentile(&values, 0.90),
            p95_ms: percentile(&values, 0.95),
        };
        self.check_source()?;
        self.check_digest(cancel)?;
        if cancel.is_cancelled() {
            return Err(NetworkError::Cancelled);
        }
        Ok(percentiles)
    }

    fn minimized_index(
        &self,
        cancel: &CancellationToken,
    ) -> Result<Arc<Vec<NetworkEntry>>, NetworkError> {
        if let Some(index) = self
            .index
            .lock()
            .map_err(|_| NetworkError::InvalidHar)?
            .clone()
        {
            return Ok(index);
        }
        if self.summary.entry_count > self.limits.max_entries {
            return Err(NetworkError::InvalidHar);
        }
        let mut entries =
            Vec::with_capacity(self.summary.entry_count.min(u32::MAX as u64) as usize);
        self.stream_frames(cancel, |index, bytes| {
            entries
                .push(parse_entry(index, bytes).map_err(|_| {
                    io::Error::new(io::ErrorKind::InvalidData, "invalid HAR entry")
                })?);
            Ok(())
        })?;
        if entries.len() as u64 != self.summary.entry_count {
            return Err(NetworkError::InvalidHar);
        }
        let index = Arc::new(entries);
        *self.index.lock().map_err(|_| NetworkError::InvalidHar)? = Some(index.clone());
        Ok(index)
    }

    fn cached_page(&self, offset: usize, limit: usize) -> Option<EntryPage> {
        let mut cache = self.cache.lock().ok()?;
        let index = cache
            .iter()
            .position(|entry| entry.offset == offset && entry.limit == limit)?;
        let entry = cache.remove(index);
        let page = entry.page.clone();
        cache.push(entry);
        Some(page)
    }

    fn cache_page(&self, page: EntryPage, limit: usize) {
        let bytes = page
            .rows
            .iter()
            .map(|row| row.method.len() + row.redacted_url.len() + 48)
            .sum::<usize>();
        if bytes > MAX_CACHED_BYTES {
            return;
        }
        let Ok(mut cache) = self.cache.lock() else {
            return;
        };
        cache.retain(|entry| !(entry.offset == page.offset && entry.limit == limit));
        cache.push(CachedPage {
            offset: page.offset,
            limit,
            page,
            bytes,
        });
        while cache.len() > MAX_CACHED_PAGES
            || cache.iter().map(|entry| entry.bytes).sum::<usize>() > MAX_CACHED_BYTES
        {
            cache.remove(0);
        }
    }

    fn check_source(&self) -> Result<(), NetworkError> {
        let Some(source) = &self.source else {
            return Ok(());
        };
        let stamp = SourceStamp::from_metadata(
            std::fs::metadata(&self.path).map_err(|_| NetworkError::SourceChanged)?,
        )?;
        if &stamp != source {
            self.invalidate();
            return Err(NetworkError::SourceChanged);
        }
        Ok(())
    }

    fn invalidate(&self) {
        if let Ok(mut cache) = self.cache.lock() {
            cache.clear();
        }
        if let Ok(mut index) = self.index.lock() {
            *index = None;
        }
        if let Ok(mut analysis) = self.analysis.lock() {
            *analysis = None;
        }
    }

    fn open_validated_source(&self) -> Result<File, NetworkError> {
        let file = File::open(&self.path).map_err(|_| NetworkError::SourceChanged)?;
        let source = self.source.as_ref().ok_or(NetworkError::SourceChanged)?;
        validate_open_source(&self.path, &file, source)?;
        Ok(file)
    }

    fn check_open_digest(
        &self,
        file: &mut File,
        cancel: &CancellationToken,
    ) -> Result<(), NetworkError> {
        let result = hash_open_source(
            &self.path,
            file,
            self.source.as_ref().ok_or(NetworkError::SourceChanged)?,
            self.limits.max_file_bytes,
            cancel,
        );
        match result {
            Ok(digest) if digest == self.summary.source_digest => Ok(()),
            Err(NetworkError::Cancelled) => Err(NetworkError::Cancelled),
            _ => {
                self.invalidate();
                Err(NetworkError::SourceChanged)
            }
        }
    }

    fn check_digest(&self, cancel: &CancellationToken) -> Result<(), NetworkError> {
        if self.observed.is_some() {
            return if cancel.is_cancelled() {
                Err(NetworkError::Cancelled)
            } else {
                Ok(())
            };
        }
        let mut file = self.open_validated_source()?;
        self.check_open_digest(&mut file, cancel)
    }
}

fn percentile(values: &[f64], fraction: f64) -> Option<f64> {
    if values.is_empty() {
        return None;
    }
    let position = (values.len() - 1) as f64 * fraction;
    let lower = position.floor() as usize;
    let upper = position.ceil() as usize;
    if lower == upper {
        Some(values[lower])
    } else {
        let weight = position - lower as f64;
        Some(values[lower] + (values[upper] - values[lower]) * weight)
    }
}

fn observed_phase_kind(kind: ObservedPhaseKind) -> PhaseKind {
    match kind {
        ObservedPhaseKind::Proxy => PhaseKind::ProxySelect,
        ObservedPhaseKind::Dns => PhaseKind::Dns,
        ObservedPhaseKind::Connect => PhaseKind::Connect,
        ObservedPhaseKind::Tls => PhaseKind::Tls,
        ObservedPhaseKind::RequestHeaders => PhaseKind::RequestHeaders,
        ObservedPhaseKind::RequestBody => PhaseKind::RequestBody,
        ObservedPhaseKind::ResponseHeaders => PhaseKind::ResponseHeaders,
        ObservedPhaseKind::ResponseBody => PhaseKind::ResponseBody,
    }
}

fn observed_detail(request: &ObservedRequest, boundary: u64) -> EntryDetail {
    let mut phases: Vec<EntryPhase> = request
        .phases
        .iter()
        .map(|phase| {
            let confidence = match (phase.start_ns, phase.end_ns) {
                (Some(_), Some(_)) => PhaseConfidence::Exact,
                (Some(_), None) | (None, Some(_)) => PhaseConfidence::Partial,
                (None, None) => PhaseConfidence::Unknown,
            };
            EntryPhase {
                kind: observed_phase_kind(phase.kind),
                duration_ms: phase.duration_ms(),
                availability: match phase.duration_ms() {
                    Some(_) => PhaseAvailability::Value,
                    None => PhaseAvailability::Unavailable,
                },
                confidence,
                parent: None,
            }
        })
        .collect();
    let start = request.start_ns.map(|value| value.saturating_sub(boundary));
    let end = request.end_ns.map(|value| value.saturating_sub(boundary));
    phases.push(EntryPhase {
        kind: PhaseKind::Total,
        duration_ms: start
            .zip(end)
            .map(|(start, end)| end.saturating_sub(start) as f64 / 1_000_000.),
        availability: if start.is_some() && end.is_some() {
            PhaseAvailability::Value
        } else {
            PhaseAvailability::Unavailable
        },
        confidence: if start.is_some() && end.is_some() {
            PhaseConfidence::Exact
        } else {
            PhaseConfidence::Partial
        },
        parent: None,
    });
    EntryDetail {
        request_headers: Vec::new(),
        response_headers: Vec::new(),
        phases,
        failure_type: (request.outcome == ObservedOutcome::Failed)
            .then(|| "AGENT_CALL_FAILED".to_owned()),
        tls_version: request.tls_version.clone(),
        cipher_suite: request.cipher_suite.clone(),
    }
}

/// Metadata detects ordinary concurrent mutation/path replacement; it is not
/// a filesystem sandbox or a claim that hostile privileged writers are absent.
fn validate_open_source(
    path: &Path,
    file: &File,
    expected: &SourceStamp,
) -> Result<(), NetworkError> {
    let handle =
        SourceStamp::from_metadata(file.metadata().map_err(|_| NetworkError::SourceChanged)?)?;
    let path = SourceStamp::from_metadata(
        std::fs::metadata(path).map_err(|_| NetworkError::SourceChanged)?,
    )?;
    if &handle != expected || &path != expected {
        return Err(NetworkError::SourceChanged);
    }
    Ok(())
}

/// Cancellation-aware hashing and parsing use one descriptor, and every hash
/// restores its position to zero for the next parser. Both handle and path are
/// checked around the hash; no path-open hash can substitute a different file.
fn hash_open_source(
    path: &Path,
    file: &mut File,
    expected: &SourceStamp,
    max_bytes: u64,
    cancel: &CancellationToken,
) -> Result<SourceDigest, NetworkError> {
    if cancel.is_cancelled() {
        return Err(NetworkError::Cancelled);
    }
    validate_open_source(path, file, expected)?;
    file.seek(SeekFrom::Start(0))
        .map_err(|_| NetworkError::SourceChanged)?;
    let digest =
        SourceDigest::hash_reader(CheckedReader::new(&mut *file, max_bytes, cancel), max_bytes)
            .map_err(|_| {
                if cancel.is_cancelled() {
                    NetworkError::Cancelled
                } else {
                    NetworkError::SourceChanged
                }
            })?;
    validate_open_source(path, file, expected)?;
    if cancel.is_cancelled() {
        return Err(NetworkError::Cancelled);
    }
    file.seek(SeekFrom::Start(0))
        .map_err(|_| NetworkError::SourceChanged)?;
    Ok(digest)
}

struct CheckedReader<'a, R> {
    file: R,
    remaining: u64,
    cancel: &'a CancellationToken,
}
impl<'a, R: Read> CheckedReader<'a, R> {
    fn new(file: R, remaining: u64, cancel: &'a CancellationToken) -> Self {
        Self {
            file,
            remaining,
            cancel,
        }
    }
}
impl<R: Read> Read for CheckedReader<'_, R> {
    fn read(&mut self, bytes: &mut [u8]) -> io::Result<usize> {
        // The underlying parser retries Interrupted errors. Cancellation must
        // use a terminal error or it would spin forever after cancellation.
        if self.cancel.is_cancelled() {
            return Err(io::ErrorKind::ConnectionAborted.into());
        }
        if self.remaining == 0 {
            let mut probe = [0];
            return if self.file.read(&mut probe)? == 0 {
                Ok(0)
            } else {
                Err(io::ErrorKind::InvalidData.into())
            };
        }
        let limit = bytes.len().min(self.remaining.min(64 * 1024) as usize);
        let count = self.file.read(&mut bytes[..limit])?;
        self.remaining -= count as u64;
        Ok(count)
    }
}

enum FrameStep {
    Buffered,
    Header,
    Completed,
}

/// Consumes the engine's framed entry stream and invokes a visitor per entry.
/// Only one frame is retained at a time; partial frames are never published.
struct FrameSink<'a, F> {
    header: Vec<u8>,
    pending: Option<(usize, Option<u64>, Vec<u8>, usize)>,
    started: bool,
    complete: bool,
    seen: u64,
    expected: u64,
    cancel: &'a CancellationToken,
    visit: &'a mut F,
}
impl<'a, F: FnMut(u64, &[u8]) -> io::Result<()>> FrameSink<'a, F> {
    fn new(expected: u64, cancel: &'a CancellationToken, visit: &'a mut F) -> Self {
        Self {
            header: Vec::new(),
            pending: None,
            started: false,
            complete: false,
            seen: 0,
            expected,
            cancel,
            visit,
        }
    }
    fn restart_header(&mut self) -> io::Result<()> {
        let header = std::str::from_utf8(&self.header).map_err(|_| invalid())?;
        if !self.started {
            if header != "APS-HAR-ENTRIES\t1" {
                return Err(invalid());
            }
            self.started = true;
            return Ok(());
        }
        let parts: Vec<_> = header.split('\t').collect();
        if parts.first() == Some(&"end") {
            let count = parts
                .get(1)
                .ok_or_else(invalid)?
                .parse::<u64>()
                .map_err(|_| invalid())?;
            if parts.len() != 2 || count != self.expected || count != self.seen {
                return Err(invalid());
            }
            self.complete = true;
            return Ok(());
        }
        let (length, entry) = match parts.as_slice() {
            ["entry", index, length] => {
                let index = index.parse::<u64>().map_err(|_| invalid())?;
                if index != self.seen || index >= self.expected {
                    return Err(invalid());
                }
                self.seen += 1;
                (length.parse::<usize>().map_err(|_| invalid())?, Some(index))
            }
            ["version" | "creator", length] => {
                (length.parse::<usize>().map_err(|_| invalid())?, None)
            }
            _ => return Err(invalid()),
        };
        if length > MAX_FRAME_BYTES {
            return Err(invalid());
        }
        self.pending = Some((length, entry, Vec::new(), 0));
        Ok(())
    }
    fn feed(&mut self, byte: u8) -> io::Result<FrameStep> {
        if self.complete {
            return Err(invalid());
        }
        if let Some((length, entry, frame, consumed)) = self.pending.as_mut() {
            if *consumed < *length {
                if entry.is_some() {
                    frame.push(byte);
                }
                *consumed += 1;
                return Ok(FrameStep::Buffered);
            }
            if byte != b'\n' {
                return Err(invalid());
            }
            return Ok(FrameStep::Completed);
        }
        if byte == b'\n' {
            return Ok(FrameStep::Header);
        }
        if self.header.len() >= 128 {
            return Err(invalid());
        }
        self.header.push(byte);
        Ok(FrameStep::Buffered)
    }
    fn take_completed(&mut self) -> Option<(u64, Vec<u8>)> {
        self.pending
            .take()
            .and_then(|(_, entry, frame, _)| entry.map(|index| (index, frame)))
    }
}
impl<F: FnMut(u64, &[u8]) -> io::Result<()>> Write for FrameSink<'_, F> {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        for byte in bytes {
            match self.feed(*byte)? {
                FrameStep::Buffered => {}
                FrameStep::Header => {
                    self.restart_header()?;
                    self.header.clear();
                }
                FrameStep::Completed => {
                    if let Some((index, frame)) = self.take_completed() {
                        if self.cancel.is_cancelled() {
                            return Err(io::ErrorKind::ConnectionAborted.into());
                        }
                        (self.visit)(index, &frame)?;
                    }
                }
            }
        }
        Ok(bytes.len())
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}
fn invalid() -> io::Error {
    io::ErrorKind::InvalidData.into()
}

fn entry_value(bytes: &[u8]) -> io::Result<aps_har_engine::json::Value> {
    let mut parser = Parser::new(bytes);
    let value = parser.bounded_value(0).map_err(|_| invalid())?;
    parser.whitespace().map_err(|_| invalid())?;
    if parser.peek().map_err(|_| invalid())?.is_some() {
        return Err(invalid());
    }
    Ok(value)
}

fn parse_entry(index: u64, bytes: &[u8]) -> io::Result<NetworkEntry> {
    let value = entry_value(bytes)?;
    let map = value.object().map_err(|_| invalid())?;
    let request = map
        .get("request")
        .ok_or_else(invalid)?
        .object()
        .map_err(|_| invalid())?;
    let response = map
        .get("response")
        .ok_or_else(invalid)?
        .object()
        .map_err(|_| invalid())?;
    let method = match request
        .get("method")
        .ok_or_else(invalid)?
        .text()
        .map_err(|_| invalid())?
    {
        "GET" => "GET",
        "POST" => "POST",
        "PUT" => "PUT",
        "PATCH" => "PATCH",
        "DELETE" => "DELETE",
        "HEAD" => "HEAD",
        "OPTIONS" => "OPTIONS",
        "CONNECT" => "CONNECT",
        "TRACE" => "TRACE",
        _ => "OTHER",
    };
    let status = response
        .get("status")
        .and_then(|value| value.integer().ok())
        .filter(|status| *status == 0 || (100..=599).contains(status))
        .ok_or_else(invalid)? as u16;
    let redacted_url = request
        .get("url")
        .and_then(|value| value.text().ok())
        .map_or_else(|| INVALID_URL.to_owned(), redact_url);
    let duration_ms = map
        .get("time")
        .and_then(|value| value.number().ok())
        .filter(|value| value.is_finite() && *value >= 0.0);
    let protocol = response
        .get("httpVersion")
        .and_then(|value| value.text().ok())
        .and_then(clean_protocol);
    let started_at_ms = map
        .get("startedDateTime")
        .and_then(|value| value.text().ok())
        .and_then(parse_rfc3339_ms);
    let connection_use = connection_use(map);
    let outcome = if map
        .get("_error")
        .and_then(|value| value.text().ok())
        .is_some()
    {
        EntryOutcome::Failed
    } else if duration_ms.is_some() {
        EntryOutcome::Completed
    } else {
        EntryOutcome::Incomplete
    };
    Ok(NetworkEntry {
        index,
        method,
        status,
        duration_ms,
        request_bytes: request
            .get("bodySize")
            .and_then(|value| value.integer().ok())
            .and_then(|value| u64::try_from(value).ok()),
        response_bytes: response
            .get("bodySize")
            .and_then(|value| value.integer().ok())
            .and_then(|value| u64::try_from(value).ok()),
        redacted_url,
        protocol,
        outcome,
        connection_use,
        started_at_ms,
    })
}

fn clean_protocol(raw: &str) -> Option<String> {
    let raw = raw.trim();
    if raw.is_empty()
        || raw.chars().count() > MAX_PROTOCOL_CHARS
        || raw.chars().any(|character| character.is_control())
    {
        return None;
    }
    Some(raw.to_owned())
}

fn connection_use(map: &BTreeMap<String, aps_har_engine::json::Value>) -> ConnectionUse {
    let timings = map.get("timings").and_then(|value| value.object().ok());
    let observed = |field: &str| {
        timings
            .and_then(|timings| timings.get(field))
            .and_then(|value| value.number().ok())
            .is_some_and(|value| value >= 0.0)
    };
    if observed("dns") || observed("connect") || observed("ssl") {
        ConnectionUse::New
    } else if timings.is_some() {
        ConnectionUse::Reused
    } else {
        ConnectionUse::Unknown
    }
}

fn detail_from_frame(bytes: &[u8]) -> io::Result<EntryDetail> {
    let value = entry_value(bytes)?;
    let map = value.object().map_err(|_| invalid())?;
    let (request_headers, response_headers) = headers_from_frame(bytes)?;
    Ok(EntryDetail {
        request_headers,
        response_headers,
        phases: derive_phases(map),
        failure_type: map
            .get("_error")
            .and_then(|value| value.text().ok())
            .map(|_| "HAR_ERROR".to_owned()),
        tls_version: None,
        cipher_suite: None,
    })
}

fn phases_from_frame(bytes: &[u8]) -> io::Result<Vec<EntryPhase>> {
    let value = entry_value(bytes)?;
    let map = value.object().map_err(|_| invalid())?;
    Ok(derive_phases(map))
}

/// HAR timings are wall-clock producer values: every value is INFERRED, and a
/// -1 only means "not applicable" when the request was plaintext.
fn derive_phases(map: &BTreeMap<String, aps_har_engine::json::Value>) -> Vec<EntryPhase> {
    let timings = map.get("timings").and_then(|value| value.object().ok());
    let secure = map
        .get("request")
        .and_then(|value| value.object().ok())
        .and_then(|request| request.get("url"))
        .and_then(|value| value.text().ok())
        .is_none_or(|url| !url.trim_start().to_ascii_lowercase().starts_with("http://"));
    let mut phases = Vec::with_capacity(8);
    for (kind, field, parent) in [
        (PhaseKind::DispatcherQueue, "blocked", None),
        (PhaseKind::Dns, "dns", None),
        (PhaseKind::Connect, "connect", None),
        (PhaseKind::Tls, "ssl", Some(PhaseKind::Connect)),
        (PhaseKind::RequestBody, "send", None),
        (PhaseKind::ServerWait, "wait", None),
        (PhaseKind::ResponseBody, "receive", None),
    ] {
        let raw = timings
            .and_then(|timings| timings.get(field))
            .and_then(|value| value.number().ok())
            .filter(|value| value.is_finite());
        let (duration, availability) = match raw {
            None => (None, PhaseAvailability::Unavailable),
            Some(value) if value >= 0.0 => (Some(value), PhaseAvailability::Value),
            Some(-1.0) if kind == PhaseKind::Tls && !secure => {
                (None, PhaseAvailability::NotApplicable)
            }
            Some(-1.0) => (None, PhaseAvailability::Unavailable),
            Some(_) => (None, PhaseAvailability::Invalid),
        };
        phases.push(EntryPhase {
            kind,
            duration_ms: duration,
            availability,
            confidence: if availability == PhaseAvailability::Value {
                PhaseConfidence::Inferred
            } else {
                PhaseConfidence::Unknown
            },
            parent,
        });
    }
    let total = map
        .get("time")
        .and_then(|value| value.number().ok())
        .filter(|value| value.is_finite() && *value >= 0.0);
    phases.push(EntryPhase {
        kind: PhaseKind::Total,
        duration_ms: total,
        availability: if total.is_some() {
            PhaseAvailability::Value
        } else {
            PhaseAvailability::Unavailable
        },
        confidence: if total.is_some() {
            PhaseConfidence::Inferred
        } else {
            PhaseConfidence::Unknown
        },
        parent: None,
    });
    phases
}

/// Header arrays are opaque to the engine's bounded value parser, so the detail
/// path parses the frame with the shared strict JSON boundary instead.
type HeaderPairs = Vec<(String, String)>;

fn headers_from_frame(bytes: &[u8]) -> io::Result<(HeaderPairs, HeaderPairs)> {
    let limits = aps_json::Limits {
        max_bytes: bytes.len().min(MAX_FRAME_BYTES) as u64,
        max_depth: 12,
        max_nodes: 50_000,
        max_string_bytes: 256 * 1024,
        max_collection_items: 4096,
    };
    let value = aps_json::parse_slice(bytes, limits).map_err(|_| invalid())?;
    let headers = |path: &str| -> Vec<(String, String)> {
        value
            .get(path)
            .and_then(|part| part.get("headers"))
            .and_then(|headers| headers.as_array())
            .map(|headers| {
                headers
                    .iter()
                    .filter_map(|header| {
                        Some((
                            header.get("name")?.as_str()?.to_owned(),
                            header
                                .get("value")
                                .and_then(|value| value.as_str())
                                .unwrap_or_default()
                                .to_owned(),
                        ))
                    })
                    .collect()
            })
            .unwrap_or_default()
    };
    Ok((
        redact_headers(headers("request")),
        redact_headers(headers("response")),
    ))
}

fn authority_host(redacted_url: &str) -> Option<String> {
    if redacted_url == INVALID_URL {
        return None;
    }
    let rest = redacted_url.split_once("://")?.1;
    let authority = rest.split(['/', '?']).next().unwrap_or("");
    let authority = authority
        .rsplit_once('@')
        .map_or(authority, |(_, host)| host);
    let host = if let Some(rest) = authority.strip_prefix('[') {
        rest.split_once(']').map(|(host, _)| format!("[{host}]"))?
    } else {
        authority
            .rsplit_once(':')
            .map_or(authority, |(host, port)| {
                if port.bytes().all(|byte| byte.is_ascii_digit()) {
                    host
                } else {
                    authority
                }
            })
            .to_owned()
    };
    (!host.is_empty()).then_some(host.to_ascii_lowercase())
}

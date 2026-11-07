//! Read-only HAR import with bounded, minimized presentation contracts.
//!
//! Raw entry frames exist only transiently inside the parser/adapter. They are
//! never returned, logged or persisted. Whole-session queries lazily retain an
//! index containing only minimized entries, bounded by the configured entry cap.

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
const MAX_FRAME_BYTES: usize = 2 * 1024 * 1024;
pub const MINIMIZED_URL: &str = "redacted://minimized-url";
pub const MINIMIZATION_POLICY: &str = "aps-network-rust-minimized-v1";

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
            Self::InvalidQuery => "HAR query exceeds its search or method limits",
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

/// Deliberately excludes raw URLs, arbitrary methods, timestamps, headers,
/// cookies, query names/values, bodies, producer strings and error output.
#[derive(Clone, Debug, PartialEq)]
pub struct NetworkEntry {
    pub index: u64,
    pub method: &'static str,
    pub status: u16,
    pub duration_ms: Option<f64>,
    pub request_bytes: Option<u64>,
    pub response_bytes: Option<u64>,
    pub minimized_url: &'static str,
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

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum EntrySort {
    #[default]
    Entry,
    DurationDescending,
    ResponseBytesDescending,
}

/// Filters apply to the entire selected HAR before paging. Search inspects
/// minimized fields only; it never searches the raw URL, producer or traffic.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct EntryQuery {
    pub status: StatusFilter,
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
            || self
                .method
                .as_deref()
                .is_some_and(|method| method != entry.method)
        {
            return false;
        }
        search.is_empty()
            || entry.method.to_ascii_lowercase().contains(search)
            || entry.minimized_url.contains(search)
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
/// not establish event coverage or prove that all network activity was
/// observed.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct DurationPercentiles {
    pub sample_count: u64,
    pub missing_count: u64,
    pub median_ms: Option<f64>,
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

/// A selected file, not a file-system browser. Retains only minimized evidence.
/// The metadata stamp is mutation detection, not a cryptographic source digest.
pub struct HarSession {
    path: PathBuf,
    source: SourceStamp,
    limits: ImportLimits,
    summary: HarSummary,
    cache: Mutex<Vec<CachedPage>>,
    index: Mutex<Option<Arc<Vec<NetworkEntry>>>>,
}

const MAX_CACHED_PAGES: usize = 8;
const MAX_CACHED_BYTES: usize = 512 * 1024;

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
            source,
            limits,
            summary,
            cache: Mutex::new(Vec::new()),
            index: Mutex::new(None),
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

    /// Parses to EOF before returning a page: partial/invalid streams are discarded.
    pub fn entries(
        &self,
        offset: usize,
        limit: usize,
        cancel: &CancellationToken,
    ) -> Result<EntryPage, NetworkError> {
        if limit == 0 || limit > MAX_PAGE_SIZE || offset.checked_add(limit).is_none() {
            return Err(NetworkError::InvalidPage);
        }
        if cancel.is_cancelled() {
            return Err(NetworkError::Cancelled);
        }
        self.check_source()?;
        self.check_digest(cancel)?;
        if let Some(page) = self.cached_page(offset, limit) {
            return Ok(page);
        }
        let mut file = self.open_validated_source()?;
        self.check_open_digest(&mut file, cancel)?;
        let mut sink = EntrySink::new(offset, limit, self.summary.entry_count);
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
        if !sink.complete || sink.pending.is_some() || !sink.header.is_empty() {
            return Err(NetworkError::InvalidHar);
        }
        self.check_open_digest(&mut file, cancel)?;
        let page = EntryPage {
            offset,
            total: self.summary.entry_count,
            rows: sink.rows,
            minimization_policy: MINIMIZATION_POLICY,
            source_digest: self.summary.source_digest,
        };
        self.cache_page(page.clone(), limit);
        Ok(page)
    }

    /// Blocking whole-session query. Only a bounded page leaves the adapter;
    /// the lazy index contains at most `ImportLimits::max_entries` minimized
    /// entries, never raw entry frames. Equal values sort by original index and
    /// missing values sort after known values. Source identity is revalidated
    /// even when the minimized index is already cached.
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
        let mut matches = Vec::new();
        for (position, entry) in index.iter().enumerate() {
            if position % 256 == 0 && cancel.is_cancelled() {
                return Err(NetworkError::Cancelled);
            }
            if query.matches(entry, &search) {
                matches.push(entry);
            }
        }
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
        Ok(EntryPage {
            offset,
            total,
            rows,
            minimization_policy: MINIMIZATION_POLICY,
            source_digest: self.summary.source_digest,
        })
    }

    /// Computes median and p95 from the same validated minimized index used by
    /// filtered pages. Quantiles use linear interpolation at `(n - 1) * p`.
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
        let quantile = |p: f64| {
            if values.is_empty() {
                return None;
            }
            let position = (values.len() - 1) as f64 * p;
            let lower = position.floor() as usize;
            let upper = position.ceil() as usize;
            if lower == upper {
                Some(values[lower])
            } else {
                let fraction = position - lower as f64;
                Some(values[lower] + (values[upper] - values[lower]) * fraction)
            }
        };
        self.check_source()?;
        self.check_digest(cancel)?;
        if cancel.is_cancelled() {
            return Err(NetworkError::Cancelled);
        }
        Ok(DurationPercentiles {
            sample_count: values.len() as u64,
            missing_count,
            median_ms: quantile(0.5),
            p95_ms: quantile(0.95),
        })
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
        let mut file = self.open_validated_source()?;
        self.check_open_digest(&mut file, cancel)?;
        let count =
            usize::try_from(self.summary.entry_count).map_err(|_| NetworkError::InvalidHar)?;
        if self.summary.entry_count > self.limits.max_entries {
            return Err(NetworkError::InvalidHar);
        }
        let mut sink = EntrySink::new(0, count, self.summary.entry_count);
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
        if !sink.complete
            || sink.pending.is_some()
            || !sink.header.is_empty()
            || sink.rows.len() != count
        {
            return Err(NetworkError::InvalidHar);
        }
        self.check_open_digest(&mut file, cancel)?;
        if cancel.is_cancelled() {
            return Err(NetworkError::Cancelled);
        }
        let index = Arc::new(sink.rows);
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
            .map(|row| row.method.len() + row.minimized_url.len() + 32)
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
        let stamp = SourceStamp::from_metadata(
            std::fs::metadata(&self.path).map_err(|_| NetworkError::SourceChanged)?,
        )?;
        if stamp != self.source {
            if let Ok(mut cache) = self.cache.lock() {
                cache.clear();
            }
            if let Ok(mut index) = self.index.lock() {
                *index = None;
            }
            return Err(NetworkError::SourceChanged);
        }
        Ok(())
    }

    fn open_validated_source(&self) -> Result<File, NetworkError> {
        let file = File::open(&self.path).map_err(|_| NetworkError::SourceChanged)?;
        validate_open_source(&self.path, &file, &self.source)?;
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
            &self.source,
            self.limits.max_file_bytes,
            cancel,
        );
        match result {
            Ok(digest) if digest == self.summary.source_digest => Ok(()),
            Err(NetworkError::Cancelled) => Err(NetworkError::Cancelled),
            _ => {
                if let Ok(mut cache) = self.cache.lock() {
                    cache.clear();
                }
                if let Ok(mut index) = self.index.lock() {
                    *index = None;
                }
                Err(NetworkError::SourceChanged)
            }
        }
    }

    fn check_digest(&self, cancel: &CancellationToken) -> Result<(), NetworkError> {
        let mut file = self.open_validated_source()?;
        self.check_open_digest(&mut file, cancel)
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

struct PendingFrame {
    length: usize,
    entry: Option<u64>,
    bytes: Vec<u8>,
    consumed: usize,
}
struct EntrySink {
    header: Vec<u8>,
    pending: Option<PendingFrame>,
    started: bool,
    complete: bool,
    seen: u64,
    expected: u64,
    offset: usize,
    limit: usize,
    rows: Vec<NetworkEntry>,
}
impl EntrySink {
    fn new(offset: usize, limit: usize, expected: u64) -> Self {
        Self {
            header: Vec::new(),
            pending: None,
            started: false,
            complete: false,
            seen: 0,
            expected,
            offset,
            limit,
            rows: Vec::new(),
        }
    }
    fn header(&mut self) -> io::Result<()> {
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
        let wanted = entry.is_some_and(|index| {
            index >= self.offset as u64 && index < (self.offset + self.limit) as u64
        });
        self.pending = Some(PendingFrame {
            length,
            entry: entry.filter(|_| wanted),
            bytes: Vec::new(),
            consumed: 0,
        });
        Ok(())
    }
}
impl Write for EntrySink {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        for byte in bytes {
            if self.complete {
                return Err(invalid());
            }
            if let Some(frame) = self.pending.as_mut() {
                if frame.consumed < frame.length {
                    if frame.entry.is_some() {
                        frame.bytes.push(*byte);
                    }
                    frame.consumed += 1;
                } else {
                    if *byte != b'\n' {
                        return Err(invalid());
                    }
                    let frame = self.pending.take().ok_or_else(invalid)?;
                    if let Some(index) = frame.entry {
                        self.rows.push(minimized_entry(index, &frame.bytes)?);
                    }
                }
            } else if *byte == b'\n' {
                self.header()?;
                self.header.clear();
            } else {
                if self.header.len() >= 128 {
                    return Err(invalid());
                }
                self.header.push(*byte);
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
fn minimized_entry(index: u64, bytes: &[u8]) -> io::Result<NetworkEntry> {
    let mut parser = Parser::new(bytes);
    let value = parser.bounded_value(0).map_err(|_| invalid())?;
    parser.whitespace().map_err(|_| invalid())?;
    if parser.peek().map_err(|_| invalid())?.is_some() {
        return Err(invalid());
    }
    let value = value.object().map_err(|_| invalid())?;
    let request = value
        .get("request")
        .ok_or_else(invalid)?
        .object()
        .map_err(|_| invalid())?;
    let response = value
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
        .and_then(|v| v.integer().ok())
        .filter(|s| *s == 0 || (100..=599).contains(s))
        .ok_or_else(invalid)? as u16;
    Ok(NetworkEntry {
        index,
        method,
        status,
        duration_ms: value
            .get("time")
            .and_then(|v| v.number().ok())
            .filter(|v| v.is_finite() && *v >= 0.),
        request_bytes: request
            .get("bodySize")
            .and_then(|v| v.integer().ok())
            .and_then(|v| v.try_into().ok()),
        response_bytes: response
            .get("bodySize")
            .and_then(|v| v.integer().ok())
            .and_then(|v| v.try_into().ok()),
        minimized_url: MINIMIZED_URL,
    })
}

#[cfg(test)]
mod descriptor_identity_tests {
    use super::{
        CancellationToken, CheckedReader, HarSession, ImportLimits, NetworkError, SourceDigest,
        SourceStamp, hash_open_source, validate_open_source,
    };
    use std::{
        fs::{self, File, OpenOptions},
        io::{Cursor, Read},
        path::PathBuf,
        sync::atomic::{AtomicU64, Ordering},
    };
    static NEXT: AtomicU64 = AtomicU64::new(0);
    struct Fixture(PathBuf);
    impl Fixture {
        fn new() -> Self {
            let root = std::env::temp_dir().join(format!(
                "aps-har-descriptor-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            fs::create_dir(&root).unwrap();
            Self(root)
        }
        fn path(&self) -> PathBuf {
            self.0.join("selected.har")
        }
        fn write(&self, bytes: &[u8]) {
            fs::write(self.path(), bytes).unwrap();
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn hash_rewinds_the_same_open_descriptor_for_parsing() {
        let fixture = Fixture::new();
        fixture.write(b"source A");
        let mut file = File::open(fixture.path()).unwrap();
        let stamp = SourceStamp::from_metadata(file.metadata().unwrap()).unwrap();
        let digest = hash_open_source(
            &fixture.path(),
            &mut file,
            &stamp,
            100,
            &CancellationToken::new(),
        )
        .unwrap();
        assert_eq!(
            digest,
            SourceDigest::hash_reader(Cursor::new(b"source A"), 100).unwrap()
        );
        let mut parsed = String::new();
        file.read_to_string(&mut parsed).unwrap();
        assert_eq!(parsed, "source A");
        assert_eq!(
            hash_open_source(
                &fixture.path(),
                &mut file,
                &stamp,
                100,
                &CancellationToken::new()
            )
            .unwrap(),
            digest
        );
    }

    #[cfg(unix)]
    #[test]
    fn replacement_between_descriptor_open_and_hash_cannot_bind_a_summary_to_b_digest() {
        let fixture = Fixture::new();
        fixture.write(b"source A");
        let mut file = File::open(fixture.path()).unwrap();
        let stamp = SourceStamp::from_metadata(file.metadata().unwrap()).unwrap();
        let modified = file.metadata().unwrap().modified().unwrap();
        let replacement = fixture.0.join("replacement.har");
        fs::write(&replacement, b"source B").unwrap();
        OpenOptions::new()
            .write(true)
            .open(&replacement)
            .unwrap()
            .set_modified(modified)
            .unwrap();
        // Deterministic replacement at the exact old split-handle boundary;
        // equal length and mtime must not make the two files interchangeable.
        fs::rename(replacement, fixture.path()).unwrap();
        assert!(matches!(
            hash_open_source(
                &fixture.path(),
                &mut file,
                &stamp,
                100,
                &CancellationToken::new()
            ),
            Err(NetworkError::SourceChanged)
        ));
    }

    #[cfg(unix)]
    #[test]
    fn replacement_after_parsing_is_detected_even_with_equal_content_and_mtime() {
        let fixture = Fixture::new();
        fixture.write(b"source A");
        let mut file = File::open(fixture.path()).unwrap();
        let stamp = SourceStamp::from_metadata(file.metadata().unwrap()).unwrap();
        hash_open_source(
            &fixture.path(),
            &mut file,
            &stamp,
            100,
            &CancellationToken::new(),
        )
        .unwrap();
        let mut parsed = String::new();
        file.read_to_string(&mut parsed).unwrap();
        let replacement = fixture.0.join("replacement.har");
        fs::write(&replacement, parsed).unwrap();
        OpenOptions::new()
            .write(true)
            .open(&replacement)
            .unwrap()
            .set_modified(file.metadata().unwrap().modified().unwrap())
            .unwrap();
        fs::rename(replacement, fixture.path()).unwrap();
        assert!(matches!(
            validate_open_source(&fixture.path(), &file, &stamp),
            Err(NetworkError::SourceChanged)
        ));
    }

    #[test]
    fn cancellation_interrupts_hash_reader_between_bounded_chunks() {
        struct CancelAfterFirstRead<'a> {
            bytes: Cursor<Vec<u8>>,
            cancel: &'a CancellationToken,
        }
        impl Read for CancelAfterFirstRead<'_> {
            fn read(&mut self, target: &mut [u8]) -> std::io::Result<usize> {
                let count = self.bytes.read(target)?;
                self.cancel.cancel();
                Ok(count)
            }
        }
        let cancel = CancellationToken::new();
        let source = CancelAfterFirstRead {
            bytes: Cursor::new(vec![1; 256 * 1024]),
            cancel: &cancel,
        };
        assert!(
            SourceDigest::hash_reader(CheckedReader::new(source, 256 * 1024, &cancel), 256 * 1024)
                .is_err()
        );
        assert!(cancel.is_cancelled());
    }

    #[test]
    fn cancelled_cached_query_does_not_publish_or_poison_the_next_query() {
        let fixture = Fixture::new();
        fixture.write(br#"{"log":{"version":"1.2","creator":{"name":"producer","version":"1"},"entries":[{"time":1,"request":{"method":"GET","url":"https://secret.invalid","bodySize":0},"response":{"status":200,"bodySize":0},"timings":{}}]}}"#);
        let active = CancellationToken::new();
        let session = HarSession::open(&fixture.path(), ImportLimits::default(), &active).unwrap();
        let query = super::EntryQuery::default();
        let before = session.query_entries(0, 1, &query, &active).unwrap();
        let cancelled = CancellationToken::new();
        cancelled.cancel();
        assert!(matches!(
            session.query_entries(0, 1, &query, &cancelled),
            Err(NetworkError::Cancelled)
        ));
        let after = session
            .query_entries(0, 1, &query, &CancellationToken::new())
            .unwrap();
        assert_eq!(before.rows, after.rows);
        assert_eq!(before.source_digest, after.source_digest);
    }
}

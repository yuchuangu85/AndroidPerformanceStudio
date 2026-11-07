//! Read-only, process-isolated HPROF sessions. No Heap/Engine enters UI memory.
//!
//! All methods are blocking and must run on the UI's background executor. One
//! session owns one child. Cancellation, timeout and Drop kill and reap it.

use aps_hprof_engine::wire::{self, Value};
use aps_platform::CancellationToken;
use std::collections::BTreeMap;
use std::fmt;
use std::io::{self, Read, Write};
use std::path::Path;
use std::process::{Child, Command, Stdio};
use std::sync::mpsc::{self, Receiver, SyncSender};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

pub mod model;
pub mod presenter;
mod regex_lite;
pub mod worker;
pub use aps_hprof_engine::wire::Value as MemoryValue;
pub use model::{
    ActivityLeak, BitmapInstance, ClassStats, DominatorRow, DominatorsPage, FindingsPage,
    FindingsSnapshot, HeapSegment, HeapSummary, HeapValueKind, InstanceDetail, InstanceField,
    InstanceRow, InstancesPage, LeakSuspect, LifecycleLeakObject, MemorySnapshot, ObjectReference,
    SnapshotMetadata,
};
pub use presenter::{
    ArrangeBy, ClassListSummary, ClassScope, ClassifierColumn, ClassifierRow, DashboardSort,
    LeakFilter, MemoryFilter, SortDirection, class_list_summary, classifier_rows,
    displayed_classes, duplicate_bitmap_classes, is_system_class, leak_class_names,
    leak_object_ids, sort_histogram_classes,
};
pub const MAX_PAGE_SIZE: usize = 200;
/// Bounded UI page size for instance and dominator views.
pub const MAX_UI_PAGE_SIZE: usize = 500;
pub const MAX_RESPONSE_BYTES: usize = 2 * 1024 * 1024;
/// The combined snapshot payload is one bounded read; it may exceed the page
/// budget but never the hard 16 MiB presentation ceiling.
pub const MAX_SNAPSHOT_BYTES: usize = 16 * 1024 * 1024;
pub const MAX_INPUT_BYTES: u64 = 8 * 1024 * 1024 * 1024;
/// Bounds for an imported R8/ProGuard class mapping carried to the worker.
pub const MAX_MAPPING_ENTRIES: usize = 200_000;
pub const MAX_MAPPING_BYTES: usize = 8 * 1024 * 1024;
const MAX_MAPPING_NAME_BYTES: usize = 4096;
const QUERY_TIMEOUT: Duration = Duration::from_secs(120);
const SNAPSHOT_TIMEOUT: Duration = Duration::from_secs(300);
const OPEN_TIMEOUT: Duration = Duration::from_secs(600);
const MAX_QUERY_CACHE_ENTRIES: usize = 8;
const MAX_QUERY_CACHE_BYTES: usize = 2 * 1024 * 1024;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum MemoryError {
    InvalidInput,
    InvalidPage,
    WorkerUnavailable,
    WorkerFailed,
    InvalidResponse,
    ResponseLimit,
    Cancelled,
    Timeout,
    Closed,
    EngineRejected,
}
impl fmt::Display for MemoryError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::InvalidInput => "Selected HPROF input or digest is invalid",
            Self::InvalidPage => "Memory page must contain 1 to 200 rows",
            Self::WorkerUnavailable => "Memory worker is unavailable; build aps-memory-worker",
            Self::WorkerFailed => "Memory worker failed",
            Self::InvalidResponse => "Memory worker response is invalid",
            Self::ResponseLimit => "Memory response exceeds the presentation budget",
            Self::Cancelled => "Memory operation was cancelled and its worker stopped",
            Self::Timeout => "Memory operation timed out and its worker stopped",
            Self::Closed => "Memory session is closed",
            Self::EngineRejected => "Memory engine rejected the input or query",
        })
    }
}
impl std::error::Error for MemoryError {}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FindingKind {
    LeakSuspects,
    BitmapInstances,
    ActivityLeaks,
    LifecycleLeakObjects,
}

/// Whole-histogram class query evaluated inside the HPROF worker before
/// pagination. This filters only class names; it never copies the heap graph
/// or raw object values into the UI process.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct HistogramQuery {
    pub search: String,
    pub match_case: bool,
    pub sort: HistogramSort,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum HistogramSort {
    #[default]
    CountDescending,
    ShallowDescending,
    RetainedDescending,
    ClassNameAscending,
}

impl HistogramSort {
    fn key(self) -> &'static str {
        match self {
            Self::CountDescending => "count",
            Self::ShallowDescending => "shallow",
            Self::RetainedDescending => "retained",
            Self::ClassNameAscending => "name",
        }
    }
}

pub const MAX_HISTOGRAM_SEARCH_BYTES: usize = 256;
impl FindingKind {
    fn key(self) -> &'static str {
        match self {
            Self::LeakSuspects => "leakSuspects",
            Self::BitmapInstances => "bitmapInstances",
            Self::ActivityLeaks => "activityLeaks",
            Self::LifecycleLeakObjects => "lifecycleLeakObjects",
        }
    }
}

/// Non-cloneable owner of one worker process. Not a security sandbox: the child
/// inherits the current user's OS rights and is trusted application code.
pub struct MemorySession {
    child: Option<Child>,
    requests: Option<SyncSender<(Vec<u8>, usize)>>,
    responses: Receiver<Result<Value, MemoryError>>,
    io_thread: Option<JoinHandle<()>>,
    source_digest: String,
    request_id: i64,
    metadata: Value,
    query_cache: Vec<QueryCacheEntry>,
}

struct QueryCacheEntry {
    key: Vec<u8>,
    value: Value,
    bytes: usize,
}

impl MemorySession {
    /// Opens only an explicitly selected file. The worker verifies its SHA-256
    /// before parsing, disables cache writes and owns the entire graph.
    pub fn open(
        worker_executable: &Path,
        selected_hprof: &Path,
        source_digest: &str,
        cancel: &CancellationToken,
    ) -> Result<Self, MemoryError> {
        Self::open_with_mapping(
            worker_executable,
            selected_hprof,
            source_digest,
            &[],
            cancel,
        )
    }

    /// Opens the selected HPROF with an R8/ProGuard class mapping. Pairs are
    /// raw HPROF class name (obfuscated) to original class name; the worker
    /// applies them to the whole graph before any typed projection is built.
    pub fn open_with_mapping(
        worker_executable: &Path,
        selected_hprof: &Path,
        source_digest: &str,
        mapping: &[(String, String)],
        cancel: &CancellationToken,
    ) -> Result<Self, MemoryError> {
        if cancel.is_cancelled() {
            return Err(MemoryError::Cancelled);
        }
        if source_digest.len() != 64 || !source_digest.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err(MemoryError::InvalidInput);
        }
        let mapping = mapping_value(mapping)?;
        let selected = selected_hprof
            .canonicalize()
            .map_err(|_| MemoryError::InvalidInput)?;
        let meta = selected.metadata().map_err(|_| MemoryError::InvalidInput)?;
        if !meta.is_file() || meta.len() > MAX_INPUT_BYTES {
            return Err(MemoryError::InvalidInput);
        }
        let path = selected.to_str().ok_or(MemoryError::InvalidInput)?;
        let executable = worker_executable
            .canonicalize()
            .map_err(|_| MemoryError::WorkerUnavailable)?;
        let mut child = Command::new(executable)
            .env_clear()
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::null())
            .spawn()
            .map_err(|_| MemoryError::WorkerUnavailable)?;
        let Some(stdin) = child.stdin.take() else {
            let _ = child.kill();
            let _ = child.wait();
            return Err(MemoryError::WorkerFailed);
        };
        let Some(stdout) = child.stdout.take() else {
            let _ = child.kill();
            let _ = child.wait();
            return Err(MemoryError::WorkerFailed);
        };
        let (request_tx, request_rx) = mpsc::sync_channel::<(Vec<u8>, usize)>(1);
        let (response_tx, response_rx) = mpsc::sync_channel(1);
        let io_thread = match thread::Builder::new()
            .name("aps-memory-protocol".into())
            .spawn(move || {
                let mut stdin = stdin;
                let mut stdout = stdout;
                while let Ok((bytes, max_response)) = request_rx.recv() {
                    let result = wire::write_encoded(&mut stdin, &bytes)
                        .map_err(|_| MemoryError::WorkerFailed)
                        .and_then(|_| read_response(&mut stdout, max_response));
                    let failed = result.is_err();
                    if response_tx.send(result).is_err() || failed {
                        break;
                    }
                }
            }) {
            Ok(handle) => handle,
            Err(_) => {
                let _ = child.kill();
                let _ = child.wait();
                return Err(MemoryError::WorkerFailed);
            }
        };
        let mut session = Self {
            child: Some(child),
            requests: Some(request_tx),
            responses: response_rx,
            io_thread: Some(io_thread),
            source_digest: source_digest.to_ascii_lowercase(),
            request_id: 0,
            metadata: Value::Null,
            query_cache: Vec::new(),
        };
        session.metadata = session.request(
            "open",
            Value::map([
                ("path", path.into()),
                ("sourceDigest", session.source_digest.clone().into()),
                ("mapping", mapping),
                ("cacheDir", Value::Null),
                ("graphStorage", "memory".into()),
            ]),
            cancel,
            OPEN_TIMEOUT,
        )?;
        Ok(session)
    }

    pub fn metadata(&self) -> &Value {
        &self.metadata
    }

    /// Identity of the exact selected HPROF bytes verified when the worker opened.
    pub fn source_digest(&self) -> &str {
        &self.source_digest
    }

    pub fn histogram(
        &mut self,
        heap: Option<&str>,
        offset: usize,
        limit: usize,
        cancel: &CancellationToken,
    ) -> Result<Value, MemoryError> {
        self.histogram_query(heap, &HistogramQuery::default(), offset, limit, cancel)
    }

    /// Returns a bounded page, with `total` reflecting all matching classes.
    /// Summary metrics retain their original whole-heap meaning.
    pub fn histogram_query(
        &mut self,
        heap: Option<&str>,
        query: &HistogramQuery,
        offset: usize,
        limit: usize,
        cancel: &CancellationToken,
    ) -> Result<Value, MemoryError> {
        page(offset, limit)?;
        if query.search.len() > MAX_HISTOGRAM_SEARCH_BYTES {
            return Err(MemoryError::InvalidInput);
        }
        self.request(
            "histogram",
            Value::map([
                ("heapName", heap.into()),
                ("offset", (offset as i64).into()),
                ("limit", (limit as i64).into()),
                ("search", query.search.as_str().into()),
                ("matchCase", query.match_case.into()),
                ("sort", query.sort.key().into()),
            ]),
            cancel,
            QUERY_TIMEOUT,
        )
    }

    pub fn instances(
        &mut self,
        class_names: &[String],
        heap: Option<&str>,
        offset: usize,
        limit: usize,
        cancel: &CancellationToken,
    ) -> Result<Value, MemoryError> {
        page(offset, limit)?;
        if class_names.is_empty()
            || class_names.len() > 64
            || class_names.iter().any(|name| name.len() > 4096)
        {
            return Err(MemoryError::InvalidInput);
        }
        self.request(
            "instances",
            Value::map([
                (
                    "classNames",
                    Value::List(class_names.iter().cloned().map(Value::from).collect()),
                ),
                ("heapName", heap.into()),
                ("offset", (offset as i64).into()),
                ("limit", (limit as i64).into()),
                ("ordinaryOnly", false.into()),
                ("anchorObjectId", Value::Null),
            ]),
            cancel,
            QUERY_TIMEOUT,
        )
    }

    /// Array fields use the engine's page. Ordinary-object fields, reference
    /// chains and inbound references carry explicit truncation metadata.
    pub fn detail(
        &mut self,
        object_id: u64,
        offset: usize,
        limit: usize,
        cancel: &CancellationToken,
    ) -> Result<Value, MemoryError> {
        page(offset, limit)?;
        self.request(
            "detail",
            Value::map([
                ("objectId", (object_id as i64).into()),
                ("arrayStart", (offset as i64).into()),
                ("arrayLimit", (limit as i64).into()),
            ]),
            cancel,
            QUERY_TIMEOUT,
        )
    }

    pub fn dominators(
        &mut self,
        parent: Option<u64>,
        offset: usize,
        limit: usize,
        cancel: &CancellationToken,
    ) -> Result<Value, MemoryError> {
        page(offset, limit)?;
        self.request(
            "dominators",
            Value::map([
                ("parentObjectId", parent.map(|id| id as i64).into()),
                ("offset", (offset as i64).into()),
                ("limit", (limit as i64).into()),
            ]),
            cancel,
            QUERY_TIMEOUT,
        )
    }

    pub fn findings(
        &mut self,
        kind: FindingKind,
        offset: usize,
        limit: usize,
        cancel: &CancellationToken,
    ) -> Result<Value, MemoryError> {
        page(offset, limit)?;
        self.request(
            "findings",
            Value::map([
                ("kind", kind.key().into()),
                ("offset", (offset as i64).into()),
                ("limit", (limit as i64).into()),
            ]),
            cancel,
            QUERY_TIMEOUT,
        )
    }

    /// One bounded, consistent read: metadata + all-heap histogram + per-heap
    /// histograms + the first 200 rows (with totals) of every finding kind.
    /// The combined payload may exceed the 2 MiB page budget but never
    /// MAX_SNAPSHOT_BYTES; per-heap histograms are omitted before failing.
    pub fn snapshot(&mut self, cancel: &CancellationToken) -> Result<MemorySnapshot, MemoryError> {
        let metadata = SnapshotMetadata::from_value(&self.metadata)?;
        let heaps: Vec<Value> = metadata.heaps.iter().cloned().map(Value::from).collect();
        let value = self.request_bounded(
            "snapshot",
            Value::map([("heaps", Value::List(heaps))]),
            cancel,
            SNAPSHOT_TIMEOUT,
            MAX_SNAPSHOT_BYTES,
        )?;
        if value.get("exceeded").and_then(Value::as_bool) == Ok(true) {
            return Err(MemoryError::ResponseLimit);
        }
        MemorySnapshot::decode(&value, &metadata, &self.source_digest)
    }

    /// Instance rows for every leaf class of one selected classifier row.
    pub fn instances_page(
        &mut self,
        class_names: &[String],
        heap: Option<&str>,
        offset: usize,
        limit: usize,
        cancel: &CancellationToken,
    ) -> Result<InstancesPage, MemoryError> {
        page_with(offset, limit, MAX_UI_PAGE_SIZE)?;
        if class_names.is_empty()
            || class_names.len() > 64
            || class_names
                .iter()
                .any(|name| name.is_empty() || name.len() > 4096)
        {
            return Err(MemoryError::InvalidInput);
        }
        let value = self.request(
            "instances",
            Value::map([
                (
                    "classNames",
                    Value::List(class_names.iter().cloned().map(Value::from).collect()),
                ),
                ("heapName", heap.into()),
                ("offset", (offset as i64).into()),
                ("limit", (limit as i64).into()),
                ("ordinaryOnly", false.into()),
                ("anchorObjectId", Value::Null),
            ]),
            cancel,
            QUERY_TIMEOUT,
        )?;
        InstancesPage::from_value(&value, offset)
    }

    /// Detail of one object; array pages use arrayStart and a limit up to 200.
    pub fn detail_page(
        &mut self,
        object_id: u64,
        array_start: usize,
        limit: usize,
        cancel: &CancellationToken,
    ) -> Result<Option<InstanceDetail>, MemoryError> {
        page(array_start, limit)?;
        let value = self.detail(object_id, array_start, limit, cancel)?;
        if value == Value::Null {
            return Ok(None);
        }
        InstanceDetail::from_value(&value, object_id, array_start, limit).map(Some)
    }

    /// One bounded page of dominator children; an absent parent means roots.
    pub fn dominators_page(
        &mut self,
        parent: Option<u64>,
        offset: usize,
        limit: usize,
        cancel: &CancellationToken,
    ) -> Result<DominatorsPage, MemoryError> {
        page_with(offset, limit, MAX_UI_PAGE_SIZE)?;
        let value = self.request(
            "dominators",
            Value::map([
                ("parentObjectId", parent.map(|id| id as i64).into()),
                ("offset", (offset as i64).into()),
                ("limit", (limit as i64).into()),
            ]),
            cancel,
            QUERY_TIMEOUT,
        )?;
        DominatorsPage::from_value(&value, offset)
    }

    pub fn leak_suspects_page(
        &mut self,
        offset: usize,
        limit: usize,
        cancel: &CancellationToken,
    ) -> Result<FindingsPage<LeakSuspect>, MemoryError> {
        let value = self.findings(FindingKind::LeakSuspects, offset, limit, cancel)?;
        model::decode_findings_page(&value, LeakSuspect::from_value)
    }

    pub fn bitmaps_page(
        &mut self,
        offset: usize,
        limit: usize,
        cancel: &CancellationToken,
    ) -> Result<FindingsPage<BitmapInstance>, MemoryError> {
        let value = self.findings(FindingKind::BitmapInstances, offset, limit, cancel)?;
        model::decode_findings_page(&value, BitmapInstance::from_value)
    }

    pub fn activity_leaks_page(
        &mut self,
        offset: usize,
        limit: usize,
        cancel: &CancellationToken,
    ) -> Result<FindingsPage<ActivityLeak>, MemoryError> {
        let value = self.findings(FindingKind::ActivityLeaks, offset, limit, cancel)?;
        model::decode_findings_page(&value, ActivityLeak::from_value)
    }

    pub fn lifecycle_objects_page(
        &mut self,
        offset: usize,
        limit: usize,
        cancel: &CancellationToken,
    ) -> Result<FindingsPage<LifecycleLeakObject>, MemoryError> {
        let value = self.findings(FindingKind::LifecycleLeakObjects, offset, limit, cancel)?;
        model::decode_findings_page(&value, LifecycleLeakObject::from_value)
    }

    /// Cancels the whole session rather than leaving an unreachable live worker.
    pub fn close(&mut self) {
        self.stop();
    }

    fn request(
        &mut self,
        op: &str,
        args: Value,
        cancel: &CancellationToken,
        timeout: Duration,
    ) -> Result<Value, MemoryError> {
        self.request_bounded(op, args, cancel, timeout, MAX_RESPONSE_BYTES)
    }

    /// A single read whose response frame may use a larger presentation cap.
    /// Every other operation keeps the 2 MiB page-response guarantee.
    fn request_bounded(
        &mut self,
        op: &str,
        args: Value,
        cancel: &CancellationToken,
        timeout: Duration,
        max_response: usize,
    ) -> Result<Value, MemoryError> {
        if self.child.is_none() {
            return Err(MemoryError::Closed);
        }
        if cancel.is_cancelled() {
            self.stop();
            return Err(MemoryError::Cancelled);
        }
        let cache_key = if matches!(
            op,
            "histogram" | "instances" | "detail" | "dominators" | "findings"
        ) {
            Some(
                wire::encode(&Value::map([("op", op.into()), ("args", args.clone())]))
                    .map_err(|_| MemoryError::InvalidInput)?,
            )
        } else {
            None
        };
        if let Some(key) = &cache_key
            && let Some(index) = self.query_cache.iter().position(|entry| &entry.key == key)
        {
            let entry = self.query_cache.remove(index);
            let value = entry.value.clone();
            self.query_cache.push(entry);
            return Ok(value);
        }
        self.request_id = self
            .request_id
            .checked_add(1)
            .ok_or(MemoryError::InvalidInput)?;
        let id = self.request_id;
        let request = Value::map([
            ("version", wire::VERSION.into()),
            ("id", id.into()),
            ("op", op.into()),
            ("sourceDigest", self.source_digest.clone().into()),
            ("args", args),
        ]);
        let bytes = wire::encode(&request).map_err(|_| MemoryError::InvalidInput)?;
        if self
            .requests
            .as_ref()
            .ok_or(MemoryError::Closed)?
            .send((bytes, max_response))
            .is_err()
        {
            self.stop();
            return Err(MemoryError::WorkerFailed);
        }
        let deadline = Instant::now() + timeout;
        loop {
            if cancel.is_cancelled() {
                self.stop();
                return Err(MemoryError::Cancelled);
            }
            if Instant::now() >= deadline {
                self.stop();
                return Err(MemoryError::Timeout);
            }
            match self.responses.recv_timeout(Duration::from_millis(10)) {
                Ok(Ok(response)) => {
                    let valid = response.get("version").and_then(Value::as_int)
                        == Ok(wire::VERSION)
                        && response.get("id").and_then(Value::as_int) == Ok(id)
                        && response.get("sourceDigest").and_then(Value::as_str)
                            == Ok(self.source_digest.as_str());
                    if !valid {
                        self.stop();
                        return Err(MemoryError::InvalidResponse);
                    }
                    return if response.get("status").and_then(Value::as_str) == Ok("ok") {
                        let value = response
                            .get("payload")
                            .cloned()
                            .map_err(|_| MemoryError::InvalidResponse)?;
                        if let Some(key) = cache_key {
                            self.cache_response(key, value.clone());
                        }
                        Ok(value)
                    } else {
                        Err(MemoryError::EngineRejected)
                    };
                }
                Ok(Err(error)) => {
                    self.stop();
                    return Err(error);
                }
                Err(mpsc::RecvTimeoutError::Disconnected) => {
                    self.stop();
                    return Err(MemoryError::WorkerFailed);
                }
                Err(mpsc::RecvTimeoutError::Timeout) => {}
            }
        }
    }

    fn stop(&mut self) {
        self.query_cache.clear();
        self.requests.take();
        if let Some(mut child) = self.child.take() {
            let _ = child.kill();
            let _ = child.wait();
        }
        if let Some(handle) = self.io_thread.take() {
            let _ = handle.join();
        }
    }

    fn cache_response(&mut self, key: Vec<u8>, value: Value) {
        let Ok(bytes) = wire::encode(&value) else {
            return;
        };
        if bytes.len() > MAX_QUERY_CACHE_BYTES {
            return;
        }
        self.query_cache.retain(|entry| entry.key != key);
        self.query_cache.push(QueryCacheEntry {
            key,
            value,
            bytes: bytes.len(),
        });
        while self.query_cache.len() > MAX_QUERY_CACHE_ENTRIES
            || self
                .query_cache
                .iter()
                .map(|entry| entry.bytes)
                .sum::<usize>()
                > MAX_QUERY_CACHE_BYTES
        {
            self.query_cache.remove(0);
        }
    }
}
impl Drop for MemorySession {
    fn drop(&mut self) {
        self.stop();
    }
}

/// Encodes the raw-to-original class map inside the worker request. The map
/// is bounded before framing so one import cannot force an unbounded child
/// allocation or an oversized protocol frame.
pub(crate) fn mapping_value(mapping: &[(String, String)]) -> Result<Value, MemoryError> {
    if mapping.len() > MAX_MAPPING_ENTRIES {
        return Err(MemoryError::InvalidInput);
    }
    let mut encoded = BTreeMap::new();
    let mut total = 0usize;
    for (raw, original) in mapping {
        if raw.is_empty()
            || original.is_empty()
            || raw.len() > MAX_MAPPING_NAME_BYTES
            || original.len() > MAX_MAPPING_NAME_BYTES
        {
            return Err(MemoryError::InvalidInput);
        }
        total = total
            .checked_add(raw.len() + original.len())
            .ok_or(MemoryError::InvalidInput)?;
        if total > MAX_MAPPING_BYTES {
            return Err(MemoryError::InvalidInput);
        }
        encoded.insert(raw.clone(), Value::from(original.as_str()));
    }
    Ok(Value::Map(encoded))
}

pub(crate) fn page(offset: usize, limit: usize) -> Result<(), MemoryError> {
    page_with(offset, limit, MAX_PAGE_SIZE)
}

pub(crate) fn page_with(offset: usize, limit: usize, max: usize) -> Result<(), MemoryError> {
    if limit == 0
        || limit > max
        || offset.checked_add(limit).is_none()
        || offset > i64::MAX as usize
    {
        return Err(MemoryError::InvalidPage);
    }
    Ok(())
}

fn read_response(reader: &mut impl Read, max_response: usize) -> Result<Value, MemoryError> {
    let mut header = [0; 4];
    reader
        .read_exact(&mut header)
        .map_err(|_| MemoryError::WorkerFailed)?;
    let size = u32::from_be_bytes(header) as usize;
    if size == 0 || size > max_response {
        return Err(MemoryError::ResponseLimit);
    }
    let mut bytes = vec![0; size];
    reader
        .read_exact(&mut bytes)
        .map_err(|_| MemoryError::WorkerFailed)?;
    wire::decode(&bytes).map_err(|_| MemoryError::InvalidResponse)
}

/// The worker and parent share a framing schema; this is not a public arbitrary
/// query API. UI callers use the bounded methods on MemorySession.
pub(crate) fn write_response(writer: &mut impl Write, response: &Value) -> io::Result<()> {
    let bytes = wire::encode(response).map_err(|_| io::ErrorKind::InvalidData)?;
    if bytes.is_empty() || bytes.len() > MAX_RESPONSE_BYTES {
        return Err(io::ErrorKind::InvalidData.into());
    }
    wire::write_encoded(writer, &bytes).map_err(|_| io::ErrorKind::BrokenPipe.into())
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_hprof_engine::wire::Value;
    use std::collections::BTreeMap;

    fn metadata_value(id_size: i64) -> Value {
        Value::map([
            ("format", "JAVA PROFILE 1.0.3".into()),
            ("idSize", id_size.into()),
            ("timestampMillis", 1_700_000_000_000i64.into()),
            ("classCount", 2i64.into()),
            ("instanceCount", 7i64.into()),
            ("objectArrayCount", 1i64.into()),
            ("primitiveArrayCount", 1i64.into()),
            ("rootCount", 3i64.into()),
            (
                "warnings",
                Value::List(vec![Value::map([("message", "truncated record".into())])]),
            ),
            ("heaps", Value::List(vec!["App".into(), "Zygote".into()])),
            ("cacheStatus", "disabled".into()),
        ])
    }

    fn summary_value() -> Value {
        Value::map([
            ("objectCount", 7i64.into()),
            ("shallowSize", 700i64.into()),
            ("classCount", 2i64.into()),
            (
                "heapSegments",
                Value::List(vec![Value::map([
                    ("name", "App".into()),
                    ("sizeBytes", 500i64.into()),
                    ("objectCount", 5i64.into()),
                ])]),
            ),
        ])
    }

    fn class_value(name: &str, count: i64, shallow: i64, retained: Option<i64>) -> Value {
        Value::map([
            ("className", name.into()),
            ("instanceCount", count.into()),
            ("shallowSize", shallow.into()),
            (
                "retainedSize",
                retained.map(Value::Int).unwrap_or(Value::Null),
            ),
            ("obfuscatedClassName", Value::Null),
            ("hierarchyDepth", 2i64.into()),
            ("nativeSize", Value::Null),
            ("totalCount", count.into()),
            ("heapNames", Value::List(vec!["App".into()])),
            ("reachableShallowSize", 11i64.into()),
            ("reachableNativeSize", Value::Null),
        ])
    }

    fn page_value(total: i64, rows: Vec<Value>) -> Value {
        Value::map([("total", total.into()), ("rows", Value::List(rows))])
    }

    fn findings_value() -> Value {
        Value::map([
            (
                "leakSuspects",
                page_value(
                    5,
                    vec![Value::map([
                        ("className", "sample.LeakingActivity".into()),
                        ("reason", "static field retains a Context".into()),
                        ("instanceCount", 2i64.into()),
                        ("retainedSize", 4096i64.into()),
                        ("activityOrFragmentLeak", false.into()),
                    ])],
                ),
            ),
            (
                "bitmapInstances",
                page_value(
                    3,
                    vec![Value::map([
                        ("objectId", 0x10i64.into()),
                        ("className", "android.graphics.Bitmap".into()),
                        ("width", 100i64.into()),
                        ("height", 50i64.into()),
                        ("estimatedPixelBytes", 20_000i64.into()),
                        ("retainedSize", 30_000i64.into()),
                        ("bitmapId", 77i64.into()),
                        ("bitmapSourceId", 88i64.into()),
                        ("nativeSizeBytes", Value::Null),
                    ])],
                ),
            ),
            (
                "activityLeaks",
                page_value(
                    1,
                    vec![Value::map([
                        ("className", "sample.Screen".into()),
                        ("liveInstanceCount", 3i64.into()),
                        ("destroyedInstanceCount", 2i64.into()),
                        ("retainedSize", Value::Null),
                    ])],
                ),
            ),
            (
                "lifecycleLeakObjects",
                page_value(
                    2,
                    vec![Value::map([
                        ("objectId", 42i64.into()),
                        ("className", "sample.Screen".into()),
                        ("heapName", "App".into()),
                    ])],
                ),
            ),
        ])
    }

    fn snapshot_payload(per_heap: Option<Value>, omitted: bool) -> Value {
        let mut payload = BTreeMap::new();
        payload.insert("summary".into(), summary_value());
        payload.insert(
            "classes".into(),
            Value::List(vec![
                class_value("sample.Alpha", 4, 400, Some(900)),
                class_value("sample.Beta", 3, 300, None),
            ]),
        );
        if let Some(per_heap) = per_heap {
            payload.insert("perHeap".into(), per_heap);
        }
        payload.insert("perHeapOmitted".into(), omitted.into());
        payload.insert("findings".into(), findings_value());
        Value::Map(payload)
    }

    #[test]
    fn snapshot_payload_decodes_histograms_findings_and_metadata() {
        let metadata = SnapshotMetadata::from_value(&metadata_value(4)).unwrap();
        assert_eq!(metadata.format, "JAVA PROFILE 1.0.3");
        assert_eq!(metadata.id_size, 4);
        assert_eq!(metadata.heaps, vec!["App", "Zygote"]);
        assert_eq!(metadata.warnings, vec!["truncated record"]);
        assert_eq!(metadata.cache_status.as_deref(), Some("disabled"));

        let per_heap = Value::Map(BTreeMap::from([(
            "App".into(),
            Value::List(vec![class_value("sample.Alpha", 2, 200, Some(800))]),
        )]));
        let payload = snapshot_payload(Some(per_heap), false);
        let snapshot = MemorySnapshot::decode(&payload, &metadata, "digest").unwrap();

        assert_eq!(snapshot.source_digest, "digest");
        assert_eq!(snapshot.summary.object_count, 7);
        assert_eq!(snapshot.summary.heap_segments.len(), 1);
        assert_eq!(snapshot.summary.heap_segments[0].name, "App");
        assert_eq!(snapshot.classes.len(), 2);
        assert_eq!(snapshot.classes[0].retained_size, Some(900));
        assert_eq!(snapshot.classes[1].retained_size, None);
        assert_eq!(snapshot.per_heap_classes.len(), 1);
        assert_eq!(snapshot.per_heap_classes["App"][0].instance_count, 2);

        let findings = &snapshot.findings;
        assert_eq!(findings.leak_suspects.total, 5);
        assert_eq!(findings.leak_suspects.rows.len(), 1);
        assert!(!findings.leak_suspects.rows[0].activity_or_fragment_leak);
        assert_eq!(findings.bitmaps.total, 3);
        assert_eq!(
            findings.bitmaps.rows[0].bitmap_source_id.as_deref(),
            Some("88")
        );
        assert_eq!(findings.bitmaps.rows[0].width, Some(100));
        assert_eq!(findings.activities.rows[0].destroyed_instance_count, 2);
        assert_eq!(findings.activities.total, 1);
        assert_eq!(findings.lifecycle.total, 2);
        assert_eq!(findings.lifecycle.rows[0].object_id, 42);
        assert_eq!(findings.lifecycle.rows[0].heap_name.as_deref(), Some("App"));
    }

    #[test]
    fn per_heap_fallback_omits_heap_rows_without_failing() {
        let metadata = SnapshotMetadata::from_value(&metadata_value(8)).unwrap();
        let payload = snapshot_payload(None, true);
        let snapshot = MemorySnapshot::decode(&payload, &metadata, "digest").unwrap();
        assert!(snapshot.per_heap_classes.is_empty());
        assert_eq!(snapshot.classes.len(), 2);
        assert_eq!(snapshot.metadata.id_size, 8);
    }

    #[test]
    fn snapshot_metadata_rejects_an_unusable_id_size() {
        assert_eq!(
            SnapshotMetadata::from_value(&metadata_value(5)),
            Err(MemoryError::InvalidResponse)
        );
        assert_eq!(MAX_UI_PAGE_SIZE, 500);
        const { assert!(MAX_SNAPSHOT_BYTES <= 16 * 1024 * 1024) };
        const { assert!(MAX_SNAPSHOT_BYTES > MAX_RESPONSE_BYTES) };
    }
}

#[cfg(test)]
mod mapping_tests {
    use super::*;

    #[test]
    fn mapping_encodes_raw_to_original_pairs() {
        let value = mapping_value(&[
            ("a.b.C".to_owned(), "com.example.C".to_owned()),
            ("a.b.D$Inner".to_owned(), "com.example.D$Inner".to_owned()),
        ])
        .unwrap();
        let map = value.as_map().unwrap();
        assert_eq!(map.len(), 2);
        assert_eq!(map["a.b.C"].as_str().unwrap(), "com.example.C");
        assert_eq!(map["a.b.D$Inner"].as_str().unwrap(), "com.example.D$Inner");
        assert_eq!(mapping_value(&[]).unwrap().as_map().unwrap().len(), 0);
    }

    #[test]
    fn mapping_rejects_unusable_entries_before_spawning_a_worker() {
        assert_eq!(
            mapping_value(&[(String::new(), "x".into())]),
            Err(MemoryError::InvalidInput)
        );
        assert_eq!(
            mapping_value(&[("x".into(), String::new())]),
            Err(MemoryError::InvalidInput)
        );
        assert_eq!(
            mapping_value(&[("x".repeat(4097), "y".into())]),
            Err(MemoryError::InvalidInput)
        );
        let too_many: Vec<(String, String)> = (0..=MAX_MAPPING_ENTRIES)
            .map(|index| (format!("a{index}"), format!("b{index}")))
            .collect();
        assert_eq!(mapping_value(&too_many), Err(MemoryError::InvalidInput));
        let too_large: Vec<(String, String)> = (0..1100)
            .map(|index| (format!("{}{index}", "x".repeat(4096)), "y".repeat(4096)))
            .collect();
        assert_eq!(mapping_value(&too_large), Err(MemoryError::InvalidInput));
    }
}

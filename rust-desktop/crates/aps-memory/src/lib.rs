//! Read-only, process-isolated HPROF sessions. No Heap/Engine enters UI memory.
//!
//! All methods are blocking and must run on the UI's background executor. One
//! session owns one child. Cancellation, timeout and Drop kill and reap it.

use aps_hprof_engine::wire::{self, Value};
use aps_platform::CancellationToken;
use std::fmt;
use std::io::{self, Read, Write};
use std::path::Path;
use std::process::{Child, Command, Stdio};
use std::sync::mpsc::{self, Receiver, SyncSender};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

pub mod worker;
pub use aps_hprof_engine::wire::Value as MemoryValue;
pub const MAX_PAGE_SIZE: usize = 200;
pub const MAX_RESPONSE_BYTES: usize = 2 * 1024 * 1024;
pub const MAX_INPUT_BYTES: u64 = 8 * 1024 * 1024 * 1024;
const QUERY_TIMEOUT: Duration = Duration::from_secs(120);
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
    requests: Option<SyncSender<Vec<u8>>>,
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
        if cancel.is_cancelled() {
            return Err(MemoryError::Cancelled);
        }
        if source_digest.len() != 64 || !source_digest.bytes().all(|b| b.is_ascii_hexdigit()) {
            return Err(MemoryError::InvalidInput);
        }
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
        let (request_tx, request_rx) = mpsc::sync_channel::<Vec<u8>>(1);
        let (response_tx, response_rx) = mpsc::sync_channel(1);
        let io_thread = match thread::Builder::new()
            .name("aps-memory-protocol".into())
            .spawn(move || {
                let mut stdin = stdin;
                let mut stdout = stdout;
                while let Ok(bytes) = request_rx.recv() {
                    let result = wire::write_encoded(&mut stdin, &bytes)
                        .map_err(|_| MemoryError::WorkerFailed)
                        .and_then(|_| read_response(&mut stdout));
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
                ("mapping", Value::map([])),
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
            .send(bytes)
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

pub(crate) fn page(offset: usize, limit: usize) -> Result<(), MemoryError> {
    if limit == 0
        || limit > MAX_PAGE_SIZE
        || offset.checked_add(limit).is_none()
        || offset > i64::MAX as usize
    {
        return Err(MemoryError::InvalidPage);
    }
    Ok(())
}

fn read_response(reader: &mut impl Read) -> Result<Value, MemoryError> {
    let mut header = [0; 4];
    reader
        .read_exact(&mut header)
        .map_err(|_| MemoryError::WorkerFailed)?;
    let size = u32::from_be_bytes(header) as usize;
    if size == 0 || size > MAX_RESPONSE_BYTES {
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

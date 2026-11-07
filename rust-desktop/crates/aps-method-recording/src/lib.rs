//! Bounded offline ART method-event import and duration analysis.
//!
//! Method events are not CPU samples. Counts below count ENTER records, self time
//! comes from observed constant-stack intervals, and inclusive time is summed
//! over invocations (including recursive invocations). No unclosed frame is
//! extended past its own thread's final observed event.
mod parser;
mod replay;

use std::collections::BTreeMap;
use std::fmt;
use std::fs::File;
use std::io::Read;
use std::path::Path;

pub type MethodId = u64;
pub type ThreadId = u32;
pub type NodeId = usize;
pub const MAX_PAGE_SIZE: usize = 512;
pub const HARD_MAX_INPUT_BYTES: u64 = 512 * 1024 * 1024;
pub const HARD_MAX_EVENTS: usize = 4_000_000;
pub const HARD_MAX_STACK_DEPTH: usize = 2048;

#[derive(Debug, Clone, Copy)]
pub struct ImportLimits {
    pub max_bytes: u64,
    pub max_events: usize,
    pub max_methods: usize,
    pub max_threads: usize,
    pub max_table_bytes: usize,
    pub max_string_bytes: usize,
    pub max_stack_depth: usize,
    pub max_call_tree_nodes: usize,
    pub max_diagnostics: usize,
}

impl Default for ImportLimits {
    fn default() -> Self {
        Self {
            max_bytes: 64 * 1024 * 1024,
            max_events: 1_000_000,
            max_methods: 100_000,
            max_threads: 16_384,
            max_table_bytes: 16 * 1024 * 1024,
            max_string_bytes: 16_384,
            max_stack_depth: 512,
            max_call_tree_nodes: 1_000_000,
            max_diagnostics: 1024,
        }
    }
}

impl ImportLimits {
    fn validate(self) -> Result<Self, ImportError> {
        if self.max_bytes == 0
            || self.max_bytes > HARD_MAX_INPUT_BYTES
            || self.max_events == 0
            || self.max_events > HARD_MAX_EVENTS
            || self.max_methods == 0
            || self.max_methods > 1_000_000
            || self.max_threads == 0
            || self.max_threads > 65_536
            || self.max_table_bytes == 0
            || self.max_table_bytes > 64 * 1024 * 1024
            || self.max_string_bytes == 0
            || self.max_string_bytes > 65_535
            || self.max_stack_depth == 0
            || self.max_stack_depth > HARD_MAX_STACK_DEPTH
            || self.max_call_tree_nodes == 0
            || self.max_call_tree_nodes > HARD_MAX_EVENTS
            || self.max_diagnostics > 16_384
        {
            return Err(ImportError::new(
                ErrorKind::Limit,
                None,
                "Import limits exceed supported hard bounds",
            ));
        }
        Ok(self)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ErrorKind {
    Io,
    Format,
    UnsupportedVersion,
    Limit,
    ArithmeticOverflow,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ImportError {
    pub kind: ErrorKind,
    pub offset: Option<usize>,
    pub message: String,
}

impl ImportError {
    pub(crate) fn new(kind: ErrorKind, offset: Option<usize>, message: impl Into<String>) -> Self {
        Self {
            kind,
            offset,
            message: message.into(),
        }
    }
}
impl fmt::Display for ImportError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        if let Some(offset) = self.offset {
            write!(f, "{} at byte {offset}", self.message)
        } else {
            f.write_str(&self.message)
        }
    }
}
impl std::error::Error for ImportError {}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ClockSource {
    /// Classic v2: the primary timestamp is thread CPU time, not wall time.
    ThreadCpu,
    /// Streaming v4 does not identify which single clock its producer selected.
    UnspecifiedSingle,
    /// Fixture-supported v3/v5 primary wall clock with separate thread CPU clock.
    WallAndThreadCpu,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TraceHeader {
    pub version: u16,
    /// Producer header value. Not added to event-relative timestamps or claimed
    /// to share an epoch with another artifact.
    pub start_time_nanos: u64,
    pub clock_source: ClockSource,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Method {
    pub id: MethodId,
    pub class_name: String,
    pub method_name: String,
    pub signature: String,
    pub source_file: String,
    pub declared: bool,
}
impl Method {
    pub fn display_name(&self) -> String {
        if !self.declared {
            return format!("0x{:x}", self.id);
        }
        let class = self.class_name.trim_end_matches(';').replace('/', ".");
        match (class.is_empty(), self.method_name.is_empty()) {
            (false, false) => format!("{class}.{}", self.method_name),
            (false, true) => class,
            (true, false) => self.method_name.clone(),
            (true, true) => format!("0x{:x}", self.id),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Thread {
    pub id: ThreadId,
    pub name: String,
    pub declared: bool,
    pub event_count: usize,
    pub first_time_nanos: Option<u64>,
    pub last_time_nanos: Option<u64>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Action {
    Enter,
    Exit,
    Unroll,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MethodEvent {
    pub thread_id: ThreadId,
    pub method_id: MethodId,
    pub action: Action,
    pub time_nanos: u64,
    pub cpu_nanos: Option<u64>,
    pub source_offset: usize,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum DiagnosticKind {
    UnknownPacket,
    UnusedAction,
    TrailingBytes,
    MalformedTable,
    DuplicateThread,
    DuplicateMethod,
    UncataloguedThread,
    UncataloguedMethod,
    CaptureOverflow,
    ClockRegression,
    CpuClockRegression,
    UnmatchedExit,
    MismatchedExit,
    UnclosedEnter,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Diagnostic {
    pub kind: DiagnosticKind,
    pub thread_id: Option<ThreadId>,
    pub event_index: Option<usize>,
    pub offset: Option<usize>,
    pub message: String,
}
#[derive(Debug, Clone, Default)]
pub struct Diagnostics {
    /// Bounded representative messages; counts retain every occurrence.
    pub entries: Vec<Diagnostic>,
    pub counts: BTreeMap<DiagnosticKind, u64>,
    pub omitted_entries: u64,
}
impl Diagnostics {
    pub fn has(&self, kind: DiagnosticKind) -> bool {
        self.counts.contains_key(&kind)
    }
    pub fn is_empty(&self) -> bool {
        self.counts.is_empty()
    }
    pub(crate) fn record(&mut self, diagnostic: Diagnostic, max: usize) {
        *self.counts.entry(diagnostic.kind).or_default() += 1;
        if self.entries.len() < max {
            self.entries.push(diagnostic);
        } else {
            self.omitted_entries += 1;
        }
    }
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct DurationTotals {
    pub primary_nanos: u64,
    /// Present only when the input supplied a second clock.
    pub cpu_nanos: Option<u64>,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct MethodStatistics {
    pub method_id: MethodId,
    /// Actual ENTER records, not sample count or the number of timeline slices.
    pub call_count: u64,
    pub completed_calls: u64,
    pub unrolled_calls: u64,
    pub incomplete_calls: u64,
    pub self_time: DurationTotals,
    /// Sum over invocation durations; recursion can make this exceed elapsed
    /// time. It is not de-duplicated method occupancy.
    pub inclusive_time: DurationTotals,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CallTreeRow {
    pub id: NodeId,
    pub parent_id: Option<NodeId>,
    pub thread_id: ThreadId,
    /// None denotes an artificial thread root, never an imported method.
    pub method_id: Option<MethodId>,
    pub depth: usize,
    pub child_count: usize,
    pub statistics: MethodStatistics,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TimelineInterval {
    pub thread_id: ThreadId,
    pub start_nanos: u64,
    pub end_nanos: u64,
    pub cpu_start_nanos: Option<u64>,
    pub cpu_end_nanos: Option<u64>,
    pub leaf_node_id: NodeId,
    pub depth: usize,
}
impl TimelineInterval {
    pub fn duration_nanos(&self) -> u64 {
        self.end_nanos - self.start_nanos
    }
}

#[derive(Debug, Clone, Copy)]
pub struct TimelineQuery {
    pub thread_id: Option<ThreadId>,
    /// Half-open primary-clock window. This filters intersecting intervals;
    /// returned intervals retain their original boundaries (not reweighted).
    pub start_nanos: Option<u64>,
    pub end_nanos: Option<u64>,
    pub offset: usize,
    pub limit: usize,
}

impl Default for TimelineQuery {
    fn default() -> Self {
        Self {
            thread_id: None,
            start_nanos: None,
            end_nanos: None,
            offset: 0,
            limit: 100,
        }
    }
}

#[derive(Debug, Clone)]
pub struct Page<T> {
    pub total: usize,
    pub offset: usize,
    pub rows: Vec<T>,
    pub has_more: bool,
}

#[derive(Debug, Clone, Default)]
pub struct TraceSummary {
    pub event_count: usize,
    pub method_count: usize,
    pub thread_count: usize,
    pub interval_count: usize,
    pub call_tree_node_count: usize,
    pub minimum_event_time_nanos: Option<u64>,
    pub maximum_event_time_nanos: Option<u64>,
    /// Sum of observed active durations across threads, not process wall time.
    pub observed_active_time: DurationTotals,
    /// Structural/replay warnings are present; even false does not prove that
    /// an on-device recording captured every call or did not perturb execution.
    pub has_integrity_diagnostics: bool,
}

pub(crate) struct ParsedTrace {
    header: TraceHeader,
    methods: BTreeMap<MethodId, Method>,
    threads: BTreeMap<ThreadId, Thread>,
    events: Vec<MethodEvent>,
    diagnostics: Diagnostics,
    raw_record_count: usize,
}
#[derive(Debug)]
pub(crate) struct AnalysisNode {
    row: CallTreeRow,
    children: Vec<NodeId>,
}

#[derive(Debug)]
pub struct MethodRecordingSession {
    header: TraceHeader,
    methods: BTreeMap<MethodId, Method>,
    threads: BTreeMap<ThreadId, Thread>,
    events: Vec<MethodEvent>,
    diagnostics: Diagnostics,
    summary: TraceSummary,
    nodes: Vec<AnalysisNode>,
    preorder: BTreeMap<ThreadId, Vec<NodeId>>,
    intervals: Vec<TimelineInterval>,
    top_methods: Vec<MethodStatistics>,
    thread_top_methods: BTreeMap<ThreadId, Vec<MethodStatistics>>,
}

impl MethodRecordingSession {
    pub fn import_bytes(bytes: &[u8], limits: ImportLimits) -> Result<Self, ImportError> {
        let limits = limits.validate()?;
        if bytes.len() as u64 > limits.max_bytes {
            return Err(ImportError::new(
                ErrorKind::Limit,
                None,
                "Method trace exceeds byte limit",
            ));
        }
        replay::analyze(parser::parse(bytes, limits)?, limits)
    }
    pub fn parse(bytes: &[u8]) -> Result<Self, ImportError> {
        Self::import_bytes(bytes, ImportLimits::default())
    }
    pub fn import_file(path: &Path, limits: ImportLimits) -> Result<Self, ImportError> {
        let limits = limits.validate()?;
        let mut file = File::open(path).map_err(|e| {
            ImportError::new(
                ErrorKind::Io,
                None,
                format!("Unable to open method trace: {e}"),
            )
        })?;
        if !file
            .metadata()
            .map_err(|e| ImportError::new(ErrorKind::Io, None, e.to_string()))?
            .is_file()
        {
            return Err(ImportError::new(
                ErrorKind::Io,
                None,
                "Method trace must be a regular file",
            ));
        }
        let mut bytes = Vec::new();
        let mut buffer = [0u8; 64 * 1024];
        loop {
            let remaining = limits.max_bytes.saturating_sub(bytes.len() as u64);
            let probe_len = usize::try_from((remaining + 1).min(buffer.len() as u64)).unwrap();
            let size = file.read(&mut buffer[..probe_len]).map_err(|e| {
                ImportError::new(
                    ErrorKind::Io,
                    None,
                    format!("Unable to read method trace: {e}"),
                )
            })?;
            if size == 0 {
                break;
            }
            if size as u64 > remaining {
                return Err(ImportError::new(
                    ErrorKind::Limit,
                    None,
                    "Method trace exceeds byte limit",
                ));
            }
            bytes.extend_from_slice(&buffer[..size]);
        }
        Self::import_bytes(&bytes, limits)
    }
    pub fn open(path: &Path) -> Result<Self, ImportError> {
        Self::import_file(path, ImportLimits::default())
    }
    pub fn header(&self) -> &TraceHeader {
        &self.header
    }
    pub fn summary(&self) -> &TraceSummary {
        &self.summary
    }
    pub fn diagnostics(&self) -> &Diagnostics {
        &self.diagnostics
    }
    pub fn method(&self, id: MethodId) -> Option<&Method> {
        self.methods.get(&id)
    }
    pub fn thread(&self, id: ThreadId) -> Option<&Thread> {
        self.threads.get(&id)
    }
    pub fn threads_page(&self, offset: usize, limit: usize) -> Result<Page<Thread>, ImportError> {
        page(
            self.threads.values().cloned(),
            self.threads.len(),
            offset,
            limit,
        )
    }
    pub fn methods_page(&self, offset: usize, limit: usize) -> Result<Page<Method>, ImportError> {
        page(
            self.methods.values().cloned(),
            self.methods.len(),
            offset,
            limit,
        )
    }
    pub fn events_page(
        &self,
        thread: Option<ThreadId>,
        offset: usize,
        limit: usize,
    ) -> Result<Page<MethodEvent>, ImportError> {
        check_thread(self, thread)?;
        let total = thread.map_or(self.events.len(), |id| self.threads[&id].event_count);
        page(
            self.events
                .iter()
                .filter(move |event| thread.is_none_or(|id| event.thread_id == id))
                .cloned(),
            total,
            offset,
            limit,
        )
    }
    /// Already aggregated and sorted once at import: self time descending, then
    /// inclusive time descending, then stable method ID.
    pub fn top_methods_page(
        &self,
        thread: Option<ThreadId>,
        offset: usize,
        limit: usize,
    ) -> Result<Page<MethodStatistics>, ImportError> {
        check_thread(self, thread)?;
        let values = thread
            .and_then(|id| self.thread_top_methods.get(&id))
            .map(Vec::as_slice)
            .unwrap_or(if thread.is_none() {
                &self.top_methods
            } else {
                &[]
            });
        page(values.iter().cloned(), values.len(), offset, limit)
    }
    pub fn call_tree_page(
        &self,
        thread: ThreadId,
        offset: usize,
        limit: usize,
    ) -> Result<Page<CallTreeRow>, ImportError> {
        check_thread(self, Some(thread))?;
        let ids = self.preorder.get(&thread).map(Vec::as_slice).unwrap_or(&[]);
        page(
            ids.iter().map(|id| self.nodes[*id].row.clone()),
            ids.len(),
            offset,
            limit,
        )
    }
    pub fn timeline_page(
        &self,
        query: TimelineQuery,
    ) -> Result<Page<TimelineInterval>, ImportError> {
        check_thread(self, query.thread_id)?;
        validate_page(query.limit)?;
        if matches!((query.start_nanos, query.end_nanos), (Some(start), Some(end)) if start >= end)
        {
            return Err(ImportError::new(
                ErrorKind::Format,
                None,
                "Timeline window must have start < end",
            ));
        }
        let matches = |interval: &&TimelineInterval| {
            query.thread_id.is_none_or(|id| interval.thread_id == id)
                && query
                    .start_nanos
                    .is_none_or(|start| interval.end_nanos > start)
                && query.end_nanos.is_none_or(|end| interval.start_nanos < end)
        };
        let total = self.intervals.iter().filter(matches).count();
        page(
            self.intervals.iter().filter(matches).cloned(),
            total,
            query.offset,
            query.limit,
        )
    }
    /// Resolve one selected interval's path, bounded by the import depth cap.
    pub fn stack(&self, leaf: NodeId) -> Result<Vec<MethodId>, ImportError> {
        let mut row = self
            .nodes
            .get(leaf)
            .ok_or_else(|| ImportError::new(ErrorKind::Format, None, "Unknown call-tree node"))?;
        let mut result = Vec::with_capacity(row.row.depth);
        loop {
            if let Some(method) = row.row.method_id {
                result.push(method);
            }
            match row.row.parent_id {
                Some(parent) => row = &self.nodes[parent],
                None => break,
            }
        }
        result.reverse();
        Ok(result)
    }
}
fn check_thread(
    session: &MethodRecordingSession,
    thread: Option<ThreadId>,
) -> Result<(), ImportError> {
    if thread.is_some_and(|id| !session.threads.contains_key(&id)) {
        Err(ImportError::new(
            ErrorKind::Format,
            None,
            "Unknown method-recording thread",
        ))
    } else {
        Ok(())
    }
}
fn validate_page(limit: usize) -> Result<(), ImportError> {
    if limit == 0 || limit > MAX_PAGE_SIZE {
        Err(ImportError::new(
            ErrorKind::Limit,
            None,
            "Page limit must be in 1..=512",
        ))
    } else {
        Ok(())
    }
}
fn page<T>(
    values: impl Iterator<Item = T>,
    total: usize,
    offset: usize,
    limit: usize,
) -> Result<Page<T>, ImportError> {
    validate_page(limit)?;
    let rows: Vec<T> = values.skip(offset).take(limit).collect();
    let has_more = offset.saturating_add(rows.len()) < total;
    Ok(Page {
        total,
        offset,
        rows,
        has_more,
    })
}

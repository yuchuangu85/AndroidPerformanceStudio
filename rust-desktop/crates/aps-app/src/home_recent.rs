//! Bounded Home history domain adapter. Discovery is passive: no profile parsing,
//! directory crawling, migration, repair, or artifact deletion. The CPU adapter
//! retains its explicit reopen/digest contract. Layout and Memory require explicit
//! Rust-owned records; Trace also delegates to its feature-owned captured-session
//! adapter. This module neither decodes Compose/private Trace catalogs nor wires UI.
use crate::cpu::{
    CPU_MEDIA_TYPE, CPU_SESSION_PREFIX, cpu_artifact, cpu_snapshot_path, validate_cpu_session,
};
use aps_contracts::ArtifactId;
use aps_json::{Limits, Value};
use aps_storage::{PageRequest, QueryBinding, Storage};
use std::{
    collections::{HashSet, VecDeque},
    path::{Component, Path, PathBuf},
    sync::Mutex,
};

pub const MAX_HOME_RECENT_ITEMS: usize = 6;
const SCAN_LIMIT: usize = 200;
const SCAN_PAGE_SIZE: usize = 20;
pub const MAX_RECORDED_RECENT_ITEMS: usize = SCAN_LIMIT;
const MAX_INDEX_BYTES: u64 = 1024 * 1024;
const MAX_PATH_BYTES: usize = 4096;
const MAX_PACKAGE_BYTES: usize = 256;
const RECENT_SCHEMA: &str = "aps-rust-home-recent-v1";
// Covers independent store instances in this process. Multiple writer processes
// must be serialized by the caller; atomic_write protects readers, not lost updates.
static RECENT_STORE_LOCK: Mutex<()> = Mutex::new(());

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HomeRecentSource {
    Layout,
    Cpu,
    Trace,
    Memory,
}

pub type HomeFeature = HomeRecentSource;

/// Plain publication event. Workspaces may emit it after validated, current
/// publication; the shell owns subscription and background history persistence.
#[derive(Debug, Clone)]
pub struct RecentArtifactOpened {
    pub feature: HomeFeature,
    pub path: PathBuf,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RecordedFeature {
    Layout,
    Trace,
    Memory,
}

impl TryFrom<HomeFeature> for RecordedFeature {
    type Error = String;

    fn try_from(feature: HomeFeature) -> Result<Self, Self::Error> {
        match feature {
            HomeFeature::Layout => Ok(Self::Layout),
            HomeFeature::Trace => Ok(Self::Trace),
            HomeFeature::Memory => Ok(Self::Memory),
            HomeFeature::Cpu => Err("CPU Home history uses the existing metadata catalog".into()),
        }
    }
}

impl RecordedFeature {
    fn key(self) -> &'static str {
        match self {
            Self::Layout => "layout",
            Self::Trace => "trace",
            Self::Memory => "memory",
        }
    }

    fn file_name(self) -> &'static str {
        match self {
            Self::Layout => "home-recent-layout-v1.json",
            Self::Trace => "home-recent-trace-v1.json",
            Self::Memory => "home-recent-memory-v1.json",
        }
    }
}

/// Only metadata supplied by a successful feature workflow, never inferred from
/// index/import times or file mtimes. Memory callers choose the usable HPROF;
/// this path-only store cannot choose between raw and converted session artifacts.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RecentPathEntry {
    pub path: PathBuf,
    pub package_name: Option<String>,
    pub captured_at_unix_ms: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) enum HistoryMutation {
    Record(RecordedFeature, RecentPathEntry),
    Clear(RecordedFeature),
}

impl HistoryMutation {
    pub(crate) fn feature(&self) -> RecordedFeature {
        match self {
            Self::Record(feature, _) | Self::Clear(feature) => *feature,
        }
    }

    pub(crate) fn apply(&self, store: &RecentPathStore) -> Result<(), String> {
        match self {
            Self::Record(feature, entry) => store.record(*feature, entry.clone()),
            Self::Clear(feature) => store.clear(*feature),
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct QueuedHistoryMutation {
    pub sequence: u64,
    pub mutation: HistoryMutation,
}

/// The shell enqueues on the UI thread and starts only one write at a time.
/// A filesystem mutex cannot provide this event ordering: a queued older record
/// must finish before clear, even when its background task has not started yet.
#[derive(Default)]
pub(crate) struct HistoryMutationQueue {
    sequence: u64,
    pending: VecDeque<QueuedHistoryMutation>,
    running: Option<QueuedHistoryMutation>,
}

impl HistoryMutationQueue {
    pub(crate) fn enqueue(&mut self, mutation: HistoryMutation) {
        self.sequence += 1;
        self.pending.push_back(QueuedHistoryMutation {
            sequence: self.sequence,
            mutation,
        });
    }

    pub(crate) fn start_next(&mut self) -> Option<QueuedHistoryMutation> {
        if self.running.is_some() {
            return None;
        }
        let next = self.pending.pop_front()?;
        self.running = Some(next.clone());
        Some(next)
    }

    pub(crate) fn complete(&mut self, sequence: u64) -> bool {
        if self.running.as_ref().map(|item| item.sequence) != Some(sequence) {
            return false;
        }
        self.running = None;
        true
    }

    pub(crate) fn is_pending(&self) -> bool {
        self.running.is_some() || !self.pending.is_empty()
    }

    pub(crate) fn has_feature(&self, feature: RecordedFeature) -> bool {
        self.running
            .iter()
            .chain(self.pending.iter())
            .any(|item| item.mutation.feature() == feature)
    }

    pub(crate) fn has_clear(&self, feature: RecordedFeature) -> bool {
        self.running
            .iter()
            .chain(self.pending.iter())
            .any(|item| item.mutation == HistoryMutation::Clear(feature))
    }
}

/// Separate per-feature indexes under a caller-supplied absolute Rust-owned root.
/// No environment/default-root lookup or access to legacy feature catalogs.
#[derive(Debug, Clone)]
pub struct RecentPathStore {
    root: PathBuf,
}

impl RecentPathStore {
    pub fn new(root: impl Into<PathBuf>) -> Self {
        Self { root: root.into() }
    }

    /// Explicitly record an existing regular file, move it to the front, and
    /// deduplicate by absolute lexical path. Capture time does not reorder MRU.
    /// Rejects malformed existing history rather than replacing it with empty.
    pub fn record(
        &self,
        feature: RecordedFeature,
        mut entry: RecentPathEntry,
    ) -> Result<(), String> {
        let _guard = RECENT_STORE_LOCK
            .lock()
            .map_err(|_| "Home history lock is unavailable")?;
        validate_entry(&entry)?;
        entry.path = entry.path.components().collect();
        if !regular_artifact(&entry.path) {
            return Err("Home recent artifact is not an existing regular file".into());
        }
        let mut entries = self.read_entries(feature)?;
        entries.retain(|existing| existing.path != entry.path);
        entries.insert(0, entry);
        entries.truncate(MAX_RECORDED_RECENT_ITEMS);
        let value = serde_json::json!({
            "schema": RECENT_SCHEMA,
            "feature": feature.key(),
            "entries": entries.iter().map(|entry| serde_json::json!({
                "path": entry.path.to_str().expect("validated UTF-8 path"),
                "package_name": entry.package_name,
                "captured_at_unix_ms": entry.captured_at_unix_ms,
            })).collect::<Vec<_>>(),
        });
        let bytes = aps_json::to_vec(&value, recent_limits())
            .map_err(|_| "Home recent index exceeds its storage budget")?;
        aps_platform::atomic_write(&self.root.join(feature.file_name()), &bytes)
            .map_err(|_| "Cannot write Home recent index".into())
    }

    /// Clear just this feature's history, including a corrupt regular index.
    /// Does not open/delete any recorded artifact or create missing directories.
    pub fn clear(&self, feature: RecordedFeature) -> Result<(), String> {
        let _guard = RECENT_STORE_LOCK
            .lock()
            .map_err(|_| "Home history lock is unavailable")?;
        if !self.root_exists()? {
            return Ok(());
        }
        let index = self.root.join(feature.file_name());
        match std::fs::symlink_metadata(&index) {
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
            Ok(metadata) if metadata.is_file() && !metadata.is_symlink() => {}
            _ => return Err("Home recent index is not a regular file".into()),
        }
        std::fs::remove_file(index).map_err(|_| "Cannot clear Home recent history".into())
    }

    /// Read-only newest-recorded-first history. Missing indexes are empty;
    /// corrupt indexes are errors. Missing, inaccessible, nonregular and final
    /// symlink artifacts are skipped without rewriting history. No content reads.
    pub fn load(
        &self,
        feature: RecordedFeature,
        limit: usize,
    ) -> Result<Vec<RecentPathEntry>, String> {
        let limit = limit.min(MAX_RECORDED_RECENT_ITEMS);
        if limit == 0 {
            return Ok(Vec::new());
        }
        let _guard = RECENT_STORE_LOCK
            .lock()
            .map_err(|_| "Home history lock is unavailable")?;
        Ok(self
            .read_entries(feature)?
            .into_iter()
            .filter(|entry| regular_artifact(&entry.path))
            .take(limit)
            .collect())
    }

    fn root_exists(&self) -> Result<bool, String> {
        if !self.root.is_absolute()
            || self
                .root
                .components()
                .any(|component| component == Component::ParentDir)
        {
            return Err("Home history root must be absolute and without parent traversal".into());
        }
        match std::fs::symlink_metadata(&self.root) {
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(false),
            Ok(metadata) if metadata.is_dir() && !metadata.is_symlink() => Ok(true),
            _ => Err("Home history root is not an accessible directory".into()),
        }
    }

    fn read_entries(&self, feature: RecordedFeature) -> Result<Vec<RecentPathEntry>, String> {
        if !self.root_exists()? {
            return Ok(Vec::new());
        }
        let index = self.root.join(feature.file_name());
        match std::fs::symlink_metadata(&index) {
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
            Ok(metadata) if metadata.is_file() && !metadata.is_symlink() => {}
            _ => return Err("Home recent index is not a regular file".into()),
        }
        let value = aps_json::read(&index, recent_limits())
            .map_err(|_| "Home recent index is corrupt or exceeds its budget")?;
        let object = value.as_object().ok_or("Invalid Home recent index")?;
        if object.len() != 3
            || object.get("schema").and_then(Value::as_str) != Some(RECENT_SCHEMA)
            || object.get("feature").and_then(Value::as_str) != Some(feature.key())
        {
            return Err("Unsupported Home recent schema or feature".into());
        }
        let values = object
            .get("entries")
            .and_then(Value::as_array)
            .ok_or("Invalid Home recent entries")?;
        let mut paths = HashSet::new();
        let mut entries = Vec::with_capacity(values.len());
        for value in values {
            let object = value.as_object().ok_or("Invalid Home recent entry")?;
            if object.len() != 3 {
                return Err("Invalid Home recent entry fields".into());
            }
            let path = object
                .get("path")
                .and_then(Value::as_str)
                .ok_or("Invalid Home recent path")?;
            let package_name = match object.get("package_name") {
                Some(Value::Null) => None,
                Some(Value::String(package)) => Some(package.clone()),
                _ => return Err("Invalid Home recent package".into()),
            };
            let captured_at_unix_ms = match object.get("captured_at_unix_ms") {
                Some(Value::Null) => None,
                Some(value) => Some(value.as_i64().ok_or("Invalid Home recent capture time")?),
                None => return Err("Missing Home recent capture time field".into()),
            };
            let entry = RecentPathEntry {
                path: PathBuf::from(path),
                package_name,
                captured_at_unix_ms,
            };
            validate_entry(&entry)?;
            if !paths.insert(entry.path.clone()) {
                return Err("Duplicate Home recent path".into());
            }
            entries.push(entry);
        }
        Ok(entries)
    }
}

fn recent_limits() -> Limits {
    Limits {
        max_bytes: MAX_INDEX_BYTES,
        max_depth: 4,
        max_nodes: 2_000,
        max_string_bytes: MAX_PATH_BYTES,
        max_collection_items: MAX_RECORDED_RECENT_ITEMS,
    }
}

fn validate_entry(entry: &RecentPathEntry) -> Result<(), String> {
    let path = entry
        .path
        .to_str()
        .ok_or("Home recent path must be UTF-8")?;
    if !entry.path.is_absolute()
        || entry.path.file_name().is_none()
        || path.len() > MAX_PATH_BYTES
        || path.chars().any(char::is_control)
        || entry
            .path
            .components()
            .any(|component| component == Component::ParentDir)
    {
        return Err(
            "Home recent path must be absolute, bounded and without parent traversal".into(),
        );
    }
    if entry.package_name.as_ref().is_some_and(|name| {
        name.trim().is_empty()
            || name.len() > MAX_PACKAGE_BYTES
            || name.chars().any(char::is_control)
    }) {
        return Err("Home recent package must be nonempty and bounded when supplied".into());
    }
    if entry
        .captured_at_unix_ms
        .is_some_and(|timestamp| timestamp < 0)
    {
        return Err("Home recent capture time must not be negative".into());
    }
    Ok(())
}

fn regular_artifact(path: &Path) -> bool {
    std::fs::symlink_metadata(path)
        .is_ok_and(|metadata| metadata.is_file() && !metadata.is_symlink())
}

#[derive(Debug, Clone)]
pub enum HomeRecentTarget {
    Cpu(HomeRecentCpu),
    Layout(PathBuf),
    Trace(PathBuf),
    Memory(PathBuf),
}

#[derive(Debug, Clone)]
pub struct HomeOverviewItem {
    /// Feature-scoped identity; a single path recorded in different features is
    /// not the same action. CPU identity retains the verified session binding.
    pub id: String,
    pub source: HomeRecentSource,
    pub title: String,
    pub package_name: Option<String>,
    pub captured_at_unix_ms: Option<i64>,
    pub target: HomeRecentTarget,
}

#[derive(Debug, Clone)]
pub struct HomeRecentSnapshot {
    pub items: Vec<HomeOverviewItem>,
    pub unavailable_source_count: usize,
    pub source_count: usize,
}

/// Passive snapshot of four independent sources in Compose source order:
/// Layout, CPU, Trace, Memory. Each source is newest-first in its own chronology;
/// round-robin interleaving does not invent a shared capture/mtime chronology.
/// No native feature writer or activation path is connected by this adapter.
pub fn load_snapshot(
    metadata_path: &Path,
    cpu_snapshot_root: &Path,
    recent_root: &Path,
    limit: usize,
) -> HomeRecentSnapshot {
    let limit = limit.min(MAX_HOME_RECENT_ITEMS);
    let mut snapshot = HomeRecentSnapshot {
        items: Vec::with_capacity(limit),
        unavailable_source_count: 0,
        source_count: 4,
    };
    if limit == 0 {
        return snapshot;
    }
    let store = RecentPathStore::new(recent_root);
    let recorded = |feature| -> Result<Vec<HomeOverviewItem>, String> {
        let mut entries = store.load(feature, limit)?;
        if feature == RecordedFeature::Trace {
            let application_root = metadata_path
                .parent()
                .filter(|root| root.is_absolute())
                .ok_or("Home metadata path must have an absolute application root")?;
            // Read even when recorded MRU already fills the page: a broken
            // captured catalog makes this source unavailable, not partly healthy.
            let captured = crate::trace::recent_home_entries(application_root, limit)?;
            entries = merge_trace_entries(entries, captured, limit)?;
        }
        Ok(entries
            .into_iter()
            .map(|entry| {
                let (source, target) = match feature {
                    RecordedFeature::Layout => (
                        HomeRecentSource::Layout,
                        HomeRecentTarget::Layout(entry.path.clone()),
                    ),
                    RecordedFeature::Trace => (
                        HomeRecentSource::Trace,
                        HomeRecentTarget::Trace(entry.path.clone()),
                    ),
                    RecordedFeature::Memory => (
                        HomeRecentSource::Memory,
                        HomeRecentTarget::Memory(entry.path.clone()),
                    ),
                };
                HomeOverviewItem {
                    id: format!("{}:{}", feature.key(), entry.path.display()),
                    source,
                    title: entry
                        .path
                        .file_name()
                        .unwrap()
                        .to_string_lossy()
                        .into_owned(),
                    package_name: entry.package_name,
                    captured_at_unix_ms: entry.captured_at_unix_ms,
                    target,
                }
            })
            .collect())
    };
    let cpu = || {
        load_recent(metadata_path, cpu_snapshot_root, limit).map(|items| {
            items
                .into_iter()
                .map(|item| HomeOverviewItem {
                    id: format!("cpu:{}", item.cpu.binding.session_id.as_str()),
                    source: HomeRecentSource::Cpu,
                    title: item.title,
                    package_name: item.package_name,
                    captured_at_unix_ms: None,
                    target: HomeRecentTarget::Cpu(item.cpu),
                })
                .collect::<Vec<_>>()
        })
    };
    let lists = [
        recorded(RecordedFeature::Layout),
        cpu(),
        recorded(RecordedFeature::Trace),
        recorded(RecordedFeature::Memory),
    ]
    .map(|result| match result {
        Ok(items) => items,
        Err(_) => {
            snapshot.unavailable_source_count += 1;
            Vec::new()
        }
    });
    let mut seen = HashSet::new();
    for index in 0..limit {
        for items in &lists {
            if let Some(item) = items.get(index)
                && seen.insert(item.id.clone())
            {
                snapshot.items.push(item.clone());
                if snapshot.items.len() == limit {
                    return snapshot;
                }
            }
        }
    }
    snapshot
}

fn merge_trace_entries(
    mut recorded: Vec<RecentPathEntry>,
    captured: Vec<RecentPathEntry>,
    limit: usize,
) -> Result<Vec<RecentPathEntry>, String> {
    let mut paths = recorded
        .iter()
        .map(|entry| entry.path.clone())
        .collect::<HashSet<_>>();
    for entry in captured {
        validate_entry(&entry)?;
        if paths.insert(entry.path.clone()) {
            recorded.push(entry);
        } else if let Some(existing) = recorded.iter_mut().find(|record| record.path == entry.path)
        {
            existing.package_name = existing.package_name.take().or(entry.package_name);
            existing.captured_at_unix_ms =
                existing.captured_at_unix_ms.or(entry.captured_at_unix_ms);
        }
    }
    recorded.truncate(limit);
    Ok(recorded)
}

#[derive(Debug, Clone)]
pub struct HomeRecentCpu {
    pub binding: QueryBinding,
    pub artifact_id: ArtifactId,
    pub byte_length: u64,
}

#[derive(Debug, Clone)]
pub struct HomeRecentItem {
    pub title: String,
    pub package_name: Option<String>,
    pub cpu: HomeRecentCpu,
}

/// Inspect at most 200 recent session rows to publish at most six entries.
/// Missing/invalid snapshot files are omitted. A malformed index is an error,
/// never interpreted as empty history or repaired by this passive reader.
pub fn load_recent(
    metadata_path: &Path,
    snapshot_root: &Path,
    limit: usize,
) -> Result<Vec<HomeRecentItem>, String> {
    let limit = limit.min(MAX_HOME_RECENT_ITEMS);
    if limit == 0 {
        return Ok(Vec::new());
    }
    // A missing Rust-owned database is the ordinary first-launch empty state.
    match std::fs::symlink_metadata(metadata_path) {
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(_) => return Err("cannot inspect Home recent metadata database".into()),
        Ok(_) => {}
    }
    let storage = Storage::open_read_only(metadata_path)?;
    let mut items = Vec::with_capacity(limit);
    let mut offset = 0;
    while offset < SCAN_LIMIT && items.len() < limit {
        let rows = storage.list_recent_sessions_read_only(PageRequest {
            offset,
            limit: SCAN_PAGE_SIZE.min(SCAN_LIMIT - offset),
        })?;
        let scanned = rows.len();
        if scanned == 0 {
            break;
        }
        for summary in rows {
            if !summary.id.as_str().starts_with(CPU_SESSION_PREFIX) {
                continue;
            }
            if summary.artifact_count != 1 {
                return Err("Home recent CPU session has invalid artifact references".into());
            }
            let session = storage
                .session(&summary.id)?
                .ok_or("Home recent CPU session disappeared")?;
            if session.generation != summary.generation || session.artifact_ids.len() != 1 {
                return Err("Home recent CPU session changed during discovery".into());
            }
            let artifact_id = session.artifact_ids[0].clone();
            let artifact = storage
                .artifact(&artifact_id)?
                .ok_or("Home recent CPU artifact is missing from index")?;
            let expected = cpu_artifact(artifact.source_digest, artifact.byte_length)?;
            if artifact.media_type != CPU_MEDIA_TYPE || artifact_id != expected.id {
                return Err("Home recent CPU artifact identity or media type is invalid".into());
            }
            validate_cpu_session(&session, artifact.source_digest, &artifact_id)
                .map_err(|error| error.display(aps_ui_foundation::Language::English))?;
            let binding = QueryBinding {
                session_id: session.id,
                generation: session.generation,
                source_digest: artifact.source_digest,
            };
            storage.validate_binding(&binding)?;
            let snapshot = cpu_snapshot_path(snapshot_root, binding.source_digest)
                .map_err(|error| error.display(aps_ui_foundation::Language::English))?;
            let metadata = match std::fs::symlink_metadata(&snapshot) {
                Ok(metadata) => metadata,
                // Missing or inaccessible snapshots are not usable recent items.
                Err(_) => continue,
            };
            // No hash or parser here: passive Home loading never reads the full
            // profile. Explicit open repeats the authoritative digest checks.
            if metadata.file_type().is_symlink()
                || !metadata.is_file()
                || metadata.len() != artifact.byte_length
                || metadata.len() > aps_cpu::DEFAULT_INPUT_LIMIT as u64
            {
                continue;
            }
            items.push(HomeRecentItem {
                title: session.label,
                // CPU import metadata has no verified package/capture time.
                // Import/index timestamps must not impersonate capture time.
                package_name: None,
                cpu: HomeRecentCpu {
                    binding,
                    artifact_id,
                    byte_length: artifact.byte_length,
                },
            });
            if items.len() == limit {
                break;
            }
        }
        offset += scanned;
        if scanned < SCAN_PAGE_SIZE {
            break;
        }
    }
    storage.validate_read_only_snapshot()?;
    Ok(items)
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_contracts::{ProfilerSession, ProfilerSessionId, SourceDigest};
    use aps_storage::{DATABASE_FILE_NAME, Storage};
    use std::{
        path::PathBuf,
        sync::atomic::{AtomicU64, Ordering},
    };
    static NEXT: AtomicU64 = AtomicU64::new(0);
    struct Fixture {
        root: PathBuf,
    }
    impl Fixture {
        fn new() -> Self {
            let root = std::env::temp_dir().join(format!(
                "aps-home-recent-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            std::fs::create_dir(&root).unwrap();
            Self { root }
        }
        fn database(&self) -> PathBuf {
            self.root.join(DATABASE_FILE_NAME)
        }
        fn snapshots(&self) -> PathBuf {
            self.root.join("cpu-artifacts")
        }
        fn history(&self) -> PathBuf {
            self.root.join("recent-artifacts")
        }
        fn store(&self) -> RecentPathStore {
            RecentPathStore::new(self.history())
        }
        fn artifact(&self, name: &str) -> PathBuf {
            let path = self.root.join(name);
            std::fs::write(&path, b"stat only; not a parsed artifact").unwrap();
            path
        }
        fn snapshot(&self, limit: usize) -> HomeRecentSnapshot {
            load_snapshot(&self.database(), &self.snapshots(), &self.history(), limit)
        }
        fn write_history(&self, feature: RecordedFeature, value: &Value) -> PathBuf {
            std::fs::create_dir_all(self.history()).unwrap();
            let path = self.history().join(feature.file_name());
            std::fs::write(&path, serde_json::to_vec(value).unwrap()).unwrap();
            path
        }
        fn populate(&self, label_override: Option<&str>, snapshot: bool) {
            let digest = SourceDigest::from_bytes([7; 32]);
            let artifact = crate::cpu::cpu_artifact(digest, 4).unwrap();
            let mut storage = Storage::open(self.database()).unwrap();
            storage.put_artifact(&artifact, 123).unwrap();
            let mut session = ProfilerSession::new(
                ProfilerSessionId::new(format!("cpu-import-v1:{}", digest.to_hex())).unwrap(),
            );
            session.artifacts.push(artifact.id);
            storage
                .create_session(
                    &session,
                    label_override.unwrap_or(&format!("CPU import · {}", &digest.to_hex()[..16])),
                    123,
                )
                .unwrap();
            drop(storage);
            if snapshot {
                std::fs::create_dir(self.snapshots()).unwrap();
                // Not a protobuf and intentionally not its digest: passive Home
                // discovery is metadata/stat-only; explicit open owns hashing.
                std::fs::write(
                    self.snapshots()
                        .join(format!("{}.simpleperf.pb", digest.to_hex())),
                    b"stat",
                )
                .unwrap();
            }
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.root);
        }
    }

    #[test]
    fn shell_history_queue_delayed_older_records_finish_before_clear_and_restart_empty() {
        let fixture = Fixture::new();
        let first = fixture.artifact("first.apinspect");
        let second = fixture.artifact("second.apinspect");
        let mut queue = HistoryMutationQueue::default();
        queue.enqueue(HistoryMutation::Record(
            RecordedFeature::Layout,
            entry(first.clone()),
        ));
        let running = queue.start_next().unwrap();
        let sequence = running.sequence;
        let store = fixture.store();
        let (release, wait) = std::sync::mpsc::channel();
        let delayed = std::thread::spawn(move || {
            wait.recv().unwrap();
            running.mutation.apply(&store)
        });
        queue.enqueue(HistoryMutation::Record(
            RecordedFeature::Layout,
            entry(second.clone()),
        ));
        queue.enqueue(HistoryMutation::Clear(RecordedFeature::Layout));
        assert!(queue.has_clear(RecordedFeature::Layout));
        assert!(
            queue.start_next().is_none(),
            "clear cannot overtake an older write"
        );
        assert!(
            !queue.complete(sequence + 1),
            "wrong completion cannot release the writer"
        );
        assert!(queue.start_next().is_none());
        release.send(()).unwrap();
        delayed.join().unwrap().unwrap();
        assert!(queue.complete(sequence));
        assert!(
            !queue.complete(sequence),
            "a duplicate completion is ignored"
        );
        let older = queue.start_next().unwrap();
        assert!(
            matches!(&older.mutation, HistoryMutation::Record(_, value) if value.path == second)
        );
        older.mutation.apply(&fixture.store()).unwrap();
        assert!(queue.complete(older.sequence));
        let clear = queue.start_next().unwrap();
        assert_eq!(
            clear.mutation,
            HistoryMutation::Clear(RecordedFeature::Layout)
        );
        clear.mutation.apply(&fixture.store()).unwrap();
        assert!(queue.complete(clear.sequence));
        assert!(!queue.is_pending());
        assert!(
            RecentPathStore::new(fixture.history())
                .load(RecordedFeature::Layout, 10)
                .unwrap()
                .is_empty()
        );
        assert!(first.is_file());
        assert!(second.is_file());
    }

    #[test]
    fn shell_history_clear_preserves_home_other_sources_and_all_evidence_bytes() {
        let fixture = Fixture::new();
        fixture.populate(None, true);
        let paths = [
            (
                RecordedFeature::Layout,
                fixture.artifact("layout.apinspect"),
            ),
            (RecordedFeature::Trace, fixture.artifact("trace.pftrace")),
            (RecordedFeature::Memory, fixture.artifact("memory.hprof")),
        ];
        for (feature, path) in &paths {
            fixture
                .store()
                .record(*feature, entry(path.clone()))
                .unwrap();
        }
        assert_eq!(fixture.snapshot(6).items.len(), 4);
        let indexes = [RecordedFeature::Trace, RecordedFeature::Memory].map(|feature| {
            let path = fixture.history().join(feature.file_name());
            let bytes = std::fs::read(&path).unwrap();
            (path, bytes)
        });
        let database = std::fs::read(fixture.database()).unwrap();
        let cpu_path = std::fs::read_dir(fixture.snapshots())
            .unwrap()
            .next()
            .unwrap()
            .unwrap()
            .path();
        let cpu_bytes = std::fs::read(&cpu_path).unwrap();
        let evidence = paths
            .iter()
            .map(|(_, path)| (path, std::fs::read(path).unwrap()))
            .collect::<Vec<_>>();
        let mut queue = HistoryMutationQueue::default();
        queue.enqueue(HistoryMutation::Clear(RecordedFeature::Layout));
        let clear = queue.start_next().unwrap();
        clear.mutation.apply(&fixture.store()).unwrap();
        assert!(queue.complete(clear.sequence));
        let snapshot = fixture.snapshot(6);
        assert_eq!(snapshot.unavailable_source_count, 0);
        assert_eq!(snapshot.items.len(), 3);
        assert!(
            snapshot
                .items
                .iter()
                .all(|item| item.source != HomeRecentSource::Layout)
        );
        for source in [
            HomeRecentSource::Cpu,
            HomeRecentSource::Trace,
            HomeRecentSource::Memory,
        ] {
            assert!(snapshot.items.iter().any(|item| item.source == source));
        }
        for (path, bytes) in indexes {
            assert_eq!(std::fs::read(path).unwrap(), bytes);
        }
        for (path, bytes) in evidence {
            assert_eq!(std::fs::read(path).unwrap(), bytes);
        }
        assert_eq!(std::fs::read(fixture.database()).unwrap(), database);
        assert_eq!(std::fs::read(cpu_path).unwrap(), cpu_bytes);
    }

    #[test]
    fn shell_history_queue_retains_only_records_accepted_after_clear() {
        let fixture = Fixture::new();
        let before = entry(fixture.artifact("before.apinspect"));
        let after = entry(fixture.artifact("after.apinspect"));
        let memory = entry(fixture.artifact("other.hprof"));
        let mut queue = HistoryMutationQueue::default();
        for mutation in [
            HistoryMutation::Record(RecordedFeature::Layout, before.clone()),
            HistoryMutation::Record(RecordedFeature::Memory, memory.clone()),
            HistoryMutation::Clear(RecordedFeature::Layout),
            HistoryMutation::Record(RecordedFeature::Layout, after.clone()),
        ] {
            queue.enqueue(mutation);
        }
        assert!(queue.has_feature(RecordedFeature::Memory));
        while let Some(operation) = queue.start_next() {
            operation.mutation.apply(&fixture.store()).unwrap();
            assert!(queue.complete(operation.sequence));
        }
        assert!(!queue.has_feature(RecordedFeature::Memory));
        assert_eq!(
            fixture.store().load(RecordedFeature::Layout, 10).unwrap(),
            vec![after]
        );
        assert_eq!(
            fixture.store().load(RecordedFeature::Memory, 10).unwrap(),
            vec![memory]
        );
        assert!(before.path.is_file());
    }

    #[test]
    fn shell_history_failed_record_does_not_prevent_queued_corrupt_index_clear() {
        let fixture = Fixture::new();
        let archive = entry(fixture.artifact("kept.apinspect"));
        fixture.write_history(RecordedFeature::Layout, &Value::Null);
        let mut queue = HistoryMutationQueue::default();
        queue.enqueue(HistoryMutation::Record(
            RecordedFeature::Layout,
            archive.clone(),
        ));
        queue.enqueue(HistoryMutation::Clear(RecordedFeature::Layout));
        let record = queue.start_next().unwrap();
        assert!(record.mutation.apply(&fixture.store()).is_err());
        assert!(queue.complete(record.sequence));
        let clear = queue.start_next().unwrap();
        clear.mutation.apply(&fixture.store()).unwrap();
        assert!(queue.complete(clear.sequence));
        assert!(archive.path.is_file());
        assert!(
            fixture
                .store()
                .load(RecordedFeature::Layout, 10)
                .unwrap()
                .is_empty()
        );
    }

    #[::core::prelude::v1::test]
    fn first_launch_does_not_create_metadata_or_snapshots() {
        let fixture = Fixture::new();
        assert!(
            load_recent(&fixture.database(), &fixture.snapshots(), 6)
                .unwrap()
                .is_empty()
        );
        assert!(std::fs::read_dir(&fixture.root).unwrap().next().is_none());
    }
    #[::core::prelude::v1::test]
    fn discovery_is_metadata_only_and_does_not_invent_package_or_capture_time() {
        let fixture = Fixture::new();
        fixture.populate(None, true);
        let before = std::fs::read(fixture.database()).unwrap();
        let items = load_recent(&fixture.database(), &fixture.snapshots(), 6).unwrap();
        assert_eq!(items.len(), 1);
        assert_eq!(items[0].title, "CPU import · 0707070707070707");
        assert_eq!(items[0].package_name, None);
        assert_eq!(std::fs::read(fixture.database()).unwrap(), before);
    }
    #[::core::prelude::v1::test]
    fn missing_saved_profile_is_filtered_but_index_corruption_is_an_error() {
        let missing = Fixture::new();
        missing.populate(None, false);
        assert!(
            load_recent(&missing.database(), &missing.snapshots(), 6)
                .unwrap()
                .is_empty()
        );
        let malformed = Fixture::new();
        malformed.populate(Some("not the canonical CPU label"), true);
        assert!(load_recent(&malformed.database(), &malformed.snapshots(), 6).is_err());
    }

    fn entry(path: PathBuf) -> RecentPathEntry {
        RecentPathEntry {
            path,
            package_name: None,
            captured_at_unix_ms: None,
        }
    }

    fn raw_entry(entry: &RecentPathEntry) -> Value {
        serde_json::json!({
            "path": entry.path,
            "package_name": entry.package_name,
            "captured_at_unix_ms": entry.captured_at_unix_ms,
        })
    }

    fn raw_history(feature: RecordedFeature, entries: Vec<Value>) -> Value {
        serde_json::json!({ "schema": RECENT_SCHEMA, "feature": feature.key(), "entries": entries })
    }

    #[test]
    fn snapshot_and_clear_first_launch_do_not_create_any_store() {
        let fixture = Fixture::new();
        let snapshot = fixture.snapshot(usize::MAX);
        assert!(snapshot.items.is_empty());
        assert_eq!(snapshot.unavailable_source_count, 0);
        assert_eq!(snapshot.source_count, 4);
        for feature in [
            RecordedFeature::Layout,
            RecordedFeature::Trace,
            RecordedFeature::Memory,
        ] {
            fixture.store().clear(feature).unwrap();
        }
        assert!(std::fs::read_dir(&fixture.root).unwrap().next().is_none());
    }

    #[test]
    fn zero_limit_skips_io_even_for_invalid_roots_and_indexes() {
        let fixture = Fixture::new();
        std::fs::write(fixture.database(), b"not sqlite").unwrap();
        std::fs::write(fixture.history(), b"not a directory").unwrap();
        let snapshot = fixture.snapshot(0);
        assert!(snapshot.items.is_empty());
        assert_eq!(snapshot.unavailable_source_count, 0);
        assert_eq!(snapshot.source_count, 4);
        assert!(
            fixture
                .store()
                .load(RecordedFeature::Layout, 0)
                .unwrap()
                .is_empty()
        );
        assert!(
            RecentPathStore::new("relative")
                .load(RecordedFeature::Memory, 0)
                .unwrap()
                .is_empty()
        );
        assert!(
            load_recent(&fixture.database(), &fixture.snapshots(), 0)
                .unwrap()
                .is_empty()
        );
    }

    #[test]
    fn record_roundtrip_is_mru_deduplicated_and_keeps_only_explicit_metadata() {
        let fixture = Fixture::new();
        let first = entry(fixture.artifact("one.apinspect"));
        let second = entry(fixture.artifact("two.apinspect"));
        fixture
            .store()
            .record(RecordedFeature::Layout, first.clone())
            .unwrap();
        fixture
            .store()
            .record(RecordedFeature::Layout, second.clone())
            .unwrap();
        let mut updated = first.clone();
        updated.package_name = Some("com.example.app".into());
        updated.captured_at_unix_ms = Some(0);
        // Use another store instance and a lexical alias to exercise shared identity.
        let mut alias = updated.clone();
        alias.path = fixture.root.join(".").join("one.apinspect");
        fixture
            .store()
            .record(RecordedFeature::Layout, alias)
            .unwrap();
        let path = fixture.history().join(RecordedFeature::Layout.file_name());
        let before = std::fs::read(&path).unwrap();
        assert_eq!(
            fixture.store().load(RecordedFeature::Layout, 10).unwrap(),
            [updated, second]
        );
        assert_eq!(std::fs::read(path).unwrap(), before);
    }

    #[test]
    fn stale_files_and_directories_are_skipped_before_visible_limit_without_rewriting() {
        let fixture = Fixture::new();
        let valid = entry(fixture.artifact("valid.hprof"));
        let missing = entry(fixture.artifact("missing.hprof"));
        let directory = entry(fixture.artifact("directory.hprof"));
        let store = fixture.store();
        for value in [&valid, &missing, &directory] {
            store
                .record(RecordedFeature::Memory, value.clone())
                .unwrap();
        }
        std::fs::remove_file(&missing.path).unwrap();
        std::fs::remove_file(&directory.path).unwrap();
        std::fs::create_dir(&directory.path).unwrap();
        let index = fixture.history().join(RecordedFeature::Memory.file_name());
        let before = std::fs::read(&index).unwrap();
        assert_eq!(store.load(RecordedFeature::Memory, 1).unwrap(), [valid]);
        assert!(store.record(RecordedFeature::Memory, missing).is_err());
        assert!(store.record(RecordedFeature::Memory, directory).is_err());
        assert_eq!(std::fs::read(index).unwrap(), before);
    }

    #[test]
    fn invalid_record_metadata_is_rejected_without_creating_history() {
        let fixture = Fixture::new();
        let valid = entry(fixture.artifact("valid.pftrace"));
        let mut invalid = vec![
            entry(PathBuf::from("relative.pftrace")),
            entry(fixture.root.join("..").join("valid.pftrace")),
            entry(fixture.root.join("bad\nname")),
            entry(PathBuf::from(format!("/{}", "a".repeat(MAX_PATH_BYTES)))),
        ];
        for package in [
            "".to_owned(),
            " ".into(),
            "bad\npackage".into(),
            "a".repeat(MAX_PACKAGE_BYTES + 1),
        ] {
            invalid.push(RecentPathEntry {
                package_name: Some(package),
                ..valid.clone()
            });
        }
        invalid.push(RecentPathEntry {
            captured_at_unix_ms: Some(-1),
            ..valid
        });
        for invalid in invalid {
            assert!(
                fixture
                    .store()
                    .record(RecordedFeature::Trace, invalid)
                    .is_err()
            );
        }
        assert!(!fixture.history().exists());
    }

    #[test]
    fn corruption_is_fail_closed_even_beyond_visible_limit_and_record_preserves_bytes() {
        let fixture = Fixture::new();
        let valid = entry(fixture.artifact("valid.apinspect"));
        let raw = raw_entry(&valid);
        let mut negative = raw.clone();
        negative["captured_at_unix_ms"] = serde_json::json!(-1);
        let mut relative = raw.clone();
        relative["path"] = serde_json::json!("relative");
        let mut wrong_type = raw.clone();
        wrong_type["package_name"] = serde_json::json!(false);
        let mut extra = raw.clone();
        extra["unknown"] = Value::Null;
        let mut wrong_schema = raw_history(RecordedFeature::Layout, vec![]);
        wrong_schema["schema"] = serde_json::json!("future-or-compose-schema");
        let mut documents = vec![
            Value::Null,
            serde_json::json!([]),
            wrong_schema,
            raw_history(RecordedFeature::Memory, vec![raw.clone()]),
            raw_history(RecordedFeature::Layout, vec![raw.clone(), raw.clone()]),
        ];
        for bad in [
            negative,
            relative,
            wrong_type,
            extra,
            serde_json::json!({}),
            Value::Null,
        ] {
            documents.push(raw_history(RecordedFeature::Layout, vec![raw.clone(), bad]));
        }
        for document in documents {
            let index = fixture.write_history(RecordedFeature::Layout, &document);
            let before = std::fs::read(&index).unwrap();
            assert!(fixture.store().load(RecordedFeature::Layout, 1).is_err());
            assert!(
                fixture
                    .store()
                    .record(RecordedFeature::Layout, valid.clone())
                    .is_err()
            );
            assert_eq!(std::fs::read(index).unwrap(), before);
        }
    }

    #[test]
    fn malformed_duplicate_key_and_oversized_indexes_are_not_repaired() {
        let fixture = Fixture::new();
        let valid = entry(fixture.artifact("valid.apinspect"));
        std::fs::create_dir(fixture.history()).unwrap();
        let index = fixture.history().join(RecordedFeature::Layout.file_name());
        let too_many = raw_history(
            RecordedFeature::Layout,
            vec![raw_entry(&valid); MAX_RECORDED_RECENT_ITEMS + 1],
        );
        for bytes in [
            b"not json".to_vec(),
            br#"{"schema":"aps-rust-home-recent-v1","feature":"layout","entries":[],"entries":[]}"#
                .to_vec(),
            vec![b' '; MAX_INDEX_BYTES as usize + 1],
            serde_json::to_vec(&too_many).unwrap(),
            serde_json::to_vec(&raw_history(
                RecordedFeature::Layout,
                vec![serde_json::json!({
                    "path": format!("/{}", "a".repeat(MAX_PATH_BYTES)),
                    "package_name": null,
                    "captured_at_unix_ms": null,
                })],
            ))
            .unwrap(),
        ] {
            std::fs::write(&index, &bytes).unwrap();
            assert!(
                fixture
                    .store()
                    .load(RecordedFeature::Layout, usize::MAX)
                    .is_err()
            );
            assert!(
                fixture
                    .store()
                    .record(RecordedFeature::Layout, valid.clone())
                    .is_err()
            );
            assert_eq!(std::fs::read(&index).unwrap(), bytes);
        }
    }

    #[test]
    fn clear_recovers_corrupt_history_without_deleting_artifacts_or_other_sources() {
        let fixture = Fixture::new();
        fixture.populate(None, true);
        let saved = fixture.artifact("saved.apinspect");
        let memory = entry(fixture.artifact("saved.hprof"));
        fixture
            .store()
            .record(RecordedFeature::Memory, memory.clone())
            .unwrap();
        let index = fixture.write_history(RecordedFeature::Layout, &Value::Null);
        let memory_index = fixture.history().join(RecordedFeature::Memory.file_name());
        let before = std::fs::read(&memory_index).unwrap();
        let cpu_before = std::fs::read(fixture.database()).unwrap();
        fixture.store().clear(RecordedFeature::Layout).unwrap();
        assert!(!index.exists());
        assert!(saved.is_file());
        assert!(memory.path.is_file());
        assert_eq!(std::fs::read(&memory_index).unwrap(), before);
        assert_eq!(std::fs::read(fixture.database()).unwrap(), cpu_before);
        assert_eq!(
            load_recent(&fixture.database(), &fixture.snapshots(), 6)
                .unwrap()
                .len(),
            1
        );
        fixture
            .store()
            .record(RecordedFeature::Layout, entry(saved.clone()))
            .unwrap();
        fixture.store().clear(RecordedFeature::Layout).unwrap();
        assert!(saved.is_file());
        assert!(
            fixture
                .store()
                .load(RecordedFeature::Layout, 6)
                .unwrap()
                .is_empty()
        );
    }

    #[test]
    fn non_directory_roots_and_nonregular_indexes_are_unavailable_and_not_removed() {
        let fixture = Fixture::new();
        let value = entry(fixture.artifact("artifact"));
        for store in [
            RecentPathStore::new("relative"),
            RecentPathStore::new(fixture.root.join("..").join("history")),
        ] {
            assert!(store.load(RecordedFeature::Layout, 1).is_err());
            assert!(
                store
                    .record(RecordedFeature::Layout, value.clone())
                    .is_err()
            );
            assert!(store.clear(RecordedFeature::Layout).is_err());
        }
        std::fs::write(fixture.history(), b"file, not directory").unwrap();
        assert!(fixture.store().load(RecordedFeature::Layout, 1).is_err());
        assert!(
            fixture
                .store()
                .record(RecordedFeature::Layout, value.clone())
                .is_err()
        );
        assert!(fixture.store().clear(RecordedFeature::Layout).is_err());
        std::fs::remove_file(fixture.history()).unwrap();
        std::fs::create_dir(fixture.history()).unwrap();
        let index = fixture.history().join(RecordedFeature::Layout.file_name());
        std::fs::create_dir(&index).unwrap();
        assert!(fixture.store().load(RecordedFeature::Layout, 1).is_err());
        assert!(
            fixture
                .store()
                .record(RecordedFeature::Layout, value)
                .is_err()
        );
        assert!(fixture.store().clear(RecordedFeature::Layout).is_err());
        assert!(index.is_dir());
    }

    #[test]
    fn snapshot_isolates_corrupt_sources_instead_of_publishing_partial_source_data() {
        let fixture = Fixture::new();
        fixture.populate(Some("invalid CPU label"), true);
        let memory = entry(fixture.artifact("memory.hprof"));
        fixture
            .store()
            .record(RecordedFeature::Memory, memory.clone())
            .unwrap();
        let layout = fixture.write_history(RecordedFeature::Layout, &Value::Null);
        fixture.write_history(RecordedFeature::Trace, &Value::Null);
        let before = std::fs::read(layout).unwrap();
        let snapshot = fixture.snapshot(6);
        assert_eq!(snapshot.source_count, 4);
        assert_eq!(snapshot.unavailable_source_count, 3);
        assert_eq!(snapshot.items.len(), 1);
        assert_eq!(snapshot.items[0].source, HomeRecentSource::Memory);
        assert!(
            matches!(&snapshot.items[0].target, HomeRecentTarget::Memory(path) if path == &memory.path)
        );
        assert_eq!(
            std::fs::read(fixture.history().join(RecordedFeature::Layout.file_name())).unwrap(),
            before
        );
    }

    #[test]
    fn snapshot_round_robins_source_order_without_cross_feature_time_sorting() {
        let fixture = Fixture::new();
        fixture.populate(None, true);
        for (feature, extension) in [
            (RecordedFeature::Layout, "apinspect"),
            (RecordedFeature::Trace, "pftrace"),
            (RecordedFeature::Memory, "hprof"),
        ] {
            for index in 1..=2 {
                fixture
                    .store()
                    .record(
                        feature,
                        RecentPathEntry {
                            path: fixture
                                .artifact(&format!("{}-{index}.{extension}", feature.key())),
                            package_name: (feature != RecordedFeature::Layout)
                                .then(|| "com.example.app".into()),
                            // Deliberately decreasing capture time: MRU remains authoritative.
                            captured_at_unix_ms: (feature != RecordedFeature::Layout)
                                .then_some(10 - index),
                        },
                    )
                    .unwrap();
            }
        }
        let before = std::fs::read(fixture.database()).unwrap();
        let snapshot = fixture.snapshot(usize::MAX);
        assert_eq!(snapshot.unavailable_source_count, 0);
        assert_eq!(
            snapshot
                .items
                .iter()
                .map(|item| item.title.as_str())
                .collect::<Vec<_>>(),
            [
                "layout-2.apinspect",
                "CPU import · 0707070707070707",
                "trace-2.pftrace",
                "memory-2.hprof",
                "layout-1.apinspect",
                "trace-1.pftrace",
            ]
        );
        assert_eq!(
            snapshot
                .items
                .iter()
                .map(|item| item.source)
                .collect::<Vec<_>>(),
            [
                HomeRecentSource::Layout,
                HomeRecentSource::Cpu,
                HomeRecentSource::Trace,
                HomeRecentSource::Memory,
                HomeRecentSource::Layout,
                HomeRecentSource::Trace,
            ]
        );
        assert_eq!(snapshot.items[0].captured_at_unix_ms, None);
        assert_eq!(snapshot.items[1].captured_at_unix_ms, None);
        assert_eq!(snapshot.items[1].package_name, None);
        assert_eq!(snapshot.items[2].captured_at_unix_ms, Some(8));
        assert_eq!(
            snapshot.items[2].package_name.as_deref(),
            Some("com.example.app")
        );
        let HomeRecentTarget::Cpu(cpu) = &snapshot.items[1].target else {
            panic!("CPU binding lost")
        };
        let existing = load_recent(&fixture.database(), &fixture.snapshots(), 1).unwrap();
        assert_eq!(cpu.binding, existing[0].cpu.binding);
        assert_eq!(cpu.artifact_id, existing[0].cpu.artifact_id);
        assert_eq!(cpu.byte_length, existing[0].cpu.byte_length);
        assert_eq!(std::fs::read(fixture.database()).unwrap(), before);
        assert_eq!(fixture.snapshot(1).items.len(), 1);
    }

    #[test]
    fn feature_scoped_path_identity_does_not_hide_other_features() {
        let fixture = Fixture::new();
        let path = fixture.artifact("shared-artifact");
        for feature in [
            RecordedFeature::Layout,
            RecordedFeature::Trace,
            RecordedFeature::Memory,
        ] {
            fixture
                .store()
                .record(feature, entry(path.clone()))
                .unwrap();
        }
        let items = fixture.snapshot(6).items;
        assert_eq!(items.len(), 3);
        assert_eq!(
            items
                .iter()
                .map(|item| item.id.clone())
                .collect::<HashSet<_>>()
                .len(),
            3
        );
    }

    #[test]
    fn trace_merge_prefers_recorded_metadata_and_order_then_unique_captures() {
        let fixture = Fixture::new();
        let recorded = entry(fixture.artifact("recorded.pftrace"));
        fixture
            .store()
            .record(RecordedFeature::Trace, recorded.clone())
            .unwrap();
        let mut duplicate_capture = recorded.clone();
        duplicate_capture.captured_at_unix_ms = Some(100);
        let mut enriched_recorded = recorded.clone();
        enriched_recorded.captured_at_unix_ms = Some(100);
        let newer = RecentPathEntry {
            captured_at_unix_ms: Some(200),
            ..entry(fixture.artifact("newer.pftrace"))
        };
        let older = RecentPathEntry {
            captured_at_unix_ms: Some(50),
            ..entry(fixture.artifact("older.pftrace"))
        };
        let captures = vec![newer.clone(), duplicate_capture, older.clone()];
        let load_recorded = || fixture.store().load(RecordedFeature::Trace, 6).unwrap();
        assert_eq!(
            merge_trace_entries(load_recorded(), captures.clone(), 6).unwrap(),
            [enriched_recorded.clone(), newer.clone(), older]
        );
        assert_eq!(
            merge_trace_entries(load_recorded(), captures, 2).unwrap(),
            [enriched_recorded, newer]
        );
        let invalid_capture = RecentPathEntry {
            captured_at_unix_ms: Some(-1),
            ..entry(fixture.root.join("invalid.pftrace"))
        };
        assert!(merge_trace_entries(load_recorded(), vec![invalid_capture], 1).is_err());
    }

    #[test]
    fn corrupt_captured_trace_catalog_makes_trace_unavailable_even_with_full_recorded_page() {
        let fixture = Fixture::new();
        fixture
            .store()
            .record(
                RecordedFeature::Trace,
                entry(fixture.artifact("recorded.pftrace")),
            )
            .unwrap();
        fixture
            .store()
            .record(
                RecordedFeature::Memory,
                entry(fixture.artifact("memory.hprof")),
            )
            .unwrap();
        let captured_root = fixture.root.join("trace-analyzer/sessions");
        std::fs::create_dir_all(&captured_root).unwrap();
        let captured_index = captured_root.join("sessions.json");
        std::fs::write(&captured_index, b"corrupt captured index").unwrap();
        assert!(crate::trace::recent_home_entries(&fixture.root, 1).is_err());
        let recorded_index = fixture.history().join(RecordedFeature::Trace.file_name());
        let before = std::fs::read(&recorded_index).unwrap();
        let snapshot = fixture.snapshot(1);
        assert_eq!(snapshot.source_count, 4);
        assert_eq!(snapshot.unavailable_source_count, 1);
        assert_eq!(snapshot.items.len(), 1);
        assert_eq!(snapshot.items[0].source, HomeRecentSource::Memory);
        assert_eq!(std::fs::read(recorded_index).unwrap(), before);
        assert_eq!(
            std::fs::read(captured_index).unwrap(),
            b"corrupt captured index"
        );
    }

    #[test]
    fn full_200_record_budget_roundtrips_and_evicts_oldest_without_deleting_it() {
        let fixture = Fixture::new();
        let store = fixture.store();
        for index in 0..=MAX_RECORDED_RECENT_ITEMS {
            store
                .record(
                    RecordedFeature::Layout,
                    RecentPathEntry {
                        path: fixture.artifact(&format!("{index}.apinspect")),
                        package_name: Some(format!("com.example.app{index}")),
                        captured_at_unix_ms: Some(index as i64),
                    },
                )
                .unwrap();
        }
        let entries = store.load(RecordedFeature::Layout, usize::MAX).unwrap();
        assert_eq!(entries.len(), MAX_RECORDED_RECENT_ITEMS);
        assert_eq!(entries[0].path, fixture.root.join("200.apinspect"));
        assert_eq!(
            entries.last().unwrap().path,
            fixture.root.join("1.apinspect")
        );
        assert!(fixture.root.join("0.apinspect").is_file());
        assert_eq!(
            fixture.snapshot(usize::MAX).items.len(),
            MAX_HOME_RECENT_ITEMS
        );
        // Scan through 199 stale entries to the last retained artifact.
        for value in entries.iter().take(MAX_RECORDED_RECENT_ITEMS - 1) {
            std::fs::remove_file(&value.path).unwrap();
        }
        assert_eq!(
            store.load(RecordedFeature::Layout, 1).unwrap(),
            [entries.last().unwrap().clone()]
        );
    }

    #[test]
    fn concurrent_store_instances_serialize_record_and_clear_without_losing_other_entries() {
        let fixture = Fixture::new();
        std::thread::scope(|scope| {
            for index in 0..16 {
                let store = fixture.store();
                let value = entry(fixture.artifact(&format!("{index}.apinspect")));
                scope.spawn(move || {
                    store.record(RecordedFeature::Layout, value).unwrap();
                    store.clear(RecordedFeature::Memory).unwrap();
                });
            }
        });
        let entries = fixture
            .store()
            .load(RecordedFeature::Layout, usize::MAX)
            .unwrap();
        assert_eq!(entries.len(), 16);
        assert_eq!(
            entries
                .iter()
                .map(|entry| entry.path.clone())
                .collect::<HashSet<_>>()
                .len(),
            16
        );
        assert_eq!(
            std::fs::read_dir(fixture.history()).unwrap().count(),
            1,
            "no abandoned temporary files"
        );
    }

    #[test]
    fn cpu_scan_budget_is_preserved_and_missing_snapshots_do_not_mark_source_unavailable() {
        let fixture = Fixture::new();
        fixture.populate(None, true);
        let mut storage = Storage::open(fixture.database()).unwrap();
        for index in 0..SCAN_LIMIT {
            let session = ProfilerSession::new(
                ProfilerSessionId::new(format!("other-session-{index}")).unwrap(),
            );
            storage
                .create_session(&session, "Other feature", 1_000 + index as u64)
                .unwrap();
        }
        drop(storage);
        assert!(
            load_recent(&fixture.database(), &fixture.snapshots(), 1)
                .unwrap()
                .is_empty()
        );
        assert_eq!(fixture.snapshot(6).unavailable_source_count, 0);
        let missing = Fixture::new();
        missing.populate(None, false);
        assert!(missing.snapshot(6).items.is_empty());
        assert_eq!(missing.snapshot(6).unavailable_source_count, 0);
    }

    #[test]
    fn publication_event_and_cpu_conversion_keep_cpu_out_of_path_history() {
        let event = RecentArtifactOpened {
            feature: HomeFeature::Layout,
            path: PathBuf::from("/saved.apinspect"),
        };
        let copied = event.clone();
        assert_eq!(
            RecordedFeature::try_from(copied.feature).unwrap(),
            RecordedFeature::Layout
        );
        assert_eq!(copied.path, event.path);
        assert!(RecordedFeature::try_from(HomeFeature::Cpu).is_err());
    }

    #[cfg(unix)]
    #[test]
    fn final_symlinks_are_neither_loaded_recorded_nor_cleared_as_indexes() {
        use std::os::unix::fs::symlink;
        let fixture = Fixture::new();
        let saved = fixture.artifact("saved.apinspect");
        let link = fixture.root.join("link.apinspect");
        symlink(&saved, &link).unwrap();
        assert!(
            fixture
                .store()
                .record(RecordedFeature::Layout, entry(link.clone()))
                .is_err()
        );
        fixture.write_history(
            RecordedFeature::Layout,
            &raw_history(RecordedFeature::Layout, vec![raw_entry(&entry(link))]),
        );
        assert!(
            fixture
                .store()
                .load(RecordedFeature::Layout, 6)
                .unwrap()
                .is_empty()
        );
        let index = fixture.history().join(RecordedFeature::Layout.file_name());
        let bytes = std::fs::read(&index).unwrap();
        let target = fixture.root.join("external-index");
        std::fs::rename(&index, &target).unwrap();
        symlink(&target, &index).unwrap();
        assert!(fixture.store().load(RecordedFeature::Layout, 6).is_err());
        assert!(fixture.store().clear(RecordedFeature::Layout).is_err());
        assert!(
            fixture
                .store()
                .record(RecordedFeature::Layout, entry(saved.clone()))
                .is_err()
        );
        assert_eq!(std::fs::read(target).unwrap(), bytes);
        let root_link = fixture.root.join("linked-root");
        symlink(fixture.history(), &root_link).unwrap();
        let store = RecentPathStore::new(root_link);
        assert!(store.load(RecordedFeature::Memory, 1).is_err());
        assert!(store.clear(RecordedFeature::Memory).is_err());
        assert!(store.record(RecordedFeature::Memory, entry(saved)).is_err());
    }
}

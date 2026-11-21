//! Durable Trace Analyzer session and bookmark indexes, mirroring Compose
//! TraceSessionStore / TraceBookmarkStore.
//!
//! Both indexes are JSON; trace bytes are copied into a managed traces/
//! directory and never embedded in JSON. Reads are byte/structure bounded and
//! fail closed on a malformed top-level document, while unknown keys and
//! individual broken entries are tolerated for forward compatibility.

use crate::trace::model;
use aps_json::{Limits, Map, Value};
use aps_platform::atomic_write;
use std::{
    fs::{self, File, OpenOptions},
    io::{self, Read},
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex, MutexGuard,
        atomic::{AtomicU64, Ordering},
    },
};

const SESSIONS_FILE: &str = "sessions.json";
const BOOKMARKS_FILE: &str = "bookmarks.json";
const TRACES_DIR: &str = "traces";
const INDEX_VERSION: u64 = 1;
const MAX_SESSIONS: usize = 200;
const MAX_BOOKMARKS: usize = 500;
const MAX_INDEX_BYTES: u64 = 512 * 1024;
const MAX_STRING_BYTES: usize = 4096;
const MAX_ID_BYTES: usize = 128;
const MAX_TITLE_BYTES: usize = 512;
const MAX_LABEL_BYTES: usize = 256;
const MAX_SUMMARY_BYTES: usize = 1024;
const MAX_LIST_ITEMS: usize = 32;
const MAX_MANAGED_TRACE_BYTES: u64 = 8 * 1024 * 1024 * 1024;

static NEXT_TEMPORARY: AtomicU64 = AtomicU64::new(0);

fn index_limits() -> Limits {
    Limits {
        max_bytes: MAX_INDEX_BYTES,
        max_depth: 8,
        max_nodes: 20_000,
        max_string_bytes: MAX_STRING_BYTES,
        max_collection_items: 2_000,
    }
}

fn clipped(value: &str, max_bytes: usize) -> String {
    let mut end = value.len().min(max_bytes);
    while !value.is_char_boundary(end) {
        end -= 1;
    }
    value[..end].to_owned()
}

fn valid_identifier(value: &str, max_bytes: usize) -> bool {
    !value.is_empty() && value.len() <= max_bytes && !value.chars().any(char::is_control)
}

fn fnv1a64(bytes: &[u8]) -> u64 {
    let mut hash = 0xcbf2_9ce4_8422_2325u64;
    for byte in bytes {
        hash ^= u64::from(*byte);
        hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
    }
    hash
}

fn managed_trace_name(id: &str) -> String {
    format!("{:016x}.pftrace", fnv1a64(id.as_bytes()))
}

fn same_file(source: &Path, destination: &Path) -> bool {
    destination.exists() && source.canonicalize().ok() == destination.canonicalize().ok()
}

/// A locally captured trace stored in the managed index.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(in crate::trace) struct TraceArtifactRecord {
    pub(in crate::trace) id: String,
    pub(in crate::trace) display_name: String,
    pub(in crate::trace) completeness_label: String,
    pub(in crate::trace) limitations: Vec<String>,
    pub(in crate::trace) warnings: Vec<String>,
}

impl TraceArtifactRecord {
    /// viewer_available is evaluated at render time: it reflects whether the
    /// trace bytes are still on disk, not whether they were present at save.
    pub(in crate::trace) fn workspace_artifact(
        &self,
        trace_path: &Path,
    ) -> model::TraceWorkspaceArtifact {
        model::TraceWorkspaceArtifact {
            artifact_id: self.id.clone(),
            display_name: self.display_name.clone(),
            location_label: trace_path.display().to_string(),
            completeness_label: self.completeness_label.clone(),
            limitations: self.limitations.clone(),
            viewer_available: trace_path.is_file(),
        }
    }
}

/// One durable capture session. The bytes live at trace_path; this record
/// only carries bounded metadata.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(in crate::trace) struct SessionRecord {
    pub(in crate::trace) id: String,
    pub(in crate::trace) title: String,
    pub(in crate::trace) captured_at_label: String,
    pub(in crate::trace) duration_label: String,
    pub(in crate::trace) captured_unix_ms: u64,
    pub(in crate::trace) duration_nanos: u64,
    pub(in crate::trace) trace_path: PathBuf,
    pub(in crate::trace) artifact: Option<TraceArtifactRecord>,
    pub(in crate::trace) config_summary: Option<String>,
    pub(in crate::trace) device_model: Option<String>,
    pub(in crate::trace) android_sdk: Option<u32>,
}

impl SessionRecord {
    pub(in crate::trace) fn workspace_session(&self) -> model::TraceWorkspaceSession {
        model::TraceWorkspaceSession {
            session_id: self.id.clone(),
            title: self.title.clone(),
            captured_at_label: self.captured_at_label.clone(),
            duration_label: self.duration_label.clone(),
            artifact: self
                .artifact
                .as_ref()
                .map(|artifact| artifact.workspace_artifact(&self.trace_path)),
        }
    }
}

/// One pinned timeline position for a trace artifact.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(in crate::trace) struct BookmarkRecord {
    pub(in crate::trace) id: String,
    pub(in crate::trace) artifact_id: String,
    pub(in crate::trace) label: String,
    pub(in crate::trace) timestamp_nanos: i64,
    pub(in crate::trace) source_query_id: Option<String>,
    pub(in crate::trace) created_unix_ms: u64,
}

/// Durable session index: root/sessions.json plus managed root/traces files.
#[derive(Clone)]
pub(in crate::trace) struct SessionIndex {
    root: PathBuf,
    mutex: Arc<Mutex<()>>,
}

impl SessionIndex {
    pub(in crate::trace) fn new(root: PathBuf) -> Self {
        Self {
            root,
            mutex: Arc::new(Mutex::new(())),
        }
    }

    fn lock_guard(&self) -> Result<MutexGuard<'_, ()>, String> {
        self.mutex
            .lock()
            .map_err(|_| "Trace session index lock is poisoned".to_owned())
    }

    fn index_path(&self) -> PathBuf {
        self.root.join(SESSIONS_FILE)
    }

    fn traces_dir(&self) -> PathBuf {
        self.root.join(TRACES_DIR)
    }

    /// Newest first, bounded by limit and the durable entry cap.
    pub(in crate::trace) fn list(&self, limit: usize) -> Result<Vec<SessionRecord>, String> {
        let _guard = self.lock_guard()?;
        let mut records = self.read()?;
        records.sort_by(|left, right| right.captured_unix_ms.cmp(&left.captured_unix_ms));
        records.truncate(limit.min(MAX_SESSIONS));
        Ok(records)
    }

    /// Copies the trace into managed storage, de-duplicates by id, persists the
    /// index and returns the record pointing at the managed file.
    pub(in crate::trace) fn save(&self, record: SessionRecord) -> Result<SessionRecord, String> {
        let _guard = self.lock_guard()?;
        let mut record = self.bounded_record(record)?;
        let destination = self.traces_dir().join(managed_trace_name(&record.id));
        copy_trace(&record.trace_path, &destination, &self.traces_dir())?;
        record.trace_path = destination;
        let mut records = self.read()?;
        records.retain(|existing| existing.id != record.id);
        records.insert(0, record.clone());
        let dropped = if records.len() > MAX_SESSIONS {
            records.split_off(MAX_SESSIONS)
        } else {
            Vec::new()
        };
        self.write(&records)?;
        for record in dropped {
            self.remove_managed_trace(&record.trace_path)?;
        }
        Ok(record)
    }

    /// Removes the session and, when it is managed, the copied trace bytes.
    pub(in crate::trace) fn delete(&self, id: &str) -> Result<(), String> {
        let _guard = self.lock_guard()?;
        if !valid_identifier(id, MAX_ID_BYTES) {
            return Err("Trace session id is invalid".to_owned());
        }
        let mut records = self.read()?;
        let removed = records
            .iter()
            .position(|record| record.id == id)
            .map(|index| records.remove(index));
        self.write(&records)?;
        if let Some(record) = removed {
            self.remove_managed_trace(&record.trace_path)?;
        }
        Ok(())
    }

    fn bounded_record(&self, mut record: SessionRecord) -> Result<SessionRecord, String> {
        if !valid_identifier(&record.id, MAX_ID_BYTES) {
            return Err("Trace session id is invalid".to_owned());
        }
        if record.trace_path.as_os_str().is_empty() {
            return Err("Trace session has no trace file".to_owned());
        }
        record.title = clipped(&record.title, MAX_TITLE_BYTES);
        record.captured_at_label = clipped(&record.captured_at_label, MAX_LABEL_BYTES);
        record.duration_label = clipped(&record.duration_label, MAX_LABEL_BYTES);
        record.config_summary = record
            .config_summary
            .map(|summary| clipped(&summary, MAX_SUMMARY_BYTES));
        record.device_model = record
            .device_model
            .map(|model| clipped(&model, MAX_LABEL_BYTES));
        record.artifact = record.artifact.map(bounded_artifact);
        Ok(record)
    }

    fn remove_managed_trace(&self, trace_path: &Path) -> Result<(), String> {
        let traces_dir = self.traces_dir();
        let Some(parent) = trace_path.parent() else {
            return Ok(());
        };
        if parent.canonicalize().ok() != traces_dir.canonicalize().ok() {
            return Ok(());
        }
        match fs::remove_file(trace_path) {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == io::ErrorKind::NotFound => Ok(()),
            Err(_) => Err("Cannot remove the managed trace file".to_owned()),
        }
    }

    fn read(&self) -> Result<Vec<SessionRecord>, String> {
        let path = self.index_path();
        match fs::metadata(&path) {
            Ok(metadata) if metadata.is_file() => {}
            Ok(_) => return Err("Trace session index is not a regular file".to_owned()),
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(Vec::new()),
            Err(_) => return Err("Cannot inspect the trace session index".to_owned()),
        }
        let value = aps_json::read(&path, index_limits()).map_err(|_| {
            "Trace session index is malformed or exceeds the storage budget".to_owned()
        })?;
        let entries = match &value {
            Value::Array(entries) => entries,
            Value::Object(object) => match object.get("sessions") {
                Some(Value::Array(entries)) => entries,
                _ => return Err("Trace session index has no sessions array".to_owned()),
            },
            _ => return Err("Trace session index has an unexpected root".to_owned()),
        };
        let mut records: Vec<SessionRecord> = entries.iter().filter_map(parse_session).collect();
        records.truncate(MAX_SESSIONS);
        Ok(records)
    }

    fn write(&self, records: &[SessionRecord]) -> Result<(), String> {
        if records.len() > MAX_SESSIONS {
            return Err("Trace session index entry budget exceeded".to_owned());
        }
        let mut root = Map::new();
        root.insert("version".into(), Value::from(INDEX_VERSION));
        root.insert(
            "sessions".into(),
            Value::Array(records.iter().map(session_to_value).collect()),
        );
        let bytes = aps_json::to_vec(&Value::Object(root), index_limits())
            .map_err(|_| "Trace session index exceeds the storage budget".to_owned())?;
        atomic_write(&self.index_path(), &bytes)
            .map_err(|_| "Cannot write the trace session index".to_owned())
    }
}

/// Durable bookmark index: root/bookmarks.json.
#[derive(Clone)]
pub(in crate::trace) struct BookmarkIndex {
    root: PathBuf,
    mutex: Arc<Mutex<()>>,
}

impl BookmarkIndex {
    pub(in crate::trace) fn new(root: PathBuf) -> Self {
        Self {
            root,
            mutex: Arc::new(Mutex::new(())),
        }
    }

    fn lock_guard(&self) -> Result<MutexGuard<'_, ()>, String> {
        self.mutex
            .lock()
            .map_err(|_| "Trace bookmark index lock is poisoned".to_owned())
    }

    fn index_path(&self) -> PathBuf {
        self.root.join(BOOKMARKS_FILE)
    }

    /// Bookmarks for one artifact in timeline order, mirroring Compose.
    pub(in crate::trace) fn list(&self, artifact_id: &str) -> Result<Vec<BookmarkRecord>, String> {
        if !valid_identifier(artifact_id, MAX_ID_BYTES) {
            return Err("Trace artifact id is invalid".to_owned());
        }
        let _guard = self.lock_guard()?;
        let mut bookmarks = self.read()?;
        bookmarks.retain(|bookmark| bookmark.artifact_id == artifact_id);
        bookmarks.sort_by_key(|bookmark| bookmark.timestamp_nanos);
        Ok(bookmarks)
    }

    pub(in crate::trace) fn add(&self, bookmark: &BookmarkRecord) -> Result<(), String> {
        let bookmark = bounded_bookmark(bookmark)?;
        let _guard = self.lock_guard()?;
        let mut bookmarks = self.read()?;
        bookmarks.retain(|existing| existing.id != bookmark.id);
        bookmarks.push(bookmark);
        if bookmarks.len() > MAX_BOOKMARKS {
            bookmarks.sort_by(|left, right| right.created_unix_ms.cmp(&left.created_unix_ms));
            bookmarks.truncate(MAX_BOOKMARKS);
        }
        self.write(&bookmarks)
    }

    pub(in crate::trace) fn delete(&self, id: &str) -> Result<(), String> {
        if !valid_identifier(id, MAX_ID_BYTES) {
            return Err("Trace bookmark id is invalid".to_owned());
        }
        let _guard = self.lock_guard()?;
        let mut bookmarks = self.read()?;
        bookmarks.retain(|bookmark| bookmark.id != id);
        self.write(&bookmarks)
    }

    fn read(&self) -> Result<Vec<BookmarkRecord>, String> {
        let path = self.index_path();
        match fs::metadata(&path) {
            Ok(metadata) if metadata.is_file() => {}
            Ok(_) => return Err("Trace bookmark index is not a regular file".to_owned()),
            Err(error) if error.kind() == io::ErrorKind::NotFound => return Ok(Vec::new()),
            Err(_) => return Err("Cannot inspect the trace bookmark index".to_owned()),
        }
        let value = aps_json::read(&path, index_limits()).map_err(|_| {
            "Trace bookmark index is malformed or exceeds the storage budget".to_owned()
        })?;
        let entries = match &value {
            Value::Array(entries) => entries,
            Value::Object(object) => match object.get("bookmarks") {
                Some(Value::Array(entries)) => entries,
                _ => return Err("Trace bookmark index has no bookmarks array".to_owned()),
            },
            _ => return Err("Trace bookmark index has an unexpected root".to_owned()),
        };
        let mut bookmarks: Vec<BookmarkRecord> =
            entries.iter().filter_map(parse_bookmark).collect();
        bookmarks.truncate(MAX_BOOKMARKS);
        Ok(bookmarks)
    }

    fn write(&self, bookmarks: &[BookmarkRecord]) -> Result<(), String> {
        let bytes = aps_json::to_vec(
            &Value::Array(bookmarks.iter().map(bookmark_to_value).collect()),
            index_limits(),
        )
        .map_err(|_| "Trace bookmark index exceeds the storage budget".to_owned())?;
        atomic_write(&self.index_path(), &bytes)
            .map_err(|_| "Cannot write the trace bookmark index".to_owned())
    }
}

fn bounded_artifact(mut artifact: TraceArtifactRecord) -> TraceArtifactRecord {
    artifact.id = clipped(&artifact.id, MAX_ID_BYTES);
    artifact.display_name = clipped(&artifact.display_name, MAX_TITLE_BYTES);
    artifact.completeness_label = clipped(&artifact.completeness_label, MAX_LABEL_BYTES);
    artifact.limitations = bounded_list(artifact.limitations);
    artifact.warnings = bounded_list(artifact.warnings);
    artifact
}

fn bounded_list(values: Vec<String>) -> Vec<String> {
    values
        .into_iter()
        .take(MAX_LIST_ITEMS)
        .map(|value| clipped(&value, MAX_LABEL_BYTES))
        .collect()
}

fn bounded_bookmark(bookmark: &BookmarkRecord) -> Result<BookmarkRecord, String> {
    if !valid_identifier(&bookmark.id, MAX_ID_BYTES) {
        return Err("Trace bookmark id is invalid".to_owned());
    }
    if !valid_identifier(&bookmark.artifact_id, MAX_ID_BYTES) {
        return Err("Trace bookmark artifact id is invalid".to_owned());
    }
    let label = bookmark.label.trim();
    if label.is_empty() || label.len() > MAX_LABEL_BYTES {
        return Err("Trace bookmark label must be non-empty and bounded".to_owned());
    }
    if bookmark.timestamp_nanos < 0 {
        return Err("Trace bookmark timestamp must not be negative".to_owned());
    }
    Ok(BookmarkRecord {
        id: bookmark.id.clone(),
        artifact_id: bookmark.artifact_id.clone(),
        label: label.to_owned(),
        timestamp_nanos: bookmark.timestamp_nanos,
        source_query_id: bookmark
            .source_query_id
            .as_deref()
            .map(|value| clipped(value, MAX_ID_BYTES)),
        created_unix_ms: bookmark.created_unix_ms,
    })
}

fn copy_trace(source: &Path, destination: &Path, traces_dir: &Path) -> Result<(), String> {
    let metadata = fs::metadata(source).map_err(|_| "Trace file does not exist".to_owned())?;
    if !metadata.is_file() {
        return Err("Trace source is not a regular file".to_owned());
    }
    if metadata.len() > MAX_MANAGED_TRACE_BYTES {
        return Err("Trace exceeds the managed trace byte budget".to_owned());
    }
    if same_file(source, destination) {
        return Ok(());
    }
    fs::create_dir_all(traces_dir)
        .map_err(|_| "Cannot create the managed trace directory".to_owned())?;
    let temporary = traces_dir.join(format!(
        "trace-{}-{}.pftrace.tmp",
        std::process::id(),
        NEXT_TEMPORARY.fetch_add(1, Ordering::Relaxed)
    ));
    let reader = File::open(source).map_err(|_| "Cannot open the trace file".to_owned())?;
    let mut writer = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&temporary)
        .map_err(|_| "Cannot create a managed trace file".to_owned())?;
    let copied = io::copy(
        &mut reader.take(metadata.len().saturating_add(1)),
        &mut writer,
    )
    .and_then(|copied| writer.sync_all().map(|()| copied));
    drop(writer);
    let copied = match copied {
        Ok(copied) => copied,
        Err(_) => {
            let _ = fs::remove_file(&temporary);
            return Err("Cannot copy the trace into managed storage".to_owned());
        }
    };
    if copied != metadata.len() {
        let _ = fs::remove_file(&temporary);
        return Err("Trace changed while it was being saved".to_owned());
    }
    if fs::rename(&temporary, destination).is_err() {
        let _ = fs::remove_file(destination);
        if fs::rename(&temporary, destination).is_err() {
            let _ = fs::remove_file(&temporary);
            return Err("Cannot store the managed trace file".to_owned());
        }
    }
    Ok(())
}

fn session_to_value(record: &SessionRecord) -> Value {
    let mut object = Map::new();
    object.insert("id".into(), Value::String(record.id.clone()));
    object.insert("title".into(), Value::String(record.title.clone()));
    object.insert(
        "capturedAtLabel".into(),
        Value::String(record.captured_at_label.clone()),
    );
    object.insert(
        "durationLabel".into(),
        Value::String(record.duration_label.clone()),
    );
    object.insert(
        "capturedUnixMs".into(),
        Value::from(record.captured_unix_ms),
    );
    object.insert("durationNanos".into(), Value::from(record.duration_nanos));
    object.insert(
        "traceFile".into(),
        Value::String(record.trace_path.display().to_string()),
    );
    object.insert(
        "configSummary".into(),
        record
            .config_summary
            .as_ref()
            .map_or(Value::Null, |value| Value::String(value.clone())),
    );
    object.insert(
        "deviceModel".into(),
        record
            .device_model
            .as_ref()
            .map_or(Value::Null, |model| Value::String(model.clone())),
    );
    object.insert(
        "androidSdk".into(),
        record
            .android_sdk
            .map_or(Value::Null, |sdk| Value::from(u64::from(sdk))),
    );
    object.insert(
        "artifact".into(),
        record
            .artifact
            .as_ref()
            .map_or(Value::Null, artifact_to_value),
    );
    Value::Object(object)
}

fn artifact_to_value(artifact: &TraceArtifactRecord) -> Value {
    let mut object = Map::new();
    object.insert("id".into(), Value::String(artifact.id.clone()));
    object.insert(
        "displayName".into(),
        Value::String(artifact.display_name.clone()),
    );
    object.insert(
        "completenessLabel".into(),
        Value::String(artifact.completeness_label.clone()),
    );
    object.insert(
        "limitations".into(),
        Value::Array(
            artifact
                .limitations
                .iter()
                .map(|value| Value::String(value.clone()))
                .collect(),
        ),
    );
    object.insert(
        "warnings".into(),
        Value::Array(
            artifact
                .warnings
                .iter()
                .map(|value| Value::String(value.clone()))
                .collect(),
        ),
    );
    Value::Object(object)
}

fn bookmark_to_value(bookmark: &BookmarkRecord) -> Value {
    let mut object = Map::new();
    object.insert("id".into(), Value::String(bookmark.id.clone()));
    object.insert(
        "artifactId".into(),
        Value::String(bookmark.artifact_id.clone()),
    );
    object.insert("label".into(), Value::String(bookmark.label.clone()));
    object.insert(
        "timestampNanos".into(),
        Value::from(bookmark.timestamp_nanos),
    );
    object.insert(
        "sourceQueryId".into(),
        bookmark
            .source_query_id
            .as_ref()
            .map_or(Value::Null, |value| Value::String(value.clone())),
    );
    object.insert(
        "createdUnixMs".into(),
        Value::from(bookmark.created_unix_ms),
    );
    Value::Object(object)
}

fn object_string(object: &Map<String, Value>, key: &str, max_bytes: usize) -> String {
    object
        .get(key)
        .and_then(Value::as_str)
        .map(|value| clipped(value, max_bytes))
        .unwrap_or_default()
}

fn optional_string(object: &Map<String, Value>, key: &str, max_bytes: usize) -> Option<String> {
    let value = object.get(key)?.as_str()?;
    let value = clipped(value, max_bytes);
    (!value.trim().is_empty()).then_some(value)
}

fn string_list(value: Option<&Value>) -> Vec<String> {
    value
        .and_then(Value::as_array)
        .map(|values| {
            values
                .iter()
                .take(MAX_LIST_ITEMS)
                .filter_map(Value::as_str)
                .map(|value| clipped(value, MAX_LABEL_BYTES))
                .collect()
        })
        .unwrap_or_default()
}

fn parse_session(value: &Value) -> Option<SessionRecord> {
    let object = value.as_object()?;
    let id = object.get("id")?.as_str()?;
    if !valid_identifier(id, MAX_ID_BYTES) {
        return None;
    }
    let trace_path = object.get("traceFile")?.as_str()?;
    if trace_path.is_empty() {
        return None;
    }
    Some(SessionRecord {
        id: id.to_owned(),
        title: object_string(object, "title", MAX_TITLE_BYTES),
        captured_at_label: object_string(object, "capturedAtLabel", MAX_LABEL_BYTES),
        duration_label: object_string(object, "durationLabel", MAX_LABEL_BYTES),
        captured_unix_ms: object
            .get("capturedUnixMs")
            .and_then(Value::as_u64)
            .unwrap_or(0),
        duration_nanos: object
            .get("durationNanos")
            .and_then(Value::as_u64)
            .unwrap_or(0),
        trace_path: PathBuf::from(trace_path),
        artifact: object.get("artifact").and_then(parse_artifact),
        config_summary: optional_string(object, "configSummary", MAX_SUMMARY_BYTES),
        device_model: optional_string(object, "deviceModel", MAX_LABEL_BYTES),
        android_sdk: object
            .get("androidSdk")
            .and_then(Value::as_u64)
            .map(|sdk| sdk.min(u64::from(u32::MAX)) as u32),
    })
}

fn parse_artifact(value: &Value) -> Option<TraceArtifactRecord> {
    let object = value.as_object()?;
    let id = object.get("id")?.as_str()?;
    if !valid_identifier(id, MAX_ID_BYTES) {
        return None;
    }
    Some(TraceArtifactRecord {
        id: id.to_owned(),
        display_name: object_string(object, "displayName", MAX_TITLE_BYTES),
        completeness_label: object_string(object, "completenessLabel", MAX_LABEL_BYTES),
        limitations: string_list(object.get("limitations")),
        warnings: string_list(object.get("warnings")),
    })
}

fn parse_bookmark(value: &Value) -> Option<BookmarkRecord> {
    let object = value.as_object()?;
    let id = object.get("id")?.as_str()?;
    let artifact_id = object.get("artifactId")?.as_str()?;
    if !valid_identifier(id, MAX_ID_BYTES) || !valid_identifier(artifact_id, MAX_ID_BYTES) {
        return None;
    }
    let label = object.get("label")?.as_str()?;
    if label.trim().is_empty() {
        return None;
    }
    let timestamp_nanos = object.get("timestampNanos")?.as_i64()?;
    if timestamp_nanos < 0 {
        return None;
    }
    Some(BookmarkRecord {
        id: id.to_owned(),
        artifact_id: artifact_id.to_owned(),
        label: clipped(label, MAX_LABEL_BYTES),
        timestamp_nanos,
        source_query_id: object
            .get("sourceQueryId")
            .and_then(Value::as_str)
            .map(|value| clipped(value, MAX_ID_BYTES)),
        created_unix_ms: object
            .get("createdUnixMs")
            .and_then(Value::as_u64)
            .unwrap_or(0),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};

    static NEXT_DIRECTORY: AtomicUsize = AtomicUsize::new(0);

    struct TestDirectory(PathBuf);
    impl TestDirectory {
        fn new(name: &str) -> Self {
            let path = std::env::temp_dir().join(format!(
                "aps-trace-{name}-{}-{}",
                std::process::id(),
                NEXT_DIRECTORY.fetch_add(1, Ordering::Relaxed)
            ));
            let _ = fs::remove_dir_all(&path);
            fs::create_dir_all(&path).unwrap();
            Self(path)
        }
        fn path(&self) -> &Path {
            &self.0
        }
    }
    impl Drop for TestDirectory {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    fn write_file(path: &Path, bytes: &[u8]) -> PathBuf {
        fs::write(path, bytes).unwrap();
        path.to_path_buf()
    }

    fn record(id: &str, trace: PathBuf, captured_unix_ms: u64) -> SessionRecord {
        SessionRecord {
            id: id.into(),
            title: format!("Capture {id}"),
            captured_at_label: "2026-11-07T12:00:00Z".into(),
            duration_label: "10.000 s".into(),
            captured_unix_ms,
            duration_nanos: 10_000_000_000,
            trace_path: trace,
            artifact: Some(TraceArtifactRecord {
                id: format!("artifact-{id}"),
                display_name: "capture.pftrace".into(),
                completeness_label: "Complete".into(),
                limitations: vec!["duration limited".into()],
                warnings: vec!["one warning".into()],
            }),
            config_summary: Some("System overview, 10 s".into()),
            device_model: Some("Pixel 9".into()),
            android_sdk: Some(35),
        }
    }

    fn directory_entry_count(path: &Path) -> usize {
        fs::read_dir(path).map(|reader| reader.count()).unwrap_or(0)
    }

    #[test]
    fn home_reader_uses_saved_captures_and_scans_past_missing_artifacts() {
        let directory = TestDirectory::new("home-history");
        let source = write_file(&directory.path().join("original.pftrace"), b"fixture trace");
        let index = SessionIndex::new(directory.path().join("trace-analyzer/sessions"));
        let older = index.save(record("older", source.clone(), 10)).unwrap();
        let missing = index.save(record("missing", source, 20)).unwrap();
        fs::remove_file(&missing.trace_path).unwrap();
        let before = fs::read(index.index_path()).unwrap();
        let entries = crate::trace::recent_home_entries(directory.path(), 1).unwrap();
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].path, older.trace_path);
        assert_eq!(entries[0].captured_at_unix_ms, Some(10));
        assert_eq!(fs::read(index.index_path()).unwrap(), before);
        assert_eq!(fs::read(&entries[0].path).unwrap(), b"fixture trace");
    }

    #[test]
    fn home_reader_keeps_corrupt_and_missing_capture_indexes_read_only() {
        let directory = TestDirectory::new("home-corruption");
        assert!(
            crate::trace::recent_home_entries(directory.path(), 6)
                .unwrap()
                .is_empty()
        );
        assert_eq!(directory_entry_count(directory.path()), 0);
        let root = directory.path().join("trace-analyzer/sessions");
        fs::create_dir_all(&root).unwrap();
        let index = root.join(SESSIONS_FILE);
        fs::write(&index, b"corrupt-index").unwrap();
        assert!(crate::trace::recent_home_entries(directory.path(), 6).is_err());
        assert_eq!(fs::read(index).unwrap(), b"corrupt-index");
    }

    #[test]
    fn home_reader_does_not_turn_unknown_capture_time_into_the_unix_epoch() {
        let directory = TestDirectory::new("home-unknown-time");
        let root = directory.path().join("trace-analyzer/sessions");
        fs::create_dir_all(&root).unwrap();
        let path = write_file(&directory.path().join("actual.pftrace"), b"trace");
        for timestamp in [
            Value::Null,
            Value::from(-1),
            Value::from("invalid"),
            Value::from(0),
        ] {
            let mut record = raw_session("unknown", 0);
            let object = record.as_object_mut().unwrap();
            object.insert("traceFile".into(), path.display().to_string().into());
            object.insert("capturedUnixMs".into(), timestamp);
            write_raw_index(&root, vec![record]);
            let entries = crate::trace::recent_home_entries(directory.path(), 6).unwrap();
            assert_eq!(entries.len(), 1);
            assert_eq!(entries[0].captured_at_unix_ms, None);
        }
    }

    fn write_raw_index(root: &Path, sessions: Vec<Value>) {
        let mut root_object = Map::new();
        root_object.insert("version".into(), Value::from(INDEX_VERSION));
        root_object.insert("sessions".into(), Value::Array(sessions));
        let bytes = aps_json::to_vec(&Value::Object(root_object), index_limits()).unwrap();
        fs::write(root.join(SESSIONS_FILE), bytes).unwrap();
    }

    fn raw_session(id: &str, captured_unix_ms: u64) -> Value {
        let mut object = Map::new();
        object.insert("id".into(), Value::String(id.into()));
        object.insert(
            "traceFile".into(),
            Value::String(format!("/tmp/{id}.pftrace")),
        );
        object.insert("capturedUnixMs".into(), Value::from(captured_unix_ms));
        Value::Object(object)
    }

    #[test]
    fn round_trips_metadata_and_managed_trace_bytes() {
        let directory = TestDirectory::new("session-roundtrip");
        let source = write_file(
            &directory.path().join("capture-with{json}.pftrace"),
            b"trace-bytes-marker",
        );
        let mut stored = record("session-1", source.clone(), 1_000);
        stored.title = "Pixel {test} \"quoted\"".into();
        stored.config_summary = Some("buffers { size_kb: 4096 }".into());
        let index = SessionIndex::new(directory.path().to_path_buf());

        let saved = index.save(stored.clone()).unwrap();

        assert_eq!(
            saved.trace_path.parent().unwrap().canonicalize().unwrap(),
            directory.path().join(TRACES_DIR).canonicalize().unwrap()
        );
        assert!(saved.workspace_session().artifact.unwrap().viewer_available);
        let listed = index.list(10).unwrap();
        assert_eq!(listed.len(), 1);
        assert_eq!(listed[0].id, "session-1");
        assert_eq!(listed[0].title, stored.title);
        assert_eq!(listed[0].config_summary, stored.config_summary);
        assert_eq!(listed[0].artifact, stored.artifact);
        assert_eq!(
            fs::read(&listed[0].trace_path).unwrap(),
            b"trace-bytes-marker".to_vec()
        );
        let index_text = fs::read_to_string(directory.path().join(SESSIONS_FILE)).unwrap();
        assert!(!index_text.contains("trace-bytes-marker"));
        assert!(source.exists());

        fs::remove_file(&saved.trace_path).unwrap();
        assert!(!saved.workspace_session().artifact.unwrap().viewer_available);

        index.delete("session-1").unwrap();
        assert!(index.list(10).unwrap().is_empty());
        assert!(!saved.trace_path.exists());
    }

    #[test]
    fn lists_newest_first_and_respects_the_limit() {
        let directory = TestDirectory::new("session-order");
        let index = SessionIndex::new(directory.path().to_path_buf());
        for (id, captured) in [("old", 100), ("new", 300), ("middle", 200)] {
            let trace = write_file(&directory.path().join(format!("{id}.pftrace")), b"x");
            index.save(record(id, trace, captured)).unwrap();
        }
        let ids: Vec<String> = index
            .list(10)
            .unwrap()
            .into_iter()
            .map(|record| record.id)
            .collect();
        assert_eq!(ids, ["new", "middle", "old"]);
        assert_eq!(index.list(2).unwrap().len(), 2);
        assert_eq!(index.list(0).unwrap().len(), 0);
    }

    #[test]
    fn resaving_the_same_id_keeps_one_entry_and_one_managed_trace() {
        let directory = TestDirectory::new("session-dedupe");
        let index = SessionIndex::new(directory.path().to_path_buf());
        let first = write_file(&directory.path().join("first.pftrace"), b"first");
        let second = write_file(&directory.path().join("second.pftrace"), b"second");
        index.save(record("session-1", first, 100)).unwrap();
        let saved = index.save(record("session-1", second, 100)).unwrap();

        assert_eq!(index.list(10).unwrap().len(), 1);
        assert_eq!(directory_entry_count(&directory.path().join(TRACES_DIR)), 1);
        assert_eq!(fs::read(&saved.trace_path).unwrap(), b"second".to_vec());
    }

    #[test]
    fn malformed_or_oversized_indexes_fail_closed_but_bad_entries_are_skipped() {
        let directory = TestDirectory::new("session-malformed");
        let index = SessionIndex::new(directory.path().to_path_buf());
        let index_file = directory.path().join(SESSIONS_FILE);

        fs::write(&index_file, b"{not json").unwrap();
        assert!(index.list(10).is_err());
        assert!(index.delete("session-1").is_err());

        fs::write(&index_file, vec![b' '; MAX_INDEX_BYTES as usize + 1]).unwrap();
        assert!(index.list(10).is_err());

        fs::write(&index_file, b"{\"sessions\": \"nope\"}").unwrap();
        assert!(index.list(10).is_err());

        let bytes = aps_json::to_vec(
            &Value::Array(vec![raw_session("valid", 1), Value::String("bad".into())]),
            index_limits(),
        )
        .unwrap();
        fs::write(&index_file, bytes).unwrap();
        let records = index.list(10).unwrap();
        assert_eq!(records.len(), 1);
        assert_eq!(records[0].id, "valid");
    }

    #[test]
    fn entry_count_is_bounded() {
        let directory = TestDirectory::new("session-bounded");
        let index = SessionIndex::new(directory.path().to_path_buf());
        let entries: Vec<Value> = (0..MAX_SESSIONS + 5)
            .map(|index| raw_session(&format!("session-{index}"), index as u64))
            .collect();
        write_raw_index(directory.path(), entries);

        assert_eq!(index.list(usize::MAX).unwrap().len(), MAX_SESSIONS);
        assert_eq!(index.list(3).unwrap().len(), 3);
    }

    #[test]
    fn bookmarks_persist_per_artifact_in_timestamp_order() {
        let directory = TestDirectory::new("bookmarks-order");
        let index = BookmarkIndex::new(directory.path().to_path_buf());
        for (id, artifact, timestamp) in [
            ("b2", "artifact-1", 2_000),
            ("b1", "artifact-1", 1_000),
            ("other", "artifact-2", 500),
        ] {
            index
                .add(&BookmarkRecord {
                    id: id.into(),
                    artifact_id: artifact.into(),
                    label: format!("Bookmark {id}"),
                    timestamp_nanos: timestamp,
                    source_query_id: (id == "b1").then(|| "frame_jank".to_owned()),
                    created_unix_ms: 10,
                })
                .unwrap();
        }
        let reloaded = BookmarkIndex::new(directory.path().to_path_buf())
            .list("artifact-1")
            .unwrap();
        assert_eq!(
            reloaded.iter().map(|b| b.id.as_str()).collect::<Vec<_>>(),
            ["b1", "b2"]
        );
        assert_eq!(reloaded[0].source_query_id.as_deref(), Some("frame_jank"));

        index.delete("b1").unwrap();
        assert_eq!(
            index
                .list("artifact-1")
                .unwrap()
                .iter()
                .map(|b| b.id.as_str())
                .collect::<Vec<_>>(),
            ["b2"]
        );
        index.delete("missing").unwrap();
    }

    #[test]
    fn invalid_bookmarks_and_corrupt_entries_fail_without_losing_valid_entries() {
        let directory = TestDirectory::new("bookmarks-invalid");
        let index = BookmarkIndex::new(directory.path().to_path_buf());
        let bookmark = BookmarkRecord {
            id: "blank".into(),
            artifact_id: "artifact-1".into(),
            label: " ".into(),
            timestamp_nanos: 1,
            source_query_id: None,
            created_unix_ms: 1,
        };
        assert!(index.add(&bookmark).is_err());
        assert!(
            index
                .add(&BookmarkRecord {
                    timestamp_nanos: -1,
                    label: "negative".into(),
                    ..bookmark.clone()
                })
                .is_err()
        );

        fs::write(
            directory.path().join(BOOKMARKS_FILE),
            br#"[{"id":"ok","artifactId":"a","label":"Frame","timestampNanos":5,"createdUnixMs":10},{"id":"bad"}]"#,
        )
        .unwrap();
        let listed = index.list("a").unwrap();
        assert_eq!(listed.len(), 1);
        assert_eq!(listed[0].id, "ok");

        fs::write(directory.path().join(BOOKMARKS_FILE), b"not json").unwrap();
        assert!(index.list("a").is_err());
    }
}

//! Append-only catalogue of exported benchmark reports.
//!
//! The index answers "what did I generate, where is it, can I open it again"
//! without turning different report bodies into one shared model. Deleting an
//! entry never deletes the user's file: this is a catalogue, not an archive.
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

use serde_json::{Map, Value};

use crate::util::{absolute_normalize, now_rfc3339, now_unix_millis};

const DEFAULT_MAX_ENTRIES: usize = 50;
const EPOCH: &str = "1970-01-01T00:00:00Z";

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BenchmarkReportEntry {
    pub id: String,
    pub title: String,
    pub format: String,
    /// RFC3339 UTC, so `load` can sort newest first without a chrono dependency.
    pub created_at: String,
    pub path: String,
    pub size_bytes: u64,
}

pub struct BenchmarkReportIndex {
    file: PathBuf,
    max_entries: usize,
}

impl BenchmarkReportIndex {
    pub fn new(file: impl Into<PathBuf>) -> Self {
        Self {
            file: file.into(),
            max_entries: DEFAULT_MAX_ENTRIES,
        }
    }

    pub fn with_max_entries(file: impl Into<PathBuf>, max_entries: usize) -> Self {
        Self {
            file: file.into(),
            max_entries,
        }
    }

    /// Entries sorted newest first. A malformed or absent index reads as empty.
    pub fn load(&self) -> Vec<BenchmarkReportEntry> {
        let Ok(bytes) = std::fs::read(&self.file) else {
            return Vec::new();
        };
        let Ok(root) = aps_json::parse_slice(&bytes, aps_json::Limits::default()) else {
            return Vec::new();
        };
        let Some(values) = root.as_array() else {
            return Vec::new();
        };
        let mut entries: Vec<BenchmarkReportEntry> =
            values.iter().filter_map(entry_from_value).collect();
        entries.sort_by(|left, right| right.created_at.cmp(&left.created_at));
        entries
    }

    /// Records an artifact that already exists on disk; the size comes from the file itself.
    pub fn record(
        &self,
        path: &Path,
        title: &str,
        format: &str,
    ) -> Result<Vec<BenchmarkReportEntry>, String> {
        static COUNTER: AtomicU64 = AtomicU64::new(0);
        let absolute = absolute_normalize(path);
        let size_bytes = std::fs::metadata(&absolute)
            .map(|meta| meta.len())
            .unwrap_or(0);
        let entry = BenchmarkReportEntry {
            id: format!(
                "{}-{}",
                now_unix_millis(),
                COUNTER.fetch_add(1, Ordering::Relaxed)
            ),
            title: title.to_string(),
            format: format.to_string(),
            created_at: now_rfc3339(),
            path: absolute.to_string_lossy().into_owned(),
            size_bytes,
        };
        let mut entries = vec![entry];
        let new_path = entries[0].path.clone();
        entries.extend(
            self.load()
                .into_iter()
                .filter(|existing| existing.path != new_path),
        );
        entries.truncate(self.max_entries);
        if let Some(parent) = self.file.parent()
            && !parent.as_os_str().is_empty()
        {
            std::fs::create_dir_all(parent).map_err(|_| {
                format!("Cannot create report index directory: {}", parent.display())
            })?;
        }
        let array = Value::Array(entries.iter().map(entry_to_value).collect());
        let text = serde_json::to_string_pretty(&array)
            .map_err(|error| format!("Cannot encode report index: {error}"))?;
        std::fs::write(&self.file, text)
            .map_err(|error| format!("Cannot write report index: {error}"))?;
        Ok(entries)
    }
}

fn entry_from_value(value: &Value) -> Option<BenchmarkReportEntry> {
    let object = value.as_object()?;
    let path = object_text(object, "path")?;
    Some(BenchmarkReportEntry {
        id: object_text(object, "id").unwrap_or_else(|| path.clone()),
        title: object_text(object, "title").unwrap_or_else(|| path.clone()),
        format: object_text(object, "format").unwrap_or_else(|| "JSON".to_string()),
        created_at: object_text(object, "createdAt").unwrap_or_else(|| EPOCH.to_string()),
        path,
        size_bytes: object_u64(object, "sizeBytes").unwrap_or(0),
    })
}

fn entry_to_value(entry: &BenchmarkReportEntry) -> Value {
    let mut object = Map::new();
    object.insert("id".to_string(), Value::String(entry.id.clone()));
    object.insert("title".to_string(), Value::String(entry.title.clone()));
    object.insert("format".to_string(), Value::String(entry.format.clone()));
    object.insert(
        "createdAt".to_string(),
        Value::String(entry.created_at.clone()),
    );
    object.insert("path".to_string(), Value::String(entry.path.clone()));
    object.insert("sizeBytes".to_string(), Value::from(entry.size_bytes));
    Value::Object(object)
}

fn object_text(object: &Map<String, Value>, name: &str) -> Option<String> {
    match object.get(name)? {
        Value::String(text) => Some(text.clone()),
        Value::Number(number) => Some(number.to_string()),
        Value::Bool(flag) => Some(flag.to_string()),
        _ => None,
    }
}

fn object_u64(object: &Map<String, Value>, name: &str) -> Option<u64> {
    match object.get(name)? {
        Value::Number(number) => number.as_u64(),
        Value::String(text) => text.parse().ok(),
        _ => None,
    }
}

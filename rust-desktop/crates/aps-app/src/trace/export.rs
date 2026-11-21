//! Trace export: raw trace copy and bounded session-package zip.
//!
//! Compose parity with TraceExporter.exportSessionPackage / exportRawTraceFile.
//! The package has exactly three entries: session.json, trace.pftrace and
//! metadata.properties. Trace bytes are streamed, never buffered in JSON;
//! every string written to the package is clipped and the JSON has a hard byte
//! budget. A partial archive is written to a sibling temporary file and only
//! renamed into place after a successful finish.

use crate::evidence::clipped;
use crate::trace::sessions::{SessionRecord, TraceArtifactRecord};
use aps_json::{Limits, Map, Value};
use std::{
    fs::{self, File, OpenOptions},
    io::{self, Read, Write},
    path::{Path, PathBuf},
    sync::atomic::{AtomicU64, Ordering},
};
use zip::{CompressionMethod, ZipWriter, write::SimpleFileOptions};

const MAX_TRACE_BYTES: u64 = 8 * 1024 * 1024 * 1024;
const MAX_TEXT_BYTES: usize = 512;
const MAX_LIST_ITEMS: usize = 32;
const MAX_SESSION_JSON_BYTES: u64 = 16 * 1024;
const MAX_PROPERTIES_BYTES: usize = 16 * 1024;
const SESSION_JSON_ENTRY: &str = "session.json";
const TRACE_ENTRY: &str = "trace.pftrace";
const METADATA_ENTRY: &str = "metadata.properties";

static NEXT_TEMPORARY: AtomicU64 = AtomicU64::new(0);

/// Copies one trace byte-for-byte after regular-file and byte-budget checks.
/// Refuses to export a file onto itself and never leaves a partial destination.
pub(in crate::trace) fn export_raw_trace(source: &Path, destination: &Path) -> Result<(), String> {
    let metadata = trace_metadata(source)?;
    if same_path(source, destination) {
        return Err("Source and destination are the same trace file".to_owned());
    }
    let temporary = temporary_sibling(destination)?;
    let reader = File::open(source).map_err(|_| "Cannot open the trace file".to_owned())?;
    let mut writer = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&temporary)
        .map_err(|_| "Cannot create the export destination".to_owned())?;
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
            return Err("Cannot copy the trace to the export destination".to_owned());
        }
    };
    if copied != metadata.len() {
        let _ = fs::remove_file(&temporary);
        return Err("Trace changed while it was exported".to_owned());
    }
    if let Err(_) = fs::rename(&temporary, destination) {
        let _ = fs::remove_file(destination);
        if fs::rename(&temporary, destination).is_err() {
            let _ = fs::remove_file(&temporary);
            return Err("Cannot finish the trace export".to_owned());
        }
    }
    Ok(())
}

/// Writes the Compose session package: session.json, trace.pftrace and
/// metadata.properties. No capture-artifact.json is added here.
pub(in crate::trace) fn export_session_package(
    record: &SessionRecord,
    destination: &Path,
) -> Result<(), String> {
    let metadata = trace_metadata(&record.trace_path)?;
    if same_path(&record.trace_path, destination) {
        return Err("The session package cannot overwrite its own trace file".to_owned());
    }
    let session_json = session_json(record, metadata.len())?;
    let properties = metadata_properties(record, metadata.len())?;
    let temporary = temporary_sibling(destination)?;
    let result = write_package(&record.trace_path, &temporary, &session_json, &properties);
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
        return result;
    }
    if let Err(_) = fs::rename(&temporary, destination) {
        let _ = fs::remove_file(destination);
        if fs::rename(&temporary, destination).is_err() {
            let _ = fs::remove_file(&temporary);
            return Err("Cannot finish the session package".to_owned());
        }
    }
    Ok(())
}

fn write_package(
    trace: &Path,
    output: &Path,
    session_json: &[u8],
    properties: &str,
) -> Result<(), String> {
    let file = File::create(output).map_err(|_| "Cannot create the session package".to_owned())?;
    let mut zip = ZipWriter::new(file);
    let text_options = SimpleFileOptions::default().compression_method(CompressionMethod::Deflated);
    let trace_options = SimpleFileOptions::default().compression_method(CompressionMethod::Stored);
    zip.start_file(SESSION_JSON_ENTRY, text_options)
        .map_err(|_| "Cannot write the session package".to_owned())?;
    zip.write_all(session_json)
        .map_err(|_| "Cannot write the session package".to_owned())?;
    zip.start_file(TRACE_ENTRY, trace_options)
        .map_err(|_| "Cannot write the session package".to_owned())?;
    let mut trace_file = File::open(trace).map_err(|_| "Cannot open the trace file".to_owned())?;
    io::copy(&mut trace_file, &mut zip)
        .map_err(|_| "Cannot write the session package trace".to_owned())?;
    zip.start_file(METADATA_ENTRY, text_options)
        .map_err(|_| "Cannot write the session package".to_owned())?;
    zip.write_all(properties.as_bytes())
        .map_err(|_| "Cannot write the session package".to_owned())?;
    let file = zip
        .finish()
        .map_err(|_| "Cannot finish the session package".to_owned())?;
    file.sync_all()
        .map_err(|_| "Cannot flush the session package".to_owned())?;
    Ok(())
}

/// Compose uses the source file name as the raw-export default.
pub(in crate::trace) fn suggested_raw_name(source: &Path) -> String {
    source
        .file_name()
        .and_then(|name| name.to_str())
        .map(|name| clipped(name, MAX_TEXT_BYTES))
        .filter(|name| !name.trim().is_empty())
        .unwrap_or_else(|| "trace.pftrace".to_owned())
}

/// Compose save-dialog default for the session package ("perfetto-session.zip").
pub(in crate::trace) fn suggested_session_name(_record: &SessionRecord) -> String {
    "perfetto-session.zip".to_owned()
}

fn trace_metadata(trace: &Path) -> Result<fs::Metadata, String> {
    let metadata = fs::metadata(trace).map_err(|_| "Trace file does not exist".to_owned())?;
    if !metadata.is_file() {
        return Err("Trace source is not a regular file".to_owned());
    }
    if metadata.len() > MAX_TRACE_BYTES {
        return Err("Trace exceeds the export byte budget".to_owned());
    }
    Ok(metadata)
}

fn same_path(source: &Path, destination: &Path) -> bool {
    destination.exists() && source.canonicalize().ok() == destination.canonicalize().ok()
}

fn temporary_sibling(destination: &Path) -> Result<PathBuf, String> {
    let parent = destination
        .parent()
        .filter(|parent| !parent.as_os_str().is_empty())
        .ok_or_else(|| "Export destination has no parent directory".to_owned())?;
    if !parent.is_dir() {
        return Err("Export destination directory does not exist".to_owned());
    }
    if destination.is_dir() {
        return Err("Export destination is a directory".to_owned());
    }
    let name = destination
        .file_name()
        .and_then(|name| name.to_str())
        .ok_or_else(|| "Export destination needs a UTF-8 file name".to_owned())?;
    Ok(parent.join(format!(
        ".{name}-{}-{}.tmp",
        std::process::id(),
        NEXT_TEMPORARY.fetch_add(1, Ordering::Relaxed)
    )))
}

fn session_json(record: &SessionRecord, file_size: u64) -> Result<Vec<u8>, String> {
    let mut object = Map::new();
    object.insert("id".into(), Value::String(clipped(&record.id, MAX_ID)));
    object.insert(
        "title".into(),
        Value::String(clipped(&record.title, MAX_TEXT_BYTES)),
    );
    object.insert(
        "capturedAt".into(),
        Value::String(format_rfc3339_millis(record.captured_unix_ms)),
    );
    object.insert(
        "capturedUnixMs".into(),
        Value::from(record.captured_unix_ms),
    );
    object.insert(
        "deviceModel".into(),
        record.device_model.as_ref().map_or(Value::Null, |model| {
            Value::String(clipped(model, MAX_TEXT_BYTES))
        }),
    );
    object.insert(
        "androidSdk".into(),
        record
            .android_sdk
            .map_or(Value::Null, |sdk| Value::from(u64::from(sdk))),
    );
    object.insert("durationNanos".into(), Value::from(record.duration_nanos));
    object.insert("fileSizeBytes".into(), Value::from(file_size));
    object.insert(
        "configSummary".into(),
        record
            .config_summary
            .as_ref()
            .map_or(Value::Null, |summary| {
                Value::String(clipped(summary, MAX_TEXT_BYTES))
            }),
    );
    object.insert(
        "artifact".into(),
        record.artifact.as_ref().map_or(Value::Null, artifact_value),
    );
    aps_json::to_vec(
        &Value::Object(object),
        Limits {
            max_bytes: MAX_SESSION_JSON_BYTES,
            max_depth: 16,
            max_nodes: 4_000,
            max_string_bytes: MAX_TEXT_BYTES,
            max_collection_items: 256,
        },
    )
    .map_err(|_| "Session package metadata exceeds the export budget".to_owned())
}

const MAX_ID: usize = 128;

fn artifact_value(artifact: &TraceArtifactRecord) -> Value {
    let mut object = Map::new();
    object.insert("id".into(), Value::String(clipped(&artifact.id, MAX_ID)));
    object.insert(
        "displayName".into(),
        Value::String(clipped(&artifact.display_name, MAX_TEXT_BYTES)),
    );
    object.insert(
        "completenessLabel".into(),
        Value::String(clipped(&artifact.completeness_label, MAX_TEXT_BYTES)),
    );
    object.insert(
        "limitations".into(),
        Value::Array(bounded_values(&artifact.limitations)),
    );
    object.insert(
        "warnings".into(),
        Value::Array(bounded_values(&artifact.warnings)),
    );
    Value::Object(object)
}

fn bounded_values(values: &[String]) -> Vec<Value> {
    values
        .iter()
        .take(MAX_LIST_ITEMS)
        .map(|value| Value::String(clipped(value, MAX_TEXT_BYTES)))
        .collect()
}

fn metadata_properties(record: &SessionRecord, file_size: u64) -> Result<String, String> {
    let mut text = String::new();
    push_property(&mut text, "session.id", &record.id);
    push_property(&mut text, "session.title", &record.title);
    push_property(
        &mut text,
        "captured.at",
        &format_rfc3339_millis(record.captured_unix_ms),
    );
    push_property(
        &mut text,
        "captured.unix.ms",
        &record.captured_unix_ms.to_string(),
    );
    push_property(
        &mut text,
        "duration.nanos",
        &record.duration_nanos.to_string(),
    );
    push_property(&mut text, "file.size.bytes", &file_size.to_string());
    if let Some(summary) = &record.config_summary {
        push_property(&mut text, "config.summary", summary);
    }
    if let Some(artifact) = &record.artifact {
        push_property(&mut text, "artifact.id", &artifact.id);
        push_property(&mut text, "artifact.display.name", &artifact.display_name);
        push_property(
            &mut text,
            "artifact.completeness",
            &artifact.completeness_label,
        );
    }
    push_property(&mut text, "exporter.version", "1.0");
    if text.len() > MAX_PROPERTIES_BYTES {
        return Err("Session package properties exceed the export budget".to_owned());
    }
    Ok(text)
}

fn push_property(output: &mut String, key: &str, value: &str) {
    output.push_str(key);
    output.push('=');
    output.push_str(&escape_property(value));
    output.push('\n');
}

fn escape_property(value: &str) -> String {
    let mut escaped = String::new();
    for character in clipped(value, MAX_TEXT_BYTES).chars() {
        match character {
            '\\' => escaped.push_str("\\\\"),
            '\n' => escaped.push_str("\\n"),
            '\r' => escaped.push_str("\\r"),
            '=' => escaped.push_str("\\="),
            ':' => escaped.push_str("\\:"),
            _ => escaped.push(character),
        }
    }
    escaped
}

/// RFC 3339 UTC timestamp with milliseconds omitted when zero, matching
/// Instant.toString for whole-second captures.
fn format_rfc3339_millis(unix_ms: u64) -> String {
    let seconds = (unix_ms / 1_000).min(i64::MAX as u64) as i64;
    let millis = (unix_ms % 1_000) as u32;
    let days = seconds.div_euclid(86_400);
    let second_of_day = seconds.rem_euclid(86_400);
    let (year, month, day) = civil_from_days(days);
    let hour = second_of_day / 3_600;
    let minute = (second_of_day % 3_600) / 60;
    let second = second_of_day % 60;
    if millis == 0 {
        format!("{year:04}-{month:02}-{day:02}T{hour:02}:{minute:02}:{second:02}Z")
    } else {
        format!("{year:04}-{month:02}-{day:02}T{hour:02}:{minute:02}:{second:02}.{millis:03}Z")
    }
}

/// Howard Hinnant's civil-from-days algorithm for the proleptic Gregorian calendar.
fn civil_from_days(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let year = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    (year + i64::from(month <= 2), month as u32, day as u32)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::trace::sessions::TraceArtifactRecord;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use zip::ZipArchive;

    static NEXT_DIRECTORY: AtomicUsize = AtomicUsize::new(0);

    struct TestDirectory(PathBuf);
    impl TestDirectory {
        fn new(name: &str) -> Self {
            let path = std::env::temp_dir().join(format!(
                "aps-trace-export-{name}-{}-{}",
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

    fn record(trace: PathBuf) -> SessionRecord {
        SessionRecord {
            id: "session-one".into(),
            title: "Pixel capture".into(),
            captured_at_label: "2026-07-25 01:02".into(),
            duration_label: "10.000 s".into(),
            captured_unix_ms: 1_784_941_323_000,
            duration_nanos: 10_000_000_000,
            trace_path: trace,
            artifact: Some(TraceArtifactRecord {
                id: "artifact-one".into(),
                display_name: "capture.pftrace".into(),
                completeness_label: "Complete".into(),
                limitations: vec!["duration limited".into()],
                warnings: Vec::new(),
            }),
            config_summary: Some("System overview; buffers=4096".into()),
            device_model: Some("Pixel 9".into()),
            android_sdk: Some(35),
        }
    }

    fn trace_file(directory: &TestDirectory, bytes: &[u8]) -> PathBuf {
        let path = directory.path().join("capture.pftrace");
        fs::write(&path, bytes).unwrap();
        path
    }

    #[test]
    fn raw_export_copies_exact_bytes_and_rejects_identity_or_missing_inputs() {
        let directory = TestDirectory::new("raw");
        let bytes = vec![1u8, 2, 3, 4, 5];
        let source = trace_file(&directory, &bytes);
        let destination = directory.path().join("exported.pftrace");

        export_raw_trace(&source, &destination).unwrap();
        assert_eq!(fs::read(&destination).unwrap(), bytes);

        fs::write(&destination, b"old").unwrap();
        export_raw_trace(&source, &destination).unwrap();
        assert_eq!(fs::read(&destination).unwrap(), bytes);

        assert!(export_raw_trace(&source, &source).is_err());
        assert!(export_raw_trace(&directory.path().join("missing.pftrace"), &destination).is_err());
        assert!(export_raw_trace(&source, &directory.path()).is_err());
    }

    #[test]
    fn session_package_contains_exactly_three_bounded_entries() {
        let directory = TestDirectory::new("package");
        let trace_bytes = b"trace-package-bytes";
        let source = trace_file(&directory, trace_bytes);
        let session = record(source.clone());
        let destination = directory.path().join("perfetto-session.zip");

        export_session_package(&session, &destination).unwrap();

        let mut archive = ZipArchive::new(File::open(&destination).unwrap()).unwrap();
        assert_eq!(archive.len(), 3);
        let mut names: Vec<String> = archive
            .file_names()
            .filter_map(|name| name.ok())
            .map(|name| name.into_owned())
            .collect();
        names.sort();
        assert_eq!(
            names,
            [
                "metadata.properties".to_owned(),
                "session.json".to_owned(),
                "trace.pftrace".to_owned()
            ]
        );
        let mut json = String::new();
        archive
            .by_name(SESSION_JSON_ENTRY)
            .unwrap()
            .read_to_string(&mut json)
            .unwrap();
        let value = aps_json::parse_slice(json.as_bytes(), Limits::default()).unwrap();
        assert_eq!(value["id"].as_str(), Some("session-one"));
        assert_eq!(value["title"].as_str(), Some("Pixel capture"));
        assert_eq!(value["capturedAt"].as_str(), Some("2026-07-25T01:02:03Z"));
        assert_eq!(value["capturedUnixMs"].as_u64(), Some(1_784_941_323_000));
        assert_eq!(value["durationNanos"].as_u64(), Some(10_000_000_000));
        assert_eq!(
            value["fileSizeBytes"].as_u64(),
            Some(trace_bytes.len() as u64)
        );
        assert_eq!(
            value["configSummary"].as_str(),
            Some("System overview; buffers=4096")
        );
        assert_eq!(value["artifact"]["id"].as_str(), Some("artifact-one"));
        assert_eq!(value["deviceModel"].as_str(), Some("Pixel 9"));
        assert_eq!(value["androidSdk"].as_u64(), Some(35));

        let mut trace = Vec::new();
        archive
            .by_name(TRACE_ENTRY)
            .unwrap()
            .read_to_end(&mut trace)
            .unwrap();
        assert_eq!(trace, trace_bytes.to_vec());

        let mut properties = String::new();
        archive
            .by_name(METADATA_ENTRY)
            .unwrap()
            .read_to_string(&mut properties)
            .unwrap();
        assert!(properties.contains("session.id=session-one\n"));
        assert!(properties.contains("exporter.version=1.0\n"));
        assert!(properties.contains("config.summary=System overview; buffers\\=4096\n"));
        assert!(properties.contains("artifact.id=artifact-one\n"));
        assert!(!properties.contains('\r'));
    }

    #[test]
    fn package_export_fails_closed_for_a_missing_trace() {
        let directory = TestDirectory::new("package-missing");
        let missing = directory.path().join("missing.pftrace");
        let session = record(missing);
        assert!(export_session_package(&session, &directory.path().join("out.zip")).is_err());
        assert!(!directory.path().join("out.zip").exists());
    }

    #[test]
    fn rfc3339_formatting_matches_whole_seconds_and_milliseconds() {
        assert_eq!(format_rfc3339_millis(0), "1970-01-01T00:00:00Z");
        assert_eq!(format_rfc3339_millis(1_000), "1970-01-01T00:00:01Z");
        assert_eq!(
            format_rfc3339_millis(1_784_941_323_000),
            "2026-07-25T01:02:03Z"
        );
        assert_eq!(
            format_rfc3339_millis(1_784_941_323_123),
            "2026-07-25T01:02:03.123Z"
        );
    }

    #[test]
    fn suggested_names_match_compose_save_dialogs() {
        let directory = TestDirectory::new("names");
        let source = trace_file(&directory, b"x");
        assert_eq!(suggested_raw_name(&source), "capture.pftrace");
        assert_eq!(suggested_raw_name(Path::new("")), "trace.pftrace");
        assert_eq!(
            suggested_session_name(&record(source)),
            "perfetto-session.zip"
        );
    }
}

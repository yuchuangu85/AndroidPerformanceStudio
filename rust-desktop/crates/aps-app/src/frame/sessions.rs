//! Durable Frame Analyzer session storage.
//!
//! Compose parity with SqliteFrameSessionStore.save(): one bounded JSON index
//! (newest first) plus one bounded JSON file per session's frames. The SQLite
//! format is an internal Compose choice; the Rust app keeps JSON so no SQLite
//! dependency is added. The index publishes a specific generation of frames
//! with its byte digest. Legacy entries without that digest support only
//! local read-back; the store never resolves a trace path from its metadata.

use aps_contracts::SourceDigest;
use aps_frame::model::FrameStages;
use aps_frame::{ExpectedDurationSource, FrameCaptureSession, FrameSample, FrameSource, JankType};
use aps_json::{Limits, Map, Value};
use aps_platform::atomic_write;
use std::{
    collections::{BTreeMap, BTreeSet},
    fs::{self, File},
    io::Read as _,
    path::{Path, PathBuf},
    sync::{Arc, Mutex, MutexGuard},
};

const SESSIONS_FILE: &str = "sessions.json";
const FRAMES_DIR: &str = "frames";
const INDEX_VERSION: u64 = 2;
const LEGACY_INDEX_VERSION: u64 = 1;
const FRAMES_VERSION: u64 = 1;
const MAX_SESSIONS: usize = 100;
const MAX_FRAMES_PER_SESSION: usize = 20_000;
const MAX_INDEX_BYTES: u64 = 1024 * 1024;
const MAX_FRAMES_JSON_BYTES: u64 = 16 * 1024 * 1024;
const MAX_STRING_BYTES: usize = 4096;
const MAX_ID_BYTES: usize = 128;
const MAX_TEXT_BYTES: usize = 512;
const MAX_PATH_BYTES: usize = 2048;
const MAX_WARNINGS: usize = 32;
const MAX_RATES: usize = 16;
const MAX_STATES: usize = 64;
const MAX_REASONS: usize = 32;
const MAX_JANK_TYPES: usize = 16;

fn index_limits() -> Limits {
    Limits {
        max_bytes: MAX_INDEX_BYTES,
        max_depth: 8,
        max_nodes: 40_000,
        max_string_bytes: MAX_STRING_BYTES,
        max_collection_items: 4_000,
    }
}

fn frames_limits() -> Limits {
    Limits {
        max_bytes: MAX_FRAMES_JSON_BYTES,
        max_depth: 8,
        max_nodes: 4_000_000,
        max_string_bytes: MAX_STRING_BYTES,
        max_collection_items: MAX_FRAMES_PER_SESSION.max(1_024),
    }
}

pub(in crate::frame) fn clipped(value: &str, max_bytes: usize) -> String {
    let mut end = value.len().min(max_bytes);
    while !value.is_char_boundary(end) {
        end -= 1;
    }
    value[..end].to_owned()
}

fn clipped_option(value: Option<String>, max_bytes: usize) -> Option<String> {
    value.map(|value| clipped(&value, max_bytes))
}

fn valid_identifier(value: &str, max_bytes: usize) -> bool {
    !value.is_empty() && value.len() <= max_bytes && !value.chars().any(char::is_control)
}

fn fnv1a64(bytes: &[u8]) -> u64 {
    bytes.iter().fold(0xcbf2_9ce4_8422_2325u64, |hash, byte| {
        (hash ^ u64::from(*byte)).wrapping_mul(0x0000_0100_0000_01b3)
    })
}

fn frames_file_name(session_id: &str) -> String {
    format!("{:016x}.json", fnv1a64(session_id.as_bytes()))
}

fn bound_frames_file_name(session_id: &str, digest: SourceDigest) -> String {
    format!("{:016x}-{digest}.json", fnv1a64(session_id.as_bytes()))
}

fn directory_if_present(path: &Path) -> Result<bool, String> {
    match fs::symlink_metadata(path) {
        Ok(metadata) if metadata.is_dir() => Ok(true),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(false),
        _ => Err("Frame history directory is invalid".into()),
    }
}

// Reject existing storage symlinks before opening files. The store belongs to
// the local desktop, not an adversarial shared directory. Bound bytes from the
// opened regular handle; verified digest comparisons use those exact bytes.
fn read_history_file(path: &Path, budget: u64) -> Result<Option<Vec<u8>>, String> {
    match fs::symlink_metadata(path) {
        Ok(metadata) if metadata.is_file() && metadata.len() <= budget => {}
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        _ => return Err("Frame history input is not a bounded regular file".into()),
    }
    let file = File::open(path).map_err(|_| "Cannot open frame history input")?;
    let metadata = file
        .metadata()
        .map_err(|_| "Cannot inspect frame history input")?;
    if !metadata.is_file() || metadata.len() > budget {
        return Err("Frame history input is not a bounded regular file".into());
    }
    let mut bytes = Vec::new();
    file.take(budget + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| "Cannot read frame history input")?;
    if bytes.len() as u64 > budget {
        return Err("Frame history input exceeds the byte budget".into());
    }
    Ok(Some(bytes))
}

/// Bounded, atomic Frame Analyzer session index.
#[derive(Clone)]
pub(in crate::frame) struct FrameSessionStore {
    root: PathBuf,
    mutex: Arc<Mutex<()>>,
}

impl FrameSessionStore {
    pub(in crate::frame) fn new(root: PathBuf) -> Self {
        Self {
            root,
            mutex: Arc::new(Mutex::new(())),
        }
    }

    fn lock_guard(&self) -> Result<MutexGuard<'_, ()>, String> {
        self.mutex
            .lock()
            .map_err(|_| "Frame session store lock is poisoned".to_owned())
    }

    fn index_path(&self) -> PathBuf {
        self.root.join(SESSIONS_FILE)
    }

    fn frames_dir(&self) -> PathBuf {
        self.root.join(FRAMES_DIR)
    }

    /// Saves frames without claiming a verified relation to any trace file.
    #[cfg_attr(not(test), allow(dead_code))]
    pub(in crate::frame) fn save(
        &self,
        session: &FrameCaptureSession,
        frames: &[FrameSample],
    ) -> Result<(), String> {
        self.save_with_trace(session, frames, None)
    }

    /// Only the producer that has verified the original analyzed trace may
    /// pass its digest. A viewer path or an old stored claim is not proof.
    pub(in crate::frame) fn save_with_trace(
        &self,
        session: &FrameCaptureSession,
        frames: &[FrameSample],
        trace_digest: Option<SourceDigest>,
    ) -> Result<(), String> {
        let _guard = self.lock_guard()?;
        let session = bounded_session(session)?;
        if frames.len() > MAX_FRAMES_PER_SESSION {
            return Err("Frame session exceeds the frame count budget".to_owned());
        }
        if frames.iter().any(|frame| frame.session_id != session.id) {
            return Err("Every frame must belong to the saved session".to_owned());
        }
        let mut seen = BTreeSet::new();
        if frames.iter().any(|frame| !seen.insert(frame.frame_id)) {
            return Err("Frame session contains duplicate frame ids".to_owned());
        }
        if let Some(digest) = trace_digest {
            check_trace_binding(&session, frames, digest)?;
        }
        let mut entries = self.read_index()?;
        let bounded_frames: Vec<Value> = frames.iter().map(sample_to_value).collect();
        let frames_json = frames_document(&session.id, bounded_frames)?;
        let frames_digest =
            SourceDigest::hash_reader(frames_json.as_slice(), MAX_FRAMES_JSON_BYTES)?;
        let frames_file = bound_frames_file_name(&session.id, frames_digest);
        directory_if_present(&self.frames_dir())?;
        atomic_write(&self.frames_dir().join(&frames_file), &frames_json)
            .map_err(|_| "Cannot write the frame session frames".to_owned())?;
        let mut retired = Vec::new();
        entries.retain(|entry| {
            if entry.session_id != session.id {
                return true;
            }
            if entry.frames_file != frames_file {
                retired.push(entry.frames_file.clone());
            }
            false
        });
        entries.insert(
            0,
            IndexEntry {
                session_id: session.id.clone(),
                frames_file,
                frame_count: frames.len(),
                frames_digest: Some(frames_digest),
                trace_digest,
                session,
            },
        );
        if entries.len() > MAX_SESSIONS {
            retired.extend(
                entries
                    .split_off(MAX_SESSIONS)
                    .into_iter()
                    .map(|entry| entry.frames_file),
            );
        }
        self.write_index(&entries)?;
        for name in retired {
            let _ = fs::remove_file(self.frames_dir().join(name));
        }
        Ok(())
    }

    fn read_index(&self) -> Result<Vec<IndexEntry>, String> {
        if !directory_if_present(&self.root)? {
            return Ok(Vec::new());
        }
        let Some(bytes) = read_history_file(&self.index_path(), MAX_INDEX_BYTES)? else {
            return Ok(Vec::new());
        };
        let value = aps_json::parse_slice(&bytes, index_limits()).map_err(|_| {
            "Frame session index is malformed or exceeds the storage budget".to_owned()
        })?;
        let (version, entries) = match &value {
            Value::Array(entries) => (LEGACY_INDEX_VERSION, entries),
            Value::Object(object) => {
                let version = object
                    .get("version")
                    .and_then(Value::as_u64)
                    .filter(|&v| matches!(v, INDEX_VERSION | LEGACY_INDEX_VERSION))
                    .ok_or("Unsupported frame session index version")?;
                let entries = object
                    .get("sessions")
                    .and_then(Value::as_array)
                    .ok_or("Frame session index has no sessions array")?;
                (version, entries)
            }
            _ => return Err("Frame session index has an unexpected root".to_owned()),
        };
        if entries.len() > MAX_SESSIONS {
            return Err("Frame session index exceeds the count budget".into());
        }
        let mut ids = BTreeSet::new();
        let mut row_files = BTreeSet::new();
        let parsed = entries
            .iter()
            .map(|entry| {
                let entry =
                    parse_index_entry(entry, version).ok_or("Invalid frame history index entry")?;
                if !ids.insert(entry.session_id.clone())
                    || !row_files.insert(entry.frames_file.clone())
                {
                    return Err("Duplicate frame history identity");
                }
                Ok(entry)
            })
            .collect::<Result<_, _>>()?;
        Ok(parsed)
    }

    fn write_index(&self, entries: &[IndexEntry]) -> Result<(), String> {
        let mut root = Map::new();
        root.insert("version".into(), Value::from(INDEX_VERSION));
        root.insert(
            "sessions".into(),
            Value::Array(
                entries
                    .iter()
                    .map(|entry| {
                        let mut object = Map::new();
                        object.insert(
                            "framesFile".into(),
                            Value::String(entry.frames_file.clone()),
                        );
                        object.insert("frameCount".into(), Value::from(entry.frame_count as u64));
                        if let Some(digest) = entry.frames_digest {
                            object.insert("framesDigest".into(), Value::String(digest.to_hex()));
                        }
                        if let Some(digest) = entry.trace_digest {
                            object.insert(
                                "originalTraceDigest".into(),
                                Value::String(digest.to_hex()),
                            );
                        }
                        object.insert("session".into(), session_to_value(&entry.session));
                        Value::Object(object)
                    })
                    .collect(),
            ),
        );
        let bytes = aps_json::to_vec(&Value::Object(root), index_limits())
            .map_err(|_| "Frame session index exceeds the storage budget".to_owned())?;
        atomic_write(&self.index_path(), &bytes)
            .map_err(|_| "Cannot write the frame session index".to_owned())
    }

    /// Internal read-back for local display. This does not open or verify any
    /// stored source path. Legacy rows carry an explicit provenance downgrade.
    #[cfg_attr(not(test), allow(dead_code))]
    fn load(
        &self,
        session_id: &str,
    ) -> Result<Option<(FrameCaptureSession, Vec<FrameSample>)>, String> {
        let _guard = self.lock_guard()?;
        let entries = self.read_index()?;
        let Some(entry) = entries.iter().find(|entry| entry.session_id == session_id) else {
            return Ok(None);
        };
        let frames = self.read_frames(entry)?;
        let mut session = entry.session.clone();
        // Do not let a cached viewer/import path become evidence. Read-back is
        // not a performed verification even when we recorded the original hash.
        session.perfetto_trace_file = None;
        if entry.frames_digest.is_none() {
            session.provenance_complete = false;
            session.provenance_warnings.push(
                "Legacy Frame history has no row digest; available for local display only.".into(),
            );
        }
        Ok(Some((session, frames)))
    }

    fn read_frames(&self, entry: &IndexEntry) -> Result<Vec<FrameSample>, String> {
        if !directory_if_present(&self.frames_dir())? {
            return Err("Frame history rows are missing".into());
        }
        let bytes = read_history_file(
            &self.frames_dir().join(&entry.frames_file),
            MAX_FRAMES_JSON_BYTES,
        )?
        .ok_or("Frame history rows are missing")?;
        if let Some(expected) = entry.frames_digest
            && SourceDigest::hash_reader(bytes.as_slice(), MAX_FRAMES_JSON_BYTES)? != expected
        {
            return Err("Frame history rows do not match their recorded digest".into());
        }
        let value = aps_json::parse_slice(&bytes, frames_limits()).map_err(|_| {
            "Frame session frames are malformed or exceed the storage budget".to_owned()
        })?;
        if value["version"].as_u64() != Some(FRAMES_VERSION)
            || value["sessionId"].as_str() != Some(&entry.session_id)
        {
            return Err("Frame history document version or identity does not match".into());
        }
        let rows = value["frames"]
            .as_array()
            .ok_or("Frame history document has no frame rows")?;
        if rows.len() != entry.frame_count {
            return Err("Frame history row count does not match its index".into());
        }
        let mut ids = BTreeSet::new();
        rows.iter()
            .map(|value| {
                let frame = parse_sample(value).ok_or("Malformed frame history row")?;
                if frame.session_id != entry.session_id || !ids.insert(frame.frame_id) {
                    return Err("Foreign or duplicate frame history row".into());
                }
                Ok(frame)
            })
            .collect()
    }

    /// Verify explicitly selected bytes against the original analyzed artifact.
    /// Never obtains a path from persisted history or opens Trace Processor.
    #[cfg_attr(not(test), allow(dead_code))]
    pub(in crate::frame) fn verify_trace(
        &self,
        session_id: &str,
        selected_path: &Path,
    ) -> Result<SourceDigest, String> {
        let _guard = self.lock_guard()?;
        let entry = self
            .read_index()?
            .into_iter()
            .find(|entry| entry.session_id == session_id)
            .ok_or("Frame history session is unavailable")?;
        let expected = entry
            .trace_digest
            .ok_or("Frame history has no analyzed trace binding")?;
        let frames = self.read_frames(&entry)?;
        check_trace_binding(&entry.session, &frames, expected)?;
        let metadata =
            fs::symlink_metadata(selected_path).map_err(|_| "Cannot inspect selected trace")?;
        if !metadata.is_file()
            || metadata.len() == 0
            || metadata.len() > aps_perfetto::MAX_TRACE_BYTES
        {
            return Err("Selected trace must be a nonempty bounded regular file".into());
        }
        if SourceDigest::hash_file(selected_path, aps_perfetto::MAX_TRACE_BYTES)? != expected {
            return Err("Selected trace does not match the analyzed source".into());
        }
        Ok(expected)
    }
}

struct IndexEntry {
    session_id: String,
    frames_file: String,
    frame_count: usize,
    frames_digest: Option<SourceDigest>,
    trace_digest: Option<SourceDigest>,
    session: FrameCaptureSession,
}

fn check_trace_binding(
    session: &FrameCaptureSession,
    frames: &[FrameSample],
    digest: SourceDigest,
) -> Result<(), String> {
    let err = || "Frame history does not describe the original analyzed trace".to_owned();
    if !session.provenance_complete
        || session.source != FrameSource::Perfetto
        || frames.is_empty()
        || frames
            .iter()
            .any(|frame| frame.source != FrameSource::Perfetto)
    {
        return Err(err());
    }
    let digest = digest.to_hex();
    let prefix = &digest[..32];
    let suffix = if session.imported_file.is_some() {
        if session.imported_file_sha256.as_deref() != Some(&digest) {
            return Err(err());
        }
        prefix.to_owned()
    } else {
        let serial = session
            .device_serial
            .as_deref()
            .filter(|s| !s.is_empty())
            .ok_or_else(err)?;
        let package = session
            .package_name
            .as_deref()
            .filter(|s| !s.is_empty())
            .ok_or_else(err)?;
        let pid = frames[0]
            .process_id
            .filter(|&pid| pid > 0)
            .ok_or_else(err)?;
        if session.imported_file_sha256.is_some()
            || session.imported_at_unix_ms.is_some()
            || frames.iter().any(|frame| {
                frame.process_id != Some(pid) || frame.package_name.as_deref() != Some(package)
            })
        {
            return Err(err());
        }
        format!(
            "{prefix}-{:016x}",
            super::artifact::capture_context(serial, pid, package)
        )
    };
    if session.artifact.as_deref() != Some(&format!("frame-{suffix}"))
        || session.id != format!("perfetto-{suffix}")
    {
        return Err(err());
    }
    Ok(())
}

fn frames_document(session_id: &str, frames: Vec<Value>) -> Result<Vec<u8>, String> {
    let mut root = Map::new();
    root.insert("version".into(), Value::from(FRAMES_VERSION));
    root.insert("sessionId".into(), Value::String(session_id.to_owned()));
    root.insert("frames".into(), Value::Array(frames));
    aps_json::to_vec(&Value::Object(root), frames_limits())
        .map_err(|_| "Frame session frames exceed the storage budget".to_owned())
}

fn bounded_session(session: &FrameCaptureSession) -> Result<FrameCaptureSession, String> {
    if !valid_identifier(&session.id, MAX_ID_BYTES) {
        return Err("Frame session id is invalid".to_owned());
    }
    Ok(FrameCaptureSession {
        id: session.id.clone(),
        source: session.source,
        started_at_unix_ms: session.started_at_unix_ms,
        package_name: clipped_option(session.package_name.clone(), MAX_TEXT_BYTES),
        device_serial: clipped_option(session.device_serial.clone(), MAX_TEXT_BYTES),
        device_api_level: session.device_api_level,
        agent_protocol: clipped_option(session.agent_protocol.clone(), MAX_ID_BYTES),
        source_capabilities: session.source_capabilities,
        observed_refresh_rates_hz: session
            .observed_refresh_rates_hz
            .iter()
            .copied()
            .filter(|value| value.is_finite())
            .take(MAX_RATES)
            .collect(),
        imported_file: clipped_option(session.imported_file.clone(), MAX_PATH_BYTES),
        imported_file_sha256: clipped_option(session.imported_file_sha256.clone(), MAX_ID_BYTES),
        imported_at_unix_ms: session.imported_at_unix_ms,
        provenance_complete: session.provenance_complete,
        provenance_warnings: session
            .provenance_warnings
            .iter()
            .take(MAX_WARNINGS)
            .map(|warning| clipped(warning, MAX_TEXT_BYTES))
            .collect(),
        perfetto_trace_file: clipped_option(session.perfetto_trace_file.clone(), MAX_PATH_BYTES),
        artifact: clipped_option(session.artifact.clone(), MAX_ID_BYTES),
    })
}

fn session_to_value(session: &FrameCaptureSession) -> Value {
    let mut object = Map::new();
    object.insert("id".into(), Value::String(session.id.clone()));
    object.insert(
        "source".into(),
        Value::String(session.source.as_name().to_owned()),
    );
    object.insert(
        "startedAtUnixMs".into(),
        Value::from(session.started_at_unix_ms),
    );
    object.insert(
        "packageName".into(),
        option_value(session.package_name.clone()),
    );
    object.insert(
        "deviceSerial".into(),
        option_value(session.device_serial.clone()),
    );
    object.insert(
        "deviceApiLevel".into(),
        session
            .device_api_level
            .map_or(Value::Null, |value| Value::from(u64::from(value))),
    );
    object.insert(
        "agentProtocol".into(),
        option_value(session.agent_protocol.clone()),
    );
    object.insert(
        "sourceCapabilities".into(),
        session
            .source_capabilities
            .map_or(Value::Null, capabilities_value),
    );
    object.insert(
        "observedRefreshRatesHz".into(),
        Value::Array(
            session
                .observed_refresh_rates_hz
                .iter()
                .filter_map(|value| serde_number(*value))
                .collect(),
        ),
    );
    object.insert(
        "importedFile".into(),
        option_value(session.imported_file.clone()),
    );
    object.insert(
        "importedFileSha256".into(),
        option_value(session.imported_file_sha256.clone()),
    );
    object.insert(
        "importedAtUnixMs".into(),
        session.imported_at_unix_ms.map_or(Value::Null, Value::from),
    );
    object.insert(
        "provenanceComplete".into(),
        Value::Bool(session.provenance_complete),
    );
    object.insert(
        "provenanceWarnings".into(),
        Value::Array(
            session
                .provenance_warnings
                .iter()
                .map(|warning| Value::String(clipped(warning, MAX_TEXT_BYTES)))
                .collect(),
        ),
    );
    object.insert(
        "perfettoTraceFile".into(),
        option_value(session.perfetto_trace_file.clone()),
    );
    object.insert("artifact".into(), option_value(session.artifact.clone()));
    Value::Object(object)
}

fn capabilities_value(capabilities: aps_frame::FrameSourceCapabilities) -> Value {
    let mut object = Map::new();
    object.insert("realtime".into(), Value::Bool(capabilities.realtime));
    object.insert(
        "stageBreakdown".into(),
        Value::Bool(capabilities.stage_breakdown),
    );
    object.insert(
        "platformJankClassification".into(),
        Value::Bool(capabilities.platform_jank_classification),
    );
    object.insert(
        "expectedFrameDeadline".into(),
        Value::Bool(capabilities.expected_frame_deadline),
    );
    object.insert(
        "appStateLabels".into(),
        Value::Bool(capabilities.app_state_labels),
    );
    Value::Object(object)
}

fn option_value(value: Option<String>) -> Value {
    value.map_or(Value::Null, Value::String)
}

fn serde_number(value: f64) -> Option<Value> {
    aps_json::Number::from_f64(value).map(Value::Number)
}

fn sample_to_value(sample: &FrameSample) -> Value {
    let mut object = Map::new();
    object.insert("frameId".into(), Value::from(sample.frame_id));
    object.insert(
        "sessionId".into(),
        Value::String(clipped(&sample.session_id, MAX_ID_BYTES)),
    );
    object.insert(
        "source".into(),
        Value::String(sample.source.as_name().to_owned()),
    );
    object.insert(
        "packageName".into(),
        option_value(clipped_option(sample.package_name.clone(), MAX_TEXT_BYTES)),
    );
    object.insert(
        "processId".into(),
        sample.process_id.map_or(Value::Null, Value::from),
    );
    object.insert(
        "activityName".into(),
        option_value(clipped_option(sample.activity_name.clone(), MAX_TEXT_BYTES)),
    );
    object.insert(
        "windowId".into(),
        option_value(clipped_option(sample.window_id.clone(), MAX_TEXT_BYTES)),
    );
    object.insert(
        "intendedVsyncNs".into(),
        sample.intended_vsync_ns.map_or(Value::Null, Value::from),
    );
    object.insert(
        "actualVsyncNs".into(),
        sample.actual_vsync_ns.map_or(Value::Null, Value::from),
    );
    object.insert(
        "frameCompletedNs".into(),
        sample.frame_completed_ns.map_or(Value::Null, Value::from),
    );
    object.insert(
        "presentNs".into(),
        sample.present_ns.map_or(Value::Null, Value::from),
    );
    object.insert(
        "expectedDurationNs".into(),
        sample.expected_duration_ns.map_or(Value::Null, Value::from),
    );
    object.insert(
        "expectedDurationSource".into(),
        Value::String(sample.expected_duration_source.as_name().to_owned()),
    );
    object.insert(
        "refreshRateHz".into(),
        sample
            .refresh_rate_hz
            .and_then(serde_number)
            .unwrap_or(Value::Null),
    );
    object.insert(
        "frameTimelineVsyncId".into(),
        sample
            .frame_timeline_vsync_id
            .map_or(Value::Null, Value::from),
    );
    object.insert(
        "totalDurationNs".into(),
        sample.total_duration_ns.map_or(Value::Null, Value::from),
    );
    object.insert("stages".into(), stages_value(&sample.stages));
    object.insert(
        "platformJank".into(),
        sample.platform_jank.map_or(Value::Null, Value::Bool),
    );
    object.insert(
        "platformJankTypes".into(),
        Value::Array(
            sample
                .platform_jank_types
                .iter()
                .take(MAX_JANK_TYPES)
                .map(|jank_type| Value::String(jank_type.as_name().to_owned()))
                .collect(),
        ),
    );
    object.insert(
        "platformJankRuleId".into(),
        option_value(clipped_option(
            sample.platform_jank_rule_id.clone(),
            MAX_TEXT_BYTES,
        )),
    );
    object.insert(
        "platformJankRuleVersion".into(),
        option_value(clipped_option(
            sample.platform_jank_rule_version.clone(),
            MAX_TEXT_BYTES,
        )),
    );
    object.insert("states".into(), states_value(&sample.states));
    object.insert(
        "eligibleForJank".into(),
        Value::Bool(sample.eligible_for_jank),
    );
    object.insert(
        "droppedBeforeSample".into(),
        Value::from(sample.dropped_before_sample),
    );
    object.insert(
        "layoutSnapshotId".into(),
        option_value(clipped_option(
            sample.layout_snapshot_id.clone(),
            MAX_ID_BYTES,
        )),
    );
    object.insert(
        "fragmentName".into(),
        option_value(clipped_option(sample.fragment_name.clone(), MAX_TEXT_BYTES)),
    );
    object.insert(
        "pageName".into(),
        option_value(clipped_option(sample.page_name.clone(), MAX_TEXT_BYTES)),
    );
    object.insert(
        "interactionState".into(),
        option_value(clipped_option(
            sample.interaction_state.clone(),
            MAX_TEXT_BYTES,
        )),
    );
    object.insert(
        "renderThreadName".into(),
        option_value(clipped_option(
            sample.render_thread_name.clone(),
            MAX_TEXT_BYTES,
        )),
    );
    object.insert(
        "surfaceFlingerJankType".into(),
        option_value(clipped_option(
            sample.surface_flinger_jank_type.clone(),
            MAX_TEXT_BYTES,
        )),
    );
    object.insert(
        "jankStatsJank".into(),
        sample.jank_stats_jank.map_or(Value::Null, Value::Bool),
    );
    object.insert(
        "jankStatsReasons".into(),
        Value::Array(
            sample
                .jank_stats_reasons
                .iter()
                .take(MAX_REASONS)
                .map(|reason| Value::String(clipped(reason, MAX_TEXT_BYTES)))
                .collect(),
        ),
    );
    object.insert(
        "jankStatsRuleId".into(),
        option_value(clipped_option(
            sample.jank_stats_rule_id.clone(),
            MAX_TEXT_BYTES,
        )),
    );
    object.insert(
        "jankStatsRuleVersion".into(),
        option_value(clipped_option(
            sample.jank_stats_rule_version.clone(),
            MAX_TEXT_BYTES,
        )),
    );
    Value::Object(object)
}

fn stages_value(stages: &FrameStages) -> Value {
    let mut object = Map::new();
    object.insert(
        "inputNs".into(),
        stages.input_ns.map_or(Value::Null, Value::from),
    );
    object.insert(
        "animationNs".into(),
        stages.animation_ns.map_or(Value::Null, Value::from),
    );
    object.insert(
        "layoutMeasureNs".into(),
        stages.layout_measure_ns.map_or(Value::Null, Value::from),
    );
    object.insert(
        "drawNs".into(),
        stages.draw_ns.map_or(Value::Null, Value::from),
    );
    object.insert(
        "syncNs".into(),
        stages.sync_ns.map_or(Value::Null, Value::from),
    );
    object.insert(
        "commandIssueNs".into(),
        stages.command_issue_ns.map_or(Value::Null, Value::from),
    );
    object.insert(
        "swapBuffersNs".into(),
        stages.swap_buffers_ns.map_or(Value::Null, Value::from),
    );
    object.insert(
        "gpuNs".into(),
        stages.gpu_ns.map_or(Value::Null, Value::from),
    );
    Value::Object(object)
}

fn states_value(states: &BTreeMap<String, String>) -> Value {
    let mut object = Map::new();
    for (key, value) in states.iter().take(MAX_STATES) {
        object.insert(
            clipped(key, MAX_ID_BYTES),
            Value::String(clipped(value, MAX_TEXT_BYTES)),
        );
    }
    Value::Object(object)
}

fn parse_index_entry(value: &Value, index_version: u64) -> Option<IndexEntry> {
    let object = value.as_object()?;
    let session = parse_session(object.get("session")?)?;
    let frames_file = object.get("framesFile")?.as_str()?.to_owned();
    let parse_digest = |key| match object.get(key) {
        Some(value) => SourceDigest::from_hex(value.as_str()?).ok().map(Some),
        None => Some(None),
    };
    let frames_digest = parse_digest("framesDigest")?;
    let trace_digest = parse_digest("originalTraceDigest")?;
    if (index_version == LEGACY_INDEX_VERSION && frames_digest.is_some())
        || (trace_digest.is_some() && frames_digest.is_none())
        || frames_file
            != frames_digest.map_or_else(
                || frames_file_name(&session.id),
                |digest| bound_frames_file_name(&session.id, digest),
            )
    {
        return None;
    }
    let frame_count = usize::try_from(object.get("frameCount")?.as_u64()?).ok()?;
    if frame_count > MAX_FRAMES_PER_SESSION {
        return None;
    }
    Some(IndexEntry {
        session_id: session.id.clone(),
        frames_file,
        frame_count,
        frames_digest,
        trace_digest,
        session,
    })
}

fn parse_session(value: &Value) -> Option<FrameCaptureSession> {
    let object = value.as_object()?;
    let id = object.get("id")?.as_str()?;
    if !valid_identifier(id, MAX_ID_BYTES) {
        return None;
    }
    Some(FrameCaptureSession {
        id: id.to_owned(),
        source: parse_frame_source(object.get("source")?.as_str()?)?,
        started_at_unix_ms: object.get("startedAtUnixMs")?.as_u64()?,
        package_name: optional_string(object, "packageName", MAX_TEXT_BYTES),
        device_serial: optional_string(object, "deviceSerial", MAX_TEXT_BYTES),
        device_api_level: object
            .get("deviceApiLevel")
            .and_then(Value::as_u64)
            .and_then(|value| u32::try_from(value).ok()),
        agent_protocol: optional_string(object, "agentProtocol", MAX_ID_BYTES),
        source_capabilities: object
            .get("sourceCapabilities")
            .and_then(parse_capabilities),
        observed_refresh_rates_hz: object
            .get("observedRefreshRatesHz")
            .and_then(Value::as_array)
            .map(|values| {
                values
                    .iter()
                    .take(MAX_RATES)
                    .filter_map(Value::as_f64)
                    .filter(|value| value.is_finite())
                    .collect()
            })
            .unwrap_or_default(),
        imported_file: optional_string(object, "importedFile", MAX_PATH_BYTES),
        imported_file_sha256: optional_string(object, "importedFileSha256", MAX_ID_BYTES),
        imported_at_unix_ms: object.get("importedAtUnixMs").and_then(Value::as_u64),
        provenance_complete: object
            .get("provenanceComplete")
            .and_then(Value::as_bool)
            .unwrap_or(false),
        provenance_warnings: string_list(object.get("provenanceWarnings"), MAX_WARNINGS),
        perfetto_trace_file: optional_string(object, "perfettoTraceFile", MAX_PATH_BYTES),
        artifact: optional_string(object, "artifact", MAX_ID_BYTES),
    })
}

fn parse_sample(value: &Value) -> Option<FrameSample> {
    let object = value.as_object()?;
    let mut sample = FrameSample {
        frame_id: object.get("frameId")?.as_u64()?,
        session_id: object.get("sessionId")?.as_str()?.to_owned(),
        source: parse_frame_source(object.get("source")?.as_str()?)?,
        package_name: optional_string(object, "packageName", MAX_TEXT_BYTES),
        process_id: object
            .get("processId")
            .and_then(Value::as_i64)
            .and_then(|value| i32::try_from(value).ok()),
        activity_name: optional_string(object, "activityName", MAX_TEXT_BYTES),
        window_id: optional_string(object, "windowId", MAX_TEXT_BYTES),
        intended_vsync_ns: object.get("intendedVsyncNs").and_then(Value::as_i64),
        actual_vsync_ns: object.get("actualVsyncNs").and_then(Value::as_i64),
        frame_completed_ns: object.get("frameCompletedNs").and_then(Value::as_i64),
        present_ns: object.get("presentNs").and_then(Value::as_i64),
        expected_duration_ns: object.get("expectedDurationNs").and_then(Value::as_i64),
        expected_duration_source: parse_expected_source(
            object.get("expectedDurationSource")?.as_str()?,
        )?,
        refresh_rate_hz: object.get("refreshRateHz").and_then(Value::as_f64),
        frame_timeline_vsync_id: object.get("frameTimelineVsyncId").and_then(Value::as_i64),
        total_duration_ns: object.get("totalDurationNs").and_then(Value::as_i64),
        stages: parse_stages(object.get("stages"))?,
        platform_jank: object.get("platformJank").and_then(Value::as_bool),
        platform_jank_types: object
            .get("platformJankTypes")
            .and_then(Value::as_array)
            .map(|values| {
                values
                    .iter()
                    .take(MAX_JANK_TYPES)
                    .filter_map(Value::as_str)
                    .filter_map(parse_jank_type)
                    .collect()
            })
            .unwrap_or_default(),
        platform_jank_rule_id: optional_string(object, "platformJankRuleId", MAX_TEXT_BYTES),
        platform_jank_rule_version: optional_string(
            object,
            "platformJankRuleVersion",
            MAX_TEXT_BYTES,
        ),
        states: parse_states(object.get("states")),
        eligible_for_jank: object
            .get("eligibleForJank")
            .and_then(Value::as_bool)
            .unwrap_or(true),
        dropped_before_sample: object
            .get("droppedBeforeSample")
            .and_then(Value::as_i64)
            .unwrap_or(0),
        layout_snapshot_id: optional_string(object, "layoutSnapshotId", MAX_ID_BYTES),
        fragment_name: optional_string(object, "fragmentName", MAX_TEXT_BYTES),
        page_name: optional_string(object, "pageName", MAX_TEXT_BYTES),
        interaction_state: optional_string(object, "interactionState", MAX_TEXT_BYTES),
        render_thread_name: optional_string(object, "renderThreadName", MAX_TEXT_BYTES),
        surface_flinger_jank_type: optional_string(
            object,
            "surfaceFlingerJankType",
            MAX_TEXT_BYTES,
        ),
        jank_stats_jank: object.get("jankStatsJank").and_then(Value::as_bool),
        jank_stats_reasons: string_list(object.get("jankStatsReasons"), MAX_REASONS)
            .into_iter()
            .collect(),
        jank_stats_rule_id: optional_string(object, "jankStatsRuleId", MAX_TEXT_BYTES),
        jank_stats_rule_version: optional_string(object, "jankStatsRuleVersion", MAX_TEXT_BYTES),
    };
    aps_frame::gfxinfo::sanitize_stored_timing(&mut sample);
    Some(sample)
}

fn parse_stages(value: Option<&Value>) -> Option<FrameStages> {
    let object = value?.as_object()?;
    Some(FrameStages {
        input_ns: object.get("inputNs").and_then(Value::as_i64),
        animation_ns: object.get("animationNs").and_then(Value::as_i64),
        layout_measure_ns: object.get("layoutMeasureNs").and_then(Value::as_i64),
        draw_ns: object.get("drawNs").and_then(Value::as_i64),
        sync_ns: object.get("syncNs").and_then(Value::as_i64),
        command_issue_ns: object.get("commandIssueNs").and_then(Value::as_i64),
        swap_buffers_ns: object.get("swapBuffersNs").and_then(Value::as_i64),
        gpu_ns: object.get("gpuNs").and_then(Value::as_i64),
    })
}

pub(in crate::frame) fn parse_states(value: Option<&Value>) -> BTreeMap<String, String> {
    value
        .and_then(Value::as_object)
        .map(|object| {
            object
                .iter()
                .take(MAX_STATES)
                .filter_map(|(key, value)| {
                    Some((
                        clipped(key, MAX_ID_BYTES),
                        clipped(value.as_str()?, MAX_TEXT_BYTES),
                    ))
                })
                .collect()
        })
        .unwrap_or_default()
}

fn parse_capabilities(value: &Value) -> Option<aps_frame::FrameSourceCapabilities> {
    let object = value.as_object()?;
    Some(aps_frame::FrameSourceCapabilities {
        realtime: object.get("realtime")?.as_bool()?,
        stage_breakdown: object.get("stageBreakdown")?.as_bool()?,
        platform_jank_classification: object.get("platformJankClassification")?.as_bool()?,
        expected_frame_deadline: object.get("expectedFrameDeadline")?.as_bool()?,
        app_state_labels: object.get("appStateLabels")?.as_bool()?,
    })
}

pub(in crate::frame) fn optional_string(
    object: &Map<String, Value>,
    key: &str,
    max_bytes: usize,
) -> Option<String> {
    object
        .get(key)
        .and_then(Value::as_str)
        .map(|value| clipped(value, max_bytes))
}

pub(in crate::frame) fn string_list(value: Option<&Value>, max_items: usize) -> Vec<String> {
    value
        .and_then(Value::as_array)
        .map(|values| {
            values
                .iter()
                .take(max_items)
                .filter_map(Value::as_str)
                .map(|value| clipped(value, MAX_TEXT_BYTES))
                .collect()
        })
        .unwrap_or_default()
}

pub(in crate::frame) fn parse_frame_source(value: &str) -> Option<FrameSource> {
    match value {
        "JANK_STATS" => Some(FrameSource::JankStats),
        "FRAME_METRICS" => Some(FrameSource::FrameMetrics),
        "GFXINFO" => Some(FrameSource::Gfxinfo),
        "PERFETTO" => Some(FrameSource::Perfetto),
        _ => None,
    }
}

pub(in crate::frame) fn parse_expected_source(value: &str) -> Option<ExpectedDurationSource> {
    match value {
        "PLATFORM_DEADLINE" => Some(ExpectedDurationSource::PlatformDeadline),
        "FRAME_INTERVAL" => Some(ExpectedDurationSource::FrameInterval),
        "REFRESH_RATE" => Some(ExpectedDurationSource::RefreshRate),
        "INFERRED_VSYNC" => Some(ExpectedDurationSource::InferredVsync),
        "UNKNOWN" => Some(ExpectedDurationSource::Unknown),
        _ => None,
    }
}

pub(in crate::frame) fn parse_jank_type(value: &str) -> Option<JankType> {
    match value {
        "PLATFORM_REPORTED" => Some(JankType::PlatformReported),
        "DEADLINE_MISSED" => Some(JankType::DeadlineMissed),
        "SLOW_INPUT" => Some(JankType::SlowInput),
        "SLOW_ANIMATION" => Some(JankType::SlowAnimation),
        "SLOW_LAYOUT" => Some(JankType::SlowLayout),
        "SLOW_DRAW" => Some(JankType::SlowDraw),
        "SLOW_SYNC" => Some(JankType::SlowSync),
        "SLOW_COMMAND" => Some(JankType::SlowCommand),
        "SLOW_SWAP" => Some(JankType::SlowSwap),
        "UNKNOWN" => Some(JankType::Unknown),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_frame::FrameSourceCapabilities;
    use aps_frame::model::FrameStages;
    use std::path::Path;
    use std::sync::atomic::{AtomicUsize, Ordering};

    static NEXT_DIRECTORY: AtomicUsize = AtomicUsize::new(0);

    struct TestDirectory(PathBuf);
    impl TestDirectory {
        fn new(name: &str) -> Self {
            let path = std::env::temp_dir().join(format!(
                "aps-frame-sessions-{name}-{}-{}",
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

    fn session(id: &str) -> FrameCaptureSession {
        FrameCaptureSession {
            id: id.into(),
            source: FrameSource::FrameMetrics,
            started_at_unix_ms: 1_234,
            package_name: Some("com.example".into()),
            device_serial: Some("device".into()),
            device_api_level: Some(35),
            agent_protocol: Some("1".into()),
            source_capabilities: Some(FrameSourceCapabilities {
                realtime: true,
                stage_breakdown: true,
                platform_jank_classification: true,
                expected_frame_deadline: true,
                app_state_labels: false,
            }),
            observed_refresh_rates_hz: vec![60.0, 120.0],
            imported_file: Some("framestats.txt".into()),
            imported_file_sha256: Some("abc123".into()),
            imported_at_unix_ms: Some(1_200),
            provenance_complete: false,
            provenance_warnings: vec!["missing sidecar".into()],
            perfetto_trace_file: Some("frame.perfetto-trace".into()),
            artifact: Some("artifact-1".into()),
        }
    }

    fn sample(frame_id: u64, session_id: &str) -> FrameSample {
        FrameSample {
            frame_id,
            session_id: session_id.into(),
            source: FrameSource::FrameMetrics,
            package_name: Some("com.example".into()),
            process_id: Some(42),
            activity_name: Some("com.example.MainActivity".into()),
            window_id: Some("window:1".into()),
            intended_vsync_ns: Some(100),
            actual_vsync_ns: Some(101),
            frame_completed_ns: Some(120),
            present_ns: Some(121),
            expected_duration_ns: Some(16),
            expected_duration_source: ExpectedDurationSource::PlatformDeadline,
            refresh_rate_hz: Some(120.0),
            frame_timeline_vsync_id: Some(99),
            total_duration_ns: Some(20),
            stages: FrameStages {
                input_ns: Some(2),
                draw_ns: Some(8),
                gpu_ns: Some(4),
                ..FrameStages::default()
            },
            platform_jank: Some(true),
            platform_jank_types: [JankType::PlatformReported].into_iter().collect(),
            platform_jank_rule_id: Some("jank-rule".into()),
            platform_jank_rule_version: Some("1.2.3".into()),
            states: BTreeMap::from([
                ("interaction".to_owned(), "scroll".to_owned()),
                ("frameTimelineJankTag".to_owned(), "Other Jank".to_owned()),
            ]),
            eligible_for_jank: false,
            dropped_before_sample: 3,
            layout_snapshot_id: Some("layout-1".into()),
            fragment_name: Some("FeedFragment".into()),
            page_name: Some("Feed".into()),
            interaction_state: Some("scroll".into()),
            render_thread_name: Some("RenderThread".into()),
            surface_flinger_jank_type: Some("SurfaceFlinger Scheduling".into()),
            jank_stats_jank: Some(true),
            jank_stats_reasons: ["slow draw".to_owned(), "missed deadline".to_owned()]
                .into_iter()
                .collect(),
            jank_stats_rule_id: Some("jankstats-default".into()),
            jank_stats_rule_version: Some("1.0.0".into()),
        }
    }

    #[test]
    fn reading_an_old_gfxinfo_session_rejects_the_corrupt_present_and_cached_total() {
        let directory = TestDirectory::new("bad-present");
        let store = FrameSessionStore::new(directory.path().to_path_buf());
        let session = session("session-1");
        let mut bad = sample(92, &session.id);
        bad.source = FrameSource::Gfxinfo;
        bad.intended_vsync_ns = Some(143_095_768_522_374);
        bad.frame_completed_ns = None;
        bad.present_ns = Some(4_902_229_767_048_270_160);
        bad.total_duration_ns = Some(4_902_086_671_279_747_786);
        bad.stages.gpu_ns = Some(i64::MAX);
        let mut other_source = bad.clone();
        other_source.frame_id = 93;
        other_source.source = FrameSource::FrameMetrics;
        store.save(&session, &[bad, other_source.clone()]).unwrap();

        let (_, frames) = store.load(&session.id).unwrap().unwrap();
        assert_eq!(frames.len(), 2);
        assert_eq!(frames[0].frame_id, 92);
        assert_eq!(frames[0].resolved_duration_ns(), None);
        assert_eq!(frames[0].present_ns, None);
        assert_eq!(frames[0].stages.gpu_ns, None);
        assert_eq!(frames[0].stages.draw_ns, Some(8));
        assert_eq!(frames[1], other_source);
    }

    #[test]
    fn round_trips_session_frames_stages_and_states() {
        let directory = TestDirectory::new("roundtrip");
        let store = FrameSessionStore::new(directory.path().to_path_buf());
        let session = session("session-1");
        let frames = vec![sample(7, "session-1"), sample(8, "session-1")];
        store.save(&session, &frames).unwrap();

        let (loaded_session, loaded_frames) = store.load("session-1").unwrap().unwrap();
        assert_eq!(
            loaded_session,
            FrameCaptureSession {
                perfetto_trace_file: None,
                ..session
            }
        );
        assert_eq!(loaded_frames, frames);
        assert!(store.load("missing").unwrap().is_none());
        let index_text = fs::read_to_string(directory.path().join(SESSIONS_FILE)).unwrap();
        assert!(index_text.contains("session-1"));
        assert!(index_text.contains("frameCount"));
    }

    #[test]
    fn newest_first_upsert_replaces_frames_and_keeps_one_entry() {
        let directory = TestDirectory::new("order");
        let store = FrameSessionStore::new(directory.path().to_path_buf());
        store
            .save(&session("first"), &[sample(1, "first")])
            .unwrap();
        store
            .save(&session("second"), &[sample(2, "second")])
            .unwrap();
        let ids: Vec<String> = store
            .read_index()
            .unwrap()
            .into_iter()
            .map(|entry| entry.session_id)
            .collect();
        assert_eq!(ids, ["second", "first"]);

        store
            .save(&session("first"), &[sample(3, "first"), sample(4, "first")])
            .unwrap();
        let entries = store.read_index().unwrap();
        assert_eq!(entries.len(), 2);
        assert_eq!(entries[0].session_id, "first");
        assert_eq!(entries[0].frame_count, 2);
        let (_, frames) = store.load("first").unwrap().unwrap();
        assert_eq!(
            frames
                .iter()
                .map(|frame| frame.frame_id)
                .collect::<Vec<_>>(),
            [3, 4]
        );
        assert_eq!(
            fs::read_dir(directory.path().join(FRAMES_DIR))
                .unwrap()
                .count(),
            2
        );
    }

    #[test]
    fn invalid_bounds_and_frame_ownership_fail_closed() {
        let directory = TestDirectory::new("bounds");
        let store = FrameSessionStore::new(directory.path().to_path_buf());
        assert!(store.save(&session(""), &[]).is_err());
        assert!(
            store
                .save(&session("session"), &[sample(1, "other")])
                .is_err()
        );
        assert!(
            store
                .save(
                    &session("session"),
                    &[sample(1, "session"), sample(1, "session")]
                )
                .is_err()
        );
        let too_many: Vec<FrameSample> = (0..=MAX_FRAMES_PER_SESSION as u64)
            .map(|frame_id| sample(frame_id, "session"))
            .collect();
        assert!(store.save(&session("session"), &too_many).is_err());
        assert!(store.load("session").unwrap().is_none());
    }

    #[test]
    fn saved_text_is_bounded_without_losing_the_session() {
        let directory = TestDirectory::new("bounded-text");
        let store = FrameSessionStore::new(directory.path().to_path_buf());
        let mut session = session("session-1");
        session.package_name = Some("x".repeat(4_000));
        session.provenance_warnings = vec!["w".repeat(4_000)];
        store.save(&session, &[sample(1, "session-1")]).unwrap();
        let (loaded, _) = store.load("session-1").unwrap().unwrap();
        assert_eq!(
            loaded.package_name.as_deref().map(str::len),
            Some(MAX_TEXT_BYTES)
        );
        assert_eq!(loaded.provenance_warnings[0].len(), MAX_TEXT_BYTES);
    }

    #[test]
    fn malformed_root_and_oversized_files_fail_closed_while_unknown_keys_are_tolerated() {
        let directory = TestDirectory::new("malformed");
        let store = FrameSessionStore::new(directory.path().to_path_buf());
        store
            .save(&session("session-1"), &[sample(1, "session-1")])
            .unwrap();

        let index_path = directory.path().join(SESSIONS_FILE);
        let mut value = aps_json::read(&index_path, Limits::default()).unwrap();
        if let Value::Object(object) = &mut value {
            object.insert("futureUnknownKey".into(), Value::from(7u64));
        }
        fs::write(
            &index_path,
            aps_json::to_vec(&value, Limits::default()).unwrap(),
        )
        .unwrap();
        assert!(store.load("session-1").unwrap().is_some());

        let frames_path = store
            .frames_dir()
            .join(&store.read_index().unwrap()[0].frames_file);
        fs::write(&frames_path, vec![b' '; MAX_FRAMES_JSON_BYTES as usize + 1]).unwrap();
        assert!(store.load("session-1").is_err());

        fs::write(&index_path, b"{not json").unwrap();
        assert!(store.load("session-1").is_err());
        assert!(store.save(&session("session-2"), &[]).is_err());
        fs::write(&index_path, vec![b' '; MAX_INDEX_BYTES as usize + 1]).unwrap();
        assert!(store.load("session-1").is_err());
    }

    fn write_json(path: &Path, value: &Value) {
        fs::write(path, aps_json::to_vec(value, frames_limits()).unwrap()).unwrap();
    }

    fn write_legacy(
        store: &FrameSessionStore,
        session: &FrameCaptureSession,
        frames: &[FrameSample],
    ) -> PathBuf {
        fs::create_dir_all(store.frames_dir()).unwrap();
        let name = frames_file_name(&session.id);
        let path = store.frames_dir().join(&name);
        fs::write(
            &path,
            frames_document(&session.id, frames.iter().map(sample_to_value).collect()).unwrap(),
        )
        .unwrap();
        write_json(
            &store.index_path(),
            &serde_json::json!({
                "version": LEGACY_INDEX_VERSION,
                "sessions": [{
                    "framesFile": name,
                    "frameCount": frames.len(),
                    "session": session_to_value(session),
                }],
            }),
        );
        path
    }

    #[test]
    fn legacy_load_checks_document_identity_counts_and_all_row_ids() {
        let dir = TestDirectory::new("legacy-identity");
        let store = FrameSessionStore::new(dir.path().to_path_buf());
        let local = session("local");
        let path = write_legacy(&store, &local, &[sample(1, "local"), sample(2, "local")]);
        let original = aps_json::read(&path, frames_limits()).unwrap();
        let (restored, _) = store.load("local").unwrap().unwrap();
        assert!(!restored.provenance_complete);
        assert!(
            restored
                .provenance_warnings
                .iter()
                .any(|w| w.contains("local display only"))
        );
        assert!(restored.perfetto_trace_file.is_none());

        for (field, value) in [
            ("version", Value::from(99)),
            ("sessionId", Value::from("other")),
            ("frames", Value::Array(vec![original["frames"][0].clone()])),
        ] {
            let mut altered = original.clone();
            altered[field] = value;
            write_json(&path, &altered);
            assert!(store.load("local").is_err(), "accepted invalid {field}");
        }
        for (field, value) in [
            ("frameId", Value::from(1)),
            ("sessionId", Value::from("other")),
            ("source", Value::from("bad source")),
        ] {
            let mut altered = original.clone();
            altered["frames"][1][field] = value;
            write_json(&path, &altered);
            assert!(store.load("local").is_err(), "accepted invalid row {field}");
        }
        write_json(&path, &original);
        store
            .save(&session("newer"), &[sample(1, "newer")])
            .unwrap();
        assert_eq!(store.read_index().unwrap()[1].frames_digest, None);
        assert!(!store.load("local").unwrap().unwrap().0.provenance_complete);
    }

    #[test]
    fn index_rejects_noncanonical_files_duplicate_ids_and_invalid_counts() {
        let dir = TestDirectory::new("index-identity");
        let store = FrameSessionStore::new(dir.path().to_path_buf());
        write_legacy(&store, &session("local"), &[sample(1, "local")]);
        let original = aps_json::read(&store.index_path(), index_limits()).unwrap();
        for (field, value) in [
            ("framesFile", Value::from("../other.json")),
            ("framesFile", Value::from(frames_file_name("other"))),
            ("frameCount", Value::from(MAX_FRAMES_PER_SESSION as u64 + 1)),
            ("frameCount", Value::from(2)),
        ] {
            let mut altered = original.clone();
            altered["sessions"][0][field] = value;
            write_json(&store.index_path(), &altered);
            assert!(store.load("local").is_err(), "accepted invalid {field}");
        }
        let mut invalid_version = original.clone();
        invalid_version["version"] = Value::from(99);
        write_json(&store.index_path(), &invalid_version);
        assert!(store.load("local").is_err());
        let mut duplicate = original.clone();
        duplicate["sessions"]
            .as_array_mut()
            .unwrap()
            .push(original["sessions"][0].clone());
        write_json(&store.index_path(), &duplicate);
        assert!(store.load("local").is_err());
    }

    #[test]
    fn row_digest_rejects_in_place_same_size_edits_and_replayed_generation() {
        let dir = TestDirectory::new("row-digest");
        let store = FrameSessionStore::new(dir.path().to_path_buf());
        let session = session("local");
        store.save(&session, &[sample(1, "local")]).unwrap();
        let old_path = store
            .frames_dir()
            .join(&store.read_index().unwrap()[0].frames_file);
        let old = fs::read(&old_path).unwrap();
        // An unpublished next-generation file must not disturb the index's
        // previous coherent view if publication is interrupted before commit.
        let newer = frames_document("local", vec![sample_to_value(&sample(2, "local"))]).unwrap();
        let next = bound_frames_file_name(
            "local",
            SourceDigest::hash_reader(newer.as_slice(), MAX_FRAMES_JSON_BYTES).unwrap(),
        );
        fs::write(store.frames_dir().join(&next), &newer).unwrap();
        assert_eq!(store.load("local").unwrap().unwrap().1[0].frame_id, 1);
        assert_eq!(old.len(), newer.len());
        store.save(&session, &[sample(2, "local")]).unwrap();
        assert!(!old_path.exists());
        let path = store
            .frames_dir()
            .join(&store.read_index().unwrap()[0].frames_file);
        fs::write(&path, old).unwrap();
        assert!(store.load("local").unwrap_err().contains("recorded digest"));
    }

    #[test]
    fn explicit_trace_selection_requires_analyzed_identity_and_bound_rows() {
        let dir = TestDirectory::new("trace-binding");
        let store = FrameSessionStore::new(dir.path().join("store"));
        let trace = dir.path().join("trace.pftrace");
        let other = dir.path().join("viewer.pftrace");
        fs::write(&trace, b"original").unwrap();
        fs::write(&other, b"not same").unwrap();
        let digest = SourceDigest::hash_file(&trace, 1000).unwrap();
        let text = digest.to_hex();
        let id = format!("perfetto-{}", &text[..32]);
        let mut session = session(&id);
        session.source = FrameSource::Perfetto;
        session.provenance_complete = true;
        session.imported_file_sha256 = Some(text.clone());
        session.artifact = Some(format!("frame-{}", &text[..32]));
        session.perfetto_trace_file = Some(other.display().to_string());
        let mut frame = sample(1, &id);
        frame.source = FrameSource::Perfetto;
        write_legacy(&store, &session, &[frame.clone()]);
        assert!(store.verify_trace(&id, &trace).is_err());
        store
            .save_with_trace(&session, &[frame.clone()], Some(digest))
            .unwrap();
        assert_eq!(store.verify_trace(&id, &trace), Ok(digest));
        assert!(store.verify_trace(&id, &other).is_err());
        fs::write(&trace, b"replaced").unwrap();
        assert!(store.verify_trace(&id, &trace).is_err());
        fs::write(&trace, b"original").unwrap();
        let path = store
            .frames_dir()
            .join(&store.read_index().unwrap()[0].frames_file);
        let mut rows = aps_json::read(&path, frames_limits()).unwrap();
        rows["frames"][0]["frameId"] = Value::from(2);
        write_json(&path, &rows);
        assert!(store.verify_trace(&id, &trace).is_err());
        store.save(&session, &[frame.clone()]).unwrap();
        assert!(store.verify_trace(&id, &trace).is_err());
        session.artifact = Some("unrelated".into());
        assert!(
            store
                .save_with_trace(&session, &[frame], Some(digest))
                .is_err()
        );
    }

    #[test]
    fn captured_trace_requires_matching_package_serial_and_pid_context() {
        let dir = TestDirectory::new("captured-scope");
        let store = FrameSessionStore::new(dir.path().join("store"));
        let trace = dir.path().join("capture.pftrace");
        fs::write(&trace, b"capture").unwrap();
        let digest = SourceDigest::hash_file(&trace, 1000).unwrap();
        let text = digest.to_hex();
        let suffix = format!(
            "{}-{:016x}",
            &text[..32],
            super::super::artifact::capture_context("device", 42, "com.example")
        );
        let id = format!("perfetto-{suffix}");
        let mut session = session(&id);
        session.source = FrameSource::Perfetto;
        session.provenance_complete = true;
        session.imported_file = None;
        session.imported_file_sha256 = None;
        session.imported_at_unix_ms = None;
        session.artifact = Some(format!("frame-{suffix}"));
        let mut frame = sample(1, &id);
        frame.source = FrameSource::Perfetto;
        store
            .save_with_trace(&session, &[frame.clone()], Some(digest))
            .unwrap();
        assert_eq!(store.verify_trace(&id, &trace), Ok(digest));
        frame.process_id = Some(43);
        assert!(
            store
                .save_with_trace(&session, &[frame], Some(digest))
                .is_err()
        );
        assert_eq!(store.verify_trace(&id, &trace), Ok(digest));
    }

    #[cfg(unix)]
    #[test]
    fn history_never_follows_a_symlink_to_external_rows() {
        let dir = TestDirectory::new("symlink");
        let store = FrameSessionStore::new(dir.path().join("store"));
        store
            .save(&session("local"), &[sample(1, "local")])
            .unwrap();
        let frames = store
            .frames_dir()
            .join(&store.read_index().unwrap()[0].frames_file);
        let outside = dir.path().join("outside.json");
        fs::rename(&frames, &outside).unwrap();
        std::os::unix::fs::symlink(outside, frames).unwrap();
        assert!(store.load("local").is_err());
    }
}

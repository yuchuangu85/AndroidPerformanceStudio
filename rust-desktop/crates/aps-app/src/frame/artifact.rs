//! Frame Analyzer capture-artifact metadata.
//!
//! Mirrors the Compose FrameCaptureArtifactFactory: content-addressed identity,
//! SHA-256 file evidence, provenance warnings, Perfetto tool version and the
//! FrameTimeline capabilities the trace actually exposed. Artifacts are saved
//! as bounded JSON under <data_dir>/capture-artifacts/<id>.json; no trace bytes
//! are copied and no device serial is persisted.

use aps_contracts::SourceDigest;
use aps_frame::FrameTimelineCapabilities;
use aps_json::{Limits, Map, Value};
use aps_platform::atomic_write;
use std::{
    fs,
    path::{Path, PathBuf},
};

const MAX_TRACE_BYTES: u64 = 8 * 1024 * 1024 * 1024;
const MAX_ID_BYTES: usize = 128;
const MAX_NAME_BYTES: usize = 512;
const MAX_TEXT_BYTES: usize = 512;
const MAX_WARNINGS: usize = 16;
const MAX_ARTIFACT_JSON_BYTES: u64 = 64 * 1024;

/// One imported/captured Perfetto FrameTimeline trace plus analysis provenance.
#[derive(Clone, Debug, PartialEq)]
pub(in crate::frame) struct FrameArtifact {
    pub(in crate::frame) id: String,
    pub(in crate::frame) display_name: String,
    pub(in crate::frame) sha256: String,
    pub(in crate::frame) provenance_warnings: Vec<String>,
    pub(in crate::frame) capabilities: aps_frame::FrameTimelineCapabilities,
    pub(in crate::frame) tool_version: Option<String>,
    pub(in crate::frame) trace_path: Option<PathBuf>,
}

impl FrameArtifact {
    /// Imported trace: producer unknown, completeness unknown until analyzed.
    pub(in crate::frame) fn imported(trace: &Path) -> Result<Self, String> {
        let evidence = trace_evidence(trace)?;
        Ok(Self {
            id: format!("frame-{}", &evidence.sha256[..32]),
            display_name: evidence.display_name,
            sha256: evidence.sha256,
            provenance_warnings: Vec::new(),
            capabilities: no_capabilities(),
            tool_version: None,
            trace_path: Some(evidence.path),
        })
    }

    /// Captured trace: identity is bound to the capture context so two devices
    /// or processes cannot collide on identical trace bytes.
    pub(in crate::frame) fn captured(
        trace: &Path,
        serial: &str,
        pid: i32,
        package: &str,
    ) -> Result<Self, String> {
        if pid <= 0 {
            return Err("The captured frame process id is invalid".to_owned());
        }
        let evidence = trace_evidence(trace)?;
        let context = capture_context(serial, pid, package);
        Ok(Self {
            id: format!("frame-{}-{:016x}", &evidence.sha256[..32], context),
            display_name: evidence.display_name,
            sha256: evidence.sha256,
            provenance_warnings: Vec::new(),
            capabilities: no_capabilities(),
            tool_version: None,
            trace_path: Some(evidence.path),
        })
    }

    /// Records the analyzer tool and the capabilities the trace exposed.
    /// Missing capabilities become explicit limitation warnings, mirroring
    /// Compose FrameCaptureArtifactFactory.analyzed.
    pub(in crate::frame) fn analyzed(
        mut self,
        tool_version: &str,
        capabilities: FrameTimelineCapabilities,
    ) -> Self {
        self.tool_version = Some(clipped(tool_version, MAX_TEXT_BYTES));
        self.capabilities = capabilities;
        let mut warnings: Vec<String> = self
            .provenance_warnings
            .into_iter()
            .filter(|warning| !warning.starts_with("frame."))
            .collect();
        warnings.extend(capability_warnings(capabilities));
        self.provenance_warnings = warnings.into_iter().take(MAX_WARNINGS).collect();
        self
    }

    /// Bounded JSON metadata; the trace bytes stay where they are.
    pub(in crate::frame) fn save(&self, data_dir: Option<&Path>) -> Result<(), String> {
        let Some(data_dir) = data_dir else {
            return Err("Frame artifact storage directory is unavailable".to_owned());
        };
        if !valid_id(&self.id) {
            return Err("Frame artifact id is invalid".to_owned());
        }
        let mut root = Map::new();
        root.insert("schemaVersion".into(), Value::from(1u64));
        root.insert("id".into(), Value::String(self.id.clone()));
        root.insert(
            "displayName".into(),
            Value::String(clipped(&self.display_name, MAX_NAME_BYTES)),
        );
        root.insert("sha256".into(), Value::String(clipped(&self.sha256, 64)));
        root.insert(
            "provenanceWarnings".into(),
            Value::Array(
                self.provenance_warnings
                    .iter()
                    .take(MAX_WARNINGS)
                    .map(|warning| Value::String(clipped(warning, MAX_TEXT_BYTES)))
                    .collect(),
            ),
        );
        root.insert(
            "capabilities".into(),
            Value::Object(capabilities_object(self.capabilities)),
        );
        root.insert(
            "toolVersion".into(),
            self.tool_version.as_ref().map_or(Value::Null, |value| {
                Value::String(clipped(value, MAX_TEXT_BYTES))
            }),
        );
        root.insert(
            "tracePath".into(),
            self.trace_path.as_ref().map_or(Value::Null, |path| {
                Value::String(clipped(&path.to_string_lossy(), MAX_NAME_BYTES * 8))
            }),
        );
        let bytes = aps_json::to_vec(
            &Value::Object(root),
            Limits {
                max_bytes: MAX_ARTIFACT_JSON_BYTES,
                max_depth: 16,
                max_nodes: 4_000,
                max_string_bytes: MAX_NAME_BYTES * 8,
                max_collection_items: 256,
            },
        )
        .map_err(|_| "Frame artifact metadata exceeds the storage budget".to_owned())?;
        let path = data_dir
            .join("capture-artifacts")
            .join(format!("{}.json", self.id));
        atomic_write(&path, &bytes)
            .map_err(|_| "Cannot write the frame artifact metadata".to_owned())
    }
}

struct TraceEvidence {
    path: PathBuf,
    sha256: String,
    display_name: String,
}

fn trace_evidence(trace: &Path) -> Result<TraceEvidence, String> {
    let metadata = fs::metadata(trace)
        .map_err(|_| "Frame trace file is missing or is not a regular file".to_owned())?;
    if !metadata.is_file() {
        return Err("Frame trace file is missing or is not a regular file".to_owned());
    }
    if metadata.len() > MAX_TRACE_BYTES {
        return Err("Frame trace exceeds the artifact byte budget".to_owned());
    }
    let path = trace
        .canonicalize()
        .map_err(|_| "Cannot resolve the frame trace file".to_owned())?;
    let sha256 = SourceDigest::hash_file(&path, MAX_TRACE_BYTES)
        .map_err(|_| "Cannot hash the frame trace file".to_owned())?
        .to_hex();
    let display_name = path
        .file_name()
        .and_then(|name| name.to_str())
        .map(|name| clipped(name, MAX_NAME_BYTES))
        .filter(|name| !name.trim().is_empty())
        .unwrap_or_else(|| "frame-timeline.perfetto-trace".to_owned());
    Ok(TraceEvidence {
        path,
        sha256,
        display_name,
    })
}

fn capture_context(serial: &str, pid: i32, package: &str) -> u64 {
    let mut bytes = Vec::new();
    bytes.extend_from_slice(serial.as_bytes());
    bytes.push(0);
    bytes.extend_from_slice(pid.to_string().as_bytes());
    bytes.push(0);
    bytes.extend_from_slice(package.as_bytes());
    bytes.iter().fold(0xcbf2_9ce4_8422_2325u64, |hash, byte| {
        (hash ^ u64::from(*byte)).wrapping_mul(0x0000_0100_0000_01b3)
    })
}

/// An unanalyzed artifact claims no capability; analyzed() replaces this with
/// the evidence-backed set from aps-frame.
fn no_capabilities() -> FrameTimelineCapabilities {
    FrameTimelineCapabilities {
        expected_timeline: false,
        actual_timeline: false,
        surface_correlation: false,
        jank_classification: false,
    }
}

fn capabilities_object(capabilities: FrameTimelineCapabilities) -> Map<String, Value> {
    let mut object = Map::new();
    object.insert(
        "expectedTimeline".into(),
        Value::Bool(capabilities.expected_timeline),
    );
    object.insert(
        "actualTimeline".into(),
        Value::Bool(capabilities.actual_timeline),
    );
    object.insert(
        "surfaceCorrelation".into(),
        Value::Bool(capabilities.surface_correlation),
    );
    object.insert(
        "jankClassification".into(),
        Value::Bool(capabilities.jank_classification),
    );
    object
}

fn capability_warnings(capabilities: FrameTimelineCapabilities) -> Vec<String> {
    let limitations = [
        ("frame.expected_timeline", capabilities.expected_timeline),
        ("frame.actual_timeline", capabilities.actual_timeline),
        (
            "frame.surface_correlation",
            capabilities.surface_correlation,
        ),
        (
            "frame.jank_classification",
            capabilities.jank_classification,
        ),
    ];
    limitations
        .into_iter()
        .filter(|(_, available)| !available)
        .map(|(capability, _)| {
            format!(
                "{capability}: The trace did not expose an app-to-SurfaceFlinger correlation for this capability."
            )
        })
        .collect()
}

fn valid_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= MAX_ID_BYTES
        && id
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"._-".contains(&byte))
}

fn clipped(value: &str, max_bytes: usize) -> String {
    let mut end = value.len().min(max_bytes);
    while !value.is_char_boundary(end) {
        end -= 1;
    }
    value[..end].to_owned()
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
                "aps-frame-artifact-{name}-{}-{}",
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

    fn trace_file(directory: &TestDirectory, bytes: &[u8]) -> PathBuf {
        let path = directory.path().join("frame.perfetto-trace");
        fs::write(&path, bytes).unwrap();
        path
    }

    fn all_capabilities() -> FrameTimelineCapabilities {
        FrameTimelineCapabilities {
            expected_timeline: true,
            actual_timeline: true,
            surface_correlation: true,
            jank_classification: true,
        }
    }

    #[test]
    fn imported_artifact_is_content_addressed_with_file_evidence() {
        let directory = TestDirectory::new("imported");
        let trace = trace_file(&directory, b"frame-trace-bytes");
        let artifact = FrameArtifact::imported(&trace).unwrap();
        assert_eq!(
            artifact.sha256,
            SourceDigest::hash_file(&trace, MAX_TRACE_BYTES)
                .unwrap()
                .to_hex()
        );
        assert_eq!(artifact.sha256.len(), 64);
        assert!(artifact.id.starts_with("frame-"));
        assert_eq!(artifact.display_name, "frame.perfetto-trace");
        assert_eq!(artifact.tool_version, None);
        assert_eq!(artifact.capabilities, no_capabilities());
        assert!(artifact.provenance_warnings.is_empty());
        let canonical = trace.canonicalize().unwrap();
        assert_eq!(artifact.trace_path.as_deref(), Some(canonical.as_path()));
        assert!(FrameArtifact::imported(&directory.path().join("missing")).is_err());
    }

    #[test]
    fn captured_artifact_binds_identity_to_capture_context_and_validates_pid() {
        let directory = TestDirectory::new("captured");
        let trace = trace_file(&directory, b"same-bytes");
        let first = FrameArtifact::captured(&trace, "serial", 42, "com.example").unwrap();
        let same = FrameArtifact::captured(&trace, "serial", 42, "com.example").unwrap();
        let other = FrameArtifact::captured(&trace, "serial", 43, "com.example").unwrap();
        assert_eq!(first.id, same.id);
        assert_ne!(first.id, other.id);
        assert!(FrameArtifact::captured(&trace, "serial", 0, "com.example").is_err());
        assert!(FrameArtifact::captured(&trace, "serial", -1, "com.example").is_err());
    }

    #[test]
    fn analyzed_records_tool_and_missing_capability_limitations() {
        let directory = TestDirectory::new("analyzed");
        let trace = trace_file(&directory, b"frame");
        let capabilities = FrameTimelineCapabilities {
            expected_timeline: true,
            actual_timeline: true,
            surface_correlation: false,
            jank_classification: false,
        };
        let artifact = FrameArtifact::imported(&trace)
            .unwrap()
            .analyzed("v59.1", capabilities);
        assert_eq!(artifact.tool_version.as_deref(), Some("v59.1"));
        assert_eq!(artifact.capabilities, capabilities);
        assert_eq!(artifact.provenance_warnings.len(), 2);
        assert!(
            artifact
                .provenance_warnings
                .iter()
                .any(|warning| warning.starts_with("frame.surface_correlation:"))
        );
        assert!(
            artifact
                .provenance_warnings
                .iter()
                .any(|warning| warning.starts_with("frame.jank_classification:"))
        );
    }

    #[test]
    fn save_writes_bounded_json_under_capture_artifacts_and_fails_without_a_directory() {
        let directory = TestDirectory::new("save");
        let trace = trace_file(&directory, b"frame");
        let artifact = FrameArtifact::imported(&trace)
            .unwrap()
            .analyzed("v59.1", all_capabilities());
        assert!(artifact.save(None).is_err());
        artifact.save(Some(directory.path())).unwrap();
        let path = directory
            .path()
            .join("capture-artifacts")
            .join(format!("{}.json", artifact.id));
        let value = aps_json::read(&path, Limits::default()).unwrap();
        assert_eq!(value["schemaVersion"].as_u64(), Some(1));
        assert_eq!(value["id"].as_str(), Some(artifact.id.as_str()));
        assert_eq!(value["sha256"].as_str(), Some(artifact.sha256.as_str()));
        assert_eq!(value["toolVersion"].as_str(), Some("v59.1"));
        assert_eq!(
            value["capabilities"]["surfaceCorrelation"].as_bool(),
            Some(true)
        );
        assert_eq!(
            value["provenanceWarnings"].as_array().map(Vec::len),
            Some(0)
        );
        assert!(!value.to_string().contains("serial"));
    }
}

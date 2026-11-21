//! Producer authority for offline Frame MCP. This state exists only on the
//! successful publication path of an actual target-scoped desktop capture.
use super::{artifact::FrameArtifact, capture::FrameProcessOption, export};
use aps_contracts::SourceDigest;
use aps_frame::{FrameAnalysisResult, FrameCaptureSession, FrameSource};
use serde_json::json;
use std::{
    collections::HashSet,
    path::Path,
    sync::{Arc, Weak},
};

#[derive(Clone)]
pub(super) struct Receipt {
    artifact_id: String,
    capture_id: String,
    trace_digest: String,
    device_digest: SourceDigest,
    api: u32,
    package: String,
    process: String,
    pid: i32,
    rates: Vec<f64>,
    frames: usize,
    probe_query_complete: bool,
}

impl Receipt {
    pub(super) fn for_capture(
        session: &FrameCaptureSession,
        frames: &[aps_frame::FrameSample],
        artifact: &FrameArtifact,
        target: &FrameProcessOption,
        measured_process: Option<&str>,
        probe_query_complete: bool,
    ) -> Option<Self> {
        let serial = session.device_serial.as_deref().filter(|s| !s.is_empty())?;
        let api = session.device_api_level.filter(|&api| api >= 31)?;
        let trace = artifact.trace_path.as_deref()?;
        let process = &target.name;
        let package = &target.package_name;
        let valid_label = |text: &str| {
            !text.is_empty()
                && text.len() <= 255
                && !text.contains(['/', '\\'])
                && !text.chars().any(char::is_control)
        };
        if measured_process != Some(process.as_str())
            || !valid_label(package)
            || !valid_label(process)
            || session.source != FrameSource::Perfetto
            || !session.provenance_complete
            || session.imported_file.is_some()
            || session.imported_file_sha256.is_some()
            || session.imported_at_unix_ms.is_some()
            || session.perfetto_trace_file.as_deref().map(Path::new) != Some(trace)
            || session.package_name.as_ref() != Some(package)
            || session.artifact.as_ref() != Some(&artifact.id)
            || target.pid <= 0
            || !artifact.matches_capture_target(serial, target.pid, package)
            || session.id != format!("perfetto-{}", artifact.id.strip_prefix("frame-")?)
            || !(1..=100_000).contains(&frames.len())
            || session.observed_refresh_rates_hz.len() > 64
            || session
                .observed_refresh_rates_hz
                .iter()
                .any(|h| !(1.0..=1000.0).contains(h))
        {
            return None;
        }
        let mut ids = HashSet::with_capacity(frames.len());
        if frames.iter().any(|s| {
            s.source != FrameSource::Perfetto
                || s.session_id != session.id
                || s.package_name.as_ref() != Some(package)
                || s.process_id != Some(target.pid)
                || s.states.get("aps.target.process") != Some(process)
                || !ids.insert(s.frame_id)
        }) {
            return None;
        }
        Some(Self {
            artifact_id: artifact.id.clone(),
            capture_id: session.id.clone(),
            trace_digest: artifact.sha256.clone(),
            device_digest: SourceDigest::hash_reader(serial.as_bytes(), 4096).ok()?,
            api,
            package: package.clone(),
            process: process.clone(),
            pid: target.pid,
            rates: session.observed_refresh_rates_hz.clone(),
            frames: frames.len(),
            probe_query_complete,
        })
    }

    pub(super) fn bind(self, analysis: &Arc<FrameAnalysisResult>) -> Publication {
        Publication {
            receipt: self,
            full_analysis: Arc::downgrade(analysis),
        }
    }

    fn save(&self, dir: &Path, report_digest: SourceDigest) -> Result<(), String> {
        let json = json!({
            "kind":"aps.frame.capture-receipt.v1", "captureId":self.capture_id,
            "reportDigest":report_digest.to_hex(), "traceDigest":self.trace_digest,
            "deviceDigest":self.device_digest.to_hex(), "androidApi":self.api,
            "packageName":self.package, "processName":self.process, "pid":self.pid,
            "refreshRatesHz":self.rates, "frameCount":self.frames,
            "probeQueryComplete":self.probe_query_complete
        });
        let path = dir
            .join("capture-artifacts")
            .join(format!("{}.frame-mcp.json", self.artifact_id));
        aps_platform::atomic_write(
            &path,
            &serde_json::to_vec(&json).map_err(|_| "Cannot serialize Frame MCP receipt")?,
        )
        .map_err(|_| "Frame report exported, but its Frame MCP receipt could not be saved".into())
    }
}

pub(super) struct Publication {
    full_analysis: Weak<FrameAnalysisResult>,
    receipt: Receipt,
}

impl Publication {
    /// Only the immutable, full capture result that was published qualifies.
    /// Not an arbitrary analysis with the same frame IDs or number of rows.
    pub(super) fn for_export(&self, analysis: &Arc<FrameAnalysisResult>) -> Option<Receipt> {
        Arc::ptr_eq(&self.full_analysis.upgrade()?, analysis).then(|| self.receipt.clone())
    }
}

pub(super) fn export_report(
    analysis: &FrameAnalysisResult,
    output: &Path,
    receipt: Option<&Receipt>,
    data_dir: Option<&Path>,
) -> Result<(), String> {
    match receipt {
        None => export::export_json(analysis, output),
        Some(receipt) => {
            let digest = export::export_bound_json(analysis, output)?
                .ok_or("Frame report exported, but it exceeds the Frame MCP input limit")?;
            let dir = data_dir
                .ok_or("Frame report exported, but Frame MCP receipt storage is unavailable")?;
            receipt.save(dir, digest)
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::frame::{perfetto, probe_tests};
    use aps_frame::{FrameJankAnalyzer, FrameSample};
    use aps_platform::{CancellationToken, TempWorkspace};
    use std::{fs, path::PathBuf};

    const SERIAL: &str = "fixture-private-device";
    const MARKER: &str = "APS.Frame/0000000000000001";

    fn target() -> FrameProcessOption {
        FrameProcessOption {
            pid: 30,
            package_name: "com.example".into(),
            name: "com.example".into(),
        }
    }

    fn capture(root: &Path, bytes: &[u8]) -> (PathBuf, FrameArtifact, FrameCaptureSession) {
        let trace = root.join("selected.pftrace");
        fs::write(&trace, bytes).unwrap();
        let target = target();
        let artifact =
            FrameArtifact::captured(&trace, SERIAL, target.pid, &target.package_name).unwrap();
        let mut session = probe_tests::session(&format!(
            "perfetto-{}",
            artifact.id.strip_prefix("frame-").unwrap()
        ));
        session.package_name = Some(target.package_name);
        session.device_serial = Some(SERIAL.into());
        session.perfetto_trace_file = artifact
            .trace_path
            .as_ref()
            .map(|p| p.display().to_string());
        session.artifact = Some(artifact.id.clone());
        (trace, artifact, session)
    }

    fn bind_rows(frames: &mut [FrameSample], session: &FrameCaptureSession) {
        let target = target();
        for f in frames {
            f.session_id = session.id.clone();
            f.package_name = session.package_name.clone();
            f.process_id = Some(target.pid);
            f.states
                .insert("aps.target.process".into(), target.name.clone());
        }
    }

    fn receipt_path(dir: &Path, artifact: &FrameArtifact) -> PathBuf {
        dir.join("capture-artifacts")
            .join(format!("{}.frame-mcp.json", artifact.id))
    }

    #[test]
    fn eligible_capture_publishes_receipt_for_exact_committed_export_only() {
        let tmp = TempWorkspace::create(&std::env::temp_dir()).unwrap();
        let root = tmp.path();
        let (_trace, artifact, session) = capture(root, b"selected trace");
        let mut frames = probe_tests::samples(MARKER);
        bind_rows(&mut frames, &session);
        let record = Receipt::for_capture(
            &session,
            &frames,
            &artifact,
            &target(),
            Some("com.example"),
            true,
        )
        .unwrap();
        let analysis = Arc::new(FrameJankAnalyzer::new().analyze(&frames));
        let published = record.bind(&analysis);
        let filtered = Arc::new(FrameJankAnalyzer::new().analyze(&frames[..1]));
        let reloaded = Arc::new(FrameJankAnalyzer::new().analyze(&frames));
        assert!(published.for_export(&filtered).is_none());
        assert!(published.for_export(&reloaded).is_none());
        let private = root.join("aps-private");
        let public = root.join("selected-dir").join("report.json");
        let receipt = receipt_path(&private, &artifact);
        let record = published.for_export(&analysis).unwrap();

        export_report(&reloaded, &public, None, Some(&private)).unwrap();
        assert!(!receipt.exists());
        let plain = fs::read(&public).unwrap();
        // An unsuccessful user export cannot create the independent receipt.
        assert!(
            export_report(
                &analysis,
                &public.join("not-a-file"),
                Some(&record),
                Some(&private)
            )
            .is_err()
        );
        assert!(!receipt.exists());
        export_report(&analysis, &public, Some(&record), Some(&private)).unwrap();
        let bytes = fs::read(&public).unwrap();
        assert_eq!(bytes, plain);
        let metadata = fs::read_to_string(&receipt).unwrap();
        assert!(!metadata.contains(SERIAL));
        assert!(!metadata.contains(root.to_str().unwrap()));
        let doc: serde_json::Value = serde_json::from_str(&metadata).unwrap();
        assert_eq!(
            doc["reportDigest"],
            SourceDigest::hash_reader(bytes.as_slice(), bytes.len() as u64)
                .unwrap()
                .to_hex()
        );
        assert_eq!(doc["traceDigest"], artifact.sha256);
        assert_eq!(doc["frameCount"], frames.len());
        assert_eq!(doc["probeQueryComplete"], true);
        assert_eq!(fs::read_dir(public.parent().unwrap()).unwrap().count(), 1);

        // Failure to write the separate receipt must be visible, but must
        // not roll back or truncate an otherwise successful user export.
        let err = export_report(&analysis, &public, Some(&record), Some(&public)).unwrap_err();
        assert!(err.contains("report exported"));
        assert_eq!(bytes, fs::read(&public).unwrap());
    }

    #[test]
    fn imported_reassociated_changed_and_foreign_rows_never_mint_receipts() {
        let temp = TempWorkspace::create(&std::env::temp_dir()).unwrap();
        let (trace, artifact, session) = capture(temp.path(), b"first");
        let digest = SourceDigest::from_hex(&artifact.sha256).unwrap();
        assert!(artifact.verify_analysis(digest).is_ok());
        assert!(
            artifact
                .verify_analysis(SourceDigest::from_bytes([0; 32]))
                .is_err()
        );
        fs::write(&trace, b"other").unwrap();
        assert!(artifact.verify_analysis(digest).is_err());

        let mut frames = probe_tests::samples(MARKER);
        bind_rows(&mut frames, &session);
        let can_bind = |s: &FrameCaptureSession, f: &[FrameSample]| {
            Receipt::for_capture(s, f, &artifact, &target(), Some("com.example"), false).is_some()
        };
        assert!(can_bind(&session, &frames));
        for observed in [None, Some("other.process")] {
            assert!(
                Receipt::for_capture(&session, &frames, &artifact, &target(), observed, true)
                    .is_none()
            );
        }
        for variant in 0..4 {
            let mut bad = session.clone();
            match variant {
                0 => {
                    bad.perfetto_trace_file =
                        Some(temp.path().join("viewer.pftrace").display().to_string())
                }
                1 => bad.imported_file = Some("reopened.json".into()),
                2 => bad.provenance_complete = false,
                _ => bad.device_serial = Some("foreign".into()),
            }
            assert!(!can_bind(&bad, &frames));
        }
        for variant in 0..4 {
            let mut bad = frames.clone();
            match variant {
                0 => bad[0].process_id = Some(999),
                1 => bad[0].session_id = "other session".into(),
                2 => bad[0].frame_id = bad[1].frame_id,
                _ => {
                    bad[0].states.remove("aps.target.process");
                }
            }
            assert!(!can_bind(&session, &bad));
        }
    }

    #[test]
    fn available_pinned_processor_capture_exports_and_mcp_reads_measured_overlaps() {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..");
        let exe = if cfg!(windows) {
            "trace_processor_shell.exe"
        } else {
            "trace_processor_shell"
        };
        if !root.join("build/perfetto-tools").join(exe).is_file() {
            return;
        }
        let tmp = TempWorkspace::create(&std::env::temp_dir()).unwrap();
        let bytes = include_bytes!("../../../aps-perfetto/tests/fixtures/frame-probes.pftrace");
        let (trace, artifact, session) = capture(tmp.path(), bytes);
        let cancel = CancellationToken::new();
        let mut measured = perfetto::analyze_with_tool(
            aps_perfetto::TraceProcessor::from_repository(&root, &cancel).unwrap(),
            &trace,
            Some(target().pid),
            &cancel,
        )
        .unwrap();
        assert!(measured.probe_query_complete);
        artifact.verify_analysis(measured.source_digest).unwrap();
        bind_rows(&mut measured.frames, &session);
        let record = Receipt::for_capture(
            &session,
            &measured.frames,
            &artifact,
            &target(),
            measured.measured_process.as_deref(),
            measured.probe_query_complete,
        )
        .unwrap();
        let frame = measured
            .frames
            .iter()
            .find(|f| perfetto::probes(f).iter().any(|p| p.marker == MARKER))
            .unwrap()
            .frame_id;
        let analysis = Arc::new(FrameJankAnalyzer::new().analyze(&measured.frames));
        let report = tmp.path().join("report.json");
        let private = tmp.path().join("private");
        let record = record.bind(&analysis).for_export(&analysis).unwrap();
        export_report(&analysis, &report, Some(&record), Some(&private)).unwrap();

        let mut server = aps_frame_mcp::FrameMcpServer::open_bound(
            &report,
            Some((&receipt_path(&private, &artifact), &trace)),
            None,
        )
        .unwrap();
        let summary = server.dispatch(&json!({"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"frame_session_summary","arguments":{}}})).unwrap();
        let result = server.dispatch(&json!({"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
            "name":"frame_trace_overlaps","arguments":{"sessionId":summary["result"]["structuredContent"]["sessionId"],"frameId":frame.to_string()}
        }})).unwrap();
        let data = &result["result"]["structuredContent"];
        assert_eq!(data["items"][0]["marker"], MARKER, "{result}");
        assert_eq!(data["items"][0]["observedScheduledNs"], 7);
        assert_eq!(data["items"][0]["overlapNs"], 20);
        assert_eq!(data["captureId"], session.id);
    }
}

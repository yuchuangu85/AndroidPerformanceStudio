//! Exercise the production source registry, exporter, approval, evidence and review
//! with a throwaway Java project. No registered user workspace or secrets touched.
use super::{probe_receipt, trace_patch};
use aps_frame::{FrameCaptureSession, FrameJankAnalyzer, FrameSource, FrameTimelineRow};
use std::{
    fs,
    io::Write,
    path::Path,
    process::{Command, Stdio},
};

const PATH: &str = "Main.java";
const SRC: &str = include_str!("../../tests/fixtures/frame-probe/Main.java");
const PKG: &str = "aps.test.frameverify";

pub(super) fn session(id: &str) -> FrameCaptureSession {
    FrameCaptureSession {
        id: id.into(),
        source: FrameSource::Perfetto,
        started_at_unix_ms: 1,
        package_name: Some(PKG.into()),
        device_serial: Some("device".into()),
        device_api_level: Some(34),
        agent_protocol: None,
        source_capabilities: None,
        observed_refresh_rates_hz: vec![60.0],
        imported_file: None,
        imported_file_sha256: None,
        imported_at_unix_ms: None,
        provenance_complete: true,
        provenance_warnings: vec![],
        perfetto_trace_file: None,
        artifact: None,
    }
}

pub(super) fn samples(marker: &str) -> Vec<aps_frame::FrameSample> {
    let rows = (1..=4)
        .map(|id| FrameTimelineRow {
            frame_id: id,
            expected_ts: Some(id as i64 * 100),
            expected_dur: Some(16),
            actual_ts: Some(id as i64 * 100),
            actual_dur: Some(22),
            surface_token: None,
            layer_name: None,
            jank_type: Some("App Deadline Missed".into()),
            jank_tag: None,
            present_type: None,
            process_name: Some(PKG.into()),
            surface_flinger_jank_type: None,
        })
        .collect();
    let mut frames = aps_frame::map_timeline_rows(rows).frames;
    for f in &mut frames {
        f.package_name = Some(PKG.into());
        f.process_id = Some(30);
        f.states.insert("aps.target.process".into(), PKG.into());
        f.states
            .insert("aps.trace.0".into(), format!("{marker} 30 10 -1 Main"));
    }
    frames
}

fn apply(root: &Path, patch: &str) {
    for args in [vec!["apply", "--check", "-"], vec!["apply", "-"]] {
        let mut child = Command::new("git")
            .args(args)
            .current_dir(root)
            .stdin(Stdio::piped())
            .spawn()
            .unwrap();
        child
            .stdin
            .take()
            .unwrap()
            .write_all(patch.as_bytes())
            .unwrap();
        assert!(child.wait().unwrap().success());
    }
}

fn patch() -> (usize, trace_patch::TracePatch) {
    let line = trace_patch::trace_methods(PATH, SRC)
        .unwrap()
        .into_iter()
        .find(|(_, name)| name == "paint")
        .unwrap()
        .0;
    (line, trace_patch::trace_patch(PATH, SRC, line).unwrap())
}

#[test]
fn probe_to_verified_optimization_and_archived_navigation() {
    workflow(false);
}

#[test]
#[ignore = "requires the fixture APK running on a disposable emulator and an explicitly started loopback model"]
fn live_probe_to_local_model() {
    workflow(true);
}

fn workflow(live: bool) {
    let t = aps_platform::TempWorkspace::create(&std::env::temp_dir()).unwrap();
    let root = t.path();
    let project = root.join("project");
    fs::create_dir(&project).unwrap();
    fs::write(project.join(PATH), SRC).unwrap();
    let (registry, location) = crate::source::local_probe_fixture(root, project.clone(), PATH);
    let (line, p) = patch();
    let dir = root.join("frame");
    let receipt = probe_receipt::Receipt {
        marker: p.marker.clone(),
        location: location.clone(),
        line,
        method: "paint".into(),
        package: Some(PKG.into()),
        instrumented: p.instrumented,
    };
    probe_receipt::save(&dir, &receipt).unwrap();
    probe_receipt::save(&dir, &receipt).unwrap();
    assert_eq!(
        probe_receipt::lookup(&dir, &p.marker, PKG).unwrap(),
        receipt
    );
    assert!(probe_receipt::lookup(&dir, &p.marker, "other.app").is_err());
    let names = probe_receipt::labels(&dir, PKG).unwrap();
    assert!(names[&p.marker].contains("Main.java"));
    assert!(probe_receipt::labels(&dir, "other.app").unwrap().is_empty());
    assert!(registry.current(&location, p.instrumented).is_err());
    apply(&project, &p.diff);
    registry.fixture_refresh(location.workspace_id);
    let src = registry.current(&location, p.instrumented).unwrap();
    assert_eq!(
        aps_source::digest_text(&src.read().unwrap()),
        p.instrumented
    );
    assert!(src.read_for_ai().is_err());
    registry.fixture_allow(location.workspace_id, true);

    let mut frames = if live {
        let serial = std::env::var("APS_FRAME_TEST_EMULATOR").unwrap();
        assert!(serial.starts_with("emulator-"));
        let pid = std::env::var("APS_FRAME_TEST_PID")
            .unwrap()
            .parse()
            .unwrap();
        let target = super::capture::FrameProcessOption {
            pid,
            name: PKG.into(),
            package_name: PKG.into(),
        };
        let c = aps_platform::CancellationToken::new();
        let capture = futures::executor::block_on(super::capture::capture_frame_timeline(
            &serial,
            &target,
            3000,
            Some(dir.clone()),
            &c,
        ))
        .unwrap();
        let tool = aps_perfetto::TraceProcessor::from_repository(
            Path::new(env!("CARGO_MANIFEST_DIR"))
                .join("../../..")
                .as_path(),
            &c,
        )
        .unwrap();
        let measured =
            super::perfetto::analyze_with_tool(tool, &capture.trace_file, Some(pid), &c).unwrap();
        assert!(measured.probe_warning.is_none());
        let mut frames = measured.frames;
        for f in &mut frames {
            f.package_name = Some(PKG.into());
            f.process_id = Some(pid);
        }
        eprintln!(
            "Emulator: {} FrameTimeline rows, {} rows with selected probe",
            frames.len(),
            frames
                .iter()
                .filter(|f| super::perfetto::probes(f)
                    .iter()
                    .any(|x| x.marker == p.marker))
                .count()
        );
        frames
    } else {
        samples(&p.marker)
    };
    let session = session("perfetto");
    for f in &mut frames {
        f.session_id = session.id.clone()
    }
    let analysis = FrameJankAnalyzer::new().analyze(&frames);
    let selected = analysis
        .frames
        .iter()
        .find(|f| super::can_probe(f) && !super::perfetto::probes(&f.sample).is_empty())
        .unwrap();
    let job = super::optimize::OptimizeJob::from_capture(
        dir.clone(),
        &session,
        &analysis,
        selected,
        &p.marker,
    )
    .unwrap();
    let (request, guard) = job.prepare(registry.clone()).unwrap();
    let cand = &request.candidates[0];
    let archived = registry.fixture_candidate(&cand.id);
    assert_eq!(archived.location.content_hash, p.instrumented);
    assert_eq!(
        archived.location.range.unwrap().start_line as u32,
        cand.start_line.unwrap()
    );
    assert_eq!(
        archived.confidence,
        aps_source::ResolutionConfidence::Probable
    );
    let excerpt = cand.snippet.as_ref().unwrap().clone();
    assert!(!excerpt.contains("onCreate"));
    let old = excerpt.lines().find(|l| l.contains("sleep")).unwrap();
    let response = serde_json::json!({"summary":"fixture result","findings":[{
        "id":"one","severity":"INFO","title":"fixture","explanation":"fixture","analysisConfidence":0.5,
        "performanceEvidenceIds":[request.evidence.last().unwrap().id],
        "sourceCandidateIds":[cand.id], "recommendation":aps_ai::frame_edit::example(old,"")
    }]});
    let (url, received) = if live {
        let port = std::env::var("APS_FRAME_TEST_MODEL_PORT")
            .map(|p| p.parse::<u16>().unwrap())
            .unwrap_or(12345);
        (format!("http://127.0.0.1:{port}/v1/responses"), None)
    } else {
        let body=serde_json::json!({"error":null,"status":"completed","model":"fixture","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":response.to_string()}]}]}).to_string();
        let reply = format!(
            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
            body.len()
        );
        let (url, rx) = crate::net::tests::serve_once(reply.into_bytes());
        (url.replace("/v1/models", "/v1/responses"), Some(rx))
    };
    let options = aps_ai::runtime::PrepareOptions {
        conversation_id: "fixture".into(),
        context: Some(request),
        question: Some("The sleep is an artificial delay in this drawing benchmark, not a business requirement. Suggest a bounded change that preserves the drawing.".into()),
        include_source: true,
        direct_chat: false,
        parent_turn: None,
        parent_conversation: None,
        report_format: aps_ai::ReportFormat::Markdown,
        report_language: aps_ai::ReportLanguage::English,
        agent_id: "aps.main".into(),
    };
    let runtime = aps_ai::runtime::AiRuntime::open_with_secret_store(
        root.join("ai"),
        aps_ai::AiSecretStore::memory(),
    )
    .unwrap();
    runtime
        .update_preferences(|p| {
            p.active_provider = aps_ai::ProviderKind::Custom;
            p.model = "aps-frame-smoke".into();
            p.connection_mut(aps_ai::ProviderKind::Custom).endpoint = url;
            p.connection_mut(aps_ai::ProviderKind::Custom).model = "aps-frame-smoke".into();
        })
        .unwrap();
    let frozen = runtime.prepare(options).unwrap();
    assert_eq!(frozen.view.sources.len(), 1);
    assert_eq!(
        frozen.view.sources[0].snippet.as_deref(),
        Some(excerpt.as_str())
    );
    assert!(frozen.view.history.is_empty());
    runtime
        .set_credential(
            aps_ai::ProviderKind::Custom,
            runtime
                .connection(aps_ai::ProviderKind::Custom)
                .credential_kind,
            "local-fixture-only",
        )
        .unwrap();
    runtime
        .set_http_client(std::sync::Arc::new(crate::net::CurlHttpClient::new(
            root.into(),
        )))
        .unwrap();
    let mut result = futures::executor::block_on(runtime.execute(frozen)).unwrap();
    if let Some(rx) = received {
        let wire = String::from_utf8(rx.recv().unwrap()).unwrap();
        assert!(wire.contains("SystemClock.sleep"));
        assert!(!wire.contains("onCreate"));
    }
    let reviewed = guard
        .review(&result)
        .unwrap()
        .expect("expected one cited, reviewable edit");
    apply(&project, &reviewed.diff);
    assert!(guard.validate().is_err(), "file was changed after approval");
    if live {
        eprintln!(
            "Local provider returned a reviewed patch: {}\n{}",
            reviewed.label, reviewed.diff
        );
    }
    assert!(
        fs::read_to_string(project.join(PATH))
            .unwrap()
            .contains("canvas.drawColor")
    );
    // Restore the disposable instrumented fixture to exercise permission/citation failures independently.
    fs::write(project.join(PATH), SRC).unwrap();
    apply(&project, &p.diff);
    result.findings[0].candidate_ids.clear();
    assert!(guard.review(&result).is_err());
    registry.fixture_allow(location.workspace_id, false);
    assert!(guard.validate().is_err());

    let mut ambiguous = receipt.clone();
    ambiguous.location.workspace_id =
        aps_source::SourceWorkspaceId::from_digest(aps_source::digest_text("another workspace"));
    probe_receipt::save(&dir, &ambiguous).unwrap();
    assert!(probe_receipt::lookup(&dir, &p.marker, PKG).is_err());
    assert!(probe_receipt::labels(&dir, PKG).unwrap().is_empty());
    assert!(
        !fs::read_to_string(dir.join("probe-receipts-v1.json"))
            .unwrap()
            .contains("SystemClock")
    );
}

//! Authored (not executed by repo policy) tests for the agent-first capture
//! bridge: descriptor validation, the fake-agent PING/CAPTURE wire protocol,
//! remote errors, truncation/oversize bounds, the RAII adb-forward removal and
//! multi-window full-display screenshot replacement.
#![cfg(unix)]

use super::*;
use aps_contracts::SourceDigest;
use aps_json::Value;
use serde_json::json;
use std::fs;
use std::io::{BufRead, BufReader, Cursor, Write as _};
use std::net::TcpListener;
use std::os::unix::fs::PermissionsExt;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::mpsc;
use std::thread;

const TOKEN: &str = "00112233445566778899aabbccddeeff";
const PACKAGE: &str = "example.app";
const SERIAL: &str = "test-serial";

static NEXT_FIXTURE: AtomicU64 = AtomicU64::new(0);

struct Fixture {
    directory: PathBuf,
}

impl Fixture {
    fn new(name: &str) -> Self {
        let directory = std::env::temp_dir().join(format!(
            "aps-agent-bridge-{}-{}-{}",
            std::process::id(),
            name,
            NEXT_FIXTURE.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir_all(&directory).unwrap();
        Self { directory }
    }

    fn write(&self, name: &str, bytes: impl AsRef<[u8]>) {
        fs::write(self.directory.join(name), bytes).unwrap();
    }

    fn log(&self) -> String {
        fs::read_to_string(self.directory.join("adb.log")).unwrap_or_default()
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.directory);
    }
}

fn session_json() -> String {
    format!(
        "{{\"protocolMajor\":1,\"protocolMinor\":1,\"socketName\":\"agentperf_example_app\",\"token\":\"{TOKEN}\"}}"
    )
}

#[derive(Clone, Copy)]
enum AdbBehaviour {
    Ok,
    RunAsFails,
    ForwardFails,
    ScreencapFails,
}

/// Fake adb shell script in the same fixture style as compatible/tests.rs. The
/// script records every argument vector so the test can assert that the forward
/// was created and later removed.
fn fake_adb(fixture: &Fixture, behaviour: AdbBehaviour) -> PathBuf {
    let mut script = String::from(
        "#!/bin/sh\nlog=\"$(dirname \"$0\")/adb.log\"\nprintf '%s\\n' \"$*\" >> \"$log\"\n[ \"$1\" = '-s' ] && [ \"$2\" = 'test-serial' ] || exit 21\nshift 2\ncase \"$*\" in\n",
    );
    script.push_str(" 'shell run-as example.app cat files/agentperf/session.json')");
    script.push_str(match behaviour {
        AdbBehaviour::RunAsFails => " exit 24 ;;\n",
        _ => " cat \"$(dirname \"$0\")/session.json\" ;;\n",
    });
    script.push_str(" 'forward --remove tcp:'*) exit 0 ;;\n");
    script.push_str(" 'forward tcp:'*' localabstract:'*) ");
    script.push_str(match behaviour {
        AdbBehaviour::ForwardFails => "exit 25 ;;\n",
        _ => "exit 0 ;;\n",
    });
    script.push_str(" 'exec-out screencap -p') ");
    script.push_str(match behaviour {
        AdbBehaviour::ScreencapFails => "exit 26 ;;\n",
        _ => "cat \"$(dirname \"$0\")/screen.png\" ;;\n",
    });
    script.push_str(" *) exit 22 ;;\nesac\n");
    let executable = fixture.directory.join("adb");
    fs::write(&executable, script).unwrap();
    fs::set_permissions(&executable, fs::Permissions::from_mode(0o700)).unwrap();
    executable
}

fn fixture_with_adb(name: &str, behaviour: AdbBehaviour, screenshot: &[u8]) -> (Fixture, PathBuf) {
    let fixture = Fixture::new(name);
    fixture.write("session.json", session_json());
    fixture.write("screen.png", screenshot);
    let adb = fake_adb(&fixture, behaviour);
    (fixture, adb)
}

fn screenshot(width: u32, height: u32) -> Vec<u8> {
    let mut bytes = Vec::new();
    {
        let mut encoder = png::Encoder::new(&mut bytes, width, height);
        encoder.set_color(png::ColorType::Rgba);
        encoder.set_depth(png::BitDepth::Eight);
        encoder
            .write_header()
            .unwrap()
            .write_image_data(&vec![255; width as usize * height as usize * 4])
            .unwrap();
    }
    bytes
}

enum AgentReply {
    Frame {
        snapshot: Vec<u8>,
        screenshot: Vec<u8>,
    },
    Error(String),
    WrongPong,
    Truncated {
        header: String,
        payload: Vec<u8>,
    },
    MalformedHeader(String),
}

fn spawn_fake_agent(
    listener: TcpListener,
    reply: AgentReply,
    requests: mpsc::Sender<String>,
) -> thread::JoinHandle<()> {
    thread::spawn(move || {
        let (mut ping, _) = listener.accept().unwrap();
        let mut ping_reader = BufReader::new(ping.try_clone().unwrap());
        let mut line = String::new();
        ping_reader.read_line(&mut line).unwrap();
        requests.send(line.trim_end().to_owned()).unwrap();
        match &reply {
            AgentReply::WrongPong => {
                ping.write_all(b"PONG 2.0\n").unwrap();
                return;
            }
            _ => ping.write_all(b"PONG 1.0\n").unwrap(),
        }
        drop(ping_reader);
        let (mut capture, _) = listener.accept().unwrap();
        let mut capture_reader = BufReader::new(capture.try_clone().unwrap());
        let mut request = String::new();
        capture_reader.read_line(&mut request).unwrap();
        requests.send(request.trim_end().to_owned()).unwrap();
        match reply {
            AgentReply::Frame {
                snapshot,
                screenshot,
            } => {
                let header = format!("CAPTURE {} {}\n", snapshot.len(), screenshot.len());
                capture.write_all(header.as_bytes()).unwrap();
                capture.write_all(&snapshot).unwrap();
                capture.write_all(&screenshot).unwrap();
            }
            AgentReply::Error(message) => capture.write_all(message.as_bytes()).unwrap(),
            AgentReply::Truncated { header, payload } => {
                capture.write_all(header.as_bytes()).unwrap();
                capture.write_all(&payload).unwrap();
            }
            AgentReply::MalformedHeader(header) => capture.write_all(header.as_bytes()).unwrap(),
            AgentReply::WrongPong => {}
        }
        capture.flush().unwrap();
    })
}

fn run_with_agent(adb: &Path, reply: AgentReply) -> (Result<AgentCapture, String>, Vec<String>) {
    let listener = TcpListener::bind(("127.0.0.1", 0)).unwrap();
    let port = listener.local_addr().unwrap().port();
    let (sender, receiver) = mpsc::channel();
    let handle = spawn_fake_agent(listener, reply, sender);
    let result = capture_agent_with(adb, SERIAL, PACKAGE, 7, &CancellationToken::new(), || {
        Ok(port)
    });
    handle.join().unwrap();
    (result, receiver.try_iter().collect())
}

fn captured(result: Result<AgentCapture, String>) -> InspectorProtocolFrame {
    match result {
        Ok(AgentCapture::Captured(frame)) => frame,
        Ok(AgentCapture::Unavailable(reason)) => panic!("unexpectedly unavailable: {reason}"),
        Err(error) => panic!("unexpectedly failed: {error}"),
    }
}

fn view_node(id: &str, class_name: &str, width: i64, height: i64) -> Value {
    json!({
        "type": "view",
        "id": id,
        "className": class_name,
        "bounds": {"left": 0, "top": 0, "right": width, "bottom": height},
        "visible": true,
        "alpha": 1.0,
    })
}

fn window_value(id: &str, root: Value, width: u32, height: u32) -> Value {
    json!({
        "id": id,
        "title": id,
        "type": "ACTIVITY",
        "bounds": {"left": 0, "top": 0, "right": width, "bottom": height},
        "root": root,
    })
}

fn snapshot_value(windows: Vec<Value>, root: Value, width: u32, height: u32) -> Value {
    json!({
        "protocolVersion": {"major": 1, "minor": 1},
        "packageName": PACKAGE,
        "capturedAtEpochMillis": 42,
        "display": {"widthPx": width, "heightPx": height, "density": 2.0},
        "capabilities": {
            "viewHierarchy": true,
            "composeSemantics": false,
            "screenshots": true,
            "timeline": false,
        },
        "root": root,
        "windows": windows,
        "defaultWindowId": "window:main",
    })
}

fn single_window_snapshot() -> Value {
    let root = view_node("root", "android.view.FrameLayout", 2, 3);
    snapshot_value(
        vec![window_value("window:main", root.clone(), 2, 3)],
        root,
        2,
        3,
    )
}

#[test]
fn live_agent_projection_exports_without_device_or_input_archive() {
    let cancel = CancellationToken::new();
    let snapshot = single_window_snapshot();
    let width = snapshot["display"]["widthPx"].as_u64().unwrap() as u32;
    let height = snapshot["display"]["heightPx"].as_u64().unwrap() as u32;
    let png = screenshot(width, height);
    // One-window projection is pure: this sentinel adb path must never run.
    let frame = project_capture(
        CapturePayload {
            snapshot: encoded(&snapshot),
            screenshot: png.clone(),
        },
        Path::new("/must-not-execute-adb"),
        SERIAL,
        PACKAGE,
        17,
        &cancel,
    )
    .unwrap();
    let temp = aps_platform::TempWorkspace::create(&std::env::temp_dir()).unwrap();
    let path = temp.path().join("agent.zip");
    frame.archive.export_archive(&path, &cancel).unwrap();
    let imported = crate::InspectorArchive::open(&path, &cancel).unwrap();
    assert_eq!(
        imported.frame.package_name,
        frame.archive.frame.package_name
    );
    assert_eq!(
        imported.frame.captured_at_epoch_millis,
        frame.archive.frame.captured_at_epoch_millis
    );
    assert_eq!(
        imported.frame.protocol_minor,
        frame.archive.frame.protocol_minor
    );
    assert_eq!(
        imported.windows[0].nodes.len(),
        frame.archive.windows[0].nodes.len()
    );
    assert_eq!(imported.screenshot_png.as_deref(), Some(png.as_slice()));
    assert_eq!(
        imported.capabilities.compose_semantics,
        frame.archive.capabilities.compose_semantics
    );
    assert_eq!(
        imported.capabilities.timeline,
        frame.archive.capabilities.timeline
    );
}

fn multi_window_snapshot() -> Value {
    let main = view_node("root:main", "android.view.FrameLayout", 2, 3);
    let dialog = view_node("root:dialog", "android.widget.FrameLayout", 2, 2);
    snapshot_value(
        vec![
            window_value("window:main", main.clone(), 2, 3),
            window_value("window:dialog", dialog, 2, 2),
        ],
        main,
        2,
        3,
    )
}

fn encoded(value: &Value) -> Vec<u8> {
    aps_json::to_vec(value, crate::json_limits(MAX_SNAPSHOT_BYTES as u64)).unwrap()
}

fn frame_header(header: &str, token: &str) -> Option<(usize, usize)> {
    match parse_capture_header(header, token).ok()? {
        CaptureHeader::Frame {
            snapshot_len,
            screenshot_len,
        } => Some((snapshot_len, screenshot_len)),
        CaptureHeader::Remote { .. } => None,
    }
}

fn wait_for_client_error(result: Result<AgentCapture, String>) -> String {
    match result {
        Err(error) => error,
        Ok(AgentCapture::Captured(_)) => panic!("unexpectedly captured a frame"),
        Ok(AgentCapture::Unavailable(reason)) => {
            panic!("unexpectedly unavailable instead of a hard error: {reason}")
        }
    }
}

#[test]
fn descriptor_parsing_accepts_the_agent_document() {
    let descriptor = parse_session_descriptor(session_json().as_bytes()).unwrap();
    assert_eq!(descriptor.socket_name, "agentperf_example_app");
    assert_eq!(descriptor.token, TOKEN);
}

#[test]
fn descriptor_parsing_rejects_malformed_and_unsupported_documents() {
    assert!(parse_session_descriptor(b"").is_err());
    assert!(parse_session_descriptor(b"not json").is_err());
    assert!(parse_session_descriptor(b"[]").is_err());
    assert!(
        parse_session_descriptor(
            br#"{"protocolMajor":2,"protocolMinor":1,"socketName":"agentperf_example_app","token":"x"}"#
        )
        .err()
        .unwrap()
        .contains("unsupported agent protocol major version 2")
    );
    assert!(
        parse_session_descriptor(
            br#"{"protocolMajor":1,"socketName":"agentperf_example_app","token":"x"}"#
        )
        .err()
        .unwrap()
        .contains("protocolMinor")
    );
    for socket in ["", "bad socket", "bad/socket", "bad;socket"] {
        let document = format!(
            "{{\"protocolMajor\":1,\"protocolMinor\":1,\"socketName\":\"{socket}\",\"token\":\"{TOKEN}\"}}"
        );
        assert!(
            parse_session_descriptor(document.as_bytes()).is_err(),
            "socket {socket:?} must be rejected"
        );
    }
    for token in ["", "has space", "line\nbreak", "tab\there"] {
        let escaped = serde_json::to_string(token).unwrap();
        let document = format!(
            "{{\"protocolMajor\":1,\"protocolMinor\":1,\"socketName\":\"agentperf_example_app\",\"token\":{escaped}}}"
        );
        let error = parse_session_descriptor(document.as_bytes()).err().unwrap();
        assert!(error.contains("invalid session token"), "{error}");
        assert!(!error.contains(TOKEN));
    }
    let oversized = "a".repeat(MAX_TOKEN_BYTES + 1);
    let document = format!(
        "{{\"protocolMajor\":1,\"protocolMinor\":1,\"socketName\":\"agentperf_example_app\",\"token\":\"{oversized}\"}}"
    );
    assert!(parse_session_descriptor(document.as_bytes()).is_err());
}

#[test]
fn capture_header_parsing_matches_the_frame_codec() {
    assert_eq!(frame_header("CAPTURE 0 0", TOKEN), Some((0, 0)));
    assert_eq!(
        frame_header("CAPTURE 8388608 33554432", TOKEN),
        Some((8 * 1024 * 1024, 32 * 1024 * 1024))
    );
    for header in [
        "CAPTURE 8388609 0",
        "CAPTURE 0 33554433",
        "CAPTURE -1 0",
        "CAPTURE +1 0",
        "CAPTURE 1",
        "CAPTURE 1 2 3",
        "CAPTURE a 0",
        "CAPTURE  1 0",
        "SNAPSHOT 1 0",
        "ERROR CAPTURE_FAILED",
        "ERROR  message",
        "ERROR   ",
    ] {
        assert!(
            parse_capture_header(header, TOKEN).is_err(),
            "header {header:?} must be rejected"
        );
    }
    match parse_capture_header("ERROR CAPTURE_FAILED No resumed activity", TOKEN).unwrap() {
        CaptureHeader::Remote { code, message } => {
            assert_eq!(code, "CAPTURE_FAILED");
            assert_eq!(message, "No resumed activity");
        }
        CaptureHeader::Frame { .. } => panic!("expected a remote error"),
    }
    match parse_capture_header(&format!("ERROR CAPTURE_FAILED token {TOKEN}"), TOKEN).unwrap() {
        CaptureHeader::Remote { message, .. } => {
            assert_eq!(message, "token [redacted]");
        }
        CaptureHeader::Frame { .. } => panic!("expected a remote error"),
    }
}

#[test]
fn agent_capture_projects_single_window_frame_with_screenshot() {
    let png = screenshot(2, 3);
    let (fixture, adb) = fixture_with_adb("single", AdbBehaviour::Ok, &png);
    let snapshot = single_window_snapshot();
    let (result, requests) = run_with_agent(
        &adb,
        AgentReply::Frame {
            snapshot: encoded(&snapshot),
            screenshot: png.clone(),
        },
    );
    let frame = captured(result);
    assert!(frame.from_agent);
    assert_eq!(frame.generation, 7);
    assert_eq!(frame.archive.frame.package_name, PACKAGE);
    assert_eq!(frame.archive.frame.captured_at_epoch_millis, 42);
    assert_eq!(frame.archive.frame.protocol_major, 1);
    assert_eq!(frame.archive.frame.protocol_minor, 1);
    assert_eq!(
        (
            frame.archive.display.width_px,
            frame.archive.display.height_px,
            frame.archive.display.density
        ),
        (2, 3, 2.0)
    );
    assert_eq!(frame.archive.windows.len(), 1);
    assert_eq!(frame.archive.windows[0].nodes.len(), 1);
    assert_eq!(
        frame.archive.windows[0].nodes[0].class_name,
        "android.view.FrameLayout"
    );
    let evidence = frame.archive.screenshot.as_ref().unwrap();
    assert_eq!(evidence.size_bytes, png.len() as u64);
    assert_eq!(
        evidence.sha256,
        SourceDigest::hash_reader(Cursor::new(png.as_slice()), png.len() as u64).unwrap()
    );
    assert_eq!(
        frame.archive.screenshot_png.as_deref(),
        Some(png.as_slice())
    );
    let compact = encoded(&snapshot);
    let expected = SourceDigest::hash_reader(
        Cursor::new(compact.as_slice()).chain(Cursor::new(png.as_slice())),
        (compact.len() + png.len()) as u64,
    )
    .unwrap();
    assert_eq!(frame.archive.frame.source_digest, expected);
    assert_eq!(
        requests,
        vec![format!("PING {TOKEN}"), format!("CAPTURE {TOKEN}")]
    );
    let log = fixture.log();
    assert!(
        log.contains("-s test-serial shell run-as example.app cat files/agentperf/session.json")
    );
    assert!(log.contains("forward tcp:"));
    assert!(log.contains("forward --remove tcp:"));
}

#[test]
fn agent_capture_replaces_multi_window_screenshot_with_full_display() {
    let full_display = screenshot(3, 4);
    let (fixture, adb) = fixture_with_adb("multi", AdbBehaviour::Ok, &full_display);
    let snapshot = multi_window_snapshot();
    let (result, _) = run_with_agent(
        &adb,
        AgentReply::Frame {
            snapshot: encoded(&snapshot),
            screenshot: Vec::new(),
        },
    );
    let frame = captured(result);
    assert!(frame.from_agent);
    assert_eq!(frame.archive.windows.len(), 2);
    assert_eq!(
        (
            frame.archive.display.width_px,
            frame.archive.display.height_px,
            frame.archive.display.density
        ),
        (3, 4, 2.0)
    );
    assert_eq!(
        frame.archive.screenshot_png.as_deref(),
        Some(full_display.as_slice())
    );
    let evidence = frame.archive.screenshot.as_ref().unwrap();
    assert_eq!(evidence.size_bytes, full_display.len() as u64);
    assert_eq!(
        evidence.sha256,
        SourceDigest::hash_reader(
            Cursor::new(full_display.as_slice()),
            full_display.len() as u64
        )
        .unwrap()
    );
    let log = fixture.log();
    assert!(log.contains("-s test-serial exec-out screencap -p"));
    assert!(log.contains("forward --remove tcp:"));
}

#[test]
fn agent_capture_single_window_without_screenshot_has_no_evidence() {
    let (fixture, adb) = fixture_with_adb("empty-png", AdbBehaviour::Ok, &[]);
    let snapshot = single_window_snapshot();
    let (result, _) = run_with_agent(
        &adb,
        AgentReply::Frame {
            snapshot: encoded(&snapshot),
            screenshot: Vec::new(),
        },
    );
    let frame = captured(result);
    assert!(frame.archive.screenshot.is_none());
    assert!(frame.archive.screenshot_png.is_none());
    assert!(!fixture.log().contains("exec-out screencap"));
}

#[test]
fn empty_windows_array_falls_back_to_the_legacy_root() {
    let png = screenshot(2, 3);
    let (fixture, adb) = fixture_with_adb("legacy", AdbBehaviour::Ok, &png);
    let root = view_node("root", "android.view.FrameLayout", 2, 3);
    let mut snapshot = snapshot_value(Vec::new(), root, 2, 3);
    snapshot.as_object_mut().unwrap().remove("windows");
    let (result, _) = run_with_agent(
        &adb,
        AgentReply::Frame {
            snapshot: encoded(&snapshot),
            screenshot: png.clone(),
        },
    );
    let frame = captured(result);
    assert_eq!(frame.archive.windows.len(), 1);
    assert_eq!(frame.archive.windows[0].id, "window:legacy");
    assert_eq!(
        frame.archive.screenshot.as_ref().unwrap().size_bytes,
        png.len() as u64
    );
    assert!(!fixture.log().contains("exec-out screencap"));
}

#[test]
fn remote_error_is_a_hard_error_and_never_leaks_the_token() {
    let png = screenshot(2, 3);
    let (_fixture, adb) = fixture_with_adb("remote-error", AdbBehaviour::Ok, &png);
    let (result, _) = run_with_agent(
        &adb,
        AgentReply::Error(format!(
            "ERROR CAPTURE_FAILED no resumed activity for {TOKEN}\n"
        )),
    );
    let error = wait_for_client_error(result);
    assert!(error.contains("CAPTURE_FAILED"), "{error}");
    assert!(error.contains("[redacted]"), "{error}");
    assert!(!error.contains(TOKEN), "{error}");
}

#[test]
fn malformed_headers_and_truncated_payloads_are_rejected() {
    let png = screenshot(2, 3);
    let (_fixture, adb) = fixture_with_adb("bad-frames", AdbBehaviour::Ok, &png);
    let oversized = run_with_agent(
        &adb,
        AgentReply::MalformedHeader("CAPTURE 9000000 0\n".to_owned()),
    );
    assert!(wait_for_client_error(oversized.0).contains("payload length is out of bounds"));
    let unknown = run_with_agent(&adb, AgentReply::MalformedHeader("NOPE 1 2\n".to_owned()));
    assert!(wait_for_client_error(unknown.0).contains("malformed"));
    let extra = run_with_agent(
        &adb,
        AgentReply::MalformedHeader("CAPTURE 1 2 3\n".to_owned()),
    );
    assert!(wait_for_client_error(extra.0).contains("malformed"));
    let truncated = run_with_agent(
        &adb,
        AgentReply::Truncated {
            header: "CAPTURE 10 0\n".to_owned(),
            payload: vec![1, 2, 3],
        },
    );
    assert!(wait_for_client_error(truncated.0).contains("truncated"));
}

#[test]
fn authentication_mismatch_is_a_hard_error_and_removes_the_forward() {
    let png = screenshot(2, 3);
    let (fixture, adb) = fixture_with_adb("bad-auth", AdbBehaviour::Ok, &png);
    let (result, requests) = run_with_agent(&adb, AgentReply::WrongPong);
    let error = wait_for_client_error(result);
    assert!(error.contains("authentication"), "{error}");
    assert_eq!(requests, vec![format!("PING {TOKEN}")]);
    assert!(fixture.log().contains("forward --remove tcp:"));
}

#[test]
fn run_as_failure_makes_capture_unavailable() {
    let (fixture, adb) = fixture_with_adb("unavailable", AdbBehaviour::RunAsFails, &[]);
    let result = capture_agent_with(&adb, SERIAL, PACKAGE, 7, &CancellationToken::new(), || {
        Ok(12345)
    });
    match result {
        Ok(AgentCapture::Unavailable(reason)) => {
            assert!(reason.starts_with("Agent capture unavailable"));
        }
        Ok(AgentCapture::Captured(_)) => panic!("unexpectedly captured a frame"),
        Err(error) => panic!("expected the ADB fallback, got {error}"),
    }
    assert!(!fixture.log().contains("forward"));
}

#[test]
fn forward_failure_is_a_hard_error() {
    let (fixture, adb) = fixture_with_adb("forward-fails", AdbBehaviour::ForwardFails, &[]);
    let result = capture_agent_with(&adb, SERIAL, PACKAGE, 7, &CancellationToken::new(), || {
        Ok(12345)
    });
    let error = wait_for_client_error(result);
    assert!(error.contains("adb forward failed"), "{error}");
    assert!(!fixture.log().contains("forward --remove"));
}

#[test]
fn multi_window_full_display_failure_is_a_hard_error() {
    let (fixture, adb) = fixture_with_adb("screencap-fails", AdbBehaviour::ScreencapFails, &[]);
    let (result, _) = run_with_agent(
        &adb,
        AgentReply::Frame {
            snapshot: encoded(&multi_window_snapshot()),
            screenshot: Vec::new(),
        },
    );
    let error = wait_for_client_error(result);
    assert!(error.contains("full-display screenshot"), "{error}");
    assert!(fixture.log().contains("forward --remove tcp:"));
}

#[test]
fn malformed_descriptor_is_a_hard_error_not_a_fallback() {
    let (fixture, adb) = fixture_with_adb("bad-descriptor", AdbBehaviour::Ok, &[]);
    fixture.write("session.json", "{ not a descriptor");
    let result = capture_agent_with(&adb, SERIAL, PACKAGE, 7, &CancellationToken::new(), || {
        Ok(12345)
    });
    let error = wait_for_client_error(result);
    assert!(error.contains("session descriptor"), "{error}");
    assert!(!fixture.log().contains("forward"));
}

#[test]
fn unsupported_descriptor_protocol_major_is_a_hard_error() {
    let (fixture, adb) = fixture_with_adb("bad-major", AdbBehaviour::Ok, &[]);
    fixture.write(
        "session.json",
        format!(
            "{{\"protocolMajor\":3,\"protocolMinor\":1,\"socketName\":\"agentperf_example_app\",\"token\":\"{TOKEN}\"}}"
        ),
    );
    let result = capture_agent_with(&adb, SERIAL, PACKAGE, 7, &CancellationToken::new(), || {
        Ok(12345)
    });
    assert!(wait_for_client_error(result).contains("unsupported agent protocol major version 3"));
}

#[test]
fn snapshot_package_and_protocol_mismatches_are_hard_errors() {
    let png = screenshot(2, 3);
    let (_fixture, adb) = fixture_with_adb("snapshot-mismatch", AdbBehaviour::Ok, &png);
    let mut wrong_package = single_window_snapshot();
    wrong_package["packageName"] = json!("other.app");
    let (result, _) = run_with_agent(
        &adb,
        AgentReply::Frame {
            snapshot: encoded(&wrong_package),
            screenshot: png.clone(),
        },
    );
    assert!(wait_for_client_error(result).contains("package"));
    let mut wrong_protocol = single_window_snapshot();
    wrong_protocol["protocolVersion"]["major"] = json!(2);
    let (result, _) = run_with_agent(
        &adb,
        AgentReply::Frame {
            snapshot: encoded(&wrong_protocol),
            screenshot: png,
        },
    );
    assert!(wait_for_client_error(result).contains("unsupported agent snapshot"));
}

#[test]
fn screenshot_display_mismatch_is_a_hard_error() {
    let png = screenshot(3, 3);
    let (_fixture, adb) = fixture_with_adb("png-mismatch", AdbBehaviour::Ok, &png);
    let (result, _) = run_with_agent(
        &adb,
        AgentReply::Frame {
            snapshot: encoded(&single_window_snapshot()),
            screenshot: png,
        },
    );
    assert!(wait_for_client_error(result).contains("screenshot dimensions do not match"),);
}

#[test]
fn window_count_above_the_bound_is_rejected() {
    let png = screenshot(2, 3);
    let (_fixture, adb) = fixture_with_adb("too-many-windows", AdbBehaviour::Ok, &png);
    let root = view_node("root", "android.view.FrameLayout", 2, 3);
    let windows = (0..65)
        .map(|index| {
            window_value(
                &format!("window:{index}"),
                view_node(&format!("root:{index}"), "android.view.View", 2, 3),
                2,
                3,
            )
        })
        .collect::<Vec<_>>();
    let snapshot = snapshot_value(windows, root, 2, 3);
    let (result, _) = run_with_agent(
        &adb,
        AgentReply::Frame {
            snapshot: encoded(&snapshot),
            screenshot: png,
        },
    );
    assert!(wait_for_client_error(result).contains("window count exceeds"));
}

#[test]
fn cancellation_is_reported_and_skips_adb() {
    let (fixture, adb) = fixture_with_adb("cancelled", AdbBehaviour::Ok, &[]);
    let cancel = CancellationToken::new();
    cancel.cancel();
    let result = capture_agent_with(&adb, SERIAL, PACKAGE, 7, &cancel, || Ok(12345));
    assert!(wait_for_client_error(result).contains("cancelled"));
    assert!(!fixture.directory.join("adb.log").exists());
}

#[test]
fn invalid_serial_or_package_is_rejected_before_adb() {
    let (fixture, adb) = fixture_with_adb("invalid-target", AdbBehaviour::Ok, &[]);
    let serial = capture_agent_with(
        &adb,
        "-unsafe",
        PACKAGE,
        7,
        &CancellationToken::new(),
        || Ok(12345),
    );
    assert!(wait_for_client_error(serial).contains("device serial"));
    let package = capture_agent_with(&adb, SERIAL, "nodot", 7, &CancellationToken::new(), || {
        Ok(12345)
    });
    assert!(wait_for_client_error(package).contains("package name"));
    assert!(!fixture.directory.join("adb.log").exists());
}

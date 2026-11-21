//! Port of the Compose host's agent-first live capture
//! (LiveDeviceClient.connectWithFallback / connectAgent /
//! ConnectedDeviceSession.capture plus CaptureFrameCodec).
//!
//! The target app embeds the Kotlin/Java agent (android-agent-core): when it
//! starts it opens an abstract local socket agentperf.<package> and writes a
//! session descriptor (protocol major/minor, socket name, bearer token) to
//! files/agentperf/session.json inside its app data directory. A debuggable app
//! can be read with: adb shell run-as <package> cat ...
//!
//! The host then:
//! 1. reads and validates the session descriptor,
//! 2. forwards one loopback TCP port to the abstract socket (adb forward),
//! 3. authenticates with "PING <token>" -> "PONG 1.0",
//! 4. requests "CAPTURE <token>" on a second connection and receives
//!    "CAPTURE <snapshotBytes> <screenshotBytes>" followed by that many bytes
//!    (or "ERROR <code> <message>"),
//! 5. removes the forward and projects the protocol snapshot into the same
//!    bounded InspectorArchive the ADB capture path publishes.
//!
//! Every read is bounded (64 KiB session descriptor, 128-byte response headers,
//! 8 MiB snapshot, 32 MiB screenshot, 16M decoded pixels), loopback sockets use
//! the Compose 5 s connect/read/write timeout, adb calls use a 30 s process
//! timeout and honour the caller's cancellation token, and the adb forward is
//! removed by an RAII guard even when capture fails. The session token is never
//! copied into an error message.
//!
//! Any app that is not agent-enabled, is not debuggable or no longer runs the
//! agent makes this path *unavailable*; the caller then falls back to the ADB
//! View/UIAutomator capture exactly like Compose's AgentUnavailableException
//! fallback. Malformed descriptors, forwarding failures, authentication
//! mismatches, remote capture errors and invalid frames stay hard errors,
//! matching Compose.
//!
//! One deliberate deviation from Kotlin: the multi-window path in Compose wraps
//! its full-display screencap retry in runCatching and silently keeps the
//! per-window agent frame when it fails. That mixes a window-sized screenshot
//! with a display-sized snapshot, so this port treats a failed retry as a hard
//! error instead of publishing inconsistent evidence.

#[cfg(test)]
mod tests;

use crate::{FrameIdentity, InspectorProtocolFrame, ScreenshotEvidence, parse_snapshot};
use aps_contracts::SourceDigest;
use aps_json::{Limits, Value};
use aps_platform::{CancellationToken, ProcessError, ProcessRequest, ProcessRunner};
use std::io::{Cursor, Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream};
use std::path::{Path, PathBuf};
use std::time::Duration;

const SESSION_PATH: &str = "files/agentperf/session.json";
const MAX_SESSION_BYTES: usize = 64 * 1024;
const MAX_TOKEN_BYTES: usize = 256;
const MAX_SOCKET_NAME_BYTES: usize = 255;
const MAX_HEADER_BYTES: usize = 128;
const MAX_SNAPSHOT_BYTES: usize = 8 * 1024 * 1024;
const MAX_SCREENSHOT_BYTES: usize = 32 * 1024 * 1024;
const MAX_PIXELS: u64 = 16 * 1024 * 1024;
const MAX_WINDOWS: usize = 64;
const MAX_ERROR_CODE_BYTES: usize = 64;
const MAX_ERROR_MESSAGE_CHARS: usize = 256;
const ADB_TIMEOUT: Duration = Duration::from_secs(30);
const ADB_SMALL_LIMIT: usize = 4 * 1024;
const ADB_STDERR_LIMIT: usize = 8 * 1024;
const SOCKET_TIMEOUT: Duration = Duration::from_secs(5);
const FORWARD_REMOVE_TIMEOUT: Duration = Duration::from_secs(5);
const PONG: &[u8] = b"PONG 1.0";
const SCREENSHOT_ARGUMENTS: [&str; 3] = ["exec-out", "screencap", "-p"];
const SESSION_LIMITS: Limits = Limits {
    max_bytes: MAX_SESSION_BYTES as u64,
    max_depth: 8,
    max_nodes: 4096,
    max_string_bytes: 4096,
    max_collection_items: 64,
};

/// Outcome of one agent-first attempt.
#[allow(clippy::large_enum_variant)] // returned once per capture; boxing would only add an allocation
pub enum AgentCapture {
    /// The in-app agent produced a validated frame.
    Captured(InspectorProtocolFrame),
    /// The app is not running in agent-enabled/debuggable mode; the caller must
    /// fall back to the ADB capture path. The reason is diagnostic only.
    Unavailable(String),
}

/// One validated session descriptor. The protocol major/minor are checked while
/// parsing and deliberately not retained: only the socket name and the token are
/// needed to talk to the agent.
struct SessionDescriptor {
    socket_name: String,
    token: String,
}

/// Header returned by the agent after a CAPTURE request.
enum CaptureHeader {
    Frame {
        snapshot_len: usize,
        screenshot_len: usize,
    },
    Remote {
        code: String,
        message: String,
    },
}

/// The exact two payloads of one CAPTURE frame, both already size-checked.
struct CapturePayload {
    snapshot: Vec<u8>,
    screenshot: Vec<u8>,
}

/// Identity fields projected out of the protocol snapshot before it is handed to
/// the shared archive parser.
struct SnapshotIdentity {
    protocol_major: u32,
    protocol_minor: u32,
    captured_at_epoch_millis: i64,
}

enum AdbFailure {
    Cancelled,
    Failed(String),
}

/// Removes the loopback adb forward exactly once, including on every error path
/// after the forward was created. Removal is best effort and bounded.
struct ForwardGuard {
    adb: PathBuf,
    serial: String,
    port: u16,
}

impl Drop for ForwardGuard {
    fn drop(&mut self) {
        let endpoint = format!("tcp:{}", self.port);
        let mut request = ProcessRequest::new(&self.adb).args([
            "-s",
            self.serial.as_str(),
            "forward",
            "--remove",
            endpoint.as_str(),
        ]);
        request.timeout = FORWARD_REMOVE_TIMEOUT;
        request.stdout_limit = ADB_SMALL_LIMIT;
        request.stderr_limit = ADB_SMALL_LIMIT;
        // Cleanup must not be skipped because the capture token is cancelled.
        let _ = ProcessRunner.run(&request, &CancellationToken::new());
    }
}

/// Blocking; callers must schedule it on a background executor.
///
/// Returns Unavailable only when the session file cannot be read through run-as
/// (not debuggable / agent not started), mirroring Compose's
/// AgentUnavailableException fallback. A malformed session descriptor,
/// forwarding failure, authentication failure, remote ERROR response or an
/// invalid frame is a hard Err with a bounded, token-free message.
pub fn capture_agent(
    adb: &Path,
    serial: &str,
    package: &str,
    generation: u64,
    cancel: &CancellationToken,
) -> Result<AgentCapture, String> {
    capture_agent_with(
        adb,
        serial,
        package,
        generation,
        cancel,
        allocate_loopback_port,
    )
}

/// Testable body of capture_agent: the loopback port allocator is injectable so a
/// fake agent can be reached without a real adb forward listener.
fn capture_agent_with(
    adb: &Path,
    serial: &str,
    package: &str,
    generation: u64,
    cancel: &CancellationToken,
    allocate_port: impl FnOnce() -> Result<u16, String>,
) -> Result<AgentCapture, String> {
    check_cancel(cancel)?;
    if aps_adb::validate_serial(serial).is_err() {
        return Err(failure("invalid Android device serial"));
    }
    if !valid_package_name(package) {
        return Err(failure("invalid Android package name"));
    }
    // 1. Read the session descriptor. Exactly this stage falls back to the ADB
    // capture path; everything after a readable descriptor is a hard error.
    let session = match run_adb(
        adb,
        serial,
        &["shell", "run-as", package, "cat", SESSION_PATH],
        MAX_SESSION_BYTES,
        ADB_TIMEOUT,
        cancel,
    ) {
        Ok(session) => session,
        Err(AdbFailure::Cancelled) => return Err(failure("cancelled")),
        Err(AdbFailure::Failed(reason)) => {
            return Ok(AgentCapture::Unavailable(unavailable(format!(
                "the agent session descriptor is not readable ({reason})"
            ))));
        }
    };
    let descriptor = parse_session_descriptor(&session)?;
    check_cancel(cancel)?;
    // 2. Allocate a loopback port and register the forward. The guard is created
    // immediately after the forward succeeds so no later error can leak it.
    let port = allocate_port().map_err(failure)?;
    let endpoint = format!("tcp:{port}");
    let remote = format!("localabstract:{}", descriptor.socket_name);
    match run_adb(
        adb,
        serial,
        &["forward", endpoint.as_str(), remote.as_str()],
        ADB_SMALL_LIMIT,
        ADB_TIMEOUT,
        cancel,
    ) {
        Ok(_) => {}
        Err(AdbFailure::Cancelled) => return Err(failure("cancelled")),
        Err(AdbFailure::Failed(reason)) => {
            return Err(failure(format!("adb forward failed ({reason})")));
        }
    }
    let _forward = ForwardGuard {
        adb: adb.to_path_buf(),
        serial: serial.to_owned(),
        port,
    };
    // 3. Authenticate on a first, short-lived connection.
    authenticate(port, &descriptor.token, cancel)?;
    check_cancel(cancel)?;
    // 4. Capture on a brand-new connection.
    let payload = request_capture(port, &descriptor.token, cancel)?;
    check_cancel(cancel)?;
    // 5. Project and validate the protocol snapshot.
    project_capture(payload, adb, serial, package, generation, cancel).map(AgentCapture::Captured)
}

fn allocate_loopback_port() -> Result<u16, String> {
    let listener = TcpListener::bind(SocketAddr::from((Ipv4Addr::LOCALHOST, 0)))
        .map_err(|_| "cannot bind a loopback port".to_owned())?;
    let port = listener
        .local_addr()
        .map_err(|_| "cannot read the loopback port".to_owned())?
        .port();
    drop(listener);
    Ok(port)
}

fn authenticate(port: u16, token: &str, cancel: &CancellationToken) -> Result<(), String> {
    check_cancel(cancel)?;
    let mut stream = connect(port)?;
    write_request(&mut stream, &format!("PING {token}"))?;
    let response = read_header(&mut stream, MAX_HEADER_BYTES)?;
    // Kotlin reads the PING reply through BufferedReader.readLine(), which strips
    // a trailing carriage return; the CAPTURE header is read byte-exact instead.
    let response = response.strip_suffix(b"\r").unwrap_or(response.as_slice());
    if response != PONG {
        return Err(failure("agent authentication was rejected"));
    }
    Ok(())
}

fn request_capture(
    port: u16,
    token: &str,
    cancel: &CancellationToken,
) -> Result<CapturePayload, String> {
    check_cancel(cancel)?;
    let mut stream = connect(port)?;
    write_request(&mut stream, &format!("CAPTURE {token}"))?;
    let header = read_header(&mut stream, MAX_HEADER_BYTES)?;
    let header = std::str::from_utf8(&header)
        .map_err(|_| failure("agent capture response header is not valid UTF-8"))?;
    match parse_capture_header(header, token)? {
        CaptureHeader::Remote { code, message } => Err(failure(format!(
            "agent capture failed remotely ({code}): {message}"
        ))),
        CaptureHeader::Frame {
            snapshot_len,
            screenshot_len,
        } => {
            let snapshot = read_payload(&mut stream, snapshot_len, "snapshot", cancel)?;
            check_cancel(cancel)?;
            let screenshot = read_payload(&mut stream, screenshot_len, "screenshot", cancel)?;
            Ok(CapturePayload {
                snapshot,
                screenshot,
            })
        }
    }
}

fn project_capture(
    payload: CapturePayload,
    adb: &Path,
    serial: &str,
    package: &str,
    generation: u64,
    cancel: &CancellationToken,
) -> Result<InspectorProtocolFrame, String> {
    check_cancel(cancel)?;
    let CapturePayload {
        snapshot,
        screenshot: agent_screenshot,
    } = payload;
    let parsed = aps_json::parse_slice(&snapshot, crate::json_limits(MAX_SNAPSHOT_BYTES as u64))
        .map_err(|_| failure("agent snapshot is not a bounded, valid JSON document"))?;
    let mut session_snapshot = parsed;
    let identity = snapshot_identity(&session_snapshot, package)?;
    let window_count = snapshot_window_count(&session_snapshot)?;
    check_cancel(cancel)?;
    // Compose requiresFullDisplayScreenshot: a multi-window snapshot includes
    // overlays that the activity window screenshot cannot show, so the display
    // geometry and screenshot are re-captured from the full display.
    let (screenshot, screenshot_dimensions) = if window_count > 1 {
        let png = match run_adb(
            adb,
            serial,
            &SCREENSHOT_ARGUMENTS,
            MAX_SCREENSHOT_BYTES,
            ADB_TIMEOUT,
            cancel,
        ) {
            Ok(png) => png,
            Err(AdbFailure::Cancelled) => return Err(failure("cancelled")),
            Err(AdbFailure::Failed(reason)) => {
                return Err(failure(format!(
                    "full-display screenshot for the multi-window frame failed ({reason})"
                )));
            }
        };
        let dimensions = decode_png_dimensions(&png)?;
        set_display_dimensions(&mut session_snapshot, dimensions)?;
        (png, Some(dimensions))
    } else if agent_screenshot.is_empty() {
        (agent_screenshot, None)
    } else {
        let dimensions = decode_png_dimensions(&agent_screenshot)?;
        (agent_screenshot, Some(dimensions))
    };
    check_cancel(cancel)?;
    let snapshot_bytes = aps_json::to_vec(
        &session_snapshot,
        crate::json_limits(MAX_SNAPSHOT_BYTES as u64),
    )
    .map_err(|_| failure("cannot encode the validated agent snapshot"))?;
    let evidence_limit = (snapshot_bytes.len() as u64).saturating_add(screenshot.len() as u64);
    let source_digest = SourceDigest::hash_reader(
        Cursor::new(snapshot_bytes.as_slice()).chain(Cursor::new(screenshot.as_slice())),
        evidence_limit,
    )
    .map_err(|_| failure("cannot hash the agent capture evidence"))?;
    let frame = FrameIdentity {
        source_digest,
        package_name: package.to_owned(),
        captured_at_epoch_millis: identity.captured_at_epoch_millis,
        protocol_major: identity.protocol_major,
        protocol_minor: identity.protocol_minor,
    };
    let mut archive = parse_snapshot(&session_snapshot, frame, 1)
        .map_err(|error| failure(format!("agent snapshot is invalid ({error})")))?;
    if let Some(dimensions) = screenshot_dimensions {
        if dimensions != (archive.display.width_px, archive.display.height_px) {
            return Err(failure(
                "agent screenshot dimensions do not match the captured display",
            ));
        }
        let sha256 =
            SourceDigest::hash_reader(Cursor::new(screenshot.as_slice()), screenshot.len() as u64)
                .map_err(|_| failure("cannot hash the agent screenshot"))?;
        archive.screenshot = Some(ScreenshotEvidence {
            sha256,
            size_bytes: screenshot.len() as u64,
        });
        archive.screenshot_png = Some(screenshot);
    }
    check_cancel(cancel)?;
    Ok(InspectorProtocolFrame {
        archive,
        generation,
        from_agent: true,
    })
}

fn parse_session_descriptor(bytes: &[u8]) -> Result<SessionDescriptor, String> {
    if bytes.is_empty() {
        return Err(failure(
            "malformed agent session descriptor: empty document",
        ));
    }
    let document = aps_json::parse_slice(bytes, SESSION_LIMITS)
        .map_err(|_| failure("malformed agent session descriptor: not a bounded JSON document"))?;
    let root = document
        .as_object()
        .ok_or_else(|| failure("malformed agent session descriptor: not a JSON object"))?;
    let protocol_major = root
        .get("protocolMajor")
        .and_then(Value::as_i64)
        .ok_or_else(|| failure("malformed agent session descriptor: missing protocolMajor"))?;
    // Kotlin's AgentSessionDescriptor also requires protocolMinor to be present.
    if root.get("protocolMinor").and_then(Value::as_i64).is_none() {
        return Err(failure(
            "malformed agent session descriptor: missing protocolMinor",
        ));
    }
    if protocol_major != 1 {
        return Err(failure(format!(
            "unsupported agent protocol major version {protocol_major}"
        )));
    }
    let socket_name = root
        .get("socketName")
        .and_then(Value::as_str)
        .ok_or_else(|| failure("malformed agent session descriptor: missing socketName"))?;
    if socket_name.is_empty()
        || socket_name.len() > MAX_SOCKET_NAME_BYTES
        || !socket_name
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'.' | b'-'))
    {
        return Err(failure(
            "malformed agent session descriptor: unsafe socket name",
        ));
    }
    let token = root
        .get("token")
        .and_then(Value::as_str)
        .ok_or_else(|| failure("malformed agent session descriptor: missing token"))?;
    // The token is written into a single request line, so whitespace or control
    // characters are rejected before they can break the wire protocol. The value
    // itself is never echoed into an error message.
    if token.is_empty()
        || token.len() > MAX_TOKEN_BYTES
        || !token
            .bytes()
            .all(|byte| !byte.is_ascii_whitespace() && !byte.is_ascii_control())
    {
        return Err(failure(
            "malformed agent session descriptor: invalid session token",
        ));
    }
    Ok(SessionDescriptor {
        socket_name: socket_name.to_owned(),
        token: token.to_owned(),
    })
}

fn snapshot_identity(snapshot: &Value, package: &str) -> Result<SnapshotIdentity, String> {
    let root = snapshot
        .as_object()
        .ok_or_else(|| failure("agent snapshot is not a JSON object"))?;
    let version = root
        .get("protocolVersion")
        .and_then(Value::as_object)
        .ok_or_else(|| failure("agent snapshot protocol version is missing"))?;
    let protocol_major = version
        .get("major")
        .and_then(Value::as_u64)
        .and_then(|value| u32::try_from(value).ok())
        .ok_or_else(|| failure("agent snapshot protocol major version is invalid"))?;
    let protocol_minor = version
        .get("minor")
        .and_then(Value::as_u64)
        .and_then(|value| u32::try_from(value).ok())
        .ok_or_else(|| failure("agent snapshot protocol minor version is invalid"))?;
    if protocol_major != 1 {
        return Err(failure(format!(
            "unsupported agent snapshot protocol major version {protocol_major}"
        )));
    }
    let package_name = root
        .get("packageName")
        .and_then(Value::as_str)
        .ok_or_else(|| failure("agent snapshot package name is missing"))?;
    if package_name != package {
        return Err(failure(
            "agent snapshot package does not match the capture target",
        ));
    }
    let captured_at_epoch_millis = root
        .get("capturedAtEpochMillis")
        .and_then(Value::as_i64)
        .ok_or_else(|| failure("agent snapshot timestamp is missing"))?;
    Ok(SnapshotIdentity {
        protocol_major,
        protocol_minor,
        captured_at_epoch_millis,
    })
}

fn snapshot_window_count(snapshot: &Value) -> Result<usize, String> {
    match snapshot.get("windows") {
        None => Ok(1),
        Some(Value::Array(windows)) if windows.is_empty() => Ok(1),
        Some(Value::Array(windows)) if windows.len() <= MAX_WINDOWS => Ok(windows.len()),
        Some(Value::Array(_)) => Err(failure("agent snapshot window count exceeds the bound")),
        Some(_) => Err(failure("agent snapshot windows field is invalid")),
    }
}

fn set_display_dimensions(snapshot: &mut Value, dimensions: (u32, u32)) -> Result<(), String> {
    let display = snapshot
        .get_mut("display")
        .and_then(Value::as_object_mut)
        .ok_or_else(|| failure("agent snapshot display is missing"))?;
    // The density and any other display fields are kept; only the pixel extent
    // is replaced by the full-display screenshot's geometry.
    display.insert("widthPx".to_owned(), Value::from(u64::from(dimensions.0)));
    display.insert("heightPx".to_owned(), Value::from(u64::from(dimensions.1)));
    Ok(())
}

fn parse_capture_header(header: &str, token: &str) -> Result<CaptureHeader, String> {
    if let Some(rest) = header.strip_prefix("ERROR ") {
        let (code, message) = rest
            .split_once(' ')
            .ok_or_else(|| failure("malformed agent error response"))?;
        if code.is_empty()
            || code.len() > MAX_ERROR_CODE_BYTES
            || code
                .bytes()
                .any(|byte| byte.is_ascii_whitespace() || byte.is_ascii_control())
        {
            return Err(failure("malformed agent error response"));
        }
        return Ok(CaptureHeader::Remote {
            code: code.to_owned(),
            message: bounded_remote_text(message, token),
        });
    }
    let mut parts = header.split(' ');
    let command = parts.next().unwrap_or_default();
    let snapshot = parts
        .next()
        .ok_or_else(|| failure("malformed agent capture header"))?;
    let screenshot = parts
        .next()
        .ok_or_else(|| failure("malformed agent capture header"))?;
    if parts.next().is_some() || command != "CAPTURE" {
        return Err(failure("malformed agent capture header"));
    }
    Ok(CaptureHeader::Frame {
        snapshot_len: parse_payload_length(snapshot, MAX_SNAPSHOT_BYTES, "snapshot")?,
        screenshot_len: parse_payload_length(screenshot, MAX_SCREENSHOT_BYTES, "screenshot")?,
    })
}

fn parse_payload_length(value: &str, maximum: usize, label: &str) -> Result<usize, String> {
    if value.is_empty() || !value.bytes().all(|byte| byte.is_ascii_digit()) {
        return Err(failure(format!("invalid agent {label} payload length")));
    }
    let parsed: u64 = value
        .parse()
        .map_err(|_| failure(format!("invalid agent {label} payload length")))?;
    if parsed > maximum as u64 {
        return Err(failure(format!(
            "agent {label} payload length is out of bounds"
        )));
    }
    Ok(parsed as usize)
}

/// Remote text is display-only: bound it and redact the session token in case a
/// misbehaving agent echoes its own credential.
fn bounded_remote_text(text: &str, token: &str) -> String {
    let text = if token.is_empty() {
        text.to_owned()
    } else {
        text.replace(token, "[redacted]")
    };
    let text: String = text
        .chars()
        .filter(|character| !character.is_control())
        .take(MAX_ERROR_MESSAGE_CHARS)
        .collect();
    if text.is_empty() {
        "[no detail]".to_owned()
    } else {
        text
    }
}

fn connect(port: u16) -> Result<TcpStream, String> {
    let address = SocketAddr::from((Ipv4Addr::LOCALHOST, port));
    let stream = TcpStream::connect_timeout(&address, SOCKET_TIMEOUT)
        .map_err(|error| socket_failure("connect", &error))?;
    stream
        .set_read_timeout(Some(SOCKET_TIMEOUT))
        .map_err(|_| failure("cannot configure the agent socket read timeout"))?;
    stream
        .set_write_timeout(Some(SOCKET_TIMEOUT))
        .map_err(|_| failure("cannot configure the agent socket write timeout"))?;
    Ok(stream)
}

fn write_request(stream: &mut TcpStream, request: &str) -> Result<(), String> {
    let mut line = Vec::with_capacity(request.len() + 1);
    line.extend_from_slice(request.as_bytes());
    line.push(b'\n');
    stream
        .write_all(&line)
        .map_err(|error| socket_failure("write", &error))?;
    stream
        .flush()
        .map_err(|error| socket_failure("write", &error))
}

fn read_header(stream: &mut TcpStream, maximum: usize) -> Result<Vec<u8>, String> {
    let mut header = Vec::new();
    let mut byte = [0u8; 1];
    loop {
        if header.len() > maximum {
            return Err(failure("agent response header exceeds the bounded size"));
        }
        match stream.read(&mut byte) {
            Ok(0) => return Err(failure("agent response ended before its header")),
            Ok(_) if byte[0] == b'\n' => return Ok(header),
            Ok(_) => header.push(byte[0]),
            Err(error) if is_timeout(&error) => {
                return Err(failure("timed out waiting for the agent response header"));
            }
            Err(error) => return Err(socket_failure("read", &error)),
        }
    }
}

fn read_payload(
    stream: &mut TcpStream,
    length: usize,
    label: &str,
    cancel: &CancellationToken,
) -> Result<Vec<u8>, String> {
    let mut payload = vec![0u8; length];
    let mut offset = 0;
    while offset < length {
        check_cancel(cancel)?;
        let chunk = (length - offset).min(64 * 1024);
        match stream.read(&mut payload[offset..offset + chunk]) {
            Ok(0) => return Err(failure(format!("truncated agent {label} payload"))),
            Ok(count) => offset += count,
            Err(error) if is_timeout(&error) => {
                return Err(failure(format!(
                    "timed out reading the agent {label} payload"
                )));
            }
            Err(error) => return Err(socket_failure("read", &error)),
        }
    }
    Ok(payload)
}

fn decode_png_dimensions(png: &[u8]) -> Result<(u32, u32), String> {
    if png.is_empty() {
        return Err(failure("agent screenshot payload is empty"));
    }
    if png.len() > MAX_SCREENSHOT_BYTES {
        return Err(failure("agent screenshot exceeds the input bound"));
    }
    let mut decoder = png::Decoder::new(Cursor::new(png));
    decoder.set_limits(png::Limits {
        bytes: 64 * 1024 * 1024,
    });
    let mut reader = decoder
        .read_info()
        .map_err(|_| failure("agent screenshot is not a valid PNG"))?;
    let dimensions = (reader.info().width, reader.info().height);
    if dimensions.0 == 0
        || dimensions.1 == 0
        || u64::from(dimensions.0) * u64::from(dimensions.1) > MAX_PIXELS
    {
        return Err(failure("agent screenshot exceeds the decoded pixel bound"));
    }
    let length = reader
        .output_buffer_size()
        .filter(|size| *size <= 64 * 1024 * 1024)
        .ok_or_else(|| failure("agent screenshot exceeds the decoded byte bound"))?;
    let mut pixels = vec![0; length];
    reader
        .next_frame(&mut pixels)
        .map_err(|_| failure("agent screenshot PNG is corrupt"))?;
    Ok(dimensions)
}

fn run_adb(
    adb: &Path,
    serial: &str,
    arguments: &[&str],
    stdout_limit: usize,
    timeout: Duration,
    cancel: &CancellationToken,
) -> Result<Vec<u8>, AdbFailure> {
    if cancel.is_cancelled() {
        return Err(AdbFailure::Cancelled);
    }
    let mut request = ProcessRequest::new(adb)
        .args(["-s", serial])
        .args(arguments);
    request.timeout = timeout;
    request.stdout_limit = stdout_limit;
    request.stderr_limit = ADB_STDERR_LIMIT;
    match ProcessRunner.run(&request, cancel) {
        Ok(output) if output.status.success() => Ok(output.stdout),
        Ok(output) => Err(AdbFailure::Failed(format!(
            "command exited with {:?}",
            output.status.code()
        ))),
        Err(ProcessError::Cancelled) => Err(AdbFailure::Cancelled),
        Err(error) => Err(AdbFailure::Failed(error.to_string())),
    }
}

fn valid_package_name(package: &str) -> bool {
    if package.is_empty() || package.len() > 255 {
        return false;
    }
    let mut segments = 0usize;
    for segment in package.split('.') {
        if segment.is_empty()
            || !segment
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_')
        {
            return false;
        }
        segments += 1;
    }
    segments >= 2
}

fn check_cancel(cancel: &CancellationToken) -> Result<(), String> {
    if cancel.is_cancelled() {
        Err(failure("cancelled"))
    } else {
        Ok(())
    }
}

fn is_timeout(error: &std::io::Error) -> bool {
    matches!(
        error.kind(),
        std::io::ErrorKind::WouldBlock | std::io::ErrorKind::TimedOut
    )
}

fn socket_failure(operation: &str, error: &std::io::Error) -> String {
    if is_timeout(error) {
        failure(format!("timed out while the agent socket {operation}"))
    } else {
        failure(format!(
            "agent socket {operation} failed ({:?})",
            error.kind()
        ))
    }
}

fn failure(detail: impl std::fmt::Display) -> String {
    format!("Agent capture failed: {detail}")
}

fn unavailable(detail: impl std::fmt::Display) -> String {
    format!("Agent capture unavailable: {detail}")
}

//! Frame Analyzer online capture parity for the Compose frame-profiler.
//!
//! Mirrors the Compose oracle:
//! - DesktopFrameOnlineBackend.kt: device/process discovery, preferred
//!   FrameMetrics Agent with gfxinfo polling fallback, capture metadata;
//! - GfxInfoPollingCaptureSession.kt: dumpsys gfxinfo framestats polling with
//!   frame-identity dedupe, bounded rolling-window warnings and sequential ids;
//! - FrameMetricsAgentCaptureSession.kt + AgentFrameProtocol.kt: adb forward to
//!   the app-local Agent socket, framed JSON batches, cursor polling and
//!   JankStats observations;
//! - BoundedFrameTimelineCapture.kt: bounded Android 12+ perfetto frame_timeline
//!   capture with device temp cleanup.
//!
//! No hardware is exercised by tests: ADB is behind the FrameAdb trait and the
//! Agent stream behind AgentFrameTransport, so parsing, protocol, dedupe,
//! preference/fallback and timeline command shape are unit-tested with fakes.
//! Every command is a fixed argument vector with a validated serial/package.

use aps_adb::{AdbClient, AdbDevice, AdbLocator, AndroidPackage, TargetSnapshot, validate_serial};
use aps_frame::model::FrameStages;
use aps_frame::{
    ExpectedDurationSource, FrameCaptureSession, FrameSample, FrameSource, FrameSourceCapabilities,
    parse_gfxinfo,
};
#[cfg(test)]
use aps_json::to_vec;
use aps_json::{Limits, Map, Value, parse_slice};
use aps_platform::{CancellationToken, ProcessRequest, ProcessRunner};
use std::{
    collections::{BTreeMap, BTreeSet, VecDeque},
    ffi::OsString,
    io::{Read, Write},
    net::{TcpListener, TcpStream},
    path::PathBuf,
    sync::{
        Arc,
        atomic::{AtomicU64, Ordering},
    },
    time::{Duration, SystemTime, UNIX_EPOCH},
};

const MINIMUM_FRAME_TIMELINE_API_LEVEL: u32 = 31;
const FRAME_TIMELINE_BUFFER_SIZE_KB: u32 = 32 * 1024;
const MAX_TIMELINE_DURATION_MS: u64 = 10_000;
const FRAME_TIMELINE_DATA_SOURCE: &str = "android.surfaceflinger.frametimeline";
const GFXINFO_COMMAND_TIMEOUT: Duration = Duration::from_secs(10);
const GFXINFO_OUTPUT_LIMIT: usize = 8 * 1024 * 1024;
const PACKAGES_OUTPUT_LIMIT: usize = 16 * 1024 * 1024;
const AGENT_COMMAND_TIMEOUT: Duration = Duration::from_secs(10);
const AGENT_COMMAND_OUTPUT_LIMIT: usize = 64 * 1024;
const MAX_AGENT_BATCH_BYTES: usize = 8 * 1024 * 1024;
const MAX_AGENT_HEADER_BYTES: usize = 128;
const MAX_AGENT_FRAMES_PER_BATCH: usize = 2_048;
const MAX_AGENT_STATES: usize = 32;
const MAX_AGENT_STRING_BYTES: usize = 4_096;
const MAX_AGENT_WARNINGS: usize = 64;
const AGENT_SESSION_PATH: &str = "files/agentperf/session.json";
const SUPPORTED_AGENT_PROTOCOL_MAJOR: u32 = 1;
const SOCKET_TIMEOUT: Duration = Duration::from_secs(10);
const MAX_DEDUPLICATION_IDENTITIES: usize = 1_024;
const FRAMESTATS_WINDOW_WARNING_SIZE: usize = 110;
const MAX_OBSERVED_REFRESH_RATES: usize = 16;

#[cfg(test)]
const JANK_STATS_RULE_ID: &str = "androidx.metrics.performance.JankStats.FrameData.isJank.default";
#[cfg(test)]
const JANK_STATS_RULE_VERSION: &str = "1.0.0";

static FRAME_SEQUENCE: AtomicU64 = AtomicU64::new(0);

// ---------------------------------------------------------------------------
// Public capture surface used by the Lead runtime and page.
// ---------------------------------------------------------------------------

#[derive(Clone, Debug, PartialEq, Eq)]
pub(in crate::frame) struct FrameDeviceOption {
    pub serial: String,
    pub name: String,
    pub online: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(in crate::frame) struct FrameProcessOption {
    pub pid: i32,
    pub name: String,
    pub package_name: String,
}

#[derive(Clone, Debug, Default, PartialEq)]
pub(in crate::frame) struct FramePollBatch {
    pub frames: Vec<FrameSample>,
    pub warnings: Vec<String>,
}

#[derive(Debug)]
pub(in crate::frame) struct BoundedFrameTimelineCapture {
    pub trace_file: PathBuf,
    pub started_at_unix_ms: u64,
    pub android_api_level: u32,
    pub recording: aps_perfetto::recording::RecordingResult,
}

// ---------------------------------------------------------------------------
// aps_frame adapter: the only place that knows the model field/variant names.
// ---------------------------------------------------------------------------

fn gfxinfo_capabilities() -> FrameSourceCapabilities {
    FrameSourceCapabilities {
        realtime: true,
        stage_breakdown: true,
        platform_jank_classification: false,
        expected_frame_deadline: true,
        app_state_labels: false,
    }
}

fn frame_metrics_capabilities() -> FrameSourceCapabilities {
    FrameSourceCapabilities {
        realtime: true,
        stage_breakdown: true,
        platform_jank_classification: true,
        expected_frame_deadline: true,
        app_state_labels: true,
    }
}

fn expected_duration_source(source: AgentExpectedDurationSource) -> ExpectedDurationSource {
    match source {
        AgentExpectedDurationSource::PlatformDeadline => ExpectedDurationSource::PlatformDeadline,
        AgentExpectedDurationSource::RefreshRate => ExpectedDurationSource::RefreshRate,
        AgentExpectedDurationSource::Unknown => ExpectedDurationSource::Unknown,
    }
}

fn capture_session(
    id: &str,
    source: FrameSource,
    started_at_unix_ms: u64,
    package_name: &str,
    serial: &str,
    android_api_level: Option<u32>,
    agent_protocol: Option<String>,
    capabilities: FrameSourceCapabilities,
    observed_refresh_rates_hz: Vec<f64>,
    provenance_complete: bool,
    provenance_warnings: Vec<String>,
) -> FrameCaptureSession {
    FrameCaptureSession {
        id: id.to_owned(),
        source,
        started_at_unix_ms,
        package_name: Some(package_name.to_owned()),
        device_serial: Some(serial.to_owned()),
        device_api_level: android_api_level,
        agent_protocol,
        source_capabilities: Some(capabilities),
        observed_refresh_rates_hz,
        imported_file: None,
        imported_file_sha256: None,
        imported_at_unix_ms: None,
        provenance_complete,
        provenance_warnings,
        perfetto_trace_file: None,
        artifact: None,
    }
}

fn parse_gfxinfo_frames(
    text: &str,
    session_id: &str,
    package_name: &str,
    warn_on_empty_frames: bool,
) -> Result<(Vec<FrameSample>, Vec<String>), String> {
    let parsed = parse_gfxinfo(text, session_id, Some(package_name), warn_on_empty_frames)?;
    Ok((parsed.frames, parsed.warnings))
}

fn gfxinfo_sample(
    session_id: &str,
    package_name: &str,
    process_id: i32,
    frame_id: u64,
    frame: &FrameSample,
) -> FrameSample {
    let mut sample = frame.clone();
    sample.frame_id = frame_id;
    sample.session_id = session_id.to_owned();
    sample.source = FrameSource::Gfxinfo;
    sample.package_name = Some(package_name.to_owned());
    sample.process_id = Some(process_id);
    sample.dropped_before_sample = 0;
    sample
}

fn agent_sample(
    frame_id: u64,
    session_id: &str,
    process_id: i32,
    dropped_before_sample: i64,
    frame: &AgentFrameSample,
) -> FrameSample {
    FrameSample {
        frame_id,
        session_id: session_id.to_owned(),
        source: FrameSource::FrameMetrics,
        package_name: Some(frame.package_name.clone()),
        process_id: Some(process_id),
        activity_name: frame.activity_name.clone(),
        window_id: frame.window_id.clone(),
        intended_vsync_ns: frame.intended_vsync_ns,
        actual_vsync_ns: frame.actual_vsync_ns,
        frame_completed_ns: frame.frame_completed_ns,
        present_ns: None,
        expected_duration_ns: frame.expected_duration_ns,
        expected_duration_source: expected_duration_source(frame.expected_duration_source),
        refresh_rate_hz: frame.refresh_rate_hz,
        frame_timeline_vsync_id: frame.frame_timeline_vsync_id,
        total_duration_ns: frame.total_duration_ns,
        stages: FrameStages {
            input_ns: frame.stages.input_ns,
            animation_ns: frame.stages.animation_ns,
            layout_measure_ns: frame.stages.layout_measure_ns,
            draw_ns: frame.stages.draw_ns,
            sync_ns: frame.stages.sync_ns,
            command_issue_ns: frame.stages.command_issue_ns,
            swap_buffers_ns: frame.stages.swap_buffers_ns,
            gpu_ns: frame.stages.gpu_ns,
        },
        platform_jank: frame.platform_jank,
        platform_jank_types: BTreeSet::new(),
        platform_jank_rule_id: frame.platform_jank_rule_id.clone(),
        platform_jank_rule_version: frame.platform_jank_rule_version.clone(),
        states: frame.states.clone(),
        eligible_for_jank: frame.eligible_for_jank,
        dropped_before_sample,
        layout_snapshot_id: None,
        fragment_name: frame.states.get("fragment").cloned(),
        page_name: frame.states.get("page").cloned(),
        interaction_state: frame
            .states
            .get("interaction")
            .or_else(|| frame.states.get("uiState"))
            .cloned(),
        render_thread_name: frame.states.get("renderThread").cloned(),
        surface_flinger_jank_type: frame.surface_flinger_jank_type.clone(),
        jank_stats_jank: frame.jank_stats_jank,
        jank_stats_reasons: frame.jank_stats_reasons.clone(),
        jank_stats_rule_id: frame.jank_stats_rule_id.clone(),
        jank_stats_rule_version: frame.jank_stats_rule_version.clone(),
    }
}

// ---------------------------------------------------------------------------
// Fixed-argument ADB transport (behind a trait so tests never touch a device).
// ---------------------------------------------------------------------------

#[derive(Clone, Debug, PartialEq, Eq)]
struct ShellOutput {
    success: bool,
    stdout: String,
    stderr: String,
}

impl ShellOutput {
    #[cfg(test)]
    fn successful(stdout: String) -> Self {
        Self {
            success: true,
            stdout,
            stderr: String::new(),
        }
    }

    fn require_success(self, message: &str) -> Result<String, String> {
        if self.success {
            Ok(self.stdout)
        } else {
            Err(message.to_owned())
        }
    }
}

trait FrameAdb: Send + Sync {
    fn shell(&self, serial: &str, arguments: &[String]) -> Result<ShellOutput, String>;

    fn sdk_level(&self, serial: &str) -> Result<u32, String>;
    fn forward(&self, serial: &str, local: &str, remote: &str) -> Result<(), String>;
    fn remove_forward(&self, serial: &str, local: &str) -> Result<(), String>;
}

struct ProcessFrameAdb {
    executable: PathBuf,
    cancel: CancellationToken,
}

impl ProcessFrameAdb {
    fn locate() -> Result<Self, String> {
        let location = AdbLocator::new()
            .locate(None)
            .map_err(|error| error.to_string())?;
        Ok(Self {
            executable: location.executable,
            cancel: CancellationToken::new(),
        })
    }

    fn run(
        &self,
        serial: &str,
        arguments: &[String],
        timeout: Duration,
        stdout_limit: usize,
    ) -> Result<ShellOutput, String> {
        validate_serial(serial).map_err(|error| error.to_string())?;
        let mut request = ProcessRequest::new(&self.executable);
        let mut argv: Vec<OsString> = vec!["-s".into(), serial.into()];
        argv.extend(arguments.iter().map(OsString::from));
        request.arguments = argv;
        request.timeout = timeout;
        request.stdout_limit = stdout_limit;
        request.stderr_limit = 256 * 1024;
        let output = ProcessRunner
            .run(&request, &self.cancel)
            .map_err(sanitized_adb_error)?;
        Ok(ShellOutput {
            success: output.status.success(),
            stdout: String::from_utf8_lossy(&output.stdout).into_owned(),
            stderr: String::from_utf8_lossy(&output.stderr).into_owned(),
        })
    }
}

fn sanitized_adb_error(error: aps_platform::ProcessError) -> String {
    use aps_platform::ProcessError;
    match error {
        ProcessError::Cancelled => "ADB command cancelled".to_owned(),
        ProcessError::Timeout => "ADB command timed out".to_owned(),
        ProcessError::OutputLimit(_) => "ADB output exceeded the bounded limit".to_owned(),
        ProcessError::OutputNotClosed => "ADB output did not close".to_owned(),
        _ => "ADB command could not be executed".to_owned(),
    }
}

fn shell_arguments(parts: &[&str]) -> Vec<String> {
    parts.iter().map(|part| (*part).to_owned()).collect()
}

impl FrameAdb for ProcessFrameAdb {
    fn shell(&self, serial: &str, arguments: &[String]) -> Result<ShellOutput, String> {
        self.run(
            serial,
            arguments,
            GFXINFO_COMMAND_TIMEOUT,
            GFXINFO_OUTPUT_LIMIT,
        )
    }

    fn sdk_level(&self, serial: &str) -> Result<u32, String> {
        let output = self.run(
            serial,
            &shell_arguments(&["shell", "getprop", "ro.build.version.sdk"]),
            GFXINFO_COMMAND_TIMEOUT,
            AGENT_COMMAND_OUTPUT_LIMIT,
        )?;
        let text = output.require_success("Unable to read the device Android API level.")?;
        text.trim()
            .parse::<u32>()
            .map_err(|_| "Unable to read the device Android API level.".to_owned())
    }

    fn forward(&self, serial: &str, local: &str, remote: &str) -> Result<(), String> {
        let output = self.run(
            serial,
            &shell_arguments(&["forward", local, remote]),
            AGENT_COMMAND_TIMEOUT,
            AGENT_COMMAND_OUTPUT_LIMIT,
        )?;
        output.require_success("Unable to forward the FrameMetrics Agent socket.")?;
        Ok(())
    }

    fn remove_forward(&self, serial: &str, local: &str) -> Result<(), String> {
        let output = self.run(
            serial,
            &shell_arguments(&["forward", "--remove", local]),
            AGENT_COMMAND_TIMEOUT,
            AGENT_COMMAND_OUTPUT_LIMIT,
        )?;
        output.require_success("Unable to remove the FrameMetrics Agent forward.")?;
        Ok(())
    }
}

// ---------------------------------------------------------------------------
// Device and process discovery.
// ---------------------------------------------------------------------------

pub(in crate::frame) async fn list_devices() -> Result<Vec<FrameDeviceOption>, String> {
    let location = AdbLocator::new()
        .locate(None)
        .map_err(|error| error.to_string())?;
    let client = AdbClient::new(location);
    let cancel = CancellationToken::new();
    let devices = client
        .list_devices(&cancel)
        .map_err(|error| error.to_string())?;
    Ok(devices.iter().map(device_option).collect())
}

fn device_option(device: &AdbDevice) -> FrameDeviceOption {
    FrameDeviceOption {
        serial: device.serial.clone(),
        name: device.display_name(),
        online: device.is_online(),
    }
}

pub(in crate::frame) async fn list_processes(
    serial: &str,
) -> Result<Vec<FrameProcessOption>, String> {
    validate_serial(serial).map_err(|error| error.to_string())?;
    let adb: Arc<dyn FrameAdb> = Arc::new(ProcessFrameAdb::locate()?);
    list_processes_with(adb.as_ref(), serial)
}

fn list_processes_with(
    adb: &dyn FrameAdb,
    serial: &str,
) -> Result<Vec<FrameProcessOption>, String> {
    let capabilities = read_package_capabilities(adb, serial)?;
    let processes_text = adb
        .shell(
            serial,
            &shell_arguments(&["shell", "ps", "-A", "-o", "PID,PPID,USER,NAME"]),
        )?
        .require_success("Unable to read the device process table.")?;
    let processes = aps_adb::parse_processes(&processes_text)
        .map_err(|error| format!("Invalid process output: {error}"))?;
    Ok(frame_capture_processes(&capabilities, processes))
}

/// Port of profileableOrDebuggableProcesses(): only app processes whose package
/// is debuggable or profileable by shell, resolved to their owning package.
fn frame_capture_processes(
    package_capabilities: &BTreeMap<String, bool>,
    processes: Vec<aps_adb::AndroidProcess>,
) -> Vec<FrameProcessOption> {
    let packages: Vec<AndroidPackage> = package_capabilities
        .iter()
        .filter(|(_, allowed)| **allowed)
        .map(|(package, _)| AndroidPackage {
            package_name: package.clone(),
        })
        .collect();
    let snapshot = TargetSnapshot {
        packages,
        processes,
    };
    snapshot
        .application_processes()
        .into_iter()
        .map(|process| FrameProcessOption {
            pid: process.pid as i32,
            name: process.name,
            package_name: process.package_name,
        })
        .collect()
}

/// Returns package name -> profileable/debuggable by shell.
fn read_package_capabilities(
    adb: &dyn FrameAdb,
    serial: &str,
) -> Result<BTreeMap<String, bool>, String> {
    let list = adb.shell(
        serial,
        &shell_arguments(&["shell", "cat", "/data/system/packages.list"]),
    );
    if let Ok(output) = list
        && output.success
        && let Some(capabilities) = parse_packages_list(&output.stdout)
        && !capabilities.is_empty()
    {
        return Ok(capabilities);
    }
    let dumpsys = adb.shell(
        serial,
        &shell_arguments(&["shell", "dumpsys", "package", "packages"]),
    )?;
    let text = dumpsys.require_success(
        "Unable to read installed package capabilities; process discovery requires a debuggable or profileable target.",
    )?;
    let capabilities = parse_package_flags(&text);
    if capabilities.is_empty() {
        return Err(
            "Unable to determine any debuggable or profileable application package.".to_owned(),
        );
    }
    Ok(capabilities)
}

/// /data/system/packages.list: name uid debuggable ... profileable(index 6).
fn parse_packages_list(output: &str) -> Option<BTreeMap<String, bool>> {
    if output.len() > PACKAGES_OUTPUT_LIMIT {
        return None;
    }
    let mut capabilities = BTreeMap::new();
    for line in output.lines() {
        let line = line.trim();
        if line.is_empty() || line.len() > 4_096 {
            continue;
        }
        let columns: Vec<&str> = line.split_whitespace().collect();
        if columns.len() < 3 {
            continue;
        }
        let Some(package_name) = valid_package_name(columns[0]) else {
            continue;
        };
        if columns[1].parse::<u32>().is_err() {
            continue;
        }
        let Some(debuggable) = binary_flag(columns.get(2).copied()) else {
            continue;
        };
        let profileable = binary_flag(columns.get(6).copied()).unwrap_or(false);
        if debuggable || profileable {
            capabilities.insert(package_name.to_owned(), true);
        }
    }
    Some(capabilities)
}

fn binary_flag(value: Option<&str>) -> Option<bool> {
    match value {
        Some("0") => Some(false),
        Some("1") => Some(true),
        _ => None,
    }
}

/// dumpsys package packages fallback: Package [name] (..): flags=[.. DEBUGGABLE ..].
fn parse_package_flags(output: &str) -> BTreeMap<String, bool> {
    let mut capabilities = BTreeMap::new();
    let mut current: Option<String> = None;
    for line in output.lines() {
        let trimmed = line.trim();
        if let Some(rest) = trimmed.strip_prefix("Package [")
            && let Some((name, _)) = rest.split_once(']')
        {
            current = valid_package_name(name).map(str::to_owned);
            if let Some(name) = &current {
                capabilities.entry(name.clone()).or_insert(false);
            }
            continue;
        }
        let Some(name) = &current else {
            continue;
        };
        if has_flag_token(trimmed, "DEBUGGABLE", false)
            || has_flag_token(trimmed, "PROFILEABLE_BY_SHELL", true)
        {
            capabilities.insert(name.clone(), true);
        }
    }
    capabilities
}

fn has_flag_token(line: &str, token: &str, allow_private_prefix: bool) -> bool {
    line.split(|character: char| !(character.is_ascii_alphanumeric() || character == '_'))
        .any(|part| {
            part == token
                || (allow_private_prefix
                    && part
                        .strip_prefix("PRIVATE_FLAG_")
                        .is_some_and(|rest| rest == token))
        })
}

fn valid_package_name(name: &str) -> Option<&str> {
    if name.is_empty()
        || name.len() > 255
        || name.starts_with('-')
        || !name
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b':' | b'-'))
    {
        return None;
    }
    Some(name)
}

/// Port of parseDisplayRefreshRates(): observed rates are evidence, not a claim.
fn parse_display_refresh_rates(output: &str) -> Vec<f64> {
    const KEYS: [&str; 4] = [
        "mRefreshRate",
        "mRenderFrameRate",
        "refreshRate",
        "renderFrameRate",
    ];
    let mut rates = BTreeSet::new();
    for line in output.lines().take(20_000) {
        if line.len() > 32_768 {
            continue;
        }
        for key in KEYS {
            let mut search = line;
            while let Some(index) = search.find(key) {
                let rest = &search[index + key.len()..];
                if let Some(rate) = leading_rate(rest)
                    && rate > 0.0
                    && rate.is_finite()
                {
                    rates.insert((rate * 1_000.0).round() as i64);
                }
                search = &rest[1.min(rest.len())..];
                if search.is_empty() {
                    break;
                }
            }
        }
    }
    rates
        .into_iter()
        .take(MAX_OBSERVED_REFRESH_RATES)
        .map(|rate| rate as f64 / 1_000.0)
        .collect()
}

fn leading_rate(rest: &str) -> Option<f64> {
    let rest = rest.trim_start();
    let rest = rest
        .strip_prefix('=')
        .or_else(|| rest.strip_prefix(':'))
        .unwrap_or(rest);
    let rest = rest.trim_start();
    let digits: String = rest
        .chars()
        .take_while(|character| character.is_ascii_digit() || *character == '.')
        .collect();
    if digits.is_empty() {
        None
    } else {
        digits.parse().ok()
    }
}

// ---------------------------------------------------------------------------
// FrameMetrics Agent framed protocol (AgentFrameProtocol.kt).
// ---------------------------------------------------------------------------

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
enum AgentExpectedDurationSource {
    PlatformDeadline,
    RefreshRate,
    #[default]
    Unknown,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
struct AgentFrameStages {
    input_ns: Option<i64>,
    animation_ns: Option<i64>,
    layout_measure_ns: Option<i64>,
    draw_ns: Option<i64>,
    sync_ns: Option<i64>,
    command_issue_ns: Option<i64>,
    swap_buffers_ns: Option<i64>,
    gpu_ns: Option<i64>,
}

#[derive(Clone, Debug, PartialEq)]
struct AgentFrameSample {
    #[allow(dead_code)]
    sequence: i64,
    package_name: String,
    activity_name: Option<String>,
    window_id: Option<String>,
    intended_vsync_ns: Option<i64>,
    actual_vsync_ns: Option<i64>,
    frame_completed_ns: Option<i64>,
    expected_duration_ns: Option<i64>,
    expected_duration_source: AgentExpectedDurationSource,
    refresh_rate_hz: Option<f64>,
    frame_timeline_vsync_id: Option<i64>,
    total_duration_ns: Option<i64>,
    stages: AgentFrameStages,
    platform_jank: Option<bool>,
    platform_jank_rule_id: Option<String>,
    platform_jank_rule_version: Option<String>,
    jank_stats_jank: Option<bool>,
    jank_stats_reasons: BTreeSet<String>,
    jank_stats_rule_id: Option<String>,
    jank_stats_rule_version: Option<String>,
    states: BTreeMap<String, String>,
    eligible_for_jank: bool,
    surface_flinger_jank_type: Option<String>,
}

#[derive(Clone, Debug, Default, PartialEq)]
struct AgentFrameBatch {
    cursor: i64,
    frames: Vec<AgentFrameSample>,
    dropped_frames: i64,
    warnings: Vec<String>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct AgentSessionDescriptor {
    protocol_major: u32,
    protocol_minor: u32,
    socket_name: String,
    token: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
enum AgentProtocolError {
    Protocol(String),
    Remote { code: String, message: String },
}

impl std::fmt::Display for AgentProtocolError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Protocol(message) => formatter.write_str(message),
            Self::Remote { code, message } => write!(formatter, "{code}: {message}"),
        }
    }
}

fn agent_limits() -> Limits {
    Limits {
        max_bytes: MAX_AGENT_BATCH_BYTES as u64,
        max_depth: 16,
        max_nodes: 200_000,
        max_string_bytes: 64 * 1024,
        max_collection_items: 4_096,
    }
}

fn protocol_error(message: impl Into<String>) -> AgentProtocolError {
    AgentProtocolError::Protocol(message.into())
}

fn parse_session_descriptor(value: &str) -> Result<AgentSessionDescriptor, AgentProtocolError> {
    let value = value.trim();
    if value.is_empty() || value.len() > 64 * 1024 {
        return Err(protocol_error(
            "FrameMetrics Agent session descriptor is empty",
        ));
    }
    let parsed = parse_slice(value.as_bytes(), agent_limits())
        .map_err(|_| protocol_error("FrameMetrics Agent session descriptor is malformed"))?;
    let object = parsed
        .as_object()
        .ok_or_else(|| protocol_error("FrameMetrics Agent session descriptor must be an object"))?;
    let protocol_major = object
        .get("protocolMajor")
        .and_then(Value::as_u64)
        .and_then(|value| u32::try_from(value).ok())
        .ok_or_else(|| protocol_error("FrameMetrics Agent protocolMajor is invalid"))?;
    let protocol_minor = object
        .get("protocolMinor")
        .and_then(Value::as_u64)
        .and_then(|value| u32::try_from(value).ok())
        .ok_or_else(|| protocol_error("FrameMetrics Agent protocolMinor is invalid"))?;
    let socket_name = object
        .get("socketName")
        .and_then(Value::as_str)
        .filter(|name| {
            !name.is_empty()
                && name.len() <= 128
                && name
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || matches!(byte, b'.' | b'_' | b'-'))
        })
        .ok_or_else(|| protocol_error("FrameMetrics Agent socketName is invalid"))?
        .to_owned();
    let token = object
        .get("token")
        .and_then(Value::as_str)
        .filter(|token| {
            !token.is_empty()
                && token.len() <= 256
                && token
                    .bytes()
                    .all(|byte| byte.is_ascii_graphic() && byte != b' ')
        })
        .ok_or_else(|| protocol_error("FrameMetrics Agent token is invalid"))?
        .to_owned();
    Ok(AgentSessionDescriptor {
        protocol_major,
        protocol_minor,
        socket_name,
        token,
    })
}

fn read_agent_batch<R: Read>(reader: &mut R) -> Result<AgentFrameBatch, AgentProtocolError> {
    let header = read_agent_header(reader)?;
    if let Some(rest) = header.strip_prefix("ERROR ") {
        let mut parts = rest.splitn(2, ' ');
        let code = parts
            .next()
            .filter(|code| !code.is_empty() && code.len() <= 128)
            .ok_or_else(|| protocol_error("Malformed error response"))?;
        let message = parts.next().unwrap_or_default();
        return Err(AgentProtocolError::Remote {
            code: code.to_owned(),
            message: bounded_string(message, MAX_AGENT_STRING_BYTES)
                .ok_or_else(|| protocol_error("Malformed error response"))?,
        });
    }
    let mut parts = header.split(' ');
    let tag = parts.next().unwrap_or_default();
    let length = parts
        .next()
        .and_then(|length| length.parse::<usize>().ok())
        .filter(|length| *length <= MAX_AGENT_BATCH_BYTES)
        .ok_or_else(|| protocol_error("Malformed frame batch header"))?;
    if tag != "FRAMES" || parts.next().is_some() {
        return Err(protocol_error("Malformed frame batch header"));
    }
    let mut payload = vec![0u8; length];
    reader
        .read_exact(&mut payload)
        .map_err(|_| protocol_error("Truncated frame batch payload"))?;
    let value = parse_slice(&payload, agent_limits())
        .map_err(|_| protocol_error("Frame batch payload is malformed"))?;
    decode_agent_batch(&value)
}

fn read_agent_header<R: Read>(reader: &mut R) -> Result<String, AgentProtocolError> {
    let mut header = Vec::new();
    loop {
        if header.len() > MAX_AGENT_HEADER_BYTES {
            return Err(protocol_error("Frame response header is too large"));
        }
        let mut byte = [0u8; 1];
        reader
            .read_exact(&mut byte)
            .map_err(|_| protocol_error("Frame response ended before its header"))?;
        if byte[0] == b'\n' {
            return String::from_utf8(header)
                .map_err(|_| protocol_error("Frame response header is not UTF-8"));
        }
        header.push(byte[0]);
    }
}

#[cfg(test)]
fn write_agent_batch<W: Write>(
    batch: &AgentFrameBatch,
    writer: &mut W,
) -> Result<(), AgentProtocolError> {
    let payload = to_vec(&encode_agent_batch(batch), agent_limits())
        .map_err(|_| protocol_error("Frame batch could not be encoded"))?;
    if payload.len() > MAX_AGENT_BATCH_BYTES {
        return Err(protocol_error("Frame batch payload is too large"));
    }
    writer
        .write_all(format!("FRAMES {}\n", payload.len()).as_bytes())
        .and_then(|_| writer.write_all(&payload))
        .and_then(|_| writer.flush())
        .map_err(|_| protocol_error("Frame batch could not be written"))
}

fn decode_agent_batch(value: &Value) -> Result<AgentFrameBatch, AgentProtocolError> {
    let object = value
        .as_object()
        .ok_or_else(|| protocol_error("Frame batch must be an object"))?;
    let cursor = object
        .get("cursor")
        .and_then(Value::as_i64)
        .ok_or_else(|| protocol_error("Frame batch cursor is invalid"))?;
    let dropped_frames = object
        .get("droppedFrames")
        .and_then(Value::as_i64)
        .unwrap_or(0)
        .max(0);
    let warnings = match object.get("warnings") {
        None => Vec::new(),
        Some(value) => decode_string_array(value, MAX_AGENT_WARNINGS, "Frame batch warnings")?,
    };
    let frames = match object.get("frames") {
        None => Vec::new(),
        Some(value) => {
            let array = value
                .as_array()
                .ok_or_else(|| protocol_error("Frame batch frames must be an array"))?;
            if array.len() > MAX_AGENT_FRAMES_PER_BATCH {
                return Err(protocol_error("Frame batch exceeds its frame budget"));
            }
            array
                .iter()
                .map(decode_agent_frame)
                .collect::<Result<Vec<_>, _>>()?
        }
    };
    Ok(AgentFrameBatch {
        cursor,
        frames,
        dropped_frames,
        warnings,
    })
}

fn decode_agent_frame(value: &Value) -> Result<AgentFrameSample, AgentProtocolError> {
    let object = value
        .as_object()
        .ok_or_else(|| protocol_error("Frame sample must be an object"))?;
    let sequence = object
        .get("sequence")
        .and_then(Value::as_i64)
        .filter(|value| *value >= 0)
        .ok_or_else(|| protocol_error("Frame sample sequence is invalid"))?;
    let package_name = object
        .get("packageName")
        .and_then(Value::as_str)
        .and_then(|name| bounded_string(name, MAX_AGENT_STRING_BYTES))
        .filter(|name| !name.is_empty())
        .ok_or_else(|| protocol_error("Frame sample packageName is invalid"))?;
    let states = match object.get("states") {
        None => BTreeMap::new(),
        Some(value) => {
            let map = value
                .as_object()
                .ok_or_else(|| protocol_error("Frame sample states must be an object"))?;
            if map.len() > MAX_AGENT_STATES {
                return Err(protocol_error("Frame sample states exceed their budget"));
            }
            let mut states = BTreeMap::new();
            for (key, value) in map {
                let key = bounded_string(key, MAX_AGENT_STRING_BYTES)
                    .ok_or_else(|| protocol_error("Frame sample state key is invalid"))?;
                let value = value
                    .as_str()
                    .and_then(|value| bounded_string(value, MAX_AGENT_STRING_BYTES))
                    .ok_or_else(|| protocol_error("Frame sample state value is invalid"))?;
                states.insert(key, value);
            }
            states
        }
    };
    let jank_stats_reasons = match object.get("jankStatsReasons") {
        None => BTreeSet::new(),
        Some(value) => decode_string_array(value, MAX_AGENT_STATES, "JankStats reasons")?
            .into_iter()
            .collect(),
    };
    let expected_duration_source =
        match object.get("expectedDurationSource").and_then(Value::as_str) {
            None => AgentExpectedDurationSource::Unknown,
            Some("PLATFORM_DEADLINE") => AgentExpectedDurationSource::PlatformDeadline,
            Some("REFRESH_RATE") => AgentExpectedDurationSource::RefreshRate,
            Some("UNKNOWN") => AgentExpectedDurationSource::Unknown,
            Some(_) => {
                return Err(protocol_error(
                    "Frame sample expectedDurationSource is invalid",
                ));
            }
        };
    Ok(AgentFrameSample {
        sequence,
        package_name,
        activity_name: optional_bounded_string(object, "activityName")?,
        window_id: optional_bounded_string(object, "windowId")?,
        intended_vsync_ns: optional_i64(object, "intendedVsyncNs")?,
        actual_vsync_ns: optional_i64(object, "actualVsyncNs")?,
        frame_completed_ns: optional_i64(object, "frameCompletedNs")?,
        expected_duration_ns: optional_i64(object, "expectedDurationNs")?,
        expected_duration_source,
        refresh_rate_hz: optional_f64(object, "refreshRateHz")?,
        frame_timeline_vsync_id: optional_i64(object, "frameTimelineVsyncId")?,
        total_duration_ns: optional_i64(object, "totalDurationNs")?,
        stages: decode_agent_stages(object.get("stages"))?,
        platform_jank: optional_bool(object, "platformJank")?,
        platform_jank_rule_id: optional_bounded_string(object, "platformJankRuleId")?,
        platform_jank_rule_version: optional_bounded_string(object, "platformJankRuleVersion")?,
        jank_stats_jank: optional_bool(object, "jankStatsJank")?,
        jank_stats_reasons,
        jank_stats_rule_id: optional_bounded_string(object, "jankStatsRuleId")?,
        jank_stats_rule_version: optional_bounded_string(object, "jankStatsRuleVersion")?,
        states,
        eligible_for_jank: optional_bool(object, "eligibleForJank")?.unwrap_or(true),
        surface_flinger_jank_type: optional_bounded_string(object, "surfaceFlingerJankType")?,
    })
}

fn decode_agent_stages(value: Option<&Value>) -> Result<AgentFrameStages, AgentProtocolError> {
    let Some(value) = value else {
        return Ok(AgentFrameStages::default());
    };
    let object = value
        .as_object()
        .ok_or_else(|| protocol_error("Frame sample stages must be an object"))?;
    Ok(AgentFrameStages {
        input_ns: optional_i64(object, "inputNs")?,
        animation_ns: optional_i64(object, "animationNs")?,
        layout_measure_ns: optional_i64(object, "layoutMeasureNs")?,
        draw_ns: optional_i64(object, "drawNs")?,
        sync_ns: optional_i64(object, "syncNs")?,
        command_issue_ns: optional_i64(object, "commandIssueNs")?,
        swap_buffers_ns: optional_i64(object, "swapBuffersNs")?,
        gpu_ns: optional_i64(object, "gpuNs")?,
    })
}

fn decode_string_array(
    value: &Value,
    limit: usize,
    label: &str,
) -> Result<Vec<String>, AgentProtocolError> {
    let array = value
        .as_array()
        .ok_or_else(|| protocol_error(format!("{label} must be an array")))?;
    if array.len() > limit {
        return Err(protocol_error(format!("{label} exceed their budget")));
    }
    array
        .iter()
        .map(|item| {
            item.as_str()
                .and_then(|item| bounded_string(item, MAX_AGENT_STRING_BYTES))
                .ok_or_else(|| protocol_error(format!("{label} contain invalid text")))
        })
        .collect()
}

fn bounded_string(value: &str, limit: usize) -> Option<String> {
    (value.len() <= limit && !value.chars().any(char::is_control)).then(|| value.to_owned())
}

fn optional_bounded_string(
    object: &Map<String, Value>,
    key: &str,
) -> Result<Option<String>, AgentProtocolError> {
    match object.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(value) => value
            .as_str()
            .and_then(|value| bounded_string(value, MAX_AGENT_STRING_BYTES))
            .map(Some)
            .ok_or_else(|| protocol_error("Frame sample text field is invalid")),
    }
}

fn optional_i64(object: &Map<String, Value>, key: &str) -> Result<Option<i64>, AgentProtocolError> {
    match object.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(value) => value
            .as_i64()
            .filter(|value| *value >= 0)
            .map(Some)
            .ok_or_else(|| protocol_error("Frame sample numeric field is invalid")),
    }
}

fn optional_bool(
    object: &Map<String, Value>,
    key: &str,
) -> Result<Option<bool>, AgentProtocolError> {
    match object.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(value) => value
            .as_bool()
            .map(Some)
            .ok_or_else(|| protocol_error("Frame sample boolean field is invalid")),
    }
}

fn optional_f64(object: &Map<String, Value>, key: &str) -> Result<Option<f64>, AgentProtocolError> {
    match object.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(value) => value
            .as_f64()
            .filter(|value| value.is_finite() && *value >= 0.0)
            .map(Some)
            .ok_or_else(|| protocol_error("Frame sample rate field is invalid")),
    }
}

#[cfg(test)]
fn encode_agent_batch(batch: &AgentFrameBatch) -> Value {
    let mut object = Map::new();
    object.insert("cursor".to_owned(), Value::from(batch.cursor));
    if !batch.frames.is_empty() {
        object.insert(
            "frames".to_owned(),
            Value::Array(batch.frames.iter().map(encode_agent_frame).collect()),
        );
    }
    if batch.dropped_frames != 0 {
        object.insert(
            "droppedFrames".to_owned(),
            Value::from(batch.dropped_frames),
        );
    }
    if !batch.warnings.is_empty() {
        object.insert(
            "warnings".to_owned(),
            Value::Array(
                batch
                    .warnings
                    .iter()
                    .map(|warning| Value::from(warning.as_str()))
                    .collect(),
            ),
        );
    }
    Value::Object(object)
}

#[cfg(test)]
fn encode_agent_frame(frame: &AgentFrameSample) -> Value {
    let mut object = Map::new();
    object.insert("sequence".to_owned(), Value::from(frame.sequence));
    object.insert(
        "packageName".to_owned(),
        Value::from(frame.package_name.as_str()),
    );
    insert_optional_string(&mut object, "activityName", &frame.activity_name);
    insert_optional_string(&mut object, "windowId", &frame.window_id);
    insert_optional_i64(&mut object, "intendedVsyncNs", frame.intended_vsync_ns);
    insert_optional_i64(&mut object, "actualVsyncNs", frame.actual_vsync_ns);
    insert_optional_i64(&mut object, "frameCompletedNs", frame.frame_completed_ns);
    insert_optional_i64(
        &mut object,
        "expectedDurationNs",
        frame.expected_duration_ns,
    );
    let source = match frame.expected_duration_source {
        AgentExpectedDurationSource::PlatformDeadline => "PLATFORM_DEADLINE",
        AgentExpectedDurationSource::RefreshRate => "REFRESH_RATE",
        AgentExpectedDurationSource::Unknown => "UNKNOWN",
    };
    object.insert("expectedDurationSource".to_owned(), Value::from(source));
    if let Some(rate) = frame.refresh_rate_hz.filter(|rate| rate.is_finite()) {
        object.insert("refreshRateHz".to_owned(), Value::from(rate));
    }
    insert_optional_i64(
        &mut object,
        "frameTimelineVsyncId",
        frame.frame_timeline_vsync_id,
    );
    insert_optional_i64(&mut object, "totalDurationNs", frame.total_duration_ns);
    if frame.stages != AgentFrameStages::default() {
        let mut stages = Map::new();
        insert_optional_i64(&mut stages, "inputNs", frame.stages.input_ns);
        insert_optional_i64(&mut stages, "animationNs", frame.stages.animation_ns);
        insert_optional_i64(
            &mut stages,
            "layoutMeasureNs",
            frame.stages.layout_measure_ns,
        );
        insert_optional_i64(&mut stages, "drawNs", frame.stages.draw_ns);
        insert_optional_i64(&mut stages, "syncNs", frame.stages.sync_ns);
        insert_optional_i64(&mut stages, "commandIssueNs", frame.stages.command_issue_ns);
        insert_optional_i64(&mut stages, "swapBuffersNs", frame.stages.swap_buffers_ns);
        insert_optional_i64(&mut stages, "gpuNs", frame.stages.gpu_ns);
        object.insert("stages".to_owned(), Value::Object(stages));
    }
    if let Some(jank) = frame.platform_jank {
        object.insert("platformJank".to_owned(), Value::from(jank));
    }
    insert_optional_string(
        &mut object,
        "platformJankRuleId",
        &frame.platform_jank_rule_id,
    );
    insert_optional_string(
        &mut object,
        "platformJankRuleVersion",
        &frame.platform_jank_rule_version,
    );
    if let Some(jank) = frame.jank_stats_jank {
        object.insert("jankStatsJank".to_owned(), Value::from(jank));
    }
    if !frame.jank_stats_reasons.is_empty() {
        object.insert(
            "jankStatsReasons".to_owned(),
            Value::Array(
                frame
                    .jank_stats_reasons
                    .iter()
                    .map(|reason| Value::from(reason.as_str()))
                    .collect(),
            ),
        );
    }
    insert_optional_string(&mut object, "jankStatsRuleId", &frame.jank_stats_rule_id);
    insert_optional_string(
        &mut object,
        "jankStatsRuleVersion",
        &frame.jank_stats_rule_version,
    );
    if !frame.states.is_empty() {
        let mut states = Map::new();
        for (key, value) in &frame.states {
            states.insert(key.clone(), Value::from(value.as_str()));
        }
        object.insert("states".to_owned(), Value::Object(states));
    }
    if !frame.eligible_for_jank {
        object.insert("eligibleForJank".to_owned(), Value::from(false));
    }
    insert_optional_string(
        &mut object,
        "surfaceFlingerJankType",
        &frame.surface_flinger_jank_type,
    );
    Value::Object(object)
}

#[cfg(test)]
fn insert_optional_string(object: &mut Map<String, Value>, key: &str, value: &Option<String>) {
    if let Some(value) = value {
        object.insert(key.to_owned(), Value::from(value.as_str()));
    }
}

#[cfg(test)]
fn insert_optional_i64(object: &mut Map<String, Value>, key: &str, value: Option<i64>) {
    if let Some(value) = value {
        object.insert(key.to_owned(), Value::from(value));
    }
}

// ---------------------------------------------------------------------------
// Agent transport: adb forward + loopback socket request/response.
// ---------------------------------------------------------------------------

trait AgentFrameTransport: Send {
    fn start(&mut self) -> Result<AgentFrameBatch, String>;
    fn fetch(&mut self, after_cursor: i64) -> Result<AgentFrameBatch, String>;
    fn stop(&mut self) -> Vec<String>;
}

struct SocketAgentFrameTransport {
    adb: Arc<dyn FrameAdb>,
    serial: String,
    package_name: String,
    descriptor: Option<AgentSessionDescriptor>,
    forwarded_port: Option<u16>,
}

impl SocketAgentFrameTransport {
    fn new(adb: Arc<dyn FrameAdb>, serial: &str, package_name: &str) -> Self {
        Self {
            adb,
            serial: serial.to_owned(),
            package_name: package_name.to_owned(),
            descriptor: None,
            forwarded_port: None,
        }
    }

    fn read_descriptor(&self) -> Result<AgentSessionDescriptor, String> {
        let output = self.adb.shell(
            &self.serial,
            &shell_arguments(&[
                "shell",
                "run-as",
                &self.package_name,
                "cat",
                AGENT_SESSION_PATH,
            ]),
        )?;
        if !output.success {
            if output.stderr.contains("No such file or directory") {
                return Err(format!(
                    "Optional FrameMetrics Agent is not initialized in target app {}; the Agent session file is missing.",
                    self.package_name
                ));
            }
            return Err("Unable to read the FrameMetrics Agent session descriptor.".to_owned());
        }
        let descriptor =
            parse_session_descriptor(&output.stdout).map_err(|error| error.to_string())?;
        if descriptor.protocol_major != SUPPORTED_AGENT_PROTOCOL_MAJOR {
            return Err(format!(
                "Unsupported Agent protocol {}.{}.",
                descriptor.protocol_major, descriptor.protocol_minor
            ));
        }
        Ok(descriptor)
    }

    fn free_loopback_port() -> Result<u16, String> {
        let listener = TcpListener::bind(("127.0.0.1", 0)).map_err(|_| {
            "Unable to allocate a loopback port for the FrameMetrics Agent.".to_owned()
        })?;
        listener
            .local_addr()
            .map(|address| address.port())
            .map_err(|_| "Unable to read the loopback port for the FrameMetrics Agent.".to_owned())
    }

    fn request(&mut self, command: &str) -> Result<AgentFrameBatch, String> {
        let port = self
            .forwarded_port
            .ok_or_else(|| "ADB frame forwarding is not configured.".to_owned())?;
        let address = std::net::SocketAddr::from(([127, 0, 0, 1], port));
        let mut stream = TcpStream::connect_timeout(&address, SOCKET_TIMEOUT)
            .map_err(|_| "Unable to connect to the FrameMetrics Agent socket.".to_owned())?;
        stream
            .set_read_timeout(Some(SOCKET_TIMEOUT))
            .map_err(|_| "Unable to configure the FrameMetrics Agent socket.".to_owned())?;
        stream
            .set_write_timeout(Some(SOCKET_TIMEOUT))
            .map_err(|_| "Unable to configure the FrameMetrics Agent socket.".to_owned())?;
        stream
            .write_all(format!("{command}\n").as_bytes())
            .and_then(|_| stream.flush())
            .map_err(|_| "Unable to send a FrameMetrics Agent request.".to_owned())?;
        read_agent_batch(&mut stream).map_err(|error| error.to_string())
    }
}

impl AgentFrameTransport for SocketAgentFrameTransport {
    fn start(&mut self) -> Result<AgentFrameBatch, String> {
        let descriptor = self.read_descriptor()?;
        let port = Self::free_loopback_port()?;
        let local = format!("tcp:{port}");
        let remote = format!("localabstract:{}", descriptor.socket_name);
        self.adb
            .forward(&self.serial, &local, &remote)
            .map_err(|error| format!("Unable to forward the FrameMetrics Agent socket: {error}"))?;
        self.descriptor = Some(descriptor.clone());
        self.forwarded_port = Some(port);
        let command = format!("FRAME_CURSOR {}", descriptor.token);
        match self.request(&command) {
            Ok(batch) => Ok(batch),
            Err(error) => {
                let _ = self.adb.remove_forward(&self.serial, &local);
                self.descriptor = None;
                self.forwarded_port = None;
                Err(error)
            }
        }
    }

    fn fetch(&mut self, after_cursor: i64) -> Result<AgentFrameBatch, String> {
        let descriptor = self
            .descriptor
            .as_ref()
            .ok_or_else(|| "FrameMetrics Agent capture is not started.".to_owned())?;
        let command = format!("FRAMES {} {after_cursor}", descriptor.token);
        self.request(&command)
    }

    fn stop(&mut self) -> Vec<String> {
        let Some(port) = self.forwarded_port.take() else {
            return Vec::new();
        };
        self.descriptor = None;
        let local = format!("tcp:{port}");
        self.adb
            .remove_forward(&self.serial, &local)
            .err()
            .map(|error| {
                vec![format!(
                    "Unable to remove ADB frame forwarding on {local}: {error}"
                )]
            })
            .unwrap_or_default()
    }
}

// ---------------------------------------------------------------------------
// gfxinfo polling (GfxInfoPollingCaptureSession.kt).
// ---------------------------------------------------------------------------

struct GfxInfoPollingSession {
    adb: Arc<dyn FrameAdb>,
    serial: String,
    package_name: String,
    process_id: i32,
    session_id: String,
    seen: VecDeque<FrameIdentity>,
    next_frame_id: u64,
    has_polled: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct FrameIdentity {
    window_id: Option<String>,
    intended_vsync_ns: Option<i64>,
    frame_completed_ns: Option<i64>,
    present_ns: Option<i64>,
    total_duration_ns: Option<i64>,
}

impl GfxInfoPollingSession {
    fn new(
        adb: Arc<dyn FrameAdb>,
        serial: &str,
        package_name: &str,
        process_id: i32,
        session_id: &str,
    ) -> Result<Self, String> {
        validate_serial(serial).map_err(|error| error.to_string())?;
        valid_package_name(package_name)
            .ok_or_else(|| "Invalid target package name.".to_owned())?;
        Ok(Self {
            adb,
            serial: serial.to_owned(),
            package_name: package_name.to_owned(),
            process_id,
            session_id: session_id.to_owned(),
            seen: VecDeque::new(),
            next_frame_id: 0,
            has_polled: false,
        })
    }

    fn start(&mut self) -> Vec<String> {
        self.seen.clear();
        self.next_frame_id = 0;
        self.has_polled = false;
        let output = self.adb.shell(
            &self.serial,
            &shell_arguments(&["shell", "dumpsys", "gfxinfo", &self.package_name, "reset"]),
        );
        match output {
            Ok(output) if output.success => Vec::new(),
            _ => vec![
                "Unable to reset existing gfxinfo data; the initial poll may include older frames."
                    .to_owned(),
            ],
        }
    }

    fn poll(&mut self) -> Result<FramePollBatch, String> {
        let output = self.adb.shell(
            &self.serial,
            &shell_arguments(&[
                "shell",
                "dumpsys",
                "gfxinfo",
                &self.package_name,
                "framestats",
            ]),
        )?;
        let text = output.require_success("Unable to read gfxinfo FrameStats from the device.")?;
        let (parsed, mut warnings) =
            parse_gfxinfo_frames(&text, &self.session_id, &self.package_name, false)?;
        let mut overlapping = 0usize;
        let mut frames = Vec::with_capacity(parsed.len());
        for frame in &parsed {
            let identity = FrameIdentity {
                window_id: frame.window_id.clone(),
                intended_vsync_ns: frame.intended_vsync_ns,
                frame_completed_ns: frame.frame_completed_ns,
                present_ns: frame.present_ns,
                total_duration_ns: frame.total_duration_ns,
            };
            if self.seen.iter().any(|seen| *seen == identity) {
                overlapping += 1;
                continue;
            }
            if self.seen.len() >= MAX_DEDUPLICATION_IDENTITIES {
                self.seen.pop_front();
            }
            self.seen.push_back(identity);
            frames.push(gfxinfo_sample(
                &self.session_id,
                &self.package_name,
                self.process_id,
                self.next_frame_id,
                frame,
            ));
            self.next_frame_id += 1;
        }
        if self.has_polled && parsed.len() >= FRAMESTATS_WINDOW_WARNING_SIZE && overlapping == 0 {
            warnings.push(
                "The gfxinfo window had no overlap with the previous poll; frames may have been overwritten."
                    .to_owned(),
            );
        }
        self.has_polled = true;
        Ok(FramePollBatch { frames, warnings })
    }

    fn stop(&mut self) -> Vec<String> {
        Vec::new()
    }
}

// ---------------------------------------------------------------------------
// FrameMetrics Agent session (FrameMetricsAgentCaptureSession.kt).
// ---------------------------------------------------------------------------

struct FrameMetricsAgentSession {
    transport: Box<dyn AgentFrameTransport>,
    session_id: String,
    process_id: i32,
    cursor: i64,
    next_frame_id: u64,
}

impl FrameMetricsAgentSession {
    fn new(transport: Box<dyn AgentFrameTransport>, session_id: &str, process_id: i32) -> Self {
        Self {
            transport,
            session_id: session_id.to_owned(),
            process_id,
            cursor: -1,
            next_frame_id: 0,
        }
    }

    fn start(&mut self) -> Result<Vec<String>, String> {
        self.next_frame_id = 0;
        let initial = self.transport.start()?;
        self.cursor = initial.cursor;
        Ok(initial.warnings)
    }

    fn poll(&mut self) -> Result<FramePollBatch, String> {
        let batch = self.transport.fetch(self.cursor)?;
        self.cursor = batch.cursor;
        let mut warnings = batch.warnings.clone();
        if batch.dropped_frames > 0 {
            warnings.push(format!(
                "{} FrameMetrics reports were overwritten before the desktop viewer received them.",
                batch.dropped_frames
            ));
        }
        let frames = batch
            .frames
            .iter()
            .enumerate()
            .map(|(index, frame)| {
                let frame_id = self.next_frame_id;
                self.next_frame_id += 1;
                agent_sample(
                    frame_id,
                    &self.session_id,
                    self.process_id,
                    if index == 0 {
                        batch.dropped_frames.max(0)
                    } else {
                        0
                    },
                    frame,
                )
            })
            .collect();
        Ok(FramePollBatch { frames, warnings })
    }

    fn stop(&mut self) -> Vec<String> {
        self.transport.stop()
    }
}

// ---------------------------------------------------------------------------
// Preferred Agent capture with gfxinfo fallback (AgentPreferredOnlineFrameCapture).
// ---------------------------------------------------------------------------

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum OnlineCaptureMode {
    Agent,
    GfxInfo,
}

pub(in crate::frame) struct OnlineFrameCapture {
    mode: OnlineCaptureMode,
    gfxinfo_metadata: FrameCaptureSession,
    agent_metadata: FrameCaptureSession,
    gfxinfo: GfxInfoPollingSession,
    agent: FrameMetricsAgentSession,
}

impl OnlineFrameCapture {
    pub(in crate::frame) async fn start(&mut self) -> Result<Vec<String>, String> {
        Ok(self.start_blocking())
    }

    pub(in crate::frame) async fn poll(&mut self) -> Result<FramePollBatch, String> {
        self.poll_blocking()
    }

    pub(in crate::frame) async fn stop(&mut self) -> Result<Vec<String>, String> {
        Ok(self.stop_blocking())
    }

    pub(in crate::frame) fn metadata(&self) -> &FrameCaptureSession {
        match self.mode {
            OnlineCaptureMode::Agent => &self.agent_metadata,
            OnlineCaptureMode::GfxInfo => &self.gfxinfo_metadata,
        }
    }

    fn start_blocking(&mut self) -> Vec<String> {
        match self.agent.start() {
            Ok(warnings) => {
                self.mode = OnlineCaptureMode::Agent;
                warnings
            }
            Err(error) => {
                self.mode = OnlineCaptureMode::GfxInfo;
                let mut warnings = vec![format!(
                    "FrameMetrics Agent is unavailable; using gfxinfo polling: {error}"
                )];
                warnings.extend(self.gfxinfo.start());
                warnings
            }
        }
    }

    fn poll_blocking(&mut self) -> Result<FramePollBatch, String> {
        match self.mode {
            OnlineCaptureMode::Agent => self.agent.poll(),
            OnlineCaptureMode::GfxInfo => self.gfxinfo.poll(),
        }
    }

    fn stop_blocking(&mut self) -> Vec<String> {
        match self.mode {
            OnlineCaptureMode::Agent => self.agent.stop(),
            OnlineCaptureMode::GfxInfo => self.gfxinfo.stop(),
        }
    }
}

impl Drop for OnlineFrameCapture {
    fn drop(&mut self) {
        let _ = self.agent.stop();
    }
}

#[allow(clippy::too_many_arguments)]
fn online_capture_with(
    adb: Arc<dyn FrameAdb>,
    serial: &str,
    process: &FrameProcessOption,
    session_id: &str,
    android_api_level: Option<u32>,
    observed_refresh_rates_hz: Vec<f64>,
    provenance_warnings: Vec<String>,
) -> Result<OnlineFrameCapture, String> {
    validate_serial(serial).map_err(|error| error.to_string())?;
    valid_package_name(&process.package_name)
        .ok_or_else(|| "Invalid target package name.".to_owned())?;
    if process.pid <= 0 {
        return Err("Frame capture requires a positive process id.".to_owned());
    }
    let started_at_unix_ms = unix_ms();
    let provenance_complete = android_api_level.is_some();
    let gfxinfo_metadata = capture_session(
        session_id,
        FrameSource::Gfxinfo,
        started_at_unix_ms,
        &process.package_name,
        serial,
        android_api_level,
        None,
        gfxinfo_capabilities(),
        observed_refresh_rates_hz.clone(),
        provenance_complete,
        provenance_warnings.clone(),
    );
    let agent_metadata = capture_session(
        session_id,
        FrameSource::FrameMetrics,
        started_at_unix_ms,
        &process.package_name,
        serial,
        android_api_level,
        Some(SUPPORTED_AGENT_PROTOCOL_MAJOR.to_string()),
        frame_metrics_capabilities(),
        observed_refresh_rates_hz,
        provenance_complete,
        provenance_warnings,
    );
    let gfxinfo = GfxInfoPollingSession::new(
        adb.clone(),
        serial,
        &process.package_name,
        process.pid,
        session_id,
    )?;
    let transport = SocketAgentFrameTransport::new(adb, serial, &process.package_name);
    let agent = FrameMetricsAgentSession::new(Box::new(transport), session_id, process.pid);
    Ok(OnlineFrameCapture {
        mode: OnlineCaptureMode::Agent,
        gfxinfo_metadata,
        agent_metadata,
        gfxinfo,
        agent,
    })
}

pub(in crate::frame) fn open_online_capture(
    serial: &str,
    process: &FrameProcessOption,
    session_id: String,
    _data_dir: Option<PathBuf>,
) -> Result<OnlineFrameCapture, String> {
    let adb: Arc<dyn FrameAdb> = Arc::new(ProcessFrameAdb::locate()?);
    let android_api_level = adb.sdk_level(serial).ok();
    let mut provenance_warnings = Vec::new();
    if android_api_level.is_none() {
        provenance_warnings.push("Unable to read the device Android API level.".to_owned());
    }
    let observed_refresh_rates_hz =
        match adb.shell(serial, &shell_arguments(&["shell", "dumpsys", "display"])) {
            Ok(output) if output.success => {
                let rates = parse_display_refresh_rates(&output.stdout);
                if rates.is_empty() {
                    provenance_warnings.push(
                        "Unable to determine the active display refresh rate from dumpsys display."
                            .to_owned(),
                    );
                }
                rates
            }
            _ => {
                provenance_warnings.push("Unable to read display refresh rate.".to_owned());
                Vec::new()
            }
        };
    online_capture_with(
        adb,
        serial,
        process,
        &session_id,
        android_api_level,
        observed_refresh_rates_hz,
        provenance_warnings,
    )
}

// ---------------------------------------------------------------------------
// Bounded Android 12+ FrameTimeline capture (BoundedFrameTimelineCapture.kt).
// ---------------------------------------------------------------------------

fn timeline_config_body() -> String {
    format!(
        "buffers: {{\n  size_kb: {FRAME_TIMELINE_BUFFER_SIZE_KB}\n  fill_policy: RING_BUFFER\n}}\ndata_sources: {{\n  config {{\n    name: \"{FRAME_TIMELINE_DATA_SOURCE}\"\n  }}\n}}\n"
    )
}

fn timeline_api_gate(android_api_level: u32) -> Result<(), String> {
    if android_api_level < MINIMUM_FRAME_TIMELINE_API_LEVEL {
        return Err(format!(
            "Bounded FrameTimeline Capture requires Android 12 (API {MINIMUM_FRAME_TIMELINE_API_LEVEL}) or newer; the selected device is API {android_api_level}."
        ));
    }
    Ok(())
}

fn validate_timeline_request(
    serial: &str,
    process: &FrameProcessOption,
    duration_ms: u64,
) -> Result<(), String> {
    validate_serial(serial).map_err(|error| error.to_string())?;
    valid_package_name(&process.package_name)
        .ok_or_else(|| "Invalid target package name.".to_owned())?;
    if process.pid <= 0 {
        return Err("FrameTimeline capture requires a positive process id.".to_owned());
    }
    if duration_ms == 0 {
        return Err("FrameTimeline capture duration must be positive.".to_owned());
    }
    if duration_ms > MAX_TIMELINE_DURATION_MS {
        return Err(format!(
            "FrameTimeline capture duration must not exceed {MAX_TIMELINE_DURATION_MS} ms."
        ));
    }
    Ok(())
}

pub(in crate::frame) async fn capture_frame_timeline(
    serial: &str,
    process: &FrameProcessOption,
    duration_ms: u64,
    data_dir: Option<PathBuf>,
    cancel: &CancellationToken,
) -> Result<BoundedFrameTimelineCapture, String> {
    validate_timeline_request(serial, process, duration_ms)?;
    if cancel.is_cancelled() {
        return Err("FrameTimeline capture cancelled before device discovery.".into());
    }
    let mut adb = ProcessFrameAdb::locate()?;
    adb.cancel = cancel.clone();
    let android_api_level = adb.sdk_level(serial)?;
    timeline_api_gate(android_api_level)?;
    let capture_root = data_dir
        .unwrap_or_else(std::env::temp_dir)
        .join("frame-captures");
    let config_body = timeline_config_body();
    let recording = aps_perfetto::recording::record_bounded(
        &aps_perfetto::recording::RecordingRequest {
            adb_executable: &adb.executable,
            serial,
            output_root: &capture_root,
            config_body: &config_body,
            duration: Duration::from_millis(duration_ms),
        },
        cancel,
    )
    .map_err(|error| error.to_string())?;
    Ok(BoundedFrameTimelineCapture {
        trace_file: recording.paths.local_trace.clone(),
        started_at_unix_ms: recording.started_at_unix_ms,
        android_api_level,
        recording,
    })
}

// ---------------------------------------------------------------------------
// Small shared helpers.
// ---------------------------------------------------------------------------

fn unix_ms() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |duration| duration.as_millis() as u64)
}

fn unique_id(prefix: &str) -> String {
    let sequence = FRAME_SEQUENCE.fetch_add(1, Ordering::Relaxed);
    format!(
        "{prefix}-{:x}-{:x}-{sequence:x}",
        unix_ms(),
        std::process::id()
    )
}

// ---------------------------------------------------------------------------
// Tests: parsers/protocol/dedupe/preference/timeline shape with fakes only.
// ---------------------------------------------------------------------------

#[cfg(test)]
mod tests {
    use super::*;

    const HEADER: &str = "Flags,IntendedVsync,Vsync,HandleInputStart,AnimationStart,PerformTraversalsStart,DrawStart,FrameDeadline,FrameInterval,SyncQueued,SyncStart,IssueDrawCommandsStart,SwapBuffers,FrameCompleted";

    fn frame_row(intended_vsync_ns: u64) -> String {
        format!(
            "0,{i},{i},{a},{b},{c},{d},{e},8333333,{f},{g},{h},{j},{k}",
            i = intended_vsync_ns,
            a = intended_vsync_ns + 1,
            b = intended_vsync_ns + 2,
            c = intended_vsync_ns + 3,
            d = intended_vsync_ns + 4,
            e = intended_vsync_ns + 8_333_333,
            f = intended_vsync_ns + 5,
            g = intended_vsync_ns + 5,
            h = intended_vsync_ns + 6,
            j = intended_vsync_ns + 7,
            k = intended_vsync_ns + 8,
        )
    }

    fn frame_stats(rows: &[String]) -> String {
        let mut text = String::from("---PROFILEDATA---\n");
        text.push_str(HEADER);
        text.push('\n');
        for row in rows {
            text.push_str(row);
            text.push('\n');
        }
        text.push_str("---PROFILEDATA---\n");
        text
    }

    struct FakeAdb {
        responses: std::sync::Mutex<VecDeque<Result<ShellOutput, String>>>,
        shell_calls: std::sync::Mutex<Vec<Vec<String>>>,
        forwarded: std::sync::Mutex<Vec<(String, String)>>,
        removed: std::sync::Mutex<Vec<String>>,
        api_level: u32,
    }

    impl FakeAdb {
        fn new(api_level: u32) -> Self {
            Self {
                responses: std::sync::Mutex::new(VecDeque::new()),
                shell_calls: std::sync::Mutex::new(Vec::new()),
                forwarded: std::sync::Mutex::new(Vec::new()),
                removed: std::sync::Mutex::new(Vec::new()),
                api_level,
            }
        }

        fn push_response(&self, response: Result<ShellOutput, String>) {
            self.responses.lock().unwrap().push_back(response);
        }
    }

    impl FrameAdb for FakeAdb {
        fn shell(&self, _serial: &str, arguments: &[String]) -> Result<ShellOutput, String> {
            self.shell_calls.lock().unwrap().push(arguments.to_vec());
            self.responses
                .lock()
                .unwrap()
                .pop_front()
                .unwrap_or_else(|| Ok(ShellOutput::successful(String::new())))
        }

        fn sdk_level(&self, _serial: &str) -> Result<u32, String> {
            Ok(self.api_level)
        }

        fn forward(&self, _serial: &str, local: &str, remote: &str) -> Result<(), String> {
            self.forwarded
                .lock()
                .unwrap()
                .push((local.to_owned(), remote.to_owned()));
            Ok(())
        }

        fn remove_forward(&self, _serial: &str, local: &str) -> Result<(), String> {
            self.removed.lock().unwrap().push(local.to_owned());
            Ok(())
        }
    }

    fn gfxinfo_session(adb: Arc<FakeAdb>) -> GfxInfoPollingSession {
        let transport: Arc<dyn FrameAdb> = adb;
        GfxInfoPollingSession::new(transport, "device", "com.example", 42, "session").unwrap()
    }

    #[::core::prelude::v1::test]
    fn gfxinfo_polling_deduplicates_overlapping_snapshots() {
        let adb = Arc::new(FakeAdb::new(34));
        adb.push_response(Ok(ShellOutput::successful(String::new())));
        adb.push_response(Ok(ShellOutput::successful(frame_stats(&[frame_row(
            100_000_000,
        )]))));
        adb.push_response(Ok(ShellOutput::successful(frame_stats(&[
            frame_row(100_000_000),
            frame_row(200_000_000),
        ]))));
        let mut session = gfxinfo_session(adb);
        assert!(session.start().is_empty());
        let first = session.poll().unwrap();
        let second = session.poll().unwrap();
        assert_eq!(
            first
                .frames
                .iter()
                .map(|frame| frame.frame_id)
                .collect::<Vec<_>>(),
            [0]
        );
        assert_eq!(
            second
                .frames
                .iter()
                .map(|frame| frame.frame_id)
                .collect::<Vec<_>>(),
            [1]
        );
        assert_eq!(second.frames[0].process_id, Some(42));
        assert_eq!(
            second.frames[0].package_name.as_deref(),
            Some("com.example")
        );
        assert_eq!(second.frames[0].source, FrameSource::Gfxinfo);
    }

    #[::core::prelude::v1::test]
    fn gfxinfo_idle_snapshots_do_not_warn_or_interrupt_collection() {
        let adb = Arc::new(FakeAdb::new(34));
        adb.push_response(Ok(ShellOutput::successful(String::new())));
        adb.push_response(Ok(ShellOutput::successful(frame_stats(&[]))));
        adb.push_response(Ok(ShellOutput::successful(frame_stats(&[frame_row(
            100_000_000,
        )]))));
        adb.push_response(Ok(ShellOutput::successful(frame_stats(&[]))));
        adb.push_response(Ok(ShellOutput::successful(frame_stats(&[
            frame_row(100_000_000),
            frame_row(200_000_000),
        ]))));
        let mut session = gfxinfo_session(adb);
        assert!(session.start().is_empty());
        let initial = session.poll().unwrap();
        assert!(initial.frames.is_empty());
        assert!(initial.warnings.is_empty());
        let first = session.poll().unwrap();
        assert_eq!(first.frames.len(), 1);
        let idle = session.poll().unwrap();
        assert!(idle.frames.is_empty());
        let resumed = session.poll().unwrap();
        assert_eq!(
            resumed
                .frames
                .iter()
                .map(|frame| frame.frame_id)
                .collect::<Vec<_>>(),
            [1]
        );
    }

    #[::core::prelude::v1::test]
    fn gfxinfo_reset_failure_is_a_warning_and_polling_continues() {
        let adb = Arc::new(FakeAdb::new(34));
        adb.push_response(Err("reset unsupported".to_owned()));
        adb.push_response(Ok(ShellOutput::successful(frame_stats(&[frame_row(
            100_000_000,
        )]))));
        let mut session = gfxinfo_session(adb);
        assert_eq!(session.start().len(), 1);
        assert_eq!(session.poll().unwrap().frames.len(), 1);
    }

    #[::core::prelude::v1::test]
    fn gfxinfo_full_window_without_overlap_warns_without_inventing_drops() {
        let adb = Arc::new(FakeAdb::new(34));
        adb.push_response(Ok(ShellOutput::successful(String::new())));
        let first: Vec<String> = (0..110)
            .map(|index| frame_row(100_000_000 + index * 8_333_333))
            .collect();
        let second: Vec<String> = (0..110)
            .map(|index| frame_row(10_000_000_000 + index * 8_333_333))
            .collect();
        adb.push_response(Ok(ShellOutput::successful(frame_stats(&first))));
        adb.push_response(Ok(ShellOutput::successful(frame_stats(&second))));
        let mut session = gfxinfo_session(adb);
        session.start();
        session.poll().unwrap();
        let second = session.poll().unwrap();
        assert!(
            second
                .warnings
                .iter()
                .any(|warning| warning.contains("may have been overwritten"))
        );
        assert!(
            second
                .frames
                .iter()
                .all(|frame| frame.dropped_before_sample == 0)
        );
    }

    #[::core::prelude::v1::test]
    fn package_list_capabilities_select_debuggable_and_profileable_packages() {
        let output = "com.example.debug 10001 1 0 0 0 0\ncom.example.profileable 10002 0 0 0 0 1\ncom.example.hidden 10003 0 0 0 0 0\n";
        let capabilities = parse_packages_list(output).unwrap();
        assert_eq!(capabilities.get("com.example.debug"), Some(&true));
        assert_eq!(capabilities.get("com.example.profileable"), Some(&true));
        assert_eq!(capabilities.get("com.example.hidden"), None);
    }

    #[::core::prelude::v1::test]
    fn dumpsys_package_flags_select_profileable_by_shell_packages() {
        let output = "Packages:\n  Package [com.example.debug] (abc):\n    flags=[ DEBUGGABLE ]\n  Package [com.example.profileable] (def):\n    privateFlags=[ PRIVATE_FLAG_PROFILEABLE_BY_SHELL ]\n  Package [com.example.hidden] (ghi):\n    flags=[]\n";
        let capabilities = parse_package_flags(output);
        assert_eq!(capabilities.get("com.example.debug"), Some(&true));
        assert_eq!(capabilities.get("com.example.profileable"), Some(&true));
        assert_eq!(capabilities.get("com.example.hidden"), Some(&false));
    }

    #[::core::prelude::v1::test]
    fn frame_capture_processes_match_compose_profileable_rules() {
        let capabilities: BTreeMap<String, bool> = [
            ("com.example.debug".to_owned(), true),
            ("com.example.profileable".to_owned(), true),
        ]
        .into_iter()
        .collect();
        let processes = vec![
            aps_adb::AndroidProcess {
                pid: 10,
                parent_pid: 1,
                user: "u0_a10".to_owned(),
                name: "com.example.debug".to_owned(),
            },
            aps_adb::AndroidProcess {
                pid: 11,
                parent_pid: 1,
                user: "u0_a11".to_owned(),
                name: "com.example.profileable:renderer".to_owned(),
            },
            aps_adb::AndroidProcess {
                pid: 12,
                parent_pid: 1,
                user: "u0_a12".to_owned(),
                name: "com.example.hidden".to_owned(),
            },
        ];
        let options = frame_capture_processes(&capabilities, processes);
        assert_eq!(
            options,
            vec![
                FrameProcessOption {
                    pid: 10,
                    name: "com.example.debug".to_owned(),
                    package_name: "com.example.debug".to_owned(),
                },
                FrameProcessOption {
                    pid: 11,
                    name: "com.example.profileable:renderer".to_owned(),
                    package_name: "com.example.profileable".to_owned(),
                },
            ]
        );
    }

    #[::core::prelude::v1::test]
    fn display_refresh_rates_are_observed_and_bounded() {
        let output = "mRefreshRate=120.0\n  mRenderFrameRate: 90.0\nrefreshRate 60\nnoise mRefreshRate=0.0\n";
        let rates = parse_display_refresh_rates(output);
        assert_eq!(rates, vec![60.0, 90.0, 120.0]);
    }

    struct FakeAgentTransport {
        initial: Result<AgentFrameBatch, String>,
        polled: Option<Result<AgentFrameBatch, String>>,
        requested_cursor: std::sync::Mutex<Option<i64>>,
        stopped: bool,
    }

    impl AgentFrameTransport for FakeAgentTransport {
        fn start(&mut self) -> Result<AgentFrameBatch, String> {
            self.initial.clone()
        }

        fn fetch(&mut self, after_cursor: i64) -> Result<AgentFrameBatch, String> {
            *self.requested_cursor.lock().unwrap() = Some(after_cursor);
            self.polled
                .clone()
                .unwrap_or_else(|| Ok(AgentFrameBatch::default()))
        }

        fn stop(&mut self) -> Vec<String> {
            self.stopped = true;
            Vec::new()
        }
    }

    fn agent_frame(sequence: i64) -> AgentFrameSample {
        AgentFrameSample {
            sequence,
            package_name: "dev.example".to_owned(),
            activity_name: None,
            window_id: None,
            intended_vsync_ns: Some(1_000),
            actual_vsync_ns: None,
            frame_completed_ns: None,
            expected_duration_ns: Some(8_333_333),
            expected_duration_source: AgentExpectedDurationSource::RefreshRate,
            refresh_rate_hz: Some(120.0),
            frame_timeline_vsync_id: Some(99),
            total_duration_ns: Some(9_000_000),
            stages: AgentFrameStages {
                draw_ns: Some(4_000_000),
                ..AgentFrameStages::default()
            },
            platform_jank: Some(true),
            platform_jank_rule_id: Some(JANK_STATS_RULE_ID.to_owned()),
            platform_jank_rule_version: Some(JANK_STATS_RULE_VERSION.to_owned()),
            jank_stats_jank: Some(true),
            jank_stats_reasons: ["Slow draw".to_owned()].into_iter().collect(),
            jank_stats_rule_id: Some(JANK_STATS_RULE_ID.to_owned()),
            jank_stats_rule_version: Some(JANK_STATS_RULE_VERSION.to_owned()),
            states: [("fragment".to_owned(), "Feed".to_owned())]
                .into_iter()
                .collect(),
            eligible_for_jank: true,
            surface_flinger_jank_type: None,
        }
    }

    #[::core::prelude::v1::test]
    fn agent_session_starts_from_the_cursor_and_maps_incremental_frames() {
        let transport = FakeAgentTransport {
            initial: Ok(AgentFrameBatch {
                cursor: 40,
                frames: Vec::new(),
                dropped_frames: 0,
                warnings: vec!["agent warning".to_owned()],
            }),
            polled: Some(Ok(AgentFrameBatch {
                cursor: 42,
                frames: vec![agent_frame(41), agent_frame(42)],
                dropped_frames: 3,
                warnings: Vec::new(),
            })),
            requested_cursor: std::sync::Mutex::new(None),
            stopped: false,
        };
        let mut session = FrameMetricsAgentSession::new(Box::new(transport), "session", 123);
        assert_eq!(session.start().unwrap(), vec!["agent warning".to_owned()]);
        let batch = session.poll().unwrap();
        assert_eq!(
            batch
                .frames
                .iter()
                .map(|frame| frame.frame_id)
                .collect::<Vec<_>>(),
            [0, 1]
        );
        assert_eq!(batch.frames[0].process_id, Some(123));
        assert_eq!(batch.frames[0].dropped_before_sample, 3);
        assert_eq!(batch.frames[1].dropped_before_sample, 0);
        assert_eq!(batch.frames[0].refresh_rate_hz, Some(120.0));
        assert_eq!(batch.frames[0].frame_timeline_vsync_id, Some(99));
        assert_eq!(
            batch.frames[0].platform_jank_rule_id.as_deref(),
            Some(JANK_STATS_RULE_ID)
        );
        assert_eq!(
            batch.frames[0].expected_duration_source,
            ExpectedDurationSource::RefreshRate
        );
        assert_eq!(batch.frames[0].jank_stats_jank, Some(true));
        assert_eq!(
            batch.frames[0]
                .jank_stats_reasons
                .iter()
                .next()
                .map(String::as_str),
            Some("Slow draw")
        );
        assert_eq!(batch.frames[0].fragment_name.as_deref(), Some("Feed"));
        assert_eq!(batch.warnings.len(), 1);
    }

    #[::core::prelude::v1::test]
    fn agent_prefers_the_agent_and_falls_back_to_gfxinfo() {
        let adb = Arc::new(FakeAdb::new(34));
        let adb_trait: Arc<dyn FrameAdb> = adb.clone();
        let fallback_transport = FakeAgentTransport {
            initial: Err("Optional FrameMetrics Agent is not initialized".to_owned()),
            polled: None,
            requested_cursor: std::sync::Mutex::new(None),
            stopped: false,
        };
        let mut capture = online_capture_with(
            adb_trait,
            "device",
            &FrameProcessOption {
                pid: 42,
                name: "com.example".to_owned(),
                package_name: "com.example".to_owned(),
            },
            "session",
            Some(34),
            vec![120.0],
            Vec::new(),
        )
        .unwrap();
        capture.agent = FrameMetricsAgentSession::new(Box::new(fallback_transport), "session", 42);
        adb.push_response(Ok(ShellOutput::successful(String::new())));
        adb.push_response(Ok(ShellOutput::successful(frame_stats(&[frame_row(
            100_000_000,
        )]))));
        let warnings = capture.start_blocking();
        assert!(
            warnings
                .iter()
                .any(|warning| warning.contains("using gfxinfo polling"))
        );
        assert_eq!(capture.metadata().source, FrameSource::Gfxinfo);
        let batch = capture.poll_blocking().unwrap();
        assert_eq!(batch.frames.len(), 1);
    }

    #[::core::prelude::v1::test]
    fn agent_metadata_wins_when_the_agent_starts() {
        let adb = Arc::new(FakeAdb::new(34));
        let transport = FakeAgentTransport {
            initial: Ok(AgentFrameBatch {
                cursor: 0,
                frames: Vec::new(),
                dropped_frames: 0,
                warnings: Vec::new(),
            }),
            polled: Some(Ok(AgentFrameBatch::default())),
            requested_cursor: std::sync::Mutex::new(None),
            stopped: false,
        };
        let mut capture = online_capture_with(
            adb,
            "device",
            &FrameProcessOption {
                pid: 42,
                name: "com.example".to_owned(),
                package_name: "com.example".to_owned(),
            },
            "session",
            Some(34),
            vec![120.0],
            Vec::new(),
        )
        .unwrap();
        capture.agent = FrameMetricsAgentSession::new(Box::new(transport), "session", 42);
        let warnings = capture.start_blocking();
        assert!(warnings.is_empty());
        assert_eq!(capture.metadata().source, FrameSource::FrameMetrics);
        assert_eq!(capture.metadata().agent_protocol.as_deref(), Some("1"));
    }

    #[::core::prelude::v1::test]
    fn protocol_round_trips_a_bounded_frame_batch() {
        let batch = AgentFrameBatch {
            cursor: 7,
            frames: vec![agent_frame(7)],
            dropped_frames: 2,
            warnings: vec!["warning".to_owned()],
        };
        let mut bytes = Vec::new();
        write_agent_batch(&batch, &mut bytes).unwrap();
        let decoded = read_agent_batch(&mut bytes.as_slice()).unwrap();
        assert_eq!(decoded, batch);
    }

    #[::core::prelude::v1::test]
    fn protocol_surfaces_stable_remote_errors() {
        let error = read_agent_batch(&mut "ERROR FRAME_UNAVAILABLE API 24 required\n".as_bytes())
            .unwrap_err();
        assert_eq!(
            error,
            AgentProtocolError::Remote {
                code: "FRAME_UNAVAILABLE".to_owned(),
                message: "API 24 required".to_owned(),
            }
        );
    }

    #[::core::prelude::v1::test]
    fn protocol_rejects_malformed_headers_and_unbounded_payloads() {
        assert!(read_agent_batch(&mut "FRAMES\n".as_bytes()).is_err());
        assert!(read_agent_batch(&mut "FRAMES abc\n".as_bytes()).is_err());
        assert!(read_agent_batch(&mut "FRAMES 999999999999\n".as_bytes()).is_err());
        assert!(read_agent_batch(&mut "GARBAGE 3\nabc".as_bytes()).is_err());
        assert!(read_agent_batch(&mut "FRAMES 3\n{}".as_bytes()).is_err());
        assert!(
            read_agent_batch(&mut "".as_bytes())
                .unwrap_err()
                .to_string()
                .contains("ended before its header")
        );
    }

    #[::core::prelude::v1::test]
    fn session_descriptor_validates_protocol_fields() {
        let descriptor = parse_session_descriptor(
            r#"{"protocolMajor":1,"protocolMinor":0,"socketName":"agentperf.dev_example","token":"secret"}"#,
        )
        .unwrap();
        assert_eq!(descriptor.protocol_major, 1);
        assert_eq!(descriptor.socket_name, "agentperf.dev_example");
        assert_eq!(descriptor.token, "secret");
        assert!(
            parse_session_descriptor(
                r#"{"protocolMajor":1,"protocolMinor":0,"socketName":"bad name","token":"secret"}"#
            )
            .is_err()
        );
        assert!(
            parse_session_descriptor(
                r#"{"protocolMajor":1,"protocolMinor":0,"socketName":"ok","token":"a b"}"#
            )
            .is_err()
        );
    }

    #[::core::prelude::v1::test]
    fn timeline_body_preserves_buffer_source_but_cannot_override_owned_limits() {
        let config = timeline_config_body();
        assert!(config.contains("size_kb: 32768"));
        assert!(config.contains("fill_policy: RING_BUFFER"));
        assert!(config.contains("name: \"android.surfaceflinger.frametimeline\""));
        assert!(!config.contains("duration_ms"));
        assert!(!config.contains("max_file_size_bytes"));
        assert!(!config.contains("write_into_file"));
        assert_eq!(MAX_TIMELINE_DURATION_MS, 10_000);
    }

    #[::core::prelude::v1::test]
    fn timeline_uses_owned_helper_and_the_current_cancellation_token() {
        let source = include_str!("capture.rs");
        let adapter = source
            .split("pub(in crate::frame) async fn capture_frame_timeline(")
            .nth(1)
            .unwrap()
            .split("// Small shared helpers.")
            .next()
            .unwrap();
        assert!(adapter.contains("aps_perfetto::recording::record_bounded("));
        assert!(adapter.contains("cancel,"));
        assert!(adapter.contains("recording.paths.local_trace.clone()"));
        assert!(
            adapter
                .find("timeline_api_gate(android_api_level)?")
                .unwrap()
                < adapter.find("record_bounded(").unwrap()
        );
        assert!(!adapter.contains("\"sh\""));
        assert!(!adapter.contains("timeline_start_command"));
        assert!(!adapter.contains("rm\", \"-f"));
    }

    #[::core::prelude::v1::test]
    fn timeline_api_gate_requires_android_twelve_before_any_recording() {
        assert!(
            timeline_api_gate(30)
                .unwrap_err()
                .contains("Android 12 (API 31)")
        );
        assert!(timeline_api_gate(31).is_ok());
        assert!(timeline_api_gate(34).is_ok());
    }

    #[::core::prelude::v1::test]
    fn timeline_request_validation_rejects_invalid_targets_and_durations() {
        let process = FrameProcessOption {
            pid: 0,
            name: "p".to_owned(),
            package_name: "dev.example.app".to_owned(),
        };
        assert!(validate_timeline_request("device", &process, 1_000).is_err());
        let process = FrameProcessOption {
            pid: 42,
            name: "p".to_owned(),
            package_name: "dev.example.app".to_owned(),
        };
        assert!(validate_timeline_request("device", &process, 0).is_err());
        assert!(
            validate_timeline_request("device", &process, MAX_TIMELINE_DURATION_MS + 1).is_err()
        );
        assert!(validate_timeline_request("-bad", &process, 1_000).is_err());
        assert!(validate_timeline_request("device", &process, 1_000).is_ok());
    }

    #[::core::prelude::v1::test]
    fn online_capture_rejects_invalid_process_targets() {
        let adb: Arc<dyn FrameAdb> = Arc::new(FakeAdb::new(34));
        let invalid_name = FrameProcessOption {
            pid: 1,
            name: "x".to_owned(),
            package_name: "-bad".to_owned(),
        };
        assert!(
            online_capture_with(
                adb.clone(),
                "device",
                &invalid_name,
                "s",
                Some(34),
                vec![],
                vec![]
            )
            .is_err()
        );
        let invalid_pid = FrameProcessOption {
            pid: 0,
            name: "x".to_owned(),
            package_name: "dev.example".to_owned(),
        };
        assert!(
            online_capture_with(adb, "device", &invalid_pid, "s", Some(34), vec![], vec![])
                .is_err()
        );
    }
}

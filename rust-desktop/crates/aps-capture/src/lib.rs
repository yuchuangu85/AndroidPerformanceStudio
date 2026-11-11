//! Explicit, bounded CPU capture. Nothing runs on constructing a plan. A plan
//! is single-use and must be confirmed against device, PID and /proc start ticks.
//! Cancellation terminates owned host ADB, not an arbitrary remote process.
mod config;
mod duration;
mod remote;
pub use config::{EventScope, SamplingConfiguration, SamplingRate, SamplingTemplate};
pub use duration::{
    CaptureDuration, MANUAL_CAPTURE_TIMEOUT, MANUAL_LEASE_SECONDS, MAX_TIMED_CAPTURE,
};

use aps_adb::{AdbClient, DeviceState, validate_serial};
use aps_contracts::SourceDigest;
use aps_platform::{CancellationToken, ProcessRequest, ProcessRunner, atomic_write};
use std::{
    fs,
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    thread,
    time::{Duration, Instant},
};

// ProcessRunner's hard per-stream cap is 64 MiB. This tighter import limit
// bounds both the transfer buffer and the bytes subsequently written to disk.
pub const MAX_ARTIFACT: u64 = 64 * 1024 * 1024;
pub const MAX_PLAN_AGE: Duration = Duration::from_secs(60);
const REMOTE_PREFIX: &str = "/data/local/tmp/aps-rust-";

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum CaptureState {
    Planned,
    Confirmed,
    Rechecking,
    Preparing,
    Recording,
    Pulling,
    Converting,
    Stopping,
    Complete,
    Cancelled,
    CancellationUnverified,
    StopUnverified,
    Failed,
}
#[derive(Clone)]
pub struct CaptureHandle {
    state: Arc<Mutex<CaptureState>>,
    cancel: CancellationToken,
    stop: Arc<AtomicBool>,
}
impl CaptureHandle {
    pub fn state(&self) -> CaptureState {
        self.state.lock().expect("capture state lock").clone()
    }
    pub fn cancel(&self) -> bool {
        let state = self.state.lock().expect("capture state lock");
        if matches!(
            *state,
            CaptureState::Complete
                | CaptureState::Cancelled
                | CaptureState::Failed
                | CaptureState::CancellationUnverified
                | CaptureState::StopUnverified
        ) {
            return false;
        }
        self.cancel.cancel();
        true
    }

    pub fn can_stop(&self) -> bool {
        self.state() == CaptureState::Recording && !self.cancel.is_cancelled()
    }

    /// Request graceful completion, not cancellation. The worker verifies exit
    /// before pulling; cancellation accepted before final publication still wins.
    pub fn stop(&self) -> bool {
        let mut state = self.state.lock().expect("capture state lock");
        if *state != CaptureState::Recording || self.cancel.is_cancelled() {
            return false;
        }
        self.stop.store(true, Ordering::Release);
        *state = CaptureState::Stopping;
        true
    }
}
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum CallGraphMode {
    #[default]
    Dwarf,
    FramePointer,
    None,
}
impl CallGraphMode {
    pub const ALL: [Self; 3] = [Self::Dwarf, Self::FramePointer, Self::None];

    pub fn arguments(self) -> &'static [&'static str] {
        match self {
            Self::Dwarf => &["-g"],
            Self::FramePointer => &["--call-graph", "fp"],
            Self::None => &[],
        }
    }
}

#[derive(Debug, Clone)]
pub struct CpuCaptureRequest {
    pub run_id: String,
    pub serial: String,
    pub pid: u32,
    pub duration: CaptureDuration,
    pub sampling: SamplingConfiguration,
    /// Optional explicit host simpleperf binary; exact Kotlin converter argv.
    pub converter: Option<PathBuf>,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct TargetObservation {
    pub serial: String,
    pub pid: u32,
    pub process_name: String,
    pub boot_id: String,
    pub start_ticks: u64,
}
#[derive(Debug, Clone)]
pub struct CapturedArtifact {
    pub path: PathBuf,
    pub digest: SourceDigest,
    pub size_bytes: u64,
    pub provenance: &'static str,
    pub input_digest: Option<SourceDigest>,
}
#[derive(Debug, Clone)]
pub struct CaptureOutput {
    pub raw: CapturedArtifact,
    pub protobuf: Option<CapturedArtifact>,
    pub target: TargetObservation,
    /// Present only when explicitly retained; successful capture cleans owned remote files.
    pub retained_remote_path: Option<String>,
    pub completion: CaptureCompletion,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum CaptureCompletion {
    RecorderExited,
    StopRequested,
}
pub struct CaptureController {
    adb: AdbClient,
    artifact_root: PathBuf,
}
pub struct PlannedCapture {
    controller: CaptureController,
    request: CpuCaptureRequest,
    observed: TargetObservation,
    remote_directory: String,
    created: Instant,
    converter_digest: Option<SourceDigest>,
    app_package: Option<String>,
}
pub struct ConfirmedCapture {
    plan: PlannedCapture,
    handle: CaptureHandle,
}

impl CaptureController {
    pub fn new(adb: AdbClient, artifact_root: PathBuf) -> Result<Self, String> {
        if !artifact_root.is_absolute() {
            return Err("capture artifact root must be absolute".into());
        }
        Ok(Self { adb, artifact_root })
    }
    /// Read-only: device readiness, process row and process-instance observation.
    pub fn plan_cpu(
        &self,
        request: CpuCaptureRequest,
        cancel: &CancellationToken,
    ) -> Result<PlannedCapture, String> {
        validate_request(&request)?;
        let observed = observe(&self.adb, &request.serial, request.pid, cancel)?;
        // Match the frozen PID to the installed-package discovery projection;
        // never infer an --app argument from arbitrary process text.
        let app_package = self
            .adb
            .target_snapshot(&request.serial, cancel)
            .map_err(|error| error.to_string())?
            .application_processes()
            .into_iter()
            .find(|process| process.pid == request.pid && process.name == observed.process_name)
            .map(|process| process.package_name)
            .filter(|package| {
                !package.is_empty()
                    && package.len() <= 255
                    && package.split('.').all(|part| {
                        !part.is_empty()
                            && part
                                .bytes()
                                .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_')
                    })
            });
        let converter_digest = request
            .converter
            .as_ref()
            .map(|path| SourceDigest::hash_file(path, 256 * 1024 * 1024))
            .transpose()?;
        if cancel.is_cancelled() {
            return Err("capture planning cancelled".into());
        }
        Ok(PlannedCapture {
            controller: Self {
                adb: self.adb.clone(),
                artifact_root: self.artifact_root.clone(),
            },
            remote_directory: format!("{REMOTE_PREFIX}{}", request.run_id),
            request,
            observed,
            created: Instant::now(),
            converter_digest,
            app_package,
        })
    }
}
impl PlannedCapture {
    pub fn target(&self) -> &TargetObservation {
        &self.observed
    }
    pub fn remote_directory(&self) -> &str {
        &self.remote_directory
    }
    pub fn request(&self) -> &CpuCaptureRequest {
        &self.request
    }
    pub fn local_directory(&self) -> PathBuf {
        self.controller.artifact_root.join(&self.request.run_id)
    }
    pub fn is_expired(&self) -> bool {
        self.created.elapsed() > MAX_PLAN_AGE
    }
    pub fn confirm(
        self,
        serial: &str,
        pid: u32,
        boot_id: &str,
        start_ticks: u64,
    ) -> Result<ConfirmedCapture, String> {
        if self.is_expired() {
            return Err("capture plan expired; inspect the target again".into());
        }
        if serial != self.observed.serial
            || pid != self.observed.pid
            || boot_id != self.observed.boot_id
            || start_ticks != self.observed.start_ticks
        {
            return Err("capture confirmation does not match observed process instance".into());
        }
        Ok(ConfirmedCapture {
            plan: self,
            handle: CaptureHandle {
                state: Arc::new(Mutex::new(CaptureState::Confirmed)),
                cancel: CancellationToken::new(),
                stop: Arc::new(AtomicBool::new(false)),
            },
        })
    }
}
impl ConfirmedCapture {
    pub fn handle(&self) -> CaptureHandle {
        self.handle.clone()
    }
    pub fn run(self) -> Result<CaptureOutput, String> {
        let result = self.run_inner();
        let failed_phase = self.handle.state();
        if result.is_err()
            && !matches!(
                self.handle.state(),
                CaptureState::CancellationUnverified | CaptureState::StopUnverified
            )
        {
            self.set(if self.handle.cancel.is_cancelled() {
                CaptureState::Cancelled
            } else {
                CaptureState::Failed
            });
        }
        result.map_err(|error| format!("CPU capture {failed_phase:?}: {error}"))
    }
    fn run_inner(&self) -> Result<CaptureOutput, String> {
        self.set(CaptureState::Rechecking);
        let request = &self.plan.request;
        let current = observe(
            &self.plan.controller.adb,
            &request.serial,
            request.pid,
            &self.handle.cancel,
        )?;
        if current != self.plan.observed {
            return Err("capture target process instance changed".into());
        }
        if self.handle.cancel.is_cancelled() {
            return Err("capture cancelled".into());
        }
        let local = self.plan.controller.artifact_root.join(&request.run_id);
        create_session_directory(&self.plan.controller.artifact_root, &local)?;
        let mut cleanup = SessionCleanup::new(local.clone());
        let remote_data = format!("{}/perf.data", self.plan.remote_directory);
        self.set(CaptureState::Preparing);
        self.adb(
            &["shell", "mkdir", "-m", "700", &self.plan.remote_directory],
            Duration::from_secs(30),
        )?;
        let mut remote = remote::RemoteRecording::new(self);
        let result = (|| {
            let pid = request.pid.to_string();
            self.recheck_target()?;
            if request.duration == CaptureDuration::Manual {
                self.refresh_manual_lease(0)?;
            }
            remote.launched = true;
            let remote_pid = self.start_remote_recording(&pid, &remote_data)?;
            self.recheck_target()?;
            self.set(CaptureState::Recording);
            self.await_remote_recording(remote_pid, &remote_data)?;
            remote.stopped = true;
            self.recheck_target()?;
            self.set(CaptureState::Pulling);
            let raw_path = local.join("perf.data");
            let bytes = self.read_remote_artifact(&remote_data)?;
            if self.handle.cancel.is_cancelled() {
                return Err("capture cancelled".into());
            }
            atomic_write(&raw_path, &bytes).map_err(|_| "cannot write bounded capture artifact")?;
            let raw = artifact(
                raw_path,
                "device simpleperf record; bounded adb exec-out cat",
                None,
            )?;
            let protobuf = if let Some(converter) = &request.converter {
                self.set(CaptureState::Converting);
                let output = local.join("report-sample.pb");
                // No converter-created regular output exists while it runs. On
                // Unix the explicit -o target is the inherited stdout pipe; the
                // existing process runner enforces its 64 MiB hard read budget.
                // A converter which cannot write this stream fails closed.
                if Some(SourceDigest::hash_file(converter, 256 * 1024 * 1024)?)
                    != self.plan.converter_digest
                {
                    return Err("selected converter changed since capture planning".into());
                }
                let bytes = convert_bounded(converter, &raw.path, &self.handle.cancel)?;
                atomic_write(&output, &bytes)
                    .map_err(|_| "cannot write bounded protobuf artifact")?;
                Some(artifact(
                    output,
                    "host simpleperf report-sample --protobuf --show-callchain; bounded stdout",
                    Some(raw.digest),
                )?)
            } else {
                None
            };
            if self.handle.cancel.is_cancelled() {
                return Err("capture cancelled".into());
            }
            self.recheck_target()?;
            Ok(CaptureOutput {
                raw,
                protobuf,
                target: current,
                retained_remote_path: None,
                completion: if self.handle.stop.load(Ordering::Acquire) {
                    CaptureCompletion::StopRequested
                } else {
                    CaptureCompletion::RecorderExited
                },
            })
        })();
        let remote_cleanup = remote.cleanup();
        match (result, remote_cleanup) {
            (Ok(output), Ok(())) => {
                let mut state = self.handle.state.lock().expect("capture state lock");
                if self.handle.cancel.is_cancelled() {
                    return Err("capture cancelled before artifact publication".into());
                }
                cleanup.disarm();
                *state = CaptureState::Complete;
                Ok(output)
            }
            (Err(error), Err(cleanup)) => Err(format!("{error}; {cleanup}")),
            (Err(error), _) | (_, Err(error)) => Err(error),
        }
    }
    fn recheck_target(&self) -> Result<(), String> {
        let current = observe(
            &self.plan.controller.adb,
            &self.plan.request.serial,
            self.plan.request.pid,
            &self.handle.cancel,
        )?;
        if current != self.plan.observed {
            return Err("capture target process instance changed".into());
        }
        Ok(())
    }
    fn read_remote_artifact(&self, remote_path: &str) -> Result<Vec<u8>, String> {
        // `exec-out` preserves binary bytes; the path is generated from a
        // restricted run ID, never arbitrary user shell text. The runner stops
        // the owned ADB child immediately when stdout exceeds 64 MiB.
        let mut process = ProcessRequest::new(&self.plan.controller.adb.location().executable)
            .args([
                "-s",
                self.plan.request.serial.as_str(),
                "exec-out",
                "cat",
                remote_path,
            ]);
        process.timeout = Duration::from_secs(180);
        process.stdout_limit = MAX_ARTIFACT as usize;
        process.stderr_limit = 64 * 1024;
        let result = ProcessRunner
            .run(&process, &self.handle.cancel)
            .map_err(|e| e.to_string())?;
        if !result.status.success() || result.stdout.is_empty() {
            return Err("bounded remote artifact read failed".into());
        }
        if !result.stdout.starts_with(b"PERFILE2") {
            return Err("simpleperf did not produce a valid perf.data artifact".into());
        }
        Ok(result.stdout)
    }
    fn adb(&self, tail: &[&str], timeout: Duration) -> Result<(), String> {
        let mut args = vec!["-s", self.plan.request.serial.as_str()];
        args.extend_from_slice(tail);
        let mut process =
            ProcessRequest::new(&self.plan.controller.adb.location().executable).args(args);
        process.timeout = timeout;
        process.stdout_limit = 1024 * 1024;
        process.stderr_limit = 1024 * 1024;
        let result = ProcessRunner
            .run(&process, &self.handle.cancel)
            .map_err(|e| e.to_string())?;
        if !result.status.success() {
            return Err("ADB capture command failed".into());
        }
        Ok(())
    }

    fn adb_output(
        &self,
        tail: &[&str],
        timeout: Duration,
        cancellation: &CancellationToken,
    ) -> Result<String, String> {
        let mut args = vec!["-s", self.plan.request.serial.as_str()];
        args.extend_from_slice(tail);
        let mut request =
            ProcessRequest::new(&self.plan.controller.adb.location().executable).args(args);
        request.timeout = timeout;
        request.stdout_limit = 64 * 1024;
        request.stderr_limit = 64 * 1024;
        let result = ProcessRunner
            .run(&request, cancellation)
            .map_err(|error| error.to_string())?;
        if !result.status.success() {
            return Err("ADB remote capture command failed".into());
        }
        String::from_utf8(result.stdout)
            .map_err(|_| "ADB remote capture output is not UTF-8".into())
    }

    fn adb_with_token(
        &self,
        tail: &[&str],
        timeout: Duration,
        cancellation: &CancellationToken,
    ) -> Result<(), String> {
        self.adb_output(tail, timeout, cancellation).map(|_| ())
    }
    fn set(&self, state: CaptureState) {
        *self.handle.state.lock().expect("capture state lock") = state;
    }
}
/// Only a freshly created, private run directory is eligible for automatic
/// cleanup. Failure removes exact output names, never another session or remote
/// directory. No path-based recursive deletion is used.
struct SessionCleanup {
    local: PathBuf,
    armed: bool,
}
impl SessionCleanup {
    fn new(local: PathBuf) -> Self {
        Self { local, armed: true }
    }
    fn disarm(&mut self) {
        self.armed = false;
    }
}
impl Drop for SessionCleanup {
    fn drop(&mut self) {
        if self.armed {
            for name in ["perf.data", "report-sample.pb"] {
                let _ = fs::remove_file(self.local.join(name));
            }
            let _ = fs::remove_dir(&self.local);
        }
    }
}
#[cfg(unix)]
fn convert_bounded(
    converter: &Path,
    input: &Path,
    cancel: &CancellationToken,
) -> Result<Vec<u8>, String> {
    let mut process = ProcessRequest::new(converter)
        .args(["report-sample", "--protobuf", "--show-callchain", "-i"])
        .arg(input)
        .args(["-o", "/dev/stdout"]);
    process.timeout = Duration::from_secs(180);
    process.stdout_limit = MAX_ARTIFACT as usize;
    process.stderr_limit = 1024 * 1024;
    let result = ProcessRunner
        .run(&process, cancel)
        .map_err(|error| error.to_string())?;
    if !result.status.success() {
        return Err("bounded simpleperf conversion failed".into());
    }
    validate_protobuf_prefix(&result.stdout)?;
    Ok(result.stdout)
}
#[cfg(not(unix))]
fn convert_bounded(_: &Path, _: &Path, _: &CancellationToken) -> Result<Vec<u8>, String> {
    Err("bounded converter output is not supported on this host; capture raw perf.data without conversion".into())
}
fn validate_protobuf_prefix(bytes: &[u8]) -> Result<(), String> {
    if bytes.len() > MAX_ARTIFACT as usize
        || bytes.len() < 12
        || !bytes.starts_with(b"SIMPLEPERF\x01\0")
    {
        return Err("converter did not produce a bounded SIMPLEPERF protobuf stream".into());
    }
    Ok(())
}
fn validate_request(request: &CpuCaptureRequest) -> Result<(), String> {
    validate_serial(&request.serial).map_err(|e| e.to_string())?;
    request.sampling.validate()?;
    request.duration.validate()?;
    if request.pid == 0
        || request.run_id.is_empty()
        || request.run_id.len() > 64
        || !request
            .run_id
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b == b'-')
    {
        return Err("invalid bounded CPU capture request".into());
    }
    if request.converter.is_some() && !cfg!(unix) {
        return Err("bounded Simpleperf conversion is available only on Unix hosts".into());
    }
    if let Some(path) = &request.converter
        && (!path.is_absolute() || !path.is_file())
    {
        return Err("converter must be an explicit absolute regular file".into());
    }
    Ok(())
}
fn observe(
    adb: &AdbClient,
    serial: &str,
    pid: u32,
    cancel: &CancellationToken,
) -> Result<TargetObservation, String> {
    let devices = adb.list_devices(cancel).map_err(|e| e.to_string())?;
    if !devices
        .iter()
        .any(|d| d.serial == serial && d.state == DeviceState::Device)
    {
        return Err("selected ADB device is not ready".into());
    }
    let process = adb
        .list_processes(serial, cancel)
        .map_err(|e| e.to_string())?
        .into_iter()
        .find(|p| p.pid == pid)
        .ok_or("selected process is unavailable")?;
    let boot_id = read_remote(adb, serial, "/proc/sys/kernel/random/boot_id", cancel)?;
    let stat_path = format!("/proc/{pid}/stat");
    let stat = read_remote(adb, serial, &stat_path, cancel)?;
    let start_ticks = parse_start_ticks(&stat, pid)?;
    if boot_id.trim().is_empty() || boot_id.trim().len() > 128 {
        return Err("invalid device boot identity".into());
    }
    Ok(TargetObservation {
        serial: serial.into(),
        pid,
        process_name: process.name,
        boot_id: boot_id.trim().to_owned(),
        start_ticks,
    })
}
fn read_remote(
    adb: &AdbClient,
    serial: &str,
    path: &str,
    cancel: &CancellationToken,
) -> Result<String, String> {
    let mut process =
        ProcessRequest::new(&adb.location().executable).args(["-s", serial, "shell", "cat", path]);
    process.timeout = Duration::from_secs(15);
    process.stdout_limit = 64 * 1024;
    process.stderr_limit = 16 * 1024;
    let result = ProcessRunner
        .run(&process, cancel)
        .map_err(|e| e.to_string())?;
    if !result.status.success() {
        return Err("cannot read process-instance identity".into());
    }
    String::from_utf8(result.stdout).map_err(|_| "invalid process-instance encoding".into())
}
/// /proc/PID/stat field 2 may contain spaces or parentheses. Field 22 is the
/// twentieth token after the final ") ", since field 3 is token zero.
fn parse_start_ticks(stat: &str, expected_pid: u32) -> Result<u64, String> {
    let (prefix, suffix) = stat.rsplit_once(") ").ok_or("malformed process stat")?;
    if prefix
        .split_once(" (")
        .and_then(|(pid, _)| pid.parse::<u32>().ok())
        != Some(expected_pid)
    {
        return Err("process stat PID mismatch".into());
    }
    suffix
        .split_whitespace()
        .nth(19)
        .ok_or("missing process start ticks")?
        .parse()
        .map_err(|_| "invalid process start ticks".into())
}
fn create_session_directory(root: &Path, local: &Path) -> Result<(), String> {
    if let Ok(meta) = fs::symlink_metadata(root) {
        if meta.is_symlink() || !meta.is_dir() {
            return Err("capture root is not a real directory".into());
        }
    } else {
        let mut builder = fs::DirBuilder::new();
        builder.recursive(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::DirBuilderExt;
            builder.mode(0o700);
        }
        builder
            .create(root)
            .map_err(|_| "cannot create capture root")?;
    }
    let mut builder = fs::DirBuilder::new();
    #[cfg(unix)]
    {
        use std::os::unix::fs::DirBuilderExt;
        builder.mode(0o700);
    }
    builder
        .create(local)
        .map_err(|_| "capture session directory already exists or is unavailable".into())
}
fn artifact(
    path: PathBuf,
    provenance: &'static str,
    input_digest: Option<SourceDigest>,
) -> Result<CapturedArtifact, String> {
    let meta = fs::symlink_metadata(&path).map_err(|_| "capture artifact is missing")?;
    if !meta.is_file() || meta.is_symlink() || meta.len() == 0 || meta.len() > MAX_ARTIFACT {
        return Err("capture artifact is not a nonempty bounded regular file".into());
    }
    let digest = SourceDigest::hash_file(&path, MAX_ARTIFACT)?;
    Ok(CapturedArtifact {
        path,
        digest,
        size_bytes: meta.len(),
        provenance,
        input_digest,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn call_graph_modes_match_compose_and_default_to_dwarf() {
        assert_eq!(CallGraphMode::default(), CallGraphMode::Dwarf);
        assert_eq!(CallGraphMode::Dwarf.arguments(), &["-g"]);
        assert_eq!(
            CallGraphMode::FramePointer.arguments(),
            &["--call-graph", "fp"]
        );
        assert!(CallGraphMode::None.arguments().is_empty());
    }

    #[test]
    fn protobuf_prefix_rejects_logs_wrong_versions_and_empty_output() {
        assert!(validate_protobuf_prefix(b"SIMPLEPERF\x01\0").is_ok());
        for bytes in [
            b"".as_slice(),
            b"log\nSIMPLEPERF\x01\0",
            b"SIMPLEPERF\x02\0",
            b"SIMPLEPERF",
        ] {
            assert!(validate_protobuf_prefix(bytes).is_err());
        }
    }
    #[test]
    fn confirmation_is_exact_and_plan_expires() {
        // This fixture constructs a plan without observing or mutating a device.
        let location = aps_adb::AdbLocation {
            executable: PathBuf::from("/unused/adb"),
            source: aps_adb::AdbLocationSource::Path,
        };
        let make = || PlannedCapture {
            controller: CaptureController {
                adb: AdbClient::new(location.clone()),
                artifact_root: std::env::temp_dir(),
            },
            request: CpuCaptureRequest {
                run_id: "safe".into(),
                serial: "device".into(),
                pid: 42,
                duration: CaptureDuration::Timed(Duration::from_secs(1)),
                sampling: SamplingConfiguration::default(),
                converter: None,
            },
            observed: TargetObservation {
                serial: "device".into(),
                pid: 42,
                process_name: "app".into(),
                boot_id: "boot".into(),
                start_ticks: 7,
            },
            remote_directory: "/data/local/tmp/aps-rust-safe".into(),
            created: Instant::now(),
            converter_digest: None,
            app_package: None,
        };
        assert!(make().confirm("device", 42, "boot", 7).is_ok());
        assert!(make().confirm("other", 42, "boot", 7).is_err());
        assert!(make().confirm("device", 43, "boot", 7).is_err());
        assert!(make().confirm("device", 42, "old", 7).is_err());
        assert!(make().confirm("device", 42, "boot", 8).is_err());
        let mut expired = make();
        expired.created = Instant::now() - MAX_PLAN_AGE - Duration::from_secs(1);
        assert!(expired.confirm("device", 42, "boot", 7).is_err());
    }
    #[test]
    fn proc_stat_parses_start_ticks_after_parenthesized_name() {
        let fields: Vec<_> = (3..=22).map(|field| field.to_string()).collect();
        let stat = format!("123 (name with ) spaces) {}", fields.join(" "));
        assert_eq!(parse_start_ticks(&stat, 123).unwrap(), 22);
        assert!(parse_start_ticks(&stat, 124).is_err());
        assert!(parse_start_ticks("123 (bad", 123).is_err());
    }
    #[test]
    fn failed_run_cleanup_removes_only_exact_owned_artifacts() {
        let parent = std::env::temp_dir().join(format!(
            "aps-capture-cleanup-{}-{:?}",
            std::process::id(),
            std::thread::current().id()
        ));
        let local = parent.join("owned");
        std::fs::create_dir_all(&local).unwrap();
        std::fs::write(local.join("perf.data"), b"partial").unwrap();
        std::fs::write(local.join("report-sample.pb"), b"partial").unwrap();
        std::fs::write(parent.join("other.data"), b"retain").unwrap();
        {
            let _cleanup = SessionCleanup::new(local.clone());
        }
        assert!(!local.exists());
        assert_eq!(std::fs::read(parent.join("other.data")).unwrap(), b"retain");
        std::fs::remove_dir_all(parent).unwrap();
    }
    #[test]
    fn successful_run_disarms_exact_cleanup() {
        let parent = std::env::temp_dir().join(format!(
            "aps-capture-disarm-{}-{:?}",
            std::process::id(),
            std::thread::current().id()
        ));
        std::fs::create_dir(&parent).unwrap();
        std::fs::write(parent.join("perf.data"), b"valid").unwrap();
        {
            let mut cleanup = SessionCleanup::new(parent.clone());
            cleanup.disarm();
        }
        assert_eq!(std::fs::read(parent.join("perf.data")).unwrap(), b"valid");
        std::fs::remove_dir_all(parent).unwrap();
    }
    #[test]
    fn request_disallows_shell_metacharacters_and_unbounded_values() {
        let mut request = CpuCaptureRequest {
            run_id: "run-1".into(),
            serial: "emulator-5554".into(),
            pid: 12,
            duration: CaptureDuration::Timed(Duration::from_secs(10)),
            sampling: SamplingConfiguration::default(),
            converter: None,
        };
        assert!(validate_request(&request).is_ok());
        request.run_id = "a;rm".into();
        assert!(validate_request(&request).is_err());
        request.run_id = "safe".into();
        request.duration = CaptureDuration::Timed(Duration::ZERO);
        assert!(validate_request(&request).is_err());
        request.duration = CaptureDuration::Timed(Duration::from_secs(10));
        request.sampling.rate = SamplingRate::Frequency(4001);
        assert!(validate_request(&request).is_err());
    }

    #[test]
    fn invalid_sampling_is_rejected_before_device_discovery() {
        let adb = AdbClient::new(aps_adb::AdbLocation {
            executable: std::env::temp_dir().join("aps-never-execute-invalid-sampling-adb"),
            source: aps_adb::AdbLocationSource::Path,
        });
        let controller = CaptureController::new(adb, std::env::temp_dir()).unwrap();
        for sampling in [
            SamplingConfiguration {
                event: "cpu-clock;id".into(),
                ..SamplingConfiguration::default()
            },
            SamplingConfiguration {
                rate: SamplingRate::Period(0),
                ..SamplingConfiguration::default()
            },
        ] {
            let result = controller.plan_cpu(
                CpuCaptureRequest {
                    run_id: "safe".into(),
                    serial: "fixture".into(),
                    pid: 42,
                    duration: CaptureDuration::Timed(Duration::from_secs(1)),
                    sampling,
                    converter: None,
                },
                &CancellationToken::new(),
            );
            let error = match result {
                Ok(_) => panic!("invalid sampling must never prepare a plan"),
                Err(error) => error,
            };
            assert!(
                error.starts_with("CPU event") || error.starts_with("CPU frequency"),
                "{error}"
            );
        }
    }
}

//! Feature-owned `am profile` transport. All operations run off the UI thread.
use aps_adb::{AdbClient, AdbLocator, AndroidApplicationProcess, AndroidProcess, validate_serial};
use aps_platform::{CancellationToken, ProcessRequest, ProcessRunner, create_private_directory};
use std::{
    fs,
    path::{Path, PathBuf},
    sync::atomic::{AtomicBool, AtomicU8, AtomicU64, Ordering},
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

pub(super) const DEFAULT_SECONDS: u32 = 10;
pub(super) const MAX_SECONDS: u32 = 300;
const COMMAND_TIMEOUT: Duration = Duration::from_secs(30);
const POLL: Duration = Duration::from_millis(100);
static NEXT_CAPTURE: AtomicU64 = AtomicU64::new(0);

#[derive(Clone, Debug, PartialEq, Eq)]
pub(super) struct CaptureTarget {
    pub serial: String,
    pub process: AndroidApplicationProcess,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
#[repr(u8)]
pub(super) enum Phase {
    Preparing,
    Recording,
    Stopping,
    Verifying,
    Pulling,
}

#[derive(Default)]
pub(super) struct CaptureControl {
    pub cancel: CancellationToken,
    stop: AtomicBool,
    phase: AtomicU8,
}

impl CaptureControl {
    pub fn request_stop(&self) {
        self.stop.store(true, Ordering::Release);
    }

    pub fn phase(&self) -> Phase {
        match self.phase.load(Ordering::Acquire) {
            1 => Phase::Recording,
            2 => Phase::Stopping,
            3 => Phase::Verifying,
            4 => Phase::Pulling,
            _ => Phase::Preparing,
        }
    }

    fn set_phase(&self, phase: Phase) {
        self.phase.store(phase as u8, Ordering::Release);
    }

    fn check_cancelled(&self) -> Result<(), CaptureError> {
        if self.cancel.is_cancelled() {
            Err(CaptureError::Cancelled)
        } else {
            Ok(())
        }
    }
}

#[derive(Debug)]
pub(super) enum CaptureError {
    Cancelled,
    SdkUnknown,
    UnsupportedApi(u32),
    Diagnostic(String),
}

impl From<String> for CaptureError {
    fn from(message: String) -> Self {
        Self::Diagnostic(message)
    }
}

impl From<&str> for CaptureError {
    fn from(message: &str) -> Self {
        Self::Diagnostic(message.to_owned())
    }
}

pub(super) struct CaptureReport {
    pub result: Result<PathBuf, CaptureError>,
    pub cleanup_warning: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct Identity {
    process: AndroidProcess,
    boot: String,
    start_ticks: u64,
}

struct CommandFailure {
    message: String,
    uncertain: bool,
}

impl From<CommandFailure> for CaptureError {
    fn from(error: CommandFailure) -> Self {
        Self::Diagnostic(error.message)
    }
}

// The narrow boundary keeps command ordering, cancellation and ownership testable
// without teaching the shared read-only ADB client capture mutations.
trait Transport {
    fn online(&self, serial: &str, cancel: &CancellationToken) -> Result<bool, String>;
    fn sdk(&self, serial: &str, cancel: &CancellationToken) -> Result<Option<u32>, String>;
    fn identity(
        &self,
        target: &CaptureTarget,
        cancel: &CancellationToken,
    ) -> Result<Identity, String>;
    fn shell(
        &self,
        serial: &str,
        args: &[&str],
        cancel: &CancellationToken,
    ) -> Result<String, CommandFailure>;
    fn pull(
        &self,
        serial: &str,
        remote: &str,
        local: &Path,
        cancel: &CancellationToken,
    ) -> Result<(), CommandFailure>;
}

struct AdbTransport(AdbClient);

impl AdbTransport {
    fn run(
        &self,
        request: ProcessRequest,
        cancel: &CancellationToken,
    ) -> Result<String, CommandFailure> {
        let mut request = request;
        request.timeout = COMMAND_TIMEOUT;
        request.stdout_limit = 1024 * 1024;
        request.stderr_limit = 256 * 1024;
        let output = ProcessRunner
            .run(&request, cancel)
            .map_err(|error| CommandFailure {
                message: format!("ADB command: {error}"),
                uncertain: true,
            })?;
        if !output.status.success()
            || reports_failure(&output.stdout)
            || reports_failure(&output.stderr)
        {
            return Err(CommandFailure {
                message: format!(
                    "ADB command rejected (exit {:?}); the app must permit shell profiling (debuggable/profileable), or the device must grant sufficient privileges",
                    output.status.code()
                ),
                uncertain: false,
            });
        }
        String::from_utf8(output.stdout).map_err(|_| CommandFailure {
            message: "ADB command returned invalid UTF-8".into(),
            uncertain: true,
        })
    }
}

impl Transport for AdbTransport {
    fn online(&self, serial: &str, cancel: &CancellationToken) -> Result<bool, String> {
        self.0
            .list_devices(cancel)
            .map(|devices| {
                devices
                    .iter()
                    .any(|device| device.serial == serial && device.is_online())
            })
            .map_err(|e| e.to_string())
    }

    fn sdk(&self, serial: &str, cancel: &CancellationToken) -> Result<Option<u32>, String> {
        self.0
            .device_properties(serial, cancel)
            .map(|properties| properties.sdk_level)
            .map_err(|e| e.to_string())
    }

    fn identity(
        &self,
        target: &CaptureTarget,
        cancel: &CancellationToken,
    ) -> Result<Identity, String> {
        let selected = &target.process;
        let process = self
            .0
            .list_processes(&target.serial, cancel)
            .map_err(|e| e.to_string())?
            .into_iter()
            .find(|process| {
                process.pid == selected.pid
                    && process.parent_pid == selected.parent_pid
                    && process.user == selected.user
                    && process.name == selected.name
            })
            .ok_or("Selected process is no longer running with the selected identity")?;
        let stat = self
            .shell(
                &target.serial,
                &["cat", &format!("/proc/{}/stat", process.pid)],
                cancel,
            )
            .map_err(|e| e.message)?;
        let start_ticks = start_ticks(&stat, process.pid)?;
        let boot = self
            .shell(
                &target.serial,
                &["cat", "/proc/sys/kernel/random/boot_id"],
                cancel,
            )
            .map_err(|e| e.message)?
            .trim()
            .to_owned();
        if boot.is_empty()
            || boot.len() > 128
            || !boot.bytes().all(|b| b.is_ascii_hexdigit() || b == b'-')
        {
            return Err("Device boot identity is unavailable".into());
        }
        Ok(Identity {
            process,
            boot,
            start_ticks,
        })
    }

    fn shell(
        &self,
        serial: &str,
        args: &[&str],
        cancel: &CancellationToken,
    ) -> Result<String, CommandFailure> {
        self.run(
            ProcessRequest::new(&self.0.location().executable)
                .args(["-s", serial, "shell"])
                .args(args),
            cancel,
        )
    }

    fn pull(
        &self,
        serial: &str,
        remote: &str,
        local: &Path,
        cancel: &CancellationToken,
    ) -> Result<(), CommandFailure> {
        self.run(
            ProcessRequest::new(&self.0.location().executable)
                .args(["-s", serial, "pull", remote])
                .arg(local),
            cancel,
        )
        .map(|_| ())
    }
}

fn reports_failure(bytes: &[u8]) -> bool {
    String::from_utf8_lossy(bytes).lines().any(|line| {
        let line = line.trim().to_ascii_lowercase();
        line.starts_with("error")
            || line.starts_with("failure")
            || line.contains("exception")
            || line.contains("permission denied")
            || line.contains("permission denial")
            || line.contains("not debuggable")
            || line.contains("not profileable")
    })
}

fn start_ticks(stat: &str, pid: u32) -> Result<u64, String> {
    let (head, tail) = stat
        .trim()
        .rsplit_once(')')
        .ok_or("Invalid process stat identity")?;
    if head
        .split_once(' ')
        .and_then(|(value, _)| value.parse::<u32>().ok())
        != Some(pid)
    {
        return Err("Process stat PID changed".into());
    }
    tail.split_whitespace()
        .nth(19)
        .and_then(|value| value.parse::<u64>().ok())
        .filter(|value| *value > 0)
        .ok_or_else(|| "Process start identity is unavailable".into())
}

pub(super) fn capture(
    target: &CaptureTarget,
    seconds: u32,
    control: &CaptureControl,
) -> CaptureReport {
    let setup = (|| {
        control.check_cancelled()?;
        let location = AdbLocator::new()
            .locate(None)
            .map_err(|e| CaptureError::Diagnostic(e.to_string()))?;
        let root = crate::settings_path()
            .map_err(CaptureError::Diagnostic)?
            .parent()
            .ok_or("Local method capture directory is unavailable")?
            .join("method-recording/sessions");
        Ok((
            AdbTransport(AdbClient::new(location).with_timeout(COMMAND_TIMEOUT)),
            root,
        ))
    })();
    match setup {
        Ok((transport, root)) => capture_with(&transport, &root, target, seconds, control),
        Err(error) => CaptureReport {
            result: Err(error),
            cleanup_warning: None,
        },
    }
}

fn capture_with(
    transport: &impl Transport,
    root: &Path,
    target: &CaptureTarget,
    seconds: u32,
    control: &CaptureControl,
) -> CaptureReport {
    let mut remote = None;
    let mut local = None;
    let mut safe_to_remove = true;
    let result = (|| {
        control.check_cancelled()?;
        validate_serial(&target.serial).map_err(|e| CaptureError::Diagnostic(e.to_string()))?;
        if target.process.pid == 0 || !(1..=MAX_SECONDS).contains(&seconds) {
            return Err(CaptureError::Diagnostic(
                "A positive PID and duration of 1..=300 seconds are required".into(),
            ));
        }
        if !transport.online(&target.serial, &control.cancel)? {
            return Err(CaptureError::Diagnostic(
                "Selected device is not online".into(),
            ));
        }
        match transport.sdk(&target.serial, &control.cancel)? {
            None => return Err(CaptureError::SdkUnknown),
            Some(api) if api < 21 => return Err(CaptureError::UnsupportedApi(api)),
            Some(_) => {}
        }
        let identity = transport.identity(target, &control.cancel)?;
        control.check_cancelled()?;
        create_private_directory(root).map_err(|e| format!("Local capture directory: {e}"))?;
        let id = format!(
            "aps-method-{}-{}-{}",
            std::process::id(),
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap_or_default()
                .as_nanos(),
            NEXT_CAPTURE.fetch_add(1, Ordering::Relaxed)
        );
        let directory = root.join(&id);
        fs::create_dir(&directory)
            .map_err(|e| format!("Cannot reserve local capture directory: {e}"))?;
        let path = directory.join(format!("{id}.trace"));
        local = Some(path.clone());
        let device_directory = format!("/data/local/tmp/{id}");
        // mkdir is exclusive: a collision is never treated as our artifact.
        transport.shell(
            &target.serial,
            &["mkdir", &device_directory],
            &CancellationToken::new(),
        )?;
        let device_path = format!("{device_directory}/{id}.trace");
        remote = Some((device_directory, device_path.clone()));
        control.check_cancelled()?;
        let pid = target.process.pid.to_string();
        let started = transport.shell(
            &target.serial,
            &["am", "profile", "start", &pid, &device_path],
            &CancellationToken::new(),
        );
        match started {
            Err(error) if !error.uncertain => return Err(error.into()),
            started => {
                control.set_phase(Phase::Recording);
                if started.is_ok() {
                    let deadline = Instant::now() + Duration::from_secs(u64::from(seconds));
                    while Instant::now() < deadline
                        && !control.cancel.is_cancelled()
                        && !control.stop.load(Ordering::Acquire)
                    {
                        thread::sleep(POLL.min(deadline.saturating_duration_since(Instant::now())));
                    }
                }
                // Cancellation cannot cancel the mandatory stop/flush command.
                control.set_phase(Phase::Stopping);
                let stop_cancel = CancellationToken::new();
                safe_to_remove = false;
                if transport.identity(target, &stop_cancel)? != identity {
                    return Err(CaptureError::Diagnostic(format!(
                        "Target process/boot identity changed; stop was not sent to a replacement process. Owned trace retained: {device_path}"
                    )));
                }
                transport
                    .shell(
                        &target.serial,
                        &["am", "profile", "stop", &pid],
                        &stop_cancel,
                    )
                    .map_err(|error| {
                        CaptureError::Diagnostic(format!(
                            "Stop/flush unconfirmed: {}. Owned trace retained: {device_path}",
                            error.message
                        ))
                    })?;
                safe_to_remove = true;
                // A timed-out start is never reported as successful evidence.
                started?;
            }
        }
        control.check_cancelled()?;
        control.set_phase(Phase::Verifying);
        let mut size = None;
        for _ in 0..20 {
            control.check_cancelled()?;
            if let Ok(value) = transport.shell(
                &target.serial,
                &["stat", "-c", "%s", &device_path],
                &control.cancel,
            ) && let Ok(bytes) = value.trim().parse::<u64>()
                && bytes > 0
            {
                size = Some(bytes);
                break;
            }
            thread::sleep(Duration::from_millis(250));
        }
        let size = size.ok_or("Method trace was not produced on the device")?;
        if size > aps_method_recording::ImportLimits::default().max_bytes {
            return Err(CaptureError::Diagnostic(
                "Device method trace exceeds the import byte budget".into(),
            ));
        }
        control.check_cancelled()?;
        control.set_phase(Phase::Pulling);
        transport.pull(&target.serial, &device_path, &path, &control.cancel)?;
        control.check_cancelled()?;
        let metadata = fs::symlink_metadata(&path)
            .map_err(|e| format!("Pulled method trace is unavailable: {e}"))?;
        if !metadata.is_file()
            || metadata.len() == 0
            || metadata.len() != size
            || metadata.len() > aps_method_recording::ImportLimits::default().max_bytes
        {
            return Err(CaptureError::Diagnostic("Pulled method trace is empty, non-regular, changed size, or exceeds the import byte budget".into()));
        }
        Ok(path)
    })();
    let cleanup_warning = remote.and_then(|(directory, path)| {
        if !safe_to_remove {
            return Some(path);
        }
        let cancel = CancellationToken::new();
        let removed = transport.shell(&target.serial, &["rm", "-f", &path], &cancel);
        let directory_removed = transport.shell(&target.serial, &["rmdir", &directory], &cancel);
        (removed.is_err() || directory_removed.is_err()).then_some(path)
    });
    if result.is_err()
        && let Some(path) = local
    {
        let _ = fs::remove_file(&path);
        if let Some(directory) = path.parent() {
            let _ = fs::remove_dir(directory);
        }
    }
    CaptureReport {
        result,
        cleanup_warning,
    }
}

#[cfg(test)]
mod tests;

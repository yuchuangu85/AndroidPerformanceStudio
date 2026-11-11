//! Bounded Android Perfetto recordings owned by a unique detached-session key.
//!
//! Blocking API: call from a background executor. A cancelled host ADB process
//! is not proof that the remote recording stopped. Cleanup uses a fresh bounded
//! token, attaches only our key with --stop, then checks --is_detached's exact
//! exit code (0 exists, 2 absent, 1 error). Unknown state retains exact paths.
use aps_platform::{CancellationToken, ProcessError, ProcessOutput, ProcessRequest, ProcessRunner};
use std::{
    fmt,
    fs::{self, Metadata, OpenOptions},
    io::Write,
    path::{Path, PathBuf},
    sync::atomic::{AtomicU64, Ordering},
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

pub const MAX_RECORDING_DURATION: Duration = Duration::from_secs(600);
pub const MAX_RECORDING_BYTES: u64 = 64 * 1024 * 1024;
const COMMAND_TIMEOUT: Duration = Duration::from_secs(10);
const STOP_TIMEOUT: Duration = Duration::from_secs(20);
const TRANSFER_TIMEOUT: Duration = Duration::from_secs(30);
const MAX_CONFIG_BYTES: usize = 256 * 1024;
static NEXT_RECORDING: AtomicU64 = AtomicU64::new(0);

pub struct RecordingRequest<'a> {
    pub adb_executable: &'a Path,
    pub serial: &'a str,
    pub output_root: &'a Path,
    /// Only top-level buffers/data_sources blocks. Safety fields are inserted
    /// by this module and cannot be overridden by the supplied configuration.
    pub config_body: &'a str,
    pub duration: Duration,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RecordingPaths {
    pub session_key: String,
    pub local_directory: PathBuf,
    pub local_config: PathBuf,
    pub local_trace: PathBuf,
    /// Shared trace parent, never an owned directory and never removed.
    pub remote_directory: String,
    pub remote_config: String,
    pub remote_trace: String,
}

#[derive(Debug)]
pub struct RecordingResult {
    pub paths: RecordingPaths,
    pub started_at_unix_ms: u64,
    pub trace_bytes: u64,
    pub warnings: Vec<String>,
    local: OwnedLocal,
}

impl RecordingResult {
    /// Frame's persistent session store calls this only after successful UI
    /// publication. Ephemeral Native/ART datasets retain this owner until close.
    pub fn retain_local_artifacts(&mut self) {
        self.local.preserved = true;
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum RecordingFailureKind {
    InvalidRequest,
    Cancelled,
    Transport,
    StopUnconfirmed,
    InvalidTrace,
}

#[derive(Debug)]
pub struct RecordingFailure {
    pub kind: RecordingFailureKind,
    pub message: String,
    pub stop_confirmed: bool,
    /// Never delete these paths until the owned session's state is reconciled.
    pub preserved_paths: Option<Box<RecordingPaths>>,
}

impl fmt::Display for RecordingFailure {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.message)?;
        if let Some(paths) = &self.preserved_paths {
            write!(
                f,
                " Preserved session key {}; local config {}; local trace {}; remote config {}; remote trace {}; shared remote parent {} (never removed). No global stop or PID kill was attempted.",
                paths.session_key,
                paths.local_config.display(),
                paths.local_trace.display(),
                paths.remote_config,
                paths.remote_trace,
                paths.remote_directory
            )?;
        }
        Ok(())
    }
}
impl std::error::Error for RecordingFailure {}

fn failure(kind: RecordingFailureKind, message: impl Into<String>) -> RecordingFailure {
    RecordingFailure {
        kind,
        message: message.into(),
        stop_confirmed: true,
        preserved_paths: None,
    }
}

#[derive(Debug)]
struct OwnedLocal {
    paths: RecordingPaths,
    identity: Metadata,
    config_owned: bool,
    trace_owned: bool,
    preserved: bool,
}

impl Drop for OwnedLocal {
    fn drop(&mut self) {
        if self.preserved || !same_directory(&self.paths.local_directory, &self.identity) {
            return;
        }
        // No recursive removal: unexpected/user-created siblings survive.
        if self.config_owned {
            let _ = fs::remove_file(&self.paths.local_config);
        }
        if self.trace_owned {
            let _ = fs::remove_file(&self.paths.local_trace);
        }
        let _ = fs::remove_dir(&self.paths.local_directory);
    }
}

fn same_directory(path: &Path, original: &Metadata) -> bool {
    let Ok(current) = fs::symlink_metadata(path) else {
        return false;
    };
    if current.is_symlink() || !current.is_dir() {
        return false;
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        current.dev() == original.dev() && current.ino() == original.ino()
    }
    #[cfg(not(unix))]
    {
        current
            .created()
            .ok()
            .zip(original.created().ok())
            .is_some_and(|(a, b)| a == b)
    }
}

trait RecordingIo {
    fn run(
        &self,
        request: &ProcessRequest,
        cancel: &CancellationToken,
    ) -> Result<ProcessOutput, ProcessError>;
}
struct AdbIo;
impl RecordingIo for AdbIo {
    fn run(
        &self,
        request: &ProcessRequest,
        cancel: &CancellationToken,
    ) -> Result<ProcessOutput, ProcessError> {
        ProcessRunner.run(request, cancel)
    }
}

pub fn record_bounded(
    request: &RecordingRequest<'_>,
    cancel: &CancellationToken,
) -> Result<RecordingResult, RecordingFailure> {
    record_bounded_checked(request, cancel, &mut || Ok(()))
}

/// Optional process/boot/install identity guard for target-bound Native/ART
/// recordings. The guard must itself use bounded/cancellable observations.
pub fn record_bounded_checked(
    request: &RecordingRequest<'_>,
    cancel: &CancellationToken,
    validate_target: &mut dyn FnMut() -> Result<(), String>,
) -> Result<RecordingResult, RecordingFailure> {
    record_with(&AdbIo, request, cancel, validate_target)
}

fn record_with(
    io: &dyn RecordingIo,
    request: &RecordingRequest<'_>,
    cancel: &CancellationToken,
    validate_target: &mut dyn FnMut() -> Result<(), String>,
) -> Result<RecordingResult, RecordingFailure> {
    validate_request(request)?;
    check_cancel(cancel)?;
    validate_target().map_err(identity_failure)?;
    let config = format!(
        "duration_ms: {}\nwrite_into_file: true\nmax_file_size_bytes: {MAX_RECORDING_BYTES}\n{}\n",
        request.duration.as_millis(),
        request.config_body
    );
    let mut local = allocate_local(request.output_root)?;
    write_private(&local.paths.local_config, config.as_bytes())?;
    local.config_owned = true;
    // Reserve the local trace before ADB pull; it is never an existing user file.
    write_private(&local.paths.local_trace, &[])?;
    local.trace_owned = true;
    let paths = local.paths.clone();
    let mut remote_config_identity = None;
    let mut remote_trace_identity = None;
    let mut remote_uncertain = false;
    let mut start_attempted = false;
    let mut start_acknowledged = false;
    let recorded = (|| {
        if detached_state(io, request, &paths.session_key, cancel)? != DetachedState::Absent {
            return Err(failure(
                RecordingFailureKind::InvalidRequest,
                "Generated recording key is already in use; no existing session was stopped.",
            ));
        }
        let reserve = reserve_remote_config(
            io,
            request,
            &paths.remote_config,
            config.as_bytes(),
            cancel,
            &mut remote_uncertain,
        )?;
        remote_config_identity = reserve.0;
        require_success(
            reserve.1,
            "Cannot reserve/write the unique remote recording config.",
        )?;
        check_cancel(cancel)?;
        validate_target().map_err(identity_failure)?;
        // The timestamp describes the recording start attempt, not file
        // reservation or config transfer, which may take significantly longer.
        let started_at_unix_ms = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_err(|_| {
                failure(
                    RecordingFailureKind::InvalidRequest,
                    "Host clock predates the Unix epoch.",
                )
            })?
            .as_millis()
            .min(u64::MAX as u128) as u64;
        start_attempted = true;
        remote_uncertain = true;
        // Only Perfetto's SELinux domain may create files in the trace parent
        // on some production devices. Its --no-clobber uses O_CREAT | O_EXCL.
        // Pipe the config: that domain cannot reopen shell's /data/local/tmp.
        let command = format!(
            "{} && {} && {} || {{ printf 'aps-refused\\n'; exit 1; }}; printf 'aps-start\\n'; {} | {} || exit $?; printf 'aps-started\\n'; {} && {} && {}",
            owned_remote_test(
                &paths.remote_config,
                remote_config_identity.as_deref().unwrap()
            ),
            quoted(&["test", "!", "-e", &paths.remote_trace]),
            quoted(&["test", "!", "-L", &paths.remote_trace]),
            quoted(&["cat", &paths.remote_config]),
            quoted(&[
                "perfetto",
                "--txt",
                "-c",
                "-",
                "--out",
                &paths.remote_trace,
                "--no-clobber",
                &format!("--detach={}", paths.session_key),
            ]),
            quoted(&["test", "-f", &paths.remote_trace]),
            quoted(&["test", "!", "-L", &paths.remote_trace]),
            quoted(&["stat", "-c", "%d:%i", &paths.remote_trace]),
        );
        let start = shell_script(io, request, &command, COMMAND_TIMEOUT, cancel)?;
        // Only an explicit preflight refusal proves no start was attempted.
        // Empty/garbled stdout can also be a lost transport acknowledgement.
        start_acknowledged = start
            .stdout
            .split(|b| *b == b'\n')
            .any(|line| line == b"aps-started");
        // A completed preflight refusal has not touched the output or key.
        if start.stdout == b"aps-refused\n" && !start.status.success() {
            start_attempted = false;
            remote_uncertain = false;
        } else if start_acknowledged && start.status.success() {
            remote_trace_identity = reservation_identity(&start.stdout, b"aps-started");
            remote_uncertain = remote_trace_identity.is_none();
        }
        require_success(
            start,
            "Perfetto did not acknowledge the owned detached recording.",
        )?;
        if remote_uncertain || !start_acknowledged {
            return Err(failure(
                RecordingFailureKind::Transport,
                "Cannot verify the exclusively created remote trace identity.",
            ));
        }
        let deadline = Instant::now() + request.duration;
        let mut last_identity_check = Instant::now();
        while Instant::now() < deadline {
            check_cancel(cancel)?;
            if last_identity_check.elapsed() >= Duration::from_millis(250) {
                validate_target().map_err(identity_failure)?;
                last_identity_check = Instant::now();
            }
            thread::sleep(
                deadline
                    .saturating_duration_since(Instant::now())
                    .min(Duration::from_millis(25)),
            );
        }
        check_cancel(cancel)?;
        Ok(started_at_unix_ms)
    })();
    // The caller's token can already be cancelled. Never use it for the one
    // bounded attempt to stop/reconcile this exact detached session.
    let cleanup_cancel = CancellationToken::new();
    let stopped = !start_attempted
        || confirm_stopped(
            io,
            request,
            &paths.session_key,
            start_acknowledged,
            &cleanup_cancel,
        );
    if !stopped || remote_uncertain {
        local.preserved = true;
        let message = if !stopped {
            "Could not confirm the owned Perfetto session stopped; recording paths were retained."
        } else {
            "Remote file reservation outcome is unknown; exact paths were retained without deleting unproven files."
        };
        return Err(RecordingFailure {
            kind: RecordingFailureKind::StopUnconfirmed,
            message: message.into(),
            stop_confirmed: stopped,
            preserved_paths: Some(Box::new(paths)),
        });
    }
    let mut result = recorded.and_then(|started_at_unix_ms| {
        check_cancel(cancel)?;
        validate_target().map_err(identity_failure)?;
        let stat_script = format!(
            "{} && {}",
            owned_remote_test(
                &paths.remote_trace,
                remote_trace_identity.as_deref().unwrap()
            ),
            quoted(&["stat", "-c", "%s", &paths.remote_trace])
        );
        let stat = shell_script(io, request, &stat_script, COMMAND_TIMEOUT, cancel)?;
        require_success_code(
            &stat,
            "Stopped recording did not produce a regular remote trace.",
        )?;
        let bytes = std::str::from_utf8(&stat.stdout)
            .ok()
            .and_then(|s| s.trim().parse::<u64>().ok())
            .filter(|bytes| *bytes > 0 && *bytes <= MAX_RECORDING_BYTES)
            .ok_or_else(|| {
                failure(
                    RecordingFailureKind::InvalidTrace,
                    "Stopped recording is empty or exceeds its 64 MiB trace limit.",
                )
            })?;
        transfer(
            io,
            request,
            "pull",
            &paths.local_trace,
            &paths.remote_trace,
            cancel,
        )?;
        check_cancel(cancel)?;
        let metadata = fs::symlink_metadata(&paths.local_trace).map_err(|_| {
            failure(
                RecordingFailureKind::InvalidTrace,
                "Cannot inspect the pulled recording.",
            )
        })?;
        if metadata.is_symlink() || !metadata.is_file() || metadata.len() != bytes {
            return Err(failure(
                RecordingFailureKind::InvalidTrace,
                "Pulled recording is not the confirmed stopped trace.",
            ));
        }
        Ok((started_at_unix_ms, bytes))
    });
    let mut failed_cleanup = Vec::new();
    for (path, identity) in [
        (&paths.remote_config, remote_config_identity.as_deref()),
        (&paths.remote_trace, remote_trace_identity.as_deref()),
    ] {
        if let Some(identity) = identity
            && !cleanup_remote(io, request, path, identity, &cleanup_cancel)
        {
            failed_cleanup.push(path.as_str());
        }
    }
    if !failed_cleanup.is_empty() {
        local.preserved = true;
        return Err(RecordingFailure {
            kind: RecordingFailureKind::Transport,
            message: format!(
                "Owned recording stopped, but cleanup could not be confirmed for: {}. Missing ownership or changed file identity never authorizes deletion; paths were retained.",
                failed_cleanup.join(", ")
            ),
            stop_confirmed: true,
            preserved_paths: Some(Box::new(paths)),
        });
    }
    // A cancel arriving during cleanup still prevents publication.
    if cancel.is_cancelled() {
        result = check_cancel(cancel).map(|()| (0, 0));
    }
    result.map(|(started_at_unix_ms, trace_bytes)| RecordingResult {
        paths,
        started_at_unix_ms,
        trace_bytes,
        warnings: Vec::new(),
        local,
    })
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum DetachedState {
    Present,
    Absent,
}

fn detached_state(
    io: &dyn RecordingIo,
    request: &RecordingRequest<'_>,
    key: &str,
    cancel: &CancellationToken,
) -> Result<DetachedState, RecordingFailure> {
    let result = shell(
        io,
        request,
        &["perfetto", &format!("--is_detached={key}")],
        COMMAND_TIMEOUT,
        cancel,
    )?;
    match result.status.code() {
        Some(0) => Ok(DetachedState::Present),
        Some(2) => Ok(DetachedState::Absent),
        _ => Err(failure(
            RecordingFailureKind::Transport,
            "Perfetto detached-session probe failed; a nonzero error is not evidence that tracing stopped.",
        )),
    }
}

fn confirm_stopped(
    io: &dyn RecordingIo,
    request: &RecordingRequest<'_>,
    key: &str,
    start_acknowledged: bool,
    cancel: &CancellationToken,
) -> bool {
    let stop = shell(
        io,
        request,
        &["perfetto", &format!("--attach={key}"), "--stop"],
        STOP_TIMEOUT,
        cancel,
    );
    let stop_completed = stop.as_ref().ok().and_then(|output| output.status.code());
    // An unacknowledged start can still detach later. Also, after a timed-out
    // attach, is_detached=2 can mean an attached consumer is still flushing.
    // Only an acknowledged completed stop, or a known completed start followed
    // by explicit attach-not-found, can qualify the subsequent absent result.
    let absent = matches!(
        detached_state(io, request, key, cancel),
        Ok(DetachedState::Absent)
    );
    (stop_completed == Some(0) || start_acknowledged && stop_completed == Some(2)) && absent
}

fn cleanup_remote(
    io: &dyn RecordingIo,
    request: &RecordingRequest<'_>,
    path: &str,
    identity: &str,
    cancel: &CancellationToken,
) -> bool {
    let absent = format!(
        "{} && {}",
        quoted(&["test", "!", "-e", path]),
        quoted(&["test", "!", "-L", path]),
    );
    let script = format!(
        "if {absent}; then exit 0; fi; {} && {} && {absent}",
        owned_remote_test(path, identity),
        quoted(&["rm", "-f", path]),
    );
    shell_script(io, request, &script, COMMAND_TIMEOUT, cancel)
        .is_ok_and(|output| output.status.success())
}

fn owned_remote_test(path: &str, identity: &str) -> String {
    format!(
        "{} && {} && test \"$({})\" = {}",
        quoted(&["test", "-f", path]),
        quoted(&["test", "!", "-L", path]),
        quoted(&["stat", "-c", "%d:%i", path]),
        quoted(&[identity]),
    )
}

/// Reserve the config in shell-writable storage, without overwriting any
/// existing file/symlink. The receipt identifies the exclusively opened file.
fn reserve_remote_config(
    io: &dyn RecordingIo,
    request: &RecordingRequest<'_>,
    path: &str,
    input: &[u8],
    cancel: &CancellationToken,
    uncertain: &mut bool,
) -> Result<(Option<String>, ProcessOutput), RecordingFailure> {
    let script = format!(
        "{} && {} || {{ printf 'aps-refused\\n'; exit 1; }}; umask 077; set -C; if {{ printf 'aps-reserved\\n'; {} || exit 1; cat >&3; }} 3>{}; then exit 0; else code=$?; printf 'aps-refused\\n'; exit \"$code\"; fi",
        quoted(&["test", "!", "-e", path]),
        quoted(&["test", "!", "-L", path]),
        quoted(&["stat", "-L", "-c", "%d:%i", "/proc/self/fd/3"]),
        quoted(&[path]),
    );
    let mut process =
        ProcessRequest::new(request.adb_executable).args(["-s", request.serial, "shell", &script]);
    process.timeout = COMMAND_TIMEOUT;
    process.stdout_limit = 64 * 1024;
    process.stderr_limit = 64 * 1024;
    process.stdin = Some(input.to_vec());
    *uncertain = true;
    let output = io.run(&process, cancel).map_err(process_failure)?;
    // A bare ADB failure is not proof of a failed exclusive redirection.
    if output.stdout == b"aps-refused\n" && !output.status.success() {
        *uncertain = false;
        return Ok((None, output));
    }
    let identity = reservation_identity(&output.stdout, b"aps-reserved");
    if identity.is_none() {
        return Err(failure(
            RecordingFailureKind::Transport,
            format!("Remote reservation receipt is unverifiable for {path}."),
        ));
    }
    *uncertain = false;
    Ok((identity, output))
}

fn reservation_identity(stdout: &[u8], marker: &[u8]) -> Option<String> {
    let mut lines = stdout.split(|b| *b == b'\n');
    lines.find(|line| *line == marker)?;
    let identity = std::str::from_utf8(lines.next()?).ok()?;
    identity
        .split_once(':')
        .filter(|(dev, ino)| {
            !dev.is_empty()
                && !ino.is_empty()
                && dev.bytes().chain(ino.bytes()).all(|b| b.is_ascii_digit())
        })
        .map(|_| identity.to_owned())
}

fn check_cancel(cancel: &CancellationToken) -> Result<(), RecordingFailure> {
    if cancel.is_cancelled() {
        Err(failure(
            RecordingFailureKind::Cancelled,
            "Owned Perfetto recording cancelled; the previous report is retained.",
        ))
    } else {
        Ok(())
    }
}

fn require_success(output: ProcessOutput, message: &str) -> Result<(), RecordingFailure> {
    require_success_code(&output, message)
}
fn require_success_code(output: &ProcessOutput, message: &str) -> Result<(), RecordingFailure> {
    if output.status.success() {
        Ok(())
    } else {
        Err(failure(RecordingFailureKind::Transport, message))
    }
}

fn quoted(tokens: &[&str]) -> String {
    tokens
        .iter()
        .map(|token| format!("'{}'", token.replace('\'', "'\\''")))
        .collect::<Vec<_>>()
        .join(" ")
}
fn shell(
    io: &dyn RecordingIo,
    request: &RecordingRequest<'_>,
    tokens: &[&str],
    timeout: Duration,
    cancel: &CancellationToken,
) -> Result<ProcessOutput, RecordingFailure> {
    shell_script(io, request, &quoted(tokens), timeout, cancel)
}
fn shell_script(
    io: &dyn RecordingIo,
    request: &RecordingRequest<'_>,
    script: &str,
    timeout: Duration,
    cancel: &CancellationToken,
) -> Result<ProcessOutput, RecordingFailure> {
    let mut process =
        ProcessRequest::new(request.adb_executable).args(["-s", request.serial, "shell", script]);
    process.timeout = timeout;
    process.stdout_limit = 64 * 1024;
    process.stderr_limit = 64 * 1024;
    io.run(&process, cancel).map_err(process_failure)
}
fn transfer(
    io: &dyn RecordingIo,
    request: &RecordingRequest<'_>,
    mode: &str,
    local: &Path,
    remote: &str,
    cancel: &CancellationToken,
) -> Result<(), RecordingFailure> {
    let mut process =
        ProcessRequest::new(request.adb_executable).args(["-s", request.serial, mode]);
    process = if mode == "push" {
        process.arg(local).arg(remote)
    } else {
        process.arg(remote).arg(local)
    };
    process.timeout = TRANSFER_TIMEOUT;
    process.stdout_limit = 64 * 1024;
    process.stderr_limit = 64 * 1024;
    require_success(
        io.run(&process, cancel).map_err(process_failure)?,
        "ADB could not transfer the exact owned recording path.",
    )
}
fn process_failure(error: ProcessError) -> RecordingFailure {
    let kind = if matches!(error, ProcessError::Cancelled) {
        RecordingFailureKind::Cancelled
    } else {
        RecordingFailureKind::Transport
    };
    failure(kind, format!("Owned recording command failed: {error}."))
}

fn identity_failure(message: String) -> RecordingFailure {
    failure(
        RecordingFailureKind::Transport,
        format!("Recording target identity changed or became unverifiable: {message}"),
    )
}

fn allocate_local(root: &Path) -> Result<OwnedLocal, RecordingFailure> {
    aps_platform::create_private_directory(root).map_err(|_| {
        failure(
            RecordingFailureKind::InvalidRequest,
            "Cannot create private recording storage.",
        )
    })?;
    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| {
            failure(
                RecordingFailureKind::InvalidRequest,
                "Host clock predates the Unix epoch.",
            )
        })?
        .as_nanos();
    for _ in 0..32 {
        let key = format!(
            "aps-{:x}-{stamp:x}-{:x}",
            std::process::id(),
            NEXT_RECORDING.fetch_add(1, Ordering::Relaxed)
        );
        let directory = root.join(&key);
        let mut builder = fs::DirBuilder::new();
        #[cfg(unix)]
        {
            use std::os::unix::fs::DirBuilderExt;
            builder.mode(0o700);
        }
        match builder.create(&directory) {
            Ok(()) => {}
            Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => continue,
            Err(_) => {
                return Err(failure(
                    RecordingFailureKind::InvalidRequest,
                    "Cannot reserve a fresh private recording directory.",
                ));
            }
        }
        let identity = fs::symlink_metadata(&directory).map_err(|_| {
            failure(
                RecordingFailureKind::InvalidRequest,
                "Cannot verify owned recording storage.",
            )
        })?;
        let remote_directory = "/data/misc/perfetto-traces".to_owned();
        return Ok(OwnedLocal {
            paths: RecordingPaths {
                local_config: directory.join("config.pbtxt"),
                local_trace: directory.join("capture.pftrace"),
                local_directory: directory,
                remote_config: format!("/data/local/tmp/aps-owned-{key}.pbtxt"),
                remote_trace: format!("{remote_directory}/aps-owned-{key}.pftrace"),
                remote_directory,
                session_key: key,
            },
            identity,
            config_owned: false,
            trace_owned: false,
            preserved: false,
        });
    }
    Err(failure(
        RecordingFailureKind::InvalidRequest,
        "Private recording directory allocation exhausted.",
    ))
}

fn write_private(path: &Path, bytes: &[u8]) -> Result<(), RecordingFailure> {
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut file = options.open(path).map_err(|_| {
        failure(
            RecordingFailureKind::InvalidRequest,
            "Cannot reserve an owned recording file.",
        )
    })?;
    if file
        .write_all(bytes)
        .and_then(|()| file.sync_all())
        .is_err()
    {
        drop(file);
        let _ = fs::remove_file(path);
        return Err(failure(
            RecordingFailureKind::InvalidRequest,
            "Cannot write owned recording configuration.",
        ));
    }
    Ok(())
}

fn validate_request(request: &RecordingRequest<'_>) -> Result<(), RecordingFailure> {
    if !request.adb_executable.is_absolute()
        || !request.output_root.is_absolute()
        || request.serial.is_empty()
        || request.serial.len() > 1024
        || request.serial.starts_with('-')
        || !request.serial.bytes().all(|b| {
            b.is_ascii_alphanumeric() || matches!(b, b'_' | b'.' | b':' | b'[' | b']' | b'-')
        })
        || request.duration < Duration::from_millis(1)
        || request.duration > MAX_RECORDING_DURATION
        || request.duration.as_millis() > u32::MAX as u128
    {
        return Err(failure(
            RecordingFailureKind::InvalidRequest,
            "Recording requires explicit ADB/device/private storage and a bounded positive duration.",
        ));
    }
    validate_body(request.config_body)
}

/// A small structural guard, not a replacement protobuf parser. By allowing
/// only root buffers/data_sources message blocks, no supplied scalar, trigger,
/// output_path, indefinite duration or file-size override reaches Perfetto.
fn validate_body(body: &str) -> Result<(), RecordingFailure> {
    let invalid = || {
        failure(
            RecordingFailureKind::InvalidRequest,
            "Recording body must contain only bounded buffers/data_sources blocks; duration, output and file limits are helper-owned.",
        )
    };
    if body.is_empty() || body.len() > MAX_CONFIG_BYTES {
        return Err(invalid());
    }
    let bytes = body.as_bytes();
    let mut index = 0;
    let mut depth = 0u32;
    let mut blocks = 0usize;
    while index < bytes.len() {
        let byte = bytes[index];
        if byte.is_ascii_whitespace() {
            index += 1;
            continue;
        }
        if byte == b'#' || byte == b'/' && bytes.get(index + 1) == Some(&b'/') {
            while index < bytes.len() && bytes[index] != b'\n' {
                index += 1;
            }
            continue;
        }
        if depth == 0 {
            let start = index;
            while index < bytes.len()
                && (bytes[index].is_ascii_alphanumeric() || bytes[index] == b'_')
            {
                index += 1;
            }
            if !matches!(&body[start..index], "buffers" | "data_sources") {
                return Err(invalid());
            }
            while index < bytes.len() && bytes[index].is_ascii_whitespace() {
                index += 1;
            }
            if bytes.get(index) == Some(&b':') {
                index += 1;
            }
            while index < bytes.len() && bytes[index].is_ascii_whitespace() {
                index += 1;
            }
            if bytes.get(index) != Some(&b'{') {
                return Err(invalid());
            }
            depth = 1;
            blocks += 1;
            index += 1;
            continue;
        }
        match byte {
            b'"' | b'\'' => {
                let quote = byte;
                index += 1;
                let mut closed = false;
                while index < bytes.len() {
                    if bytes[index] == b'\\' {
                        index += 2;
                        continue;
                    }
                    if bytes[index] == quote {
                        index += 1;
                        closed = true;
                        break;
                    }
                    index += 1;
                }
                if !closed {
                    return Err(invalid());
                }
            }
            b'{' => {
                depth += 1;
                if depth > 64 {
                    return Err(invalid());
                }
                index += 1;
            }
            b'}' => {
                depth -= 1;
                index += 1;
            }
            b'<' | b'>' | 0 => return Err(invalid()),
            _ if byte.is_ascii_control() => return Err(invalid()),
            _ => index += 1,
        }
    }
    if depth != 0 || blocks == 0 {
        Err(invalid())
    } else {
        Ok(())
    }
}

/// FakeIo never launches a subprocess or ADB.
#[cfg(all(test, unix))]
mod tests {
    use super::*;
    use std::{collections::BTreeMap, os::unix::process::ExitStatusExt, sync::Mutex};

    const CONFIG_ID: &str = "42:100";
    const TRACE_ID: &str = "42:101";

    struct FakeIo {
        calls: Mutex<Vec<Vec<String>>>,
        probes: AtomicU64,
        first_probe: i32,
        final_probe: i32,
        stop_error: bool,
        stop_code: i32,
        start_error: bool,
        cancel_after_start: bool,
        files: Mutex<BTreeMap<String, (String, Vec<u8>)>>,
        collision: Option<&'static str>,
        config_timeout: bool,
        config_write_error: bool,
        config_lost_output: bool,
        invalid_receipt: bool,
        invalid_trace_receipt: bool,
        start_code: i32,
        start_lost_output: bool,
        cleanup_error: Option<&'static str>,
        replace_trace: bool,
        stat_bytes: &'static [u8],
    }
    impl Default for FakeIo {
        fn default() -> Self {
            Self {
                calls: Mutex::new(Vec::new()),
                probes: AtomicU64::new(0),
                first_probe: 2,
                final_probe: 2,
                stop_error: false,
                stop_code: 0,
                start_error: false,
                cancel_after_start: false,
                files: Mutex::new(BTreeMap::new()),
                collision: None,
                config_timeout: false,
                config_write_error: false,
                config_lost_output: false,
                invalid_receipt: false,
                invalid_trace_receipt: false,
                start_code: 0,
                start_lost_output: false,
                cleanup_error: None,
                replace_trace: false,
                stat_bytes: b"4\n",
            }
        }
    }
    fn output(code: i32, text: &[u8]) -> ProcessOutput {
        ProcessOutput {
            status: std::process::ExitStatus::from_raw(code << 8),
            stdout: text.to_vec(),
            stderr: Vec::new(),
        }
    }
    impl RecordingIo for FakeIo {
        fn run(
            &self,
            request: &ProcessRequest,
            cancel: &CancellationToken,
        ) -> Result<ProcessOutput, ProcessError> {
            if cancel.is_cancelled() {
                return Err(ProcessError::Cancelled);
            }
            let arguments: Vec<_> = request
                .arguments
                .iter()
                .map(|arg| arg.to_string_lossy().into_owned())
                .collect();
            self.calls.lock().unwrap().push(arguments.clone());
            if arguments[2] == "pull" {
                let files = self.files.lock().unwrap();
                fs::write(&arguments[4], &files[&arguments[3]].1).unwrap();
            }
            if arguments[2] == "shell" {
                assert_eq!(
                    arguments.len(),
                    4,
                    "one quoted remote script preserves its actual exit status"
                );
                let script = &arguments[3];
                assert!(!script.contains("'mkdir'") && !script.contains("'rmdir'"));
                if script.contains("--is_detached=") {
                    let code = if self.probes.fetch_add(1, Ordering::Relaxed) == 0 {
                        self.first_probe
                    } else {
                        self.final_probe
                    };
                    return Ok(output(code, b""));
                }
                if script.contains("set -C") {
                    assert!(script.contains("umask 077;"));
                    let path = script
                        .rsplit_once("3>'")
                        .unwrap()
                        .1
                        .split('\'')
                        .next()
                        .unwrap();
                    assert!(path.starts_with("/data/local/tmp/") && path.ends_with(".pbtxt"));
                    let mut files = self.files.lock().unwrap();
                    if self.collision.is_some_and(|suffix| path.ends_with(suffix)) {
                        files.insert(path.into(), ("99:999".into(), b"unowned".to_vec()));
                    }
                    if files.contains_key(path) {
                        return Ok(output(1, b"aps-refused\n"));
                    }
                    files.insert(
                        path.into(),
                        (CONFIG_ID.into(), request.stdin.as_ref().unwrap().clone()),
                    );
                    if self.config_timeout {
                        return Err(ProcessError::Timeout);
                    }
                    if self.config_lost_output {
                        return Ok(output(1, b""));
                    }
                    if self.invalid_receipt {
                        return Ok(output(0, b"aps-reserved\ninvalid\n"));
                    }
                    return Ok(output(
                        i32::from(self.config_write_error),
                        format!("aps-reserved\n{CONFIG_ID}\n").as_bytes(),
                    ));
                }
                if script.contains("--detach=") {
                    assert!(script.contains("'--no-clobber'"));
                    assert!(script.contains("| 'perfetto' '--txt' '-c' '-'"));
                    assert!(script.contains("'cat' '/data/local/tmp/"));
                    let path = script
                        .split("'--out' '")
                        .nth(1)
                        .unwrap()
                        .split('\'')
                        .next()
                        .unwrap();
                    assert!(
                        path.starts_with("/data/misc/perfetto-traces/")
                            && path.ends_with(".pftrace")
                    );
                    let mut files = self.files.lock().unwrap();
                    if self.collision == Some(".pftrace") {
                        files.insert(path.into(), ("99:999".into(), b"unowned".to_vec()));
                        return Ok(output(1, b"aps-refused\n"));
                    }
                    files.insert(path.into(), (TRACE_ID.into(), b"data".to_vec()));
                    if self.start_error {
                        return Err(ProcessError::Timeout);
                    }
                    if self.start_lost_output {
                        return Ok(output(1, b""));
                    }
                    if self.cancel_after_start {
                        cancel.cancel();
                    }
                    if self.start_code != 0 {
                        return Ok(output(self.start_code, b"aps-start\n"));
                    }
                    if self.invalid_trace_receipt {
                        return Ok(output(0, b"aps-start\naps-started\ninvalid\n"));
                    }
                    return Ok(output(
                        0,
                        format!("aps-start\naps-started\n{TRACE_ID}\n").as_bytes(),
                    ));
                }
                if script.contains("--attach=") {
                    assert!(script.contains("'--stop'"));
                    if self.replace_trace {
                        for (path, file) in self.files.lock().unwrap().iter_mut() {
                            if path.ends_with(".pftrace") {
                                *file = ("99:999".into(), b"unowned".to_vec());
                            }
                        }
                    }
                    if self.stop_error {
                        return Err(ProcessError::Timeout);
                    }
                    return Ok(output(self.stop_code, b""));
                }
                if script.contains("'stat'") {
                    let mut files = self.files.lock().unwrap();
                    let path = files
                        .keys()
                        .find(|path| script.contains(path.as_str()))
                        .unwrap()
                        .clone();
                    if script.contains("'rm'") {
                        if self
                            .cleanup_error
                            .is_some_and(|suffix| path.ends_with(suffix))
                            || !script.contains(&quoted(&[&files[&path].0]))
                        {
                            return Ok(output(1, b""));
                        }
                        files.remove(&path);
                        return Ok(output(0, b""));
                    }
                    if !script.contains(&quoted(&[&files[&path].0])) {
                        return Ok(output(1, b""));
                    }
                    return Ok(output(0, self.stat_bytes));
                }
            }
            Ok(output(0, b""))
        }
    }
    fn root() -> PathBuf {
        std::env::temp_dir().join(format!(
            "aps-record-test-{}-{}-{}",
            std::process::id(),
            SystemTime::now()
                .duration_since(UNIX_EPOCH)
                .unwrap()
                .as_nanos(),
            NEXT_RECORDING.fetch_add(1, Ordering::Relaxed)
        ))
    }
    fn request(root: &Path) -> RecordingRequest<'_> {
        RecordingRequest {
            adb_executable: Path::new("/fake/adb"),
            serial: "device-one",
            output_root: root,
            config_body: "buffers { size_kb: 8192 } data_sources { config { name: \"android.heapprofd\" } }",
            duration: Duration::from_millis(1),
        }
    }
    fn record(
        io: &FakeIo,
        root: &Path,
        cancel: &CancellationToken,
    ) -> Result<RecordingResult, RecordingFailure> {
        record_with(io, &request(root), cancel, &mut || Ok(()))
    }

    #[test]
    fn success_is_bounded_key_owned_and_drop_preserves_unexpected_siblings() {
        let root = root();
        let io = FakeIo::default();
        let result = record(&io, &root, &CancellationToken::new()).unwrap();
        let paths = result.paths.clone();
        let config = fs::read_to_string(&paths.local_config).unwrap();
        assert!(config.contains("duration_ms: 1\n"));
        assert!(config.contains("write_into_file: true\n"));
        assert!(config.contains("max_file_size_bytes: 67108864\n"));
        assert_eq!(result.trace_bytes, 4);
        let sibling = paths.local_directory.join("user-note.txt");
        fs::write(&sibling, b"keep").unwrap();
        let calls = io.calls.lock().unwrap();
        assert!(calls.iter().any(|args| {
            args.last()
                .unwrap()
                .contains(&format!("--attach={}", paths.session_key))
        }));
        assert_eq!(paths.remote_directory, "/data/misc/perfetto-traces");
        assert!(
            paths
                .remote_trace
                .starts_with("/data/misc/perfetto-traces/aps-owned-aps-")
        );
        assert!(
            paths
                .remote_config
                .starts_with("/data/local/tmp/aps-owned-aps-")
        );
        assert!(calls.iter().all(|args| args[2] != "push"));
        assert!(io.files.lock().unwrap().is_empty());
        assert!(
            calls
                .iter()
                .all(|args| !args.last().unwrap().contains("--background")
                    && !args.last().unwrap().contains("kill"))
        );
        drop(calls);
        drop(result);
        assert!(!paths.local_config.exists());
        assert!(!paths.local_trace.exists());
        assert_eq!(fs::read(sibling).unwrap(), b"keep");
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn persistent_frame_artifacts_require_explicit_retention() {
        let root = root();
        let mut result = record(&FakeIo::default(), &root, &CancellationToken::new()).unwrap();
        let trace = result.paths.local_trace.clone();
        result.retain_local_artifacts();
        drop(result);
        assert_eq!(fs::read(trace).unwrap(), b"data");
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn cancellation_stops_only_its_key_with_a_fresh_cleanup_token() {
        let root = root();
        let io = FakeIo {
            cancel_after_start: true,
            ..Default::default()
        };
        let error = record(&io, &root, &CancellationToken::new()).unwrap_err();
        assert_eq!(error.kind, RecordingFailureKind::Cancelled);
        assert!(error.stop_confirmed);
        assert!(error.preserved_paths.is_none());
        let calls = io.calls.lock().unwrap();
        assert!(
            calls
                .iter()
                .any(|args| args.last().unwrap().contains("--attach="))
        );
        assert!(calls.iter().all(|args| args[2] != "pull"));
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn probe_error_and_timed_out_attach_never_qualify_as_confirmed_stop() {
        for io in [
            FakeIo {
                final_probe: 1,
                ..Default::default()
            },
            FakeIo {
                stop_error: true,
                final_probe: 2,
                ..Default::default()
            },
            FakeIo {
                start_error: true,
                stop_code: 2,
                final_probe: 2,
                ..Default::default()
            },
        ] {
            let root = root();
            let error = record(&io, &root, &CancellationToken::new()).unwrap_err();
            assert_eq!(error.kind, RecordingFailureKind::StopUnconfirmed);
            assert!(!error.stop_confirmed);
            let paths = error.preserved_paths.as_ref().unwrap();
            assert!(paths.local_config.exists());
            assert!(error.to_string().contains(&paths.remote_trace));
            let calls = io.calls.lock().unwrap();
            assert!(
                calls
                    .iter()
                    .all(|args| args[2] != "pull" && !args.last().unwrap().contains("'rm'"))
            );
            assert_eq!(io.probes.load(Ordering::Relaxed), 2);
            fs::remove_dir_all(root).unwrap();
        }
    }

    #[test]
    fn key_collision_does_not_stop_someone_elses_session() {
        let root = root();
        let io = FakeIo {
            first_probe: 0,
            ..Default::default()
        };
        let error = record(&io, &root, &CancellationToken::new()).unwrap_err();
        assert_eq!(error.kind, RecordingFailureKind::InvalidRequest);
        assert!(
            io.calls
                .lock()
                .unwrap()
                .iter()
                .all(|args| !args.last().unwrap().contains("--attach="))
        );
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn flat_file_collisions_are_never_overwritten_removed_or_started() {
        for suffix in [".pbtxt", ".pftrace"] {
            let root = root();
            let io = FakeIo {
                collision: Some(suffix),
                ..Default::default()
            };
            let error = record(&io, &root, &CancellationToken::new()).unwrap_err();
            assert_eq!(error.kind, RecordingFailureKind::Transport);
            assert!(error.stop_confirmed);
            assert!(error.preserved_paths.is_none());
            let files = io.files.lock().unwrap();
            assert_eq!(files.len(), 1);
            let (path, (_, contents)) = files.first_key_value().unwrap();
            assert!(path.ends_with(suffix));
            assert_eq!(contents, b"unowned");
            assert!(io.calls.lock().unwrap().iter().all(|args| {
                !args[3].contains("--attach=")
                    && !(args[3].contains("'rm'") && args[3].contains(path))
            }));
            fs::remove_dir_all(root).unwrap();
        }
    }

    #[test]
    fn uncertain_config_reservations_retain_exact_paths_without_deletion_or_stop() {
        for io in [
            FakeIo {
                config_timeout: true,
                ..Default::default()
            },
            FakeIo {
                invalid_receipt: true,
                ..Default::default()
            },
        ] {
            let root = root();
            let error = record(&io, &root, &CancellationToken::new()).unwrap_err();
            assert_eq!(error.kind, RecordingFailureKind::StopUnconfirmed);
            assert!(error.stop_confirmed, "no start was attempted");
            let paths = error.preserved_paths.as_ref().unwrap();
            assert!(paths.local_config.exists());
            assert!(error.to_string().contains(&paths.remote_config));
            assert!(
                io.calls
                    .lock()
                    .unwrap()
                    .iter()
                    .all(|args| { !args[3].contains("--attach=") && !args[3].contains("'rm'") })
            );
            fs::remove_dir_all(root).unwrap();
        }
    }

    #[test]
    fn failed_config_write_cleans_only_the_confirmed_reservation() {
        let root = root();
        let io = FakeIo {
            config_write_error: true,
            ..Default::default()
        };
        let error = record(&io, &root, &CancellationToken::new()).unwrap_err();
        assert_eq!(error.kind, RecordingFailureKind::Transport);
        assert!(error.preserved_paths.is_none());
        assert!(io.files.lock().unwrap().is_empty());
        assert!(
            io.calls
                .lock()
                .unwrap()
                .iter()
                .all(|args| !args[3].contains("--attach="))
        );
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn unacknowledged_start_or_trace_identity_retains_potential_output() {
        for io in [
            FakeIo {
                start_code: 1,
                ..Default::default()
            },
            FakeIo {
                invalid_trace_receipt: true,
                ..Default::default()
            },
        ] {
            let root = root();
            let error = record(&io, &root, &CancellationToken::new()).unwrap_err();
            assert_eq!(error.kind, RecordingFailureKind::StopUnconfirmed);
            assert!(error.stop_confirmed);
            let paths = error.preserved_paths.as_ref().unwrap();
            assert!(paths.local_config.exists());
            assert!(error.to_string().contains(&paths.remote_trace));
            assert!(
                io.calls
                    .lock()
                    .unwrap()
                    .iter()
                    .all(|args| !args[3].contains("'rm'"))
            );
            assert!(io.files.lock().unwrap().contains_key(&paths.remote_trace));
            fs::remove_dir_all(root).unwrap();
        }
    }

    #[test]
    fn silent_adb_failures_never_prove_reservation_refusal_or_session_absence() {
        for io in [
            FakeIo {
                config_lost_output: true,
                ..Default::default()
            },
            FakeIo {
                start_lost_output: true,
                ..Default::default()
            },
        ] {
            let root = root();
            let error = record(&io, &root, &CancellationToken::new()).unwrap_err();
            assert_eq!(error.kind, RecordingFailureKind::StopUnconfirmed);
            let paths = error.preserved_paths.as_ref().unwrap();
            assert!(paths.local_config.exists());
            assert!(error.to_string().contains(&paths.remote_config));
            let calls = io.calls.lock().unwrap();
            assert!(calls.iter().all(|args| !args[3].contains("'rm'")));
            assert_eq!(
                calls.iter().any(|args| args[3].contains("--attach=")),
                io.start_lost_output
            );
            fs::remove_dir_all(root).unwrap();
        }
    }

    #[test]
    fn cleanup_failure_names_each_failed_path_and_still_cleans_other_owned_files() {
        for suffix in [".pbtxt", ".pftrace"] {
            let root = root();
            let io = FakeIo {
                cleanup_error: Some(suffix),
                ..Default::default()
            };
            let error = record(&io, &root, &CancellationToken::new()).unwrap_err();
            assert_eq!(error.kind, RecordingFailureKind::Transport);
            assert!(error.stop_confirmed);
            let paths = error.preserved_paths.as_ref().unwrap();
            assert!(paths.local_trace.exists());
            let files = io.files.lock().unwrap();
            assert_eq!(files.len(), 1);
            assert!(
                files
                    .keys()
                    .all(|path| path.ends_with(suffix) && error.message.contains(path))
            );
            fs::remove_dir_all(root).unwrap();
        }
    }

    #[test]
    fn replaced_remote_trace_is_not_pulled_or_deleted() {
        let root = root();
        let io = FakeIo {
            replace_trace: true,
            ..Default::default()
        };
        let error = record(&io, &root, &CancellationToken::new()).unwrap_err();
        assert!(error.stop_confirmed);
        let paths = error.preserved_paths.as_ref().unwrap();
        assert!(error.message.contains(&paths.remote_trace));
        assert_eq!(io.files.lock().unwrap()[&paths.remote_trace].1, b"unowned");
        assert!(
            io.calls
                .lock()
                .unwrap()
                .iter()
                .all(|args| args[2] != "pull")
        );
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn empty_or_oversized_trace_is_not_pulled_and_owned_files_are_cleaned() {
        for bytes in [b"0\n".as_slice(), b"67108865\n", b"invalid\n"] {
            let root = root();
            let io = FakeIo {
                stat_bytes: bytes,
                ..Default::default()
            };
            let error = record(&io, &root, &CancellationToken::new()).unwrap_err();
            assert_eq!(error.kind, RecordingFailureKind::InvalidTrace);
            assert!(error.stop_confirmed);
            assert!(error.preserved_paths.is_none());
            assert!(io.files.lock().unwrap().is_empty());
            assert!(
                io.calls
                    .lock()
                    .unwrap()
                    .iter()
                    .all(|args| args[2] != "pull")
            );
            fs::remove_dir_all(root).unwrap();
        }
    }

    #[test]
    fn recordings_use_distinct_flat_paths_and_detached_keys() {
        let root = root();
        let io = FakeIo::default();
        let first = record(&io, &root, &CancellationToken::new()).unwrap();
        let second = record(&io, &root, &CancellationToken::new()).unwrap();
        assert_ne!(first.paths.session_key, second.paths.session_key);
        assert_ne!(first.paths.remote_config, second.paths.remote_config);
        assert_ne!(first.paths.remote_trace, second.paths.remote_trace);
        drop((first, second));
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn identity_guard_before_start_never_starts_a_session() {
        let root = root();
        let io = FakeIo::default();
        let mut checks = 0;
        let error = record_with(&io, &request(&root), &CancellationToken::new(), &mut || {
            checks += 1;
            if checks == 2 {
                Err("boot changed".into())
            } else {
                Ok(())
            }
        })
        .unwrap_err();
        assert_eq!(error.kind, RecordingFailureKind::Transport);
        assert!(error.preserved_paths.is_none());
        assert!(io.files.lock().unwrap().is_empty());
        assert!(
            io.calls
                .lock()
                .unwrap()
                .iter()
                .all(|args| !args[3].contains("--detach=") && !args[3].contains("--attach="))
        );
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn identity_guard_during_recording_stops_only_the_owned_session() {
        let root = root();
        let io = FakeIo::default();
        let mut req = request(&root);
        req.duration = Duration::from_millis(300);
        let mut checks = 0;
        let error = record_with(&io, &req, &CancellationToken::new(), &mut || {
            checks += 1;
            if checks == 3 {
                Err("process start ticks changed".into())
            } else {
                Ok(())
            }
        })
        .unwrap_err();
        assert_eq!(checks, 3);
        assert_eq!(error.kind, RecordingFailureKind::Transport);
        assert!(error.stop_confirmed);
        assert!(error.preserved_paths.is_none());
        assert!(io.files.lock().unwrap().is_empty());
        let calls = io.calls.lock().unwrap();
        assert!(calls.iter().any(|args| args[3].contains("--attach=")));
        assert!(calls.iter().all(|args| args[2] != "pull"));
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn identity_guard_failure_aborts_publication_and_runs_owned_cleanup() {
        let root = root();
        let io = FakeIo::default();
        let mut checks = 0;
        let error = record_with(&io, &request(&root), &CancellationToken::new(), &mut || {
            checks += 1;
            if checks >= 3 {
                Err("process start ticks changed".into())
            } else {
                Ok(())
            }
        })
        .unwrap_err();
        assert_eq!(error.kind, RecordingFailureKind::Transport);
        assert!(error.stop_confirmed);
        assert!(error.preserved_paths.is_none());
        assert!(
            io.calls
                .lock()
                .unwrap()
                .iter()
                .any(|args| args.last().unwrap().contains("--attach="))
        );
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn supplied_config_cannot_override_duration_output_or_size_safety_fields() {
        for body in [
            "duration_ms: 0",
            "buffers { size_kb: 8 } write_into_file: false",
            "data_sources {} output_path: \"/other/file\"",
            "buffers {} max_file_size_bytes: 999999999",
            "buffers {} trigger_config { trigger_mode: START_TRACING }",
            "buffers {",
            "buffers < size_kb: 8 >",
            "buffers {} /* } */ duration_ms: 0",
        ] {
            assert!(validate_body(body).is_err(), "{body}");
        }
        assert!(validate_body("# comment\nbuffers: {size_kb: 8}\ndata_sources { config {name:\"brace}and\\\"quote\"} }").is_ok());
        assert_eq!(quoted(&["perfetto", "a'b"]), "'perfetto' 'a'\\''b'");
    }
}

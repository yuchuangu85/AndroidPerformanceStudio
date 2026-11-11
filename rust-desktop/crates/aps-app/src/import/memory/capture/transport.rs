//! Blocking, user-initiated raw Android HPROF capture. Run off the UI thread.
//!
//! Only generated device paths and app-created local files are owned here. No
//! root, process restart, install, conversion, or user export is performed.
//! `adb pull` does not expose a streaming byte limit: metadata checks and a
//! polling watchdog reject oversized/changing files, but cannot impose a hard
//! disk-write ceiling. Full HPROF parsing belongs to the isolated memory worker.

use aps_adb::{AdbClient, AdbDevice, AdbLocator, TargetSnapshot, validate_serial};
use aps_platform::{
    CancellationToken, ProcessError, ProcessOutput, ProcessRequest, ProcessRunner, TempWorkspace,
};
use std::{
    fs::{self, File, Metadata, OpenOptions},
    io::{Read, Seek, SeekFrom},
    path::{Path, PathBuf},
    sync::{
        atomic::{AtomicU64, Ordering},
        mpsc,
    },
    thread,
    time::{Duration, SystemTime, UNIX_EPOCH},
};

const COMMAND_TIMEOUT: Duration = Duration::from_secs(15);
const DUMP_TIMEOUT: Duration = Duration::from_secs(120);
const PULL_TIMEOUT: Duration = Duration::from_secs(300);
const CLEANUP_TIMEOUT: Duration = Duration::from_secs(5);
const WATCH_INTERVAL: Duration = Duration::from_millis(25);
const OUTPUT_LIMIT: usize = 16 * 1024;
const DIAGNOSTIC_LIMIT: usize = 512;
const HPROF_HEADER_BYTES: usize = 31;
const BOOT_ID_PATH: &str = "/proc/sys/kernel/random/boot_id";
static NEXT_CAPTURE: AtomicU64 = AtomicU64::new(0);

#[derive(Clone, Debug, PartialEq, Eq)]
pub(in crate::import::memory) struct HeapCaptureTarget {
    pub serial: String,
    pub pid: u32,
    pub process_name: String,
    pub package_name: String,
}

/// Retain this owner (including in raw-export tasks) as long as `path` is used.
/// Dropping the last owner removes its private, capture-only scratch directory.
pub(in crate::import::memory) struct CapturedHeap {
    pub path: PathBuf,
    _workspace: TempWorkspace,
}

pub(in crate::import::memory) fn capture_heap(
    target: &HeapCaptureTarget,
    root: &Path,
    cancel: &CancellationToken,
) -> Result<CapturedHeap, String> {
    validate_target(target, root)?;
    check_cancelled(cancel)?;
    let location = AdbLocator::new()
        .locate(None)
        .map_err(|error| error.to_string())?;
    let executable = location.executable.clone();
    let executor = AndroidExecutor {
        client: AdbClient::new(location).with_timeout(COMMAND_TIMEOUT),
    };
    capture_with(&executor, &executable, target, root, cancel)
}

// Discovery uses the shared client's package/process semantics; the command
// boundary is injectable so regression tests never need ADB or a device.
trait HeapExecutor {
    fn devices(&self, cancel: &CancellationToken) -> Result<Vec<AdbDevice>, String>;
    fn target_snapshot(
        &self,
        serial: &str,
        cancel: &CancellationToken,
    ) -> Result<TargetSnapshot, String>;
    fn run(
        &self,
        request: &ProcessRequest,
        cancel: &CancellationToken,
    ) -> Result<ProcessOutput, ProcessError>;
}

struct AndroidExecutor {
    client: AdbClient,
}

impl HeapExecutor for AndroidExecutor {
    fn devices(&self, cancel: &CancellationToken) -> Result<Vec<AdbDevice>, String> {
        self.client.list_devices(cancel).map_err(|error| {
            format!(
                "Cannot inspect connected devices: {error}. Check USB debugging and authorization."
            )
        })
    }

    fn target_snapshot(
        &self,
        serial: &str,
        cancel: &CancellationToken,
    ) -> Result<TargetSnapshot, String> {
        self.client.target_snapshot(serial, cancel).map_err(|error| {
            format!("Cannot verify the selected application process: {error}. Refresh the target selector.")
        })
    }

    fn run(
        &self,
        request: &ProcessRequest,
        cancel: &CancellationToken,
    ) -> Result<ProcessOutput, ProcessError> {
        ProcessRunner.run(request, cancel)
    }
}

fn capture_with(
    executor: &impl HeapExecutor,
    executable: &Path,
    target: &HeapCaptureTarget,
    root: &Path,
    cancel: &CancellationToken,
) -> Result<CapturedHeap, String> {
    validate_target(target, root)?;
    check_cancelled(cancel)?;
    let devices = executor.devices(cancel)?;
    let device = devices
        .iter()
        .find(|device| device.serial == target.serial)
        .ok_or("Selected device is disconnected. Reconnect it and refresh the target selector.")?;
    if !device.is_online() {
        return Err("Selected device is not online. Check USB debugging authorization and refresh the target selector.".into());
    }

    // Bracket descriptor discovery with process-instance observations. A PID
    // alone, or a matching package/process name, is not a durable identity.
    let frozen = read_identity(executor, executable, target, cancel)?;
    let snapshot = executor.target_snapshot(&target.serial, cancel)?;
    verify_descriptor(target, &snapshot)?;
    check_cancelled(cancel)?;
    let workspace = TempWorkspace::create(root).map_err(|error| {
        format!(
            "Cannot create the heap capture workspace ({:?}).",
            error.kind()
        )
    })?;
    let path = workspace.path().join("capture.raw.hprof");
    let remote_path = generated_remote_path()?;

    // Never overwrite an already present path, including a dangling symlink.
    // This script contains only our generated hexadecimal path, no target text.
    let absent = request(executable, &target.serial, COMMAND_TIMEOUT)
        .arg("shell")
        .arg(format!("[ ! -e {remote_path} ] && [ ! -L {remote_path} ]"));
    run_checked(
        executor,
        &absent,
        cancel,
        "Reserve a unique device heap path",
    )?;
    verify_identity(executor, executable, target, &frozen, cancel)?;

    // Register cleanup before issuing the first device mutation: a timeout or
    // cancellation can occur after AM has opened/started writing the file.
    let mut remote = RemoteHeap {
        executor,
        executable,
        serial: &target.serial,
        path: remote_path,
        active: true,
    };
    let result = (|| {
        let dump = dump_request(executable, target, &remote.path);
        run_checked(executor, &dump, cancel, "Dump the selected process heap")?;
        verify_identity(executor, executable, target, &frozen, cancel)?;
        let before = remote_metadata(executor, executable, target, &remote.path, cancel)?;
        verify_identity(executor, executable, target, &frozen, cancel)?;
        // `adb pull` may replace the destination inode instead of writing
        // through the placeholder. Reserve the path for the transfer, but do
        // not retain a handle to the placeholder across the pull.
        drop(create_local_file(&path)?);
        let pull = request(executable, &target.serial, PULL_TIMEOUT)
            .args(["pull", &remote.path])
            .arg(&path);
        pull_with_watchdog(executor, &pull, &path, before.size, cancel)?;
        verify_identity(executor, executable, target, &frozen, cancel)?;
        let after = remote_metadata(executor, executable, target, &remote.path, cancel)?;
        if before != after {
            return Err("Device heap file changed during transfer. Refresh the process target and capture again.".into());
        }
        validate_local_heap(&path, before.size, cancel)?;
        verify_identity(executor, executable, target, &frozen, cancel)?;
        check_cancelled(cancel)
    })();
    let cleanup = remote.cleanup();
    match (result, cleanup) {
        (Err(error), Err(cleanup)) => return Err(format!("{error} {cleanup}")),
        (Err(error), Ok(())) | (Ok(()), Err(error)) => return Err(error),
        (Ok(()), Ok(())) => {}
    }
    check_cancelled(cancel)?;
    Ok(CapturedHeap {
        path,
        _workspace: workspace,
    })
}

fn validate_target(target: &HeapCaptureTarget, root: &Path) -> Result<(), String> {
    validate_serial(&target.serial).map_err(|error| error.to_string())?;
    if target.pid == 0 || target.pid > i32::MAX as u32 {
        return Err("Select a valid running application PID before dumping its heap.".into());
    }
    let package = &target.package_name;
    if package.is_empty()
        || package.len() > 255
        || !package.split('.').all(|part| {
            part.as_bytes()
                .first()
                .is_some_and(|byte| byte.is_ascii_alphabetic() || *byte == b'_')
                && part
                    .bytes()
                    .all(|byte| byte.is_ascii_alphanumeric() || byte == b'_')
        })
    {
        return Err("Selected package name is invalid. Refresh the target selector.".into());
    }
    let process = &target.process_name;
    if process.len() > 255
        || !(process == package
            || process.strip_prefix(package).is_some_and(|suffix| {
                suffix.starts_with(':')
                    && suffix.len() > 1
                    && suffix.bytes().all(|byte| {
                        byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'.' | b':' | b'-')
                    })
            }))
    {
        return Err("Selected process name is invalid. Refresh the target selector.".into());
    }
    if !root.is_absolute() {
        return Err("Heap capture requires an absolute application scratch directory.".into());
    }
    Ok(())
}

fn verify_descriptor(target: &HeapCaptureTarget, snapshot: &TargetSnapshot) -> Result<(), String> {
    let matches = snapshot
        .application_processes()
        .into_iter()
        .filter(|process| {
            process.pid == target.pid
                && process.name == target.process_name
                && process.package_name == target.package_name
        })
        .count();
    if matches != 1 {
        return Err("Selected application process has exited or changed. Refresh the target selector and select it again.".into());
    }
    Ok(())
}

fn check_cancelled(cancel: &CancellationToken) -> Result<(), String> {
    if cancel.is_cancelled() {
        Err("Heap capture cancelled.".into())
    } else {
        Ok(())
    }
}

fn request(executable: &Path, serial: &str, timeout: Duration) -> ProcessRequest {
    let mut request = ProcessRequest::new(executable).args(["-s", serial]);
    request.timeout = timeout;
    request.stdout_limit = OUTPUT_LIMIT;
    request.stderr_limit = OUTPUT_LIMIT;
    request
}

fn dump_request(executable: &Path, target: &HeapCaptureTarget, remote: &str) -> ProcessRequest {
    request(executable, &target.serial, DUMP_TIMEOUT)
        .args(["shell", "am", "dumpheap"])
        .arg(target.pid.to_string())
        .arg(remote)
}

fn generated_remote_path() -> Result<String, String> {
    let timestamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| "Host clock is unavailable for allocating a unique heap capture path.")?
        .as_nanos();
    let sequence = NEXT_CAPTURE.fetch_add(1, Ordering::Relaxed);
    Ok(format!(
        "/data/local/tmp/aps-heap-{timestamp:032x}-{:x}-{sequence:016x}.hprof",
        std::process::id()
    ))
}

#[derive(Debug, PartialEq, Eq)]
struct ProcessIdentity {
    boot_id: String,
    start_ticks: u64,
}

fn read_identity(
    executor: &impl HeapExecutor,
    executable: &Path,
    target: &HeapCaptureTarget,
    cancel: &CancellationToken,
) -> Result<ProcessIdentity, String> {
    let stat = format!("/proc/{}/stat", target.pid);
    let command = request(executable, &target.serial, COMMAND_TIMEOUT).args([
        "shell",
        "cat",
        BOOT_ID_PATH,
        &stat,
        BOOT_ID_PATH,
    ]);
    let output = run_checked(executor, &command, cancel, "Read the selected process identity")
        .map_err(|error| format!("{error} Capture requires readable boot and process identity; select an accessible application."))?;
    let text = std::str::from_utf8(&output.stdout)
        .map_err(|_| "Cannot verify the process identity: invalid device response.")?;
    parse_identity(text, target.pid)
}

fn parse_identity(text: &str, pid: u32) -> Result<ProcessIdentity, String> {
    let invalid = || {
        "Cannot verify boot ID and process start time. The process may have exited or identity access is restricted; refresh the target selector.".to_owned()
    };
    let mut lines = text.lines();
    let boot = lines.next().ok_or_else(invalid)?.trim();
    let stat = lines.next().ok_or_else(invalid)?;
    let end_boot = lines.next().ok_or_else(invalid)?.trim();
    if lines.next().is_some() || !valid_boot_id(boot) || !boot.eq_ignore_ascii_case(end_boot) {
        return Err(invalid());
    }
    Ok(ProcessIdentity {
        boot_id: boot.to_ascii_lowercase(),
        start_ticks: parse_start_ticks(stat, pid).ok_or_else(invalid)?,
    })
}

fn valid_boot_id(boot: &str) -> bool {
    boot.len() == 36
        && boot.bytes().enumerate().all(|(index, byte)| {
            if matches!(index, 8 | 13 | 18 | 23) {
                byte == b'-'
            } else {
                byte.is_ascii_hexdigit()
            }
        })
        && boot
            .bytes()
            .any(|byte| byte.is_ascii_hexdigit() && byte != b'0')
}

fn parse_start_ticks(stat: &str, expected_pid: u32) -> Option<u64> {
    let (pid, rest) = stat.split_once(' ')?;
    if pid.parse::<u32>().ok()? != expected_pid || !rest.starts_with('(') {
        return None;
    }
    // comm (field 2) may contain spaces or ')'; split at its closing delimiter,
    // not at a whitespace index. starttime is field 22, index 19 after comm.
    let (_, fields) = rest.rsplit_once(") ")?;
    let mut fields = fields.split_ascii_whitespace();
    let state = fields.next()?;
    if state.len() != 1 || !b"RSDTtWKPI".contains(&state.as_bytes()[0]) {
        return None;
    }
    let ticks = fields.nth(18)?;
    if ticks.is_empty() || !ticks.bytes().all(|byte| byte.is_ascii_digit()) {
        return None;
    }
    ticks.parse().ok().filter(|ticks| *ticks > 0)
}

fn verify_identity(
    executor: &impl HeapExecutor,
    executable: &Path,
    target: &HeapCaptureTarget,
    frozen: &ProcessIdentity,
    cancel: &CancellationToken,
) -> Result<(), String> {
    if read_identity(executor, executable, target, cancel)? != *frozen {
        return Err("Device rebooted or the selected process restarted/PID was reused during heap capture. Refresh the target selector and capture again.".into());
    }
    Ok(())
}

#[derive(Debug, PartialEq, Eq)]
struct RemoteMetadata {
    mode: u32,
    size: u64,
    modified: i64,
    changed: i64,
    inode: u64,
}

fn remote_metadata(
    executor: &impl HeapExecutor,
    executable: &Path,
    target: &HeapCaptureTarget,
    remote: &str,
    cancel: &CancellationToken,
) -> Result<RemoteMetadata, String> {
    // stat without -L inspects the link itself, so symlinks cannot pass the mode
    // check. Quotes survive ADB's remote-shell argument joining for the format.
    let command = request(executable, &target.serial, COMMAND_TIMEOUT).args([
        "shell",
        "stat",
        "-c",
        "'%f %s %Y %Z %i'",
        remote,
    ]);
    let output = run_checked(executor, &command, cancel, "Inspect the device heap file")?;
    let text = std::str::from_utf8(&output.stdout)
        .map_err(|_| "Cannot verify the device heap file metadata.")?;
    parse_remote_metadata(text)
}

fn parse_remote_metadata(text: &str) -> Result<RemoteMetadata, String> {
    let invalid = || {
        "Device heap must be a completed regular non-symlink HPROF within the memory input size limit.".to_owned()
    };
    let mut fields = text.split_ascii_whitespace();
    let metadata = RemoteMetadata {
        mode: u32::from_str_radix(fields.next().ok_or_else(invalid)?, 16).map_err(|_| invalid())?,
        size: fields
            .next()
            .ok_or_else(invalid)?
            .parse()
            .map_err(|_| invalid())?,
        modified: fields
            .next()
            .ok_or_else(invalid)?
            .parse()
            .map_err(|_| invalid())?,
        changed: fields
            .next()
            .ok_or_else(invalid)?
            .parse()
            .map_err(|_| invalid())?,
        inode: fields
            .next()
            .ok_or_else(invalid)?
            .parse()
            .map_err(|_| invalid())?,
    };
    if fields.next().is_some()
        || metadata.mode & 0o170000 != 0o100000
        || metadata.size < HPROF_HEADER_BYTES as u64
        || metadata.size > aps_memory::MAX_INPUT_BYTES
        || metadata.inode == 0
    {
        return Err(invalid());
    }
    Ok(metadata)
}

fn create_local_file(path: &Path) -> Result<File, String> {
    let mut options = OpenOptions::new();
    options.create_new(true).read(true).write(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    options.open(path).map_err(|error| {
        format!("Cannot create the local heap capture file ({:?}). Check scratch-directory permissions and free disk space.", error.kind())
    })
}

fn inspect_local_file(path: &Path, limit: u64) -> Result<Metadata, String> {
    let metadata = fs::symlink_metadata(path)
        .map_err(|_| "Local heap capture file is unavailable. Check disk space and scratch-directory permissions.")?;
    if metadata.is_symlink() || !metadata.is_file() || metadata.len() > limit {
        return Err("Local heap capture is not a regular non-symlink file within the expected size limit; transfer stopped.".into());
    }
    Ok(metadata)
}

fn pull_with_watchdog(
    executor: &impl HeapExecutor,
    command: &ProcessRequest,
    path: &Path,
    expected: u64,
    cancel: &CancellationToken,
) -> Result<(), String> {
    check_cancelled(cancel)?;
    let transfer_cancel = CancellationToken::new();
    thread::scope(|scope| {
        // Keep the sender inside the scope closure so unwinding also wakes the
        // watcher before the scope waits for its thread to finish.
        let (done, finished) = mpsc::channel();
        let child_cancel = transfer_cancel.clone();
        let watcher = thread::Builder::new()
            .name("aps-heap-size-watch".into())
            .spawn_scoped(scope, move || {
                loop {
                    if cancel.is_cancelled() {
                        child_cancel.cancel();
                        return Err("Heap capture cancelled.".to_owned());
                    }
                    if let Err(error) =
                        inspect_local_file(path, expected.min(aps_memory::MAX_INPUT_BYTES))
                    {
                        child_cancel.cancel();
                        return Err(error);
                    }
                    match finished.recv_timeout(WATCH_INTERVAL) {
                        Ok(()) | Err(mpsc::RecvTimeoutError::Disconnected) => return Ok(()),
                        Err(mpsc::RecvTimeoutError::Timeout) => {}
                    }
                }
            })
            .map_err(|_| "Cannot start the bounded heap-transfer size watchdog.")?;
        let output = executor.run(command, &transfer_cancel);
        let _ = done.send(());
        let watched = watcher
            .join()
            .map_err(|_| "Heap-transfer size watchdog failed.")?;
        check_cancelled(cancel)?;
        watched?;
        checked_output(output, "Pull the device heap file", false)?;
        Ok(())
    })
}

fn validate_local_heap(
    path: &Path,
    expected: u64,
    cancel: &CancellationToken,
) -> Result<(), String> {
    check_cancelled(cancel)?;
    if !(HPROF_HEADER_BYTES as u64..=aps_memory::MAX_INPUT_BYTES).contains(&expected) {
        return Err("Heap capture file size is outside the memory input limit.".into());
    }
    let named = inspect_local_file(path, expected)?;
    let mut file = OpenOptions::new()
        .read(true)
        .open(path)
        .map_err(|_| "Cannot open the captured HPROF file.")?;
    let opened = file
        .metadata()
        .map_err(|_| "Cannot inspect the captured HPROF file.")?;
    if named.len() != expected || !same_local_file(&named, &opened) {
        return Err("Heap transfer is incomplete or its local file changed. Capture again.".into());
    }
    let mut header = [0; HPROF_HEADER_BYTES];
    file.seek(SeekFrom::Start(0))
        .and_then(|_| file.read_exact(&mut header))
        .map_err(|_| "Captured heap has an incomplete HPROF header.")?;
    validate_hprof_header(&header)?;
    let after = inspect_local_file(path, expected)?;
    let opened_after = file
        .metadata()
        .map_err(|_| "Cannot recheck the captured HPROF file.")?;
    if after.len() != expected
        || !same_local_file(&named, &after)
        || !same_local_file(&after, &opened_after)
    {
        return Err("Local heap file changed during validation. Capture again.".into());
    }
    check_cancelled(cancel)
}

fn same_local_file(left: &Metadata, right: &Metadata) -> bool {
    if !left.is_file()
        || !right.is_file()
        || left.len() != right.len()
        || left.modified().ok() != right.modified().ok()
    {
        return false;
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        left.dev() == right.dev()
            && left.ino() == right.ino()
            && left.nlink() == 1
            && right.nlink() == 1
    }
    #[cfg(not(unix))]
    {
        left.created().ok() == right.created().ok()
    }
}

fn validate_hprof_header(header: &[u8]) -> Result<(), String> {
    if header.len() != HPROF_HEADER_BYTES
        || !matches!(
            &header[..19],
            b"JAVA PROFILE 1.0.1\0" | b"JAVA PROFILE 1.0.2\0" | b"JAVA PROFILE 1.0.3\0"
        )
        || !matches!(
            u32::from_be_bytes(header[19..23].try_into().expect("fixed header slice")),
            4 | 8
        )
    {
        return Err("Captured file is not a supported Java/Android HPROF (expected a complete header and 4- or 8-byte identifiers).".into());
    }
    Ok(())
}

struct RemoteHeap<'a, E: HeapExecutor> {
    executor: &'a E,
    executable: &'a Path,
    serial: &'a str,
    path: String,
    active: bool,
}

impl<E: HeapExecutor> RemoteHeap<'_, E> {
    fn cleanup(&mut self) -> Result<(), String> {
        if !self.active {
            return Ok(());
        }
        self.active = false;
        let command = request(self.executable, self.serial, CLEANUP_TIMEOUT)
            .args(["shell", "rm", "-f", "--", &self.path]);
        run_checked(
            self.executor,
            &command,
            &CancellationToken::new(),
            "Remove the owned device heap file",
        )
        .map(|_| ())
        .map_err(|error| format!("{error} Device cleanup could not be confirmed; reconnect the device and remove only {} if it remains.", self.path))
    }
}

impl<E: HeapExecutor> Drop for RemoteHeap<'_, E> {
    fn drop(&mut self) {
        // Normal paths report cleanup errors; this fallback also covers unwinds.
        let _ = self.cleanup();
    }
}

fn run_checked(
    executor: &impl HeapExecutor,
    command: &ProcessRequest,
    cancel: &CancellationToken,
    operation: &str,
) -> Result<ProcessOutput, String> {
    check_cancelled(cancel)?;
    let output = executor.run(command, cancel);
    check_cancelled(cancel)?;
    let inspect_stdout = command.arguments.get(3).is_some_and(|arg| arg == "am")
        && command
            .arguments
            .get(4)
            .is_some_and(|arg| arg == "dumpheap");
    checked_output(output, operation, inspect_stdout)
}

fn checked_output(
    output: Result<ProcessOutput, ProcessError>,
    operation: &str,
    inspect_stdout: bool,
) -> Result<ProcessOutput, String> {
    let output = output.map_err(|error| format!("{operation}: {error}."))?;
    if !output.status.success()
        || (inspect_stdout && reports_command_error(&output.stdout))
        || reports_command_error(&output.stderr)
    {
        let stderr = sanitized_diagnostic(&output.stderr);
        let stdout = sanitized_diagnostic(&output.stdout);
        let details = match (stderr.is_empty(), stdout.is_empty()) {
            (false, false) => format!(" {stderr}; {stdout}"),
            (false, true) => format!(" {stderr}"),
            (true, false) => format!(" {stdout}"),
            (true, true) => String::new(),
        };
        return Err(format!(
            "{operation} failed (exit {:?}).{details} Check application debug/heap-dump permissions, process liveness, device authorization and available storage.",
            output.status.code()
        ));
    }
    Ok(output)
}

fn reports_command_error(bytes: &[u8]) -> bool {
    let text = String::from_utf8_lossy(bytes).to_ascii_lowercase();
    text.lines().any(|line| {
        let line = line.trim();
        line.starts_with("error")
            || [
                "error:",
                "exception",
                "permission deni",
                "not debuggable",
                "no process",
                "unknown process",
                "unable to",
                "failed to",
                "could not",
                "failure",
                "not allowed",
                "does not exist",
                "not found",
            ]
            .iter()
            .any(|needle| line.contains(needle))
    })
}

fn sanitized_diagnostic(bytes: &[u8]) -> String {
    bytes
        .iter()
        .take(DIAGNOSTIC_LIMIT)
        .map(|byte| match byte {
            b' '..=b'~' => char::from(*byte),
            b'\n' | b'\r' | b'\t' => ' ',
            _ => '?',
        })
        .collect::<String>()
        .trim()
        .to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_adb::{AndroidPackage, AndroidProcess, DeviceState};
    use std::{
        cell::{Cell, RefCell},
        collections::BTreeMap,
        ffi::OsString,
        process::ExitStatus,
    };

    const BOOT: &str = "01234567-89ab-cdef-0123-456789abcdef";
    const OTHER_BOOT: &str = "01234567-89ab-cdef-0123-456789abcdee";

    fn target() -> HeapCaptureTarget {
        HeapCaptureTarget {
            serial: "emulator-5554".into(),
            pid: 123,
            process_name: "com.example.app:worker".into(),
            package_name: "com.example.app".into(),
        }
    }

    fn snapshot() -> TargetSnapshot {
        TargetSnapshot {
            packages: vec![AndroidPackage {
                package_name: target().package_name,
            }],
            processes: vec![AndroidProcess {
                pid: 123,
                parent_pid: 1,
                user: "u0_a123".into(),
                name: target().process_name,
            }],
        }
    }

    fn stat(pid: u32, comm: &str, ticks: u64) -> String {
        format!("{pid} ({comm}) S {} {ticks} 0 0", ["0"; 18].join(" "))
    }

    fn identity(boot: &str, ticks: u64) -> String {
        format!("{boot}\n{}\n{boot}\n", stat(123, "example (worker)", ticks))
    }

    fn hprof() -> Vec<u8> {
        let mut bytes = b"JAVA PROFILE 1.0.3\0".to_vec();
        bytes.extend(8u32.to_be_bytes());
        bytes.extend(0u64.to_be_bytes());
        bytes.extend([0x2c, 0, 0, 0, 0, 0, 0, 0, 0]);
        bytes
    }

    #[cfg(unix)]
    fn exit_status(code: i32) -> ExitStatus {
        use std::os::unix::process::ExitStatusExt;
        ExitStatus::from_raw(code << 8)
    }

    #[cfg(windows)]
    fn exit_status(code: i32) -> ExitStatus {
        use std::os::windows::process::ExitStatusExt;
        ExitStatus::from_raw(code as u32)
    }

    fn output(stdout: impl Into<Vec<u8>>, stderr: impl Into<Vec<u8>>, code: i32) -> ProcessOutput {
        ProcessOutput {
            status: exit_status(code),
            stdout: stdout.into(),
            stderr: stderr.into(),
        }
    }

    #[derive(Default, Clone, Copy, PartialEq, Eq)]
    enum Scenario {
        #[default]
        Success,
        DumpPermission,
        CancelDump,
        CancelPull,
        ReusedPid,
        RebootAfterPull,
        OversizedRemote,
        ChangedRemote,
        PartialPull,
        OversizedPull,
        InvalidHeader,
        CleanupFailure,
        ExistingRemote,
        ReplacedPull,
    }

    struct SeenRequest {
        arguments: Vec<OsString>,
        timeout: Duration,
        cancelled: bool,
    }

    struct FakeExecutor {
        scenario: Scenario,
        cancel: CancellationToken,
        snapshot: TargetSnapshot,
        online: bool,
        requests: RefCell<Vec<SeenRequest>>,
        identity_reads: Cell<usize>,
        metadata_reads: Cell<usize>,
    }

    impl FakeExecutor {
        fn new(scenario: Scenario) -> Self {
            Self {
                scenario,
                cancel: CancellationToken::new(),
                snapshot: snapshot(),
                online: true,
                requests: RefCell::new(Vec::new()),
                identity_reads: Cell::new(0),
                metadata_reads: Cell::new(0),
            }
        }

        fn capture(&self, root: &Path) -> Result<CapturedHeap, String> {
            capture_with(self, Path::new("/fake/adb"), &target(), root, &self.cancel)
        }

        fn cleanup_count(&self) -> usize {
            self.requests
                .borrow()
                .iter()
                .filter(|request| request.arguments[3] == "rm")
                .count()
        }

        fn pulled(&self) -> bool {
            self.requests
                .borrow()
                .iter()
                .any(|request| request.arguments[2] == "pull")
        }
    }

    impl HeapExecutor for FakeExecutor {
        fn devices(&self, _: &CancellationToken) -> Result<Vec<AdbDevice>, String> {
            Ok(vec![AdbDevice {
                serial: target().serial,
                state: if self.online {
                    DeviceState::Device
                } else {
                    DeviceState::Unauthorized
                },
                raw_state: String::new(),
                attributes: BTreeMap::new(),
                status_detail: None,
            }])
        }

        fn target_snapshot(
            &self,
            _: &str,
            _: &CancellationToken,
        ) -> Result<TargetSnapshot, String> {
            Ok(self.snapshot.clone())
        }

        fn run(
            &self,
            request: &ProcessRequest,
            cancel: &CancellationToken,
        ) -> Result<ProcessOutput, ProcessError> {
            assert_eq!(request.stdout_limit, OUTPUT_LIMIT);
            assert_eq!(request.stderr_limit, OUTPUT_LIMIT);
            self.requests.borrow_mut().push(SeenRequest {
                arguments: request.arguments.clone(),
                timeout: request.timeout,
                cancelled: cancel.is_cancelled(),
            });
            let args: Vec<_> = request
                .arguments
                .iter()
                .map(|arg| arg.to_string_lossy())
                .collect();
            match (args[2].as_ref(), args[3].as_ref()) {
                ("shell", "cat") => {
                    let count = self.identity_reads.get() + 1;
                    self.identity_reads.set(count);
                    let ticks = if self.scenario == Scenario::ReusedPid && count >= 3 {
                        999
                    } else {
                        456
                    };
                    let boot = if self.scenario == Scenario::RebootAfterPull && self.pulled() {
                        OTHER_BOOT
                    } else {
                        BOOT
                    };
                    Ok(output(identity(boot, ticks), [], 0))
                }
                ("shell", "am") => match self.scenario {
                    Scenario::DumpPermission => Ok(output(
                        b"Error: Process is not debuggable".to_vec(),
                        b"java.lang.SecurityException: Permission Denial\x1b\n".to_vec(),
                        0,
                    )),
                    Scenario::CancelDump => {
                        self.cancel.cancel();
                        Err(ProcessError::Cancelled)
                    }
                    _ => Ok(output(b"Heap dump finished\n".to_vec(), [], 0)),
                },
                ("shell", "stat") => {
                    let count = self.metadata_reads.get() + 1;
                    self.metadata_reads.set(count);
                    let size = if self.scenario == Scenario::OversizedRemote {
                        aps_memory::MAX_INPUT_BYTES + 1
                    } else if self.scenario == Scenario::ChangedRemote && count > 1 {
                        hprof().len() as u64 + 1
                    } else {
                        hprof().len() as u64
                    };
                    Ok(output(format!("81a4 {size} 100 100 12345\n"), [], 0))
                }
                ("shell", "rm") => {
                    assert!(!cancel.is_cancelled(), "cleanup must have a fresh token");
                    assert_eq!(request.timeout, CLEANUP_TIMEOUT);
                    if self.scenario == Scenario::CleanupFailure {
                        Ok(output([], b"rm: Permission denied".to_vec(), 1))
                    } else {
                        Ok(output([], [], 0))
                    }
                }
                ("pull", _) => {
                    if self.scenario == Scenario::CancelPull {
                        self.cancel.cancel();
                        cancel.cancel();
                        return Err(ProcessError::Cancelled);
                    }
                    let mut bytes = hprof();
                    match self.scenario {
                        Scenario::PartialPull => {
                            bytes.pop();
                        }
                        Scenario::OversizedPull => bytes.extend([0; 32]),
                        Scenario::InvalidHeader => bytes[0] = b'X',
                        _ => {}
                    }
                    let destination = Path::new(&request.arguments[4]);
                    if self.scenario == Scenario::ReplacedPull {
                        let replacement = destination.with_extension("replacement.hprof");
                        fs::write(&replacement, bytes).unwrap();
                        fs::rename(replacement, destination).unwrap();
                    } else {
                        fs::write(destination, bytes).unwrap();
                    }
                    Ok(output([], [], 0))
                }
                ("shell", script) if script.starts_with("[ ! -e /data/local/tmp/aps-heap-") => Ok(
                    output([], [], i32::from(self.scenario == Scenario::ExistingRemote)),
                ),
                _ => panic!("unexpected fake command"),
            }
        }
    }

    fn capture_error(result: Result<CapturedHeap, String>) -> String {
        match result {
            Ok(_) => panic!("capture unexpectedly succeeded"),
            Err(error) => error,
        }
    }

    #[test]
    fn validates_serial_pid_names_and_absolute_root() {
        let root = std::env::temp_dir();
        assert!(validate_target(&target(), &root).is_ok());
        for serial in ["", "-s", "serial;reboot", "serial\n"] {
            let mut value = target();
            value.serial = serial.into();
            assert!(validate_target(&value, &root).is_err());
        }
        for pid in [0, u32::MAX] {
            let mut value = target();
            value.pid = pid;
            assert!(validate_target(&value, &root).is_err());
        }
        for package in ["", "com..app", "-com.app", "com.app;id", "com.app/path"] {
            let mut value = target();
            value.package_name = package.into();
            assert!(validate_target(&value, &root).is_err());
        }
        for name in [
            "other.app",
            "com.example.app:",
            "com.example.app:worker;id",
            "com.example.app:$(id)",
        ] {
            let mut value = target();
            value.process_name = name.into();
            assert!(validate_target(&value, &root).is_err());
        }
        assert!(validate_target(&target(), Path::new("relative")).is_err());
    }

    #[test]
    fn dump_command_uses_selected_serial_pid_and_generated_path_only() {
        let remote = generated_remote_path().unwrap();
        assert_ne!(remote, generated_remote_path().unwrap());
        let id = remote
            .strip_prefix("/data/local/tmp/aps-heap-")
            .unwrap()
            .strip_suffix(".hprof")
            .unwrap();
        assert!(
            id.bytes()
                .all(|byte| byte.is_ascii_hexdigit() || byte == b'-')
        );
        let command = dump_request(Path::new("/canonical/adb"), &target(), &remote);
        assert_eq!(command.program, Path::new("/canonical/adb"));
        assert_eq!(
            command.arguments,
            [
                "-s",
                "emulator-5554",
                "shell",
                "am",
                "dumpheap",
                "123",
                &remote
            ]
            .map(OsString::from)
        );
        assert_eq!(command.timeout, DUMP_TIMEOUT);
    }

    #[test]
    fn identity_parsing_handles_comm_spaces_and_parentheses_and_rejects_stale_pid() {
        assert_eq!(
            parse_start_ticks(&stat(123, "worker ) (nested)", 456), 123),
            Some(456)
        );
        assert_eq!(parse_start_ticks(&stat(124, "worker", 456), 123), None);
        assert_eq!(parse_start_ticks(&stat(123, "worker", 0), 123), None);
        assert_eq!(
            parse_start_ticks(&stat(123, "worker", 456).replace(") S ", ") Z "), 123),
            None
        );
        assert_eq!(parse_start_ticks("123 (worker) S 0", 123), None);
        assert_eq!(
            parse_identity(&identity(BOOT, 456), 123)
                .unwrap()
                .start_ticks,
            456
        );
        assert!(
            parse_identity(
                &format!("{BOOT}\n{}\n{OTHER_BOOT}\n", stat(123, "worker", 456)),
                123
            )
            .is_err()
        );
        assert!(
            parse_identity(&identity("00000000-0000-0000-0000-000000000000", 456), 123).is_err()
        );
        assert!(parse_identity("Permission denied\n", 123).is_err());
    }

    #[test]
    fn descriptor_requires_exact_running_application() {
        let mut snapshot = snapshot();
        assert!(verify_descriptor(&target(), &snapshot).is_ok());
        snapshot.processes[0].name = "com.example.app".into();
        assert!(verify_descriptor(&target(), &snapshot).is_err());
        snapshot = self::snapshot();
        snapshot.packages.clear();
        assert!(verify_descriptor(&target(), &snapshot).is_err());
        snapshot = self::snapshot();
        snapshot.processes[0].pid += 1;
        assert!(verify_descriptor(&target(), &snapshot).is_err());
    }

    #[test]
    fn detects_zero_exit_permission_errors_and_bounds_sanitized_diagnostics() {
        for text in [
            "Error: Process not found",
            "Permission Denial",
            "java.lang.SecurityException",
            "Failed to dump heap",
            "not debuggable",
        ] {
            assert!(reports_command_error(text.as_bytes()));
        }
        assert!(!reports_command_error(
            b"Waiting for dump...\nHeap dump finished\n"
        ));
        let diagnostic = sanitized_diagnostic(&[b'\x1b'; DIAGNOSTIC_LIMIT * 2]);
        assert_eq!(diagnostic.len(), DIAGNOSTIC_LIMIT);
        assert!(!diagnostic.chars().any(char::is_control));
        let error = checked_output(
            Ok(output([], b"Permission denied\x1b\n".to_vec(), 0)),
            "Dump",
            true,
        )
        .unwrap_err();
        assert!(error.contains("Permission denied"));
        assert!(!error.contains('\x1b'));
        assert!(
            checked_output(
                Ok(output(
                    identity(BOOT, 456).replace("example (worker)", "exception.app"),
                    [],
                    0
                )),
                "Read identity",
                false,
            )
            .is_ok()
        );
    }

    #[test]
    fn remote_metadata_rejects_symlinks_directories_oversize_and_malformed_sizes() {
        assert!(parse_remote_metadata("81a4 40 100 100 12").is_ok());
        for text in [
            "a1ff 40 100 100 12",
            "41ed 40 100 100 12",
            "81a4 0 100 100 12",
            "81a4 -1 100 100 12",
            "81a4 40 100 100 0",
            "81a4 40 100 100 12 extra",
        ] {
            assert!(parse_remote_metadata(text).is_err());
        }
        assert!(
            parse_remote_metadata(&format!(
                "81a4 {} 100 100 12",
                aps_memory::MAX_INPUT_BYTES + 1
            ))
            .is_err()
        );
    }

    #[test]
    fn validates_android_and_java_hprof_headers_without_reading_payloads() {
        for version in *b"123" {
            for size in [4u32, 8u32] {
                let mut header = hprof()[..HPROF_HEADER_BYTES].to_vec();
                header[17] = version;
                header[19..23].copy_from_slice(&size.to_be_bytes());
                assert!(validate_hprof_header(&header).is_ok());
            }
        }
        let mut header = hprof()[..HPROF_HEADER_BYTES].to_vec();
        header[19..23].copy_from_slice(&16u32.to_be_bytes());
        assert!(validate_hprof_header(&header).is_err());
        assert!(validate_hprof_header(b"JAVA PROFILE 1.0.3").is_err());
        assert!(validate_hprof_header(&[0; HPROF_HEADER_BYTES]).is_err());
    }

    #[test]
    fn success_retains_private_workspace_until_owner_is_dropped() {
        let root = TempWorkspace::create(&std::env::temp_dir()).unwrap();
        let fake = FakeExecutor::new(Scenario::Success);
        let heap = fake.capture(root.path()).unwrap();
        let path = heap.path.clone();
        assert!(path.is_file());
        assert_eq!(fs::metadata(&path).unwrap().len(), hprof().len() as u64);
        assert_eq!(fake.cleanup_count(), 1);
        for request in fake.requests.borrow().iter() {
            assert!(!request.cancelled);
            assert!(request.timeout <= PULL_TIMEOUT);
            assert!(
                !request
                    .arguments
                    .iter()
                    .any(|arg| arg == "root" || arg == "install" || arg == "reboot")
            );
        }
        drop(heap);
        assert!(!path.exists());
        assert_eq!(fs::read_dir(root.path()).unwrap().count(), 0);
    }

    #[cfg(unix)]
    #[test]
    fn capture_accepts_adb_replacing_the_local_destination_inode() {
        let root = TempWorkspace::create(&std::env::temp_dir()).unwrap();
        let fake = FakeExecutor::new(Scenario::ReplacedPull);
        let heap = fake.capture(root.path()).unwrap();
        assert_eq!(fs::read(&heap.path).unwrap(), hprof());
        assert_eq!(fake.cleanup_count(), 1);
    }

    #[test]
    fn failure_and_cancellation_clean_only_the_owned_remote_with_fresh_token() {
        for scenario in [
            Scenario::DumpPermission,
            Scenario::CancelDump,
            Scenario::CancelPull,
            Scenario::ReusedPid,
            Scenario::RebootAfterPull,
            Scenario::OversizedRemote,
            Scenario::ChangedRemote,
            Scenario::PartialPull,
            Scenario::OversizedPull,
            Scenario::InvalidHeader,
            Scenario::CleanupFailure,
        ] {
            let root = TempWorkspace::create(&std::env::temp_dir()).unwrap();
            let fake = FakeExecutor::new(scenario);
            let error = capture_error(fake.capture(root.path()));
            assert!(!error.is_empty());
            assert_eq!(fake.cleanup_count(), 1);
            assert_eq!(fs::read_dir(root.path()).unwrap().count(), 0);
            let requests = fake.requests.borrow();
            let dump_path = &requests
                .iter()
                .find(|request| request.arguments[3] == "am")
                .unwrap()
                .arguments[6];
            let cleanup = requests
                .iter()
                .find(|request| request.arguments[3] == "rm")
                .unwrap();
            assert_eq!(cleanup.arguments[6], *dump_path);
            assert_eq!(
                &cleanup.arguments[3..6],
                &[
                    OsString::from("rm"),
                    OsString::from("-f"),
                    OsString::from("--")
                ]
            );
            assert!(!cleanup.cancelled);
            if scenario == Scenario::DumpPermission {
                assert!(error.contains("Permission Denial"));
            }
            if scenario == Scenario::OversizedRemote {
                assert!(!fake.pulled());
            }
        }
    }

    #[test]
    fn preflight_failure_never_dumps_or_removes_unowned_remote_files() {
        let root = TempWorkspace::create(&std::env::temp_dir()).unwrap();
        for scenario in [Scenario::Success, Scenario::ExistingRemote] {
            let mut fake = FakeExecutor::new(scenario);
            if scenario == Scenario::Success {
                fake.snapshot.processes.clear();
            }
            capture_error(fake.capture(root.path()));
            assert!(
                !fake
                    .requests
                    .borrow()
                    .iter()
                    .any(|request| request.arguments[3] == "am")
            );
            assert_eq!(fake.cleanup_count(), 0);
            assert_eq!(fs::read_dir(root.path()).unwrap().count(), 0);
        }
        let mut offline = FakeExecutor::new(Scenario::Success);
        offline.online = false;
        capture_error(offline.capture(root.path()));
        assert!(offline.requests.borrow().is_empty());
        let cancelled = FakeExecutor::new(Scenario::Success);
        cancelled.cancel.cancel();
        capture_error(cancelled.capture(root.path()));
        assert!(cancelled.requests.borrow().is_empty());
    }

    #[test]
    fn local_validation_rejects_truncated_file_and_honors_cancellation() {
        let root = TempWorkspace::create(&std::env::temp_dir()).unwrap();
        let path = root.path().join("capture.raw.hprof");
        drop(create_local_file(&path).unwrap());
        fs::write(&path, hprof()).unwrap();
        let cancel = CancellationToken::new();
        validate_local_heap(&path, hprof().len() as u64, &cancel).unwrap();
        let file = OpenOptions::new().write(true).open(&path).unwrap();
        file.set_len(HPROF_HEADER_BYTES as u64 - 1).unwrap();
        assert!(validate_local_heap(&path, hprof().len() as u64, &cancel).is_err());
        cancel.cancel();
        assert!(
            validate_local_heap(&path, hprof().len() as u64, &cancel)
                .unwrap_err()
                .contains("cancelled")
        );
    }

    #[cfg(unix)]
    #[test]
    fn local_validation_accepts_adb_style_path_replacement_and_rejects_symlink() {
        use std::os::unix::fs::symlink;
        let root = TempWorkspace::create(&std::env::temp_dir()).unwrap();
        let path = root.path().join("capture.raw.hprof");
        let replacement = root.path().join("replacement.hprof");
        drop(create_local_file(&path).unwrap());
        fs::write(&path, hprof()).unwrap();
        fs::write(&replacement, hprof()).unwrap();
        fs::rename(&replacement, &path).unwrap();
        assert!(
            validate_local_heap(&path, hprof().len() as u64, &CancellationToken::new()).is_ok()
        );
        symlink(&path, &replacement).unwrap();
        assert!(inspect_local_file(&replacement, aps_memory::MAX_INPUT_BYTES).is_err());
    }
}

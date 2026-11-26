use std::collections::BTreeMap;
use std::ffi::{OsStr, OsString};
use std::fmt;
use std::io::{self, Read, Write};
use std::path::PathBuf;
use std::process::{Child, Command, ExitStatus, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, mpsc};
use std::thread;
use std::time::{Duration, Instant};

/// A per-stream allocation ceiling, independent of any tool's input size.
pub const MAX_OUTPUT_LIMIT: usize = 64 * 1024 * 1024;
/// Upper bound for an optional bounded stdin payload (for example a CLI prompt).
pub const MAX_STDIN_LIMIT: usize = 4 * 1024 * 1024;
const POLL_INTERVAL: Duration = Duration::from_millis(10);
const DRAIN_GRACE: Duration = Duration::from_millis(250);

#[derive(Clone, Default)]
pub struct CancellationToken(Arc<AtomicBool>);

impl CancellationToken {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn cancel(&self) {
        self.0.store(true, Ordering::Release);
    }

    pub fn is_cancelled(&self) -> bool {
        self.0.load(Ordering::Acquire)
    }
}

/// Command arguments are passed directly to the OS, never concatenated as shell text.
/// Intentionally does not implement Debug: arguments/environment may contain secrets.
pub struct ProcessRequest {
    pub program: PathBuf,
    pub arguments: Vec<OsString>,
    pub cwd: Option<PathBuf>,
    pub environment: BTreeMap<OsString, OsString>,
    pub env_clear: bool,
    pub timeout: Duration,
    pub stdout_limit: usize,
    pub stderr_limit: usize,
    /// Optional bounded payload written to the child's stdin before it is closed.
    pub stdin: Option<Vec<u8>>,
}

impl ProcessRequest {
    pub fn new(program: impl Into<PathBuf>) -> Self {
        Self {
            program: program.into(),
            arguments: Vec::new(),
            cwd: None,
            environment: BTreeMap::new(),
            env_clear: false,
            timeout: Duration::from_secs(30),
            stdout_limit: 1024 * 1024,
            stderr_limit: 1024 * 1024,
            stdin: None,
        }
    }

    /// Write this bounded payload to the child's stdin, then close the pipe.
    /// The current request-lifetime pipes are closed by dropping the child.
    pub fn stdin_bytes(mut self, bytes: impl Into<Vec<u8>>) -> Self {
        self.stdin = Some(bytes.into());
        self
    }

    pub fn arg(mut self, argument: impl AsRef<OsStr>) -> Self {
        self.arguments.push(argument.as_ref().to_owned());
        self
    }

    pub fn args<I, S>(mut self, arguments: I) -> Self
    where
        I: IntoIterator<Item = S>,
        S: AsRef<OsStr>,
    {
        self.arguments
            .extend(arguments.into_iter().map(|value| value.as_ref().to_owned()));
        self
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Stream {
    Stdout,
    Stderr,
}

#[derive(Debug)]
pub enum ProcessError {
    InvalidRequest(&'static str),
    Io {
        operation: &'static str,
        kind: io::ErrorKind,
    },
    Cancelled,
    Timeout,
    OutputLimit(Stream),
    /// The child exited, but inherited output handles did not close before timeout.
    OutputNotClosed,
}

impl fmt::Display for ProcessError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidRequest(message) => write!(f, "Invalid process request: {message}"),
            Self::Io { operation, kind } => write!(f, "Host process {operation} failed ({kind:?})"),
            Self::Cancelled => f.write_str("Host process cancelled"),
            Self::Timeout => f.write_str("Host process timed out"),
            Self::OutputLimit(stream) => write!(f, "Host process {stream:?} limit exceeded"),
            Self::OutputNotClosed => f.write_str("Host process output handles did not close"),
        }
    }
}

impl std::error::Error for ProcessError {}

fn io_error(operation: &'static str, error: io::Error) -> ProcessError {
    // Do not retain an OS error's arbitrary text, program path, argv or stderr.
    ProcessError::Io {
        operation,
        kind: error.kind(),
    }
}

#[derive(Debug)]
pub struct ProcessOutput {
    pub status: ExitStatus,
    pub stdout: Vec<u8>,
    pub stderr: Vec<u8>,
}

#[derive(Debug, Clone, Copy, Default)]
pub struct ProcessRunner;

struct DrainResult {
    stream: Stream,
    bytes: io::Result<Vec<u8>>,
    exceeded: bool,
}

impl ProcessRunner {
    /// Blocking operation: UI callers must schedule it on their background executor.
    /// Both pipes are drained concurrently; cancellation/timeout/limits kill and reap
    /// the direct child. Unix children start in a new process group which is also
    /// signalled on termination. This is lifecycle control, not a security sandbox;
    /// detached daemon processes and Windows descendant jobs need tool-specific policy.
    pub fn run(
        &self,
        request: &ProcessRequest,
        cancellation: &CancellationToken,
    ) -> Result<ProcessOutput, ProcessError> {
        if cancellation.is_cancelled() {
            return Err(ProcessError::Cancelled);
        }
        if request.program.as_os_str().is_empty() || request.timeout.is_zero() {
            return Err(ProcessError::InvalidRequest(
                "program and nonzero timeout required",
            ));
        }
        if request.stdout_limit > MAX_OUTPUT_LIMIT || request.stderr_limit > MAX_OUTPUT_LIMIT {
            return Err(ProcessError::InvalidRequest("stream limit exceeds 64 MiB"));
        }
        if request
            .stdin
            .as_ref()
            .is_some_and(|stdin| stdin.len() > MAX_STDIN_LIMIT)
        {
            return Err(ProcessError::InvalidRequest("stdin exceeds 4 MiB"));
        }
        let deadline = Instant::now()
            .checked_add(request.timeout)
            .ok_or(ProcessError::InvalidRequest("timeout exceeds clock range"))?;
        let mut command = Command::new(&request.program);
        command
            .args(&request.arguments)
            .stdin(if request.stdin.is_some() {
                Stdio::piped()
            } else {
                Stdio::null()
            })
            .stdout(Stdio::piped())
            .stderr(Stdio::piped());
        if let Some(cwd) = &request.cwd {
            command.current_dir(cwd);
        }
        if request.env_clear {
            command.env_clear();
        }
        // The headless provider credential belongs only to its approved HTTP
        // request. ADB, profilers and other tool subprocesses never need it.
        command
            .envs(&request.environment)
            .env_remove("APS_CLI_AI_KEY");
        #[cfg(unix)]
        {
            use std::os::unix::process::CommandExt;
            command.process_group(0);
        }
        #[cfg(windows)]
        {
            use std::os::windows::process::CommandExt;
            // Background tools must not allocate or inherit a console window.
            command.creation_flags(0x08000000); // CREATE_NO_WINDOW
        }
        let child = command.spawn().map_err(|error| io_error("start", error))?;
        let mut guard = ChildGuard(Some(child));
        let child = guard.0.as_mut().expect("child is present until reaped");
        let stdout = child
            .stdout
            .take()
            .expect("stdout was configured as a pipe");
        let stderr = child
            .stderr
            .take()
            .expect("stderr was configured as a pipe");
        if let Some(bytes) = request.stdin.clone() {
            let mut stdin = child.stdin.take().expect("stdin was configured as a pipe");
            let _ = thread::Builder::new()
                .name("aps-stdin".into())
                .spawn(move || {
                    let _ = stdin.write_all(&bytes);
                });
        }
        let (sender, receiver) = mpsc::channel();
        let stdout_limit = request.stdout_limit;
        let stderr_limit = request.stderr_limit;
        let stdout_sender = sender.clone();
        let stdout_thread = thread::Builder::new()
            .name("aps-stdout".into())
            .spawn(move || {
                let _ = stdout_sender.send(drain(stdout, Stream::Stdout, stdout_limit));
            })
            .map_err(|error| io_error("stdout reader start", error))?;
        let stderr_thread = thread::Builder::new()
            .name("aps-stderr".into())
            .spawn(move || {
                let _ = sender.send(drain(stderr, Stream::Stderr, stderr_limit));
            })
            .map_err(|error| io_error("stderr reader start", error))?;
        let mut received = 0;
        let result = (|| {
            let mut stdout = None;
            let mut stderr = None;
            let mut status = None;
            loop {
                while let Ok(output) = receiver.try_recv() {
                    received += 1;
                    if output.exceeded {
                        return Err(ProcessError::OutputLimit(output.stream));
                    }
                    let bytes = output
                        .bytes
                        .map_err(|error| io_error("output read", error))?;
                    match output.stream {
                        Stream::Stdout => stdout = Some(bytes),
                        Stream::Stderr => stderr = Some(bytes),
                    }
                }
                if cancellation.is_cancelled() {
                    return Err(ProcessError::Cancelled);
                }
                if status.is_none() {
                    status = guard
                        .0
                        .as_mut()
                        .expect("child is present until reaped")
                        .try_wait()
                        .map_err(|error| io_error("wait", error))?;
                }
                if let (Some(status), Some(_), Some(_)) = (status, &stdout, &stderr) {
                    return Ok(ProcessOutput {
                        status,
                        stdout: stdout.take().expect("stdout completed"),
                        stderr: stderr.take().expect("stderr completed"),
                    });
                }
                let now = Instant::now();
                if now >= deadline {
                    return Err(if status.is_some() {
                        ProcessError::OutputNotClosed
                    } else {
                        ProcessError::Timeout
                    });
                }
                thread::sleep(POLL_INTERVAL.min(deadline.duration_since(now)));
            }
        })();
        if result.is_err() {
            guard.terminate();
            // Killing an ordinary owned child closes its pipes. Never block
            // forever if a detached descendant escaped with inherited handles.
            let drain_deadline = Instant::now() + DRAIN_GRACE;
            while received < 2 {
                let Some(remaining) = drain_deadline.checked_duration_since(Instant::now()) else {
                    break;
                };
                match receiver.recv_timeout(remaining) {
                    Ok(_) => received += 1,
                    Err(_) => break,
                }
            }
        } else {
            // try_wait above has already reaped this child.
            guard.0 = None;
        }
        for handle in [stdout_thread, stderr_thread] {
            if handle.is_finished() {
                let _ = handle.join();
            }
        }
        result
    }
}

fn drain(mut pipe: impl Read, stream: Stream, limit: usize) -> DrainResult {
    let mut bytes = Vec::with_capacity(limit.min(8192));
    let mut buffer = [0u8; 8192];
    loop {
        let read = match pipe.read(&mut buffer) {
            Ok(0) => {
                return DrainResult {
                    stream,
                    bytes: Ok(bytes),
                    exceeded: false,
                };
            }
            Ok(read) => read,
            Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
            Err(error) => {
                return DrainResult {
                    stream,
                    bytes: Err(error),
                    exceeded: false,
                };
            }
        };
        let remaining = limit.saturating_sub(bytes.len());
        bytes.extend_from_slice(&buffer[..read.min(remaining)]);
        if read > remaining {
            return DrainResult {
                stream,
                bytes: Ok(bytes),
                exceeded: true,
            };
        }
    }
}

struct ChildGuard(Option<Child>);

impl ChildGuard {
    fn terminate(&mut self) {
        if let Some(child) = &mut self.0 {
            #[cfg(unix)]
            signal_group(child.id());
            let _ = child.kill();
            let _ = child.wait();
        }
        self.0 = None;
    }
}

impl Drop for ChildGuard {
    fn drop(&mut self) {
        self.terminate();
    }
}

#[cfg(unix)]
fn signal_group(id: u32) {
    unsafe extern "C" {
        fn kill(pid: i32, signal: i32) -> i32;
    }
    if let Ok(id) = i32::try_from(id) {
        // SAFETY: kill accepts integer identifiers; this is the process group
        // created explicitly for this child, not the caller's inherited group.
        let _ = unsafe { kill(-id, 9) };
    }
}

#[cfg(all(test, windows))]
mod windows_tests {
    use super::*;
    use std::os::windows::process::CommandExt;

    #[link(name = "kernel32")]
    unsafe extern "system" {
        fn GetConsoleWindow() -> *mut std::ffi::c_void;
    }

    #[link(name = "user32")]
    unsafe extern "system" {
        fn IsWindowVisible(window: *mut std::ffi::c_void) -> i32;
    }

    fn has_console() -> bool {
        // SAFETY: GetConsoleWindow takes no arguments and returns a borrowed handle.
        !unsafe { GetConsoleWindow() }.is_null()
    }

    #[test]
    fn background_child_has_no_console_and_preserves_output_and_status() {
        // Give the host a hidden console: the unfixed runner inherits it instead
        // of creating a visible window. PowerShell is only the fixture launcher;
        // both the ProcessRunner host and its console probe are Rust test helpers.
        let executable = std::env::current_exe().unwrap();
        let quoted = executable.to_str().unwrap().replace('\'', "''");
        let script = format!(
            "$ErrorActionPreference = 'Stop'; \
             $stdout = [IO.Path]::GetTempFileName(); \
             $stderr = [IO.Path]::GetTempFileName(); \
             try {{ $child = Start-Process -FilePath '{quoted}' \
             -ArgumentList '--exact process::windows_tests::hidden_console_host --ignored --nocapture' \
             -WindowStyle Hidden -RedirectStandardOutput $stdout \
             -RedirectStandardError $stderr -PassThru -Wait; \
             [Console]::Out.Write([IO.File]::ReadAllText($stdout)); \
             [Console]::Error.Write([IO.File]::ReadAllText($stderr)); \
             exit $child.ExitCode }} finally {{ Remove-Item -LiteralPath $stdout, $stderr }}"
        );
        let output = Command::new("powershell.exe")
            .args([
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                &script,
            ])
            .creation_flags(0x08000000) // CREATE_NO_WINDOW: never flash the launcher.
            .output()
            .expect("Windows PowerShell must be available for the hidden-console fixture");
        assert!(
            output.status.success()
                && String::from_utf8_lossy(&output.stdout).contains("aps-hidden-console-host-ok"),
            "hidden console host failed: status={:?}, stdout={}, stderr={}",
            output.status.code(),
            String::from_utf8_lossy(&output.stdout),
            String::from_utf8_lossy(&output.stderr),
        );
    }

    #[test]
    #[ignore = "subprocess fixture, launched with a hidden console by the regression test"]
    fn hidden_console_host() {
        assert!(
            has_console(),
            "fixture must own a console to detect inheritance"
        );
        // SAFETY: the console window handle is borrowed and only queried.
        assert_eq!(unsafe { IsWindowVisible(GetConsoleWindow()) }, 0);
        let request = ProcessRequest::new(std::env::current_exe().unwrap()).args([
            "--exact",
            "process::windows_tests::console_probe_child",
            "--ignored",
            "--nocapture",
        ]);
        let output = ProcessRunner
            .run(&request, &CancellationToken::new())
            .unwrap();
        let stdout = String::from_utf8(output.stdout).unwrap();
        assert!(
            stdout.contains("console=false"),
            "child inherited a console: {stdout}"
        );
        assert!(
            stdout.ends_with("probe-out"),
            "stdout was not preserved: {stdout}"
        );
        assert_eq!(output.stderr, b"probe-err");
        assert_eq!(output.status.code(), Some(7));
        println!("aps-hidden-console-host-ok");
    }

    #[test]
    #[ignore = "subprocess fixture, launched only through ProcessRunner"]
    fn console_probe_child() {
        let mut stdout = io::stdout().lock();
        write!(stdout, "console={}\nprobe-out", has_console()).unwrap();
        stdout.flush().unwrap();
        let mut stderr = io::stderr().lock();
        stderr.write_all(b"probe-err").unwrap();
        stderr.flush().unwrap();
        std::process::exit(7);
    }
}

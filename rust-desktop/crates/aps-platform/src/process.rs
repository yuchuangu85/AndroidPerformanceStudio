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
        command.envs(&request.environment);
        #[cfg(unix)]
        {
            use std::os::unix::process::CommandExt;
            command.process_group(0);
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

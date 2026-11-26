//! Bounded Windows sink for simpleperf's sequential fopen/fwrite reporter.
use super::{MAX_ARTIFACT, validate_protobuf_prefix};
use aps_platform::{CancellationToken, ProcessRequest, ProcessRunner};
use std::{
    ffi::c_void,
    path::Path,
    sync::{
        atomic::{AtomicU64, Ordering},
        mpsc,
    },
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

type Handle = *mut c_void;
const INVALID_HANDLE: Handle = -1isize as Handle;
const ERROR_IO_PENDING: u32 = 997;
const ERROR_PIPE_CONNECTED: u32 = 535;
const ERROR_BROKEN_PIPE: u32 = 109;
const ERROR_NO_DATA: u32 = 232;
const WAIT_OBJECT_0: u32 = 0;
const WAIT_TIMEOUT: u32 = 258;
const POLL_MS: u32 = 10;

// OVERLAPPED's union is represented by its two DWORD offsets (unused for pipes).
#[repr(C)]
struct Overlapped {
    internal: usize,
    internal_high: usize,
    offset: u32,
    offset_high: u32,
    event: Handle,
}

#[link(name = "kernel32")]
unsafe extern "system" {
    fn CreateNamedPipeW(
        name: *const u16,
        open: u32,
        mode: u32,
        instances: u32,
        out_size: u32,
        in_size: u32,
        timeout: u32,
        security: *const c_void,
    ) -> Handle;
    fn CreateEventW(security: *const c_void, manual: i32, initial: i32, name: *const u16)
    -> Handle;
    fn ConnectNamedPipe(pipe: Handle, overlapped: *mut Overlapped) -> i32;
    fn ReadFile(
        file: Handle,
        buffer: *mut c_void,
        size: u32,
        read: *mut u32,
        overlapped: *mut Overlapped,
    ) -> i32;
    fn GetOverlappedResult(
        file: Handle,
        overlapped: *mut Overlapped,
        bytes: *mut u32,
        wait: i32,
    ) -> i32;
    fn CancelIoEx(file: Handle, overlapped: *mut Overlapped) -> i32;
    fn WaitForSingleObject(handle: Handle, milliseconds: u32) -> u32;
    fn CloseHandle(handle: Handle) -> i32;
    fn GetLastError() -> u32;
}

struct OwnedHandle(Handle);
impl Drop for OwnedHandle {
    fn drop(&mut self) {
        // SAFETY: this wrapper exclusively owns a valid kernel handle.
        unsafe {
            CloseHandle(self.0);
        }
    }
}

struct Pipe {
    handle: OwnedHandle,
    name: String,
}
impl Pipe {
    fn new() -> Result<Self, String> {
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_err(|_| "converter pipe clock unavailable")?
            .as_nanos();
        let name = format!(
            r"\\.\pipe\aps-simpleperf-{}-{nonce:x}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        );
        let wide: Vec<u16> = name.encode_utf16().chain(Some(0)).collect();
        // SAFETY: NUL-terminated name; null security means non-inheritable.
        // INBOUND | OVERLAPPED | FIRST_PIPE_INSTANCE; BYTE | WAIT | REJECT_REMOTE_CLIENTS.
        let handle = unsafe {
            CreateNamedPipeW(
                wide.as_ptr(),
                0x4008_0001,
                0x8,
                1,
                0,
                64 * 1024,
                0,
                std::ptr::null(),
            )
        };
        if handle == INVALID_HANDLE {
            return Err(os_error("create converter pipe"));
        }
        Ok(Self {
            handle: OwnedHandle(handle),
            name,
        })
    }
}

fn os_error(operation: &str) -> String {
    // Numeric diagnostics never include input paths or child-controlled text.
    format!("{operation} failed (Windows error {})", unsafe {
        GetLastError()
    })
}

/// An operation owns its event and cancels/settles pending I/O before its
/// OVERLAPPED or caller-owned read buffer may leave scope.
struct Operation<'a> {
    pipe: &'a Pipe,
    event: OwnedHandle,
    overlapped: Box<Overlapped>,
    pending: bool,
}
impl<'a> Operation<'a> {
    fn new(pipe: &'a Pipe) -> Result<Self, String> {
        // SAFETY: unnamed, manual-reset event, initially nonsignalled.
        let event = unsafe { CreateEventW(std::ptr::null(), 1, 0, std::ptr::null()) };
        if event.is_null() {
            return Err(os_error("create converter pipe event"));
        }
        Ok(Self {
            pipe,
            event: OwnedHandle(event),
            overlapped: Box::new(Overlapped {
                internal: 0,
                internal_high: 0,
                offset: 0,
                offset_high: 0,
                event,
            }),
            pending: false,
        })
    }

    fn wait(&mut self, check: &mut impl FnMut(bool) -> Result<(), String>) -> Result<u32, String> {
        loop {
            // Check a ready event before checking child exit: buffered data and
            // EOF can already be available even when ProcessRunner has finished.
            let state = unsafe { WaitForSingleObject(self.event.0, 0) };
            if state == WAIT_OBJECT_0 {
                let mut bytes = 0;
                // SAFETY: stable OVERLAPPED, live handle and buffer until settled.
                let ok = unsafe {
                    GetOverlappedResult(self.pipe.handle.0, &mut *self.overlapped, &mut bytes, 0)
                };
                self.pending = false;
                if ok != 0 {
                    return Ok(bytes);
                }
                let error = unsafe { GetLastError() };
                if error == ERROR_BROKEN_PIPE {
                    return Ok(0);
                }
                return Err(format!("converter pipe I/O failed (Windows error {error})"));
            }
            if state != WAIT_TIMEOUT {
                return Err(os_error("wait for converter pipe"));
            }
            if let Err(error) = check(true) {
                // Child exit can race the readiness probe above. Its final
                // close may have signalled EOF while we observed its status.
                if unsafe { WaitForSingleObject(self.event.0, 0) } == WAIT_OBJECT_0 {
                    continue;
                }
                return Err(error);
            }
            // Bounded polling bridges caller cancellation without modifying it.
            let state = unsafe { WaitForSingleObject(self.event.0, POLL_MS) };
            if state != WAIT_OBJECT_0 && state != WAIT_TIMEOUT {
                return Err(os_error("wait for converter pipe"));
            }
        }
    }
}
impl Drop for Operation<'_> {
    fn drop(&mut self) {
        if self.pending {
            // SAFETY: cancel exactly this operation, then wait for completion;
            // even ERROR_NOT_FOUND means completion raced and must be settled.
            unsafe {
                CancelIoEx(self.pipe.handle.0, &mut *self.overlapped);
                let mut bytes = 0;
                GetOverlappedResult(self.pipe.handle.0, &mut *self.overlapped, &mut bytes, 1);
            }
        }
    }
}

fn drain(
    pipe: &Pipe,
    limit: usize,
    mut check: impl FnMut(bool) -> Result<(), String>,
) -> Result<Vec<u8>, String> {
    check(false)?;
    {
        let mut operation = Operation::new(pipe)?;
        // SAFETY: stable, event-backed OVERLAPPED, valid inbound pipe.
        if unsafe { ConnectNamedPipe(pipe.handle.0, &mut *operation.overlapped) } == 0 {
            match unsafe { GetLastError() } {
                ERROR_PIPE_CONNECTED | ERROR_NO_DATA => {} // Client connected (possibly already closed).
                ERROR_IO_PENDING => {
                    operation.pending = true;
                    operation.wait(&mut check)?;
                }
                error => {
                    return Err(format!(
                        "connect converter pipe failed (Windows error {error})"
                    ));
                }
            }
        }
    }
    let mut bytes = Vec::with_capacity(limit.min(64 * 1024));
    let mut buffer = [0u8; 64 * 1024];
    loop {
        // Read only the remaining allowance, then exactly one sentinel byte.
        let size = (limit - bytes.len()).min(buffer.len()).max(1);
        let mut operation = Operation::new(pipe)?;
        let mut read = 0;
        // SAFETY: buffer outlives operation, which settles I/O before dropping.
        let ok = unsafe {
            ReadFile(
                pipe.handle.0,
                buffer.as_mut_ptr().cast(),
                size as u32,
                &mut read,
                &mut *operation.overlapped,
            )
        };
        if ok == 0 {
            match unsafe { GetLastError() } {
                ERROR_BROKEN_PIPE => read = 0,
                ERROR_IO_PENDING => {
                    operation.pending = true;
                    read = operation.wait(&mut check)?;
                }
                error => {
                    return Err(format!(
                        "read converter pipe failed (Windows error {error})"
                    ));
                }
            }
        }
        // Also check between synchronous reads, so a fast writer cannot starve
        // cancellation/deadline checks. Child completion alone is not an error here.
        if read == 0 {
            return Ok(bytes);
        }
        if read as usize > limit - bytes.len() {
            return Err("converter protobuf output limit exceeded".into());
        }
        bytes.extend_from_slice(&buffer[..read as usize]);
        // The check passed to drain must allow ready bytes after child success.
        check(false)?;
    }
}

pub(super) fn convert(
    converter: &Path,
    input: &Path,
    cancel: &CancellationToken,
    offline: bool,
) -> Result<Vec<u8>, String> {
    execute(
        cancel,
        MAX_ARTIFACT as usize,
        Duration::from_secs(180),
        |pipe| {
            let mut request = ProcessRequest::new(converter)
                .args(["report-sample", "--protobuf", "--show-callchain", "-i"])
                .arg(input)
                .arg("-o")
                .arg(pipe);
            if offline {
                request.env_clear = true;
                request.cwd = input.parent().map(Path::to_path_buf);
            }
            request
        },
    )
}

fn execute(
    cancel: &CancellationToken,
    limit: usize,
    timeout: Duration,
    request: impl FnOnce(&str) -> ProcessRequest,
) -> Result<Vec<u8>, String> {
    if cancel.is_cancelled() {
        return Err("simpleperf conversion cancelled".into());
    }
    let deadline = Instant::now()
        .checked_add(timeout)
        .ok_or("converter deadline out of range")?;
    let pipe = Pipe::new()?;
    let mut request = request(&pipe.name);
    request.timeout = timeout;
    request.stdout_limit = 1024 * 1024;
    request.stderr_limit = 1024 * 1024;
    let local = CancellationToken::new();
    thread::scope(|scope| {
        let (sender, receiver) = mpsc::sync_channel(1);
        let local_ref = &local;
        let worker = thread::Builder::new()
            .name("aps-simpleperf".into())
            .spawn_scoped(scope, move || {
                let result = ProcessRunner.run(&request, local_ref);
                let _ = sender.send(result);
            })
            .map_err(|_| "could not start converter worker")?;
        let mut child = None;
        let mut check = |waiting: bool| {
            if cancel.is_cancelled() {
                return Err("simpleperf conversion cancelled".into());
            }
            if Instant::now() >= deadline {
                return Err("simpleperf conversion timed out".into());
            }
            if child.is_none() {
                match receiver.try_recv() {
                    Ok(result) => child = Some(result),
                    Err(mpsc::TryRecvError::Empty) => {}
                    Err(mpsc::TryRecvError::Disconnected) => {
                        return Err("converter worker disconnected".into());
                    }
                }
            }
            match &child {
                Some(Err(error)) => Err(error.to_string()),
                Some(Ok(output)) if !output.status.success() => {
                    Err("bounded simpleperf conversion failed".into())
                }
                Some(Ok(_)) if waiting => Err("converter exited before pipe EOF".into()),
                _ => Ok(()),
            }
        };
        let result = drain(&pipe, limit, &mut check);
        if result.is_err() {
            local.cancel();
        }
        // After EOF, keep bridging cancellation while waiting for child exit.
        let result = result.and_then(|bytes| {
            loop {
                check(false)?;
                if worker.is_finished() {
                    break;
                }
                thread::sleep(Duration::from_millis(POLL_MS as u64));
            }
            Ok(bytes)
        });
        if result.is_err() {
            local.cancel();
        }
        let joined = worker.join();
        if joined.is_err() {
            return Err("converter worker panicked".into());
        }
        let bytes = result?;
        let output = match child {
            Some(result) => result,
            None => receiver
                .recv()
                .map_err(|_| "converter worker disconnected")?,
        }
        .map_err(|error| error.to_string())?;
        if !output.status.success() {
            return Err("bounded simpleperf conversion failed".into());
        }
        validate_protobuf_prefix(&bytes)?;
        Ok(bytes)
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::{fs::OpenOptions, io::Write};

    const PREFIX: &[u8] = b"SIMPLEPERF\x01\0";

    fn fixture(
        mode: &str,
        limit: usize,
        timeout: Duration,
        cancel: &CancellationToken,
    ) -> Result<Vec<u8>, String> {
        execute(cancel, limit, timeout, |pipe| {
            let mut request = ProcessRequest::new(std::env::current_exe().unwrap()).args([
                "--exact",
                "windows_converter::tests::writer",
                "--ignored",
                "--nocapture",
            ]);
            request
                .environment
                .insert("APS_PIPE_FIXTURE".into(), pipe.into());
            request
                .environment
                .insert("APS_PIPE_MODE".into(), mode.into());
            request
        })
    }

    #[test]
    #[ignore = "owned child fixture; invoked only with explicit pipe/mode environment"]
    fn writer() {
        let mode = std::env::var("APS_PIPE_MODE").unwrap();
        if mode == "fail-before-connect" {
            std::process::exit(7);
        }
        if mode == "success-before-connect" {
            return;
        }
        if mode == "wait-before-connect" {
            thread::sleep(Duration::from_secs(30));
            return;
        }
        let mut pipe = OpenOptions::new()
            .write(true)
            .open(std::env::var_os("APS_PIPE_FIXTURE").unwrap())
            .unwrap();
        if mode == "wait-after-connect" {
            thread::sleep(Duration::from_secs(30));
            return;
        }
        if mode == "truncated" {
            pipe.write_all(b"SIMPL").unwrap();
            return;
        }
        pipe.write_all(PREFIX).unwrap();
        if mode == "overflow" {
            // More than pipe buffering too: reader errors must terminate/reap
            // a writer blocked in WriteFile rather than wait for its exit.
            let block = [42; 64 * 1024];
            for _ in 0..64 {
                if pipe.write_all(&block).is_err() {
                    return;
                }
            }
        } else if mode == "bytes" {
            for chunk in [b"abc".as_slice(), b"defgh"] {
                pipe.write_all(chunk).unwrap();
            }
        }
        drop(pipe);
        if mode == "fail-after-eof" {
            std::process::exit(9);
        }
        if mode == "wait-after-eof" {
            thread::sleep(Duration::from_secs(30));
        }
    }

    #[test]
    fn preserves_bytes_and_accepts_exact_cap_with_eof() {
        let cancel = CancellationToken::new();
        let bytes = fixture("bytes", 20, Duration::from_secs(5), &cancel).unwrap();
        assert_eq!(bytes, b"SIMPLEPERF\x01\0abcdefgh");
        assert!(!cancel.is_cancelled());
        assert_eq!(
            fixture("prefix", 12, Duration::from_secs(5), &cancel).unwrap(),
            PREFIX
        );
    }

    #[test]
    fn overflow_is_bounded_and_does_not_cancel_caller() {
        let cancel = CancellationToken::new();
        let start = Instant::now();
        let error = fixture("overflow", 32, Duration::from_secs(5), &cancel).unwrap_err();
        assert!(error.contains("limit exceeded"), "{error}");
        assert!(!cancel.is_cancelled());
        assert!(start.elapsed() < Duration::from_secs(3));
    }

    #[test]
    fn rejects_truncated_prefix_and_child_failure_after_eof() {
        for mode in ["truncated", "fail-after-eof"] {
            assert!(fixture(mode, 32, Duration::from_secs(5), &CancellationToken::new()).is_err());
        }
    }

    #[test]
    fn child_exit_before_connection_does_not_wait_for_deadline() {
        for mode in ["fail-before-connect", "success-before-connect"] {
            let start = Instant::now();
            assert!(fixture(mode, 32, Duration::from_secs(5), &CancellationToken::new()).is_err());
            assert!(start.elapsed() < Duration::from_secs(3), "{mode}");
        }
    }

    #[test]
    fn spawn_failure_does_not_wait_for_connection() {
        let start = Instant::now();
        let result = execute(
            &CancellationToken::new(),
            32,
            Duration::from_secs(5),
            |_| ProcessRequest::new(r"C:\aps-nonexistent-fixture\missing.exe"),
        );
        assert!(result.is_err());
        assert!(start.elapsed() < Duration::from_secs(3));
    }

    #[test]
    fn cancellation_interrupts_accept_read_and_post_eof_wait() {
        for mode in [
            "wait-before-connect",
            "wait-after-connect",
            "wait-after-eof",
        ] {
            let cancel = CancellationToken::new();
            let start = Instant::now();
            thread::scope(|scope| {
                scope.spawn(|| {
                    thread::sleep(Duration::from_millis(150));
                    cancel.cancel();
                });
                let error = fixture(mode, 32, Duration::from_secs(5), &cancel).unwrap_err();
                assert!(error.to_lowercase().contains("cancel"), "{mode}: {error}");
            });
            assert!(start.elapsed() < Duration::from_secs(3), "{mode}");
        }
    }

    #[test]
    fn deadline_interrupts_accept_read_and_post_eof_wait() {
        for mode in [
            "wait-before-connect",
            "wait-after-connect",
            "wait-after-eof",
        ] {
            let cancel = CancellationToken::new();
            let start = Instant::now();
            let error = fixture(mode, 32, Duration::from_millis(250), &cancel).unwrap_err();
            assert!(
                error.to_lowercase().contains("timed out"),
                "{mode}: {error}"
            );
            assert!(!cancel.is_cancelled());
            assert!(start.elapsed() < Duration::from_secs(3));
        }
    }

    #[test]
    fn already_cancelled_does_not_build_or_launch_child() {
        let cancel = CancellationToken::new();
        cancel.cancel();
        assert!(
            execute(&cancel, 32, Duration::from_secs(1), |_| panic!(
                "must not launch"
            ))
            .is_err()
        );
    }

    #[test]
    fn client_closed_before_accept_preserves_buffered_bytes() {
        let pipe = Pipe::new().unwrap();
        let mut writer = OpenOptions::new().write(true).open(&pipe.name).unwrap();
        writer.write_all(PREFIX).unwrap();
        drop(writer);
        assert_eq!(drain(&pipe, 12, |_| Ok(())).unwrap(), PREFIX);
    }

    #[test]
    fn client_connected_before_accept_race_is_supported() {
        let pipe = Pipe::new().unwrap();
        assert!(pipe.name.is_ascii());
        let mut writer = OpenOptions::new().write(true).open(&pipe.name).unwrap();
        writer.write_all(PREFIX).unwrap();
        thread::scope(|scope| {
            scope.spawn(move || {
                thread::sleep(Duration::from_millis(50));
                drop(writer);
            });
            assert_eq!(drain(&pipe, 12, |_| Ok(())).unwrap(), PREFIX);
        });
    }
}

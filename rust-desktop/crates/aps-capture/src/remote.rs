//! An owned recorder is stopped through its private stdin pipe, never by
//! signalling a numeric PID which could have been reused. Simpleperf's
//! --stdio-controls-profiling exits on EOF (AOSP simpleperf/cmd_record.cpp).
use super::*;

const CONTROL_ACK_TIMEOUT: Duration = Duration::from_secs(5);
const EXIT_RECEIPT_TIMEOUT: Duration = Duration::from_secs(5);

// A single ADB query may time out while a user stop is in flight. Retrying
// cannot establish success: only an actual parsed supervisor receipt can.
fn exit_receipt_poll(
    stop: &AtomicBool,
    deadline: Option<Instant>,
    read: impl FnOnce(Duration) -> Result<Option<i32>, String>,
) -> Result<Option<i32>, String> {
    let timeout = match deadline {
        Some(deadline) => deadline
            .checked_duration_since(Instant::now())
            .filter(|remaining| !remaining.is_zero())
            .ok_or("owned stop request did not produce a verified recorder exit before timeout")?
            .min(EXIT_RECEIPT_TIMEOUT),
        None => EXIT_RECEIPT_TIMEOUT,
    };
    match read(timeout) {
        Err(error)
            if stop.load(Ordering::Acquire)
                && error == "cannot read recorder exit receipt: Host process timed out" =>
        {
            Ok(None)
        }
        result => result,
    }
}

fn exit_receipt_poll_for_cleanup(
    deadline: Instant,
    read: impl FnOnce(Duration) -> Result<Option<i32>, String>,
) -> Result<Option<i32>, String> {
    let timeout = deadline
        .checked_duration_since(Instant::now())
        .filter(|remaining| !remaining.is_zero())
        .ok_or("owned recorder termination was not confirmed")?
        .min(EXIT_RECEIPT_TIMEOUT);
    match read(timeout) {
        Err(error) if error == "cannot read recorder exit receipt: Host process timed out" => {
            Ok(None)
        }
        result => result,
    }
}

#[cfg(test)]
mod tests;

#[derive(Debug, PartialEq, Eq)]
struct RecorderIdentity {
    boot_id: String,
    start_ticks: u64,
}

#[derive(Debug)]
enum RecorderObservationError {
    Transport(String),
    Invalid(String),
}

impl From<String> for RecorderObservationError {
    fn from(value: String) -> Self {
        Self::Invalid(value)
    }
}
impl From<&str> for RecorderObservationError {
    fn from(value: &str) -> Self {
        Self::Invalid(value.to_owned())
    }
}
impl std::fmt::Display for RecorderObservationError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::Transport(value) | Self::Invalid(value) => formatter.write_str(value),
        }
    }
}

pub(super) struct RemoteRecording<'a> {
    capture: &'a ConfirmedCapture,
    pub(super) launched: bool,
    pub(super) stopped: bool,
    active: bool,
}
impl<'a> RemoteRecording<'a> {
    pub(super) fn new(capture: &'a ConfirmedCapture) -> Self {
        Self {
            capture,
            launched: false,
            stopped: false,
            active: true,
        }
    }
    pub(super) fn cleanup(&mut self) -> Result<(), String> {
        if !self.active {
            return Ok(());
        }
        self.active = false;
        let directory = &self.capture.plan.remote_directory;
        let token = CancellationToken::new();
        if self.launched && !self.stopped {
            let result = (|| {
                self.capture.request_recorder_stop()?;
                let deadline = Instant::now() + Duration::from_secs(10);
                loop {
                    if exit_receipt_poll_for_cleanup(deadline, |timeout| {
                        self.capture.recorder_exit_code_with_timeout(timeout)
                    })?
                    .is_some()
                    {
                        break;
                    }
                    if Instant::now() >= deadline {
                        return Err("owned recorder termination was not confirmed".to_owned());
                    }
                    thread::sleep(Duration::from_millis(100));
                }
                Ok(())
            })();
            if let Err(error) = result {
                self.capture.set(
                    if self.capture.handle.stop.load(Ordering::Acquire)
                        && !self.capture.handle.cancel.is_cancelled()
                    {
                        CaptureState::StopUnverified
                    } else {
                        CaptureState::CancellationUnverified
                    },
                );
                return Err(format!(
                    "{error}; exact remote capture directory retained: {directory}"
                ));
            }
            self.stopped = true;
        }
        // Only the fresh, exclusively created directory is eligible. Never
        // delete files under a recorder whose termination remains unknown.
        let script = format!(
            "rm -f {directory}/perf.data {directory}/record.log {directory}/record.pid {directory}/record.exit {directory}/record.stop {directory}/record.writer.exit {directory}/record.lease {directory}/record.lease.expired {directory}/record.control {directory}/record.control.tmp {directory}/record.control.sent {directory}/record.reply && rmdir {directory}"
        );
        self.capture
            .adb_with_token(&["shell", &script], Duration::from_secs(5), &token)
            .map_err(|error| format!("{error}; stopped recorder artifacts retained at {directory}"))
    }
}
impl Drop for RemoteRecording<'_> {
    fn drop(&mut self) {
        let _ = self.cleanup();
    }
}

impl ConfirmedCapture {
    pub(super) fn start_remote_recording(
        &self,
        pid: &str,
        remote_data: &str,
    ) -> Result<u32, String> {
        let directory = &self.plan.remote_directory;
        let app = self
            .plan
            .app_package
            .as_ref()
            .map(|package| format!(" --app {package}"))
            .unwrap_or_default();
        self.plan.request.duration.validate()?;
        // Only this private writer has the recorder's stdin. A request is
        // atomic and has a monotonically increasing sequence; repeated polls
        // cannot emit the same command twice. AOSP replies `ok\n` or `error\n`
        // on stdout after enabling/disabling events, not when queued here.
        let control_poll = format!(
            "if [ -f {directory}/record.control ]; then \
             sequence=; command=; IFS=' ' read -r sequence command <{directory}/record.control || :; \
             case \"$sequence\" in ''|*[!0-9]*) ;; *) case \"$command\" in pause|resume) \
             if [ \"$sequence\" -gt \"$previous_control\" ]; then \
             printf '%s\\n' \"$command\" || break; printf '%s\\n' \"$sequence\" >{directory}/record.control.sent; previous_control=$sequence; \
             fi ;; esac ;; esac; fi"
        );
        let writer_control = match self.plan.request.duration {
            CaptureDuration::Timed(value) => {
                let writer_ticks = value.as_millis().div_ceil(100) + 100;
                format!(
                    "n={writer_ticks}; previous_control=0; while [ \"$n\" -gt 0 ] && [ -d {directory} ] && [ ! -f {directory}/record.stop ]; do {control_poll}; sleep 0.1; n=$((n-1)); done"
                )
            }
            CaptureDuration::Manual => {
                let ticks = MANUAL_LEASE_SECONDS * 10;
                format!(
                    "n={ticks}; previous=; previous_control=0; while [ \"$n\" -gt 0 ] && [ -d {directory} ] && [ ! -f {directory}/record.stop ]; do \
                    {control_poll}; \
                    current=; IFS= read -r current <{directory}/record.lease || :; \
                    case \"$current\" in ''|*[!0-9]*) ;; *) if [ \"$current\" != \"$previous\" ]; then previous=$current; n={ticks}; fi ;; esac; \
                    sleep 0.1; n=$((n-1)); done; \
                    if [ \"$n\" -eq 0 ] && [ -d {directory} ] && [ ! -f {directory}/record.stop ]; then : >{directory}/record.lease.expired; fi"
                )
            }
        };
        let duration = self
            .plan
            .request
            .duration
            .seconds_argument()
            .map(|value| format!(" --duration {value}"))
            .unwrap_or_default();
        let sampling = self.plan.request.sampling.record_arguments()?.join(" ");
        // Wait for the recorder inside the pipe's reader, not for the whole
        // pipeline. Its exit closes the writer promptly, including on failure.
        // Save the pipe first: Android's shell can replace fd 0 with /dev/null
        // for an asynchronous child even when it has the no-op redirect <&0.
        let worker = format!(
            ": >{directory}/record.reply; ( trap '' PIPE; {writer_control}; : >{directory}/record.writer.exit ) | \
             ( exec 3<&0; simpleperf record --stdio-controls-profiling {sampling}{app} -p {pid}{duration} --size-limit {MAX_ARTIFACT} -o {remote_data} <&3 3<&- >>{directory}/record.reply 2>{directory}/record.log & \
             aps_pid=$!; exec 3<&-; printf '%s\\n' \"$aps_pid\" >{directory}/record.pid; wait \"$aps_pid\"; aps_exit=$?; : >{directory}/record.stop; exit \"$aps_exit\" ); \
             aps_exit=$?; n=50; while [ \"$n\" -gt 0 ] && [ ! -f {directory}/record.writer.exit ]; do sleep 0.1; n=$((n-1)); done; \
             [ -f {directory}/record.writer.exit ] && printf '%s\\n' \"$aps_exit\" >{directory}/record.exit"
        );
        // Android's shell saves redirected descriptors above fd 2 for a
        // compound subshell. Exec a fresh shell so those CLOEXEC ADB sockets
        // cannot keep `adb shell` open for the recorder's entire lifetime.
        let script = format!(
            "sh -c '{}' </dev/null >/dev/null 2>&1 & printf 'APS_LAUNCH=%s\\n' \"$!\"",
            worker.replace('\'', "'\\''")
        );
        let output = self.adb_output(
            &["shell", &script],
            Duration::from_secs(15),
            &CancellationToken::new(),
        )?;
        let launched = output
            .lines()
            .find_map(|line| line.trim().strip_prefix("APS_LAUNCH="))
            .and_then(|pid| pid.parse::<u32>().ok())
            .filter(|pid| *pid > 0);
        if launched.is_none() {
            return Err("remote recorder did not return its framed launch identity".into());
        }
        let script =
            format!("if [ -f {directory}/record.pid ]; then cat {directory}/record.pid; fi");
        let deadline = Instant::now() + Duration::from_secs(5);
        loop {
            let value = self.adb_output(
                &["shell", &script],
                Duration::from_secs(5),
                &CancellationToken::new(),
            )?;
            if let Ok(pid) = value.trim().parse::<u32>()
                && pid > 0
            {
                return Ok(pid);
            }
            if Instant::now() >= deadline {
                return Err("remote recorder PID was not published before timeout".into());
            }
            thread::sleep(Duration::from_millis(50));
        }
    }
    pub(super) fn await_remote_recording(&self, pid: u32, remote_data: &str) -> Result<(), String> {
        let deadline = Instant::now() + self.plan.request.duration.host_timeout();
        let mut identity = None;
        let mut stop_deadline = None;
        let mut next_lease = Instant::now();
        let mut lease_sequence = 0u64;
        let mut control_sequence = 0u64;
        let mut pending_control = None;
        loop {
            if self.handle.cancel.is_cancelled() {
                self.set(CaptureState::Stopping);
                return Err("capture cancelled; awaiting owned recorder shutdown".into());
            }
            // Stop has priority over all polling reads. A device may take up to
            // five seconds to answer an exit-status query; issuing the owned EOF
            // first makes a UI stop request observable by the recorder promptly.
            if self.handle.stop.load(Ordering::Acquire) && stop_deadline.is_none() {
                self.request_recorder_stop()?;
                stop_deadline = Some(Instant::now() + Duration::from_secs(10));
            }
            if let Some(code) = exit_receipt_poll(&self.handle.stop, stop_deadline, |timeout| {
                self.recorder_exit_code_with_timeout(timeout)
            })? {
                if self.plan.request.duration == CaptureDuration::Manual {
                    let directory = &self.plan.remote_directory;
                    let expired = self.adb_output(&["shell", &format!("if [ -f {directory}/record.lease.expired ]; then printf expired; fi")], Duration::from_secs(5), &CancellationToken::new())?;
                    if !expired.is_empty() {
                        return Err("manual recorder stopped because its host lease expired; no completed artifact is published".into());
                    }
                }
                return self.check_recorder_exit(code);
            }
            if self.plan.request.duration == CaptureDuration::Manual
                && stop_deadline.is_none()
                && Instant::now() >= next_lease
            {
                lease_sequence += 1;
                self.refresh_manual_lease(lease_sequence)?;
                next_lease = Instant::now() + Duration::from_secs(1);
            }
            if stop_deadline.is_none() {
                match self.recorder_identity(pid, remote_data) {
                    Ok(Some(current)) => {
                        if identity
                            .as_ref()
                            .is_some_and(|previous| previous != &current)
                        {
                            return Err("remote recorder process instance changed".into());
                        }
                        identity = Some(current);
                    }
                    Ok(None) => {}
                    Err(RecorderObservationError::Transport(_))
                        if self.handle.stop.load(Ordering::Acquire) =>
                    {
                        // A stop can arrive while a /proc request is in flight.
                        // Stop the owned stdin writer, never a numeric PID, then
                        // require the supervisor exit receipt before publication.
                        self.request_recorder_stop()?;
                        stop_deadline = Some(Instant::now() + Duration::from_secs(10));
                    }
                    Err(error) => return Err(error.to_string()),
                }
                if !self.handle.stop.load(Ordering::Acquire) {
                    let result = (|| {
                        if let Some((control, sequence, requested_at)) = pending_control {
                            if self.recorder_control_acknowledged(sequence)? {
                                self.handle.acknowledge_control(control);
                                pending_control = None;
                            } else if Instant::now() >= requested_at + CONTROL_ACK_TIMEOUT {
                                return Err("simpleperf sampling control was not acknowledged before timeout; recorder support for pause/resume is required".into());
                            }
                        } else if let Some(control) = match self.handle.state() {
                            CaptureState::Pausing => Some(SamplingControl::Pause),
                            CaptureState::Resuming => Some(SamplingControl::Resume),
                            _ => None,
                        } {
                            control_sequence += 1;
                            self.request_sampling_control(control, control_sequence)?;
                            pending_control = Some((control, control_sequence, Instant::now()));
                        }
                        Ok(())
                    })();
                    match result {
                        Ok(()) => {}
                        Err(RecorderObservationError::Transport(_))
                            if self.handle.stop.load(Ordering::Acquire) =>
                        {
                            // Stop can arrive while publication/reply reads
                            // are in flight. Only transport failure may yield
                            // to owned EOF and a verified supervisor exit.
                            self.request_recorder_stop()?;
                            stop_deadline = Some(Instant::now() + Duration::from_secs(10));
                        }
                        Err(error) => return Err(error.to_string()),
                    }
                }
            }
            // Absence alone never means successful exit; the parent must
            // publish its verified wait status, including launch failures.
            if stop_deadline.is_some_and(|deadline| Instant::now() >= deadline) {
                return Err(
                    "owned stop request did not produce a verified recorder exit before timeout"
                        .into(),
                );
            }
            if Instant::now() >= deadline {
                return Err("remote recording exceeded its bounded host timeout or lost its verified exit status".into());
            }
            thread::sleep(Duration::from_millis(100));
        }
    }
    fn request_sampling_control(
        &self,
        control: SamplingControl,
        sequence: u64,
    ) -> Result<(), RecorderObservationError> {
        let directory = &self.plan.remote_directory;
        let command = match control {
            SamplingControl::Pause => "pause",
            SamplingControl::Resume => "resume",
        };
        // stdout is opened O_APPEND by the recorder, so resetting the previous
        // reply does not leave holes or retain a previous `ok`. The new command
        // becomes visible only after that reset. Only one is outstanding.
        let script = format!(
            "[ -d {directory} ] && [ ! -L {directory} ] && \
             : >{directory}/record.reply && printf '%s\\n' '{sequence} {command}' >{directory}/record.control.tmp && \
             mv {directory}/record.control.tmp {directory}/record.control"
        );
        let output = self
            .adb_output_bytes(
                &["shell", &script],
                Duration::from_secs(5),
                &self.handle.cancel,
            )
            .map_err(|error| {
                RecorderObservationError::Transport(format!(
                    "cannot request simpleperf {command}: {error}"
                ))
            })?;
        if !output.is_empty() {
            return Err("unexpected sampling control publication response".into());
        }
        Ok(())
    }

    fn recorder_control_acknowledged(
        &self,
        sequence: u64,
    ) -> Result<bool, RecorderObservationError> {
        let directory = &self.plan.remote_directory;
        let script = format!(
            "sent=; if [ -f {directory}/record.control.sent ]; then IFS= read -r sent <{directory}/record.control.sent || :; fi; \
             printf '%s\\n' \"$sent\"; if [ -f {directory}/record.reply ]; then head -c 16 {directory}/record.reply; fi"
        );
        let bytes = self
            .adb_output_bytes(
                &["shell", &script],
                Duration::from_secs(5),
                &self.handle.cancel,
            )
            .map_err(RecorderObservationError::Transport)?;
        let output = String::from_utf8(bytes).map_err(|_| {
            RecorderObservationError::Invalid("sampling control receipt is not UTF-8".into())
        })?;
        let (sent, reply) = output
            .split_once('\n')
            .ok_or("malformed sampling control receipt")?;
        if sent != sequence.to_string() || reply.is_empty() {
            return Ok(false);
        }
        match reply {
            "ok\n" => Ok(true),
            "error\n" => {
                Err("simpleperf rejected sampling control; pause/resume is unavailable".into())
            }
            _ => Err("simpleperf returned an invalid sampling control acknowledgment".into()),
        }
    }
    pub(super) fn refresh_manual_lease(&self, sequence: u64) -> Result<(), String> {
        let directory = &self.plan.remote_directory;
        self.adb_with_token(&["shell", &format!("[ -d {directory} ] && [ ! -L {directory} ] && printf '%s\\n' '{sequence}' >{directory}/record.lease")], Duration::from_secs(5), &CancellationToken::new())
            .map_err(|error| format!("cannot renew manual recorder lease: {error}"))
    }
    fn request_recorder_stop(&self) -> Result<(), String> {
        let directory = &self.plan.remote_directory;
        let script =
            format!("[ -d {directory} ] && [ ! -L {directory} ] && : >{directory}/record.stop");
        self.adb_with_token(
            &["shell", &script],
            Duration::from_secs(5),
            &CancellationToken::new(),
        )
        .map_err(|error| format!("cannot request owned recorder stop: {error}"))
    }
    #[cfg(all(test, unix))]
    fn recorder_exit_code(&self) -> Result<Option<i32>, String> {
        self.recorder_exit_code_with_timeout(EXIT_RECEIPT_TIMEOUT)
    }
    fn recorder_exit_code_with_timeout(&self, timeout: Duration) -> Result<Option<i32>, String> {
        let path = format!("{}/record.exit", self.plan.remote_directory);
        let script = format!("if [ -f {path} ]; then cat {path}; fi");
        let output = self
            .adb_output(&["shell", &script], timeout, &CancellationToken::new())
            .map_err(|error| format!("cannot read recorder exit receipt: {error}"))?;
        if output.trim().is_empty() {
            return Ok(None);
        }
        output
            .trim()
            .parse()
            .map(Some)
            .map_err(|_| "malformed recorder exit status".into())
    }
    fn check_recorder_exit(&self, code: i32) -> Result<(), String> {
        if code == 0 {
            return Ok(());
        }
        let log = format!("head -c 2048 {}/record.log", self.plan.remote_directory);
        let detail = self
            .adb_output(
                &["shell", &log],
                Duration::from_secs(5),
                &CancellationToken::new(),
            )
            .unwrap_or_default();
        let detail: String = detail
            .chars()
            .filter(|c| !c.is_control() || *c == '\n')
            .take(512)
            .collect();
        let sampling = &self.plan.request.sampling;
        Err(format!(
            "simpleperf record failed (exit {code}) for event {} with scope {:?}; verify event availability and target profiling permission. Simpleperf stdin profiling controls are required. No root or permission changes were requested. {detail}",
            sampling.event, sampling.scope,
        ))
    }
    fn recorder_identity(
        &self,
        pid: u32,
        remote_data: &str,
    ) -> Result<Option<RecorderIdentity>, RecorderObservationError> {
        let script = format!(
            "cat /proc/sys/kernel/random/boot_id || exit 1; if [ -d /proc/{pid} ]; then cat /proc/{pid}/stat || exit 1; tr '\\000' ' ' </proc/{pid}/cmdline || exit 1; else printf 'APS_RECORDER_EXITED'; fi"
        );
        let output = self
            .adb_output(
                &["shell", &script],
                Duration::from_secs(5),
                &CancellationToken::new(),
            )
            .map_err(|error| {
                RecorderObservationError::Transport(format!(
                    "cannot observe recorder identity: {error}"
                ))
            })?;
        let mut lines = output.lines();
        let boot_id = lines.next().ok_or("missing recorder boot identity")?;
        if boot_id != self.plan.observed.boot_id {
            return Err("device rebooted during CPU capture".into());
        }
        let stat = lines.next().ok_or("missing recorder process identity")?;
        if stat == "APS_RECORDER_EXITED" {
            return Ok(None);
        }
        let start_ticks = parse_start_ticks(stat, pid)?;
        let cmdline = lines.collect::<Vec<_>>().join(" ");
        if !cmdline.contains("simpleperf") || !cmdline.contains(remote_data) {
            return Err("remote recorder command identity changed".into());
        }
        Ok(Some(RecorderIdentity {
            boot_id: boot_id.to_owned(),
            start_ticks,
        }))
    }
}

#[cfg(test)]
mod receipt_retry_tests {
    use super::*;

    #[test]
    fn real_host_process_timeout_retries_then_accepts_only_verified_receipt() {
        let stop = AtomicBool::new(true);
        let deadline = Some(Instant::now() + Duration::from_secs(5));
        let first = exit_receipt_poll(&stop, deadline, |_| {
            let mut process = ProcessRequest::new(std::env::current_exe().unwrap()).args([
                "--exact",
                "remote::receipt_retry_tests::timeout_child",
                "--ignored",
                "--nocapture",
            ]);
            process.timeout = Duration::from_millis(200);
            let err = ProcessRunner
                .run(&process, &CancellationToken::new())
                .unwrap_err();
            Err(format!("cannot read recorder exit receipt: {err}"))
        });
        assert_eq!(first, Ok(None));
        assert_eq!(
            exit_receipt_poll(&stop, deadline, |_| Ok(Some(0))),
            Ok(Some(0))
        );
    }

    #[test]
    #[ignore = "subprocess fixture for the real host timeout regression"]
    fn timeout_child() {
        thread::sleep(Duration::from_secs(3));
    }

    #[test]
    fn stopping_timeout_retries_but_bad_receipts_never_become_success() {
        let stop = AtomicBool::new(true);
        let deadline = Some(Instant::now() + Duration::from_secs(1));
        assert_eq!(
            exit_receipt_poll(&stop, deadline, |_| Err(
                "cannot read recorder exit receipt: Host process timed out".into()
            )),
            Ok(None)
        );
        assert_eq!(
            exit_receipt_poll(&stop, deadline, |_| Ok(Some(0))),
            Ok(Some(0))
        );
        assert!(
            exit_receipt_poll(&stop, deadline, |_| Err(
                "malformed recorder exit status".into()
            ))
            .is_err()
        );
        assert!(
            exit_receipt_poll(&stop, deadline, |_| Err(
                "cannot read recorder exit receipt: ADB remote capture command failed".into()
            ))
            .is_err()
        );
        assert!(
            exit_receipt_poll(&stop, Some(Instant::now()), |_| panic!(
                "expired stop deadline must not spawn another ADB process"
            ))
            .is_err()
        );
        assert_eq!(
            exit_receipt_poll_for_cleanup(Instant::now() + Duration::from_secs(1), |_| Err(
                "cannot read recorder exit receipt: Host process timed out".into()
            )),
            Ok(None)
        );
        assert!(
            exit_receipt_poll_for_cleanup(Instant::now(), |_| panic!(
                "expired cleanup deadline must not spawn another ADB process"
            ))
            .is_err()
        );
        assert!(
            exit_receipt_poll_for_cleanup(Instant::now() + Duration::from_secs(1), |_| Err(
                "malformed recorder exit status".into()
            ))
            .is_err()
        );
        stop.store(false, Ordering::Release);
        assert!(
            exit_receipt_poll(&stop, None, |_| Err(
                "cannot read recorder exit receipt: Host process timed out".into()
            ))
            .is_err()
        );
        assert_eq!(
            exit_receipt_poll(&stop, None, |_| {
                stop.store(true, Ordering::Release);
                Err("cannot read recorder exit receipt: Host process timed out".into())
            }),
            Ok(None),
            "stop racing an in-flight query must issue EOF on the next loop"
        );
    }
}

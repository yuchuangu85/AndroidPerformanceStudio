//! An owned recorder is stopped through its private stdin pipe, never by
//! signalling a numeric PID which could have been reused. Simpleperf's
//! --stdio-controls-profiling exits on EOF (AOSP simpleperf/cmd_record.cpp).
use super::*;

#[cfg(test)]
mod tests;

#[derive(Debug, PartialEq, Eq)]
struct RecorderIdentity {
    boot_id: String,
    start_ticks: u64,
}

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
                    if self.capture.recorder_exit_code()?.is_some() {
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
            "rm -f {directory}/perf.data {directory}/record.log {directory}/record.pid {directory}/record.exit {directory}/record.stop {directory}/record.writer.exit {directory}/record.lease {directory}/record.lease.expired && rmdir {directory}"
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
        let writer_control = match self.plan.request.duration {
            CaptureDuration::Timed(value) => {
                let writer_ticks = value.as_millis().div_ceil(100) + 100;
                format!(
                    "n={writer_ticks}; while [ \"$n\" -gt 0 ] && [ -d {directory} ] && [ ! -f {directory}/record.stop ]; do sleep 0.1; n=$((n-1)); done"
                )
            }
            CaptureDuration::Manual => {
                let ticks = MANUAL_LEASE_SECONDS * 10;
                format!(
                    "n={ticks}; previous=; while [ \"$n\" -gt 0 ] && [ -d {directory} ] && [ ! -f {directory}/record.stop ]; do \
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
            "( {writer_control}; : >{directory}/record.writer.exit ) | \
             ( exec 3<&0; simpleperf record --stdio-controls-profiling {sampling}{app} -p {pid}{duration} --size-limit {MAX_ARTIFACT} -o {remote_data} <&3 3<&- >{directory}/record.log 2>&1 & \
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
        loop {
            if self.handle.cancel.is_cancelled() {
                self.set(CaptureState::Stopping);
                return Err("capture cancelled; awaiting owned recorder shutdown".into());
            }
            if let Some(code) = self.recorder_exit_code()? {
                if self.plan.request.duration == CaptureDuration::Manual {
                    let directory = &self.plan.remote_directory;
                    let expired = self.adb_output(&["shell", &format!("if [ -f {directory}/record.lease.expired ]; then printf expired; fi")], Duration::from_secs(5), &CancellationToken::new())?;
                    if !expired.is_empty() {
                        return Err("manual recorder stopped because its host lease expired; no completed artifact is published".into());
                    }
                }
                return self.check_recorder_exit(code);
            }
            if self.handle.stop.load(Ordering::Acquire) && stop_deadline.is_none() {
                self.request_recorder_stop()?;
                stop_deadline = Some(Instant::now() + Duration::from_secs(10));
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
    fn recorder_exit_code(&self) -> Result<Option<i32>, String> {
        let path = format!("{}/record.exit", self.plan.remote_directory);
        let script = format!("if [ -f {path} ]; then cat {path}; fi");
        let output = self
            .adb_output(
                &["shell", &script],
                Duration::from_secs(5),
                &CancellationToken::new(),
            )
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

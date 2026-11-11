#![cfg(unix)]

use super::*;
use aps_adb::{AdbLocation, AdbLocationSource};
use std::{
    os::unix::fs::PermissionsExt,
    sync::atomic::{AtomicU64, Ordering},
};

static NEXT_FIXTURE: AtomicU64 = AtomicU64::new(0);

fn valid_perf_data_fixture() -> Vec<u8> {
    let mut bytes = vec![0u8; 272];
    bytes[..8].copy_from_slice(b"PERFILE2");
    bytes[8..16].copy_from_slice(&104u64.to_le_bytes());
    bytes[16..24].copy_from_slice(&160u64.to_le_bytes());
    bytes[24..32].copy_from_slice(&104u64.to_le_bytes());
    bytes[32..40].copy_from_slice(&160u64.to_le_bytes());
    bytes[40..48].copy_from_slice(&264u64.to_le_bytes());
    bytes[48..56].copy_from_slice(&8u64.to_le_bytes());
    bytes[270..272].copy_from_slice(&8u16.to_le_bytes());
    bytes
}

struct FakeAdb {
    root: PathBuf,
}

impl FakeAdb {
    fn new(mode: &str) -> Self {
        let root = std::env::var_os("APS_CAPTURE_TEST_ROOT")
            .map(PathBuf::from)
            .unwrap_or_else(std::env::temp_dir)
            .join(format!(
                "aps-capture-{}-{}",
                std::process::id(),
                NEXT_FIXTURE.fetch_add(1, Ordering::Relaxed)
            ));
        fs::create_dir_all(root.parent().unwrap()).unwrap();
        fs::create_dir(&root).unwrap();
        fs::create_dir(root.join("bin")).unwrap();
        fs::write(root.join("mode"), mode).unwrap();
        let quoted_root = format!("'{}'", root.to_str().unwrap().replace('\'', "'\\''"));
        // The shell shim records crossing the exec boundary and verifies that
        // neither of its diagnostic streams reaches the ADB launch response.
        Self::executable(
            &root.join("bin/sh"),
            &format!(
                r#"#!/bin/sh
printf detached >{root}/exec-boundary
printf unexpected-worker-stdout
printf unexpected-worker-stderr >&2
exec /bin/sh "$@"
"#,
                root = quoted_root
            ),
        );
        Self::executable(
            &root.join("bin/simpleperf"),
            &format!(
                r#"#!/bin/sh
printf '%s\000' "$@" >{root}/recorder-argv
while [ "$#" -gt 0 ]; do
    if [ "$1" = -o ]; then shift; output=$1; fi
    shift
done
cp {root}/perf.data.fixture "$output"
printf started
printf ready >{root}/recorder-ready
case $(cat {root}/mode) in
    eof|launch-failure|manual|delayed-pull|identity-block|identity-invalid) cat >/dev/null; printf eof >{root}/recorder-eof ;;
    failure) printf recorder-failed >&2; exit 23 ;;
    *) sleep 0.1 ;;
esac
exit 0
"#,
                root = quoted_root
            ),
        );
        Self::executable(
            &root.join("adb"),
            &format!(
                r#"#!/bin/sh
export PATH={root}/bin:$PATH
if [ "$1" = devices ]; then printf 'List of devices attached\ndevice device\n'; exit 0; fi
[ "$1" = -s ] && [ "$2" = device ] || exit 91
shift 2
if [ "$1" = exec-out ]; then
    shift
    if [ "$(cat {root}/mode)" = delayed-pull ]; then
        : >{root}/pull-ready
        while [ ! -f {root}/allow-pull ]; do sleep 0.01; done
    fi
    exec "$@"
fi
[ "$1" = shell ] || exit 92
shift
case "$*" in
    'ps -A -o PID,PPID,USER,NAME') printf 'PID PPID USER NAME\n42 1 u0_a1 com.example.sample\n'; exit 0 ;;
    'cat /proc/sys/kernel/random/boot_id') printf 'boot\n'; exit 0 ;;
    'cat /proc/42/stat') printf '42 (sample) S 1 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 7\n'; exit 0 ;;
    'cat /proc/sys/kernel/random/boot_id || exit 1;'*)
        case $(cat {root}/mode) in
            identity-block|identity-invalid)
                : >{root}/identity-waiting
                while [ ! -f {root}/release-identity ]; do sleep 0.01; done
                if [ "$(cat {root}/mode)" = identity-block ]; then exit 94; fi
                printf 'wrong-boot\nAPS_RECORDER_EXITED\n'; exit 0 ;;
        esac
        printf 'boot\nAPS_RECORDER_EXITED\n'; exit 0 ;;
esac
case "$*" in
    *record.stop*) [ ! -f {root}/stop-fails ] || exit 93 ;;
esac
case "$*" in
    *APS_LAUNCH=*)
        response=$(/bin/sh -c "$*" 2>{root}/launch-stderr)
        [ ! -s {root}/launch-stderr ] || exit 94
        case "${{response#APS_LAUNCH=}}" in ''|*[!0-9]*) exit 95 ;; esac
        if [ "$(cat {root}/mode)" = launch-failure ]; then printf malformed; exit 0; fi
        printf '%s\n' "$response"
        exit 0 ;;
esac
exec /bin/sh -c "$*"
"#,
                root = quoted_root
            ),
        );
        fs::write(root.join("perf.data.fixture"), valid_perf_data_fixture()).unwrap();
        fs::create_dir(root.join("unrelated")).unwrap();
        fs::write(root.join("unrelated/perf.data"), b"retain").unwrap();
        Self { root }
    }

    fn executable(path: &Path, script: &str) {
        fs::write(path, script).unwrap();
        fs::set_permissions(path, fs::Permissions::from_mode(0o700)).unwrap();
    }

    fn capture(&self) -> ConfirmedCapture {
        self.capture_with_mode(CallGraphMode::default())
    }

    fn capture_with_mode(&self, call_graph: CallGraphMode) -> ConfirmedCapture {
        self.plan(call_graph)
            .confirm("device", 42, "boot", 7)
            .unwrap()
    }

    fn plan(&self, call_graph: CallGraphMode) -> PlannedCapture {
        self.plan_with_configuration(SamplingConfiguration {
            call_graph,
            ..SamplingConfiguration::default()
        })
    }

    fn plan_with_configuration(&self, sampling: SamplingConfiguration) -> PlannedCapture {
        PlannedCapture {
            controller: CaptureController {
                adb: AdbClient::new(AdbLocation {
                    executable: self.root.join("adb"),
                    source: AdbLocationSource::Path,
                }),
                artifact_root: self.root.join("local"),
            },
            request: CpuCaptureRequest {
                run_id: "owned".into(),
                serial: "device".into(),
                pid: 42,
                duration: CaptureDuration::Timed(Duration::from_secs(1)),
                sampling,
                converter: None,
            },
            observed: TargetObservation {
                serial: "device".into(),
                pid: 42,
                process_name: "com.example.sample".into(),
                boot_id: "boot".into(),
                start_ticks: 7,
            },
            remote_directory: self.root.join("remote").to_str().unwrap().into(),
            created: Instant::now(),
            converter_digest: None,
            app_package: Some("com.example.sample".into()),
        }
    }

    fn assert_recorder_argv(&self, call_graph: CallGraphMode) {
        self.assert_sampling_argv(&SamplingConfiguration {
            call_graph,
            ..SamplingConfiguration::default()
        });
    }

    fn assert_sampling_argv(&self, sampling: &SamplingConfiguration) {
        self.assert_duration_argv(sampling, CaptureDuration::Timed(Duration::from_secs(1)));
    }

    fn assert_duration_argv(&self, sampling: &SamplingConfiguration, duration: CaptureDuration) {
        let event = format!(
            "{}{}",
            sampling.event,
            match sampling.scope {
                EventScope::User => ":u",
                EventScope::Kernel => ":k",
                EventScope::Both => "",
            }
        );
        let rate = sampling.rate.value().to_string();
        let mut expected = vec![
            "record",
            "--stdio-controls-profiling",
            "-e",
            &event,
            if sampling.rate.is_period() {
                "-c"
            } else {
                "-f"
            },
            &rate,
        ];
        match sampling.call_graph {
            CallGraphMode::Dwarf => expected.push("-g"),
            CallGraphMode::FramePointer => expected.extend(["--call-graph", "fp"]),
            CallGraphMode::None => {}
        }
        expected.extend(["--app", "com.example.sample", "-p", "42"]);
        let duration = duration.seconds_argument();
        if let Some(duration) = &duration {
            expected.extend(["--duration", duration]);
        }
        expected.push("--size-limit");
        let limit = MAX_ARTIFACT.to_string();
        let output = self.root.join("remote/perf.data");
        expected.extend([&limit, "-o", output.to_str().unwrap()]);
        let actual = fs::read_to_string(self.root.join("recorder-argv")).unwrap();
        assert_eq!(
            actual.split_terminator('\0').collect::<Vec<_>>(),
            expected,
            "executed arguments for {sampling:?}"
        );
    }

    fn wait_for(&self, name: &str) -> bool {
        let deadline = Instant::now() + Duration::from_secs(2);
        while !self.root.join(name).exists() && Instant::now() < deadline {
            thread::sleep(Duration::from_millis(10));
        }
        self.root.join(name).exists()
    }

    fn assert_cleaned(&self) {
        assert!(!self.root.join("remote").exists());
        assert_eq!(
            fs::read(self.root.join("unrelated/perf.data")).unwrap(),
            b"retain"
        );
    }
}

impl Drop for FakeAdb {
    fn drop(&mut self) {
        // Unblock only this fixture's writer even when a regression panics.
        let _ = fs::write(self.root.join("remote/record.stop"), b"");
        let _ = fs::write(self.root.join("allow-pull"), b"");
        let _ = fs::write(self.root.join("release-identity"), b"");
        if self.root.join("remote/record.pid").exists() {
            self.wait_for("remote/record.exit");
        }
        let _ = fs::remove_dir_all(&self.root);
    }
}

fn wait_until_recording(handle: &CaptureHandle) {
    let deadline = Instant::now() + Duration::from_secs(5);
    while !handle.can_stop() {
        assert!(
            Instant::now() < deadline,
            "recorder never became stoppable: {:?}",
            handle.state()
        );
        thread::sleep(Duration::from_millis(10));
    }
}

#[test]
fn stop_during_inflight_identity_transport_failure_still_requires_owned_exit_receipt() {
    let fake = FakeAdb::new("identity-block");
    let capture = fake.capture();
    let handle = capture.handle();
    let controller = handle.clone();
    let root = fake.root.clone();
    let stopper = thread::spawn(move || {
        let deadline = Instant::now() + Duration::from_secs(5);
        while !root.join("identity-waiting").exists() {
            assert!(Instant::now() < deadline);
            thread::sleep(Duration::from_millis(10));
        }
        assert!(controller.stop());
        fs::write(root.join("release-identity"), b"").unwrap();
    });
    let output = capture.run().unwrap();
    stopper.join().unwrap();
    assert_eq!(output.completion, CaptureCompletion::StopRequested);
    assert_eq!(handle.state(), CaptureState::Complete);
    assert!(fake.root.join("recorder-eof").exists());
    fake.assert_cleaned();
}

#[test]
fn stop_cannot_promote_invalid_recorder_identity_into_a_successful_capture() {
    let fake = FakeAdb::new("identity-invalid");
    let capture = fake.capture();
    let handle = capture.handle();
    let controller = handle.clone();
    let root = fake.root.clone();
    let stopper = thread::spawn(move || {
        let deadline = Instant::now() + Duration::from_secs(5);
        while !root.join("identity-waiting").exists() {
            assert!(Instant::now() < deadline);
            thread::sleep(Duration::from_millis(10));
        }
        assert!(controller.stop());
        fs::write(root.join("release-identity"), b"").unwrap();
    });
    let error = capture.run().unwrap_err();
    stopper.join().unwrap();
    assert!(error.contains("rebooted"), "{error}");
    assert_eq!(handle.state(), CaptureState::Failed);
    assert!(!fake.root.join("local/owned").exists());
    fake.assert_cleaned();
}

#[test]
fn graceful_stop_preserves_output_and_terminal_controls_cannot_change_completion() {
    let fake = FakeAdb::new("eof");
    let mut plan = fake.plan(CallGraphMode::Dwarf);
    plan.request.duration = CaptureDuration::Timed(Duration::from_secs(20));
    let capture = plan.confirm("device", 42, "boot", 7).unwrap();
    let handle = capture.handle();
    assert!(!handle.stop());
    let stopper_handle = handle.clone();
    let stopper = thread::spawn(move || {
        wait_until_recording(&stopper_handle);
        assert!(stopper_handle.stop());
        assert!(!stopper_handle.stop());
    });
    let output = capture.run().unwrap();
    stopper.join().unwrap();
    assert_eq!(output.completion, CaptureCompletion::StopRequested);
    assert_eq!(
        fs::read(output.raw.path).unwrap(),
        valid_perf_data_fixture()
    );
    assert_eq!(handle.state(), CaptureState::Complete);
    assert!(!handle.stop());
    assert!(!handle.cancel());
    assert_eq!(handle.state(), CaptureState::Complete);
    fake.assert_duration_argv(
        &SamplingConfiguration::default(),
        CaptureDuration::Timed(Duration::from_secs(20)),
    );
    fake.assert_cleaned();
}

#[test]
fn manual_recording_keeps_renewing_its_lease_until_explicit_stop_and_save() {
    let fake = FakeAdb::new("manual");
    let mut plan = fake.plan(CallGraphMode::Dwarf);
    plan.request.duration = CaptureDuration::Manual;
    let capture = plan.confirm("device", 42, "boot", 7).unwrap();
    let handle = capture.handle();
    let stopper_handle = handle.clone();
    let root = fake.root.clone();
    let stopper = thread::spawn(move || {
        wait_until_recording(&stopper_handle);
        thread::sleep(Duration::from_secs(u64::from(MANUAL_LEASE_SECONDS) + 2));
        assert!(!root.join("remote/record.lease.expired").exists());
        let sequence = fs::read_to_string(root.join("remote/record.lease")).unwrap();
        assert!(sequence.trim().parse::<u64>().unwrap() > 5);
        assert!(stopper_handle.stop());
    });
    let output = capture.run().unwrap();
    stopper.join().unwrap();
    assert_eq!(output.completion, CaptureCompletion::StopRequested);
    assert_eq!(handle.state(), CaptureState::Complete);
    assert_eq!(
        fs::read(output.raw.path).unwrap(),
        valid_perf_data_fixture()
    );
    fake.assert_duration_argv(&SamplingConfiguration::default(), CaptureDuration::Manual);
    fake.assert_cleaned();
}

#[test]
fn abandoned_manual_recorder_exits_on_lease_loss_and_cannot_publish_success() {
    let fake = FakeAdb::new("manual");
    let mut plan = fake.plan(CallGraphMode::Dwarf);
    plan.request.duration = CaptureDuration::Manual;
    let capture = plan.confirm("device", 42, "boot", 7).unwrap();
    fs::create_dir(&capture.plan.remote_directory).unwrap();
    capture.refresh_manual_lease(0).unwrap();
    let mut remote = RemoteRecording::new(&capture);
    remote.launched = true;
    let path = format!("{}/perf.data", capture.plan.remote_directory);
    let pid = capture.start_remote_recording("42", &path).unwrap();
    let deadline = Instant::now() + Duration::from_secs(u64::from(MANUAL_LEASE_SECONDS) + 10);
    while !fake.root.join("remote/record.exit").exists() {
        assert!(
            Instant::now() < deadline,
            "abandoned manual recorder outlived its lease"
        );
        thread::sleep(Duration::from_millis(100));
    }
    assert!(fake.root.join("remote/record.lease.expired").exists());
    assert!(fake.root.join("recorder-eof").exists());
    let error = capture.await_remote_recording(pid, &path).unwrap_err();
    assert!(error.contains("lease expired"), "{error}");
    assert!(!fake.root.join("local/owned").exists());
    remote.cleanup().unwrap();
    fake.assert_cleaned();
}

#[test]
fn cancellation_during_pull_wins_after_graceful_stop_and_discards_output() {
    let fake = FakeAdb::new("delayed-pull");
    let capture = fake.capture();
    let handle = capture.handle();
    let controller = handle.clone();
    let root = fake.root.clone();
    let canceller = thread::spawn(move || {
        wait_until_recording(&controller);
        assert!(controller.stop());
        let deadline = Instant::now() + Duration::from_secs(5);
        while !root.join("pull-ready").exists() {
            assert!(Instant::now() < deadline, "pull did not start after stop");
            thread::sleep(Duration::from_millis(10));
        }
        assert!(controller.cancel());
        fs::write(root.join("allow-pull"), b"").unwrap();
    });
    assert!(capture.run().is_err());
    canceller.join().unwrap();
    assert_eq!(handle.state(), CaptureState::Cancelled);
    assert!(!handle.stop());
    assert!(!handle.cancel());
    assert!(!fake.root.join("local/owned").exists());
    fake.assert_cleaned();
}

#[test]
fn unverified_graceful_stop_retains_recovery_paths_without_publishing_output() {
    let fake = FakeAdb::new("eof");
    let capture = fake.capture();
    let handle = capture.handle();
    let controller = handle.clone();
    let root = fake.root.clone();
    let stopper = thread::spawn(move || {
        wait_until_recording(&controller);
        fs::write(root.join("stop-fails"), b"").unwrap();
        assert!(controller.stop());
    });
    let error = capture.run().unwrap_err();
    stopper.join().unwrap();
    assert!(error.contains("retained"), "{error}");
    assert_eq!(handle.state(), CaptureState::StopUnverified);
    assert!(fake.root.join("remote").is_dir());
    assert!(!fake.root.join("local/owned").exists());
    assert_eq!(
        fs::read(fake.root.join("unrelated/perf.data")).unwrap(),
        b"retain"
    );
    fs::remove_file(fake.root.join("stop-fails")).unwrap();
    fs::write(fake.root.join("remote/record.stop"), b"").unwrap();
    assert!(fake.wait_for("remote/record.exit"));
}

#[test]
fn fractional_duration_reaches_the_actual_recorder_without_integer_rounding() {
    let fake = FakeAdb::new("success");
    let mut plan = fake.plan(CallGraphMode::Dwarf);
    let duration = CaptureDuration::parse("0.25").unwrap();
    plan.request.duration = duration;
    let capture = plan.confirm("device", 42, "boot", 7).unwrap();
    let output = capture.run().unwrap();
    assert_eq!(output.completion, CaptureCompletion::RecorderExited);
    fake.assert_duration_argv(&SamplingConfiguration::default(), duration);
    fake.assert_cleaned();
}

#[test]
fn launch_exec_detaches_and_recorder_exit_closes_writer_promptly() {
    let fake = FakeAdb::new("success");
    let capture = fake.capture();
    fs::create_dir(&capture.plan.remote_directory).unwrap();
    let mut remote = RemoteRecording::new(&capture);
    remote.launched = true;
    let started = Instant::now();
    let pid = capture
        .start_remote_recording(
            "42",
            &format!("{}/perf.data", capture.plan.remote_directory),
        )
        .unwrap();
    assert!(pid > 0);
    let crossed_exec = fake.wait_for("exec-boundary");
    let promptly_exited = fake.wait_for("remote/record.exit");
    let elapsed = started.elapsed();
    let status = capture.recorder_exit_code().unwrap();
    let writer_exited = fake.root.join("remote/record.writer.exit").exists();
    let cleanup = remote.cleanup();
    assert_eq!(fake.root.join("launch-stderr").metadata().unwrap().len(), 0);
    assert!(
        crossed_exec,
        "detached worker never crossed an exec boundary"
    );
    assert!(
        promptly_exited,
        "pipeline waited for the stdin writer's full safety timeout"
    );
    assert!(elapsed < Duration::from_secs(3), "exit took {elapsed:?}");
    assert_eq!(status, Some(0));
    assert!(
        writer_exited,
        "recorder exit published before writer acknowledgement"
    );
    cleanup.unwrap();
    fake.assert_cleaned();
}

#[test]
fn successful_fake_capture_pulls_artifact_and_cleans_exact_remote_directory() {
    for mode in CallGraphMode::ALL {
        let fake = FakeAdb::new("success");
        let capture = fake.capture_with_mode(mode);
        let handle = capture.handle();
        let output = capture.run().unwrap();
        assert_eq!(handle.state(), CaptureState::Complete);
        assert_eq!(
            fs::read(output.raw.path).unwrap(),
            valid_perf_data_fixture()
        );
        assert_eq!(output.retained_remote_path, None);
        fake.assert_recorder_argv(mode);
        fake.assert_cleaned();
    }
}

#[test]
fn prepared_and_confirmed_requests_keep_their_mode_when_the_next_draft_changes() {
    let fake = FakeAdb::new("success");
    let mut draft = CallGraphMode::FramePointer;
    let plan = fake.plan(draft);
    draft = CallGraphMode::None;
    assert_eq!(
        plan.request().sampling.call_graph,
        CallGraphMode::FramePointer
    );
    let capture = plan.confirm("device", 42, "boot", 7).unwrap();
    assert_eq!(
        capture.plan.request.sampling.call_graph,
        CallGraphMode::FramePointer
    );
    capture.run().unwrap();
    fake.assert_recorder_argv(CallGraphMode::FramePointer);
    fake.assert_cleaned();

    let next = FakeAdb::new("success");
    let next_plan = next.plan(draft);
    assert_eq!(next_plan.request().sampling.call_graph, CallGraphMode::None);
    next_plan
        .confirm("device", 42, "boot", 7)
        .unwrap()
        .run()
        .unwrap();
    next.assert_recorder_argv(CallGraphMode::None);
    next.assert_cleaned();
}

#[test]
fn event_scope_and_frequency_or_period_reach_the_actual_recorder() {
    for event in ["cpu-clock", "cpu-cycles"] {
        for (index, scope) in EventScope::ALL.into_iter().enumerate() {
            for rate in [SamplingRate::Frequency(1000), SamplingRate::Period(4096)] {
                let fake = FakeAdb::new("success");
                let sampling = SamplingConfiguration {
                    event: event.into(),
                    rate,
                    scope,
                    call_graph: CallGraphMode::ALL[index],
                };
                let capture = fake
                    .plan_with_configuration(sampling.clone())
                    .confirm("device", 42, "boot", 7)
                    .unwrap();
                capture.run().unwrap();
                fake.assert_sampling_argv(&sampling);
                fake.assert_cleaned();
            }
        }
    }
}

#[test]
fn templates_reach_execution_and_later_draft_edits_cannot_mutate_frozen_parameters() {
    for template in SamplingTemplate::ALL {
        let fake = FakeAdb::new("success");
        let mut draft = template.create(true);
        let expected = draft.clone();
        let plan = fake.plan_with_configuration(draft.clone());
        draft.event = "instructions".into();
        draft.rate = SamplingRate::Period(2048);
        draft.scope = EventScope::Kernel;
        draft.call_graph = CallGraphMode::None;
        assert_eq!(plan.request().sampling, expected);
        plan.confirm("device", 42, "boot", 7)
            .unwrap()
            .run()
            .unwrap();
        fake.assert_sampling_argv(&expected);
        fake.assert_cleaned();
        let next = FakeAdb::new("success");
        let next_plan = next.plan_with_configuration(draft.clone());
        assert_eq!(next_plan.request().sampling, draft);
        next_plan
            .confirm("device", 42, "boot", 7)
            .unwrap()
            .run()
            .unwrap();
        next.assert_sampling_argv(&draft);
        next.assert_cleaned();
    }
}

#[test]
fn cancellation_closes_owned_stdin_without_killing_or_pulling() {
    for mode in CallGraphMode::ALL {
        let fake = FakeAdb::new("eof");
        let capture = fake.capture_with_mode(mode);
        let handle = capture.handle();
        let cancel = handle.clone();
        let ready = fake.root.join("recorder-ready");
        let eof = fake.root.join("recorder-eof");
        let canceller = thread::spawn(move || {
            let deadline = Instant::now() + Duration::from_secs(3);
            while !ready.exists() && Instant::now() < deadline {
                thread::sleep(Duration::from_millis(10));
            }
            thread::sleep(Duration::from_millis(100));
            let premature_eof = eof.exists();
            cancel.cancel();
            premature_eof
        });
        let result = capture.run();
        let premature_eof = canceller.join().unwrap();
        assert!(result.is_err());
        assert_eq!(handle.state(), CaptureState::Cancelled, "{result:?}");
        assert!(
            !premature_eof,
            "recorder got null stdin before cancellation"
        );
        assert!(
            fake.root.join("recorder-eof").exists(),
            "recorder didn't receive owned-pipe EOF"
        );
        assert!(!fake.root.join("local/owned").exists());
        fake.assert_recorder_argv(mode);
        fake.assert_cleaned();
    }
}

#[test]
fn recorder_failure_preserves_exit_status_and_does_not_import() {
    for scope in EventScope::ALL {
        for mode in CallGraphMode::ALL {
            let fake = FakeAdb::new("failure");
            let sampling = SamplingConfiguration {
                scope,
                call_graph: mode,
                ..SamplingConfiguration::default()
            };
            let capture = fake
                .plan_with_configuration(sampling.clone())
                .confirm("device", 42, "boot", 7)
                .unwrap();
            let handle = capture.handle();
            let error = capture.run().unwrap_err();
            assert!(error.contains("exit 23"), "{error}");
            assert!(error.contains("recorder-failed"), "{error}");
            assert!(error.contains(&format!("scope {scope:?}")), "{error}");
            assert!(!error.contains("No kernel sampling"), "{error}");
            assert!(error.contains("No root or permission changes"), "{error}");
            assert_eq!(handle.state(), CaptureState::Failed);
            assert!(!fake.root.join("local/owned").exists());
            fake.assert_sampling_argv(&sampling);
            fake.assert_cleaned();
        }
    }
}

#[test]
fn lost_launch_frame_still_stops_owned_worker_and_cleans_without_pid_signals() {
    for mode in CallGraphMode::ALL {
        let fake = FakeAdb::new("launch-failure");
        let capture = fake.capture_with_mode(mode);
        let handle = capture.handle();
        let error = capture.run().unwrap_err();
        assert!(error.contains("framed launch identity"), "{error}");
        assert_eq!(handle.state(), CaptureState::Failed);
        assert!(fake.root.join("recorder-eof").exists());
        assert!(!fake.root.join("local/owned").exists());
        fake.assert_recorder_argv(mode);
        fake.assert_cleaned();
    }
}

#[test]
fn unverified_stop_retains_exact_remote_directory() {
    for mode in CallGraphMode::ALL {
        let fake = FakeAdb::new("success");
        let capture = fake.capture_with_mode(mode);
        fs::create_dir(&capture.plan.remote_directory).unwrap();
        fs::write(fake.root.join("remote/perf.data"), b"partial").unwrap();
        fs::write(fake.root.join("stop-fails"), b"").unwrap();
        let mut remote = RemoteRecording::new(&capture);
        remote.launched = true;
        let error = remote.cleanup().unwrap_err();
        assert!(error.contains(&capture.plan.remote_directory), "{error}");
        assert_eq!(capture.handle.state(), CaptureState::CancellationUnverified);
        assert_eq!(
            fs::read(fake.root.join("remote/perf.data")).unwrap(),
            b"partial"
        );
        assert_eq!(
            fs::read(fake.root.join("unrelated/perf.data")).unwrap(),
            b"retain"
        );
    }
}

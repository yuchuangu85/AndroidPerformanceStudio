use super::*;
use std::cell::{Cell, RefCell};

struct TestRoot(PathBuf);
impl TestRoot {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!(
            "aps-method-test-{}-{}",
            std::process::id(),
            NEXT_CAPTURE.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&path).unwrap();
        Self(path)
    }
}
impl Drop for TestRoot {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

struct FakeTransport<'a> {
    control: &'a CaptureControl,
    commands: RefCell<Vec<Vec<String>>>,
    online: bool,
    sdk: Option<u32>,
    start_failure: Option<bool>,
    stop_failure: bool,
    cleanup_failure: bool,
    cancel_after_start: bool,
    change_identity: bool,
    identity_reads: Cell<usize>,
    size: u64,
    pull_bytes: Vec<u8>,
}
impl<'a> FakeTransport<'a> {
    fn new(control: &'a CaptureControl) -> Self {
        Self {
            control,
            commands: RefCell::new(Vec::new()),
            online: true,
            sdk: Some(33),
            start_failure: None,
            stop_failure: false,
            cleanup_failure: false,
            cancel_after_start: false,
            change_identity: false,
            identity_reads: Cell::new(0),
            size: 5,
            pull_bytes: b"trace".to_vec(),
        }
    }
    fn failure(uncertain: bool) -> CommandFailure {
        CommandFailure {
            message: "test command failure".into(),
            uncertain,
        }
    }
    fn record(&self, args: &[&str]) {
        self.commands
            .borrow_mut()
            .push(args.iter().map(|arg| (*arg).to_owned()).collect());
    }
}

fn target() -> CaptureTarget {
    CaptureTarget {
        serial: "selected-device".into(),
        process: AndroidApplicationProcess {
            pid: 42,
            parent_pid: 1,
            user: "u0_a42".into(),
            name: "com.example".into(),
            package_name: "com.example".into(),
        },
    }
}

impl Transport for FakeTransport<'_> {
    fn online(&self, serial: &str, cancel: &CancellationToken) -> Result<bool, String> {
        assert_eq!(serial, "selected-device");
        assert!(!cancel.is_cancelled());
        Ok(self.online)
    }
    fn sdk(&self, _: &str, _: &CancellationToken) -> Result<Option<u32>, String> {
        Ok(self.sdk)
    }
    fn identity(&self, _: &CaptureTarget, cancel: &CancellationToken) -> Result<Identity, String> {
        assert!(!cancel.is_cancelled());
        let reads = self.identity_reads.get();
        self.identity_reads.set(reads + 1);
        Ok(Identity {
            process: AndroidProcess {
                pid: 42,
                parent_pid: 1,
                user: "u0_a42".into(),
                name: "com.example".into(),
            },
            boot: "boot".into(),
            start_ticks: if self.change_identity && reads > 0 {
                2
            } else {
                1
            },
        })
    }
    fn shell(
        &self,
        serial: &str,
        args: &[&str],
        cancel: &CancellationToken,
    ) -> Result<String, CommandFailure> {
        assert_eq!(serial, "selected-device");
        assert!(
            !cancel.is_cancelled(),
            "stop and cleanup require independent cancellation tokens"
        );
        self.record(args);
        match args {
            ["am", "profile", "start", "42", _] => {
                if self.cancel_after_start {
                    self.control.cancel.cancel();
                } else {
                    self.control.request_stop();
                }
                if let Some(uncertain) = self.start_failure {
                    return Err(Self::failure(uncertain));
                }
            }
            ["am", "profile", "stop", "42"] if self.stop_failure => {
                return Err(Self::failure(true));
            }
            ["rm", "-f", _] if self.cleanup_failure => return Err(Self::failure(false)),
            ["stat", "-c", "%s", _] => return Ok(self.size.to_string()),
            _ => {}
        }
        Ok(String::new())
    }
    fn pull(
        &self,
        serial: &str,
        remote: &str,
        local: &Path,
        cancel: &CancellationToken,
    ) -> Result<(), CommandFailure> {
        assert_eq!(serial, "selected-device");
        assert!(!cancel.is_cancelled());
        self.record(&["pull", remote]);
        fs::write(local, &self.pull_bytes).unwrap();
        Ok(())
    }
}

#[::core::prelude::v1::test]
fn successful_early_stop_pulls_verified_trace_then_cleans_only_owned_path() {
    let root = TestRoot::new();
    let control = CaptureControl::default();
    let transport = FakeTransport::new(&control);
    let started = Instant::now();
    let report = capture_with(&transport, &root.0, &target(), 300, &control);
    let path = report.result.unwrap();
    assert!(started.elapsed() < Duration::from_secs(1));
    assert_eq!(fs::read(&path).unwrap(), b"trace");
    assert_eq!(path.extension().unwrap(), "trace");
    assert!(report.cleanup_warning.is_none());
    let commands = transport.commands.borrow();
    let start = commands
        .iter()
        .position(|args| args.starts_with(&["am".into(), "profile".into(), "start".into()]))
        .unwrap();
    let stop = commands
        .iter()
        .position(|args| args == &["am", "profile", "stop", "42"])
        .unwrap();
    let pull = commands.iter().position(|args| args[0] == "pull").unwrap();
    let rm = commands.iter().position(|args| args[0] == "rm").unwrap();
    assert!(start < stop && stop < pull && pull < rm);
    let owned = &commands[start][4];
    assert!(owned.starts_with("/data/local/tmp/aps-method-"));
    assert_eq!(commands[rm], ["rm", "-f", owned]);
    assert!(
        commands
            .iter()
            .all(|args| !args.iter().any(|arg| arg == "-rf" || arg.contains('*')))
    );
}

#[::core::prelude::v1::test]
fn cancellation_still_stops_and_cleans_without_pulling_or_publishing() {
    let root = TestRoot::new();
    let control = CaptureControl::default();
    let mut transport = FakeTransport::new(&control);
    transport.cancel_after_start = true;
    let report = capture_with(&transport, &root.0, &target(), 10, &control);
    assert!(matches!(report.result, Err(CaptureError::Cancelled)));
    let commands = transport.commands.borrow();
    assert!(
        commands
            .iter()
            .any(|args| args == &["am", "profile", "stop", "42"])
    );
    assert!(commands.iter().any(|args| args[0] == "rm"));
    assert!(!commands.iter().any(|args| args[0] == "pull"));
    assert_eq!(fs::read_dir(&root.0).unwrap().count(), 0);
}

#[::core::prelude::v1::test]
fn unsupported_unknown_api_offline_and_precancelled_targets_never_mutate_device() {
    let root = TestRoot::new();
    for (online, sdk) in [(false, Some(33)), (true, Some(19)), (true, None)] {
        let control = CaptureControl::default();
        let mut transport = FakeTransport::new(&control);
        transport.online = online;
        transport.sdk = sdk;
        assert!(
            capture_with(&transport, &root.0, &target(), 10, &control)
                .result
                .is_err()
        );
        assert!(transport.commands.borrow().is_empty());
    }
    let control = CaptureControl::default();
    control.cancel.cancel();
    let transport = FakeTransport::new(&control);
    assert!(matches!(
        capture_with(&transport, &root.0, &target(), 10, &control).result,
        Err(CaptureError::Cancelled)
    ));
    assert!(transport.commands.borrow().is_empty());
}

#[::core::prelude::v1::test]
fn uncertain_start_is_stopped_but_never_reported_as_success() {
    let root = TestRoot::new();
    for uncertain in [false, true] {
        let control = CaptureControl::default();
        let mut transport = FakeTransport::new(&control);
        transport.start_failure = Some(uncertain);
        assert!(
            capture_with(&transport, &root.0, &target(), 10, &control)
                .result
                .is_err()
        );
        let commands = transport.commands.borrow();
        assert_eq!(
            commands
                .iter()
                .any(|args| args == &["am", "profile", "stop", "42"]),
            uncertain
        );
        assert!(!commands.iter().any(|args| args[0] == "pull"));
        assert!(commands.iter().any(|args| args[0] == "rm"));
    }
}

#[::core::prelude::v1::test]
fn unconfirmed_stop_or_replaced_pid_retains_owned_artifact() {
    let root = TestRoot::new();
    for replaced in [false, true] {
        let control = CaptureControl::default();
        let mut transport = FakeTransport::new(&control);
        transport.stop_failure = !replaced;
        transport.change_identity = replaced;
        let report = capture_with(&transport, &root.0, &target(), 10, &control);
        assert!(report.result.is_err());
        assert!(
            report
                .cleanup_warning
                .unwrap()
                .starts_with("/data/local/tmp/aps-method-")
        );
        let commands = transport.commands.borrow();
        assert!(
            !commands
                .iter()
                .any(|args| matches!(args[0].as_str(), "rm" | "rmdir" | "pull"))
        );
        if replaced {
            assert!(
                !commands
                    .iter()
                    .any(|args| args == &["am", "profile", "stop", "42"])
            );
        }
    }
}

#[::core::prelude::v1::test]
fn size_budget_and_pull_mismatch_fail_without_leaving_partial_local_files() {
    let root = TestRoot::new();
    for size in [
        1,
        aps_method_recording::ImportLimits::default().max_bytes + 1,
    ] {
        let control = CaptureControl::default();
        let mut transport = FakeTransport::new(&control);
        transport.size = size;
        assert!(
            capture_with(&transport, &root.0, &target(), 10, &control)
                .result
                .is_err()
        );
        assert_eq!(fs::read_dir(&root.0).unwrap().count(), 0);
        if size > 1 {
            assert!(
                !transport
                    .commands
                    .borrow()
                    .iter()
                    .any(|args| args[0] == "pull")
            );
        }
    }
}

#[::core::prelude::v1::test]
fn cleanup_failure_preserves_success_with_owned_path_warning() {
    let root = TestRoot::new();
    let control = CaptureControl::default();
    let mut transport = FakeTransport::new(&control);
    transport.cleanup_failure = true;
    let report = capture_with(&transport, &root.0, &target(), 10, &control);
    assert!(report.result.is_ok());
    assert!(report.cleanup_warning.is_some());
}

#[::core::prelude::v1::test]
fn proc_identity_and_success_exit_error_text_are_not_assumed_valid() {
    let stat = "42 (com.example (worker)) S 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20 21";
    assert_eq!(start_ticks(stat, 42).unwrap(), 19);
    assert!(start_ticks(stat, 43).is_err());
    assert!(reports_failure(b"Error: Process is not debuggable"));
    assert!(reports_failure(b"java.lang.SecurityException: denied"));
    assert!(!reports_failure(b"Profiler started"));
}

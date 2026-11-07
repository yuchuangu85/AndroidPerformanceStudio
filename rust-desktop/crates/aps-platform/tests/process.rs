//! Deferred test suite. Do not run until the migration implementation is complete.
use aps_platform::{CancellationToken, ProcessError, ProcessRequest, ProcessRunner};
use std::time::Duration;

#[test]
fn cancellation_before_start_does_not_launch_a_program() {
    let token = CancellationToken::new();
    token.cancel();
    let request = ProcessRequest::new("this-program-must-not-be-started");
    assert!(matches!(
        ProcessRunner.run(&request, &token),
        Err(ProcessError::Cancelled)
    ));
}

#[test]
fn start_errors_do_not_reveal_program_or_arguments() {
    let request =
        ProcessRequest::new("aps-nonexistent-secret-program-4c29").arg("sk-secret-do-not-log");
    let error = ProcessRunner
        .run(&request, &CancellationToken::new())
        .unwrap_err();
    assert!(!format!("{error:?} {error}").contains("secret"));
}

#[test]
fn invalid_timeout_is_rejected_before_start() {
    let mut request = ProcessRequest::new("must-not-run");
    request.timeout = Duration::ZERO;
    assert!(matches!(
        ProcessRunner.run(&request, &CancellationToken::new()),
        Err(ProcessError::InvalidRequest(_))
    ));
}

#[cfg(unix)]
mod unix {
    use super::*;
    use aps_platform::Stream;
    use std::thread;
    use std::time::Instant;

    fn shell(script: &str) -> ProcessRequest {
        // A shell is explicitly the fixture executable, never inserted by the runner.
        ProcessRequest::new("/bin/sh").args(["-c", script])
    }

    #[test]
    fn preserves_stdout_stderr_and_nonzero_status_separately() {
        let output = ProcessRunner
            .run(
                &shell("printf out; printf err >&2; exit 7"),
                &CancellationToken::new(),
            )
            .unwrap();
        assert_eq!(output.stdout, b"out");
        assert_eq!(output.stderr, b"err");
        assert_eq!(output.status.code(), Some(7));
    }

    #[test]
    fn argument_is_not_interpreted_as_shell_source() {
        let request = ProcessRequest::new("/usr/bin/printf").args(["%s", "$(echo injected); true"]);
        let output = ProcessRunner
            .run(&request, &CancellationToken::new())
            .unwrap();
        assert_eq!(output.stdout, b"$(echo injected); true");
    }

    #[test]
    fn independently_limits_both_output_streams() {
        let mut request = shell("while :; do printf abcdefghijklmnopqrstuvwxyz; done");
        request.stdout_limit = 1024;
        assert!(matches!(
            ProcessRunner.run(&request, &CancellationToken::new()),
            Err(ProcessError::OutputLimit(Stream::Stdout))
        ));
        let mut request = shell("while :; do printf abcdefghijklmnopqrstuvwxyz >&2; done");
        request.stderr_limit = 1024;
        assert!(matches!(
            ProcessRunner.run(&request, &CancellationToken::new()),
            Err(ProcessError::OutputLimit(Stream::Stderr))
        ));
    }

    #[test]
    fn cancellation_terminates_a_running_child() {
        let token = CancellationToken::new();
        let cancel = token.clone();
        let thread = thread::spawn(move || {
            thread::sleep(Duration::from_millis(30));
            cancel.cancel();
        });
        let start = Instant::now();
        let result = ProcessRunner.run(&shell("sleep 60"), &token);
        thread.join().unwrap();
        assert!(matches!(result, Err(ProcessError::Cancelled)));
        assert!(start.elapsed() < Duration::from_secs(5));
    }

    #[test]
    fn timeout_does_not_wait_for_the_requested_child_duration() {
        let mut request = shell("sleep 60");
        request.timeout = Duration::from_millis(50);
        let start = Instant::now();
        assert!(matches!(
            ProcessRunner.run(&request, &CancellationToken::new()),
            Err(ProcessError::Timeout)
        ));
        assert!(start.elapsed() < Duration::from_secs(5));
    }

    #[test]
    fn concurrent_streams_do_not_deadlock_at_pipe_capacity() {
        let mut request = shell(
            "i=0; while [ $i -lt 3000 ]; do printf 1234567890; printf abcdefghij >&2; i=$((i+1)); done",
        );
        request.timeout = Duration::from_secs(10);
        let output = ProcessRunner
            .run(&request, &CancellationToken::new())
            .unwrap();
        assert!(output.status.success());
        assert_eq!(output.stdout.len(), 30_000);
        assert_eq!(output.stderr.len(), 30_000);
    }
}

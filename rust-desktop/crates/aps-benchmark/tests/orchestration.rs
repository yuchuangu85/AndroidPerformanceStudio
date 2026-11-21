//! Ports of `MacrobenchmarkCommandTest.kt`, `MacrobenchmarkProcessRunnerTest.kt`
//! and `BenchmarkOrchestrationTest.kt` (plan validation + result locator).
mod common;

use std::path::{Path, PathBuf};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::{Duration, SystemTime};

use aps_benchmark::{
    BenchmarkScenario, CompilationMode, MacrobenchmarkCommand, MacrobenchmarkPlan, StartupMode,
    build_command, locate_benchmark_result, run_macrobenchmark,
};
use common::{temp_dir, write_text};

fn write_wrapper(root: &Path, body: &str) {
    let path = root.join("gradlew");
    write_text(&path, body);
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let mut permissions = std::fs::metadata(&path).unwrap().permissions();
        permissions.set_mode(0o755);
        std::fs::set_permissions(&path, permissions).unwrap();
    }
}

fn plan(root: &Path) -> MacrobenchmarkPlan {
    MacrobenchmarkPlan {
        project_root: root.to_path_buf(),
        ..MacrobenchmarkPlan::default()
    }
}

#[test]
fn builds_reproducible_connected_benchmark_command() {
    let root = temp_dir("orchestration-command");
    write_wrapper(&root, "#!/bin/sh\n");
    let command = build_command(&MacrobenchmarkPlan {
        project_root: root.clone(),
        device_serial: Some("device-serial".into()),
        gradle_task: ":macrobenchmark:connectedCheck".to_string(),
        benchmark_filter: Some("com.example.Benchmark#startup".to_string()),
        compilation_mode: CompilationMode::SpeedProfile,
        startup_mode: StartupMode::Warm,
        scenario: BenchmarkScenario::Scroll,
        warmups: 3,
        iterations: 7,
        trace_directory: Some(root.join("traces")),
    })
    .unwrap();

    assert_eq!(command.working_directory, root);
    assert_eq!(
        command
            .environment
            .get("ANDROID_SERIAL")
            .map(String::as_str),
        Some("device-serial")
    );
    assert!(
        command
            .arguments
            .iter()
            .any(|arg| arg == "-Pandroid.injected.device.serial=device-serial")
    );
    for expected in [
        ":macrobenchmark:connectedCheck",
        "--no-daemon",
        "-Pandroidx.benchmark.output.enable=true",
        "-Paps.macrobenchmark.compilationMode=speed_profile",
        "-Paps.macrobenchmark.startupMode=warm",
        "-Paps.macrobenchmark.scenario=scroll",
        "-Paps.macrobenchmark.warmups=3",
        "-Paps.macrobenchmark.iterations=7",
        "-Pandroid.testInstrumentationRunnerArguments.class=com.example.Benchmark#startup",
    ] {
        assert!(
            command
                .arguments
                .iter()
                .any(|argument| argument == expected),
            "missing {expected:?} in {:?}",
            command.arguments
        );
    }
    assert!(
        command
            .arguments
            .iter()
            .any(|argument| argument.starts_with("-Paps.macrobenchmark.traceDirectory="))
    );
    assert!(
        command
            .command_line()
            .contains("com.example.Benchmark#startup")
    );
    assert_eq!(
        command
            .environment
            .get("APS_MACROBENCHMARK_COMPILATION_MODE")
            .map(String::as_str),
        Some("speed_profile")
    );
    assert_eq!(
        command
            .environment
            .get("APS_MACROBENCHMARK_SCENARIO")
            .map(String::as_str),
        Some("scroll")
    );
    let trace_directory = command
        .environment
        .get("APS_MACROBENCHMARK_TRACE_DIR")
        .expect("trace directory");
    assert!(Path::new(trace_directory).is_absolute());
    assert!(trace_directory.ends_with("traces"));
}

#[test]
fn command_line_quotes_values_with_whitespace() {
    let command = MacrobenchmarkCommand {
        working_directory: PathBuf::from("/tmp"),
        executable: PathBuf::from("/tmp/my gradle"),
        arguments: vec!["--task".to_string(), "a b".to_string()],
        environment: Default::default(),
    };
    assert_eq!(command.command_line(), "\"/tmp/my gradle\" --task \"a b\"");
}

#[test]
fn validates_the_plan_before_building_a_command() {
    let missing = temp_dir("orchestration-missing").join("absent");
    assert!(build_command(&plan(&missing)).is_err());

    let root = temp_dir("orchestration-invalid");
    write_wrapper(&root, "#!/bin/sh\n");
    let mut invalid = plan(&root);
    invalid.gradle_task = "macrobenchmark:connectedCheck".to_string();
    assert!(build_command(&invalid).is_err());

    let mut invalid = plan(&root);
    invalid.warmups = 101;
    assert!(build_command(&invalid).is_err());

    let mut invalid = plan(&root);
    invalid.iterations = 0;
    assert!(build_command(&invalid).is_err());

    let without_wrapper = temp_dir("orchestration-no-wrapper");
    assert!(build_command(&plan(&without_wrapper)).is_err());
}

#[cfg(unix)]
#[test]
fn enforces_timeout_while_process_is_still_running() {
    let root = temp_dir("orchestration-timeout");
    write_wrapper(&root, "#!/bin/sh\necho started\nsleep 3\n");
    let command = build_command(&plan(&root)).unwrap();
    let cancel = Arc::new(AtomicBool::new(false));
    let mut streamed = String::new();
    let result = run_macrobenchmark(&command, Duration::from_secs(1), cancel, &mut |chunk| {
        streamed.push_str(chunk)
    });
    assert!(result.timed_out);
    assert!(!result.cancelled);
    assert!(!result.succeeded());
    assert_eq!(result.exit_code, -1);
    assert!(result.output.contains("started"));
    assert!(streamed.contains("started"));
}

#[cfg(unix)]
#[test]
fn stops_a_running_process_as_soon_as_the_caller_cancels() {
    let root = temp_dir("orchestration-cancel");
    write_wrapper(&root, "#!/bin/sh\necho started\nsleep 3\n");
    let command = build_command(&plan(&root)).unwrap();
    let cancel = Arc::new(AtomicBool::new(false));
    let flag = Arc::clone(&cancel);
    let mut streamed = String::new();
    let result = run_macrobenchmark(&command, Duration::from_secs(30), cancel, &mut |chunk| {
        streamed.push_str(chunk);
        if chunk.contains("started") {
            flag.store(true, Ordering::Relaxed);
        }
    });
    assert!(result.cancelled);
    assert!(!result.timed_out);
    assert!(!result.succeeded());
    assert_eq!(result.exit_code, -1);
    assert!(result.output.contains("started"));
    assert!(streamed.contains("started"));
}

#[cfg(unix)]
#[test]
fn a_clean_exit_is_success_and_merges_stderr_into_the_output() {
    let root = temp_dir("orchestration-success");
    write_wrapper(&root, "#!/bin/sh\necho out\necho err >&2\nexit 0\n");
    let command = build_command(&plan(&root)).unwrap();
    let result = run_macrobenchmark(
        &command,
        Duration::from_secs(10),
        Arc::new(AtomicBool::new(false)),
        &mut |_| {},
    );
    assert!(result.succeeded());
    assert_eq!(result.exit_code, 0);
    assert!(result.output.contains("out"));
    assert!(result.output.contains("err"));
}

fn set_modified(path: &Path, seconds: u64) {
    let file = std::fs::File::options().write(true).open(path).unwrap();
    file.set_times(
        std::fs::FileTimes::new()
            .set_modified(SystemTime::UNIX_EPOCH + Duration::from_secs(seconds)),
    )
    .unwrap();
}

#[test]
fn an_explicit_result_path_must_exist_and_wins_over_discovery() {
    let root = temp_dir("orchestration-explicit");
    let discovered = root.join("module/build/outputs/benchmarkData.json");
    write_text(&discovered, "{}");
    assert_eq!(
        locate_benchmark_result(&root, Some("/tmp/aps-missing-result.json")),
        None
    );
    assert_eq!(
        locate_benchmark_result(&root, Some(discovered.to_str().unwrap())),
        Some(discovered)
    );
}

#[test]
fn discovery_picks_the_newest_benchmark_json_and_skips_unrelated_files() {
    let root = temp_dir("orchestration-discovery");
    let older = root.join("macrobenchmark/build/outputs/connected/benchmarkData.json");
    write_text(&older, "{}");
    set_modified(&older, 100);

    let newer =
        root.join("app/build/outputs/connected_android_test_additional_output/benchmarkData.json");
    write_text(&newer, "{}");
    set_modified(&newer, 200);

    let in_git = root.join(".git/benchmarkData.json");
    write_text(&in_git, "{}");
    set_modified(&in_git, 300);

    let unrelated = root.join("app/build/reports/report.json");
    write_text(&unrelated, "{}");
    set_modified(&unrelated, 400);

    assert_eq!(locate_benchmark_result(&root, None), Some(newer));
}

#[test]
fn discovery_is_bounded_to_depth_eight_and_returns_none_when_empty() {
    let empty = temp_dir("orchestration-empty");
    assert_eq!(locate_benchmark_result(&empty, None), None);

    let root = temp_dir("orchestration-depth");
    let shallow = root.join("a/b/c/benchmarkData.json");
    write_text(&shallow, "{}");
    set_modified(&shallow, 100);

    // Depth 9 (directory depth 8) must never be scanned, even when it is newer.
    let deep = root.join("a/b/c/d/e/f/g/h/benchmarkData.json");
    write_text(&deep, "{}");
    set_modified(&deep, 500);

    assert_eq!(locate_benchmark_result(&root, None), Some(shallow));
}

mod common;

use std::path::Path;
use std::process::Command;

use common::{temp_dir, write_text};

fn fixture(path: &Path, values: &[u64]) {
    let samples = values
        .iter()
        .map(u64::to_string)
        .collect::<Vec<_>>()
        .join(",");
    write_text(
        path,
        &format!(
            r#"{{
  "benchmarkDataVersion": 1,
  "benchmarkLibraryVersion": "1.4",
  "context": {{"deviceModel": "Pixel", "apiLevel": 35, "abi": "arm64-v8a"}},
  "build": {{"variant": "benchmark"}},
  "benchmarks": [{{
    "name": "dev.Bench.startup",
    "scenario": "startup",
    "compilationMode": "none",
    "startupMode": "cold",
    "metrics": {{"timeToInitialDisplayMs": {{"unit": "ms", "runs": [{samples}]}}}}
  }}]
}}"#
        ),
    );
}

fn run_cli(args: &[&str]) -> std::process::Output {
    Command::new(env!("CARGO_BIN_EXE_aps-benchmark"))
        .args(args)
        .output()
        .expect("run aps-benchmark")
}

#[test]
fn compare_writes_requested_reports_and_returns_one_for_a_regression() {
    let root = temp_dir("cli-compare-regression");
    let baseline = root.join("baseline.json");
    let current = root.join("current.json");
    fixture(&baseline, &[100, 101, 99, 100, 100]);
    fixture(&current, &[120, 121, 119, 120, 120]);
    let json = root.join("report.json");
    let junit = root.join("report.xml");
    let sarif = root.join("report.sarif");

    let output = run_cli(&[
        "compare",
        "--baseline",
        baseline.to_str().unwrap(),
        "--current",
        current.to_str().unwrap(),
        "--threshold-percent",
        "5",
        "--json",
        json.to_str().unwrap(),
        "--junit",
        junit.to_str().unwrap(),
        "--sarif",
        sarif.to_str().unwrap(),
    ]);

    assert_eq!(
        output.status.code(),
        Some(1),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let stdout = String::from_utf8_lossy(&output.stdout);
    assert!(
        stdout.contains("REGRESSED\tdev.Bench#startup\ttimeToInitialDisplayMs\t20"),
        "{stdout}"
    );
    assert!(
        std::fs::read_to_string(&json)
            .unwrap()
            .contains("REGRESSED")
    );
    assert!(
        std::fs::read_to_string(&junit)
            .unwrap()
            .contains("failures=\"1\"")
    );
    assert!(
        std::fs::read_to_string(&sarif)
            .unwrap()
            .contains("benchmark-regression")
    );
}

#[test]
fn compare_returns_zero_when_the_threshold_makes_the_change_stable() {
    let root = temp_dir("cli-compare-stable");
    let baseline = root.join("baseline.json");
    let current = root.join("current.json");
    fixture(&baseline, &[100, 101, 99, 100, 100]);
    fixture(&current, &[120, 121, 119, 120, 120]);

    let output = run_cli(&[
        "--baseline",
        baseline.to_str().unwrap(),
        "--current",
        current.to_str().unwrap(),
        "--threshold-percent",
        "25",
    ]);

    assert_eq!(
        output.status.code(),
        Some(0),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert!(String::from_utf8_lossy(&output.stdout).contains("STABLE"));
}

#[test]
fn insufficient_samples_are_not_reported_as_a_passing_comparison() {
    let root = temp_dir("cli-compare-inconclusive");
    let baseline = root.join("baseline.json");
    let current = root.join("current.json");
    fixture(&baseline, &[100]);
    fixture(&current, &[120]);
    let output = run_cli(&[
        "--baseline",
        baseline.to_str().unwrap(),
        "--current",
        current.to_str().unwrap(),
    ]);
    assert_eq!(output.status.code(), Some(3));
    assert!(String::from_utf8_lossy(&output.stdout).contains("INCONCLUSIVE"));
}

#[cfg(unix)]
fn write_wrapper(root: &Path, body: &str) {
    use std::os::unix::fs::PermissionsExt;

    let path = root.join("gradlew");
    write_text(&path, body);
    let mut permissions = std::fs::metadata(&path).unwrap().permissions();
    permissions.set_mode(0o755);
    std::fs::set_permissions(path, permissions).unwrap();
}

#[cfg(unix)]
#[test]
fn macro_plan_prints_the_safe_argument_vector_and_environment() {
    let root = temp_dir("cli-macro-plan");
    write_wrapper(&root, "#!/bin/sh\nexit 0\n");

    let output = run_cli(&[
        "macro-plan",
        "--project",
        root.to_str().unwrap(),
        "--filter",
        "dev.Bench#startup with spaces",
        "--compilation-mode",
        "SPEED_PROFILE",
        "--startup-mode",
        "WARM",
        "--scenario",
        "SCROLL",
        "--warmups",
        "3",
        "--iterations",
        "7",
    ]);

    assert_eq!(
        output.status.code(),
        Some(0),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let stdout = String::from_utf8_lossy(&output.stdout);
    assert!(stdout.contains("-Paps.macrobenchmark.compilationMode=speed_profile"));
    assert!(stdout.contains("-Paps.macrobenchmark.startupMode=warm"));
    assert!(stdout.contains("-Paps.macrobenchmark.scenario=scroll"));
    assert!(stdout.contains("APS_MACROBENCHMARK_ITERATIONS=7"));
    assert!(stdout.contains(
        "\"-Pandroid.testInstrumentationRunnerArguments.class=dev.Bench#startup with spaces\""
    ));
}

#[cfg(unix)]
#[test]
fn macro_run_uses_fake_gradle_and_compares_the_generated_result() {
    let root = temp_dir("cli-macro-run");
    let baseline = root.join("baseline.json");
    let result = root.join("generated.json");
    let report = root.join("report.json");
    fixture(&baseline, &[100, 101, 99, 100, 100]);
    let result_json = format!(
        r#"{{"benchmarkDataVersion":1,"benchmarkLibraryVersion":"1.4","context":{{"deviceModel":"Pixel","apiLevel":35,"abi":"arm64-v8a"}},"build":{{"variant":"benchmark"}},"benchmarks":[{{"name":"dev.Bench.startup","scenario":"startup","compilationMode":"none","startupMode":"cold","metrics":{{"timeToInitialDisplayMs":{{"unit":"ms","runs":[120,121,119,120,120]}}}}}}]}}"#
    );
    write_wrapper(
        &root,
        &format!(
            "#!/bin/sh\necho fake-gradle-output\nprintf '%s' '{}' > '{}'\nexit 0\n",
            result_json,
            result.display()
        ),
    );

    let output = run_cli(&[
        "macro-run",
        "--project",
        root.to_str().unwrap(),
        "--result",
        result.to_str().unwrap(),
        "--baseline",
        baseline.to_str().unwrap(),
        "--report-json",
        report.to_str().unwrap(),
    ]);

    assert_eq!(
        output.status.code(),
        Some(1),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    let stdout = String::from_utf8_lossy(&output.stdout);
    assert!(stdout.contains("fake-gradle-output"));
    assert!(stdout.contains("macrobenchmark_cases=1"));
    assert!(stdout.contains("macrobenchmark_regressions=1"));
    assert!(
        std::fs::read_to_string(&report)
            .unwrap()
            .contains("REGRESSED")
    );
}

#[cfg(unix)]
#[test]
fn macro_run_returns_124_for_a_timeout() {
    let root = temp_dir("cli-macro-timeout");
    write_wrapper(&root, "#!/bin/sh\necho started\nsleep 3\n");

    let output = run_cli(&[
        "macro-run",
        "--project",
        root.to_str().unwrap(),
        "--timeout-seconds",
        "1",
    ]);

    assert_eq!(
        output.status.code(),
        Some(124),
        "stderr: {}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert!(String::from_utf8_lossy(&output.stdout).contains("started"));
    assert!(String::from_utf8_lossy(&output.stderr).contains("timed out"));
}

#[cfg(unix)]
#[test]
fn macro_run_exports_profile_evidence() {
    let root = temp_dir("cli-profile");
    let baseline = root.join("baseline.json");
    let current = root.join("current.json");
    fixture(&baseline, &[100, 101, 99, 100, 100]);
    fixture(&current, &[120, 121, 119, 120, 120]);
    for path in [&baseline, &current] {
        let text = std::fs::read_to_string(path).unwrap().replace(
            "\"scenario\": \"startup\",",
            "\"scenario\": \"startup\", \"baselineProfile\": {\"status\": \"verified\"}, \"tracePaths\": [\"run.pftrace\"],",
        );
        std::fs::write(path, text).unwrap();
    }
    write_wrapper(&root, "#!/bin/sh\nexit 0\n");
    let report = root.join("profile.json");
    let output = run_cli(&[
        "macro-run",
        "--project",
        root.to_str().unwrap(),
        "--result",
        current.to_str().unwrap(),
        "--baseline",
        baseline.to_str().unwrap(),
        "--report-json",
        report.to_str().unwrap(),
    ]);
    assert_eq!(
        output.status.code(),
        Some(1),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    let json: serde_json::Value = serde_json::from_slice(&std::fs::read(report).unwrap()).unwrap();
    assert_eq!(json["kind"], "baseline-profile-comparison");
    assert_eq!(json["profiledProfileStatus"], "VERIFIED");
    assert_eq!(json["tracePairs"].as_array().unwrap().len(), 1);
}

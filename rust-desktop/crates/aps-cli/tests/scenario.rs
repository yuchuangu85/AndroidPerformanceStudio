use serde_json::{Value, json};
use std::{
    fs,
    path::Path,
    process::{Command, Output},
};

fn cli(args: &[&str]) -> Output {
    Command::new(env!("CARGO_BIN_EXE_aps"))
        .args(args)
        .env("APS_CLI_AI_KEY", "fixture-not-a-credential")
        .output()
        .unwrap()
}
fn doc(res: Output) -> Value {
    assert!(
        res.status.success(),
        "{}",
        String::from_utf8_lossy(&res.stderr)
    );
    serde_json::from_slice(&res.stdout).unwrap()
}
fn summary(v: &Value) -> &Value {
    &v["evidence"]["items"]
        .as_array()
        .unwrap()
        .iter()
        .find(|v| v["label"] == "summary")
        .unwrap()["value"]
}
fn analyze(p: &Path) -> Value {
    doc(cli(&["analyze", p.to_str().unwrap()]))
}

#[test]
fn startup_import_preserves_missing_timing_and_source_identity() {
    let d = tempfile::tempdir().unwrap();
    let p = d.path().join("startup.csv");
    fs::write(&p, "iteration,runId,requestedType,observedType,displayedTimeMs,fullyDrawnTimeMs,totalTimeMs\n1,r1,COLD,UNKNOWN,67,,100\n").unwrap();
    let v = analyze(&p);
    assert_eq!(summary(&v)["ttid"]["p50Ms"], 67);
    assert_eq!(summary(&v)["ttfd"]["p50Ms"], Value::Null);
    assert_eq!(summary(&v)["amTotalTime"]["p50Ms"], 100);
}

#[test]
fn art_clock_stays_separate_from_cpu_sampling_weights() {
    let d = tempfile::tempdir().unwrap();
    let p = d.path().join("methods.trace");
    let mut bytes=b"*version\n2\nclock=wall\n*threads\n10\tmain\n*methods\n0x0\texample.Main\trun\t()V\tMain.java\n*end\n".to_vec();
    let base = bytes.len();
    bytes.extend(b"SLOW");
    bytes.extend(2_u16.to_le_bytes());
    bytes.extend(32_u16.to_le_bytes());
    bytes.extend(0_u64.to_le_bytes());
    bytes.resize(base + 32, 0);
    for (method, time) in [(0_u32, 100_u32), (1, 140)] {
        bytes.extend(10_u16.to_le_bytes());
        bytes.extend(method.to_le_bytes());
        bytes.extend(time.to_le_bytes());
    }
    fs::write(&p, &bytes).unwrap();
    let v = analyze(&p);
    assert_eq!(summary(&v)["eventCount"], 2);
    assert_eq!(summary(&v)["clock"], "Wall");
    assert_eq!(summary(&v)["topMethods"][0]["selfPrimaryNs"], 40_000);
}

#[cfg(unix)]
#[test]
fn only_approved_target_runs_and_only_fresh_benchmark_is_published() {
    use std::os::unix::fs::PermissionsExt;
    let dir = tempfile::tempdir().unwrap();
    let wrapper = dir.path().join("gradlew");
    fs::write(&wrapper, r#"#!/bin/sh
set -eu
test -z "${APS_CLI_AI_KEY:-}"
test "$ANDROID_SERIAL" = emulator-fixture
printf ran > invoked
printf '%s' '{"benchmarks":[{"name":"Home.launch","metrics":{"timeToInitialDisplayMs":{"unit":"ms","runs":[5,10,15]}}}]}' > fresh.json
"#).unwrap();
    fs::set_permissions(&wrapper, fs::Permissions::from_mode(0o700)).unwrap();
    let f = dir.path().join("scenario.json");
    let manifest = json!({"schemaVersion":"aps-scenario-v1","kind":"macrobenchmark","project":".","result":"fresh.json","iterations":1,"timeoutSeconds":5});
    fs::write(&f, manifest.to_string()).unwrap();
    let f = f.to_str().unwrap();
    let run = |extra: &[&str]| {
        let mut args = vec!["run", f, "--device", "emulator-fixture"];
        args.extend_from_slice(extra);
        cli(&args)
    };
    let preview = doc(run(&[]));
    let digest = preview["approvalDigest"].as_str().unwrap();
    assert!(!dir.path().join("invoked").exists());
    assert!(!run(&["--approve", "different"]).status.success());
    assert!(!dir.path().join("invoked").exists());
    let result = doc(run(&["--approve", digest]));
    assert_eq!(summary(&result)["caseCount"], 1);
    assert_eq!(summary(&result)["metrics"][0]["representativeValue"], 10.0);
    assert!(dir.path().join("invoked").exists());
    assert!(!run(&["--approve", digest]).status.success());
    // A trusted wrapper changed since preview: the prior grant no longer matches.
    fs::remove_file(dir.path().join("fresh.json")).unwrap();
    fs::write(wrapper, b"#!/bin/sh\nexit 0\n").unwrap();
    assert!(
        String::from_utf8_lossy(&run(&["--approve", digest]).stderr)
            .contains("E_SCENARIO_APPROVAL")
    );
    let new_preview = doc(run(&[]));
    let new_digest = new_preview["approvalDigest"].as_str().unwrap();
    assert!(
        String::from_utf8_lossy(&run(&["--approve", new_digest]).stderr)
            .contains("E_SCENARIO_RESULT")
    );
}

#[test]
fn scenario_never_treats_free_text_as_a_device_command() {
    let d = tempfile::tempdir().unwrap();
    let p = d.path().join("unsafe.json");
    fs::write(
        &p,
        r#"{"schemaVersion":"aps-scenario-v1","kind":"macrobenchmark","command":"not executable"}"#,
    )
    .unwrap();
    assert!(
        !cli(&["run", p.to_str().unwrap(), "--device", "fixture"])
            .status
            .success()
    );
}

#[test]
fn reserved_capabilities_are_discoverable_but_cannot_run() {
    let caps = doc(cli(&["capabilities"]));
    for v in caps["deferred"]["capture"].as_array().unwrap() {
        let rejected = cli(&["capture", v.as_str().unwrap()]);
        assert!(!rejected.status.success());
        assert!(String::from_utf8_lossy(&rejected.stderr).contains("E_CAPABILITY_UNAVAILABLE"));
    }
    for v in caps["deferred"]["input"].as_array().unwrap() {
        let rejected = cli(&["analyze", "file-not-opened", "--kind", v.as_str().unwrap()]);
        assert!(!rejected.status.success());
        assert!(String::from_utf8_lossy(&rejected.stderr).contains("E_CAPABILITY_UNAVAILABLE"));
    }
}

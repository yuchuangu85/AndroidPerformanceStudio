//! Real one-shot CLI. No external models, devices, downloads, or user data.
use serde_json::{Value, json};
use std::ffi::OsStr;
use std::fs;
use std::process::{Command, Output, Stdio};
use tempfile::tempdir;

fn cli<I, S>(args: I) -> Output
where
    I: IntoIterator<Item = S>,
    S: AsRef<OsStr>,
{
    Command::new(env!("CARGO_BIN_EXE_aps"))
        .args(args)
        .stdin(Stdio::null())
        .output()
        .unwrap()
}
fn analyze(input: &[u8]) -> Value {
    let dir = tempdir().unwrap();
    let path = dir.path().join("private-artifact-no-extension");
    fs::write(&path, input).unwrap();
    let out = cli(["analyze", path.to_str().unwrap()]);
    assert!(
        out.status.success(),
        "{}",
        String::from_utf8_lossy(&out.stderr)
    );
    assert!(!String::from_utf8_lossy(&out.stdout).contains("private-artifact-no-extension"));
    let envelope: Value = serde_json::from_slice(&out.stdout).unwrap();
    assert_eq!(envelope["schemaVersion"], "aps-evidence-v1");
    assert_eq!(
        envelope["input"]["sourceDigest"],
        aps_contracts::SourceDigest::hash_reader(input, input.len() as u64)
            .unwrap()
            .to_hex()
    );
    envelope
}
fn item<'a>(v: &'a Value, label: &str) -> &'a Value {
    let kind = v["evidence"]["kind"].as_str().unwrap();
    &v["evidence"]["items"]
        .as_array()
        .unwrap()
        .iter()
        .find(|i| i["id"] == format!("{kind}-{label}"))
        .unwrap()["value"]
}

#[test]
fn protobuf_and_gecko_keep_sample_units_and_private_paths_out() {
    let mut protobuf = b"SIMPLEPERF".to_vec();
    protobuf.extend_from_slice(&1u16.to_le_bytes());
    protobuf.extend_from_slice(&[0; 4]);
    let cpu = analyze(&protobuf);
    assert_eq!(item(&cpu, "summary")["sampleCount"], 0);
    assert!(item(&cpu, "summary")["lostRate"].is_null());
    let gecko = json!({"meta":{"version":24,"interval":1},"libs":[],"threads":[{
        "tid":1,"pid":1,"name":"main","markers":{},
        "samples":{"schema":{"stack":0,"time":1},"data":[[0,5]]},
        "frameTable":{"schema":{"location":0},"data":[[0]]},
        "stackTable":{"schema":{"prefix":0,"frame":1},"data":[[null,0]]},
        "stringTable":["run (in /private-resource/libdemo.so)"]
    }],"processes":[]});
    let cpu = analyze(&serde_json::to_vec(&gecko).unwrap());
    assert_eq!(cpu["evidence"]["inputFormat"], "gecko_v24");
    assert_eq!(item(&cpu, "summary")["sampleCount"], 1);
    let top = item(&cpu, "topFunctions");
    assert_eq!(top["weightUnit"], "samples");
    assert_eq!(top["rows"][0]["name"], "run");
    assert_eq!(item(&cpu, "methodDurationAvailable"), false);
    assert!(!cpu.to_string().contains("private-resource"));
}

#[test]
fn frame_text_and_v3_report_use_same_analyzer_and_ignore_supplied_summary() {
    let frame = analyze(b"Window: main\n---PROFILEDATA---\nFlags,IntendedVsync,Vsync,FrameCompleted\n0,100000000,100000000,125000000\n---PROFILEDATA---\n");
    assert_eq!(item(&frame, "summary")["p95DurationNs"], 25_000_000);
    assert_eq!(item(&frame, "summary")["deadlineMissRate"], Value::Null);
    let v3 = json!({"schemaVersion":3,"summary":{"totalFrames":1000},"frames":[{
        "frameId":1,"source":"PERFETTO","intendedVsyncNs":100000000,"frameCompletedNs":120000000,
        "totalDurationNs":20000000,"expectedDurationNs":16666667,"expectedDurationSource":"FRAME_INTERVAL",
        "states":{},"eligibleForJank":true
    }]});
    let frame = analyze(&serde_json::to_vec(&v3).unwrap());
    assert_eq!(item(&frame, "summary")["totalFrames"], 1);
    assert_eq!(item(&frame, "summary")["p95DurationNs"], 20_000_000);
}

const HAR: &[u8] = br#"{"log":{"version":"1.2","entries":[{"startedDateTime":"2020-01-01","time":3.0,"request":{"method":"GET","url":"https://private-host.example/private"},"response":{"status":200}}]}}"#;

#[test]
fn har_export_contains_only_numeric_aggregates_and_parser_owned_labels() {
    let doc = analyze(HAR);
    let summary = item(&doc, "summary");
    assert_eq!(summary["entryCount"], 1);
    assert_eq!(summary["meanDurationMs"], 3.0);
    assert_eq!(summary["unknownResponseByteCount"], 1);
    assert!(!doc.to_string().contains("private-host"));
}

#[test]
fn capability_discovery_and_invalid_artifacts_are_fail_closed() {
    let capabilities = cli(["capabilities"]);
    assert!(capabilities.status.success());
    let doc: Value = serde_json::from_slice(&capabilities.stdout).unwrap();
    assert!(
        doc["analyze"]
            .as_array()
            .unwrap()
            .iter()
            .any(|k| k == "cpu")
    );
    assert!(doc["unavailable"].as_array().is_some_and(|a| !a.is_empty()));
    assert!(cli(["--help"]).status.success());
    let dir = tempdir().unwrap();
    let file = dir.path().join("deceptive.har");
    for bytes in [
        b"PERFILE2".as_slice(),
        b"\x1f\x8b\x08\x00not-decoded",
        b"{\"log\":{},\"log\":{}}",
        b"not a performance artifact",
    ] {
        fs::write(&file, bytes).unwrap();
        let out = cli(["analyze", file.to_str().unwrap()]);
        assert!(!out.status.success());
        assert!(out.stdout.is_empty());
        assert!(!String::from_utf8_lossy(&out.stderr).contains("deceptive.har"));
    }
    let out = cli(["analyze", "file:///not-allowed"]);
    assert!(!out.status.success());
    assert!(String::from_utf8_lossy(&out.stderr).contains("E_FETCH_URL"));
}

#[test]
fn explicit_output_never_clobbers_and_evidence_survives_failed_ai() {
    let dir = tempdir().unwrap();
    let input = dir.path().join("capture.har");
    let evidence = dir.path().join("observed.json");
    let report = dir.path().join("report.md");
    fs::write(&input, HAR).unwrap();
    let (i, e, r) = (
        input.to_str().unwrap(),
        evidence.to_str().unwrap(),
        report.to_str().unwrap(),
    );
    let out = cli(["analyze", i, "--evidence-out", i]);
    assert!(!out.status.success());
    assert_eq!(fs::read(&input).unwrap(), HAR);
    let out = cli([
        "analyze",
        i,
        "--evidence-out",
        r,
        "--ai",
        "review",
        "--out",
        r,
    ]);
    assert!(!out.status.success());
    assert!(!report.exists());
    let out = cli([
        "analyze",
        i,
        "--format",
        "json",
        "--evidence-out",
        e,
        "--ai",
        "review",
        "--out",
        r,
    ]);
    assert!(!out.status.success());
    assert!(String::from_utf8_lossy(&out.stderr).contains("E_AI_APPROVAL_REQUIRED"));
    assert!(out.stdout.is_empty());
    let evidence: Value = serde_json::from_slice(&fs::read(&evidence).unwrap()).unwrap();
    assert_eq!(evidence["evidence"]["kind"], "network");
    assert!(!report.exists());
    let out = cli(["analyze", i, "--evidence-out", e]);
    assert!(!out.status.success());
}

#[test]
#[cfg(unix)]
fn dangling_symlink_is_not_a_new_output_file() {
    let dir = tempdir().unwrap();
    let input = dir.path().join("in");
    let output = dir.path().join("out");
    let absent = dir.path().join("never-create");
    fs::write(&input, HAR).unwrap();
    std::os::unix::fs::symlink(&absent, &output).unwrap();
    assert!(
        !cli([
            "analyze",
            input.to_str().unwrap(),
            "--evidence-out",
            output.to_str().unwrap()
        ])
        .status
        .success()
    );
    assert!(!absent.exists());
}

#[test]
fn frame_selectors_do_not_reach_capture_or_scenario_execution() {
    for (flag, value) in [("--scope", "report"), ("--frame-id", "1")] {
        for (args, expected) in [
            (vec!["capture", "cpu", flag, value], "E_CAPTURE_OPTION"),
            (
                vec!["run", "missing-scenario", flag, value],
                "E_SCENARIO_OPTIONS",
            ),
        ] {
            let out = cli(args);
            assert!(!out.status.success());
            assert!(out.stdout.is_empty());
            assert!(String::from_utf8_lossy(&out.stderr).contains(expected));
        }
    }
}

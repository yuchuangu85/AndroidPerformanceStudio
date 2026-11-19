//! Real CLI, explicit fake ADB and no device. Only --plan may query fake ADB
//! under pipes; execution needs an actual TTY review and cannot be preapproved.
#![cfg(unix)]
use serde_json::Value;
use std::fs;
use std::os::unix::fs::PermissionsExt;
use std::path::Path;
use std::process::{Command, Stdio};

#[test]
fn inspect_only_queries_selected_process_and_rejects_automation_approval() {
    let d = tempfile::tempdir().unwrap();
    let tool = d.path().join("adb");
    let converter = d.path().join("converter");
    let log = d.path().join("log");
    let executable = |p: &Path, content: &str| {
        fs::write(p, content).unwrap();
        fs::set_permissions(p, fs::Permissions::from_mode(0o755)).unwrap();
    };
    executable(
        &tool,
        r#"#!/bin/sh
set -eu
test -z "${APS_CLI_AI_KEY:-}" || exit 88
printf '%s\n' "$*" >> "$LOG"
case "$*" in
  'devices -l') printf 'List of devices attached\nfixture device\n';;
  '-s fixture shell ps -A -o PID,PPID,USER,NAME') printf 'PID PPID USER NAME\n7 1 u0_a123 com.demo\n';;
  '-s fixture shell cat /proc/sys/kernel/random/boot_id') printf 'fixture-boot\n';;
  '-s fixture shell cat /proc/7/stat') printf '7 (com.demo) S 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 123\n';;
  '-s fixture shell cmd package list packages') printf 'package:com.demo\n';;
  *) exit 90;;
esac
"#,
    );
    executable(&converter, "#!/bin/sh\nexit 90\n");
    let args = [
        "capture",
        "cpu",
        "--device",
        "fixture",
        "--pid",
        "7",
        "--seconds",
        "3",
        "--artifact-dir",
        d.path().to_str().unwrap(),
        "--adb",
        tool.to_str().unwrap(),
        "--converter",
        converter.to_str().unwrap(),
    ];
    let run = |more: &[&str]| {
        Command::new(env!("CARGO_BIN_EXE_aps"))
            .args(args)
            .args(more)
            .stdin(Stdio::null())
            .env("APS_CLI_AI_KEY", "fixture")
            .env("LOG", &log)
            .output()
            .unwrap()
    };
    let out = run(&["--plan"]);
    assert!(
        out.status.success(),
        "{}",
        String::from_utf8_lossy(&out.stderr)
    );
    let v: Value = serde_json::from_slice(&out.stdout).unwrap();
    assert_eq!(v["executed"], false);
    assert_eq!(v["startTicks"], 123);
    assert_eq!(v["bootId"], "fixture-boot");
    assert!(!Path::new(v["localDirectory"].as_str().unwrap()).exists());
    let reads = fs::read_to_string(&log).unwrap();
    assert!(reads.contains("devices -l"));
    assert!(!reads.contains("mkdir"));
    assert!(!run(&["--yes"]).status.success());
    assert!(!run(&["--plan", "--out", "untouched"]).status.success());
    let out = run(&[]);
    assert!(!out.status.success());
    assert!(String::from_utf8_lossy(&out.stderr).contains("E_CAPTURE_APPROVAL_REQUIRED"));
    assert_eq!(reads, fs::read_to_string(log).unwrap());
}

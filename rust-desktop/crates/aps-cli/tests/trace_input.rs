use serde_json::Value;
use std::path::Path;
use std::process::Command;

const APS: &str = env!("CARGO_BIN_EXE_aps");
const FIXTURE: &[u8] = include_bytes!("../../aps-perfetto/tests/fixtures/startup-cpu.pftrace");

#[test]
fn parser_hints_never_grant_executable_authority() {
    let dir = tempfile::tempdir().unwrap();
    let untrusted_tool = dir.path().join("untrusted-tool");
    std::fs::write(&untrusted_tool, "not a pinned executable").unwrap();
    let file = dir.path().join("trace-without-suffix");
    std::fs::write(&file, FIXTURE).unwrap();
    let response = Command::new(APS)
        .arg("analyze")
        .arg(&file)
        .args(["--kind", "trace"])
        .env("APS_TRACE_PROCESSOR", &untrusted_tool)
        .output()
        .unwrap();
    assert!(!response.status.success());
    assert!(response.stdout.is_empty());
    assert!(String::from_utf8_lossy(&response.stderr).contains("E_TRACE_TOOL"));
    assert!(!String::from_utf8_lossy(&response.stderr).contains("untrusted-tool"));
    let file = dir.path().join("wrong.pftrace");
    std::fs::write(&file, b"{}").unwrap();
    let response = Command::new(APS)
        .arg("analyze")
        .arg(file)
        .env("APS_TRACE_PROCESSOR", &untrusted_tool)
        .output()
        .unwrap();
    assert!(String::from_utf8_lossy(&response.stderr).contains("E_TRACE_INPUT"));
}

#[test]
#[ignore = "set APS_TRACE_PROCESSOR to the pinned absolute binary for this host"]
fn pinned_processor_returns_numeric_evidence_without_user_labels() {
    let tool = std::env::var_os("APS_TRACE_PROCESSOR").expect("APS_TRACE_PROCESSOR required");
    assert!(Path::new(&tool).is_absolute());
    let dir = tempfile::tempdir().unwrap();
    let file = dir.path().join("private.pftrace");
    std::fs::write(&file, FIXTURE).unwrap();
    let out = Command::new(APS)
        .arg("analyze")
        .arg(&file)
        .env("APS_TRACE_PROCESSOR", tool)
        .output()
        .unwrap();
    assert!(
        out.status.success(),
        "{}",
        String::from_utf8_lossy(&out.stderr)
    );
    let text = String::from_utf8(out.stdout).unwrap();
    assert!(
        !text.contains("private.pftrace")
            && !text.contains("com.example")
            && !text.contains("system_server")
    );
    let doc: Value = serde_json::from_str(&text).unwrap();
    let items = doc["evidence"]["items"].as_array().unwrap();
    let value = |id: &str| &items.iter().find(|row| row["id"] == id).unwrap()["value"];
    assert_eq!(value("trace-bounds")["startNs"], 99);
    assert_eq!(value("trace-bounds")["endNs"], 231);
    assert_eq!(value("trace-processor")["version"], "v59.1");
    assert_eq!(value("trace-frames")["rows"], serde_json::json!([]));
    assert_eq!(
        value("trace-scheduling")["rows"].as_array().unwrap().len(),
        2
    );
}

#![cfg(unix)]
use aps_contracts::SourceDigest;
use serde_json::Value;
use std::{
    fs,
    os::unix::fs::PermissionsExt,
    path::Path,
    process::{Command, Output},
};

fn raw() -> Vec<u8> {
    let mut bytes = vec![0; 264];
    bytes[..8].copy_from_slice(b"PERFILE2");
    for (offset, value) in [(8, 104u64), (16, 160), (24, 104), (32, 160)] {
        bytes[offset..offset + 8].copy_from_slice(&value.to_le_bytes());
    }
    bytes
}
fn tool(path: &Path, payload: &[u8]) {
    let octal = payload
        .iter()
        .map(|b| format!("\\{:03o}", b))
        .collect::<String>();
    // Asserts fixed argv, private captured path, and absence of inherited secrets.
    fs::write(path,format!("#!/bin/sh\n[ \"$1 $2 $3 $4 $6 $7\" = 'report-sample --protobuf --show-callchain -i -o /dev/stdout' ] && [ -f \"$5\" ] && [ -z \"$APS_SENTINEL\" ] || exit 2\nprintf '{octal}'\n")).unwrap();
    fs::set_permissions(path, fs::Permissions::from_mode(0o700)).unwrap();
}
fn analyze(input: &Path, converter: Option<&Path>) -> Output {
    let mut cmd = Command::new(env!("CARGO_BIN_EXE_aps"));
    cmd.arg("analyze")
        .arg(input)
        .env_remove("APS_SIMPLEPERF")
        .env("APS_SENTINEL", "private");
    if let Some(tool) = converter {
        cmd.env("APS_SIMPLEPERF", tool);
    }
    cmd.output().unwrap()
}
#[test]
fn explicit_trusted_converter_preserves_both_identities_and_empty_coverage() {
    let temp = tempfile::tempdir().unwrap();
    let path = temp.path().join("private-perf.data");
    let executable = temp.path().join("private-tool");
    fs::write(&path, raw()).unwrap();
    let missing = analyze(&path, None);
    assert!(!missing.status.success() && missing.stdout.is_empty());
    assert!(String::from_utf8_lossy(&missing.stderr).contains("APS_SIMPLEPERF"));
    let empty = b"SIMPLEPERF\x01\x00\0\0\0\0";
    tool(&executable, empty);
    let output = analyze(&path, Some(&executable));
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    let text = String::from_utf8(output.stdout).unwrap();
    assert!(!text.contains("private-"));
    let doc: Value = serde_json::from_str(&text).unwrap();
    let sections = doc["evidence"]["items"].as_array().unwrap();
    let item = |id: &str| &sections.iter().find(|s| s["id"] == id).unwrap()["value"];
    let link = item("cpu-conversion");
    let expected = SourceDigest::hash_reader(raw().as_slice(), 264)
        .unwrap()
        .to_hex();
    assert_eq!(doc["input"]["sourceDigest"], expected);
    assert_eq!(link["rawSha256"], expected);
    assert_eq!(
        link["protobufSha256"],
        SourceDigest::hash_reader(&empty[..], 256).unwrap().to_hex()
    );
    assert_eq!(link["samplingEvidence"], "no-observed-samples");
    assert_eq!(item("cpu-summary")["sampleCount"], 0);
    tool(&executable, b"SIMPLEPERF\x01\x00\xff\xff\xff\xff");
    let invalid = analyze(&path, Some(&executable));
    assert!(!invalid.status.success() && invalid.stdout.is_empty());
    fs::write(&path, b"PERFILE2-truncated").unwrap();
    assert!(
        String::from_utf8_lossy(&analyze(&path, Some(&executable)).stderr)
            .contains("E_RAW_CPU_INPUT")
    );
}

use std::io::Write;
use std::process::{Command, Stdio};

fn invoke(input: &[u8]) -> std::process::Output {
    let mut child = Command::new(env!("CARGO_BIN_EXE_aps-har-engine"))
        .args(["summarize", "-"])
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();
    child.stdin.take().unwrap().write_all(input).unwrap();
    child.wait_with_output().unwrap()
}

#[test]
fn stdin_success_is_one_json_line_and_no_stderr() {
    let output = invoke(br#"{"log":{"version":"1.2","entries":[]}}"#);
    assert!(output.status.success());
    assert!(output.stderr.is_empty());
    let stdout = String::from_utf8(output.stdout).unwrap();
    assert_eq!(stdout.lines().count(), 1);
    assert!(stdout.starts_with("{\"protocolVersion\":1,\"harVersion\":\"1.2\",\"entryCount\":0,"));
    assert!(stdout.ends_with("\"slowestRequests\":[]}\n"));
}

#[test]
fn malformed_input_and_arguments_have_no_success_output() {
    let output = invoke(br#"{"log":{"version":"1.2","entries":[]}} {}"#);
    assert_eq!(output.status.code(), Some(1));
    assert!(output.stdout.is_empty());
    assert!(String::from_utf8(output.stderr)
        .unwrap()
        .starts_with("aps-har-engine:"));
    let output = Command::new(env!("CARGO_BIN_EXE_aps-har-engine"))
        .output()
        .unwrap();
    assert_eq!(output.status.code(), Some(1));
    assert!(output.stdout.is_empty());
}

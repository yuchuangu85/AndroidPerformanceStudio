use serde_json::{Value, json};
use std::io::{BufRead, BufReader, Write};
use std::path::Path;
use std::process::{Child, ChildStdin, ChildStdout, Command, Stdio};

const BIN: &str = env!("CARGO_BIN_EXE_aps-orchestrator-mcp");
const HAR: &str = r#"{"log":{"version":"1.2","entries":[]}}"#;

struct Client {
    child: Child,
    input: Option<ChildStdin>,
    output: BufReader<ChildStdout>,
}

impl Client {
    fn start(source: Option<&Path>) -> Self {
        let mut command = Command::new(BIN);
        if let Some(path) = source {
            command.arg("--input").arg(path);
        }
        let mut child = command
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .spawn()
            .unwrap();
        let input = child.stdin.take();
        let output = BufReader::new(child.stdout.take().unwrap());
        Self {
            child,
            input,
            output,
        }
    }

    fn send(&mut self, body: &str) {
        let stdin = self.input.as_mut().unwrap();
        writeln!(stdin, "{body}").unwrap();
        stdin.flush().unwrap();
    }

    fn read(&mut self) -> Value {
        let mut line = String::new();
        assert!(self.output.read_line(&mut line).unwrap() > 0);
        serde_json::from_str(&line).unwrap()
    }

    fn request(&mut self, method: &str, params: Value) -> Value {
        self.send(&json!({"jsonrpc":"2.0","id":123,"method":method,"params":params}).to_string());
        let result = self.read();
        assert_eq!(result["id"], 123);
        result
    }

    fn call(&mut self, name: &str, args: Value) -> Value {
        self.request("tools/call", json!({"name":name,"arguments":args}))
    }

    fn finish(mut self) {
        self.input.take();
        assert!(self.child.wait().unwrap().success());
    }
}

impl Drop for Client {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

#[test]
fn local_source_returns_actual_parser_evidence_and_rejects_rebinding() {
    let temp = tempfile::tempdir().unwrap();
    let path = temp.path().join("private-user-source.har");
    std::fs::write(&path, HAR).unwrap();
    let mut client = Client::start(Some(&path));
    assert_eq!(
        client.request("initialize", json!({}))["result"]["serverInfo"]["name"],
        "aps-orchestrator-mcp"
    );
    client.send(r#"{"jsonrpc":"2.0","method":"notifications/initialized"}"#);
    let list = client.request("tools/list", json!({}));
    assert!(
        list["result"]["tools"]
            .as_array()
            .unwrap()
            .iter()
            .any(|tool| tool["name"] == "aps_analyze_file")
    );
    let args = json!({"question":"这份记录里面有几个请求？"});
    let result = client.call("aps_analyze_file", args.clone());
    let evidence = &result["result"]["structuredContent"];
    assert_eq!(evidence["evidence"]["kind"], "network");
    let items = evidence["evidence"]["items"].as_array().unwrap();
    let summary = items
        .iter()
        .find(|item| item["id"] == "network-summary")
        .unwrap();
    assert_eq!(summary["value"]["entryCount"], 0);
    assert_eq!(
        evidence["input"]["sourceDigest"].as_str().unwrap().len(),
        64
    );
    assert_eq!(
        &serde_json::from_str::<Value>(result["result"]["content"][0]["text"].as_str().unwrap())
            .unwrap(),
        evidence
    );
    assert!(!result.to_string().contains("private-user-source"));
    assert!(
        client.call("aps_analyze_file", json!({"path":"/some/other.har"}))["error"].is_object()
    );
    std::fs::write(&path, HAR.replace("1.2", "1.1")).unwrap();
    assert_eq!(
        client.call("aps_analyze_file", args.clone())["error"]["code"],
        -32001
    );
    std::fs::write(&path, HAR).unwrap();
    assert_eq!(
        client.call("aps_analyze_file", args)["error"]["code"],
        -32001
    );
    client.finish();
}

#[test]
fn unbound_server_only_discovers_and_plans_without_side_effects() {
    let mut client = Client::start(None);
    let list = client.request("tools/list", json!({}));
    assert_eq!(list["result"]["tools"].as_array().unwrap().len(), 2);
    let cap = client.call("aps_capabilities", json!({}));
    assert_eq!(cap["result"]["structuredContent"]["sourceBound"], false);
    let plan = client.call(
        "aps_run_plan",
        json!({"intent":"macrobenchmark","question":"录制启动"}),
    );
    assert_eq!(plan["result"]["structuredContent"]["executed"], false);
    assert_eq!(
        client.call(
            "aps_run_plan",
            json!({"intent":"macrobenchmark","device":"unsafe"})
        )["error"]["code"],
        -32602
    );
    assert_eq!(
        client.call("aps_analyze_file", json!({}))["error"]["code"],
        -32001
    );
    assert_eq!(
        client.call("aps_capabilities", Value::Null)["error"]["code"],
        -32602
    );
    client.finish();
}

#[test]
fn protocol_budgets_recover_and_urls_never_open() {
    let output = Command::new(BIN)
        .args(["--input", "https://example.invalid/private?sig=secret"])
        .output()
        .unwrap();
    assert!(!output.status.success());
    assert!(output.stdout.is_empty());
    assert!(!String::from_utf8_lossy(&output.stderr).contains("secret"));

    let mut client = Client::start(None);
    client.send(&"x".repeat(70 * 1024));
    assert_eq!(client.read()["error"]["code"], -32600);
    client.send(r#"{"jsonrpc":"2.0","id":1,"method":"ping","method":"tools/call"}"#);
    assert_eq!(client.read()["error"]["code"], -32700);
    assert_eq!(client.request("ping", json!({}))["result"], json!({}));
    client.finish();
}

#[test]
fn aps_mcp_alias_and_cli_use_the_same_file_evidence() {
    let temp = tempfile::tempdir().unwrap();
    let path = temp.path().join("opaque"); // the suffix is not an analyzer selector
    std::fs::write(&path, HAR).unwrap();
    let cli = Command::new(env!("CARGO_BIN_EXE_aps"))
        .arg("analyze")
        .arg(&path)
        .output()
        .unwrap();
    assert!(cli.status.success());
    let expected: Value = serde_json::from_slice(&cli.stdout).unwrap();

    let mut alias = Command::new(env!("CARGO_BIN_EXE_aps"))
        .args(["mcp", "--input"])
        .arg(&path)
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .spawn()
        .unwrap();
    let mut stdin = alias.stdin.take().unwrap();
    writeln!(stdin, r#"{{"jsonrpc":"2.0","id":1,"method":"initialize"}}"#).unwrap();
    writeln!(
        stdin,
        r#"{{"jsonrpc":"2.0","method":"notifications/initialized"}}"#
    )
    .unwrap();
    writeln!(
        stdin,
        r#"{{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{{"name":"aps_analyze_file"}}}}"#
    )
    .unwrap();
    drop(stdin);
    let output = alias.wait_with_output().unwrap();
    assert!(output.status.success());
    let messages: Vec<Value> = output
        .stdout
        .split(|b| *b == b'\n')
        .filter(|b| !b.is_empty())
        .map(|line| serde_json::from_slice(line).unwrap())
        .collect();
    assert_eq!(messages.len(), 2);
    assert_eq!(messages[1]["result"]["structuredContent"], expected);
    assert_eq!(std::fs::read_dir(temp.path()).unwrap().count(), 1);
}

#[test]
fn host_url_option_cannot_be_used_to_select_a_local_path_or_cleartext_url() {
    for target in [
        "file:///private/data",
        "/tmp/should-not-resolve",
        "http://example.invalid/?secret",
    ] {
        let out = Command::new(env!("CARGO_BIN_EXE_aps"))
            .args(["mcp", "--artifact-url", target])
            .output()
            .unwrap();
        assert!(!out.status.success());
        assert!(out.stdout.is_empty());
        assert!(String::from_utf8_lossy(&out.stderr).contains("E_FETCH_URL"));
        assert!(!String::from_utf8_lossy(&out.stderr).contains(target));
    }
}

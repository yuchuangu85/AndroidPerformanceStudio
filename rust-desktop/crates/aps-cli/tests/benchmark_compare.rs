use aps_contracts::SourceDigest;
use serde_json::{Value, json};
use std::io::{BufRead, BufReader, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, ChildStdout, Command, Output, Stdio};

const CLI: &str = env!("CARGO_BIN_EXE_aps");
const MCP: &str = env!("CARGO_BIN_EXE_aps-orchestrator-mcp");
const TOOL: &str = "aps_benchmark_compare";

fn report(samples: &[u64], stable_case: bool) -> Value {
    let case = |name, samples: &[u64]| {
        json!({
            "name":name, "scenario":"startup", "compilationMode":"none", "startupMode":"cold",
            "metrics":{"timeToInitialDisplayMs":{"unit":"ms","runs":samples}}
        })
    };
    let mut cases = vec![case("dev.Bench.startup", samples)];
    if stable_case {
        cases.push(case("dev.Bench.anotherStartup", &[90, 90, 90, 90, 90]));
    }
    json!({
        "benchmarkDataVersion":1,
        "benchmarkLibraryVersion":"1.4",
        "context":{"deviceModel":"Pixel","apiLevel":35,"abi":"arm64-v8a","fingerprint":"PRIVATE-device-fingerprint"},
        "build":{"variant":"benchmark","gitBranch":"PRIVATE-branch"},
        "benchmarks":cases,
        "privateUnrecognisedMetadata":"PRIVATE-not-for-output"
    })
}

struct Pair {
    root: tempfile::TempDir,
    baseline: PathBuf,
    current: PathBuf,
}

impl Pair {
    fn new(before: &[u64], after: &[u64], stable_case: bool) -> Self {
        let root = tempfile::tempdir().unwrap();
        let baseline = root.path().join("PRIVATE-baseline.json");
        let current = root.path().join("PRIVATE-current.json");
        std::fs::write(&baseline, report(before, stable_case).to_string()).unwrap();
        std::fs::write(&current, report(after, stable_case).to_string()).unwrap();
        Self {
            root,
            baseline,
            current,
        }
    }

    fn cli(&self, opts: &[&str]) -> Output {
        Command::new(CLI)
            .args(["benchmark", "compare", "--baseline"])
            .arg(&self.baseline)
            .arg("--current")
            .arg(&self.current)
            .args(opts)
            .output()
            .unwrap()
    }

    fn page(&self, opts: &[&str], code: i32) -> Value {
        let out = self.cli(opts);
        assert_eq!(
            out.status.code(),
            Some(code),
            "{}",
            String::from_utf8_lossy(&out.stderr)
        );
        serde_json::from_slice(&out.stdout).unwrap()
    }

    fn connect(&self, opts: &[&str]) -> Client {
        let mut command = Command::new(CLI);
        command
            .arg("mcp")
            .arg("--input")
            .arg(&self.current)
            .arg("--baseline")
            .arg(&self.baseline)
            .args(opts);
        Client::start(command)
    }
}

struct Client {
    child: Child,
    stdin: ChildStdin,
    stdout: BufReader<ChildStdout>,
}

impl Client {
    fn start(mut command: Command) -> Self {
        let mut child = command
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .spawn()
            .unwrap();
        let stdin = child.stdin.take().unwrap();
        let stdout = BufReader::new(child.stdout.take().unwrap());
        let mut client = Self {
            child,
            stdin,
            stdout,
        };
        let handshake = client.request(
            "initialize",
            json!({
                "protocolVersion":"2024-11-05",
                "capabilities":{},
                "clientInfo":{"name":"benchmark-fixture","version":"1"}
            }),
        );
        assert!(handshake["result"].is_object());
        client.send(json!({"jsonrpc":"2.0","method":"notifications/initialized"}));
        client
    }

    fn send(&mut self, body: Value) {
        writeln!(&mut self.stdin, "{body}").unwrap();
        self.stdin.flush().unwrap();
    }

    fn request(&mut self, method: &str, params: Value) -> Value {
        self.send(json!({"jsonrpc":"2.0","id":1,"method":method,"params":params}));
        let mut response = String::new();
        assert!(self.stdout.read_line(&mut response).unwrap() > 0);
        let value: Value = serde_json::from_str(&response).unwrap();
        assert_eq!(value["id"], 1);
        value
    }

    fn call(&mut self, name: &str, args: Value) -> Value {
        self.request("tools/call", json!({"name":name,"arguments":args}))
    }

    fn page(&mut self, args: Value) -> Value {
        let response = self.call(TOOL, args);
        let page = &response["result"]["structuredContent"];
        assert!(page.is_object(), "{response}");
        let text = response["result"]["content"][0]["text"].as_str().unwrap();
        assert_eq!(serde_json::from_str::<Value>(text).unwrap(), *page);
        page.clone()
    }
}

impl Drop for Client {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

fn digest(path: &Path) -> String {
    SourceDigest::hash_file(path, 64 * 1024 * 1024)
        .unwrap()
        .to_hex()
}

#[test]
fn cli_and_mcp_publish_the_same_bound_pages_and_full_comparison_gate() {
    let pair = Pair::new(&[100, 101, 99, 100, 100], &[120, 121, 119, 120, 120], true);
    let mut client = pair.connect(&[]);
    let tools = client.request("tools/list", json!({}));
    assert!(
        tools["result"]["tools"]
            .as_array()
            .unwrap()
            .iter()
            .any(|t| t["name"] == TOOL)
    );
    let results = pair.page(&["--limit", "1"], 1);
    assert_eq!(results["schemaVersion"], "aps-benchmark-comparison-v1");
    assert_eq!(results["baselineDigest"], digest(&pair.baseline));
    assert_eq!(results["currentDigest"], digest(&pair.current));
    assert_eq!(results["gate"]["decision"], "FAIL");
    assert_eq!(results["total"], 2);
    assert_eq!(results["offset"], 0);
    assert_eq!(results["nextOffset"], 1);
    assert_eq!(results["items"].as_array().unwrap().len(), 1);
    assert!(
        results["items"][0]["id"]
            .as_str()
            .is_some_and(|id| !id.is_empty())
    );
    assert!(!results.to_string().contains("PRIVATE"));
    assert_eq!(client.page(json!({"limit":1})), results);

    let next = pair.page(&["--offset", "1", "--limit", "1"], 1);
    assert_eq!(client.page(json!({"offset":1,"limit":1})), next);
    assert_eq!(next["gate"], results["gate"]);
    assert_eq!(next["nextOffset"], Value::Null);
    assert_ne!(next["items"][0]["id"], results["items"][0]["id"]);
    assert_eq!(client.page(json!({"limit":1}))["items"], results["items"]);
}

#[test]
fn mcp_discovery_and_queries_cannot_rebind_the_selected_pair() {
    let pair = Pair::new(&[100, 101, 99, 100, 100], &[120, 121, 119, 120, 120], true);
    let mut single = Command::new(MCP);
    single.arg("--input").arg(&pair.current);
    let mut single = Client::start(single);
    let tools = single.request("tools/list", json!({}));
    assert!(
        tools["result"]["tools"]
            .as_array()
            .unwrap()
            .iter()
            .all(|t| t["name"] != TOOL)
    );
    assert!(single.call(TOOL, json!({}))["error"].is_object());

    for changed in [&pair.baseline, &pair.current] {
        let original = std::fs::read(changed).unwrap();
        let mut bound = pair.connect(&[]);
        let baseline = bound.page(json!({}));
        for args in [
            json!({"baseline":"/another/source.json"}),
            json!({"path":"/another/source.json"}),
            json!({"thresholdPercent":25}),
            json!({"limit":0}),
            json!({"offset":3}),
        ] {
            assert_eq!(bound.call(TOOL, args)["error"]["code"], -32602);
        }
        // Target a real measurement while keeping byte length unchanged.
        let mut modified = original.clone();
        let needle = br#""runs":["#;
        let value_at = modified
            .windows(needle.len())
            .position(|w| w == needle)
            .unwrap()
            + needle.len();
        modified[value_at] = b'7';
        std::fs::write(changed, modified).unwrap();
        assert_eq!(bound.call(TOOL, json!({}))["error"]["code"], -32001);
        std::fs::write(changed, original).unwrap();
        assert_eq!(bound.call(TOOL, json!({}))["error"]["code"], -32001);
        assert_eq!(pair.connect(&[]).page(json!({})), baseline);
    }
    assert_eq!(std::fs::read_dir(pair.root.path()).unwrap().count(), 2);
}

#[test]
fn cli_exit_codes_and_atomic_output_are_separate_from_gate_evidence() {
    let pair = Pair::new(&[100, 101, 99, 100, 100], &[120, 121, 119, 120, 120], false);
    let out = pair.root.path().join("evidence.json");
    let output = out.to_str().unwrap();
    let page = pair.page(&["--threshold-percent", "25"], 0);
    assert_eq!(page["gate"]["decision"], "PASS");
    assert_eq!(
        pair.connect(&["--threshold-percent", "25"]).page(json!({})),
        page
    );

    let regression = pair.cli(&["--evidence-out", output]);
    assert_eq!(regression.status.code(), Some(1));
    let original = std::fs::read(&out).unwrap();
    let published: Value = serde_json::from_slice(&original).unwrap();
    assert_eq!(published["gate"]["decision"], "FAIL");
    assert_eq!(
        pair.cli(&["--threshold-percent", "25", "--evidence-out", output])
            .status
            .code(),
        Some(2)
    );
    assert_eq!(std::fs::read(&out).unwrap(), original);
    for opts in [
        &["--threshold-percent", "NaN"][..],
        &["--offset", "-1"][..],
        &["--limit", "0"][..],
    ] {
        let rejected = pair.cli(opts);
        assert_eq!(rejected.status.code(), Some(2));
        assert!(rejected.stdout.is_empty());
    }
    let sparse = Pair::new(&[100, 101], &[120, 121], false);
    let inconclusive = sparse.page(&[], 3);
    assert_eq!(inconclusive["gate"]["decision"], "INCONCLUSIVE");
}

#[test]
fn dropping_baseline_metrics_never_turns_a_partial_comparison_into_a_pass() {
    let pair = Pair::new(&[100, 101, 99, 100, 100], &[101, 102, 100, 101, 101], true);
    let mut current = report(&[101, 102, 100, 101, 101], false);
    std::fs::write(&pair.current, current.to_string()).unwrap();
    let page = pair.page(&[], 3);
    assert_eq!(page["gate"]["decision"], "INCONCLUSIVE");
    assert_eq!(page["counts"]["baselineOnlyMetrics"], 1);
    assert_eq!(pair.connect(&[]).page(json!({})), page);
    // A unit mismatch can still show each measurement in its own unit,
    // but definitely cannot imply a percentage change between them.
    current["benchmarks"][0]["metrics"]["timeToInitialDisplayMs"]["unit"] = "ns".into();
    std::fs::write(&pair.current, current.to_string()).unwrap();
    let mixed = pair.page(&[], 3);
    let row = &mixed["items"][0]["value"];
    assert_eq!(row["classification"], "INCOMPATIBLE");
    assert_eq!(row["baselineUnit"], "ms");
    assert_eq!(row["currentUnit"], "ns");
    assert_eq!(row["absoluteDelta"], Value::Null);
    assert_eq!(row["relativeDeltaPercent"], Value::Null);
}

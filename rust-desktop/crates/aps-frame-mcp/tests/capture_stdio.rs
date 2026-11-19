//! Offline contract tests for the capture-receipt mode. Synthetic trace bytes
//! stand in for the operator-selected artifact; MCP hashes but does not analyze it.
use aps_contracts::SourceDigest;
use serde_json::{Value, json};
use std::ffi::OsString;
use std::fs;
use std::io::{BufRead, BufReader, Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, ChildStdout, Command, Stdio};
use std::sync::atomic::{AtomicUsize, Ordering};

const BIN: &str = env!("CARGO_BIN_EXE_aps-frame-mcp");
const OVERLAPS: &str = "frame_trace_overlaps";
const COMPARE: &str = "frame_session_compare";
const MARKER: &str = "APS.Frame/0000000000000001";
const BASE_ONLY: &str = "APS.Frame/0000000000000002";

struct Workspace(PathBuf);

impl Workspace {
    fn new() -> Self {
        static NEXT: AtomicUsize = AtomicUsize::new(0);
        loop {
            let dir = std::env::temp_dir().join(format!(
                "aps-frame-capture-stdio-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            match fs::create_dir(&dir) {
                Ok(()) => return Self(dir),
                Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => continue,
                Err(e) => panic!("cannot create test workspace: {e}"),
            }
        }
    }
}

impl Drop for Workspace {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

fn digest(bytes: &[u8]) -> String {
    SourceDigest::hash_reader(bytes, bytes.len() as u64)
        .unwrap()
        .to_hex()
}

fn json_file(path: &Path, value: &Value) {
    fs::write(path, serde_json::to_vec(value).unwrap()).unwrap();
}

fn read_json(path: &Path) -> Value {
    serde_json::from_slice(&fs::read(path).unwrap()).unwrap()
}

struct Capture {
    report: PathBuf,
    trace: PathBuf,
    receipt: PathBuf,
}

impl Capture {
    fn new(dir: &Workspace, baseline: bool) -> Self {
        let label = if baseline { "baseline" } else { "after" };
        let pid = if baseline { 234 } else { 123 };
        let durations = if baseline {
            [30_000_000, 20_000_000]
        } else {
            [11_000_000, 16_000_000]
        };
        let paths = Self {
            report: dir.0.join(format!("{label}.json")),
            trace: dir.0.join(format!("{label}.pftrace")),
            receipt: dir.0.join(format!("{label}.frame-mcp.json")),
        };
        let trace = format!("synthetic trace fixture: {label}");
        let trace_digest = digest(trace.as_bytes());
        fs::write(&paths.trace, trace).unwrap();
        let frames = durations.into_iter().enumerate().map(|(i,duration)| {
            let start = 1_000_000_000_i64 + i as i64 * 32_000_000;
            let mut states = json!({"aps.target.process":"com.example", "private":"never-emit-this"});
            if i == 0 {
                let overlap = if baseline { 700 } else { 500 };
                states["aps.trace.0"] = json!(format!("{MARKER} {pid} {overlap} -1 Main"));
            } else if baseline {
                states["aps.trace.0"] = json!(format!("{BASE_ONLY} {} 900 100 RenderThread",pid+1));
            }
            json!({"frameId":i+1,"source":"PERFETTO","packageName":"com.example","processId":pid,
                "states":states,"frameTimelineVsyncId":88,"windowId":"main","intendedVsyncNs":start,
                "actualVsyncNs":start,"frameCompletedNs":start+duration,"presentNs":start+duration,
                "totalDurationNs":duration,"expectedDurationNs":16_666_667,
                "expectedDurationSource":"PLATFORM_DEADLINE","eligibleForJank":true,"refreshRateHz":60.0})
        }).collect::<Vec<_>>();
        json_file(&paths.report, &json!({"schemaVersion":3,"frames":frames}));
        json_file(
            &paths.receipt,
            &json!({
                "kind":"aps.frame.capture-receipt.v1",
                "captureId":format!("perfetto-{}-0123456789abcdef",&trace_digest[..32]),
                "reportDigest":paths.report_digest(),"traceDigest":trace_digest,
                "deviceDigest":digest(b"synthetic-private-device"),"androidApi":35,
                "packageName":"com.example","processName":"com.example","pid":pid,
                "refreshRatesHz":[60.0],"frameCount":2,"probeQueryComplete":true
            }),
        );
        paths
    }

    fn report_digest(&self) -> String {
        digest(&fs::read(&self.report).unwrap())
    }

    fn frame_args(&self, id: &str) -> Value {
        json!({"sessionId":format!("frame-{}", self.report_digest()),"frameId":id})
    }

    fn args(&self, baseline: Option<&Self>) -> Vec<OsString> {
        let flags = ["--input", "--capture-receipt", "--trace"];
        let base_flags = ["--baseline-input", "--baseline-receipt", "--baseline-trace"];
        let mut args = Vec::new();
        let mut add = |cap: &Self, flags: [&str; 3]| {
            for (flag, path) in flags
                .into_iter()
                .zip([&cap.report, &cap.receipt, &cap.trace])
            {
                args.push(OsString::from(flag));
                args.push(path.as_os_str().to_owned());
            }
        };
        add(self, flags);
        if let Some(cap) = baseline {
            add(cap, base_flags);
        }
        args
    }
}

struct Client {
    child: Child,
    stdin: ChildStdin,
    stdout: BufReader<ChildStdout>,
    next_id: u32,
}

impl Client {
    fn start(args: &[OsString]) -> Self {
        let mut child = Command::new(BIN)
            .args(args)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .unwrap();
        let mut client = Self {
            stdin: child.stdin.take().unwrap(),
            stdout: BufReader::new(child.stdout.take().unwrap()),
            child,
            next_id: 0,
        };
        let init = client.send("initialize",json!({"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1"}}));
        assert_eq!(init["result"]["serverInfo"]["name"], "aps-frame-mcp");
        writeln!(
            client.stdin,
            "{}",
            json!({"jsonrpc":"2.0","method":"notifications/initialized"})
        )
        .unwrap();
        client
    }

    fn send(&mut self, method: &str, params: Value) -> Value {
        self.next_id += 1;
        writeln!(
            self.stdin,
            "{}",
            json!({"jsonrpc":"2.0","id":self.next_id,"method":method,"params":params})
        )
        .unwrap();
        self.stdin.flush().unwrap();
        let mut line = String::new();
        if self.stdout.read_line(&mut line).unwrap() == 0 {
            let mut stderr = String::new();
            self.child
                .stderr
                .as_mut()
                .unwrap()
                .read_to_string(&mut stderr)
                .unwrap();
            panic!("server closed without a response: {stderr}");
        }
        let response: Value = serde_json::from_str(&line).unwrap();
        assert_eq!(response["id"], self.next_id);
        assert_eq!(response["jsonrpc"], "2.0");
        response
    }

    fn catalog(&mut self) -> Vec<Value> {
        self.send("tools/list", json!({}))["result"]["tools"]
            .as_array()
            .unwrap()
            .clone()
    }

    fn call(&mut self, name: &str, arguments: Value) -> Value {
        self.send("tools/call", json!({"name":name,"arguments":arguments}))
    }

    fn ok(&mut self, name: &str, arguments: Value) -> Value {
        let response = self.call(name, arguments);
        assert_eq!(response["result"]["isError"], false, "{response}");
        let result = &response["result"];
        let text: Value =
            serde_json::from_str(result["content"][0]["text"].as_str().unwrap()).unwrap();
        assert_eq!(text, result["structuredContent"]);
        text
    }
}

impl Drop for Client {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

fn code(response: Value, expected: i64) {
    assert_eq!(response["error"]["code"], expected, "{response}");
    assert!(response.get("result").is_none(), "{response}");
}

#[test]
fn report_alone_does_not_authorize_embedded_trace_rows_or_a_comparison() {
    let dir = Workspace::new();
    let after = Capture::new(&dir, false);
    let args = after.args(None);
    let mut legacy = Client::start(&args[..2]);
    let tools = legacy.catalog();
    assert_eq!(tools.len(), 5);
    for name in [OVERLAPS, COMPARE] {
        assert!(!tools.iter().any(|t| t["name"] == name));
        assert!(legacy.call(name, json!({})).get("error").is_some());
    }
    let details = legacy.ok("frame_detail", after.frame_args("1")).to_string();
    assert!(!details.contains(MARKER));
    assert!(!details.contains("never-emit-this"));
}

#[test]
fn target_receipt_enables_only_bound_observations_and_does_not_invent_scheduling() {
    let dir = Workspace::new();
    let after = Capture::new(&dir, false);
    let mut client = Client::start(&after.args(None));
    let tools = client.catalog();
    assert_eq!(tools.len(), 7);
    for name in [OVERLAPS, COMPARE] {
        let entry = tools.iter().find(|tool| tool["name"] == name).unwrap();
        assert_eq!(entry["inputSchema"]["additionalProperties"], false);
        assert_eq!(entry["annotations"]["readOnlyHint"], true);
    }
    let overlaps = client.ok(OVERLAPS, after.frame_args("1"));
    let summary = client.ok("frame_session_summary", json!({}));
    assert_eq!(summary["sourceDigest"], after.report_digest());
    for key in ["sourceDigest", "sessionId", "analysisPolicyVersion"] {
        assert_eq!(overlaps[key], summary[key], "{key}");
    }
    assert_eq!(overlaps["items"][0]["marker"], MARKER);
    assert_eq!(overlaps["items"][0]["tid"], 123);
    assert_eq!(overlaps["items"][0]["threadRole"], "Main");
    assert_eq!(overlaps["items"][0]["overlapNs"], 500);
    assert_eq!(
        overlaps["items"][0].get("observedScheduledNs"),
        Some(&Value::Null)
    );
    assert_eq!(overlaps["coverage"]["complete"], true);
    let empty = client.ok(OVERLAPS, after.frame_args("2"));
    assert_eq!(empty["items"], json!([]));
    // Both surfaces share VSYNC 88; it must not be accepted as a row identity.
    code(client.call(OVERLAPS, after.frame_args("88")), -32001);
    code(
        client.call(OVERLAPS, json!({"sessionId":"other","frameId":"1"})),
        -32001,
    );
    code(client.call(COMPARE, json!({})), -32001);
    let mut injected = after.frame_args("1");
    injected["tracePath"] = json!(after.trace);
    code(client.call(OVERLAPS, injected), -32602);
    code(
        client.call(COMPARE, json!({"baseline":after.report})),
        -32602,
    );
    assert!(!overlaps.to_string().contains("never-emit-this"));
    assert!(!overlaps.to_string().contains(dir.0.to_str().unwrap()));
}

#[test]
fn comparable_distinct_runs_report_after_minus_baseline_and_observed_marker_coverage() {
    let dir = Workspace::new();
    let after = Capture::new(&dir, false);
    let before = Capture::new(&dir, true);
    let mut client = Client::start(&after.args(Some(&before)));
    let page = client.ok(COMPARE, json!({"limit":1}));
    assert_eq!(page["analysisPolicyVersion"], "aps-frame-v2");
    assert_eq!(page["sourceDigest"], after.report_digest());
    assert_eq!(page["sessionId"], after.frame_args("1")["sessionId"]);
    assert_eq!(page["direction"], "after_minus_baseline");
    assert_eq!(page["baseline"]["reportDigest"], before.report_digest());
    assert_eq!(page["after"]["reportDigest"], after.report_digest());
    assert_eq!(page["baseline"]["p95DurationNs"], 30_000_000);
    assert_eq!(page["after"]["p95DurationNs"], 16_000_000);
    assert_eq!(page["delta"]["p95DurationNs"], -14_000_000);
    assert_eq!(page["baseline"]["deadlineClassifiedFrames"], 2);
    assert_eq!(page["baseline"]["deadlineMissFrames"], 2);
    assert_eq!(page["after"]["deadlineMissFrames"], 0);
    assert_eq!(
        page["delta"]["deadlineMissPercentagePoints"].as_f64(),
        Some(-100.0)
    );
    assert_eq!(page["items"][0]["marker"], MARKER);
    assert_eq!(page["items"][0]["deltaP95OverlapNs"], -200);
    assert_eq!(page["page"]["nextOffset"], 1);
    let next = client.ok(COMPARE, json!({"limit":1,"offset":1}));
    assert_eq!(next["items"][0]["marker"], BASE_ONLY);
    assert_eq!(
        next["items"][0]["after"].get("p95OverlapNs"),
        Some(&Value::Null)
    );
    assert_eq!(
        next["items"][0].get("deltaP95OverlapNs"),
        Some(&Value::Null)
    );
    assert_eq!(next["page"].get("nextOffset"), Some(&Value::Null));
    for hidden in [
        "synthetic-private-device",
        "never-emit-this",
        dir.0.to_str().unwrap(),
    ] {
        assert!(!page.to_string().contains(hidden));
    }
    code(client.call(COMPARE, json!({"limit":0})), -32602);
}

#[test]
fn each_selected_source_is_rechecked_on_followup_comparison_pages() {
    let dir = Workspace::new();
    let after = Capture::new(&dir, false);
    let before = Capture::new(&dir, true);
    for path in [
        &after.report,
        &after.receipt,
        &after.trace,
        &before.report,
        &before.receipt,
        &before.trace,
    ] {
        let mut client = Client::start(&after.args(Some(&before)));
        assert_eq!(
            client.ok(COMPARE, json!({"limit":1}))["page"]["nextOffset"],
            1
        );
        let bytes = fs::read(path).unwrap();
        let mut changed = bytes.clone();
        // Replacing a file without changing size must still invalidate its digest.
        changed[0] ^= 1;
        fs::write(path, &changed).unwrap();
        let args = json!({"offset":1,"limit":1});
        code(client.call(COMPARE, args.clone()), -32001);
        fs::write(path, &bytes).unwrap();
        code(client.call(COMPARE, args), -32001);
    }
}

fn cannot_start(args: &[OsString], dir: &Workspace) {
    let result = Command::new(BIN)
        .args(args)
        .stdin(Stdio::null())
        .output()
        .unwrap();
    assert!(
        !result.status.success(),
        "unexpectedly accepted invalid capture inputs"
    );
    assert!(result.stdout.is_empty(), "partial MCP output");
    assert!(!result.stderr.is_empty());
    assert!(!String::from_utf8_lossy(&result.stderr).contains(dir.0.to_str().unwrap()));
}

#[test]
fn incomplete_cli_or_unbound_receipt_is_rejected_before_listening() {
    let dir = Workspace::new();
    let after = Capture::new(&dir, false);
    let before = Capture::new(&dir, true);
    let args = after.args(Some(&before));
    for pair in [1, 2, 3, 4, 5] {
        let mut incomplete = args.clone();
        incomplete.drain(pair * 2..pair * 2 + 2);
        cannot_start(&incomplete, &dir);
    }
    let good = read_json(&after.receipt);
    for (key, wrong) in [
        ("kind", json!("unverified")),
        ("captureId", json!("imported-report")),
        ("traceDigest", json!(digest(b"unrelated trace"))),
        ("reportDigest", json!(digest(b"unrelated report"))),
        ("frameCount", json!(3)),
        ("pid", json!(4321)),
        ("processName", json!("com.example:other")),
    ] {
        let mut changed = good.clone();
        changed[key] = wrong;
        json_file(&after.receipt, &changed);
        cannot_start(&after.args(None), &dir);
    }
}

#[test]
fn unavailable_probe_query_is_not_an_empty_observation_or_observed_improvement() {
    let dir = Workspace::new();
    let after = Capture::new(&dir, false);
    let before = Capture::new(&dir, true);
    // A real unsuccessful query emitted no stored rows. Frame durations are
    // still measured, so overall comparison need not pretend the probes ran.
    let mut report = read_json(&after.report);
    for frame in report["frames"].as_array_mut().unwrap() {
        frame["states"]
            .as_object_mut()
            .unwrap()
            .remove("aps.trace.0");
    }
    json_file(&after.report, &report);
    let mut receipt = read_json(&after.receipt);
    receipt["reportDigest"] = json!(after.report_digest());
    receipt["probeQueryComplete"] = json!(false);
    json_file(&after.receipt, &receipt);

    let mut client = Client::start(&after.args(Some(&before)));
    code(client.call(OVERLAPS, after.frame_args("1")), -32001);
    let pair = client.ok(COMPARE, json!({}));
    assert_eq!(pair["after"]["probeQueryComplete"], false);
    assert_eq!(pair["after"]["probeCoverageComplete"], false);
    assert_eq!(pair["delta"]["p95DurationNs"], -14_000_000);
    let item = &pair["items"][0];
    assert_eq!(item["marker"], MARKER);
    assert_eq!(item["after"].get("observedSpans"), Some(&Value::Null));
    assert_eq!(item.get("deltaP95OverlapNs"), Some(&Value::Null));
}

#[test]
fn different_devices_or_observed_refresh_modes_cannot_be_compared() {
    let dir = Workspace::new();
    let after = Capture::new(&dir, false);
    let before = Capture::new(&dir, true);
    let original = read_json(&before.receipt);
    for (key, other_scope) in [
        ("deviceDigest", json!(digest(b"another-device"))),
        ("refreshRatesHz", json!([90.0])),
        ("androidApi", json!(31)),
    ] {
        let mut receipt = original.clone();
        receipt[key] = other_scope;
        json_file(&before.receipt, &receipt);
        let mut client = Client::start(&after.args(Some(&before)));
        code(client.call(COMPARE, json!({})), -32001);
    }
}

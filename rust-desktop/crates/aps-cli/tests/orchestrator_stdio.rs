use aps_analysis::frame::{FrameReport, FrameScope};
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

#[test]
fn frame_scope_is_opt_in_source_bound_and_identical_across_transports() {
    let dir = tempfile::tempdir().unwrap();
    let path = dir.path().join("selected");
    let frames: Vec<_> = (1..=9).chain([9_007_199_254_740_993_u64]).map(|id| json!({"frameId":id,"source":"PERFETTO",
        "totalDurationNs":100*id, "platformJankRuleId":"None",
        "states":{"frameTimelineJankTag":"Self Jank", "aps.trace.0":"private-probe","path":"/private/secret"},
        "stagesNs":{"Draw":19}})).collect();
    let bytes = json!({"schemaVersion":3,"frames":frames}).to_string();
    std::fs::write(&path, &bytes).unwrap();
    let cli = |args: &[&str]| {
        Command::new(env!("CARGO_BIN_EXE_aps"))
            .arg("analyze")
            .arg(&path)
            .args(args)
            .output()
            .unwrap()
    };
    let mut client = Client::start(Some(&path));
    let tools = client.request("tools/list", json!({}));
    let schema = &tools["result"]["tools"]
        .as_array()
        .unwrap()
        .iter()
        .find(|tool| tool["name"] == "aps_analyze_file")
        .unwrap()["inputSchema"];
    assert!(schema["properties"]["frameId"].is_object());
    let report = FrameReport::from_bytes(bytes.as_bytes()).unwrap();
    for (args, params, scope, count) in [
        (
            vec!["--scope", "report"],
            json!({"scope":"report"}),
            FrameScope::Report,
            8,
        ),
        (
            vec!["--frame-id", "9007199254740993"],
            json!({"frameId":"9007199254740993"}),
            FrameScope::Selected(9_007_199_254_740_993),
            1,
        ),
        (
            vec!["--frame-id", "9"],
            json!({"frameId":9}),
            FrameScope::Selected(9),
            1,
        ),
    ] {
        let out = cli(&args);
        assert!(
            out.status.success(),
            "{}",
            String::from_utf8_lossy(&out.stderr)
        );
        let doc: Value = serde_json::from_slice(&out.stdout).unwrap();
        assert_eq!(
            client.call("aps_analyze_file", params)["result"]["structuredContent"],
            doc
        );
        let item = &doc["evidence"]["items"][0];
        let shared = report.analysis_evidence(scope).unwrap();
        assert_eq!(item["id"], shared.id);
        assert_eq!(item["value"], shared.payload);
        assert_eq!(item["label"], shared.summary);
        assert_eq!(item["value"]["includedFrames"], count);
        assert!(!item.to_string().contains("private"));
        if let FrameScope::Selected(frame_id) = scope {
            assert_eq!(item["value"]["frames"][0]["frameId"], frame_id.to_string());
            assert_eq!(item["value"]["frames"][0]["reportedStageNs"]["Draw"], 19);
            assert_eq!(item["value"]["frames"][0]["timelineStatus"], "SELF_JANK");
            assert_eq!(
                item["value"]["frames"][0].get("appTraceOverlaps"),
                Some(&Value::Null)
            );
        }
    }
    for args in [
        vec!["--frame-id", "99"],
        vec!["--frame-id", "-1"],
        vec!["--scope", "report", "--frame-id", "9"],
    ] {
        let out = cli(&args);
        assert!(!out.status.success());
        assert!(out.stdout.is_empty());
    }
    for args in [
        json!({"scope":"report","frameId":9}),
        json!({"scope":"row"}),
        json!({"frameId":0.1}),
        json!({"frameId":"09"}),
        json!({"frameId":"18446744073709551616"}),
        json!({"frameId":9_007_199_254_740_993_u64}),
        json!({"frameId":99}),
    ] {
        assert!(client.call("aps_analyze_file", args)["error"].is_object());
    }
    // Legacy no-scope callers still get the original aggregate shape.
    let legacy = client.call("aps_analyze_file", json!({}));
    let default_envelope = &legacy["result"]["structuredContent"];
    assert_eq!(
        default_envelope["evidence"]["inputFormat"],
        "frame_analysis_json_v3"
    );
    let mut legacy_ids: Vec<_> = default_envelope["evidence"]["items"]
        .as_array()
        .unwrap()
        .iter()
        .map(|item| item["id"].as_str().unwrap())
        .collect();
    legacy_ids.sort_unstable();
    assert_eq!(
        legacy_ids,
        [
            "frame-analysisPolicyVersion",
            "frame-coverage",
            "frame-provenanceWarnings",
            "frame-summary"
        ]
    );
    assert_eq!(
        serde_json::from_slice::<Value>(&cli(&[]).stdout).unwrap(),
        *default_envelope
    );
    std::fs::write(&path, HAR).unwrap();
    assert_eq!(
        client.call("aps_analyze_file", json!({"frameId":9}))["error"]["code"],
        -32001
    );
    std::fs::write(&path, &bytes).unwrap();
    assert_eq!(
        client.call("aps_analyze_file", json!({"scope":"report"}))["error"]["code"],
        -32001
    );
    client.finish();
    std::fs::write(&path, HAR).unwrap();
    let mut other = Client::start(Some(&path));
    assert!(other.call("aps_analyze_file", json!({"scope":"report"}))["error"].is_object());
    assert!(!cli(&["--frame-id", "9"]).status.success());
    other.finish();
}

const FOLLOW: &str = "aps_follow_up";

fn payload(response: Value) -> Value {
    let value = &response["result"]["structuredContent"];
    assert!(value.is_object(), "{response}");
    let text = response["result"]["content"][0]["text"].as_str().unwrap();
    assert_eq!(serde_json::from_str::<Value>(text).unwrap(), *value);
    value.clone()
}

fn advertised_queries(client: &mut Client) -> Vec<String> {
    let response = client.request("tools/list", json!({}));
    let tool = response["result"]["tools"]
        .as_array()
        .unwrap()
        .iter()
        .find(|tool| tool["name"] == FOLLOW)
        .unwrap();
    assert_eq!(tool["annotations"]["readOnlyHint"], true);
    let mut queries: Vec<_> = tool["inputSchema"]["properties"]["query"]["enum"]
        .as_array()
        .unwrap()
        .iter()
        .map(|q| q.as_str().unwrap().to_owned())
        .collect();
    queries.sort_unstable();
    queries
}

fn follow(client: &mut Client, digest: &Value, query: &str, mut args: Value) -> Value {
    args["sourceDigest"] = digest.clone();
    args["query"] = json!(query);
    let page = payload(client.call(FOLLOW, args));
    assert_eq!(&page["sourceDigest"], digest);
    assert_eq!(page["query"], query);
    assert!(page["items"].is_array());
    assert!(page["remainingQueries"].is_u64());
    assert!(serde_json::to_vec(&page).unwrap().len() <= 32 * 1024);
    page
}

#[test]
fn follow_up_har_discovers_new_measured_evidence_without_leaking_raw_traffic() {
    let temp = tempfile::tempdir().unwrap();
    let path = temp.path().join("private_canary-input.har");
    let entries: Vec<_> = [11, 3, 55].into_iter().map(|duration| json!({
        "time":duration,"startedDateTime":"private_canary-date",
        "request":{"method":"private_canary-method","url":"https://private_canary.url/","headers":[{"name":"Authorization","value":"private_canary-key"}]},
        "response":{"status":200,"httpVersion":"private_canary-protocol","content":{"text":"private_canary-body"}},
        "timings":{"wait":duration}
    })).collect();
    std::fs::write(
        &path,
        json!({"log":{"version":"1.2","entries":entries}}).to_string(),
    )
    .unwrap();
    let mut client = Client::start(Some(&path));
    assert_eq!(
        advertised_queries(&mut client),
        ["evidence_item", "har_phases", "har_slowest"]
    );
    let overview = payload(client.call("aps_analyze_file", json!({})));
    let digest = &overview["input"]["sourceDigest"];
    let item = &overview["evidence"]["items"][0];
    let section = follow(
        &mut client,
        digest,
        "evidence_item",
        json!({"evidenceId":item["id"]}),
    );
    assert_eq!(section["items"], json!([item]));
    assert_eq!(section["remainingQueries"], 7);
    assert_eq!(
        payload(client.call("aps_capabilities", json!({})))["followUpRemaining"],
        7
    );

    let page = follow(&mut client, digest, "har_slowest", json!({"limit":1}));
    assert_eq!(page["total"], 3);
    assert_eq!(page["offset"], 0);
    let row = &page["items"][0];
    assert_eq!(row["value"]["entryIndex"], 2);
    assert_eq!(row["value"]["durationMs"].as_f64(), Some(55.0));
    assert_eq!(page["nextOffset"], 1);
    let next = follow(
        &mut client,
        digest,
        "har_slowest",
        json!({"offset":page["nextOffset"],"limit":2}),
    );
    assert_eq!(next["items"][0]["value"]["entryIndex"], 0);
    assert_eq!(next["nextOffset"], Value::Null);
    let phases = follow(&mut client, digest, "har_phases", json!({"limit":16}));
    assert_eq!(phases["total"], 7);
    let phase = |name| {
        phases["items"]
            .as_array()
            .unwrap()
            .iter()
            .map(|item| &item["value"])
            .find(|v| v["phase"] == name)
            .unwrap()
    };
    assert_eq!(phase("wait")["sampleCount"], 3);
    assert_eq!(phase("wait")["maxMs"].as_f64(), Some(55.0));
    assert_eq!(phase("dns")["missingCount"], 3);
    assert_eq!(phase("dns")["meanMs"], Value::Null);
    for published in [&overview, &section, &page, &next, &phases] {
        assert!(!published.to_string().contains("private_canary"));
    }
    let repeat = follow(&mut client, digest, "har_slowest", json!({"limit":1}));
    assert_eq!(repeat["items"], page["items"]);
    // A terminal empty page is valid; subsequent valid reads still cost one.
    for remaining in [2, 1, 0] {
        let empty = follow(&mut client, digest, "har_slowest", json!({"offset":3}));
        assert_eq!(empty["items"], json!([]));
        assert_eq!(empty["nextOffset"], Value::Null);
        assert_eq!(empty["remainingQueries"], remaining);
    }
    for op in ["aps_analyze_file", "aps_capabilities"] {
        assert!(client.call(op, json!({}))["result"].is_object());
        assert_eq!(
            client.call(FOLLOW, json!({"sourceDigest":digest,"query":"har_phases"}))["error"]["code"],
            -32002
        );
    }
    assert_eq!(std::fs::read_dir(temp.path()).unwrap().count(), 1);
    client.finish();
}

#[test]
fn follow_up_rejects_rebinding_and_spends_only_the_documented_budget() {
    let temp = tempfile::tempdir().unwrap();
    let path = temp.path().join("bound.har");
    std::fs::write(&path, HAR).unwrap();
    let mut unbound = Client::start(None);
    let list = unbound.request("tools/list", json!({}));
    assert!(
        list["result"]["tools"]
            .as_array()
            .unwrap()
            .iter()
            .all(|tool| tool["name"] != FOLLOW)
    );
    assert!(
        unbound.call(
            FOLLOW,
            json!({"sourceDigest":"0".repeat(64),"query":"har_phases"})
        )["error"]
            .is_object()
    );
    unbound.finish();

    let mut client = Client::start(Some(&path));
    let overview = payload(client.call("aps_analyze_file", json!({})));
    let digest = &overview["input"]["sourceDigest"];
    for args in [
        json!({"query":"har_slowest"}),
        json!({"query":"frame_rows","sourceDigest":digest}),
        json!({"query":"har_slowest","sourceDigest":digest,"limit":0}),
        json!({"query":"har_slowest","sourceDigest":digest,"limit":17}),
        json!({"query":"har_slowest","sourceDigest":digest,"offset":-1}),
        json!({"query":"har_phases","sourceDigest":digest,"offset":"0"}),
        json!({"query":"har_slowest","sourceDigest":digest,"path":"/other"}),
        json!({"query":"har_slowest","sourceDigest":digest,"url":"https://invalid.example/"}),
        json!({"query":"har_phases","sourceDigest":digest,"sql":"select *"}),
        json!({"query":"har_phases","sourceDigest":digest,"view":"all"}),
        json!({"query":"evidence_item","sourceDigest":digest}),
    ] {
        assert_eq!(
            client.call(FOLLOW, args.clone())["error"]["code"],
            -32602,
            "{args}"
        );
    }
    assert_eq!(
        client.call(
            FOLLOW,
            json!({"sourceDigest":"f".repeat(64),"query":"har_slowest"})
        )["error"]["code"],
        -32001
    );
    for args in [
        json!({"query":"har_slowest","sourceDigest":digest,"offset":1}),
        json!({"query":"evidence_item","sourceDigest":digest,"evidenceId":"unavailable"}),
    ] {
        assert!(client.call(FOLLOW, args)["error"].is_object());
    }
    assert_eq!(
        follow(&mut client, digest, "har_slowest", json!({}))["remainingQueries"],
        5
    );
    std::fs::write(&path, HAR.replace("1.2", "1.1")).unwrap();
    let args = json!({"query":"har_slowest","sourceDigest":digest});
    assert_eq!(client.call(FOLLOW, args.clone())["error"]["code"], -32001);
    std::fs::write(&path, HAR).unwrap();
    assert_eq!(client.call(FOLLOW, args)["error"]["code"], -32001);
    assert_eq!(
        client.call("aps_analyze_file", json!({}))["error"]["code"],
        -32001
    );
    client.finish();
}

#[test]
fn follow_up_frame_finds_one_miss_beyond_header_and_drills_into_that_exact_frame() {
    let temp = tempfile::tempdir().unwrap();
    let path = temp.path().join("frames.json");
    let missed_id = 9_007_199_254_740_993_u64;
    let frames: Vec<_> = (1..=9).chain([missed_id]).map(|id| json!({
        "frameId":id,"source":"PERFETTO","expectedDurationNs":16_000_000,"expectedDurationSource":"FRAME_INTERVAL",
        "eligibleForJank":true,"totalDurationNs":if id==missed_id { 20_000_000 } else { 10_000_000 },
        "windowId":"private_canary-window","states":{"aps.trace.0":"private_canary-marker"}
    })).collect();
    std::fs::write(
        &path,
        json!({"schemaVersion":3,"frames":frames}).to_string(),
    )
    .unwrap();
    let mut client = Client::start(Some(&path));
    assert_eq!(
        advertised_queries(&mut client),
        ["evidence_item", "frame_rows"]
    );
    let report = payload(client.call("aps_analyze_file", json!({"scope":"report"})));
    let digest = &report["input"]["sourceDigest"];
    assert_eq!(report["evidence"]["items"][0]["value"]["includedFrames"], 8);
    let page = follow(
        &mut client,
        digest,
        "frame_rows",
        json!({"view":"deadline_misses","limit":1}),
    );
    assert_eq!(page["total"], 1);
    let item = &page["items"][0];
    assert_eq!(item["value"]["frameId"], missed_id.to_string());
    assert!(!page.to_string().contains("private_canary"));
    assert_eq!(
        follow(
            &mut client,
            digest,
            "frame_rows",
            json!({"view":"all","offset":9,"limit":1})
        )["items"][0],
        *item
    );
    let detail = payload(client.call(
        "aps_analyze_file",
        json!({"frameId":item["value"]["frameId"]}),
    ));
    assert_eq!(
        detail["evidence"]["items"][0]["value"]["frames"][0]["frameId"],
        item["value"]["frameId"]
    );
    assert_ne!(detail["evidence"]["items"][0]["id"], item["id"]);
    client.finish();
}

#[test]
fn startup_csv_and_desktop_json_share_cli_mcp_evidence_and_bounded_run_filters() {
    let temp = tempfile::tempdir().unwrap();
    let csv = "iteration,runId,requestedType,observedType,displayedTimeMs,fullyDrawnTimeMs,totalTimeMs,warnings\n1,PRIVATE-r1,COLD,UNKNOWN,,,100,PRIVATE-log\n2,PRIVATE-r2,COLD,WARM,67,120,100,\n3,PRIVATE-r3,HOT,HOT,25,,100,\n";
    let run = |iteration, requested, observed, ttid, ttfd| {
        json!({
            "iteration":iteration,"runId":format!("PRIVATE-r{iteration}"),
            "requestedType":requested,"observedType":observed,"displayedTimeMs":ttid,
            "fullyDrawnTimeMs":ttfd,"totalTimeMs":100,
            "rawEvidence":{"amStartOutput":"PRIVATE-log"},"traceEvidence":{"file":"PRIVATE-trace"}
        })
    };
    let report = json!({"schemaVersion":1,"warnings":["PRIVATE-warning"],"runs":[
        run(1,"COLD","UNKNOWN",None,None),
        run(2,"COLD","WARM",Some(67),Some(120)),
        run(3,"HOT","HOT",Some(25),None),
    ]})
    .to_string();

    for (file, format, bytes) in [
        ("startup.csv", "startup-csv", csv),
        ("report.json", "startup-json-v1", &report),
    ] {
        let path = temp.path().join(file);
        std::fs::write(&path, bytes).unwrap();
        let cli = |args: &[&str]| {
            let output = Command::new(env!("CARGO_BIN_EXE_aps"))
                .arg("analyze")
                .arg(&path)
                .args(args)
                .output()
                .unwrap();
            assert!(
                output.status.success(),
                "{}",
                String::from_utf8_lossy(&output.stderr)
            );
            serde_json::from_slice::<Value>(&output.stdout).unwrap()
        };
        let mut client = Client::start(Some(&path));
        assert_eq!(
            advertised_queries(&mut client),
            ["evidence_item", "startup_runs"]
        );
        let overview = payload(client.call("aps_analyze_file", json!({})));
        assert_eq!(overview, cli(&[]));
        assert_eq!(overview, cli(&["--kind", "startup"]));
        assert_eq!(overview["evidence"]["inputFormat"], format);
        let digest = &overview["input"]["sourceDigest"];
        let summary = &overview["evidence"]["items"][0];
        assert_eq!(summary["id"], "startup-summary");
        assert_eq!(summary["value"]["ttid"]["missing"], 1);
        assert_eq!(summary["value"]["ttfd"]["missing"], 2);
        assert_eq!(summary["value"]["requestedObservedMismatches"], 1);
        assert_eq!(
            follow(
                &mut client,
                digest,
                "evidence_item",
                json!({"evidenceId":summary["id"]})
            )["items"],
            json!([summary])
        );

        let filter = json!({"requestedMode":"cold","observedMode":"unknown","missingMetric":"ttid","limit":1});
        let page = follow(&mut client, digest, "startup_runs", filter);
        assert_eq!(page["total"], 1);
        assert_eq!(page["nextOffset"], Value::Null);
        let row = &page["items"][0];
        assert_eq!(row["value"]["observedMode"], "unknown");
        assert_eq!(row["value"]["ttidMs"], Value::Null);
        assert_eq!(row["value"]["ttfdMs"], Value::Null);
        assert_eq!(row["value"]["amTotalTimeMs"], 100);
        assert!(!page.to_string().contains("PRIVATE"));
        assert!(!overview.to_string().contains("PRIVATE"));
        let cli_page = cli(&[
            "--scope",
            "startup-runs",
            "--requested-mode",
            "cold",
            "--observed-mode",
            "unknown",
            "--missing-metric",
            "ttid",
            "--limit",
            "1",
        ]);
        assert_eq!(cli_page["input"]["sourceDigest"], *digest);
        assert_eq!(cli_page["evidence"]["items"], json!([summary, row]));
        assert_eq!(cli_page["evidence"]["page"]["total"], 1);
        let first = follow(&mut client, digest, "startup_runs", json!({"limit":1}));
        assert_eq!(first["items"][0], *row);
        let second = follow(
            &mut client,
            digest,
            "startup_runs",
            json!({"offset":first["nextOffset"],"limit":1}),
        );
        assert_eq!(second["items"][0]["value"]["ttfdMs"], 120);

        for args in [
            json!({"requestedMode":"COLD"}),
            json!({"observedMode":null}),
            json!({"missingMetric":"amTotalTime"}),
            json!({"path":"other.csv"}),
            json!({"offset":4}),
        ] {
            let mut args = args;
            args["query"] = "startup_runs".into();
            args["sourceDigest"] = digest.clone();
            assert_eq!(client.call(FOLLOW, args)["error"]["code"], -32602);
        }
        std::fs::write(&path, format!("{bytes} ")).unwrap();
        assert_eq!(
            client.call(
                FOLLOW,
                json!({"query":"startup_runs","sourceDigest":digest})
            )["error"]["code"],
            -32001
        );
        std::fs::write(&path, bytes).unwrap();
        assert_eq!(
            client.call(
                FOLLOW,
                json!({"query":"startup_runs","sourceDigest":digest})
            )["error"]["code"],
            -32001
        );
        client.finish();
    }
}

#[test]
fn startup_cli_rejects_malformed_reports_and_incompatible_selections() {
    let temp = tempfile::tempdir().unwrap();
    let path = temp.path().join("input");
    let cli = |args: &[&str]| {
        let output = Command::new(env!("CARGO_BIN_EXE_aps"))
            .arg("analyze")
            .arg(&path)
            .args(args)
            .output()
            .unwrap();
        assert!(!output.status.success());
        assert!(output.stdout.is_empty());
        String::from_utf8(output.stderr).unwrap()
    };
    std::fs::write(&path, HAR).unwrap();
    assert!(cli(&["--kind", "startup"]).contains("E_STARTUP_JSON"));
    assert!(cli(&["--scope", "startup-runs", "--kind", "trace"]).contains("E_ANALYSIS_SCOPE"));
    assert!(cli(&["--kind", "startup", "--frame-id", "1"]).contains("E_ANALYSIS_SCOPE"));
    assert!(cli(&["--requested-mode", "warm"]).contains("E_ANALYSIS_SCOPE"));
    assert!(cli(&["--scope", "startup-runs", "--limit", "17"]).contains("E_STARTUP_QUERY"));
    let raw = json!({"schemaVersion":2,"runs":[{"iteration":1,"runId":"r","requestedType":"COLD",
        "observedType":"UNKNOWN","rawEvidence":{"amStartOutput":"PRIVATE-raw"}}]});
    std::fs::write(&path, raw.to_string()).unwrap();
    let error = cli(&[]);
    assert!(error.contains("E_STARTUP_JSON") && !error.contains("PRIVATE"));
    let mut ambiguous = raw;
    ambiguous["log"] = json!({});
    std::fs::write(&path, ambiguous.to_string()).unwrap();
    assert!(cli(&[]).contains("ambiguous JSON artifact"));
}

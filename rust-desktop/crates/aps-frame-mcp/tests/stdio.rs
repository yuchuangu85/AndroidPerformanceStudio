use aps_contracts::SourceDigest;
use serde_json::{Value, json};
use std::fs;
use std::io::{BufRead, BufReader, Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, ChildStdout, Command, Stdio};
use std::sync::atomic::{AtomicUsize, Ordering};

const BIN: &str = env!("CARGO_BIN_EXE_aps-frame-mcp");

struct Fixture(PathBuf);

impl Fixture {
    fn new(contents: &[u8]) -> Self {
        static NEXT: AtomicUsize = AtomicUsize::new(0);
        let dir = loop {
            let dir = std::env::temp_dir().join(format!(
                "aps-frame-stdio-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            match fs::create_dir(&dir) {
                Ok(()) => break dir,
                Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => continue,
                Err(error) => panic!("create test directory: {error}"),
            }
        };
        let path = dir.join("selected-input");
        let fixture = Self(path);
        fs::write(&fixture.0, contents).unwrap();
        fixture
    }

    fn report(frames: Vec<Value>) -> Self {
        Self::new(
            json!({
                "schemaVersion":3,
                "summary":{"totalFrames":999,"deadlineMissRate":1.0},
                "clusters":[{"id":999,"memberCount":999}],
                "attributions":[{"windowId":"untrusted-derived-group"}],
                "frames":frames
            })
            .to_string()
            .as_bytes(),
        )
    }
}

impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(self.0.parent().unwrap());
    }
}

struct Server {
    child: Child,
    stdin: Option<ChildStdin>,
    stdout: BufReader<ChildStdout>,
    next_id: u64,
}

impl Server {
    fn start(path: &Path) -> Self {
        let mut child = Command::new(BIN)
            .arg("--input")
            .arg(path)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .unwrap();
        Self {
            stdin: child.stdin.take(),
            stdout: BufReader::new(child.stdout.take().unwrap()),
            child,
            next_id: 1,
        }
    }

    fn write(&mut self, bytes: &[u8]) {
        let input = self.stdin.as_mut().unwrap();
        input.write_all(bytes).unwrap();
        input.flush().unwrap();
    }

    fn read(&mut self) -> Value {
        let mut line = String::new();
        if self.stdout.read_line(&mut line).unwrap() == 0 {
            let mut error = String::new();
            self.child
                .stderr
                .as_mut()
                .unwrap()
                .read_to_string(&mut error)
                .unwrap();
            panic!("server closed stdout: {error}");
        }
        serde_json::from_str(&line).expect("stdout must be JSON Lines")
    }

    fn request(&mut self, method: &str, params: Value) -> Value {
        let id = self.next_id;
        self.next_id += 1;
        self.write(
            format!(
                "{}\n",
                json!({"jsonrpc":"2.0","id":id,"method":method,"params":params})
            )
            .as_bytes(),
        );
        let response = self.read();
        assert_eq!(response["id"], id);
        assert_eq!(response["jsonrpc"], "2.0");
        response
    }

    fn call(&mut self, name: &str, arguments: Value) -> Value {
        self.request("tools/call", json!({"name":name,"arguments":arguments}))
    }

    fn tool(&mut self, name: &str, arguments: Value) -> Value {
        let response = self.call(name, arguments);
        assert_eq!(response["result"]["isError"], false, "{response}");
        let payload = &response["result"]["structuredContent"];
        let text: Value =
            serde_json::from_str(response["result"]["content"][0]["text"].as_str().unwrap())
                .unwrap();
        assert_eq!(payload, &text);
        payload.clone()
    }

    fn finish(mut self) {
        self.stdin.take();
        let mut trailing = String::new();
        self.stdout.read_to_string(&mut trailing).unwrap();
        let mut stderr = String::new();
        self.child
            .stderr
            .as_mut()
            .unwrap()
            .read_to_string(&mut stderr)
            .unwrap();
        assert!(self.child.wait().unwrap().success(), "{stderr}");
        assert!(trailing.is_empty(), "{trailing}");
        assert!(stderr.is_empty(), "{stderr}");
    }
}

impl Drop for Server {
    fn drop(&mut self) {
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

fn perfetto_frame(id: u64, vsync: i64, duration: i64) -> Value {
    json!({
        "frameId":id,"source":"PERFETTO","activityName":"Home","windowId":"main",
        "pageName":"Feed","interactionState":"scroll",
        "intendedVsyncNs":vsync,"frameCompletedNs":vsync + duration,
        "expectedDurationNs":16_000_000,"expectedDurationSource":"FRAME_INTERVAL",
        "totalDurationNs":duration,"frameTimelineVsyncId":77,"eligibleForJank":true,
        "states":{"privateState":"omit-this-state"},
        "deadlineVerdict":"MET"
    })
}

fn ids(page: &Value, key: &str) -> Vec<Value> {
    page["items"]
        .as_array()
        .unwrap()
        .iter()
        .map(|row| row[key].clone())
        .collect()
}

#[test]
fn five_stdio_tools_share_bound_identity_and_filtered_frame_evidence() {
    let mut first = perfetto_frame(u64::MAX, 100_000_000, 30_000_000);
    first["platformJank"] = json!(true);
    first["stagesNs"] = json!({"Draw":20_000_000});
    let mut smooth = perfetto_frame(502, 116_000_000, 10_000_000);
    smooth["platformJank"] = json!(false);
    smooth["jankStatsJank"] = json!(true);
    let mut last_miss = perfetto_frame(503, 132_000_000, 20_000_000);
    last_miss["jankStatsJank"] = json!(false);
    // Keep the nonmatching window outside the primary cluster (the analyzer
    // does not bridge windows), while still inside the filter time interval.
    let mut other_window = perfetto_frame(504, 92_000_000, 10_000_000);
    other_window["windowId"] = json!("secondary");
    let mut other_source = perfetto_frame(506, 94_000_000, 10_000_000);
    other_source["source"] = json!("FRAME_METRICS");
    let at_end = perfetto_frame(505, 148_000_000, 9_000_000);
    let source = Fixture::report(vec![
        last_miss,
        first,
        other_source,
        at_end,
        other_window,
        smooth,
    ]);
    let digest = SourceDigest::hash_file(&source.0, 64 * 1024)
        .unwrap()
        .to_hex();
    let mut server = Server::start(&source.0);
    let initialize = server.request(
        "initialize",
        json!({
            "protocolVersion":"2024-11-05","capabilities":{},
            "clientInfo":{"name":"aps-frame-test","version":"1"}
        }),
    );
    assert_eq!(initialize["result"]["protocolVersion"], "2024-11-05");
    assert_eq!(initialize["result"]["serverInfo"]["name"], "aps-frame-mcp");
    server.write(b"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n");
    let listed = server.request("tools/list", json!({}));
    let tools = listed["result"]["tools"].as_array().unwrap();
    assert_eq!(
        tools
            .iter()
            .map(|tool| tool["name"].as_str().unwrap())
            .collect::<Vec<_>>(),
        [
            "frame_session_summary",
            "frame_list",
            "frame_detail",
            "frame_problem_clusters",
            "frame_attributions"
        ]
    );
    assert!(
        tools
            .iter()
            .all(|tool| tool["annotations"]["readOnlyHint"] == true
                && tool["inputSchema"]["additionalProperties"] == false)
    );

    let filter =
        json!({"source":"PERFETTO","windowId":"main","startNs":90_000_000,"endNs":148_000_000});
    let summary = server.tool("frame_session_summary", filter.clone());
    let session_id = summary["sessionId"].as_str().unwrap();
    assert_eq!(summary["provenanceComplete"], false);
    assert_eq!(summary["coverage"]["inputFrames"], 6);
    assert_eq!(summary["coverage"]["matchedFrames"], 3);
    let stats = &summary["summary"];
    assert_eq!(stats["totalFrames"], 3);
    assert_eq!(stats["deadlineClassifiedFrames"], 3);
    assert_eq!(stats["deadlineMissFrames"], 2);
    assert_eq!(stats["platformClassifiedFrames"], 2);
    assert_eq!(stats["platformJankRate"], 0.5);
    assert_eq!(stats["jankStatsClassifiedFrames"], 2);
    assert_eq!(stats["jankStatsJankRate"], 0.5);
    assert_eq!(stats["p95DurationNs"], 30_000_000);
    assert_eq!(summary["observedCapabilities"]["stageBreakdown"], true);

    let mut page_args = filter.clone();
    page_args["limit"] = json!(2);
    let page = server.tool("frame_list", page_args.clone());
    assert_eq!(
        ids(&page, "frameId"),
        json!([u64::MAX.to_string(), "502"])
            .as_array()
            .unwrap()
            .clone()
    );
    page_args["offset"] = page["page"]["nextOffset"].clone();
    let second = server.tool("frame_list", page_args);
    assert_eq!(ids(&second, "frameId"), vec![json!("503")]);
    assert_eq!(page["page"]["total"], stats["totalFrames"]);
    assert_eq!(second["page"]["nextOffset"], Value::Null);
    assert_eq!(page["items"][0]["deadlineVerdict"], "MISSED");

    let mut detail_args = filter.clone();
    detail_args["sessionId"] = json!(session_id);
    detail_args["frameId"] = page["items"][0]["frameId"].clone();
    let detail = server.tool("frame_detail", detail_args.clone());
    assert_eq!(detail["frame"]["frameTimelineVsyncId"], "77");
    assert_eq!(detail["frame"]["stagesNs"]["draw"], 20_000_000);
    assert_eq!(detail["frame"]["stagesNs"]["gpu"], Value::Null);
    assert_eq!(detail["frame"]["refreshRateHz"], Value::Null);
    detail_args["frameId"] = json!("77");
    assert_eq!(
        server.call("frame_detail", detail_args.clone())["error"]["code"],
        -32001
    );
    detail_args["frameId"] = json!(u64::MAX.to_string());
    detail_args["sessionId"] = json!("stale-session");
    assert_eq!(
        server.call("frame_detail", detail_args)["error"]["code"],
        -32001
    );

    let clusters = server.tool("frame_problem_clusters", filter.clone());
    assert_eq!(clusters["page"]["total"], 1);
    assert_eq!(
        clusters["items"][0]["memberCount"],
        stats["deadlineMissFrames"]
    );
    assert_eq!(clusters["items"][0]["dominantReportedStage"], "Draw");
    assert_eq!(clusters["items"][0]["durationNs"], 52_000_000);
    let attributions = server.tool("frame_attributions", filter.clone());
    assert_eq!(attributions["page"]["total"], 1);
    assert_eq!(attributions["items"][0]["pageName"], "Feed");
    assert_eq!(
        attributions["items"][0]["totalFrames"],
        stats["totalFrames"]
    );
    assert_eq!(
        attributions["items"][0]["evidenceKind"],
        "context_group_not_cause"
    );

    for payload in [&summary, &page, &second, &detail, &clusters, &attributions] {
        assert_eq!(payload["sourceDigest"], digest);
        assert_eq!(payload["sessionId"], session_id);
        assert_eq!(
            payload["analysisPolicyVersion"],
            summary["analysisPolicyVersion"]
        );
        assert_eq!(payload["filter"], summary["filter"]);
        let text = payload.to_string();
        assert!(!text.contains("omit-this-state"));
        assert!(!text.contains(source.0.to_str().unwrap()));
        assert!(!text.contains("untrusted-derived-group"));
    }
    server.finish();
}

#[test]
fn cluster_members_page_without_reconnecting_clusters_after_filtering() {
    let mut frames: Vec<Value> = (0..22)
        .map(|i| perfetto_frame(i, 100_000_000 + i as i64 * 16_000_000, 30_000_000))
        .collect();
    let separator = json!({"frameId":100,"source":"PERFETTO","windowId":"secondary","intendedVsyncNs":452_000_000,"eligibleForJank":true});
    frames.push(separator);
    frames.push(perfetto_frame(101, 468_000_000, 30_000_000));
    let source = Fixture::report(frames);
    let mut server = Server::start(&source.0);
    let mut args = json!({"windowId":"main","view":"deadline_misses","limit":1});
    let page = server.tool("frame_problem_clusters", args.clone());
    assert_eq!(page["page"]["total"], 2);
    let cluster = &page["items"][0];
    assert_eq!(cluster["memberCount"], 22);
    assert_eq!(
        cluster["deadlineMissFrameIds"].as_array().unwrap().len(),
        20
    );
    assert_eq!(cluster["memberPage"]["nextOffset"], 20);
    args["memberOffset"] = cluster["memberPage"]["nextOffset"].clone();
    let remaining = server.tool("frame_problem_clusters", args.clone());
    assert_eq!(remaining["items"][0]["clusterId"], cluster["clusterId"]);
    assert_eq!(
        remaining["items"][0]["deadlineMissFrameIds"],
        json!(["20", "21"])
    );
    assert_eq!(
        remaining["items"][0]["memberPage"]["nextOffset"],
        Value::Null
    );

    args.as_object_mut().unwrap().remove("memberOffset");
    args["offset"] = page["page"]["nextOffset"].clone();
    let next_cluster = server.tool("frame_problem_clusters", args);
    assert_eq!(
        next_cluster["items"][0]["deadlineMissFrameIds"],
        json!(["101"])
    );
    server.finish();
}

#[test]
fn gfxinfo_has_no_invented_budget_or_jank_signal_and_cached_pages_check_source() {
    let text = b"Window: com.example/.MainActivity\n---PROFILEDATA---\nFlags,IntendedVsync,Vsync,FrameCompleted\n0,100000000,100000000,125000000\n---PROFILEDATA---\n";
    let source = Fixture::new(text);
    let mut server = Server::start(&source.0);
    let summary = server.tool("frame_session_summary", json!({}));
    assert_eq!(summary["summary"]["totalFrames"], 1);
    assert_eq!(summary["summary"]["p95DurationNs"], 25_000_000);
    assert_eq!(summary["summary"]["deadlineUnknownFrames"], 1);
    for metric in ["deadlineMissRate", "platformJankRate", "jankStatsJankRate"] {
        assert_eq!(summary["summary"][metric], Value::Null);
    }

    let args = json!({"source":"GFXINFO","windowId":"com.example/.MainActivity"});
    let page = server.tool("frame_list", args.clone());
    assert_eq!(page["page"]["total"], 1);
    assert_eq!(page["items"][0]["windowId"], "com.example/.MainActivity");
    assert_eq!(page["items"][0]["expectedDurationNs"], Value::Null);
    assert_eq!(page["items"][0]["expectedDurationSource"], "UNKNOWN");
    assert_eq!(page["items"][0]["platformJank"], Value::Null);
    let changed = String::from_utf8_lossy(text).replace("125000000", "126000000");
    assert_eq!(changed.len(), text.len());
    fs::write(&source.0, changed).unwrap();
    assert_eq!(
        server.call("frame_list", args.clone())["error"]["code"],
        -32001
    );
    fs::write(&source.0, text).unwrap();
    assert_eq!(server.call("frame_list", args)["error"]["code"], -32001);
    server.finish();
}

#[test]
fn path_like_labels_are_omitted_without_combining_private_contexts_or_losing_evidence() {
    let mut first = perfetto_frame(1, 100_000_000, 30_000_000);
    first["windowId"] = json!("/tmp/secret-window");
    first["activityName"] = json!("file:/home/secret-activity");
    first["interactionState"] = json!("repo/tree/secret-state");
    first["pageName"] = json!("report.path/secret-page.json");
    first["fragmentName"] = json!("fragment%2Fsecret-fragment");
    first["renderThreadName"] = json!(" RenderThread(C:secret-render)");
    first["surfaceFlingerJankType"] = json!(r"\host\secret-surface");
    first["platformJankRuleId"] = json!("opened /tmp/secret-rule");
    first["platformJankRuleVersion"] = json!("control\u{202e}secret-version");
    first["jankStatsRuleId"] = json!("https://example.invalid/secret-jank-rule");
    first["jankStatsRuleVersion"] = json!("content:/secret-jank-version");
    first["states"] = json!({"screen":"/path/secret-screen","privateKey":"secret-raw-state"});
    let mut second = first.clone();
    second["frameId"] = json!(2);
    second["windowId"] = json!("hidden\u{202e}different-secret-window");
    second["intendedVsyncNs"] = json!(116_000_000);
    second["frameCompletedNs"] = json!(146_000_000);

    let mut smooth = perfetto_frame(3, 132_000_000, 12_000_000);
    smooth["windowId"] = json!("com.example/com.example.MainActivity");
    smooth["jankStatsReasons"] = json!(["source at /tmp/secret-reason"]);
    let source = Fixture::report(vec![first, second, smooth]);
    let mut server = Server::start(&source.0);
    let common =
        json!({"source":"PERFETTO","view":"problems","startNs":100_000_000,"endNs":148_000_000});
    let summary = server.tool("frame_session_summary", common.clone());
    assert_eq!(summary["coverage"]["matchedFrames"], 3);
    assert_eq!(summary["summary"]["deadlineMissFrames"], 2);
    assert_eq!(summary["analysisPolicyVersion"], "aps-frame-v2");
    let listed = server.tool("frame_list", common.clone());
    assert_eq!(
        ids(&listed, "frameId"),
        vec![json!("1"), json!("2"), json!("3")]
    );
    assert!(listed["items"][0]["windowId"].is_null());
    assert!(listed["items"][0]["activityName"].is_null());
    assert_eq!(
        listed["items"][2]["windowId"],
        "com.example/com.example.MainActivity"
    );
    let clusters = server.tool("frame_problem_clusters", common.clone());
    assert_eq!(clusters["items"].as_array().unwrap().len(), 2);
    assert_eq!(clusters["items"][0]["deadlineMissFrameIds"], json!(["1"]));
    assert_eq!(clusters["items"][1]["deadlineMissFrameIds"], json!(["2"]));
    let attributions = server.tool("frame_attributions", common.clone());
    assert_eq!(attributions["items"].as_array().unwrap().len(), 3);
    let mut detail_args = common;
    detail_args["sessionId"] = summary["sessionId"].clone();
    detail_args["frameId"] = json!("1");
    let detail = server.tool("frame_detail", detail_args.clone());
    assert!(detail["frame"]["platformJankRuleId"].is_null());
    assert!(detail["frame"]["jankStatsRuleVersion"].is_null());
    detail_args["frameId"] = json!("3");
    let reason = server.tool("frame_detail", detail_args);
    assert_eq!(reason["frame"]["jankStatsReasons"], json!([]));
    assert_eq!(reason["frame"]["jankStatsReasonsRedacted"], true);
    for payload in [
        &summary,
        &listed,
        &clusters,
        &attributions,
        &detail,
        &reason,
    ] {
        for key in [
            "sourceDigest",
            "analysisPolicyVersion",
            "sessionId",
            "filter",
        ] {
            assert_eq!(payload[key], summary[key], "{key}");
        }
        assert!(!payload.to_string().contains("secret-"), "{payload}");
        assert!(!payload.to_string().contains("\\u0000"), "{payload}");
    }
    let path_filter = server.call("frame_list", json!({"windowId":"/tmp/secret-window"}));
    assert_eq!(path_filter["error"]["code"], -32602);
    assert!(!path_filter.to_string().contains("secret-window"));
    server.finish();
}

#[test]
fn gfxinfo_does_not_project_path_like_windows_or_retained_raw_columns() {
    let text = b"Window: /secret/raw-window\n---PROFILEDATA---\nFlags,IntendedVsync,Vsync,FrameCompleted,secret-column\n0,100000000,100000000,125000000,1234567\n---PROFILEDATA---\n";
    let input = Fixture::new(text);
    let mut server = Server::start(&input.0);
    let result = server.tool("frame_list", json!({}));
    assert_eq!(result["items"][0]["windowId"], Value::Null);
    assert_eq!(result["items"][0]["durationNs"], 25_000_000);
    assert!(!result.to_string().contains("secret"));
    assert!(!result.to_string().contains("1234567"));
    server.finish();
}

#[test]
fn malformed_requests_and_invalid_tool_arguments_do_not_poison_the_stdio_session() {
    let source = Fixture::report(vec![perfetto_frame(12, 100_000_000, 30_000_000)]);
    let mut server = Server::start(&source.0);
    server.write(b"{oops\n");
    assert_eq!(server.read()["error"]["code"], -32700);
    let mut over_limit = vec![b'x'; 64 * 1024 + 1];
    over_limit.push(b'\n');
    server.write(&over_limit);
    assert_eq!(server.read()["error"]["code"], -32600);
    for args in [
        json!({"path":source.0}),
        json!({"limit":101}),
        json!({"startNs":10,"endNs":10}),
        json!({"memberOffset":1}),
        json!({"offset":-1}),
        json!({"source":"unknown"}),
    ] {
        let response = server.call("frame_list", args);
        assert_eq!(response["error"]["code"], -32602);
        assert!(!response.to_string().contains(source.0.to_str().unwrap()));
    }
    assert_eq!(
        server.call("frame_detail", json!({"sessionId":"any","frameId":12}))["error"]["code"],
        -32602
    );
    assert_eq!(
        server.request("shell/exec", json!({}))["error"]["code"],
        -32601
    );
    assert_eq!(server.tool("frame_list", json!({}))["page"]["total"], 1);
    server.finish();
}

#[test]
fn large_output_is_rejected_and_the_same_query_can_resume_at_a_smaller_page_size() {
    let mut frames = Vec::new();
    for group in 0..100 {
        for row in 0..4 {
            let id = group * 4 + row;
            let mut frame = perfetto_frame(id, 100_000_000 + id as i64 * 16_000_000, 9_000_000);
            frame["windowId"] = json!(format!("group-{group}"));
            frame["renderThreadName"] = json!(format!("{row}{}", "r".repeat(499)));
            frame["surfaceFlingerJankType"] = json!(format!("{row}{}", "s".repeat(499)));
            frame["activityName"] = json!("a".repeat(500));
            frame["interactionState"] = json!("i".repeat(500));
            frame["pageName"] = json!("p".repeat(500));
            frames.push(frame);
        }
    }
    let source = Fixture::report(frames);
    let mut server = Server::start(&source.0);
    assert_eq!(
        server.call("frame_attributions", json!({"limit":100}))["error"]["code"],
        -32002
    );
    let small = server.tool("frame_attributions", json!({"limit":1}));
    assert_eq!(small["page"]["total"], 100);
    assert_eq!(small["page"]["nextOffset"], 1);
    server.finish();
}

#[test]
fn invalid_selected_inputs_fail_before_listening_without_echoing_the_path() {
    for bytes in [
        br#"{"schemaVersion":2,"frames":[{"frameId":1,"source":"PERFETTO"}]}"#.as_slice(),
        br#"{"schemaVersion":3,"frames":[]}"#,
        br#"{"schemaVersion":3,"frames":[{"frameId":1}]}"#,
        br#"{"schemaVersion":3,"frames":[{"frameId":1,"source":"PERFETTO","eligibleForJank":true},{"frameId":1,"source":"GFXINFO","eligibleForJank":true}]}"#,
    ] {
        let source = Fixture::new(bytes);
        let output = Command::new(BIN)
            .arg("--input")
            .arg(&source.0)
            .stdin(Stdio::null())
            .output()
            .unwrap();
        assert!(!output.status.success(), "unexpectedly accepted {bytes:?}");
        assert!(output.stdout.is_empty());
        assert!(!output.stderr.is_empty());
        assert!(!String::from_utf8_lossy(&output.stderr).contains(source.0.to_str().unwrap()));
    }
}

//! End-to-end snapshot diff contract, using real HPROF sources and worker processes.

use aps_hprof_engine::cache::sha256_file;
use aps_memory::{MemorySession, MemorySnapshot, compare_snapshots};
use aps_platform::{
    CancellationToken, ProcessOutput, ProcessRequest, ProcessRunner, TempWorkspace,
};
use serde_json::{Value, json};
use std::ffi::OsString;
use std::fs;
use std::io::{BufRead, BufReader, Read, Write};
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, Command, ExitStatus, Stdio};
use std::sync::mpsc::{self, Receiver, RecvTimeoutError};
use std::thread::{self, JoinHandle};
use std::time::{Duration, Instant};

const MCP: &str = env!("CARGO_BIN_EXE_aps-mcp");
const WORKER: &str = env!("CARGO_BIN_EXE_aps-memory-worker");
const RESPONSE_TIMEOUT: Duration = Duration::from_secs(15);
const BEFORE: &[(&str, u32, u32)] = &[
    ("sample.Removed", 2, 16),
    ("sample.Zeta", 1, 32),
    ("sample.Shrinks", 4, 24),
    ("sample.Alpha", 1, 32),
    ("sample.Steady", 1, 8),
];
const AFTER: &[(&str, u32, u32)] = &[
    ("sample.Added", 2, 16),
    ("sample.Zeta", 3, 32),
    ("sample.Shrinks", 1, 24),
    ("sample.Alpha", 3, 32),
    ("sample.Steady", 1, 8),
];

struct MinimalHprof {
    bytes: Vec<u8>,
    dump: Vec<u8>,
    next_object: u64,
}

impl MinimalHprof {
    fn new(timestamp: u64) -> Self {
        let mut bytes = b"JAVA PROFILE 1.0.3\0".to_vec();
        bytes.extend(8_u32.to_be_bytes());
        bytes.extend(timestamp.to_be_bytes());
        Self {
            bytes,
            dump: Vec::new(),
            next_object: 0x1000,
        }
    }

    fn record(&mut self, tag: u8, data: &[u8]) {
        self.bytes.push(tag);
        self.bytes.extend(0_u32.to_be_bytes());
        self.bytes
            .extend(u32::try_from(data.len()).unwrap().to_be_bytes());
        self.bytes.extend(data);
    }

    fn class(&mut self, id: u64, name: &str, count: u32, size: u32) {
        let string_id = id + 0x10000;
        let mut string = string_id.to_be_bytes().to_vec();
        string.extend(name.as_bytes());
        self.record(0x01, &string);
        let mut load = u32::try_from(id).unwrap().to_be_bytes().to_vec();
        load.extend(id.to_be_bytes());
        load.extend(0_u32.to_be_bytes());
        load.extend(string_id.to_be_bytes());
        self.record(0x02, &load);

        self.dump.push(0x20);
        self.dump.extend(id.to_be_bytes());
        self.dump.extend(0_u32.to_be_bytes());
        self.dump.extend([0; 48]);
        self.dump.extend(size.to_be_bytes());
        self.dump.extend([0; 6]);
        self.dump.push(0x05);
        self.dump.extend(id.to_be_bytes());
        for _ in 0..count {
            let object_id = self.next_object;
            self.next_object += 1;
            self.dump.push(0x21);
            self.dump.extend(object_id.to_be_bytes());
            self.dump.extend(0_u32.to_be_bytes());
            self.dump.extend(id.to_be_bytes());
            self.dump.extend(size.to_be_bytes());
            self.dump.extend(vec![0; size as usize]);
            // Independent roots make each instance's retained size its shallow size.
            self.dump.push(0xff);
            self.dump.extend(object_id.to_be_bytes());
        }
    }

    fn finish(mut self) -> Vec<u8> {
        let dump = std::mem::take(&mut self.dump);
        self.record(0x1c, &dump);
        self.record(0x2c, &[]);
        self.bytes
    }
}

fn write_hprof(path: &Path, timestamp: u64, classes: &[(&str, u32, u32)]) {
    let mut hprof = MinimalHprof::new(timestamp);
    for (index, &(name, count, size)) in classes.iter().enumerate() {
        hprof.class(100 + index as u64, name, count, size);
    }
    fs::write(path, hprof.finish()).unwrap();
}

struct Sources {
    workspace: TempWorkspace,
    before: PathBuf,
    after: PathBuf,
    before_digest: String,
    after_digest: String,
}

impl Sources {
    fn new() -> Self {
        let workspace = TempWorkspace::create(&std::env::temp_dir()).unwrap();
        let before = workspace.path().join("private baseline A.hprof");
        let after = workspace.path().join("private selected B.hprof");
        write_hprof(&before, 1, BEFORE);
        write_hprof(&after, 2, AFTER);
        Self {
            before_digest: sha256_file(&before).unwrap(),
            after_digest: sha256_file(&after).unwrap(),
            workspace,
            before,
            after,
        }
    }

    fn args(&self, baseline: bool) -> Vec<OsString> {
        let mut args = vec![
            "--hprof".into(),
            self.after.as_os_str().to_owned(),
            "--worker".into(),
            WORKER.into(),
        ];
        if baseline {
            args.extend([
                "--baseline-hprof".into(),
                self.before.as_os_str().to_owned(),
            ]);
        }
        args
    }

    fn assert_path_free(&self, text: &str) {
        for path in [&self.before, &self.after] {
            assert!(!text.contains(path.file_name().unwrap().to_str().unwrap()));
        }
        assert!(!text.contains(self.workspace.path().to_str().unwrap()));
        assert!(!text.contains(MCP));
        assert!(!text.contains(WORKER));
    }

    fn query(&self, baseline: bool, calls: &[Value]) -> Vec<Value> {
        self.query_args(&self.args(baseline), calls)
    }

    fn query_args(&self, args: &[OsString], calls: &[Value]) -> Vec<Value> {
        let mut requests = vec![
            json!({"jsonrpc":"2.0","id":"initialize","method":"initialize"}),
            json!({"jsonrpc":"2.0","method":"notifications/initialized"}),
            json!({"jsonrpc":"2.0","id":"catalog","method":"tools/list"}),
        ];
        requests.extend_from_slice(calls);
        let input = requests
            .iter()
            .map(|value| format!("{value}\n"))
            .collect::<String>();
        let output = run_cli(args, input);
        assert!(
            output.status.success(),
            "MCP failed: {}",
            String::from_utf8_lossy(&output.stderr)
        );
        assert!(
            output.stderr.is_empty(),
            "{}",
            String::from_utf8_lossy(&output.stderr)
        );
        let stdout = String::from_utf8(output.stdout).unwrap();
        self.assert_path_free(&stdout);
        let responses = stdout
            .lines()
            .map(|line| serde_json::from_str(line).unwrap())
            .collect::<Vec<Value>>();
        assert_eq!(responses.len(), calls.len() + 2, "{responses:?}");
        assert_eq!(responses[0]["id"], "initialize");
        assert_eq!(responses[1]["id"], "catalog");
        for (call, response) in calls.iter().zip(&responses[2..]) {
            assert_eq!(response["jsonrpc"], "2.0");
            assert_eq!(response["id"], call["id"]);
        }
        responses
    }
}

fn run_cli(args: &[OsString], input: String) -> ProcessOutput {
    let mut request = ProcessRequest::new(MCP).args(args).stdin_bytes(input);
    request.timeout = Duration::from_secs(30);
    ProcessRunner
        .run(&request, &CancellationToken::new())
        .unwrap()
}

fn call(id: impl Into<Value>, name: &str, arguments: Value) -> Value {
    json!({"jsonrpc":"2.0","id":id.into(),"method":"tools/call","params":{"name":name,"arguments":arguments}})
}

fn diff(id: impl Into<Value>, arguments: Value) -> Value {
    call(id, "memory_snapshot_diff", arguments)
}

fn payload(response: &Value) -> &Value {
    assert!(response.get("error").is_none(), "{response}");
    assert_eq!(response["result"]["isError"], false, "{response}");
    let payload = &response["result"]["structuredContent"];
    assert!(payload.is_object(), "{response}");
    let text: Value =
        serde_json::from_str(response["result"]["content"][0]["text"].as_str().unwrap()).unwrap();
    assert_eq!(&text, payload);
    payload
}

fn assert_error(response: &Value, code: i64) {
    assert_eq!(response["error"]["code"], code, "{response}");
    assert!(
        response["error"]["message"]
            .as_str()
            .is_some_and(|text| !text.is_empty())
    );
    assert!(
        response.get("result").is_none(),
        "error must not carry a cached result: {response}"
    );
}

fn snapshot(path: &Path, digest: &str) -> MemorySnapshot {
    let cancel = CancellationToken::new();
    MemorySession::open(Path::new(WORKER), path, digest, &cancel)
        .unwrap()
        .snapshot(&cancel)
        .unwrap()
}

fn expected_row(
    name: &str,
    before: (u64, u64, Option<u64>),
    after: (u64, u64, Option<u64>),
) -> Value {
    json!({
        "className":name,
        "beforeCount":before.0,"afterCount":after.0,"countDelta":i128::from(after.0)-i128::from(before.0),
        "beforeShallowSizeBytes":before.1,"afterShallowSizeBytes":after.1,"shallowSizeDeltaBytes":i128::from(after.1)-i128::from(before.1),
        "beforeRetainedSizeBytes":before.2,"afterRetainedSizeBytes":after.2,
        "retainedSizeDeltaBytes":before.2.zip(after.2).map(|(a,b)| i128::from(b)-i128::from(a)),
    })
}

fn expected_rows() -> Vec<Value> {
    // Class histogram counts include the represented class object, whose shallow size is unknown here.
    vec![
        expected_row("sample.Shrinks", (5, 96, Some(96)), (2, 24, Some(24))),
        expected_row("sample.Alpha", (2, 32, Some(32)), (4, 96, Some(96))),
        expected_row("sample.Zeta", (2, 32, Some(32)), (4, 96, Some(96))),
        expected_row("sample.Steady", (2, 8, Some(8)), (2, 8, Some(8))),
        expected_row("sample.Added", (0, 0, None), (3, 32, Some(32))),
        expected_row("sample.Removed", (3, 32, Some(32)), (0, 0, None)),
    ]
}

fn assert_page(sources: &Sources, page: &Value, offset: u64, limit: u64, rows: &[Value]) {
    assert_eq!(page["policyVersion"], "aps-memory-snapshot-diff-v1");
    assert_eq!(page["direction"], "B-A");
    assert_eq!(page["beforeSnapshotId"], sources.before_digest);
    assert_eq!(page["afterSnapshotId"], sources.after_digest);
    for key in ["beforeMappingDigest", "afterMappingDigest"] {
        assert_eq!(
            page.get(key),
            Some(&Value::Null),
            "{key} must be explicitly null"
        );
    }
    assert_eq!(page["total"], 6);
    assert_eq!(page["offset"], offset);
    assert_eq!(page["limit"], limit);
    assert_eq!(page["rows"], json!(rows));
    assert_eq!(
        page["hasMore"],
        offset.saturating_add(rows.len() as u64) < 6
    );
    assert_eq!(
        page["coverage"],
        json!({
            "beforeClassCount":5,"afterClassCount":5,
            "beforeLoadedClasses":5,"afterLoadedClasses":5,
            "included":rows.len(),"omitted":6-rows.len(),"truncated":rows.len()<6,
        })
    );
    assert!(
        page["evidenceBoundary"]
            .as_str()
            .is_some_and(|boundary| !boundary.trim().is_empty())
    );
}

#[test]
fn real_worker_diff_preserves_metrics_unknown_sides_and_shared_comparison_order() {
    let sources = Sources::new();
    let responses = sources.query(true, &[diff("diff", json!({}))]);
    let page = payload(&responses[2]);
    assert_page(&sources, page, 0, 50, &expected_rows());

    let before = snapshot(&sources.before, &sources.before_digest);
    let after = snapshot(&sources.after, &sources.after_digest);
    let comparison = compare_snapshots(&before, &after, "A".into(), "B".into()).unwrap();
    assert_eq!(
        page["rows"]
            .as_array()
            .unwrap()
            .iter()
            .map(|row| row["className"].as_str().unwrap())
            .collect::<Vec<_>>(),
        comparison
            .rows
            .iter()
            .map(|row| row.class_name.as_str())
            .collect::<Vec<_>>()
    );
    assert_ne!(sources.before_digest, sources.after_digest);
    for digest in [&sources.before_digest, &sources.after_digest] {
        assert_eq!(digest.len(), 64);
        assert!(digest.bytes().all(|byte| byte.is_ascii_hexdigit()));
    }
}

#[test]
fn reversing_endpoints_swaps_evidence_and_negates_every_known_delta() {
    let sources = Sources::new();
    let args = vec![
        "--hprof".into(),
        sources.before.as_os_str().to_owned(),
        "--worker".into(),
        WORKER.into(),
        "--baseline-hprof".into(),
        sources.after.as_os_str().to_owned(),
    ];
    let responses = sources.query_args(&args, &[diff("reverse", json!({}))]);
    let page = payload(&responses[2]);
    assert_eq!(page["direction"], "B-A");
    assert_eq!(page["beforeSnapshotId"], sources.after_digest);
    assert_eq!(page["afterSnapshotId"], sources.before_digest);
    let rows = page["rows"].as_array().unwrap();
    assert_eq!(rows.len(), 6);
    for (row, forward) in rows.iter().zip(expected_rows()) {
        assert_eq!(row["className"], forward["className"]);
        for (before, after) in [
            ("beforeCount", "afterCount"),
            ("beforeShallowSizeBytes", "afterShallowSizeBytes"),
            ("beforeRetainedSizeBytes", "afterRetainedSizeBytes"),
        ] {
            assert_eq!(row[before], forward[after]);
            assert_eq!(row[after], forward[before]);
        }
        for key in [
            "countDelta",
            "shallowSizeDeltaBytes",
            "retainedSizeDeltaBytes",
        ] {
            match forward[key].as_i64() {
                Some(delta) => assert_eq!(row[key], -delta),
                None => assert_eq!(row.get(key), Some(&Value::Null)),
            }
        }
    }
}

#[test]
fn pagination_is_stable_bounded_and_empty_past_the_end() {
    let sources = Sources::new();
    let pages = [
        (0, 2),
        (2, 2),
        (4, 2),
        (6, 2),
        (1000, 2),
        (u64::MAX, 100),
        (0, 100),
        (1, 50),
    ];
    let mut calls = pages
        .iter()
        .enumerate()
        .map(|(id, &(offset, limit))| diff(id, json!({"offset":offset,"limit":limit})))
        .collect::<Vec<_>>();
    calls.push(diff("offset-only", json!({"offset":1})));
    calls.push(diff("repeat", json!({"offset":0,"limit":2})));
    let responses = sources.query(true, &calls);
    let expected = expected_rows();
    for (&(offset, limit), response) in pages.iter().zip(&responses[2..]) {
        let start = usize::try_from(offset)
            .unwrap_or(usize::MAX)
            .min(expected.len());
        let end = start.saturating_add(limit as usize).min(expected.len());
        assert_page(
            &sources,
            payload(response),
            offset,
            limit,
            &expected[start..end],
        );
    }
    assert_page(
        &sources,
        payload(&responses[2 + pages.len()]),
        1,
        50,
        &expected[1..],
    );
    assert_eq!(payload(&responses[3 + pages.len()]), payload(&responses[2]));
}

fn assert_catalog(response: &Value) {
    let tools = response["result"]["tools"].as_array().unwrap();
    let mut names = tools
        .iter()
        .map(|tool| tool["name"].as_str().unwrap())
        .collect::<Vec<_>>();
    names.sort_unstable();
    assert_eq!(
        names,
        [
            "memory_class_histogram",
            "memory_leak_findings",
            "memory_object_detail",
            "memory_snapshot_diff",
            "memory_snapshot_summary"
        ]
    );
    let schema = &tools
        .iter()
        .find(|tool| tool["name"] == "memory_snapshot_diff")
        .unwrap()["inputSchema"];
    assert_eq!(schema["type"], "object");
    assert_eq!(schema["additionalProperties"], false);
    assert_eq!(schema["properties"].as_object().unwrap().len(), 2);
    assert_eq!(schema["properties"]["offset"]["type"], "integer");
    assert_eq!(schema["properties"]["offset"]["minimum"], 0);
    assert_eq!(schema["properties"]["limit"]["type"], "integer");
    assert_eq!(schema["properties"]["limit"]["minimum"], 1);
    assert_eq!(schema["properties"]["limit"]["maximum"], 100);
}

#[test]
fn catalog_is_always_complete_and_legacy_tools_remain_bound_to_selected_b() {
    let sources = Sources::new();
    for baseline in [false, true] {
        let responses = sources.query(
            baseline,
            &[
                diff("diff", json!({})),
                call("summary", "memory_snapshot_summary", json!({})),
                call("findings", "memory_leak_findings", json!({})),
                call("histogram", "memory_class_histogram", json!({"limit":100})),
                call("detail", "memory_object_detail", json!({"objectId":"4096"})),
            ],
        );
        assert_catalog(&responses[1]);
        if baseline {
            assert_page(&sources, payload(&responses[2]), 0, 50, &expected_rows());
        } else {
            assert_error(&responses[2], -32001);
        }
        for response in &responses[3..6] {
            assert_eq!(payload(response)["sourceDigest"], sources.after_digest);
        }
        let names = payload(&responses[5])["rows"]
            .as_array()
            .unwrap()
            .iter()
            .map(|row| row["className"].as_str().unwrap())
            .collect::<Vec<_>>();
        assert!(names.contains(&"sample.Added"));
        assert!(!names.contains(&"sample.Removed"));
        assert_eq!(payload(&responses[6])["className"], "sample.Added");
    }
}

#[test]
fn identical_content_is_rejected_even_at_different_source_paths() {
    let sources = Sources::new();
    fs::copy(&sources.after, &sources.before).unwrap();
    assert_eq!(sha256_file(&sources.before).unwrap(), sources.after_digest);
    for baseline in [&sources.before, &sources.after] {
        let mut args = sources.args(false);
        args.extend(["--baseline-hprof".into(), baseline.as_os_str().to_owned()]);
        let responses = sources.query_args(
            &args,
            &[
                diff("same", json!({})),
                diff("same-page", json!({"offset":1,"limit":1})),
                call("summary", "memory_snapshot_summary", json!({})),
            ],
        );
        assert_catalog(&responses[1]);
        assert_error(&responses[2], -32001);
        assert_error(&responses[3], -32001);
        assert_eq!(payload(&responses[4])["sourceDigest"], sources.after_digest);
    }
}

#[test]
fn invalid_diff_arguments_fail_as_invalid_params_without_rebinding_sources() {
    let sources = Sources::new();
    let other = sources
        .workspace
        .path()
        .join("unselected-private-snapshot.hprof");
    write_hprof(&other, 3, &[("unselected.MustNeverAppear", 1, 64)]);
    let invalid = vec![
        Value::Null,
        json!([]),
        json!([{}]),
        json!(true),
        json!(0),
        json!("{}"),
        json!({"offset":-1}),
        json!({"offset":0.5}),
        json!({"offset":1.0}),
        json!({"offset":"0"}),
        json!({"offset":null}),
        json!({"offset":false}),
        json!({"offset":[]}),
        json!({"offset":{}}),
        json!({"limit":0}),
        json!({"limit":101}),
        json!({"limit":u64::MAX}),
        json!({"limit":-1}),
        json!({"limit":1.5}),
        json!({"limit":1.0}),
        json!({"limit":"1"}),
        json!({"limit":null}),
        json!({"limit":true}),
        json!({"limit":[]}),
        json!({"limit":{}}),
        serde_json::from_str(r#"{"offset":18446744073709551616}"#).unwrap(),
        json!({"path":other}),
        json!({"hprof":other}),
        json!({"baselineHprof":other}),
        json!({"beforePath":other,"afterPath":other}),
        json!({"sourceDigest":"f".repeat(64)}),
        json!({"beforeSnapshotId":"f".repeat(64)}),
        json!({"search":"sample"}),
        json!({"offset":0,"limit":1,"unexpected":false}),
    ];
    let mut calls = vec![diff("initial", json!({}))];
    calls.extend(
        invalid
            .into_iter()
            .enumerate()
            .map(|(id, arguments)| diff(id, arguments)),
    );
    calls.push(diff("unchanged", json!({})));
    for baseline in [true, false] {
        let responses = sources.query(baseline, &calls);
        for response in &responses[3..responses.len() - 1] {
            assert_error(response, -32602);
            assert!(
                !response
                    .to_string()
                    .contains(other.file_name().unwrap().to_str().unwrap())
            );
        }
        if baseline {
            assert_page(&sources, payload(&responses[2]), 0, 50, &expected_rows());
            assert_eq!(payload(responses.last().unwrap()), payload(&responses[2]));
            assert!(
                !payload(responses.last().unwrap())
                    .to_string()
                    .contains("unselected.MustNeverAppear")
            );
        } else {
            assert_error(&responses[2], -32001);
            assert_error(responses.last().unwrap(), -32001);
        }
    }
}

#[test]
fn cli_accepts_legacy_invocation_and_every_known_flag_order() {
    let sources = Sources::new();
    let flags = [
        [
            OsString::from("--hprof"),
            sources.after.as_os_str().to_owned(),
        ],
        [OsString::from("--worker"), OsString::from(WORKER)],
        [
            OsString::from("--baseline-hprof"),
            sources.before.as_os_str().to_owned(),
        ],
    ];
    for order in [
        vec![0, 1],
        vec![1, 0],
        vec![0, 1, 2],
        vec![0, 2, 1],
        vec![1, 0, 2],
        vec![1, 2, 0],
        vec![2, 0, 1],
        vec![2, 1, 0],
    ] {
        let args = order
            .iter()
            .flat_map(|&index| flags[index].clone())
            .collect::<Vec<_>>();
        let responses = sources.query_args(&args, &[diff("diff", json!({"limit":1}))]);
        assert_catalog(&responses[1]);
        if order.len() == 3 {
            assert_page(
                &sources,
                payload(&responses[2]),
                0,
                1,
                &expected_rows()[..1],
            );
        } else {
            assert_error(&responses[2], -32001);
        }
    }
}

#[test]
fn cli_rejects_duplicate_unknown_odd_missing_and_non_file_arguments() {
    let sources = Sources::new();
    let mut invalid = vec![
        vec![],
        vec!["--hprof".into()],
        vec!["--worker".into(), WORKER.into()],
        vec!["--hprof".into(), sources.after.as_os_str().to_owned()],
        vec![
            "--baseline-hprof".into(),
            sources.before.as_os_str().to_owned(),
        ],
        vec!["--hprof".into(), "--worker".into(), WORKER.into()],
        vec!["--unknown".into(), "value".into()],
    ];
    for flag in ["--hprof", "--worker", "--baseline-hprof"] {
        let mut odd = sources.args(true);
        odd.push(flag.into());
        invalid.push(odd);
        let mut duplicate = sources.args(true);
        let value: OsString = match flag {
            "--hprof" => sources.after.as_os_str().to_owned(),
            "--worker" => WORKER.into(),
            _ => sources.before.as_os_str().to_owned(),
        };
        duplicate.extend([flag.into(), value]);
        invalid.push(duplicate);
        for path in [
            sources.workspace.path().to_path_buf(),
            sources.workspace.path().join("missing.hprof"),
            PathBuf::new(),
        ] {
            let mut args = sources.args(true);
            let index = args.iter().position(|argument| argument == flag).unwrap();
            args[index + 1] = path.into_os_string();
            invalid.push(args);
        }
    }
    for extra in [
        vec!["--unknown", "value"],
        vec!["--mapping", "mapping.txt"],
        vec!["extra"],
        vec!["extra", "value"],
    ] {
        let mut args = sources.args(true);
        args.extend(extra.into_iter().map(OsString::from));
        invalid.push(args);
    }
    for args in invalid {
        let output = run_cli(&args, String::new());
        assert!(
            !output.status.success(),
            "invalid CLI was accepted: {args:?}"
        );
        assert!(
            output.stdout.is_empty(),
            "invalid CLI emitted protocol data: {args:?}"
        );
        assert!(
            !output.stderr.is_empty(),
            "invalid CLI omitted its diagnostic: {args:?}"
        );
    }
}

struct InteractiveServer {
    child: Child,
    stdin: Option<ChildStdin>,
    responses: Receiver<Result<Value, String>>,
    stderr: Receiver<Vec<u8>>,
    readers: Vec<JoinHandle<()>>,
}

impl InteractiveServer {
    fn start(sources: &Sources) -> Self {
        let mut child = Command::new(MCP)
            .args(sources.args(true))
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .unwrap();
        let stdin = child.stdin.take();
        let stdout = child.stdout.take().unwrap();
        let stderr = child.stderr.take().unwrap();
        let (sender, responses) = mpsc::channel();
        let stdout_reader = thread::spawn(move || {
            for line in BufReader::new(stdout).lines() {
                let response = line.map_err(|error| error.to_string()).and_then(|line| {
                    serde_json::from_str(&line).map_err(|error| error.to_string())
                });
                if sender.send(response).is_err() {
                    break;
                }
            }
        });
        let (sender, stderr_receiver) = mpsc::channel();
        let stderr_reader = thread::spawn(move || {
            let mut bytes = Vec::new();
            let _ = stderr.take(64 * 1024).read_to_end(&mut bytes);
            let _ = sender.send(bytes);
        });
        Self {
            child,
            stdin,
            responses,
            stderr: stderr_receiver,
            readers: vec![stdout_reader, stderr_reader],
        }
    }

    fn send(&mut self, request: &Value) {
        let stdin = self.stdin.as_mut().unwrap();
        writeln!(stdin, "{request}").unwrap();
        stdin.flush().unwrap();
    }

    fn request(&mut self, sources: &Sources, request: Value) -> Value {
        self.send(&request);
        let response = self
            .responses
            .recv_timeout(RESPONSE_TIMEOUT)
            .expect("MCP response timed out or pipe closed")
            .unwrap();
        assert_eq!(response["id"], request["id"]);
        assert_eq!(response["jsonrpc"], "2.0");
        sources.assert_path_free(&response.to_string());
        response
    }

    fn stop(&mut self) -> Option<ExitStatus> {
        self.stdin.take();
        let deadline = Instant::now() + Duration::from_secs(3);
        loop {
            match self.child.try_wait() {
                Ok(Some(status)) => return Some(status),
                Ok(None) if Instant::now() < deadline => thread::sleep(Duration::from_millis(10)),
                _ => {
                    let _ = self.child.kill();
                    return self.child.wait().ok();
                }
            }
        }
    }

    fn finish(mut self) {
        assert!(
            self.stop().unwrap().success(),
            "interactive MCP did not exit cleanly"
        );
        let stderr = self
            .stderr
            .recv_timeout(RESPONSE_TIMEOUT)
            .expect("stderr pipe did not close");
        assert!(stderr.is_empty(), "{}", String::from_utf8_lossy(&stderr));
        assert!(
            matches!(
                self.responses.recv_timeout(RESPONSE_TIMEOUT),
                Err(RecvTimeoutError::Disconnected)
            ),
            "stdout pipe did not close cleanly or emitted an unexpected response"
        );
        for reader in self.readers.drain(..) {
            reader.join().unwrap();
        }
    }
}

impl Drop for InteractiveServer {
    fn drop(&mut self) {
        self.stop();
        for reader in self.readers.drain(..) {
            if reader.is_finished() {
                let _ = reader.join();
            }
        }
    }
}

fn assert_cached_report_rejects_modified_source(modify_before: bool) {
    let sources = Sources::new();
    let mut server = InteractiveServer::start(&sources);
    let initialized = server.request(
        &sources,
        json!({"jsonrpc":"2.0","id":"initialize","method":"initialize"}),
    );
    assert!(initialized.get("result").is_some(), "{initialized}");
    server.send(&json!({"jsonrpc":"2.0","method":"notifications/initialized"}));
    let histogram = server.request(
        &sources,
        call(
            "histogram-before",
            "memory_class_histogram",
            json!({"limit":100}),
        ),
    );
    assert_eq!(payload(&histogram)["sourceDigest"], sources.after_digest);
    let first = server.request(&sources, diff("first", json!({"offset":0,"limit":1})));
    assert_page(&sources, payload(&first), 0, 1, &expected_rows()[..1]);

    let (path, original_digest, classes) = if modify_before {
        (&sources.before, &sources.before_digest, BEFORE)
    } else {
        (&sources.after, &sources.after_digest, AFTER)
    };
    let original_len = fs::metadata(path).unwrap().len();
    // Change only the HPROF timestamp: a valid, same-length source with different content.
    write_hprof(path, 999, classes);
    assert_eq!(fs::metadata(path).unwrap().len(), original_len);
    assert_ne!(&sha256_file(path).unwrap(), original_digest);

    assert_error(
        &server.request(
            &sources,
            diff("invalid-before-source-check", json!({"path":path})),
        ),
        -32602,
    );
    for (id, args) in [
        ("cached-same-page", json!({"offset":0,"limit":1})),
        ("cached-next-page", json!({"offset":1,"limit":1})),
        ("cached-empty-page", json!({"offset":1000,"limit":1})),
    ] {
        assert_error(&server.request(&sources, diff(id, args)), -32001);
    }
    let histogram_after = server.request(
        &sources,
        call(
            "histogram-after",
            "memory_class_histogram",
            json!({"limit":100}),
        ),
    );
    if modify_before {
        assert_eq!(payload(&histogram_after), payload(&histogram));
    } else {
        // A known-invalid B session must not return its old cached histogram.
        assert_error(&histogram_after, -32001);
    }
    server.finish();
}

#[test]
fn cached_pages_reject_a_modified_baseline_source() {
    assert_cached_report_rejects_modified_source(true);
}

#[test]
fn cached_pages_reject_a_modified_selected_source() {
    assert_cached_report_rejects_modified_source(false);
}

#[path = "engine-fixtures/mod.rs"]
mod common;

use aps_contracts::SourceDigest;
use serde_json::{json, Value};
use std::io::{BufRead, BufReader, Write};
use std::path::PathBuf;
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::atomic::{AtomicUsize, Ordering};
use std::sync::mpsc::{self, Receiver};
use std::time::Duration;

static SEQUENCE: AtomicUsize = AtomicUsize::new(0);

struct Client {
    child: Child,
    stdin: Option<ChildStdin>,
    responses: Receiver<Result<Value, String>>,
    path: PathBuf,
    baseline_path: Option<PathBuf>,
    bytes: Vec<u8>,
}

impl Client {
    fn new() -> Self {
        let mut profile = common::profile();
        for record in &mut profile.records {
            if let aps_simpleperf_engine::simpleperf::Record::File(file) = record {
                file.path = "C:\\private\\cpu-fixture\\fixture.so".into();
            }
        }
        Self::bytes(common::encoded(&profile))
    }

    fn bytes(bytes: Vec<u8>) -> Self {
        Self::with_baseline(bytes, None)
    }

    fn with_baseline(bytes: Vec<u8>, baseline: Option<&[u8]>) -> Self {
        let path = std::env::temp_dir().join(format!(
            "aps-cpu-mcp-{}-{}.profile",
            std::process::id(),
            SEQUENCE.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::write(&path, &bytes).unwrap();
        let mut command = Command::new(env!("CARGO_BIN_EXE_aps-cpu-mcp"));
        let baseline_path = baseline.map(|data| {
            let baseline_path = path.with_extension("before");
            std::fs::write(&baseline_path, data).unwrap();
            command.arg("--baseline-cpu").arg(&baseline_path);
            baseline_path
        });
        let mut child = command
            .arg("--cpu")
            .arg(&path)
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::piped())
            .spawn()
            .unwrap();
        let stdin = child.stdin.take();
        let stdout = BufReader::new(child.stdout.take().unwrap());
        let (sender, responses) = mpsc::channel();
        std::thread::spawn(move || {
            for line in stdout.lines() {
                let response = line.map_err(|error| error.to_string()).and_then(|line| {
                    serde_json::from_str(&line).map_err(|error| error.to_string())
                });
                if sender.send(response).is_err() {
                    break;
                }
            }
        });
        let mut client = Self {
            child,
            stdin,
            responses,
            path,
            baseline_path,
            bytes,
        };
        assert_eq!(
            client.request(json!({"method":"initialize","params":{}}))["result"]["serverInfo"]
                ["name"],
            "aps-cpu-mcp"
        );
        client.send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}\n");
        client
    }

    fn send(&mut self, line: &str) {
        let stdin = self.stdin.as_mut().unwrap();
        stdin.write_all(line.as_bytes()).unwrap();
        stdin.flush().unwrap();
    }

    fn response(&mut self) -> Value {
        self.responses
            .recv_timeout(Duration::from_secs(15))
            .expect("server stopped or did not respond within 15 seconds")
            .unwrap()
    }

    fn request(&mut self, mut request: Value) -> Value {
        request["jsonrpc"] = json!("2.0");
        request["id"] = json!(1);
        self.send(&format!("{request}\n"));
        self.response()
    }

    fn call(&mut self, name: &str, arguments: Value) -> Value {
        self.request(json!({"method":"tools/call","params":{"name":name,"arguments":arguments}}))
    }
}

impl Drop for Client {
    fn drop(&mut self) {
        self.stdin.take();
        let _ = self.child.kill();
        let _ = self.child.wait();
        let _ = std::fs::remove_file(&self.path);
        if let Some(path) = &self.baseline_path {
            let _ = std::fs::remove_file(path);
        }
    }
}

fn payload(response: Value) -> Value {
    assert!(response.get("error").is_none(), "{response}");
    assert_eq!(response["result"]["isError"], false);
    let result = &response["result"];
    assert_eq!(
        serde_json::from_str::<Value>(result["content"][0]["text"].as_str().unwrap()).unwrap(),
        result["structuredContent"]
    );
    result["structuredContent"].clone()
}

#[test]
fn discover_eight_tools_and_query_original_source_bound_pages() {
    let mut client = Client::new();
    let tools = client.request(json!({"method":"tools/list"}));
    let names: Vec<_> = tools["result"]["tools"]
        .as_array()
        .unwrap()
        .iter()
        .map(|tool| tool["name"].as_str().unwrap())
        .collect();
    assert_eq!(
        names,
        [
            "cpu_session_summary",
            "cpu_top_functions",
            "cpu_call_tree",
            "cpu_timeline_summary",
            "cpu_compare_top_functions",
            "cpu_flame_blocks",
            "cpu_stack_blocks",
            "cpu_export_report"
        ]
    );
    let summary = payload(client.call("cpu_session_summary", json!({})));
    assert_eq!(
        summary["sourceDigest"],
        SourceDigest::hash_reader(client.bytes.as_slice(), client.bytes.len() as u64)
            .unwrap()
            .to_hex()
    );
    assert_eq!(summary["sampleCount"], 6);
    assert_eq!(summary["inputFormat"], "simpleperf_protobuf");
    assert_eq!(summary["evidenceQuality"]["methodDurationAvailable"], false);
    assert!(
        summary["lostRate"].is_null(),
        "no producer loss receipt is not zero measured loss"
    );
    let whole = payload(client.call(
        "cpu_top_functions",
        json!({"event":"cpu-cycles","startNs":10,"endNs":50,"weightType":"events"}),
    ));
    assert_eq!(whole["rows"][0]["inclusiveWeight"], 8);
    assert_eq!(whole["rows"][0]["resource"], "fixture.so");
    assert!(!whole.to_string().contains("private"));
    let first = payload(client.call(
        "cpu_top_functions",
        json!({"event":"cpu-cycles","startNs":10,"endNs":50,"weightType":"events","limit":1}),
    ));
    let second = payload(client.call("cpu_top_functions", json!({"event":"cpu-cycles","startNs":10,"endNs":50,"weightType":"events","limit":1,"offset":1})));
    assert_eq!(first["rows"][0], whole["rows"][0]);
    assert_eq!(second["rows"][0], whole["rows"][1]);
    assert_eq!(first["hasMore"], true);
    let tree = payload(client.call("cpu_call_tree", json!({"direction":"inverted","limit":1})));
    assert_eq!(tree["rows"].as_array().unwrap().len(), 1);
    assert_eq!(tree["query"]["direction"], "inverted");
    assert_eq!(tree["sourceDigest"], summary["sourceDigest"]);
    let empty = payload(client.call("cpu_call_tree", json!({"offset":100})));
    assert_eq!(empty["rows"], json!([]));
    assert_eq!(empty["hasMore"], false);
}

#[test]
fn schema_is_enforced_at_runtime_without_silent_defaults() {
    let mut client = Client::new();
    for tool in ["cpu_top_functions", "cpu_call_tree"] {
        for arguments in [
            json!([]),
            json!({"event":7}),
            json!({"event":""}),
            json!({"event":"not-in-profile"}),
            json!({"direction":false}),
            json!({"weightType":null}),
            json!({"limit":0}),
            json!({"limit":"1"}),
            json!({"offset":-1}),
            json!({"offset":null}),
            json!({"startNs":null}),
            json!({"startNs":20,"endNs":20}),
            json!({"weightType":"events"}),
            json!({"path":"/private/secret"}),
            json!({"command":"echo"}),
        ] {
            assert_eq!(
                client.call(tool, arguments.clone())["error"]["code"],
                -32602,
                "{tool}: {arguments}"
            );
        }
    }
    assert_eq!(
        client.call("cpu_top_functions", json!({"limit":101}))["error"]["code"],
        -32602
    );
    assert_eq!(
        client.call("cpu_call_tree", json!({"limit":2001}))["error"]["code"],
        -32602
    );
    assert_eq!(
        client.call("cpu_session_summary", json!({"path":"/secret"}))["error"]["code"],
        -32602
    );
    assert_eq!(
        client.call("cpu_session_summary", json!(null))["error"]["code"],
        -32602
    );
    assert_eq!(client.call("unknown", json!({}))["error"]["code"], -32602);
    payload(client.call("cpu_session_summary", json!({})));
}

#[test]
fn malformed_duplicate_and_oversized_requests_do_not_kill_stdio_session() {
    let mut client = Client::new();
    for line in [
        "{bad\n".to_owned(),
        "[]\n".to_owned(),
        "{\"jsonrpc\":\"1.0\",\"id\":7,\"method\":\"tools/list\"}\n".to_owned(),
        "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\",\"method\":\"ping\"}\n".to_owned(),
        format!("{}\n", "x".repeat(70_000)),
    ] {
        client.send(&line);
        assert!(client.response()["error"].is_object());
        assert_eq!(
            client.request(json!({"method":"ping"}))["result"],
            json!({})
        );
    }
    client.stdin.take();
    assert!(client.child.wait().unwrap().success());
}

#[test]
fn cached_query_is_permanently_invalidated_after_source_changes() {
    let mut client = Client::new();
    payload(client.call("cpu_top_functions", json!({"limit":1})));
    std::fs::write(&client.path, b"changed").unwrap();
    for tool in ["cpu_session_summary", "cpu_top_functions", "cpu_call_tree"] {
        let response = client.call(tool, json!({}));
        assert_eq!(response["error"]["code"], -32001);
        assert!(!response.to_string().contains(client.path.to_str().unwrap()));
    }
    std::fs::write(&client.path, &client.bytes).unwrap();
    assert_eq!(
        client.call("cpu_top_functions", json!({}))["error"]["code"],
        -32001
    );
}

#[test]
fn call_tree_supports_advertised_limit_above_engine_page_size() {
    use aps_simpleperf_engine::simpleperf::{FileRecord, Record, SimpleperfStream};
    let source = SimpleperfStream {
        version: 1,
        records: vec![Record::File(FileRecord {
            id: 7,
            path: "fixture.so".into(),
            symbols: (0..2100).map(|i| format!("f{i}")).collect(),
        })]
        .into_iter()
        .chain((0..2100).map(|i| common::sample(i as u64, 1, 0, 1, &[i])))
        .collect(),
    };
    let mut client = Client::bytes(common::encoded(&source));
    let tree = payload(client.call("cpu_call_tree", json!({"limit":2000})));
    assert_eq!(tree["rows"].as_array().unwrap().len(), 2000);
    assert_eq!(tree["hasMore"], true);
    let tail = payload(client.call("cpu_call_tree", json!({"limit":2000,"offset":2000})));
    assert_eq!(tail["rows"].as_array().unwrap().len(), 100);
    assert_eq!(tail["hasMore"], false);
}

#[test]
fn raw_perf_data_is_rejected_without_launching_converter() {
    let path = std::env::temp_dir().join(format!("aps-cpu-mcp-perf-{}", std::process::id()));
    std::fs::write(&path, b"PERFILE2raw").unwrap();
    let output = Command::new(env!("CARGO_BIN_EXE_aps-cpu-mcp"))
        .arg("--cpu")
        .arg(&path)
        .output()
        .unwrap();
    let _ = std::fs::remove_file(path);
    assert!(!output.status.success());
    assert!(output.stdout.is_empty());
    assert!(String::from_utf8_lossy(&output.stderr).contains("convert"));
}

#[test]
fn gecko_profile_uses_the_same_three_tools_and_preserves_unknown_loss() {
    let profile = json!({"meta":{"version":24},"processes":[],"threads":[{
        "tid":1,"pid":1,"name":"main",
        "stringTable":["root (in /private/build/libfixture.so)"],
        "frameTable":{"schema":{"location":0},"data":[[0]]},
        "stackTable":{"schema":{"prefix":0,"frame":1},"data":[[null,0]]},
        "samples":{"schema":{"stack":0,"time":1},"data":[[0,1.5],[0,2.0]]}
    }]});
    let mut client = Client::bytes(serde_json::to_vec(&profile).unwrap());
    let summary = payload(client.call("cpu_session_summary", json!({})));
    assert_eq!(summary["inputFormat"], "gecko_v24");
    assert_eq!(summary["sampleCount"], 2);
    assert_eq!(summary["firstTimestampNs"], 1_500_000);
    assert!(summary["lostRate"].is_null());
    let top = payload(client.call("cpu_top_functions", json!({"event":"samples"})));
    assert_eq!(top["rows"][0]["inclusiveWeight"], 2);
    assert_eq!(top["rows"][0]["resource"], "libfixture.so");
    let tree = payload(client.call("cpu_call_tree", json!({"startNs":2_000_000})));
    assert_eq!(tree["rows"][0]["sampleCount"], 1);
}

#[test]
fn oversized_payload_fails_explicitly_and_session_remains_usable() {
    use aps_simpleperf_engine::simpleperf::{FileRecord, Record, SimpleperfStream};
    let source = SimpleperfStream {
        version: 1,
        records: vec![
            common::sample(1, 1, 0, 1, &[0]),
            Record::File(FileRecord {
                id: 7,
                path: "fixture.so".into(),
                symbols: vec!["x".repeat(513 * 1024)],
            }),
        ],
    };
    let mut client = Client::bytes(common::encoded(&source));
    let response = client.call("cpu_top_functions", json!({"limit":1}));
    assert_eq!(response["error"]["code"], -32002);
    assert!(response.get("result").is_none());
    payload(client.call("cpu_session_summary", json!({})));
}

#[test]
fn producer_loss_receipt_remains_separate_from_observed_samples() {
    let mut source = common::profile();
    source
        .records
        .push(aps_simpleperf_engine::simpleperf::Record::Lost {
            sample_count: 100,
            lost_count: 4,
        });
    let mut client = Client::bytes(common::encoded(&source));
    let summary = payload(client.call("cpu_session_summary", json!({})));
    assert_eq!(summary["sampleCount"], 6);
    assert_eq!(summary["reportedSampleCount"], 100);
    assert_eq!(summary["lostSampleCount"], 4);
    assert_eq!(summary["lostRate"], 0.04);
    assert_eq!(summary["lossRateAvailable"], true);
}

#[test]
fn redacted_resource_labels_do_not_destroy_function_identity() {
    use aps_simpleperf_engine::simpleperf::{FileRecord, Frame, Record, Sample, SimpleperfStream};
    let records = ["/private/a/libsame.so", "/private/b/libsame.so"]
        .into_iter()
        .enumerate()
        .flat_map(|(i, path)| {
            [
                Record::File(FileRecord {
                    id: i as u32,
                    path: path.into(),
                    symbols: vec!["foo".into()],
                }),
                Record::Sample(Sample {
                    time: 1,
                    thread_id: 1,
                    event_type_id: 0,
                    event_count: 1,
                    callchain: vec![Frame {
                        file_id: i as u32,
                        symbol_id: 0,
                        virtual_address: 10,
                        execution_type: 0,
                    }],
                }),
            ]
        })
        .collect();
    let mut client = Client::bytes(common::encoded(&SimpleperfStream {
        version: 1,
        records,
    }));
    let top = payload(client.call("cpu_top_functions", json!({})));
    let rows = top["rows"].as_array().unwrap();
    assert_eq!(rows.len(), 2);
    assert_eq!(rows[0]["name"], rows[1]["name"]);
    assert_eq!(rows[0]["resource"], rows[1]["resource"]);
    assert_ne!(rows[0]["id"], rows[1]["id"]);
    assert!(!top.to_string().contains("private"));
}

#[test]
fn timeline_counts_original_samples_with_half_open_bounds_and_limited_threads() {
    let mut client = Client::new();
    let value = payload(client.call(
        "cpu_timeline_summary",
        json!({
            "event":"cpu-cycles","startNs":10,"endNs":61,"bucketCount":3,"threadLimit":1
        }),
    ));
    assert_eq!(
        value["sourceDigest"],
        SourceDigest::hash_reader(client.bytes.as_slice(), client.bytes.len() as u64)
            .unwrap()
            .to_hex()
    );
    assert_eq!(value["sampleCount"], 4);
    assert_eq!(value["emptyStackSampleCount"], 1);
    let buckets = value["buckets"].as_array().unwrap();
    assert_eq!(
        buckets
            .iter()
            .map(|b| b["sampleCount"].as_u64().unwrap())
            .collect::<Vec<_>>(),
        [2, 0, 2]
    );
    assert_eq!(value["threadCount"], 3);
    assert_eq!(value["omittedThreadCount"], 2);
    assert_eq!(value["threads"][0]["threadId"], 11);
    assert_eq!(value["threads"][0]["sampleCount"], 2);
    assert_eq!(value["threads"][0]["peakBucketIndex"], 0);
    let empty = payload(client.call(
        "cpu_timeline_summary",
        json!({"startNs":21,"endNs":30,"bucketCount":3}),
    ));
    assert_eq!(empty["sampleCount"], 0);
    assert_eq!(empty["firstTimestampNs"], Value::Null);
    for bad in [
        json!({"event":"absent"}),
        json!({"bucketCount":0}),
        json!({"bucketCount":129}),
        json!({"threadLimit":101}),
        json!({"path":"other"}),
        json!({"startNs":8,"endNs":8}),
    ] {
        assert_eq!(
            client.call("cpu_timeline_summary", bad)["error"]["code"],
            -32602
        );
    }
    std::fs::remove_file(&client.path).unwrap();
    assert_eq!(
        client.call("cpu_timeline_summary", json!({}))["error"]["code"],
        -32001
    );
}

fn p1_pair() -> (Vec<u8>, Vec<u8>) {
    let mut profile = common::profile();
    let before = common::encoded(&profile);
    profile.records.push(common::sample(70, 1, 0, 20, &[1, 0]));
    (before, common::encoded(&profile))
}

#[test]
fn compare_binds_two_sources_only_for_the_comparison_tool() {
    let (before, after) = p1_pair();
    let mut single = Client::bytes(after.clone());
    assert_eq!(
        single.call("cpu_compare_top_functions", json!({}))["error"]["code"],
        -32001
    );
    let mut client = Client::with_baseline(after.clone(), Some(&before));
    assert_eq!(
        payload(client.call("cpu_session_summary", json!({})))["sampleCount"],
        7
    );
    let page = payload(client.call(
        "cpu_compare_top_functions",
        json!({"event":"cpu-cycles","weightType":"events","limit":1}),
    ));
    assert_eq!(
        page["beforeSourceDigest"],
        SourceDigest::hash_reader(before.as_slice(), before.len() as u64)
            .unwrap()
            .to_hex()
    );
    assert_eq!(
        page["afterSourceDigest"],
        SourceDigest::hash_reader(after.as_slice(), after.len() as u64)
            .unwrap()
            .to_hex()
    );
    assert_eq!(page["hasMore"], true);
    let row = &page["rows"][0];
    assert_eq!(row["beforeInclusiveWeight"], 15);
    assert_eq!(row["afterInclusiveWeight"], 35);
    assert_eq!(row["deltaInclusiveWeight"], 20);
    assert_eq!(row["deltaSampleCount"], 1);
    let next = payload(client.call(
        "cpu_compare_top_functions",
        json!({"event":"cpu-cycles","weightType":"events","offset":1,"limit":1}),
    ));
    assert_ne!(row["name"], next["rows"][0]["name"]);
    for bad in [
        json!({"event":"absent"}),
        json!({"limit":0}),
        json!({"limit":101}),
        json!({"baselinePath":"/other"}),
        json!({"weightType":"events"}),
        json!({"startNs":10}),
    ] {
        assert_eq!(
            client.call("cpu_compare_top_functions", bad)["error"]["code"],
            -32602
        );
    }
    let baseline = client.baseline_path.clone().unwrap();
    std::fs::write(&baseline, b"changed").unwrap();
    let failed = client.call("cpu_compare_top_functions", json!({}));
    assert_eq!(failed["error"]["code"], -32001);
    assert!(!failed.to_string().contains(baseline.to_str().unwrap()));
    std::fs::write(&baseline, &before).unwrap();
    assert_eq!(
        client.call("cpu_compare_top_functions", json!({}))["error"]["code"],
        -32001
    );
    assert_eq!(
        payload(client.call("cpu_timeline_summary", json!({})))["sampleCount"],
        7
    );
}

#[test]
fn compare_ranks_full_union_before_paging_and_preserves_signed_direction() {
    use aps_simpleperf_engine::simpleperf::{FileRecord, Record, SimpleperfStream};
    let n = 700;
    let mut source = SimpleperfStream {
        version: 1,
        records: (0..n)
            .map(|i| common::sample(i as u64, 1, 0, 1, &[i]))
            .collect(),
    };
    source.records.push(Record::File(FileRecord {
        id: 7,
        path: "/private/hidden/lib.so".into(),
        symbols: (0..n).map(|i| format!("sym{i}")).collect(),
    }));
    let small = common::encoded(&source);
    for _ in 0..20 {
        source.records.push(common::sample(1000, 1, 0, 1, &[n - 1]));
    }
    let large = common::encoded(&source);
    let mut client = Client::with_baseline(small, Some(&large));
    let value = payload(client.call("cpu_compare_top_functions", json!({"limit":1})));
    assert!(!value.to_string().contains("/private"));
    assert_eq!(value["rows"][0]["name"], "sym699");
    assert_eq!(value["rows"][0]["deltaInclusiveWeight"], -20);
    assert_eq!(value["rows"][0]["deltaSelfWeight"], -20);
    assert_eq!(value["rows"][0]["deltaSampleCount"], -20);
    std::fs::write(&client.path, b"changed").unwrap();
    assert_eq!(
        client.call("cpu_compare_top_functions", json!({}))["error"]["code"],
        -32001
    );
}

#[test]
fn comparison_matches_original_labels_not_profile_local_ids_or_redacted_basenames() {
    use aps_simpleperf_engine::simpleperf::{FileRecord, Frame, Record, Sample, SimpleperfStream};
    fn bytes(entries: &[(u32, &str, &str)]) -> Vec<u8> {
        let records = entries
            .iter()
            .flat_map(|&(id, resource, name)| {
                [
                    Record::File(FileRecord {
                        id,
                        path: resource.into(),
                        symbols: vec![name.into()],
                    }),
                    Record::Sample(Sample {
                        time: 10,
                        thread_id: 1,
                        event_type_id: 0,
                        event_count: 1,
                        callchain: vec![Frame {
                            file_id: id,
                            symbol_id: 0,
                            virtual_address: 10,
                            execution_type: 0,
                        }],
                    }),
                ]
            })
            .collect();
        common::encoded(&SimpleperfStream {
            version: 1,
            records,
        })
    }
    let before = bytes(&[
        (1, "/private/a/lib.so", "foo"),
        (2, "/private/a/lib.so", "same"),
        (3, "/private/a/lib.so", "ambiguous"),
        (4, "/private/a/lib.so", "ambiguous"),
        (5, "/private/a/lib.so", "<unknown-symbol>"),
    ]);
    let after = bytes(&[
        (1, "/private/b/lib.so", "foo"),
        (20, "/private/a/lib.so", "same"),
        (30, "/private/a/lib.so", "ambiguous"),
    ]);
    let mut client = Client::with_baseline(after, Some(&before));
    let value = payload(client.call("cpu_compare_top_functions", json!({})));
    let rows = value["rows"].as_array().unwrap();
    assert_eq!(rows.len(), 3);
    assert!(!value.to_string().contains("/private"));
    let matched = rows.iter().find(|r| r["matchStatus"] == "matched").unwrap();
    assert_eq!(matched["name"], "same");
    assert_ne!(matched["beforeFunctionId"], matched["afterFunctionId"]);
    for (status, delta, missing) in [
        ("after_only", 1, "beforeFunctionId"),
        ("before_only", -1, "afterFunctionId"),
    ] {
        let row = rows.iter().find(|r| r["matchStatus"] == status).unwrap();
        assert_eq!(row["name"], "foo");
        assert!(row[missing].is_null());
        assert_eq!(row["resource"], "lib.so");
        assert_eq!(row["deltaInclusiveWeight"], delta);
    }
    assert_eq!(value["coverage"]["before"]["ambiguousFunctions"], 2);
    assert_eq!(value["coverage"]["after"]["ambiguousFunctions"], 1);
    assert_eq!(value["coverage"]["before"]["unidentifiableFunctions"], 1);
    assert_eq!(value["measurementComparabilityVerified"], false);
}

#[test]
fn p2_flame_projects_weights_not_time_and_identifies_aggregate_bins() {
    let mut client = Client::new();
    let flame = payload(client.call(
        "cpu_flame_blocks",
        json!({
            "startNs":10,"endNs":30,"event":"cpu-cycles","weightType":"events","endFraction":0.5
        }),
    ));
    assert_eq!(
        flame["sourceDigest"],
        SourceDigest::hash_reader(client.bytes.as_slice(), client.bytes.len() as u64)
            .unwrap()
            .to_hex()
    );
    assert_eq!(flame["evidenceQuality"]["methodDurationAvailable"], false);
    let root = flame["blocks"]
        .as_array()
        .unwrap()
        .iter()
        .find(|row| row["name"] == "root")
        .unwrap();
    assert_eq!(root["depth"], 0);
    assert_eq!(root["inclusiveWeight"], 8);
    assert_eq!(root["sampleCount"], 2);
    assert_eq!(root["resource"], "fixture.so");
    assert!(root["callNodeId"].is_string());
    assert!(!flame.to_string().contains("private"));
    let limited = payload(client.call("cpu_flame_blocks", json!({"maxBlocks":1})));
    assert_eq!(limited["blocks"].as_array().unwrap().len(), 1);
    assert_eq!(limited["truncated"], true);
    assert!(limited["omittedBlocks"].as_u64().unwrap() > 0);
    let coarse = payload(client.call("cpu_flame_blocks", json!({"resolutionBins":1})));
    let aggregate = coarse["blocks"]
        .as_array()
        .unwrap()
        .iter()
        .find(|row| row["kind"] == "aggregate")
        .unwrap();
    assert!(aggregate.get("callNodeId").is_some_and(Value::is_null));
    assert!(aggregate.get("name").is_some_and(Value::is_null));
    assert!(aggregate["sourceCount"].as_u64().unwrap() > 0);
}

#[test]
fn p2_stack_uses_one_local_thread_and_never_reports_a_measured_duration() {
    let mut client = Client::new();
    let stack = payload(client.call(
        "cpu_stack_blocks",
        json!({"startNs":10,"endNs":61,"threadId":22}),
    ));
    let blocks = stack["blocks"].as_array().unwrap();
    assert!(!blocks.is_empty());
    assert_eq!(stack["evidenceQuality"]["methodDurationAvailable"], false);
    assert!(blocks.iter().all(|row| row["threadId"] == 22
        && row["kind"] == "sampledRun"
        && row["layoutStartNs"].as_u64().unwrap() >= 10
        && row["layoutEndNsExclusive"].as_u64().unwrap() <= 61
        && row["selfWeight"].is_null()));
    assert!(!stack.to_string().contains("private"));
    let implicit = payload(client.call("cpu_stack_blocks", json!({"startNs":10,"endNs":61})));
    assert!(implicit["blocks"]
        .as_array()
        .unwrap()
        .iter()
        .all(|row| row["threadId"] == 11));
    let empty = payload(client.call("cpu_stack_blocks", json!({"startNs":100,"endNs":200})));
    assert_eq!(empty["blocks"], json!([]));
}

#[test]
fn p2_report_returns_verifiable_bytes_for_current_profile_without_writing_to_disk() {
    let (before, after) = p1_pair();
    let mut client = Client::with_baseline(after, Some(&before));
    let digest = SourceDigest::hash_reader(client.bytes.as_slice(), client.bytes.len() as u64)
        .unwrap()
        .to_hex();
    for format in ["json", "csv"] {
        let args = json!({"format":format,"event":"cpu-cycles","startNs":10,"endNs":51,"limit":1});
        let report = payload(client.call("cpu_export_report", args.clone()));
        let text = report["content"].as_str().unwrap();
        assert_eq!(report["format"], format);
        assert_eq!(report["byteLength"], text.len());
        assert_eq!(report["sourceDigest"], digest);
        assert_eq!(
            report["artifactSha256"],
            SourceDigest::hash_reader(text.as_bytes(), text.len() as u64)
                .unwrap()
                .to_hex()
        );
        assert!(text.contains(&digest));
        for section in ["topFunctions", "callTree"] {
            let coverage = &report["coverage"][section];
            assert_eq!(coverage["returned"], 1);
            assert!(coverage["total"].as_u64().unwrap() > 1);
            assert_eq!(
                coverage["omitted"].as_u64().unwrap(),
                coverage["total"].as_u64().unwrap() - 1
            );
        }
        if format == "json" {
            let document: Value = serde_json::from_str(text).unwrap();
            assert_eq!(document["schemaVersion"], "aps-cpu-mcp-report-v1");
            assert_eq!(document["profileSummary"]["sampleCount"], 7);
            assert!(document["profileSummary"]["lostRate"].is_null());
            assert_eq!(document["query"]["event"], "cpu-cycles");
            assert_eq!(document["topFunctions"][0]["inclusiveWeight"], 3);
            assert_eq!(document["topFunctions"].as_array().unwrap().len(), 1);
            assert_eq!(document["callTree"].as_array().unwrap().len(), 1);
            assert_eq!(document["coverage"], report["coverage"]);
        } else {
            assert_eq!(report["mimeType"], "text/csv");
            assert!(text.starts_with("section,metadataJson,"));
            for section in ["metadata", "topFunctions", "callTree"] {
                assert!(text.contains(&format!("\r\n{section},")));
            }
        }
        assert_eq!(
            payload(client.call("cpu_export_report", args))["artifactSha256"],
            report["artifactSha256"]
        );
    }
    let output = client.path.with_extension("never-created.csv");
    let response = client.call(
        "cpu_export_report",
        json!({"format":"csv","outputPath":output}),
    );
    assert_eq!(response["error"]["code"], -32602);
    assert!(!output.exists());
}

#[test]
fn p2_rejects_unknown_arguments_and_invalidated_current_profile_for_every_tool() {
    let mut client = Client::new();
    for (tool, args) in [
        ("cpu_flame_blocks", json!({"weightType":"events"})),
        (
            "cpu_flame_blocks",
            json!({"endFraction":0.1,"startFraction":0.2}),
        ),
        ("cpu_flame_blocks", json!({"maxBlocks":513})),
        ("cpu_flame_blocks", json!({"event":"no-such-event"})),
        ("cpu_flame_blocks", json!({"path":"new.pb"})),
        ("cpu_stack_blocks", json!({})),
        ("cpu_stack_blocks", json!({"startNs":1,"endNs":1})),
        (
            "cpu_stack_blocks",
            json!({"startNs":1,"endNs":2,"threadId":111222}),
        ),
        (
            "cpu_stack_blocks",
            json!({"startNs":1,"endNs":2,"depthCount":0}),
        ),
        ("cpu_export_report", json!({"format":"html"})),
        ("cpu_export_report", json!({"format":null})),
        ("cpu_export_report", json!({"limit":101})),
        ("cpu_export_report", json!({"weightType":"events"})),
        ("cpu_export_report", json!({"offset":10})),
    ] {
        assert_eq!(
            client.call(tool, args.clone())["error"]["code"],
            -32602,
            "{tool}: {args}"
        );
    }
    std::fs::write(&client.path, b"replaced").unwrap();
    for (tool, args) in [
        ("cpu_flame_blocks", json!({})),
        ("cpu_stack_blocks", json!({"startNs":10,"endNs":20})),
        ("cpu_export_report", json!({})),
    ] {
        let failed = client.call(tool, args);
        assert_eq!(failed["error"]["code"], -32001);
        assert!(failed.get("result").is_none());
    }
}

#[test]
fn p2_rejects_oversized_json_and_csv_artifacts_without_leaking_partial_content() {
    use aps_simpleperf_engine::simpleperf::{FileRecord, Record, SimpleperfStream};
    let source = SimpleperfStream {
        version: 1,
        records: vec![
            common::sample(1, 1, 0, 1, &[0]),
            Record::File(FileRecord {
                id: 7,
                path: "/private/fixture.so".into(),
                symbols: vec!["x".repeat(129 * 1024)],
            }),
        ],
    };
    let mut client = Client::bytes(common::encoded(&source));
    for format in ["json", "csv"] {
        let response = client.call("cpu_export_report", json!({"format":format,"limit":1}));
        assert_eq!(response["error"]["code"], -32002);
        assert!(response.get("result").is_none());
    }
    assert_eq!(
        payload(client.call("cpu_session_summary", json!({})))["sampleCount"],
        1
    );
}

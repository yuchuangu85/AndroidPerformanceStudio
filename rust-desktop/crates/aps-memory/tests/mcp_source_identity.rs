use aps_hprof_engine::cache::sha256_file;
use aps_platform::{CancellationToken, ProcessRequest, ProcessRunner, TempWorkspace};
use serde_json::{Value, json};
use std::path::Path;

fn query(hprof: &Path) -> Vec<Value> {
    let input = [
        json!({"jsonrpc":"2.0","id":1,"method":"initialize"}),
        json!({"jsonrpc":"2.0","method":"notifications/initialized"}),
        json!({"jsonrpc":"2.0","id":2,"method":"tools/list"}),
        json!({"jsonrpc":"2.0","id":"memory_snapshot_summary","method":"tools/call","params":{"name":"memory_snapshot_summary","arguments":{}}}),
        json!({"jsonrpc":"2.0","id":"memory_leak_findings","method":"tools/call","params":{"name":"memory_leak_findings","arguments":{}}}),
        json!({"jsonrpc":"2.0","id":"memory_class_histogram","method":"tools/call","params":{"name":"memory_class_histogram","arguments":{"limit":50}}}),
    ]
    .iter()
    .map(Value::to_string)
    .collect::<Vec<_>>()
    .join("\n")
        + "\n";
    let request = ProcessRequest::new(env!("CARGO_BIN_EXE_aps-mcp"))
        .arg("--hprof")
        .arg(hprof)
        .arg("--worker")
        .arg(env!("CARGO_BIN_EXE_aps-memory-worker"))
        .stdin_bytes(input);
    let output = ProcessRunner
        .run(&request, &CancellationToken::new())
        .unwrap();
    assert!(output.status.success());
    assert!(output.stderr.is_empty());
    let stdout = String::from_utf8(output.stdout).unwrap();
    assert!(!stdout.contains(hprof.to_str().unwrap()));
    stdout
        .lines()
        .map(|line| serde_json::from_str(line).unwrap())
        .collect()
}

#[test]
fn every_prefetched_tool_carries_the_full_identity_of_its_real_worker_snapshot() {
    let workspace = TempWorkspace::create(&std::env::temp_dir()).unwrap();
    let mut digests = Vec::new();
    for timestamp in [1_u64, 2] {
        let hprof = workspace.path().join(format!("snapshot-{timestamp}.hprof"));
        let mut bytes = b"JAVA PROFILE 1.0.3\0".to_vec();
        bytes.extend(4_u32.to_be_bytes());
        bytes.extend(timestamp.to_be_bytes());
        std::fs::write(&hprof, bytes).unwrap();
        let digest = sha256_file(&hprof).unwrap();
        let responses = query(&hprof);
        assert_eq!(responses.len(), 5);
        for (name, response) in [
            "memory_snapshot_summary",
            "memory_leak_findings",
            "memory_class_histogram",
        ]
        .into_iter()
        .zip(&responses[2..])
        {
            assert_eq!(response["id"], name);
            assert_eq!(response["result"]["isError"], false);
            let payload = &response["result"]["structuredContent"];
            assert_eq!(payload["sourceDigest"], digest);
            let text: Value =
                serde_json::from_str(response["result"]["content"][0]["text"].as_str().unwrap())
                    .unwrap();
            assert_eq!(&text, payload);
        }
        assert_eq!(
            responses[3]["result"]["structuredContent"]["heuristicOnly"],
            true
        );
        assert_eq!(responses[4]["result"]["structuredContent"]["total"], 0);
        assert_eq!(
            responses[4]["result"]["structuredContent"]["rows"],
            json!([])
        );
        digests.push(digest);
    }
    assert_ne!(digests[0], digests[1]);
}

//! Read-only MCP server for one selected HPROF and an optional comparison baseline.
use aps_hprof_engine::cache::sha256_file;
use aps_hprof_engine::wire::Value as WireValue;
use aps_memory::{
    ClassDelta, FindingKind, HistogramQuery, HistogramSort, MemorySession, SnapshotComparison,
    compare_snapshots,
};
use aps_platform::CancellationToken;
use serde_json::{Map, Value, json};
use std::ffi::OsString;
use std::io::{self, BufRead, Write};
use std::path::PathBuf;

const PROTOCOL_VERSION: &str = "2024-11-05";
const MAX_OUTPUT_BYTES: usize = 512 * 1024;
const USAGE: &str =
    "usage: aps-mcp --hprof <B> --worker <aps-memory-worker> [--baseline-hprof <A>]";

struct Options {
    hprof: PathBuf,
    worker: PathBuf,
    baseline: Option<PathBuf>,
}

struct McpServer {
    current: MemorySession,
    baseline: Option<MemorySession>,
    comparison: Option<SnapshotComparison>,
}

impl McpServer {
    fn open(options: &Options, cancel: &CancellationToken) -> Result<Self, String> {
        let digest = sha256_file(&options.hprof)?;
        let current = MemorySession::open(&options.worker, &options.hprof, &digest, cancel)
            .map_err(|error| error.to_string())?;
        let baseline = options
            .baseline
            .as_ref()
            .map(|path| {
                let digest = sha256_file(path)?;
                MemorySession::open(&options.worker, path, &digest, cancel)
                    .map_err(|error| error.to_string())
            })
            .transpose()?;
        Ok(Self {
            current,
            baseline,
            comparison: None,
        })
    }

    fn snapshot_diff(
        &mut self,
        offset: usize,
        limit: usize,
        cancel: &CancellationToken,
    ) -> Result<Value, String> {
        let result = (|| {
            let baseline = self
                .baseline
                .as_mut()
                .ok_or("Snapshot comparison requires --baseline-hprof at server startup")?;
            // Every page bypasses query caches, including a retry after failure.
            baseline
                .check_source(cancel)
                .map_err(|error| error.to_string())?;
            self.current
                .check_source(cancel)
                .map_err(|error| error.to_string())?;
            if self.comparison.is_none() {
                let before = baseline
                    .snapshot(cancel)
                    .map_err(|error| error.to_string())?;
                let after = self
                    .current
                    .snapshot(cancel)
                    .map_err(|error| error.to_string())?;
                let report = compare_snapshots(&before, &after, "A".into(), "B".into())
                    .map_err(|error| error.to_string())?;
                // A later page must not conceal an invalid delta elsewhere.
                for row in &report.rows {
                    validate_deltas(row)?;
                }
                // The first source may have changed while the second was read.
                baseline
                    .check_source(cancel)
                    .map_err(|error| error.to_string())?;
                self.current
                    .check_source(cancel)
                    .map_err(|error| error.to_string())?;
                self.comparison = Some(report);
            }
            comparison_page(
                self.comparison
                    .as_ref()
                    .ok_or("Comparison is unavailable")?,
                offset,
                limit,
            )
        })();
        if result.is_err() {
            self.comparison = None;
        }
        result
    }
}

fn main() {
    if let Err(error) = run() {
        let _ = writeln!(io::stderr(), "aps-mcp: {error}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), String> {
    let options = args()?;
    let cancel = CancellationToken::new();
    let mut server = McpServer::open(&options, &cancel)?;
    let stdin = io::stdin();
    let mut stdout = io::BufWriter::new(io::stdout());
    for line in stdin.lock().lines() {
        let line = line.map_err(|error| error.to_string())?;
        if line.trim().is_empty() {
            continue;
        }
        let request: Value = serde_json::from_str(&line).map_err(|_| "invalid JSON-RPC input")?;
        if request.get("id").is_none() {
            continue;
        }
        let response = dispatch(&mut server, &cancel, &request);
        serde_json::to_writer(&mut stdout, &response).map_err(|error| error.to_string())?;
        stdout.write_all(b"\n").map_err(|error| error.to_string())?;
        stdout.flush().map_err(|error| error.to_string())?;
    }
    Ok(())
}

fn args() -> Result<Options, String> {
    let arguments: Vec<_> = std::env::args_os().skip(1).collect();
    let options = parse_args(&arguments)?;
    if !options.hprof.is_file()
        || !options.worker.is_file()
        || options
            .baseline
            .as_ref()
            .is_some_and(|path| !path.is_file())
    {
        return Err("MCP inputs must be regular files".into());
    }
    Ok(options)
}

fn parse_args(arguments: &[OsString]) -> Result<Options, String> {
    let (mut hprof, mut worker, mut baseline) = (None, None, None);
    let (pairs, remainder) = arguments.as_chunks::<2>();
    if !remainder.is_empty() {
        return Err(USAGE.into());
    }
    for [flag, value] in pairs {
        let slot = match flag.to_str() {
            Some("--hprof") => &mut hprof,
            Some("--worker") => &mut worker,
            Some("--baseline-hprof") => &mut baseline,
            _ => return Err(USAGE.into()),
        };
        if slot.replace(PathBuf::from(value)).is_some() {
            return Err(USAGE.into());
        }
    }
    Ok(Options {
        hprof: hprof.ok_or(USAGE)?,
        worker: worker.ok_or(USAGE)?,
        baseline,
    })
}

fn dispatch(server: &mut McpServer, cancel: &CancellationToken, request: &Value) -> Value {
    let id = request.get("id").cloned().unwrap_or(Value::Null);
    let method = request.get("method").and_then(Value::as_str).unwrap_or("");
    let result = match method {
        "initialize" => Ok(json!({
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {"tools": {"listChanged": false}},
            "serverInfo": {"name": "aps-mcp", "version": env!("CARGO_PKG_VERSION")}
        })),
        "tools/list" => Ok(tools()),
        "tools/call" => call_tool(server, cancel, request),
        _ => Err((-32601, "method not found".to_owned())),
    };
    match result {
        Ok(result) => json!({"jsonrpc":"2.0", "id":id, "result":result}),
        Err((code, message)) => {
            json!({"jsonrpc":"2.0", "id":id, "error":{"code":code,"message":message}})
        }
    }
}

fn tools() -> Value {
    json!({"tools":[
        {"name":"memory_snapshot_summary","description":"Bounded metadata and whole-snapshot summary for the selected HPROF.","inputSchema":{"type":"object","additionalProperties":false}},
        {"name":"memory_leak_findings","description":"Bounded HPROF leak candidates, lifecycle candidates, and reference chains.","inputSchema":{"type":"object","additionalProperties":false}},
        {"name":"memory_class_histogram","description":"Bounded class histogram page with optional heap and class-name filter.","inputSchema":{"type":"object","properties":{"heapName":{"type":"string"},"search":{"type":"string"},"limit":{"type":"integer","minimum":1,"maximum":100}},"additionalProperties":false}},
        {"name":"memory_object_detail","description":"Bounded fields, inbound references, and GC-root evidence for one object.","inputSchema":{"type":"object","required":["objectId"],"properties":{"objectId":{"type":"string"},"offset":{"type":"integer","minimum":0,"maximum":1000},"limit":{"type":"integer","minimum":1,"maximum":100}},"additionalProperties":false}},
        {"name":"memory_snapshot_diff","description":"Class-level B minus A comparison; requires --baseline-hprof A and --hprof B at server startup. Object identity does not cross snapshots.","inputSchema":{"type":"object","properties":{"offset":{"type":"integer","minimum":0},"limit":{"type":"integer","minimum":1,"maximum":100}},"additionalProperties":false}}
    ]})
}

fn call_tool(
    server: &mut McpServer,
    cancel: &CancellationToken,
    request: &Value,
) -> Result<Value, (i64, String)> {
    let params = request.get("params").cloned().unwrap_or_else(|| json!({}));
    let name = params
        .get("name")
        .and_then(Value::as_str)
        .ok_or((-32602, "missing tool name".into()))?;
    let args = params
        .get("arguments")
        .cloned()
        .unwrap_or_else(|| json!({}));
    let payload = if name == "memory_snapshot_diff" {
        let (offset, limit) = comparison_args(&args)?;
        server.snapshot_diff(offset, limit, cancel)
    } else {
        let digest = server.current.source_digest().to_owned();
        let session = &mut server.current;
        match name {
            "memory_snapshot_summary" => snapshot_summary(session, &digest, cancel),
            "memory_leak_findings" => leak_findings(session, &digest, cancel),
            "memory_class_histogram" => class_histogram(session, &args, cancel),
            "memory_object_detail" => object_detail(session, &args, cancel),
            _ => Err("unknown memory tool".into()),
        }
    }
    .map_err(|error| (-32001, error))?;
    let text = serde_json::to_string(&payload).map_err(|error| (-32001, error.to_string()))?;
    if text.len() > MAX_OUTPUT_BYTES {
        return Err((-32002, "MCP tool output exceeds the bounded budget".into()));
    }
    Ok(json!({"content":[{"type":"text","text":text}],"structuredContent":payload,"isError":false}))
}

fn comparison_args(value: &Value) -> Result<(usize, usize), (i64, String)> {
    let invalid = || {
        (
            -32602,
            "Snapshot diff accepts only offset >= 0 and limit from 1 to 100".into(),
        )
    };
    let arguments = value.as_object().ok_or_else(invalid)?;
    if arguments
        .keys()
        .any(|key| !matches!(key.as_str(), "offset" | "limit"))
    {
        return Err(invalid());
    }
    let read = |key: &str, default: u64| -> Result<usize, (i64, String)> {
        let number = match arguments.get(key) {
            None => default,
            Some(value) => value.as_u64().ok_or_else(invalid)?,
        };
        usize::try_from(number).map_err(|_| invalid())
    };
    let offset = read("offset", 0)?;
    let limit = read("limit", 50)?;
    if !(1..=100).contains(&limit) {
        return Err(invalid());
    }
    Ok((offset, limit))
}

fn delta(after: u64, before: u64) -> Result<i64, String> {
    i64::try_from(i128::from(after) - i128::from(before))
        .map_err(|_| "Snapshot diff exceeds the signed JSON delta range".into())
}

fn validate_deltas(row: &ClassDelta) -> Result<(), String> {
    delta(row.b_count, row.a_count)?;
    delta(row.b_shallow, row.a_shallow)?;
    if let Some((before, after)) = row.a_retained.zip(row.b_retained) {
        delta(after, before)?;
    }
    Ok(())
}

fn comparison_page(
    report: &SnapshotComparison,
    offset: usize,
    limit: usize,
) -> Result<Value, String> {
    let rows = report
        .rows
        .iter()
        .skip(offset)
        .take(limit)
        .map(|row| {
            Ok(json!({
                "className": row.class_name,
                "beforeCount": row.a_count,
                "afterCount": row.b_count,
                "countDelta": delta(row.b_count, row.a_count)?,
                "beforeShallowSizeBytes": row.a_shallow,
                "afterShallowSizeBytes": row.b_shallow,
                "shallowSizeDeltaBytes": delta(row.b_shallow, row.a_shallow)?,
                "beforeRetainedSizeBytes": row.a_retained,
                "afterRetainedSizeBytes": row.b_retained,
                "retainedSizeDeltaBytes": row.a_retained.zip(row.b_retained)
                    .map(|(before, after)| delta(after, before)).transpose()?,
            }))
        })
        .collect::<Result<Vec<_>, String>>()?;
    let total = report.rows.len();
    let included = rows.len();
    Ok(json!({
        "policyVersion": "aps-memory-snapshot-diff-v1",
        "direction": "B-A",
        "beforeSnapshotId": report.a_digest,
        "afterSnapshotId": report.b_digest,
        "beforeMappingDigest": report.a_mapping_digest,
        "afterMappingDigest": report.b_mapping_digest,
        "total": total,
        "offset": offset,
        "limit": limit,
        "hasMore": offset.saturating_add(included) < total,
        "coverage": {
            "beforeClassCount": report.a_class_count,
            "afterClassCount": report.b_class_count,
            "beforeLoadedClasses": report.a_loaded_classes,
            "afterLoadedClasses": report.b_loaded_classes,
            "included": included,
            "omitted": total - included,
            "truncated": included < total,
        },
        "rows": rows,
        "evidenceBoundary": "Class-name matching only; build identity unverified; no cross-dump object identity; retained sizes may overlap and unavailable retained deltas remain unknown",
    }))
}

fn snapshot_summary(
    session: &mut MemorySession,
    digest: &str,
    cancel: &CancellationToken,
) -> Result<Value, String> {
    let histogram = session
        .histogram(None, 0, 1, cancel)
        .map_err(|error| error.to_string())?;
    Ok(
        json!({"sourceDigest":digest,"metadata":wire_json(session.metadata()),"summary":wire_json(histogram.get("summary").map_err(|error| error.to_string())?),"evidenceBoundary":"offline HPROF; unavailable metrics remain unknown"}),
    )
}

fn leak_findings(
    session: &mut MemorySession,
    digest: &str,
    cancel: &CancellationToken,
) -> Result<Value, String> {
    let mut findings = Map::new();
    for kind in [
        FindingKind::LeakSuspects,
        FindingKind::ActivityLeaks,
        FindingKind::LifecycleLeakObjects,
    ] {
        let value = session
            .findings(kind, 0, 32, cancel)
            .map_err(|error| error.to_string())?;
        let key = match kind {
            FindingKind::LeakSuspects => "leakSuspects",
            FindingKind::ActivityLeaks => "activityLeaks",
            FindingKind::LifecycleLeakObjects => "lifecycleLeakObjects",
            FindingKind::BitmapInstances => "bitmapInstances",
        };
        findings.insert(key.to_owned(), wire_json(&value));
    }
    Ok(
        json!({"sourceDigest":digest,"heuristicOnly":true,"findings":findings,"evidenceBoundary":"candidates are not confirmed leaks; omitted rows remain unknown"}),
    )
}

fn class_histogram(
    session: &mut MemorySession,
    args: &Value,
    cancel: &CancellationToken,
) -> Result<Value, String> {
    let search = args.get("search").and_then(Value::as_str).unwrap_or("");
    if search.len() > 1024 {
        return Err("search exceeds MCP limit".into());
    }
    let limit = args
        .get("limit")
        .and_then(Value::as_u64)
        .unwrap_or(50)
        .min(100) as usize;
    let query = HistogramQuery {
        search: search.to_owned(),
        match_case: false,
        sort: HistogramSort::RetainedDescending,
    };
    let histogram = session
        .histogram_query(
            args.get("heapName").and_then(Value::as_str),
            &query,
            0,
            limit,
            cancel,
        )
        .map_err(|error| error.to_string())?;
    let Value::Object(mut payload) = wire_json(&histogram) else {
        return Err("invalid histogram payload".into());
    };
    payload.insert("sourceDigest".into(), session.source_digest().into());
    Ok(Value::Object(payload))
}

fn object_detail(
    session: &mut MemorySession,
    args: &Value,
    cancel: &CancellationToken,
) -> Result<Value, String> {
    let raw = args
        .get("objectId")
        .and_then(Value::as_str)
        .ok_or("objectId must be a hexadecimal string")?;
    let object_id = raw
        .strip_prefix("0x")
        .unwrap_or(raw)
        .parse::<u64>()
        .map_err(|_| "objectId is invalid")?;
    let offset = args
        .get("offset")
        .and_then(Value::as_u64)
        .unwrap_or(0)
        .min(1000) as usize;
    let limit = args
        .get("limit")
        .and_then(Value::as_u64)
        .unwrap_or(100)
        .min(100) as usize;
    session
        .detail(object_id, offset, limit, cancel)
        .map(|value| wire_json(&value))
        .map_err(|error| error.to_string())
}

fn wire_json(value: &WireValue) -> Value {
    match value {
        WireValue::Null => Value::Null,
        WireValue::Bool(value) => Value::Bool(*value),
        WireValue::Int(value) => json!(value),
        WireValue::String(value) => Value::String(value.clone()),
        WireValue::List(values) => Value::Array(values.iter().map(wire_json).collect()),
        WireValue::Map(values) => Value::Object(
            values
                .iter()
                .map(|(key, value)| (key.clone(), wire_json(value)))
                .collect(),
        ),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tool_catalog_is_read_only_and_memory_scoped() {
        let tools = tools();
        let names = tools["tools"]
            .as_array()
            .unwrap()
            .iter()
            .map(|tool| tool["name"].as_str().unwrap())
            .collect::<Vec<_>>();
        assert_eq!(
            names,
            vec![
                "memory_snapshot_summary",
                "memory_leak_findings",
                "memory_class_histogram",
                "memory_object_detail",
                "memory_snapshot_diff"
            ]
        );
        assert!(tools.to_string().contains("additionalProperties"));
        assert!(!tools.to_string().contains("shell"));
        assert!(!tools.to_string().contains("network"));
    }

    fn options(values: &[&str]) -> Result<Options, String> {
        parse_args(&values.iter().map(OsString::from).collect::<Vec<_>>())
    }

    #[test]
    fn cli_keeps_legacy_mode_and_binds_optional_baseline_independently_of_flag_order() {
        let single = options(&["--hprof", "B.hprof", "--worker", "worker"]).unwrap();
        assert_eq!(single.hprof, PathBuf::from("B.hprof"));
        assert_eq!(single.worker, PathBuf::from("worker"));
        assert!(single.baseline.is_none());
        let pair = options(&[
            "--worker",
            "worker",
            "--baseline-hprof",
            "A.hprof",
            "--hprof",
            "B.hprof",
        ])
        .unwrap();
        assert_eq!(pair.hprof, PathBuf::from("B.hprof"));
        assert_eq!(pair.baseline, Some(PathBuf::from("A.hprof")));
    }

    #[test]
    fn cli_rejects_missing_unknown_duplicate_and_unpaired_flags() {
        for arguments in [
            vec![],
            vec!["--hprof", "B.hprof"],
            vec!["--hprof", "B.hprof", "--worker"],
            vec![
                "--hprof",
                "B.hprof",
                "--worker",
                "worker",
                "--unknown",
                "A.hprof",
            ],
            vec![
                "--hprof",
                "B.hprof",
                "--worker",
                "worker",
                "--hprof",
                "other.hprof",
            ],
            vec![
                "--hprof", "B.hprof", "--worker", "worker", "--worker", "other",
            ],
            vec![
                "--hprof",
                "B.hprof",
                "--worker",
                "worker",
                "--baseline-hprof",
                "A.hprof",
                "--baseline-hprof",
                "other.hprof",
            ],
        ] {
            assert!(options(&arguments).is_err());
        }
    }

    #[test]
    fn diff_arguments_reject_bad_types_ranges_and_arbitrary_source_paths() {
        assert_eq!(comparison_args(&json!({})).unwrap(), (0, 50));
        assert_eq!(
            comparison_args(&json!({"offset":4,"limit":100})).unwrap(),
            (4, 100)
        );
        for value in [
            Value::Null,
            json!([]),
            json!({"offset":-1}),
            json!({"offset":1.5}),
            json!({"offset":"0"}),
            json!({"limit":0}),
            json!({"limit":101}),
            json!({"limit":null}),
            json!({"limit":true}),
            json!({"path":"other.hprof"}),
            json!({"beforeSnapshotId":"a".repeat(64)}),
        ] {
            assert_eq!(comparison_args(&value).unwrap_err().0, -32602);
        }
    }

    fn comparison_fixture() -> SnapshotComparison {
        let snapshot = |digest: &str, entries: &[(&str, u64)]| aps_memory::MemorySnapshot {
            source_digest: digest.repeat(64),
            metadata: aps_memory::SnapshotMetadata {
                class_count: entries.len() as u64,
                ..Default::default()
            },
            classes: entries
                .iter()
                .map(|(name, count)| aps_memory::ClassStats {
                    class_name: (*name).into(),
                    instance_count: *count,
                    shallow_size: count * 8,
                    retained_size: Some(count * 16),
                    ..Default::default()
                })
                .collect(),
            ..Default::default()
        };
        compare_snapshots(
            &snapshot("a", &[("Drop", 2), ("Grew", 1), ("Shrank", 5)]),
            &snapshot("b", &[("Add", 3), ("Grew", 3), ("Shrank", 2)]),
            "A".into(),
            "B".into(),
        )
        .unwrap()
    }

    #[test]
    fn diff_pages_keep_frozen_order_source_identity_direction_and_unknown_retained() {
        let report = comparison_fixture();
        let page = comparison_page(&report, 1, 2).unwrap();
        assert_eq!(page["policyVersion"], "aps-memory-snapshot-diff-v1");
        assert_eq!(page["direction"], "B-A");
        assert_eq!(page["beforeSnapshotId"], "a".repeat(64));
        assert_eq!(page["afterSnapshotId"], "b".repeat(64));
        assert!(page["beforeMappingDigest"].is_null());
        assert_eq!(page["total"], 4);
        assert_eq!(page["offset"], 1);
        assert_eq!(page["hasMore"], true);
        assert_eq!(page["coverage"]["included"], 2);
        assert_eq!(page["coverage"]["omitted"], 2);
        assert_eq!(page["coverage"]["beforeLoadedClasses"], 3);
        assert_eq!(page["rows"][0]["className"], "Grew");
        assert_eq!(page["rows"][0]["countDelta"], 2);
        assert_eq!(page["rows"][0]["shallowSizeDeltaBytes"], 16);
        assert_eq!(page["rows"][0]["retainedSizeDeltaBytes"], 32);
        assert_eq!(page["rows"][1]["className"], "Add");
        assert!(page["rows"][1]["beforeRetainedSizeBytes"].is_null());
        assert!(page["rows"][1]["retainedSizeDeltaBytes"].is_null());
        let first = comparison_page(&report, 0, 1).unwrap();
        assert_eq!(first["rows"][0]["className"], "Shrank");
        assert_eq!(first["rows"][0]["countDelta"], -3);
        assert_eq!(first["rows"][0]["retainedSizeDeltaBytes"], -48);
        assert!(!page.to_string().contains("objectId"));
    }

    #[test]
    fn past_end_and_empty_comparisons_keep_explicit_page_coverage() {
        let report = comparison_fixture();
        let page = comparison_page(&report, usize::MAX, 50).unwrap();
        assert_eq!(page["rows"], json!([]));
        assert_eq!(page["hasMore"], false);
        assert_eq!(page["coverage"]["included"], 0);
        assert_eq!(page["coverage"]["omitted"], 4);
        assert_eq!(page["coverage"]["truncated"], true);
        let mut empty = report;
        empty.rows.clear();
        let page = comparison_page(&empty, 0, 50).unwrap();
        assert_eq!(page["total"], 0);
        assert_eq!(page["hasMore"], false);
        assert_eq!(page["coverage"]["truncated"], false);
    }

    #[test]
    fn comparison_deltas_fail_instead_of_overflowing_or_fabricating_unknown_values() {
        assert_eq!(delta(i64::MAX as u64, 0).unwrap(), i64::MAX);
        assert_eq!(delta(0, i64::MAX as u64).unwrap(), -i64::MAX);
        assert!(delta(u64::MAX, 0).is_err());
        assert!(delta(0, u64::MAX).is_err());
        for row in [
            ClassDelta {
                a_count: u64::MAX,
                ..Default::default()
            },
            ClassDelta {
                b_shallow: u64::MAX,
                ..Default::default()
            },
            ClassDelta {
                a_retained: Some(0),
                b_retained: Some(u64::MAX),
                ..Default::default()
            },
        ] {
            assert!(validate_deltas(&row).is_err());
        }
        let unknown = ClassDelta {
            a_retained: None,
            b_retained: Some(u64::MAX),
            ..Default::default()
        };
        assert!(validate_deltas(&unknown).is_ok());
    }

    #[test]
    fn wire_values_convert_to_json_without_paths_or_raw_frames() {
        let value = WireValue::map([
            ("sourceDigest", "a".repeat(64).into()),
            (
                "rows",
                WireValue::List(vec![1i64.into(), "candidate".into()]),
            ),
        ]);
        let json = wire_json(&value);
        assert_eq!(json["rows"][0], 1);
        assert_eq!(json["rows"][1], "candidate");
        assert!(json.get("path").is_none());
    }
}

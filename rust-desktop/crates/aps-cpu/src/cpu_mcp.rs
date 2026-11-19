//! Read-only MCP server for a host-bound CPU profile and an optional baseline.
//!
//! The host binds all inputs at startup. Tool calls never receive a path,
//! command, converter, or arbitrary source selector from the MCP client.

use aps_contracts::SourceDigest;
use aps_cpu::{CpuSession, Direction, Query, TopSort, WeightType};
use serde_json::{json, Value};
use std::io::{self, BufRead, Read, Write};
use std::path::{Path, PathBuf};

#[path = "cpu_mcp/blocks.rs"]
mod blocks;
#[path = "cpu_mcp/compare.rs"]
mod compare;
#[path = "cpu_mcp/report.rs"]
mod report;
#[path = "cpu_mcp/timeline.rs"]
mod timeline;

const PROTOCOL_VERSION: &str = "2024-11-05";
const MAX_OUTPUT_BYTES: usize = 512 * 1024;
const MAX_PAGE_LIMIT: usize = 100;
const MAX_CALL_TREE_LIMIT: usize = 2_000;
const MAX_REQUEST_BYTES: usize = 64 * 1024;
const USAGE: &str = "usage: aps-cpu-mcp --cpu <current-profile> [--baseline-cpu <before-profile>]";

struct Options {
    cpu: PathBuf,
    baseline_cpu: Option<PathBuf>,
}

struct CpuMcpServer {
    current: BoundProfile,
    baseline: Option<BoundProfile>,
}

struct BoundProfile {
    path: PathBuf,
    digest: SourceDigest,
    input_format: &'static str,
    session: CpuSession,
    invalidated: bool,
}

impl BoundProfile {
    fn open(path: &Path) -> Result<Self, String> {
        if !path.is_file() {
            return Err("selected CPU profile must be a regular file".into());
        }
        let file = std::fs::File::open(path).map_err(|_| "cannot open selected CPU profile")?;
        let metadata = file
            .metadata()
            .map_err(|_| "cannot inspect selected CPU profile")?;
        if !metadata.is_file() || metadata.len() > aps_cpu::DEFAULT_INPUT_LIMIT as u64 {
            return Err(
                "selected profile must be a regular file within the 128 MiB input budget".into(),
            );
        }
        let mut bytes = Vec::new();
        file.take(aps_cpu::DEFAULT_INPUT_LIMIT as u64 + 1)
            .read_to_end(&mut bytes)
            .map_err(|_| "cannot read selected CPU profile")?;
        let digest =
            SourceDigest::hash_reader(bytes.as_slice(), aps_cpu::DEFAULT_INPUT_LIMIT as u64)?;
        let mut session = CpuSession::from_bytes(&bytes).map_err(|_| {
            "cannot parse selected CPU profile; perf.data must be converted before starting aps-cpu-mcp"
        })?;
        let input_format = if bytes.iter().find(|byte| !byte.is_ascii_whitespace()) == Some(&b'{') {
            "gecko_v24"
        } else {
            "simpleperf_protobuf"
        };
        session.rebind_path(path);
        if SourceDigest::hash_file(path, aps_cpu::DEFAULT_INPUT_LIMIT as u64)? != digest {
            return Err("CPU profile changed while opening selected input".into());
        }
        Ok(Self {
            path: path.to_owned(),
            digest,
            input_format,
            session,
            invalidated: false,
        })
    }

    fn check_source(&mut self) -> Result<(), String> {
        if self.invalidated {
            return Err("CPU profile invalidated; restart with the selected input".into());
        }
        if !matches!(SourceDigest::hash_file(&self.path, aps_cpu::DEFAULT_INPUT_LIMIT as u64),
            Ok(digest) if digest == self.digest)
        {
            self.invalidated = true;
            return Err(
                "CPU profile changed or is unavailable; restart with the selected input".into(),
            );
        }
        Ok(())
    }

    fn session_summary(&self) -> Result<Value, String> {
        let summary = self.session.summary();
        Ok(json!({
            "policyVersion": "aps-cpu-mcp-summary-v1",
            "sourceDigest": self.digest.to_hex(),
            "inputFormat": self.input_format,
            "formatVersion": summary.format_version,
            "recordCount": summary.record_count,
            "sampleCount": summary.sample_count,
            "reportedSampleCount": summary.reported_sample_count,
            "lostSampleCount": summary.lost_sample_count,
            "lostRate": (summary.reported_sample_count > 0).then(|| summary.lost_rate()),
            "lossRateAvailable": summary.reported_sample_count > 0,
            "totalEventCount": summary.total_event_count,
            "firstTimestampNs": summary.first_timestamp_nanos,
            "lastTimestampNs": summary.last_timestamp_nanos,
            "threadCount": summary.thread_count,
            "fileCount": summary.file_count,
            "eventTypes": summary.event_types,
            "traceOffcpu": summary.trace_offcpu,
            "evidenceQuality": {
                "samplesAreStatistical": true,
                "methodDurationAvailable": false,
                "deviceCaptureCompletenessVerified": false
            }
        }))
    }

    fn top_functions(&mut self, args: &Value) -> Result<Value, String> {
        let query = query_from_args(args, MAX_PAGE_LIMIT)?;
        let offset = read_usize(args, "offset", 0, usize::MAX)?;
        let limit = read_usize(args, "limit", 50, MAX_PAGE_LIMIT)?;
        let page = self.session.top_functions_sorted(
            &query,
            TopSort::InclusiveWeight,
            true,
            offset,
            limit,
        )?;
        let rows = page
            .rows
            .into_iter()
            .map(|row| {
                json!({
                    "id": row.id,
                    "name": row.name,
                    "resource": sanitize_resource(&row.resource),
                    "inclusiveWeight": row.inclusive_weight,
                    "selfWeight": row.self_weight,
                    "sampleCount": row.sample_count,
                    "selfSampleCount": row.self_sample_count,
                    "threadCount": row.thread_count
                })
            })
            .collect::<Vec<_>>();
        Ok(json!({
            "policyVersion": "aps-cpu-mcp-top-functions-v1",
            "sourceDigest": self.digest.to_hex(),
            "query": query_json(&query, offset, limit),
            "total": page.total,
            "offset": page.offset,
            "limit": limit,
            "hasMore": offset.saturating_add(rows.len()) < page.total,
            "resourcePathsRedacted": true,
            "rows": rows
        }))
    }

    fn call_tree(&mut self, args: &Value) -> Result<Value, String> {
        let query = query_from_args(args, MAX_CALL_TREE_LIMIT)?;
        let offset = read_usize(args, "offset", 0, usize::MAX)?;
        let limit = read_usize(args, "limit", 200, MAX_CALL_TREE_LIMIT)?;
        // The engine's internal page cap is 512, independent of the MCP cap.
        // Reuse its cached projection while assembling at most 2,000 rows.
        let mut all_rows = Vec::new();
        let total = loop {
            let page = self.session.query(&Query {
                offset: offset + all_rows.len(),
                limit: (limit - all_rows.len()).min(aps_cpu::MAX_PAGE_SIZE),
                ..query.clone()
            })?;
            let total = page.total;
            all_rows.extend(page.rows);
            if all_rows.len() == limit || offset + all_rows.len() >= total {
                break total;
            }
        };
        let rows = all_rows
            .into_iter()
            .map(|row| {
                json!({
                    "id": row.id,
                    "parentId": row.parent_id,
                    "depth": row.depth,
                    "name": row.name,
                    "resource": sanitize_resource(&row.resource),
                    "inclusiveWeight": row.inclusive_weight,
                    "selfWeight": row.self_weight,
                    "sampleCount": row.sample_count,
                    "selfSampleCount": row.self_sample_count
                })
            })
            .collect::<Vec<_>>();
        Ok(json!({
            "policyVersion": "aps-cpu-mcp-call-tree-v1",
            "sourceDigest": self.digest.to_hex(),
            "query": query_json(&query, offset, limit),
            "total": total,
            "offset": offset,
            "limit": limit,
            "hasMore": offset.saturating_add(rows.len()) < total,
            "resourcePathsRedacted": true,
            "rows": rows
        }))
    }
}

fn main() {
    if let Err(error) = run() {
        let _ = writeln!(io::stderr(), "aps-cpu-mcp: {error}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), String> {
    let options = parse_args(&std::env::args_os().skip(1).collect::<Vec<_>>())?;
    let mut server = CpuMcpServer {
        current: BoundProfile::open(&options.cpu)?,
        baseline: options
            .baseline_cpu
            .as_deref()
            .map(BoundProfile::open)
            .transpose()?,
    };
    let stdin = io::stdin();
    let mut stdout = io::BufWriter::new(io::stdout());
    let mut reader = stdin.lock();
    while let Some(line) = read_line(&mut reader)? {
        let response = match line {
            None => rpc_error(Value::Null, -32600, "Request exceeds 64 KiB"),
            Some(bytes) if bytes.iter().all(u8::is_ascii_whitespace) => continue,
            Some(bytes) => match aps_json::parse_slice(
                &bytes,
                aps_json::Limits {
                    max_bytes: MAX_REQUEST_BYTES as u64,
                    max_depth: 12,
                    max_nodes: 4096,
                    max_string_bytes: 2048,
                    max_collection_items: 128,
                },
            ) {
                Ok(request) if request.is_object() => {
                    if request.get("id").is_none() {
                        continue;
                    }
                    dispatch(&mut server, &request)
                }
                Ok(_) => rpc_error(Value::Null, -32600, "Expected a JSON-RPC object"),
                Err(_) => rpc_error(Value::Null, -32700, "Malformed or unbounded JSON request"),
            },
        };
        serde_json::to_writer(&mut stdout, &response).map_err(|error| error.to_string())?;
        stdout.write_all(b"\n").map_err(|error| error.to_string())?;
        stdout.flush().map_err(|error| error.to_string())?;
    }
    Ok(())
}

// Consume the rest of an oversized line without growing its buffer.
fn read_line(reader: &mut impl BufRead) -> Result<Option<Option<Vec<u8>>>, String> {
    let mut line = Vec::new();
    let mut oversized = false;
    loop {
        let chunk = reader.fill_buf().map_err(|_| "cannot read MCP stdin")?;
        if chunk.is_empty() {
            return Ok(if oversized {
                Some(None)
            } else if line.is_empty() {
                None
            } else {
                Some(Some(line))
            });
        }
        let end = chunk.iter().position(|&byte| byte == b'\n');
        let length = end.map_or(chunk.len(), |index| index + 1);
        if !oversized {
            if line.len().saturating_add(length) > MAX_REQUEST_BYTES {
                oversized = true;
                line.clear();
            } else {
                line.extend_from_slice(&chunk[..length]);
            }
        }
        reader.consume(length);
        if end.is_some() {
            return Ok(Some(if oversized { None } else { Some(line) }));
        }
    }
}

fn parse_args(arguments: &[std::ffi::OsString]) -> Result<Options, String> {
    let mut cpu = None;
    let mut baseline_cpu = None;
    let mut pairs = arguments.chunks_exact(2);
    for pair in &mut pairs {
        let target = if pair[0] == "--cpu" {
            &mut cpu
        } else if pair[0] == "--baseline-cpu" {
            &mut baseline_cpu
        } else {
            return Err(USAGE.into());
        };
        if target.is_some()
            || pair[1].is_empty()
            || pair[1] == "--cpu"
            || pair[1] == "--baseline-cpu"
        {
            return Err(USAGE.into());
        }
        *target = Some(PathBuf::from(&pair[1]));
    }
    if !pairs.remainder().is_empty() {
        return Err(USAGE.into());
    }
    Ok(Options {
        cpu: cpu.ok_or(USAGE)?,
        baseline_cpu,
    })
}

fn dispatch(server: &mut CpuMcpServer, request: &Value) -> Value {
    let id = request.get("id").cloned().unwrap_or(Value::Null);
    if request.get("jsonrpc").and_then(Value::as_str) != Some("2.0")
        || !(id.is_null() || id.is_string() || id.is_i64() || id.is_u64())
        || request.get("method").and_then(Value::as_str).is_none()
    {
        return rpc_error(Value::Null, -32600, "Invalid JSON-RPC request");
    }
    let method = request.get("method").and_then(Value::as_str).unwrap_or("");
    let result = match method {
        "initialize" => Ok(json!({
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {"tools": {"listChanged": false}},
            "serverInfo": {"name": "aps-cpu-mcp", "version": env!("CARGO_PKG_VERSION")}
        })),
        "notifications/initialized" => Ok(Value::Null),
        "ping" => Ok(json!({})),
        "tools/list" => Ok(tools_list()),
        "tools/call" => call_tool(server, request),
        _ => Err((-32601, "method not found".into())),
    };
    match result {
        Ok(value) => json!({"jsonrpc":"2.0", "id":id, "result":value}),
        Err((code, message)) => rpc_error(id, code, &message),
    }
}

fn rpc_error(id: Value, code: i32, message: &str) -> Value {
    json!({"jsonrpc":"2.0","id":id,"error":{"code":code,"message":message}})
}

fn tool_failure(error: String) -> (i32, String) {
    (-32001, error)
}

fn call_tool(server: &mut CpuMcpServer, request: &Value) -> Result<Value, (i32, String)> {
    let params = request
        .get("params")
        .and_then(Value::as_object)
        .ok_or((-32602, "missing params".into()))?;
    if params
        .keys()
        .any(|key| !matches!(key.as_str(), "name" | "arguments" | "_meta"))
    {
        return Err((-32602, "unknown tools/call parameter".into()));
    }
    let name = params
        .get("name")
        .and_then(Value::as_str)
        .ok_or((-32602, "missing tool name".into()))?;
    let args = params
        .get("arguments")
        .cloned()
        .unwrap_or_else(|| json!({}));
    match name {
        "cpu_session_summary" => {
            if !args.as_object().is_some_and(|object| object.is_empty()) {
                return Err((-32602, "summary arguments must be an empty object".into()));
            }
        }
        "cpu_top_functions" | "cpu_call_tree" => {
            let query = query_from_args(
                &args,
                if name == "cpu_top_functions" {
                    MAX_PAGE_LIMIT
                } else {
                    MAX_CALL_TREE_LIMIT
                },
            )
            .map_err(|error| (-32602, error))?;
            if query
                .event_types
                .iter()
                .any(|event| !server.current.session.events().contains(event))
            {
                return Err((-32602, "event is absent from the bound CPU profile".into()));
            }
        }
        "cpu_timeline_summary"
        | "cpu_compare_top_functions"
        | "cpu_flame_blocks"
        | "cpu_stack_blocks"
        | "cpu_export_report" => {}
        _ => return Err((-32602, "unknown tool".into())),
    }
    let current = &mut server.current;
    current.check_source().map_err(tool_failure)?;
    let value = match name {
        "cpu_session_summary" => current.session_summary().map_err(tool_failure)?,
        "cpu_top_functions" => current.top_functions(&args).map_err(tool_failure)?,
        "cpu_call_tree" => current.call_tree(&args).map_err(tool_failure)?,
        "cpu_timeline_summary" => {
            let mut result = timeline::call(&current.session, &args)?;
            result["policyVersion"] = json!("aps-cpu-mcp-timeline-v1");
            result["sourceDigest"] = json!(current.digest.to_hex());
            result
        }
        "cpu_flame_blocks" | "cpu_stack_blocks" => {
            let flame = name == "cpu_flame_blocks";
            let mut result = blocks::call(&mut current.session, &args, flame)?;
            result["policyVersion"] = json!(if flame {
                "aps-cpu-mcp-flame-blocks-v1"
            } else {
                "aps-cpu-mcp-stack-blocks-v1"
            });
            result["sourceDigest"] = json!(current.digest.to_hex());
            result
        }
        "cpu_export_report" => {
            let mut result = report::call(current, &args)?;
            result["policyVersion"] = json!("aps-cpu-mcp-export-report-v1");
            result["sourceDigest"] = json!(current.digest.to_hex());
            result
        }
        "cpu_compare_top_functions" => {
            let baseline = server.baseline.as_mut().ok_or_else(|| {
                tool_failure("CPU comparison requires a startup-bound --baseline-cpu".into())
            })?;
            baseline.check_source().map_err(tool_failure)?;
            let mut result = compare::call(&mut baseline.session, &mut current.session, &args)?;
            result["policyVersion"] = json!("aps-cpu-mcp-compare-top-functions-v1");
            result["beforeSourceDigest"] = json!(baseline.digest.to_hex());
            result["afterSourceDigest"] = json!(current.digest.to_hex());
            baseline.check_source().map_err(tool_failure)?;
            result
        }
        _ => unreachable!("tool name validated above"),
    };
    current.check_source().map_err(tool_failure)?;
    let output = serde_json::to_string(&value).map_err(|error| (-32002, error.to_string()))?;
    if output.len() > MAX_OUTPUT_BYTES {
        return Err((-32002, "MCP tool output exceeds the bounded budget".into()));
    }
    Ok(json!({
        "content":[{"type":"text","text":output}],
        "structuredContent":value,
        "isError":false
    }))
}

fn tools_list() -> Value {
    json!({"tools":[
        {
            "name":"cpu_session_summary",
            "description":"Bounded metadata and evidence-quality summary for the startup-bound CPU profile.",
            "inputSchema":{"type":"object","properties":{},"additionalProperties":false},
            "annotations":read_only_annotations()
        },
        {
            "name":"cpu_top_functions",
            "description":"Bounded top-function projection sorted by inclusive sample weight. Resource paths are redacted to file labels.",
            "inputSchema":query_schema(100),
            "annotations":read_only_annotations()
        },
        {
            "name":"cpu_call_tree",
            "description":"Bounded paged call-tree rows for the startup-bound CPU profile. Resource paths are redacted to file labels.",
            "inputSchema":query_schema(MAX_CALL_TREE_LIMIT),
            "annotations":read_only_annotations()
        },
        {
            "name":"cpu_timeline_summary",
            "description":"Exact time-bucket sample counts and bounded thread summaries for the current profile. Not CPU time.",
            "inputSchema":timeline::schema(), "annotations":read_only_annotations()
        },
        {
            "name":"cpu_compare_top_functions",
            "description":"Compare hotspot weights, current minus baseline, using a startup-bound baseline. Raw differences are not a regression verdict.",
            "inputSchema":compare::schema(), "annotations":read_only_annotations()
        },
        {
            "name":"cpu_flame_blocks",
            "description":"Bounded flame graph blocks in normalized sample or event weight space. Aggregate bins do not identify a function.",
            "inputSchema":blocks::schema(true), "annotations":read_only_annotations()
        },
        {
            "name":"cpu_stack_blocks",
            "description":"One thread's sampled stack runs over a selected time range. Layout boundaries are not measured execution durations.",
            "inputSchema":blocks::schema(false), "annotations":read_only_annotations()
        },
        {
            "name":"cpu_export_report",
            "description":"Return a bounded UTF-8 JSON or CSV report inline for the current profile. Does not write files.",
            "inputSchema":report::schema(), "annotations":read_only_annotations()
        }
    ]})
}

fn read_only_annotations() -> Value {
    json!({"readOnlyHint":true,"destructiveHint":false,"idempotentHint":true,"openWorldHint":false})
}

fn query_schema(max_limit: usize) -> Value {
    json!({
        "type":"object",
        "properties":{
            "event":{"type":"string","minLength":1,"maxLength":2048},
            "startNs":{"type":"integer","minimum":0},
            "endNs":{"type":"integer","minimum":0},
            "direction":{"type":"string","enum":["forward","inverted"]},
            "weightType":{"type":"string","enum":["samples","events"]},
            "offset":{"type":"integer","minimum":0},
            "limit":{"type":"integer","minimum":1,"maximum":max_limit}
        },
        "additionalProperties":false
    })
}

fn query_from_args(args: &Value, max_limit: usize) -> Result<Query, String> {
    let object = args.as_object().ok_or("tool arguments must be an object")?;
    for key in object.keys() {
        if !matches!(
            key.as_str(),
            "event" | "startNs" | "endNs" | "direction" | "weightType" | "offset" | "limit"
        ) {
            return Err(format!("unsupported query argument {key}"));
        }
    }
    let direction = match read_text(object.get("direction"), "direction")?.unwrap_or("forward") {
        "forward" => Direction::Forward,
        "inverted" => Direction::Inverted,
        _ => return Err("direction must be forward or inverted".into()),
    };
    let weight_type = match read_text(object.get("weightType"), "weightType")?.unwrap_or("samples")
    {
        "samples" => WeightType::Samples,
        "events" => WeightType::EventCount,
        _ => return Err("weightType must be samples or events".into()),
    };
    let offset = read_usize(args, "offset", 0, usize::MAX)?;
    let limit = read_usize(
        args,
        "limit",
        if max_limit == MAX_CALL_TREE_LIMIT {
            200
        } else {
            50
        },
        max_limit,
    )?;
    if limit == 0 || offset.checked_add(limit).is_none() {
        return Err("limit must be positive and offset + limit must fit platform size".into());
    }
    let query = Query {
        thread_ids: Default::default(),
        event_types: read_text(object.get("event"), "event")?
            .map(|event| [event.to_owned()].into_iter().collect())
            .unwrap_or_default(),
        start: read_u64(object.get("startNs"), "startNs")?,
        end: read_u64(object.get("endNs"), "endNs")?,
        direction,
        weight_type,
        offset,
        limit: limit.min(aps_cpu::MAX_PAGE_SIZE),
    };
    query.validate()?;
    if weight_type == WeightType::EventCount && query.event_types.len() != 1 {
        return Err("weightType=events requires exactly one event".into());
    }
    Ok(query)
}

fn read_text<'a>(value: Option<&'a Value>, name: &str) -> Result<Option<&'a str>, String> {
    value
        .map(|value| {
            value
                .as_str()
                .filter(|value| {
                    !value.is_empty() && value.len() <= 2048 && !value.chars().any(char::is_control)
                })
                .ok_or_else(|| {
                    format!("{name} must be nonempty, control-free text within 2048 bytes")
                })
        })
        .transpose()
}

fn read_u64(value: Option<&Value>, name: &str) -> Result<Option<u64>, String> {
    match value {
        None => Ok(None),
        Some(value) => value
            .as_u64()
            .map(Some)
            .ok_or_else(|| format!("{name} must be an unsigned integer")),
    }
}

fn read_usize(args: &Value, name: &str, default: usize, max: usize) -> Result<usize, String> {
    let value = match args.get(name) {
        None => default,
        Some(value) => value
            .as_u64()
            .and_then(|value| usize::try_from(value).ok())
            .ok_or_else(|| format!("{name} must be a nonnegative platform-sized integer"))?,
    };
    if value > max {
        return Err(format!("{name} must be <= {max}"));
    }
    Ok(value)
}

fn query_json(query: &Query, offset: usize, limit: usize) -> Value {
    json!({
        "event": query.event_types.iter().next(),
        "startNs": query.start,
        "endNs": query.end,
        "direction": match query.direction { Direction::Forward => "forward", Direction::Inverted => "inverted" },
        "weightType": match query.weight_type { WeightType::Samples => "samples", WeightType::EventCount => "events" },
        "offset": offset,
        "limit": limit
    })
}

fn sanitize_resource(resource: &str) -> String {
    resource
        .rsplit(['/', '\\'])
        .find(|part| !part.is_empty())
        .unwrap_or("unknown")
        .to_owned()
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::OsString;

    #[test]
    fn cli_requires_one_current_and_accepts_only_one_optional_baseline() {
        assert_eq!(
            parse_args(&[OsString::from("--cpu"), OsString::from("profile.pb")])
                .unwrap()
                .cpu,
            PathBuf::from("profile.pb")
        );
        for values in [
            ["--cpu", "b", "--baseline-cpu", "a"],
            ["--baseline-cpu", "a", "--cpu", "b"],
        ] {
            let paired = parse_args(&values.map(OsString::from)).unwrap();
            assert_eq!(paired.cpu, PathBuf::from("b"));
            assert_eq!(paired.baseline_cpu, Some(PathBuf::from("a")));
        }
        for values in [
            vec!["--baseline-cpu", "a"],
            vec!["--cpu", "b", "--baseline-cpu"],
            vec!["--cpu", "b", "--cpu", "a"],
            vec!["--cpu", "b", "--baseline-cpu", "a", "--baseline-cpu", "c"],
            vec!["--cpu", "b", "--baseline-cpu", "--cpu"],
        ] {
            assert!(
                parse_args(&values.into_iter().map(OsString::from).collect::<Vec<_>>()).is_err()
            );
        }
        assert!(parse_args(&[]).is_err());
        assert!(parse_args(&[OsString::from("--cpu")]).is_err());
        assert!(parse_args(&[
            OsString::from("--cpu"),
            OsString::from("a"),
            OsString::from("b")
        ])
        .is_err());
    }

    #[test]
    fn query_rejects_unknown_fields_and_invalid_event_weight() {
        assert!(query_from_args(&json!({"unknown": true}), 100).is_err());
        assert!(query_from_args(&json!({"weightType":"events"}), 100).is_err());
        assert!(query_from_args(&json!({"event":"cpu-cycles","weightType":"events"}), 100).is_ok());
        assert!(query_from_args(&json!({"limit":101}), 100).is_err());
    }

    #[test]
    fn tool_list_has_only_bounded_read_only_cpu_tools() {
        let tools = tools_list();
        let names = tools["tools"]
            .as_array()
            .unwrap()
            .iter()
            .map(|tool| tool["name"].as_str().unwrap())
            .collect::<Vec<_>>();
        assert_eq!(
            names,
            vec![
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
        assert!(tools.to_string().contains("additionalProperties"));
        assert!(!tools.to_string().contains("shell"));
        assert!(!tools.to_string().contains("adb"));
    }
}

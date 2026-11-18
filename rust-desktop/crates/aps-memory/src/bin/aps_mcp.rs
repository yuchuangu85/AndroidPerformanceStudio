//! Read-only MCP server for one explicitly selected HPROF snapshot.
use aps_hprof_engine::cache::sha256_file;
use aps_hprof_engine::wire::Value as WireValue;
use aps_memory::{FindingKind, HistogramQuery, HistogramSort, MemorySession};
use aps_platform::CancellationToken;
use serde_json::{Map, Value, json};
use std::io::{self, BufRead, Write};
use std::path::PathBuf;

const PROTOCOL_VERSION: &str = "2024-11-05";
const MAX_OUTPUT_BYTES: usize = 512 * 1024;

fn main() {
    if let Err(error) = run() {
        let _ = writeln!(io::stderr(), "aps-mcp: {error}");
        std::process::exit(1);
    }
}

fn run() -> Result<(), String> {
    let (hprof, worker) = args()?;
    let digest = sha256_file(&hprof)?;
    let cancel = CancellationToken::new();
    let mut session = MemorySession::open(&worker, &hprof, &digest, &cancel)
        .map_err(|error| error.to_string())?;
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
        let response = dispatch(&mut session, &digest, &cancel, &request);
        serde_json::to_writer(&mut stdout, &response).map_err(|error| error.to_string())?;
        stdout.write_all(b"\n").map_err(|error| error.to_string())?;
        stdout.flush().map_err(|error| error.to_string())?;
    }
    Ok(())
}

fn args() -> Result<(PathBuf, PathBuf), String> {
    let args: Vec<_> = std::env::args_os().skip(1).collect();
    if args.len() != 4 || args[0] != "--hprof" || args[2] != "--worker" {
        return Err("usage: aps-mcp --hprof <file> --worker <aps-memory-worker>".into());
    }
    let hprof = PathBuf::from(&args[1]);
    let worker = PathBuf::from(&args[3]);
    if !hprof.is_file() || !worker.is_file() {
        return Err("MCP inputs must be regular files".into());
    }
    Ok((hprof, worker))
}

fn dispatch(
    session: &mut MemorySession,
    digest: &str,
    cancel: &CancellationToken,
    request: &Value,
) -> Value {
    let id = request.get("id").cloned().unwrap_or(Value::Null);
    let method = request.get("method").and_then(Value::as_str).unwrap_or("");
    let result = match method {
        "initialize" => Ok(json!({
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {"tools": {"listChanged": false}},
            "serverInfo": {"name": "aps-mcp", "version": env!("CARGO_PKG_VERSION")}
        })),
        "tools/list" => Ok(tools()),
        "tools/call" => call_tool(session, digest, cancel, request),
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
        {"name":"memory_object_detail","description":"Bounded fields, inbound references, and GC-root evidence for one object.","inputSchema":{"type":"object","required":["objectId"],"properties":{"objectId":{"type":"string"},"offset":{"type":"integer","minimum":0,"maximum":1000},"limit":{"type":"integer","minimum":1,"maximum":100}},"additionalProperties":false}}
    ]})
}

fn call_tool(
    session: &mut MemorySession,
    digest: &str,
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
    let payload = match name {
        "memory_snapshot_summary" => snapshot_summary(session, digest, cancel),
        "memory_leak_findings" => leak_findings(session, digest, cancel),
        "memory_class_histogram" => class_histogram(session, &args, cancel),
        "memory_object_detail" => object_detail(session, &args, cancel),
        _ => Err("unknown memory tool".into()),
    }
    .map_err(|error| (-32001, error))?;
    let text = serde_json::to_string(&payload).map_err(|error| (-32001, error.to_string()))?;
    if text.len() > MAX_OUTPUT_BYTES {
        return Err((-32002, "MCP tool output exceeds the bounded budget".into()));
    }
    Ok(json!({"content":[{"type":"text","text":text}],"structuredContent":payload,"isError":false}))
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
    session
        .histogram_query(
            args.get("heapName").and_then(Value::as_str),
            &query,
            0,
            limit,
            cancel,
        )
        .map(|value| wire_json(&value))
        .map_err(|error| error.to_string())
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
                "memory_object_detail"
            ]
        );
        assert!(tools.to_string().contains("additionalProperties"));
        assert!(!tools.to_string().contains("shell"));
        assert!(!tools.to_string().contains("network"));
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

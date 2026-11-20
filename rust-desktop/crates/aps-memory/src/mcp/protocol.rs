//! JSON-RPC method dispatch, tool discovery, and MCP wire responses.
use super::comparison::comparison_args;
use super::projection::{class_histogram, leak_findings, object_detail, snapshot_summary};
use super::server::McpServer;
use aps_platform::CancellationToken;
use serde_json::{Value, json};

const PROTOCOL_VERSION: &str = "2024-11-05";
const MAX_OUTPUT_BYTES: usize = 512 * 1024;

pub(super) fn dispatch(
    server: &mut McpServer,
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
}

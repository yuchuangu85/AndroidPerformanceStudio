//! JSON-RPC dispatch; wire-level methods do not select files or perform work.
use super::{RpcError, source::Selected, tools};
use serde_json::{Value, json};

const PROTOCOL: &str = "2024-11-05";

pub(super) fn error_response(id: &Value, (code, message): RpcError) -> Value {
    json!({"jsonrpc":"2.0","id":id,"error":{"code":code,"message":message}})
}

pub(super) fn dispatch(selected: Option<&mut Selected>, request: &Value) -> Option<Value> {
    let id = request.get("id");
    let method = request["method"].as_str();
    if request["jsonrpc"] != "2.0"
        || method.is_none_or(str::is_empty)
        || id.is_some_and(|id| !(id.is_string() || id.is_i64() || id.is_u64()))
    {
        return Some(error_response(
            &Value::Null,
            (-32600, "invalid JSON-RPC request"),
        ));
    }
    let id = id?; // Notifications do not trigger tools or emit a response.
    let result = match method? {
        "initialize" => Ok(
            json!({"protocolVersion":PROTOCOL,"capabilities":{"tools":{"listChanged":false}},"serverInfo":{"name":"aps-orchestrator-mcp","version":env!("CARGO_PKG_VERSION")}}),
        ),
        "ping" => Ok(json!({})),
        "tools/list" => Ok(tools::list(selected.as_deref())),
        "tools/call" => tools::call(selected, &request["params"]),
        _ => Err((-32601, "method not found")),
    };
    Some(match result {
        Ok(value) => json!({"jsonrpc":"2.0","id":id,"result":value}),
        Err(error) => error_response(id, error),
    })
}

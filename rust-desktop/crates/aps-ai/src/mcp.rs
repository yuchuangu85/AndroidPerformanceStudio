//! Bounded MCP host hydration for app-owned, read-only profiler servers.

use crate::{AnalysisRequest, Evidence};
use aps_platform::{CancellationToken, ProcessRequest, ProcessRunner};
use serde_json::Value;
use std::ffi::OsString;
use std::time::Duration;

const MAX_STDOUT: usize = 768 * 1024;
const MAX_EVIDENCE_BYTES: usize = 512 * 1024;

pub(crate) fn hydrate(request: &mut AnalysisRequest) -> Result<(), String> {
    let Some(context) = request.mcp.take() else {
        return Ok(());
    };
    if request.profiler != "memory" || context.source_digest.len() != 64 {
        return Err("E_MCP_CONTEXT_INVALID".into());
    }
    let input = requests();
    let mut process = ProcessRequest::new(&context.command);
    process.arguments = context.arguments.into_iter().map(OsString::from).collect();
    process.env_clear = true;
    process.timeout = Duration::from_secs(45);
    process.stdout_limit = MAX_STDOUT;
    process.stderr_limit = 64 * 1024;
    process.stdin = Some(input.into_bytes());
    let output = ProcessRunner
        .run(&process, &CancellationToken::new())
        .map_err(|_| "E_MCP_EXECUTION".to_owned())?;
    let stdout = String::from_utf8(output.stdout).map_err(|_| "E_MCP_PROTOCOL".to_owned())?;
    let mut added = 0usize;
    for line in stdout.lines() {
        let value: Value = serde_json::from_str(line).map_err(|_| "E_MCP_PROTOCOL".to_owned())?;
        let Some(result) = value.get("result") else {
            continue;
        };
        let Some(content) = result.get("structuredContent") else {
            continue;
        };
        let Some(name) = value
            .get("id")
            .and_then(Value::as_str)
            .or_else(|| value.get("id").and_then(Value::as_i64).map(|_| "memory"))
        else {
            continue;
        };
        let payload =
            serde_json::from_value(content.clone()).map_err(|_| "E_MCP_PROTOCOL".to_owned())?;
        let bytes = aps_json::to_vec(
            &payload,
            crate::model::json_limits(MAX_EVIDENCE_BYTES as u64),
        )
        .map_err(|_| "E_MCP_LIMIT".to_owned())?;
        if bytes.len() > MAX_EVIDENCE_BYTES {
            return Err("E_MCP_LIMIT".into());
        }
        request.evidence.push(Evidence {
            id: format!("mcp-memory-{name}-{}", &context.source_digest[..16]),
            kind: format!("mcp-memory-{name}"),
            summary: "Read-only MCP memory evidence; source-bound and bounded".into(),
            payload,
        });
        added += 1;
    }
    if added == 0 {
        return Err("E_MCP_NO_EVIDENCE".into());
    }
    request
        .scope
        .push_str("; MCP memory evidence hydrated from the selected HPROF");
    Ok(())
}

fn requests() -> String {
    [
        r#"{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"aps-ai","version":"0.1.0"}}}"#,
        r#"{"jsonrpc":"2.0","method":"notifications/initialized"}"#,
        r#"{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}"#,
        r#"{"jsonrpc":"2.0","id":"memory_snapshot_summary","method":"tools/call","params":{"name":"memory_snapshot_summary","arguments":{}}}"#,
        r#"{"jsonrpc":"2.0","id":"memory_leak_findings","method":"tools/call","params":{"name":"memory_leak_findings","arguments":{}}}"#,
        r#"{"jsonrpc":"2.0","id":"memory_class_histogram","method":"tools/call","params":{"name":"memory_class_histogram","arguments":{"limit":50}}}"#,
    ].join("\n") + "\n"
}

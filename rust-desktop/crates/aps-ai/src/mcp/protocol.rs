//! Pure JSON-RPC exchange for hydrating the fixed Memory evidence batch.
use super::MAX_STDOUT;
use crate::Evidence;
use aps_contracts::SourceDigest;
use serde_json::Value;
use std::collections::BTreeSet;

pub(super) const MAX_EVIDENCE_BYTES: usize = 32 * 1024;
pub(super) const TOOLS: [&str; 3] = [
    "memory_snapshot_summary",
    "memory_leak_findings",
    "memory_class_histogram",
];

pub(super) fn parse_evidence(stdout: &str, digest: SourceDigest) -> Result<Vec<Evidence>, String> {
    if stdout.len() > MAX_STDOUT {
        return Err("E_MCP_LIMIT".into());
    }
    let canonical_digest = digest.to_hex();
    let mut evidence = Vec::with_capacity(TOOLS.len());
    let mut received = BTreeSet::new();
    for line in stdout.lines() {
        let value = aps_json::parse_slice(
            line.as_bytes(),
            crate::model::json_limits(MAX_STDOUT as u64),
        )
        .map_err(|_| "E_MCP_PROTOCOL".to_owned())?;
        if value.get("jsonrpc").and_then(Value::as_str) != Some("2.0")
            || value.get("error").is_some()
        {
            return Err("E_MCP_PROTOCOL".into());
        }
        let result = value.get("result").ok_or("E_MCP_PROTOCOL")?;
        if let Some(id @ (1 | 2)) = value.get("id").and_then(Value::as_u64) {
            if !result.is_object() || !received.insert(id.to_string()) {
                return Err("E_MCP_PROTOCOL".into());
            }
            continue;
        }
        let name = value
            .get("id")
            .and_then(Value::as_str)
            .filter(|name| TOOLS.contains(name))
            .ok_or("E_MCP_PROTOCOL")?;
        if !received.insert(name.to_owned()) {
            return Err("E_MCP_PROTOCOL".into());
        }
        if result.get("isError").and_then(Value::as_bool) != Some(false) {
            return Err("E_MCP_TOOL_FAILED".into());
        }
        let content = result.get("structuredContent").ok_or("E_MCP_PROTOCOL")?;
        let source = content
            .get("sourceDigest")
            .and_then(Value::as_str)
            .and_then(|source| SourceDigest::from_hex(source).ok())
            .ok_or("E_MCP_SOURCE_INVALID")?;
        if source != digest {
            return Err("E_MCP_SOURCE_MISMATCH".into());
        }
        let payload = content.clone();
        aps_json::to_vec(
            &payload,
            crate::model::json_limits(MAX_EVIDENCE_BYTES as u64),
        )
        .map_err(|_| "E_MCP_LIMIT".to_owned())?;
        evidence.push(Evidence {
            id: format!("mcp-memory-{name}-{}", &canonical_digest[..16]),
            kind: format!("mcp-memory-{name}"),
            summary: "Read-only MCP memory evidence; source-bound and bounded".into(),
            payload,
        });
    }
    if evidence.is_empty() {
        return Err("E_MCP_NO_EVIDENCE".into());
    }
    if evidence.len() != TOOLS.len() || !received.contains("1") || !received.contains("2") {
        return Err("E_MCP_INCOMPLETE".into());
    }
    Ok(evidence)
}

pub(super) fn requests() -> String {
    [
        r#"{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"aps-ai","version":"0.1.0"}}}"#,
        r#"{"jsonrpc":"2.0","method":"notifications/initialized"}"#,
        r#"{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}"#,
        r#"{"jsonrpc":"2.0","id":"memory_snapshot_summary","method":"tools/call","params":{"name":"memory_snapshot_summary","arguments":{}}}"#,
        r#"{"jsonrpc":"2.0","id":"memory_leak_findings","method":"tools/call","params":{"name":"memory_leak_findings","arguments":{}}}"#,
        r#"{"jsonrpc":"2.0","id":"memory_class_histogram","method":"tools/call","params":{"name":"memory_class_histogram","arguments":{"limit":50}}}"#,
    ].join("\n") + "\n"
}

//! Bounded MCP host hydration for app-owned, read-only profiler servers.
mod protocol;
#[cfg(test)]
mod tests;

use crate::AnalysisRequest;
use aps_contracts::SourceDigest;
use aps_platform::{CancellationToken, ProcessRequest, ProcessRunner};
use std::ffi::OsString;
use std::time::Duration;

const MAX_STDOUT: usize = 768 * 1024;

pub(crate) fn hydrate(request: &mut AnalysisRequest) -> Result<(), String> {
    let Some(context) = request.mcp.as_ref() else {
        return Ok(());
    };
    let digest = SourceDigest::from_hex(&context.source_digest)
        .map_err(|_| "E_MCP_CONTEXT_INVALID".to_owned())?;
    if request.profiler != "memory" {
        return Err("E_MCP_CONTEXT_INVALID".into());
    }
    for evidence in &request.evidence {
        if evidence.kind == "memory-hprof-leak-findings"
            || evidence.payload.get("sourceDigest").is_some()
        {
            let source = evidence.payload["sourceDigest"]
                .as_str()
                .and_then(|source| SourceDigest::from_hex(source).ok())
                .ok_or("E_MCP_CONTEXT_INVALID")?;
            if source != digest {
                return Err("E_MCP_SOURCE_MISMATCH".into());
            }
        }
    }
    let input = protocol::requests();
    let mut process = ProcessRequest::new(&context.command);
    process.arguments = context.arguments.iter().map(OsString::from).collect();
    process.env_clear = true;
    process.timeout = Duration::from_secs(45);
    process.stdout_limit = MAX_STDOUT;
    process.stderr_limit = 64 * 1024;
    process.stdin = Some(input.into_bytes());
    let output = ProcessRunner
        .run(&process, &CancellationToken::new())
        .map_err(|_| "E_MCP_EXECUTION".to_owned())?;
    if !output.status.success() {
        return Err("E_MCP_EXECUTION".into());
    }
    let stdout = String::from_utf8(output.stdout).map_err(|_| "E_MCP_PROTOCOL".to_owned())?;
    let evidence = protocol::parse_evidence(&stdout, digest)?;
    if request.evidence.len() + evidence.len() > 100
        || evidence.iter().any(|added| {
            request
                .evidence
                .iter()
                .any(|existing| existing.id == added.id)
        })
    {
        return Err("E_MCP_CONTEXT_INVALID".into());
    }
    // Publish only after the whole batch and its source identity have passed.
    request.evidence.extend(evidence);
    request.mcp = None;
    request
        .scope
        .push_str("; MCP memory evidence hydrated from the selected HPROF");
    Ok(())
}

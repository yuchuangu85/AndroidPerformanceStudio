//! Bounded MCP host hydration for app-owned, read-only profiler servers.

use crate::{AnalysisRequest, Evidence};
use aps_contracts::SourceDigest;
use aps_platform::{CancellationToken, ProcessRequest, ProcessRunner};
use serde_json::Value;
use std::collections::BTreeSet;
use std::ffi::OsString;
use std::time::Duration;

const MAX_STDOUT: usize = 768 * 1024;
const MAX_EVIDENCE_BYTES: usize = 32 * 1024;
const TOOLS: [&str; 3] = [
    "memory_snapshot_summary",
    "memory_leak_findings",
    "memory_class_histogram",
];

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
    let input = requests();
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
    let evidence = parse_evidence(&stdout, digest)?;
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

fn parse_evidence(stdout: &str, digest: SourceDigest) -> Result<Vec<Evidence>, String> {
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::McpContext;
    use serde_json::json;

    fn digest() -> SourceDigest {
        SourceDigest::from_hex(&"a".repeat(64)).unwrap()
    }

    fn replies() -> Vec<Value> {
        let mut replies = vec![
            json!({"jsonrpc":"2.0","id":1,"result":{"protocolVersion":"2024-11-05"}}),
            json!({"jsonrpc":"2.0","id":2,"result":{"tools":[]}}),
        ];
        replies.extend(TOOLS.map(|name| {
            json!({"jsonrpc":"2.0","id":name,"result":{
                "isError":false,
                "structuredContent":{"sourceDigest":"a".repeat(64),"tool":name},
                "content":[{"type":"text","text":"untrusted text is not performance evidence"}]
            }})
        }));
        replies
    }

    fn stdout(replies: &[Value]) -> String {
        replies
            .iter()
            .map(Value::to_string)
            .collect::<Vec<_>>()
            .join("\n")
            + "\n"
    }

    fn request() -> AnalysisRequest {
        AnalysisRequest {
            session_id: "session".into(),
            run_id: "run".into(),
            generation: 1,
            profiler: "memory".into(),
            scope: "selected snapshot".into(),
            evidence: vec![Evidence {
                id: "memory-header".into(),
                kind: "memory-hprof-leak-findings".into(),
                summary: "source identity".into(),
                payload: json!({"sourceDigest":"a".repeat(64)}),
            }],
            candidates: Vec::new(),
            user_question: None,
            mcp: Some(McpContext {
                command: "must-not-run".into(),
                arguments: Vec::new(),
                source_digest: "a".repeat(64),
            }),
        }
    }

    #[test]
    fn accepts_only_complete_source_bound_tools_and_canonicalizes_identity() {
        let mut replies = replies();
        for reply in &mut replies[2..] {
            reply["result"]["structuredContent"]["sourceDigest"] = "A".repeat(64).into();
        }
        let evidence = parse_evidence(&stdout(&replies), digest()).unwrap();
        assert_eq!(evidence.len(), TOOLS.len());
        for (name, evidence) in TOOLS.into_iter().zip(evidence) {
            assert_eq!(evidence.kind, format!("mcp-memory-{name}"));
            assert_eq!(evidence.id, format!("mcp-memory-{name}-{}", "a".repeat(16)));
            assert_eq!(evidence.payload["tool"], name);
            assert!(evidence.payload.get("content").is_none());
        }
    }

    #[test]
    fn rejects_missing_malformed_or_mismatched_sources_on_every_tool() {
        for index in 2..5 {
            for source in [
                Value::Null,
                json!("g".repeat(64)),
                json!("b".repeat(64)),
                json!(""),
            ] {
                let mut replies = replies();
                replies[index]["result"]["structuredContent"]["sourceDigest"] = source.clone();
                let error = parse_evidence(&stdout(&replies), digest()).err().unwrap();
                assert_eq!(
                    error,
                    if source == json!("b".repeat(64)) {
                        "E_MCP_SOURCE_MISMATCH"
                    } else {
                        "E_MCP_SOURCE_INVALID"
                    }
                );
            }
            let mut replies = replies();
            replies[index]["result"]["structuredContent"]
                .as_object_mut()
                .unwrap()
                .remove("sourceDigest");
            assert_eq!(
                parse_evidence(&stdout(&replies), digest()).err().unwrap(),
                "E_MCP_SOURCE_INVALID"
            );
        }
    }

    #[test]
    fn context_identity_is_validated_before_launching_any_process() {
        for invalid in [
            "g".repeat(64),
            "a".repeat(63),
            "\u{e9}".repeat(32),
            String::new(),
        ] {
            let mut request = request();
            request.mcp.as_mut().unwrap().source_digest = invalid;
            assert_eq!(hydrate(&mut request).unwrap_err(), "E_MCP_CONTEXT_INVALID");
            assert_eq!(request.evidence.len(), 1);
            assert_eq!(request.scope, "selected snapshot");
            assert!(request.mcp.is_some());
        }
        let mut request = request();
        request.mcp.as_mut().unwrap().source_digest = "b".repeat(64);
        assert_eq!(hydrate(&mut request).unwrap_err(), "E_MCP_SOURCE_MISMATCH");
        assert!(request.mcp.is_some());
    }

    #[test]
    fn rejects_errors_duplicates_unknown_ids_and_missing_results() {
        for index in 0..5 {
            let mut error = replies();
            error[index].as_object_mut().unwrap().remove("result");
            error[index]["error"] = json!({"code":-32001,"message":"private diagnostic"});
            assert_eq!(
                parse_evidence(&stdout(&error), digest()).err().unwrap(),
                "E_MCP_PROTOCOL"
            );

            let mut duplicate = replies();
            duplicate.push(duplicate[index].clone());
            assert_eq!(
                parse_evidence(&stdout(&duplicate), digest()).err().unwrap(),
                "E_MCP_PROTOCOL"
            );

            let mut missing = replies();
            missing.remove(index);
            assert_eq!(
                parse_evidence(&stdout(&missing), digest()).err().unwrap(),
                "E_MCP_INCOMPLETE"
            );
        }
        for id in [json!(3), json!(null), json!("unexpected")] {
            let mut replies = replies();
            replies[4]["id"] = id;
            assert_eq!(
                parse_evidence(&stdout(&replies), digest()).err().unwrap(),
                "E_MCP_PROTOCOL"
            );
        }
        for flag in [json!(true), json!(null), json!("false")] {
            let mut replies = replies();
            replies[3]["result"]["isError"] = flag;
            assert_eq!(
                parse_evidence(&stdout(&replies), digest()).err().unwrap(),
                "E_MCP_TOOL_FAILED"
            );
        }
        assert_eq!(
            parse_evidence("", digest()).err().unwrap(),
            "E_MCP_NO_EVIDENCE"
        );
        assert_eq!(
            parse_evidence("not json", digest()).err().unwrap(),
            "E_MCP_PROTOCOL"
        );
    }

    #[test]
    fn rejects_ambiguous_json_and_oversized_evidence_without_truncating() {
        let duplicate_digest = format!(
            "{{\"jsonrpc\":\"2.0\",\"id\":\"memory_snapshot_summary\",\"result\":{{\"isError\":false,\"structuredContent\":{{\"sourceDigest\":\"{}\",\"sourceDigest\":\"{}\"}}}}}}",
            "b".repeat(64),
            "a".repeat(64)
        );
        assert_eq!(
            parse_evidence(&duplicate_digest, digest()).err().unwrap(),
            "E_MCP_PROTOCOL"
        );
        let mut replies = replies();
        replies[4]["result"]["structuredContent"]["large"] = "x".repeat(MAX_EVIDENCE_BYTES).into();
        assert_eq!(
            parse_evidence(&stdout(&replies), digest()).err().unwrap(),
            "E_MCP_LIMIT"
        );
    }

    #[cfg(unix)]
    fn server(
        request: &mut AnalysisRequest,
        output: &str,
        status: u8,
    ) -> aps_platform::TempWorkspace {
        let workspace = aps_platform::TempWorkspace::create(&std::env::temp_dir()).unwrap();
        let script = workspace.path().join("server.sh");
        let response = workspace.path().join("response.jsonl");
        std::fs::write(
            &script,
            "/bin/cat > /dev/null\n/bin/cat \"$1\"\nexit \"$2\"\n",
        )
        .unwrap();
        std::fs::write(&response, output).unwrap();
        let context = request.mcp.as_mut().unwrap();
        context.command = "/bin/sh".into();
        context.arguments = vec![
            script.to_str().unwrap().into(),
            response.to_str().unwrap().into(),
            status.to_string(),
        ];
        workspace
    }

    #[cfg(unix)]
    #[test]
    fn hydration_publishes_the_entire_batch_once_and_consumes_context_only_on_success() {
        let mut request = request();
        let server_workspace = server(&mut request, &stdout(&replies()), 0);
        hydrate(&mut request).unwrap();
        assert_eq!(request.evidence.len(), 4);
        assert!(request.mcp.is_none());
        let prompt = crate::chat::build(
            Some(&request),
            None,
            &[],
            false,
            crate::platform::ReportLanguage::English,
        )
        .unwrap();
        assert_eq!(prompt.evidence_count, 4);
        let payload = serde_json::to_string(&prompt.payload).unwrap();
        assert!(!payload.contains(server_workspace.path().to_str().unwrap()));
        assert!(!payload.contains("server.sh"));
        assert!(!payload.contains("untrusted text is not performance evidence"));
        let scope = request.scope.clone();
        hydrate(&mut request).unwrap();
        assert_eq!(request.evidence.len(), 4);
        assert_eq!(request.scope, scope);
    }

    #[cfg(unix)]
    #[test]
    fn partial_batch_failure_and_unsuccessful_process_never_publish_evidence() {
        for status in [0, 1] {
            let mut request = request();
            let mut replies = replies();
            if status == 0 {
                replies[4]["result"]["structuredContent"]["sourceDigest"] = "b".repeat(64).into();
            }
            let _server = server(&mut request, &stdout(&replies), status);
            let error = hydrate(&mut request).unwrap_err();
            assert_eq!(
                error,
                if status == 0 {
                    "E_MCP_SOURCE_MISMATCH"
                } else {
                    "E_MCP_EXECUTION"
                }
            );
            assert_eq!(request.evidence.len(), 1);
            assert_eq!(request.evidence[0].id, "memory-header");
            assert_eq!(request.scope, "selected snapshot");
            assert!(request.mcp.is_some());
        }
    }
}

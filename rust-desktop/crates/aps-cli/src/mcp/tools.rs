//! Read-only tool discovery, argument validation and evidence presentation.
use super::{RpcError, follow_up, source::Selected};
use serde_json::{Map, Value, json};

// Above this bound common MCP clients cannot faithfully serialize a JSON number.
const JSON_SAFE_ID: u64 = (1 << 53) - 1;

fn question_schema() -> Value {
    json!({"type":"string","minLength":1,"maxLength":2048,"description":"Optional natural-language question; untrusted context, never authorization or an executable instruction."})
}

pub(super) fn list(selected: Option<&Selected>) -> Value {
    let mut entries = vec![
        json!({"name":"aps_capabilities","description":"Discover what this read-only session can do before making a plan.","inputSchema":{"type":"object","properties":{},"additionalProperties":false}}),
        json!({"name":"aps_run_plan","description":"Preview the steps for local analysis or host-owned CPU or Macrobenchmark capture. This is advice only; it never downloads, executes, invokes AI or grants permission.","inputSchema":{"type":"object","properties":{"intent":{"type":"string","enum":["analyze","macrobenchmark","cpu-capture"]},"question":question_schema()},"required":["intent"],"additionalProperties":false}}),
    ];
    if let Some(selected) = selected {
        let mut schema = json!({"type":"object","properties":{"question":question_schema()},"additionalProperties":false});
        if selected.supports_frame_scope() {
            schema["properties"]["scope"] = json!({"type":"string","enum":["report"],"description":"Bounded Frame report, up to eight rows. Omit both selectors for the legacy aggregate."});
            schema["properties"]["frameId"] = json!({"oneOf":[{"type":"string","pattern":"^(0|[1-9][0-9]*)$","maxLength":20},{"type":"integer","minimum":0,"maximum":JSON_SAFE_ID}],
                "description":"One frame ID in this bound source. Prefer a canonical decimal string (required above 2^53-1); mutually exclusive with scope."});
            schema["not"] = json!({"required":["scope","frameId"]});
        }
        entries.push(json!({"name":"aps_analyze_file","description":"Read the frozen APS evidence for the one host-selected input. Interpret the numeric facts, units and missing signals in your answer to the user's question.","inputSchema":schema}));
        entries.push(follow_up::definition(selected));
    }
    json!({"tools":entries})
}

fn validate_args(args: &Map<String, Value>, allowed: &[&str]) -> Result<(), RpcError> {
    if args.keys().any(|field| !allowed.contains(&field.as_str())) {
        return Err((
            -32602,
            "unknown tool argument; paths, commands and devices cannot be selected",
        ));
    }
    if let Some(question) = args.get("question")
        && !question
            .as_str()
            .is_some_and(|q| !q.trim().is_empty() && q.chars().count() <= 2048)
    {
        return Err((
            -32602,
            "question must be nonempty text of at most 2048 characters",
        ));
    }
    Ok(())
}

pub(super) fn call(selected: Option<&mut Selected>, params: &Value) -> Result<Value, RpcError> {
    let name = params
        .get("name")
        .and_then(Value::as_str)
        .ok_or((-32602, "missing tool name"))?;
    let empty = Map::new();
    let args = match params.get("arguments") {
        None => &empty,
        Some(value) => value
            .as_object()
            .ok_or((-32602, "arguments must be an object"))?,
    };
    let projection = match name {
        "aps_capabilities" => {
            validate_args(args, &[])?;
            let analyzers = crate::capabilities()["analyze"].clone();
            json!({"supportedAnalyzers":analyzers,"sourceBound":selected.is_some(),"boundAnalyzer":selected.as_deref().and_then(Selected::analyzer_kind), "followUpRemaining":selected.as_deref().map(Selected::remaining_queries), "readOnly":true,"permissions":{"device":false,"download":false,"writeFiles":false,"invokeModel":false},"report":"The calling client interprets measured evidence. CLI actions require separate host authorization."})
        }
        follow_up::NAME => {
            let input = selected.ok_or((-32001, "no bound input for follow-up"))?;
            let (digest, query) = follow_up::Query::parse(args, input)?;
            input.follow_up(digest, query)?
        }
        "aps_analyze_file" => {
            let frame = selected
                .as_deref()
                .is_some_and(Selected::supports_frame_scope);
            validate_args(
                args,
                if frame {
                    &["question", "scope", "frameId"]
                } else {
                    &["question"]
                },
            )?;
            let scope = args
                .get("scope")
                .map(|v| v.as_str().ok_or((-32602, "scope must be report")))
                .transpose()?;
            let frame_id = args
                .get("frameId")
                .map(|v| {
                    if let Some(text) = v.as_str() {
                        aps_analysis::frame::parse_frame_id(text).map_err(|e| (-32602, e))
                    } else {
                        v.as_u64().filter(|&id| id <= JSON_SAFE_ID).ok_or((
                            -32602,
                            "frameId must be a safe unsigned integer or a canonical u64 string",
                        ))
                    }
                })
                .transpose()?;
            let scope = crate::frame_scope(scope, frame_id).map_err(|msg| (-32602, msg))?;
            selected
                .ok_or((-32001, "no bound input: restart with --input LOCAL_FILE"))?
                .evidence(scope)?
        }
        "aps_run_plan" => {
            validate_args(args, &["intent", "question"])?;
            let steps = match args.get("intent").and_then(Value::as_str) {
                Some("cpu-capture") => vec![
                    "Choose a device serial and target process in a supported CLI host.",
                    "Use aps capture cpu to inspect the capture plan. Device recording requires host confirmation; the MCP client cannot grant it.",
                    "Verify the recorded artifact and convert raw perf.data to a supported profile before analysis.",
                ],
                Some("macrobenchmark") => vec![
                    "Choose an existing trusted Macrobenchmark project and a device in the CLI.",
                    "Preview aps run macro-plan with the same project and options.",
                    "After host review run aps run macro-run; MCP does not execute the project.",
                    "Inspect the result from this run before sharing evidence.",
                ],
                Some("analyze") if selected.is_some() => vec![
                    "Call aps_analyze_file on the host-bound input.",
                    "If needed, use aps_follow_up with the same sourceDigest and an advertised query; stop when the evidence is sufficient or the budget ends.",
                    "Distinguish measured observations from hypotheses; cite the supplied evidence and retain unknown values.",
                ],
                Some("analyze") => vec![
                    "Have the host restart with --input LOCAL_FILE for a supported artifact.",
                    "Call aps_analyze_file, then interpret its frozen evidence.",
                ],
                _ => {
                    return Err((
                        -32602,
                        "intent must be analyze, cpu-capture or macrobenchmark",
                    ));
                }
            };
            json!({"status":"preview","executed":false,"steps":steps})
        }
        _ => return Err((-32602, "unknown tool")),
    };
    render(name, projection)
}

fn render(name: &str, projection: Value) -> Result<Value, RpcError> {
    // Keep the wire bound separate from the engine bound; the text fallback
    // duplicates structuredContent and JSON escaping can enlarge it again.
    let text = serde_json::to_string(&projection).map_err(|_| (-32603, "cannot encode result"))?;
    if name == follow_up::NAME && text.len() > follow_up::MAX_RESULT_BYTES {
        return Err((-32002, "follow-up result exceeds 32 KiB"));
    }
    if text.len() > 512 * 1024 {
        return Err((-32002, "evidence exceeds result budget"));
    }
    Ok(
        json!({"isError":false,"content":[{"type":"text","text":text}],"structuredContent":projection}),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn follow_up_enforces_its_own_serialized_byte_budget_before_text_fallback() {
        // Quotes inside the string must be escaped on the wire; counting
        // characters (or unescaped bytes) would wrongly publish this result.
        let page = json!({"items":[{"value":"\"".repeat(follow_up::MAX_RESULT_BYTES / 2)}]});
        assert_eq!(render(follow_up::NAME, page.clone()).unwrap_err().0, -32002);
        assert!(render("aps_analyze_file", page).is_ok());
    }
}

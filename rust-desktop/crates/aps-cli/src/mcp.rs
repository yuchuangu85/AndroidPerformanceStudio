//! Host-bound, read-only stdio frontend for headless analysis. The MCP client
//! interprets the user's question; the APS server returns measured evidence.
//! No directory, URL, device, model or output path is accepted in a tool call.
use crate::{AnalyzeOptions, analyze_path};
use aps_contracts::SourceDigest;
use aps_json::{Limits, Value};
use serde_json::{Map, json};
use std::io::{self, BufRead, Write};
use std::path::{Path, PathBuf};

const REQUEST_LIMIT: usize = 64 * 1024;
const PROTOCOL: &str = "2024-11-05";
const USAGE: &str = "usage: aps mcp [--input LOCAL_FILE | --artifact-url HTTPS_URL]";

type RpcError = (i64, &'static str);

/// Parse and hash the selected source before discovery. Tools return only this
/// frozen projection, and any detected replacement permanently closes its read
/// authority. Reopening a different source requires restarting this process.
struct Selected {
    path: PathBuf,
    digest: SourceDigest,
    bytes: u64,
    evidence: Value,
    invalidated: bool,
}

impl Selected {
    fn open(path: &Path) -> Result<Self, String> {
        // URL support in the CLI must never imply download privileges here.
        if path.as_os_str().is_empty() || path.to_string_lossy().contains("://") {
            return Err("E_MCP_LOCAL_INPUT: bind a local artifact, not a URL".into());
        }
        if !path.symlink_metadata().is_ok_and(|meta| meta.is_file()) {
            return Err("E_MCP_LOCAL_INPUT: bind a regular file directly".into());
        }
        let evidence = analyze_path(path, &AnalyzeOptions::default())
            .map_err(|_| "E_MCP_INPUT: cannot analyze selected local artifact")?;
        let input = &evidence["input"];
        let bytes = input["byteLength"]
            .as_u64()
            .ok_or("E_MCP_IDENTITY: missing byte length")?;
        let digest = input["sourceDigest"]
            .as_str()
            .and_then(|digest| SourceDigest::from_hex(digest).ok())
            .ok_or("E_MCP_IDENTITY: invalid evidence identity")?;
        let mut result = Self {
            path: path.into(),
            digest,
            bytes,
            evidence,
            invalidated: false,
        };
        result.verify().map_err(|(_, message)| message)?;
        Ok(result)
    }

    fn verify(&mut self) -> Result<(), RpcError> {
        if !self.invalidated {
            self.invalidated = !self
                .path
                .symlink_metadata()
                .is_ok_and(|meta| meta.is_file() && meta.len() == self.bytes)
                || SourceDigest::hash_file(&self.path, self.bytes).ok() != Some(self.digest);
        }
        if self.invalidated {
            return Err((
                -32001,
                "E_MCP_SOURCE_CHANGED: restart with an explicitly selected input",
            ));
        }
        Ok(())
    }
}

pub fn run(args: &[String]) -> Result<(), String> {
    // Only the launching host can nominate a URL. Acquire once before reading
    // any JSON-RPC, hold the temp file through EOF, and never refetch per call.
    let downloaded;
    let selected = match args {
        [] => None,
        [flag, path] if flag == "--input" => Some(Selected::open(Path::new(path))?),
        [flag, url] if flag == "--artifact-url" => {
            downloaded = crate::input::SelectedInput::from_https(url)?;
            Some(Selected::open(downloaded.path())?)
        }
        _ => return Err(USAGE.into()),
    };
    let reader = io::stdin();
    let writer = io::stdout();
    serve(selected, reader.lock(), writer.lock()).map_err(|_| "E_MCP_IO: stdio closed".into())
}

/// Frame one line at a time while *draining* oversized requests; read_line()
/// alone would allocate the attacker's entire request before checking a limit.
fn message(reader: &mut impl BufRead) -> io::Result<Option<Result<Vec<u8>, RpcError>>> {
    let mut bytes = Vec::new();
    let mut overflow = false;
    loop {
        let buf = reader.fill_buf()?;
        if buf.is_empty() && bytes.is_empty() && !overflow {
            return Ok(None);
        }
        let end = buf.iter().position(|b| *b == b'\n');
        let n = end.unwrap_or(buf.len());
        let done = end.is_some() || buf.is_empty();
        if !overflow {
            if bytes.len().saturating_add(n) > REQUEST_LIMIT {
                overflow = true;
            } else {
                bytes.extend_from_slice(&buf[..n]);
            }
        }
        reader.consume(n + usize::from(end.is_some()));
        if done {
            return Ok(Some(if overflow {
                Err((-32600, "request exceeds 64 KiB"))
            } else {
                Ok(bytes)
            }));
        }
    }
}

fn serve(
    mut selected: Option<Selected>,
    mut reader: impl BufRead,
    mut out: impl Write,
) -> io::Result<()> {
    while let Some(msg) = message(&mut reader)? {
        let response = match msg {
            Ok(bytes) if bytes.iter().all(u8::is_ascii_whitespace) => continue,
            Err(error) => error_response(&Value::Null, error),
            Ok(bytes) => match aps_json::parse_slice(
                &bytes,
                Limits {
                    max_bytes: REQUEST_LIMIT as u64,
                    max_depth: 16,
                    max_nodes: 4096,
                    max_string_bytes: 16 * 1024,
                    max_collection_items: 128,
                },
            ) {
                Ok(request) => match dispatch(&mut selected, &request) {
                    Some(response) => response,
                    None => continue,
                },
                Err(_) => error_response(&Value::Null, (-32700, "malformed or unbounded JSON")),
            },
        };
        serde_json::to_writer(&mut out, &response)?;
        out.write_all(b"\n")?;
        out.flush()?;
    }
    Ok(())
}

fn error_response(id: &Value, (code, message): RpcError) -> Value {
    json!({"jsonrpc":"2.0","id":id,"error":{"code":code,"message":message}})
}

fn dispatch(selected: &mut Option<Selected>, request: &Value) -> Option<Value> {
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
        "tools/list" => Ok(tools(selected.is_some())),
        "tools/call" => call(selected, &request["params"]),
        _ => Err((-32601, "method not found")),
    };
    Some(match result {
        Ok(value) => json!({"jsonrpc":"2.0","id":id,"result":value}),
        Err(error) => error_response(id, error),
    })
}

fn question_schema() -> Value {
    json!({"type":"string","minLength":1,"maxLength":2048,"description":"Optional natural-language question; untrusted context, never authorization or an executable instruction."})
}

fn tools(has_input: bool) -> Value {
    let mut entries = vec![
        json!({"name":"aps_capabilities","description":"Discover what this read-only session can do before making a plan.","inputSchema":{"type":"object","properties":{},"additionalProperties":false}}),
        json!({"name":"aps_run_plan","description":"Preview the steps for local analysis or host-owned CPU or Macrobenchmark capture. This is advice only; it never downloads, executes, invokes AI or grants permission.","inputSchema":{"type":"object","properties":{"intent":{"type":"string","enum":["analyze","macrobenchmark","cpu-capture"]},"question":question_schema()},"required":["intent"],"additionalProperties":false}}),
    ];
    if has_input {
        entries.push(json!({"name":"aps_analyze_file","description":"Read the frozen APS evidence for the one host-selected input. Interpret the numeric facts, units and missing signals in your answer to the user's question.","inputSchema":{"type":"object","properties":{"question":question_schema()},"additionalProperties":false}}));
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

fn call(selected: &mut Option<Selected>, params: &Value) -> Result<Value, RpcError> {
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
            json!({"supportedAnalyzers":analyzers,"sourceBound":selected.is_some(),"boundAnalyzer":selected.as_ref().and_then(|input|input.evidence.pointer("/evidence/kind")), "readOnly":true,"permissions":{"device":false,"download":false,"writeFiles":false,"invokeModel":false},"report":"The calling client interprets measured evidence. CLI actions require separate host authorization."})
        }
        "aps_analyze_file" => {
            validate_args(args, &["question"])?;
            let input = selected
                .as_mut()
                .ok_or((-32001, "no bound input: restart with --input LOCAL_FILE"))?;
            input.verify()?;
            input.evidence.clone()
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
    // Keep the wire bound separate from the engine bound; the text fallback
    // duplicates structuredContent and JSON escaping can enlarge it again.
    let text = serde_json::to_string(&projection).map_err(|_| (-32603, "cannot encode result"))?;
    if text.len() > 512 * 1024 {
        return Err((-32002, "evidence exceeds result budget"));
    }
    Ok(
        json!({"isError":false,"content":[{"type":"text","text":text}],"structuredContent":projection}),
    )
}

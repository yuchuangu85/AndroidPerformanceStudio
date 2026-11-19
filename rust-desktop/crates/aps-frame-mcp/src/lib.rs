//! Read-only, source-bound MCP projection of an explicitly selected frame report.
mod capture;
mod capture_query;
mod input;
mod labels;
mod query;

use aps_contracts::SourceDigest;
use aps_frame::analysis::{FrameAnalysisResult, FrameJankAnalyzer};
use serde_json::{Value, json};
use std::path::{Path, PathBuf};

pub const PROTOCOL_VERSION: &str = "2024-11-05";
pub const MAX_REQUEST_BYTES: usize = 64 * 1024;
const MAX_PAYLOAD_BYTES: usize = 512 * 1024;
pub const ANALYSIS_POLICY_VERSION: &str = "aps-frame-v2";

type ToolResult = Result<Value, (i64, String)>;

struct BoundReport {
    path: PathBuf,
    digest: SourceDigest,
    input_format: &'static str,
    parse_warning_count: usize,
    analysis: FrameAnalysisResult,
    capture: Option<capture::Capture>,
}

impl BoundReport {
    fn open(path: &Path, capture_files: Option<(&Path, &Path)>) -> Result<Self, String> {
        let capture = capture_files
            .map(|(receipt, trace)| capture::Capture::open(receipt, trace))
            .transpose()?;
        let loaded = input::load_input(path, capture.as_ref().map(|c| &c.meta))?;
        let analysis = FrameJankAnalyzer::new().analyze(&loaded.samples);
        let report = Self {
            path: path.to_owned(),
            digest: loaded.digest,
            input_format: loaded.input_format,
            parse_warning_count: loaded.parse_warning_count,
            analysis,
            capture,
        };
        report.verify()?;
        Ok(report)
    }

    fn verify(&self) -> Result<(), String> {
        input::check_source(&self.path, self.digest)?;
        if let Some(capture) = &self.capture {
            capture.verify()?;
        }
        Ok(())
    }
}

pub struct FrameMcpServer {
    selected: BoundReport,
    baseline: Option<BoundReport>,
    cached: Option<(query::Filter, FrameAnalysisResult)>,
    invalidated: bool,
}

impl FrameMcpServer {
    pub fn open(path: &Path) -> Result<Self, String> {
        Self::open_bound(path, None, None)
    }

    /// Baseline is an independently selected (report, receipt, trace) triple.
    pub fn open_bound(
        path: &Path,
        files: Option<(&Path, &Path)>,
        baseline: Option<(&Path, &Path, &Path)>,
    ) -> Result<Self, String> {
        if baseline.is_some() && files.is_none() {
            return Err("Baseline requires a receipt-bound primary capture".into());
        }
        let selected = BoundReport::open(path, files)?;
        let baseline = baseline
            .map(|(path, receipt, trace)| BoundReport::open(path, Some((receipt, trace))))
            .transpose()?;
        Ok(Self {
            selected,
            baseline,
            cached: None,
            invalidated: false,
        })
    }

    /// Notifications have no response; request errors do not terminate the session.
    pub fn dispatch(&mut self, request: &Value) -> Option<Value> {
        let id = request.get("id");
        let method = request.get("method").and_then(Value::as_str);
        if request.get("jsonrpc").and_then(Value::as_str) != Some("2.0")
            || !id.is_none_or(|id| id.is_null() || id.is_string() || id.is_i64() || id.is_u64())
            || method.is_none_or(str::is_empty)
        {
            return Some(rpc_error(Value::Null, -32600, "Invalid JSON-RPC request"));
        }
        let id = id?.clone();
        let result = match method? {
            "initialize" => Ok(json!({
                "protocolVersion": PROTOCOL_VERSION,
                "capabilities": {"tools": {"listChanged": false}},
                "serverInfo": {"name": "aps-frame-mcp", "version": env!("CARGO_PKG_VERSION")}
            })),
            "ping" => Ok(json!({})),
            "tools/list" => Ok(query::tools(self.selected.capture.is_some())),
            "tools/call" => self.call_tool(request.get("params")),
            _ => Err((-32601, "Method not found".into())),
        };
        Some(match result {
            Ok(result) => json!({"jsonrpc":"2.0", "id":id, "result":result}),
            Err((code, message)) => rpc_error(id, code, &message),
        })
    }

    fn verify_source(&mut self, compare: bool) -> Result<(), (i64, String)> {
        if self.invalidated {
            return Err((
                -32001,
                "Frame source invalidated; restart with the selected input".into(),
            ));
        }
        if self.selected.verify().is_err()
            || (compare && self.baseline.as_ref().is_some_and(|b| b.verify().is_err()))
        {
            self.invalidated = true;
            self.cached = None;
            return Err((
                -32001,
                "Frame source changed or is unavailable; restart with the selected input".into(),
            ));
        }
        Ok(())
    }

    fn call_tool(&mut self, params: Option<&Value>) -> ToolResult {
        let params = params
            .and_then(Value::as_object)
            .ok_or_else(|| (-32602, "Expected tools/call parameters".into()))?;
        if params
            .keys()
            .any(|key| !matches!(key.as_str(), "name" | "arguments" | "_meta"))
        {
            return Err((-32602, "Unknown tools/call parameter".into()));
        }
        let name = params
            .get("name")
            .and_then(Value::as_str)
            .ok_or_else(|| (-32602, "Missing tool name".into()))?;
        if query::is_capture_tool(name) && self.selected.capture.is_none() {
            return Err((-32602, "Unknown tool in report-only mode".into()));
        }
        let empty = json!({});
        let arguments = query::Arguments::parse(name, params.get("arguments").unwrap_or(&empty))
            .map_err(|error| (-32602, error))?;
        let compare = name == "frame_session_compare";
        self.verify_source(compare)?;
        let mut payload = if query::is_capture_tool(name) {
            self.capture_payload(name, &arguments)
        } else {
            self.report_payload(name, &arguments)
        }
        .map_err(|message| (-32001, message))?;
        payload["inputWarningCount"] = json!(self.selected.parse_warning_count);
        let text = serde_json::to_string(&payload)
            .map_err(|_| (-32001, "Cannot serialize frame result".into()))?;
        if text.len() > MAX_PAYLOAD_BYTES {
            return Err((
                -32002,
                "Frame tool payload exceeds 512 KiB; reduce limit".into(),
            ));
        }
        self.verify_source(compare)?;
        Ok(
            json!({"content":[{"type":"text","text":text}], "structuredContent":payload,"isError":false}),
        )
    }

    fn capture_payload(&self, name: &str, args: &query::Arguments) -> Result<Value, String> {
        let current = &self.selected;
        let bound = current.capture.as_ref().ok_or("Missing selected capture")?;
        let session_id = format!("frame-{}", current.digest);
        let mut payload = match name {
            "frame_trace_overlaps" => {
                if args.session_id.as_deref() != Some(&session_id) {
                    return Err("Stale or unrelated frame sessionId".into());
                }
                capture_query::overlaps(
                    &current.analysis,
                    args.frame_id.ok_or("Missing frameId")?,
                    bound.meta.probe_query_complete,
                )?
            }
            "frame_session_compare" => {
                let baseline = self
                    .baseline
                    .as_ref()
                    .ok_or("Comparison requires a pinned baseline capture")?;
                let before = baseline
                    .capture
                    .as_ref()
                    .ok_or("Missing baseline receipt")?;
                let mut result = capture_query::compare(
                    (&bound.meta, &current.analysis),
                    (&before.meta, &baseline.analysis),
                    args.offset,
                    args.limit,
                )?;
                result["baseline"]["receiptDigest"] = json!(before.receipt_digest.to_hex());
                result["after"]["receiptDigest"] = json!(bound.receipt_digest.to_hex());
                result
            }
            _ => return Err("Unknown capture tool".into()),
        };
        payload["sessionId"] = json!(session_id);
        payload["sourceDigest"] = json!(current.digest.to_hex());
        payload["traceDigest"] = json!(bound.meta.trace_digest.to_hex());
        payload["receiptDigest"] = json!(bound.receipt_digest.to_hex());
        payload["captureId"] = json!(bound.meta.capture_id);
        payload["analysisPolicyVersion"] = json!(ANALYSIS_POLICY_VERSION);
        Ok(payload)
    }

    fn report_payload(
        &mut self,
        name: &str,
        arguments: &query::Arguments,
    ) -> Result<Value, String> {
        let base = &self.selected.analysis;
        if arguments.filter != query::Filter::default()
            && self
                .cached
                .as_ref()
                .is_none_or(|(filter, _)| filter != &arguments.filter)
        {
            self.cached = Some((
                arguments.filter.clone(),
                query::filtered(base, &arguments.filter),
            ));
        }
        let analysis = if arguments.filter == query::Filter::default() {
            base
        } else {
            &self.cached.as_ref().expect("filtered view cached").1
        };
        query::project(
            name,
            arguments,
            analysis,
            base.frames.len(),
            &self.selected.digest.to_hex(),
            self.selected.input_format,
        )
    }
}

pub fn rpc_error(id: Value, code: i64, message: &str) -> Value {
    json!({"jsonrpc":"2.0", "id":id, "error":{"code":code,"message":message}})
}

//! Shared, read-only capability discovery. Describing an action never executes it.
use crate::{EVIDENCE_SCHEMA, MAX_INPUT_BYTES, analysis::ADDITIONAL_KINDS};
use serde_json::{Value, json};

/// Discoverable extension slots. Reserving an ID is not permission to call it.
/// Keep these out of MCP's executable tool list until a bounded adapter exists.
pub const DEFERRED_CAPTURE: &[&str] = &[
    "frame",
    "trace",
    "startup",
    "memory",
    "battery",
    "network",
    "gpu",
    "layout-inspector",
    "method-recording",
];
pub const DEFERRED_INPUT: &[&str] = &["agi", "source-workspace", "session-package", "gzip"];

pub fn capabilities() -> Value {
    let mut kinds = vec!["cpu", "frame", "network", "trace"];
    kinds.extend_from_slice(ADDITIONAL_KINDS);
    json!({
        "schemaVersion":"aps-cli-capabilities-v1",
        "analyze":kinds,
        "evidenceSchema":EVIDENCE_SCHEMA,
        "frameScopes":["report","selected-frame"],
        "benchmarkRegression":{"inputs":"explicit-local-baseline-and-current","cli":"aps benchmark compare","mcpTool":"aps_benchmark_compare"},
        "startup":{"inputFormats":["startup-csv","startup-json-v1"],"scope":"startup-runs","mcpQuery":"startup_runs"},
        "maxInputBytes":MAX_INPUT_BYTES,
        "rawPerfData":if cfg!(unix) {"requires-host-selected-APS_SIMPLEPERF"} else {"unsupported-host"},
        "remoteFetch":"explicit-https-host-input-only",
        "ai":"explicit-terminal-review",
        "capture":if cfg!(unix) { vec!["macrobenchmark","cpu"] } else { vec!["macrobenchmark"] },
        "mcp":"read-only-source-bound",
        "deferred":{"capture":DEFERRED_CAPTURE,"input":DEFERRED_INPUT,
            "status":"unavailable","errorCode":"E_CAPABILITY_UNAVAILABLE"},
        "conditional":{"memory":"requires sibling aps-memory-worker",
            "trace":"requires pinned host trace_processor_shell",
            "cpuCapture":"Unix; requires explicit ADB, Simpleperf, and matching live target",
            "aiReport":"requires explicit Chat Completions provider and interactive review"},
        "unavailable":[
            {"kind":"compressed-cpu-profile","nextStep":"Unpack gzip or an APS CPU session in the desktop import flow first."},
            {"kind":"gpu/source","nextStep":"Use the corresponding APS desktop workspace."},
            {"kind":"custom-device-steps","nextStep":"Define a trusted Macrobenchmark test scenario; arbitrary shell is not supported."}
        ]
    })
}

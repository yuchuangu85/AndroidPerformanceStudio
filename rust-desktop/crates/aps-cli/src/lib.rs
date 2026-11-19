//! Headless, source-bound evidence shared by the local CLI and read-only MCP.
//! Acquiring an artifact, authorizing a provider and running a device scenario
//! are separate host operations. None occurs inside `analyze_path`.
pub mod ai;
pub mod cpu_capture;
mod extra;
pub mod input;
pub mod mcp;
mod offline_inspection;
mod raw_cpu;
pub mod scenario;
mod trace;

use aps_contracts::SourceDigest;
use aps_cpu::{CpuSession, Query, TopSort};
use aps_frame_mcp::FrameMcpServer;
use serde_json::{Value, json};
use std::fs::File;
use std::io::{Read, Write};
use std::path::{Path, PathBuf};
use std::process::Command;

pub const EVIDENCE_SCHEMA: &str = "aps-evidence-v1";
pub const MAX_INPUT_BYTES: u64 = 256 * 1024 * 1024;
const MAX_EVIDENCE_BYTES: usize = 512 * 1024;
const TOP_LIMIT: usize = 16;

#[derive(Default, Debug, Clone, Copy, PartialEq, Eq)]
pub enum OutputFormat {
    #[default]
    Json,
}

impl OutputFormat {
    pub fn parse(value: &str) -> Result<Self, String> {
        match value {
            "json" => Ok(Self::Json),
            _ => Err("E_FORMAT_UNSUPPORTED: use --format json".into()),
        }
    }
}

#[derive(Default, Debug, Clone)]
pub struct AnalyzeOptions {
    pub force_trace: bool,
    pub format: OutputFormat,
}

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
    kinds.extend_from_slice(extra::KINDS);
    json!({
        "schemaVersion":"aps-cli-capabilities-v1",
        "analyze":kinds,
        "evidenceSchema":EVIDENCE_SCHEMA,
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

/// Analyze an explicitly selected local artifact. The full filename and source
/// bytes are not part of the public payload. CLI downloads (if requested) must
/// finish *before* this boundary. The digest identifies the bytes analyzed.
pub fn analyze_path(path: &Path, options: &AnalyzeOptions) -> Result<Value, String> {
    if path.to_string_lossy().contains("://") {
        return Err("E_LOCAL_INPUT_REQUIRED: bind a local file to this analyzer".into());
    }
    let bytes = read_bounded(path)?;
    let digest = SourceDigest::hash_reader(bytes.as_slice(), MAX_INPUT_BYTES)?.to_hex();
    let evidence = index_sections(
        if options.force_trace || trace::looks_like_trace(path, &bytes) {
            if !trace::has_packet(&bytes) {
                return Err("E_TRACE_INPUT: expected a Perfetto protobuf Trace packet".into());
            }
            // Native Trace Processor reopens its input (including during fallback).
            // Hand it only our captured bytes, not a source path which could be
            // replaced and restored between two identity checks.
            let mut snapshot = tempfile::NamedTempFile::new().map_err(|_| "E_TRACE_SNAPSHOT")?;
            snapshot
                .write_all(&bytes)
                .and_then(|_| snapshot.flush())
                .map_err(|_| "E_TRACE_SNAPSHOT")?;
            trace::analyze(snapshot.path(), &digest)?
        } else {
            detect_and_analyze(path, &bytes, &digest)?
        },
    )?;
    let envelope = json!({
        "schemaVersion": EVIDENCE_SCHEMA,
        "input": {"sourceDigest":digest, "byteLength":bytes.len()},
        "evidence": evidence,
    });
    match options.format {
        OutputFormat::Json => {
            if serde_json::to_vec(&envelope)
                .map_err(|_| "E_EVIDENCE_ENCODING")?
                .len()
                > MAX_EVIDENCE_BYTES
            {
                return Err("E_EVIDENCE_LIMIT: analysis exceeded the output budget".into());
            }
        }
    }
    Ok(envelope)
}

// Provider findings and external clients can reference exactly the same sections.
// The source digest scopes these stable IDs to this input.
fn index_sections(projection: Value) -> Result<Value, String> {
    let Value::Object(mut sections) = projection else {
        return Err("E_EVIDENCE_SCHEMA".into());
    };
    let kind = sections.remove("kind").ok_or("E_EVIDENCE_SCHEMA")?;
    let format = sections.remove("inputFormat").ok_or("E_EVIDENCE_SCHEMA")?;
    let domain = kind.as_str().ok_or("E_EVIDENCE_SCHEMA")?;
    let items = sections
        .into_iter()
        .map(|(key, value)| json!({"id":format!("{domain}-{key}"), "label":key, "value":value}))
        .collect::<Vec<_>>();
    if items.is_empty() {
        return Err("E_EVIDENCE_EMPTY".into());
    }
    Ok(json!({"kind":kind, "inputFormat":format, "items":items}))
}

fn detect_and_analyze(path: &Path, bytes: &[u8], digest: &str) -> Result<Value, String> {
    if bytes.starts_with(b"PERFILE2") || bytes.starts_with(b"2ELIFREP") {
        return raw_cpu::analyze(bytes, digest);
    }
    if bytes.starts_with(&[0x1f, 0x8b]) {
        return Err(
            "E_INPUT_FORMAT: import gzip profiles in the desktop app or decompress explicitly"
                .into(),
        );
    }
    if bytes.starts_with(b"PK\x03\x04")
        && path
            .file_name()
            .and_then(|s| s.to_str())
            .is_some_and(|s| s.to_ascii_lowercase().ends_with(".apsession.zip"))
    {
        return Err("E_INPUT_FORMAT: open the CPU session package in the desktop app, then select its supported profile".into());
    }
    if bytes.starts_with(b"SIMPLEPERF") {
        return cpu_evidence(bytes, "simpleperf_protobuf");
    }
    if bytes.iter().copied().find(|b| !b.is_ascii_whitespace()) == Some(b'{') {
        // Only select a parser on an unambiguous top-level contract; extension
        // and producer strings cannot decide the data type. The domain parser
        // remains responsible for its full schema.
        let document = aps_json::parse_slice(
            bytes,
            aps_json::Limits {
                max_bytes: MAX_INPUT_BYTES,
                max_depth: 64,
                max_nodes: 4_000_000,
                max_string_bytes: 2 * 1024 * 1024,
                max_collection_items: 1_000_000,
            },
        )
        .map_err(|_| "E_INPUT_FORMAT: malformed, ambiguous or over-budget JSON")?;
        let har = document.get("log").is_some();
        let frame = document.get("frames").is_some() && document.get("schemaVersion").is_some();
        let gecko = document.get("threads").is_some() && document.get("meta").is_some();
        let benchmark = document.get("benchmarks").is_some();
        let battery = offline_inspection::is_battery(&document);
        if benchmark || battery {
            if har || frame || gecko || benchmark && battery {
                return Err("E_INPUT_FORMAT: ambiguous JSON artifact".into());
            }
            if let Some(result) = extra::analyze(path, bytes, digest, Some(&document)) {
                return result;
            }
        }
        drop(document);
        return match (har, frame, gecko) {
            (true, false, false) => har_evidence(bytes),
            (false, true, false) => frame_evidence(path, digest),
            (false, false, true) => cpu_evidence(bytes, "gecko_v24"),
            _ => Err(
                "E_INPUT_FORMAT: unsupported or ambiguous JSON artifact (see aps capabilities)"
                    .into(),
            ),
        };
    }
    if let Some(result) = extra::analyze(path, bytes, digest, None) {
        return result;
    }
    if bytes
        .windows(b"IntendedVsync".len())
        .any(|w| w == b"IntendedVsync")
        && bytes
            .windows(b"FrameCompleted".len())
            .any(|w| w == b"FrameCompleted")
    {
        return frame_evidence(path, digest);
    }
    Err("E_INPUT_FORMAT: unsupported local artifact (see aps capabilities)".into())
}

fn cpu_evidence(bytes: &[u8], input_format: &str) -> Result<Value, String> {
    let mut session = CpuSession::from_bytes(bytes)
        .map_err(|_| "E_CPU_INPUT: malformed or over-budget CPU profile")?;
    let top = session
        .top_functions_sorted(
            &Query::default(),
            TopSort::InclusiveWeight,
            true,
            0,
            TOP_LIMIT,
        )
        .map_err(|_| "E_CPU_QUERY: hotspot projection failed")?;
    let summary = session.summary();
    let rows: Vec<_> = top
        .rows
        .iter()
        .map(|row| {
            json!({
                "id":row.id,
                // Do not emit filesystem paths, URLs, unbounded labels or control text.
                "name":public_symbol(&row.name),
                "inclusiveWeight":row.inclusive_weight, "selfWeight":row.self_weight,
                "sampleCount":row.sample_count, "threadCount":row.thread_count,
            })
        })
        .collect();
    Ok(json!({
        "kind":"cpu","inputFormat":input_format,
        "summary":{
            "recordCount":summary.record_count,"sampleCount":summary.sample_count,
            "reportedSampleCount":summary.reported_sample_count,"lostSampleCount":summary.lost_sample_count,
            "lostRate":(summary.reported_sample_count > 0).then(|| summary.lost_rate()),
            "firstTimestampNs":summary.first_timestamp_nanos,"lastTimestampNs":summary.last_timestamp_nanos,
            "threadCount":summary.thread_count, "eventTypeCount":summary.event_types.len()
        },
        "topFunctions":{"weightUnit":"samples","sort":"inclusive-desc","rows":rows,
            "total":top.total, "omitted":top.total-top.rows.len()},
        "methodDurationAvailable":false
    }))
}

fn public_symbol(name: &str) -> Option<&str> {
    (!name.is_empty()
        && name.len() <= 200
        && !name.contains(['/', '\\'])
        && !name.chars().any(char::is_control))
    .then_some(name)
}

fn frame_evidence(path: &Path, digest: &str) -> Result<Value, String> {
    let mut server = FrameMcpServer::open(path)
        .map_err(|_| "E_FRAME_INPUT: malformed or over-budget frame report")?;
    let response = server
        .dispatch(&json!({
            "jsonrpc":"2.0","id":1,"method":"tools/call",
            "params":{"name":"frame_session_summary","arguments":{}}
        }))
        .ok_or("E_FRAME_QUERY: no response")?;
    let payload = response
        .pointer("/result/structuredContent")
        .ok_or("E_FRAME_QUERY: summary unavailable")?;
    if payload["sourceDigest"].as_str() != Some(digest) {
        return Err("E_SOURCE_CHANGED: frame report changed while preparing evidence".into());
    }
    Ok(
        json!({"kind":"frame","inputFormat":payload["inputFormat"],"summary":payload["summary"],
        "coverage":payload["coverage"],"provenanceWarnings":payload["provenanceWarnings"],
        "analysisPolicyVersion":payload["analysisPolicyVersion"]}),
    )
}

fn har_evidence(bytes: &[u8]) -> Result<Value, String> {
    let report = aps_har_engine::summarize(bytes)
        .map_err(|_| "E_HAR_INPUT: malformed or over-budget HAR")?;
    // Only numeric aggregate facts and parser-owned labels. In particular, do
    // not send URLs, request methods, startedDateTime, headers or response bodies.
    Ok(json!({"kind":"network","inputFormat":"har","summary":{
        "entryCount":report.entry_count,"statusFamilies":report.status_families,
        "durationUnit":"ms","meanDurationMs":report.durations.mean_ms,"maxDurationMs":report.durations.max_ms,
        "knownDurationCount":report.durations.sample_count,"missingDurationCount":report.durations.missing_count,
        "invalidDurationCount":report.durations.invalid_count,
        "knownResponseBytes":report.response_bytes.total_bytes,"unknownResponseByteCount":report.response_bytes.unknown_count
    }}))
}

fn read_bounded(path: &Path) -> Result<Vec<u8>, String> {
    let regular = |metadata: std::fs::Metadata| {
        if !metadata.is_file() || metadata.len() == 0 || metadata.len() > MAX_INPUT_BYTES {
            Err("E_INPUT: expected a nonempty regular local file within 256 MiB")
        } else {
            Ok(())
        }
    };
    regular(std::fs::metadata(path).map_err(|_| "E_INPUT: cannot inspect input")?)?;
    let file = File::open(path).map_err(|_| "E_INPUT: cannot open input")?;
    regular(
        file.metadata()
            .map_err(|_| "E_INPUT: cannot inspect opened input")?,
    )?;
    let mut bytes = Vec::new();
    file.take(MAX_INPUT_BYTES + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| "E_INPUT: cannot read input")?;
    if bytes.len() as u64 > MAX_INPUT_BYTES {
        return Err("E_INPUT: input exceeds 256 MiB".into());
    }
    Ok(bytes)
}

/// Publish the complete file without ever truncating an existing destination
/// (including the selected input, hardlinks and dangling symlinks).
pub fn write_new(path: &Path, bytes: &[u8]) -> Result<(), String> {
    let parent = path
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .unwrap_or(Path::new("."));
    let mut tmp = tempfile::NamedTempFile::new_in(parent)
        .map_err(|_| "E_OUTPUT: cannot stage file in selected directory")?;
    tmp.write_all(bytes)
        .and_then(|_| tmp.as_file().sync_all())
        .map_err(|_| "E_OUTPUT: cannot write complete file")?;
    tmp.persist_noclobber(path)
        .map_err(|_| "E_OUTPUT: destination exists or cannot be published")?;
    Ok(())
}

/// An installed companion only. Do not resolve a similarly named PATH binary.
pub fn benchmark_binary() -> Result<PathBuf, String> {
    let host = std::env::current_exe().map_err(|_| "E_RUNNER_UNAVAILABLE: cannot locate host")?;
    let path = host.with_file_name(format!("aps-benchmark{}", std::env::consts::EXE_SUFFIX));
    path.is_file()
        .then_some(path)
        .ok_or_else(|| "E_RUNNER_UNAVAILABLE: install aps-benchmark beside aps".into())
}

pub fn run_macrobenchmark(args: &[String]) -> Result<i32, String> {
    if !matches!(
        args.first().map(String::as_str),
        Some("macro-plan" | "macro-run")
    ) {
        return Err("E_RUNNER_MODE: expected macro-plan or macro-run".into());
    }
    let status = Command::new(benchmark_binary()?)
        .env_remove("APS_CLI_AI_KEY")
        .args(args)
        .status()
        .map_err(|_| "E_RUNNER_UNAVAILABLE: cannot execute companion")?;
    Ok(status.code().unwrap_or(1))
}

//! Source-bound analysis used by both frontends. No command dispatch, model
//! approval, report publication or MCP protocol types belong here.
mod extra;
mod har;
mod offline_inspection;
mod raw_cpu;
mod trace;

use aps_analysis::evidence::{EvidenceBundle, EvidenceItem};
use aps_analysis::frame::FrameReport;
pub use aps_analysis::{evidence::EVIDENCE_SCHEMA, frame::FrameScope};
use aps_contracts::SourceDigest;
use aps_cpu::{CpuSession, Query, TopSort};
pub(super) use extra::KINDS as ADDITIONAL_KINDS;
use serde_json::{Value, json};
use std::fs::File;
use std::io::{Read, Write};
use std::path::Path;
pub(super) use trace::has_trace_extension;

pub const MAX_INPUT_BYTES: u64 = 256 * 1024 * 1024;
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

/// Optional selectors are intentionally disjoint. An omitted selector retains
/// legacy aggregate evidence; never substitute a report for a stale selection.
pub fn frame_scope(
    scope: Option<&str>,
    frame_id: Option<u64>,
) -> Result<Option<FrameScope>, &'static str> {
    match (scope, frame_id) {
        (None, None) => Ok(None),
        (Some("report"), None) => Ok(Some(FrameScope::Report)),
        (None, Some(id)) => Ok(Some(FrameScope::Selected(id))),
        _ => Err("E_ANALYSIS_SCOPE: choose either report scope or a single frame ID"),
    }
}

/// Analyze an explicitly selected local artifact. The full filename and source
/// bytes are not part of the public payload. CLI downloads (if requested) must
/// finish *before* this boundary. The digest identifies the bytes analyzed.
pub fn analyze_path(path: &Path, options: &AnalyzeOptions) -> Result<Value, String> {
    analyze_path_with_scope(path, options, None)
}

/// Explicit Frame report / selected-row projections. Omission preserves the
/// historical representation and the public two-field AnalyzeOptions contract.
pub fn analyze_path_with_scope(
    path: &Path,
    options: &AnalyzeOptions,
    scope: Option<FrameScope>,
) -> Result<Value, String> {
    let analysis = FrozenAnalysis::open(path, options, scope)?;
    match scope {
        None => Ok(analysis.evidence),
        Some(scope) => analysis.scoped(scope),
    }
}

const FRAME_ONLY: &str = "E_ANALYSIS_SCOPE: explicit Frame scope requires a Frame report";

/// Optional query material is frozen from the very same bytes as the default
/// projection, never reacquired as a result of a question or selector.
pub(super) struct FrozenAnalysis {
    digest: SourceDigest,
    byte_length: u64,
    evidence: Value,
    details: Details,
}

enum Details {
    None,
    Frame(Box<FrameReport>),
    Har(har::HarDetails),
}

impl FrozenAnalysis {
    pub(super) fn digest(&self) -> SourceDigest {
        self.digest
    }
    pub(super) fn byte_length(&self) -> u64 {
        self.byte_length
    }
    pub(super) fn evidence(&self) -> &Value {
        &self.evidence
    }
    pub(super) fn supports_frame_scope(&self) -> bool {
        self.frame().is_some()
    }

    pub(super) fn frame(&self) -> Option<&FrameReport> {
        match &self.details {
            Details::Frame(report) => Some(report),
            _ => None,
        }
    }

    pub(super) fn har(&self) -> Option<&har::HarDetails> {
        match &self.details {
            Details::Har(details) => Some(details),
            _ => None,
        }
    }

    pub(super) fn open(
        path: &Path,
        options: &AnalyzeOptions,
        scope: Option<FrameScope>,
    ) -> Result<Self, String> {
        if path.to_string_lossy().contains("://") {
            return Err("E_LOCAL_INPUT_REQUIRED: bind a local file to this analyzer".into());
        }
        let bytes = read_bounded(path)?;
        let digest = SourceDigest::hash_reader(bytes.as_slice(), MAX_INPUT_BYTES)?;
        let byte_length = bytes.len() as u64;
        let frame_only = scope.is_some();
        let (projection, details) = if options.force_trace || trace::looks_like_trace(path, &bytes)
        {
            if frame_only {
                return Err(FRAME_ONLY.into());
            }
            if !trace::has_packet(&bytes) {
                return Err("E_TRACE_INPUT: expected a Perfetto protobuf Trace packet".into());
            }
            // Native Trace Processor reopens its input (including during fallback).
            // Hand it only captured bytes, never a replaceable source path.
            let mut snapshot = tempfile::NamedTempFile::new().map_err(|_| "E_TRACE_SNAPSHOT")?;
            snapshot
                .write_all(&bytes)
                .and_then(|_| snapshot.flush())
                .map_err(|_| "E_TRACE_SNAPSHOT")?;
            (
                trace::analyze(snapshot.path(), &digest.to_hex())?,
                Details::None,
            )
        } else {
            detect_and_analyze(path, &bytes, &digest.to_hex(), frame_only)?
        };
        let evidence =
            EvidenceBundle::from_projection(digest, byte_length, projection)?.into_json()?;
        Ok(Self {
            digest,
            byte_length,
            evidence,
            details,
        })
    }

    pub(super) fn scoped(&self, scope: FrameScope) -> Result<Value, String> {
        let frame = self.frame().ok_or(FRAME_ONLY)?;
        let evidence = frame.analysis_evidence(scope)?;
        EvidenceBundle::new(
            self.digest,
            self.byte_length,
            "frame",
            frame.input_format(),
            vec![EvidenceItem {
                id: evidence.id,
                label: evidence.summary.into(),
                value: evidence.payload,
            }],
        )?
        .into_json()
    }
}

fn detect_and_analyze(
    path: &Path,
    bytes: &[u8],
    digest: &str,
    frame_only: bool,
) -> Result<(Value, Details), String> {
    // Reject non-Frame selections before invoking any host-selected helper.
    let sections = |result: Result<Value, String>| result.map(|value| (value, Details::None));
    let frame = || {
        FrameReport::from_bytes(bytes)
            .map(|report| (report.session_summary(), Details::Frame(Box::new(report))))
            .map_err(|_| "E_FRAME_INPUT: malformed or over-budget frame report".to_string())
    };
    if bytes.starts_with(b"PERFILE2") || bytes.starts_with(b"2ELIFREP") {
        if frame_only {
            return Err(FRAME_ONLY.into());
        }
        return sections(raw_cpu::analyze(bytes, digest));
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
        if frame_only {
            return Err(FRAME_ONLY.into());
        }
        return sections(cpu_evidence(bytes, "simpleperf_protobuf"));
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
        let is_frame = document.get("frames").is_some() && document.get("schemaVersion").is_some();
        let gecko = document.get("threads").is_some() && document.get("meta").is_some();
        let benchmark = document.get("benchmarks").is_some();
        let battery = offline_inspection::is_battery(&document);
        if benchmark || battery {
            if har || is_frame || gecko || benchmark && battery {
                return Err("E_INPUT_FORMAT: ambiguous JSON artifact".into());
            }
            if frame_only {
                return Err(FRAME_ONLY.into());
            }
            if let Some(result) = extra::analyze(path, bytes, digest, Some(&document)) {
                return sections(result);
            }
        }
        drop(document);
        return match (har, is_frame, gecko) {
            (false, true, false) => frame(),
            _ if frame_only => Err(FRAME_ONLY.into()),
            (true, false, false) => {
                har::parse(bytes).map(|(projection, details)| (projection, Details::Har(details)))
            }
            (false, false, true) => sections(cpu_evidence(bytes, "gecko_v24")),
            _ => Err(
                "E_INPUT_FORMAT: unsupported or ambiguous JSON artifact (see aps capabilities)"
                    .into(),
            ),
        };
    }
    if !frame_only && let Some(result) = extra::analyze(path, bytes, digest, None) {
        return sections(result);
    }
    if bytes
        .windows(b"IntendedVsync".len())
        .any(|w| w == b"IntendedVsync")
        && bytes
            .windows(b"FrameCompleted".len())
            .any(|w| w == b"FrameCompleted")
    {
        return frame();
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

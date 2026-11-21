//! Bounded Frame AI evidence for a report or one frame in the supplied analysis.
//! The caller owns source selection and provenance validation. No paths, raw
//! states, SQL, transport or provider configuration cross this boundary.

mod input;
pub mod labels;
mod report;
pub use input::{MAX_FRAMES, MAX_INPUT_BYTES, parse_source};
pub use report::{ANALYSIS_POLICY_VERSION, FrameReport, IMPORT_WARNING, coverage, summary};

use aps_contracts::SourceDigest;
use aps_frame::{FrameAnalysisResult, analysis::AnalyzedFrame};
use aps_json::{Limits, Map, Value};
use serde_json::json;
use std::io::Read;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FrameScope {
    Report,
    Selected(u64),
}

/// Exact portable spelling (including IDs above the JavaScript safe-integer range).
pub fn parse_frame_id(text: &str) -> Result<u64, &'static str> {
    if text.is_empty()
        || !text.bytes().all(|byte| byte.is_ascii_digit())
        || (text.len() > 1 && text.starts_with('0'))
    {
        return Err("frameId must be a canonical unsigned decimal string");
    }
    text.parse().map_err(|_| "frameId exceeds u64")
}

#[derive(Debug, PartialEq)]
pub struct FrameEvidence {
    pub id: String,
    pub kind: &'static str,
    pub summary: &'static str,
    pub payload: Value,
}

const MAX_INCLUDED_FRAMES: usize = 8;
const MAX_PAYLOAD_BYTES: u64 = 8 * 1024;
const EVIDENCE_SUMMARY: &str = "Deadline misses and platform Jank are distinct signals";

fn payload_limits() -> Limits {
    Limits {
        max_bytes: MAX_PAYLOAD_BYTES,
        max_depth: 8,
        max_nodes: 4_000,
        max_string_bytes: 2_048,
        max_collection_items: 256,
    }
}

/// A stale selection never falls back to the report.
pub fn project(analysis: &FrameAnalysisResult, scope: FrameScope) -> Result<FrameEvidence, String> {
    let binding = analysis
        .frames
        .first()
        .ok_or("Import or capture frames before AI analysis")?
        .sample
        .session_id
        .as_str();
    project_inner(analysis, scope, binding, false)
}

/// Project the exact bytes opened by the caller, independent of its local session
/// ID. A report alone cannot attest receipt-only probe measurements.
pub fn project_file(
    analysis: &FrameAnalysisResult,
    scope: FrameScope,
    digest: SourceDigest,
) -> Result<FrameEvidence, String> {
    project_inner(analysis, scope, &format!("frame-{digest}"), true)
}

fn project_inner(
    analysis: &FrameAnalysisResult,
    scope: FrameScope,
    binding: &str,
    report_only: bool,
) -> Result<FrameEvidence, String> {
    if analysis.frames.is_empty() {
        return Err("Import or capture frames before AI analysis".to_owned());
    }
    let (rows, kind, selected) = match scope {
        FrameScope::Selected(frame_id) => {
            let frame = analysis
                .frames
                .iter()
                .find(|f| f.sample.frame_id == frame_id)
                .ok_or("The selected frame is no longer available")?;
            (std::slice::from_ref(frame), "frame-selection", true)
        }
        FrameScope::Report => (
            &analysis.frames[..analysis.frames.len().min(MAX_INCLUDED_FRAMES)],
            "frame-summary",
            false,
        ),
    };
    let mut payload = payload(analysis, rows, selected, report_only);
    if report_only {
        payload["analysisPolicyVersion"] = ANALYSIS_POLICY_VERSION.into();
        // Separate frontend builds can select different serde_json map backends.
        // Bind the versioned file policy to canonical keys, including nested rows.
        payload.sort_all_objects();
    }
    let payload_bytes = aps_json::to_vec(&payload, payload_limits())
        .map_err(|_| "Selected frame evidence exceeds the AI context budget")?;
    let identity = binding
        .as_bytes()
        .chain(&b"\0"[..])
        .chain(payload_bytes.as_slice());
    let digest = SourceDigest::hash_reader(identity, MAX_PAYLOAD_BYTES + 4_096)
        .map_err(|_| "Cannot bind the selected frame evidence".to_owned())?;
    Ok(FrameEvidence {
        id: format!("{kind}:{}", digest.to_hex()),
        kind,
        summary: EVIDENCE_SUMMARY,
        payload,
    })
}

fn payload(
    analysis: &FrameAnalysisResult,
    rows: &[AnalyzedFrame],
    selected: bool,
    report_only: bool,
) -> Value {
    let summary = &analysis.summary;
    json!({
        "timeUnit": "nanoseconds",
        "totalFrames": summary.total_frames,
        "deadlineMissFrames": summary.deadline_miss_frames,
        "deadlineUnknownFrames": summary.deadline_unknown_frames,
        "platformJankFrames": summary.platform_jank_frames,
        "platformUnknownFrames": summary.platform_unknown_frames,
        "p95DurationNs": summary.p95_duration_ns,
        "includedFrames": rows.len(),
        "omittedFrames": if selected { 0 } else { analysis.frames.len() - rows.len() },
        "frames": rows.iter().map(|f| frame_row(f, selected, report_only)).collect::<Vec<_>>(),
    })
}

fn frame_row(frame: &AnalyzedFrame, selected: bool, report_only: bool) -> Value {
    let sample = &frame.sample;
    // Keep legacy live GUI order: its approved IDs predate file canonicalization.
    let mut row = json!({"frameId": if report_only {
        Value::String(sample.frame_id.to_string())
    } else {
        Value::from(sample.frame_id)
    }});
    if selected {
        row["timelineStatus"] = aps_frame::frame_timeline_status(sample).as_name().into();
        row["reportedStageNs"] = Value::Object(
            sample
                .stages
                .values()
                .into_iter()
                .filter(|(_, ns)| *ns >= 0)
                .map(|(label, ns)| (label.to_owned(), Value::from(ns)))
                .collect::<Map<_, _>>(),
        );
        row["appTraceOverlaps"] = if report_only {
            // Unknown is not a measured empty set.
            Value::Null
        } else {
            Value::Array(
                aps_frame::probe::probes(sample)
                    .into_iter()
                    .map(|p| {
                        json!({
                            "marker": p.marker, "tid": p.tid, "threadRole": p.role.label(),
                            "windowOverlapNs": p.overlap_ns, "observedScheduledNs": p.scheduled_ns,
                        })
                    })
                    .collect(),
            )
        };
    }
    row["durationNs"] = sample.resolved_duration_ns().into();
    row["expectedDurationNs"] = sample.known_expected_duration_ns().into();
    row["deadlineVerdict"] = frame.deadline_verdict.as_name().into();
    row["platformJank"] = sample.platform_jank.into();
    row["droppedBeforeSample"] = sample.dropped_before_sample.into();
    row["source"] = sample.source.as_name().into();
    row
}

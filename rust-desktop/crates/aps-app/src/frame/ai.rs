//! Frame Analyzer metadata-only AI context.
//!
//! Port of Compose FrameAiContext.kt plus the shared boundedAiContext policy:
//! only summary counters and up to eight frame facts leave this module. Raw
//! per-frame states, rule versions, arbitrary strings and SQL never do. The
//! payload is bounded JSON and the evidence id is a SHA-256 binding of the
//! analysis session id and the serialized payload.

use aps_ai::{AnalysisRequest, Evidence};
use aps_contracts::SourceDigest;
use aps_frame::FrameAnalysisResult;
use aps_json::{Limits, Map, Value};

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

/// Compose frameAiContext: a stale selected id fails closed instead of
/// silently falling back to a summary context.
pub(in crate::frame) fn analysis_request(
    analysis: &FrameAnalysisResult,
    selected_frame_id: Option<u64>,
) -> Result<AnalysisRequest, String> {
    if analysis.frames.is_empty() {
        return Err("Import or capture frames before AI analysis".to_owned());
    }
    let selected = match selected_frame_id {
        Some(frame_id) => Some(
            analysis
                .frames
                .iter()
                .find(|frame| frame.sample.frame_id == frame_id)
                .ok_or_else(|| "The selected frame is no longer available".to_owned())?,
        ),
        None => None,
    };
    let rows: Vec<&aps_frame::analysis::AnalyzedFrame> = match selected {
        Some(frame) => vec![frame],
        None => analysis.frames.iter().take(MAX_INCLUDED_FRAMES).collect(),
    };
    let payload = payload(analysis, &rows, selected.is_some());
    let payload_bytes = aps_json::to_vec(&payload, payload_limits())
        .map_err(|_| "Selected frame evidence exceeds the AI context budget")?;
    let session_identity = analysis
        .frames
        .first()
        .map(|frame| frame.sample.session_id.as_str())
        .unwrap_or_default();
    let mut identity = Vec::with_capacity(session_identity.len() + payload_bytes.len() + 2);
    identity.extend_from_slice(session_identity.as_bytes());
    identity.push(0);
    identity.extend_from_slice(&payload_bytes);
    let digest =
        SourceDigest::hash_reader(identity.as_slice(), MAX_PAYLOAD_BYTES.saturating_add(4_096))
            .map_err(|_| "Cannot bind the selected frame evidence".to_owned())?;
    let kind = if selected.is_some() {
        "frame-selection"
    } else {
        "frame-summary"
    };
    Ok(AnalysisRequest {
        session_id: "frame-view".to_owned(),
        run_id: "frame-selection".to_owned(),
        generation: 1,
        profiler: "frame".to_owned(),
        scope: "Frame evidence".to_owned(),
        evidence: vec![Evidence {
            id: format!("{kind}:{}", digest.to_hex()),
            kind: kind.to_owned(),
            summary: EVIDENCE_SUMMARY.to_owned(),
            payload,
        }],
        candidates: Vec::new(),
        user_question: None,
    })
}

fn payload(
    analysis: &FrameAnalysisResult,
    rows: &[&aps_frame::analysis::AnalyzedFrame],
    selected: bool,
) -> Value {
    let summary = &analysis.summary;
    let mut object = Map::new();
    object.insert("timeUnit".into(), Value::String("nanoseconds".into()));
    object.insert(
        "totalFrames".into(),
        Value::from(summary.total_frames as u64),
    );
    object.insert(
        "deadlineMissFrames".into(),
        Value::from(summary.deadline_miss_frames as u64),
    );
    object.insert(
        "deadlineUnknownFrames".into(),
        Value::from(summary.deadline_unknown_frames as u64),
    );
    object.insert(
        "platformJankFrames".into(),
        Value::from(summary.platform_jank_frames as u64),
    );
    object.insert(
        "platformUnknownFrames".into(),
        Value::from(summary.platform_unknown_frames as u64),
    );
    object.insert(
        "p95DurationNs".into(),
        summary.p95_duration_ns.map_or(Value::Null, Value::from),
    );
    object.insert("includedFrames".into(), Value::from(rows.len() as u64));
    object.insert(
        "omittedFrames".into(),
        Value::from(if selected {
            0
        } else {
            analysis.frames.len().saturating_sub(rows.len()) as u64
        }),
    );
    object.insert(
        "frames".into(),
        Value::Array(
            rows.iter()
                .map(|frame| {
                    let sample = &frame.sample;
                    let mut value = Map::new();
                    value.insert("frameId".into(), Value::from(sample.frame_id));
                    value.insert(
                        "durationNs".into(),
                        sample
                            .resolved_duration_ns()
                            .map_or(Value::Null, Value::from),
                    );
                    value.insert(
                        "expectedDurationNs".into(),
                        sample
                            .known_expected_duration_ns()
                            .map_or(Value::Null, Value::from),
                    );
                    value.insert(
                        "deadlineVerdict".into(),
                        Value::String(frame.deadline_verdict.as_name().to_owned()),
                    );
                    value.insert(
                        "platformJank".into(),
                        sample.platform_jank.map_or(Value::Null, Value::Bool),
                    );
                    value.insert(
                        "droppedBeforeSample".into(),
                        Value::from(sample.dropped_before_sample),
                    );
                    value.insert(
                        "source".into(),
                        Value::String(sample.source.as_name().to_owned()),
                    );
                    Value::Object(value)
                })
                .collect(),
        ),
    );
    Value::Object(object)
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_frame::analysis::{AnalyzedFrame, FrameSummary};
    use aps_frame::model::FrameStages;
    use aps_frame::{
        ExpectedDurationSource, FrameDeadlineVerdict, FrameSample, FrameSource, JankSeverity,
    };
    use std::collections::{BTreeMap, BTreeSet};

    fn sample(frame_id: u64, total_duration_ns: Option<i64>) -> FrameSample {
        FrameSample {
            frame_id,
            session_id: "session-1".into(),
            source: FrameSource::Gfxinfo,
            package_name: Some("com.example".into()),
            process_id: Some(42),
            activity_name: Some("MainActivity".into()),
            window_id: Some("window:1".into()),
            intended_vsync_ns: Some(1_000),
            actual_vsync_ns: Some(1_001),
            frame_completed_ns: None,
            present_ns: None,
            expected_duration_ns: None,
            expected_duration_source: ExpectedDurationSource::Unknown,
            refresh_rate_hz: Some(120.0),
            frame_timeline_vsync_id: None,
            total_duration_ns,
            stages: FrameStages::default(),
            platform_jank: Some(false),
            platform_jank_types: BTreeSet::new(),
            platform_jank_rule_id: Some("secret-rule".into()),
            platform_jank_rule_version: Some("9.9.9".into()),
            states: BTreeMap::from([
                ("rawStateKey".to_owned(), "raw-state-value".to_owned()),
                ("sql".to_owned(), "SELECT secret".to_owned()),
            ]),
            eligible_for_jank: true,
            dropped_before_sample: 3,
            layout_snapshot_id: Some("layout-secret".into()),
            fragment_name: Some("FeedFragment".into()),
            page_name: Some("Feed".into()),
            interaction_state: Some("scroll".into()),
            render_thread_name: Some("RenderThread".into()),
            surface_flinger_jank_type: Some("Missed deadline".into()),
            jank_stats_jank: Some(true),
            jank_stats_reasons: BTreeSet::from(["slow draw".to_owned()]),
            jank_stats_rule_id: Some("jankstats-default".into()),
            jank_stats_rule_version: Some("1.0.0".into()),
        }
    }

    fn analyzed(frame_id: u64, total_duration_ns: Option<i64>) -> AnalyzedFrame {
        AnalyzedFrame {
            sample: sample(frame_id, total_duration_ns),
            deadline_verdict: FrameDeadlineVerdict::Missed,
            severity: JankSeverity::Major,
            missed_vsync_count: Some(2),
            platform_jank_types: BTreeSet::new(),
            largest_reported_stage: Some("Draw".into()),
        }
    }

    fn analysis(frames: Vec<AnalyzedFrame>) -> FrameAnalysisResult {
        FrameAnalysisResult {
            summary: FrameSummary {
                total_frames: frames.len(),
                deadline_miss_frames: frames.len(),
                p95_duration_ns: Some(20_000_000),
                ..FrameSummary::default()
            },
            frames,
            clusters: Vec::new(),
            attributions: Vec::new(),
        }
    }

    fn payload_text(request: &AnalysisRequest) -> String {
        String::from_utf8(
            aps_json::to_vec(&request.evidence[0].payload, Limits::default()).unwrap(),
        )
        .unwrap()
    }

    #[test]
    fn report_includes_eight_frames_and_explicit_omissions_within_budget() {
        let frames = (1..=24).map(|id| analyzed(id, Some(12_345))).collect();
        let result = analysis(frames);
        let request = analysis_request(&result, None).unwrap();
        let payload = &request.evidence[0].payload;
        assert_eq!(payload["totalFrames"].as_u64(), Some(24));
        assert_eq!(payload["includedFrames"].as_u64(), Some(8));
        assert_eq!(payload["omittedFrames"].as_u64(), Some(16));
        assert_eq!(payload["timeUnit"].as_str(), Some("nanoseconds"));
        assert_eq!(payload["frames"].as_array().map(Vec::len), Some(8));
        assert_eq!(request.evidence[0].kind, "frame-summary");
        assert!(payload_text(&request).len() <= 8 * 1024);
    }

    #[test]
    fn selection_preserves_metrics_missing_states_and_rejects_stale_ids() {
        let mut frames = vec![analyzed(1, Some(12_345))];
        frames.extend((2..=10).map(|id| analyzed(id, Some(9_000))));
        let result = analysis(frames);
        let request = analysis_request(&result, Some(1)).unwrap();
        let payload = &request.evidence[0].payload;
        assert_eq!(payload["includedFrames"].as_u64(), Some(1));
        assert_eq!(payload["omittedFrames"].as_u64(), Some(0));
        assert_eq!(payload["frames"][0]["frameId"].as_u64(), Some(1));
        assert_eq!(payload["frames"][0]["durationNs"].as_i64(), Some(12_345));
        assert!(payload["frames"][0]["expectedDurationNs"].is_null());
        assert_eq!(
            payload["frames"][0]["deadlineVerdict"].as_str(),
            Some("MISSED")
        );
        assert_eq!(request.evidence[0].kind, "frame-selection");
        assert!(analysis_request(&result, Some(99)).is_err());
    }

    #[test]
    fn raw_states_rules_and_paths_never_leave_the_projection() {
        let result = analysis(vec![analyzed(1, Some(12_345))]);
        let request = analysis_request(&result, Some(1)).unwrap();
        let text = payload_text(&request);
        for forbidden in [
            "raw-state-value",
            "rawStateKey",
            "SELECT secret",
            "secret-rule",
            "9.9.9",
            "layout-secret",
            "RenderThread",
            "FeedFragment",
        ] {
            assert!(!text.contains(forbidden), "{forbidden} leaked");
        }
        assert_eq!(request.evidence[0].summary, EVIDENCE_SUMMARY);
        assert!(request.candidates.is_empty());
        assert_eq!(request.generation, 1);
        assert_eq!(request.session_id, "frame-view");
        assert_eq!(request.profiler, "frame");
    }

    #[test]
    fn empty_analysis_fails_closed() {
        let result = analysis(Vec::new());
        assert!(analysis_request(&result, None).is_err());
        assert!(analysis_request(&result, Some(1)).is_err());
    }
}

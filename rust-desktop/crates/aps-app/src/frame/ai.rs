//! Frame Analyzer GUI request adapter. Evidence selection, bounds, and
//! projection are shared with offline analysis; probe planning stays here.
use aps_ai::{AnalysisRequest, Evidence};
use aps_analysis::frame::{FrameEvidence, FrameScope, project, project_file};
use aps_contracts::SourceDigest;
use aps_frame::FrameAnalysisResult;

pub(in crate::frame) fn analysis_request(
    analysis: &FrameAnalysisResult,
    selected_frame_id: Option<u64>,
) -> Result<AnalysisRequest, String> {
    let scope = selected_frame_id.map_or(FrameScope::Report, FrameScope::Selected);
    Ok(request(project(analysis, scope)?))
}

pub(in crate::frame) fn analysis_request_for_file(
    analysis: &FrameAnalysisResult,
    selected_frame_id: Option<u64>,
    digest: SourceDigest,
) -> Result<AnalysisRequest, String> {
    let scope = selected_frame_id.map_or(FrameScope::Report, FrameScope::Selected);
    Ok(request(project_file(analysis, scope, digest)?))
}

fn request(projection: FrameEvidence) -> AnalysisRequest {
    AnalysisRequest {
        session_id: "frame-view".to_owned(),
        run_id: "frame-selection".to_owned(),
        generation: 1,
        profiler: "frame".to_owned(),
        scope: "Frame evidence".to_owned(),
        evidence: vec![Evidence {
            id: projection.id,
            kind: projection.kind.to_owned(),
            summary: projection.summary.to_owned(),
            payload: projection.payload,
        }],
        candidates: Vec::new(),
        user_question: None,
        mcp: None,
    }
}

/// No model output is executed. The measurement question, not arbitrary
/// per-frame strings, is bound into the provider-approved evidence.
pub(super) fn probe_plan(
    analysis: &FrameAnalysisResult,
    id: u64,
) -> Result<AnalysisRequest, String> {
    let row = analysis
        .frames
        .iter()
        .find(|f| f.sample.frame_id == id)
        .ok_or("Frame unavailable")?;
    let has_probe = !super::perfetto::probes(&row.sample).is_empty();
    if !super::can_probe(row) && !has_probe {
        return Err("Select a measured problem frame or a frame with a matched probe.".into());
    }
    let mut r = analysis_request(analysis, Some(id))?;
    r.evidence[0].kind = "frame-trace-probe".into();
    let question = if has_probe {
        "Interpret the measured probe overlaps for this frame, including frames within budget. Compare wall time to observed scheduled time only where measured. Do not sum nested spans, infer zero CPU from missing data, or claim an optimization without a comparable baseline."
    } else {
        "Propose a falsifiable Trace instrumentation hypothesis, not a source fix. Name the synchronous app boundary worth testing, how to reproduce it, and what would disprove it. If evidence points outside the app, recommend investigation there instead of adding an app probe."
    };
    r.scope = format!(
        "{question} Distinguish deadline misses, platform jank and actual causes. Same-process overlap alone is not causation. No verified method or build mapping was supplied: never invent a path, line or method."
    );
    r.run_id = if has_probe {
        "frame-trace-interpret"
    } else {
        "frame-trace-plan"
    }
    .into();
    Ok(r)
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_frame::analysis::{AnalyzedFrame, FrameSummary};
    use aps_frame::model::FrameStages;
    use aps_frame::{
        ExpectedDurationSource, FrameDeadlineVerdict, FrameSample, FrameSource, JankSeverity,
    };
    use aps_json::Limits;
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
    fn plan_requires_a_measured_problem_and_uses_only_known_stage_names() {
        let mut frame = analyzed(7, None);
        frame.sample.stages.draw_ns = Some(9_000);
        assert!(probe_plan(&analysis(vec![frame.clone()]), 7).is_err());
        frame.sample.total_duration_ns = Some(20_000);
        frame.sample.source = FrameSource::Perfetto;
        frame.sample.states.insert(
            "aps.trace.0".into(),
            "APS.Frame/private-source 7 9 -1 Main".into(),
        );
        frame.sample.states.insert(
            "aps.trace.1".into(),
            "APS.Frame/1111111111111111 7 90000 -1 Main".into(),
        );
        let plan = probe_plan(&analysis(vec![frame.clone()]), 7).unwrap();
        let facts = &plan.evidence[0].payload["frames"][0];
        assert_eq!(facts["appTraceOverlaps"].as_array().unwrap().len(), 0);
        assert_eq!(facts["reportedStageNs"]["Draw"].as_i64(), Some(9_000));
        frame.deadline_verdict = FrameDeadlineVerdict::Met;
        assert!(probe_plan(&analysis(vec![frame]), 7).is_err());
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
        assert_eq!(
            request.evidence[0].summary,
            "Deadline misses and platform Jank are distinct signals"
        );
        assert!(request.candidates.is_empty());
        assert_eq!(request.generation, 1);
        assert_eq!(request.session_id, "frame-view");
        assert_eq!(request.profiler, "frame");
    }

    #[test]
    fn measured_probe_is_bounded_and_evidence_remains_session_bound() {
        let mut frame = analyzed(3, Some(20_000));
        frame.sample.source = FrameSource::Perfetto;
        frame.sample.states.insert(
            "aps.trace.0".into(),
            "APS.Frame/1111111111111111 7 9500 -1 Other".into(),
        );
        let first = analysis_request(&analysis(vec![frame.clone()]), Some(3)).unwrap();
        let overlap = &first.evidence[0].payload["frames"][0]["appTraceOverlaps"][0];
        assert_eq!(overlap["windowOverlapNs"], 9500);
        assert!(overlap["observedScheduledNs"].is_null());
        frame.sample.session_id = "another-session".into();
        let second = analysis_request(&analysis(vec![frame]), Some(3)).unwrap();
        assert_eq!(first.evidence[0].payload, second.evidence[0].payload);
        assert_ne!(first.evidence[0].id, second.evidence[0].id);
    }

    #[test]
    fn empty_analysis_fails_closed() {
        let result = analysis(Vec::new());
        assert!(analysis_request(&result, None).is_err());
        assert!(analysis_request(&result, Some(1)).is_err());
    }
}

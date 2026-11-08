//! Import of the exported `frame-analysis.json` (Compose schemaVersion 3).
//!
//! The Frame Analyzer exports a versioned analysis report; opening that report
//! again must not be mistaken for a gfxinfo FrameStats capture. The report is a
//! superset of the frame rows, so this importer rebuilds the frame samples and
//! lets the current analyzer produce the analysis, which keeps the opened page
//! consistent with the filters, clusters and attributions it renders.

use super::sessions::{
    clipped, optional_string, parse_expected_source, parse_frame_source, parse_jank_type,
    parse_states, string_list,
};
use aps_frame::FrameSample;
use aps_frame::model::{ExpectedDurationSource, FrameStages};
use aps_json::{Limits, Value};

const SCHEMA_VERSION: u64 = 3;
const MAX_BYTES: u64 = 512 * 1024 * 1024;
const MAX_FRAMES: usize = 100_000;
const MAX_STRING_BYTES: usize = 4096;
const MAX_TEXT_BYTES: usize = 512;
const MAX_ID_BYTES: usize = 128;
const MAX_JANK_TYPES: usize = 16;
const MAX_REASONS: usize = 32;

fn export_limits() -> Limits {
    Limits {
        max_bytes: MAX_BYTES,
        max_depth: 8,
        max_nodes: 4_000_000,
        max_string_bytes: MAX_STRING_BYTES,
        max_collection_items: MAX_FRAMES,
    }
}

/// An exported analysis report always starts with a JSON object; gfxinfo
/// FrameStats text never does.
pub(in crate::frame) fn looks_like_export(text: &str) -> bool {
    text.trim_start().starts_with('{')
}

/// Rebuild the frame rows of an exported analysis report.
///
/// Only the frame rows are read: the exported verdicts, clusters, attributions
/// and summary are derived data that the current analyzer recomputes, so an old
/// report never injects a stale classification into the live views.
pub(in crate::frame) fn parse_export(
    text: &str,
    session_id: &str,
) -> Result<Vec<FrameSample>, String> {
    let value = aps_json::parse_slice(text.as_bytes(), export_limits()).map_err(|error| {
        format!("The selected frame-analysis.json is not a bounded valid JSON document: {error}.")
    })?;
    let root = value
        .as_object()
        .ok_or_else(|| "The selected frame-analysis.json is not a JSON object.".to_owned())?;
    let version = root
        .get("schemaVersion")
        .and_then(Value::as_u64)
        .unwrap_or_default();
    if version != SCHEMA_VERSION {
        return Err(format!(
            "Unsupported frame-analysis.json schema version {version}; this build reads version {SCHEMA_VERSION}."
        ));
    }
    let frames = root
        .get("frames")
        .and_then(Value::as_array)
        .ok_or_else(|| "The selected frame-analysis.json has no frame rows.".to_owned())?;
    if frames.len() > MAX_FRAMES {
        return Err(format!(
            "The selected frame-analysis.json exceeds the {MAX_FRAMES} frame limit."
        ));
    }
    let mut samples = Vec::with_capacity(frames.len());
    for frame in frames {
        samples.push(parse_frame(frame, session_id).ok_or_else(|| {
            "The selected frame-analysis.json has an unusable frame row.".to_owned()
        })?);
    }
    Ok(samples)
}

fn parse_frame(value: &Value, session_id: &str) -> Option<FrameSample> {
    let object = value.as_object()?;
    Some(FrameSample {
        frame_id: object.get("frameId")?.as_u64()?,
        session_id: clipped(session_id, MAX_ID_BYTES),
        source: parse_frame_source(object.get("source")?.as_str()?)?,
        package_name: optional_string(object, "packageName", MAX_TEXT_BYTES),
        process_id: object
            .get("processId")
            .and_then(Value::as_i64)
            .map(|value| value as i32),
        activity_name: optional_string(object, "activityName", MAX_TEXT_BYTES),
        window_id: optional_string(object, "windowId", MAX_TEXT_BYTES),
        intended_vsync_ns: object.get("intendedVsyncNs").and_then(Value::as_i64),
        actual_vsync_ns: object.get("actualVsyncNs").and_then(Value::as_i64),
        frame_completed_ns: object.get("frameCompletedNs").and_then(Value::as_i64),
        present_ns: object.get("presentNs").and_then(Value::as_i64),
        expected_duration_ns: object.get("expectedDurationNs").and_then(Value::as_i64),
        expected_duration_source: object
            .get("expectedDurationSource")
            .and_then(Value::as_str)
            .and_then(parse_expected_source)
            .unwrap_or(ExpectedDurationSource::Unknown),
        refresh_rate_hz: object.get("refreshRateHz").and_then(Value::as_f64),
        frame_timeline_vsync_id: object.get("frameTimelineVsyncId").and_then(Value::as_i64),
        total_duration_ns: object.get("totalDurationNs").and_then(Value::as_i64),
        stages: parse_export_stages(object.get("stagesNs")),
        platform_jank: object.get("platformJank").and_then(Value::as_bool),
        platform_jank_types: object
            .get("platformJankTypes")
            .and_then(Value::as_array)
            .map(|values| {
                values
                    .iter()
                    .take(MAX_JANK_TYPES)
                    .filter_map(Value::as_str)
                    .filter_map(parse_jank_type)
                    .collect()
            })
            .unwrap_or_default(),
        platform_jank_rule_id: optional_string(object, "platformJankRuleId", MAX_TEXT_BYTES),
        platform_jank_rule_version: optional_string(
            object,
            "platformJankRuleVersion",
            MAX_TEXT_BYTES,
        ),
        states: parse_states(object.get("states")),
        eligible_for_jank: object
            .get("eligibleForJank")
            .and_then(Value::as_bool)
            .unwrap_or(true),
        dropped_before_sample: object
            .get("droppedBeforeSample")
            .and_then(Value::as_i64)
            .unwrap_or_default(),
        layout_snapshot_id: None,
        fragment_name: optional_string(object, "fragmentName", MAX_TEXT_BYTES),
        page_name: optional_string(object, "pageName", MAX_TEXT_BYTES),
        interaction_state: optional_string(object, "interactionState", MAX_TEXT_BYTES),
        render_thread_name: optional_string(object, "renderThreadName", MAX_TEXT_BYTES),
        surface_flinger_jank_type: optional_string(
            object,
            "surfaceFlingerJankType",
            MAX_TEXT_BYTES,
        ),
        jank_stats_jank: object.get("jankStatsJank").and_then(Value::as_bool),
        jank_stats_reasons: string_list(object.get("jankStatsReasons"), MAX_REASONS)
            .into_iter()
            .collect(),
        jank_stats_rule_id: optional_string(object, "jankStatsRuleId", MAX_TEXT_BYTES),
        jank_stats_rule_version: optional_string(object, "jankStatsRuleVersion", MAX_TEXT_BYTES),
    })
}

/// The exporter writes stages under their Compose display labels.
fn parse_export_stages(value: Option<&Value>) -> FrameStages {
    let Some(object) = value.and_then(Value::as_object) else {
        return FrameStages::default();
    };
    FrameStages {
        input_ns: object.get("Input").and_then(Value::as_i64),
        animation_ns: object.get("Animation").and_then(Value::as_i64),
        layout_measure_ns: object.get("Layout/Measure").and_then(Value::as_i64),
        draw_ns: object.get("Draw").and_then(Value::as_i64),
        sync_ns: object.get("Sync").and_then(Value::as_i64),
        command_issue_ns: object.get("Command").and_then(Value::as_i64),
        swap_buffers_ns: object.get("Swap").and_then(Value::as_i64),
        gpu_ns: object.get("GPU").and_then(Value::as_i64),
    }
}

#[cfg(test)]
mod tests {
    use super::super::export::export_json;
    use super::*;
    use aps_frame::{FrameJankAnalyzer, FrameSource};
    use std::collections::{BTreeMap, BTreeSet};
    use std::path::{Path, PathBuf};
    use std::sync::atomic::{AtomicUsize, Ordering};

    static NEXT_DIRECTORY: AtomicUsize = AtomicUsize::new(0);

    struct TestDirectory(PathBuf);

    impl TestDirectory {
        fn new(name: &str) -> Self {
            let path = std::env::temp_dir().join(format!(
                "aps-frame-export-{name}-{}-{}",
                std::process::id(),
                NEXT_DIRECTORY.fetch_add(1, Ordering::Relaxed)
            ));
            std::fs::create_dir_all(&path).unwrap();
            Self(path)
        }

        fn path(&self) -> &Path {
            &self.0
        }
    }

    impl Drop for TestDirectory {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    fn sample(frame_id: u64) -> FrameSample {
        FrameSample {
            frame_id,
            session_id: "session-1".into(),
            source: FrameSource::Gfxinfo,
            package_name: Some("com.example.app".into()),
            process_id: Some(4242),
            activity_name: Some("MainActivity".into()),
            window_id: Some("window:1".into()),
            intended_vsync_ns: Some(frame_id as i64 * 16_666_667),
            actual_vsync_ns: Some(frame_id as i64 * 16_666_667),
            frame_completed_ns: Some(frame_id as i64 * 16_666_667 + 20_000_000),
            present_ns: None,
            expected_duration_ns: Some(16_666_667),
            expected_duration_source: ExpectedDurationSource::FrameInterval,
            refresh_rate_hz: Some(60.0),
            frame_timeline_vsync_id: None,
            total_duration_ns: Some(20_000_000),
            stages: FrameStages {
                input_ns: Some(1_000),
                animation_ns: Some(2_000),
                layout_measure_ns: Some(3_000),
                draw_ns: Some(8_000_000),
                sync_ns: Some(4_000),
                command_issue_ns: Some(5_000),
                swap_buffers_ns: Some(6_000),
                gpu_ns: Some(7_000),
            },
            platform_jank: Some(true),
            platform_jank_types: [aps_frame::JankType::PlatformReported]
                .into_iter()
                .collect(),
            platform_jank_rule_id: Some("rule".into()),
            platform_jank_rule_version: Some("1".into()),
            states: BTreeMap::from([("screen".to_owned(), "feed".to_owned())]),
            eligible_for_jank: true,
            dropped_before_sample: 0,
            layout_snapshot_id: None,
            fragment_name: Some("FeedFragment".into()),
            page_name: Some("Feed".into()),
            interaction_state: Some("scroll".into()),
            render_thread_name: Some("RenderThread".into()),
            surface_flinger_jank_type: Some("Missed deadline".into()),
            jank_stats_jank: Some(true),
            jank_stats_reasons: BTreeSet::from(["slow draw".to_owned()]),
            jank_stats_rule_id: Some("jankstats".into()),
            jank_stats_rule_version: Some("1.0.0".into()),
        }
    }

    #[test]
    fn exported_analysis_round_trips_through_the_importer() {
        let directory = TestDirectory::new("round-trip");
        let output = directory.path().join("frame-analysis.json");
        let analyzer = FrameJankAnalyzer::default();
        let original = analyzer.analyze(&[sample(1), sample(2)]);
        export_json(&original, &output).unwrap();
        let text = std::fs::read_to_string(&output).unwrap();
        assert!(looks_like_export(&text));

        let samples = parse_export(&text, "session-1").unwrap();
        let imported = analyzer.analyze(&samples);
        assert_eq!(samples.len(), 2);
        assert_eq!(imported, original);
    }

    #[test]
    fn gfxinfo_text_is_not_an_exported_report() {
        assert!(!looks_like_export(
            "---PROFILEDATA---\nFlags,FrameTimelineVsyncId\n"
        ));
        assert!(looks_like_export("  \n\t{\"schemaVersion\": 3}"));
    }

    #[test]
    fn unsupported_versions_and_unusable_rows_fail_closed() {
        assert_eq!(
            parse_export(r#"{"schemaVersion": 2, "frames": []}"#, "session-1")
                .err()
                .unwrap(),
            "Unsupported frame-analysis.json schema version 2; this build reads version 3."
        );
        assert_eq!(
            parse_export(r#"{"schemaVersion": 3}"#, "session-1")
                .err()
                .unwrap(),
            "The selected frame-analysis.json has no frame rows."
        );
        assert_eq!(
            parse_export(
                r#"{"schemaVersion": 3, "frames": [{"frameId": 1}]}"#,
                "session-1"
            )
            .err()
            .unwrap(),
            "The selected frame-analysis.json has an unusable frame row."
        );
        assert!(parse_export("not json", "session-1").is_err());
    }
}

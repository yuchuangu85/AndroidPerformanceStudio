use crate::labels;
use aps_contracts::SourceDigest;
use aps_frame::gfxinfo::{parse_gfxinfo_for_query, sanitize_stored_timing};
use aps_frame::model::{ExpectedDurationSource, FrameSample, FrameSource, FrameStages, JankType};
use aps_json::Limits;
use serde_json::{Map, Value};
use std::collections::HashSet;
use std::fs::{self, File};
use std::io::Read;
use std::path::Path;

const MAX_INPUT_BYTES: u64 = 64 * 1024 * 1024;
pub(crate) const MAX_FRAMES: usize = 100_000;

pub(crate) struct LoadedInput {
    pub digest: SourceDigest,
    pub input_format: &'static str,
    pub parse_warning_count: usize,
    pub samples: Vec<FrameSample>,
}

pub(crate) fn load_input(
    path: &Path,
    capture: Option<&crate::capture::CaptureMeta>,
) -> Result<LoadedInput, String> {
    let bytes = read_bounded(path, MAX_INPUT_BYTES)?;
    // Hash the same buffer we parse, not a separate read of the pathname.
    let digest = SourceDigest::hash_reader(bytes.as_slice(), MAX_INPUT_BYTES)?;
    if capture.is_some_and(|c| c.report_digest != digest) {
        return Err("Report bytes do not match the selected capture receipt".into());
    }
    let session_id = format!("frame-{digest}");
    let text = std::str::from_utf8(&bytes).map_err(|_| "Frame input must be UTF-8 text")?;
    let (mut samples, input_format, parse_warning_count) = if text.trim_start().starts_with('{') {
        (
            parse_export(&bytes, &session_id, capture.is_some())?,
            "frame_analysis_json_v3",
            0,
        )
    } else {
        if capture.is_some() {
            return Err("Capture receipt requires a Frame Analyzer v3 report".into());
        }
        let parsed = parse_gfxinfo_for_query(text, &session_id, MAX_FRAMES)?;
        (parsed.frames, "gfxinfo_framestats", parsed.warnings.len())
    };
    if samples.is_empty() {
        return Err("No usable frame rows in selected input".into());
    }
    if let Some(meta) = capture {
        meta.check_samples(&samples)?;
    }
    labels::normalize_contexts(&mut samples);
    Ok(LoadedInput {
        digest,
        input_format,
        parse_warning_count,
        samples,
    })
}

pub(crate) fn read_bounded(path: &Path, max_bytes: u64) -> Result<Vec<u8>, String> {
    let metadata = fs::metadata(path).map_err(|_| "Cannot inspect selected frame input")?;
    require_regular_input(&metadata, max_bytes)?;
    let file = File::open(path).map_err(|_| "Cannot open selected frame input")?;
    require_regular_input(
        &file
            .metadata()
            .map_err(|_| "Cannot inspect open frame input")?,
        max_bytes,
    )?;
    let mut bytes = Vec::new();
    file.take(max_bytes + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| "Cannot read selected frame input")?;
    if bytes.len() as u64 > max_bytes {
        return Err("Frame input exceeds byte limit".into());
    }
    Ok(bytes)
}

fn require_regular_input(metadata: &fs::Metadata, max_bytes: u64) -> Result<(), String> {
    if !metadata.is_file() || metadata.len() == 0 || metadata.len() > max_bytes {
        return Err("Frame input must be a nonempty regular file within the byte limit".into());
    }
    Ok(())
}

pub(crate) fn check_source(path: &Path, expected: SourceDigest) -> Result<(), String> {
    check_digest(path, MAX_INPUT_BYTES, expected)
}

pub(crate) fn check_digest(
    path: &Path,
    max_bytes: u64,
    expected: SourceDigest,
) -> Result<(), String> {
    require_regular_input(
        &fs::metadata(path).map_err(|_| "Cannot inspect selected frame input")?,
        max_bytes,
    )?;
    if SourceDigest::hash_file(path, max_bytes)? != expected {
        return Err("Selected frame input digest changed".into());
    }
    Ok(())
}

// Read the desktop exporter wire schema, not the private history store. Only
// frame evidence goes into the analyzer. Exported summary/verdicts and arbitrary
// app state never participate in the result.
fn parse_export(bytes: &[u8], session_id: &str, capture: bool) -> Result<Vec<FrameSample>, String> {
    let document = aps_json::parse_slice(
        bytes,
        Limits {
            max_bytes: MAX_INPUT_BYTES,
            max_depth: 8,
            max_nodes: 4_000_000,
            max_string_bytes: 4096,
            max_collection_items: MAX_FRAMES,
        },
    )
    .map_err(|_| "Invalid or unbounded frame-analysis JSON")?;
    if document.get("schemaVersion").and_then(Value::as_u64) != Some(3) {
        return Err("Expected frame-analysis JSON schemaVersion 3".into());
    }
    let rows = document
        .get("frames")
        .and_then(Value::as_array)
        .ok_or("Frame-analysis JSON has no frames array")?;
    let mut ids = HashSet::with_capacity(rows.len());
    rows.iter()
        .map(|row| {
            let mut frame = parse_frame(row, session_id).ok_or("Unusable frame-analysis row")?;
            if capture {
                let states = row
                    .get("states")
                    .and_then(Value::as_object)
                    .ok_or("Missing capture frame state")?;
                for (key, value) in states {
                    if key.starts_with("aps.trace.") || key == "aps.target.process" {
                        if key != "aps.target.process"
                            && key != "aps.trace.omitted"
                            && !matches!(
                                key.strip_prefix("aps.trace."),
                                Some("0" | "1" | "2" | "3" | "4" | "5" | "6" | "7")
                            )
                        {
                            return Err("Unsupported probe state in capture".into());
                        }
                        let text = bounded_label(value).ok_or("Invalid capture state")?;
                        frame.states.insert(key.clone(), text.to_owned());
                    }
                }
            }
            if !ids.insert(frame.frame_id) {
                return Err("Duplicate frameId in frame-analysis JSON".into());
            }
            Ok(frame)
        })
        .collect()
}

fn parse_frame(value: &Value, session_id: &str) -> Option<FrameSample> {
    let row = value.as_object()?;
    let number = |key| row.get(key).and_then(Value::as_i64);
    let raw_label = |key| row.get(key).and_then(bounded_label).map(str::to_owned);
    let label = |key| row.get(key).and_then(public_label).map(str::to_owned);
    // JSON already bounds strings. Window/activity values that won't be
    // published still get distinct private keys for cluster/group identity.
    let context = |key| {
        row.get(key)
            .and_then(Value::as_str)
            .filter(|text| !text.trim().is_empty())
            .map(str::to_owned)
    };
    let source = parse_source(row.get("source")?.as_str()?)?;
    let expected_duration_ns = number("expectedDurationNs").filter(|&value| value > 0);
    let expected_duration_source = match (
        expected_duration_ns,
        row.get("expectedDurationSource").and_then(Value::as_str),
    ) {
        (Some(_), Some("PLATFORM_DEADLINE")) => ExpectedDurationSource::PlatformDeadline,
        (Some(_), Some("FRAME_INTERVAL")) => ExpectedDurationSource::FrameInterval,
        (Some(_), Some("REFRESH_RATE")) => ExpectedDurationSource::RefreshRate,
        (Some(_), Some("INFERRED_VSYNC")) => ExpectedDurationSource::InferredVsync,
        _ => ExpectedDurationSource::Unknown,
    };
    let states = row.get("states").and_then(Value::as_object);
    let mut frame = FrameSample {
        frame_id: row.get("frameId")?.as_u64()?,
        session_id: session_id.to_owned(),
        source,
        package_name: raw_label("packageName"),
        process_id: number("processId")
            .and_then(|id| i32::try_from(id).ok())
            .filter(|&id| id > 0),
        activity_name: context("activityName"),
        window_id: context("windowId"),
        intended_vsync_ns: number("intendedVsyncNs"),
        actual_vsync_ns: number("actualVsyncNs"),
        frame_completed_ns: number("frameCompletedNs"),
        present_ns: number("presentNs"),
        expected_duration_ns,
        expected_duration_source,
        refresh_rate_hz: row
            .get("refreshRateHz")
            .and_then(Value::as_f64)
            .filter(|value| value.is_finite() && *value > 0.0),
        frame_timeline_vsync_id: number("frameTimelineVsyncId").filter(|&id| id >= 0),
        total_duration_ns: number("totalDurationNs").filter(|&ns| ns >= 0),
        stages: parse_stages(row.get("stagesNs").and_then(Value::as_object)),
        platform_jank: row.get("platformJank").and_then(Value::as_bool),
        platform_jank_types: array(row, "platformJankTypes")
            .filter_map(Value::as_str)
            .filter_map(parse_jank_type)
            .collect(),
        platform_jank_rule_id: label("platformJankRuleId"),
        platform_jank_rule_version: label("platformJankRuleVersion"),
        states: [
            "screen",
            "screenName",
            "uiState",
            "fragment",
            "page",
            "interaction",
        ]
        .into_iter()
        .filter_map(|key| Some((key.to_owned(), public_label(states?.get(key)?)?.to_owned())))
        .collect(),
        eligible_for_jank: row
            .get("eligibleForJank")
            .and_then(Value::as_bool)
            .unwrap_or(true),
        dropped_before_sample: number("droppedBeforeSample")
            .filter(|&count| count >= 0)
            .unwrap_or(0),
        layout_snapshot_id: None,
        fragment_name: label("fragmentName"),
        page_name: label("pageName"),
        interaction_state: label("interactionState"),
        render_thread_name: label("renderThreadName"),
        surface_flinger_jank_type: label("surfaceFlingerJankType"),
        jank_stats_jank: row.get("jankStatsJank").and_then(Value::as_bool),
        jank_stats_reasons: array(row, "jankStatsReasons")
            .filter_map(Value::as_str)
            .filter(|reason| !reason.trim().is_empty())
            // Reason presence is evidence for the problems view even when its
            // producer text cannot safely leave the process.
            .map(|reason| {
                if labels::public(reason, false) {
                    reason
                } else {
                    labels::REDACTED_REASON
                }
            })
            .map(str::to_owned)
            .collect(),
        jank_stats_rule_id: label("jankStatsRuleId"),
        jank_stats_rule_version: label("jankStatsRuleVersion"),
    };
    sanitize_stored_timing(&mut frame);
    let valid_timestamp = |ns: i64| (0..i64::MAX).contains(&ns);
    frame.intended_vsync_ns = frame.intended_vsync_ns.filter(|&ns| valid_timestamp(ns));
    frame.actual_vsync_ns = frame.actual_vsync_ns.filter(|&ns| valid_timestamp(ns));
    let valid_end =
        |&ns: &i64| valid_timestamp(ns) && frame.intended_vsync_ns.is_none_or(|start| ns >= start);
    frame.frame_completed_ns = frame.frame_completed_ns.filter(valid_end);
    frame.present_ns = frame.present_ns.filter(valid_end);
    Some(frame)
}

fn bounded_label(value: &Value) -> Option<&str> {
    value.as_str().filter(|label| labels::bounded(label))
}

fn public_label(value: &Value) -> Option<&str> {
    value.as_str().filter(|label| labels::public(label, false))
}

fn array<'a>(row: &'a Map<String, Value>, key: &str) -> impl Iterator<Item = &'a Value> {
    row.get(key)
        .and_then(Value::as_array)
        .into_iter()
        .flatten()
        .take(32)
}

pub(crate) fn parse_source(source: &str) -> Option<FrameSource> {
    Some(match source {
        "GFXINFO" => FrameSource::Gfxinfo,
        "PERFETTO" => FrameSource::Perfetto,
        "FRAME_METRICS" => FrameSource::FrameMetrics,
        "JANK_STATS" => FrameSource::JankStats,
        _ => return None,
    })
}

fn parse_jank_type(name: &str) -> Option<JankType> {
    Some(match name {
        "PLATFORM_REPORTED" => JankType::PlatformReported,
        "DEADLINE_MISSED" => JankType::DeadlineMissed,
        "SLOW_INPUT" => JankType::SlowInput,
        "SLOW_ANIMATION" => JankType::SlowAnimation,
        "SLOW_LAYOUT" => JankType::SlowLayout,
        "SLOW_DRAW" => JankType::SlowDraw,
        "SLOW_SYNC" => JankType::SlowSync,
        "SLOW_COMMAND" => JankType::SlowCommand,
        "SLOW_SWAP" => JankType::SlowSwap,
        "UNKNOWN" => JankType::Unknown,
        _ => return None,
    })
}

fn parse_stages(object: Option<&Map<String, Value>>) -> FrameStages {
    let stage = |key| object?.get(key)?.as_i64().filter(|&ns| ns >= 0);
    FrameStages {
        input_ns: stage("Input"),
        animation_ns: stage("Animation"),
        layout_measure_ns: stage("Layout/Measure"),
        draw_ns: stage("Draw"),
        sync_ns: stage("Sync"),
        command_issue_ns: stage("Command"),
        swap_buffers_ns: stage("Swap"),
        gpu_ns: stage("GPU"),
    }
}

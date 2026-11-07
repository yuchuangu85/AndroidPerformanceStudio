//! Frame Analyzer CSV/JSON exporters, ported from Compose FrameCsvExporter and
//! FrameJsonExporter.
//!
//! CSV writes the exact Compose header/column order and escaping; JSON writes
//! the Compose schemaVersion 3 layout (summary, frames, clusters, attributions)
//! with the same key order and Kotlin-style number/string formatting. Both
//! stream to a sibling temporary file and rename, so a failed export never
//! leaves a truncated destination.

use aps_frame::{FrameAnalysisResult, FrameAttribution, FrameSample};
use std::{
    fs::{self, File, OpenOptions},
    io::{self, BufWriter, Write},
    path::{Path, PathBuf},
    sync::atomic::{AtomicU64, Ordering},
};

const CSV_HEADER: &str = "frame_id,source,intended_vsync_ns,duration_ns,expected_duration_ns,expected_source,\
deadline_verdict,platform_jank_signal,platform_jank_rule_id,platform_jank_rule_version,\
severity,missed_vsync_count,largest_reported_stage,platform_jank_types,frame_timeline_vsync_id";

static NEXT_TEMPORARY: AtomicU64 = AtomicU64::new(0);

pub(in crate::frame) fn export_csv(
    result: &FrameAnalysisResult,
    output: &Path,
) -> Result<(), String> {
    write_output(output, |writer| {
        writeln!(writer, "{CSV_HEADER}")?;
        for frame in &result.frames {
            let sample = &frame.sample;
            let values = [
                sample.frame_id.to_string(),
                sample.source.as_name().to_owned(),
                option_text(sample.intended_vsync_ns.map(|value| value.to_string())),
                option_text(sample.resolved_duration_ns().map(|value| value.to_string())),
                option_text(sample.expected_duration_ns.map(|value| value.to_string())),
                sample.expected_duration_source.as_name().to_owned(),
                frame.deadline_verdict.as_name().to_owned(),
                option_text(sample.platform_jank.map(|value| value.to_string())),
                option_text(sample.platform_jank_rule_id.clone()),
                option_text(sample.platform_jank_rule_version.clone()),
                frame.severity.as_name().to_owned(),
                option_text(frame.missed_vsync_count.map(|value| value.to_string())),
                option_text(frame.largest_reported_stage.clone()),
                frame
                    .platform_jank_types
                    .iter()
                    .map(|jank_type| jank_type.as_name())
                    .collect::<Vec<_>>()
                    .join("|"),
                option_text(
                    sample
                        .frame_timeline_vsync_id
                        .map(|value| value.to_string()),
                ),
            ];
            let row = values
                .iter()
                .map(|value| escape_csv(value))
                .collect::<Vec<_>>()
                .join(",");
            writeln!(writer, "{row}")?;
        }
        Ok(())
    })
}

pub(in crate::frame) fn export_json(
    result: &FrameAnalysisResult,
    output: &Path,
) -> Result<(), String> {
    write_output(output, |writer| {
        let mut out = JsonWriter { writer };
        out.line("{")?;
        out.line("  \"schemaVersion\": 3,")?;
        out.line("  \"summary\": {")?;
        let summary = &result.summary;
        out.line(&format!("    \"totalFrames\": {},", summary.total_frames))?;
        out.line(&format!(
            "    \"deadlineClassifiedFrames\": {},",
            summary.deadline_classified_frames
        ))?;
        out.line(&format!(
            "    \"deadlineMissFrames\": {},",
            summary.deadline_miss_frames
        ))?;
        out.line(&format!(
            "    \"deadlineUnknownFrames\": {},",
            summary.deadline_unknown_frames
        ))?;
        out.line(&format!(
            "    \"deadlineMissRate\": {},",
            json_f64(summary.deadline_miss_rate)
        ))?;
        out.line(&format!(
            "    \"platformClassifiedFrames\": {},",
            summary.platform_classified_frames
        ))?;
        out.line(&format!(
            "    \"platformJankFrames\": {},",
            summary.platform_jank_frames
        ))?;
        out.line(&format!(
            "    \"platformUnknownFrames\": {},",
            summary.platform_unknown_frames
        ))?;
        out.line(&format!(
            "    \"platformJankRate\": {},",
            json_f64(summary.platform_jank_rate)
        ))?;
        out.line(&format!(
            "    \"jankStatsClassifiedFrames\": {},",
            summary.jank_stats_classified_frames
        ))?;
        out.line(&format!(
            "    \"jankStatsJankFrames\": {},",
            summary.jank_stats_jank_frames
        ))?;
        out.line(&format!(
            "    \"jankStatsJankRate\": {},",
            json_f64(summary.jank_stats_jank_rate)
        ))?;
        out.line(&format!(
            "    \"p50DurationNs\": {},",
            json_i64(summary.p50_duration_ns)
        ))?;
        out.line(&format!(
            "    \"p95DurationNs\": {},",
            json_i64(summary.p95_duration_ns)
        ))?;
        out.line(&format!(
            "    \"p99DurationNs\": {},",
            json_i64(summary.p99_duration_ns)
        ))?;
        out.line(&format!(
            "    \"worstDurationNs\": {}",
            json_i64(summary.worst_duration_ns)
        ))?;
        out.line("  },")?;
        out.line("  \"frames\": [")?;
        for (index, frame) in result.frames.iter().enumerate() {
            write_frame(&mut out, frame)?;
            out.line(if index == result.frames.len().saturating_sub(1) {
                ""
            } else {
                ","
            })?;
        }
        out.line("  ],")?;
        out.line("  \"clusters\": [")?;
        for (index, cluster) in result.clusters.iter().enumerate() {
            out.line("    {")?;
            out.line(&format!("      \"id\": {},", cluster.id))?;
            out.line(&format!(
                "      \"firstFrameId\": {},",
                cluster.first_frame_id
            ))?;
            out.line(&format!(
                "      \"lastFrameId\": {},",
                cluster.last_frame_id
            ))?;
            let frame_ids = cluster
                .deadline_miss_frame_ids
                .iter()
                .map(u64::to_string)
                .collect::<Vec<_>>()
                .join(", ");
            out.line(&format!("      \"deadlineMissFrameIds\": [{frame_ids}],"))?;
            out.line(&format!("      \"durationNs\": {},", cluster.duration_ns))?;
            out.line(&format!(
                "      \"worstSeverity\": {},",
                json_string(cluster.worst_severity.as_name())
            ))?;
            out.line(&format!(
                "      \"windowId\": {},",
                json_str(cluster.window_id.as_deref())
            ))?;
            out.line(&format!(
                "      \"activityName\": {},",
                json_str(cluster.activity_name.as_deref())
            ))?;
            out.line(&format!(
                "      \"dominantReportedStage\": {}",
                json_str(cluster.dominant_reported_stage.as_deref())
            ))?;
            out.line("    }")?;
            out.line(if index == result.clusters.len().saturating_sub(1) {
                ""
            } else {
                ","
            })?;
        }
        out.line("  ],")?;
        out.line("  \"attributions\": [")?;
        for (index, attribution) in result.attributions.iter().enumerate() {
            write_attribution(&mut out, attribution)?;
            out.line(if index == result.attributions.len().saturating_sub(1) {
                ""
            } else {
                ","
            })?;
        }
        out.line("  ]")?;
        out.line("}")?;
        Ok(())
    })
}

struct JsonWriter<'a> {
    writer: &'a mut BufWriter<File>,
}

impl JsonWriter<'_> {
    fn line(&mut self, value: &str) -> io::Result<()> {
        self.writer.write_all(value.as_bytes())?;
        self.writer.write_all(b"\n")
    }
}

fn write_frame(
    out: &mut JsonWriter<'_>,
    frame: &aps_frame::analysis::AnalyzedFrame,
) -> io::Result<()> {
    let sample = &frame.sample;
    out.line("    {")?;
    out.line(&format!("      \"frameId\": {},", sample.frame_id))?;
    out.line(&format!(
        "      \"source\": {},",
        json_string(sample.source.as_name())
    ))?;
    out.line(&format!(
        "      \"packageName\": {},",
        json_str(sample.package_name.as_deref())
    ))?;
    out.line(&format!(
        "      \"processId\": {},",
        json_i32(sample.process_id)
    ))?;
    out.line(&format!(
        "      \"activityName\": {},",
        json_str(sample.activity_name.as_deref())
    ))?;
    out.line(&format!(
        "      \"fragmentName\": {},",
        json_str(sample.fragment_name.as_deref())
    ))?;
    out.line(&format!(
        "      \"pageName\": {},",
        json_str(sample.page_name.as_deref())
    ))?;
    out.line(&format!(
        "      \"interactionState\": {},",
        json_str(sample.interaction_state.as_deref())
    ))?;
    out.line(&format!(
        "      \"renderThreadName\": {},",
        json_str(sample.render_thread_name.as_deref())
    ))?;
    out.line(&format!(
        "      \"surfaceFlingerJankType\": {},",
        json_str(sample.surface_flinger_jank_type.as_deref())
    ))?;
    out.line(&format!(
        "      \"jankStatsJank\": {},",
        json_bool(sample.jank_stats_jank)
    ))?;
    out.line(&format!(
        "      \"jankStatsReasons\": {},",
        json_string_array(
            &sample
                .jank_stats_reasons
                .iter()
                .cloned()
                .collect::<Vec<_>>()
        )
    ))?;
    out.line(&format!(
        "      \"jankStatsRuleId\": {},",
        json_str(sample.jank_stats_rule_id.as_deref())
    ))?;
    out.line(&format!(
        "      \"jankStatsRuleVersion\": {},",
        json_str(sample.jank_stats_rule_version.as_deref())
    ))?;
    out.line(&format!(
        "      \"windowId\": {},",
        json_str(sample.window_id.as_deref())
    ))?;
    out.line(&format!(
        "      \"intendedVsyncNs\": {},",
        json_i64(sample.intended_vsync_ns)
    ))?;
    out.line(&format!(
        "      \"actualVsyncNs\": {},",
        json_i64(sample.actual_vsync_ns)
    ))?;
    out.line(&format!(
        "      \"frameCompletedNs\": {},",
        json_i64(sample.frame_completed_ns)
    ))?;
    out.line(&format!(
        "      \"presentNs\": {},",
        json_i64(sample.present_ns)
    ))?;
    out.line(&format!(
        "      \"durationNs\": {},",
        json_i64(sample.resolved_duration_ns())
    ))?;
    out.line(&format!(
        "      \"totalDurationNs\": {},",
        json_i64(sample.total_duration_ns)
    ))?;
    out.line(&format!(
        "      \"expectedDurationNs\": {},",
        json_i64(sample.expected_duration_ns)
    ))?;
    out.line(&format!(
        "      \"expectedDurationSource\": {},",
        json_string(sample.expected_duration_source.as_name())
    ))?;
    out.line(&format!(
        "      \"refreshRateHz\": {},",
        json_f64(sample.refresh_rate_hz)
    ))?;
    out.line(&format!(
        "      \"frameTimelineVsyncId\": {},",
        json_i64(sample.frame_timeline_vsync_id)
    ))?;
    out.line(&format!(
        "      \"deadlineVerdict\": {},",
        json_string(frame.deadline_verdict.as_name())
    ))?;
    out.line(&format!(
        "      \"severity\": {},",
        json_string(frame.severity.as_name())
    ))?;
    out.line(&format!(
        "      \"missedVsyncCount\": {},",
        optionally(frame.missed_vsync_count.map(|value| value.to_string()))
    ))?;
    out.line(&format!(
        "      \"largestReportedStage\": {},",
        json_str(frame.largest_reported_stage.as_deref())
    ))?;
    out.line(&format!(
        "      \"platformJankTypes\": {},",
        json_string_array(
            &frame
                .platform_jank_types
                .iter()
                .map(|jank_type| jank_type.as_name().to_owned())
                .collect::<Vec<_>>()
        )
    ))?;
    out.line(&format!(
        "      \"platformJank\": {},",
        json_bool(sample.platform_jank)
    ))?;
    out.line(&format!(
        "      \"platformJankRuleId\": {},",
        json_str(sample.platform_jank_rule_id.as_deref())
    ))?;
    out.line(&format!(
        "      \"platformJankRuleVersion\": {},",
        json_str(sample.platform_jank_rule_version.as_deref())
    ))?;
    out.line(&format!(
        "      \"eligibleForJank\": {},",
        sample.eligible_for_jank
    ))?;
    out.line(&format!(
        "      \"droppedBeforeSample\": {},",
        sample.dropped_before_sample
    ))?;
    out.line(&format!(
        "      \"states\": {},",
        json_object(
            &sample
                .states
                .iter()
                .map(|(key, value)| (key.clone(), value.clone()))
                .collect::<Vec<_>>()
        )
    ))?;
    out.line(&format!(
        "      \"stagesNs\": {}",
        json_object_numbers(&stage_pairs(sample))
    ))?;
    out.line("    }")
}

fn write_attribution(out: &mut JsonWriter<'_>, attribution: &FrameAttribution) -> io::Result<()> {
    out.line("    {")?;
    out.line(&format!(
        "      \"activityName\": {},",
        json_string(&attribution.activity_name)
    ))?;
    out.line(&format!(
        "      \"windowId\": {},",
        json_string(&attribution.window_id)
    ))?;
    out.line(&format!(
        "      \"uiState\": {},",
        json_string(&attribution.ui_state)
    ))?;
    out.line(&format!(
        "      \"fragmentName\": {},",
        json_str(attribution.fragment_name.as_deref())
    ))?;
    out.line(&format!(
        "      \"pageName\": {},",
        json_str(attribution.page_name.as_deref())
    ))?;
    out.line(&format!(
        "      \"interactionState\": {},",
        json_str(attribution.interaction_state.as_deref())
    ))?;
    out.line(&format!(
        "      \"renderThreadNames\": {},",
        json_string_array(&sorted(&attribution.render_thread_names))
    ))?;
    out.line(&format!(
        "      \"surfaceFlingerJankTypes\": {},",
        json_string_array(&sorted(&attribution.surface_flinger_jank_types))
    ))?;
    out.line(&format!(
        "      \"jankStatsFrames\": {},",
        attribution.jank_stats_frames
    ))?;
    out.line(&format!(
        "      \"totalFrames\": {},",
        attribution.total_frames
    ))?;
    out.line(&format!(
        "      \"deadlineMissFrames\": {},",
        attribution.deadline_miss_frames
    ))?;
    out.line(&format!(
        "      \"platformJankFrames\": {},",
        attribution.platform_jank_frames
    ))?;
    out.line(&format!(
        "      \"averageDurationNs\": {},",
        json_i64(attribution.average_duration_ns)
    ))?;
    out.line(&format!(
        "      \"p95DurationNs\": {},",
        json_i64(attribution.p95_duration_ns)
    ))?;
    out.line(&format!(
        "      \"worstDurationNs\": {},",
        json_i64(attribution.worst_duration_ns)
    ))?;
    out.line(&format!(
        "      \"jankTypes\": {}",
        json_string_array(&sorted(&attribution.jank_types))
    ))?;
    out.line("    }")
}

fn sorted(values: &std::collections::BTreeSet<String>) -> Vec<String> {
    values.iter().cloned().collect()
}

fn stage_pairs(sample: &FrameSample) -> Vec<(String, String)> {
    let stages = &sample.stages;
    let mut pairs = Vec::new();
    for (name, value) in [
        ("Input", stages.input_ns),
        ("Animation", stages.animation_ns),
        ("Layout/Measure", stages.layout_measure_ns),
        ("Draw", stages.draw_ns),
        ("Sync", stages.sync_ns),
        ("Command", stages.command_issue_ns),
        ("Swap", stages.swap_buffers_ns),
        ("GPU", stages.gpu_ns),
    ] {
        if let Some(value) = value {
            pairs.push((name.to_owned(), value.to_string()));
        }
    }
    pairs
}

/// Compose joinToString(",") treats a null value as the text "null".
fn option_text(value: Option<String>) -> String {
    value.unwrap_or_else(|| "null".to_owned())
}

fn optionally(value: Option<String>) -> String {
    option_text(value)
}

fn escape_csv(value: &str) -> String {
    if value.contains(',') || value.contains('"') || value.contains('\n') {
        format!("\"{}\"", value.replace('"', "\"\""))
    } else {
        value.to_owned()
    }
}

fn json_string(value: &str) -> String {
    let mut escaped = String::with_capacity(value.len() + 2);
    escaped.push('"');
    for character in value.chars() {
        match character {
            '"' => escaped.push_str("\\\""),
            '\\' => escaped.push_str("\\\\"),
            '\u{0008}' => escaped.push_str("\\b"),
            '\u{000C}' => escaped.push_str("\\f"),
            '\n' => escaped.push_str("\\n"),
            '\r' => escaped.push_str("\\r"),
            '\t' => escaped.push_str("\\t"),
            character if character < '\u{0020}' => {
                escaped.push_str(&format!("\\u{:04x}", character as u32))
            }
            character => escaped.push(character),
        }
    }
    escaped.push('"');
    escaped
}

fn json_str(value: Option<&str>) -> String {
    value.map_or_else(|| "null".to_owned(), json_string)
}

fn json_i64(value: Option<i64>) -> String {
    value.map_or_else(|| "null".to_owned(), |value| value.to_string())
}

fn json_i32(value: Option<i32>) -> String {
    value.map_or_else(|| "null".to_owned(), |value| value.to_string())
}

fn json_bool(value: Option<bool>) -> String {
    value.map_or_else(|| "null".to_owned(), |value| value.to_string())
}

fn json_f64(value: Option<f64>) -> String {
    value.map_or_else(
        || "null".to_owned(),
        |value| {
            if !value.is_finite() {
                "null".to_owned()
            } else if value.fract() == 0.0 && value.abs() < 10_000_000.0 {
                format!("{value:.1}")
            } else {
                format!("{value}")
            }
        },
    )
}

fn json_string_array(values: &[String]) -> String {
    format!(
        "[{}]",
        values
            .iter()
            .map(|value| json_string(value))
            .collect::<Vec<_>>()
            .join(", ")
    )
}

fn json_object(entries: &[(String, String)]) -> String {
    format!(
        "{{{}}}",
        entries
            .iter()
            .map(|(key, value)| format!("{}: {}", json_string(key), json_string(value)))
            .collect::<Vec<_>>()
            .join(", ")
    )
}

/// Compose stagesNs is a Map<String, Long>; values stay JSON numbers.
fn json_object_numbers(entries: &[(String, String)]) -> String {
    format!(
        "{{{}}}",
        entries
            .iter()
            .map(|(key, value)| format!("{}: {}", json_string(key), value))
            .collect::<Vec<_>>()
            .join(", ")
    )
}

fn write_output(
    output: &Path,
    write: impl FnOnce(&mut BufWriter<File>) -> io::Result<()>,
) -> Result<(), String> {
    let parent = output
        .parent()
        .filter(|parent| !parent.as_os_str().is_empty())
        .ok_or_else(|| "Frame export destination has no parent directory".to_owned())?;
    fs::create_dir_all(parent)
        .map_err(|_| "Cannot create the frame export directory".to_owned())?;
    let temporary = temporary_path(output)?;
    let file = OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(&temporary)
        .map_err(|_| "Cannot create the frame export file".to_owned())?;
    let mut writer = BufWriter::new(file);
    let result = write(&mut writer)
        .and_then(|()| writer.flush())
        .and_then(|()| writer.get_ref().sync_all());
    drop(writer);
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
        return Err("Cannot write the frame export file".to_owned());
    }
    if fs::rename(&temporary, output).is_err() {
        let _ = fs::remove_file(output);
        if fs::rename(&temporary, output).is_err() {
            let _ = fs::remove_file(&temporary);
            return Err("Cannot finish the frame export".to_owned());
        }
    }
    Ok(())
}

fn temporary_path(output: &Path) -> Result<PathBuf, String> {
    let parent = output
        .parent()
        .filter(|parent| !parent.as_os_str().is_empty())
        .ok_or_else(|| "Frame export destination has no parent directory".to_owned())?;
    let name = output
        .file_name()
        .and_then(|name| name.to_str())
        .ok_or_else(|| "Frame export destination needs a UTF-8 file name".to_owned())?;
    Ok(parent.join(format!(
        ".{name}-{}-{}.tmp",
        std::process::id(),
        NEXT_TEMPORARY.fetch_add(1, Ordering::Relaxed)
    )))
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_frame::analysis::{AnalyzedFrame, DeadlineMissCluster, FrameSummary};
    use aps_frame::model::FrameStages;
    use aps_frame::{
        ExpectedDurationSource, FrameDeadlineVerdict, FrameSample, FrameSource, JankSeverity,
    };
    use std::collections::BTreeMap;
    use std::sync::atomic::AtomicUsize;

    static NEXT_DIRECTORY: AtomicUsize = AtomicUsize::new(0);

    struct TestDirectory(PathBuf);
    impl TestDirectory {
        fn new(name: &str) -> Self {
            let path = std::env::temp_dir().join(format!(
                "aps-frame-export-{name}-{}-{}",
                std::process::id(),
                NEXT_DIRECTORY.fetch_add(1, Ordering::Relaxed)
            ));
            let _ = fs::remove_dir_all(&path);
            fs::create_dir_all(&path).unwrap();
            Self(path)
        }
        fn path(&self) -> &Path {
            &self.0
        }
    }
    impl Drop for TestDirectory {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    fn sample() -> FrameSample {
        FrameSample {
            frame_id: 1,
            session_id: "session".into(),
            source: FrameSource::FrameMetrics,
            package_name: Some("dev.example".into()),
            process_id: Some(42),
            activity_name: Some("MainActivity".into()),
            window_id: Some("window:1".into()),
            intended_vsync_ns: Some(100),
            actual_vsync_ns: None,
            frame_completed_ns: None,
            present_ns: None,
            expected_duration_ns: Some(8_333_333),
            expected_duration_source: ExpectedDurationSource::RefreshRate,
            refresh_rate_hz: Some(120.0),
            frame_timeline_vsync_id: None,
            total_duration_ns: Some(20_000_000),
            stages: FrameStages {
                draw_ns: Some(8_000_000),
                ..FrameStages::default()
            },
            platform_jank: Some(true),
            platform_jank_types: [aps_frame::JankType::PlatformReported]
                .into_iter()
                .collect(),
            platform_jank_rule_id: Some("jank-rule".into()),
            platform_jank_rule_version: Some("1.2.3".into()),
            states: BTreeMap::from([("screen".to_owned(), "feed\"list".to_owned())]),
            eligible_for_jank: true,
            dropped_before_sample: 0,
            layout_snapshot_id: None,
            fragment_name: Some("FeedFragment".into()),
            page_name: Some("Feed".into()),
            interaction_state: Some("scroll".into()),
            render_thread_name: Some("RenderThread".into()),
            surface_flinger_jank_type: Some("Missed deadline".into()),
            jank_stats_jank: Some(true),
            jank_stats_reasons: ["slow draw".to_owned()].into_iter().collect(),
            jank_stats_rule_id: Some("jankstats-default".into()),
            jank_stats_rule_version: Some("1.0.0".into()),
        }
    }

    fn analyzed(sample: FrameSample) -> AnalyzedFrame {
        AnalyzedFrame {
            sample,
            deadline_verdict: FrameDeadlineVerdict::Missed,
            severity: JankSeverity::Major,
            missed_vsync_count: Some(1),
            platform_jank_types: [aps_frame::JankType::PlatformReported]
                .into_iter()
                .collect(),
            largest_reported_stage: Some("Draw".into()),
        }
    }

    fn analysis(frames: Vec<AnalyzedFrame>) -> FrameAnalysisResult {
        FrameAnalysisResult {
            frames,
            summary: FrameSummary {
                total_frames: 1,
                deadline_classified_frames: 1,
                deadline_miss_frames: 1,
                deadline_unknown_frames: 0,
                deadline_miss_rate: Some(1.0),
                platform_classified_frames: 1,
                platform_jank_frames: 1,
                platform_unknown_frames: 0,
                platform_jank_rate: Some(1.0),
                jank_stats_classified_frames: 1,
                jank_stats_jank_frames: 1,
                jank_stats_jank_rate: Some(1.0),
                p50_duration_ns: Some(20_000_000),
                p95_duration_ns: Some(20_000_000),
                p99_duration_ns: Some(20_000_000),
                worst_duration_ns: Some(20_000_000),
            },
            clusters: vec![DeadlineMissCluster {
                id: 0,
                first_frame_id: 1,
                last_frame_id: 1,
                deadline_miss_frame_ids: vec![1],
                duration_ns: 20_000_000,
                worst_severity: JankSeverity::Major,
                window_id: Some("window:1".into()),
                activity_name: Some("MainActivity".into()),
                dominant_reported_stage: Some("Draw".into()),
            }],
            attributions: vec![aps_frame::FrameAttribution {
                activity_name: "MainActivity".into(),
                window_id: "window:1".into(),
                ui_state: "scroll".into(),
                total_frames: 1,
                deadline_miss_frames: 1,
                platform_jank_frames: 1,
                average_duration_ns: Some(20_000_000),
                p95_duration_ns: Some(20_000_000),
                worst_duration_ns: Some(20_000_000),
                jank_types: ["PLATFORM_REPORTED".to_owned()].into_iter().collect(),
                fragment_name: Some("FeedFragment".into()),
                page_name: Some("Feed".into()),
                interaction_state: Some("scroll".into()),
                render_thread_names: ["RenderThread".to_owned()].into_iter().collect(),
                surface_flinger_jank_types: ["Missed deadline".to_owned()].into_iter().collect(),
                jank_stats_frames: 1,
            }],
        }
    }

    #[test]
    fn csv_matches_compose_header_order_null_text_and_verdicts() {
        let directory = TestDirectory::new("csv");
        let output = directory.path().join("frames.csv");
        let mut sample = sample();
        sample.source = FrameSource::Gfxinfo;
        sample.process_id = None;
        sample.package_name = None;
        sample.activity_name = None;
        sample.window_id = None;
        sample.intended_vsync_ns = None;
        sample.expected_duration_ns = Some(8_333_333);
        sample.expected_duration_source = ExpectedDurationSource::FrameInterval;
        sample.platform_jank = None;
        sample.platform_jank_types.clear();
        sample.refresh_rate_hz = None;
        sample.platform_jank_rule_id = Some("jank-rule".into());
        sample.platform_jank_rule_version = Some("1.2.3".into());
        sample.total_duration_ns = Some(10_000_000);
        let mut frame = analyzed(sample);
        frame.missed_vsync_count = Some(1);
        frame.largest_reported_stage = None;
        frame.platform_jank_types.clear();
        let result = FrameAnalysisResult {
            summary: FrameSummary::default(),
            clusters: Vec::new(),
            attributions: Vec::new(),
            frames: vec![frame],
        };

        export_csv(&result, &output).unwrap();
        let text = fs::read_to_string(&output).unwrap();
        let lines: Vec<&str> = text.lines().collect();
        assert_eq!(lines[0], CSV_HEADER);
        assert_eq!(lines.len(), 2);
        assert_eq!(
            lines[1],
            "1,GFXINFO,null,10000000,8333333,FRAME_INTERVAL,MISSED,null,jank-rule,1.2.3,MAJOR,1,null,,null"
        );
        assert!(text.contains("MISSED"));
        assert!(text.contains("8333333"));
        assert!(text.contains("jank-rule,1.2.3"));
    }

    #[test]
    fn csv_escapes_commas_quotes_and_newlines_like_compose() {
        let directory = TestDirectory::new("csv-escape");
        let output = directory.path().join("frames.csv");
        let mut sample = sample();
        sample.platform_jank_rule_id = Some("jank,\"rule\"\nline".into());
        let result = FrameAnalysisResult {
            summary: FrameSummary::default(),
            clusters: Vec::new(),
            attributions: Vec::new(),
            frames: vec![analyzed(sample)],
        };
        export_csv(&result, &output).unwrap();
        let text = fs::read_to_string(&output).unwrap();
        assert!(text.contains("\"jank,\"\"rule\"\"\nline\""));
    }

    #[test]
    fn json_matches_compose_schema_summary_frames_clusters_and_attributions() {
        let directory = TestDirectory::new("json");
        let output = directory.path().join("report.json");
        let result = analysis(vec![analyzed(sample())]);
        export_json(&result, &output).unwrap();

        let text = fs::read_to_string(&output).unwrap();
        assert!(text.starts_with("{\n  \"schemaVersion\": 3,\n"));
        assert!(text.contains("\"deadlineMissFrames\": 1"));
        assert!(text.contains("\"platformJankFrames\": 1"));
        assert!(text.contains("\"platformJankRuleId\": \"jank-rule\""));
        assert!(text.contains("\"platformJankRuleVersion\": \"1.2.3\""));
        assert!(text.contains("\"fragmentName\": \"FeedFragment\""));
        assert!(text.contains("\"jankStatsJank\": true"));
        assert!(text.contains("\"jankStatsRuleId\": \"jankstats-default\""));
        assert!(text.contains("\"screen\": \"feed\\\"list\""));
        assert!(text.contains("\"clusters\": ["));
        assert!(text.contains("\"attributions\": ["));
        assert!(text.ends_with("]\n}\n"));

        let value = aps_json::parse_slice(text.as_bytes(), aps_json::Limits::default()).unwrap();
        assert_eq!(value["schemaVersion"].as_u64(), Some(3));
        assert_eq!(value["summary"]["totalFrames"].as_u64(), Some(1));
        assert_eq!(value["summary"]["deadlineMissRate"].as_f64(), Some(1.0));
        assert_eq!(value["summary"]["p95DurationNs"].as_i64(), Some(20_000_000));
        assert_eq!(value["frames"][0]["frameId"].as_u64(), Some(1));
        assert_eq!(value["frames"][0]["source"].as_str(), Some("FRAME_METRICS"));
        assert_eq!(value["frames"][0]["durationNs"].as_i64(), Some(20_000_000));
        assert_eq!(
            value["frames"][0]["expectedDurationSource"].as_str(),
            Some("REFRESH_RATE")
        );
        assert_eq!(
            value["frames"][0]["deadlineVerdict"].as_str(),
            Some("MISSED")
        );
        assert_eq!(value["frames"][0]["severity"].as_str(), Some("MAJOR"));
        assert_eq!(value["frames"][0]["missedVsyncCount"].as_u64(), Some(1));
        assert_eq!(value["frames"][0]["refreshRateHz"].as_f64(), Some(120.0));
        assert_eq!(
            value["frames"][0]["states"]["screen"].as_str(),
            Some("feed\"list")
        );
        assert_eq!(
            value["frames"][0]["stagesNs"]["Draw"].as_u64(),
            Some(8_000_000)
        );
        assert_eq!(
            value["clusters"][0]["deadlineMissFrameIds"]
                .as_array()
                .map(|values| values[0].as_u64()),
            Some(Some(1))
        );
        assert_eq!(
            value["attributions"][0]["jankTypes"][0].as_str(),
            Some("PLATFORM_REPORTED")
        );
    }

    #[test]
    fn exports_create_parent_directories_and_suggest_compose_names() {
        let directory = TestDirectory::new("dirs");
        let output = directory.path().join("nested/deeper/frames.csv");
        let result = FrameAnalysisResult {
            summary: FrameSummary::default(),
            frames: Vec::new(),
            clusters: Vec::new(),
            attributions: Vec::new(),
        };
        export_csv(&result, &output).unwrap();
        assert!(output.is_file());
        export_json(&result, &directory.path().join("nested/deeper/frames.json")).unwrap();
        assert!(export_csv(&result, Path::new("bare.csv")).is_err());
    }
}

//! Faithful port of Compose `parser-frame/.../GfxInfoFrameStatsParser.kt`.
//!
//! Parses `dumpsys gfxinfo ... framestats` text with repeated `Window:` /
//! `---PROFILEDATA---` sections, modern named columns, independent trailing
//! separators and explicit budget provenance. Unknown and metadata columns are
//! preserved in `FrameSample.states` as `gfxinfo.column.<name>`. Warnings keep
//! the Compose wording.

use crate::MAX_INPUT_BYTES;
use crate::model::{ExpectedDurationSource, FrameSample, FrameSource, FrameStages};
use std::collections::BTreeMap;

const MIN_REFRESH_INTERVAL_NS: i64 = 4_000_000;
const MAX_REFRESH_INTERVAL_NS: i64 = 50_000_000;
const REFRESH_INTERVAL_TOLERANCE_DIVISOR: i64 = 20;

const METADATA_COLUMNS: &[&str] = &[
    "FrameTimelineVsyncId",
    "InputEventId",
    "FrameStartTime",
    "CommandSubmissionCompleted",
];

const KNOWN_COLUMNS: &[&str] = &[
    "Flags",
    "FrameTimelineVsyncId",
    "IntendedVsync",
    "Vsync",
    "InputEventId",
    "OldestInputEvent",
    "NewestInputEvent",
    "HandleInputStart",
    "AnimationStart",
    "PerformTraversalsStart",
    "DrawStart",
    "FrameDeadline",
    "FrameInterval",
    "FrameStartTime",
    "SyncQueued",
    "SyncStart",
    "IssueDrawCommandsStart",
    "SwapBuffers",
    "FrameCompleted",
    "GpuCompleted",
    "SwapBuffersCompleted",
    "DisplayPresentTime",
    "DequeueBufferDuration",
    "QueueBufferDuration",
    "CommandSubmissionCompleted",
];

/// Parsed gfxinfo framestats snapshot with Compose-parity warnings.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct GfxInfoParse {
    pub frames: Vec<FrameSample>,
    pub warnings: Vec<String>,
}

/// Full `GfxInfoFrameStatsParser.parse` port.
///
/// `warn_on_empty_frames` is false for live polling snapshots, where an empty
/// section is expected; imports keep the Compose empty warning.
pub fn parse_gfxinfo(
    text: &str,
    session_id: &str,
    package_name: Option<&str>,
    warn_on_empty_frames: bool,
) -> Result<GfxInfoParse, String> {
    if text.len() as u64 > MAX_INPUT_BYTES {
        return Err("FrameStats text exceeds 256 MiB".into());
    }

    let mut frames: Vec<FrameSample> = Vec::new();
    let mut warnings: Vec<String> = Vec::new();
    let mut header: Option<Vec<&str>> = None;
    let mut window_id: Option<String> = None;
    let mut in_profile_data = false;
    let mut found_header = false;
    let mut malformed_rows = 0usize;
    let mut unknown_columns: Vec<String> = Vec::new();

    for raw_line in text.lines() {
        let line = raw_line.trim();
        if let Some(name) = line.strip_prefix("Window:") {
            window_id = Some(name.trim().to_owned()).filter(|value| !value.is_empty());
        } else if line == "---PROFILEDATA---" {
            in_profile_data = !in_profile_data;
            if !in_profile_data {
                header = None;
            }
        } else if in_profile_data && line.starts_with("Flags,") {
            let mut columns: Vec<&str> = line.split(',').map(str::trim).collect();
            while columns.last() == Some(&"") {
                columns.pop();
            }
            header = Some(columns);
            found_header = true;
        } else if in_profile_data
            && let Some(header) = &header
            && is_frame_data_row(line)
        {
            let columns = frame_columns(line, header.len());
            if columns.len() != header.len() {
                malformed_rows += 1;
            } else {
                for name in header.iter().filter(|name| !KNOWN_COLUMNS.contains(name)) {
                    if !unknown_columns.iter().any(|existing| existing == name) {
                        unknown_columns.push((*name).to_owned());
                    }
                }
                match parse_row(
                    header,
                    &columns,
                    session_id,
                    package_name,
                    window_id.as_deref(),
                ) {
                    Some(mut frame) => {
                        frame.frame_id = frames.len() as u64;
                        frames.push(frame);
                    }
                    None => malformed_rows += 1,
                }
            }
        }
    }

    if !found_header {
        warnings.push("No gfxinfo framestats header was found.".into());
    }
    if malformed_rows > 0 {
        warnings.push(format!(
            "{malformed_rows} malformed frame row(s) were skipped."
        ));
    }
    if warn_on_empty_frames && frames.is_empty() && found_header {
        warnings.push("The framestats section did not contain usable frames.".into());
    }
    if !unknown_columns.is_empty() {
        warnings.push(format!(
            "Preserved unsupported framestats column(s): {}.",
            unknown_columns.join(", ")
        ));
    }

    let frames = fill_inferred_expected_durations(frames);
    let inferred_intervals: Vec<i64> = frames
        .iter()
        .filter(|frame| frame.expected_duration_source == ExpectedDurationSource::InferredVsync)
        .filter_map(|frame| frame.expected_duration_ns)
        .collect();
    if distinct_near(inferred_intervals).len() > 1 {
        warnings.push(
            "Multiple frame intervals were observed; legacy frame budgets were inferred per frame."
                .into(),
        );
    }
    Ok(GfxInfoParse { frames, warnings })
}

fn parse_row(
    header: &[&str],
    columns: &[&str],
    session_id: &str,
    package_name: Option<&str>,
    window_id: Option<&str>,
) -> Option<FrameSample> {
    let values: BTreeMap<&str, Option<i64>> = header
        .iter()
        .zip(columns)
        .map(|(name, value)| (*name, value.parse::<i64>().ok()))
        .collect();
    let flags = (*values.get("Flags")?)?;
    let number = |key: &str| -> Option<i64> {
        values
            .get(key)
            .copied()
            .flatten()
            .filter(|value| *value > 0)
    };
    let intended_vsync = number("IntendedVsync");
    let actual_vsync = number("Vsync");
    let completed = number("FrameCompleted");
    let present = number("DisplayPresentTime");
    let deadline = number("FrameDeadline");
    let interval = number("FrameInterval");
    let expected = positive_difference(deadline, intended_vsync)
        .map(|duration| (duration, ExpectedDurationSource::PlatformDeadline))
        .or_else(|| interval.map(|duration| (duration, ExpectedDurationSource::FrameInterval)));

    let mut states: BTreeMap<String, String> = BTreeMap::new();
    for name in header
        .iter()
        .filter(|name| !KNOWN_COLUMNS.contains(name) || METADATA_COLUMNS.contains(name))
    {
        if let Some(value) = values.get(name).copied().flatten() {
            states.insert(format!("gfxinfo.column.{name}"), value.to_string());
        }
    }
    if flags != 0 {
        states.insert("gfxinfo.flags".into(), flags.to_string());
    }

    Some(FrameSample {
        frame_id: 0,
        session_id: session_id.to_owned(),
        source: FrameSource::Gfxinfo,
        package_name: package_name.map(str::to_owned),
        process_id: None,
        activity_name: None,
        window_id: window_id.map(str::to_owned),
        intended_vsync_ns: intended_vsync,
        actual_vsync_ns: actual_vsync,
        frame_completed_ns: completed,
        present_ns: present,
        expected_duration_ns: expected.map(|(duration, _)| duration),
        expected_duration_source: expected
            .map_or(ExpectedDurationSource::Unknown, |(_, source)| source),
        refresh_rate_hz: None,
        frame_timeline_vsync_id: number("FrameTimelineVsyncId"),
        total_duration_ns: positive_difference(completed.or(present), intended_vsync),
        stages: FrameStages {
            input_ns: positive_difference(number("AnimationStart"), number("HandleInputStart")),
            animation_ns: positive_difference(
                number("PerformTraversalsStart"),
                number("AnimationStart"),
            ),
            layout_measure_ns: positive_difference(
                number("DrawStart"),
                number("PerformTraversalsStart"),
            ),
            draw_ns: positive_difference(number("SyncQueued"), number("DrawStart")),
            sync_ns: positive_difference(number("IssueDrawCommandsStart"), number("SyncStart")),
            command_issue_ns: positive_difference(
                number("SwapBuffers"),
                number("IssueDrawCommandsStart"),
            ),
            swap_buffers_ns: positive_difference(completed, number("SwapBuffers")),
            gpu_ns: positive_difference(number("GpuCompleted"), number("SwapBuffers")),
        },
        platform_jank: None,
        platform_jank_types: Default::default(),
        platform_jank_rule_id: None,
        platform_jank_rule_version: None,
        states,
        eligible_for_jank: flags == 0,
        dropped_before_sample: 0,
        layout_snapshot_id: None,
        fragment_name: None,
        page_name: None,
        interaction_state: None,
        render_thread_name: None,
        surface_flinger_jank_type: None,
        jank_stats_jank: None,
        jank_stats_reasons: Default::default(),
        jank_stats_rule_id: None,
        jank_stats_rule_version: None,
    })
}

fn fill_inferred_expected_durations(frames: Vec<FrameSample>) -> Vec<FrameSample> {
    let mut inferred = Vec::with_capacity(frames.len());
    for (index, frame) in frames.iter().enumerate() {
        if frame.expected_duration_ns.is_some() {
            inferred.push(frame.clone());
            continue;
        }
        let current = frame.intended_vsync_ns;
        let next = frames
            .get(index + 1)
            .filter(|candidate| candidate.window_id == frame.window_id)
            .and_then(|candidate| candidate.intended_vsync_ns);
        let previous = index
            .checked_sub(1)
            .and_then(|index| frames.get(index))
            .filter(|candidate| candidate.window_id == frame.window_id)
            .and_then(|candidate| candidate.intended_vsync_ns);
        let interval = positive_difference(next, current)
            .filter(|value| valid_refresh_interval(*value))
            .or_else(|| {
                positive_difference(current, previous)
                    .filter(|value| valid_refresh_interval(*value))
            });
        match interval {
            Some(interval) => {
                let mut frame = frame.clone();
                frame.expected_duration_ns = Some(interval);
                frame.expected_duration_source = ExpectedDurationSource::InferredVsync;
                inferred.push(frame);
            }
            None => inferred.push(frame.clone()),
        }
    }
    inferred
}

fn distinct_near(mut values: Vec<i64>) -> Vec<i64> {
    values.sort_unstable();
    let mut groups: Vec<i64> = Vec::new();
    for value in values {
        if !groups.iter().any(|existing| {
            (existing - value).abs() <= existing / REFRESH_INTERVAL_TOLERANCE_DIVISOR
        }) {
            groups.push(value);
        }
    }
    groups
}

fn valid_refresh_interval(value: i64) -> bool {
    (MIN_REFRESH_INTERVAL_NS..=MAX_REFRESH_INTERVAL_NS).contains(&value)
}

fn frame_columns(line: &str, expected_size: usize) -> Vec<&str> {
    let columns: Vec<&str> = line.split(',').map(str::trim).collect();
    if columns.len() > expected_size
        && columns[expected_size..]
            .iter()
            .all(|value| value.is_empty())
    {
        columns[..expected_size].to_vec()
    } else {
        columns
    }
}

fn is_frame_data_row(line: &str) -> bool {
    line.split(',')
        .next()
        .is_some_and(|value| value.parse::<i64>().is_ok())
}

fn positive_difference(end: Option<i64>, start: Option<i64>) -> Option<i64> {
    match (end, start) {
        (Some(end), Some(start)) if end > 0 && start > 0 && end >= start => Some(end - start),
        _ => None,
    }
}

//! Faithful port of Compose `parser-frame/.../GfxInfoFrameStatsParser.kt`.
//!
//! Parses `dumpsys gfxinfo ... framestats` text with repeated `Window:` /
//! `---PROFILEDATA---` sections, modern named columns, independent trailing
//! separators and explicit budget provenance. Unknown and metadata columns are
//! preserved in `FrameSample.states` as `gfxinfo.column.<name>`. Warnings keep
//! the Compose wording.

use crate::MAX_INPUT_BYTES;
use crate::model::{ExpectedDurationSource, FrameSample, FrameSource, FrameStages};
use std::collections::{BTreeMap, BTreeSet};

const MIN_REFRESH_INTERVAL_NS: i64 = 4_000_000;
const MAX_REFRESH_INTERVAL_NS: i64 = 50_000_000;
const REFRESH_INTERVAL_TOLERANCE_DIVISOR: i64 = 20;

// A corruption guard, not a jank threshold. Headerless imports and old sessions
// have no dump clock; even a multi-hour stall is distinct from a years-long fence.
const MAX_FRAME_SPAN_NS: i64 = 24 * 60 * 60 * 1_000_000_000;

/// dumpsys prints device monotonic uptime in ms *before* collecting the rows.
/// Allow collection skew (the live command has a 10 s timeout). Realtime includes
/// suspend time and host wall clock is unrelated; neither is a fence clock.
pub(crate) fn dump_fence_limit(line: &str) -> Option<i64> {
    let ms: i64 = line
        .strip_prefix("Uptime:")?
        .split_whitespace()
        .next()?
        .parse()
        .ok()?;
    (ms > 0)
        .then_some(ms)?
        .checked_mul(1_000_000)?
        .checked_add(10_000_000_000)
}

pub(crate) fn valid_fence(end: Option<i64>, start: Option<i64>, limit: Option<i64>) -> Option<i64> {
    end.filter(|&ns| {
        ns > 0
            && ns < i64::MAX
            && limit.is_none_or(|latest| ns <= latest)
            && start.is_none_or(|vsync| ns.checked_sub(vsync).is_some_and(valid_span))
    })
}

fn valid_span(span: i64) -> bool {
    (0..=MAX_FRAME_SPAN_NS).contains(&span)
}

/// Older gfxinfo sessions and reports cached durations before validating the
/// fences. Repair only the in-memory sample, never reinterpret other sources.
pub fn sanitize_stored_timing(sample: &mut FrameSample) {
    if sample.source != FrameSource::Gfxinfo {
        return;
    }
    let start = sample.intended_vsync_ns.filter(|&ns| ns > 0);
    sample.intended_vsync_ns = start;
    let completed = valid_fence(sample.frame_completed_ns, start, None);
    let present = valid_fence(sample.present_ns, start, None);
    if completed != sample.frame_completed_ns {
        sample.stages.swap_buffers_ns = None;
    }
    if start.is_some() && (sample.frame_completed_ns.is_some() || sample.present_ns.is_some()) {
        sample.total_duration_ns = positive_difference(completed.or(present), start);
    } else {
        sample.total_duration_ns = sample.total_duration_ns.filter(|&ns| valid_span(ns));
    }
    sample.frame_completed_ns = completed;
    sample.present_ns = present;
    sample.stages.swap_buffers_ns = sample.stages.swap_buffers_ns.filter(|&ns| valid_span(ns));
    sample.stages.gpu_ns = sample.stages.gpu_ns.filter(|&ns| valid_span(ns));
}

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
    parse(text, session_id, package_name, warn_on_empty_frames, None)
}

/// Query projection: limit materialized rows and do not copy raw columns into
/// each sample. Timing, inferred budgets and deadline eligibility are unchanged.
pub fn parse_gfxinfo_for_query(
    text: &str,
    session_id: &str,
    max_frames: usize,
) -> Result<GfxInfoParse, String> {
    parse(text, session_id, None, true, Some(max_frames))
}

fn parse(
    text: &str,
    session_id: &str,
    package_name: Option<&str>,
    warn_on_empty_frames: bool,
    query_frame_limit: Option<usize>,
) -> Result<GfxInfoParse, String> {
    if text.len() as u64 > MAX_INPUT_BYTES {
        return Err("FrameStats text exceeds 256 MiB".into());
    }
    let retain_columns = query_frame_limit.is_none();

    let mut frames: Vec<FrameSample> = Vec::new();
    let mut warnings: Vec<String> = Vec::new();
    let mut header: Option<Vec<&str>> = None;
    let mut window_id: Option<String> = None;
    let mut in_profile_data = false;
    let mut found_header = false;
    let mut malformed_rows = 0usize;
    let mut unknown_columns: Vec<&str> = Vec::new();
    let mut seen_unknown_columns = BTreeSet::new();

    let mut fence_limit = None;
    for raw_line in text.lines() {
        let line = raw_line.trim();
        if !in_profile_data && line.starts_with("Uptime:") {
            fence_limit = dump_fence_limit(line);
        } else if let Some(name) = line.strip_prefix("Window:") {
            let name = name.trim();
            if !retain_columns && (name.len() > 512 || name.chars().any(char::is_control)) {
                return Err("FrameStats window label exceeds query input bounds".into());
            }
            window_id = (!name.is_empty()).then(|| name.to_owned());
        } else if line == "---PROFILEDATA---" {
            in_profile_data = !in_profile_data;
            if !in_profile_data {
                header = None;
            }
        } else if in_profile_data && line.starts_with("Flags,") {
            if !retain_columns && line.len() > 16 * 1024 {
                return Err("FrameStats header exceeds query input limit".into());
            }
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
            if !retain_columns && line.len() > 16 * 1024 {
                return Err("FrameStats row exceeds query input limit".into());
            }
            let columns = frame_columns(line, header.len());
            if columns.len() != header.len() {
                malformed_rows += 1;
            } else {
                for name in header.iter().filter(|name| !KNOWN_COLUMNS.contains(name)) {
                    if (retain_columns && seen_unknown_columns.insert(*name))
                        || (!retain_columns && unknown_columns.is_empty())
                    {
                        unknown_columns.push(*name);
                    }
                }
                match parse_row(
                    header,
                    &columns,
                    session_id,
                    package_name,
                    window_id.as_deref(),
                    fence_limit,
                    retain_columns,
                ) {
                    Some(mut frame) => {
                        if query_frame_limit.is_some_and(|limit| frames.len() >= limit) {
                            return Err("FrameStats exceeds the frame query input limit".into());
                        }
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
        warnings.push(if retain_columns {
            format!(
                "Preserved unsupported framestats column(s): {}.",
                unknown_columns.join(", ")
            )
        } else {
            "Unsupported framestats columns were omitted.".into()
        });
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
    fence_limit: Option<i64>,
    retain_columns: bool,
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
    let fence = |key| valid_fence(number(key), intended_vsync, fence_limit);
    let completed = fence("FrameCompleted");
    let present = fence("DisplayPresentTime");
    let deadline = number("FrameDeadline");
    let interval = number("FrameInterval");
    let expected = positive_difference(deadline, intended_vsync)
        .map(|duration| (duration, ExpectedDurationSource::PlatformDeadline))
        .or_else(|| interval.map(|duration| (duration, ExpectedDurationSource::FrameInterval)));

    let mut states: BTreeMap<String, String> = BTreeMap::new();
    if retain_columns {
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
            swap_buffers_ns: fence_stage_duration(completed, number("SwapBuffers")),
            gpu_ns: fence_stage_duration(fence("GpuCompleted"), number("SwapBuffers")),
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

pub(crate) fn fence_stage_duration(end: Option<i64>, start: Option<i64>) -> Option<i64> {
    positive_difference(end, start).filter(|&ns| valid_span(ns))
}

fn positive_difference(end: Option<i64>, start: Option<i64>) -> Option<i64> {
    match (end, start) {
        (Some(end), Some(start)) if end > 0 && start > 0 && end >= start => Some(end - start),
        _ => None,
    }
}

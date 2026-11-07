use crate::{
    AnalyzedFrame, BudgetSource, DeadlineVerdict, FrameStages, MAX_FRAMES, MAX_INPUT_BYTES,
};
use std::collections::{BTreeMap, BTreeSet};

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

pub fn parse(text: &str) -> Result<(Vec<AnalyzedFrame>, Vec<String>), String> {
    if text.len() as u64 > MAX_INPUT_BYTES {
        return Err("FrameStats text exceeds 256 MiB".into());
    }
    let mut frames = Vec::new();
    let mut warnings = Vec::new();
    let mut unknown = BTreeSet::new();
    let mut header: Option<Vec<&str>> = None;
    let mut window = None;
    let mut in_profile = false;
    let mut found_header = false;
    let mut malformed = 0usize;
    for raw in text.lines() {
        if raw.len() > 64 * 1024 {
            return Err("FrameStats line exceeds 64 KiB".into());
        }
        let line = raw.trim();
        if let Some(name) = line.strip_prefix("Window:") {
            window = Some(name.trim().to_owned()).filter(|value| !value.is_empty());
        } else if line == "---PROFILEDATA---" {
            in_profile = !in_profile;
            if !in_profile {
                header = None;
            }
        } else if in_profile && line.starts_with("Flags,") {
            let mut columns: Vec<_> = line.split(',').map(str::trim).collect();
            while columns.last() == Some(&"") {
                columns.pop();
            }
            if columns.len() > 256 || columns.iter().any(|column| column.is_empty()) {
                return Err("Invalid FrameStats column header".into());
            }
            let unique: BTreeSet<_> = columns.iter().copied().collect();
            if unique.len() != columns.len() || !columns.contains(&"IntendedVsync") {
                return Err("Duplicate or incomplete FrameStats columns".into());
            }
            unknown.extend(
                columns
                    .iter()
                    .filter(|column| !KNOWN_COLUMNS.contains(column))
                    .map(|value| (*value).to_owned()),
            );
            header = Some(columns);
            found_header = true;
        } else if in_profile && let Some(header) = &header {
            if line.is_empty() {
                continue;
            }
            let mut values: Vec<_> = line.split(',').map(str::trim).collect();
            while values.len() > header.len() && values.last() == Some(&"") {
                values.pop();
            }
            if values.len() != header.len() {
                malformed += 1;
                continue;
            }
            match parse_row(header, &values, frames.len() as u64, window.clone()) {
                Some(frame) => frames.push(frame),
                None => malformed += 1,
            }
            if frames.len() > MAX_FRAMES {
                return Err("FrameStats exceeds one million frame budget".into());
            }
        }
    }
    if !found_header {
        return Err("No gfxinfo FrameStats PROFILEDATA header found".into());
    }
    if in_profile {
        warnings.push("FrameStats PROFILEDATA block is not closed; input may be truncated.".into());
    }
    if malformed > 0 {
        warnings.push(format!("{malformed} malformed frame row(s) were skipped."));
    }
    if frames.is_empty() {
        warnings.push("FrameStats contains no usable frames.".into());
    }
    if !unknown.is_empty() {
        warnings.push(format!(
            "Preserved unsupported columns: {}.",
            unknown.into_iter().collect::<Vec<_>>().join(", ")
        ));
    }
    infer_budgets(&mut frames);
    let inferred: BTreeSet<_> = frames
        .iter()
        .filter(|frame| frame.budget_source == BudgetSource::InferredVsync)
        .filter_map(|frame| frame.budget_ns)
        .collect();
    if inferred.len() > 1 {
        warnings.push(
            "Legacy frame budgets were inferred per frame; observed intervals differ.".into(),
        );
    }
    for frame in &mut frames {
        classify(frame);
    }
    Ok((frames, warnings))
}

fn parse_row(
    header: &[&str],
    values: &[&str],
    id: u64,
    window: Option<String>,
) -> Option<AnalyzedFrame> {
    let columns: BTreeMap<_, _> = header
        .iter()
        .zip(values)
        .map(|(key, value)| ((*key).to_owned(), (*value).to_owned()))
        .collect();
    let flags = columns.get("Flags")?.parse().ok()?;
    let number = |key: &str| {
        columns
            .get(key)?
            .parse::<i64>()
            .ok()
            .filter(|value| *value > 0)
            .map(|value| value as u64)
    };
    let start = number("IntendedVsync");
    let completed = number("FrameCompleted");
    let present = number("DisplayPresentTime");
    let deadline_budget = difference(number("FrameDeadline"), start).filter(|value| *value > 0);
    let (budget_ns, budget_source) = if let Some(budget) = deadline_budget {
        (Some(budget), BudgetSource::PlatformDeadline)
    } else if let Some(interval) = number("FrameInterval") {
        (Some(interval), BudgetSource::FrameInterval)
    } else {
        (None, BudgetSource::Unknown)
    };
    let stages = FrameStages {
        input_ns: difference(number("AnimationStart"), number("HandleInputStart")),
        animation_ns: difference(number("PerformTraversalsStart"), number("AnimationStart")),
        layout_measure_ns: difference(number("DrawStart"), number("PerformTraversalsStart")),
        draw_ns: difference(number("SyncQueued"), number("DrawStart")),
        sync_ns: difference(number("IssueDrawCommandsStart"), number("SyncStart")),
        command_issue_ns: difference(number("SwapBuffers"), number("IssueDrawCommandsStart")),
        swap_buffers_ns: difference(completed, number("SwapBuffers")),
        gpu_ns: difference(number("GpuCompleted"), number("SwapBuffers")),
    };
    Some(AnalyzedFrame {
        id,
        window,
        intended_vsync_ns: start,
        actual_vsync_ns: number("Vsync"),
        completed_ns: completed,
        present_ns: present,
        vsync_id: number("FrameTimelineVsyncId"),
        duration_ns: difference(completed.or(present), start),
        budget_ns,
        budget_source,
        flags,
        deadline: DeadlineVerdict::Unknown,
        missed_vsync_count: None,
        platform_jank: None,
        stages,
        columns,
    })
}

fn difference(end: Option<u64>, start: Option<u64>) -> Option<u64> {
    end?.checked_sub(start?)
}

fn infer_budgets(frames: &mut [AnalyzedFrame]) {
    for index in 0..frames.len() {
        if frames[index].budget_ns.is_some() {
            continue;
        }
        let current = &frames[index];
        let next = frames
            .get(index + 1)
            .filter(|frame| frame.window == current.window)
            .and_then(|frame| frame.intended_vsync_ns);
        let previous = index
            .checked_sub(1)
            .and_then(|index| frames.get(index))
            .filter(|frame| frame.window == current.window)
            .and_then(|frame| frame.intended_vsync_ns);
        let budget = difference(next, current.intended_vsync_ns)
            .filter(|value| (4_000_000..=50_000_000).contains(value))
            .or_else(|| {
                difference(current.intended_vsync_ns, previous)
                    .filter(|value| (4_000_000..=50_000_000).contains(value))
            });
        if let Some(budget) = budget {
            frames[index].budget_ns = Some(budget);
            frames[index].budget_source = BudgetSource::InferredVsync;
        }
    }
}

fn classify(frame: &mut AnalyzedFrame) {
    if frame.flags != 0 {
        return;
    }
    if let (Some(duration), Some(budget)) = (frame.duration_ns, frame.budget_ns) {
        frame.deadline = if duration > budget {
            DeadlineVerdict::Missed
        } else {
            DeadlineVerdict::Met
        };
        frame.missed_vsync_count = Some(duration.div_ceil(budget).saturating_sub(1));
    }
}

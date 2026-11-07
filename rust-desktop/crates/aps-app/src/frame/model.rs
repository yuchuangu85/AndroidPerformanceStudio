//! Frame Analyzer presentation model helpers shared by the runtime and page.
//!
//! Mirrors Compose FrameProfilerScreen/FrameAnalysisFilter/FrameEvidenceText/
//! FrameTimelineAxes/FrameTimelineWindow where the value is UI-facing: evidence
//! strings, filter labels, sparkline series and the timeline viewport math.
//! Localization happens at render time through frame.json TextKeys.

use aps_frame::analysis::{AnalyzedFrame, DeadlineMissCluster};
use aps_frame::model::FrameStages;
use aps_frame::{
    ExpectedDurationSource, FrameAnalysisResult, FrameDeadlineVerdict, FrameSample, FrameSource,
    FrameTimelineStatus, FrameViewFilter, JankSeverity, JankType,
};
use aps_ui_foundation::{Language, TextKey};

pub(in crate::frame) use aps_frame::{
    FRAME_TIMELINE_JANK_TAG, filter_analysis, format_millis, frame_timeline_status,
    is_problem_frame,
};

const NANOS_PER_MILLISECOND: f64 = 1_000_000.0;
const AXIS_INTERVALS: usize = 4;

/// UI-level operation status shown in the header and status bar.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(in crate::frame) enum FrameOperationStatus {
    Capturing {
        package_name: String,
        source: String,
        frame_count: Option<usize>,
    },
    CaptureStopped {
        frame_count: usize,
    },
    ImportedFrames {
        frame_count: usize,
    },
    Exported {
        file_name: String,
    },
}

impl FrameOperationStatus {
    pub(in crate::frame) fn localized(&self, language: Language) -> String {
        match self {
            Self::Capturing {
                package_name,
                source,
                frame_count: None,
            } => language.format(
                TextKey::FrameCapturingVia,
                &[("value1", package_name), ("value2", source)],
            ),
            Self::Capturing {
                package_name,
                source,
                frame_count: Some(count),
            } => language.format(
                TextKey::FrameCapturingFrameCount,
                &[
                    ("value1", package_name),
                    ("value2", source),
                    ("value3", count),
                ],
            ),
            Self::CaptureStopped { frame_count: 0 } => language
                .text(TextKey::FrameCaptureStoppedWithoutFrames)
                .to_owned(),
            Self::CaptureStopped { frame_count } => language.format(
                TextKey::FrameCaptureStoppedWithFrames,
                &[("value1", frame_count)],
            ),
            Self::ImportedFrames { frame_count } => {
                language.format(TextKey::FrameImportedFrames, &[("value1", frame_count)])
            }
            Self::Exported { file_name } => {
                language.format(TextKey::FrameExported, &[("value1", file_name)])
            }
        }
    }
}

// ---------------- selection and navigation ----------------

pub(in crate::frame) fn selected_frame<'a>(
    analysis: &'a FrameAnalysisResult,
    selected_frame_id: Option<u64>,
) -> Option<&'a AnalyzedFrame> {
    selected_frame_id
        .and_then(|id| {
            analysis
                .frames
                .iter()
                .find(|frame| frame.sample.frame_id == id)
        })
        .or_else(|| analysis.frames.first())
}

pub(in crate::frame) fn next_frame_id(
    analysis: &FrameAnalysisResult,
    selected_frame_id: Option<u64>,
) -> Option<u64> {
    let index = analysis
        .frames
        .iter()
        .position(|frame| Some(frame.sample.frame_id) == selected_frame_id)?;
    analysis
        .frames
        .get(index + 1)
        .map(|frame| frame.sample.frame_id)
}

pub(in crate::frame) fn previous_frame_id(
    analysis: &FrameAnalysisResult,
    selected_frame_id: Option<u64>,
) -> Option<u64> {
    let index = analysis
        .frames
        .iter()
        .position(|frame| Some(frame.sample.frame_id) == selected_frame_id)?;
    index
        .checked_sub(1)
        .and_then(|index| analysis.frames.get(index))
        .map(|frame| frame.sample.frame_id)
}

/// Navigate to the first frame of the next deadline-miss cluster.
pub(in crate::frame) fn next_cluster_frame_id(
    analysis: &FrameAnalysisResult,
    selected_frame_id: Option<u64>,
) -> Option<u64> {
    let current = selected_frame_id.unwrap_or_default();
    analysis
        .clusters
        .iter()
        .find(|cluster| cluster.first_frame_id > current)
        .map(|cluster| cluster.first_frame_id)
}

pub(in crate::frame) fn previous_cluster_frame_id(
    analysis: &FrameAnalysisResult,
    selected_frame_id: Option<u64>,
) -> Option<u64> {
    let current = selected_frame_id.unwrap_or(u64::MAX);
    analysis
        .clusters
        .iter()
        .rev()
        .find(|cluster| cluster.first_frame_id < current)
        .map(|cluster| cluster.first_frame_id)
}

/// Compose clusterTargetFrameId: the first cluster miss that still exists in
/// the visible analysis as a MISSED frame.
pub(in crate::frame) fn cluster_target_frame_id(
    analysis: &FrameAnalysisResult,
    cluster: &DeadlineMissCluster,
) -> Option<u64> {
    cluster.deadline_miss_frame_ids.iter().copied().find(|id| {
        analysis.frames.iter().any(|frame| {
            frame.sample.frame_id == *id && frame.deadline_verdict == FrameDeadlineVerdict::Missed
        })
    })
}

pub(in crate::frame) fn problem_frames(analysis: &FrameAnalysisResult) -> Vec<&AnalyzedFrame> {
    analysis
        .frames
        .iter()
        .filter(|frame| is_problem_frame(frame))
        .collect()
}

// ---------------- labels ----------------

pub(in crate::frame) fn source_label(source: FrameSource) -> TextKey {
    match source {
        FrameSource::JankStats => TextKey::FrameSourceJankStats,
        FrameSource::FrameMetrics => TextKey::FrameSourceFrameMetrics,
        FrameSource::Gfxinfo => TextKey::FrameSourceGfxinfo,
        FrameSource::Perfetto => TextKey::FrameSourcePerfetto,
    }
}

pub(in crate::frame) fn source_display(language: Language, source: FrameSource) -> String {
    language.text(source_label(source)).to_owned()
}

/// Sources present in the unfiltered analysis, in first-seen order.
pub(in crate::frame) fn available_sources(analysis: &FrameAnalysisResult) -> Vec<FrameSource> {
    let mut sources = Vec::new();
    for frame in &analysis.frames {
        if !sources.contains(&frame.sample.source) {
            sources.push(frame.sample.source);
        }
    }
    sources
}

pub(in crate::frame) fn view_filter_label(filter: FrameViewFilter) -> TextKey {
    match filter {
        FrameViewFilter::All => TextKey::FrameAllFrames,
        FrameViewFilter::JankOnly => TextKey::FrameJankOnly,
        FrameViewFilter::DeadlineMisses => TextKey::FrameDeadlineMisses,
    }
}

pub(in crate::frame) const VIEW_FILTERS: [FrameViewFilter; 3] = [
    FrameViewFilter::All,
    FrameViewFilter::JankOnly,
    FrameViewFilter::DeadlineMisses,
];

pub(in crate::frame) fn timeline_status_label(status: FrameTimelineStatus) -> TextKey {
    match status {
        FrameTimelineStatus::Good => TextKey::FrameTimelineNormal,
        FrameTimelineStatus::HighLatency => TextKey::FrameTimelineHighLatency,
        FrameTimelineStatus::SelfJank => TextKey::FrameTimelineJank,
        FrameTimelineStatus::OtherJank => TextKey::FrameTimelineOtherJank,
        FrameTimelineStatus::Dropped => TextKey::FrameTimelineDropped,
        FrameTimelineStatus::Unknown => TextKey::FrameTimelineUnknown,
    }
}

pub(in crate::frame) const TIMELINE_LEGEND: [FrameTimelineStatus; 6] = [
    FrameTimelineStatus::Good,
    FrameTimelineStatus::HighLatency,
    FrameTimelineStatus::SelfJank,
    FrameTimelineStatus::OtherJank,
    FrameTimelineStatus::Dropped,
    FrameTimelineStatus::Unknown,
];

pub(in crate::frame) fn jank_type_display(jank_type: JankType, language: Language) -> String {
    language
        .text(match jank_type {
            JankType::PlatformReported => TextKey::FrameJankTypePlatformReported,
            JankType::DeadlineMissed => TextKey::FrameJankTypeDeadlineMissed,
            JankType::SlowInput => TextKey::FrameJankTypeSlowInput,
            JankType::SlowAnimation => TextKey::FrameJankTypeSlowAnimation,
            JankType::SlowLayout => TextKey::FrameJankTypeSlowLayout,
            JankType::SlowDraw => TextKey::FrameJankTypeSlowDraw,
            JankType::SlowSync => TextKey::FrameJankTypeSlowSync,
            JankType::SlowCommand => TextKey::FrameJankTypeSlowCommand,
            JankType::SlowSwap => TextKey::FrameJankTypeSlowSwap,
            JankType::Unknown => TextKey::FrameJankTypeUnknown,
        })
        .to_owned()
}

pub(in crate::frame) fn capture_source_label(source: FrameSource) -> &'static str {
    match source {
        FrameSource::FrameMetrics => "FrameMetrics Agent",
        FrameSource::Gfxinfo => "gfxinfo",
        FrameSource::JankStats => "JankStats Agent",
        FrameSource::Perfetto => "Perfetto",
    }
}

// ---------------- metrics ----------------

/// Compose formatMillis for frame durations.
pub(in crate::frame) fn format_duration(duration_ns: Option<i64>) -> String {
    format_millis(duration_ns)
}

/// The declared expected frame duration; an untrusted number stays unavailable.
pub(in crate::frame) fn display_budget(sample: &FrameSample) -> String {
    format_millis(sample.known_expected_duration_ns())
}

pub(in crate::frame) fn percent_text(rate: Option<f64>) -> String {
    match rate {
        Some(rate) if rate.is_finite() => format!("{:.1}%", rate * 100.0),
        _ => "—".to_owned(),
    }
}

pub(in crate::frame) fn refresh_rate_text(
    analysis: Option<&FrameAnalysisResult>,
    observed_refresh_rates_hz: &[f64],
) -> String {
    let mut rates: Vec<f64> = analysis
        .map(|analysis| {
            analysis
                .frames
                .iter()
                .filter_map(|frame| frame.sample.refresh_rate_hz)
                .filter(|rate| *rate > 0.0)
                .collect::<Vec<_>>()
        })
        .unwrap_or_default();
    if rates.is_empty() {
        rates = observed_refresh_rates_hz
            .iter()
            .copied()
            .filter(|rate| *rate > 0.0)
            .collect();
    }
    rates.sort_by(|left, right| left.total_cmp(right));
    rates.dedup_by(|left, right| (*left - *right).abs() < f64::EPSILON);
    match rates.as_slice() {
        [] => "—".to_owned(),
        [rate] => format!("{rate:.0} Hz"),
        [first, .., last] => format!("{first:.0}–{last:.0} Hz"),
    }
}

pub(in crate::frame) fn duration_series(
    analysis: Option<&FrameAnalysisResult>,
) -> Vec<Option<f64>> {
    analysis
        .map(|analysis| {
            analysis
                .frames
                .iter()
                .map(|frame| {
                    frame
                        .sample
                        .resolved_duration_ns()
                        .map(|value| value as f64)
                })
                .collect()
        })
        .unwrap_or_default()
}

pub(in crate::frame) fn deadline_series(
    analysis: Option<&FrameAnalysisResult>,
) -> Vec<Option<f64>> {
    analysis
        .map(|analysis| {
            analysis
                .frames
                .iter()
                .map(|frame| match frame.deadline_verdict {
                    FrameDeadlineVerdict::Met => Some(0.0),
                    FrameDeadlineVerdict::Missed => Some(1.0),
                    FrameDeadlineVerdict::Unknown => None,
                })
                .collect()
        })
        .unwrap_or_default()
}

pub(in crate::frame) fn attribution_entry(
    attribution: &aps_frame::FrameAttribution,
    language: Language,
) -> String {
    let p95 = format_millis(attribution.p95_duration_ns);
    language.format(
        TextKey::FrameAttributionEntry,
        &[
            ("value1", &attribution.activity_name),
            ("value2", &attribution.ui_state),
            ("value3", &attribution.platform_jank_frames),
            ("value4", &attribution.total_frames),
            ("value5", &p95),
        ],
    )
}

// ---------------- problem frame evidence ----------------

pub(in crate::frame) fn problem_signal(frame: &AnalyzedFrame, language: Language) -> String {
    let mut signals = Vec::new();
    if frame.deadline_verdict == FrameDeadlineVerdict::Missed {
        signals.push(language.text(TextKey::FrameDeadlineMissSignal).to_owned());
    }
    if frame.sample.platform_jank == Some(true) || !frame.platform_jank_types.is_empty() {
        signals.push(language.text(TextKey::FramePlatformJank).to_owned());
    }
    if frame.sample.jank_stats_jank == Some(true) || !frame.sample.jank_stats_reasons.is_empty() {
        signals.push(language.text(TextKey::FrameJankStats).to_owned());
    }
    if signals.is_empty() {
        "—".to_owned()
    } else {
        signals.join(" · ")
    }
}

fn label_with_value(language: Language, key: TextKey, value: impl std::fmt::Display) -> String {
    format!("{}: {value}", language.text(key))
}

pub(in crate::frame) fn problem_evidence(frame: &AnalyzedFrame, language: Language) -> String {
    let mut parts = vec![problem_signal(frame, language)];
    if let Some(duration) = frame.sample.resolved_duration_ns() {
        parts.push(label_with_value(
            language,
            TextKey::FrameDuration,
            format_millis(Some(duration)),
        ));
    }
    if let Some(budget) = frame.sample.known_expected_duration_ns() {
        parts.push(label_with_value(
            language,
            TextKey::FrameBudget,
            format_millis(Some(budget)),
        ));
        if let Some(duration) = frame.sample.resolved_duration_ns() {
            if duration > budget {
                parts.push(language.format(
                    TextKey::FrameOverBudgetBy,
                    &[("value1", &format_millis(Some(duration - budget)))],
                ));
            }
        }
    }
    if let Some(missed) = frame.missed_vsync_count {
        if missed > 0 {
            parts.push(label_with_value(
                language,
                TextKey::FrameMissedVsync,
                missed,
            ));
        }
    }
    if !frame.platform_jank_types.is_empty() {
        let names: Vec<String> = frame
            .platform_jank_types
            .iter()
            .map(|jank_type| jank_type_display(*jank_type, language))
            .collect();
        parts.push(label_with_value(
            language,
            TextKey::FrameJankTypes,
            names.join(", "),
        ));
    }
    if !frame.sample.jank_stats_reasons.is_empty() {
        let reasons: Vec<&str> = frame
            .sample
            .jank_stats_reasons
            .iter()
            .map(String::as_str)
            .collect();
        parts.push(label_with_value(
            language,
            TextKey::FrameJankStatsReasons,
            reasons.join(", "),
        ));
    }
    parts.join(" · ")
}

pub(in crate::frame) fn escape_evidence_field(value: &str) -> String {
    let mut escaped = String::with_capacity(value.len());
    for character in value.chars() {
        match character {
            '\\' => escaped.push_str("\\\\"),
            '\n' => escaped.push_str("\\n"),
            '\r' => escaped.push_str("\\r"),
            '\t' => escaped.push_str("\\t"),
            character if character.is_control() => {
                escaped.push_str(&format!("\\u{:04x}", character as u32));
            }
            character => escaped.push(character),
        }
    }
    escaped
}

/// Port of Compose FrameEvidenceText.copyEvidenceText.
pub(in crate::frame) fn copy_evidence_text(frame: &AnalyzedFrame, language: Language) -> String {
    let sample = &frame.sample;
    let mut lines = Vec::new();
    let mut add = |label: &str, value: String| {
        lines.push(format!("{label}: {}", escape_evidence_field(&value)));
    };
    add(
        language.text(TextKey::FrameSessionId),
        sample.session_id.clone(),
    );
    add(
        language.text(TextKey::FrameSource),
        sample.source.as_name().to_owned(),
    );
    add(
        language.text(TextKey::FrameFrame),
        format!("#{}", sample.frame_id),
    );
    add(
        language.text(TextKey::FrameTimelineVsyncId),
        sample
            .frame_timeline_vsync_id
            .map(|value| value.to_string())
            .unwrap_or_else(|| "—".to_owned()),
    );
    add(
        language.text(TextKey::FrameDuration),
        format_millis(sample.resolved_duration_ns()),
    );
    add(language.text(TextKey::FrameBudget), display_budget(sample));
    add(
        language.text(TextKey::FrameBudgetSource),
        sample.expected_duration_source.as_name().to_owned(),
    );
    add(
        language.text(TextKey::FrameVerdict),
        frame.deadline_verdict.as_name().to_owned(),
    );
    add(
        language.text(TextKey::FrameMissedVsync),
        frame
            .missed_vsync_count
            .map(|value| value.to_string())
            .unwrap_or_else(|| "—".to_owned()),
    );
    add(
        language.text(TextKey::FramePlatformJank),
        sample
            .platform_jank
            .map(|value| value.to_string())
            .unwrap_or_else(|| "—".to_owned()),
    );
    let jank_types: Vec<&str> = frame
        .platform_jank_types
        .iter()
        .map(JankType::as_name)
        .collect();
    add(
        language.text(TextKey::FrameJankTypes),
        if jank_types.is_empty() {
            "—".to_owned()
        } else {
            jank_types.join(", ")
        },
    );
    add(
        language.text(TextKey::FrameLargestReportedStage),
        frame
            .largest_reported_stage
            .clone()
            .unwrap_or_else(|| "—".to_owned()),
    );

    lines.push(format!("{}:", language.text(TextKey::FrameStageBreakdown)));
    let stages = sample.stages.values();
    if stages.is_empty() {
        lines.push("  —".to_owned());
    } else {
        for (name, duration) in stages {
            lines.push(format!(
                "  {}: {}",
                escape_evidence_field(name),
                format_millis(Some(duration))
            ));
        }
    }

    lines.push(format!("{}:", language.text(TextKey::FrameStateLabels)));
    if sample.states.is_empty() {
        lines.push("  —".to_owned());
    } else {
        for (key, value) in &sample.states {
            lines.push(format!(
                "  {}: {}",
                language.format(
                    TextKey::FrameStateDetail,
                    &[("value1", &escape_evidence_field(key))]
                ),
                escape_evidence_field(value)
            ));
        }
    }
    lines.join("\n")
}

// ---------------- timeline viewport ----------------

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(in crate::frame) struct FrameTimelineWindow {
    pub(in crate::frame) start_index: usize,
    pub(in crate::frame) count: usize,
}

impl FrameTimelineWindow {
    pub(in crate::frame) fn end_exclusive(&self) -> usize {
        self.start_index + self.count
    }

    /// Compose indexAt: the frame index under a horizontal canvas position.
    pub(in crate::frame) fn index_at(&self, x: f32, width: f32) -> Option<usize> {
        if self.count == 0 || width <= 0.0 || !x.is_finite() {
            return None;
        }
        let slot = (x / width * self.count as f32).floor();
        if !slot.is_finite() {
            return None;
        }
        let slot = (slot.max(0.0) as usize).min(self.count - 1);
        Some(self.start_index + slot)
    }
}

pub(in crate::frame) fn frame_timeline_window(
    total_frames: usize,
    requested_start: usize,
    requested_count: usize,
) -> FrameTimelineWindow {
    if total_frames == 0 {
        return FrameTimelineWindow {
            start_index: 0,
            count: 0,
        };
    }
    let count = requested_count.clamp(1, total_frames);
    FrameTimelineWindow {
        start_index: requested_start.min(total_frames - count),
        count,
    }
}

pub(in crate::frame) fn centered_frame_timeline_window(
    total_frames: usize,
    selected_index: usize,
    requested_count: usize,
) -> FrameTimelineWindow {
    frame_timeline_window(
        total_frames,
        selected_index.saturating_sub(requested_count / 2),
        requested_count,
    )
}

/// A duration ruler, not an assumed frame budget: every visible duration fits
/// below its upper bound (Compose frameTimelineDurationTicks).
pub(in crate::frame) fn duration_ticks(maximum_duration_ns: i64) -> Vec<f64> {
    let maximum_ms = (maximum_duration_ns.max(0) as f64 / NANOS_PER_MILLISECOND).max(1.0);
    let upper_bound_ms = 2f64.powf(maximum_ms.log2().ceil());
    (0..=AXIS_INTERVALS)
        .map(|index| upper_bound_ms * index as f64 / AXIS_INTERVALS as f64)
        .collect()
}

pub(in crate::frame) fn axis_indices(window: FrameTimelineWindow) -> Vec<usize> {
    if window.count == 0 {
        return Vec::new();
    }
    let mut indices = Vec::with_capacity(AXIS_INTERVALS + 1);
    for step in 0..=AXIS_INTERVALS {
        let index = window.start_index + ((window.count - 1) * step / AXIS_INTERVALS);
        if indices.last().copied() != Some(index) {
            indices.push(index);
        }
    }
    indices
}

pub(in crate::frame) fn format_tick(value_ms: f64) -> String {
    let mut text = format!("{value_ms:.2}");
    while text.contains('.') && (text.ends_with('0') || text.ends_with('.')) {
        text.pop();
    }
    text
}

// ---------------- stage helpers ----------------

pub(in crate::frame) fn frame_stage_values(sample: &FrameSample) -> Vec<(&'static str, i64)> {
    sample.stages.values()
}

pub(in crate::frame) fn maximum_stage_ns(stages: &FrameStages) -> i64 {
    stages
        .values()
        .into_iter()
        .map(|(_, duration)| duration)
        .max()
        .unwrap_or(1)
        .max(1)
}

pub(in crate::frame) fn filtered(
    analysis: &FrameAnalysisResult,
    source: Option<FrameSource>,
    filter: FrameViewFilter,
) -> FrameAnalysisResult {
    filter_analysis(analysis, source, filter)
}

pub(in crate::frame) fn severity_enum_name(severity: JankSeverity) -> &'static str {
    severity.as_name()
}

pub(in crate::frame) fn expected_duration_source_enum_name(
    source: ExpectedDurationSource,
) -> &'static str {
    source.as_name()
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_frame::analysis::{AnalyzedFrame, DeadlineMissCluster, FrameSummary};
    use aps_frame::model::FrameStages;
    use std::collections::{BTreeMap, BTreeSet};

    fn sample(frame_id: u64) -> FrameSample {
        FrameSample {
            frame_id,
            session_id: "session-1".into(),
            source: FrameSource::Perfetto,
            package_name: Some("com.example.gallery".into()),
            process_id: Some(42),
            activity_name: Some("com.example.gallery.GalleryActivity".into()),
            window_id: Some("SurfaceView - GalleryActivity".into()),
            intended_vsync_ns: Some(frame_id as i64 * 20_000_000),
            actual_vsync_ns: Some(frame_id as i64 * 20_000_000),
            frame_completed_ns: None,
            present_ns: None,
            expected_duration_ns: None,
            expected_duration_source: aps_frame::ExpectedDurationSource::Unknown,
            refresh_rate_hz: None,
            frame_timeline_vsync_id: None,
            total_duration_ns: None,
            stages: FrameStages::default(),
            platform_jank: Some(false),
            platform_jank_types: BTreeSet::new(),
            platform_jank_rule_id: None,
            platform_jank_rule_version: None,
            states: BTreeMap::new(),
            eligible_for_jank: true,
            dropped_before_sample: 0,
            layout_snapshot_id: None,
            fragment_name: None,
            page_name: None,
            interaction_state: None,
            render_thread_name: None,
            surface_flinger_jank_type: None,
            jank_stats_jank: Some(false),
            jank_stats_reasons: BTreeSet::new(),
            jank_stats_rule_id: None,
            jank_stats_rule_version: None,
        }
    }

    fn missed_sample(frame_id: u64, total_duration_ns: Option<i64>) -> FrameSample {
        FrameSample {
            total_duration_ns,
            expected_duration_ns: Some(16_666_667),
            expected_duration_source: aps_frame::ExpectedDurationSource::FrameInterval,
            ..sample(frame_id)
        }
    }

    fn analyzed(sample: FrameSample, missed_vsync_count: Option<u32>) -> AnalyzedFrame {
        AnalyzedFrame {
            sample,
            deadline_verdict: FrameDeadlineVerdict::Missed,
            severity: JankSeverity::Major,
            missed_vsync_count,
            platform_jank_types: BTreeSet::new(),
            largest_reported_stage: Some("Draw".into()),
        }
    }

    fn analysis(
        frames: Vec<AnalyzedFrame>,
        clusters: Vec<DeadlineMissCluster>,
    ) -> FrameAnalysisResult {
        FrameAnalysisResult {
            summary: FrameSummary {
                total_frames: frames.len(),
                ..FrameSummary::default()
            },
            frames,
            clusters,
            attributions: Vec::new(),
        }
    }

    #[::core::prelude::v1::test]
    fn operation_status_localizes_all_variants() {
        let language = Language::English;
        assert!(
            FrameOperationStatus::Capturing {
                package_name: "app".into(),
                source: "gfxinfo".into(),
                frame_count: None
            }
            .localized(language)
            .contains("app")
        );
        assert_eq!(
            FrameOperationStatus::CaptureStopped { frame_count: 0 }.localized(language),
            "Capture stopped without receiving frames."
        );
        assert_eq!(
            FrameOperationStatus::CaptureStopped { frame_count: 2 }.localized(language),
            "Capture stopped: 2 frames."
        );
        assert_eq!(
            FrameOperationStatus::Exported {
                file_name: "a.csv".into()
            }
            .localized(language),
            "Exported a.csv."
        );
        assert!(
            FrameOperationStatus::CaptureStopped { frame_count: 2 }
                .localized(Language::SimplifiedChinese)
                .contains("采集")
        );
    }

    #[::core::prelude::v1::test]
    fn timeline_window_clamps_and_maps_indices_like_compose() {
        let empty = frame_timeline_window(0, 10, 500);
        assert_eq!(empty.start_index, 0);
        assert_eq!(empty.count, 0);
        assert_eq!(empty.index_at(10.0, 100.0), None);

        let window = frame_timeline_window(1_500, 500, 500);
        assert_eq!(window.start_index, 500);
        assert_eq!(window.count, 500);
        assert_eq!(window.end_exclusive(), 1_000);
        assert_eq!(window.index_at(0.0, 500.0), Some(500));
        assert_eq!(window.index_at(499.9, 500.0), Some(999));
        assert_eq!(window.index_at(500.0, 500.0), Some(999));

        let clamped = frame_timeline_window(1_500, 1_200, 500);
        assert_eq!(clamped.start_index, 1_000);
        let oversized = frame_timeline_window(72, 0, 10_000);
        assert_eq!(oversized.count, 72);
        assert_eq!(
            centered_frame_timeline_window(10_001, 9_500, 500).start_index,
            9_250
        );
    }

    #[::core::prelude::v1::test]
    fn duration_ticks_and_frame_axis_match_compose() {
        let ticks = duration_ticks(34_000_000);
        assert_eq!(
            ticks
                .iter()
                .map(|tick| format_tick(*tick))
                .collect::<Vec<_>>(),
            vec!["0", "16", "32", "48", "64"]
        );
        let ms_ticks = duration_ticks(8_000_000);
        assert_eq!(
            ms_ticks
                .iter()
                .map(|tick| format_tick(*tick))
                .collect::<Vec<_>>(),
            vec!["0", "2", "4", "6", "8"]
        );
        assert_eq!(format_tick(0.0), "0");
        assert_eq!(format_tick(2.5), "2.5");
        assert_eq!(
            axis_indices(frame_timeline_window(1_500, 500, 500)),
            vec![500, 624, 749, 874, 999]
        );
        assert_eq!(
            axis_indices(frame_timeline_window(1_500, 625, 250)),
            vec![625, 687, 749, 811, 874]
        );
        assert!(axis_indices(frame_timeline_window(0, 0, 0)).is_empty());
    }

    #[::core::prelude::v1::test]
    fn timeline_status_uses_tags_rules_and_deadline_fallbacks() {
        let mut tagged = sample(1);
        tagged
            .states
            .insert(FRAME_TIMELINE_JANK_TAG.into(), "Self Jank".into());
        assert_eq!(
            frame_timeline_status(&tagged),
            FrameTimelineStatus::SelfJank
        );

        let mut good_tag = sample(2);
        good_tag
            .states
            .insert(FRAME_TIMELINE_JANK_TAG.into(), "No Jank".into());
        assert_eq!(frame_timeline_status(&good_tag), FrameTimelineStatus::Good);

        let mut rule = sample(3);
        rule.platform_jank_rule_id = Some("Buffer Stuffing".into());
        assert_eq!(
            frame_timeline_status(&rule),
            FrameTimelineStatus::HighLatency
        );

        let mut clean = sample(4);
        clean.source = FrameSource::Gfxinfo;
        clean.platform_jank = Some(false);
        assert_eq!(frame_timeline_status(&clean), FrameTimelineStatus::Good);

        let mut unknown = sample(5);
        unknown.platform_jank = None;
        unknown.jank_stats_jank = None;
        assert_eq!(
            frame_timeline_status(&unknown),
            FrameTimelineStatus::Unknown
        );

        let mut meeting = sample(6);
        meeting.source = FrameSource::Gfxinfo;
        meeting.platform_jank = None;
        meeting.jank_stats_jank = None;
        meeting.total_duration_ns = Some(8_000_000);
        meeting.expected_duration_ns = Some(16_666_667);
        meeting.expected_duration_source = aps_frame::ExpectedDurationSource::RefreshRate;
        assert_eq!(frame_timeline_status(&meeting), FrameTimelineStatus::Good);
    }

    #[::core::prelude::v1::test]
    fn problem_signal_and_evidence_match_compose_wording() {
        let frame = analyzed(missed_sample(2, Some(24_000_000)), Some(1));
        let language = Language::English;
        assert_eq!(problem_signal(&frame, language), "Deadline Miss");
        assert_eq!(
            problem_evidence(&frame, language),
            "Deadline Miss · Duration: 24.00 ms · Budget: 16.67 ms · Over budget by 7.33 ms · Missed VSync: 1"
        );
        assert!(is_problem_frame(&frame));
        let mut smooth = analyzed(sample(3), None);
        smooth.deadline_verdict = FrameDeadlineVerdict::Met;
        assert!(!is_problem_frame(&smooth));
        assert_eq!(problem_signal(&smooth, language), "—");
        assert_eq!(problem_frames(&analysis(vec![frame], Vec::new())).len(), 1);
    }

    #[::core::prelude::v1::test]
    fn copy_evidence_escapes_fields_and_lists_stages_and_states() {
        let mut frame = analyzed(missed_sample(7, Some(24_000_000)), Some(1));
        frame.sample.states = BTreeMap::from([
            ("z".to_owned(), "tail".to_owned()),
            ("a".to_owned(), "line\nvalue".to_owned()),
        ]);
        frame.sample.stages = FrameStages {
            draw_ns: Some(4_000_000),
            ..FrameStages::default()
        };
        let text = copy_evidence_text(&frame, Language::English);
        assert!(text.contains("Session ID: session-1"));
        assert!(text.contains("Source: PERFETTO"));
        assert!(text.contains("Frame: #7"));
        assert!(text.contains("Duration: 24.00 ms"));
        assert!(text.contains("Budget: 16.67 ms"));
        assert!(text.contains("Budget Source: FRAME_INTERVAL"));
        assert!(text.contains("Deadline Verdict: MISSED"));
        assert!(text.contains("Stage Breakdown:"));
        assert!(text.contains("  Draw: 4.00 ms"));
        assert!(text.contains("State Labels:"));
        assert!(text.contains("State · a: line\\nvalue"));
        let a = text.find("State · a").expect("state a");
        let z = text.find("State · z").expect("state z");
        assert!(a < z);
        assert_eq!(escape_evidence_field("a\tb\\c"), "a\\tb\\\\c");
    }

    #[::core::prelude::v1::test]
    fn budgets_rates_and_percentages_never_invent_values() {
        let mut unknown_budget = missed_sample(1, Some(24_000_000));
        unknown_budget.expected_duration_source = aps_frame::ExpectedDurationSource::Unknown;
        assert_eq!(display_budget(&unknown_budget), "—");
        assert_eq!(display_budget(&missed_sample(2, None)), "16.67 ms");

        assert_eq!(percent_text(Some(0.5)), "50.0%");
        assert_eq!(percent_text(None), "—");
        assert_eq!(percent_text(Some(f64::NAN)), "—");

        let mut fast = analyzed(sample(1), None);
        let mut slow = analyzed(sample(2), None);
        fast.sample.refresh_rate_hz = Some(120.0);
        slow.sample.refresh_rate_hz = Some(60.0);
        let mixed = analysis(vec![fast, slow], Vec::new());
        assert_eq!(refresh_rate_text(Some(&mixed), &[]), "60–120 Hz");
        assert_eq!(refresh_rate_text(None, &[90.0]), "90 Hz");
        assert_eq!(refresh_rate_text(None, &[]), "—");

        let frame = analyzed(missed_sample(1, Some(8_000_000)), None);
        let summary = FrameAnalysisResult {
            summary: FrameSummary {
                total_frames: 1,
                deadline_miss_rate: Some(0.5),
                platform_jank_rate: Some(0.25),
                p50_duration_ns: Some(8_000_000),
                p95_duration_ns: Some(24_000_000),
                worst_duration_ns: Some(24_000_000),
                ..FrameSummary::default()
            },
            frames: vec![frame],
            clusters: Vec::new(),
            attributions: Vec::new(),
        };
        assert_eq!(percent_text(summary.summary.deadline_miss_rate), "50.0%");
        assert_eq!(format_duration(summary.summary.p95_duration_ns), "24.00 ms");
    }

    #[::core::prelude::v1::test]
    fn attribution_entry_uses_the_compressed_compose_template() {
        let attribution = aps_frame::FrameAttribution {
            activity_name: "GalleryActivity".into(),
            window_id: "window:1".into(),
            ui_state: "Gallery".into(),
            total_frames: 2,
            deadline_miss_frames: 1,
            platform_jank_frames: 1,
            average_duration_ns: Some(16_000_000),
            p95_duration_ns: Some(20_000_000),
            worst_duration_ns: Some(24_000_000),
            jank_types: BTreeSet::new(),
            fragment_name: None,
            page_name: None,
            interaction_state: None,
            render_thread_names: BTreeSet::new(),
            surface_flinger_jank_types: BTreeSet::new(),
            jank_stats_frames: 0,
        };
        assert_eq!(
            attribution_entry(&attribution, Language::English),
            "GalleryActivity · Gallery · 1 jank / 2 frames · p95 20.00 ms"
        );
    }

    #[::core::prelude::v1::test]
    fn frame_and_cluster_navigation_match_compose() {
        let frames = vec![
            analyzed(sample(1), None),
            analyzed(sample(2), None),
            analyzed(sample(3), None),
        ];
        let cluster = DeadlineMissCluster {
            id: 0,
            first_frame_id: 2,
            last_frame_id: 2,
            deadline_miss_frame_ids: vec![2],
            duration_ns: 24_000_000,
            worst_severity: JankSeverity::Major,
            window_id: None,
            activity_name: None,
            dominant_reported_stage: None,
        };
        let result = analysis(frames, vec![cluster.clone()]);
        assert_eq!(
            selected_frame(&result, Some(2)).map(|frame| frame.sample.frame_id),
            Some(2)
        );
        assert_eq!(
            selected_frame(&result, None).map(|frame| frame.sample.frame_id),
            Some(1)
        );
        assert_eq!(previous_frame_id(&result, Some(2)), Some(1));
        assert_eq!(next_frame_id(&result, Some(2)), Some(3));
        assert_eq!(previous_frame_id(&result, Some(1)), None);
        assert_eq!(next_frame_id(&result, Some(3)), None);
        assert_eq!(next_cluster_frame_id(&result, Some(1)), Some(2));
        assert_eq!(next_cluster_frame_id(&result, Some(2)), None);
        assert_eq!(previous_cluster_frame_id(&result, Some(3)), Some(2));
        assert_eq!(cluster_target_frame_id(&result, &cluster), Some(2));

        let mut other_cluster = cluster.clone();
        other_cluster.deadline_miss_frame_ids = vec![1, 2];
        assert_eq!(cluster_target_frame_id(&result, &other_cluster), Some(1));
        other_cluster.deadline_miss_frame_ids = vec![99];
        assert_eq!(cluster_target_frame_id(&result, &other_cluster), None);
    }

    #[::core::prelude::v1::test]
    fn source_view_filter_and_jank_type_labels_are_localized() {
        assert_eq!(
            source_display(Language::English, FrameSource::Gfxinfo),
            "gfxinfo"
        );
        assert_eq!(
            source_display(Language::SimplifiedChinese, FrameSource::Perfetto),
            "Perfetto"
        );
        assert_eq!(
            Language::English.text(view_filter_label(FrameViewFilter::JankOnly)),
            "Jank Only"
        );
        assert_eq!(
            Language::English.text(view_filter_label(FrameViewFilter::DeadlineMisses)),
            "Deadline Misses"
        );
        assert_eq!(
            jank_type_display(JankType::SlowDraw, Language::English),
            "Slow draw"
        );
        assert_eq!(
            jank_type_display(JankType::SlowDraw, Language::SimplifiedChinese),
            "绘制耗时"
        );
        assert_eq!(JankType::SlowDraw.as_name(), "SLOW_DRAW");
        assert_eq!(severity_enum_name(JankSeverity::Severe), "SEVERE");
        assert_eq!(
            expected_duration_source_enum_name(aps_frame::ExpectedDurationSource::PlatformDeadline),
            "PLATFORM_DEADLINE"
        );
        let mut sources = analysis(
            vec![analyzed(sample(1), None), {
                let mut frame = analyzed(sample(2), None);
                frame.sample.source = FrameSource::Gfxinfo;
                frame
            }],
            Vec::new(),
        );
        sources.frames[0].sample.source = FrameSource::Perfetto;
        assert_eq!(
            available_sources(&sources),
            vec![FrameSource::Perfetto, FrameSource::Gfxinfo]
        );
    }

    #[::core::prelude::v1::test]
    fn series_and_stage_helpers_feed_the_sparklines() {
        let frames = vec![
            analyzed(missed_sample(1, Some(8_000_000)), None),
            {
                let mut frame = analyzed(missed_sample(2, Some(24_000_000)), None);
                frame.deadline_verdict = FrameDeadlineVerdict::Met;
                frame
            },
            {
                let mut frame = analyzed(missed_sample(3, None), None);
                frame.deadline_verdict = FrameDeadlineVerdict::Unknown;
                frame
            },
        ];
        let result = analysis(frames, Vec::new());
        assert_eq!(
            deadline_series(Some(&result)),
            vec![Some(1.0), Some(0.0), None]
        );
        assert_eq!(
            duration_series(Some(&result)),
            vec![Some(8_000_000.0), Some(24_000_000.0), None]
        );
        assert_eq!(duration_series(None), Vec::<Option<f64>>::new());
        let mut stage_sample = sample(1);
        stage_sample.stages = FrameStages {
            input_ns: Some(1_000_000),
            draw_ns: Some(4_000_000),
            ..FrameStages::default()
        };
        assert_eq!(
            frame_stage_values(&stage_sample),
            vec![("Input", 1_000_000), ("Draw", 4_000_000)]
        );
        assert_eq!(maximum_stage_ns(&stage_sample.stages), 4_000_000);
    }
}

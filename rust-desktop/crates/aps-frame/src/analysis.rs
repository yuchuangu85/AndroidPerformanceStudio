//! Compose frame-profiler analysis parity.
//!
//! Ports `analysis-frame/...` (`FrameJankAnalyzer`, `FrameAttributionAnalyzer`,
//! `FrameTimelineClassification`, `FrameJankStatsCorrelator`) and the
//! `presentation/.../FrameAnalysisFilter.kt` filter plus the Compose
//! `formatMillis` helper. No platform jank is inferred from deadline misses.

use crate::model::{FrameSample, FrameSource, JankType};
use std::collections::{BTreeSet, HashMap};

/// State key holding the authoritative FrameTimeline jank tag.
pub const FRAME_TIMELINE_JANK_TAG: &str = "frameTimelineJankTag";
/// State key holding the authoritative FrameTimeline present type.
pub const FRAME_TIMELINE_PRESENT_TYPE: &str = "frameTimelinePresentType";

/// Deadline verdict derived from duration versus expected duration.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum FrameDeadlineVerdict {
    Met,
    Missed,
    Unknown,
}

impl FrameDeadlineVerdict {
    /// Kotlin enum `.name`.
    pub const fn as_name(&self) -> &'static str {
        match self {
            Self::Met => "MET",
            Self::Missed => "MISSED",
            Self::Unknown => "UNKNOWN",
        }
    }
}

/// Jank severity, ordered exactly like the Compose enum ordinals.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum JankSeverity {
    Smooth,
    Minor,
    Major,
    Severe,
    Frozen,
    Unknown,
}

impl JankSeverity {
    /// Kotlin enum `.name`.
    pub const fn as_name(&self) -> &'static str {
        match self {
            Self::Smooth => "SMOOTH",
            Self::Minor => "MINOR",
            Self::Major => "MAJOR",
            Self::Severe => "SEVERE",
            Self::Frozen => "FROZEN",
            Self::Unknown => "UNKNOWN",
        }
    }
}

/// One analyzed frame with evidence only, matching Compose `AnalyzedFrame`.
#[derive(Debug, Clone, PartialEq)]
pub struct AnalyzedFrame {
    pub sample: FrameSample,
    pub deadline_verdict: FrameDeadlineVerdict,
    pub severity: JankSeverity,
    pub missed_vsync_count: Option<u32>,
    pub platform_jank_types: BTreeSet<JankType>,
    pub largest_reported_stage: Option<String>,
}

/// Aggregate analysis summary, matching Compose `FrameSummary`.
#[derive(Debug, Clone, Default, PartialEq)]
pub struct FrameSummary {
    pub total_frames: usize,
    pub deadline_classified_frames: usize,
    pub deadline_miss_frames: usize,
    pub deadline_unknown_frames: usize,
    pub deadline_miss_rate: Option<f64>,
    pub platform_classified_frames: usize,
    pub platform_jank_frames: usize,
    pub platform_unknown_frames: usize,
    pub platform_jank_rate: Option<f64>,
    pub jank_stats_classified_frames: usize,
    pub jank_stats_jank_frames: usize,
    pub jank_stats_jank_rate: Option<f64>,
    pub p50_duration_ns: Option<i64>,
    pub p95_duration_ns: Option<i64>,
    pub p99_duration_ns: Option<i64>,
    pub worst_duration_ns: Option<i64>,
}

/// A run of deadline-miss frames within one window/activity context.
#[derive(Debug, Clone, PartialEq)]
pub struct DeadlineMissCluster {
    pub id: u32,
    pub first_frame_id: u64,
    pub last_frame_id: u64,
    pub deadline_miss_frame_ids: Vec<u64>,
    pub duration_ns: i64,
    pub worst_severity: JankSeverity,
    pub window_id: Option<String>,
    pub activity_name: Option<String>,
    pub dominant_reported_stage: Option<String>,
}

/// Frames grouped by Activity/window/UI-state so platform jank can be attributed
/// to app context, matching Compose `FrameAttribution`.
#[derive(Debug, Clone, PartialEq)]
pub struct FrameAttribution {
    pub activity_name: String,
    pub window_id: String,
    pub ui_state: String,
    pub total_frames: usize,
    pub deadline_miss_frames: usize,
    pub platform_jank_frames: usize,
    pub average_duration_ns: Option<i64>,
    pub p95_duration_ns: Option<i64>,
    pub worst_duration_ns: Option<i64>,
    pub jank_types: BTreeSet<String>,
    pub fragment_name: Option<String>,
    pub page_name: Option<String>,
    pub interaction_state: Option<String>,
    pub render_thread_names: BTreeSet<String>,
    pub surface_flinger_jank_types: BTreeSet<String>,
    pub jank_stats_frames: usize,
}

/// Complete analysis result, matching Compose `FrameAnalysisResult`.
#[derive(Debug, Clone, PartialEq)]
pub struct FrameAnalysisResult {
    pub frames: Vec<AnalyzedFrame>,
    pub summary: FrameSummary,
    pub clusters: Vec<DeadlineMissCluster>,
    pub attributions: Vec<FrameAttribution>,
}

const DEFAULT_MAX_SMOOTH_GAP: usize = 2;
const FROZEN_FRAME_NS: i64 = 700_000_000;

/// Port of Compose `FrameJankAnalyzer`.
#[derive(Debug, Clone)]
pub struct FrameJankAnalyzer {
    max_smooth_gap: usize,
}

impl Default for FrameJankAnalyzer {
    fn default() -> Self {
        Self::new()
    }
}

impl FrameJankAnalyzer {
    /// Analyzer with the Compose default smooth gap (2 frames).
    pub const fn new() -> Self {
        Self {
            max_smooth_gap: DEFAULT_MAX_SMOOTH_GAP,
        }
    }

    /// Analyzer with an explicit smooth gap, like the Compose constructor argument.
    pub const fn with_max_smooth_gap(max_smooth_gap: usize) -> Self {
        Self { max_smooth_gap }
    }

    /// Sort by intended vsync (frame id fallback), analyze, summarize and cluster.
    pub fn analyze(&self, samples: &[FrameSample]) -> FrameAnalysisResult {
        let mut sorted = samples.to_vec();
        sorted.sort_by_key(|sample| sample.intended_vsync_ns.unwrap_or(sample.frame_id as i64));
        let frames: Vec<AnalyzedFrame> = sorted
            .into_iter()
            .map(|sample| self.analyze_frame(sample))
            .collect();
        let attributions = {
            let samples: Vec<&FrameSample> = frames.iter().map(|frame| &frame.sample).collect();
            FrameAttributionAnalyzer::default().analyze_refs(&samples)
        };
        FrameAnalysisResult {
            summary: summarize(&frames),
            clusters: self.cluster(&frames),
            attributions,
            frames,
        }
    }

    /// Analyze a single frame exactly like Compose `analyzeFrame`.
    pub fn analyze_frame(&self, sample: FrameSample) -> AnalyzedFrame {
        if !sample.eligible_for_jank {
            return unknown(sample);
        }
        let duration = sample.resolved_duration_ns();
        let expected = sample.known_expected_duration_ns();
        let (deadline_verdict, missed_vsync_count) = match (duration, expected) {
            (Some(duration), Some(expected)) => {
                let verdict = if duration > expected {
                    FrameDeadlineVerdict::Missed
                } else {
                    FrameDeadlineVerdict::Met
                };
                (verdict, Some(missed_vsync_count(duration, expected)))
            }
            _ => (FrameDeadlineVerdict::Unknown, None),
        };
        let largest_reported_stage = largest_reported_stage(&sample);
        let mut platform_jank_types = sample.platform_jank_types.clone();
        if sample.platform_jank == Some(true) {
            platform_jank_types.insert(JankType::PlatformReported);
        }
        AnalyzedFrame {
            severity: severity(deadline_verdict, duration, expected, missed_vsync_count),
            sample,
            deadline_verdict,
            missed_vsync_count,
            platform_jank_types,
            largest_reported_stage,
        }
    }

    fn cluster(&self, frames: &[AnalyzedFrame]) -> Vec<DeadlineMissCluster> {
        let mut clusters: Vec<DeadlineMissCluster> = Vec::new();
        let mut cursor = 0usize;
        while cursor < frames.len() {
            let Some(first_index) = (cursor..frames.len())
                .find(|&index| frames[index].deadline_verdict == FrameDeadlineVerdict::Missed)
            else {
                break;
            };
            let first = &frames[first_index];
            let mut jank_frames: Vec<&AnalyzedFrame> = vec![first];
            let mut scan = first_index + 1;
            let mut smooth_gap = 0usize;
            while scan < frames.len() {
                let candidate = &frames[scan];
                if !same_context(first, candidate)
                    || candidate.deadline_verdict == FrameDeadlineVerdict::Unknown
                {
                    break;
                }
                if candidate.deadline_verdict == FrameDeadlineVerdict::Missed {
                    jank_frames.push(candidate);
                    smooth_gap = 0;
                } else {
                    smooth_gap += 1;
                    if smooth_gap > self.max_smooth_gap {
                        break;
                    }
                }
                scan += 1;
            }
            let last = *jank_frames
                .last()
                .expect("cluster contains the first frame");
            let last_index = frames
                .iter()
                .rposition(|frame| frame == last)
                .expect("cluster frame belongs to the analyzed slice");
            let mut worst_severity = jank_frames[0].severity;
            for frame in &jank_frames[1..] {
                if frame.severity > worst_severity {
                    worst_severity = frame.severity;
                }
            }
            let mut stage_counts: Vec<(String, usize)> = Vec::new();
            for frame in &jank_frames {
                let Some(stage) = &frame.largest_reported_stage else {
                    continue;
                };
                match stage_counts.iter_mut().find(|(name, _)| name == stage) {
                    Some((_, count)) => *count += 1,
                    None => stage_counts.push((stage.clone(), 1)),
                }
            }
            let mut dominant_reported_stage: Option<(String, usize)> = None;
            for (stage, count) in stage_counts {
                if dominant_reported_stage
                    .as_ref()
                    .is_none_or(|(_, best)| count > *best)
                {
                    dominant_reported_stage = Some((stage, count));
                }
            }
            clusters.push(DeadlineMissCluster {
                id: clusters.len() as u32,
                first_frame_id: first.sample.frame_id,
                last_frame_id: last.sample.frame_id,
                deadline_miss_frame_ids: jank_frames
                    .iter()
                    .map(|frame| frame.sample.frame_id)
                    .collect(),
                duration_ns: cluster_duration(&first.sample, &last.sample),
                worst_severity,
                window_id: first.sample.window_id.clone(),
                activity_name: first.sample.activity_name.clone(),
                dominant_reported_stage: dominant_reported_stage.map(|(stage, _)| stage),
            });
            cursor = last_index + 1;
        }
        clusters
    }
}

fn same_context(first: &AnalyzedFrame, other: &AnalyzedFrame) -> bool {
    first.sample.window_id == other.sample.window_id
        && first.sample.activity_name == other.sample.activity_name
}

fn severity(
    verdict: FrameDeadlineVerdict,
    duration_ns: Option<i64>,
    expected_ns: Option<i64>,
    missed_vsync_count: Option<u32>,
) -> JankSeverity {
    match verdict {
        FrameDeadlineVerdict::Unknown => JankSeverity::Unknown,
        FrameDeadlineVerdict::Met => JankSeverity::Smooth,
        FrameDeadlineVerdict::Missed => {
            if duration_ns.is_some_and(|duration| duration >= FROZEN_FRAME_NS) {
                JankSeverity::Frozen
            } else if expected_ns.is_none() || missed_vsync_count.is_none_or(|count| count <= 1) {
                JankSeverity::Minor
            } else if missed_vsync_count.is_some_and(|count| count <= 3) {
                JankSeverity::Major
            } else {
                JankSeverity::Severe
            }
        }
    }
}

fn unknown(sample: FrameSample) -> AnalyzedFrame {
    let platform_jank_types = sample.platform_jank_types.clone();
    AnalyzedFrame {
        sample,
        deadline_verdict: FrameDeadlineVerdict::Unknown,
        severity: JankSeverity::Unknown,
        missed_vsync_count: None,
        platform_jank_types,
        largest_reported_stage: None,
    }
}

fn missed_vsync_count(duration_ns: i64, expected_ns: i64) -> u32 {
    let missed = (duration_ns as f64 / expected_ns as f64).ceil() as i64 - 1;
    u32::try_from(missed.max(0)).unwrap_or(u32::MAX)
}

fn largest_reported_stage(sample: &FrameSample) -> Option<String> {
    let mut best: Option<(&'static str, i64)> = None;
    for (name, value) in sample.stages.values() {
        if best.is_none_or(|(_, best_value)| value > best_value) {
            best = Some((name, value));
        }
    }
    best.map(|(name, _)| name.to_owned())
}

fn cluster_duration(first: &FrameSample, last: &FrameSample) -> i64 {
    let start = first.intended_vsync_ns;
    let end = last.present_ns.or(last.frame_completed_ns);
    match (start, end) {
        (Some(start), Some(end)) if end >= start => end - start,
        _ => last.resolved_duration_ns().unwrap_or(0),
    }
}

fn summarize(frames: &[AnalyzedFrame]) -> FrameSummary {
    let mut durations: Vec<i64> = frames
        .iter()
        .filter_map(|frame| frame.sample.resolved_duration_ns())
        .collect();
    durations.sort_unstable();
    let deadline_classified = frames
        .iter()
        .filter(|frame| frame.deadline_verdict != FrameDeadlineVerdict::Unknown)
        .count();
    let deadline_misses = frames
        .iter()
        .filter(|frame| frame.deadline_verdict == FrameDeadlineVerdict::Missed)
        .count();
    let platform_classified = frames
        .iter()
        .filter(|frame| frame.sample.platform_jank.is_some())
        .count();
    let platform_jank = frames
        .iter()
        .filter(|frame| frame.sample.platform_jank == Some(true))
        .count();
    let jank_stats_classified = frames
        .iter()
        .filter(|frame| frame.sample.jank_stats_jank.is_some())
        .count();
    let jank_stats_jank = frames
        .iter()
        .filter(|frame| frame.sample.jank_stats_jank == Some(true))
        .count();
    FrameSummary {
        total_frames: frames.len(),
        deadline_classified_frames: deadline_classified,
        deadline_miss_frames: deadline_misses,
        deadline_unknown_frames: frames.len() - deadline_classified,
        deadline_miss_rate: ratio(deadline_misses, deadline_classified),
        platform_classified_frames: platform_classified,
        platform_jank_frames: platform_jank,
        platform_unknown_frames: frames.len() - platform_classified,
        platform_jank_rate: ratio(platform_jank, platform_classified),
        jank_stats_classified_frames: jank_stats_classified,
        jank_stats_jank_frames: jank_stats_jank,
        jank_stats_jank_rate: ratio(jank_stats_jank, jank_stats_classified),
        p50_duration_ns: analyzer_percentile(&durations, 0.50),
        p95_duration_ns: analyzer_percentile(&durations, 0.95),
        p99_duration_ns: analyzer_percentile(&durations, 0.99),
        worst_duration_ns: durations.last().copied(),
    }
}

fn ratio(numerator: usize, denominator: usize) -> Option<f64> {
    (denominator > 0).then(|| numerator as f64 / denominator as f64)
}

fn analyzer_percentile(sorted: &[i64], fraction: f64) -> Option<i64> {
    if sorted.is_empty() {
        return None;
    }
    let index = ((sorted.len() as f64 * fraction).ceil() as usize)
        .saturating_sub(1)
        .min(sorted.len() - 1);
    Some(sorted[index])
}

/// Port of Compose `FrameAttributionAnalyzer`.
#[derive(Debug, Clone, Copy, Default)]
pub struct FrameAttributionAnalyzer;

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
struct AttributionKey {
    activity_name: String,
    window_id: String,
    ui_state: String,
    fragment_name: Option<String>,
    page_name: Option<String>,
    interaction_state: Option<String>,
}

impl AttributionKey {
    fn of(sample: &FrameSample) -> Self {
        Self {
            activity_name: sample
                .activity_name
                .clone()
                .unwrap_or_else(|| "<unknown activity>".to_owned()),
            window_id: sample
                .window_id
                .clone()
                .unwrap_or_else(|| "<unknown window>".to_owned()),
            ui_state: sample
                .interaction_state
                .clone()
                .or_else(|| sample.states.get("screen").cloned())
                .or_else(|| sample.states.get("screenName").cloned())
                .or_else(|| sample.states.get("uiState").cloned())
                .unwrap_or_else(|| "<unknown state>".to_owned()),
            fragment_name: sample
                .fragment_name
                .clone()
                .or_else(|| sample.states.get("fragment").cloned()),
            page_name: sample
                .page_name
                .clone()
                .or_else(|| sample.states.get("page").cloned()),
            interaction_state: sample
                .interaction_state
                .clone()
                .or_else(|| sample.states.get("interaction").cloned()),
        }
    }
}

impl FrameAttributionAnalyzer {
    /// Group samples by Activity/window/UI-state and rank by platform jank.
    pub fn analyze(&self, samples: &[FrameSample]) -> Vec<FrameAttribution> {
        let samples: Vec<&FrameSample> = samples.iter().collect();
        self.analyze_refs(&samples)
    }

    fn analyze_refs(&self, samples: &[&FrameSample]) -> Vec<FrameAttribution> {
        let mut groups: Vec<(AttributionKey, Vec<&FrameSample>)> = Vec::new();
        let mut positions: HashMap<AttributionKey, usize> = HashMap::new();
        for sample in samples {
            let key = AttributionKey::of(sample);
            match positions.get(&key) {
                Some(&position) => groups[position].1.push(sample),
                None => {
                    let position = groups.len();
                    positions.insert(key.clone(), position);
                    groups.push((key, vec![sample]));
                }
            }
        }
        let mut attributions: Vec<FrameAttribution> = groups
            .into_iter()
            .map(|(key, frames)| {
                let mut durations: Vec<i64> = frames
                    .iter()
                    .filter_map(|frame| frame.resolved_duration_ns())
                    .collect();
                durations.sort_unstable();
                let average_duration_ns = if durations.is_empty() {
                    None
                } else {
                    let sum: i128 = durations.iter().map(|value| *value as i128).sum();
                    Some((sum as f64 / durations.len() as f64) as i64)
                };
                FrameAttribution {
                    activity_name: key.activity_name,
                    window_id: key.window_id,
                    ui_state: key.ui_state,
                    total_frames: frames.len(),
                    deadline_miss_frames: frames
                        .iter()
                        .filter(|frame| {
                            frame.eligible_for_jank
                                && frame
                                    .resolved_duration_ns()
                                    .zip(frame.known_expected_duration_ns())
                                    .is_some_and(|(duration, expected)| duration > expected)
                        })
                        .count(),
                    platform_jank_frames: frames
                        .iter()
                        .filter(|frame| frame.platform_jank == Some(true))
                        .count(),
                    average_duration_ns,
                    p95_duration_ns: attribution_percentile(&durations, 0.95),
                    worst_duration_ns: durations.last().copied(),
                    jank_types: frames
                        .iter()
                        .flat_map(|frame| {
                            frame
                                .platform_jank_types
                                .iter()
                                .map(|jank_type| jank_type.as_name().to_owned())
                        })
                        .collect(),
                    fragment_name: key.fragment_name,
                    page_name: key.page_name,
                    interaction_state: key.interaction_state,
                    render_thread_names: frames
                        .iter()
                        .filter_map(|frame| frame.render_thread_name.clone())
                        .collect(),
                    surface_flinger_jank_types: frames
                        .iter()
                        .filter_map(|frame| frame.surface_flinger_jank_type.clone())
                        .collect(),
                    jank_stats_frames: frames
                        .iter()
                        .filter(|frame| frame.jank_stats_jank == Some(true))
                        .count(),
                }
            })
            .collect();
        attributions.sort_by(|left, right| {
            right
                .platform_jank_frames
                .cmp(&left.platform_jank_frames)
                .then_with(|| {
                    right
                        .worst_duration_ns
                        .unwrap_or(0)
                        .cmp(&left.worst_duration_ns.unwrap_or(0))
                })
        });
        attributions
    }
}

fn attribution_percentile(sorted: &[i64], fraction: f64) -> Option<i64> {
    if sorted.is_empty() {
        return None;
    }
    let index = ((sorted.len() as f64 * fraction) as usize).min(sorted.len() - 1);
    Some(sorted[index])
}

/// FrameTimeline classification, matching Compose `FrameTimelineStatus`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum FrameTimelineStatus {
    Good,
    HighLatency,
    SelfJank,
    OtherJank,
    Dropped,
    Unknown,
}

impl FrameTimelineStatus {
    /// Kotlin enum `.name`.
    pub const fn as_name(&self) -> &'static str {
        match self {
            Self::Good => "GOOD",
            Self::HighLatency => "HIGH_LATENCY",
            Self::SelfJank => "SELF_JANK",
            Self::OtherJank => "OTHER_JANK",
            Self::Dropped => "DROPPED",
            Self::Unknown => "UNKNOWN",
        }
    }
}

/// Port of Compose `FrameSample.frameTimelineStatus()`.
pub fn frame_timeline_status(sample: &FrameSample) -> FrameTimelineStatus {
    match sample
        .states
        .get(FRAME_TIMELINE_JANK_TAG)
        .map(String::as_str)
    {
        Some(tag) => tag_status(tag),
        None => match sample.source {
            FrameSource::Perfetto => match sample.platform_jank_rule_id.as_deref() {
                Some("None" | "On time") => FrameTimelineStatus::Good,
                Some("Buffer Stuffing" | "SurfaceFlinger Stuffing") => {
                    FrameTimelineStatus::HighLatency
                }
                _ => FrameTimelineStatus::Unknown,
            },
            _ => non_perfetto_status(sample),
        },
    }
}

fn tag_status(tag: &str) -> FrameTimelineStatus {
    match tag {
        "No Jank" => FrameTimelineStatus::Good,
        "Buffer Stuffing" | "SurfaceFlinger Stuffing" => FrameTimelineStatus::HighLatency,
        "Self Jank" => FrameTimelineStatus::SelfJank,
        "Other Jank" => FrameTimelineStatus::OtherJank,
        "Dropped Frame" => FrameTimelineStatus::Dropped,
        _ => FrameTimelineStatus::Unknown,
    }
}

fn non_perfetto_status(sample: &FrameSample) -> FrameTimelineStatus {
    if sample.platform_jank == Some(true) || sample.jank_stats_jank == Some(true) {
        return FrameTimelineStatus::Unknown;
    }
    if sample.platform_jank == Some(false) || sample.jank_stats_jank == Some(false) {
        return FrameTimelineStatus::Good;
    }
    if !sample.eligible_for_jank
        || !sample.platform_jank_types.is_empty()
        || !sample.jank_stats_reasons.is_empty()
    {
        return FrameTimelineStatus::Unknown;
    }
    match (
        sample.resolved_duration_ns(),
        sample.known_expected_duration_ns(),
    ) {
        (Some(duration), Some(budget)) if duration <= budget => FrameTimelineStatus::Good,
        _ => FrameTimelineStatus::Unknown,
    }
}

/// A JankStats observation captured by the optional AndroidX agent.
#[derive(Debug, Clone, PartialEq)]
pub struct JankStatsObservation {
    pub frame_id: Option<u64>,
    pub frame_timeline_vsync_id: Option<i64>,
    pub timestamp_ns: Option<i64>,
    pub is_jank: bool,
    pub reasons: BTreeSet<String>,
    pub rule_id: Option<String>,
    pub rule_version: Option<String>,
    pub activity_name: Option<String>,
    pub fragment_name: Option<String>,
    pub page_name: Option<String>,
    pub interaction_state: Option<String>,
    pub render_thread_name: Option<String>,
}

/// One timeline/JankStats correlation result.
#[derive(Debug, Clone, PartialEq)]
pub struct FrameJankCorrelation {
    pub frame: FrameSample,
    pub jank_stats: Option<JankStatsObservation>,
    pub matched_by: JankStatsMatch,
    pub time_delta_ns: Option<i64>,
}

/// How a JankStats observation was matched to a frame.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum JankStatsMatch {
    FrameId,
    VsyncId,
    Timestamp,
    Unmatched,
}

impl JankStatsMatch {
    /// Kotlin enum `.name`.
    pub const fn as_name(&self) -> &'static str {
        match self {
            Self::FrameId => "FRAME_ID",
            Self::VsyncId => "VSYNC_ID",
            Self::Timestamp => "TIMESTAMP",
            Self::Unmatched => "UNMATCHED",
        }
    }
}

/// Port of Compose `FrameJankStatsCorrelator`.
#[derive(Debug, Clone)]
pub struct FrameJankStatsCorrelator {
    timestamp_tolerance_ns: i64,
}

impl Default for FrameJankStatsCorrelator {
    fn default() -> Self {
        Self::new(2_000_000)
    }
}

impl FrameJankStatsCorrelator {
    pub fn new(timestamp_tolerance_ns: i64) -> Self {
        assert!(
            timestamp_tolerance_ns >= 0,
            "timestampToleranceNs must not be negative"
        );
        Self {
            timestamp_tolerance_ns,
        }
    }

    /// Join JankStats observations to timeline frames: IDs win, timestamps are
    /// bounded derived evidence and every observation is consumed once.
    pub fn correlate(
        &self,
        timeline: &[FrameSample],
        observations: &[JankStatsObservation],
    ) -> Vec<FrameJankCorrelation> {
        let mut unused: BTreeSet<usize> = (0..observations.len()).collect();
        let mut correlations = Vec::with_capacity(timeline.len());
        for frame in timeline {
            let exact_index = unused
                .iter()
                .copied()
                .find(|&index| observations[index].frame_id == Some(frame.frame_id));
            let vsync_index = exact_index.or_else(|| {
                frame.frame_timeline_vsync_id.and_then(|vsync_id| {
                    unused.iter().copied().find(|&index| {
                        observations[index].frame_timeline_vsync_id == Some(vsync_id)
                    })
                })
            });
            let timestamp_index = vsync_index.or_else(|| {
                frame_timestamp(frame).and_then(|timestamp| {
                    let mut best: Option<(usize, i64)> = None;
                    for &index in &unused {
                        let Some(observation_timestamp) = observations[index].timestamp_ns else {
                            continue;
                        };
                        let delta = (observation_timestamp - timestamp).abs();
                        if best.is_none_or(|(_, best_delta)| delta < best_delta) {
                            best = Some((index, delta));
                        }
                    }
                    best.filter(|(_, delta)| *delta <= self.timestamp_tolerance_ns)
                        .map(|(index, _)| index)
                })
            });
            let observation = timestamp_index.map(|index| &observations[index]);
            if let Some(index) = timestamp_index {
                unused.remove(&index);
            }
            let matched_by = match (exact_index, vsync_index, timestamp_index) {
                (Some(_), _, _) => JankStatsMatch::FrameId,
                (None, Some(_), _) => JankStatsMatch::VsyncId,
                (None, None, Some(_)) => JankStatsMatch::Timestamp,
                (None, None, None) => JankStatsMatch::Unmatched,
            };
            let time_delta_ns = observation.and_then(|observation| {
                observation
                    .timestamp_ns
                    .zip(frame_timestamp(frame))
                    .map(|(observation_timestamp, timestamp)| timestamp - observation_timestamp)
            });
            let frame = match observation {
                Some(observation) => merge(frame, observation),
                None => frame.clone(),
            };
            correlations.push(FrameJankCorrelation {
                frame,
                jank_stats: observation.cloned(),
                matched_by,
                time_delta_ns,
            });
        }
        correlations
    }

    /// Correlate and return only the enriched samples.
    pub fn merge_into_samples(
        &self,
        timeline: &[FrameSample],
        observations: &[JankStatsObservation],
    ) -> Vec<FrameSample> {
        self.correlate(timeline, observations)
            .into_iter()
            .map(|correlation| correlation.frame)
            .collect()
    }
}

fn merge(frame: &FrameSample, observation: &JankStatsObservation) -> FrameSample {
    let mut merged = frame.clone();
    merged.activity_name = frame
        .activity_name
        .clone()
        .or_else(|| observation.activity_name.clone());
    merged.fragment_name = frame
        .fragment_name
        .clone()
        .or_else(|| observation.fragment_name.clone());
    merged.page_name = frame
        .page_name
        .clone()
        .or_else(|| observation.page_name.clone());
    merged.interaction_state = frame
        .interaction_state
        .clone()
        .or_else(|| observation.interaction_state.clone());
    merged.render_thread_name = frame
        .render_thread_name
        .clone()
        .or_else(|| observation.render_thread_name.clone());
    merged.jank_stats_jank = Some(observation.is_jank);
    merged.jank_stats_reasons = observation.reasons.clone();
    merged.jank_stats_rule_id = observation.rule_id.clone();
    merged.jank_stats_rule_version = observation.rule_version.clone();
    let mut states = frame.states.clone();
    if let Some(value) = &observation.activity_name {
        states.insert("activity".into(), value.clone());
    }
    if let Some(value) = &observation.fragment_name {
        states.insert("fragment".into(), value.clone());
    }
    if let Some(value) = &observation.page_name {
        states.insert("page".into(), value.clone());
    }
    if let Some(value) = &observation.interaction_state {
        states.insert("interaction".into(), value.clone());
    }
    merged.states = states;
    merged
}

fn frame_timestamp(frame: &FrameSample) -> Option<i64> {
    frame.actual_vsync_ns.or(frame.intended_vsync_ns)
}

/// Presentation view filter, matching Compose `FrameViewFilter`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum FrameViewFilter {
    All,
    JankOnly,
    DeadlineMisses,
}

impl FrameViewFilter {
    /// Kotlin enum `.name`.
    pub const fn as_name(&self) -> &'static str {
        match self {
            Self::All => "ALL",
            Self::JankOnly => "JANK_ONLY",
            Self::DeadlineMisses => "DEADLINE_MISSES",
        }
    }
}

/// Port of Compose `FrameAnalysisResult.filtered`, including cluster trimming
/// and duration recomputation.
pub fn filter_analysis(
    analysis: &FrameAnalysisResult,
    source: Option<FrameSource>,
    filter: FrameViewFilter,
) -> FrameAnalysisResult {
    if source.is_none() && filter == FrameViewFilter::All {
        return analysis.clone();
    }
    let visible_samples: Vec<FrameSample> = analysis
        .frames
        .iter()
        .filter(|frame| {
            source.is_none_or(|source| frame.sample.source == source)
                && matches_filter(frame, filter)
        })
        .map(|frame| frame.sample.clone())
        .collect();
    let filtered = FrameJankAnalyzer::new().analyze(&visible_samples);
    let visible_frame_ids: BTreeSet<u64> = filtered
        .frames
        .iter()
        .map(|frame| frame.sample.frame_id)
        .collect();
    let clusters = analysis
        .clusters
        .iter()
        .filter_map(|cluster| {
            let visible_misses: Vec<u64> = cluster
                .deadline_miss_frame_ids
                .iter()
                .copied()
                .filter(|frame_id| visible_frame_ids.contains(frame_id))
                .collect();
            let first_id = *visible_misses.first()?;
            let last_id = *visible_misses.last()?;
            let first = filtered
                .frames
                .iter()
                .find(|frame| frame.sample.frame_id == first_id)?
                .sample
                .clone();
            let last = filtered
                .frames
                .iter()
                .find(|frame| frame.sample.frame_id == last_id)?
                .sample
                .clone();
            let mut cluster = cluster.clone();
            cluster.first_frame_id = first_id;
            cluster.last_frame_id = last_id;
            cluster.deadline_miss_frame_ids = visible_misses;
            cluster.duration_ns = cluster_duration(&first, &last);
            Some(cluster)
        })
        .collect();
    FrameAnalysisResult {
        frames: filtered.frames,
        summary: filtered.summary,
        clusters,
        attributions: filtered.attributions,
    }
}

/// Compose `isProblemFrame`: deadline miss or any reported jank signal.
pub fn is_problem_frame(frame: &AnalyzedFrame) -> bool {
    frame.deadline_verdict == FrameDeadlineVerdict::Missed
        || frame.sample.platform_jank == Some(true)
        || !frame.platform_jank_types.is_empty()
        || frame.sample.jank_stats_jank == Some(true)
        || !frame.sample.jank_stats_reasons.is_empty()
}

fn matches_filter(frame: &AnalyzedFrame, filter: FrameViewFilter) -> bool {
    match filter {
        FrameViewFilter::All => true,
        FrameViewFilter::JankOnly => is_problem_frame(frame),
        FrameViewFilter::DeadlineMisses => frame.deadline_verdict == FrameDeadlineVerdict::Missed,
    }
}

/// Compose `FrameProfilerScreen.formatMillis`: two decimals or an em dash.
pub fn format_millis(value: Option<i64>) -> String {
    match value {
        Some(value) => format!("{:.2} ms", value as f64 / 1_000_000.0),
        None => "—".to_owned(),
    }
}

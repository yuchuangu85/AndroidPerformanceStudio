//! Frame Analyzer engine: Compose frame-profiler parity modules plus the legacy
//! offline gfxinfo FrameStats import with explicit evidence and budget provenance.
//!
//! The Compose-faithful modules are `model`, `gfxinfo`, `analysis` and
//! `frame_timeline`. Most of their items are re-exported at the crate root;
//! the exceptions are the names that still belong to the legacy import API
//! (`FrameStages`, `AnalyzedFrame`, `FrameSummary`, `DeadlineMissCluster`),
//! which stay at `aps_frame::model::FrameStages` and
//! `aps_frame::analysis::{AnalyzedFrame, FrameSummary, DeadlineMissCluster}`
//! until the old import page is removed.
pub mod analysis;
pub mod frame_timeline;
pub mod gfxinfo;
pub mod model;
mod parser;

use std::fs::File;
use std::io::Read;
use std::path::Path;

pub const MAX_INPUT_BYTES: u64 = 256 * 1024 * 1024;
pub const MAX_FRAMES: usize = 1_000_000;
pub const MAX_PAGE_SIZE: usize = 512;
pub const MAX_CLUSTERS: usize = 512;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BudgetSource {
    PlatformDeadline,
    FrameInterval,
    InferredVsync,
    Unknown,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DeadlineVerdict {
    Met,
    Missed,
    Unknown,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct FrameStages {
    pub input_ns: Option<u64>,
    pub animation_ns: Option<u64>,
    pub layout_measure_ns: Option<u64>,
    pub draw_ns: Option<u64>,
    pub sync_ns: Option<u64>,
    pub command_issue_ns: Option<u64>,
    pub swap_buffers_ns: Option<u64>,
    pub gpu_ns: Option<u64>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AnalyzedFrame {
    pub id: u64,
    pub window: Option<String>,
    /// gfxinfo's device monotonic nanosecond domain; not host/wall-clock time.
    pub intended_vsync_ns: Option<u64>,
    pub actual_vsync_ns: Option<u64>,
    pub completed_ns: Option<u64>,
    pub present_ns: Option<u64>,
    pub vsync_id: Option<u64>,
    pub duration_ns: Option<u64>,
    pub budget_ns: Option<u64>,
    pub budget_source: BudgetSource,
    pub flags: i64,
    pub deadline: DeadlineVerdict,
    pub missed_vsync_count: Option<u64>,
    /// gfxinfo does not report this signal. Never infer it from deadline misses.
    pub platform_jank: Option<bool>,
    pub stages: FrameStages,
    /// All source column values, including unknown future columns, retained verbatim.
    pub columns: std::collections::BTreeMap<String, String>,
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct FrameSummary {
    pub total_frames: usize,
    pub classified_frames: usize,
    pub deadline_misses: usize,
    pub unknown_deadlines: usize,
    pub deadline_miss_rate: Option<f64>,
    pub platform_classified_frames: usize,
    pub platform_jank_frames: usize,
    pub platform_jank_rate: Option<f64>,
    pub p50_duration_ns: Option<u64>,
    pub p95_duration_ns: Option<u64>,
    pub p99_duration_ns: Option<u64>,
    pub worst_duration_ns: Option<u64>,
}

#[derive(Debug, Clone, Default)]
pub struct FrameQuery {
    pub window: Option<String>,
    pub start_ns: Option<u64>,
    pub end_ns: Option<u64>,
    pub deadline: Option<DeadlineVerdict>,
    pub offset: usize,
    pub limit: usize,
}

impl FrameQuery {
    pub fn first_page() -> Self {
        Self {
            limit: 100,
            ..Self::default()
        }
    }

    fn validate(&self) -> Result<(), String> {
        if self.limit > MAX_PAGE_SIZE || self.offset.checked_add(self.limit).is_none() {
            return Err("Frame page exceeds supported bounds".into());
        }
        if self
            .start_ns
            .zip(self.end_ns)
            .is_some_and(|(start, end)| start >= end)
        {
            return Err("Frame range requires start < end ([start,end))".into());
        }
        Ok(())
    }

    fn matches(&self, frame: &AnalyzedFrame) -> bool {
        self.window
            .as_ref()
            .is_none_or(|window| frame.window.as_ref() == Some(window))
            && self
                .deadline
                .is_none_or(|deadline| frame.deadline == deadline)
            && self
                .start_ns
                .is_none_or(|start| frame.intended_vsync_ns.is_some_and(|time| time >= start))
            && self
                .end_ns
                .is_none_or(|end| frame.intended_vsync_ns.is_some_and(|time| time < end))
    }
}

#[derive(Debug, Clone)]
pub struct FramePage {
    pub total: usize,
    pub offset: usize,
    pub rows: Vec<AnalyzedFrame>,
    pub summary: FrameSummary,
}

#[derive(Debug, Clone)]
pub struct ProblemFrame {
    pub filtered_index: usize,
    pub frame: AnalyzedFrame,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeadlineMissCluster {
    pub first_frame_id: u64,
    pub last_frame_id: u64,
    pub first_filtered_index: usize,
    pub miss_count: usize,
    pub worst_duration_ns: Option<u64>,
}

#[derive(Debug, Clone)]
pub struct FrameAnalysisPage {
    pub problem_total: usize,
    pub problem_offset: usize,
    pub problems: Vec<ProblemFrame>,
    pub cluster_total: usize,
    pub clusters: Vec<DeadlineMissCluster>,
    pub clusters_truncated: bool,
}

pub struct FrameSession {
    frames: Vec<AnalyzedFrame>,
    warnings: Vec<String>,
    summary: FrameSummary,
}

impl FrameSession {
    pub fn open(path: &Path, max_bytes: u64) -> Result<Self, String> {
        if max_bytes == 0 || max_bytes > MAX_INPUT_BYTES {
            return Err("Frame input limit must be between 1 and 256 MiB".into());
        }
        let file = File::open(path).map_err(|_| "Cannot open FrameStats input")?;
        if !file
            .metadata()
            .map_err(|_| "Cannot inspect FrameStats input")?
            .is_file()
        {
            return Err("FrameStats input must be a regular file".into());
        }
        let mut text = String::new();
        file.take(max_bytes + 1)
            .read_to_string(&mut text)
            .map_err(|_| "FrameStats input must be bounded UTF-8 text")?;
        if text.len() as u64 > max_bytes {
            return Err("FrameStats input exceeds byte limit".into());
        }
        Self::parse_gfxinfo(&text)
    }

    pub fn parse_gfxinfo(text: &str) -> Result<Self, String> {
        let (mut frames, warnings) = parser::parse(text)?;
        frames.sort_by_key(|frame| (frame.intended_vsync_ns.unwrap_or(u64::MAX), frame.id));
        let summary = summarize(frames.iter());
        Ok(Self {
            frames,
            warnings,
            summary,
        })
    }

    pub fn frames(&self) -> &[AnalyzedFrame] {
        &self.frames
    }
    pub fn warnings(&self) -> &[String] {
        &self.warnings
    }
    pub fn summary(&self) -> &FrameSummary {
        &self.summary
    }

    pub fn page(&self, query: &FrameQuery) -> Result<FramePage, String> {
        query.validate()?;
        let mut total = 0usize;
        let mut rows = Vec::with_capacity(query.limit.min(self.frames.len()));
        let mut summary = SummaryBuilder::default();
        for frame in self.frames.iter().filter(|frame| query.matches(frame)) {
            if total >= query.offset && rows.len() < query.limit {
                rows.push(frame.clone());
            }
            total = total
                .checked_add(1)
                .ok_or("Frame count exceeds supported bounds")?;
            summary.push(frame);
        }
        Ok(FramePage {
            total,
            offset: query.offset,
            rows,
            summary: summary.finish(),
        })
    }

    /// Produces bounded problem rows and evidence-only deadline-miss runs for
    /// the same window/time/deadline filter as `page`. No platform-jank or
    /// root-cause attribution is inferred.
    pub fn analysis_page(
        &self,
        query: &FrameQuery,
        problem_offset: usize,
        problem_limit: usize,
    ) -> Result<FrameAnalysisPage, String> {
        query.validate()?;
        if problem_limit > MAX_PAGE_SIZE || problem_offset.checked_add(problem_limit).is_none() {
            return Err("Frame problem page exceeds supported bounds".into());
        }
        let allows_misses = query
            .deadline
            .is_none_or(|value| value == DeadlineVerdict::Missed);
        let mut problem_total = 0usize;
        let mut problems = Vec::with_capacity(problem_limit.min(self.frames.len()));
        let mut cluster_total = 0usize;
        let mut clusters = Vec::new();
        let mut active: Option<DeadlineMissCluster> = None;
        let mut filtered_index = 0usize;
        for frame in &self.frames {
            let in_scope = query
                .window
                .as_ref()
                .is_none_or(|window| frame.window.as_ref() == Some(window))
                && query
                    .start_ns
                    .is_none_or(|start| frame.intended_vsync_ns.is_some_and(|time| time >= start))
                && query
                    .end_ns
                    .is_none_or(|end| frame.intended_vsync_ns.is_some_and(|time| time < end));
            if !in_scope {
                continue;
            }
            let matches_query = query
                .deadline
                .is_none_or(|deadline| frame.deadline == deadline);
            let current_filtered_index = matches_query.then_some(filtered_index);
            if matches_query {
                filtered_index = filtered_index
                    .checked_add(1)
                    .ok_or("Frame count exceeds supported bounds")?;
            }
            if frame.deadline == DeadlineVerdict::Missed {
                if allows_misses {
                    let index = current_filtered_index.expect("miss matches all/missed filter");
                    if problem_total >= problem_offset && problems.len() < problem_limit {
                        problems.push(ProblemFrame {
                            filtered_index: index,
                            frame: frame.clone(),
                        });
                    }
                    problem_total = problem_total
                        .checked_add(1)
                        .ok_or("Problem frame count exceeds supported bounds")?;
                    match &mut active {
                        Some(cluster) => {
                            cluster.last_frame_id = frame.id;
                            cluster.miss_count += 1;
                            cluster.worst_duration_ns =
                                cluster.worst_duration_ns.max(frame.duration_ns);
                        }
                        None => {
                            active = Some(DeadlineMissCluster {
                                first_frame_id: frame.id,
                                last_frame_id: frame.id,
                                first_filtered_index: index,
                                miss_count: 1,
                                worst_duration_ns: frame.duration_ns,
                            });
                        }
                    }
                }
            } else if let Some(cluster) = active.take() {
                cluster_total += 1;
                if clusters.len() < MAX_CLUSTERS {
                    clusters.push(cluster);
                }
            }
        }
        if let Some(cluster) = active {
            cluster_total += 1;
            if clusters.len() < MAX_CLUSTERS {
                clusters.push(cluster);
            }
        }
        Ok(FrameAnalysisPage {
            problem_total,
            problem_offset,
            problems,
            cluster_total,
            clusters_truncated: cluster_total > clusters.len(),
            clusters,
        })
    }
}

fn summarize<'a>(frames: impl Iterator<Item = &'a AnalyzedFrame>) -> FrameSummary {
    let mut summary = SummaryBuilder::default();
    for frame in frames {
        summary.push(frame);
    }
    summary.finish()
}

#[derive(Default)]
struct SummaryBuilder {
    summary: FrameSummary,
    durations: Vec<u64>,
}

impl SummaryBuilder {
    fn push(&mut self, frame: &AnalyzedFrame) {
        self.summary.total_frames += 1;
        match frame.deadline {
            DeadlineVerdict::Met => self.summary.classified_frames += 1,
            DeadlineVerdict::Missed => {
                self.summary.classified_frames += 1;
                self.summary.deadline_misses += 1;
            }
            DeadlineVerdict::Unknown => self.summary.unknown_deadlines += 1,
        }
        if let Some(jank) = frame.platform_jank {
            self.summary.platform_classified_frames += 1;
            self.summary.platform_jank_frames += usize::from(jank);
        }
        if let Some(duration) = frame.duration_ns {
            self.durations.push(duration);
        }
    }

    fn finish(mut self) -> FrameSummary {
        self.durations.sort_unstable();
        self.summary.deadline_miss_rate =
            ratio(self.summary.deadline_misses, self.summary.classified_frames);
        self.summary.platform_jank_rate = ratio(
            self.summary.platform_jank_frames,
            self.summary.platform_classified_frames,
        );
        self.summary.p50_duration_ns = percentile(&self.durations, 50);
        self.summary.p95_duration_ns = percentile(&self.durations, 95);
        self.summary.p99_duration_ns = percentile(&self.durations, 99);
        self.summary.worst_duration_ns = self.durations.last().copied();
        self.summary
    }
}

fn ratio(numerator: usize, denominator: usize) -> Option<f64> {
    (denominator > 0).then(|| numerator as f64 / denominator as f64)
}

fn percentile(values: &[u64], percentile: usize) -> Option<u64> {
    if values.is_empty() {
        return None;
    }
    values
        .get((values.len() * percentile).div_ceil(100).saturating_sub(1))
        .copied()
}

// Compose-parity surface. The legacy names above keep the crate root; the four
// colliding Compose types stay in their modules:
// - `aps_frame::model::FrameStages`
// - `aps_frame::analysis::{AnalyzedFrame, FrameSummary, DeadlineMissCluster}`
pub use analysis::{
    FRAME_TIMELINE_JANK_TAG, FRAME_TIMELINE_PRESENT_TYPE, FrameAnalysisResult, FrameAttribution,
    FrameAttributionAnalyzer, FrameDeadlineVerdict, FrameJankAnalyzer, FrameJankCorrelation,
    FrameJankStatsCorrelator, FrameTimelineStatus, FrameViewFilter, JankSeverity, JankStatsMatch,
    JankStatsObservation, filter_analysis, format_millis, frame_timeline_status, is_problem_frame,
};
pub use frame_timeline::{
    FrameTimelineCapabilities, FrameTimelineResult, FrameTimelineRow, map_timeline_rows,
};
pub use gfxinfo::{GfxInfoParse, parse_gfxinfo};
pub use model::{
    ExpectedDurationSource, FrameCaptureSession, FrameSample, FrameSource, FrameSourceCapabilities,
    JankType,
};

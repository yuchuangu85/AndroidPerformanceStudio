//! Immutable frame report parsed from an exact byte slice. No path or host authority.
use super::{FrameEvidence, FrameScope, input, labels, project_file};
use aps_contracts::SourceDigest;
use aps_frame::FrameSample;
use aps_frame::analysis::{
    FrameAnalysisResult, FrameDeadlineVerdict, FrameJankAnalyzer, FrameSummary,
};
use serde_json::{Value, json};

pub const ANALYSIS_POLICY_VERSION: &str = "aps-frame-v2";
pub const IMPORT_WARNING: &str = "Imported input; source labels and context are producer-provided evidence, not independently verified device provenance.";

pub struct FrameReport {
    digest: SourceDigest,
    input_format: &'static str,
    parse_warning_count: usize,
    analysis: FrameAnalysisResult,
}

impl FrameReport {
    pub fn from_bytes(bytes: &[u8]) -> Result<Self, String> {
        Ok(Self::analyze(input::parse_input(bytes, None)?))
    }

    /// Host has selected a capture receipt: match its digest and let the host
    /// check the raw rows before analysis or display-label normalization.
    pub fn from_capture_bytes(
        bytes: &[u8],
        expected: SourceDigest,
        validate_rows: impl FnOnce(&[FrameSample]) -> Result<(), String>,
    ) -> Result<Self, String> {
        let input = input::parse_input(bytes, Some(expected))?;
        validate_rows(&input.samples)?;
        Ok(Self::analyze(input))
    }

    fn analyze(mut input: input::LoadedInput) -> Self {
        labels::normalize_contexts(&mut input.samples);
        Self {
            digest: input.digest,
            input_format: input.input_format,
            parse_warning_count: input.parse_warning_count,
            analysis: FrameJankAnalyzer::new().analyze(&input.samples),
        }
    }

    pub fn digest(&self) -> SourceDigest {
        self.digest
    }
    pub fn input_format(&self) -> &'static str {
        self.input_format
    }
    pub fn parse_warning_count(&self) -> usize {
        self.parse_warning_count
    }
    pub fn analysis(&self) -> &FrameAnalysisResult {
        &self.analysis
    }
    pub fn analysis_evidence(&self, scope: FrameScope) -> Result<FrameEvidence, String> {
        project_file(&self.analysis, scope, self.digest)
    }

    /// Page through report-only row observations in analyzer order. Offset counts
    /// matching rows, not frame IDs; the exact end is a valid empty page.
    /// Row citations stay stable across pages/filters and are distinct from
    /// those for the richer selected-frame evidence.
    pub fn follow_up_rows(
        &self,
        deadline_misses_only: bool,
        offset: usize,
        limit: usize,
    ) -> Result<Value, &'static str> {
        if !(1..=16).contains(&limit) {
            return Err("Frame row limit must be between 1 and 16");
        }
        let total = if deadline_misses_only {
            self.analysis.summary.deadline_miss_frames
        } else {
            self.analysis.frames.len()
        };
        if offset > total {
            return Err("Frame row offset exceeds matching row count");
        }
        let prefix = format!("frame-row:{ANALYSIS_POLICY_VERSION}:{}:", self.digest);
        let items: Vec<_> = self
            .analysis
            .frames
            .iter()
            .filter(|frame| {
                !deadline_misses_only || frame.deadline_verdict == FrameDeadlineVerdict::Missed
            })
            .skip(offset)
            .take(limit)
            .map(|frame| {
                json!({
                    "id":format!("{prefix}{}",frame.sample.frame_id),
                    "label":"Frame observation",
                    "value":super::frame_row(frame, false, true),
                })
            })
            .collect();
        let end = offset + items.len();
        Ok(
            json!({"items":items,"total":total,"offset":offset,"nextOffset":(end < total).then_some(end)}),
        )
    }

    /// Legacy aggregate (no explicit scope). Keep its evidence section IDs stable.
    pub fn session_summary(&self) -> Value {
        json!({"kind":"frame","inputFormat":self.input_format,"summary":summary(&self.analysis.summary),
             "coverage":coverage(&self.analysis,self.analysis.frames.len()),"provenanceWarnings":[IMPORT_WARNING],
             "analysisPolicyVersion":ANALYSIS_POLICY_VERSION})
    }
}

pub fn coverage(analysis: &FrameAnalysisResult, input_frames: usize) -> Value {
    json!({
        "inputFrames":input_frames,
        "matchedFrames":analysis.frames.len(),
        "framesWithoutIntendedVsync":analysis.frames.iter().filter(|frame| frame.sample.intended_vsync_ns.is_none()).count(),
        "framesWithoutDuration":analysis.frames.iter().filter(|frame| frame.sample.resolved_duration_ns().is_none()).count()
    })
}

pub fn summary(summary: &FrameSummary) -> Value {
    json!({
        "totalFrames":summary.total_frames,
        "deadlineClassifiedFrames":summary.deadline_classified_frames,
        "deadlineMissFrames":summary.deadline_miss_frames,
        "deadlineUnknownFrames":summary.deadline_unknown_frames,
        "deadlineMissRate":summary.deadline_miss_rate,
        "platformClassifiedFrames":summary.platform_classified_frames,
        "platformJankFrames":summary.platform_jank_frames,
        "platformUnknownFrames":summary.platform_unknown_frames,
        "platformJankRate":summary.platform_jank_rate,
        "jankStatsClassifiedFrames":summary.jank_stats_classified_frames,
        "jankStatsJankFrames":summary.jank_stats_jank_frames,
        "jankStatsUnknownFrames":summary.total_frames - summary.jank_stats_classified_frames,
        "jankStatsJankRate":summary.jank_stats_jank_rate,
        "p50DurationNs":summary.p50_duration_ns,
        "p95DurationNs":summary.p95_duration_ns,
        "p99DurationNs":summary.p99_duration_ns,
        "worstDurationNs":summary.worst_duration_ns
    })
}

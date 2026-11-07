//! Compose frame-profiler model parity for `frame-model/.../FrameModels.kt`.
//!
//! These types are additive to the legacy gfxinfo-import API that still lives at
//! the crate root. The legacy `FrameStages`, `AnalyzedFrame`, `FrameSummary` and
//! `DeadlineMissCluster` keep their root names until the old import page is
//! removed; this module's Compose-faithful types are reachable as
//! `aps_frame::model::*` and are re-exported from the crate root where their
//! names do not collide.

use std::collections::{BTreeMap, BTreeSet};

/// Frame source, matching Compose `FrameSource`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum FrameSource {
    JankStats,
    FrameMetrics,
    Gfxinfo,
    Perfetto,
}

impl FrameSource {
    /// Kotlin enum `.name`, used verbatim by CSV/JSON/evidence output.
    pub const fn as_name(&self) -> &'static str {
        match self {
            Self::JankStats => "JANK_STATS",
            Self::FrameMetrics => "FRAME_METRICS",
            Self::Gfxinfo => "GFXINFO",
            Self::Perfetto => "PERFETTO",
        }
    }
}

/// Declared provenance of a frame's expected duration.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum ExpectedDurationSource {
    PlatformDeadline,
    FrameInterval,
    RefreshRate,
    InferredVsync,
    Unknown,
}

impl ExpectedDurationSource {
    /// Kotlin enum `.name`.
    pub const fn as_name(&self) -> &'static str {
        match self {
            Self::PlatformDeadline => "PLATFORM_DEADLINE",
            Self::FrameInterval => "FRAME_INTERVAL",
            Self::RefreshRate => "REFRESH_RATE",
            Self::InferredVsync => "INFERRED_VSYNC",
            Self::Unknown => "UNKNOWN",
        }
    }
}

impl Default for ExpectedDurationSource {
    fn default() -> Self {
        Self::Unknown
    }
}

/// Platform-reported jank classification, matching Compose `JankType`.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum JankType {
    PlatformReported,
    DeadlineMissed,
    SlowInput,
    SlowAnimation,
    SlowLayout,
    SlowDraw,
    SlowSync,
    SlowCommand,
    SlowSwap,
    Unknown,
}

impl JankType {
    /// Kotlin enum `.name`.
    pub const fn as_name(&self) -> &'static str {
        match self {
            Self::PlatformReported => "PLATFORM_REPORTED",
            Self::DeadlineMissed => "DEADLINE_MISSED",
            Self::SlowInput => "SLOW_INPUT",
            Self::SlowAnimation => "SLOW_ANIMATION",
            Self::SlowLayout => "SLOW_LAYOUT",
            Self::SlowDraw => "SLOW_DRAW",
            Self::SlowSync => "SLOW_SYNC",
            Self::SlowCommand => "SLOW_COMMAND",
            Self::SlowSwap => "SLOW_SWAP",
            Self::Unknown => "UNKNOWN",
        }
    }
}

/// Reported stage durations in the device monotonic nanosecond domain.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct FrameStages {
    pub input_ns: Option<i64>,
    pub animation_ns: Option<i64>,
    pub layout_measure_ns: Option<i64>,
    pub draw_ns: Option<i64>,
    pub sync_ns: Option<i64>,
    pub command_issue_ns: Option<i64>,
    pub swap_buffers_ns: Option<i64>,
    pub gpu_ns: Option<i64>,
}

impl FrameStages {
    /// Present stages in Compose order with the exact Compose labels.
    pub fn values(&self) -> Vec<(&'static str, i64)> {
        let mut values = Vec::with_capacity(8);
        if let Some(value) = self.input_ns {
            values.push(("Input", value));
        }
        if let Some(value) = self.animation_ns {
            values.push(("Animation", value));
        }
        if let Some(value) = self.layout_measure_ns {
            values.push(("Layout/Measure", value));
        }
        if let Some(value) = self.draw_ns {
            values.push(("Draw", value));
        }
        if let Some(value) = self.sync_ns {
            values.push(("Sync", value));
        }
        if let Some(value) = self.command_issue_ns {
            values.push(("Command", value));
        }
        if let Some(value) = self.swap_buffers_ns {
            values.push(("Swap", value));
        }
        if let Some(value) = self.gpu_ns {
            values.push(("GPU", value));
        }
        values
    }
}

/// One frame with explicit provenance, matching Compose `FrameSample`.
#[derive(Debug, Clone, PartialEq)]
pub struct FrameSample {
    pub frame_id: u64,
    pub session_id: String,
    pub source: FrameSource,
    pub package_name: Option<String>,
    pub process_id: Option<i32>,
    pub activity_name: Option<String>,
    pub window_id: Option<String>,
    pub intended_vsync_ns: Option<i64>,
    pub actual_vsync_ns: Option<i64>,
    pub frame_completed_ns: Option<i64>,
    pub present_ns: Option<i64>,
    pub expected_duration_ns: Option<i64>,
    pub expected_duration_source: ExpectedDurationSource,
    pub refresh_rate_hz: Option<f64>,
    pub frame_timeline_vsync_id: Option<i64>,
    pub total_duration_ns: Option<i64>,
    pub stages: FrameStages,
    pub platform_jank: Option<bool>,
    pub platform_jank_types: BTreeSet<JankType>,
    pub platform_jank_rule_id: Option<String>,
    pub platform_jank_rule_version: Option<String>,
    pub states: BTreeMap<String, String>,
    pub eligible_for_jank: bool,
    pub dropped_before_sample: i64,
    pub layout_snapshot_id: Option<String>,
    pub fragment_name: Option<String>,
    pub page_name: Option<String>,
    pub interaction_state: Option<String>,
    pub render_thread_name: Option<String>,
    pub surface_flinger_jank_type: Option<String>,
    pub jank_stats_jank: Option<bool>,
    pub jank_stats_reasons: BTreeSet<String>,
    pub jank_stats_rule_id: Option<String>,
    pub jank_stats_rule_version: Option<String>,
}

impl FrameSample {
    /// An expected duration is usable only when its positive value has declared provenance.
    pub fn known_expected_duration_ns(&self) -> Option<i64> {
        self.expected_duration_ns.filter(|value| {
            *value > 0 && self.expected_duration_source != ExpectedDurationSource::Unknown
        })
    }

    /// Reported total duration, or the completion/present minus intended-vsync difference.
    pub fn resolved_duration_ns(&self) -> Option<i64> {
        self.total_duration_ns
            .filter(|value| *value >= 0)
            .or_else(|| {
                frame_difference(
                    self.frame_completed_ns.or(self.present_ns),
                    self.intended_vsync_ns,
                )
            })
    }
}

fn frame_difference(end_ns: Option<i64>, start_ns: Option<i64>) -> Option<i64> {
    match (end_ns, start_ns) {
        (Some(end), Some(start)) if end >= start => Some(end - start),
        _ => None,
    }
}

/// Captured or imported frame session metadata, matching Compose `FrameCaptureSession`.
///
/// Compose uses `java.time.Instant`; this crate has no clock dependency, so
/// timestamps are unix epoch milliseconds and the artifact keeps its id string
/// only (the app persists artifact metadata).
#[derive(Debug, Clone, PartialEq)]
pub struct FrameCaptureSession {
    pub id: String,
    pub source: FrameSource,
    pub started_at_unix_ms: u64,
    pub package_name: Option<String>,
    pub device_serial: Option<String>,
    pub device_api_level: Option<u32>,
    pub agent_protocol: Option<String>,
    pub source_capabilities: Option<FrameSourceCapabilities>,
    pub observed_refresh_rates_hz: Vec<f64>,
    pub imported_file: Option<String>,
    pub imported_file_sha256: Option<String>,
    pub imported_at_unix_ms: Option<u64>,
    pub provenance_complete: bool,
    pub provenance_warnings: Vec<String>,
    pub perfetto_trace_file: Option<String>,
    pub artifact: Option<String>,
}

/// Source capabilities, matching Compose `FrameSourceCapabilities`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FrameSourceCapabilities {
    pub realtime: bool,
    pub stage_breakdown: bool,
    pub platform_jank_classification: bool,
    pub expected_frame_deadline: bool,
    pub app_state_labels: bool,
}

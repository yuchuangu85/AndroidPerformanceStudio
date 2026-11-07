//! Port of Compose `FrameTimelineTraceAdapter` row mapping.
//!
//! The fixed FrameTimeline SQL lives in `aps-perfetto`; this module maps its
//! rows into `FrameSample` and reports which capabilities the rows actually
//! support. No arbitrary SQL and no root-cause claims.

use crate::analysis::{FRAME_TIMELINE_JANK_TAG, FRAME_TIMELINE_PRESENT_TYPE};
use crate::model::{ExpectedDurationSource, FrameSample, FrameSource, JankType};
use std::collections::BTreeMap;

/// One Android 12+ Expected/Actual FrameTimeline row.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FrameTimelineRow {
    pub frame_id: u64,
    pub expected_ts: Option<i64>,
    pub expected_dur: Option<i64>,
    pub actual_ts: Option<i64>,
    pub actual_dur: Option<i64>,
    pub surface_token: Option<i64>,
    pub layer_name: Option<String>,
    pub jank_type: Option<String>,
    pub jank_tag: Option<String>,
    pub present_type: Option<String>,
    pub process_name: Option<String>,
    pub surface_flinger_jank_type: Option<String>,
}

/// Capabilities exposed by a mapped FrameTimeline result.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FrameTimelineCapabilities {
    pub expected_timeline: bool,
    pub actual_timeline: bool,
    pub surface_correlation: bool,
    pub jank_classification: bool,
}

impl FrameTimelineCapabilities {
    /// The full capability set before evidence-based subtraction.
    pub const fn all() -> Self {
        Self {
            expected_timeline: true,
            actual_timeline: true,
            surface_correlation: true,
            jank_classification: true,
        }
    }
}

impl Default for FrameTimelineCapabilities {
    fn default() -> Self {
        Self::all()
    }
}

/// Mapped frames plus the capabilities their evidence supports.
#[derive(Debug, Clone, PartialEq)]
pub struct FrameTimelineResult {
    pub frames: Vec<FrameSample>,
    pub capabilities: FrameTimelineCapabilities,
}

/// Port of Compose `FrameTimelineTraceAdapter.map`.
pub fn map_timeline_rows(rows: Vec<FrameTimelineRow>) -> FrameTimelineResult {
    let mut capabilities = FrameTimelineCapabilities::all();
    if !rows
        .iter()
        .any(|row| row.surface_token.is_some() && row.surface_flinger_jank_type.is_some())
    {
        capabilities.surface_correlation = false;
    }
    FrameTimelineResult {
        frames: rows.iter().map(map_row).collect(),
        capabilities,
    }
}

fn map_row(row: &FrameTimelineRow) -> FrameSample {
    let jank = row.jank_type.as_deref().filter(|jank_type| {
        !matches!(
            *jank_type,
            "None" | "On time" | "Buffer Stuffing" | "SurfaceFlinger Stuffing"
        )
    });
    let platform_jank = row.jank_type.as_ref().map(|_| jank.is_some());
    let mut states = BTreeMap::new();
    if let Some(surface_token) = row.surface_token {
        states.insert("surfaceFrameToken".to_owned(), surface_token.to_string());
    }
    if let Some(surface_flinger_jank_type) = &row.surface_flinger_jank_type {
        states.insert(
            "surfaceFlingerJankType".to_owned(),
            surface_flinger_jank_type.clone(),
        );
    }
    if let Some(jank_tag) = &row.jank_tag {
        states.insert(FRAME_TIMELINE_JANK_TAG.to_owned(), jank_tag.clone());
    }
    if let Some(present_type) = &row.present_type {
        states.insert(FRAME_TIMELINE_PRESENT_TYPE.to_owned(), present_type.clone());
    }
    let platform_jank_types = if jank.is_some() {
        [JankType::PlatformReported].into_iter().collect()
    } else {
        Default::default()
    };
    FrameSample {
        frame_id: row.frame_id,
        session_id: "perfetto".to_owned(),
        source: FrameSource::Perfetto,
        package_name: None,
        process_id: None,
        activity_name: activity_name(row),
        window_id: None,
        intended_vsync_ns: row.expected_ts,
        actual_vsync_ns: row.actual_ts,
        frame_completed_ns: None,
        present_ns: None,
        expected_duration_ns: row.expected_dur,
        expected_duration_source: ExpectedDurationSource::PlatformDeadline,
        refresh_rate_hz: None,
        frame_timeline_vsync_id: Some(row.frame_id as i64),
        total_duration_ns: row.actual_dur,
        stages: Default::default(),
        platform_jank,
        platform_jank_types,
        platform_jank_rule_id: row.jank_type.clone(),
        platform_jank_rule_version: None,
        states,
        eligible_for_jank: true,
        dropped_before_sample: 0,
        layout_snapshot_id: None,
        fragment_name: None,
        page_name: None,
        interaction_state: None,
        render_thread_name: None,
        surface_flinger_jank_type: row.surface_flinger_jank_type.clone(),
        jank_stats_jank: None,
        jank_stats_reasons: Default::default(),
        jank_stats_rule_id: None,
        jank_stats_rule_version: None,
    }
}

fn activity_name(row: &FrameTimelineRow) -> Option<String> {
    match (&row.process_name, &row.layer_name) {
        (Some(process_name), Some(layer_name)) => Some(format!("{process_name}: {layer_name}")),
        (Some(process_name), None) => Some(process_name.clone()),
        (None, Some(layer_name)) => Some(layer_name.clone()),
        (None, None) => None,
    }
}

//! Model parity tests: FrameModels.kt methods, stage order and Compose-name strings.

use aps_frame::model::FrameStages;
use aps_frame::{
    ExpectedDurationSource, FrameDeadlineVerdict, FrameSample, FrameSource,
    FrameTimelineCapabilities, FrameTimelineStatus, FrameViewFilter, JankSeverity, JankStatsMatch,
    JankType,
};
use std::collections::{BTreeMap, BTreeSet};

fn sample() -> FrameSample {
    FrameSample {
        frame_id: 1,
        session_id: "session".to_owned(),
        source: FrameSource::Gfxinfo,
        package_name: None,
        process_id: None,
        activity_name: None,
        window_id: None,
        intended_vsync_ns: Some(100),
        actual_vsync_ns: None,
        frame_completed_ns: Some(200),
        present_ns: None,
        expected_duration_ns: Some(16),
        expected_duration_source: ExpectedDurationSource::PlatformDeadline,
        refresh_rate_hz: None,
        frame_timeline_vsync_id: None,
        total_duration_ns: None,
        stages: FrameStages::default(),
        platform_jank: None,
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
        jank_stats_jank: None,
        jank_stats_reasons: BTreeSet::new(),
        jank_stats_rule_id: None,
        jank_stats_rule_version: None,
    }
}

#[test]
fn frame_stage_values_use_compose_order_and_labels() {
    let stages = FrameStages {
        input_ns: Some(1),
        animation_ns: Some(2),
        layout_measure_ns: Some(3),
        draw_ns: Some(4),
        sync_ns: Some(5),
        command_issue_ns: Some(6),
        swap_buffers_ns: Some(7),
        gpu_ns: Some(8),
    };

    assert_eq!(
        stages.values(),
        [
            ("Input", 1),
            ("Animation", 2),
            ("Layout/Measure", 3),
            ("Draw", 4),
            ("Sync", 5),
            ("Command", 6),
            ("Swap", 7),
            ("GPU", 8),
        ]
    );
    assert!(FrameStages::default().values().is_empty());
}

#[test]
fn known_expected_duration_requires_positive_value_and_provenance() {
    assert_eq!(sample().known_expected_duration_ns(), Some(16));

    let mut zero = sample();
    zero.expected_duration_ns = Some(0);
    assert_eq!(zero.known_expected_duration_ns(), None);

    let mut negative = sample();
    negative.expected_duration_ns = Some(-1);
    assert_eq!(negative.known_expected_duration_ns(), None);

    let mut unknown = sample();
    unknown.expected_duration_source = ExpectedDurationSource::Unknown;
    assert_eq!(unknown.known_expected_duration_ns(), None);
}

#[test]
fn resolved_duration_prefers_reported_total_then_completion_difference() {
    assert_eq!(sample().resolved_duration_ns(), Some(100));

    let mut reported = sample();
    reported.total_duration_ns = Some(42);
    assert_eq!(reported.resolved_duration_ns(), Some(42));

    let mut negative_total = sample();
    negative_total.total_duration_ns = Some(-1);
    assert_eq!(negative_total.resolved_duration_ns(), Some(100));

    let mut present = sample();
    present.frame_completed_ns = None;
    present.present_ns = Some(300);
    assert_eq!(present.resolved_duration_ns(), Some(200));

    let mut inverted = sample();
    inverted.frame_completed_ns = Some(50);
    assert_eq!(inverted.resolved_duration_ns(), None);

    let mut missing_start = sample();
    missing_start.intended_vsync_ns = None;
    assert_eq!(missing_start.resolved_duration_ns(), None);
}

#[test]
fn enums_expose_compose_name_strings() {
    assert_eq!(FrameSource::JankStats.as_name(), "JANK_STATS");
    assert_eq!(FrameSource::FrameMetrics.as_name(), "FRAME_METRICS");
    assert_eq!(FrameSource::Gfxinfo.as_name(), "GFXINFO");
    assert_eq!(FrameSource::Perfetto.as_name(), "PERFETTO");

    assert_eq!(
        ExpectedDurationSource::PlatformDeadline.as_name(),
        "PLATFORM_DEADLINE"
    );
    assert_eq!(
        ExpectedDurationSource::FrameInterval.as_name(),
        "FRAME_INTERVAL"
    );
    assert_eq!(
        ExpectedDurationSource::RefreshRate.as_name(),
        "REFRESH_RATE"
    );
    assert_eq!(
        ExpectedDurationSource::InferredVsync.as_name(),
        "INFERRED_VSYNC"
    );
    assert_eq!(ExpectedDurationSource::Unknown.as_name(), "UNKNOWN");
    assert_eq!(
        ExpectedDurationSource::default(),
        ExpectedDurationSource::Unknown
    );

    let jank_types = [
        (JankType::PlatformReported, "PLATFORM_REPORTED"),
        (JankType::DeadlineMissed, "DEADLINE_MISSED"),
        (JankType::SlowInput, "SLOW_INPUT"),
        (JankType::SlowAnimation, "SLOW_ANIMATION"),
        (JankType::SlowLayout, "SLOW_LAYOUT"),
        (JankType::SlowDraw, "SLOW_DRAW"),
        (JankType::SlowSync, "SLOW_SYNC"),
        (JankType::SlowCommand, "SLOW_COMMAND"),
        (JankType::SlowSwap, "SLOW_SWAP"),
        (JankType::Unknown, "UNKNOWN"),
    ];
    for (jank_type, name) in jank_types {
        assert_eq!(jank_type.as_name(), name);
    }

    assert_eq!(JankSeverity::Smooth.as_name(), "SMOOTH");
    assert_eq!(JankSeverity::Minor.as_name(), "MINOR");
    assert_eq!(JankSeverity::Major.as_name(), "MAJOR");
    assert_eq!(JankSeverity::Severe.as_name(), "SEVERE");
    assert_eq!(JankSeverity::Frozen.as_name(), "FROZEN");
    assert_eq!(JankSeverity::Unknown.as_name(), "UNKNOWN");

    assert_eq!(FrameDeadlineVerdict::Met.as_name(), "MET");
    assert_eq!(FrameDeadlineVerdict::Missed.as_name(), "MISSED");
    assert_eq!(FrameDeadlineVerdict::Unknown.as_name(), "UNKNOWN");

    assert_eq!(FrameTimelineStatus::Good.as_name(), "GOOD");
    assert_eq!(FrameTimelineStatus::HighLatency.as_name(), "HIGH_LATENCY");
    assert_eq!(FrameTimelineStatus::SelfJank.as_name(), "SELF_JANK");
    assert_eq!(FrameTimelineStatus::OtherJank.as_name(), "OTHER_JANK");
    assert_eq!(FrameTimelineStatus::Dropped.as_name(), "DROPPED");
    assert_eq!(FrameTimelineStatus::Unknown.as_name(), "UNKNOWN");

    assert_eq!(FrameViewFilter::All.as_name(), "ALL");
    assert_eq!(FrameViewFilter::JankOnly.as_name(), "JANK_ONLY");
    assert_eq!(FrameViewFilter::DeadlineMisses.as_name(), "DEADLINE_MISSES");

    assert_eq!(JankStatsMatch::FrameId.as_name(), "FRAME_ID");
    assert_eq!(JankStatsMatch::VsyncId.as_name(), "VSYNC_ID");
    assert_eq!(JankStatsMatch::Timestamp.as_name(), "TIMESTAMP");
    assert_eq!(JankStatsMatch::Unmatched.as_name(), "UNMATCHED");
}

#[test]
fn timeline_capabilities_default_to_the_full_compose_set() {
    let capabilities = FrameTimelineCapabilities::default();

    assert!(capabilities.expected_timeline);
    assert!(capabilities.actual_timeline);
    assert!(capabilities.surface_correlation);
    assert!(capabilities.jank_classification);
    assert_eq!(capabilities, FrameTimelineCapabilities::all());
}

#[test]
fn severity_ordinals_follow_compose_ordering() {
    assert!(JankSeverity::Smooth < JankSeverity::Minor);
    assert!(JankSeverity::Minor < JankSeverity::Major);
    assert!(JankSeverity::Major < JankSeverity::Severe);
    assert!(JankSeverity::Severe < JankSeverity::Frozen);
    assert!(JankSeverity::Frozen < JankSeverity::Unknown);
}

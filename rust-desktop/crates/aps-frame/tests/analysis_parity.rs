//! Ports of analysis-frame and presentation filter tests:
//! FrameJankAnalyzerTest, FrameAttributionAnalyzerTest, FrameTimelineClassificationTest,
//! FrameJankStatsCorrelatorTest, FrameAnalysisFilterTest plus formatMillis.

use aps_frame::analysis::{
    FRAME_TIMELINE_JANK_TAG, FRAME_TIMELINE_PRESENT_TYPE, FrameAttributionAnalyzer,
    FrameDeadlineVerdict, FrameJankAnalyzer, FrameJankStatsCorrelator, FrameTimelineStatus,
    FrameViewFilter, JankSeverity, JankStatsMatch, JankStatsObservation, filter_analysis,
    format_millis, frame_timeline_status,
};
use aps_frame::model::FrameStages;
use aps_frame::{ExpectedDurationSource, FrameSample, FrameSource, JankType};
use std::collections::{BTreeMap, BTreeSet};

fn base_sample(frame_id: u64, duration_ns: i64, expected_ns: i64) -> FrameSample {
    FrameSample {
        frame_id,
        session_id: "session".to_owned(),
        source: FrameSource::Gfxinfo,
        package_name: None,
        process_id: None,
        activity_name: None,
        window_id: None,
        intended_vsync_ns: Some(frame_id as i64 * expected_ns),
        actual_vsync_ns: None,
        frame_completed_ns: Some(frame_id as i64 * expected_ns + duration_ns),
        present_ns: None,
        expected_duration_ns: Some(expected_ns),
        expected_duration_source: ExpectedDurationSource::PlatformDeadline,
        refresh_rate_hz: None,
        frame_timeline_vsync_id: None,
        total_duration_ns: Some(duration_ns),
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

fn edited(mut sample: FrameSample, edit: impl FnOnce(&mut FrameSample)) -> FrameSample {
    edit(&mut sample);
    sample
}

fn jank_frame(frame_id: u64, duration_ns: i64) -> FrameSample {
    base_sample(frame_id, duration_ns, 16_666_667)
}

// ---- FrameJankAnalyzerTest ----

#[test]
fn unknown_budget_provenance_cannot_classify_a_deadline_miss() {
    let sample = edited(jank_frame(1, 20_000_000), |sample| {
        sample.expected_duration_source = ExpectedDurationSource::Unknown;
    });

    let result = FrameJankAnalyzer::default().analyze(&[sample]);

    assert_eq!(
        result.frames[0].deadline_verdict,
        FrameDeadlineVerdict::Unknown
    );
    assert_eq!(result.frames[0].missed_vsync_count, None);
    assert_eq!(result.summary.deadline_unknown_frames, 1);
    assert_eq!(result.summary.deadline_miss_rate, None);
}

#[test]
fn uses_each_frame_deadline_instead_of_a_fixed_60hz_threshold() {
    let frames = [
        base_sample(1, 10_000_000, 16_666_667),
        base_sample(2, 10_000_000, 8_333_333),
    ];

    let result = FrameJankAnalyzer::default().analyze(&frames);

    let first = result
        .frames
        .iter()
        .find(|frame| frame.sample.frame_id == 1)
        .expect("frame 1 analyzed");
    let second = result
        .frames
        .iter()
        .find(|frame| frame.sample.frame_id == 2)
        .expect("frame 2 analyzed");
    assert_eq!(first.deadline_verdict, FrameDeadlineVerdict::Met);
    assert_eq!(second.deadline_verdict, FrameDeadlineVerdict::Missed);
    assert_eq!(result.summary.deadline_miss_rate, Some(0.5));
}

#[test]
fn platform_signal_and_deadline_miss_remain_separate() {
    let platform_smooth = edited(base_sample(1, 20_000_000, 8_333_333), |sample| {
        sample.platform_jank = Some(false);
    });
    let platform_jank = edited(base_sample(2, 5_000_000, 16_666_667), |sample| {
        sample.platform_jank = Some(true);
    });

    let result = FrameJankAnalyzer::default().analyze(&[platform_smooth, platform_jank]);

    assert_eq!(
        result.frames[0].deadline_verdict,
        FrameDeadlineVerdict::Missed
    );
    assert_eq!(result.frames[0].sample.platform_jank, Some(false));
    assert_eq!(result.frames[1].deadline_verdict, FrameDeadlineVerdict::Met);
    assert_eq!(result.frames[1].sample.platform_jank, Some(true));
    assert_eq!(result.summary.deadline_miss_rate, Some(0.5));
    assert_eq!(result.summary.platform_jank_rate, Some(0.5));
}

#[test]
fn groups_nearby_jank_frames_into_one_cluster() {
    let result = FrameJankAnalyzer::default().analyze(&[
        jank_frame(1, 20_000_000),
        jank_frame(2, 5_000_000),
        jank_frame(3, 6_000_000),
        jank_frame(4, 25_000_000),
        jank_frame(5, 5_000_000),
        jank_frame(6, 5_000_000),
        jank_frame(7, 5_000_000),
        jank_frame(8, 30_000_000),
    ]);

    assert_eq!(result.clusters.len(), 2);
    assert_eq!(result.clusters[0].deadline_miss_frame_ids, [1, 4]);
    assert_eq!(result.clusters[1].deadline_miss_frame_ids, [8]);
}

#[test]
fn reports_largest_stage_without_inferring_a_root_cause() {
    let sample = edited(jank_frame(1, 30_000_000), |sample| {
        sample.stages = FrameStages {
            layout_measure_ns: Some(12_000_000),
            draw_ns: Some(4_000_000),
            ..FrameStages::default()
        };
    });

    let result = FrameJankAnalyzer::default().analyze_frame(sample);

    assert_eq!(
        result.largest_reported_stage.as_deref(),
        Some("Layout/Measure")
    );
    assert!(result.platform_jank_types.is_empty());
    assert_eq!(result.severity, JankSeverity::Minor);
}

#[test]
fn severity_uses_frozen_threshold_and_missed_vsync_counts() {
    let frozen = FrameJankAnalyzer::default().analyze_frame(jank_frame(1, 700_000_000));
    assert_eq!(frozen.severity, JankSeverity::Frozen);

    let minor = FrameJankAnalyzer::default().analyze_frame(jank_frame(1, 20_000_000));
    assert_eq!(minor.missed_vsync_count, Some(1));
    assert_eq!(minor.severity, JankSeverity::Minor);

    let severe = FrameJankAnalyzer::default().analyze_frame(jank_frame(1, 70_000_000));
    assert_eq!(severe.missed_vsync_count, Some(4));
    assert_eq!(severe.severity, JankSeverity::Severe);
}

// ---- FrameAttributionAnalyzerTest ----

fn attributed_sample(
    frame_id: u64,
    duration_ns: i64,
    activity: &str,
    window: &str,
    state: &str,
) -> FrameSample {
    edited(base_sample(frame_id, duration_ns, 16), |sample| {
        sample.source = FrameSource::JankStats;
        sample.intended_vsync_ns = Some(0);
        sample.frame_completed_ns = Some(duration_ns);
        sample.total_duration_ns = None;
        sample.expected_duration_ns = Some(16);
        sample.activity_name = Some(activity.to_owned());
        sample.window_id = Some(window.to_owned());
        sample.states = BTreeMap::from([("screen".to_owned(), state.to_owned())]);
    })
}

#[test]
fn unknown_budget_provenance_does_not_count_as_an_attributed_deadline_miss() {
    let result = FrameAttributionAnalyzer::default().analyze(&[edited(
        attributed_sample(1, 30, "Home", "window-1", "home"),
        |sample| sample.expected_duration_source = ExpectedDurationSource::Unknown,
    )]);

    assert_eq!(result[0].deadline_miss_frames, 0);
}

#[test]
fn ineligible_frame_does_not_count_as_an_attributed_deadline_miss() {
    let result = FrameAttributionAnalyzer::default().analyze(&[edited(
        attributed_sample(1, 30, "Home", "window-1", "home"),
        |sample| sample.eligible_for_jank = false,
    )]);

    assert_eq!(result[0].deadline_miss_frames, 0);
}

#[test]
fn groups_jank_by_activity_window_and_ui_state() {
    let samples = [
        attributed_sample(1, 10, "Home", "window-1", "home"),
        attributed_sample(2, 30, "Home", "window-1", "home"),
        attributed_sample(3, 15, "Details", "window-2", "details"),
    ];

    let result = FrameAttributionAnalyzer::default().analyze(&samples);

    assert_eq!(result.len(), 2);
    assert_eq!(result[0].activity_name, "Home");
    assert_eq!(result[0].deadline_miss_frames, 1);
    assert_eq!(result[1].ui_state, "details");
}

#[test]
fn attribution_counts_platform_and_jankstats_signals() {
    let sample = edited(
        attributed_sample(1, 10, "Home", "window-1", "home"),
        |sample| {
            sample.platform_jank = Some(true);
            sample
                .platform_jank_types
                .insert(JankType::PlatformReported);
            sample.jank_stats_jank = Some(true);
            sample.render_thread_name = Some("RenderThread".to_owned());
            sample.surface_flinger_jank_type =
                Some("SurfaceFlinger CPU Deadline Missed".to_owned());
        },
    );

    let result = FrameAttributionAnalyzer::default().analyze(&[sample]);

    assert_eq!(result[0].platform_jank_frames, 1);
    assert_eq!(result[0].jank_stats_frames, 1);
    assert_eq!(
        result[0].jank_types,
        BTreeSet::from(["PLATFORM_REPORTED".to_owned()])
    );
    assert_eq!(
        result[0].render_thread_names,
        BTreeSet::from(["RenderThread".to_owned()])
    );
    assert_eq!(
        result[0].surface_flinger_jank_types,
        BTreeSet::from(["SurfaceFlinger CPU Deadline Missed".to_owned()])
    );
}

// ---- FrameTimelineClassificationTest ----

fn timeline_sample(source: FrameSource) -> FrameSample {
    edited(base_sample(1, 5, 10), |sample| {
        sample.source = source;
        sample.total_duration_ns = Some(5);
        sample.expected_duration_ns = Some(10);
        sample.intended_vsync_ns = None;
        sample.frame_completed_ns = None;
    })
}

fn tagged(mut sample: FrameSample, tag: &str) -> FrameSample {
    sample
        .states
        .insert(FRAME_TIMELINE_JANK_TAG.to_owned(), tag.to_owned());
    sample
}

#[test]
fn maps_every_supported_authoritative_tag() {
    let statuses = [
        ("No Jank", FrameTimelineStatus::Good),
        ("Buffer Stuffing", FrameTimelineStatus::HighLatency),
        ("SurfaceFlinger Stuffing", FrameTimelineStatus::HighLatency),
        ("Self Jank", FrameTimelineStatus::SelfJank),
        ("Other Jank", FrameTimelineStatus::OtherJank),
        ("Dropped Frame", FrameTimelineStatus::Dropped),
    ];

    for (tag, status) in statuses {
        let frame = tagged(timeline_sample(FrameSource::Perfetto), tag);
        assert_eq!(frame_timeline_status(&frame), status, "{tag}");
    }
}

#[test]
fn authoritative_tags_win_over_conflicting_presentation_signals() {
    let statuses = [
        ("No Jank", FrameTimelineStatus::Good),
        ("Buffer Stuffing", FrameTimelineStatus::HighLatency),
        ("Self Jank", FrameTimelineStatus::SelfJank),
        ("Other Jank", FrameTimelineStatus::OtherJank),
        ("Dropped Frame", FrameTimelineStatus::Dropped),
    ];
    let sources = [
        FrameSource::JankStats,
        FrameSource::FrameMetrics,
        FrameSource::Gfxinfo,
        FrameSource::Perfetto,
    ];

    for source in sources {
        for duration in [5, 20] {
            for (tag, status) in statuses {
                let present_type = if tag == "Dropped Frame" {
                    "On-time Present"
                } else {
                    "Dropped Frame"
                };
                let mut frame = timeline_sample(source);
                frame.total_duration_ns = Some(duration);
                frame.platform_jank = Some(true);
                frame.platform_jank_rule_id = Some("Buffer Stuffing".to_owned());
                frame.jank_stats_jank = Some(true);
                frame.platform_jank_types.insert(JankType::DeadlineMissed);
                frame.surface_flinger_jank_type =
                    Some("SurfaceFlinger CPU Deadline Missed".to_owned());
                frame
                    .states
                    .insert(FRAME_TIMELINE_JANK_TAG.to_owned(), tag.to_owned());
                frame.states.insert(
                    FRAME_TIMELINE_PRESENT_TYPE.to_owned(),
                    present_type.to_owned(),
                );

                assert_eq!(
                    frame_timeline_status(&frame),
                    status,
                    "{source:?} / {tag} / {duration}"
                );
            }
        }
    }
}

#[test]
fn unsupported_present_tags_remain_unknown_without_legacy_fallback() {
    let unsupported = [
        "Unspecified",
        "Non-perceivable Jank",
        "Unknown Jank",
        "future tag",
        "",
        "no jank",
        " No Jank",
        "No Jank ",
    ];

    for tag in unsupported {
        let mut frame = timeline_sample(FrameSource::Perfetto);
        frame.platform_jank = Some(false);
        frame.platform_jank_rule_id = Some("None".to_owned());
        frame
            .states
            .insert(FRAME_TIMELINE_JANK_TAG.to_owned(), tag.to_owned());
        frame.states.insert(
            FRAME_TIMELINE_PRESENT_TYPE.to_owned(),
            "Dropped Frame".to_owned(),
        );

        assert_eq!(
            frame_timeline_status(&frame),
            FrameTimelineStatus::Unknown,
            "{tag}"
        );
    }
}

#[test]
fn legacy_perfetto_rules_accept_only_exact_neutral_or_stuffing_values() {
    let rules = [
        ("None", FrameTimelineStatus::Good),
        ("On time", FrameTimelineStatus::Good),
        ("Buffer Stuffing", FrameTimelineStatus::HighLatency),
        ("SurfaceFlinger Stuffing", FrameTimelineStatus::HighLatency),
        ("App Deadline Missed", FrameTimelineStatus::Unknown),
        (
            "SurfaceFlinger CPU Deadline Missed",
            FrameTimelineStatus::Unknown,
        ),
        (
            "App Deadline Missed, Buffer Stuffing",
            FrameTimelineStatus::Unknown,
        ),
        (
            "Buffer Stuffing, SurfaceFlinger Stuffing",
            FrameTimelineStatus::Unknown,
        ),
        ("Dropped Frame", FrameTimelineStatus::Unknown),
        ("On-time Present", FrameTimelineStatus::Unknown),
        ("On Time", FrameTimelineStatus::Unknown),
        ("None ", FrameTimelineStatus::Unknown),
    ];

    for (rule, status) in rules {
        let mut frame = timeline_sample(FrameSource::Perfetto);
        frame.total_duration_ns = Some(20);
        frame.platform_jank = Some(true);
        frame.platform_jank_rule_id = Some(rule.to_owned());

        assert_eq!(frame_timeline_status(&frame), status, "{rule}");
    }
}

#[test]
fn perfetto_missing_classification_never_falls_back_to_duration_or_booleans() {
    let frames = [
        timeline_sample(FrameSource::Perfetto),
        edited(timeline_sample(FrameSource::Perfetto), |frame| {
            frame.total_duration_ns = None;
        }),
        edited(timeline_sample(FrameSource::Perfetto), |frame| {
            frame.platform_jank = Some(false);
        }),
        edited(timeline_sample(FrameSource::Perfetto), |frame| {
            frame.jank_stats_jank = Some(false);
        }),
        edited(timeline_sample(FrameSource::Perfetto), |frame| {
            frame.states.insert(
                FRAME_TIMELINE_PRESENT_TYPE.to_owned(),
                "Dropped Frame".to_owned(),
            );
        }),
    ];

    for frame in frames {
        assert_eq!(
            frame_timeline_status(&frame),
            FrameTimelineStatus::Unknown,
            "{frame:?}"
        );
    }
}

#[test]
fn associated_surfaceflinger_jank_does_not_attribute_app_jank() {
    let frame = edited(timeline_sample(FrameSource::Perfetto), |frame| {
        frame.surface_flinger_jank_type = Some("SurfaceFlinger CPU Deadline Missed".to_owned());
    });

    assert_eq!(frame_timeline_status(&frame), FrameTimelineStatus::Unknown);
    assert_eq!(
        frame_timeline_status(&tagged(frame.clone(), "No Jank")),
        FrameTimelineStatus::Good
    );
    let mut gfxinfo = frame;
    gfxinfo.source = FrameSource::Gfxinfo;
    assert_eq!(frame_timeline_status(&gfxinfo), FrameTimelineStatus::Good);
}

#[test]
fn lost_samples_never_become_dropped_frames() {
    for source in [
        FrameSource::JankStats,
        FrameSource::FrameMetrics,
        FrameSource::Gfxinfo,
        FrameSource::Perfetto,
    ] {
        let frame = edited(timeline_sample(source), |frame| {
            frame.total_duration_ns = None;
            frame.dropped_before_sample = 5;
        });

        assert_eq!(
            frame_timeline_status(&frame),
            FrameTimelineStatus::Unknown,
            "{source:?}"
        );
        let mut neutral = frame;
        neutral.platform_jank_rule_id = Some("None".to_owned());
        neutral.platform_jank = Some(false);
        assert_eq!(frame_timeline_status(&neutral), FrameTimelineStatus::Good);
    }

    assert_eq!(
        frame_timeline_status(&tagged(
            timeline_sample(FrameSource::Perfetto),
            "Dropped Frame"
        )),
        FrameTimelineStatus::Dropped
    );
}

#[test]
fn explicit_non_jank_wins_over_duration_but_never_a_conflicting_true_signal() {
    let signals = [
        ((None, None), FrameTimelineStatus::Unknown),
        ((Some(false), None), FrameTimelineStatus::Good),
        ((None, Some(false)), FrameTimelineStatus::Good),
        ((Some(false), Some(false)), FrameTimelineStatus::Good),
        ((Some(true), None), FrameTimelineStatus::Unknown),
        ((None, Some(true)), FrameTimelineStatus::Unknown),
        ((Some(true), Some(false)), FrameTimelineStatus::Unknown),
        ((Some(false), Some(true)), FrameTimelineStatus::Unknown),
        ((Some(true), Some(true)), FrameTimelineStatus::Unknown),
    ];

    for source in [
        FrameSource::JankStats,
        FrameSource::FrameMetrics,
        FrameSource::Gfxinfo,
    ] {
        for (signal, status) in signals {
            let mut frame = timeline_sample(source);
            frame.total_duration_ns = Some(20);
            frame.platform_jank = signal.0;
            frame.jank_stats_jank = signal.1;

            assert_eq!(
                frame_timeline_status(&frame),
                status,
                "{source:?} / {signal:?}"
            );
        }
    }
}

#[test]
fn duration_fallback_requires_an_eligible_frame_and_a_known_positive_budget() {
    let base = timeline_sample(FrameSource::Gfxinfo);
    let cases = [
        (base.clone(), FrameTimelineStatus::Good),
        (
            edited(base.clone(), |frame| frame.total_duration_ns = Some(10)),
            FrameTimelineStatus::Good,
        ),
        (
            edited(base.clone(), |frame| frame.total_duration_ns = Some(11)),
            FrameTimelineStatus::Unknown,
        ),
        (
            edited(base.clone(), |frame| {
                frame.total_duration_ns = None;
                frame.intended_vsync_ns = Some(10);
                frame.frame_completed_ns = Some(15);
            }),
            FrameTimelineStatus::Good,
        ),
        (
            edited(base.clone(), |frame| frame.total_duration_ns = None),
            FrameTimelineStatus::Unknown,
        ),
        (
            edited(base.clone(), |frame| frame.total_duration_ns = Some(-1)),
            FrameTimelineStatus::Unknown,
        ),
        (
            edited(base.clone(), |frame| frame.expected_duration_ns = None),
            FrameTimelineStatus::Unknown,
        ),
        (
            edited(base.clone(), |frame| frame.expected_duration_ns = Some(0)),
            FrameTimelineStatus::Unknown,
        ),
        (
            edited(base.clone(), |frame| frame.expected_duration_ns = Some(-1)),
            FrameTimelineStatus::Unknown,
        ),
        (
            edited(base.clone(), |frame| {
                frame.expected_duration_source = ExpectedDurationSource::Unknown;
            }),
            FrameTimelineStatus::Unknown,
        ),
        (
            edited(base.clone(), |frame| frame.eligible_for_jank = false),
            FrameTimelineStatus::Unknown,
        ),
    ];

    for source in [
        FrameSource::JankStats,
        FrameSource::FrameMetrics,
        FrameSource::Gfxinfo,
    ] {
        for (frame, status) in &cases {
            let mut frame = frame.clone();
            frame.source = source;
            assert_eq!(
                frame_timeline_status(&frame),
                *status,
                "{source:?} / {frame:?}"
            );
        }
    }
}

#[test]
fn unattributed_jank_reports_stay_unknown_even_within_budget() {
    let cases = [
        edited(timeline_sample(FrameSource::Gfxinfo), |frame| {
            frame.platform_jank = Some(true);
        }),
        edited(timeline_sample(FrameSource::Gfxinfo), |frame| {
            frame.jank_stats_jank = Some(true);
        }),
        edited(timeline_sample(FrameSource::Gfxinfo), |frame| {
            frame.platform_jank_types.insert(JankType::PlatformReported);
        }),
        edited(timeline_sample(FrameSource::Gfxinfo), |frame| {
            frame.platform_jank_types.insert(JankType::DeadlineMissed);
        }),
        edited(timeline_sample(FrameSource::Gfxinfo), |frame| {
            frame
                .jank_stats_reasons
                .insert("App Deadline Missed".to_owned());
        }),
    ];

    for source in [
        FrameSource::JankStats,
        FrameSource::FrameMetrics,
        FrameSource::Gfxinfo,
    ] {
        for frame in &cases {
            let mut frame = frame.clone();
            frame.source = source;
            assert_eq!(
                frame_timeline_status(&frame),
                FrameTimelineStatus::Unknown,
                "{source:?} / {frame:?}"
            );
        }
    }
}

// ---- FrameJankStatsCorrelatorTest ----

fn observation() -> JankStatsObservation {
    JankStatsObservation {
        frame_id: None,
        frame_timeline_vsync_id: None,
        timestamp_ns: None,
        is_jank: false,
        reasons: BTreeSet::new(),
        rule_id: None,
        rule_version: None,
        activity_name: None,
        fragment_name: None,
        page_name: None,
        interaction_state: None,
        render_thread_name: None,
    }
}

#[test]
fn prefers_vsync_identity_and_enriches_context() {
    let frame = edited(base_sample(7, 5, 10), |frame| {
        frame.source = FrameSource::Perfetto;
        frame.frame_timeline_vsync_id = Some(42);
        frame.intended_vsync_ns = Some(1_000_000);
        frame.total_duration_ns = None;
        frame.frame_completed_ns = None;
    });
    let observation = JankStatsObservation {
        frame_timeline_vsync_id: Some(42),
        timestamp_ns: Some(1_100_000),
        is_jank: true,
        rule_id: Some("jankstats-default".to_owned()),
        rule_version: Some("1.0.0".to_owned()),
        fragment_name: Some("FeedFragment".to_owned()),
        interaction_state: Some("scroll".to_owned()),
        ..observation()
    };

    let result = FrameJankStatsCorrelator::default().correlate(&[frame], &[observation]);

    assert_eq!(result[0].matched_by, JankStatsMatch::VsyncId);
    assert_eq!(result[0].frame.jank_stats_jank, Some(true));
    assert_eq!(
        result[0].frame.fragment_name.as_deref(),
        Some("FeedFragment")
    );
    assert_eq!(result[0].frame.interaction_state.as_deref(), Some("scroll"));
    assert_eq!(
        result[0].frame.jank_stats_rule_id.as_deref(),
        Some("jankstats-default")
    );
    assert_eq!(
        result[0].frame.jank_stats_rule_version.as_deref(),
        Some("1.0.0")
    );
}

#[test]
fn does_not_reuse_one_observation_for_duplicate_frame_identities() {
    let frame = edited(base_sample(7, 5, 10), |frame| {
        frame.source = FrameSource::Perfetto;
        frame.total_duration_ns = None;
        frame.frame_completed_ns = None;
    });
    let frames = [frame.clone(), frame];
    let observation = JankStatsObservation {
        frame_id: Some(7),
        is_jank: true,
        ..observation()
    };

    let results = FrameJankStatsCorrelator::default().correlate(&frames, &[observation]);

    assert_eq!(results[0].matched_by, JankStatsMatch::FrameId);
    assert_eq!(results[1].matched_by, JankStatsMatch::Unmatched);
    assert_eq!(results[1].jank_stats, None);
}

#[test]
fn uses_bounded_timestamp_matching_only_as_derived_evidence() {
    let frame = edited(base_sample(1, 5, 10), |frame| {
        frame.source = FrameSource::Perfetto;
        frame.actual_vsync_ns = Some(10_000_000);
        frame.total_duration_ns = None;
        frame.frame_completed_ns = None;
    });
    let observation = JankStatsObservation {
        timestamp_ns: Some(9_750_000),
        is_jank: false,
        ..observation()
    };

    let result = FrameJankStatsCorrelator::new(500_000).correlate(&[frame], &[observation]);

    assert_eq!(result[0].matched_by, JankStatsMatch::Timestamp);
    assert_eq!(result[0].time_delta_ns, Some(250_000));
}

// ---- FrameAnalysisFilterTest ----

fn filter_sample(frame_id: u64, source: FrameSource, duration_ns: i64) -> FrameSample {
    edited(base_sample(frame_id, duration_ns, 16_666_667), |frame| {
        frame.source = source;
        frame.intended_vsync_ns = Some(frame_id as i64 * 20_000_000);
        frame.expected_duration_ns = Some(16_666_667);
        frame.expected_duration_source = ExpectedDurationSource::FrameInterval;
        frame.total_duration_ns = Some(duration_ns);
    })
}

#[test]
fn source_and_problem_filters_preserve_only_matching_evidence() {
    let frames = [
        filter_sample(1, FrameSource::Gfxinfo, 8_000_000),
        filter_sample(2, FrameSource::Perfetto, 24_000_000),
        edited(
            filter_sample(3, FrameSource::Perfetto, 8_000_000),
            |frame| {
                frame.platform_jank = Some(true);
            },
        ),
    ];
    let analysis = FrameJankAnalyzer::default().analyze(&frames);

    let filtered = filter_analysis(
        &analysis,
        Some(FrameSource::Perfetto),
        FrameViewFilter::JankOnly,
    );

    assert_eq!(
        filtered
            .frames
            .iter()
            .map(|frame| frame.sample.frame_id)
            .collect::<Vec<_>>(),
        [2, 3]
    );
    assert!(filtered.frames.iter().all(aps_frame::is_problem_frame));
    assert_eq!(filtered.summary.deadline_miss_frames, 1);
}

#[test]
fn unknown_budget_remains_unknown_after_filtering() {
    let analysis = FrameJankAnalyzer::default().analyze(&[edited(
        filter_sample(1, FrameSource::Gfxinfo, 20_000_000),
        |frame| frame.expected_duration_source = ExpectedDurationSource::Unknown,
    )]);

    let filtered = filter_analysis(&analysis, None, FrameViewFilter::DeadlineMisses);

    assert!(filtered.frames.is_empty());
    assert!(filtered.clusters.is_empty());
}

#[test]
fn filtering_does_not_merge_clusters_across_excluded_frames() {
    let analysis = FrameJankAnalyzer::with_max_smooth_gap(1).analyze(&[
        filter_sample(1, FrameSource::Perfetto, 24_000_000),
        filter_sample(2, FrameSource::Gfxinfo, 8_000_000),
        filter_sample(3, FrameSource::Gfxinfo, 8_000_000),
        filter_sample(4, FrameSource::Perfetto, 24_000_000),
    ]);

    let filtered = filter_analysis(
        &analysis,
        Some(FrameSource::Perfetto),
        FrameViewFilter::DeadlineMisses,
    );

    assert_eq!(analysis.clusters.len(), 2);
    assert_eq!(filtered.clusters.len(), 2);
    assert_eq!(
        filtered
            .clusters
            .iter()
            .map(|cluster| cluster.deadline_miss_frame_ids.clone())
            .collect::<Vec<_>>(),
        [vec![1], vec![4]]
    );
}

#[test]
fn all_frames_filter_returns_the_analysis_unchanged() {
    let analysis =
        FrameJankAnalyzer::default().analyze(&[filter_sample(1, FrameSource::Gfxinfo, 8_000_000)]);

    let filtered = filter_analysis(&analysis, None, FrameViewFilter::All);

    assert_eq!(filtered, analysis);
}

// ---- Compose formatMillis ----

#[test]
fn formats_millis_like_compose_frame_profiler_screen() {
    assert_eq!(format_millis(None), "\u{2014}");
    assert_eq!(format_millis(Some(0)), "0.00 ms");
    assert_eq!(format_millis(Some(8_333_333)), "8.33 ms");
    assert_eq!(format_millis(Some(-1_000_000)), "-1.00 ms");
}

//! Single-frame evidence projection for the detail view.

use super::model;
use aps_frame::FrameSource;
use aps_frame::analysis::AnalyzedFrame;
use aps_ui_foundation::{Language, TextKey};

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub(super) enum DetailSectionKey {
    Summary,
    Timing,
    Stages,
    Jank,
    Context,
}

pub(super) struct DetailRow {
    pub(super) label: String,
    pub(super) value: String,
}

pub(super) struct DetailSection {
    pub(super) key: DetailSectionKey,
    pub(super) title: TextKey,
    pub(super) rows: Vec<DetailRow>,
}

fn row(language: Language, key: TextKey, value: impl Into<String>) -> DetailRow {
    DetailRow {
        label: language.text(key).to_owned(),
        value: value.into(),
    }
}

fn optional<T: std::fmt::Display>(value: Option<T>) -> String {
    value.map_or_else(|| "—".to_owned(), |value| value.to_string())
}

fn timestamp(value: Option<i64>) -> String {
    value.map_or_else(|| "—".to_owned(), |value| format!("{value} ns"))
}

pub(super) fn frame_detail_sections(
    frame: &AnalyzedFrame,
    language: Language,
) -> Vec<DetailSection> {
    let sample = &frame.sample;
    let duration = sample.resolved_duration_ns();
    let budget = sample.known_expected_duration_ns();
    let over_budget = duration
        .zip(budget)
        .and_then(|(duration, budget)| duration.checked_sub(budget))
        .map(|value| value.max(0));
    let refresh = sample
        .refresh_rate_hz
        .filter(|rate| rate.is_finite() && *rate > 0.0)
        .map_or_else(|| "—".into(), |rate| format!("{rate:.2} Hz"));
    let summary = vec![
        row(
            language,
            TextKey::FrameDuration,
            model::format_millis(duration),
        ),
        row(
            language,
            TextKey::FrameBudget,
            model::display_budget(sample),
        ),
        row(
            language,
            TextKey::FrameDetailOverBudget,
            model::format_millis(over_budget),
        ),
        row(
            language,
            TextKey::FrameBudgetSource,
            sample.expected_duration_source.as_name(),
        ),
        row(
            language,
            TextKey::FrameVerdict,
            frame.deadline_verdict.as_name(),
        ),
        row(
            language,
            TextKey::FrameMissedVsync,
            optional(frame.missed_vsync_count),
        ),
        row(language, TextKey::FrameDetailRefreshRate, refresh),
        row(
            language,
            TextKey::FrameLargestReportedStage,
            optional(frame.largest_reported_stage.as_deref()),
        ),
    ];
    let timing = vec![
        row(
            language,
            TextKey::FrameDetailIntendedVsync,
            timestamp(sample.intended_vsync_ns),
        ),
        row(
            language,
            TextKey::FrameDetailActualVsync,
            timestamp(sample.actual_vsync_ns),
        ),
        row(
            language,
            TextKey::FrameDetailCompleted,
            timestamp(sample.frame_completed_ns),
        ),
        row(
            language,
            TextKey::FrameDetailPresented,
            timestamp(sample.present_ns),
        ),
        row(
            language,
            if sample.source == FrameSource::Perfetto {
                // FrameTimeline rows carry the display frame token, not a vsync id.
                TextKey::FrameDisplayFrameToken
            } else {
                TextKey::FrameTimelineVsyncId
            },
            optional(sample.frame_timeline_vsync_id),
        ),
    ];
    let mut stages: Vec<DetailRow> = sample
        .stages
        .values()
        .into_iter()
        .map(|(label, duration)| DetailRow {
            label: label.to_owned(),
            value: model::format_millis(Some(duration)),
        })
        .collect();
    if stages.is_empty() {
        stages.push(row(language, TextKey::FrameDetailStagesUnavailable, "—"));
    }
    let types = if frame.platform_jank_types.is_empty() {
        "—".into()
    } else {
        frame
            .platform_jank_types
            .iter()
            .map(|kind| model::jank_type_display(*kind, language))
            .collect::<Vec<_>>()
            .join(", ")
    };
    let reasons = if sample.jank_stats_reasons.is_empty() {
        "—".into()
    } else {
        sample
            .jank_stats_reasons
            .iter()
            .cloned()
            .collect::<Vec<_>>()
            .join(", ")
    };
    let jank = vec![
        row(
            language,
            TextKey::FramePlatformJank,
            optional(sample.platform_jank),
        ),
        row(language, TextKey::FrameDetailNoJankTypes, types),
        row(
            language,
            TextKey::FrameDetailPlatformRule,
            optional(sample.platform_jank_rule_id.as_deref()),
        ),
        row(
            language,
            TextKey::FrameDetailPlatformRuleVersion,
            optional(sample.platform_jank_rule_version.as_deref()),
        ),
        row(
            language,
            TextKey::FrameSurfaceFlingerJank,
            optional(sample.surface_flinger_jank_type.as_deref()),
        ),
        row(
            language,
            TextKey::FrameJankStats,
            optional(sample.jank_stats_jank),
        ),
        row(language, TextKey::FrameJankStatsReasons, reasons),
        row(
            language,
            TextKey::FrameDetailJankstatsRule,
            optional(sample.jank_stats_rule_id.as_deref()),
        ),
        row(
            language,
            TextKey::FrameDetailJankstatsRuleVersion,
            optional(sample.jank_stats_rule_version.as_deref()),
        ),
    ];
    let dropped = if sample.source == FrameSource::FrameMetrics && sample.dropped_before_sample > 0
    {
        optional(Some(sample.dropped_before_sample))
    } else {
        "—".into()
    };
    let mut context = vec![
        row(
            language,
            TextKey::FrameSource,
            model::source_display(language, sample.source),
        ),
        row(language, TextKey::FrameSessionId, sample.session_id.clone()),
        row(
            language,
            TextKey::FrameDetailPackage,
            optional(sample.package_name.as_deref()),
        ),
        row(
            language,
            TextKey::FrameDetailProcessId,
            optional(sample.process_id),
        ),
        row(
            language,
            TextKey::FrameDetailActivity,
            optional(sample.activity_name.as_deref()),
        ),
        row(
            language,
            TextKey::FrameWindow,
            optional(sample.window_id.as_deref()),
        ),
        row(
            language,
            TextKey::FrameFragment,
            optional(sample.fragment_name.as_deref()),
        ),
        row(
            language,
            TextKey::FramePage,
            optional(sample.page_name.as_deref()),
        ),
        row(
            language,
            TextKey::FrameInteractionState,
            optional(sample.interaction_state.as_deref()),
        ),
        row(
            language,
            TextKey::FrameRenderThread,
            optional(sample.render_thread_name.as_deref()),
        ),
        row(language, TextKey::FrameDetailDroppedSamples, dropped),
    ];
    context.extend(sample.states.iter().map(|(key, value)| DetailRow {
        label: language.format(TextKey::FrameStateDetail, &[("value1", key)]),
        value: value.clone(),
    }));
    vec![
        DetailSection {
            key: DetailSectionKey::Summary,
            title: TextKey::FrameDetailSummary,
            rows: summary,
        },
        DetailSection {
            key: DetailSectionKey::Timing,
            title: TextKey::FrameDetailTiming,
            rows: timing,
        },
        DetailSection {
            key: DetailSectionKey::Stages,
            title: TextKey::FrameDetailStages,
            rows: stages,
        },
        DetailSection {
            key: DetailSectionKey::Jank,
            title: TextKey::FrameDetailJank,
            rows: jank,
        },
        DetailSection {
            key: DetailSectionKey::Context,
            title: TextKey::FrameDetailContext,
            rows: context,
        },
    ]
}

pub(super) fn copy_detail_evidence(frame: &AnalyzedFrame, language: Language) -> String {
    let mut output = vec![format!(
        "{}: #{}",
        language.text(TextKey::FrameFrame),
        frame.sample.frame_id
    )];
    for section in frame_detail_sections(frame, language) {
        output.push(format!("{}:", language.text(section.title)));
        for row in section.rows {
            output.push(format!(
                "  {}: {}",
                model::escape_evidence_field(&row.label),
                model::escape_evidence_field(&row.value)
            ));
        }
    }
    output.join("\n")
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_frame::{ExpectedDurationSource, FrameJankAnalyzer, FrameSample};
    use std::collections::{BTreeMap, BTreeSet};

    fn sample() -> FrameSample {
        FrameSample {
            frame_id: 1,
            session_id: "session".into(),
            source: FrameSource::FrameMetrics,
            package_name: Some("example.app".into()),
            process_id: Some(42),
            activity_name: Some("example.app.MainActivity".into()),
            window_id: None,
            intended_vsync_ns: Some(123456789),
            actual_vsync_ns: None,
            frame_completed_ns: None,
            present_ns: None,
            expected_duration_ns: Some(16_000_000),
            expected_duration_source: ExpectedDurationSource::FrameInterval,
            refresh_rate_hz: Some(60.0),
            frame_timeline_vsync_id: None,
            total_duration_ns: Some(20_000_000),
            stages: Default::default(),
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

    fn value(frame: &aps_frame::analysis::AnalyzedFrame, key: TextKey) -> String {
        frame_detail_sections(frame, Language::English)
            .into_iter()
            .flat_map(|section| section.rows)
            .find(|row| row.label == Language::English.text(key))
            .expect("detail field")
            .value
    }

    #[test]
    fn reports_only_known_timing_and_budget_evidence() {
        let sample = sample();
        let analyzer = FrameJankAnalyzer::default();
        let frame = analyzer.analyze_frame(sample.clone());
        assert_eq!(
            value(&frame, TextKey::FrameDetailIntendedVsync),
            "123456789 ns"
        );
        assert_eq!(value(&frame, TextKey::FrameDetailActualVsync), "—");
        assert_eq!(value(&frame, TextKey::FrameDetailOverBudget), "4.00 ms");
        assert_eq!(value(&frame, TextKey::FrameDetailRefreshRate), "60.00 Hz");
        assert_eq!(value(&frame, TextKey::FrameDetailPackage), "example.app");
        assert_eq!(
            value(&frame, TextKey::FrameDetailActivity),
            "example.app.MainActivity"
        );
        let unknown = analyzer.analyze_frame(FrameSample {
            expected_duration_source: ExpectedDurationSource::Unknown,
            refresh_rate_hz: Some(f64::NAN),
            ..sample
        });
        assert_eq!(value(&unknown, TextKey::FrameDetailOverBudget), "—");
        assert_eq!(value(&unknown, TextKey::FrameDetailRefreshRate), "—");
    }

    #[test]
    fn perfetto_frames_label_the_row_as_a_display_frame_token() {
        let analyzer = FrameJankAnalyzer::default();
        let perfetto = analyzer.analyze_frame(FrameSample {
            source: FrameSource::Perfetto,
            frame_timeline_vsync_id: Some(3248292),
            ..sample()
        });
        let rows: Vec<_> = frame_detail_sections(&perfetto, Language::English)
            .into_iter()
            .flat_map(|section| section.rows)
            .collect();
        assert!(rows.iter().any(|row| {
            row.label == Language::English.text(TextKey::FrameDisplayFrameToken)
                && row.value == "3248292"
        }));
        assert!(
            !rows
                .iter()
                .any(|row| row.label == Language::English.text(TextKey::FrameTimelineVsyncId))
        );

        let metrics = analyzer.analyze_frame(FrameSample {
            frame_timeline_vsync_id: Some(42),
            ..sample()
        });
        let rows: Vec<_> = frame_detail_sections(&metrics, Language::English)
            .into_iter()
            .flat_map(|section| section.rows)
            .collect();
        assert!(rows.iter().any(|row| {
            row.label == Language::English.text(TextKey::FrameTimelineVsyncId) && row.value == "42"
        }));
    }

    #[test]
    fn copied_detail_includes_new_fields_and_escapes_state_values() {
        let mut sample = sample();
        sample.states.insert("page".into(), "one\ntwo".into());
        let frame = FrameJankAnalyzer::default().analyze_frame(sample);
        let copy = copy_detail_evidence(&frame, Language::English);
        assert!(copy.contains("123456789 ns"));
        assert!(copy.contains("example.app.MainActivity"));
        assert!(copy.contains("one\\ntwo"));
        assert!(!copy.contains("one\ntwo"));
    }
}

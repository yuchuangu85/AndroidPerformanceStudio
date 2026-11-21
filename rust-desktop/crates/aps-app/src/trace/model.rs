//! Trace Analyzer workspace model, mirroring Compose TraceAnalyzerWorkspaceModel.kt.

/// Severity order is meaningful: lower is more severe (Compose ordinal order).
#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub(in crate::trace) enum TraceInsightSeverity {
    Critical,
    Warning,
    Info,
    Opportunity,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub(in crate::trace) enum TraceInsightCategory {
    Frame,
    Binder,
    LongTask,
    Rendering,
    Cpu,
    Memory,
    Input,
    Other,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(in crate::trace) enum TraceInsightFilter {
    #[default]
    All,
    Critical,
    Warning,
    Info,
}
impl TraceInsightFilter {
    pub(in crate::trace) const ALL: [Self; 4] =
        [Self::All, Self::Critical, Self::Warning, Self::Info];
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(in crate::trace) enum TraceDetailTab {
    #[default]
    Result,
    Query,
    Search,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(in crate::trace) struct TraceWorkspaceArtifact {
    pub(in crate::trace) artifact_id: String,
    pub(in crate::trace) display_name: String,
    pub(in crate::trace) location_label: String,
    pub(in crate::trace) completeness_label: String,
    pub(in crate::trace) limitations: Vec<String>,
    pub(in crate::trace) viewer_available: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(in crate::trace) struct TraceWorkspaceSession {
    pub(in crate::trace) session_id: String,
    pub(in crate::trace) title: String,
    pub(in crate::trace) captured_at_label: String,
    pub(in crate::trace) duration_label: String,
    pub(in crate::trace) artifact: Option<TraceWorkspaceArtifact>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(in crate::trace) struct TraceInsightEvidence {
    pub(in crate::trace) query_id: String,
    pub(in crate::trace) artifact_id: String,
    pub(in crate::trace) title: String,
    pub(in crate::trace) result_rows_summary: Option<String>,
    pub(in crate::trace) limitations: Vec<String>,
    pub(in crate::trace) error: Option<String>,
    pub(in crate::trace) timestamp_nanos: Option<i64>,
    pub(in crate::trace) severity: TraceInsightSeverity,
    pub(in crate::trace) category: TraceInsightCategory,
    pub(in crate::trace) aggregate: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(in crate::trace) struct TraceDiagnosticPresetOption {
    pub(in crate::trace) id: String,
    pub(in crate::trace) title: String,
    pub(in crate::trace) query_ids: Vec<String>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(in crate::trace) struct TraceResultTable {
    pub(in crate::trace) query_id: String,
    pub(in crate::trace) artifact_id: String,
    pub(in crate::trace) title: String,
    pub(in crate::trace) description: String,
    pub(in crate::trace) sql: String,
    pub(in crate::trace) schema_version: String,
    pub(in crate::trace) columns: Vec<String>,
    pub(in crate::trace) rows: Vec<Vec<String>>,
    pub(in crate::trace) row_timestamps_nanos: Vec<Option<i64>>,
    pub(in crate::trace) truncated: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(in crate::trace) struct TraceSearchHit {
    pub(in crate::trace) timestamp_nanos: i64,
    pub(in crate::trace) duration_nanos: Option<i64>,
    pub(in crate::trace) name: String,
    pub(in crate::trace) thread_label: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(in crate::trace) struct TraceWorkspaceBookmark {
    pub(in crate::trace) bookmark_id: String,
    pub(in crate::trace) label: String,
    pub(in crate::trace) timestamp_nanos: i64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(in crate::trace) struct TraceBookmarkRequest {
    pub(in crate::trace) label: String,
    pub(in crate::trace) timestamp_nanos: i64,
    pub(in crate::trace) source_query_id: Option<String>,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(in crate::trace) struct TraceAnalyzerWorkspaceState {
    pub(in crate::trace) sessions: Vec<TraceWorkspaceSession>,
    pub(in crate::trace) selected_session_id: Option<String>,
    pub(in crate::trace) search_query: String,
    pub(in crate::trace) active_trace: Option<TraceWorkspaceArtifact>,
    pub(in crate::trace) insights: Vec<TraceInsightEvidence>,
    pub(in crate::trace) selected_query_id: Option<String>,
    pub(in crate::trace) capture_visible: bool,
    pub(in crate::trace) status: Option<String>,
    pub(in crate::trace) error: Option<String>,
    pub(in crate::trace) presets: Vec<TraceDiagnosticPresetOption>,
    pub(in crate::trace) selected_preset_id: Option<String>,
    pub(in crate::trace) preset_running: bool,
    pub(in crate::trace) insight_filter: TraceInsightFilter,
    pub(in crate::trace) detail_tab: TraceDetailTab,
    pub(in crate::trace) result_table: Option<TraceResultTable>,
    pub(in crate::trace) trace_search_query: String,
    pub(in crate::trace) searched_term: Option<String>,
    pub(in crate::trace) search_hits: Vec<TraceSearchHit>,
    pub(in crate::trace) search_running: bool,
    pub(in crate::trace) search_error: Option<String>,
    pub(in crate::trace) bookmarks: Vec<TraceWorkspaceBookmark>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub(in crate::trace) struct TraceWorkspaceSummary {
    pub(in crate::trace) category: TraceInsightCategory,
    pub(in crate::trace) value: Option<String>,
    pub(in crate::trace) severity: Option<TraceInsightSeverity>,
    pub(in crate::trace) source_title: Option<String>,
}

pub(in crate::trace) const SUMMARY_CATEGORIES: [TraceInsightCategory; 4] = [
    TraceInsightCategory::Frame,
    TraceInsightCategory::Binder,
    TraceInsightCategory::LongTask,
    TraceInsightCategory::Rendering,
];

pub(in crate::trace) fn filter_trace_workspace_sessions(
    sessions: &[TraceWorkspaceSession],
    search_query: &str,
) -> Vec<TraceWorkspaceSession> {
    let query = search_query.trim();
    if query.is_empty() {
        return sessions.to_vec();
    }
    let needle = query.to_lowercase();
    sessions
        .iter()
        .filter(|session| {
            [
                Some(session.title.as_str()),
                Some(session.captured_at_label.as_str()),
                Some(session.duration_label.as_str()),
                session.artifact.as_ref().map(|a| a.artifact_id.as_str()),
                session.artifact.as_ref().map(|a| a.display_name.as_str()),
            ]
            .into_iter()
            .flatten()
            .any(|value| value.to_lowercase().contains(&needle))
        })
        .cloned()
        .collect()
}

fn summary_value(insight: &TraceInsightEvidence) -> Option<&str> {
    insight
        .aggregate
        .as_deref()
        .filter(|value| !value.trim().is_empty())
        .or_else(|| {
            insight
                .result_rows_summary
                .as_deref()
                .filter(|value| !value.trim().is_empty())
        })
}

/// Each summary card shows the most severe successful evidence in its category.
pub(in crate::trace) fn build_trace_workspace_summaries(
    insights: &[TraceInsightEvidence],
) -> Vec<TraceWorkspaceSummary> {
    SUMMARY_CATEGORIES
        .into_iter()
        .map(|category| {
            let evidence = insights
                .iter()
                .filter(|insight| {
                    insight.category == category
                        && insight.error.is_none()
                        && summary_value(insight).is_some()
                })
                .min_by_key(|insight| insight.severity);
            TraceWorkspaceSummary {
                category,
                value: evidence.and_then(|insight| summary_value(insight).map(str::to_owned)),
                severity: evidence.map(|insight| insight.severity),
                source_title: evidence.map(|insight| insight.title.clone()),
            }
        })
        .collect()
}

pub(in crate::trace) fn can_open_insight_in_timeline(
    insight: &TraceInsightEvidence,
    active_trace: Option<&TraceWorkspaceArtifact>,
) -> bool {
    insight.timestamp_nanos.is_some()
        && insight.error.is_none()
        && active_trace.is_some_and(|active| {
            active.viewer_available && active.artifact_id == insight.artifact_id
        })
}

fn insight_matches(insight: &TraceInsightEvidence, filter: TraceInsightFilter) -> bool {
    match filter {
        TraceInsightFilter::All => true,
        TraceInsightFilter::Critical => insight.severity == TraceInsightSeverity::Critical,
        TraceInsightFilter::Warning => insight.severity == TraceInsightSeverity::Warning,
        TraceInsightFilter::Info => matches!(
            insight.severity,
            TraceInsightSeverity::Info | TraceInsightSeverity::Opportunity
        ),
    }
}

/// Insights belonging to the selected preset (all when unknown), then the severity filter.
pub(in crate::trace) fn visible_trace_insights(
    insights: &[TraceInsightEvidence],
    presets: &[TraceDiagnosticPresetOption],
    selected_preset_id: Option<&str>,
    filter: TraceInsightFilter,
) -> Vec<TraceInsightEvidence> {
    let preset = selected_preset_id.and_then(|id| presets.iter().find(|preset| preset.id == id));
    let candidates: Vec<&TraceInsightEvidence> = match preset {
        Some(preset) => insights
            .iter()
            .filter(|insight| preset.query_ids.iter().any(|id| id == &insight.query_id))
            .collect(),
        None => insights.iter().collect(),
    };
    candidates
        .into_iter()
        .filter(|insight| insight_matches(insight, filter))
        .cloned()
        .collect()
}

pub(in crate::trace) fn trace_insight_counts(
    insights: &[TraceInsightEvidence],
) -> [(TraceInsightFilter, usize); 4] {
    TraceInsightFilter::ALL.map(|filter| {
        (
            filter,
            insights
                .iter()
                .filter(|insight| insight_matches(insight, filter))
                .count(),
        )
    })
}

/// Locatable insights in trace order; anchors into Perfetto, not a synthetic timeline.
pub(in crate::trace) fn trace_timeline_anchors(
    insights: &[TraceInsightEvidence],
    active_trace: Option<&TraceWorkspaceArtifact>,
) -> Vec<TraceInsightEvidence> {
    let mut anchors: Vec<TraceInsightEvidence> = insights
        .iter()
        .filter(|insight| can_open_insight_in_timeline(insight, active_trace))
        .cloned()
        .collect();
    anchors.sort_by_key(|insight| insight.timestamp_nanos.unwrap_or(i64::MAX));
    anchors
}

/// Trace timestamps are absolute trace-clock values, shown in seconds.
pub(in crate::trace) fn format_trace_timestamp(timestamp_nanos: i64) -> String {
    format!("{:.6} s", timestamp_nanos as f64 / 1_000_000_000.0)
}

pub(in crate::trace) fn format_trace_duration_nanos(duration_nanos: Option<i64>) -> String {
    match duration_nanos {
        Some(value) if value >= 0 => {
            let millis = value as f64 / 1_000_000.0;
            if millis >= 1_000.0 {
                format!("{:.3} s", millis / 1_000.0)
            } else {
                format!("{:.3} ms", millis)
            }
        }
        _ => "—".to_owned(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn insight(
        query: &str,
        category: TraceInsightCategory,
        severity: TraceInsightSeverity,
        aggregate: Option<&str>,
    ) -> TraceInsightEvidence {
        TraceInsightEvidence {
            query_id: query.into(),
            artifact_id: "artifact-1".into(),
            title: format!("{query} title"),
            result_rows_summary: Some("3 rows".into()),
            limitations: Vec::new(),
            error: None,
            timestamp_nanos: Some(100),
            severity,
            category,
            aggregate: aggregate.map(str::to_owned),
        }
    }

    #[::core::prelude::v1::test]
    fn summaries_pick_the_most_severe_successful_category_evidence() {
        let insights = vec![
            insight(
                "frame_info",
                TraceInsightCategory::Frame,
                TraceInsightSeverity::Info,
                None,
            ),
            insight(
                "frame_jank",
                TraceInsightCategory::Frame,
                TraceInsightSeverity::Critical,
                Some("12 janky"),
            ),
            insight(
                "binder",
                TraceInsightCategory::Binder,
                TraceInsightSeverity::Warning,
                Some("5 ms"),
            ),
        ];
        let summaries = build_trace_workspace_summaries(&insights);
        assert_eq!(summaries.len(), 4);
        assert_eq!(summaries[0].category, TraceInsightCategory::Frame);
        assert_eq!(summaries[0].value.as_deref(), Some("12 janky"));
        assert_eq!(summaries[0].severity, Some(TraceInsightSeverity::Critical));
        assert_eq!(summaries[1].value.as_deref(), Some("5 ms"));
        assert!(summaries[2].value.is_none());
    }

    #[::core::prelude::v1::test]
    fn filters_and_preset_scoping_match_compose() {
        let mut warning = insight(
            "b",
            TraceInsightCategory::Cpu,
            TraceInsightSeverity::Warning,
            None,
        );
        warning.timestamp_nanos = Some(50);
        let insights = vec![
            insight(
                "a",
                TraceInsightCategory::Cpu,
                TraceInsightSeverity::Critical,
                None,
            ),
            warning.clone(),
            insight(
                "c",
                TraceInsightCategory::Cpu,
                TraceInsightSeverity::Opportunity,
                None,
            ),
        ];
        assert_eq!(
            visible_trace_insights(&insights, &[], None, TraceInsightFilter::All).len(),
            3
        );
        assert_eq!(
            visible_trace_insights(&insights, &[], None, TraceInsightFilter::Info).len(),
            1
        );
        let presets = vec![TraceDiagnosticPresetOption {
            id: "core".into(),
            title: "core".into(),
            query_ids: vec!["b".into()],
        }];
        let scoped =
            visible_trace_insights(&insights, &presets, Some("core"), TraceInsightFilter::All);
        assert_eq!(scoped.len(), 1);
        assert_eq!(
            trace_insight_counts(&insights),
            [
                (TraceInsightFilter::All, 3),
                (TraceInsightFilter::Critical, 1),
                (TraceInsightFilter::Warning, 1),
                (TraceInsightFilter::Info, 1),
            ]
        );
        let anchors = trace_timeline_anchors(
            &insights,
            Some(&TraceWorkspaceArtifact {
                artifact_id: "artifact-1".into(),
                display_name: "trace".into(),
                location_label: "/tmp/trace".into(),
                completeness_label: "complete".into(),
                limitations: Vec::new(),
                viewer_available: true,
            }),
        );
        assert_eq!(
            anchors.first().and_then(|item| item.timestamp_nanos),
            Some(50)
        );
    }

    #[::core::prelude::v1::test]
    fn sessions_filter_and_formatting_match_compose() {
        let sessions = vec![TraceWorkspaceSession {
            session_id: "s1".into(),
            title: "Pixel 9 launcher".into(),
            captured_at_label: "2026-11-07".into(),
            duration_label: "2.500 s".into(),
            artifact: None,
        }];
        assert_eq!(filter_trace_workspace_sessions(&sessions, "pixel").len(), 1);
        assert_eq!(
            filter_trace_workspace_sessions(&sessions, "missing").len(),
            0
        );
        assert_eq!(filter_trace_workspace_sessions(&sessions, "  ").len(), 1);
        assert_eq!(format_trace_timestamp(1_500_000_000), "1.500000 s");
        assert_eq!(format_trace_duration_nanos(Some(2_500_000_000)), "2.500 s");
        assert_eq!(format_trace_duration_nanos(Some(1_500_000)), "1.500 ms");
        assert_eq!(format_trace_duration_nanos(None), "—");
        assert_eq!(format_trace_duration_nanos(Some(-1)), "—");
        assert_eq!(format_trace_timestamp(58_123_000_000), "58.123000 s");
    }

    #[::core::prelude::v1::test]
    fn session_search_matches_artifact_identity_case_insensitively() {
        let sessions = vec![
            TraceWorkspaceSession {
                session_id: "game".into(),
                title: "Game trace".into(),
                captured_at_label: "2026-10-15 10:20".into(),
                duration_label: "12 s".into(),
                artifact: Some(TraceWorkspaceArtifact {
                    artifact_id: "artifact-game".into(),
                    display_name: "game.pftrace".into(),
                    location_label: "/traces/game.pftrace".into(),
                    completeness_label: "Complete".into(),
                    limitations: Vec::new(),
                    viewer_available: true,
                }),
            },
            TraceWorkspaceSession {
                session_id: "startup".into(),
                title: "Cold startup".into(),
                captured_at_label: "2026-10-15 09:42".into(),
                duration_label: "20 s".into(),
                artifact: None,
            },
        ];
        assert_eq!(
            filter_trace_workspace_sessions(&sessions, "ARTIFACT-GAME")
                .iter()
                .map(|session| session.session_id.as_str())
                .collect::<Vec<_>>(),
            vec!["game"]
        );
        assert_eq!(
            filter_trace_workspace_sessions(&sessions, "20 s")
                .iter()
                .map(|session| session.session_id.as_str())
                .collect::<Vec<_>>(),
            vec!["startup"]
        );
    }

    #[::core::prelude::v1::test]
    fn summaries_ignore_failed_and_rowless_evidence_like_compose() {
        let mut binder_error = insight(
            "binder",
            TraceInsightCategory::Binder,
            TraceInsightSeverity::Critical,
            None,
        );
        binder_error.result_rows_summary = Some("18 rows".into());
        binder_error.error = Some("schema unavailable".into());
        let mut long_task_no_result = insight(
            "long_task",
            TraceInsightCategory::LongTask,
            TraceInsightSeverity::Warning,
            Some("   "),
        );
        long_task_no_result.result_rows_summary = None;
        let summaries = build_trace_workspace_summaries(&[
            insight(
                "frame",
                TraceInsightCategory::Frame,
                TraceInsightSeverity::Info,
                None,
            ),
            binder_error,
            long_task_no_result,
        ]);
        let frame = &summaries[0];
        assert_eq!(frame.value.as_deref(), Some("3 rows"));
        assert!(summaries[1].value.is_none());
        assert_eq!(summaries[1].severity, None);
        assert!(summaries[2].value.is_none());
        assert!(summaries[3].value.is_none());
    }

    #[::core::prelude::v1::test]
    fn timeline_actions_require_a_locatable_insight_for_the_active_artifact() {
        let artifact = TraceWorkspaceArtifact {
            artifact_id: "artifact-active".into(),
            display_name: "game.pftrace".into(),
            location_label: "/traces/game.pftrace".into(),
            completeness_label: "Complete".into(),
            limitations: Vec::new(),
            viewer_available: true,
        };
        let mut locatable = insight(
            "frame",
            TraceInsightCategory::Frame,
            TraceInsightSeverity::Warning,
            Some("1 row"),
        );
        locatable.timestamp_nanos = Some(58_123_000_000);
        locatable.artifact_id = artifact.artifact_id.clone();
        assert!(can_open_insight_in_timeline(&locatable, Some(&artifact)));
        let mut without_timestamp = locatable.clone();
        without_timestamp.timestamp_nanos = None;
        assert!(!can_open_insight_in_timeline(
            &without_timestamp,
            Some(&artifact)
        ));
        let mut failed = locatable.clone();
        failed.error = Some("query failed".into());
        assert!(!can_open_insight_in_timeline(&failed, Some(&artifact)));
        let mut other_artifact = locatable.clone();
        other_artifact.artifact_id = "another-artifact".into();
        assert!(!can_open_insight_in_timeline(
            &other_artifact,
            Some(&artifact)
        ));
        let mut hidden_viewer = artifact.clone();
        hidden_viewer.viewer_available = false;
        assert!(!can_open_insight_in_timeline(
            &locatable,
            Some(&hidden_viewer)
        ));
        assert!(!can_open_insight_in_timeline(&locatable, None));
    }

    #[::core::prelude::v1::test]
    fn aggregates_win_over_row_summaries_in_visible_insight_cards() {
        let mut with_aggregate = insight(
            "frame_jank",
            TraceInsightCategory::Frame,
            TraceInsightSeverity::Critical,
            Some("12 / 840 janky frames"),
        );
        with_aggregate.result_rows_summary = Some("2 rows".into());
        let summaries = build_trace_workspace_summaries(&[with_aggregate]);
        assert_eq!(summaries[0].value.as_deref(), Some("12 / 840 janky frames"));
        assert_eq!(
            summaries[0].source_title.as_deref(),
            Some("frame_jank title")
        );
    }
}

use super::*;
use aps_battery::{BaselineIssue, BatteryReportComparison, compare_reports};

/// Advances only from the accepted report publication hook, never task start,
/// catalog refresh, capture invalidation, or failed/cancelled completion.
#[derive(Default)]
pub(super) struct BatteryBaselineState {
    current: Option<Arc<BatteryReport>>,
    previous: Option<Arc<BatteryReport>>,
    comparison: Option<BatteryReportComparison>,
    current_issues: Vec<BaselineIssue>,
}

impl BatteryBaselineState {
    pub(super) fn publish(&mut self, report: Arc<BatteryReport>) {
        if self
            .current
            .as_ref()
            .is_some_and(|current| Arc::ptr_eq(current, &report))
        {
            return;
        }
        let same_session = self.current.as_ref().is_some_and(|current| {
            current.session_id == report.session_id
                && current.device_local_id == report.device_local_id
                && current.package_name == report.package_name
                && current.uid == report.uid
        });
        if !same_session {
            self.previous = self.current.take();
        }
        self.comparison = self
            .previous
            .as_ref()
            .map(|previous| compare_reports(previous, &report));
        self.current_issues = if self.comparison.is_none() {
            aps_battery::baseline_issues(&report)
        } else {
            Vec::new()
        };
        self.current = Some(report);
    }
}

fn issue_key(issue: BaselineIssue) -> TextKey {
    match issue {
        BaselineIssue::MissingIdentity => TextKey::BatteryBaselineMissingIdentity,
        BaselineIssue::DifferentIdentity => TextKey::BatteryBaselineDifferentIdentity,
        BaselineIssue::MissingWorkload => TextKey::BatteryBaselineMissingWorkload,
        BaselineIssue::DifferentWorkload => TextKey::BatteryBaselineDifferentWorkload,
        BaselineIssue::MissingConfiguration => TextKey::BatteryBaselineMissingConfiguration,
        BaselineIssue::MissingCompletion => TextKey::BatteryBaselineMissingCompletion,
        BaselineIssue::DifferentConfiguration => TextKey::BatteryBaselineDifferentConfiguration,
        BaselineIssue::IncompleteRuns => TextKey::BatteryBaselineIncompleteRuns,
        BaselineIssue::MissingSnapshots => TextKey::BatteryBaselineMissingSnapshots,
        BaselineIssue::InvalidSnapshots => TextKey::BatteryBaselineInvalidSnapshots,
        BaselineIssue::MissingCharging => TextKey::BatteryBaselineMissingCharging,
        BaselineIssue::DifferentCharging => TextKey::BatteryBaselineDifferentCharging,
        BaselineIssue::MissingNetwork => TextKey::BatteryBaselineMissingNetwork,
        BaselineIssue::ZeroBaseline => TextKey::BatteryBaselineZero,
    }
}

fn signed_number(value: Option<f64>, unit: &str) -> String {
    value.map_or_else(|| "—".into(), |value| format!("{value:+.1}{unit}"))
}

impl ImportWorkspace {
    pub(super) fn battery_baseline_comparison(&self, colors: &studio::StudioColors) -> AnyElement {
        let state = &self.battery_shell_ui.baseline;
        let language = self.language;
        let mut panel =
            div()
                .flex()
                .flex_col()
                .flex_shrink_0()
                .gap(px(4.))
                .child(studio::section_title(
                    language.text(TextKey::BatteryBaseline),
                    colors,
                ));
        let Some(comparison) = &state.comparison else {
            panel = panel.child(note(
                language.text(TextKey::BatteryBaselineNoPrevious),
                colors,
            ));
            for reason in &state.current_issues {
                panel = panel.child(note(language.text(issue_key(*reason)), colors));
            }
            return panel.into_any_element();
        };
        if let Some(previous) = &state.previous {
            panel = panel.child(note(
                language.format(
                    TextKey::BatteryBaselineSession,
                    &[("session", &previous.session_id)],
                ),
                colors,
            ));
        }
        if let Some(current) = &state.current {
            panel = panel.child(note(
                language.format(
                    TextKey::BatteryBaselineCurrentSession,
                    &[("session", &current.session_id)],
                ),
                colors,
            ));
        }
        panel = panel
            .child(note(
                language.text(if comparison.comparable {
                    TextKey::BatteryBaselineCompatible
                } else {
                    TextKey::BatteryBaselineUnavailable
                }),
                colors,
            ))
            .child(note(
                language.format(
                    TextKey::BatteryBaselineNetworkValues,
                    &[
                        (
                            "current",
                            &optional_number(comparison.current_network_median, "B"),
                        ),
                        (
                            "baseline",
                            &optional_number(comparison.baseline_network_median, "B"),
                        ),
                        (
                            "delta",
                            &comparison
                                .network_bytes_change
                                .map_or_else(|| "—".into(), |value| format!("{value:+} B")),
                        ),
                        (
                            "percent",
                            &signed_number(comparison.network_percent_change, "%"),
                        ),
                    ],
                ),
                colors,
            ));
        for reason in &comparison.reasons {
            panel = panel.child(note(language.text(issue_key(*reason)), colors));
        }
        panel
            .child(note(
                language.text(TextKey::BatteryBaselineEvidenceBoundary),
                colors,
            ))
            .into_any_element()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn report(id: &str, bytes: u64) -> Arc<BatteryReport> {
        let before = aps_battery::parse_snapshot(
            "9,0,l,start,period\n9,10001,l,nt,0,0,0,0,0,0\n",
            "",
            "AC powered: false\n",
            aps_battery::SnapshotIdentity {
                uid: 10001,
                shared_uid: false,
                device_id: "device".into(),
                boot_id: Some("boot".into()),
                elapsed_ms: 1000,
            },
        )
        .unwrap();
        let mut after = before.clone();
        after.identity.elapsed_ms += 5000;
        after.network_bytes.insert("wifi_rx".into(), Some(bytes));
        let mut report = BatteryReport::from_live_snapshots(
            id,
            "example.app",
            "TIMED",
            &[aps_battery::BatteryRunObservation {
                iteration: 1,
                before,
                after,
                temperatures_tenths_celsius: Vec::new(),
                thermal_statuses: Vec::new(),
                charging_states: vec![Some(false), Some(false)],
            }],
        )
        .unwrap();
        report.configuration = Some(aps_battery::BatteryCaptureConfig {
            mode: aps_battery::BatteryCaptureMode::Timed,
            duration_seconds: 5,
            polling_seconds: 5,
            runs: 1,
            cooldown_seconds: 0,
        });
        report.workload_id = Some("workload".into());
        report.stopped_early = Some(false);
        Arc::new(
            BatteryReport::parse_json(&report.to_json(aps_battery::MAX_INPUT_BYTES).unwrap())
                .unwrap(),
        )
    }

    #[::core::prelude::v1::test]
    fn publication_retains_only_the_previous_success_and_paging_does_not_shift_it() {
        let mut state = BatteryBaselineState::default();
        let first = report("first", 10);
        state.publish(first.clone());
        assert!(state.previous.is_none());
        let second = report("second", 20);
        state.publish(second.clone());
        assert!(Arc::ptr_eq(state.previous.as_ref().unwrap(), &first));
        assert!(state.comparison.as_ref().unwrap().comparable);
        assert_eq!(
            state.comparison.as_ref().unwrap().network_percent_change,
            Some(100.)
        );
        state.publish(second.clone());
        assert!(Arc::ptr_eq(state.previous.as_ref().unwrap(), &first));
        state.publish(report("second", 20));
        assert!(Arc::ptr_eq(state.previous.as_ref().unwrap(), &first));
        let current = state.current.as_ref().unwrap().clone();
        state.publish(report("third", 30));
        assert!(Arc::ptr_eq(state.previous.as_ref().unwrap(), &current));
    }

    #[::core::prelude::v1::test]
    fn failed_import_and_cancelled_capture_do_not_publish_or_shift_comparison() {
        let mut workspace = ImportWorkspace::new(Destination::Battery, Language::English);
        workspace.session = Some(Session::Battery(report("first", 10)));
        workspace.publish_battery_run_page();
        workspace.session = Some(Session::Battery(report("second", 20)));
        workspace.publish_battery_run_page();
        workspace.battery_ui.query = "retained query".into();
        workspace.battery_shell_ui.current_saved_key = Some("retained-key".into());
        let before = workspace.battery_shell_ui.baseline.comparison.clone();
        let current = workspace.battery_shell_ui.baseline.current.clone().unwrap();
        let previous = workspace
            .battery_shell_ui
            .baseline
            .previous
            .clone()
            .unwrap();
        assert!(BatteryReport::parse_json(b"invalid").is_err());
        let cancel = CancellationToken::new();
        workspace.battery_shell_ui.capture.export_cancel = Some(cancel.clone());
        workspace.invalidate_battery_capture();
        assert!(cancel.is_cancelled());
        workspace.publish_battery_run_page();
        let state = &workspace.battery_shell_ui.baseline;
        assert!(Arc::ptr_eq(state.current.as_ref().unwrap(), &current));
        assert!(Arc::ptr_eq(state.previous.as_ref().unwrap(), &previous));
        assert_eq!(state.comparison, before);
        assert!(
            matches!(&workspace.session, Some(Session::Battery(report)) if Arc::ptr_eq(report, &current))
        );
        assert_eq!(workspace.battery_ui.query, "retained query");
        assert_eq!(
            workspace.battery_shell_ui.current_saved_key.as_deref(),
            Some("retained-key")
        );
    }

    #[::core::prelude::v1::test]
    fn clearing_current_view_does_not_discard_last_successful_baseline_candidate() {
        let mut workspace = ImportWorkspace::new(Destination::Battery, Language::English);
        let first = report("first", 10);
        workspace.session = Some(Session::Battery(first.clone()));
        workspace.publish_battery_run_page();
        workspace.session = None;
        workspace.publish_battery_run_page();
        assert!(workspace.battery_shell_ui.baseline.previous.is_none());
        workspace.session = Some(Session::Battery(report("second", 20)));
        workspace.publish_battery_run_page();
        assert!(Arc::ptr_eq(
            workspace
                .battery_shell_ui
                .baseline
                .previous
                .as_ref()
                .unwrap(),
            &first
        ));
    }

    #[::core::prelude::v1::test]
    fn first_legacy_import_already_exposes_missing_comparison_evidence() {
        let report = Arc::new(BatteryReport::parse_json(br#"{"schemaVersion":1,"sessionId":"legacy","runs":[{"runId":"r","iteration":1,"durationMs":5000,"networkBytes":0}]}"#).unwrap());
        let mut state = BatteryBaselineState::default();
        state.publish(report);
        assert!(state.comparison.is_none());
        for issue in [
            BaselineIssue::MissingIdentity,
            BaselineIssue::MissingWorkload,
            BaselineIssue::MissingConfiguration,
            BaselineIssue::MissingCompletion,
            BaselineIssue::MissingSnapshots,
        ] {
            assert!(state.current_issues.contains(&issue));
        }
    }

    #[::core::prelude::v1::test]
    fn zero_and_absent_percent_have_distinct_values_and_both_locales_cover_reasons() {
        assert_eq!(signed_number(Some(0.), "%"), "+0.0%");
        assert_eq!(signed_number(None, "%"), "—");
        for reason in [
            BaselineIssue::MissingIdentity,
            BaselineIssue::DifferentIdentity,
            BaselineIssue::MissingWorkload,
            BaselineIssue::DifferentWorkload,
            BaselineIssue::MissingConfiguration,
            BaselineIssue::MissingCompletion,
            BaselineIssue::DifferentConfiguration,
            BaselineIssue::IncompleteRuns,
            BaselineIssue::MissingSnapshots,
            BaselineIssue::InvalidSnapshots,
            BaselineIssue::MissingCharging,
            BaselineIssue::DifferentCharging,
            BaselineIssue::MissingNetwork,
            BaselineIssue::ZeroBaseline,
        ] {
            for language in [Language::English, Language::SimplifiedChinese] {
                assert!(!language.text(issue_key(reason)).is_empty());
            }
        }
    }
}

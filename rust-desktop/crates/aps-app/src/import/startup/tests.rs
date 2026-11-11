//! Startup report, publication and Compose contract regression tests.
use super::*;

#[::core::prelude::v1::test]
fn metric_support_formats_observed_and_missing_counts_in_both_locales() {
    for language in [Language::English, Language::SimplifiedChinese] {
        let formatted = language
            .try_format(
                TextKey::StartupMetricSupport,
                &[("count", &3usize), ("missing", &7usize)],
            )
            .unwrap();
        assert!(formatted.contains('3') && formatted.contains('7'));
        assert!(!formatted.contains('{'));
    }
}

fn fixture(count: usize) -> StartupSession {
    let mut csv = String::from(
        "iteration,runId,requestedType,observedType,totalTimeMs,displayedTimeMs,fullyDrawnTimeMs,agentAvailable,compilerFilter,diagnostics\n",
    );
    for index in 1..=count {
        let mode = if index.is_multiple_of(2) {
            "WARM"
        } else {
            "COLD"
        };
        let ttid = if index == 1 {
            String::new()
        } else {
            index.to_string()
        };
        csv.push_str(&format!("{index},run-{index},{mode},UNKNOWN,9000,{ttid},,true,speed-profile,original diagnostic\n"));
    }
    StartupSession::parse_csv(&csv).unwrap()
}

#[::core::prelude::v1::test]
fn filtered_publication_binds_rows_statistics_exports_and_query() {
    let session = fixture(250);
    let page = load_page(
        &session,
        &StartupQuery {
            requested_mode: Some(StartupMode::Warm),
            offset: 100,
            limit: PAGE,
            ..Default::default()
        },
        &CancellationToken::new(),
    )
    .unwrap();
    let published = page.ui.startup_page.as_ref().unwrap();
    assert_eq!(published.total, 125);
    assert_eq!(published.summary.runs, 125);
    assert_eq!(published.rows.len(), 25);
    assert_eq!(published.rows[0].id, "run-202");
    assert_eq!(published.summary.ttid.count, 125);
    assert_eq!(published.summary.ttid.p50_ms, Some(126));
    assert_eq!(
        page.ui.startup_query.as_ref().unwrap().requested_mode,
        Some(StartupMode::Warm)
    );
    assert_eq!(page.offset, 100);
    assert!(!page.has_more);
    assert_eq!(page.rows.len(), 25);
    assert_eq!(page.rows[0][0], "202");
    assert_eq!(page.rows[0][4], "202");
    assert!(
        page.rows
            .iter()
            .all(|row| row[2] == "Warm" && row[3] == "Unknown" && row[5].is_empty())
    );
    let bytes = export::encode_page(&page, export::Format::Csv).unwrap();
    let csv = String::from_utf8(bytes).unwrap();
    assert!(!csv.contains("Some("));
    assert!(!csv.contains("run-1"));
}

#[::core::prelude::v1::test]
fn missing_platform_metrics_do_not_use_total_time_or_agent_declaration() {
    let session = fixture(1);
    let page = load_page(
        &session,
        &StartupQuery::default(),
        &CancellationToken::new(),
    )
    .unwrap();
    let summary = &page.ui.startup_page.as_ref().unwrap().summary;
    assert_eq!(summary.ttid.count, 0);
    assert_eq!(summary.ttid.missing, 1);
    assert_eq!(summary.ttfd.count, 0);
    assert_eq!(summary.ttfd.missing, 1);
    assert_eq!(page.ui.startup_mad, None);
    assert_eq!(page.rows[0][4], "");
    assert_eq!(page.rows[0][6], "9000");
}

#[::core::prelude::v1::test]
fn zero_match_filter_publishes_empty_cohort_not_previous_summary() {
    let page = load_page(
        &fixture(10),
        &StartupQuery {
            requested_mode: Some(StartupMode::Hot),
            ..Default::default()
        },
        &CancellationToken::new(),
    )
    .unwrap();
    assert_eq!(page.ui.startup_page.as_ref().unwrap().total, 0);
    assert_eq!(page.ui.startup_page.as_ref().unwrap().summary.runs, 0);
    assert!(page.rows.is_empty());
    assert!(!page.has_more);
    assert_eq!(page.ui.startup_mad, None);
}

#[::core::prelude::v1::test]
fn selection_uses_run_identity_and_never_frame_index() {
    let session = fixture(250);
    let mut workspace = ImportWorkspace::new(Destination::Startup, Language::English);
    workspace.page = Some(
        load_page(
            &session,
            &StartupQuery::default(),
            &CancellationToken::new(),
        )
        .unwrap(),
    );
    workspace.selected_frame_id = Some(999);
    workspace.selected_startup_run_id = Some("run-2".into());
    workspace.publish_startup_selection();
    assert_eq!(workspace.selected_startup_run_id.as_deref(), Some("run-2"));
    let query = StartupQuery {
        requested_mode: Some(StartupMode::Cold),
        offset: 100,
        ..Default::default()
    };
    workspace.page = Some(load_page(&session, &query, &CancellationToken::new()).unwrap());
    workspace.publish_startup_selection();
    assert_eq!(
        workspace.selected_startup_run_id.as_deref(),
        Some("run-201")
    );
    assert_eq!(workspace.selected_frame_id, Some(999));
    assert_eq!(workspace.startup_page_offset, 100);
    assert_eq!(workspace.startup_mode_filter, Some(StartupMode::Cold));
}

#[::core::prelude::v1::test]
fn cancelled_or_invalid_projection_cannot_publish_new_evidence() {
    let session = fixture(10);
    let cancel = CancellationToken::new();
    cancel.cancel();
    assert!(load_page(&session, &StartupQuery::default(), &cancel).is_err());
    assert!(
        load_page(
            &session,
            &StartupQuery {
                limit: usize::MAX,
                ..Default::default()
            },
            &CancellationToken::new()
        )
        .is_err()
    );
}

#[::core::prelude::v1::test]
fn empty_workspace_has_no_implicit_import_selection_or_ai_authority() {
    let workspace = ImportWorkspace::new(Destination::Startup, Language::English);
    assert!(workspace.page.is_none());
    assert!(workspace.selected_startup_run_id.is_none());
    assert!(!workspace.busy);
    assert!(!workspace.startup_capture.in_progress);
    assert!(workspace.analysis_request().is_err());
    let ui = include_str!("../startup.rs");
    assert!(ui.contains(".child(self.render_startup_capture_config("));
    let capture = include_str!("capture.rs");
    for id in [
        "startup-live-mode",
        "startup-live-iterations",
        "startup-live-timeout",
        "startup-live-settle",
        "startup-live-event-wait",
        "startup-live-review",
        "startup-live-confirm",
        "startup-live-cancel-review",
    ] {
        assert!(
            capture.contains(id),
            "Missing explicit scenario control: {id}"
        );
    }
    let review = capture
        .split("pub(crate) fn start_startup_capture(")
        .nth(1)
        .unwrap()
        .split("fn confirm_startup_capture(")
        .next()
        .unwrap();
    assert!(review.contains("review = Some(ReviewedExperiment"));
    assert!(!review.contains("load_capture("));
    assert!(!review.contains("capture_startup("));
    assert!(!review.contains(".spawn("));
    let confirm = capture
        .split("fn confirm_startup_capture(")
        .nth(1)
        .unwrap()
        .split("pub(in crate::import) fn save_startup_page(")
        .next()
        .unwrap();
    assert!(confirm.contains("review.take()"));
    assert!(confirm.contains("target.as_ref() != Some(&experiment.target)"));
    assert!(confirm.contains("config != experiment.config"));
    assert!(confirm.contains("load_capture(&experiment"));
    assert!(ui.contains("StartupMetrics"));
    assert!(ui.contains("startup-evidence-scroll"));
    assert!(!ui.contains("this.selected_frame ="));
    assert!(!ui.contains("this.selected_frame_id ="));
}

#[::core::prelude::v1::test]
fn export_and_minimized_ai_cannot_promote_csv_metadata_to_live_origin() {
    let session = StartupSession::parse_csv(
        "iteration,runId,requestedType,observedType,displayedTimeMs,fullyDrawnTimeMs,captureSource,origin\n1,forged,COLD,COLD,123,,live-platform,LiveCapture\n",
    )
    .unwrap();
    let page = load_page(
        &session,
        &StartupQuery::default(),
        &CancellationToken::new(),
    )
    .unwrap();
    assert_eq!(page.columns.len(), 8);
    assert_eq!(page.columns[7], "Evidence source");
    assert_eq!(page.rows[0][7], "imported-csv");
    assert!(page.summary.contains("source imported-csv"));
    let csv = String::from_utf8(export::encode_page(&page, export::Format::Csv).unwrap()).unwrap();
    assert!(csv.contains("imported-csv"));
    assert!(!csv.contains("live-platform"));
    let request = export::analysis_request(Destination::Startup, "typed-source", 1, &page).unwrap();
    assert_eq!(
        request.evidence[0].payload["rows"][0][7].as_str(),
        Some("imported-csv")
    );
}

#[::core::prelude::v1::test]
fn empty_filtered_live_page_retains_its_published_source() {
    let imported = fixture(1);
    let mut run = imported.runs()[0].clone();
    run.origin = aps_startup::StartupEvidenceOrigin::LiveCapture;
    run.package = Some("dev.sample".into());
    let session = Arc::new(StartupSession::from_captured_runs(vec![run]).unwrap());
    let mut workspace = ImportWorkspace::new(Destination::Startup, Language::English);
    workspace.page = Some(
        load_page(
            &session,
            &StartupQuery {
                requested_mode: Some(StartupMode::Hot),
                ..Default::default()
            },
            &CancellationToken::new(),
        )
        .unwrap(),
    );
    workspace.session = Some(Session::Startup(session));
    assert!(workspace.page.as_ref().unwrap().rows.is_empty());
    assert!(workspace.startup_evidence_is_live());
    assert!(
        workspace
            .page
            .as_ref()
            .unwrap()
            .summary
            .contains("source live-platform")
    );
}

fn oracle_path(name: &str) -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../aps-startup/tests/fixtures/compose-oracle")
        .join(name)
}

#[::core::prelude::v1::test]
fn actual_compose_exports_decode_and_match_comparison_oracle() {
    let expected = std::fs::read_to_string(oracle_path("expected.csv")).unwrap();
    for line in expected.lines().skip(1) {
        let fields: Vec<_> = line.split(';').collect();
        let current =
            reports::open_session(&oracle_path(&format!("{}-current.json", fields[0]))).unwrap();
        let baseline =
            reports::open_session(&oracle_path(&format!("{}-baseline.json", fields[0]))).unwrap();
        let comparison = current.compare(&baseline, 5.).unwrap();
        if fields[2] == "null" {
            assert_eq!(
                comparison.status,
                aps_startup::ComparisonStatus::Insufficient
            );
        } else {
            let interval = comparison.confidence_interval_ms.unwrap();
            for (actual, expected) in [
                comparison.median_difference_ms.unwrap(),
                comparison.percent_difference.unwrap(),
                interval.0,
                interval.1,
                comparison.practical_threshold_ms.unwrap(),
            ]
            .into_iter()
            .zip(&fields[2..7])
            {
                assert!(
                    (actual - expected.parse::<f64>().unwrap()).abs() < 1e-9,
                    "{}: {actual} != {expected}",
                    fields[0]
                );
            }
        }
        let encoded = reports::encode_json(&current).unwrap();
        let replay =
            reports::decode(aps_json::parse_slice(&encoded, aps_json::Limits::default()).unwrap())
                .unwrap();
        assert_eq!(replay.compare(&baseline, 5.).unwrap(), comparison);
        assert_eq!(current.warnings(), &["fixture warning"]);
        assert_eq!(
            current.runs()[0].origin,
            aps_startup::StartupEvidenceOrigin::ImportedJson
        );
        assert_eq!(current.runs()[0].ttfd.milliseconds, None);
        assert!(current.runs()[0].ttfd.unavailable_reason.is_some());
    }
}

fn json_fixture() -> aps_json::Value {
    aps_json::read(
        &oracle_path("regression-current.json"),
        aps_json::Limits::default(),
    )
    .unwrap()
}

#[::core::prelude::v1::test]
fn json_recomputes_measurements_and_phases_ignoring_forged_summary_and_phase_arrays() {
    let mut root = json_fixture();
    root["summary"] = serde_json::json!({"firstFrame":{"medianMs":999999}});
    for run in root["runs"].as_array_mut().unwrap() {
        run["phases"] = serde_json::json!([{"name":"invented phase", "durationNs": 999999}]);
    }
    let session = reports::decode(root).unwrap();
    assert_eq!(session.summary().ttid.p50_ms, Some(130));
    let phases = session.evidence(&session.runs()[0].id).unwrap().phases();
    assert_eq!(phases.len(), 1);
    assert_eq!(phases[0].name, "Activity create");
    assert_eq!(phases[0].duration_ns, 13_000_000);
}

#[::core::prelude::v1::test]
fn json_boundary_rejects_invalid_schema_identity_types_and_unknown_enum_evidence() {
    let mut invalid = Vec::new();
    let mut root = json_fixture();
    root["schemaVersion"] = 2.into();
    invalid.push(root);
    let mut root = json_fixture();
    root["runs"] = serde_json::json!([]);
    invalid.push(root);
    let mut root = json_fixture();
    root["runs"][1]["runId"] = root["runs"][0]["runId"].clone();
    invalid.push(root);
    let mut root = json_fixture();
    root["runs"][0]["iteration"] = 0.into();
    invalid.push(root);
    let mut root = json_fixture();
    root["runs"][0]["displayedTimeMs"] = (-1).into();
    invalid.push(root);
    let mut root = json_fixture();
    root["runs"][0]["displayedTimeMs"] = "100".into();
    invalid.push(root);
    let mut root = json_fixture();
    root["runs"][0]["rawEvidence"] = aps_json::Value::Null;
    invalid.push(root);
    let mut root = json_fixture();
    root["runs"][0]["context"]["deviceLocalId"] = "raw-serial".into();
    invalid.push(root);
    let mut root = json_fixture();
    root["runs"][0]["metricEvidence"]["ttid"]["source"] = "UNKNOWN".into();
    invalid.push(root);
    let mut root = json_fixture();
    root["runs"][0]["compilationEvidence"]["verified"] = "true".into();
    invalid.push(root);
    let mut root = json_fixture();
    root["runs"][0]["environmentEvidence"]["capturedAt"] = "2026-02-30T00:00:00Z".into();
    invalid.push(root);
    for root in invalid {
        assert!(reports::decode(root).is_err());
    }
    assert!(
        aps_json::parse_slice(
            b"{\"schemaVersion\":1,\"schemaVersion\":2}",
            aps_json::Limits::default()
        )
        .is_err()
    );
}

#[::core::prelude::v1::test]
fn legacy_metric_defaults_match_compose_but_missing_cohort_evidence_is_incompatible() {
    let mut root = json_fixture();
    for run in root["runs"].as_array_mut().unwrap() {
        let object = run.as_object_mut().unwrap();
        for key in [
            "context",
            "compilationEvidence",
            "environmentEvidence",
            "metricEvidence",
        ] {
            object.remove(key);
        }
    }
    let session = reports::decode(root).unwrap();
    let evidence = session.evidence(&session.runs()[0].id).unwrap();
    assert_eq!(evidence.ttid.source.as_deref(), Some("EVENT_LOG"));
    assert_eq!(evidence.ttid.confidence, aps_startup::Confidence::Exact);
    assert_eq!(
        evidence.ttfd.confidence,
        aps_startup::Confidence::Unavailable
    );
    assert_eq!(
        session.compare(&session, 5.).unwrap().status,
        aps_startup::ComparisonStatus::Incompatible
    );
}

#[::core::prelude::v1::test]
fn exports_cover_full_cohort_not_current_page_and_csv_is_reimportable() {
    let mut root = json_fixture();
    let template = root["runs"][0].clone();
    root["runs"] = (1..=250)
        .map(|i| {
            let mut run = template.clone();
            run["iteration"] = i.into();
            run["runId"] = format!("run-{i}").into();
            run
        })
        .collect();
    let session = reports::decode(root).unwrap();
    let page = load_page(
        &session,
        &StartupQuery {
            offset: 200,
            limit: PAGE,
            ..Default::default()
        },
        &CancellationToken::new(),
    )
    .unwrap();
    assert_eq!(page.rows.len(), 50);
    let json = reports::encode_json(&session).unwrap();
    let exported = aps_json::parse_slice(&json, aps_json::Limits::default()).unwrap();
    assert_eq!(exported["schemaVersion"], 1);
    assert_eq!(exported["runs"].as_array().unwrap().len(), 250);
    let csv = String::from_utf8(reports::encode_csv(&session).unwrap()).unwrap();
    let reimported = StartupSession::parse_csv(&csv).unwrap();
    assert_eq!(reimported.runs().len(), 250);
    assert_eq!(reimported.runs()[249].id, "run-250");
    assert!(
        reimported
            .runs()
            .iter()
            .all(|r| r.ttfd.milliseconds.is_none())
    );
    assert_eq!(
        reimported.compare(&session, 5.).unwrap().status,
        aps_startup::ComparisonStatus::Incompatible
    );
}

#[::core::prelude::v1::test]
fn successful_publications_advance_baseline_only_once_and_failure_cancel_preserve_selection() {
    let first = Arc::new(reports::open_session(&oracle_path("regression-baseline.json")).unwrap());
    let prepared = reports::prepare_report(
        &oracle_path("regression-current.json"),
        Some(&first),
        &CancellationToken::new(),
    )
    .unwrap();
    let second = prepared.session;
    let mut workspace = ImportWorkspace::new(Destination::Startup, Language::English);
    workspace.session = Some(Session::Startup(first.clone()));
    workspace.publish_startup_selection();
    workspace.session = Some(Session::Startup(second.clone()));
    workspace.page = Some(prepared.page);
    workspace.publish_startup_selection();
    workspace.startup_capture.reports.comparison = prepared.comparison;
    workspace.selected_startup_run_id = Some(second.runs()[2].id.clone());
    workspace.publish_startup_selection();
    let comparison = workspace.startup_capture.reports.comparison.clone();
    let selected = workspace.selected_startup_run_id.clone();
    assert!(
        reports::prepare_report(
            &oracle_path("missing.json"),
            Some(&second),
            &CancellationToken::new()
        )
        .is_err()
    );
    let cancel = CancellationToken::new();
    cancel.cancel();
    assert!(
        reports::prepare_report(
            &oracle_path("improvement-current.json"),
            Some(&second),
            &cancel
        )
        .is_err()
    );
    assert!(Arc::ptr_eq(
        workspace.startup_capture.reports.current.as_ref().unwrap(),
        &second
    ));
    assert!(Arc::ptr_eq(
        workspace.startup_capture.reports.baseline.as_ref().unwrap(),
        &first
    ));
    assert_eq!(workspace.startup_capture.reports.comparison, comparison);
    assert_eq!(workspace.selected_startup_run_id, selected);
    let next = Arc::new(reports::open_session(&oracle_path("improvement-current.json")).unwrap());
    workspace.session = Some(Session::Startup(next));
    workspace.publish_startup_selection();
    assert!(Arc::ptr_eq(
        workspace.startup_capture.reports.baseline.as_ref().unwrap(),
        &second
    ));
}

#[::core::prelude::v1::test]
fn comparison_locale_contract_formats_all_fields_in_both_languages() {
    for language in [Language::English, Language::SimplifiedChinese] {
        assert!(
            language
                .try_format(
                    TextKey::StartupComparisonResult,
                    &[
                        ("status", &"status"),
                        ("difference", &"30 ms"),
                        ("low", &"27 ms"),
                        ("high", &"32 ms")
                    ]
                )
                .is_ok()
        );
        assert!(
            language
                .try_format(
                    TextKey::StartupComparisonCohort,
                    &[
                        ("cohort", &"current"),
                        ("ttid", &"100 ms"),
                        ("ttfd", &"unavailable"),
                        ("count", &5),
                        ("missing", &0),
                        ("source", &"imported-json")
                    ]
                )
                .is_ok()
        );
    }
}

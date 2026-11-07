//! Regression sources are compiled only during the rewrite validation stage.
use super::*;

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
    assert!(workspace.analysis_request().is_err());
    let ui = include_str!("../startup.rs");
    assert!(ui.contains(".child(scenario("));
    assert!(ui.contains("StartupMetrics"));
    assert!(ui.contains("startup-evidence-scroll"));
    assert!(!ui.contains("this.selected_frame ="));
    assert!(!ui.contains("this.selected_frame_id ="));
}

//! Deferred tests: do not run before the full rewrite implementation is complete.
use aps_frame::{BudgetSource, DeadlineVerdict, FrameQuery, FrameSession};

fn capture(columns: &str, rows: &str) -> String {
    format!("---PROFILEDATA---\n{columns}\n{rows}\n---PROFILEDATA---\n")
}

#[test]
fn explicit_deadline_miss_is_not_a_platform_jank_signal() {
    let session = FrameSession::parse_gfxinfo(&capture(
        "Flags,IntendedVsync,FrameDeadline,FrameCompleted,",
        "0,100000000,108333333,110000000,\n0,108333333,116666666,116000000,",
    ))
    .unwrap();
    assert_eq!(session.frames()[0].deadline, DeadlineVerdict::Missed);
    assert_eq!(
        session.frames()[0].budget_source,
        BudgetSource::PlatformDeadline
    );
    assert_eq!(session.frames()[0].platform_jank, None);
    assert_eq!(session.summary().deadline_misses, 1);
    assert_eq!(session.summary().platform_classified_frames, 0);
    assert_eq!(session.summary().platform_jank_rate, None);
}

#[test]
fn unknown_budget_and_nonzero_flags_remain_unclassified() {
    let unknown = FrameSession::parse_gfxinfo(&capture(
        "Flags,IntendedVsync,FrameCompleted",
        "0,100000000,110000000",
    ))
    .unwrap();
    assert_eq!(unknown.frames()[0].budget_source, BudgetSource::Unknown);
    assert_eq!(unknown.frames()[0].deadline, DeadlineVerdict::Unknown);
    assert_eq!(unknown.summary().deadline_miss_rate, None);
    let flags = FrameSession::parse_gfxinfo(&capture(
        "Flags,IntendedVsync,FrameInterval,FrameCompleted",
        "1,100000000,8333333,130000000",
    ))
    .unwrap();
    assert_eq!(flags.frames()[0].deadline, DeadlineVerdict::Unknown);
}

#[test]
fn legacy_refresh_inference_preserves_source_and_mode_changes() {
    let session = FrameSession::parse_gfxinfo(&capture("Flags,IntendedVsync,FrameCompleted", "0,100000000,108000000\n0,108333333,116000000\n0,116666666,124000000\n0,133333333,141000000")).unwrap();
    assert_eq!(
        session
            .frames()
            .iter()
            .map(|frame| frame.budget_ns)
            .collect::<Vec<_>>(),
        [
            Some(8_333_333),
            Some(8_333_333),
            Some(16_666_667),
            Some(16_666_667)
        ]
    );
    assert!(
        session
            .frames()
            .iter()
            .all(|frame| frame.budget_source == BudgetSource::InferredVsync)
    );
}

#[test]
fn multiwindow_schemas_unknown_values_and_filter_pages_are_preserved() {
    let text = format!(
        "Window: Window A\n{}Window: Window B\n{}",
        capture(
            "Flags,IntendedVsync,FrameInterval,FrameCompleted",
            "0,100000000,16666667,110000000"
        ),
        capture(
            "Flags,IntendedVsync,FrameCompleted,FutureStage,",
            "0,200000000,210000000,7,"
        )
    );
    let session = FrameSession::parse_gfxinfo(&text).unwrap();
    assert_eq!(
        session.frames()[1]
            .columns
            .get("FutureStage")
            .map(String::as_str),
        Some("7")
    );
    assert_eq!(session.frames()[1].budget_source, BudgetSource::Unknown);
    let page = session
        .page(&FrameQuery {
            window: Some("Window B".into()),
            limit: 1,
            ..FrameQuery::default()
        })
        .unwrap();
    assert_eq!(page.total, 1);
    assert_eq!(page.summary.total_frames, 1);
    assert_eq!(page.rows[0].window.as_deref(), Some("Window B"));
    assert!(!session.warnings().is_empty());
}

#[test]
fn bad_rows_are_reported_and_bad_formats_rejected() {
    let session = FrameSession::parse_gfxinfo(&capture(
        "Flags,IntendedVsync,FrameCompleted,",
        "0,100000000\n0,100000000,110000000,7,",
    ))
    .unwrap();
    assert!(session.frames().is_empty());
    assert!(
        session
            .warnings()
            .iter()
            .any(|warning| warning.contains("2 malformed"))
    );
    assert!(FrameSession::parse_gfxinfo("arbitrary file").is_err());
    assert!(
        FrameSession::parse_gfxinfo(&capture("Flags,IntendedVsync,IntendedVsync", "0,1,2"))
            .is_err()
    );
}

#[test]
fn time_range_is_half_open_and_pagination_limits_are_checked() {
    let session = FrameSession::parse_gfxinfo(&capture(
        "Flags,IntendedVsync,FrameCompleted",
        "0,100000000,108000000\n0,108333333,116000000",
    ))
    .unwrap();
    let page = session
        .page(&FrameQuery {
            start_ns: Some(100000000),
            end_ns: Some(108333333),
            limit: 100,
            ..FrameQuery::default()
        })
        .unwrap();
    assert_eq!(page.total, 1);
    assert!(
        session
            .page(&FrameQuery {
                limit: 513,
                ..FrameQuery::default()
            })
            .is_err()
    );
    assert!(
        session
            .page(&FrameQuery {
                start_ns: Some(2),
                end_ns: Some(1),
                ..FrameQuery::default()
            })
            .is_err()
    );
}

#[test]
fn paged_rows_keep_the_complete_filtered_summary() {
    let session = FrameSession::parse_gfxinfo(&capture(
        "Flags,IntendedVsync,FrameCompleted,FrameDeadline",
        "0,100000000,108000000,116000000\n0,116000000,140000000,132000000\n0,132000000,140000000,148000000",
    ))
    .unwrap();
    let page = session
        .page(&FrameQuery {
            offset: 1,
            limit: 1,
            ..FrameQuery::default()
        })
        .unwrap();
    assert_eq!(page.total, 3);
    assert_eq!(page.rows.len(), 1);
    assert_eq!(page.rows[0].intended_vsync_ns, Some(116000000));
    assert_eq!(page.summary.total_frames, 3);
    assert_eq!(page.summary.deadline_misses, 1);
    let analysis = session
        .analysis_page(&FrameQuery::first_page(), 0, 1)
        .unwrap();
    assert_eq!(analysis.problem_total, 1);
    assert_eq!(analysis.problems.len(), 1);
    assert_eq!(analysis.problems[0].filtered_index, 1);
    assert_eq!(analysis.cluster_total, 1);
    assert_eq!(
        analysis.clusters[0].first_frame_id,
        analysis.problems[0].frame.id
    );
}

#[test]
fn zero_stage_timestamps_do_not_turn_into_real_durations() {
    let session = FrameSession::parse_gfxinfo(&capture(
        "Flags,IntendedVsync,HandleInputStart,AnimationStart,FrameCompleted",
        "0,100000000,0,0,110000000",
    ))
    .unwrap();
    assert_eq!(session.frames()[0].stages.input_ns, None);
}

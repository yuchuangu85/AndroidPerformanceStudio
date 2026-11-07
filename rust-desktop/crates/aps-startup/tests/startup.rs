//! Deferred tests: do not run before the full rewrite implementation is complete.
use aps_startup::{StartupMode, StartupQuery, StartupSession};

const AM_START: &str = "Starting: Intent { cmp=dev.sample/.MainActivity }\nStatus: ok\nLaunchState: COLD\nActivity: dev.sample/.MainActivity\nThisTime: 320\nTotalTime: 410\nWaitTime: 432\nComplete\n";

#[test]
fn am_start_metrics_are_not_silently_substituted_for_ttid_or_ttfd() {
    let session =
        StartupSession::parse_am_start(AM_START, "dev.sample", StartupMode::Warm).unwrap();
    let run = &session.runs()[0];
    assert_eq!(run.total_time_ms, Some(410));
    assert_eq!(run.requested_mode, StartupMode::Warm);
    assert_eq!(run.observed_mode, StartupMode::Cold);
    assert!(run.complete);
    assert_eq!(run.ttid.milliseconds, None);
    assert_eq!(run.ttfd.milliseconds, None);
    assert_eq!(session.summary().requested_observed_mismatches, 1);
}

#[test]
fn platform_displayed_and_fully_drawn_evidence_are_independent() {
    let events = "I/wm_activity_launch_time(123): [0,123,dev.sample/.MainActivity,210]\nI/wm_fully_drawn_time(123): [0,123,dev.sample/.MainActivity,540]\nI/wm_fully_drawn_time(222): [0,222,other.app/.MainActivity,999]\n";
    let session = StartupSession::parse_am_start(AM_START, "dev.sample", StartupMode::Cold)
        .unwrap()
        .with_event_log(events)
        .unwrap();
    assert_eq!(session.runs()[0].ttid.milliseconds, Some(210));
    assert_eq!(session.runs()[0].ttfd.milliseconds, Some(540));
    let displayed_only = StartupSession::parse_am_start(AM_START, "dev.sample", StartupMode::Cold)
        .unwrap()
        .with_event_log("ActivityTaskManager: Displayed dev.sample/.MainActivity: +1s210ms")
        .unwrap();
    assert_eq!(displayed_only.runs()[0].ttid.milliseconds, Some(1210));
    assert_eq!(displayed_only.runs()[0].ttfd.milliseconds, None);
}

#[test]
fn exact_package_boundary_and_single_launch_scope_are_required() {
    let session = StartupSession::parse_am_start(AM_START, "dev.sample", StartupMode::Cold)
        .unwrap()
        .with_event_log("ActivityTaskManager: Displayed dev.sample.other/.MainActivity: +210ms")
        .unwrap();
    assert_eq!(session.runs()[0].ttid.milliseconds, None);
    let events = "ActivityTaskManager: Displayed dev.sample/.MainActivity: +210ms\nActivityTaskManager: Displayed dev.sample/.MainActivity: +310ms";
    assert!(
        StartupSession::parse_am_start(AM_START, "dev.sample", StartupMode::Cold)
            .unwrap()
            .with_event_log(events)
            .is_err()
    );
}

#[test]
fn requested_mode_cannot_fill_missing_observed_mode() {
    let text = AM_START.replace("LaunchState: COLD\n", "");
    let session = StartupSession::parse_am_start(&text, "dev.sample", StartupMode::Cold).unwrap();
    assert_eq!(session.runs()[0].observed_mode, StartupMode::Unknown);
    assert_eq!(session.summary().unknown_observed_modes, 1);
}

#[test]
fn real_export_csv_quotes_newlines_and_missing_metrics_are_preserved() {
    let csv = "iteration,runId,requestedType,observedType,totalTimeMs,thisTimeMs,waitTimeMs,displayedTimeMs,fullyDrawnTimeMs,warnings,compilerFilter\n\"1\",\"first\",\"COLD\",\"COLD\",\"400\",\"300\",\"450\",\"210\",\"\",\"warning, with comma\nand newline\",\"speed-profile\"\n\"2\",\"second\",\"WARM\",\"UNKNOWN\",\"500\",\"\",\"\",\"\",\"\",\"\",\"\"\n";
    let session = StartupSession::parse_csv(csv).unwrap();
    assert_eq!(session.runs().len(), 2);
    assert_eq!(session.summary().ttid.count, 1);
    assert_eq!(session.summary().ttid.missing, 1);
    assert_eq!(session.summary().ttfd.count, 0);
    assert_eq!(
        session.runs()[0]
            .imported_fields
            .get("compilerFilter")
            .map(String::as_str),
        Some("speed-profile")
    );
    assert_eq!(
        session.runs()[0].warnings[0],
        "warning, with comma\nand newline"
    );
    assert_eq!(
        session
            .page(&StartupQuery {
                observed_mode: Some(StartupMode::Unknown),
                ..StartupQuery::default()
            })
            .unwrap()
            .total,
        1
    );
    assert!(
        session
            .page(&StartupQuery {
                limit: 513,
                ..StartupQuery::default()
            })
            .is_err()
    );
}

#[test]
fn malformed_negative_durations_multiple_runs_and_wrong_targets_fail_closed() {
    assert!(
        StartupSession::parse_am_start(
            &AM_START.replace("410", "-1"),
            "dev.sample",
            StartupMode::Cold
        )
        .is_err()
    );
    assert!(
        StartupSession::parse_am_start(
            &(AM_START.to_owned() + AM_START),
            "dev.sample",
            StartupMode::Cold
        )
        .is_err()
    );
    assert!(StartupSession::parse_am_start(AM_START, "other.app", StartupMode::Cold).is_err());
    assert!(StartupSession::parse_csv("iteration,runId,requestedType,observedType,displayedTimeMs,fullyDrawnTimeMs\n1,x,COLD,COLD,500,200\n").is_err());
    assert!(StartupSession::parse_csv("iteration,runId,requestedType,observedType,displayedTimeMs,fullyDrawnTimeMs\n1,\"unterminated").is_err());
}

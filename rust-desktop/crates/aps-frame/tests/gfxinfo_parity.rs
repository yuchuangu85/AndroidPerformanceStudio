//! Port of `parser-frame/src/test/.../GfxInfoFrameStatsParserTest.kt`.

use aps_frame::{ExpectedDurationSource, FrameSource, parse_gfxinfo};

fn parse(text: &str, session_id: &str) -> aps_frame::GfxInfoParse {
    parse_gfxinfo(text, session_id, None, true).expect("gfxinfo text parses")
}

fn parse_with_package(text: &str, session_id: &str, package_name: &str) -> aps_frame::GfxInfoParse {
    parse_gfxinfo(text, session_id, Some(package_name), true).expect("gfxinfo text parses")
}

#[test]
fn parses_deadline_and_stage_durations_by_column_name() {
    let result = parse_with_package(FRAMESTATS_WITH_DEADLINE, "session", "com.example");

    assert!(result.warnings.is_empty(), "{:?}", result.warnings);
    assert_eq!(result.frames.len(), 2);
    let first = &result.frames[0];
    assert_eq!(first.expected_duration_ns, Some(8_333_333));
    assert_eq!(
        first.expected_duration_source,
        ExpectedDurationSource::PlatformDeadline
    );
    assert_eq!(first.total_duration_ns, Some(10_000_000));
    assert_eq!(first.stages.input_ns, Some(1_000_000));
    assert_eq!(first.stages.layout_measure_ns, Some(2_000_000));
    assert_eq!(first.package_name.as_deref(), Some("com.example"));
    assert_eq!(first.source, FrameSource::Gfxinfo);
}

#[test]
fn marks_nonzero_flag_rows_ineligible_for_jank_classification() {
    let result = parse(
        &FRAMESTATS_WITH_DEADLINE.replacen("0,100000000", "1,100000000", 1),
        "session",
    );

    assert!(!result.frames[0].eligible_for_jank);
    assert_eq!(
        result.frames[0]
            .states
            .get("gfxinfo.flags")
            .map(String::as_str),
        Some("1")
    );
}

#[test]
fn infers_refresh_interval_when_legacy_rows_have_no_deadline() {
    let result = parse(LEGACY_FRAMESTATS, "legacy");

    assert_eq!(result.frames[0].expected_duration_ns, Some(8_333_333));
    assert_eq!(
        result.frames[0].expected_duration_source,
        ExpectedDurationSource::InferredVsync
    );
}

#[test]
fn keeps_unavailable_zero_timestamp_stages_unknown() {
    let result = parse(
        &FRAMESTATS_WITH_DEADLINE.replacen("101000000,102000000", "0,0", 1),
        "session",
    );

    assert_eq!(result.frames[0].stages.input_ns, None);
}

#[test]
fn infers_legacy_budgets_per_frame_across_a_refresh_mode_change() {
    let result = parse(LEGACY_FRAMESTATS_WITH_MODE_CHANGE, "legacy");

    assert_eq!(
        result
            .frames
            .iter()
            .map(|frame| frame.expected_duration_ns)
            .collect::<Vec<_>>(),
        [
            Some(8_333_333),
            Some(8_333_333),
            Some(16_666_667),
            Some(16_666_667)
        ]
    );
    assert!(
        result
            .warnings
            .iter()
            .any(|warning| warning.contains("Multiple frame intervals"))
    );
}

#[test]
fn parses_repeated_window_schemas_and_preserves_unknown_numeric_columns() {
    let result = parse(MULTI_WINDOW_FRAMESTATS, "multi");

    assert_eq!(
        result
            .frames
            .iter()
            .map(|frame| frame.window_id.as_deref())
            .collect::<Vec<_>>(),
        [Some("Window A"), Some("Window B")]
    );
    assert_eq!(
        result.frames[1]
            .states
            .get("gfxinfo.column.FutureStage")
            .map(String::as_str),
        Some("7")
    );
    assert!(
        result
            .warnings
            .iter()
            .any(|warning| warning.contains("FutureStage"))
    );
}

#[test]
fn parses_modern_device_columns_with_trailing_separators_without_unsupported_warnings() {
    let result = parse(MODERN_FRAMESTATS, "modern");

    assert!(result.warnings.is_empty(), "{:?}", result.warnings);
    assert_eq!(result.frames.len(), 2);
    let first = &result.frames[0];
    assert_eq!(first.frame_timeline_vsync_id, Some(477300));
    assert_eq!(first.expected_duration_ns, Some(24_132_507));
    assert_eq!(
        first.expected_duration_source,
        ExpectedDurationSource::PlatformDeadline
    );
    assert_eq!(first.total_duration_ns, Some(13_826_484));
    assert_eq!(
        first
            .states
            .get("gfxinfo.column.FrameTimelineVsyncId")
            .map(String::as_str),
        Some("477300")
    );
    assert_eq!(
        first
            .states
            .get("gfxinfo.column.InputEventId")
            .map(String::as_str),
        Some("975831300")
    );
    assert_eq!(
        first
            .states
            .get("gfxinfo.column.FrameStartTime")
            .map(String::as_str),
        Some("8373159")
    );
    assert_eq!(
        first
            .states
            .get("gfxinfo.column.CommandSubmissionCompleted")
            .map(String::as_str),
        Some("104583071253309")
    );
    assert_eq!(first.platform_jank, None);
}

#[test]
fn accepts_trailing_separators_independently_on_headers_and_rows() {
    for header_separator in [false, true] {
        for row_separator in [false, true] {
            let text = format!(
                "---PROFILEDATA---\nFlags,IntendedVsync,FrameCompleted{}\n0,100000000,110000000{}\n---PROFILEDATA---",
                if header_separator { "," } else { "" },
                if row_separator { "," } else { "" }
            );
            let result = parse(&text, "separators");

            assert_eq!(result.frames.len(), 1);
            assert!(result.warnings.is_empty(), "{:?}", result.warnings);
        }
    }
}

#[test]
fn keeps_an_empty_named_last_column_without_treating_it_as_a_missing_field() {
    let result = parse(
        "---PROFILEDATA---\nFlags,IntendedVsync,FrameCompleted,CommandSubmissionCompleted,\n0,100000000,110000000,,\n---PROFILEDATA---",
        "empty-column",
    );

    assert_eq!(result.frames.len(), 1);
    assert!(result.warnings.is_empty(), "{:?}", result.warnings);
}

#[test]
fn still_preserves_and_warns_about_unknown_columns_with_trailing_separators() {
    let result = parse(
        "---PROFILEDATA---\nFlags,IntendedVsync,FrameCompleted,FutureStage,\n0,100000000,110000000,7,\n---PROFILEDATA---",
        "future",
    );

    assert_eq!(
        result.frames[0]
            .states
            .get("gfxinfo.column.FutureStage")
            .map(String::as_str),
        Some("7")
    );
    assert_eq!(
        result.warnings,
        ["Preserved unsupported framestats column(s): FutureStage."]
    );
}

#[test]
fn retains_malformed_row_warnings_when_suppressing_empty_snapshot_warnings() {
    let result = parse_gfxinfo(
        "---PROFILEDATA---\nFlags,IntendedVsync,FrameCompleted,\n0,100000000\n0,100000000,110000000,7,\n---PROFILEDATA---",
        "malformed",
        None,
        false,
    )
    .expect("gfxinfo text parses");

    assert!(result.frames.is_empty());
    assert_eq!(result.warnings, ["2 malformed frame row(s) were skipped."]);
}

#[test]
fn empty_imports_retain_the_warning_while_live_snapshots_can_suppress_it() {
    let text = "---PROFILEDATA---\nFlags,IntendedVsync,FrameCompleted,\n---PROFILEDATA---";

    assert_eq!(
        parse(text, "import").warnings,
        ["The framestats section did not contain usable frames."]
    );
    assert!(
        parse_gfxinfo(text, "live", None, false)
            .expect("gfxinfo text parses")
            .warnings
            .is_empty()
    );
    assert_eq!(
        parse_gfxinfo("invalid", "live", None, false)
            .expect("gfxinfo text parses")
            .warnings,
        ["No gfxinfo framestats header was found."]
    );
}

#[test]
fn explicit_frame_interval_changes_are_not_described_as_legacy_inference() {
    let result = parse(
        &MULTI_WINDOW_FRAMESTATS.replacen("16666667,210000000", "8333333,210000000", 1),
        "explicit",
    );

    assert_eq!(
        result
            .frames
            .iter()
            .map(|frame| frame.expected_duration_ns)
            .collect::<Vec<_>>(),
        [Some(16_666_667), Some(8_333_333)]
    );
    assert!(
        result
            .frames
            .iter()
            .all(|frame| frame.expected_duration_source == ExpectedDurationSource::FrameInterval)
    );
    assert!(
        !result
            .warnings
            .iter()
            .any(|warning| warning.contains("legacy frame budgets"))
    );
}

#[test]
fn parse_assigns_sequential_frame_ids_and_keeps_window_headers() {
    let result = parse(MULTI_WINDOW_FRAMESTATS, "multi");

    assert_eq!(
        result
            .frames
            .iter()
            .map(|frame| frame.frame_id)
            .collect::<Vec<_>>(),
        [0, 1]
    );
}

const MODERN_FRAMESTATS: &str = r"---PROFILEDATA---
Flags,FrameTimelineVsyncId,IntendedVsync,Vsync,InputEventId,HandleInputStart,AnimationStart,PerformTraversalsStart,DrawStart,FrameDeadline,FrameInterval,FrameStartTime,SyncQueued,SyncStart,IssueDrawCommandsStart,SwapBuffers,FrameCompleted,DequeueBufferDuration,QueueBufferDuration,GpuCompleted,SwapBuffersCompleted,DisplayPresentTime,CommandSubmissionCompleted,
0,477300,104583059254518,104583059254518,975831300,104583064459771,104583067407848,104583067413002,104583070297694,104583083387025,104583064449386,8373159,104583070469925,104583070543156,104583070723540,104583071253309,104583073081002,19384,221923,104583073081002,104583071906232,104583082254848,104583071253309,
0,477315,104583067638732,104583067638732,530497226,104583073060771,104583074875463,104583074876694,104583074956463,104583091760184,104583072699771,8374588,104583075110848,104583075177079,104583075347540,104583075921079,104583077835540,20231,468616,104583077835540,104583077273617,104583090604079,104583075921079,
---PROFILEDATA---";

const FRAMESTATS_WITH_DEADLINE: &str = r"---PROFILEDATA---
Flags,IntendedVsync,Vsync,HandleInputStart,AnimationStart,PerformTraversalsStart,DrawStart,FrameDeadline,FrameInterval,SyncQueued,SyncStart,IssueDrawCommandsStart,SwapBuffers,FrameCompleted,GpuCompleted,DisplayPresentTime
0,100000000,100000000,101000000,102000000,103000000,105000000,108333333,8333333,106000000,106500000,107000000,108000000,110000000,111000000,110000000
0,108333333,108333333,109000000,110000000,111000000,112000000,116666666,8333333,113000000,113500000,114000000,115000000,116000000,117000000,116000000
---PROFILEDATA---";

const LEGACY_FRAMESTATS: &str = r"---PROFILEDATA---
Flags,IntendedVsync,Vsync,HandleInputStart,AnimationStart,PerformTraversalsStart,DrawStart,SyncQueued,SyncStart,IssueDrawCommandsStart,SwapBuffers,FrameCompleted
0,100000000,100000000,101000000,102000000,103000000,104000000,105000000,105000000,106000000,107000000,108000000
0,108333333,108333333,109000000,110000000,111000000,112000000,113000000,113000000,114000000,115000000,116000000
0,116666666,116666666,117000000,118000000,119000000,120000000,121000000,121000000,122000000,123000000,124000000
---PROFILEDATA---";

const LEGACY_FRAMESTATS_WITH_MODE_CHANGE: &str = r"---PROFILEDATA---
Flags,IntendedVsync,Vsync,FrameCompleted
0,100000000,100000000,108000000
0,108333333,108333333,116000000
0,116666666,116666666,124000000
0,133333333,133333333,141000000
---PROFILEDATA---";

const MULTI_WINDOW_FRAMESTATS: &str = r"Window: Window A
---PROFILEDATA---
Flags,IntendedVsync,Vsync,FrameInterval,FrameCompleted
0,100000000,100000000,16666667,110000000
---PROFILEDATA---
Window: Window B
---PROFILEDATA---
Flags,IntendedVsync,Vsync,FrameInterval,FrameCompleted,FutureStage
0,200000000,200000000,16666667,210000000,7
---PROFILEDATA---";

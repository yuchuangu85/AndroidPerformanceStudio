//! Port of `analysis-frame/src/test/.../FrameTimelineTraceAdapterTest.kt` plus
//! capability subtraction checks.

use aps_frame::{
    FRAME_TIMELINE_JANK_TAG, FRAME_TIMELINE_PRESENT_TYPE, FrameSource, FrameTimelineRow,
    FrameTimelineStatus, frame_timeline_status, map_timeline_rows,
};

fn row(frame_id: u64) -> FrameTimelineRow {
    FrameTimelineRow {
        frame_id,
        expected_ts: Some(1000),
        expected_dur: Some(16_666_666),
        actual_ts: Some(1100),
        actual_dur: Some(18_000_000),
        surface_token: Some(7),
        layer_name: Some("SurfaceView".to_owned()),
        jank_type: Some("None".to_owned()),
        jank_tag: Some("No Jank".to_owned()),
        present_type: Some("On-time Present".to_owned()),
        process_name: Some("com.example".to_owned()),
        surface_flinger_jank_type: Some("None".to_owned()),
    }
}

#[test]
fn maps_expected_and_actual_slices_with_surface_correlation_and_jank() {
    let result = map_timeline_rows(vec![FrameTimelineRow {
        jank_type: Some("Missed deadline".to_owned()),
        jank_tag: Some("Self Jank".to_owned()),
        present_type: Some("Late Present".to_owned()),
        surface_flinger_jank_type: Some("Missed deadline".to_owned()),
        ..row(42)
    }]);

    let frame = &result.frames[0];
    assert_eq!(frame.source, FrameSource::Perfetto);
    assert_eq!(frame.frame_timeline_vsync_id, Some(42));
    assert_eq!(frame.frame_id, 42);
    assert_eq!(frame.total_duration_ns, Some(18_000_000));
    assert_eq!(frame.intended_vsync_ns, Some(1000));
    assert_eq!(frame.actual_vsync_ns, Some(1100));
    assert_eq!(frame.expected_duration_ns, Some(16_666_666));
    assert_eq!(frame.platform_jank, Some(true));
    assert_eq!(
        frame.activity_name.as_deref(),
        Some("com.example: SurfaceView")
    );
    assert!(result.capabilities.surface_correlation);
    assert_eq!(
        frame
            .states
            .get("surfaceFlingerJankType")
            .map(String::as_str),
        Some("Missed deadline")
    );
    assert_eq!(
        frame.states.get("surfaceFrameToken").map(String::as_str),
        Some("7")
    );
    assert_eq!(
        frame
            .states
            .get(FRAME_TIMELINE_JANK_TAG)
            .map(String::as_str),
        Some("Self Jank")
    );
    assert_eq!(
        frame
            .states
            .get(FRAME_TIMELINE_PRESENT_TYPE)
            .map(String::as_str),
        Some("Late Present")
    );
    assert_eq!(
        frame
            .platform_jank_types
            .iter()
            .map(|value| value.as_name())
            .collect::<Vec<_>>(),
        ["PLATFORM_REPORTED"]
    );
}

#[test]
fn normal_and_stuffing_frames_are_not_platform_jank_and_missing_actual_evidence_stays_unknown() {
    let mut normal = row(1);
    normal.jank_type = Some("None".to_owned());
    normal.jank_tag = Some("No Jank".to_owned());
    normal.present_type = Some("On-time Present".to_owned());

    let mut buffer_stuffing = row(2);
    buffer_stuffing.expected_ts = Some(2000);
    buffer_stuffing.actual_ts = Some(2100);
    buffer_stuffing.actual_dur = Some(28_000_000);
    buffer_stuffing.jank_type = Some("Buffer Stuffing".to_owned());
    buffer_stuffing.jank_tag = Some("Buffer Stuffing".to_owned());
    buffer_stuffing.present_type = Some("Late Present".to_owned());

    let mut surface_flinger_stuffing = row(3);
    surface_flinger_stuffing.expected_ts = Some(3000);
    surface_flinger_stuffing.actual_ts = Some(3100);
    surface_flinger_stuffing.actual_dur = Some(28_000_000);
    surface_flinger_stuffing.jank_type = Some("SurfaceFlinger Stuffing".to_owned());
    surface_flinger_stuffing.jank_tag = Some("SurfaceFlinger Stuffing".to_owned());
    surface_flinger_stuffing.present_type = Some("Late Present".to_owned());

    let mut missing_actual = row(4);
    missing_actual.expected_ts = Some(4000);
    missing_actual.actual_ts = None;
    missing_actual.actual_dur = None;
    missing_actual.jank_type = None;
    missing_actual.jank_tag = None;
    missing_actual.present_type = None;
    missing_actual.surface_flinger_jank_type = None;

    let result = map_timeline_rows(vec![
        normal,
        buffer_stuffing,
        surface_flinger_stuffing,
        missing_actual,
    ]);

    assert_eq!(
        result
            .frames
            .iter()
            .map(|frame| frame.platform_jank)
            .collect::<Vec<_>>(),
        [Some(false), Some(false), Some(false), None]
    );
    assert_eq!(
        result
            .frames
            .iter()
            .map(frame_timeline_status)
            .collect::<Vec<_>>(),
        [
            FrameTimelineStatus::Good,
            FrameTimelineStatus::HighLatency,
            FrameTimelineStatus::HighLatency,
            FrameTimelineStatus::Unknown,
        ]
    );
}

#[test]
fn subtracts_surface_correlation_without_any_correlated_row() {
    let mut uncorrelated = row(7);
    uncorrelated.surface_token = None;

    let result = map_timeline_rows(vec![uncorrelated]);

    assert!(!result.capabilities.surface_correlation);
    assert!(result.capabilities.expected_timeline);
    assert!(result.capabilities.actual_timeline);
    assert!(result.capabilities.jank_classification);
}

#[test]
fn joins_process_and_layer_like_the_compose_adapter() {
    let mut layer_only = row(1);
    layer_only.process_name = None;
    let mut process_only = row(2);
    process_only.layer_name = None;
    let mut neither = row(3);
    neither.process_name = None;
    neither.layer_name = None;

    let result = map_timeline_rows(vec![layer_only, process_only, neither]);

    assert_eq!(
        result
            .frames
            .iter()
            .map(|frame| frame.activity_name.clone())
            .collect::<Vec<_>>(),
        [
            Some("SurfaceView".to_owned()),
            Some("com.example".to_owned()),
            None
        ]
    );
}

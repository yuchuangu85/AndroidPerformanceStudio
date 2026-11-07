//! Perfetto FrameTimeline import for the Frame Analyzer.
//!
//! Uses the shared checksum-pinned Trace Processor resolver and the fixed
//! aps-perfetto FrameTimeline query. Row mapping into FrameSample and the
//! capability set is aps-frame's Compose port; this module only bridges the
//! pinned tool, the trace session and the bounded row list. Empty input is an
//! explicit successful zero-row result so the runtime can surface the Compose
//! "no FrameTimeline rows" message.

use aps_frame::{
    FrameSample, FrameTimelineCapabilities, FrameTimelineResult, FrameTimelineRow,
    map_timeline_rows,
};
use aps_perfetto::TraceSession;
use aps_platform::CancellationToken;
use std::path::Path;

pub(in crate::frame) struct PerfettoTimeline {
    pub(in crate::frame) frames: Vec<FrameSample>,
    pub(in crate::frame) tool_version: String,
    pub(in crate::frame) capabilities: FrameTimelineCapabilities,
}

pub(in crate::frame) fn analyze_trace(
    trace: &Path,
    cancel: &CancellationToken,
) -> Result<PerfettoTimeline, String> {
    let tool = crate::perfetto_tool::locate(cancel)?;
    let tool_version = tool.version().to_owned();
    let session = TraceSession::open(tool, trace, cancel)
        .map_err(|error| format!("Cannot open the Perfetto FrameTimeline trace: {error}"))?;
    let rows = session
        .frame_timeline_rows(None, cancel)
        .map_err(|error| format!("Cannot read Perfetto FrameTimeline rows: {error}"))?;
    let result = map_rows(rows)?;
    Ok(PerfettoTimeline {
        frames: result.frames,
        tool_version,
        capabilities: result.capabilities,
    })
}

fn map_rows(
    rows: Vec<aps_perfetto::frame_timeline::FrameTimelineRow>,
) -> Result<FrameTimelineResult, String> {
    let mapped = rows
        .into_iter()
        .map(|row| {
            Ok(FrameTimelineRow {
                frame_id: u64::try_from(row.frame_id).map_err(|_| {
                    "Perfetto returned a negative FrameTimeline frame id".to_owned()
                })?,
                expected_ts: row.expected_ts,
                expected_dur: row.expected_dur,
                actual_ts: row.actual_ts,
                actual_dur: row.actual_dur,
                surface_token: row.surface_token,
                layer_name: row.layer_name,
                jank_type: row.jank_type,
                jank_tag: row.jank_tag,
                present_type: row.present_type,
                process_name: row.process_name,
                surface_flinger_jank_type: row.surface_flinger_jank_type,
            })
        })
        .collect::<Result<Vec<_>, String>>()?;
    Ok(map_timeline_rows(mapped))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;

    fn row(frame_id: i64) -> aps_perfetto::frame_timeline::FrameTimelineRow {
        aps_perfetto::frame_timeline::FrameTimelineRow {
            frame_id,
            expected_ts: Some(1_000),
            expected_dur: Some(16_666_666),
            actual_ts: Some(1_100),
            actual_dur: Some(18_000_000),
            surface_token: Some(7),
            layer_name: Some("SurfaceView".into()),
            jank_type: Some("Missed deadline".into()),
            jank_tag: Some("Self Jank".into()),
            present_type: Some("Late Present".into()),
            process_name: Some("com.example".into()),
            surface_flinger_jank_type: Some("Missed deadline".into()),
        }
    }

    #[test]
    fn maps_expected_and_actual_rows_with_surface_correlation_and_jank() {
        let result = map_rows(vec![row(42)]).unwrap();
        let frame = &result.frames[0];
        assert_eq!(frame.source.as_name(), "PERFETTO");
        assert_eq!(frame.frame_id, 42);
        assert_eq!(frame.frame_timeline_vsync_id, Some(42));
        assert_eq!(frame.total_duration_ns, Some(18_000_000));
        assert_eq!(frame.expected_duration_ns, Some(16_666_666));
        assert_eq!(frame.intended_vsync_ns, Some(1_000));
        assert_eq!(frame.actual_vsync_ns, Some(1_100));
        assert_eq!(frame.platform_jank, Some(true));
        assert!(
            frame
                .platform_jank_types
                .iter()
                .any(|kind| kind.as_name() == "PLATFORM_REPORTED")
        );
        assert_eq!(
            frame.states.get("surfaceFrameToken").map(String::as_str),
            Some("7")
        );
        assert_eq!(
            frame
                .states
                .get("surfaceFlingerJankType")
                .map(String::as_str),
            Some("Missed deadline")
        );
        assert_eq!(
            frame
                .states
                .get(aps_frame::FRAME_TIMELINE_JANK_TAG)
                .map(String::as_str),
            Some("Self Jank")
        );
        assert_eq!(
            frame
                .states
                .get(aps_frame::FRAME_TIMELINE_PRESENT_TYPE)
                .map(String::as_str),
            Some("Late Present")
        );
        assert!(result.capabilities.surface_correlation);
    }

    #[test]
    fn normal_stuffing_and_missing_actual_rows_stay_unclassified() {
        let mut normal = row(1);
        normal.jank_type = Some("None".into());
        normal.jank_tag = Some("No Jank".into());
        normal.present_type = Some("On-time Present".into());
        let mut stuffing = row(2);
        stuffing.jank_type = Some("Buffer Stuffing".into());
        let mut surfaceflinger_stuffing = row(3);
        surfaceflinger_stuffing.jank_type = Some("SurfaceFlinger Stuffing".into());
        let mut missing = row(4);
        missing.jank_type = None;
        missing.jank_tag = None;
        missing.present_type = None;
        missing.actual_ts = None;
        missing.actual_dur = None;
        missing.surface_flinger_jank_type = None;

        let result = map_rows(vec![normal, stuffing, surfaceflinger_stuffing, missing]).unwrap();
        assert_eq!(
            result
                .frames
                .iter()
                .map(|frame| frame.platform_jank)
                .collect::<Vec<_>>(),
            [Some(false), Some(false), Some(false), None]
        );
        assert!(
            result
                .frames
                .iter()
                .all(|frame| frame.platform_jank_types.is_empty())
        );
    }

    #[test]
    fn negative_frame_ids_fail_closed() {
        assert!(map_rows(vec![row(-1)]).is_err());
    }

    #[test]
    fn pinned_tool_reads_a_synthetic_trace_with_zero_frame_timeline_rows() {
        let repository_root = Path::new(env!("CARGO_MANIFEST_DIR"))
            .ancestors()
            .nth(3)
            .expect("repository root");
        let fixture: PathBuf = repository_root
            .join("desktop-viewer/desktop-app/src/test/resources/fixtures/synthetic-empty.pftrace");
        if !fixture.is_file() {
            return;
        }
        let cancel = CancellationToken::new();
        let Ok(tool) = crate::perfetto_tool::locate(&cancel) else {
            return;
        };
        let Ok(session) = TraceSession::open(tool, &fixture, &cancel) else {
            return;
        };
        let rows = session
            .frame_timeline_rows(None, &cancel)
            .expect("synthetic trace must query cleanly");
        assert!(rows.is_empty());
    }
}

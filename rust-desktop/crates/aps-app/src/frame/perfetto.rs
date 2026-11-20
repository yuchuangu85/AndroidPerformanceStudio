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
use aps_perfetto::{TraceProcessor, TraceSession};
use aps_platform::CancellationToken;
use std::path::Path;

pub(in crate::frame) struct PerfettoTimeline {
    pub(in crate::frame) frames: Vec<FrameSample>,
    pub(super) probe_warning: Option<String>,
    pub(super) probe_query_complete: bool,
    pub(super) measured_process: Option<String>,
    pub(super) source_digest: aps_contracts::SourceDigest,
    pub(in crate::frame) tool_version: String,
    pub(in crate::frame) capabilities: FrameTimelineCapabilities,
}

pub(in crate::frame) fn analyze_trace(
    trace: &Path,
    cancel: &CancellationToken,
) -> Result<PerfettoTimeline, String> {
    analyze_with_tool(crate::perfetto_tool::locate(cancel)?, trace, None, cancel)
}

pub(in crate::frame) fn analyze_with_tool(
    tool: TraceProcessor,
    trace: &Path,
    pid: Option<i32>,
    cancel: &CancellationToken,
) -> Result<PerfettoTimeline, String> {
    let tool_version = tool.version().to_owned();
    let session = TraceSession::open(tool, trace, cancel)
        .map_err(|error| format!("Cannot open the Perfetto FrameTimeline trace: {error}"))?;
    let rows = session
        .frame_timeline_rows(pid, cancel)
        .map_err(|error| format!("Cannot read Perfetto FrameTimeline rows: {error}"))?;
    // PID is scoped by the fixed query. Do not certify a capture on PID alone:
    // require an independent matching process name on every returned trace row.
    let measured_process = pid.and_then(|_| {
        let name = rows.first()?.process_name.as_ref()?;
        (!name.is_empty()
            && rows
                .iter()
                .all(|row| row.process_name.as_ref() == Some(name)))
        .then(|| name.clone())
    });
    let keys: Vec<_> = rows.iter().map(|r| r.actual_slice_id).collect();
    let mut result = map_rows(rows)?;
    let mut probe_warning = None;
    let mut probe_query_complete = false;
    if keys.iter().any(Option::is_some) {
        match session.frame_probes(pid, cancel) {
            Ok(hits) => {
                attach_probes(&mut result.frames, keys, hits);
                probe_query_complete = true;
            }
            Err(
                aps_perfetto::PerfettoError::Cancelled | aps_perfetto::PerfettoError::SourceChanged,
            ) => return Err("Frame trace changed or was cancelled.".into()),
            Err(err) => probe_warning = Some(format!("App Trace overlap unavailable: {err}")),
        }
    }
    Ok(PerfettoTimeline {
        frames: result.frames,
        probe_warning,
        probe_query_complete,
        measured_process,
        source_digest: session.source_digest(),
        tool_version,
        capabilities: result.capabilities,
    })
}

// Keep bounded typed facts alongside each sample so filtering, persistence and
// export retain row identity. Do not join on display vsync IDs across layers.
fn attach_probes(
    frames: &mut [FrameSample],
    keys: Vec<Option<i64>>,
    hits: Vec<(i64, aps_perfetto::frame_probes::Probe)>,
) {
    use aps_perfetto::frame_probes::PER_FRAME;
    let mut by_id = std::collections::HashMap::<_, Vec<_>>::new();
    for (id, probe) in hits {
        by_id.entry(id).or_default().push(probe);
    }
    for (frame, id) in frames.iter_mut().zip(keys) {
        let Some(probes) = id.and_then(|k| by_id.get(&k)) else {
            continue;
        };
        for (i, p) in probes.iter().take(PER_FRAME).enumerate() {
            frame.states.insert(
                format!("aps.trace.{i}"),
                format!(
                    "{} {} {} {} {}",
                    p.marker,
                    p.tid,
                    p.overlap_ns,
                    p.scheduled_ns.unwrap_or(-1),
                    p.role.label()
                ),
            );
        }
        if probes.len() > PER_FRAME {
            frame.states.insert(
                "aps.trace.omitted".into(),
                (probes.len() - PER_FRAME).to_string(),
            );
        }
    }
}

pub(super) use aps_frame::probe::probes;

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
            actual_slice_id: Some(frame_id),
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
    fn generated_probe_survives_trace_import_selection_report_roundtrip_and_ai_projection() {
        use crate::frame::{ai, analysis_json, export, trace_patch};
        let dir = aps_platform::TempWorkspace::create(&std::env::temp_dir()).unwrap();
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..");
        let binary = if cfg!(windows) {
            "trace_processor_shell.exe"
        } else {
            "trace_processor_shell"
        };
        if !root.join("build/perfetto-tools").join(binary).is_file() {
            return;
        }
        let marker = trace_patch::trace_patch(
            "app/HiddenFeed.kt",
            "fun bindSecret() {\n    draw()\n}\n",
            1,
        )
        .unwrap()
        .marker;
        let mut bytes =
            include_bytes!("../../../aps-perfetto/tests/fixtures/frame-probes.pftrace").to_vec();
        let original = b"APS.Frame/0000000000000001";
        let at = bytes
            .windows(original.len())
            .position(|w| w == original)
            .unwrap();
        bytes[at..at + original.len()].copy_from_slice(marker.as_bytes());
        let trace = dir.path().join("recapture.pftrace");
        std::fs::write(&trace, bytes).unwrap();
        let cancel = CancellationToken::new();
        let timeline = analyze_with_tool(
            TraceProcessor::from_repository(&root, &cancel).unwrap(),
            &trace,
            None,
            &cancel,
        )
        .unwrap();
        assert!(timeline.probe_warning.is_none());
        assert_eq!(timeline.frames.len(), 2);
        let sample = timeline
            .frames
            .iter()
            .find(|s| probes(s).first().is_some_and(|p| p.marker == marker))
            .unwrap();
        let frame_id = sample.frame_id;
        assert_eq!(probes(sample)[0].scheduled_ns, Some(7));
        let analyzer = aps_frame::FrameJankAnalyzer::new();
        let result = analyzer.analyze(&timeline.frames);
        let file = dir.path().join("frame-analysis.json");
        export::export_json(&result, &file).unwrap();
        let restored =
            analysis_json::parse_export(&std::fs::read_to_string(file).unwrap(), "reopen").unwrap();
        let request = ai::probe_plan(&analyzer.analyze(&restored), frame_id).unwrap();
        let evidence = &request.evidence[0].payload["frames"][0]["appTraceOverlaps"];
        assert_eq!(evidence.as_array().unwrap().len(), 2);
        assert_eq!(evidence[0]["marker"].as_str(), Some(marker.as_str()));
        assert_eq!(evidence[0]["threadRole"].as_str(), Some("Main"));
        assert_eq!(evidence[0]["windowOverlapNs"].as_i64(), Some(20));
        assert_eq!(evidence[0]["observedScheduledNs"].as_i64(), Some(7));
        assert!(
            !request.evidence[0]
                .payload
                .to_string()
                .contains("HiddenFeed")
        );
        assert!(request.candidates.is_empty());
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
            .join("rust-desktop/crates/aps-perfetto/tests/fixtures/synthetic-empty.pftrace");
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

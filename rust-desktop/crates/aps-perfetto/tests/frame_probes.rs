use aps_perfetto::{PerfettoError, TraceProcessor, TraceSession};
use aps_platform::CancellationToken;
use std::path::Path;
#[test]
fn real_processor_clips_to_actual_window_and_process_despite_shared_display_token() {
    let root = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..");
    if !root
        .join("build/perfetto-tools")
        .join(if cfg!(windows) {
            "trace_processor_shell.exe"
        } else {
            "trace_processor_shell"
        })
        .is_file()
    {
        eprintln!("Pinned tool unavailable");
        return;
    }
    let c = CancellationToken::new();
    let session = TraceSession::open(
        TraceProcessor::from_repository(&root, &c).unwrap(),
        &Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/frame-probes.pftrace"),
        &c,
    )
    .unwrap();
    let rows = session.frame_timeline_rows(Some(30), &c).unwrap();
    assert_eq!(rows.len(), 1);
    let hits = session.frame_probes(Some(30), &c).unwrap();
    assert_eq!(hits.len(), 2);
    assert_eq!(hits[1].1.role.label(), "RenderThread");
    assert_eq!(hits[1].1.overlap_ns, 12);
    assert_eq!(hits[1].1.scheduled_ns, None);
    assert_eq!(Some(hits[0].0), rows[0].actual_slice_id);
    let p = &hits[0].1;
    assert_eq!(
        (&*p.marker, p.tid, p.overlap_ns, p.scheduled_ns),
        ("APS.Frame/0000000000000001", 30, 20, Some(7))
    );
    let all = session.frame_probes(None, &c).unwrap();
    assert_eq!(all.len(), 3);
    assert!(all.iter().any(|(id, p)| *id != hits[0].0
        && p.tid == 40
        && p.overlap_ns == 16
        && p.scheduled_ns.is_none()));
    c.cancel();
    assert_eq!(
        session.frame_probes(None, &c),
        Err(PerfettoError::Cancelled)
    );
}

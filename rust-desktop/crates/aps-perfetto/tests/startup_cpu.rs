//! Real pinned Trace Processor queries against a small synthetic Android trace.
//! Skip only when no host tool was installed, not when a present tool disagrees.
use aps_perfetto::{
    PerfettoError, StartupCpuEvidence, StartupCpuHotspot, StartupCpuScheduling, TraceProcessor,
    TraceSession,
};
use aps_platform::{CancellationToken, TempWorkspace};
use std::path::{Path, PathBuf};

fn root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..")
}

fn pinned(cancel: &CancellationToken) -> Option<TraceProcessor> {
    if !root()
        .join("build/perfetto-tools")
        .join(if cfg!(windows) {
            "trace_processor_shell.exe"
        } else {
            "trace_processor_shell"
        })
        .is_file()
    {
        eprintln!("skipping startup_cpu integration: no host tool installed");
        return None;
    }
    Some(TraceProcessor::from_repository(&root(), cancel).expect("verify installed processor"))
}

fn fixture() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("tests/fixtures/startup-cpu.pftrace")
}

#[test]
fn startup_cpu_scopes_by_first_display_main_thread_and_half_open_window() {
    let cancel = CancellationToken::new();
    let Some(tool) = pinned(&cancel) else { return };
    let session = TraceSession::open(tool, &fixture(), &cancel).unwrap();
    let StartupCpuEvidence::Measured(w) = session
        .startup_cpu_hotspots("com.example.startup", &cancel)
        .unwrap()
    else {
        panic!("missing fixture window")
    };
    assert_eq!(
        (w.startup_id, w.pid, w.tid, w.start_ns, w.end_ns),
        (7, 30, 30, 100, 130)
    );
    // [100,101) is unknown, not blocked. Running ends after TTID; clip it.
    assert_eq!(
        w.scheduling,
        Some(StartupCpuScheduling {
            running_ns: 19,
            runnable_ns: 10,
            other_ns: 0
        })
    );
    assert_eq!((w.total_samples, w.unresolved_samples), (3, 1));
    assert_eq!(w.trace_perf_lost_records, 12);
    assert_eq!(
        w.hotspots,
        vec![StartupCpuHotspot {
            symbol: "Example.init".into(),
            samples: 2
        }]
    );
    assert!(matches!(
        session
            .startup_cpu_hotspots("com.example.start", &cancel)
            .unwrap(),
        StartupCpuEvidence::Unavailable(_)
    ));
    assert!(matches!(
        session
            .startup_cpu_hotspots("com.example.secondx", &cancel)
            .unwrap(),
        StartupCpuEvidence::Unavailable(_)
    ));
    assert_eq!(
        session.startup_cpu_hotspots("' OR 1=1 --", &cancel),
        Err(PerfettoError::InvalidPage)
    );
    cancel.cancel();
    assert_eq!(
        session.startup_cpu_hotspots("com.example.startup", &cancel),
        Err(PerfettoError::Cancelled)
    );
}

fn replace_marker(bytes: &mut [u8], from: &[u8], to: &[u8]) {
    assert_eq!(from.len(), to.len());
    let at = bytes
        .windows(from.len())
        .position(|s| s == from)
        .expect("fixture marker");
    bytes[at..at + from.len()].copy_from_slice(to);
}

#[test]
fn ambiguous_launch_and_missing_frame_remain_unavailable() {
    let cancel = CancellationToken::new();
    let Some(tool) = pinned(&cancel) else { return };
    let workspace = TempWorkspace::create(&std::env::temp_dir()).unwrap();
    for (from, to, expected) in [
        ("com.example.secondx", "com.example.startup", "Ambiguous"),
        ("DrawFrame", "GoneFrame", "No first-display"),
    ] {
        let mut bytes = std::fs::read(fixture()).unwrap();
        replace_marker(&mut bytes, from.as_bytes(), to.as_bytes());
        let trace = workspace.path().join("variant.pftrace");
        std::fs::write(&trace, &bytes).unwrap();
        let session = TraceSession::open(tool.clone(), &trace, &cancel).unwrap();
        assert!(
            matches!(session.startup_cpu_hotspots("com.example.startup",&cancel).unwrap(),StartupCpuEvidence::Unavailable(reason) if reason.contains(expected))
        );
    }
}

#[test]
fn missing_symbols_do_not_hide_samples_or_invent_methods() {
    let cancel = CancellationToken::new();
    let Some(tool) = pinned(&cancel) else { return };
    let workspace = TempWorkspace::create(&std::env::temp_dir()).unwrap();
    let mut bytes = std::fs::read(fixture()).unwrap();
    replace_marker(&mut bytes, b"Example.init", b"            ");
    let trace = workspace.path().join("unnamed.pftrace");
    std::fs::write(&trace, bytes).unwrap();
    let session = TraceSession::open(tool, &trace, &cancel).unwrap();
    let StartupCpuEvidence::Measured(w) = session
        .startup_cpu_hotspots("com.example.startup", &cancel)
        .unwrap()
    else {
        panic!("window was lost")
    };
    assert_eq!((w.total_samples, w.unresolved_samples), (3, 3));
    assert!(w.hotspots.is_empty());
    assert!(w.scheduling.is_some());
}

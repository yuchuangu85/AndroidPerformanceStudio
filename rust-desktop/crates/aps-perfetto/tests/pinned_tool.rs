//! Pinned Trace Processor integration evidence. Every test skips cleanly when
//! the pinned native tool or the synthetic trace fixture is absent, so packaged
//! source builds without native artifacts stay green.

use aps_perfetto::{
    MAX_DIAGNOSTIC_ROWS, PINNED_VERSION, TraceProcessor, TraceSession, TraceSessionMode,
    diagnostic_preset, diagnostic_queries, evaluate_finding,
};
use aps_platform::CancellationToken;
use std::fs;
use std::path::{Path, PathBuf};
use std::process::Command;
use std::time::{SystemTime, UNIX_EPOCH};

fn repository_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..")
}

fn tool_path() -> Option<PathBuf> {
    let name = if cfg!(windows) {
        "trace_processor_shell.exe"
    } else {
        "trace_processor_shell"
    };
    let path = repository_root().join("build/perfetto-tools").join(name);
    path.is_file().then_some(path)
}

fn fixture_path() -> Option<PathBuf> {
    let path = repository_root()
        .join("desktop-viewer/desktop-app/src/test/resources/fixtures/synthetic-empty.pftrace");
    path.is_file().then_some(path)
}

fn open_session() -> Option<(TraceProcessor, TraceSession, PathBuf)> {
    let tool_path = tool_path()?;
    let fixture = fixture_path()?;
    let cancel = CancellationToken::new();
    let tool = match TraceProcessor::from_repository(&repository_root(), &cancel) {
        Ok(tool) => tool,
        Err(error) => {
            eprintln!("skipping: pinned tool did not verify here: {error}");
            return None;
        }
    };
    match TraceSession::open(tool.clone(), &fixture, &cancel) {
        Ok(session) => Some((tool, session, tool_path)),
        Err(error) => panic!("pinned tool present but open failed: {error}"),
    }
}

#[test]
fn pinned_tool_runs_the_whole_catalog_against_the_synthetic_empty_trace() {
    let Some((_tool, session, _tool_path)) = open_session() else {
        eprintln!("skipping: pinned tool or fixture absent");
        return;
    };
    let cancel = CancellationToken::new();
    let mode = session.mode();
    assert!(
        matches!(
            mode,
            TraceSessionMode::Warm | TraceSessionMode::OneShotFallback
        ),
        "unexpected session mode {mode:?}"
    );
    let warm_name = session.warm_session_name();
    eprintln!(
        "pinned tool {} session mode {:?} session name {:?}",
        PINNED_VERSION, mode, warm_name
    );
    if mode == TraceSessionMode::Warm {
        assert!(session.is_warm());
        assert!(warm_name.is_some());
    }

    for query in diagnostic_queries() {
        let result = session
            .diagnostic(query, &cancel)
            .unwrap_or_else(|error| panic!("{} failed: {error}", query.id));
        assert_eq!(result.query_id, query.id);
        assert_eq!(result.schema_version, PINNED_VERSION);
        let declared: Vec<&str> = query.columns.to_vec();
        let actual: Vec<&str> = result.columns.iter().map(String::as_str).collect();
        assert_eq!(actual, declared, "{} columns", query.id);
        assert!(result.rows.len() <= MAX_DIAGNOSTIC_ROWS, "{}", query.id);
        for row in &result.rows {
            assert_eq!(row.len(), result.columns.len(), "{} row width", query.id);
        }
        // The Compose schema test expects every diagnostic to execute.
        let finding = evaluate_finding(query, &result);
        assert!(finding.is_some(), "{} finding", query.id);
    }

    // The core preset resolves and runs end to end.
    let core = diagnostic_preset(Some("core"));
    assert_eq!(core.queries().count(), 8);
    for query in core.queries() {
        session
            .diagnostic(query, &cancel)
            .unwrap_or_else(|error| panic!("core {} failed: {error}", query.id));
    }

    // Slice search is safely escaped and bounded on a real trace.
    let hits = session
        .search_slices("it's main", 200, &cancel)
        .expect("search");
    assert!(hits.len() <= 200);
    let bounded = session
        .search_slices_bounded("main", 1, &cancel)
        .expect("bounded search");
    assert!(bounded.hits.len() <= 1);
    assert!(!bounded.term.is_empty());

    drop(session);
    // Drop must have stopped the owned daemon: a second kill finds no session.
    if let Some(name) = warm_name {
        let status = Command::new(_tool_path)
            .env_clear()
            .args(["server", "kill", name.as_str()])
            .stdout(std::process::Stdio::null())
            .stderr(std::process::Stdio::null())
            .status()
            .expect("kill invocation");
        assert!(!status.success(), "warm server survived TraceSession Drop");
    }
}

#[test]
fn pinned_tool_fails_closed_on_an_invalid_trace() {
    if tool_path().is_none() {
        eprintln!("skipping: pinned tool absent");
        return;
    }
    let cancel = CancellationToken::new();
    let tool = match TraceProcessor::from_repository(&repository_root(), &cancel) {
        Ok(tool) => tool,
        Err(error) => {
            eprintln!("skipping: pinned tool did not verify here: {error}");
            return;
        }
    };
    let unique = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_nanos())
        .unwrap_or(0);
    let bad = std::env::temp_dir().join(format!(
        "aps-perfetto-invalid-{}-{unique}.pftrace",
        std::process::id()
    ));
    fs::write(&bad, b"this is not a perfetto trace").expect("write invalid fixture");
    let opened = TraceSession::open(tool, &bad, &cancel);
    let _ = fs::remove_file(&bad);
    match opened {
        Ok(session) => {
            // Hosts without warm support verify through one-shot, which also
            // rejects the bytes; a successful open would be a real regression.
            drop(session);
            panic!("invalid trace was accepted");
        }
        Err(error) => {
            assert_eq!(error, aps_perfetto::PerfettoError::InvalidTrace);
        }
    }
}

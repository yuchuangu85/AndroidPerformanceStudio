//! Additional offline adapters. Each returns derived data, never raw collection payloads.
use serde_json::{Value, json};
use std::path::Path;

pub const KINDS: &[&str] = &[
    "memory",
    "benchmark",
    "startup",
    "method-recording",
    "layout-inspector",
    "battery",
];

pub fn analyze(
    path: &Path,
    bytes: &[u8],
    digest: &str,
    doc: Option<&Value>,
) -> Option<Result<Value, String>> {
    if bytes.starts_with(b"PK\x03\x04") {
        Some(super::offline_inspection::layout(path, digest))
    } else if doc.is_some_and(super::offline_inspection::is_battery) {
        Some(super::offline_inspection::battery(bytes))
    } else if bytes.starts_with(b"JAVA PROFILE ") {
        Some(memory(path, digest))
    } else if doc.is_some_and(|v| v.get("benchmarks").is_some()) {
        Some(benchmark(bytes))
    } else if bytes.starts_with(b"SLOW")
        || bytes.starts_with(b"*version\n")
        || bytes.starts_with(b"*version\r\n")
    {
        Some(method(bytes))
    } else {
        let header = bytes.split(|&b| b == b'\n').next()?;
        if header.len() > 8192 || !header.windows(13).any(|w| w == b"requestedType") {
            return None;
        }
        Some(startup(bytes))
    }
}

fn memory(path: &Path, digest: &str) -> Result<Value, String> {
    let executable = std::env::current_exe()
        .map_err(|_| "E_MEMORY_WORKER")?
        .with_file_name(format!("aps-memory-worker{}", std::env::consts::EXE_SUFFIX));
    let cancel = aps_platform::CancellationToken::default();
    let mut session = aps_memory::MemorySession::open(&executable, path, digest, &cancel)
        .map_err(|e| format!("E_MEMORY_OPEN: {e}"))?;
    let snap = session
        .snapshot(&cancel)
        .map_err(|e| format!("E_MEMORY_QUERY: {e}"))?;
    session
        .check_source(&cancel)
        .map_err(|_| "E_SOURCE_CHANGED")?;
    Ok(json!({"kind":"memory","inputFormat":"hprof","summary":{
        "objectCount":snap.summary.object_count,"classCount":snap.summary.class_count,
        "shallowBytes":snap.summary.shallow_size,"retainedBytes":snap.summary.retained_size,
        "gcRootCount":snap.metadata.root_count,"parseWarningCount":snap.metadata.warnings.len(),
        "heuristicLeakCandidateCount":snap.findings.leak_suspects.total
    }}))
}

fn startup(bytes: &[u8]) -> Result<Value, String> {
    let text = std::str::from_utf8(bytes).map_err(|_| "E_STARTUP_CSV")?;
    let session = aps_startup::StartupSession::parse_csv(text).map_err(|_| "E_STARTUP_CSV")?;
    let s = session.summary();
    fn metric(m: &aps_startup::Statistics) -> Value {
        json!({"observed":m.count,"missing":m.missing,"p50Ms":m.p50_ms,"p95Ms":m.p95_ms,"meanMs":m.mean_ms})
    }
    Ok(
        json!({"kind":"startup","inputFormat":"startup-csv","summary":{
            "runCount":s.runs,"unknownObservedModes":s.unknown_observed_modes,
            "requestedObservedMismatches":s.requested_observed_mismatches,
            "ttid":metric(&s.ttid),"ttfd":metric(&s.ttfd),"amTotalTime":metric(&s.total_time)
        }}),
    )
}

// Only short, printable metric identifiers are forwarded. Never expose linked artifacts.
fn label(s: &str) -> Option<&str> {
    (!s.is_empty()
        && s.len() <= 128
        && s.chars()
            .all(|c| c.is_alphanumeric() || ".#_ %():-".contains(c)))
    .then_some(s)
}

fn benchmark(bytes: &[u8]) -> Result<Value, String> {
    let run = aps_benchmark::BenchmarkRun::parse_json(bytes).map_err(|_| "E_BENCHMARK_JSON")?;
    let count: usize = run.cases.iter().map(|c| c.metrics.len()).sum();
    let rows: Vec<_> = run
        .cases
        .iter()
        .enumerate()
        .flat_map(|(i, c)| {
            c.metrics.iter().map(move |m|json!({
        "caseIndex":i,"case":label(&c.test_name),"metric":label(&m.name),"unit":label(&m.unit),
        "samples":m.samples.len(),"representativeValue":m.representative_value()
    }))
        })
        .take(24)
        .collect();
    Ok(
        json!({"kind":"benchmark","inputFormat":"androidx-benchmark-json","summary":{
            "caseCount":run.cases.len(),"metricCount":count,"omittedMetrics":count-rows.len(),
            "metrics":rows,"warningCount":run.warnings.len(),"comparisonPerformed":false
        }}),
    )
}

fn method(bytes: &[u8]) -> Result<Value, String> {
    let session =
        aps_method_recording::MethodRecordingSession::parse(bytes).map_err(|_| "E_METHOD_TRACE")?;
    let s = session.summary();
    let page = session
        .top_methods_page(None, 0, 12)
        .map_err(|_| "E_METHOD_QUERY")?;
    let rows:Vec<_>=page.rows.iter().map(|m|json!({"methodId":m.method_id,
        "method":session.method(m.method_id).and_then(|m|label(&m.method_name)),
        "callCount":m.call_count,"incompleteCalls":m.incomplete_calls,
        "selfPrimaryNs":m.self_time.primary_nanos,"inclusivePrimaryNs":m.inclusive_time.primary_nanos
    })).collect();
    Ok(
        json!({"kind":"method-recording","inputFormat":"art-method-trace","summary":{
            "clock":format!("{:?}",session.header().clock_source),"eventCount":s.event_count,"methodCount":s.method_count,
            "threadCount":s.thread_count,"hasIntegrityDiagnostics":s.has_integrity_diagnostics,
            "omittedTopMethods":page.total-rows.len(),"topMethods":rows
        }}),
    )
}

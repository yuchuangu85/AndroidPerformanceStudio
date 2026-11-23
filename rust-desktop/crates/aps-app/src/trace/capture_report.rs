//! Read-only, bounded capture observations, not a certificate of complete data.
//!
//! Uses the checksum-pinned adapter's static diagnostic API (and its bounded
//! ProcessRunner), never PATH, downloads, shell commands, or device operations.
//! Run off the UI thread. `inspect` does not write; the caller explicitly calls
//! `write_report` and can assign its returned path to `report.report_path`.

use aps_contracts::SourceDigest;
use aps_perfetto::{
    DiagnosticCategory, DiagnosticQuery, DiagnosticResult, MAX_TRACE_BYTES, PINNED_VERSION,
    PerfettoError, TraceSession,
};
use aps_platform::CancellationToken;
use std::{
    fs::{self, File, Metadata, OpenOptions},
    io::{self, Read, Write},
    path::{Path, PathBuf},
    sync::atomic::{AtomicU64, Ordering},
    time::{Duration, Instant},
};

const MAX_SOURCES: usize = 64;
const MAX_SOURCE_BYTES: usize = 128;
const MAX_COUNTERS: usize = 64;
const MAX_MESSAGES: usize = 160;
const MAX_MESSAGE_BYTES: usize = 512;
const MAX_REPORT_BYTES: usize = 128 * 1024;
const HASH_TIMEOUT: Duration = Duration::from_secs(60);
const COVERAGE: &str = "Partial coverage: available checks only; not proof that all requested data was captured. Event absence does not prove a source is unsupported.";
const LIMITATIONS: &[&str] = &[
    COVERAGE,
    "loss_count is the sum of positive data_loss stat values, not unique lost events; counters can overlap and use different units. Zero means no reported loss, not no loss.",
    "trace_bounds describes the parsed timestamp interval, not device recording wall time or continuous per-source coverage. Duration comparison uses a 10% tolerance (at least 100 ms).",
    "Source checks look only for allowlisted parsed family evidence, not every configured event, process, sample, or time interval.",
    "The pinned adapter bounds each query to 120 seconds, stdout to 4 MiB, stderr to 64 KiB, and startup to 300 seconds. Its own source hashing is byte-bounded, with cancellation checked before/after hashing, not during it.",
];

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(in crate::trace) enum IntegrityStatus {
    /// Available checks passed, subject to the report's explicit limitations.
    Verified,
    Issues,
    Unverified,
}

#[derive(Clone, Debug)]
pub(in crate::trace) struct IntegrityReport {
    pub status: IntegrityStatus,
    pub messages: Vec<String>,
    pub requested_sources: Vec<String>,
    pub observed_duration_ns: Option<u64>,
    /// Sum of positive `data_loss` values, not a count of distinct lost events.
    pub loss_count: u64,
    pub report_path: Option<PathBuf>,
    // Keep observations bound to the bytes inspected, including at persistence.
    identity: Option<TraceIdentity>,
    expected_duration_ms: Option<u64>,
    tool_digest: Option<SourceDigest>,
}

#[derive(Clone, Debug)]
struct TraceIdentity {
    path: PathBuf,
    metadata: Metadata,
    digest: SourceDigest,
}

const fn query(
    id: &'static str,
    columns: &'static [&'static str],
    sql: &'static str,
) -> DiagnosticQuery {
    DiagnosticQuery {
        id,
        category: DiagnosticCategory::Runtime,
        title: id,
        description: "Bounded capture integrity observation",
        columns,
        sql,
    }
}

// Perfetto storage/stats.h defines severity, not name substrings. In particular,
// *_begin/end totals can predate this recording; don't sum them as new loss.
const STATS: DiagnosticQuery = query(
    "capture_integrity_stats",
    &["stats_rows", "loss_count", "error_count", "positive_rows"],
    "SELECT COUNT(*) AS stats_rows,
       COALESCE(SUM(CASE WHEN severity='data_loss' AND value>0 THEN value ELSE 0 END),0) AS loss_count,
       COALESCE(SUM(CASE WHEN severity='error' AND value>0 THEN value ELSE 0 END),0) AS error_count,
       COALESCE(SUM(CASE WHEN severity IN ('data_loss','error') AND value>0 THEN 1 ELSE 0 END),0) AS positive_rows
     FROM stats",
);
const COUNTERS: DiagnosticQuery = query(
    "capture_integrity_counters",
    &["name", "idx", "severity", "source", "value"],
    "SELECT name, idx, severity, source, value FROM stats
     WHERE severity IN ('data_loss','error') AND value>0
     ORDER BY severity, name, idx LIMIT 65",
);
const BOUNDS: DiagnosticQuery = query(
    "capture_integrity_bounds",
    &["start_ts", "end_ts"],
    "SELECT start_ts, end_ts FROM trace_bounds LIMIT 2",
);

// All SQL is static: requested source strings select this allowlist and never
// become SQL literals. Track existence alone is not event evidence.
fn source_query(source: &str) -> Option<DiagnosticQuery> {
    let sql = match source {
        "linux.ftrace" => "SELECT EXISTS(SELECT 1 FROM ftrace_event) AS observed",
        "android.log" => "SELECT EXISTS(SELECT 1 FROM android_logs) AS observed",
        "android.surfaceflinger.frametimeline" => {
            "SELECT (EXISTS(SELECT 1 FROM actual_frame_timeline_slice) OR
               EXISTS(SELECT 1 FROM expected_frame_timeline_slice)) AS observed"
        }
        "android.heapprofd" => "SELECT EXISTS(SELECT 1 FROM heap_profile_allocation) AS observed",
        "android.java_hprof" => "SELECT EXISTS(SELECT 1 FROM heap_graph_object) AS observed",
        "linux.perf" => "SELECT EXISTS(SELECT 1 FROM perf_sample) AS observed",
        "track_event" => {
            "SELECT (EXISTS(SELECT 1 FROM slice s JOIN track t ON t.id=s.track_id
                 WHERE EXTRACT_ARG(t.source_arg_set_id,'source')='descriptor') OR
               EXISTS(SELECT 1 FROM counter c JOIN track t ON t.id=c.track_id
                 WHERE EXTRACT_ARG(t.source_arg_set_id,'source')='descriptor')) AS observed"
        }
        _ => return None,
    };
    Some(query("capture_integrity_source", &["observed"], sql))
}

/// Read-only inspection of one raw, regular local trace. No capture or GUI work.
/// Any failed, truncated, or unavailable check takes precedence over Issues and
/// leaves Unverified, while retaining independently observed issues in messages.
pub(in crate::trace) fn inspect(
    trace_path: &Path,
    requested_sources: &[String],
    expected_duration_ms: Option<u64>,
    cancel: &CancellationToken,
) -> IntegrityReport {
    let mut report = initial_report(requested_sources, expected_duration_ms);
    let identity = match read_identity(trace_path, cancel) {
        Ok(identity) => identity,
        Err(message) => {
            report.messages.push(message);
            return report;
        }
    };
    report.identity = Some(identity.clone());
    if cancel.is_cancelled() {
        report
            .messages
            .push("Integrity inspection cancelled.".into());
        return report;
    }
    let tool = match crate::perfetto_tool::locate(cancel) {
        Ok(tool) => tool,
        Err(error) => {
            report.messages.push(format!(
                "Pinned Trace Processor unavailable: {error}. Checks were not run; no PATH/download fallback is used."
            ));
            return report;
        }
    };
    report.tool_digest = Some(tool.sha256());
    let session = match TraceSession::open(tool, trace_path, cancel) {
        Ok(session) => session,
        Err(error) => {
            report.messages.push(check_failure("Trace load", error));
            return report;
        }
    };
    if session.source_digest() != identity.digest {
        report
            .messages
            .push("Trace identity changed before inspection.".into());
        return report;
    }
    report = analyze(report, requested_sources, cancel, |query| {
        session.diagnostic(query, cancel)
    });
    if cancel.is_cancelled() || !identity_matches(&identity, trace_path) {
        report.status = IntegrityStatus::Unverified;
        report.messages.push("Inspection cancelled or trace identity changed; observations are not verified for the current file.".into());
    }
    // TraceSession owns and reaps its warm server on Drop, including early exits.
    report
}

fn valid_identifier(value: &str) -> bool {
    !value.is_empty()
        && value.len() <= MAX_SOURCE_BYTES
        && value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b'-'))
}

fn initial_report(sources: &[String], expected_duration_ms: Option<u64>) -> IntegrityReport {
    let mut messages = vec![COVERAGE.to_owned()];
    if sources.len() > MAX_SOURCES || sources.iter().any(|source| !valid_identifier(source)) {
        messages.push("Requested source list exceeds the budget or contains invalid identifiers; omitted identifiers remain unverified.".into());
    }
    IntegrityReport {
        status: IntegrityStatus::Unverified,
        messages,
        requested_sources: sources
            .iter()
            .take(MAX_SOURCES)
            .filter(|source| valid_identifier(source))
            .cloned()
            .collect(),
        observed_duration_ns: None,
        loss_count: 0,
        report_path: None,
        identity: None,
        expected_duration_ms,
        tool_digest: None,
    }
}

fn checked_result(
    query: &DiagnosticQuery,
    result: DiagnosticResult,
) -> Result<Vec<Vec<Option<String>>>, PerfettoError> {
    if result.truncated {
        return Err(PerfettoError::OutputLimit);
    }
    if result.query_id != query.id
        || result.schema_version != PINNED_VERSION
        || result.columns != query.columns
        || result
            .rows
            .iter()
            .any(|row| row.len() != query.columns.len())
        || result.rows.len() > MAX_COUNTERS + 1
        || result
            .rows
            .iter()
            .flatten()
            .flatten()
            .any(|cell| cell.len() > MAX_MESSAGE_BYTES)
    {
        return Err(PerfettoError::InvalidResult);
    }
    Ok(result.rows)
}

fn number<T: std::str::FromStr>(cell: &Option<String>) -> Result<T, PerfettoError> {
    cell.as_deref()
        .ok_or(PerfettoError::InvalidResult)?
        .parse()
        .map_err(|_| PerfettoError::InvalidResult)
}

fn check_failure(label: &str, error: PerfettoError) -> String {
    // These errors are typed, sanitized adapter messages, never raw stderr/SQL.
    format!("{label} unverified: {error}.")
}

fn analyze(
    mut report: IntegrityReport,
    sources: &[String],
    cancel: &CancellationToken,
    mut run: impl FnMut(&DiagnosticQuery) -> Result<DiagnosticResult, PerfettoError>,
) -> IntegrityReport {
    let mut incomplete =
        sources.len() > MAX_SOURCES || sources.iter().any(|source| !valid_identifier(source));
    let mut issues = false;
    let mut execute = |query: &DiagnosticQuery| {
        if cancel.is_cancelled() {
            return Err(PerfettoError::Cancelled);
        }
        checked_result(query, run(query)?)
    };
    let stats = execute(&STATS).and_then(|rows| {
        let [row] = rows.as_slice() else {
            return Err(PerfettoError::InvalidResult);
        };
        let count: u64 = number(&row[0])?;
        let loss: u64 = number(&row[1])?;
        let errors: u64 = number(&row[2])?;
        let positives: u64 = number(&row[3])?;
        if count == 0 || positives > count || (positives == 0) != (loss == 0 && errors == 0) {
            return Err(PerfettoError::InvalidResult);
        }
        Ok((loss, errors, positives))
    });
    match stats {
        Ok((loss, errors, positives)) => {
            report.loss_count = loss;
            issues |= positives > 0;
            report.messages.push(format!(
                "Trace Processor stats: {positives} positive data_loss/error counters; data_loss value sum={loss}, error value sum={errors}. These are not unique lost-event counts."
            ));
            if positives > 0 {
                match execute(&COUNTERS).and_then(|rows| counter_messages(rows, positives)) {
                    Ok(messages) => report.messages.extend(messages),
                    Err(error) => {
                        incomplete = true;
                        report
                            .messages
                            .push(check_failure("Counter details", error));
                    }
                }
            }
        }
        Err(error) => {
            incomplete = true;
            report
                .messages
                .push(check_failure("Loss/error stats", error));
        }
    }
    match execute(&BOUNDS).and_then(observed_duration) {
        Ok(duration) => {
            report.observed_duration_ns = Some(duration);
            report
                .messages
                .push(format!("Parsed trace_bounds interval: {duration} ns."));
            if duration == 0 {
                issues = true;
                report.messages.push(
                    "Parsed trace interval is empty; usable time coverage was not observed.".into(),
                );
            }
            if let Some(expected_ms) = report.expected_duration_ms {
                match expected_ms.checked_mul(1_000_000).filter(|ns| *ns > 0) {
                    Some(expected_ns) => {
                        let tolerance = (expected_ns / 10).max(100_000_000);
                        if duration.abs_diff(expected_ns) > tolerance {
                            issues = true;
                            report.messages.push(format!("Observed interval differs from requested {expected_ms} ms by more than {tolerance} ns. Sparse events or early stop can explain this; this alone is not proof of loss."));
                        }
                    }
                    None => {
                        incomplete = true;
                        report.messages.push("Expected duration is zero or overflows nanoseconds; duration comparison unverified.".into());
                    }
                }
            }
        }
        Err(error) => {
            incomplete = true;
            report.messages.push(check_failure("Trace bounds", error));
        }
    }
    let mut seen = std::collections::BTreeSet::new();
    for source in report.requested_sources.iter() {
        if !seen.insert(source) {
            continue;
        }
        let Some(query) = source_query(source) else {
            incomplete = true;
            report.messages.push(format!("{source}: no defensible source-family check is implemented; unverified (not evidence of unsupported capture)."));
            continue;
        };
        match execute(&query).and_then(observed_source) {
            Ok(true) => report.messages.push(format!("{source}: parsed source-family event evidence observed; this does not establish full source coverage.")),
            Ok(false) => {
                incomplete = true;
                report.messages.push(format!("{source}: checked family evidence absent; unverified. Absence can reflect inactivity, parsing, or capture configuration, not necessarily unsupported capture."));
            }
            Err(error) => {
                incomplete = true;
                report.messages.push(check_failure(source, error));
            }
        }
    }
    report.status = if incomplete || cancel.is_cancelled() {
        IntegrityStatus::Unverified
    } else if issues {
        IntegrityStatus::Issues
    } else {
        report.messages.push("Available checks passed.".into());
        IntegrityStatus::Verified
    };
    report
}

fn counter_messages(
    rows: Vec<Vec<Option<String>>>,
    expected_rows: u64,
) -> Result<Vec<String>, PerfettoError> {
    if rows.len() > MAX_COUNTERS {
        return Err(PerfettoError::OutputLimit);
    }
    if rows.len() as u64 != expected_rows {
        return Err(PerfettoError::InvalidResult);
    }
    rows.into_iter()
        .map(|row| {
            let name = row[0].as_deref().ok_or(PerfettoError::InvalidResult)?;
            let severity = row[2].as_deref().ok_or(PerfettoError::InvalidResult)?;
            let source = row[3].as_deref().ok_or(PerfettoError::InvalidResult)?;
            let value: u64 = number(&row[4])?;
            if !valid_identifier(name)
                || !matches!(severity, "data_loss" | "error")
                || !matches!(source, "trace" | "analysis")
                || value == 0
            {
                return Err(PerfettoError::InvalidResult);
            }
            let index = match &row[1] {
                None => String::new(),
                Some(_) => format!("[{}]", number::<u64>(&row[1])?),
            };
            Ok(format!("{severity}: {name}{index}={value} ({source})."))
        })
        .collect()
}

fn observed_duration(rows: Vec<Vec<Option<String>>>) -> Result<u64, PerfettoError> {
    let [row] = rows.as_slice() else {
        return Err(PerfettoError::InvalidResult);
    };
    let start: i64 = number(&row[0])?;
    let end: i64 = number(&row[1])?;
    // Signed clocks are valid; compute the interval without signed overflow.
    u64::try_from(i128::from(end) - i128::from(start)).map_err(|_| PerfettoError::InvalidResult)
}

fn observed_source(rows: Vec<Vec<Option<String>>>) -> Result<bool, PerfettoError> {
    let [row] = rows.as_slice() else {
        return Err(PerfettoError::InvalidResult);
    };
    match number::<u8>(&row[0])? {
        0 => Ok(false),
        1 => Ok(true),
        _ => Err(PerfettoError::InvalidResult),
    }
}

struct DigestReader<'a> {
    file: &'a mut File,
    cancel: &'a CancellationToken,
    deadline: Instant,
}

impl Read for DigestReader<'_> {
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        if self.cancel.is_cancelled() || Instant::now() >= self.deadline {
            return Err(io::Error::other("source digest interrupted"));
        }
        let count = buffer.len().min(64 * 1024);
        self.file.read(&mut buffer[..count])
    }
}

fn same_metadata(before: &Metadata, after: &Metadata) -> bool {
    if !after.is_file()
        || before.len() != after.len()
        || before.modified().ok() != after.modified().ok()
        || before.created().ok() != after.created().ok()
    {
        return false;
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        if before.dev() != after.dev()
            || before.ino() != after.ino()
            || before.ctime() != after.ctime()
            || before.ctime_nsec() != after.ctime_nsec()
        {
            return false;
        }
    }
    true
}

fn identity_matches(identity: &TraceIdentity, path: &Path) -> bool {
    path.canonicalize().ok().as_ref() == Some(&identity.path)
        && fs::symlink_metadata(path).is_ok_and(|metadata| {
            !metadata.is_symlink() && same_metadata(&identity.metadata, &metadata)
        })
}

fn read_identity(path: &Path, cancel: &CancellationToken) -> Result<TraceIdentity, String> {
    if cancel.is_cancelled() {
        return Err("Integrity inspection cancelled.".into());
    }
    let failure = || {
        "Trace identity unavailable: require a stable regular local file within the 8 GiB/60 second hashing budget.".to_owned()
    };
    let before = fs::symlink_metadata(path).map_err(|_| failure())?;
    if !before.is_file() || before.is_symlink() || before.len() > MAX_TRACE_BYTES {
        return Err(failure());
    }
    let canonical = path.canonicalize().map_err(|_| failure())?;
    let mut file = File::open(&canonical).map_err(|_| failure())?;
    if !same_metadata(&before, &file.metadata().map_err(|_| failure())?) {
        return Err(failure());
    }
    let digest = SourceDigest::hash_reader(
        DigestReader {
            file: &mut file,
            cancel,
            deadline: Instant::now() + HASH_TIMEOUT,
        },
        MAX_TRACE_BYTES,
    )
    .map_err(|_| failure())?;
    let identity = TraceIdentity {
        path: canonical,
        metadata: before,
        digest,
    };
    if cancel.is_cancelled()
        || !same_metadata(&identity.metadata, &file.metadata().map_err(|_| failure())?)
        || !identity_matches(&identity, path)
    {
        return Err("Trace changed or inspection was cancelled while hashing.".into());
    }
    Ok(identity)
}

fn report_bytes(report: &IntegrityReport) -> Result<Vec<u8>, String> {
    let identity = report
        .identity
        .as_ref()
        .ok_or("Report has no inspected trace identity")?;
    if report.messages.len() > MAX_MESSAGES
        || report
            .messages
            .iter()
            .any(|message| message.len() > MAX_MESSAGE_BYTES)
        || report.requested_sources.len() > MAX_SOURCES
        || report
            .requested_sources
            .iter()
            .any(|source| !valid_identifier(source))
    {
        return Err("Integrity report exceeds its metadata budget".into());
    }
    let status = match report.status {
        IntegrityStatus::Verified => "Verified",
        IntegrityStatus::Issues => "Issues",
        IntegrityStatus::Unverified => "Unverified",
    };
    let bytes = serde_json::to_vec_pretty(&serde_json::json!({
        "schemaVersion": 1,
        "reporterVersion": 1,
        "status": status,
        "verifiedMeaning": "available checks passed",
        "sourceDigest": identity.digest.to_hex(),
        "sourceBytes": identity.metadata.len(),
        "traceProcessor": {
            "pinnedVersion": PINNED_VERSION,
            "verifiedToolDigest": report.tool_digest.map(SourceDigest::to_hex),
        },
        "requestedSources": report.requested_sources,
        "expectedDurationMs": report.expected_duration_ms,
        "observedDurationNs": report.observed_duration_ns,
        "lossCount": report.loss_count,
        "observations": report.messages,
        "limitations": LIMITATIONS,
    }))
    .map_err(|_| "Cannot encode integrity report".to_owned())?;
    if bytes.len() > MAX_REPORT_BYTES {
        return Err("Integrity report exceeds its JSON byte budget".into());
    }
    Ok(bytes)
}

/// Persist `<trace file name>.integrity.json` next to the trace. Refuses to bind
/// old observations to changed bytes. Contains no trace paths or raw payloads.
/// Does not mutate the supplied report; caller owns `report_path` and write errors.
pub(in crate::trace) fn write_report(
    trace_path: &Path,
    report: &IntegrityReport,
) -> Result<PathBuf, String> {
    let inspected = report
        .identity
        .as_ref()
        .ok_or("Report has no inspected trace identity")?;
    if !identity_matches(inspected, trace_path) {
        return Err("Trace identity changed since inspection; report was not written".into());
    }
    let current = read_identity(trace_path, &CancellationToken::new())?;
    if current.digest != inspected.digest || !same_metadata(&inspected.metadata, &current.metadata)
    {
        return Err("Trace bytes changed since inspection; report was not written".into());
    }
    let bytes = report_bytes(report)?;
    let mut name = trace_path
        .file_name()
        .ok_or("Trace has no file name")?
        .to_os_string();
    name.push(".integrity.json");
    let destination = trace_path.with_file_name(name);
    if fs::symlink_metadata(&destination)
        .is_ok_and(|metadata| !metadata.is_file() || metadata.is_symlink())
    {
        return Err("Integrity sidecar destination is not a regular file".into());
    }
    static NEXT_TEMP: AtomicU64 = AtomicU64::new(0);
    let mut temporary_name = destination.file_name().unwrap().to_os_string();
    temporary_name.push(format!(
        ".tmp-{}-{}",
        std::process::id(),
        NEXT_TEMP.fetch_add(1, Ordering::Relaxed)
    ));
    let temporary = destination.with_file_name(temporary_name);
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut file = options
        .open(&temporary)
        .map_err(|_| "Cannot create integrity sidecar")?;
    let result = (|| {
        file.write_all(&bytes)
            .map_err(|_| "Cannot write integrity sidecar")?;
        file.sync_all()
            .map_err(|_| "Cannot sync integrity sidecar")?;
        drop(file);
        if !identity_matches(inspected, trace_path) {
            return Err("Trace changed before sidecar publication".to_owned());
        }
        fs::rename(&temporary, &destination)
            .map_err(|_| "Cannot publish integrity sidecar".to_owned())?;
        Ok(destination)
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

#[cfg(test)]
mod tests {
    use super::*;

    fn result(query: &DiagnosticQuery, rows: &[&[Option<&str>]]) -> DiagnosticResult {
        DiagnosticResult {
            query_id: query.id,
            schema_version: PINNED_VERSION,
            columns: query
                .columns
                .iter()
                .map(|column| (*column).into())
                .collect(),
            rows: rows
                .iter()
                .map(|row| row.iter().map(|cell| cell.map(str::to_owned)).collect())
                .collect(),
            truncated: false,
        }
    }

    fn good(query: &DiagnosticQuery) -> DiagnosticResult {
        match query.id {
            "capture_integrity_stats" => {
                result(query, &[&[Some("100"), Some("0"), Some("0"), Some("0")]])
            }
            "capture_integrity_bounds" => result(query, &[&[Some("100"), Some("1000000100")]]),
            "capture_integrity_source" => result(query, &[&[Some("1")]]),
            _ => panic!("unexpected query"),
        }
    }

    fn check(
        sources: &[&str],
        run: impl FnMut(&DiagnosticQuery) -> Result<DiagnosticResult, PerfettoError>,
    ) -> IntegrityReport {
        let sources: Vec<_> = sources.iter().map(|source| (*source).into()).collect();
        analyze(
            initial_report(&sources, Some(1000)),
            &sources,
            &CancellationToken::new(),
            run,
        )
    }

    #[test]
    fn verified_means_only_available_checks_passed() {
        let report = check(&["android.log", "track_event"], |query| Ok(good(query)));
        assert_eq!(report.status, IntegrityStatus::Verified);
        assert_eq!(report.observed_duration_ns, Some(1_000_000_000));
        assert_eq!(report.loss_count, 0);
        assert!(report.messages.iter().any(|message| message == COVERAGE));
        assert!(
            report
                .messages
                .iter()
                .any(|message| message == "Available checks passed.")
        );
        assert!(report.report_path.is_none());
    }

    #[test]
    fn positive_loss_and_parser_errors_are_issues_not_unique_event_counts() {
        let report = check(&[], |query| {
            Ok(match query.id {
                "capture_integrity_stats" => {
                    result(query, &[&[Some("100"), Some("7"), Some("2"), Some("2")]])
                }
                "capture_integrity_counters" => result(
                    query,
                    &[
                        &[
                            Some("traced_buf_bytes_overwritten"),
                            Some("0"),
                            Some("data_loss"),
                            Some("trace"),
                            Some("7"),
                        ],
                        &[
                            Some("ftrace_cpu_dropped_events_delta"),
                            Some("3"),
                            Some("error"),
                            Some("trace"),
                            Some("2"),
                        ],
                    ],
                ),
                _ => good(query),
            })
        });
        assert_eq!(report.status, IntegrityStatus::Issues);
        assert_eq!(report.loss_count, 7);
        assert!(
            report
                .messages
                .iter()
                .any(|message| message.contains("ftrace_cpu_dropped_events_delta[3]=2"))
        );
    }

    #[test]
    fn absent_unknown_and_injected_sources_never_verify() {
        for source in [
            "android.log",
            "linux.process_stats",
            "unknown.source",
            "x'; DROP TABLE stats;--",
        ] {
            let report = check(&[source], |query| {
                Ok(if query.id == "capture_integrity_source" {
                    result(query, &[&[Some("0")]])
                } else {
                    good(query)
                })
            });
            assert_eq!(report.status, IntegrityStatus::Unverified);
            assert!(
                !report
                    .messages
                    .iter()
                    .any(|message| message == "Available checks passed.")
            );
        }
        assert!(source_query("android.log' OR 1=1--").is_none());
        assert!(source_query("linux.process_stats").is_none());
    }

    #[test]
    fn failed_missing_timeout_cancelled_and_truncated_results_never_verify() {
        for error in [
            PerfettoError::ToolMissing,
            PerfettoError::ProcessFailed,
            PerfettoError::InvalidResult,
            PerfettoError::Timeout,
            PerfettoError::Cancelled,
            PerfettoError::OutputLimit,
        ] {
            let report = check(&[], |query| {
                if query.id == STATS.id {
                    Err(error)
                } else {
                    Ok(good(query))
                }
            });
            assert_eq!(report.status, IntegrityStatus::Unverified);
            assert_eq!(report.observed_duration_ns, Some(1_000_000_000));
        }
        let report = check(&["android.log"], |query| {
            let mut output = good(query);
            if query.id == "capture_integrity_source" {
                output.truncated = true;
            }
            Ok(output)
        });
        assert_eq!(report.status, IntegrityStatus::Unverified);
    }

    #[test]
    fn failed_checks_take_precedence_but_keep_observed_loss() {
        let report = check(&[], |query| match query.id {
            "capture_integrity_stats" => Ok(result(
                query,
                &[&[Some("20"), Some("8"), Some("0"), Some("1")]],
            )),
            "capture_integrity_counters" => Err(PerfettoError::Timeout),
            _ => Ok(good(query)),
        });
        assert_eq!(report.status, IntegrityStatus::Unverified);
        assert_eq!(report.loss_count, 8);
        assert!(
            report
                .messages
                .iter()
                .any(|message| message.contains("data_loss value sum=8"))
        );
    }

    #[test]
    fn validates_schema_nulls_and_numeric_values() {
        for row in [
            vec![None, Some("1")],
            vec![Some("2"), Some("1")],
            vec![Some("0"), Some("NaN")],
            vec![Some("0"), Some("18446744073709551616")],
        ] {
            let report = check(&[], |query| {
                Ok(if query.id == BOUNDS.id {
                    result(query, &[&row])
                } else {
                    good(query)
                })
            });
            assert_eq!(report.status, IntegrityStatus::Unverified);
        }
        for mutation in 0..4 {
            let mut output = good(&STATS);
            match mutation {
                0 => output.columns.reverse(),
                1 => output.query_id = "other",
                2 => output.schema_version = "unknown",
                _ => output.rows[0].pop().map(|_| ()).unwrap(),
            }
            assert!(checked_result(&STATS, output).is_err());
        }
        assert_eq!(
            observed_duration(vec![vec![
                Some(i64::MIN.to_string()),
                Some(i64::MAX.to_string())
            ]])
            .unwrap(),
            u64::MAX
        );
    }

    #[test]
    fn bounds_zero_short_long_tolerance_and_duration_overflow() {
        for (end, expected) in [
            ("100", IntegrityStatus::Issues),
            ("100000100", IntegrityStatus::Issues),
            ("2000000100", IntegrityStatus::Issues),
            ("900000100", IntegrityStatus::Verified),
        ] {
            let report = check(&[], |query| {
                Ok(if query.id == BOUNDS.id {
                    result(query, &[&[Some("100"), Some(end)]])
                } else {
                    good(query)
                })
            });
            assert_eq!(report.status, expected);
        }
        for expected in [0, u64::MAX] {
            let report = analyze(
                initial_report(&[], Some(expected)),
                &[],
                &CancellationToken::new(),
                |query| Ok(good(query)),
            );
            assert_eq!(report.status, IntegrityStatus::Unverified);
        }
    }

    #[test]
    fn missing_or_inconsistent_stats_do_not_pass() {
        for row in [
            [Some("0"), Some("0"), Some("0"), Some("0")],
            [Some("100"), Some("1"), Some("0"), Some("0")],
            [Some("100"), Some("0"), Some("0"), Some("1")],
            [Some("100"), Some("-1"), Some("0"), Some("1")],
        ] {
            assert_eq!(
                check(&[], |query| Ok(if query.id == STATS.id {
                    result(query, &[&row])
                } else {
                    good(query)
                }))
                .status,
                IntegrityStatus::Unverified
            );
        }
    }

    #[test]
    fn row_limit_sentinel_and_bad_counter_text_fail_closed() {
        let row = vec![
            Some("loss".into()),
            None,
            Some("data_loss".into()),
            Some("trace".into()),
            Some("1".into()),
        ];
        assert_eq!(
            counter_messages(vec![row.clone(); 65], 65),
            Err(PerfettoError::OutputLimit)
        );
        assert!(counter_messages(vec![row.clone()], 2).is_err());
        let mut invalid = row;
        invalid[0] = Some("private payload\nnot a stat".into());
        assert!(counter_messages(vec![invalid], 1).is_err());
    }

    #[test]
    fn cancellation_skips_queries_and_source_lists_are_bounded() {
        let cancel = CancellationToken::new();
        cancel.cancel();
        let report = analyze(initial_report(&[], None), &[], &cancel, |_| {
            panic!("cancelled query ran")
        });
        assert_eq!(report.status, IntegrityStatus::Unverified);
        let sources = vec!["android.log".into(); MAX_SOURCES + 1];
        let report = analyze(
            initial_report(&sources, None),
            &sources,
            &CancellationToken::new(),
            |query| Ok(good(query)),
        );
        assert_eq!(report.status, IntegrityStatus::Unverified);
        assert_eq!(report.requested_sources.len(), MAX_SOURCES);
    }

    #[test]
    fn serializes_version_digest_limitations_and_no_trace_payload_or_path() {
        let path = Path::new(env!("CARGO_MANIFEST_DIR"))
            .join("../aps-perfetto/tests/fixtures/synthetic-empty.pftrace");
        let identity = read_identity(&path, &CancellationToken::new()).unwrap();
        let digest = identity.digest.to_hex();
        let mut report = check(&[], |query| Ok(good(query)));
        report.identity = Some(identity);
        let bytes = report_bytes(&report).unwrap();
        let value: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(value["schemaVersion"], 1);
        assert_eq!(value["sourceDigest"], digest);
        assert_eq!(value["verifiedMeaning"], "available checks passed");
        assert_eq!(value["limitations"][0], COVERAGE);
        assert!(bytes.len() < MAX_REPORT_BYTES);
        assert!(
            !String::from_utf8(bytes)
                .unwrap()
                .contains("synthetic-empty.pftrace")
        );
        report.messages = vec!["x".repeat(MAX_MESSAGE_BYTES + 1)];
        assert!(report_bytes(&report).is_err());
    }

    #[test]
    fn sidecar_is_bounded_digest_bound_and_refuses_changed_trace() {
        struct TemporaryDirectory(PathBuf);
        impl Drop for TemporaryDirectory {
            fn drop(&mut self) {
                let _ = fs::remove_dir_all(&self.0);
            }
        }
        static NEXT_TEST: AtomicU64 = AtomicU64::new(0);
        let directory = TemporaryDirectory(std::env::temp_dir().join(format!(
            "aps-integrity-test-{}-{}",
            std::process::id(),
            NEXT_TEST.fetch_add(1, Ordering::Relaxed),
        )));
        fs::create_dir(&directory.0).unwrap();
        let trace = directory.0.join("capture with spaces.pftrace");
        let private_payload = b"private test payload, never copied to sidecar";
        fs::write(&trace, private_payload).unwrap();
        let mut report = check(&[], |query| Ok(good(query)));
        let identity = read_identity(&trace, &CancellationToken::new()).unwrap();
        report.identity = Some(identity.clone());
        let sidecar = write_report(&trace, &report).unwrap();
        assert_eq!(
            sidecar,
            directory
                .0
                .join("capture with spaces.pftrace.integrity.json")
        );
        let bytes = fs::read(&sidecar).unwrap();
        let json: serde_json::Value = serde_json::from_slice(&bytes).unwrap();
        assert_eq!(json["sourceDigest"], identity.digest.to_hex());
        assert_eq!(json["sourceBytes"], private_payload.len());
        assert!(bytes.len() <= MAX_REPORT_BYTES);
        assert!(
            !String::from_utf8(bytes.clone())
                .unwrap()
                .contains("private test payload")
        );
        assert!(report.report_path.is_none());
        fs::write(&trace, b"changed test payload").unwrap();
        assert!(write_report(&trace, &report).is_err());
        assert_eq!(fs::read(&sidecar).unwrap(), bytes);
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(
                fs::metadata(&sidecar).unwrap().permissions().mode() & 0o777,
                0o600
            );
            let link = directory.0.join("linked.pftrace");
            std::os::unix::fs::symlink(&trace, &link).unwrap();
            assert!(read_identity(&link, &CancellationToken::new()).is_err());
        }
    }

    #[test]
    #[ignore = "requires the repository's checksum-pinned Trace Processor"]
    fn pinned_queries_and_inspect_accept_synthetic_local_fixtures() {
        let cancel = CancellationToken::new();
        let root = Path::new(env!("CARGO_MANIFEST_DIR"));
        let empty = root.join("../aps-perfetto/tests/fixtures/synthetic-empty.pftrace");
        let tool = crate::perfetto_tool::locate(&cancel).unwrap();
        let session = TraceSession::open(tool, &empty, &cancel).unwrap();
        for query in [STATS, COUNTERS, BOUNDS] {
            checked_result(&query, session.diagnostic(&query, &cancel).unwrap()).unwrap();
        }
        for source in [
            "linux.ftrace",
            "android.log",
            "android.surfaceflinger.frametimeline",
            "android.heapprofd",
            "android.java_hprof",
            "linux.perf",
            "track_event",
        ] {
            let query = source_query(source).unwrap();
            let rows =
                checked_result(&query, session.diagnostic(&query, &cancel).unwrap()).unwrap();
            assert!(!observed_source(rows).unwrap(), "{source}");
        }
        session.close();
        let frame = root.join("../aps-perfetto/tests/fixtures/frame-probes.pftrace");
        let report = inspect(
            &frame,
            &["android.surfaceflinger.frametimeline".into()],
            None,
            &cancel,
        );
        assert_eq!(
            report.status,
            IntegrityStatus::Verified,
            "{:?}",
            report.messages
        );
        assert!(report.observed_duration_ns.is_some_and(|ns| ns > 0));
        let report = inspect(&empty, &["android.log".into()], None, &cancel);
        assert_eq!(report.status, IntegrityStatus::Unverified);
        assert!(
            report
                .messages
                .iter()
                .any(|message| message.contains("evidence absent"))
        );
    }
}

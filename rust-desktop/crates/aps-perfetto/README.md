# Pinned Trace Processor adapter

This crate reuses the real Perfetto v59.1 executable. It never reimplements
trace parsing. Exact host SHA-256 values match the existing canonical
desktop-viewer/platform-perfetto/trace-processor-manifest.json; all five hosts
are declared, but host declarations are not cross-platform validation evidence.
The resolver accepts only explicit package/source-checkout paths, checks SHA-256
before executing --version, and rechecks the checksum before each operation.
There is no unverified PATH fallback or download. Rust packaging must
independently include the pinned executable and its notices.

## Warm per-session Trace Processor

The verified v59.1 CLI exposes a warm Unix-socket session:

    trace_processor_shell server unix --name SESSION --daemonize --quiet \
        --idle-timeout 30m TRACE_FILE
    trace_processor_shell query --remote SESSION --format csv --quiet "SELECT ..."
    trace_processor_shell server kill SESSION

TraceSession::open starts one daemonized server, so the trace is parsed once per
opened session. Diagnostic, search and QueryPreset page queries run against that
server through query --remote. Names are generated per process/trace
(aps_<pid>_<counter>_<first 8 digest hex characters>), match the pinned
[A-Za-z0-9][A-Za-z0-9_-]* grammar, and are bounded to 64 characters. Every
client process is started with env_clear = true, so the session directory is
deterministic (/tmp/perfetto on the verified macOS build); the trace path is
canonicalized because --daemonize changes the working directory to /.

Lifecycle guarantees:

- close() and Drop run server kill for exactly the owned session name. Server
  startup failures and cancellations also best-effort kill the name, so a
  partially started daemon is not left behind.
- The daemon is started with --idle-timeout 30m. If the host process crashes and
  Drop cannot run, the pinned tool reaps the orphaned session after 30 idle
  minutes instead of leaking it forever.
- A failed remote query other than cancellation/timeout/output-limit marks the
  session degraded and retries that query through the one-shot path once. The
  name is retained until close/Drop so the daemon is still stopped.
- If daemonization or remote sessions are unavailable on a host (for example a
  non-POSIX --daemonize build), open verifies the trace with one bounded
  one-shot SELECT before accepting fallback. An invalid trace still fails closed
  at open with PerfettoError::InvalidTrace.
- Degradation is explicit: TraceSessionMode::Warm,
  TraceSessionMode::OneShotFallback or TraceSessionMode::Closed, exposed through
  mode() / is_warm(). The one-shot path is a documented fallback, never silent
  per-query reparsing without the flag.
- Queries after close() fail with PerfettoError::SessionClosed.
- TraceSession is Send + Sync + 'static (the app holds Arc<TraceSession> and
  queries it from a background executor).

The one-shot path remains: query --format csv --no-agent --quiet TRACE SQL. It
is used for fallback sessions and for the startup verification query. Every
process still has bounded stdout (4 MiB), bounded stderr (64 KiB, never
displayed), a 120-second query timeout (300 seconds for warm startup), the
ProcessRunner cancellation/termination/reaping contract, and the pinned tool
checksum/version checks. ProcessRunner kills the direct child process group;
the daemonized server calls setsid, so it is stopped only by the explicit
server kill / idle reaper, never by an accidental group kill of a client.

## Diagnostic catalog, presets, findings

- diagnostics.rs is a direct port of Compose PerfettoDiagnostics.all: 35
  DiagnosticQuery entries with identical id, category, title, description,
  declared column names/order and SQL body. Presets match
  PerfettoDiagnosticPresets (core, jank, scheduling, binder, io, memory, power,
  all); diagnostic_preset() mirrors byId's fallback to core.
- DiagnosticCategory is the Compose analysis enum
  {Cpu, Binder, Graphics, Memory, Input, Io, Runtime, Power} with name() and
  display_name(). DiagnosticQuery::insight_category() reproduces the Compose
  TraceInsightCategory mapping (frame_jank -> Frame, main_thread_blocked ->
  LongTask, binder -> Binder, graphics -> Rendering, cpu/memory/input, else
  Other).
- DiagnosticQuery::timestamp_column() mirrors the Compose
  timelineTimestampColumn() derivation; duration_column() reports a declared
  duration_ns column when present. DiagnosticResult carries only declared
  columns, NULL-preserving cells, schema_version = PINNED_VERSION ("v59.1") and
  a truncated flag.
- findings.rs ports DiagnosticFindings.evaluate: the same thresholds (jank
  ratio 0.05, Binder 100/16 ms, blocked 500/100 ms, input 200/100 ms, wakeup
  5000 us), the same per-query evaluators, the same first-row vs max-with-
  subject rules, the same heuristic flag and NO_DATA for empty rows. No
  localized strings are produced here; the UI localizes metric kinds.
- search.rs ports TraceSliceSearch: control characters are dropped, the term is
  trimmed, bounded to 120 characters, lowercased and embedded as a single-quoted
  SQLite literal with every quote doubled. Blank terms produce no query. The
  result is LIMIT-bound (200 by default; search_slices takes an explicit
  1..=200 limit) and ordered by slice duration descending like Compose.
  search_slices_bounded() additionally returns the truncation flag.

## Result decoding and bounds

- The CSV decoder accepts quoted cells with embedded commas/CR/newlines and
  doubled quotes, LF/CRLF/CR row endings, empty cells, the literal "[NULL]"
  marker and UTF-8. Ambiguous output (a quote inside an unquoted cell, data
  after a closing quote, an unterminated quote, invalid UTF-8, more than 16
  columns, a cell over 32 KiB, more rows than the caller's bound) fails closed
  with InvalidResult. The upstream formatter does not escape quotes in trace
  text, so such a trace result is rejected instead of misparsed.
- Every result set is validated against the query's declared columns, in order,
  before any row is published; header drift or a short/long row fails closed.
- SQL NULL is None; an empty cell is Some(""). The "[NULL]" text marker is
  ambiguous by construction and maps to None (same as Compose).
- One diagnostic page is capped at MAX_DIAGNOSTIC_ROWS = 2000 rows and each text
  cell at MAX_DIAGNOSTIC_CELL_CHARS = 4096 characters (cut at a UTF-8 boundary).
  truncated is true when a page reaches the row cap or a cell was cut.
- QueryPreset pages keep the existing LIMIT+1 lookahead, 200-row page bound,
  hex-encoded bounded name previews and the eight-page/2 MiB session cache.

## Public API (consumed by aps-app/aps-gpu)

    pub const PINNED_VERSION: &str
    pub const CORE_PRESET_ID: &str
    pub enum DiagnosticCategory { Cpu, Binder, Graphics, Memory, Input, Io, Runtime, Power }
    pub struct DiagnosticQuery { id, category, title, description, columns, sql }
    pub struct DiagnosticPreset { id, title, query_ids }
    pub struct DiagnosticResult { query_id, schema_version, columns, rows, truncated }
    pub enum DiagnosticFindingLevel { Critical, Warning, Info, NoData }
    pub enum DiagnosticMetricKind { JankyFrames, MaxLatencyMs, MaxBlockedMs,
        MaxWakeupUs, PeakRssKb, TopCpuMs, TopFrequencyMhz, TopStateMs, Rows }
    pub struct DiagnosticMetric { kind, value, total, subject }
    pub struct DiagnosticFinding { level, metric, heuristic }
    pub struct SearchHit { timestamp_nanos, duration_nanos, name, thread_label }
    pub fn diagnostic_queries() -> &'static [DiagnosticQuery]
    pub fn diagnostic_presets() -> &'static [DiagnosticPreset]
    pub fn diagnostic_query(id: &str) -> Option<&'static DiagnosticQuery>
    pub fn diagnostic_preset(id: Option<&str>) -> &'static DiagnosticPreset
    pub fn evaluate_finding(query, result) -> Option<DiagnosticFinding>
    pub fn TraceSession::open(tool, path, cancel) -> Result<TraceSession, PerfettoError>
    pub fn TraceSession::query(preset, offset, limit, cancel) -> Result<QueryPage, _>
    pub fn TraceSession::diagnostic(query, cancel) -> Result<DiagnosticResult, _>
    pub fn TraceSession::search_slices(term, limit, cancel) -> Result<Vec<SearchHit>, _>
    pub fn TraceSession::search_slices_bounded(term, limit, cancel) -> Result<SliceSearchResult, _>
    pub fn TraceSession::mode() / is_warm() / warm_session_name() / close()

Existing QueryPreset, QueryPage, TraceProcessor and TraceSession::open/query/
source_digest behavior is preserved for aps-gpu and aps-app import.

## Validation evidence and gaps

Tests in this crate cover catalog/preset invariants, an oracle test that reads
PerfettoDiagnostics.kt and PerfettoDiagnosticPresets.kt and compares every id,
category, title, description, column list and SQL body, findings thresholds,
search escaping/bounds, CSV edge cases, bounded decoding and warm-session
lifecycle with a fake tool (warm, degraded startup, lost server, close). The
pinned-tool integration test runs every one of the 35 diagnostics plus the core
preset and slice search against
desktop-viewer/desktop-app/src/test/resources/fixtures/synthetic-empty.pftrace
and skips cleanly when the pinned tool or fixture is absent. On the verified
macOS arm64 host the pinned v59.1 binary starts a warm daemon (mode Warm), served
the full catalog, and Drop left no trace_processor_shell server process behind.

Known gaps: the warm session is per TraceSession instance and is not shared
across sessions; a lost daemon is not restarted (the session stays in one-shot
fallback until reopened); a remote query timeout cannot prove whether the daemon
finished the abandoned query; very large traces make synchronous SHA-256
scanning and warm startup long-running operations that rely on executor
scheduling and cancellation between blocks rather than hard I/O cancellation;
same-stamp malicious file replacement is not detected beyond length/mtime;
and large-trace RSS, packaged native interaction and Windows warm-session
behavior remain unverified. CrossDomainAttributionAnalyzer is intentionally not
ported: it is a presentation-level overlap correlator that no diagnostics,
preset, finding or search path feeds or consumes (verified by grep across
perfetto-viewer), and the fixed engine API does not include it.

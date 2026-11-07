# aps-benchmark

Offline AndroidX Benchmark import, Compose-parity regression report and
fail-closed release gate for the Benchmark Regression workspace.

## Import

`BenchmarkRun::open(path, max_bytes)` and `BenchmarkRun::parse_json(bytes)` port
the Compose `BenchmarkJsonParser`: root `benchmarks`/`results`/`tests`, case
names split on the last `.`, `context`/`device` metadata, root or context
`build`, `metrics`/`measurements` objects with raw `runs`/`values`/`samples`/
`measurements` (numbers or `value`/`measurement`/`ns` objects), Compose unit and
direction inference, trace/profile scenario evidence, skipped-case warnings and
unknown top-level warnings. The run id is the SHA-256 of the JSON bytes
(`aps_contracts::SourceDigest`); `imported_at` is a fixed-width RFC3339 UTC
string so history sorts stably.

Fail-closed bounds: nonregular or missing file, input over `MAX_IMPORT_BYTES`
(64 MiB), more than 10 000 cases, a duplicate `class#test` identity, or a
document with no valid case is rejected; an invalid individual case is skipped
with a warning like the Compose parser.

## Comparison and release gate

`compare` produces a Compose-shaped `RegressionReport`: compatibility issues
(device model/API/ABI, fingerprint soft, build variant, missing cohort identity,
per-case scenario/compilation/startup) and one `MetricComparison` per metric of
the current run, including the baseline/current representatives, deltas, reasons
and confidence. Classification evidence remains the Rust analyzer contract: a
deterministic two-sample BCa interval for the median difference plus an explicit
practical threshold; missing/unknown direction, low raw samples, absent metrics,
unit mismatch or incompatible cohorts are inconclusive or incompatible, never a
fabricated score.

`evaluate_gate` and `ReleaseGatePolicy` port the Compose `ReleaseGateEvaluator`
(decision order incompatible evidence -> blocking regressions -> inconclusive ->
pass, versioned policy, metric-pattern blocking, minimum confidence rank).
`compare_baseline_profile` ports the Baseline Profile experiment boundary with
aggregated profile status and trace pairs.

## Adapters

- `storage`: rusqlite store with the Compose schema, transactional save,
  content-addressed re-import replacement, recent runs, trend metric keys and
  chronological metric history.
- `export`: JSON (schema 1 report view / schema 2 release export), CSV, Markdown,
  JUnit, SARIF 2.1.0 and a dependency-free PDF 1.4 writer (Helvetica, A4, 60
  lines/page, WinAnsi with non-ASCII replaced by `?`).
- `report_index`: bounded JSON catalogue of exported artifacts; never deletes
  user files.
- `orchestrate`: exact Macrobenchmark Gradle command/environment builder,
  blocking runner with merged output, cancellation and timeout, and a bounded
  benchmark-result locator.

## Verification

`cargo test -p aps-benchmark` runs the ported parser, analyzer, gate, experiment,
storage, exporter/PDF/index and orchestration/locator suites plus small unit
tests for the analyzer helpers and formatting. The suites cover added behavior;
real-device Macrobenchmark execution and release CI integration remain separate
gates.

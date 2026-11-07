# aps-benchmark

Offline AndroidX Benchmark JSON import and regression comparison. The parser
supports existing root `benchmarks`, `results` or `tests` arrays; named metric
objects with raw `runs`/`values`/`samples`/`measurements` arrays or summary
median/min/max; scenario, compilation/startup mode, target package, device/build
cohort, baseline-profile and trace metadata. Unknown evidence is preserved only
within bounded `aps-json` values; trace paths are metadata and not opened.

`compare` requires a complete equal Comparable Benchmark Cohort (device model,
API, ABI, fingerprint, physical/emulator identity, variant, library/data versions)
and equal case target/scenario/compilation/startup/Profile evidence. It never
combines non-comparable samples. Both sides require raw samples and explicit or
configured metric direction. Regression decisions use a two-sample BCa bootstrap
confidence interval for the median difference plus an explicit practical
threshold; baseline-only MAD or p-values cannot pass the gate. Missing/unknown
direction, zero-baseline relative threshold, low samples, absent cases or
uncertainty failure are inconclusive; the gate can fail closed for CI.

Inputs are bounded and paginated. Tests are written but deliberately unexecuted
until the complete rewrite is implemented. Macrobenchmark execution, Perfetto
trace validation, real-device reproducibility and release CI integration remain
separate gates.

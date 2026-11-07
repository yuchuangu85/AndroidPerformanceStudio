//! Offline AndroidX Benchmark import, Compose-parity regression report and
//! fail-closed release gate.
//!
//! The parser, model shape, compatibility rules, release gate, storage,
//! exporters and Macrobenchmark orchestration are ports of the Compose
//! benchmark-regression modules. Classification evidence remains the Rust
//! analyzer contract: a two-sample BCa confidence interval for the median
//! difference plus an explicit practical threshold, with missing, low-sample or
//! incompatible evidence failing closed.
mod compare;
mod experiment;
mod export;
mod model;
mod orchestrate;
mod parser;
mod pdf;
mod report_index;
mod storage;
mod uncertainty;
mod util;

pub const MAX_IMPORT_BYTES: u64 = 64 * 1024 * 1024;

pub use compare::{
    Classification, CompatibilityIssue, GateDecision, GateReason, MetricComparison, POLICY_VERSION,
    RegressionPolicy, RegressionReport, ReleaseGatePolicy, ReleaseGateResult, compare,
    evaluate_gate,
};
pub use experiment::{BaselineProfileComparison, compare_baseline_profile};
pub use export::{
    render_pdf_lines, write_csv, write_json, write_junit, write_markdown, write_pdf, write_sarif,
};
pub use model::{
    BenchmarkBuild, BenchmarkCase, BenchmarkDevice, BenchmarkRun, Confidence, Metric,
    MetricDirection, ProfileEvidence, ProfileStatus, Scenario,
};
pub use orchestrate::{
    CompilationMode, DEFAULT_MACROBENCHMARK_TASK, MacrobenchmarkCommand, MacrobenchmarkPlan,
    MacrobenchmarkResult, Scenario as BenchmarkScenario, StartupMode, build_command,
    locate_benchmark_result, run_macrobenchmark,
};
pub use report_index::{BenchmarkReportEntry, BenchmarkReportIndex};
pub use storage::{BenchmarkStore, StoredBenchmarkRun, StoredMetric, StoredMetricPoint};

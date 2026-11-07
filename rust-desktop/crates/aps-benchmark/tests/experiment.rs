//! Port of `BenchmarkExperimentAnalyzerTest.kt`.
mod common;

use std::path::PathBuf;

use aps_benchmark::{
    BenchmarkCase, BenchmarkRun, ProfileEvidence, ProfileStatus, RegressionPolicy, Scenario,
    compare_baseline_profile,
};
use common::{case, metric, run};

fn profiled_case(status: ProfileStatus, value: f64, trace: Option<&str>) -> BenchmarkCase {
    let mut benchmark_case = case(
        "ExampleBenchmark",
        "scroll",
        Scenario::Scroll,
        vec![metric(
            "frameDurationMs",
            "ms",
            aps_benchmark::MetricDirection::LowerIsBetter,
            &[value],
        )],
    );
    benchmark_case.profile = Some(ProfileEvidence {
        status,
        ..ProfileEvidence::default()
    });
    benchmark_case.trace_artifacts = trace.map(PathBuf::from).into_iter().collect();
    benchmark_case
}

fn profiled_run(id: &str, status: ProfileStatus, value: f64, trace: Option<&str>) -> BenchmarkRun {
    run(
        id,
        "Pixel",
        "2026-11-08T00:00:00Z",
        vec![profiled_case(status, value, trace)],
    )
}

#[test]
fn compares_profile_state_and_pairs_traces_by_case() {
    let before = profiled_run(
        "before",
        ProfileStatus::Missing,
        20.0,
        Some("before.pftrace"),
    );
    let after = profiled_run(
        "after",
        ProfileStatus::Verified,
        15.0,
        Some("after.pftrace"),
    );
    let result = compare_baseline_profile(&before, &after, &RegressionPolicy::default()).unwrap();
    assert_eq!(result.baseline_run_id, "before");
    assert_eq!(result.profiled_run_id, "after");
    assert_eq!(result.baseline_status, ProfileStatus::Missing);
    assert_eq!(result.profiled_status, ProfileStatus::Verified);
    assert_eq!(result.trace_pairs.len(), 1);
    let pair = &result.trace_pairs[0];
    assert_eq!(pair.case_identity, "ExampleBenchmark#scroll");
    assert_eq!(pair.before, vec![PathBuf::from("before.pftrace")]);
    assert_eq!(pair.after, vec![PathBuf::from("after.pftrace")]);
    assert!(!result.report.comparisons.is_empty());
}

#[test]
fn does_not_report_a_partially_verified_run_as_verified() {
    let baseline = run(
        "before",
        "Pixel",
        "2026-11-08T00:00:00Z",
        vec![
            profiled_case(ProfileStatus::Verified, 10.0, None),
            profiled_case(ProfileStatus::Missing, 10.0, None),
        ],
    );
    let profiled = run(
        "after",
        "Pixel",
        "2026-11-08T00:00:01Z",
        vec![
            profiled_case(ProfileStatus::Verified, 10.0, None),
            profiled_case(ProfileStatus::Verified, 10.0, None),
        ],
    );
    let result =
        compare_baseline_profile(&baseline, &profiled, &RegressionPolicy::default()).unwrap();
    assert_eq!(result.baseline_status, ProfileStatus::Missing);
    assert_eq!(result.profiled_status, ProfileStatus::Verified);
    assert!(result.trace_pairs.is_empty());
}

#[test]
fn installed_profile_evidence_is_not_verified_evidence() {
    let baseline = run(
        "before",
        "Pixel",
        "2026-11-08T00:00:00Z",
        vec![profiled_case(ProfileStatus::Installed, 10.0, None)],
    );
    let profiled = run(
        "after",
        "Pixel",
        "2026-11-08T00:00:01Z",
        vec![profiled_case(ProfileStatus::Installed, 10.0, None)],
    );
    let result =
        compare_baseline_profile(&baseline, &profiled, &RegressionPolicy::default()).unwrap();
    assert_eq!(result.baseline_status, ProfileStatus::Installed);
    assert_eq!(result.profiled_status, ProfileStatus::Installed);
}

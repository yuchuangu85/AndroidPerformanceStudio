//! Ports of `RegressionAnalyzerTest.kt` and `ReleaseGateEvaluatorTest.kt`,
//! plus the Rust fail-closed compatibility and BCa evidence rules.
mod common;

use aps_benchmark::{
    BenchmarkCase, BenchmarkRun, Classification, CompatibilityIssue, Confidence, GateDecision,
    GateReason, MetricDirection, RegressionPolicy, ReleaseGatePolicy, Scenario, compare,
    evaluate_gate,
};
use common::{case, comparison, metric, report, run};

fn policy() -> RegressionPolicy {
    let mut policy = RegressionPolicy::default();
    policy.relative_threshold_percent = Some(5.0);
    policy.minimum_sample_count = 3;
    policy.bootstrap_iterations = 400;
    policy
}

fn sample_case(scenario: Scenario, samples: &[f64]) -> BenchmarkCase {
    case(
        "dev.Bench",
        "startup",
        scenario,
        vec![metric(
            "timeToInitialDisplayMs",
            "ms",
            MetricDirection::LowerIsBetter,
            samples,
        )],
    )
}

fn devices(model: &str, scenario: Scenario, samples: &[f64]) -> BenchmarkRun {
    run(
        "run",
        model,
        "2026-11-08T00:00:00Z",
        vec![sample_case(scenario, samples)],
    )
}

#[test]
fn detects_compatible_slower_regression() {
    let baseline = devices("Pixel", Scenario::Startup, &[100.0, 101.0, 99.0]);
    let current = devices("Pixel", Scenario::Startup, &[120.0, 119.0, 121.0]);
    let result = compare(&baseline, &current, &policy()).unwrap();
    assert_eq!(result.comparisons.len(), 1);
    let comparison = &result.comparisons[0];
    assert_eq!(comparison.classification, Classification::Regressed);
    assert_eq!(comparison.baseline_value, Some(100.0));
    assert_eq!(comparison.current_value, Some(120.0));
    assert_eq!(comparison.absolute_delta, Some(20.0));
    assert_eq!(comparison.relative_delta_percent, Some(20.0));
    assert_eq!(comparison.confidence, Confidence::Exact);
    assert!(comparison.confidence_interval.is_some());
    assert!(comparison.practical_threshold.is_some());
    assert_eq!(result.baseline_run_id, "run");
    assert_eq!(result.current_run_id, "run");
    assert_eq!(result.policy_version, aps_benchmark::POLICY_VERSION);
    assert_eq!(result.created_at.len(), 20);
}

#[test]
fn rejects_cross_device_comparison() {
    let baseline = devices("Pixel", Scenario::Startup, &[100.0, 101.0, 99.0]);
    let current = devices("Other", Scenario::Startup, &[120.0, 119.0, 121.0]);
    let result = compare(&baseline, &current, &policy()).unwrap();
    assert_eq!(
        result.comparisons[0].classification,
        Classification::Incompatible
    );
    let issue = result
        .compatibility_issues
        .iter()
        .find(|issue| issue.field == "device.model")
        .expect("device model issue");
    assert!(issue.hard);
    assert_eq!(issue.baseline.as_deref(), Some("Pixel"));
    assert_eq!(issue.current.as_deref(), Some("Other"));
}

#[test]
fn require_same_device_can_be_disabled() {
    let baseline = devices("Pixel", Scenario::Startup, &[100.0, 101.0, 99.0]);
    let current = devices("Other", Scenario::Startup, &[100.0, 101.0, 99.0]);
    let mut policy = policy();
    policy.require_same_device = false;
    let result = compare(&baseline, &current, &policy).unwrap();
    assert!(
        result
            .compatibility_issues
            .iter()
            .all(|issue| issue.field != "device.model")
    );
    assert_ne!(
        result.comparisons[0].classification,
        Classification::Incompatible
    );
}

#[test]
fn rejects_comparison_across_benchmark_scenarios() {
    let baseline = devices("Pixel", Scenario::Startup, &[100.0, 101.0, 99.0]);
    let current = devices("Pixel", Scenario::Scroll, &[120.0, 119.0, 121.0]);
    let result = compare(&baseline, &current, &policy()).unwrap();
    assert_eq!(
        result.comparisons[0].classification,
        Classification::Incompatible
    );
    assert_eq!(
        result.compatibility_issues[0].field,
        "case.dev.Bench#startup.scenario"
    );
}

#[test]
fn compares_current_cases_only() {
    let baseline = run(
        "run",
        "Pixel",
        "2026-11-08T00:00:00Z",
        vec![
            sample_case(Scenario::Startup, &[100.0, 101.0, 99.0]),
            case(
                "dev.Bench",
                "scroll",
                Scenario::Scroll,
                vec![metric(
                    "frameDurationMs",
                    "ms",
                    MetricDirection::LowerIsBetter,
                    &[10.0, 11.0, 9.0],
                )],
            ),
        ],
    );
    let current = run(
        "run",
        "Pixel",
        "2026-11-08T00:00:01Z",
        vec![case(
            "dev.Bench",
            "scroll",
            Scenario::Scroll,
            vec![metric(
                "frameDurationMs",
                "ms",
                MetricDirection::LowerIsBetter,
                &[12.0, 13.0, 11.0],
            )],
        )],
    );
    let result = compare(&baseline, &current, &policy()).unwrap();
    assert_eq!(result.comparisons.len(), 1);
    assert_eq!(result.comparisons[0].case_identity, "dev.Bench#scroll");
    assert_eq!(result.comparisons[0].metric_name, "frameDurationMs");
    assert_eq!(result.comparisons[0].baseline_value, Some(10.0));
    assert_eq!(result.comparisons[0].current_value, Some(12.0));
    assert!(
        result
            .comparisons
            .iter()
            .all(|comparison| comparison.case_identity != "dev.Bench#startup")
    );
}

#[test]
fn missing_baseline_case_or_metric_is_inconclusive() {
    let baseline = devices("Pixel", Scenario::Startup, &[100.0, 101.0, 99.0]);
    let mut other_case = sample_case(Scenario::Startup, &[100.0, 101.0, 99.0]);
    other_case.test_name = "animation".to_string();
    let current = run(
        "run",
        "Pixel",
        "2026-11-08T00:00:01Z",
        vec![
            sample_case(Scenario::Startup, &[100.0, 101.0, 99.0]),
            other_case,
        ],
    );
    let result = compare(&baseline, &current, &policy()).unwrap();
    assert_eq!(result.comparisons.len(), 2);
    let missing_case = result
        .comparisons
        .iter()
        .find(|comparison| comparison.case_identity == "dev.Bench#animation")
        .unwrap();
    assert_eq!(missing_case.classification, Classification::Inconclusive);
    assert!(missing_case.baseline_value.is_none());
    assert!(
        missing_case
            .reasons
            .iter()
            .any(|reason| reason.contains("No baseline metric"))
    );

    let mut extra_metric = sample_case(Scenario::Startup, &[100.0, 101.0, 99.0]);
    extra_metric.metrics.push(metric(
        "frameDurationMs",
        "ms",
        MetricDirection::LowerIsBetter,
        &[10.0, 11.0, 9.0],
    ));
    let baseline = devices("Pixel", Scenario::Startup, &[100.0, 101.0, 99.0]);
    let current = run("run", "Pixel", "2026-11-08T00:00:01Z", vec![extra_metric]);
    let result = compare(&baseline, &current, &policy()).unwrap();
    let extra = result
        .comparisons
        .iter()
        .find(|comparison| comparison.metric_name == "frameDurationMs")
        .unwrap();
    assert_eq!(extra.classification, Classification::Inconclusive);
}

#[test]
fn differing_units_are_incompatible() {
    let baseline = run(
        "run",
        "Pixel",
        "2026-11-08T00:00:00Z",
        vec![case(
            "dev.Bench",
            "startup",
            Scenario::Startup,
            vec![metric(
                "timeToInitialDisplayMs",
                "ms",
                MetricDirection::LowerIsBetter,
                &[100.0, 101.0, 99.0],
            )],
        )],
    );
    let current = run(
        "run",
        "Pixel",
        "2026-11-08T00:00:01Z",
        vec![case(
            "dev.Bench",
            "startup",
            Scenario::Startup,
            vec![metric(
                "timeToInitialDisplayMs",
                "ns",
                MetricDirection::LowerIsBetter,
                &[100.0, 101.0, 99.0],
            )],
        )],
    );
    let result = compare(&baseline, &current, &policy()).unwrap();
    assert_eq!(
        result.comparisons[0].classification,
        Classification::Incompatible
    );
}

#[test]
fn unknown_direction_requires_explicit_configuration() {
    let baseline = run(
        "run",
        "Pixel",
        "2026-11-08T00:00:00Z",
        vec![case(
            "dev.Bench",
            "startup",
            Scenario::Startup,
            vec![metric(
                "customScore",
                "points",
                MetricDirection::Unknown,
                &[10.0, 11.0, 12.0],
            )],
        )],
    );
    let current = run(
        "run",
        "Pixel",
        "2026-11-08T00:00:01Z",
        vec![case(
            "dev.Bench",
            "startup",
            Scenario::Startup,
            vec![metric(
                "customScore",
                "points",
                MetricDirection::Unknown,
                &[20.0, 21.0, 22.0],
            )],
        )],
    );
    let result = compare(&baseline, &current, &policy()).unwrap();
    assert_eq!(
        result.comparisons[0].classification,
        Classification::Inconclusive
    );
    let mut configured = policy();
    configured
        .directions
        .insert("customScore".to_string(), MetricDirection::LowerIsBetter);
    let result = compare(&baseline, &current, &configured).unwrap();
    assert_eq!(
        result.comparisons[0].classification,
        Classification::Regressed
    );
}

#[test]
fn confidence_is_exact_only_with_enough_raw_samples_on_both_sides() {
    let baseline = devices("Pixel", Scenario::Startup, &[100.0, 101.0, 99.0]);
    let current = devices("Pixel", Scenario::Startup, &[120.0, 119.0, 121.0]);
    let result = compare(&baseline, &current, &policy()).unwrap();
    assert_eq!(result.comparisons[0].confidence, Confidence::Exact);
    let low = devices("Pixel", Scenario::Startup, &[120.0, 119.0]);
    let result = compare(&baseline, &low, &policy()).unwrap();
    assert_eq!(result.comparisons[0].confidence, Confidence::Partial);
    assert_eq!(
        result.comparisons[0].classification,
        Classification::Inconclusive
    );
}

#[test]
fn fingerprint_difference_is_a_soft_issue() {
    let baseline = devices("Pixel", Scenario::Startup, &[100.0, 101.0, 99.0]);
    let mut current = devices("Pixel", Scenario::Startup, &[100.0, 101.0, 99.0]);
    current.device.fingerprint = Some("other".to_string());
    let result = compare(&baseline, &current, &policy()).unwrap();
    let issue = result
        .compatibility_issues
        .iter()
        .find(|issue| issue.field == "device.fingerprint")
        .expect("fingerprint issue");
    assert!(!issue.hard);
    assert_ne!(
        result.comparisons[0].classification,
        Classification::Incompatible
    );
}

#[test]
fn missing_cohort_identity_is_a_hard_issue() {
    let baseline = devices("Pixel", Scenario::Startup, &[100.0, 101.0, 99.0]);
    let mut current = devices("Pixel", Scenario::Startup, &[100.0, 101.0, 99.0]);
    current.library_version = None;
    current.data_version = None;
    current.device.abi = None;
    let result = compare(&baseline, &current, &policy()).unwrap();
    let fields: Vec<_> = result
        .compatibility_issues
        .iter()
        .filter(|issue| issue.hard)
        .map(|issue| issue.field.as_str())
        .collect();
    assert!(fields.contains(&"library_version"), "{fields:?}");
    assert!(fields.contains(&"data_version"), "{fields:?}");
    assert!(fields.contains(&"device.abi"), "{fields:?}");
    assert_eq!(
        result.comparisons[0].classification,
        Classification::Incompatible
    );

    let mut both_missing = devices("Pixel", Scenario::Startup, &[100.0, 101.0, 99.0]);
    both_missing.device.model = None;
    let baseline_missing = devices("Pixel", Scenario::Startup, &[100.0, 101.0, 99.0]);
    let report = compare(&baseline_missing, &both_missing, &policy()).unwrap();
    assert!(
        report
            .compatibility_issues
            .iter()
            .any(|issue| issue.field == "device.model" && issue.hard)
    );
}

#[test]
fn invalid_policy_is_rejected() {
    let baseline = devices("Pixel", Scenario::Startup, &[100.0, 101.0, 99.0]);
    let current = devices("Pixel", Scenario::Startup, &[100.0, 101.0, 99.0]);
    let mut invalid = policy();
    invalid.minimum_sample_count = 1;
    assert!(compare(&baseline, &current, &invalid).is_err());
    let mut invalid = policy();
    invalid.relative_threshold_percent = Some(f64::NAN);
    assert!(compare(&baseline, &current, &invalid).is_err());
}

#[test]
fn exact_regression_fails_the_gate_and_is_listed_as_blocking() {
    let regression = comparison(Classification::Regressed, Confidence::Exact, "timeMs");
    let result = evaluate_gate(
        &report(vec![regression.clone()]),
        &ReleaseGatePolicy::default(),
    );
    assert_eq!(result.decision, GateDecision::Fail);
    assert_eq!(result.blocking_comparisons, vec![regression]);
    assert!(result.reasons.contains(&GateReason::BlockingRegressions));
    assert_eq!(result.policy_version, "1");
}

#[test]
fn incompatible_environments_take_precedence_over_the_regression_verdict() {
    let mut report = report(vec![comparison(
        Classification::Regressed,
        Confidence::Exact,
        "timeMs",
    )]);
    report.compatibility_issues.push(CompatibilityIssue {
        field: "device.apiLevel".to_string(),
        baseline: Some("34".to_string()),
        current: Some("35".to_string()),
        hard: true,
    });
    let result = evaluate_gate(&report, &ReleaseGatePolicy::default());
    assert_eq!(result.decision, GateDecision::Inconclusive);
    assert!(result.reasons.contains(&GateReason::IncompatibleEvidence));
}

#[test]
fn a_low_confidence_regression_is_inconclusive_instead_of_blocking() {
    let regression = comparison(Classification::Regressed, Confidence::Partial, "timeMs");
    let result = evaluate_gate(&report(vec![regression]), &ReleaseGatePolicy::default());
    assert_eq!(result.decision, GateDecision::Inconclusive);
    assert!(result.blocking_comparisons.is_empty());
    assert!(result.reasons.contains(&GateReason::InconclusiveEvidence));
}

#[test]
fn a_policy_that_forbids_inconclusive_evidence_fails_on_it() {
    let regression = comparison(Classification::Regressed, Confidence::Partial, "timeMs");
    let policy = ReleaseGatePolicy {
        allow_inconclusive: false,
        ..ReleaseGatePolicy::default()
    };
    let result = evaluate_gate(&report(vec![regression]), &policy);
    assert_eq!(result.decision, GateDecision::Fail);
    assert!(result.reasons.contains(&GateReason::InconclusiveDisallowed));
}

#[test]
fn blocking_metric_patterns_decide_which_regressions_can_fail_the_gate() {
    let regression = comparison(Classification::Regressed, Confidence::Exact, "timeMs");
    let policy = ReleaseGatePolicy {
        blocking_metric_patterns: vec!["startupMs".to_string()],
        ..ReleaseGatePolicy::default()
    };
    let result = evaluate_gate(&report(vec![regression]), &policy);
    assert_eq!(result.decision, GateDecision::Pass);
    assert!(!result.reasons.contains(&GateReason::BlockingRegressions));
}

#[test]
fn the_configured_regression_allowance_is_honoured() {
    let comparisons = vec![
        comparison(Classification::Regressed, Confidence::Exact, "timeMs"),
        comparison(Classification::Regressed, Confidence::Exact, "frameMs"),
    ];
    let allow_two = ReleaseGatePolicy {
        max_allowed_regressions: 2,
        ..ReleaseGatePolicy::default()
    };
    assert_eq!(
        evaluate_gate(&report(comparisons.clone()), &allow_two).decision,
        GateDecision::Pass
    );
    let allow_one = ReleaseGatePolicy {
        max_allowed_regressions: 1,
        ..ReleaseGatePolicy::default()
    };
    assert_eq!(
        evaluate_gate(&report(comparisons), &allow_one).decision,
        GateDecision::Fail
    );
}

#[test]
fn a_clean_comparison_passes_and_reports_the_policy_version() {
    let policy = ReleaseGatePolicy {
        policy_version: "2026.1".to_string(),
        ..ReleaseGatePolicy::default()
    };
    let result = evaluate_gate(
        &report(vec![comparison(
            Classification::Improved,
            Confidence::Exact,
            "timeMs",
        )]),
        &policy,
    );
    assert_eq!(result.decision, GateDecision::Pass);
    assert_eq!(result.policy_version, "2026.1");
    assert!(result.reasons.contains(&GateReason::NoBlockingRegression));
}

#[test]
fn minimum_confidence_accepts_derived_evidence_when_the_policy_asks_for_it() {
    let regression = comparison(Classification::Regressed, Confidence::Derived, "timeMs");
    let blocking = ReleaseGatePolicy {
        minimum_confidence: Confidence::Derived,
        ..ReleaseGatePolicy::default()
    };
    assert_eq!(
        evaluate_gate(&report(vec![regression.clone()]), &blocking).decision,
        GateDecision::Fail
    );
    assert_eq!(
        evaluate_gate(&report(vec![regression]), &ReleaseGatePolicy::default()).decision,
        GateDecision::Inconclusive
    );
}

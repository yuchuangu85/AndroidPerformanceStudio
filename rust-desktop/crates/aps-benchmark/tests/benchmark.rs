//! Deferred tests: do not run before complete migration implementation.
use aps_benchmark::{BenchmarkRun, Classification, MetricDirection, RegressionPolicy, compare};

fn document(device: &str, values: &str) -> String {
    format!(
        r#"{{"benchmarkDataVersion":3,"benchmarkLibraryVersion":"1","context":{{"deviceModel":"{device}","apiLevel":35,"abi":"arm64-v8a","fingerprint":"fp","physicalDevice":true,"build":{{"variant":"release"}}}},"benchmarks":[{{"name":"dev.Example.scroll","packageName":"dev.example","compilationMode":"FULL","startupMode":"WARM","scenario":"SCROLL","metrics":{{"frameDurationMs":{{"unit":"ms","runs":{values}}}}}}}]}}"#
    )
}

#[test]
fn parses_androidx_json_raw_samples_and_metadata() {
    let run = BenchmarkRun::parse_json(document("Pixel 9", "[10,11,12,13,14]").as_bytes()).unwrap();
    assert_eq!(run.cases()[0].identity, "dev.Example#scroll");
    assert_eq!(run.cases()[0].metrics["frameDurationMs"].samples.len(), 5);
    assert_eq!(
        run.cases()[0].metrics["frameDurationMs"].direction,
        MetricDirection::LowerIsBetter
    );
}

#[test]
fn incompatible_device_cohort_never_mixes_samples_or_reports_regression() {
    let baseline =
        BenchmarkRun::parse_json(document("Pixel 8", "[10,10,10,10,10]").as_bytes()).unwrap();
    let current =
        BenchmarkRun::parse_json(document("Pixel 9", "[20,20,20,20,20]").as_bytes()).unwrap();
    let report = compare(&baseline, &current, &RegressionPolicy::default()).unwrap();
    assert_eq!(
        report.comparisons[0].classification,
        Classification::Incompatible
    );
    assert_eq!(report.gate(false), aps_benchmark::GateDecision::Fail);
    assert_eq!(report.gate(true), aps_benchmark::GateDecision::Inconclusive);
}

#[test]
fn bca_interval_and_practical_gate_distinguish_stable_from_regressed() {
    let baseline =
        BenchmarkRun::parse_json(document("Pixel 9", "[10,10.1,9.9,10.2,9.8,10]").as_bytes())
            .unwrap();
    let current =
        BenchmarkRun::parse_json(document("Pixel 9", "[12,12.1,11.9,12.2,11.8,12]").as_bytes())
            .unwrap();
    let mut policy = RegressionPolicy::default();
    policy.bootstrap_iterations = 200;
    let report = compare(&baseline, &current, &policy).unwrap();
    assert!(report.comparisons[0].confidence_interval.is_some());
    assert_eq!(
        report.comparisons[0].classification,
        Classification::Regressed
    );
}

#[test]
fn unknown_direction_and_insufficient_samples_are_inconclusive() {
    let mut baseline = document("Pixel 9", "[1,2,3,4,5]");
    baseline = baseline
        .replace("frameDurationMs", "customScore")
        .replace("\"ms\"", "\"points\"");
    let current = baseline.replace("[1,2,3,4,5]", "[2,3,4,5,6]");
    let report = compare(
        &BenchmarkRun::parse_json(baseline.as_bytes()).unwrap(),
        &BenchmarkRun::parse_json(current.as_bytes()).unwrap(),
        &RegressionPolicy::default(),
    )
    .unwrap();
    assert_eq!(
        report.comparisons[0].classification,
        Classification::Inconclusive
    );
}

#[test]
fn parser_rejects_duplicate_case_identity_and_nan() {
    assert!(
        BenchmarkRun::parse_json(
            br#"{"benchmarks":[{"name":"x","metrics":{"m":1}},{"name":"x","metrics":{"m":1}}]}"#
        )
        .is_err()
    );
    assert!(
        BenchmarkRun::parse_json(br#"{"benchmarks":[{"name":"x","metrics":{"m":"NaN"}}]}"#)
            .is_err()
    );
}

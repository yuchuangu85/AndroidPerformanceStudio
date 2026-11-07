#![allow(dead_code)]
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

use aps_benchmark::{
    BenchmarkBuild, BenchmarkCase, BenchmarkDevice, BenchmarkRun, Classification, Confidence,
    Metric, MetricComparison, MetricDirection, POLICY_VERSION, RegressionReport, Scenario,
};

static COUNTER: AtomicU64 = AtomicU64::new(0);

pub fn temp_dir(label: &str) -> PathBuf {
    let dir = std::env::temp_dir().join(format!(
        "aps-benchmark-{label}-{}-{}",
        std::process::id(),
        COUNTER.fetch_add(1, Ordering::Relaxed)
    ));
    std::fs::create_dir_all(&dir).unwrap();
    dir
}

pub fn write_text(path: &Path, content: &str) {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent).unwrap();
    }
    std::fs::write(path, content).unwrap();
}

pub fn device(model: &str) -> BenchmarkDevice {
    BenchmarkDevice {
        model: Some(model.to_string()),
        brand: Some("Google".to_string()),
        api_level: Some(35),
        os_version: Some("15".to_string()),
        abi: Some("arm64-v8a".to_string()),
        fingerprint: Some("google/build".to_string()),
        core_count: Some(8),
        physical_device: Some(true),
    }
}

pub fn build() -> BenchmarkBuild {
    BenchmarkBuild {
        target_package: Some("dev.app".to_string()),
        version_name: Some("1.0".to_string()),
        version_code: Some(1),
        variant: Some("benchmark".to_string()),
        git_commit: Some("abc1234".to_string()),
        git_branch: Some("main".to_string()),
    }
}

pub fn sample_median(samples: &[f64]) -> Option<f64> {
    if samples.is_empty() {
        return None;
    }
    let mut sorted = samples.to_vec();
    sorted.sort_by(f64::total_cmp);
    let middle = sorted.len() / 2;
    Some(if sorted.len() % 2 == 0 {
        sorted[middle - 1] / 2.0 + sorted[middle] / 2.0
    } else {
        sorted[middle]
    })
}

pub fn metric(name: &str, unit: &str, direction: MetricDirection, samples: &[f64]) -> Metric {
    Metric {
        name: name.to_string(),
        unit: unit.to_string(),
        direction,
        samples: samples.to_vec(),
        minimum: samples.iter().copied().reduce(f64::min),
        median: sample_median(samples),
        maximum: samples.iter().copied().reduce(f64::max),
        confidence: if samples.is_empty() {
            Confidence::Partial
        } else {
            Confidence::Exact
        },
        source_fields: Default::default(),
    }
}

pub fn case(
    class_name: &str,
    test_name: &str,
    scenario: Scenario,
    metrics: Vec<Metric>,
) -> BenchmarkCase {
    BenchmarkCase {
        class_name: class_name.to_string(),
        test_name: test_name.to_string(),
        package_name: Some("dev.app".to_string()),
        compilation_mode: Some("Partial".to_string()),
        startup_mode: Some("COLD".to_string()),
        iteration_count: Some(3),
        scenario,
        profile: None,
        trace_artifacts: Vec::new(),
        metrics,
    }
}

pub fn run(
    id: &str,
    device_model: &str,
    imported_at: &str,
    cases: Vec<BenchmarkCase>,
) -> BenchmarkRun {
    BenchmarkRun {
        id: id.to_string(),
        source_path: PathBuf::from(format!("{id}.json")),
        imported_at: imported_at.to_string(),
        data_version: Some(1),
        library_version: Some("1.4".to_string()),
        device: device(device_model),
        build: build(),
        warnings: Vec::new(),
        cases,
    }
}

pub fn simple_run(id: &str, device_model: &str, imported_at: &str, value: f64) -> BenchmarkRun {
    run(
        id,
        device_model,
        imported_at,
        vec![case(
            "dev.Bench",
            "test",
            Scenario::Scroll,
            vec![metric(
                "timeMs",
                "ms",
                MetricDirection::LowerIsBetter,
                &[value],
            )],
        )],
    )
}

pub fn comparison(
    classification: Classification,
    confidence: Confidence,
    name: &str,
) -> MetricComparison {
    MetricComparison {
        case_identity: "Bench#test".to_string(),
        metric_name: name.to_string(),
        unit: "ms".to_string(),
        baseline_value: Some(100.0),
        current_value: Some(120.0),
        absolute_delta: Some(20.0),
        relative_delta_percent: Some(20.0),
        classification,
        confidence,
        reasons: Vec::new(),
        confidence_interval: Some((15.0, 25.0)),
        practical_threshold: Some(5.0),
    }
}

pub fn report(comparisons: Vec<MetricComparison>) -> RegressionReport {
    RegressionReport {
        baseline_run_id: "baseline".to_string(),
        current_run_id: "current".to_string(),
        created_at: "2026-11-08T00:00:00Z".to_string(),
        comparisons,
        compatibility_issues: Vec::new(),
        policy_version: POLICY_VERSION,
    }
}

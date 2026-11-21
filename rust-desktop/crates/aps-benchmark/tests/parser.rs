//! Ports of `BenchmarkJsonParserTest.kt` plus the Rust fail-closed import bounds.
mod common;

use std::path::Path;

use aps_benchmark::{
    BenchmarkRun, Confidence, MAX_IMPORT_BYTES, MetricDirection, ProfileStatus, Scenario,
};
use common::{temp_dir, write_text};

fn parse_at(directory: &Path, name: &str, json: &str) -> Result<BenchmarkRun, String> {
    let path = directory.join(name);
    write_text(&path, json);
    BenchmarkRun::open(&path, MAX_IMPORT_BYTES)
}

#[test]
fn parses_benchmark_data_and_preserves_unknown_metrics() {
    let directory = temp_dir("parser-basic");
    let run = parse_at(
        &directory,
        "benchmarkData.json",
        r#"{"benchmarkDataVersion":3,"context":{"deviceModel":"Pixel 9","apiLevel":35,"abi":"arm64-v8a"},"benchmarks":[{"name":"dev.Example.startup","compilationMode":"Partial","metrics":{"timeToInitialDisplayMs":{"minimum":100,"median":110,"maximum":130,"runs":[100,110,130]},"customScore":{"median":42}}}]}"#,
    )
    .unwrap();
    assert_eq!(run.device.model.as_deref(), Some("Pixel 9"));
    assert_eq!(run.device.api_level, Some(35));
    assert_eq!(run.data_version, Some(3));
    assert_eq!(run.cases.len(), 1);
    let case = &run.cases[0];
    assert_eq!(case.identity(), "dev.Example#startup");
    assert_eq!(case.metrics.len(), 2);
    assert_eq!(case.compilation_mode.as_deref(), Some("Partial"));
    let display = case.metric("timeToInitialDisplayMs").unwrap();
    assert_eq!(display.direction, MetricDirection::LowerIsBetter);
    assert_eq!(display.unit, "ms");
    assert_eq!(display.samples, vec![100.0, 110.0, 130.0]);
    assert_eq!(display.minimum, Some(100.0));
    assert_eq!(display.median, Some(110.0));
    assert_eq!(display.maximum, Some(130.0));
    assert_eq!(display.confidence, Confidence::Exact);
    let custom = case.metric("customScore").unwrap();
    assert_eq!(custom.direction, MetricDirection::Unknown);
    assert_eq!(custom.unit, "unit");
    assert!(custom.samples.is_empty());
    assert_eq!(custom.median, Some(42.0));
    assert_eq!(custom.confidence, Confidence::Partial);
    assert!(!custom.source_fields.is_empty());
    assert_eq!(run.id.len(), 64);
    assert!(run.imported_at.ends_with('Z'));
    assert_eq!(run.imported_at.len(), 20);
    assert_eq!(run.source_path, directory.join("benchmarkData.json"));
}

#[test]
fn preserves_scenario_profile_and_trace_artifacts() {
    let directory = temp_dir("parser-evidence");
    let path = directory.join("benchmarkData.json");
    write_text(
        &path,
        r#"{"benchmarkDataVersion":3,"benchmarks":[{"name":"dev.Example.scroll","scenario":"scroll","baselineProfile":{"status":"verified","path":"profiles/baseline-prof.txt","sha256":"abc"},"tracePaths":["traces/scroll.perfetto-trace"],"metrics":{"frameDurationMs":{"median":12,"runs":[11,12,13]}}}]}"#,
    );
    let run = BenchmarkRun::open(&path, MAX_IMPORT_BYTES).unwrap();
    let case = &run.cases[0];
    assert_eq!(case.scenario, Scenario::Scroll);
    let profile = case.profile.as_ref().unwrap();
    assert_eq!(profile.status, ProfileStatus::Verified);
    assert_eq!(
        profile.artifact.as_deref(),
        Some(directory.join("profiles/baseline-prof.txt").as_path())
    );
    assert_eq!(profile.profile_hash.as_deref(), Some("abc"));
    assert_eq!(
        case.trace_artifacts,
        vec![directory.join("traces/scroll.perfetto-trace")]
    );
}

#[test]
fn content_addressed_id_is_sha256_of_the_json_bytes() {
    let bytes = br#"{"benchmarks":[{"name":"dev.Example.scroll","metrics":{"frameDurationMs":{"runs":[1,2,3]}}}]}"#;
    let first = BenchmarkRun::parse_json(bytes).unwrap();
    let second = BenchmarkRun::parse_json(bytes).unwrap();
    assert_eq!(first.id, second.id);
    assert_eq!(first.id.len(), 64);
    assert!(
        first
            .id
            .chars()
            .all(|character| character.is_ascii_hexdigit())
    );
    // RFC3339 UTC strings are lexicographically sortable.
    assert!(first.imported_at <= second.imported_at);
    let other = BenchmarkRun::parse_json(
        br#"{"benchmarks":[{"name":"dev.Example.scroll","metrics":{"frameDurationMs":{"runs":[1,2,4]}}}]}"#,
    )
    .unwrap();
    assert_ne!(first.id, other.id);
}

#[test]
fn skips_invalid_cases_with_warnings_and_keeps_valid_ones() {
    let run = BenchmarkRun::parse_json(
        br#"{"benchmarks":[{"name":"dev.Broken.empty","metrics":{}},{"name":"dev.Valid.scroll","metrics":{"frameDurationMs":1}}]}"#,
    )
    .unwrap();
    assert_eq!(run.cases.len(), 1);
    assert_eq!(run.cases[0].identity(), "dev.Valid#scroll");
    assert_eq!(run.warnings.len(), 1);
    assert!(run.warnings[0].starts_with("Case #0 skipped:"));
}

#[test]
fn warns_about_unknown_top_level_fields() {
    let run = BenchmarkRun::parse_json(
        br#"{"surprise":true,"benchmarks":[{"name":"dev.Valid.scroll","metrics":{"frameDurationMs":1}}]}"#,
    )
    .unwrap();
    assert!(
        run.warnings
            .iter()
            .any(|warning| warning.contains("surprise"))
    );
}

#[test]
fn rejects_duplicate_case_identity() {
    let error = BenchmarkRun::parse_json(
        br#"{"benchmarks":[{"name":"x","className":"C","testName":"t","metrics":{"m":1}},{"name":"x","className":"C","testName":"t","metrics":{"m":1}}]}"#,
    )
    .unwrap_err();
    assert!(error.contains("Duplicate"), "{error}");
}

#[test]
fn rejects_nonfinite_samples_and_case_limits() {
    assert!(
        BenchmarkRun::parse_json(br#"{"benchmarks":[{"name":"x","metrics":{"m":"NaN"}}]}"#)
            .is_err()
    );
    assert!(
        BenchmarkRun::parse_json(
            br#"{"benchmarks":[{"name":"x","metrics":{"m":{"runs":[1,"NaN"]}}}]}"#
        )
        .is_err()
    );
    let cases = (0..=10_000)
        .map(|index| {
            format!(
                r#"{{"name":"case{index}","className":"C","testName":"t{index}","metrics":{{"m":1}}}}"#
            )
        })
        .collect::<Vec<_>>()
        .join(",");
    let oversized = format!(r#"{{"benchmarks":[{cases}]}}"#);
    assert!(BenchmarkRun::parse_json(oversized.as_bytes()).is_err());
}

#[test]
fn rejects_missing_nonregular_and_oversized_files() {
    let directory = temp_dir("parser-files");
    assert!(BenchmarkRun::open(&directory, MAX_IMPORT_BYTES).is_err());
    assert!(BenchmarkRun::open(&directory.join("absent.json"), MAX_IMPORT_BYTES).is_err());
    let path = directory.join("result.json");
    write_text(
        &path,
        r#"{"benchmarks":[{"name":"dev.Valid.scroll","metrics":{"frameDurationMs":1}}]}"#,
    );
    assert!(BenchmarkRun::open(&path, 4).is_err());
}

#[test]
fn parses_measurement_objects_traces_and_profile_status() {
    let directory = temp_dir("parser-objects");
    let path = directory.join("benchmarkData.json");
    write_text(
        &path,
        r#"{"benchmarks":[{"name":"dev.Bench.coldStartup","params":{"compilationMode":"Full","startupMode":"COLD","scenario":"cold"},"packageName":"dev.app","repeatIterations":7,"baselineProfileStatus":"installed","baselineProfileSource":"plugin","baselineProfilePath":"profiles/p.txt","profilerOutputs":[{"filePath":"traces/a.perfetto-trace"},"traces/b.trace","notes.txt"],"measurements":{"timeToInitialDisplayMs":{"unit":"ns","runs":[{"value":10},{"measurement":11},{"ns":12}]}}}]}"#,
    );
    let run = BenchmarkRun::open(&path, MAX_IMPORT_BYTES).unwrap();
    let case = &run.cases[0];
    assert_eq!(case.class_name, "dev.Bench");
    assert_eq!(case.test_name, "coldStartup");
    assert_eq!(case.package_name.as_deref(), Some("dev.app"));
    assert_eq!(case.compilation_mode.as_deref(), Some("Full"));
    assert_eq!(case.startup_mode.as_deref(), Some("COLD"));
    assert_eq!(case.iteration_count, Some(7));
    assert_eq!(case.scenario, Scenario::Startup);
    let profile = case.profile.as_ref().unwrap();
    assert_eq!(profile.status, ProfileStatus::Installed);
    assert_eq!(profile.source.as_deref(), Some("plugin"));
    assert_eq!(
        case.trace_artifacts,
        vec![
            directory.join("traces/a.perfetto-trace"),
            directory.join("traces/b.trace"),
        ]
    );
    let metric = case.metric("timeToInitialDisplayMs").unwrap();
    assert_eq!(metric.unit, "ns");
    assert_eq!(metric.samples, vec![10.0, 11.0, 12.0]);
    assert_eq!(metric.confidence, Confidence::Exact);
}

#[test]
fn infers_units_and_directions_like_compose() {
    let run = BenchmarkRun::parse_json(
        br#"{"benchmarks":[{"name":"dev.Valid.scroll","metrics":{"frameDurationMs":1,"timeNs":2,"cacheBytes":3,"sizeKb":4,"jankPercent":5,"throughputOps":6,"customScore":7}}]}"#,
    )
    .unwrap();
    let case = &run.cases[0];
    assert_eq!(case.metric("frameDurationMs").unwrap().unit, "ms");
    assert_eq!(case.metric("timeNs").unwrap().unit, "ns");
    assert_eq!(case.metric("cacheBytes").unwrap().unit, "bytes");
    assert_eq!(case.metric("sizeKb").unwrap().unit, "bytes");
    assert_eq!(case.metric("jankPercent").unwrap().unit, "%");
    assert_eq!(
        case.metric("throughputOps").unwrap().direction,
        MetricDirection::HigherIsBetter
    );
    assert_eq!(
        case.metric("frameDurationMs").unwrap().direction,
        MetricDirection::LowerIsBetter
    );
}

#[test]
fn scenario_is_inferred_from_the_case_name() {
    let run = BenchmarkRun::parse_json(
        br#"{"benchmarks":[{"name":"dev.Example.scrollBenchmark","metrics":{"m":1}},{"name":"dev.Example.coldStartup","metrics":{"m":1}},{"name":"dev.Example.animation","metrics":{"m":1}},{"name":"dev.Example.pageNavigation","metrics":{"m":1}},{"name":"dev.Example.unknown","metrics":{"m":1}}]}"#,
    )
    .unwrap();
    let scenarios: Vec<_> = run.cases.iter().map(|case| case.scenario).collect();
    assert_eq!(
        scenarios,
        vec![
            Scenario::Scroll,
            Scenario::Startup,
            Scenario::Animation,
            Scenario::PageSwitch,
            Scenario::Unknown,
        ]
    );
}

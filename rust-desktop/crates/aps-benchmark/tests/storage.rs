//! Port of `SqliteBenchmarkStoreTest.kt` against the rusqlite store.
mod common;

use std::path::PathBuf;

use aps_benchmark::{
    BenchmarkCase, BenchmarkRun, BenchmarkStore, MAX_IMPORT_BYTES, ProfileEvidence, ProfileStatus,
    Scenario,
};
use common::{case, metric, run, simple_run, temp_dir};

fn history_run(
    id: &str,
    version: &str,
    commit: &str,
    imported_at: &str,
    value: f64,
) -> BenchmarkRun {
    let mut benchmark_run = simple_run(id, "Pixel", imported_at, value);
    benchmark_run.build.version_name = Some(version.to_string());
    benchmark_run.build.git_commit = Some(commit.to_string());
    benchmark_run
}

fn profiled_case() -> BenchmarkCase {
    let mut benchmark_case = case(
        "Bench",
        "test",
        Scenario::Scroll,
        vec![metric(
            "timeMs",
            "ms",
            aps_benchmark::MetricDirection::LowerIsBetter,
            &[1.0],
        )],
    );
    benchmark_case.compilation_mode = Some("speed-profile".to_string());
    benchmark_case.startup_mode = None;
    benchmark_case.iteration_count = Some(1);
    benchmark_case.trace_artifacts = vec![PathBuf::from("trace.perfetto-trace")];
    benchmark_case.profile = Some(ProfileEvidence {
        status: ProfileStatus::Verified,
        source: Some("baseline-profile-plugin".to_string()),
        artifact: Some(PathBuf::from("baseline-prof.txt")),
        profile_hash: Some("abc".to_string()),
    });
    benchmark_case
}

#[test]
fn persists_run_summary_idempotently() {
    let directory = temp_dir("storage-summary");
    let database = directory.join("db.sqlite");
    let benchmark_run = run(
        "run",
        "Pixel",
        "2026-11-08T00:00:00Z",
        vec![profiled_case()],
    );
    {
        let store = BenchmarkStore::open(&database).unwrap();
        store.save(&benchmark_run).unwrap();
        store.save(&benchmark_run).unwrap();
        let saved = store.list_recent(50).unwrap();
        assert_eq!(saved.len(), 1);
        assert_eq!(saved[0].id, "run");
        assert_eq!(saved[0].device_model.as_deref(), Some("Pixel"));
        assert_eq!(saved[0].case_count, 1);
        assert_eq!(saved[0].trace_artifact_count, 1);
        assert_eq!(saved[0].baseline_profile_case_count, 1);
        assert_eq!(saved[0].version_name.as_deref(), Some("1.0"));
        assert_eq!(saved[0].git_commit.as_deref(), Some("abc1234"));
        assert_eq!(saved[0].api_level, Some(35));
    }
    let connection = rusqlite::Connection::open(&database).unwrap();
    let row: (String, String, String) = connection
        .query_row(
            "SELECT baseline_profile_source, baseline_profile_artifact, baseline_profile_hash \
             FROM benchmark_case",
            [],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        )
        .unwrap();
    assert_eq!(row.0, "baseline-profile-plugin");
    assert_eq!(row.1, "baseline-prof.txt");
    assert_eq!(row.2, "abc");
}

#[test]
fn metric_history_keeps_chronological_order_and_run_metadata() {
    let directory = temp_dir("storage-history");
    let database = directory.join("history.sqlite");
    let store = BenchmarkStore::open(&database).unwrap();
    store
        .save(&history_run(
            "r1",
            "v1.0.0",
            "aaa1111",
            "2026-01-01T00:00:00Z",
            10.0,
        ))
        .unwrap();
    store
        .save(&history_run(
            "r2",
            "v2.0.0",
            "bbb2222",
            "2026-02-01T00:00:00Z",
            20.0,
        ))
        .unwrap();
    let history = store
        .metric_history("dev.Bench#test", "timeMs", 10)
        .unwrap();
    assert_eq!(
        history
            .iter()
            .map(|point| point.run_id.as_str())
            .collect::<Vec<_>>(),
        vec!["r1", "r2"]
    );
    assert_eq!(
        history
            .iter()
            .map(|point| point.build_label.as_deref().unwrap())
            .collect::<Vec<_>>(),
        vec!["v1.0.0", "v2.0.0"]
    );
    assert_eq!(
        history
            .iter()
            .map(|point| point.git_commit.as_deref().unwrap())
            .collect::<Vec<_>>(),
        vec!["aaa1111", "bbb2222"]
    );
    assert_eq!(
        history.iter().map(|point| point.value).collect::<Vec<_>>(),
        vec![10.0, 20.0]
    );
    assert_eq!(
        history
            .iter()
            .map(|point| point.sample_count)
            .collect::<Vec<_>>(),
        vec![1, 1]
    );
    let metric = store.list_metric_keys(200).unwrap();
    assert_eq!(metric.len(), 1);
    assert_eq!(metric[0].case_identity, "dev.Bench#test");
    assert_eq!(metric[0].metric_name, "timeMs");
    assert_eq!(metric[0].unit, "ms");
    assert_eq!(metric[0].point_count, 2);
    let newest = store.list_recent(50).unwrap().remove(0);
    assert_eq!(newest.id, "r2");
    assert_eq!(newest.version_name.as_deref(), Some("v2.0.0"));
    assert_eq!(newest.git_commit.as_deref(), Some("bbb2222"));
    assert_eq!(newest.api_level, Some(35));
}

#[test]
fn history_limit_keeps_the_newest_values_oldest_first() {
    let directory = temp_dir("storage-limit");
    let store = BenchmarkStore::open(&directory.join("db.sqlite")).unwrap();
    for (index, value) in [10.0, 20.0, 30.0].into_iter().enumerate() {
        store
            .save(&history_run(
                &format!("r{index}"),
                &format!("v{index}"),
                "commit",
                &format!("2026-0{}-01T00:00:00Z", index + 1),
                value,
            ))
            .unwrap();
    }
    let history = store.metric_history("dev.Bench#test", "timeMs", 2).unwrap();
    assert_eq!(
        history.iter().map(|point| point.value).collect::<Vec<_>>(),
        vec![20.0, 30.0]
    );
    assert_eq!(store.list_recent(2).unwrap().len(), 2);
}

#[test]
fn identical_bytes_replace_the_run_instead_of_duplicating_it() {
    let directory = temp_dir("storage-content");
    let bytes = br#"{"benchmarks":[{"name":"dev.Bench.test","scenario":"scroll","metrics":{"frameDurationMs":{"runs":[1,2,3]}}}]}"#;
    let first = BenchmarkRun::parse_json(bytes).unwrap();
    let second = BenchmarkRun::parse_json(bytes).unwrap();
    assert_eq!(first.id, second.id);
    let store = BenchmarkStore::open(&directory.join("db.sqlite")).unwrap();
    store.save(&first).unwrap();
    store.save(&second).unwrap();
    assert_eq!(store.list_recent(50).unwrap().len(), 1);
}

#[test]
fn metrics_without_a_stored_median_are_not_trend_keys() {
    let directory = temp_dir("storage-null-median");
    let store = BenchmarkStore::open(&directory.join("db.sqlite")).unwrap();
    let mut benchmark_case = case(
        "Bench",
        "test",
        Scenario::Scroll,
        vec![metric(
            "timeMs",
            "ms",
            aps_benchmark::MetricDirection::LowerIsBetter,
            &[10.0],
        )],
    );
    let mut empty = metric(
        "noMedian",
        "unit",
        aps_benchmark::MetricDirection::Unknown,
        &[],
    );
    empty.median = None;
    benchmark_case.metrics.push(empty);
    store
        .save(&run(
            "run",
            "Pixel",
            "2026-11-08T00:00:00Z",
            vec![benchmark_case],
        ))
        .unwrap();
    let keys = store.list_metric_keys(10).unwrap();
    assert_eq!(keys.len(), 1);
    assert_eq!(keys[0].metric_name, "timeMs");
    assert!(
        store
            .metric_history("dev.Bench#test", "noMedian", 10)
            .unwrap()
            .is_empty()
    );
}

#[test]
fn open_creates_parent_directories() {
    let directory = temp_dir("storage-parents");
    let database = directory.join("nested").join("deeper").join("db.sqlite");
    let store = BenchmarkStore::open(&database).unwrap();
    store
        .save(&simple_run("run", "Pixel", "2026-11-08T00:00:00Z", 1.0))
        .unwrap();
    assert!(database.is_file());
}

#[test]
fn imported_file_roundtrips_to_a_stored_run() {
    let directory = temp_dir("storage-import");
    let path = directory.join("benchmarkData.json");
    common::write_text(
        &path,
        r#"{"benchmarkDataVersion":3,"context":{"deviceModel":"Pixel 9","apiLevel":35,"abi":"arm64-v8a","fingerprint":"fp","physicalDevice":true,"build":{"variant":"benchmark"}},"benchmarkLibraryVersion":"1.4","benchmarks":[{"name":"dev.Example.scroll","packageName":"dev.app","compilationMode":"FULL","startupMode":"WARM","scenario":"SCROLL","metrics":{"frameDurationMs":{"unit":"ms","runs":[10,11,12]}}}]}"#,
    );
    let benchmark_run = BenchmarkRun::open(&path, MAX_IMPORT_BYTES).unwrap();
    let store = BenchmarkStore::open(&directory.join("db.sqlite")).unwrap();
    store.save(&benchmark_run).unwrap();
    let stored = store.list_recent(10).unwrap().remove(0);
    assert_eq!(stored.id, benchmark_run.id);
    assert_eq!(stored.source_path, path.to_string_lossy());
    assert_eq!(stored.device_model.as_deref(), Some("Pixel 9"));
    assert_eq!(stored.case_count, 1);
}

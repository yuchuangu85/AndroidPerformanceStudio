//! Port of `BenchmarkExportersTest.kt`: JSON/CSV/Markdown/JUnit/SARIF/PDF/report index.
mod common;

use aps_benchmark::{
    BenchmarkReportIndex, Classification, CompatibilityIssue, Confidence, GateDecision, GateReason,
    RegressionReport, ReleaseGateResult, render_pdf_lines, write_csv, write_json, write_junit,
    write_markdown, write_pdf, write_sarif,
};
use common::{comparison, temp_dir};
use std::path::PathBuf;

fn report() -> RegressionReport {
    let mut item = comparison(Classification::Regressed, Confidence::Exact, "timeMs");
    item.reasons = vec!["Run environments are incompatible: device.apiLevel".to_string()];
    RegressionReport {
        baseline_run_id: "b".to_string(),
        current_run_id: "c".to_string(),
        created_at: "2026-11-08T00:00:00Z".to_string(),
        comparisons: vec![item],
        compatibility_issues: vec![CompatibilityIssue {
            field: "device.apiLevel".to_string(),
            baseline: Some("34".to_string()),
            current: Some("35".to_string()),
            hard: true,
        }],
        policy_version: aps_benchmark::POLICY_VERSION,
    }
}

fn gate() -> ReleaseGateResult {
    ReleaseGateResult {
        decision: GateDecision::Inconclusive,
        policy_version: "2026.1".to_string(),
        blocking_comparisons: Vec::new(),
        reasons: vec![GateReason::IncompatibleEvidence],
    }
}

fn read(path: &PathBuf) -> String {
    std::fs::read_to_string(path).unwrap()
}

#[test]
fn exports_junit_and_sarif_regressions() {
    let directory = temp_dir("exporters-junit");
    let junit = directory.join("out.xml");
    let sarif = directory.join("out.sarif");
    write_junit(&report(), &junit).unwrap();
    write_sarif(&report(), &sarif).unwrap();
    assert!(read(&junit).contains("failure"));
    assert!(read(&sarif).contains("benchmark-regression"));
    let parsed: serde_json::Value = serde_json::from_str(&read(&sarif)).unwrap();
    assert_eq!(parsed["version"], "2.1.0");
}

#[test]
fn release_gate_policy_version_and_compatibility_travel_with_the_release_exports() {
    let directory = temp_dir("exporters-release");
    let csv = directory.join("release.csv");
    let json = directory.join("release.json");
    write_csv(&report(), Some(&gate()), &csv).unwrap();
    write_json(&report(), Some(&gate()), &json).unwrap();

    let csv_text = read(&csv);
    assert!(csv_text.contains("blocking,policy_version"));
    assert!(csv_text.contains("2026.1"));
    assert!(csv_text.contains("false"));

    let json_text = read(&json);
    assert!(json_text.contains("releaseGate"));
    assert!(json_text.contains("INCONCLUSIVE"));
    assert!(json_text.contains("compatibilityIssues"));
    assert!(json_text.contains("device.apiLevel"));
    assert!(json_text.contains("2026.1"));
    let parsed: serde_json::Value = serde_json::from_str(&json_text).unwrap();
    assert_eq!(parsed["schemaVersion"], 2);
    assert_eq!(parsed["releaseGate"]["decision"], "INCONCLUSIVE");
    assert_eq!(parsed["releaseGate"]["blockingComparisonCount"], 0);
    assert_eq!(parsed["comparisons"][0]["blocking"], false);
}

#[test]
fn json_without_a_gate_is_the_schema_one_report_view() {
    let directory = temp_dir("exporters-schema1");
    let json = directory.join("report.json");
    write_json(&report(), None, &json).unwrap();
    let parsed: serde_json::Value = serde_json::from_str(&read(&json)).unwrap();
    assert_eq!(parsed["schemaVersion"], 1);
    assert!(parsed.get("releaseGate").is_none());
    assert_eq!(parsed["regressionCount"], 1);
    assert_eq!(parsed["comparisons"][0]["classification"], "REGRESSED");
}

#[test]
fn csv_quotes_and_escapes_like_compose() {
    let directory = temp_dir("exporters-csv");
    let csv = directory.join("report.csv");
    let mut item = comparison(Classification::Regressed, Confidence::Exact, "timeMs");
    item.case_identity = "a,b\"c".to_string();
    let mut report = report();
    report.comparisons = vec![item];
    write_csv(&report, None, &csv).unwrap();
    let text = read(&csv);
    assert!(text.contains("\"a,b\"\"c\""), "{text}");
    assert!(text.starts_with("case,metric,unit,baseline,current"));
}

#[test]
fn markdown_table_carries_the_comparison_rows() {
    let directory = temp_dir("exporters-markdown");
    let markdown = directory.join("report.md");
    write_markdown(&report(), &markdown).unwrap();
    let text = read(&markdown);
    assert!(text.contains("# Benchmark Regression Report"));
    assert!(text.contains("Regressions: **1**"));
    assert!(text.contains("| Case | Metric | Baseline | Current | Delta | Result |"));
    assert!(text.contains("Bench#test"));
    assert!(text.contains("REGRESSED"));
}

#[test]
fn the_pdf_export_is_a_real_pdf_that_carries_the_gate_decision() {
    let directory = temp_dir("exporters-pdf");
    let output = directory.join("release.pdf");
    write_pdf(&report(), &gate(), &output).unwrap();

    let bytes = std::fs::read(&output).unwrap();
    let text = String::from_utf8(bytes).unwrap();
    assert!(text.starts_with("%PDF-1.4"));
    assert!(text.contains("Gate decision:  INCONCLUSIVE"));
    assert!(text.contains("policy v2026.1"));
    assert!(text.contains("Bench#test"));
    assert!(text.contains("%%EOF"));
    assert!(text.contains("xref"));
    assert!(text.contains("startxref"));

    let lines = render_pdf_lines(&report(), &gate());
    assert!(
        lines
            .iter()
            .any(|line| line.contains("Gate decision:  INCONCLUSIVE"))
    );
    assert!(lines.iter().any(|line| line.contains("device.apiLevel")));
    assert!(lines.iter().any(|line| line.contains("[hard]")));
    assert!(lines.iter().any(|line| line.contains("Baseline run:   b")));
    assert!(
        lines
            .iter()
            .any(|line| line.contains("Comparisons:    1   Regressions: 1   Blocking: 0"))
    );
}

#[test]
fn pdf_paginates_long_reports() {
    let directory = temp_dir("exporters-pdf-pages");
    let output = directory.join("long.pdf");
    let mut report = report();
    report.comparisons = (0..130)
        .map(|index| {
            let mut item = comparison(Classification::Stable, Confidence::Exact, "timeMs");
            item.case_identity = format!("Bench#{index}");
            item
        })
        .collect();
    write_pdf(&report, &gate(), &output).unwrap();
    let text = String::from_utf8(std::fs::read(&output).unwrap()).unwrap();
    assert!(text.contains("/Count 3"), "expected three PDF pages");
    assert!(text.ends_with("%%EOF\n"));
}

#[test]
fn the_report_index_catalogues_exported_artifacts_without_owning_them() {
    let directory = temp_dir("exporters-index");
    let artifact = directory.join("release.pdf");
    std::fs::write(&artifact, "pdf").unwrap();
    let index_path = directory.join("index.json");
    let store = BenchmarkReportIndex::new(&index_path);

    let entries = store.record(&artifact, "v2 vs v1", "PDF").unwrap();
    assert_eq!(entries.len(), 1);
    assert_eq!(entries[0].path, artifact.to_string_lossy());
    assert!(entries[0].size_bytes > 0);
    assert_eq!(entries[0].format, "PDF");
    assert!(artifact.is_file());
    assert!(index_path.is_file());

    // Re-recording the same path replaces the entry instead of duplicating it.
    assert_eq!(store.record(&artifact, "v2 vs v1", "PDF").unwrap().len(), 1);
    assert_eq!(BenchmarkReportIndex::new(&index_path).load().len(), 1);
}

#[test]
fn the_report_index_is_bounded_and_tolerates_missing_or_malformed_files() {
    let directory = temp_dir("exporters-index-bounds");
    let missing = BenchmarkReportIndex::new(directory.join("absent.json"));
    assert!(missing.load().is_empty());
    let malformed_path = directory.join("malformed.json");
    std::fs::write(&malformed_path, "not json").unwrap();
    assert!(BenchmarkReportIndex::new(&malformed_path).load().is_empty());

    let index = BenchmarkReportIndex::with_max_entries(directory.join("index.json"), 2);
    for name in ["a", "b", "c"] {
        let artifact = directory.join(format!("{name}.json"));
        std::fs::write(&artifact, name).unwrap();
        index.record(&artifact, name, "JSON").unwrap();
    }
    let loaded = BenchmarkReportIndex::new(directory.join("index.json")).load();
    assert_eq!(loaded.len(), 2);
    assert!(loaded.iter().any(|entry| entry.path.ends_with("c.json")));
    assert!(loaded.iter().any(|entry| entry.path.ends_with("b.json")));
}

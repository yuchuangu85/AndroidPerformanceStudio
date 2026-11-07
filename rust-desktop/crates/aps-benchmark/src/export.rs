//! Report exporters (port of `BenchmarkReportExporter.kt` / `BenchmarkReportIndexStore.kt`).
//!
//! A report is only reproducible together with the policy it used, so the
//! release exports carry the gate decision, the policy version and the
//! compatibility issues next to the comparison rows. JSON/CSV are bounded
//! report views, never a lossless input archive.
use std::path::Path;

use serde_json::{Map, Value};

use crate::compare::{Classification, MetricComparison, RegressionReport, ReleaseGateResult};

pub use crate::pdf::{render_pdf_lines, write_pdf};

/// JSON report. With a gate this is the schema-2 release export, otherwise the schema-1 report view.
pub fn write_json(
    report: &RegressionReport,
    gate: Option<&ReleaseGateResult>,
    path: &Path,
) -> Result<(), String> {
    let mut root = Map::new();
    match gate {
        None => {
            root.insert("schemaVersion".to_string(), Value::from(1));
            root.insert(
                "baselineRunId".to_string(),
                Value::String(report.baseline_run_id.clone()),
            );
            root.insert(
                "currentRunId".to_string(),
                Value::String(report.current_run_id.clone()),
            );
            root.insert(
                "createdAt".to_string(),
                Value::String(report.created_at.clone()),
            );
            root.insert(
                "regressionCount".to_string(),
                Value::from(regression_count(report)),
            );
            root.insert(
                "comparisons".to_string(),
                Value::Array(
                    report
                        .comparisons
                        .iter()
                        .map(|comparison| comparison_json(comparison, None, false))
                        .collect(),
                ),
            );
        }
        Some(gate) => {
            root.insert("schemaVersion".to_string(), Value::from(2));
            root.insert(
                "baselineRunId".to_string(),
                Value::String(report.baseline_run_id.clone()),
            );
            root.insert(
                "currentRunId".to_string(),
                Value::String(report.current_run_id.clone()),
            );
            root.insert(
                "createdAt".to_string(),
                Value::String(report.created_at.clone()),
            );
            root.insert(
                "regressionCount".to_string(),
                Value::from(regression_count(report)),
            );
            let mut release_gate = Map::new();
            release_gate.insert(
                "decision".to_string(),
                Value::String(gate.decision.label().to_string()),
            );
            release_gate.insert(
                "policyVersion".to_string(),
                Value::String(gate.policy_version.clone()),
            );
            release_gate.insert(
                "blockingComparisonCount".to_string(),
                Value::from(gate.blocking_comparisons.len()),
            );
            release_gate.insert(
                "reasons".to_string(),
                Value::Array(
                    gate.reasons
                        .iter()
                        .map(|reason| Value::String(reason.label().to_string()))
                        .collect(),
                ),
            );
            root.insert("releaseGate".to_string(), Value::Object(release_gate));
            root.insert(
                "compatibilityIssues".to_string(),
                Value::Array(
                    report
                        .compatibility_issues
                        .iter()
                        .map(|issue| {
                            let mut object = Map::new();
                            object.insert("field".to_string(), Value::String(issue.field.clone()));
                            if let Some(baseline) = &issue.baseline {
                                object.insert(
                                    "baseline".to_string(),
                                    Value::String(baseline.clone()),
                                );
                            }
                            if let Some(current) = &issue.current {
                                object
                                    .insert("current".to_string(), Value::String(current.clone()));
                            }
                            object.insert("hard".to_string(), Value::Bool(issue.hard));
                            Value::Object(object)
                        })
                        .collect(),
                ),
            );
            root.insert(
                "comparisons".to_string(),
                Value::Array(
                    report
                        .comparisons
                        .iter()
                        .map(|comparison| comparison_json(comparison, Some(gate), true))
                        .collect(),
                ),
            );
        }
    }
    let text = serde_json::to_string_pretty(&Value::Object(root))
        .map_err(|error| format!("Cannot encode benchmark report JSON: {error}"))?;
    write_text(path, &text)
}

/// CSV with the gate columns when a gate is supplied.
pub fn write_csv(
    report: &RegressionReport,
    gate: Option<&ReleaseGateResult>,
    path: &Path,
) -> Result<(), String> {
    let mut text = String::new();
    match gate {
        Some(gate) => {
            text.push_str(
                "case,metric,unit,baseline,current,absolute_delta,relative_delta_percent,classification,confidence,blocking,policy_version\n",
            );
            for comparison in &report.comparisons {
                let row = [
                    comparison.case_identity.clone(),
                    comparison.metric_name.clone(),
                    comparison.unit.clone(),
                    number_text(comparison.baseline_value),
                    number_text(comparison.current_value),
                    number_text(comparison.absolute_delta),
                    number_text(comparison.relative_delta_percent),
                    comparison.classification.label().to_string(),
                    comparison.confidence.label().to_string(),
                    gate.blocking_comparisons.contains(comparison).to_string(),
                    gate.policy_version.clone(),
                ];
                text.push_str(&csv_row(&row));
                text.push('\n');
            }
        }
        None => {
            text.push_str(
                "case,metric,unit,baseline,current,absolute_delta,relative_delta_percent,classification,confidence\n",
            );
            for comparison in &report.comparisons {
                let row = [
                    comparison.case_identity.clone(),
                    comparison.metric_name.clone(),
                    comparison.unit.clone(),
                    number_text(comparison.baseline_value),
                    number_text(comparison.current_value),
                    number_text(comparison.absolute_delta),
                    number_text(comparison.relative_delta_percent),
                    comparison.classification.label().to_string(),
                    comparison.confidence.label().to_string(),
                ];
                text.push_str(&csv_row(&row));
                text.push('\n');
            }
        }
    }
    write_text(path, &text)
}

pub fn write_markdown(report: &RegressionReport, path: &Path) -> Result<(), String> {
    let mut text = String::new();
    text.push_str("# Benchmark Regression Report\n\n");
    text.push_str(&format!(
        "Regressions: **{}**\n\n",
        regression_count(report)
    ));
    text.push_str("| Case | Metric | Baseline | Current | Delta | Result |\n");
    text.push_str("| --- | --- | ---: | ---: | ---: | --- |\n");
    for comparison in &report.comparisons {
        text.push_str(&format!(
            "| {} | {} ({}) | {} | {} | {} | {} |\n",
            comparison.case_identity,
            comparison.metric_name,
            comparison.unit,
            value_text(comparison.baseline_value),
            value_text(comparison.current_value),
            delta_text(comparison.relative_delta_percent),
            comparison.classification.label(),
        ));
    }
    write_text(path, &text)
}

pub fn write_junit(report: &RegressionReport, path: &Path) -> Result<(), String> {
    let mut text = String::new();
    text.push_str(&format!(
        "<testsuite name=\"AndroidPerformanceStudio Benchmark\" tests=\"{}\" failures=\"{}\">",
        report.comparisons.len(),
        regression_count(report)
    ));
    for comparison in &report.comparisons {
        text.push_str(&format!(
            "<testcase classname=\"{}\" name=\"{}\">",
            xml(&comparison.case_identity),
            xml(&comparison.metric_name)
        ));
        if comparison.classification == Classification::Regressed {
            text.push_str(&format!(
                "<failure message=\"Regression\">{}</failure>",
                xml(&format!("{comparison:?}"))
            ));
        }
        text.push_str("</testcase>");
    }
    text.push_str("</testsuite>");
    write_text(path, &text)
}

pub fn write_sarif(report: &RegressionReport, path: &Path) -> Result<(), String> {
    let results: Vec<Value> = report
        .comparisons
        .iter()
        .filter(|comparison| comparison.classification == Classification::Regressed)
        .map(|comparison| {
            serde_json::json!({
                "ruleId": "benchmark-regression",
                "level": "error",
                "message": {
                    "text": format!(
                        "{} {} regressed by {}%",
                        comparison.case_identity,
                        comparison.metric_name,
                        comparison
                            .relative_delta_percent
                            .map(|value| format!("{value:?}"))
                            .unwrap_or_else(|| "n/a".to_string())
                    )
                }
            })
        })
        .collect();
    let sarif = serde_json::json!({
        "version": "2.1.0",
        "runs": [{
            "tool": { "driver": { "name": "AndroidPerformanceStudio Benchmark Regression" } },
            "results": results,
        }]
    });
    let text = serde_json::to_string_pretty(&sarif)
        .map_err(|error| format!("Cannot encode SARIF report: {error}"))?;
    write_text(path, &text)
}

fn comparison_json(
    comparison: &MetricComparison,
    gate: Option<&ReleaseGateResult>,
    include_gate_fields: bool,
) -> Value {
    let mut object = Map::new();
    object.insert(
        "case".to_string(),
        Value::String(comparison.case_identity.clone()),
    );
    object.insert(
        "metric".to_string(),
        Value::String(comparison.metric_name.clone()),
    );
    object.insert("unit".to_string(), Value::String(comparison.unit.clone()));
    if let Some(value) = comparison.baseline_value {
        object.insert("baseline".to_string(), serde_json::json!(value));
    }
    if let Some(value) = comparison.current_value {
        object.insert("current".to_string(), serde_json::json!(value));
    }
    if let Some(value) = comparison.absolute_delta {
        object.insert("absoluteDelta".to_string(), serde_json::json!(value));
    }
    if let Some(value) = comparison.relative_delta_percent {
        object.insert("relativeDeltaPercent".to_string(), serde_json::json!(value));
    }
    object.insert(
        "classification".to_string(),
        Value::String(comparison.classification.label().to_string()),
    );
    object.insert(
        "confidence".to_string(),
        Value::String(comparison.confidence.label().to_string()),
    );
    if include_gate_fields {
        object.insert(
            "blocking".to_string(),
            Value::Bool(gate.is_some_and(|gate| gate.blocking_comparisons.contains(comparison))),
        );
        object.insert(
            "reasons".to_string(),
            Value::Array(
                comparison
                    .reasons
                    .iter()
                    .map(|reason| Value::String(reason.clone()))
                    .collect(),
            ),
        );
    }
    Value::Object(object)
}

fn regression_count(report: &RegressionReport) -> usize {
    report
        .comparisons
        .iter()
        .filter(|comparison| comparison.classification == Classification::Regressed)
        .count()
}

fn csv_row(values: &[String]) -> String {
    values
        .iter()
        .map(|value| format!("\"{}\"", value.replace('"', "\"\"")))
        .collect::<Vec<_>>()
        .join(",")
}

fn number_text(value: Option<f64>) -> String {
    value.map(|value| format!("{value:?}")).unwrap_or_default()
}

fn value_text(value: Option<f64>) -> String {
    value
        .map(|value| format!("{value:?}"))
        .unwrap_or_else(|| "\u{2014}".to_string())
}

fn delta_text(value: Option<f64>) -> String {
    value
        .map(|value| format!("{value:.2}%"))
        .unwrap_or_else(|| "\u{2014}".to_string())
}

fn xml(value: &str) -> String {
    value
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('"', "&quot;")
}

fn write_text(path: &Path, content: &str) -> Result<(), String> {
    if let Some(parent) = path.parent()
        && !parent.as_os_str().is_empty()
    {
        std::fs::create_dir_all(parent)
            .map_err(|_| format!("Cannot create report directory: {}", parent.display()))?;
    }
    std::fs::write(path, content).map_err(|error| format!("Cannot write report: {error}"))
}

//! Bounded Benchmark AI context.
//!
//! Port of Compose `BenchmarkAiContext.kt` with the shared `boundedAiContext` policy: only summary
//! counters and at most eight comparison rows leave this module. Case identities, metric names and
//! units are the only arbitrary strings; paths, raw samples and gate policies never do. The payload
//! is bounded JSON (8 KiB) and the evidence id binds the profiler name plus those payload bytes with a
//! SHA-256 `SourceDigest`, so a different selection cannot reuse an approval.

use super::model::BenchmarkReportsState;
use aps_ai::{AnalysisRequest, Evidence};
use aps_benchmark::MetricComparison;
use aps_contracts::SourceDigest;
use aps_json::{Limits, Map, Number, Value};

const MAX_INCLUDED_COMPARISONS: usize = 8;
const MAX_PAYLOAD_BYTES: u64 = 8 * 1024;
const EVIDENCE_SUMMARY: &str = "Comparison units, compatibility and confidence remain explicit";

fn payload_limits() -> Limits {
    Limits {
        max_bytes: MAX_PAYLOAD_BYTES,
        max_depth: 8,
        max_nodes: 4_000,
        max_string_bytes: 2_048,
        max_collection_items: 256,
    }
}

/// Compose `benchmarkAiContext`: a stale selection fails closed instead of silently falling back
/// to a summary context, and no report means no evidence.
pub(in crate::benchmark) fn analysis_request(
    state: &BenchmarkReportsState,
) -> Result<AnalysisRequest, String> {
    if state.busy {
        return Err("Benchmark comparison is still running".to_owned());
    }
    let Some(report) = state.report.as_deref() else {
        return Err("Compare two benchmark runs before AI analysis".to_owned());
    };
    let selected = match state.selected.as_ref() {
        Some(_) => Some(
            state
                .selected_comparison()
                .ok_or_else(|| "The selected comparison is no longer available".to_owned())?,
        ),
        None => None,
    };
    let visible = state.visible_comparisons();
    let rows: Vec<&MetricComparison> = match selected {
        Some(comparison) => vec![comparison],
        None => visible
            .iter()
            .take(MAX_INCLUDED_COMPARISONS)
            .copied()
            .collect(),
    };
    let omitted = if selected.is_some() {
        0
    } else {
        visible.len().saturating_sub(rows.len())
    };
    let payload = payload(state, report, &visible, &rows, omitted);
    let payload_bytes = aps_json::to_vec(&payload, payload_limits())
        .map_err(|_| "Selected benchmark evidence exceeds the AI context budget")?;
    let mut identity = Vec::with_capacity(payload_bytes.len() + 16);
    identity.extend_from_slice(b"benchmark");
    identity.push(0);
    identity.extend_from_slice(&payload_bytes);
    let digest = SourceDigest::hash_reader(identity.as_slice(), identity.len() as u64)
        .map_err(|_| "Cannot bind the selected benchmark evidence")?;
    let kind = if selected.is_some() {
        "benchmark-selection"
    } else {
        "benchmark-summary"
    };
    Ok(AnalysisRequest {
        session_id: "benchmark-view".to_owned(),
        run_id: kind.to_owned(),
        generation: 1,
        profiler: "benchmark".to_owned(),
        scope: "Benchmark comparison evidence".to_owned(),
        evidence: vec![Evidence {
            id: format!("{kind}:{}", digest.to_hex()),
            kind: kind.to_owned(),
            summary: EVIDENCE_SUMMARY.to_owned(),
            payload,
        }],
        candidates: Vec::new(),
        user_question: None,
    })
}

fn payload(
    state: &BenchmarkReportsState,
    report: &aps_benchmark::RegressionReport,
    visible: &[&MetricComparison],
    rows: &[&MetricComparison],
    omitted: usize,
) -> Value {
    let mut object = Map::new();
    object.insert(
        "totalComparisons".into(),
        Value::from(report.comparisons.len() as u64),
    );
    object.insert(
        "visibleComparisons".into(),
        Value::from(visible.len() as u64),
    );
    object.insert("includedComparisons".into(), Value::from(rows.len() as u64));
    object.insert(
        "omittedVisibleComparisons".into(),
        Value::from(omitted as u64),
    );
    object.insert(
        "compatibilityIssueCount".into(),
        Value::from(report.compatibility_issues.len() as u64),
    );
    object.insert(
        "queryFilterActive".into(),
        Value::Bool(!state.query.trim().is_empty()),
    );
    object.insert(
        "classificationFilter".into(),
        state.classification_filter.map_or(Value::Null, |filter| {
            Value::String(filter.label().to_owned())
        }),
    );
    object.insert(
        "comparisons".into(),
        Value::Array(rows.iter().map(|row| comparison_value(row)).collect()),
    );
    Value::Object(object)
}

fn comparison_value(comparison: &MetricComparison) -> Value {
    let mut object = Map::new();
    object.insert(
        "comparisonRef".into(),
        Value::String(comparison_ref(
            &comparison.case_identity,
            &comparison.metric_name,
        )),
    );
    object.insert(
        "metric".into(),
        Value::String(comparison.metric_name.clone()),
    );
    object.insert("unit".into(), Value::String(comparison.unit.clone()));
    object.insert("baseline".into(), number(comparison.baseline_value));
    object.insert("current".into(), number(comparison.current_value));
    object.insert("absoluteDelta".into(), number(comparison.absolute_delta));
    object.insert(
        "relativeDeltaPercent".into(),
        number(comparison.relative_delta_percent),
    );
    object.insert(
        "classification".into(),
        Value::String(comparison.classification.label().to_owned()),
    );
    object.insert(
        "confidence".into(),
        Value::String(comparison.confidence.label().to_owned()),
    );
    object.insert(
        "reasonCount".into(),
        Value::from(comparison.reasons.len() as u64),
    );
    Value::Object(object)
}

fn number(value: Option<f64>) -> Value {
    value
        .and_then(Number::from_f64)
        .map_or(Value::Null, Value::Number)
}

/// Compose `uiAiEvidenceId("benchmark-metric", caseIdentity, metricName)`: SHA-256 over the
/// NUL-joined parts, rendered as a stable row reference.
fn comparison_ref(case_identity: &str, metric_name: &str) -> String {
    let mut identity = Vec::with_capacity(case_identity.len() + metric_name.len() + 1);
    identity.extend_from_slice(case_identity.as_bytes());
    identity.push(0);
    identity.extend_from_slice(metric_name.as_bytes());
    match SourceDigest::hash_reader(identity.as_slice(), identity.len() as u64) {
        Ok(digest) => format!("benchmark-metric:{}", digest.to_hex()),
        Err(_) => "benchmark-metric:unavailable".to_owned(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::benchmark::model::{self, BenchmarkReportsState};
    use aps_benchmark::{Classification, Confidence, RegressionReport};
    use std::sync::Arc;

    fn comparison(index: usize, classification: Classification) -> MetricComparison {
        MetricComparison {
            case_identity: format!("dev.Example#case{index}"),
            metric_name: format!("metric{index}"),
            unit: "ms".into(),
            baseline_value: Some(10.0),
            current_value: Some(11.0),
            absolute_delta: Some(1.0),
            relative_delta_percent: Some(10.0),
            classification,
            confidence: Confidence::Exact,
            reasons: Vec::new(),
            confidence_interval: None,
            practical_threshold: None,
        }
    }

    fn state_with(comparisons: Vec<MetricComparison>) -> BenchmarkReportsState {
        let report = RegressionReport {
            baseline_run_id: "baseline".into(),
            current_run_id: "current".into(),
            created_at: "2026-11-08T00:00:00Z".into(),
            comparisons,
            compatibility_issues: Vec::new(),
            policy_version: aps_benchmark::POLICY_VERSION,
        };
        BenchmarkReportsState {
            report: Some(Arc::new(report)),
            ..Default::default()
        }
    }

    fn payload_text(request: &AnalysisRequest) -> String {
        String::from_utf8(
            aps_json::to_vec(&request.evidence[0].payload, Limits::default()).unwrap(),
        )
        .unwrap()
    }

    #[::core::prelude::v1::test]
    fn summary_includes_eight_rows_and_explicit_omissions_within_budget() {
        let comparisons = (0..10)
            .map(|index| comparison(index, Classification::Stable))
            .collect();
        let request = analysis_request(&state_with(comparisons)).unwrap();
        let payload = &request.evidence[0].payload;
        assert_eq!(payload["totalComparisons"].as_u64(), Some(10));
        assert_eq!(payload["visibleComparisons"].as_u64(), Some(10));
        assert_eq!(payload["includedComparisons"].as_u64(), Some(8));
        assert_eq!(payload["omittedVisibleComparisons"].as_u64(), Some(2));
        assert_eq!(payload["comparisons"].as_array().map(Vec::len), Some(8));
        assert_eq!(request.evidence[0].kind, "benchmark-summary");
        assert!(request.evidence[0].id.starts_with("benchmark-summary:"));
        assert!(payload_text(&request).len() <= 8 * 1024);
    }

    #[::core::prelude::v1::test]
    fn selection_includes_only_the_selected_row_and_keeps_its_identity() {
        let comparisons: Vec<MetricComparison> = (0..10)
            .map(|index| comparison(index, Classification::Regressed))
            .collect();
        let mut state = state_with(comparisons);
        state.selected = Some(model::comparison_key(&state.comparisons()[3]));
        let request = analysis_request(&state).unwrap();
        let payload = &request.evidence[0].payload;
        assert_eq!(payload["includedComparisons"].as_u64(), Some(1));
        assert_eq!(payload["omittedVisibleComparisons"].as_u64(), Some(0));
        assert_eq!(
            payload["comparisons"][0]["metric"].as_str(),
            Some("metric3")
        );
        assert_eq!(
            payload["comparisons"][0]["classification"].as_str(),
            Some("REGRESSED")
        );
        assert_eq!(
            payload["comparisons"][0]["confidence"].as_str(),
            Some("EXACT")
        );
        assert_eq!(request.evidence[0].kind, "benchmark-selection");
        assert!(request.evidence[0].id.starts_with("benchmark-selection:"));
    }

    #[::core::prelude::v1::test]
    fn filters_and_missing_values_are_explicit_in_the_projection() {
        let mut comparisons: Vec<MetricComparison> = (0..3)
            .map(|index| comparison(index, Classification::Stable))
            .collect();
        comparisons[1].baseline_value = None;
        comparisons[1].current_value = None;
        comparisons[1].absolute_delta = None;
        comparisons[1].relative_delta_percent = None;
        let mut state = state_with(comparisons);
        state.query = "case1".into();
        state.classification_filter = Some(Classification::Stable);
        let request = analysis_request(&state).unwrap();
        let payload = &request.evidence[0].payload;
        assert_eq!(payload["queryFilterActive"].as_bool(), Some(true));
        assert_eq!(payload["classificationFilter"].as_str(), Some("STABLE"));
        assert_eq!(payload["visibleComparisons"].as_u64(), Some(1));
        assert!(payload["comparisons"][0]["baseline"].is_null());
        assert!(payload["comparisons"][0]["current"].is_null());
    }

    #[::core::prelude::v1::test]
    fn neither_an_empty_state_nor_a_busy_compare_publishes_evidence() {
        assert!(analysis_request(&BenchmarkReportsState::default()).is_err());
        let mut state = state_with(vec![comparison(0, Classification::Stable)]);
        state.busy = true;
        assert!(analysis_request(&state).is_err());
    }
}

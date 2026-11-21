//! Shared, bounded and privacy-minimized Benchmark result paging. Both hosts
//! hand in exact JSON byte slices; no local/remote I/O happens in this module.
use aps_benchmark::{
    BenchmarkRun, Classification, GateDecision, Metric, MetricComparison, MetricDirection,
    RegressionPolicy, ReleaseGatePolicy, compare, evaluate_gate,
};
use aps_contracts::SourceDigest;
use serde_json::{Value, json};
use std::collections::BTreeMap;

pub(crate) const MAX_LIMIT: usize = 16;
pub(crate) const DEFAULT_LIMIT: usize = 8;
const MAX_METRICS_PER_RUN: usize = 512;
const MAX_BOOTSTRAP_DRAWS: usize = 10_000_000;

pub(crate) struct Comparison {
    baseline_digest: SourceDigest,
    current_digest: SourceDigest,
    decision: GateDecision,
    header: Value,
    current_summary: Value,
    items: Vec<Value>,
}

impl Comparison {
    pub(crate) fn from_bytes(
        baseline: &[u8],
        current: &[u8],
        threshold_percent: Option<f64>,
    ) -> Result<Self, String> {
        let mut policy = RegressionPolicy::default();
        if let Some(threshold) = threshold_percent {
            if !(0.0..=100.0).contains(&threshold) {
                return Err(
                    "E_BENCHMARK_POLICY: threshold percent must be between 0 and 100".into(),
                );
            }
            policy.relative_threshold_percent =
                Some(if threshold == 0.0 { 0.0 } else { threshold });
        }
        let baseline = load(
            baseline,
            "E_BENCHMARK_BASELINE: invalid or over-budget Benchmark JSON",
        )?;
        let current = load(
            current,
            "E_BENCHMARK_CURRENT: invalid or over-budget Benchmark JSON",
        )?;
        let baseline_digest = SourceDigest::from_hex(&baseline.id)?;
        let current_digest = SourceDigest::from_hex(&current.id)?;
        if baseline_digest == current_digest {
            return Err("E_BENCHMARK_PAIR: baseline and current are the same report bytes".into());
        }

        let before = metrics(&baseline)?;
        let after = metrics(&current)?;
        check_work(&before, &after, &policy)?;
        let mut report = compare(&baseline, &current, &policy).map_err(|_| "E_BENCHMARK_POLICY")?;
        // With extremely large imported numbers even finite input can overflow
        // a delta/threshold calculation; never let an infinite threshold
        // turn numerically invalid evidence into a passing row.
        for row in &mut report.comparisons {
            if matches!(
                row.classification,
                Classification::Stable | Classification::Improved | Classification::Regressed
            ) && nonfinite_derivation(row)
            {
                row.classification = Classification::Inconclusive;
            }
        }
        let gate = evaluate_gate(&report, &ReleaseGatePolicy::default());

        let baseline_only = before
            .keys()
            .filter(|key| !after.contains_key(*key))
            .count();
        let current_only = after
            .keys()
            .filter(|key| !before.contains_key(*key))
            .count();
        let baseline_skipped = skipped_cases(&baseline);
        let current_skipped = skipped_cases(&current);
        let mut gate_reasons: Vec<_> = gate.reasons.iter().map(|r| r.label()).collect();
        let mut decision = gate.decision;
        // Domain comparison walks only current metrics; a removed metric or a
        // discarded case must not become a passing headless gate by omission.
        // A material regression among the measured rows remains a FAIL.
        if baseline_only > 0 || baseline_skipped > 0 || current_skipped > 0 {
            if decision == GateDecision::Pass {
                decision = GateDecision::Inconclusive;
                gate_reasons.retain(|&reason| reason != "NO_BLOCKING_REGRESSION");
            }
            if baseline_only > 0 {
                gate_reasons.push("BASELINE_METRICS_MISSING_IN_CURRENT");
            }
            if baseline_skipped > 0 || current_skipped > 0 {
                gate_reasons.push("SKIPPED_BENCHMARK_CASES");
            }
        }

        let prefix = format!(
            "{baseline_digest}:{current_digest}:{}:{:x}",
            report.policy_version,
            policy
                .relative_threshold_percent
                .unwrap_or_default()
                .to_bits()
        );
        let pair_id = SourceDigest::hash_reader(prefix.as_bytes(), prefix.len() as u64)?;
        let mut counts = [0; 5];
        let mut items = Vec::with_capacity(report.comparisons.len());
        // Canonical order independent of the serde_json map backend used by
        // either host. Evidence IDs stay stable across limit and offset.
        report.comparisons.sort_by(|a, b| {
            (&a.case_identity, &a.metric_name).cmp(&(&b.case_identity, &b.metric_name))
        });
        for (index, row) in report.comparisons.iter().enumerate() {
            counts[classification_index(row.classification)] += 1;
            let key = (row.case_identity.clone(), row.metric_name.clone());
            let baseline_metric = before.get(&key).copied();
            let current_metric = after.get(&key).copied().ok_or("E_BENCHMARK_PROJECTION")?;
            let comparable = row.classification != Classification::Incompatible;
            let mut reasons = Vec::new();
            if baseline_metric.is_none() {
                reasons.push("BASELINE_METRIC_MISSING");
            }
            if baseline_metric.is_some_and(|metric| metric.unit != current_metric.unit) {
                reasons.push("UNITS_DIFFER");
            }
            if row.classification == Classification::Incompatible
                && !reasons.contains(&"UNITS_DIFFER")
            {
                reasons.push("INCOMPATIBLE_COHORT");
            } else if row.classification == Classification::Inconclusive && reasons.is_empty() {
                if current_metric.direction == MetricDirection::Unknown {
                    reasons.push("UNKNOWN_METRIC_DIRECTION");
                } else if current_metric.samples.len() < policy.minimum_sample_count
                    || baseline_metric
                        .is_some_and(|metric| metric.samples.len() < policy.minimum_sample_count)
                {
                    reasons.push("INSUFFICIENT_RAW_SAMPLES");
                } else if row.baseline_value.is_none_or(|value| value == 0.0) {
                    reasons.push("UNDEFINED_RELATIVE_THRESHOLD");
                } else if nonfinite_derivation(row) {
                    reasons.push("NONFINITE_DERIVED_VALUE");
                } else if row.confidence_interval.is_none() {
                    reasons.push("UNCERTAINTY_UNAVAILABLE");
                } else {
                    reasons.push("INTERVAL_OVERLAPS_THRESHOLD");
                }
            }
            items.push(json!({
                "id":format!("benchmark-comparison:{pair_id}:{index}"),
                "label":"Benchmark metric comparison",
                "value":{
                    "caseIdentity":public_label(&row.case_identity),"metricName":public_label(&row.metric_name),
                    "baselineUnit":baseline_metric.and_then(|metric| public_label(&metric.unit)),
                    "currentUnit":public_label(&row.unit),
                    "baselineValue":row.baseline_value,"currentValue":row.current_value,
                    "baselineSamples":baseline_metric.map(|metric| metric.samples.len()),
                    "currentSamples":current_metric.samples.len(),
                    "absoluteDelta":comparable.then_some(row.absolute_delta).flatten(),
                    "relativeDeltaPercent":comparable.then_some(row.relative_delta_percent).flatten(),
                    "classification":row.classification.label(),"confidence":row.confidence.label(),
                    "confidenceInterval":row.confidence_interval,"practicalThreshold":row.practical_threshold,
                    "metricDirection":current_metric.direction.label(),"reasons":reasons
                }
            }));
        }
        let hard = report
            .compatibility_issues
            .iter()
            .filter(|issue| issue.hard)
            .count();
        let header = json!({
            "schemaVersion":"aps-benchmark-comparison-v1",
            "baselineDigest":baseline_digest.to_hex(),"currentDigest":current_digest.to_hex(),
            "policy":{"version":report.policy_version,"relativeThresholdPercent":policy.relative_threshold_percent,
                "minimumSampleCount":policy.minimum_sample_count,"bootstrapIterations":policy.bootstrap_iterations,
                "confidenceLevel":policy.confidence_level,"requireSameDevice":policy.require_same_device,"gateVersion":gate.policy_version},
            "gate":{"decision":decision.label(),"reasons":gate_reasons},
            "counts":{"regressed":counts[0],"improved":counts[1],"stable":counts[2],"inconclusive":counts[3],
                "incompatible":counts[4],"baselineOnlyMetrics":baseline_only,"currentOnlyMetrics":current_only,
                "baselineSkippedCases":baseline_skipped,"currentSkippedCases":current_skipped,
                "hardCompatibilityIssues":hard,"softCompatibilityIssues":report.compatibility_issues.len()-hard}
        });
        Ok(Self {
            baseline_digest,
            current_digest,
            decision,
            header,
            current_summary: super::extra::benchmark_summary(&current),
            items,
        })
    }

    pub(crate) fn current_summary(&self) -> &Value {
        &self.current_summary
    }

    pub(crate) fn baseline_digest(&self) -> SourceDigest {
        self.baseline_digest
    }

    pub(crate) fn current_digest(&self) -> SourceDigest {
        self.current_digest
    }

    pub(crate) fn page(&self, offset: usize, limit: usize) -> Result<Value, &'static str> {
        let total = self.items.len();
        if !(1..=MAX_LIMIT).contains(&limit) || offset > total {
            return Err("invalid Benchmark comparison page");
        }
        let end = (offset + limit).min(total);
        let mut output = self.header.clone();
        output["items"] = json!(self.items[offset..end]);
        output["total"] = total.into();
        output["offset"] = offset.into();
        output["nextOffset"] = json!((end < total).then_some(end));
        Ok(output)
    }

    pub(crate) fn exit_code(&self) -> i32 {
        match self.decision {
            GateDecision::Pass => 0,
            GateDecision::Fail => 1,
            GateDecision::Inconclusive => 3,
        }
    }
}

fn load(bytes: &[u8], error: &'static str) -> Result<BenchmarkRun, String> {
    BenchmarkRun::parse_json(bytes).map_err(|_| error.to_string())
}

type Metrics<'a> = BTreeMap<(String, String), &'a Metric>;

fn metrics(run: &BenchmarkRun) -> Result<Metrics<'_>, String> {
    let mut result = BTreeMap::new();
    for case in &run.cases {
        let identity = case.identity();
        for metric in &case.metrics {
            if result.len() >= MAX_METRICS_PER_RUN {
                return Err("E_BENCHMARK_BUDGET: more than 512 metrics in one report".into());
            }
            result.insert((identity.clone(), metric.name.clone()), metric);
        }
    }
    Ok(result)
}

fn check_work(
    baseline: &Metrics<'_>,
    current: &Metrics<'_>,
    policy: &RegressionPolicy,
) -> Result<(), String> {
    let mut draws = 0usize;
    for (key, after) in current {
        let Some(before) = baseline.get(key) else {
            continue;
        };
        if before.samples.len() < policy.minimum_sample_count
            || after.samples.len() < policy.minimum_sample_count
            || before.unit != after.unit
            || after.direction == MetricDirection::Unknown
        {
            continue;
        }
        let work = (before.samples.len() + after.samples.len())
            .saturating_mul(policy.bootstrap_iterations);
        draws = draws.saturating_add(work);
        if draws > MAX_BOOTSTRAP_DRAWS {
            return Err("E_BENCHMARK_BUDGET: bootstrap work exceeds 10000000 draws".into());
        }
    }
    Ok(())
}

fn skipped_cases(run: &BenchmarkRun) -> usize {
    // The legacy parser distinguishes its own case-skip diagnostics from
    // top-level producer field warnings. Never send any warning text.
    run.warnings
        .iter()
        .filter(|warning| warning.starts_with("Case #"))
        .count()
}

fn classification_index(classification: Classification) -> usize {
    match classification {
        Classification::Regressed => 0,
        Classification::Improved => 1,
        Classification::Stable => 2,
        Classification::Inconclusive => 3,
        Classification::Incompatible => 4,
    }
}

fn nonfinite_derivation(row: &MetricComparison) -> bool {
    [
        row.absolute_delta,
        row.relative_delta_percent,
        row.practical_threshold,
    ]
    .into_iter()
    .flatten()
    .any(|value| !value.is_finite())
}

fn public_label(value: &str) -> Option<&str> {
    (!value.is_empty()
        && value.len() <= 128
        && value
            .bytes()
            .all(|b| b.is_ascii_alphanumeric() || b".#_ %()-[]".contains(&b)))
    .then_some(value)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixture(samples: &[u64], unit: &str) -> Value {
        json!({
            "benchmarkDataVersion": 1, "benchmarkLibraryVersion": "1.4",
            "context":{"deviceModel":"Pixel","apiLevel":35,"abi":"arm64-v8a"},
            "build":{"variant":"benchmark","gitBranch":"PRIVATE-branch"},
            "benchmarks":[{
                "name":"dev.Bench.startup","scenario":"startup","compilationMode":"none","startupMode":"cold",
                "metrics":{"timeToInitialDisplayMs":{"unit":unit,"runs":samples}}
            }]
        })
    }

    fn bind(baseline: &Value, current: &Value) -> Result<Comparison, String> {
        Comparison::from_bytes(
            baseline.to_string().as_bytes(),
            current.to_string().as_bytes(),
            None,
        )
    }

    #[test]
    fn missing_baseline_metric_and_skipped_cases_do_not_disappear_into_pass() {
        let before = fixture(&[100, 100, 100, 100, 100], "ms");
        let after = fixture(&[101, 101, 101, 101, 101], "ms");
        assert_eq!(bind(&before, &after).unwrap().exit_code(), 0);
        let mut before = before;
        before["benchmarks"][0]["metrics"]["timeToFullDisplayMs"] =
            json!({"unit":"ms","runs":[180, 180, 180, 180, 180]});
        let mut after = after;
        after["benchmarks"]
            .as_array_mut()
            .unwrap()
            .push(Value::Null);
        let bind = bind(&before, &after).unwrap();
        let page = bind.page(0, 1).unwrap();
        assert_eq!(bind.exit_code(), 3);
        assert_eq!(page["gate"]["decision"], "INCONCLUSIVE");
        assert_eq!(
            page["gate"]["reasons"],
            json!([
                "BASELINE_METRICS_MISSING_IN_CURRENT",
                "SKIPPED_BENCHMARK_CASES"
            ])
        );
        assert_eq!(page["counts"]["baselineOnlyMetrics"], 1);
        assert_eq!(page["counts"]["currentSkippedCases"], 1);
        assert_eq!(page["counts"]["stable"], 1);
        assert_eq!(page["items"][0]["value"]["classification"], "STABLE");
    }

    #[test]
    fn incompatible_units_remove_deltas_and_private_identifiers() {
        let mut before = fixture(&[100, 100, 100, 100, 100], "ns");
        let mut after = fixture(&[120, 120, 120, 120, 120], "https://PRIVATE-unit");
        for doc in [&mut before, &mut after] {
            doc["benchmarks"][0]["className"] = json!("PRIVATE/path");
        }
        let bind = bind(&before, &after).unwrap();
        let page = bind.page(0, 1).unwrap();
        let row = &page["items"][0]["value"];
        assert_eq!(row["baselineUnit"], "ns");
        assert_eq!(row["currentUnit"], Value::Null);
        assert_eq!(row["absoluteDelta"], Value::Null);
        assert_eq!(row["relativeDeltaPercent"], Value::Null);
        assert_eq!(row["reasons"], json!(["UNITS_DIFFER"]));
        assert_eq!(row["classification"], "INCOMPATIBLE");
        assert_eq!(bind.exit_code(), 3);
        assert!(!page.to_string().contains("PRIVATE"));
        assert_eq!(bind.page(1, 16).unwrap()["items"], json!([]));
        assert!(bind.page(2, 1).is_err());
        assert!(bind.page(0, 17).is_err());
    }

    #[test]
    fn finite_imports_cannot_fake_a_pass_via_an_overflowed_threshold() {
        let mut before = fixture(&[100, 100, 100, 100, 100], "ms");
        let mut after = before.clone();
        let large = 5.0e307;
        before["benchmarks"][0]["metrics"]["timeToInitialDisplayMs"]["runs"] =
            json!(vec![large; 5]);
        after["benchmarks"][0]["metrics"]["timeToInitialDisplayMs"]["runs"] =
            json!(vec![large * 0.99; 5]);
        let bind = bind(&before, &after).unwrap();
        let page = bind.page(0, 16).unwrap();
        assert_eq!(bind.exit_code(), 3);
        assert_eq!(
            page["items"][0]["value"]["reasons"],
            json!(["NONFINITE_DERIVED_VALUE"])
        );
        assert_eq!(page["items"][0]["value"]["practicalThreshold"], Value::Null);
    }

    #[test]
    fn identical_bytes_and_over_budget_pair_are_rejected() {
        let before = fixture(&[100, 100, 100, 100, 100], "ms");
        assert!(
            bind(&before, &before)
                .err()
                .unwrap()
                .contains("E_BENCHMARK_PAIR")
        );
        // Three individually supported rows together exceed the aggregate work
        // budget; this test must reject before entering the BCa loop.
        let make = |sample: u64| {
            let mut doc = fixture(&vec![sample; 1000], "ms");
            for name in ["timeToFullDisplayMs", "frameDurationCpuMs"] {
                doc["benchmarks"][0]["metrics"][name] =
                    json!({"unit":"ms","runs":vec![sample; 1000]});
            }
            doc
        };
        assert!(
            bind(&make(100), &make(120))
                .err()
                .unwrap()
                .contains("E_BENCHMARK_BUDGET")
        );
    }
}

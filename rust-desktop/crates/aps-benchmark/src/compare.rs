//! Compose-shaped compatibility report on top of the Rust BCa analyzer contract.
//!
//! Compatibility issues and the comparison row shape are ports of
//! `RegressionAnalyzer.kt` / `ReleaseGateEvaluator.kt`. Classification still uses
//! the Rust two-sample BCa interval plus an explicit practical threshold: a
//! baseline-only MAD, a p-value or a summary median alone can never pass the gate.
use std::collections::{BTreeMap, HashMap};

use crate::model::{BenchmarkCase, BenchmarkRun, Confidence, Metric, MetricDirection};
use crate::uncertainty;
use crate::util::now_rfc3339;

/// Version exported with every report so a decision can be reproduced after the analyzer changes.
pub const POLICY_VERSION: &str = "aps-benchmark-bca-median-v1";

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum Classification {
    Regressed,
    Improved,
    Stable,
    Inconclusive,
    Incompatible,
}

impl Classification {
    pub fn label(self) -> &'static str {
        match self {
            Self::Regressed => "REGRESSED",
            Self::Improved => "IMPROVED",
            Self::Stable => "STABLE",
            Self::Inconclusive => "INCONCLUSIVE",
            Self::Incompatible => "INCOMPATIBLE",
        }
    }
}

/// Structured compatibility difference between the baseline and current runs.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CompatibilityIssue {
    pub field: String,
    pub baseline: Option<String>,
    pub current: Option<String>,
    /// A hard issue makes every comparison of the affected cohort incompatible.
    pub hard: bool,
}

#[derive(Debug, Clone, PartialEq)]
pub struct MetricComparison {
    pub case_identity: String,
    pub metric_name: String,
    pub unit: String,
    pub baseline_value: Option<f64>,
    pub current_value: Option<f64>,
    pub absolute_delta: Option<f64>,
    pub relative_delta_percent: Option<f64>,
    pub classification: Classification,
    pub confidence: Confidence,
    pub reasons: Vec<String>,
    /// Rust analyzer evidence retained alongside the Compose-shaped row.
    pub confidence_interval: Option<(f64, f64)>,
    pub practical_threshold: Option<f64>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct RegressionReport {
    pub baseline_run_id: String,
    pub current_run_id: String,
    /// RFC3339 UTC instant, sortable like the imported run timestamps.
    pub created_at: String,
    pub comparisons: Vec<MetricComparison>,
    pub compatibility_issues: Vec<CompatibilityIssue>,
    pub policy_version: &'static str,
}

#[derive(Debug, Clone, PartialEq)]
pub struct RegressionPolicy {
    pub relative_threshold_percent: Option<f64>,
    pub absolute_threshold: Option<f64>,
    pub minimum_sample_count: usize,
    pub bootstrap_iterations: usize,
    pub confidence_level: f64,
    /// Explicit semantic configuration, never guess an unknown metric direction.
    pub directions: BTreeMap<String, MetricDirection>,
    /// Compose rules only flag a device-model difference when this is enabled.
    pub require_same_device: bool,
}

impl Default for RegressionPolicy {
    fn default() -> Self {
        Self {
            relative_threshold_percent: Some(5.0),
            absolute_threshold: None,
            minimum_sample_count: 5,
            bootstrap_iterations: 2000,
            confidence_level: 0.95,
            directions: BTreeMap::new(),
            require_same_device: true,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum GateDecision {
    Pass,
    Fail,
    Inconclusive,
}

impl GateDecision {
    pub fn label(self) -> &'static str {
        match self {
            Self::Pass => "PASS",
            Self::Fail => "FAIL",
            Self::Inconclusive => "INCONCLUSIVE",
        }
    }
}

/// Machine-stable reason identifiers so presentations localize them instead of matching prose.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum GateReason {
    IncompatibleEvidence,
    BlockingRegressions,
    InconclusiveEvidence,
    InconclusiveDisallowed,
    NoBlockingRegression,
}

impl GateReason {
    pub fn label(self) -> &'static str {
        match self {
            Self::IncompatibleEvidence => "INCOMPATIBLE_EVIDENCE",
            Self::BlockingRegressions => "BLOCKING_REGRESSIONS",
            Self::InconclusiveEvidence => "INCONCLUSIVE_EVIDENCE",
            Self::InconclusiveDisallowed => "INCONCLUSIVE_DISALLOWED",
            Self::NoBlockingRegression => "NO_BLOCKING_REGRESSION",
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct ReleaseGatePolicy {
    pub policy_version: String,
    pub blocking_metric_patterns: Vec<String>,
    pub fail_on_regression: bool,
    pub max_allowed_regressions: u32,
    pub allow_inconclusive: bool,
    pub minimum_confidence: Confidence,
}

impl Default for ReleaseGatePolicy {
    fn default() -> Self {
        Self {
            policy_version: "1".to_string(),
            blocking_metric_patterns: Vec::new(),
            fail_on_regression: true,
            max_allowed_regressions: 0,
            allow_inconclusive: true,
            minimum_confidence: Confidence::Exact,
        }
    }
}

impl ReleaseGatePolicy {
    /// An empty pattern list treats every metric as blocking.
    pub fn blocks_metric(&self, metric_name: &str) -> bool {
        self.blocking_metric_patterns.is_empty()
            || self
                .blocking_metric_patterns
                .iter()
                .any(|pattern| metric_name.to_lowercase().contains(&pattern.to_lowercase()))
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct ReleaseGateResult {
    pub decision: GateDecision,
    pub policy_version: String,
    pub blocking_comparisons: Vec<MetricComparison>,
    pub reasons: Vec<GateReason>,
}

/// Compare every metric of the current run against the baseline run.
pub fn compare(
    baseline: &BenchmarkRun,
    current: &BenchmarkRun,
    policy: &RegressionPolicy,
) -> Result<RegressionReport, String> {
    validate_policy(policy)?;
    let run_issues = run_compatibility_issues(baseline, current, policy);
    let baseline_cases: HashMap<String, &BenchmarkCase> = baseline
        .cases
        .iter()
        .map(|case| (case.identity(), case))
        .collect();
    let mut compatibility_issues = run_issues.clone();
    let mut comparisons = Vec::new();
    for case in &current.cases {
        let identity = case.identity();
        let baseline_case = baseline_cases.get(&identity).copied();
        let case_issues = baseline_case
            .map(|baseline_case| case_compatibility_issues(baseline_case, case))
            .unwrap_or_default();
        compatibility_issues.extend(case_issues.iter().cloned());
        let mut hard_issues: Vec<CompatibilityIssue> = run_issues
            .iter()
            .filter(|issue| issue.hard)
            .cloned()
            .collect();
        hard_issues.extend(case_issues.iter().filter(|issue| issue.hard).cloned());
        for metric in &case.metrics {
            let baseline_metric = baseline_case.and_then(|case| case.metric(&metric.name));
            comparisons.push(compare_metric(
                &identity,
                baseline_metric,
                metric,
                policy,
                &hard_issues,
            ));
        }
    }
    Ok(RegressionReport {
        baseline_run_id: baseline.id.clone(),
        current_run_id: current.id.clone(),
        created_at: now_rfc3339(),
        comparisons,
        compatibility_issues,
        policy_version: POLICY_VERSION,
    })
}

/// Faithful port of `ReleaseGateEvaluator.evaluate`.
pub fn evaluate_gate(report: &RegressionReport, policy: &ReleaseGatePolicy) -> ReleaseGateResult {
    let hard_issues = report
        .compatibility_issues
        .iter()
        .filter(|issue| issue.hard)
        .count();
    let incompatible_comparisons: Vec<&MetricComparison> = report
        .comparisons
        .iter()
        .filter(|comparison| comparison.classification == Classification::Incompatible)
        .collect();
    let regressions: Vec<&MetricComparison> = report
        .comparisons
        .iter()
        .filter(|comparison| comparison.classification == Classification::Regressed)
        .collect();
    let confident_regressions: Vec<&MetricComparison> = regressions
        .iter()
        .copied()
        .filter(|comparison| comparison.confidence.rank() >= policy.minimum_confidence.rank())
        .collect();
    let blocking_regressions: Vec<&MetricComparison> = confident_regressions
        .iter()
        .copied()
        .filter(|comparison| policy.blocks_metric(&comparison.metric_name))
        .collect();
    let mut blocking: Vec<MetricComparison> = blocking_regressions
        .iter()
        .map(|comparison| (*comparison).clone())
        .collect();
    blocking.extend(
        incompatible_comparisons
            .iter()
            .map(|comparison| (*comparison).clone()),
    );
    let incompatible_evidence = hard_issues > 0 || !incompatible_comparisons.is_empty();
    let exceeded = policy.fail_on_regression
        && blocking_regressions.len() > policy.max_allowed_regressions as usize;
    let inconclusive_evidence = report
        .comparisons
        .iter()
        .any(|comparison| comparison.classification == Classification::Inconclusive)
        || regressions
            .iter()
            .any(|comparison| !confident_regressions.contains(comparison));

    let mut reasons = Vec::new();
    if incompatible_evidence {
        reasons.push(GateReason::IncompatibleEvidence);
    }
    if exceeded {
        reasons.push(GateReason::BlockingRegressions);
    }
    if inconclusive_evidence {
        reasons.push(if policy.allow_inconclusive {
            GateReason::InconclusiveEvidence
        } else {
            GateReason::InconclusiveDisallowed
        });
    }
    if reasons.is_empty() {
        reasons.push(GateReason::NoBlockingRegression);
    }

    let decision = if incompatible_evidence {
        GateDecision::Inconclusive
    } else if exceeded {
        GateDecision::Fail
    } else if inconclusive_evidence {
        if policy.allow_inconclusive {
            GateDecision::Inconclusive
        } else {
            GateDecision::Fail
        }
    } else {
        GateDecision::Pass
    };

    ReleaseGateResult {
        decision,
        policy_version: policy.policy_version.clone(),
        blocking_comparisons: blocking,
        reasons,
    }
}

fn run_compatibility_issues(
    baseline: &BenchmarkRun,
    current: &BenchmarkRun,
    policy: &RegressionPolicy,
) -> Vec<CompatibilityIssue> {
    let mut issues = Vec::new();
    // Compose device/build rules plus the Rust fail-closed missing-cohort rules.
    cohort_field(
        &mut issues,
        "device.model",
        baseline.device.model.clone(),
        current.device.model.clone(),
        policy.require_same_device,
    );
    cohort_field(
        &mut issues,
        "device.apiLevel",
        baseline.device.api_level.map(|value| value.to_string()),
        current.device.api_level.map(|value| value.to_string()),
        true,
    );
    cohort_field(
        &mut issues,
        "device.abi",
        baseline.device.abi.clone(),
        current.device.abi.clone(),
        true,
    );
    if let (Some(before), Some(after)) = (
        baseline.device.fingerprint.as_deref(),
        current.device.fingerprint.as_deref(),
    ) && before != after
    {
        issues.push(CompatibilityIssue {
            field: "device.fingerprint".to_string(),
            baseline: Some(before.to_string()),
            current: Some(after.to_string()),
            hard: false,
        });
    }
    cohort_field(
        &mut issues,
        "build.variant",
        baseline.build.variant.clone(),
        current.build.variant.clone(),
        true,
    );
    for (field, before, after) in [
        (
            "library_version",
            baseline.library_version.clone(),
            current.library_version.clone(),
        ),
        (
            "data_version",
            baseline.data_version.map(|value| value.to_string()),
            current.data_version.map(|value| value.to_string()),
        ),
    ] {
        if before.is_none() || after.is_none() {
            issues.push(CompatibilityIssue {
                field: field.to_string(),
                baseline: before,
                current: after,
                hard: true,
            });
        }
    }
    issues
}

fn cohort_field(
    issues: &mut Vec<CompatibilityIssue>,
    field: &str,
    baseline: Option<String>,
    current: Option<String>,
    compare_some_values: bool,
) {
    if baseline.is_none() || current.is_none() {
        issues.push(CompatibilityIssue {
            field: field.to_string(),
            baseline,
            current,
            hard: true,
        });
    } else if compare_some_values && baseline != current {
        issues.push(CompatibilityIssue {
            field: field.to_string(),
            baseline,
            current,
            hard: true,
        });
    }
}

fn case_compatibility_issues(
    baseline: &BenchmarkCase,
    current: &BenchmarkCase,
) -> Vec<CompatibilityIssue> {
    let identity = current.identity();
    let mut issues = Vec::new();
    push_case_difference(
        &mut issues,
        &identity,
        "scenario",
        Some(baseline.scenario.label().to_string()),
        Some(current.scenario.label().to_string()),
    );
    push_case_difference(
        &mut issues,
        &identity,
        "compilationMode",
        baseline.compilation_mode.clone(),
        current.compilation_mode.clone(),
    );
    push_case_difference(
        &mut issues,
        &identity,
        "startupMode",
        baseline.startup_mode.clone(),
        current.startup_mode.clone(),
    );
    issues
}

fn push_case_difference(
    issues: &mut Vec<CompatibilityIssue>,
    identity: &str,
    field: &str,
    baseline: Option<String>,
    current: Option<String>,
) {
    if baseline != current {
        issues.push(CompatibilityIssue {
            field: format!("case.{identity}.{field}"),
            baseline,
            current,
            hard: true,
        });
    }
}

fn compare_metric(
    case_identity: &str,
    baseline: Option<&Metric>,
    current: &Metric,
    policy: &RegressionPolicy,
    hard_issues: &[CompatibilityIssue],
) -> MetricComparison {
    let baseline_value = baseline.and_then(Metric::representative_value);
    let current_value = current.representative_value();
    let absolute_delta = baseline_value
        .zip(current_value)
        .map(|(before, after)| after - before);
    let relative_delta_percent = baseline_value
        .zip(absolute_delta)
        .filter(|(before, _)| *before != 0.0 && before.is_finite())
        .map(|(before, delta)| delta / before.abs() * 100.0);
    let confidence = if baseline.is_some()
        && baseline.map(|metric| metric.samples.len()).unwrap_or(0) >= policy.minimum_sample_count
        && current.samples.len() >= policy.minimum_sample_count
    {
        Confidence::Exact
    } else {
        Confidence::Partial
    };
    let mut result = MetricComparison {
        case_identity: case_identity.to_string(),
        metric_name: current.name.clone(),
        unit: current.unit.clone(),
        baseline_value,
        current_value,
        absolute_delta,
        relative_delta_percent,
        classification: Classification::Inconclusive,
        confidence,
        reasons: Vec::new(),
        confidence_interval: None,
        practical_threshold: None,
    };
    if !hard_issues.is_empty() {
        let fields = hard_issues
            .iter()
            .map(|issue| issue.field.as_str())
            .collect::<Vec<_>>()
            .join(", ");
        result
            .reasons
            .push(format!("Run environments are incompatible: {fields}"));
        result.classification = Classification::Incompatible;
        return result;
    }
    let Some(baseline) = baseline else {
        result
            .reasons
            .push("No baseline metric with the same case and metric identity.".to_string());
        return result;
    };
    if baseline.unit != current.unit {
        result.reasons.push(format!(
            "Metric units differ ({} vs {}).",
            baseline.unit, current.unit
        ));
        result.classification = Classification::Incompatible;
        return result;
    }
    let direction = policy
        .directions
        .get(&current.name)
        .copied()
        .unwrap_or(current.direction);
    if direction == MetricDirection::Unknown {
        result
            .reasons
            .push("Metric direction requires explicit configuration.".to_string());
        return result;
    }
    if baseline.samples.len() < policy.minimum_sample_count
        || current.samples.len() < policy.minimum_sample_count
    {
        result.reasons.push(
            "Both runs require sufficient raw samples; summary medians alone cannot gate."
                .to_string(),
        );
        return result;
    }
    let absolute_threshold = policy.absolute_threshold.unwrap_or(0.0);
    let relative_threshold = match (policy.relative_threshold_percent, baseline_value) {
        (Some(percent), Some(before)) if before != 0.0 => percent * before.abs() / 100.0,
        (Some(_), _) if policy.absolute_threshold.is_none() => {
            result
                .reasons
                .push("Relative threshold is undefined for a zero baseline.".to_string());
            return result;
        }
        _ => 0.0,
    };
    let threshold = absolute_threshold.max(relative_threshold);
    result.practical_threshold = Some(threshold);
    let Some(interval) = uncertainty::bca_median_difference(
        &baseline.samples,
        &current.samples,
        policy.bootstrap_iterations,
        policy.confidence_level,
    ) else {
        result
            .reasons
            .push("Two-sample BCa interval could not be established.".to_string());
        return result;
    };
    result.confidence_interval = Some(interval);
    let (low, high) = match direction {
        MetricDirection::HigherIsBetter => (-interval.1, -interval.0),
        _ => interval,
    };
    if low > threshold {
        result.classification = Classification::Regressed;
    } else if high < -threshold {
        result.classification = Classification::Improved;
    } else if low >= -threshold && high <= threshold {
        result.classification = Classification::Stable;
    } else {
        result
            .reasons
            .push("Confidence interval overlaps the practical-change boundary.".to_string());
    }
    result
}

fn validate_policy(policy: &RegressionPolicy) -> Result<(), String> {
    if policy.relative_threshold_percent.is_none() && policy.absolute_threshold.is_none()
        || [policy.relative_threshold_percent, policy.absolute_threshold]
            .into_iter()
            .flatten()
            .any(|value| !value.is_finite() || value < 0.0)
        || !(3..=100_000).contains(&policy.minimum_sample_count)
        || !(200..=10_000).contains(&policy.bootstrap_iterations)
        || !(0.80..=0.99).contains(&policy.confidence_level)
    {
        return Err("Invalid benchmark regression policy".into());
    }
    Ok(())
}

//! AndroidX Benchmark import and fail-closed comparable-cohort regression gates.
mod parser;
mod uncertainty;

use std::collections::BTreeMap;
use std::path::Path;

pub const MAX_PAGE_SIZE: usize = 512;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MetricDirection {
    LowerIsBetter,
    HigherIsBetter,
    Unknown,
}

#[derive(Debug, Clone)]
pub struct Metric {
    pub name: String,
    pub unit: String,
    pub direction: MetricDirection,
    pub samples: Vec<f64>,
    pub reported_median: Option<f64>,
}

#[derive(Debug, Clone)]
pub struct BenchmarkCase {
    pub identity: String,
    pub package: Option<String>,
    pub compilation_mode: Option<String>,
    pub startup_mode: Option<String>,
    pub scenario: Option<String>,
    pub profile_status: Option<String>,
    pub profile_hash: Option<String>,
    pub trace_paths: Vec<String>,
    pub metrics: BTreeMap<String, Metric>,
}

#[derive(Debug, Clone, Default)]
pub struct Cohort {
    pub device_model: Option<String>,
    pub api_level: Option<u32>,
    pub abi: Option<String>,
    pub fingerprint: Option<String>,
    pub physical_device: Option<bool>,
    pub build_variant: Option<String>,
    pub library_version: Option<String>,
    pub data_version: Option<u32>,
}

pub struct BenchmarkRun {
    pub cohort: Cohort,
    pub warnings: Vec<String>,
    cases: Vec<BenchmarkCase>,
}

impl BenchmarkRun {
    pub fn open(path: &Path, max_bytes: u64) -> Result<Self, String> {
        parser::document(
            aps_json::read(
                path,
                aps_json::Limits {
                    max_bytes,
                    ..aps_json::Limits::default()
                },
            )
            .map_err(|error| error.to_string())?,
        )
    }
    pub fn parse_json(bytes: &[u8]) -> Result<Self, String> {
        parser::document(
            aps_json::parse_slice(bytes, aps_json::Limits::default())
                .map_err(|error| error.to_string())?,
        )
    }
    pub fn cases(&self) -> &[BenchmarkCase] {
        &self.cases
    }
    pub fn page(&self, query: &str, offset: usize, limit: usize) -> Result<CasePage, String> {
        bounds(offset, limit)?;
        let query = query.to_ascii_lowercase();
        let selected: Vec<_> = self
            .cases
            .iter()
            .filter(|case| case.identity.to_ascii_lowercase().contains(&query))
            .collect();
        Ok(CasePage {
            total: selected.len(),
            offset,
            rows: selected
                .into_iter()
                .skip(offset)
                .take(limit)
                .cloned()
                .collect(),
        })
    }
}

#[derive(Debug, Clone)]
pub struct CasePage {
    pub total: usize,
    pub offset: usize,
    pub rows: Vec<BenchmarkCase>,
}

#[derive(Debug, Clone)]
pub struct RegressionPolicy {
    pub relative_threshold_percent: Option<f64>,
    pub absolute_threshold: Option<f64>,
    pub minimum_sample_count: usize,
    pub bootstrap_iterations: usize,
    pub confidence_level: f64,
    /// Explicit semantic configuration, never guess an unknown metric direction.
    pub directions: BTreeMap<String, MetricDirection>,
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
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Classification {
    Regressed,
    Improved,
    Stable,
    Inconclusive,
    Incompatible,
}

#[derive(Debug, Clone)]
pub struct MetricComparison {
    pub case_identity: String,
    pub metric: String,
    pub unit: String,
    pub baseline_median: Option<f64>,
    pub current_median: Option<f64>,
    pub median_difference: Option<f64>,
    pub relative_difference_percent: Option<f64>,
    pub confidence_interval: Option<(f64, f64)>,
    pub practical_threshold: Option<f64>,
    pub classification: Classification,
    pub reasons: Vec<String>,
}

pub struct RegressionReport {
    pub comparisons: Vec<MetricComparison>,
    pub compatibility_issues: Vec<String>,
    pub policy_version: &'static str,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum GateDecision {
    Pass,
    Fail,
    Inconclusive,
}

impl RegressionReport {
    /// Incompatible/inconclusive required evidence fails closed unless the caller
    /// explicitly asks for warnings. No p-value or baseline MAD can pass this gate.
    pub fn gate(&self, allow_inconclusive: bool) -> GateDecision {
        if self
            .comparisons
            .iter()
            .any(|comparison| comparison.classification == Classification::Regressed)
        {
            return GateDecision::Fail;
        }
        if !self.compatibility_issues.is_empty()
            || self.comparisons.is_empty()
            || self.comparisons.iter().any(|comparison| {
                matches!(
                    comparison.classification,
                    Classification::Inconclusive | Classification::Incompatible
                )
            })
        {
            return if allow_inconclusive {
                GateDecision::Inconclusive
            } else {
                GateDecision::Fail
            };
        }
        GateDecision::Pass
    }

    pub fn page(&self, offset: usize, limit: usize) -> Result<&[MetricComparison], String> {
        bounds(offset, limit)?;
        let start = offset.min(self.comparisons.len());
        Ok(&self.comparisons[start..start.saturating_add(limit).min(self.comparisons.len())])
    }
}

pub fn compare(
    baseline: &BenchmarkRun,
    current: &BenchmarkRun,
    policy: &RegressionPolicy,
) -> Result<RegressionReport, String> {
    validate_policy(policy)?;
    let compatibility_issues = cohort_issues(&baseline.cohort, &current.cohort);
    let mut comparisons = Vec::new();
    let before: BTreeMap<_, _> = baseline
        .cases
        .iter()
        .map(|case| (&case.identity, case))
        .collect();
    let after: BTreeMap<_, _> = current
        .cases
        .iter()
        .map(|case| (&case.identity, case))
        .collect();
    let identities: std::collections::BTreeSet<_> =
        before.keys().chain(after.keys()).copied().collect();
    for identity in identities {
        let old = before.get(identity).copied();
        let new = after.get(identity).copied();
        let names: std::collections::BTreeSet<_> = old
            .into_iter()
            .chain(new)
            .flat_map(|case| case.metrics.keys())
            .collect();
        let mut issues = compatibility_issues.clone();
        if let Some((old, new)) = old.zip(new) {
            issues.extend(case_issues(old, new));
        } else {
            issues.push("Benchmark case is absent from one run.".into());
        }
        for name in names {
            let old_metric = old.and_then(|case| case.metrics.get(name));
            let new_metric = new.and_then(|case| case.metrics.get(name));
            comparisons.push(compare_metric(
                identity, name, old_metric, new_metric, policy, &issues,
            ));
        }
    }
    Ok(RegressionReport {
        comparisons,
        compatibility_issues,
        policy_version: "aps-benchmark-bca-median-v1",
    })
}

fn compare_metric(
    identity: &str,
    name: &str,
    old: Option<&Metric>,
    new: Option<&Metric>,
    policy: &RegressionPolicy,
    issues: &[String],
) -> MetricComparison {
    let before = old.and_then(|metric| uncertainty::median(&metric.samples));
    let after = new.and_then(|metric| uncertainty::median(&metric.samples));
    let difference = before.zip(after).map(|(before, after)| after - before);
    let relative = before
        .zip(difference)
        .filter(|(before, _)| *before != 0.0)
        .map(|(before, difference)| difference / before.abs() * 100.0);
    let mut result = MetricComparison {
        case_identity: identity.into(),
        metric: name.into(),
        unit: new
            .or(old)
            .map_or("unknown", |metric| metric.unit.as_str())
            .into(),
        baseline_median: before,
        current_median: after,
        median_difference: difference,
        relative_difference_percent: relative,
        confidence_interval: None,
        practical_threshold: None,
        classification: Classification::Inconclusive,
        reasons: issues.into(),
    };
    if !issues.is_empty() {
        result.classification = Classification::Incompatible;
        return result;
    }
    let Some((old, new)) = old.zip(new) else {
        result.reasons.push("Metric is absent from one run.".into());
        return result;
    };
    if old.unit != new.unit {
        result.classification = Classification::Incompatible;
        result.reasons.push("Metric units differ.".into());
        return result;
    }
    let direction = policy
        .directions
        .get(name)
        .copied()
        .unwrap_or(new.direction);
    if direction == MetricDirection::Unknown {
        result
            .reasons
            .push("Metric direction requires explicit configuration.".into());
        return result;
    }
    if old.samples.len() < policy.minimum_sample_count
        || new.samples.len() < policy.minimum_sample_count
    {
        result.reasons.push(
            "Both runs require sufficient raw samples; summary medians alone cannot gate.".into(),
        );
        return result;
    }
    let absolute = policy.absolute_threshold.unwrap_or(0.0);
    let relative_threshold = match (policy.relative_threshold_percent, before) {
        (Some(percent), Some(before)) if before != 0.0 => percent * before.abs() / 100.0,
        (Some(_), _) if policy.absolute_threshold.is_none() => {
            result
                .reasons
                .push("Relative threshold is undefined for a zero baseline.".into());
            return result;
        }
        _ => 0.0,
    };
    let threshold = absolute.max(relative_threshold);
    result.practical_threshold = Some(threshold);
    let Some(interval) = uncertainty::bca_median_difference(
        &old.samples,
        &new.samples,
        policy.bootstrap_iterations,
        policy.confidence_level,
    ) else {
        result
            .reasons
            .push("Two-sample BCa interval could not be established.".into());
        return result;
    };
    result.confidence_interval = Some(interval);
    let (low, high) = match direction {
        MetricDirection::HigherIsBetter => (-interval.1, -interval.0),
        _ => interval,
    };
    result.classification = if low > threshold {
        Classification::Regressed
    } else if high < -threshold {
        Classification::Improved
    } else if low >= -threshold && high <= threshold {
        Classification::Stable
    } else {
        result
            .reasons
            .push("Confidence interval overlaps the practical-change boundary.".into());
        Classification::Inconclusive
    };
    result
}

fn cohort_issues(old: &Cohort, new: &Cohort) -> Vec<String> {
    let mut issues = Vec::new();
    macro_rules! required {
        ($field:ident) => {
            if old.$field.is_none() || new.$field.is_none() || old.$field != new.$field {
                issues
                    .push(concat!("Missing or differing cohort ", stringify!($field), ".").into());
            }
        };
    }
    required!(device_model);
    required!(api_level);
    required!(abi);
    required!(fingerprint);
    required!(physical_device);
    required!(build_variant);
    required!(library_version);
    required!(data_version);
    issues
}

fn case_issues(old: &BenchmarkCase, new: &BenchmarkCase) -> Vec<String> {
    let mut issues = Vec::new();
    for (name, before, after) in [
        ("target package", &old.package, &new.package),
        (
            "compilation mode",
            &old.compilation_mode,
            &new.compilation_mode,
        ),
        ("scenario", &old.scenario, &new.scenario),
    ] {
        if before.is_none() || after.is_none() || before != after {
            issues.push(format!("Missing or differing case {name}."));
        }
    }
    if old.startup_mode != new.startup_mode
        || old.scenario.as_deref() == Some("STARTUP")
            && (old.startup_mode.is_none() || new.startup_mode.is_none())
    {
        issues.push("Missing or differing startup mode.".into());
    }
    if old.profile_status != new.profile_status || old.profile_hash != new.profile_hash {
        issues.push(
            "Baseline Profile evidence differs; use an explicitly separate experiment cohort."
                .into(),
        );
    }
    issues
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
fn bounds(offset: usize, limit: usize) -> Result<(), String> {
    if limit > MAX_PAGE_SIZE || offset.checked_add(limit).is_none() {
        return Err("Benchmark page exceeds supported bounds".into());
    }
    Ok(())
}

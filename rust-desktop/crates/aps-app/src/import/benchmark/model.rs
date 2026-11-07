//! Session-local, evidence-bound projections for the Benchmark / Reports UI.
use aps_benchmark::{
    BenchmarkCase, BenchmarkRun, Classification, Cohort, GateDecision, Metric, MetricComparison,
    RegressionPolicy, RegressionReport,
};
use std::sync::Arc;

pub const MAX_RUNS: usize = 200;
pub const COMPARISON_PAGE: usize = 100;
pub const TREND_WINDOW: usize = 10;

#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct ComparisonKey {
    pub case_identity: String,
    pub metric: String,
}
impl ComparisonKey {
    pub fn of(comparison: &MetricComparison) -> Self {
        Self {
            case_identity: comparison.case_identity.clone(),
            metric: comparison.metric.clone(),
        }
    }
}

#[derive(Clone)]
pub struct RunEntry {
    pub id: u64,
    pub label: String,
    pub run: Arc<BenchmarkRun>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Severity {
    High,
    Low,
    None,
    Unknown,
    Incompatible,
}
pub fn severity(comparison: &MetricComparison) -> Severity {
    match comparison.classification {
        Classification::Regressed if comparison.confidence_interval.is_some() => Severity::High,
        Classification::Regressed => Severity::Low,
        Classification::Improved | Classification::Stable => Severity::None,
        Classification::Inconclusive => Severity::Unknown,
        Classification::Incompatible => Severity::Incompatible,
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MetricKey {
    pub case_identity: String,
    pub metric: String,
    pub unit: String,
}
#[derive(Clone, Debug, PartialEq)]
pub struct TrendPoint {
    pub run_id: u64,
    pub label: String,
    pub value: f64,
    pub sample_count: usize,
    pub comparable: bool,
}
#[derive(Clone, Debug, PartialEq)]
pub struct Trend {
    pub metric: MetricKey,
    pub points: Vec<TrendPoint>,
    pub environment_varies: bool,
    pub has_low_samples: bool,
    pub minimum_sample_count: usize,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ComparisonPage {
    pub total: usize,
    pub offset: usize,
    pub indices: Vec<usize>,
}

pub struct BenchmarkUi {
    pub runs: Vec<RunEntry>,
    pub baseline_id: Option<u64>,
    pub current_id: Option<u64>,
    pub report: Option<Arc<RegressionReport>>,
    pub selected: Option<ComparisonKey>,
    pub query: String,
    pub severity_filter: Option<Severity>,
    pub classification_filter: Option<Classification>,
    pub offset: usize,
    pub selected_metric: Option<MetricKey>,
    pub policy: RegressionPolicy,
    pub allow_inconclusive: bool,
    next_id: u64,
}
impl Default for BenchmarkUi {
    fn default() -> Self {
        Self {
            runs: Vec::new(),
            baseline_id: None,
            current_id: None,
            report: None,
            selected: None,
            query: String::new(),
            severity_filter: None,
            classification_filter: None,
            offset: 0,
            selected_metric: None,
            policy: RegressionPolicy::default(),
            allow_inconclusive: false,
            next_id: 1,
        }
    }
}
impl BenchmarkUi {
    pub fn add_run(
        &mut self,
        label: impl Into<String>,
        run: Arc<BenchmarkRun>,
    ) -> Result<u64, String> {
        if self.runs.len() >= MAX_RUNS {
            return Err("Benchmark session run limit reached".into());
        }
        let id = self.next_id;
        self.next_id += 1;
        self.runs.push(RunEntry {
            id,
            label: label.into(),
            run,
        });
        if self.current_id.is_none() {
            self.current_id = Some(id);
        }
        Ok(id)
    }
    pub fn baseline(&self) -> Option<&RunEntry> {
        self.baseline_id
            .and_then(|id| self.runs.iter().find(|run| run.id == id))
    }
    pub fn current(&self) -> Option<&RunEntry> {
        self.current_id
            .and_then(|id| self.runs.iter().find(|run| run.id == id))
    }
    pub fn select_baseline(&mut self, id: Option<u64>) -> bool {
        if id.is_some_and(|id| !self.runs.iter().any(|run| run.id == id)) {
            return false;
        }
        if id != self.baseline_id {
            self.baseline_id = id;
            self.invalidate();
        }
        true
    }
    pub fn select_current(&mut self, id: Option<u64>) -> bool {
        if id.is_some_and(|id| !self.runs.iter().any(|run| run.id == id)) {
            return false;
        }
        if id != self.current_id {
            self.current_id = id;
            self.invalidate();
        }
        true
    }
    pub fn publish_report(&mut self, report: RegressionReport) {
        self.report = Some(Arc::new(report));
        self.offset = 0;
        self.reconcile_selection();
    }
    pub fn invalidate(&mut self) {
        self.report = None;
        self.selected = None;
        self.offset = 0;
    }
    pub fn comparisons(&self) -> &[MetricComparison] {
        self.report
            .as_ref()
            .map_or(&[], |report| &report.comparisons)
    }
    pub fn comparison_page(&self) -> ComparisonPage {
        let query = self.query.trim().to_lowercase();
        let matches = |comparison: &&MetricComparison| {
            (query.is_empty()
                || comparison.case_identity.to_lowercase().contains(&query)
                || comparison.metric.to_lowercase().contains(&query))
                && !self
                    .severity_filter
                    .is_some_and(|value| severity(comparison) != value)
                && !self
                    .classification_filter
                    .is_some_and(|value| comparison.classification != value)
        };
        let total = self.comparisons().iter().filter(matches).count();
        let offset = self
            .offset
            .min(total.saturating_sub(1) / COMPARISON_PAGE * COMPARISON_PAGE);
        let indices = self
            .comparisons()
            .iter()
            .enumerate()
            .filter(|(_, comparison)| matches(comparison))
            .skip(offset)
            .take(COMPARISON_PAGE)
            .map(|(index, _)| index)
            .collect();
        ComparisonPage {
            total,
            offset,
            indices,
        }
    }
    pub fn set_query(&mut self, query: impl Into<String>) {
        self.query = query.into();
        self.offset = 0;
        self.reconcile_selection();
    }
    pub fn set_severity(&mut self, value: Option<Severity>) {
        self.severity_filter = value;
        self.offset = 0;
        self.reconcile_selection();
    }
    pub fn set_classification(&mut self, value: Option<Classification>) {
        self.classification_filter = value;
        self.offset = 0;
        self.reconcile_selection();
    }
    pub fn reconcile_selection(&mut self) {
        if self.selected.as_ref().is_some_and(|key| {
            !self
                .comparison_page()
                .indices
                .iter()
                .any(|&index| ComparisonKey::of(&self.comparisons()[index]) == *key)
        }) {
            self.selected = None;
        }
    }
    pub fn selected_comparison(&self) -> Option<&MetricComparison> {
        self.selected.as_ref().and_then(|key| {
            self.comparisons()
                .iter()
                .find(|comparison| ComparisonKey::of(comparison) == *key)
        })
    }
    pub fn sample_counts(&self, comparison: &MetricComparison) -> (Option<usize>, Option<usize>) {
        let count = |run: Option<&RunEntry>| {
            run.and_then(|run| {
                case_metric(&run.run, &comparison.case_identity, &comparison.metric)
                    .map(|(_, metric)| metric.samples.len())
            })
        };
        (count(self.baseline()), count(self.current()))
    }
    pub fn trace_artifacts(&self, comparison: &MetricComparison) -> &[String] {
        self.current()
            .and_then(|run| {
                run.run
                    .cases()
                    .iter()
                    .find(|case| case.identity == comparison.case_identity)
            })
            .map_or(&[], |case| &case.trace_paths)
    }
    pub fn metric_options(&self) -> Vec<MetricKey> {
        let mut options = Vec::new();
        for run in &self.runs {
            for case in run.run.cases() {
                for metric in case.metrics.values() {
                    let key = MetricKey {
                        case_identity: case.identity.clone(),
                        metric: metric.name.clone(),
                        unit: metric.unit.clone(),
                    };
                    if !options.contains(&key) {
                        options.push(key);
                    }
                }
            }
        }
        options
    }
    pub fn trend(&self) -> Option<Trend> {
        let options = self.metric_options();
        let key = self
            .selected_metric
            .as_ref()
            .filter(|key| options.contains(key))
            .or_else(|| options.first())?;
        let candidates: Vec<_> = self
            .runs
            .iter()
            .filter_map(|run| {
                let (case, metric) = case_metric(&run.run, &key.case_identity, &key.metric)?;
                if metric.unit != key.unit {
                    return None;
                }
                let value = median(&metric.samples)
                    .or(metric.reported_median)
                    .filter(|value| value.is_finite())?;
                Some((run, case, metric, value))
            })
            .collect();
        let last = candidates.last()?;
        let selected = &candidates[candidates.len().saturating_sub(TREND_WINDOW)..];
        let points: Vec<_> = selected
            .iter()
            .map(|(run, case, metric, value)| TrendPoint {
                run_id: run.id,
                label: run.label.clone(),
                value: *value,
                sample_count: metric.samples.len(),
                comparable: cohorts_comparable(&last.0.run.cohort, &run.run.cohort)
                    && cases_comparable(last.1, case),
            })
            .collect();
        Some(Trend {
            metric: key.clone(),
            environment_varies: points.iter().any(|point| !point.comparable),
            has_low_samples: points
                .iter()
                .any(|point| point.sample_count < self.policy.minimum_sample_count),
            points,
            minimum_sample_count: self.policy.minimum_sample_count,
        })
    }
    pub fn release_gate(&self) -> Option<GateDecision> {
        self.report
            .as_ref()
            .map(|report| report.gate(self.allow_inconclusive))
    }
}
fn case_metric<'a>(
    run: &'a BenchmarkRun,
    identity: &str,
    name: &str,
) -> Option<(&'a BenchmarkCase, &'a Metric)> {
    let case = run.cases().iter().find(|case| case.identity == identity)?;
    Some((case, case.metrics.get(name)?))
}
fn median(values: &[f64]) -> Option<f64> {
    if values.is_empty() || values.iter().any(|value| !value.is_finite()) {
        return None;
    }
    let mut sorted = values.to_vec();
    sorted.sort_by(f64::total_cmp);
    let middle = sorted.len() / 2;
    Some(if sorted.len().is_multiple_of(2) {
        sorted[middle - 1] / 2. + sorted[middle] / 2.
    } else {
        sorted[middle]
    })
}
fn cohorts_comparable(a: &Cohort, b: &Cohort) -> bool {
    macro_rules! same { ($($field:ident),+) => { true $(&& a.$field.is_some() && a.$field == b.$field)+ }; }
    same!(
        device_model,
        api_level,
        abi,
        fingerprint,
        physical_device,
        build_variant,
        library_version,
        data_version
    )
}
fn cases_comparable(a: &BenchmarkCase, b: &BenchmarkCase) -> bool {
    a.package.is_some()
        && a.package == b.package
        && a.compilation_mode.is_some()
        && a.compilation_mode == b.compilation_mode
        && a.scenario.is_some()
        && a.scenario == b.scenario
        && a.startup_mode == b.startup_mode
        && (a.scenario.as_deref() != Some("STARTUP") || a.startup_mode.is_some())
        && a.profile_status == b.profile_status
        && a.profile_hash == b.profile_hash
}

#[cfg(test)]
mod tests {
    use super::*;
    fn run(device: &str, unit: &str, samples: &str) -> Arc<BenchmarkRun> {
        Arc::new(BenchmarkRun::parse_json(format!(
            r#"{{"benchmarkDataVersion":3,"benchmarkLibraryVersion":"1","context":{{"deviceModel":"{device}","apiLevel":35,"abi":"arm64-v8a","fingerprint":"fp","physicalDevice":true,"build":{{"variant":"release"}}}},"benchmarks":[{{"name":"dev.Example.scroll","packageName":"dev.example","compilationMode":"FULL","startupMode":"WARM","scenario":"SCROLL","metrics":{{"frameDurationMs":{{"unit":"{unit}","runs":{samples}}}}}}}]}}"#
        ).as_bytes()).unwrap())
    }
    fn comparison(case_identity: String, classification: Classification) -> MetricComparison {
        MetricComparison {
            case_identity,
            metric: "frameDurationMs".into(),
            unit: "ms".into(),
            baseline_median: Some(10.),
            current_median: Some(11.),
            median_difference: Some(1.),
            relative_difference_percent: Some(10.),
            confidence_interval: None,
            practical_threshold: Some(0.5),
            classification,
            reasons: Vec::new(),
        }
    }
    #[test]
    fn comparison_identity_is_not_delimiter_concatenation() {
        assert_ne!(
            ComparisonKey {
                case_identity: "a#b".into(),
                metric: "c".into()
            },
            ComparisonKey {
                case_identity: "a".into(),
                metric: "b#c".into()
            }
        );
    }
    #[test]
    fn medians_use_only_finite_actual_samples() {
        assert_eq!(median(&[3., 1., 2.]), Some(2.));
        assert_eq!(median(&[4., 1., 3., 2.]), Some(2.5));
        assert_eq!(median(&[]), None);
        assert_eq!(median(&[f64::NAN]), None);
    }
    #[test]
    fn absent_environment_does_not_become_comparable_with_itself() {
        assert!(!cohorts_comparable(&Cohort::default(), &Cohort::default()));
    }
    #[test]
    fn invalid_run_selection_retains_state() {
        let mut state = BenchmarkUi::default();
        assert!(!state.select_baseline(Some(42)));
        assert!(!state.select_current(Some(42)));
        assert_eq!(state.baseline_id, None);
        assert_eq!(state.current_id, None);
    }
    #[test]
    fn duplicate_run_labels_do_not_alias_ids_or_selection() {
        let mut state = BenchmarkUi::default();
        let first = state
            .add_run("capture.json", run("Pixel 9", "ms", "[10,11,12]"))
            .unwrap();
        let second = state
            .add_run("capture.json", run("Pixel 9", "ms", "[13,14,15]"))
            .unwrap();
        assert_ne!(first, second);
        assert!(state.select_baseline(Some(first)));
        assert!(state.select_current(Some(second)));
        state.runs.reverse();
        assert_eq!(state.baseline().unwrap().id, first);
        assert_eq!(state.current().unwrap().id, second);
    }
    #[test]
    fn filters_keep_source_indices_and_hidden_selection_is_cleared() {
        let mut state = BenchmarkUi::default();
        state.publish_report(RegressionReport {
            comparisons: vec![
                comparison("dev.Example#scroll".into(), Classification::Stable),
                comparison("dev.Other#launch".into(), Classification::Incompatible),
            ],
            compatibility_issues: Vec::new(),
            policy_version: "fixture",
        });
        state.selected = Some(ComparisonKey::of(&state.comparisons()[0]));
        state.set_classification(Some(Classification::Incompatible));
        assert_eq!(state.comparison_page().indices, vec![1]);
        assert!(state.selected.is_none());
        state.set_query("OTHER");
        assert_eq!(state.comparison_page().indices, vec![1]);
        state.set_query("absent");
        assert_eq!(state.comparison_page().total, 0);
    }
    #[test]
    fn pagination_is_bounded_and_stale_offset_clamps_to_last_page() {
        let mut state = BenchmarkUi::default();
        state.publish_report(RegressionReport {
            comparisons: (0..205)
                .map(|index| comparison(format!("case{index}"), Classification::Stable))
                .collect(),
            compatibility_issues: Vec::new(),
            policy_version: "fixture",
        });
        assert_eq!(state.comparison_page().indices.len(), COMPARISON_PAGE);
        state.offset = usize::MAX;
        let page = state.comparison_page();
        assert_eq!(page.offset, 200);
        assert_eq!(page.indices, (200..205).collect::<Vec<_>>());
    }
    #[test]
    fn trend_never_mixes_units_and_flags_device_changes_and_low_samples() {
        let mut state = BenchmarkUi::default();
        state
            .add_run("before", run("Pixel 8", "ms", "[10,11,12]"))
            .unwrap();
        state
            .add_run(
                "unit mismatch",
                run("Pixel 9", "ns", "[90000,100000,110000]"),
            )
            .unwrap();
        state
            .add_run("after", run("Pixel 9", "ms", "[20,21,22]"))
            .unwrap();
        let trend = state.trend().unwrap();
        assert_eq!(trend.points.len(), 2);
        assert_eq!(trend.points[0].value, 11.);
        assert_eq!(trend.points[1].value, 21.);
        assert!(!trend.points[0].comparable);
        assert!(trend.points[1].comparable);
        assert!(trend.environment_varies);
        assert!(trend.has_low_samples);
    }
}

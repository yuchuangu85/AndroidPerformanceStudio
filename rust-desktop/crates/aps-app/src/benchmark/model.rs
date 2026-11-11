//! Benchmark Regression projections for the Compose-parity page.
//!
//! Compose `BenchmarkReportsState.kt` is the oracle: derived summary counts, severity derived from the
//! verdict plus its evidence confidence, free-text/severity/classification filtering on stable
//! case/metric identities, and a metric-history trend that discloses mixed environments and low
//! sample points instead of drawing a silently incomparable line. Everything here is a pure
//! projection of imported evidence; nothing is invented.

use aps_benchmark::{
    BenchmarkReportEntry, Classification, CompatibilityIssue, Confidence, MetricComparison,
    RegressionReport, StoredBenchmarkRun, StoredMetric, StoredMetricPoint,
};
use std::path::{Path, PathBuf};
use std::sync::Arc;

pub const TREND_WINDOW: usize = 10;
pub const TREND_MIN_SAMPLES: usize = 3;

/// Compose `RegressionSeverity`: derived from the verdict, never from an invented score.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Severity {
    High,
    Low,
    None,
    Unknown,
    Incompatible,
}

/// Compose `severityOf`: an exact-confidence regression is High, a weaker regression is Low,
/// incompatible evidence stays incompatible instead of being folded into a regression.
pub fn severity(comparison: &MetricComparison) -> Severity {
    match comparison.classification {
        Classification::Regressed => match comparison.confidence {
            Confidence::Exact => Severity::High,
            _ => Severity::Low,
        },
        Classification::Improved | Classification::Stable => Severity::None,
        Classification::Inconclusive => Severity::Unknown,
        Classification::Incompatible => Severity::Incompatible,
    }
}

/// Stable case/metric identity. Never concatenated into one delimiter-joined string.
#[derive(Clone, Debug, PartialEq, Eq, Hash)]
pub struct ComparisonKey {
    pub case_identity: String,
    pub metric: String,
}

pub fn comparison_key(comparison: &MetricComparison) -> ComparisonKey {
    ComparisonKey {
        case_identity: comparison.case_identity.clone(),
        metric: comparison.metric_name.clone(),
    }
}

/// One stored run offered by the baseline/current selectors.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct RunOption {
    pub id: String,
    pub label: String,
    pub detail: String,
    pub source_path: String,
    pub available: bool,
}

impl RunOption {
    /// Compose `loadRunOptions`: label is the build version name or the JSON file name, detail is
    /// "device · API n · imported date", and availability is the source file still being readable.
    pub fn from_stored(run: &StoredBenchmarkRun) -> Self {
        let label = run
            .version_name
            .clone()
            .filter(|value| !value.trim().is_empty())
            .unwrap_or_else(|| file_name(&run.source_path));
        let mut detail = Vec::new();
        if let Some(device) = run
            .device_model
            .as_deref()
            .filter(|value| !value.is_empty())
        {
            detail.push(device.to_owned());
        }
        if let Some(api) = run.api_level {
            detail.push(format!("API {api}"));
        }
        if let Some(date) = date_prefix(&run.imported_at) {
            detail.push(date.to_owned());
        }
        Self {
            id: run.id.clone(),
            label,
            detail: detail.join(" · "),
            source_path: run.source_path.clone(),
            available: Path::new(&run.source_path).is_file(),
        }
    }
}

/// A case/metric pair that has stored history and can be plotted.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MetricOption {
    pub key: String,
    pub case_identity: String,
    pub metric_name: String,
    pub unit: String,
}

impl MetricOption {
    /// Compose `loadMetricOptions`: the key is the case identity is kept as its own field rather
    /// than re-split from a joined string.
    pub fn from_stored(metric: &StoredMetric) -> Self {
        Self {
            key: metric_key(&metric.case_identity, &metric.metric_name),
            case_identity: metric.case_identity.clone(),
            metric_name: metric.metric_name.clone(),
            unit: metric.unit.clone(),
        }
    }

    pub fn label(&self) -> String {
        if self.unit.is_empty() {
            format!("{} · {}", self.case_identity, self.metric_name)
        } else {
            format!(
                "{} · {} ({})",
                self.case_identity, self.metric_name, self.unit
            )
        }
    }
}

pub fn metric_key(case_identity: &str, metric_name: &str) -> String {
    format!("{case_identity}\u{1f}{metric_name}")
}

/// One point of the trend chart; the label is the build label or the import date.
#[derive(Clone, Debug, PartialEq)]
pub struct TrendPoint {
    pub label: String,
    pub value: f64,
    pub sample_count: usize,
    pub comparable: bool,
}

#[derive(Clone, Debug, PartialEq)]
pub struct Trend {
    pub metric_key: String,
    pub metric_label: String,
    pub unit: String,
    pub points: Vec<TrendPoint>,
    pub environment_varies: bool,
    pub minimum_sample_count: usize,
    pub has_low_sample_points: bool,
}

/// Compose `loadTrend`: points are stored history in import order, the newest point defines the
/// reference environment, and fewer than two points cannot plot a trend.
pub fn build_trend(option: &MetricOption, points: &[StoredMetricPoint]) -> Option<Trend> {
    if points.len() < 2 {
        return None;
    }
    let newest = points.last()?;
    let referenced_device = newest.device_model.as_deref();
    let referenced_api = newest.api_level;
    let chart_points: Vec<TrendPoint> = points
        .iter()
        .map(|point| TrendPoint {
            label: point_label(point),
            value: point.value,
            sample_count: point.sample_count,
            comparable: point.device_model.as_deref() == referenced_device
                && point.api_level == referenced_api,
        })
        .collect();
    let mut environments: Vec<(&str, Option<u32>)> = points
        .iter()
        .map(|point| (point.device_model.as_deref().unwrap_or(""), point.api_level))
        .collect();
    environments.sort();
    environments.dedup();
    Some(Trend {
        metric_key: option.key.clone(),
        metric_label: option.label(),
        unit: option.unit.clone(),
        environment_varies: environments.len() > 1,
        minimum_sample_count: TREND_MIN_SAMPLES,
        has_low_sample_points: chart_points
            .iter()
            .any(|point| point.sample_count < TREND_MIN_SAMPLES),
        points: chart_points,
    })
}

fn point_label(point: &StoredMetricPoint) -> String {
    if let Some(label) = point
        .build_label
        .as_deref()
        .filter(|value| !value.is_empty())
    {
        return label.to_owned();
    }
    if let Some(commit) = point
        .git_commit
        .as_deref()
        .filter(|value| !value.is_empty())
    {
        return commit.chars().take(7).collect();
    }
    date_prefix(&point.imported_at)
        .map(str::to_owned)
        .unwrap_or_else(|| point.imported_at.clone())
}

/// The page state: Compose `BenchmarkReportsState` with the same derived values.
#[derive(Clone, Default)]
pub struct BenchmarkReportsState {
    pub(crate) import_timing: crate::import_timing::ImportTiming,
    pub run_options: Vec<RunOption>,
    pub baseline_id: Option<String>,
    pub current_id: Option<String>,
    pub baseline: Option<Arc<aps_benchmark::BenchmarkRun>>,
    pub current: Option<Arc<aps_benchmark::BenchmarkRun>>,
    pub report: Option<Arc<RegressionReport>>,
    pub gate: Option<aps_benchmark::ReleaseGateResult>,
    pub regression_policy: aps_benchmark::RegressionPolicy,
    pub gate_policy: aps_benchmark::ReleaseGatePolicy,
    pub metrics: Vec<MetricOption>,
    pub selected_metric: Option<String>,
    pub trend: Option<Trend>,
    pub query: String,
    pub severity_filter: Option<Severity>,
    pub classification_filter: Option<Classification>,
    pub selected: Option<ComparisonKey>,
    pub recent_reports: Vec<BenchmarkReportEntry>,
    pub busy: bool,
    pub message: Option<String>,
    pub error: Option<String>,
}

impl BenchmarkReportsState {
    pub fn comparisons(&self) -> &[MetricComparison] {
        self.report
            .as_ref()
            .map_or(&[], |report| &report.comparisons)
    }

    pub fn compared_count(&self) -> usize {
        self.comparisons().len()
    }

    pub fn regression_count(&self) -> usize {
        self.comparisons()
            .iter()
            .filter(|comparison| comparison.classification == Classification::Regressed)
            .count()
    }

    pub fn improvement_count(&self) -> usize {
        self.comparisons()
            .iter()
            .filter(|comparison| comparison.classification == Classification::Improved)
            .count()
    }

    pub fn stable_count(&self) -> usize {
        self.comparisons()
            .iter()
            .filter(|comparison| comparison.classification == Classification::Stable)
            .count()
    }

    /// Compose `visibleComparisons`: free-text query and both filters operate on the original
    /// comparison order, so a filtered row never loses its source identity.
    pub fn visible_comparisons(&self) -> Vec<&MetricComparison> {
        let query = self.query.trim().to_lowercase();
        self.comparisons()
            .iter()
            .filter(|comparison| {
                let matches_query = query.is_empty()
                    || comparison.case_identity.to_lowercase().contains(&query)
                    || comparison.metric_name.to_lowercase().contains(&query);
                let matches_severity = self
                    .severity_filter
                    .is_none_or(|filter| severity(comparison) == filter);
                let matches_classification = self
                    .classification_filter
                    .is_none_or(|filter| comparison.classification == filter);
                matches_query && matches_severity && matches_classification
            })
            .collect()
    }

    /// Compose `selectedComparison`: the selection is looked up in the full comparison set, so it
    /// survives a filter that hides its row.
    pub fn selected_comparison(&self) -> Option<&MetricComparison> {
        let key = self.selected.as_ref()?;
        self.comparisons()
            .iter()
            .find(|comparison| comparison_key(comparison) == *key)
    }

    /// The evidence the analyzer gated on: current-run raw sample count for this comparison.
    pub fn sample_count(&self, comparison: &MetricComparison) -> Option<usize> {
        self.current.as_ref().and_then(|run| {
            run.case(&comparison.case_identity)
                .and_then(|case| case.metric(&comparison.metric_name))
                .map(|metric| metric.samples.len())
        })
    }

    /// Trace artifacts recorded by the current run for this comparison's case.
    pub fn trace_artifacts(&self, comparison: &MetricComparison) -> Vec<PathBuf> {
        self.current
            .as_ref()
            .and_then(|run| run.case(&comparison.case_identity))
            .map_or_else(Vec::new, |case| case.trace_artifacts.clone())
    }

    /// Compose `compatibilityIssues`: run-level issues plus the case-level issues that belong to
    /// the inspected comparison.
    pub fn compatibility_issues(&self) -> Vec<&CompatibilityIssue> {
        let Some(report) = self.report.as_deref() else {
            return Vec::new();
        };
        let inspected = self.selected_comparison();
        report
            .compatibility_issues
            .iter()
            .filter(|issue| {
                let Some(comparison) = inspected else {
                    return true;
                };
                !issue.field.starts_with("case.") || issue.field.contains(&comparison.case_identity)
            })
            .collect()
    }

    /// A new report or a changed run selection drops a selection that no longer resolves.
    pub fn reconcile_selection(&mut self) {
        if self.selected.as_ref().is_some_and(|key| {
            !self
                .comparisons()
                .iter()
                .any(|comparison| comparison_key(comparison) == *key)
        }) {
            self.selected = None;
        }
    }
}

/// Compose `formatMetricValue`: locale-neutral 4 significant digits, em dash for missing values.
pub fn format_metric_value(value: Option<f64>, unit: &str) -> String {
    match value.filter(|value| value.is_finite()) {
        None => "—".into(),
        Some(value) if unit.trim().is_empty() => format_compact(value),
        Some(value) => format!("{} {}", format_compact(value), unit),
    }
}

/// Compose `DeltaCell`: signed one-decimal percentage with an arrow.
pub fn format_delta(percent: Option<f64>) -> String {
    let Some(percent) = percent.filter(|value| value.is_finite()) else {
        return "—".into();
    };
    let arrow = if percent >= 0.0 { "↑" } else { "↓" };
    let sign = if percent > 0.0 { "+" } else { "" };
    format!("{sign}{percent:.1}% {arrow}")
}

/// Compose `formatBytes` for report sizes.
pub fn format_bytes(bytes: u64) -> String {
    if bytes >= 1024 * 1024 {
        format!("{:.1} MB", bytes as f64 / (1024.0 * 1024.0))
    } else if bytes >= 1024 {
        format!("{:.1} KB", bytes as f64 / 1024.0)
    } else {
        format!("{bytes} B")
    }
}

fn format_compact(value: f64) -> String {
    // Four significant digits, no locale grouping: closest stable %-style formatting available
    // without pulling a printf locale into the UI layer.
    let magnitude = value.abs();
    if magnitude != 0.0 && !(0.0001..1_000_000.0).contains(&magnitude) {
        return format!("{value:.3e}");
    }
    let digits = if magnitude >= 1000.0 {
        0
    } else if magnitude >= 100.0 {
        1
    } else if magnitude >= 10.0 {
        2
    } else {
        3
    };
    // Compose formats with Java's HALF_UP; Rust's formatter rounds half to even, so round the
    // scaled value away from zero first and only then format the already-rounded number.
    let scale = 10f64.powi(digits as i32);
    let rounded = if value < 0.0 {
        -((value.abs() * scale + 0.5).floor() / scale)
    } else {
        (value * scale + 0.5).floor() / scale
    };
    let mut text = format!("{rounded:.digits$}");
    if text.contains('.') {
        while text.ends_with('0') {
            text.pop();
        }
        if text.ends_with('.') {
            text.pop();
        }
    }
    text
}

pub fn file_name(path: &str) -> String {
    Path::new(path)
        .file_name()
        .map(|name| name.to_string_lossy().into_owned())
        .unwrap_or_else(|| path.to_owned())
}

fn date_prefix(imported_at: &str) -> Option<&str> {
    let date = imported_at.get(..10)?;
    if date.len() == 10 && date.as_bytes().get(4) == Some(&b'-') {
        Some(date)
    } else {
        None
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn comparison(
        case_identity: &str,
        metric_name: &str,
        classification: Classification,
    ) -> MetricComparison {
        MetricComparison {
            case_identity: case_identity.into(),
            metric_name: metric_name.into(),
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

    #[test]
    fn severity_follows_verdict_and_evidence_confidence() {
        let mut high = comparison("a#b", "m", Classification::Regressed);
        assert_eq!(severity(&high), Severity::High);
        high.confidence = Confidence::Partial;
        assert_eq!(severity(&high), Severity::Low);
        assert_eq!(
            severity(&comparison("a#b", "m", Classification::Inconclusive)),
            Severity::Unknown
        );
        assert_eq!(
            severity(&comparison("a#b", "m", Classification::Incompatible)),
            Severity::Incompatible
        );
        assert_eq!(
            severity(&comparison("a#b", "m", Classification::Stable)),
            Severity::None
        );
    }

    #[test]
    fn filters_keep_source_order_and_selection_survives_hidden_rows() {
        let mut state = state_with(vec![
            comparison(
                "dev.Example#scroll",
                "frameDurationMs",
                Classification::Stable,
            ),
            comparison("dev.Other#launch", "startupMs", Classification::Regressed),
        ]);
        state.selected = Some(comparison_key(&state.comparisons()[1]));
        state.query = "other".into();
        let visible = state.visible_comparisons();
        assert_eq!(visible.len(), 1);
        assert_eq!(visible[0].case_identity, "dev.Other#launch");
        // The inspector still resolves the selection even while the query hides its row.
        assert_eq!(
            state
                .selected_comparison()
                .map(|comparison| comparison.metric_name.as_str()),
            Some("startupMs")
        );
        state.set_query_for_test("absent");
        assert!(state.visible_comparisons().is_empty());
        state.set_query_for_test("");
        state.classification_filter = Some(Classification::Stable);
        assert_eq!(state.visible_comparisons().len(), 1);
        state.selected = Some(ComparisonKey {
            case_identity: "gone".into(),
            metric: "gone".into(),
        });
        state.reconcile_selection();
        assert!(state.selected.is_none());
    }

    #[test]
    fn trend_marks_comparable_points_mixed_environments_and_low_samples() {
        let option = MetricOption {
            key: metric_key("dev.Example#scroll", "frameDurationMs"),
            case_identity: "dev.Example#scroll".into(),
            metric_name: "frameDurationMs".into(),
            unit: "ms".into(),
        };
        let points = vec![
            StoredMetricPoint {
                run_id: "1".into(),
                imported_at: "2026-11-01T10:00:00Z".into(),
                build_label: Some("1.0".into()),
                git_commit: None,
                device_model: Some("Pixel 8".into()),
                api_level: Some(34),
                value: 10.0,
                sample_count: 2,
            },
            StoredMetricPoint {
                run_id: "2".into(),
                imported_at: "2026-11-02T10:00:00Z".into(),
                build_label: None,
                git_commit: Some("abcdef1234".into()),
                device_model: Some("Pixel 9".into()),
                api_level: Some(35),
                value: 12.0,
                sample_count: 5,
            },
        ];
        let trend = build_trend(&option, &points).expect("two points plot");
        assert_eq!(trend.points.len(), 2);
        assert_eq!(trend.points[0].label, "1.0");
        assert_eq!(trend.points[1].label, "abcdef1");
        assert!(trend.environment_varies);
        assert!(!trend.points[0].comparable);
        assert!(trend.points[1].comparable);
        assert!(trend.has_low_sample_points);
        assert_eq!(trend.minimum_sample_count, TREND_MIN_SAMPLES);
        assert!(build_trend(&option, &points[..1]).is_none());
    }

    #[test]
    fn run_options_use_version_name_or_file_and_keep_availability() {
        let run = StoredBenchmarkRun {
            id: "run".into(),
            source_path: "/tmp/benchmark.json".into(),
            imported_at: "2026-11-08T00:00:00Z".into(),
            device_model: Some("Pixel 9".into()),
            case_count: 3,
            trace_artifact_count: 1,
            baseline_profile_case_count: 0,
            version_name: None,
            git_commit: None,
            api_level: Some(35),
        };
        let option = RunOption::from_stored(&run);
        assert_eq!(option.label, "benchmark.json");
        assert_eq!(option.detail, "Pixel 9 · API 35 · 2026-11-08");
        assert!(!option.available);
    }

    #[test]
    fn formatting_is_locale_neutral_and_marks_missing_values() {
        assert_eq!(format_metric_value(None, "ms"), "—");
        assert_eq!(format_metric_value(Some(10.0), "ms"), "10 ms");
        assert_eq!(format_metric_value(Some(1234.5), ""), "1235");
        assert_eq!(format_delta(Some(12.34)), "+12.3% ↑");
        assert_eq!(format_delta(Some(-2.0)), "-2.0% ↓");
        assert_eq!(format_delta(None), "—");
        assert_eq!(format_bytes(2048), "2.0 KB");
    }
}

impl BenchmarkReportsState {
    #[cfg(test)]
    fn set_query_for_test(&mut self, query: &str) {
        self.query = query.to_owned();
    }
}

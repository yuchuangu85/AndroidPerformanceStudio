//! Benchmark Regression workspace: Compose `benchmark-regression` parity for the Benchmark route.
//!
//! The Compose page (BenchmarkReportsScreen + BenchmarkRegressionMainPage) is the behavior oracle:
//! persistent run history and metric trends, baseline/current selection, comparison table with
//! severity/classification filters, release gate summary, exported-report catalogue, PDF/CSV report
//! exports, in-app Macrobenchmark orchestration and a bounded AI context. This module owns the
//! runtime/state machine; `page`, `table`, `trend`, `rail` and `dialogs` own presentation.

mod ai;
mod dialogs;
mod model;
mod page;
mod rail;
mod table;
mod trend;

use crate::import_timing::ImportTiming;
use crate::notifications::{NoticeSnapshot, NoticeSource};
use aps_ai::AnalysisRequest;
use aps_benchmark::{
    BenchmarkReportIndex, BenchmarkRun, BenchmarkStore, MacrobenchmarkPlan, RegressionPolicy,
    ReleaseGatePolicy, build_command, compare, evaluate_gate, locate_benchmark_result,
    run_macrobenchmark, write_csv, write_pdf,
};
use aps_ui_foundation::{Language, TextKey};
use gpui_kit::{prelude::*, *};
use std::{
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, Instant},
};

const STORE_FILE: &str = "benchmark.db";
const REPORT_DIRECTORY: &str = "reports";
const REPORT_INDEX_FILE: &str = "index.json";
const MAX_IMPORT_BYTES: u64 = 64 * 1024 * 1024;
const RUN_OPTION_LIMIT: usize = 50;
const METRIC_OPTION_LIMIT: usize = 200;
const MAX_ORCHESTRATION_OUTPUT: usize = 1_000_000;
const OUTPUT_POLL_INTERVAL: Duration = Duration::from_millis(400);

/// The bounded initial trend projection is prepared with the imported run, so
/// timing does not stop at parsing while selector/history work is still pending.
struct MetricPublication {
    metrics: Vec<model::MetricOption>,
    selected: Option<String>,
    trend: Option<model::Trend>,
}

impl MetricPublication {
    fn read(store: &BenchmarkStore, wanted: Option<String>) -> Result<Self, String> {
        let stored = store.list_metric_keys(METRIC_OPTION_LIMIT)?;
        let metrics: Vec<model::MetricOption> = stored
            .iter()
            .map(model::MetricOption::from_stored)
            .collect();
        let selected = wanted
            .filter(|key| metrics.iter().any(|option| &option.key == key))
            .or_else(|| metrics.first().map(|option| option.key.clone()));
        let trend = selected
            .as_ref()
            .and_then(|key| metrics.iter().find(|option| &option.key == key))
            .map(|option| {
                store
                    .metric_history(
                        &option.case_identity,
                        &option.metric_name,
                        model::TREND_WINDOW,
                    )
                    .map(|points| model::build_trend(option, &points))
            })
            .transpose()?
            .flatten();
        Ok(Self {
            metrics,
            selected,
            trend,
        })
    }

    fn publish(self, state: &mut model::BenchmarkReportsState) {
        state.metrics = self.metrics;
        state.selected_metric = self.selected;
        state.trend = self.trend;
    }
}

/// Emitted when the page asks the shell to show a benchmark trace artifact.
#[derive(Clone, Debug)]
pub struct OpenBenchmarkTrace(pub Option<PathBuf>);

/// Live Macrobenchmark process state. The dialog renders it; this workspace owns it because the
/// process and its cancellation/timeout live here.
pub(in crate::benchmark) struct OrchestrationRun {
    generation: u64,
    pub running: bool,
    pub output: Arc<Mutex<String>>,
    pub cancel: Arc<AtomicBool>,
    pub status: Option<String>,
}

impl Default for OrchestrationRun {
    fn default() -> Self {
        Self {
            generation: 0,
            running: false,
            output: Arc::new(Mutex::new(String::new())),
            cancel: Arc::new(AtomicBool::new(false)),
            status: None,
        }
    }
}

pub struct BenchmarkWorkspace {
    selected_device_serial: Option<String>,
    pub(in crate::benchmark) language: Language,
    /// `<settings parent>/benchmark`: run store, report index and exported reports live here.
    pub(in crate::benchmark) data_dir: Option<PathBuf>,
    pub(in crate::benchmark) state: model::BenchmarkReportsState,
    pub(in crate::benchmark) ui: page::PageUi,
    pub(in crate::benchmark) dialogs: dialogs::DialogsUi,
    pub(in crate::benchmark) orchestration: OrchestrationRun,
    pub(in crate::benchmark) studio: Option<WeakEntity<crate::shell::Studio>>,
    /// Debug-only verification seam: the run to select after the demo baseline loads.
    #[allow(dead_code)]
    pub(in crate::benchmark) debug_demo_current: Option<String>,
    pub(in crate::benchmark) options_generation: u64,
    pub(in crate::benchmark) trend_generation: u64,
    pub(in crate::benchmark) load_generation: u64,
}

impl BenchmarkWorkspace {
    pub fn new(
        language: Language,
        settings_path: Option<&Path>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Self {
        let data_dir = settings_path
            .and_then(Path::parent)
            .map(|root| root.join("benchmark"));
        let mut this = Self {
            selected_device_serial: None,
            language,
            data_dir,
            state: model::BenchmarkReportsState::default(),
            ui: page::PageUi::new(window, cx),
            dialogs: dialogs::DialogsUi::new(window, cx),
            orchestration: OrchestrationRun::default(),
            studio: None,
            debug_demo_current: None,
            options_generation: 0,
            trend_generation: 0,
            load_generation: 0,
        };
        this.refresh_options(cx);
        this.refresh_metrics(cx);
        #[cfg(debug_assertions)]
        this.maybe_debug_demo(cx);
        this
    }

    /// Debug-only verification seams after a report lands: select a row, open a dialog or export
    /// demo reports so a native UI check can capture those states without native input.
    #[cfg(debug_assertions)]
    fn maybe_debug_after_report(&mut self, cx: &mut Context<Self>) {
        if std::env::var_os("APS_RUST_BENCHMARK_DEMO_SELECT").is_some() {
            if let Some(first) = self.state.comparisons().first() {
                self.state.selected = Some(model::comparison_key(first));
            }
        }
        match std::env::var("APS_RUST_BENCHMARK_DIALOG").as_deref() {
            Ok("compare") => self
                .dialogs
                .open_compare(&self.state.regression_policy, &self.state.gate_policy),
            Ok("orchestrate") => self.dialogs.open_orchestration(),
            _ => {}
        }
        if std::env::var_os("APS_RUST_BENCHMARK_EXPORT").is_some() {
            self.debug_export_reports(cx);
        }
        cx.notify();
    }

    #[cfg(debug_assertions)]
    fn debug_export_reports(&mut self, cx: &mut Context<Self>) {
        let (Some(report), Some(gate), Some(index)) = (
            self.state.report.clone(),
            self.state.gate.clone(),
            self.report_index(),
        ) else {
            return;
        };
        let directory = self.export_directory();
        if std::fs::create_dir_all(&directory).is_err() {
            return;
        }
        let title = report_title(&self.state);
        let pdf = directory.join("benchmark-regression.pdf");
        let csv = directory.join("benchmark-regression.csv");
        let mut entries = Vec::new();
        if write_pdf(&report, &gate, &pdf).is_ok() {
            if let Ok(recorded) = index.record(&pdf, &title, "PDF") {
                entries = recorded;
            }
        }
        if write_csv(&report, Some(&gate), &csv).is_ok() {
            if let Ok(recorded) = index.record(&csv, &title, "CSV") {
                entries = recorded;
            }
        }
        self.state.recent_reports = entries;
        cx.notify();
    }

    /// Debug-only verification seam: select the oldest run as baseline and the newest as current
    /// so a native UI check can capture a populated report without driving the native dropdowns.
    /// Release builds never read the variable.
    #[cfg(debug_assertions)]
    fn maybe_debug_demo(&mut self, cx: &mut Context<Self>) {
        if std::env::var_os("APS_RUST_BENCHMARK_DEMO").is_none() {
            return;
        }
        cx.spawn(async move |this, cx| {
            for _ in 0..60 {
                let pair = this
                    .update(cx, |this, _| {
                        (
                            this.state
                                .run_options
                                .first()
                                .map(|option| option.id.clone()),
                            this.state
                                .run_options
                                .last()
                                .map(|option| option.id.clone()),
                        )
                    })
                    .ok();
                if let Some((Some(newest), Some(oldest))) = pair {
                    this.update(cx, |this, cx| {
                        this.debug_demo_current = Some(newest);
                        this.select_baseline(oldest, cx);
                    })
                    .ok();
                    return;
                }
                cx.background_executor()
                    .timer(Duration::from_millis(100))
                    .await;
            }
        })
        .detach();
    }

    pub fn set_studio(&mut self, studio: WeakEntity<crate::shell::Studio>) {
        self.studio = Some(studio);
    }

    pub fn set_language(
        &mut self,
        language: Language,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        self.language = language;
        self.ui.set_language(language, window, cx);
        self.dialogs.set_language(language, window, cx);
        cx.notify();
    }

    pub(crate) fn analysis_request(&self) -> Result<AnalysisRequest, String> {
        ai::analysis_request(&self.state)
    }

    fn store_path(&self) -> Option<PathBuf> {
        self.data_dir.as_ref().map(|dir| dir.join(STORE_FILE))
    }

    fn report_index_path(&self) -> Option<PathBuf> {
        self.data_dir
            .as_ref()
            .map(|dir| dir.join(REPORT_DIRECTORY).join(REPORT_INDEX_FILE))
    }

    fn report_index(&self) -> Option<BenchmarkReportIndex> {
        self.report_index_path().map(BenchmarkReportIndex::new)
    }

    fn export_directory(&self) -> PathBuf {
        self.data_dir
            .clone()
            .unwrap_or_else(|| std::env::temp_dir())
            .join(REPORT_DIRECTORY)
    }

    fn no_persistence(&mut self, cx: &mut Context<Self>) {
        self.state.error = Some(self.text(TextKey::BenchmarkPersistenceUnavailable));
        cx.notify();
    }

    fn text(&self, key: TextKey) -> String {
        self.language.text(key).to_owned()
    }

    fn format(&self, key: TextKey, args: &[(&str, &dyn std::fmt::Display)]) -> String {
        self.language.format(key, args)
    }

    // ---------------- run catalogue / metric history ----------------

    /// Compose `refreshOptions`: reload the stored run catalogue and the exported-report index.
    fn refresh_options(&mut self, cx: &mut Context<Self>) {
        let Some(store_path) = self.store_path() else {
            self.no_persistence(cx);
            return;
        };
        let Some(report_path) = self.report_index_path() else {
            return;
        };
        self.options_generation += 1;
        let generation = self.options_generation;
        let task = cx.background_executor().spawn(async move {
            let store = BenchmarkStore::open(&store_path)?;
            let runs = store.list_recent(RUN_OPTION_LIMIT)?;
            let reports = BenchmarkReportIndex::new(report_path).load();
            Ok::<_, String>((runs, reports))
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.options_generation != generation {
                    return;
                }
                match result {
                    Ok((runs, reports)) => {
                        this.state.run_options =
                            runs.iter().map(model::RunOption::from_stored).collect();
                        this.state.recent_reports = reports;
                        this.state.error = None;
                    }
                    Err(error) => this.state.error = Some(error),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Compose `refreshTrend`: reload stored metric keys and the trend of the selected metric.
    fn refresh_metrics(&mut self, cx: &mut Context<Self>) {
        let Some(store_path) = self.store_path() else {
            return;
        };
        self.trend_generation += 1;
        let generation = self.trend_generation;
        let wanted = self.state.selected_metric.clone();
        let task = cx.background_executor().spawn(async move {
            let store = BenchmarkStore::open(&store_path)?;
            MetricPublication::read(&store, wanted)
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.trend_generation != generation {
                    return;
                }
                match result {
                    Ok(metrics) => metrics.publish(&mut this.state),
                    Err(error) => this.state.error = Some(error),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    // ---------------- import / selection / comparison ----------------

    pub(crate) fn import_run(&mut self, cx: &mut Context<Self>) {
        if self.state.busy {
            return;
        }
        if self.store_path().is_none() {
            self.no_persistence(cx);
            return;
        }
        let prompt: SharedString = self
            .text(TextKey::BenchmarkImportAndroidxBenchmarkJson)
            .into();
        let picker = cx.prompt_for_paths(PathPromptOptions {
            files: true,
            directories: false,
            multiple: false,
            prompt: Some(prompt),
        });
        cx.spawn(async move |this, cx| {
            let result = picker.await;
            this.update(cx, |this, cx| match result {
                Ok(Ok(Some(paths))) if paths.len() == 1 => {
                    this.import_benchmark_path(paths[0].clone(), cx)
                }
                Ok(Err(error)) => {
                    this.state.error = Some(format!("{error}"));
                    cx.notify();
                }
                _ => {}
            })
            .ok();
        })
        .detach();
    }

    /// Parse one AndroidX Benchmark JSON, store it in the run catalogue and refresh the selectors.
    fn import_benchmark_path(&mut self, path: PathBuf, cx: &mut Context<Self>) {
        let Some(store_path) = self.store_path() else {
            self.no_persistence(cx);
            return;
        };
        let Some(report_path) = self.report_index_path() else {
            return;
        };
        self.state.busy = true;
        let started = Instant::now();
        self.state.message = None;
        self.state.error = None;
        let generation = {
            self.options_generation += 1;
            self.options_generation
        };
        self.trend_generation += 1;
        let trend_generation = self.trend_generation;
        let wanted = self.state.selected_metric.clone();
        let task = cx.background_executor().spawn(async move {
            let run = BenchmarkRun::open(&path, MAX_IMPORT_BYTES)?;
            let store = BenchmarkStore::open(&store_path)?;
            store.save(&run)?;
            let runs = store
                .list_recent(RUN_OPTION_LIMIT)?
                .iter()
                .map(model::RunOption::from_stored)
                .collect::<Vec<_>>();
            let reports = BenchmarkReportIndex::new(report_path).load();
            let metrics = MetricPublication::read(&store, wanted)?;
            Ok::<_, String>((run, runs, reports, metrics))
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.options_generation != generation {
                    return;
                }
                this.state.busy = false;
                match result {
                    Ok((run, runs, reports, metrics)) => {
                        this.state.run_options = runs;
                        this.state.recent_reports = reports;
                        if this.trend_generation == trend_generation {
                            metrics.publish(&mut this.state);
                        }
                        this.state.message = Some(this.format(
                            TextKey::BenchmarkImportedCurrent,
                            &[("label", &display_name(&run.source_path))],
                        ));
                        this.state.error = None;
                        this.state.import_timing = ImportTiming::completed(started);
                    }
                    Err(error) => {
                        this.state.error =
                            Some(this.format(TextKey::BenchmarkImportFailed, &[("value", &error)]))
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    pub(in crate::benchmark) fn select_baseline(&mut self, id: String, cx: &mut Context<Self>) {
        self.load_run(id, true, cx);
    }

    pub(in crate::benchmark) fn select_current(&mut self, id: String, cx: &mut Context<Self>) {
        self.load_run(id, false, cx);
    }

    /// Compose `loadRun`: resolve the stored option, reparse its source JSON and compare when both
    /// sides are available.
    fn load_run(&mut self, id: String, as_baseline: bool, cx: &mut Context<Self>) {
        if self.state.busy {
            return;
        }
        let Some(option) = self
            .state
            .run_options
            .iter()
            .find(|option| option.id == id)
            .cloned()
        else {
            return;
        };
        if !option.available {
            self.state.error = Some(self.format(
                TextKey::BenchmarkReportsMissingSource,
                &[("path", &option.source_path)],
            ));
            cx.notify();
            return;
        }
        self.state.busy = true;
        self.load_generation += 1;
        let generation = self.load_generation;
        let path = PathBuf::from(&option.source_path);
        let started = Instant::now();
        let opposite = if as_baseline {
            self.state.current.clone()
        } else {
            self.state.baseline.clone()
        };
        let policy = self.state.regression_policy.clone();
        let gate_policy = self.state.gate_policy.clone();
        let task_policy = policy.clone();
        let task_gate_policy = gate_policy.clone();
        let store_path = self.store_path();
        let wanted = self.state.selected_metric.clone();
        self.trend_generation += 1;
        let trend_generation = self.trend_generation;
        let task = cx.background_executor().spawn(async move {
            let run = Arc::new(BenchmarkRun::open(&path, MAX_IMPORT_BYTES)?);
            let comparison = opposite
                .map(|opposite| {
                    let report = if as_baseline {
                        compare(&run, &opposite, &task_policy)?
                    } else {
                        compare(&opposite, &run, &task_policy)?
                    };
                    let gate = evaluate_gate(&report, &task_gate_policy);
                    Ok::<_, String>((Arc::new(report), gate))
                })
                .transpose()?;
            let metrics = store_path
                .map(|path| {
                    let store = BenchmarkStore::open(&path)?;
                    MetricPublication::read(&store, wanted)
                })
                .transpose()?;
            Ok::<_, String>((run, comparison, metrics))
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.load_generation != generation {
                    return;
                }
                this.state.busy = false;
                match result {
                    Ok((run, comparison, metrics)) => {
                        if as_baseline {
                            this.state.baseline_id = Some(id);
                            this.state.baseline = Some(run);
                        } else {
                            this.state.current_id = Some(id);
                            this.state.current = Some(run);
                        }
                        if this.trend_generation == trend_generation {
                            if let Some(metrics) = metrics {
                                metrics.publish(&mut this.state);
                            }
                        }
                        let policy_changed = this.state.regression_policy != policy
                            || this.state.gate_policy != gate_policy;
                        if let Some((report, gate)) = comparison {
                            if !policy_changed {
                                this.state.report = Some(report);
                                this.state.gate = Some(gate);
                                this.state.reconcile_selection();
                            }
                        }
                        this.state.error = None;
                        this.state.import_timing = ImportTiming::completed(started);
                        #[cfg(debug_assertions)]
                        if as_baseline {
                            if let Some(current) = this.debug_demo_current.take() {
                                this.select_current(current, cx);
                                return;
                            }
                        }
                        if policy_changed
                            && this.state.baseline.is_some()
                            && this.state.current.is_some()
                        {
                            // A later user policy edit is separate work, not part
                            // of the initial import whose projection is now ready.
                            this.compare(cx);
                        } else {
                            #[cfg(debug_assertions)]
                            if this.state.report.is_some() {
                                this.maybe_debug_after_report(cx);
                            }
                            cx.notify();
                        }
                    }
                    Err(error) => {
                        this.state.error = Some(error);
                        cx.notify();
                    }
                }
            })
            .ok();
        })
        .detach();
    }

    /// Compare the selected runs with the current policies and evaluate the release gate.
    pub(in crate::benchmark) fn compare(&mut self, cx: &mut Context<Self>) {
        if self.state.busy {
            return;
        }
        let (Some(baseline), Some(current)) =
            (self.state.baseline.clone(), self.state.current.clone())
        else {
            return;
        };
        let policy = self.state.regression_policy.clone();
        let gate_policy = self.state.gate_policy.clone();
        self.state.busy = true;
        self.load_generation += 1;
        let generation = self.load_generation;
        let task = cx.background_executor().spawn(async move {
            let report = compare(&baseline, &current, &policy)?;
            let gate = evaluate_gate(&report, &gate_policy);
            Ok::<_, String>((report, gate))
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.load_generation != generation {
                    return;
                }
                this.state.busy = false;
                match result {
                    Ok((report, gate)) => {
                        this.state.report = Some(Arc::new(report));
                        this.state.gate = Some(gate);
                        this.state.error = None;
                        this.state.reconcile_selection();
                        this.refresh_metrics(cx);
                        #[cfg(debug_assertions)]
                        this.maybe_debug_after_report(cx);
                    }
                    Err(error) => {
                        this.state.error = Some(
                            this.format(TextKey::BenchmarkCompareFailed, &[("value", &error)]),
                        );
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    pub(in crate::benchmark) fn open_compare_settings(&mut self, cx: &mut Context<Self>) {
        self.dialogs
            .open_compare(&self.state.regression_policy, &self.state.gate_policy);
        cx.notify();
    }

    pub(in crate::benchmark) fn close_compare_settings(&mut self, cx: &mut Context<Self>) {
        self.dialogs.close_compare();
        cx.notify();
    }

    /// Apply validated policies. The dialog is the only place where a gate decision can change.
    pub(in crate::benchmark) fn apply_compare_settings(
        &mut self,
        regression: RegressionPolicy,
        gate: ReleaseGatePolicy,
        cx: &mut Context<Self>,
    ) {
        self.dialogs.close_compare();
        self.state.regression_policy = regression;
        self.state.gate_policy = gate;
        if self.state.baseline.is_some() && self.state.current.is_some() {
            self.compare(cx);
        } else {
            cx.notify();
        }
    }

    // ---------------- filters / selection ----------------

    pub(in crate::benchmark) fn select_metric(&mut self, key: String, cx: &mut Context<Self>) {
        self.state.selected_metric = Some(key);
        self.refresh_metrics(cx);
        cx.notify();
    }

    pub(in crate::benchmark) fn set_query(&mut self, query: String, cx: &mut Context<Self>) {
        self.state.query = query;
        cx.notify();
    }

    pub(in crate::benchmark) fn set_severity_filter(
        &mut self,
        filter: Option<model::Severity>,
        cx: &mut Context<Self>,
    ) {
        self.state.severity_filter = filter;
        cx.notify();
    }

    pub(in crate::benchmark) fn set_classification_filter(
        &mut self,
        filter: Option<aps_benchmark::Classification>,
        cx: &mut Context<Self>,
    ) {
        self.state.classification_filter = filter;
        cx.notify();
    }

    pub(in crate::benchmark) fn select_comparison(
        &mut self,
        key: Option<model::ComparisonKey>,
        cx: &mut Context<Self>,
    ) {
        self.state.selected = key;
        cx.notify();
    }

    // ---------------- reports / exports ----------------

    pub(in crate::benchmark) fn export_pdf(&mut self, cx: &mut Context<Self>) {
        self.export_report(true, cx);
    }

    pub(in crate::benchmark) fn export_csv(&mut self, cx: &mut Context<Self>) {
        self.export_report(false, cx);
    }

    /// Compose `exportReport`: write the report, then record it in the report catalogue.
    fn export_report(&mut self, pdf: bool, cx: &mut Context<Self>) {
        let (Some(report), Some(gate)) = (self.state.report.clone(), self.state.gate.clone())
        else {
            return;
        };
        let directory = self.export_directory();
        if let Err(error) = std::fs::create_dir_all(&directory) {
            self.state.error = Some(format!("{error}"));
            cx.notify();
            return;
        }
        let file_name = if pdf {
            "benchmark-regression.pdf"
        } else {
            "benchmark-regression.csv"
        };
        let dialog = cx.prompt_for_new_path(&directory, Some(file_name));
        let title = report_title(&self.state);
        cx.spawn(async move |this, cx| {
            let result = dialog.await;
            this.update(cx, |this, cx| match result {
                Ok(Ok(Some(path))) => {
                    let written = if pdf {
                        write_pdf(&report, &gate, &path)
                    } else {
                        write_csv(&report, Some(&gate), &path)
                    };
                    match written {
                        Ok(()) => {
                            let mut index_error = None;
                            if let Some(index) = this.report_index() {
                                match index.record(&path, &title, if pdf { "PDF" } else { "CSV" }) {
                                    Ok(entries) => this.state.recent_reports = entries,
                                    Err(error) => index_error = Some(error),
                                }
                            }
                            if let Some(error) = index_error {
                                this.state.error = Some(error);
                            }
                            let name = display_name(&path);
                            this.state.message = Some(
                                this.format(TextKey::BenchmarkReportsExported, &[("path", &name)]),
                            );
                            this.state.error = None;
                        }
                        Err(error) => this.state.error = Some(error),
                    }
                    cx.notify();
                }
                Ok(Err(error)) => {
                    this.state.error = Some(format!("{error}"));
                    cx.notify();
                }
                _ => {}
            })
            .ok();
        })
        .detach();
    }

    /// Compose `revealReport`: reveal the newest exported report in the file manager.
    pub(in crate::benchmark) fn share_report(&mut self, cx: &mut Context<Self>) {
        let Some(entry) = self.state.recent_reports.first().cloned() else {
            return;
        };
        let path = PathBuf::from(&entry.path);
        if !path.exists() {
            self.state.error = Some(self.format(
                TextKey::BenchmarkReportsMissingSource,
                &[("path", &entry.path)],
            ));
            cx.notify();
            return;
        }
        cx.reveal_path(&path);
        self.state.message =
            Some(self.format(TextKey::BenchmarkReportRevealed, &[("path", &entry.path)]));
        cx.notify();
    }

    pub(in crate::benchmark) fn open_report(&mut self, path: String, cx: &mut Context<Self>) {
        let target = PathBuf::from(&path);
        if !target.is_file() {
            self.state.error =
                Some(self.format(TextKey::BenchmarkReportsMissingSource, &[("path", &path)]));
            cx.notify();
            return;
        }
        if let Err(error) = open_external(&target) {
            self.state.error = Some(error);
            cx.notify();
        }
    }

    /// Compose `openCurrentTrace`: hand the current run's first recorded trace to the shell.
    pub(in crate::benchmark) fn open_trace(&mut self, cx: &mut Context<Self>) {
        let path = self.state.current.as_ref().and_then(|run| {
            run.cases()
                .iter()
                .flat_map(|case| case.trace_artifacts.iter())
                .next()
                .cloned()
        });
        if path.is_some() {
            cx.emit(OpenBenchmarkTrace(path));
        }
    }

    // ---------------- orchestration ----------------

    pub(crate) fn set_shared_device(&mut self, serial: Option<String>, cx: &mut Context<Self>) {
        if self.selected_device_serial != serial {
            self.orchestration.generation = self.orchestration.generation.wrapping_add(1);
            // Stop only the owned Gradle process when the approved target changes.
            if self.orchestration.running {
                self.orchestration.cancel.store(true, Ordering::SeqCst);
            }
            self.selected_device_serial = serial;
            cx.notify();
        }
    }

    pub(in crate::benchmark) fn orchestration_open(&mut self, cx: &mut Context<Self>) {
        if self.orchestration.running {
            return;
        }
        self.dialogs.open_orchestration();
        cx.notify();
    }

    pub(in crate::benchmark) fn orchestration_close(&mut self, cx: &mut Context<Self>) {
        if self.orchestration.running {
            return;
        }
        self.dialogs.close_orchestration();
        cx.notify();
    }

    /// Run the same plan the APS CLI macro-run executes, streaming output into the dialog.
    pub(in crate::benchmark) fn start_orchestration(
        &mut self,
        mut plan: MacrobenchmarkPlan,
        result_json: Option<String>,
        timeout_secs: u64,
        cx: &mut Context<Self>,
    ) {
        if self.orchestration.running {
            return;
        }
        let Some(serial) = self.selected_device_serial.clone() else {
            self.orchestration.status = Some(
                self.text(TextKey::BenchmarkSelectDeviceBeforeRun)
                    .to_string(),
            );
            cx.notify();
            return;
        };
        plan.device_serial = Some(serial);
        let command = match build_command(&plan) {
            Ok(command) => command,
            Err(error) => {
                self.orchestration.status = Some(error);
                cx.notify();
                return;
            }
        };
        self.orchestration.generation = self.orchestration.generation.wrapping_add(1);
        let generation = self.orchestration.generation;
        self.orchestration.running = true;
        self.orchestration.cancel.store(false, Ordering::SeqCst);
        if let Ok(mut buffer) = self.orchestration.output.lock() {
            buffer.clear();
        }
        self.orchestration.status = None;
        cx.notify();
        let output = self.orchestration.output.clone();
        let cancel = self.orchestration.cancel.clone();
        let project_root = plan.project_root.clone();
        let timeout = Duration::from_secs(timeout_secs.max(1));
        let task = cx.background_executor().spawn(async move {
            let mut sink = move |chunk: &str| {
                if let Ok(mut buffer) = output.lock() {
                    if buffer.len() < MAX_ORCHESTRATION_OUTPUT {
                        buffer.push_str(chunk);
                    }
                }
            };
            run_macrobenchmark(&command, timeout, cancel, &mut sink)
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                this.orchestration.running = false;
                if this.orchestration.generation != generation {
                    this.orchestration.status =
                        Some(this.text(TextKey::BenchmarkOrchestrateCancelled).to_owned());
                    cx.notify();
                    return;
                }
                let status = if result.cancelled {
                    this.text(TextKey::BenchmarkOrchestrateCancelled)
                } else if result.timed_out {
                    this.text(TextKey::BenchmarkOrchestrateTimedOut)
                } else {
                    this.format(
                        TextKey::BenchmarkOrchestrateFinished,
                        &[("code", &result.exit_code)],
                    )
                };
                this.orchestration.status = Some(status);
                cx.notify();
                if result.succeeded() {
                    this.import_orchestration_result(project_root, result_json, cx);
                }
            })
            .ok();
        })
        .detach();
        self.schedule_orchestration_poll(cx);
    }

    fn schedule_orchestration_poll(&mut self, cx: &mut Context<Self>) {
        if !self.orchestration.running {
            return;
        }
        cx.spawn(async move |this, cx| {
            cx.background_executor().timer(OUTPUT_POLL_INTERVAL).await;
            this.update(cx, |this, cx| {
                if !this.orchestration.running {
                    return;
                }
                cx.notify();
                this.schedule_orchestration_poll(cx);
            })
            .ok();
        })
        .detach();
    }

    pub(in crate::benchmark) fn cancel_orchestration(&mut self, cx: &mut Context<Self>) {
        self.orchestration.cancel.store(true, Ordering::SeqCst);
        cx.notify();
    }

    /// Compose `importOrchestrationResult`: locate, parse and store the JSON the run produced.
    fn import_orchestration_result(
        &mut self,
        project_root: PathBuf,
        explicit: Option<String>,
        cx: &mut Context<Self>,
    ) {
        let Some(store_path) = self.store_path() else {
            self.no_persistence(cx);
            return;
        };
        self.options_generation += 1;
        let generation = self.options_generation;
        let task = cx.background_executor().spawn(async move {
            let located = locate_benchmark_result(&project_root, explicit.as_deref())
                .ok_or_else(|| "no benchmark JSON was produced".to_owned())?;
            let run = BenchmarkRun::open(&located, MAX_IMPORT_BYTES)?;
            let store = BenchmarkStore::open(&store_path)?;
            store.save(&run)?;
            Ok::<_, String>((run, located))
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.options_generation != generation {
                    return;
                }
                match result {
                    Ok((run, located)) => {
                        // This is generated capture output, not a user import.
                        this.state.import_timing.clear();
                        this.orchestration.status = Some(this.format(
                            TextKey::BenchmarkOrchestrateImported,
                            &[("path", &display_name(&located))],
                        ));
                        this.state.current_id = Some(run.id.clone());
                        this.state.current = Some(Arc::new(run));
                        this.state.error = None;
                        this.refresh_options(cx);
                        this.refresh_metrics(cx);
                        if this.state.baseline.is_some() {
                            this.compare(cx);
                        } else {
                            cx.notify();
                        }
                    }
                    Err(error) => {
                        this.orchestration.status =
                            Some(this.text(TextKey::BenchmarkOrchestrateResultMissing));
                        this.state.error = Some(error);
                        cx.notify();
                    }
                }
            })
            .ok();
        })
        .detach();
    }
}

fn report_title(state: &model::BenchmarkReportsState) -> String {
    let current = state
        .current
        .as_ref()
        .map(|run| run_label(run))
        .unwrap_or_default();
    let baseline = state
        .baseline
        .as_ref()
        .map(|run| run_label(run))
        .unwrap_or_default();
    format!("{current} vs {baseline}")
}

fn run_label(run: &BenchmarkRun) -> String {
    run.build
        .version_name
        .clone()
        .filter(|value| !value.trim().is_empty())
        .unwrap_or_else(|| display_name(&run.source_path))
}

fn display_name(path: &Path) -> String {
    path.file_name()
        .map(|name| name.to_string_lossy().into_owned())
        .unwrap_or_else(|| path.to_string_lossy().into_owned())
}

fn open_external(path: &Path) -> Result<(), String> {
    #[cfg(target_os = "macos")]
    let mut command = std::process::Command::new("open");
    #[cfg(target_os = "windows")]
    let mut command = {
        let mut command = std::process::Command::new("cmd");
        command.arg("/C").arg("start").arg("");
        command
    };
    #[cfg(all(unix, not(target_os = "macos")))]
    let mut command = std::process::Command::new("xdg-open");
    command.arg(path);
    command
        .spawn()
        .map(|_| ())
        .map_err(|error| format!("{error}"))
}

// ---------------- menu bar ----------------

#[derive(Clone, PartialEq, Eq)]
pub(crate) enum BenchmarkMenuCommand {
    ImportRun,
    RunBenchmark,
    Compare,
    ExportPdf,
    ExportCsv,
    ShareReport,
    CompareSettings,
    OpenTrace,
}

impl crate::shell::menubar::MenuHost for BenchmarkWorkspace {
    type Command = BenchmarkMenuCommand;

    fn menu_groups(
        &self,
        _language: Language,
    ) -> Vec<crate::shell::menubar::MenuGroup<Self::Command>> {
        use crate::shell::menubar::MenuGroup;

        let busy = self.state.busy;
        let has_report = self.state.report.is_some();
        let has_reports = !self.state.recent_reports.is_empty();
        let has_trace = self.state.current.as_ref().is_some_and(|run| {
            run.cases()
                .iter()
                .any(|case| !case.trace_artifacts.is_empty())
        });

        // Compose's Benchmark page publishes no page menu; the Rust window menu keeps the
        // route-aware Open row (Cmd+O imports a run) and exposes the page's real actions.
        let file = MenuGroup::new(TextKey::ShellMenuFile)
            .item_action_when(
                !busy,
                TextKey::ShellMenuOpen,
                BenchmarkMenuCommand::ImportRun,
                crate::shell::OpenActiveRouteFile,
            )
            .item_when(
                !busy,
                TextKey::BenchmarkImportRun,
                BenchmarkMenuCommand::ImportRun,
            )
            .item_when(
                !busy && !self.orchestration.running,
                TextKey::BenchmarkOrchestrateRun,
                BenchmarkMenuCommand::RunBenchmark,
            )
            .separator()
            .item_when(
                !busy && self.state.baseline_id.is_some() && self.state.current_id.is_some(),
                TextKey::BenchmarkCompare,
                BenchmarkMenuCommand::Compare,
            )
            .submenu(TextKey::ShellMenuExport, has_report, |menu| {
                menu.item_when(
                    has_report,
                    TextKey::BenchmarkExportPdf,
                    BenchmarkMenuCommand::ExportPdf,
                )
                .item_when(
                    has_report,
                    TextKey::BenchmarkExportCsv,
                    BenchmarkMenuCommand::ExportCsv,
                )
            })
            .separator()
            .item_when(
                has_reports,
                TextKey::BenchmarkShareReport,
                BenchmarkMenuCommand::ShareReport,
            )
            .item_when(
                has_trace,
                TextKey::BenchmarkOpenTraceInPerfetto,
                BenchmarkMenuCommand::OpenTrace,
            )
            .item(
                TextKey::BenchmarkCompareSettings,
                BenchmarkMenuCommand::CompareSettings,
            );
        vec![file]
    }

    fn run_menu_command(
        &mut self,
        command: Self::Command,
        _window: &mut gpui_kit::Window,
        cx: &mut gpui_kit::Context<Self>,
    ) {
        match command {
            BenchmarkMenuCommand::ImportRun => self.import_run(cx),
            BenchmarkMenuCommand::RunBenchmark => self.orchestration_open(cx),
            BenchmarkMenuCommand::Compare => self.compare(cx),
            BenchmarkMenuCommand::ExportPdf => self.export_pdf(cx),
            BenchmarkMenuCommand::ExportCsv => self.export_csv(cx),
            BenchmarkMenuCommand::ShareReport => self.share_report(cx),
            BenchmarkMenuCommand::CompareSettings => self.open_compare_settings(cx),
            BenchmarkMenuCommand::OpenTrace => self.open_trace(cx),
        }
    }
}

impl EventEmitter<OpenBenchmarkTrace> for BenchmarkWorkspace {}

impl Render for BenchmarkWorkspace {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let page = page::render(&self.state, &mut self.ui, self.language, window, cx);
        let overlay = dialogs::overlay(
            &mut self.dialogs,
            &self.state,
            &self.orchestration,
            self.language,
            window,
            cx,
        );
        div().relative().size_full().child(page).children(overlay)
    }
}

impl NoticeSource for BenchmarkWorkspace {
    fn notice_snapshot(&self) -> Vec<NoticeSnapshot> {
        let mut notices = Vec::new();
        if let Some(error) = &self.state.error {
            notices.push(NoticeSnapshot::error(crate::evidence::clipped(error, 8192)));
        }
        notices.retain(|notice| !notice.message.trim().is_empty());
        notices.truncate(8);
        notices
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[::core::prelude::v1::test]
    fn import_clock_publishes_on_success_and_generated_results_clear_it() {
        let source = include_str!("benchmark.rs")
            .split("#[cfg(test)]")
            .next()
            .unwrap();
        for function in ["fn import_benchmark_path(", "fn load_run("] {
            let body = source
                .split(function)
                .nth(1)
                .unwrap()
                .split(".detach();")
                .next()
                .unwrap();
            assert!(body.contains("let started = Instant::now()"));
            assert!(body.contains("MetricPublication::read(&store, wanted)"));
            let publication = body.split("match result {").nth(1).unwrap();
            assert!(
                publication
                    .find("metrics.publish(&mut this.state)")
                    .unwrap()
                    < publication
                        .find("this.state.import_timing = ImportTiming::completed(started)")
                        .unwrap()
            );
            assert!(
                !body
                    .split("Err(error) =>")
                    .nth(1)
                    .unwrap()
                    .contains("import_timing")
            );
        }
        let file_import = source
            .split("fn import_benchmark_path(")
            .nth(1)
            .unwrap()
            .split("pub(in crate::benchmark) fn select_baseline(")
            .next()
            .unwrap();
        assert!(
            file_import
                .split_whitespace()
                .collect::<String>()
                .contains("store.list_recent(RUN_OPTION_LIMIT)")
        );
        assert!(file_import.contains("BenchmarkReportIndex::new(report_path).load()"));
        assert!(
            file_import.find("this.state.run_options = runs").unwrap()
                < file_import
                    .find("this.state.import_timing = ImportTiming::completed(started)")
                    .unwrap()
        );
        assert!(!file_import.contains("this.refresh_options(cx)"));
        assert!(!file_import.contains("this.refresh_metrics(cx)"));
        let selected = source
            .split("fn load_run(")
            .nth(1)
            .unwrap()
            .split("/// Compare the selected runs")
            .next()
            .unwrap();
        assert!(selected.contains("compare(&run, &opposite, &task_policy)"));
        assert!(selected.contains("compare(&opposite, &run, &task_policy)"));
        assert!(
            selected.find("this.state.report = Some(report)").unwrap()
                < selected
                    .find("this.state.import_timing = ImportTiming::completed(started)")
                    .unwrap()
        );
        let generated = source
            .split("fn import_orchestration_result(")
            .nth(1)
            .unwrap();
        assert!(generated.contains("this.state.import_timing.clear()"));
    }

    #[::core::prelude::v1::test]
    fn trend_publication_does_not_overwrite_the_last_valid_import_clock() {
        let timing = ImportTiming::from_duration(Duration::from_millis(1234));
        let mut state = model::BenchmarkReportsState {
            import_timing: timing,
            selected_metric: Some("previous".into()),
            ..Default::default()
        };
        MetricPublication {
            metrics: Vec::new(),
            selected: None,
            trend: None,
        }
        .publish(&mut state);
        assert_eq!(state.import_timing, timing);
        assert!(state.selected_metric.is_none());
        let source = include_str!("benchmark.rs")
            .split("#[cfg(test)]")
            .next()
            .unwrap();
        let refresh = source
            .split("fn refresh_metrics(")
            .nth(1)
            .unwrap()
            .split("// ---------------- import")
            .next()
            .unwrap();
        assert!(!refresh.contains("import_timing"));
    }

    #[::core::prelude::v1::test]
    fn report_title_prefers_build_version_then_file_name() {
        let mut state = model::BenchmarkReportsState::default();
        assert_eq!(report_title(&state), " vs ");
        let run = BenchmarkRun {
            build: aps_benchmark::BenchmarkBuild {
                version_name: Some("1.4.0".into()),
                ..Default::default()
            },
            source_path: PathBuf::from("/tmp/benchmark.json"),
            ..sample_run()
        };
        state.current = Some(Arc::new(run));
        assert_eq!(report_title(&state), "1.4.0 vs ");
        let plain = BenchmarkRun {
            source_path: PathBuf::from("/tmp/other.json"),
            ..sample_run()
        };
        assert_eq!(run_label(&plain), "other.json");
    }

    fn sample_run() -> BenchmarkRun {
        BenchmarkRun {
            id: "run".into(),
            source_path: PathBuf::from("/tmp/benchmark.json"),
            imported_at: "2026-11-08T00:00:00Z".into(),
            data_version: Some(3),
            library_version: Some("1".into()),
            device: aps_benchmark::BenchmarkDevice::default(),
            build: aps_benchmark::BenchmarkBuild::default(),
            warnings: Vec::new(),
            cases: Vec::new(),
        }
    }
}

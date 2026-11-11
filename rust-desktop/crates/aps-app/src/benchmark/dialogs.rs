//! Benchmark comparison-settings and Macrobenchmark orchestration dialogs.
//!
//! Ports of Compose `BenchmarkCompareSettingsDialog.kt` and `BenchmarkOrchestrationDialog.kt`.
//! The panels own form state and rendering only: applying policies goes through the workspace,
//! and a run's process, cancellation, timeout and live output buffer stay in
//! [`super::OrchestrationRun`] so no process ever starts from a composable/render side effect.
//! Both dialogs are a full-page absolute overlay (backdrop plus centered card) appended after the
//! page by the workspace renderer.

use super::model::BenchmarkReportsState;
use super::{BenchmarkWorkspace, OrchestrationRun};
use crate::studio::{self, FONT_BODY, FONT_SUPPORTING, StudioColors};
use aps_benchmark::{
    BenchmarkScenario, CompilationMode, Confidence, MacrobenchmarkPlan, RegressionPolicy,
    ReleaseGatePolicy, StartupMode,
};
use aps_ui_foundation::{Language, TextKey};
use gpui_kit::component::Sizable;
use gpui_kit::{
    component::{
        button::Button,
        checkbox::Checkbox,
        input::{Input, InputState},
        menu::{DropdownMenu, PopupMenuItem},
        scroll::ScrollableElement,
    },
    prelude::*,
    *,
};
use std::path::{Path, PathBuf};

/// Compose `DEFAULT_MACROBENCHMARK_TASK`; the value is owned by the orchestration module.
pub(in crate::benchmark) const DEFAULT_GRADLE_TASK: &str =
    aps_benchmark::DEFAULT_MACROBENCHMARK_TASK;
/// Compose `MAX_WARMUPS` / `MAX_ITERATIONS`.
const MAX_WARMUPS: u32 = 100;
const MAX_ITERATIONS: u32 = 100;
/// Compose `OUTPUT_TAIL_CHARACTERS`: only the tail of the streamed output is shown.
const OUTPUT_TAIL_CHARACTERS: usize = 8000;
const COMPARE_DIALOG_WIDTH: f32 = 560.;
const ORCHESTRATION_DIALOG_WIDTH: f32 = 680.;
const DIALOG_BODY_MAX_HEIGHT: f32 = 520.;
const OUTPUT_MAX_HEIGHT: f32 = 180.;
const FIELD_GAP: f32 = 8.;
const LABEL_WIDTH: f32 = 180.;

const CONFIDENCES: [Confidence; 5] = [
    Confidence::Exact,
    Confidence::Derived,
    Confidence::Inferred,
    Confidence::Partial,
    Confidence::Unknown,
];

/// Why the orchestration form cannot start yet; Compose `BenchmarkPlanProblem`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum PlanProblem {
    ProjectRequired,
    ProjectNotADirectory,
    WarmupsInvalid,
    IterationsInvalid,
    TimeoutInvalid,
}

#[derive(Clone, Copy)]
enum CompareToggle {
    RequireSameDevice,
    FailOnRegression,
    AllowInconclusive,
}

#[derive(Clone, Copy)]
enum BrowseTarget {
    ProjectRoot,
    TraceDirectory,
}

#[derive(Clone)]
enum ModeValue {
    Compilation(CompilationMode),
    Startup(StartupMode),
    Scenario(BenchmarkScenario),
}

struct CompareInputs {
    relative_threshold: Entity<InputState>,
    absolute_threshold: Entity<InputState>,
    minimum_samples: Entity<InputState>,
    bootstrap_iterations: Entity<InputState>,
    confidence_level: Entity<InputState>,
    policy_version: Entity<InputState>,
    max_regressions: Entity<InputState>,
    blocking_patterns: Entity<InputState>,
    require_same_device: bool,
    fail_on_regression: bool,
    allow_inconclusive: bool,
    minimum_confidence: Confidence,
}

struct ComparePrefill {
    relative_threshold: String,
    absolute_threshold: String,
    minimum_samples: String,
    bootstrap_iterations: String,
    confidence_level: String,
    policy_version: String,
    max_regressions: String,
    blocking_patterns: String,
    require_same_device: bool,
    fail_on_regression: bool,
    allow_inconclusive: bool,
    minimum_confidence: Confidence,
}

struct CompareBase {
    regression: RegressionPolicy,
    gate: ReleaseGatePolicy,
}

struct OrchestrationInputs {
    project_root: Entity<InputState>,
    gradle_task: Entity<InputState>,
    benchmark_filter: Entity<InputState>,
    warmups: Entity<InputState>,
    iterations: Entity<InputState>,
    timeout: Entity<InputState>,
    trace_directory: Entity<InputState>,
    result_json: Entity<InputState>,
    compilation_mode: CompilationMode,
    startup_mode: StartupMode,
    scenario: BenchmarkScenario,
    problem: Option<PlanProblem>,
}

/// Presentation-only dialog state owned by the workspace.
pub(in crate::benchmark) struct DialogsUi {
    compare_visible: bool,
    orchestration_visible: bool,
    compare_prefill: Option<ComparePrefill>,
    compare_base: Option<CompareBase>,
    compare: CompareInputs,
    orchestration: OrchestrationInputs,
    last_plan: Option<MacrobenchmarkPlan>,
}

impl DialogsUi {
    pub(in crate::benchmark) fn new(
        window: &mut Window,
        cx: &mut Context<BenchmarkWorkspace>,
    ) -> Self {
        Self {
            compare_visible: false,
            orchestration_visible: false,
            compare_prefill: None,
            compare_base: None,
            compare: CompareInputs {
                relative_threshold: text_input(window, cx),
                absolute_threshold: text_input(window, cx),
                minimum_samples: text_input(window, cx),
                bootstrap_iterations: text_input(window, cx),
                confidence_level: text_input(window, cx),
                policy_version: text_input(window, cx),
                max_regressions: text_input(window, cx),
                blocking_patterns: text_input(window, cx),
                require_same_device: true,
                fail_on_regression: true,
                allow_inconclusive: true,
                minimum_confidence: Confidence::Exact,
            },
            orchestration: OrchestrationInputs {
                project_root: text_input(window, cx),
                gradle_task: text_input_with_default(window, cx, DEFAULT_GRADLE_TASK),
                benchmark_filter: text_input(window, cx),
                warmups: text_input_with_default(window, cx, "2"),
                iterations: text_input_with_default(window, cx, "5"),
                timeout: text_input_with_default(window, cx, "3600"),
                trace_directory: text_input(window, cx),
                result_json: text_input(window, cx),
                compilation_mode: CompilationMode::None,
                startup_mode: StartupMode::Cold,
                scenario: BenchmarkScenario::Startup,
                problem: None,
            },
            last_plan: None,
        }
    }

    pub(in crate::benchmark) fn set_language(
        &mut self,
        _language: Language,
        _window: &mut Window,
        _cx: &mut App,
    ) {
        // Labels and placeholders are resolved at render time; the draft text is language-neutral.
    }

    pub(in crate::benchmark) fn compare_visible(&self) -> bool {
        self.compare_visible
    }

    /// Compose `BenchmarkCompareSettingsDialog` open: snapshot both policies into the form.
    pub(in crate::benchmark) fn open_compare(
        &mut self,
        regression: &RegressionPolicy,
        gate: &ReleaseGatePolicy,
    ) {
        self.compare_prefill = Some(ComparePrefill {
            relative_threshold: regression
                .relative_threshold_percent
                .map(format_double)
                .unwrap_or_default(),
            absolute_threshold: regression
                .absolute_threshold
                .map(format_double)
                .unwrap_or_default(),
            minimum_samples: regression.minimum_sample_count.to_string(),
            bootstrap_iterations: regression.bootstrap_iterations.to_string(),
            confidence_level: format_double(regression.confidence_level),
            policy_version: gate.policy_version.clone(),
            max_regressions: gate.max_allowed_regressions.to_string(),
            blocking_patterns: gate.blocking_metric_patterns.join(", "),
            require_same_device: regression.require_same_device,
            fail_on_regression: gate.fail_on_regression,
            allow_inconclusive: gate.allow_inconclusive,
            minimum_confidence: gate.minimum_confidence,
        });
        self.compare_base = Some(CompareBase {
            regression: regression.clone(),
            gate: gate.clone(),
        });
        self.compare_visible = true;
    }

    pub(in crate::benchmark) fn close_compare(&mut self) {
        self.compare_visible = false;
        self.compare_prefill = None;
        self.compare_base = None;
    }

    pub(in crate::benchmark) fn orchestration_visible(&self) -> bool {
        self.orchestration_visible
    }

    /// Compose `BenchmarkOrchestrationDialog` open: the draft survives close/reopen like the
    /// page-level `remember { BenchmarkRunPlanForm() }` state in the Compose oracle.
    pub(in crate::benchmark) fn open_orchestration(&mut self) {
        self.orchestration_visible = true;
    }

    pub(in crate::benchmark) fn close_orchestration(&mut self) {
        self.orchestration_visible = false;
    }

    /// Contract accessor for the last plan handed to the workspace; kept even though the workspace
    /// owns the run process and does not need to read it back.
    #[allow(dead_code)]
    pub(in crate::benchmark) fn last_plan(&self) -> Option<&MacrobenchmarkPlan> {
        self.last_plan.as_ref()
    }

    /// Resolved comparisons policy from the text fields, port of the Compose `onApply` coercion:
    /// unparsable numbers fall back to the policy defaults and the sample count is at least 1.
    fn compare_policies(&self, cx: &App) -> (RegressionPolicy, ReleaseGatePolicy) {
        let base_regression = self
            .compare_base
            .as_ref()
            .map_or_else(RegressionPolicy::default, |base| base.regression.clone());
        let base_gate = self
            .compare_base
            .as_ref()
            .map_or_else(ReleaseGatePolicy::default, |base| base.gate.clone());
        let mut regression = base_regression.clone();
        regression.relative_threshold_percent =
            parse_f64(value(&self.compare.relative_threshold, cx));
        regression.absolute_threshold = parse_f64(value(&self.compare.absolute_threshold, cx));
        regression.minimum_sample_count = parse_u32(value(&self.compare.minimum_samples, cx))
            .map_or(1, |count| count.max(1) as usize);
        regression.bootstrap_iterations = parse_u32(value(&self.compare.bootstrap_iterations, cx))
            .filter(|count| *count > 0)
            .map_or(base_regression.bootstrap_iterations, |count| count as usize);
        regression.confidence_level = parse_f64(value(&self.compare.confidence_level, cx))
            .filter(|level| *level > 0.0 && *level < 1.0)
            .unwrap_or(base_regression.confidence_level);
        regression.require_same_device = self.compare.require_same_device;
        let mut gate = base_gate;
        let policy_version = value(&self.compare.policy_version, cx);
        gate.policy_version = if policy_version.is_empty() {
            "1".to_owned()
        } else {
            policy_version
        };
        // Compose @@maximumOf(maxRegressions.toIntOrNull(), 0)@@: an unparsable field means zero.
        gate.max_allowed_regressions =
            parse_u32(value(&self.compare.max_regressions, cx)).unwrap_or(0);
        gate.blocking_metric_patterns = value(&self.compare.blocking_patterns, cx)
            .split(',')
            .map(str::trim)
            .filter(|pattern| !pattern.is_empty())
            .map(str::to_owned)
            .collect();
        gate.fail_on_regression = self.compare.fail_on_regression;
        gate.allow_inconclusive = self.compare.allow_inconclusive;
        gate.minimum_confidence = self.compare.minimum_confidence;
        (regression, gate)
    }

    /// Compose `BenchmarkRunPlanForm.validate`: the plan exists only after the ranges, the
    /// project directory and the timeout are valid, so a rejected form never builds a command.
    fn validate_orchestration(
        &self,
        cx: &App,
    ) -> Result<(MacrobenchmarkPlan, Option<String>, u64), PlanProblem> {
        let project = value(&self.orchestration.project_root, cx);
        if project.is_empty() {
            return Err(PlanProblem::ProjectRequired);
        }
        let project_path = PathBuf::from(&project);
        if !project_path.is_dir() {
            return Err(PlanProblem::ProjectNotADirectory);
        }
        let warmups = parse_u32(value(&self.orchestration.warmups, cx))
            .filter(|count| *count <= MAX_WARMUPS)
            .ok_or(PlanProblem::WarmupsInvalid)?;
        let iterations = parse_u32(value(&self.orchestration.iterations, cx))
            .filter(|count| (1..=MAX_ITERATIONS).contains(count))
            .ok_or(PlanProblem::IterationsInvalid)?;
        let timeout = value(&self.orchestration.timeout, cx)
            .parse::<u64>()
            .ok()
            .filter(|seconds| *seconds > 0)
            .ok_or(PlanProblem::TimeoutInvalid)?;
        let gradle_task = {
            let task = value(&self.orchestration.gradle_task, cx);
            if task.is_empty() {
                DEFAULT_GRADLE_TASK.to_owned()
            } else {
                task
            }
        };
        let benchmark_filter = Some(value(&self.orchestration.benchmark_filter, cx))
            .filter(|filter| !filter.is_empty());
        let trace_directory = Some(value(&self.orchestration.trace_directory, cx))
            .filter(|directory| !directory.is_empty())
            .map(PathBuf::from);
        let result_json =
            Some(value(&self.orchestration.result_json, cx)).filter(|path| !path.is_empty());
        let project_root = std::path::absolute(&project_path).unwrap_or(project_path);
        Ok((
            MacrobenchmarkPlan {
                device_serial: None,
                project_root,
                gradle_task,
                benchmark_filter,
                compilation_mode: self.orchestration.compilation_mode.clone(),
                startup_mode: self.orchestration.startup_mode.clone(),
                scenario: self.orchestration.scenario.clone(),
                warmups,
                iterations,
                trace_directory,
            },
            result_json,
            timeout,
        ))
    }
}

fn text_input(window: &mut Window, cx: &mut Context<BenchmarkWorkspace>) -> Entity<InputState> {
    cx.new(|cx| InputState::new(window, cx))
}

fn text_input_with_default(
    window: &mut Window,
    cx: &mut Context<BenchmarkWorkspace>,
    value: &'static str,
) -> Entity<InputState> {
    cx.new(|cx| InputState::new(window, cx).default_value(value))
}

/// Compose `BenchmarkCompareSettingsDialog` + `BenchmarkOrchestrationDialog` entry point.
pub(in crate::benchmark) fn overlay(
    dialogs: &mut DialogsUi,
    _state: &BenchmarkReportsState,
    run: &OrchestrationRun,
    language: Language,
    window: &mut Window,
    cx: &mut Context<BenchmarkWorkspace>,
) -> Option<AnyElement> {
    let colors = StudioColors::from_theme(cx);
    if dialogs.compare_visible() {
        apply_compare_prefill(dialogs, window, cx);
        let content = compare_card(dialogs, language, &colors, cx);
        let dismiss = cx.listener(|this, _, _, cx| this.close_compare_settings(cx));
        return Some(modal(
            "benchmark-compare-settings-backdrop",
            "benchmark-compare-settings-card",
            COMPARE_DIALOG_WIDTH,
            &colors,
            content,
            dismiss,
        ));
    }
    if dialogs.orchestration_visible() {
        let content = orchestration_card(dialogs, run, language, &colors, cx);
        let dismiss = cx.listener(|this, _, _, cx| {
            if !this.orchestration.running {
                this.orchestration_close(cx);
            }
        });
        return Some(modal(
            "benchmark-orchestration-backdrop",
            "benchmark-orchestration-card",
            ORCHESTRATION_DIALOG_WIDTH,
            &colors,
            content,
            dismiss,
        ));
    }
    None
}

fn apply_compare_prefill(
    dialogs: &mut DialogsUi,
    window: &mut Window,
    cx: &mut Context<BenchmarkWorkspace>,
) {
    let Some(prefill) = dialogs.compare_prefill.take() else {
        return;
    };
    let inputs = &dialogs.compare;
    set_input(
        &inputs.relative_threshold,
        prefill.relative_threshold,
        window,
        cx,
    );
    set_input(
        &inputs.absolute_threshold,
        prefill.absolute_threshold,
        window,
        cx,
    );
    set_input(&inputs.minimum_samples, prefill.minimum_samples, window, cx);
    set_input(
        &inputs.bootstrap_iterations,
        prefill.bootstrap_iterations,
        window,
        cx,
    );
    set_input(
        &inputs.confidence_level,
        prefill.confidence_level,
        window,
        cx,
    );
    set_input(&inputs.policy_version, prefill.policy_version, window, cx);
    set_input(&inputs.max_regressions, prefill.max_regressions, window, cx);
    set_input(
        &inputs.blocking_patterns,
        prefill.blocking_patterns,
        window,
        cx,
    );
    dialogs.compare.require_same_device = prefill.require_same_device;
    dialogs.compare.fail_on_regression = prefill.fail_on_regression;
    dialogs.compare.allow_inconclusive = prefill.allow_inconclusive;
    dialogs.compare.minimum_confidence = prefill.minimum_confidence;
}

fn set_input(
    input: &Entity<InputState>,
    value: String,
    window: &mut Window,
    cx: &mut Context<BenchmarkWorkspace>,
) {
    input.update(cx, |state, cx| state.set_value(value, window, cx));
}

fn compare_card(
    dialogs: &DialogsUi,
    language: Language,
    colors: &StudioColors,
    cx: &mut Context<BenchmarkWorkspace>,
) -> AnyElement {
    let entity = cx.entity().downgrade();
    let confidence_label = language
        .text(confidence_key(dialogs.compare.minimum_confidence))
        .to_owned();
    let mut body = div()
        .id("benchmark-compare-settings-form")
        .w_full()
        .max_h(px(DIALOG_BODY_MAX_HEIGHT))
        .overflow_y_scrollbar()
        .flex()
        .flex_col()
        .gap(px(FIELD_GAP));
    body = body
        .child(text_field(
            language.text(TextKey::BenchmarkSettingsThresholdPercent),
            &dialogs.compare.relative_threshold,
            colors,
        ))
        .child(text_field(
            language.text(TextKey::BenchmarkSettingsAbsoluteThreshold),
            &dialogs.compare.absolute_threshold,
            colors,
        ))
        .child(text_field(
            language.text(TextKey::BenchmarkSettingsMinimumSamples),
            &dialogs.compare.minimum_samples,
            colors,
        ))
        .child(studio::divider(colors))
        // Rust analyzer contract: DESIGN.md requires the BCa iterations and confidence level to be
        // editable, so they are exposed next to the Compose fields instead of hidden.
        .child(text_field(
            language.text(TextKey::BenchmarkSettingsBootstrapIterations),
            &dialogs.compare.bootstrap_iterations,
            colors,
        ))
        .child(text_field(
            language.text(TextKey::BenchmarkSettingsConfidenceLevel),
            &dialogs.compare.confidence_level,
            colors,
        ))
        .child(studio::divider(colors))
        .child(text_field(
            language.text(TextKey::BenchmarkPolicyVersionLabel),
            &dialogs.compare.policy_version,
            colors,
        ))
        .child(text_field(
            language.text(TextKey::BenchmarkSettingsMaxRegressions),
            &dialogs.compare.max_regressions,
            colors,
        ))
        .child(text_field(
            language.text(TextKey::BenchmarkSettingsBlockingMetrics),
            &dialogs.compare.blocking_patterns,
            colors,
        ))
        .child(toggle_row(
            "benchmark-compare-require-same-device",
            language.text(TextKey::BenchmarkSettingsRequireSameDevice),
            dialogs.compare.require_same_device,
            CompareToggle::RequireSameDevice,
            &entity,
            colors,
        ))
        .child(confidence_row(confidence_label, language, &entity, colors))
        .child(toggle_row(
            "benchmark-compare-fail-on-regression",
            language.text(TextKey::BenchmarkSettingsFailOnRegression),
            dialogs.compare.fail_on_regression,
            CompareToggle::FailOnRegression,
            &entity,
            colors,
        ))
        .child(toggle_row(
            "benchmark-compare-allow-inconclusive",
            language.text(TextKey::BenchmarkSettingsAllowInconclusive),
            dialogs.compare.allow_inconclusive,
            CompareToggle::AllowInconclusive,
            &entity,
            colors,
        ));
    let apply = studio::text_button(
        "benchmark-compare-apply",
        language.text(TextKey::BenchmarkApply),
        true,
        true,
        colors,
    )
    .on_click(cx.listener(|this, _, _, cx| {
        let (regression, gate) = this.dialogs.compare_policies(cx);
        this.apply_compare_settings(regression, gate, cx);
    }));
    let cancel = studio::text_button(
        "benchmark-compare-cancel",
        language.text(TextKey::BenchmarkCancel),
        true,
        false,
        colors,
    )
    .on_click(cx.listener(|this, _, _, cx| this.close_compare_settings(cx)));
    dialog_card(
        language.text(TextKey::BenchmarkCompareSettings),
        body.into_any_element(),
        footer(cancel, apply),
        colors,
    )
}

fn confidence_row(
    selected_label: String,
    language: Language,
    entity: &WeakEntity<BenchmarkWorkspace>,
    colors: &StudioColors,
) -> Div {
    let entity = entity.clone();
    div()
        .w_full()
        .flex()
        .items_center()
        .gap(px(FIELD_GAP))
        .child(row_label(
            language.text(TextKey::BenchmarkSettingsMinConfidence),
            colors,
        ))
        .child(
            div().flex_1().min_w_0().child(
                studio::compact_text_button(
                    "benchmark-compare-min-confidence",
                    selected_label,
                    true,
                    false,
                    colors,
                )
                .w_full()
                .justify_start()
                .dropdown_menu(move |menu, _, _| {
                    let mut menu = menu;
                    for confidence in CONFIDENCES {
                        let entity = entity.clone();
                        let label = language.text(confidence_key(confidence));
                        menu = menu.item(PopupMenuItem::new(label).on_click(move |_, _, cx| {
                            let _ = entity.update(cx, |this, cx| {
                                this.dialogs.compare.minimum_confidence = confidence;
                                cx.notify();
                            });
                        }));
                    }
                    menu
                }),
            ),
        )
}

fn toggle_row(
    id: &'static str,
    label: &'static str,
    checked: bool,
    toggle: CompareToggle,
    entity: &WeakEntity<BenchmarkWorkspace>,
    colors: &StudioColors,
) -> Div {
    let entity = entity.clone();
    div()
        .w_full()
        .flex()
        .items_center()
        .gap(px(6.))
        .child(
            Checkbox::new(id)
                .with_size(gpui_kit::component::Size::Small)
                .checked(checked)
                .on_change(move |checked, _, cx| {
                    let checked = *checked;
                    let _ = entity.update(cx, |this, cx| {
                        match toggle {
                            CompareToggle::RequireSameDevice => {
                                this.dialogs.compare.require_same_device = checked;
                            }
                            CompareToggle::FailOnRegression => {
                                this.dialogs.compare.fail_on_regression = checked;
                            }
                            CompareToggle::AllowInconclusive => {
                                this.dialogs.compare.allow_inconclusive = checked;
                            }
                        }
                        cx.notify();
                    });
                }),
        )
        .child(
            div()
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.secondary_text)
                .child(studio::selectable(label)),
        )
}

fn orchestration_card(
    dialogs: &DialogsUi,
    run: &OrchestrationRun,
    language: Language,
    colors: &StudioColors,
    cx: &mut Context<BenchmarkWorkspace>,
) -> AnyElement {
    let entity = cx.entity().downgrade();
    let running = run.running;
    let output = run
        .output
        .lock()
        .map(|buffer| tail_chars(&buffer, OUTPUT_TAIL_CHARACTERS).to_owned())
        .unwrap_or_default();
    let mut body = div()
        .id("benchmark-orchestration-form")
        .w_full()
        .max_h(px(DIALOG_BODY_MAX_HEIGHT))
        .overflow_y_scrollbar()
        .flex()
        .flex_col()
        .gap(px(FIELD_GAP))
        .child(
            div()
                .w_full()
                .text_size(px(FONT_SUPPORTING))
                .line_height(px(15.))
                .text_color(colors.secondary_text)
                .child(studio::selectable(
                    language.text(TextKey::BenchmarkOrchestrateSubtitle),
                )),
        )
        .child(path_field(
            language.text(TextKey::BenchmarkPlanProject),
            &dialogs.orchestration.project_root,
            "benchmark-plan-project-browse",
            language,
            colors,
            cx.listener(|this, _, _, cx| {
                let prompt: SharedString = this.language.text(TextKey::BenchmarkPlanProject).into();
                pick_directory(BrowseTarget::ProjectRoot, prompt, cx);
            }),
        ))
        .child(text_field(
            language.text(TextKey::BenchmarkPlanTask),
            &dialogs.orchestration.gradle_task,
            colors,
        ))
        .child(text_field(
            language.text(TextKey::BenchmarkPlanFilter),
            &dialogs.orchestration.benchmark_filter,
            colors,
        ))
        .child(mode_row(
            "benchmark-plan-compilation",
            language.text(TextKey::BenchmarkPlanCompilation),
            dialogs.orchestration.compilation_mode.label(),
            compilation_items(),
            &entity,
            colors,
        ))
        .child(mode_row(
            "benchmark-plan-startup",
            language.text(TextKey::BenchmarkPlanStartup),
            dialogs.orchestration.startup_mode.label(),
            startup_items(),
            &entity,
            colors,
        ))
        .child(mode_row(
            "benchmark-plan-scenario",
            language.text(TextKey::BenchmarkPlanScenario),
            dialogs.orchestration.scenario.label(),
            scenario_items(),
            &entity,
            colors,
        ))
        .child(
            div()
                .w_full()
                .flex()
                .gap(px(FIELD_GAP))
                .child(
                    text_field(
                        language.text(TextKey::BenchmarkPlanWarmups),
                        &dialogs.orchestration.warmups,
                        colors,
                    )
                    .flex_1(),
                )
                .child(
                    text_field(
                        language.text(TextKey::BenchmarkPlanIterations),
                        &dialogs.orchestration.iterations,
                        colors,
                    )
                    .flex_1(),
                )
                .child(
                    text_field(
                        language.text(TextKey::BenchmarkPlanTimeout),
                        &dialogs.orchestration.timeout,
                        colors,
                    )
                    .flex_1(),
                ),
        )
        .child(path_field(
            language.text(TextKey::BenchmarkPlanTraceDir),
            &dialogs.orchestration.trace_directory,
            "benchmark-plan-trace-browse",
            language,
            colors,
            cx.listener(|this, _, _, cx| {
                let prompt: SharedString =
                    this.language.text(TextKey::BenchmarkPlanTraceDir).into();
                pick_directory(BrowseTarget::TraceDirectory, prompt, cx);
            }),
        ))
        .child(path_field(
            language.text(TextKey::BenchmarkPlanResultJson),
            &dialogs.orchestration.result_json,
            "benchmark-plan-result-browse",
            language,
            colors,
            cx.listener(|_, _, _, cx| pick_result_json(cx)),
        ));
    if let Some(problem) = dialogs.orchestration.problem {
        body = body.child(
            div()
                .w_full()
                .text_size(px(FONT_SUPPORTING))
                .line_height(px(15.))
                .text_color(colors.error)
                .child(studio::selectable(language.text(plan_problem_key(problem)))),
        );
    }
    if running {
        body = body.child(
            div()
                .w_full()
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.accent)
                .child(studio::selectable(
                    language.text(TextKey::BenchmarkOrchestrateRunning),
                )),
        );
    }
    if let Some(status) = run.status.as_deref().filter(|status| !status.is_empty()) {
        body = body.child(
            div()
                .w_full()
                .min_w_0()
                .text_size(px(FONT_SUPPORTING))
                .line_height(px(15.))
                .text_color(colors.text)
                .child(studio::selectable(status.to_owned())),
        );
    }
    if !output.is_empty() {
        body = body
            .child(
                div()
                    .w_full()
                    .text_size(px(FONT_SUPPORTING))
                    .text_color(colors.secondary_text)
                    .child(studio::selectable(
                        language.text(TextKey::BenchmarkOrchestrateOutput),
                    )),
            )
            .child(
                div()
                    .id("benchmark-orchestration-output")
                    .w_full()
                    .max_h(px(OUTPUT_MAX_HEIGHT))
                    .overflow_y_scrollbar()
                    .p(px(6.))
                    .bg(colors.section_background)
                    .border_1()
                    .border_color(colors.border)
                    .rounded(px(studio::metrics::SMALL_RADIUS))
                    .font_family("monospace")
                    .text_size(px(FONT_SUPPORTING))
                    .line_height(px(15.))
                    .text_color(colors.text)
                    .child(studio::selectable(output)),
            );
    }
    let primary = if running {
        studio::text_button(
            "benchmark-orchestration-cancel",
            language.text(TextKey::BenchmarkOrchestrateCancel),
            true,
            false,
            colors,
        )
        .on_click(cx.listener(|this, _, _, cx| this.cancel_orchestration(cx)))
    } else {
        studio::text_button(
            "benchmark-orchestration-start",
            language.text(TextKey::BenchmarkOrchestrateStart),
            true,
            true,
            colors,
        )
        .on_click(cx.listener(|this, _, _, cx| {
            let validation = this.dialogs.validate_orchestration(cx);
            match validation {
                Ok((plan, result_json, timeout)) => {
                    this.dialogs.orchestration.problem = None;
                    this.dialogs.last_plan = Some(plan.clone());
                    this.start_orchestration(plan, result_json, timeout, cx);
                }
                Err(problem) => {
                    this.dialogs.orchestration.problem = Some(problem);
                    cx.notify();
                }
            }
        }))
    };
    let close = studio::text_button(
        "benchmark-orchestration-close",
        language.text(TextKey::BenchmarkOrchestrateClose),
        !running,
        false,
        colors,
    )
    .on_click(cx.listener(|this, _, _, cx| this.orchestration_close(cx)));
    dialog_card(
        language.text(TextKey::BenchmarkOrchestrateTitle),
        body.into_any_element(),
        footer(close, primary),
        colors,
    )
}

fn compilation_items() -> Vec<(String, ModeValue)> {
    [
        CompilationMode::None,
        CompilationMode::Partial,
        CompilationMode::Full,
        CompilationMode::SpeedProfile,
    ]
    .into_iter()
    .map(|mode| (mode.label().to_owned(), ModeValue::Compilation(mode)))
    .collect()
}

fn startup_items() -> Vec<(String, ModeValue)> {
    [StartupMode::Cold, StartupMode::Warm, StartupMode::Hot]
        .into_iter()
        .map(|mode| (mode.label().to_owned(), ModeValue::Startup(mode)))
        .collect()
}

fn scenario_items() -> Vec<(String, ModeValue)> {
    [
        BenchmarkScenario::Startup,
        BenchmarkScenario::Scroll,
        BenchmarkScenario::Animation,
        BenchmarkScenario::PageSwitch,
    ]
    .into_iter()
    .map(|mode| (mode.label().to_owned(), ModeValue::Scenario(mode)))
    .collect()
}

fn mode_row(
    id: &'static str,
    label: &'static str,
    selected: &'static str,
    items: Vec<(String, ModeValue)>,
    entity: &WeakEntity<BenchmarkWorkspace>,
    colors: &StudioColors,
) -> Div {
    let entity = entity.clone();
    div()
        .w_full()
        .flex()
        .items_center()
        .gap(px(FIELD_GAP))
        .child(row_label(label, colors))
        .child(
            div().flex_1().min_w_0().child(
                studio::compact_text_button(id, selected, true, false, colors)
                    .w_full()
                    .justify_start()
                    .dropdown_menu(move |menu, _, _| {
                        let mut menu = menu;
                        for (label, value) in &items {
                            let label = label.clone();
                            let value = value.clone();
                            let entity = entity.clone();
                            menu =
                                menu.item(PopupMenuItem::new(label).on_click(move |_, _, cx| {
                                    let value = value.clone();
                                    let _ = entity.update(cx, |this, cx| {
                                        match value {
                                            ModeValue::Compilation(mode) => {
                                                this.dialogs.orchestration.compilation_mode = mode;
                                            }
                                            ModeValue::Startup(mode) => {
                                                this.dialogs.orchestration.startup_mode = mode;
                                            }
                                            ModeValue::Scenario(mode) => {
                                                this.dialogs.orchestration.scenario = mode;
                                            }
                                        }
                                        cx.notify();
                                    });
                                }));
                        }
                        menu
                    }),
            ),
        )
}

fn text_field(label: &'static str, input: &Entity<InputState>, colors: &StudioColors) -> Div {
    div()
        .w_full()
        .min_w_0()
        .flex()
        .flex_col()
        .gap(px(2.))
        .child(field_label(label, colors))
        .child(Input::new(input).h(px(26.)).w_full())
}

fn path_field(
    label: &'static str,
    input: &Entity<InputState>,
    browse_id: &'static str,
    language: Language,
    colors: &StudioColors,
    browse: impl Fn(&ClickEvent, &mut Window, &mut App) + 'static,
) -> Div {
    div()
        .w_full()
        .min_w_0()
        .flex()
        .flex_col()
        .gap(px(2.))
        .child(field_label(label, colors))
        .child(
            div()
                .w_full()
                .flex()
                .items_center()
                .gap(px(FIELD_GAP))
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .child(Input::new(input).h(px(26.)).w_full()),
                )
                .child(
                    studio::compact_text_button(
                        browse_id,
                        language.text(TextKey::BenchmarkPlanBrowse),
                        true,
                        false,
                        colors,
                    )
                    .on_click(browse),
                ),
        )
}

fn field_label(text: &'static str, colors: &StudioColors) -> Div {
    div()
        .w_full()
        .text_size(px(FONT_SUPPORTING))
        .line_height(px(15.))
        .text_color(colors.secondary_text)
        .child(studio::selectable(text))
}

fn row_label(text: &'static str, colors: &StudioColors) -> Div {
    div()
        .w(px(LABEL_WIDTH))
        .flex_shrink_0()
        .text_size(px(FONT_SUPPORTING))
        .text_color(colors.secondary_text)
        .child(studio::selectable(text))
}

/// Compose AlertDialog body: title, divider, scrollable form and the button row.
fn dialog_card(
    title: &'static str,
    body: AnyElement,
    buttons: AnyElement,
    colors: &StudioColors,
) -> AnyElement {
    div()
        .w_full()
        .flex()
        .flex_col()
        .gap(px(FIELD_GAP))
        .child(
            div()
                .w_full()
                .text_size(px(FONT_BODY))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(colors.accent)
                .child(studio::selectable(title)),
        )
        .child(studio::divider(colors))
        .child(body)
        .child(studio::divider(colors))
        .child(buttons)
        .into_any_element()
}

fn footer(cancel: Button, primary: Button) -> AnyElement {
    div()
        .w_full()
        .flex()
        .items_center()
        .justify_end()
        .gap(px(FIELD_GAP))
        .child(cancel)
        .child(primary)
        .into_any_element()
}

fn modal(
    backdrop_id: &'static str,
    card_id: &'static str,
    width: f32,
    colors: &StudioColors,
    content: AnyElement,
    dismiss: impl Fn(&ClickEvent, &mut Window, &mut App) + 'static,
) -> AnyElement {
    div()
        .id(backdrop_id)
        .absolute()
        .inset_0()
        .flex()
        .items_center()
        .justify_center()
        .bg(rgba(0x00000052))
        .on_click(dismiss)
        .child(
            div()
                .id(card_id)
                .w(px(width))
                .p(px(12.))
                .bg(colors.panel)
                .border_1()
                .border_color(colors.strong_border)
                .rounded(px(studio::metrics::CARD_RADIUS))
                .on_click(|_, _, cx| cx.stop_propagation())
                .child(content),
        )
        .into_any_element()
}

fn pick_directory(
    target: BrowseTarget,
    prompt: SharedString,
    cx: &mut Context<BenchmarkWorkspace>,
) {
    let entity = cx.entity().downgrade();
    let picker = cx.prompt_for_paths(PathPromptOptions {
        files: false,
        directories: true,
        multiple: false,
        prompt: Some(prompt),
    });
    cx.spawn(async move |_this, cx| {
        let Ok(Ok(Some(paths))) = picker.await else {
            return;
        };
        let Some(path) = paths.into_iter().next() else {
            return;
        };
        let value = path.to_string_lossy().into_owned();
        let _ = entity.update_in(cx, |this, window, cx| {
            let input = match target {
                BrowseTarget::ProjectRoot => this.dialogs.orchestration.project_root.clone(),
                BrowseTarget::TraceDirectory => this.dialogs.orchestration.trace_directory.clone(),
            };
            set_input(&input, value, window, cx);
            cx.notify();
        });
    })
    .detach();
}

fn pick_result_json(cx: &mut Context<BenchmarkWorkspace>) {
    let entity = cx.entity().downgrade();
    // Compose uses the platform save dialog; start it at the project root when it is valid so the
    // suggested benchmarkData.json lands where AndroidX would write it, else at the process cwd.
    let project_root = cx
        .entity()
        .read(cx)
        .dialogs
        .orchestration
        .project_root
        .read(cx)
        .value()
        .trim()
        .to_owned();
    let directory = if !project_root.is_empty() && Path::new(&project_root).is_dir() {
        PathBuf::from(project_root)
    } else {
        std::env::current_dir().unwrap_or_else(|_| PathBuf::from("."))
    };
    let dialog = cx.prompt_for_new_path(&directory, Some("benchmarkData.json"));
    cx.spawn(async move |_this, cx| {
        let Ok(Ok(Some(path))) = dialog.await else {
            return;
        };
        let value = path.to_string_lossy().into_owned();
        let _ = entity.update_in(cx, |this, window, cx| {
            let input = this.dialogs.orchestration.result_json.clone();
            set_input(&input, value, window, cx);
            cx.notify();
        });
    })
    .detach();
}

fn value(input: &Entity<InputState>, cx: &App) -> String {
    input.read(cx).value().trim().to_owned()
}

/// Kotlin @@toDoubleOrNull@@ parity: surrounding whitespace is accepted, non-finite is not.
fn parse_f64(text: String) -> Option<f64> {
    text.trim()
        .parse::<f64>()
        .ok()
        .filter(|value| value.is_finite())
}

/// Kotlin @@toIntOrNull@@ parity for the unsigned counts used by the policy and plan forms.
fn parse_u32(text: String) -> Option<u32> {
    text.trim().parse::<u32>().ok()
}

/// Kotlin `Double.toString()` parity for the pre-filled form values: `5.0`, not `5`.
fn format_double(value: f64) -> String {
    if !value.is_finite() {
        return String::new();
    }
    if value.fract() == 0.0 && value.abs() < 1e15 {
        format!("{value:.1}")
    } else {
        value.to_string()
    }
}

fn confidence_key(confidence: Confidence) -> TextKey {
    match confidence {
        Confidence::Exact => TextKey::BenchmarkConfidenceExact,
        Confidence::Derived => TextKey::BenchmarkConfidenceDerived,
        Confidence::Inferred => TextKey::BenchmarkConfidenceInferred,
        Confidence::Partial => TextKey::BenchmarkConfidencePartial,
        Confidence::Unknown => TextKey::BenchmarkConfidenceUnknown,
    }
}

fn plan_problem_key(problem: PlanProblem) -> TextKey {
    match problem {
        PlanProblem::ProjectRequired => TextKey::BenchmarkOrchestrateProjectRequired,
        PlanProblem::ProjectNotADirectory => TextKey::BenchmarkOrchestrateProjectMissing,
        PlanProblem::WarmupsInvalid => TextKey::BenchmarkOrchestrateWarmupsInvalid,
        PlanProblem::IterationsInvalid => TextKey::BenchmarkOrchestrateIterationsInvalid,
        PlanProblem::TimeoutInvalid => TextKey::BenchmarkOrchestrateTimeoutInvalid,
    }
}

/// Last `limit` characters of the streamed output without splitting a UTF-8 scalar.
fn tail_chars(text: &str, limit: usize) -> &str {
    if limit == 0 {
        return "";
    }
    match text.char_indices().rev().nth(limit - 1) {
        Some((index, _)) => &text[index..],
        None => text,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[::core::prelude::v1::test]
    fn double_text_matches_compose_remembered_values() {
        assert_eq!(format_double(5.0), "5.0");
        assert_eq!(format_double(0.95), "0.95");
        assert_eq!(format_double(2000.0), "2000.0");
        assert_eq!(format_double(f64::NAN), "");
    }

    #[::core::prelude::v1::test]
    fn parsed_ranges_match_the_compose_form_coercions() {
        assert_eq!(parse_f64(" 12.5 ".into()), Some(12.5));
        assert_eq!(parse_f64("".into()), None);
        assert_eq!(parse_f64("NaN".into()), None);
        assert_eq!(parse_f64("inf".into()), None);
        assert_eq!(parse_u32("0".into()), Some(0));
        assert_eq!(parse_u32("101".into()), Some(101));
        assert_eq!(parse_u32("-1".into()), None);
    }

    #[::core::prelude::v1::test]
    fn output_tail_keeps_the_last_characters_without_panicking_on_multibyte() {
        assert_eq!(tail_chars("abcdef", 3), "def");
        assert_eq!(tail_chars("abc", 8), "abc");
        assert_eq!(tail_chars("", 8), "");
        assert_eq!(tail_chars("aé日z", 2), "日z");
        assert_eq!(tail_chars("abc", 0), "");
    }

    #[::core::prelude::v1::test]
    fn plan_problems_map_to_the_compose_messages() {
        assert_eq!(
            plan_problem_key(PlanProblem::ProjectRequired),
            TextKey::BenchmarkOrchestrateProjectRequired
        );
        assert_eq!(
            plan_problem_key(PlanProblem::TimeoutInvalid),
            TextKey::BenchmarkOrchestrateTimeoutInvalid
        );
    }
}

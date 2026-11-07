//! Compose \`BenchmarkReportsScreen.kt\` parity for the Benchmark Regression workspace.
//!
//! The page is the oracle layout: one scrollable header action row, four summary metric cards, then
//! the comparison table (fills) above the 210px trend panel beside the 320px report rail, and a
//! single copyable status line at the bottom. Every visible application-owned string is a
//! \`TextKey\`; imported identities, paths and diagnostics render verbatim.

use super::model::{BenchmarkReportsState, RunOption};
use super::{rail, table, trend};
use crate::benchmark::BenchmarkWorkspace;
use crate::studio::{self, StudioColors};
use aps_benchmark::GateDecision;
use aps_ui_foundation::{Destination, Language, TextKey};
use gpui_kit::component::{
    button::Button,
    input::{InputEvent, InputState},
    menu::{DropdownMenu, PopupMenuItem},
};
use gpui_kit::{prelude::*, *};

const HEADER_HEIGHT: f32 = 34.0;
const RUN_SELECTOR_WIDTH: f32 = 220.0;
const TREND_HEIGHT: f32 = 210.0;
const REPORT_RAIL_WIDTH: f32 = 320.0;
const STATUS_HEIGHT: f32 = 22.0;

/// Presentation-only state owned by the page: the shared search input entity and its subscription.
pub(in crate::benchmark) struct PageUi {
    language: Option<Language>,
    search: Entity<InputState>,
    _search_subscription: Subscription,
}

impl PageUi {
    pub(in crate::benchmark) fn new(
        window: &mut Window,
        cx: &mut Context<BenchmarkWorkspace>,
    ) -> Self {
        let search = cx.new(|cx| InputState::new(window, cx));
        // The query is owned by the workspace state, exactly like Compose's onQueryChange.
        let subscription = cx.subscribe(&search, |this, input, event, cx| {
            if matches!(event, InputEvent::Change) {
                let value = input.read(cx).value().to_string();
                this.set_query(value, cx);
            }
        });
        Self {
            language: None,
            search,
            _search_subscription: subscription,
        }
    }

    pub(in crate::benchmark) fn set_language(
        &mut self,
        language: Language,
        window: &mut Window,
        cx: &mut App,
    ) {
        self.language = Some(language);
        let placeholder = language.text(TextKey::BenchmarkSearchBenchmarks);
        self.search.update(cx, |state, cx| {
            state.set_placeholder(placeholder, window, cx)
        });
    }

    /// The comparison table renders this entity; the page owns its lifecycle and subscription.
    pub(in crate::benchmark) fn search_input(&self) -> &Entity<InputState> {
        &self.search
    }
}

pub(in crate::benchmark) fn render(
    state: &BenchmarkReportsState,
    ui: &mut PageUi,
    language: Language,
    window: &mut Window,
    cx: &mut Context<BenchmarkWorkspace>,
) -> AnyElement {
    let colors = StudioColors::from_theme(cx);
    if ui.language != Some(language) {
        ui.set_language(language, window, cx);
    }
    let body = div()
        .flex_1()
        .min_h_0()
        .min_w_0()
        .w_full()
        .flex()
        .flex_col()
        .gap(px(studio::metrics::CARD_GAP))
        .p(px(studio::metrics::WORKSPACE_PADDING))
        .child(summary(state, language, &colors))
        .child(
            div()
                .flex()
                .flex_1()
                .min_h_0()
                .min_w_0()
                .w_full()
                .gap(px(studio::metrics::CARD_GAP))
                .child(
                    div()
                        .flex()
                        .flex_1()
                        .min_h_0()
                        .min_w_0()
                        .flex_col()
                        .gap(px(studio::metrics::CARD_GAP))
                        .child(
                            div()
                                .flex_1()
                                .min_h_0()
                                .min_w_0()
                                .child(table::render(state, &mut *ui, language, cx)),
                        )
                        .child(
                            div()
                                .h(px(TREND_HEIGHT))
                                .flex_shrink_0()
                                .min_w_0()
                                .child(trend::panel(state, language, cx)),
                        ),
                )
                .child(
                    div()
                        .id("benchmark-report-rail-scroll")
                        .w(px(REPORT_RAIL_WIDTH))
                        .h_full()
                        .flex_shrink_0()
                        .min_h_0()
                        .overflow_y_scroll()
                        .child(
                            div()
                                .flex()
                                .flex_col()
                                .gap(px(studio::metrics::CARD_GAP))
                                .child(rail::report_column(state, language, cx))
                                .child(rail::inspector(state, language, cx)),
                        ),
                ),
        );
    div()
        .id("benchmark-reports-page")
        .size_full()
        .min_h_0()
        .min_w_0()
        .flex()
        .flex_col()
        .bg(colors.workspace)
        .text_color(colors.text)
        .text_size(px(studio::FONT_BODY))
        .child(header(state, language, &colors, cx))
        .child(studio::divider(&colors))
        .child(body)
        .child(status_line(state, &colors))
        .into_any_element()
}

fn action_button(
    id: &'static str,
    key: TextKey,
    enabled: bool,
    primary: bool,
    language: Language,
    colors: &StudioColors,
) -> Button {
    studio::compact_text_button(id, language.text(key), enabled, primary, colors).flex_shrink_0()
}

fn run_option_label(option: &RunOption, language: Language) -> String {
    if option.detail.trim().is_empty() {
        option.label.clone()
    } else {
        language.format(
            TextKey::BenchmarkRunOptionDetail,
            &[("label", &option.label), ("detail", &option.detail)],
        )
    }
}

/// Compose \`DropdownSelector\`: 220px wide, selected run as the trigger label, one row per stored
/// run with its device/API/date detail. Selection dispatches to the Lead's workspace action.
fn run_selector(
    id: &'static str,
    options: &[RunOption],
    selected_id: Option<&str>,
    language: Language,
    enabled: bool,
    colors: &StudioColors,
    select: fn(&mut BenchmarkWorkspace, String, &mut Context<BenchmarkWorkspace>),
    cx: &mut Context<BenchmarkWorkspace>,
) -> AnyElement {
    let label = selected_id
        .and_then(|id| options.iter().find(|option| option.id == id))
        .map_or_else(
            || language.text(TextKey::BenchmarkSelectRun).to_owned(),
            |option| option.label.clone(),
        );
    let items: Vec<(String, String)> = options
        .iter()
        .map(|option| (option.id.clone(), run_option_label(option, language)))
        .collect();
    let entity = cx.entity().downgrade();
    studio::compact_text_button(id, label, enabled, false, colors)
        .w(px(RUN_SELECTOR_WIDTH))
        .dropdown_menu(move |menu, _, _| {
            let mut menu = menu;
            for (option_id, option_label) in &items {
                let entity = entity.clone();
                let option_id = option_id.clone();
                menu = menu.item(PopupMenuItem::new(option_label.clone()).on_click(
                    move |_, _, cx| {
                        let option_id = option_id.clone();
                        let _ = entity.update(cx, |this, cx| select(this, option_id, cx));
                    },
                ));
            }
            menu
        })
        .into_any_element()
}

/// Compose \`BenchmarkHeaderActions\` plus the Open Trace and AI entries from the main page header.
fn header(
    state: &BenchmarkReportsState,
    language: Language,
    colors: &StudioColors,
    cx: &mut Context<BenchmarkWorkspace>,
) -> AnyElement {
    let busy = state.busy;
    let trace_available = state.current.as_ref().is_some_and(|run| {
        run.cases()
            .iter()
            .any(|case| !case.trace_artifacts.is_empty())
    });
    let ai_enabled = state.report.is_some() && !busy;
    div()
        .id("benchmark-header")
        .h(px(HEADER_HEIGHT))
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .gap(px(8.))
        .px(px(8.))
        .overflow_x_scroll()
        .child(
            studio::supporting(language.text(TextKey::BenchmarkBaselineBuild), colors)
                .flex_shrink_0(),
        )
        .child(run_selector(
            "benchmark-baseline-run",
            &state.run_options,
            state.baseline_id.as_deref(),
            language,
            !busy && !state.run_options.is_empty(),
            colors,
            BenchmarkWorkspace::select_baseline,
            cx,
        ))
        .child(
            studio::supporting(language.text(TextKey::BenchmarkCurrentBuild), colors)
                .flex_shrink_0(),
        )
        .child(run_selector(
            "benchmark-current-run",
            &state.run_options,
            state.current_id.as_deref(),
            language,
            !busy && !state.run_options.is_empty(),
            colors,
            BenchmarkWorkspace::select_current,
            cx,
        ))
        .child(
            action_button(
                "benchmark-compare",
                TextKey::BenchmarkCompare,
                state.baseline_id.is_some() && state.current_id.is_some() && !busy,
                true,
                language,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.compare(cx))),
        )
        .child(
            action_button(
                "benchmark-import-run",
                TextKey::BenchmarkImportRun,
                !busy,
                false,
                language,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.import_run(cx))),
        )
        .child(
            action_button(
                "benchmark-run-benchmark",
                TextKey::BenchmarkOrchestrateRun,
                !busy,
                false,
                language,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.orchestration_open(cx))),
        )
        .child(
            action_button(
                "benchmark-export-pdf",
                TextKey::BenchmarkExportPdf,
                state.report.is_some(),
                false,
                language,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.export_pdf(cx))),
        )
        .child(
            action_button(
                "benchmark-export-csv",
                TextKey::BenchmarkExportCsv,
                state.report.is_some(),
                false,
                language,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.export_csv(cx))),
        )
        .child(
            action_button(
                "benchmark-share-report",
                TextKey::BenchmarkShareReport,
                !state.recent_reports.is_empty(),
                false,
                language,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.share_report(cx))),
        )
        .child(
            action_button(
                "benchmark-compare-settings",
                TextKey::BenchmarkCompareSettings,
                true,
                false,
                language,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.open_compare_settings(cx))),
        )
        .child(
            action_button(
                "benchmark-open-trace",
                TextKey::BenchmarkOpenTraceInPerfetto,
                trace_available,
                false,
                language,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.open_trace(cx))),
        )
        .child(
            action_button(
                "benchmark-ai-analysis",
                TextKey::FoundationAiAnalysis,
                ai_enabled,
                false,
                language,
                colors,
            )
            .on_click(cx.listener(|this, _, window, cx| {
                let request = this.analysis_request();
                if let Some(studio) = this.studio.clone() {
                    studio
                        .update(cx, |studio, cx| {
                            studio.present_ai_request(Destination::Benchmark, request, window, cx)
                        })
                        .ok();
                }
            })),
        )
        .child(div().flex_1())
        .into_any_element()
}

fn gate_key(decision: GateDecision) -> TextKey {
    match decision {
        GateDecision::Pass => TextKey::BenchmarkGatePass,
        GateDecision::Fail => TextKey::BenchmarkGateFail,
        GateDecision::Inconclusive => TextKey::BenchmarkGateInconclusive,
    }
}

fn gate_tone(decision: GateDecision, colors: &StudioColors) -> Rgba {
    match decision {
        GateDecision::Pass => colors.success,
        GateDecision::Fail => colors.error,
        GateDecision::Inconclusive => colors.warning,
    }
}

/// Compose \`BenchmarkSummaryRow\`: four metric cards with the same derived counts and tones.
fn summary(state: &BenchmarkReportsState, language: Language, colors: &StudioColors) -> AnyElement {
    let compared = state.compared_count().to_string();
    let regressions = state.regression_count().to_string();
    let improvements = state.improvement_count().to_string();
    let gate_value = state.gate.as_ref().map_or_else(
        || "—".to_owned(),
        |gate| language.text(gate_key(gate.decision)).to_owned(),
    );
    let gate_support = state.gate.as_ref().map_or_else(String::new, |gate| {
        language.format(
            TextKey::BenchmarkGatePolicyVersion,
            &[("version", &gate.policy_version)],
        )
    });
    let compared_support = if state.report.is_some() {
        language.format(
            TextKey::BenchmarkGatePolicyVersion,
            &[("version", &state.gate_policy.policy_version)],
        )
    } else {
        String::new()
    };
    let stable_support = language.format(
        TextKey::BenchmarkCountStable,
        &[("count", &state.stable_count())],
    );
    let regression_tone = if state.regression_count() > 0 {
        colors.error
    } else {
        colors.secondary_text
    };
    let improvement_tone = if state.improvement_count() > 0 {
        colors.success
    } else {
        colors.secondary_text
    };
    let gate_tone = state.gate.as_ref().map_or(colors.secondary_text, |gate| {
        gate_tone(gate.decision, colors)
    });
    div()
        .id("benchmark-summary")
        .w_full()
        .flex_shrink_0()
        .flex()
        .gap(px(studio::metrics::CARD_GAP))
        .child(div().flex_1().min_w_0().child(studio::kpi_tile(
            "benchmark-summary-compared",
            language.text(TextKey::BenchmarkComparedBenchmarks),
            compared,
            compared_support,
            colors.info,
            colors,
        )))
        .child(div().flex_1().min_w_0().child(studio::kpi_tile(
            "benchmark-summary-regressions",
            language.text(TextKey::BenchmarkRegressions),
            regressions,
            stable_support,
            regression_tone,
            colors,
        )))
        .child(div().flex_1().min_w_0().child(studio::kpi_tile(
            "benchmark-summary-improvements",
            language.text(TextKey::BenchmarkImprovements),
            improvements,
            String::new(),
            improvement_tone,
            colors,
        )))
        .child(div().flex_1().min_w_0().child(studio::kpi_tile(
            "benchmark-summary-gate",
            language.text(TextKey::BenchmarkReleaseGate),
            gate_value,
            gate_support,
            gate_tone,
            colors,
        )))
        .into_any_element()
}

/// Compose \`StatusLine\`: the single error-or-message line, one line, ellipsized and selectable.
fn status_line(state: &BenchmarkReportsState, colors: &StudioColors) -> AnyElement {
    let status = state
        .error
        .as_ref()
        .map(|error| (error, true))
        .or_else(|| state.message.as_ref().map(|message| (message, false)));
    let Some((text, is_error)) = status else {
        return div().h(px(0.)).w_full().flex_shrink_0().into_any_element();
    };
    div()
        .id("benchmark-status-line")
        .h(px(STATUS_HEIGHT))
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .px(px(studio::metrics::WORKSPACE_PADDING))
        .child(
            div()
                .w_full()
                .min_w_0()
                .text_size(px(studio::FONT_SUPPORTING))
                .text_color(if is_error {
                    colors.error
                } else {
                    colors.secondary_text
                })
                .whitespace_nowrap()
                .text_ellipsis()
                .child(studio::selectable(text.clone())),
        )
        .into_any_element()
}

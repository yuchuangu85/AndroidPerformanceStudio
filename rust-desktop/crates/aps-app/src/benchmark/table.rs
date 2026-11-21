//! Compose \`BenchmarkComparisonTable.kt\` parity: the seven-column comparison table with its
//! search field and severity/classification filter menus.
//!
//! Rows are built from \`BenchmarkReportsState::visible_comparisons\`, so a filtered row keeps its
//! original case/metric identity; the selection is a \`ComparisonKey\`, never a row index. The
//! table never invents a score or an evidence value: missing values render as an em dash and every
//! cell repeats the analyzer's own verdict, confidence and classification.

use super::model::{
    BenchmarkReportsState, Severity, comparison_key, format_delta, format_metric_value, severity,
};
use super::page::PageUi;
use crate::benchmark::BenchmarkWorkspace;
use crate::studio::{self, StudioColors};
use aps_benchmark::{Classification, Confidence, MetricComparison};
use aps_ui_foundation::{Language, TextKey};
use gpui_kit::component::{
    button::Button,
    input::Input,
    menu::{DropdownMenu, PopupMenuItem},
};
use gpui_kit::{prelude::*, *};

/// Compose \`COMPARISON_COLUMN_INDICES\`: Benchmark, Baseline, Current, Delta, Severity,
/// Confidence, Classification.
const COLUMN_WEIGHTS: [f32; 7] = [2.4, 1.0, 1.0, 1.2, 1.0, 1.0, 1.2];

/// Bound on the rows materialized in one render. Comparisons come from imported evidence only, so
/// the cap never fabricates rows: past it the table draws the first \`MAX_VISIBLE_ROWS\` rows of the
/// filtered set and reports the real shown/matching/total counts instead of silently dropping the
/// remainder. Selection still resolves through the full comparison set by \`ComparisonKey\`.
const MAX_VISIBLE_ROWS: usize = 2000;

fn severity_key(value: Severity) -> TextKey {
    match value {
        Severity::High => TextKey::BenchmarkSeverityHigh,
        Severity::Low => TextKey::BenchmarkSeverityLow,
        Severity::None => TextKey::BenchmarkSeverityNone,
        Severity::Unknown => TextKey::BenchmarkSeverityUnknown,
        Severity::Incompatible => TextKey::BenchmarkSeverityIncompatible,
    }
}

fn severity_tone(value: Severity, colors: &StudioColors) -> Rgba {
    match value {
        Severity::High | Severity::Incompatible => colors.error,
        Severity::Low => colors.warning,
        Severity::Unknown => colors.info,
        Severity::None => colors.secondary_text,
    }
}

fn classification_key(value: Classification) -> TextKey {
    match value {
        Classification::Regressed => TextKey::BenchmarkClassificationRegressed,
        Classification::Improved => TextKey::BenchmarkClassificationImproved,
        Classification::Stable => TextKey::BenchmarkClassificationStable,
        Classification::Inconclusive => TextKey::BenchmarkClassificationInconclusive,
        Classification::Incompatible => TextKey::BenchmarkClassificationIncompatible,
    }
}

/// Compose \`RegressionClassification.textColor\`.
fn classification_color(value: Classification, colors: &StudioColors) -> Rgba {
    match value {
        Classification::Regressed | Classification::Incompatible => colors.error,
        Classification::Improved => colors.success,
        Classification::Inconclusive => colors.warning,
        Classification::Stable => colors.secondary_text,
    }
}

fn confidence_key(value: Confidence) -> TextKey {
    match value {
        Confidence::Exact => TextKey::BenchmarkConfidenceExact,
        Confidence::Derived => TextKey::BenchmarkConfidenceDerived,
        Confidence::Inferred => TextKey::BenchmarkConfidenceInferred,
        Confidence::Partial => TextKey::BenchmarkConfidencePartial,
        Confidence::Unknown => TextKey::BenchmarkConfidenceUnknown,
    }
}

/// One weighted table cell: the Compose column weights are applied through flex-grow on a zero
/// basis, the same device the battery/GPU tables use.
fn weighted(weight: f32) -> Div {
    div().flex_basis(px(0.)).flex_grow(weight).min_w_0()
}

fn text_run(text: impl Into<SharedString>, color: Rgba, size: f32, mono: bool) -> Div {
    let mut run = div()
        .text_size(px(size))
        .text_color(color)
        .whitespace_nowrap()
        .text_ellipsis()
        .child(studio::selectable(text));
    if mono {
        run = run.font_family("monospace");
    }
    run
}

fn status_chip(label: impl Into<SharedString>, tone: Rgba, colors: &StudioColors) -> Div {
    div()
        .px(px(6.))
        .py(px(2.))
        .rounded(px(4.))
        .bg(tone.alpha(if colors.dark { 0.18 } else { 0.12 }))
        .text_size(px(studio::FONT_SUPPORTING))
        .text_color(if tone == colors.secondary_text {
            colors.secondary_text
        } else {
            tone
        })
        .whitespace_nowrap()
        .child(studio::selectable(label))
}

fn filter_button(id: &'static str, label: String, colors: &StudioColors) -> Button {
    studio::compact_text_button(id, label, true, false, colors).flex_shrink_0()
}

/// Compose \`SeverityFilter\`: an "all severities" reset row plus one row per severity value.
fn severity_filter(
    state: &BenchmarkReportsState,
    language: Language,
    colors: &StudioColors,
    cx: &mut Context<BenchmarkWorkspace>,
) -> AnyElement {
    let selected = state.severity_filter;
    let label = selected.map_or_else(
        || language.text(TextKey::BenchmarkFilterSeverity).to_owned(),
        |value| language.text(severity_key(value)).to_owned(),
    );
    let reset_label = language.text(TextKey::BenchmarkFilterSeverity).to_owned();
    let items: Vec<(Severity, String, bool)> = [
        Severity::High,
        Severity::Low,
        Severity::None,
        Severity::Unknown,
        Severity::Incompatible,
    ]
    .into_iter()
    .map(|value| {
        (
            value,
            language.text(severity_key(value)).to_owned(),
            selected == Some(value),
        )
    })
    .collect();
    let entity = cx.entity().downgrade();
    filter_button("benchmark-severity-filter", label, colors)
        .dropdown_menu(move |menu, _, _| {
            let mut menu = menu;
            let reset = entity.clone();
            menu = menu.item(
                PopupMenuItem::new(reset_label.clone())
                    .checked(selected.is_none())
                    .on_click(move |_, _, cx| {
                        let _ = reset.update(cx, |this, cx| this.set_severity_filter(None, cx));
                    }),
            );
            for (value, item_label, checked) in &items {
                let entity = entity.clone();
                let value = *value;
                let checked = *checked;
                menu = menu.item(
                    PopupMenuItem::new(item_label.clone())
                        .checked(checked)
                        .on_click(move |_, _, cx| {
                            let _ = entity.update(cx, |this, cx| {
                                this.set_severity_filter(Some(value), cx);
                            });
                        }),
                );
            }
            menu
        })
        .into_any_element()
}

/// Compose \`ClassificationFilter\`: an "all statuses" reset row plus one row per classification.
fn classification_filter(
    state: &BenchmarkReportsState,
    language: Language,
    colors: &StudioColors,
    cx: &mut Context<BenchmarkWorkspace>,
) -> AnyElement {
    let selected = state.classification_filter;
    let label = selected.map_or_else(
        || {
            language
                .text(TextKey::BenchmarkFilterClassification)
                .to_owned()
        },
        |value| language.text(classification_key(value)).to_owned(),
    );
    let reset_label = language
        .text(TextKey::BenchmarkFilterClassification)
        .to_owned();
    let items: Vec<(Classification, String, bool)> = [
        Classification::Regressed,
        Classification::Improved,
        Classification::Stable,
        Classification::Inconclusive,
        Classification::Incompatible,
    ]
    .into_iter()
    .map(|value| {
        (
            value,
            language.text(classification_key(value)).to_owned(),
            selected == Some(value),
        )
    })
    .collect();
    let entity = cx.entity().downgrade();
    filter_button("benchmark-classification-filter", label, colors)
        .dropdown_menu(move |menu, _, _| {
            let mut menu = menu;
            let reset = entity.clone();
            menu = menu.item(
                PopupMenuItem::new(reset_label.clone())
                    .checked(selected.is_none())
                    .on_click(move |_, _, cx| {
                        let _ =
                            reset.update(cx, |this, cx| this.set_classification_filter(None, cx));
                    }),
            );
            for (value, item_label, checked) in &items {
                let entity = entity.clone();
                let value = *value;
                let checked = *checked;
                menu = menu.item(
                    PopupMenuItem::new(item_label.clone())
                        .checked(checked)
                        .on_click(move |_, _, cx| {
                            let _ = entity.update(cx, |this, cx| {
                                this.set_classification_filter(Some(value), cx);
                            });
                        }),
                );
            }
            menu
        })
        .into_any_element()
}

fn toolbar(
    state: &BenchmarkReportsState,
    page: &PageUi,
    language: Language,
    colors: &StudioColors,
    cx: &mut Context<BenchmarkWorkspace>,
) -> AnyElement {
    div()
        .id("benchmark-comparison-toolbar")
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .gap(px(8.))
        .child(
            div()
                .flex_1()
                .min_w_0()
                .child(Input::new(page.search_input()).w_full()),
        )
        .child(severity_filter(state, language, colors, cx))
        .child(classification_filter(state, language, colors, cx))
        .into_any_element()
}

fn header_row(language: Language, colors: &StudioColors) -> AnyElement {
    let labels = [
        TextKey::BenchmarkColumnBenchmark,
        TextKey::BenchmarkColumnBaseline,
        TextKey::BenchmarkColumnCurrent,
        TextKey::BenchmarkColumnDelta,
        TextKey::BenchmarkColumnSeverity,
        TextKey::BenchmarkColumnConfidence,
        TextKey::BenchmarkColumnClassification,
    ];
    let mut row = studio::table_header_row(colors).w_full().flex_shrink_0();
    for (index, key) in labels.into_iter().enumerate() {
        row = row.child(
            weighted(COLUMN_WEIGHTS[index]).child(
                div()
                    .text_size(px(studio::FONT_SUPPORTING))
                    .font_weight(FontWeight::SEMIBOLD)
                    .text_color(colors.secondary_text)
                    .whitespace_nowrap()
                    .text_ellipsis()
                    .child(studio::selectable(language.text(key))),
            ),
        );
    }
    row.into_any_element()
}

fn benchmark_cell(comparison: &MetricComparison, colors: &StudioColors) -> Div {
    div()
        .flex()
        .flex_col()
        .min_w_0()
        .child(text_run(
            comparison.metric_name.clone(),
            colors.text,
            studio::FONT_BODY,
            false,
        ))
        .child(text_run(
            comparison.case_identity.clone(),
            colors.muted_text,
            studio::FONT_SUPPORTING,
            true,
        ))
}

fn value_cell(value: Option<f64>, unit: &str, color: Rgba) -> Div {
    text_run(
        format_metric_value(value, unit),
        color,
        studio::FONT_BODY,
        true,
    )
}

fn delta_cell(comparison: &MetricComparison, colors: &StudioColors) -> Div {
    let color = if comparison.relative_delta_percent.is_some() {
        classification_color(comparison.classification, colors)
    } else {
        colors.muted_text
    };
    text_run(
        format_delta(comparison.relative_delta_percent),
        color,
        studio::FONT_BODY,
        true,
    )
}

fn row_cells(comparison: &MetricComparison, language: Language, colors: &StudioColors) -> Vec<Div> {
    let severity = severity(comparison);
    vec![
        weighted(COLUMN_WEIGHTS[0]).child(benchmark_cell(comparison, colors)),
        weighted(COLUMN_WEIGHTS[1]).child(value_cell(
            comparison.baseline_value,
            &comparison.unit,
            colors.secondary_text,
        )),
        weighted(COLUMN_WEIGHTS[2]).child(value_cell(
            comparison.current_value,
            &comparison.unit,
            colors.text,
        )),
        weighted(COLUMN_WEIGHTS[3]).child(delta_cell(comparison, colors)),
        weighted(COLUMN_WEIGHTS[4]).child(status_chip(
            language.text(severity_key(severity)),
            severity_tone(severity, colors),
            colors,
        )),
        weighted(COLUMN_WEIGHTS[5]).child(text_run(
            language.text(confidence_key(comparison.confidence)),
            colors.secondary_text,
            studio::FONT_SUPPORTING,
            false,
        )),
        weighted(COLUMN_WEIGHTS[6]).child(text_run(
            language.text(classification_key(comparison.classification)),
            classification_color(comparison.classification, colors),
            studio::FONT_SUPPORTING,
            false,
        )),
    ]
}

fn row_background(selected: bool, striped: bool, colors: &StudioColors) -> Rgba {
    if selected {
        colors.selected_row
    } else if striped {
        colors.detail_row_light
    } else {
        rgba(0x00000000)
    }
}

pub(in crate::benchmark) fn render(
    state: &BenchmarkReportsState,
    page: &mut PageUi,
    language: Language,
    cx: &mut Context<BenchmarkWorkspace>,
) -> AnyElement {
    let colors = StudioColors::from_theme(cx);
    let rows = state.visible_comparisons();
    let filtered = rows.len();
    let rendered = filtered.min(MAX_VISIBLE_ROWS);
    let mut panel = studio::card(&colors)
        .size_full()
        .min_h_0()
        .min_w_0()
        .child(
            studio::section_title(language.text(TextKey::BenchmarkComparisonResults), &colors)
                .flex_shrink_0(),
        )
        .child(toolbar(state, page, language, &colors, cx))
        .child(header_row(language, &colors));
    if rows.is_empty() {
        panel = panel.child(
            div()
                .id("benchmark-comparison-empty")
                .flex_1()
                .min_h_0()
                .w_full()
                .p(px(8.))
                .child(studio::supporting(
                    language.text(TextKey::BenchmarkNoComparisons),
                    &colors,
                )),
        );
        return panel.into_any_element();
    }
    let mut list = div()
        .id("benchmark-comparison-rows")
        .w_full()
        .flex_1()
        .min_h_0()
        .flex()
        .flex_col()
        .overflow_y_scroll();
    for (index, comparison) in rows.iter().take(MAX_VISIBLE_ROWS).enumerate() {
        let key = comparison_key(comparison);
        let selected = state.selected.as_ref() == Some(&key);
        let mut row = div()
            .id(("benchmark-comparison-row", index))
            .w_full()
            .flex_shrink_0()
            .flex()
            .items_center()
            .px(px(studio::metrics::COMPACT_PADDING))
            .py(px(4.))
            .cursor_pointer()
            .bg(row_background(selected, index % 2 == 1, &colors))
            .on_click(cx.listener(move |this, _, _, cx| {
                let selected = this.state.selected.as_ref() == Some(&key);
                let next = if selected { None } else { Some(key.clone()) };
                this.select_comparison(next, cx);
            }));
        for cell in row_cells(comparison, language, &colors) {
            row = row.child(cell);
        }
        list = list.child(row);
    }
    panel = panel.child(list);
    if filtered > rendered {
        panel = panel.child(div().w_full().flex_shrink_0().child(studio::supporting(
            language.format(
                TextKey::BenchmarkResultCount,
                &[
                    ("shown", &rendered),
                    ("matching", &filtered),
                    ("total", &state.compared_count()),
                ],
            ),
            &colors,
        )));
    }
    panel.into_any_element()
}

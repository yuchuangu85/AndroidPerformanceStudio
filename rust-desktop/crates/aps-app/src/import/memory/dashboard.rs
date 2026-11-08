//! Dashboard: Overview metric cards + sortable class histogram.
//!
//! MemoryProfilerScreen.kt (Dashboard / Overview / Histogram) is the behavior
//! oracle: four StudioMetricCards fed by the whole-snapshot summary plus
//! activity findings, then a class histogram sorted exactly like
//! MemoryProfilerPresenter.sortClasses (metric descending, class name
//! ascending). Class names are matched by name only; no metric is inferred.
use super::ImportWorkspace;
use super::comparison::{display_class_name, format_bytes, format_count, row_background};
use super::state::DashboardSort;
use super::widgets::{
    TABLE_FONT, TABLE_HEADER_HEIGHT, TABLE_LINE, TABLE_ROW_HEIGHT, header_cell, metric_cell,
};
use crate::studio::{self, StudioColors};
use aps_memory::ClassStats;
use aps_ui_foundation::{Language, TextKey, typography};
use gpui_kit::{
    component::scroll::{ScrollableElement, Scrollbar, ScrollbarAxis},
    prelude::*,
    *,
};
use std::rc::Rc;

fn histogram_sort(sort: DashboardSort) -> aps_memory::DashboardSort {
    match sort {
        DashboardSort::Count => aps_memory::DashboardSort::Count,
        DashboardSort::Shallow => aps_memory::DashboardSort::Shallow,
    }
}

fn metric_value(loaded: bool, value: String, unavailable: &str) -> String {
    if loaded {
        value
    } else {
        unavailable.to_owned()
    }
}

/// Compose StudioMetricCard: neutral card with a semibold 13 px accent label
/// over a 16/19 semibold value. The accent never paints the card fill. Card
/// interior is 8 px padding + 4 px spacing, so the row is 58 px tall (the
/// Compose overview card measures 58).
fn overview_metric_card(
    index: usize,
    label: &'static str,
    value: String,
    colors: &StudioColors,
) -> Stateful<Div> {
    div()
        .id(("memory-overview-metric", index))
        .flex_1()
        .min_w(px(140.))
        .flex()
        .flex_col()
        .gap(px(4.))
        .p(px(8.))
        .bg(colors.panel)
        .border_1()
        .border_color(colors.accent.alpha(0.5))
        .rounded(px(studio::metrics::CARD_RADIUS))
        .child(
            div()
                .text_size(px(typography::BODY))
                .line_height(px(17.))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(colors.accent)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(crate::studio::selectable(label)),
        )
        .child(
            div()
                .text_size(px(typography::TITLE))
                .line_height(px(19.))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(colors.text)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(crate::studio::selectable(value)),
        )
}

/// 24 px section-surface header with the same 1 px accent column grid as the
/// body rows; labels are 13 px semibold and the active sort shows Compose's
/// arrow glyph (Count ↓ / Shallow ↓).
fn histogram_header(
    sort: DashboardSort,
    colors: &StudioColors,
    language: Language,
    cx: &mut Context<ImportWorkspace>,
) -> AnyElement {
    let count_label = if sort == DashboardSort::Count {
        TextKey::MemoryPageSortCount
    } else {
        TextKey::MemoryPageCount
    };
    let shallow_label = if sort == DashboardSort::Shallow {
        TextKey::MemoryPageSortShallow
    } else {
        TextKey::MemoryPageShallow
    };
    div()
        .id("memory-dashboard-header")
        .w_full()
        .h(px(TABLE_HEADER_HEIGHT))
        .flex_shrink_0()
        .flex()
        .items_center()
        .bg(colors.section_background)
        .child(
            header_cell(
                None,
                language.text(TextKey::MemoryPageClassName),
                false,
                false,
                colors,
            )
            .pl(px(8.)),
        )
        .child(
            header_cell(Some(96.), language.text(count_label), true, true, colors)
                .id("memory-dashboard-sort-count")
                .cursor_pointer()
                .on_click(cx.listener(|this, _, _, cx| {
                    this.memory_set_dashboard_sort(DashboardSort::Count, cx)
                })),
        )
        .child(
            header_cell(Some(112.), language.text(shallow_label), true, true, colors)
                .id("memory-dashboard-sort-shallow")
                .cursor_pointer()
                .on_click(cx.listener(|this, _, _, cx| {
                    this.memory_set_dashboard_sort(DashboardSort::Shallow, cx)
                })),
        )
        .child(header_cell(
            Some(140.),
            language.text(TextKey::MemoryPageRetained),
            true,
            true,
            colors,
        ))
        .into_any_element()
}

fn histogram_row(
    index: usize,
    stats: &ClassStats,
    highlighted: Option<&str>,
    entity: &Entity<ImportWorkspace>,
    colors: &StudioColors,
    language: Language,
) -> AnyElement {
    let class_name = stats.class_name.clone();
    let entity = entity.clone();
    let background = if highlighted == Some(stats.class_name.as_str()) {
        colors.selected_row
    } else {
        row_background(colors, index)
    };
    let retained = stats
        .retained_size
        .map(format_bytes)
        .unwrap_or_else(|| language.text(TextKey::MemoryPageUnavailable).to_owned());
    div()
        .id(("memory-dashboard-row", index))
        .h(px(TABLE_ROW_HEIGHT))
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .bg(background)
        .cursor_pointer()
        .on_click(move |_, _, cx| {
            let class_name = class_name.clone();
            let _ = entity.update(cx, |this, cx| {
                this.memory_highlight_class(class_name, cx);
            });
        })
        .child(
            div()
                .flex_1()
                .min_w_0()
                .px(px(8.))
                .text_size(px(TABLE_FONT))
                .line_height(px(TABLE_LINE))
                .text_color(colors.text)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(crate::studio::selectable(display_class_name(stats))),
        )
        .child(metric_cell(
            Some(96.),
            format_count(stats.instance_count),
            colors,
        ))
        .child(metric_cell(
            Some(112.),
            format_bytes(stats.shallow_size),
            colors,
        ))
        .child(metric_cell(Some(140.), retained, colors))
        .into_any_element()
}

impl ImportWorkspace {
    pub(super) fn render_memory_dashboard(
        &mut self,
        colors: &StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let loaded = self.memory_state.snapshot.is_some();
        let summary = self.memory_state.summary_metrics();
        let activity = self.memory_state.activity_count();
        let sort = self.memory_state.dashboard_sort;
        let highlighted = self.memory_state.highlighted_class.clone();
        let classes = self
            .memory_state
            .snapshot
            .as_ref()
            .map(|snapshot| {
                aps_memory::sort_histogram_classes(&snapshot.classes, histogram_sort(sort))
            })
            .unwrap_or_default();
        let entity = cx.entity();
        let unavailable = language.text(TextKey::MemoryPageUnavailable).to_owned();
        let activity_text = format_count(activity);
        let activity_value = if loaded {
            language.format(TextKey::MemoryPageCountValue, &[("arg_1", &activity_text)])
        } else {
            unavailable.clone()
        };
        let metrics = [
            (
                TextKey::MemoryPageHeapSize,
                metric_value(loaded, format_bytes(summary.shallow_size), &unavailable),
            ),
            (
                TextKey::MemoryPageObjects,
                metric_value(loaded, format_count(summary.object_count), &unavailable),
            ),
            (
                TextKey::MemoryPageClasses,
                metric_value(loaded, format_count(summary.class_count), &unavailable),
            ),
            (TextKey::MemoryPageActivity, activity_value),
        ];

        let overview = div()
            .id("memory-dashboard-overview")
            .w_full()
            .flex_shrink_0()
            .flex()
            .flex_col()
            .gap(px(4.))
            .child(studio::section_title(
                language.text(TextKey::MemoryPageOverview),
                colors,
            ))
            .child(
                div()
                    .id("memory-overview-metrics")
                    .w_full()
                    .h(px(58.))
                    .flex()
                    .gap(px(4.))
                    .overflow_x_scrollbar()
                    .children(
                        metrics
                            .into_iter()
                            .enumerate()
                            .map(|(index, (key, value))| {
                                overview_metric_card(index, language.text(key), value, colors)
                            }),
                    ),
            );

        let mut histogram = studio::card(colors)
            .id("memory-dashboard-histogram")
            .flex_1()
            .min_h_0()
            .w_full()
            .p_0()
            .gap_0()
            .child(
                div()
                    .px(px(8.))
                    .pt(px(8.))
                    .pb(px(4.))
                    .child(studio::section_title(
                        language.text(TextKey::MemoryPageClassHistogram),
                        colors,
                    )),
            )
            .child(histogram_header(sort, colors, language, cx))
            .child(studio::divider(colors));
        if classes.is_empty() {
            histogram = histogram.child(
                div()
                    .p(px(8.))
                    .text_size(px(TABLE_FONT))
                    .line_height(px(TABLE_LINE))
                    .text_color(colors.secondary_text)
                    .child(crate::studio::selectable(
                        language
                            .text(TextKey::MemoryPageImportOrDumpAnHprofFileToShowClassHistogram)
                            .to_owned(),
                    )),
            );
        } else {
            let scroll_handle = self.memory_state.dashboard_scroll.clone();
            let rows = Rc::new(classes);
            let colors_copy = *colors;
            let list = uniform_list("memory-dashboard-rows", rows.len(), {
                let rows = rows.clone();
                let highlighted = highlighted.clone();
                let entity = entity.clone();
                move |range, _, _| {
                    let mut items = Vec::with_capacity(range.len());
                    for index in range {
                        if let Some(stats) = rows.get(index) {
                            items.push(histogram_row(
                                index,
                                stats,
                                highlighted.as_deref(),
                                &entity,
                                &colors_copy,
                                language,
                            ));
                        }
                    }
                    items
                }
            })
            .h_full()
            .w_full()
            .track_scroll(&scroll_handle);
            histogram = histogram.child(
                div()
                    .relative()
                    .flex_1()
                    .min_h_0()
                    .w_full()
                    .child(list)
                    .child(
                        div().absolute().inset_0().child(
                            Scrollbar::new(&scroll_handle)
                                .id("memory-dashboard-scrollbar")
                                .axis(ScrollbarAxis::Vertical)
                                .viewport_from_layout(),
                        ),
                    ),
            );
        }

        div()
            .id("memory-dashboard")
            .flex_1()
            .min_h_0()
            .w_full()
            .flex()
            .flex_col()
            .gap(px(4.))
            .p(px(4.))
            .child(overview)
            .child(histogram)
            .into_any_element()
    }
}

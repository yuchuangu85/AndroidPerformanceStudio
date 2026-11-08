//! Class-level snapshot comparison. Object IDs never cross snapshots.
//!
//! MemorySnapshotComparison.kt is the behavior oracle: A/B snapshot selection
//! from the open snapshot tabs, a Compare action, and a typed class table
//! (Class / A Count / B Count / Delta Count / A Retained / B Retained /
//! Delta Retained) sorted by absolute retained delta. Metrics the worker did
//! not publish stay an em dash; nothing is inferred across dumps.
use super::ImportWorkspace;
use super::dominators::{memory_heap_distribution, memory_summary_row};
use super::state::{ClassifierDelta, ComparisonReport};
use super::widgets::{
    TABLE_FONT, TABLE_HEADER_HEIGHT, TABLE_LINE, TABLE_ROW_HEIGHT, header_cell, metric_cell,
    toolbar_button,
};
use crate::studio::{self, StudioColors};
use aps_memory::{ClassStats, MemorySnapshot};
use aps_ui_foundation::{Language, TextKey, typography};
use gpui_kit::{
    component::{
        Disableable,
        button::{Button, ButtonVariants},
        menu::{DropdownMenu, PopupMenuItem},
        scroll::{Scrollbar, ScrollbarAxis},
    },
    prelude::*,
    *,
};
use std::collections::BTreeMap;

// ---------------------------------------------------------------------------
// Shared dense-memory formatters (Compose MemoryFormatters.kt).
// ---------------------------------------------------------------------------

/// Unavailable metric. The comparison renders a missing retained size or delta
/// as an em dash so the numeric columns stay aligned; nothing is inferred.
pub(super) const UNAVAILABLE_DASH: &str = "\u{2014}";

/// formatBytes: 1 decimal MB / KB, whole bytes below 1 KiB (Locale.US shape).
pub(super) fn format_bytes(bytes: u64) -> String {
    const KB: f64 = 1024.;
    const MB: f64 = 1024. * 1024.;
    let value = bytes as f64;
    if value >= MB {
        format!("{:.1} MB", value / MB)
    } else if value >= KB {
        format!("{:.1} KB", value / KB)
    } else {
        format!("{bytes} B")
    }
}

/// integer: grouped decimal counts ("12,451") without a locale dependency.
pub(super) fn format_count(value: u64) -> String {
    let raw = value.to_string();
    let mut grouped = String::with_capacity(raw.len() + raw.len() / 3);
    for (index, digit) in raw.chars().enumerate() {
        if index > 0 && (raw.len() - index) % 3 == 0 {
            grouped.push(',');
        }
        grouped.push(digit);
    }
    grouped
}

/// Signed count delta with Compose's signedCount shape.
pub(super) fn signed_count(value: i128) -> String {
    if value > 0 {
        format!("+{}", format_count(value.unsigned_abs() as u64))
    } else if value < 0 {
        format!("-{}", format_count(value.unsigned_abs() as u64))
    } else {
        "0".to_owned()
    }
}

/// Signed byte delta with Compose's signedMemoryBytes shape.
pub(super) fn signed_bytes(value: i128) -> String {
    if value > 0 {
        format!("+{}", format_bytes(value.unsigned_abs() as u64))
    } else if value < 0 {
        format!("-{}", format_bytes(value.unsigned_abs() as u64))
    } else {
        format_bytes(0)
    }
}

/// ClassStats display name: keeps the obfuscated name in parentheses.
pub(super) fn display_class_name(stats: &ClassStats) -> String {
    match stats.obfuscated_class_name.as_deref() {
        Some(obfuscated) => format!("{} ({})", stats.class_name, obfuscated),
        None => stats.class_name.clone(),
    }
}

/// Dense-table row surface. Compose's surfaceContainerLow and surface both map
/// to the workspace-style panel, so the oracle tables have no zebra striping.
pub(super) fn row_background(colors: &StudioColors, _index: usize) -> Rgba {
    colors.panel
}

// ---------------------------------------------------------------------------
// Pure comparison projection.
// ---------------------------------------------------------------------------

/// B - A instance-count delta (never fabricates a cross-dump identity).
pub(super) fn count_delta(row: &ClassifierDelta) -> i128 {
    i128::from(row.b_count) - i128::from(row.a_count)
}

/// B - A retained-size delta; unknown when either side is unavailable.
pub(super) fn retained_delta(row: &ClassifierDelta) -> Option<i128> {
    Some(i128::from(row.b_retained?) - i128::from(row.a_retained?))
}

/// Merge two bounded class histograms by class name only. Sorted by absolute
/// retained delta (known deltas first), then class name, exactly the table the
/// Compose diff card shows. Pure so it can run on the background executor.
pub(super) fn compare(
    a: &MemorySnapshot,
    b: &MemorySnapshot,
    a_label: String,
    b_label: String,
) -> ComparisonReport {
    #[derive(Default)]
    struct Metrics {
        a_count: u64,
        b_count: u64,
        a_retained: Option<u64>,
        b_retained: Option<u64>,
    }
    let mut merged: BTreeMap<String, Metrics> = BTreeMap::new();
    for class in &a.classes {
        let entry = merged.entry(class.class_name.clone()).or_default();
        entry.a_count = class.instance_count;
        entry.a_retained = class.retained_size;
    }
    for class in &b.classes {
        let entry = merged.entry(class.class_name.clone()).or_default();
        entry.b_count = class.instance_count;
        entry.b_retained = class.retained_size;
    }
    let mut rows = merged
        .into_iter()
        .map(|(class_name, metrics)| ClassifierDelta {
            class_name,
            a_count: metrics.a_count,
            b_count: metrics.b_count,
            a_retained: metrics.a_retained,
            b_retained: metrics.b_retained,
        })
        .collect::<Vec<_>>();
    rows.sort_by(|left, right| {
        retained_delta(left)
            .map(i128::unsigned_abs)
            .is_none()
            .cmp(&retained_delta(right).map(i128::unsigned_abs).is_none())
            .then_with(|| {
                let left_delta = retained_delta(left).map(i128::unsigned_abs).unwrap_or(0);
                let right_delta = retained_delta(right).map(i128::unsigned_abs).unwrap_or(0);
                right_delta.cmp(&left_delta)
            })
            .then_with(|| left.class_name.cmp(&right.class_name))
    });
    ComparisonReport {
        a_label,
        b_label,
        a_digest: a.source_digest.clone(),
        b_digest: b.source_digest.clone(),
        rows,
    }
}

// ---------------------------------------------------------------------------
// View.
// ---------------------------------------------------------------------------

/// Compose's default selection: A is the newest inactive snapshot, B is the
/// active tab. Once a comparison ran, comparison_pair is authoritative.
fn default_pair(tabs: &[(u64, String, bool)]) -> Option<(u64, u64)> {
    let active = tabs
        .iter()
        .find(|(_, _, active)| *active)
        .map(|(id, _, _)| *id)?;
    let a = tabs
        .iter()
        .filter(|(id, _, _)| *id != active)
        .map(|(id, _, _)| *id)
        .max()?;
    Some((a, active))
}

/// Compose DropdownSelector control with the 13 px body label used by the diff
/// A/B pickers.
fn comparison_selector(
    id: &'static str,
    label: String,
    enabled: bool,
    colors: &StudioColors,
) -> Button {
    Button::new(id)
        .ghost()
        .h(px(26.))
        .px(px(8.))
        .rounded(px(4.))
        .bg(colors.section_background)
        .border_1()
        .border_color(colors.accent)
        .disabled(!enabled)
        .accessibility_label(label.clone())
        .child(
            div()
                .flex()
                .items_center()
                .gap(px(4.))
                .child(
                    div()
                        .text_size(px(typography::BODY))
                        .line_height(px(16.))
                        .text_color(if enabled {
                            colors.accent
                        } else {
                            colors.accent.alpha(0.45)
                        })
                        .whitespace_nowrap()
                        .child(label),
                )
                .child(
                    div()
                        .text_size(px(10.))
                        .text_color(colors.accent.alpha(if enabled { 1. } else { 0.45 }))
                        .child("\u{2304}"),
                ),
        )
}

/// 24 px section-surface header with the shared 1 px accent column grid.
fn comparison_header(language: Language, colors: &StudioColors) -> AnyElement {
    div()
        .id("memory-comparison-header-row")
        .w_full()
        .h(px(TABLE_HEADER_HEIGHT))
        .flex_shrink_0()
        .flex()
        .items_center()
        .bg(colors.section_background)
        .child(
            header_cell(
                None,
                language.text(TextKey::MemoryPageDiffClass),
                false,
                false,
                colors,
            )
            .pl(px(8.)),
        )
        .child(header_cell(
            Some(96.),
            language.text(TextKey::MemoryPageMemoryBeforeCount),
            true,
            true,
            colors,
        ))
        .child(header_cell(
            Some(96.),
            language.text(TextKey::MemoryPageMemoryAfterCount),
            true,
            true,
            colors,
        ))
        .child(header_cell(
            Some(96.),
            language.text(TextKey::MemoryPageMemoryDeltaCount),
            true,
            true,
            colors,
        ))
        .child(header_cell(
            Some(112.),
            language.text(TextKey::MemoryPageMemoryBeforeRetained),
            true,
            true,
            colors,
        ))
        .child(header_cell(
            Some(112.),
            language.text(TextKey::MemoryPageMemoryAfterRetained),
            true,
            true,
            colors,
        ))
        .child(header_cell(
            Some(112.),
            language.text(TextKey::MemoryPageMemoryDeltaRetained),
            true,
            true,
            colors,
        ))
        .into_any_element()
}

/// 20 px body-compact value cell with the continuous accent column grid.
fn value_cell(width: f32, text: String, color: Rgba, border: Rgba) -> Div {
    div()
        .w(px(width))
        .flex_shrink_0()
        .h_full()
        .flex()
        .items_center()
        .justify_end()
        .px(px(8.))
        .border_l_1()
        .border_color(border)
        .text_size(px(TABLE_FONT))
        .line_height(px(TABLE_LINE))
        .text_color(color)
        .text_align(TextAlign::Right)
        .whitespace_nowrap()
        .text_ellipsis()
        .child(crate::studio::selectable(text))
}

fn comparison_row(
    index: usize,
    row: &ClassifierDelta,
    colors: &StudioColors,
    _language: Language,
) -> AnyElement {
    let count = count_delta(row);
    let retained = retained_delta(row);
    let missing = || UNAVAILABLE_DASH.to_owned();
    div()
        .id(("memory-comparison-row", index))
        .h(px(TABLE_ROW_HEIGHT))
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .bg(row_background(colors, index))
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
                .child(crate::studio::selectable(row.class_name.clone())),
        )
        .child(metric_cell(Some(96.), format_count(row.a_count), colors))
        .child(metric_cell(Some(96.), format_count(row.b_count), colors))
        .child(value_cell(
            96.,
            signed_count(count),
            if count > 0 { colors.error } else { colors.text },
            colors.border,
        ))
        .child(metric_cell(
            Some(112.),
            row.a_retained.map(format_bytes).unwrap_or_else(missing),
            colors,
        ))
        .child(metric_cell(
            Some(112.),
            row.b_retained.map(format_bytes).unwrap_or_else(missing),
            colors,
        ))
        .child(value_cell(
            112.,
            retained.map(signed_bytes).unwrap_or_else(missing),
            if retained.is_some_and(|delta| delta > 0) {
                colors.error
            } else {
                colors.text
            },
            colors.border,
        ))
        .into_any_element()
}

fn snapshot_selector(
    id: &'static str,
    role: TextKey,
    is_a: bool,
    selected: Option<u64>,
    pair: Option<(u64, u64)>,
    tabs: &[(u64, String, bool)],
    language: Language,
    colors: &StudioColors,
    entity: &Entity<ImportWorkspace>,
) -> AnyElement {
    let other = pair.map(|(a, b)| if is_a { b } else { a });
    let label = selected
        .and_then(|id| tabs.iter().find(|tab| tab.0 == id).map(|tab| tab.1.clone()))
        .unwrap_or_else(|| language.text(role).to_owned());
    let menu_entity = entity.clone().downgrade();
    let tabs = tabs.to_vec();
    let button = comparison_selector(id, label, !tabs.is_empty(), colors).dropdown_menu(
        move |menu, _, _| {
            let mut menu = menu.min_w(px(220.));
            for (tab_id, tab_label, _) in &tabs {
                let workspace = menu_entity.clone();
                let tab_id = *tab_id;
                let label = if selected == Some(tab_id) {
                    format!("\u{2713} {tab_label}")
                } else {
                    tab_label.clone()
                };
                let new_pair = if is_a {
                    (tab_id, other.unwrap_or(tab_id))
                } else {
                    (other.unwrap_or(tab_id), tab_id)
                };
                menu = menu.item(PopupMenuItem::new(label).on_click(move |_, _, cx| {
                    let _ = workspace.update(cx, |this, cx| {
                        this.memory_state.comparison_pair = Some(new_pair);
                        cx.notify();
                    });
                }));
            }
            menu
        },
    );
    button.into_any_element()
}

fn hint(text: String, color: Rgba) -> AnyElement {
    div()
        .py(px(12.))
        .text_size(px(TABLE_FONT))
        .line_height(px(TABLE_LINE))
        .text_color(color)
        .child(crate::studio::selectable(text))
        .into_any_element()
}

impl ImportWorkspace {
    pub(super) fn render_memory_comparison(
        &mut self,
        colors: &StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let loaded = self.memory_state.snapshot.is_some();
        let tabs = self.memory_snapshot_tabs();
        let pair = self
            .memory_state
            .comparison_pair
            .or_else(|| default_pair(&tabs));
        let report = self.memory_state.comparison.clone();
        let loading = self.memory_state.comparison_loading;
        let error = self.memory_state.comparison_error.clone();
        let entity = cx.entity();
        let snapshot_summary = self.memory_state.summary_metrics();
        let leak_suspects = self.memory_state.leak_suspect_count();
        let distribution = self.memory_state.snapshot.as_ref().and_then(|snapshot| {
            (snapshot
                .summary
                .heap_segments
                .iter()
                .any(|segment| segment.size_bytes > 0)
                || !snapshot.classes.is_empty())
            .then(|| {
                memory_heap_distribution(
                    "memory-comparison-distribution",
                    snapshot,
                    colors,
                    language,
                )
            })
        });

        let can_compare = pair.is_some_and(|(a, b)| a != b) && !loading;
        let compare_pair = pair;
        let header = div()
            .id("memory-comparison-header")
            .w_full()
            .flex_shrink_0()
            .flex()
            .items_center()
            .flex_wrap()
            .gap(px(8.))
            .py(px(4.))
            .child(studio::section_title(
                language.text(TextKey::MemoryPageComparisonTitle),
                colors,
            ))
            .child(snapshot_selector(
                "memory-comparison-select-a",
                TextKey::MemoryPageMemoryCompareA,
                true,
                pair.map(|(a, _)| a),
                pair,
                &tabs,
                language,
                colors,
                &entity,
            ))
            .child(snapshot_selector(
                "memory-comparison-select-b",
                TextKey::MemoryPageMemoryCompareB,
                false,
                pair.map(|(_, b)| b),
                pair,
                &tabs,
                language,
                colors,
                &entity,
            ))
            .child(
                toolbar_button(
                    "memory-comparison-run",
                    language.text(TextKey::MemoryPageMemoryCompare),
                    can_compare,
                    false,
                    colors,
                )
                .on_click(cx.listener(move |this, _, _, cx| {
                    if let Some((a, b)) = compare_pair {
                        this.memory_set_comparison_pair(a, b, cx);
                    }
                })),
            );

        let mut body = div()
            .id("memory-comparison-body")
            .flex_1()
            .min_h_0()
            .w_full()
            .flex()
            .flex_col()
            .gap(px(4.))
            .child(studio::supporting(
                language.text(TextKey::MemoryPageMemoryCompareLimit),
                colors,
            ));

        if loading {
            body = body.child(hint(
                language.text(TextKey::MemoryPageWorking).to_owned(),
                colors.secondary_text,
            ));
        } else if let Some(error) = error {
            body = body.child(hint(error, colors.error));
        } else if let Some(report) = report {
            let summary = format!(
                "{}: {}  |  {}: {}",
                language.text(TextKey::MemoryPageMemoryCompareA),
                report.a_label,
                language.text(TextKey::MemoryPageMemoryCompareB),
                report.b_label
            );
            body = body.child(
                div()
                    .w_full()
                    .flex_shrink_0()
                    .flex()
                    .items_center()
                    .gap(px(12.))
                    .pb(px(4.))
                    .child(studio::dense_label(summary, colors.secondary_text))
                    .child(studio::dense_label(
                        language
                            .format(TextKey::MemoryPageClasses, &[("arg_1", &report.rows.len())]),
                        colors.secondary_text,
                    )),
            );
            let mut table = studio::card(colors)
                .id("memory-comparison-table")
                .flex_1()
                .min_h_0()
                .w_full()
                .p_0()
                .gap_0()
                .child(comparison_header(language, colors))
                .child(studio::divider(colors));
            if report.rows.is_empty() {
                table = table.child(hint(
                    language.text(TextKey::MemoryPageMemoryNoGrowth).to_owned(),
                    colors.secondary_text,
                ));
                body = body.child(table);
            } else {
                let scroll_handle = self.memory_state.comparison_scroll.clone();
                let report_for_list = report.clone();
                let colors_copy = *colors;
                let list = uniform_list("memory-comparison-rows", report.rows.len(), {
                    let report = report_for_list;
                    move |range, _, _| {
                        let mut items = Vec::with_capacity(range.len());
                        for index in range {
                            if let Some(row) = report.rows.get(index) {
                                items.push(comparison_row(index, row, &colors_copy, language));
                            }
                        }
                        items
                    }
                })
                .h_full()
                .w_full()
                .track_scroll(&scroll_handle);
                table = table.child(
                    div()
                        .relative()
                        .flex_1()
                        .min_h_0()
                        .w_full()
                        .child(list)
                        .child(
                            div().absolute().inset_0().child(
                                Scrollbar::new(&scroll_handle)
                                    .id("memory-comparison-scrollbar")
                                    .axis(ScrollbarAxis::Vertical)
                                    .viewport_from_layout(),
                            ),
                        ),
                );
                body = body.child(table);
            }
        } else {
            body = body.child(hint(
                language.text(TextKey::MemoryPageDiffUnavailable).to_owned(),
                colors.secondary_text,
            ));
        }

        let mut root = div()
            .id("memory-comparison")
            .flex_1()
            .min_h_0()
            .w_full()
            .flex()
            .flex_col()
            .gap(px(4.))
            .p(px(4.))
            // Compose wraps Diff in MemoryInvestigationWorkspace: the whole
            // snapshot metrics and heap distribution stay visible above the
            // class-level comparison.
            .child(
                div()
                    .id("memory-comparison-kpis")
                    .w_full()
                    .h(px(44.))
                    .flex_shrink_0()
                    .child(memory_summary_row(
                        "memory-comparison-summary",
                        &snapshot_summary,
                        leak_suspects,
                        loaded,
                        language,
                        colors,
                    )),
            );
        if let Some(distribution) = distribution {
            root = root.child(distribution);
        }
        root.child(header)
            .child(studio::divider(colors))
            .child(body)
            .into_any_element()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_memory::ClassStats;

    fn class(name: &str, count: u64, retained: Option<u64>) -> ClassStats {
        ClassStats {
            class_name: name.to_owned(),
            instance_count: count,
            retained_size: retained,
            ..ClassStats::default()
        }
    }

    #[::core::prelude::v1::test]
    fn formatters_match_compose_memory_formatters() {
        assert_eq!(format_bytes(0), "0 B");
        assert_eq!(format_bytes(32), "32 B");
        assert_eq!(format_bytes(1024), "1.0 KB");
        assert_eq!(format_bytes(6 * 1024 * 1024), "6.0 MB");
        assert_eq!(format_count(12451), "12,451");
        assert_eq!(format_count(384), "384");
        assert_eq!(signed_count(2), "+2");
        assert_eq!(signed_count(-3), "-3");
        assert_eq!(signed_count(0), "0");
        assert_eq!(signed_bytes(2048), "+2.0 KB");
        assert_eq!(signed_bytes(-1024), "-1.0 KB");
        assert_eq!(signed_bytes(0), "0 B");
    }

    #[::core::prelude::v1::test]
    fn compare_merges_by_name_sorts_by_absolute_retained_delta_and_keeps_unknown() {
        let a = MemorySnapshot {
            source_digest: "a".into(),
            classes: vec![
                class("example.Same", 2, Some(100)),
                class("example.Grown", 1, Some(10)),
                class("example.Unknown", 3, None),
            ],
            ..MemorySnapshot::default()
        };
        let b = MemorySnapshot {
            source_digest: "b".into(),
            classes: vec![
                class("example.Same", 2, Some(100)),
                class("example.Grown", 1, Some(1000)),
                class("example.Unknown", 4, Some(7)),
            ],
            ..MemorySnapshot::default()
        };
        let report = compare(&a, &b, "a.hprof".into(), "b.hprof".into());
        assert_eq!(report.rows.len(), 3);
        assert_eq!(report.rows[0].class_name, "example.Grown");
        assert_eq!(retained_delta(&report.rows[0]), Some(990));
        assert_eq!(count_delta(&report.rows[0]), 0);
        let unknown = report
            .rows
            .iter()
            .find(|row| row.class_name == "example.Unknown")
            .unwrap();
        assert_eq!(retained_delta(unknown), None);
        assert_eq!(count_delta(unknown), 1);
        assert_eq!(report.rows.last().unwrap().class_name, "example.Unknown");
    }
}

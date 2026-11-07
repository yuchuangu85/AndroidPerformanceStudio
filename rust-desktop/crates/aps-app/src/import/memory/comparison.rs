//! Class-level snapshot comparison. Object IDs never cross snapshots.
//!
//! MemorySnapshotComparison.kt is the behavior oracle: A/B snapshot selection
//! from the open snapshot tabs, a Compare action, and a typed class table
//! (Class / A Count / B Count / Delta Count / A Retained / B Retained /
//! Delta Retained) sorted by absolute retained delta. Metrics the worker did
//! not publish stay an em dash; nothing is inferred across dumps.
use super::ImportWorkspace;
use super::state::memory_row_height;
use super::state::{ClassifierDelta, ComparisonReport};
use crate::studio::{self, StudioColors};
use aps_memory::{ClassStats, MemorySnapshot};
use aps_ui_foundation::{Language, TextKey, typography};
use gpui_kit::{
    component::{
        menu::{DropdownMenu, PopupMenuItem},
        scroll::{ScrollableElement, Scrollbar, ScrollbarAxis},
    },
    prelude::*,
    *,
};
use std::collections::BTreeMap;

// ---------------------------------------------------------------------------
// Shared dense-memory formatters (Compose MemoryFormatters.kt).
// ---------------------------------------------------------------------------

/// Unavailable metric. The Compose comparison renders a missing retained size
/// or delta as unavailable; the Rust dense table keeps the em dash so the
/// numeric columns stay aligned.
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

/// Zebra striping for dense tables; light and dark both stay one surface sheet.
pub(super) fn row_background(colors: &StudioColors, index: usize) -> Rgba {
    if index % 2 == 0 {
        colors.panel
    } else {
        colors.panel.blend(colors.text.alpha(0.035))
    }
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

/// Compose's default selection: the second-to-last open snapshot is A and the
/// newest is B. Once a comparison ran, comparison_pair is authoritative.
fn default_pair(tabs: &[(u64, String, bool)]) -> Option<(u64, u64)> {
    let last = tabs.len().checked_sub(1)?;
    let previous = last.checked_sub(1)?;
    Some((tabs[previous].0, tabs[last].0))
}

fn comparison_header(language: Language, colors: &StudioColors) -> AnyElement {
    studio::table_header_row(colors)
        .children([
            studio::table_header_cell(None, language.text(TextKey::MemoryPageDiffClass), colors),
            studio::table_header_cell(
                Some(96.),
                language.text(TextKey::MemoryPageMemoryBeforeCount),
                colors,
            ),
            studio::table_header_cell(
                Some(96.),
                language.text(TextKey::MemoryPageMemoryAfterCount),
                colors,
            ),
            studio::table_header_cell(
                Some(96.),
                language.text(TextKey::MemoryPageMemoryDeltaCount),
                colors,
            ),
            studio::table_header_cell(
                Some(112.),
                language.text(TextKey::MemoryPageMemoryBeforeRetained),
                colors,
            ),
            studio::table_header_cell(
                Some(112.),
                language.text(TextKey::MemoryPageMemoryAfterRetained),
                colors,
            ),
            studio::table_header_cell(
                Some(112.),
                language.text(TextKey::MemoryPageMemoryDeltaRetained),
                colors,
            ),
        ])
        .into_any_element()
}

fn comparison_cell_flex(text: String, color: Rgba) -> Div {
    div()
        .flex_1()
        .min_w_0()
        .text_size(px(typography::SUPPORTING))
        .text_color(color)
        .whitespace_nowrap()
        .text_ellipsis()
        .child(crate::studio::selectable(text))
}

fn comparison_cell(width: f32, text: String, color: Rgba) -> Div {
    div()
        .w(px(width))
        .flex_shrink_0()
        .text_size(px(typography::SUPPORTING))
        .text_color(color)
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
    let missing = || UNAVAILABLE_DASH.to_owned();
    let retained = retained_delta(row);
    div()
        .id(("memory-comparison-row", index))
        .h(px(memory_row_height()))
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .gap(px(4.))
        .px(px(8.))
        .bg(row_background(colors, index))
        .child(comparison_cell_flex(row.class_name.clone(), colors.text))
        .child(comparison_cell(96., format_count(row.a_count), colors.text))
        .child(comparison_cell(96., format_count(row.b_count), colors.text))
        .child(comparison_cell(
            96.,
            signed_count(count),
            if count > 0 { colors.error } else { colors.text },
        ))
        .child(comparison_cell(
            112.,
            row.a_retained.map(format_bytes).unwrap_or_else(missing),
            colors.secondary_text,
        ))
        .child(comparison_cell(
            112.,
            row.b_retained.map(format_bytes).unwrap_or_else(missing),
            colors.secondary_text,
        ))
        .child(comparison_cell(
            112.,
            retained.map(signed_bytes).unwrap_or_else(missing),
            if retained.is_some_and(|delta| delta > 0) {
                colors.error
            } else {
                colors.text
            },
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
    let button = studio::compact_text_button(id, label, !tabs.is_empty(), false, colors)
        .dropdown_menu(move |menu, _, _| {
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
        });
    button.into_any_element()
}

impl ImportWorkspace {
    pub(super) fn render_memory_comparison(
        &mut self,
        colors: &StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
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
        let distribution = self
            .memory_state
            .snapshot
            .as_ref()
            .map(|snapshot| diff_distribution(snapshot, colors, language));

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
            .child(studio::dense_label(
                language.text(TextKey::MemoryPageMemoryCompareA),
                colors.secondary_text,
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
            .child(studio::dense_label(
                language.text(TextKey::MemoryPageMemoryCompareB),
                colors.secondary_text,
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
                studio::compact_text_button(
                    "memory-comparison-run",
                    language.text(TextKey::MemoryPageMemoryCompare),
                    can_compare,
                    true,
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
            .child(studio::supporting(
                language.text(TextKey::MemoryPageMemoryCompareLimit),
                colors,
            ));

        if loading {
            body = body.child(div().py(px(12.)).child(studio::supporting(
                language.text(TextKey::MemoryPageWorking),
                colors,
            )));
        } else if let Some(error) = error {
            body = body.child(
                div()
                    .py(px(12.))
                    .text_size(px(typography::SUPPORTING))
                    .text_color(colors.error)
                    .child(crate::studio::selectable(error)),
            );
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
            body = body.child(comparison_header(language, colors));
            if report.rows.is_empty() {
                body = body.child(div().py(px(12.)).child(studio::supporting(
                    language.text(TextKey::MemoryPageMemoryNoGrowth),
                    colors,
                )));
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
                body = body.child(
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
            }
        } else {
            body = body.child(div().py(px(12.)).child(studio::supporting(
                language.text(TextKey::MemoryPageDiffUnavailable),
                colors,
            )));
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
                    .h(px(52.))
                    .flex_shrink_0()
                    .child(diff_summary(
                        &snapshot_summary,
                        leak_suspects,
                        colors,
                        language,
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
fn diff_summary(
    summary: &aps_memory::HeapSummary,
    leak_suspects: u64,
    colors: &StudioColors,
    language: Language,
) -> AnyElement {
    let cards = [
        (
            TextKey::MemoryPageHeapSize,
            format_bytes(summary.shallow_size),
            false,
        ),
        (
            TextKey::MemoryPageObjects,
            format_count(summary.object_count),
            false,
        ),
        (
            TextKey::MemoryPageClasses,
            format_count(summary.class_count),
            false,
        ),
        (
            TextKey::MemoryPageLeakSuspects,
            format_count(leak_suspects),
            leak_suspects > 0,
        ),
    ];
    div()
        .id("memory-comparison-summary")
        .w_full()
        .flex()
        .gap(px(4.))
        .overflow_x_scrollbar()
        .children(
            cards
                .into_iter()
                .enumerate()
                .map(|(index, (key, value, warning))| {
                    let tone = if warning { colors.warning } else { colors.text };
                    studio::card(colors)
                        .id(("memory-comparison-metric", index))
                        .flex_1()
                        .min_w(px(140.))
                        .p(px(4.))
                        .gap(px(1.))
                        .child(studio::supporting(language.text(key), colors))
                        .child(
                            div()
                                .text_size(px(typography::TITLE))
                                .font_weight(FontWeight::SEMIBOLD)
                                .text_color(tone)
                                .child(crate::studio::selectable(value)),
                        )
                }),
        )
        .into_any_element()
}

fn diff_distribution(
    snapshot: &aps_memory::MemorySnapshot,
    colors: &StudioColors,
    language: Language,
) -> AnyElement {
    let palette = [
        rgb(0x4F9BE8),
        rgb(0x47B796),
        rgb(0xCA9B4B),
        rgb(0xAC85CC),
        rgb(0xDA7382),
    ];
    let segments: Vec<(&str, u64)> = snapshot
        .summary
        .heap_segments
        .iter()
        .filter(|segment| segment.size_bytes > 0)
        .map(|segment| (segment.name.as_str(), segment.size_bytes))
        .collect();
    let total = segments.iter().map(|(_, size)| *size).sum::<u64>().max(1);
    let mut bar = div()
        .id("memory-comparison-distribution-bar")
        .w_full()
        .h(px(12.))
        .flex()
        .bg(colors.section_background);
    for (index, (_, size)) in segments.iter().enumerate() {
        let weight = ((*size as f64 / total as f64).max(0.0001) * 1000.) as f32;
        bar = bar.child(
            div()
                .flex_basis(px(0.))
                .flex_grow(weight)
                .h_full()
                .bg(palette[index % palette.len()]),
        );
    }
    let mut legend = div()
        .id("memory-comparison-distribution-legend")
        .w_full()
        .flex()
        .items_center()
        .gap(px(20.))
        .overflow_x_scrollbar();
    for (index, (name, size)) in segments.iter().enumerate() {
        legend = legend.child(
            div()
                .flex()
                .items_center()
                .flex_shrink_0()
                .gap(px(5.))
                .child(div().w(px(8.)).h(px(8.)).bg(palette[index % palette.len()]))
                .child(studio::supporting(
                    format!("{name}  {}", format_bytes(*size)),
                    colors,
                )),
        );
    }
    if segments.is_empty() {
        legend = legend.child(studio::supporting(
            language.text(TextKey::MemoryPageMemoryNoSnapshot),
            colors,
        ));
    }
    div()
        .id("memory-comparison-distribution")
        .w_full()
        .h(px(76.))
        .flex_shrink_0()
        .child(
            studio::card(colors)
                .w_full()
                .child(studio::section_title(
                    language.text(TextKey::MemoryPageHeapDistribution),
                    colors,
                ))
                .child(bar)
                .child(legend),
        )
        .into_any_element()
}

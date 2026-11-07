//! Dominator tree workspace (three panes) plus the whole-snapshot KPI strip.
//!
//! MemoryDominatorWorkspace.kt and the Dominators branch of
//! MemoryInvestigationWorkspace.kt / MemoryProfilerScreen.kt are the behavior
//! oracles. The tree expands child pages lazily through the worker-backed
//! MemoryState maps; an absent child total means unknown (expandable), only an
//! explicit zero proves a leaf. Object IDs are shown as 0x hex and selecting a
//! row drives the shared object inspector.
use super::ImportWorkspace;
use super::comparison::{format_bytes, format_count, row_background};
use super::state::{MemoryState, memory_row_height};
use crate::studio::{self, StudioColors};
use aps_memory::{
    ClassStats, DominatorRow, DominatorsPage, HeapSegment, HeapSummary, MemorySnapshot,
};
use aps_ui_foundation::{Language, TextKey, typography};
use gpui_kit::{
    component::{
        checkbox::Checkbox,
        input::Input,
        scroll::{ScrollableElement, Scrollbar, ScrollbarAxis},
    },
    prelude::*,
    *,
};
use std::collections::BTreeSet;
use std::rc::Rc;

const FILTERS_WIDTH: f32 = 190.;
const INSPECTOR_WIDTH: f32 = 330.;
const MAX_INDENT_DEPTH: u32 = 10;
const INDENT_PER_DEPTH: f32 = 12.;

/// Compose's heap distribution palette (#4F9BE8, #47B796, #CA9B4B, #AC85CC, #DA7382).
const HEAP_PALETTE: [u32; 5] = [0x4f9be8, 0x47b796, 0xca9b4b, 0xac85cc, 0xda7382];

fn palette_color(index: usize) -> Rgba {
    rgb(HEAP_PALETTE[index % HEAP_PALETTE.len()])
}

fn dominator_row_height() -> f32 {
    memory_row_height() + 5.
}

fn indent_for(depth: u32) -> f32 {
    8. + depth.min(MAX_INDENT_DEPTH) as f32 * INDENT_PER_DEPTH
}

/// One flattened dominator-tree entry; the tree is pre-projected because
/// uniform_list needs the full visible row set up front.
enum DominatorEntry {
    Node {
        object_id: u64,
        depth: u32,
        label: String,
        shallow: String,
        retained: String,
        can_expand: bool,
        expanded: bool,
        selected: bool,
    },
    Status {
        depth: u32,
        text: String,
    },
}

/// Compose remoteDominatorCanExpand: only an explicit zero proves a leaf.
fn dominator_can_expand(state: &MemoryState, object_id: u64) -> bool {
    state
        .dominator_child_totals
        .get(&object_id)
        .map_or(true, |total| *total != 0)
}

fn child_page_range(page: &DominatorsPage, language: Language) -> String {
    let start = if page.rows.is_empty() {
        0
    } else {
        page.offset.saturating_add(1)
    };
    let end = page.offset.saturating_add(page.rows.len() as u64);
    language.format(
        TextKey::MemoryPageMemoryPageRange,
        &[("arg_1", &start), ("arg_2", &end), ("arg_3", &page.total)],
    )
}

/// Class names that satisfy the shared class filter (search / match case /
/// regex), computed by the aps-memory presenter so the dominator filter uses
/// exactly the oracle's matching rules instead of a second regex engine.
fn dominator_matching_classes(state: &MemoryState) -> Option<BTreeSet<String>> {
    if state.filter.search.trim().is_empty() {
        return None;
    }
    let snapshot = state.snapshot.as_ref()?;
    let mut filter = state.filter.clone();
    filter.heap = None;
    filter.scope = aps_memory::ClassScope::All;
    filter.leak_filter = aps_memory::LeakFilter::None;
    Some(
        aps_memory::displayed_classes(snapshot, &filter)
            .into_iter()
            .map(|stats| stats.class_name)
            .collect(),
    )
}

fn flatten_dominators(
    rows: &[DominatorRow],
    state: &MemoryState,
    language: Language,
    matching: Option<&BTreeSet<String>>,
    depth: u32,
    visited: &mut BTreeSet<u64>,
    out: &mut Vec<DominatorEntry>,
) {
    for row in rows {
        if !visited.insert(row.object_id) {
            continue;
        }
        if matching.is_some_and(|matching| !matching.contains(&row.class_name)) {
            continue;
        }
        let object_id = row.object_id;
        let can_expand = dominator_can_expand(state, object_id);
        let expanded = state.expanded_dominators.contains(&object_id);
        out.push(DominatorEntry::Node {
            object_id,
            depth,
            label: format!("0x{object_id:x} \u{00b7} {}", row.class_name),
            shallow: format_bytes(row.shallow_size),
            retained: format_bytes(row.retained_size),
            can_expand,
            expanded,
            selected: state.selected_dominator == Some(object_id),
        });
        if !expanded || !can_expand {
            continue;
        }
        match state.dominator_children.get(&object_id) {
            Some(page) => {
                flatten_dominators(
                    &page.rows,
                    state,
                    language,
                    matching,
                    depth.saturating_add(1),
                    visited,
                    out,
                );
                if page.total > page.rows.len() as u64 {
                    out.push(DominatorEntry::Status {
                        depth: depth.saturating_add(1),
                        text: child_page_range(page, language),
                    });
                }
            }
            None => {
                let text = if state.dominator_loading.contains(&object_id) {
                    language
                        .text(TextKey::MemoryPageMemoryInspectorLoading)
                        .to_owned()
                } else if state.dominator_error.is_some() {
                    language.text(TextKey::MemoryPageUnavailable).to_owned()
                } else {
                    language
                        .text(TextKey::MemoryPageMemoryInspectorLoading)
                        .to_owned()
                };
                out.push(DominatorEntry::Status {
                    depth: depth.saturating_add(1),
                    text,
                });
            }
        }
    }
}

fn dominator_entries(
    state: &MemoryState,
    language: Language,
    matching: Option<&BTreeSet<String>>,
) -> Vec<DominatorEntry> {
    let Some(roots) = state.dominator_roots.as_ref() else {
        return Vec::new();
    };
    let mut entries = Vec::new();
    let mut visited = BTreeSet::new();
    flatten_dominators(
        &roots.rows,
        state,
        language,
        matching,
        0,
        &mut visited,
        &mut entries,
    );
    entries
}

fn dominator_summary_card(
    id: (&'static str, usize),
    label: &str,
    value: String,
    warning: bool,
    colors: &StudioColors,
) -> AnyElement {
    let mut label_row = div()
        .w_full()
        .flex()
        .items_center()
        .gap(px(4.))
        .child(studio::supporting(label, colors));
    if warning {
        label_row = label_row.child(
            div()
                .text_size(px(typography::SUPPORTING))
                .text_color(colors.warning)
                .child(crate::studio::selectable("\u{26a0}")),
        );
    }
    studio::card(colors)
        .id(id)
        .flex_1()
        .min_w(px(140.))
        .p(px(4.))
        .gap(px(1.))
        .child(label_row)
        .child(
            div()
                .text_size(px(typography::TITLE))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(if warning { colors.warning } else { colors.text })
                .whitespace_nowrap()
                .text_ellipsis()
                .child(crate::studio::selectable(value)),
        )
        .into_any_element()
}

fn dominator_summary(
    summary: &HeapSummary,
    leak_suspects: u64,
    loaded: bool,
    language: Language,
    colors: &StudioColors,
) -> AnyElement {
    let unavailable = language.text(TextKey::MemoryPageUnavailable).to_owned();
    let value = |text: String| if loaded { text } else { unavailable.clone() };
    let cards = [
        (
            TextKey::MemoryPageHeapSize,
            value(format_bytes(summary.shallow_size)),
            false,
        ),
        (
            TextKey::MemoryPageObjects,
            value(format_count(summary.object_count)),
            false,
        ),
        (
            TextKey::MemoryPageClasses,
            value(format_count(summary.class_count)),
            false,
        ),
        (
            TextKey::MemoryPageLeakSuspects,
            value(format_count(leak_suspects)),
            loaded && leak_suspects > 0,
        ),
    ];
    div()
        .id("memory-dominator-summary")
        .w_full()
        .flex_shrink_0()
        .flex()
        .gap(px(4.))
        .overflow_x_scrollbar()
        .children(
            cards
                .into_iter()
                .enumerate()
                .map(|(index, (key, value, warning))| {
                    dominator_summary_card(
                        ("memory-dominator-metric", index),
                        language.text(key),
                        value,
                        warning,
                        colors,
                    )
                }),
        )
        .into_any_element()
}

fn dominator_distribution(
    snapshot: &MemorySnapshot,
    colors: &StudioColors,
    language: Language,
) -> AnyElement {
    let segments: Vec<&HeapSegment> = snapshot
        .summary
        .heap_segments
        .iter()
        .filter(|segment| segment.size_bytes > 0)
        .collect();
    let by_heap = !segments.is_empty();
    let rows: Vec<(String, u64)> = if by_heap {
        segments
            .iter()
            .map(|segment| (segment.name.clone(), segment.size_bytes))
            .collect()
    } else {
        let mut classes: Vec<&ClassStats> = snapshot.classes.iter().collect();
        classes.sort_by(|left, right| {
            right
                .shallow_size
                .cmp(&left.shallow_size)
                .then_with(|| left.class_name.cmp(&right.class_name))
        });
        classes
            .into_iter()
            .take(4)
            .map(|stats| (stats.class_name.clone(), stats.shallow_size))
            .collect()
    };
    let total = if by_heap {
        rows.iter().map(|(_, size)| *size).sum::<u64>()
    } else {
        snapshot.summary.shallow_size
    };
    let title = if by_heap {
        TextKey::MemoryPageMemoryDistribution
    } else {
        TextKey::MemoryPageMemoryComposition
    };
    let mut bar = div().w_full().h(px(12.)).flex().bg(colors.border);
    if total > 0 {
        let displayed = rows.iter().map(|(_, size)| *size).sum::<u64>().min(total);
        for (index, (_, size)) in rows.iter().filter(|(_, size)| *size > 0).enumerate() {
            let weight = ((*size as f64 / total as f64).max(0.0001) * 1000.) as f32;
            bar = bar.child(
                div()
                    .flex_basis(px(0.))
                    .flex_grow(weight)
                    .h_full()
                    .bg(palette_color(index)),
            );
        }
        if displayed < total {
            let weight = (((total - displayed) as f64 / total as f64).max(0.0001) * 1000.) as f32;
            bar = bar.child(div().flex_basis(px(0.)).flex_grow(weight).h_full());
        }
    }
    let mut legend = div()
        .w_full()
        .flex()
        .items_center()
        .gap(px(20.))
        .overflow_x_scrollbar();
    for (index, (name, size)) in rows.iter().enumerate() {
        legend = legend.child(
            div()
                .flex()
                .items_center()
                .flex_shrink_0()
                .gap(px(5.))
                .child(
                    div()
                        .w(px(8.))
                        .h(px(8.))
                        .flex_shrink_0()
                        .bg(palette_color(index)),
                )
                .child(studio::supporting(
                    format!("{name}  {}", format_bytes(*size)),
                    colors,
                )),
        );
    }
    if rows.is_empty() {
        legend = legend.child(studio::supporting(
            language.text(TextKey::MemoryPageMemoryNoSnapshot),
            colors,
        ));
    }
    studio::card(colors)
        .id("memory-dominator-distribution")
        .w_full()
        .flex_shrink_0()
        .child(studio::section_title(language.text(title), colors))
        .child(bar)
        .child(legend)
        .into_any_element()
}

fn dominator_row(
    index: usize,
    entry: &DominatorEntry,
    entity: &Entity<ImportWorkspace>,
    colors: &StudioColors,
    _language: Language,
) -> AnyElement {
    match entry {
        DominatorEntry::Node {
            object_id,
            depth,
            label,
            shallow,
            retained,
            can_expand,
            expanded,
            selected,
        } => {
            let background = if *selected {
                colors.selected_row
            } else {
                row_background(colors, index)
            };
            let mut row = div()
                .id(("memory-dominator-row", index))
                .h(px(dominator_row_height()))
                .w_full()
                .flex_shrink_0()
                .flex()
                .items_center()
                .gap(px(8.))
                .pl(px(indent_for(*depth)))
                .pr(px(8.))
                .bg(background);
            if *can_expand {
                let toggle_entity = entity.clone();
                let object_id = *object_id;
                let expanded = *expanded;
                row = row.child(
                    div()
                        .id(("memory-dominator-toggle", index))
                        .w(px(12.))
                        .h_full()
                        .flex_shrink_0()
                        .flex()
                        .items_center()
                        .justify_center()
                        .cursor_pointer()
                        .on_click(move |_, _, cx| {
                            let _ = toggle_entity.update(cx, |this, cx| {
                                this.memory_toggle_dominator(object_id, cx);
                            });
                            cx.stop_propagation();
                        })
                        .child(
                            div()
                                .text_size(px(typography::SUPPORTING))
                                .text_color(colors.accent)
                                .child(if expanded { "\u{25be}" } else { "\u{25b8}" }),
                        ),
                );
            } else {
                row = row.child(
                    div()
                        .w(px(12.))
                        .flex_shrink_0()
                        .flex()
                        .items_center()
                        .justify_center()
                        .child(
                            div()
                                .text_size(px(typography::SUPPORTING))
                                .text_color(colors.secondary_text)
                                .child(crate::studio::selectable("\u{00b7}")),
                        ),
                );
            }
            let select_entity = entity.clone();
            let object_id = *object_id;
            row.child(
                div()
                    .flex_1()
                    .min_w_0()
                    .text_size(px(typography::SUPPORTING))
                    .text_color(colors.text)
                    .whitespace_nowrap()
                    .text_ellipsis()
                    .child(crate::studio::selectable(label.clone())),
            )
            .child(
                div()
                    .w(px(80.))
                    .flex_shrink_0()
                    .text_size(px(typography::SUPPORTING))
                    .text_color(colors.secondary_text)
                    .whitespace_nowrap()
                    .text_ellipsis()
                    .child(shallow.clone()),
            )
            .child(
                div()
                    .w(px(80.))
                    .flex_shrink_0()
                    .text_size(px(typography::SUPPORTING))
                    .text_color(colors.secondary_text)
                    .whitespace_nowrap()
                    .text_ellipsis()
                    .child(retained.clone()),
            )
            .cursor_pointer()
            .on_click(move |_, _, cx| {
                let _ = select_entity.update(cx, |this, cx| {
                    this.memory_select_dominator(object_id, cx);
                });
            })
            .into_any_element()
        }
        DominatorEntry::Status { depth, text } => div()
            .id(("memory-dominator-status", index))
            .h(px(dominator_row_height()))
            .w_full()
            .flex_shrink_0()
            .flex()
            .items_center()
            .pl(px(indent_for(*depth)))
            .pr(px(8.))
            .child(
                div()
                    .text_size(px(typography::SUPPORTING))
                    .text_color(colors.secondary_text)
                    .whitespace_nowrap()
                    .text_ellipsis()
                    .child(crate::studio::selectable(text.clone())),
            )
            .into_any_element(),
    }
}

fn dominator_tree_card(
    state: &MemoryState,
    entity: &Entity<ImportWorkspace>,
    colors: &StudioColors,
    language: Language,
) -> AnyElement {
    let card = div()
        .id("memory-dominator-tree")
        .size_full()
        .min_h_0()
        .flex()
        .flex_col()
        .child(div().px(px(8.)).pt(px(8.)).child(studio::section_title(
            language.text(TextKey::MemoryPageDominatorTree),
            colors,
        )))
        .child(
            div()
                .w_full()
                .flex()
                .items_center()
                .gap(px(8.))
                .px(px(8.))
                .py(px(4.))
                .child(studio::table_header_cell(
                    None,
                    language.text(TextKey::MemoryPageClassName),
                    colors,
                ))
                .child(studio::table_header_cell(
                    Some(80.),
                    language.text(TextKey::MemoryPageShallow),
                    colors,
                ))
                .child(studio::table_header_cell(
                    Some(80.),
                    language.text(TextKey::MemoryPageRetained),
                    colors,
                )),
        )
        .child(studio::divider(colors));
    let empty_hint = language.text(TextKey::MemoryPageImportOrDumpAnHprofFileToShowClassHistogram);
    if state.dominator_roots.is_none() {
        if let Some(error) = state.dominator_error.as_deref() {
            return card
                .child(
                    div()
                        .p(px(8.))
                        .text_size(px(typography::SUPPORTING))
                        .text_color(colors.error)
                        .child(crate::studio::selectable(error.to_owned())),
                )
                .into_any_element();
        }
        let message = if state.snapshot.is_some() {
            language
                .text(TextKey::MemoryPageMemoryInspectorLoading)
                .to_owned()
        } else {
            empty_hint.to_owned()
        };
        return card
            .child(div().p(px(8.)).child(studio::supporting(message, colors)))
            .into_any_element();
    }
    let matching = dominator_matching_classes(state);
    let entries = Rc::new(dominator_entries(state, language, matching.as_ref()));
    if entries.is_empty() {
        let message = if matching.is_some() {
            language.text(TextKey::MemoryPageNoRows).to_owned()
        } else {
            empty_hint.to_owned()
        };
        return card
            .child(div().p(px(8.)).child(studio::supporting(message, colors)))
            .into_any_element();
    }
    let scroll_handle = state.dominator_scroll.clone();
    let colors_copy = *colors;
    let list = uniform_list("memory-dominator-rows", entries.len(), {
        let entries = entries.clone();
        let entity = entity.clone();
        move |range, _, _| {
            let mut items = Vec::with_capacity(range.len());
            for index in range {
                if let Some(entry) = entries.get(index) {
                    items.push(dominator_row(index, entry, &entity, &colors_copy, language));
                }
            }
            items
        }
    })
    .h_full()
    .w_full()
    .track_scroll(&scroll_handle);
    card.child(
        div()
            .relative()
            .flex_1()
            .min_h_0()
            .w_full()
            .child(list)
            .child(
                div().absolute().inset_0().child(
                    Scrollbar::new(&scroll_handle)
                        .id("memory-dominator-scrollbar")
                        .axis(ScrollbarAxis::Vertical)
                        .viewport_from_layout(),
                ),
            ),
    )
    .into_any_element()
}

impl ImportWorkspace {
    /// Loads the root page at most once per tab: the generation is bumped by
    /// every dominator request, so a zero generation means "never requested".
    fn memory_ensure_dominators(&mut self, cx: &mut Context<Self>) {
        if self.memory_state.dominator_roots.is_none()
            && self.memory_state.snapshot.is_some()
            && self.memory_state.dominator_generation == 0
        {
            self.memory_load_dominators(None, cx);
        }
    }

    fn memory_dominator_filters(
        &mut self,
        window: &mut Window,
        colors: &StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let input = self.memory_dominator_search_input(window, cx);
        let match_case = self.memory_state.filter.match_case;
        let use_regex = self.memory_state.filter.use_regex;
        let entity = cx.entity();
        let match_entity = entity.clone();
        let regex_entity = entity;
        div()
            .id("memory-dominator-filters")
            .w_full()
            .flex()
            .flex_col()
            .gap(px(10.))
            .child(studio::section_title(
                language.text(TextKey::MemoryPageFilters),
                colors,
            ))
            .child(div().w_full().child(Input::new(&input).h(px(24.))))
            .child(
                Checkbox::new("memory-dominator-match-case")
                    .label(language.text(TextKey::MemoryPageMatchCase))
                    .checked(match_case)
                    .on_change(move |value, _, cx| {
                        let _ = match_entity.update(cx, |this, cx| {
                            this.memory_set_match_case(*value, cx);
                        });
                    }),
            )
            .child(
                Checkbox::new("memory-dominator-regex")
                    .label(language.text(TextKey::MemoryPageRegex))
                    .checked(use_regex)
                    .on_change(move |value, _, cx| {
                        let _ = regex_entity.update(cx, |this, cx| {
                            this.memory_set_regex(*value, cx);
                        });
                    }),
            )
            .into_any_element()
    }

    pub(super) fn render_memory_dominators(
        &mut self,
        window: &mut Window,
        colors: &StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        self.memory_ensure_dominators(cx);
        let language = self.language;
        let loaded = self.memory_state.snapshot.is_some();
        let summary = self.memory_state.summary_metrics();
        let leak_suspects = self.memory_state.leak_suspect_count();
        let entity = cx.entity();

        let summary_cards = dominator_summary(&summary, leak_suspects, loaded, language, colors);
        let distribution = self
            .memory_state
            .snapshot
            .as_ref()
            .map(|snapshot| dominator_distribution(snapshot, colors, language));
        let filters = self.memory_dominator_filters(window, colors, cx);
        let tree = dominator_tree_card(&self.memory_state, &entity, colors, language);
        let inspector = self.render_memory_inspector(colors, cx);

        let mut root = div()
            .id("memory-dominators")
            .flex_1()
            .min_h_0()
            .w_full()
            .flex()
            .flex_col()
            .gap(px(4.))
            .p(px(4.))
            .child(
                div()
                    .id("memory-dominator-kpi-row")
                    .w_full()
                    .h(px(52.))
                    .flex_shrink_0()
                    .child(summary_cards),
            );
        if let Some(distribution) = distribution {
            root = root.child(
                div()
                    .id("memory-dominator-distribution")
                    .w_full()
                    .h(px(76.))
                    .flex_shrink_0()
                    .child(distribution),
            );
        }
        root.child(
            div()
                .id("memory-dominator-panes")
                .flex_1()
                .min_h_0()
                .w_full()
                .flex()
                .gap(px(4.))
                .child(
                    studio::card(colors)
                        .id("memory-filters-card")
                        .w(px(FILTERS_WIDTH))
                        .h_full()
                        .flex_shrink_0()
                        .p(px(10.))
                        .gap(px(10.))
                        .child(
                            div()
                                .id("memory-dominator-filters-scroll")
                                .flex_1()
                                .min_h_0()
                                .w_full()
                                .overflow_y_scrollbar()
                                .child(filters),
                        ),
                )
                .child(
                    studio::card(colors)
                        .id("memory-dominator-analysis-card")
                        .flex_1()
                        .min_w(px(0.))
                        .h_full()
                        .p_0()
                        .gap_0()
                        .child(tree),
                )
                .child(
                    studio::card(colors)
                        .id("memory-inspector-card")
                        .w(px(INSPECTOR_WIDTH))
                        .h_full()
                        .flex_shrink_0()
                        .p_0()
                        .gap_0()
                        .child(inspector),
                ),
        )
        .into_any_element()
    }
}

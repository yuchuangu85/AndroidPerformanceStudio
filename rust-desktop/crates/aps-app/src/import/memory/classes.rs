//! Classes workspace: primary filter bar, KPI cards, heap distribution, the
//! classifier tree table and the selected class's instance list.
//!
//! MemoryInvestigationWorkspace + MemoryProfilerClassListView are the behavior
//! oracle: a dense primary filter bar, eight equal KPI cards, the heap-composition
//! card, then one class-analysis card split vertically into the classifier tree
//! (top) and the instance list (bottom). A loaded instance detail splits the
//! instance area horizontally to host the object inspector. Every number is
//! presenter output; unavailable metrics stay "—"/"Unavailable".
use super::ImportWorkspace;
use super::state::MemoryState;
use super::widgets::{
    METRIC_WIDTH, TABLE_FONT, TABLE_HEADER_HEIGHT, TABLE_LINE, TABLE_ROW_HEIGHT, WARNING_WIDTH,
    checkbox, header_cell, kpi_card, metric_cell, selector_button, toolbar_button, warning_icon,
};
use crate::studio::{self, StudioColors};
use aps_memory::{
    ArrangeBy, ClassScope, ClassifierColumn, ClassifierRow, InstanceRow, LeakFilter, SortDirection,
};
use aps_ui_foundation::{Language, TextKey, typography};
use gpui_kit::{
    component::{
        input::{Input, InputState},
        menu::{DropdownMenu, PopupMenuItem},
        resizable::{h_resizable, resizable_panel, v_resizable},
        scroll::{ScrollableElement, Scrollbar, ScrollbarAxis},
    },
    prelude::*,
    *,
};
use std::collections::BTreeSet;
use std::rc::Rc;

/// Compose primary filter bar: one 36 dp row, 8 dp insets and gaps.
const FILTER_BAR_HEIGHT: f32 = 36.;
/// Compose snapshot summary row height (4 dp card padding + 19 dp value line).
const KPI_ROW_HEIGHT: f32 = 44.;
const KPI_MIN_WIDTH: f32 = 140.;
/// Compose InstanceListTitle row: 6 dp vertical padding + body line.
const INSTANCE_TITLE_HEIGHT: f32 = 28.;
/// Compose INSTANCE_COLUMN_WIDTHS: Depth, Native Size, Shallow, Retained.
const INSTANCE_DEPTH_WIDTH: f32 = 60.;
const INSTANCE_NATIVE_WIDTH: f32 = 104.;
const INSTANCE_SHALLOW_WIDTH: f32 = 96.;
const INSTANCE_RETAINED_WIDTH: f32 = 120.;
const INSTANCE_COLUMN_WIDTHS: [f32; 4] = [
    INSTANCE_DEPTH_WIDTH,
    INSTANCE_NATIVE_WIDTH,
    INSTANCE_SHALLOW_WIDTH,
    INSTANCE_RETAINED_WIDTH,
];
/// Compose INITIAL_CLASS_PANE_FRACTION; minimums are scaled down on short panes.
const MIN_CLASSES_HEIGHT: f32 = 100.;
const MIN_INSTANCES_HEIGHT: f32 = 180.;
/// Compose INITIAL_INSTANCE_PANE_FRACTION.
const INITIAL_INSTANCE_FRACTION: f32 = 0.62;
const MIN_INSTANCE_TABLE_WIDTH: f32 = 280.;
const MIN_INSTANCE_DETAILS_WIDTH: f32 = 260.;
/// Compose MemoryHeapDistribution palette.
const HEAP_PALETTE: [u32; 5] = [0x4F9BE8, 0x47B796, 0xCA9B4B, 0xAC85CC, 0xDA7382];

impl ImportWorkspace {
    pub(super) fn render_memory_classes(
        &mut self,
        window: &mut Window,
        colors: &StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        if !self.memory_state.loaded() {
            return Self::memory_classes_empty(&self.memory_state, language, colors);
        }
        let search = self.memory_class_search_input(window, cx);
        let mut page = div()
            .id("memory-investigation-workspace")
            .flex_1()
            .min_h_0()
            .w_full()
            .flex()
            .flex_col()
            .gap(px(studio::metrics::CARD_GAP))
            .p(px(studio::metrics::WORKSPACE_PADDING))
            .bg(colors.workspace)
            .text_color(colors.text);
        page = page
            .child(self.memory_class_filter_bar(language, &search, colors, cx))
            // The KPI row is container-measured; give it the Compose card height
            // so the flex column cannot stretch it into the analysis pane.
            .child(
                div()
                    .id("memory-class-kpi-row")
                    .w_full()
                    .h(px(KPI_ROW_HEIGHT))
                    .flex_shrink_0()
                    .child(self.memory_class_summary(language, colors)),
            )
            .child(self.memory_heap_distribution(language, colors));
        page.child(self.memory_class_analysis(colors, cx))
            .into_any_element()
    }

    fn memory_classes_empty(
        state: &MemoryState,
        language: Language,
        colors: &StudioColors,
    ) -> AnyElement {
        let mut column = div()
            .id("memory-classes-empty")
            .flex_1()
            .min_h_0()
            .w_full()
            .flex()
            .flex_col()
            .items_center()
            .justify_center()
            .gap(px(4.))
            .p(px(24.));
        if let Some(error) = state.error.clone() {
            column = column.child(
                div()
                    .text_size(px(TABLE_FONT))
                    .line_height(px(TABLE_LINE))
                    .text_color(colors.error)
                    .text_align(TextAlign::Center)
                    .child(crate::studio::selectable(error)),
            );
        }
        let message = if state.loading {
            state
                .loading_message
                .clone()
                .unwrap_or_else(|| language.text(TextKey::MemoryPageLoadingSnapshot).to_owned())
        } else {
            language
                .text(TextKey::MemoryPageImportOrDumpAnHprofFileToShowClassHistogram)
                .to_owned()
        };
        column
            .child(
                div()
                    .text_size(px(TABLE_FONT))
                    .line_height(px(TABLE_LINE))
                    .text_color(colors.secondary_text)
                    .text_align(TextAlign::Center)
                    .child(crate::studio::selectable(message)),
            )
            .into_any_element()
    }

    // ---- primary filter bar -------------------------------------------

    fn memory_class_filter_bar(
        &self,
        language: Language,
        search: &Entity<InputState>,
        colors: &StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let filter = self.memory_state.filter.clone();
        let snapshot = self.memory_state.snapshot.clone();
        let heaps = snapshot
            .as_ref()
            .map_or_else(Vec::new, |snapshot| snapshot.metadata.heaps.clone());
        let per_heap_available = snapshot
            .as_ref()
            .is_some_and(|snapshot| !snapshot.per_heap_classes.is_empty());
        let workspace = cx.entity().downgrade();
        let mut row = div()
            .id("memory-primary-filter-bar")
            .w_full()
            .h(px(FILTER_BAR_HEIGHT))
            .flex_shrink_0()
            .flex()
            .items_center()
            .gap(px(8.))
            .px(px(8.));

        // Heap selector: a heap filter is only meaningful when the snapshot
        // carries per-heap histograms; the budget fallback keeps all heaps.
        let heap_label = filter
            .heap
            .clone()
            .unwrap_or_else(|| language.text(TextKey::MemoryPageAllHeaps).to_owned());
        let heap_control = if per_heap_available {
            let current = filter.heap.clone();
            let workspace = workspace.clone();
            let heaps = heaps.clone();
            selector_button("memory-heap-selector", heap_label, true, colors)
                .dropdown_menu(move |menu, _, _| {
                    let mut menu = menu.min_w(px(200.));
                    let all_label = language.text(TextKey::MemoryPageAllHeaps);
                    let select_all = workspace.clone();
                    menu = menu.item(
                        PopupMenuItem::new(all_label)
                            .checked(current.is_none())
                            .on_click(move |_, _, cx| {
                                let _ = select_all
                                    .update(cx, |this, cx| this.memory_set_heap_filter(None, cx));
                            }),
                    );
                    for heap in &heaps {
                        let selected = current.as_deref() == Some(heap.as_str());
                        let heap_name = heap.clone();
                        let select_heap = workspace.clone();
                        menu =
                            menu.item(PopupMenuItem::new(heap.clone()).checked(selected).on_click(
                                move |_, _, cx| {
                                    let heap_name = heap_name.clone();
                                    let _ = select_heap.update(cx, |this, cx| {
                                        this.memory_set_heap_filter(Some(heap_name), cx)
                                    });
                                },
                            ));
                    }
                    menu
                })
                .into_any_element()
        } else {
            selector_button(
                "memory-heap-selector",
                language.text(TextKey::MemoryPageAllHeaps),
                false,
                colors,
            )
            .into_any_element()
        };
        row = row.child(heap_control);

        // Class scope.
        let scope = filter.scope;
        let workspace_scope = workspace.clone();
        row = row.child(
            selector_button(
                "memory-class-scope",
                class_scope_label(scope, language),
                true,
                colors,
            )
            .dropdown_menu(move |menu, _, _| {
                let mut menu = menu.min_w(px(180.));
                for choice in [ClassScope::All, ClassScope::Project, ClassScope::System] {
                    let select_scope = workspace_scope.clone();
                    menu = menu.item(
                        PopupMenuItem::new(class_scope_label(choice, language))
                            .checked(choice == scope)
                            .on_click(move |_, _, cx| {
                                let _ = select_scope
                                    .update(cx, |this, cx| this.memory_set_scope(choice, cx));
                            }),
                    );
                }
                menu
            }),
        );

        // Leak filter.
        let leak_filter = filter.leak_filter;
        let workspace_leak = workspace.clone();
        row = row.child(
            selector_button(
                "memory-leak-filter",
                leak_filter_label(leak_filter, language),
                true,
                colors,
            )
            .dropdown_menu(move |menu, _, _| {
                let mut menu = menu.min_w(px(220.));
                for choice in [
                    LeakFilter::None,
                    LeakFilter::AllIssues,
                    LeakFilter::ActivityFragmentLeak,
                    LeakFilter::DuplicateBitmaps,
                ] {
                    let select_leak = workspace_leak.clone();
                    menu = menu.item(
                        PopupMenuItem::new(leak_filter_label(choice, language))
                            .checked(choice == leak_filter)
                            .on_click(move |_, _, cx| {
                                let _ = select_leak
                                    .update(cx, |this, cx| this.memory_set_leak_filter(choice, cx));
                            }),
                    );
                }
                menu
            }),
        );

        // Arrange by.
        let arrange = filter.arrange_by;
        let workspace_arrange = workspace.clone();
        row = row.child(
            selector_button(
                "memory-arrange-by",
                arrange_label(arrange, language),
                true,
                colors,
            )
            .dropdown_menu(move |menu, _, _| {
                let mut menu = menu.min_w(px(160.));
                for choice in [ArrangeBy::Class, ArrangeBy::Package] {
                    let select_arrange = workspace_arrange.clone();
                    menu = menu.item(
                        PopupMenuItem::new(arrange_label(choice, language))
                            .checked(choice == arrange)
                            .on_click(move |_, _, cx| {
                                let _ = select_arrange
                                    .update(cx, |this, cx| this.memory_set_arrange(choice, cx));
                            }),
                    );
                }
                menu
            }),
        );

        // Class filter input, sized like the Compose 160-220 dp field.
        row = row.child(
            div()
                .w(px(200.))
                .min_w(px(160.))
                .max_w(px(220.))
                .flex_shrink_0()
                .child(
                    Input::new(search)
                        .h(px(32.))
                        .px(px(8.))
                        .rounded(px(4.))
                        .bg(colors.panel)
                        .border_1()
                        .border_color(colors.border)
                        .text_size(px(TABLE_FONT)),
                ),
        );

        // Match case / Regex.
        let match_case = filter.match_case;
        row = row.child(
            checkbox(
                "memory-match-case",
                language.text(TextKey::MemoryPageMatchCase),
                match_case,
                true,
                colors,
            )
            .on_click(
                cx.listener(move |this, _, _, cx| this.memory_set_match_case(!match_case, cx)),
            ),
        );
        let use_regex = filter.use_regex;
        row = row.child(
            checkbox(
                "memory-regex",
                language.text(TextKey::MemoryPageRegex),
                use_regex,
                true,
                colors,
            )
            .on_click(cx.listener(move |this, _, _, cx| this.memory_set_regex(!use_regex, cx))),
        );
        row.overflow_x_scrollbar().into_any_element()
    }

    // ---- eight KPI cards ----------------------------------------------

    fn memory_class_summary(&self, language: Language, colors: &StudioColors) -> AnyElement {
        let summary = self.memory_state.class_summary.clone();
        let unavailable = language.text(TextKey::MemoryPageUnavailable).to_owned();
        let optional_bytes =
            |value: Option<u64>| value.map_or_else(|| unavailable.clone(), format_bytes);
        let cards: Vec<(TextKey, String, bool)> = vec![
            (
                TextKey::MemoryPageLifecycleLeaks,
                integer(summary.leak_count),
                summary.leak_count > 0,
            ),
            (
                TextKey::MemoryPageDuplicateBitmaps,
                integer(summary.duplicate_bitmap_count),
                false,
            ),
            (
                TextKey::MemoryPageClasses,
                integer(summary.class_count),
                false,
            ),
            (
                TextKey::MemoryPageCount,
                integer(summary.total_count),
                false,
            ),
            (
                TextKey::MemoryPageNativeSize,
                optional_bytes(summary.total_native_size),
                false,
            ),
            (
                TextKey::MemoryPageShallow,
                format_bytes(summary.total_shallow_size),
                false,
            ),
            (
                TextKey::MemoryPageRetainedNativeSize,
                optional_bytes(summary.total_retained_native_size),
                false,
            ),
            (
                TextKey::MemoryPageRetained,
                optional_bytes(summary.total_retained_size),
                false,
            ),
        ];
        let colors = *colors;
        container_query(move |size, _, _| {
            let available = f32::from(size.width);
            let gaps = studio::metrics::CARD_GAP * (cards.len().saturating_sub(1)) as f32;
            let card_width = ((available - gaps) / cards.len() as f32).max(KPI_MIN_WIDTH);
            let mut row = div()
                .id("memory-class-summary")
                .w_full()
                .flex()
                .gap(px(studio::metrics::CARD_GAP))
                .overflow_x_scrollbar();
            for (index, (key, value, warning)) in cards.iter().enumerate() {
                row = row.child(kpi_card(
                    ("memory-kpi-card", index),
                    language.text(*key),
                    value.clone(),
                    *warning,
                    card_width,
                    &colors,
                ));
            }
            row
        })
        .into_any_element()
    }

    // ---- heap distribution --------------------------------------------

    fn memory_heap_distribution(&self, language: Language, colors: &StudioColors) -> AnyElement {
        let Some(snapshot) = self.memory_state.snapshot.clone() else {
            return div().into_any_element();
        };
        let segments: Vec<(String, u64)> = snapshot
            .summary
            .heap_segments
            .iter()
            .filter(|segment| segment.size_bytes > 0)
            .map(|segment| (segment.name.clone(), segment.size_bytes))
            .collect();
        let has_segments = !segments.is_empty();
        let rows: Vec<(String, u64)> = if has_segments {
            segments
        } else {
            // Compose MemoryHeapDistribution: top-4 classes by shallow size.
            let mut classes: Vec<(String, u64)> = snapshot
                .classes
                .iter()
                .map(|stats| (stats.class_name.clone(), stats.shallow_size))
                .collect();
            classes.sort_by(|left, right| right.1.cmp(&left.1));
            classes.truncate(4);
            classes
        };
        let total = if has_segments {
            rows.iter().map(|(_, bytes)| *bytes).sum::<u64>()
        } else {
            snapshot.summary.shallow_size
        };
        let title = if has_segments {
            TextKey::MemoryPageHeapDistribution
        } else {
            TextKey::MemoryPageMemoryComposition
        };
        let mut bar = div()
            .id("memory-heap-distribution-bar")
            .w_full()
            .h(px(12.))
            .flex()
            .bg(colors.section_background);
        if total > 0 {
            let mut displayed = 0_u64;
            for (index, (_, bytes)) in rows.iter().enumerate() {
                if *bytes == 0 {
                    continue;
                }
                bar = bar.child(
                    div()
                        .h_full()
                        .w(relative(fraction(*bytes, total)))
                        .bg(palette_color(index)),
                );
                displayed = displayed.saturating_add(*bytes);
            }
            if displayed < total {
                bar = bar.child(
                    div()
                        .h_full()
                        .w(relative(fraction(total - displayed, total))),
                );
            }
        }
        let mut legend = div()
            .id("memory-heap-distribution-legend")
            .w_full()
            .flex()
            .items_center()
            .gap(px(20.))
            .overflow_x_scrollbar();
        for (index, (name, bytes)) in rows.iter().enumerate() {
            legend = legend.child(
                div()
                    .flex()
                    .items_center()
                    .gap(px(5.))
                    .flex_shrink_0()
                    .child(div().w(px(8.)).h(px(8.)).bg(palette_color(index)))
                    .child(secondary_text(
                        format!("{name}  {}", format_bytes(*bytes)),
                        colors,
                    )),
            );
        }
        if rows.is_empty() {
            legend = legend.child(secondary_text(
                language
                    .text(TextKey::MemoryPageMemoryNoSnapshot)
                    .to_owned(),
                colors,
            ));
        }
        studio::card(colors)
            .id("memory-distribution-card")
            .w_full()
            .flex_shrink_0()
            .child(
                div()
                    .text_size(px(TABLE_FONT))
                    .line_height(px(TABLE_LINE))
                    .font_weight(FontWeight::SEMIBOLD)
                    .text_color(colors.text)
                    .child(crate::studio::selectable(language.text(title))),
            )
            .child(bar)
            .child(legend)
            .into_any_element()
    }

    // ---- class analysis card ------------------------------------------

    fn memory_class_analysis(
        &mut self,
        colors: &StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let classifier = self.memory_classifier_table(colors, cx);
        let instances = self.memory_instance_pane(colors, cx);
        // Compose MemoryClassesPane: a vertical drag split, initial 50/50,
        // min 100 dp classes / 180 dp instances, scaled down on short panes.
        studio::card(colors)
            .id("memory-class-analysis-card")
            .flex_1()
            .min_h_0()
            .w_full()
            .p(px(0.))
            .gap(px(0.))
            .overflow_hidden()
            .child(container_query(move |size, _, _| {
                let pane = f32::from(size.height).max(0.);
                let min_classes = MIN_CLASSES_HEIGHT.min(pane * 0.5);
                let min_instances = MIN_INSTANCES_HEIGHT.min(pane * 0.5);
                let max_classes = (pane - min_instances).max(min_classes);
                let max_instances = (pane - min_classes).max(min_instances);
                v_resizable("memory-class-instance-split")
                    .child(
                        resizable_panel()
                            .size(px(pane * 0.5))
                            .size_range(px(min_classes)..px(max_classes))
                            .child(classifier),
                    )
                    .child(
                        resizable_panel()
                            .size_range(px(min_instances)..px(max_instances))
                            .child(instances),
                    )
            }))
            .into_any_element()
    }

    fn memory_classifier_table(
        &mut self,
        colors: &StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        // Compose shows the Heap column only without per-heap histograms and
        // without a heap filter; with grouping the heap is the parent row.
        let show_heap = self
            .memory_state
            .snapshot
            .as_ref()
            .is_some_and(|snapshot| snapshot.per_heap_classes.is_empty())
            && self.memory_state.filter.heap.is_none();
        let rows: Rc<Vec<ClassifierRow>> = Rc::new(self.memory_state.visible_classifier_rows());
        let expanded = Rc::new(classifier_expanded_ids(&self.memory_state));
        let leak_rows = Rc::new(classifier_leak_ids(
            &self.memory_state.classifier_rows,
            &self.memory_state.leak_classes,
        ));
        let duplicates = Rc::new(self.memory_state.duplicate_classes.clone());
        let selected = self.memory_state.selected_classifier_id.clone();
        let entity = cx.entity();
        let colors_copy = *colors;
        let header = self.memory_classifier_header(language, show_heap, colors, cx);
        let pane = div()
            .id("memory-classifier-table")
            .size_full()
            .min_h_0()
            .flex()
            .flex_col()
            .overflow_hidden()
            .child(header)
            // Compose MemoryClassesPane: HorizontalDivider below the header.
            .child(studio::divider(colors));
        if rows.is_empty() {
            return pane
                .child(
                    div()
                        .flex_1()
                        .min_h_0()
                        .w_full()
                        .flex()
                        .items_center()
                        .justify_center()
                        .p(px(8.))
                        .child(
                            div()
                                .text_size(px(TABLE_FONT))
                                .line_height(px(TABLE_LINE))
                                .text_color(colors_copy.secondary_text)
                                .text_align(TextAlign::Center)
                                .child(crate::studio::selectable(language.text(
                                    TextKey::MemoryPageImportOrDumpAnHprofFileToShowClassHistogram,
                                ))),
                        ),
                )
                .into_any_element();
        }
        let row_height = TABLE_ROW_HEIGHT;
        let list = uniform_list("memory-classifier-rows", rows.len(), {
            let rows = rows.clone();
            let expanded = expanded.clone();
            let leak_rows = leak_rows.clone();
            let duplicates = duplicates.clone();
            let selected = selected.clone();
            let entity = entity.clone();
            move |range, _, _| {
                let mut items = Vec::with_capacity(range.len());
                for index in range {
                    let Some(row) = rows.get(index) else {
                        continue;
                    };
                    items.push(classifier_row(
                        index,
                        row,
                        selected.as_deref(),
                        expanded.contains(&row.id),
                        leak_rows.contains(&row.id),
                        row.class_name
                            .as_deref()
                            .is_some_and(|name| duplicates.contains(name)),
                        show_heap,
                        row_height,
                        &entity,
                        language,
                        &colors_copy,
                    ));
                }
                items
            }
        })
        .h_full()
        .w_full()
        .track_scroll(&self.memory_state.classifier_scroll);
        let metric_columns = if show_heap { 5 } else { 4 };
        pane.child(
            div()
                .relative()
                .flex_1()
                .min_h_0()
                .w_full()
                .child(list)
                .child(table_grid_overlay(
                    "memory-classifier-grid",
                    &[METRIC_WIDTH; 5][..metric_columns],
                    colors,
                ))
                .child(vertical_scrollbar(
                    "memory-classifier-scrollbar",
                    &self.memory_state.classifier_scroll,
                )),
        )
        .into_any_element()
    }

    fn memory_classifier_header(
        &self,
        language: Language,
        show_heap: bool,
        colors: &StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let sort = self.memory_state.sort_column;
        let direction = self.memory_state.sort_direction;
        let first = match self.memory_state.filter.arrange_by {
            ArrangeBy::Class => TextKey::MemoryPageClassName,
            ArrangeBy::Package => TextKey::MemoryPagePackageName,
        };
        let mut header = div()
            .id("memory-classifier-header")
            .w_full()
            .h(px(TABLE_HEADER_HEIGHT))
            .flex_shrink_0()
            .flex()
            .items_center()
            .bg(colors.section_background);
        header =
            header.child(
                header_cell(
                    None,
                    header_title(
                        language.text(first),
                        ClassifierColumn::Name,
                        sort,
                        direction,
                    ),
                    false,
                    false,
                    colors,
                )
                .id("memory-classifier-header-name")
                .cursor_pointer()
                .on_click(cx.listener(|this, _, _, cx| {
                    this.memory_sort_classifier(ClassifierColumn::Name, cx)
                })),
            );
        for (column, key) in [
            (ClassifierColumn::TotalCount, TextKey::MemoryPageInstances),
            (ClassifierColumn::NativeSize, TextKey::MemoryPageNativeSize),
            (ClassifierColumn::ShallowSize, TextKey::MemoryPageShallow),
            (ClassifierColumn::RetainedSize, TextKey::MemoryPageRetained),
        ] {
            header = header.child(
                header_cell(
                    Some(METRIC_WIDTH),
                    header_title(language.text(key), column, sort, direction),
                    true,
                    true,
                    colors,
                )
                .id(("memory-classifier-sort", column as usize))
                .cursor_pointer()
                .on_click(
                    cx.listener(move |this, _, _, cx| this.memory_sort_classifier(column, cx)),
                ),
            );
        }
        if show_heap {
            // Compose leaves the Heap header left aligned; only its values are
            // right aligned.
            header = header.child(header_cell(
                Some(METRIC_WIDTH),
                language.text(TextKey::MemoryPageHeap),
                false,
                true,
                colors,
            ));
        }
        header.into_any_element()
    }

    // ---- instance list ------------------------------------------------

    fn memory_instance_pane(
        &mut self,
        colors: &StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let title = self.memory_instance_title(colors);
        let table = self.memory_instance_table(colors, cx);
        let content = if self.memory_state.detail.is_some() {
            // The inspector renders its own content only; this pane is the box.
            let inspector = div()
                .size_full()
                .min_h_0()
                .min_w_0()
                .child(self.render_memory_inspector(colors, cx))
                .into_any_element();
            Self::memory_instance_split(table, inspector)
        } else {
            div()
                .flex_1()
                .min_h_0()
                .w_full()
                .child(table)
                .into_any_element()
        };
        div()
            .id("memory-instances-card")
            .size_full()
            .min_h_0()
            .flex()
            .flex_col()
            .overflow_hidden()
            // Opaque pane: the resizable split does not clip its panels, so the
            // classifier list must not show through the instance pane.
            .bg(colors.panel)
            .child(title)
            .child(studio::divider(colors))
            .child(content)
            .into_any_element()
    }

    /// Compose InstanceListContent split state: 62% table / 38% inspector,
    /// 7 dp splitter, min 280 dp table / 260 dp inspector (scaled on narrow).
    fn memory_instance_split(table: AnyElement, inspector: AnyElement) -> AnyElement {
        container_query(move |size, _, _| {
            let pane = f32::from(size.width).max(0.);
            let min_table = MIN_INSTANCE_TABLE_WIDTH.min(pane * 0.5);
            let min_details = MIN_INSTANCE_DETAILS_WIDTH.min(pane * 0.5);
            let max_table = (pane - min_details).max(min_table);
            let max_details = (pane - min_table).max(min_details);
            h_resizable("memory-instance-inspector-split")
                .child(
                    resizable_panel()
                        .size(px(pane * INITIAL_INSTANCE_FRACTION))
                        .size_range(px(min_table)..px(max_table))
                        .child(table),
                )
                .child(
                    resizable_panel()
                        .size_range(px(min_details)..px(max_details))
                        .child(inspector),
                )
        })
        .flex_1()
        .min_h_0()
        .w_full()
        .into_any_element()
    }

    fn memory_instance_title(&self, colors: &StudioColors) -> AnyElement {
        let language = self.language;
        let selected = self
            .memory_state
            .selected_class_label
            .clone()
            .or_else(|| self.memory_state.selected_class_name.clone());
        let is_leak = self
            .memory_state
            .selected_class_name
            .as_ref()
            .is_some_and(|name| self.memory_state.leak_classes.contains(name));
        let label = match selected.as_deref() {
            Some(selected) => format!(
                "{} — {selected}",
                language.text(TextKey::MemoryPageInstanceList)
            ),
            None => language.text(TextKey::MemoryPageInstanceList).to_owned(),
        };
        let mut row = div()
            .id("memory-instance-list-title")
            .w_full()
            .h(px(INSTANCE_TITLE_HEIGHT))
            .flex_shrink_0()
            .flex()
            .items_center()
            .gap(px(6.))
            .px(px(8.))
            .child(
                div()
                    .min_w_0()
                    .text_size(px(typography::TITLE))
                    .line_height(px(20.))
                    .font_weight(FontWeight::BOLD)
                    .text_color(colors.text)
                    .whitespace_nowrap()
                    .text_ellipsis()
                    .child(crate::studio::selectable(label)),
            );
        if is_leak {
            row = row.child(warning_icon(colors));
        }
        if selected.is_some() {
            let total = self
                .memory_state
                .instances
                .as_ref()
                .map_or(0, |page| page.total)
                .max(self.memory_state.instance_count() as u64);
            row = row.child(
                secondary_text(
                    language.format(TextKey::MemoryPageCountValue, &[("arg_1", &total)]),
                    colors,
                )
                .whitespace_nowrap(),
            );
        }
        row.into_any_element()
    }

    fn memory_instance_table(&self, colors: &StudioColors, cx: &mut Context<Self>) -> AnyElement {
        let language = self.language;
        let page = instance_page(&self.memory_state);
        let mut column = div()
            .id("memory-instance-list-content")
            .size_full()
            .min_h_0()
            .flex()
            .flex_col()
            .overflow_hidden();
        if page.is_paged() {
            column = column.child(self.memory_instance_paging(language, page, colors, cx));
        }
        column = column.child(instance_table_header(language, colors));
        if self.memory_state.selected_class_name.is_none() {
            return column
                .child(instance_hint(
                    language.text(TextKey::MemoryPageSelectAClassToViewItsInstances),
                    colors,
                ))
                .into_any_element();
        }
        let rows: Rc<Vec<InstanceRow>> =
            Rc::new(self.memory_state.selected_class_instances().to_vec());
        if rows.is_empty() {
            let message = if self.memory_state.instances_loading {
                language.text(TextKey::MemoryPageWorking)
            } else {
                language.text(TextKey::MemoryPageNoInstancesForClass)
            };
            return column
                .child(instance_hint(message, colors))
                .into_any_element();
        }
        let row_height = TABLE_ROW_HEIGHT;
        let selected = self.memory_state.selected_object;
        let leak_ids = Rc::new(self.memory_state.leak_object_ids.clone());
        let entity = cx.entity();
        let colors_copy = *colors;
        let list = uniform_list("memory-instance-rows", rows.len(), {
            let rows = rows.clone();
            let leak_ids = leak_ids.clone();
            let entity = entity.clone();
            move |range, _, _| {
                let mut items = Vec::with_capacity(range.len());
                for index in range {
                    let Some(row) = rows.get(index) else {
                        continue;
                    };
                    items.push(instance_row(
                        index,
                        row,
                        selected == Some(row.object_id),
                        leak_ids.contains(&row.object_id),
                        row_height,
                        language,
                        &entity,
                        &colors_copy,
                    ));
                }
                items
            }
        })
        .h_full()
        .w_full()
        .track_scroll(&self.memory_state.instance_scroll);
        column
            .child(
                div()
                    .relative()
                    .flex_1()
                    .min_h_0()
                    .w_full()
                    .child(list)
                    .child(table_grid_overlay(
                        "memory-instance-grid",
                        &INSTANCE_COLUMN_WIDTHS,
                        colors,
                    ))
                    .child(vertical_scrollbar(
                        "memory-instance-scrollbar",
                        &self.memory_state.instance_scroll,
                    )),
            )
            .into_any_element()
    }

    fn memory_instance_paging(
        &self,
        language: Language,
        page: InstancePage,
        colors: &StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let range = if page.is_paged() {
            language.format(
                TextKey::MemoryPageMemoryPageRange,
                &[
                    ("arg_1", &page.start()),
                    ("arg_2", &page.end()),
                    ("arg_3", &page.total),
                ],
            )
        } else {
            language.format(TextKey::MemoryPageCountValue, &[("arg_1", &page.total)])
        };
        let previous = page.previous_offset();
        let next = page.next_offset();
        let loading = self.memory_state.instances_loading;
        div()
            .id("memory-instance-page")
            .w_full()
            .flex_shrink_0()
            .flex()
            .flex_wrap()
            .items_center()
            .gap(px(4.))
            .px(px(8.))
            .py(px(2.))
            .child(secondary_text(range, colors).whitespace_nowrap())
            .child(
                toolbar_button(
                    "memory-instance-page-previous",
                    language.text(TextKey::MemoryPageMemoryPagePrevious),
                    previous.is_some() && !loading,
                    false,
                    colors,
                )
                .on_click(cx.listener(move |this, _, _, cx| {
                    if let Some(offset) = previous {
                        this.memory_load_instance_page(offset, cx);
                    }
                })),
            )
            .child(
                toolbar_button(
                    "memory-instance-page-next",
                    language.text(TextKey::MemoryPageMemoryPageNext),
                    next.is_some() && !loading,
                    false,
                    colors,
                )
                .on_click(cx.listener(move |this, _, _, cx| {
                    if let Some(offset) = next {
                        this.memory_load_instance_page(offset, cx);
                    }
                })),
            )
            .into_any_element()
    }
}

// ---------------------------------------------------------------------------
// Rows and small render helpers.
// ---------------------------------------------------------------------------

#[allow(clippy::too_many_arguments)]
fn classifier_row(
    index: usize,
    row: &ClassifierRow,
    selected_id: Option<&str>,
    expanded: bool,
    is_leak: bool,
    is_duplicate_bitmap: bool,
    show_heap: bool,
    row_height: f32,
    entity: &Entity<ImportWorkspace>,
    language: Language,
    colors: &StudioColors,
) -> AnyElement {
    let selected = selected_id == Some(row.id.as_str());
    let is_heap = row.heap_name.is_some() && row.depth == 0;
    let background = if selected {
        colors.selected_row
    } else if is_leak {
        // Compose errorContainer(0.45) over the workspace panel.
        colors.error.alpha(0.06)
    } else if is_duplicate_bitmap {
        // Compose tertiaryContainer maps to the warning surface, tinted 0.45.
        colors.warning.alpha(0.10)
    } else {
        // Compose surfaceContainerLow == surface == workspace panel.
        colors.panel
    };
    let indent = 8. + row.depth as f32 * 16.;
    let has_children = !row.children.is_empty();
    let chevron = if !has_children {
        ""
    } else if expanded {
        "▾"
    } else {
        "▸"
    };
    let toggle = div()
        .id(("memory-classifier-toggle", index))
        .w(px(14.))
        .flex_shrink_0()
        .text_size(px(12.))
        .text_color(colors.accent)
        .child(chevron);
    let toggle = if has_children {
        let toggle_row = row.clone();
        let toggle_entity = entity.clone();
        toggle.cursor_pointer().on_click(move |_, _, cx| {
            cx.stop_propagation();
            let toggle_row = toggle_row.clone();
            let _ = toggle_entity.update(cx, move |this, cx| {
                this.memory_toggle_classifier(&toggle_row, cx)
            });
        })
    } else {
        toggle
    };
    let name = div()
        .flex_1()
        .min_w_0()
        .text_size(px(TABLE_FONT))
        .line_height(px(TABLE_LINE))
        .text_color(colors.text)
        .whitespace_nowrap()
        .text_ellipsis()
        .when(is_heap, |label| label.font_weight(FontWeight::SEMIBOLD))
        .child(row.label.clone());
    let mut element = div()
        .id(("memory-classifier-row", index))
        .h(px(row_height))
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .cursor_pointer()
        .bg(background)
        .child(
            div()
                .flex_1()
                .min_w_0()
                .h_full()
                .flex()
                .items_center()
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .flex()
                        .items_center()
                        .gap(px(4.))
                        .pl(px(indent))
                        .pr(px(8.))
                        .child(toggle)
                        .child(name),
                )
                .when(is_leak, |cell| cell.child(warning_slot(colors))),
        )
        .child(metric_cell(
            Some(METRIC_WIDTH),
            integer(row.total_count),
            colors,
        ))
        .child(metric_cell(
            Some(METRIC_WIDTH),
            optional_bytes(row.native_size, language),
            colors,
        ))
        .child(metric_cell(
            Some(METRIC_WIDTH),
            format_bytes(row.shallow_size),
            colors,
        ))
        .child(metric_cell(
            Some(METRIC_WIDTH),
            optional_bytes(row.retained_size, language),
            colors,
        ));
    if show_heap {
        let heaps = {
            let mut names = row.heap_names.clone();
            names.sort();
            let joined = names.join(", ");
            if joined.is_empty() {
                "—".to_owned()
            } else {
                joined
            }
        };
        element = element.child(metric_cell(Some(METRIC_WIDTH), heaps, colors));
    }
    let click_row = row.clone();
    let click_entity = entity.clone();
    element
        .on_click(move |_, _, cx| {
            let click_row = click_row.clone();
            let _ = click_entity.update(cx, move |this, cx| {
                this.memory_select_classifier(click_row, cx)
            });
        })
        .into_any_element()
}

#[allow(clippy::too_many_arguments)]
fn instance_row(
    index: usize,
    row: &InstanceRow,
    selected: bool,
    is_leak: bool,
    row_height: f32,
    language: Language,
    entity: &Entity<ImportWorkspace>,
    colors: &StudioColors,
) -> AnyElement {
    let background = if selected {
        colors.selected_row
    } else {
        colors.panel
    };
    let label = instance_table_label(
        row.object_id,
        row.class_name.as_deref(),
        row.represented_class_name.as_deref(),
    );
    let depth = row.depth.map_or_else(
        || language.text(TextKey::MemoryPageUnavailable).to_owned(),
        |value| integer(u64::from(value)),
    );
    let shallow = if row.shallow_size_known {
        format_bytes(row.shallow_size)
    } else {
        language.text(TextKey::MemoryPageUnavailable).to_owned()
    };
    let name = div()
        .flex_1()
        .min_w_0()
        .h_full()
        .flex()
        .items_center()
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
                .child(crate::studio::selectable(label)),
        )
        .when(is_leak, |cell| cell.child(warning_slot(colors)));
    let object_id = row.object_id;
    let entity = entity.clone();
    div()
        .id(("memory-instance-row", index))
        .h(px(row_height))
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .cursor_pointer()
        .bg(background)
        .child(name)
        .child(metric_cell(Some(INSTANCE_DEPTH_WIDTH), depth, colors))
        .child(metric_cell(
            Some(INSTANCE_NATIVE_WIDTH),
            row.native_size.map_or_else(|| "—".to_owned(), format_bytes),
            colors,
        ))
        .child(metric_cell(Some(INSTANCE_SHALLOW_WIDTH), shallow, colors))
        .child(metric_cell(
            Some(INSTANCE_RETAINED_WIDTH),
            row.retained_size.map_or_else(
                || language.text(TextKey::MemoryPageUnavailable).to_owned(),
                format_bytes,
            ),
            colors,
        ))
        .on_click(move |_, _, cx| {
            let _ = entity.update(cx, move |this, cx| {
                this.memory_select_instance(object_id, cx)
            });
        })
        .into_any_element()
}

fn instance_table_header(language: Language, colors: &StudioColors) -> AnyElement {
    div()
        .id("memory-instance-header")
        .w_full()
        .h(px(TABLE_HEADER_HEIGHT))
        .flex_shrink_0()
        .flex()
        .items_center()
        .bg(colors.section_background)
        .child(header_cell(
            None,
            language.text(TextKey::MemoryPageInstance),
            false,
            false,
            colors,
        ))
        .child(header_cell(
            Some(INSTANCE_DEPTH_WIDTH),
            language.text(TextKey::MemoryPageDepth),
            true,
            true,
            colors,
        ))
        .child(header_cell(
            Some(INSTANCE_NATIVE_WIDTH),
            language.text(TextKey::MemoryPageNativeSize),
            true,
            true,
            colors,
        ))
        .child(header_cell(
            Some(INSTANCE_SHALLOW_WIDTH),
            language.text(TextKey::MemoryPageShallow),
            true,
            true,
            colors,
        ))
        .child(header_cell(
            Some(INSTANCE_RETAINED_WIDTH),
            language.text(TextKey::MemoryPageRetained),
            true,
            true,
            colors,
        ))
        .into_any_element()
}

fn instance_hint(text: &'static str, colors: &StudioColors) -> AnyElement {
    div()
        .flex_1()
        .min_h_0()
        .w_full()
        .p(px(8.))
        .child(
            div()
                .text_size(px(TABLE_FONT))
                .line_height(px(TABLE_LINE))
                .text_color(colors.secondary_text)
                .child(crate::studio::selectable(text)),
        )
        .into_any_element()
}

/// Compose ViewerTypography.secondary run (11 / 15) for captions.
fn secondary_text(text: impl Into<SharedString>, colors: &StudioColors) -> Div {
    div()
        .text_size(px(typography::SUPPORTING))
        .line_height(px(15.))
        .text_color(colors.secondary_text)
        .child(crate::studio::selectable(text))
}

/// Compose MemoryTableNameCell warning reserve: a 14 dp marker in the 22 dp
/// slot at the right edge of the name column.
fn warning_slot(colors: &StudioColors) -> AnyElement {
    div()
        .w(px(WARNING_WIDTH))
        .h_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .justify_center()
        .child(warning_icon(colors))
        .into_any_element()
}

/// Compose memoryTableGrid: a 1 dp line at the left edge of every metric
/// column, drawn across the whole table height so empty space keeps the grid.
/// The flexible filler soaks up the name column, so the lines land on the same
/// pixels as the metric cells' own left borders.
fn table_grid_overlay(id: &'static str, widths: &[f32], colors: &StudioColors) -> AnyElement {
    let mut overlay = div().id(id).absolute().inset_0().flex();
    overlay = overlay.child(div().flex_1());
    for index in 0..widths.len() {
        if index > 0 {
            overlay = overlay.child(div().w(px(widths[index - 1] - 1.)).h_full());
        }
        overlay = overlay.child(div().w(px(1.)).h_full().bg(colors.border));
    }
    if let Some(last) = widths.last() {
        overlay = overlay.child(div().w(px(last - 1.)).h_full());
    }
    overlay.into_any_element()
}

fn vertical_scrollbar(id: &'static str, handle: &UniformListScrollHandle) -> AnyElement {
    div()
        .absolute()
        .inset_0()
        .child(
            Scrollbar::new(handle)
                .id(id)
                .axis(ScrollbarAxis::Vertical)
                .viewport_from_layout(),
        )
        .into_any_element()
}

fn header_title(
    title: &'static str,
    column: ClassifierColumn,
    selected: ClassifierColumn,
    direction: SortDirection,
) -> String {
    if column != selected {
        return title.to_owned();
    }
    let suffix = match direction {
        SortDirection::Ascending => " ↑",
        SortDirection::Descending => " ↓",
    };
    format!("{title}{suffix}")
}

fn class_scope_label(scope: ClassScope, language: Language) -> &'static str {
    match scope {
        ClassScope::All => language.text(TextKey::MemoryPageAllClasses),
        ClassScope::Project => language.text(TextKey::MemoryPageProjectClasses),
        ClassScope::System => language.text(TextKey::MemoryPageSystemClasses),
    }
}

fn leak_filter_label(filter: LeakFilter, language: Language) -> &'static str {
    match filter {
        LeakFilter::None => language.text(TextKey::MemoryPageNone),
        LeakFilter::AllIssues => language.text(TextKey::MemoryPageAllIssues),
        LeakFilter::ActivityFragmentLeak => language.text(TextKey::MemoryPageActivityFragmentLeak),
        LeakFilter::DuplicateBitmaps => language.text(TextKey::MemoryPageDuplicateBitmaps),
    }
}

fn arrange_label(arrange: ArrangeBy, language: Language) -> &'static str {
    match arrange {
        ArrangeBy::Class => language.text(TextKey::MemoryPageClassOption),
        ArrangeBy::Package => language.text(TextKey::MemoryPagePackageOption),
    }
}

fn classifier_expanded_ids(state: &MemoryState) -> BTreeSet<String> {
    fn walk(rows: &[ClassifierRow], state: &MemoryState, out: &mut BTreeSet<String>) {
        for row in rows {
            if !row.children.is_empty() && state.classifier_expanded(row) {
                out.insert(row.id.clone());
            }
            walk(&row.children, state, out);
        }
    }
    let mut out = BTreeSet::new();
    walk(&state.classifier_rows, state, &mut out);
    out
}

/// Compose containsLeakClass: a group row is a leak row when it or any
/// descendant leaf class is reported by the leak findings.
fn classifier_leak_ids(
    rows: &[ClassifierRow],
    leak_classes: &BTreeSet<String>,
) -> BTreeSet<String> {
    fn walk(
        row: &ClassifierRow,
        leak_classes: &BTreeSet<String>,
        out: &mut BTreeSet<String>,
    ) -> bool {
        let mut leak = row
            .class_name
            .as_deref()
            .is_some_and(|name| leak_classes.contains(name));
        for child in &row.children {
            leak |= walk(child, leak_classes, out);
        }
        if leak {
            out.insert(row.id.clone());
        }
        leak
    }
    let mut out = BTreeSet::new();
    for row in rows {
        walk(row, leak_classes, &mut out);
    }
    out
}

/// Compose instanceTableLabel.
fn instance_table_label(
    object_id: u64,
    class_name: Option<&str>,
    represented_class_name: Option<&str>,
) -> String {
    let identity = format!("{object_id} (0x{object_id:x})");
    if let Some(represented) = represented_class_name {
        return format!("{} (class)@{identity}", short_class_name(represented));
    }
    match class_name.filter(|name| !name.is_empty()) {
        Some(class_name) => format!("{}@{identity}", short_class_name(class_name)),
        None => identity,
    }
}

fn short_class_name(class_name: &str) -> &str {
    class_name.rsplit('.').next().unwrap_or(class_name)
}

/// Compose formatBytes: MB/KB with one decimal, bytes below 1 KiB.
fn format_bytes(bytes: u64) -> String {
    const BYTES_PER_KB: f64 = 1024.;
    const BYTES_PER_MB: f64 = 1024. * 1024.;
    let value = bytes as f64;
    if value >= BYTES_PER_MB {
        format!("{:.1} MB", value / BYTES_PER_MB)
    } else if value >= BYTES_PER_KB {
        format!("{:.1} KB", value / BYTES_PER_KB)
    } else {
        format!("{bytes} B")
    }
}

/// Compose integer: grouping separators on base-10 counts.
fn integer(value: u64) -> String {
    let digits = value.to_string();
    let mut output = String::with_capacity(digits.len() + digits.len() / 3);
    for (index, character) in digits.chars().enumerate() {
        if index > 0 && (digits.len() - index) % 3 == 0 {
            output.push(',');
        }
        output.push(character);
    }
    output
}

fn optional_bytes(value: Option<u64>, language: Language) -> String {
    value.map_or_else(
        || language.text(TextKey::MemoryPageUnavailable).to_owned(),
        format_bytes,
    )
}

fn palette_color(index: usize) -> Rgba {
    rgb(HEAP_PALETTE[index % HEAP_PALETTE.len()])
}

fn fraction(value: u64, total: u64) -> f32 {
    if total == 0 {
        return 0.;
    }
    ((value as f64 / total as f64) as f32).max(0.0001)
}

/// Bounded instance paging mirroring Compose MemoryHeapPage.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
struct InstancePage {
    offset: usize,
    total: u64,
    loaded: usize,
    page_size: usize,
}

fn instance_page(state: &MemoryState) -> InstancePage {
    InstancePage {
        offset: state.instance_offset,
        total: state.instances.as_ref().map_or(0, |page| page.total),
        loaded: state.instance_count(),
        page_size: super::MEMORY_INSTANCE_PAGE,
    }
}

impl InstancePage {
    fn start(self) -> u64 {
        if self.loaded > 0 && (self.offset as u64) < self.total {
            self.offset as u64 + 1
        } else {
            0
        }
    }

    fn end(self) -> u64 {
        if self.start() == 0 {
            0
        } else {
            self.total.min(self.offset as u64 + self.loaded as u64)
        }
    }

    fn is_paged(self) -> bool {
        self.offset > 0 || self.total > self.loaded as u64
    }

    fn previous_offset(self) -> Option<usize> {
        if self.offset > 0 {
            Some(self.offset.saturating_sub(self.page_size.max(1)).max(0))
        } else {
            None
        }
    }

    fn next_offset(self) -> Option<usize> {
        let end = self.end();
        if self.loaded > 0 && self.page_size > 0 && end >= 1 && end < self.total {
            Some(end as usize)
        } else {
            None
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[::core::prelude::v1::test]
    fn formatters_match_the_compose_oracle() {
        assert_eq!(format_bytes(0), "0 B");
        assert_eq!(format_bytes(1023), "1023 B");
        assert_eq!(format_bytes(1024), "1.0 KB");
        assert_eq!(format_bytes(57_000_000), "54.4 MB");
        assert_eq!(integer(0), "0");
        assert_eq!(integer(68_772), "68,772");
    }

    #[::core::prelude::v1::test]
    fn instance_labels_match_the_compose_oracle() {
        assert_eq!(
            instance_table_label(42, Some("android.graphics.Bitmap"), None),
            "Bitmap@42 (0x2a)"
        );
        assert_eq!(
            instance_table_label(
                42,
                Some("android.graphics.Bitmap"),
                Some("android.graphics.Bitmap")
            ),
            "Bitmap (class)@42 (0x2a)"
        );
        assert_eq!(instance_table_label(42, None, None), "42 (0x2a)");
    }

    #[::core::prelude::v1::test]
    fn paging_is_bounded_and_does_not_invent_offsets() {
        let page = InstancePage {
            offset: 0,
            total: 500,
            loaded: 500,
            page_size: 500,
        };
        assert!(!page.is_paged());
        assert_eq!(page.previous_offset(), None);
        assert_eq!(page.next_offset(), None);

        let page = InstancePage {
            offset: 500,
            total: 1200,
            loaded: 500,
            page_size: 500,
        };
        assert!(page.is_paged());
        assert_eq!(page.start(), 501);
        assert_eq!(page.end(), 1000);
        assert_eq!(page.previous_offset(), Some(0));
        assert_eq!(page.next_offset(), Some(1000));

        let page = InstancePage {
            offset: 1000,
            total: 1200,
            loaded: 200,
            page_size: 500,
        };
        assert_eq!(page.end(), 1200);
        assert_eq!(page.next_offset(), None);
        assert_eq!(page.previous_offset(), Some(500));
    }
}

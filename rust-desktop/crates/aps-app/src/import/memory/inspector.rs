//! Object inspector (Fields / References / nearest GC root).
//!
//! This renders Compose MemoryObjectInspector *content* only: the caller owns
//! the pane width, card border and splitter. The bounded InstanceDetail payload
//! is the only evidence source, so an expandable object reference is a
//! drill-in affordance (chevron toggles the persisted expansion marker and the
//! row loads the target object) instead of claiming nested field rows the
//! worker never sent. Unavailable metrics stay an em dash.
use super::state::{InspectorTab, memory_row_height};
use super::{ImportWorkspace, MEMORY_ARRAY_PAGE};
use crate::studio::{self, StudioColors};
use aps_memory::{HeapValueKind, InstanceDetail, InstanceField};
use aps_ui_foundation::TextKey;
use gpui_kit::{
    component::{
        Selectable,
        checkbox::Checkbox,
        scroll::{ScrollableElement, Scrollbar, ScrollbarAxis},
    },
    prelude::*,
    *,
};
use std::collections::BTreeSet;

const TITLE_HEIGHT: f32 = 28.;
const TAB_HEIGHT: f32 = 28.;
const TOOLBAR_HEIGHT: f32 = 26.;
const MESSAGE_HEIGHT: f32 = 16.;
const HEADER_HEIGHT: f32 = 24.;
const TREE_INDENT: f32 = 12.;
const CHEVRON_WIDTH: f32 = 12.;
const TYPE_ICON_WIDTH: f32 = 16.;
const DEPTH_WIDTH: f32 = 52.;
const SHALLOW_WIDTH: f32 = 70.;
const RETAINED_WIDTH: f32 = 76.;
const NATIVE_WIDTH: f32 = 84.;

// Compose MemoryInspectorColumn order used by the persisted sort keys.
const SORT_NAME: u8 = 0;
const SORT_DEPTH: u8 = 1;
const SORT_SHALLOW: u8 = 2;
const SORT_RETAINED: u8 = 3;

/// One flattened inspector row: the object itself (References tab root), a
/// field, or an inbound reference edge. Built each frame from the bounded
/// detail payload; no cached child detail is implied by the chevron marker.
struct InspectorRow {
    label: String,
    copy_text: String,
    sort_name: String,
    tree_depth: u32,
    field_depth: Option<u32>,
    shallow_size: Option<u64>,
    retained_size: Option<u64>,
    native_size: Option<u64>,
    value_kind: HeapValueKind,
    /// Object to load when the row is activated.
    navigate: Option<u64>,
    /// Object whose chevron expansion marker this row toggles.
    expand_key: Option<u64>,
    is_root: bool,
    is_message: bool,
    expandable: bool,
    expanded: bool,
}

impl InspectorRow {
    fn message(label: String) -> Self {
        Self {
            label,
            copy_text: String::new(),
            sort_name: String::new(),
            tree_depth: 1,
            field_depth: None,
            shallow_size: None,
            retained_size: None,
            native_size: None,
            value_kind: HeapValueKind::Unknown,
            navigate: None,
            expand_key: None,
            is_root: false,
            is_message: true,
            expandable: false,
            expanded: false,
        }
    }
}

impl InspectorRow {
    fn sort_metric(&self, column: u8) -> i64 {
        let value = match column {
            SORT_DEPTH => self.field_depth.map(u64::from),
            SORT_SHALLOW => self.shallow_size,
            SORT_RETAINED => self.retained_size,
            _ => None,
        };
        value.map_or(-1, |value| value.min(i64::MAX as u64) as i64)
    }
}

impl ImportWorkspace {
    pub(super) fn render_memory_inspector(
        &mut self,
        colors: &StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let Some(detail) = self.memory_state.detail.clone() else {
            return inspector_placeholder(language, colors, self.memory_state.detail_loading);
        };
        let entity = cx.entity();
        let references_tab = self.memory_state.inspector_tab == InspectorTab::References;
        let nearest_only = self.memory_state.nearest_root_only;
        let pinned = self.memory_state.pinned.contains(&detail.object_id);
        let expanded = self.memory_state.inspector_expanded.clone();
        let sort_column = self.memory_state.inspector_sort_column;
        let sort_ascending = self.memory_state.inspector_sort_ascending;
        // Compose labels objects as "<Class>@<objectId> (0x<hex>)"; the first
        // number is the snapshot-local object id, never a page ordinal.
        let ordinal = detail.object_id;
        let show_native = detail.native_size.is_some()
            || detail
                .fields
                .iter()
                .any(|field| field.native_size.is_some())
            || detail
                .references
                .iter()
                .any(|field| field.native_size.is_some());
        let mut rows = build_rows(
            &detail,
            references_tab,
            nearest_only,
            ordinal,
            &expanded,
            sort_column,
            sort_ascending,
        );
        // The References tab always shows its object root row; when it has no
        // visible inbound edge, say so instead of leaving a bare root.
        if references_tab && rows.len() == 1 && rows.first().is_some_and(|row| row.expanded) {
            let key = if nearest_only && !detail.references.is_empty() {
                TextKey::MemoryPageMemoryRootUnavailable
            } else {
                TextKey::MemoryPageNoReferences
            };
            rows.push(InspectorRow::message(language.text(key).to_owned()));
        }

        let mut panel = div()
            .id("memory-object-inspector")
            .size_full()
            .min_h_0()
            .min_w_0()
            .overflow_hidden()
            .flex()
            .flex_col();

        // Title: Instance Details - shortClass@ordinal (0xID) + close x.
        let title = format!(
            "{} - {}",
            language.text(TextKey::MemoryPageInstanceDetails),
            object_label(
                detail.object_id,
                &detail.class_name,
                detail.represented_class_name.as_deref(),
                Some(ordinal),
            )
        );
        panel = panel.child(
            div()
                .id("memory-inspector-title")
                .h(px(TITLE_HEIGHT))
                .flex_shrink_0()
                .flex()
                .items_center()
                .gap(px(4.))
                .px(px(6.))
                .child(
                    div()
                        .id("memory-inspector-title-text")
                        .flex_1()
                        .min_w_0()
                        .text_size(px(studio::FONT_BODY))
                        .line_height(px(18.))
                        .font_weight(FontWeight::SEMIBOLD)
                        .text_color(colors.text)
                        .whitespace_nowrap()
                        .text_ellipsis()
                        .child(crate::studio::selectable(title)),
                )
                .child(
                    studio::compact_text_button("memory-inspector-close", "×", true, false, colors)
                        .tooltip(language.text(TextKey::MemoryPageMemoryInspectorClose))
                        .on_click(cx.listener(|this, _, _, cx| this.memory_close_inspector(cx))),
                ),
        );
        panel = panel.child(studio::divider(colors));

        // Tabs Fields / References with a 2px accent underline.
        let mut tabs = div()
            .id("memory-inspector-tabs")
            .h(px(TAB_HEIGHT))
            .flex_shrink_0()
            .flex()
            .items_center()
            .px(px(4.));
        for (index, (tab, key)) in [
            (InspectorTab::Fields, TextKey::MemoryPageFields),
            (InspectorTab::References, TextKey::MemoryPageReferences),
        ]
        .into_iter()
        .enumerate()
        {
            let selected = self.memory_state.inspector_tab == tab;
            tabs = tabs.child(
                div()
                    .id(("memory-inspector-tab", index))
                    .h(px(TAB_HEIGHT))
                    .flex_shrink_0()
                    .flex()
                    .flex_col()
                    .cursor_pointer()
                    .on_click(
                        cx.listener(move |this, _, _, cx| this.memory_set_inspector_tab(tab, cx)),
                    )
                    .child(
                        div()
                            .flex_1()
                            .flex()
                            .items_center()
                            .px(px(10.))
                            .text_size(px(studio::FONT_BODY))
                            .line_height(px(18.))
                            .font_weight(if selected {
                                FontWeight::SEMIBOLD
                            } else {
                                FontWeight::NORMAL
                            })
                            .text_color(if selected {
                                colors.accent
                            } else {
                                colors.secondary_text
                            })
                            .child(crate::studio::selectable(language.text(key))),
                    )
                    .child(div().h(px(2.)).w_full().bg(if selected {
                        colors.accent
                    } else {
                        colors.panel
                    })),
            );
        }
        panel = panel.child(tabs);
        panel = panel.child(studio::divider(colors));

        // History / clipboard / pin toolbar.
        let mut toolbar = div()
            .id("memory-inspector-toolbar")
            .h(px(TOOLBAR_HEIGHT))
            .flex_shrink_0()
            .flex()
            .items_center()
            .gap(px(4.))
            .px(px(6.))
            .overflow_x_scrollbar()
            .child(
                studio::compact_text_button(
                    "memory-inspector-back",
                    language.text(TextKey::MemoryPageBack),
                    self.memory_state.history_back_enabled(),
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.memory_history_back(cx))),
            )
            .child(
                studio::compact_text_button(
                    "memory-inspector-forward",
                    language.text(TextKey::MemoryPageForward),
                    self.memory_state.history_forward_enabled(),
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.memory_history_forward(cx))),
            );
        let object_id = detail.object_id;
        toolbar = toolbar
            .child(
                studio::compact_text_button(
                    "memory-inspector-copy-id",
                    language.text(TextKey::MemoryPageCopyObjectId),
                    true,
                    false,
                    colors,
                )
                .on_click(
                    cx.listener(move |this, _, _, cx| this.memory_copy_object_id(object_id, cx)),
                ),
            )
            .child(
                studio::compact_text_button(
                    "memory-inspector-pin",
                    language.text(if pinned {
                        TextKey::MemoryPageUnpinObject
                    } else {
                        TextKey::MemoryPagePinObject
                    }),
                    true,
                    pinned,
                    colors,
                )
                .selected(pinned)
                .on_click(cx.listener(move |this, _, _, cx| this.memory_toggle_pin(object_id, cx))),
            );
        panel = panel.child(toolbar);

        // Whole-object metrics (unavailable evidence stays an em dash).
        let mut metrics = format!(
            "{} {} · {} {} · {} {}",
            language.text(TextKey::MemoryPageShallow),
            byte_metric(detail.shallow_size_known.then_some(detail.shallow_size)),
            language.text(TextKey::MemoryPageRetained),
            byte_metric(detail.retained_size),
            language.text(TextKey::MemoryPageNativeSize),
            byte_metric(detail.native_size),
        );
        if let Some(depth) = detail.depth {
            metrics.push_str(&format!(
                " · {} {}",
                language.text(TextKey::MemoryPageDepth),
                grouped_integer(u64::from(depth))
            ));
        }
        panel = panel.child(
            div()
                .id("memory-inspector-metrics")
                .h(px(MESSAGE_HEIGHT))
                .flex_shrink_0()
                .px(px(6.))
                .overflow_hidden()
                .flex()
                .items_center()
                .child(
                    studio::supporting(metrics, colors)
                        .whitespace_nowrap()
                        .text_ellipsis(),
                ),
        );
        if let Some(preview) = detail.string_preview.as_deref() {
            panel = panel.child(
                div()
                    .id("memory-inspector-string-preview")
                    .h(px(MESSAGE_HEIGHT))
                    .flex_shrink_0()
                    .px(px(6.))
                    .overflow_hidden()
                    .flex()
                    .items_center()
                    .child(
                        div()
                            .text_size(px(studio::FONT_SUPPORTING))
                            .line_height(px(15.))
                            .text_color(colors.success)
                            .font_weight(FontWeight::SEMIBOLD)
                            .whitespace_nowrap()
                            .text_ellipsis()
                            .child(crate::studio::selectable(preview.to_owned())),
                    ),
            );
        }
        if detail.omitted {
            panel = panel.child(
                div()
                    .id("memory-inspector-omitted")
                    .h(px(MESSAGE_HEIGHT))
                    .flex_shrink_0()
                    .px(px(6.))
                    .overflow_hidden()
                    .flex()
                    .items_center()
                    .child(
                        div()
                            .text_size(px(studio::FONT_SUPPORTING))
                            .line_height(px(15.))
                            .text_color(colors.warning)
                            .whitespace_nowrap()
                            .text_ellipsis()
                            .child(crate::studio::selectable(
                                language.text(TextKey::MemoryPageOmitted),
                            )),
                    ),
            );
        }

        // Array paging: 200-element bounded pages loaded from object_id/start.
        if detail.is_array {
            let element_count = detail.element_count.unwrap_or(0);
            let page_size = if detail.array_page_size == 0 {
                MEMORY_ARRAY_PAGE as u64
            } else {
                detail.array_page_size
            };
            let start = detail.array_start;
            let end = start
                .saturating_add(detail.fields.len() as u64)
                .min(element_count);
            let first = if end > start {
                start.saturating_add(1)
            } else {
                start
            };
            let previous_start = start.saturating_sub(page_size);
            let next_start = start.saturating_add(page_size);
            let can_previous = start > 0;
            let can_next = next_start < element_count;
            panel = panel.child(
                div()
                    .id("memory-inspector-array-paging")
                    .h(px(TOOLBAR_HEIGHT))
                    .flex_shrink_0()
                    .flex()
                    .items_center()
                    .gap(px(6.))
                    .px(px(6.))
                    .overflow_x_scrollbar()
                    .child(studio::supporting(
                        language.format(
                            TextKey::MemoryPageArrayElements,
                            &[("arg_1", &element_count)],
                        ),
                        colors,
                    ))
                    .child(studio::supporting(
                        language.format(
                            TextKey::MemoryPageArrayPageRange,
                            &[
                                ("arg_1", &first),
                                ("arg_2", &end),
                                ("arg_3", &element_count),
                            ],
                        ),
                        colors,
                    ))
                    .child(
                        studio::compact_text_button(
                            "memory-inspector-array-previous",
                            language.text(TextKey::MemoryPagePreviousArrayPage),
                            can_previous,
                            false,
                            colors,
                        )
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.memory_load_inspector_object(object_id, previous_start, cx)
                        })),
                    )
                    .child(
                        studio::compact_text_button(
                            "memory-inspector-array-next",
                            language.text(TextKey::MemoryPageNextArrayPage),
                            can_next,
                            false,
                            colors,
                        )
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.memory_load_inspector_object(object_id, next_start, cx)
                        })),
                    ),
            );
        }

        // References tab: nearest GC root only checkbox.
        if references_tab {
            let weak = entity.downgrade();
            panel = panel.child(
                div()
                    .id("memory-inspector-nearest-root")
                    .h(px(TAB_HEIGHT))
                    .flex_shrink_0()
                    .flex()
                    .items_center()
                    .px(px(6.))
                    .child(
                        Checkbox::new("memory-inspector-nearest-root-only")
                            .checked(nearest_only)
                            .label(language.text(TextKey::MemoryPageShowNearestGcRootOnly))
                            .on_click(move |checked, _, cx| {
                                let _ = weak.update(cx, |this, cx| {
                                    this.memory_set_nearest_root_only(*checked, cx)
                                });
                            }),
                    ),
            );
        }

        // Table header, sorted like Compose MemoryInspectorTable.
        let label_key = if references_tab {
            TextKey::MemoryPageMemoryReferenceColumn
        } else {
            TextKey::MemoryPageInstance
        };
        let mut header = studio::table_header_row(colors)
            .id("memory-inspector-columns")
            .h(px(HEADER_HEIGHT))
            .flex_shrink_0()
            .py(px(2.))
            .px(px(6.))
            .child(sort_header(
                "memory-inspector-header-name",
                SORT_NAME,
                language.text(label_key),
                None,
                sort_column,
                sort_ascending,
                &entity,
                colors,
            ))
            .child(sort_header(
                "memory-inspector-header-depth",
                SORT_DEPTH,
                language.text(TextKey::MemoryPageDepth),
                Some(DEPTH_WIDTH),
                sort_column,
                sort_ascending,
                &entity,
                colors,
            ))
            .child(sort_header(
                "memory-inspector-header-shallow",
                SORT_SHALLOW,
                language.text(TextKey::MemoryPageMemoryInspectorShallowSize),
                Some(SHALLOW_WIDTH),
                sort_column,
                sort_ascending,
                &entity,
                colors,
            ))
            .child(sort_header(
                "memory-inspector-header-retained",
                SORT_RETAINED,
                language.text(TextKey::MemoryPageMemoryInspectorRetainedSize),
                Some(RETAINED_WIDTH),
                sort_column,
                sort_ascending,
                &entity,
                colors,
            ));
        if show_native {
            header = header.child(
                div()
                    .w(px(NATIVE_WIDTH))
                    .flex_shrink_0()
                    .h_full()
                    .flex()
                    .items_center()
                    .justify_end()
                    .px(px(4.))
                    .border_l_1()
                    .border_color(colors.border)
                    .text_size(px(studio::FONT_SUPPORTING))
                    .font_weight(FontWeight::SEMIBOLD)
                    .text_color(colors.secondary_text)
                    .whitespace_nowrap()
                    .text_ellipsis()
                    .child(crate::studio::selectable(
                        language.text(TextKey::MemoryPageNativeSize),
                    )),
            );
        }
        panel = panel.child(header);

        // Row viewport.
        if rows.is_empty() {
            let empty_key = if references_tab {
                if nearest_only && !detail.references.is_empty() {
                    TextKey::MemoryPageMemoryRootUnavailable
                } else {
                    TextKey::MemoryPageNoReferences
                }
            } else if detail.is_array && detail.element_count.unwrap_or(0) > 0 {
                TextKey::MemoryPageArrayValuesUnavailable
            } else {
                TextKey::MemoryPageNoObjectFields
            };
            panel = panel.child(
                div()
                    .flex_1()
                    .min_h_0()
                    .w_full()
                    .flex()
                    .items_center()
                    .justify_center()
                    .p(px(12.))
                    .child(studio::supporting(language.text(empty_key), colors)),
            );
        } else {
            let list_entity = entity.clone();
            let list_rows = rows;
            let list_colors = *colors;
            let list_scroll = self.memory_state.inspector_scroll.clone();
            let list = uniform_list(
                "memory-inspector-rows",
                list_rows.len(),
                move |range, _, _| {
                    range
                        .filter_map(|index| {
                            list_rows.get(index).map(|row| {
                                inspector_row(index, row, &list_entity, &list_colors, show_native)
                            })
                        })
                        .collect()
                },
            )
            .track_scroll(&list_scroll)
            .h_full()
            .w_full();
            panel = panel.child(
                div()
                    .relative()
                    .flex_1()
                    .min_h_0()
                    .w_full()
                    .child(list)
                    .child(
                        div().absolute().inset_0().child(
                            Scrollbar::new(&list_scroll)
                                .id("memory-inspector-vertical-scrollbar")
                                .axis(ScrollbarAxis::Vertical)
                                .viewport_from_layout(),
                        ),
                    ),
            );
        }

        panel.into_any_element()
    }
}

fn inspector_placeholder(
    language: aps_ui_foundation::Language,
    colors: &StudioColors,
    loading: bool,
) -> AnyElement {
    let hint = if loading {
        TextKey::MemoryPageMemoryInspectorLoading
    } else {
        TextKey::MemoryPageSelectAnInstanceToViewDetails
    };
    div()
        .id("memory-object-inspector")
        .size_full()
        .min_h_0()
        .min_w_0()
        .overflow_hidden()
        .flex()
        .flex_col()
        .child(
            div()
                .h(px(TITLE_HEIGHT))
                .flex_shrink_0()
                .flex()
                .items_center()
                .px(px(6.))
                .child(
                    div()
                        .text_size(px(studio::FONT_BODY))
                        .line_height(px(18.))
                        .font_weight(FontWeight::SEMIBOLD)
                        .text_color(colors.text)
                        .whitespace_nowrap()
                        .text_ellipsis()
                        .child(crate::studio::selectable(
                            language.text(TextKey::MemoryPageInstanceDetails),
                        )),
                ),
        )
        .child(studio::divider(colors))
        .child(
            div()
                .flex_1()
                .min_h_0()
                .flex()
                .items_center()
                .justify_center()
                .p(px(12.))
                .child(studio::supporting(language.text(hint), colors)),
        )
        .into_any_element()
}

/// Header cell: click cycles/toggles the persisted sort column, matching the
/// Compose MemoryInspectorSort default direction (name/depth ascending, size
/// metrics descending).
#[allow(clippy::too_many_arguments)]
fn sort_header(
    id: &'static str,
    column: u8,
    label: &'static str,
    width: Option<f32>,
    sort_column: u8,
    sort_ascending: bool,
    entity: &Entity<ImportWorkspace>,
    colors: &StudioColors,
) -> AnyElement {
    let active = sort_column == column;
    let arrow = if !active {
        ""
    } else if sort_ascending {
        " ↑"
    } else {
        " ↓"
    };
    let text: SharedString = format!("{label}{arrow}").into();
    let mut cell = div()
        .id(id)
        .h_full()
        .flex()
        .items_center()
        .cursor_pointer()
        .text_size(px(studio::FONT_SUPPORTING))
        .line_height(px(15.))
        .font_weight(FontWeight::SEMIBOLD)
        .text_color(if active {
            colors.text
        } else {
            colors.secondary_text
        })
        .whitespace_nowrap()
        .text_ellipsis()
        .on_click({
            let entity = entity.clone();
            move |_, _, cx| {
                let _ = entity.update(cx, |this, cx| {
                    let state = &mut this.memory_state;
                    if state.inspector_sort_column == column {
                        state.inspector_sort_ascending = !state.inspector_sort_ascending;
                    } else {
                        state.inspector_sort_column = column;
                        state.inspector_sort_ascending = column <= SORT_DEPTH;
                    }
                    cx.notify();
                });
            }
        });
    if let Some(width) = width {
        cell = cell
            .w(px(width))
            .flex_shrink_0()
            .justify_end()
            .px(px(4.))
            .border_l_1()
            .border_color(colors.border);
    } else {
        cell = cell.flex_1().min_w_0();
    }
    cell.child(crate::studio::selectable(text))
        .into_any_element()
}

fn inspector_row(
    index: usize,
    row: &InspectorRow,
    entity: &Entity<ImportWorkspace>,
    colors: &StudioColors,
    show_native: bool,
) -> AnyElement {
    let indent = 6. + row.tree_depth as f32 * TREE_INDENT;
    if row.is_message {
        return div()
            .id(("memory-inspector-row", index))
            .h(px(memory_row_height()))
            .w_full()
            .flex_shrink_0()
            .flex()
            .items_center()
            .pl(px(indent))
            .pr(px(6.))
            .text_size(px(studio::FONT_SUPPORTING))
            .line_height(px(15.))
            .text_color(colors.muted_text)
            .whitespace_nowrap()
            .text_ellipsis()
            .child(row.label.clone())
            .into_any_element();
    }
    let mut element = div()
        .id(("memory-inspector-row", index))
        .h(px(memory_row_height()))
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .gap(px(3.))
        .pl(px(indent))
        .pr(px(6.))
        .cursor_pointer();
    if row.expandable {
        let glyph = if row.expanded { "▾" } else { "▸" };
        let toggle_key = row.expand_key;
        let navigate = row.navigate;
        let chevron_entity = entity.clone();
        element = element.child(
            div()
                .id(("memory-inspector-chevron", index))
                .w(px(CHEVRON_WIDTH))
                .h_full()
                .flex()
                .items_center()
                .justify_center()
                .text_size(px(10.))
                .line_height(px(15.))
                .text_color(colors.secondary_text)
                .on_click(move |_, _, cx| {
                    cx.stop_propagation();
                    let _ = chevron_entity.update(cx, |this, cx| {
                        if let Some(key) = toggle_key {
                            if !this.memory_state.inspector_expanded.remove(&key) {
                                this.memory_state.inspector_expanded.insert(key);
                            }
                        }
                        match navigate {
                            Some(target) => {
                                this.memory_state.push_history(target);
                                this.memory_load_inspector_object(target, 0, cx);
                            }
                            None => cx.notify(),
                        }
                    });
                })
                .child(glyph),
        );
    } else {
        element = element.child(div().w(px(CHEVRON_WIDTH)).h_full());
    }
    let (glyph, tint) = type_icon(row.value_kind, row.is_root, colors);
    element = element
        .child(
            div()
                .w(px(TYPE_ICON_WIDTH))
                .h_full()
                .flex()
                .items_center()
                .justify_center()
                .text_size(px(9.))
                .line_height(px(15.))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(tint)
                .child(glyph),
        )
        .child(
            div()
                .flex_1()
                .min_w_0()
                .text_size(px(studio::FONT_SUPPORTING))
                .line_height(px(15.))
                .text_color(colors.text)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(row.label.clone()),
        )
        .child(metric_cell(
            metric_text(row.field_depth.map(u64::from)),
            DEPTH_WIDTH,
            colors,
        ))
        .child(metric_cell(
            metric_text(row.shallow_size),
            SHALLOW_WIDTH,
            colors,
        ))
        .child(metric_cell(
            metric_text(row.retained_size),
            RETAINED_WIDTH,
            colors,
        ));
    if show_native {
        element = element.child(metric_cell(
            metric_text(row.native_size),
            NATIVE_WIDTH,
            colors,
        ));
    }
    let navigate = row.navigate;
    let copy_text = row.copy_text.clone();
    let is_root = row.is_root;
    let expand_key = row.expand_key;
    let row_entity = entity.clone();
    element
        .on_click(move |_, _, cx| {
            if is_root {
                let _ = row_entity.update(cx, |this, cx| {
                    if let Some(key) = expand_key {
                        if !this.memory_state.inspector_expanded.remove(&key) {
                            this.memory_state.inspector_expanded.insert(key);
                        }
                    }
                    cx.notify();
                });
                return;
            }
            match navigate {
                Some(target) => {
                    let _ = row_entity.update(cx, |this, cx| {
                        this.memory_state.push_history(target);
                        this.memory_load_inspector_object(target, 0, cx);
                    });
                }
                None => cx.write_to_clipboard(ClipboardItem::new_string(copy_text.clone())),
            }
        })
        .into_any_element()
}

fn metric_cell(text: String, width: f32, colors: &StudioColors) -> AnyElement {
    let available = text != "—";
    div()
        .w(px(width))
        .flex_shrink_0()
        .h_full()
        .flex()
        .items_center()
        .justify_end()
        .px(px(4.))
        .border_l_1()
        .border_color(colors.border)
        .text_size(px(studio::FONT_SUPPORTING))
        .line_height(px(15.))
        .text_color(if available {
            colors.text
        } else {
            colors.muted_text
        })
        .whitespace_nowrap()
        .text_ellipsis()
        .child(crate::studio::selectable(text))
        .into_any_element()
}

fn type_icon(kind: HeapValueKind, is_root: bool, colors: &StudioColors) -> (&'static str, Rgba) {
    match kind {
        HeapValueKind::Primitive => ("·", colors.warning),
        HeapValueKind::Array => ("[]", colors.info),
        HeapValueKind::Class => ("C", colors.accent),
        HeapValueKind::Object => {
            if is_root {
                ("●", colors.success)
            } else {
                ("○", colors.success)
            }
        }
        HeapValueKind::Unknown => ("?", colors.muted_text),
    }
}

fn build_rows(
    detail: &InstanceDetail,
    references_tab: bool,
    nearest_only: bool,
    ordinal: u64,
    expanded: &BTreeSet<u64>,
    sort_column: u8,
    sort_ascending: bool,
) -> Vec<InspectorRow> {
    let mut rows = Vec::new();
    if references_tab {
        let root_expanded = !expanded.contains(&detail.object_id);
        let label = object_label(
            detail.object_id,
            &detail.class_name,
            detail.represented_class_name.as_deref(),
            Some(ordinal),
        );
        let has_references = !detail.references.is_empty();
        rows.push(InspectorRow {
            copy_text: label.clone(),
            label,
            sort_name: detail.class_name.clone(),
            tree_depth: 0,
            field_depth: detail.depth,
            shallow_size: detail.shallow_size_known.then_some(detail.shallow_size),
            retained_size: detail.retained_size,
            native_size: detail.native_size,
            value_kind: detail.value_kind,
            navigate: None,
            expand_key: Some(detail.object_id),
            is_root: true,
            is_message: false,
            expandable: has_references,
            expanded: root_expanded,
        });
        if root_expanded {
            let chain: BTreeSet<u64> = detail
                .reference_chain
                .iter()
                .map(|reference| reference.object_id)
                .collect();
            let mut children: Vec<InspectorRow> = detail
                .references
                .iter()
                .filter(|field| reference_visible(detail.depth, field, nearest_only, &chain))
                .map(|field| field_row(field, true, 1, expanded))
                .collect();
            sort_rows(&mut children, sort_column, sort_ascending);
            rows.extend(children);
        }
    } else {
        rows.extend(
            detail
                .fields
                .iter()
                .map(|field| field_row(field, false, 0, expanded)),
        );
        sort_rows(&mut rows, sort_column, sort_ascending);
    }
    rows
}

fn reference_visible(
    detail_depth: Option<u32>,
    field: &InstanceField,
    nearest_only: bool,
    chain: &BTreeSet<u64>,
) -> bool {
    if !nearest_only {
        return true;
    }
    if let (Some(detail_depth), Some(depth)) = (detail_depth, field.depth) {
        if depth < detail_depth {
            return true;
        }
    }
    field
        .target_object_id
        .is_some_and(|target| chain.contains(&target))
}

fn field_row(
    field: &InstanceField,
    references: bool,
    tree_depth: u32,
    expanded: &BTreeSet<u64>,
) -> InspectorRow {
    let target = field.target_object_id.filter(|id| *id != 0);
    let label = if references {
        reference_label(field)
    } else {
        field_label(field)
    };
    let copy_text = if references {
        label.clone()
    } else {
        field_value_label(field)
    };
    InspectorRow {
        label,
        copy_text,
        sort_name: field.name.clone(),
        tree_depth,
        field_depth: field.depth,
        shallow_size: field.shallow_size,
        retained_size: field.retained_size,
        native_size: field.native_size,
        value_kind: field.value_kind,
        navigate: target,
        expand_key: target,
        is_root: false,
        is_message: false,
        expandable: target.is_some(),
        expanded: target.is_some_and(|id| expanded.contains(&id)),
    }
}

fn sort_rows(rows: &mut [InspectorRow], column: u8, ascending: bool) {
    rows.sort_by(|a, b| {
        let ordering = if column == SORT_NAME {
            a.sort_name.cmp(&b.sort_name)
        } else {
            a.sort_metric(column).cmp(&b.sort_metric(column))
        };
        if ascending {
            ordering
        } else {
            ordering.reverse()
        }
    });
}

fn object_label(
    object_id: u64,
    class_name: &str,
    represented_class_name: Option<&str>,
    ordinal: Option<u64>,
) -> String {
    let name = match represented_class_name {
        Some(represented) => format!("{} (class)", short_class_name(represented)),
        None => short_class_name(class_name).to_owned(),
    };
    format!(
        "{}@{} (0x{:x})",
        name,
        ordinal.unwrap_or(object_id),
        object_id
    )
}

fn short_class_name(class_name: &str) -> &str {
    class_name.rsplit('.').next().unwrap_or(class_name)
}

fn field_label(field: &InstanceField) -> String {
    let name = field
        .name
        .strip_prefix('[')
        .and_then(|name| name.strip_suffix(']'))
        .unwrap_or(field.name.as_str());
    let value = field_value_label(field);
    if name.is_empty() {
        value
    } else {
        format!("{name} = {value}")
    }
}

fn field_value_label(field: &InstanceField) -> String {
    if field.value_kind == HeapValueKind::Primitive {
        return field.display_value.clone();
    }
    match field.target_object_id {
        Some(0) => "null".to_owned(),
        None => field.display_value.clone(),
        Some(_) => {
            let class = field
                .represented_class_name
                .as_deref()
                .or(field.target_class_name.as_deref())
                .unwrap_or("<unknown>");
            let mut value = format!("{{{}}}", short_class_name(class));
            if let Some(preview) = field.string_preview.as_deref() {
                value.push(' ');
                value.push_str(preview);
            }
            value
        }
    }
}

fn reference_label(field: &InstanceField) -> String {
    let identity = match field.target_object_id.filter(|id| *id != 0) {
        Some(target) => object_label(
            target,
            field.target_class_name.as_deref().unwrap_or("<unknown>"),
            field.represented_class_name.as_deref(),
            None,
        ),
        None => field.display_value.clone(),
    };
    if field.name.is_empty() {
        identity
    } else {
        format!("{} in {identity}", field.name)
    }
}

fn metric_text(value: Option<u64>) -> String {
    value.map_or_else(|| "—".to_owned(), grouped_integer)
}

fn byte_metric(value: Option<u64>) -> String {
    value.map_or_else(|| "—".to_owned(), bytes_text)
}

fn bytes_text(value: u64) -> String {
    if value >= 1024 * 1024 {
        format!("{:.1} MB", value as f64 / 1048576.)
    } else if value >= 1024 {
        format!("{:.1} KB", value as f64 / 1024.)
    } else {
        format!("{value} B")
    }
}

fn grouped_integer(value: u64) -> String {
    let digits = value.to_string();
    let mut out = String::with_capacity(digits.len() + digits.len() / 3);
    for (index, character) in digits.chars().enumerate() {
        if index > 0 && (digits.len() - index) % 3 == 0 {
            out.push(',');
        }
        out.push(character);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn field(name: &str, shallow: Option<u64>, retained: Option<u64>) -> InstanceField {
        InstanceField {
            name: name.into(),
            display_value: shallow.map_or_else(String::new, |value| value.to_string()),
            shallow_size: shallow,
            retained_size: retained,
            ..InstanceField::default()
        }
    }

    #[::core::prelude::v1::test]
    fn object_labels_use_short_class_ordinal_and_lowercase_hex() {
        assert_eq!(
            object_label(42, "sample.Target", None, Some(42)),
            "Target@42 (0x2a)"
        );
        assert_eq!(
            object_label(43, "sample.Target", Some("com.example.Carrier"), Some(43)),
            "Carrier (class)@43 (0x2b)"
        );
        // Falls back to the object id when no instance row supplied an ordinal.
        assert_eq!(object_label(255, "a.b.C", None, None), "C@255 (0xff)");
    }

    #[::core::prelude::v1::test]
    fn field_values_keep_primitive_object_null_and_preview_shapes() {
        let primitive = InstanceField {
            name: "count".into(),
            display_value: "7".into(),
            value_kind: HeapValueKind::Primitive,
            ..InstanceField::default()
        };
        assert_eq!(field_label(&primitive), "count = 7");

        let object = InstanceField {
            name: "child".into(),
            display_value: "ignored".into(),
            target_object_id: Some(7),
            target_class_name: Some("sample.Child".into()),
            value_kind: HeapValueKind::Object,
            string_preview: Some("hello".into()),
            ..InstanceField::default()
        };
        assert_eq!(field_label(&object), "child = {Child} hello");

        let null = InstanceField {
            name: "zero".into(),
            display_value: "ignored".into(),
            target_object_id: Some(0),
            ..InstanceField::default()
        };
        assert_eq!(field_label(&null), "zero = null");

        // Array element names lose their brackets and keep the scalar value.
        assert_eq!(field_label(&field("[3]", Some(8), None)), "3 = 8");
    }

    #[::core::prelude::v1::test]
    fn reference_labels_name_the_class_and_never_invent_an_edge_name() {
        let reference = InstanceField {
            name: "target42".into(),
            target_object_id: Some(4),
            target_class_name: Some("sample.Root".into()),
            ..InstanceField::default()
        };
        assert_eq!(reference_label(&reference), "target42 in Root@4 (0x4)");

        let unnamed = InstanceField {
            name: String::new(),
            target_object_id: Some(4),
            target_class_name: Some("sample.Root".into()),
            ..InstanceField::default()
        };
        assert_eq!(reference_label(&unnamed), "Root@4 (0x4)");
    }

    #[::core::prelude::v1::test]
    fn rows_sort_known_metrics_before_unavailable_ones() {
        let detail = InstanceDetail {
            fields: vec![
                field("a", Some(10), Some(1)),
                field("b", None, None),
                field("c", Some(5), Some(2)),
            ],
            ..InstanceDetail::default()
        };
        let expanded = BTreeSet::new();
        let ascending = build_rows(&detail, false, false, 0, &expanded, SORT_SHALLOW, true);
        assert_eq!(
            ascending
                .iter()
                .map(|row| row.sort_name.as_str())
                .collect::<Vec<_>>(),
            ["b", "c", "a"]
        );
        let descending = build_rows(&detail, false, false, 0, &expanded, SORT_SHALLOW, false);
        assert_eq!(
            descending
                .iter()
                .map(|row| row.sort_name.as_str())
                .collect::<Vec<_>>(),
            ["a", "c", "b"]
        );
        let by_name = build_rows(&detail, false, false, 0, &expanded, SORT_NAME, true);
        assert_eq!(
            by_name
                .iter()
                .map(|row| row.sort_name.as_str())
                .collect::<Vec<_>>(),
            ["a", "b", "c"]
        );
    }

    #[::core::prelude::v1::test]
    fn nearest_root_filter_keeps_closer_depths_and_chain_members() {
        let near = InstanceField {
            depth: Some(0),
            ..InstanceField::default()
        };
        let far = InstanceField {
            depth: Some(1),
            ..InstanceField::default()
        };
        let mut chain = BTreeSet::new();
        chain.insert(9);
        let chained = InstanceField {
            target_object_id: Some(9),
            depth: Some(2),
            ..InstanceField::default()
        };
        assert!(reference_visible(Some(1), &near, true, &chain));
        assert!(!reference_visible(Some(1), &far, true, &chain));
        assert!(reference_visible(Some(1), &chained, true, &chain));
        // The filter never hides evidence when nearest-root-only is off.
        assert!(reference_visible(Some(1), &far, false, &chain));
        // Without a known detail depth the chain is the only near-root proof.
        assert!(!reference_visible(None, &far, true, &chain));
    }

    #[::core::prelude::v1::test]
    fn reference_tab_renders_root_row_and_honours_collapse() {
        let detail = InstanceDetail {
            object_id: 42,
            class_name: "sample.Target".into(),
            reference_chain: vec![aps_memory::ObjectReference {
                object_id: 4,
                class_name: "sample.Root".into(),
            }],
            references: vec![InstanceField {
                name: "target42".into(),
                target_object_id: Some(4),
                target_class_name: Some("sample.Root".into()),
                depth: Some(0),
                ..InstanceField::default()
            }],
            depth: Some(1),
            shallow_size_known: true,
            shallow_size: 32,
            ..InstanceDetail::default()
        };
        let expanded = BTreeSet::new();
        let rows = build_rows(&detail, true, false, 7, &expanded, SORT_NAME, true);
        assert_eq!(rows.len(), 2);
        assert!(rows[0].is_root);
        assert_eq!(rows[0].label, "Target@7 (0x2a)");
        assert_eq!(rows[1].tree_depth, 1);

        let mut collapsed = BTreeSet::new();
        collapsed.insert(42);
        let rows = build_rows(&detail, true, false, 7, &collapsed, SORT_NAME, true);
        assert_eq!(rows.len(), 1);
        assert!(!rows[0].expanded);
    }

    #[::core::prelude::v1::test]
    fn unavailable_metrics_render_em_dash_and_bytes_stay_dense() {
        assert_eq!(metric_text(None), "—");
        assert_eq!(metric_text(Some(1234567)), "1,234,567");
        assert_eq!(byte_metric(None), "—");
        assert_eq!(bytes_text(64), "64 B");
        assert_eq!(bytes_text(2048), "2.0 KB");
        assert_eq!(bytes_text(3 * 1024 * 1024), "3.0 MB");
    }
}

//! Object inspector (Fields / References / nearest GC root).
//!
//! This renders Compose MemoryObjectInspector / MemoryInspectorTable parity:
//! a 28 px title row with the close control, the Fields/References tab strip
//! with its 2 px accent underline, the References nearest-root toggle, and the
//! Compose inspector table (24 px toolbar header, 20 px rows, 13/16 body-compact
//! text, disclosure + type badge + Compose label grammar, ASCII-hyphen
//! unavailable metrics and Compose's column order/widths).
//!
//! Expanded fields and references use bounded, lazily loaded InstanceDetail
//! payloads; their expansion state is local to each tree path.
use super::state::{InspectorTab, MemoryTable};
use super::{ImportWorkspace, MEMORY_ARRAY_PAGE, widgets};
use crate::studio::{self, StudioColors};
use aps_memory::{HeapValueKind, InstanceDetail, InstanceField, MemorySnapshot};
use aps_ui_foundation::{Language, TextKey, typography};
use gpui_kit::{
    component::scroll::{ScrollableElement, Scrollbar, ScrollbarAxis},
    prelude::*,
    *,
};
use std::collections::{BTreeMap, BTreeSet};
use std::rc::Rc;
use std::sync::Arc;

const REFERENCE_ROOT_PATH: &str = "reference-root";
const FIELD_ROOT_PATH: &str = "field-root";

pub(super) fn open_reference_root(expanded: &mut BTreeSet<String>) {
    expanded.insert(REFERENCE_ROOT_PATH.to_owned());
}

/// Finds the next path in the nearest-GC-root traversal that must be expanded
/// or loaded. Paths use original reference indices so sorting never changes
/// the identity of an already expanded branch.
pub(super) fn nearest_reference_path(
    root: &InstanceDetail,
    details: &BTreeMap<(u64, u64), Arc<InstanceDetail>>,
    expanded: &BTreeSet<String>,
) -> Option<String> {
    fn visit(
        detail: &InstanceDetail,
        parent_path: &str,
        ancestors: &BTreeSet<u64>,
        details: &BTreeMap<(u64, u64), Arc<InstanceDetail>>,
        expanded: &BTreeSet<String>,
        depth: u32,
    ) -> Option<String> {
        let chain: BTreeSet<u64> = detail
            .reference_chain
            .iter()
            .map(|reference| reference.object_id)
            .collect();
        let mut fields: Vec<(usize, &InstanceField)> = detail
            .references
            .iter()
            .enumerate()
            .filter(|(_, field)| reference_visible(detail.depth, field, true, &chain))
            .collect();
        sort_reference_fields(&mut fields);
        for (index, field) in fields {
            let path = format!("{parent_path}/{index}");
            let Some(target) = field.target_object_id.filter(|id| *id != 0) else {
                continue;
            };
            let terminal_root = field.depth == Some(0);
            if terminal_root || ancestors.contains(&target) || depth >= 32 {
                continue;
            }
            if !expanded.contains(&path) {
                return Some(path);
            }
            let key = (target, 0);
            let Some(child) = details.get(&key) else {
                return Some(path);
            };
            let mut next_ancestors = ancestors.clone();
            next_ancestors.insert(target);
            if let Some(next) = visit(child, &path, &next_ancestors, details, expanded, depth + 1) {
                return Some(next);
            }
        }
        None
    }

    if !expanded.contains(REFERENCE_ROOT_PATH) {
        return Some(REFERENCE_ROOT_PATH.to_owned());
    }
    visit(
        root,
        REFERENCE_ROOT_PATH,
        &BTreeSet::from([root.object_id]),
        details,
        expanded,
        1,
    )
}

/// Resolves a path-local reference expansion to its target object id.
pub(super) fn reference_target_for_path(
    root: &InstanceDetail,
    details: &BTreeMap<(u64, u64), Arc<InstanceDetail>>,
    path: &str,
) -> Option<u64> {
    let mut segments = path.strip_prefix("reference-root/")?.split('/');
    let mut detail = root;
    let mut target = None;
    while let Some(segment) = segments.next() {
        let index = segment.parse::<usize>().ok()?;
        let field = detail.references.get(index)?;
        target = field.target_object_id.filter(|id| *id != 0);
        if segments.clone().next().is_some() {
            detail = details.get(&(target?, 0))?;
        }
    }
    target
}

fn sort_reference_fields(fields: &mut [(usize, &InstanceField)]) {
    fields.sort_by(|(_, a), (_, b)| {
        a.depth
            .map(u64::from)
            .cmp(&b.depth.map(u64::from))
            .then_with(|| a.name.cmp(&b.name))
    });
}

/// Compose inspector title/row strip height.
const TITLE_HEIGHT: f32 = 28.;
/// Compose inspector tab strip height.
const TAB_HEIGHT: f32 = 28.;
/// Compose inspector header height (CLASSIFIER_HEADER_HEIGHT).
const TABLE_HEADER_HEIGHT: f32 = widgets::TABLE_HEADER_HEIGHT;
/// Compose inspector body row height (body-compact line + 4 dp).
const TABLE_ROW_HEIGHT: f32 = widgets::TABLE_ROW_HEIGHT;
/// Compose ViewerTypography.bodyCompact font size.
const TABLE_FONT: f32 = widgets::TABLE_FONT;
/// Compose ViewerTypography.bodyCompact line height.
const TABLE_LINE: f32 = widgets::TABLE_LINE;
/// Trailing inset the Compose table keeps for its overlay scrollbars.
/// Overlay horizontal scrollbar thickness reserved at the table bottom.
/// Compose array paging strip height: one compact (20 px) control row.
const ARRAY_PAGING_HEIGHT: f32 = 20.;
/// Compose inspector message row start padding (24 + depth * 12).
const MESSAGE_ROW_INSET: f32 = 24.;
/// Compose inspector tree indent step.
const TREE_INDENT: f32 = 12.;
/// Compose clamps the visible tree indent to 20 steps.
const TREE_INDENT_LIMIT: u32 = 20;
/// Compose inspector row label start padding.
const ROW_PADDING_START: f32 = 8.;
/// Compose inspector row label end padding.
const ROW_PADDING_END: f32 = 4.;
/// Compose inspector row item gap.
const ROW_GAP: f32 = 3.;
/// Compose disclosure triangle slot.
const DISCLOSURE_WIDTH: f32 = widgets::DISCLOSURE_WIDTH;
/// Compose MemoryInspectorColumn.LABEL minimum width.
const LABEL_MIN_WIDTH: f32 = 120.;
/// Compose metric column minimum width.
const METRIC_MIN_WIDTH: f32 = 40.;
/// Compose colors the field string preview with a constant green.
const STRING_PREVIEW_COLOR: u32 = 0x008000;

/// Compose MemoryInspectorColumn, in table order.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum InspectorColumn {
    Label,
    Depth,
    NativeSize,
    ShallowSize,
    RetainedNativeSize,
    RetainedSize,
}

impl InspectorColumn {
    /// Compose enum declaration order, used as the persisted sort key space.
    const ALL: [Self; 6] = [
        Self::Label,
        Self::Depth,
        Self::NativeSize,
        Self::ShallowSize,
        Self::RetainedNativeSize,
        Self::RetainedSize,
    ];

    /// Compact metric-column width for a pane-constrained GPUI inspector. The
    /// Compose preferred widths assume a 900+ px inspector; the label column
    /// absorbs the remaining width through flex_1.
    fn fixed_width(self) -> f32 {
        match self {
            Self::Label => 0.,
            Self::Depth => 72.,
            Self::NativeSize => 68.,
            Self::ShallowSize => 70.,
            Self::RetainedNativeSize => 76.,
            Self::RetainedSize => 74.,
        }
    }

    /// Compose MemoryInspectorColumn.preferredWidth.
    #[allow(dead_code)]
    fn preferred_width(self) -> f32 {
        match self {
            Self::Label => 500.,
            Self::Depth => 150.,
            Self::NativeSize => 110.,
            Self::ShallowSize => 120.,
            Self::RetainedNativeSize => 150.,
            Self::RetainedSize => 130.,
        }
    }

    /// Compose distributes extra width over minimums: 120 for the label, 40 for
    /// every metric column.
    #[allow(dead_code)]
    fn minimum_width(self) -> f32 {
        if self == Self::Label {
            LABEL_MIN_WIDTH
        } else {
            METRIC_MIN_WIDTH
        }
    }

    /// Compose inspectorColumnTitle.
    fn title(self, references: bool, language: Language) -> &'static str {
        let key = match self {
            Self::Label => {
                if references {
                    TextKey::MemoryPageMemoryReferenceColumn
                } else {
                    TextKey::MemoryPageInstance
                }
            }
            Self::Depth => TextKey::MemoryPageDepth,
            Self::NativeSize => TextKey::MemoryPageNativeSize,
            Self::ShallowSize => TextKey::MemoryPageMemoryInspectorShallowSize,
            Self::RetainedNativeSize => TextKey::MemoryPageRetainedNativeSize,
            Self::RetainedSize => TextKey::MemoryPageMemoryInspectorRetainedSize,
        };
        language.text(key)
    }

    /// Compose inspectorMetric: the row value a column renders and sorts on.
    fn metric(self, row: &InspectorRow) -> Option<u64> {
        match self {
            Self::Label => None,
            Self::Depth => row.field_depth.map(u64::from),
            Self::NativeSize => row.native_size,
            Self::ShallowSize => row.shallow_size,
            Self::RetainedNativeSize => row.retained_native_size,
            Self::RetainedSize => row.retained_size,
        }
    }

    /// Compose picks an ascending direction for a newly sorted label/depth
    /// column and a descending one for the size metrics.
    fn default_ascending(self) -> bool {
        matches!(self, Self::Label | Self::Depth)
    }
}

/// One flattened inspector row: the object itself (References tab root), a
/// field, an inbound reference edge, or an inline message.
struct InspectorRow {
    /// Rendered label; the trailing string preview is split into its own run.
    label: String,
    /// Compose colors the field string preview green and semibold.
    string_preview: Option<String>,
    sort_name: String,
    tree_depth: u32,
    field_depth: Option<u32>,
    shallow_size: Option<u64>,
    retained_size: Option<u64>,
    native_size: Option<u64>,
    retained_native_size: Option<u64>,
    value_kind: HeapValueKind,
    /// Object to load when the row is expanded.
    navigate: Option<u64>,
    is_root: bool,
    is_message: bool,
    expandable: bool,
    expanded: bool,
    /// Compose's nearest-root pass selects the first chain row.
    in_reference_chain: bool,
    selected: bool,
    /// Path-local expansion key for the References tree.
    reference_path: Option<String>,
    /// Path-local expansion key for the Fields tree.
    field_path: Option<String>,
    array_paging: Option<FieldArrayPaging>,
}

struct FieldArrayPaging {
    path: String,
    target: u64,
    start: u64,
    page_size: u64,
    total: u64,
    page_len: Option<usize>,
    loading: bool,
}

impl InspectorRow {
    fn message(label: String, tree_depth: u32) -> Self {
        Self::message_with_path(label, tree_depth, String::new())
    }

    fn message_with_path(label: String, tree_depth: u32, path: String) -> Self {
        Self {
            label,
            string_preview: None,
            sort_name: String::new(),
            tree_depth,
            field_depth: None,
            shallow_size: None,
            retained_size: None,
            native_size: None,
            retained_native_size: None,
            value_kind: HeapValueKind::Unknown,
            navigate: None,
            is_root: false,
            is_message: true,
            expandable: false,
            expanded: false,
            in_reference_chain: false,
            selected: false,
            reference_path: (!path.is_empty()).then_some(path),
            field_path: None,
            array_paging: None,
        }
    }

    fn sort_metric(&self, column: InspectorColumn) -> i64 {
        column
            .metric(self)
            .map_or(-1, |value| value.min(i64::MAX as u64) as i64)
    }
}

/// Compose inspector sort defaults: Fields sort by retained size descending,
/// References by depth ascending.
fn default_sort(tab: InspectorTab) -> (InspectorColumn, bool) {
    match tab {
        InspectorTab::Fields => (InspectorColumn::RetainedSize, false),
        InspectorTab::References => (InspectorColumn::Depth, true),
    }
}

/// Decode the persisted sort pair. A zero sort column is the untouched state
/// written by MemoryState::new/reset_for_snapshot, so the tab default applies
/// until the first header click stores a column index + 1.
fn stored_sort(code: u8, ascending: bool, tab: InspectorTab) -> (InspectorColumn, bool) {
    match usize::from(code)
        .checked_sub(1)
        .and_then(|index| InspectorColumn::ALL.get(index))
        .copied()
    {
        Some(column) => (column, ascending),
        None => default_sort(tab),
    }
}

fn sort_state(column: InspectorColumn, ascending: bool) -> (u8, bool) {
    (column as u8 + 1, ascending)
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
        let expanded = self.memory_state.inspector_expanded.clone();
        let field_array_offsets = self.memory_state.inspector_field_array_offsets.clone();
        let reference_expanded = self.memory_state.inspector_reference_expanded.clone();
        let reference_details = self.memory_state.inspector_reference_details.clone();
        let reference_loading = self.memory_state.inspector_reference_loading.clone();
        let (sort_column, sort_ascending) = stored_sort(
            self.memory_state.inspector_sort_column,
            self.memory_state.inspector_sort_ascending,
            self.memory_state.inspector_tab,
        );
        // Compose gates the Native Size column on the snapshot capability and the
        // Retained Native Size column on the selected detail's evidence.
        let columns = Rc::new(inspector_columns(
            snapshot_native_capability(self.memory_state.snapshot.as_deref()),
            detail.retained_native_size.is_some(),
        ));
        let mut rows = build_rows_with_references(
            &detail,
            references_tab,
            nearest_only,
            &expanded,
            &field_array_offsets,
            &reference_expanded,
            &reference_details,
            &reference_loading,
            language,
            sort_column,
            sort_ascending,
        );
        apply_selected_path(
            &mut rows,
            self.memory_state.inspector_selected_path.as_deref(),
        );
        let mut empty_message = SharedString::default();
        if references_tab && rows.len() == 1 && rows.first().is_some_and(|row| row.expanded) {
            let key = if nearest_only && !detail.references.is_empty() {
                TextKey::MemoryPageMemoryRootUnavailable
            } else {
                TextKey::MemoryPageNoReferences
            };
            rows.push(InspectorRow::message(language.text(key).to_owned(), 1));
        } else if !references_tab && rows.is_empty() {
            let key = if detail.is_array && detail.element_count.unwrap_or(0) > 0 {
                TextKey::MemoryPageArrayValuesUnavailable
            } else {
                TextKey::MemoryPageNoObjectFields
            };
            empty_message = SharedString::from(language.text(key).to_owned());
        }
        let rows = Rc::new(rows);

        let mut panel = div()
            .id("memory-object-inspector")
            .size_full()
            .min_h_0()
            .min_w_0()
            .overflow_hidden()
            .flex()
            .flex_col();

        // Title row: Instance Details - shortClass@id (0xhex) with the close
        // control in a 28 px square on the trailing edge.
        let object_title = object_label(
            detail.object_id,
            &detail.class_name,
            detail.represented_class_name.as_deref(),
        );
        let title = match detail.heap_name.as_deref() {
            Some(heap) => format!(
                "{} - {} · {}",
                language.text(TextKey::MemoryPageInstanceDetails),
                object_title,
                heap,
            ),
            None => format!(
                "{} - {}",
                language.text(TextKey::MemoryPageInstanceDetails),
                object_title,
            ),
        };
        panel = panel.child(
            div()
                .id("memory-inspector-title")
                .h(px(TITLE_HEIGHT))
                .flex_shrink_0()
                .flex()
                .items_center()
                .pl(px(4.))
                .child(
                    div()
                        .id("memory-inspector-title-text")
                        .flex_1()
                        .min_w_0()
                        .text_size(px(typography::SUPPORTING))
                        .line_height(px(TABLE_LINE))
                        .font_weight(FontWeight::SEMIBOLD)
                        .text_color(colors.text)
                        .whitespace_nowrap()
                        .text_ellipsis()
                        .child(crate::studio::selectable(title)),
                )
                .child(
                    widgets::icon_button("memory-inspector-close", "×", true, false, colors)
                        .tooltip(language.text(TextKey::MemoryPageMemoryInspectorClose))
                        .on_click(cx.listener(|this, _, _, cx| this.memory_close_inspector(cx))),
                ),
        );
        panel = panel.child(studio::divider(colors));

        // Tabs: Fields / References with a 2 px accent underline spanning the
        // label, both inset 10 dp like Compose.
        let mut tabs = div()
            .id("memory-instance-detail-tabs")
            .h(px(TAB_HEIGHT))
            .flex_shrink_0()
            .flex()
            .items_center();
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
                    .px(px(10.))
                    .cursor_pointer()
                    .on_click(
                        cx.listener(move |this, _, _, cx| this.memory_set_inspector_tab(tab, cx)),
                    )
                    .child(
                        div()
                            .flex_1()
                            .flex()
                            .items_center()
                            .justify_center()
                            .text_size(px(TABLE_FONT))
                            .line_height(px(TABLE_LINE))
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

        // References tab: 28 dp Show nearest GC root only toggle row.
        if references_tab {
            let weak = entity.downgrade();
            let checked = nearest_only;
            panel = panel.child(
                div()
                    .id("memory-show-nearest-gc-root-only")
                    .h(px(TAB_HEIGHT))
                    .flex_shrink_0()
                    .flex()
                    .items_center()
                    .px(px(6.))
                    .cursor_pointer()
                    .on_click(move |_, _, cx| {
                        let _ = weak.update(cx, |this, cx| {
                            this.memory_set_nearest_root_only(!checked, cx)
                        });
                    })
                    .child(widgets::checkbox(
                        "memory-inspector-nearest-root-only",
                        language.text(TextKey::MemoryPageShowNearestGcRootOnly),
                        checked,
                        true,
                        colors,
                    )),
            );
        }

        // Array paging: bounded 200-element pages, rendered as one compact row.
        if detail.is_array && !references_tab {
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
            let object_id = detail.object_id;
            panel = panel.child(
                div()
                    .id("memory-inspector-array-paging")
                    .h(px(ARRAY_PAGING_HEIGHT))
                    .flex_shrink_0()
                    .flex()
                    .items_center()
                    .gap(px(6.))
                    .px(px(6.))
                    .overflow_x_scrollbar()
                    .child(
                        studio::supporting(
                            language.format(
                                TextKey::MemoryPageArrayElements,
                                &[("arg_1", &element_count)],
                            ),
                            colors,
                        )
                        .whitespace_nowrap(),
                    )
                    .child(
                        studio::supporting(
                            language.format(
                                TextKey::MemoryPageArrayPageRange,
                                &[
                                    ("arg_1", &first),
                                    ("arg_2", &end),
                                    ("arg_3", &element_count),
                                ],
                            ),
                            colors,
                        )
                        .whitespace_nowrap(),
                    )
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

        // The table measures its pane so the Compose width formula
        // (minimum + proportional extra) and the horizontal-scroll decision can
        // be applied exactly.
        let table_rows = rows;
        let table_columns = columns;
        let table_entity = entity.clone();
        let table_colors = *colors;
        let table_scroll = self.memory_state.inspector_scroll.clone();
        let table_references = references_tab;
        let table_empty = empty_message;
        panel = panel.child(div().flex_1().min_h_0().w_full().child(inspector_table(
            &table_rows,
            &table_columns,
            table_references,
            (sort_column, sort_ascending),
            &table_entity,
            &table_colors,
            &table_scroll,
            language,
            table_empty.clone(),
            &self.memory_state.inspector_column_widths,
            cx,
        )));

        panel.into_any_element()
    }

    /// Header click: toggle the active column or adopt Compose's default
    /// direction for the new column.
    fn memory_sort_inspector(&mut self, column: InspectorColumn, cx: &mut Context<Self>) {
        let state = &mut self.memory_state;
        let (current, ascending) = stored_sort(
            state.inspector_sort_column,
            state.inspector_sort_ascending,
            state.inspector_tab,
        );
        let next_ascending = if current == column {
            !ascending
        } else {
            column.default_ascending()
        };
        let (code, ascending) = sort_state(column, next_ascending);
        state.inspector_sort_column = code;
        state.inspector_sort_ascending = ascending;
        cx.notify();
    }
}

/// Render the measured inspector table: 24 px toolbar header, 20 px rows, the
/// Compose column widths and overlay scrollbars.
#[allow(clippy::too_many_arguments)]
fn inspector_table(
    rows: &Rc<Vec<InspectorRow>>,
    columns: &Rc<Vec<InspectorColumn>>,
    references: bool,
    sort: (InspectorColumn, bool),
    entity: &Entity<ImportWorkspace>,
    colors: &StudioColors,
    scroll_handle: &UniformListScrollHandle,
    language: Language,
    empty_message: SharedString,
    widths: &[f32; 6],
    cx: &mut Context<ImportWorkspace>,
) -> AnyElement {
    // The inspector pane is constrained to roughly 260-620 px in Compose; the
    // label column absorbs all extra width while metric columns stay compact.
    // Avoiding a container query here also prevents the panel from measuring
    // the window instead of its own pane in GPUI.
    let (sort_column, sort_ascending) = sort;
    let mut header = div()
        .id("memory-inspector-columns")
        .h(px(TABLE_HEADER_HEIGHT))
        .flex_shrink_0()
        .w_full()
        .flex()
        .items_center()
        .bg(colors.toolbar);
    for column in columns.iter() {
        let active = *column == sort_column;
        let title = if active {
            format!(
                "{}{}",
                column.title(references, language),
                if sort_ascending { " ↑" } else { " ↓" }
            )
        } else {
            column.title(references, language).to_owned()
        };
        let metric = *column != InspectorColumn::Label;
        let sort_entity = entity.clone();
        let sort_column = *column;
        let column_index = InspectorColumn::ALL
            .iter()
            .position(|candidate| candidate == column)
            .unwrap_or(0);
        header = header.child(
            widgets::resizable_header(
                widgets::header_cell(
                    metric.then_some(widths[column_index]),
                    title,
                    metric,
                    metric,
                    colors,
                ),
                MemoryTable::Inspector,
                column_index,
                widths[column_index],
                cx,
            )
            .text_size(px(typography::SUPPORTING))
            .id(("memory-inspector-header", sort_column as usize))
            .cursor_pointer()
            .on_click(move |_, _, cx| {
                let _ =
                    sort_entity.update(cx, |this, cx| this.memory_sort_inspector(sort_column, cx));
            }),
        );
    }

    let body: AnyElement = if rows.is_empty() {
        div()
            .flex_1()
            .min_h_0()
            .w_full()
            .flex()
            .items_center()
            .justify_center()
            .p(px(12.))
            .child(studio::supporting(empty_message, colors))
            .into_any_element()
    } else {
        let list_rows = rows.clone();
        let list_columns = columns.clone();
        let list_entity = entity.clone();
        let list_colors = *colors;
        let list_widths = *widths;
        let list = uniform_list("memory-inspector-rows", rows.len(), move |range, _, _| {
            let mut items = Vec::with_capacity(range.len());
            for index in range {
                let Some(row) = list_rows.get(index) else {
                    continue;
                };
                items.push(inspector_row(
                    index,
                    row,
                    &list_columns,
                    &list_entity,
                    &list_colors,
                    language,
                    &list_widths,
                ));
            }
            items
        })
        .track_scroll(scroll_handle)
        .h_full()
        .w_full();
        div()
            .relative()
            .flex_1()
            .min_h_0()
            .w_full()
            .child(list)
            .into_any_element()
    };

    let table = div()
        .id("memory-inspector-table")
        .w_full()
        .h_full()
        .flex_shrink_0()
        .flex()
        .flex_col()
        .child(header)
        .child(body);

    div()
        .relative()
        .size_full()
        .child(
            div()
                .id("memory-inspector-table-scroll")
                .size_full()
                .overflow_x_scrollbar()
                .child(table),
        )
        .child(
            div()
                .absolute()
                .top(px(TABLE_HEADER_HEIGHT))
                .right_0()
                .bottom(px(0.))
                .left_0()
                .child(
                    Scrollbar::new(scroll_handle)
                        .id("memory-inspector-vertical-scrollbar")
                        .axis(ScrollbarAxis::Vertical)
                        .viewport_from_layout(),
                ),
        )
        .into_any_element()
}

/// One inspector body row: the label cell (disclosure, type badge, Compose
/// label with the string preview run) followed by the metric cells.
fn inspector_row(
    index: usize,
    row: &InspectorRow,
    columns: &[InspectorColumn],
    entity: &Entity<ImportWorkspace>,
    colors: &StudioColors,
    language: Language,
    widths: &[f32; 6],
) -> AnyElement {
    if let Some(paging) = &row.array_paging {
        return field_array_paging_row(index, row.tree_depth, paging, entity, colors, language);
    }
    if row.is_message {
        let indent = MESSAGE_ROW_INSET + row.tree_depth.min(TREE_INDENT_LIMIT) as f32 * TREE_INDENT;
        return div()
            .id(("memory-inspector-row", index))
            .h(px(TABLE_ROW_HEIGHT))
            .w_full()
            .flex_shrink_0()
            .flex()
            .items_center()
            .pl(px(indent))
            .pr(px(ROW_PADDING_END))
            .text_size(px(TABLE_FONT))
            .line_height(px(TABLE_LINE))
            .text_color(colors.secondary_text)
            .whitespace_nowrap()
            .text_ellipsis()
            .child(row.label.clone())
            .into_any_element();
    }

    let mut element = div()
        .id(("memory-inspector-row", index))
        .h(px(TABLE_ROW_HEIGHT))
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .bg(if row.selected {
            colors.selected_row
        } else {
            colors.panel
        })
        .cursor_pointer();
    element = element.child(inspector_label_cell(index, row, entity, colors));
    for column in columns.iter().skip(1) {
        let column_index = InspectorColumn::ALL
            .iter()
            .position(|candidate| candidate == column)
            .unwrap_or(0);
        element = element.child(widgets::metric_cell(
            Some(widths[column_index]),
            metric_text(column.metric(row)),
            colors,
        ));
    }

    let selection_path = row
        .field_path
        .clone()
        .or_else(|| row.reference_path.clone());
    let row_entity = entity.clone();
    element
        .on_mouse_down(MouseButton::Left, move |_, _, cx| {
            cx.stop_propagation();
            if let Some(path) = selection_path.clone() {
                let _ = row_entity.update(cx, |this, cx| {
                    this.memory_select_inspector_row(path, cx);
                });
            }
        })
        .into_any_element()
}

fn field_array_paging_row(
    index: usize,
    depth: u32,
    paging: &FieldArrayPaging,
    entity: &Entity<ImportWorkspace>,
    colors: &StudioColors,
    language: Language,
) -> AnyElement {
    let label = field_array_page_label(paging, language);
    let previous = paging.start.saturating_sub(paging.page_size);
    let next = paging.start.saturating_add(paging.page_size);
    let can_previous = paging.start > 0 && !paging.loading;
    let can_next = paging.page_len.is_some() && next < paging.total && !paging.loading;
    let target = paging.target;
    let previous_path = paging.path.clone();
    let next_path = paging.path.clone();
    let previous_entity = entity.clone();
    let next_entity = entity.clone();
    div()
        .id(("memory-field-array-page", index))
        .h(px(TABLE_ROW_HEIGHT))
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .pl(px(
            ROW_PADDING_START + depth.min(TREE_INDENT_LIMIT) as f32 * TREE_INDENT
        ))
        .child(
            div()
                .flex_1()
                .min_w_0()
                .text_size(px(TABLE_FONT))
                .text_color(colors.secondary_text)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(label),
        )
        .child(
            widgets::icon_button(
                ("memory-field-array-previous", index),
                "‹",
                can_previous,
                false,
                colors,
            )
            .h(px(TABLE_ROW_HEIGHT))
            .w(px(TABLE_ROW_HEIGHT))
            .tooltip(language.text(TextKey::MemoryPagePreviousArrayPage))
            .on_click(move |_, _, cx| {
                cx.stop_propagation();
                if can_previous {
                    let _ = previous_entity.update(cx, |this, cx| {
                        this.memory_page_field_array(previous_path.clone(), target, previous, cx);
                    });
                }
            }),
        )
        .child(
            widgets::icon_button(
                ("memory-field-array-next", index),
                "›",
                can_next,
                false,
                colors,
            )
            .h(px(TABLE_ROW_HEIGHT))
            .w(px(TABLE_ROW_HEIGHT))
            .tooltip(language.text(TextKey::MemoryPageNextArrayPage))
            .on_click(move |_, _, cx| {
                cx.stop_propagation();
                if can_next {
                    let _ = next_entity.update(cx, |this, cx| {
                        this.memory_page_field_array(next_path.clone(), target, next, cx);
                    });
                }
            }),
        )
        .into_any_element()
}

fn field_array_page_label(paging: &FieldArrayPaging, language: Language) -> String {
    match paging.page_len {
        Some(len) => {
            let end = paging.start.saturating_add(len as u64).min(paging.total);
            let first = if end > paging.start {
                paging.start.saturating_add(1)
            } else {
                paging.start
            };
            language.format(
                TextKey::MemoryPageArrayPageRange,
                &[("arg_1", &first), ("arg_2", &end), ("arg_3", &paging.total)],
            )
        }
        None => language
            .text(if paging.loading {
                TextKey::MemoryPageMemoryInspectorLoading
            } else {
                TextKey::MemoryPageUnavailable
            })
            .to_owned(),
    }
}

/// Inspector label cell: tree indent, shared disclosure, type badge and
/// the label run (with the green string-preview suffix on Fields).
fn inspector_label_cell(
    index: usize,
    row: &InspectorRow,
    entity: &Entity<ImportWorkspace>,
    colors: &StudioColors,
) -> AnyElement {
    let indent = ROW_PADDING_START + row.tree_depth.min(TREE_INDENT_LIMIT) as f32 * TREE_INDENT;
    let mut cell = div()
        .flex_1()
        .min_w_0()
        .h_full()
        .flex()
        .items_center()
        .gap(px(ROW_GAP))
        .pl(px(indent))
        .pr(px(ROW_PADDING_END));
    if row.expandable {
        let navigate = row.navigate;
        let reference_path = row.reference_path.clone();
        let field_path = row.field_path.clone();
        let disclosure_entity = entity.clone();
        cell = cell.child(
            widgets::disclosure_button(
                ("memory-inspector-disclosure", index),
                row.expanded,
                colors.secondary_text,
            )
            .on_click(move |_, _, cx| {
                cx.stop_propagation();
                let _ = disclosure_entity.update(cx, |this, cx| {
                    if let Some(path) = reference_path.clone() {
                        this.memory_toggle_reference(path, navigate, cx);
                        return;
                    }
                    if let Some(path) = field_path.clone() {
                        this.memory_toggle_field(path, navigate, cx);
                    }
                });
            }),
        );
    } else {
        cell = cell.child(div().w(px(DISCLOSURE_WIDTH)).h_full().flex_shrink_0());
    }
    cell = cell.child(widgets::type_badge(row.value_kind, row.is_root, colors));
    let mut label = div()
        .flex_1()
        .min_w_0()
        .flex()
        .items_center()
        .text_size(px(TABLE_FONT))
        .line_height(px(TABLE_LINE));
    label = label.child(
        div()
            .flex_1()
            .min_w_0()
            .text_color(colors.text)
            .whitespace_nowrap()
            .text_ellipsis()
            .child(row.label.clone()),
    );
    if let Some(preview) = row.string_preview.clone() {
        label = label.child(
            div()
                .flex_shrink_0()
                .font_weight(FontWeight::BOLD)
                .text_color(rgb(STRING_PREVIEW_COLOR))
                .child(preview),
        );
    }
    cell.child(label).into_any_element()
}

fn inspector_placeholder(language: Language, colors: &StudioColors, loading: bool) -> AnyElement {
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
                .pl(px(4.))
                .child(
                    div()
                        .text_size(px(typography::SUPPORTING))
                        .line_height(px(TABLE_LINE))
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

/// Compose inspectorColumns: the native column follows the snapshot
/// capability; the retained-native column follows the selected detail evidence.
fn inspector_columns(
    native_capability: bool,
    retained_native_evidence: bool,
) -> Vec<InspectorColumn> {
    let mut columns = vec![InspectorColumn::Label, InspectorColumn::Depth];
    if native_capability {
        columns.push(InspectorColumn::NativeSize);
    }
    columns.push(InspectorColumn::ShallowSize);
    if retained_native_evidence {
        columns.push(InspectorColumn::RetainedNativeSize);
    }
    columns.push(InspectorColumn::RetainedSize);
    columns
}

/// Native columns require actual registered-native evidence in the snapshot.
fn snapshot_native_capability(snapshot: Option<&MemorySnapshot>) -> bool {
    let Some(snapshot) = snapshot else {
        return false;
    };
    snapshot
        .classes
        .iter()
        .any(|class| class.native_size.is_some())
}

/// Flattened rows for the active tab: the References root row is followed by
/// the inbound edges when expanded; the Fields tab never shows a root row.
fn build_rows(
    detail: &InstanceDetail,
    references_tab: bool,
    nearest_only: bool,
    expanded: &BTreeSet<String>,
    sort_column: InspectorColumn,
    sort_ascending: bool,
) -> Vec<InspectorRow> {
    let mut reference_expanded = BTreeSet::new();
    if !expanded.contains(REFERENCE_ROOT_PATH) {
        reference_expanded.insert(REFERENCE_ROOT_PATH.to_owned());
    }
    build_rows_with_references(
        detail,
        references_tab,
        nearest_only,
        expanded,
        &BTreeMap::new(),
        &reference_expanded,
        &BTreeMap::new(),
        &BTreeSet::new(),
        Language::English,
        sort_column,
        sort_ascending,
    )
}

fn build_rows_with_references(
    detail: &InstanceDetail,
    references_tab: bool,
    nearest_only: bool,
    expanded: &BTreeSet<String>,
    field_array_offsets: &BTreeMap<String, u64>,
    reference_expanded: &BTreeSet<String>,
    reference_details: &BTreeMap<(u64, u64), Arc<InstanceDetail>>,
    reference_loading: &BTreeSet<(u64, u64)>,
    language: Language,
    sort_column: InspectorColumn,
    sort_ascending: bool,
) -> Vec<InspectorRow> {
    let mut rows = Vec::new();
    if references_tab {
        let root_expanded = reference_expanded.contains(REFERENCE_ROOT_PATH);
        rows.push(InspectorRow {
            label: object_label(
                detail.object_id,
                &detail.class_name,
                detail.represented_class_name.as_deref(),
            ),
            string_preview: None,
            sort_name: detail.class_name.clone(),
            tree_depth: 0,
            field_depth: detail.depth,
            shallow_size: detail.shallow_size_known.then_some(detail.shallow_size),
            retained_size: detail.retained_size,
            native_size: detail.native_size,
            retained_native_size: detail.retained_native_size,
            value_kind: detail.value_kind,
            navigate: None,
            is_root: true,
            is_message: false,
            expandable: !detail.references.is_empty(),
            expanded: root_expanded,
            in_reference_chain: false,
            selected: false,
            reference_path: Some(REFERENCE_ROOT_PATH.to_owned()),
            field_path: None,
            array_paging: None,
        });
        if root_expanded {
            append_reference_rows(
                detail,
                REFERENCE_ROOT_PATH,
                1,
                &BTreeSet::from([detail.object_id]),
                nearest_only,
                reference_expanded,
                reference_details,
                reference_loading,
                language,
                sort_column,
                sort_ascending,
                &mut rows,
            );
            append_gc_root_path_rows(detail, language, &mut rows);
            if nearest_only {
                let has_chain = rows[1..].iter().any(|row| row.in_reference_chain);
                if has_chain {
                    if let Some(selected) = rows[1..].iter_mut().find(|row| row.in_reference_chain)
                    {
                        selected.selected = true;
                    }
                } else if rows.len() == 1 {
                    rows[0].selected = true;
                }
            }
        }
    } else {
        append_field_rows(
            detail,
            FIELD_ROOT_PATH,
            0,
            &BTreeSet::from([detail.object_id]),
            expanded,
            field_array_offsets,
            reference_details,
            reference_loading,
            language,
            sort_column,
            sort_ascending,
            &mut rows,
        );
    }
    rows
}

fn append_field_rows(
    detail: &InstanceDetail,
    parent_path: &str,
    tree_depth: u32,
    ancestors: &BTreeSet<u64>,
    expanded: &BTreeSet<String>,
    array_offsets: &BTreeMap<String, u64>,
    details: &BTreeMap<(u64, u64), Arc<InstanceDetail>>,
    loading: &BTreeSet<(u64, u64)>,
    language: Language,
    sort_column: InspectorColumn,
    sort_ascending: bool,
    rows: &mut Vec<InspectorRow>,
) {
    let mut children: Vec<_> = detail
        .fields
        .iter()
        .enumerate()
        .map(|(index, field)| {
            let index = if detail.is_array {
                detail.array_start.saturating_add(index as u64)
            } else {
                index as u64
            };
            let path = format!("{parent_path}/{index}");
            field_row(field, false, tree_depth, expanded, None, Some(path))
        })
        .collect();
    sort_rows(&mut children, sort_column, sort_ascending);

    for child in children {
        let path = child.field_path.clone();
        let target = child.navigate;
        let is_expanded = child.expanded;
        rows.push(child);
        let (Some(path), Some(target)) = (path, target) else {
            continue;
        };
        if !is_expanded {
            continue;
        }
        let start = array_offsets.get(&path).copied().unwrap_or(0);
        let key = (target, start);
        let page = details.get(&key);
        let metadata = details.get(&(target, 0)).or(page);
        let is_loading = loading.contains(&key);
        let message = if ancestors.contains(&target) {
            Some(TextKey::MemoryPageMemoryInspectorCycle)
        } else if tree_depth >= 31 {
            Some(TextKey::MemoryPageMemoryInspectorDepthLimit)
        } else {
            None
        };
        if let Some(message) = message {
            rows.push(InspectorRow::message_with_path(
                language.text(message).to_owned(),
                tree_depth + 1,
                path,
            ));
            continue;
        }
        if let Some(metadata) = metadata.filter(|detail| detail.is_array) {
            let mut row = InspectorRow::message(String::new(), tree_depth + 1);
            row.array_paging = Some(FieldArrayPaging {
                path: path.clone(),
                target,
                start,
                page_size: match metadata.array_page_size {
                    0 => MEMORY_ARRAY_PAGE as u64,
                    size => size,
                },
                total: metadata.element_count.unwrap_or(0),
                page_len: page
                    .filter(|_| !is_loading)
                    .map(|detail| detail.fields.len()),
                loading: is_loading,
            });
            rows.push(row);
        }
        if is_loading || page.is_none() {
            if metadata.is_none_or(|detail| !detail.is_array) {
                rows.push(InspectorRow::message_with_path(
                    language
                        .text(if is_loading {
                            TextKey::MemoryPageMemoryInspectorLoading
                        } else {
                            TextKey::MemoryPageUnavailable
                        })
                        .to_owned(),
                    tree_depth + 1,
                    path,
                ));
            }
        } else if let Some(child_detail) = page {
            let mut next_ancestors = ancestors.clone();
            next_ancestors.insert(target);
            append_field_rows(
                child_detail,
                &path,
                tree_depth + 1,
                &next_ancestors,
                expanded,
                array_offsets,
                details,
                loading,
                language,
                sort_column,
                sort_ascending,
                rows,
            );
        }
    }
}

fn append_gc_root_path_rows(
    detail: &InstanceDetail,
    language: Language,
    rows: &mut Vec<InspectorRow>,
) {
    if detail.reference_chain.is_empty() {
        return;
    }
    let mut path = String::from("GC Root");
    for edge in &detail.reference_chain {
        if let Some(root_kind) = edge.root_kind.as_deref() {
            path.push_str(" [");
            path.push_str(root_kind);
            path.push_str("] ");
        } else if let Some(field_name) = edge.field_name.as_deref() {
            path.push_str(" --");
            path.push_str(field_name);
            path.push_str("--> ");
        } else {
            path.push_str(" -> ");
        }
        path.push_str(&format!("{} (0x{:x})", edge.class_name, edge.object_id));
    }
    rows.push(InspectorRow::message_with_path(
        format!("{}: {path}", language.text(TextKey::MemoryDiffGcRootPath)),
        1,
        "gc-root-path".to_owned(),
    ));
}

fn append_reference_rows(
    detail: &InstanceDetail,
    parent_path: &str,
    tree_depth: u32,
    ancestors: &BTreeSet<u64>,
    nearest_only: bool,
    reference_expanded: &BTreeSet<String>,
    reference_details: &BTreeMap<(u64, u64), Arc<InstanceDetail>>,
    reference_loading: &BTreeSet<(u64, u64)>,
    language: Language,
    sort_column: InspectorColumn,
    sort_ascending: bool,
    rows: &mut Vec<InspectorRow>,
) {
    let chain: BTreeSet<u64> = detail
        .reference_chain
        .iter()
        .map(|reference| reference.object_id)
        .collect();
    let mut children: Vec<InspectorRow> = detail
        .references
        .iter()
        .enumerate()
        .filter(|(_, field)| reference_visible(detail.depth, field, nearest_only, &chain))
        .map(|(index, field)| {
            let path = format!("{parent_path}/{index}");
            field_row(
                field,
                true,
                tree_depth,
                &BTreeSet::new(),
                Some(&chain),
                Some(path),
            )
        })
        .collect();
    sort_rows(&mut children, sort_column, sort_ascending);

    for child in children {
        let child_path = child.reference_path.clone();
        let target = child.navigate;
        let should_expand = child
            .reference_path
            .as_ref()
            .is_some_and(|path| reference_expanded.contains(path));
        let mut child = child;
        child.expanded = should_expand;
        rows.push(child);
        if !should_expand {
            continue;
        }
        let Some(child_path) = child_path else {
            continue;
        };
        let Some(target) = target else {
            continue;
        };
        if ancestors.contains(&target) {
            rows.push(InspectorRow::message_with_path(
                language
                    .text(TextKey::MemoryPageMemoryInspectorCycle)
                    .to_owned(),
                tree_depth + 1,
                child_path,
            ));
            continue;
        }
        if tree_depth >= 32 {
            rows.push(InspectorRow::message_with_path(
                language
                    .text(TextKey::MemoryPageMemoryInspectorDepthLimit)
                    .to_owned(),
                tree_depth + 1,
                child_path,
            ));
            continue;
        }
        let key = (target, 0);
        if reference_loading.contains(&key) {
            rows.push(InspectorRow::message_with_path(
                language
                    .text(TextKey::MemoryPageMemoryInspectorLoading)
                    .to_owned(),
                tree_depth + 1,
                child_path,
            ));
        } else if let Some(detail) = reference_details.get(&key) {
            let mut next_ancestors = ancestors.clone();
            next_ancestors.insert(target);
            append_reference_rows(
                detail,
                &child_path,
                tree_depth + 1,
                &next_ancestors,
                nearest_only,
                reference_expanded,
                reference_details,
                reference_loading,
                language,
                sort_column,
                sort_ascending,
                rows,
            );
        } else {
            rows.push(InspectorRow::message_with_path(
                language
                    .text(TextKey::MemoryPageMemoryInspectorLoading)
                    .to_owned(),
                tree_depth + 1,
                child_path,
            ));
        }
    }
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
    expanded: &BTreeSet<String>,
    chain: Option<&BTreeSet<u64>>,
    path: Option<String>,
) -> InspectorRow {
    let target = field.target_object_id.filter(|id| *id != 0);
    let (label, string_preview) = if references {
        (reference_label(field), None)
    } else {
        split_field_label(field)
    };
    InspectorRow {
        label,
        string_preview,
        sort_name: field.name.clone(),
        tree_depth,
        field_depth: field.depth,
        shallow_size: field.shallow_size,
        retained_size: field.retained_size,
        native_size: field.native_size,
        retained_native_size: field.retained_native_size,
        value_kind: field.value_kind,
        navigate: target,
        is_root: false,
        is_message: false,
        expandable: target.is_some(),
        expanded: !references
            && target.is_some()
            && path.as_ref().is_some_and(|path| expanded.contains(path)),
        in_reference_chain: references
            && target.is_some_and(|target| chain.is_some_and(|chain| chain.contains(&target))),
        selected: false,
        reference_path: references.then(|| path.clone()).flatten(),
        field_path: (!references).then_some(path).flatten(),
        array_paging: None,
    }
}

fn sort_rows(rows: &mut [InspectorRow], column: InspectorColumn, ascending: bool) {
    rows.sort_by(|a, b| {
        let ordering = if column == InspectorColumn::Label {
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

fn apply_selected_path(rows: &mut [InspectorRow], selected: Option<&str>) {
    let Some(selected) = selected else {
        return;
    };
    for row in rows {
        row.selected = !row.is_message
            && row.array_paging.is_none()
            && (row.field_path.as_deref() == Some(selected)
                || row.reference_path.as_deref() == Some(selected));
    }
}

/// Compose inspectorObjectLabel: represented classes render as
/// Short.class@id (0xhex), everything else as Short@id (0xhex).
fn object_label(object_id: u64, class_name: &str, represented_class_name: Option<&str>) -> String {
    let name = match represented_class_name {
        Some(represented) => format!("{}.class", short_class_name(represented)),
        None => short_class_name(class_name).to_owned(),
    };
    format!("{name}@{object_id} (0x{object_id:x})")
}

fn short_class_name(class_name: &str) -> &str {
    class_name.rsplit('.').next().unwrap_or(class_name)
}

/// Compose inspectorFieldLabel plus the split of its trailing string preview so
/// the row can color that run independently.
fn split_field_label(field: &InstanceField) -> (String, Option<String>) {
    let label = field_label(field);
    match field.string_preview.as_deref() {
        Some(preview) if !preview.is_empty() && label.ends_with(preview) => {
            let split = label.len() - preview.len();
            (label[..split].to_owned(), Some(preview.to_owned()))
        }
        _ => (label, None),
    }
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
    value.map_or_else(|| "-".to_owned(), grouped_integer)
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
    use aps_memory::{ClassStats, SnapshotMetadata};

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
    fn object_labels_use_short_class_and_lowercase_hex() {
        assert_eq!(object_label(42, "sample.Target", None), "Target@42 (0x2a)");
        assert_eq!(
            object_label(43, "sample.Target", Some("com.example.Carrier")),
            "Carrier.class@43 (0x2b)"
        );
        assert_eq!(object_label(255, "a.b.C", None), "C@255 (0xff)");
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
        assert_eq!(
            split_field_label(&object),
            ("child = {Child} ".to_owned(), Some("hello".to_owned()))
        );

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

        let represented = InstanceField {
            name: "target42".into(),
            target_object_id: Some(4),
            target_class_name: Some("sample.Root".into()),
            represented_class_name: Some("com.example.Carrier".into()),
            ..InstanceField::default()
        };
        assert_eq!(
            reference_label(&represented),
            "target42 in Carrier.class@4 (0x4)"
        );

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
        let ascending = build_rows(
            &detail,
            false,
            false,
            &expanded,
            InspectorColumn::ShallowSize,
            true,
        );
        assert_eq!(
            ascending
                .iter()
                .map(|row| row.sort_name.as_str())
                .collect::<Vec<_>>(),
            ["b", "c", "a"]
        );
        let descending = build_rows(
            &detail,
            false,
            false,
            &expanded,
            InspectorColumn::ShallowSize,
            false,
        );
        assert_eq!(
            descending
                .iter()
                .map(|row| row.sort_name.as_str())
                .collect::<Vec<_>>(),
            ["a", "c", "b"]
        );
        let by_name = build_rows(
            &detail,
            false,
            false,
            &expanded,
            InspectorColumn::Label,
            true,
        );
        assert_eq!(
            by_name
                .iter()
                .map(|row| row.sort_name.as_str())
                .collect::<Vec<_>>(),
            ["a", "b", "c"]
        );
    }

    #[::core::prelude::v1::test]
    fn fields_tab_has_no_root_row_and_references_root_expands() {
        let detail = InstanceDetail {
            object_id: 42,
            class_name: "sample.Target".into(),
            reference_chain: vec![aps_memory::ObjectReference {
                field_name: Some("root".into()),
                root_kind: None,
                object_id: 4,
                class_name: "sample.Root".into(),
            }],
            fields: vec![InstanceField {
                name: "child42".into(),
                target_object_id: Some(7),
                target_class_name: Some("sample.Child".into()),
                ..InstanceField::default()
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
        let fields = build_rows(
            &detail,
            false,
            false,
            &expanded,
            InspectorColumn::RetainedSize,
            false,
        );
        assert_eq!(fields.len(), 1);
        assert!(!fields[0].is_root);

        let rows = build_rows(
            &detail,
            true,
            false,
            &expanded,
            InspectorColumn::Depth,
            true,
        );
        assert_eq!(rows.len(), 3);
        assert!(rows[0].is_root);
        assert_eq!(rows[0].label, "Target@42 (0x2a)");
        assert_eq!(rows[1].tree_depth, 1);
        assert!(rows[2].is_message);
        assert!(rows[2].label.contains("GC Root path"));

        let mut collapsed = BTreeSet::new();
        collapsed.insert(REFERENCE_ROOT_PATH.to_owned());
        let rows = build_rows(
            &detail,
            true,
            false,
            &collapsed,
            InspectorColumn::Depth,
            true,
        );
        assert_eq!(rows.len(), 1);
        assert!(!rows[0].expanded);
    }

    fn field_tree_rows(
        root: &InstanceDetail,
        expanded: &BTreeSet<String>,
        details: &BTreeMap<(u64, u64), Arc<InstanceDetail>>,
        loading: &BTreeSet<(u64, u64)>,
    ) -> Vec<InspectorRow> {
        build_rows_with_references(
            root,
            false,
            false,
            expanded,
            &BTreeMap::new(),
            &BTreeSet::new(),
            details,
            loading,
            Language::English,
            InspectorColumn::Label,
            true,
        )
    }

    fn object_field(name: &str, target: u64) -> InstanceField {
        InstanceField {
            name: name.into(),
            target_object_id: Some(target),
            ..InstanceField::default()
        }
    }

    #[::core::prelude::v1::test]
    fn field_rows_only_expose_non_null_objects_as_expandable() {
        let root = InstanceDetail {
            object_id: 1,
            fields: vec![
                InstanceField {
                    name: "primitive".into(),
                    value_kind: HeapValueKind::Primitive,
                    ..InstanceField::default()
                },
                object_field("null", 0),
                object_field("object", 2),
            ],
            ..InstanceDetail::default()
        };
        let rows = field_tree_rows(&root, &BTreeSet::new(), &BTreeMap::new(), &BTreeSet::new());
        for row in &rows {
            assert!(row.field_path.is_some());
            match row.sort_name.as_str() {
                "primitive" | "null" => {
                    assert_eq!(row.navigate, None);
                    assert!(!row.expandable);
                }
                "object" => {
                    assert_eq!(row.navigate, Some(2));
                    assert!(row.expandable);
                }
                _ => panic!("unexpected field"),
            }
        }
        assert_eq!(rows.len(), 3);
    }

    #[::core::prelude::v1::test]
    fn selecting_a_row_preserves_the_current_object_and_tree() {
        let mut state = super::super::state::MemoryState::new();
        state.selected_object = Some(1);
        state.detail = Some(Arc::new(InstanceDetail {
            object_id: 1,
            ..InstanceDetail::default()
        }));
        state.instances = Some(Arc::new(aps_memory::InstancesPage::default()));
        state.history = vec![1];
        state.inspector_expanded.insert("field-root/0".into());
        state
            .inspector_reference_expanded
            .insert("reference-root/0".into());
        state.inspector_nearest_auto_expand = true;
        state.select_inspector_row("field-root/1".into());
        assert_eq!(
            state.inspector_selected_path.as_deref(),
            Some("field-root/1")
        );
        assert!(!state.inspector_nearest_auto_expand);
        assert_eq!(state.selected_object, Some(1));
        assert_eq!(state.detail.as_ref().unwrap().object_id, 1);
        assert!(state.instances.is_some());
        assert_eq!(state.history, [1]);
        assert!(state.inspector_expanded.contains("field-root/0"));
        assert!(
            state
                .inspector_reference_expanded
                .contains("reference-root/0")
        );
    }

    #[::core::prelude::v1::test]
    fn selected_path_highlights_only_the_matching_visible_row() {
        let detail = InstanceDetail {
            object_id: 42,
            fields: vec![object_field("object", 7), field("number", Some(4), None)],
            references: vec![
                InstanceField {
                    depth: Some(0),
                    ..object_field("parent", 7)
                },
                InstanceField {
                    depth: Some(0),
                    ..object_field("other", 8)
                },
            ],
            reference_chain: vec![aps_memory::ObjectReference {
                field_name: None,
                root_kind: None,
                object_id: 7,
                class_name: "Root".into(),
            }],
            depth: Some(1),
            ..InstanceDetail::default()
        };
        let mut fields = field_tree_rows(
            &detail,
            &BTreeSet::new(),
            &BTreeMap::new(),
            &BTreeSet::new(),
        );
        apply_selected_path(&mut fields, Some("field-root/1"));
        assert_eq!(
            fields
                .iter()
                .filter(|row| row.selected)
                .map(|row| row.sort_name.as_str())
                .collect::<Vec<_>>(),
            ["number"]
        );
        assert!(fields.iter().all(|row| !row.expanded));

        let mut references = build_rows(
            &detail,
            true,
            true,
            &BTreeSet::new(),
            InspectorColumn::Depth,
            true,
        );
        assert!(references.iter().any(|row| row.selected));
        apply_selected_path(&mut references, Some("reference-root/1"));
        assert_eq!(
            references
                .iter()
                .filter(|row| row.selected)
                .map(|row| row.reference_path.as_deref())
                .collect::<Vec<_>>(),
            [Some("reference-root/1")]
        );
        apply_selected_path(&mut references, Some("reference-root/99"));
        assert!(references.iter().all(|row| !row.selected));
    }

    #[::core::prelude::v1::test]
    fn nested_array_pages_are_path_local_and_use_absolute_indices() {
        let root = InstanceDetail {
            object_id: 1,
            fields: vec![object_field("a", 2), object_field("b", 2)],
            ..InstanceDetail::default()
        };
        let page = |start, name: &str| {
            Arc::new(InstanceDetail {
                object_id: 2,
                is_array: true,
                element_count: Some(401),
                array_start: start,
                array_page_size: 200,
                fields: vec![object_field(name, 3), field("other", None, None)],
                ..InstanceDetail::default()
            })
        };
        let details = BTreeMap::from([((2, 0), page(0, "z")), ((2, 200), page(200, "a"))]);
        let expanded = BTreeSet::from([
            "field-root/0".to_owned(),
            "field-root/1".to_owned(),
            "field-root/1/200".to_owned(),
        ]);
        let mut offsets = BTreeMap::from([("field-root/1".to_owned(), 200)]);
        let build = |offsets: &BTreeMap<String, u64>, loading: &BTreeSet<(u64, u64)>| {
            build_rows_with_references(
                &root,
                false,
                false,
                &expanded,
                offsets,
                &BTreeSet::new(),
                &details,
                loading,
                Language::English,
                InspectorColumn::Label,
                true,
            )
        };
        let rows = build(&offsets, &BTreeSet::new());
        let pages: Vec<_> = rows
            .iter()
            .filter_map(|row| row.array_paging.as_ref())
            .collect();
        assert_eq!(pages.len(), 2);
        assert_eq!(
            (&*pages[0].path, pages[0].target, pages[0].start),
            ("field-root/0", 2, 0)
        );
        assert_eq!(
            (&*pages[1].path, pages[1].target, pages[1].start),
            ("field-root/1", 2, 200)
        );
        assert_eq!(
            (pages[1].page_size, pages[1].total, pages[1].page_len),
            (200, 401, Some(2))
        );
        assert!(
            rows.iter()
                .any(|row| row.field_path.as_deref() == Some("field-root/0/0"))
        );
        assert!(
            rows.iter()
                .any(|row| row.field_path.as_deref() == Some("field-root/1/200") && row.expanded)
        );
        assert!(
            !rows
                .iter()
                .any(|row| row.field_path.as_deref() == Some("field-root/1/0"))
        );

        let loading = build(&offsets, &BTreeSet::from([(2, 200)]));
        let second_page = loading
            .iter()
            .filter_map(|row| row.array_paging.as_ref())
            .nth(1)
            .unwrap();
        assert!(second_page.loading);
        assert_eq!(second_page.page_len, None);
        assert_eq!(
            field_array_page_label(second_page, Language::English),
            Language::English.text(TextKey::MemoryPageMemoryInspectorLoading)
        );
        assert!(
            loading
                .iter()
                .any(|row| row.field_path.as_deref() == Some("field-root/1"))
        );
        assert!(
            !loading
                .iter()
                .any(|row| row.field_path.as_deref() == Some("field-root/1/200"))
        );

        offsets.insert("field-root/1".to_owned(), 400);
        let unavailable = build(&offsets, &BTreeSet::new());
        let second_page = unavailable
            .iter()
            .filter_map(|row| row.array_paging.as_ref())
            .nth(1)
            .unwrap();
        assert!(!second_page.loading);
        assert_eq!(second_page.page_len, None);
        assert_eq!(
            field_array_page_label(second_page, Language::English),
            Language::English.text(TextKey::MemoryPageUnavailable)
        );

        offsets.insert("field-root/1".to_owned(), 0);
        let rows = build(&offsets, &BTreeSet::new());
        assert!(
            rows.iter()
                .any(|row| row.field_path.as_deref() == Some("field-root/1/0") && !row.expanded)
        );
        assert!(
            !rows
                .iter()
                .any(|row| row.field_path.as_deref() == Some("field-root/1/200"))
        );
    }

    #[::core::prelude::v1::test]
    fn root_array_field_paths_use_the_page_offset() {
        let root = InstanceDetail {
            object_id: 1,
            is_array: true,
            array_start: 200,
            fields: vec![object_field("z", 2), object_field("a", 3)],
            ..InstanceDetail::default()
        };
        let rows = field_tree_rows(&root, &BTreeSet::new(), &BTreeMap::new(), &BTreeSet::new());
        assert_eq!(rows[0].field_path.as_deref(), Some("field-root/201"));
        assert_eq!(rows[1].field_path.as_deref(), Some("field-root/200"));
    }

    #[::core::prelude::v1::test]
    fn fields_expand_by_original_path_sort_each_level_and_collapse_independently() {
        let root = InstanceDetail {
            object_id: 1,
            fields: vec![object_field("z", 2), object_field("a", 2)],
            ..InstanceDetail::default()
        };
        let child = Arc::new(InstanceDetail {
            object_id: 2,
            fields: vec![field("z-child", None, None), object_field("a-child", 3)],
            ..InstanceDetail::default()
        });
        let grandchild = Arc::new(InstanceDetail {
            object_id: 3,
            fields: vec![field("value", None, None)],
            ..InstanceDetail::default()
        });
        let details = BTreeMap::from([((2, 0), child), ((3, 0), grandchild)]);
        let mut expanded = BTreeSet::from(["field-root/1".to_owned(), "field-root/1/1".to_owned()]);
        let rows = field_tree_rows(&root, &expanded, &details, &BTreeSet::new());
        let paths: Vec<_> = rows.iter().map(|row| row.field_path.as_deref()).collect();
        assert_eq!(
            paths,
            [
                Some("field-root/1"),
                Some("field-root/1/1"),
                Some("field-root/1/1/0"),
                Some("field-root/1/0"),
                Some("field-root/0")
            ]
        );
        assert_eq!(
            rows.iter().map(|row| row.tree_depth).collect::<Vec<_>>(),
            [0, 1, 2, 1, 0]
        );
        assert!(rows[0].expanded);
        assert!(!rows[4].expanded);

        expanded.insert("field-root/0".to_owned());
        let rows = field_tree_rows(&root, &expanded, &details, &BTreeSet::new());
        assert!(
            rows.iter()
                .any(|row| row.field_path.as_deref() == Some("field-root/0/1"))
        );
        expanded.remove("field-root/1");
        let rows = field_tree_rows(&root, &expanded, &details, &BTreeSet::new());
        assert!(
            !rows
                .iter()
                .any(|row| row.field_path.as_deref() == Some("field-root/1/1"))
        );
        assert!(
            rows.iter()
                .any(|row| row.field_path.as_deref() == Some("field-root/0/1"))
        );
        expanded.insert("field-root/1".to_owned());
        let rows = field_tree_rows(&root, &expanded, &details, &BTreeSet::new());
        assert!(
            rows.iter()
                .any(|row| row.field_path.as_deref() == Some("field-root/1/1/0"))
        );
    }

    #[::core::prelude::v1::test]
    fn fields_keep_parent_while_loading_and_detect_ancestor_cycles() {
        let root = InstanceDetail {
            object_id: 1,
            fields: vec![object_field("child", 2)],
            ..InstanceDetail::default()
        };
        let expanded = BTreeSet::from(["field-root/0".to_owned(), "field-root/0/0".to_owned()]);
        let loading_rows = field_tree_rows(
            &root,
            &expanded,
            &BTreeMap::new(),
            &BTreeSet::from([(2, 0)]),
        );
        assert_eq!(loading_rows.len(), 2);
        assert_eq!(loading_rows[0].field_path.as_deref(), Some("field-root/0"));
        assert!(loading_rows[0].expanded);
        assert!(loading_rows[1].is_message);
        assert_eq!(
            loading_rows[1].label,
            Language::English.text(TextKey::MemoryPageMemoryInspectorLoading)
        );
        let unavailable_rows =
            field_tree_rows(&root, &expanded, &BTreeMap::new(), &BTreeSet::new());
        assert_eq!(
            unavailable_rows[1].label,
            Language::English.text(TextKey::MemoryPageUnavailable)
        );

        let child = Arc::new(InstanceDetail {
            object_id: 2,
            fields: vec![object_field("back", 1)],
            ..InstanceDetail::default()
        });
        let rows = field_tree_rows(
            &root,
            &expanded,
            &BTreeMap::from([((2, 0), child)]),
            &BTreeSet::new(),
        );
        assert_eq!(rows.len(), 3);
        assert_eq!(rows[1].field_path.as_deref(), Some("field-root/0/0"));
        assert_eq!(
            rows[2].label,
            Language::English.text(TextKey::MemoryPageMemoryInspectorCycle)
        );
    }

    #[::core::prelude::v1::test]
    fn fields_stop_after_thirty_two_levels() {
        let root = InstanceDetail {
            object_id: 1,
            fields: vec![object_field("next", 2)],
            ..InstanceDetail::default()
        };
        let mut details = BTreeMap::new();
        for id in 2..=33 {
            details.insert(
                (id, 0),
                Arc::new(InstanceDetail {
                    object_id: id,
                    fields: vec![object_field("next", id + 1)],
                    ..InstanceDetail::default()
                }),
            );
        }
        let mut expanded = BTreeSet::new();
        let mut path = FIELD_ROOT_PATH.to_owned();
        for _ in 0..32 {
            path.push_str("/0");
            expanded.insert(path.clone());
        }
        let rows = field_tree_rows(&root, &expanded, &details, &BTreeSet::new());
        assert_eq!(rows.len(), 33);
        assert_eq!(rows[31].tree_depth, 31);
        assert!(rows[32].is_message);
        assert_eq!(
            rows[32].label,
            Language::English.text(TextKey::MemoryPageMemoryInspectorDepthLimit)
        );
    }

    #[::core::prelude::v1::test]
    fn references_expand_in_place_and_keep_repeated_paths_independent() {
        let root = InstanceDetail {
            object_id: 42,
            class_name: "sample.Root".into(),
            references: vec![InstanceField {
                name: "owner".into(),
                target_object_id: Some(7),
                target_class_name: Some("sample.Owner".into()),
                ..InstanceField::default()
            }],
            ..InstanceDetail::default()
        };
        let child = Arc::new(InstanceDetail {
            object_id: 7,
            class_name: "sample.Owner".into(),
            references: vec![InstanceField {
                name: "root".into(),
                target_object_id: Some(42),
                target_class_name: Some("sample.Root".into()),
                ..InstanceField::default()
            }],
            ..InstanceDetail::default()
        });
        let mut expanded = BTreeSet::from([
            REFERENCE_ROOT_PATH.to_owned(),
            "reference-root/0".to_owned(),
        ]);
        let details = BTreeMap::from([((7, 0), child)]);
        let rows = build_rows_with_references(
            &root,
            true,
            false,
            &BTreeSet::new(),
            &BTreeMap::new(),
            &expanded,
            &details,
            &BTreeSet::new(),
            Language::English,
            InspectorColumn::Depth,
            true,
        );
        assert_eq!(rows[0].label, "Root@42 (0x2a)");
        assert_eq!(rows[1].tree_depth, 1);
        assert!(rows[1].expanded);
        assert_eq!(rows[2].tree_depth, 2);
        assert!(
            rows[2]
                .reference_path
                .as_deref()
                .is_some_and(|path| path.starts_with("reference-root/0/"))
        );

        expanded.remove("reference-root/0");
        let rows = build_rows_with_references(
            &root,
            true,
            false,
            &BTreeSet::new(),
            &BTreeMap::new(),
            &expanded,
            &details,
            &BTreeSet::new(),
            Language::English,
            InspectorColumn::Depth,
            true,
        );
        assert_eq!(rows.len(), 2);
    }

    #[::core::prelude::v1::test]
    fn nearest_reference_path_expands_each_decreasing_depth_edge_until_gc_root() {
        let root = InstanceDetail {
            object_id: 42,
            depth: Some(3),
            references: vec![
                InstanceField {
                    name: "nearer".into(),
                    target_object_id: Some(142),
                    depth: Some(2),
                    ..InstanceField::default()
                },
                InstanceField {
                    name: "equal".into(),
                    target_object_id: Some(143),
                    depth: Some(3),
                    ..InstanceField::default()
                },
                InstanceField {
                    name: "gcRoot".into(),
                    target_object_id: Some(4),
                    depth: Some(0),
                    ..InstanceField::default()
                },
            ],
            ..InstanceDetail::default()
        };
        let child = Arc::new(InstanceDetail {
            object_id: 142,
            depth: Some(2),
            references: vec![
                InstanceField {
                    name: "nearerChild".into(),
                    target_object_id: Some(242),
                    depth: Some(1),
                    ..InstanceField::default()
                },
                InstanceField {
                    name: "gcRoot".into(),
                    target_object_id: Some(4),
                    depth: Some(0),
                    ..InstanceField::default()
                },
            ],
            ..InstanceDetail::default()
        });
        let grandchild = Arc::new(InstanceDetail {
            object_id: 242,
            depth: Some(1),
            references: vec![InstanceField {
                name: "gcRoot".into(),
                target_object_id: Some(4),
                depth: Some(0),
                ..InstanceField::default()
            }],
            ..InstanceDetail::default()
        });
        let details = BTreeMap::from([((142, 0), child), ((242, 0), grandchild)]);
        let mut expanded = BTreeSet::from([REFERENCE_ROOT_PATH.to_owned()]);
        assert_eq!(
            nearest_reference_path(&root, &details, &expanded).as_deref(),
            Some("reference-root/0")
        );
        expanded.insert("reference-root/0".into());
        assert_eq!(
            nearest_reference_path(&root, &details, &expanded).as_deref(),
            Some("reference-root/0/0")
        );
        expanded.insert("reference-root/0/0".into());
        assert_eq!(nearest_reference_path(&root, &details, &expanded), None);
        assert_eq!(
            reference_target_for_path(&root, &details, "reference-root/0/0"),
            Some(242)
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
    fn nearest_root_selects_the_first_reference_chain_row() {
        let detail = InstanceDetail {
            object_id: 42,
            class_name: "sample.Target".into(),
            reference_chain: vec![aps_memory::ObjectReference {
                field_name: Some("root".into()),
                root_kind: None,
                object_id: 4,
                class_name: "sample.Root".into(),
            }],
            references: vec![
                InstanceField {
                    name: "target42".into(),
                    target_object_id: Some(4),
                    target_class_name: Some("sample.Root".into()),
                    depth: Some(0),
                    ..InstanceField::default()
                },
                InstanceField {
                    name: "target".into(),
                    target_object_id: Some(242),
                    target_class_name: Some("sample.Owner42".into()),
                    depth: Some(0),
                    ..InstanceField::default()
                },
            ],
            depth: Some(1),
            ..InstanceDetail::default()
        };
        let rows = build_rows(
            &detail,
            true,
            true,
            &BTreeSet::new(),
            InspectorColumn::Depth,
            true,
        );
        let selected: Vec<_> = rows
            .iter()
            .filter(|row| row.selected)
            .map(|row| row.label.as_str())
            .collect();
        assert_eq!(selected, ["target42 in Root@4 (0x4)"]);
        // Without nearest-root-only the Compose table starts with no selection.
        let rows = build_rows(
            &detail,
            true,
            false,
            &BTreeSet::new(),
            InspectorColumn::Depth,
            true,
        );
        assert!(rows.iter().all(|row| !row.selected));
    }

    #[::core::prelude::v1::test]
    fn inspector_columns_follow_capabilities_and_evidence() {
        assert_eq!(
            inspector_columns(false, false),
            vec![
                InspectorColumn::Label,
                InspectorColumn::Depth,
                InspectorColumn::ShallowSize,
                InspectorColumn::RetainedSize,
            ]
        );
        assert_eq!(
            inspector_columns(true, true),
            vec![
                InspectorColumn::Label,
                InspectorColumn::Depth,
                InspectorColumn::NativeSize,
                InspectorColumn::ShallowSize,
                InspectorColumn::RetainedNativeSize,
                InspectorColumn::RetainedSize,
            ]
        );
    }

    #[::core::prelude::v1::test]
    fn hprof_native_columns_require_evidence() {
        let hprof = MemorySnapshot {
            metadata: SnapshotMetadata {
                format: "JAVA PROFILE 1.0.3".into(),
                ..SnapshotMetadata::default()
            },
            classes: vec![ClassStats {
                class_name: "sample.Target".into(),
                native_size: Some(64),
                ..ClassStats::default()
            }],
            ..MemorySnapshot::default()
        };
        assert!(snapshot_native_capability(Some(&hprof)));

        let native = MemorySnapshot {
            classes: vec![ClassStats {
                class_name: "sample.Target".into(),
                native_size: Some(64),
                ..ClassStats::default()
            }],
            ..MemorySnapshot::default()
        };
        assert!(snapshot_native_capability(Some(&native)));
        assert!(!snapshot_native_capability(
            Some(&MemorySnapshot::default())
        ));
        assert!(!snapshot_native_capability(None));
    }

    #[::core::prelude::v1::test]
    fn sort_defaults_match_compose_and_round_trip_through_state() {
        assert_eq!(
            default_sort(InspectorTab::Fields),
            (InspectorColumn::RetainedSize, false)
        );
        assert_eq!(
            default_sort(InspectorTab::References),
            (InspectorColumn::Depth, true)
        );
        // The untouched state selects the active tab's default.
        assert_eq!(
            stored_sort(0, true, InspectorTab::Fields),
            (InspectorColumn::RetainedSize, false)
        );
        assert_eq!(
            stored_sort(0, false, InspectorTab::References),
            (InspectorColumn::Depth, true)
        );
        let (code, ascending) = sort_state(InspectorColumn::Depth, false);
        assert_eq!(code, 2);
        assert_eq!(
            stored_sort(code, ascending, InspectorTab::References),
            (InspectorColumn::Depth, false)
        );
    }

    #[::core::prelude::v1::test]
    fn unavailable_metrics_use_an_ascii_hyphen() {
        assert_eq!(metric_text(None), "-");
        assert_eq!(metric_text(Some(1234567)), "1,234,567");
    }
}

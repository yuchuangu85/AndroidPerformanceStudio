//! Shared Compose-parity primitives for the Memory Profiler page.
//!
//! Cards use the neutral panel surface, borders the shared divider and table
//! headers the section surface; the accent colors interactive state only.
//! `StudioColors::from_theme` owns that palette for every profiler, so this
//! module only adds Memory-specific primitives and never re-derives colors.
use super::ImportWorkspace;
use super::state::{ColumnResizeOrigin, MemoryTable};
use crate::studio::{self, StudioColors};
use aps_memory::HeapValueKind;
use aps_ui_foundation::typography;
use gpui_kit::{
    component::{
        Disableable,
        button::{Button, ButtonVariants},
    },
    prelude::*,
    *,
};

/// Compose ViewerTypography.bodyCompact line height + 4 dp row padding.
pub(super) const TABLE_ROW_HEIGHT: f32 = 20.;
/// Compose CLASSIFIER_HEADER_HEIGHT.
pub(super) const TABLE_HEADER_HEIGHT: f32 = 24.;
/// Compose ViewerTypography.bodyCompact font size.
pub(super) const TABLE_FONT: f32 = typography::BODY;
/// Compose ViewerTypography.bodyCompact line height.
pub(super) const TABLE_LINE: f32 = 16.;
/// Compose table metric-column width (CLASSIFIER_METRIC_WIDTH).
pub(super) const METRIC_WIDTH: f32 = 104.;
/// Compose NAME_WARNING_WIDTH.
pub(super) const WARNING_WIDTH: f32 = 22.;
/// Full-row disclosure hit target shared by all Memory trees.
pub(super) const DISCLOSURE_WIDTH: f32 = 20.;

pub(super) fn disclosure_button(
    id: impl Into<ElementId>,
    expanded: bool,
    color: Rgba,
) -> Stateful<Div> {
    div()
        .id(id)
        .w(px(DISCLOSURE_WIDTH))
        .h(px(TABLE_ROW_HEIGHT))
        .flex_shrink_0()
        .flex()
        .items_center()
        .justify_center()
        .text_size(px(typography::TITLE))
        .line_height(px(TABLE_ROW_HEIGHT))
        .text_color(color)
        .cursor_pointer()
        .child(if expanded { "▾" } else { "▸" })
        // Row selection runs on MouseDown; stopping Click alone is too late
        // to prevent a heap/group row from toggling a second time.
        .on_mouse_down(MouseButton::Left, |_, _, cx| cx.stop_propagation())
}

// The Memory page uses the shared `StudioColors::from_theme` palette (neutral
// panel, section, border and field surfaces), not page-local overrides, so its
// cards match every other profiler workspace and never carry the theme color.

#[derive(Clone)]
struct MemoryResizeDrag {
    table: MemoryTable,
    column: usize,
}

impl Render for MemoryResizeDrag {
    fn render(&mut self, _window: &mut Window, _cx: &mut Context<Self>) -> impl IntoElement {
        Empty
    }
}

/// Adds transparent resize targets over a header whose columns are assembled
/// by a tab-specific renderer (for example Leak Analysis).
pub(super) fn column_resize_overlay(
    table: MemoryTable,
    widths: &[f32],
    cx: &mut Context<ImportWorkspace>,
) -> Div {
    let mut overlay = div().absolute().inset_0().flex();
    for (index, width) in widths.iter().enumerate() {
        let cell = div().relative().h_full();
        let cell = if index == 0 {
            cell.flex_1().min_w_0()
        } else {
            cell.w(px(*width)).flex_shrink_0()
        };
        overlay = overlay.child(if index > 0 {
            cell.child(resize_boundary(table, index, *width, cx))
        } else {
            cell
        });
    }
    overlay
}

fn resize_boundary_handle(table: MemoryTable, column: usize) -> Stateful<Div> {
    div()
        .id(ElementId::Name(
            format!("memory-column-boundary-{table:?}-{column}").into(),
        ))
        .absolute()
        .top_0()
        .left_0()
        .w(px(8.))
        .h_full()
        .flex_shrink_0()
        .cursor(CursorStyle::ResizeLeftRight)
}

fn resized_column_width(origin: ColumnResizeOrigin, pointer_x: f32) -> f32 {
    // The flexible first column absorbs this change; fixed columns stay
    // anchored on the right, so dragging their left edge right narrows them.
    origin.width - (pointer_x - origin.pointer_x)
}

fn resize_boundary(
    table: MemoryTable,
    column: usize,
    width: f32,
    cx: &mut Context<ImportWorkspace>,
) -> Stateful<Div> {
    resize_boundary_handle(table, column)
        .on_mouse_down(
            MouseButton::Left,
            cx.listener(move |this, event: &MouseDownEvent, _, cx| {
                cx.stop_propagation();
                this.memory_state.column_resize_origin = Some(ColumnResizeOrigin {
                    table,
                    column,
                    pointer_x: f32::from(event.position.x),
                    width,
                });
            }),
        )
        .on_click(|_, _, cx| cx.stop_propagation())
        .on_drag_move(cx.listener(
            move |this, event: &DragMoveEvent<MemoryResizeDrag>, _, cx| {
                let Some(origin) = this.memory_state.column_resize_origin else {
                    return;
                };
                if origin.table != table || origin.column != column {
                    return;
                }
                let width = resized_column_width(origin, f32::from(event.event.position.x));
                this.memory_resize_column(table, column, width, cx);
            },
        ))
        .on_drag(MemoryResizeDrag { table, column }, |drag, _, _, cx| {
            cx.new(|_| drag.clone())
        })
}

/// Adds a narrow, independent drag target to a table-column boundary. The
/// separate hit area keeps header sorting and column resizing from competing.
pub(super) fn resizable_header(
    cell: Div,
    table: MemoryTable,
    column: usize,
    width: f32,
    cx: &mut Context<ImportWorkspace>,
) -> Div {
    if column == 0 {
        return cell;
    }
    cell.relative()
        .child(resize_boundary(table, column, width, cx))
}

/// Compose ProfilerCompactButton used in the Memory toolbar and filter bar.
/// Height 24, 4 dp radius, accent outline, selected rows are accent-filled.
pub(super) fn toolbar_button(
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    enabled: bool,
    selected: bool,
    colors: &StudioColors,
) -> Button {
    let label: SharedString = label.into();
    let (background, content) = if !enabled {
        (colors.panel.alpha(0.5), colors.accent.alpha(0.4))
    } else if selected {
        (colors.accent, colors.accent_text)
    } else {
        (colors.panel, colors.accent)
    };
    Button::new(id)
        .ghost()
        .h(px(24.))
        .px(px(8.))
        .rounded(px(4.))
        .bg(background)
        .border_1()
        .border_color(colors.accent)
        .text_color(content)
        .disabled(!enabled)
        .accessibility_label(label.clone())
        .child(
            div()
                .text_size(px(typography::SUPPORTING))
                .font_weight(if selected {
                    FontWeight::SEMIBOLD
                } else {
                    FontWeight::NORMAL
                })
                .whitespace_nowrap()
                .child(label),
        )
}

/// Compose DropdownSelector: section surface, accent outline, accent text and
/// a 12 dp expand caret.
pub(super) fn selector_button(
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    enabled: bool,
    colors: &StudioColors,
) -> Button {
    let label: SharedString = label.into();
    let background = if enabled {
        colors.section_background
    } else {
        colors.section_background.alpha(0.5)
    };
    Button::new(id)
        .ghost()
        .h(px(23.))
        .px(px(6.))
        .rounded(px(4.))
        .bg(background)
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
                        .text_size(px(typography::SUPPORTING))
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
                        .text_size(px(9.))
                        .text_color(colors.accent.alpha(if enabled { 1. } else { 0.45 }))
                        .child("⌄"),
                ),
        )
}

/// Compose MemoryToolButton close control: 28 dp square, 16 px glyph.
pub(super) fn icon_button(
    id: impl Into<ElementId>,
    glyph: impl Into<SharedString>,
    enabled: bool,
    selected: bool,
    colors: &StudioColors,
) -> Button {
    let glyph: SharedString = glyph.into();
    let background = if selected {
        colors.selected_row
    } else {
        colors.panel
    };
    Button::new(id)
        .ghost()
        .h(px(28.))
        .w(px(28.))
        .rounded(px(0.))
        .bg(background)
        .disabled(!enabled)
        .child(
            div()
                .text_size(px(16.))
                .text_color(if enabled {
                    colors.accent
                } else {
                    colors.accent.alpha(0.35)
                })
                .child(glyph),
        )
}

/// Compose FilterCheckbox: 14 dp compact checkbox plus a body-compact label.
pub(super) fn checkbox(
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    checked: bool,
    enabled: bool,
    colors: &StudioColors,
) -> Stateful<Div> {
    let label: SharedString = label.into();
    let box_color = if checked {
        colors.accent
    } else {
        colors.border
    };
    div()
        .id(id)
        .flex()
        .items_center()
        .gap(px(2.))
        .cursor_pointer()
        .when(!enabled, |element| element.opacity(0.45))
        .child(
            div()
                .w(px(14.))
                .h(px(14.))
                .flex()
                .items_center()
                .justify_center()
                .rounded(px(2.))
                .border_2()
                .border_color(box_color)
                .when(checked, |element| {
                    element.bg(colors.accent).child(
                        div()
                            .text_size(px(11.))
                            .text_color(colors.accent_text)
                            .font_weight(FontWeight::BOLD)
                            .child("✓"),
                    )
                }),
        )
        .child(
            div()
                .text_size(px(TABLE_FONT))
                .text_color(colors.text)
                .whitespace_nowrap()
                .child(label),
        )
}

/// 14 dp Android Studio-style leak warning marker.
pub(super) fn warning_icon(colors: &StudioColors) -> AnyElement {
    div()
        .flex_shrink_0()
        .text_size(px(12.))
        .text_color(colors.warning)
        .child("⚠")
        .into_any_element()
}

/// Compose InspectorTypeIcon: colored object/field/class/array/primitive badge.
pub(super) fn type_badge(kind: HeapValueKind, is_root: bool, colors: &StudioColors) -> AnyElement {
    let (background, glyph) = match kind {
        HeapValueKind::Primitive => (rgb(0x40b6e0), "10"),
        HeapValueKind::Array => (rgb(0x40b6e0), "[]"),
        HeapValueKind::Class => (rgb(0x40b6e0), "C"),
        HeapValueKind::Object if is_root => (rgb(0x62b543), "i"),
        HeapValueKind::Object => (rgb(0xf4af3d), "f"),
        HeapValueKind::Unknown => (colors.secondary_text, "?"),
    };
    div()
        .size(px(14.))
        .flex_shrink_0()
        .flex()
        .items_center()
        .justify_center()
        .rounded_full()
        .bg(background.alpha(0.65))
        .child(
            div()
                .text_size(px(8.))
                .text_color(rgb(0x1d1d1f))
                .font_weight(FontWeight::SEMIBOLD)
                .child(glyph),
        )
        .into_any_element()
}

/// Compose table header cell (body compact, semibold) with the same column grid
/// line as every metric cell.
pub(super) fn header_cell(
    width: Option<f32>,
    label: impl Into<SharedString>,
    right: bool,
    grid: bool,
    colors: &StudioColors,
) -> Div {
    let mut cell = div()
        .h_full()
        .flex()
        .items_center()
        .min_w_0()
        .overflow_hidden()
        .px(px(6.))
        .text_size(px(TABLE_FONT))
        .line_height(px(TABLE_LINE))
        .font_weight(FontWeight::SEMIBOLD)
        .text_color(colors.text)
        .whitespace_nowrap()
        .text_ellipsis()
        .when(right, |cell| {
            cell.justify_end().text_align(TextAlign::Right)
        })
        .when(grid, |cell| cell.border_l_1().border_color(colors.border));
    if let Some(width) = width {
        cell = cell.w(px(width)).flex_shrink_0();
    } else {
        cell = cell.flex_1().min_w_0();
    }
    cell.child(
        div()
            .flex_1()
            .min_w_0()
            .overflow_hidden()
            .whitespace_nowrap()
            .text_ellipsis()
            .when(right, |text| text.text_align(TextAlign::Right))
            .child(label.into()),
    )
}

/// Compose table metric cell value: body compact, right aligned, with the
/// continuous column grid line on its left edge.
pub(super) fn metric_cell(
    width: Option<f32>,
    text: impl Into<SharedString>,
    colors: &StudioColors,
) -> Div {
    let mut cell = div()
        .h_full()
        .flex()
        .items_center()
        .justify_end()
        .min_w_0()
        .overflow_hidden()
        .px(px(6.))
        .border_l_1()
        .border_color(colors.border)
        .text_size(px(TABLE_FONT))
        .line_height(px(TABLE_LINE))
        .text_color(colors.text)
        .text_align(TextAlign::Right)
        .whitespace_nowrap()
        .text_ellipsis();
    if let Some(width) = width {
        cell = cell.w(px(width)).flex_shrink_0();
    } else {
        cell = cell.flex_1().min_w_0();
    }
    cell.child(
        div()
            .flex_1()
            .min_w_0()
            .overflow_hidden()
            .whitespace_nowrap()
            .text_ellipsis()
            .child(crate::studio::selectable(text.into())),
    )
}

/// Keep unavailable evidence visually subordinate to real metric values.
pub(super) fn summary_value(value: impl Into<SharedString>, available: bool, color: Rgba) -> Div {
    div()
        .w_full()
        .text_size(px(if available {
            typography::TITLE
        } else {
            typography::SUPPORTING
        }))
        .line_height(px(19.))
        .font_weight(FontWeight::SEMIBOLD)
        .text_color(color)
        .whitespace_nowrap()
        .text_ellipsis()
        .child(crate::studio::selectable(value.into()))
}

/// Compose MemorySnapshotSummary KPI card.
pub(super) fn kpi_card(
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    value: impl Into<SharedString>,
    available: bool,
    warning: bool,
    width: f32,
    colors: &StudioColors,
) -> AnyElement {
    let label: SharedString = label.into();
    let value: SharedString = value.into();
    let mut label_row = div().w_full().flex().items_center().gap(px(4.)).child(
        div()
            .flex_1()
            .min_w_0()
            .text_size(px(typography::SUPPORTING))
            .line_height(px(15.))
            .text_color(colors.secondary_text)
            .whitespace_nowrap()
            .text_ellipsis()
            .child(crate::studio::selectable(label)),
    );
    if warning {
        label_row = label_row.child(warning_icon(colors));
    }
    studio::card(colors)
        .id(id)
        .w(px(width))
        .flex_shrink_0()
        .p(px(4.))
        .gap(px(0.))
        .child(label_row)
        .child(summary_value(
            value,
            available,
            if warning { colors.warning } else { colors.text },
        ))
        .into_any_element()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[::core::prelude::v1::test]
    fn unavailable_summary_values_are_smaller_without_shrinking_real_metrics() {
        let colors = StudioColors::light();
        for language in [
            aps_ui_foundation::Language::English,
            aps_ui_foundation::Language::SimplifiedChinese,
        ] {
            let mut unavailable = summary_value(
                language.text(aps_ui_foundation::TextKey::MemoryPageUnavailable),
                false,
                colors.text,
            );
            assert_eq!(
                unavailable.style().text.font_size,
                Some(px(typography::SUPPORTING).into())
            );
        }
        for value in ["0", "104,134,969", "22.5 MB"] {
            let mut available = summary_value(value, true, colors.text);
            assert_eq!(
                available.style().text.font_size,
                Some(px(typography::TITLE).into())
            );
        }
    }

    #[::core::prelude::v1::test]
    fn tree_disclosures_use_larger_glyphs_and_full_row_hit_targets() {
        for expanded in [false, true] {
            let mut button = disclosure_button("memory-disclosure-test", expanded, rgb(0x808080));
            assert_eq!(button.style().size.width, Some(px(20.).into()));
            assert_eq!(
                button.style().size.height,
                Some(px(TABLE_ROW_HEIGHT).into())
            );
            assert_eq!(
                button.style().text.font_size,
                Some(px(typography::TITLE).into())
            );
            assert_eq!(button.style().flex_shrink, Some(0.));
            assert_eq!(button.style().justify_content, Some(JustifyContent::Center));
            assert_eq!(button.style().align_items, Some(AlignItems::Center));
        }
    }

    #[::core::prelude::v1::test]
    fn column_resize_handle_uses_the_fixed_columns_left_boundary() {
        let mut handle = resize_boundary_handle(MemoryTable::Classifier, 1);
        assert_eq!(handle.style().inset.left, Some(px(0.).into()));
        assert_eq!(handle.style().inset.right, None);
        assert_eq!(handle.style().size.width, Some(px(8.).into()));
        assert_eq!(handle.style().flex_shrink, Some(0.));
    }

    #[::core::prelude::v1::test]
    fn column_resize_keeps_the_right_edge_anchored_as_the_left_edge_moves() {
        let origin = ColumnResizeOrigin {
            table: MemoryTable::Classifier,
            column: 1,
            pointer_x: 500.,
            width: 104.,
        };
        for (pointer_x, expected_width) in [(480., 124.), (500., 104.), (520., 84.)] {
            let width = resized_column_width(origin, pointer_x);
            assert_eq!(width, expected_width);
            assert_eq!(pointer_x + width, origin.pointer_x + origin.width);
        }
    }

    #[::core::prelude::v1::test]
    fn table_headers_and_metrics_keep_flexible_first_and_right_aligned_rest() {
        let colors = StudioColors::light();
        let mut first = header_cell(None, "Name", false, false, &colors);
        assert_eq!(first.style().flex_grow, Some(1.));
        assert_eq!(first.style().flex_shrink, Some(1.));

        let mut header = header_cell(Some(104.), "Count", true, true, &colors);
        assert_eq!(header.style().flex_shrink, Some(0.));
        assert_eq!(header.style().justify_content, Some(JustifyContent::End));

        let mut value = metric_cell(Some(104.), "42", &colors);
        assert_eq!(value.style().flex_shrink, Some(0.));
        assert_eq!(value.style().justify_content, Some(JustifyContent::End));
    }
}

//! Shared Compose-parity primitives for the Memory Profiler page.
//!
//! The Compose page uses ViewerColors with the "workspace style" overlay:
//! cards are an accent-tinted panel, borders are accent-tinted, and table
//! headers use the section surface. The Rust shell's flat-sheet tokens are
//! intentionally different for other profilers, so the memory page derives its
//! own tokens here while still reusing StudioColors.
use crate::studio::{self, StudioColors};
use aps_memory::HeapValueKind;
use aps_ui_foundation::typography;
use gpui_kit::{
    component::{
        Disableable, Selectable,
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

/// Compose viewerColors(...).withWorkspaceStyle() projected onto StudioColors.
pub(super) fn memory_colors(cx: &App) -> StudioColors {
    let mut colors = StudioColors::from_theme(cx);
    let accent = colors.accent;
    if colors.dark {
        // Dark palette base = canvasBackground(0x1E1E1E) * 0.45, then the
        // Compose workspace-style accent overlays.
        let base = rgb(0x0e0e0e);
        let panel = base.blend(accent.alpha(0.08));
        colors.workspace = rgb(0x29292b);
        colors.toolbar = rgb(0x29292b);
        colors.panel = panel;
        colors.border = panel.blend(accent.alpha(0.22));
        colors.strong_border = rgb(0x636366);
        colors.section_background = panel.blend(accent.alpha(0.06));
        colors.selected_row = panel.blend(accent.alpha(0.32));
        colors.field = rgb(0x1c1c1e);
        colors.text = rgb(0xf5f5f7);
        colors.secondary_text = rgb(0xaeaeb2);
        colors.muted_text = rgb(0xaeaeb2);
        colors.warning = rgb(0xff9f0a);
        colors.info = accent;
        colors.error = rgb(0xff453a);
        colors.success = rgb(0x30d158);
    } else {
        colors.workspace = rgb(0xfafafb);
        colors.toolbar = rgb(0xfafafb);
        colors.panel = rgb(0xffffff);
        colors.border = rgb(0xd1d1d6);
        colors.strong_border = rgb(0xb8b8bd);
        colors.section_background = rgb(0xfafafb);
        colors.selected_row = rgb(0xd6eaff);
        colors.field = rgb(0xffffff);
        colors.text = rgb(0x1d1d1f);
        colors.secondary_text = rgb(0x6e6e73);
        colors.muted_text = rgb(0x6e6e73);
        colors.warning = rgb(0xff9f0a);
        colors.info = accent;
        colors.error = rgb(0xff3b30);
        colors.success = rgb(0x34c759);
    }
    colors
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

/// Compose FilterCheckbox: 18 dp Material checkbox plus a body-compact label.
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
                .w(px(18.))
                .h(px(18.))
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
        .px(px(8.))
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
    cell.child(label.into())
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
        .px(px(8.))
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
    cell.child(crate::studio::selectable(text.into()))
}

/// Compose MemorySnapshotSummary KPI card.
pub(super) fn kpi_card(
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    value: impl Into<SharedString>,
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
        .child(
            div()
                .w_full()
                .text_size(px(typography::TITLE))
                .line_height(px(19.))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(if warning { colors.warning } else { colors.text })
                .whitespace_nowrap()
                .text_ellipsis()
                .child(crate::studio::selectable(value)),
        )
        .into_any_element()
}

//! Studio design tokens and compact workspace primitives.
//!
//! The values mirror the Compose desktop contract (`ViewerColors`,
//! `ViewerTypography`, `ViewerDimensions` and `StudioTokens`) so the Rust
//! rewrite keeps the same dense visual language instead of the raw component
//! library defaults.
//!
//! Every card, section and field keeps a neutral surface: the persisted accent
//! colors interactive state (selection, focus, controls, charts) and never a
//! card background, so changing the accent leaves the chrome and card fills
//! untouched.

use gpui_kit::{
    base::SelectableText,
    component::{
        ActiveTheme, Disableable, Selectable,
        button::{Button, ButtonVariants},
    },
    prelude::*,
    *,
};

pub const FONT_TITLE: f32 = 16.0;
pub const FONT_BODY: f32 = 13.0;
pub const FONT_SUPPORTING: f32 = 11.0;

/// Semantic measurements shared by the dense profiler workspaces.
#[allow(dead_code)]
pub mod metrics {
    pub const WORKSPACE_PADDING: f32 = 4.0;
    pub const CARD_GAP: f32 = 4.0;
    pub const PANEL_GAP: f32 = 8.0;
    pub const COMPACT_PADDING: f32 = 8.0;
    pub const CARD_RADIUS: f32 = 8.0;
    pub const PANEL_RADIUS: f32 = 6.0;
    pub const CONTROL_RADIUS: f32 = 6.0;
    pub const SMALL_RADIUS: f32 = 4.0;
    pub const HAIRLINE: f32 = 1.0;
    pub const BUTTON_HEIGHT: f32 = 28.0;
    pub const COMPACT_BUTTON_HEIGHT: f32 = 20.0;
    pub const COMPACT_CONTROL_HEIGHT: f32 = 24.0;
    pub const TAB_HEIGHT: f32 = 28.0;
    pub const TOOLBAR_HEIGHT: f32 = 25.0;
    pub const BREADCRUMB_HEIGHT: f32 = 22.0;
    pub const PANEL_HEADER_HEIGHT: f32 = 36.0;
    pub const KPI_PADDING_H: f32 = 12.0;
    pub const KPI_PADDING_V: f32 = 8.0;
    pub const DETAILS_WIDTH: f32 = 300.0;
    pub const NARROW_DETAILS_HEIGHT: f32 = 160.0;
    pub const NARROW_REPORT_WIDTH: f32 = 760.0;
    pub const TIMELINE_MIN_HEIGHT: f32 = 120.0;
    pub const TIMELINE_MAX_HEIGHT: f32 = 480.0;
    pub const TIMELINE_DEFAULT_HEIGHT: f32 = 220.0;
    pub const TIMELINE_HANDLE_HEIGHT: f32 = 8.0;
    pub const KPI_WIDE_WIDTH: f32 = 900.0;
    pub const KPI_MEDIUM_WIDTH: f32 = 520.0;
}

/// Compose `ViewerColors` subset used by dense profiler pages.
#[derive(Clone, Copy, Debug)]
#[allow(dead_code)]
pub struct StudioColors {
    pub dark: bool,
    pub workspace: Rgba,
    pub toolbar: Rgba,
    pub panel: Rgba,
    pub border: Rgba,
    pub strong_border: Rgba,
    pub field: Rgba,
    pub section_background: Rgba,
    pub accent: Rgba,
    pub accent_text: Rgba,
    pub text: Rgba,
    pub secondary_text: Rgba,
    pub muted_text: Rgba,
    pub warning: Rgba,
    pub info: Rgba,
    pub error: Rgba,
    pub success: Rgba,
    pub selected_row: Rgba,
    pub firefox_activity: Rgba,
    /// Compose `detailRowDeep`/`detailRowLight`: alternating properties rows.
    pub detail_row_deep: Rgba,
    pub detail_row_light: Rgba,
}

/// Background color of the window chrome (title bar, navigation rail, toolbars).
/// Cards, sections and fields use the neutral base palette from
/// [`StudioColors::for_mode_with_accent`]; the accent is never painted into a
/// card surface.
pub fn titlebar_background(dark: bool) -> Rgba {
    if dark { rgb(0x29292b) } else { rgb(0xfafafb) }
}

/// The one separator color shared by every screen, panel and component: page
/// title bars, navigation rails, table rows, settings panes and studio panels
/// all draw their hairlines and card borders with this value.
pub fn divider_color(dark: bool) -> Rgba {
    if dark { rgb(0x48484a) } else { rgb(0xd1d1d6) }
}

impl StudioColors {
    /// Compose `viewerColors(dark, accent)` with the neutral workspace palette:
    /// the base palette plus the accent state tints.
    ///
    /// Card surfaces stay neutral. `panel`, `section_background`, `border` and
    /// `field` mirror the Compose base palette and never composite the accent,
    /// so no card, KPI tile, table header or settings card is painted in the
    /// theme color. The accent lives only in interactive state: `accent` draws
    /// labels, focus, controls and charts, and `selected_row` is the accent
    /// selection tint over the neutral panel.
    pub fn for_mode_with_accent(dark: bool, accent: Rgba) -> Self {
        let mut colors = if dark {
            Self::dark_palette(accent)
        } else {
            Self::light_palette(accent)
        };
        colors.accent = accent;
        colors.workspace = titlebar_background(dark);
        colors.toolbar = titlebar_background(dark);
        colors.selected_row = colors
            .panel
            .blend(accent.alpha(if dark { 0.32 } else { 0.20 }));
        colors
    }

    /// Default-accent light palette for palette-level regression tests.
    #[cfg(test)]
    pub fn light() -> Self {
        Self::for_mode_with_accent(false, rgb(0x0a84ff))
    }

    /// The light neutral base palette: Compose `ViewerPalettes.light` without
    /// the accent-composited card surfaces. White cards sit over the toolbar
    /// workspace; the accent only tints interactive state.
    fn light_palette(accent: Rgba) -> Self {
        let background = titlebar_background(false);
        Self {
            dark: false,
            workspace: background,
            toolbar: background,
            panel: rgb(0xffffff),
            border: divider_color(false),
            strong_border: rgb(0xb8b8bd),
            field: rgb(0xffffff),
            section_background: background,
            accent,
            accent_text: rgb(0xffffff),
            text: rgb(0x1d1d1f),
            secondary_text: rgb(0x6e6e73),
            muted_text: rgb(0x6e6e73),
            warning: rgb(0xff9f0a),
            info: rgb(0x0a84ff),
            error: rgb(0xff3b30),
            success: rgb(0x34c759),
            selected_row: rgb(0xd6eaff),
            firefox_activity: rgb(0x5b8db8),
            detail_row_deep: rgb(0xffffff),
            detail_row_light: rgb(0xffffff),
        }
    }

    /// The dark neutral base palette: Compose `ViewerPalettes.dark` without the
    /// accent-composited card surfaces. Cards sit on the neutral panel surface and
    /// never carry the theme color.
    fn dark_palette(accent: Rgba) -> Self {
        let background = titlebar_background(true);
        Self {
            dark: true,
            workspace: background,
            toolbar: background,
            panel: rgb(0x2c2c2e),
            border: divider_color(true),
            strong_border: rgb(0x636366),
            field: rgb(0x1c1c1e),
            section_background: rgb(0x29292b),
            accent,
            accent_text: rgb(0xffffff),
            text: rgb(0xf5f5f7),
            secondary_text: rgb(0xaeaeb2),
            muted_text: rgb(0xaeaeb2),
            warning: rgb(0xff9f0a),
            info: rgb(0x64d2ff),
            error: rgb(0xff453a),
            success: rgb(0x30d158),
            selected_row: background.blend(accent.alpha(0.32)),
            firefox_activity: accent,
            detail_row_deep: rgb(0x1c1c1e),
            detail_row_light: rgb(0x2c2c2e),
        }
    }

    pub fn from_theme(cx: &App) -> Self {
        Self::for_mode_with_accent(cx.theme().is_dark(), cx.theme().accent.into())
    }
}

/// Root of a profiler workspace: workspace padding and the shared card rhythm.
pub fn page(colors: &StudioColors) -> Div {
    div()
        .size_full()
        .flex()
        .flex_col()
        .gap(px(metrics::CARD_GAP))
        .p(px(metrics::WORKSPACE_PADDING))
        .bg(colors.workspace)
        .text_color(colors.text)
        .text_size(px(FONT_BODY))
}

/// Compact repeatable card surface for dense workspaces.
pub fn card(colors: &StudioColors) -> Div {
    div()
        .flex()
        .flex_col()
        .gap(px(metrics::PANEL_GAP))
        .p(px(metrics::COMPACT_PADDING))
        .bg(colors.panel)
        .border_1()
        .border_color(colors.border)
        .rounded(px(metrics::CARD_RADIUS))
}

pub fn divider(colors: &StudioColors) -> Div {
    div().w_full().h(px(metrics::HAIRLINE)).bg(colors.border)
}

pub fn section_title(text: impl Into<SharedString>, colors: &StudioColors) -> Div {
    div()
        .text_size(px(FONT_BODY))
        .font_weight(FontWeight::SEMIBOLD)
        .text_color(colors.text)
        .child(selectable(text))
}

pub fn supporting(text: impl Into<SharedString>, colors: &StudioColors) -> Div {
    div()
        .text_size(px(FONT_SUPPORTING))
        .text_color(colors.secondary_text)
        .child(selectable(text))
}

pub fn dense_label(text: impl Into<SharedString>, color: Rgba) -> Div {
    div()
        .text_size(px(FONT_SUPPORTING))
        .text_color(color)
        .child(selectable(text))
}

/// A text run that joins the window-wide text selection.
///
/// Drag across runs to select; secondary+C copies the selected text. Styling
/// and ellipsis are inherited from the surrounding element, so replacing a
/// plain text child keeps the layout.
///
/// The retained selection state is keyed by the call site, the same
/// track_caller identity GPUI Base's own text helpers use. Repeated runs
/// rendered from one loop must pass a row identity to [`selectable_keyed`]
/// instead, or they would share one handle.
#[track_caller]
pub fn selectable(text: impl Into<SharedString>) -> SelectableText {
    let text: SharedString = text.into();
    SelectableText::new(
        selectable_element_id(std::panic::Location::caller(), &text),
        text,
    )
}

/// Identity for one selectable run: the call site plus its text.
///
/// A shared helper (for example [`supporting`]) would otherwise give every run
/// it renders the same identity and one retained selection handle, so only the
/// last painted run could be selected. Hashing the text keeps different runs
/// apart while remaining stable for an unchanged label; identical text from one
/// call site shares a handle, and copying it yields that same text.
fn selectable_element_id(
    location: &'static std::panic::Location<'static>,
    text: &str,
) -> ElementId {
    use std::hash::{Hash, Hasher};
    let mut hasher = std::collections::hash_map::DefaultHasher::new();
    location.file().hash(&mut hasher);
    location.line().hash(&mut hasher);
    location.column().hash(&mut hasher);
    text.hash(&mut hasher);
    ElementId::NamedInteger("selectable-text".into(), hasher.finish())
}

/// Window-selectable text with an explicit element identity, for repeated runs.
pub fn selectable_keyed(id: impl Into<ElementId>, text: impl Into<SharedString>) -> SelectableText {
    SelectableText::new(id, text)
}

/// KPI card: tone label, lead value and evidence support line.
pub fn kpi_tile(
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    value: impl Into<SharedString>,
    support: impl Into<SharedString>,
    tone: Rgba,
    colors: &StudioColors,
) -> Stateful<Div> {
    div()
        .id(id)
        .flex()
        .flex_col()
        .gap(px(2.))
        .px(px(metrics::KPI_PADDING_H))
        .py(px(metrics::KPI_PADDING_V))
        .bg(colors.panel)
        .border_1()
        .border_color(colors.border)
        .rounded(px(metrics::CARD_RADIUS))
        .child(
            div()
                .text_size(px(FONT_SUPPORTING))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(tone)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(selectable(label)),
        )
        .child(
            div()
                .text_size(px(FONT_BODY))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(colors.text)
                .whitespace_nowrap()
                .child(selectable(value)),
        )
        .child(
            div()
                .h(px(14.))
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.secondary_text)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(selectable(support)),
        )
}

/// Metric card used by the Overview grid (`StudioMetricCard`): neutral fill with
/// a tone-colored label and outline. The accent/tone never paints the fill.
pub fn metric_card(
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    value: impl Into<SharedString>,
    tone: Rgba,
    colors: &StudioColors,
) -> Stateful<Div> {
    div()
        .id(id)
        .flex()
        .flex_col()
        .gap(px(4.))
        .p(px(metrics::COMPACT_PADDING))
        .bg(colors.panel)
        .border_1()
        .border_color(tone.alpha(0.5))
        .rounded(px(metrics::CARD_RADIUS))
        .child(
            div()
                .text_size(px(FONT_BODY))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(tone)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(selectable(label)),
        )
        .child(
            div()
                .text_size(px(FONT_TITLE))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(colors.text)
                .whitespace_nowrap()
                .child(selectable(value)),
        )
}

fn button_label(label: SharedString, size: f32, weight: FontWeight) -> Div {
    div()
        .text_size(px(size))
        .font_weight(weight)
        .whitespace_nowrap()
        .text_ellipsis()
        .child(label)
}

/// `MacOSTextButton`: compact outlined action button.
pub fn text_button(
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    enabled: bool,
    primary: bool,
    colors: &StudioColors,
) -> Button {
    let label: SharedString = label.into();
    let container = if primary { colors.accent } else { colors.panel };
    let content = if primary {
        colors.accent_text
    } else {
        colors.accent
    };
    let (container, content) = if enabled {
        (container, content)
    } else {
        (container.alpha(0.55), content.alpha(0.48))
    };
    Button::new(id)
        .ghost()
        .h(px(metrics::BUTTON_HEIGHT))
        .px(px(10.))
        .rounded(px(metrics::CONTROL_RADIUS))
        .bg(container)
        .border_1()
        .border_color(colors.accent)
        .text_color(content)
        .disabled(!enabled)
        .accessibility_label(label.clone())
        .child(button_label(
            label,
            FONT_SUPPORTING,
            if primary {
                FontWeight::SEMIBOLD
            } else {
                FontWeight::NORMAL
            },
        ))
}

/// 20px-tall variant used inside dense toolbars and table rows.
pub fn compact_text_button(
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    enabled: bool,
    primary: bool,
    colors: &StudioColors,
) -> Button {
    let label: SharedString = label.into();
    let container = if primary { colors.accent } else { colors.panel };
    let content = if primary {
        colors.accent_text
    } else {
        colors.accent
    };
    let (container, content) = if enabled {
        (container, content)
    } else {
        (container.alpha(0.55), content.alpha(0.48))
    };
    Button::new(id)
        .ghost()
        .h(px(metrics::COMPACT_BUTTON_HEIGHT))
        .px(px(8.))
        .rounded(px(metrics::SMALL_RADIUS))
        .bg(container)
        .border_1()
        .border_color(colors.accent)
        .text_color(content)
        .disabled(!enabled)
        .accessibility_label(label.clone())
        .child(button_label(
            label,
            FONT_SUPPORTING,
            if primary {
                FontWeight::SEMIBOLD
            } else {
                FontWeight::NORMAL
            },
        ))
}

/// Report tab: 28px pill with a translucent accent selection.
pub fn tab_button(
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    selected: bool,
    colors: &StudioColors,
) -> Button {
    let label: SharedString = label.into();
    Button::new(id)
        .ghost()
        .h(px(metrics::TAB_HEIGHT))
        .px(px(10.))
        .rounded(px(metrics::SMALL_RADIUS))
        .bg(if selected {
            colors.accent.alpha(0.18)
        } else {
            colors.toolbar
        })
        .text_color(if selected { colors.accent } else { colors.text })
        .selected(selected)
        .accessibility_label(label.clone())
        .child(button_label(
            label,
            FONT_BODY,
            if selected {
                FontWeight::SEMIBOLD
            } else {
                FontWeight::NORMAL
            },
        ))
}

pub fn table_header_row(colors: &StudioColors) -> Div {
    div()
        .flex()
        .items_center()
        .gap(px(4.))
        .px(px(metrics::COMPACT_PADDING))
        .py(px(6.))
        .bg(colors.section_background)
}

pub fn table_header_cell(
    width: Option<f32>,
    label: impl Into<SharedString>,
    colors: &StudioColors,
) -> Div {
    let mut cell = div()
        .text_size(px(FONT_SUPPORTING))
        .font_weight(FontWeight::SEMIBOLD)
        .text_color(colors.secondary_text)
        .whitespace_nowrap()
        .text_ellipsis();
    if let Some(width) = width {
        cell = cell.w(px(width)).flex_shrink_0();
    } else {
        cell = cell.flex_1().min_w_0();
    }
    cell.child(selectable(label))
}

pub fn details_heading(text: impl Into<SharedString>, colors: &StudioColors) -> Div {
    div()
        .text_size(px(FONT_BODY))
        .font_weight(FontWeight::SEMIBOLD)
        .text_color(colors.text)
        .child(selectable(text))
}

/// Two-line metric tile used inside the detail inspector.
pub fn details_metric(
    label: impl Into<SharedString>,
    value: impl Into<SharedString>,
    colors: &StudioColors,
) -> Div {
    div()
        .flex()
        .flex_col()
        .gap(px(1.))
        .flex_1()
        .min_w_0()
        .px(px(8.))
        .py(px(5.))
        .bg(colors.toolbar)
        .rounded(px(metrics::SMALL_RADIUS))
        .child(
            div()
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.secondary_text)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(selectable(label)),
        )
        .child(
            div()
                .text_size(px(FONT_SUPPORTING))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(colors.text)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(selectable(value)),
        )
}

/// Draggable divider used to resize the activity timeline.
pub fn resize_handle(colors: &StudioColors) -> Div {
    div()
        .w_full()
        .h(px(metrics::TIMELINE_HANDLE_HEIGHT))
        .flex()
        .items_center()
        .cursor(CursorStyle::ResizeUpDown)
        .child(div().w_full().h(px(2.)).bg(colors.strong_border))
}

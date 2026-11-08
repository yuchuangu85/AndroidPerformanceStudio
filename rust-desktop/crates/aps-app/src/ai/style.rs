//! Chat-local DSH tokens for the AI Analysis conversation window.
//!
//! These mirror Compose `DshChatStyle` exactly and are deliberately separate
//! from the Studio/Viewer theme: the chat window keeps its own colors and
//! typography and must not recolor other profiler pages.
#![allow(dead_code)]

use gpui_kit::prelude::*;
use gpui_kit::*;

/// Chat-local DSH 0.2.1-rc.5 colors; these do not change the app theme.
#[derive(Clone, Copy, Debug, PartialEq)]
pub struct DshChatColors {
    /// Whether these tokens are the dark palette (used for code token tints).
    pub dark: bool,
    pub background: Rgba,
    pub text: Rgba,
    pub muted: Rgba,
    pub sidebar: Rgba,
    pub bubble: Rgba,
    pub composer: Rgba,
    pub send: Rgba,
    pub on_send: Rgba,
    pub border: Rgba,
    pub error: Rgba,
}

impl DshChatColors {
    pub fn light() -> Self {
        Self {
            dark: false,
            background: rgb(0xffffff),
            text: rgb(0x0f1115),
            muted: rgb(0x686b73),
            sidebar: rgb(0xf7f7f8),
            bubble: rgb(0xedf3fe),
            composer: rgb(0xffffff),
            send: rgb(0x4176e6),
            on_send: rgb(0xffffff),
            border: rgb(0xe5e5e9),
            error: rgb(0xb42332),
        }
    }

    pub fn dark() -> Self {
        Self {
            dark: true,
            background: rgb(0x151517),
            text: rgb(0xf9fafb),
            muted: rgb(0xababb3),
            sidebar: rgb(0x202024),
            bubble: rgb(0x2c2c2e),
            composer: rgb(0x2c2c2e),
            send: rgb(0x7aaaff),
            on_send: rgb(0x151517),
            border: rgb(0x3c3c42),
            error: rgb(0xffa2ab),
        }
    }

    pub fn for_dark(dark: bool) -> Self {
        if dark { Self::dark() } else { Self::light() }
    }
}

/// Compose `DshChatStyle` measurements and typography.
pub mod tokens {
    pub const SIDEBAR_WIDTH: f32 = 260.0;
    pub const SMALL_RADIUS: f32 = 8.0;
    pub const LARGE_RADIUS: f32 = 16.0;
    pub const EXTRA_LARGE_RADIUS: f32 = 20.0;
    pub const PANEL_RADIUS: f32 = 28.0;
    pub const SEND_BUTTON_SIZE: f32 = 28.0;
    pub const SCROLL_BUTTON_SIZE: f32 = 32.0;
    pub const SCROLL_BUTTON_GAP: f32 = 6.0;
    pub const SCROLL_CONTROLS_INSET: f32 = 12.0;
    pub const HEADER_HEIGHT: f32 = 56.0;
    pub const SIDEBAR_HEADER_HEIGHT: f32 = 58.0;
    pub const SEARCH_FIELD_HEIGHT: f32 = 36.0;
    pub const MESSAGE_GAP: f32 = 24.0;
    pub const MESSAGE_TOP_PADDING: f32 = 16.0;
    pub const MESSAGE_BOTTOM_PADDING: f32 = 24.0;
    pub const EMPTY_TOP_PADDING: f32 = 72.0;
    pub const EMPTY_BOTTOM_PADDING: f32 = 40.0;
    pub const USER_BUBBLE_MAX_FRACTION: f32 = 0.702;
    pub const USER_BUBBLE_PADDING_X: f32 = 16.0;
    pub const USER_BUBBLE_PADDING_Y: f32 = 10.0;
    pub const COMPOSER_MIN_HEIGHT: f32 = 44.0;
    pub const COMPOSER_MAX_HEIGHT: f32 = 160.0;
    pub const COMPOSER_TEXTAREA_LINES: usize = 6;
    pub const QUEUED_DOCK_MAX_HEIGHT: f32 = 148.0;
    pub const MODEL_MENU_MAX_HEIGHT: f32 = 320.0;
    pub const MODEL_MENU_MAX_WIDTH: f32 = 280.0;
    pub const ACTIVITY_ICON_SIZE: f32 = 18.0;
    pub const EMPTY_SPARKLE_SIZE: f32 = 36.0;
    pub const TITLE_FONT_SIZE: f32 = 26.0;
    pub const TITLE_LINE_HEIGHT: f32 = 32.0;
    pub const BODY_FONT_SIZE: f32 = 14.0;
    pub const BODY_LINE_HEIGHT: f32 = 24.0;
    pub const USER_LINE_HEIGHT: f32 = 22.0;
    pub const SECONDARY_FONT_SIZE: f32 = 12.0;
    pub const SECONDARY_LINE_HEIGHT: f32 = 18.0;
    pub const CODE_FONT_SIZE: f32 = 12.0;
    pub const CODE_LINE_HEIGHT: f32 = 20.0;
    pub const COMPOSER_MAX_TEXT: usize = 4096;
    pub const MAX_QUEUED_MESSAGES: usize = 8;
    pub const SIDEBAR_BREAKPOINT: f32 = 1000.0;
    pub const OVERLAY_BREAKPOINT: f32 = 640.0;
    pub const NAVIGATION_GUTTER: f32 = SCROLL_BUTTON_SIZE + SCROLL_CONTROLS_INSET;
}

/// Compose `DshChatStyle.chatWidth`: 64% of the available width clamped to
/// [680, 920], then capped so the scroll controls gutter always fits.
pub fn chat_width(available: f32) -> f32 {
    let available = available.max(0.0);
    let base = (available * 0.64).clamp(680.0, 920.0);
    base.min((available - tokens::NAVIGATION_GUTTER * 2.0).max(0.0))
}

/// Compose `DshChatStyle.composerWidth`: the chat width plus 32 px, capped to
/// the available width minus 16 px.
pub fn composer_width(available: f32) -> f32 {
    let available = available.max(0.0);
    (chat_width(available) + 32.0).min((available - 16.0).max(0.0))
}

/// Body text (14/24) inherited by most conversation content.
pub fn body_text(element: Div) -> Div {
    element
        .text_size(px(tokens::BODY_FONT_SIZE))
        .line_height(px(tokens::BODY_LINE_HEIGHT))
}

/// User bubble text (14/22).
pub fn user_text(element: Div) -> Div {
    element
        .text_size(px(tokens::BODY_FONT_SIZE))
        .line_height(px(tokens::USER_LINE_HEIGHT))
}

/// Secondary text (12/18).
pub fn secondary_text(element: Div) -> Div {
    element
        .text_size(px(tokens::SECONDARY_FONT_SIZE))
        .line_height(px(tokens::SECONDARY_LINE_HEIGHT))
}

/// Empty-state title (26/32 medium).
pub fn title_text(element: Div) -> Div {
    element
        .text_size(px(tokens::TITLE_FONT_SIZE))
        .line_height(px(tokens::TITLE_LINE_HEIGHT))
        .font_weight(FontWeight::MEDIUM)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[::core::prelude::v1::test]
    fn chat_width_matches_compose_clamp() {
        // Wide window: 64% clamped to 920.
        assert_eq!(chat_width(1600.0), 920.0);
        // Mid window: 64% governs.
        assert_eq!(chat_width(1200.0), 768.0);
        // Small window: clamped to 680, then capped by the navigation gutter.
        assert_eq!(chat_width(1000.0), 680.0);
        // Very narrow: only what fits beside the two scroll-control gutters.
        assert_eq!(chat_width(400.0), 400.0 - 2.0 * tokens::NAVIGATION_GUTTER);
        assert_eq!(chat_width(0.0), 0.0);
    }

    #[::core::prelude::v1::test]
    fn composer_width_matches_compose_clamp() {
        assert_eq!(composer_width(1600.0), 952.0);
        assert_eq!(composer_width(900.0), 680.0 + 32.0);
        assert_eq!(composer_width(400.0), 312.0 + 32.0);
        assert_eq!(composer_width(0.0), 0.0);
    }

    #[::core::prelude::v1::test]
    fn colors_match_dsh_light_and_dark() {
        let light = DshChatColors::light();
        let dark = DshChatColors::dark();
        assert_eq!(light.background, rgb(0xffffff));
        assert_eq!(light.text, rgb(0x0f1115));
        assert_eq!(light.send, rgb(0x4176e6));
        assert_eq!(light.bubble, rgb(0xedf3fe));
        assert_eq!(dark.background, rgb(0x151517));
        assert_eq!(dark.send, rgb(0x7aaaff));
        assert_eq!(dark.on_send, rgb(0x151517));
        assert_eq!(dark.error, rgb(0xffa2ab));
        assert_eq!(DshChatColors::for_dark(true), DshChatColors::dark());
        assert_eq!(DshChatColors::for_dark(false), DshChatColors::light());
    }
}

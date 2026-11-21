//! Small DSH-chat primitives shared by the conversation components.
//!
//! Every control here is chat-local: it uses the DshChatStyle tokens and never
//! the Studio/Viewer theme, so the AI window keeps its own visual language.

use super::AiWorkspace;
use super::state::CopyOutcome;
use super::style::{DshChatColors, tokens};
use crate::studio;
use aps_ui_foundation::{Language, TextKey};
use gpui_kit::{
    assets::IconName,
    component::{
        Disableable,
        button::{Button, ButtonVariants},
    },
    prelude::*,
    *,
};

/// Compose FontFamily.Monospace equivalent.
pub const MONOSPACE: &str = "monospace";

/// Ellipsized single-run label; text remains selectable where Compose uses a
/// selection container.
pub fn text(value: impl Into<SharedString>, color: Rgba, font_size: f32, line_height: f32) -> Div {
    div()
        .text_size(px(font_size))
        .line_height(px(line_height))
        .text_color(color)
        .whitespace_nowrap()
        .text_ellipsis()
        .child(studio::selectable(value.into()))
}

/// Body-sized label with wrapping.
pub fn body_text(value: impl Into<SharedString>, color: Rgba) -> Div {
    div()
        .text_size(px(tokens::BODY_FONT_SIZE))
        .line_height(px(tokens::BODY_LINE_HEIGHT))
        .text_color(color)
        .child(studio::selectable(value.into()))
}

/// Secondary-sized label with wrapping.
pub fn secondary_text(value: impl Into<SharedString>, color: Rgba) -> Div {
    div()
        .text_size(px(tokens::SECONDARY_FONT_SIZE))
        .line_height(px(tokens::SECONDARY_LINE_HEIGHT))
        .text_color(color)
        .child(studio::selectable(value.into()))
}

/// Round icon button; the tooltip is rendered as an accessibility label because
/// GPUI has no floating tooltip surface.
pub fn icon_button(
    entity: WeakEntity<AiWorkspace>,
    icon: IconName,
    title: impl Into<SharedString>,
    tag: impl Into<ElementId>,
    enabled: bool,
    size: f32,
    background: Rgba,
    tint: Rgba,
    on_click: impl Fn(&mut AiWorkspace, &mut Window, &mut Context<AiWorkspace>) + 'static,
) -> Button {
    let title: SharedString = title.into();
    let tint = if enabled { tint } else { tint.alpha(0.4) };
    let icon_color = tint;
    let handler = std::rc::Rc::new(on_click);
    Button::new(tag)
        .ghost()
        .w(px(size))
        .h(px(size))
        .rounded(px(size / 2.0))
        .p(px(0.))
        .bg(background)
        .text_color(icon_color)
        .disabled(!enabled)
        .accessibility_label(title)
        .on_click(move |_, window, cx| {
            let handler = handler.clone();
            let _ = entity.update(cx, |this, cx| handler(this, window, cx));
        })
        .child(
            div()
                .flex()
                .items_center()
                .justify_center()
                .w_full()
                .h_full()
                .text_size(px(18.))
                .text_color(icon_color)
                .child(icon),
        )
}

/// Copy control with Compose's copied/failed feedback (one second).
pub fn copy_button(
    entity: WeakEntity<AiWorkspace>,
    text: String,
    language: Language,
    tag: impl Into<ElementId>,
    key: String,
    feedback: Option<CopyOutcome>,
    colors: &DshChatColors,
) -> Button {
    let (icon, title) = match feedback {
        Some(CopyOutcome::Copied) => (
            IconName::Check,
            language.text(TextKey::AiChatCopied).to_owned(),
        ),
        Some(CopyOutcome::Failed) => (
            IconName::Close,
            language.text(TextKey::AiChatCopyFailed).to_owned(),
        ),
        None => (
            IconName::Copy,
            language.text(TextKey::AiChatCopy).to_owned(),
        ),
    };
    let tint = if feedback.is_some() {
        colors.send
    } else {
        colors.muted
    };
    icon_button(
        entity,
        icon,
        title,
        tag,
        true,
        32.0,
        rgba(0x00000000),
        tint,
        move |this, _, cx| this.copy_text(&key, &text, cx),
    )
}
/// Icon + secondary label command, used for Retry, Branch and report rows.
pub fn command_button(
    entity: WeakEntity<AiWorkspace>,
    icon: IconName,
    title: impl Into<SharedString>,
    tag: impl Into<ElementId>,
    enabled: bool,
    colors: &DshChatColors,
    on_click: impl Fn(&mut AiWorkspace, &mut Window, &mut Context<AiWorkspace>) + 'static,
) -> Button {
    let title: SharedString = title.into();
    let handler = std::rc::Rc::new(on_click);
    let label = title.clone();
    let tint = if enabled {
        colors.muted
    } else {
        colors.muted.alpha(0.45)
    };
    Button::new(tag)
        .ghost()
        .h(px(28.))
        .px(px(6.))
        .text_color(tint)
        .disabled(!enabled)
        .accessibility_label(title)
        .on_click(move |_, window, cx| {
            let handler = handler.clone();
            let _ = entity.update(cx, |this, cx| handler(this, window, cx));
        })
        .child(
            div()
                .flex()
                .items_center()
                .gap(px(6.))
                .child(div().text_size(px(16.)).text_color(tint).child(icon))
                .child(
                    div()
                        .text_size(px(tokens::SECONDARY_FONT_SIZE))
                        .line_height(px(tokens::SECONDARY_LINE_HEIGHT))
                        .text_color(tint)
                        .whitespace_nowrap()
                        .child(label),
                ),
        )
}

/// Text-only command (Approve / Cancel / Retry).
pub fn text_action(
    entity: WeakEntity<AiWorkspace>,
    title: impl Into<SharedString>,
    tag: impl Into<ElementId>,
    enabled: bool,
    color: Rgba,
    on_click: impl Fn(&mut AiWorkspace, &mut Window, &mut Context<AiWorkspace>) + 'static,
) -> Button {
    let title: SharedString = title.into();
    let handler = std::rc::Rc::new(on_click);
    let color = if enabled { color } else { color.alpha(0.45) };
    let label = title.clone();
    Button::new(tag)
        .ghost()
        .h(px(28.))
        .px(px(8.))
        .rounded(px(6.))
        .text_color(color)
        .disabled(!enabled)
        .accessibility_label(title)
        .on_click(move |_, window, cx| {
            let handler = handler.clone();
            let _ = entity.update(cx, |this, cx| handler(this, window, cx));
        })
        .child(
            div()
                .text_size(px(tokens::SECONDARY_FONT_SIZE))
                .line_height(px(tokens::SECONDARY_LINE_HEIGHT))
                .text_color(color)
                .whitespace_nowrap()
                .child(label),
        )
}
/// Explicit Display coercion for localized format argument arrays.
pub fn display_arg<T: std::fmt::Display>(value: &T) -> &dyn std::fmt::Display {
    value
}

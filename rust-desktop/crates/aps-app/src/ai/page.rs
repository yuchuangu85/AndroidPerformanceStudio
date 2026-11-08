//! Top-level DSH chat surface: sidebar + conversation column.
//!
//! Owns the width breakpoints, the sidebar overlay rule and the page-level key
//! handling (Cmd/Ctrl+N, Cmd/Ctrl+K and the two-Escape stop gesture).

use super::AiWorkspace;
use super::style::{DshChatColors, tokens};
use gpui_kit::{component::ActiveTheme, prelude::*, *};

pub fn render(
    workspace: &mut AiWorkspace,
    window: &mut Window,
    cx: &mut Context<AiWorkspace>,
) -> AnyElement {
    let colors = DshChatColors::for_dark(cx.theme().is_dark());
    let language = workspace.language;
    let width = f32::from(window.viewport_size().width);
    workspace.state.resolve_sidebar(width);

    let sidebar_visible = workspace.state.sidebar_visible;
    let overlay = sidebar_visible && width < tokens::OVERLAY_BREAKPOINT;
    let inline_sidebar = sidebar_visible && width >= tokens::OVERLAY_BREAKPOINT;

    let conversation =
        super::messages::render_conversation(workspace, &colors, language, window, cx);

    let mut body = div().flex_1().min_h_0().w_full().flex();
    if inline_sidebar {
        body = body.child(super::sidebar::render(
            workspace, &colors, language, window, cx,
        ));
    }
    body = body.child(conversation);

    let entity = cx.entity().downgrade();
    let mut root = div()
        .id(ElementId::Name("ai-workspace".into()))
        .size_full()
        .relative()
        .flex()
        .flex_col()
        .bg(colors.background)
        .text_color(colors.text)
        .text_size(px(tokens::BODY_FONT_SIZE))
        .line_height(px(tokens::BODY_LINE_HEIGHT))
        .track_focus(&workspace.page_focus)
        .capture_key_down(cx.listener(|this, event: &KeyDownEvent, window, cx| {
            let key = event.keystroke.key.to_ascii_lowercase();
            let modifiers = event.keystroke.modifiers;
            let secondary = modifiers.secondary(cx);
            if secondary && key == "n" && !event.is_held {
                this.new_chat(window, cx);
                cx.stop_propagation();
                return;
            }
            if secondary && key == "k" && !event.is_held {
                this.focus_search(window, cx);
                cx.stop_propagation();
                return;
            }
            if key == "escape" && !event.is_held {
                if this.state.models_open {
                    this.close_models(cx);
                    cx.stop_propagation();
                    return;
                }
                if this.state.pending_approval.is_some() {
                    this.state.stop_gesture.reset();
                    return;
                }
                if this.state.busy {
                    let now = this.started.elapsed().as_millis() as u64;
                    if this.state.stop_gesture.press(now) {
                        this.stop(cx);
                    }
                    cx.stop_propagation();
                } else {
                    this.state.stop_gesture.reset();
                }
            }
        }))
        .capture_key_up(cx.listener(|this, event: &KeyUpEvent, _, _| {
            if event.keystroke.key.eq_ignore_ascii_case("escape") {
                this.state.stop_gesture.release();
            }
        }));
    root = root.child(body);

    if overlay {
        let sidebar = super::sidebar::render(workspace, &colors, language, window, cx);
        let _ = entity;
        root = root.child(
            div()
                .id(ElementId::Name("ai-sidebar-overlay".into()))
                .absolute()
                .left_0()
                .top_0()
                .h_full()
                .child(sidebar),
        );
    }

    root.into_any_element()
}

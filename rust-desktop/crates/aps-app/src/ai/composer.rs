//! Composer: queued dock, bound context, input panel and model picker.

use super::AiWorkspace;
use super::state::{self, ModelMenuStatus};
use super::style::{DshChatColors, tokens};
use super::widgets;
use aps_ui_foundation::{Language, TextKey};
use gpui_kit::{
    assets::IconName,
    component::{
        Disableable,
        button::{Button, ButtonVariants},
        input::Textarea,
        menu::{DropdownMenu, PopupMenuItem},
    },
    prelude::*,
    *,
};

pub fn render(
    workspace: &mut AiWorkspace,
    colors: &DshChatColors,
    language: Language,
    width: f32,
    _window: &mut Window,
    cx: &mut Context<AiWorkspace>,
) -> AnyElement {
    let entity = cx.entity().downgrade();
    let queued = workspace.state.visible_queue();
    let context_title = workspace.state.context_title.clone();
    let can_send = workspace.state.can_send;
    let busy = workspace.state.busy;
    let pending = workspace.state.pending_approval.is_some();
    let include_source = workspace.state.include_source;
    let can_include_source = workspace.state.can_include_source;
    let stopping = workspace.state.stopping();
    let can_submit = workspace.state.can_submit();
    let model = workspace.state.model.clone();

    let mut column = div().w(px(width)).flex().flex_col().gap(px(8.)).relative();

    if !queued.is_empty() {
        let mut dock = div()
            .id(ElementId::Name("ai-queued-dock".into()))
            .w_full()
            .max_h(px(tokens::QUEUED_DOCK_MAX_HEIGHT))
            .overflow_y_scroll()
            .flex()
            .flex_col()
            .child(
                div()
                    .px(px(16.))
                    .text_size(px(tokens::SECONDARY_FONT_SIZE))
                    .line_height(px(tokens::SECONDARY_LINE_HEIGHT))
                    .text_color(colors.muted)
                    .child(crate::studio::selectable(language.format(
                        TextKey::AiChatQueued,
                        &[("count", widgets::display_arg(&queued.len().to_string()))],
                    ))),
            );
        for (index, item) in queued.iter().enumerate() {
            let edit_entity = entity.clone();
            let remove_entity = entity.clone();
            let question = item.question.clone();
            dock = dock.child(
                div()
                    .w_full()
                    .h(px(36.))
                    .flex()
                    .items_center()
                    .pl(px(16.))
                    .pr(px(8.))
                    .child(
                        div()
                            .flex_1()
                            .min_w_0()
                            .text_size(px(tokens::SECONDARY_FONT_SIZE))
                            .line_height(px(tokens::SECONDARY_LINE_HEIGHT))
                            .text_color(colors.text)
                            .whitespace_nowrap()
                            .text_ellipsis()
                            .child(crate::studio::selectable_keyed(
                                format!("ai-queued-{index}"),
                                question.clone(),
                            )),
                    )
                    .child(widgets::icon_button(
                        edit_entity,
                        IconName::Replace,
                        language.text(TextKey::AiChatEditQueuedQuestion),
                        format!("ai-edit-queued-{index}"),
                        true,
                        28.0,
                        rgba(0x00000000),
                        colors.muted,
                        move |this, window, cx| this.edit_queued(index, window, cx),
                    ))
                    .child(widgets::icon_button(
                        remove_entity,
                        IconName::Delete,
                        language.text(TextKey::AiChatRemoveQueuedQuestion),
                        format!("ai-remove-queued-{index}"),
                        true,
                        28.0,
                        rgba(0x00000000),
                        colors.muted,
                        move |this, _, cx| this.remove_queued(index, cx),
                    )),
            );
        }
        column = column.child(dock);
    }

    if let Some(title) = context_title {
        column = column.child(
            div()
                .w_full()
                .flex()
                .items_center()
                .gap(px(6.))
                .px(px(16.))
                .child(
                    div()
                        .text_size(px(14.))
                        .text_color(colors.muted)
                        .child(IconName::FileText),
                )
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .text_size(px(tokens::SECONDARY_FONT_SIZE))
                        .line_height(px(tokens::SECONDARY_LINE_HEIGHT))
                        .text_color(colors.muted)
                        .whitespace_nowrap()
                        .text_ellipsis()
                        .child(crate::studio::selectable(title)),
                ),
        );
    }

    if !can_send && (!busy || pending) {
        let rebind_entity = entity.clone();
        let source_entity = entity.clone();
        let ordinary_entity = entity.clone();
        let hint = if can_include_source && !include_source {
            language.text(TextKey::AiChatReauthorizeSourceHint)
        } else {
            language.text(TextKey::AiChatContextUnavailableHint)
        };
        let mut hints = div()
            .w_full()
            .flex()
            .flex_col()
            .gap(px(4.))
            .px(px(16.))
            .child(
                div()
                    .text_size(px(tokens::SECONDARY_FONT_SIZE))
                    .line_height(px(tokens::SECONDARY_LINE_HEIGHT))
                    .text_color(colors.error)
                    .child(crate::studio::selectable(hint)),
            );
        if !busy {
            let mut actions = div().flex().items_center().gap(px(4.));
            if can_include_source && !include_source {
                actions = actions.child(widgets::command_button(
                    source_entity,
                    IconName::Eye,
                    language.text(TextKey::AiChatAuthorizeSource),
                    "ai-reauthorize-source",
                    true,
                    colors,
                    |this, _, cx| this.toggle_include_source(true, cx),
                ));
            } else {
                actions = actions.child(widgets::command_button(
                    rebind_entity,
                    IconName::FileText,
                    language.text(TextKey::AiChatRebindContext),
                    "ai-rebind-context",
                    true,
                    colors,
                    |this, _, cx| this.rebind_context(cx),
                ));
            }
            actions = actions.child(widgets::command_button(
                ordinary_entity,
                IconName::Bot,
                language.text(TextKey::AiChatStartOrdinaryChat),
                "ai-start-ordinary-chat",
                true,
                colors,
                |this, window, cx| this.start_ordinary_chat(window, cx),
            ));
            hints = hints.child(actions);
        }
        column = column.child(hints);
    }

    let context_entity = entity.clone();
    let list_entity = entity.clone();
    let send_entity = entity.clone();
    let model_entity = entity.clone();
    let refresh_entity = entity.clone();

    let mut panel = div()
        .w_full()
        .flex()
        .flex_col()
        .bg(colors.composer)
        .rounded(px(tokens::PANEL_RADIUS))
        .border_1()
        .border_color(colors.border)
        .pt(px(14.))
        .pb(px(10.))
        .pl(px(16.))
        .pr(px(12.));

    panel = panel.child(
        div()
            .w_full()
            .min_h(px(tokens::COMPOSER_MIN_HEIGHT))
            .max_h(px(tokens::COMPOSER_MAX_HEIGHT))
            .capture_key_down(cx.listener(|this, event: &KeyDownEvent, window, cx| {
                let key = event.keystroke.key.to_ascii_lowercase();
                let modifiers = event.keystroke.modifiers;
                let secondary = modifiers.secondary(cx);
                if key == "enter"
                    && !modifiers.shift
                    && !modifiers.alt
                    && !secondary
                    && !event.is_held
                {
                    this.send(window, cx);
                    cx.stop_propagation();
                } else if key == "up"
                    && this.state.draft.is_empty()
                    && !modifiers.modified()
                    && !event.is_held
                    && this.recall_last_question(window, cx)
                {
                    cx.stop_propagation();
                }
            }))
            .child(
                Textarea::new(&workspace.composer)
                    .appearance(false)
                    .bordered(false)
                    .h(px(64.))
                    .text_size(px(tokens::BODY_FONT_SIZE))
                    .line_height(px(tokens::USER_LINE_HEIGHT))
                    .text_color(colors.text),
            ),
    );

    let mut actions = div().w_full().flex().items_center();
    actions = actions.child({
        let context_button = widgets::icon_button(
            context_entity,
            IconName::Plus,
            language.text(TextKey::AiChatContext),
            "ai-context-menu",
            true,
            28.0,
            rgba(0x00000000),
            colors.muted,
            |_, _, _| {},
        );
        let include_checked = include_source && can_include_source;
        let include_label = if include_checked {
            format!(
                "{} {}",
                language.text(TextKey::AiChatIncludeAuthorizedSource),
                "\u{2713}"
            )
        } else {
            language
                .text(TextKey::AiChatIncludeAuthorizedSource)
                .to_owned()
        };
        let bind_entity = list_entity.clone();
        let include_entity = list_entity.clone();
        let include_enabled = can_include_source;
        let context_button = context_button.dropdown_menu(move |menu, _, _| {
            let bind_entity = bind_entity.clone();
            let include_entity = include_entity.clone();
            menu.item(
                PopupMenuItem::new(language.text(TextKey::AiChatBindCurrentEvidence)).on_click(
                    move |_, _, cx| {
                        let _ = bind_entity.update(cx, |this, cx| this.rebind_context(cx));
                    },
                ),
            )
            .item(
                PopupMenuItem::new(include_label.clone())
                    .disabled(!include_enabled)
                    .on_click(move |_, _, cx| {
                        let _ = include_entity.update(cx, |this, cx| {
                            this.toggle_include_source(!include_checked, cx)
                        });
                    }),
            )
        });
        context_button
    });
    actions = actions.child(div().flex_1().min_w_0());
    actions = actions.child(model_picker(
        workspace,
        colors,
        language,
        &model,
        &model_entity,
        &refresh_entity,
        cx,
    ));
    let icon = if stopping {
        IconName::Square
    } else {
        IconName::ArrowUp
    };
    let label = if stopping {
        language.text(TextKey::AiChatStop)
    } else if busy {
        language.text(TextKey::AiChatAddToQueue)
    } else {
        language.text(TextKey::AiChatSend)
    };
    let enabled = stopping || can_submit;
    let background = if stopping { colors.text } else { colors.send };
    let tint = if stopping {
        colors.background
    } else {
        colors.on_send
    };
    let handler = if stopping {
        SendAction::Stop
    } else {
        SendAction::Send
    };
    let send_tag = if stopping { "ai-stop" } else { "ai-send" };
    actions = actions.child(
        Button::new(send_tag)
            .ghost()
            .w(px(tokens::SEND_BUTTON_SIZE))
            .h(px(tokens::SEND_BUTTON_SIZE))
            .rounded_full()
            .p(px(0.))
            .bg(if enabled {
                background
            } else {
                background.alpha(0.5)
            })
            .text_color(if enabled { tint } else { tint.alpha(0.5) })
            .disabled(!enabled)
            .accessibility_label(label)
            .on_click(move |_, window, cx| {
                let _ = send_entity.update(cx, |this, cx| match handler {
                    SendAction::Stop => this.stop(cx),
                    SendAction::Send => this.send(window, cx),
                });
            })
            .child(
                div()
                    .flex()
                    .items_center()
                    .justify_center()
                    .w_full()
                    .h_full()
                    .text_size(px(16.))
                    .text_color(tint)
                    .child(icon),
            ),
    );
    panel = panel.child(actions);
    column = column.child(panel);

    // A lightweight model popup avoids a second floating-menu implementation.
    if workspace.state.models_open && !workspace.state.busy {
        column = column.child(model_popup(
            workspace, colors, language, &model, &entity, cx,
        ));
    }

    column.into_any_element()
}

#[derive(Clone, Copy)]
enum SendAction {
    Send,
    Stop,
}

fn model_picker(
    workspace: &mut AiWorkspace,
    colors: &DshChatColors,
    language: Language,
    model: &str,
    entity: &WeakEntity<AiWorkspace>,
    _refresh_entity: &WeakEntity<AiWorkspace>,
    cx: &mut Context<AiWorkspace>,
) -> AnyElement {
    let _ = cx;
    let label = if model.trim().is_empty() {
        language.text(TextKey::AiChatChooseModel).to_owned()
    } else {
        model.to_owned()
    };
    let enabled = !workspace.state.busy;
    let picker_entity = entity.clone();
    Button::new("ai-model")
        .ghost()
        .h(px(28.))
        .px(px(6.))
        .max_w(px(240.))
        .text_color(colors.muted)
        .disabled(!enabled)
        .on_click(move |_, _, cx| {
            let _ = picker_entity.update(cx, |this, cx| {
                if this.state.models_open {
                    this.close_models(cx);
                } else {
                    this.open_models(cx);
                }
            });
        })
        .child(
            div()
                .flex()
                .items_center()
                .gap(px(4.))
                .child(
                    div()
                        .min_w_0()
                        .text_size(px(tokens::SECONDARY_FONT_SIZE))
                        .line_height(px(tokens::SECONDARY_LINE_HEIGHT))
                        .whitespace_nowrap()
                        .text_ellipsis()
                        .child(crate::studio::selectable(label)),
                )
                .child(
                    div()
                        .text_size(px(12.))
                        .text_color(colors.muted)
                        .child(IconName::ChevronDown),
                ),
        )
        .into_any_element()
}

fn model_popup(
    workspace: &mut AiWorkspace,
    colors: &DshChatColors,
    language: Language,
    model: &str,
    entity: &WeakEntity<AiWorkspace>,
    cx: &mut Context<AiWorkspace>,
) -> AnyElement {
    let status = state::model_menu_status(
        workspace.state.models_loading,
        workspace.state.models_error.as_deref(),
        &workspace.state.models,
    );
    let refresh_entity = entity.clone();
    let mut popup = div()
        .id(ElementId::Name("ai-chat-model-menu".into()))
        .absolute()
        .right(px(40.))
        .bottom(px(52.))
        .w(px(tokens::MODEL_MENU_MAX_WIDTH))
        .max_h(px(tokens::MODEL_MENU_MAX_HEIGHT))
        .overflow_y_scroll()
        .flex()
        .flex_col()
        .bg(colors.sidebar)
        .border_1()
        .border_color(colors.border)
        .rounded(px(tokens::SMALL_RADIUS))
        .p(px(8.))
        .child(
            div()
                .w_full()
                .flex()
                .items_center()
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .text_size(px(tokens::SECONDARY_FONT_SIZE))
                        .line_height(px(tokens::SECONDARY_LINE_HEIGHT))
                        .text_color(colors.muted)
                        .whitespace_nowrap()
                        .text_ellipsis()
                        .child(crate::studio::selectable(if model.trim().is_empty() {
                            language.text(TextKey::AiChatChooseModel).to_owned()
                        } else {
                            model.to_owned()
                        })),
                )
                .child(widgets::icon_button(
                    refresh_entity,
                    IconName::RefreshCw,
                    language.text(TextKey::AiChatRefreshModels),
                    "ai-chat-model-retry",
                    !workspace.state.models_loading,
                    28.0,
                    rgba(0x00000000),
                    colors.muted,
                    |this, _, cx| this.refresh_model_catalog(true, cx),
                )),
        );
    match status {
        ModelMenuStatus::Loading => {
            popup = popup.child(model_status_row(
                language.text(TextKey::AiChatLoadingModels),
                colors.muted,
            ));
        }
        ModelMenuStatus::Error(code) => {
            popup = popup.child(model_status_row(
                model_menu_error_label(&code, language),
                colors.error,
            ));
            // The fix for every catalog error lives in AI settings: a missing
            // saved credential, an invalid endpoint or a provider that rejects
            // the model list. Offer the jump instead of a dead-end message.
            popup = popup.child(
                div()
                    .id(ElementId::Name("ai-chat-model-open-settings".into()))
                    .w_full()
                    .h(px(32.))
                    .flex()
                    .items_center()
                    .px(px(8.))
                    .rounded(px(4.))
                    .cursor_pointer()
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.state.models_open = false;
                        this.open_settings(cx);
                    }))
                    .child(
                        div()
                            .text_size(px(tokens::SECONDARY_FONT_SIZE))
                            .line_height(px(tokens::SECONDARY_LINE_HEIGHT))
                            .text_color(colors.text)
                            .child(language.text(TextKey::AiChatOpenAiSettings)),
                    ),
            );
        }
        ModelMenuStatus::Empty => {
            popup = popup.child(model_status_row(
                language.text(TextKey::AiChatNoModels),
                colors.muted,
            ));
        }
        ModelMenuStatus::Ready => {}
    }
    let mut seen = std::collections::BTreeSet::new();
    for candidate in workspace.state.models.clone() {
        if !seen.insert(candidate.clone()) || !state::selectable_model(&candidate) {
            continue;
        }
        let selected = candidate == model;
        let option_entity = entity.clone();
        let option = candidate.clone();
        popup = popup.child(
            div()
                .id(ElementId::Name(
                    format!("ai-chat-model-option-{candidate}").into(),
                ))
                .w_full()
                .h(px(32.))
                .flex()
                .items_center()
                .px(px(8.))
                .rounded(px(4.))
                .cursor_pointer()
                .on_click(cx.listener(move |this, _, _, cx| {
                    this.select_model(option.clone(), cx);
                }))
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .text_size(px(tokens::BODY_FONT_SIZE))
                        .line_height(px(tokens::USER_LINE_HEIGHT))
                        .text_color(colors.text)
                        .whitespace_nowrap()
                        .text_ellipsis()
                        .child(crate::studio::selectable(candidate)),
                )
                .when(selected, |row| {
                    row.child(
                        div()
                            .text_size(px(16.))
                            .text_color(colors.send)
                            .child(IconName::Check),
                    )
                }),
        );
        let _ = option_entity;
    }
    popup.into_any_element()
}

/// Compose model-menu error copy. A missing saved credential is a different
/// problem from an unreachable list, and the user fixes it in AI settings.
fn model_menu_error_label(code: &str, language: Language) -> &'static str {
    match code {
        "E_AI_MODEL_CATALOG_CREDENTIAL" => language.text(TextKey::AiChatModelCredentialMissing),
        "E_AI_MODEL_CATALOG_INVALID_ENDPOINT" => {
            language.text(TextKey::AiChatModelEndpointUnavailable)
        }
        "E_AI_MODEL_CATALOG_HTML_RESPONSE"
        | "E_AI_MODEL_CATALOG_INVALID_RESPONSE"
        | "E_AI_HTTP_STATUS" => language.text(TextKey::AiChatModelListUnavailable),
        _ => language.text(TextKey::AiChatUnableLoadModels),
    }
}

fn model_status_row(label: &str, color: Rgba) -> AnyElement {
    div()
        .w_full()
        .px(px(8.))
        .py(px(8.))
        .text_size(px(tokens::SECONDARY_FONT_SIZE))
        .line_height(px(tokens::SECONDARY_LINE_HEIGHT))
        .text_color(color)
        .child(crate::studio::selectable(label.to_owned()))
        .into_any_element()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[::core::prelude::v1::test]
    fn model_menu_errors_name_the_settings_fix() {
        let english = Language::English;
        let chinese = Language::SimplifiedChinese;
        let credential = model_menu_error_label("E_AI_MODEL_CATALOG_CREDENTIAL", english);
        assert!(credential.contains("credential"), "{credential}");
        assert!(credential.contains("AI settings"), "{credential}");
        let credential_zh = model_menu_error_label("E_AI_MODEL_CATALOG_CREDENTIAL", chinese);
        assert!(credential_zh.contains("凭据"), "{credential_zh}");
        assert!(credential_zh.contains("保存并应用"), "{credential_zh}");
        assert_eq!(
            model_menu_error_label("E_AI_MODEL_CATALOG_INVALID_ENDPOINT", english),
            english.text(TextKey::AiChatModelEndpointUnavailable)
        );
        for code in [
            "E_AI_MODEL_CATALOG_HTML_RESPONSE",
            "E_AI_MODEL_CATALOG_INVALID_RESPONSE",
            "E_AI_HTTP_STATUS",
        ] {
            assert_eq!(
                model_menu_error_label(code, english),
                english.text(TextKey::AiChatModelListUnavailable)
            );
        }
        // A transport failure keeps the honest, generic code path.
        assert_eq!(
            model_menu_error_label("E_AI_MODEL_CATALOG_NETWORK", english),
            english.text(TextKey::AiChatUnableLoadModels)
        );
    }
}

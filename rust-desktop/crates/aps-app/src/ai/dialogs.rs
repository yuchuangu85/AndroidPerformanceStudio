//! Delete / rename / archive confirmation dialogs and the evidence detail.

use super::AiWorkspace;
use super::style::{DshChatColors, tokens};
use super::widgets;
use aps_ui_foundation::{Language, TextKey};
use gpui_kit::{
    component::{ActiveTheme, input::Input},
    prelude::*,
    *,
};

pub fn render(
    workspace: &mut AiWorkspace,
    window: &mut Window,
    cx: &mut Context<AiWorkspace>,
) -> Option<AnyElement> {
    let colors = DshChatColors::for_dark(cx.theme().is_dark());
    let language = workspace.language;
    let entity = cx.entity().downgrade();
    let dialogs = workspace.state.dialogs.clone();
    if dialogs.rename.is_none()
        && dialogs.delete.is_none()
        && dialogs.archive.is_none()
        && dialogs.evidence.is_none()
    {
        return None;
    }

    let close_entity = entity.clone();
    let scrim = div()
        .id(ElementId::Name("ai-dialog-scrim".into()))
        .absolute()
        .inset_0()
        .bg(rgba(0x00000066))
        .on_click(move |_, _, cx| {
            let _ = close_entity.update(cx, |this, cx| this.close_dialogs(cx));
        });

    let card = if let Some(renaming) = dialogs.rename.clone() {
        rename_dialog(workspace, &colors, language, &renaming, &entity, cx)
    } else if let Some(id) = dialogs.delete.clone() {
        delete_dialog(workspace, &colors, language, &id, &entity, window, cx)
    } else if let Some(id) = dialogs.archive.clone() {
        archive_dialog(workspace, &colors, language, &id, &entity, window, cx)
    } else if let Some(id) = dialogs.evidence.clone() {
        evidence_dialog(workspace, &colors, language, &id, &entity, cx)
    } else {
        return None;
    };

    Some(
        div()
            .id(ElementId::Name("ai-dialog-overlay".into()))
            .absolute()
            .inset_0()
            .flex()
            .items_center()
            .justify_center()
            .child(scrim)
            .child(card)
            .into_any_element(),
    )
}

fn card(colors: &DshChatColors) -> Stateful<Div> {
    div()
        .id(ElementId::Name("ai-dialog-card".into()))
        .w(px(440.))
        .max_h(relative(0.82))
        .flex()
        .flex_col()
        .gap(px(12.))
        .p(px(16.))
        .bg(colors.background)
        .border_1()
        .border_color(colors.border)
        .rounded(px(tokens::SMALL_RADIUS))
        .text_color(colors.text)
        .on_mouse_down(MouseButton::Left, |_, _, cx| cx.stop_propagation())
}

fn title(value: &str, colors: &DshChatColors) -> AnyElement {
    div()
        .text_size(px(16.))
        .line_height(px(22.))
        .font_weight(FontWeight::SEMIBOLD)
        .text_color(colors.text)
        .child(crate::studio::selectable(value.to_owned()))
        .into_any_element()
}

fn body(value: &str, colors: &DshChatColors) -> AnyElement {
    div()
        .text_size(px(tokens::SECONDARY_FONT_SIZE))
        .line_height(px(tokens::SECONDARY_LINE_HEIGHT))
        .text_color(colors.muted)
        .child(crate::studio::selectable(value.to_owned()))
        .into_any_element()
}

fn buttons(children: Vec<AnyElement>) -> AnyElement {
    let mut row = div()
        .w_full()
        .flex()
        .items_center()
        .justify_end()
        .gap(px(8.));
    for child in children {
        row = row.child(child);
    }
    row.into_any_element()
}

fn rename_dialog(
    workspace: &mut AiWorkspace,
    colors: &DshChatColors,
    language: Language,
    _renaming: &super::state::RenamingConversation,
    entity: &WeakEntity<AiWorkspace>,
    cx: &mut Context<AiWorkspace>,
) -> AnyElement {
    let title_text = workspace.rename_input.read(cx).value().to_string();
    let can_rename = !title_text.trim().is_empty();
    let cancel_entity = entity.clone();
    let confirm_entity = entity.clone();
    card(colors)
        .child(title(
            language.text(TextKey::AiChatRenameConversation),
            colors,
        ))
        .child(
            div()
                .w_full()
                .h(px(32.))
                .child(Input::new(&workspace.rename_input)),
        )
        .child(buttons(vec![
            widgets::text_action(
                cancel_entity,
                language.text(TextKey::AiChatCancel),
                "ai-rename-cancel",
                true,
                colors.muted,
                |this, _, cx| this.close_dialogs(cx),
            )
            .into_any_element(),
            widgets::text_action(
                confirm_entity,
                language.text(TextKey::AiChatRename),
                "ai-rename-confirm",
                can_rename,
                colors.send,
                |this, _, cx| this.confirm_rename(cx),
            )
            .into_any_element(),
        ]))
        .into_any_element()
}

fn delete_dialog(
    workspace: &mut AiWorkspace,
    colors: &DshChatColors,
    language: Language,
    id: &str,
    entity: &WeakEntity<AiWorkspace>,
    _window: &mut Window,
    _cx: &mut Context<AiWorkspace>,
) -> AnyElement {
    let session_title = workspace
        .state
        .sessions
        .iter()
        .find(|session| session.id == id)
        .map(|session| session.title.clone())
        .unwrap_or_default();
    let cancel_entity = entity.clone();
    let confirm_entity = entity.clone();
    card(colors)
        .child(title(
            language.text(TextKey::AiChatDeleteConversation),
            colors,
        ))
        .child(body(&session_title, colors))
        .child(body(
            language.text(TextKey::AiChatDeleteConversationBody),
            colors,
        ))
        .child(buttons(vec![
            widgets::text_action(
                cancel_entity,
                language.text(TextKey::AiChatCancel),
                "ai-delete-cancel",
                true,
                colors.muted,
                |this, _, cx| this.close_dialogs(cx),
            )
            .into_any_element(),
            widgets::text_action(
                confirm_entity,
                language.text(TextKey::AiChatDelete),
                "ai-delete-confirm",
                true,
                colors.error,
                |this, window, cx| this.confirm_delete(window, cx),
            )
            .into_any_element(),
        ]))
        .into_any_element()
}

fn archive_dialog(
    workspace: &mut AiWorkspace,
    colors: &DshChatColors,
    language: Language,
    id: &str,
    entity: &WeakEntity<AiWorkspace>,
    _window: &mut Window,
    _cx: &mut Context<AiWorkspace>,
) -> AnyElement {
    let _ = (id, workspace);
    let cancel_entity = entity.clone();
    let confirm_entity = entity.clone();
    card(colors)
        .child(title(
            language.text(TextKey::AiChatArchiveRunningConversation),
            colors,
        ))
        .child(body(
            language.text(TextKey::AiChatArchiveRunningBody),
            colors,
        ))
        .child(buttons(vec![
            widgets::text_action(
                cancel_entity,
                language.text(TextKey::AiChatCancel),
                "ai-archive-cancel",
                true,
                colors.muted,
                |this, _, cx| this.close_dialogs(cx),
            )
            .into_any_element(),
            widgets::text_action(
                confirm_entity,
                language.text(TextKey::AiChatStopAndArchive),
                "ai-archive-confirm",
                true,
                colors.send,
                |this, window, cx| this.confirm_archive(window, cx),
            )
            .into_any_element(),
        ]))
        .into_any_element()
}

fn evidence_dialog(
    workspace: &mut AiWorkspace,
    colors: &DshChatColors,
    language: Language,
    id: &str,
    entity: &WeakEntity<AiWorkspace>,
    cx: &mut Context<AiWorkspace>,
) -> AnyElement {
    let _ = cx;
    let evidence = workspace
        .bound_context
        .as_ref()
        .and_then(|request| request.evidence.iter().find(|evidence| evidence.id == id));
    let close_entity = entity.clone();
    let mut card = card(colors)
        .child(title(language.text(TextKey::AiChatEvidenceSources), colors))
        .max_h(relative(0.82))
        .overflow_y_scroll();
    match evidence {
        None => {
            card = card
                .child(body("E_EVIDENCE_CONTEXT_UNAVAILABLE", colors))
                .child(body(id, colors));
        }
        Some(evidence) => {
            let payload = aps_json::to_vec(
                &evidence.payload,
                aps_json::Limits {
                    max_bytes: 64 * 1024,
                    max_depth: 16,
                    max_nodes: 10_000,
                    max_string_bytes: 32 * 1024,
                    max_collection_items: 512,
                },
            )
            .ok()
            .map(|bytes| String::from_utf8_lossy(&bytes).into_owned())
            .unwrap_or_else(|| "{}".to_owned());
            let identity = workspace
                .bound_context
                .as_ref()
                .map(|request| {
                    format!(
                        "{} · {} · {}",
                        request.session_id, request.profiler, request.scope
                    )
                })
                .unwrap_or_default();
            card = card
                .child(mono_row(&evidence.id, colors))
                .child(mono_row(&evidence.kind, colors))
                .child(body(&evidence.summary, colors))
                .child(mono_row(&payload, colors))
                .child(body(&identity, colors));
        }
    }
    card.child(buttons(vec![
        widgets::text_action(
            close_entity,
            language.text(TextKey::AiChatClose),
            "ai-evidence-close",
            true,
            colors.send,
            |this, _, cx| this.close_dialogs(cx),
        )
        .into_any_element(),
    ]))
    .into_any_element()
}

fn mono_row(value: &str, colors: &DshChatColors) -> AnyElement {
    div()
        .w_full()
        .text_size(px(tokens::CODE_FONT_SIZE))
        .line_height(px(tokens::CODE_LINE_HEIGHT))
        .font_family(super::widgets::MONOSPACE)
        .text_color(colors.text)
        .child(crate::studio::selectable(value.to_owned()))
        .into_any_element()
}

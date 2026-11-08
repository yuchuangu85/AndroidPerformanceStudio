//! Sidebar: header, search, Recent/Archived session rows and row menus.

use super::AiWorkspace;
use super::style::{DshChatColors, tokens};
use super::widgets;
use aps_ui_foundation::{Language, TextKey};
use gpui_kit::{
    assets::IconName,
    component::{
        input::Input,
        menu::{DropdownMenu, PopupMenuItem},
    },
    prelude::*,
    *,
};

pub fn render(
    workspace: &mut AiWorkspace,
    colors: &DshChatColors,
    language: Language,
    _window: &mut Window,
    cx: &mut Context<AiWorkspace>,
) -> AnyElement {
    let entity = cx.entity().downgrade();
    let sessions = workspace.state.visible_sessions();
    let showing_archived = workspace.state.showing_archived;
    let selected = workspace.state.selected_conversation_id.clone();

    let mut sidebar = div()
        .id(ElementId::Name("ai-chat-sidebar".into()))
        .w(px(tokens::SIDEBAR_WIDTH))
        .flex_shrink_0()
        .h_full()
        .flex()
        .flex_col()
        .bg(colors.sidebar)
        .px(px(12.))
        .child(
            div()
                .w_full()
                .h(px(tokens::SIDEBAR_HEADER_HEIGHT))
                .flex()
                .items_center()
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .text_size(px(tokens::BODY_FONT_SIZE))
                        .line_height(px(tokens::USER_LINE_HEIGHT))
                        .text_color(colors.text)
                        .whitespace_nowrap()
                        .text_ellipsis()
                        .child(crate::studio::selectable(
                            language.text(TextKey::AiChatConversations),
                        )),
                )
                .child({
                    let entity = entity.clone();
                    widgets::icon_button(
                        entity,
                        IconName::PanelLeftClose,
                        language.text(TextKey::AiChatCloseSidebar),
                        "ai-sidebar-close",
                        true,
                        32.0,
                        rgba(0x00000000),
                        colors.text,
                        |this, _, cx| this.set_sidebar_visible(false, cx),
                    )
                })
                .child({
                    let entity = entity.clone();
                    widgets::icon_button(
                        entity,
                        IconName::Plus,
                        language.text(TextKey::AiChatNewChat),
                        "ai-new-chat",
                        true,
                        32.0,
                        rgba(0x00000000),
                        colors.text,
                        |this, window, cx| this.new_chat(window, cx),
                    )
                }),
        )
        .child(
            div()
                .id(ElementId::Name("ai-search-field".into()))
                .w_full()
                .h(px(tokens::SEARCH_FIELD_HEIGHT))
                .flex()
                .items_center()
                .gap(px(8.))
                .px(px(10.))
                .rounded(px(tokens::SMALL_RADIUS))
                .bg(colors.background)
                .border_1()
                .border_color(colors.border)
                .child(
                    div()
                        .text_size(px(16.))
                        .text_color(colors.muted)
                        .child(IconName::Search),
                )
                .child(
                    div().flex_1().min_w_0().child(
                        Input::new(&workspace.search)
                            .appearance(false)
                            .bordered(false),
                    ),
                ),
        )
        .child(
            div().w_full().pt(px(16.)).pb(px(4.)).child(
                div()
                    .px(px(8.))
                    .py(px(4.))
                    .text_size(px(tokens::SECONDARY_FONT_SIZE))
                    .line_height(px(tokens::SECONDARY_LINE_HEIGHT))
                    .text_color(colors.muted)
                    .child(crate::studio::selectable(language.text(
                        if showing_archived {
                            TextKey::AiChatArchived
                        } else {
                            TextKey::AiChatRecent
                        },
                    ))),
            ),
        )
        .child({
            let mut list = div()
                .id(ElementId::Name("ai-session-list".into()))
                .flex_1()
                .min_h_0()
                .w_full()
                .overflow_y_scroll()
                .flex()
                .flex_col()
                .gap(px(4.));
            if sessions.is_empty() {
                list = list.child(
                    div()
                        .p(px(8.))
                        .text_size(px(tokens::SECONDARY_FONT_SIZE))
                        .line_height(px(tokens::SECONDARY_LINE_HEIGHT))
                        .text_color(colors.muted)
                        .child(crate::studio::selectable(
                            language.text(TextKey::AiChatNoConversations),
                        )),
                );
            }
            for session in &sessions {
                list = list.child(session_row(
                    colors,
                    language,
                    session,
                    selected.as_deref() == Some(session.id.as_str()),
                    &entity,
                    cx,
                ));
            }
            list
        })
        .child({
            let archive_entity = entity.clone();
            let settings_entity = entity.clone();
            div()
                .w_full()
                .min_h(px(54.))
                .flex()
                .items_center()
                .child(div().flex_1().min_w_0().child(widgets::command_button(
                    archive_entity,
                    IconName::Archive,
                    language.text(if showing_archived {
                        TextKey::AiChatBackToRecent
                    } else {
                        TextKey::AiChatArchived
                    }),
                    "ai-show-archived",
                    true,
                    colors,
                    move |this, _, cx| this.show_archived(!showing_archived, cx),
                )))
                .child(widgets::icon_button(
                    settings_entity,
                    IconName::Settings,
                    language.text(TextKey::AiChatOpenSettings),
                    "ai-open-settings",
                    true,
                    32.0,
                    rgba(0x00000000),
                    colors.muted,
                    |this, _, cx| this.open_settings(cx),
                ))
        });

    sidebar.into_any_element()
}

fn session_row(
    colors: &DshChatColors,
    language: Language,
    session: &aps_ai::runtime::SessionSummary,
    selected: bool,
    entity: &WeakEntity<AiWorkspace>,
    cx: &mut Context<AiWorkspace>,
) -> AnyElement {
    let id = session.id.clone();
    let pinned = session.pinned;
    let archived = session.archived;
    let running = session.status.is_some_and(|status| !status.terminal());
    let title = if session.title.trim().is_empty() {
        language.text(TextKey::AiChatNewChat).to_owned()
    } else {
        session.title.clone()
    };
    let subtitle = format!(
        "{}  ·  {}",
        super::state::format_session_date(session.updated_at_millis),
        session.turn_count
    );

    let mut title_row = div().flex().items_center().gap(px(4.));
    if pinned {
        title_row = title_row.child(
            div()
                .text_size(px(12.))
                .text_color(colors.muted)
                .child(IconName::StarFill),
        );
    }
    title_row = title_row.child(
        div()
            .min_w_0()
            .text_size(px(tokens::BODY_FONT_SIZE))
            .line_height(px(tokens::USER_LINE_HEIGHT))
            .text_color(colors.text)
            .whitespace_nowrap()
            .text_ellipsis()
            .child(crate::studio::selectable(title)),
    );

    let menu_entity = entity.clone();
    let menu_id = id.clone();
    let menu_language = language;
    let menu_colors = *colors;
    let menu = widgets::icon_button(
        menu_entity.clone(),
        IconName::Ellipsis,
        language.text(TextKey::AiChatConversations),
        format!("ai-session-menu-{id}"),
        true,
        28.0,
        rgba(0x00000000),
        colors.muted,
        |_, _, _| {},
    )
    .dropdown_menu(move |menu, _, _| {
        let pin_label = if pinned {
            menu_language.text(TextKey::AiChatUnpin)
        } else {
            menu_language.text(TextKey::AiChatPin)
        };
        let archive_label = if archived {
            menu_language.text(TextKey::AiChatUnarchive)
        } else {
            menu_language.text(TextKey::AiChatArchive)
        };
        let pin_entity = menu_entity.clone();
        let pin_id = menu_id.clone();
        let rename_entity = menu_entity.clone();
        let rename_id = menu_id.clone();
        let fork_entity = menu_entity.clone();
        let fork_id = menu_id.clone();
        let archive_entity = menu_entity.clone();
        let archive_id = menu_id.clone();
        let copy_entity = menu_entity.clone();
        let copy_id = menu_id.clone();
        let delete_entity = menu_entity.clone();
        let delete_id = menu_id.clone();
        let _ = menu_colors;
        menu.item(PopupMenuItem::new(pin_label).on_click(move |_, _, cx| {
            let _ = pin_entity.update(cx, |this, cx| {
                this.pin_conversation(pin_id.clone(), !pinned, cx)
            });
        }))
        .item(
            PopupMenuItem::new(menu_language.text(TextKey::AiChatRename)).on_click(
                move |_, window, cx| {
                    let _ = rename_entity.update(cx, |this, cx| {
                        this.open_rename_dialog(rename_id.clone(), window, cx)
                    });
                },
            ),
        )
        .item(
            PopupMenuItem::new(menu_language.text(TextKey::AiChatBranchConversation)).on_click(
                move |_, _, cx| {
                    let _ = fork_entity.update(cx, |this, cx| {
                        this.fork_conversation(fork_id.clone(), None, cx)
                    });
                },
            ),
        )
        .item(
            PopupMenuItem::new(archive_label).on_click(move |_, window, cx| {
                let _ = archive_entity.update(cx, |this, cx| {
                    if !archived && running {
                        this.open_archive_dialog(archive_id.clone(), cx);
                    } else {
                        this.archive_conversation(archive_id.clone(), !archived, window, cx);
                    }
                });
            }),
        )
        .item(
            PopupMenuItem::new(menu_language.text(TextKey::AiChatCopySessionId)).on_click(
                move |_, _, cx| {
                    let key = format!("session-id-{}", copy_id);
                    let value = copy_id.clone();
                    let _ = copy_entity.update(cx, |this, cx| this.copy_text(&key, &value, cx));
                },
            ),
        )
        .item(
            PopupMenuItem::new(menu_language.text(TextKey::AiChatDelete)).on_click(
                move |_, _, cx| {
                    let _ = delete_entity.update(cx, |this, cx| {
                        this.open_delete_dialog(delete_id.clone(), cx)
                    });
                },
            ),
        )
    });

    div()
        .id(ElementId::Name(format!("ai-session-{id}").into()))
        .w_full()
        .flex()
        .items_center()
        .px(px(10.))
        .py(px(7.))
        .rounded(px(tokens::SMALL_RADIUS))
        .when(selected, |row| row.bg(colors.bubble))
        .cursor_pointer()
        .on_click(
            cx.listener(move |this, _, window, cx| {
                this.select_conversation(id.clone(), window, cx)
            }),
        )
        .child(
            div()
                .flex_1()
                .min_w_0()
                .flex()
                .flex_col()
                .child(title_row)
                .child(
                    div()
                        .text_size(px(tokens::SECONDARY_FONT_SIZE))
                        .line_height(px(tokens::SECONDARY_LINE_HEIGHT))
                        .text_color(colors.muted)
                        .whitespace_nowrap()
                        .text_ellipsis()
                        .child(crate::studio::selectable(subtitle)),
                ),
        )
        .child(menu)
        .into_any_element()
}

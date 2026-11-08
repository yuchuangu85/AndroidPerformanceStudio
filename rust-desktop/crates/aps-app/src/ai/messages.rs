//! Conversation header, message list, preflight card, activity and scroll controls.

use super::AiWorkspace;
use super::markdown::render_safe_text;
use super::state;
use super::style::{self, DshChatColors, tokens};
use super::widgets;
use aps_ui_foundation::{Language, TextKey};
use gpui_kit::{assets::IconName, prelude::*, *};
use std::collections::BTreeSet;

pub fn render_conversation(
    workspace: &mut AiWorkspace,
    colors: &DshChatColors,
    language: Language,
    window: &mut Window,
    cx: &mut Context<AiWorkspace>,
) -> AnyElement {
    let entity = cx.entity().downgrade();
    let viewport = f32::from(window.viewport_size().width);
    let sidebar_width = if workspace.state.sidebar_visible && viewport >= tokens::OVERLAY_BREAKPOINT
    {
        tokens::SIDEBAR_WIDTH
    } else {
        0.0
    };
    let available = (viewport - sidebar_width).max(0.0);
    let chat_width =
        style::chat_width(available).min((available - tokens::NAVIGATION_GUTTER * 2.0).max(0.0));
    let composer_width = style::composer_width(available);
    let title = workspace
        .state
        .selected_title()
        .unwrap_or_default()
        .to_owned();
    let sidebar_visible = workspace.state.sidebar_visible;

    let mut header = div()
        .w_full()
        .h(px(tokens::HEADER_HEIGHT))
        .flex_shrink_0()
        .flex()
        .items_center()
        .px(px(16.));
    if !sidebar_visible {
        let open_entity = entity.clone();
        let new_entity = entity.clone();
        header = header
            .child(widgets::icon_button(
                open_entity,
                IconName::PanelLeft,
                language.text(TextKey::AiChatOpenSidebar),
                "ai-sidebar-open",
                true,
                32.0,
                rgba(0x00000000),
                colors.text,
                |this, _, cx| this.set_sidebar_visible(true, cx),
            ))
            .child(widgets::icon_button(
                new_entity,
                IconName::Plus,
                language.text(TextKey::AiChatNewChat),
                "ai-new-chat",
                true,
                32.0,
                rgba(0x00000000),
                colors.text,
                |this, window, cx| this.new_chat(window, cx),
            ));
    }
    header = header.child(
        div()
            .flex_1()
            .min_w_0()
            .px(px(8.))
            .text_size(px(tokens::BODY_FONT_SIZE))
            .line_height(px(tokens::USER_LINE_HEIGHT))
            .text_color(colors.text)
            .whitespace_nowrap()
            .text_ellipsis()
            .child(crate::studio::selectable(title)),
    );

    let message_list =
        render_message_list(workspace, colors, language, chat_width, &entity, window, cx);
    let composer = super::composer::render(workspace, colors, language, composer_width, window, cx);

    div()
        .flex_1()
        .min_w_0()
        .h_full()
        .flex()
        .flex_col()
        .items_center()
        .child(header)
        .child(message_list)
        .child(div().w(px(composer_width)).pb(px(16.)).child(composer))
        .into_any_element()
}

fn render_message_list(
    workspace: &mut AiWorkspace,
    colors: &DshChatColors,
    language: Language,
    chat_width: f32,
    entity: &WeakEntity<AiWorkspace>,
    window: &mut Window,
    cx: &mut Context<AiWorkspace>,
) -> AnyElement {
    let key = super::MessageKey {
        turn_count: workspace.state.turns.len(),
        conversation_id: workspace.state.selected_conversation_id.clone(),
        activity_len: workspace.state.activity.len(),
        status: workspace.state.status,
        error: workspace.state.error.clone(),
        pending_approval: workspace
            .state
            .pending_approval
            .as_ref()
            .map(|approval| approval.run_id.clone()),
        busy: workspace.state.busy,
    };
    let changed = workspace.message_key != key;
    workspace.message_key = key;
    if changed && workspace.state.follow_latest {
        workspace.message_scroll.scroll_to_bottom();
    } else if !changed {
        let offset = f32::from(workspace.message_scroll.offset().y);
        let max = f32::from(workspace.message_scroll.max_offset().y);
        if max > 0.0 {
            workspace.state.follow_latest = (-offset) >= max - 4.0;
        }
    }
    let _ = window;

    let mut content = div()
        .w(px(chat_width))
        .flex()
        .flex_col()
        .gap(px(tokens::MESSAGE_GAP))
        .pt(px(tokens::MESSAGE_TOP_PADDING))
        .pb(px(tokens::MESSAGE_BOTTOM_PADDING));

    if workspace.state.turns.is_empty() && workspace.state.pending_approval.is_none() {
        content = content.child(
            div()
                .w_full()
                .flex()
                .flex_col()
                .items_center()
                .pt(px(tokens::EMPTY_TOP_PADDING))
                .pb(px(tokens::EMPTY_BOTTOM_PADDING))
                .child(
                    div()
                        .w(px(tokens::EMPTY_SPARKLE_SIZE))
                        .h(px(tokens::EMPTY_SPARKLE_SIZE))
                        .flex_shrink_0()
                        .text_size(px(tokens::EMPTY_SPARKLE_SIZE))
                        .text_color(colors.send)
                        .child(IconName::Bot),
                )
                .child(div().h(px(20.)))
                .child(
                    div()
                        .text_size(px(tokens::TITLE_FONT_SIZE))
                        .line_height(px(tokens::TITLE_LINE_HEIGHT))
                        .font_weight(FontWeight::MEDIUM)
                        .text_color(colors.text)
                        .child(crate::studio::selectable(
                            language.text(TextKey::AiChatEmptyPrompt),
                        )),
                ),
        );
    }

    let conversation_id = workspace.state.selected_conversation_id.clone();
    let retry_enabled = !workspace.state.busy && workspace.state.can_send;
    for turn in workspace.state.turns.clone() {
        content = content.child(turn_view(
            workspace,
            colors,
            language,
            &turn,
            conversation_id.as_deref(),
            retry_enabled,
            entity,
            cx,
        ));
    }

    if let Some(approval) = workspace.state.pending_approval.clone() {
        content = content.child(approval_card(
            &approval,
            colors,
            language,
            entity,
            workspace.state.history_expanded,
        ));
    }

    if !workspace.state.activity.is_empty() {
        content = content.child(activity_card(workspace, colors, language, entity));
    }

    if workspace.state.busy && workspace.state.pending_approval.is_none() {
        content = content.child(
            div()
                .text_size(px(tokens::SECONDARY_FONT_SIZE))
                .line_height(px(tokens::SECONDARY_LINE_HEIGHT))
                .text_color(colors.muted)
                .child(crate::studio::selectable(state::run_status_label(
                    language,
                    workspace
                        .state
                        .status
                        .unwrap_or(aps_ai::runtime::RunStatus::Running),
                ))),
        );
    }

    if let Some(error) = workspace.state.error.clone() {
        content = content.child(error_row(&error, colors, language, entity, retry_enabled));
    }

    content = content.child(div().h(px(8.)));

    let scroll = workspace.message_scroll.clone();
    let can_scroll_top = f32::from(scroll.offset().y).abs() > 4.0;
    let can_scroll_bottom =
        f32::from(scroll.offset().y).abs() < f32::from(scroll.max_offset().y) - 4.0;
    let top_entity = entity.clone();
    let bottom_entity = entity.clone();
    let mut controls = div()
        .absolute()
        .right(px(tokens::SCROLL_CONTROLS_INSET))
        .bottom(px(tokens::SCROLL_CONTROLS_INSET))
        .flex()
        .flex_col()
        .gap(px(tokens::SCROLL_BUTTON_GAP))
        .id(ElementId::Name("ai-scroll-controls".into()));
    if can_scroll_top {
        controls = controls.child(
            div()
                .rounded_full()
                .border_1()
                .border_color(colors.border)
                .child(widgets::icon_button(
                    top_entity,
                    IconName::ArrowUp,
                    language.text(TextKey::AiChatBackToTop),
                    "ai-scroll-top",
                    true,
                    tokens::SCROLL_BUTTON_SIZE,
                    colors.sidebar,
                    colors.text,
                    |this, _, cx| this.scroll_to_top(cx),
                )),
        );
    }
    if can_scroll_bottom {
        controls = controls.child(
            div()
                .rounded_full()
                .border_1()
                .border_color(colors.border)
                .child(widgets::icon_button(
                    bottom_entity,
                    IconName::ArrowDown,
                    language.text(TextKey::AiChatBackToLatest),
                    "ai-scroll-bottom",
                    true,
                    tokens::SCROLL_BUTTON_SIZE,
                    colors.sidebar,
                    colors.text,
                    |this, _, cx| this.scroll_to_latest(cx),
                )),
        );
    }

    div()
        .id(ElementId::Name("ai-chat-messages".into()))
        .relative()
        .flex_1()
        .min_h_0()
        .w_full()
        .child(
            div()
                .id(ElementId::Name("ai-chat-history".into()))
                .size_full()
                .overflow_y_scroll()
                .track_scroll(&scroll)
                .flex()
                .flex_col()
                .items_center()
                .child(content),
        )
        .child(controls)
        .into_any_element()
}

fn copy_key(prefix: &str, run_id: &str) -> String {
    format!("{prefix}-{run_id}")
}

fn feedback(workspace: &AiWorkspace, prefix: &str, run_id: &str) -> Option<state::CopyOutcome> {
    workspace.copy_feedback(&copy_key(prefix, run_id))
}

fn turn_view(
    workspace: &mut AiWorkspace,
    colors: &DshChatColors,
    language: Language,
    turn: &aps_ai::runtime::TurnRecord,
    conversation_id: Option<&str>,
    retry_enabled: bool,
    entity: &WeakEntity<AiWorkspace>,
    cx: &mut Context<AiWorkspace>,
) -> AnyElement {
    let run_id = turn.run_id.clone();
    let mut column = div().w_full().flex().flex_col().gap(px(18.));
    let text_style = |element: Div| {
        element
            .text_size(px(tokens::BODY_FONT_SIZE))
            .line_height(px(tokens::USER_LINE_HEIGHT))
    };
    if let Some(question) = turn.question.clone() {
        let q_copy_entity = entity.clone();
        let q_edit_entity = entity.clone();
        let q_copy_id = run_id.clone();
        let q_edit_question = question.clone();
        let q_branch_id = conversation_id.map(str::to_owned);
        let q_run = run_id.clone();
        let bubble = div()
            .w_full()
            .flex()
            .flex_col()
            .items_end()
            .child(
                div()
                    .max_w(relative(tokens::USER_BUBBLE_MAX_FRACTION))
                    .bg(colors.bubble)
                    .rounded(px(tokens::EXTRA_LARGE_RADIUS))
                    .px(px(tokens::USER_BUBBLE_PADDING_X))
                    .py(px(tokens::USER_BUBBLE_PADDING_Y))
                    .child(text_style(div()).text_color(colors.text).child(
                        crate::studio::selectable_keyed(format!("ai-user-{run_id}"), question),
                    )),
            )
            .child(
                div()
                    .flex()
                    .items_center()
                    .child(widgets::copy_button(
                        q_copy_entity,
                        q_edit_question.clone(),
                        language,
                        format!("ai-copy-question-{q_copy_id}"),
                        copy_key("question", &q_copy_id),
                        feedback(workspace, "question", &q_copy_id),
                        colors,
                    ))
                    .child(widgets::icon_button(
                        q_edit_entity,
                        IconName::Replace,
                        language.text(TextKey::AiChatEditQuestion),
                        format!("ai-edit-question-{run_id}"),
                        true,
                        32.0,
                        rgba(0x00000000),
                        colors.muted,
                        move |this, window, cx| this.edit_question(&q_edit_question, window, cx),
                    ))
                    .when_some(q_branch_id, |row, conversation| {
                        let branch_entity = entity.clone();
                        row.child(widgets::icon_button(
                            branch_entity,
                            IconName::Network,
                            language.text(TextKey::AiChatBranchFromHere),
                            format!("ai-branch-question-{q_run}"),
                            true,
                            32.0,
                            rgba(0x00000000),
                            colors.muted,
                            move |this, _, cx| {
                                this.fork_conversation(
                                    conversation.clone(),
                                    Some(q_run.clone()),
                                    cx,
                                )
                            },
                        ))
                    }),
            );
        column = column.child(bubble);
    }
    if let Some(result) = turn.result.clone() {
        let summary = render_safe_text(
            &result.summary,
            colors,
            language,
            &format!("ai-summary-{run_id}"),
            entity,
            &workspace.state.code_wrap,
        );
        let mut assistant = div().w_full().flex().flex_col().gap(px(12.)).child(summary);
        for finding in &result.findings {
            assistant = assistant.child(finding_view(
                workspace, colors, language, finding, entity, cx,
            ));
        }
        let mut hashes: Vec<&String> = result_artifact_hashes(turn);
        hashes.sort();
        hashes.dedup();
        for hash in hashes {
            let hash = hash.clone();
            assistant = assistant.child(widgets::command_button(
                entity.clone(),
                IconName::ExternalLink,
                language.text(TextKey::AiChatOpenVerifiedReport),
                format!("ai-artifact-{hash}"),
                true,
                colors,
                move |this, window, cx| this.open_artifact(hash.clone(), window, cx),
            ));
        }
        let answer_copy_entity = entity.clone();
        let branch_entity = entity.clone();
        let answer_text = state::result_text(&result);
        let branch_run = run_id.clone();
        let branch_id = conversation_id.map(str::to_owned);
        assistant = assistant.child(
            div()
                .w_full()
                .flex()
                .items_center()
                .child(widgets::copy_button(
                    answer_copy_entity,
                    answer_text,
                    language,
                    format!("ai-copy-answer-{run_id}"),
                    copy_key("answer", &run_id),
                    feedback(workspace, "answer", &run_id),
                    colors,
                ))
                .when_some(branch_id, |row, conversation| {
                    row.child(widgets::icon_button(
                        branch_entity,
                        IconName::Network,
                        language.text(TextKey::AiChatBranchFromHere),
                        format!("ai-branch-answer-{branch_run}"),
                        true,
                        32.0,
                        rgba(0x00000000),
                        colors.muted,
                        move |this, _, cx| {
                            this.fork_conversation(
                                conversation.clone(),
                                Some(branch_run.clone()),
                                cx,
                            )
                        },
                    ))
                })
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .pl(px(8.))
                        .text_size(px(tokens::SECONDARY_FONT_SIZE))
                        .line_height(px(tokens::SECONDARY_LINE_HEIGHT))
                        .text_color(colors.muted)
                        .whitespace_nowrap()
                        .text_ellipsis()
                        .child(crate::studio::selectable(result.model.clone())),
                ),
        );
        column = column.child(assistant);
    } else if turn.status.terminal() {
        let status_entity = entity.clone();
        let mut row = div().w_full().flex().items_center().child(
            div()
                .text_size(px(tokens::SECONDARY_FONT_SIZE))
                .line_height(px(tokens::SECONDARY_LINE_HEIGHT))
                .text_color(colors.muted)
                .child(crate::studio::selectable(state::run_status_label(
                    language,
                    turn.status,
                ))),
        );
        if turn.status.allows_chat_retry() {
            let turn_run = run_id.clone();
            row = row.child(widgets::command_button(
                status_entity,
                IconName::RefreshCw,
                language.text(TextKey::AiChatRetry),
                format!("ai-retry-{run_id}"),
                retry_enabled,
                colors,
                move |this, window, cx| this.retry(Some(turn_run.clone()), window, cx),
            ));
        }
        column = column.child(row);
    }
    column.into_any_element()
}

fn finding_view(
    workspace: &mut AiWorkspace,
    colors: &DshChatColors,
    language: Language,
    finding: &aps_ai::AnalysisFinding,
    entity: &WeakEntity<AiWorkspace>,
    cx: &mut Context<AiWorkspace>,
) -> AnyElement {
    let mut column = div()
        .w_full()
        .flex()
        .flex_col()
        .gap(px(6.))
        .child(
            div()
                .text_size(px(tokens::BODY_FONT_SIZE))
                .line_height(px(tokens::BODY_LINE_HEIGHT))
                .font_weight(FontWeight::MEDIUM)
                .text_color(colors.text)
                .child(crate::studio::selectable(finding.title.clone())),
        )
        .child(render_safe_text(
            &finding.explanation,
            colors,
            language,
            &format!("ai-finding-explanation-{}", finding.id),
            entity,
            &workspace.state.code_wrap,
        ));
    if !finding.recommendation.trim().is_empty() {
        column = column.child(render_safe_text(
            &finding.recommendation,
            colors,
            language,
            &format!("ai-finding-recommendation-{}", finding.id),
            entity,
            &workspace.state.code_wrap,
        ));
    }
    let evidence: Vec<String> = finding.evidence_ids.clone();
    let candidates: Vec<String> = finding.candidate_ids.clone();
    let mut chips = div().w_full().flex().flex_wrap().items_center();
    for id in evidence {
        let chip_id = id.clone();
        chips = chips.child(widgets::command_button(
            entity.clone(),
            IconName::FileText,
            id.clone(),
            format!("ai-finding-evidence-{id}"),
            true,
            colors,
            move |this, _, cx| this.open_evidence_dialog(chip_id.clone(), cx),
        ));
    }
    for id in candidates {
        let chip_id = id.clone();
        chips = chips.child(widgets::command_button(
            entity.clone(),
            IconName::SquareTerminal,
            id.clone(),
            format!("ai-finding-source-{id}"),
            true,
            colors,
            move |this, window, cx| this.open_source_chip(chip_id.clone(), window, cx),
        ));
    }
    if finding.evidence_ids.is_empty() && finding.candidate_ids.is_empty() {
        chips = chips.child(
            div()
                .text_size(px(tokens::SECONDARY_FONT_SIZE))
                .line_height(px(tokens::SECONDARY_LINE_HEIGHT))
                .text_color(colors.muted)
                .child(crate::studio::selectable(
                    language.text(TextKey::AiChatNoReferences),
                )),
        );
    }
    column = column.child(chips);
    let _ = cx;
    column.into_any_element()
}

fn result_artifact_hashes(turn: &aps_ai::runtime::TurnRecord) -> Vec<&String> {
    turn.artifact_hashes.iter().collect()
}

fn approval_card(
    approval: &aps_ai::runtime::ApprovalView,
    colors: &DshChatColors,
    language: Language,
    entity: &WeakEntity<AiWorkspace>,
    history_expanded: bool,
) -> AnyElement {
    let approve_entity = entity.clone();
    let cancel_entity = entity.clone();
    let mut column = div()
        .w_full()
        .flex()
        .flex_col()
        .gap(px(6.))
        .child(
            div()
                .text_size(px(tokens::BODY_FONT_SIZE))
                .line_height(px(tokens::BODY_LINE_HEIGHT))
                .font_weight(FontWeight::MEDIUM)
                .text_color(colors.text)
                .child(crate::studio::selectable(
                    language.text(TextKey::AiChatConfirmAnalysis),
                )),
        )
        .child(
            div()
                .text_size(px(tokens::SECONDARY_FONT_SIZE))
                .line_height(px(tokens::SECONDARY_LINE_HEIGHT))
                .text_color(colors.muted)
                .child(crate::studio::selectable(format!(
                    "{}\n{}",
                    format!("{} / {}", approval.provider_id, approval.model),
                    language.format(
                        TextKey::AiChatEvidenceSummary,
                        &[
                            (
                                "evidence",
                                widgets::display_arg(&approval.evidence_count.to_string())
                            ),
                            (
                                "candidates",
                                widgets::display_arg(&approval.candidate_count.to_string())
                            ),
                            (
                                "bytes",
                                widgets::display_arg(&approval.payload_bytes.to_string())
                            ),
                        ],
                    )
                ))),
        )
        .child(
            div()
                .text_size(px(tokens::SECONDARY_FONT_SIZE))
                .line_height(px(tokens::SECONDARY_LINE_HEIGHT))
                .text_color(colors.muted)
                .child(crate::studio::selectable(language.format(
                    TextKey::AiChatPlannedInvocations,
                    &[
                        (
                            "requests",
                            widgets::display_arg(&approval.planned_provider_calls.to_string()),
                        ),
                        (
                            "scripts",
                            widgets::display_arg(&approval.planned_scripts.to_string()),
                        ),
                    ],
                ))),
        )
        .child(
            div()
                .text_size(px(tokens::SECONDARY_FONT_SIZE))
                .line_height(px(tokens::SECONDARY_LINE_HEIGHT))
                .text_color(colors.muted)
                .child(crate::studio::selectable(language.format(
                    TextKey::AiChatReportFormat,
                    &[(
                        "format",
                        widgets::display_arg(&approval.report_format.label().to_owned()),
                    )],
                ))),
        );
    column = column.child(
        div()
            .text_size(px(tokens::SECONDARY_FONT_SIZE))
            .line_height(px(tokens::SECONDARY_LINE_HEIGHT))
            .text_color(colors.muted)
            .child(crate::studio::selectable(language.format(
                TextKey::AiChatReportLanguage,
                &[(
                    "language",
                    widgets::display_arg(
                        &report_language_label(approval.report_language, language).to_owned(),
                    ),
                )],
            ))),
    );
    if let Some(question) = approval.question.clone() {
        column = column.child(
            div()
                .text_size(px(tokens::BODY_FONT_SIZE))
                .line_height(px(tokens::BODY_LINE_HEIGHT))
                .text_color(colors.text)
                .child(crate::studio::selectable(question)),
        );
    }
    for evidence in &approval.evidence {
        column = column.child(
            div()
                .text_size(px(tokens::SECONDARY_FONT_SIZE))
                .line_height(px(tokens::SECONDARY_LINE_HEIGHT))
                .child(crate::studio::selectable(format!(
                    "{} · {}",
                    evidence.id, evidence.summary
                ))),
        );
    }
    for source in &approval.sources {
        column = column.child(
            div()
                .text_size(px(tokens::SECONDARY_FONT_SIZE))
                .line_height(px(tokens::SECONDARY_LINE_HEIGHT))
                .child(crate::studio::selectable(format!(
                    "{} · {}",
                    source.id, source.relative_path
                ))),
        );
    }
    if !approval.history.is_empty() {
        column = column.child(widgets::command_button(
            entity.clone(),
            IconName::Menu,
            language.format(
                TextKey::AiChatPriorDialogue,
                &[(
                    "count",
                    widgets::display_arg(&approval.history.len().to_string()),
                )],
            ),
            "ai-preflight-history-toggle",
            true,
            colors,
            |this, _, cx| this.toggle_history(cx),
        ));
    }
    if history_expanded && !approval.history.is_empty() {
        let mut history = div().w_full().flex().flex_col().gap(px(6.));
        history = history.child(
            div()
                .text_size(px(tokens::SECONDARY_FONT_SIZE))
                .line_height(px(tokens::SECONDARY_LINE_HEIGHT))
                .text_color(colors.muted)
                .child(crate::studio::selectable(
                    language.text(TextKey::AiChatUntrustedContext),
                )),
        );
        for turn in &approval.history {
            if let Some(question) = turn.question.clone() {
                history = history.child(
                    div()
                        .text_size(px(tokens::SECONDARY_FONT_SIZE))
                        .line_height(px(tokens::SECONDARY_LINE_HEIGHT))
                        .child(crate::studio::selectable(question)),
                );
            }
            history = history.child(
                div()
                    .text_size(px(tokens::SECONDARY_FONT_SIZE))
                    .line_height(px(tokens::SECONDARY_LINE_HEIGHT))
                    .child(crate::studio::selectable(turn.answer.clone())),
            );
        }
        column = column.child(history);
    }
    let mut permissions = approval.permissions.clone();
    permissions.sort();
    column = column.child(
        div()
            .text_size(px(tokens::SECONDARY_FONT_SIZE))
            .line_height(px(tokens::SECONDARY_LINE_HEIGHT))
            .text_color(colors.muted)
            .child(crate::studio::selectable(format!(
                "SHA256 {}\n{}",
                approval.payload_hash,
                permissions.join(", ")
            ))),
    );
    column = column.child(
        div()
            .flex()
            .items_center()
            .child(widgets::text_action(
                approve_entity,
                language.text(TextKey::AiChatApprove),
                "ai-confirm",
                true,
                colors.send,
                |this, _, cx| this.approve(cx),
            ))
            .child(widgets::text_action(
                cancel_entity,
                language.text(TextKey::AiChatCancel),
                "ai-cancel-preflight",
                true,
                colors.muted,
                |this, _, cx| this.cancel_preflight(cx),
            )),
    );
    column.into_any_element()
}

fn report_language_label(language: aps_ai::ReportLanguage, ui: Language) -> &'static str {
    match language {
        aps_ai::ReportLanguage::Auto => ui.text(TextKey::AiChatLanguageAuto),
        aps_ai::ReportLanguage::English => ui.text(TextKey::AiChatLanguageEnglish),
        aps_ai::ReportLanguage::SimplifiedChinese => ui.text(TextKey::AiChatLanguageChinese),
    }
}

fn activity_card(
    workspace: &mut AiWorkspace,
    colors: &DshChatColors,
    language: Language,
    entity: &WeakEntity<AiWorkspace>,
) -> AnyElement {
    let count = workspace.state.activity.len();
    let expanded = workspace.state.activity_expanded;
    let mut column = div()
        .w_full()
        .flex()
        .flex_col()
        .child(widgets::command_button(
            entity.clone(),
            IconName::Menu,
            language.format(
                TextKey::AiChatActivity,
                &[("count", widgets::display_arg(&count.to_string()))],
            ),
            "ai-activity-toggle",
            true,
            colors,
            |this, _, cx| this.toggle_activity(cx),
        ));
    if expanded {
        let mut rows = div().w_full().flex().flex_col().pl(px(8.));
        for event in &workspace.state.activity {
            let error = event
                .error_code
                .as_deref()
                .map(|code| format!("  {code}"))
                .unwrap_or_default();
            rows = rows.child(
                div()
                    .text_size(px(tokens::SECONDARY_FONT_SIZE))
                    .line_height(px(tokens::SECONDARY_LINE_HEIGHT))
                    .text_color(colors.muted)
                    .child(crate::studio::selectable(format!(
                        "{}  {} / {}{}",
                        event.subject_id, event.kind, event.phase, error
                    ))),
            );
        }
        column = column.child(rows);
    }
    column.into_any_element()
}

fn error_row(
    error: &str,
    colors: &DshChatColors,
    language: Language,
    entity: &WeakEntity<AiWorkspace>,
    retry_enabled: bool,
) -> AnyElement {
    let mut column = div().w_full().flex().flex_col().gap(px(4.)).child(
        div()
            .text_size(px(tokens::BODY_FONT_SIZE))
            .line_height(px(tokens::BODY_LINE_HEIGHT))
            .text_color(colors.error)
            .child(crate::studio::selectable(error.to_owned())),
    );
    if error == "E_CONVERSATION_HAS_CHILDREN" {
        column = column.child(
            div()
                .text_size(px(tokens::SECONDARY_FONT_SIZE))
                .line_height(px(tokens::SECONDARY_LINE_HEIGHT))
                .text_color(colors.muted)
                .child(crate::studio::selectable(
                    language.text(TextKey::AiChatChildrenDeleteHint),
                )),
        );
    }
    column = column.child(widgets::command_button(
        entity.clone(),
        IconName::RefreshCw,
        language.text(TextKey::AiChatRetry),
        "ai-retry",
        retry_enabled,
        colors,
        |this, window, cx| this.retry(None, window, cx),
    ));
    column.into_any_element()
}

fn _dedupe(values: Vec<String>) -> BTreeSet<String> {
    values.into_iter().collect()
}

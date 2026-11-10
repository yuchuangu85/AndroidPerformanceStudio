//! Application-wide notification center for page errors and tips.
//!
//! Every page keeps its own transient error/notice state. The shell polls each
//! workspace through NoticeSource and feeds the state here; the center keeps a
//! bounded, copyable history and renders the floating panel opened from the
//! title-bar bell button.

use crate::studio::{self, StudioColors};
use aps_ui_foundation::{Language, TextKey};
use gpui_kit::{
    component::button::{Button, ButtonVariants},
    prelude::*,
    *,
};
use std::{
    collections::{HashMap, VecDeque},
    time::Instant,
};

/// Bound for retained cards, per-source snapshot entries and message bytes.
const MAX_CARDS: usize = 200;
const MAX_SOURCE_ENTRIES: usize = 8;
const MAX_MESSAGE_BYTES: usize = 8192;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum NoticeLevel {
    Info,
    Warning,
    Error,
}

impl NoticeLevel {
    fn label_key(self) -> TextKey {
        match self {
            Self::Info => TextKey::ShellNotificationLevelInfo,
            Self::Warning => TextKey::ShellNotificationLevelWarning,
            Self::Error => TextKey::ShellNotificationLevelError,
        }
    }

    fn tone(self, colors: &StudioColors) -> Rgba {
        match self {
            Self::Info => colors.info,
            Self::Warning => colors.warning,
            Self::Error => colors.error,
        }
    }
}

/// One current message exposed by a workspace.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct NoticeSnapshot {
    pub(crate) level: NoticeLevel,
    pub(crate) message: String,
}

impl NoticeSnapshot {
    pub(crate) fn info(message: impl Into<String>) -> Self {
        Self {
            level: NoticeLevel::Info,
            message: message.into(),
        }
    }

    pub(crate) fn warning(message: impl Into<String>) -> Self {
        Self {
            level: NoticeLevel::Warning,
            message: message.into(),
        }
    }

    pub(crate) fn error(message: impl Into<String>) -> Self {
        Self {
            level: NoticeLevel::Error,
            message: message.into(),
        }
    }
}

/// Implemented by every page workspace so the shell can collect its current
/// user-visible errors and tips without knowing the workspace internals.
pub(crate) trait NoticeSource {
    fn notice_snapshot(&self) -> Vec<NoticeSnapshot>;
}

#[derive(Clone, Debug)]
pub(crate) struct NoticeCard {
    pub(crate) id: u64,
    pub(crate) level: NoticeLevel,
    pub(crate) source: String,
    pub(crate) message: String,
    pub(crate) count: u32,
    pub(crate) created: Instant,
    pub(crate) seen: bool,
}

pub(crate) struct NotificationCenter {
    cards: VecDeque<NoticeCard>,
    /// Last snapshot per source, used to push a card only for a new message.
    last_state: HashMap<String, Vec<NoticeSnapshot>>,
    open: bool,
    next_id: u64,
    language: Language,
}

impl NotificationCenter {
    pub(crate) fn new(language: Language) -> Self {
        Self {
            cards: VecDeque::new(),
            last_state: HashMap::new(),
            open: false,
            next_id: 1,
            language,
        }
    }

    pub(crate) fn set_language(&mut self, language: Language, cx: &mut Context<Self>) {
        if self.language != language {
            self.language = language;
            cx.notify();
        }
    }

    pub(crate) fn is_open(&self) -> bool {
        self.open
    }

    #[cfg(test)]
    pub(crate) fn cards(&self) -> &VecDeque<NoticeCard> {
        &self.cards
    }

    pub(crate) fn unread_count(&self) -> usize {
        self.cards.iter().filter(|card| !card.seen).count()
    }

    pub(crate) fn open(&mut self) {
        self.open = true;
        self.mark_all_read();
    }

    pub(crate) fn close(&mut self) {
        self.open = false;
    }

    pub(crate) fn toggle(&mut self) {
        if self.open {
            self.close();
        } else {
            self.open();
        }
    }

    pub(crate) fn mark_all_read(&mut self) {
        for card in &mut self.cards {
            card.seen = true;
        }
    }

    /// Keeps the current per-source snapshot as already reported, so clearing
    /// the history does not immediately re-add still-visible errors.
    pub(crate) fn clear(&mut self) {
        self.cards.clear();
    }

    /// Pushes a card for every message that appeared since the previous snapshot
    /// of this source, coalescing an identical message into the existing card.
    pub(crate) fn sync_source(&mut self, source: &str, notices: Vec<NoticeSnapshot>) {
        let current: Vec<NoticeSnapshot> = notices
            .into_iter()
            .filter(|notice| !notice.message.trim().is_empty())
            .take(MAX_SOURCE_ENTRIES)
            .map(|notice| NoticeSnapshot {
                level: notice.level,
                message: truncate(&notice.message, MAX_MESSAGE_BYTES),
            })
            .collect();
        let previous = self.last_state.get(source).cloned().unwrap_or_default();
        for notice in &current {
            if previous.contains(notice) {
                continue;
            }
            if let Some(position) = self.cards.iter().position(|card| {
                card.source == source
                    && card.level == notice.level
                    && card.message == notice.message
            }) {
                if let Some(mut card) = self.cards.remove(position) {
                    card.count = card.count.saturating_add(1);
                    card.created = Instant::now();
                    card.seen = self.open;
                    self.cards.push_front(card);
                }
                continue;
            }
            let id = self.next_id;
            self.next_id = self.next_id.saturating_add(1);
            self.cards.push_front(NoticeCard {
                id,
                level: notice.level,
                source: source.to_owned(),
                message: notice.message.clone(),
                count: 1,
                created: Instant::now(),
                seen: self.open,
            });
            while self.cards.len() > MAX_CARDS {
                self.cards.pop_back();
            }
        }
        if current.is_empty() {
            self.last_state.remove(source);
        } else {
            self.last_state.insert(source.to_owned(), current);
        }
    }

    #[cfg(debug_assertions)]
    pub(crate) fn push_debug(
        &mut self,
        level: NoticeLevel,
        source: impl Into<String>,
        message: impl Into<String>,
    ) {
        self.sync_source(
            &source.into(),
            vec![NoticeSnapshot {
                level,
                message: message.into(),
            }],
        );
    }

    pub(crate) fn card_text(&self, card: &NoticeCard) -> String {
        let level = self.language.text(card.level.label_key());
        let count = if card.count > 1 {
            format!(" x{}", card.count)
        } else {
            String::new()
        };
        format!(
            "[{level}] {} ({}{count})\n{}",
            card.source,
            age_label(self.language, card.created),
            card.message
        )
    }

    pub(crate) fn copy_all_text(&self) -> String {
        self.cards
            .iter()
            .map(|card| self.card_text(card))
            .collect::<Vec<_>>()
            .join("\n\n")
    }
}

fn truncate(value: &str, limit: usize) -> String {
    if value.len() <= limit {
        return value.to_owned();
    }
    let mut end = limit;
    while end > 0 && !value.is_char_boundary(end) {
        end -= 1;
    }
    value[..end].to_owned()
}

fn age_label(language: Language, created: Instant) -> String {
    let seconds = created.elapsed().as_secs();
    if seconds < 10 {
        language.text(TextKey::ShellNotificationAgeNow).to_owned()
    } else if seconds < 60 {
        language.format(
            TextKey::ShellNotificationAgeSeconds,
            &[("seconds", &seconds.to_string())],
        )
    } else if seconds < 3_600 {
        language.format(
            TextKey::ShellNotificationAgeMinutes,
            &[("minutes", &(seconds / 60).to_string())],
        )
    } else {
        language.format(
            TextKey::ShellNotificationAgeHours,
            &[("hours", &(seconds / 3_600).to_string())],
        )
    }
}

fn card_view(
    card: &NoticeCard,
    colors: &StudioColors,
    language: Language,
    text: String,
) -> AnyElement {
    let level = language.text(card.level.label_key());
    let tone = card.level.tone(colors);
    let age = age_label(language, card.created);
    let source = card.source.clone();
    let message = card.message.clone();
    let count = card.count;
    let seen = card.seen;
    let header = div()
        .flex()
        .items_center()
        .gap(px(6.))
        .child(div().size(px(7.)).rounded_full().bg(tone))
        .child(
            div()
                .text_size(px(studio::FONT_SUPPORTING))
                .text_color(tone)
                .child(level.to_owned()),
        )
        .child(
            div()
                .flex_1()
                .min_w_0()
                .text_size(px(studio::FONT_SUPPORTING))
                .text_color(colors.muted_text)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(source),
        )
        .child(
            div()
                .text_size(px(studio::FONT_SUPPORTING))
                .text_color(colors.muted_text)
                .child(if count > 1 {
                    format!("{age} x{count}")
                } else {
                    age
                }),
        )
        .child(
            Button::new(("notification-copy-card", card.id))
                .ghost()
                .h(px(20.))
                .px(px(6.))
                .text_size(px(studio::FONT_SUPPORTING))
                .label(language.text(TextKey::ShellNotificationCopy))
                .on_click(move |_, _, cx| {
                    cx.write_to_clipboard(ClipboardItem::new_string(text.clone()));
                }),
        );
    div()
        .id(("notification-card", card.id))
        .flex()
        .flex_col()
        .gap(px(4.))
        .pl(px(8.))
        .pr(px(8.))
        .py(px(6.))
        .bg(colors.section_background)
        .border_l(px(3.))
        .border_color(tone)
        .rounded(px(6.))
        .child(header)
        .child(
            div()
                .text_size(px(studio::FONT_BODY))
                .text_color(if seen {
                    colors.secondary_text
                } else {
                    colors.text
                })
                .child(studio::selectable(message)),
        )
        .into_any_element()
}

impl Render for NotificationCenter {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let colors = StudioColors::from_theme(cx);
        let language = self.language;
        let total = self.cards.len();
        let unread = self.unread_count();
        let copy_all_text = self.copy_all_text();
        let header = self.header(language, &colors, unread, total, copy_all_text, cx);
        let mut body = div()
            .id("notification-scroll")
            .flex_1()
            .min_h_0()
            .overflow_y_scroll();
        if self.cards.is_empty() {
            body = body.child(
                div()
                    .flex()
                    .items_center()
                    .justify_center()
                    .py(px(48.))
                    .text_size(px(studio::FONT_BODY))
                    .text_color(colors.muted_text)
                    .child(language.text(TextKey::ShellNotificationEmpty)),
            );
        } else {
            let mut list = div()
                .id("notification-card-list")
                .flex()
                .flex_col()
                .gap(px(6.))
                .p(px(8.));
            for card in &self.cards {
                list = list.child(card_view(card, &colors, language, self.card_text(card)));
            }
            body = body.child(list);
        }
        div()
            .id("notification-panel")
            .w_full()
            .max_h(px(560.))
            .flex()
            .flex_col()
            .bg(colors.panel)
            .border_1()
            .border_color(colors.border)
            .rounded(px(10.))
            .shadow_sm()
            .overflow_hidden()
            .child(header)
            .child(body)
    }
}

impl NotificationCenter {
    fn header(
        &self,
        language: Language,
        colors: &StudioColors,
        unread: usize,
        total: usize,
        copy_all_text: String,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let title = div()
            .text_size(px(studio::FONT_BODY))
            .text_color(colors.text)
            .child(language.text(TextKey::ShellNotificationTitle));
        let count = div()
            .text_size(px(studio::FONT_SUPPORTING))
            .text_color(colors.muted_text)
            .child(if unread > 0 {
                language.format(
                    TextKey::ShellNotificationUnread,
                    &[("count", &unread.to_string())],
                )
            } else {
                language.format(
                    TextKey::ShellNotificationCount,
                    &[("count", &total.to_string())],
                )
            });
        let copy = Button::new("notification-copy-all")
            .ghost()
            .h(px(22.))
            .px(px(6.))
            .text_size(px(studio::FONT_SUPPORTING))
            .label(language.text(TextKey::ShellNotificationCopyAll))
            .on_click(move |_, _, cx| {
                cx.write_to_clipboard(ClipboardItem::new_string(copy_all_text.clone()));
            });
        let mark = Button::new("notification-mark-read")
            .ghost()
            .h(px(22.))
            .px(px(6.))
            .text_size(px(studio::FONT_SUPPORTING))
            .label(language.text(TextKey::ShellNotificationMarkAllRead))
            .on_click(cx.listener(|this, _, _, cx| {
                this.mark_all_read();
                cx.notify();
            }));
        let clear = Button::new("notification-clear")
            .ghost()
            .h(px(22.))
            .px(px(6.))
            .text_size(px(studio::FONT_SUPPORTING))
            .label(language.text(TextKey::ShellNotificationClear))
            .on_click(cx.listener(|this, _, _, cx| {
                this.clear();
                cx.notify();
            }));
        let close = Button::new("notification-close")
            .ghost()
            .h(px(22.))
            .w(px(22.))
            .px(px(0.))
            .label("×")
            .on_click(cx.listener(|this, _, _, cx| {
                this.close();
                cx.notify();
            }));
        div()
            .flex()
            .items_center()
            .gap(px(8.))
            .px(px(10.))
            .py(px(8.))
            .border_b_1()
            .border_color(colors.border)
            .child(title)
            .child(count)
            .child(div().flex_1())
            .child(copy)
            .child(mark)
            .child(clear)
            .child(close)
            .into_any_element()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn center() -> NotificationCenter {
        NotificationCenter::new(Language::English)
    }

    #[::core::prelude::v1::test]
    fn sync_reports_new_messages_once_and_coalesces_reappearance() {
        let mut center = center();
        center.sync_source("CPU", vec![NoticeSnapshot::error("boom")]);
        assert_eq!(center.cards().len(), 1);
        center.sync_source("CPU", vec![NoticeSnapshot::error("boom")]);
        assert_eq!(center.cards().len(), 1);
        center.sync_source("CPU", vec![NoticeSnapshot::error("other")]);
        assert_eq!(center.cards().len(), 2);
        center.sync_source("CPU", vec![NoticeSnapshot::error("boom")]);
        assert_eq!(center.cards().len(), 2);
        assert_eq!(center.cards()[0].count, 2);
    }

    #[::core::prelude::v1::test]
    fn clearing_keeps_current_state_from_reappearing() {
        let mut center = center();
        center.sync_source("CPU", vec![NoticeSnapshot::error("boom")]);
        center.clear();
        center.sync_source("CPU", vec![NoticeSnapshot::error("boom")]);
        assert!(center.cards().is_empty());
        center.sync_source("CPU", vec![]);
        center.sync_source("CPU", vec![NoticeSnapshot::error("boom")]);
        assert_eq!(center.cards().len(), 1);
    }

    #[::core::prelude::v1::test]
    fn opening_marks_every_card_seen_and_copy_text_is_stable() {
        let mut center = center();
        center.sync_source("CPU", vec![NoticeSnapshot::warning("slow")]);
        assert_eq!(center.unread_count(), 1);
        center.open();
        assert!(center.is_open());
        assert_eq!(center.unread_count(), 0);
        let text = center.copy_all_text();
        assert!(text.contains("[Warning] CPU"));
        assert!(text.contains("slow"));
    }
}

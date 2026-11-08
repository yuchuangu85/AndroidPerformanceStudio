//! Owned view state and pure transitions for the AI Analysis conversation window.
//!
//! Rendering reads this state; the runtime-facing controller writes it. Keeping
//! the transitions pure makes the queue, search, retry and stop rules testable
//! without a provider, database or window.
#![allow(dead_code)]

use aps_ai::runtime::{ApprovalView, RunStatus, SessionSummary, TurnRecord};
use aps_ui_foundation::{Language, TextKey};
use std::collections::HashMap;
use std::time::Instant;

/// Hard cap matching Compose's MAX_QUEUED_MESSAGES.
pub const MAX_QUEUED_MESSAGES: usize = 8;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct QueuedQuestion {
    pub conversation_id: String,
    pub question: String,
    pub include_source: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ActivityEvent {
    pub subject_id: String,
    pub kind: &'static str,
    pub phase: &'static str,
    pub error_code: Option<String>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CopyOutcome {
    Copied,
    Failed,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CopyFeedback {
    pub target: String,
    pub outcome: CopyOutcome,
    pub at: Instant,
}

#[derive(Debug, Clone, Default)]
pub struct Dialogs {
    pub rename: Option<RenamingConversation>,
    pub delete: Option<String>,
    pub archive: Option<String>,
    pub evidence: Option<String>,
}

#[derive(Debug, Clone)]
pub struct RenamingConversation {
    pub id: String,
    pub title: String,
}

/// All render-facing conversation state. The controller owns async work; this
/// struct only stores what the window draws.
#[derive(Clone)]
pub struct ChatState {
    pub sessions: Vec<SessionSummary>,
    pub selected_conversation_id: Option<String>,
    pub turns: Vec<TurnRecord>,
    pub draft: String,
    pub context_title: Option<String>,
    pub can_send: bool,
    pub model: String,
    pub busy: bool,
    pub status: Option<RunStatus>,
    pub error: Option<String>,
    pub pending_approval: Option<ApprovalView>,
    pub activity: Vec<ActivityEvent>,
    pub include_source: bool,
    pub can_include_source: bool,
    pub queued: Vec<QueuedQuestion>,
    pub showing_archived: bool,
    pub search_query: String,
    pub models: Vec<String>,
    pub models_loading: bool,
    pub models_error: Option<String>,
    pub follow_latest: bool,
    pub sidebar_visible: bool,
    /// Last resolved wide/narrow breakpoint; resizing re-resolves the default.
    pub sidebar_wide: bool,
    pub copy_feedback: Option<CopyFeedback>,
    pub activity_expanded: bool,
    pub history_expanded: bool,
    pub dialogs: Dialogs,
    pub last_question: Option<String>,
    /// Per-conversation unsent drafts survive switching conversations.
    pub drafts: HashMap<String, String>,
    /// Code-fence wrap toggles keyed by the rendered block tag.
    pub code_wrap: std::collections::HashSet<String>,
    /// Model menu visibility (closed while a run is busy).
    pub models_open: bool,
    pub stop_gesture: StopGesture,
}

impl Default for ChatState {
    fn default() -> Self {
        Self::new()
    }
}

impl ChatState {
    pub fn new() -> Self {
        Self {
            sessions: Vec::new(),
            selected_conversation_id: None,
            turns: Vec::new(),
            draft: String::new(),
            context_title: None,
            can_send: false,
            model: String::new(),
            busy: false,
            status: None,
            error: None,
            pending_approval: None,
            activity: Vec::new(),
            include_source: false,
            can_include_source: false,
            queued: Vec::new(),
            showing_archived: false,
            search_query: String::new(),
            models: Vec::new(),
            models_loading: false,
            models_error: None,
            follow_latest: true,
            sidebar_visible: true,
            sidebar_wide: true,
            copy_feedback: None,
            activity_expanded: false,
            history_expanded: false,
            dialogs: Dialogs::default(),
            last_question: None,
            drafts: HashMap::new(),
            code_wrap: std::collections::HashSet::new(),
            models_open: false,
            stop_gesture: StopGesture::default(),
        }
    }

    pub fn code_wrap(&self, key: &str) -> bool {
        self.code_wrap.contains(key)
    }

    pub fn toggle_code_wrap(&mut self, key: &str) {
        if !self.code_wrap.remove(key) {
            self.code_wrap.insert(key.to_owned());
        }
    }

    /// Sessions visible in the two sidebar sections, filtered and ordered the
    /// same way Compose does: pinned first, then most recently updated.
    pub fn visible_sessions(&self) -> Vec<SessionSummary> {
        visible_sessions(&self.sessions, &self.search_query, self.showing_archived)
    }

    pub fn selected_title(&self) -> Option<&str> {
        let id = self.selected_conversation_id.as_deref()?;
        self.sessions
            .iter()
            .find(|session| session.id == id)
            .map(|session| session.title.as_str())
    }

    pub fn can_submit(&self) -> bool {
        self.can_send && !self.draft.trim().is_empty() && self.draft.chars().count() <= 4096
    }

    /// A run is in flight while the draft may still queue a question.
    pub fn stopping(&self) -> bool {
        self.busy && !self.can_submit()
    }

    pub fn terminal_turn(&self) -> Option<&TurnRecord> {
        self.turns
            .last()
            .filter(|turn| turn.status.terminal() && turn.result.is_none())
    }

    /// Recompute follow-latest after the scroll controls or a user scroll.
    pub fn on_user_scrolled(&mut self, at_latest: bool) {
        self.follow_latest = at_latest;
    }

    /// Compose resolves sidebar visibility from the actual width; a manual
    /// toggle sticks until the wide/narrow breakpoint changes again.
    pub fn resolve_sidebar(&mut self, available_width: f32) {
        let wide = available_width >= 1000.0;
        if wide != self.sidebar_wide {
            self.sidebar_wide = wide;
            self.sidebar_visible = wide;
        }
    }

    pub fn push_queued(&mut self, question: String) -> Result<(), &'static str> {
        if self.queued.len() >= MAX_QUEUED_MESSAGES {
            return Err("E_QUEUE_LIMIT");
        }
        if question.trim().is_empty() || question.chars().count() > 4096 {
            return Err("E_AI_INPUT_LIMIT");
        }
        let Some(conversation_id) = self.selected_conversation_id.clone() else {
            return Err("E_CONTEXT_UNAVAILABLE");
        };
        self.queued.push(QueuedQuestion {
            conversation_id,
            question,
            include_source: self.include_source,
        });
        Ok(())
    }

    pub fn remove_queued(&mut self, index: usize) -> Option<QueuedQuestion> {
        if index >= self.queued.len() {
            return None;
        }
        Some(self.queued.remove(index))
    }

    pub fn edit_queued(&mut self, index: usize) -> Option<QueuedQuestion> {
        let removed = self.remove_queued(index)?;
        self.draft = removed.question.clone();
        Some(removed)
    }

    pub fn visible_queue(&self) -> Vec<QueuedQuestion> {
        let Some(id) = self.selected_conversation_id.as_deref() else {
            return Vec::new();
        };
        self.queued
            .iter()
            .filter(|item| item.conversation_id == id)
            .cloned()
            .collect()
    }

    pub fn add_activity(
        &mut self,
        subject_id: impl Into<String>,
        kind: &'static str,
        phase: &'static str,
    ) {
        self.activity.push(ActivityEvent {
            subject_id: subject_id.into(),
            kind,
            phase,
            error_code: None,
        });
    }

    pub fn add_activity_error(
        &mut self,
        subject_id: impl Into<String>,
        kind: &'static str,
        phase: &'static str,
        error_code: impl Into<String>,
    ) {
        self.activity.push(ActivityEvent {
            subject_id: subject_id.into(),
            kind,
            phase,
            error_code: Some(error_code.into()),
        });
    }
}

/// Compose sidebar filter: section membership, case-insensitive title search,
/// pinned first, then newest.
pub fn visible_sessions(
    sessions: &[SessionSummary],
    search: &str,
    showing_archived: bool,
) -> Vec<SessionSummary> {
    let needle = search.trim().to_lowercase();
    let mut rows: Vec<SessionSummary> = sessions
        .iter()
        .filter(|session| session.archived == showing_archived)
        .filter(|session| needle.is_empty() || session.title.to_lowercase().contains(&needle))
        .cloned()
        .collect();
    rows.sort_by(|left, right| {
        right
            .pinned
            .cmp(&left.pinned)
            .then_with(|| right.updated_at_millis.cmp(&left.updated_at_millis))
    });
    rows
}

/// Compose's two-Escape stop gesture. Held repeats are ignored, and the two
/// independent presses must land within 500 ms.
#[derive(Debug, Clone, Default)]
pub struct StopGesture {
    previous_millis: Option<u64>,
    held: bool,
}

impl StopGesture {
    pub fn press(&mut self, now_millis: u64) -> bool {
        if self.held {
            return false;
        }
        self.held = true;
        let stop = self
            .previous_millis
            .is_some_and(|previous| now_millis.saturating_sub(previous) <= 500);
        self.previous_millis = if stop { None } else { Some(now_millis) };
        stop
    }

    pub fn release(&mut self) {
        self.held = false;
    }

    pub fn reset(&mut self) {
        self.previous_millis = None;
        self.held = false;
    }
}

/// Enter sends only without Shift/Ctrl/Meta/Alt and outside IME composition.
pub fn should_send(
    key: &str,
    shift: bool,
    ctrl: bool,
    meta: bool,
    alt: bool,
    composing: bool,
) -> bool {
    key.eq_ignore_ascii_case("enter") && !shift && !ctrl && !meta && !alt && !composing
}

/// Up recalls the last question only from an empty, non-composed draft.
pub fn should_recall_last_question(
    key: &str,
    draft_is_empty: bool,
    shift: bool,
    ctrl: bool,
    meta: bool,
    alt: bool,
    composing: bool,
) -> bool {
    draft_is_empty
        && key.eq_ignore_ascii_case("up")
        && !shift
        && !ctrl
        && !meta
        && !alt
        && !composing
}

/// Compose's status labels; stable codes stay in English.
pub fn run_status_label(language: Language, status: RunStatus) -> &'static str {
    language.text(run_status_key(status))
}

pub fn run_status_key(status: RunStatus) -> TextKey {
    match status {
        RunStatus::Queued => TextKey::AiChatStatusQueued,
        RunStatus::Preparing => TextKey::AiChatStatusPreparing,
        RunStatus::AwaitingApproval => TextKey::AiChatStatusAwaitingApproval,
        RunStatus::Running => TextKey::AiChatStatusRunning,
        RunStatus::Succeeded => TextKey::AiChatStatusSucceeded,
        RunStatus::Cancelled => TextKey::AiChatStatusCancelled,
        RunStatus::RetryableFailure => TextKey::AiChatStatusFailed,
        RunStatus::PermanentFailure => TextKey::AiChatStatusUnavailable,
        RunStatus::Interrupted => TextKey::AiChatStatusInterrupted,
    }
}

/// Format a sidebar row's MM/dd from an epoch-millis value without a date crate.
pub fn format_session_date(epoch_millis: i64) -> String {
    let days = epoch_millis.div_euclid(86_400_000);
    let (year, month, day) = civil_from_days(days);
    let _ = year;
    format!("{month:02}/{day:02}")
}

/// Howard Hinnant's civil_from_days algorithm for the proleptic Gregorian calendar.
fn civil_from_days(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let d = doy - (153 * mp + 2) / 5 + 1;
    let m = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = if m <= 2 { y + 1 } else { y };
    (year, m as u32, d as u32)
}

/// Whether a terminal turn offers Retry: only failures/interruption, never a
/// successful, stopped or awaiting-approval turn.
pub fn turn_allows_retry(turn: &TurnRecord) -> bool {
    turn.result.is_none() && turn.status.allows_chat_retry()
}

/// Copy feedback lives for one second, like Compose's LaunchedEffect delay.
pub const COPY_FEEDBACK_MILLIS: u64 = 1_000;

pub fn copy_feedback_active(feedback: &CopyFeedback, now: Instant) -> bool {
    now.duration_since(feedback.at).as_millis() < u128::from(COPY_FEEDBACK_MILLIS)
}

/// Clamp a model id the way Compose disables unusable menu rows.
pub fn selectable_model(model: &str) -> bool {
    !model.is_empty() && model.len() <= 256 && !model.chars().any(char::is_control)
}

/// Model menu empty/loading/error projection.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ModelMenuStatus {
    Loading,
    Error(String),
    Empty,
    Ready,
}

pub fn model_menu_status(loading: bool, error: Option<&str>, models: &[String]) -> ModelMenuStatus {
    if loading {
        ModelMenuStatus::Loading
    } else if let Some(error) = error {
        ModelMenuStatus::Error(error.to_owned())
    } else if models.is_empty() {
        ModelMenuStatus::Empty
    } else {
        ModelMenuStatus::Ready
    }
}

/// Compose dshResultText: the copyable text of one validated answer.
pub fn result_text(result: &aps_ai::AnalysisResult) -> String {
    let mut output = result.summary.clone();
    for finding in &result.findings {
        output.push_str("\n\n");
        output.push_str(&finding.title);
        output.push('\n');
        output.push_str(&finding.explanation);
        output.push('\n');
        output.push_str(&finding.recommendation);
        let references: Vec<&String> = finding
            .evidence_ids
            .iter()
            .chain(finding.candidate_ids.iter())
            .collect();
        if !references.is_empty() {
            output.push('\n');
            output.push_str(
                &references
                    .iter()
                    .map(|value| value.as_str())
                    .collect::<Vec<_>>()
                    .join(", "),
            );
        }
    }
    output
}

#[cfg(test)]
mod tests {
    use super::*;

    fn session(
        id: &str,
        title: &str,
        pinned: bool,
        archived: bool,
        updated: i64,
    ) -> SessionSummary {
        SessionSummary {
            id: id.into(),
            title: title.into(),
            updated_at_millis: updated,
            pinned,
            turn_count: 1,
            status: None,
            archived,
        }
    }

    #[test]
    fn sidebar_filters_section_search_and_orders_pinned_first() {
        let sessions = vec![
            session("a", "Alpha", false, false, 10),
            session("b", "Beta", true, false, 5),
            session("c", "Alpha old", false, true, 20),
        ];
        let recent = visible_sessions(&sessions, "", false);
        assert_eq!(
            recent.iter().map(|row| row.id.as_str()).collect::<Vec<_>>(),
            ["b", "a"]
        );
        let search = visible_sessions(&sessions, " alpha ", false);
        assert_eq!(
            search.iter().map(|row| row.id.as_str()).collect::<Vec<_>>(),
            ["a"]
        );
        let archived = visible_sessions(&sessions, "", true);
        assert_eq!(
            archived
                .iter()
                .map(|row| row.id.as_str())
                .collect::<Vec<_>>(),
            ["c"]
        );
    }

    #[test]
    fn stop_gesture_requires_two_independent_presses_within_500ms() {
        let mut gesture = StopGesture::default();
        assert!(!gesture.press(1_000));
        // Held repeat does not count.
        assert!(!gesture.press(1_000));
        gesture.release();
        // Too late for the same pair.
        assert!(!gesture.press(1_600));
        gesture.release();
        // The pending press is now the first of a new pair.
        assert!(gesture.press(1_800));
        gesture.release();
        // Third press starts over.
        assert!(!gesture.press(1_900));
        gesture.release();
        assert!(gesture.press(2_200));
    }

    #[test]
    fn send_and_recall_keys_respect_modifiers_and_composition() {
        assert!(should_send("enter", false, false, false, false, false));
        assert!(!should_send("enter", true, false, false, false, false));
        assert!(!should_send("enter", false, true, false, false, false));
        assert!(!should_send("enter", false, false, true, false, false));
        assert!(!should_send("enter", false, false, false, true, false));
        assert!(!should_send("enter", false, false, false, false, true));
        assert!(!should_send("a", false, false, false, false, false));
        assert!(should_recall_last_question(
            "up", true, false, false, false, false, false
        ));
        assert!(!should_recall_last_question(
            "up", false, false, false, false, false, false
        ));
        assert!(!should_recall_last_question(
            "up", true, true, false, false, false, false
        ));
        assert!(!should_recall_last_question(
            "up", true, false, false, false, false, true
        ));
    }

    #[test]
    fn queue_edit_remove_and_limit() {
        let mut state = ChatState::new();
        state.selected_conversation_id = Some("c1".into());
        state.can_send = true;
        assert_eq!(state.push_queued("  ".into()), Err("E_AI_INPUT_LIMIT"));
        for index in 0..MAX_QUEUED_MESSAGES {
            state.draft = format!("q{index}");
            assert!(state.push_queued(format!("q{index}")).is_ok());
        }
        assert_eq!(state.push_queued("overflow".into()), Err("E_QUEUE_LIMIT"));
        assert_eq!(state.visible_queue().len(), MAX_QUEUED_MESSAGES);
        let edited = state.edit_queued(1).expect("queued row");
        assert_eq!(edited.question, "q1");
        assert_eq!(state.draft, "q1");
        assert_eq!(state.visible_queue().len(), MAX_QUEUED_MESSAGES - 1);
        assert!(state.remove_queued(0).is_some());
        assert_eq!(state.visible_queue().len(), MAX_QUEUED_MESSAGES - 2);
        assert!(state.remove_queued(99).is_none());
    }

    #[test]
    fn queue_is_scoped_to_the_selected_conversation() {
        let mut state = ChatState::new();
        state.selected_conversation_id = Some("c1".into());
        assert!(state.push_queued("for c1".into()).is_ok());
        state.selected_conversation_id = Some("c2".into());
        assert!(state.visible_queue().is_empty());
        assert_eq!(state.queued.len(), 1);
    }

    #[test]
    fn sidebar_breakpoints_follow_the_wide_rule() {
        let mut state = ChatState::new();
        state.resolve_sidebar(1_200.0);
        assert!(state.sidebar_wide);
        assert!(state.sidebar_visible);
        state.sidebar_visible = false;
        state.resolve_sidebar(1_200.0);
        assert!(!state.sidebar_visible);
        state.resolve_sidebar(900.0);
        assert!(!state.sidebar_wide);
        assert!(!state.sidebar_visible);
        state.resolve_sidebar(1_200.0);
        assert!(state.sidebar_visible);
    }

    #[test]
    fn session_date_uses_month_and_day() {
        // 2026-11-08T00:00:00Z
        assert_eq!(format_session_date(1_762_560_000_000), "11/08");
        assert_eq!(format_session_date(0), "01/01");
    }

    #[test]
    fn model_menu_states_and_validation() {
        assert_eq!(
            model_menu_status(true, Some("E"), &[]),
            ModelMenuStatus::Loading
        );
        assert_eq!(
            model_menu_status(false, Some("E_AI_MODEL_CATALOG"), &[]),
            ModelMenuStatus::Error("E_AI_MODEL_CATALOG".into())
        );
        assert_eq!(model_menu_status(false, None, &[]), ModelMenuStatus::Empty);
        assert_eq!(
            model_menu_status(false, None, &["m".into()]),
            ModelMenuStatus::Ready
        );
        assert!(selectable_model("gpt-5"));
        assert!(!selectable_model(""));
        assert!(!selectable_model("bad\nmodel"));
        assert!(!selectable_model(&"x".repeat(257)));
    }

    #[test]
    fn run_status_labels_are_localized() {
        assert_eq!(
            run_status_label(Language::English, RunStatus::Succeeded),
            "Completed"
        );
        assert_eq!(
            run_status_label(Language::SimplifiedChinese, RunStatus::Succeeded),
            "已完成"
        );
        assert_eq!(
            run_status_key(RunStatus::Cancelled),
            TextKey::AiChatStatusCancelled
        );
    }

    #[test]
    fn result_text_joins_summary_findings_and_references() {
        let result = aps_ai::AnalysisResult {
            session_id: "s".into(),
            run_id: "r".into(),
            generation: 1,
            model: "m".into(),
            summary: "Summary".into(),
            findings: vec![aps_ai::AnalysisFinding {
                id: "f1".into(),
                severity: aps_ai::FindingSeverity::Warning,
                title: "Title".into(),
                explanation: "Why".into(),
                recommendation: "Fix".into(),
                analysis_confidence: 0.5,
                evidence_ids: vec!["e1".into()],
                candidate_ids: vec!["c1".into()],
            }],
        };
        let text = result_text(&result);
        assert!(text.contains("Summary"));
        assert!(text.contains("Title\nWhy\nFix"));
        assert!(text.contains("e1, c1"));
    }

    #[test]
    fn follow_latest_tracks_user_scroll() {
        let mut state = ChatState::new();
        assert!(state.follow_latest);
        state.on_user_scrolled(false);
        assert!(!state.follow_latest);
        state.on_user_scrolled(true);
        assert!(state.follow_latest);
    }
}

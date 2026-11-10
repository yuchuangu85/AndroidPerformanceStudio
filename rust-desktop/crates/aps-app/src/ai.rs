//! The Compose-shaped AI Analysis conversation workspace.
//!
//! This window replaces the old preflight-only form. One AiWorkspace entity
//! owns the DSH chat surface (sidebar, conversation, composer, dialogs) and the
//! runtime-driven state machine. Rendering never blocks: every runtime call
//! runs on the background executor and publishes under an epoch guard so a
//! stale response cannot replace newer state.

mod composer;
mod dialogs;
mod markdown;
mod messages;
mod page;
mod sidebar;
mod state;
mod style;
mod widgets;

use crate::notifications::{NoticeSnapshot, NoticeSource};
use aps_ai::runtime::{AiRuntime, FrozenRun, RunStatus, SessionSummary, TurnRecord};
use aps_ai::{AnalysisRequest, ProviderCatalog, ReportFormat, ReportLanguage};
use aps_ui_foundation::{Language, TextKey};
use gpui_kit::{
    component::input::{InputEvent, InputState, TextareaState},
    prelude::*,
    *,
};
use state::{ChatState, CopyFeedback, CopyOutcome};
use std::{
    path::PathBuf,
    rc::Rc,
    sync::Arc,
    time::{Duration, Instant},
};

/// In-window menu commands for the AI window.
#[derive(Clone, PartialEq, Eq, Debug)]
pub(crate) enum AiMenuCommand {
    NewChat,
    FocusSearch,
    ClearEvidence,
}

/// Identity of the rendered message content for follow-latest decisions.
#[derive(Default, PartialEq, Eq)]
pub(crate) struct MessageKey {
    turn_count: usize,
    conversation_id: Option<String>,
    activity_len: usize,
    status: Option<RunStatus>,
    error: Option<String>,
    pending_approval: Option<String>,
    busy: bool,
}

pub struct AiWorkspace {
    language: Language,
    runtime: Option<Arc<AiRuntime>>,
    state: ChatState,
    composer: Entity<TextareaState>,
    search: Entity<InputState>,
    rename_input: Entity<InputState>,
    page_focus: FocusHandle,
    message_scroll: ScrollHandle,
    epoch: u64,
    frozen: Option<FrozenRun>,
    bound_context: Option<AnalysisRequest>,
    bound_title: Option<String>,
    open_settings: Option<Rc<dyn Fn(&mut Window, &mut App)>>,
    open_source: Option<Rc<dyn Fn(String, &mut Window, &mut App)>>,
    studio: Option<WeakEntity<crate::shell::Studio>>,
    catalog: Option<ProviderCatalog>,
    audit_path: Option<PathBuf>,
    settings_requested: bool,
    started: Instant,
    /// Question queued while busy that should be sent as soon as the run ends.
    auto_send: Option<String>,
    /// Last rendered message composition; detects content that should follow.
    message_key: MessageKey,
    subscriptions: Vec<Subscription>,
    #[cfg(debug_assertions)]
    debug_demo: bool,
}

impl AiWorkspace {
    pub fn new(language: Language, window: &mut Window, cx: &mut Context<Self>) -> Self {
        let composer = cx.new(|cx| {
            TextareaState::new(window, cx)
                .placeholder(language.text(TextKey::AiChatAskQuestion))
                .submit_on_enter(true)
        });
        let search = cx.new(|cx| {
            InputState::new(window, cx)
                .placeholder(language.text(TextKey::AiChatSearchConversations))
        });
        let rename_input = cx.new(|cx| {
            InputState::new(window, cx).placeholder(language.text(TextKey::AiChatRename))
        });
        let mut subscriptions = Vec::new();
        subscriptions.push(
            cx.subscribe(&composer, |this, input, event: &InputEvent, cx| {
                if matches!(event, InputEvent::Change) {
                    this.state.draft = input.read(cx).value().to_string();
                    cx.notify();
                }
            }),
        );
        subscriptions.push(
            cx.subscribe(&search, |this, input, event: &InputEvent, cx| {
                if matches!(event, InputEvent::Change) {
                    this.state.search_query = input.read(cx).value().to_string();
                    cx.notify();
                }
            }),
        );
        let mut this = Self {
            language,
            runtime: None,
            state: ChatState::new(),
            composer,
            search,
            rename_input,
            page_focus: cx.focus_handle(),
            message_scroll: ScrollHandle::new(),
            epoch: 0,
            frozen: None,
            bound_context: None,
            bound_title: None,
            open_settings: None,
            open_source: None,
            studio: None,
            catalog: None,
            audit_path: None,
            settings_requested: false,
            started: Instant::now(),
            auto_send: None,
            message_key: MessageKey::default(),
            subscriptions,
            #[cfg(debug_assertions)]
            debug_demo: false,
        };
        #[cfg(debug_assertions)]
        this.maybe_debug_demo(window, cx);
        this
    }

    // ------------------------------------------------------------------
    // Host wiring
    // ------------------------------------------------------------------

    /// Inject the shared runtime created by the shell. new() deliberately does
    /// not open storage on its own.
    pub fn set_runtime(&mut self, runtime: Arc<AiRuntime>, cx: &mut Context<Self>) {
        self.runtime = Some(runtime);
        // The debug demo owns its fixture state; a real runtime injection must
        // not replace it with the empty database before screenshots.
        if self.demo_mode() {
            cx.notify();
            return;
        }
        let preferences = self
            .runtime
            .as_ref()
            .map(|runtime| runtime.preferences())
            .unwrap_or_default();
        self.state.model = preferences.model.clone();
        self.state.can_send = true;
        if self.state.selected_conversation_id.is_none() {
            self.start_conversation(cx);
        }
        self.reload_sessions(cx);
        self.refresh_model_catalog(false, cx);
        cx.notify();
    }

    pub fn runtime(&self) -> Option<Arc<AiRuntime>> {
        self.runtime.clone()
    }

    pub fn set_language(
        &mut self,
        language: Language,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        self.language = language;
        let ask = language.text(TextKey::AiChatAskQuestion);
        self.composer
            .update(cx, |state, cx| state.set_placeholder(ask, window, cx));
        let search = language.text(TextKey::AiChatSearchConversations);
        self.search
            .update(cx, |state, cx| state.set_placeholder(search, window, cx));
        let rename = language.text(TextKey::AiChatRename);
        self.rename_input
            .update(cx, |state, cx| state.set_placeholder(rename, window, cx));
        cx.notify();
    }

    /// Bind the workspace to a profiler's frozen bounded context. The next
    /// question analyzes exactly this evidence.
    pub fn bind_context(
        &mut self,
        request: AnalysisRequest,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        self.cancel_active_run(cx);
        self.bound_title = Some(format!("{} · {}", request.profiler, request.scope));
        self.bound_context = Some(request);
        self.state.pending_approval = None;
        self.state.status = None;
        self.state.error = None;
        self.state.can_include_source = self
            .bound_context
            .as_ref()
            .is_some_and(|request| !request.candidates.is_empty());
        self.state.include_source = false;
        self.state.context_title = self.bound_title.clone();
        if self.state.selected_conversation_id.is_none() {
            self.new_chat(window, cx);
        }
        cx.notify();
    }

    /// Profiler evidence changed: invalidate any prepared/resulting run but do
    /// not create a new conversation or touch unrelated history.
    pub fn invalidate_origin(&mut self, cx: &mut Context<Self>) {
        self.cancel_active_run(cx);
        self.bound_context = None;
        self.bound_title = None;
        self.state.context_title = None;
        self.state.include_source = false;
        self.state.can_include_source = false;
        self.state.pending_approval = None;
        self.state.status = None;
        self.state.error = None;
        self.state.activity.clear();
        cx.notify();
    }

    pub fn open_settings(&mut self, cx: &mut Context<Self>) {
        self.settings_requested = true;
        cx.notify();
    }

    pub fn set_open_settings(&mut self, callback: Box<dyn Fn(&mut Window, &mut App) + 'static>) {
        self.open_settings = Some(Rc::from(callback));
    }

    pub fn set_open_source(
        &mut self,
        callback: Box<dyn Fn(String, &mut Window, &mut App) + 'static>,
    ) {
        self.open_source = Some(Rc::from(callback));
    }

    // ------------------------------------------------------------------
    // Legacy host shims retained until the shell rewiring lands.
    // ------------------------------------------------------------------

    pub fn set_provider_catalog(&mut self, catalog: ProviderCatalog, cx: &mut Context<Self>) {
        self.catalog = Some(catalog);
        cx.notify();
    }

    pub fn set_studio(&mut self, studio: WeakEntity<crate::shell::Studio>) {
        self.studio = Some(studio);
    }

    pub fn set_audit_path(&mut self, path: Option<PathBuf>, cx: &mut Context<Self>) {
        self.audit_path = path;
        cx.notify();
    }

    pub fn set_request(
        &mut self,
        request: AnalysisRequest,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        self.bind_context(request, window, cx);
    }

    /// Settings page compatibility shim; the AI settings lane now drives
    /// runtime() directly, so this only renders a small status card.
    pub fn settings_provider_panel(&self, _cx: &mut Context<Self>) -> AnyElement {
        let language = self.language;
        div()
            .flex()
            .flex_col()
            .gap_1()
            .child(
                div()
                    .text_size(px(crate::studio::FONT_BODY))
                    .font_weight(FontWeight::SEMIBOLD)
                    .child(crate::studio::selectable(
                        language.text(TextKey::AiAiAnalysis),
                    )),
            )
            .child(div().text_size(px(crate::studio::FONT_SUPPORTING)).child(
                crate::studio::selectable(
                    language.text(TextKey::AiSelectActualProfilerEvidenceUsingItsAi),
                ),
            ))
            .into_any_element()
    }

    pub fn has_preflight(&self) -> bool {
        self.frozen.is_some()
    }

    // ------------------------------------------------------------------
    // Pure view helpers used by components
    // ------------------------------------------------------------------

    pub(crate) fn copy_feedback(&self, key: &str) -> Option<CopyOutcome> {
        self.state.copy_feedback.as_ref().and_then(|feedback| {
            (feedback.target == key && state::copy_feedback_active(feedback, Instant::now()))
                .then_some(feedback.outcome)
        })
    }

    pub(crate) fn copy_text(&mut self, key: &str, value: &str, cx: &mut Context<Self>) {
        let copied = {
            cx.write_to_clipboard(ClipboardItem::new_string(value.to_owned()));
            cx.read_from_clipboard()
                .and_then(|item| item.text())
                .as_deref()
                == Some(value)
        };
        let outcome = if copied {
            CopyOutcome::Copied
        } else {
            CopyOutcome::Failed
        };
        self.state.copy_feedback = Some(CopyFeedback {
            target: key.to_owned(),
            outcome,
            at: Instant::now(),
        });
        cx.notify();
        let key = key.to_owned();
        let entity = cx.entity();
        cx.spawn(async move |_, cx| {
            cx.background_executor()
                .timer(Duration::from_millis(state::COPY_FEEDBACK_MILLIS))
                .await;
            entity.update(cx, |this, cx| {
                if this
                    .state
                    .copy_feedback
                    .as_ref()
                    .is_some_and(|feedback| feedback.target == key)
                {
                    this.state.copy_feedback = None;
                    cx.notify();
                }
            });
        })
        .detach();
    }

    pub(crate) fn toggle_code_wrap(&mut self, key: &str, cx: &mut Context<Self>) {
        self.state.toggle_code_wrap(key);
        cx.notify();
    }

    pub(crate) fn open_source_chip(
        &mut self,
        candidate_id: String,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if let Some(callback) = self.open_source.clone() {
            callback(candidate_id, window, cx);
            return;
        }
        if let Some(studio) = self.studio.clone() {
            studio
                .update(cx, |studio, cx| {
                    studio.open_source_candidate(candidate_id, window, cx)
                })
                .ok();
        }
    }

    // ------------------------------------------------------------------
    // Conversation state machine. Runtime calls behind these transitions are
    // filled in by the controller slice; these remain safe without a runtime.
    // ------------------------------------------------------------------

    pub(crate) fn new_chat(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.start_conversation(cx);
        self.set_draft("", window, cx);
        cx.notify();
    }

    fn start_conversation(&mut self, cx: &mut Context<Self>) {
        self.cancel_active_run(cx);
        if let Some(id) = self.state.selected_conversation_id.clone() {
            self.state.drafts.insert(id, self.state.draft.clone());
        }
        let id = self
            .runtime
            .as_ref()
            .map(|runtime| runtime.new_conversation_id());
        self.state.selected_conversation_id = id;
        self.state.turns.clear();
        self.state.activity.clear();
        self.state.error = None;
        self.state.status = None;
        self.state.pending_approval = None;
        self.state.queued.clear();
        self.state.include_source = false;
        self.state.follow_latest = true;
        self.state.draft.clear();
        cx.notify();
    }

    pub(crate) fn set_draft(&mut self, value: &str, window: &mut Window, cx: &mut Context<Self>) {
        self.state.draft = value.to_owned();
        self.composer.update(cx, |state, cx| {
            state.set_value(value.to_owned(), window, cx)
        });
    }

    pub(crate) fn edit_question(
        &mut self,
        question: &str,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        self.set_draft(question, window, cx);
        self.composer
            .update(cx, |state, cx| state.focus(window, cx));
        cx.notify();
    }

    pub(crate) fn recall_last_question(
        &mut self,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> bool {
        if !self.state.draft.is_empty() {
            return false;
        }
        let Some(question) = self.state.last_question.clone() else {
            return false;
        };
        self.set_draft(&question, window, cx);
        cx.notify();
        true
    }

    pub(crate) fn select_conversation(
        &mut self,
        id: String,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if self.state.selected_conversation_id.as_deref() == Some(id.as_str()) {
            return;
        }
        if let Some(previous) = self.state.selected_conversation_id.clone() {
            self.state.drafts.insert(previous, self.state.draft.clone());
        }
        self.cancel_active_run(cx);
        let draft = self.state.drafts.get(&id).cloned().unwrap_or_default();
        self.state.selected_conversation_id = Some(id.clone());
        self.state.turns.clear();
        self.state.pending_approval = None;
        self.state.status = None;
        self.state.error = None;
        self.state.activity.clear();
        self.state.follow_latest = true;
        self.set_draft(&draft, window, cx);
        self.load_transcript(id, cx);
        cx.notify();
    }

    pub(crate) fn reload_sessions(&mut self, cx: &mut Context<Self>) {
        if self.demo_mode() {
            return;
        }
        let Some(runtime) = self.runtime.clone() else {
            return;
        };
        let epoch = self.epoch;
        let task = cx
            .background_executor()
            .spawn(async move { runtime.sessions(true) });
        cx.spawn(async move |this, cx| {
            let rows = task.await;
            this.update(cx, |this, cx| {
                if this.epoch == epoch {
                    this.state.sessions = rows;
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
    }

    pub(crate) fn load_transcript(&mut self, conversation_id: String, cx: &mut Context<Self>) {
        if self.demo_mode() {
            return;
        }
        let Some(runtime) = self.runtime.clone() else {
            return;
        };
        let epoch = self.epoch.wrapping_add(1);
        self.epoch = epoch;
        let lookup = conversation_id.clone();
        let task = cx.background_executor().spawn(async move {
            runtime
                .restore(&lookup)
                .map(|restored| restored.turns)
                .unwrap_or_default()
        });
        cx.spawn(async move |this, cx| {
            let turns = task.await;
            this.update(cx, |this, cx| {
                if this.epoch == epoch
                    && this.state.selected_conversation_id.as_deref()
                        == Some(conversation_id.as_str())
                {
                    this.state.turns = turns;
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
    }

    pub(crate) fn refresh_model_catalog(&mut self, refresh: bool, cx: &mut Context<Self>) {
        if self.demo_mode() {
            self.state.models = vec![
                "mock-responses-model".to_owned(),
                "mock-fast-model".to_owned(),
            ];
            self.state.models_loading = false;
            self.state.models_error = None;
            return;
        }
        let Some(runtime) = self.runtime.clone() else {
            return;
        };
        let preferences = runtime.preferences();
        let provider = preferences.active_provider;
        if !refresh {
            if let Some(models) = runtime.cached_models(provider) {
                self.state.models = models;
                self.state.models_error = None;
                self.state.models_loading = false;
                cx.notify();
                return;
            }
        }
        let epoch = self.epoch;
        self.state.models_loading = true;
        self.state.models_error = None;
        let task = cx
            .background_executor()
            .spawn(async move { runtime.load_models(provider, refresh).await });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.epoch != epoch {
                    return;
                }
                this.state.models_loading = false;
                match result {
                    Ok(models) => {
                        this.state.models = models;
                        this.state.models_error = None;
                    }
                    Err(error) => {
                        this.state.models.clear();
                        this.state.models_error = Some(error);
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    pub(crate) fn open_models(&mut self, cx: &mut Context<Self>) {
        if self.state.busy {
            return;
        }
        self.state.models_open = true;
        self.refresh_model_catalog(false, cx);
        cx.notify();
    }

    pub(crate) fn close_models(&mut self, cx: &mut Context<Self>) {
        self.state.models_open = false;
        cx.notify();
    }

    pub(crate) fn select_model(&mut self, model: String, cx: &mut Context<Self>) {
        if self.state.busy || !state::selectable_model(&model) {
            return;
        }
        if let Some(runtime) = self.runtime.clone() {
            let provider = runtime.preferences().active_provider;
            let model_for_task = model.clone();
            let task = cx
                .background_executor()
                .spawn(async move { runtime.select_model(provider, &model_for_task) });
            let epoch = self.epoch;
            cx.spawn(async move |this, cx| {
                let result = task.await;
                this.update(cx, |this, cx| {
                    if this.epoch != epoch {
                        return;
                    }
                    match result {
                        Ok(()) => {
                            this.state.model = model.clone();
                            this.state.models_open = false;
                        }
                        Err(error) => this.state.error = Some(error.to_string()),
                    }
                    cx.notify();
                })
                .ok();
            })
            .detach();
        }
        cx.notify();
    }

    pub(crate) fn focus_search(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.state.sidebar_visible = true;
        self.search.update(cx, |state, cx| state.focus(window, cx));
        cx.notify();
    }

    pub(crate) fn show_archived(&mut self, showing: bool, cx: &mut Context<Self>) {
        self.state.showing_archived = showing;
        cx.notify();
    }

    pub(crate) fn toggle_include_source(&mut self, include: bool, cx: &mut Context<Self>) {
        if self.state.busy {
            return;
        }
        self.state.include_source = include && self.state.can_include_source;
        self.state.models_open = false;
        cx.notify();
    }

    pub(crate) fn set_sidebar_visible(&mut self, visible: bool, cx: &mut Context<Self>) {
        self.state.sidebar_visible = visible;
        cx.notify();
    }

    /// Open the one-use preflight approval card. Rendering the frozen view is
    /// the only thing that can lead to execution.
    pub(crate) fn present_approval(&mut self, frozen: FrozenRun) {
        self.state.can_include_source = !frozen.view.sources.is_empty() && !self.state.busy;
        self.state.pending_approval = Some(frozen.view.clone());
        self.frozen = Some(frozen);
    }

    pub(crate) fn clear_approval(&mut self, cx: &mut Context<Self>) {
        self.frozen = None;
        self.state.pending_approval = None;
        self.state.busy = false;
        self.state.status = None;
        cx.notify();
    }

    pub(crate) fn cancel_active_run(&mut self, cx: &mut Context<Self>) {
        self.epoch = self.epoch.wrapping_add(1);
        if let Some(runtime) = self.runtime.as_ref() {
            runtime.cancel();
        }
        self.frozen = None;
        self.state.pending_approval = None;
        self.state.busy = false;
        self.state.status = None;
        self.state.models_loading = false;
        cx.notify();
    }

    pub(crate) fn open_artifact(
        &mut self,
        hash: String,
        _window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let path = self
            .runtime
            .as_ref()
            .and_then(|runtime| runtime.artifact_path(&hash));
        match path {
            Some(path) => {
                let url = format!("file://{}", path.display());
                cx.open_url(&url);
            }
            None => {
                self.state.error = Some("E_AI_ARTIFACT".to_owned());
                cx.notify();
            }
        }
    }

    // ------------------------------------------------------------------
    // Runs
    // ------------------------------------------------------------------

    /// Send the composer draft: queue it while a run is active, otherwise
    /// freeze a new prepare request under the current bounded context.
    pub(crate) fn send(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let question = self.state.draft.trim().to_owned();
        if question.is_empty() || question.chars().count() > 4096 {
            return;
        }
        if self.state.busy {
            match self.state.push_queued(question) {
                Ok(()) => self.set_draft("", window, cx),
                Err(code) => self.state.error = Some(code.to_owned()),
            }
            cx.notify();
            return;
        }
        self.prepare_question(question, window, cx);
    }

    fn prepare_question(&mut self, question: String, window: &mut Window, cx: &mut Context<Self>) {
        let Some(runtime) = self.runtime.clone() else {
            self.state.error = Some("E_AI_UNAVAILABLE".to_owned());
            cx.notify();
            return;
        };
        let preferences = runtime.preferences();
        let context = self.bound_context.clone();
        let direct_chat = context.is_none();
        let include_source = self.state.include_source;
        let mut frozen_context = context.clone();
        if let Some(request) = frozen_context.as_mut() {
            if !include_source {
                request.candidates.clear();
            }
        }
        let options = aps_ai::runtime::PrepareOptions {
            conversation_id: self
                .state
                .selected_conversation_id
                .clone()
                .unwrap_or_default(),
            context: frozen_context,
            question: Some(question.clone()),
            include_source,
            direct_chat,
            parent_turn: self
                .state
                .turns
                .iter()
                .rev()
                .find(|turn| turn.status.terminal())
                .map(|turn| turn.run_id.clone()),
            parent_conversation: None,
            report_format: preferences.report_format,
            report_language: preferences.report_language,
            agent_id: if direct_chat {
                "aps.chat".to_owned()
            } else {
                preferences.agent_id.clone()
            },
        };
        let epoch = self.epoch.wrapping_add(1);
        self.epoch = epoch;
        self.state.busy = true;
        self.state.status = Some(RunStatus::Preparing);
        self.state.error = None;
        self.state.pending_approval = None;
        self.frozen = None;
        self.state.last_question = Some(question);
        self.state.add_activity(
            self.state
                .selected_conversation_id
                .clone()
                .unwrap_or_default(),
            "prepare",
            "started",
        );
        self.set_draft("", window, cx);
        cx.notify();
        let task = cx
            .background_executor()
            .spawn(async move { runtime.prepare(options) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.epoch != epoch {
                    return;
                }
                match result {
                    Ok(frozen) => {
                        this.state.status = Some(RunStatus::AwaitingApproval);
                        this.state.add_activity(
                            frozen.view.run_id.clone(),
                            "prepare",
                            "awaiting_approval",
                        );
                        this.present_approval(frozen);
                    }
                    Err(error) => {
                        this.state.busy = false;
                        this.state.status = Some(RunStatus::RetryableFailure);
                        this.state.error = Some(error.to_string());
                        this.state.add_activity_error(
                            String::new(),
                            "prepare",
                            "failed",
                            error.to_string(),
                        );
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Approve the frozen request exactly once and execute it off-thread.
    pub(crate) fn approve(&mut self, cx: &mut Context<Self>) {
        let Some(frozen) = self.frozen.take() else {
            return;
        };
        let Some(runtime) = self.runtime.clone() else {
            self.state.error = Some("E_AI_UNAVAILABLE".to_owned());
            cx.notify();
            return;
        };
        let run_id = frozen.view.run_id.clone();
        let conversation_id = self.state.selected_conversation_id.clone();
        let epoch = self.epoch.wrapping_add(1);
        self.epoch = epoch;
        self.state.pending_approval = None;
        self.state.busy = true;
        self.state.status = Some(RunStatus::Running);
        self.state.error = None;
        self.state.add_activity(run_id.clone(), "run", "started");
        cx.notify();
        let task = cx
            .background_executor()
            .spawn(async move { runtime.execute(frozen).await });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.epoch != epoch {
                    return;
                }
                this.state.busy = false;
                match result {
                    Ok(result) => {
                        this.state.status = Some(RunStatus::Succeeded);
                        this.state.add_activity(run_id.clone(), "run", "succeeded");
                        this.state.last_question = None;
                        let _ = result;
                    }
                    Err(error) => {
                        this.state.status = Some(RunStatus::RetryableFailure);
                        this.state.error = Some(error.to_string());
                        this.state.add_activity_error(
                            run_id.clone(),
                            "run",
                            "failed",
                            error.to_string(),
                        );
                    }
                }
                this.reload_sessions(cx);
                if let Some(id) = conversation_id {
                    this.load_transcript(id, cx);
                }
                this.drain_queue(cx);
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Stop the active preparation or execution; a late answer is discarded by
    /// the epoch guard as well as the runtime's cancellation token.
    pub(crate) fn stop(&mut self, cx: &mut Context<Self>) {
        if !self.state.busy && self.state.pending_approval.is_none() {
            return;
        }
        self.epoch = self.epoch.wrapping_add(1);
        if let Some(runtime) = self.runtime.as_ref() {
            runtime.cancel();
        }
        self.frozen = None;
        self.state.pending_approval = None;
        self.state.busy = false;
        self.state.status = Some(RunStatus::Cancelled);
        self.state.add_activity(String::new(), "run", "cancelled");
        cx.notify();
    }

    pub(crate) fn cancel_preflight(&mut self, cx: &mut Context<Self>) {
        self.stop(cx);
        self.state.status = None;
        cx.notify();
    }

    /// Retry starts a fresh prepare from the failed question; it never replays
    /// a consumed approval.
    pub(crate) fn retry(
        &mut self,
        run_id: Option<String>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if self.state.busy {
            return;
        }
        let question = if let Some(run_id) = run_id.as_deref() {
            self.state
                .turns
                .iter()
                .find(|turn| turn.run_id == run_id)
                .and_then(|turn| turn.question.clone())
        } else {
            self.state
                .turns
                .last()
                .filter(|turn| turn.status.allows_chat_retry())
                .and_then(|turn| turn.question.clone())
                .or_else(|| self.state.last_question.clone())
        };
        match question {
            Some(question) if !question.trim().is_empty() => {
                self.state.error = None;
                self.prepare_question(question, window, cx);
            }
            _ => {
                self.state.error = Some("E_RETRY_UNAVAILABLE".to_owned());
                cx.notify();
            }
        }
    }

    pub(crate) fn drain_queue(&mut self, cx: &mut Context<Self>) {
        self.state.busy = false;
        let Some(next) = self.state.queued.first().cloned() else {
            return;
        };
        if next.conversation_id
            != self
                .state
                .selected_conversation_id
                .clone()
                .unwrap_or_default()
        {
            return;
        }
        self.state.queued.remove(0);
        self.state.include_source = next.include_source;
        self.state.draft = next.question.clone();
        self.auto_send = Some(next.question);
        cx.notify();
    }

    // ------------------------------------------------------------------
    // Conversation actions
    // ------------------------------------------------------------------

    fn spawn_conversation_action<F>(
        &mut self,
        cx: &mut Context<Self>,
        action: F,
        reload_sessions: bool,
        reload_transcript: bool,
    ) where
        F: FnOnce(Arc<AiRuntime>) -> Result<(), String> + Send + 'static,
    {
        let Some(runtime) = self.runtime.clone() else {
            self.state.error = Some("E_AI_UNAVAILABLE".to_owned());
            cx.notify();
            return;
        };
        let conversation_id = self.state.selected_conversation_id.clone();
        let task = cx
            .background_executor()
            .spawn(async move { action(runtime) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(()) => {
                        this.state.error = None;
                    }
                    Err(code) => this.state.error = Some(code),
                }
                if reload_sessions {
                    this.reload_sessions(cx);
                }
                if reload_transcript {
                    if let Some(id) = conversation_id {
                        this.load_transcript(id, cx);
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    pub(crate) fn rename_conversation(
        &mut self,
        id: String,
        title: String,
        cx: &mut Context<Self>,
    ) {
        let title = title.trim().to_owned();
        if title.is_empty() || title.chars().count() > 120 {
            return;
        }
        self.spawn_conversation_action(
            cx,
            move |runtime| {
                runtime
                    .rename_conversation(&id, &title)
                    .map_err(|_| "E_AI_STORAGE".to_owned())
            },
            true,
            true,
        );
        self.state.dialogs.rename = None;
        cx.notify();
    }

    pub(crate) fn pin_conversation(&mut self, id: String, pinned: bool, cx: &mut Context<Self>) {
        self.spawn_conversation_action(
            cx,
            move |runtime| {
                runtime
                    .pin_conversation(&id, pinned)
                    .map_err(|_| "E_AI_STORAGE".to_owned())
            },
            true,
            false,
        );
    }

    pub(crate) fn archive_conversation(
        &mut self,
        id: String,
        archived: bool,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let current = self.state.selected_conversation_id.clone();
        if archived && current.as_deref() == Some(id.as_str()) {
            self.new_chat(window, cx);
        }
        let Some(runtime) = self.runtime.clone() else {
            self.state.error = Some("E_AI_UNAVAILABLE".to_owned());
            cx.notify();
            return;
        };
        let archive_id = id.clone();
        let task = cx.background_executor().spawn(async move {
            runtime
                .archive_conversation(&archive_id, archived)
                .map_err(|_| "E_AI_STORAGE".to_owned())
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if let Err(code) = result {
                    this.state.error = Some(code);
                }
                this.reload_sessions(cx);
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    pub(crate) fn delete_conversation(
        &mut self,
        id: String,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let current = self.state.selected_conversation_id.clone();
        if current.as_deref() == Some(id.as_str()) {
            self.new_chat(window, cx);
        }
        let Some(runtime) = self.runtime.clone() else {
            self.state.error = Some("E_AI_UNAVAILABLE".to_owned());
            cx.notify();
            return;
        };
        let delete_id = id.clone();
        let task = cx.background_executor().spawn(async move {
            runtime
                .delete_conversation(&delete_id)
                .map_err(|_| "E_AI_STORAGE".to_owned())
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if let Err(code) = result {
                    this.state.error = Some(code);
                }
                this.state.queued.retain(|item| item.conversation_id != id);
                this.state.drafts.remove(&id);
                this.reload_sessions(cx);
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    pub(crate) fn fork_conversation(
        &mut self,
        id: String,
        parent_turn: Option<String>,
        cx: &mut Context<Self>,
    ) {
        if self.state.busy {
            return;
        }
        let Some(runtime) = self.runtime.clone() else {
            self.state.error = Some("E_AI_UNAVAILABLE".to_owned());
            cx.notify();
            return;
        };
        let fork_id = id.clone();
        let task = cx
            .background_executor()
            .spawn(async move { runtime.fork_conversation(&fork_id, parent_turn) });
        let entity = cx.entity();
        cx.spawn(async move |_, cx| {
            let result = task.await;
            entity.update(cx, |this, cx| {
                match result {
                    Ok(new_id) => {
                        this.state.selected_conversation_id = Some(new_id.clone());
                        this.state.turns.clear();
                        this.state.error = None;
                        this.load_transcript(new_id, cx);
                        this.reload_sessions(cx);
                    }
                    Err(code) => this.state.error = Some(code),
                }
                cx.notify();
            });
        })
        .detach();
    }

    pub(crate) fn remove_queued(&mut self, index: usize, cx: &mut Context<Self>) {
        self.state.remove_queued(index);
        cx.notify();
    }

    pub(crate) fn edit_queued(
        &mut self,
        index: usize,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if let Some(item) = self.state.edit_queued(index) {
            self.set_draft(&item.question, window, cx);
        }
        cx.notify();
    }

    pub(crate) fn start_ordinary_chat(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.bound_context = None;
        self.bound_title = None;
        self.state.context_title = None;
        self.state.can_include_source = false;
        self.state.include_source = false;
        self.new_chat(window, cx);
    }

    pub(crate) fn rebind_context(&mut self, cx: &mut Context<Self>) {
        if self.bound_context.is_some() {
            self.state.context_title = self.bound_title.clone();
            cx.notify();
        } else {
            self.state.error = Some("E_CONTEXT_UNAVAILABLE".to_owned());
            cx.notify();
        }
    }

    pub(crate) fn toggle_activity(&mut self, cx: &mut Context<Self>) {
        self.state.activity_expanded = !self.state.activity_expanded;
        cx.notify();
    }

    pub(crate) fn toggle_history(&mut self, cx: &mut Context<Self>) {
        self.state.history_expanded = !self.state.history_expanded;
        cx.notify();
    }

    pub(crate) fn open_rename_dialog(
        &mut self,
        id: String,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let title = self
            .state
            .sessions
            .iter()
            .find(|session| session.id == id)
            .map(|session| session.title.clone())
            .unwrap_or_default();
        self.rename_input
            .update(cx, |state, cx| state.set_value(title.clone(), window, cx));
        self.state.dialogs.rename = Some(state::RenamingConversation { id, title });
        cx.notify();
    }

    pub(crate) fn open_evidence_dialog(&mut self, id: String, cx: &mut Context<Self>) {
        self.state.dialogs.evidence = Some(id);
        cx.notify();
    }

    pub(crate) fn open_delete_dialog(&mut self, id: String, cx: &mut Context<Self>) {
        self.state.dialogs.delete = Some(id);
        cx.notify();
    }

    pub(crate) fn open_archive_dialog(&mut self, id: String, cx: &mut Context<Self>) {
        self.state.dialogs.archive = Some(id);
        cx.notify();
    }

    pub(crate) fn close_dialogs(&mut self, cx: &mut Context<Self>) {
        self.state.dialogs = state::Dialogs::default();
        cx.notify();
    }

    pub(crate) fn confirm_rename(&mut self, cx: &mut Context<Self>) {
        let Some(renaming) = self.state.dialogs.rename.clone() else {
            return;
        };
        let title = self.rename_input.read(cx).value().to_string();
        self.rename_conversation(renaming.id, title, cx);
    }

    pub(crate) fn confirm_delete(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let Some(id) = self.state.dialogs.delete.take() else {
            return;
        };
        self.delete_conversation(id, window, cx);
    }

    pub(crate) fn confirm_archive(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let Some(id) = self.state.dialogs.archive.take() else {
            return;
        };
        self.archive_conversation(id, true, window, cx);
    }

    pub(crate) fn scroll_to_top(&mut self, cx: &mut Context<Self>) {
        self.message_scroll.scroll_to_item(0);
        self.state.follow_latest = false;
        cx.notify();
    }

    pub(crate) fn scroll_to_latest(&mut self, cx: &mut Context<Self>) {
        self.message_scroll.scroll_to_bottom();
        self.state.follow_latest = true;
        cx.notify();
    }

    pub(crate) fn on_message_scrolled(&mut self, cx: &mut Context<Self>) {
        let offset = f32::from(self.message_scroll.offset().y);
        let max = f32::from(self.message_scroll.max_offset().y);
        self.state.follow_latest = (-offset) >= max - 4.0;
        cx.notify();
    }

    fn demo_mode(&self) -> bool {
        #[cfg(debug_assertions)]
        {
            self.debug_demo
        }
        #[cfg(not(debug_assertions))]
        {
            false
        }
    }

    #[cfg(debug_assertions)]
    fn maybe_debug_demo(&mut self, _window: &mut Window, cx: &mut Context<Self>) {
        if std::env::var_os("APS_RUST_AI_DEMO").is_none() {
            return;
        }
        self.debug_demo = true;
        self.state.model = "mock-responses-model".to_owned();
        self.state.can_send = true;
        self.state.context_title = Some("Frame · Frozen frame timeline evidence".to_owned());
        self.state.can_include_source = true;
        self.state.include_source = false;
        self.state.follow_latest = std::env::var_os("APS_RUST_AI_DEMO_TOP").is_none();
        self.state.models = vec![
            "mock-responses-model".to_owned(),
            "mock-fast-model".to_owned(),
        ];
        self.state.models_open = std::env::var_os("APS_RUST_AI_DEMO_MODELS").is_some();
        self.state.sessions = demo_sessions();
        self.state.selected_conversation_id = self
            .state
            .sessions
            .first()
            .map(|session| session.id.clone());
        self.state.turns = demo_turns();
        self.state.last_question = Some("What causes the deadline misses?".to_owned());
        if std::env::var_os("APS_RUST_AI_DEMO_APPROVAL").is_some() {
            self.state.status = Some(RunStatus::AwaitingApproval);
            self.state.busy = true;
            self.state.models_open = false;
            self.state.pending_approval = Some(demo_approval());
        }
        if std::env::var_os("APS_RUST_AI_DEMO_ARCHIVED").is_some() {
            self.state.showing_archived = true;
        }
        cx.notify();
    }
}

#[cfg(debug_assertions)]
fn demo_sessions() -> Vec<SessionSummary> {
    let now = aps_ai::platform::now_millis();
    vec![
        SessionSummary {
            id: "demo-session-frame".to_owned(),
            title: "Why do frames miss the 16ms deadline?".to_owned(),
            updated_at_millis: now,
            pinned: true,
            turn_count: 3,
            status: None,
            archived: false,
        },
        SessionSummary {
            id: "demo-session-memory".to_owned(),
            title: "Dominator tree review".to_owned(),
            updated_at_millis: now - 86_400_000,
            pinned: false,
            turn_count: 2,
            status: None,
            archived: false,
        },
        SessionSummary {
            id: "demo-session-archived".to_owned(),
            title: "Startup regression notes".to_owned(),
            updated_at_millis: now - 2 * 86_400_000,
            pinned: false,
            turn_count: 1,
            status: None,
            archived: true,
        },
    ]
}

#[cfg(debug_assertions)]
fn demo_turns() -> Vec<TurnRecord> {
    use aps_ai::{AnalysisFinding, AnalysisResult, FindingSeverity};
    let summary = concat!(
        "### Frame deadline review\n\n",
        "Answered **what caused the deadline misses** against bounded evidence.\n\n",
        "- deadline misses and platform jank stay distinct\n",
        "- counts are correlation, not proof of causality\n\n",
        "| signal | value |\n",
        "| --- | --- |\n",
        "| deadline misses | 13 |\n",
        "| jank clusters | 9 |\n\n",
        "> Correlation is not causality.\n\n",
        "```kotlin\n",
        "renderFrame()\n",
        "```\n",
    );
    let result = AnalysisResult {
        session_id: "demo-session-frame".to_owned(),
        run_id: "demo-run-1".to_owned(),
        generation: 1,
        model: "mock-responses-model".to_owned(),
        summary: summary.to_owned(),
        findings: vec![
            AnalysisFinding {
                id: "finding-demo-1".to_owned(),
                severity: FindingSeverity::Warning,
                title: "Deadline misses concentrate in one window".to_owned(),
                explanation: "**13 of 60** frames exceeded the 16 ms deadline, mostly in the animation window.".to_owned(),
                recommendation: "Check the layout pass first; do not infer causality from counts.".to_owned(),
                analysis_confidence: 0.72,
                evidence_ids: vec!["evidence-frame-timeline".to_owned()],
                candidate_ids: vec!["candidate-render-frame".to_owned()],
            },
            AnalysisFinding {
                id: "finding-demo-2".to_owned(),
                severity: FindingSeverity::Info,
                title: "Platform jank is listed separately".to_owned(),
                explanation: "Jank type stays explicit and is not merged into app attribution.".to_owned(),
                recommendation: String::new(),
                analysis_confidence: 0.55,
                evidence_ids: vec!["evidence-jank-type".to_owned()],
                candidate_ids: Vec::new(),
            },
        ],
    };
    vec![
        TurnRecord {
            run_id: "demo-run-1".to_owned(),
            generation: 1,
            status: RunStatus::Succeeded,
            question: Some("What causes the deadline misses?".to_owned()),
            result: Some(result),
            timestamp_millis: 1_762_560_000_000,
            artifact_hashes: vec![
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".to_owned(),
            ],
        },
        TurnRecord {
            run_id: "demo-run-2".to_owned(),
            generation: 2,
            status: RunStatus::RetryableFailure,
            question: Some("Summarize the jank clusters.".to_owned()),
            result: None,
            timestamp_millis: 1_762_560_100_000,
            artifact_hashes: Vec::new(),
        },
    ]
}

#[cfg(debug_assertions)]
fn demo_approval() -> aps_ai::runtime::ApprovalView {
    use aps_ai::platform::ProviderKind;
    aps_ai::runtime::ApprovalView {
        run_id: "demo-run-approval".to_owned(),
        session_id: "demo-session-frame".to_owned(),
        provider_id: "codex".to_owned(),
        provider_kind: ProviderKind::Codex,
        model: "mock-responses-model".to_owned(),
        agent_id: "aps.main".to_owned(),
        evidence_count: 2,
        candidate_count: 1,
        payload_bytes: 4096,
        planned_provider_calls: 1,
        planned_scripts: 0,
        report_format: ReportFormat::Html,
        report_language: ReportLanguage::Auto,
        question: Some("What causes the deadline misses?".to_owned()),
        evidence: vec![
            aps_ai::runtime::ApprovalEvidence {
                id: "evidence-frame-timeline".to_owned(),
                summary: "60 frames, 13 deadline misses".to_owned(),
            },
            aps_ai::runtime::ApprovalEvidence {
                id: "evidence-jank-type".to_owned(),
                summary: "9 clusters, platform jank separated".to_owned(),
            },
        ],
        sources: vec![aps_ai::runtime::ApprovalSource {
            id: "candidate-render-frame".to_owned(),
            relative_path: "app/src/main/kotlin/RenderFrame.kt".to_owned(),
        }],
        history: vec![aps_ai::runtime::ApprovalHistoryTurn {
            question: Some("Any earlier findings?".to_owned()),
            answer: "One bounded review, stored as untrusted context.".to_owned(),
        }],
        payload_hash: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef".to_owned(),
        permissions: vec![
            "PROVIDER_NETWORK".to_owned(),
            "READ_EVIDENCE".to_owned(),
            "WRITE_ARTIFACT".to_owned(),
        ],
    }
}
impl Drop for AiWorkspace {
    fn drop(&mut self) {
        if let Some(runtime) = self.runtime.as_ref() {
            runtime.cancel();
        }
    }
}

impl Render for AiWorkspace {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        if self.settings_requested {
            self.settings_requested = false;
            if let Some(callback) = self.open_settings.clone() {
                window.defer(cx, move |window, cx| callback(window, cx));
            }
        }
        if let Some(question) = self.auto_send.take() {
            if !self.state.busy && !question.trim().is_empty() {
                self.state.draft = question;
                self.send(window, cx);
            }
        }
        let page = page::render(self, window, cx);
        let chrome_dialogs = dialogs::render(self, window, cx);
        match chrome_dialogs {
            Some(dialog) => div()
                .relative()
                .size_full()
                .child(page)
                .child(dialog)
                .into_any_element(),
            None => page,
        }
    }
}

impl crate::shell::menubar::MenuHost for AiWorkspace {
    type Command = AiMenuCommand;

    fn menu_groups(
        &self,
        _language: Language,
    ) -> Vec<crate::shell::menubar::MenuGroup<Self::Command>> {
        use crate::shell::menubar::MenuGroup;
        vec![
            MenuGroup::new(TextKey::ShellMenuSession)
                .item(TextKey::AiChatNewChat, AiMenuCommand::NewChat)
                .item(
                    TextKey::AiChatSearchConversations,
                    AiMenuCommand::FocusSearch,
                )
                .separator()
                .item(TextKey::AiChatClearEvidence, AiMenuCommand::ClearEvidence),
        ]
    }

    fn run_menu_command(
        &mut self,
        command: Self::Command,
        window: &mut gpui_kit::Window,
        cx: &mut gpui_kit::Context<Self>,
    ) {
        match command {
            AiMenuCommand::NewChat => self.new_chat(window, cx),
            AiMenuCommand::FocusSearch => self.focus_search(window, cx),
            AiMenuCommand::ClearEvidence => self.invalidate_origin(cx),
        }
    }
}

impl NoticeSource for AiWorkspace {
    fn notice_snapshot(&self) -> Vec<NoticeSnapshot> {
        let mut notices = Vec::new();
        if let Some(error) = &self.state.error {
            notices.push(NoticeSnapshot::error(crate::evidence::clipped(error, 8192)));
        }
        if let Some(error) = &self.state.models_error {
            notices.push(NoticeSnapshot::error(crate::evidence::clipped(error, 8192)));
        }
        notices.retain(|notice| !notice.message.trim().is_empty());
        notices.truncate(8);
        notices
    }
}

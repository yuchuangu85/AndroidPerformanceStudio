//! AI settings: the Compose Provider / Agent / Skill / Execution-policy surface.
//!
//! Opening this page never discovers local secrets, imports code, executes tools
//! or authorizes provider transport. Credentials are written only through
//! AiRuntime (via save_connection) and are never part of settings or a persisted
//! text field.
//!
//! Page state is app-global because the AI settings page is rendered inside the
//! single Studio entity; keeping it in this module avoids touching the shell and
//! settings-page owners. The state is created once, then hydrates from the shared
//! Arc<AiRuntime> when the shell injects it.

use super::*;
use crate::studio::{self, StudioColors};
use aps_ai::runtime::{
    AiRuntime, ConnectionState, ImportFailure, ImportedConnection, RuntimeError,
};
use aps_ai::{
    AgentDescriptor, AiPreferences, BUILTIN_AGENTS, CredentialKind, ProviderKind, ReportFormat,
    ReportLanguage,
};
use gpui_kit::component::checkbox::Checkbox;
use gpui_kit::component::input::{Input, InputState};
use gpui_kit::component::radio::Radio;
use std::cell::Cell;
use std::collections::BTreeMap;
use std::fmt::Display;
use std::sync::Arc;

const PROVIDER_ORDER: [ProviderKind; 3] = [
    ProviderKind::Codex,
    ProviderKind::Claude,
    ProviderKind::Custom,
];
const FIELD_WIDTH: f32 = 320.;
const PROVIDER_SELECTOR_WIDTH: f32 = 240.;
const CREDENTIAL_INPUT_LIMIT: usize = 8_192;
const ENDPOINT_INPUT_LIMIT: usize = 2_048;
const MODEL_INPUT_LIMIT: usize = 256;

/// Compose import-failure taxonomy, projected for the settings card.
#[derive(Clone, Copy, PartialEq, Eq)]
enum ImportStatus {
    Imported { has_secret: bool },
    Failure(ImportFailure),
}

/// One per-connection unsaved draft. Switching providers never discards it.
struct ProviderDraft {
    api_key: Entity<InputState>,
    model: Entity<InputState>,
    endpoint: Entity<InputState>,
    models_endpoint: Entity<InputState>,
    credential_kind: CredentialKind,
    models: Vec<String>,
    models_loaded: bool,
    models_loading: bool,
    models_error: Option<String>,
    /// Endpoint pair the current model list was discovered against; editing an
    /// endpoint makes that list stale without discarding it silently.
    models_endpoints: Option<(String, String)>,
    working: bool,
    save_error: Option<String>,
    import: Option<ImportStatus>,
    /// A secret imported from local configuration is held by the runtime until
    /// Save consumes it; the page never sees the value itself.
    imported_secret: bool,
    generation: u64,
}

/// Plain snapshot copied out of the page state before any listener borrows the
/// app mutably. Also covers the runtime-unavailable placeholder path.
struct AiDraftView {
    credential_kind: CredentialKind,
    models: Vec<String>,
    models_loaded: bool,
    models_loading: bool,
    models_error: Option<String>,
    models_endpoints: Option<(String, String)>,
    working: bool,
    save_error: Option<String>,
    import: Option<ImportStatus>,
    imported_secret: bool,
    api_key: Entity<InputState>,
    model: Entity<InputState>,
    endpoint: Entity<InputState>,
    models_endpoint: Entity<InputState>,
    model_value: String,
    endpoint_value: String,
    models_endpoint_value: String,
    key_value: String,
}

/// Provider/Agent/Skill/Policy page state. App-global because it owns GPUI input
/// entities and the single Studio window is its only renderer.
struct AiSettingsUiState(AiSettingsUi);

impl Global for AiSettingsUiState {}

/// One-shot guard for creating the state during a render pass; creating input
/// entities needs a window, so creation runs on the next async update.
struct AiSettingsInitRequested;

impl Global for AiSettingsInitRequested {}

pub(super) struct AiSettingsUi {
    selected: ProviderKind,
    drafts: BTreeMap<ProviderKind, ProviderDraft>,
    hydrated: bool,
    hydration_in_flight: Cell<bool>,
    retain_confirm: bool,
    history_removed: Option<usize>,
    history_busy: bool,
    history_error: Option<String>,
    settings_error: Option<String>,
}

impl AiSettingsUi {
    fn new(window: &mut Window, cx: &mut Context<Studio>) -> Self {
        let drafts = PROVIDER_ORDER
            .into_iter()
            .map(|kind| (kind, ProviderDraft::new(window, cx)))
            .collect();
        Self {
            selected: ProviderKind::Custom,
            drafts,
            hydrated: false,
            hydration_in_flight: Cell::new(false),
            retain_confirm: false,
            history_removed: None,
            history_busy: false,
            history_error: None,
            settings_error: None,
        }
    }

    fn draft(&self, kind: ProviderKind) -> &ProviderDraft {
        self.drafts
            .get(&kind)
            .expect("every provider kind has a draft")
    }

    fn draft_mut(&mut self, kind: ProviderKind) -> &mut ProviderDraft {
        self.drafts
            .get_mut(&kind)
            .expect("every provider kind has a draft")
    }

    fn draft_view(&self, kind: ProviderKind, cx: &App) -> AiDraftView {
        let draft = self.draft(kind);
        AiDraftView {
            credential_kind: draft.credential_kind,
            models: draft.models.clone(),
            models_loaded: draft.models_loaded,
            models_loading: draft.models_loading,
            models_error: draft.models_error.clone(),
            models_endpoints: draft.models_endpoints.clone(),
            working: draft.working,
            save_error: draft.save_error.clone(),
            import: draft.import,
            imported_secret: draft.imported_secret,
            api_key: draft.api_key.clone(),
            model: draft.model.clone(),
            endpoint: draft.endpoint.clone(),
            models_endpoint: draft.models_endpoint.clone(),
            model_value: draft.model.read(cx).value().to_string(),
            endpoint_value: draft.endpoint.read(cx).value().to_string(),
            models_endpoint_value: draft.models_endpoint.read(cx).value().to_string(),
            key_value: draft.api_key.read(cx).value().to_string(),
        }
    }

    /// Hydration is one-shot per runtime appearance; a draft the user is editing
    /// is never overwritten by a late hydrate.
    fn begin_hydration(&self) -> bool {
        if self.hydrated || self.hydration_in_flight.get() {
            return false;
        }
        self.hydration_in_flight.set(true);
        true
    }

    fn end_hydration(&self) {
        self.hydration_in_flight.set(false);
    }
}

impl ProviderDraft {
    fn new(window: &mut Window, cx: &mut Context<Studio>) -> Self {
        let this = Self {
            api_key: ai_input(window, cx, true, CREDENTIAL_INPUT_LIMIT),
            model: ai_input(window, cx, false, MODEL_INPUT_LIMIT),
            endpoint: ai_input(window, cx, false, ENDPOINT_INPUT_LIMIT),
            models_endpoint: ai_input(window, cx, false, ENDPOINT_INPUT_LIMIT),
            credential_kind: CredentialKind::ApiKey,
            models: Vec::new(),
            models_loaded: false,
            models_loading: false,
            models_error: None,
            models_endpoints: None,
            working: false,
            save_error: None,
            import: None,
            imported_secret: false,
            generation: 0,
        };
        // Text edits change action enablement and stale-catalog detection.
        for input in [
            &this.api_key,
            &this.model,
            &this.endpoint,
            &this.models_endpoint,
        ] {
            cx.observe(input, |_, _, cx| cx.notify()).detach();
        }
        this
    }
}

fn ai_input(
    window: &mut Window,
    cx: &mut Context<Studio>,
    masked: bool,
    limit: usize,
) -> Entity<InputState> {
    cx.new(|cx| {
        let input = InputState::new(window, cx).validate(move |value, _| value.len() <= limit);
        if masked { input.masked(true) } else { input }
    })
}

fn display(value: &impl Display) -> &dyn Display {
    value
}

// ---------------------------------------------------------------------------
// Pure projections (unit-tested without a GPUI context)
// ---------------------------------------------------------------------------

fn ai_provider_label(kind: ProviderKind, language: Language) -> String {
    match kind {
        ProviderKind::Codex => "Codex".to_owned(),
        ProviderKind::Claude => "Claude".to_owned(),
        ProviderKind::Custom => language
            .text(TextKey::AiSettingsCustomConnectionLabel)
            .to_owned(),
    }
}

fn ai_credential_key(credential_kind: CredentialKind) -> TextKey {
    match credential_kind {
        CredentialKind::ApiKey => TextKey::AiSettingsApiKey,
        CredentialKind::BearerToken => TextKey::AiSettingsBearerToken,
    }
}

fn ai_credential_kind_label(kind: CredentialKind, language: Language) -> String {
    language.text(ai_credential_key(kind)).to_owned()
}

/// Compose providerHasCredential: the stored secret only counts for the same
/// credential kind and the exact endpoint that was saved with it.
fn ai_credential_configured(
    connection: &ConnectionState,
    credential_kind: CredentialKind,
    endpoint: &str,
) -> bool {
    connection.has_credential
        && connection.credential_kind == credential_kind
        && connection.endpoint.trim() == endpoint.trim()
}

/// Compose save enablement: endpoint + model + (stored credential or entered key).
fn ai_save_enabled(
    endpoint: &str,
    model: &str,
    configured: bool,
    imported_secret: bool,
    api_key: &str,
) -> bool {
    !endpoint.trim().is_empty()
        && !model.trim().is_empty()
        && (configured || imported_secret || !api_key.trim().is_empty())
}

fn ai_models_match_endpoints(view: &AiDraftView) -> bool {
    view.models_endpoints
        .as_ref()
        .is_some_and(|(endpoint, models_endpoint)| {
            endpoint == &view.endpoint_value && models_endpoint == &view.models_endpoint_value
        })
}

fn ai_models_selectable(view: &AiDraftView) -> bool {
    view.models_loaded && view.models_error.is_none() && ai_models_match_endpoints(view)
}

fn import_failure_key(failure: ImportFailure) -> TextKey {
    match failure {
        ImportFailure::NotFound => TextKey::AiSettingsImportNotFound,
        ImportFailure::Malformed => TextKey::AiSettingsImportMalformed,
        ImportFailure::PermissionDenied => TextKey::AiSettingsImportDenied,
        ImportFailure::UnsupportedAuth => TextKey::AiSettingsImportUnsupportedAuth,
        ImportFailure::UnsupportedConfig => TextKey::AiSettingsImportUnsupportedConfig,
    }
}

fn report_format_label(format: ReportFormat, language: Language) -> String {
    language
        .text(match format {
            ReportFormat::Html => TextKey::AiSettingsFormatHtml,
            ReportFormat::Markdown => TextKey::AiSettingsFormatMarkdown,
        })
        .to_owned()
}

fn report_language_label(report_language: ReportLanguage, language: Language) -> String {
    language
        .text(match report_language {
            ReportLanguage::Auto => TextKey::AiSettingsLanguageAuto,
            ReportLanguage::English => TextKey::AiSettingsLanguageEnglish,
            ReportLanguage::SimplifiedChinese => TextKey::AiSettingsLanguageChinese,
        })
        .to_owned()
}

fn ai_agent_description(descriptor: &AgentDescriptor, language: Language) -> String {
    match descriptor.id {
        "aps.main" => language
            .text(TextKey::AiSettingsAgentApsMainDescription)
            .to_owned(),
        "aps.chat" => language
            .text(TextKey::AiSettingsAgentApsChatDescription)
            .to_owned(),
        _ => descriptor.description.to_owned(),
    }
}

// ---------------------------------------------------------------------------
// Rendering
// ---------------------------------------------------------------------------

impl Studio {
    fn ai_runtime(&self, cx: &App) -> Option<Arc<AiRuntime>> {
        self.ai.read(cx).runtime()
    }

    pub(super) fn settings_ai(&self, cx: &mut Context<Self>) -> AnyElement {
        let language = self.settings.language;
        let colors = StudioColors::from_theme(cx);
        let tab = self.settings.ai_settings_tab.min(3);
        let tabs = [
            TextKey::AiSettingsProvider,
            TextKey::AiSettingsAgent,
            TextKey::AiSettingsSkill,
            TextKey::AiSettingsPolicy,
        ];
        let selector = div().flex().justify_center().flex_shrink_0().child(
            div()
                .id("ai-settings-tabs")
                .flex()
                .overflow_x_scroll()
                .border_1()
                .border_color(colors.border)
                .rounded(px(6.))
                .children(tabs.into_iter().enumerate().map(|(index, key)| {
                    studio::tab_button(
                        ("ai-settings-tab", index),
                        language.text(key),
                        tab as usize == index,
                        &colors,
                    )
                    .h(px(30.))
                    .on_click(cx.listener(move |this, _, window, cx| {
                        this.settings.ai_settings_tab = index as u8;
                        this.persist_settings(window, cx);
                    }))
                })),
        );
        let initialized = self.ai_settings_ensure(cx);
        let runtime = self.ai_runtime(cx);
        if initialized {
            self.ai_settings_schedule_hydration(runtime.as_ref(), cx);
        }
        let body = if !initialized {
            div()
                .flex()
                .flex_col()
                .gap(px(4.))
                .child(studio::supporting(
                    language.text(TextKey::AiSettingsWorking),
                    &colors,
                ))
                .into_any_element()
        } else {
            match tab {
                0 => self.ai_provider_body(language, &colors, runtime.as_ref(), cx),
                1 => self.ai_agent_body(language, &colors, runtime.as_ref(), cx),
                2 => self.ai_skill_body(language, &colors, runtime.as_ref(), cx),
                _ => self.ai_policy_body(language, &colors, runtime.as_ref(), cx),
            }
        };
        div()
            .size_full()
            .min_h_0()
            .flex()
            .flex_col()
            .gap(px(8.))
            .p(px(4.))
            .text_size(px(typography::BODY))
            .child(
                div()
                    .flex_shrink_0()
                    .text_size(px(typography::TITLE))
                    .font_weight(FontWeight::SEMIBOLD)
                    .child(crate::studio::selectable(
                        language.text(TextKey::AiSettingsTitle),
                    )),
            )
            .child(selector)
            .child(
                div()
                    .id("ai-settings-category-content")
                    .flex_1()
                    .min_h_0()
                    .overflow_y_scroll()
                    .child(body),
            )
            .into_any_element()
    }

    /// Create the page state once. Input entities need a window, so creation is
    /// posted through the async updater instead of being performed in render.
    fn ai_settings_ensure(&self, cx: &mut Context<Self>) -> bool {
        if cx.has_global::<AiSettingsUiState>() {
            return true;
        }
        if cx.has_global::<AiSettingsInitRequested>() {
            return false;
        }
        cx.set_global(AiSettingsInitRequested);
        cx.spawn(async move |this, cx| {
            let _ = this.update_in(cx, |studio, window, cx| {
                if !cx.has_global::<AiSettingsUiState>() {
                    let state = AiSettingsUi::new(window, cx);
                    cx.set_global(AiSettingsUiState(state));
                    studio.ai_hydrate(window, cx);
                }
                cx.notify();
            });
        })
        .detach();
        false
    }

    /// Hydrate once the shell has injected the shared runtime. Late hydration
    /// never overwrites a draft the user already edited because it only runs while
    /// the state is not yet hydrated.
    fn ai_settings_schedule_hydration(
        &self,
        runtime: Option<&Arc<AiRuntime>>,
        cx: &mut Context<Self>,
    ) {
        let Some(_) = runtime else {
            return;
        };
        let schedule = cx
            .try_global::<AiSettingsUiState>()
            .map(|state| state.0.begin_hydration())
            .unwrap_or(false);
        if !schedule {
            return;
        }
        cx.spawn(async move |this, cx| {
            let _ = this.update_in(cx, |studio, window, cx| {
                if cx.has_global::<AiSettingsUiState>() {
                    studio.ai_hydrate(window, cx);
                    cx.global_mut::<AiSettingsUiState>().0.end_hydration();
                }
                cx.notify();
            });
        })
        .detach();
    }

    fn ai_hydrate(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let Some(runtime) = self.ai_runtime(cx) else {
            return;
        };
        let preferences = runtime.preferences();
        if !cx.has_global::<AiSettingsUiState>() {
            return;
        }
        let mut updates: Vec<(Entity<InputState>, String)> = Vec::new();
        {
            let state = &mut cx.global_mut::<AiSettingsUiState>().0;
            if !state.hydrated {
                state.selected = preferences.active_provider;
            }
            for kind in PROVIDER_ORDER {
                let connection = preferences.connection(kind);
                let draft = state.draft_mut(kind);
                updates.push((draft.endpoint.clone(), connection.endpoint.clone()));
                updates.push((
                    draft.models_endpoint.clone(),
                    connection.models_endpoint.clone(),
                ));
                updates.push((draft.model.clone(), connection.model.clone()));
                draft.credential_kind = connection.credential_kind;
                if let Some(models) = runtime.cached_models(kind) {
                    draft.models = models;
                    draft.models_loaded = true;
                    draft.models_error = None;
                    draft.models_endpoints = Some((
                        connection.endpoint.clone(),
                        connection.models_endpoint.clone(),
                    ));
                }
            }
            state.hydrated = true;
        }
        for (input, value) in updates {
            input.update(cx, |input, cx| input.set_value(value, window, cx));
        }
        // Compose auto-refreshes the model list once a configured card appears.
        // This runs only from the one-shot hydrate path, never from a plain render.
        let auto_refresh = {
            let state = cx.global::<AiSettingsUiState>();
            let draft = state.0.draft(state.0.selected);
            runtime.connection(state.0.selected).has_credential
                && !draft.working
                && !draft.models_loading
        };
        if auto_refresh {
            let selected = cx.global::<AiSettingsUiState>().0.selected;
            self.ai_refresh_models(selected, true, cx);
        }
    }

    fn ai_settings_error_line(
        &self,
        language: Language,
        colors: &StudioColors,
        cx: &App,
    ) -> Option<AnyElement> {
        let code = cx
            .try_global::<AiSettingsUiState>()
            .and_then(|state| state.0.settings_error.clone())?;
        Some(error_line(
            language.format(
                TextKey::AiSettingsSettingsSaveFailed,
                &[("code", display(&code))],
            ),
            colors,
        ))
    }

    fn ai_draft_view(&self, kind: ProviderKind, cx: &App) -> Option<AiDraftView> {
        cx.try_global::<AiSettingsUiState>()
            .map(|state| state.0.draft_view(kind, cx))
    }

    fn ai_provider_body(
        &self,
        language: Language,
        colors: &StudioColors,
        runtime: Option<&Arc<AiRuntime>>,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let Some(selected) = cx
            .try_global::<AiSettingsUiState>()
            .map(|state| state.0.selected)
        else {
            return ai_unavailable(language, colors);
        };
        let Some(view) = self.ai_draft_view(selected, cx) else {
            return ai_unavailable(language, colors);
        };
        let saved = runtime.map(|runtime| runtime.connection(selected));
        let configured = saved.as_ref().is_some_and(|connection| {
            ai_credential_configured(connection, view.credential_kind, &view.endpoint_value)
        });
        let runtime_available = runtime.is_some();
        let disabled = view.working || !runtime_available;
        let save_ready = !disabled
            && ai_save_enabled(
                &view.endpoint_value,
                &view.model_value,
                configured,
                view.imported_secret,
                &view.key_value,
            );
        let models_match = ai_models_match_endpoints(&view);
        let model_choices = if ai_models_selectable(&view) {
            view.models.clone()
        } else {
            Vec::new()
        };
        let selected_model = model_choices
            .contains(&view.model_value)
            .then(|| view.model_value.clone());
        let count = model_choices.len().to_string();
        let model_label = selected_model.unwrap_or_else(|| {
            language.format(
                TextKey::AiSettingsChooseModel,
                &[("count", display(&count))],
            )
        });

        // Provider kind dropdown: compact and disabled while a card operation runs.
        let weak = cx.entity().downgrade();
        let provider_selector = Button::new("ai-provider-selector")
            .w(px(PROVIDER_SELECTOR_WIDTH))
            .h(px(30.))
            .justify_start()
            .border_1()
            .border_color(colors.border)
            .label(ai_provider_label(selected, language))
            .disabled(disabled)
            .dropdown_menu(move |menu, _, _| {
                PROVIDER_ORDER
                    .into_iter()
                    .fold(menu.min_w(px(200.)), |menu, kind| {
                        let weak = weak.clone();
                        menu.item(
                            PopupMenuItem::new(ai_provider_label(kind, language)).on_click(
                                move |_, _, cx| {
                                    let _ = weak.update(cx, |_, cx| {
                                        if cx.has_global::<AiSettingsUiState>() {
                                            cx.global_mut::<AiSettingsUiState>().0.selected = kind;
                                        }
                                        cx.notify();
                                    });
                                },
                            ),
                        )
                    })
            });
        let applied = runtime
            .map(|runtime| runtime.preferences().active_provider)
            .unwrap_or(selected);

        let import_button = if selected.saves_locally() {
            Some(
                studio::text_button(
                    "ai-connection-import",
                    language.text(TextKey::AiSettingsReadLocalConfig),
                    !disabled,
                    false,
                    colors,
                )
                .on_click(cx.listener(move |this, _, _, cx| this.ai_import_config(selected, cx))),
            )
        } else {
            None
        };
        let mut card = studio::card(colors);
        if !runtime_available {
            card = card.child(error_line(
                language.text(TextKey::AiSettingsRuntimeUnavailable),
                colors,
            ));
        }
        card = card.child(
            div()
                .flex()
                .items_center()
                .justify_between()
                .gap(px(8.))
                .child(studio::section_title(
                    ai_provider_label(selected, language),
                    colors,
                ))
                .children(import_button),
        );
        if let Some(import) = view.import {
            match import {
                ImportStatus::Imported { has_secret } => {
                    card = card.child(studio::supporting(
                        language.text(TextKey::AiSettingsImported),
                        colors,
                    ));
                    if !has_secret {
                        card = card.child(studio::supporting(
                            language.text(TextKey::AiSettingsImportedWithoutKey),
                            colors,
                        ));
                    }
                }
                ImportStatus::Failure(failure) => {
                    card = card.child(error_line(
                        language.text(import_failure_key(failure)),
                        colors,
                    ));
                }
            }
        }
        if selected == ProviderKind::Claude {
            let weak = cx.entity().downgrade();
            let credential_kind_label = ai_credential_kind_label(view.credential_kind, language);
            let credential_selector = Button::new("ai-claude-credential-kind")
                .w(px(FIELD_WIDTH))
                .h(px(30.))
                .justify_start()
                .border_1()
                .border_color(colors.border)
                .label(credential_kind_label)
                .disabled(disabled)
                .dropdown_menu(move |menu, _, _| {
                    [CredentialKind::ApiKey, CredentialKind::BearerToken]
                        .into_iter()
                        .fold(menu.min_w(px(200.)), |menu, credential_kind| {
                            let weak = weak.clone();
                            menu.item(
                                PopupMenuItem::new(ai_credential_kind_label(
                                    credential_kind,
                                    language,
                                ))
                                .on_click(move |_, window, cx| {
                                    let _ = weak.update(cx, |this, cx| {
                                        this.ai_change_credential_kind(
                                            selected,
                                            credential_kind,
                                            window,
                                            cx,
                                        );
                                    });
                                }),
                            )
                        })
                });
            card = card
                .child(studio::section_title(
                    language.text(TextKey::AiSettingsCredentialKind),
                    colors,
                ))
                .child(credential_selector);
        }
        card = card.child(studio::supporting(
            language.text(if configured {
                TextKey::AiSettingsCredentialConfigured
            } else {
                TextKey::AiSettingsCredentialMissing
            }),
            colors,
        ));
        if !configured && view.key_value.trim().is_empty() {
            card = card.child(error_line(
                language.text(TextKey::AiSettingsCredentialRequired),
                colors,
            ));
        }
        card = card.child(ai_field(
            language.text(ai_credential_key(view.credential_kind)),
            &view.api_key,
            disabled,
            colors,
        ));
        // Model row: dropdown from the catalog plus an explicit refresh.
        let weak = cx.entity().downgrade();
        let model_input = view.model.clone();
        let model_choices_for_menu = model_choices.clone();
        let model_picker = Button::new("ai-model-picker")
            .w(px(FIELD_WIDTH))
            .h(px(30.))
            .justify_start()
            .border_1()
            .border_color(colors.border)
            .label(model_label)
            .disabled(disabled || model_choices_for_menu.is_empty())
            .dropdown_menu(move |menu, _, _| {
                model_choices_for_menu
                    .iter()
                    .fold(menu.min_w(px(200.)), |menu, model| {
                        let weak = weak.clone();
                        let model_input = model_input.clone();
                        let chosen = model.clone();
                        menu.item(PopupMenuItem::new(model.clone()).on_click(
                            move |_, window, cx| {
                                let _ = weak.update(cx, |_, cx| {
                                    model_input.update(cx, |input, cx| {
                                        input.set_value(chosen.clone(), window, cx)
                                    });
                                    cx.notify();
                                });
                            },
                        ))
                    })
            });
        let refresh_enabled = !disabled
            && !view.endpoint_value.trim().is_empty()
            && (configured || !view.key_value.trim().is_empty());
        card =
            card.child(
                div()
                    .flex()
                    .items_center()
                    .gap(px(8.))
                    .child(model_picker)
                    .child(
                        studio::text_button(
                            "ai-refresh-models",
                            language.text(TextKey::AiSettingsRefreshModels),
                            refresh_enabled,
                            false,
                            colors,
                        )
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.ai_refresh_models(selected, true, cx)
                        })),
                    )
                    .when(view.models_loading, |row| {
                        row.child(studio::supporting(
                            language.text(TextKey::AiSettingsModelsLoading),
                            colors,
                        ))
                    }),
            );
        if models_match {
            if let Some(code) = view.models_error.clone() {
                card = card.child(error_line(
                    language.format(TextKey::AiSettingsModelsFailed, &[("code", display(&code))]),
                    colors,
                ));
            } else if view.models_loading {
                card = card.child(studio::supporting(
                    language.text(TextKey::AiSettingsModelsLoading),
                    colors,
                ));
            } else if view.models_loaded && view.models.is_empty() {
                card = card.child(studio::supporting(
                    language.text(TextKey::AiSettingsModelsEmpty),
                    colors,
                ));
            }
        }
        card = card
            .child(ai_field(
                language.text(TextKey::AiSettingsModel),
                &view.model,
                disabled,
                colors,
            ))
            .child(ai_field(
                language.text(TextKey::AiSettingsEndpoint),
                &view.endpoint,
                disabled,
                colors,
            ));
        if selected != ProviderKind::Claude {
            card = card
                .child(ai_field(
                    language.text(TextKey::AiSettingsModelsEndpoint),
                    &view.models_endpoint,
                    disabled,
                    colors,
                ))
                .child(studio::supporting(
                    language.text(TextKey::AiSettingsModelsEndpointHint),
                    colors,
                ));
        }
        card = card
            .child(
                studio::text_button(
                    "ai-connection-save",
                    language.text(TextKey::AiSettingsSaveApply),
                    save_ready,
                    true,
                    colors,
                )
                .on_click(cx.listener(move |this, _, window, cx| {
                    this.ai_save_connection(selected, window, cx)
                })),
            )
            .child(studio::supporting(
                language.text(TextKey::AiSettingsConnectionNote),
                colors,
            ));
        if view.working {
            card = card.child(studio::supporting(
                language.text(TextKey::AiSettingsWorking),
                colors,
            ));
        }
        if view.save_error.is_some() {
            card = card.child(error_line(
                language.text(TextKey::AiSettingsSaveFailed),
                colors,
            ));
        }
        let mut body = div()
            .flex()
            .flex_col()
            .gap(px(8.))
            .child(
                div()
                    .flex()
                    .items_center()
                    .gap(px(8.))
                    .child(provider_selector),
            )
            .child(studio::supporting(
                language.format(
                    TextKey::AiSettingsAppliedProvider,
                    &[("provider", display(&ai_provider_label(applied, language)))],
                ),
                colors,
            ));
        if let Some(line) = self.ai_settings_error_line(language, colors, cx) {
            body = body.child(line);
        }
        body.child(card).into_any_element()
    }

    /// Compose Agent category: ManagementExtensions over agent packages.
    fn ai_agent_body(
        &self,
        language: Language,
        colors: &StudioColors,
        runtime: Option<&Arc<AiRuntime>>,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let active_agent = runtime
            .map(|runtime| runtime.preferences().agent_id)
            .unwrap_or_default();
        let runtime_available = runtime.is_some();
        let mut body = div().flex().flex_col().gap(px(8.)).child(ai_import_card(
            language.text(TextKey::AiSettingsImportAgent),
            "ai-agent-import",
            language,
            colors,
        ));
        if !runtime_available {
            body = body.child(error_line(
                language.text(TextKey::AiSettingsRuntimeUnavailable),
                colors,
            ));
        }
        if let Some(line) = self.ai_settings_error_line(language, colors, cx) {
            body = body.child(line);
        }
        for (index, descriptor) in BUILTIN_AGENTS.iter().enumerate() {
            let id = descriptor.id.to_owned();
            let active = id == active_agent;
            let chat_only = descriptor.id == "aps.chat";
            let mut card = studio::card(colors)
                .child(accent_title(descriptor.label, colors))
                .child(studio::supporting(
                    language.text(TextKey::AiSettingsBuiltin),
                    colors,
                ))
                .child(studio::supporting(
                    ai_agent_description(descriptor, language),
                    colors,
                ))
                .child(studio::supporting(
                    language.format(
                        TextKey::AiSettingsAgentPermissions,
                        &[("permissions", display(&descriptor.permissions.join(", ")))],
                    ),
                    colors,
                ));
            if chat_only {
                card = card.child(studio::supporting(
                    language.text(TextKey::AiSettingsAgentChatOnly),
                    colors,
                ));
            } else {
                let select_id = id.clone();
                card = card.child(
                    Radio::new(("ai-agent-active", index))
                        .label(language.text(TextKey::AiSettingsAgentActive))
                        .checked(active)
                        .disabled(!runtime_available)
                        .on_click(cx.listener(move |this, checked: &bool, _, cx| {
                            if *checked {
                                this.ai_select_agent(select_id.clone(), cx);
                            }
                        })),
                );
            }
            card = card.child(
                Checkbox::new(("ai-agent-enabled", index))
                    .label(language.text(TextKey::AiSettingsEnabled))
                    .checked(true)
                    .disabled(true),
            );
            body = body.child(card);
        }
        body.into_any_element()
    }

    /// Compose Skill category: ManagementExtensions over skill packages.
    fn ai_skill_body(
        &self,
        language: Language,
        colors: &StudioColors,
        runtime: Option<&Arc<AiRuntime>>,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let mut body = div().flex().flex_col().gap(px(8.)).child(ai_import_card(
            language.text(TextKey::AiSettingsImportSkill),
            "ai-skill-import",
            language,
            colors,
        ));
        if runtime.is_none() {
            body = body.child(error_line(
                language.text(TextKey::AiSettingsRuntimeUnavailable),
                colors,
            ));
        }
        if let Some(line) = self.ai_settings_error_line(language, colors, cx) {
            body = body.child(line);
        }
        body.into_any_element()
    }

    /// Compose Policy category: execution permissions, report output and
    /// conversation-history retention cards.
    fn ai_policy_body(
        &self,
        language: Language,
        colors: &StudioColors,
        runtime: Option<&Arc<AiRuntime>>,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let preferences = runtime
            .map(|runtime| runtime.preferences())
            .unwrap_or_default();
        let runtime_available = runtime.is_some();
        let (retain_confirm, history_busy, history_error, history_removed) = cx
            .try_global::<AiSettingsUiState>()
            .map(|state| {
                (
                    state.0.retain_confirm,
                    state.0.history_busy,
                    state.0.history_error.clone(),
                    state.0.history_removed,
                )
            })
            .unwrap_or((false, false, None, None));

        let artifacts = Checkbox::new("ai-policy-allow-artifacts")
            .label(language.text(TextKey::AiSettingsAllowReports))
            .checked(preferences.allow_artifacts)
            .disabled(!runtime_available)
            .on_click(cx.listener(|this, checked: &bool, _, cx| {
                let value = *checked;
                this.ai_apply_preferences(cx, move |preferences| {
                    preferences.allow_artifacts = value
                });
            }));
        let scripts = Checkbox::new("ai-policy-allow-scripts")
            .label(language.text(TextKey::AiSettingsAllowScripts))
            .checked(false)
            .disabled(true);
        let mut permissions = studio::card(colors)
            .child(accent_title(
                language.text(TextKey::AiSettingsPermissions),
                colors,
            ))
            .child(artifacts)
            .child(scripts)
            .child(studio::supporting(
                language.text(TextKey::AiSettingsPermissionsUnavailable),
                colors,
            ));
        if let Some(line) = self.ai_settings_error_line(language, colors, cx) {
            permissions = permissions.child(line);
        }

        let weak = cx.entity().downgrade();
        let format_selector = Button::new("ai-report-format")
            .w(px(FIELD_WIDTH))
            .h(px(30.))
            .justify_start()
            .border_1()
            .border_color(colors.border)
            .label(report_format_label(preferences.report_format, language))
            .disabled(!runtime_available)
            .dropdown_menu(move |menu, _, _| {
                [ReportFormat::Html, ReportFormat::Markdown]
                    .into_iter()
                    .fold(menu.min_w(px(200.)), |menu, format| {
                        let weak = weak.clone();
                        menu.item(
                            PopupMenuItem::new(report_format_label(format, language)).on_click(
                                move |_, _, cx| {
                                    let _ = weak.update(cx, |this, cx| {
                                        this.ai_apply_preferences(cx, move |preferences| {
                                            preferences.report_format = format
                                        });
                                    });
                                },
                            ),
                        )
                    })
            });
        let weak = cx.entity().downgrade();
        let language_selector = Button::new("ai-report-language")
            .w(px(FIELD_WIDTH))
            .h(px(30.))
            .justify_start()
            .border_1()
            .border_color(colors.border)
            .label(report_language_label(preferences.report_language, language))
            .disabled(!runtime_available)
            .dropdown_menu(move |menu, _, _| {
                [
                    ReportLanguage::Auto,
                    ReportLanguage::English,
                    ReportLanguage::SimplifiedChinese,
                ]
                .into_iter()
                .fold(menu.min_w(px(220.)), |menu, report_language| {
                    let weak = weak.clone();
                    menu.item(
                        PopupMenuItem::new(report_language_label(report_language, language))
                            .on_click(move |_, _, cx| {
                                let _ = weak.update(cx, |this, cx| {
                                    this.ai_apply_preferences(cx, move |preferences| {
                                        preferences.report_language = report_language
                                    });
                                });
                            }),
                    )
                })
            });
        let report = studio::card(colors)
            .child(accent_title(
                language.text(TextKey::AiSettingsReportOutput),
                colors,
            ))
            .child(format_selector)
            .child(language_selector);

        let mut history = studio::card(colors)
            .child(accent_title(
                language.text(TextKey::AiSettingsHistory),
                colors,
            ))
            .child(
                studio::text_button(
                    "ai-retain-history",
                    language.text(TextKey::AiSettingsRetainHistory),
                    runtime_available && !history_busy,
                    false,
                    colors,
                )
                .on_click(cx.listener(|_, _, _, cx| {
                    if cx.has_global::<AiSettingsUiState>() {
                        let state = &mut cx.global_mut::<AiSettingsUiState>().0;
                        state.retain_confirm = true;
                        state.history_removed = None;
                    }
                    cx.notify();
                })),
            );
        if let Some(removed) = history_removed {
            history = history.child(studio::supporting(
                language.format(
                    TextKey::AiSettingsRetentionRemoved,
                    &[("count", display(&removed))],
                ),
                colors,
            ));
        }
        if let Some(code) = history_error {
            history = history.child(error_line(
                language.format(
                    TextKey::AiSettingsRetentionFailed,
                    &[("code", display(&code))],
                ),
                colors,
            ));
        }
        if retain_confirm {
            history = history
                .child(studio::section_title(
                    language.text(TextKey::AiSettingsRetentionTitle),
                    colors,
                ))
                .child(studio::supporting(
                    language.text(TextKey::AiSettingsRetentionBody),
                    colors,
                ))
                .child(
                    div()
                        .flex()
                        .gap(px(8.))
                        .child(
                            studio::text_button(
                                "ai-retain-history-cancel",
                                language.text(TextKey::AiSettingsCancel),
                                true,
                                false,
                                colors,
                            )
                            .on_click(cx.listener(|_, _, _, cx| {
                                if cx.has_global::<AiSettingsUiState>() {
                                    cx.global_mut::<AiSettingsUiState>().0.retain_confirm = false;
                                }
                                cx.notify();
                            })),
                        )
                        .child(
                            studio::text_button(
                                "ai-retain-history-confirm",
                                language.text(TextKey::AiSettingsRetentionConfirm),
                                runtime_available && !history_busy,
                                true,
                                colors,
                            )
                            .on_click(cx.listener(|this, _, _, cx| this.ai_retain_history(cx))),
                        ),
                );
        }
        let mut body = div()
            .flex()
            .flex_col()
            .gap(px(8.))
            .child(permissions)
            .child(report)
            .child(history);
        if !runtime_available {
            body = body.child(error_line(
                language.text(TextKey::AiSettingsRuntimeUnavailable),
                colors,
            ));
        }
        body.into_any_element()
    }

    // ---------------------------------------------------------------
    // Actions
    // ---------------------------------------------------------------

    fn ai_apply_preferences(
        &mut self,
        cx: &mut Context<Self>,
        edit: impl FnOnce(&mut AiPreferences),
    ) {
        let Some(runtime) = self.ai_runtime(cx) else {
            if cx.has_global::<AiSettingsUiState>() {
                cx.global_mut::<AiSettingsUiState>().0.settings_error =
                    Some("E_AI_UNAVAILABLE".to_owned());
            }
            cx.notify();
            return;
        };
        let error = match runtime.update_preferences(edit) {
            Ok(_) => None,
            Err(error) => Some(error.to_string()),
        };
        if cx.has_global::<AiSettingsUiState>() {
            cx.global_mut::<AiSettingsUiState>().0.settings_error = error;
        }
        cx.notify();
    }

    fn ai_select_agent(&mut self, agent_id: String, cx: &mut Context<Self>) {
        self.ai_apply_preferences(cx, move |preferences| {
            preferences.agent_id = agent_id;
        });
    }

    fn ai_change_credential_kind(
        &mut self,
        kind: ProviderKind,
        credential_kind: CredentialKind,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if !cx.has_global::<AiSettingsUiState>() {
            return;
        }
        let key_input = {
            let state = &mut cx.global_mut::<AiSettingsUiState>().0;
            let draft = state.draft_mut(kind);
            draft.credential_kind = credential_kind;
            draft.models.clear();
            draft.models_loaded = false;
            draft.models_loading = false;
            draft.models_error = None;
            draft.models_endpoints = None;
            draft.save_error = None;
            draft.api_key.clone()
        };
        key_input.update(cx, |input, cx| input.set_value("", window, cx));
        cx.notify();
    }

    fn ai_import_config(&mut self, kind: ProviderKind, cx: &mut Context<Self>) {
        let Some(runtime) = self.ai_runtime(cx) else {
            return;
        };
        if !cx.has_global::<AiSettingsUiState>() {
            return;
        }
        let generation = {
            let state = &mut cx.global_mut::<AiSettingsUiState>().0;
            let draft = state.draft_mut(kind);
            if draft.working {
                return;
            }
            draft.generation += 1;
            draft.working = true;
            draft.save_error = None;
            draft.import = None;
            draft.generation
        };
        cx.notify();
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn({
                    let runtime = runtime.clone();
                    async move { runtime.import_local_config(kind) }
                })
                .await;
            let _ = this.update_in(cx, |studio, window, cx| {
                studio.ai_finish_import(kind, generation, result, window, cx);
            });
        })
        .detach();
    }

    fn ai_finish_import(
        &mut self,
        kind: ProviderKind,
        generation: u64,
        result: Result<ImportedConnection, ImportFailure>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if !cx.has_global::<AiSettingsUiState>() {
            return;
        }
        let mut updates: Vec<(Entity<InputState>, String)> = Vec::new();
        let mut clear_key: Option<Entity<InputState>> = None;
        {
            let state = &mut cx.global_mut::<AiSettingsUiState>().0;
            let draft = state.draft_mut(kind);
            if draft.generation != generation {
                return;
            }
            draft.working = false;
            match result {
                Ok(imported) => {
                    draft.models.clear();
                    draft.models_loaded = false;
                    draft.models_loading = false;
                    draft.models_error = None;
                    draft.models_endpoints = None;
                    draft.credential_kind = imported.credential_kind;
                    draft.imported_secret = imported.has_secret;
                    draft.import = Some(ImportStatus::Imported {
                        has_secret: imported.has_secret,
                    });
                    if let Some(endpoint) = imported.endpoint {
                        updates.push((draft.endpoint.clone(), endpoint));
                    }
                    if let Some(model) = imported.model {
                        updates.push((draft.model.clone(), model));
                    }
                    // An imported endpoint never borrows another connection secret.
                    clear_key = Some(draft.api_key.clone());
                }
                Err(failure) => {
                    draft.import = Some(ImportStatus::Failure(failure));
                }
            }
        }
        if let Some(key_input) = clear_key {
            key_input.update(cx, |input, cx| input.set_value("", window, cx));
        }
        // Refresh only reads the pending imported secret through the runtime.
        for (input, value) in updates {
            input.update(cx, |input, cx| input.set_value(value, window, cx));
        }
        cx.notify();
    }

    fn ai_refresh_models(&mut self, kind: ProviderKind, refresh: bool, cx: &mut Context<Self>) {
        let Some(runtime) = self.ai_runtime(cx) else {
            return;
        };
        if !cx.has_global::<AiSettingsUiState>() {
            return;
        }
        let (generation, endpoint, models_endpoint) = {
            let state = cx.global::<AiSettingsUiState>();
            let draft = state.0.draft(kind);
            (
                draft.generation,
                draft.endpoint.read(cx).value().to_string(),
                draft.models_endpoint.read(cx).value().to_string(),
            )
        };
        let generation = {
            let state = &mut cx.global_mut::<AiSettingsUiState>().0;
            let draft = state.draft_mut(kind);
            if draft.working {
                return;
            }
            draft.generation = generation + 1;
            draft.working = true;
            draft.models_loading = true;
            draft.models_error = None;
            draft.generation
        };
        cx.notify();
        cx.spawn(async move |this, cx| {
            let result = runtime.load_models(kind, refresh).await;
            let _ = this.update(cx, |studio, cx| {
                studio.ai_finish_models(kind, generation, endpoint, models_endpoint, result, cx);
            });
        })
        .detach();
    }

    fn ai_finish_models(
        &mut self,
        kind: ProviderKind,
        generation: u64,
        endpoint: String,
        models_endpoint: String,
        result: Result<Vec<String>, String>,
        cx: &mut Context<Self>,
    ) {
        if !cx.has_global::<AiSettingsUiState>() {
            return;
        }
        {
            let state = &mut cx.global_mut::<AiSettingsUiState>().0;
            let draft = state.draft_mut(kind);
            if draft.generation != generation {
                return;
            }
            draft.working = false;
            draft.models_loading = false;
            draft.models_endpoints = Some((endpoint, models_endpoint));
            match result {
                Ok(models) => {
                    draft.models = models;
                    draft.models_loaded = true;
                    draft.models_error = None;
                }
                Err(code) => {
                    draft.models.clear();
                    draft.models_loaded = true;
                    draft.models_error = Some(code);
                }
            }
        }
        cx.notify();
    }

    fn ai_save_connection(
        &mut self,
        kind: ProviderKind,
        _window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let Some(runtime) = self.ai_runtime(cx) else {
            return;
        };
        if !cx.has_global::<AiSettingsUiState>() {
            return;
        }
        let (
            endpoint,
            models_endpoint,
            model,
            api_key,
            credential_kind,
            models,
            generation,
            configured,
            imported_secret,
        ) = {
            let state = cx.global::<AiSettingsUiState>();
            let draft = state.0.draft(kind);
            let view = state.0.draft_view(kind, cx);
            let saved = runtime.connection(kind);
            let configured =
                ai_credential_configured(&saved, view.credential_kind, &view.endpoint_value);
            let models = ai_models_selectable(&view).then(|| view.models.clone());
            (
                view.endpoint_value.trim().to_owned(),
                view.models_endpoint_value.trim().to_owned(),
                view.model_value.trim().to_owned(),
                view.key_value.trim().to_owned(),
                view.credential_kind,
                models,
                draft.generation,
                configured,
                view.imported_secret,
            )
        };
        if !ai_save_enabled(&endpoint, &model, configured, imported_secret, &api_key) {
            if cx.has_global::<AiSettingsUiState>() {
                cx.global_mut::<AiSettingsUiState>()
                    .0
                    .draft_mut(kind)
                    .save_error = Some("E_AI_CONNECTION_SAVE".to_owned());
            }
            cx.notify();
            return;
        }
        let generation = {
            let state = &mut cx.global_mut::<AiSettingsUiState>().0;
            let draft = state.draft_mut(kind);
            if draft.working {
                return;
            }
            draft.generation = generation + 1;
            draft.working = true;
            draft.save_error = None;
            draft.generation
        };
        cx.notify();
        let secret = (!api_key.is_empty()).then_some(api_key);
        let models_for_save = models;
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn({
                    let runtime = runtime.clone();
                    async move {
                        runtime.save_connection(
                            kind,
                            &endpoint,
                            &models_endpoint,
                            &model,
                            credential_kind,
                            secret.as_deref(),
                            models_for_save.as_deref(),
                        )
                    }
                })
                .await;
            let _ = this.update_in(cx, |studio, window, cx| {
                studio.ai_finish_save(kind, generation, result, window, cx);
            });
        })
        .detach();
    }

    fn ai_finish_save(
        &mut self,
        kind: ProviderKind,
        generation: u64,
        result: Result<(), RuntimeError>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if !cx.has_global::<AiSettingsUiState>() {
            return;
        }
        let key_input = {
            let state = &mut cx.global_mut::<AiSettingsUiState>().0;
            let draft = state.draft_mut(kind);
            if draft.generation != generation {
                return;
            }
            draft.working = false;
            match result {
                Ok(()) => {
                    draft.import = None;
                    draft.save_error = None;
                    Some(draft.api_key.clone())
                }
                Err(_) => {
                    draft.save_error = Some("E_AI_CONNECTION_SAVE".to_owned());
                    None
                }
            }
        };
        if let Some(input) = key_input {
            input.update(cx, |input, cx| input.set_value("", window, cx));
        }
        cx.notify();
    }

    fn ai_retain_history(&mut self, cx: &mut Context<Self>) {
        let Some(runtime) = self.ai_runtime(cx) else {
            return;
        };
        if !cx.has_global::<AiSettingsUiState>() {
            return;
        }
        {
            let state = &mut cx.global_mut::<AiSettingsUiState>().0;
            if state.history_busy {
                return;
            }
            state.retain_confirm = false;
            state.history_busy = true;
            state.history_error = None;
            state.history_removed = None;
        }
        cx.notify();
        cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn({
                    let runtime = runtime.clone();
                    async move { runtime.retain_history(100, 30) }
                })
                .await;
            let _ = this.update(cx, |_studio, cx| {
                if !cx.has_global::<AiSettingsUiState>() {
                    return;
                }
                let state = &mut cx.global_mut::<AiSettingsUiState>().0;
                state.history_busy = false;
                match result {
                    Ok(removed) => {
                        state.history_removed = Some(removed);
                        state.history_error = None;
                    }
                    Err(code) => {
                        state.history_error = Some(code);
                    }
                }
                cx.notify();
            });
        })
        .detach();
    }
}

// ---------------------------------------------------------------------------
// Small render helpers
// ---------------------------------------------------------------------------

fn ai_unavailable(language: Language, colors: &StudioColors) -> AnyElement {
    studio::card(colors)
        .child(error_line(
            language.text(TextKey::AiSettingsRuntimeUnavailable),
            colors,
        ))
        .into_any_element()
}

fn error_line(text: impl Into<SharedString>, colors: &StudioColors) -> AnyElement {
    div()
        .text_color(colors.error)
        .child(crate::studio::selectable(text))
        .into_any_element()
}

fn ai_field(
    label: impl Into<SharedString>,
    input: &Entity<InputState>,
    disabled: bool,
    colors: &StudioColors,
) -> Div {
    div()
        .flex()
        .flex_col()
        .gap(px(4.))
        .child(studio::section_title(label, colors))
        .child(
            div().w(px(FIELD_WIDTH)).flex_shrink_0().child(
                Input::new(input)
                    .h(px(26.))
                    .rounded(px(4.))
                    .bg(colors.field)
                    .border_1()
                    .border_color(colors.border)
                    .disabled(disabled),
            ),
        )
}

/// Compose subsection title rendered in the accent colour.
fn accent_title(text: impl Into<SharedString>, colors: &StudioColors) -> Div {
    div()
        .text_size(px(typography::BODY))
        .font_weight(FontWeight::SEMIBOLD)
        .text_color(colors.accent)
        .child(text.into())
}

/// Compose ManagementExtensions import card (Agent/Skill packages). The Rust
/// runtime has no extension registry, so the directory/import controls stay
/// disabled and the empty-registry state is shown.
fn ai_import_card(
    title: impl Into<SharedString>,
    id_prefix: &'static str,
    language: Language,
    colors: &StudioColors,
) -> Div {
    studio::card(colors)
        .child(accent_title(title, colors))
        .child(studio::section_title(
            language.text(TextKey::AiSettingsPackageDirectory),
            colors,
        ))
        .child(
            div()
                .w(px(FIELD_WIDTH))
                .h(px(28.))
                .px(px(8.))
                .flex()
                .items_center()
                .border_1()
                .border_color(colors.border)
                .rounded(px(6.))
                .text_color(colors.muted_text)
                .child(language.text(TextKey::AiSettingsDirectoryPlaceholder)),
        )
        .child(
            div()
                .flex()
                .items_center()
                .gap(px(8.))
                .child(studio::text_button(
                    (id_prefix, 0usize),
                    language.text(TextKey::AiSettingsBrowse),
                    false,
                    false,
                    colors,
                ))
                .child(studio::text_button(
                    (id_prefix, 1usize),
                    language.text(TextKey::AiSettingsImportVerified),
                    false,
                    true,
                    colors,
                )),
        )
        .child(studio::supporting(
            language.text(TextKey::AiSettingsPackageUnavailable),
            colors,
        ))
        .child(studio::supporting(
            language.text(TextKey::AiSettingsNoPackagesInstalled),
            colors,
        ))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn connection(has_credential: bool, endpoint: &str) -> ConnectionState {
        ConnectionState {
            kind: ProviderKind::Codex,
            endpoint: endpoint.to_owned(),
            models_endpoint: String::new(),
            model: "gpt".to_owned(),
            credential_kind: CredentialKind::ApiKey,
            revision: "revision".to_owned(),
            has_credential,
        }
    }

    #[::core::prelude::v1::test]
    fn save_requires_endpoint_model_and_credential() {
        assert!(ai_save_enabled(
            "https://api.example/v1",
            "gpt",
            true,
            false,
            ""
        ));
        assert!(ai_save_enabled(
            "https://api.example/v1",
            "gpt",
            false,
            false,
            "sk-key"
        ));
        // A secret imported from local configuration is held by the runtime until Save.
        assert!(ai_save_enabled(
            "https://api.example/v1",
            "gpt",
            false,
            true,
            ""
        ));
        assert!(!ai_save_enabled("", "gpt", true, false, ""));
        assert!(!ai_save_enabled(
            "https://api.example/v1",
            "  ",
            true,
            false,
            ""
        ));
        assert!(!ai_save_enabled(
            "https://api.example/v1",
            "gpt",
            false,
            false,
            ""
        ));
    }

    #[::core::prelude::v1::test]
    fn stored_credential_only_matches_its_kind_and_endpoint() {
        let stored = connection(true, "https://api.example/v1");
        assert!(ai_credential_configured(
            &stored,
            CredentialKind::ApiKey,
            "https://api.example/v1"
        ));
        assert!(!ai_credential_configured(
            &stored,
            CredentialKind::BearerToken,
            "https://api.example/v1"
        ));
        assert!(!ai_credential_configured(
            &stored,
            CredentialKind::ApiKey,
            "https://other.example/v1"
        ));
        assert!(!ai_credential_configured(
            &connection(false, "https://api.example/v1"),
            CredentialKind::ApiKey,
            "https://api.example/v1"
        ));
    }

    #[::core::prelude::v1::test]
    fn every_import_failure_maps_to_its_own_message() {
        let keys: std::collections::BTreeSet<_> = [
            ImportFailure::NotFound,
            ImportFailure::Malformed,
            ImportFailure::PermissionDenied,
            ImportFailure::UnsupportedAuth,
            ImportFailure::UnsupportedConfig,
        ]
        .into_iter()
        .map(|failure| import_failure_key(failure).as_str().to_owned())
        .collect();
        assert_eq!(keys.len(), 5);
    }

    #[::core::prelude::v1::test]
    fn report_labels_cover_every_variant() {
        for format in [ReportFormat::Html, ReportFormat::Markdown] {
            assert!(!report_format_label(format, Language::English).is_empty());
            assert!(!report_format_label(format, Language::SimplifiedChinese).is_empty());
        }
        for report_language in [
            ReportLanguage::Auto,
            ReportLanguage::English,
            ReportLanguage::SimplifiedChinese,
        ] {
            assert!(!report_language_label(report_language, Language::English).is_empty());
            assert!(
                !report_language_label(report_language, Language::SimplifiedChinese).is_empty()
            );
        }
    }

    #[::core::prelude::v1::test]
    fn builtin_agents_use_localized_descriptions() {
        for descriptor in BUILTIN_AGENTS {
            let english = ai_agent_description(descriptor, Language::English);
            let chinese = ai_agent_description(descriptor, Language::SimplifiedChinese);
            assert!(!english.is_empty());
            assert!(!chinese.is_empty());
            assert_ne!(english, chinese);
        }
    }

    /// The Compose category order is fixed and the credential field is masked and
    /// cleared after save.

    /// Compose Policy retains a bounded history window instead of clearing all;
    /// Compose Agent/Skill use the package-import management cards.
    #[::core::prelude::v1::test]
    fn policy_retention_and_package_cards_mirror_compose() {
        let source = include_str!("settings_ai.rs");
        for marker in [
            "AiSettingsRetainHistory",
            "AiSettingsRetentionConfirm",
            "AiSettingsImportAgent",
            "AiSettingsImportSkill",
            "AiSettingsPackageDirectory",
            "AiSettingsNoPackagesInstalled",
            "runtime.retain_history(100, 30)",
            "accent_title(",
        ] {
            assert!(source.contains(marker), "missing Compose section: {marker}");
        }
        assert!(!source.contains(concat!("AiSettingsOpen", "Analysis")));
        assert!(!source.contains(concat!("ai-settings-open", "-analysis")));
    }

    #[::core::prelude::v1::test]
    fn settings_page_keeps_fixed_order_and_masked_credential() {
        let source = include_str!("settings_ai.rs");
        let tabs = source
            .split("let tabs = [")
            .nth(1)
            .unwrap()
            .split("];")
            .next()
            .unwrap();
        let provider = tabs.find("AiSettingsProvider").unwrap();
        let agent = tabs.find("AiSettingsAgent").unwrap();
        let skill = tabs.find("AiSettingsSkill").unwrap();
        let policy = tabs.find("AiSettingsPolicy").unwrap();
        assert!(provider < agent && agent < skill && skill < policy);
        assert!(source.contains(".masked(true)"));
        assert!(source.contains(r#"set_value("", window, cx)"#));
        assert!(source.contains("ai_settings_tab"));
    }

    #[::core::prelude::v1::test]
    fn policy_wires_real_and_unavailable_permissions_honestly() {
        let source = include_str!("settings_ai.rs");
        assert!(source.contains("preferences.allow_artifacts = value"));
        assert!(source.contains("ai-policy-allow-scripts"));
        let scripts = source
            .split("ai-policy-allow-scripts")
            .nth(1)
            .unwrap()
            .split(';')
            .next()
            .unwrap();
        assert!(scripts.contains(".checked(false)"));
        assert!(scripts.contains(".disabled(true)"));
    }

    #[::core::prelude::v1::test]
    fn provider_drafts_are_retained_per_connection() {
        let source = include_str!("settings_ai.rs");
        assert!(source.contains("drafts: BTreeMap<ProviderKind, ProviderDraft>"));
        // Switching the visible connection only changes the selection; drafts are
        // built once and never rebuilt or cleared by a switch.
        assert!(source.contains("selected = kind"));
        assert!(!source.contains(concat!("drafts.", "clear()")));
    }

    #[::core::prelude::v1::test]
    fn agent_selection_routes_through_preferences() {
        let source = include_str!("settings_ai.rs");
        assert!(source.contains("preferences.agent_id = agent_id"));
        assert!(source.contains("AiSettingsAgentActive"));
        assert!(source.contains("ai-agent-active"));
        assert!(source.contains("BUILTIN_AGENTS"));
    }

    #[::core::prelude::v1::test]
    fn provider_actions_route_through_runtime_only() {
        let source = include_str!("settings_ai.rs");
        assert!(source.contains("runtime.save_connection("));
        assert!(source.contains("runtime.import_local_config("));
        assert!(source.contains("runtime.load_models(kind, refresh)"));
        assert!(source.contains("runtime.retain_history("));
        assert!(source.contains("runtime.update_preferences("));
        assert!(!source.contains(concat!("AiSecret", "Store")));
    }
}

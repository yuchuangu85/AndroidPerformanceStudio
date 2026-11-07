//! Explicit user-triggered AI analysis. Provider configuration and credentials
//! stay in this window; imported text never grants approval or executes tools.
use aps_ai::{
    AiError, AnalysisRequest, AnalysisResult, ApprovalScope, CredentialReference,
    OneShotCredential, OpenAiGateway, PreparedRun, ProviderCatalog, ProviderConfig, RunCoordinator,
    SecretCredential, SessionStore,
};
use aps_ui_foundation::{Language, TextKey, typography};
use gpui_kit::{
    component::{
        ActiveTheme, Disableable, Selectable,
        button::{Button, ButtonVariants},
        input::{Input, InputState},
    },
    prelude::*,
    *,
};
use std::{
    path::PathBuf,
    sync::{Arc, Mutex},
    time::Duration,
};

pub struct AiWorkspace {
    language: Language,
    request: Option<AnalysisRequest>,
    prepared: Option<PreparedRun>,
    result: Option<AnalysisResult>,
    coordinator: RunCoordinator,
    provider: Entity<InputState>,
    endpoint: Entity<InputState>,
    model: Entity<InputState>,
    credential_reference: Entity<InputState>,
    credential: Entity<InputState>,
    question: Entity<InputState>,
    network_consent: bool,
    source_consent: bool,
    streaming: bool,
    busy: bool,
    epoch: u64,
    activated: bool,
    consumed: bool,
    clear_credential_on_render: bool,
    audit_path: Option<PathBuf>,
    audit_lock: Arc<Mutex<()>>,
    error: Option<String>,
    catalog: ProviderCatalog,
    settings_input_subscriptions: Vec<Subscription>,
    settings_configuration_check: Option<(String, String, String, String, bool, bool)>,
    studio: Option<WeakEntity<crate::shell::Studio>>,
}

impl AiWorkspace {
    pub fn new(language: Language, window: &mut Window, cx: &mut Context<Self>) -> Self {
        let mut this = Self {
            language,
            request: None,
            prepared: None,
            result: None,
            coordinator: RunCoordinator::new(),
            provider: cx.new(|cx| {
                InputState::new(window, cx)
                    .placeholder(language.text(TextKey::AiProviderIdPlaceholder))
                    .default_value("local")
                    .validate(|value, _| value.len() <= 128)
            }),
            endpoint: cx.new(|cx| {
                InputState::new(window, cx)
                    .placeholder(language.text(TextKey::AiHttpsApiBaseUrlPlaceholder))
                    .default_value("http://127.0.0.1:12345/v1")
                    .validate(|value, _| value.len() <= 2048)
            }),
            model: cx.new(|cx| {
                InputState::new(window, cx)
                    .placeholder(language.text(TextKey::AiModelIdPlaceholder))
                    .default_value("local-model")
                    .validate(|value, _| value.len() <= 256)
            }),
            credential_reference: cx.new(|cx| {
                InputState::new(window, cx)
                    .placeholder(language.text(TextKey::AiCredentialReferencePlaceholder))
                    .default_value("memory:manual")
                    .validate(|value, _| value.len() <= 128)
            }),
            question: cx.new(|cx| {
                InputState::new(window, cx)
                    .placeholder(language.text(TextKey::AiOptionalQuestionPlaceholder))
                    .validate(|value, _| value.len() <= 4096)
            }),
            credential: cx.new(|cx| {
                InputState::new(window, cx)
                    .placeholder(language.text(TextKey::AiTemporaryApiCredentialPlaceholder))
                    .masked(true)
                    .validate(|value, _| value.len() <= 4096)
            }),
            network_consent: false,
            source_consent: false,
            streaming: false,
            busy: false,
            epoch: 0,
            activated: false,
            consumed: false,
            clear_credential_on_render: false,
            audit_path: None,
            audit_lock: Arc::new(Mutex::new(())),
            error: None,
            catalog: ProviderCatalog::default(),
            settings_input_subscriptions: Vec::new(),
            settings_configuration_check: None,
            studio: None,
        };
        for input in [
            &this.provider,
            &this.endpoint,
            &this.model,
            &this.credential_reference,
        ] {
            this.settings_input_subscriptions
                .push(cx.observe(input, |_, _, cx| cx.notify()));
        }
        this
    }
    /// A session-local editor, not provider persistence or request authorization.
    /// Prepared requests lock these fields so settings cannot change their identity.
    pub fn settings_provider_panel(&self, cx: &mut Context<Self>) -> AnyElement {
        use crate::studio::{self, StudioColors};
        use gpui_kit::component::menu::{DropdownMenu, PopupMenuItem};
        let language = self.language;
        let colors = StudioColors::from_theme(cx);
        let locked = self.busy || self.prepared.is_some();
        let provider = self.provider.read(cx).value().to_string();
        let endpoint = self.endpoint.read(cx).value().to_string();
        let model = self.model.read(cx).value().to_string();
        let status = self.settings_configuration_check.as_ref().filter(|check| {
            check.0 == provider
                && check.1 == endpoint
                && check.2 == model
                && check.3 == self.credential_reference.read(cx).value().as_str()
                && check.4 == self.streaming
        });
        let field = |label: TextKey, input: &Entity<InputState>| {
            div()
                .flex()
                .flex_col()
                .gap(px(4.))
                .child(studio::section_title(language.text(label), &colors))
                .child(Input::new(input).disabled(locked))
        };
        let models: Vec<String> = self
            .catalog
            .models(provider.trim())
            .iter()
            .map(|model| model.id.clone())
            .collect();
        let workspace = cx.entity().downgrade();
        let model_menu = Button::new("ai-settings-model-directory")
            .w(px(320.))
            .h(px(28.))
            .justify_start()
            .label(model.clone())
            .disabled(locked || models.is_empty())
            .dropdown_menu(move |menu, _, _| {
                models.iter().fold(menu.min_w(px(200.)), |menu, model| {
                    let model = model.clone();
                    let workspace = workspace.clone();
                    menu.item(
                        PopupMenuItem::new(model.clone()).on_click(move |_, window, cx| {
                            let _ = workspace.update(cx, |this, cx| {
                                if this.busy || this.prepared.is_some() {
                                    return;
                                }
                                this.model.update(cx, |input, cx| {
                                    input.set_value(model.clone(), window, cx)
                                });
                                this.settings_configuration_check = None;
                                cx.notify();
                            });
                        }),
                    )
                })
            });
        let mut card = studio::card(&colors)
            .child(studio::section_title(
                language.text(TextKey::AiSettingsRegisteredConnection),
                &colors,
            ))
            .child(
                Button::new("ai-settings-provider-selector")
                    .w(px(320.))
                    .h(px(28.))
                    .justify_start()
                    .label(language.text(TextKey::AiSettingsCustomConnection))
                    .dropdown_menu(move |menu, _, _| {
                        menu.min_w(px(200.))
                            .item(
                                PopupMenuItem::new(
                                    language.text(TextKey::AiSettingsCustomConnection),
                                )
                                .disabled(true),
                            )
                            .item(
                                PopupMenuItem::new(
                                    language.text(TextKey::AiSettingsCodexUnavailable),
                                )
                                .disabled(true),
                            )
                            .item(
                                PopupMenuItem::new(
                                    language.text(TextKey::AiSettingsClaudeUnavailable),
                                )
                                .disabled(true),
                            )
                    }),
            )
            .child(studio::supporting(
                language.text(TextKey::AiSettingsConnectionScope),
                &colors,
            ))
            .child(studio::text_button(
                "ai-settings-import-config",
                language.text(TextKey::AiSettingsImportConfig),
                false,
                false,
                &colors,
            ))
            .child(studio::supporting(
                language.text(TextKey::AiSettingsImportUnavailable),
                &colors,
            ))
            .child(field(TextKey::AiSettingsProviderId, &self.provider))
            .child(model_menu)
            .child(field(TextKey::AiSettingsModel, &self.model))
            .child(studio::text_button(
                "ai-settings-refresh-models",
                language.text(TextKey::AiSettingsRefreshModels),
                false,
                false,
                &colors,
            ))
            .child(field(TextKey::AiSettingsEndpoint, &self.endpoint))
            .child(studio::section_title(
                language.text(TextKey::AiSettingsModelsEndpoint),
                &colors,
            ))
            .child(
                Button::new("ai-settings-model-endpoint-unavailable")
                    .w(px(320.))
                    .h(px(28.))
                    .justify_start()
                    .label(language.text(TextKey::CommonUnavailable))
                    .disabled(true),
            )
            .child(field(
                TextKey::AiSettingsCredentialReference,
                &self.credential_reference,
            ))
            .child(studio::supporting(
                language.text(TextKey::AiSettingsCredentialStatus),
                &colors,
            ))
            .child(studio::supporting(
                language.text(TextKey::AiSettingsModelsUnavailable),
                &colors,
            ))
            .child(
                div()
                    .flex()
                    .flex_wrap()
                    .gap(px(8.))
                    .child(
                        studio::text_button(
                            "ai-settings-verify",
                            language.text(TextKey::AiSettingsVerify),
                            !locked,
                            false,
                            &colors,
                        )
                        .on_click(cx.listener(|this, _, _, cx| {
                            let provider = this.provider.read(cx).value().to_string();
                            let endpoint = this.endpoint.read(cx).value().to_string();
                            let model = this.model.read(cx).value().to_string();
                            let valid = CredentialReference::new(
                                this.credential_reference.read(cx).value().trim().to_owned(),
                            )
                            .and_then(|reference| {
                                ProviderConfig::new(
                                    provider.trim(),
                                    endpoint.trim(),
                                    model.trim(),
                                    reference,
                                )
                            })
                            .is_ok_and(|config| {
                                this.catalog.accepts_config(&config, this.streaming)
                            });
                            this.settings_configuration_check = Some((
                                provider,
                                endpoint,
                                model,
                                this.credential_reference.read(cx).value().to_string(),
                                this.streaming,
                                valid,
                            ));
                            cx.notify();
                        })),
                    )
                    .child(studio::text_button(
                        "ai-settings-save",
                        language.text(TextKey::AiSettingsSaveUnavailable),
                        false,
                        true,
                        &colors,
                    )),
            );
        if let Some(check) = status {
            card = card.child(studio::supporting(
                language.text(if check.5 {
                    TextKey::AiSettingsValid
                } else {
                    TextKey::AiSettingsInvalid
                }),
                &colors,
            ));
        }
        card.into_any_element()
    }

    pub fn set_provider_catalog(&mut self, catalog: ProviderCatalog, cx: &mut Context<Self>) {
        self.catalog = catalog;
        cx.notify();
    }

    /// Upward handle so validated result findings can open their persisted
    /// source candidates in Source Workspaces (Compose onOpenSourceCandidate).
    pub fn set_studio(&mut self, studio: WeakEntity<crate::shell::Studio>) {
        self.studio = Some(studio);
    }
    pub fn set_language(
        &mut self,
        language: Language,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        self.language = language;
        self.provider.update(cx, |state, cx| {
            state.set_placeholder(language.text(TextKey::AiProviderIdPlaceholder), window, cx)
        });
        self.endpoint.update(cx, |state, cx| {
            state.set_placeholder(
                language.text(TextKey::AiHttpsApiBaseUrlPlaceholder),
                window,
                cx,
            )
        });
        self.model.update(cx, |state, cx| {
            state.set_placeholder(language.text(TextKey::AiModelIdPlaceholder), window, cx)
        });
        self.credential_reference.update(cx, |state, cx| {
            state.set_placeholder(
                language.text(TextKey::AiCredentialReferencePlaceholder),
                window,
                cx,
            )
        });
        self.question.update(cx, |state, cx| {
            state.set_placeholder(
                language.text(TextKey::AiOptionalQuestionPlaceholder),
                window,
                cx,
            )
        });
        self.credential.update(cx, |state, cx| {
            state.set_placeholder(
                language.text(TextKey::AiTemporaryApiCredentialPlaceholder),
                window,
                cx,
            )
        });
        cx.notify();
    }
    /// Invalidates a frozen/in-flight run when its originating profiler changes.
    /// May be called from an entity observer without an available Window.
    pub fn invalidate_origin(&mut self, cx: &mut Context<Self>) {
        self.reset_run();
        self.request = None;
        self.clear_credential_on_render = true;
        cx.notify();
    }
    /// Optional independent Rust-owned audit path. No body, credential or answer
    /// text is persisted. The fixed basename/path policy is enforced by aps-ai.
    pub fn set_audit_path(&mut self, path: Option<PathBuf>, cx: &mut Context<Self>) {
        self.audit_path = path;
        cx.notify();
    }
    /// Trusted profiler adapter supplies actual bounded evidence; there is no
    /// editable evidence JSON box or synthetic/example fallback.
    pub fn set_request(
        &mut self,
        request: AnalysisRequest,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        self.reset_run();
        self.clear_credential(window, cx);
        self.question.update(cx, |input, cx| {
            input.set_value(
                request.user_question.clone().unwrap_or_default(),
                window,
                cx,
            )
        });
        self.request = Some(request);
        cx.notify();
    }
    /// Supports host-prepared requests without rebuilding or changing payload.
    pub fn present(&mut self, prepared: PreparedRun, window: &mut Window, cx: &mut Context<Self>) {
        self.reset_run();
        self.clear_credential(window, cx);
        self.request = None;
        match self
            .coordinator
            .activate(prepared.session_id(), prepared.generation())
        {
            Ok(()) => {
                self.activated = true;
                self.prepared = Some(prepared);
            }
            Err(error) => self.error = Some(error.to_string()),
        }
        cx.notify();
    }
    pub fn has_preflight(&self) -> bool {
        self.prepared.is_some()
    }
    fn reset_run(&mut self) {
        self.coordinator.cancel();
        self.epoch = self.epoch.wrapping_add(1);
        self.prepared = None;
        self.result = None;
        self.network_consent = false;
        self.source_consent = false;
        self.busy = false;
        self.activated = false;
        self.consumed = false;
        self.error = None;
    }
    fn clear_credential(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        // SDK set_value clears editing/undo history as well as the visible text.
        self.credential
            .update(cx, |input, cx| input.set_value("", window, cx));
        self.clear_credential_on_render = false;
    }
    pub fn close(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.reset_run();
        self.request = None;
        self.question
            .update(cx, |input, cx| input.set_value("", window, cx));
        self.clear_credential(window, cx);
        cx.notify();
    }
    fn freeze(&mut self, cx: &mut Context<Self>) {
        if self.busy || self.consumed {
            return;
        }
        let Some(mut request) = self.request.clone() else {
            return;
        };
        let question = self.question.read(cx).value().trim().to_owned();
        request.user_question = (!question.is_empty()).then_some(question);
        self.prepared = None;
        self.result = None;
        self.network_consent = false;
        self.source_consent = false;
        self.error = None;
        self.epoch = self.epoch.wrapping_add(1);
        let result = (|| {
            let reference = CredentialReference::new(
                self.credential_reference.read(cx).value().trim().to_owned(),
            )?;
            let provider = ProviderConfig::new(
                self.provider.read(cx).value().trim(),
                self.endpoint.read(cx).value().trim(),
                self.model.read(cx).value().trim(),
                reference,
            )?;
            if !self.catalog.accepts_config(&provider, self.streaming) {
                return Err(AiError::InvalidConfiguration);
            }
            let prepared = PreparedRun::new(request, provider, self.streaming)?;
            if !self.activated {
                self.coordinator
                    .activate(prepared.session_id(), prepared.generation())?;
                self.activated = true;
            }
            Ok::<_, AiError>(prepared)
        })();
        match result {
            Ok(prepared) => self.prepared = Some(prepared),
            Err(error) => self.error = Some(error.to_string()),
        }
        cx.notify();
    }
    fn send(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if self.busy || !self.network_consent {
            return;
        }
        let Some(prepared) = self.prepared.as_ref() else {
            return;
        };
        let source_ids: Vec<_> = prepared.source_candidate_ids().map(str::to_owned).collect();
        if !source_ids.is_empty() && !self.source_consent {
            return;
        }
        let mut entered = self.credential.read(cx).value().as_bytes().to_vec();
        self.clear_credential(window, cx);
        let secret = match SecretCredential::new(std::mem::take(&mut entered)) {
            Ok(secret) => secret,
            Err(error) => {
                self.error = Some(error.to_string());
                cx.notify();
                return;
            }
        };
        let Some(prepared) = self.prepared.take() else {
            return;
        };
        let resolver = Arc::new(OneShotCredential::new(
            prepared.provider().credential_reference().clone(),
            secret,
        ));
        let scope = if source_ids.is_empty() {
            ApprovalScope::network_only()
        } else {
            ApprovalScope::with_sources(source_ids)
        };
        let run = self
            .coordinator
            .approve_explicit(&prepared, scope, Duration::from_secs(120))
            .and_then(|approval| self.coordinator.authorize(prepared, approval));
        let run = match run {
            Ok(run) => run,
            Err(error) => {
                self.error = Some(error.to_string());
                cx.notify();
                return;
            }
        };
        self.busy = true;
        self.consumed = true;
        self.result = None;
        self.error = None;
        self.network_consent = false;
        self.source_consent = false;
        self.epoch = self.epoch.wrapping_add(1);
        let epoch = self.epoch;
        let gateway = OpenAiGateway::new(cx.http_client(), resolver);
        let audit_path = self.audit_path.clone();
        let audit_lock = self.audit_lock.clone();
        let task = cx.background_executor().spawn(async move {
            let identity = (
                run.session_id().to_owned(),
                run.run_id().to_owned(),
                run.generation(),
            );
            if let Some(path) = &audit_path {
                let _guard = audit_lock.lock().map_err(|_| AiError::Persistence)?;
                SessionStore::open(path)?.begin(&run)?;
            }
            let result = gateway.execute(run).await;
            if let Some(path) = &audit_path {
                let _guard = audit_lock.lock().map_err(|_| AiError::Persistence)?;
                let mut store = SessionStore::open(path)?;
                match &result {
                    Ok(result) => store.complete(result)?,
                    Err(error) => store.fail(&identity.0, &identity.1, identity.2, *error)?,
                }
            }
            result
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.epoch != epoch {
                    return;
                }
                this.busy = false;
                match result {
                    Ok(result) => match this.coordinator.accept_result(&result) {
                        Ok(()) => this.result = Some(result),
                        Err(error) => this.error = Some(error.to_string()),
                    },
                    Err(error) => this.error = Some(error.to_string()),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }
    fn cancel(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.coordinator.cancel();
        self.epoch = self.epoch.wrapping_add(1);
        self.prepared = None;
        self.busy = false;
        self.consumed = true;
        self.network_consent = false;
        self.source_consent = false;
        self.clear_credential(window, cx);
        self.error = Some(AiError::Cancelled.to_string());
        cx.notify();
    }
}

impl Drop for AiWorkspace {
    fn drop(&mut self) {
        self.coordinator.cancel();
    }
}

impl Render for AiWorkspace {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        if self.clear_credential_on_render {
            self.clear_credential(window, cx);
        }
        let lang = self.language;
        let busy = self.busy;
        let mut body = div()
            .flex()
            .flex_col()
            .gap_3()
            .p_4()
            .child(
                div()
                    .text_size(px(typography::TITLE))
                    .child(lang.text(TextKey::AiAiAnalysis)),
            )
            .child(lang.text(TextKey::AiConfigureAnExplicitProviderFreezeActualProfiler))
            .child(Input::new(&self.provider).disabled(busy))
            .child(Input::new(&self.endpoint).disabled(busy))
            .child(Input::new(&self.model).disabled(busy))
            .child(Input::new(&self.credential_reference).disabled(busy))
            .child(Input::new(&self.question).disabled(busy))
            .child(
                Button::new("ai-streaming")
                    .label(lang.text(TextKey::AiStreaming))
                    .selected(self.streaming)
                    .disabled(busy || self.prepared.is_some())
                    .on_click(cx.listener(|this, _, _, cx| {
                        this.streaming = !this.streaming;
                        cx.notify();
                    })),
            )
            .child(
                div()
                    .flex()
                    .gap_2()
                    .child(
                        Button::new("ai-freeze")
                            .primary()
                            .label(lang.text(TextKey::AiFreezeAndReviewRequest))
                            .disabled(busy || self.consumed || self.request.is_none())
                            .on_click(cx.listener(|this, _, _, cx| this.freeze(cx))),
                    )
                    .child(
                        Button::new("ai-cancel")
                            .label(lang.text(TextKey::CommonCancel))
                            .on_click(cx.listener(|this, _, window, cx| this.cancel(window, cx))),
                    )
                    .child(
                        Button::new("ai-clear")
                            .label(lang.text(TextKey::AiClearEvidenceAndResult))
                            .on_click(cx.listener(|this, _, window, cx| this.close(window, cx))),
                    ),
            )
            .children(
                self.error
                    .clone()
                    .map(|error| div().text_color(cx.theme().danger).child(error)),
            );
        if busy {
            body =
                body.child(lang.text(TextKey::AiWaitingForValidatedResponseCancelStopsPublication));
        }
        if self.request.is_none() && self.prepared.is_none() && self.result.is_none() {
            body = body.child(lang.text(TextKey::AiSelectActualProfilerEvidenceUsingItsAi));
        }
        if let Some(prepared) = &self.prepared {
            let provider = prepared.provider();
            let has_sources = !prepared.source_candidates().is_empty();
            body = body
                .child(lang.format(
                    TextKey::DetailsAiFrozenProvider,
                    &[
                        ("provider", &provider.id()),
                        ("endpoint", &provider.endpoint()),
                        ("model", &provider.model()),
                        ("reference", &provider.credential_reference().as_str()),
                    ],
                ))
                .child(lang.format(
                    TextKey::DetailsAiFrozenIdentity,
                    &[
                        ("session_id", &prepared.session_id()),
                        ("run_id", &prepared.run_id()),
                        ("generation", &prepared.generation()),
                        ("request_digest", &prepared.request_digest()),
                    ],
                ))
                .child(lang.format(
                    TextKey::DetailsAiEvidenceScope,
                    &[
                        ("profiler", &prepared.profiler()),
                        ("scope", &prepared.scope()),
                        ("evidence_count", &prepared.evidence_count()),
                        ("source_count", &prepared.source_candidates().len()),
                    ],
                ))
                .child(
                    div()
                        .id("ai-exact-payload")
                        .max_h(px(360.))
                        .overflow_y_scroll()
                        .p_3()
                        .bg(cx.theme().muted)
                        .font_family("monospace")
                        .text_size(px(typography::SUPPORTING))
                        .child(prepared.review_body().to_owned()),
                )
                .child(lang.text(TextKey::AiTheJsonAboveIsTheExactFrozen))
                .child(Input::new(&self.credential).disabled(busy))
                .child(lang.text(TextKey::AiCredentialIsMaskedAndClearedWhenSending))
                .child(
                    Button::new("ai-network-consent")
                        .label(lang.text(TextKey::AiIApproveSendingThisExactRequestTo))
                        .selected(self.network_consent)
                        .disabled(busy)
                        .on_click(cx.listener(|this, _, _, cx| {
                            this.network_consent = !this.network_consent;
                            cx.notify();
                        })),
                );
            if has_sources {
                body = body.child(
                    Button::new("ai-source-consent")
                        .label(lang.text(TextKey::AiIApproveUploadingEveryDisplayedSourceCandidate))
                        .selected(self.source_consent)
                        .disabled(busy)
                        .on_click(cx.listener(|this, _, _, cx| {
                            this.source_consent = !this.source_consent;
                            cx.notify();
                        })),
                );
            }
            body = body.child(
                Button::new("ai-send-once")
                    .primary()
                    .label(lang.text(TextKey::AiApproveAndSendOnce))
                    .disabled(busy || !self.network_consent || has_sources && !self.source_consent)
                    .on_click(cx.listener(|this, _, window, cx| this.send(window, cx))),
            );
        }
        if self.consumed && !busy {
            body = body.child(lang.text(TextKey::AiThisEvidenceRequestWasConsumedOrCancelled));
        }
        if let Some(result) = &self.result {
            body = body
                .child(lang.format(
                    TextKey::DetailsAiResultIdentity,
                    &[
                        ("model", &result.model),
                        ("session_id", &result.session_id),
                        ("run_id", &result.run_id),
                        ("generation", &result.generation),
                    ],
                ))
                .child(result.summary.clone())
                .children(result.findings.iter().map(|finding| {
                    div()
                        .flex()
                        .flex_col()
                        .gap_1()
                        .p_3()
                        .bg(cx.theme().muted)
                        .child(lang.format(
                            TextKey::DetailsAiFindingHeader,
                            &[
                                ("severity", &format!("{:?}", finding.severity)),
                                ("title", &finding.title),
                                ("confidence", &finding.analysis_confidence),
                            ],
                        ))
                        .child(finding.explanation.clone())
                        .child(finding.recommendation.clone())
                        .child(lang.format(
                            TextKey::DetailsAiFindingEvidence,
                            &[
                                ("evidence_ids", &finding.evidence_ids.join(", ")),
                                ("candidate_ids", &finding.candidate_ids.join(", ")),
                            ],
                        ))
                        .children(finding.candidate_ids.iter().enumerate().map(
                            |(index, candidate_id)| {
                                let candidate_id = candidate_id.clone();
                                Button::new(format!("ai-open-source-{}-{index}", finding.id))
                                    .label(lang.text(TextKey::SourceOpenCandidate))
                                    .disabled(busy)
                                    .on_click(cx.listener(move |this, _, window, cx| {
                                        if let Some(studio) = this.studio.clone() {
                                            studio
                                                .update(cx, |studio, cx| {
                                                    studio.open_source_candidate(
                                                        candidate_id.clone(),
                                                        window,
                                                        cx,
                                                    )
                                                })
                                                .ok();
                                        }
                                    }))
                            },
                        ))
                }));
        }
        body
    }
}

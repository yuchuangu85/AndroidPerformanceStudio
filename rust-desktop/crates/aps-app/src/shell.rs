mod home_page;
mod settings_ai;
mod settings_page;

use crate::{
    ai::AiWorkspace,
    capture::{CaptureCompleted, CaptureWorkspace},
    cpu::CpuWorkspace,
    frame::FrameWorkspace,
    import::ImportWorkspace,
    inspector::InspectorWorkspace,
    method::MethodWorkspace,
    source::SourceWorkspace,
    targets::{TargetSelectionChanged, TargetsWorkspace},
    trace::TraceWorkspace,
};
use aps_ai::{CredentialReference, ModelDescriptor, ProviderCatalog, ProviderConfig};
use aps_ui_foundation::{
    AccentColor, Destination, DisplayScale, Language, Settings, TextKey, ThemeMode, typography,
};
use gpui_kit::{
    component::InteractiveElementExt,
    component::{
        ActiveTheme, Disableable, Selectable,
        button::{Button, ButtonVariants},
        menu::{DropdownMenu, PopupMenuItem},
    },
    prelude::*,
    *,
};
use std::{collections::HashMap, path::PathBuf};

#[derive(Clone, Copy, PartialEq, Eq)]
enum SettingsPage {
    General,
    Configuration,
    LayoutInspector,
    Simpleperf,
    Ai,
    About,
}

struct TitlebarDragState {
    should_move: bool,
}

impl Render for TitlebarDragState {
    fn render(&mut self, _: &mut Window, _: &mut Context<Self>) -> impl IntoElement {
        div()
    }
}

impl SettingsPage {
    const ALL: [Self; 6] = [
        Self::General,
        Self::Configuration,
        Self::LayoutInspector,
        Self::Simpleperf,
        Self::Ai,
        Self::About,
    ];

    fn label(self, language: Language) -> &'static str {
        match self {
            Self::General => language.text(TextKey::CommonGeneral),
            Self::Configuration => language.text(TextKey::CommonConfiguration),
            Self::LayoutInspector => language.text(TextKey::FoundationLayoutInspector),
            Self::Simpleperf => language.text(TextKey::CommonSimpleperf),
            Self::Ai => language.text(TextKey::CommonAi),
            Self::About => language.text(TextKey::CommonAbout),
        }
    }
}

gpui_kit::actions!(
    studio,
    [
        Quit,
        OpenProfile,
        OpenHomeTrace,
        OpenHomeHprof,
        OpenBatteryReport,
        OpenFrameStats,
        ExportFrameCsv,
        ExportFrameJson,
        OpenSettings,
        FocusCpuSearch,
        PrepareCurrentAi,
        OpenInspectorArchive,
        NextInspectorWindow,
        ToggleInspectorHierarchy,
        ToggleInspectorFindings,
        ToggleInspectorDetails,
    ]
);

/// Request from the Frame Analyzer to correlate a frame with the Layout Inspector.
#[derive(Clone, Debug)]
pub(crate) struct FrameLayoutInspectionRequest {
    pub device_serial: Option<String>,
    pub package_name: String,
    pub activity_name: Option<String>,
    pub window_id: Option<String>,
    pub frame_id: u64,
}

pub struct Studio {
    settings: Settings,
    settings_path: Option<PathBuf>,
    destination: Destination,
    cpu: Entity<CpuWorkspace>,
    ai: Entity<AiWorkspace>,
    capture: Entity<CaptureWorkspace>,
    method: Entity<MethodWorkspace>,
    source: Entity<SourceWorkspace>,
    trace: Entity<TraceWorkspace>,
    frame: Entity<FrameWorkspace>,
    inspector: Entity<InspectorWorkspace>,
    imports: HashMap<Destination, Entity<ImportWorkspace>>,
    focus: FocusHandle,
    notice: Option<String>,
    save_generation: u64,
    settings_save: Option<Task<()>>,
    targets: Entity<TargetsWorkspace>,
    ai_origin: Option<Destination>,
    ai_generation: u64,
    captured: Option<aps_capture::CapturedArtifact>,
    settings_page: SettingsPage,
    settings_ui: settings_page::SettingsUi,
    home_recent: home_page::HomeRecentState,
    home_recent_generation: u64,
    home_recent_task: Option<Task<()>>,
    home_import_pending: bool,
    _subscriptions: Vec<Subscription>,
}

impl Studio {
    pub fn new(
        settings: Settings,
        settings_path: Option<PathBuf>,
        notice: Option<String>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Self {
        let metadata_path = settings_path
            .as_ref()
            .and_then(|path| path.parent())
            .map(|root| root.join(aps_storage::DATABASE_FILE_NAME));
        let snapshot_root = settings_path
            .as_ref()
            .and_then(|path| path.parent())
            .map(|root| root.join("cpu-artifacts"));
        let cpu = cx.new(|cx| {
            CpuWorkspace::new(
                settings.language.resolved(),
                metadata_path.clone(),
                snapshot_root,
                window,
                cx,
            )
        });
        // The CPU page owns the "Run AI Analysis" action from its report tab
        // row, so it needs an upward handle to the shell preflight.
        let studio_handle = cx.weak_entity();
        cpu.update(cx, |cpu, _| cpu.set_studio(studio_handle.clone()));
        let ai = cx.new(|cx| AiWorkspace::new(settings.language.resolved(), window, cx));
        ai.update(cx, |ai, cx| {
            ai.set_provider_catalog(default_ai_catalog(), cx);
        });
        // Validated AI findings can navigate to their persisted source candidates.
        ai.update(cx, |ai, _| ai.set_studio(studio_handle.clone()));
        let artifact_root = settings_path
            .as_ref()
            .and_then(|path| path.parent())
            .map(|root| root.join("captures"));
        let capture = cx.new(|cx| {
            CaptureWorkspace::new(
                settings.language.resolved(),
                artifact_root,
                settings.capture_duration_seconds,
                settings.capture_frequency_hz,
                window,
                cx,
            )
        });
        let method = cx.new(|_| MethodWorkspace::new(settings.language.resolved()));
        let source = cx.new(|cx| {
            SourceWorkspace::new(settings.language.resolved(), metadata_path, window, cx)
        });
        // The page owns its "Run AI analysis" and "AI Settings" entries and needs
        // the same upward shell handle as the CPU report.
        source.update(cx, |source, _| source.set_studio(studio_handle.clone()));
        let trace = cx.new(|cx| {
            TraceWorkspace::new(
                settings.language.resolved(),
                settings_path.as_deref(),
                window,
                cx,
            )
        });
        trace.update(cx, |trace, _| trace.set_studio(studio_handle.clone()));
        let frame = cx.new(|cx| {
            FrameWorkspace::new(
                settings.language.resolved(),
                settings_path.as_deref(),
                window,
                cx,
            )
        });
        frame.update(cx, |frame, _| frame.set_studio(studio_handle.clone()));
        let inspector =
            cx.new(|cx| InspectorWorkspace::new(settings.language.resolved(), window, cx));
        // The Inspector findings pane owns its "Run AI analysis" entry and needs the
        // same upward preflight handle as the CPU report.
        inspector.update(cx, |inspector, _| {
            inspector.set_studio(studio_handle.clone())
        });
        let imports: HashMap<Destination, Entity<ImportWorkspace>> = [
            Destination::Memory,
            Destination::Network,
            Destination::Startup,
            Destination::Battery,
            Destination::Benchmark,
            Destination::Gpu,
        ]
        .into_iter()
        .map(|kind| {
            (
                kind,
                cx.new(|_| ImportWorkspace::new(kind, settings.language.resolved())),
            )
        })
        .collect();
        let mut subscriptions = vec![
            cx.observe(&cpu, |this, _, cx| this.invalidate_ai(Destination::Cpu, cx)),
            cx.observe(&method, |this, _, cx| {
                this.invalidate_ai(Destination::MethodRecording, cx)
            }),
            cx.observe(&inspector, |this, _, cx| {
                this.invalidate_ai(Destination::LayoutInspector, cx)
            }),
            cx.observe(&source, |this, _, cx| {
                if this.ai_origin.is_some() {
                    this.ai.update(cx, |ai, cx| ai.invalidate_origin(cx));
                    this.ai_origin = None;
                }
            }),
            cx.observe(&trace, |this, _, cx| {
                this.invalidate_ai(Destination::Trace, cx)
            }),
            cx.observe(&frame, |this, _, cx| {
                this.invalidate_ai(Destination::Frame, cx)
            }),
            cx.subscribe(&capture, |this, _, completed: &CaptureCompleted, cx| {
                this.captured = completed.0.protobuf.clone();
                this.notice = Some(
                    this.settings
                        .language
                        .text(if this.captured.is_some() {
                            TextKey::ShellCaptureCompletedNotice
                        } else {
                            TextKey::ShellRawCaptureCompletedNotice
                        })
                        .into(),
                );
                cx.notify();
            }),
        ];
        for (kind, import) in &imports {
            let kind = *kind;
            subscriptions.push(cx.observe(import, move |this, _, cx| this.invalidate_ai(kind, cx)));
        }
        if let Some(gpu) = imports.get(&Destination::Gpu) {
            subscriptions.push(cx.subscribe(
                gpu,
                |this, _, event: &crate::import::OpenGpuTrace, cx| {
                    if let Some(path) = event.0.clone() {
                        this.trace.update(cx, |trace, cx| {
                            trace.open_trace_path(path, cx);
                        });
                    }
                    this.navigate(Destination::Trace, cx);
                },
            ));
        }
        let audit_path = settings_path
            .as_ref()
            .and_then(|path| path.parent())
            .map(|root| root.join(aps_ai::AUDIT_FILE_NAME));
        ai.update(cx, |ai, cx| ai.set_audit_path(audit_path, cx));
        // The title bar owns the shared ADB target for the whole window; pages
        // follow the published selection instead of rediscovering devices.
        let settings_ui = settings_page::SettingsUi::new(
            &settings,
            settings_path.as_deref(),
            window,
            cx,
            &mut subscriptions,
        );
        let targets = cx.new(|cx| TargetsWorkspace::new(settings.language.resolved(), window, cx));
        subscriptions.push(cx.subscribe(
            &targets,
            |this, _, event: &TargetSelectionChanged, cx| {
                let serial = event.serial.clone();
                let pid = event.pid;
                this.capture.update(cx, |capture, cx| {
                    capture.set_shared_target(serial.clone(), pid, cx)
                });
                this.inspector.update(cx, |inspector, cx| {
                    inspector.set_target(event.serial.clone(), event.pid, cx)
                });
                this.trace.update(cx, |trace, cx| {
                    trace.select_capture_device(serial.clone(), cx)
                });
                if let Some(serial) = event.serial.clone() {
                    this.frame.update(cx, |frame, cx| {
                        frame.select_device(serial, cx);
                        if let Some(pid) = pid {
                            frame.select_process(pid as i32, cx);
                        }
                    });
                }
                if let Some(gpu) = this.imports.get(&Destination::Gpu) {
                    gpu.update(cx, |gpu, cx| gpu.set_gpu_target(event.serial.clone(), cx));
                }
            },
        ));
        subscriptions.push(cx.observe(&ai, |_, _, cx| cx.notify()));
        subscriptions.push(cx.observe(&targets, |_, _, cx| cx.notify()));
        subscriptions.push(cx.subscribe_in(
            &inspector,
            window,
            |this, _, event: &crate::inspector::InspectorPreferencesChanged, window, cx| {
                this.settings.layout = event.0;
                this.persist_settings(window, cx);
            },
        ));
        inspector.update(cx, |inspector, cx| {
            inspector.apply_preferences(settings.layout, cx)
        });
        cpu.update(cx, |cpu, cx| {
            cpu.set_follow_tooltip(settings.simpleperf_follow_tooltip, cx)
        });
        window.set_rem_size(px(16. * settings.display_scale.multiplier()));
        let focus = cx.focus_handle();
        window.focus(&focus, cx);
        let mut studio = Self {
            settings,
            settings_path,
            destination: Destination::Home,
            cpu,
            ai,
            capture,
            method,
            source,
            trace,
            frame,
            inspector,
            imports,
            focus,
            notice,
            save_generation: 0,
            settings_save: None,
            targets,
            ai_origin: None,
            ai_generation: 0,
            captured: None,
            settings_page: SettingsPage::General,
            settings_ui,
            home_recent: home_page::HomeRecentState::Loading,
            home_recent_generation: 0,
            home_recent_task: None,
            home_import_pending: false,
            _subscriptions: subscriptions,
        };
        studio.refresh_home_recent(cx);
        studio
    }

    pub(crate) fn open_frame_layout(
        &mut self,
        request: FrameLayoutInspectionRequest,
        cx: &mut Context<Self>,
    ) {
        let _ = (
            request.activity_name.clone(),
            request.window_id.clone(),
            request.frame_id,
        );
        let _ = &request.package_name;
        self.inspector.update(cx, |inspector, cx| {
            inspector.set_target(request.device_serial.clone(), None, cx)
        });
        self.navigate(Destination::LayoutInspector, cx);
    }

    fn invalidate_ai(&mut self, origin: Destination, cx: &mut Context<Self>) {
        if self.ai_origin == Some(origin) {
            self.ai.update(cx, |ai, cx| ai.invalidate_origin(cx));
            self.ai_origin = None;
            cx.notify();
        }
    }
    pub(crate) fn open_source_symbol(
        &mut self,
        symbol: String,
        resource: String,
        _window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        self.destination = Destination::SourceWorkspaces;
        self.source.update(cx, |source, cx| {
            source.navigate_to_symbol(symbol, resource, cx)
        });
        cx.notify();
    }

    pub(crate) fn prepare_ai(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let origin = self.destination;
        let result = match origin {
            Destination::Cpu => self.cpu.read(cx).analysis_request(),
            Destination::MethodRecording => self.method.read(cx).analysis_request(),
            Destination::LayoutInspector => self.inspector.read(cx).analysis_request(),
            Destination::SourceWorkspaces => self.source.read(cx).analysis_request(),
            Destination::Trace => self.trace.read(cx).analysis_request(),
            Destination::Frame => self.frame.read(cx).analysis_request(),
            kind => self
                .imports
                .get(&kind)
                .ok_or_else(|| {
                    self.settings
                        .language
                        .text(TextKey::ShellSelectProfilerFirst)
                        .to_owned()
                })
                .and_then(|workspace| workspace.read(cx).analysis_request()),
        };
        self.present_ai_request(origin, result, window, cx);
    }

    /// Applies an already-computed analysis request. Page-owned AI buttons call
    /// this directly: while a workspace entity is being updated, re-reading it
    /// through `prepare_ai` would borrow the same entity twice.
    pub(crate) fn present_ai_request(
        &mut self,
        origin: Destination,
        result: Result<aps_ai::AnalysisRequest, String>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        match result {
            Ok(mut request) => {
                let Some(generation) = self.ai_generation.checked_add(1) else {
                    self.notice = Some(
                        self.settings
                            .language
                            .text(TextKey::ShellAiGenerationExhausted)
                            .into(),
                    );
                    cx.notify();
                    return;
                };
                self.ai_generation = generation;
                request.generation = generation;
                request.run_id = format!("ai-run-{generation}");
                self.ai
                    .update(cx, |ai, cx| ai.set_request(request, window, cx));
                self.ai_origin = Some(origin);
                self.destination = Destination::Ai;
                self.notice = None;
            }
            Err(error) => self.notice = Some(error),
        }
        cx.notify();
    }
    fn open_captured(&mut self, cx: &mut Context<Self>) {
        if let Some(artifact) = &self.captured {
            self.cpu.update(cx, |cpu, cx| {
                cpu.open_artifact(artifact.path.clone(), artifact.digest, cx)
            });
            self.destination = Destination::Cpu;
            cx.notify();
        }
    }
    fn open_cpu(&mut self, _: &OpenProfile, _: &mut Window, cx: &mut Context<Self>) {
        self.destination = Destination::Cpu;
        self.cpu.update(cx, |cpu, cx| cpu.pick_file(cx));
        cx.notify();
    }

    fn open_inspector_archive(
        &mut self,
        _: &OpenInspectorArchive,
        _: &mut Window,
        cx: &mut Context<Self>,
    ) {
        self.destination = Destination::LayoutInspector;
        self.inspector
            .update(cx, |inspector, cx| inspector.pick(cx));
        cx.notify();
    }

    fn open_settings(&mut self, _: &OpenSettings, _: &mut Window, cx: &mut Context<Self>) {
        self.destination = Destination::Settings;
        cx.notify();
    }

    /// Opens the AI settings page from a profiler page action button.
    pub(crate) fn open_ai_settings(&mut self, _: &mut Window, cx: &mut Context<Self>) {
        self.settings_page = SettingsPage::Ai;
        self.destination = Destination::Settings;
        cx.notify();
    }

    /// Opens a persisted source candidate from a validated AI finding.
    pub(crate) fn open_source_candidate(
        &mut self,
        candidate_id: String,
        _: &mut Window,
        cx: &mut Context<Self>,
    ) {
        self.destination = Destination::SourceWorkspaces;
        self.source.update(cx, |source, cx| {
            source.open_candidate_hex(&candidate_id, cx)
        });
        cx.notify();
    }

    fn persist_settings(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        crate::apply_theme(&self.settings, cx);
        window.set_rem_size(px(16. * self.settings.display_scale.multiplier()));
        self.inspector.update(cx, |inspector, cx| {
            inspector.apply_preferences(self.settings.layout, cx)
        });
        self.cpu.update(cx, |cpu, cx| {
            cpu.set_follow_tooltip(self.settings.simpleperf_follow_tooltip, cx)
        });
        self.capture.update(cx, |capture, cx| {
            capture.set_defaults(
                self.settings.capture_duration_seconds,
                self.settings.capture_frequency_hz,
                window,
                cx,
            )
        });
        self.cpu.update(cx, |cpu, cx| {
            cpu.set_language(self.settings.language.resolved(), window, cx)
        });
        self.ai.update(cx, |ai, cx| {
            ai.set_language(self.settings.language.resolved(), window, cx)
        });
        self.capture.update(cx, |capture, cx| {
            capture.set_language(self.settings.language.resolved(), window, cx)
        });
        self.targets.update(cx, |targets, cx| {
            targets.set_language(self.settings.language.resolved(), cx)
        });
        self.method.update(cx, |method, cx| {
            method.set_language(self.settings.language.resolved(), cx)
        });
        self.source.update(cx, |source, cx| {
            source.set_language(self.settings.language.resolved(), window, cx)
        });
        self.trace.update(cx, |trace, cx| {
            trace.set_language(self.settings.language.resolved(), window, cx)
        });
        self.frame.update(cx, |frame, cx| {
            frame.set_language(self.settings.language.resolved(), window, cx)
        });
        self.inspector.update(cx, |inspector, cx| {
            inspector.set_language(self.settings.language.resolved(), window, cx)
        });
        for import in self.imports.values() {
            import.update(cx, |import, cx| {
                import.set_language(self.settings.language.resolved(), cx)
            });
        }
        let Some(path) = self.settings_path.clone() else {
            self.notice = Some(
                self.settings
                    .language
                    .text(TextKey::ShellSettingsDirectoryUnavailable)
                    .into(),
            );
            return;
        };
        let settings = self.settings;
        self.save_generation += 1;
        let generation = self.save_generation;
        // Serialize persistence: a slower old write can never overwrite a newer setting.
        let previous = self.settings_save.take();
        self.settings_save = Some(cx.spawn(async move |this, cx| {
            if let Some(previous) = previous {
                previous.await;
            }
            let result = cx
                .background_executor()
                .spawn(async move { settings.save(&path).map_err(|error| error.to_string()) })
                .await;
            this.update(cx, |this, cx| {
                if generation == this.save_generation {
                    this.notice = result.err();
                    cx.notify();
                }
            })
            .ok();
        }));
        cx.notify();
    }

    /// Home activation refreshes only the Rust-owned history projection. Device
    /// refresh remains owned by the shared title bar and cannot reload history.
    fn navigate(&mut self, destination: Destination, cx: &mut Context<Self>) {
        let entering_home = destination == Destination::Home && self.destination != destination;
        let route = destination;
        self.destination = destination;
        if self.entered_route(route, cx) {
            cx.notify();
            return;
        }
        if entering_home {
            self.refresh_home_recent(cx);
        }
        cx.notify();
    }

    fn entered_route(&mut self, destination: Destination, cx: &mut Context<Self>) -> bool {
        match destination {
            Destination::Trace => {
                self.trace.update(cx, |trace, cx| trace.refresh_devices(cx));
                true
            }
            Destination::Frame => {
                self.frame.update(cx, |frame, cx| frame.refresh_devices(cx));
                true
            }
            _ => false,
        }
    }

    fn refresh_home_recent(&mut self, cx: &mut Context<Self>) {
        self.home_recent_generation += 1;
        let generation = self.home_recent_generation;
        self.home_recent = home_page::HomeRecentState::Loading;
        let root = self
            .settings_path
            .as_ref()
            .and_then(|path| path.parent())
            .map(PathBuf::from);
        self.home_recent_task = Some(cx.spawn(async move |this, cx| {
            let result = cx
                .background_executor()
                .spawn(async move {
                    let root = root.ok_or_else(|| {
                        "Rust application data location is unavailable".to_owned()
                    })?;
                    crate::home_recent::load_recent(
                        &root.join(aps_storage::DATABASE_FILE_NAME),
                        &root.join("cpu-artifacts"),
                        crate::home_recent::MAX_HOME_RECENT_ITEMS,
                    )
                })
                .await;
            this.update(cx, |this, cx| {
                if generation != this.home_recent_generation {
                    return;
                }
                this.home_recent = match result {
                    Ok(items) => home_page::HomeRecentState::Available(items),
                    Err(error) => home_page::HomeRecentState::Unavailable(error),
                };
                cx.notify();
            })
            .ok();
        }));
        cx.notify();
    }

    fn open_home_import(&mut self, destination: Destination, cx: &mut Context<Self>) {
        if self.home_import_pending {
            return;
        }
        if destination == Destination::Trace {
            self.navigate(Destination::Trace, cx);
            self.trace.update(cx, |trace, cx| trace.pick_trace(cx));
            return;
        }
        if destination == Destination::Frame {
            self.navigate(Destination::Frame, cx);
            self.frame
                .update(cx, |frame, cx| frame.pick_import_frame_stats(cx));
            return;
        }
        if !matches!(destination, Destination::Memory | Destination::Battery) {
            return;
        }
        let Some(workspace) = self.imports.get(&destination).cloned() else {
            return;
        };
        if !workspace.read(cx).can_open_home_artifact() {
            return;
        }
        self.home_import_pending = true;
        let picker = cx.prompt_for_paths(PathPromptOptions {
            files: true,
            directories: false,
            multiple: false,
            prompt: Some(
                self.settings
                    .language
                    .text(match destination {
                        Destination::Memory => TextKey::HomeAnalyzeHprof,
                        Destination::Battery => TextKey::BatteryImportAnalysis,
                        _ => TextKey::HomeImportTrace,
                    })
                    .into(),
            ),
        });
        // A cancelled picker must leave Home (and any later user navigation)
        // unchanged. Only an accepted ordinary file activates the target page.
        cx.spawn(async move |this, cx| {
            let result = picker.await;
            this.update(cx, |this, cx| {
                this.home_import_pending = false;
                match result {
                    Ok(Ok(Some(paths))) if paths.len() == 1 => {
                        if workspace.update(cx, |workspace, cx| {
                            workspace.open_home_artifact(paths[0].clone(), cx)
                        }) {
                            this.navigate(destination, cx);
                        }
                    }
                    Ok(Err(_)) => {
                        this.notice = Some(
                            this.settings
                                .language
                                .text(TextKey::ImportUiFilePickerFailed)
                                .into(),
                        );
                    }
                    _ => {}
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn open_home_recent(
        &mut self,
        item: crate::home_recent::HomeRecentItem,
        cx: &mut Context<Self>,
    ) {
        self.navigate(Destination::Cpu, cx);
        self.cpu
            .update(cx, |cpu, cx| cpu.open_home_recent(item.cpu, cx));
    }
}

fn default_ai_catalog() -> ProviderCatalog {
    let mut catalog = ProviderCatalog::default();
    let Ok(reference) = CredentialReference::new("memory:manual") else {
        return catalog;
    };
    let Ok(provider) = ProviderConfig::new(
        "local",
        "http://127.0.0.1:12345/v1",
        "local-model",
        reference,
    ) else {
        return catalog;
    };
    let _ = catalog.register(
        provider,
        vec![ModelDescriptor {
            id: "local-model".into(),
            supports_streaming: true,
            supports_json_schema: true,
        }],
    );
    catalog
}

fn navigation_icon_data(destination: Destination) -> &'static [u8] {
    match destination {
        Destination::Home => include_bytes!("../assets/navigation/ic_nav_home.svg"),
        Destination::LayoutInspector => {
            include_bytes!("../assets/navigation/ic_nav_layout_inspector.svg")
        }
        Destination::Cpu => include_bytes!("../assets/navigation/ic_nav_cpu_profiler.svg"),
        Destination::Trace => include_bytes!("../assets/navigation/ic_nav_trace_analyzer.svg"),
        Destination::Memory => include_bytes!("../assets/navigation/ic_nav_memory_profiler.svg"),
        Destination::Frame => include_bytes!("../assets/navigation/ic_nav_frame_profiler.svg"),
        Destination::Startup => {
            include_bytes!("../assets/navigation/ic_nav_startup_profiler.svg")
        }
        Destination::Battery => include_bytes!("../assets/navigation/ic_nav_battery.svg"),
        Destination::Network => include_bytes!("../assets/navigation/ic_nav_network.svg"),
        Destination::Gpu => include_bytes!("../assets/navigation/ic_nav_gpu_inspector.svg"),
        Destination::Benchmark => include_bytes!("../assets/navigation/ic_nav_benchmark.svg"),
        Destination::SourceWorkspaces => {
            include_bytes!("../assets/navigation/ic_nav_source_workspaces.svg")
        }
        Destination::Ai => include_bytes!("../assets/navigation/ic_settings_ai.svg"),
        Destination::Settings => include_bytes!("../assets/navigation/ic_settings.svg"),
        Destination::Capture | Destination::MethodRecording => {
            include_bytes!("../assets/navigation/ic_nav_method_recording.svg")
        }
    }
}

fn navigation_entry_content(
    destination: Destination,
    language: Language,
    collapsed: bool,
    color: Rgba,
) -> AnyElement {
    let icon = svg()
        .data(navigation_icon_data(destination))
        .size(px(18.))
        .text_color(color);
    if collapsed {
        div()
            .flex()
            .items_center()
            .justify_center()
            .size_full()
            .child(icon)
            .into_any_element()
    } else {
        div()
            .flex()
            .items_center()
            .gap(px(10.))
            .w_full()
            .child(icon)
            .child(
                div()
                    .flex_1()
                    .text_left()
                    .child(destination.label(language)),
            )
            .into_any_element()
    }
}

impl Focusable for Studio {
    fn focus_handle(&self, _: &App) -> FocusHandle {
        self.focus.clone()
    }
}

impl Render for Studio {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let language = self.settings.language;
        let selected = self.destination;
        let dark = cx.theme().is_dark();
        // Every background in the shell is the title bar surface, so the whole
        // window reads as one sheet; borders, accents and the selected-row tint
        // carry the structure instead.
        let background = crate::studio::titlebar_background(dark);
        let border = crate::studio::divider_color(dark);
        let selected_row = if dark { rgb(0x163d66) } else { rgb(0xd6eaff) };
        let primary_text = if dark { rgb(0xf5f5f7) } else { rgb(0x1d1d1f) };
        let secondary_text = if dark { rgb(0xaeaeb2) } else { rgb(0x6e6e73) };
        let accent = Rgba::from(cx.theme().accent);
        let collapsed = self.settings.navigation_rail_collapsed;
        let primary_navigation = [
            Destination::Home,
            Destination::LayoutInspector,
            Destination::Cpu,
            Destination::Trace,
            Destination::Memory,
            Destination::Frame,
            Destination::Startup,
            Destination::Battery,
            Destination::Network,
            Destination::Gpu,
            Destination::Benchmark,
            Destination::SourceWorkspaces,
        ];
        let body = match selected {
            Destination::Home => self.home(window, cx),
            Destination::Cpu => self.cpu.clone().into_any_element(),
            Destination::Ai => self.ai.clone().into_any_element(),
            Destination::Capture => self.capture.clone().into_any_element(),
            Destination::MethodRecording => self.method.clone().into_any_element(),
            Destination::SourceWorkspaces => self.source.clone().into_any_element(),
            Destination::Trace => self.trace.clone().into_any_element(),
            Destination::Frame => self.frame.clone().into_any_element(),
            Destination::LayoutInspector => self.inspector.clone().into_any_element(),
            Destination::Settings => self.settings_page(cx),
            Destination::Memory
            | Destination::Network
            | Destination::Startup
            | Destination::Battery
            | Destination::Benchmark
            | Destination::Gpu => self
                .imports
                .get(&selected)
                .expect("registered workspace")
                .clone()
                .into_any_element(),
        };
        let titlebar_state = window.use_state(cx, |_, _| TitlebarDragState { should_move: false });
        let titlebar = div()
            .id("studio-titlebar")
            .h(px(36.))
            .w_full()
            .flex()
            .items_center()
            .gap(px(8.))
            .pl(px(78.))
            .pr_4()
            .bg(background)
            .border_b_1()
            .border_color(border)
            .on_mouse_down_out(window.listener_for(&titlebar_state, |state, _, _, _| {
                state.should_move = false;
            }))
            .on_mouse_down(
                MouseButton::Left,
                window.listener_for(&titlebar_state, |state, _, _, _| {
                    state.should_move = true;
                }),
            )
            .on_mouse_up(
                MouseButton::Left,
                window.listener_for(&titlebar_state, |state, _, _, _| {
                    state.should_move = false;
                }),
            )
            .on_mouse_move(window.listener_for(&titlebar_state, |state, _, window, _| {
                if state.should_move {
                    state.should_move = false;
                    window.start_window_move();
                }
            }))
            .on_double_click(|_, window, _| window.titlebar_double_click())
            .child(div().w(px(3.)).h(px(16.)).bg(accent).rounded_sm())
            .child(
                div()
                    .text_color(primary_text)
                    .text_size(px(typography::BODY))
                    .child(language.text(TextKey::FoundationAndroidPerformanceStudio)),
            )
            .child(div().flex_1())
            .child(self.targets.clone());
        div()
            .id("studio-root")
            .key_context("Studio")
            .track_focus(&self.focus)
            .on_action(cx.listener(Self::open_cpu))
            .on_action(cx.listener(|this, _: &OpenBatteryReport, _, cx| {
                this.open_home_import(Destination::Battery, cx);
            }))
            .on_action(cx.listener(|this, _: &OpenFrameStats, _, cx| {
                this.navigate(Destination::Frame, cx);
                this.frame
                    .update(cx, |frame, cx| frame.pick_import_frame_stats(cx));
            }))
            .on_action(cx.listener(|this, _: &ExportFrameCsv, _, cx| {
                this.navigate(Destination::Frame, cx);
                this.frame.update(cx, |frame, cx| frame.export_csv(cx));
            }))
            .on_action(cx.listener(|this, _: &ExportFrameJson, _, cx| {
                this.navigate(Destination::Frame, cx);
                this.frame.update(cx, |frame, cx| frame.export_json(cx));
            }))
            .on_action(cx.listener(Self::open_settings))
            .when(selected == Destination::Home, |this| {
                this.on_action(cx.listener(|this, _: &OpenHomeTrace, _, cx| {
                    this.open_home_import(Destination::Trace, cx);
                }))
                .on_action(cx.listener(|this, _: &OpenHomeHprof, _, cx| {
                    this.open_home_import(Destination::Memory, cx);
                }))
            })
            .on_action(cx.listener(Self::open_inspector_archive))
            .on_action(cx.listener(|this, _: &NextInspectorWindow, _, cx| {
                this.destination = Destination::LayoutInspector;
                this.inspector
                    .update(cx, |inspector, cx| inspector.cycle_window(cx));
                cx.notify();
            }))
            .on_action(cx.listener(|this, _: &ToggleInspectorHierarchy, _, cx| {
                this.destination = Destination::LayoutInspector;
                this.inspector
                    .update(cx, |inspector, cx| inspector.toggle_hierarchy(cx));
                cx.notify();
            }))
            .on_action(cx.listener(|this, _: &ToggleInspectorFindings, _, cx| {
                this.destination = Destination::LayoutInspector;
                this.inspector
                    .update(cx, |inspector, cx| inspector.toggle_findings(cx));
                cx.notify();
            }))
            .on_action(cx.listener(|this, _: &ToggleInspectorDetails, _, cx| {
                this.destination = Destination::LayoutInspector;
                this.inspector
                    .update(cx, |inspector, cx| inspector.toggle_details(cx));
                cx.notify();
            }))
            .on_action(cx.listener(|this, _: &FocusCpuSearch, window, cx| {
                if this.destination == Destination::Cpu {
                    this.cpu.update(cx, |cpu, cx| cpu.focus_search(window, cx));
                }
            }))
            .on_action(cx.listener(|this, _: &PrepareCurrentAi, window, cx| {
                this.prepare_ai(window, cx);
            }))
            .size_full()
            .flex()
            .bg(background)
            .text_color(primary_text)
            .text_size(px(typography::BODY))
            .flex_col()
            .child(titlebar)
            .child(
                div()
                    .flex_1()
                    .min_h_0()
                    .flex()
                    .child(
                        div()
                            .id("navigation")
                            .overflow_y_scroll()
                            .w(px(if collapsed { 54. } else { 216. }))
                            .flex_shrink_0()
                            .h_full()
                            .flex()
                            .flex_col()
                            .gap(px(2.))
                            .p_2()
                            .bg(background)
                            .border_r_1()
                            .border_color(border)
                            .children(primary_navigation.into_iter().map(|destination| {
                                let is_selected = destination == selected;
                                div()
                                    .h(px(36.))
                                    .w_full()
                                    .rounded_md()
                                    .bg(if is_selected {
                                        selected_row
                                    } else {
                                        rgba(0x00000000)
                                    })
                                    .child(
                                        Button::new(("destination", destination as usize))
                                            .ghost()
                                            .h(px(36.))
                                            .w_full()
                                            .text_color(if is_selected {
                                                accent
                                            } else {
                                                secondary_text
                                            })
                                            .child(navigation_entry_content(
                                                destination,
                                                language,
                                                collapsed,
                                                if is_selected { accent } else { secondary_text },
                                            ))
                                            .on_click(cx.listener(move |this, _, _, cx| {
                                                this.navigate(destination, cx);
                                            })),
                                    )
                            }))
                            .child(div().flex_1())
                            .child(
                                div().h(px(36.)).w_full().rounded_md().child(
                                    Button::new("navigation-ai")
                                        .ghost()
                                        .h(px(36.))
                                        .w_full()
                                        .text_color(secondary_text)
                                        .child(navigation_entry_content(
                                            Destination::Ai,
                                            language,
                                            collapsed,
                                            secondary_text,
                                        ))
                                        .on_click(cx.listener(|this, _, _, cx| {
                                            this.destination = Destination::Ai;
                                            cx.notify();
                                        })),
                                ),
                            )
                            .child(div().h(px(1.)).my_2().bg(border))
                            .child(
                                div()
                                    .h(px(36.))
                                    .w_full()
                                    .rounded_md()
                                    .bg(if selected == Destination::Settings {
                                        selected_row
                                    } else {
                                        rgba(0x00000000)
                                    })
                                    .child(
                                        Button::new("navigation-settings")
                                            .ghost()
                                            .h(px(36.))
                                            .w_full()
                                            .text_color(if selected == Destination::Settings {
                                                accent
                                            } else {
                                                secondary_text
                                            })
                                            .child(navigation_entry_content(
                                                Destination::Settings,
                                                language,
                                                collapsed,
                                                if selected == Destination::Settings {
                                                    accent
                                                } else {
                                                    secondary_text
                                                },
                                            ))
                                            .on_click(cx.listener(|this, _, _, cx| {
                                                this.destination = Destination::Settings;
                                                cx.notify();
                                            })),
                                    ),
                            ),
                    )
                    .child(
                        div()
                            .bg(background)
                            .flex_1()
                            .min_w_0()
                            .h_full()
                            .flex()
                            .flex_col()
                            // CPU, Inspector, Source Workspaces and Settings render their
                            // workspaces directly below the title bar, so they have no
                            // page-title row; other pages keep the page-level chrome.
                            .children(
                                (selected != Destination::Home
                                    && selected != Destination::Cpu
                                    && selected != Destination::LayoutInspector
                                    && selected != Destination::Network
                                    && selected != Destination::Startup
                                    && selected != Destination::Gpu
                                    && selected != Destination::Battery
                                    && selected != Destination::Trace
                                    && selected != Destination::Frame
                                    && selected != Destination::SourceWorkspaces
                                    && selected != Destination::Settings)
                                    .then(|| {
                                        div()
                                            .h(px(56.))
                                            .flex()
                                            .items_center()
                                            .px_4()
                                            .border_b_1()
                                            .border_color(border)
                                            .bg(background)
                                            .gap_3()
                                            .child(
                                                div()
                                                    .text_color(primary_text)
                                                    .child(selected.label(language)),
                                            )
                                            .child(
                                                Button::new("prepare-current-ai")
                                                    .label(
                                                        language
                                                            .text(TextKey::ShellPrepareAiAnalysis),
                                                    )
                                                    .disabled(matches!(
                                                        selected,
                                                        Destination::Home
                                                            | Destination::Settings
                                                            | Destination::Ai
                                                            | Destination::Capture
                                                    ))
                                                    .on_click(cx.listener(
                                                        |this, _, window, cx| {
                                                            this.prepare_ai(window, cx)
                                                        },
                                                    )),
                                            )
                                            .child(
                                                Button::new("open-captured-cpu")
                                                    .label(
                                                        language
                                                            .text(TextKey::ShellOpenCapturedCpu),
                                                    )
                                                    .disabled(self.captured.is_none())
                                                    .on_click(cx.listener(|this, _, _, cx| {
                                                        this.open_captured(cx)
                                                    })),
                                            )
                                    }),
                            )
                            .children(self.notice.clone().map(|notice| {
                                div().p_3().text_color(cx.theme().danger).child(notice)
                            }))
                            .child({
                                // The CPU report is a fill-height layout with its
                                // own internal viewports; every other page keeps
                                // the page-level vertical scroll.
                                let workspace = div()
                                    .id("workspace")
                                    .bg(background)
                                    .flex_1()
                                    .min_h_0()
                                    .child(body);
                                if matches!(
                                    selected,
                                    Destination::Home
                                        | Destination::Gpu
                                        | Destination::Battery
                                        | Destination::Network
                                        | Destination::Startup
                                        | Destination::Cpu
                                        | Destination::LayoutInspector
                                ) {
                                    workspace.overflow_hidden()
                                } else {
                                    workspace.overflow_y_scroll()
                                }
                            }),
                    ),
            )
    }
}

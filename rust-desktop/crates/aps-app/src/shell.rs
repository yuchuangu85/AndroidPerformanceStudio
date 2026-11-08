mod home_page;
pub(crate) mod menubar;
mod settings_ai;
mod settings_page;

use crate::{
    ai::AiWorkspace,
    benchmark::{BenchmarkWorkspace, OpenBenchmarkTrace},
    capture::{CaptureCompleted, CaptureWorkspace},
    cpu::CpuWorkspace,
    frame::FrameWorkspace,
    import::ImportWorkspace,
    inspector::{InspectorPanelVisibility, InspectorPanelsChanged, InspectorWorkspace},
    method::MethodWorkspace,
    source::SourceWorkspace,
    targets::{TargetSelectionChanged, TargetsWorkspace},
    trace::TraceWorkspace,
};
use aps_ui_foundation::{
    AccentColor, Destination, DisplayScale, Language, LayoutPreferences, Settings, TextKey,
    ThemeMode, typography,
};
use gpui_kit::{
    base::{TextSelection, TextSelectionLayer},
    component::InteractiveElementExt,
    component::{
        ActiveTheme, Disableable, Placement, Selectable,
        button::{Button, ButtonVariants},
        menu::{DropdownMenu, PopupMenuItem},
    },
    prelude::*,
    *,
};
use menubar::MenuGroup;
use std::{collections::HashMap, path::PathBuf, rc::Rc};

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

    /// Locale key of the section; the in-window menubar reuses it for its
    /// section items so the settings menu cannot drift from the page tabs.
    const fn text_key(self) -> TextKey {
        match self {
            Self::General => TextKey::CommonGeneral,
            Self::Configuration => TextKey::CommonConfiguration,
            Self::LayoutInspector => TextKey::FoundationLayoutInspector,
            Self::Simpleperf => TextKey::CommonSimpleperf,
            Self::Ai => TextKey::CommonAi,
            Self::About => TextKey::CommonAbout,
        }
    }

    fn label(self, language: Language) -> &'static str {
        language.text(self.text_key())
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
        OpenAiWorkspace,
        ToggleLayoutHideHierarchy,
        ToggleLayoutHideFindings,
        ToggleLayoutHideIndices,
        ToggleLayoutShowLayers,
        ToggleLayoutShowBounds,
        ToggleLayoutShowIds,
        OpenActiveRouteFile,
        OpenFolderActiveRoute,
        ExportActiveRoute,
        PreviousInspectorNode,
        NextInspectorNode,
        ToggleSelectedInspectorNode,
        RecentFilesActiveRoute,
        CloseActiveRoute,
        FocusActiveSearch,
        GoToSourceActiveRoute,
        ToggleAllInspectorPanes,
        StartCaptureActiveRoute,
    ]
);

/// Opens one of the capture-configuration sections. Compose publishes three
/// Tools entries; the Rust settings page exposes one capture section today, so
/// the value keeps the menu shape while all three open that section.
#[derive(Clone, PartialEq, Debug, Action)]
#[action(namespace = studio, no_json)]
pub struct OpenCaptureSettings {
    pub section: u8,
}

/// View-menu toggles that persist through the shared layout preferences.
#[derive(Clone, Copy, PartialEq, Eq)]
enum LayoutMenuToggle {
    HideInvisibleHierarchy,
    HideInvisibleFindings,
    HideHierarchyIndices,
    ShowLayerButtons,
    ShowVisibleBounds,
    ShowIds,
}

/// Commands published by the shell-owned menus: Home, Settings, Layout
/// Inspector, Analyze and Help. Workspaces publish their own typed commands;
/// the inspector, AI and settings surfaces belong to the shell, so they stay
/// here where their state already lives.
#[derive(Clone, Copy, PartialEq, Eq)]
enum ShellMenu {
    OpenActiveRouteFile,
    RecentFilesActiveRoute,
    CloseActiveRoute,
    FocusActiveSearch,
    GoToSourceActiveRoute,
    ToggleAllPanes,
    StartCaptureActiveRoute,
    OpenUserGuide,
    Unavailable,
    PreviousInspectorNode,
    NextInspectorNode,
    ToggleSelectedInspectorNode,
    ImportCpuProfile,
    ImportTrace,
    ImportHprof,
    ImportBattery,
    ImportFrameTimeline,
    OpenInspectorArchive,
    OpenCapturedCpu,
    ToggleInspectorHierarchy,
    ToggleInspectorFindings,
    ToggleInspectorDetails,
    NextInspectorWindow,
    ToggleLayout(LayoutMenuToggle),
    PrepareAi,
    OpenAiWorkspace,
    OpenSettings,
    SettingsTab(SettingsPage),
}

/// Home publishes the import surface that starts every workspace.
fn home_menu_groups() -> Vec<MenuGroup<ShellMenu>> {
    vec![
        MenuGroup::new(TextKey::ShellMenuFile)
            .item(
                TextKey::ShellMenuImportCpuProfile,
                ShellMenu::ImportCpuProfile,
            )
            .item(TextKey::HomeImportTrace, ShellMenu::ImportTrace)
            .item(TextKey::HomeAnalyzeHprof, ShellMenu::ImportHprof)
            .item(TextKey::BatteryImportAnalysis, ShellMenu::ImportBattery)
            .item(
                TextKey::FrameImportGfxinfoFramestats,
                ShellMenu::ImportFrameTimeline,
            )
            .separator()
            .item(
                TextKey::InspectorImportArchive,
                ShellMenu::OpenInspectorArchive,
            ),
    ]
}

/// Settings publishes its own sections instead of repeating the page tabs.
fn settings_menu_groups(page: SettingsPage) -> Vec<MenuGroup<ShellMenu>> {
    let mut group = MenuGroup::new(TextKey::ShellMenuSettingsPage);
    for section in SettingsPage::ALL {
        group = group.check(
            section.text_key(),
            section == page,
            ShellMenu::SettingsTab(section),
        );
    }
    vec![group]
}

/// Layout Inspector menus: archive import, pane and view toggles, and the
/// window and node tools that previously only existed in the native menu.
fn inspector_menu_groups(
    panels: InspectorPanelVisibility,
    layout: LayoutPreferences,
    state: crate::inspector::InspectorMenuState,
) -> Vec<MenuGroup<ShellMenu>> {
    let file = MenuGroup::new(TextKey::ShellMenuFile)
        .item_action(
            TextKey::ShellMenuOpen,
            ShellMenu::OpenInspectorArchive,
            OpenActiveRouteFile,
        )
        .submenu(TextKey::ShellMenuImport, true, |menu| {
            menu.item_when(
                false,
                TextKey::ShellMenuImportScreenshot,
                ShellMenu::Unavailable,
            )
        })
        .submenu(TextKey::ShellMenuOpenRecent, !state.busy, |menu| {
            menu.item_when(false, TextKey::ShellMenuNoRecent, ShellMenu::Unavailable)
                .separator()
                .item_when(false, TextKey::ShellMenuClearRecent, ShellMenu::Unavailable)
        })
        .separator()
        .submenu(TextKey::ShellMenuExport, !state.busy, |menu| {
            menu.item_when(
                false,
                TextKey::ShellMenuExportArchive,
                ShellMenu::Unavailable,
            )
        });
    // macOS reaches Settings through the application menu, matching Compose.
    #[cfg(not(target_os = "macos"))]
    let file = file.separator().item_action(
        TextKey::ShellMenuSettings,
        ShellMenu::OpenSettings,
        OpenSettings,
    );
    vec![
        file,
        MenuGroup::new(TextKey::ShellMenuView)
            .check_action(
                TextKey::InspectorToggleHierarchy,
                !panels.hierarchy,
                ShellMenu::ToggleInspectorHierarchy,
                ToggleInspectorHierarchy,
            )
            .check_action(
                TextKey::InspectorToggleFindings,
                !panels.findings,
                ShellMenu::ToggleInspectorFindings,
                ToggleInspectorFindings,
            )
            .check_action(
                TextKey::InspectorToggleDetails,
                !panels.details,
                ShellMenu::ToggleInspectorDetails,
                ToggleInspectorDetails,
            )
            .separator()
            .item_action(
                TextKey::ShellMenuFocusSearch,
                ShellMenu::FocusActiveSearch,
                FocusActiveSearch,
            )
            .item_action(
                if panels.hierarchy || panels.findings || panels.details {
                    TextKey::ShellMenuHideAllPanes
                } else {
                    TextKey::ShellMenuShowAllPanes
                },
                ShellMenu::ToggleAllPanes,
                ToggleAllInspectorPanes,
            )
            .separator()
            .check_action(
                TextKey::SettingsUiHideHierarchy,
                layout.hide_invisible_hierarchy,
                ShellMenu::ToggleLayout(LayoutMenuToggle::HideInvisibleHierarchy),
                ToggleLayoutHideHierarchy,
            )
            .check_action(
                TextKey::SettingsUiHideFindings,
                layout.hide_invisible_findings,
                ShellMenu::ToggleLayout(LayoutMenuToggle::HideInvisibleFindings),
                ToggleLayoutHideFindings,
            )
            .check_action(
                TextKey::SettingsUiHideIndices,
                layout.hide_indices,
                ShellMenu::ToggleLayout(LayoutMenuToggle::HideHierarchyIndices),
                ToggleLayoutHideIndices,
            )
            .separator()
            .check_action(
                TextKey::SettingsUiShowLayers,
                layout.show_layer_buttons,
                ShellMenu::ToggleLayout(LayoutMenuToggle::ShowLayerButtons),
                ToggleLayoutShowLayers,
            )
            .separator()
            .check_action(
                TextKey::SettingsUiShowBounds,
                layout.show_visible_bounds,
                ShellMenu::ToggleLayout(LayoutMenuToggle::ShowVisibleBounds),
                ToggleLayoutShowBounds,
            ),
        MenuGroup::new(TextKey::ShellMenuTools)
            .item_when(false, TextKey::InspectorAutoScan, ShellMenu::Unavailable)
            .separator()
            .item_action_when(
                state.has_selection,
                TextKey::ShellMenuPreviousNode,
                ShellMenu::PreviousInspectorNode,
                PreviousInspectorNode,
            )
            .item_action_when(
                state.has_selection,
                TextKey::ShellMenuNextNode,
                ShellMenu::NextInspectorNode,
                NextInspectorNode,
            )
            // gpui renders the Enter key equivalent as a single character, so this
            // row keeps the command (and its real key binding) without a
            // misleading shortcut label in the native menu.
            .item_when(
                state.has_selection,
                TextKey::ShellMenuToggleSelectedNode,
                ShellMenu::ToggleSelectedInspectorNode,
            )
            .separator()
            .check_action(
                TextKey::SettingsUiShowIds,
                layout.show_ids,
                ShellMenu::ToggleLayout(LayoutMenuToggle::ShowIds),
                ToggleLayoutShowIds,
            )
            .separator()
            .item_action(
                TextKey::InspectorNextWindow,
                ShellMenu::NextInspectorWindow,
                NextInspectorWindow,
            ),
    ]
}

/// Routes that freeze profiler evidence for AI share one Analyze menu.
fn supports_ai(destination: Destination) -> bool {
    matches!(
        destination,
        Destination::Cpu
            | Destination::MethodRecording
            | Destination::LayoutInspector
            | Destination::Trace
            | Destination::Frame
            | Destination::SourceWorkspaces
            | Destination::Memory
            | Destination::Network
            | Destination::Startup
            | Destination::Battery
            | Destination::Benchmark
            | Destination::Gpu
    )
}

/// The Analyze menu freezes the active workspace's evidence for the AI panel.
fn analyze_menu_group() -> MenuGroup<ShellMenu> {
    MenuGroup::new(TextKey::ShellMenuAnalyze).item_action(
        TextKey::ShellPrepareAiAnalysis,
        ShellMenu::PrepareAi,
        PrepareCurrentAi,
    )
}

/// The Help menu is the same on every route: AI and settings are application
/// level, so they stay reachable without a mode-specific menu.
fn help_menu_group(language: Language) -> MenuGroup<ShellMenu> {
    // Compose publishes Help > User guide and Help > AI analysis. The bundled
    // documentation site is opened in the browser; the row stays disabled when
    // the documentation is not shipped next to the executable.
    let guide = user_guide_path(language).is_some();
    let group = MenuGroup::new(TextKey::ShellMenuHelp)
        .item_when(guide, TextKey::SettingsUiGuide, ShellMenu::OpenUserGuide)
        .item(TextKey::FoundationAiAnalysis, ShellMenu::OpenAiWorkspace);
    // macOS reaches Settings through the application menu.
    #[cfg(not(target_os = "macos"))]
    let group = group
        .separator()
        .item(TextKey::ShellMenuSettings, ShellMenu::OpenSettings);
    group
}

/// Locates the bundled documentation landing page for the current language.
fn user_guide_path(language: Language) -> Option<PathBuf> {
    let relative = if language == Language::SimplifiedChinese {
        "docs-user-zh/index.html"
    } else {
        "docs-user/index.html"
    };
    let mut roots = Vec::new();
    if let Ok(current) = std::env::current_dir() {
        roots.push(current);
    }
    if let Ok(executable) = std::env::current_exe()
        && let Some(directory) = executable.parent()
    {
        roots.push(directory.to_path_buf());
    }
    for root in roots {
        for ancestor in root.ancestors() {
            let candidate = ancestor.join(relative);
            if candidate.is_file() {
                return Some(candidate);
            }
        }
    }
    None
}

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
    benchmark: Entity<BenchmarkWorkspace>,
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
    /// Menus of the active route: native menu data is installed on macOS and the
    /// fallback strip is rendered elsewhere.
    route_menu: Vec<menubar::StripMenu>,
    route_menu_dispatch: Rc<Vec<menubar::RouteDispatch>>,
    /// Signature of the installed route menus; unchanged state does not reinstall
    /// the native menu bar.
    route_menu_signature: String,
    self_handle: WeakEntity<Self>,
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
        // Rust-owned AI state: settings, Keychain-backed credentials, conversation
        // database and report artifacts live under <Rust data dir>/ai only.
        let ai_data_dir = settings_path
            .as_ref()
            .and_then(|path| path.parent())
            .map(|root| root.join("ai"));
        if let Some(directory) = ai_data_dir {
            if let Ok(runtime) = aps_ai::runtime::AiRuntime::open(directory) {
                let _ = runtime.set_http_client(cx.http_client());
                ai.update(cx, |ai, cx| ai.set_runtime(runtime, cx));
            }
        }
        // Validated AI findings navigate to their persisted source candidates and
        // the chat sidebar opens the real AI settings page.
        let settings_handle = studio_handle.clone();
        let source_handle = studio_handle.clone();
        ai.update(cx, |ai, _| {
            ai.set_open_settings(Box::new(move |window, cx| {
                settings_handle
                    .update(cx, |studio, cx| studio.open_ai_settings(window, cx))
                    .ok();
            }));
            ai.set_open_source(Box::new(move |candidate_id, window, cx| {
                source_handle
                    .update(cx, |studio, cx| {
                        studio.open_source_candidate(candidate_id, window, cx)
                    })
                    .ok();
            }));
        });
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
        source.update(cx, |source, cx| {
            source.set_studio(studio_handle.clone());
            source.set_lsp_enabled(settings.source_lsp_enabled, cx);
        });
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
        let benchmark = cx.new(|cx| {
            BenchmarkWorkspace::new(
                settings.language.resolved(),
                settings_path.as_deref(),
                window,
                cx,
            )
        });
        benchmark.update(cx, |benchmark, _| {
            benchmark.set_studio(studio_handle.clone())
        });
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
            Destination::Gpu,
        ]
        .into_iter()
        .map(|kind| {
            let workspace = cx.new(|_| ImportWorkspace::new(kind, settings.language.resolved()));
            workspace.update(cx, |workspace, _| {
                workspace.set_studio(studio_handle.clone())
            });
            (kind, workspace)
        })
        .collect();
        let mut subscriptions = vec![
            cx.observe(&cpu, |this, _, cx| {
                this.invalidate_ai(Destination::Cpu, cx);
                this.refresh_route_menus(cx);
            }),
            cx.observe(&method, |this, _, cx| {
                this.invalidate_ai(Destination::MethodRecording, cx);
                this.refresh_route_menus(cx);
            }),
            cx.observe(&inspector, |this, _, cx| {
                this.invalidate_ai(Destination::LayoutInspector, cx);
                this.refresh_route_menus(cx);
            }),
            cx.observe(&source, |this, _, cx| {
                if this.ai_origin.is_some() {
                    this.ai.update(cx, |ai, cx| ai.invalidate_origin(cx));
                    this.ai_origin = None;
                }
                this.refresh_route_menus(cx);
            }),
            cx.observe(&trace, |this, _, cx| {
                this.invalidate_ai(Destination::Trace, cx);
                this.refresh_route_menus(cx);
            }),
            cx.observe(&frame, |this, _, cx| {
                this.invalidate_ai(Destination::Frame, cx);
                this.refresh_route_menus(cx);
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
                this.refresh_route_menus(cx);
                cx.notify();
            }),
        ];
        // Pane visibility feeds the Layout Inspector View menu checks.
        subscriptions.push(
            cx.subscribe(&inspector, |this, _, _: &InspectorPanelsChanged, cx| {
                this.refresh_route_menus(cx)
            }),
        );
        for (kind, import) in &imports {
            let kind = *kind;
            subscriptions.push(cx.observe(import, move |this, _, cx| {
                this.invalidate_ai(kind, cx);
                this.refresh_route_menus(cx);
            }));
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
        subscriptions.push(cx.observe(&benchmark, |this, _, cx| {
            this.invalidate_ai(Destination::Benchmark, cx);
            this.refresh_route_menus(cx);
        }));
        // A benchmark case may record trace artifacts; opening one goes through the
        // same Trace Analyzer import path as every other page.
        subscriptions.push(
            cx.subscribe(&benchmark, |this, _, event: &OpenBenchmarkTrace, cx| {
                if let Some(path) = event.0.clone() {
                    this.trace
                        .update(cx, |trace, cx| trace.open_trace_path(path, cx));
                }
                this.navigate(Destination::Trace, cx);
            }),
        );
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
        let self_handle = cx.weak_entity();
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
            benchmark,
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
            route_menu: Vec::new(),
            route_menu_dispatch: Rc::new(Vec::new()),
            route_menu_signature: String::new(),
            self_handle,
            _subscriptions: subscriptions,
        };
        studio.refresh_route_menus(cx);
        studio.refresh_home_recent(cx);
        // Debug-only verification seam: lets a headless/native UI check open a
        // real HPROF on launch without driving the native file picker. Release
        // builds never read the variable.
        #[cfg(debug_assertions)]
        if let Some(path) = std::env::var_os("APS_RUST_OPEN_HPROF") {
            let memory = studio.imports.get(&Destination::Memory).cloned();
            if let Some(memory) = memory {
                let opened = memory.update(cx, |memory, cx| {
                    memory.open_home_artifact(std::path::PathBuf::from(path), cx)
                });
                if opened {
                    studio.destination = Destination::Memory;
                }
            }
        }
        // Debug-only verification seam: starts on a route without driving the
        // native navigation, so menu/toolbar checks run headlessly.
        #[cfg(debug_assertions)]
        if let Some(route) = std::env::var_os("APS_RUST_OPEN_ROUTE") {
            let route = route.to_string_lossy().to_ascii_lowercase();
            studio.destination = match route.as_str() {
                "home" => Destination::Home,
                "inspector" => Destination::LayoutInspector,
                "cpu" => Destination::Cpu,
                "trace" => Destination::Trace,
                "memory" => Destination::Memory,
                "frame" => Destination::Frame,
                "startup" => Destination::Startup,
                "battery" => Destination::Battery,
                "network" => Destination::Network,
                "gpu" => Destination::Gpu,
                "benchmark" => Destination::Benchmark,
                "source" => Destination::SourceWorkspaces,
                "ai" => Destination::Ai,
                "settings" => Destination::Settings,
                _ => studio.destination,
            };
        }
        // Debug-only verification seam: opens a picked CPU source on launch
        // without driving the native file picker. Runs after the route seam so an
        // explicit CPU source always lands on the CPU workspace.
        #[cfg(debug_assertions)]
        if let Some(path) = std::env::var_os("APS_RUST_OPEN_CPU") {
            studio.destination = Destination::Cpu;
            let cpu = studio.cpu.clone();
            cpu.update(cx, |cpu, cx| {
                cpu.open_debug_source(std::path::PathBuf::from(path), cx)
            });
        }
        // Debug-only verification seam: opens Settings on the AI page without
        // driving the native navigation.
        #[cfg(debug_assertions)]
        if std::env::var_os("APS_RUST_OPEN_SETTINGS_AI").is_some() {
            studio.destination = Destination::Settings;
            studio.settings_page = SettingsPage::Ai;
        }
        // Debug-only verification seam: opens an exported frame-analysis.json
        // on launch without driving the native file picker.
        #[cfg(debug_assertions)]
        if let Some(path) = std::env::var_os("APS_RUST_OPEN_FRAME_JSON") {
            studio.destination = Destination::Frame;
            studio.frame.update(cx, |frame, cx| {
                frame.import_debug_analysis_json(std::path::PathBuf::from(path), cx)
            });
        }
        // Debug-only verification seam: opens a real Inspector archive without
        // driving the platform file picker.
        #[cfg(debug_assertions)]
        if let Some(path) = std::env::var_os("APS_RUST_OPEN_INSPECTOR") {
            studio.destination = Destination::LayoutInspector;
            studio.inspector.update(cx, |inspector, cx| {
                inspector.open_debug_archive(std::path::PathBuf::from(path), cx)
            });
        }
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
            Destination::Benchmark => self.benchmark.read(cx).analysis_request(),
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
                    .update(cx, |ai, cx| ai.bind_context(request, window, cx));
                self.ai_origin = Some(origin);
                self.destination = Destination::Ai;
                self.notice = None;
            }
            Err(error) => self.notice = Some(error),
        }
        cx.notify();
    }
    /// Opens the bundled user documentation in the default browser.
    fn open_user_guide(&mut self, cx: &mut Context<Self>) {
        // A file:// page cannot load the docsify bundle: it requests markdown with
        // an absolute basePath and browsers block XHR from file://. Serve the tree
        // from loopback instead, matching the Compose launcher.
        match crate::user_guide::url(self.settings.language) {
            Ok(url) => cx.open_url(&url),
            Err(error) => eprintln!("User guide unavailable: {error}"),
        }
    }

    /// IDEA Recent Files (Cmd+E): opens the newest listed session of the active
    /// page. Pages without a persisted recent catalog do nothing.
    fn recent_files_active_route(&mut self, cx: &mut Context<Self>) {
        match self.destination {
            Destination::Cpu => {
                self.cpu
                    .update(cx, |cpu, cx| cpu.open_most_recent_session(cx));
            }
            Destination::Trace => {
                self.trace
                    .update(cx, |trace, cx| trace.open_most_recent_session(cx));
            }
            _ => {}
        }
    }

    /// IDEA Close editor (Cmd+W): closes the active page's open document.
    fn close_active_route(&mut self, cx: &mut Context<Self>) {
        match self.destination {
            Destination::Cpu => {
                self.cpu.update(cx, |cpu, cx| cpu.close_primary(cx));
            }
            Destination::Trace => {
                self.trace.update(cx, |trace, cx| trace.close_primary(cx));
            }
            Destination::MethodRecording => {
                self.method
                    .update(cx, |method, cx| method.close_primary(cx));
            }
            _ => {}
        }
    }

    /// IDEA Find (Cmd+F): focuses the active page's search field.
    fn focus_active_search(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        match self.destination {
            Destination::Cpu => {
                self.cpu.update(cx, |cpu, cx| cpu.focus_search(window, cx));
            }
            Destination::LayoutInspector => {
                self.inspector.update(cx, |inspector, cx| {
                    inspector.focus_search_primary(window, cx)
                });
            }
            _ => {}
        }
    }

    /// IDEA Go to Declaration (Cmd+B): navigates from the current evidence to its
    /// source. CPU opens the selected frame's source, Frame correlates the
    /// selected frame with the Layout Inspector, Source opens the selected
    /// location in the IDE.
    fn go_to_source_active_route(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        match self.destination {
            Destination::Cpu => {
                self.cpu
                    .update(cx, |cpu, cx| cpu.open_selected_source_primary(window, cx));
            }
            Destination::Frame => {
                self.frame
                    .update(cx, |frame, cx| frame.inspect_layout_primary(cx));
            }
            Destination::SourceWorkspaces => {
                self.source
                    .update(cx, |source, cx| source.open_location_in_ide(cx));
            }
            _ => {}
        }
    }

    /// IDEA Hide All Tool Windows (Shift+Cmd+F12): hides every Layout Inspector
    /// pane, or restores them when they are already hidden.
    fn toggle_all_inspector_panes(&mut self, cx: &mut Context<Self>) {
        if self.destination == Destination::LayoutInspector {
            self.inspector
                .update(cx, |inspector, cx| inspector.toggle_all_panes(cx));
        }
    }

    /// IDEA Run (Ctrl+R): starts the active page's capture when it has one.
    fn start_capture_active_route(&mut self, cx: &mut Context<Self>) {
        match self.destination {
            Destination::Trace => {
                self.trace
                    .update(cx, |trace, cx| trace.toggle_capture_primary(cx));
            }
            Destination::Frame => {
                self.frame
                    .update(cx, |frame, cx| frame.start_capture_primary(cx));
            }
            _ => {}
        }
    }

    /// Opens the active page's folder picker (Compose Open Folder…, Cmd+Shift+O).
    fn open_folder_active_route(&mut self, cx: &mut Context<Self>) {
        if self.destination == Destination::Cpu {
            self.cpu.update(cx, |cpu, cx| cpu.open_folder_primary(cx));
        }
    }

    /// Runs the active page's primary export (Compose assigns Cmd+E to the most
    /// common export of each page). Pages without a Compose export shortcut keep
    /// the current document instead.
    fn export_active_route(&mut self, cx: &mut Context<Self>) {
        match self.destination {
            Destination::Cpu => {
                self.cpu.update(cx, |cpu, cx| cpu.export_primary(cx));
            }
            Destination::Trace => {
                self.trace.update(cx, |trace, cx| trace.export_primary(cx));
            }
            _ => {}
        }
    }

    /// Opens the capture-configuration settings section from Tools > Configuration.
    fn open_capture_settings(&mut self, section: u8, cx: &mut Context<Self>) {
        let _ = section;
        self.settings_page = SettingsPage::Simpleperf;
        self.destination = Destination::Settings;
        self.refresh_route_menus(cx);
        cx.notify();
    }

    /// Opens the file picker of the active route. The window menu publishes one
    /// Open… row per page, so Cmd+O keeps matching the visible page instead of a
    /// single global import.
    fn open_active_route_file(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        match self.destination {
            Destination::LayoutInspector => {
                self.open_inspector_archive(&OpenInspectorArchive, window, cx)
            }
            Destination::Trace => {
                self.trace.update(cx, |trace, cx| trace.pick_trace(cx));
            }
            Destination::Frame => {
                self.frame
                    .update(cx, |frame, cx| frame.pick_import_frame_stats(cx));
            }
            Destination::Benchmark => {
                self.benchmark
                    .update(cx, |benchmark, cx| benchmark.import_run(cx));
            }
            Destination::Memory
            | Destination::Network
            | Destination::Startup
            | Destination::Battery
            | Destination::Gpu => {
                if let Some(workspace) = self.imports.get(&self.destination) {
                    workspace.update(cx, |workspace, cx| workspace.pick(cx));
                }
            }
            Destination::Cpu
            | Destination::Home
            | Destination::Capture
            | Destination::MethodRecording => self.open_cpu(&OpenProfile, window, cx),
            Destination::SourceWorkspaces | Destination::Ai | Destination::Settings => {}
        }
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

    fn toggle_layout_menu(
        &mut self,
        toggle: LayoutMenuToggle,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let layout = &mut self.settings.layout;
        match toggle {
            LayoutMenuToggle::HideInvisibleHierarchy => {
                layout.hide_invisible_hierarchy = !layout.hide_invisible_hierarchy;
            }
            LayoutMenuToggle::HideInvisibleFindings => {
                layout.hide_invisible_findings = !layout.hide_invisible_findings;
            }
            LayoutMenuToggle::HideHierarchyIndices => {
                layout.hide_indices = !layout.hide_indices;
            }
            LayoutMenuToggle::ShowLayerButtons => {
                layout.show_layer_buttons = !layout.show_layer_buttons;
            }
            LayoutMenuToggle::ShowVisibleBounds => {
                layout.show_visible_bounds = !layout.show_visible_bounds;
            }
            LayoutMenuToggle::ShowIds => {
                layout.show_ids = !layout.show_ids;
            }
        }
        self.persist_settings(window, cx);
    }

    fn open_ai_workspace(&mut self, _: &OpenAiWorkspace, _: &mut Window, cx: &mut Context<Self>) {
        self.destination = Destination::Ai;
        cx.notify();
    }

    /// Builds the menu bar of the active route from live workspace state.
    ///
    /// Each workspace publishes its own groups, so the bar always describes the
    /// functionality of the screen that is showing; the shell adds the shared
    /// Analyze and Help menus. One build feeds both the native macOS menu bar and
    /// the fallback strip, so the two platforms cannot drift apart.
    fn route_menu_build(&self, language: Language, cx: &App) -> menubar::RouteMenuBuild {
        let mut build = menubar::RouteMenuBuild::default();
        match self.destination {
            Destination::Home => self.push_shell_menus(home_menu_groups(), language, &mut build),
            Destination::Settings => self.push_shell_menus(
                settings_menu_groups(self.settings_page),
                language,
                &mut build,
            ),
            Destination::LayoutInspector => {
                let inspector = self.inspector.read(cx);
                let groups = inspector_menu_groups(
                    inspector.panel_visibility(),
                    self.settings.layout,
                    inspector.menu_state(),
                );
                self.push_shell_menus(groups, language, &mut build);
            }
            Destination::Cpu => self.push_workspace_menus(&self.cpu, language, &mut build, cx),
            Destination::Trace => self.push_workspace_menus(&self.trace, language, &mut build, cx),
            Destination::Frame => self.push_workspace_menus(&self.frame, language, &mut build, cx),
            Destination::SourceWorkspaces => {
                self.push_workspace_menus(&self.source, language, &mut build, cx)
            }
            Destination::Ai => self.push_workspace_menus(&self.ai, language, &mut build, cx),
            Destination::Capture => {
                self.push_workspace_menus(&self.capture, language, &mut build, cx)
            }
            Destination::MethodRecording => {
                self.push_workspace_menus(&self.method, language, &mut build, cx)
            }
            Destination::Benchmark => {
                self.push_workspace_menus(&self.benchmark, language, &mut build, cx)
            }
            Destination::Memory
            | Destination::Network
            | Destination::Startup
            | Destination::Battery
            | Destination::Gpu => {
                if let Some(workspace) = self.imports.get(&self.destination) {
                    self.push_workspace_menus(workspace, language, &mut build, cx);
                }
            }
        }
        if supports_ai(self.destination) {
            self.push_shell_menus(vec![analyze_menu_group()], language, &mut build);
        }
        self.push_shell_menus(vec![help_menu_group(language)], language, &mut build);
        build
    }

    /// Adds a workspace's menus; commands route back into the workspace that
    /// published them.
    fn push_workspace_menus<H: menubar::MenuHost>(
        &self,
        entity: &Entity<H>,
        language: Language,
        build: &mut menubar::RouteMenuBuild,
        cx: &App,
    ) {
        for group in entity.read(cx).menu_groups(language) {
            let entity = entity.clone();
            build.push(group, language, move |command, window, cx| {
                entity.update(cx, |this, cx| this.run_menu_command(command, window, cx));
            });
        }
    }

    /// Adds shell-owned groups (Home, Settings, Layout Inspector, Analyze, Help)
    /// and routes their commands back to the shell.
    fn push_shell_menus(
        &self,
        groups: Vec<MenuGroup<ShellMenu>>,
        language: Language,
        build: &mut menubar::RouteMenuBuild,
    ) {
        for group in groups {
            if group.is_empty() {
                continue;
            }
            let studio = self.self_handle.clone();
            build.push(group, language, move |command, window, cx| {
                if let Some(studio) = studio.upgrade() {
                    studio.update(cx, |this, cx| this.run_shell_menu(command, window, cx));
                }
            });
        }
    }

    /// Rebuilds the route menus and reinstalls them only when their content
    /// changed, so notifications do not close the menu the user has open.
    fn refresh_route_menus(&mut self, cx: &mut Context<Self>) {
        let mut build = self.route_menu_build(self.settings.language, cx);
        if build.signature == self.route_menu_signature {
            return;
        }
        self.route_menu_signature = build.signature;
        self.route_menu_dispatch = Rc::new(std::mem::take(&mut build.dispatch));
        self.route_menu = std::mem::take(&mut build.strip);
        #[cfg(target_os = "macos")]
        menubar::install_native_menus(build.menus, self.settings.language, cx);
        #[cfg(not(target_os = "macos"))]
        let _ = build.menus;
    }

    /// Runs a command chosen in the native menu bar. Deferred to the next frame
    /// so a workspace command can write back through its shell handle without
    /// re-entering the shell update that dispatched the menu action.
    fn run_route_menu(&mut self, id: u32, window: &mut Window, cx: &mut Context<Self>) {
        let Some(run) = self.route_menu_dispatch.get(id as usize).cloned() else {
            return;
        };
        window.defer(cx, move |window, cx| run(window, cx));
    }

    fn run_shell_menu(&mut self, command: ShellMenu, window: &mut Window, cx: &mut Context<Self>) {
        match command {
            ShellMenu::OpenActiveRouteFile => self.open_active_route_file(window, cx),
            ShellMenu::OpenUserGuide => self.open_user_guide(cx),
            ShellMenu::RecentFilesActiveRoute => self.recent_files_active_route(cx),
            ShellMenu::CloseActiveRoute => self.close_active_route(cx),
            ShellMenu::FocusActiveSearch => self.focus_active_search(window, cx),
            ShellMenu::GoToSourceActiveRoute => self.go_to_source_active_route(window, cx),
            ShellMenu::ToggleAllPanes => self.toggle_all_inspector_panes(cx),
            ShellMenu::StartCaptureActiveRoute => self.start_capture_active_route(cx),
            ShellMenu::Unavailable => {}
            ShellMenu::PreviousInspectorNode => {
                self.inspector
                    .update(cx, |inspector, cx| inspector.select_previous_node(cx));
            }
            ShellMenu::NextInspectorNode => {
                self.inspector
                    .update(cx, |inspector, cx| inspector.select_next_node(cx));
            }
            ShellMenu::ToggleSelectedInspectorNode => {
                self.inspector
                    .update(cx, |inspector, cx| inspector.toggle_selected_node(cx));
            }
            ShellMenu::ImportCpuProfile => self.open_cpu(&OpenProfile, window, cx),
            ShellMenu::ImportTrace => self.open_home_import(Destination::Trace, cx),
            ShellMenu::ImportHprof => self.open_home_import(Destination::Memory, cx),
            ShellMenu::ImportBattery => self.open_home_import(Destination::Battery, cx),
            ShellMenu::ImportFrameTimeline => {
                self.navigate(Destination::Frame, cx);
                self.frame
                    .update(cx, |frame, cx| frame.pick_import_frame_stats(cx));
            }
            ShellMenu::OpenInspectorArchive => {
                self.open_inspector_archive(&OpenInspectorArchive, window, cx)
            }
            ShellMenu::OpenCapturedCpu => self.open_captured(cx),
            ShellMenu::ToggleInspectorHierarchy => {
                self.inspector
                    .update(cx, |inspector, cx| inspector.toggle_hierarchy(cx));
            }
            ShellMenu::ToggleInspectorFindings => {
                self.inspector
                    .update(cx, |inspector, cx| inspector.toggle_findings(cx));
            }
            ShellMenu::ToggleInspectorDetails => {
                self.inspector
                    .update(cx, |inspector, cx| inspector.toggle_details(cx));
            }
            ShellMenu::NextInspectorWindow => {
                self.inspector
                    .update(cx, |inspector, cx| inspector.cycle_window(cx));
            }
            ShellMenu::ToggleLayout(toggle) => self.toggle_layout_menu(toggle, window, cx),
            ShellMenu::PrepareAi => self.prepare_ai(window, cx),
            ShellMenu::OpenAiWorkspace => self.open_ai_workspace(&OpenAiWorkspace, window, cx),
            ShellMenu::OpenSettings => self.open_settings(&OpenSettings, window, cx),
            ShellMenu::SettingsTab(page) => {
                self.settings_page = page;
                self.destination = Destination::Settings;
            }
        }
        self.refresh_route_menus(cx);
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
            source.set_language(self.settings.language.resolved(), window, cx);
            source.set_lsp_enabled(self.settings.source_lsp_enabled, cx);
        });
        self.trace.update(cx, |trace, cx| {
            trace.set_language(self.settings.language.resolved(), window, cx)
        });
        self.frame.update(cx, |frame, cx| {
            frame.set_language(self.settings.language.resolved(), window, cx)
        });
        self.benchmark.update(cx, |benchmark, cx| {
            benchmark.set_language(self.settings.language.resolved(), window, cx)
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
        self.refresh_route_menus(cx);
        cx.notify();
    }

    /// Home activation refreshes only the Rust-owned history projection. Device
    /// refresh remains owned by the shared title bar and cannot reload history.
    fn navigate(&mut self, destination: Destination, cx: &mut Context<Self>) {
        let entering_home = destination == Destination::Home && self.destination != destination;
        let route = destination;
        self.destination = destination;
        self.refresh_route_menus(cx);
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

/// When the rail is collapsed only icons are drawn, so hovering an icon shows
/// the destination title as a tooltip placed to the right of the rail.
fn navigation_tooltip(
    button: Button,
    destination: Destination,
    language: Language,
    collapsed: bool,
) -> Button {
    if collapsed {
        button
            .tooltip(destination.label(language).to_owned())
            .tooltip_placement(Placement::Right)
    } else {
        button
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
        // Shell chrome stays on the title-bar surface; workspace cards paint the
        // accent-derived panel/section surfaces from StudioColors.
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
            Destination::Benchmark => self.benchmark.clone().into_any_element(),
            Destination::Memory
            | Destination::Network
            | Destination::Startup
            | Destination::Battery
            | Destination::Gpu => self
                .imports
                .get(&selected)
                .expect("registered workspace")
                .clone()
                .into_any_element(),
        };
        let studio_colors = crate::studio::StudioColors::from_theme(cx);
        let menubar = menubar::strip(
            &self.route_menu,
            self.route_menu_dispatch.clone(),
            &studio_colors,
        );
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
            .child(
                div()
                    .text_color(primary_text)
                    .text_size(px(typography::BODY))
                    .child(crate::studio::selectable(
                        language.text(TextKey::FoundationAndroidPerformanceStudio),
                    )),
            )
            .child(div().flex_1())
            // The AI Analysis window has its own chat surface and no device
            // target; Compose's conversation window does not show one either.
            .when(selected != Destination::Ai, |this| {
                this.child(self.targets.clone())
            });
        div()
            .id("studio-root")
            .key_context(if selected == Destination::LayoutInspector {
                "Inspector"
            } else {
                "Studio"
            })
            .track_focus(&self.focus)
            // Window-wide text selection: the layer owns the drag/copy state for
            // every selectable run the pages project into the window.
            .on_key_down(|event: &KeyDownEvent, window, cx| {
                if !event.keystroke.modifiers.secondary(cx) {
                    return;
                }
                match event.keystroke.key.to_ascii_lowercase().as_str() {
                    "c" => {
                        let text = TextSelection::selected_text(window, cx);
                        if !text.is_empty() {
                            cx.write_to_clipboard(ClipboardItem::new_string(text));
                            cx.stop_propagation();
                        }
                    }
                    _ => {}
                }
            })
            .on_action(cx.listener(Self::open_cpu))
            .on_action(cx.listener(|this, _: &OpenActiveRouteFile, window, cx| {
                this.open_active_route_file(window, cx);
            }))
            .on_action(cx.listener(|this, _: &RecentFilesActiveRoute, _, cx| {
                this.recent_files_active_route(cx);
            }))
            .on_action(cx.listener(|this, _: &CloseActiveRoute, _, cx| {
                this.close_active_route(cx);
            }))
            .on_action(cx.listener(|this, _: &FocusActiveSearch, window, cx| {
                this.focus_active_search(window, cx);
            }))
            .on_action(cx.listener(|this, _: &GoToSourceActiveRoute, window, cx| {
                this.go_to_source_active_route(window, cx);
            }))
            .on_action(cx.listener(|this, _: &ToggleAllInspectorPanes, _, cx| {
                this.toggle_all_inspector_panes(cx);
            }))
            .on_action(cx.listener(|this, _: &StartCaptureActiveRoute, _, cx| {
                this.start_capture_active_route(cx);
            }))
            .on_action(cx.listener(|this, _: &OpenFolderActiveRoute, _, cx| {
                this.open_folder_active_route(cx);
            }))
            .on_action(cx.listener(|this, _: &ExportActiveRoute, _, cx| {
                this.export_active_route(cx);
            }))
            .on_action(cx.listener(|this, action: &OpenCaptureSettings, _, cx| {
                this.open_capture_settings(action.section, cx);
            }))
            .on_action(cx.listener(|this, _: &PreviousInspectorNode, _, cx| {
                this.inspector
                    .update(cx, |inspector, cx| inspector.select_previous_node(cx));
            }))
            .on_action(cx.listener(|this, _: &NextInspectorNode, _, cx| {
                this.inspector
                    .update(cx, |inspector, cx| inspector.select_next_node(cx));
            }))
            .on_action(cx.listener(|this, _: &ToggleSelectedInspectorNode, _, cx| {
                this.inspector
                    .update(cx, |inspector, cx| inspector.toggle_selected_node(cx));
            }))
            .on_action(
                cx.listener(|this, action: &menubar::RouteMenuAction, window, cx| {
                    this.run_route_menu(action.id, window, cx);
                }),
            )
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
            .on_action(cx.listener(Self::open_ai_workspace))
            .on_action(
                cx.listener(|this, _: &ToggleLayoutHideHierarchy, window, cx| {
                    this.toggle_layout_menu(LayoutMenuToggle::HideInvisibleHierarchy, window, cx);
                }),
            )
            .on_action(
                cx.listener(|this, _: &ToggleLayoutHideFindings, window, cx| {
                    this.toggle_layout_menu(LayoutMenuToggle::HideInvisibleFindings, window, cx);
                }),
            )
            .on_action(
                cx.listener(|this, _: &ToggleLayoutHideIndices, window, cx| {
                    this.toggle_layout_menu(LayoutMenuToggle::HideHierarchyIndices, window, cx);
                }),
            )
            .on_action(cx.listener(|this, _: &ToggleLayoutShowLayers, window, cx| {
                this.toggle_layout_menu(LayoutMenuToggle::ShowLayerButtons, window, cx);
            }))
            .on_action(cx.listener(|this, _: &ToggleLayoutShowBounds, window, cx| {
                this.toggle_layout_menu(LayoutMenuToggle::ShowVisibleBounds, window, cx);
            }))
            .on_action(cx.listener(|this, _: &ToggleLayoutShowIds, window, cx| {
                this.toggle_layout_menu(LayoutMenuToggle::ShowIds, window, cx);
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
            .child(TextSelectionLayer)
            .child(titlebar)
            .child(menubar)
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
                                        navigation_tooltip(
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
                                                    if is_selected {
                                                        accent
                                                    } else {
                                                        secondary_text
                                                    },
                                                )),
                                            destination,
                                            language,
                                            collapsed,
                                        )
                                        .on_click(
                                            cx.listener(move |this, _, _, cx| {
                                                this.navigate(destination, cx);
                                            }),
                                        ),
                                    )
                            }))
                            .child(div().flex_1())
                            .child(
                                div().h(px(36.)).w_full().rounded_md().child(
                                    navigation_tooltip(
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
                                            )),
                                        Destination::Ai,
                                        language,
                                        collapsed,
                                    )
                                    .on_click(cx.listener(
                                        |this, _, _, cx| {
                                            this.destination = Destination::Ai;
                                            cx.notify();
                                        },
                                    )),
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
                                        navigation_tooltip(
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
                                                )),
                                            Destination::Settings,
                                            language,
                                            collapsed,
                                        )
                                        .on_click(
                                            cx.listener(|this, _, _, cx| {
                                                this.destination = Destination::Settings;
                                                cx.notify();
                                            }),
                                        ),
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
                            // CPU, Inspector, Source Workspaces, Benchmark and Settings
                            // render their own headers directly below the title bar, so they
                            // have no shell page-title row; other pages keep the page chrome.
                            .children(
                                (selected != Destination::Home
                                    && selected != Destination::Cpu
                                    && selected != Destination::Memory
                                    && selected != Destination::LayoutInspector
                                    && selected != Destination::Network
                                    && selected != Destination::Startup
                                    && selected != Destination::Gpu
                                    && selected != Destination::Battery
                                    && selected != Destination::Trace
                                    && selected != Destination::Frame
                                    && selected != Destination::SourceWorkspaces
                                    && selected != Destination::Benchmark
                                    && selected != Destination::Settings
                                    && selected != Destination::Ai)
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
                                div()
                                    .p_3()
                                    .text_color(cx.theme().danger)
                                    .child(crate::studio::selectable(notice))
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
                                        | Destination::Memory
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

#[cfg(test)]
mod menubar_tests {
    use super::*;

    #[::core::prelude::v1::test]
    fn home_menu_publishes_the_import_surface() {
        let groups = home_menu_groups();
        assert_eq!(groups.len(), 1);
        assert_eq!(groups[0].title(), TextKey::ShellMenuFile);
        assert_eq!(groups[0].len(), 6);
    }

    #[::core::prelude::v1::test]
    fn settings_menu_publishes_every_section_once() {
        for page in SettingsPage::ALL {
            let groups = settings_menu_groups(page);
            assert_eq!(groups.len(), 1);
            assert_eq!(groups[0].title(), TextKey::ShellMenuSettingsPage);
            assert_eq!(groups[0].len(), SettingsPage::ALL.len());
        }
    }

    #[::core::prelude::v1::test]
    fn help_menu_is_shared_by_every_route() {
        let help = help_menu_group(Language::English);
        assert_eq!(help.title(), TextKey::ShellMenuHelp);
        // Compose Help publishes the user guide and AI analysis; macOS reaches
        // Settings through the application menu.
        #[cfg(target_os = "macos")]
        assert_eq!(help.len(), 2);
        #[cfg(not(target_os = "macos"))]
        assert_eq!(help.len(), 3);
    }

    /// The Layout Inspector window menu mirrors Compose NativeViewerMenuBar:
    /// File (Open/Import/Open Recent/Export), View (panes + display options) and
    /// Tools (auto scan + tree navigation + hierarchy ids).
    #[::core::prelude::v1::test]
    fn layout_inspector_menus_mirror_compose() {
        let panels = InspectorPanelVisibility {
            hierarchy: true,
            findings: true,
            details: true,
        };
        let layout = LayoutPreferences::default();
        let state = crate::inspector::InspectorMenuState {
            busy: false,
            has_selection: false,
            has_archive: true,
        };
        let groups = inspector_menu_groups(panels, layout, state);
        assert_eq!(groups.len(), 3);
        assert_eq!(groups[0].title(), TextKey::ShellMenuFile);
        assert_eq!(groups[1].title(), TextKey::ShellMenuView);
        assert_eq!(groups[2].title(), TextKey::ShellMenuTools);

        let mut build = menubar::RouteMenuBuild::default();
        for group in groups {
            build.push(group, Language::English, |_command, _window, _cx| {});
        }
        let file = &build.menus[0];
        assert!(matches!(file.items[0], MenuItem::Action { .. }));
        let submenus = file
            .items
            .iter()
            .filter(|item| matches!(item, MenuItem::Submenu(_)))
            .count();
        assert_eq!(submenus, 3, "Import, Open Recent and Export submenus");

        // Hide left panel is checked only while the pane is hidden, and the row
        // carries the real action so macOS shows Cmd+1.
        let MenuItem::Action {
            checked, action, ..
        } = &build.menus[1].items[0]
        else {
            panic!("expected the hierarchy pane row");
        };
        assert!(!checked);
        assert!(action.as_any().is::<ToggleInspectorHierarchy>());
        // Tree navigation stays disabled without a selection but still owns the
        // shortcut action that Cmd+S (arrow keys) resolve to.
        let MenuItem::Action {
            disabled, action, ..
        } = &build.menus[2].items[2]
        else {
            panic!("expected the previous node row");
        };
        assert!(disabled);
        assert!(action.as_any().is::<PreviousInspectorNode>());
    }

    /// Every screen owns its menu builder, so the strip follows the route
    /// instead of publishing one application-wide skeleton.
    #[::core::prelude::v1::test]
    fn every_workspace_publishes_its_own_route_menus() {
        for (source, marker) in [
            (include_str!("cpu.rs"), "MenuHost for CpuWorkspace"),
            (include_str!("trace.rs"), "MenuHost for TraceWorkspace"),
            (include_str!("frame.rs"), "MenuHost for FrameWorkspace"),
            (
                include_str!("benchmark.rs"),
                "MenuHost for BenchmarkWorkspace",
            ),
            (include_str!("source.rs"), "MenuHost for SourceWorkspace"),
            (include_str!("ai.rs"), "MenuHost for AiWorkspace"),
            (
                include_str!("import/menus.rs"),
                "MenuHost for ImportWorkspace",
            ),
        ] {
            assert!(source.contains(marker), "missing route menus: {marker}");
        }
    }

    /// macOS shows the route menus in the native application menu bar; other
    /// platforms fall back to the in-window strip. One build feeds both.
    /// The collapsed rail only draws icons, so hovering one reveals the
    /// destination title; the expanded rail keeps its visible labels.
    #[::core::prelude::v1::test]
    fn collapsed_navigation_reveals_destination_titles_as_tooltips() {
        let source = include_str!("shell.rs");
        assert!(source.contains("fn navigation_tooltip("));
        assert!(source.matches("navigation_tooltip(").count() >= 4);
        assert!(source.contains(".tooltip_placement(Placement::Right)"));
        assert!(source.contains("if collapsed {"));
    }

    /// The window menu follows JetBrains IDEA conventions for its commands.
    #[::core::prelude::v1::test]
    fn idea_window_shortcuts_are_bound() {
        let app = include_str!("main.rs");
        for keystroke in [
            "secondary-e",
            "secondary-w",
            "secondary-f",
            "secondary-b",
            "secondary-shift-f12",
        ] {
            assert!(app.contains(keystroke), "missing IDEA binding: {keystroke}");
        }
        for action in [
            "RecentFilesActiveRoute",
            "CloseActiveRoute",
            "FocusActiveSearch",
            "GoToSourceActiveRoute",
            "ToggleAllInspectorPanes",
            "StartCaptureActiveRoute",
        ] {
            assert!(app.contains(action), "missing IDEA action: {action}");
        }
    }

    #[::core::prelude::v1::test]
    fn the_route_menus_drive_native_and_fallback_bars() {
        let source = include_str!("shell.rs");
        assert!(source.contains("fn route_menu_build("));
        assert!(source.contains("fn refresh_route_menus("));
        assert!(source.contains("fn run_route_menu("));
        assert!(source.contains("menubar::install_native_menus"));
        assert!(source.contains("menubar::strip("));
        assert!(source.contains("RouteMenuAction"));
    }
}

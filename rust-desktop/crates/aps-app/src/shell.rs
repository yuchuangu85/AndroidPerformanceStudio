mod home_page;
pub(crate) mod menubar;
mod settings_ai;
mod settings_page;

use crate::notifications::{NoticeSnapshot, NoticeSource, NotificationCenter};
use crate::{
    ai::AiWorkspace,
    benchmark::{BenchmarkWorkspace, OpenBenchmarkTrace},
    capture::{CaptureCompleted, CaptureWorkspace},
    cpu::CpuWorkspace,
    frame::FrameWorkspace,
    home_recent::{
        HistoryMutation, HistoryMutationQueue, RecentPathEntry, RecentPathStore, RecordedFeature,
    },
    import::ImportWorkspace,
    inspector::{InspectorPanelVisibility, InspectorPanelsChanged, InspectorWorkspace},
    method::MethodWorkspace,
    source::SourceWorkspace,
    targets::{TargetListsChanged, TargetSelectionChanged, TargetsWorkspace},
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
        ActiveTheme, Disableable, IconName, Placement, Selectable,
        button::{Button, ButtonVariants},
        menu::{DropdownMenu, PopupMenuItem},
    },
    prelude::*,
    *,
};
use menubar::MenuGroup;
use std::{collections::HashMap, path::PathBuf, rc::Rc};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum SettingsPage {
    General,
    Configuration,
    LayoutInspector,
    Simpleperf,
    CpuCapture,
    Ai,
    About,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum CaptureCompletionAction {
    OpenCpu,
    ManualOpen,
    RawOnly,
}

impl CaptureCompletionAction {
    fn notice_key(self) -> TextKey {
        match self {
            Self::OpenCpu => TextKey::ShellCaptureOpeningNotice,
            Self::ManualOpen => TextKey::ShellCaptureCompletedNotice,
            Self::RawOnly => TextKey::ShellRawCaptureCompletedNotice,
        }
    }
}

fn capture_completion_action(
    destination: Destination,
    settings_page: SettingsPage,
    has_protobuf: bool,
    cpu_available: bool,
) -> CaptureCompletionAction {
    if !has_protobuf {
        CaptureCompletionAction::RawOnly
    } else if (destination == Destination::Capture
        || (destination == Destination::Settings && settings_page == SettingsPage::CpuCapture))
        && cpu_available
    {
        CaptureCompletionAction::OpenCpu
    } else {
        CaptureCompletionAction::ManualOpen
    }
}

/// Keep the previous route as an alias without creating another capture owner.
fn resolve_capture_route(
    destination: Destination,
    settings_page: SettingsPage,
) -> (Destination, SettingsPage) {
    if destination == Destination::Capture {
        (Destination::Settings, SettingsPage::CpuCapture)
    } else {
        (destination, settings_page)
    }
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
    const ALL: [Self; 7] = [
        Self::General,
        Self::Configuration,
        Self::LayoutInspector,
        Self::Simpleperf,
        Self::CpuCapture,
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
            Self::CpuCapture => TextKey::FoundationCapturePlanning,
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
#[derive(Clone, PartialEq, Eq)]
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
    OpenRecentInspectorArchive { path: PathBuf, generation: u64 },
    ClearRecentInspectorArchives { generation: u64 },
    OpenCapturedCpu,
    ToggleInspectorHierarchy,
    ToggleInspectorFindings,
    ToggleInspectorDetails,
    ToggleInspectorAutoScan,
    NextInspectorWindow,
    ToggleLayout(LayoutMenuToggle),
    PrepareAi,
    OpenAiWorkspace,
    OpenSettings,
    SettingsTab(SettingsPage),
}

const INSPECTOR_RECENT_LIMIT: usize = 10;

#[cfg(debug_assertions)]
fn debug_route_destination(route: &str) -> Option<Destination> {
    Some(match route {
        "home" => Destination::Home,
        "inspector" => Destination::LayoutInspector,
        "cpu" => Destination::Cpu,
        "capture" => Destination::Capture,
        "method" => Destination::MethodRecording,
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
        _ => return None,
    })
}

#[derive(Default)]
struct InspectorRecentHistory {
    rows: Vec<RecentPathEntry>,
    generation: u64,
    loading: bool,
    error: Option<String>,
}

impl InspectorRecentHistory {
    fn begin_load(&mut self) -> u64 {
        self.generation += 1;
        self.loading = true;
        self.error = None;
        self.generation
    }

    fn finish_load(
        &mut self,
        generation: u64,
        result: Result<Vec<RecentPathEntry>, String>,
    ) -> bool {
        if generation != self.generation {
            return false;
        }
        self.loading = false;
        match result {
            Ok(mut rows) => {
                rows.truncate(INSPECTOR_RECENT_LIMIT);
                self.rows = rows;
                self.error = None;
            }
            Err(error) => self.fail(error),
        }
        true
    }

    fn fail(&mut self, error: String) {
        self.loading = false;
        self.rows.clear();
        self.error = Some(error);
    }

    fn can_open(&self, path: &std::path::Path, generation: u64, busy: bool, pending: bool) -> bool {
        !busy
            && !pending
            && !self.loading
            && self.error.is_none()
            && self.generation == generation
            && self.rows.iter().any(|row| row.path == path)
    }

    fn can_clear(
        &self,
        generation: u64,
        busy: bool,
        clear_pending: bool,
        history_pending: bool,
    ) -> bool {
        generation == self.generation
            && !busy
            && !clear_pending
            && (!self.rows.is_empty() || self.error.is_some() || history_pending)
    }
}

/// The store already normalizes and deduplicates paths; match Compose's labels.
fn inspector_recent_archive_labels(rows: &[RecentPathEntry]) -> Vec<String> {
    rows.iter()
        .map(|row| {
            let basename = row.path.file_name();
            let name = basename.unwrap_or_default().to_string_lossy();
            let duplicate = rows
                .iter()
                .filter(|other| other.path.file_name() == basename)
                .take(2)
                .count()
                > 1;
            if name.trim().is_empty() || duplicate {
                row.path.to_string_lossy().into_owned()
            } else {
                name.into_owned()
            }
        })
        .collect()
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
    history: &InspectorRecentHistory,
    history_pending: bool,
    clear_pending: bool,
    language: Language,
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
        .submenu(TextKey::ShellMenuOpenRecent, true, |mut menu| {
            if history.loading {
                menu = menu.item_when(false, TextKey::HomeRecentLoading, ShellMenu::Unavailable);
            } else if let Some(error) = &history.error {
                menu = menu.item_when(
                    false,
                    format!("{}: {error}", language.text(TextKey::HomeRecentUnavailable)),
                    ShellMenu::Unavailable,
                );
            } else if history.rows.is_empty() {
                menu = menu.item_when(false, TextKey::ShellMenuNoRecent, ShellMenu::Unavailable);
            } else {
                for (row, label) in history
                    .rows
                    .iter()
                    .zip(inspector_recent_archive_labels(&history.rows))
                {
                    menu = menu.item_when(
                        history.can_open(
                            &row.path,
                            history.generation,
                            state.busy,
                            history_pending,
                        ),
                        label,
                        ShellMenu::OpenRecentInspectorArchive {
                            path: row.path.clone(),
                            generation: history.generation,
                        },
                    );
                }
            }
            menu.separator().item_when(
                history.can_clear(
                    history.generation,
                    state.busy,
                    clear_pending,
                    history_pending,
                ),
                TextKey::ShellMenuClearRecent,
                ShellMenu::ClearRecentInspectorArchives {
                    generation: history.generation,
                },
            )
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
            .check(
                TextKey::InspectorAutoScan,
                state.auto_scan,
                ShellMenu::ToggleInspectorAutoScan,
            )
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

#[derive(Default)]
struct AiRequestEpoch {
    next: u64,
    pending: Option<u64>,
}

impl AiRequestEpoch {
    fn begin(&mut self) -> Result<u64, String> {
        self.cancel();
        self.next = self
            .next
            .checked_add(1)
            .ok_or("AI request epochs are exhausted")?;
        self.pending = Some(self.next);
        Ok(self.next)
    }

    fn cancel(&mut self) {
        self.pending = None;
    }

    fn accepts(&self, epoch: u64, origin: Destination, destination: Destination) -> bool {
        self.pending == Some(epoch) && origin == destination
    }
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
    notifications: Entity<NotificationCenter>,
    /// Sampling task that feeds page errors into the notification center.
    notice_task: Option<Task<()>>,
    save_generation: u64,
    settings_save: Option<Task<()>>,
    targets: Entity<TargetsWorkspace>,
    ai_origin: Option<Destination>,
    ai_generation: u64,
    ai_request_epoch: AiRequestEpoch,
    captured: Option<aps_capture::CapturedArtifact>,
    settings_page: SettingsPage,
    settings_scroll: ScrollHandle,
    settings_ui: settings_page::SettingsUi,
    home_recent: home_page::HomeRecentState,
    home_recent_generation: u64,
    home_recent_task: Option<Task<()>>,
    inspector_recent: InspectorRecentHistory,
    history_mutations: HistoryMutationQueue,
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
            // Debug-only verification seam: keep AI credentials in memory so a
            // native check never writes to the user's login Keychain.
            #[cfg(debug_assertions)]
            let opened = std::env::var_os("APS_RUST_AI_MEMORY_SECRETS").map_or_else(
                || aps_ai::runtime::AiRuntime::open(directory.clone()),
                |_| {
                    aps_ai::runtime::AiRuntime::open_with_secret_store(
                        directory.clone(),
                        aps_ai::AiSecretStore::memory(),
                    )
                },
            );
            #[cfg(not(debug_assertions))]
            let opened = aps_ai::runtime::AiRuntime::open(directory);
            if let Ok(runtime) = opened {
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
            cx.subscribe(
                &inspector,
                |this, _, event: &crate::home_recent::RecentArtifactOpened, cx| {
                    this.record_recent_artifact(event.clone(), cx);
                },
            ),
            cx.subscribe(
                &trace,
                |this, _, event: &crate::home_recent::RecentArtifactOpened, cx| {
                    this.record_recent_artifact(event.clone(), cx);
                },
            ),
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
                let action = capture_completion_action(
                    this.destination,
                    this.settings_page,
                    this.captured.is_some(),
                    this.cpu.read(cx).can_accept_captured_profile(),
                );
                this.notice = Some(this.settings.language.text(action.notice_key()).into());
                if action == CaptureCompletionAction::OpenCpu {
                    this.open_captured(cx);
                }
                this.refresh_route_menus(cx);
                cx.notify();
            }),
        ];
        if let Some(memory) = imports.get(&Destination::Memory) {
            subscriptions.push(cx.subscribe(
                memory,
                |this, _, event: &crate::home_recent::RecentArtifactOpened, cx| {
                    this.record_recent_artifact(event.clone(), cx);
                },
            ));
        }
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
            &capture,
            window,
            cx,
            &mut subscriptions,
        );
        let targets = cx.new(|cx| TargetsWorkspace::new(settings.language.resolved(), window, cx));
        let notifications = cx.new(|_cx| NotificationCenter::new(settings.language.resolved()));
        subscriptions.push(cx.observe(&notifications, |_, _, cx| cx.notify()));
        let initial_target_lists = targets.read(cx).current_lists();
        subscriptions.push(cx.subscribe(
            &targets,
            |this, targets, event: &TargetSelectionChanged, cx| {
                let serial = event.serial.clone();
                let pid = event.pid;
                let lists = targets.read(cx).current_lists();
                if let Some(memory) = this.imports.get(&Destination::Memory) {
                    memory.update(cx, |memory, cx| {
                        memory.set_memory_capture_target(serial.clone(), pid, cx);
                        if let Some(lists) = &lists {
                            memory.apply_memory_capture_targets(
                                &lists.devices,
                                &lists.processes,
                                cx,
                            );
                        }
                    });
                }
                for destination in [
                    Destination::Startup,
                    Destination::Battery,
                    Destination::Network,
                ] {
                    if let Some(workspace) = this.imports.get(&destination) {
                        workspace.update(cx, |workspace, cx| {
                            match destination {
                                Destination::Startup => {
                                    workspace.set_startup_capture_target(serial.clone(), pid, cx)
                                }
                                Destination::Battery => {
                                    workspace.set_battery_capture_target(serial.clone(), pid, cx)
                                }
                                Destination::Network => {
                                    workspace.set_network_capture_target(serial.clone(), pid, cx)
                                }
                                _ => unreachable!(),
                            }
                            if let Some(lists) = &lists {
                                match destination {
                                    Destination::Startup => workspace
                                        .apply_startup_capture_targets(
                                            &lists.devices,
                                            &lists.processes,
                                            cx,
                                        ),
                                    Destination::Battery => workspace
                                        .apply_battery_capture_targets(
                                            &lists.devices,
                                            &lists.processes,
                                            cx,
                                        ),
                                    Destination::Network => workspace
                                        .apply_network_capture_targets(
                                            &lists.devices,
                                            &lists.processes,
                                            cx,
                                        ),
                                    _ => unreachable!(),
                                }
                            }
                        });
                    }
                }
                this.benchmark.update(cx, |benchmark, cx| {
                    benchmark.set_shared_device(serial.clone(), cx);
                });
                this.capture.update(cx, |capture, cx| {
                    capture.set_shared_target(serial.clone(), pid, cx)
                });
                this.method.update(cx, |method, cx| {
                    method.sync_shared_target(
                        serial.clone(),
                        pid,
                        lists.as_ref().map_or(&[], |lists| lists.devices.as_slice()),
                        lists
                            .as_ref()
                            .map_or(&[], |lists| lists.processes.as_slice()),
                        cx,
                    )
                });
                this.inspector.update(cx, |inspector, cx| {
                    inspector.set_target(event.serial.clone(), cx)
                });
                if this.destination == Destination::LayoutInspector {
                    this.inspector
                        .update(cx, |inspector, cx| inspector.capture_if_idle(cx));
                }
                this.trace.update(cx, |trace, cx| {
                    trace.select_capture_device(serial.clone(), cx)
                });
                if let Some(serial) = event.serial.clone() {
                    this.frame.update(cx, |frame, cx| {
                        frame.set_shared_target(Some(serial), pid, cx);
                    });
                }
                if let Some(gpu) = this.imports.get(&Destination::Gpu) {
                    gpu.update(cx, |gpu, cx| gpu.set_gpu_target(event.serial.clone(), cx));
                }
            },
        ));
        subscriptions.push(
            cx.subscribe(&targets, |this, _, event: &TargetListsChanged, cx| {
                if let Some(memory) = this.imports.get(&Destination::Memory) {
                    memory.update(cx, |memory, cx| {
                        memory.apply_memory_capture_targets(&event.devices, &event.processes, cx);
                    });
                }
                for destination in [
                    Destination::Startup,
                    Destination::Battery,
                    Destination::Network,
                ] {
                    if let Some(workspace) = this.imports.get(&destination) {
                        workspace.update(cx, |workspace, cx| match destination {
                            Destination::Startup => workspace.apply_startup_capture_targets(
                                &event.devices,
                                &event.processes,
                                cx,
                            ),
                            Destination::Battery => workspace.apply_battery_capture_targets(
                                &event.devices,
                                &event.processes,
                                cx,
                            ),
                            Destination::Network => workspace.apply_network_capture_targets(
                                &event.devices,
                                &event.processes,
                                cx,
                            ),
                            _ => unreachable!(),
                        });
                    }
                }
                let selected = this.targets.read(cx).selected_device().map(str::to_owned);
                let selected_pid = this.targets.read(cx).selected_process();
                let selected = selected.filter(|serial| {
                    event
                        .devices
                        .iter()
                        .any(|device| device.serial == *serial && device.is_online())
                });
                this.benchmark.update(cx, |benchmark, cx| {
                    benchmark.set_shared_device(selected.clone(), cx)
                });
                this.trace.update(cx, |trace, cx| {
                    trace.apply_shared_target_devices(&event.devices, cx);
                });
                this.frame.update(cx, |frame, cx| {
                    let selection = (
                        frame.selected_shared_target().0.map(str::to_owned),
                        frame.selected_shared_target().1,
                    );
                    frame.apply_shared_target_lists(
                        &event.devices,
                        &event.processes,
                        selection.0.as_deref(),
                        selection.1,
                        cx,
                    )
                });
                this.method.update(cx, |method, cx| {
                    method.sync_shared_target(
                        selected,
                        selected_pid,
                        &event.devices,
                        &event.processes,
                        cx,
                    )
                });
            }),
        );
        if let Some(lists) = initial_target_lists {
            let selected = targets.read(cx).selected_device().map(str::to_owned);
            let selected_pid = targets.read(cx).selected_process();
            let selected = selected.filter(|serial| {
                lists
                    .devices
                    .iter()
                    .any(|device| device.serial == *serial && device.is_online())
            });
            benchmark.update(cx, |benchmark, cx| {
                benchmark.set_shared_device(selected.clone(), cx)
            });
            if let Some(memory) = imports.get(&Destination::Memory) {
                memory.update(cx, |memory, cx| {
                    memory.apply_memory_capture_targets(&lists.devices, &lists.processes, cx);
                });
            }
            for destination in [
                Destination::Startup,
                Destination::Battery,
                Destination::Network,
            ] {
                if let Some(workspace) = imports.get(&destination) {
                    workspace.update(cx, |workspace, cx| match destination {
                        Destination::Startup => workspace.apply_startup_capture_targets(
                            &lists.devices,
                            &lists.processes,
                            cx,
                        ),
                        Destination::Battery => workspace.apply_battery_capture_targets(
                            &lists.devices,
                            &lists.processes,
                            cx,
                        ),
                        Destination::Network => workspace.apply_network_capture_targets(
                            &lists.devices,
                            &lists.processes,
                            cx,
                        ),
                        _ => unreachable!(),
                    });
                }
            }
            trace.update(cx, |trace, cx| {
                trace.apply_shared_target_devices(&lists.devices, cx);
            });
            frame.update(cx, |frame, cx| {
                let selection = (
                    frame.selected_shared_target().0.map(str::to_owned),
                    frame.selected_shared_target().1,
                );
                frame.apply_shared_target_lists(
                    &lists.devices,
                    &lists.processes,
                    selection.0.as_deref(),
                    selection.1,
                    cx,
                );
            });
            method.update(cx, |method, cx| {
                method.sync_shared_target(
                    selected,
                    selected_pid,
                    &lists.devices,
                    &lists.processes,
                    cx,
                )
            });
        }
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
            cpu.set_follow_tooltip(settings.simpleperf_follow_tooltip, cx);
            cpu.set_viewer_engine(settings.simpleperf_viewer_engine, cx);
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
            notifications,
            notice_task: None,
            save_generation: 0,
            settings_save: None,
            targets,
            ai_origin: None,
            ai_generation: 0,
            ai_request_epoch: AiRequestEpoch::default(),
            captured: None,
            settings_page: SettingsPage::General,
            settings_scroll: ScrollHandle::new(),
            settings_ui,
            home_recent: home_page::HomeRecentState::Loading,
            home_recent_generation: 0,
            home_recent_task: None,
            inspector_recent: InspectorRecentHistory::default(),
            history_mutations: HistoryMutationQueue::default(),
            home_import_pending: false,
            route_menu: Vec::new(),
            route_menu_dispatch: Rc::new(Vec::new()),
            route_menu_signature: String::new(),
            self_handle,
            _subscriptions: subscriptions,
        };
        studio.refresh_route_menus(cx);
        studio.refresh_home_recent(cx);
        studio.refresh_inspector_recent(cx);
        // Page errors are transient UI state, so the shell samples each workspace
        // on a bounded cadence and turns changed messages into notification cards.
        studio.notice_task = Some(cx.spawn(async move |this, cx| {
            loop {
                cx.background_executor()
                    .timer(std::time::Duration::from_millis(500))
                    .await;
                if this
                    .update(cx, |studio, cx| studio.collect_notices(cx))
                    .is_err()
                {
                    break;
                }
            }
        }));
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
                    studio.set_destination(Destination::Memory);
                }
            }
        }
        // Debug-only verification seam: starts on a route without driving the
        // native navigation, so menu/toolbar checks run headlessly.
        #[cfg(debug_assertions)]
        if let Some(route) = std::env::var_os("APS_RUST_OPEN_ROUTE") {
            let route = route.to_string_lossy().to_ascii_lowercase();
            if let Some(destination) = debug_route_destination(&route) {
                (studio.destination, studio.settings_page) =
                    resolve_capture_route(destination, studio.settings_page);
            }
        }
        #[cfg(debug_assertions)]
        if let Some(path) = std::env::var_os("APS_RUST_OPEN_METHOD") {
            studio.set_destination(Destination::MethodRecording);
            studio.method.update(cx, |method, cx| {
                method.open_debug_trace(std::path::PathBuf::from(path), cx);
            });
        }
        // Debug-only verification seam: opens a picked CPU source on launch
        // without driving the native file picker. Runs after the route seam so an
        // explicit CPU source always lands on the CPU workspace.
        #[cfg(debug_assertions)]
        if let Some(path) = std::env::var_os("APS_RUST_OPEN_CPU") {
            studio.set_destination(Destination::Cpu);
            let cpu = studio.cpu.clone();
            cpu.update(cx, |cpu, cx| {
                cpu.open_debug_source(std::path::PathBuf::from(path), cx)
            });
        }
        // Debug-only verification seam: opens Settings on the AI page without
        // driving the native navigation.
        #[cfg(debug_assertions)]
        if std::env::var_os("APS_RUST_OPEN_SETTINGS_AI").is_some() {
            studio.set_destination(Destination::Settings);
            studio.settings_page = SettingsPage::Ai;
        }
        // Debug-only verification seam: opens an exported frame-analysis.json
        // on launch without driving the native file picker.
        #[cfg(debug_assertions)]
        if let Some(path) = std::env::var_os("APS_RUST_OPEN_FRAME_JSON") {
            studio.set_destination(Destination::Frame);
            studio.frame.update(cx, |frame, cx| {
                frame.import_debug_analysis_json(std::path::PathBuf::from(path), cx)
            });
        }
        // Debug-only verification seam: opens a real Inspector archive without
        // driving the platform file picker.
        #[cfg(debug_assertions)]
        if let Some(path) = std::env::var_os("APS_RUST_OPEN_INSPECTOR") {
            studio.set_destination(Destination::LayoutInspector);
            studio.inspector.update(cx, |inspector, cx| {
                inspector.open_debug_archive(std::path::PathBuf::from(path), cx)
            });
        }
        #[cfg(debug_assertions)]
        if let Some(path) = std::env::var_os("APS_RUST_OPEN_TRACE") {
            studio.trace.update(cx, |trace, cx| {
                trace.open_trace_path(std::path::PathBuf::from(path), cx)
            });
        }
        // Debug-only verification seam: triggers one live Inspector capture on
        // launch so the layout rendering path can be checked without native
        // clicks. The value is the target device serial.
        // Debug-only verification seam: seeds the notification panel with one card
        // per severity so the popover can be checked without triggering failures.
        #[cfg(debug_assertions)]
        if std::env::var_os("APS_RUST_NOTIFICATIONS_DEMO").is_some() {
            studio.notifications.update(cx, |notifications, cx| {
                notifications.push_debug(
                    crate::notifications::NoticeLevel::Info,
                    "Home",
                    "App started; layout capture is ready.",
                );
                notifications.push_debug(
                    crate::notifications::NoticeLevel::Warning,
                    "Layout Inspector",
                    "Device target changed during capture; refresh again.",
                );
                notifications.push_debug(
                    crate::notifications::NoticeLevel::Error,
                    "Memory",
                    "Capture failed: adb exec-out screencap -p failed (exit Some(1))",
                );
                cx.notify();
            });
        }
        // Debug-only verification seam: opens the notification popover on launch.
        #[cfg(debug_assertions)]
        if std::env::var_os("APS_RUST_OPEN_NOTIFICATIONS").is_some() {
            studio.notifications.update(cx, |notifications, cx| {
                notifications.open();
                cx.notify();
            });
        }
        #[cfg(debug_assertions)]
        if let Some(serial) = std::env::var_os("APS_RUST_INSPECTOR_CAPTURE") {
            studio.set_destination(Destination::LayoutInspector);
            let serial = serial.to_string_lossy().into_owned();
            cx.spawn(async move |this, cx| {
                cx.background_executor()
                    .timer(std::time::Duration::from_millis(1500))
                    .await;
                this.update(cx, |studio, cx| {
                    studio.inspector.update(cx, |inspector, cx| {
                        inspector.set_target(Some(serial), cx);
                        inspector.refresh(cx);
                    });
                })
                .ok();
            })
            .detach();
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
            inspector.set_target(request.device_serial.clone(), cx)
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
        self.set_destination(Destination::SourceWorkspaces);
        self.source.update(cx, |source, cx| {
            source.navigate_to_symbol(symbol, resource, cx)
        });
        cx.notify();
    }

    pub(crate) fn prepare_ai(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.ai_request_epoch.cancel();
        let origin = self.destination;
        if origin == Destination::Cpu
            && let Some(query) = self.cpu.read(cx).firefox_ai_query()
        {
            let query = match query {
                Ok(query) => query,
                Err(error) => {
                    self.present_ai_request(origin, Err(error), window, cx);
                    return;
                }
            };
            let epoch = match self.ai_request_epoch.begin() {
                Ok(epoch) => epoch,
                Err(error) => {
                    self.present_ai_request(origin, Err(error), window, cx);
                    return;
                }
            };
            let binding = query.binding;
            cx.spawn_in(window, async move |this, cx| {
                let result = cx
                    .background_executor()
                    .spawn(async move { query.wait() })
                    .await;
                let _ = this.update_in(cx, |studio, window, cx| {
                    if !studio
                        .ai_request_epoch
                        .accepts(epoch, origin, studio.destination)
                    {
                        return;
                    }
                    studio.ai_request_epoch.cancel();
                    if !studio.cpu.read(cx).firefox_ai_binding_is_current(binding) {
                        return;
                    }
                    studio.present_ai_request(origin, result, window, cx);
                });
            })
            .detach();
            return;
        }
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
        self.ai_request_epoch.cancel();
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
                self.set_destination(Destination::Ai);
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
            Destination::Memory
            | Destination::Startup
            | Destination::Battery
            | Destination::Network => {
                if let Some(workspace) = self.imports.get(&self.destination) {
                    workspace.update(cx, |workspace, cx| workspace.close_primary(cx));
                }
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

    /// CPU opens capture setup only; planning and recording require explicit
    /// actions there. Other profilers retain their existing capture commands.
    fn start_capture_active_route(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        match self.destination {
            Destination::Cpu => {
                let converter = self.cpu.read(cx).selected_converter();
                self.capture.update(cx, |capture, cx| {
                    capture.set_converter_if_empty(converter, window, cx);
                });
                self.navigate(Destination::Capture, cx);
            }
            Destination::MethodRecording => {
                self.method
                    .update(cx, |method, cx| method.start_capture(cx));
            }
            Destination::Trace => {
                self.trace
                    .update(cx, |trace, cx| trace.toggle_capture_primary(cx));
            }
            Destination::Frame => {
                self.frame
                    .update(cx, |frame, cx| frame.start_capture_primary(cx));
            }
            Destination::Memory => {
                if let Some(memory) = self.imports.get(&Destination::Memory) {
                    memory.update(cx, |memory, cx| memory.capture_memory_active(cx));
                }
            }
            Destination::Startup => {
                if let Some(workspace) = self.imports.get(&Destination::Startup) {
                    workspace.update(cx, |workspace, cx| workspace.start_startup_capture(cx));
                }
            }
            Destination::Battery => {
                if let Some(workspace) = self.imports.get(&Destination::Battery) {
                    workspace.update(cx, |workspace, cx| workspace.capture_battery(window, cx));
                }
            }
            Destination::Network => {
                if let Some(workspace) = self.imports.get(&Destination::Network) {
                    workspace.update(cx, |workspace, cx| workspace.network_start_live(cx));
                }
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
        self.set_destination(Destination::Settings);
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
            if !self.cpu.read(cx).can_accept_captured_profile() {
                self.notice = Some(
                    self.settings
                        .language
                        .text(TextKey::ShellCaptureCompletedNotice)
                        .into(),
                );
                cx.notify();
                return;
            }
            self.cpu.update(cx, |cpu, cx| {
                cpu.open_artifact(artifact.path.clone(), artifact.digest, cx)
            });
            self.navigate(Destination::Cpu, cx);
        }
    }
    fn open_cpu(&mut self, _: &OpenProfile, _: &mut Window, cx: &mut Context<Self>) {
        self.set_destination(Destination::Cpu);
        self.cpu.update(cx, |cpu, cx| cpu.pick_file(cx));
        cx.notify();
    }

    fn open_inspector_archive(
        &mut self,
        _: &OpenInspectorArchive,
        _: &mut Window,
        cx: &mut Context<Self>,
    ) {
        self.set_destination(Destination::LayoutInspector);
        self.refresh_inspector_recent(cx);
        self.inspector
            .update(cx, |inspector, cx| inspector.pick(cx));
        cx.notify();
    }

    fn enter_inspector_for_action(&mut self, cx: &mut Context<Self>) {
        let entering = self.destination != Destination::LayoutInspector;
        self.set_destination(Destination::LayoutInspector);
        if entering {
            self.refresh_inspector_recent(cx);
        }
    }

    fn open_settings(&mut self, _: &OpenSettings, _: &mut Window, cx: &mut Context<Self>) {
        self.set_destination(Destination::Settings);
        cx.notify();
    }

    /// Opens the AI settings page from a profiler page action button.
    pub(crate) fn open_ai_settings(&mut self, _: &mut Window, cx: &mut Context<Self>) {
        self.settings_page = SettingsPage::Ai;
        self.set_destination(Destination::Settings);
        cx.notify();
    }

    /// Opens a persisted source candidate from a validated AI finding.
    pub(crate) fn open_source_candidate(
        &mut self,
        candidate_id: String,
        _: &mut Window,
        cx: &mut Context<Self>,
    ) {
        self.set_destination(Destination::SourceWorkspaces);
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
        self.set_destination(Destination::Ai);
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
            Destination::Settings => {
                self.push_shell_menus(
                    settings_menu_groups(self.settings_page),
                    language,
                    &mut build,
                );
                if self.settings_page == SettingsPage::CpuCapture {
                    self.push_workspace_menus(&self.capture, language, &mut build, cx);
                    self.push_shell_menus(
                        vec![MenuGroup::new(TextKey::ShellMenuFile).item_when(
                            self.captured.is_some(),
                            TextKey::ShellOpenCapturedCpu,
                            ShellMenu::OpenCapturedCpu,
                        )],
                        language,
                        &mut build,
                    );
                }
            }
            Destination::LayoutInspector => {
                let inspector = self.inspector.read(cx);
                let groups = inspector_menu_groups(
                    inspector.panel_visibility(),
                    self.settings.layout,
                    inspector.menu_state(),
                    &self.inspector_recent,
                    self.history_mutations.has_feature(RecordedFeature::Layout),
                    self.history_mutations.has_clear(RecordedFeature::Layout),
                    language,
                );
                self.push_shell_menus(groups, language, &mut build);
                // Menu signatures otherwise include labels only. Equal filenames
                // must not keep an older path/generation in the dispatch table.
                build.signature.push_str(&format!(
                    ":inspector-recent:{}",
                    self.inspector_recent.generation
                ));
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
            ShellMenu::StartCaptureActiveRoute => self.start_capture_active_route(window, cx),
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
            ShellMenu::OpenRecentInspectorArchive { path, generation } => {
                if self.destination == Destination::LayoutInspector
                    && self.inspector_recent.can_open(
                        &path,
                        generation,
                        self.inspector.read(cx).menu_state().busy,
                        self.history_mutations.has_feature(RecordedFeature::Layout),
                    )
                {
                    self.inspector
                        .update(cx, |inspector, cx| inspector.open_recent_archive(path, cx));
                }
            }
            ShellMenu::ClearRecentInspectorArchives { generation } => {
                self.clear_inspector_recent(generation, cx)
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
            ShellMenu::ToggleInspectorAutoScan => {
                self.inspector
                    .update(cx, |inspector, cx| inspector.toggle_auto_scan(cx));
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
                self.select_settings_page(page);
                self.set_destination(Destination::Settings);
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
            cpu.set_follow_tooltip(self.settings.simpleperf_follow_tooltip, cx);
            cpu.set_viewer_engine(self.settings.simpleperf_viewer_engine, cx);
        });
        self.capture.update(cx, |capture, cx| {
            capture.set_defaults(
                self.settings.capture_duration_seconds,
                self.settings.capture_frequency_hz,
                window,
                cx,
            )
        });
        let language = self.settings.language.resolved();
        self.notifications.update(cx, |notifications, cx| {
            notifications.set_language(language, cx)
        });
        self.cpu
            .update(cx, |cpu, cx| cpu.set_language(language, window, cx));
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
    /// Samples every page workspace and forwards changed user-visible messages to
    /// the notification center. Workspaces only expose current state through
    /// NoticeSource; they do not know about the title-bar surface.
    fn collect_notices(&mut self, cx: &mut Context<Self>) {
        let language = self.settings.language.resolved();
        let mut batches: Vec<(String, Vec<NoticeSnapshot>)> = Vec::new();
        batches.push((
            Destination::Cpu.label(language).to_owned(),
            self.cpu.read(cx).notice_snapshot(),
        ));
        batches.push((
            Destination::Ai.label(language).to_owned(),
            self.ai.read(cx).notice_snapshot(),
        ));
        batches.push((
            Destination::Frame.label(language).to_owned(),
            self.frame.read(cx).notice_snapshot(),
        ));
        batches.push((
            Destination::Benchmark.label(language).to_owned(),
            self.benchmark.read(cx).notice_snapshot(),
        ));
        batches.push((
            Destination::SourceWorkspaces.label(language).to_owned(),
            self.source.read(cx).notice_snapshot(),
        ));
        batches.push((
            Destination::Capture.label(language).to_owned(),
            self.capture.read(cx).notice_snapshot(),
        ));
        batches.push((
            Destination::MethodRecording.label(language).to_owned(),
            self.method.read(cx).notice_snapshot(),
        ));
        batches.push((
            Destination::Trace.label(language).to_owned(),
            self.trace.read(cx).notice_snapshot(),
        ));
        batches.push((
            Destination::LayoutInspector.label(language).to_owned(),
            self.inspector.read(cx).notice_snapshot(),
        ));
        for (destination, workspace) in &self.imports {
            batches.push((
                destination.label(language).to_owned(),
                workspace.read(cx).notice_snapshot(),
            ));
        }
        let mut app = self.targets.read(cx).notice_snapshot();
        if let Some(notice) = &self.notice {
            app.push(NoticeSnapshot::error(notice.clone()));
        }
        if let Some(error) = &self.inspector_recent.error {
            app.push(NoticeSnapshot::error(format!(
                "{}: {error}",
                language.text(TextKey::HomeRecentUnavailable)
            )));
        }
        batches.push((
            language
                .text(TextKey::ShellNotificationSourceApp)
                .to_owned(),
            app,
        ));
        self.notifications.update(cx, |notifications, cx| {
            for (source, notices) in batches {
                notifications.sync_source(&source, notices);
            }
            cx.notify();
        });
    }

    fn set_destination(&mut self, destination: Destination) {
        self.ai_request_epoch.cancel();
        self.destination = destination;
    }

    fn navigate(&mut self, destination: Destination, cx: &mut Context<Self>) {
        let (destination, settings_page) = resolve_capture_route(destination, self.settings_page);
        if destination != Destination::LayoutInspector {
            self.inspector
                .update(cx, |inspector, cx| inspector.stop_live_capture(cx));
        }
        self.select_settings_page(settings_page);
        let entering_home = destination == Destination::Home && self.destination != destination;
        let route = destination;
        self.set_destination(destination);
        self.refresh_route_menus(cx);
        if destination == Destination::LayoutInspector {
            self.refresh_inspector_recent(cx);
            // Entering the page with a connected device fills an empty page once,
            // so "device linked" is followed by the foreground app's layout
            // instead of empty panes; the Refresh button remains the manual path.
            self.inspector
                .update(cx, |inspector, cx| inspector.capture_if_idle(cx));
        }
        if self.entered_route(route, cx) {
            cx.notify();
            return;
        }
        if entering_home {
            self.refresh_home_recent(cx);
        }
        cx.notify();
    }

    fn select_settings_page(&mut self, page: SettingsPage) {
        if page == SettingsPage::LayoutInspector {
            self.settings_scroll.set_offset(point(px(0.), px(0.)));
        }
        self.settings_page = page;
    }

    fn entered_route(&mut self, destination: Destination, cx: &mut Context<Self>) -> bool {
        match destination {
            Destination::Trace => true,
            Destination::Frame => true,
            _ => false,
        }
    }

    pub(crate) fn refresh_home_recent(&mut self, cx: &mut Context<Self>) {
        self.home_recent_generation += 1;
        let generation = self.home_recent_generation;
        self.home_recent = home_page::HomeRecentState::Loading;
        self.home_recent_task = None;
        if self.history_mutations.is_pending() {
            cx.notify();
            return;
        }
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
                    Ok::<_, String>(crate::home_recent::load_snapshot(
                        &root.join(aps_storage::DATABASE_FILE_NAME),
                        &root.join("cpu-artifacts"),
                        &root.join("recent-artifacts"),
                        crate::home_recent::MAX_HOME_RECENT_ITEMS,
                    ))
                })
                .await;
            this.update(cx, |this, cx| {
                if generation != this.home_recent_generation {
                    return;
                }
                this.home_recent = match result {
                    Ok(snapshot) => home_page::HomeRecentState::Available(snapshot),
                    Err(error) => home_page::HomeRecentState::Unavailable(error),
                };
                cx.notify();
            })
            .ok();
        }));
        cx.notify();
    }

    fn record_recent_artifact(
        &mut self,
        event: crate::home_recent::RecentArtifactOpened,
        cx: &mut Context<Self>,
    ) {
        let feature = match RecordedFeature::try_from(event.feature) {
            Ok(feature) => feature,
            Err(error) => {
                self.notice = Some(error);
                cx.notify();
                return;
            }
        };
        self.enqueue_history_mutation(
            HistoryMutation::Record(
                feature,
                RecentPathEntry {
                    path: event.path,
                    package_name: None,
                    captured_at_unix_ms: None,
                },
            ),
            cx,
        );
    }

    fn recent_path_store(&self) -> Result<RecentPathStore, String> {
        self.settings_path
            .as_ref()
            .and_then(|path| path.parent())
            .map(|root| RecentPathStore::new(root.join("recent-artifacts")))
            .ok_or_else(|| "Rust application data location is unavailable".into())
    }

    fn refresh_inspector_recent(&mut self, cx: &mut Context<Self>) {
        if self.history_mutations.has_feature(RecordedFeature::Layout) {
            return;
        }
        let generation = self.inspector_recent.begin_load();
        let store = self.recent_path_store();
        let task = cx
            .background_executor()
            .spawn(async move { store?.load(RecordedFeature::Layout, INSPECTOR_RECENT_LIMIT) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.inspector_recent.finish_load(generation, result) {
                    this.refresh_route_menus(cx);
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
        self.refresh_route_menus(cx);
        cx.notify();
    }

    fn clear_inspector_recent(&mut self, generation: u64, cx: &mut Context<Self>) {
        if self.destination != Destination::LayoutInspector
            || !self.inspector_recent.can_clear(
                generation,
                self.inspector.read(cx).menu_state().busy,
                self.history_mutations.has_clear(RecordedFeature::Layout),
                self.history_mutations.has_feature(RecordedFeature::Layout),
            )
        {
            return;
        }
        self.enqueue_history_mutation(HistoryMutation::Clear(RecordedFeature::Layout), cx);
    }

    fn enqueue_history_mutation(&mut self, mutation: HistoryMutation, cx: &mut Context<Self>) {
        if mutation.feature() == RecordedFeature::Layout {
            self.inspector_recent.begin_load();
        }
        self.history_mutations.enqueue(mutation);
        // Reject Home snapshots begun before the mutation. The drain below
        // refreshes it only after all already-accepted shell writes complete.
        self.refresh_home_recent(cx);
        self.refresh_route_menus(cx);
        self.run_next_history_mutation(cx);
    }

    fn run_next_history_mutation(&mut self, cx: &mut Context<Self>) {
        let Some(operation) = self.history_mutations.start_next() else {
            return;
        };
        let feature = operation.mutation.feature();
        let sequence = operation.sequence;
        let store = self.recent_path_store();
        let task = cx
            .background_executor()
            .spawn(async move { operation.mutation.apply(&store?) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if !this.history_mutations.complete(sequence) {
                    return;
                }
                if feature == RecordedFeature::Layout {
                    if !this.history_mutations.has_feature(feature) {
                        match result {
                            Ok(()) => this.refresh_inspector_recent(cx),
                            Err(error) => this.inspector_recent.fail(error),
                        }
                    }
                } else if let Err(error) = result {
                    this.notice = Some(error);
                }
                if this.history_mutations.is_pending() {
                    this.run_next_history_mutation(cx);
                } else {
                    this.refresh_home_recent(cx);
                }
                this.refresh_route_menus(cx);
                cx.notify();
            })
            .ok();
        })
        .detach();
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
        item: crate::home_recent::HomeOverviewItem,
        cx: &mut Context<Self>,
    ) {
        use crate::home_recent::HomeRecentTarget;
        match item.target {
            HomeRecentTarget::Cpu(cpu) => {
                self.cpu
                    .update(cx, |workspace, cx| workspace.open_home_recent(cpu, cx));
                self.navigate(Destination::Cpu, cx);
            }
            HomeRecentTarget::Layout(path) => {
                self.inspector
                    .update(cx, |workspace, cx| workspace.open_recent_archive(path, cx));
                self.navigate(Destination::LayoutInspector, cx);
            }
            HomeRecentTarget::Trace(path) => {
                self.trace
                    .update(cx, |workspace, cx| workspace.open_trace_path(path, cx));
                self.navigate(Destination::Trace, cx);
            }
            HomeRecentTarget::Memory(path) => {
                if self
                    .imports
                    .get(&Destination::Memory)
                    .is_some_and(|workspace| {
                        workspace.update(cx, |workspace, cx| workspace.open_home_artifact(path, cx))
                    })
                {
                    self.navigate(Destination::Memory, cx);
                }
            }
        }
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

/// Bell button at the trailing edge of the title bar. The badge counts unseen
/// cards; clicking toggles the popover owned by the notification center.
fn titlebar_notification_button(
    notifications: &Entity<NotificationCenter>,
    unread: usize,
    open: bool,
    colors: &crate::studio::StudioColors,
) -> AnyElement {
    let entity = notifications.clone();
    let badge = (unread > 0).then(|| {
        if unread > 9 {
            "9+".to_owned()
        } else {
            unread.to_string()
        }
    });
    let hover = colors.section_background;
    let mut button = div()
        .id("titlebar-notifications")
        .relative()
        .flex_shrink_0()
        .size(px(26.))
        .flex()
        .items_center()
        .justify_center()
        .rounded(px(5.))
        .cursor_pointer()
        .text_color(colors.text)
        .text_size(px(16.))
        .when(open, |this| this.bg(hover))
        .hover(move |style| style.bg(hover))
        .on_mouse_down(MouseButton::Left, |_, _, cx| cx.stop_propagation())
        .on_click(move |_, _, cx| {
            entity.update(cx, |notifications, cx| {
                notifications.toggle();
                cx.notify();
            });
        })
        .child(IconName::Bell);
    if let Some(badge) = badge {
        button = button.child(
            div()
                .absolute()
                .top(px(1.))
                .right(px(0.))
                .min_w(px(14.))
                .h(px(14.))
                .px(px(3.))
                .flex()
                .items_center()
                .justify_center()
                .rounded(px(7.))
                .bg(colors.error)
                .text_size(px(9.))
                .text_color(rgb(0xffffff))
                .child(badge),
        );
    }
    button.into_any_element()
}

impl Render for Studio {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let language = self.settings.language;
        let selected = self.destination;
        let dark = cx.theme().is_dark();
        // Shell chrome stays on the title-bar surface; workspace cards paint the
        // neutral panel/section surfaces from StudioColors.
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
            Destination::Capture => self.settings_page(cx),
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
        // The macOS traffic lights own a 78 px leading inset while the window gap
        // is 16 px, so a centred page label compensates by the 62 px difference on
        // its trailing edge to sit on the window's centre instead of the content
        // box's centre.
        const TITLEBAR_LEADING_INSET: f32 = 78.;
        const TITLEBAR_TRAILING_INSET: f32 = 16.;
        // Narrower windows cannot fit the fixed 200 px target selectors on both
        // sides of a centred label, so the strip keeps the simple leading-name
        // layout there instead of overlapping.
        const PAGE_TITLE_MIN_WIDTH: f32 = 1140.;
        let show_page_title = f32::from(window.viewport_size().width) >= PAGE_TITLE_MIN_WIDTH;
        let app_name = |flex: bool| {
            let zone = div().min_w_0().flex().items_center();
            let zone = if flex { zone.flex_1() } else { zone };
            zone.child(
                div()
                    .text_color(primary_text)
                    .text_size(px(typography::BODY))
                    .whitespace_nowrap()
                    .child(crate::studio::selectable(
                        language.text(TextKey::FoundationAndroidPerformanceStudio),
                    )),
            )
        };
        let notifications_open = self.notifications.read(cx).is_open();
        let unread_notifications = self.notifications.read(cx).unread_count();
        let notification_button = || {
            titlebar_notification_button(
                &self.notifications,
                unread_notifications,
                notifications_open,
                &studio_colors,
            )
        };
        let titlebar_state = window.use_state(cx, |_, _| TitlebarDragState { should_move: false });
        let mut titlebar = div()
            .id("studio-titlebar")
            .h(px(36.))
            .w_full()
            .flex()
            .items_center()
            .gap(px(8.))
            .pl(px(TITLEBAR_LEADING_INSET))
            .pr(px(TITLEBAR_TRAILING_INSET))
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
            .on_double_click(|_, window, _| window.titlebar_double_click());
        if show_page_title {
            // Compose retitles its native window per destination; the Rust window
            // title is hidden, so the app-drawn strip carries the active page name
            // in the middle while the product name and targets keep their edges.
            titlebar = titlebar
                .child(app_name(true))
                .child(
                    div()
                        .flex_shrink_0()
                        .pr(px(TITLEBAR_LEADING_INSET - TITLEBAR_TRAILING_INSET))
                        .text_color(primary_text)
                        .text_size(px(typography::BODY))
                        .font_weight(FontWeight::MEDIUM)
                        .whitespace_nowrap()
                        .child(selected.label(language)),
                )
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .flex()
                        .items_center()
                        .justify_end()
                        // The AI Analysis window has its own chat surface and no
                        // device target; Compose's conversation window does not
                        // show one either.
                        .when(selected != Destination::Ai, |this| {
                            this.child(self.targets.clone())
                        })
                        .child(notification_button()),
                );
        } else {
            titlebar = titlebar
                .child(app_name(false))
                .child(div().flex_1())
                .when(selected != Destination::Ai, |this| {
                    this.child(self.targets.clone())
                })
                .child(notification_button());
        }
        // Popover surface: a full-window transparent backdrop closes the panel,
        // and the notification center renders the cards above it.
        let notification_backdrop = div()
            .id("notification-backdrop")
            .absolute()
            .inset_0()
            .bg(rgba(0x00000026))
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(|this, _, _, cx| {
                    this.notifications.update(cx, |notifications, cx| {
                        notifications.close();
                        cx.notify();
                    });
                    cx.notify();
                }),
            );
        let notification_panel = div()
            .id("notification-popover")
            .absolute()
            .top(px(40.))
            .right(px(10.))
            .w(px(440.))
            // Block pointer events from reaching the closing backdrop underneath
            // so selecting or copying card text never dismisses the panel.
            .occlude()
            .child(self.notifications.clone());
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
            .on_key_down(cx.listener(|this, event: &KeyDownEvent, _, cx| {
                if event.keystroke.key.eq_ignore_ascii_case("escape")
                    && this.notifications.read(cx).is_open()
                {
                    this.notifications.update(cx, |notifications, cx| {
                        notifications.close();
                        cx.notify();
                    });
                    cx.notify();
                    cx.stop_propagation();
                }
            }))
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
            .on_action(
                cx.listener(|this, _: &StartCaptureActiveRoute, window, cx| {
                    this.start_capture_active_route(window, cx);
                }),
            )
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
                this.enter_inspector_for_action(cx);
                this.inspector
                    .update(cx, |inspector, cx| inspector.cycle_window(cx));
                cx.notify();
            }))
            .on_action(cx.listener(|this, _: &ToggleInspectorHierarchy, _, cx| {
                this.enter_inspector_for_action(cx);
                this.inspector
                    .update(cx, |inspector, cx| inspector.toggle_hierarchy(cx));
                cx.notify();
            }))
            .on_action(cx.listener(|this, _: &ToggleInspectorFindings, _, cx| {
                this.enter_inspector_for_action(cx);
                this.inspector
                    .update(cx, |inspector, cx| inspector.toggle_findings(cx));
                cx.notify();
            }))
            .on_action(cx.listener(|this, _: &ToggleInspectorDetails, _, cx| {
                this.enter_inspector_for_action(cx);
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
                                            this.set_destination(Destination::Ai);
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
                                                this.set_destination(Destination::Settings);
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
                                    && selected != Destination::MethodRecording
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
                                        | Destination::Settings
                                ) {
                                    workspace.overflow_hidden()
                                } else {
                                    workspace.overflow_y_scroll()
                                }
                            }),
                    ),
            )
            .when(notifications_open, |this| {
                this.child(notification_backdrop).child(notification_panel)
            })
    }
}

#[cfg(test)]
mod menubar_tests {
    use super::*;

    #[::core::prelude::v1::test]
    fn firefox_ai_request_epoch_rejects_older_requests_and_navigation_round_trips() {
        let mut state = AiRequestEpoch::default();
        let first = state.begin().unwrap();
        assert!(state.accepts(first, Destination::Cpu, Destination::Cpu));
        assert!(!state.accepts(first, Destination::Cpu, Destination::Ai));
        let newer = state.begin().unwrap();
        assert!(!state.accepts(first, Destination::Cpu, Destination::Cpu));
        assert!(state.accepts(newer, Destination::Cpu, Destination::Cpu));
        state.cancel();
        assert!(!state.accepts(newer, Destination::Cpu, Destination::Cpu));
        let after_navigation = state.begin().unwrap();
        assert!(after_navigation > newer);
        assert!(!state.accepts(newer, Destination::Cpu, Destination::Cpu));
        assert!(state.accepts(after_navigation, Destination::Cpu, Destination::Cpu));
    }

    #[::core::prelude::v1::test]
    fn firefox_ai_request_epoch_exhaustion_cancels_pending_work() {
        let mut state = AiRequestEpoch {
            next: u64::MAX,
            pending: Some(1),
        };
        assert!(state.begin().is_err());
        assert!(!state.accepts(1, Destination::Cpu, Destination::Cpu));
    }

    #[::core::prelude::v1::test]
    fn firefox_ai_all_shell_navigation_and_presented_requests_cancel_pending_queries() {
        let source = include_str!("shell.rs");
        let implementation = source.split("mod menubar_tests {").next().unwrap();
        assert_eq!(implementation.matches(".destination = ").count(), 1);
        let navigation = implementation
            .split("fn set_destination(")
            .nth(1)
            .unwrap()
            .split("fn navigate(")
            .next()
            .unwrap();
        assert!(navigation.contains("self.ai_request_epoch.cancel()"));
        let presentation = implementation
            .split("pub(crate) fn present_ai_request(")
            .nth(1)
            .unwrap()
            .split("/// Opens the bundled user")
            .next()
            .unwrap();
        assert!(presentation.contains("self.ai_request_epoch.cancel()"));
    }

    #[cfg(debug_assertions)]
    #[::core::prelude::v1::test]
    fn debug_capture_route_preserves_existing_route_mapping() {
        assert_eq!(
            debug_route_destination("capture"),
            Some(Destination::Capture)
        );
        assert_eq!(debug_route_destination("cpu"), Some(Destination::Cpu));
        assert_eq!(
            debug_route_destination("inspector"),
            Some(Destination::LayoutInspector)
        );
        assert_eq!(
            debug_route_destination("settings"),
            Some(Destination::Settings)
        );
        assert_eq!(debug_route_destination("unknown"), None);
        assert_eq!(
            resolve_capture_route(
                debug_route_destination("capture").unwrap(),
                SettingsPage::General
            ),
            (Destination::Settings, SettingsPage::CpuCapture)
        );
    }

    #[::core::prelude::v1::test]
    fn capture_route_resolves_to_settings_without_changing_other_destinations() {
        for page in SettingsPage::ALL {
            for destination in Destination::ALL {
                let expected = if destination == Destination::Capture {
                    (Destination::Settings, SettingsPage::CpuCapture)
                } else {
                    (destination, page)
                };
                assert_eq!(resolve_capture_route(destination, page), expected);
            }
        }
    }

    #[::core::prelude::v1::test]
    fn capture_settings_navigation_does_not_plan_or_record() {
        let source = include_str!("shell.rs");
        let navigation = source
            .split("fn navigate(")
            .nth(1)
            .unwrap()
            .split("fn entered_route(")
            .next()
            .unwrap();
        assert!(navigation.contains("resolve_capture_route(destination, self.settings_page)"));
        for forbidden in [
            "capture.plan(",
            "capture.confirm(",
            "CaptureWorkspace::new(",
        ] {
            assert!(!navigation.contains(forbidden));
        }
        let menus = source
            .split("fn route_menu_build(")
            .nth(1)
            .unwrap()
            .split("fn push_workspace_menus")
            .next()
            .unwrap();
        let settings = menus
            .split("Destination::Settings =>")
            .nth(1)
            .unwrap()
            .split("Destination::LayoutInspector =>")
            .next()
            .unwrap();
        assert!(settings.contains("if self.settings_page == SettingsPage::CpuCapture"));
        assert!(settings.contains("self.push_workspace_menus(&self.capture"));
        assert!(settings.contains("ShellMenu::OpenCapturedCpu"));
    }

    #[::core::prelude::v1::test]
    fn cpu_capture_action_routes_to_setup_without_planning_or_recording() {
        let source = include_str!("shell.rs");
        let routing = source
            .split("fn start_capture_active_route(")
            .nth(1)
            .unwrap();
        let cpu_arm = routing
            .split("Destination::Cpu =>")
            .nth(1)
            .unwrap()
            .split("Destination::MethodRecording =>")
            .next()
            .unwrap();
        assert!(cpu_arm.contains("self.cpu.read(cx).selected_converter()"));
        assert!(cpu_arm.contains("capture.set_converter_if_empty(converter, window, cx)"));
        assert!(cpu_arm.contains("self.navigate(Destination::Capture, cx)"));
        assert!(
            cpu_arm.find("set_converter_if_empty").unwrap()
                < cpu_arm.find("self.navigate(").unwrap()
        );
        for forbidden in ["prepare(", "confirm(", "execute(", "resolve_simpleperf("] {
            assert!(!cpu_arm.contains(forbidden));
        }
    }

    #[::core::prelude::v1::test]
    fn capture_completion_only_auto_opens_on_the_current_capture_route_with_idle_cpu() {
        for destination in Destination::ALL {
            for page in SettingsPage::ALL {
                for cpu_available in [false, true] {
                    let action = capture_completion_action(destination, page, true, cpu_available);
                    let expected = if cpu_available
                        && (destination == Destination::Capture
                            || (destination == Destination::Settings
                                && page == SettingsPage::CpuCapture))
                    {
                        CaptureCompletionAction::OpenCpu
                    } else {
                        CaptureCompletionAction::ManualOpen
                    };
                    assert_eq!(
                        action, expected,
                        "{destination:?}, {page:?}, idle={cpu_available}"
                    );
                }
            }
        }
    }

    #[::core::prelude::v1::test]
    fn raw_capture_completion_never_auto_opens_even_when_cpu_is_available() {
        for destination in Destination::ALL {
            for page in SettingsPage::ALL {
                for cpu_available in [false, true] {
                    assert_eq!(
                        capture_completion_action(destination, page, false, cpu_available),
                        CaptureCompletionAction::RawOnly,
                    );
                }
            }
        }
    }

    #[::core::prelude::v1::test]
    fn capture_completion_has_distinct_truthful_opening_manual_and_raw_notices() {
        assert_eq!(
            CaptureCompletionAction::OpenCpu.notice_key(),
            TextKey::ShellCaptureOpeningNotice,
        );
        assert_eq!(
            CaptureCompletionAction::ManualOpen.notice_key(),
            TextKey::ShellCaptureCompletedNotice,
        );
        assert_eq!(
            CaptureCompletionAction::RawOnly.notice_key(),
            TextKey::ShellRawCaptureCompletedNotice,
        );
        for language in [Language::English, Language::SimplifiedChinese] {
            let opening = language.text(CaptureCompletionAction::OpenCpu.notice_key());
            let manual = language.text(CaptureCompletionAction::ManualOpen.notice_key());
            let raw = language.text(CaptureCompletionAction::RawOnly.notice_key());
            assert!(!opening.is_empty() && !manual.is_empty() && !raw.is_empty());
            assert_ne!(opening, manual);
            assert_ne!(opening, raw);
            assert_ne!(manual, raw);
        }
    }

    fn recent_entry(path: impl Into<PathBuf>) -> RecentPathEntry {
        RecentPathEntry {
            path: path.into(),
            package_name: None,
            captured_at_unix_ms: None,
        }
    }

    fn inspector_history_menu(
        history: &InspectorRecentHistory,
        busy: bool,
        pending: bool,
        clear_pending: bool,
        language: Language,
    ) -> Vec<menubar::StripRow> {
        let mut groups = inspector_menu_groups(
            InspectorPanelVisibility {
                hierarchy: true,
                findings: true,
                details: true,
            },
            LayoutPreferences::default(),
            crate::inspector::InspectorMenuState {
                busy,
                has_selection: false,
                has_archive: false,
                auto_scan: false,
            },
            history,
            pending,
            clear_pending,
            language,
        );
        let mut build = menubar::RouteMenuBuild::default();
        build.push(groups.remove(0), language, |_, _, _| {});
        build.strip.remove(0).rows
    }

    #[::core::prelude::v1::test]
    fn inspector_recent_archive_labels_match_compose_duplicate_unique_and_blank_names() {
        let rows = [
            "/first/same.zip",
            "/second/same.zip",
            "/first/unique.zip",
            "/first/  ",
            "/",
            "/third/Same.zip",
        ]
        .map(recent_entry);
        assert_eq!(
            inspector_recent_archive_labels(&rows),
            vec![
                "/first/same.zip",
                "/second/same.zip",
                "unique.zip",
                "/first/  ",
                "/",
                "Same.zip",
            ]
        );
        assert!(inspector_recent_archive_labels(&[]).is_empty());
    }

    #[::core::prelude::v1::test]
    fn inspector_recent_menu_disambiguates_duplicate_basenames_and_keeps_unique_names_short() {
        let root = aps_platform::TempWorkspace::create(&std::env::temp_dir()).unwrap();
        let first = root.path().join("first/same.zip");
        let second = root.path().join("second/same.zip");
        let unique = root.path().join("unique.zip");
        let store = RecentPathStore::new(root.path().join("recent-artifacts"));
        for path in [&first, &second, &unique] {
            std::fs::create_dir_all(path.parent().unwrap()).unwrap();
            std::fs::write(path, b"archive bytes").unwrap();
            store
                .record(RecordedFeature::Layout, recent_entry(path.clone()))
                .unwrap();
        }
        let mut history = InspectorRecentHistory::default();
        let generation = history.begin_load();
        assert!(history.finish_load(generation, store.load(RecordedFeature::Layout, 10)));
        for language in [Language::English, Language::SimplifiedChinese] {
            let menu = inspector_history_menu(&history, false, false, false, language);
            let entries = menu.iter()
                .skip_while(|row| !matches!(row, menubar::StripRow::Header(label) if label.as_ref() == language.text(TextKey::ShellMenuOpenRecent)))
                .skip(1)
                .take_while(|row| !matches!(row, menubar::StripRow::Separator))
                .filter_map(|row| match row {
                    menubar::StripRow::Item { label, enabled, .. } => Some((label.to_string(), *enabled)),
                    _ => None,
                }).collect::<Vec<_>>();
            assert_eq!(
                entries,
                vec![
                    ("unique.zip".into(), true),
                    (second.to_string_lossy().into_owned(), true),
                    (first.to_string_lossy().into_owned(), true),
                ]
            );
        }
        assert_eq!(
            history.rows.iter().map(|row| &row.path).collect::<Vec<_>>(),
            vec![&unique, &second, &first]
        );
    }

    #[::core::prelude::v1::test]
    fn inspector_recent_initial_load_is_bounded_and_busy_state_rechecks_commands() {
        let root = aps_platform::TempWorkspace::create(&std::env::temp_dir()).unwrap();
        let store = RecentPathStore::new(root.path().join("recent-artifacts"));
        for index in 0..13 {
            let path = root.path().join(format!("saved-{index:02}.apinspect"));
            std::fs::write(&path, b"archive bytes").unwrap();
            store
                .record(RecordedFeature::Layout, recent_entry(path))
                .unwrap();
        }
        let mut history = InspectorRecentHistory::default();
        let generation = history.begin_load();
        let loading = inspector_history_menu(&history, false, false, false, Language::English);
        assert!(loading.iter().any(|row| matches!(row, menubar::StripRow::Item { label, enabled: false, .. } if label.as_ref() == Language::English.text(TextKey::HomeRecentLoading))));
        assert!(history.finish_load(
            generation,
            store.load(RecordedFeature::Layout, INSPECTOR_RECENT_LIMIT)
        ));
        assert_eq!(history.rows.len(), 10);
        assert_eq!(
            history.rows[0].path.file_name().unwrap(),
            "saved-12.apinspect"
        );
        let path = history.rows[0].path.clone();
        assert!(history.can_open(&path, generation, false, false));
        assert!(!history.can_open(&path, generation, true, false));
        assert!(!history.can_open(&path, generation, false, true));
        assert!(!history.can_open(
            &root.path().join("absent.apinspect"),
            generation,
            false,
            false
        ));
        assert!(history.can_clear(generation, false, false, false));
        assert!(!history.can_clear(generation, true, false, false));
        assert!(!history.can_clear(generation, false, true, true));
        for busy in [false, true] {
            let rows = inspector_history_menu(&history, busy, false, false, Language::English);
            let archives = rows
                .iter()
                .filter_map(|row| match row {
                    menubar::StripRow::Item { label, enabled, .. }
                        if label.starts_with("saved-") =>
                    {
                        Some(*enabled)
                    }
                    _ => None,
                })
                .collect::<Vec<_>>();
            assert_eq!(archives, vec![!busy; 10]);
            assert!(rows.iter().any(|row| matches!(row, menubar::StripRow::Item { label, enabled, .. } if label.as_ref() == Language::English.text(TextKey::ShellMenuClearRecent) && *enabled == !busy)));
        }
        let next = history.begin_load();
        assert!(!history.can_open(&path, generation, false, false));
        assert!(!history.can_clear(generation, false, false, false));
        assert!(history.finish_load(next, store.load(RecordedFeature::Layout, 10)));
        assert!(history.can_open(&path, next, false, false));
    }

    #[::core::prelude::v1::test]
    fn inspector_recent_delayed_load_and_older_record_cannot_undo_clear() {
        let root = aps_platform::TempWorkspace::create(&std::env::temp_dir()).unwrap();
        let path = root.path().join("kept.apinspect");
        std::fs::write(&path, b"immutable archive").unwrap();
        let store = RecentPathStore::new(root.path().join("recent-artifacts"));
        store
            .record(RecordedFeature::Layout, recent_entry(path.clone()))
            .unwrap();
        let mut history = InspectorRecentHistory::default();
        let initial = history.begin_load();
        assert!(history.finish_load(initial, store.load(RecordedFeature::Layout, 10)));
        let delayed_generation = history.begin_load();
        let delayed_result = store.load(RecordedFeature::Layout, 10);
        let mut queue = HistoryMutationQueue::default();
        history.begin_load();
        queue.enqueue(HistoryMutation::Record(
            RecordedFeature::Layout,
            recent_entry(path.clone()),
        ));
        let older = queue.start_next().unwrap();
        assert!(history.can_clear(
            history.generation,
            false,
            queue.has_clear(RecordedFeature::Layout),
            queue.has_feature(RecordedFeature::Layout)
        ));
        history.begin_load();
        queue.enqueue(HistoryMutation::Clear(RecordedFeature::Layout));
        assert!(queue.start_next().is_none());
        older.mutation.apply(&store).unwrap();
        assert!(queue.complete(older.sequence));
        let clear = queue.start_next().unwrap();
        clear.mutation.apply(&store).unwrap();
        assert!(queue.complete(clear.sequence));
        let current = history.begin_load();
        assert!(history.finish_load(current, store.load(RecordedFeature::Layout, 10)));
        assert!(!history.finish_load(delayed_generation, delayed_result));
        assert!(!history.finish_load(initial, Err("late failure".into())));
        assert!(history.rows.is_empty());
        assert!(history.error.is_none());
        assert!(!history.loading);
        assert!(!history.can_open(&path, initial, false, false));
        assert_eq!(std::fs::read(&path).unwrap(), b"immutable archive");
        let reopened = RecentPathStore::new(root.path().join("recent-artifacts"));
        assert!(
            reopened
                .load(RecordedFeature::Layout, 10)
                .unwrap()
                .is_empty()
        );
    }

    #[::core::prelude::v1::test]
    fn inspector_recent_errors_are_visible_in_both_languages_and_clear_recovers() {
        let root = aps_platform::TempWorkspace::create(&std::env::temp_dir()).unwrap();
        let history_root = root.path().join("recent-artifacts");
        std::fs::create_dir(&history_root).unwrap();
        let index = history_root.join("home-recent-layout-v1.json");
        std::fs::write(&index, b"invalid index").unwrap();
        let store = RecentPathStore::new(&history_root);
        let mut history = InspectorRecentHistory::default();
        let generation = history.begin_load();
        assert!(history.finish_load(generation, store.load(RecordedFeature::Layout, 10)));
        assert!(history.error.is_some());
        assert!(history.can_clear(generation, false, false, false));
        for language in [Language::English, Language::SimplifiedChinese] {
            let rows = inspector_history_menu(&history, false, false, false, language);
            assert!(rows.iter().any(|row| matches!(row, menubar::StripRow::Item { label, enabled: false, .. } if label.starts_with(language.text(TextKey::HomeRecentUnavailable)))));
            assert!(rows.iter().any(|row| matches!(row, menubar::StripRow::Item { label, enabled: true, .. } if label.as_ref() == language.text(TextKey::ShellMenuClearRecent))));
        }
        let cleared = history.begin_load();
        HistoryMutation::Clear(RecordedFeature::Layout)
            .apply(&store)
            .unwrap();
        assert!(history.finish_load(cleared, store.load(RecordedFeature::Layout, 10)));
        assert!(history.error.is_none());
        assert!(history.rows.is_empty());
        assert!(!index.exists());
        assert!(!history.can_clear(cleared, false, false, false));
    }

    #[::core::prelude::v1::test]
    fn inspector_recent_failed_clear_keeps_evidence_and_exposes_error() {
        let root = aps_platform::TempWorkspace::create(&std::env::temp_dir()).unwrap();
        let history_root = root.path().join("recent-artifacts");
        std::fs::create_dir(&history_root).unwrap();
        let index = history_root.join("home-recent-layout-v1.json");
        std::fs::create_dir(&index).unwrap();
        let retained = index.join("not-an-index.apinspect");
        std::fs::write(&retained, b"not ours to delete").unwrap();
        let store = RecentPathStore::new(&history_root);
        let mut history = InspectorRecentHistory::default();
        history.begin_load();
        let error = HistoryMutation::Clear(RecordedFeature::Layout)
            .apply(&store)
            .unwrap_err();
        history.fail(error);
        assert!(!history.loading);
        assert!(history.error.is_some());
        assert_eq!(std::fs::read(&retained).unwrap(), b"not ours to delete");
        let rows = inspector_history_menu(&history, false, false, false, Language::English);
        assert!(rows.iter().any(|row| matches!(row, menubar::StripRow::Item { label, enabled: false, .. } if label.starts_with(Language::English.text(TextKey::HomeRecentUnavailable)))));
    }

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

    /// Entering the Layout Inspector (or receiving a device selection while it is
    /// open) fills an empty page with one live capture, so a connected device is
    /// followed by content instead of empty panes; an existing frame and active
    /// captures are never disturbed and the Refresh button remains the manual path.
    #[::core::prelude::v1::test]
    fn inspector_page_auto_captures_once_on_entry() {
        let source = include_str!("shell.rs");
        let navigate = source
            .split("fn navigate(")
            .nth(1)
            .expect("navigate")
            .split("fn entered_route(")
            .next()
            .expect("navigate body");
        assert!(navigate.contains("inspector.capture_if_idle(cx)"));
        let selection = source
            .split("event: &TargetSelectionChanged")
            .nth(1)
            .expect("target selection subscription")
            .split("event: &TargetListsChanged")
            .next()
            .expect("selection body");
        assert!(selection.contains("capture_if_idle(cx)"));
        let inspector = include_str!("inspector.rs");
        assert!(inspector.contains("pub fn capture_if_idle("));
        assert!(inspector.contains("self.archive.is_some()"));
    }

    /// The app-drawn title bar carries the active destination's localized name in
    /// the middle (Compose retitles its native window instead); narrower windows
    /// fall back to the leading-name layout so the fixed target selectors cannot
    /// overlap the label.
    #[::core::prelude::v1::test]
    fn titlebar_centres_active_page_name() {
        let source = include_str!("shell.rs");
        let titlebar = source
            .split("impl Render for Studio")
            .nth(1)
            .expect("Studio render");
        assert!(titlebar.contains("PAGE_TITLE_MIN_WIDTH"));
        assert!(titlebar.contains(".child(selected.label(language))"));
        assert!(titlebar.contains("justify_end()"));
        assert!(titlebar.contains("TITLEBAR_LEADING_INSET - TITLEBAR_TRAILING_INSET"));
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
            auto_scan: false,
        };
        let groups = inspector_menu_groups(
            panels,
            layout,
            state,
            &InspectorRecentHistory::default(),
            false,
            false,
            Language::English,
        );
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

    #[::core::prelude::v1::test]
    fn trace_receives_shared_device_ticks_and_the_initial_device_list() {
        let source = include_str!("shell.rs");
        let wiring = source
            .split("let initial_target_lists = targets.read(cx).current_lists();")
            .nth(1)
            .unwrap()
            .split("subscriptions.push(cx.observe(&ai,")
            .next()
            .unwrap();
        assert!(wiring.contains("event: &TargetListsChanged"));
        assert!(wiring.contains("trace.apply_shared_target_devices(&event.devices, cx)"));
        assert!(wiring.contains("if let Some(lists) = initial_target_lists"));
        assert!(wiring.contains("trace.apply_shared_target_devices(&lists.devices, cx)"));
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

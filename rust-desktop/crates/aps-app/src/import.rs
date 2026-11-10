mod battery;
mod export;
mod frame_page;
mod gpu;
pub(crate) use gpu::OpenGpuTrace;
mod memory;
mod menus;
mod network;
mod snapshots;
mod startup;
use crate::studio;
use aps_battery::BatteryReport;
use aps_contracts::SourceDigest;
use aps_frame::{BudgetSource, DeadlineVerdict, FrameQuery, FrameSession};
use aps_gpu::{ArtifactKind, GpuArtifact, OpenRoute};
use aps_memory::MemorySession;
use aps_network::{HarSession, ImportLimits};
use aps_perfetto::{QueryPreset, TraceProcessor, TraceSession};
use aps_platform::CancellationToken;
use aps_startup::{StartupMode, StartupQuery, StartupSession};
use aps_ui_foundation::{Destination, Language, TextKey, typography};
use gpui_kit::{
    component::{
        ActiveTheme, Disableable, Selectable,
        button::{Button, ButtonVariants},
        scroll::ScrollableElement,
    },
    prelude::*,
    *,
};
use memory::state::MemoryState;
use std::{
    collections::BTreeMap,
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex,
        atomic::{AtomicU64, Ordering},
    },
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

const PAGE: usize = 100;
static NEXT_SESSION: AtomicU64 = AtomicU64::new(0);

#[derive(Clone)]
enum Session {
    Network(Arc<HarSession>),
    Memory(Arc<Mutex<MemorySession>>),
    Trace(Arc<TraceSession>),
    Frame(Arc<FrameSession>),
    Startup(Arc<StartupSession>),
    Battery(Arc<BatteryReport>),
    Gpu(Arc<GpuArtifact>),
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum GpuTab {
    Overview,
    Artifacts,
    RelatedEvidence,
    ToolCapability,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum TraceDetailTab {
    Result,
    Query,
    Search,
}

impl TraceDetailTab {
    const ALL: [Self; 3] = [Self::Result, Self::Query, Self::Search];

    fn label(self, language: Language) -> &'static str {
        match (self, language == Language::SimplifiedChinese) {
            (Self::Result, true) => "结果",
            (Self::Query, true) => "查询",
            (Self::Search, true) => "搜索",
            (Self::Result, false) => "Result",
            (Self::Query, false) => "Query",
            (Self::Search, false) => "Search",
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum TraceView {
    Processes,
    Threads,
    Slices,
    Scheduling,
}

impl TraceView {
    const ALL: [Self; 4] = [
        Self::Processes,
        Self::Threads,
        Self::Slices,
        Self::Scheduling,
    ];

    fn preset(self) -> QueryPreset {
        match self {
            Self::Processes => QueryPreset::Processes,
            Self::Threads => QueryPreset::Threads,
            Self::Slices => QueryPreset::Slices,
            Self::Scheduling => QueryPreset::CpuScheduling,
        }
    }

    fn title(self) -> TextKey {
        trace_title(self.preset())
    }
}

#[derive(Clone, Debug)]
enum UiText {
    Raw(String),
    Message(TextKey, Vec<(&'static str, UiText)>),
    List(Vec<UiText>),
}

impl UiText {
    fn format(key: TextKey, args: impl IntoIterator<Item = (&'static str, UiText)>) -> Self {
        Self::Message(key, args.into_iter().collect())
    }

    fn render(&self, language: Language) -> String {
        match self {
            Self::Raw(value) => value.clone(),
            Self::Message(key, args) => {
                let values: Vec<_> = args
                    .iter()
                    .map(|(name, value)| (*name, value.render(language)))
                    .collect();
                let args: Vec<_> = values
                    .iter()
                    .map(|(name, value)| (*name, value as &dyn std::fmt::Display))
                    .collect();
                language.format(*key, &args)
            }
            Self::List(values) => values
                .iter()
                .map(|value| value.render(language))
                .collect::<Vec<_>>()
                .join("; "),
        }
    }
}

impl From<String> for UiText {
    fn from(value: String) -> Self {
        Self::Raw(value)
    }
}

impl From<&str> for UiText {
    fn from(value: &str) -> Self {
        Self::Raw(value.to_owned())
    }
}

impl From<TextKey> for UiText {
    fn from(key: TextKey) -> Self {
        Self::Message(key, Vec::new())
    }
}

// Display-only projections. Export and AI evidence continue to read the raw fields.
#[derive(Clone, Default)]
struct PageUi {
    summary: Option<UiText>,
    columns: Vec<TextKey>,
    warnings: BTreeMap<usize, UiText>,
    cells: BTreeMap<(usize, usize), UiText>,

    frame_page: Option<aps_frame::FramePage>,
    startup_page: Option<aps_startup::StartupPage>,
    startup_query: Option<StartupQuery>,
    startup_mad: Option<f64>,
    frame_analysis: Option<aps_frame::FrameAnalysisPage>,
    frame_query: Option<FrameQuery>,
    frame_windows: Vec<String>,
    frame_windows_truncated: bool,
}

impl PageUi {
    fn new(summary: UiText, columns: &[TextKey]) -> Self {
        Self {
            summary: Some(summary),
            columns: columns.to_vec(),
            ..Self::default()
        }
    }

    fn warning(mut self, index: usize, text: UiText) -> Self {
        self.warnings.insert(index, text);
        self
    }
}

#[derive(Clone)]
struct PageData {
    summary: String,
    columns: Vec<String>,
    rows: Vec<Vec<String>>,
    offset: usize,
    has_more: bool,
    warnings: Vec<String>,
    row_actions: Vec<()>,
    ui: PageUi,
}

impl PageData {
    fn display_summary(&self, language: Language) -> String {
        self.ui
            .summary
            .as_ref()
            .map_or_else(|| self.summary.clone(), |text| text.render(language))
    }

    fn display_columns(&self, language: Language) -> String {
        self.columns
            .iter()
            .enumerate()
            .map(|(index, raw)| {
                self.ui
                    .columns
                    .get(index)
                    .map_or_else(|| raw.clone(), |key| language.text(*key).into())
            })
            .collect::<Vec<_>>()
            .join("  |  ")
    }

    fn display_row(&self, row: usize, language: Language) -> String {
        self.rows[row]
            .iter()
            .enumerate()
            .map(|(column, raw)| {
                self.ui
                    .cells
                    .get(&(row, column))
                    .map_or_else(|| raw.clone(), |text| text.render(language))
            })
            .collect::<Vec<_>>()
            .join("  |  ")
    }

    fn display_warning(&self, index: usize, language: Language) -> String {
        self.ui.warnings.get(&index).map_or_else(
            || self.warnings[index].clone(),
            |text| text.render(language),
        )
    }
}

pub struct ImportWorkspace {
    language: Language,
    kind: Destination,
    session: Option<Session>,
    path: Option<PathBuf>,
    page: Option<PageData>,
    busy: bool,
    import_started: Option<Instant>,
    import_duration: Option<Duration>,
    error: Option<UiText>,
    generation: u64,
    cancel: CancellationToken,
    trace_view: TraceView,
    memory_state: MemoryState,
    memory_snapshots: Vec<snapshots::MemorySnapshot>,
    memory_snapshot_id: Option<u64>,
    studio: Option<WeakEntity<crate::shell::Studio>>,
    selected_network: Option<Vec<String>>,
    session_id: Option<String>,
    export_busy: bool,
    export_generation: u64,
    notice: Option<UiText>,
    selected_frame_id: Option<u64>,
    frame_hovered_id: Option<u64>,
    frame_select_last_on_publish: bool,
    frame_deadline_filter: Option<DeadlineVerdict>,
    frame_window_filter: Option<String>,
    frame_timeline_start: usize,
    frame_timeline_count: usize,
    startup_mode_filter: Option<StartupMode>,
    startup_page_offset: usize,
    selected_startup_run_id: Option<String>,
    gpu_tab: GpuTab,
    gpu: gpu::GpuUi,
    network: network::NetworkUi,
    battery_ui: battery::BatteryUi,
    battery_workspace_ui: battery::workspace::BatteryWorkspaceUi,
    battery_shell_ui: battery::BatteryShellUi,
    battery_metrics: Option<battery::ReportMetrics>,
    trace_detail_tab: TraceDetailTab,
    selected_trace_row: Option<usize>,
}

impl ImportWorkspace {
    pub fn new(kind: Destination, language: Language) -> Self {
        Self {
            language,
            kind,
            session: None,
            path: None,
            page: None,
            busy: false,
            import_started: None,
            import_duration: None,
            error: None,
            generation: 0,
            cancel: CancellationToken::new(),
            trace_view: TraceView::Processes,
            memory_state: MemoryState::new(),
            memory_snapshots: Vec::new(),
            memory_snapshot_id: None,
            studio: None,
            selected_network: None,
            session_id: None,
            export_busy: false,
            export_generation: 0,
            notice: None,
            selected_frame_id: None,
            frame_hovered_id: None,
            frame_select_last_on_publish: false,
            frame_deadline_filter: None,
            frame_window_filter: None,
            frame_timeline_start: 0,
            frame_timeline_count: 100,
            startup_mode_filter: None,
            startup_page_offset: 0,
            selected_startup_run_id: None,
            gpu_tab: GpuTab::Overview,
            gpu: gpu::GpuUi::default(),
            network: network::NetworkUi::default(),
            battery_ui: battery::BatteryUi::default(),
            battery_workspace_ui: battery::workspace::BatteryWorkspaceUi::default(),
            battery_shell_ui: battery::BatteryShellUi::default(),
            battery_metrics: None,
            trace_detail_tab: TraceDetailTab::Result,
            selected_trace_row: None,
        }
    }
    pub(super) fn import_status(&self, colors: &studio::StudioColors) -> AnyElement {
        let value = self.import_duration.map_or_else(
            || "—".to_owned(),
            |duration| format!("Import · {:.2}s", duration.as_secs_f64()),
        );
        div()
            .id("import-operation-status")
            .flex()
            .items_center()
            .px(px(8.))
            .py(px(3.))
            .flex_shrink_0()
            .border_t_1()
            .border_color(colors.border)
            .child(studio::supporting(value, colors))
            .into_any_element()
    }

    pub fn set_language(&mut self, language: Language, cx: &mut Context<Self>) {
        self.language = language;
        cx.notify();
    }
    pub fn set_studio(&mut self, studio: WeakEntity<crate::shell::Studio>) {
        self.studio = Some(studio);
    }
    pub fn pick(&mut self, cx: &mut Context<Self>) {
        if self.kind == Destination::Gpu {
            self.gpu_pick(false, cx);
            return;
        }
        if self.busy {
            return;
        }
        self.busy = true;
        self.error = None;
        self.generation += 1;
        let generation = self.generation;
        cx.notify();
        let picker = cx.prompt_for_paths(PathPromptOptions {
            files: true,
            directories: false,
            multiple: false,
            prompt: Some(
                self.language
                    .text(TextKey::ImportImportLocalArtifact)
                    .into(),
            ),
        });
        cx.spawn(async move |this, cx| {
            let result = picker.await;
            this.update(cx, |this, cx| {
                if this.generation != generation {
                    return;
                }
                this.busy = false;
                match result {
                    Ok(Ok(Some(paths))) if paths.len() == 1 => this.open(paths[0].clone(), cx),
                    Ok(Err(_)) => {
                        this.import_started = None;
                        this.import_duration = None;
                        this.error = Some(TextKey::ImportUiFilePickerFailed.into());
                    }
                    _ => {
                        this.import_started = None;
                        this.import_duration = None;
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }
    pub(crate) fn can_open_home_artifact(&self) -> bool {
        !self.busy
            && (self.kind != Destination::Memory
                || self.memory_snapshot_tabs().len() < snapshots::MAX_OPEN_MEMORY_SNAPSHOTS)
    }

    /// Home keeps its route until a file is selected; all parsing and snapshot
    /// limits continue to use the same feature-owned import implementation.
    pub(crate) fn open_home_artifact(&mut self, path: PathBuf, cx: &mut Context<Self>) -> bool {
        if !self.can_open_home_artifact() || !path.is_file() {
            return false;
        }
        self.open(path, cx);
        true
    }

    fn open(&mut self, path: PathBuf, cx: &mut Context<Self>) {
        if self.kind == Destination::Memory
            && self.memory_snapshot_tabs().len() >= snapshots::MAX_OPEN_MEMORY_SNAPSHOTS
        {
            self.error = Some(snapshots::memory_tab_limit_message(self.language));
            cx.notify();
            return;
        }
        self.cancel.cancel();
        self.cancel = CancellationToken::new();
        self.generation += 1;
        self.import_started = Some(Instant::now());
        self.import_duration = None;
        let generation = self.generation;
        self.busy = true;
        self.error = None;
        self.export_generation += 1;
        self.export_busy = false;
        let kind = self.kind;
        let cancel = self.cancel.clone();
        let view = self.trace_view;
        let task = cx.background_executor().spawn(async move {
            if kind == Destination::Network {
                let session = Arc::new(
                    HarSession::open(&path, ImportLimits::default(), &cancel)
                        .map_err(network::network_error)?,
                );
                let publication = network::load_page(
                    &session,
                    0,
                    &aps_network::EntryQuery::default(),
                    None,
                    &cancel,
                )?;
                Ok((
                    path,
                    Session::Network(session),
                    publication.page.clone(),
                    Some(publication),
                    None,
                ))
            } else {
                open_selected(kind, &path, view, &cancel).map(|(session, page)| {
                    let metrics = match &session {
                        Session::Battery(report) => Some(battery::report_metrics(report)),
                        _ => None,
                    };
                    (path, session, page, None, metrics)
                })
            }
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.generation != generation {
                    return;
                }
                this.busy = false;
                match result {
                    Ok((path, session, page, network, battery_metrics)) => {
                        let memory_snapshot_id = if this.kind == Destination::Memory {
                            match snapshots::new_memory_snapshot_id() {
                                Some(id) => Some(id),
                                None => {
                                    this.error = Some(
                                        snapshots::memory_identity_exhausted_message(this.language),
                                    );
                                    cx.notify();
                                    return;
                                }
                            }
                        } else {
                            None
                        };
                        if this.kind == Destination::Memory {
                            if let Some(previous) = this.take_active_memory_snapshot() {
                                this.memory_snapshots.push(previous);
                            }
                        }
                        this.path = Some(path);
                        this.import_duration =
                            this.import_started.take().map(|started| started.elapsed());
                        this.session = Some(session);
                        this.page = Some(page);
                        this.session_id = Some(new_session_id());
                        this.memory_snapshot_id = memory_snapshot_id;
                        if this.kind == Destination::Memory {
                            this.memory_state = MemoryState::new();
                        }
                        this.selected_network = None;
                        this.network.reset();
                        this.battery_ui.reset();
                        this.battery_workspace_ui = Default::default();
                        this.battery_metrics = battery_metrics;
                        this.battery_shell_ui.warnings_offset = 0;
                        this.publish_battery_run_page();
                        if let Some(network) = network {
                            this.publish_network(network, aps_network::EntryQuery::default());
                        }
                        this.notice = None;
                        this.selected_frame_id = None;
                        this.frame_hovered_id = None;
                        this.frame_select_last_on_publish = false;
                        this.frame_deadline_filter = None;
                        this.frame_window_filter = None;
                        this.frame_timeline_start = 0;
                        this.frame_timeline_count = 100;
                        this.startup_mode_filter = None;
                        this.startup_page_offset = 0;
                        this.selected_startup_run_id = None;
                        this.publish_startup_selection();
                        if this.kind == Destination::Memory
                            && matches!(this.session, Some(Session::Memory(_)))
                        {
                            this.memory_begin_load(cx);
                        }
                    }
                    Err(error) => this.error = Some(error),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }
    fn page(&mut self, offset: usize, cx: &mut Context<Self>) {
        if self.kind == Destination::Startup {
            self.query_startup(self.startup_mode_filter, offset, cx);
            return;
        }
        if self.kind == Destination::Network {
            self.query_network(offset, self.network.query.clone(), cx);
            return;
        }
        if self.busy {
            return;
        }
        let Some(session) = self.session.clone() else {
            return;
        };
        self.cancel.cancel();
        self.cancel = CancellationToken::new();
        self.generation += 1;
        let generation = self.generation;
        self.busy = true;
        self.error = None;
        let view = self.trace_view;
        let frame_deadline = self.frame_deadline_filter;
        let frame_window = self.frame_window_filter.clone();
        let frame_limit = self.frame_timeline_count.clamp(20, 500);
        if self.kind == Destination::Startup {
            self.startup_page_offset = offset;
        }
        let cancel = self.cancel.clone();
        let task = cx.background_executor().spawn(async move {
            let frame_query = matches!(session, Session::Frame(_)).then(|| FrameQuery {
                window: frame_window,
                deadline: frame_deadline,
                offset,
                limit: frame_limit,
                ..FrameQuery::default()
            });
            page_for(&session, view, offset, frame_query, &cancel)
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.generation != generation {
                    return;
                }
                this.busy = false;
                match result {
                    Ok(page) => {
                        this.page = Some(page);
                        this.publish_battery_run_page();
                        if this.kind == Destination::Frame {
                            if let Some(query) = this
                                .page
                                .as_ref()
                                .and_then(|page| page.ui.frame_query.as_ref())
                            {
                                this.frame_deadline_filter = query.deadline;
                                this.frame_window_filter = query.window.clone();
                                this.frame_timeline_start = query.offset;
                                this.frame_timeline_count = query.limit;
                            }
                            if this.frame_select_last_on_publish {
                                this.selected_frame_id = this
                                    .page
                                    .as_ref()
                                    .and_then(|page| page.ui.frame_page.as_ref())
                                    .and_then(|page| page.rows.last())
                                    .map(|frame| frame.id);
                                this.frame_select_last_on_publish = false;
                            }
                        }
                        this.selected_network = None;
                    }
                    Err(error) => {
                        if this.kind == Destination::Frame {
                            if let Some(query) = this
                                .page
                                .as_ref()
                                .and_then(|page| page.ui.frame_query.as_ref())
                            {
                                this.frame_deadline_filter = query.deadline;
                                this.frame_window_filter = query.window.clone();
                                this.frame_timeline_start = query.offset;
                                this.frame_timeline_count = query.limit;
                            }
                        }
                        this.error = Some(error);
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }
    fn trace_view(&mut self, view: TraceView, cx: &mut Context<Self>) {
        if self.busy {
            return;
        }
        self.trace_view = view;
        self.page(0, cx);
    }
    fn close(&mut self, cx: &mut Context<Self>) {
        if self.kind == Destination::Memory {
            if let Some(id) = self.memory_snapshot_id {
                self.close_memory_snapshot(id, cx);
                return;
            }
        }
        self.cancel.cancel();
        self.generation += 1;
        self.session = None;
        self.page = None;
        self.path = None;
        self.busy = false;
        self.error = None;
        self.memory_state = MemoryState::new();
        self.memory_snapshots.clear();
        self.memory_snapshot_id = None;
        self.selected_network = None;
        self.network.reset();
        self.battery_ui.reset();
        self.battery_workspace_ui = Default::default();
        self.battery_metrics = None;
        self.battery_shell_ui.warnings_offset = 0;
        self.trace_detail_tab = TraceDetailTab::Result;
        self.selected_trace_row = None;
        self.session_id = None;
        self.export_generation += 1;
        self.export_busy = false;
        self.notice = None;
        self.selected_frame_id = None;
        self.frame_hovered_id = None;
        self.frame_select_last_on_publish = false;
        self.frame_deadline_filter = None;
        self.frame_window_filter = None;
        self.frame_timeline_start = 0;
        self.frame_timeline_count = 100;
        self.startup_mode_filter = None;
        self.startup_page_offset = 0;
        self.selected_startup_run_id = None;
        cx.notify();
    }

    fn cancel_work(&mut self, cx: &mut Context<Self>) {
        self.cancel.cancel();
        self.generation += 1;
        self.busy = false;
        if self.kind == Destination::Memory {
            self.memory_state.load_generation += 1;
            self.memory_state.loading = false;
            self.memory_state.loading_message = None;
            self.memory_state.error = None;
        }
        if let Some(query) = self
            .page
            .as_ref()
            .and_then(|page| page.ui.frame_query.as_ref())
        {
            self.frame_deadline_filter = query.deadline;
            self.frame_window_filter = query.window.clone();
            self.frame_timeline_start = query.offset;
            self.frame_timeline_count = query.limit;
        }
        self.network.query = self.network.published_query.clone();
        self.error = None;
        self.notice = Some(TextKey::ImportOperationCancelledPreviousPublishedPageRetained.into());
        cx.notify();
    }
    pub fn analysis_request(&self) -> Result<aps_ai::AnalysisRequest, String> {
        if self.busy {
            return Err(self.language.text(TextKey::ImportUiWaitForImport).into());
        }
        if self.kind == Destination::Memory {
            return self.memory_analysis_request();
        }
        let session = self.session_id.as_ref().ok_or_else(|| {
            self.language
                .text(TextKey::ImportUiImportBeforeAi)
                .to_owned()
        })?;
        let page = self.page.as_ref().ok_or_else(|| {
            self.language
                .text(TextKey::ImportUiNoEvidencePage)
                .to_owned()
        })?;
        export::analysis_request(self.kind, session, self.generation, page)
            .map_err(|error| export_error(error).render(self.language))
    }
    fn copy_page(&mut self, format: export::Format, cx: &mut Context<Self>) {
        match self
            .page
            .as_ref()
            .ok_or_else(|| UiText::from(TextKey::ImportUiNoPublishedPage))
            .and_then(|page| export::encode_page(page, format).map_err(export_error))
        {
            Ok(bytes) => {
                cx.write_to_clipboard(ClipboardItem::new_string(
                    String::from_utf8(bytes).expect("export is UTF-8"),
                ));
                self.notice = Some(TextKey::ImportCurrentPageCopiedImportedContentMayBe.into());
            }
            Err(error) => self.error = Some(error),
        }
        cx.notify();
    }
    fn save_page(&mut self, format: export::Format, cx: &mut Context<Self>) {
        if self.export_busy || self.busy {
            return;
        }
        let Some(page) = self.page.clone() else {
            return;
        };
        self.export_busy = true;
        self.export_generation += 1;
        let generation = self.export_generation;
        let directory = self
            .path
            .as_ref()
            .and_then(|path| path.parent())
            .unwrap_or(Path::new("."));
        let filename = format!("aps-page.{}", format.extension());
        let picker = cx.prompt_for_new_path(directory, Some(&filename));
        cx.spawn(async move |this, cx| {
            let chosen = picker.await;
            let Some(path) = this
                .update(cx, |this, cx| {
                    if this.export_generation != generation {
                        return None;
                    }
                    match chosen {
                        Ok(Ok(Some(path))) => Some(path),
                        Ok(Err(_)) => {
                            this.error = Some(TextKey::ImportUiSavePickerFailed.into());
                            this.export_busy = false;
                            cx.notify();
                            None
                        }
                        _ => {
                            this.export_busy = false;
                            cx.notify();
                            None
                        }
                    }
                })
                .ok()
                .flatten()
            else {
                return;
            };
            let result = cx
                .background_executor()
                .spawn(async move {
                    let bytes = export::encode_page(&page, format).map_err(export_error)?;
                    export::write_new_file(&path, &bytes).map_err(export_error)?;
                    Ok::<_, UiText>(path)
                })
                .await;
            this.update(cx, |this, cx| {
                if this.export_generation != generation {
                    return;
                }
                this.export_busy = false;
                match result {
                    Ok(path) => {
                        this.notice = Some(UiText::format(
                            TextKey::ImportUiSavedPage,
                            [("path", path.display().to_string().into())],
                        ))
                    }
                    Err(error) => this.error = Some(error),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }
}

fn new_session_id() -> String {
    let now = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_micros());
    format!(
        "import-{now:x}-{:x}-{:x}",
        std::process::id(),
        NEXT_SESSION.fetch_add(1, Ordering::Relaxed)
    )
}

fn trace_title(preset: QueryPreset) -> TextKey {
    match preset {
        QueryPreset::TraceBounds => TextKey::ImportUiTraceBounds,
        QueryPreset::Processes => TextKey::ImportUiProcesses,
        QueryPreset::Threads => TextKey::ImportUiThreads,
        QueryPreset::Slices => TextKey::ImportUiSlices,
        QueryPreset::CpuScheduling => TextKey::ImportUiCpuScheduling,
        QueryPreset::GpuFrequency => TextKey::ImportUiGpuFrequency,
        QueryPreset::GpuMemory => TextKey::ImportUiGpuMemory,
        QueryPreset::GpuRenderStages => TextKey::ImportUiGpuRenderStages,
    }
}

fn trace_columns(preset: QueryPreset) -> &'static [TextKey] {
    match preset {
        QueryPreset::TraceBounds => &[TextKey::ImportUiStartTs, TextKey::ImportUiEndTs],
        QueryPreset::Processes => &[
            TextKey::ImportUiUpid,
            TextKey::ImportUiPid,
            TextKey::ImportUiName,
        ],
        QueryPreset::Threads => &[
            TextKey::ImportUiUtid,
            TextKey::ImportUiTid,
            TextKey::ImportUiUpid,
            TextKey::ImportUiName,
        ],
        QueryPreset::Slices => &[
            TextKey::ImportUiId,
            TextKey::ImportUiTimestamp,
            TextKey::ImportUiDuration,
            TextKey::ImportUiTrackId,
            TextKey::ImportUiName,
        ],
        QueryPreset::CpuScheduling => &[
            TextKey::ImportUiId,
            TextKey::ImportUiTimestamp,
            TextKey::ImportUiDuration,
            TextKey::ImportUiCpu,
            TextKey::ImportUiUtid,
        ],
        QueryPreset::GpuFrequency => &[
            TextKey::ImportUiGpuId,
            TextKey::ImportUiFrequency,
            TextKey::ImportUiDurationMs,
            TextKey::ImportUiTimestampNs,
        ],
        QueryPreset::GpuMemory => &[
            TextKey::ImportUiTimestampNs,
            TextKey::ImportUiDurationNs,
            TextKey::ImportUiMemoryBytes,
        ],
        QueryPreset::GpuRenderStages => &[
            TextKey::ImportUiId,
            TextKey::ImportUiTimestamp,
            TextKey::ImportUiDuration,
            TextKey::ImportUiTrackId,
            TextKey::ImportUiName,
        ],
    }
}

// Only errors from our exporter are mapped; source/engine diagnostics stay raw.
fn export_error(error: String) -> UiText {
    match error.as_str() {
        "Current page exceeds JSON export bounds" => TextKey::ImportUiJsonExportLimit.into(),
        "Current page exceeds CSV export byte budget" => TextKey::ImportUiCsvExportLimit.into(),
        "Export requires an absolute destination within its byte budget" => {
            TextKey::ImportUiExportDestinationRequired.into()
        }
        "Export destination already exists or cannot be created; select a new filename" => {
            TextKey::ImportUiExportDestinationUnavailable.into()
        }
        "Cannot finish selected export; partial output cleanup was attempted" => {
            TextKey::ImportUiExportWriteFailed.into()
        }
        "Workspace has no imported profiler evidence" => {
            TextKey::ImportUiWorkspaceNoEvidence.into()
        }
        "Evidence page exceeds AI context budget" => TextKey::ImportUiEvidenceBudget.into(),
        _ => error.into(),
    }
}

fn open_selected(
    kind: Destination,
    path: &Path,
    view: TraceView,
    cancel: &CancellationToken,
) -> Result<(Session, PageData), UiText> {
    let session = match kind {
        Destination::Network => Session::Network(Arc::new(
            HarSession::open(path, ImportLimits::default(), cancel).map_err(|e| e.to_string())?,
        )),
        Destination::Memory => {
            let digest = SourceDigest::hash_file(path, aps_memory::MAX_INPUT_BYTES)?;
            let worker = std::env::current_exe()
                .map_err(|_| TextKey::ImportUiCannotLocateApp)?
                .with_file_name(if cfg!(target_os = "windows") {
                    "aps-memory-worker.exe"
                } else {
                    "aps-memory-worker"
                });
            Session::Memory(Arc::new(Mutex::new(
                MemorySession::open(&worker, path, &digest.to_hex(), cancel)
                    .map_err(|e| e.to_string())?,
            )))
        }
        Destination::Trace => {
            let tool = locate_trace_processor(cancel)?;
            Session::Trace(Arc::new(
                TraceSession::open(tool, path, cancel).map_err(|e| e.to_string())?,
            ))
        }
        Destination::Frame => Session::Frame(Arc::new(
            FrameSession::open(path, aps_frame::MAX_INPUT_BYTES).map_err(|e| e.to_string())?,
        )),
        Destination::Startup => Session::Startup(Arc::new(
            StartupSession::open_csv(path, aps_startup::MAX_INPUT_BYTES)
                .map_err(|e| e.to_string())?,
        )),
        Destination::Battery => {
            Session::Battery(Arc::new(BatteryReport::open(path, 64 * 1024 * 1024)?))
        }
        Destination::Gpu => Session::Gpu(Arc::new(
            GpuArtifact::import(path, cancel).map_err(|e| e.to_string())?,
        )),
        _ => return Err(TextKey::ImportUiNoImporter.into()),
    };
    let page = page_for(&session, view, 0, None, cancel)?;
    Ok((session, page))
}

/// Release builds never depend on a developer checkout. A present but invalid
/// packaged binary fails closed rather than silently using a different tool.
fn locate_trace_processor(cancel: &CancellationToken) -> Result<TraceProcessor, UiText> {
    let executable = std::env::current_exe().map_err(|_| TextKey::ImportUiCannotLocateRustApp)?;
    let directory = executable.parent().ok_or(TextKey::ImportUiAppHasNoParent)?;
    let resources = directory.join("resources");
    if resources.join("perfetto-tools").exists() {
        return TraceProcessor::from_packaged_resources(&resources, cancel)
            .map_err(|error| UiText::from(error.to_string()));
    }
    #[cfg(debug_assertions)]
    {
        let root = Path::new(env!("CARGO_MANIFEST_DIR"))
            .ancestors()
            .nth(3)
            .ok_or(TextKey::ImportUiCannotLocateRepository)?;
        return TraceProcessor::from_repository(root, cancel)
            .map_err(|error| UiText::from(error.to_string()));
    }
    #[cfg(not(debug_assertions))]
    Err(TextKey::ImportUiPackagedTraceProcessorMissing.into())
}

fn page_for(
    session: &Session,
    view: TraceView,
    offset: usize,
    frame_query: Option<FrameQuery>,
    cancel: &CancellationToken,
) -> Result<PageData, UiText> {
    if cancel.is_cancelled() {
        return Err(TextKey::ImportUiImportCancelled.into());
    }
    match session {
        Session::Battery(session) => {
            let rows = session.page(offset, PAGE)?;
            let mut ui = PageUi::new(
                UiText::format(
                    TextKey::ImportUiBatterySummary,
                    [
                        ("count", session.runs().len().to_string().into()),
                        ("uid", format!("{:?}", session.uid).into()),
                        (
                            "package",
                            session
                                .package_name
                                .clone()
                                .map_or_else(|| TextKey::ImportUiUnattributed.into(), UiText::from),
                        ),
                    ],
                ),
                &[
                    TextKey::ImportUiIteration,
                    TextKey::ImportUiDurationMs,
                    TextKey::ImportUiNetworkBytes,
                    TextKey::ImportUiTimers,
                    TextKey::ImportUiEnergyEvidence,
                    TextKey::ImportUiThermalStatus,
                ],
            );
            for (index, row) in rows.iter().enumerate() {
                ui.cells.insert((index, 2), optional_ui(row.network_bytes));
                ui.cells
                    .insert((index, 5), optional_ui(row.max_thermal_status));
                ui.cells.insert(
                    (index, 4),
                    UiText::List(
                        row.energy
                            .iter()
                            .map(|energy| {
                                UiText::format(
                                    TextKey::ImportUiEnergyValue,
                                    [
                                        ("component", energy.component.clone().into()),
                                        ("value", optional_ui(energy.energy_mah)),
                                        ("source", energy.source.clone().into()),
                                        ("scope", energy.scope.clone().into()),
                                        ("confidence", energy.confidence.clone().into()),
                                    ],
                                )
                            })
                            .collect(),
                    ),
                );
            }
            Ok(PageData {
                ui,
                row_actions: Vec::new(),
                summary: format!(
                    "{} runs · UID {:?} · {}",
                    session.runs().len(),
                    session.uid,
                    session.package_name.as_deref().unwrap_or("unattributed")
                ),
                columns: vec![
                    "Iteration",
                    "Duration ms",
                    "Network bytes",
                    "Timers",
                    "Energy evidence",
                    "Thermal status",
                ]
                .into_iter()
                .map(str::to_owned)
                .collect(),
                rows: rows
                    .into_iter()
                    .map(|row| {
                        vec![
                            row.iteration.to_string(),
                            row.duration_ms.to_string(),
                            optional(row.network_bytes),
                            row.timers.len().to_string(),
                            row.energy
                                .iter()
                                .map(|e| {
                                    format!(
                                        "{}: {} mAh ({}, {}, {})",
                                        e.component,
                                        optional(e.energy_mah),
                                        e.source,
                                        e.scope,
                                        e.confidence
                                    )
                                })
                                .collect::<Vec<_>>()
                                .join("; "),
                            optional(row.max_thermal_status),
                        ]
                    })
                    .collect(),
                offset,
                has_more: offset + PAGE < session.runs().len(),
                warnings: session.warnings.clone(),
            })
        }
        Session::Gpu(artifact) => {
            let resolved = artifact
                .resolve_location(cancel)
                .map_err(|e| e.to_string())?;
            Ok(PageData {
                ui: PageUi::new(
                    UiText::format(TextKey::ImportUiGpuSummary, [
                        ("kind", format!("{:?}", artifact.kind).into()),
                        ("bytes", artifact.size_bytes.to_string().into()),
                    ]),
                    &[
                        TextKey::ImportUiSha256,
                        TextKey::ImportUiVerifiedLocationStatus,
                        TextKey::ImportUiProposedOpenRoute,
                    ],
                ).warning(0, TextKey::ImportUiGpuWarning.into()),
                row_actions: Vec::new(),
                summary:format!("GPU artifact · {:?} · {} bytes",artifact.kind,artifact.size_bytes),
                columns:vec!["SHA-256".into(),"Verified location status".into(),"Proposed open route".into()],
                rows:vec![vec![artifact.sha256.to_hex(),format!("{:?}",resolved.status),format!("{:?}",artifact.open_route)]],offset:0,has_more:false,
                warnings:vec!["Artifact classification does not prove AGI is installed or authorize launching an external tool.".into()],
            })
        }
        Session::Network(session) => {
            let page = session
                .entries(offset, PAGE, cancel)
                .map_err(|e| e.to_string())?;
            let summary = session.summary();
            Ok(PageData {
                ui: PageUi::new(
                    UiText::format(
                        TextKey::ImportUiNetworkSummary,
                        [
                            ("count", summary.entry_count.to_string().into()),
                            (
                                "bytes",
                                summary.response_bytes.total_bytes.to_string().into(),
                            ),
                            ("mean", format!("{:?}", summary.mean_duration_ms).into()),
                        ],
                    ),
                    &[
                        TextKey::ImportUiEntryNumber,
                        TextKey::ImportUiMethod,
                        TextKey::ImportUiStatus,
                        TextKey::ImportUiDurationMs,
                        TextKey::ImportUiRequestBytes,
                        TextKey::ImportUiResponseBytes,
                        TextKey::ImportUiUrl,
                    ],
                )
                .warning(0, TextKey::ImportUiNetworkWarning.into()),
                row_actions: Vec::new(),
                summary: format!(
                    "{} requests · {} known response bytes · mean {:?} ms · minimized",
                    summary.entry_count,
                    summary.response_bytes.total_bytes,
                    summary.mean_duration_ms
                ),
                columns: vec![
                    "#",
                    "Method",
                    "Status",
                    "Duration ms",
                    "Request bytes",
                    "Response bytes",
                    "URL",
                ]
                .into_iter()
                .map(str::to_owned)
                .collect(),
                rows: page
                    .rows
                    .into_iter()
                    .map(|row| {
                        vec![
                            row.index.to_string(),
                            row.method.into(),
                            row.status.to_string(),
                            format!("{:?}", row.duration_ms),
                            format!("{:?}", row.request_bytes),
                            format!("{:?}", row.response_bytes),
                            row.minimized_url.into(),
                        ]
                    })
                    .collect(),
                offset,
                has_more: (offset as u64 + PAGE as u64) < page.total,
                warnings: vec![
                    "URL, headers, cookies and body are excluded from this preview".into(),
                ],
            })
        }
        Session::Memory(_) => Ok(PageData {
            summary: String::new(),
            columns: Vec::new(),
            rows: Vec::new(),
            offset,
            has_more: false,
            warnings: Vec::new(),
            row_actions: Vec::new(),
            ui: PageUi::default(),
        }),
        Session::Trace(session) => {
            let page = session
                .query(view.preset(), offset, PAGE, cancel)
                .map_err(|e| e.to_string())?;
            Ok(PageData {
                ui: PageUi::new(
                    UiText::format(
                        TextKey::ImportUiTraceSummary,
                        [
                            ("view", trace_title(page.preset).into()),
                            ("digest", page.source_digest.to_string().into()),
                        ],
                    ),
                    trace_columns(page.preset),
                )
                .warning(0, TextKey::ImportUiTraceWarning.into()),
                row_actions: Vec::new(),
                summary: format!(
                    "Perfetto {} · source SHA-256 {} · one-shot query",
                    page.preset.title(),
                    page.source_digest
                ),
                columns: page.columns,
                rows: page
                    .rows
                    .into_iter()
                    .map(|row| {
                        row.into_iter()
                            .map(|v| v.unwrap_or_else(|| "NULL".into()))
                            .collect()
                    })
                    .collect(),
                offset,
                has_more: page.has_more,
                warnings: vec![
                    "Each page reparses the trace; timestamps retain their source clock domain"
                        .into(),
                ],
            })
        }
        Session::Frame(session) => {
            let mut query = frame_query.unwrap_or_else(FrameQuery::first_page);
            query.offset = offset;
            let page = session.page(&query).map_err(|e| e.to_string())?;
            let mut window_set = std::collections::BTreeSet::new();
            let mut windows_truncated = false;
            for window in session
                .frames()
                .iter()
                .filter_map(|frame| frame.window.as_ref())
            {
                if window_set.len() < 64 || window_set.contains(window) {
                    window_set.insert(window.clone());
                } else {
                    windows_truncated = true;
                }
            }
            let windows = window_set.into_iter().collect();
            let frame_rows = page.rows.clone();
            let analysis = session
                .analysis_page(&query, 0, 200)
                .map_err(UiText::from)?;
            let mut ui = PageUi::new(
                UiText::format(
                    TextKey::ImportUiFrameSummary,
                    [
                        ("count", page.summary.total_frames.to_string().into()),
                        ("misses", page.summary.deadline_misses.to_string().into()),
                        ("unknown", page.summary.unknown_deadlines.to_string().into()),
                    ],
                ),
                &[
                    TextKey::ImportUiWindow,
                    TextKey::ImportUiIntendedVsyncNs,
                    TextKey::ImportUiDurationNs,
                    TextKey::ImportUiBudgetNs,
                    TextKey::ImportUiDeadline,
                ],
            );
            ui.frame_page = Some(page.clone());
            ui.frame_analysis = Some(analysis);
            ui.frame_query = Some(query);
            ui.frame_windows = windows;
            ui.frame_windows_truncated = windows_truncated;
            Ok(PageData {
                ui,
                row_actions: Vec::new(),
                summary: format!(
                    "{} frames · {} deadline misses · {} unknown deadlines · platform jank unavailable",
                    page.summary.total_frames,
                    page.summary.deadline_misses,
                    page.summary.unknown_deadlines
                ),
                columns: vec![
                    "Window",
                    "Intended vsync ns",
                    "Duration ns",
                    "Budget ns",
                    "Deadline",
                ]
                .into_iter()
                .map(str::to_owned)
                .collect(),
                rows: frame_rows
                    .into_iter()
                    .map(|row| {
                        vec![
                            row.window.unwrap_or_default(),
                            format!("{:?}", row.intended_vsync_ns),
                            format!("{:?}", row.duration_ns),
                            if row.budget_source == BudgetSource::Unknown {
                                "unavailable".into()
                            } else {
                                row.budget_ns
                                    .map_or_else(|| "unavailable".into(), |value| value.to_string())
                            },
                            format!("{:?}", row.deadline),
                        ]
                    })
                    .collect(),
                offset,
                has_more: offset.saturating_add(page.rows.len()) < page.total,
                warnings: session.warnings().to_vec(),
            })
        }
        Session::Startup(session) => startup::load_page(
            session,
            &StartupQuery {
                offset,
                limit: PAGE,
                ..Default::default()
            },
            cancel,
        ),
    }
}

impl Render for ImportWorkspace {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        if self.kind == Destination::Trace {
            return self.render_trace_workspace(cx);
        }
        if matches!(self.kind, Destination::Memory | Destination::Frame) {
            return self.render_specialized(window, cx);
        }
        if self.kind == Destination::Startup {
            return self.render_startup_workspace(cx);
        }
        if self.kind == Destination::Network {
            return self.render_network_profiler(window, cx);
        }
        if self.kind == Destination::Battery {
            return self.render_battery_profiler(window, cx);
        }
        if self.kind == Destination::Gpu {
            return self.render_gpu_hub(window, cx);
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
                    .flex()
                    .gap_2()
                    .items_center()
                    .child(
                        Button::new("artifact-import")
                            .primary()
                            .label(lang.text(TextKey::ImportImportLocalArtifact))
                            .disabled(busy)
                            .on_click(cx.listener(|this, _, _, cx| this.pick(cx))),
                    )
                    .child(
                        Button::new("artifact-cancel")
                            .label(lang.text(TextKey::ImportCancelOperation))
                            .disabled(!busy)
                            .on_click(cx.listener(|this, _, _, cx| this.cancel_work(cx))),
                    )
                    .child(
                        Button::new("artifact-close")
                            .label(lang.text(TextKey::CommonClose))
                            .on_click(cx.listener(|this, _, _, cx| this.close(cx))),
                    ),
            )
            .children(self.notice.as_ref().map(|notice| {
                div()
                    .text_size(px(typography::SUPPORTING))
                    .child(notice.render(lang))
            }))
            .children(self.error.as_ref().map(|error| {
                div()
                    .p_3()
                    .text_color(cx.theme().danger)
                    .child(error.render(lang))
            }));
        if self.page.is_some() {
            body = body
                .child(
                    div()
                        .flex()
                        .flex_wrap()
                        .gap_2()
                        .child(
                            Button::new("page-copy-csv")
                                .label(lang.text(TextKey::ImportCopyPageCsv))
                                .disabled(busy)
                                .on_click(cx.listener(|this, _, _, cx| {
                                    this.copy_page(export::Format::Csv, cx)
                                })),
                        )
                        .child(
                            Button::new("page-copy-json")
                                .label(lang.text(TextKey::ImportCopyPageJson))
                                .disabled(busy)
                                .on_click(cx.listener(|this, _, _, cx| {
                                    this.copy_page(export::Format::Json, cx)
                                })),
                        )
                        .child(
                            Button::new("page-save-csv")
                                .label(lang.text(TextKey::ImportSavePageCsv))
                                .disabled(busy || self.export_busy)
                                .on_click(cx.listener(|this, _, _, cx| {
                                    this.save_page(export::Format::Csv, cx)
                                })),
                        )
                        .child(
                            Button::new("page-save-json")
                                .label(lang.text(TextKey::ImportSavePageJson))
                                .disabled(busy || self.export_busy)
                                .on_click(cx.listener(|this, _, _, cx| {
                                    this.save_page(export::Format::Json, cx)
                                })),
                        ),
                )
                .child(div().text_size(px(typography::SUPPORTING)).child(
                    crate::studio::selectable(
                        lang.text(TextKey::ImportExportsContainOnlyThePublishedPageNot),
                    ),
                ));
        }
        if self.kind == Destination::Trace {
            body = body.child(
                div().flex().gap_1().children(
                    [
                        TraceView::Processes,
                        TraceView::Threads,
                        TraceView::Slices,
                        TraceView::Scheduling,
                    ]
                    .into_iter()
                    .map(|view| {
                        Button::new(("trace-view", view as usize))
                            .label(lang.text(view.title()))
                            .disabled(busy)
                            .on_click(cx.listener(move |this, _, _, cx| this.trace_view(view, cx)))
                    }),
                ),
            );
        }
        if let Some(path) = &self.path {
            body = body.child(
                div()
                    .text_size(px(typography::SUPPORTING))
                    .child(crate::studio::selectable(path.display().to_string())),
            );
        }
        if busy {
            body = body.child(crate::studio::selectable(
                lang.text(TextKey::ImportLoadingQuerying),
            ));
        }
        if let Some(page) = &self.page {
            body = body.child(
                div()
                    .text_size(px(typography::TITLE))
                    .child(page.display_summary(lang)),
            );
            body = body.child(
                div()
                    .flex()
                    .gap_2()
                    .items_center()
                    .child(lang.format(
                        TextKey::ImportUiPageRange,
                        &[
                            ("first", &(page.offset + usize::from(!page.rows.is_empty()))),
                            ("last", &(page.offset + page.rows.len())),
                        ],
                    ))
                    .child(
                        Button::new("artifact-prev")
                            .label(lang.text(TextKey::CommonPrevious))
                            .disabled(busy || page.offset == 0)
                            .on_click(cx.listener(|this, _, _, cx| {
                                this.page(
                                    this.page
                                        .as_ref()
                                        .map(|p| p.offset.saturating_sub(PAGE))
                                        .unwrap_or(0),
                                    cx,
                                )
                            })),
                    )
                    .child(
                        Button::new("artifact-next")
                            .label(lang.text(TextKey::CommonNext))
                            .disabled(busy || !page.has_more)
                            .on_click(cx.listener(|this, _, _, cx| {
                                this.page(
                                    this.page
                                        .as_ref()
                                        .map(|p| p.offset.saturating_add(PAGE))
                                        .unwrap_or(0),
                                    cx,
                                )
                            })),
                    ),
            );
            body = body.child(
                div()
                    .text_size(px(typography::SUPPORTING))
                    .child(page.display_columns(lang)),
            );
            body = body.child(
                div()
                    .flex()
                    .flex_col()
                    .children(page.rows.iter().enumerate().map(|(index, _row)| {
                        div()
                            .flex()
                            .gap_2()
                            .p_1()
                            .border_b_1()
                            .border_color(cx.theme().border)
                            .child(
                                div()
                                    .flex_1()
                                    .min_w_0()
                                    .child(page.display_row(index, lang)),
                            )
                    })),
            );
            body = body.child(
                div()
                    .text_size(px(typography::SUPPORTING))
                    .text_color(cx.theme().muted_foreground)
                    .children(
                        page.warnings
                            .iter()
                            .enumerate()
                            .take(5)
                            .map(|(index, _)| div().child(page.display_warning(index, lang))),
                    ),
            );
        } else if !busy {
            body = body.child(crate::studio::selectable(
                lang.text(TextKey::ImportSelectALocalArtifactThisPageIs),
            ));
        }
        body.into_any_element()
    }
}

impl ImportWorkspace {
    fn render_trace_workspace(&mut self, cx: &mut Context<Self>) -> AnyElement {
        let lang = self.language;
        let busy = self.busy;
        let current_view = self.trace_view;
        let border = cx.theme().border;
        let muted = cx.theme().muted_foreground;
        let page = self.page.clone();
        let selected_row = self.selected_trace_row;
        let mut root = div()
            .id("trace-analyzer-workspace")
            .size_full()
            .min_h_0()
            .flex()
            .flex_col()
            .bg(cx.theme().background)
            .text_color(cx.theme().foreground)
            .child(
                div()
                    .id("trace-analyzer-header")
                    .h(px(40.))
                    .flex_shrink_0()
                    .flex()
                    .items_center()
                    .gap_2()
                    .px_3()
                    .border_b_1()
                    .border_color(border)
                    .overflow_x_scroll()
                    .child(
                        Button::new("trace-import")
                            .primary()
                            .label(lang.text(TextKey::ImportImportLocalArtifact))
                            .disabled(busy)
                            .on_click(cx.listener(|this, _, _, cx| this.pick(cx))),
                    )
                    .child(
                        Button::new("trace-cancel")
                            .label(lang.text(TextKey::ImportCancelOperation))
                            .disabled(!busy)
                            .on_click(cx.listener(|this, _, _, cx| this.cancel_work(cx))),
                    )
                    .child(
                        Button::new("trace-close")
                            .label(lang.text(TextKey::CommonClose))
                            .on_click(cx.listener(|this, _, _, cx| this.close(cx))),
                    )
                    .child(div().flex_1())
                    .child(
                        Button::new("trace-capture")
                            .label(if lang == Language::SimplifiedChinese {
                                "实时采集尚未接入"
                            } else {
                                "Live capture unavailable"
                            })
                            .disabled(true),
                    ),
            );
        if let Some(error) = &self.error {
            root = root.child(
                div()
                    .flex_shrink_0()
                    .p_2()
                    .text_color(cx.theme().danger)
                    .child(error.render(lang)),
            );
        }
        let mut center = div()
            .id("trace-analyzer-center")
            .flex_1()
            .min_w_0()
            .h_full()
            .flex()
            .flex_col()
            .gap_2()
            .p_3()
            .overflow_y_scroll();
        center = center.child(div().flex().flex_wrap().gap_2().children(
            TraceView::ALL.into_iter().map(|view| {
                Button::new(("trace-view", view as usize))
                    .label(lang.text(view.title()))
                    .selected(view == current_view)
                    .disabled(busy)
                    .on_click(cx.listener(move |this, _, _, cx| this.trace_view(view, cx)))
            }),
        ));
        if let Some(page) = &page {
            center =
                center
                    .child(
                        div()
                            .text_size(px(typography::TITLE))
                            .child(page.display_summary(lang)),
                    )
                    .child(
                        div().flex().flex_wrap().gap_2().children(
                            [
                                format!(
                                    "{}: {}",
                                    lang.text(TextKey::ImportUiTraceSource),
                                    self.path
                                        .as_ref()
                                        .map_or("—".into(), |path| path.display().to_string())
                                ),
                                format!(
                                    "{}: {}",
                                    lang.text(TextKey::ImportUiTraceQuery),
                                    lang.text(current_view.title())
                                ),
                                lang.text(TextKey::ImportUiTraceOneShotQuery).to_owned(),
                            ]
                            .into_iter()
                            .map(|value| {
                                div()
                                    .p_2()
                                    .bg(cx.theme().muted)
                                    .rounded_md()
                                    .child(crate::studio::selectable(value))
                            }),
                        ),
                    )
                    .child(
                        div()
                            .flex()
                            .items_center()
                            .gap_2()
                            .child(lang.format(
                                TextKey::ImportUiPageRange,
                                &[
                                    ("first", &(page.offset + usize::from(!page.rows.is_empty()))),
                                    ("last", &(page.offset + page.rows.len())),
                                ],
                            ))
                            .child(
                                Button::new("trace-prev")
                                    .label(lang.text(TextKey::CommonPrevious))
                                    .disabled(busy || page.offset == 0)
                                    .on_click(cx.listener(|this, _, _, cx| {
                                        this.page(
                                            this.page
                                                .as_ref()
                                                .map(|page| page.offset.saturating_sub(PAGE))
                                                .unwrap_or(0),
                                            cx,
                                        )
                                    })),
                            )
                            .child(
                                Button::new("trace-next")
                                    .label(lang.text(TextKey::CommonNext))
                                    .disabled(busy || !page.has_more)
                                    .on_click(cx.listener(|this, _, _, cx| {
                                        this.page(
                                            this.page
                                                .as_ref()
                                                .map(|page| page.offset.saturating_add(PAGE))
                                                .unwrap_or(0),
                                            cx,
                                        )
                                    })),
                            ),
                    )
                    .child(
                        div()
                            .text_size(px(typography::SUPPORTING))
                            .text_color(muted)
                            .child(page.display_columns(lang)),
                    )
                    .child(div().flex().flex_col().gap_1().children(
                        page.rows.iter().enumerate().map(|(index, _)| {
                            Button::new(("trace-result-row", index))
                                .ghost()
                                .selected(selected_row == Some(index))
                                .w_full()
                                .justify_start()
                                .label(page.display_row(index, lang))
                                .on_click(cx.listener(move |this, _, _, cx| {
                                    this.selected_trace_row = Some(index);
                                    cx.notify();
                                }))
                        }),
                    ));
        } else if !busy {
            center = center.child(
                div()
                    .flex_1()
                    .flex()
                    .items_center()
                    .justify_center()
                    .text_color(muted)
                    .child(crate::studio::selectable(
                        lang.text(TextKey::ImportSelectALocalArtifactThisPageIs),
                    )),
            );
        }
        let sidebar = div()
            .id("trace-sessions-sidebar")
            .w(px(220.))
            .h_full()
            .flex_shrink_0()
            .flex()
            .flex_col()
            .gap_2()
            .p_3()
            .border_r_1()
            .border_color(border)
            .child(div().text_size(px(typography::TITLE)).child(
                if lang == Language::SimplifiedChinese {
                    "会话"
                } else {
                    "Sessions"
                },
            ))
            .child(
                div()
                    .text_color(muted)
                    .child(self.path.as_ref().map_or_else(
                        || {
                            if lang == Language::SimplifiedChinese {
                                "未打开 Trace".to_owned()
                            } else {
                                "No active trace".to_owned()
                            }
                        },
                        |path| path.display().to_string(),
                    )),
            )
            .child(div().h(px(1.)).w_full().bg(border))
            .child(
                div()
                    .text_color(muted)
                    .child(if lang == Language::SimplifiedChinese {
                        "书签"
                    } else {
                        "Bookmarks"
                    }),
            )
            .child(
                div()
                    .text_color(muted)
                    .child(if lang == Language::SimplifiedChinese {
                        "暂无书签"
                    } else {
                        "No bookmarks"
                    }),
            );
        let details = div()
            .id("trace-details")
            .w(px(280.))
            .h_full()
            .flex_shrink_0()
            .flex()
            .flex_col()
            .border_l_1()
            .border_color(border)
            .child(
                div()
                    .flex()
                    .gap_1()
                    .p_2()
                    .children(TraceDetailTab::ALL.into_iter().map(|tab| {
                        Button::new(("trace-detail-tab", tab as usize))
                            .label(tab.label(lang))
                            .selected(tab == self.trace_detail_tab)
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.trace_detail_tab = tab;
                                cx.notify();
                            }))
                    })),
            );
        let detail_text = if let Some(index) =
            selected_row.and_then(|index| page.as_ref().and_then(|page| page.rows.get(index)))
        {
            format!(
                "{}\n\n{}",
                if lang == Language::SimplifiedChinese {
                    "选中结果"
                } else {
                    "Selected result"
                },
                index
                    .iter()
                    .enumerate()
                    .map(|(column, value)| format!(
                        "{}: {}",
                        page.as_ref()
                            .and_then(|page| page.columns.get(column))
                            .cloned()
                            .unwrap_or_else(|| format!("Column {column}")),
                        value.as_str()
                    ))
                    .collect::<Vec<_>>()
                    .join("\n")
            )
        } else {
            if lang == Language::SimplifiedChinese {
                "选择一行查看结果详情".to_owned()
            } else {
                "Select a row to inspect result details".to_owned()
            }
        };
        let details = details.child(
            div()
                .flex_1()
                .min_h_0()
                .overflow_y_scrollbar()
                .p_3()
                .text_color(muted)
                .child(detail_text),
        );
        root.child(
            div()
                .id("trace-analyzer-body")
                .flex_1()
                .min_h_0()
                .flex()
                .child(sidebar)
                .child(center)
                .child(details),
        )
        .child(
            div()
                .h(px(29.))
                .flex_shrink_0()
                .flex()
                .items_center()
                .px_3()
                .border_t_1()
                .border_color(border)
                .text_color(muted)
                .child(
                    page.as_ref()
                        .map_or_else(|| "No trace".to_owned(), |page| page.summary.clone()),
                ),
        )
        .into_any_element()
    }

    fn render_specialized(&mut self, window: &mut Window, cx: &mut Context<Self>) -> AnyElement {
        if self.kind == Destination::Memory {
            return self.render_memory_profiler(window, cx);
        }
        let language = self.language;
        let busy = self.busy;
        let mut root = div()
            .id(if self.kind == Destination::Memory {
                "memory-profiler-workspace"
            } else {
                "frame-profiler-workspace"
            })
            .size_full()
            .min_h_0()
            .flex()
            .flex_col()
            .gap_3()
            .p_4()
            .bg(cx.theme().background)
            .text_color(cx.theme().foreground);
        if let Some(notice) = &self.notice {
            root = root.child(
                div()
                    .text_color(cx.theme().muted_foreground)
                    .child(notice.render(language)),
            );
        }
        if let Some(error) = &self.error {
            root = root.child(
                div()
                    .p_3()
                    .text_color(cx.theme().danger)
                    .child(error.render(language)),
            );
        }
        if busy {
            root = root.child(div().text_color(cx.theme().muted_foreground).child(
                crate::studio::selectable(language.text(TextKey::ImportLoadingQuerying)),
            ));
        }
        if self.kind == Destination::Frame {
            root = root.child(self.render_frame_workspace(cx));
        }
        root.into_any_element()
    }
}

impl Drop for ImportWorkspace {
    fn drop(&mut self) {
        self.cancel.cancel();
    }
}

fn optional<T: std::fmt::Display>(value: Option<T>) -> String {
    value
        .map(|value| value.to_string())
        .unwrap_or_else(|| "unavailable".into())
}

fn optional_ui<T: std::fmt::Display>(value: Option<T>) -> UiText {
    value.map_or_else(
        || TextKey::ImportUiUnavailable.into(),
        |value| value.to_string().into(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[::core::prelude::v1::test]
    fn workspace_starts_without_implicit_artifact_or_evidence() {
        let workspace = ImportWorkspace::new(Destination::Network, Language::English);
        assert!(workspace.session_id.is_none());
        assert!(workspace.page.is_none());
        assert!(workspace.analysis_request().is_err());
        assert!(workspace.selected_network.is_none());
    }

    #[::core::prelude::v1::test]
    fn memory_ai_request_requires_a_published_snapshot() {
        let mut workspace = ImportWorkspace::new(Destination::Memory, Language::English);
        workspace.session_id = Some("test-heap".into());
        workspace.generation = 3;
        assert!(workspace.analysis_request().is_err());
    }

    #[::core::prelude::v1::test]
    fn imported_page_ai_request_uses_real_generation_and_no_local_path() {
        let mut workspace = ImportWorkspace::new(Destination::Network, Language::English);
        workspace.session_id = Some("network-evidence".into());
        workspace.generation = 7;
        workspace.path = Some(PathBuf::from("/private/selected.har"));
        workspace.page = Some(PageData {
            ui: PageUi::default(),
            summary: "2 requests · minimized".into(),
            columns: vec!["Method".into(), "URL".into()],
            rows: vec![vec!["GET".into(), aps_network::MINIMIZED_URL.into()]],
            offset: 0,
            has_more: true,
            warnings: Vec::new(),
            row_actions: Vec::new(),
        });
        let request = workspace.analysis_request().unwrap();
        assert_eq!(request.session_id, "network-evidence");
        assert_eq!(request.generation, 7);
        assert!(request.candidates.is_empty());
        assert!(!format!("{:?}", request.evidence[0].payload).contains("/private/selected.har"));
        workspace.busy = true;
        assert!(workspace.analysis_request().is_err());
    }

    #[::core::prelude::v1::test]
    fn new_import_session_identifiers_do_not_reuse_identity() {
        let first = new_session_id();
        let second = new_session_id();
        assert_ne!(first, second);
        assert!(first.len() < 100);
        assert!(
            first
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || b == b'-')
        );
    }
}

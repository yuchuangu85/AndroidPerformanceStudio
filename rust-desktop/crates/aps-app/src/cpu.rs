use crate::notifications::{NoticeSnapshot, NoticeSource};
use crate::shell::menubar::MenuGroup;
use crate::studio::{self, StudioColors};
use aps_contracts::{
    ArtifactCompleteness, ArtifactId, Capabilities, CapabilityId, CaptureArtifact, ClockDomain,
    ClockDomainId, ClockKind, ProfilerSession, ProfilerSessionId, Provenance, ProvenanceAction,
    ProvenanceStep, SourceDigest,
};
use aps_cpu::{
    ChartBlock, ChartBlockKind, ChartDomain, ChartFrame, ChartViewport, CpuSession, Direction,
    FullCallTree, MAX_FULL_TREE_ROWS, ProfileSummary, Query, QueryPage, QueryRow, ThreadInfo,
    TimelineViewport, TopFunctionsPage, TopSort, TransformSet, WeightType,
};
use aps_platform::{CancellationToken, ProcessRequest, ProcessRunner, TempWorkspace};
use aps_storage::{PageRequest, QueryBinding, Storage, StoredSession};
use aps_ui_foundation::{Language, TextKey};
use gpui_kit::{
    component::{
        VirtualListScrollHandle,
        input::{Input, InputState},
        menu::{DropdownMenu, PopupMenuItem},
        scroll::{Scrollbar, ScrollbarAxis},
        v_virtual_list,
    },
    prelude::*,
    *,
};
use std::{
    collections::BTreeSet,
    fs::File,
    hash::{Hash, Hasher},
    io::{BufReader, Cursor, Read, Write},
    path::{Path, PathBuf},
    rc::Rc,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, Ordering},
    },
    time::{Duration, SystemTime, UNIX_EPOCH},
};

const PAGE_SIZE: usize = 128;
const RECENT_PAGE_SIZE: usize = 20;
pub(crate) const CPU_MEDIA_TYPE: &str = "application/x-aps-simpleperf-protobuf";
pub(crate) const CPU_SESSION_PREFIX: &str = "cpu-import-v1:";
/// One bounded probe classifies a source by content: it covers the SIMPLEPERF
/// and perf.data magic and the leading meta/threads keys of a Gecko JSON profile.
const CPU_SOURCE_PROBE_BYTES: usize = 64 * 1024;
const SIMPLEPERF_MAGIC: &[u8; 10] = b"SIMPLEPERF";
/// Chart packets are rendered at a fixed logical width and scrolled/painted by
/// the workspace; the engine viewport is independent of the window width.
const CHART_WIDTH_PX: f32 = 1024.0;
const TIMELINE_WIDTH_PX: f32 = 1024.0;
const CHART_ROW_HEIGHT_PX: f32 = 18.0;
const TIMELINE_BUCKETS: usize = 160;
const TIMELINE_MAX_TRACKS: usize = 24;
const KPI_MAX_COLUMNS: usize = 6;
const KPI_MEDIUM_COLUMNS: usize = 3;
const KPI_NARROW_COLUMNS: usize = 2;
const CALLOUT_TOP_FUNCTIONS: usize = 8;
const OVERVIEW_ROW_LIMIT: usize = 8;
const MAX_CHART_LABELS: usize = 400;
const NAVIGATION_WIDTH_ESTIMATE: f32 = 216.0;

// Only application-owned failures carry resource keys. Engine diagnostics stay
// verbatim, even when their text happens to match an application message.
#[derive(Clone, Debug)]
pub(crate) enum CpuError {
    Local(TextKey),
    Diagnostic(String),
}

impl CpuError {
    pub(crate) fn display(&self, language: Language) -> String {
        match self {
            Self::Local(key) => language.text(*key).to_owned(),
            Self::Diagnostic(message) => message.clone(),
        }
    }
}

impl From<String> for CpuError {
    fn from(message: String) -> Self {
        Self::Diagnostic(message)
    }
}

impl From<&str> for CpuError {
    fn from(message: &str) -> Self {
        Self::Diagnostic(message.to_owned())
    }
}

impl std::ops::Deref for CpuError {
    type Target = str;

    fn deref(&self) -> &str {
        match self {
            Self::Local(key) => Language::English.text(*key),
            Self::Diagnostic(message) => message,
        }
    }
}

impl std::fmt::Display for CpuError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(self)
    }
}
const TREE_ROW_HEIGHT: f32 = 16.0;

/// Firefox-style transform operations applied to the flame graph and call tree.
#[derive(Clone, Debug, PartialEq, Eq)]
enum TransformOp {
    Focus(String),
    Merge(String),
    Collapse(String),
    Drop(String),
    CollapseRecursion,
}

#[derive(Clone, Default)]
struct DisassemblyPanel {
    symbol: String,
    binary: Option<PathBuf>,
    tool: Option<PathBuf>,
    lines: Vec<String>,
    error: Option<String>,
}
/// Standalone perf.data inputs are converted with a host simpleperf before
/// parsing; both the raw input and converted stream are byte-bounded.
const MAX_PERF_DATA_INPUT_BYTES: u64 = 512 * 1024 * 1024;
const PERF_DATA_CONVERSION_TIMEOUT: Duration = Duration::from_secs(180);

/// Export format chosen from the toolbar dropdown or the File > Export menu.
#[derive(Clone, Copy, PartialEq, Eq)]
pub(crate) enum ExportKind {
    ReportJson,
    Folded,
    Html,
    Pprof,
    SessionPackage,
}

impl ExportKind {
    fn label(self, language: Language) -> &'static str {
        match self {
            Self::ReportJson => language.text(TextKey::CpuWorkspaceExportReportJson),
            Self::Folded => language.text(TextKey::CpuWorkspaceExportFolded),
            Self::Html => language.text(TextKey::CpuWorkspaceExportHtml),
            Self::Pprof => language.text(TextKey::CpuWorkspaceExportPprof),
            Self::SessionPackage => language.text(TextKey::CpuWorkspaceExportSessionPackage),
        }
    }

    fn saved_message(self, language: Language) -> &'static str {
        match self {
            Self::ReportJson => language.text(TextKey::CpuWorkspaceExportJsonSaved),
            Self::Folded => language.text(TextKey::CpuWorkspaceExportFoldedSaved),
            Self::Html => language.text(TextKey::CpuWorkspaceExportHtmlSaved),
            Self::Pprof => language.text(TextKey::CpuWorkspaceExportPprofSaved),
            Self::SessionPackage => language.text(TextKey::CpuWorkspaceExportSessionSaved),
        }
    }
}

/// Report tabs in the commercial CPU layout order. Marker projections are not
/// produced by the Simpleperf protobuf engine, so those tabs keep their layout
/// and show the shared "not collected" empty state.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum CpuTab {
    Overview,
    FlameGraph,
    CallTree,
    TopFunctions,
    StackChart,
    MarkerChart,
    MarkerTable,
}

impl CpuTab {
    const ALL: [Self; 7] = [
        Self::Overview,
        Self::FlameGraph,
        Self::CallTree,
        Self::TopFunctions,
        Self::StackChart,
        Self::MarkerChart,
        Self::MarkerTable,
    ];

    fn label(self, language: Language) -> &'static str {
        match self {
            Self::Overview => language.text(TextKey::CpuTabOverview),
            Self::FlameGraph => language.text(TextKey::CpuFlameGraph),
            Self::CallTree => language.text(TextKey::CpuCallTree),
            Self::TopFunctions => language.text(TextKey::CpuTopFunctions),
            Self::StackChart => language.text(TextKey::CpuTabStackChart),
            Self::MarkerChart => language.text(TextKey::CpuTabMarkerChart),
            Self::MarkerTable => language.text(TextKey::CpuTabMarkerTable),
        }
    }

    fn is_call_stack(self) -> bool {
        matches!(self, Self::FlameGraph | Self::CallTree)
    }

    fn needs_query(self) -> bool {
        matches!(
            self,
            Self::FlameGraph | Self::CallTree | Self::TopFunctions | Self::StackChart
        )
    }
}

#[derive(Clone)]
struct Catalog {
    summary: ProfileSummary,
    threads: Vec<ThreadInfo>,
    events: Vec<String>,
    source_label: String,
    source_digest: SourceDigest,
    byte_length: u64,
}

pub struct CpuWorkspace {
    follow_tooltip: bool,
    language: Language,
    metadata_path: Option<PathBuf>,
    snapshot_root: Option<PathBuf>,
    metadata_error: Option<CpuError>,
    association: Option<QueryBinding>,
    recent: Option<RecentCpuPage>,
    recent_busy: bool,
    recent_generation: u64,
    import_cancel: Arc<AtomicBool>,
    session: Option<Arc<Mutex<CpuSession>>>,
    catalog: Option<Catalog>,
    tab: CpuTab,
    top: Option<TopFunctionsPage>,
    /// Top functions for the KPI card and Overview list, refreshed with the
    /// shared timeline instead of once per page.
    overview_top: Option<TopFunctionsPage>,
    chart: Option<ChartFrame>,
    timeline: Option<ChartFrame>,
    chart_selected: Option<String>,
    row_selected: Option<String>,
    /// Symbol requested by a cross-tab focus action; resolved after the next
    /// projection publishes its rows or chart blocks.
    pending_focus: Option<String>,
    /// Hovered chart payload for the floating facts card.
    chart_hover: Option<String>,
    top_sort: TopSort,
    top_descending: bool,
    /// In-flight chart drag: (start fraction, current fraction) of the plot width.
    chart_drag: Option<(f32, f32)>,
    /// Hover position relative to the chart viewport, for the facts card.
    chart_hover_position: Option<(f32, f32)>,
    /// Scroll state of the flame/stack chart surface; the facts card uses its
    /// viewport bounds to choose the visible side of the pointer.
    chart_scroll: ScrollHandle,
    /// Open chart context menu: (x, y, block id).
    chart_menu: Option<(f32, f32, String)>,
    /// Keyboard navigation state for the call-tree table.
    tree: Option<FullCallTree>,
    tree_generation: u64,
    tree_visible: Rc<Vec<usize>>,
    tree_sizes: Rc<Vec<Size<Pixels>>>,
    tree_content_width: f32,
    tree_cache_key: Option<(u64, u64, String)>,
    /// Vertical scroll of the call-tree rows; the virtual list owns the axis.
    tree_scroll: VirtualListScrollHandle,
    /// Horizontal scroll shared by the call-tree header and rows.
    tree_h_scroll: ScrollHandle,
    /// Vertical scroll of the top-functions rows.
    top_scroll: ScrollHandle,
    tree_focus: FocusHandle,
    page_focus: FocusHandle,
    /// Optional host simpleperf executable for standalone perf.data import.
    converter: Option<PathBuf>,
    /// Applied flame/call-tree transforms, in application order.
    transforms: Vec<TransformOp>,
    disassembly: Option<DisassemblyPanel>,
    disassembly_busy: bool,
    disassembly_generation: u64,
    disassembly_cancel: Arc<AtomicBool>,
    /// Optional folder containing binary_cache/ or symbols/ artifacts for
    /// disassembly. Defaults to the Rust data root.
    binary_root: Option<PathBuf>,
    collapsed: BTreeSet<String>,
    details_visible: bool,
    timeline_height: f32,
    timeline_drag: Option<(f32, f32)>,
    export_message: Option<String>,
    zoom_start: f64,
    zoom_end: f64,
    query: Query,
    busy: bool,
    shared_busy: bool,
    generation: u64,
    shared_generation: u64,
    error: Option<CpuError>,
    search: Entity<InputState>,
    start: Entity<InputState>,
    end: Entity<InputState>,
    studio: Option<WeakEntity<crate::shell::Studio>>,
}

impl CpuWorkspace {
    pub fn set_follow_tooltip(&mut self, follow: bool, cx: &mut Context<Self>) {
        if self.follow_tooltip != follow {
            self.follow_tooltip = follow;
            cx.notify();
        }
    }

    pub fn new(
        language: Language,
        metadata_path: Option<PathBuf>,
        snapshot_root: Option<PathBuf>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Self {
        Self {
            follow_tooltip: false,
            language,
            metadata_path,
            snapshot_root,
            metadata_error: None,
            association: None,
            recent: None,
            recent_busy: false,
            recent_generation: 0,
            import_cancel: Arc::new(AtomicBool::new(false)),
            session: None,
            catalog: None,
            tab: CpuTab::Overview,
            top: None,
            overview_top: None,
            chart: None,
            timeline: None,
            chart_selected: None,
            row_selected: None,
            pending_focus: None,
            chart_hover: None,
            top_sort: TopSort::SelfWeight,
            top_descending: true,
            chart_drag: None,
            chart_hover_position: None,
            chart_scroll: ScrollHandle::new(),
            chart_menu: None,
            tree: None,
            tree_generation: 0,
            tree_visible: Rc::new(Vec::new()),
            tree_sizes: Rc::new(Vec::new()),
            tree_content_width: 0.0,
            tree_cache_key: None,
            tree_scroll: VirtualListScrollHandle::new(),
            tree_h_scroll: ScrollHandle::new(),
            top_scroll: ScrollHandle::new(),
            tree_focus: cx.focus_handle(),
            page_focus: cx.focus_handle(),
            converter: None,
            transforms: Vec::new(),
            disassembly: None,
            disassembly_busy: false,
            disassembly_generation: 0,
            disassembly_cancel: Arc::new(AtomicBool::new(false)),
            binary_root: None,
            collapsed: BTreeSet::new(),
            details_visible: true,
            timeline_height: studio::metrics::TIMELINE_DEFAULT_HEIGHT,
            timeline_drag: None,
            export_message: None,
            zoom_start: 0.0,
            zoom_end: 1.0,
            query: Query {
                limit: PAGE_SIZE,
                ..Default::default()
            },
            busy: false,
            shared_busy: false,
            generation: 0,
            shared_generation: 0,
            error: None,
            search: cx.new(|cx| {
                InputState::new(window, cx)
                    .placeholder(language.text(TextKey::CpuSearchSymbolsPlaceholder))
            }),
            start: cx.new(|cx| {
                InputState::new(window, cx)
                    .placeholder(language.text(TextKey::CpuStartNsOptionalPlaceholder))
            }),
            end: cx.new(|cx| {
                InputState::new(window, cx)
                    .placeholder(language.text(TextKey::CpuEndNsOptionalPlaceholder))
            }),
            studio: None,
        }
    }

    /// The shell injects its own weak handle so the page-level "Run AI
    /// Analysis" action can open the shared AI preflight exactly like the
    /// workspace header action does.
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
        self.search.update(cx, |state, cx| {
            state.set_placeholder(
                language.text(TextKey::CpuSearchSymbolsPlaceholder),
                window,
                cx,
            )
        });
        self.start.update(cx, |state, cx| {
            state.set_placeholder(
                language.text(TextKey::CpuStartNsOptionalPlaceholder),
                window,
                cx,
            )
        });
        self.end.update(cx, |state, cx| {
            state.set_placeholder(
                language.text(TextKey::CpuEndNsOptionalPlaceholder),
                window,
                cx,
            )
        });
        cx.notify();
    }
    pub fn open_artifact(&mut self, path: PathBuf, digest: SourceDigest, cx: &mut Context<Self>) {
        self.open_path(path, None, Some(digest), cx);
    }
    /// Debug-only verification seam: open a picked CPU source on launch without
    /// driving the platform file picker.
    pub(crate) fn open_debug_source(&mut self, path: PathBuf, cx: &mut Context<Self>) {
        self.open_path(path, None, None, cx);
    }
    pub fn analysis_request(&self) -> Result<aps_ai::AnalysisRequest, String> {
        use crate::evidence::{clipped, object, request};
        if self.busy {
            return Err(self.language.text(TextKey::ProfilerCpuWaitQuery).into());
        }
        let catalog = self.catalog.as_ref().ok_or_else(|| {
            self.language
                .text(TextKey::ProfilerCpuImportFirst)
                .to_owned()
        })?;
        let rows = if let Some(page) = &self.top {
            page.rows
                .iter()
                .take(12)
                .map(|row| {
                    object([
                        ("symbol", clipped(&row.name, 512).into()),
                        ("inclusiveWeight", row.inclusive_weight.into()),
                        ("selfWeight", row.self_weight.into()),
                        ("sampleCount", row.sample_count.into()),
                    ])
                })
                .collect::<Vec<_>>()
        } else if let Some(tree) = &self.tree {
            tree.rows
                .iter()
                .take(12)
                .map(|row| {
                    object([
                        ("symbol", clipped(&row.name, 512).into()),
                        ("depth", row.depth.into()),
                        ("inclusiveWeight", row.inclusive_weight.into()),
                        ("selfWeight", row.self_weight.into()),
                        ("sampleCount", row.sample_count.into()),
                    ])
                })
                .collect::<Vec<_>>()
        } else if let Some(chart) = &self.chart {
            chart
                .blocks
                .iter()
                .filter(|b| self.chart_selected.as_ref().is_none_or(|id| id == &b.id))
                .take(12)
                .map(|b| {
                    object([
                        ("symbol", clipped(&b.label, 512).into()),
                        ("blockKind", format!("{:?}", b.kind).into()),
                        ("sampleCount", b.sample_count.into()),
                        ("sourceCount", b.source_count.into()),
                        ("inclusiveWeight", b.inclusive_weight.into()),
                    ])
                })
                .collect::<Vec<_>>()
        } else {
            return Err(self.language.text(TextKey::ProfilerCpuNoSelection).into());
        };
        request(
            "cpu",
            "Current CPU query; at most 12 visible rows/blocks; symbols but no paths. Samples are not method duration.",
            &format!(
                "{} samples, {} lost samples, {} threads",
                catalog.summary.sample_count,
                catalog.summary.lost_sample_count,
                catalog.summary.thread_count
            ),
            object([
                ("sourceDigest", catalog.source_digest.to_hex().into()),
                ("queryGeneration", self.generation.into()),
                ("view", format!("{:?}", self.tab).into()),
                ("direction", self.query.direction.as_str().into()),
                ("weightType", self.query.weight_type.as_str().into()),
                (
                    "startNanos",
                    self.query.start.map_or(aps_json::Value::Null, Into::into),
                ),
                (
                    "endNanos",
                    self.query.end.map_or(aps_json::Value::Null, Into::into),
                ),
                ("rows", aps_json::Value::Array(rows)),
            ]),
        )
    }
    fn pick_converter(&mut self, cx: &mut Context<Self>) {
        if self.busy {
            return;
        }
        let language = self.language;
        let picker = cx.prompt_for_paths(PathPromptOptions {
            files: true,
            directories: false,
            multiple: false,
            prompt: Some(language.text(TextKey::CpuSelectSimpleperf).into()),
        });
        cx.spawn(async move |this, cx| {
            if let Ok(Ok(Some(paths))) = picker.await {
                if let Some(path) = paths.into_iter().next() {
                    this.update(cx, |this, cx| {
                        if executable_file(&path) {
                            this.converter = Some(path);
                            this.error = None;
                        } else {
                            this.error = Some(CpuError::Diagnostic(
                                "Selected simpleperf must be an executable file".into(),
                            ));
                        }
                        cx.notify();
                    })
                    .ok();
                }
            }
        })
        .detach();
        cx.notify();
    }

    pub fn pick_file(&mut self, cx: &mut Context<Self>) {
        self.pick_source(None, cx);
    }
    fn pick_source(&mut self, recent: Option<RecentCpu>, cx: &mut Context<Self>) {
        if self.busy {
            return;
        }
        self.generation += 1;
        let generation = self.generation;
        self.busy = true;
        self.error = None;
        let prompt = if recent.is_some() {
            self.language
                .text(TextKey::CpuSelectTheSourceFileForThisSaved)
        } else {
            self.language.text(TextKey::CpuImportSimpleperfProtobuf)
        };
        let picker = cx.prompt_for_paths(PathPromptOptions {
            files: true,
            directories: false,
            multiple: false,
            prompt: Some(prompt.into()),
        });
        cx.spawn(async move |this, cx| {
            let result = picker.await;
            this.update(cx, |this, cx| {
                if this.generation != generation {
                    return;
                }
                this.busy = false;
                match result {
                    Ok(Ok(Some(paths))) if paths.len() == 1 => {
                        this.open_path(paths[0].clone(), recent, None, cx)
                    }
                    Ok(Err(error)) => this.error = Some(error.to_string().into()),
                    _ => {}
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }
    fn open_path(
        &mut self,
        path: PathBuf,
        recent: Option<RecentCpu>,
        expected: Option<SourceDigest>,
        cx: &mut Context<Self>,
    ) {
        if self.busy {
            return;
        }
        self.import_cancel.store(true, Ordering::Release);
        self.import_cancel = Arc::new(AtomicBool::new(false));
        let cancel = self.import_cancel.clone();
        self.generation += 1;
        let generation = self.generation;
        self.busy = true;
        self.error = None;
        self.export_message = None;
        self.metadata_error = None;
        let metadata_path = self.metadata_path.clone();
        let snapshot_root = self.snapshot_root.clone();
        // A standalone perf.data record is converted to the SIMPLEPERF protobuf
        // stream with an explicitly resolved host simpleperf. Conversion runs
        // under the bounded process runner and the result is persisted by digest.
        // A gzip-wrapped profile carries either transport, so it is decompressed
        // and then follows the same route. Anything else is classified before the
        // engine so a non-profile file gets an actionable message instead of
        // "invalid SIMPLEPERF magic".
        let class = match classify_cpu_source(&path) {
            Ok(class) => class,
            Err(error) => {
                self.busy = false;
                self.error = Some(error);
                cx.notify();
                return;
            }
        };
        if let Some(error) = class.rejection() {
            self.busy = false;
            self.error = Some(error);
            cx.notify();
            return;
        }
        let converter = if class.kind == CpuSourceKind::PerfData {
            match resolve_simpleperf(self.converter.as_deref()) {
                Ok(converter) => Some(converter),
                Err(error) => {
                    self.busy = false;
                    self.error = Some(error);
                    cx.notify();
                    return;
                }
            }
        } else {
            None
        };
        let task = cx.background_executor().spawn(async move {
            // Reopen requires an existing digest/generation association before
            // touching the explicitly reselected file, never a stored locator.
            if let Some(recent) = &recent {
                let path = metadata_path
                    .as_deref()
                    .ok_or(CpuError::Local(TextKey::ProfilerCpuMetadataNotConfigured))?;
                validate_recent(path, recent)?;
            }
            let loaded = load_cpu_import(
                &path,
                class,
                converter.as_deref(),
                recent.as_ref(),
                snapshot_root.as_deref(),
                || cancel.load(Ordering::Acquire),
            )?;
            if expected.is_some_and(|expected| loaded.digest != expected) {
                return Err(CpuError::Local(TextKey::ProfilerCpuCapturedDigestChanged));
            }
            finish_cpu_open(loaded, metadata_path.as_deref(), recent.as_ref(), || {
                cancel.load(Ordering::Acquire)
            })
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.generation != generation {
                    return;
                }
                this.busy = false;
                this.publish_open_result(result, cx);
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    fn publish_open_result(
        &mut self,
        result: Result<CpuOpenResult, CpuError>,
        cx: &mut Context<Self>,
    ) {
        match result {
            Ok((session, catalog, query, _page, association, metadata_error)) => {
                self.session = Some(session);
                self.catalog = Some(catalog);
                self.query = query;
                self.tab = CpuTab::Overview;
                self.zoom_start = 0.0;
                self.zoom_end = 1.0;
                self.tree = None;
                self.tree_generation = self.tree_generation.wrapping_add(1);
                self.tree_visible = Rc::new(Vec::new());
                self.tree_content_width = 0.0;
                self.tree_sizes = Rc::new(Vec::new());
                self.tree_cache_key = None;
                self.top = None;
                self.overview_top = None;
                self.chart = None;
                self.timeline = None;
                self.row_selected = None;
                self.chart_selected = None;
                self.collapsed.clear();
                self.transforms.clear();
                self.association = association;
                self.metadata_error = metadata_error;
                if self.association.is_some() {
                    self.refresh_recent(0, cx);
                }
                self.refresh_shared(cx);
                self.reload(cx);
            }
            Err(error) => self.error = Some(error.into()),
        }
    }
    /// Home discovery reads metadata only. Explicit activation repeats the
    /// original binding and full saved-snapshot digest checks before import.
    pub fn open_home_recent(
        &mut self,
        item: crate::home_recent::HomeRecentCpu,
        cx: &mut Context<Self>,
    ) {
        self.open_recent(
            RecentCpu {
                binding: item.binding,
                artifact_id: item.artifact_id,
                byte_length: item.byte_length,
                last_opened_unix_ms: 0,
            },
            cx,
        );
    }

    fn open_recent(&mut self, recent: RecentCpu, cx: &mut Context<Self>) {
        if self.busy {
            return;
        }
        let Some(snapshot_root) = self.snapshot_root.clone() else {
            self.error = Some(CpuError::Local(
                TextKey::ProfilerCpuSnapshotLocationUnavailable,
            ));
            cx.notify();
            return;
        };
        let Some(metadata_path) = self.metadata_path.clone() else {
            self.error = Some(CpuError::Local(TextKey::ProfilerCpuRustMetadataUnavailable));
            cx.notify();
            return;
        };
        self.import_cancel.store(true, Ordering::Release);
        self.import_cancel = Arc::new(AtomicBool::new(false));
        let cancel = self.import_cancel.clone();
        self.generation += 1;
        let generation = self.generation;
        self.busy = true;
        self.error = None;
        self.metadata_error = None;
        let task = cx.background_executor().spawn(async move {
            validate_recent(&metadata_path, &recent)?;
            let loaded =
                load_cpu_snapshot(&snapshot_root, &recent, || cancel.load(Ordering::Acquire))?;
            finish_cpu_open(loaded, Some(&metadata_path), Some(&recent), || {
                cancel.load(Ordering::Acquire)
            })
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.generation != generation {
                    return;
                }
                this.busy = false;
                this.publish_open_result(result, cx);
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    fn refresh_recent(&mut self, offset: usize, cx: &mut Context<Self>) {
        if self.recent_busy {
            return;
        }
        let Some(path) = self.metadata_path.clone() else {
            self.metadata_error =
                Some(CpuError::Local(TextKey::ProfilerCpuRustMetadataUnavailable));
            cx.notify();
            return;
        };
        self.recent_busy = true;
        self.recent_generation += 1;
        let generation = self.recent_generation;
        let task = cx
            .background_executor()
            .spawn(async move { recent_cpu(&path, offset) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.recent_generation != generation {
                    return;
                }
                this.recent_busy = false;
                match result {
                    Ok(page) => this.recent = Some(page),
                    Err(error) => this.metadata_error = Some(error.into()),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }
    fn apply_binding_check(&mut self, result: Option<Result<QueryBinding, CpuError>>) {
        if let Some(result) = result {
            match result {
                Ok(binding) => self.association = Some(binding),
                Err(error) => {
                    self.association = None;
                    self.metadata_error = Some(error.into());
                }
            }
        }
    }
    /// Drops every published projection while keeping the parsed session open.
    fn reset_results(&mut self) {
        self.chart_hover = None;
        self.chart_hover_position = None;
        self.chart_menu = None;
        self.tree = None;
        self.tree_generation = self.tree_generation.wrapping_add(1);
        self.tree_visible = Rc::new(Vec::new());
        self.tree_content_width = 0.0;
        self.tree_sizes = Rc::new(Vec::new());
        self.tree_cache_key = None;
        self.top = None;
        self.overview_top = None;
        self.chart = None;
        self.timeline = None;
        self.chart_selected = None;
        self.row_selected = None;
        self.pending_focus = None;
        self.collapsed.clear();
        self.error = None;
        self.export_message = None;
    }

    /// Sample timestamps are trace-local; the session summary bounds the
    /// timeline unless an explicit query range narrows it.
    fn time_bounds(&self) -> (u64, u64) {
        let Some(catalog) = &self.catalog else {
            return (0, 1);
        };
        let summary = &catalog.summary;
        let start = self
            .query
            .start
            .or(summary.first_timestamp_nanos)
            .unwrap_or(0);
        let end = self
            .query
            .end
            .or_else(|| {
                summary
                    .last_timestamp_nanos
                    .and_then(|value| value.checked_add(1))
            })
            .unwrap_or(start.saturating_add(1))
            .max(start.saturating_add(1));
        (start, end)
    }

    /// Loads the activity timeline and the KPI/Overview top functions. These
    /// aggregations are shared by every tab, so paging the active projection
    /// never recomputes them.
    fn refresh_shared(&mut self, cx: &mut Context<Self>) {
        let Some(session) = self.session.clone() else {
            return;
        };
        self.shared_busy = true;
        self.shared_generation += 1;
        let generation = self.shared_generation;
        self.timeline = None;
        self.overview_top = None;
        let query = self.query.clone();
        let (start, end) = self.time_bounds();
        let metadata_path = self.metadata_path.clone();
        let association = self.association.clone();
        let task = cx.background_executor().spawn(async move {
            let mut session = session
                .lock()
                .map_err(|_| CpuError::Local(TextKey::ProfilerCpuSessionLockPoisoned))?;
            let viewport = ChartViewport {
                width_px: TIMELINE_WIDTH_PX,
                row_height_px: 6.0,
                first_depth: 0,
                depth_count: 1,
                max_blocks: 20_000,
            };
            let timeline = session.stack_chart(
                &query,
                TimelineViewport {
                    chart: viewport,
                    start_nanos: start,
                    end_nanos: end,
                },
            )?;
            let top = session.top_functions(&query, 0, CALLOUT_TOP_FUNCTIONS)?;
            Ok::<_, CpuError>((
                timeline,
                top,
                check_cpu_binding(metadata_path.as_deref(), association),
            ))
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.shared_generation != generation {
                    return;
                }
                this.shared_busy = false;
                match result {
                    Ok((timeline, top, binding)) => {
                        this.apply_binding_check(binding);
                        this.timeline = Some(timeline);
                        this.overview_top = Some(top);
                    }
                    Err(error) => this.error = Some(error.into()),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    /// Loads the active tab projection. Paging and tab switches reuse the
    /// already parsed session and only aggregate the current query.
    fn reload(&mut self, cx: &mut Context<Self>) {
        let Some(session) = self.session.clone() else {
            return;
        };
        let tab = self.tab;
        self.tree = None;
        self.tree_generation = self.tree_generation.wrapping_add(1);
        self.tree_visible = Rc::new(Vec::new());
        self.tree_content_width = 0.0;
        self.tree_sizes = Rc::new(Vec::new());
        self.tree_cache_key = None;
        self.top = None;
        self.chart = None;
        self.chart_selected = None;
        self.error = None;
        if !tab.needs_query() {
            // Cancel any in-flight projection publication; the new tab owns
            // the current generation.
            self.generation += 1;
            self.busy = false;
            cx.notify();
            return;
        }
        self.busy = true;
        self.generation += 1;
        let generation = self.generation;
        let query = self.query.clone();
        let (start, end) = self.time_bounds();
        let zoom = (self.zoom_start, self.zoom_end);
        let top_sort = self.top_sort;
        let top_descending = self.top_descending;
        let transforms = self.engine_transforms();
        let metadata_path = self.metadata_path.clone();
        let association = self.association.clone();
        let task = cx.background_executor().spawn(async move {
            let mut session = session
                .lock()
                .map_err(|_| CpuError::Local(TextKey::ProfilerCpuSessionLockPoisoned))?;
            let viewport = ChartViewport {
                width_px: CHART_WIDTH_PX,
                row_height_px: CHART_ROW_HEIGHT_PX,
                first_depth: 0,
                depth_count: 64,
                max_blocks: 8_000,
            };
            let result = match tab {
                CpuTab::CallTree => if transforms.is_empty() {
                    session.full_call_tree(&query, MAX_FULL_TREE_ROWS)
                } else {
                    session.transformed_call_tree(&query, &transforms, MAX_FULL_TREE_ROWS)
                }
                .map(CpuResult::Tree),
                CpuTab::TopFunctions => session
                    .top_functions_sorted(
                        &query,
                        top_sort,
                        top_descending,
                        query.offset,
                        query.limit,
                    )
                    .map(CpuResult::Top),
                CpuTab::FlameGraph => if transforms.is_empty() {
                    session.flame_graph_in_range(&query, viewport, zoom.0, zoom.1)
                } else {
                    session.transformed_flame_frame(&query, &transforms, viewport, zoom.0, zoom.1)
                }
                .map(CpuResult::Chart),
                CpuTab::StackChart => session
                    .stack_chart(
                        &query,
                        TimelineViewport {
                            chart: viewport,
                            start_nanos: start,
                            end_nanos: end,
                        },
                    )
                    .map(CpuResult::Chart),
                CpuTab::Overview | CpuTab::MarkerChart | CpuTab::MarkerTable => {
                    Ok(CpuResult::Empty)
                }
            }?;
            Ok::<_, CpuError>((
                result,
                check_cpu_binding(metadata_path.as_deref(), association),
            ))
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.generation != generation {
                    return;
                }
                this.busy = false;
                match result {
                    Ok((result, binding)) => {
                        this.apply_binding_check(binding);
                        match result {
                            CpuResult::Tree(tree) => {
                                this.tree = Some(tree);
                                this.tree_generation = this.tree_generation.wrapping_add(1);
                                this.tree_cache_key = None;
                                this.resolve_pending_focus();
                            }
                            CpuResult::Top(page) => this.top = Some(page),
                            CpuResult::Chart(frame) => {
                                this.chart = Some(frame);
                                this.resolve_pending_focus();
                            }
                            CpuResult::Empty => {}
                        }
                    }
                    Err(error) => this.error = Some(error.into()),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    fn resolve_pending_focus(&mut self) {
        let Some(symbol) = self.pending_focus.take() else {
            return;
        };
        match self.tab {
            CpuTab::CallTree => {
                if let Some(tree) = &self.tree {
                    if let Some(index) = tree.rows.iter().position(|row| row.name == symbol) {
                        let row_id = tree.rows[index].id.clone();
                        // The focused row may sit far below the viewport; use the
                        // current collapse state so a cross-tab focus scrolls to it.
                        let visible = visible_row_indices(&tree.rows, &self.collapsed);
                        let position = visible.iter().position(|visible| *visible == index);
                        self.row_selected = Some(row_id);
                        if let Some(position) = position {
                            self.tree_scroll
                                .scroll_to_item(position, ScrollStrategy::Center);
                        }
                    }
                }
            }
            CpuTab::FlameGraph => {
                if let Some(frame) = &self.chart {
                    if let Some(block) = frame.blocks.iter().find(|block| block.label == symbol) {
                        self.chart_selected = Some(block.id.clone());
                    }
                }
            }
            _ => {}
        }
    }

    fn cycle_tab(&mut self, next: bool, cx: &mut Context<Self>) {
        let index = CpuTab::ALL
            .iter()
            .position(|tab| *tab == self.tab)
            .unwrap_or(0);
        let len = CpuTab::ALL.len();
        let index = if next {
            (index + 1) % len
        } else {
            (index + len - 1) % len
        };
        self.switch_tab(CpuTab::ALL[index], cx);
    }

    fn switch_tab(&mut self, tab: CpuTab, cx: &mut Context<Self>) {
        if self.tab == tab {
            return;
        }
        self.tab = tab;
        self.query.offset = 0;
        self.row_selected = None;
        self.chart_selected = None;
        self.chart_hover = None;
        self.chart_hover_position = None;
        self.chart_menu = None;
        self.pending_focus = None;
        self.collapsed.clear();
        self.reload(cx);
    }

    fn zoom(&mut self, inward: bool, cx: &mut Context<Self>) {
        if self.tab != CpuTab::FlameGraph {
            return;
        }
        let width = self.zoom_end - self.zoom_start;
        let next = if inward {
            width * 0.5
        } else {
            (width * 2.0).min(1.0)
        };
        let middle = (self.zoom_start + self.zoom_end) * 0.5;
        self.zoom_start = (middle - next * 0.5).max(0.0);
        self.zoom_end = (self.zoom_start + next).min(1.0);
        self.reload(cx);
    }

    fn close(&mut self, cx: &mut Context<Self>) {
        self.import_cancel.store(true, Ordering::Release);
        self.recent_generation += 1;
        self.shared_generation += 1;
        self.recent_busy = false;
        self.shared_busy = false;
        self.association = None;
        self.metadata_error = None;
        self.generation += 1;
        self.busy = false;
        self.session = None;
        self.catalog = None;
        self.tree = None;
        self.tree_generation = self.tree_generation.wrapping_add(1);
        self.tree_visible = Rc::new(Vec::new());
        self.tree_content_width = 0.0;
        self.tree_sizes = Rc::new(Vec::new());
        self.tree_cache_key = None;
        self.top = None;
        self.overview_top = None;
        self.chart = None;
        self.timeline = None;
        self.chart_selected = None;
        self.row_selected = None;
        self.pending_focus = None;
        self.collapsed.clear();
        self.export_message = None;
        self.tab = CpuTab::Overview;
        self.transforms.clear();
        self.error = None;
        cx.notify();
    }

    fn copy_csv(&mut self, cx: &mut Context<Self>) {
        if self.busy {
            return;
        }
        let Some(session) = self.session.clone() else {
            return;
        };
        let query = self.query.clone();
        let tab = self.tab;
        if !matches!(tab, CpuTab::CallTree | CpuTab::TopFunctions) {
            return;
        }
        self.busy = true;
        self.error = None;
        self.generation += 1;
        let generation = self.generation;
        let metadata_path = self.metadata_path.clone();
        let association = self.association.clone();
        let task = cx.background_executor().spawn(async move {
            let mut session = session
                .lock()
                .map_err(|_| CpuError::Local(TextKey::ProfilerCpuSessionUnavailable))?;
            let csv = match tab {
                CpuTab::CallTree => session.export_call_tree_csv(&query),
                CpuTab::TopFunctions => session.export_top_functions_csv(&query),
                _ => unreachable!("tab checked before task"),
            }?;
            Ok::<_, CpuError>((
                csv,
                check_cpu_binding(metadata_path.as_deref(), association),
            ))
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.generation != generation {
                    return;
                }
                this.busy = false;
                match result {
                    Ok((csv, binding)) => {
                        this.apply_binding_check(binding);
                        cx.write_to_clipboard(ClipboardItem::new_string(csv));
                    }
                    Err(error) => this.error = Some(error.into()),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    fn save_export(&mut self, kind: ExportKind, cx: &mut Context<Self>) {
        if self.busy {
            return;
        }
        let Some(session) = self.session.clone() else {
            return;
        };
        self.busy = true;
        self.error = None;
        self.export_message = None;
        self.generation += 1;
        let generation = self.generation;
        let query = self.query.clone();
        let language = self.language;
        let prompt = kind.label(language).to_owned();
        let picker = cx.prompt_for_paths(PathPromptOptions {
            files: true,
            directories: false,
            multiple: false,
            prompt: Some(prompt.into()),
        });
        cx.spawn(async move |this, cx| {
            let selected = match picker.await {
                Ok(Ok(Some(paths))) if paths.len() == 1 => Some(paths[0].clone()),
                Ok(Err(error)) => {
                    this.update(cx, |this, cx| {
                        if this.generation == generation {
                            this.busy = false;
                            this.error = Some(error.to_string().into());
                            cx.notify();
                        }
                    })
                    .ok();
                    return;
                }
                _ => None,
            };
            let Some(destination) = selected else {
                this.update(cx, |this, cx| {
                    if this.generation == generation {
                        this.busy = false;
                        cx.notify();
                    }
                })
                .ok();
                return;
            };
            let task = cx.background_executor().spawn(async move {
                let mut session = session
                    .lock()
                    .map_err(|_| CpuError::Local(TextKey::ProfilerCpuSessionUnavailable))?;
                let (bytes, max_bytes) = match kind {
                    ExportKind::ReportJson => (
                        session.export_report_json(&query)?.into_bytes(),
                        aps_cpu::MAX_REPORT_JSON_BYTES,
                    ),
                    ExportKind::Folded => (
                        session.export_folded_stacks(&query)?.into_bytes(),
                        aps_cpu::MAX_FOLDED_BYTES,
                    ),
                    ExportKind::Html => (
                        session.export_html_report(&query)?.into_bytes(),
                        aps_cpu::MAX_HTML_BYTES,
                    ),
                    ExportKind::Pprof => (session.export_pprof(&query)?, aps_cpu::MAX_PPROF_BYTES),
                    ExportKind::SessionPackage => (
                        session.export_session_package(&query)?,
                        aps_cpu::MAX_SESSION_PACKAGE_BYTES,
                    ),
                };
                write_new_blob(&destination, &bytes)?;
                if bytes.len() > max_bytes {
                    return Err(CpuError::Local(TextKey::ProfilerCpuExportBudgetExceeded));
                }
                Ok::<_, CpuError>(kind.saved_message(language).to_owned())
            });
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.generation != generation {
                    return;
                }
                this.busy = false;
                match result {
                    Ok(message) => this.export_message = Some(message),
                    Err(error) => this.error = Some(error.into()),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    fn save_firefox(&mut self, cx: &mut Context<Self>) {
        if self.busy {
            return;
        }
        let Some(session) = self.session.clone() else {
            return;
        };
        self.busy = true;
        self.error = None;
        self.export_message = None;
        self.generation += 1;
        let generation = self.generation;
        let picker = cx.prompt_for_paths(PathPromptOptions {
            files: true,
            directories: false,
            multiple: false,
            prompt: Some(
                self.language
                    .text(TextKey::CpuSaveFirefoxProfilerProfileJsonGz)
                    .into(),
            ),
        });
        cx.spawn(async move |this, cx| {
            let selected = match picker.await {
                Ok(Ok(Some(paths))) if paths.len() == 1 => Some(paths[0].clone()),
                Ok(Err(error)) => {
                    this.update(cx, |this, cx| {
                        if this.generation == generation {
                            this.busy = false;
                            this.error = Some(error.to_string().into());
                            cx.notify();
                        }
                    })
                    .ok();
                    return;
                }
                _ => None,
            };
            let Some(destination) = selected else {
                this.update(cx, |this, cx| {
                    if this.generation == generation {
                        this.busy = false;
                        cx.notify();
                    }
                })
                .ok();
                return;
            };
            let task = cx.background_executor().spawn(async move {
                let session = session
                    .lock()
                    .map_err(|_| CpuError::Local(TextKey::ProfilerCpuSessionUnavailable))?;
                let bytes = session.export_firefox_profile()?;
                write_new_export(&destination, &bytes)
            });
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.generation != generation {
                    return;
                }
                this.busy = false;
                match result {
                    Ok(()) => {
                        this.export_message = Some(
                            this.language
                                .text(TextKey::CpuFirefoxProfileExportedExistingFilesAreNever)
                                .into(),
                        );
                    }
                    Err(error) => this.error = Some(error.into()),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    fn apply_time(&mut self, cx: &mut Context<Self>) {
        let parse = |input: &Entity<InputState>| {
            let value = input.read(cx).value();
            let value = value.trim();
            if value.is_empty() {
                Ok(None)
            } else {
                value
                    .parse::<u64>()
                    .map(Some)
                    .map_err(|_| CpuError::Local(TextKey::ProfilerCpuInvalidTimeBounds))
            }
        };
        let bounds = parse(&self.start).and_then(|start| parse(&self.end).map(|end| (start, end)));
        match bounds {
            Ok((start, end)) => {
                let mut query = self.query.clone();
                query.start = start;
                query.end = end;
                query.offset = 0;
                match query.validate() {
                    Ok(()) => {
                        self.query = query;
                        self.reset_results();
                        self.refresh_shared(cx);
                        self.reload(cx);
                    }
                    Err(error) => {
                        self.error = Some(error.into());
                        cx.notify();
                    }
                }
            }
            Err(error) => {
                self.error = Some(error.into());
                cx.notify();
            }
        }
    }

    fn toggle_direction(&mut self, cx: &mut Context<Self>) {
        self.query.direction = match self.query.direction {
            Direction::Forward => Direction::Inverted,
            Direction::Inverted => Direction::Forward,
        };
        self.query.offset = 0;
        self.reset_results();
        self.refresh_shared(cx);
        self.reload(cx);
    }

    fn toggle_weight(&mut self, cx: &mut Context<Self>) {
        self.query.weight_type = match self.query.weight_type {
            WeightType::Samples => WeightType::EventCount,
            WeightType::EventCount => WeightType::Samples,
        };
        self.query.offset = 0;
        self.reset_results();
        self.refresh_shared(cx);
        self.reload(cx);
    }

    fn cycle_thread(&mut self, cx: &mut Context<Self>) {
        let ids: Vec<_> = self
            .catalog
            .as_ref()
            .map(|catalog| catalog.threads.iter().map(|thread| thread.id).collect())
            .unwrap_or_default();
        let current = self.query.thread_ids.first().copied();
        let next = current
            .and_then(|id| ids.iter().position(|candidate| *candidate == id))
            .map_or_else(|| ids.first().copied(), |index| ids.get(index + 1).copied());
        self.query.thread_ids = next.into_iter().collect();
        self.query.offset = 0;
        self.reset_results();
        self.refresh_shared(cx);
        self.reload(cx);
    }

    fn cycle_event(&mut self, cx: &mut Context<Self>) {
        let events = self
            .catalog
            .as_ref()
            .map(|catalog| catalog.events.as_slice())
            .unwrap_or_default();
        let next = self
            .query
            .event_types
            .first()
            .and_then(|event| events.iter().position(|candidate| candidate == event))
            .map_or_else(
                || events.first().cloned(),
                |index| events.get(index + 1).cloned(),
            );
        self.query.event_types = next.into_iter().collect();
        self.query.offset = 0;
        self.reset_results();
        self.refresh_shared(cx);
        self.reload(cx);
    }

    fn page_by(&mut self, next: bool, cx: &mut Context<Self>) {
        let (offset, limit, total) = self
            .top
            .as_ref()
            .map(|page| (page.offset, PAGE_SIZE, page.total))
            .unwrap_or((0, PAGE_SIZE, 0));
        let next_offset = if next {
            offset.saturating_add(limit).min(total.saturating_sub(1))
        } else {
            offset.saturating_sub(limit)
        };
        if next_offset == offset {
            return;
        }
        self.query.offset = next_offset;
        self.reload(cx);
    }

    fn select_row(&mut self, id: String, cx: &mut Context<Self>) {
        self.row_selected = Some(id);
        cx.notify();
    }

    fn toggle_collapsed(&mut self, id: String, cx: &mut Context<Self>) {
        if !self.collapsed.remove(&id) {
            self.collapsed.insert(id);
        }
        cx.notify();
    }

    fn expand_all(&mut self, cx: &mut Context<Self>) {
        self.collapsed.clear();
        cx.notify();
    }

    fn collapse_all(&mut self, cx: &mut Context<Self>) {
        if let Some(tree) = &self.tree {
            for (index, row) in tree.rows.iter().enumerate() {
                let has_children = tree
                    .rows
                    .get(index + 1)
                    .is_some_and(|next| next.depth > row.depth);
                if has_children {
                    self.collapsed.insert(row.id.clone());
                }
            }
        }
        cx.notify();
    }

    fn update_chart_hover(
        &mut self,
        id: Option<String>,
        position: Option<(f32, f32)>,
        cx: &mut Context<Self>,
    ) {
        // Hit testing works in chart-content coordinates; the facts card is
        // positioned inside the chart viewport, so store the scrolled-into-view
        // position and let the card choose its side against real viewport bounds.
        let offset = self.chart_scroll.offset();
        let position = position.map(|(x, y)| (x + f32::from(offset.x), y + f32::from(offset.y)));
        let mut changed = self.chart_hover != id;
        let quantized =
            |value: Option<(f32, f32)>| value.map(|(x, y)| (x as i32 / 4, y as i32 / 4));
        if quantized(self.chart_hover_position) != quantized(position) {
            self.chart_hover_position = position;
            changed = true;
        }
        self.chart_hover = id;
        if changed {
            cx.notify();
        }
    }

    fn open_chart_menu(&mut self, position: (f32, f32), id: String, cx: &mut Context<Self>) {
        self.chart_menu = Some((position.0, position.1, id));
        cx.notify();
    }

    fn close_chart_menu(&mut self, cx: &mut Context<Self>) {
        if self.chart_menu.take().is_some() {
            cx.notify();
        }
    }

    /// Zooms the flame graph to the weight range occupied by the block.
    fn focus_flame_block(&mut self, id: String, cx: &mut Context<Self>) {
        let Some(frame) = self.chart.as_ref() else {
            return;
        };
        let Some(block) = frame.blocks.iter().find(|block| block.id == id) else {
            return;
        };
        let width = frame.width_px.max(1.0);
        let lo = (block.x / width).clamp(0.0, 1.0) as f64;
        let hi = ((block.x + block.width) / width).clamp(0.0, 1.0) as f64;
        let zoom_width = self.zoom_end - self.zoom_start;
        let start = self.zoom_start + zoom_width * lo;
        let end = self.zoom_start + zoom_width * hi;
        if end - start > 0.0001 {
            self.zoom_start = start.max(0.0);
            self.zoom_end = end.min(1.0);
            self.chart_menu = None;
            self.reload(cx);
        }
    }

    /// Opens the indexed source declaration for the current selection. Requires
    /// a registered and indexed Source Workspace; otherwise that page explains
    /// the prerequisite.
    fn open_source(
        &mut self,
        symbol: String,
        resource: String,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let Some(studio) = self.studio.clone() else {
            self.error = Some(CpuError::Local(
                TextKey::ProfilerCpuSourceNavigationUnavailable,
            ));
            cx.notify();
            return;
        };
        studio
            .update(cx, |studio, cx| {
                studio.open_source_symbol(symbol, resource, window, cx)
            })
            .ok();
    }

    /// Primary export of the CPU page: Compose assigns Cmd+E to the page's most
    /// common export, which is the JSON/CSV report. Runs the same report export
    /// as File > Export > Report JSON and the toolbar dropdown.
    pub(crate) fn export_primary(&mut self, cx: &mut Context<Self>) {
        self.save_export(ExportKind::ReportJson, cx);
    }

    /// Open Folder… of the CPU page (Compose Cmd+Shift+O): the page's folder
    /// picker selects the folder that holds the symbols/binary_cache artifacts,
    /// which is the only folder this screen can open today.
    pub(crate) fn open_folder_primary(&mut self, cx: &mut Context<Self>) {
        self.pick_binary_root(cx);
    }

    pub fn focus_search(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.search.update(cx, |state, cx| {
            state.focus_handle(cx).focus(window, cx);
        });
    }

    fn begin_chart_drag(&mut self, fraction: f32, cx: &mut Context<Self>) {
        self.chart_drag = Some((fraction, fraction));
        cx.notify();
    }

    fn update_chart_drag(&mut self, fraction: f32, cx: &mut Context<Self>) {
        if let Some((start, _)) = self.chart_drag {
            self.chart_drag = Some((start, fraction));
            cx.notify();
        }
    }

    /// Commits a chart drag. Flame graphs zoom into the dragged weight range;
    /// stack charts crop the sampled time range. A drag shorter than 0.5% of
    /// the plot is treated as a click (selection is handled separately).
    fn commit_chart_drag(&mut self, cx: &mut Context<Self>) {
        let Some((a, b)) = self.chart_drag.take() else {
            return;
        };
        let (lo, hi) = if a <= b { (a, b) } else { (b, a) };
        if hi - lo < 0.005 {
            cx.notify();
            return;
        }
        match self.tab {
            CpuTab::StackChart => {
                let domain = self.time_bounds();
                self.apply_time_fraction(domain, (lo, hi), cx);
            }
            CpuTab::FlameGraph => {
                let width = self.zoom_end - self.zoom_start;
                let start = self.zoom_start + width * f64::from(lo);
                let end = self.zoom_start + width * f64::from(hi);
                if end - start > 0.0001 {
                    self.zoom_start = start.max(0.0);
                    self.zoom_end = end.min(1.0);
                    self.reload(cx);
                }
            }
            _ => {}
        }
        cx.notify();
    }

    /// Applies a normalized sub-range of a sample-timestamp domain as the
    /// active time filter.
    fn apply_time_fraction(
        &mut self,
        domain: (u64, u64),
        range: (f32, f32),
        cx: &mut Context<Self>,
    ) {
        let (start, end) = domain;
        let span = end.saturating_sub(start) as f64;
        if span <= 0.0 {
            return;
        }
        let low = f64::from(range.0.clamp(0.0, 1.0));
        let high = f64::from(range.1.clamp(0.0, 1.0));
        let from = start.saturating_add((span * low) as u64);
        let to = start
            .saturating_add((span * high) as u64)
            .max(from.saturating_add(1));
        self.query.start = Some(from);
        self.query.end = Some(to);
        self.query.offset = 0;
        self.reset_results();
        self.refresh_shared(cx);
        self.reload(cx);
    }

    fn reset_time_range(&mut self, cx: &mut Context<Self>) {
        if self.query.start.is_none() && self.query.end.is_none() {
            return;
        }
        self.query.start = None;
        self.query.end = None;
        self.query.offset = 0;
        self.reset_results();
        self.refresh_shared(cx);
        self.reload(cx);
    }

    fn reset_zoom(&mut self, cx: &mut Context<Self>) {
        if self.zoom_start == 0.0 && self.zoom_end == 1.0 {
            return;
        }
        self.zoom_start = 0.0;
        self.zoom_end = 1.0;
        self.reload(cx);
    }

    fn select_thread_filter(&mut self, thread_id: Option<i32>, cx: &mut Context<Self>) {
        self.query.thread_ids = thread_id.into_iter().collect();
        self.query.offset = 0;
        self.reset_results();
        self.refresh_shared(cx);
        self.reload(cx);
    }

    fn copy_symbol(&mut self, symbol: String, cx: &mut Context<Self>) {
        cx.write_to_clipboard(ClipboardItem::new_string(symbol));
    }

    fn set_top_sort(&mut self, sort: TopSort, cx: &mut Context<Self>) {
        if self.top_sort == sort {
            return;
        }
        self.top_sort = sort;
        self.query.offset = 0;
        self.row_selected = None;
        self.reload(cx);
    }

    fn engine_transforms(&self) -> TransformSet {
        let mut set = TransformSet::default();
        for op in &self.transforms {
            match op {
                TransformOp::Focus(name) => set.focus = Some(name.clone()),
                TransformOp::Merge(name) => {
                    set.merge.insert(name.clone());
                }
                TransformOp::Collapse(name) => {
                    set.collapse.insert(name.clone());
                }
                TransformOp::Drop(name) => {
                    set.drop.insert(name.clone());
                }
                TransformOp::CollapseRecursion => set.collapse_recursion = true,
            }
        }
        set
    }

    fn apply_transform(&mut self, op: TransformOp, cx: &mut Context<Self>) {
        if self.transforms.contains(&op) {
            return;
        }
        self.transforms.push(op);
        self.reload(cx);
    }

    fn remove_transform(&mut self, index: usize, cx: &mut Context<Self>) {
        if index < self.transforms.len() {
            self.transforms.remove(index);
            self.reload(cx);
        }
    }

    fn reset_transforms(&mut self, cx: &mut Context<Self>) {
        if !self.transforms.is_empty() {
            self.transforms.clear();
            self.reload(cx);
        }
    }

    fn data_root(&self) -> Option<PathBuf> {
        self.metadata_path
            .as_ref()
            .and_then(|path| path.parent())
            .map(PathBuf::from)
    }

    fn pick_binary_root(&mut self, cx: &mut Context<Self>) {
        if self.busy {
            return;
        }
        let language = self.language;
        let picker = cx.prompt_for_paths(PathPromptOptions {
            files: false,
            directories: true,
            multiple: false,
            prompt: Some(language.text(TextKey::CpuSelectBinaryFolder).into()),
        });
        cx.spawn(async move |this, cx| {
            if let Ok(Ok(Some(paths))) = picker.await {
                if let Some(path) = paths.into_iter().next() {
                    this.update(cx, |this, cx| {
                        this.binary_root = Some(path);
                        cx.notify();
                    })
                    .ok();
                }
            }
        })
        .detach();
        cx.notify();
    }

    fn disassemble(&mut self, symbol: String, resource: String, cx: &mut Context<Self>) {
        if symbol.trim().is_empty() {
            return;
        }
        self.disassembly_cancel.store(true, Ordering::Release);
        self.disassembly_cancel = Arc::new(AtomicBool::new(false));
        let cancel = self.disassembly_cancel.clone();
        self.disassembly_generation += 1;
        let generation = self.disassembly_generation;
        self.disassembly_busy = true;
        self.disassembly = Some(DisassemblyPanel {
            symbol: symbol.clone(),
            ..Default::default()
        });
        let request = crate::disassembly::DisassemblyRequest {
            symbol,
            resource,
            binary_root: self.binary_root.clone(),
            data_root: self.data_root(),
        };
        let task = cx
            .background_executor()
            .spawn(async move { crate::disassembly::resolve_disassembly(&request, &cancel) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.disassembly_generation != generation {
                    return;
                }
                this.disassembly_busy = false;
                match result {
                    Ok(disassembly) => {
                        this.disassembly = Some(DisassemblyPanel {
                            symbol: disassembly.symbol,
                            binary: Some(disassembly.binary),
                            tool: Some(disassembly.tool),
                            lines: disassembly.lines,
                            error: None,
                        });
                    }
                    Err(error) => {
                        if let Some(panel) = &mut this.disassembly {
                            panel.error = Some(error);
                        }
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    fn close_disassembly(&mut self, cx: &mut Context<Self>) {
        self.disassembly_cancel.store(true, Ordering::Release);
        self.disassembly = None;
        self.disassembly_busy = false;
        cx.notify();
    }

    fn copy_disassembly(&mut self, cx: &mut Context<Self>) {
        let Some(panel) = &self.disassembly else {
            return;
        };
        let text = panel.lines.join(
            "
",
        );
        cx.write_to_clipboard(ClipboardItem::new_string(text));
    }

    fn toggle_top_order(&mut self, cx: &mut Context<Self>) {
        self.top_descending = !self.top_descending;
        self.query.offset = 0;
        self.reload(cx);
    }

    fn set_details_visible(&mut self, visible: bool, cx: &mut Context<Self>) {
        self.details_visible = visible;
        cx.notify();
    }

    fn begin_timeline_drag(&mut self, y: f32, cx: &mut Context<Self>) {
        self.timeline_drag = Some((y, self.timeline_height));
        cx.notify();
    }

    fn update_timeline_drag(&mut self, y: f32, cx: &mut Context<Self>) {
        if let Some((start, base)) = self.timeline_drag {
            self.timeline_height = (base + (y - start)).clamp(
                studio::metrics::TIMELINE_MIN_HEIGHT,
                studio::metrics::TIMELINE_MAX_HEIGHT,
            );
            cx.notify();
        }
    }

    fn end_timeline_drag(&mut self, cx: &mut Context<Self>) {
        self.timeline_drag = None;
        cx.notify();
    }

    /// Builds the cached visible-row projection and uniform virtual-list item
    /// sizes for the full call tree. Recomputing is keyed by tree generation,
    /// collapsed set and search text so scrolling never rebuilds them.
    fn build_tree_cache(&mut self, cx: &App) {
        let Some(tree) = self.tree.as_ref() else {
            self.tree_visible = Rc::new(Vec::new());
            self.tree_content_width = 0.0;
            self.tree_sizes = Rc::new(Vec::new());
            self.tree_cache_key = None;
            return;
        };
        let search = self.search.read(cx).value().to_lowercase().to_string();
        let collapse_hash = {
            let mut hasher = std::collections::hash_map::DefaultHasher::new();
            for id in &self.collapsed {
                id.hash(&mut hasher);
            }
            hasher.finish()
        };
        let key = (self.tree_generation, collapse_hash, search.clone());
        if self.tree_cache_key.as_ref() == Some(&key) {
            return;
        }
        let empty = BTreeSet::new();
        let collapsed = if search.is_empty() {
            &self.collapsed
        } else {
            &empty
        };
        let visible = visible_row_indices(&tree.rows, collapsed);
        self.tree_content_width = call_tree_content_width(&tree.rows);
        self.tree_sizes = Rc::new(vec![size(px(1.), px(TREE_ROW_HEIGHT)); visible.len()]);
        self.tree_visible = Rc::new(visible);
        self.tree_cache_key = Some(key);
    }

    /// Renders one virtualized window of the full call tree.
    fn call_tree_rows(
        &mut self,
        range: std::ops::Range<usize>,
        cx: &mut Context<Self>,
    ) -> Vec<AnyElement> {
        let colors = studio::StudioColors::from_theme(cx);
        let lang = self.language;
        let total_weight = match self.query.weight_type {
            WeightType::Samples => self
                .catalog
                .as_ref()
                .map_or(0, |catalog| catalog.summary.sample_count),
            WeightType::EventCount => self
                .catalog
                .as_ref()
                .map_or(0, |catalog| catalog.summary.total_event_count),
        };
        let selected = self.row_selected.clone();
        let search = self.search.read(cx).value().to_lowercase().to_string();
        let Some(tree) = self.tree.as_ref() else {
            return Vec::new();
        };
        let visible = self.tree_visible.clone();
        let mut rendered = Vec::new();
        for visible_index in range {
            let Some(row_index) = visible.get(visible_index).copied() else {
                continue;
            };
            let Some(row) = tree.rows.get(row_index) else {
                continue;
            };
            let is_selected = selected.as_deref() == Some(row.id.as_str());
            let background = if is_selected {
                colors.accent
            } else if visible_index % 2 == 1 {
                colors.panel.blend(colors.toolbar.alpha(0.62))
            } else {
                colors.panel
            };
            let foreground = if is_selected {
                colors.accent_text
            } else {
                colors.text
            };
            let secondary = if is_selected {
                colors.accent_text
            } else {
                colors.secondary_text
            };
            let has_children = tree
                .rows
                .get(row_index + 1)
                .is_some_and(|next| next.depth > row.depth);
            let collapsed = self.collapsed.contains(&row.id);
            let highlighted = !search.is_empty() && row.name.to_lowercase().contains(&search);
            let toggle_id = row.id.clone();
            let select_id = row.id.clone();
            let name = row.name.clone();
            let resource = row.resource.clone();
            let depth = row.depth;
            let percent = if total_weight > 0 {
                format_percent(row.inclusive_weight as f64 / total_weight as f64)
            } else {
                format_percent(0.0)
            };
            let inclusive = format_count(row.inclusive_weight);
            let own = format_count(row.self_weight);
            let samples = format_count(row.sample_count);
            let mut function = div()
                .flex()
                .items_center()
                .gap(px(2.))
                .flex_1()
                .min_w_0()
                .pl(px((depth.saturating_mul(10).min(400)) as f32));
            if has_children {
                function = function.child(
                    div()
                        .id(("cpu-call-toggle", row_index))
                        .w(px(18.))
                        .flex_shrink_0()
                        .cursor_pointer()
                        .text_size(px(studio::FONT_SUPPORTING))
                        .text_color(secondary)
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.toggle_collapsed(toggle_id.clone(), cx)
                        }))
                        .child(if collapsed { "▸" } else { "▾" }),
                );
            } else {
                function = function.child(div().w(px(18.)).flex_shrink_0());
            }
            function = function.child(
                div()
                    .text_size(px(studio::FONT_SUPPORTING))
                    .text_color(if highlighted {
                        colors.accent
                    } else {
                        foreground
                    })
                    .when(highlighted, |this| this.font_weight(FontWeight::SEMIBOLD))
                    .whitespace_nowrap()
                    .child(crate::studio::selectable(name)),
            );
            if !resource.is_empty() {
                function = function.child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .pl(px(10.))
                        .text_size(px(studio::FONT_SUPPORTING))
                        .text_color(secondary)
                        .whitespace_nowrap()
                        .text_ellipsis()
                        .child(resource),
                );
            }
            rendered.push(
                div()
                    .id(("cpu-call-tree-row", row_index))
                    .h(px(TREE_ROW_HEIGHT))
                    .w_full()
                    .flex()
                    .items_center()
                    .bg(background)
                    .cursor_pointer()
                    .on_click(cx.listener(move |this, _, window, cx| {
                        window.focus(&this.tree_focus, cx);
                        this.select_row(select_id.clone(), cx);
                    }))
                    .child(table_value(percent, 95., foreground))
                    .child(table_value(inclusive, 70., foreground))
                    .child(table_value(own, 70., foreground))
                    .child(table_value(samples, 64., foreground))
                    .child(function)
                    .into_any_element(),
            );
        }
        let _ = lang;
        rendered
    }

    /// Top-functions keys: Up/Down move the selection and Enter focuses the
    /// selected function in the flame graph.
    fn handle_top_functions_key(
        &mut self,
        event: &KeyDownEvent,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let Some(page) = self.top.as_ref() else {
            return;
        };
        let rows: Vec<(String, String)> = page
            .rows
            .iter()
            .map(|row| (row.id.clone(), row.name.clone()))
            .collect();
        if rows.is_empty() {
            return;
        }
        let current = self
            .row_selected
            .as_deref()
            .and_then(|id| rows.iter().position(|(row_id, _)| row_id == id));
        match event.keystroke.key.to_ascii_lowercase().as_str() {
            "up" => {
                let position = current
                    .map(|position| position.saturating_sub(1))
                    .unwrap_or(0);
                self.row_selected = Some(rows[position].0.clone());
                self.top_scroll.scroll_to_item(position);
                cx.notify();
            }
            "down" => {
                let position = current
                    .map(|position| (position + 1).min(rows.len() - 1))
                    .unwrap_or(0);
                self.row_selected = Some(rows[position].0.clone());
                self.top_scroll.scroll_to_item(position);
                cx.notify();
            }
            "enter" => {
                let Some(position) = current else {
                    return;
                };
                self.row_selected = Some(rows[position].0.clone());
                let name = rows[position].1.clone();
                self.focus_in_flame(name, cx);
            }
            _ => {}
        }
        let _ = window;
    }

    /// Firefox-style call-tree keys: Up/Down move the selection, Right expands
    /// or descends, Left collapses or ascends, the multiply key expands the
    /// selected subtree and Enter focuses the selected function in the flame graph.
    fn handle_call_tree_key(
        &mut self,
        event: &KeyDownEvent,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let Some(tree) = self.tree.as_ref() else {
            return;
        };
        let rows: Vec<(String, Option<String>, usize, String)> = tree
            .rows
            .iter()
            .map(|row| {
                (
                    row.id.clone(),
                    row.parent_id.clone(),
                    row.depth,
                    row.name.clone(),
                )
            })
            .collect();
        let search = self.search.read(cx).value().to_string();
        let empty = BTreeSet::new();
        let collapsed = if search.is_empty() {
            &self.collapsed
        } else {
            &empty
        };
        let visible = visible_row_indices(&tree.rows, collapsed);
        if visible.is_empty() {
            return;
        }
        let current = self
            .row_selected
            .as_deref()
            .and_then(|id| visible.iter().position(|index| rows[*index].0 == id));
        let key = event.keystroke.key.to_ascii_lowercase();
        let shift = event.keystroke.modifiers.shift;
        let mut selection = self.row_selected.clone();
        let mut scroll_to = None;
        match key.as_str() {
            "up" => {
                let position = current
                    .map(|position| position.saturating_sub(1))
                    .unwrap_or(0);
                selection = Some(rows[visible[position]].0.clone());
                scroll_to = Some(position);
            }
            "down" => {
                let position = current
                    .map(|position| (position + 1).min(visible.len() - 1))
                    .unwrap_or(0);
                selection = Some(rows[visible[position]].0.clone());
                scroll_to = Some(position);
            }
            "left" => {
                let Some(position) = current else {
                    return;
                };
                let (id, parent, depth, _) = &rows[visible[position]];
                let has_children = rows
                    .iter()
                    .position(|row| &row.0 == id)
                    .and_then(|index| rows.get(index + 1))
                    .is_some_and(|next| &next.2 > depth);
                if has_children && !collapsed.contains(id) {
                    self.collapsed.insert(id.clone());
                } else if let Some(parent) = parent {
                    selection = Some(parent.clone());
                }
            }
            "right" => {
                let Some(position) = current else {
                    return;
                };
                let (id, _, depth, _) = &rows[visible[position]];
                let abs = rows.iter().position(|row| &row.0 == id);
                let child = abs.and_then(|index| rows.get(index + 1));
                let has_children = child.is_some_and(|next| next.2 > *depth);
                if has_children && !self.collapsed.remove(id) {
                    if let Some(child) = child {
                        selection = Some(child.0.clone());
                        scroll_to = Some(position + 1);
                    }
                }
            }
            "enter" => {
                let Some(position) = current else {
                    return;
                };
                let name = rows[visible[position]].3.clone();
                if shift {
                    self.focus_in_call_tree(name, cx);
                } else {
                    self.focus_in_flame(name, cx);
                }
                return;
            }
            "*" => {
                let Some(position) = current else {
                    return;
                };
                let (id, _, depth, _) = &rows[visible[position]];
                if let Some(index) = rows.iter().position(|row| &row.0 == id) {
                    for row in rows.iter().skip(index + 1) {
                        if row.2 <= *depth {
                            break;
                        }
                        self.collapsed.remove(&row.0);
                    }
                }
            }
            "escape" => {
                selection = None;
            }
            _ => return,
        }
        if self.row_selected != selection {
            self.row_selected = selection;
        }
        if let Some(position) = scroll_to {
            self.tree_scroll
                .scroll_to_item(position, ScrollStrategy::Top);
        }
        cx.notify();
        let _ = window;
    }

    fn focus_in_call_tree(&mut self, symbol: String, cx: &mut Context<Self>) {
        self.pending_focus = Some(symbol);
        self.tab = CpuTab::CallTree;
        self.query.offset = 0;
        self.row_selected = None;
        self.collapsed.clear();
        self.reload(cx);
    }

    fn focus_in_flame(&mut self, symbol: String, cx: &mut Context<Self>) {
        self.pending_focus = Some(symbol);
        self.tab = CpuTab::FlameGraph;
        self.chart_selected = None;
        self.reload(cx);
    }
}
impl Render for CpuWorkspace {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let colors = studio::StudioColors::from_theme(cx);
        let width = f32::from(window.viewport_size().width);
        let available = (width - NAVIGATION_WIDTH_ESTIMATE - 8.0).max(320.0);
        // Timeline resizing tracks the pointer on the page root: element-scoped
        // move listeners stop firing once the drag leaves the 8px handle.
        if self.tab == CpuTab::CallTree {
            self.build_tree_cache(cx);
        }
        let mut root = studio::page(&colors)
            .on_mouse_move(cx.listener(|this, event: &MouseMoveEvent, _, cx| {
                if this.timeline_drag.is_some() {
                    this.update_timeline_drag(f32::from(event.position.y), cx);
                }
            }))
            .on_mouse_up(
                MouseButton::Left,
                cx.listener(|this, _, _, cx| {
                    if this.timeline_drag.is_some() {
                        this.end_timeline_drag(cx);
                    }
                }),
            )
            .child(workspace_toolbar(self, &colors, cx));

        if self.catalog.is_none() {
            return root
                .child(empty_state(self, &colors, available, cx))
                .into_any_element();
        }
        root = root
            .child(kpi_strip(self, &colors, available))
            .child(timeline_panel(self, &colors, cx))
            .child(timeline_handle(self, &colors, cx))
            .child(tab_row(self, &colors, cx))
            .child(filter_toolbar(self, &colors, cx))
            .child(studio::divider(&colors));
        if self.tab.is_call_stack() {
            root = root.child(breadcrumb(self, &colors, cx));
        }
        root = root.child(content_and_details(self, &colors, width, cx));
        if self.disassembly.is_some() || self.disassembly_busy {
            root = root.child(disassembly_panel(self, &colors, cx));
        }
        if let Some(message) = self.export_message.clone() {
            root = root.child(message_line(message, colors.secondary_text));
        }
        root.child(disclaimer(self, &colors)).into_any_element()
    }
}

fn disassembly_panel(
    ws: &CpuWorkspace,
    colors: &StudioColors,
    cx: &Context<CpuWorkspace>,
) -> AnyElement {
    let lang = ws.language;
    let panel = ws.disassembly.as_ref();
    let symbol = panel.map(|panel| panel.symbol.clone()).unwrap_or_default();
    let has_lines = panel.is_some_and(|panel| !panel.lines.is_empty());
    let select_label = lang.text(TextKey::CpuSelectBinaryFolder).to_owned();
    let copy_label = lang.text(TextKey::CpuCopyDisassembly).to_owned();
    let close_label = lang.text(TextKey::CpuCloseDisassembly).to_owned();
    let mut header = div()
        .flex()
        .items_center()
        .gap(px(6.))
        .child(studio::details_heading(
            lang.text(TextKey::CpuDisassemblyTitle),
            colors,
        ))
        .child(
            div()
                .text_size(px(studio::FONT_SUPPORTING))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(colors.text)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(crate::studio::selectable(symbol)),
        )
        .child(div().flex_1())
        .child(
            studio::compact_text_button(
                "cpu-disassembly-select-binary",
                select_label,
                !ws.busy,
                false,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.pick_binary_root(cx))),
        )
        .child(
            studio::compact_text_button(
                "cpu-disassembly-copy",
                copy_label,
                has_lines,
                false,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.copy_disassembly(cx))),
        )
        .child(
            studio::compact_text_button("cpu-disassembly-close", close_label, true, false, colors)
                .on_click(cx.listener(|this, _, _, cx| this.close_disassembly(cx))),
        );
    let mut meta = div().flex().flex_col().gap(px(1.));
    if let Some(panel) = panel {
        if let Some(binary) = panel.binary.as_deref() {
            meta = meta.child(studio::dense_label(
                lang.format(
                    TextKey::CpuDisassemblyBinaryFormat,
                    &[("path", &binary.display().to_string())],
                ),
                colors.secondary_text,
            ));
        }
        if let Some(tool) = panel.tool.as_deref() {
            meta = meta.child(studio::dense_label(
                lang.format(
                    TextKey::CpuDisassemblyToolFormat,
                    &[("path", &tool.display().to_string())],
                ),
                colors.secondary_text,
            ));
        }
    }
    let body = if ws.disassembly_busy {
        studio::supporting(lang.text(TextKey::CpuDisassembling), colors).into_any_element()
    } else if let Some(panel) = panel {
        if let Some(error) = panel.error.clone() {
            studio::supporting(error, colors).into_any_element()
        } else if panel.lines.is_empty() {
            studio::supporting(lang.text(TextKey::CpuDisassemblyUnavailable), colors)
                .into_any_element()
        } else {
            div()
                .id("cpu-disassembly-lines")
                .flex_1()
                .min_h_0()
                .w_full()
                .overflow_scroll()
                .font_family("monospace")
                .text_size(px(10.))
                .flex()
                .flex_col()
                .children(
                    panel
                        .lines
                        .iter()
                        .map(|line| {
                            div()
                                .whitespace_nowrap()
                                .text_color(colors.text)
                                .child(line.clone())
                        })
                        .collect::<Vec<_>>(),
                )
                .into_any_element()
        }
    } else {
        studio::supporting(lang.text(TextKey::CpuDisassemblyUnavailable), colors).into_any_element()
    };
    div()
        .id("cpu-disassembly-panel")
        .flex()
        .flex_col()
        .gap(px(4.))
        .h(px(240.))
        .flex_shrink_0()
        .w_full()
        .p(px(8.))
        .bg(colors.panel)
        .border_1()
        .border_color(colors.border)
        .rounded(px(studio::metrics::PANEL_RADIUS))
        .child(header)
        .child(meta)
        .child(body)
        .into_any_element()
}

fn message_line(text: impl Into<SharedString>, color: Rgba) -> Div {
    div()
        .px(px(10.))
        .text_size(px(studio::FONT_SUPPORTING))
        .text_color(color)
        .whitespace_nowrap()
        .text_ellipsis()
        .child(crate::studio::selectable(text.into()))
}

fn workspace_toolbar(
    ws: &CpuWorkspace,
    colors: &StudioColors,
    cx: &Context<CpuWorkspace>,
) -> Stateful<Div> {
    let lang = ws.language;
    let busy = ws.busy || ws.shared_busy;
    let has_session = ws.catalog.is_some();
    let mut row = div()
        .id("cpu-workspace-toolbar")
        .flex()
        .items_center()
        .gap(px(4.))
        .h(px(studio::metrics::BUTTON_HEIGHT))
        .overflow_x_scroll()
        .child(
            studio::text_button(
                "cpu-import",
                lang.text(TextKey::CpuWorkspaceImport),
                !busy,
                false,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.pick_file(cx))),
        )
        .child(
            studio::text_button(
                "cpu-close",
                lang.text(TextKey::CpuWorkspaceCloseSession),
                has_session && !busy,
                false,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.close(cx))),
        )
        .child(
            div()
                .w(px(studio::metrics::HAIRLINE))
                .h(px(16.))
                .bg(colors.border),
        )
        .child(
            studio::compact_text_button(
                "cpu-recent-refresh",
                lang.text(TextKey::CpuRefreshRecentMetadata),
                !ws.recent_busy && !busy && ws.metadata_path.is_some(),
                false,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.refresh_recent(0, cx))),
        )
        .child(
            studio::compact_text_button(
                "cpu-select-simpleperf",
                lang.text(TextKey::CpuSelectSimpleperf),
                !busy,
                false,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.pick_converter(cx))),
        );
    if let Some(recent) = &ws.recent {
        let offset = recent.offset;
        let end = offset.saturating_add(recent.scanned_sessions);
        row = row
            .child(
                studio::compact_text_button(
                    "cpu-recent-prev",
                    lang.text(TextKey::CpuPreviousMetadata),
                    !ws.recent_busy && !busy && offset > 0,
                    false,
                    colors,
                )
                .on_click(cx.listener(move |this, _, _, cx| {
                    this.refresh_recent(offset.saturating_sub(RECENT_PAGE_SIZE), cx)
                })),
            )
            .child(
                studio::compact_text_button(
                    "cpu-recent-next",
                    lang.text(TextKey::CpuNextMetadata),
                    !ws.recent_busy && !busy && end < recent.total_sessions,
                    false,
                    colors,
                )
                .on_click(cx.listener(move |this, _, _, cx| {
                    this.refresh_recent(offset.saturating_add(RECENT_PAGE_SIZE), cx)
                })),
            )
            .child(studio::dense_label(
                format!(
                    "{} {}–{} / {}",
                    lang.text(TextKey::CpuSessionMetadataCatalog),
                    offset + usize::from(recent.scanned_sessions != 0),
                    end,
                    recent.total_sessions
                ),
                colors.secondary_text,
            ));
    }
    row.child(div().flex_1()).child(studio::dense_label(
        if busy {
            lang.text(TextKey::CpuWorking)
        } else {
            lang.text(TextKey::CpuOfflineNoCloudAccess)
        },
        colors.secondary_text,
    ))
}

fn empty_state(
    ws: &CpuWorkspace,
    colors: &StudioColors,
    available: f32,
    cx: &Context<CpuWorkspace>,
) -> AnyElement {
    let lang = ws.language;
    let pending = if ws.busy {
        lang.text(TextKey::CpuKpiPendingLoading)
    } else if ws.error.is_some() {
        lang.text(TextKey::CpuKpiPendingFailed)
    } else {
        lang.text(TextKey::CpuKpiPendingNoSession)
    };
    let pending_strip = pending_kpi_strip(ws, colors, available, pending);
    let mut column = div()
        .id("cpu-empty-state")
        .flex_1()
        .min_h_0()
        .overflow_y_scroll()
        .flex()
        .flex_col()
        .gap(px(studio::metrics::PANEL_GAP));
    column = column.child(pending_strip);
    if let Some(recent) = &ws.recent {
        let mut card = studio::card(colors).child(studio::section_title(
            lang.text(TextKey::CpuSessionMetadataCatalog),
            colors,
        ));
        if recent.rows.is_empty() {
            card = card.child(studio::supporting(
                lang.text(TextKey::CpuNoCpuAssociationsOnThisMetadataPage),
                colors,
            ));
        }
        for (index, row) in recent.rows.iter().enumerate() {
            let selected = row.clone();
            let reselect = row.clone();
            card =
                card.child(
                    div()
                        .flex()
                        .items_center()
                        .gap(px(6.))
                        .child(
                            studio::compact_text_button(
                                ("cpu-recent-open", index),
                                lang.text(TextKey::CpuOpenSavedSnapshot),
                                !ws.busy,
                                false,
                                colors,
                            )
                            .on_click(cx.listener(
                                move |this, _, _, cx| this.open_recent(selected.clone(), cx),
                            )),
                        )
                        .child(
                            studio::compact_text_button(
                                ("cpu-recent-reselect", index),
                                lang.text(TextKey::CpuReselectSource),
                                !ws.busy,
                                false,
                                colors,
                            )
                            .on_click(cx.listener(
                                move |this, _, _, cx| this.pick_source(Some(reselect.clone()), cx),
                            )),
                        )
                        .child(studio::dense_label(
                            format!(
                                "SHA-256 {} · {} bytes · g{}",
                                row.binding.source_digest, row.byte_length, row.binding.generation
                            ),
                            colors.secondary_text,
                        )),
                );
        }
        column = column.child(card);
    }
    column
        .child(
            studio::card(colors)
                .child(studio::supporting(
                    lang.text(TextKey::CpuImportASimpleperfProtobufFileToInspect),
                    colors,
                ))
                .child(studio::supporting(
                    lang.text(TextKey::CpuImportPerfDataHint),
                    colors,
                ))
                .child(studio::dense_label(
                    converter_caption(ws),
                    colors.secondary_text,
                ))
                .child(
                    div()
                        .flex()
                        .items_center()
                        .gap(px(6.))
                        .child(
                            studio::text_button(
                                "cpu-empty-import",
                                lang.text(TextKey::CpuImportProfile),
                                !ws.busy,
                                true,
                                colors,
                            )
                            .on_click(cx.listener(|this, _, _, cx| this.pick_file(cx))),
                        )
                        .child(
                            studio::compact_text_button(
                                "cpu-empty-select-simpleperf",
                                lang.text(TextKey::CpuSelectSimpleperf),
                                !ws.busy,
                                false,
                                colors,
                            )
                            .on_click(cx.listener(|this, _, _, cx| this.pick_converter(cx))),
                        ),
                ),
        )
        .into_any_element()
}

fn converter_caption(ws: &CpuWorkspace) -> String {
    let lang = ws.language;
    if let Some(path) = ws.converter.as_deref() {
        return lang.format(
            TextKey::CpuSimpleperfPathFormat,
            &[("path", &path.display().to_string())],
        );
    }
    match discover_simpleperf() {
        Some(path) => lang.format(
            TextKey::CpuSimpleperfPathFormat,
            &[("path", &path.display().to_string())],
        ),
        None => lang.text(TextKey::CpuSimpleperfMissing).to_owned(),
    }
}

fn pending_kpi_strip(
    ws: &CpuWorkspace,
    colors: &StudioColors,
    available: f32,
    pending: &str,
) -> AnyElement {
    let lang = ws.language;
    let kinds = [
        ("Samples", TextKey::CpuKpiSamples),
        ("Threads", TextKey::CpuKpiThreads),
        ("CaptureDuration", TextKey::CpuKpiCaptureDuration),
        ("EffectiveSampleRate", TextKey::CpuKpiEffectiveSampleRate),
    ];
    let columns = kpi_columns(available, kinds.len());
    let mut strip = div().flex().flex_col().gap(px(studio::metrics::CARD_GAP));
    let mut slot = 0usize;
    for chunk in kinds.chunks(columns) {
        let mut row = div()
            .flex()
            .gap(px(studio::metrics::CARD_GAP))
            .items_stretch();
        for (_, label) in chunk {
            let tile_id = slot;
            slot += 1;
            row = row.child(
                studio::kpi_tile(
                    ("cpu-kpi-pending", tile_id),
                    lang.text(*label),
                    "—",
                    pending,
                    colors.accent,
                    colors,
                )
                .flex_1()
                .min_w_0(),
            );
        }
        for _ in chunk.len()..columns {
            row = row.child(div().flex_1());
        }
        strip = strip.child(row);
    }
    strip.into_any_element()
}

#[derive(Clone)]
struct CpuKpis {
    sample_count: u64,
    lost_samples: u64,
    lost_events: u64,
    lost_rate: f64,
    thread_count: u64,
    duration_nanos: Option<u64>,
    rate_hz: Option<f64>,
    invalid_samples: u64,
    top_function: Option<(String, f64)>,
}

impl CpuKpis {
    fn from_workspace(ws: &CpuWorkspace) -> Self {
        let Some(catalog) = &ws.catalog else {
            return Self {
                sample_count: 0,
                lost_samples: 0,
                lost_events: 0,
                lost_rate: 0.0,
                thread_count: 0,
                duration_nanos: None,
                rate_hz: None,
                invalid_samples: 0,
                top_function: None,
            };
        };
        let summary = &catalog.summary;
        let duration = summary
            .first_timestamp_nanos
            .zip(summary.last_timestamp_nanos)
            .and_then(|(start, end)| end.checked_sub(start));
        let rate_hz = duration
            .filter(|duration| *duration > 0 && summary.sample_count > 0)
            .map(|duration| summary.sample_count as f64 * 1_000_000_000.0 / duration as f64);
        let lost = summary.lost_sample_count;
        let total = summary.sample_count.saturating_add(lost);
        let lost_rate = if total > 0 {
            lost as f64 / total as f64
        } else {
            0.0
        };
        let total_weight = match ws.query.weight_type {
            WeightType::Samples => summary.sample_count,
            WeightType::EventCount => summary.total_event_count,
        };
        let top_function = ws.overview_top.as_ref().and_then(|page| {
            page.rows.first().and_then(|row| {
                (total_weight > 0 && row.self_weight > 0).then(|| {
                    (
                        row.name.clone(),
                        row.self_weight as f64 / total_weight as f64,
                    )
                })
            })
        });
        Self {
            sample_count: summary.sample_count,
            lost_samples: lost,
            lost_events: summary.lost_event_count,
            lost_rate,
            thread_count: summary.thread_count as u64,
            duration_nanos: duration,
            rate_hz,
            invalid_samples: lost,
            top_function,
        }
    }

    fn visible(&self) -> Vec<KpiKind> {
        let mut kinds = vec![
            KpiKind::Samples,
            KpiKind::Threads,
            KpiKind::CaptureDuration,
            KpiKind::EffectiveSampleRate,
        ];
        if self.invalid_samples > 0 {
            kinds.push(KpiKind::InvalidSamples);
        }
        if self.top_function.is_some() {
            kinds.push(KpiKind::TopFunctionShare);
        }
        kinds
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum KpiKind {
    Samples,
    Threads,
    CaptureDuration,
    EffectiveSampleRate,
    InvalidSamples,
    TopFunctionShare,
}

fn kpi_columns(available_width: f32, count: usize) -> usize {
    let fit = if available_width >= studio::metrics::KPI_WIDE_WIDTH {
        KPI_MAX_COLUMNS
    } else if available_width >= studio::metrics::KPI_MEDIUM_WIDTH {
        KPI_MEDIUM_COLUMNS
    } else {
        KPI_NARROW_COLUMNS
    };
    fit.min(count.max(1))
}

fn kpi_strip(ws: &CpuWorkspace, colors: &StudioColors, available: f32) -> AnyElement {
    let lang = ws.language;
    let kpis = CpuKpis::from_workspace(ws);
    let kinds = kpis.visible();
    let columns = kpi_columns(available, kinds.len());
    let unavailable = lang.text(TextKey::CpuKpiUnavailable);
    let mut strip = div().flex().flex_col().gap(px(studio::metrics::CARD_GAP));
    let mut slot = 0usize;
    for chunk in kinds.chunks(columns) {
        let mut row = div()
            .flex()
            .gap(px(studio::metrics::CARD_GAP))
            .items_stretch();
        for kind in chunk {
            let tile_id = slot;
            slot += 1;
            let (label, value, support) = kpi_values(*kind, &kpis, lang, unavailable);
            let tone = if *kind == KpiKind::InvalidSamples {
                colors.warning
            } else {
                colors.accent
            };
            row = row.child(
                studio::kpi_tile(("cpu-kpi", tile_id), label, value, support, tone, colors)
                    .flex_1()
                    .min_w_0(),
            );
        }
        for _ in chunk.len()..columns {
            row = row.child(div().flex_1());
        }
        strip = strip.child(row);
    }
    strip.into_any_element()
}

fn kpi_values(
    kind: KpiKind,
    kpis: &CpuKpis,
    lang: Language,
    unavailable: &str,
) -> (String, String, String) {
    match kind {
        KpiKind::Samples => {
            let lost = format_count(kpis.lost_samples);
            let rate = format_percent(kpis.lost_rate);
            (
                lang.text(TextKey::CpuKpiSamples).into(),
                format_count(kpis.sample_count),
                lang.format(
                    TextKey::CpuKpiSamplesSupportFormat,
                    &[("lost", &lost), ("rate", &rate)],
                ),
            )
        }
        KpiKind::Threads => (
            lang.text(TextKey::CpuKpiThreads).into(),
            format_count(kpis.thread_count),
            String::new(),
        ),
        KpiKind::CaptureDuration => {
            let value = kpis
                .duration_nanos
                .map(format_duration)
                .unwrap_or_else(|| unavailable.to_owned());
            let support = match kpis.duration_nanos {
                Some(duration) => lang.format(
                    TextKey::CpuKpiCaptureDurationSupportFormat,
                    &[
                        ("start", &format_duration(0)),
                        ("end", &format_duration(duration)),
                    ],
                ),
                None => String::new(),
            };
            (
                lang.text(TextKey::CpuKpiCaptureDuration).into(),
                value,
                support,
            )
        }
        KpiKind::EffectiveSampleRate => (
            lang.text(TextKey::CpuKpiEffectiveSampleRate).into(),
            kpis.rate_hz
                .map(format_rate)
                .unwrap_or_else(|| unavailable.to_owned()),
            lang.text(TextKey::CpuKpiEffectiveSampleRateSupport).into(),
        ),
        KpiKind::InvalidSamples => {
            let events = format_count(kpis.lost_events);
            (
                lang.text(TextKey::CpuKpiInvalidSamples).into(),
                format_count(kpis.invalid_samples),
                lang.format(
                    TextKey::CpuKpiInvalidSamplesSupportFormat,
                    &[("events", &events)],
                ),
            )
        }
        KpiKind::TopFunctionShare => match &kpis.top_function {
            Some((name, share)) => (
                lang.text(TextKey::CpuKpiTopFunctionShare).into(),
                format_percent(*share),
                name.clone(),
            ),
            None => (
                lang.text(TextKey::CpuKpiTopFunctionShare).into(),
                unavailable.to_owned(),
                String::new(),
            ),
        },
    }
}
#[derive(Clone)]
struct TimelineTrack {
    thread_id: Option<i32>,
    name: String,
    buckets: Vec<u32>,
    total: u32,
}

struct TimelineModel {
    start: u64,
    end: u64,
    tracks: Vec<TimelineTrack>,
}

fn build_timeline(ws: &CpuWorkspace) -> Option<TimelineModel> {
    let frame = ws.timeline.as_ref()?;
    let catalog = ws.catalog.as_ref()?;
    let (start, end) = match frame.domain {
        ChartDomain::SamplePoints {
            start_nanos,
            end_nanos,
        } => (start_nanos, end_nanos),
        _ => return None,
    };
    let mut map: std::collections::BTreeMap<Option<i32>, Vec<u32>> =
        std::collections::BTreeMap::new();
    let width = frame.width_px.max(1.0);
    for block in &frame.blocks {
        if !matches!(
            block.kind,
            ChartBlockKind::SamplePoint | ChartBlockKind::PixelAggregate
        ) {
            continue;
        }
        let bucket = ((block.x / width) * TIMELINE_BUCKETS as f32).floor();
        let bucket = bucket.clamp(0.0, (TIMELINE_BUCKETS - 1) as f32) as usize;
        let counts = map
            .entry(block.thread_id)
            .or_insert_with(|| vec![0u32; TIMELINE_BUCKETS]);
        let samples = block.sample_count.max(1).min(u64::from(u32::MAX)) as u32;
        counts[bucket] = counts[bucket].saturating_add(samples);
    }
    let mut tracks: Vec<TimelineTrack> = map
        .into_iter()
        .map(|(thread_id, buckets)| {
            let total = buckets.iter().copied().fold(0u32, u32::saturating_add);
            let name = match thread_id {
                Some(id) => catalog
                    .threads
                    .iter()
                    .find(|thread| thread.id == id)
                    .map(|thread| thread.name.clone())
                    .unwrap_or_else(|| format!("TID {id}")),
                None => ws.language.text(TextKey::CpuTimelineAllThreads).to_owned(),
            };
            TimelineTrack {
                thread_id,
                name,
                buckets,
                total,
            }
        })
        .collect();
    tracks.sort_by(|a, b| b.total.cmp(&a.total).then_with(|| a.name.cmp(&b.name)));
    Some(TimelineModel { start, end, tracks })
}

fn timeline_panel(
    ws: &CpuWorkspace,
    colors: &StudioColors,
    cx: &Context<CpuWorkspace>,
) -> AnyElement {
    let lang = ws.language;
    let mut panel = div()
        .id("cpu-timeline")
        .w_full()
        .h(px(ws.timeline_height))
        .flex()
        .flex_col()
        .overflow_hidden()
        .bg(colors.panel)
        .border_1()
        .border_color(colors.border)
        .rounded(px(studio::metrics::CARD_RADIUS));
    let model = build_timeline(ws);
    let (visible_tracks, total_tracks) = model
        .as_ref()
        .map(|model| (model.tracks.len(), model.tracks.len()))
        .unwrap_or((0, 0));
    let visible = visible_tracks.to_string();
    let total = total_tracks.to_string();
    let tracks_label = lang.format(
        TextKey::CpuTimelineVisibleTracksFormat,
        &[("visible", &visible), ("total", &total)],
    );
    let mut ruler = div().flex_1().h_full().flex().items_center();
    if let Some(model) = &model {
        for index in 0..6 {
            let fraction = index as f64 / 6.0;
            let offset = ((model.end.saturating_sub(model.start)) as f64 * fraction) as u64;
            ruler = ruler.child(
                div()
                    .flex_1()
                    .h_full()
                    .flex()
                    .items_center()
                    .justify_end()
                    .pr(px(5.))
                    .text_size(px(studio::FONT_SUPPORTING))
                    .text_color(colors.secondary_text)
                    .child(format_duration(offset)),
            );
        }
    }
    panel = panel.child(
        div()
            .h(px(20.))
            .w_full()
            .flex()
            .items_center()
            .bg(colors.toolbar)
            .border_b_1()
            .border_color(colors.border)
            .child(
                div()
                    .w(px(150.))
                    .h_full()
                    .px(px(8.))
                    .flex()
                    .items_center()
                    .gap(px(4.))
                    .border_r_1()
                    .border_color(colors.border)
                    .child(
                        div()
                            .flex_1()
                            .text_size(px(studio::FONT_SUPPORTING))
                            .font_weight(FontWeight::SEMIBOLD)
                            .text_color(colors.text)
                            .whitespace_nowrap()
                            .text_ellipsis()
                            .child(tracks_label),
                    )
                    .child(
                        div()
                            .text_size(px(studio::FONT_SUPPORTING))
                            .text_color(colors.secondary_text)
                            .child(crate::studio::selectable("▾")),
                    ),
            )
            .child(ruler),
    );
    let mut body = div()
        .id("cpu-timeline-tracks")
        .flex_1()
        .min_h_0()
        .w_full()
        .overflow_y_scroll()
        .flex()
        .flex_col()
        .bg(colors.field);
    match model {
        Some(model) if !model.tracks.is_empty() => {
            let max = model
                .tracks
                .iter()
                .flat_map(|track| track.buckets.iter().copied())
                .max()
                .unwrap_or(1)
                .max(1);
            let activity = colors.firefox_activity;
            let domain = (model.start, model.end);
            let paint_drag = ws.chart_drag;
            let selected_range = time_range_fractions(domain, ws.query.start, ws.query.end);
            for (index, track) in model.tracks.iter().take(TIMELINE_MAX_TRACKS).enumerate() {
                let buckets = track.buckets.clone();
                let name = track.name.clone();
                let active = match track.thread_id {
                    Some(id) => ws.query.thread_ids.contains(&id),
                    None => ws.query.thread_ids.is_empty(),
                };
                let thread = track
                    .thread_id
                    .map_or_else(String::new, |id| format!("({id})"));
                let thread_id = track.thread_id;
                let origin: Arc<Mutex<Option<Bounds<Pixels>>>> = Arc::new(Mutex::new(None));
                let paint_origin = origin.clone();
                let down_origin = origin.clone();
                let move_origin = origin.clone();
                let paint_activity = activity;
                let paint_domain = domain;
                let paint_selected = selected_range;
                let paint_drag_for_track = paint_drag;
                body = body.child(
                    div()
                        .id(("cpu-timeline-track", index))
                        .h(px(31.))
                        .w_full()
                        .flex()
                        .items_center()
                        .bg(if active {
                            colors.accent.alpha(0.10)
                        } else {
                            rgba(0x00000000)
                        })
                        .border_b_1()
                        .border_color(colors.border)
                        .child(
                            div()
                                .id(("cpu-timeline-label", index))
                                .w(px(150.))
                                .h_full()
                                .flex()
                                .items_center()
                                .gap(px(4.))
                                .px(px(8.))
                                .cursor_pointer()
                                .border_r_1()
                                .border_color(colors.border)
                                .on_click(cx.listener(move |this, _, _, cx| {
                                    this.select_thread_filter(thread_id, cx)
                                }))
                                .child(
                                    div()
                                        .flex_1()
                                        .min_w_0()
                                        .text_size(px(studio::FONT_SUPPORTING))
                                        .text_color(if active {
                                            colors.accent
                                        } else {
                                            colors.text
                                        })
                                        .when(active, |this| this.font_weight(FontWeight::SEMIBOLD))
                                        .whitespace_nowrap()
                                        .text_ellipsis()
                                        .child(crate::studio::selectable(name)),
                                )
                                .child(
                                    div()
                                        .text_size(px(studio::FONT_SUPPORTING))
                                        .text_color(colors.secondary_text)
                                        .child(thread),
                                ),
                        )
                        .child(
                            div()
                                .flex_1()
                                .h_full()
                                .min_w_0()
                                .bg(colors.panel)
                                .on_mouse_down(
                                    MouseButton::Left,
                                    cx.listener(move |this, event: &MouseDownEvent, _, cx| {
                                        let Ok(guard) = down_origin.lock() else {
                                            return;
                                        };
                                        let Some(bounds) = *guard else {
                                            return;
                                        };
                                        let width = f32::from(bounds.size.width).max(1.0);
                                        let x = f32::from(event.position.x - bounds.origin.x);
                                        this.begin_chart_drag((x / width).clamp(0.0, 1.0), cx);
                                    }),
                                )
                                .on_mouse_move(cx.listener(
                                    move |this, event: &MouseMoveEvent, _, cx| {
                                        if this.chart_drag.is_none() {
                                            return;
                                        }
                                        let Ok(guard) = move_origin.lock() else {
                                            return;
                                        };
                                        let Some(bounds) = *guard else {
                                            return;
                                        };
                                        let width = f32::from(bounds.size.width).max(1.0);
                                        let x = f32::from(event.position.x - bounds.origin.x);
                                        this.update_chart_drag((x / width).clamp(0.0, 1.0), cx);
                                    },
                                ))
                                .on_mouse_up(
                                    MouseButton::Left,
                                    cx.listener(move |this, _, _, cx| {
                                        let dragged = this
                                            .chart_drag
                                            .is_some_and(|(a, b)| (a - b).abs() >= 0.005);
                                        if dragged {
                                            if let Some((a, b)) = this.chart_drag {
                                                this.chart_drag = None;
                                                this.apply_time_fraction(paint_domain, (a, b), cx);
                                            }
                                        } else {
                                            this.chart_drag = None;
                                            cx.notify();
                                        }
                                    }),
                                )
                                .child(
                                    canvas(
                                        move |bounds, _, _| {
                                            if let Ok(mut origin) = paint_origin.lock() {
                                                *origin = Some(bounds);
                                            }
                                        },
                                        move |bounds, _, window, _| {
                                            let width = f32::from(bounds.size.width)
                                                / TIMELINE_BUCKETS as f32;
                                            let height = f32::from(bounds.size.height);
                                            for (bucket, count) in buckets.iter().enumerate() {
                                                if *count == 0 {
                                                    continue;
                                                }
                                                let fraction = *count as f32 / max as f32;
                                                let bar = (height * fraction)
                                                    .max(1.0)
                                                    .min(height.max(1.0));
                                                let x = bounds.origin.x + px(bucket as f32 * width);
                                                let y = bounds.origin.y + px(height - bar);
                                                window.paint_quad(quad(
                                                    Bounds {
                                                        origin: point(x, y),
                                                        size: size(px(width.max(1.0)), px(bar)),
                                                    },
                                                    px(0.),
                                                    paint_activity,
                                                    px(0.),
                                                    transparent_black(),
                                                    Default::default(),
                                                ));
                                            }
                                            let mut band = |from: f32, to: f32, fill: Rgba| {
                                                let (lo, hi) = if from <= to {
                                                    (from, to)
                                                } else {
                                                    (to, from)
                                                };
                                                if hi - lo <= 0.0 {
                                                    return;
                                                }
                                                let x = bounds.origin.x
                                                    + px(lo * f32::from(bounds.size.width));
                                                let width_band =
                                                    px((hi - lo) * f32::from(bounds.size.width));
                                                window.paint_quad(quad(
                                                    Bounds {
                                                        origin: point(x, bounds.origin.y),
                                                        size: size(width_band, px(height)),
                                                    },
                                                    px(0.),
                                                    fill,
                                                    px(0.),
                                                    transparent_black(),
                                                    Default::default(),
                                                ));
                                            };
                                            if let Some((a, b)) = paint_drag_for_track {
                                                band(a, b, paint_activity.alpha(0.35));
                                            }
                                            if let Some((lo, hi)) = paint_selected {
                                                band(lo, hi, paint_activity.alpha(0.18));
                                            }
                                        },
                                    )
                                    .size_full(),
                                ),
                        ),
                );
            }
        }
        _ => {
            body = body.child(
                div()
                    .p(px(16.))
                    .text_size(px(studio::FONT_BODY))
                    .text_color(colors.secondary_text)
                    .child(if ws.timeline.is_none() {
                        lang.text(TextKey::CpuKpiPendingLoading)
                    } else {
                        lang.text(TextKey::CpuStackNoSamples)
                    }),
            );
        }
    }
    panel.child(body).into_any_element()
}

/// Absolute sample-time range as normalized fractions of a timeline domain.
fn time_range_fractions(
    domain: (u64, u64),
    start: Option<u64>,
    end: Option<u64>,
) -> Option<(f32, f32)> {
    if start.is_none() && end.is_none() {
        return None;
    }
    let (from, to) = domain;
    let span = to.saturating_sub(from) as f64;
    if span <= 0.0 {
        return None;
    }
    let lo = ((start.unwrap_or(from).saturating_sub(from)) as f64 / span).clamp(0.0, 1.0);
    let hi = ((end.unwrap_or(to).saturating_sub(from)) as f64 / span).clamp(0.0, 1.0);
    (hi > lo).then_some((lo as f32, hi as f32))
}

fn timeline_handle(
    ws: &CpuWorkspace,
    colors: &StudioColors,
    cx: &Context<CpuWorkspace>,
) -> AnyElement {
    let _ = ws;
    studio::resize_handle(colors)
        .id("cpu-timeline-handle")
        .on_mouse_down(
            MouseButton::Left,
            cx.listener(|this, event: &MouseDownEvent, _, cx| {
                this.begin_timeline_drag(f32::from(event.position.y), cx)
            }),
        )
        .into_any_element()
}

fn tab_row(ws: &CpuWorkspace, colors: &StudioColors, cx: &Context<CpuWorkspace>) -> AnyElement {
    let lang = ws.language;
    let mut tabs = div().flex().items_center().gap(px(3.));
    for tab in CpuTab::ALL {
        let id = match tab {
            CpuTab::Overview => "cpu-tab-overview",
            CpuTab::FlameGraph => "cpu-tab-flame",
            CpuTab::CallTree => "cpu-tab-call-tree",
            CpuTab::TopFunctions => "cpu-tab-top",
            CpuTab::StackChart => "cpu-tab-stack",
            CpuTab::MarkerChart => "cpu-tab-marker-chart",
            CpuTab::MarkerTable => "cpu-tab-marker-table",
        };
        tabs = tabs.child(
            studio::tab_button(id, tab.label(lang), ws.tab == tab, colors).on_click(cx.listener(
                move |this, _, window, cx| {
                    window.focus(&this.page_focus, cx);
                    this.switch_tab(tab, cx)
                },
            )),
        );
    }
    let ai = if let Some(studio) = ws.studio.clone() {
        studio::text_button(
            "cpu-run-ai",
            lang.text(TextKey::CpuWorkspaceRunAiAnalysis),
            !ws.busy,
            false,
            colors,
        )
        .on_click(move |_, window, cx| {
            studio
                .update(cx, |studio, cx| studio.prepare_ai(window, cx))
                .ok();
        })
        .into_any_element()
    } else {
        studio::text_button(
            "cpu-run-ai",
            lang.text(TextKey::CpuWorkspaceRunAiAnalysis),
            false,
            false,
            colors,
        )
        .into_any_element()
    };
    let details_label = if ws.details_visible {
        lang.text(TextKey::CpuWorkspaceHideDetails)
    } else {
        lang.text(TextKey::CpuWorkspaceShowDetails)
    };
    div()
        .flex()
        .items_center()
        .gap(px(6.))
        .h(px(studio::metrics::TAB_HEIGHT))
        .track_focus(&ws.page_focus)
        .on_key_down(cx.listener(|this, event: &KeyDownEvent, _, cx| {
            match event.keystroke.key.to_ascii_lowercase().as_str() {
                "left" => this.cycle_tab(false, cx),
                "right" => this.cycle_tab(true, cx),
                _ => {}
            }
        }))
        .child(
            div()
                .id("cpu-tabs")
                .flex_1()
                .min_w_0()
                .overflow_x_scroll()
                .child(tabs),
        )
        .child(export_button(ws, colors, cx))
        .child(ai)
        .child(
            studio::text_button("cpu-details-toggle", details_label, true, false, colors).on_click(
                cx.listener(|this, _, _, cx| this.set_details_visible(!this.details_visible, cx)),
            ),
        )
        .into_any_element()
}

fn export_button(
    ws: &CpuWorkspace,
    colors: &StudioColors,
    cx: &Context<CpuWorkspace>,
) -> AnyElement {
    let lang = ws.language;
    let entity = cx.entity();
    let csv_label = lang.text(TextKey::CpuCopyCurrentQueryCsv).to_owned();
    let firefox_label = lang.text(TextKey::CpuExportFirefoxProfile).to_owned();
    let labels: Vec<(ExportKind, String)> = [
        ExportKind::ReportJson,
        ExportKind::Folded,
        ExportKind::Html,
        ExportKind::Pprof,
        ExportKind::SessionPackage,
    ]
    .into_iter()
    .map(|kind| (kind, kind.label(lang).to_owned()))
    .collect();
    let can_csv = !ws.busy && matches!(ws.tab, CpuTab::CallTree | CpuTab::TopFunctions);
    let can_save = !ws.busy && ws.session.is_some();
    let copy_entity = entity.clone();
    let firefox_entity = entity.clone();
    let export_entity = entity;
    studio::text_button(
        "cpu-export",
        lang.text(TextKey::CpuWorkspaceExport),
        can_csv || can_save,
        false,
        colors,
    )
    .dropdown_menu(move |menu, _, _| {
        let copy = copy_entity.clone();
        let firefox = firefox_entity.clone();
        let mut menu = menu.item(
            PopupMenuItem::new(csv_label.clone())
                .disabled(!can_csv)
                .on_click(move |_, _, cx| {
                    copy.update(cx, |this, cx| this.copy_csv(cx));
                }),
        );
        for (kind, label) in &labels {
            let kind = *kind;
            let entity = export_entity.clone();
            let enabled = can_save;
            menu = menu.item(
                PopupMenuItem::new(label.clone())
                    .disabled(!enabled)
                    .on_click(move |_, _, cx| {
                        entity.update(cx, |this, cx| this.save_export(kind, cx));
                    }),
            );
        }
        menu.separator().item(
            PopupMenuItem::new(firefox_label.clone())
                .disabled(!can_save)
                .on_click(move |_, _, cx| {
                    firefox.update(cx, |this, cx| this.save_firefox(cx));
                }),
        )
    })
    .into_any_element()
}

fn filter_toolbar(
    ws: &CpuWorkspace,
    colors: &StudioColors,
    cx: &Context<CpuWorkspace>,
) -> AnyElement {
    let lang = ws.language;
    let direction = match ws.query.direction {
        Direction::Forward => lang.text(TextKey::CpuForward),
        Direction::Inverted => lang.text(TextKey::CpuInverted),
    };
    let weight = match ws.query.weight_type {
        WeightType::Samples => lang.text(TextKey::CpuSampleWeight),
        WeightType::EventCount => lang.text(TextKey::CpuEventWeight),
    };
    let thread = ws
        .query
        .thread_ids
        .first()
        .map(ToString::to_string)
        .unwrap_or_else(|| lang.text(TextKey::CpuAll).to_owned());
    let event = ws
        .query
        .event_types
        .first()
        .cloned()
        .unwrap_or_else(|| lang.text(TextKey::CpuAll).to_owned());
    let mut row = div()
        .id("cpu-filter-toolbar")
        .flex()
        .items_center()
        .gap(px(4.))
        .h(px(28.))
        .px(px(5.))
        .overflow_x_scroll()
        .bg(colors.toolbar)
        .child(
            studio::compact_text_button("cpu-direction", direction, !ws.busy, false, colors)
                .on_click(cx.listener(|this, _, _, cx| this.toggle_direction(cx))),
        )
        .child(
            studio::compact_text_button("cpu-weight", weight, !ws.busy, false, colors)
                .on_click(cx.listener(|this, _, _, cx| this.toggle_weight(cx))),
        )
        .child(
            div()
                .w(px(studio::metrics::HAIRLINE))
                .h(px(19.))
                .bg(colors.border),
        )
        .child(
            studio::compact_text_button(
                "cpu-thread",
                format!("{}: {}", lang.text(TextKey::CpuThread), thread),
                !ws.busy,
                false,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.cycle_thread(cx))),
        )
        .child(
            studio::compact_text_button(
                "cpu-event",
                format!("{}: {}", lang.text(TextKey::CpuEvent), event),
                !ws.busy,
                false,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.cycle_event(cx))),
        )
        .child(div().w(px(120.)).child(Input::new(&ws.start).h(px(22.))))
        .child(div().w(px(120.)).child(Input::new(&ws.end).h(px(22.))))
        .child(
            studio::compact_text_button(
                "cpu-time",
                lang.text(TextKey::CpuApplyTimeRange),
                !ws.busy,
                false,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.apply_time(cx))),
        );
    if ws.query.start.is_some() || ws.query.end.is_some() {
        row = row.child(
            studio::compact_text_button(
                "cpu-filter-reset-range",
                lang.text(TextKey::CpuWorkspaceResetRange),
                !ws.busy,
                false,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.reset_time_range(cx))),
        );
    }
    if ws.tab == CpuTab::FlameGraph {
        row = row
            .child(
                studio::compact_text_button(
                    "zoom-in",
                    lang.text(TextKey::CpuZoomIn),
                    !ws.busy,
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.zoom(true, cx))),
            )
            .child(
                studio::compact_text_button(
                    "zoom-out",
                    lang.text(TextKey::CpuZoomOut),
                    !ws.busy,
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.zoom(false, cx))),
            );
    }
    row.child(div().flex_1())
        .child(
            div()
                .text_size(px(studio::FONT_SUPPORTING))
                .text_color(colors.secondary_text)
                .whitespace_nowrap()
                .child(crate::studio::selectable(
                    lang.text(TextKey::CpuWorkspaceFilterStacks),
                )),
        )
        .child(div().w(px(220.)).child(Input::new(&ws.search).h(px(22.))))
        .into_any_element()
}

fn breadcrumb(ws: &CpuWorkspace, colors: &StudioColors, cx: &Context<CpuWorkspace>) -> AnyElement {
    let lang = ws.language;
    let mut row = div()
        .flex()
        .items_center()
        .gap(px(4.))
        .h(px(studio::metrics::BREADCRUMB_HEIGHT))
        .px(px(10.))
        .child(
            div()
                .text_size(px(studio::FONT_SUPPORTING))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(colors.secondary_text)
                .whitespace_nowrap()
                .child(crate::studio::selectable(
                    lang.text(TextKey::CpuWorkspaceSelectedPath),
                )),
        );
    let path = selected_path(ws);
    if path.is_empty() {
        row = row.child(
            div()
                .text_size(px(studio::FONT_SUPPORTING))
                .text_color(colors.secondary_text)
                .child(crate::studio::selectable(
                    lang.text(TextKey::CpuWorkspaceNoSelectionPath),
                )),
        );
    } else {
        let current = ws.row_selected.as_deref();
        for (index, (id, name)) in path.iter().enumerate() {
            if index > 0 {
                row = row.child(
                    div()
                        .text_size(px(studio::FONT_SUPPORTING))
                        .text_color(colors.secondary_text)
                        .child(crate::studio::selectable("›")),
                );
            }
            let is_current = current == Some(id.as_str());
            let target = id.clone();
            row = row.child(
                div()
                    .id(("cpu-crumb", index))
                    .cursor_pointer()
                    .px(px(2.))
                    .text_size(px(studio::FONT_SUPPORTING))
                    .text_color(if is_current {
                        colors.accent
                    } else {
                        colors.text
                    })
                    .when(is_current, |this| this.font_weight(FontWeight::SEMIBOLD))
                    .whitespace_nowrap()
                    .child(crate::studio::selectable(name.clone()))
                    .on_click(
                        cx.listener(move |this, _, _, cx| this.select_row(target.clone(), cx)),
                    ),
            );
        }
    }
    row.into_any_element()
}

fn selected_path(ws: &CpuWorkspace) -> Vec<(String, String)> {
    let Some(selected) = ws.row_selected.as_deref() else {
        return ws
            .chart_selected
            .as_deref()
            .and_then(|id| ws.chart.as_ref()?.selected(id))
            .map(|block| vec![(block.id.clone(), block.label.clone())])
            .unwrap_or_default();
    };
    let Some(tree) = &ws.tree else {
        return Vec::new();
    };
    let by_id: std::collections::BTreeMap<&str, &QueryRow> =
        tree.rows.iter().map(|row| (row.id.as_str(), row)).collect();
    let mut path = Vec::new();
    let mut current = by_id.get(selected).copied();
    while let Some(row) = current {
        path.push((row.id.clone(), row.name.clone()));
        current = row
            .parent_id
            .as_deref()
            .and_then(|parent| by_id.get(parent).copied());
        if path.len() > 512 {
            break;
        }
    }
    path.reverse();
    path
}

fn content_and_details(
    ws: &CpuWorkspace,
    colors: &StudioColors,
    width: f32,
    cx: &Context<CpuWorkspace>,
) -> AnyElement {
    let content = tab_content(ws, colors, cx);
    if !ws.details_visible {
        return div()
            .flex_1()
            .min_h_0()
            .flex()
            .flex_col()
            .child(content)
            .into_any_element();
    }
    if width < 1000.0 {
        div()
            .flex_1()
            .min_h_0()
            .flex()
            .flex_col()
            .gap(px(6.))
            .child(div().flex_1().min_h_0().flex().flex_col().child(content))
            .child(
                div()
                    .h(px(studio::metrics::NARROW_DETAILS_HEIGHT))
                    .flex_shrink_0()
                    .child(details_pane(ws, colors, cx)),
            )
            .into_any_element()
    } else {
        div()
            .flex_1()
            .min_h_0()
            .flex()
            .gap(px(6.))
            .child(div().flex_1().min_w_0().flex().flex_col().child(content))
            .child(
                div()
                    .w(px(studio::metrics::DETAILS_WIDTH))
                    .flex_shrink_0()
                    .child(details_pane(ws, colors, cx)),
            )
            .into_any_element()
    }
}

fn tab_content(ws: &CpuWorkspace, colors: &StudioColors, cx: &Context<CpuWorkspace>) -> AnyElement {
    match ws.tab {
        CpuTab::Overview => overview_panel(ws, colors, cx),
        CpuTab::FlameGraph => chart_panel(ws, colors, cx),
        CpuTab::CallTree => call_tree_panel(ws, colors, cx),
        CpuTab::TopFunctions => top_functions_panel(ws, colors, cx),
        CpuTab::StackChart => stack_panel(ws, colors, cx),
        CpuTab::MarkerChart | CpuTab::MarkerTable => marker_panel(ws, colors),
    }
}

fn panel_message(ws: &CpuWorkspace, colors: &StudioColors, text: &str) -> AnyElement {
    let _ = ws;
    div()
        .size_full()
        .p(px(16.))
        .text_size(px(studio::FONT_BODY))
        .text_color(colors.secondary_text)
        .child(crate::studio::selectable(text.to_owned()))
        .into_any_element()
}

fn overview_panel(
    ws: &CpuWorkspace,
    colors: &StudioColors,
    cx: &Context<CpuWorkspace>,
) -> AnyElement {
    let lang = ws.language;
    let Some(catalog) = &ws.catalog else {
        return panel_message(ws, colors, lang.text(TextKey::CpuKpiPendingNoSession));
    };
    let kpis = CpuKpis::from_workspace(ws);
    let mut column = div()
        .id("cpu-overview")
        .size_full()
        .overflow_y_scroll()
        .flex()
        .flex_col()
        .gap(px(studio::metrics::PANEL_GAP));
    let metrics = [
        (
            lang.text(TextKey::CpuKpiSamples).to_owned(),
            format_count(kpis.sample_count),
        ),
        (
            lang.text(TextKey::CpuEventWeight).to_owned(),
            format_count(catalog.summary.total_event_count),
        ),
        (
            lang.text(TextKey::CpuKpiThreads).to_owned(),
            format_count(kpis.thread_count),
        ),
        (
            lang.text(TextKey::CpuDetailsLostRate).to_owned(),
            format_percent(kpis.lost_rate),
        ),
    ];
    let mut cards = div().flex().flex_wrap().gap(px(studio::metrics::CARD_GAP));
    for (index, (label, value)) in metrics.into_iter().enumerate() {
        cards = cards.child(
            studio::metric_card(
                ("cpu-overview-metric", index),
                label,
                value,
                colors.accent,
                colors,
            )
            .flex_1()
            .min_w(px(150.)),
        );
    }
    column = column.child(cards);
    column = column.child(data_quality_card(ws, colors));
    column = column.child(artifacts_card(ws, colors));
    column = column.child(studio::section_title(
        lang.text(TextKey::CpuOverviewTopThreads),
        colors,
    ));
    if catalog.threads.is_empty() {
        column = column.child(studio::supporting(
            lang.text(TextKey::CpuKpiUnavailable),
            colors,
        ));
    } else {
        for thread in catalog.threads.iter().take(OVERVIEW_ROW_LIMIT) {
            column = column.child(studio::dense_label(
                format!("{} · TID {}", thread.name, thread.id),
                colors.text,
            ));
        }
    }
    column = column.child(studio::section_title(
        lang.text(TextKey::CpuOverviewTopFunctions),
        colors,
    ));
    match &ws.overview_top {
        Some(page) if !page.rows.is_empty() => {
            for (index, row) in page.rows.iter().take(OVERVIEW_ROW_LIMIT).enumerate() {
                let id = row.id.clone();
                let name = row.name.clone();
                let inclusive = row.inclusive_weight.to_string();
                let own = row.self_weight.to_string();
                let line = lang.format(
                    TextKey::CpuOverviewFunctionRowFormat,
                    &[("name", &name), ("inclusive", &inclusive), ("own", &own)],
                );
                column = column.child(
                    div()
                        .id(("cpu-overview-function", index))
                        .cursor_pointer()
                        .px(px(4.))
                        .py(px(2.))
                        .rounded(px(studio::metrics::SMALL_RADIUS))
                        .text_size(px(studio::FONT_SUPPORTING))
                        .text_color(colors.text)
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.row_selected = Some(id.clone());
                            this.switch_tab(CpuTab::TopFunctions, cx);
                        }))
                        .child(line),
                );
            }
        }
        _ => {
            column = column.child(studio::supporting(
                if ws.shared_busy {
                    lang.text(TextKey::CpuKpiPendingLoading)
                } else {
                    lang.text(TextKey::CpuKpiUnavailable)
                },
                colors,
            ));
        }
    }
    column.into_any_element()
}

fn data_quality_card(ws: &CpuWorkspace, colors: &StudioColors) -> AnyElement {
    let lang = ws.language;
    let Some(catalog) = &ws.catalog else {
        return div().into_any_element();
    };
    let summary = &catalog.summary;
    studio::card(colors)
        .child(studio::section_title(
            lang.text(TextKey::CpuDetailsDataQuality),
            colors,
        ))
        .child(studio::dense_label(
            format!(
                "{}: {}",
                lang.text(TextKey::CpuLostSamples),
                format_count(summary.lost_sample_count)
            ),
            colors.text,
        ))
        .child(studio::dense_label(
            format!(
                "{}: {}",
                lang.text(TextKey::CpuDetailsLostEvents),
                format_count(summary.lost_event_count)
            ),
            colors.text,
        ))
        .child(studio::dense_label(
            format!(
                "{}: {}",
                lang.text(TextKey::CpuDetailsLostRate),
                format_percent(if summary.sample_count + summary.lost_sample_count > 0 {
                    summary.lost_sample_count as f64
                        / (summary.sample_count + summary.lost_sample_count) as f64
                } else {
                    0.0
                })
            ),
            colors.text,
        ))
        .child(studio::dense_label(
            format!(
                "{}: {}",
                lang.text(TextKey::CpuEvent),
                summary.event_types.len()
            ),
            colors.text,
        ))
        .into_any_element()
}

/// Imported-session evidence shown in the Overview panel (source identity,
/// size, durable association). Simpleperf protobuf carries no screenshots or
/// session package, so those artifact rows are intentionally absent.
fn artifacts_card(ws: &CpuWorkspace, colors: &StudioColors) -> AnyElement {
    let lang = ws.language;
    let Some(catalog) = &ws.catalog else {
        return div().into_any_element();
    };
    let mut card = studio::card(colors)
        .child(studio::section_title(
            lang.text(TextKey::CpuDetailsSource),
            colors,
        ))
        .child(studio::dense_label(
            catalog.source_label.clone(),
            colors.text,
        ))
        .child(studio::dense_label(
            format!("SHA-256 {}", catalog.source_digest),
            colors.secondary_text,
        ))
        .child(studio::dense_label(
            lang.format(
                TextKey::ProfilerCpuByteCount,
                &[("bytes", &catalog.byte_length)],
            ),
            colors.secondary_text,
        ));
    card = match &ws.association {
        Some(binding) => card.child(studio::dense_label(
            format!("{} · g{}", binding.session_id, binding.generation),
            colors.secondary_text,
        )),
        None => card.child(studio::dense_label(
            lang.text(TextKey::CpuMemoryOnlyNoValidDurableAssociation),
            colors.secondary_text,
        )),
    };
    card.into_any_element()
}

fn visible_row_indices(rows: &[QueryRow], collapsed: &BTreeSet<String>) -> Vec<usize> {
    let mut visible = Vec::new();
    let mut hidden_below: Option<usize> = None;
    for (index, row) in rows.iter().enumerate() {
        if let Some(depth) = hidden_below {
            if row.depth > depth {
                continue;
            }
            hidden_below = None;
        }
        if collapsed.contains(&row.id) {
            hidden_below = Some(row.depth);
        }
        visible.push(index);
    }
    visible
}

fn table_value(text: impl Into<SharedString>, width: f32, color: Rgba) -> Div {
    div()
        .w(px(width))
        .flex_shrink_0()
        .px(px(5.))
        .text_size(px(studio::FONT_SUPPORTING))
        .text_color(color)
        .whitespace_nowrap()
        .text_ellipsis()
        .child(crate::studio::selectable(text.into()))
}

fn call_tree_panel(
    ws: &CpuWorkspace,
    colors: &StudioColors,
    cx: &Context<CpuWorkspace>,
) -> AnyElement {
    let lang = ws.language;
    let Some(tree) = &ws.tree else {
        let message = if ws.busy {
            lang.text(TextKey::CpuKpiPendingLoading)
        } else {
            lang.text(TextKey::CpuKpiUnavailable)
        };
        return panel_message(ws, colors, message);
    };
    let visible = ws.tree_visible.len();
    let total = tree.rows.len();
    let mut count_label = format!("{visible} / {total}");
    if tree.truncated {
        count_label.push_str(" · ");
        count_label.push_str(lang.text(TextKey::CpuChartOutputOmittedBlocks));
    }
    let toolbar = div()
        .flex()
        .items_center()
        .gap(px(4.))
        .h(px(24.))
        .child(
            studio::compact_text_button(
                "call-tree-expand",
                lang.text(TextKey::CpuWorkspaceExpandAll),
                !ws.busy,
                false,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.expand_all(cx))),
        )
        .child(
            studio::compact_text_button(
                "call-tree-collapse",
                lang.text(TextKey::CpuWorkspaceCollapseAll),
                !ws.busy,
                false,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.collapse_all(cx))),
        )
        .child(div().flex_1())
        .child(studio::dense_label(count_label, colors.secondary_text));
    let header = studio::table_header_row(colors)
        .child(studio::table_header_cell(
            Some(95.),
            format!("{} %", lang.text(TextKey::CpuCallTreeTotal)),
            colors,
        ))
        .child(studio::table_header_cell(
            Some(70.),
            lang.text(TextKey::CpuDetailsInclusive),
            colors,
        ))
        .child(studio::table_header_cell(
            Some(70.),
            lang.text(TextKey::CpuDetailsSelf),
            colors,
        ))
        .child(studio::table_header_cell(
            Some(64.),
            lang.text(TextKey::CpuKpiSamples),
            colors,
        ))
        .child(
            div()
                .flex_1()
                .min_w_0()
                .text_size(px(studio::FONT_SUPPORTING))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(colors.secondary_text)
                .child(crate::studio::selectable(
                    lang.text(TextKey::CpuCallTreeFunction),
                )),
        );
    // One horizontal scroller owns the header and the rows together so they stay
    // aligned while the tree pans sideways; the rows keep their own vertical
    // virtual list inside it. The content width is the longest rendered row so
    // deep or long names are reachable instead of being permanently clipped.
    let content_width = ws.tree_content_width;
    let list = v_virtual_list(
        cx.entity(),
        "cpu-call-tree-rows",
        ws.tree_sizes.clone(),
        move |ws, range, _window, cx| ws.call_tree_rows(range, cx),
    )
    // The virtual list scrolls vertically only; the horizontal scroller that
    // also owns the header pans it sideways.
    .track_scroll(&ws.tree_scroll);
    div()
        .size_full()
        .flex()
        .flex_col()
        .gap(px(6.))
        .children(if ws.transforms.is_empty() {
            None
        } else {
            Some(transform_bar(ws, colors, cx))
        })
        .child(toolbar)
        .child(
            div()
                .id("cpu-call-tree")
                .relative()
                .flex_1()
                .min_h_0()
                .w_full()
                .bg(colors.panel)
                .border_1()
                .border_color(colors.border)
                .rounded(px(studio::metrics::PANEL_RADIUS))
                .overflow_hidden()
                .track_focus(&ws.tree_focus)
                .on_key_down(cx.listener(|this, event: &KeyDownEvent, window, cx| {
                    this.handle_call_tree_key(event, window, cx);
                }))
                .child(
                    div()
                        .id("cpu-call-tree-hscroll")
                        .absolute()
                        .inset_0()
                        .overflow_x_scroll()
                        .restrict_scroll_to_axis()
                        .track_scroll(&ws.tree_h_scroll)
                        .child(
                            div()
                                .h_full()
                                .w_full()
                                .min_w(px(content_width))
                                .flex()
                                .flex_col()
                                .child(header)
                                .child(div().flex_1().min_h_0().w_full().child(list)),
                        ),
                )
                .child(
                    div()
                        .absolute()
                        .top(px(CALL_TREE_HEADER_HEIGHT))
                        .bottom(px(0.))
                        .right(px(0.))
                        .child(
                            Scrollbar::new(&ws.tree_scroll)
                                .id("cpu-call-tree-scrollbar-vertical")
                                .axis(ScrollbarAxis::Vertical)
                                .viewport_from_layout(),
                        ),
                )
                .child(
                    div()
                        .absolute()
                        .bottom(px(0.))
                        .left(px(0.))
                        .right(px(0.))
                        .child(
                            Scrollbar::new(&ws.tree_h_scroll)
                                .id("cpu-call-tree-scrollbar-horizontal")
                                .axis(ScrollbarAxis::Horizontal)
                                .viewport_from_layout(),
                        ),
                ),
        )
        .into_any_element()
}

/// Fixed metric columns of a call-tree row (total %, inclusive, self, samples).
const CALL_TREE_FIXED_COLUMNS: f32 = 95. + 70. + 70. + 64.;
/// Header row height, used only to keep the vertical scrollbar below it.
const CALL_TREE_HEADER_HEIGHT: f32 = 28.;
/// Dense supporting font width estimate for the horizontally scrolled labels.
const CALL_TREE_TEXT_WIDTH_ESTIMATE: f32 = 8.;

/// Longest call-tree row: metric columns plus depth indent, disclosure toggle and
/// both the symbol and the path label. Over-estimating only adds empty scroll
/// range; under-estimating would clip the longest name.
fn call_tree_content_width(rows: &[QueryRow]) -> f32 {
    rows.iter()
        .map(|row| {
            let label_width = (row.name.chars().count() + row.resource.chars().count()) as f32
                * CALL_TREE_TEXT_WIDTH_ESTIMATE;
            CALL_TREE_FIXED_COLUMNS
                + row.depth.saturating_mul(10).min(400) as f32
                + 18.
                + 10.
                + 8.
                + 40.
                + label_width
        })
        .fold(0.0, f32::max)
}

fn top_functions_panel(
    ws: &CpuWorkspace,
    colors: &StudioColors,
    cx: &Context<CpuWorkspace>,
) -> AnyElement {
    let lang = ws.language;
    let Some(page) = &ws.top else {
        let message = if ws.busy {
            lang.text(TextKey::CpuKpiPendingLoading)
        } else {
            lang.text(TextKey::CpuKpiUnavailable)
        };
        return panel_message(ws, colors, message);
    };
    let total_weight = match ws.query.weight_type {
        WeightType::Samples => ws
            .catalog
            .as_ref()
            .map_or(0, |catalog| catalog.summary.sample_count),
        WeightType::EventCount => ws
            .catalog
            .as_ref()
            .map_or(0, |catalog| catalog.summary.total_event_count),
    };
    let offset = page.offset;
    let end = offset.saturating_add(page.rows.len());
    let range = format!(
        "{}–{} / {}",
        if page.total == 0 { 0 } else { offset + 1 },
        end,
        page.total
    );
    let mut sort_row = div().flex().items_center().gap(px(4.)).h(px(24.));
    let sorts: [(TopSort, TextKey, &'static str); 6] = [
        (
            TopSort::SelfWeight,
            TextKey::CpuDetailsSelf,
            "top-sort-self",
        ),
        (
            TopSort::InclusiveWeight,
            TextKey::CpuDetailsInclusive,
            "top-sort-inclusive",
        ),
        (
            TopSort::SampleCount,
            TextKey::CpuKpiSamples,
            "top-sort-samples",
        ),
        (
            TopSort::ThreadCount,
            TextKey::CpuKpiThreads,
            "top-sort-threads",
        ),
        (
            TopSort::SymbolName,
            TextKey::CpuCallTreeFunction,
            "top-sort-symbol",
        ),
        (TopSort::FilePath, TextKey::CpuCallTreePath, "top-sort-path"),
    ];
    for (sort, label, id) in sorts {
        sort_row = sort_row.child(
            studio::compact_text_button(
                id,
                lang.text(label),
                !ws.busy,
                ws.top_sort == sort,
                colors,
            )
            .on_click(cx.listener(move |this, _, _, cx| this.set_top_sort(sort, cx))),
        );
    }
    sort_row = sort_row.child(div().flex_1()).child(
        studio::compact_text_button(
            "top-sort-order",
            if ws.top_descending {
                lang.text(TextKey::CpuWorkspaceSortDescending)
            } else {
                lang.text(TextKey::CpuWorkspaceSortAscending)
            },
            !ws.busy,
            false,
            colors,
        )
        .on_click(cx.listener(|this, _, _, cx| this.toggle_top_order(cx))),
    );
    let toolbar = div()
        .flex()
        .items_center()
        .gap(px(4.))
        .h(px(24.))
        .child(div().flex_1())
        .child(studio::dense_label(range, colors.secondary_text))
        .child(
            studio::compact_text_button(
                "top-prev",
                lang.text(TextKey::CommonPrevious),
                !ws.busy && offset > 0,
                false,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.page_by(false, cx))),
        )
        .child(
            studio::compact_text_button(
                "top-next",
                lang.text(TextKey::CommonNext),
                !ws.busy && end < page.total,
                false,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.page_by(true, cx))),
        );
    let header = studio::table_header_row(colors)
        .child(
            div()
                .flex_1()
                .min_w_0()
                .text_size(px(studio::FONT_SUPPORTING))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(colors.secondary_text)
                .child(crate::studio::selectable(
                    lang.text(TextKey::CpuReportTopFunctionsTableFunction),
                )),
        )
        .child(
            div()
                .flex_1()
                .min_w_0()
                .text_size(px(studio::FONT_SUPPORTING))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(colors.secondary_text)
                .child(crate::studio::selectable(
                    lang.text(TextKey::CpuReportTopFunctionsTablePath),
                )),
        )
        .child(studio::table_header_cell(
            Some(110.),
            lang.text(TextKey::CpuDetailsTotalShare),
            colors,
        ))
        .child(studio::table_header_cell(
            Some(80.),
            lang.text(TextKey::CpuDetailsInclusive),
            colors,
        ))
        .child(studio::table_header_cell(
            Some(80.),
            lang.text(TextKey::CpuDetailsSelf),
            colors,
        ))
        .child(studio::table_header_cell(
            Some(64.),
            lang.text(TextKey::CpuKpiSamples),
            colors,
        ))
        .child(studio::table_header_cell(
            Some(64.),
            lang.text(TextKey::CpuKpiThreads),
            colors,
        ))
        .child(studio::table_header_cell(
            Some(150.),
            lang.text(TextKey::CpuReportTopFunctionsTableActions),
            colors,
        ));
    let mut list = div()
        .id("cpu-top-functions")
        .flex_1()
        .min_h_0()
        .overflow_y_scroll()
        .flex()
        .flex_col()
        .gap(px(1.))
        .track_focus(&ws.tree_focus)
        .track_scroll(&ws.top_scroll)
        .on_key_down(cx.listener(|this, event: &KeyDownEvent, window, cx| {
            this.handle_top_functions_key(event, window, cx);
        }));
    for (index, row) in page.rows.iter().enumerate() {
        let selected = ws.row_selected.as_deref() == Some(row.id.as_str());
        let share = if total_weight > 0 {
            row.inclusive_weight as f64 / total_weight as f64
        } else {
            0.0
        };
        let select_id = row.id.clone();
        let path_symbol = row.name.clone();
        let flame_symbol = row.name.clone();
        list = list.child(
            div()
                .id(("cpu-top-row", index))
                .flex()
                .items_center()
                .gap(px(4.))
                .px(px(8.))
                .py(px(7.))
                .bg(if selected {
                    colors.accent.alpha(0.16)
                } else {
                    colors.panel
                })
                .border_1()
                .border_color(if selected {
                    colors.accent
                } else {
                    colors.border
                })
                .rounded(px(9.))
                .cursor_pointer()
                .on_click(cx.listener(move |this, _, window, cx| {
                    window.focus(&this.tree_focus, cx);
                    this.select_row(select_id.clone(), cx)
                }))
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .text_size(px(studio::FONT_SUPPORTING))
                        .font_weight(FontWeight::SEMIBOLD)
                        .text_color(colors.text)
                        .whitespace_nowrap()
                        .text_ellipsis()
                        .child(row.name.clone()),
                )
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .text_size(px(studio::FONT_SUPPORTING))
                        .text_color(colors.secondary_text)
                        .whitespace_nowrap()
                        .text_ellipsis()
                        .child(row.resource.clone()),
                )
                .child(
                    div()
                        .w(px(110.))
                        .flex_shrink_0()
                        .flex()
                        .items_center()
                        .gap(px(4.))
                        .child(
                            div()
                                .w(px(44.))
                                .text_size(px(studio::FONT_SUPPORTING))
                                .text_color(colors.text)
                                .child(format_percent(share)),
                        )
                        .child(
                            div()
                                .w(px(40.))
                                .h(px(4.))
                                .rounded(px(2.))
                                .bg(colors.toolbar)
                                .child(
                                    div()
                                        .h(px(4.))
                                        .w(px(40.0 * share.clamp(0.0, 1.0) as f32))
                                        .rounded(px(2.))
                                        .bg(colors.accent),
                                ),
                        ),
                )
                .child(table_value(
                    format_count(row.inclusive_weight),
                    80.,
                    colors.text,
                ))
                .child(table_value(format_count(row.self_weight), 80., colors.text))
                .child(table_value(
                    format_count(row.sample_count),
                    64.,
                    colors.text,
                ))
                .child(table_value(
                    format_count(row.thread_count as u64),
                    64.,
                    colors.text,
                ))
                .child(
                    div()
                        .w(px(150.))
                        .flex_shrink_0()
                        .flex()
                        .items_center()
                        .gap(px(4.))
                        .child(
                            studio::compact_text_button(
                                ("cpu-top-path", index),
                                lang.text(TextKey::CpuCallTreePath),
                                !ws.busy,
                                false,
                                colors,
                            )
                            .on_click(cx.listener(
                                move |this, _, _, cx| {
                                    this.focus_in_call_tree(path_symbol.clone(), cx)
                                },
                            )),
                        )
                        .child(
                            studio::compact_text_button(
                                ("cpu-top-flame", index),
                                lang.text(TextKey::CpuCallTreeFlame),
                                !ws.busy,
                                false,
                                colors,
                            )
                            .on_click(cx.listener(
                                move |this, _, _, cx| this.focus_in_flame(flame_symbol.clone(), cx),
                            )),
                        ),
                ),
        );
    }
    div()
        .size_full()
        .flex()
        .flex_col()
        .gap(px(6.))
        .child(sort_row)
        .child(toolbar)
        .child(
            div()
                .flex_1()
                .min_h_0()
                .flex()
                .flex_col()
                .bg(colors.panel)
                .border_1()
                .border_color(colors.border)
                .rounded(px(studio::metrics::PANEL_RADIUS))
                .overflow_hidden()
                .child(header)
                .child(list),
        )
        .into_any_element()
}

fn selected_chart_block<'a>(ws: &'a CpuWorkspace) -> Option<&'a ChartBlock> {
    let frame = ws.chart.as_ref()?;
    let id = ws.chart_selected.as_deref()?;
    frame.blocks.iter().find(|block| block.id == id)
}

fn chart_panel(ws: &CpuWorkspace, colors: &StudioColors, cx: &Context<CpuWorkspace>) -> AnyElement {
    let lang = ws.language;
    let Some(frame) = &ws.chart else {
        let message = if ws.busy {
            lang.text(TextKey::CpuKpiPendingLoading)
        } else {
            lang.text(TextKey::CpuKpiUnavailable)
        };
        return panel_message(ws, colors, message);
    };
    let mut note = div().flex().items_center().gap(px(6.)).child(
        div()
            .text_size(px(studio::FONT_SUPPORTING))
            .text_color(colors.secondary_text)
            .child(crate::studio::selectable(
                lang.text(TextKey::CpuWidthIsStatisticalWeightNotTime),
            )),
    );
    if ws.zoom_start > 0.0 || ws.zoom_end < 1.0 {
        note = note.child(div().flex_1()).child(
            studio::compact_text_button(
                "cpu-flame-reset-range",
                lang.text(TextKey::CpuWorkspaceResetRange),
                !ws.busy,
                false,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.reset_zoom(cx))),
        );
    } else {
        note = note.child(div().flex_1());
    }
    let mut column = div().size_full().flex().flex_col().gap(px(4.));
    if !ws.transforms.is_empty() {
        column = column.child(transform_bar(ws, colors, cx));
    }
    column = column.child(note);
    if frame.truncated {
        column = column.child(div().text_size(px(studio::FONT_SUPPORTING)).child(
            crate::studio::selectable(format!(
                "{} {}",
                lang.text(TextKey::CpuChartOutputOmittedBlocks),
                frame.omitted_blocks
            )),
        ));
    }
    column = column.child(chart_scroll(
        ws,
        frame.clone(),
        ChartMode::Flame,
        colors,
        cx,
    ));
    if let Some(block) = selected_chart_block(ws) {
        column = column.child(
            div()
                .px(px(8.))
                .py(px(4.))
                .bg(colors.section_background)
                .rounded(px(studio::metrics::SMALL_RADIUS))
                .text_size(px(studio::FONT_SUPPORTING))
                .text_color(colors.text)
                .child(crate::studio::selectable(format!(
                    "{} · {} · {} {} · {} {}",
                    block.label,
                    block.resource,
                    lang.text(TextKey::CpuDetailsInclusive),
                    block.inclusive_weight,
                    lang.text(TextKey::CpuKpiSamples),
                    block.sample_count
                ))),
        );
    }
    column.into_any_element()
}

fn stack_panel(ws: &CpuWorkspace, colors: &StudioColors, cx: &Context<CpuWorkspace>) -> AnyElement {
    let lang = ws.language;
    let Some(frame) = &ws.chart else {
        let message = if ws.busy {
            lang.text(TextKey::CpuKpiPendingLoading)
        } else {
            lang.text(TextKey::CpuStackNoSamples)
        };
        return panel_message(ws, colors, message);
    };
    let mut note = div()
        .flex()
        .items_center()
        .gap(px(6.))
        .child(
            div()
                .text_size(px(studio::FONT_SUPPORTING))
                .text_color(colors.secondary_text)
                .child(crate::studio::selectable(
                    lang.text(TextKey::CpuOnePixelSamplePointsIntervalsAreNot),
                )),
        )
        .child(div().flex_1());
    if ws.query.start.is_some() || ws.query.end.is_some() {
        note = note.child(
            studio::compact_text_button(
                "cpu-stack-reset-range",
                lang.text(TextKey::CpuWorkspaceResetRange),
                !ws.busy,
                false,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.reset_time_range(cx))),
        );
    }
    let mut column = div().size_full().flex().flex_col().gap(px(4.)).child(note);
    if frame.blocks.is_empty() {
        return column
            .child(panel_message(
                ws,
                colors,
                lang.text(TextKey::CpuStackNoBlocks),
            ))
            .into_any_element();
    }
    column = column.child(chart_scroll(
        ws,
        frame.clone(),
        ChartMode::Stack,
        colors,
        cx,
    ));
    if let Some(block) = selected_chart_block(ws) {
        column = column.child(
            div()
                .px(px(8.))
                .py(px(4.))
                .bg(colors.section_background)
                .rounded(px(studio::metrics::SMALL_RADIUS))
                .text_size(px(studio::FONT_SUPPORTING))
                .text_color(colors.text)
                .child(crate::studio::selectable(format!(
                    "{} · {} {} · {} {}",
                    block.label,
                    lang.text(TextKey::CpuKpiSamples),
                    block.sample_count,
                    lang.text(TextKey::CpuDetailsInclusive),
                    block.inclusive_weight
                ))),
        );
    }
    column.into_any_element()
}

fn marker_panel(ws: &CpuWorkspace, colors: &StudioColors) -> AnyElement {
    panel_message(ws, colors, ws.language.text(TextKey::CpuMarkerNotCollected))
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum ChartMode {
    Flame,
    Stack,
}

fn transform_label(lang: Language, op: &TransformOp) -> String {
    match op {
        TransformOp::Focus(name) => lang.format(TextKey::CpuTransformFocusChip, &[("name", name)]),
        TransformOp::Merge(name) => lang.format(TextKey::CpuTransformMergeChip, &[("name", name)]),
        TransformOp::Collapse(name) => {
            lang.format(TextKey::CpuTransformCollapseChip, &[("name", name)])
        }
        TransformOp::Drop(name) => lang.format(TextKey::CpuTransformDropChip, &[("name", name)]),
        TransformOp::CollapseRecursion => lang.text(TextKey::CpuTransformRecursionChip).to_owned(),
    }
}

fn transform_bar(
    ws: &CpuWorkspace,
    colors: &StudioColors,
    cx: &Context<CpuWorkspace>,
) -> AnyElement {
    let lang = ws.language;
    let mut row = div()
        .flex()
        .items_center()
        .gap(px(4.))
        .h(px(24.))
        .child(studio::dense_label(
            lang.text(TextKey::CpuTransformTitle),
            colors.secondary_text,
        ));
    for (index, op) in ws.transforms.iter().enumerate() {
        row = row.child(
            div()
                .flex()
                .items_center()
                .gap(px(2.))
                .h(px(20.))
                .px(px(6.))
                .rounded(px(10.))
                .bg(colors.section_background)
                .border_1()
                .border_color(colors.border)
                .child(
                    div()
                        .text_size(px(studio::FONT_SUPPORTING))
                        .text_color(colors.text)
                        .whitespace_nowrap()
                        .child(transform_label(lang, op)),
                )
                .child(
                    div()
                        .id(("cpu-transform-remove", index))
                        .w(px(14.))
                        .flex_shrink_0()
                        .cursor_pointer()
                        .text_size(px(studio::FONT_SUPPORTING))
                        .text_color(colors.secondary_text)
                        .on_click(
                            cx.listener(move |this, _, _, cx| this.remove_transform(index, cx)),
                        )
                        .child(crate::studio::selectable("×")),
                ),
        );
    }
    row.child(div().flex_1())
        .child(
            studio::compact_text_button(
                "cpu-transform-reset",
                lang.text(TextKey::CpuTransformReset),
                true,
                false,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.reset_transforms(cx))),
        )
        .into_any_element()
}

fn chart_scroll(
    ws: &CpuWorkspace,
    frame: ChartFrame,
    mode: ChartMode,
    colors: &StudioColors,
    cx: &Context<CpuWorkspace>,
) -> AnyElement {
    let height = frame.height_px.clamp(48.0, 4000.0);
    let viewport = chart_viewport_size(ws);
    let tooltip = ws
        .chart_hover
        .as_deref()
        .and_then(|id| frame.selected(id))
        .map(|block| chart_tooltip(ws, block, colors, ws.chart_hover_position, viewport));
    let menu = ws.chart_menu.as_ref().and_then(|(x, y, id)| {
        frame
            .selected(id)
            .map(|block| chart_context_menu(ws, block, (*x, *y), colors, cx))
    });
    let mut root = div().relative().flex_1().min_h_0().w_full().child(
        div()
            .id("cpu-chart-scroll")
            .size_full()
            .overflow_scroll()
            .track_scroll(&ws.chart_scroll)
            .bg(colors.field)
            .rounded(px(studio::metrics::PANEL_RADIUS))
            .border_1()
            .border_color(colors.border)
            .child(chart_canvas(ws, frame, height, mode, colors, cx)),
    );
    if let Some(tooltip) = tooltip {
        root = root.child(tooltip);
    }
    if let Some(menu) = menu {
        root = root.child(menu);
    }
    root.into_any_element()
}

fn block_color(
    colors: &StudioColors,
    block: &ChartBlock,
    selected: bool,
    hovered: bool,
    search_match: bool,
) -> Rgba {
    if selected {
        return colors.accent;
    }
    if block.kind != ChartBlockKind::Function {
        return colors.accent.alpha(if colors.dark { 0.45 } else { 0.35 });
    }
    if search_match {
        return colors
            .accent
            .blend(rgb(0xffffff).alpha(if colors.dark { 0.30 } else { 0.10 }));
    }
    let color = {
        let palette: [u32; 6] = if colors.dark {
            [0x2b5d8c, 0x356f83, 0x5b4f8c, 0x7a5230, 0x3f6f45, 0x7a3f5f]
        } else {
            [0x9ecbff, 0x9ed9d4, 0xc7b3f0, 0xf0c99e, 0xbfe3a8, 0xf0b3d2]
        };
        rgb(palette[block.depth % palette.len()])
    };
    if hovered {
        color.blend(rgb(0xffffff).alpha(if colors.dark { 0.22 } else { 0.28 }))
    } else {
        color
    }
}

fn chart_canvas(
    ws: &CpuWorkspace,
    frame: ChartFrame,
    height: f32,
    mode: ChartMode,
    colors: &StudioColors,
    cx: &Context<CpuWorkspace>,
) -> AnyElement {
    let colors = *colors;
    let origin = Arc::new(Mutex::new(Point::default()));
    let paint_origin = origin.clone();
    let down_origin = origin.clone();
    let move_origin = origin.clone();
    let up_origin = origin.clone();
    let menu_origin = origin.clone();
    let width = frame.width_px;
    let selected = ws.chart_selected.clone();
    let hovered = ws.chart_hover.clone();
    let search = ws.search.read(cx).value().to_lowercase().to_string();
    let drag = ws.chart_drag;
    let selected_range = match mode {
        ChartMode::Stack => sample_range_fractions(&frame, ws.query.start, ws.query.end),
        ChartMode::Flame => None,
    };
    let domain = frame.domain.clone();
    let paint_frame = frame.clone();
    let hit_frame_move = frame.clone();
    let hit_frame_up = frame.clone();
    let hit_frame_menu = frame.clone();
    let paint_selected = selected.clone();
    let paint_hovered = hovered.clone();
    let paint_search = search.clone();
    let paint_drag = drag;
    let paint_range = selected_range;
    let label_color = if colors.dark {
        rgb(0xf2f7ff)
    } else {
        rgb(0x1d1d1f)
    };
    let mut container = div()
        .id("cpu-chart")
        .relative()
        .w(px(width))
        .h(px(height))
        .overflow_hidden()
        .on_mouse_down(
            MouseButton::Left,
            cx.listener(move |this, event: &MouseDownEvent, _, cx| {
                let Ok(origin) = down_origin.lock() else {
                    return;
                };
                let x = f32::from(event.position.x - origin.x);
                if x.is_finite() && x >= 0.0 && x <= width {
                    this.begin_chart_drag((x / width).clamp(0.0, 1.0), cx);
                }
            }),
        )
        .on_mouse_move(cx.listener(move |this, event: &MouseMoveEvent, _, cx| {
            let Ok(origin) = move_origin.lock() else {
                return;
            };
            let x = f32::from(event.position.x - origin.x);
            let y = f32::from(event.position.y - origin.y);
            if this.chart_drag.is_some() {
                this.update_chart_drag((x / width).clamp(0.0, 1.0), cx);
            } else {
                let hit = hit_frame_move.hit_test(x, y).map(|block| block.id.clone());
                this.update_chart_hover(hit, Some((x, y)), cx);
            }
        }))
        .on_mouse_up(
            MouseButton::Left,
            cx.listener(move |this, event: &MouseUpEvent, _, cx| {
                let Ok(origin) = up_origin.lock() else {
                    return;
                };
                let x = f32::from(event.position.x - origin.x);
                let y = f32::from(event.position.y - origin.y);
                let dragged = this.chart_drag.is_some_and(|(a, b)| (a - b).abs() >= 0.005);
                if dragged {
                    this.commit_chart_drag(cx);
                } else {
                    this.chart_drag = None;
                    this.chart_selected = hit_frame_up.hit_test(x, y).map(|block| block.id.clone());
                    cx.notify();
                }
            }),
        )
        .on_mouse_down(
            MouseButton::Right,
            cx.listener(move |this, event: &MouseDownEvent, _, cx| {
                let Ok(origin) = menu_origin.lock() else {
                    return;
                };
                let x = f32::from(event.position.x - origin.x);
                let y = f32::from(event.position.y - origin.y);
                if let Some(block) = hit_frame_menu.hit_test(x, y) {
                    this.open_chart_menu((x, y), block.id.clone(), cx);
                }
            }),
        )
        .on_mouse_exit(cx.listener(|this, _, _, cx| this.update_chart_hover(None, None, cx)))
        .child(
            canvas(
                move |bounds, _, _| {
                    if let Ok(mut origin) = paint_origin.lock() {
                        *origin = bounds.origin;
                    }
                },
                move |bounds, _, window, _| {
                    for block in &paint_frame.blocks {
                        if block.y >= height {
                            continue;
                        }
                        let search_match = !paint_search.is_empty()
                            && block.kind == ChartBlockKind::Function
                            && block.label.to_lowercase().contains(&paint_search);
                        window.paint_quad(quad(
                            Bounds {
                                origin: point(
                                    bounds.origin.x + px(block.x),
                                    bounds.origin.y + px(block.y),
                                ),
                                size: size(px(block.width.max(1.0)), px(block.height.max(1.0))),
                            },
                            px(0.),
                            block_color(
                                &colors,
                                block,
                                paint_selected.as_deref() == Some(block.id.as_str()),
                                paint_hovered.as_deref() == Some(block.id.as_str()),
                                search_match,
                            ),
                            px(0.),
                            transparent_black(),
                            Default::default(),
                        ));
                    }
                    let mut paint_band = |from: f32, to: f32, fill: Rgba| {
                        let (lo, hi) = if from <= to { (from, to) } else { (to, from) };
                        if hi - lo <= 0.0 {
                            return;
                        }
                        let x = bounds.origin.x + px(lo * width);
                        let band = px((hi - lo) * width);
                        window.paint_quad(quad(
                            Bounds {
                                origin: point(x, bounds.origin.y),
                                size: size(band, px(height)),
                            },
                            px(0.),
                            fill,
                            px(0.),
                            transparent_black(),
                            Default::default(),
                        ));
                    };
                    if let Some((a, b)) = paint_drag {
                        paint_band(a, b, colors.accent.alpha(0.18));
                    }
                    if let Some((lo, hi)) = paint_range {
                        paint_band(lo as f32, hi as f32, colors.accent.alpha(0.10));
                    }
                },
            )
            .size_full(),
        );
    let _ = &domain;
    let mut labels = 0usize;
    for block in &frame.blocks {
        if labels >= MAX_CHART_LABELS {
            break;
        }
        if block.y >= height || block.width < 44.0 || block.height < 11.0 {
            continue;
        }
        labels += 1;
        let selected_block = selected.as_deref() == Some(block.id.as_str());
        let hovered_block = hovered.as_deref() == Some(block.id.as_str());
        let search_match = !search.is_empty()
            && block.kind == ChartBlockKind::Function
            && block.label.to_lowercase().contains(&search);
        let label = if block.label.is_empty() {
            block.id.clone()
        } else {
            block.label.clone()
        };
        container = container.child(
            div()
                .absolute()
                .left(px(block.x + 3.0))
                .top(px(block.y + 1.0))
                .w(px((block.width - 6.0).max(1.0)))
                .h(px(block.height - 2.0))
                .overflow_hidden()
                .text_size(px(studio::FONT_SUPPORTING))
                .text_color(if selected_block || hovered_block || search_match {
                    colors.accent_text
                } else {
                    label_color
                })
                .whitespace_nowrap()
                .child(crate::studio::selectable(label)),
        );
    }
    container.into_any_element()
}

/// Converts an absolute sample-time range into normalized fractions of the
/// chart's sample-point domain, clamped to the visible frame.
fn sample_range_fractions(
    frame: &ChartFrame,
    start: Option<u64>,
    end: Option<u64>,
) -> Option<(f64, f64)> {
    let ChartDomain::SamplePoints {
        start_nanos,
        end_nanos,
    } = frame.domain
    else {
        return None;
    };
    let span = end_nanos.saturating_sub(start_nanos) as f64;
    if span <= 0.0 {
        return None;
    }
    let from = start.unwrap_or(start_nanos);
    let to = end.unwrap_or(end_nanos);
    let lo = ((from.saturating_sub(start_nanos)) as f64 / span).clamp(0.0, 1.0);
    let hi = ((to.saturating_sub(start_nanos)) as f64 / span).clamp(0.0, 1.0);
    (hi > lo).then_some((lo, hi))
}

/// Anchor-positioned flame-graph context menu with the actions the engine can
/// support without a synthetic transform model: zoom focus, cross-tab focus,
/// indexed source navigation, copy and range reset.
fn chart_context_menu(
    ws: &CpuWorkspace,
    block: &ChartBlock,
    position: (f32, f32),
    colors: &StudioColors,
    cx: &Context<CpuWorkspace>,
) -> AnyElement {
    let lang = ws.language;
    let label = block.label.clone();
    let resource = block.resource.clone();
    let block_id = block.id.clone();
    let focus_label = lang.text(TextKey::CpuContextFocus).to_owned();
    let call_tree_label = lang.text(TextKey::CpuCallTree).to_owned();
    let source_label = lang.text(TextKey::CpuDetailsOpenSource).to_owned();
    let copy_label = lang.text(TextKey::CpuWorkspaceCopySymbol).to_owned();
    let reset_label = lang.text(TextKey::CpuWorkspaceResetRange).to_owned();
    let close_label = lang.text(TextKey::CpuContextClose).to_owned();
    let can_focus = ws.tab == CpuTab::FlameGraph;
    let can_reset = ws.zoom_start > 0.0 || ws.zoom_end < 1.0;
    let focus_symbol = label.clone();
    let source_symbol = label.clone();
    let source_resource = resource.clone();
    let copy_symbol = label.clone();
    let disassemble_symbol = label.clone();
    let disassemble_resource = resource.clone();
    let mut menu = div()
        .absolute()
        .left(px((position.0 + 8.0).max(4.0)))
        .top(px((position.1 + 8.0).max(4.0)))
        .w(px(190.))
        .flex()
        .flex_col()
        .gap(px(3.))
        .p(px(6.))
        .bg(colors.panel)
        .border_1()
        .border_color(colors.border)
        .rounded(px(studio::metrics::PANEL_RADIUS))
        .child(
            div()
                .text_size(px(studio::FONT_SUPPORTING))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(colors.text)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(crate::studio::selectable(label.clone())),
        );
    if can_focus {
        menu = menu.child(
            studio::compact_text_button("cpu-chart-menu-focus", focus_label, true, false, colors)
                .on_click(cx.listener(move |this, _, _, cx| {
                    this.close_chart_menu(cx);
                    this.focus_flame_block(block_id.clone(), cx);
                })),
        );
    }
    let transform_ops = [
        (
            TransformOp::Focus(label.clone()),
            TextKey::CpuTransformFocus,
            "cpu-chart-menu-transform-focus",
        ),
        (
            TransformOp::Merge(label.clone()),
            TextKey::CpuTransformMerge,
            "cpu-chart-menu-transform-merge",
        ),
        (
            TransformOp::Collapse(label.clone()),
            TextKey::CpuTransformCollapse,
            "cpu-chart-menu-transform-collapse",
        ),
        (
            TransformOp::Drop(label.clone()),
            TextKey::CpuTransformDrop,
            "cpu-chart-menu-transform-drop",
        ),
    ];
    for (op, key, id) in transform_ops {
        let applied = ws.transforms.contains(&op);
        let op_for_click = op.clone();
        menu = menu.child(
            studio::compact_text_button(id, lang.text(key), !applied, false, colors).on_click(
                cx.listener(move |this, _, _, cx| {
                    this.close_chart_menu(cx);
                    this.apply_transform(op_for_click.clone(), cx);
                }),
            ),
        );
    }
    if !ws.transforms.contains(&TransformOp::CollapseRecursion) {
        menu = menu.child(
            studio::compact_text_button(
                "cpu-chart-menu-transform-recursion",
                lang.text(TextKey::CpuTransformRecursion),
                true,
                false,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| {
                this.close_chart_menu(cx);
                this.apply_transform(TransformOp::CollapseRecursion, cx);
            })),
        );
    }
    if !ws.transforms.is_empty() {
        menu = menu.child(
            studio::compact_text_button(
                "cpu-chart-menu-transform-reset",
                lang.text(TextKey::CpuTransformReset),
                true,
                false,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| {
                this.close_chart_menu(cx);
                this.reset_transforms(cx);
            })),
        );
    }
    menu = menu
        .child(
            studio::compact_text_button(
                "cpu-chart-menu-call-tree",
                call_tree_label,
                true,
                false,
                colors,
            )
            .on_click(cx.listener(move |this, _, _, cx| {
                this.close_chart_menu(cx);
                this.focus_in_call_tree(focus_symbol.clone(), cx);
            })),
        )
        .child(
            studio::compact_text_button(
                "cpu-chart-menu-source",
                source_label,
                ws.studio.is_some(),
                false,
                colors,
            )
            .on_click(cx.listener(move |this, _, window, cx| {
                this.close_chart_menu(cx);
                this.open_source(source_symbol.clone(), source_resource.clone(), window, cx);
            })),
        )
        .child(
            studio::compact_text_button("cpu-chart-menu-copy", copy_label, true, false, colors)
                .on_click(cx.listener(move |this, _, _, cx| {
                    this.close_chart_menu(cx);
                    this.copy_symbol(copy_symbol.clone(), cx);
                })),
        );
    menu = menu.child(
        studio::compact_text_button(
            "cpu-chart-menu-disassemble",
            lang.text(TextKey::CpuDisassemble),
            !disassemble_symbol.is_empty(),
            false,
            colors,
        )
        .on_click(cx.listener(move |this, _, _, cx| {
            this.close_chart_menu(cx);
            this.disassemble(disassemble_symbol.clone(), disassemble_resource.clone(), cx);
        })),
    );
    if can_reset {
        menu = menu.child(
            studio::compact_text_button("cpu-chart-menu-reset", reset_label, true, false, colors)
                .on_click(cx.listener(|this, _, _, cx| {
                    this.close_chart_menu(cx);
                    this.reset_zoom(cx);
                })),
        );
    }
    menu.child(
        studio::compact_text_button("cpu-chart-menu-close", close_label, true, false, colors)
            .on_click(cx.listener(|this, _, _, cx| this.close_chart_menu(cx))),
    )
    .into_any_element()
}

fn chart_tooltip(
    ws: &CpuWorkspace,
    block: &ChartBlock,
    colors: &StudioColors,
    position: Option<(f32, f32)>,
    viewport: (f32, f32),
) -> AnyElement {
    let lang = ws.language;
    let total_weight = match ws.query.weight_type {
        WeightType::Samples => ws
            .catalog
            .as_ref()
            .map_or(0, |catalog| catalog.summary.sample_count),
        WeightType::EventCount => ws
            .catalog
            .as_ref()
            .map_or(0, |catalog| catalog.summary.total_event_count),
    };
    let share = if total_weight > 0 {
        format_percent(block.inclusive_weight as f64 / total_weight as f64)
    } else {
        "—".to_owned()
    };
    let follow = ws.follow_tooltip;
    let margin = CHART_TOOLTIP_MARGIN;
    let cursor = position.unwrap_or((margin, margin));
    let (top, left) = if follow {
        chart_tooltip_placement(
            cursor,
            (CHART_TOOLTIP_WIDTH, chart_tooltip_height(block)),
            viewport,
        )
    } else {
        // The fixed mode keeps the card in the chart's top-right corner.
        (margin, margin)
    };
    let mut card = div()
        .absolute()
        .top(px(top))
        .when(follow, |card| card.left(px(left)))
        .when(!follow, |card| card.right(px(margin)))
        .w(px(CHART_TOOLTIP_WIDTH))
        .flex()
        .flex_col()
        .gap(px(2.))
        .p(px(8.))
        .bg(colors.panel)
        .border_1()
        .border_color(colors.border)
        .rounded(px(studio::metrics::PANEL_RADIUS))
        .text_size(px(studio::FONT_SUPPORTING))
        .text_color(colors.text)
        .child(
            div()
                .font_weight(FontWeight::SEMIBOLD)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(block.label.clone()),
        );
    if !block.resource.is_empty() {
        card = card.child(
            div()
                .text_color(colors.secondary_text)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(block.resource.clone()),
        );
    }
    card = card
        .child(
            div()
                .text_color(colors.secondary_text)
                .child(crate::studio::selectable(format!(
                    "{} {} · {} {}",
                    lang.text(TextKey::CpuDetailsInclusive),
                    format_count(block.inclusive_weight),
                    lang.text(TextKey::CpuDetailsSelf),
                    format_count(block.self_weight)
                ))),
        )
        .child(
            div()
                .text_color(colors.secondary_text)
                .child(crate::studio::selectable(format!(
                    "{} {} · {}",
                    lang.text(TextKey::CpuKpiSamples),
                    format_count(block.sample_count),
                    share
                ))),
        );
    if let Some(thread) = block.thread_id {
        card = card.child(div().text_color(colors.secondary_text).child(
            crate::studio::selectable(format!("{} {thread}", lang.text(TextKey::CpuThread))),
        ));
    }
    if let Some(event) = &block.event_type {
        card = card.child(
            div()
                .text_color(colors.secondary_text)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(crate::studio::selectable(format!(
                    "{} {event}",
                    lang.text(TextKey::CpuEvent)
                ))),
        );
    }
    if let (Some(first), Some(last)) = (block.first_timestamp_nanos, block.last_timestamp_nanos) {
        let origin = source_start(ws);
        card = card.child(div().text_color(colors.secondary_text).child(
            crate::studio::selectable(format!(
                "{} {} · {} {}",
                lang.text(TextKey::CpuMarkerStart),
                format_duration(first.saturating_sub(origin)),
                lang.text(TextKey::CpuMarkerEnd),
                format_duration(last.saturating_sub(origin))
            )),
        ));
    }
    card.into_any_element()
}

/// Width of the floating facts card, shared by its layout and its placement math.
const CHART_TOOLTIP_WIDTH: f32 = 280.0;
/// Distance between the pointer and the card on the chosen side.
const CHART_TOOLTIP_GAP: f32 = 12.0;
/// Smallest margin the card keeps from the visible chart edges.
const CHART_TOOLTIP_MARGIN: f32 = 6.0;
/// Dense supporting-font line height used to estimate the card height.
const CHART_TOOLTIP_LINE_HEIGHT: f32 = 16.0;

/// Estimated card height: one row per rendered line (symbol, optional resource,
/// inclusive/self, samples/share, optional thread/event/timestamps) plus padding
/// and the 2px row gap. Slightly over-estimating only keeps more space at the
/// viewport edge.
fn chart_tooltip_height(block: &ChartBlock) -> f32 {
    let mut lines = 1.0;
    if !block.resource.is_empty() {
        lines += 1.0;
    }
    lines += 2.0;
    if block.thread_id.is_some() {
        lines += 1.0;
    }
    if block.event_type.is_some() {
        lines += 1.0;
    }
    if block.first_timestamp_nanos.is_some() && block.last_timestamp_nanos.is_some() {
        lines += 1.0;
    }
    lines * CHART_TOOLTIP_LINE_HEIGHT + (lines - 1.0) * 2.0 + 16.0
}

/// Visible chart viewport size. The scroll handle's tracked bounds are the chart
/// scroll container's own bounds; before the first layout they are empty, and a
/// conservative fallback keeps the card inside the window rather than at 0x0.
fn chart_viewport_size(ws: &CpuWorkspace) -> (f32, f32) {
    let bounds = ws.chart_scroll.bounds();
    let size = (f32::from(bounds.size.width), f32::from(bounds.size.height));
    if size.0 > 1.0 && size.1 > 1.0 {
        size
    } else {
        (800.0, 600.0)
    }
}

/// Place the follow-mouse facts card on the best side of the pointer: to the
/// right unless that would overflow, then to the left; below unless that would
/// overflow, then above; finally clamp fully inside the visible viewport. The
/// result is (top, left) in viewport coordinates.
fn chart_tooltip_placement(
    position: (f32, f32),
    size: (f32, f32),
    viewport: (f32, f32),
) -> (f32, f32) {
    let (cursor_x, cursor_y) = position;
    let (width, height) = size;
    let (viewport_width, viewport_height) = viewport;
    let right = cursor_x + CHART_TOOLTIP_GAP;
    let left = cursor_x - CHART_TOOLTIP_GAP - width;
    let left = if right + width + CHART_TOOLTIP_MARGIN <= viewport_width {
        right
    } else if left >= CHART_TOOLTIP_MARGIN {
        left
    } else {
        (viewport_width - width - CHART_TOOLTIP_MARGIN).max(CHART_TOOLTIP_MARGIN)
    };
    let below = cursor_y + CHART_TOOLTIP_GAP;
    let above = cursor_y - CHART_TOOLTIP_GAP - height;
    let top = if below + height + CHART_TOOLTIP_MARGIN <= viewport_height {
        below
    } else if above >= CHART_TOOLTIP_MARGIN {
        above
    } else {
        (viewport_height - height - CHART_TOOLTIP_MARGIN).max(CHART_TOOLTIP_MARGIN)
    };
    (top, left)
}

fn details_pane(
    ws: &CpuWorkspace,
    colors: &StudioColors,
    cx: &Context<CpuWorkspace>,
) -> AnyElement {
    div()
        .id("cpu-details")
        .size_full()
        .overflow_y_scroll()
        .flex()
        .flex_col()
        .gap(px(8.))
        .p(px(12.))
        .bg(colors.panel)
        .border_1()
        .border_color(colors.border)
        .rounded(px(studio::metrics::PANEL_RADIUS))
        .child(match ws.tab {
            CpuTab::Overview => details_overview(ws, colors),
            CpuTab::TopFunctions => details_function(ws, colors, cx),
            CpuTab::CallTree | CpuTab::FlameGraph => details_frame(ws, colors, cx),
            CpuTab::StackChart => details_stack(ws, colors),
            CpuTab::MarkerChart | CpuTab::MarkerTable => details_prompt(
                ws,
                colors,
                TextKey::CpuDetailsDetails,
                TextKey::CpuDetailsSelectMarkerHint,
            ),
        })
        .into_any_element()
}

fn details_prompt(
    ws: &CpuWorkspace,
    colors: &StudioColors,
    title: TextKey,
    hint: TextKey,
) -> AnyElement {
    div()
        .flex()
        .flex_col()
        .gap(px(6.))
        .child(studio::details_heading(ws.language.text(title), colors))
        .child(studio::supporting(ws.language.text(hint), colors))
        .into_any_element()
}

fn details_overview(ws: &CpuWorkspace, colors: &StudioColors) -> AnyElement {
    let lang = ws.language;
    let Some(catalog) = &ws.catalog else {
        return div().into_any_element();
    };
    let summary = &catalog.summary;
    let total = summary
        .sample_count
        .saturating_add(summary.lost_sample_count);
    let lost_rate = if total > 0 {
        summary.lost_sample_count as f64 / total as f64
    } else {
        0.0
    };
    let mut column = div()
        .flex()
        .flex_col()
        .gap(px(8.))
        .child(studio::details_heading(
            lang.text(TextKey::CpuDetailsDataQuality),
            colors,
        ))
        .child(metric_grid(
            vec![
                (
                    lang.text(TextKey::CpuLostSamples).to_owned(),
                    format_count(summary.lost_sample_count),
                ),
                (
                    lang.text(TextKey::CpuDetailsLostEvents).to_owned(),
                    format_count(summary.lost_event_count),
                ),
                (
                    lang.text(TextKey::CpuDetailsLostRate).to_owned(),
                    format_percent(lost_rate),
                ),
                (
                    lang.text(TextKey::CpuEvent).to_owned(),
                    format_count(summary.event_types.len() as u64),
                ),
            ],
            colors,
        ))
        .child(studio::details_heading(
            lang.text(TextKey::CpuDetailsSource),
            colors,
        ))
        .child(studio::supporting(catalog.source_label.clone(), colors))
        .child(studio::dense_label(
            format!("SHA-256 {}", catalog.source_digest),
            colors.secondary_text,
        ))
        .child(studio::dense_label(
            lang.format(
                TextKey::ProfilerCpuByteCount,
                &[("bytes", &catalog.byte_length)],
            ),
            colors.secondary_text,
        ));
    if let Some(binding) = &ws.association {
        column = column.child(studio::dense_label(
            format!("{} · g{}", binding.session_id, binding.generation),
            colors.secondary_text,
        ));
    } else {
        column = column.child(studio::dense_label(
            lang.text(TextKey::CpuMemoryOnlyNoValidDurableAssociation),
            colors.secondary_text,
        ));
    }
    column.into_any_element()
}

fn details_function(
    ws: &CpuWorkspace,
    colors: &StudioColors,
    cx: &Context<CpuWorkspace>,
) -> AnyElement {
    let lang = ws.language;
    let total_weight = match ws.query.weight_type {
        WeightType::Samples => ws
            .catalog
            .as_ref()
            .map_or(0, |catalog| catalog.summary.sample_count),
        WeightType::EventCount => ws
            .catalog
            .as_ref()
            .map_or(0, |catalog| catalog.summary.total_event_count),
    };
    let selected = ws.row_selected.as_deref();
    let function = ws.top.as_ref().and_then(|page| {
        page.rows
            .iter()
            .find(|row| Some(row.id.as_str()) == selected)
    });
    let Some(function) = function else {
        return details_prompt(
            ws,
            colors,
            TextKey::CpuDetailsFunctionDetails,
            TextKey::CpuDetailsSelectFunctionHint,
        );
    };
    let share = |weight: u64| {
        if total_weight > 0 {
            format_percent(weight as f64 / total_weight as f64)
        } else {
            "—".to_owned()
        }
    };
    let path_symbol = function.name.clone();
    let flame_symbol = function.name.clone();
    let copy_symbol = function.name.clone();
    let source_symbol = function.name.clone();
    let source_resource = function.resource.clone();
    let disassemble_symbol = function.name.clone();
    let disassemble_resource = function.resource.clone();
    div()
        .flex()
        .flex_col()
        .gap(px(8.))
        .child(studio::details_heading(
            lang.text(TextKey::CpuDetailsFunctionDetails),
            colors,
        ))
        .child(
            div()
                .text_size(px(studio::FONT_BODY))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(colors.text)
                .child(function.name.clone()),
        )
        .child(studio::dense_label(
            format!(
                "{}: {}",
                lang.text(TextKey::CpuDetailsLibrary),
                function.resource
            ),
            colors.secondary_text,
        ))
        .child(metric_grid(
            vec![
                (
                    lang.text(TextKey::CpuDetailsTotalShare).to_owned(),
                    share(function.inclusive_weight),
                ),
                (
                    lang.text(TextKey::CpuDetailsSelfShare).to_owned(),
                    share(function.self_weight),
                ),
                (
                    lang.text(TextKey::CpuDetailsInclusive).to_owned(),
                    format_count(function.inclusive_weight),
                ),
                (
                    lang.text(TextKey::CpuDetailsSelf).to_owned(),
                    format_count(function.self_weight),
                ),
                (
                    lang.text(TextKey::CpuKpiSamples).to_owned(),
                    format_count(function.sample_count),
                ),
                (
                    lang.text(TextKey::CpuKpiThreads).to_owned(),
                    format_count(function.thread_count as u64),
                ),
            ],
            colors,
        ))
        .child(
            div()
                .flex()
                .gap(px(6.))
                .child(
                    studio::compact_text_button(
                        "details-path",
                        lang.text(TextKey::CpuCallTreePath),
                        !ws.busy,
                        false,
                        colors,
                    )
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.focus_in_call_tree(path_symbol.clone(), cx)
                    })),
                )
                .child(
                    studio::compact_text_button(
                        "details-flame",
                        lang.text(TextKey::CpuCallTreeFlame),
                        !ws.busy,
                        false,
                        colors,
                    )
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.focus_in_flame(flame_symbol.clone(), cx)
                    })),
                )
                .child(
                    studio::compact_text_button(
                        "details-copy-symbol",
                        lang.text(TextKey::CpuWorkspaceCopySymbol),
                        true,
                        false,
                        colors,
                    )
                    .on_click(
                        cx.listener(move |this, _, _, cx| {
                            this.copy_symbol(copy_symbol.clone(), cx)
                        }),
                    ),
                )
                .child(
                    studio::compact_text_button(
                        "details-open-source",
                        lang.text(TextKey::CpuDetailsOpenSource),
                        ws.studio.is_some(),
                        false,
                        colors,
                    )
                    .on_click(cx.listener(move |this, _, window, cx| {
                        this.open_source(source_symbol.clone(), source_resource.clone(), window, cx)
                    })),
                )
                .child(
                    studio::compact_text_button(
                        "details-disassemble",
                        lang.text(TextKey::CpuDisassemble),
                        !disassemble_symbol.is_empty(),
                        false,
                        colors,
                    )
                    .on_click(cx.listener(move |this, _, _, cx| {
                        this.disassemble(
                            disassemble_symbol.clone(),
                            disassemble_resource.clone(),
                            cx,
                        )
                    })),
                ),
        )
        .into_any_element()
}

fn details_frame(
    ws: &CpuWorkspace,
    colors: &StudioColors,
    cx: &Context<CpuWorkspace>,
) -> AnyElement {
    let lang = ws.language;
    let total_weight = match ws.query.weight_type {
        WeightType::Samples => ws
            .catalog
            .as_ref()
            .map_or(0, |catalog| catalog.summary.sample_count),
        WeightType::EventCount => ws
            .catalog
            .as_ref()
            .map_or(0, |catalog| catalog.summary.total_event_count),
    };
    let selected = ws.row_selected.as_deref();
    if let Some(tree) = &ws.tree {
        if let Some(row) = tree
            .rows
            .iter()
            .find(|row| Some(row.id.as_str()) == selected)
        {
            let share = |weight: u64| {
                if total_weight > 0 {
                    format_percent(weight as f64 / total_weight as f64)
                } else {
                    "—".to_owned()
                }
            };
            let children: Vec<&QueryRow> = tree
                .rows
                .iter()
                .filter(|child| child.parent_id.as_deref() == Some(row.id.as_str()))
                .take(5)
                .collect();
            let mut column = div()
                .flex()
                .flex_col()
                .gap(px(8.))
                .child(studio::details_heading(
                    lang.text(TextKey::CpuDetailsFunctionDetails),
                    colors,
                ))
                .child(
                    div()
                        .text_size(px(studio::FONT_BODY))
                        .font_weight(FontWeight::SEMIBOLD)
                        .text_color(colors.text)
                        .child(row.name.clone()),
                )
                .child(studio::dense_label(
                    format!(
                        "{}: {}",
                        lang.text(TextKey::CpuDetailsLibrary),
                        row.resource
                    ),
                    colors.secondary_text,
                ))
                .child(metric_grid(
                    vec![
                        (
                            lang.text(TextKey::CpuDetailsTotalShare).to_owned(),
                            share(row.inclusive_weight),
                        ),
                        (
                            lang.text(TextKey::CpuDetailsSelfShare).to_owned(),
                            share(row.self_weight),
                        ),
                        (
                            lang.text(TextKey::CpuDetailsInclusive).to_owned(),
                            format_count(row.inclusive_weight),
                        ),
                        (
                            lang.text(TextKey::CpuDetailsSelf).to_owned(),
                            format_count(row.self_weight),
                        ),
                        (
                            lang.text(TextKey::CpuKpiSamples).to_owned(),
                            format_count(row.sample_count),
                        ),
                    ],
                    colors,
                ));
            let symbol = row.name.clone();
            let path_symbol = row.name.clone();
            let flame_symbol = row.name.clone();
            let copy_symbol = row.name.clone();
            let source_symbol = row.name.clone();
            let source_resource = row.resource.clone();
            column = column.child(
                div()
                    .flex()
                    .gap(px(6.))
                    .child(
                        studio::compact_text_button(
                            "details-frame-path",
                            lang.text(TextKey::CpuCallTreePath),
                            !ws.busy,
                            false,
                            colors,
                        )
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.focus_in_call_tree(path_symbol.clone(), cx)
                        })),
                    )
                    .child(
                        studio::compact_text_button(
                            "details-frame-flame",
                            lang.text(TextKey::CpuCallTreeFlame),
                            !ws.busy,
                            false,
                            colors,
                        )
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.focus_in_flame(flame_symbol.clone(), cx)
                        })),
                    )
                    .child(
                        studio::compact_text_button(
                            "details-frame-copy",
                            lang.text(TextKey::CpuWorkspaceCopySymbol),
                            true,
                            false,
                            colors,
                        )
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.copy_symbol(copy_symbol.clone(), cx)
                        })),
                    )
                    .child(
                        studio::compact_text_button(
                            "details-frame-source",
                            lang.text(TextKey::CpuDetailsOpenSource),
                            ws.studio.is_some(),
                            false,
                            colors,
                        )
                        .on_click(cx.listener(
                            move |this, _, window, cx| {
                                this.open_source(
                                    source_symbol.clone(),
                                    source_resource.clone(),
                                    window,
                                    cx,
                                )
                            },
                        )),
                    ),
            );
            let transform_symbol = symbol.clone();
            let mut transform_row = div().flex().flex_wrap().gap(px(4.));
            for (op, key, id) in [
                (
                    TransformOp::Focus(transform_symbol.clone()),
                    TextKey::CpuTransformFocus,
                    "details-transform-focus",
                ),
                (
                    TransformOp::Merge(transform_symbol.clone()),
                    TextKey::CpuTransformMerge,
                    "details-transform-merge",
                ),
                (
                    TransformOp::Collapse(transform_symbol.clone()),
                    TextKey::CpuTransformCollapse,
                    "details-transform-collapse",
                ),
                (
                    TransformOp::Drop(transform_symbol.clone()),
                    TextKey::CpuTransformDrop,
                    "details-transform-drop",
                ),
            ] {
                let applied = ws.transforms.contains(&op);
                let op_for_click = op.clone();
                transform_row = transform_row.child(
                    studio::compact_text_button(id, lang.text(key), !applied, false, colors)
                        .on_click(cx.listener(move |this, _, _, cx| {
                            this.apply_transform(op_for_click.clone(), cx);
                        })),
                );
            }
            column = column.child(transform_row);
            let path = selected_path(ws);
            if !path.is_empty() {
                column = column.child(studio::details_heading(
                    lang.text(TextKey::CpuDetailsCallPath),
                    colors,
                ));
                for (index, (id, name)) in path.iter().enumerate() {
                    let target = id.clone();
                    column = column.child(
                        div()
                            .id(("cpu-details-path", index))
                            .pl(px((index.saturating_mul(6)) as f32))
                            .py(px(1.))
                            .cursor_pointer()
                            .text_size(px(studio::FONT_SUPPORTING))
                            .text_color(if Some(id.as_str()) == selected {
                                colors.accent
                            } else {
                                colors.text
                            })
                            .whitespace_nowrap()
                            .text_ellipsis()
                            .child(crate::studio::selectable(name.clone()))
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.select_row(target.clone(), cx)
                            })),
                    );
                }
            }
            if !children.is_empty() {
                column = column.child(studio::details_heading(
                    lang.text(TextKey::CpuDetailsChildren),
                    colors,
                ));
                for child in children {
                    column = column.child(
                        div()
                            .text_size(px(studio::FONT_SUPPORTING))
                            .text_color(colors.text)
                            .whitespace_nowrap()
                            .text_ellipsis()
                            .child(child.name.clone()),
                    );
                }
            }
            return column.into_any_element();
        }
    }
    if let Some(block) = selected_chart_block(ws) {
        if ws.tab == CpuTab::FlameGraph {
            return div()
                .flex()
                .flex_col()
                .gap(px(8.))
                .child(studio::details_heading(
                    lang.text(TextKey::CpuDetailsFunctionDetails),
                    colors,
                ))
                .child(
                    div()
                        .text_size(px(studio::FONT_BODY))
                        .font_weight(FontWeight::SEMIBOLD)
                        .text_color(colors.text)
                        .child(block.label.clone()),
                )
                .child(studio::dense_label(
                    format!(
                        "{}: {}",
                        lang.text(TextKey::CpuDetailsLibrary),
                        block.resource
                    ),
                    colors.secondary_text,
                ))
                .child(metric_grid(
                    vec![
                        (
                            lang.text(TextKey::CpuDetailsInclusive).to_owned(),
                            format_count(block.inclusive_weight),
                        ),
                        (
                            lang.text(TextKey::CpuDetailsSelf).to_owned(),
                            format_count(block.self_weight),
                        ),
                        (
                            lang.text(TextKey::CpuKpiSamples).to_owned(),
                            format_count(block.sample_count),
                        ),
                        (
                            lang.text(TextKey::CpuDetailsSource).to_owned(),
                            format_count(block.source_count as u64),
                        ),
                    ],
                    colors,
                ))
                .child({
                    let source_symbol = block.label.clone();
                    let source_resource = block.resource.clone();
                    let copy_symbol = block.label.clone();
                    div()
                        .flex()
                        .gap(px(6.))
                        .child(
                            studio::compact_text_button(
                                "details-flame-copy",
                                lang.text(TextKey::CpuWorkspaceCopySymbol),
                                true,
                                false,
                                colors,
                            )
                            .on_click(cx.listener(
                                move |this, _, _, cx| this.copy_symbol(copy_symbol.clone(), cx),
                            )),
                        )
                        .child(
                            studio::compact_text_button(
                                "details-flame-source",
                                lang.text(TextKey::CpuDetailsOpenSource),
                                ws.studio.is_some(),
                                false,
                                colors,
                            )
                            .on_click(cx.listener(
                                move |this, _, window, cx| {
                                    this.open_source(
                                        source_symbol.clone(),
                                        source_resource.clone(),
                                        window,
                                        cx,
                                    )
                                },
                            )),
                        )
                        .child({
                            let symbol = block.label.clone();
                            let resource = block.resource.clone();
                            studio::compact_text_button(
                                "details-flame-disassemble",
                                lang.text(TextKey::CpuDisassemble),
                                !symbol.is_empty(),
                                false,
                                colors,
                            )
                            .on_click(cx.listener(
                                move |this, _, _, cx| {
                                    this.disassemble(symbol.clone(), resource.clone(), cx)
                                },
                            ))
                        })
                })
                .into_any_element();
        }
    }
    details_prompt(
        ws,
        colors,
        TextKey::CpuDetailsDetails,
        TextKey::CpuDetailsSelectFrameHint,
    )
}

fn details_stack(ws: &CpuWorkspace, colors: &StudioColors) -> AnyElement {
    let lang = ws.language;
    let Some(block) = selected_chart_block(ws) else {
        return details_prompt(
            ws,
            colors,
            TextKey::CpuDetailsDetails,
            TextKey::CpuDetailsSelectStackHint,
        );
    };
    let mut column = div()
        .flex()
        .flex_col()
        .gap(px(8.))
        .child(studio::details_heading(
            lang.text(TextKey::CpuDetailsFunctionDetails),
            colors,
        ))
        .child(
            div()
                .text_size(px(studio::FONT_BODY))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(colors.text)
                .child(block.label.clone()),
        )
        .child(studio::dense_label(
            block.resource.clone(),
            colors.secondary_text,
        ));
    if let Some(timestamp) = block.first_timestamp_nanos {
        column = column.child(studio::dense_label(
            format!(
                "Start: {}",
                format_duration(timestamp.saturating_sub(source_start(ws)))
            ),
            colors.secondary_text,
        ));
    }
    if let Some(timestamp) = block.last_timestamp_nanos {
        column = column.child(studio::dense_label(
            format!(
                "End: {}",
                format_duration(timestamp.saturating_sub(source_start(ws)))
            ),
            colors.secondary_text,
        ));
    }
    column = column.child(metric_grid(
        vec![
            (
                lang.text(TextKey::CpuKpiSamples).to_owned(),
                format_count(block.sample_count),
            ),
            (
                lang.text(TextKey::CpuDetailsInclusive).to_owned(),
                format_count(block.inclusive_weight),
            ),
            (
                lang.text(TextKey::CpuDetailsSelf).to_owned(),
                format_count(block.self_weight),
            ),
            (
                lang.text(TextKey::CpuDetailsSource).to_owned(),
                format_count(block.source_count as u64),
            ),
        ],
        colors,
    ));
    column.into_any_element()
}

fn metric_grid(pairs: Vec<(String, String)>, colors: &StudioColors) -> AnyElement {
    let mut column = div().flex().flex_col().gap(px(4.));
    for chunk in pairs.chunks(2) {
        let mut row = div().flex().gap(px(4.));
        for (label, value) in chunk {
            row = row.child(studio::details_metric(label.clone(), value.clone(), colors));
        }
        if chunk.len() == 1 {
            row = row.child(div().flex_1());
        }
        column = column.child(row);
    }
    column.into_any_element()
}

fn disclaimer(ws: &CpuWorkspace, colors: &StudioColors) -> AnyElement {
    div()
        .text_size(px(studio::FONT_SUPPORTING))
        .text_color(colors.secondary_text)
        .whitespace_nowrap()
        .text_ellipsis()
        .child(
            ws.language
                .text(TextKey::CpuSamplesAreStatisticalEvidenceNotMethodDurations),
        )
        .into_any_element()
}

fn source_start(ws: &CpuWorkspace) -> u64 {
    ws.catalog
        .as_ref()
        .and_then(|catalog| catalog.summary.first_timestamp_nanos)
        .unwrap_or(0)
}

fn format_count(value: u64) -> String {
    let digits = value.to_string();
    let mut output = String::with_capacity(digits.len() + digits.len() / 3);
    for (index, character) in digits.chars().enumerate() {
        if index > 0 && (digits.len() - index) % 3 == 0 {
            output.push(',');
        }
        output.push(character);
    }
    output
}

fn format_duration(nanos: u64) -> String {
    if nanos >= 1_000_000_000 {
        format!("{:.2} s", nanos as f64 / 1_000_000_000.0)
    } else if nanos >= 1_000_000 {
        format!("{:.1} ms", nanos as f64 / 1_000_000.0)
    } else if nanos >= 1_000 {
        format!("{:.1} µs", nanos as f64 / 1_000.0)
    } else {
        format!("{nanos} ns")
    }
}

fn format_rate(hz: f64) -> String {
    if hz >= 1_000_000.0 {
        format!("{:.2} MHz", hz / 1_000_000.0)
    } else if hz >= 1_000.0 {
        format!("{:.2} kHz", hz / 1_000.0)
    } else {
        format!("{hz:.0} Hz")
    }
}

fn format_percent(fraction: f64) -> String {
    format!("{:.1}%", fraction * 100.0)
}

enum CpuResult {
    Tree(FullCallTree),
    Top(TopFunctionsPage),
    Chart(ChartFrame),
    Empty,
} // CPU import metadata is content-addressed and intentionally has no persisted
// source locator. The file path is session-local display state only. Reopening
// requires explicit reselection plus digest/length and DB-generation checks.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct RecentCpu {
    binding: QueryBinding,
    artifact_id: ArtifactId,
    byte_length: u64,
    last_opened_unix_ms: u64,
}
struct RecentCpuPage {
    rows: Vec<RecentCpu>,
    offset: usize,
    scanned_sessions: usize,
    total_sessions: usize,
}
struct LoadedCpu {
    session: CpuSession,
    path: PathBuf,
    display_label: String,
    digest: SourceDigest,
    byte_length: u64,
}

type CpuOpenResult = (
    Arc<Mutex<CpuSession>>,
    Catalog,
    Query,
    QueryPage,
    Option<QueryBinding>,
    Option<CpuError>,
);

/// Payload classification of a picked CPU source.
///
/// The engine reports an unrecognized file as "invalid SIMPLEPERF magic", which
/// does not say what to import instead. Classifying by content first gives the
/// user an application-owned, localized message and still routes perf.data to
/// the converter.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum CpuSourceKind {
    PerfData,
    Simpleperf,
    /// Firefox / Gecko Profiler v24 JSON profile, plain or gzip-wrapped.
    GeckoProfile,
    ZipArchive,
    Unknown,
}

/// The payload kind of a picked source, and whether the picked file is a gzip
/// wrapper around that payload.
///
/// A gzip-compressed CPU profile carries the same profile as the plain file, so
/// the import transparently decompresses the picked archive and then reuses the
/// existing perf.data conversion or SIMPLEPERF parsing route. Classification
/// peeks at the decompressed prefix; the picked name is never trusted.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct CpuSourceClass {
    kind: CpuSourceKind,
    gzip: bool,
}

impl CpuSourceClass {
    const fn plain(kind: CpuSourceKind) -> Self {
        Self { kind, gzip: false }
    }
    const fn gzip(kind: CpuSourceKind) -> Self {
        Self { kind, gzip: true }
    }
    /// None when the CPU workspace can import this source.
    fn rejection(self) -> Option<CpuError> {
        match self.kind {
            CpuSourceKind::PerfData | CpuSourceKind::Simpleperf | CpuSourceKind::GeckoProfile => {
                None
            }
            CpuSourceKind::ZipArchive => {
                Some(CpuError::Local(TextKey::ProfilerCpuSourceZipArchive))
            }
            CpuSourceKind::Unknown => {
                Some(CpuError::Local(TextKey::ProfilerCpuSourceUnknownFormat))
            }
        }
    }
}

fn classify_cpu_source(path: &Path) -> Result<CpuSourceClass, CpuError> {
    let mut buffer = vec![0u8; CPU_SOURCE_PROBE_BYTES];
    let read = read_cpu_source_prefix(path, &mut buffer)?;
    let prefix = &buffer[..read];
    if is_gzip(prefix) {
        // Look through the wrapper at the profile it transports. Only the profile
        // transports are decompressed; anything else inside the archive stays an
        // unclassified payload instead of becoming a second archive format the
        // CPU workspace pretends to support.
        let mut decompressed = vec![0u8; CPU_SOURCE_PROBE_BYTES];
        let read = read_cpu_source_gzip_prefix(path, &mut decompressed)?;
        let kind = match payload_kind(&decompressed[..read]) {
            Some(
                kind @ (CpuSourceKind::PerfData
                | CpuSourceKind::Simpleperf
                | CpuSourceKind::GeckoProfile),
            ) => kind,
            _ => CpuSourceKind::Unknown,
        };
        return Ok(CpuSourceClass::gzip(kind));
    }
    Ok(CpuSourceClass::plain(
        payload_kind(prefix).unwrap_or(CpuSourceKind::Unknown),
    ))
}

/// Payload magic of an uncompressed prefix. gzip is the only wrapper the CPU
/// workspace looks through, so it reports None here.
fn payload_kind(prefix: &[u8]) -> Option<CpuSourceKind> {
    if is_perf_data(prefix) {
        Some(CpuSourceKind::PerfData)
    } else if prefix.starts_with(SIMPLEPERF_MAGIC) {
        Some(CpuSourceKind::Simpleperf)
    } else if aps_cpu::is_gecko_profile_prefix(prefix) {
        Some(CpuSourceKind::GeckoProfile)
    } else if prefix.starts_with(b"PK\x03\x04")
        || prefix.starts_with(b"PK\x05\x06")
        || prefix.starts_with(b"PK\x07\x08")
    {
        Some(CpuSourceKind::ZipArchive)
    } else {
        None
    }
}

fn is_gzip(prefix: &[u8]) -> bool {
    prefix.starts_with(&[0x1f, 0x8b])
}

/// Current and legacy Linux perf.data magic, in either byte order.
fn is_perf_data(prefix: &[u8]) -> bool {
    [b"PERFILE2", b"2ELIFREP", b"PERFFILE", b"ELIFFREP"]
        .iter()
        .any(|magic| prefix.starts_with(*magic))
}

fn read_cpu_source_prefix(path: &Path, buffer: &mut [u8]) -> Result<usize, CpuError> {
    let mut file =
        File::open(path).map_err(|_| CpuError::Local(TextKey::ProfilerCpuSourceInspectFailed))?;
    let mut filled = 0;
    while filled < buffer.len() {
        match file.read(&mut buffer[filled..]) {
            Ok(0) => break,
            Ok(read) => filled += read,
            Err(error) if error.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(_) => {
                return Err(CpuError::Local(TextKey::ProfilerCpuSourceInspectFailed));
            }
        }
    }
    Ok(filled)
}

/// Read only the first decompressed bytes of a gzip wrapper, so classification
/// stays bounded by the prefix instead of materializing the whole payload. A
/// wrapper this reader cannot decode is reported as a decompression failure
/// rather than silently falling back to the unclassified-payload message.
fn read_cpu_source_gzip_prefix(path: &Path, buffer: &mut [u8]) -> Result<usize, CpuError> {
    let file =
        File::open(path).map_err(|_| CpuError::Local(TextKey::ProfilerCpuSourceInspectFailed))?;
    let mut decoder = flate2::read::MultiGzDecoder::new(BufReader::new(file));
    let mut filled = 0;
    while filled < buffer.len() {
        match decoder.read(&mut buffer[filled..]) {
            Ok(0) => break,
            Ok(read) => filled += read,
            Err(error) if error.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(_) => {
                return Err(CpuError::Local(
                    TextKey::ProfilerCpuSourceGzipDecompressionFailed,
                ));
            }
        }
    }
    Ok(filled)
}

const CPU_GZIP_CHUNK_BYTES: usize = 64 * 1024;

/// Decompress a gzip-wrapped profile into an owned scratch directory so the
/// existing perf.data conversion and SIMPLEPERF parsing routes can read it as a
/// regular file. The decompressed size is bounded independently of the picked
/// file because a small archive can expand far past the import limit; the guard
/// keeps the file alive for exactly as long as the import needs it and removes
/// the scratch directory afterwards.
fn decompress_cpu_source(
    path: &Path,
    root: &Path,
    payload_limit: u64,
    cancelled: impl Fn() -> bool,
) -> Result<(TempWorkspace, PathBuf), CpuError> {
    let file =
        File::open(path).map_err(|_| CpuError::Local(TextKey::ProfilerCpuSourceInspectFailed))?;
    let picked = file
        .metadata()
        .map_err(|_| CpuError::Local(TextKey::ProfilerCpuSourceInspectFailed))?;
    if !picked.is_file() || picked.len() > MAX_PERF_DATA_INPUT_BYTES {
        return Err(CpuError::Local(TextKey::ProfilerCpuSourceNotBoundedFile));
    }
    let workspace = TempWorkspace::create(root)
        .map_err(|_| CpuError::Local(TextKey::ProfilerCpuSnapshotLocationUnavailable))?;
    let target = workspace.path().join("decompressed.cpu-profile");
    let mut output = File::create(&target)
        .map_err(|_| CpuError::Local(TextKey::ProfilerCpuSourceGzipDecompressionFailed))?;
    let mut decoder = flate2::read::MultiGzDecoder::new(BufReader::new(file));
    let mut buffer = [0u8; CPU_GZIP_CHUNK_BYTES];
    let mut written = 0u64;
    loop {
        if cancelled() {
            return Err(CpuError::Local(TextKey::ProfilerCpuImportClosed));
        }
        let read = match decoder.read(&mut buffer) {
            Ok(0) => break,
            Ok(read) => read,
            Err(_) => {
                return Err(CpuError::Local(
                    TextKey::ProfilerCpuSourceGzipDecompressionFailed,
                ));
            }
        };
        written = written
            .checked_add(read as u64)
            .filter(|total| *total <= payload_limit)
            .ok_or(CpuError::Local(TextKey::ProfilerCpuSourceGzipTooLarge))?;
        output
            .write_all(&buffer[..read])
            .map_err(|_| CpuError::Local(TextKey::ProfilerCpuSourceGzipDecompressionFailed))?;
    }
    output
        .sync_all()
        .map_err(|_| CpuError::Local(TextKey::ProfilerCpuSourceGzipDecompressionFailed))?;
    drop(output);
    Ok((workspace, target))
}

fn executable_file(path: &Path) -> bool {
    let Ok(metadata) = std::fs::metadata(path) else {
        return false;
    };
    if !metadata.is_file() {
        return false;
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        metadata.permissions().mode() & 0o111 != 0
    }
    #[cfg(not(unix))]
    {
        true
    }
}

fn discover_simpleperf() -> Option<PathBuf> {
    if let Some(path) = std::env::var_os("APS_SIMPLEPERF") {
        let path = PathBuf::from(path);
        if path.is_absolute() && executable_file(&path) {
            return Some(path);
        }
    }
    if let Some(paths) = std::env::var_os("PATH") {
        for directory in std::env::split_paths(&paths) {
            let candidate = directory.join("simpleperf");
            if executable_file(&candidate) {
                return Some(candidate);
            }
        }
    }
    let mut roots = Vec::new();
    for key in ["ANDROID_NDK_HOME", "ANDROID_NDK_ROOT"] {
        if let Some(root) = std::env::var_os(key) {
            roots.push(PathBuf::from(root));
        }
    }
    for key in ["ANDROID_HOME", "ANDROID_SDK_ROOT"] {
        if let Some(root) = std::env::var_os(key) {
            if let Ok(entries) = std::fs::read_dir(PathBuf::from(root).join("ndk")) {
                let mut versions: Vec<PathBuf> = entries.flatten().map(|e| e.path()).collect();
                versions.sort();
                roots.extend(versions);
            }
        }
    }
    if let Some(home) = std::env::var_os("HOME") {
        if let Ok(entries) = std::fs::read_dir(PathBuf::from(home).join("Library/Android/sdk/ndk"))
        {
            let mut versions: Vec<PathBuf> = entries.flatten().map(|e| e.path()).collect();
            versions.sort();
            roots.extend(versions);
        }
    }
    for root in roots {
        for candidate in [
            root.join("simpleperf"),
            root.join("simpleperf/bin/darwin/x86_64/simpleperf"),
            root.join("simpleperf/bin/darwin/arm64/simpleperf"),
            root.join("simpleperf/bin/linux/x86_64/simpleperf"),
            root.join("simpleperf/bin/linux/arm64/simpleperf"),
            root.join("simpleperf/bin/windows/x86_64/simpleperf.exe"),
            root.join("toolchains/llvm/prebuilt/darwin-x86_64/bin/simpleperf"),
            root.join("toolchains/llvm/prebuilt/linux-x86_64/bin/simpleperf"),
        ] {
            if executable_file(&candidate) {
                return Some(candidate);
            }
        }
    }
    None
}

fn resolve_simpleperf(explicit: Option<&Path>) -> Result<PathBuf, CpuError> {
    if let Some(path) = explicit {
        if !path.is_absolute() || !executable_file(path) {
            return Err(CpuError::Diagnostic(
                "Selected simpleperf must be an executable file picked by absolute path".into(),
            ));
        }
        return Ok(path.to_path_buf());
    }
    discover_simpleperf().ok_or_else(|| {
        CpuError::Diagnostic(
            "perf.data needs a compatible host simpleperf. Pick one in the CPU import toolbar, or set APS_SIMPLEPERF / ANDROID_NDK_HOME.".into(),
        )
    })
}

fn load_perf_data_source(
    path: &Path,
    converter: &Path,
    snapshot_root: Option<&Path>,
    cancelled: impl Fn() -> bool,
) -> Result<LoadedCpu, CpuError> {
    if cancelled() {
        return Err(CpuError::Local(TextKey::ProfilerCpuImportClosed));
    }
    if !path.is_absolute() {
        return Err(CpuError::Local(TextKey::ProfilerCpuSourceAbsoluteRequired));
    }
    let canonical = std::fs::canonicalize(path)
        .map_err(|_| CpuError::Local(TextKey::ProfilerCpuSourceUnresolved))?;
    let before = std::fs::metadata(&canonical)
        .map_err(|_| CpuError::Local(TextKey::ProfilerCpuSourceInspectFailed))?;
    if !before.is_file() || before.len() > MAX_PERF_DATA_INPUT_BYTES {
        return Err(CpuError::Diagnostic(
            "perf.data must be a regular file within 512 MiB".into(),
        ));
    }
    let Some(root) = snapshot_root else {
        return Err(CpuError::Diagnostic(
            "perf.data conversion requires the Rust snapshot location".into(),
        ));
    };
    let workspace = TempWorkspace::create(root).map_err(|_| {
        CpuError::Diagnostic("Cannot allocate a perf.data conversion workspace".into())
    })?;
    let output = workspace.path().join("converted.simpleperf.pb");
    let mut process = ProcessRequest::new(converter)
        .args(["report-sample", "--protobuf", "--show-callchain", "-i"])
        .arg(&canonical)
        .arg("-o")
        .arg(&output);
    process.timeout = PERF_DATA_CONVERSION_TIMEOUT;
    process.stdout_limit = 1024 * 1024;
    process.stderr_limit = 1024 * 1024;
    let token = CancellationToken::new();
    let result = ProcessRunner
        .run(&process, &token)
        .map_err(|error| CpuError::Diagnostic(format!("perf.data conversion failed: {error}")))?;
    if !result.status.success() {
        return Err(CpuError::Diagnostic(
            "simpleperf report-sample did not succeed; check the selected executable".into(),
        ));
    }
    if cancelled() {
        return Err(CpuError::Local(TextKey::ProfilerCpuImportClosed));
    }
    let after = std::fs::metadata(&canonical)
        .map_err(|_| CpuError::Local(TextKey::ProfilerCpuSourceDisappeared))?;
    if !same_cpu_file(&before, &after) {
        return Err(CpuError::Diagnostic(
            "perf.data changed while it was being converted".into(),
        ));
    }
    let bytes = read_bounded_cpu_file(&output)?;
    if bytes.len() < 12 || &bytes[..10] != b"SIMPLEPERF" {
        return Err(CpuError::Diagnostic(
            "simpleperf did not produce a SIMPLEPERF protobuf stream".into(),
        ));
    }
    let digest =
        SourceDigest::hash_reader(Cursor::new(&bytes), aps_cpu::DEFAULT_INPUT_LIMIT as u64)?;
    let byte_length = bytes.len() as u64;
    let snapshot = persist_cpu_snapshot(root, &output, digest, byte_length)?;
    if cancelled() {
        return Err(CpuError::Local(TextKey::ProfilerCpuImportClosed));
    }
    let session = CpuSession::open(&snapshot)?;
    Ok(LoadedCpu {
        session,
        path: snapshot,
        display_label: format!(
            "perf.data -> SIMPLEPERF protobuf via {}",
            converter.display()
        ),
        digest,
        byte_length,
    })
}

fn load_cpu_source(
    path: &Path,
    recent: Option<&RecentCpu>,
    cancelled: impl Fn() -> bool,
) -> Result<LoadedCpu, CpuError> {
    load_cpu_source_with(path, recent, cancelled, || CpuSession::open(path))
}
fn load_cpu_source_with(
    path: &Path,
    recent: Option<&RecentCpu>,
    cancelled: impl Fn() -> bool,
    parse: impl FnOnce() -> Result<CpuSession, String>,
) -> Result<LoadedCpu, CpuError> {
    if cancelled() {
        return Err(CpuError::Local(TextKey::ProfilerCpuImportClosed).into());
    }
    if !path.is_absolute() {
        return Err(CpuError::Local(TextKey::ProfilerCpuSourceAbsoluteRequired).into());
    }
    // Canonicalization is for transient identity comparison, not a saved path or
    // an authorization grant. Symlinks may be explicitly picked; their current
    // resolved target and content must stay unchanged over the import checks.
    let canonical = std::fs::canonicalize(path)
        .map_err(|_| CpuError::Local(TextKey::ProfilerCpuSourceUnresolved))?;
    let before = std::fs::metadata(&canonical)
        .map_err(|_| CpuError::Local(TextKey::ProfilerCpuSourceInspectFailed))?;
    if !before.is_file() || before.len() > aps_cpu::DEFAULT_INPUT_LIMIT as u64 {
        return Err(CpuError::Local(TextKey::ProfilerCpuSourceNotBoundedFile).into());
    }
    let digest = SourceDigest::hash_file(&canonical, aps_cpu::DEFAULT_INPUT_LIMIT as u64)?;
    if let Some(recent) = recent {
        if digest != recent.binding.source_digest || before.len() != recent.byte_length {
            return Err(CpuError::Local(TextKey::ProfilerCpuReselectedSourceMismatch).into());
        }
    }
    if cancelled() {
        return Err(CpuError::Local(TextKey::ProfilerCpuImportClosed).into());
    }
    let session = parse()?;
    if cancelled() {
        return Err(CpuError::Local(TextKey::ProfilerCpuImportClosed).into());
    }
    let after_digest = SourceDigest::hash_file(path, aps_cpu::DEFAULT_INPUT_LIMIT as u64)?;
    let after = std::fs::metadata(path)
        .map_err(|_| CpuError::Local(TextKey::ProfilerCpuSourceDisappeared))?;
    let after_canonical = std::fs::canonicalize(path)
        .map_err(|_| CpuError::Local(TextKey::ProfilerCpuSourceDisappeared))?;
    if digest != after_digest || canonical != after_canonical || !same_cpu_file(&before, &after) {
        return Err(CpuError::Local(TextKey::ProfilerCpuSourceChanged).into());
    }
    if cancelled() {
        return Err(CpuError::Local(TextKey::ProfilerCpuImportClosed).into());
    }
    Ok(LoadedCpu {
        session,
        path: canonical.clone(),
        display_label: canonical.display().to_string(),
        digest,
        byte_length: before.len(),
    })
}

/// Load one picked source through the route its content classification chose.
///
/// A gzip wrapper is decompressed into the snapshot root first, so the profile
/// inside follows exactly the same bounded perf.data conversion or SIMPLEPERF
/// parsing path as a plain file. The durable identity is the profile's own
/// SHA-256 and byte length after decompression: re-picking the same archive
/// decompresses to the same bytes, while the extracted profile also matches.
fn load_cpu_import(
    path: &Path,
    class: CpuSourceClass,
    converter: Option<&Path>,
    recent: Option<&RecentCpu>,
    snapshot_root: Option<&Path>,
    cancelled: impl Fn() -> bool,
) -> Result<LoadedCpu, CpuError> {
    if cancelled() {
        return Err(CpuError::Local(TextKey::ProfilerCpuImportClosed));
    }
    let (source, _workspace, gzip_label) = if class.gzip {
        let root = snapshot_root.ok_or(CpuError::Local(
            TextKey::ProfilerCpuSnapshotLocationUnavailable,
        ))?;
        let payload_limit = match class.kind {
            CpuSourceKind::PerfData => MAX_PERF_DATA_INPUT_BYTES,
            CpuSourceKind::GeckoProfile => aps_cpu::MAX_GECKO_PROFILE_BYTES as u64,
            _ => aps_cpu::DEFAULT_INPUT_LIMIT as u64,
        };
        let (workspace, decompressed) =
            decompress_cpu_source(path, root, payload_limit, &cancelled)?;
        let name = path
            .file_name()
            .map(|name| name.to_string_lossy().into_owned())
            .unwrap_or_else(|| "gzip CPU profile".into());
        let label = match (class.kind, converter) {
            (CpuSourceKind::PerfData, Some(converter)) => format!(
                "{name} (gzip) -> SIMPLEPERF protobuf via {}",
                converter.display()
            ),
            (CpuSourceKind::GeckoProfile, _) => {
                format!("{name} (gzip) -> Gecko profile JSON")
            }
            _ => format!("{name} (gzip) -> SIMPLEPERF protobuf"),
        };
        (decompressed, Some(workspace), Some(label))
    } else {
        (path.to_path_buf(), None, None)
    };
    let mut loaded = if class.kind == CpuSourceKind::PerfData {
        let converter = converter.ok_or_else(|| {
            CpuError::Diagnostic("perf.data needs a compatible host simpleperf".into())
        })?;
        let loaded = load_perf_data_source(&source, converter, snapshot_root, &cancelled)?;
        if let Some(recent) = recent {
            if loaded.digest != recent.binding.source_digest
                || loaded.byte_length != recent.byte_length
            {
                return Err(CpuError::Local(
                    TextKey::ProfilerCpuReselectedSourceMismatch,
                ));
            }
        }
        loaded
    } else {
        let mut loaded = load_cpu_source(&source, recent, &cancelled)?;
        if let Some(root) = snapshot_root {
            let snapshot =
                persist_cpu_snapshot(root, &loaded.path, loaded.digest, loaded.byte_length)?;
            // Gzip imports decompress into an owned scratch directory that is
            // removed as soon as loading finishes; keep package exports on the
            // durable snapshot bytes instead.
            loaded.session.rebind_path(snapshot);
        }
        loaded
    };
    match gzip_label {
        Some(label) => loaded.display_label = label,
        None if class.kind == CpuSourceKind::GeckoProfile => {
            loaded.display_label = format!("{} (Gecko profile JSON)", loaded.display_label);
        }
        None => {}
    }
    Ok(loaded)
}

fn finish_cpu_open(
    loaded: LoadedCpu,
    metadata_path: Option<&Path>,
    recent: Option<&RecentCpu>,
    cancelled: impl Fn() -> bool,
) -> Result<CpuOpenResult, CpuError> {
    let mut session = loaded.session;
    let catalog = Catalog {
        summary: session.summary().clone(),
        threads: session.threads().to_vec(),
        events: session.events().to_vec(),
        source_label: loaded.display_label,
        source_digest: loaded.digest,
        byte_length: loaded.byte_length,
    };
    let query = Query {
        limit: PAGE_SIZE,
        ..Default::default()
    };
    let page = session.query(&query)?;
    if cancelled() {
        return Err(CpuError::Local(TextKey::ProfilerCpuImportClosed).into());
    }
    // SQLite failure never discards a successfully decoded local file. A
    // committed record may remain if Close races the final commit; the UI
    // generation guard still prevents stale publication.
    let (association, metadata_error) = match metadata_path {
        Some(path) => match associate_cpu(path, loaded.digest, loaded.byte_length, recent) {
            Ok(binding) => (Some(binding), None),
            Err(error) => (None, Some(error.into())),
        },
        None => (
            None,
            Some(CpuError::Local(TextKey::ProfilerCpuMemoryOnlyImport).into()),
        ),
    };
    Ok((
        Arc::new(Mutex::new(session)),
        catalog,
        query,
        page,
        association,
        metadata_error,
    ))
}

pub(crate) fn cpu_snapshot_path(root: &Path, digest: SourceDigest) -> Result<PathBuf, CpuError> {
    if !root.is_absolute() {
        return Err(CpuError::Local(TextKey::ProfilerCpuSnapshotRootAbsoluteRequired).into());
    }
    match std::fs::symlink_metadata(root) {
        Ok(metadata) if metadata.file_type().is_symlink() || !metadata.is_dir() => {
            return Err(CpuError::Local(TextKey::ProfilerCpuSnapshotRootInvalid).into());
        }
        Ok(_) => {}
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(_) => {
            return Err(CpuError::Local(TextKey::ProfilerCpuSnapshotRootInspectFailed).into());
        }
    }
    Ok(root.join(format!("{}.simpleperf.pb", digest.to_hex())))
}

fn read_bounded_cpu_file(path: &Path) -> Result<Vec<u8>, CpuError> {
    let mut file =
        File::open(path).map_err(|_| CpuError::Local(TextKey::ProfilerCpuSnapshotOpenFailed))?;
    let before = file
        .metadata()
        .map_err(|_| CpuError::Local(TextKey::ProfilerCpuSnapshotInspectFailed))?;
    if !before.is_file() || before.len() > aps_cpu::DEFAULT_INPUT_LIMIT as u64 {
        return Err(CpuError::Local(TextKey::ProfilerCpuSnapshotSourceInvalid).into());
    }
    let mut bytes = Vec::with_capacity(before.len().min(1024 * 1024) as usize);
    (&mut file)
        .take(aps_cpu::DEFAULT_INPUT_LIMIT as u64 + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| CpuError::Local(TextKey::ProfilerCpuSnapshotReadFailed))?;
    if bytes.len() > aps_cpu::DEFAULT_INPUT_LIMIT {
        return Err(CpuError::Local(TextKey::ProfilerCpuSnapshotTooLarge).into());
    }
    let after = file
        .metadata()
        .map_err(|_| CpuError::Local(TextKey::ProfilerCpuSnapshotReinspectFailed))?;
    if !same_cpu_file(&before, &after) || bytes.len() as u64 != before.len() {
        return Err(CpuError::Local(TextKey::ProfilerCpuSnapshotChanged).into());
    }
    Ok(bytes)
}

fn persist_cpu_snapshot(
    root: &Path,
    source: &Path,
    digest: SourceDigest,
    byte_length: u64,
) -> Result<PathBuf, CpuError> {
    let target = cpu_snapshot_path(root, digest)?;
    let bytes = read_bounded_cpu_file(source)?;
    if bytes.len() as u64 != byte_length
        || SourceDigest::hash_reader(Cursor::new(&bytes), aps_cpu::DEFAULT_INPUT_LIMIT as u64)?
            != digest
    {
        return Err(CpuError::Local(TextKey::ProfilerCpuSnapshotBytesChanged).into());
    }
    aps_platform::atomic_write(&target, &bytes)
        .map_err(|_| CpuError::Local(TextKey::ProfilerCpuSnapshotPersistFailed))?;
    validate_cpu_snapshot(&target, digest, byte_length)?;
    Ok(target)
}

fn validate_cpu_snapshot(
    path: &Path,
    digest: SourceDigest,
    byte_length: u64,
) -> Result<(), CpuError> {
    let metadata = std::fs::symlink_metadata(path)
        .map_err(|_| CpuError::Local(TextKey::ProfilerCpuSavedSnapshotMissing))?;
    if metadata.file_type().is_symlink() || !metadata.is_file() || metadata.len() != byte_length {
        return Err(CpuError::Local(TextKey::ProfilerCpuSavedSnapshotInvalid).into());
    }
    if SourceDigest::hash_file(path, aps_cpu::DEFAULT_INPUT_LIMIT as u64)? != digest {
        return Err(CpuError::Local(TextKey::ProfilerCpuSavedSnapshotDigestMismatch).into());
    }
    Ok(())
}

fn load_cpu_snapshot(
    root: &Path,
    recent: &RecentCpu,
    cancelled: impl Fn() -> bool,
) -> Result<LoadedCpu, CpuError> {
    if cancelled() {
        return Err(CpuError::Local(TextKey::ProfilerCpuImportClosed).into());
    }
    let path = cpu_snapshot_path(root, recent.binding.source_digest)?;
    validate_cpu_snapshot(&path, recent.binding.source_digest, recent.byte_length)?;
    let session = CpuSession::open(&path)?;
    validate_cpu_snapshot(&path, recent.binding.source_digest, recent.byte_length)?;
    if cancelled() {
        return Err(CpuError::Local(TextKey::ProfilerCpuImportClosed).into());
    }
    Ok(LoadedCpu {
        session,
        path,
        display_label: format!("Rust-owned CPU snapshot · {}", recent.binding.source_digest),
        digest: recent.binding.source_digest,
        byte_length: recent.byte_length,
    })
}

fn same_cpu_file(before: &std::fs::Metadata, after: &std::fs::Metadata) -> bool {
    if !after.is_file()
        || before.len() != after.len()
        || before.modified().ok() != after.modified().ok()
        || before.created().ok() != after.created().ok()
    {
        return false;
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        if before.dev() != after.dev()
            || before.ino() != after.ino()
            || before.ctime() != after.ctime()
            || before.ctime_nsec() != after.ctime_nsec()
        {
            return false;
        }
    }
    true
}

fn write_new_export(path: &Path, bytes: &[u8]) -> Result<(), CpuError> {
    if !path.is_absolute() || bytes.len() > aps_cpu::MAX_FIREFOX_BYTES {
        return Err(CpuError::Local(TextKey::ProfilerCpuExportDestinationInvalid).into());
    }
    write_new_blob(path, bytes)
}

fn write_new_blob(path: &Path, bytes: &[u8]) -> Result<(), CpuError> {
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut file = options
        .open(path)
        .map_err(|_| CpuError::Local(TextKey::ProfilerCpuExportCreateFailed))?;
    if file.write_all(bytes).and_then(|_| file.sync_all()).is_err() {
        drop(file);
        let _ = std::fs::remove_file(path);
        return Err(CpuError::Local(TextKey::ProfilerCpuFirefoxExportWriteFailed).into());
    }
    Ok(())
}

pub(crate) fn cpu_artifact(
    digest: SourceDigest,
    byte_length: u64,
) -> Result<CaptureArtifact, String> {
    let hex = digest.to_hex();
    CaptureArtifact::new(CaptureArtifact {
        id: ArtifactId::new(format!("cpu-artifact-v1:{hex}"))?,
        source_digest: digest,
        byte_length,
        media_type: CPU_MEDIA_TYPE.into(),
        provenance: Provenance::new(
            "Unknown producer of imported SIMPLEPERF stream",
            vec![ProvenanceStep::new(
                ProvenanceAction::Import,
                "aps-rust-desktop:cpu-import:v1",
                Some(digest),
                digest,
            )?],
        )?,
        capabilities: Capabilities::new([CapabilityId::new("cpu.simpleperf-protobuf")?])?,
        completeness: ArtifactCompleteness::unknown(
            "Offline stream import does not establish device capture completeness or method durations",
        )?,
        clock: ClockDomain::new(
            ClockDomainId::new(format!("cpu-trace:{hex}"))?,
            ClockKind::Trace,
            format!("sha256:{hex}; producer clock epoch unverified"),
        )?,
        device: None,
        process: None,
    })
}
fn cpu_session_id(digest: SourceDigest) -> Result<ProfilerSessionId, String> {
    ProfilerSessionId::new(format!("{CPU_SESSION_PREFIX}{}", digest.to_hex()))
}
fn cpu_label(digest: SourceDigest) -> String {
    format!("CPU import · {}", &digest.to_hex()[..16])
}
fn unix_ms() -> Result<u64, CpuError> {
    u64::try_from(
        SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_err(|_| CpuError::Local(TextKey::ProfilerCpuClockBeforeEpoch))?
            .as_millis(),
    )
    .map_err(|_| CpuError::Local(TextKey::ProfilerCpuTimestampOverflow).into())
}
fn ensure_cpu_metadata_parent(path: &Path) -> Result<(), CpuError> {
    if !path.is_absolute()
        || path.file_name().and_then(|name| name.to_str()) != Some(aps_storage::DATABASE_FILE_NAME)
    {
        return Err(CpuError::Local(TextKey::ProfilerCpuMetadataPathInvalid).into());
    }
    let parent = path
        .parent()
        .ok_or(CpuError::Local(TextKey::ProfilerCpuMetadataParentRequired))?;
    let mut builder = std::fs::DirBuilder::new();
    builder.recursive(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::DirBuilderExt;
        builder.mode(0o700);
    }
    builder
        .create(parent)
        .map_err(|_| CpuError::Local(TextKey::ProfilerCpuMetadataDirectoryCreateFailed))?;
    let metadata = std::fs::symlink_metadata(parent)
        .map_err(|_| CpuError::Local(TextKey::ProfilerCpuMetadataDirectoryInspectFailed))?;
    if metadata.file_type().is_symlink() || !metadata.is_dir() {
        return Err(CpuError::Local(TextKey::ProfilerCpuMetadataParentInvalid).into());
    }
    Ok(())
}
pub(crate) fn validate_cpu_session(
    session: &StoredSession,
    digest: SourceDigest,
    artifact_id: &ArtifactId,
) -> Result<(), CpuError> {
    if session.id != cpu_session_id(digest)?
        || session.artifact_ids.as_slice() != [artifact_id.clone()]
        || session.device_id.is_some()
        || session.process_identity.is_some()
        || session.label != cpu_label(digest)
    {
        return Err(CpuError::Local(TextKey::ProfilerCpuSavedSessionInvalid).into());
    }
    Ok(())
}
fn associate_cpu(
    path: &Path,
    digest: SourceDigest,
    byte_length: u64,
    recent: Option<&RecentCpu>,
) -> Result<QueryBinding, CpuError> {
    ensure_cpu_metadata_parent(path)?;
    let now = unix_ms()?;
    let artifact = cpu_artifact(digest, byte_length)?;
    let id = cpu_session_id(digest)?;
    let mut storage = Storage::open(path)?;
    // put_artifact is idempotent only for an identical immutable metadata
    // document. It rejects a reused identity with altered content/provenance.
    storage.put_artifact(&artifact, now)?;
    let expected_generation = if let Some(existing) = storage.session(&id)? {
        validate_cpu_session(&existing, digest, &artifact.id)?;
        if let Some(recent) = recent {
            if existing.generation != recent.binding.generation
                || recent.binding.session_id != id
                || recent.binding.source_digest != digest
                || recent.artifact_id != artifact.id
                || recent.byte_length != byte_length
            {
                return Err(CpuError::Local(TextKey::ProfilerCpuSavedAssociationChanged).into());
            }
        }
        existing.generation
    } else {
        if recent.is_some() {
            return Err(CpuError::Local(TextKey::ProfilerCpuSavedSessionRemoved).into());
        }
        let mut session = ProfilerSession::new(id.clone());
        session.artifacts.push(artifact.id.clone());
        storage.create_session(&session, &cpu_label(digest), now)?;
        0
    };
    let next = expected_generation
        .checked_add(1)
        .filter(|value| *value <= i64::MAX as u64)
        .ok_or(CpuError::Local(TextKey::ProfilerCpuGenerationExhausted))?;
    storage.open_session(&id, expected_generation, now)?;
    storage.advance_generation(&id, expected_generation, next)?;
    let binding = QueryBinding {
        session_id: id,
        generation: next,
        source_digest: digest,
    };
    storage.validate_binding(&binding)?;
    Ok(binding)
}
fn validate_recent(path: &Path, recent: &RecentCpu) -> Result<(), CpuError> {
    // Do not create a replacement database if saved state was removed.
    if !path.exists() {
        return Err(CpuError::Local(TextKey::ProfilerCpuDatabaseMissing).into());
    }
    let storage = Storage::open(path)?;
    storage.validate_binding(&recent.binding)?;
    let session = storage
        .session(&recent.binding.session_id)?
        .ok_or(CpuError::Local(TextKey::ProfilerCpuSavedSessionMissing))?;
    validate_cpu_session(&session, recent.binding.source_digest, &recent.artifact_id)?;
    if session.generation != recent.binding.generation {
        return Err(CpuError::Local(TextKey::ProfilerCpuGenerationStale).into());
    }
    let artifact = storage
        .artifact(&recent.artifact_id)?
        .ok_or(CpuError::Local(TextKey::ProfilerCpuSavedArtifactMissing))?;
    if artifact.source_digest != recent.binding.source_digest
        || artifact.byte_length != recent.byte_length
        || artifact.media_type != CPU_MEDIA_TYPE
    {
        return Err(CpuError::Local(TextKey::ProfilerCpuSavedSourceInconsistent).into());
    }
    Ok(())
}
fn recent_cpu(path: &Path, offset: usize) -> Result<RecentCpuPage, CpuError> {
    // Explicit refresh only. Merely constructing the view neither creates a
    // database nor reads persisted source files or automatically imports one.
    match std::fs::symlink_metadata(path) {
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            return Ok(RecentCpuPage {
                rows: Vec::new(),
                offset,
                scanned_sessions: 0,
                total_sessions: 0,
            });
        }
        Err(_) => return Err(CpuError::Local(TextKey::ProfilerCpuDatabaseInspectFailed).into()),
        Ok(_) => {}
    }
    let storage = Storage::open(path)?;
    let page = storage.list_sessions(PageRequest {
        offset,
        limit: RECENT_PAGE_SIZE,
    })?;
    let mut rows = Vec::new();
    for summary in &page.rows {
        if !summary.id.as_str().starts_with(CPU_SESSION_PREFIX) {
            continue;
        }
        let session = storage
            .session(&summary.id)?
            .ok_or(CpuError::Local(TextKey::ProfilerCpuRecentSessionChanged))?;
        if session.generation != summary.generation || session.artifact_ids.len() != 1 {
            return Err(CpuError::Local(TextKey::ProfilerCpuRecentReferencesChanged).into());
        }
        let artifact_id = session.artifact_ids[0].clone();
        let artifact = storage
            .artifact(&artifact_id)?
            .ok_or(CpuError::Local(TextKey::ProfilerCpuRecentArtifactMissing))?;
        if artifact.media_type != CPU_MEDIA_TYPE {
            return Err(CpuError::Local(TextKey::ProfilerCpuRecentArtifactTypeMismatch).into());
        }
        let expected = cpu_artifact(artifact.source_digest, artifact.byte_length)?;
        if artifact_id != expected.id {
            return Err(CpuError::Local(TextKey::ProfilerCpuRecentArtifactInconsistent).into());
        }
        validate_cpu_session(&session, artifact.source_digest, &artifact_id)?;
        let binding = QueryBinding {
            session_id: session.id,
            generation: session.generation,
            source_digest: artifact.source_digest,
        };
        storage.validate_binding(&binding)?;
        rows.push(RecentCpu {
            binding,
            artifact_id,
            byte_length: artifact.byte_length,
            last_opened_unix_ms: session.last_opened_unix_ms,
        });
    }
    Ok(RecentCpuPage {
        rows,
        offset: page.offset,
        scanned_sessions: page.rows.len(),
        total_sessions: page.total,
    })
}
fn check_cpu_binding(
    path: Option<&Path>,
    binding: Option<QueryBinding>,
) -> Option<Result<QueryBinding, CpuError>> {
    binding.map(|binding| {
        let path = path.ok_or(CpuError::Local(
            TextKey::ProfilerCpuMetadataLocationUnavailable,
        ))?;
        if !path.exists() {
            return Err(CpuError::Local(TextKey::ProfilerCpuMetadataDisappeared).into());
        }
        let storage = Storage::open(path)?;
        storage.validate_binding(&binding)?;
        Ok(binding)
    })
}
impl Drop for CpuWorkspace {
    fn drop(&mut self) {
        self.import_cancel.store(true, Ordering::Release);
    }
}

impl NoticeSource for CpuWorkspace {
    fn notice_snapshot(&self) -> Vec<NoticeSnapshot> {
        let mut notices = Vec::new();
        if let Some(error) = &self.error {
            let message = error.display(self.language);
            notices.push(NoticeSnapshot::error(crate::evidence::clipped(
                &message, 8192,
            )));
        }
        if let Some(error) = &self.metadata_error {
            let message = error.display(self.language);
            notices.push(NoticeSnapshot::error(crate::evidence::clipped(
                &message, 8192,
            )));
        }
        if let Some(error) = self
            .disassembly
            .as_ref()
            .and_then(|panel| panel.error.as_deref())
        {
            notices.push(NoticeSnapshot::error(crate::evidence::clipped(error, 8192)));
        }
        notices.retain(|notice| !notice.message.trim().is_empty());
        notices.truncate(8);
        notices
    }
}

#[cfg(test)]
mod metadata_tests {
    use super::{
        CPU_MEDIA_TYPE, CpuError, CpuSourceClass, CpuSourceKind, Language, RecentCpu,
        associate_cpu, chart_tooltip_placement, check_cpu_binding, classify_cpu_source,
        cpu_artifact, cpu_snapshot_path, decompress_cpu_source, load_cpu_import, load_cpu_snapshot,
        load_cpu_source, load_cpu_source_with, persist_cpu_snapshot, recent_cpu, validate_recent,
    };
    use aps_contracts::{ArtifactCompleteness, ProfilerSession, ProfilerSessionId, SourceDigest};
    use aps_storage::{PageRequest, Storage};
    use aps_ui_foundation::TextKey;
    use std::{
        io::{Cursor, Write as _},
        path::{Path, PathBuf},
        sync::atomic::{AtomicU64, Ordering},
    };
    static SEQUENCE: AtomicU64 = AtomicU64::new(0);
    struct Fixture {
        root: PathBuf,
    }
    impl Fixture {
        fn new() -> Self {
            let root = std::env::temp_dir().join(format!(
                "aps-cpu-import-metadata-{}-{}",
                std::process::id(),
                SEQUENCE.fetch_add(1, Ordering::Relaxed)
            ));
            std::fs::create_dir(&root).unwrap();
            Self { root }
        }
        fn database(&self) -> PathBuf {
            self.root
                .join("metadata")
                .join(aps_storage::DATABASE_FILE_NAME)
        }
        fn profile(&self, name: &str) -> PathBuf {
            let path = self.root.join(name);
            std::fs::write(&path, b"SIMPLEPERF\x01\x00\x00\x00\x00\x00").unwrap();
            path
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.root);
        }
    }
    fn imported(fixture: &Fixture, profile: &Path) -> RecentCpu {
        let loaded = load_cpu_source(profile, None, || false).unwrap();
        let binding =
            associate_cpu(&fixture.database(), loaded.digest, loaded.byte_length, None).unwrap();
        let mut page = recent_cpu(&fixture.database(), 0).unwrap();
        assert_eq!(page.rows.len(), 1);
        let recent = page.rows.remove(0);
        assert_eq!(recent.binding, binding);
        recent
    }

    #[test]
    fn explicit_recent_refresh_of_missing_database_does_not_create_it() {
        let fixture = Fixture::new();
        let page = recent_cpu(&fixture.database(), 0).unwrap();
        assert_eq!(
            (page.rows.len(), page.scanned_sessions, page.total_sessions),
            (0, 0, 0)
        );
        assert!(!fixture.database().exists());
        assert!(!fixture.database().parent().unwrap().exists());
    }
    #[test]
    fn cpu_import_stores_immutable_metadata_without_source_path_or_content_copy() {
        let fixture = Fixture::new();
        let profile = fixture.profile("sensitive-project-source-profile.protobuf");
        let recent = imported(&fixture, &profile);
        let storage = Storage::open(fixture.database()).unwrap();
        let stored = storage.artifact(&recent.artifact_id).unwrap().unwrap();
        assert_eq!(stored.media_type, CPU_MEDIA_TYPE);
        assert_eq!(
            stored.byte_length,
            std::fs::metadata(&profile).unwrap().len()
        );
        let document = format!("{:?}", stored.metadata);
        assert!(!document.contains("sensitive-project-source-profile"));
        assert!(!document.contains(&profile.display().to_string()));
        let session = storage
            .session(&recent.binding.session_id)
            .unwrap()
            .unwrap();
        assert!(!session.label.contains("sensitive-project-source-profile"));
        assert_eq!(session.artifact_ids, [recent.artifact_id]);
        assert_eq!(std::fs::read_dir(&fixture.root).unwrap().count(), 2);
    }
    #[test]
    fn reopen_reselects_identical_content_at_new_path_and_advances_generation() {
        let fixture = Fixture::new();
        let original = fixture.profile("original.protobuf");
        let recent = imported(&fixture, &original);
        let moved = fixture.root.join("moved.protobuf");
        std::fs::rename(&original, &moved).unwrap();
        validate_recent(&fixture.database(), &recent).unwrap();
        let loaded = load_cpu_source(&moved, Some(&recent), || false).unwrap();
        let binding = associate_cpu(
            &fixture.database(),
            loaded.digest,
            loaded.byte_length,
            Some(&recent),
        )
        .unwrap();
        assert_eq!(binding.generation, recent.binding.generation + 1);
        assert_eq!(binding.source_digest, recent.binding.source_digest);
        assert!(validate_recent(&fixture.database(), &recent).is_err());
        let storage = Storage::open(fixture.database()).unwrap();
        assert_eq!(
            storage
                .list_artifacts(PageRequest::default())
                .unwrap()
                .total,
            1
        );
        assert_eq!(
            storage.list_sessions(PageRequest::default()).unwrap().total,
            1
        );
    }
    #[test]
    fn saved_cpu_snapshot_reopens_after_source_is_removed() {
        let fixture = Fixture::new();
        let profile = fixture.profile("original.protobuf");
        let recent = imported(&fixture, &profile);
        let loaded = load_cpu_source(&profile, None, || false).unwrap();
        let snapshot_root = fixture.root.join("cpu-artifacts");
        persist_cpu_snapshot(
            &snapshot_root,
            &loaded.path,
            loaded.digest,
            loaded.byte_length,
        )
        .unwrap();
        std::fs::remove_file(&profile).unwrap();

        let reopened = load_cpu_snapshot(&snapshot_root, &recent, || false).unwrap();
        assert_eq!(reopened.digest, recent.binding.source_digest);
        assert_eq!(reopened.byte_length, recent.byte_length);
        assert_eq!(reopened.session.summary(), loaded.session.summary());
        assert!(
            reopened
                .display_label
                .starts_with("Rust-owned CPU snapshot")
        );
    }
    #[test]
    fn tampered_cpu_snapshot_fails_closed() {
        let fixture = Fixture::new();
        let profile = fixture.profile("original.protobuf");
        let recent = imported(&fixture, &profile);
        let loaded = load_cpu_source(&profile, None, || false).unwrap();
        let snapshot_root = fixture.root.join("cpu-artifacts");
        let snapshot = persist_cpu_snapshot(
            &snapshot_root,
            &loaded.path,
            loaded.digest,
            loaded.byte_length,
        )
        .unwrap();
        std::fs::write(snapshot, b"tampered").unwrap();

        assert!(load_cpu_snapshot(&snapshot_root, &recent, || false).is_err());
    }
    #[test]
    fn different_reselected_source_fails_before_parser_and_saved_metadata_stays_unchanged() {
        let fixture = Fixture::new();
        let profile = fixture.profile("profile.protobuf");
        let recent = imported(&fixture, &profile);
        let wrong = fixture.root.join("wrong.protobuf");
        std::fs::write(&wrong, b"other bytes").unwrap();
        let result = load_cpu_source_with(
            &wrong,
            Some(&recent),
            || false,
            || panic!("mismatch must fail before parsing"),
        );
        assert!(result.is_err());
        validate_recent(&fixture.database(), &recent).unwrap();
        let storage = Storage::open(fixture.database()).unwrap();
        assert_eq!(
            storage
                .session(&recent.binding.session_id)
                .unwrap()
                .unwrap()
                .generation,
            recent.binding.generation
        );
    }
    #[test]
    fn mutation_between_pre_hash_and_post_hash_rejects_durable_identity() {
        let fixture = Fixture::new();
        let profile = fixture.profile("changing.protobuf");
        let result = load_cpu_source_with(
            &profile,
            None,
            || false,
            || {
                let parsed = aps_cpu::CpuSession::open(&profile)?;
                std::fs::write(&profile, b"SIMPLEPERF\x01\x00\x01\x00\x00\x00").unwrap();
                Ok(parsed)
            },
        );
        assert!(
            matches!(result, Err(message) if message.contains("changed across hashing/parsing"))
        );
        assert!(!fixture.database().exists());
    }
    #[test]
    fn close_flag_is_observed_before_parse_and_after_parse() {
        let fixture = Fixture::new();
        let profile = fixture.profile("profile.protobuf");
        assert!(
            load_cpu_source_with(
                &profile,
                None,
                || true,
                || panic!("cancelled parse must not start")
            )
            .is_err()
        );
        let closed = std::cell::Cell::new(false);
        let result = load_cpu_source_with(
            &profile,
            None,
            || closed.get(),
            || {
                let parsed = aps_cpu::CpuSession::open(&profile)?;
                closed.set(true);
                Ok(parsed)
            },
        );
        assert!(matches!(result, Err(message) if message.contains("closed")));
    }
    #[test]
    fn database_failure_is_separate_from_successful_local_decode() {
        let fixture = Fixture::new();
        let profile = fixture.profile("profile.protobuf");
        let loaded = load_cpu_source(&profile, None, || false).unwrap();
        assert_eq!(loaded.session.summary().sample_count, 0);
        let database = fixture.database();
        std::fs::create_dir_all(database.parent().unwrap()).unwrap();
        std::fs::write(&database, b"foreign database bytes to preserve").unwrap();
        let association = associate_cpu(&database, loaded.digest, loaded.byte_length, None);
        assert!(association.is_err());
        assert_eq!(loaded.session.summary().sample_count, 0);
        assert_eq!(
            std::fs::read(database).unwrap(),
            b"foreign database bytes to preserve"
        );
    }
    #[test]
    fn query_binding_invalidates_when_another_open_advances_saved_generation() {
        let fixture = Fixture::new();
        let profile = fixture.profile("profile.protobuf");
        let recent = imported(&fixture, &profile);
        assert!(
            check_cpu_binding(Some(&fixture.database()), Some(recent.binding.clone()))
                .unwrap()
                .is_ok()
        );
        let newer = associate_cpu(
            &fixture.database(),
            recent.binding.source_digest,
            recent.byte_length,
            None,
        )
        .unwrap();
        assert_eq!(newer.generation, recent.binding.generation + 1);
        assert!(
            check_cpu_binding(Some(&fixture.database()), Some(recent.binding))
                .unwrap()
                .is_err()
        );
        assert!(check_cpu_binding(None, None).is_none());
    }
    #[test]
    fn deleted_or_stale_recent_session_cannot_silently_recreate_saved_association() {
        let fixture = Fixture::new();
        let profile = fixture.profile("profile.protobuf");
        let recent = imported(&fixture, &profile);
        Storage::open(fixture.database())
            .unwrap()
            .delete_session_metadata(&recent.binding.session_id, recent.binding.generation)
            .unwrap();
        assert!(validate_recent(&fixture.database(), &recent).is_err());
        assert!(
            associate_cpu(
                &fixture.database(),
                recent.binding.source_digest,
                recent.byte_length,
                Some(&recent)
            )
            .is_err()
        );
        assert_eq!(
            std::fs::read(&profile).unwrap(),
            b"SIMPLEPERF\x01\x00\x00\x00\x00\x00"
        );
    }
    #[test]
    fn recent_pages_are_bounded_over_shared_session_catalog_without_importing_sources() {
        let fixture = Fixture::new();
        let profile = fixture.profile("profile.protobuf");
        let recent = imported(&fixture, &profile);
        let mut storage = Storage::open(fixture.database()).unwrap();
        for index in 0..24 {
            storage
                .create_session(
                    &ProfilerSession::new(
                        ProfilerSessionId::new(format!("other:{index:02}")).unwrap(),
                    ),
                    "Other profiler",
                    i64::MAX as u64 - 100 + index,
                )
                .unwrap();
        }
        std::fs::remove_file(profile).unwrap();
        let first = recent_cpu(&fixture.database(), 0).unwrap();
        assert_eq!(
            (
                first.scanned_sessions,
                first.total_sessions,
                first.rows.len()
            ),
            (20, 25, 0)
        );
        let second = recent_cpu(&fixture.database(), 20).unwrap();
        assert_eq!(
            (
                second.scanned_sessions,
                second.total_sessions,
                second.rows.len()
            ),
            (5, 25, 1)
        );
        assert_eq!(second.rows[0].binding, recent.binding);
    }
    #[test]
    fn provenance_and_completeness_do_not_invent_device_or_capture_claims() {
        let digest = SourceDigest::hash_reader(b"test artifact".as_slice(), 100).unwrap();
        let artifact = cpu_artifact(digest, 13).unwrap();
        assert!(artifact.device.is_none());
        assert!(artifact.process.is_none());
        assert!(artifact.provenance.producer.starts_with("Unknown producer"));
        assert_eq!(artifact.provenance.steps[0].input_digest, Some(digest));
        assert_eq!(artifact.provenance.steps[0].output_digest, digest);
        assert!(matches!(
            artifact.completeness,
            ArtifactCompleteness::Unknown { .. }
        ));
        artifact.validate().unwrap();
    }
    #[test]
    fn relative_source_and_wrong_metadata_filename_are_rejected_without_persistence() {
        let fixture = Fixture::new();
        assert!(load_cpu_source(Path::new("relative.protobuf"), None, || false).is_err());
        let digest = SourceDigest::hash_reader(b"test".as_slice(), 10).unwrap();
        let wrong = fixture.root.join("legacy.sqlite3");
        assert!(associate_cpu(&wrong, digest, 4, None).is_err());
        assert!(!wrong.exists());
    }
    /// Minimal SIMPLEPERF stream the engine accepts (magic + one record word),
    /// enough to prove the decompressed payload is what the import parses.
    const MINIMAL_SIMPLEPERF: &[u8] = b"SIMPLEPERF\x01\x00\x00\x00\x00\x00";
    fn gzip(bytes: &[u8]) -> Vec<u8> {
        let mut encoder = flate2::write::GzEncoder::new(Vec::new(), flate2::Compression::default());
        encoder.write_all(bytes).unwrap();
        encoder.finish().unwrap()
    }
    fn gzip_source(fixture: &Fixture, name: &str, payload: &[u8]) -> PathBuf {
        let path = fixture.root.join(name);
        std::fs::write(&path, gzip(payload)).unwrap();
        path
    }
    /// Minimal Firefox / Gecko v24 profile: one thread, two samples, one real
    /// stack plus one stack-less sample.
    fn gecko_profile() -> Vec<u8> {
        br#"{"meta":{"interval":1,"product":"Android Performance Studio","version":24},"libs":[],"threads":[{"tid":11,"pid":11,"name":"main","markers":{},"samples":{"schema":{"stack":0,"time":1,"responsiveness":2},"data":[[1,1.5,0],[null,2.5,0]]},"frameTable":{"schema":{"location":0,"category":1},"data":[[0,2],[1,2]]},"stackTable":{"schema":{"prefix":0,"frame":1},"data":[[null,0],[0,1]]},"stringTable":["root (in /lib/liba.so)","leaf (in /lib/liba.so)"]}],"processes":[],"pausedRanges":[]}"#.to_vec()
    }
    #[test]
    fn gecko_json_profiles_are_classified_and_imported() {
        let fixture = Fixture::new();
        let document = gecko_profile();
        let plain = fixture.root.join("perf_data.json");
        std::fs::write(&plain, &document).unwrap();
        assert_eq!(
            classify_cpu_source(&plain).unwrap(),
            CpuSourceClass::plain(CpuSourceKind::GeckoProfile)
        );
        let snapshot_root = fixture.root.join("cpu-artifacts");
        let loaded = load_cpu_import(
            &plain,
            CpuSourceClass::plain(CpuSourceKind::GeckoProfile),
            None,
            None,
            Some(&snapshot_root),
            || false,
        )
        .unwrap();
        assert_eq!(loaded.byte_length, document.len() as u64);
        assert!(loaded.display_label.ends_with("(Gecko profile JSON)"));
        assert_eq!(loaded.session.summary().sample_count, 2);
        assert_eq!(loaded.session.summary().thread_count, 1);
        assert_eq!(loaded.session.threads().len(), 1);
        assert_eq!(loaded.session.events(), ["samples"]);
        // Non-Gecko JSON stays an unclassified payload instead of a parse error.
        let other = fixture.root.join("other.json");
        std::fs::write(&other, br#"{"cells":[1,2,3]}"#).unwrap();
        assert_eq!(
            classify_cpu_source(&other).unwrap(),
            CpuSourceClass::plain(CpuSourceKind::Unknown)
        );
    }
    #[test]
    fn gzip_wrapped_gecko_profiles_import_and_reopen() {
        let fixture = Fixture::new();
        let document = gecko_profile();
        let archive = gzip_source(&fixture, "perf_data.json.gz", &document);
        let class = classify_cpu_source(&archive).unwrap();
        assert_eq!(class, CpuSourceClass::gzip(CpuSourceKind::GeckoProfile));
        let snapshot_root = fixture.root.join("cpu-artifacts");
        let loaded =
            load_cpu_import(&archive, class, None, None, Some(&snapshot_root), || false).unwrap();
        assert_eq!(
            loaded.digest,
            SourceDigest::hash_reader(Cursor::new(&document), 1024).unwrap()
        );
        assert_eq!(loaded.byte_length, document.len() as u64);
        assert!(
            loaded
                .display_label
                .starts_with("perf_data.json.gz (gzip) -> Gecko profile JSON")
        );
        let snapshot = cpu_snapshot_path(&snapshot_root, loaded.digest).unwrap();
        assert_eq!(std::fs::read(&snapshot).unwrap(), document);
        // The persisted snapshot is re-opened through the same Gecko reader.
        let binding =
            associate_cpu(&fixture.database(), loaded.digest, loaded.byte_length, None).unwrap();
        let mut page = recent_cpu(&fixture.database(), 0).unwrap();
        let recent = page.rows.remove(0);
        assert_eq!(recent.binding, binding);
        let reopened = load_cpu_import(
            &archive,
            class,
            None,
            Some(&recent),
            Some(&snapshot_root),
            || false,
        )
        .unwrap();
        assert_eq!(reopened.digest, loaded.digest);
        let snapshot_session = load_cpu_snapshot(&snapshot_root, &recent, || false).unwrap();
        assert_eq!(snapshot_session.session.summary().sample_count, 2);
    }
    #[test]
    fn cpu_sources_are_classified_by_content_not_by_name() {
        let fixture = Fixture::new();
        let plain: [(&[u8], CpuSourceKind); 6] = [
            (b"PERFILE2\x00\x01", CpuSourceKind::PerfData),
            (b"2ELIFREP\x00\x01", CpuSourceKind::PerfData),
            (b"PERFFILE\x00\x01", CpuSourceKind::PerfData),
            (b"SIMPLEPERF\x01\x00", CpuSourceKind::Simpleperf),
            (b"PK\x03\x04payload", CpuSourceKind::ZipArchive),
            (b"<!DOCTYPE html>", CpuSourceKind::Unknown),
        ];
        for (index, (bytes, expected)) in plain.into_iter().enumerate() {
            let path = fixture.root.join(format!("source-{index}"));
            std::fs::write(&path, bytes).unwrap();
            assert_eq!(
                classify_cpu_source(&path).unwrap(),
                CpuSourceClass::plain(expected),
                "case {index} starting with {bytes:?}"
            );
        }
        let empty = fixture.root.join("empty");
        std::fs::write(&empty, b"").unwrap();
        assert_eq!(
            classify_cpu_source(&empty).unwrap(),
            CpuSourceClass::plain(CpuSourceKind::Unknown)
        );
        // A gzip wrapper is classified by what it transports, not by its name.
        let wrapped: [(&[u8], CpuSourceKind); 3] = [
            (b"PERFILE2\x00\x01", CpuSourceKind::PerfData),
            (b"SIMPLEPERF\x01\x00", CpuSourceKind::Simpleperf),
            (b"<!DOCTYPE html>", CpuSourceKind::Unknown),
        ];
        for (index, (payload, expected)) in wrapped.into_iter().enumerate() {
            let path = fixture.root.join(format!("wrapped-{index}.gz"));
            std::fs::write(&path, gzip(payload)).unwrap();
            assert_eq!(
                classify_cpu_source(&path).unwrap(),
                CpuSourceClass::gzip(expected),
                "wrapped case {index} containing {payload:?}"
            );
        }
    }
    #[test]
    fn only_parseable_cpu_sources_skip_the_rejection_message() {
        for kind in [
            CpuSourceKind::PerfData,
            CpuSourceKind::Simpleperf,
            CpuSourceKind::GeckoProfile,
        ] {
            assert!(CpuSourceClass::plain(kind).rejection().is_none());
            assert!(CpuSourceClass::gzip(kind).rejection().is_none());
        }
        for kind in [CpuSourceKind::ZipArchive, CpuSourceKind::Unknown] {
            assert!(
                matches!(
                    CpuSourceClass::plain(kind).rejection(),
                    Some(CpuError::Local(_))
                ),
                "{kind:?} must carry an application resource key"
            );
        }
        // A gzip wrapper around anything else is still not a CPU profile.
        assert!(matches!(
            CpuSourceClass::gzip(CpuSourceKind::Unknown).rejection(),
            Some(CpuError::Local(TextKey::ProfilerCpuSourceUnknownFormat))
        ));
    }
    #[test]
    fn gzip_wrapped_simpleperf_imports_the_decompressed_profile() {
        let fixture = Fixture::new();
        let archive = gzip_source(&fixture, "cpu-profile.trace.gz", MINIMAL_SIMPLEPERF);
        let class = classify_cpu_source(&archive).unwrap();
        assert_eq!(class, CpuSourceClass::gzip(CpuSourceKind::Simpleperf));
        let snapshot_root = fixture.root.join("cpu-artifacts");
        let loaded =
            load_cpu_import(&archive, class, None, None, Some(&snapshot_root), || false).unwrap();
        // The durable identity is the profile inside the archive: re-picking the
        // archive and importing the extracted file both resolve to these bytes.
        assert_eq!(
            loaded.digest,
            SourceDigest::hash_reader(Cursor::new(MINIMAL_SIMPLEPERF), 1024).unwrap()
        );
        assert_eq!(loaded.byte_length, MINIMAL_SIMPLEPERF.len() as u64);
        assert!(
            loaded
                .display_label
                .starts_with("cpu-profile.trace.gz (gzip)")
        );
        let snapshot = cpu_snapshot_path(&snapshot_root, loaded.digest).unwrap();
        assert_eq!(std::fs::read(&snapshot).unwrap(), MINIMAL_SIMPLEPERF);

        let binding =
            associate_cpu(&fixture.database(), loaded.digest, loaded.byte_length, None).unwrap();
        let mut page = recent_cpu(&fixture.database(), 0).unwrap();
        let recent = page.rows.remove(0);
        assert_eq!(recent.binding, binding);
        let reopened = load_cpu_import(
            &archive,
            class,
            None,
            Some(&recent),
            Some(&snapshot_root),
            || false,
        )
        .unwrap();
        assert_eq!(reopened.digest, loaded.digest);
        assert_eq!(reopened.byte_length, loaded.byte_length);
    }
    #[cfg(unix)]
    fn fake_simpleperf(fixture: &Fixture) -> PathBuf {
        use std::os::unix::fs::PermissionsExt;
        let path = fixture.root.join("fake-simpleperf.sh");
        std::fs::write(
            &path,
            "#!/bin/sh\nout=\nwhile [ $# -gt 0 ]; do\n  if [ \"$1\" = \"-o\" ]; then out=\"$2\"; fi\n  shift\ndone\nprintf 'SIMPLEPERF' > \"$out\"\nprintf '\\001\\000\\000\\000\\000\\000' >> \"$out\"\n",
        )
        .unwrap();
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o755)).unwrap();
        path
    }
    #[cfg(unix)]
    #[test]
    fn gzip_wrapped_perf_data_converts_through_the_resolved_host_simpleperf() {
        let fixture = Fixture::new();
        let converter = fake_simpleperf(&fixture);
        let snapshot_root = fixture.root.join("cpu-artifacts");
        let archive = gzip_source(
            &fixture,
            "capture.perf.data.gz",
            b"PERFILE2\x00\x01\x00\x00\x00\x00\x00\x00",
        );
        let class = classify_cpu_source(&archive).unwrap();
        assert_eq!(class, CpuSourceClass::gzip(CpuSourceKind::PerfData));
        let loaded = load_cpu_import(
            &archive,
            class,
            Some(&converter),
            None,
            Some(&snapshot_root),
            || false,
        )
        .unwrap();
        // The durable identity is the converted SIMPLEPERF stream, exactly as a
        // plain perf.data pick would produce it.
        assert_eq!(
            loaded.digest,
            SourceDigest::hash_reader(Cursor::new(MINIMAL_SIMPLEPERF), 1024).unwrap()
        );
        assert_eq!(loaded.byte_length, MINIMAL_SIMPLEPERF.len() as u64);
        assert!(
            loaded
                .display_label
                .starts_with("capture.perf.data.gz (gzip) -> SIMPLEPERF protobuf via")
        );
    }
    #[test]
    fn corrupt_gzip_wrappers_are_reported_as_decompression_failures() {
        let fixture = Fixture::new();
        let path = fixture.root.join("corrupt.gz");
        std::fs::write(&path, [0x1f, 0x8b, 0x08, 0x00, 0x00]).unwrap();
        assert!(matches!(
            classify_cpu_source(&path),
            Err(CpuError::Local(
                TextKey::ProfilerCpuSourceGzipDecompressionFailed
            ))
        ));
        let root = fixture.root.join("cpu-artifacts");
        assert!(matches!(
            decompress_cpu_source(&path, &root, 1024, || false),
            Err(CpuError::Local(
                TextKey::ProfilerCpuSourceGzipDecompressionFailed
            ))
        ));
    }
    #[test]
    fn decompression_is_bounded_and_cancellable() {
        let fixture = Fixture::new();
        let archive = gzip_source(&fixture, "large.gz", &[0u8; 4096]);
        let root = fixture.root.join("cpu-artifacts");
        assert!(matches!(
            decompress_cpu_source(&archive, &root, 1024, || false),
            Err(CpuError::Local(TextKey::ProfilerCpuSourceGzipTooLarge))
        ));
        assert!(matches!(
            decompress_cpu_source(&archive, &root, 8192, || true),
            Err(CpuError::Local(TextKey::ProfilerCpuImportClosed))
        ));
    }
    #[test]
    #[test]
    fn chart_tooltip_placement_flips_to_the_visible_side() {
        let size = (280.0, 120.0);
        let viewport = (800.0, 600.0);
        // Room on the right and below: the preferred side keeps the pointer clear.
        assert_eq!(
            chart_tooltip_placement((100.0, 100.0), size, viewport),
            (112.0, 112.0)
        );
        // Near the right edge the card flips to the left of the pointer.
        assert_eq!(
            chart_tooltip_placement((700.0, 100.0), size, viewport),
            (112.0, 700.0 - 12.0 - 280.0)
        );
        // Near the bottom edge the card flips above the pointer.
        assert_eq!(
            chart_tooltip_placement((100.0, 550.0), size, viewport),
            (550.0 - 12.0 - 120.0, 112.0)
        );
        // A viewport that cannot fit either side clamps the card inside it.
        assert_eq!(
            chart_tooltip_placement((50.0, 50.0), size, (300.0, 200.0)),
            (62.0, 14.0)
        );
    }
    #[test]
    fn follow_tooltip_uses_the_chart_viewport_not_the_content_width() {
        let source = include_str!("cpu.rs");
        assert!(source.contains("fn chart_tooltip_placement("));
        assert!(source.contains("fn chart_viewport_size("));
        assert!(source.contains(".track_scroll(&ws.chart_scroll)"));
        assert!(
            source.contains("chart_tooltip(ws, block, colors, ws.chart_hover_position, viewport)")
        );
    }
    #[test]
    fn timeline_plot_click_does_not_filter_threads() {
        let source = include_str!("cpu.rs");
        let timeline = source
            .split("fn timeline_panel(")
            .nth(1)
            .unwrap()
            .split("fn time_range_fractions(")
            .next()
            .unwrap();
        assert_eq!(
            timeline.matches("select_thread_filter(thread_id").count(),
            1
        );
        assert!(source.contains("const CHART_WIDTH_PX: f32 = 1024.0;"));
    }
    #[test]
    fn call_tree_panel_scrolls_both_axes() {
        // The call tree used to share a scroll handle whose scroll container was
        // never wired, so neither wheel nor trackpad could move it. One horizontal
        // scroller now owns the header and rows together, the rows keep their own
        // vertical virtual list, and the content width is the longest row.
        let source = include_str!("cpu.rs");
        assert!(source.contains("cpu-call-tree-hscroll"));
        assert!(source.contains(".track_scroll(&ws.tree_h_scroll)"));
        assert!(source.contains("cpu-call-tree-scrollbar-vertical"));
        assert!(source.contains("cpu-call-tree-scrollbar-horizontal"));
        assert!(source.contains("min_w(px(content_width))"));
        assert!(source.contains("fn call_tree_content_width"));
        assert!(source.contains("restrict_scroll_to_axis"));
    }
    #[test]
    fn unclassified_bytes_still_reach_the_engine_diagnostic_without_precheck() {
        // Documents why open_path classifies first: the engine only reports the
        // low-level magic failure, which says nothing about what to import.
        let fixture = Fixture::new();
        let path = fixture.root.join("not-a-profile");
        std::fs::write(&path, b"<!DOCTYPE html>").unwrap();
        let error = match load_cpu_source(&path, None, || false) {
            Ok(_) => panic!("unclassified bytes must not parse"),
            Err(error) => error,
        };
        assert_eq!(error.display(Language::English), "invalid SIMPLEPERF magic");
    }
}

/// In-window menu bar commands for the Cpu workspace. Each screen publishes
/// only the commands its own functionality supports.
#[derive(Clone, PartialEq, Eq)]
pub(crate) enum CpuMenuCommand {
    /// Open the active route's file picker (Compose File > Open…, Cmd+O).
    OpenFile,
    /// Open the CPU page's folder picker (Compose File > Open Folder…,
    /// Cmd+Shift+O).
    OpenFolder,
    /// Reopen the saved session an Open Recent row names.
    OpenRecent(RecentCpu),
    /// Save one export format of the open session.
    Export(ExportKind),
    /// Save the Firefox profiler JSON of the open session.
    ExportFirefox,
    /// Open one capture-configuration section (Compose Tools > Configuration).
    Configuration(u8),
    /// Shell Settings page. macOS reaches Settings through the application menu,
    /// so this row only exists on the other platforms.
    #[cfg(not(target_os = "macos"))]
    OpenSettings,
    /// Layout row whose capability this page does not implement; stays disabled.
    Unavailable,
    /// Reload the bounded saved-session catalog page.
    RefreshRecentMetadata,
    /// Pick the host simpleperf used to convert perf.data.
    SelectSimpleperf,
    /// Copy the active call-tree / top-functions page as CSV.
    CopyCsv,
    /// Copy the selected row or chart block's symbol.
    CopySymbol,
    /// Drop the parsed session and every published projection.
    CloseSession,
    /// Focus the stack filter input.
    FocusSearch,
    /// Expand every call-tree row.
    ExpandAll,
    /// Collapse every call-tree row that has children.
    CollapseAll,
    /// Show or hide the details pane.
    ToggleDetails,
    /// Clear the sample-time range filter.
    ResetTimeRange,
    /// Clear the flame-graph zoom.
    ResetZoom,
    /// Clear every applied transform.
    ResetTransforms,
    /// Open the indexed source of the selected function.
    OpenSource,
    /// Disassemble the selected function.
    Disassemble,
    /// Pick the folder that holds the symbols/binary_cache artifacts.
    SelectBinaryFolder,
    /// Copy the rendered disassembly lines.
    CopyDisassembly,
    /// Close the disassembly panel.
    CloseDisassembly,
}

/// Symbol and library of the row or chart block the user selected, for menu
/// commands that act on the current selection. Mirrors the details pane, which
/// reads the tree row, the top-functions row or the chart block per tab.
fn cpu_selected_function(ws: &CpuWorkspace) -> Option<(String, String)> {
    let selected = ws.row_selected.as_deref();
    if let Some(tree) = ws.tree.as_ref()
        && let Some(row) = tree
            .rows
            .iter()
            .find(|row| Some(row.id.as_str()) == selected)
    {
        return Some((row.name.clone(), row.resource.clone()));
    }
    if let Some(page) = ws.top.as_ref()
        && let Some(row) = page
            .rows
            .iter()
            .find(|row| Some(row.id.as_str()) == selected)
    {
        return Some((row.name.clone(), row.resource.clone()));
    }
    selected_chart_block(ws).map(|block| (block.label.clone(), block.resource.clone()))
}

impl crate::shell::menubar::MenuHost for CpuWorkspace {
    type Command = CpuMenuCommand;

    fn menu_groups(
        &self,
        _language: Language,
    ) -> Vec<crate::shell::menubar::MenuGroup<Self::Command>> {
        vec![
            cpu_file_menu(self),
            cpu_view_menu(self),
            cpu_tools_menu(self),
        ]
    }

    fn run_menu_command(
        &mut self,
        command: Self::Command,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        match command {
            // Shell rows run their bound action, so the native menu and the
            // fallback strip share one implementation. The window defers the
            // dispatch, so this never re-enters the shell update.
            CpuMenuCommand::OpenFile => {
                window.dispatch_action(Box::new(crate::shell::OpenActiveRouteFile), cx)
            }
            CpuMenuCommand::OpenFolder => {
                window.dispatch_action(Box::new(crate::shell::OpenFolderActiveRoute), cx)
            }
            CpuMenuCommand::OpenRecent(recent) => self.open_recent(recent, cx),
            CpuMenuCommand::Export(kind) => self.save_export(kind, cx),
            CpuMenuCommand::ExportFirefox => self.save_firefox(cx),
            CpuMenuCommand::Configuration(section) => {
                window.dispatch_action(Box::new(crate::shell::OpenCaptureSettings { section }), cx)
            }
            #[cfg(not(target_os = "macos"))]
            CpuMenuCommand::OpenSettings => {
                window.dispatch_action(Box::new(crate::shell::OpenSettings), cx)
            }
            CpuMenuCommand::Unavailable => {}
            CpuMenuCommand::RefreshRecentMetadata => self.refresh_recent(0, cx),
            CpuMenuCommand::SelectSimpleperf => self.pick_converter(cx),
            CpuMenuCommand::CopyCsv => self.copy_csv(cx),
            CpuMenuCommand::CopySymbol => {
                if let Some((symbol, _)) = cpu_selected_function(self) {
                    self.copy_symbol(symbol, cx);
                }
            }
            CpuMenuCommand::CloseSession => self.close(cx),
            CpuMenuCommand::FocusSearch => self.focus_search(window, cx),
            CpuMenuCommand::ExpandAll => self.expand_all(cx),
            CpuMenuCommand::CollapseAll => self.collapse_all(cx),
            CpuMenuCommand::ToggleDetails => self.set_details_visible(!self.details_visible, cx),
            CpuMenuCommand::ResetTimeRange => self.reset_time_range(cx),
            CpuMenuCommand::ResetZoom => self.reset_zoom(cx),
            CpuMenuCommand::ResetTransforms => self.reset_transforms(cx),
            CpuMenuCommand::OpenSource => {
                if let Some((symbol, resource)) = cpu_selected_function(self) {
                    self.open_source(symbol, resource, window, cx);
                }
            }
            CpuMenuCommand::Disassemble => {
                if let Some((symbol, resource)) = cpu_selected_function(self) {
                    self.disassemble(symbol, resource, cx);
                }
            }
            CpuMenuCommand::SelectBinaryFolder => self.pick_binary_root(cx),
            CpuMenuCommand::CopyDisassembly => self.copy_disassembly(cx),
            CpuMenuCommand::CloseDisassembly => self.close_disassembly(cx),
        }
    }
}

/// File menu of the CPU Profiler page: Compose SimpleperfFileMenu publishes
/// Open…, Open Folder…, Open Recent, the grouped Export rows and Close session in
/// that order, and reaches Settings outside macOS.
fn cpu_file_menu(ws: &CpuWorkspace) -> MenuGroup<CpuMenuCommand> {
    let busy = ws.busy;
    let can_export = !busy && ws.session.is_some();
    // The saved-session catalog is the page's recent list. Each row carries the
    // session it names, so a catalog refresh between menu build and click cannot
    // reopen a different snapshot.
    let recent = ws
        .recent
        .as_ref()
        .map_or(&[][..], |page| page.rows.as_slice());
    let file = MenuGroup::new(TextKey::ShellMenuFile)
        .item_action(
            TextKey::ShellMenuOpen,
            CpuMenuCommand::OpenFile,
            crate::shell::OpenActiveRouteFile,
        )
        .item_action_when(
            !busy,
            TextKey::CpuMenuOpenFolder,
            CpuMenuCommand::OpenFolder,
            crate::shell::OpenFolderActiveRoute,
        )
        .item_action_when(
            !busy && recent.first().is_some(),
            TextKey::ShellMenuRecentFiles,
            recent
                .first()
                .map(|row| CpuMenuCommand::OpenRecent(row.clone()))
                .unwrap_or(CpuMenuCommand::Unavailable),
            crate::shell::RecentFilesActiveRoute,
        )
        .submenu(TextKey::ShellMenuOpenRecent, true, |menu| {
            if recent.is_empty() {
                return menu.item_when(
                    false,
                    TextKey::ShellMenuNoRecent,
                    CpuMenuCommand::Unavailable,
                );
            }
            let menu = recent.iter().fold(menu, |menu, row| {
                menu.item_when(
                    !busy,
                    cpu_label(row.binding.source_digest),
                    CpuMenuCommand::OpenRecent(row.clone()),
                )
            });
            // Rust keeps no recent-path list of its own: these rows come from the
            // shared session catalog, so clearing them would delete saved
            // sessions. The row keeps Compose's shape and stays disabled.
            menu.separator().item_when(
                false,
                TextKey::ShellMenuClearRecent,
                CpuMenuCommand::Unavailable,
            )
        })
        .separator()
        .submenu(TextKey::ShellMenuExport, true, |menu| {
            menu.item_action_when(
                can_export,
                TextKey::CpuWorkspaceExportReportJson,
                CpuMenuCommand::Export(ExportKind::ReportJson),
                crate::shell::ExportActiveRoute,
            )
            .item_when(
                can_export,
                TextKey::CpuWorkspaceExportSessionPackage,
                CpuMenuCommand::Export(ExportKind::SessionPackage),
            )
            .item_when(
                false,
                TextKey::CpuWorkspaceExportScreenshot,
                CpuMenuCommand::Unavailable,
            )
            .separator()
            .item_when(
                false,
                TextKey::CpuWorkspaceExportRawProtobuf,
                CpuMenuCommand::Unavailable,
            )
            .item_when(
                can_export,
                TextKey::CpuExportFirefoxProfile,
                CpuMenuCommand::ExportFirefox,
            )
            .item_when(
                can_export,
                TextKey::CpuWorkspaceExportFolded,
                CpuMenuCommand::Export(ExportKind::Folded),
            )
            .item_when(
                can_export,
                TextKey::CpuWorkspaceExportPprof,
                CpuMenuCommand::Export(ExportKind::Pprof),
            )
            .separator()
            .item_when(
                false,
                TextKey::CpuWorkspaceExportSimpleperfReport,
                CpuMenuCommand::Unavailable,
            )
            .item_when(
                can_export,
                TextKey::CpuWorkspaceExportHtml,
                CpuMenuCommand::Export(ExportKind::Html),
            )
            .item_when(
                false,
                TextKey::CpuWorkspaceExportOpenExternally,
                CpuMenuCommand::Unavailable,
            )
        })
        .separator()
        .item_action_when(
            !busy && ws.catalog.is_some(),
            TextKey::CpuWorkspaceCloseSession,
            CpuMenuCommand::CloseSession,
            crate::shell::CloseActiveRoute,
        );
    // macOS reaches Settings through the application menu, matching Compose.
    #[cfg(not(target_os = "macos"))]
    let file = file.separator().item_action(
        TextKey::ShellMenuSettings,
        CpuMenuCommand::OpenSettings,
        crate::shell::OpenSettings,
    );
    file
}

/// View menu of the CPU page: the page's existing search, projection, details
/// and range/zoom/transform rows, which Compose publishes for this screen.
fn cpu_view_menu(ws: &CpuWorkspace) -> MenuGroup<CpuMenuCommand> {
    let busy = ws.busy;
    let has_tree = ws.tab == CpuTab::CallTree && ws.tree.is_some();
    let has_range = ws.query.start.is_some() || ws.query.end.is_some();
    let has_zoom = ws.zoom_start > 0.0 || ws.zoom_end < 1.0;
    MenuGroup::new(TextKey::ShellMenuView)
        .item_action(
            TextKey::CpuWorkspaceFocusSearch,
            CpuMenuCommand::FocusSearch,
            crate::shell::FocusActiveSearch,
        )
        .separator()
        .item_when(
            !busy && has_tree,
            TextKey::CpuWorkspaceExpandAll,
            CpuMenuCommand::ExpandAll,
        )
        .item_when(
            !busy && has_tree,
            TextKey::CpuWorkspaceCollapseAll,
            CpuMenuCommand::CollapseAll,
        )
        .separator()
        .check(
            TextKey::CpuDetailsDetails,
            ws.details_visible,
            CpuMenuCommand::ToggleDetails,
        )
        .separator()
        .item_when(
            !busy && has_range,
            TextKey::CpuWorkspaceResetRange,
            CpuMenuCommand::ResetTimeRange,
        )
        .item_when(
            !busy && has_zoom,
            TextKey::CpuWorkspaceResetZoom,
            CpuMenuCommand::ResetZoom,
        )
        .item_when(
            !ws.transforms.is_empty(),
            TextKey::CpuTransformReset,
            CpuMenuCommand::ResetTransforms,
        )
}

/// Tools menu of the CPU page: Compose's capture Configuration submenu plus the
/// page tools this screen can really perform (source/disassembly/binary folder
/// and the clipboard/converter actions the toolbar already offers).
fn cpu_tools_menu(ws: &CpuWorkspace) -> MenuGroup<CpuMenuCommand> {
    let busy = ws.busy;
    let has_symbol = cpu_selected_function(ws).is_some_and(|(symbol, _)| !symbol.trim().is_empty());
    let has_csv = matches!(ws.tab, CpuTab::CallTree | CpuTab::TopFunctions);
    let has_disassembly = ws
        .disassembly
        .as_ref()
        .is_some_and(|panel| !panel.lines.is_empty());
    MenuGroup::new(TextKey::ShellMenuTools)
        .submenu(TextKey::CommonConfiguration, true, |menu| {
            menu.item(
                TextKey::SettingsUiTemplates,
                CpuMenuCommand::Configuration(0),
            )
            .item(TextKey::SettingsUiCapture, CpuMenuCommand::Configuration(1))
            .item(
                TextKey::SettingsUiAdvanced,
                CpuMenuCommand::Configuration(2),
            )
        })
        .separator()
        .item_action_when(
            has_symbol && ws.studio.is_some(),
            TextKey::CpuDetailsOpenSource,
            CpuMenuCommand::OpenSource,
            crate::shell::GoToSourceActiveRoute,
        )
        .item_when(
            has_symbol,
            TextKey::CpuDisassemble,
            CpuMenuCommand::Disassemble,
        )
        .item_when(
            !busy,
            TextKey::CpuSelectBinaryFolder,
            CpuMenuCommand::SelectBinaryFolder,
        )
        .item_when(
            has_disassembly,
            TextKey::CpuCopyDisassembly,
            CpuMenuCommand::CopyDisassembly,
        )
        .item_when(
            ws.disassembly.is_some() || ws.disassembly_busy,
            TextKey::CpuCloseDisassembly,
            CpuMenuCommand::CloseDisassembly,
        )
        .separator()
        .item_when(
            !busy && has_csv && ws.session.is_some(),
            TextKey::CpuCopyCurrentQueryCsv,
            CpuMenuCommand::CopyCsv,
        )
        .item_when(
            !busy && has_symbol,
            TextKey::CpuWorkspaceCopySymbol,
            CpuMenuCommand::CopySymbol,
        )
        .item_when(
            !busy,
            TextKey::CpuSelectSimpleperf,
            CpuMenuCommand::SelectSimpleperf,
        )
        .item_when(
            !busy && !ws.recent_busy && ws.metadata_path.is_some(),
            TextKey::CpuRefreshRecentMetadata,
            CpuMenuCommand::RefreshRecentMetadata,
        )
}

/// Route-shortcut entry points (IDEA-style window shortcuts). These reuse the
/// page's existing commands so the menu rows and the shortcuts share one path.
impl CpuWorkspace {
    pub(crate) fn open_most_recent_session(&mut self, cx: &mut Context<Self>) {
        let recent = self
            .recent
            .as_ref()
            .and_then(|page| page.rows.first())
            .cloned();
        if let Some(recent) = recent {
            self.open_recent(recent, cx);
        }
    }

    pub(crate) fn close_primary(&mut self, cx: &mut Context<Self>) {
        self.close(cx);
    }

    pub(crate) fn open_selected_source_primary(
        &mut self,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if let Some((symbol, resource)) = cpu_selected_function(self) {
            self.open_source(symbol, resource, window, cx);
        }
    }
}

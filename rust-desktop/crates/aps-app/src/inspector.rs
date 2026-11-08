use aps_layout_inspector::{
    Bounds as LayoutBounds, ComposeDetailEvidence, ComposeEvidence, ComposeValueEvidence,
    DisplayInfo, EdgeInsets, InspectorArchive, InspectorProtocolFrame, LayoutNode, LayoutWindow,
    NodeKind,
};
use aps_platform::CancellationToken;
use aps_ui_foundation::{Language, LayoutPreferences, TextKey};
use gpui_kit::{
    base::TextSelection,
    component::{
        Disableable, IconName, Selectable,
        button::{Button, ButtonVariants},
        input::{Input, InputState},
        menu::{ContextMenuExt, DropdownMenu, PopupMenuItem},
        scroll::{ScrollableElement, Scrollbar, ScrollbarAxis, ScrollbarMode},
    },
    prelude::*,
    *,
};
use std::{
    collections::BTreeSet,
    path::PathBuf,
    sync::{Arc, Mutex},
};

use crate::studio::{self, StudioColors};

const MAX_CANVAS_NODES: usize = 4000;

// Compose LayoutInspectorMainPage metrics (dp), mirrored so the Rust page keeps
// the same dense layout contract.
const HEADER_TOOLBAR_HEIGHT: f32 = 40.0;
const PANEL_HEADER_HEIGHT: f32 = 29.0;
const STATUS_HEIGHT: f32 = 29.0;
const SPLITTER: f32 = 7.0;
const FINDINGS_HEIGHT: f32 = 240.0;
const HIERARCHY_WIDTH: f32 = 320.0;
const PROPERTIES_WIDTH: f32 = 300.0;
// Compose PaneLayout/FindingsLayout drag bounds.
const HIERARCHY_MIN_WIDTH: f32 = 180.0;
const HIERARCHY_MAX_WIDTH: f32 = 640.0;
const PROPERTIES_MIN_WIDTH: f32 = 200.0;
const PROPERTIES_MAX_WIDTH: f32 = 600.0;
const CANVAS_MIN_WIDTH: f32 = 320.0;
const FINDINGS_MIN_HEIGHT: f32 = 56.0;
const FINDINGS_MAX_RATIO: f32 = 0.5;
const HIERARCHY_ROW_HEIGHT: f32 = 20.0;
const HIERARCHY_INDENT: f32 = 14.0;
const DETAIL_LABEL_WIDTH: f32 = 108.0;
const TOOLBAR_BUTTON_HEIGHT: f32 = 24.0;
const TOOLBAR_BUTTON_RADIUS: f32 = 4.0;
// Compose PreviewZoomState: 50%–250% in 25% steps.
const MIN_ZOOM: f32 = 0.5;
const MAX_ZOOM: f32 = 2.5;
const ZOOM_STEP: f32 = 0.25;

const PREVIEW_SURFACE_DARK: u32 = 0x2c2c2e;
const PREVIEW_SURFACE_LIGHT: u32 = 0xffffff;

/// Compose `CaptureTargetMode`: the offline archive adapter has no live
/// capture, so the selection stays presentation state and never starts a
/// device operation.
#[derive(Clone, Copy, PartialEq, Eq)]
enum CaptureTargetMode {
    ForegroundApp,
    SystemUi,
}

impl CaptureTargetMode {
    const ALL: [Self; 2] = [Self::ForegroundApp, Self::SystemUi];

    fn label(self, language: Language) -> &'static str {
        match self {
            Self::ForegroundApp => language.text(TextKey::InspectorCaptureForeground),
            Self::SystemUi => language.text(TextKey::InspectorCaptureSystemUi),
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum DetailsTab {
    Overview,
    Layout,
    Drawing,
    Accessibility,
    Compose,
    Evidence,
}

impl DetailsTab {
    const ALL: [Self; 6] = [
        Self::Overview,
        Self::Layout,
        Self::Drawing,
        Self::Accessibility,
        Self::Compose,
        Self::Evidence,
    ];

    fn label(self, language: Language) -> &'static str {
        match self {
            Self::Overview => language.text(TextKey::InspectorDetailsOverview),
            Self::Layout => language.text(TextKey::InspectorDetailsLayout),
            Self::Drawing => language.text(TextKey::InspectorDetailsDrawing),
            Self::Accessibility => language.text(TextKey::InspectorDetailsAccessibility),
            Self::Compose => language.text(TextKey::InspectorDetailsCompose),
            Self::Evidence => language.text(TextKey::InspectorDetailsEvidence),
        }
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum FindingsTone {
    All,
    Info,
    Warning,
    Error,
}

impl FindingsTone {
    const ALL: [Self; 4] = [Self::All, Self::Info, Self::Warning, Self::Error];

    fn label(self, language: Language) -> String {
        let count = 0_usize;
        match self {
            Self::All => language.format(TextKey::InspectorFindingsAll, &[("count", &count)]),
            Self::Info => language.format(TextKey::InspectorFindingsInfo, &[("count", &count)]),
            Self::Warning => {
                language.format(TextKey::InspectorFindingsWarning, &[("count", &count)])
            }
            Self::Error => language.format(TextKey::InspectorFindingsError, &[("count", &count)]),
        }
    }

    fn color(self, colors: &StudioColors) -> Rgba {
        match self {
            Self::All => colors.accent,
            Self::Info => colors.accent,
            Self::Warning => colors.warning,
            Self::Error => colors.error,
        }
    }
}

/// Shared preference changes from the preview toolbar are persisted by the shell.
#[derive(Clone, Copy)]
pub struct InspectorPreferencesChanged(pub LayoutPreferences);
impl EventEmitter<InspectorPreferencesChanged> for InspectorWorkspace {}

/// Pane visibility changed; the shell mirrors it in the native View menu.
pub struct InspectorPanelsChanged;
impl EventEmitter<InspectorPanelsChanged> for InspectorWorkspace {}

/// One pane splitter drag; deltas are applied against the start sizes so a
/// clamped pane never accumulates rounding drift (Compose PaneLayout drags).
#[derive(Clone, Copy, PartialEq, Eq)]
enum PaneDragKind {
    Hierarchy,
    Properties,
    Findings,
}

#[derive(Clone, Copy)]
struct PreviewDrag {
    start_x: f32,
    start_y: f32,
    start_pan: (f32, f32),
    moved: bool,
}

/// Viewport/content geometry captured while the canvas paints, so wheel, drag
/// and scrollbar handlers share one clamping model (Compose
/// PreviewPanState.clamp).
#[derive(Clone, Copy)]
struct PreviewMetrics {
    viewport: Bounds<Pixels>,
    source: PreviewSource,
    base_scale: f32,
    origin: Point<Pixels>,
    scale: f32,
    pan: (f32, f32),
    content_w: f32,
    content_h: f32,
    zoom: f32,
}

#[derive(Clone, Copy)]
struct CanvasFrame {
    origin: Point<Pixels>,
    width: f32,
    height: f32,
    scale: f32,
    pan: (f32, f32),
    viewport: Bounds<Pixels>,
}

fn preview_pan_limits(content_w: f32, content_h: f32, vw: f32, vh: f32) -> (f32, f32) {
    (
        ((content_w - vw) / 2.0).max(0.0),
        ((content_h - vh) / 2.0).max(0.0),
    )
}

fn preview_clamp_pan(
    pan: (f32, f32),
    content_w: f32,
    content_h: f32,
    vw: f32,
    vh: f32,
) -> (f32, f32) {
    let (limit_x, limit_y) = preview_pan_limits(content_w, content_h, vw, vh);
    (
        if limit_x == 0.0 {
            0.0
        } else {
            pan.0.clamp(-limit_x, limit_x)
        },
        if limit_y == 0.0 {
            0.0
        } else {
            pan.1.clamp(-limit_y, limit_y)
        },
    )
}

fn paint_preview_scrollbars(
    window: &mut Window,
    viewport: Bounds<Pixels>,
    vw: f32,
    vh: f32,
    content_w: f32,
    content_h: f32,
    pan: (f32, f32),
    track: Rgba,
    thumb: Rgba,
) {
    let track_w = (vw - 20.0).max(1.0);
    let track_x = viewport.origin.x + px(6.0);
    let bar_y = viewport.origin.y + viewport.size.height - px(5.0);
    window.paint_quad(quad(
        Bounds {
            origin: point(track_x, bar_y),
            size: size(px(track_w), px(2.0)),
        },
        px(1.0),
        track,
        px(0.0),
        transparent_black(),
        Default::default(),
    ));
    let limit_x = ((content_w - vw) / 2.0).max(0.0);
    let thumb_w = if limit_x <= 0.0 {
        track_w
    } else {
        (vw / content_w.max(1.0) * track_w).clamp(24.0, track_w)
    };
    let fraction_x = if limit_x <= 0.0 {
        0.0
    } else {
        ((pan.0 + limit_x) / (2.0 * limit_x)).clamp(0.0, 1.0)
    };
    window.paint_quad(quad(
        Bounds {
            origin: point(
                track_x + px(fraction_x * (track_w - thumb_w)),
                bar_y - px(1.0),
            ),
            size: size(px(thumb_w), px(4.0)),
        },
        px(2.0),
        thumb,
        px(0.0),
        transparent_black(),
        Default::default(),
    ));
    let track_h = (vh - 20.0).max(1.0);
    let track_y = viewport.origin.y + px(6.0);
    let bar_x = viewport.origin.x + viewport.size.width - px(5.0);
    window.paint_quad(quad(
        Bounds {
            origin: point(bar_x, track_y),
            size: size(px(2.0), px(track_h)),
        },
        px(1.0),
        track,
        px(0.0),
        transparent_black(),
        Default::default(),
    ));
    let limit_y = ((content_h - vh) / 2.0).max(0.0);
    let thumb_h = if limit_y <= 0.0 {
        track_h
    } else {
        (vh / content_h.max(1.0) * track_h).clamp(24.0, track_h)
    };
    let fraction_y = if limit_y <= 0.0 {
        0.0
    } else {
        ((pan.1 + limit_y) / (2.0 * limit_y)).clamp(0.0, 1.0)
    };
    window.paint_quad(quad(
        Bounds {
            origin: point(
                bar_x - px(1.0),
                track_y + px(fraction_y * (track_h - thumb_h)),
            ),
            size: size(px(4.0), px(thumb_h)),
        },
        px(2.0),
        thumb,
        px(0.0),
        transparent_black(),
        Default::default(),
    ));
}

#[derive(Clone, Copy)]
struct PaneDrag {
    kind: PaneDragKind,
    start_x: f32,
    start_y: f32,
    start_hierarchy: f32,
    start_properties: f32,
    start_findings: f32,
    available_width: f32,
    available_height: f32,
}

/// Pane visibility projected for the shell-owned native menu.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct InspectorPanelVisibility {
    pub hierarchy: bool,
    pub findings: bool,
    pub details: bool,
}

pub struct InspectorWorkspace {
    language: Language,
    preferences: LayoutPreferences,
    hovered: Option<String>,
    archive: Option<Arc<InspectorArchive>>,
    window: usize,
    selected: Option<String>,
    hidden: BTreeSet<String>,
    collapsed: BTreeSet<String>,
    search: Entity<InputState>,
    show_hierarchy: bool,
    show_findings: bool,
    show_details: bool,
    details_tab: DetailsTab,
    findings_tone: FindingsTone,
    zoom: f32,
    show_bounds: bool,
    busy: bool,
    error: Option<String>,
    generation: u64,
    cancel: CancellationToken,
    studio: Option<WeakEntity<crate::shell::Studio>>,
    target_serial: Option<String>,
    target_pid: Option<u32>,
    capture_target: CaptureTargetMode,
    /// Compose `CanvasPane` local state: crop the preview to the app window
    /// instead of the full display. Presentation-only; never persisted.
    app_only: bool,
    screenshot_image: Option<Arc<RenderImage>>,
    /// Compose Agent opt-in, mirroring the Compose toolbar checkbox. Offline
    /// archives have no live injection; checking it reveals the Compose
    /// inspection sidecar (Compose category/summary) captured in the archive.
    compose_agent: bool,
    /// Resizable pane sizes, mirroring Compose's remembered pane widths and
    /// findings height. Session state only, like Compose's remember state.
    hierarchy_width: f32,
    properties_width: f32,
    findings_height: f32,
    pane_drag: Option<PaneDrag>,
    hierarchy_scroll: UniformListScrollHandle,
    /// Preview pan offset from the centred content, in window pixels
    /// (Compose PreviewPanState).
    preview_pan: (f32, f32),
    /// Canvas drag/selection state: a left press pans the canvas and only
    /// selects on release when the pointer did not move.
    preview_drag: Option<PreviewDrag>,
    preview_bar_drag: Option<bool>,
    preview_metrics: Arc<Mutex<Option<PreviewMetrics>>>,
}

impl InspectorWorkspace {
    pub fn new(language: Language, window: &mut Window, cx: &mut Context<Self>) -> Self {
        let search = cx.new(|cx| {
            InputState::new(window, cx)
                .placeholder(language.text(TextKey::InspectorSearchNodesPlaceholder))
        });
        cx.observe(&search, |_, _, cx| cx.notify()).detach();
        Self {
            language,
            preferences: LayoutPreferences::default(),
            hovered: None,
            archive: None,
            window: 0,
            selected: None,
            hidden: BTreeSet::new(),
            collapsed: BTreeSet::new(),
            search,
            show_hierarchy: true,
            show_findings: true,
            show_details: true,
            details_tab: DetailsTab::Overview,
            findings_tone: FindingsTone::All,
            zoom: 1.0,
            show_bounds: true,
            busy: false,
            error: None,
            generation: 0,
            cancel: CancellationToken::new(),
            studio: None,
            target_serial: None,
            target_pid: None,
            capture_target: CaptureTargetMode::ForegroundApp,
            app_only: true,
            screenshot_image: None,
            compose_agent: false,
            hierarchy_width: HIERARCHY_WIDTH,
            properties_width: PROPERTIES_WIDTH,
            findings_height: FINDINGS_HEIGHT,
            pane_drag: None,
            hierarchy_scroll: UniformListScrollHandle::new(),
            preview_pan: (0.0, 0.0),
            preview_drag: None,
            preview_bar_drag: None,
            preview_metrics: Arc::new(Mutex::new(None)),
        }
    }

    /// Applies presentation-only preferences without changing immutable archive
    /// evidence, selection, or temporary hidden-layer state. Capture multipliers
    /// do not relax the import adapter's independent ZIP safety limits.
    pub fn apply_preferences(&mut self, preferences: LayoutPreferences, cx: &mut Context<Self>) {
        if self.preferences == preferences && self.show_bounds == preferences.show_visible_bounds {
            return;
        }
        self.preferences = preferences;
        self.show_bounds = preferences.show_visible_bounds;
        self.hovered = None;
        cx.notify();
    }

    pub fn set_target(&mut self, serial: Option<String>, pid: Option<u32>, cx: &mut Context<Self>) {
        self.target_serial = serial;
        self.target_pid = pid;
        cx.notify();
    }

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
        let placeholder = language.text(TextKey::InspectorSearchNodesPlaceholder);
        self.search.update(cx, |state, cx| {
            state.set_placeholder(placeholder, window, cx)
        });
        cx.notify();
    }

    /// Publishes one already-validated host protocol frame. Transport ownership
    /// stays outside the UI entity; this method only applies the generation-safe
    /// bounded projection to the existing Inspector canvas and panes.
    pub fn publish_protocol_frame(
        &mut self,
        frame: InspectorProtocolFrame,
        cx: &mut Context<Self>,
    ) -> Result<(), String> {
        if frame.generation < self.generation {
            return Err(self
                .language
                .text(TextKey::ProfilerInspectorStaleFrame)
                .into());
        }
        let window = frame
            .archive
            .windows
            .iter()
            .position(|window| window.id == frame.archive.default_window_id)
            .unwrap_or(0);
        self.generation = frame.generation;
        let screenshot_png = frame.archive.screenshot_png.clone();
        self.archive = Some(Arc::new(frame.archive));
        self.screenshot_image = screenshot_png
            .as_deref()
            .and_then(|bytes| decode_screenshot(bytes, cx));
        self.window = window;
        self.selected = None;
        self.hovered = None;
        self.hidden.clear();
        self.collapsed.clear();
        self.zoom = 1.0;
        self.busy = false;
        self.error = None;
        cx.notify();
        Ok(())
    }

    pub fn analysis_request(&self) -> Result<aps_ai::AnalysisRequest, String> {
        use crate::evidence::{clipped, object, request};
        if self.busy {
            return Err(self
                .language
                .text(TextKey::ProfilerInspectorWaitQuery)
                .into());
        }
        let archive = self
            .archive
            .as_ref()
            .ok_or_else(|| self.language.text(TextKey::ProfilerInspectorImportFirst))?;
        let window = archive.windows.get(self.window).ok_or_else(|| {
            self.language
                .text(TextKey::ProfilerInspectorWindowUnavailable)
        })?;
        let rows = window
            .nodes
            .iter()
            .skip(self.selected_index().unwrap_or(0))
            .take(12)
            .map(|node| {
                object([
                    ("id", clipped(&node.id, 256).into()),
                    ("kind", format!("{:?}", node.kind).into()),
                    ("class", clipped(&node.class_name, 512).into()),
                    ("depth", node.depth.into()),
                    ("visible", node.visible.into()),
                    ("alpha", node.alpha.into()),
                    (
                        "resourceName",
                        node.resource_name
                            .as_deref()
                            .map_or(aps_json::Value::Null, |value| clipped(value, 512).into()),
                    ),
                ])
            })
            .collect();
        request(
            "layout-inspector",
            "Current bounded Inspector hierarchy projection; runtime values and screenshot pixels are excluded.",
            &format!(
                "package={} window={} nodes={}",
                archive.frame.package_name,
                window.id,
                window.nodes.len()
            ),
            rows,
        )
    }

    fn selected_is_compose(&self) -> bool {
        self.archive
            .as_ref()
            .and_then(|archive| archive.windows.get(self.window))
            .and_then(|window| {
                self.selected
                    .as_ref()
                    .and_then(|id| window.nodes.iter().find(|node| &node.id == id))
            })
            .is_some_and(|node| node.kind == NodeKind::Compose)
    }

    fn selected_index(&self) -> Option<usize> {
        let archive = self.archive.as_ref()?;
        let window = archive.windows.get(self.window)?;
        let selected = self.selected.as_deref()?;
        window.nodes.iter().position(|node| node.id == selected)
    }

    fn node(&self, index: usize) -> Option<&LayoutNode> {
        self.archive
            .as_ref()?
            .windows
            .get(self.window)?
            .nodes
            .get(index)
    }

    pub fn pick(&mut self, cx: &mut Context<Self>) {
        if self.busy {
            return;
        }
        self.busy = true;
        self.generation += 1;
        let generation = self.generation;
        cx.notify();
        let picker = cx.prompt_for_paths(PathPromptOptions {
            files: true,
            directories: false,
            multiple: false,
            prompt: Some(
                self.language
                    .text(TextKey::InspectorImportInspectorArchive)
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
                        this.error =
                            Some(this.language.text(TextKey::ProfilerFilePickerFailed).into())
                    }
                    _ => {}
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Debug-only entry for native verification: opens a real archive without
    /// driving the platform file picker. Release builds never see this method.
    #[cfg(debug_assertions)]
    pub(crate) fn open_debug_archive(&mut self, path: PathBuf, cx: &mut Context<Self>) {
        self.open(path, cx);
    }

    fn open(&mut self, path: PathBuf, cx: &mut Context<Self>) {
        self.cancel.cancel();
        self.cancel = CancellationToken::new();
        let cancel = self.cancel.clone();
        self.generation += 1;
        let generation = self.generation;
        self.busy = true;
        self.error = None;
        let task = cx.background_executor().spawn(async move {
            InspectorArchive::open(&path, &cancel).map_err(|e| e.to_string())
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.generation != generation {
                    return;
                }
                this.busy = false;
                match result {
                    Ok(archive) => {
                        let _ = this.publish_protocol_frame(
                            InspectorProtocolFrame {
                                archive,
                                generation,
                            },
                            cx,
                        );
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

    pub fn cycle_window(&mut self, cx: &mut Context<Self>) {
        if let Some(archive) = &self.archive
            && !archive.windows.is_empty()
        {
            self.window = (self.window + 1) % archive.windows.len();
            self.selected = None;
            self.hovered = None;
            self.hidden.clear();
            self.collapsed.clear();
            self.zoom = 1.0;
            cx.notify();
        }
    }

    pub fn toggle_hierarchy(&mut self, cx: &mut Context<Self>) {
        self.show_hierarchy = !self.show_hierarchy;
        cx.emit(InspectorPanelsChanged);
        cx.notify();
    }

    pub fn toggle_findings(&mut self, cx: &mut Context<Self>) {
        self.show_findings = !self.show_findings;
        cx.emit(InspectorPanelsChanged);
        cx.notify();
    }

    pub fn toggle_details(&mut self, cx: &mut Context<Self>) {
        self.show_details = !self.show_details;
        cx.emit(InspectorPanelsChanged);
        cx.notify();
    }

    /// Pane visibility for the shell-owned native menu.
    pub fn panel_visibility(&self) -> InspectorPanelVisibility {
        InspectorPanelVisibility {
            hierarchy: self.show_hierarchy,
            findings: self.show_findings,
            details: self.show_details,
        }
    }

    fn set_capture_target(&mut self, mode: CaptureTargetMode, cx: &mut Context<Self>) {
        if self.capture_target != mode {
            self.capture_target = mode;
            cx.notify();
        }
    }

    fn header_toolbar(
        &self,
        language: Language,
        colors: &StudioColors,
        archive: Option<&Arc<InspectorArchive>>,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let window_count = archive.map_or(0, |archive| archive.windows.len());
        let window_label = archive
            .and_then(|archive| archive.windows.get(self.window))
            .map(|window| format!("{}: {}", language.text(TextKey::InspectorWindow), window.id));
        // Compose order: capture target, optional window selector, status, then the
        // right-aligned scan control immediately before the trailing divider.
        let mut controls = div()
            .id("inspector-header-controls")
            .flex_1()
            .min_w_0()
            .flex()
            .items_center()
            .gap(px(8.))
            .overflow_x_scroll()
            .child(capture_target_control(
                language,
                colors,
                self.capture_target,
                cx,
            ));
        if let Some(window_label) = window_label.filter(|_| window_count > 1) {
            controls = controls.child(
                toolbar_button("inspector-window", window_label, !self.busy, false, colors)
                    .flex_shrink_0()
                    .on_click(cx.listener(|this, _, _, cx| this.cycle_window(cx))),
            );
        }
        controls = controls
            .child(header_divider(colors))
            .child(
                div()
                    .h(px(7.))
                    .w(px(7.))
                    .flex_shrink_0()
                    .rounded_full()
                    .bg(colors.warning),
            )
            .child(
                div()
                    .flex_shrink_0()
                    .text_size(px(studio::FONT_SUPPORTING))
                    .text_color(colors.warning)
                    .whitespace_nowrap()
                    .child(if archive.is_some() {
                        language.text(TextKey::InspectorOfflineArchive).to_owned()
                    } else if let Some(serial) = &self.target_serial {
                        language.format(
                            TextKey::InspectorDeviceLinked,
                            &[
                                ("serial", serial),
                                (
                                    "pid",
                                    &self
                                        .target_pid
                                        .map_or_else(|| "—".to_owned(), |pid| pid.to_string()),
                                ),
                            ],
                        )
                    } else {
                        language
                            .text(TextKey::InspectorLiveCaptureUnavailable)
                            .to_owned()
                    }),
            )
            .child(div().flex_1())
            .child(compose_agent_control(
                language,
                colors,
                archive
                    .and_then(|archive| archive.compose.as_ref())
                    .is_some(),
                self.compose_agent,
                cx,
            ))
            .child(scan_mode_control(language, colors));

        div()
            .id("inspector-header-toolbar")
            .h(px(HEADER_TOOLBAR_HEIGHT))
            .w_full()
            .flex_shrink_0()
            .flex()
            .items_center()
            .gap(px(8.))
            .px(px(10.))
            .bg(colors.toolbar)
            .border_b_1()
            .border_color(colors.border)
            .child(controls)
            .child(header_divider(colors))
            .child(
                panel_toggle_button(
                    "inspector-toggle-hierarchy",
                    IconName::PanelLeft,
                    language.text(TextKey::InspectorToggleHierarchy),
                    self.show_hierarchy,
                    colors,
                )
                .flex_shrink_0()
                .on_click(cx.listener(|this, _, _, cx| this.toggle_hierarchy(cx))),
            )
            .child(
                panel_toggle_button(
                    "inspector-toggle-findings",
                    IconName::PanelBottom,
                    language.text(TextKey::InspectorToggleFindings),
                    self.show_findings,
                    colors,
                )
                .flex_shrink_0()
                .on_click(cx.listener(|this, _, _, cx| this.toggle_findings(cx))),
            )
            .child(
                panel_toggle_button(
                    "inspector-toggle-details",
                    IconName::PanelRight,
                    language.text(TextKey::InspectorToggleDetails),
                    self.show_details,
                    colors,
                )
                .flex_shrink_0()
                .on_click(cx.listener(|this, _, _, cx| this.toggle_details(cx))),
            )
            .into_any_element()
    }

    fn toggle_hidden(&mut self, id: String, cx: &mut Context<Self>) {
        if !self.hidden.remove(&id) {
            self.hidden.insert(id);
        }
        cx.notify();
    }

    fn toggle_collapsed(&mut self, id: String, cx: &mut Context<Self>) {
        if !self.collapsed.remove(&id) {
            self.collapsed.insert(id);
        }
        cx.notify();
    }

    fn prepare_ai(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        let Some(studio) = self.studio.clone() else {
            return;
        };
        studio
            .update(cx, |studio, cx| studio.prepare_ai(window, cx))
            .ok();
    }

    fn has_children(&self, index: usize) -> bool {
        match (self.node(index), self.node(index + 1)) {
            (Some(parent), Some(next)) => {
                next.parent_index == Some(index) && parent.depth < next.depth
            }
            _ => false,
        }
    }

    /// Visible hierarchy row indices: collapsed subtrees and unmatched search
    /// results are pruned, mirroring Compose HierarchyTreeState.displayRows.
    fn visible_rows(&self, cx: &Context<Self>) -> Vec<usize> {
        let Some(archive) = self.archive.as_ref() else {
            return Vec::new();
        };
        let Some(window) = archive.windows.get(self.window) else {
            return Vec::new();
        };
        let query = self.search.read(cx).value().to_lowercase();
        let query = query.trim();
        let mut rows = Vec::new();
        let mut skip_depth: Option<usize> = None;
        for (index, node) in window.nodes.iter().enumerate() {
            if let Some(depth) = skip_depth {
                if node.depth > depth {
                    continue;
                }
                skip_depth = None;
            }
            if self.preferences.hide_invisible_hierarchy && !node.visible {
                skip_depth = Some(node.depth);
                continue;
            }
            if self.collapsed.contains(&node.id) && self.has_children(index) {
                skip_depth = Some(node.depth);
            }
            if !query.is_empty() && !row_matches(node, query) {
                continue;
            }
            rows.push(index);
        }
        rows
    }

    fn begin_pane_drag(
        &mut self,
        kind: PaneDragKind,
        event: &MouseDownEvent,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        self.pane_drag = Some(PaneDrag {
            kind,
            start_x: f32::from(event.position.x),
            start_y: f32::from(event.position.y),
            start_hierarchy: self.hierarchy_width,
            start_properties: self.properties_width,
            start_findings: self.findings_height,
            available_width: f32::from(window.bounds().size.width),
            available_height: f32::from(window.bounds().size.height),
        });
        cx.notify();
    }

    /// Compose PaneLayout.dragHierarchy/dragProperties and FindingsLayout.drag:
    /// deltas are applied against the drag-start sizes and clamped so the
    /// canvas keeps at least 320 dp and findings keeps 56 dp / max 50% height.
    fn update_pane_drag(&mut self, event: &MouseMoveEvent, cx: &mut Context<Self>) {
        let Some(drag) = self.pane_drag else {
            return;
        };
        if event.pressed_button != Some(MouseButton::Left) {
            self.pane_drag = None;
            cx.notify();
            return;
        }
        match drag.kind {
            PaneDragKind::Hierarchy => {
                let delta = f32::from(event.position.x) - drag.start_x;
                let maximum = (drag.available_width
                    - drag.start_properties
                    - 2.0 * SPLITTER
                    - CANVAS_MIN_WIDTH)
                    .clamp(HIERARCHY_MIN_WIDTH, HIERARCHY_MAX_WIDTH);
                self.hierarchy_width =
                    (drag.start_hierarchy + delta).clamp(HIERARCHY_MIN_WIDTH, maximum);
            }
            PaneDragKind::Properties => {
                let delta = f32::from(event.position.x) - drag.start_x;
                let maximum = (drag.available_width
                    - drag.start_hierarchy
                    - 2.0 * SPLITTER
                    - CANVAS_MIN_WIDTH)
                    .clamp(PROPERTIES_MIN_WIDTH, PROPERTIES_MAX_WIDTH);
                self.properties_width =
                    (drag.start_properties - delta).clamp(PROPERTIES_MIN_WIDTH, maximum);
            }
            PaneDragKind::Findings => {
                let delta = f32::from(event.position.y) - drag.start_y;
                let maximum = (drag.available_height * FINDINGS_MAX_RATIO).max(FINDINGS_MIN_HEIGHT);
                self.findings_height =
                    (drag.start_findings - delta).clamp(FINDINGS_MIN_HEIGHT, maximum);
            }
        }
        cx.notify();
    }

    fn end_pane_drag(&mut self, cx: &mut Context<Self>) {
        if self.pane_drag.take().is_some() {
            cx.notify();
        }
    }

    fn preview_limits(&self) -> (f32, f32) {
        let Ok(Some(metrics)) = self.preview_metrics.lock().map(|metrics| *metrics) else {
            return (0.0, 0.0);
        };
        let factor = if metrics.zoom > 0.0 {
            self.zoom / metrics.zoom
        } else {
            1.0
        };
        preview_pan_limits(
            metrics.content_w * factor,
            metrics.content_h * factor,
            f32::from(metrics.viewport.size.width),
            f32::from(metrics.viewport.size.height),
        )
    }

    fn clamp_preview_pan(&self, pan: (f32, f32)) -> (f32, f32) {
        let (limit_x, limit_y) = self.preview_limits();
        (
            if limit_x == 0.0 {
                0.0
            } else {
                pan.0.clamp(-limit_x, limit_x)
            },
            if limit_y == 0.0 {
                0.0
            } else {
                pan.1.clamp(-limit_y, limit_y)
            },
        )
    }

    fn preview_pan_at_bar(&mut self, horizontal: bool, x: f32, y: f32) {
        let Ok(Some(metrics)) = self.preview_metrics.lock().map(|metrics| *metrics) else {
            return;
        };
        let vw = f32::from(metrics.viewport.size.width);
        let vh = f32::from(metrics.viewport.size.height);
        let (limit_x, limit_y) = self.preview_limits();
        if horizontal {
            let left = f32::from(metrics.viewport.origin.x);
            let fraction = ((x - left - 6.0) / (vw - 20.0).max(1.0)).clamp(0.0, 1.0);
            let pan_x = if limit_x <= 0.0 {
                0.0
            } else {
                (2.0 * fraction - 1.0) * limit_x
            };
            self.preview_pan = self.clamp_preview_pan((pan_x, self.preview_pan.1));
        } else {
            let top = f32::from(metrics.viewport.origin.y);
            let fraction = ((y - top - 6.0) / (vh - 20.0).max(1.0)).clamp(0.0, 1.0);
            let pan_y = if limit_y <= 0.0 {
                0.0
            } else {
                (2.0 * fraction - 1.0) * limit_y
            };
            self.preview_pan = self.clamp_preview_pan((self.preview_pan.0, pan_y));
        }
    }

    /// Compose PreviewPanState.scroll: wheel pans the preview; shift+wheel pans
    /// horizontally, matching the Compose fallback that maps the wheel amount
    /// onto the horizontal axis.
    fn preview_scroll(&mut self, event: &ScrollEvent, cx: &mut Context<Self>) {
        let delta = event.delta.pixel_delta(px(20.0));
        let (dx, dy) = if event.modifiers.shift {
            (f32::from(delta.x) + f32::from(delta.y), 0.0)
        } else {
            (f32::from(delta.x), f32::from(delta.y))
        };
        let next = self.clamp_preview_pan((self.preview_pan.0 - dx, self.preview_pan.1 - dy));
        if next != self.preview_pan {
            self.preview_pan = next;
            cx.notify();
        }
    }

    fn update_preview_pan_drag(&mut self, event: &MouseMoveEvent, cx: &mut Context<Self>) {
        if let Some(horizontal) = self.preview_bar_drag {
            if event.pressed_button != Some(MouseButton::Left) {
                self.preview_bar_drag = None;
                cx.notify();
                return;
            }
            self.preview_pan_at_bar(
                horizontal,
                f32::from(event.position.x),
                f32::from(event.position.y),
            );
            cx.notify();
            return;
        }
        let Some(drag) = self.preview_drag else {
            return;
        };
        if event.pressed_button != Some(MouseButton::Left) {
            return;
        }
        let dx = f32::from(event.position.x) - drag.start_x;
        let dy = f32::from(event.position.y) - drag.start_y;
        if !drag.moved && dx.abs() + dy.abs() <= 2.0 {
            return;
        }
        if !drag.moved {
            self.preview_drag = Some(PreviewDrag {
                moved: true,
                ..drag
            });
        }
        let next = self.clamp_preview_pan((drag.start_pan.0 + dx, drag.start_pan.1 + dy));
        if next != self.preview_pan {
            self.preview_pan = next;
            cx.notify();
        }
    }

    fn end_preview_drag(&mut self, cx: &mut Context<Self>) {
        let changed = self.preview_bar_drag.take().is_some() || self.preview_drag.take().is_some();
        if changed {
            cx.notify();
        }
    }

    fn set_zoom(&mut self, zoom: f32, cx: &mut Context<Self>) {
        let zoom = zoom.clamp(MIN_ZOOM, MAX_ZOOM);
        if zoom == self.zoom {
            return;
        }
        self.zoom = zoom;
        self.preview_pan = self.clamp_preview_pan(self.preview_pan);
        self.preview_drag = None;
        cx.notify();
    }

    fn zoom_by(&mut self, delta: f32, cx: &mut Context<Self>) {
        self.set_zoom(self.zoom + delta, cx);
    }

    fn zoom_reset(&mut self, cx: &mut Context<Self>) {
        self.zoom = 1.0;
        self.preview_pan = (0.0, 0.0);
        self.preview_drag = None;
        cx.notify();
    }

    /// Compose `PreviewZoomControls`: bottom-right overlay row with a −/value/+
    /// group over the canvas, radius 8, 90% panel fill and a 65% hairline.
    fn zoom_controls(
        &self,
        language: Language,
        colors: &StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let zoom_out = self.zoom > MIN_ZOOM;
        let zoom_in = self.zoom < MAX_ZOOM;
        let zoom_out_button = Button::new("inspector-zoom-out")
            .ghost()
            .h(px(24.))
            .w(px(24.))
            .p(px(0.))
            .text_color(if zoom_out {
                colors.accent
            } else {
                colors.muted_text.alpha(0.45)
            })
            .disabled(!zoom_out)
            .accessibility_label(language.text(TextKey::InspectorZoomOut))
            .tooltip(language.text(TextKey::InspectorZoomOut))
            .child(
                // The glyph must not own the click: a selectable text run would
                // swallow pointer events, leaving only the button padding active.
                div()
                    .text_size(px(16.))
                    .font_weight(FontWeight::SEMIBOLD)
                    .child("−"),
            )
            .on_click(cx.listener(|this, _, _, cx| this.zoom_by(-ZOOM_STEP, cx)));
        let zoom_in_button = Button::new("inspector-zoom-in")
            .ghost()
            .h(px(24.))
            .w(px(24.))
            .p(px(0.))
            .text_color(if zoom_in {
                colors.accent
            } else {
                colors.muted_text.alpha(0.45)
            })
            .disabled(!zoom_in)
            .accessibility_label(language.text(TextKey::InspectorZoomIn))
            .tooltip(language.text(TextKey::InspectorZoomIn))
            .child(
                div()
                    .text_size(px(16.))
                    .font_weight(FontWeight::SEMIBOLD)
                    .child("+"),
            )
            .on_click(cx.listener(|this, _, _, cx| this.zoom_by(ZOOM_STEP, cx)));
        div()
            .id("inspector-zoom-controls")
            .absolute()
            .bottom(px(28.))
            .right(px(28.))
            .flex()
            .items_center()
            .rounded(px(8.))
            .bg(colors.panel.alpha(0.9))
            .border_1()
            .border_color(colors.border.alpha(0.65))
            .px(px(6.))
            .py(px(4.))
            .child(zoom_out_button)
            .child(
                // Compose renders the scale as a plain label; clicking it resets
                // to 100% here so the overlay always has a visible reset control
                // (the canvas header's Fit button stays the Compose-parity path).
                div()
                    .id("inspector-zoom-reset")
                    .w(px(42.))
                    .flex()
                    .justify_center()
                    .cursor_pointer()
                    .font_family("monospace")
                    .text_size(px(studio::FONT_SUPPORTING))
                    .text_color(colors.text)
                    .on_click(cx.listener(|this, _, _, cx| this.zoom_reset(cx)))
                    .child(format!("{}%", (self.zoom * 100.0).round() as i32)),
            )
            .child(zoom_in_button)
            .into_any_element()
    }
}

impl Drop for InspectorWorkspace {
    fn drop(&mut self) {
        self.cancel.cancel();
    }
}

impl Render for InspectorWorkspace {
    fn render(&mut self, _window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        let colors = StudioColors::from_theme(cx);
        let language = self.language;
        let archive = self.archive.clone();
        let window_data = archive
            .as_ref()
            .and_then(|archive| archive.windows.get(self.window));
        let node_count = window_data.map_or(0, |window| window.nodes.len());
        let visible_nodes = window_data.map_or(0, |window| {
            window.nodes.iter().filter(|node| node.visible).count()
        });
        let visible_rows = self.visible_rows(cx);
        let entity = cx.entity();

        let header_toolbar = self.header_toolbar(language, &colors, archive.as_ref(), cx);
        let status = status_bar(
            language,
            &colors,
            archive.as_ref(),
            node_count,
            visible_nodes,
            &self.hidden,
        );

        let mut panes = div()
            .id("inspector-panes")
            .flex_1()
            .min_h_0()
            .w_full()
            .overflow_hidden()
            .flex()
            .flex_row();
        if self.show_hierarchy {
            panes = panes
                .child(hierarchy_pane(
                    language,
                    &colors,
                    archive.clone(),
                    self.window,
                    self.hierarchy_width,
                    &visible_rows,
                    &self.hidden,
                    &self.selected,
                    &self.collapsed,
                    self.preferences,
                    entity.clone(),
                    self.search.clone(),
                    self.hierarchy_scroll.clone(),
                    cx,
                ))
                .child(vertical_splitter(
                    "inspector-splitter-hierarchy",
                    PaneDragKind::Hierarchy,
                    &colors,
                    cx,
                ));
        }
        panes = panes.child(self.preview_pane(
            language,
            &colors,
            archive.clone(),
            &self.hidden,
            &self.selected,
            cx,
        ));
        if self.show_details {
            panes = panes
                .child(vertical_splitter(
                    "inspector-splitter-properties",
                    PaneDragKind::Properties,
                    &colors,
                    cx,
                ))
                .child(details_pane(
                    language,
                    &colors,
                    archive.as_ref(),
                    self.window,
                    self.properties_width,
                    self.selected_index(),
                    self.details_tab,
                    &self.selected,
                    self.compose_agent,
                    cx,
                ));
        }

        let mut workspace = div()
            .id("inspector-workspace-body")
            .flex_1()
            .min_h_0()
            .w_full()
            .flex()
            .flex_col();
        workspace = workspace.child(panes);
        if self.show_findings {
            workspace = workspace
                .child(horizontal_splitter(&colors, cx))
                .child(findings_pane(
                    language,
                    &colors,
                    self.findings_tone,
                    self.findings_height,
                    cx,
                ));
        }

        div()
            .id("inspector-workspace")
            .size_full()
            .min_h_0()
            .min_w_0()
            .flex()
            .flex_col()
            .overflow_hidden()
            .on_mouse_move(cx.listener(|this, event: &MouseMoveEvent, _, cx| {
                this.update_pane_drag(event, cx);
                this.update_preview_pan_drag(event, cx);
            }))
            .on_mouse_up(
                MouseButton::Left,
                cx.listener(|this, _, _, cx| {
                    this.end_pane_drag(cx);
                    this.end_preview_drag(cx);
                }),
            )
            .bg(colors.workspace)
            .text_color(colors.text)
            .text_size(px(studio::FONT_BODY))
            .child(header_toolbar)
            .children(self.error.clone().map(|error| {
                div()
                    .flex_shrink_0()
                    .px(px(10.))
                    .py(px(4.))
                    .text_size(px(studio::FONT_SUPPORTING))
                    .text_color(colors.error)
                    .child(crate::studio::selectable(error))
            }))
            .children(self.busy.then(|| {
                div()
                    .flex_shrink_0()
                    .px(px(10.))
                    .py(px(4.))
                    .text_size(px(studio::FONT_SUPPORTING))
                    .text_color(colors.muted_text)
                    .child(crate::studio::selectable(
                        language.text(TextKey::InspectorValidatingArchive),
                    ))
            }))
            .child(workspace)
            .child(status)
    }
}

/// Preview source rectangle in display pixels, mirroring Compose
/// `CanvasGeometry.sourceRect` / `CanvasWindowSource.sourceRect`.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct PreviewSource {
    left: i32,
    top: i32,
    width: u32,
    height: u32,
}

/// App-only crops to the active window bounds, clamped to the display. Invalid,
/// empty or unknown bounds fall back to the full display, as Compose does.
fn preview_source(
    display: DisplayInfo,
    window: Option<&LayoutWindow>,
    app_only: bool,
) -> PreviewSource {
    let full = PreviewSource {
        left: 0,
        top: 0,
        width: display.width_px,
        height: display.height_px,
    };
    let Some(window) = window.filter(|_| app_only) else {
        return full;
    };
    let width = i64::from(display.width_px);
    let height = i64::from(display.height_px);
    let left = i64::from(window.bounds.left).clamp(0, width);
    let top = i64::from(window.bounds.top).clamp(0, height);
    let right = i64::from(window.bounds.right).clamp(0, width);
    let bottom = i64::from(window.bounds.bottom).clamp(0, height);
    if right <= left || bottom <= top {
        return full;
    }
    PreviewSource {
        left: left as i32,
        top: top as i32,
        width: (right - left) as u32,
        height: (bottom - top) as u32,
    }
}

/// Preorder ancestry is validated by the archive adapter. Visibility and alpha
/// prune descendants, but invalid/offscreen bounds do not prune their children.
fn canvas_nodes(
    nodes: &[LayoutNode],
    hidden: &BTreeSet<String>,
    selected: Option<&str>,
    display_width: u32,
    display_height: u32,
) -> (Vec<(usize, LayoutNode)>, usize) {
    let mut alpha = vec![0.0; nodes.len()];
    let mut eligible = Vec::new();
    for (index, node) in nodes.iter().enumerate() {
        let ancestor_alpha =
            node.parent_index.map_or(
                1.0,
                |parent| {
                    if parent < index { alpha[parent] } else { 0.0 }
                },
            );
        alpha[index] = if node.visible && !hidden.contains(&node.id) {
            ancestor_alpha * node.alpha
        } else {
            0.0
        };
        let b = node.bounds;
        if alpha[index] > 0.0
            && b.right > b.left
            && b.bottom > b.top
            && b.right > 0
            && b.bottom > 0
            && (b.left as i64) < display_width as i64
            && (b.top as i64) < display_height as i64
        {
            eligible.push(index);
        }
    }
    let total = eligible.len();
    let selected_index = eligible
        .iter()
        .copied()
        .find(|&i| Some(nodes[i].id.as_str()) == selected);
    eligible.truncate(MAX_CANVAS_NODES);
    if let Some(index) = selected_index
        && !eligible.contains(&index)
    {
        eligible.pop();
        eligible.push(index);
        eligible.sort_unstable();
    }
    (
        eligible
            .into_iter()
            .map(|i| (i, nodes[i].clone()))
            .collect(),
        total,
    )
}

/// Candidate ties resolve by reverse paint order, both for Z-order mode and
/// equally-sized bounds in smallest-area mode. No candidate identity is guessed.
fn canvas_hit(nodes: &[(usize, LayoutNode)], x: f32, y: f32, smallest: bool) -> Option<String> {
    let candidates = nodes.iter().rev().filter(|(_, node)| {
        x >= node.bounds.left as f32
            && x < node.bounds.right as f32
            && y >= node.bounds.top as f32
            && y < node.bounds.bottom as f32
    });
    if smallest {
        candidates
            .min_by_key(|(index, node)| {
                let b = node.bounds;
                let area = (i64::from(b.right) - i64::from(b.left)) as u64
                    * (i64::from(b.bottom) - i64::from(b.top)) as u64;
                (area, std::cmp::Reverse(*index))
            })
            .map(|(_, node)| node.id.clone())
    } else {
        candidates
            .into_iter()
            .next()
            .map(|(_, node)| node.id.clone())
    }
}

/// Compose InspectorPresenter.appendRows: the number is depth-index
/// with a per-depth preorder counter, the label is the simple class name
/// and show_ids adds id/{last resource segment}. Numbers are computed over
/// the full preorder, so filters keep the original number.

fn hierarchy_label(node: &LayoutNode, number: &str, preferences: LayoutPreferences) -> String {
    let mut parts = Vec::with_capacity(3);
    if !preferences.hide_indices {
        parts.push(number.to_owned());
    }
    if preferences.show_ids {
        if let Some(resource) = node.resource_name.as_deref() {
            let segment = resource.rsplit('/').next().unwrap_or(resource);
            if !segment.is_empty() {
                parts.push(format!("id/{segment}"));
            }
        }
    }
    parts.push(
        node.class_name
            .rsplit('.')
            .next()
            .unwrap_or(&node.class_name)
            .to_owned(),
    );
    parts.join("  ")
}

fn hierarchy_numbers(nodes: &[LayoutNode]) -> Vec<String> {
    let mut next_by_depth: std::collections::HashMap<usize, usize> =
        std::collections::HashMap::new();
    nodes
        .iter()
        .map(|node| {
            let index = next_by_depth.entry(node.depth).or_insert(0);
            let number = format!("{}-{}", node.depth, *index);
            *index += 1;
            number
        })
        .collect()
}

fn hierarchy_content_width(nodes: &[LayoutNode], preferences: LayoutPreferences) -> f32 {
    let mut maximum = 0.0f32;
    for node in nodes {
        let label = hierarchy_label(node, "0-0", preferences);
        let mut row = 8.0 + (node.depth.min(40) as f32) * HIERARCHY_INDENT + 12.0 + 4.0 + 8.0;
        if preferences.show_layer_buttons {
            row += 20.0 + 4.0;
        }
        row += label.chars().count() as f32 * 7.2;
        row += 32.0;
        maximum = maximum.max(row);
    }
    maximum
}

fn canvas_dimensions(
    display_width: u32,
    display_height: u32,
    viewport_width: f32,
    viewport_height: f32,
) -> (f32, f32, f32) {
    let scale = (viewport_width.max(0.0) / display_width.max(1) as f32)
        .min(viewport_height.max(0.0) / display_height.max(1) as f32);
    (
        display_width.max(1) as f32 * scale,
        display_height.max(1) as f32 * scale,
        scale,
    )
}

fn row_matches(node: &LayoutNode, query: &str) -> bool {
    node.class_name.to_lowercase().contains(query)
        || node.id.to_lowercase().contains(query)
        || node
            .resource_name
            .as_deref()
            .is_some_and(|resource| resource.to_lowercase().contains(query))
}

fn divider(colors: &StudioColors) -> Div {
    div().w_full().h(px(1.)).flex_shrink_0().bg(colors.border)
}

fn vertical_splitter(
    id: &'static str,
    kind: PaneDragKind,
    colors: &StudioColors,
    cx: &mut Context<InspectorWorkspace>,
) -> AnyElement {
    div()
        .id(id)
        .w(px(SPLITTER))
        .cursor(CursorStyle::ResizeLeftRight)
        .h_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .justify_center()
        .on_mouse_down(
            MouseButton::Left,
            cx.listener(move |this, event: &MouseDownEvent, window, cx| {
                this.begin_pane_drag(kind, event, window, cx);
            }),
        )
        .child(div().w(px(1.)).h_full().bg(colors.border))
        .into_any_element()
}

fn horizontal_splitter(colors: &StudioColors, cx: &mut Context<InspectorWorkspace>) -> AnyElement {
    div()
        .id("inspector-splitter-findings")
        .h(px(SPLITTER))
        .cursor(CursorStyle::ResizeUpDown)
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .justify_center()
        .on_mouse_down(
            MouseButton::Left,
            cx.listener(|this, event: &MouseDownEvent, window, cx| {
                this.begin_pane_drag(PaneDragKind::Findings, event, window, cx);
            }),
        )
        .child(div().w_full().h(px(1.)).bg(colors.border))
        .into_any_element()
}

fn header_divider(colors: &StudioColors) -> Div {
    div().w(px(1.)).h(px(14.)).flex_shrink_0().bg(colors.border)
}

fn panel_header(
    language: Language,
    title: TextKey,
    label: TextKey,
    trailing: impl IntoElement,
    colors: &StudioColors,
) -> Div {
    div()
        .h(px(PANEL_HEADER_HEIGHT))
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .gap(px(6.))
        .px(px(12.))
        .border_b_1()
        .border_color(colors.border)
        .child(
            div()
                .text_size(px(studio::FONT_SUPPORTING))
                .font_weight(FontWeight::BOLD)
                .text_color(colors.secondary_text)
                .child(crate::studio::selectable(language.text(title))),
        )
        .child(
            div()
                .text_size(px(studio::FONT_SUPPORTING - 1.0))
                .text_color(colors.muted_text)
                .child(crate::studio::selectable(language.text(label))),
        )
        .child(div().flex_1())
        .child(trailing)
}

fn toolbar_button(
    id: impl Into<ElementId>,
    label: impl Into<SharedString>,
    enabled: bool,
    primary: bool,
    colors: &StudioColors,
) -> Button {
    let label: SharedString = label.into();
    let container = if primary { colors.accent } else { colors.panel };
    let content = if primary {
        colors.accent_text
    } else {
        colors.accent
    };
    let (container, content) = if enabled {
        (container, content)
    } else {
        (container.alpha(0.55), content.alpha(0.48))
    };
    Button::new(id)
        .ghost()
        .h(px(TOOLBAR_BUTTON_HEIGHT))
        .px(px(8.))
        .rounded(px(TOOLBAR_BUTTON_RADIUS))
        .bg(container)
        .border_1()
        .border_color(colors.accent)
        .text_color(content)
        .disabled(!enabled)
        .accessibility_label(label.clone())
        .child(
            div()
                .text_size(px(studio::FONT_SUPPORTING))
                .whitespace_nowrap()
                .text_ellipsis()
                .child(crate::studio::selectable(label)),
        )
}

fn toolbar_icon_button(
    id: impl Into<ElementId>,
    icon: IconName,
    tooltip: impl Into<SharedString>,
    selected: bool,
    enabled: bool,
    colors: &StudioColors,
) -> Button {
    Button::new(id)
        .ghost()
        .h(px(TOOLBAR_BUTTON_HEIGHT))
        .w(px(26.))
        .rounded(px(TOOLBAR_BUTTON_RADIUS))
        .bg(if selected {
            colors.accent.alpha(0.18)
        } else {
            colors.panel
        })
        .border_1()
        .border_color(if selected {
            colors.accent
        } else {
            colors.border
        })
        .text_color(if selected {
            colors.accent
        } else {
            colors.secondary_text
        })
        .disabled(!enabled)
        .icon(icon)
        .tooltip(tooltip)
}

/// Compose `DropdownSelector`: accent outline, accent label, and a trailing
/// expand chevron over the section surface.
fn capture_target_control(
    language: Language,
    colors: &StudioColors,
    current: CaptureTargetMode,
    cx: &mut Context<InspectorWorkspace>,
) -> AnyElement {
    let prefix = language.text(TextKey::InspectorCaptureTarget);
    let label = format!("{prefix}: {}", current.label(language));
    let entity = cx.entity().downgrade();
    Button::new("inspector-capture-target")
        .ghost()
        .h(px(TOOLBAR_BUTTON_HEIGHT))
        .px(px(8.))
        .flex_shrink_0()
        .rounded(px(TOOLBAR_BUTTON_RADIUS))
        .bg(colors.section_background)
        .border_1()
        .border_color(colors.accent)
        .text_color(colors.accent)
        .accessibility_label(label.clone())
        .child(
            div()
                .flex()
                .items_center()
                .gap(px(4.))
                .text_size(px(studio::FONT_SUPPORTING))
                .whitespace_nowrap()
                .child(crate::studio::selectable(label))
                .child(IconName::ChevronDown),
        )
        .dropdown_menu(move |menu, _, _| {
            let mut menu = menu.min_w(px(160.));
            for mode in CaptureTargetMode::ALL {
                let entity = entity.clone();
                menu = menu.item(
                    PopupMenuItem::new(mode.label(language))
                        .checked(mode == current)
                        .on_click(move |_, _, cx| {
                            entity
                                .update(cx, |this, cx| this.set_capture_target(mode, cx))
                                .ok();
                        }),
                );
            }
            menu
        })
        .into_any_element()
}

/// Compose `SegmentedSelector(SegmentedSelectorStyle.COMPACT_OUTLINED)` for
/// Refresh / Auto scan. Live capture is not connected, so both segments stay
/// disabled while keeping the Compose outline and selected fill.
/// Compose `ComposeAgentToggle`: 22 dp checkbox + label. Offline archives
/// cannot inject an agent, so it is enabled when the archive already contains a
/// Compose inspection sidecar and reveals the Compose category/summary.
fn compose_agent_control(
    language: Language,
    colors: &StudioColors,
    available: bool,
    checked: bool,
    cx: &mut Context<InspectorWorkspace>,
) -> AnyElement {
    let enabled = checked || available;
    let border = if checked {
        colors.accent
    } else if enabled {
        colors.secondary_text
    } else {
        colors.muted_text.alpha(0.45)
    };
    let mut control = div()
        .id("inspector-compose-agent")
        .flex()
        .flex_shrink_0()
        .items_center()
        .gap(px(4.))
        .child(
            div()
                .size(px(18.))
                .flex_shrink_0()
                .flex()
                .items_center()
                .justify_center()
                .rounded(px(2.))
                .border_1()
                .border_color(border)
                .bg(if checked {
                    colors.accent
                } else {
                    rgba(0x00000000)
                })
                .text_size(px(12.))
                .font_weight(FontWeight::BOLD)
                .text_color(colors.accent_text)
                .child(if checked { "✓" } else { "" }),
        )
        .child(
            div()
                .text_size(px(studio::FONT_SUPPORTING))
                .text_color(if enabled {
                    colors.text
                } else {
                    colors.muted_text
                })
                .child(crate::studio::selectable(
                    language.text(TextKey::InspectorComposeAgent),
                )),
        );
    if enabled {
        control = control
            .cursor_pointer()
            .on_click(cx.listener(|this, _, _, cx| {
                this.compose_agent = !this.compose_agent;
                if this.compose_agent && this.selected_is_compose() {
                    this.details_tab = DetailsTab::Compose;
                }
                cx.notify();
            }));
    }
    control.into_any_element()
}

/// Compose `SegmentedSelector(SegmentedSelectorStyle.COMPACT_OUTLINED)` track:
/// 24 dp high, 4 dp radius, 1 px accent outline and accent hairlines between
/// items. Shared by the header scan control and the properties category tabs.
fn segmented_track(id: impl Into<ElementId>, colors: &StudioColors) -> Stateful<Div> {
    div()
        .id(id)
        .flex_shrink_0()
        .h(px(TOOLBAR_BUTTON_HEIGHT))
        .flex()
        .items_center()
        .rounded(px(TOOLBAR_BUTTON_RADIUS))
        .border_1()
        .border_color(colors.accent)
        .overflow_hidden()
}

fn segmented_divider(colors: &StudioColors) -> Div {
    div().w(px(1.)).h_full().flex_shrink_0().bg(colors.accent)
}

fn segmented_item(
    id: impl Into<ElementId>,
    label: &'static str,
    selected: bool,
    enabled: bool,
    colors: &StudioColors,
) -> Button {
    let content = if selected {
        colors.accent_text
    } else {
        colors.accent
    };
    Button::new(id)
        .ghost()
        .h_full()
        .px(px(8.))
        .rounded_none()
        .bg(if selected {
            colors.accent
        } else {
            rgba(0x00000000)
        })
        .text_color(if enabled {
            content
        } else {
            content.alpha(0.48)
        })
        .disabled(!enabled)
        .accessibility_label(label)
        .child(
            div()
                .text_size(px(studio::FONT_SUPPORTING))
                .whitespace_nowrap()
                .child(crate::studio::selectable(label)),
        )
}

fn scan_mode_control(language: Language, colors: &StudioColors) -> AnyElement {
    let entries = [
        (TextKey::InspectorRefresh, true),
        (TextKey::InspectorAutoScan, false),
    ];
    let mut track = segmented_track("inspector-scan-mode", colors);
    for (index, (key, selected)) in entries.into_iter().enumerate() {
        if index > 0 {
            track = track.child(segmented_divider(colors));
        }
        track = track.child(
            segmented_item(
                ("inspector-scan-mode", index),
                language.text(key),
                selected,
                false,
                colors,
            )
            .tooltip(language.text(TextKey::InspectorLiveCaptureUnavailable)),
        );
    }
    track.into_any_element()
}

/// Compose `PanelToggleButton`: 26 x 21, accent-tinted when the pane is
/// visible, no outline.
/// Compose canvas-header chip: bold accent label on a translucent accent
/// surface (18%/8% for the app-only on/off states, 14% for hit order).
fn canvas_chip(
    id: impl Into<ElementId>,
    label: &'static str,
    tint: f32,
    colors: &StudioColors,
) -> Stateful<Div> {
    div()
        .id(id)
        .flex_shrink_0()
        .rounded(px(4.))
        .bg(colors.accent.alpha(tint))
        .px(px(8.))
        .py(px(4.))
        .cursor_pointer()
        .text_size(px(studio::FONT_SUPPORTING))
        .font_weight(FontWeight::BOLD)
        .text_color(colors.accent)
        .whitespace_nowrap()
        .child(crate::studio::selectable(label))
}

fn panel_toggle_button(
    id: impl Into<ElementId>,
    icon: IconName,
    tooltip: impl Into<SharedString>,
    visible: bool,
    colors: &StudioColors,
) -> Button {
    Button::new(id)
        .ghost()
        .h(px(21.))
        .w(px(26.))
        .rounded(px(3.))
        .bg(if visible {
            colors.accent.alpha(0.18)
        } else {
            rgba(0x00000000)
        })
        .text_color(colors.accent)
        .icon(icon)
        .tooltip(tooltip)
}

fn status_value(
    label: &'static str,
    value: impl Into<SharedString>,
    mono: bool,
    colors: &StudioColors,
) -> Div {
    let value: SharedString = value.into();
    let value_div = if mono {
        div()
            .text_size(px(studio::FONT_SUPPORTING))
            .text_color(colors.text)
            .whitespace_nowrap()
            .child(crate::studio::selectable(value))
    } else {
        div()
            .text_size(px(studio::FONT_SUPPORTING))
            .text_color(colors.text)
            .whitespace_nowrap()
            .child(crate::studio::selectable(value))
    };
    div()
        .flex()
        .items_center()
        .gap(px(4.))
        .child(
            div()
                .text_size(px(studio::FONT_SUPPORTING))
                .text_color(colors.muted_text)
                .whitespace_nowrap()
                .child(crate::studio::selectable(label)),
        )
        .child(value_div)
}

fn status_bar(
    language: Language,
    colors: &StudioColors,
    archive: Option<&Arc<InspectorArchive>>,
    node_count: usize,
    visible_count: usize,
    hidden: &BTreeSet<String>,
) -> AnyElement {
    let package = archive.map_or_else(
        || language.text(TextKey::InspectorNoArchive).to_owned(),
        |archive| archive.frame.package_name.clone(),
    );
    let window = archive
        .and_then(|archive| archive.windows.get(0))
        .map_or_else(|| "-".to_owned(), |window| window.id.clone());
    let metrics = language.format(
        TextKey::InspectorStatusNodes,
        &[("count", &node_count), ("visible", &visible_count)],
    );
    let hidden_text = language.format(TextKey::InspectorHiddenSummary, &[("count", &hidden.len())]);
    let mut bar = div()
        .id("inspector-status")
        .h(px(STATUS_HEIGHT))
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .gap(px(8.))
        .px(px(16.))
        .border_1()
        .border_color(colors.border)
        .overflow_x_scroll()
        .child(status_value(
            language.text(TextKey::InspectorStatusPackage),
            package,
            true,
            colors,
        ))
        .child(header_divider(colors))
        .child(status_value(
            language.text(TextKey::InspectorStatusWindow),
            window,
            true,
            colors,
        ))
        .child(header_divider(colors))
        .child(status_value(
            language.text(TextKey::InspectorStatusMetrics),
            metrics,
            false,
            colors,
        ));
    if !hidden.is_empty() {
        bar = bar.child(header_divider(colors)).child(status_value(
            language.text(TextKey::InspectorShowAll),
            hidden_text,
            false,
            colors,
        ));
    }
    bar.child(div().flex_1())
        .child(status_value(
            language.text(TextKey::InspectorStatusSource),
            language.text(TextKey::InspectorOfflineArchive).to_owned(),
            false,
            colors,
        ))
        .into_any_element()
}

fn inherited_hidden(nodes: &[LayoutNode], index: usize, hidden: &BTreeSet<String>) -> bool {
    let mut current = nodes.get(index).and_then(|node| node.parent_index);
    while let Some(parent) = current {
        let Some(node) = nodes.get(parent) else {
            break;
        };
        if hidden.contains(&node.id) {
            return true;
        }
        current = node.parent_index;
    }
    false
}

fn hierarchy_pane(
    language: Language,
    colors: &StudioColors,
    archive: Option<Arc<InspectorArchive>>,
    window_index: usize,
    width: f32,
    rows: &[usize],
    hidden: &BTreeSet<String>,
    selected: &Option<String>,
    collapsed: &BTreeSet<String>,
    preferences: LayoutPreferences,
    entity: Entity<InspectorWorkspace>,
    search: Entity<InputState>,
    scroll: UniformListScrollHandle,
    cx: &mut Context<InspectorWorkspace>,
) -> AnyElement {
    let count = rows.len();
    let hidden_set = Arc::new(hidden.clone());
    let collapsed_set = Arc::new(collapsed.clone());
    let selected_id = Arc::new(selected.clone());
    let row_indices = Arc::new(rows.to_vec());
    let nodes: Arc<Vec<LayoutNode>> = Arc::new(
        archive
            .as_ref()
            .and_then(|archive| archive.windows.get(window_index))
            .map_or_else(Vec::new, |window| window.nodes.clone()),
    );
    // Preorder per-depth numbers, matching Compose's TreeRowModel.number.
    let numbers: Arc<Vec<String>> = Arc::new(hierarchy_numbers(&nodes));

    let header = panel_header(
        language,
        TextKey::InspectorHierarchyTitle,
        TextKey::InspectorHierarchyLabel,
        div()
            .text_size(px(studio::FONT_SUPPORTING))
            .text_color(colors.muted_text)
            .child(crate::studio::selectable(count.to_string())),
        colors,
    );

    let search_row = div()
        .h(px(28.))
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .px(px(8.))
        .border_b_1()
        .border_color(colors.border)
        .child(div().w_full().child(Input::new(&search).h(px(22.))));

    let mut body = div()
        .id("inspector-hierarchy-body")
        .flex_1()
        .min_h_0()
        .w_full()
        .overflow_hidden()
        .flex()
        .flex_col();
    if rows.is_empty() {
        body = body.child(
            div()
                .flex_1()
                .min_h_0()
                .flex()
                .items_center()
                .justify_center()
                .p(px(12.))
                .text_color(colors.muted_text)
                .text_size(px(studio::FONT_SUPPORTING))
                .child(crate::studio::selectable(
                    language.text(TextKey::InspectorNoHierarchy),
                )),
        );
    } else {
        // Compose puts the hierarchy list in one horizontal scroller and sizes
        // rows to the longest label, so deep trees pan sideways.
        let content_width = hierarchy_content_width(&nodes, preferences).max(width);
        let list_entity = entity.clone();
        let list_nodes = nodes.clone();
        let list_numbers = numbers.clone();
        let list_rows = row_indices.clone();
        let list_hidden = hidden_set.clone();
        let list_collapsed = collapsed_set.clone();
        let list_selected = selected_id.clone();
        let list_colors = *colors;
        let list = uniform_list(
            "inspector-hierarchy-rows",
            rows.len(),
            move |range, _, cx| {
                let mut items = Vec::with_capacity(range.len());
                for row_index in range {
                    let Some(global_index) = list_rows.get(row_index).copied() else {
                        continue;
                    };
                    let Some(node) = list_nodes.get(global_index) else {
                        continue;
                    };
                    let number = list_numbers
                        .get(global_index)
                        .map(String::as_str)
                        .unwrap_or("");
                    items.push(hierarchy_row(
                        row_index,
                        global_index,
                        number,
                        content_width,
                        node,
                        &list_nodes,
                        &list_colors,
                        &list_hidden,
                        &list_collapsed,
                        &list_selected,
                        preferences,
                        &list_entity,
                        language,
                        cx,
                    ));
                }
                items
            },
        )
        .with_horizontal_sizing_behavior(ListHorizontalSizingBehavior::Unconstrained)
        .h_full()
        .w_full()
        .track_scroll(&scroll);
        body = body.child(
            div()
                .relative()
                .flex_1()
                .min_h_0()
                .w_full()
                .child(list)
                .child(
                    div().absolute().inset_0().child(
                        Scrollbar::new(&scroll)
                            .id("inspector-hierarchy-scrollbar-vertical")
                            .axis(ScrollbarAxis::Vertical)
                            .mode(ScrollbarMode::Always)
                            .viewport_from_layout(),
                    ),
                )
                .child(
                    div().absolute().inset_0().child(
                        Scrollbar::new(&scroll)
                            .id("inspector-hierarchy-scrollbar-horizontal")
                            .axis(ScrollbarAxis::Horizontal)
                            .mode(ScrollbarMode::Always)
                            .viewport_from_layout(),
                    ),
                ),
        );
    }

    let mut pane = div()
        .id("inspector-hierarchy-pane")
        .w(px(width))
        .h_full()
        .flex_shrink_0()
        .flex()
        .flex_col()
        .min_h_0()
        .overflow_hidden()
        .bg(colors.panel)
        .child(header)
        .child(search_row)
        .child(body);
    let _ = cx;
    pane.into_any_element()
}

fn hierarchy_row(
    row_index: usize,
    global_index: usize,
    number: &str,
    content_width: f32,
    node: &LayoutNode,
    nodes: &[LayoutNode],
    colors: &StudioColors,
    hidden: &BTreeSet<String>,
    collapsed: &BTreeSet<String>,
    selected: &Option<String>,
    preferences: LayoutPreferences,
    entity: &Entity<InspectorWorkspace>,
    language: Language,
    cx: &mut App,
) -> AnyElement {
    let id = node.id.clone();
    let class_name = node.class_name.clone();
    let depth = node.depth;
    let has_children = nodes
        .get(global_index + 1)
        .is_some_and(|next| next.parent_index == Some(global_index));
    let is_collapsed = collapsed.contains(&id);
    let is_selected = selected.as_deref() == Some(id.as_str());
    let hidden_direct = hidden.contains(&id);
    let hidden_inherited = !hidden_direct && inherited_hidden(nodes, global_index, hidden);
    let muted = hidden_direct || hidden_inherited || !node.visible;
    let text_color = if muted {
        colors.muted_text
    } else {
        colors.text
    };
    let background = if is_selected {
        colors.selected_row
    } else {
        colors.panel
    };

    let indent = 8.0 + depth.min(40) as f32 * HIERARCHY_INDENT;
    let chevron = if !has_children {
        IconName::Minus
    } else if is_collapsed {
        IconName::ChevronRight
    } else {
        IconName::ChevronDown
    };
    let click_id = id.clone();
    let click_entity = entity.clone();
    let collapse_id = id.clone();
    let collapse_entity = entity.clone();
    let hide_id = id.clone();
    let hide_entity = entity.clone();
    let mut row = div()
        .id(("inspector-hierarchy-row", row_index))
        .h(px(HIERARCHY_ROW_HEIGHT))
        .w(px(content_width))
        .flex()
        .items_center()
        .bg(background)
        .pl(px(indent))
        .pr(px(8.));
    if has_children {
        row = row.child(
            Button::new(("inspector-hierarchy-disclosure", row_index))
                .ghost()
                .h(px(16.))
                .w(px(12.))
                .p(px(0.))
                .icon(chevron)
                .tooltip(node.class_name.clone())
                .on_click(move |_, _, cx| {
                    collapse_entity.update(cx, |this, cx| {
                        this.toggle_collapsed(collapse_id.clone(), cx)
                    });
                }),
        );
    } else {
        row = row.child(div().w(px(12.)));
    }
    row = row.child(div().w(px(4.)));
    if preferences.show_layer_buttons {
        row = row.child(
            Button::new(("inspector-hierarchy-visibility", row_index))
                .ghost()
                .h(px(16.))
                .w(px(20.))
                .p(px(0.))
                .icon(if hidden_direct {
                    IconName::EyeOff
                } else {
                    IconName::Eye
                })
                .tooltip(class_name.clone())
                .disabled(hidden_inherited)
                .on_click(move |_, _, cx| {
                    hide_entity.update(cx, |this, cx| this.toggle_hidden(hide_id.clone(), cx));
                }),
        );
        row = row.child(div().w(px(4.)));
    }
    let label = hierarchy_label(node, number, preferences);
    row = row
        .child(
            div()
                .flex_1()
                .min_w_0()
                .text_size(px(studio::FONT_SUPPORTING))
                .text_color(text_color)
                .whitespace_nowrap()
                .child(crate::studio::selectable(label)),
        )
        .on_click(move |_, _, cx| {
            let id = click_id.clone();
            click_entity.update(cx, |this, cx| {
                this.selected = Some(id);
                cx.notify();
            });
        });
    if hidden_inherited {
        row = row.child(
            div()
                .flex_shrink_0()
                .ml(px(4.))
                .text_size(px(studio::FONT_SUPPORTING - 1.0))
                .text_color(colors.muted_text)
                .whitespace_nowrap()
                .child(crate::studio::selectable(
                    language.text(TextKey::InspectorHiddenInherited),
                )),
        );
    }
    let _ = cx;
    row.into_any_element()
}

/// Decodes the integrity-checked archive PNG into a GPUI render image once per
/// published frame. Decode failures stay layout-only rather than showing a
/// placeholder pixel buffer.
fn decode_screenshot(bytes: &[u8], cx: &mut App) -> Option<Arc<RenderImage>> {
    Image::from_bytes(ImageFormat::Png, bytes.to_vec())
        .to_image_data(cx.svg_renderer())
        .ok()
}

fn preview_surface_color(dark: bool) -> Rgba {
    if dark {
        rgb(PREVIEW_SURFACE_DARK)
    } else {
        rgb(PREVIEW_SURFACE_LIGHT)
    }
}

impl InspectorWorkspace {
    fn preview_pane(
        &self,
        language: Language,
        colors: &StudioColors,
        archive: Option<Arc<InspectorArchive>>,
        hidden: &BTreeSet<String>,
        selected: &Option<String>,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let dark = colors.dark;
        let display = archive.as_ref().map(|archive| archive.display);
        let window = archive
            .as_ref()
            .and_then(|archive| archive.windows.get(self.window));
        let hidden_count = hidden.len();
        let source = display.map(|display| preview_source(display, window, self.app_only));
        let dimensions = source.map_or_else(
            || language.text(TextKey::InspectorNoSnapshot).to_owned(),
            |source| format!("{} × {}", source.width, source.height),
        );
        let app_only_label = if self.app_only {
            TextKey::InspectorAppOnlyOn
        } else {
            TextKey::InspectorAppOnlyOff
        };
        let hit_order_label = if self.preferences.hit_smallest {
            TextKey::InspectorSmallAreaHits
        } else {
            TextKey::InspectorZOrderHits
        };
        let mut trailing = div().flex().items_center().gap(px(6.)).child(
            div()
                .text_size(px(studio::FONT_SUPPORTING))
                .text_color(colors.muted_text)
                .child(dimensions),
        );
        trailing = trailing.child(
            toolbar_button(
                "inspector-fit-preview",
                language.text(TextKey::InspectorFitPreview),
                display.is_some(),
                false,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.zoom_reset(cx))),
        );
        // Compose canvas header order: dimensions, Fit, app-only mode, hit order.
        trailing = trailing
            .child(
                canvas_chip(
                    "inspector-app-only",
                    language.text(app_only_label),
                    if self.app_only { 0.18 } else { 0.08 },
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| {
                    this.app_only = !this.app_only;
                    cx.notify();
                })),
            )
            .child(
                canvas_chip(
                    "inspector-hit-order",
                    language.text(hit_order_label),
                    0.14,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| {
                    this.preferences.hit_smallest = !this.preferences.hit_smallest;
                    cx.emit(InspectorPreferencesChanged(this.preferences));
                    cx.notify();
                })),
            )
            .child(
                toolbar_icon_button(
                    "inspector-show-bounds",
                    IconName::Frame,
                    language.text(TextKey::InspectorShowBounds),
                    self.show_bounds,
                    display.is_some(),
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| {
                    this.show_bounds = !this.show_bounds;
                    this.preferences.show_visible_bounds = this.show_bounds;
                    cx.emit(InspectorPreferencesChanged(this.preferences));
                    cx.notify();
                })),
            );
        if hidden_count > 0 {
            trailing = trailing.child(
                div()
                    .text_size(px(studio::FONT_SUPPORTING))
                    .text_color(colors.accent)
                    .whitespace_nowrap()
                    .child(
                        language
                            .format(TextKey::InspectorHiddenSummary, &[("count", &hidden_count)]),
                    ),
            );
        }

        let header = panel_header(
            language,
            TextKey::InspectorPreviewTitle,
            TextKey::InspectorCanvasLabel,
            trailing,
            colors,
        );

        let mut body = div()
            .id("inspector-canvas-surface")
            .flex_1()
            .min_h_0()
            .w_full()
            .overflow_hidden()
            .flex()
            .flex_col();
        if let (Some(display), Some(window), Some(source)) = (display, window, source) {
            let (nodes, _eligible) = canvas_nodes(
                &window.nodes,
                hidden,
                selected.as_deref(),
                display.width_px,
                display.height_px,
            );
            // Compose draws the zoom overlay at the bottom-right of the canvas
            // viewport instead of a fixed row below it.
            body = body.child(
                div()
                    .flex_1()
                    .min_h_0()
                    .w_full()
                    .flex()
                    .flex_col()
                    .child(self.geometry_canvas(
                        nodes,
                        source,
                        display.width_px,
                        display.height_px,
                        self.screenshot_image.clone(),
                        dark,
                        colors,
                        cx,
                    ))
                    .child(self.zoom_controls(language, colors, cx)),
            );
        } else {
            body = body
                .child(
                    div()
                        .flex_1()
                        .min_h_0()
                        .flex()
                        .items_center()
                        .justify_center()
                        .text_color(colors.muted_text)
                        .child(crate::studio::selectable(
                            language.text(TextKey::InspectorNoLayoutSnapshot),
                        )),
                )
                .child(
                    div()
                        .h(px(28.))
                        .flex_shrink_0()
                        .w_full()
                        .flex()
                        .items_center()
                        .justify_center()
                        .text_size(px(studio::FONT_SUPPORTING))
                        .text_color(colors.muted_text)
                        .child(crate::studio::selectable(
                            language.text(TextKey::InspectorNoMedia),
                        )),
                );
        }

        div()
            .id("inspector-canvas-pane")
            .flex_1()
            .min_w_0()
            .h_full()
            .flex()
            .flex_col()
            .overflow_hidden()
            .bg(colors.panel)
            .child(header)
            .child(body)
            .into_any_element()
    }

    fn geometry_canvas(
        &self,
        nodes: Vec<(usize, LayoutNode)>,
        source: PreviewSource,
        display_width: u32,
        display_height: u32,
        screenshot: Option<Arc<RenderImage>>,
        dark: bool,
        colors: &StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let metrics = self.preview_metrics.clone();
        let down_metrics = metrics.clone();
        let move_metrics = metrics.clone();
        let up_metrics = metrics.clone();
        let pan_state = self.preview_pan;
        let hit_nodes = nodes.clone();
        let hover_nodes = nodes.clone();
        let hit_smallest = self.preferences.hit_smallest;
        // Compose maps pointer coordinates back into display space, then crops
        // the frame with a 12 dp / 2 dp radius for app-only on / off.
        let source_left = source.left as f32;
        let source_top = source.top as f32;
        let corner_radius = if self.app_only { 12.0 } else { 2.0 };
        let hovered = self.hovered.clone();
        let zoom = self.zoom;
        let selected = self.selected.clone();
        let show_bounds = self.show_bounds;
        let normal = rgba(self.preferences.normal_color.rotate_left(8));
        let hover_color = rgba(self.preferences.hover_color.rotate_left(8));
        let selected_color = rgba(self.preferences.selected_color.rotate_left(8));
        let surface = preview_surface_color(dark);
        let frame_border = colors.strong_border;
        let bar_track = colors.muted_text.alpha(0.14);
        let bar_thumb = colors.muted_text.alpha(0.55);
        div()
            .id("layout-canvas")
            .flex_1()
            .min_h_0()
            .w_full()
            .overflow_hidden()
            .bg(colors.panel)
            // Canvas zoom gestures: Ctrl/Cmd/Alt+wheel steps like the overlay
            // buttons, and a trackpad pinch scales continuously. Both clamp to
            // the same 50%-250% range and keep the preview pan valid.
            .on_scroll(cx.listener(|this, event: &ScrollEvent, _, cx| {
                if !(event.modifiers.control || event.modifiers.platform || event.modifiers.alt) {
                    return;
                }
                let vertical = f32::from(event.delta.pixel_delta(px(16.)).y);
                if vertical.abs() < 0.01 {
                    return;
                }
                let step = if vertical < 0.0 {
                    ZOOM_STEP
                } else {
                    -ZOOM_STEP
                };
                this.zoom_by(step, cx);
            }))
            .on_pinch(cx.listener(|this, event: &PinchEvent, _, cx| {
                this.set_zoom(this.zoom * (1.0 + event.delta), cx);
            }))
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(move |this, event: &MouseDownEvent, _, cx| {
                    let Ok(Some(metrics)) = down_metrics.lock().map(|metrics| *metrics) else {
                        return;
                    };
                    let viewport = metrics.viewport;
                    let x = f32::from(event.position.x - viewport.origin.x);
                    let y = f32::from(event.position.y - viewport.origin.y);
                    let vw = f32::from(viewport.size.width);
                    let vh = f32::from(viewport.size.height);
                    if y >= vh - 7.0 && x >= 4.0 && x <= vw - 10.0 {
                        this.preview_bar_drag = Some(true);
                        this.preview_pan_at_bar(
                            true,
                            f32::from(event.position.x),
                            f32::from(event.position.y),
                        );
                        cx.notify();
                        return;
                    }
                    if x >= vw - 7.0 && y >= 4.0 && y <= vh - 10.0 {
                        this.preview_bar_drag = Some(false);
                        this.preview_pan_at_bar(
                            false,
                            f32::from(event.position.x),
                            f32::from(event.position.y),
                        );
                        cx.notify();
                        return;
                    }
                    this.preview_drag = Some(PreviewDrag {
                        start_x: f32::from(event.position.x),
                        start_y: f32::from(event.position.y),
                        start_pan: this.preview_pan,
                        moved: false,
                    });
                }),
            )
            .on_mouse_move(cx.listener(move |this, event: &MouseMoveEvent, _, cx| {
                let Ok(Some(metrics)) = move_metrics.lock().map(|metrics| *metrics) else {
                    return;
                };
                if metrics.scale <= 0.0 {
                    return;
                }
                let x =
                    f32::from(event.position.x - metrics.origin.x) / metrics.scale + source_left;
                let y = f32::from(event.position.y - metrics.origin.y) / metrics.scale + source_top;
                let hit = canvas_hit(&hover_nodes, x, y, hit_smallest);
                if this.hovered != hit {
                    this.hovered = hit;
                    cx.notify();
                }
            }))
            .on_mouse_up(
                MouseButton::Left,
                cx.listener(move |this, event: &MouseUpEvent, _, cx| {
                    if this.preview_bar_drag.take().is_some() {
                        cx.notify();
                        return;
                    }
                    let Some(drag) = this.preview_drag.take() else {
                        return;
                    };
                    if drag.moved {
                        return;
                    }
                    let Ok(Some(metrics)) = up_metrics.lock().map(|metrics| *metrics) else {
                        return;
                    };
                    if metrics.scale <= 0.0 {
                        return;
                    }
                    let x = f32::from(event.position.x - metrics.origin.x) / metrics.scale
                        + source_left;
                    let y =
                        f32::from(event.position.y - metrics.origin.y) / metrics.scale + source_top;
                    this.selected = canvas_hit(&hit_nodes, x, y, hit_smallest);
                    cx.notify();
                }),
            )
            .on_mouse_exit(cx.listener(|this, _, _, cx| {
                if this.hovered.take().is_some() {
                    cx.notify();
                }
            }))
            .on_scroll_wheel(cx.listener(|this, event: &ScrollEvent, _, cx| {
                this.preview_scroll(event, cx);
            }))
            .child(
                canvas(
                    move |bounds, _, _| {
                        let vw = f32::from(bounds.size.width);
                        let vh = f32::from(bounds.size.height);
                        let (width, height, base_scale) =
                            canvas_dimensions(source.width, source.height, vw - 56.0, vh - 56.0);
                        let content_w = width * zoom;
                        let content_h = height * zoom;
                        let scale = base_scale * zoom;
                        let pan = preview_clamp_pan(pan_state, content_w, content_h, vw, vh);
                        let origin = point(
                            bounds.origin.x + (bounds.size.width - px(content_w)) / 2.0 + px(pan.0),
                            bounds.origin.y
                                + (bounds.size.height - px(content_h)) / 2.0
                                + px(pan.1),
                        );
                        if let Ok(mut metrics) = metrics.lock() {
                            *metrics = Some(PreviewMetrics {
                                viewport: bounds,
                                source,
                                base_scale,
                                origin,
                                scale,
                                pan,
                                content_w,
                                content_h,
                                zoom,
                            });
                        }
                        CanvasFrame {
                            origin,
                            width: content_w,
                            height: content_h,
                            scale,
                            pan,
                            viewport: bounds,
                        }
                    },
                    move |_, frame, window, _| {
                        let CanvasFrame {
                            origin,
                            width,
                            height,
                            scale,
                            pan,
                            viewport,
                        } = frame;
                        window.paint_quad(quad(
                            Bounds {
                                origin,
                                size: size(px(width), px(height)),
                            },
                            px(corner_radius),
                            surface,
                            px(1.),
                            frame_border,
                            Default::default(),
                        ));
                        if let Some(image) = screenshot.as_ref() {
                            let image_origin = point(
                                origin.x - px(source_left * scale),
                                origin.y - px(source_top * scale),
                            );
                            let image_size = size(
                                px(display_width as f32 * scale),
                                px(display_height as f32 * scale),
                            );
                            window
                                .paint_image(
                                    Bounds {
                                        origin,
                                        size: size(px(width), px(height)),
                                    },
                                    Bounds {
                                        origin: image_origin,
                                        size: image_size,
                                    },
                                    Corners::all(px(corner_radius)),
                                    image.clone(),
                                    0,
                                    false,
                                )
                                .ok();
                        }
                        if show_bounds {
                            for (_, node) in &nodes {
                                let b = node.bounds;
                                let active = selected.as_ref() == Some(&node.id);
                                let color = if active {
                                    selected_color
                                } else if hovered.as_ref() == Some(&node.id) {
                                    hover_color
                                } else {
                                    normal
                                };
                                let left = ((b.left as f32 - source_left) * scale).max(0.0);
                                let top = ((b.top as f32 - source_top) * scale).max(0.0);
                                let right = ((b.right as f32 - source_left) * scale).min(width);
                                let bottom = ((b.bottom as f32 - source_top) * scale).min(height);
                                if right <= left || bottom <= top {
                                    continue;
                                }
                                window.paint_quad(quad(
                                    Bounds {
                                        origin: point(origin.x + px(left), origin.y + px(top)),
                                        size: size(px(right - left), px(bottom - top)),
                                    },
                                    px(0.),
                                    transparent_black(),
                                    px(if active { 2.0 } else { 1.0 }),
                                    color,
                                    Default::default(),
                                ));
                            }
                        }
                        let vw = f32::from(viewport.size.width);
                        let vh = f32::from(viewport.size.height);
                        paint_preview_scrollbars(
                            window, viewport, vw, vh, width, height, pan, bar_track, bar_thumb,
                        );
                    },
                )
                .size_full(),
            )
            .into_any_element()
    }
}

fn section_header(language: Language, title: TextKey, colors: &StudioColors) -> Div {
    div()
        .h(px(29.))
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .px(px(12.))
        .border_b_1()
        .border_color(colors.border)
        .text_size(px(studio::FONT_SUPPORTING))
        .font_weight(FontWeight::BOLD)
        .text_color(colors.secondary_text)
        .child(crate::studio::selectable(language.text(title)))
}

fn risk_section_header(language: Language, title: TextKey, colors: &StudioColors) -> Div {
    section_header(language, title, colors).text_color(colors.warning)
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum DetailTone {
    Normal,
    Info,
    Warning,
}

struct DetailRow {
    label: String,
    value: String,
    tone: DetailTone,
}

impl DetailRow {
    fn new(label: impl Into<String>, value: impl Into<String>) -> Self {
        Self {
            label: label.into(),
            value: value.into(),
            tone: DetailTone::Normal,
        }
    }

    fn toned(label: impl Into<String>, value: impl Into<String>, tone: DetailTone) -> Self {
        Self {
            label: label.into(),
            value: value.into(),
            tone,
        }
    }
}

struct DetailSection {
    title: TextKey,
    rows: Vec<DetailRow>,
}

impl DetailSection {
    fn new(title: TextKey, rows: Vec<DetailRow>) -> Self {
        Self { title, rows }
    }
}

fn blend_tint(base: Rgba, tint: Rgba, amount: f32) -> Rgba {
    Rgba {
        r: base.r + (tint.r - base.r) * amount,
        g: base.g + (tint.g - base.g) * amount,
        b: base.b + (tint.b - base.b) * amount,
        a: base.a,
    }
}

fn detail_row(
    language: Language,
    title: TextKey,
    row: &DetailRow,
    index: usize,
    colors: &StudioColors,
) -> AnyElement {
    let color = match row.tone {
        DetailTone::Normal => colors.text,
        DetailTone::Info => colors.info,
        DetailTone::Warning => colors.warning,
    };
    // Compose DetailRowStripe: even rows use detailRowDeep, odd rows
    // detailRowLight; tone rows layer a 6% tone tint over the stripe.
    let stripe = if index % 2 == 0 {
        colors.detail_row_deep
    } else {
        colors.detail_row_light
    };
    let background = if row.tone == DetailTone::Normal {
        stripe
    } else {
        blend_tint(stripe, color, 0.06)
    };
    let value = row.value.clone();
    let copy_label = language.text(TextKey::InspectorCopyValue).to_owned();
    div()
        .id(ElementId::Name(
            format!("inspector-detail-row-{title:?}-{index}").into(),
        ))
        .w_full()
        .flex()
        .items_start()
        .gap(px(8.))
        .px(px(12.))
        .py(px(4.))
        .bg(background)
        .context_menu(move |menu, _, _| {
            let value = value.clone();
            menu.item(
                PopupMenuItem::new(copy_label.clone()).on_click(move |_, window, cx| {
                    let selected = TextSelection::selected_text(window, cx);
                    let text = if selected.is_empty() {
                        value.clone()
                    } else {
                        selected
                    };
                    cx.write_to_clipboard(ClipboardItem::new_string(text));
                }),
            )
        })
        .child(
            div()
                .w(px(DETAIL_LABEL_WIDTH))
                .flex_shrink_0()
                .text_size(px(studio::FONT_SUPPORTING))
                .text_color(colors.muted_text)
                .child(row.label.clone()),
        )
        .child(
            div()
                .flex_1()
                .min_w_0()
                .font_family("monospace")
                .text_size(px(studio::FONT_SUPPORTING))
                .text_color(color)
                .child(crate::studio::selectable(row.value.clone())),
        )
        .into_any_element()
}

fn detail_section(
    language: Language,
    colors: &StudioColors,
    section: &DetailSection,
    risk: bool,
) -> Div {
    let header = if risk {
        risk_section_header(language, section.title, colors)
    } else {
        section_header(language, section.title, colors)
    };
    let mut container = div()
        .w_full()
        .flex_shrink_0()
        .flex()
        .flex_col()
        .child(header);
    for (index, row) in section.rows.iter().enumerate() {
        container = container.child(detail_row(language, section.title, row, index, colors));
    }
    container
}

/// Kotlin \`Float.toString()\` equivalent for the protocol's float properties.
fn format_f32(value: f32) -> String {
    format!("{value:?}")
}

fn format_alpha(value: f64) -> String {
    format_f32(value as f32)
}

fn format_bounds(bounds: &LayoutBounds) -> String {
    format!(
        "{}, {}, {}, {}",
        bounds.left, bounds.top, bounds.right, bounds.bottom
    )
}

fn format_size(bounds: &LayoutBounds) -> String {
    format!(
        "{} × {}",
        (bounds.right - bounds.left).max(0),
        (bounds.bottom - bounds.top).max(0)
    )
}

fn format_insets(insets: Option<EdgeInsets>) -> String {
    insets.map_or_else(
        || "—".to_owned(),
        |insets| {
            format!(
                "{}, {}, {}, {}",
                insets.left, insets.top, insets.right, insets.bottom
            )
        },
    )
}

fn format_dimension(value: Option<i32>) -> String {
    match value {
        None => "—".to_owned(),
        Some(-1) => "MATCH_PARENT (-1)".to_owned(),
        Some(-2) => "WRAP_CONTENT (-2)".to_owned(),
        Some(value) => value.to_string(),
    }
}

fn format_pair_size(first: Option<i32>, second: Option<i32>) -> String {
    match (first, second) {
        (Some(first), Some(second)) => format!("{first} × {second}"),
        _ => "—".to_owned(),
    }
}

fn format_optional<T: ToString>(value: Option<T>) -> String {
    value.map_or_else(|| "—".to_owned(), |value| value.to_string())
}

fn unavailable(language: Language) -> String {
    language.text(TextKey::InspectorValueUnavailable).to_owned()
}

#[derive(Default)]
struct NodeStats {
    child_count: usize,
    descendants: usize,
    subtree_depth: usize,
    hidden_descendants: usize,
}

fn node_stats(nodes: &[LayoutNode], index: usize) -> NodeStats {
    let Some(node) = nodes.get(index) else {
        return NodeStats::default();
    };
    let mut stats = NodeStats {
        child_count: nodes
            .iter()
            .filter(|candidate| candidate.parent_index == Some(index))
            .count(),
        subtree_depth: 1,
        ..NodeStats::default()
    };
    let mut cursor = index + 1;
    while let Some(candidate) = nodes.get(cursor) {
        if candidate.depth <= node.depth {
            break;
        }
        stats.descendants += 1;
        if !candidate.visible || candidate.alpha <= 0.0 {
            stats.hidden_descendants += 1;
        }
        stats.subtree_depth = stats.subtree_depth.max(candidate.depth - node.depth + 1);
        cursor += 1;
    }
    stats
}

struct OverlapStats {
    pairs: usize,
    max_ratio: f32,
}

fn overlap_ratio(first: &LayoutBounds, second: &LayoutBounds) -> f32 {
    let width = (first.right.min(second.right) - first.left.max(second.left)).max(0) as i64;
    let height = (first.bottom.min(second.bottom) - first.top.max(second.top)).max(0) as i64;
    let first_area =
        (first.right - first.left).max(0) as i64 * (first.bottom - first.top).max(0) as i64;
    let second_area =
        (second.right - second.left).max(0) as i64 * (second.bottom - second.top).max(0) as i64;
    let smaller = first_area.min(second_area);
    if smaller == 0 {
        0.0
    } else {
        (width * height) as f32 / smaller as f32
    }
}

fn overlap_stats(nodes: &[LayoutNode], index: usize) -> OverlapStats {
    let children: Vec<&LayoutNode> = nodes
        .iter()
        .filter(|candidate| candidate.parent_index == Some(index))
        .collect();
    let mut stats = OverlapStats {
        pairs: 0,
        max_ratio: 0.0,
    };
    for first in 0..children.len() {
        for second in first + 1..children.len() {
            let ratio = overlap_ratio(&children[first].bounds, &children[second].bounds);
            if ratio >= 0.8 {
                stats.pairs += 1;
                stats.max_ratio = stats.max_ratio.max(ratio);
            }
        }
    }
    stats
}

fn identity_section(language: Language, node: &LayoutNode) -> DetailSection {
    DetailSection::new(
        TextKey::InspectorDetailSectionIdentity,
        vec![
            DetailRow::new(
                language.text(TextKey::InspectorDetailClass),
                node.class_name.clone(),
            ),
            DetailRow::new(language.text(TextKey::InspectorDetailId), node.id.clone()),
            DetailRow::new(
                language.text(TextKey::InspectorDetailResource),
                format_optional(node.resource_name.clone()),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailText),
                format_optional(node.text.clone()),
            ),
        ],
    )
}

fn layout_section(language: Language, nodes: &[LayoutNode], index: usize) -> DetailSection {
    let node = &nodes[index];
    let stats = node_stats(nodes, index);
    let attributes = &node.attributes;
    let visibility = attributes
        .visibility
        .clone()
        .unwrap_or_else(|| node.visible.to_string());
    DetailSection::new(
        TextKey::InspectorDetailSectionLayout,
        vec![
            DetailRow::new(
                language.text(TextKey::InspectorDetailBounds),
                format_bounds(&node.bounds),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailSize),
                format_size(&node.bounds),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailLocalLayoutBounds),
                attributes
                    .layout_bounds
                    .as_ref()
                    .map_or_else(|| "—".to_owned(), format_bounds),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailLocalLayoutSize),
                attributes
                    .layout_bounds
                    .as_ref()
                    .map_or_else(|| "—".to_owned(), format_size),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailVisibility),
                visibility,
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailTreeDepth),
                (node.depth + 1).to_string(),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailDirectChildren),
                stats.child_count.to_string(),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailDescendants),
                stats.descendants.to_string(),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailSubtreeDepth),
                stats.subtree_depth.to_string(),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailLayoutWidth),
                format_dimension(attributes.layout_width),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailLayoutHeight),
                format_dimension(attributes.layout_height),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailLayoutParamsClass),
                format_optional(attributes.layout_params_class.clone()),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailMeasuredSize),
                format_pair_size(attributes.measured_width, attributes.measured_height),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailMinimumSize),
                format_pair_size(attributes.min_width, attributes.min_height),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailPadding),
                format_insets(attributes.padding),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailMargin),
                format_insets(attributes.margin),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailScroll),
                match (attributes.scroll_x, attributes.scroll_y) {
                    (Some(x), Some(y)) => format!("{x}, {y}"),
                    _ => "—".to_owned(),
                },
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailLayoutRequested),
                format_optional(attributes.layout_requested),
            ),
        ],
    )
}

fn drawing_section(language: Language, node: &LayoutNode) -> DetailSection {
    let attributes = &node.attributes;
    let translation = attributes.translation_x.map(|x| {
        format!(
            "{}, {}, {}",
            format_f32(x),
            format_f32(attributes.translation_y.unwrap_or(0.0)),
            format_f32(attributes.translation_z.unwrap_or(0.0))
        )
    });
    let rotation = attributes.rotation.map(|z| {
        format!(
            "{}, {}, {}",
            format_f32(attributes.rotation_x.unwrap_or(0.0)),
            format_f32(attributes.rotation_y.unwrap_or(0.0)),
            format_f32(z)
        )
    });
    let scale = attributes.scale_x.map(|x| {
        format!(
            "{}, {}",
            format_f32(x),
            format_f32(attributes.scale_y.unwrap_or(1.0))
        )
    });
    let pivot = attributes.pivot_x.map(|x| {
        format!(
            "{}, {}",
            format_f32(x),
            format_f32(attributes.pivot_y.unwrap_or(0.0))
        )
    });
    DetailSection::new(
        TextKey::InspectorDetailSectionDrawing,
        vec![
            DetailRow::new(
                language.text(TextKey::InspectorDetailAlpha),
                format_alpha(node.alpha),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailZ),
                format_optional(attributes.z.map(format_f32)),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailElevation),
                format_optional(attributes.elevation.map(format_f32)),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailTranslation),
                format_optional(translation),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailRotation),
                format_optional(rotation),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailScale),
                format_optional(scale),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailPivot),
                format_optional(pivot),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailBackground),
                format_optional(attributes.background.clone()),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailBackgroundColor),
                format_optional(attributes.background_color.clone()),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailForeground),
                format_optional(attributes.foreground.clone()),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailClipBounds),
                attributes
                    .clip_bounds
                    .as_ref()
                    .map_or_else(|| "—".to_owned(), format_bounds),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailClipChildren),
                format_optional(attributes.clip_children),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailClipToPadding),
                format_optional(attributes.clip_to_padding),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailOpaque),
                format_optional(attributes.opaque),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailWillNotDraw),
                format_optional(attributes.will_not_draw),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailHardwareAccelerated),
                format_optional(attributes.hardware_accelerated),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailLayerType),
                format_optional(attributes.layer_type.clone()),
            ),
        ],
    )
}

fn interaction_section(language: Language, node: &LayoutNode) -> DetailSection {
    let attributes = &node.attributes;
    DetailSection::new(
        TextKey::InspectorDetailSectionInteraction,
        vec![
            DetailRow::new(
                language.text(TextKey::InspectorDetailContentDescription),
                format_optional(attributes.content_description.clone()),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailSemanticsRole),
                format_optional(node.semantics_role.clone()),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailEnabled),
                format_optional(attributes.enabled),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailClickable),
                format_optional(attributes.clickable),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailLongClickable),
                format_optional(attributes.long_clickable),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailFocusable),
                format_optional(attributes.focusable),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailFocused),
                format_optional(attributes.focused),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailSelected),
                format_optional(attributes.selected),
            ),
        ],
    )
}

fn risk_section(language: Language, nodes: &[LayoutNode], index: usize) -> DetailSection {
    let node = &nodes[index];
    let stats = node_stats(nodes, index);
    let overlap = overlap_stats(nodes, index);
    let overdraw = if overlap.pairs == 0 {
        language
            .text(TextKey::InspectorNoHighOverlapPairs)
            .to_owned()
    } else {
        language.format(
            TextKey::InspectorOverlapPairs,
            &[
                ("pairs", &overlap.pairs),
                ("ratio", &((overlap.max_ratio * 100.0).round() as i32)),
            ],
        )
    };
    let complexity = language.format(
        TextKey::InspectorSubtreeComplexity,
        &[
            ("descendants", &stats.descendants),
            ("depth", &stats.subtree_depth),
        ],
    );
    let blending = if node.alpha < 1.0 {
        language.format(
            TextKey::InspectorBlendingAlpha,
            &[("alpha", &format_alpha(node.alpha))],
        )
    } else {
        language.text(TextKey::InspectorAlphaOne).to_owned()
    };
    let layer_cost = node
        .attributes
        .layer_type
        .clone()
        .unwrap_or_else(|| unavailable(language));
    DetailSection::new(
        TextKey::InspectorDetailSectionRenderRisks,
        vec![
            DetailRow::toned(
                language.text(TextKey::InspectorDetailOverdrawEstimate),
                overdraw,
                if overlap.pairs > 0 {
                    DetailTone::Warning
                } else {
                    DetailTone::Normal
                },
            ),
            DetailRow::toned(
                language.text(TextKey::InspectorDetailSubtreeComplexity),
                complexity,
                if stats.subtree_depth > 10 || stats.descendants > 50 {
                    DetailTone::Warning
                } else {
                    DetailTone::Normal
                },
            ),
            DetailRow::toned(
                language.text(TextKey::InspectorDetailHiddenDescendants),
                stats.hidden_descendants.to_string(),
                if stats.hidden_descendants > 0 {
                    DetailTone::Info
                } else {
                    DetailTone::Normal
                },
            ),
            DetailRow::toned(
                language.text(TextKey::InspectorDetailBlending),
                blending,
                if node.alpha < 1.0 {
                    DetailTone::Warning
                } else {
                    DetailTone::Normal
                },
            ),
            DetailRow::toned(
                language.text(TextKey::InspectorDetailLayerCost),
                layer_cost,
                if node.attributes.layer_type.as_deref() == Some("SOFTWARE") {
                    DetailTone::Warning
                } else {
                    DetailTone::Normal
                },
            ),
        ],
    )
}

fn raw_properties_section(node: &LayoutNode) -> Option<DetailSection> {
    let mut properties = node.attributes.raw_properties.clone();
    properties.extend(node.attributes.semantic_properties.clone());
    if properties.is_empty() {
        return None;
    }
    Some(DetailSection {
        title: TextKey::InspectorDetailSectionRawProperties,
        rows: properties
            .into_iter()
            .map(|(label, value)| DetailRow::new(label, value))
            .collect(),
    })
}

const COMPOSE_NODE_ID_PREFIX: &str = "compose-inspection:";
const MAX_COMPOSE_VALUE_ROWS: usize = 512;
const MAX_COMPOSE_LABEL_BYTES: usize = 512;

fn compose_node_id(id: &str) -> Option<i64> {
    id.strip_prefix(COMPOSE_NODE_ID_PREFIX)
        .unwrap_or(id)
        .parse()
        .ok()
}

fn clipped_compose_label(text: &str) -> String {
    if text.len() <= MAX_COMPOSE_LABEL_BYTES {
        return text.to_owned();
    }
    let mut end = MAX_COMPOSE_LABEL_BYTES - 1;
    while end > 0 && !text.is_char_boundary(end) {
        end -= 1;
    }
    format!("{}…", &text[..end])
}

fn compose_value_rows(
    values: &[ComposeValueEvidence],
    rows: &mut Vec<DetailRow>,
    prefix: Option<&str>,
    depth: usize,
    remaining: &mut usize,
) {
    if depth > 8 || *remaining == 0 {
        return;
    }
    for (index, value) in values.iter().enumerate() {
        if *remaining == 0 {
            return;
        }
        let segment = if value.name.is_empty() {
            index.to_string()
        } else {
            value.name.clone()
        };
        let full_label = match prefix {
            None => segment,
            Some(prefix) => format!("{prefix}.{segment}"),
        };
        let label = clipped_compose_label(&full_label);
        let mut text = value.type_name.clone();
        if let Some(value_text) = &value.value {
            text.push_str(" · ");
            text.push_str(value_text);
        }
        if value.truncated {
            text.push_str(" · <truncated>");
        }
        rows.push(DetailRow::toned(
            label,
            text,
            if value.truncated {
                DetailTone::Info
            } else {
                DetailTone::Normal
            },
        ));
        *remaining -= 1;
        if !value.elements.is_empty() {
            compose_value_rows(
                &value.elements,
                rows,
                Some(&full_label),
                depth + 1,
                remaining,
            );
        }
    }
}

fn push_compose_values_section(
    sections: &mut Vec<DetailSection>,
    title: TextKey,
    values: &[ComposeValueEvidence],
) {
    if values.is_empty() {
        return;
    }
    let mut rows = Vec::new();
    let mut remaining = MAX_COMPOSE_VALUE_ROWS;
    compose_value_rows(values, &mut rows, None, 0, &mut remaining);
    if !rows.is_empty() {
        sections.push(DetailSection::new(title, rows));
    }
}

fn compose_summary_section(
    language: Language,
    evidence: Option<&ComposeEvidence>,
    node: &LayoutNode,
) -> Option<DetailSection> {
    let evidence = evidence?;
    let node_id = compose_node_id(&node.id)?;
    let metadata = evidence.nodes.get(&node_id)?;
    let detail = evidence.details.get(&node_id);
    let mut rows = Vec::new();
    if let Some(source) = &metadata.source {
        rows.push(DetailRow::new(
            language.text(TextKey::InspectorDetailComposeSummarySource),
            format!("{}:{}", source.file_name, source.line_number),
        ));
    }
    rows.push(DetailRow::new(
        language.text(TextKey::InspectorDetailComposeSummaryParameters),
        detail
            .map_or(0, |detail| detail.parameters.len())
            .to_string(),
    ));
    rows.push(DetailRow::new(
        language.text(TextKey::InspectorDetailComposeSummaryModifiers),
        detail
            .map_or(0, |detail| detail.modifiers.len())
            .to_string(),
    ));
    rows.push(DetailRow::new(
        language.text(TextKey::InspectorDetailComposeSummarySemantics),
        format!(
            "{} / {}",
            detail.map_or(0, |detail| detail.merged_semantics.len()),
            detail.map_or(0, |detail| detail.unmerged_semantics.len())
        ),
    ));
    Some(DetailSection::new(
        TextKey::InspectorDetailSectionComposeSummary,
        rows,
    ))
}

fn compose_sections(
    language: Language,
    evidence: &ComposeEvidence,
    node: &LayoutNode,
) -> Vec<DetailSection> {
    let mut sections = vec![DetailSection::new(
        TextKey::InspectorDetailSectionComposeInspection,
        vec![
            DetailRow::new(
                language.text(TextKey::InspectorDetailComposeMode),
                evidence.mode.clone(),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailComposeGeneration),
                evidence.generation.to_string(),
            ),
            DetailRow::new(
                language.text(TextKey::InspectorDetailComposeCompleteness),
                evidence.declared_completeness.clone(),
            ),
        ],
    )];
    let Some(node_id) = compose_node_id(&node.id) else {
        return sections;
    };
    let Some(metadata) = evidence.nodes.get(&node_id) else {
        return sections;
    };
    if let Some(source) = &metadata.source {
        sections.push(DetailSection::new(
            TextKey::InspectorDetailSectionComposeSource,
            vec![
                DetailRow::new(
                    language.text(TextKey::InspectorDetailComposeFile),
                    source.file_name.clone(),
                ),
                DetailRow::new(
                    language.text(TextKey::InspectorDetailComposeLine),
                    source.line_number.to_string(),
                ),
                DetailRow::new(
                    language.text(TextKey::InspectorDetailComposeOffset),
                    source.offset.to_string(),
                ),
                DetailRow::new(
                    language.text(TextKey::InspectorDetailComposePackageHash),
                    source.package_hash.to_string(),
                ),
            ],
        ));
    }
    if metadata.recompose_count.is_some() || metadata.skip_count.is_some() {
        sections.push(DetailSection::new(
            TextKey::InspectorDetailSectionRecomposition,
            vec![
                DetailRow::new(
                    language.text(TextKey::InspectorDetailRecomposeCount),
                    format_optional(metadata.recompose_count),
                ),
                DetailRow::new(
                    language.text(TextKey::InspectorDetailSkipCount),
                    format_optional(metadata.skip_count),
                ),
            ],
        ));
    }
    if let Some(reads) = evidence.state_reads.get(&metadata.anchor_hash) {
        let mut rows = Vec::new();
        for read in reads.iter().take(MAX_COMPOSE_VALUE_ROWS) {
            let mut value = match (&read.value_name, &read.value_text) {
                (Some(name), Some(text)) => format!("{name} = {text}"),
                (Some(name), None) => name.clone(),
                (None, Some(text)) => text.clone(),
                (None, None) => unavailable(language),
            };
            if read.invalidated {
                value.push_str(" · invalidated");
            }
            if let Some(frame) = &read.top_frame {
                value.push_str(" · ");
                value.push_str(frame);
            }
            rows.push(DetailRow::new(
                language.format(
                    TextKey::InspectorRecompositionLabel,
                    &[("number", &read.recomposition_number)],
                ),
                value,
            ));
        }
        if !rows.is_empty() {
            sections.push(DetailSection::new(
                TextKey::InspectorDetailSectionStateReads,
                rows,
            ));
        }
    }
    if let Some(detail) = evidence.details.get(&node_id) {
        push_compose_values_section(
            &mut sections,
            TextKey::InspectorDetailSectionComposeParameters,
            &detail.parameters,
        );
        push_compose_values_section(
            &mut sections,
            TextKey::InspectorDetailSectionComposeModifiers,
            &detail.modifiers,
        );
        push_compose_values_section(
            &mut sections,
            TextKey::InspectorDetailSectionMergedSemantics,
            &detail.merged_semantics,
        );
        push_compose_values_section(
            &mut sections,
            TextKey::InspectorDetailSectionUnmergedSemantics,
            &detail.unmerged_semantics,
        );
    }
    let mut coverage_rows = Vec::new();
    for coverage in evidence
        .coverage
        .iter()
        .filter(|coverage| coverage.node_id == node_id)
        .take(MAX_COMPOSE_VALUE_ROWS)
    {
        let mut text = format!(
            "{} · depth {} · {}",
            coverage.state, coverage.recursion_depth, coverage.loaded_elements
        );
        if let Some(total) = coverage.total_elements {
            text.push('/');
            text.push_str(&total.to_string());
        }
        coverage_rows.push(DetailRow::new(coverage.field.clone(), text));
    }
    if !coverage_rows.is_empty() {
        sections.push(DetailSection::new(
            TextKey::InspectorDetailSectionDetailCoverage,
            coverage_rows,
        ));
    }
    sections
}

fn details_pane(
    language: Language,
    colors: &StudioColors,
    archive: Option<&Arc<InspectorArchive>>,
    window_index: usize,
    width: f32,
    selected_index: Option<usize>,
    tab: DetailsTab,
    selected_id: &Option<String>,
    compose_agent: bool,
    cx: &mut Context<InspectorWorkspace>,
) -> AnyElement {
    let nodes = archive
        .and_then(|archive| archive.windows.get(window_index))
        .map(|window| window.nodes.as_slice());
    let compose_evidence = archive.and_then(|archive| archive.compose.as_ref());
    let compose_enabled = compose_agent
        && compose_evidence.is_some()
        && selected_index
            .and_then(|index| nodes.and_then(|nodes| nodes.get(index)))
            .is_some_and(|node| node.kind == NodeKind::Compose);
    let visible_tabs: Vec<DetailsTab> = DetailsTab::ALL
        .into_iter()
        .filter(|candidate| *candidate != DetailsTab::Compose || compose_enabled)
        .collect();
    let active_tab = if visible_tabs.contains(&tab) {
        tab
    } else {
        DetailsTab::Overview
    };
    let trailing = div()
        .text_size(px(studio::FONT_SUPPORTING))
        .text_color(colors.muted_text)
        .whitespace_nowrap()
        .child(selected_id.clone().unwrap_or_else(|| "-".to_owned()));
    let header = panel_header(
        language,
        TextKey::InspectorPropertiesTitle,
        TextKey::InspectorPropertiesLabel,
        trailing,
        colors,
    );

    // Compose uses the same COMPACT_OUTLINED segmented selector as the toolbar
    // scan control, stretched to equal-width items across the properties pane.
    let mut tab_track = segmented_track("inspector-details-tabs", colors).w_full();
    for (tab_index, candidate) in visible_tabs.into_iter().enumerate() {
        if tab_index > 0 {
            tab_track = tab_track.child(segmented_divider(colors));
        }
        tab_track = tab_track.child(
            segmented_item(
                ("inspector-details-tab", tab_index),
                candidate.label(language),
                candidate == active_tab,
                true,
                colors,
            )
            .flex_1()
            .min_w_0()
            .on_click(cx.listener(move |this, _, _, cx| {
                this.details_tab = candidate;
                cx.notify();
            })),
        );
    }
    let tabs = div()
        .h(px(30.))
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .border_b_1()
        .border_color(colors.border)
        .child(tab_track);

    let mut content = div()
        .id("inspector-properties-rows")
        .flex_1()
        .min_h_0()
        .w_full()
        .overflow_y_scroll()
        .flex()
        .flex_col();
    match (nodes, selected_index) {
        (Some(nodes), Some(index)) if index < nodes.len() => {
            let node = &nodes[index];
            match active_tab {
                DetailsTab::Overview => {
                    content = content.child(detail_section(
                        language,
                        colors,
                        &identity_section(language, node),
                        false,
                    ));
                    if compose_enabled {
                        if let Some(section) =
                            compose_summary_section(language, compose_evidence, node)
                        {
                            content =
                                content.child(detail_section(language, colors, &section, false));
                        }
                    }
                }
                DetailsTab::Layout => {
                    content = content.child(detail_section(
                        language,
                        colors,
                        &layout_section(language, nodes, index),
                        false,
                    ));
                }
                DetailsTab::Drawing => {
                    content = content.child(detail_section(
                        language,
                        colors,
                        &drawing_section(language, node),
                        false,
                    ));
                }
                DetailsTab::Accessibility => {
                    content = content.child(detail_section(
                        language,
                        colors,
                        &interaction_section(language, node),
                        false,
                    ));
                }
                DetailsTab::Compose => {
                    if let Some(evidence) = compose_evidence {
                        for section in compose_sections(language, evidence, node) {
                            content =
                                content.child(detail_section(language, colors, &section, false));
                        }
                    }
                }
                DetailsTab::Evidence => {
                    content = content.child(detail_section(
                        language,
                        colors,
                        &risk_section(language, nodes, index),
                        true,
                    ));
                    if let Some(section) = raw_properties_section(node) {
                        content = content.child(detail_section(language, colors, &section, false));
                    }
                }
            }
        }
        _ => {
            content = content.child(
                div()
                    .flex_1()
                    .min_h_0()
                    .flex()
                    .items_center()
                    .justify_center()
                    .p(px(12.))
                    .text_size(px(studio::FONT_SUPPORTING))
                    .text_color(colors.muted_text)
                    .child(crate::studio::selectable(
                        language.text(TextKey::InspectorNoNodeSelected),
                    )),
            );
        }
    }

    div()
        .id("inspector-properties-pane")
        .w(px(width))
        .h_full()
        .flex_shrink_0()
        .flex()
        .flex_col()
        .min_h_0()
        .overflow_hidden()
        .bg(colors.panel)
        .child(header)
        .child(tabs)
        .child(content)
        .into_any_element()
}

fn findings_chip(
    id: impl Into<ElementId>,
    label: String,
    tone: Rgba,
    selected: bool,
    colors: &StudioColors,
) -> Button {
    let _ = colors;
    Button::new(id)
        .ghost()
        .h(px(18.))
        .px(px(8.))
        .rounded_full()
        .bg(tone.alpha(if selected { 0.28 } else { 0.10 }))
        .border_1()
        .border_color(tone.alpha(if selected { 0.9 } else { 0.35 }))
        .text_color(tone)
        .selected(selected)
        .child(
            div()
                .text_size(px(studio::FONT_SUPPORTING - 1.0))
                .whitespace_nowrap()
                .child(crate::studio::selectable(label)),
        )
}

fn findings_table_header(language: Language, colors: &StudioColors) -> Div {
    div()
        .h(px(28.))
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .gap(px(8.))
        .px(px(16.))
        .border_b_1()
        .border_color(colors.border)
        .text_size(px(studio::FONT_SUPPORTING))
        .text_color(colors.secondary_text)
        .child(
            div()
                .w(px(100.))
                .flex_shrink_0()
                .child(crate::studio::selectable(
                    language.text(TextKey::InspectorFindingsSeverity),
                )),
        )
        .child(div().flex_1().min_w_0().child(crate::studio::selectable(
            language.text(TextKey::InspectorFindingsProblem),
        )))
        .child(div().flex_1().min_w_0().child(crate::studio::selectable(
            language.text(TextKey::InspectorFindingsNode),
        )))
        .child(div().flex_1().min_w_0().child(crate::studio::selectable(
            language.text(TextKey::InspectorFindingsSuggestion),
        )))
}

fn findings_pane(
    language: Language,
    colors: &StudioColors,
    tone: FindingsTone,
    height: f32,
    cx: &mut Context<InspectorWorkspace>,
) -> AnyElement {
    let mut header = div()
        .h(px(PANEL_HEADER_HEIGHT))
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .gap(px(8.))
        .px(px(16.))
        .border_b_1()
        .border_color(colors.border)
        .child(
            div()
                .text_size(px(studio::FONT_SUPPORTING))
                .font_weight(FontWeight::BOLD)
                .text_color(colors.secondary_text)
                .child(crate::studio::selectable(
                    language.text(TextKey::InspectorFindings),
                )),
        )
        .child(
            div()
                .text_size(px(studio::FONT_SUPPORTING - 1.0))
                .text_color(colors.muted_text)
                .child(crate::studio::selectable(
                    language.text(TextKey::InspectorFindingsLabel),
                )),
        );
    for (tone_index, candidate) in FindingsTone::ALL.into_iter().enumerate() {
        let selected = candidate == tone;
        header = header.child(
            findings_chip(
                ("inspector-findings-chip", tone_index),
                candidate.label(language),
                candidate.color(colors),
                selected,
                colors,
            )
            .on_click(cx.listener(move |this, _, _, cx| {
                this.findings_tone = candidate;
                cx.notify();
            })),
        );
    }
    header = header
        .child(div().flex_1())
        .child(
            div()
                .text_size(px(studio::FONT_SUPPORTING))
                .text_color(colors.muted_text)
                .whitespace_nowrap()
                .child(crate::studio::selectable(
                    language.text(TextKey::InspectorTimelineLive),
                )),
        )
        .child(
            toolbar_button(
                "inspector-run-ai",
                language.text(TextKey::InspectorRunAi),
                true,
                false,
                colors,
            )
            .on_click(cx.listener(|this, _, window, cx| this.prepare_ai(window, cx))),
        );

    div()
        .id("inspector-findings-pane")
        .h(px(height))
        .w_full()
        .flex_shrink_0()
        .flex()
        .flex_col()
        .overflow_hidden()
        .bg(colors.panel)
        .child(header)
        .child(findings_table_header(language, colors))
        .child(
            div()
                .flex_1()
                .min_h_0()
                .flex()
                .flex_col()
                .items_center()
                .justify_center()
                .gap(px(6.))
                .px(px(24.))
                .text_size(px(studio::FONT_SUPPORTING))
                .text_color(colors.muted_text)
                .child(crate::studio::selectable(
                    language.text(TextKey::InspectorFindingsEmpty),
                )),
        )
        .into_any_element()
}

/// Menu-relevant Layout Inspector state: the native menu enables tree
/// navigation only while a node is selected and ignores archive operations while
/// an import is in flight.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct InspectorMenuState {
    pub busy: bool,
    pub has_selection: bool,
    pub has_archive: bool,
}

impl InspectorWorkspace {
    pub fn menu_state(&self) -> InspectorMenuState {
        InspectorMenuState {
            busy: self.busy,
            has_selection: self.selected.is_some(),
            has_archive: self.archive.is_some(),
        }
    }

    /// Selects the adjacent visible hierarchy row, mirroring Compose
    /// HierarchyTreeState.adjacentNodeId (collapsed subtrees and hidden
    /// invisible views are skipped because they are not rows).
    pub(crate) fn select_previous_node(&mut self, cx: &mut Context<Self>) {
        self.select_adjacent_node(false, cx);
    }

    pub(crate) fn select_next_node(&mut self, cx: &mut Context<Self>) {
        self.select_adjacent_node(true, cx);
    }

    fn select_adjacent_node(&mut self, forward: bool, cx: &mut Context<Self>) {
        let rows = self.visible_rows(cx);
        if rows.is_empty() {
            return;
        }
        let Some(archive) = self.archive.as_ref() else {
            return;
        };
        let Some(window) = archive.windows.get(self.window) else {
            return;
        };
        let current = self.selected.as_deref().and_then(|selected| {
            rows.iter()
                .position(|index| window.nodes[*index].id == selected)
        });
        let next = match (current, forward) {
            (None, _) => 0,
            (Some(index), true) => (index + 1) % rows.len(),
            (Some(index), false) => (index + rows.len() - 1) % rows.len(),
        };
        let Some(node) = window.nodes.get(rows[next]) else {
            return;
        };
        self.selected = Some(node.id.clone());
        self.hovered = None;
        cx.notify();
    }

    /// Expands or collapses the selected node, matching Compose
    /// TOGGLE_SELECTED_NODE (toggleExpandable), not layer visibility.
    pub(crate) fn toggle_selected_node(&mut self, cx: &mut Context<Self>) {
        let Some(id) = self.selected.clone() else {
            return;
        };
        self.toggle_collapsed(id, cx);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_layout_inspector::{Bounds as NodeBounds, DisplayInfo, LayoutWindow, NodeKind};

    fn node(id: usize, parent: Option<usize>) -> LayoutNode {
        LayoutNode {
            id: id.to_string(),
            kind: NodeKind::View,
            class_name: "View".into(),
            parent_index: parent,
            depth: usize::from(parent.is_some()),
            bounds: NodeBounds {
                left: 0,
                top: 0,
                right: 10,
                bottom: 10,
            },
            visible: true,
            alpha: 1.0,
            resource_name: None,
            text: None,
            semantics_role: None,
            attributes: Default::default(),
        }
    }
    #[::core::prelude::v1::test]
    fn ancestor_visibility_alpha_and_user_hidden_prune_descendants() {
        let mut nodes = vec![node(0, None), node(1, Some(0)), node(2, None)];
        for mode in 0..3 {
            nodes[0].visible = mode != 0;
            nodes[0].alpha = if mode == 1 { 0.0 } else { 1.0 };
            let hidden = if mode == 2 {
                BTreeSet::from(["0".into()])
            } else {
                BTreeSet::new()
            };
            let (visible, total) = canvas_nodes(&nodes, &hidden, None, 100, 100);
            assert_eq!(total, 1);
            assert_eq!(visible[0].0, 2);
        }
    }
    #[::core::prelude::v1::test]
    fn invalid_bounds_do_not_prune_children_and_selected_survives_budget() {
        let mut nodes = vec![node(0, None), node(1, Some(0))];
        nodes[0].bounds.right = 0;
        let (visible, _) = canvas_nodes(&nodes, &BTreeSet::new(), None, 100, 100);
        assert_eq!(visible[0].0, 1);
        let nodes: Vec<_> = (0..=MAX_CANVAS_NODES).map(|i| node(i, None)).collect();
        let selected = MAX_CANVAS_NODES.to_string();
        let (visible, total) = canvas_nodes(&nodes, &BTreeSet::new(), Some(&selected), 100, 100);
        assert_eq!(total, MAX_CANVAS_NODES + 1);
        assert_eq!(visible.len(), MAX_CANVAS_NODES);
        assert_eq!(visible.last().unwrap().0, MAX_CANVAS_NODES);
    }
    #[::core::prelude::v1::test]
    fn hit_order_respects_smallest_area_and_reverse_paint_ties() {
        let mut small = node(1, None);
        small.bounds.right = 5;
        small.bounds.bottom = 5;
        let large = node(2, None);
        let nodes = vec![(0, small.clone()), (1, large)];
        assert_eq!(canvas_hit(&nodes, 2.0, 2.0, true).as_deref(), Some("1"));
        assert_eq!(canvas_hit(&nodes, 2.0, 2.0, false).as_deref(), Some("2"));
        let mut tied = small.clone();
        tied.id = "3".into();
        let nodes = vec![(0, small), (1, tied)];
        assert_eq!(canvas_hit(&nodes, 2.0, 2.0, true).as_deref(), Some("3"));
        assert_eq!(canvas_hit(&nodes, 5.0, 2.0, true), None);
        assert_eq!(canvas_hit(&nodes, f32::NAN, 2.0, true), None);
    }

    fn window_with_bounds(bounds: NodeBounds) -> LayoutWindow {
        LayoutWindow {
            id: "window-0".into(),
            kind: "app".into(),
            bounds,
            nodes: Vec::new(),
        }
    }

    fn test_display() -> DisplayInfo {
        DisplayInfo {
            width_px: 1080,
            height_px: 2400,
            density: 2.625,
        }
    }

    #[::core::prelude::v1::test]
    fn preview_source_crops_to_app_bounds_only_when_app_only_is_on() {
        let display = test_display();
        let window = window_with_bounds(NodeBounds {
            left: 0,
            top: 120,
            right: 1080,
            bottom: 2200,
        });
        assert_eq!(
            preview_source(display, Some(&window), true),
            PreviewSource {
                left: 0,
                top: 120,
                width: 1080,
                height: 2080,
            }
        );
        assert_eq!(
            preview_source(display, Some(&window), false),
            PreviewSource {
                left: 0,
                top: 0,
                width: 1080,
                height: 2400,
            }
        );
        assert_eq!(
            preview_source(display, None, true),
            PreviewSource {
                left: 0,
                top: 0,
                width: 1080,
                height: 2400,
            }
        );
    }

    #[::core::prelude::v1::test]
    fn preview_source_clamps_app_bounds_and_falls_back_when_empty() {
        let display = test_display();
        let clamped = window_with_bounds(NodeBounds {
            left: -50,
            top: -10,
            right: 900,
            bottom: 2000,
        });
        assert_eq!(
            preview_source(display, Some(&clamped), true),
            PreviewSource {
                left: 0,
                top: 0,
                width: 900,
                height: 2000,
            }
        );
        let empty = window_with_bounds(NodeBounds {
            left: 500,
            top: 500,
            right: 400,
            bottom: 400,
        });
        assert_eq!(
            preview_source(display, Some(&empty), true),
            PreviewSource {
                left: 0,
                top: 0,
                width: 1080,
                height: 2400,
            }
        );
    }

    #[::core::prelude::v1::test]
    fn preview_header_exposes_canvas_mode_and_hit_order_chips() {
        let source = include_str!("inspector.rs");
        assert!(source.contains("\"inspector-app-only\""));
        assert!(source.contains("\"inspector-hit-order\""));
        assert!(source.contains("fn preview_source("));
    }

    #[::core::prelude::v1::test]
    fn preview_zoom_controls_click_everywhere_and_reset_from_the_scale_label() {
        let source = include_str!("inspector.rs");
        // Exclude the test module so an assertion cannot satisfy itself.
        let ui = source.split("mod tests").next().unwrap_or(source);
        // The minus/plus glyphs used to be selectable runs, which swallowed the
        // button click and left only the 24px padding active.
        assert!(ui.contains(r#".child("−")"#));
        assert!(!ui.contains(r#"selectable("−")"#));
        assert!(!ui.contains(r#"selectable("+")"#));
        assert!(ui.contains("inspector-zoom-reset"));
        assert!(ui.contains("fn set_zoom("));
        assert!(ui.contains("this.zoom_reset(cx)"));
        // Wheel and pinch gestures share the same clamped 50%-250% range.
        assert!(ui.contains(".on_pinch("));
        assert!(ui.contains("fn zoom_by("));
    }

    #[::core::prelude::v1::test]
    fn hierarchy_preferences_hide_indices_and_resource_ids_not_evidence() {
        let mut sample = node(0, None);
        sample.resource_name = Some("app:id/content".into());
        let mut preferences = LayoutPreferences::default();
        assert_eq!(
            hierarchy_label(&sample, "2-3", preferences),
            "2-3  id/content  View"
        );
        preferences.hide_indices = true;
        assert_eq!(
            hierarchy_label(&sample, "2-3", preferences),
            "id/content  View"
        );
        preferences.show_ids = false;
        assert_eq!(hierarchy_label(&sample, "2-3", preferences), "View");
        assert_eq!(sample.id, "0");
        assert_eq!(sample.resource_name.as_deref(), Some("app:id/content"));
    }

    #[::core::prelude::v1::test]
    fn hierarchy_numbers_use_per_depth_preorder() {
        let mut nodes = vec![
            node(0, None),
            node(1, Some(0)),
            node(2, Some(0)),
            node(3, Some(1)),
            node(4, Some(2)),
        ];
        nodes[0].depth = 0;
        nodes[1].depth = 1;
        nodes[2].depth = 1;
        nodes[3].depth = 2;
        nodes[4].depth = 2;
        assert_eq!(
            hierarchy_numbers(&nodes),
            vec!["0-0", "1-0", "1-1", "2-0", "2-1"]
        );
    }

    #[::core::prelude::v1::test]
    fn preview_pan_clamps_to_content_overflow() {
        // Content that fits is always centred.
        assert_eq!(
            preview_clamp_pan((50.0, -50.0), 100.0, 100.0, 200.0, 200.0),
            (0.0, 0.0)
        );
        // Content 400x300 in a 200x200 viewport allows +-100/+ -50 pans.
        assert_eq!(
            preview_clamp_pan((1000.0, -1000.0), 400.0, 300.0, 200.0, 200.0),
            (100.0, -50.0)
        );
        assert_eq!(
            preview_pan_limits(400.0, 300.0, 200.0, 200.0),
            (100.0, 50.0)
        );
    }

    #[::core::prelude::v1::test]
    fn tall_display_is_scaled_not_cropped() {
        let (width, height, scale) = canvas_dimensions(100, 10_000, 440.0, 500.0);
        assert!(width <= 440.0 && height <= 500.0);
        assert!((height - 10_000.0 * scale).abs() < 0.01);
    }

    #[::core::prelude::v1::test]
    fn canvas_fits_portrait_landscape_and_zero_sized_viewports() {
        for (display_width, display_height) in [(1080, 2400), (2400, 1080)] {
            for (viewport_width, viewport_height) in [(160.0, 300.0), (640.0, 480.0), (0.0, 0.0)] {
                let (width, height, scale) = canvas_dimensions(
                    display_width,
                    display_height,
                    viewport_width,
                    viewport_height,
                );
                assert!(width >= 0.0 && width <= viewport_width + 0.01);
                assert!(height >= 0.0 && height <= viewport_height + 0.01);
                assert!(scale.is_finite());
            }
        }
    }

    #[::core::prelude::v1::test]
    fn inspector_empty_state_keeps_all_workspace_panes() {
        let source = include_str!("inspector.rs");
        let render = source
            .split("impl Render for InspectorWorkspace")
            .nth(1)
            .unwrap()
            .split("/// Preorder ancestry")
            .next()
            .unwrap();
        for component in [
            "hierarchy_pane(",
            "self.preview_pane(",
            "details_pane(",
            "status_bar(",
        ] {
            assert!(
                render.contains(component),
                "Missing workspace component: {component}"
            );
        }
        assert!(
            !render.contains("return content"),
            "Empty/error states must not bypass the workspace"
        );
    }

    #[::core::prelude::v1::test]
    fn inspector_has_header_toolbar_without_duplicate_import_or_title_rows() {
        let source = include_str!("inspector.rs");
        let render = source
            .split("impl Render for InspectorWorkspace")
            .nth(1)
            .unwrap()
            .split("/// Preorder ancestry")
            .next()
            .unwrap();
        assert!(render.contains(".child(header_toolbar)"));
        assert!(!source.contains(".id(\"inspector-toolbar\")"));
        assert!(source.contains(".id(\"inspector-header-toolbar\")"));
        let header = source
            .split("fn header_toolbar(")
            .nth(1)
            .unwrap()
            .split("fn toggle_hidden(")
            .next()
            .unwrap();
        assert!(!header.contains("inspector-import"));
        assert!(header.contains("inspector-toggle-hierarchy"));
        assert!(header.contains("inspector-toggle-findings"));
        assert!(header.contains("inspector-toggle-details"));
        let shell = include_str!("shell.rs");
        assert!(shell.contains("selected != Destination::LayoutInspector"));
        // The menu strip is drawn inside the window and the shell publishes the
        // inspector commands; the page itself owns no menu code anymore.
        assert!(shell.contains("fn route_menu_build("));
        assert!(shell.contains("fn inspector_menu_groups("));
        assert!(shell.contains("TextKey::InspectorToggleHierarchy"));
        assert!(shell.contains("TextKey::InspectorToggleFindings"));
        assert!(shell.contains("TextKey::InspectorToggleDetails"));
        let app = include_str!("main.rs");
        assert!(
            !app.contains("cx.set_menus("),
            "The shell module owns the application menu"
        );
    }
}

/// Route-shortcut entry points (IDEA-style window shortcuts).
impl InspectorWorkspace {
    /// IDEA Find (Cmd+F): focuses the hierarchy search field.
    pub(crate) fn focus_search_primary(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        self.search.update(cx, |state, cx| {
            state.focus_handle(cx).focus(window, cx);
        });
    }

    /// IDEA Hide All Tool Windows (Shift+Cmd+F12): hides every pane, or restores
    /// them when all panes are already hidden.
    pub(crate) fn toggle_all_panes(&mut self, cx: &mut Context<Self>) {
        let any_visible = self.show_hierarchy || self.show_findings || self.show_details;
        self.show_hierarchy = !any_visible;
        self.show_findings = !any_visible;
        self.show_details = !any_visible;
        cx.emit(InspectorPanelsChanged);
        cx.notify();
    }
}

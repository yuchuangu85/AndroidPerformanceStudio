use aps_layout_inspector::{InspectorArchive, InspectorProtocolFrame, LayoutNode};
use aps_platform::CancellationToken;
use aps_ui_foundation::{Language, LayoutPreferences, TextKey};
use gpui_kit::{
    component::{
        Disableable, IconName, Selectable,
        button::{Button, ButtonVariants},
        input::{Input, InputState},
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
const HIERARCHY_ROW_HEIGHT: f32 = 20.0;
const HIERARCHY_INDENT: f32 = 14.0;
const DETAIL_LABEL_WIDTH: f32 = 108.0;
const TOOLBAR_BUTTON_HEIGHT: f32 = 24.0;
const TOOLBAR_BUTTON_RADIUS: f32 = 4.0;

const PREVIEW_SURFACE_DARK: u32 = 0x2c2c2e;
const PREVIEW_SURFACE_LIGHT: u32 = 0xffffff;

#[derive(Clone, Copy, PartialEq, Eq)]
enum DetailsTab {
    Layout,
    Drawing,
    Accessibility,
}

impl DetailsTab {
    const ALL: [Self; 3] = [Self::Layout, Self::Drawing, Self::Accessibility];

    fn label(self, language: Language) -> &'static str {
        match self {
            Self::Layout => language.text(TextKey::InspectorDetailsLayout),
            Self::Drawing => language.text(TextKey::InspectorDetailsDrawing),
            Self::Accessibility => language.text(TextKey::InspectorDetailsAccessibility),
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
            details_tab: DetailsTab::Layout,
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
        self.archive = Some(Arc::new(frame.archive));
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
        cx.notify();
    }

    pub fn toggle_findings(&mut self, cx: &mut Context<Self>) {
        self.show_findings = !self.show_findings;
        cx.notify();
    }

    pub fn toggle_details(&mut self, cx: &mut Context<Self>) {
        self.show_details = !self.show_details;
        cx.notify();
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
        let mut controls = div()
            .id("inspector-header-controls")
            .flex_1()
            .min_w_0()
            .flex()
            .items_center()
            .gap(px(8.))
            .overflow_x_scroll()
            .child(
                toolbar_button(
                    "inspector-target-foreground",
                    self.target_serial.as_ref().map_or_else(
                        || {
                            language
                                .text(TextKey::InspectorCaptureForeground)
                                .to_owned()
                        },
                        |serial| {
                            format!("{}: {}", language.text(TextKey::InspectorNoDevice), serial)
                        },
                    ),
                    false,
                    false,
                    colors,
                )
                .flex_shrink_0()
                .tooltip(language.text(TextKey::InspectorLiveCaptureUnavailable)),
            );
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
                    .text_color(colors.secondary_text)
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
            .child(
                toolbar_button(
                    "inspector-manual-refresh",
                    language.text(TextKey::InspectorManualRefresh),
                    false,
                    false,
                    colors,
                )
                .flex_shrink_0()
                .tooltip(language.text(TextKey::InspectorLiveCaptureUnavailable)),
            )
            .child(
                toolbar_button(
                    "inspector-auto-refresh",
                    language.text(TextKey::InspectorAutoRefresh),
                    false,
                    false,
                    colors,
                )
                .flex_shrink_0()
                .tooltip(language.text(TextKey::InspectorLiveCaptureUnavailable)),
            );

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
                toolbar_icon_button(
                    "inspector-toggle-hierarchy",
                    IconName::PanelLeft,
                    language.text(TextKey::InspectorToggleHierarchy),
                    self.show_hierarchy,
                    true,
                    colors,
                )
                .flex_shrink_0()
                .on_click(cx.listener(|this, _, _, cx| this.toggle_hierarchy(cx))),
            )
            .child(
                toolbar_icon_button(
                    "inspector-toggle-findings",
                    IconName::PanelBottom,
                    language.text(TextKey::InspectorToggleFindings),
                    self.show_findings,
                    true,
                    colors,
                )
                .flex_shrink_0()
                .on_click(cx.listener(|this, _, _, cx| this.toggle_findings(cx))),
            )
            .child(
                toolbar_icon_button(
                    "inspector-toggle-details",
                    IconName::PanelRight,
                    language.text(TextKey::InspectorToggleDetails),
                    self.show_details,
                    true,
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

    fn zoom_by(&mut self, delta: f32, cx: &mut Context<Self>) {
        self.zoom = (self.zoom + delta).clamp(0.25, 4.0);
        cx.notify();
    }

    fn zoom_reset(&mut self, cx: &mut Context<Self>) {
        self.zoom = 1.0;
        cx.notify();
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
        let selected = self
            .selected_index()
            .and_then(|index| window_data.and_then(|window| window.nodes.get(index)));
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
                    &visible_rows,
                    &self.hidden,
                    &self.selected,
                    &self.collapsed,
                    self.preferences,
                    entity.clone(),
                    self.search.clone(),
                    cx,
                ))
                .child(vertical_splitter(&colors));
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
            panes = panes.child(vertical_splitter(&colors)).child(details_pane(
                language,
                &colors,
                archive.as_ref(),
                self.window,
                selected,
                self.details_tab,
                &self.selected,
                self.selected
                    .as_deref()
                    .is_some_and(|id| self.hidden.contains(id)),
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
                .child(horizontal_splitter(&colors))
                .child(findings_pane(language, &colors, self.findings_tone, cx));
        }

        div()
            .id("inspector-workspace")
            .size_full()
            .min_h_0()
            .min_w_0()
            .flex()
            .flex_col()
            .overflow_hidden()
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
                    .child(error)
            }))
            .children(self.busy.then(|| {
                div()
                    .flex_shrink_0()
                    .px(px(10.))
                    .py(px(4.))
                    .text_size(px(studio::FONT_SUPPORTING))
                    .text_color(colors.muted_text)
                    .child(language.text(TextKey::InspectorValidatingArchive))
            }))
            .child(workspace)
            .child(status)
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

fn hierarchy_label(node: &LayoutNode, index: usize, preferences: LayoutPreferences) -> String {
    let mut parts = Vec::with_capacity(3);
    if !preferences.hide_indices {
        parts.push((index + 1).to_string());
    }
    if preferences.show_ids {
        if let Some(resource) = &node.resource_name {
            parts.push(resource.clone());
        }
    }
    parts.push(node.class_name.clone());
    parts.join("  ")
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

fn vertical_splitter(colors: &StudioColors) -> Stateful<Div> {
    div()
        .id("inspector-vertical-splitter")
        .w(px(SPLITTER))
        .cursor(CursorStyle::ResizeLeftRight)
        .h_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .justify_center()
        .child(div().w(px(1.)).h_full().bg(colors.border))
}

fn horizontal_splitter(colors: &StudioColors) -> Stateful<Div> {
    div()
        .id("inspector-horizontal-splitter")
        .h(px(SPLITTER))
        .cursor(CursorStyle::ResizeUpDown)
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .justify_center()
        .child(div().w_full().h(px(1.)).bg(colors.border))
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
                .child(language.text(title)),
        )
        .child(
            div()
                .text_size(px(studio::FONT_SUPPORTING - 1.0))
                .text_color(colors.muted_text)
                .child(language.text(label)),
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
                .child(label),
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
            .child(value)
    } else {
        div()
            .text_size(px(studio::FONT_SUPPORTING))
            .text_color(colors.text)
            .whitespace_nowrap()
            .child(value)
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
                .child(label),
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
    rows: &[usize],
    hidden: &BTreeSet<String>,
    selected: &Option<String>,
    collapsed: &BTreeSet<String>,
    preferences: LayoutPreferences,
    entity: Entity<InspectorWorkspace>,
    search: Entity<InputState>,
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

    let header = panel_header(
        language,
        TextKey::InspectorHierarchyTitle,
        TextKey::InspectorHierarchyLabel,
        div()
            .text_size(px(studio::FONT_SUPPORTING))
            .text_color(colors.muted_text)
            .child(count.to_string()),
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
                .child(language.text(TextKey::InspectorNoHierarchy)),
        );
    } else {
        let list_entity = entity.clone();
        let list_nodes = nodes.clone();
        let list_rows = row_indices.clone();
        let list_hidden = hidden_set.clone();
        let list_collapsed = collapsed_set.clone();
        let list_selected = selected_id.clone();
        let list_colors = *colors;
        body = body.child(
            uniform_list(
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
                        items.push(hierarchy_row(
                            row_index,
                            global_index,
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
            .h_full()
            .w_full(),
        );
    }

    let mut pane = div()
        .id("inspector-hierarchy-pane")
        .w(px(HIERARCHY_WIDTH))
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
        .w_full()
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
    let label = hierarchy_label(node, global_index, preferences);
    row = row
        .child(
            div()
                .flex_1()
                .min_w_0()
                .text_size(px(studio::FONT_SUPPORTING))
                .text_color(text_color)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(label),
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
                .child(language.text(TextKey::InspectorHiddenInherited)),
        );
    }
    let _ = cx;
    row.into_any_element()
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
        let dimensions = display.map_or_else(
            || language.text(TextKey::InspectorNoSnapshot).to_owned(),
            |display| format!("{} x {}", display.width_px, display.height_px),
        );
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
        trailing = trailing.child(
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
        if let (Some(display), Some(window)) = (display, window) {
            let (nodes, eligible) = canvas_nodes(
                &window.nodes,
                hidden,
                selected.as_deref(),
                display.width_px,
                display.height_px,
            );
            body = body.child(self.geometry_canvas(
                nodes,
                display.width_px,
                display.height_px,
                dark,
                colors,
                cx,
            ));
            body = body.child(
                div()
                    .h(px(28.))
                    .flex_shrink_0()
                    .w_full()
                    .flex()
                    .items_center()
                    .justify_center()
                    .gap(px(8.))
                    .text_size(px(studio::FONT_SUPPORTING))
                    .text_color(colors.muted_text)
                    .child(
                        toolbar_icon_button(
                            "inspector-zoom-out",
                            IconName::Minus,
                            language.text(TextKey::InspectorZoomOut),
                            false,
                            true,
                            colors,
                        )
                        .on_click(cx.listener(|this, _, _, cx| this.zoom_by(-0.1, cx))),
                    )
                    .child(
                        div()
                            .min_w(px(44.))
                            .flex()
                            .justify_center()
                            .child(format!("{}%", (self.zoom * 100.0).round() as i32)),
                    )
                    .child(
                        toolbar_icon_button(
                            "inspector-zoom-in",
                            IconName::Plus,
                            language.text(TextKey::InspectorZoomIn),
                            false,
                            true,
                            colors,
                        )
                        .on_click(cx.listener(|this, _, _, cx| this.zoom_by(0.1, cx))),
                    )
                    .child(div().text_color(colors.muted_text).child(
                        if eligible > MAX_CANVAS_NODES {
                            format!("{MAX_CANVAS_NODES} / {eligible}")
                        } else {
                            language.text(TextKey::InspectorGeometry).to_owned()
                        },
                    )),
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
                        .child(language.text(TextKey::InspectorNoLayoutSnapshot)),
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
                        .child(language.text(TextKey::InspectorNoMedia)),
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
        display_width: u32,
        display_height: u32,
        dark: bool,
        colors: &StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let origin = Arc::new(Mutex::new(None::<(Point<Pixels>, f32)>));
        let paint_origin = origin.clone();
        let hit_nodes = nodes.clone();
        let hover_nodes = nodes.clone();
        let hover_origin = origin.clone();
        let hit_smallest = self.preferences.hit_smallest;
        let hovered = self.hovered.clone();
        let zoom = self.zoom;
        let selected = self.selected.clone();
        let show_bounds = self.show_bounds;
        let normal = rgba(self.preferences.normal_color.rotate_left(8));
        let hover_color = rgba(self.preferences.hover_color.rotate_left(8));
        let selected_color = rgba(self.preferences.selected_color.rotate_left(8));
        let surface = preview_surface_color(dark);
        let frame_border = colors.strong_border;
        div()
            .id("layout-canvas")
            .flex_1()
            .min_h_0()
            .w_full()
            .overflow_hidden()
            .bg(colors.panel)
            .on_mouse_down(
                MouseButton::Left,
                cx.listener(move |this, event: &MouseDownEvent, _, cx| {
                    let Ok(origin) = origin.lock() else {
                        return;
                    };
                    let Some((origin, scale)) = *origin else {
                        return;
                    };
                    if scale <= 0.0 {
                        return;
                    }
                    let x = f32::from(event.position.x - origin.x) / scale;
                    let y = f32::from(event.position.y - origin.y) / scale;
                    let hit = canvas_hit(&hit_nodes, x, y, hit_smallest);
                    this.selected = hit;
                    cx.notify();
                }),
            )
            .on_mouse_move(cx.listener(move |this, event: &MouseMoveEvent, _, cx| {
                let Ok(origin) = hover_origin.lock() else {
                    return;
                };
                let Some((origin, scale)) = *origin else {
                    return;
                };
                if scale <= 0.0 {
                    return;
                }
                let x = f32::from(event.position.x - origin.x) / scale;
                let y = f32::from(event.position.y - origin.y) / scale;
                let hit = canvas_hit(&hover_nodes, x, y, hit_smallest);
                if this.hovered != hit {
                    this.hovered = hit;
                    cx.notify();
                }
            }))
            .on_mouse_exit(cx.listener(|this, _, _, cx| {
                if this.hovered.take().is_some() {
                    cx.notify();
                }
            }))
            .child(
                canvas(
                    move |bounds, _, _| {
                        let (width, height, base_scale) = canvas_dimensions(
                            display_width,
                            display_height,
                            f32::from(bounds.size.width) - 56.0,
                            f32::from(bounds.size.height) - 56.0,
                        );
                        let scale = base_scale * zoom;
                        let width = width * zoom;
                        let height = height * zoom;
                        let canvas_origin = point(
                            bounds.origin.x + (bounds.size.width - px(width)) / 2.0,
                            bounds.origin.y + (bounds.size.height - px(height)) / 2.0,
                        );
                        if let Ok(mut origin) = paint_origin.lock() {
                            *origin = Some((canvas_origin, scale));
                        }
                        (canvas_origin, width, height, scale)
                    },
                    move |_, (origin, width, height, scale), window, _| {
                        window.paint_quad(quad(
                            Bounds {
                                origin,
                                size: size(px(width), px(height)),
                            },
                            px(0.),
                            surface,
                            px(1.),
                            frame_border,
                            Default::default(),
                        ));
                        if !show_bounds {
                            return;
                        }
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
                            let left = (b.left as f32 * scale).max(0.0);
                            let top = (b.top as f32 * scale).max(0.0);
                            let right = (b.right as f32 * scale).min(width);
                            let bottom = (b.bottom as f32 * scale).min(height);
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
                    },
                )
                .size_full(),
            )
            .into_any_element()
    }
}

fn details_tab_button(
    id: impl Into<ElementId>,
    label: &'static str,
    selected: bool,
    colors: &StudioColors,
) -> Button {
    Button::new(id)
        .ghost()
        .h(px(24.))
        .flex_1()
        .rounded(px(4.))
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
        .selected(selected)
        .child(
            div()
                .text_size(px(studio::FONT_SUPPORTING))
                .whitespace_nowrap()
                .child(label),
        )
}

fn section_header(language: Language, title: TextKey, colors: &StudioColors) -> Div {
    div()
        .h(px(24.))
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .px(px(8.))
        .text_size(px(studio::FONT_SUPPORTING))
        .font_weight(FontWeight::SEMIBOLD)
        .text_color(colors.secondary_text)
        .child(language.text(title))
}

fn detail_row(
    label: impl Into<SharedString>,
    value: impl Into<SharedString>,
    colors: &StudioColors,
) -> Div {
    let label: SharedString = label.into();
    let value: SharedString = value.into();
    div()
        .h(px(22.))
        .w_full()
        .flex()
        .items_center()
        .gap(px(8.))
        .px(px(8.))
        .child(
            div()
                .w(px(DETAIL_LABEL_WIDTH))
                .flex_shrink_0()
                .text_size(px(studio::FONT_SUPPORTING))
                .text_color(colors.muted_text)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(label),
        )
        .child(
            div()
                .flex_1()
                .min_w_0()
                .text_size(px(studio::FONT_SUPPORTING))
                .text_color(colors.text)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(value),
        )
}

fn details_pane(
    language: Language,
    colors: &StudioColors,
    archive: Option<&Arc<InspectorArchive>>,
    window_index: usize,
    selected: Option<&LayoutNode>,
    tab: DetailsTab,
    selected_id: &Option<String>,
    hidden_selected: bool,
    cx: &mut Context<InspectorWorkspace>,
) -> AnyElement {
    let nodes = archive
        .and_then(|archive| archive.windows.get(window_index))
        .map(|window| window.nodes.as_slice());
    let parent_label = selected
        .and_then(|node| node.parent_index)
        .and_then(|parent| nodes.and_then(|nodes| nodes.get(parent)))
        .map_or_else(
            || "-".to_owned(),
            |parent| format!("{} @{}", parent.class_name, parent.id),
        );
    let package = archive.map_or_else(
        || language.text(TextKey::InspectorUnavailable).to_owned(),
        |archive| archive.frame.package_name.clone(),
    );
    let window_name = archive
        .and_then(|archive| archive.windows.get(window_index))
        .map_or_else(
            || language.text(TextKey::InspectorUnavailable).to_owned(),
            |window| window.id.clone(),
        );

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

    let mut tabs = div()
        .h(px(30.))
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .gap(px(4.))
        .px(px(8.))
        .border_b_1()
        .border_color(colors.border);
    for (tab_index, candidate) in DetailsTab::ALL.into_iter().enumerate() {
        let selected_tab = candidate == tab;
        tabs = tabs.child(
            details_tab_button(
                ("inspector-details-tab", tab_index),
                candidate.label(language),
                selected_tab,
                colors,
            )
            .on_click(cx.listener(move |this, _, _, cx| {
                this.details_tab = candidate;
                cx.notify();
            })),
        );
    }

    let mut content = div()
        .id("inspector-properties-rows")
        .flex_1()
        .min_h_0()
        .w_full()
        .overflow_y_scroll()
        .flex()
        .flex_col();
    match selected {
        None => {
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
                    .child(language.text(TextKey::InspectorNoNodeSelected)),
            );
        }
        Some(node) => {
            let visible = if node.visible {
                language.text(TextKey::InspectorValueYes)
            } else {
                language.text(TextKey::InspectorValueNo)
            };
            let hidden = if hidden_selected {
                language.text(TextKey::InspectorValueYes)
            } else {
                language.text(TextKey::InspectorValueNo)
            };
            match tab {
                DetailsTab::Layout => {
                    content = content
                        .child(section_header(
                            language,
                            TextKey::InspectorSectionBasic,
                            colors,
                        ))
                        .child(detail_row(
                            language.text(TextKey::InspectorDetailId),
                            node.id.clone(),
                            colors,
                        ))
                        .child(detail_row(
                            language.text(TextKey::InspectorDetailClass),
                            node.class_name.clone(),
                            colors,
                        ))
                        .child(detail_row(
                            language.text(TextKey::InspectorDetailType),
                            format!("{:?}", node.kind),
                            colors,
                        ))
                        .child(detail_row(
                            language.text(TextKey::InspectorDetailPackage),
                            package,
                            colors,
                        ))
                        .child(detail_row(
                            language.text(TextKey::InspectorDetailParent),
                            parent_label,
                            colors,
                        ))
                        .child(detail_row(
                            language.text(TextKey::InspectorDetailDepth),
                            node.depth.to_string(),
                            colors,
                        ))
                        .child(detail_row(
                            language.text(TextKey::InspectorDetailWindow),
                            window_name,
                            colors,
                        ))
                        .child(section_header(
                            language,
                            TextKey::InspectorSectionLayout,
                            colors,
                        ))
                        .child(detail_row(
                            language.text(TextKey::InspectorDetailBounds),
                            format!(
                                "[{}, {}][{}, {}]",
                                node.bounds.left,
                                node.bounds.top,
                                node.bounds.right,
                                node.bounds.bottom
                            ),
                            colors,
                        ))
                        .child(detail_row(
                            language.text(TextKey::InspectorDetailSize),
                            format!(
                                "{} x {}",
                                node.bounds.right - node.bounds.left,
                                node.bounds.bottom - node.bounds.top
                            ),
                            colors,
                        ))
                        .child(detail_row(
                            language.text(TextKey::InspectorDetailPosition),
                            format!("{}, {}", node.bounds.left, node.bounds.top),
                            colors,
                        ))
                        .child(detail_row(
                            language.text(TextKey::InspectorDetailResourceName),
                            node.resource_name.clone().unwrap_or_else(|| "-".to_owned()),
                            colors,
                        ));
                }
                DetailsTab::Drawing => {
                    content = content
                        .child(section_header(
                            language,
                            TextKey::InspectorSectionDrawing,
                            colors,
                        ))
                        .child(detail_row(
                            language.text(TextKey::InspectorDetailVisible),
                            visible,
                            colors,
                        ))
                        .child(detail_row(
                            language.text(TextKey::InspectorDetailAlpha),
                            format!("{:.2}", node.alpha),
                            colors,
                        ))
                        .child(detail_row(
                            language.text(TextKey::InspectorDetailHiddenLayer),
                            hidden,
                            colors,
                        ));
                }
                DetailsTab::Accessibility => {
                    content = content
                        .child(section_header(
                            language,
                            TextKey::InspectorSectionAccessibility,
                            colors,
                        ))
                        .child(detail_row(
                            language.text(TextKey::InspectorDetailVisible),
                            visible,
                            colors,
                        ))
                        .child(
                            div()
                                .w_full()
                                .p(px(8.))
                                .text_size(px(studio::FONT_SUPPORTING))
                                .text_color(colors.muted_text)
                                .child(language.text(TextKey::InspectorUnavailable)),
                        );
                }
            }
        }
    }

    div()
        .id("inspector-properties-pane")
        .w(px(PROPERTIES_WIDTH))
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
                .child(label),
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
                .child(language.text(TextKey::InspectorFindingsSeverity)),
        )
        .child(
            div()
                .flex_1()
                .min_w_0()
                .child(language.text(TextKey::InspectorFindingsProblem)),
        )
        .child(
            div()
                .flex_1()
                .min_w_0()
                .child(language.text(TextKey::InspectorFindingsNode)),
        )
        .child(
            div()
                .flex_1()
                .min_w_0()
                .child(language.text(TextKey::InspectorFindingsSuggestion)),
        )
}

fn findings_pane(
    language: Language,
    colors: &StudioColors,
    tone: FindingsTone,
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
                .child(language.text(TextKey::InspectorFindings)),
        )
        .child(
            div()
                .text_size(px(studio::FONT_SUPPORTING - 1.0))
                .text_color(colors.muted_text)
                .child(language.text(TextKey::InspectorFindingsLabel)),
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
                .child(language.text(TextKey::InspectorTimelineLive)),
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
        .h(px(FINDINGS_HEIGHT))
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
                .child(language.text(TextKey::InspectorFindingsEmpty)),
        )
        .into_any_element()
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_layout_inspector::{Bounds as NodeBounds, NodeKind};

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
            runtime_values_omitted: true,
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

    #[::core::prelude::v1::test]
    fn hierarchy_preferences_hide_indices_and_resource_ids_not_evidence() {
        let mut sample = node(0, None);
        sample.resource_name = Some("app:id/content".into());
        let mut preferences = LayoutPreferences::default();
        assert_eq!(
            hierarchy_label(&sample, 4, preferences),
            "5  app:id/content  View"
        );
        preferences.hide_indices = true;
        assert_eq!(
            hierarchy_label(&sample, 4, preferences),
            "app:id/content  View"
        );
        preferences.show_ids = false;
        assert_eq!(hierarchy_label(&sample, 4, preferences), "View");
        assert_eq!(sample.id, "0");
        assert_eq!(sample.resource_name.as_deref(), Some("app:id/content"));
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
        let app = include_str!("main.rs");
        assert!(app.contains("shell::OpenInspectorArchive"));
        assert!(app.contains("shell::ToggleInspectorHierarchy"));
        assert!(app.contains("shell::ToggleInspectorFindings"));
        assert!(app.contains("shell::ToggleInspectorDetails"));
    }
}

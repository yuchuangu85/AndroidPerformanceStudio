//! Source Workspaces page UI.
//!
//! `SourceWorkspacesPage.kt` is the behavior oracle: a full-height toolbar, a
//! three-pane browser (workspaces / files / source) with draggable splitters, a
//! collapsed-by-default directory tree with search, and the One Dark code
//! viewer fed by the runtime's verified read. Every action goes through
//! `SourceWorkspace`; this module owns presentation-only UI state.

use super::code_viewer::{CodeLanguage, SourceCodeViewer, SourceDocument};
use super::{SourceWorkspace, layout, tree};
use crate::studio::{self, FONT_BODY, FONT_SUPPORTING, StudioColors};
use aps_source::{
    ResolutionConfidence, SourceContentState, SourceLanguage, SourceProviderKind,
    SourceWorkspace as WorkspaceRecord, SourceWorkspacePhase,
};
use aps_ui_foundation::{Destination, Language, TextKey};
use gpui_kit::{
    component::{
        InteractiveElementExt,
        input::{Input, InputEvent, InputState},
        scroll::{ScrollableElement, Scrollbar, ScrollbarAxis},
    },
    prelude::*,
    *,
};
use std::{collections::BTreeSet, rc::Rc};

const TOOLBAR_HEIGHT: f32 = 36.;
const TOOLBAR_PADDING: f32 = 16.;
const TOOLBAR_GAP: f32 = 8.;
const SEARCH_ROW_HEIGHT: f32 = 28.;
const SEARCH_INPUT_HEIGHT: f32 = 22.;
const PROGRESS_HEIGHT: f32 = 4.;
const TEXT_WIDTH_ESTIMATE: f32 = 8.;

/// Presentation-only state owned by the page.
pub(super) struct PageUi {
    pub(super) pane_widths: layout::PaneWidths,
    pub(super) collapsed_directories: BTreeSet<String>,
    pub(super) file_query: String,
    /// Last language applied to the search fields; `None` until the first
    /// render or the shell's explicit `set_language`.
    language: Option<Language>,
    search: Entity<InputState>,
    _search_subscription: Subscription,
    viewer: Entity<SourceCodeViewer>,
    drag: Option<SplitterDrag>,
    tree_files_key: Option<(u64, usize, usize)>,
    tree_revision: u64,
    tree_cache: Option<TreeCache>,
    tree_scroll: UniformListScrollHandle,
    scroll_tree_top: bool,
    document: Option<DocumentKey>,
}

impl PageUi {
    pub(super) fn new(window: &mut Window, cx: &mut Context<SourceWorkspace>) -> Self {
        let search = cx.new(|cx| InputState::new(window, cx));
        let search_subscription = cx.subscribe(&search, |this, input, event, cx| {
            if matches!(event, InputEvent::Change) {
                this.ui.file_query = input.read(cx).value().to_string();
                this.ui.tree_revision = this.ui.tree_revision.wrapping_add(1);
                this.ui.scroll_tree_top = true;
                cx.notify();
            }
        });
        let viewer = cx.new(|cx| SourceCodeViewer::new(window, cx));
        Self {
            pane_widths: layout::PaneWidths::default(),
            collapsed_directories: BTreeSet::new(),
            file_query: String::new(),
            language: None,
            search,
            _search_subscription: search_subscription,
            viewer,
            drag: None,
            tree_files_key: None,
            tree_revision: 0,
            tree_cache: None,
            tree_scroll: UniformListScrollHandle::new(),
            scroll_tree_top: false,
            document: None,
        }
    }

    pub(super) fn set_language(&mut self, language: Language, window: &mut Window, cx: &mut App) {
        self.language = Some(language);
        let placeholder = language.text(TextKey::SourceSearchFiles);
        self.search.update(cx, |state, cx| {
            state.set_placeholder(placeholder, window, cx);
        });
        self.viewer
            .update(cx, |viewer, cx| viewer.set_language(language, window, cx));
    }

    /// Reveals a selected file by expanding all of its ancestors.
    pub(super) fn expand_ancestors(&mut self, relative_path: &str) {
        let ancestors = tree::ancestor_directories(relative_path);
        if ancestors.is_empty() {
            return;
        }
        let before = self.collapsed_directories.len();
        self.collapsed_directories
            .retain(|path| !ancestors.contains(path));
        if self.collapsed_directories.len() != before {
            self.tree_revision = self.tree_revision.wrapping_add(1);
        }
    }

    fn toggle_directory(&mut self, path: &str) {
        if !self.collapsed_directories.remove(path) {
            self.collapsed_directories.insert(path.to_owned());
        }
        self.tree_revision = self.tree_revision.wrapping_add(1);
    }
}

/// One draggable splitter's start state; deltas are applied against it so a
/// fitted pane never accumulates rounding drift.
#[derive(Clone, Copy)]
struct SplitterDrag {
    kind: SplitterKind,
    start_x: f32,
    start_widths: layout::PaneWidths,
    available: f32,
}

#[derive(Clone, Copy, PartialEq, Eq)]
enum SplitterKind {
    Workspaces,
    Files,
}

/// Cached directory-tree projection. `key` combines the runtime's manifest
/// generation with the page's collapse/search revision.
struct TreeCache {
    key: (u64, usize, usize, u64),
    rows: Rc<Vec<tree::TreeRow>>,
    content_width: f32,
}

/// What is currently shown in the viewer; avoids resending the same document
/// (which would reset the viewer's search and scroll position) every frame.
#[derive(PartialEq)]
struct DocumentKey {
    generation: u64,
    loaded: bool,
    path: Option<String>,
    range: Option<(usize, usize)>,
    language: CodeLanguage,
}

pub(super) fn render(
    this: &mut SourceWorkspace,
    window: &mut Window,
    cx: &mut Context<SourceWorkspace>,
) -> AnyElement {
    let colors = StudioColors::from_theme(cx);
    if this.ui.language != Some(this.language) {
        let language = this.language;
        this.ui.set_language(language, window, cx);
    }
    prepare_tree(this);
    sync_viewer(this, window, cx);
    if this.ui.scroll_tree_top {
        this.ui.scroll_tree_top = false;
        this.ui.tree_scroll.scroll_to_item(0, ScrollStrategy::Top);
    }

    let entity = cx.entity().downgrade();
    let mut root = div()
        .id("source-page")
        .size_full()
        .flex()
        .flex_col()
        .bg(colors.workspace)
        .text_color(colors.text)
        .text_size(px(FONT_BODY))
        .on_mouse_move(
            cx.listener(|this, event: &MouseMoveEvent, _, cx| update_drag(this, event, cx)),
        )
        .on_mouse_up(
            MouseButton::Left,
            cx.listener(|this, _, _, cx| {
                if this.ui.drag.take().is_some() {
                    cx.notify();
                }
            }),
        )
        .child(toolbar(this, &colors, cx))
        .child(studio::divider(&colors));
    if let Some(error) = this.page_error.clone() {
        root = root.child(message_line(error, colors.error));
    }
    if let Some(error) = this.permissions_error.clone() {
        if this.page_error.as_deref() != Some(error.as_str()) {
            root = root.child(message_line(error, colors.error));
        }
    }
    root.child(
        div()
            .flex_1()
            .min_h_0()
            .w_full()
            .child(container_query(move |size, _, cx| {
                let Some(entity) = entity.upgrade() else {
                    return div().into_any_element();
                };
                entity.update(cx, |this, cx| body(this, size, &colors, cx))
            })),
    )
    .into_any_element()
}

fn prepare_tree(this: &mut SourceWorkspace) {
    // The manifest identity combines the runtime's load generation with the
    // list address: a render that lands between load start and completion sees
    // the previous list, so the completion must still read as a change.
    let files_key = (
        this.files_generation,
        this.files.len(),
        this.files.as_ptr() as usize,
    );
    if this.ui.tree_files_key != Some(files_key) {
        this.ui.tree_files_key = Some(files_key);
        // A newly shown manifest starts fully collapsed, then the selected
        // file's ancestors are revealed again (Compose's `remember(files)` +
        // `LaunchedEffect(files, selectedFile)`).
        this.ui.collapsed_directories = tree::all_directories(&this.files);
        if let Some(selected) = this.selected_file.clone() {
            this.ui.expand_ancestors(&selected);
        }
        this.ui.tree_revision = this.ui.tree_revision.wrapping_add(1);
    }
    let cache_key = (
        this.files_generation,
        this.files.len(),
        this.files.as_ptr() as usize,
        this.ui.tree_revision,
    );
    let stale = this.ui.tree_cache.as_ref().map(|cache| cache.key) != Some(cache_key);
    if stale {
        let rows = tree::tree_rows(
            &this.files,
            &this.ui.file_query,
            &this.ui.collapsed_directories,
        );
        let content_width = tree_content_width(&rows);
        this.ui.tree_cache = Some(TreeCache {
            key: cache_key,
            rows: Rc::new(rows),
            content_width,
        });
    }
}

fn tree_content_width(rows: &[tree::TreeRow]) -> f32 {
    rows.iter()
        .map(|row| {
            row.name.chars().count() as f32 * TEXT_WIDTH_ESTIMATE
                + 8.
                + row.depth as f32 * tree::TREE_INDENT
                + 12.
                + 4.
                + 14.
                + 5.
                + 8.
        })
        .fold(0.0, f32::max)
}

fn sync_viewer(this: &mut SourceWorkspace, window: &mut Window, cx: &mut Context<SourceWorkspace>) {
    let language = selected_language(this);
    let range = this.selected_location.as_ref().and_then(|location| {
        location
            .range
            .as_ref()
            .map(|range| (range.start_line, range.end_line))
    });
    let key = DocumentKey {
        generation: this.content_generation,
        loaded: this.content.is_some(),
        path: this
            .selected_location
            .as_ref()
            .map(|location| location.relative_path.clone()),
        range,
        language: code_language(language),
    };
    if this.ui.document.as_ref() == Some(&key) {
        return;
    }
    let document = match &this.content {
        Some(content) if this.selected_location.is_some() => SourceDocument {
            text: content.text.clone().into(),
            language: code_language(language),
            highlighted_range: range,
        },
        _ => SourceDocument {
            text: "".into(),
            language: code_language(language),
            highlighted_range: None,
        },
    };
    this.ui.document = Some(key);
    this.ui
        .viewer
        .update(cx, |viewer, cx| viewer.set_document(document, window, cx));
}

fn selected_language(this: &SourceWorkspace) -> SourceLanguage {
    let Some(location) = this.selected_location.as_ref() else {
        return SourceLanguage::Other;
    };
    this.location_files
        .iter()
        .find(|file| {
            file.snapshot_id == location.snapshot_id && file.relative_path == location.relative_path
        })
        .or_else(|| {
            this.files
                .iter()
                .find(|file| file.relative_path == location.relative_path)
        })
        .map(|file| file.language)
        .unwrap_or(SourceLanguage::Other)
}

fn code_language(language: SourceLanguage) -> CodeLanguage {
    match language {
        SourceLanguage::Kotlin => CodeLanguage::Kotlin,
        SourceLanguage::Java => CodeLanguage::Java,
        SourceLanguage::Xml => CodeLanguage::Xml,
        SourceLanguage::C => CodeLanguage::C,
        SourceLanguage::Cpp => CodeLanguage::Cpp,
        SourceLanguage::Other => CodeLanguage::Other,
    }
}

fn update_drag(
    this: &mut SourceWorkspace,
    event: &MouseMoveEvent,
    cx: &mut Context<SourceWorkspace>,
) {
    let Some(drag) = this.ui.drag else {
        return;
    };
    if event.pressed_button != Some(MouseButton::Left) {
        this.ui.drag = None;
        return;
    }
    let delta = f32::from(event.position.x) - drag.start_x;
    let widths = match drag.kind {
        SplitterKind::Workspaces => {
            layout::drag_workspaces(drag.start_widths, delta, drag.available)
        }
        SplitterKind::Files => layout::drag_files(drag.start_widths, delta, drag.available),
    };
    if widths != this.ui.pane_widths {
        this.ui.pane_widths = widths;
        cx.notify();
    }
}

fn toolbar(
    this: &SourceWorkspace,
    colors: &StudioColors,
    cx: &Context<SourceWorkspace>,
) -> AnyElement {
    let language = this.language;
    let busy = this.add_in_flight || !this.sync_in_flight.is_empty();
    let analyze_enabled = this.can_analyze() && !busy;
    div()
        .id("source-toolbar")
        .h(px(TOOLBAR_HEIGHT))
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .gap(px(TOOLBAR_GAP))
        .px(px(TOOLBAR_PADDING))
        .child(div().flex_1())
        .child(
            studio::text_button(
                "source-add-local",
                language.text(TextKey::SourceAddLocal),
                !this.add_in_flight,
                false,
                colors,
            )
            .flex_shrink_0()
            .on_click(cx.listener(|this, _, _, cx| this.pick_local_workspace(cx))),
        )
        .child(
            studio::text_button(
                "source-ai-settings",
                language.text(TextKey::SourceAiSettings),
                true,
                false,
                colors,
            )
            .flex_shrink_0()
            .on_click(cx.listener(|this, _, window, cx| {
                if let Some(studio) = this.studio.clone() {
                    studio
                        .update(cx, |studio, cx| studio.open_ai_settings(window, cx))
                        .ok();
                }
            })),
        )
        .child(
            studio::text_button(
                "source-run-ai-analysis",
                language.text(TextKey::FoundationAiAnalysis),
                analyze_enabled,
                false,
                colors,
            )
            .flex_shrink_0()
            .on_click(cx.listener(|this, _, window, cx| {
                // Compute the request here: `prepare_ai` re-reads the source
                // entity and would panic while this page is being updated.
                let request = this.analysis_request();
                if let Some(studio) = this.studio.clone() {
                    studio
                        .update(cx, |studio, cx| {
                            studio.present_ai_request(
                                Destination::SourceWorkspaces,
                                request,
                                window,
                                cx,
                            )
                        })
                        .ok();
                }
            })),
        )
        .into_any_element()
}

fn body(
    this: &SourceWorkspace,
    size: Size<Pixels>,
    colors: &StudioColors,
    cx: &Context<SourceWorkspace>,
) -> AnyElement {
    // BoxWithConstraints: the panes are fitted to the measured body width.
    let available = f32::from(size.width);
    let fitted = layout::fit(this.ui.pane_widths, available);
    let content_width = available.max(layout::minimum_content_width());
    let workspaces: Vec<&WorkspaceRecord> = this
        .workspaces
        .iter()
        .filter(|workspace| workspace.config.kind() == SourceProviderKind::Local)
        .collect();
    let active = this.selected_workspace.and_then(|id| {
        workspaces
            .iter()
            .copied()
            .find(|workspace| workspace.id == id)
    });
    let rest = match active.filter(|workspace| workspace.active_snapshot_id.is_some()) {
        Some(_) => div()
            .flex_1()
            .min_w_0()
            .h_full()
            .flex()
            .child(files_pane(this, fitted.files, colors, cx))
            .child(splitter(
                "source-splitter-files",
                SplitterKind::Files,
                fitted,
                available,
                colors,
                cx,
            ))
            .child(source_pane(this, colors, cx))
            .into_any_element(),
        None => div()
            .flex_1()
            .min_w_0()
            .h_full()
            .flex()
            .items_center()
            .justify_center()
            .child(
                div()
                    .px(px(24.))
                    .text_size(px(FONT_BODY))
                    .text_color(colors.secondary_text)
                    .child(this.language.text(TextKey::SourceSelectIndexedWorkspace)),
            )
            .into_any_element(),
    };
    let row = div()
        .id("source-body-content")
        .h_full()
        .w(px(content_width))
        .flex_shrink_0()
        .flex()
        .child(workspaces_pane(
            this,
            &workspaces,
            fitted.workspaces,
            colors,
            cx,
        ))
        .child(splitter(
            "source-splitter-workspaces",
            SplitterKind::Workspaces,
            fitted,
            available,
            colors,
            cx,
        ))
        .child(rest);

    div()
        .id("source-body")
        .relative()
        .size_full()
        .overflow_hidden()
        .child(
            div()
                .id("source-body-scroll")
                .size_full()
                .overflow_x_scroll()
                .lock_scroll_axis()
                .child(row),
        )
        .into_any_element()
}

fn workspaces_pane(
    this: &SourceWorkspace,
    workspaces: &[&WorkspaceRecord],
    width: f32,
    colors: &StudioColors,
    cx: &Context<SourceWorkspace>,
) -> AnyElement {
    let language = this.language;
    let longest = workspaces
        .iter()
        .map(|workspace| workspace.display_name.chars().count() as f32 * TEXT_WIDTH_ESTIMATE + 48.)
        .fold(0.0, f32::max);
    let content_width = (width - 12.).max(longest);
    let mut column = div()
        .flex()
        .flex_col()
        .gap(px(studio::metrics::CARD_GAP))
        .w(px(content_width))
        .p(px(studio::metrics::WORKSPACE_PADDING));
    if this.add_in_flight {
        if let Some(progress) = this.add_progress.as_ref() {
            let pending_name = this
                .add_display_name
                .clone()
                .unwrap_or_else(|| language.text(TextKey::SourceAddLocal).to_owned());
            column = column.child(add_progress_card(progress, &pending_name, language, colors));
        }
    }
    if !this.list_loaded {
        column = column.child(pane_status(
            language.text(TextKey::SourceReadingIndexing),
            colors.secondary_text,
        ));
    } else if let Some(error) = this.list_error.clone() {
        column = column.child(pane_status(error, colors.error));
    } else if workspaces.is_empty() {
        column = column.child(pane_status(
            language.text(TextKey::SourceEmptyHint),
            colors.secondary_text,
        ));
    }
    for workspace in workspaces {
        let selected = this.selected_workspace == Some(workspace.id);
        let syncing = this.sync_in_flight.contains_key(&workspace.id.to_hex());
        column = column.child(workspace_card(
            workspace, selected, syncing, language, colors, cx,
        ));
    }
    div()
        .id("source-workspaces-pane")
        .w(px(width))
        .h_full()
        .flex_shrink_0()
        .flex()
        .flex_col()
        .overflow_hidden()
        .bg(colors.panel)
        .child(
            div()
                .id("source-workspaces-scroll")
                .size_full()
                .overflow_scrollbar()
                .child(column),
        )
        .into_any_element()
}

/// Live registration/indexing feedback for "+ Local": the runtime only
/// publishes the finished workspace, exactly like Compose's service-in-flight
/// card that stays visible while the new workspace indexes.
fn add_progress_card(
    progress: &super::service::Progress,
    display_name: &str,
    language: Language,
    colors: &StudioColors,
) -> AnyElement {
    let provider = language.text(TextKey::SourceProviderLocal).to_owned();
    let phase = language.text(phase_key(progress.phase)).to_owned();
    let status = language.format(
        TextKey::SourceStatusSummary,
        &[("provider", &provider), ("phase", &phase)],
    );
    let mut card = div()
        .w_full()
        .flex()
        .flex_col()
        .gap(px(4.))
        .p(px(studio::metrics::COMPACT_PADDING))
        .rounded(px(studio::metrics::CARD_RADIUS))
        .border_1()
        .border_color(colors.border)
        .bg(colors.panel)
        .child(
            div()
                .w_full()
                .text_size(px(FONT_BODY))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(colors.text)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(display_name.to_owned()),
        )
        .child(
            div()
                .w_full()
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.secondary_text)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(status),
        );
    let fraction = if progress.progress.is_finite() {
        progress.progress.clamp(0., 1.)
    } else {
        0.
    };
    card = card.child(
        div()
            .w_full()
            .h(px(PROGRESS_HEIGHT))
            .rounded(px(2.))
            .bg(colors.border)
            .child(
                div()
                    .w(relative(fraction))
                    .h_full()
                    .rounded(px(2.))
                    .bg(colors.accent),
            ),
    );
    if let Some(message) = progress.message.as_deref() {
        card = card.child(
            div()
                .w_full()
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.text)
                .child(localized_message(language, message)),
        );
    }
    card.into_any_element()
}

fn workspace_card(
    workspace: &WorkspaceRecord,
    selected: bool,
    syncing: bool,
    language: Language,
    colors: &StudioColors,
    cx: &Context<SourceWorkspace>,
) -> AnyElement {
    let id = workspace.id;
    let hex = id.to_hex();
    let background = if selected {
        colors.selected_row
    } else {
        colors.panel
    };
    let provider = language.text(TextKey::SourceProviderLocal).to_owned();
    let phase = language.text(phase_key(workspace.phase)).to_owned();
    let status = language.format(
        TextKey::SourceStatusSummary,
        &[("provider", &provider), ("phase", &phase)],
    );
    let mut card = div()
        .id(format!("source-workspace-card-{hex}"))
        .flex()
        .flex_col()
        .gap(px(4.))
        .p(px(studio::metrics::COMPACT_PADDING))
        .rounded(px(studio::metrics::CARD_RADIUS))
        .border_1()
        .border_color(colors.border)
        .bg(background)
        .cursor_pointer()
        .on_click(cx.listener(move |this, _, _, cx| this.select_workspace(id, cx)))
        .child(
            div()
                .w_full()
                .text_size(px(FONT_BODY))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(colors.text)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(workspace.display_name.clone()),
        )
        .child(
            div()
                .w_full()
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.secondary_text)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(status),
        );
    if !matches!(
        workspace.phase,
        SourceWorkspacePhase::Ready | SourceWorkspacePhase::Failed
    ) {
        let fraction = if workspace.progress.is_finite() {
            workspace.progress.clamp(0., 1.)
        } else {
            0.
        };
        card = card.child(
            div()
                .w_full()
                .h(px(PROGRESS_HEIGHT))
                .rounded(px(2.))
                .bg(colors.border)
                .child(
                    div()
                        .w(relative(fraction))
                        .h_full()
                        .rounded(px(2.))
                        .bg(colors.accent),
                ),
        );
    }
    if let Some(message) = workspace.message.as_deref() {
        card = card.child(
            div()
                .w_full()
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.text)
                .child(localized_message(language, message)),
        );
    }
    let sync_label = language.text(TextKey::SourceSync);
    let ai_label = language.text(if workspace.allow_ai_source_upload {
        TextKey::SourceAiUploadAllowed
    } else {
        TextKey::SourceAiUploadBlocked
    });
    let remove_label = language.text(TextKey::SourceRemove);
    card.child(
        div()
            .flex()
            .items_center()
            .gap(px(6.))
            .child(
                studio::text_button(
                    format!("source-sync-{hex}"),
                    sync_label,
                    !syncing,
                    false,
                    colors,
                )
                .on_click(cx.listener(move |this, _, _, cx| this.sync_workspace(id, cx))),
            )
            .child(
                studio::text_button(
                    format!("source-ai-upload-{hex}"),
                    ai_label,
                    true,
                    false,
                    colors,
                )
                .on_click(cx.listener(move |this, _, _, cx| this.toggle_ai_source_upload(id, cx))),
            )
            .child(
                studio::text_button(
                    format!("source-remove-{hex}"),
                    remove_label,
                    true,
                    false,
                    colors,
                )
                .on_click(cx.listener(move |this, _, _, cx| this.remove_workspace(id, cx))),
            ),
    )
    .into_any_element()
}

fn files_pane(
    this: &SourceWorkspace,
    width: f32,
    colors: &StudioColors,
    cx: &Context<SourceWorkspace>,
) -> AnyElement {
    let language = this.language;
    let entity = cx.entity();
    let colors = *colors;
    let search_row = div()
        .h(px(SEARCH_ROW_HEIGHT))
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .px(px(6.))
        .py(px(2.))
        .child(
            div().flex_1().min_w_0().child(
                Input::new(&this.ui.search)
                    .h(px(SEARCH_INPUT_HEIGHT))
                    .rounded(px(3.)),
            ),
        );
    let (rows, estimate) = match &this.ui.tree_cache {
        Some(cache) => (cache.rows.clone(), cache.content_width),
        None => (Rc::new(Vec::new()), 0.),
    };
    let content_width = estimate.max(width);
    let selected = this.selected_file.clone();
    let row_scroll = this.ui.tree_scroll.clone();
    let list = uniform_list("source-tree-rows", rows.len(), {
        let rows = rows.clone();
        let entity = entity.clone();
        move |range, _, _cx| {
            let mut items = Vec::with_capacity(range.len());
            for index in range {
                if let Some(row) = rows.get(index) {
                    items.push(tree_row(
                        index,
                        row,
                        selected.as_deref(),
                        &entity,
                        content_width,
                        &colors,
                    ));
                }
            }
            items
        }
    })
    // One scroller owns both axes, so a vertical wheel scrolls the tree
    // vertically even when long paths overflow horizontally.
    .with_horizontal_sizing_behavior(ListHorizontalSizingBehavior::Unconstrained)
    .h_full()
    .w_full()
    .track_scroll(&row_scroll);

    let mut tree_area = div()
        .relative()
        .flex_1()
        .min_h_0()
        .w_full()
        .child(list)
        .child(
            div().absolute().inset_0().child(
                Scrollbar::new(&row_scroll)
                    .id("source-tree-scrollbar-vertical")
                    .axis(ScrollbarAxis::Vertical)
                    .viewport_from_layout(),
            ),
        )
        .child(
            div().absolute().inset_0().child(
                Scrollbar::new(&row_scroll)
                    .id("source-tree-scrollbar-horizontal")
                    .axis(ScrollbarAxis::Horizontal)
                    .viewport_from_layout(),
            ),
        );
    if !this.files_loaded && rows.is_empty() {
        tree_area = div()
            .flex_1()
            .min_h_0()
            .w_full()
            .flex()
            .items_center()
            .justify_center()
            .child(
                div()
                    .text_size(px(FONT_SUPPORTING))
                    .text_color(colors.secondary_text)
                    .child(language.text(TextKey::SourceReadingIndexing)),
            );
    }
    div()
        .id("source-files-pane")
        .w(px(width))
        .h_full()
        .flex_shrink_0()
        .flex()
        .flex_col()
        .overflow_hidden()
        .bg(colors.panel)
        .child(search_row)
        .child(tree_area)
        .into_any_element()
}

fn tree_row(
    index: usize,
    row: &tree::TreeRow,
    selected: Option<&str>,
    entity: &Entity<SourceWorkspace>,
    width: f32,
    colors: &StudioColors,
) -> AnyElement {
    let indent = 8. + row.depth as f32 * tree::TREE_INDENT;
    let name = row.name.clone();
    let label = div()
        .flex_1()
        .min_w_0()
        .text_size(px(FONT_SUPPORTING))
        .text_color(colors.text)
        .whitespace_nowrap()
        .text_ellipsis()
        .child(name);
    let path = row.path.clone();
    let mut element = div()
        .id(("source-tree-row", index))
        .h(px(tree::TREE_ROW_HEIGHT))
        .w(px(width))
        .flex_shrink_0()
        .flex()
        .items_center()
        .pl(px(indent))
        .pr(px(8.))
        .cursor_pointer();
    match row.kind {
        tree::TreeRowKind::Directory { expanded } => {
            let directory_path = path.clone();
            let entity = entity.clone();
            element = element
                .child(
                    div()
                        .w(px(12.))
                        .h_full()
                        .flex()
                        .items_center()
                        .justify_center()
                        .child(disclosure(expanded, colors)),
                )
                .child(div().w(px(4.)))
                .child(folder_icon(expanded, colors))
                .child(div().w(px(5.)))
                .child(label)
                .on_click(move |_, _, cx| {
                    let _ = entity.update(cx, |this, cx| {
                        this.ui.toggle_directory(&directory_path);
                        cx.notify();
                    });
                });
        }
        tree::TreeRowKind::File { language } => {
            let is_selected = selected == Some(path.as_str());
            if is_selected {
                element = element.bg(colors.selected_row);
            }
            let file_path = path.clone();
            let entity = entity.clone();
            element = element
                .child(div().w(px(12.)))
                .child(div().w(px(4.)))
                .child(file_icon(language, colors))
                .child(div().w(px(5.)))
                .child(label)
                .on_click(move |_, _, cx| {
                    let _ = entity.update(cx, |this, cx| {
                        this.open_file(file_path.clone(), None, cx);
                    });
                });
        }
    }
    element.into_any_element()
}

fn source_pane(
    this: &SourceWorkspace,
    colors: &StudioColors,
    cx: &Context<SourceWorkspace>,
) -> AnyElement {
    let language = this.language;
    let location = this.selected_location.as_ref();
    let mut header_text = this
        .selected_file
        .as_deref()
        .unwrap_or_else(|| language.text(TextKey::SourceSelectFile))
        .to_owned();
    if let Some(range) = location.and_then(|location| location.range.as_ref()) {
        header_text.push(':');
        header_text.push_str(&range.start_line.to_string());
    }
    let mut header = div()
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .gap(px(8.))
        .child(
            div()
                .flex_1()
                .min_w_0()
                .text_size(px(FONT_BODY))
                .font_weight(FontWeight::SEMIBOLD)
                .text_color(colors.text)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(header_text),
        );
    if location.is_some() {
        header = header
            .child(
                studio::compact_text_button(
                    "source-copy-location",
                    language.text(TextKey::SourceCopyLocation),
                    true,
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.copy_location(cx))),
            )
            .child(
                studio::compact_text_button(
                    "source-open-in-ide",
                    language.text(TextKey::SourceOpenInIde),
                    true,
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.open_location_in_ide(cx))),
            );
    }
    let mut column = div()
        .id("source-pane")
        .flex_1()
        .min_w_0()
        .h_full()
        .flex()
        .flex_col()
        .gap(px(4.))
        .p(px(12.))
        .overflow_hidden()
        .child(header);

    if let Some(snapshot) = this.location_snapshot.as_ref().or(this.snapshot.as_ref()) {
        let provider = language.text(TextKey::SourceProviderLocal).to_owned();
        let revision: String = snapshot.immutable_revision.chars().take(16).collect();
        let status = match this
            .candidate
            .as_ref()
            .filter(|candidate| Some(&candidate.location) == location)
        {
            Some(candidate) => language
                .text(confidence_key(candidate.confidence))
                .to_owned(),
            None => language.text(TextKey::SourceIndexed).to_owned(),
        };
        let summary = language.format(
            TextKey::SourceSnapshotSummary,
            &[
                ("provider", &provider),
                ("revision", &revision),
                ("status", &status),
            ],
        );
        column = column.child(
            div()
                .w_full()
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.secondary_text)
                .whitespace_nowrap()
                .text_ellipsis()
                .child(summary),
        );
    }

    if let Some(candidate) = this.candidate.as_ref() {
        if !candidate.reasons.is_empty() {
            let reasons = candidate
                .reasons
                .iter()
                .map(|reason| localized_reason(language, reason))
                .collect::<Vec<_>>()
                .join(" · ");
            column = column.child(
                div()
                    .w_full()
                    .text_size(px(FONT_SUPPORTING))
                    .text_color(colors.secondary_text)
                    .child(reasons),
            );
        }
    }

    if this
        .content
        .as_ref()
        .is_some_and(|content| content.state == SourceContentState::Stale)
    {
        column = column.child(
            div()
                .w_full()
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.error)
                .child(language.text(TextKey::SourceContentStale)),
        );
    }

    let loading = this.content.is_none() && this.selected_location.is_some();
    let no_selection = this.selected_file.is_none() && this.selected_location.is_none();
    if loading {
        column = column.child(
            div()
                .flex_1()
                .min_h_0()
                .w_full()
                .flex()
                .items_center()
                .justify_center()
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.secondary_text)
                .child(language.text(TextKey::SourceReadingIndexing)),
        );
    } else if !no_selection {
        column = column.child(
            div()
                .flex_1()
                .min_h_0()
                .w_full()
                .child(this.ui.viewer.clone()),
        );
    }
    column.into_any_element()
}

fn splitter(
    id: &'static str,
    kind: SplitterKind,
    fitted: layout::PaneWidths,
    available: f32,
    colors: &StudioColors,
    cx: &Context<SourceWorkspace>,
) -> AnyElement {
    div()
        .id(id)
        .w(px(layout::SPLITTER_WIDTH))
        .h_full()
        .flex_shrink_0()
        .cursor(CursorStyle::ResizeLeftRight)
        .flex()
        .items_center()
        .justify_center()
        .on_mouse_down(
            MouseButton::Left,
            cx.listener(move |this, event: &MouseDownEvent, _, cx| {
                this.ui.drag = Some(SplitterDrag {
                    kind,
                    start_x: f32::from(event.position.x),
                    start_widths: fitted,
                    available,
                });
                cx.notify();
            }),
        )
        .child(div().w(px(1.)).h_full().bg(colors.border))
        .into_any_element()
}

fn message_line(message: String, color: Rgba) -> AnyElement {
    div()
        .w_full()
        .flex_shrink_0()
        .px(px(12.))
        .py(px(8.))
        .text_size(px(FONT_SUPPORTING))
        .text_color(color)
        .child(message)
        .into_any_element()
}

fn pane_status(message: impl Into<SharedString>, color: Rgba) -> AnyElement {
    div()
        .px(px(4.))
        .py(px(2.))
        .text_size(px(FONT_SUPPORTING))
        .text_color(color)
        .child(message.into())
        .into_any_element()
}

fn localized_message(language: Language, message: &str) -> String {
    match parse_ready_summary(message) {
        Some((files, symbols)) => language.format(
            TextKey::SourceReadySummary,
            &[("files", &files), ("symbols", &symbols)],
        ),
        None => message.to_owned(),
    }
}

/// Compose's `READY_SUMMARY_PATTERN = (\d+) files · (\d+) symbols`.
fn parse_ready_summary(message: &str) -> Option<(u64, u64)> {
    let (files, symbols) = message.split_once(" files · ")?;
    let symbols = symbols.strip_suffix(" symbols")?;
    Some((files.parse().ok()?, symbols.parse().ok()?))
}

fn localized_reason(language: Language, reason: &str) -> String {
    let key = match reason {
        "Qualified type matched" => TextKey::SourceReasonQualifiedTypeMatched,
        "Android resource matched" => TextKey::SourceReasonAndroidResourceMatched,
        "Managed symbol matched" => TextKey::SourceReasonManagedSymbolMatched,
        "Method name matched" => TextKey::SourceReasonMethodNameMatched,
        "Symbolizer source path matched" => TextKey::SourceReasonSymbolizerSourcePathMatched,
        "Native symbol name matched" => TextKey::SourceReasonNativeSymbolNameMatched,
        _ => return reason.to_owned(),
    };
    language.text(key).to_owned()
}

fn phase_key(phase: SourceWorkspacePhase) -> TextKey {
    match phase {
        SourceWorkspacePhase::Registering => TextKey::SourcePhaseRegistering,
        SourceWorkspacePhase::ResolvingRevision => TextKey::SourcePhaseResolvingRevision,
        SourceWorkspacePhase::BuildingManifest => TextKey::SourcePhaseBuildingManifest,
        SourceWorkspacePhase::Indexing => TextKey::SourcePhaseIndexing,
        SourceWorkspacePhase::Ready => TextKey::SourcePhaseReady,
        SourceWorkspacePhase::Partial => TextKey::SourcePhasePartial,
        SourceWorkspacePhase::Failed => TextKey::SourcePhaseFailed,
    }
}

fn confidence_key(confidence: ResolutionConfidence) -> TextKey {
    match confidence {
        ResolutionConfidence::Exact => TextKey::SourceConfidenceExact,
        ResolutionConfidence::Probable => TextKey::SourceConfidenceProbable,
        ResolutionConfidence::Weak => TextKey::SourceConfidenceWeak,
    }
}

// ---------------------------------------------------------------------------
// Tree icons: vector ports of `SourceFileTreeIcon.kt` at the same 14px size.
// ---------------------------------------------------------------------------

/// Disclosure triangle for directory rows (8px inside a 12px rail).
fn disclosure(expanded: bool, colors: &StudioColors) -> AnyElement {
    let color = colors.accent;
    canvas(
        |_, _, _| (),
        move |bounds, _, window, _| {
            let origin = bounds.origin;
            let mut path = PathBuilder::stroke(px(1.2));
            if expanded {
                path.move_to(at(origin, 0.5, 2.));
                path.line_to(at(origin, 4., 5.5));
                path.line_to(at(origin, 7.5, 2.));
            } else {
                path.move_to(at(origin, 2., 0.5));
                path.line_to(at(origin, 5.5, 4.));
                path.line_to(at(origin, 2., 7.5));
            }
            if let Ok(path) = path.build() {
                window.paint_path(path, color);
            }
        },
    )
    .flex_shrink_0()
    .size(px(8.))
    .into_any_element()
}

fn folder_icon(expanded: bool, colors: &StudioColors) -> AnyElement {
    let color = colors.info;
    canvas(
        |_, _, _| (),
        move |bounds, _, window, _| {
            let origin = bounds.origin;
            let corner = px(1.5);
            window.paint_quad(quad(
                Bounds::new(at(origin, 1., 3.), size(px(8.), px(4.))),
                corner,
                color.alpha(0.86),
                px(0.),
                transparent_black(),
                BorderStyle::Solid,
            ));
            let (top, height) = if expanded { (5., 7.) } else { (6., 6.) };
            window.paint_quad(quad(
                Bounds::new(at(origin, 1., top), size(px(12.), px(height))),
                corner,
                color,
                px(0.),
                transparent_black(),
                BorderStyle::Solid,
            ));
        },
    )
    .flex_shrink_0()
    .size(px(14.))
    .into_any_element()
}

fn file_icon(language: SourceLanguage, colors: &StudioColors) -> AnyElement {
    let color = match language {
        SourceLanguage::Kotlin => colors.accent,
        SourceLanguage::Java => colors.warning,
        SourceLanguage::Xml => colors.success,
        SourceLanguage::C | SourceLanguage::Cpp => colors.info,
        SourceLanguage::Other => colors.muted_text,
    };
    canvas(
        |_, _, _| (),
        move |bounds, _, window, _| {
            let origin = bounds.origin;
            let corner = px(1.5);
            let outline = Bounds::new(at(origin, 2., 1.), size(px(10.), px(12.)));
            window.paint_quad(quad(
                outline,
                corner,
                color.alpha(0.15),
                px(0.),
                transparent_black(),
                BorderStyle::Solid,
            ));
            window.paint_quad(quad(
                outline,
                corner,
                transparent_black(),
                px(1.),
                color,
                BorderStyle::Solid,
            ));
            let mut path = PathBuilder::stroke(px(1.2));
            match language {
                SourceLanguage::Kotlin => {
                    path.move_to(at(origin, 4., 4.));
                    path.line_to(at(origin, 10., 10.));
                    path.move_to(at(origin, 10., 4.));
                    path.line_to(at(origin, 4., 10.));
                }
                SourceLanguage::Java | SourceLanguage::C | SourceLanguage::Cpp => {
                    path.move_to(at(origin, 4., 5.));
                    path.line_to(at(origin, 10., 5.));
                    path.move_to(at(origin, 4., 8.));
                    path.line_to(at(origin, 9., 8.));
                }
                SourceLanguage::Xml => {
                    path.move_to(at(origin, 4., 5.));
                    path.line_to(at(origin, 6., 7.));
                    path.line_to(at(origin, 4., 9.));
                    path.move_to(at(origin, 10., 5.));
                    path.line_to(at(origin, 8., 7.));
                    path.line_to(at(origin, 10., 9.));
                }
                SourceLanguage::Other => {
                    path.move_to(at(origin, 4., 6.));
                    path.line_to(at(origin, 10., 6.));
                    path.move_to(at(origin, 4., 9.));
                    path.line_to(at(origin, 8., 9.));
                }
            }
            if let Ok(path) = path.build() {
                window.paint_path(path, color);
            }
        },
    )
    .flex_shrink_0()
    .size(px(14.))
    .into_any_element()
}

fn at(origin: Point<Pixels>, x: f32, y: f32) -> Point<Pixels> {
    point(origin.x + px(x), origin.y + px(y))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[::core::prelude::v1::test]
    fn ready_summary_and_reasons_localize_like_compose() {
        assert_eq!(
            localized_message(Language::English, "4 files · 6 symbols"),
            "4 files · 6 symbols"
        );
        assert_eq!(
            localized_message(Language::SimplifiedChinese, "4 files · 6 symbols"),
            "4 个文件 · 6 个符号"
        );
        assert_eq!(localized_message(Language::English, "3/7"), "3/7");
        assert_eq!(
            localized_reason(Language::English, "Qualified type matched"),
            "Qualified type matched"
        );
        assert_eq!(
            localized_reason(Language::SimplifiedChinese, "Method name matched"),
            "方法名匹配"
        );
        assert_eq!(
            localized_reason(Language::English, "Build identity not verified"),
            "Build identity not verified"
        );
        assert_eq!(
            parse_ready_summary("12 files · 429 symbols"),
            Some((12, 429))
        );
        assert_eq!(parse_ready_summary("3/7"), None);
    }

    #[::core::prelude::v1::test]
    fn phase_and_confidence_labels_cover_every_variant() {
        for candidate in [
            SourceWorkspacePhase::Registering,
            SourceWorkspacePhase::ResolvingRevision,
            SourceWorkspacePhase::BuildingManifest,
            SourceWorkspacePhase::Indexing,
            SourceWorkspacePhase::Ready,
            SourceWorkspacePhase::Partial,
            SourceWorkspacePhase::Failed,
        ] {
            assert!(!Language::English.text(phase_key(candidate)).is_empty());
        }
        for candidate in [
            ResolutionConfidence::Exact,
            ResolutionConfidence::Probable,
            ResolutionConfidence::Weak,
        ] {
            assert!(!Language::English.text(confidence_key(candidate)).is_empty());
        }
    }
}

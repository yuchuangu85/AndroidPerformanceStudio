//! Typed HPROF Memory Profiler workspace (Compose parity).
//!
//! All heap analysis runs in the isolated `aps-memory` worker; this module owns
//! the GPUI state machine, background queries and the Compose-shaped page.
pub(super) mod auxiliary;
pub(super) mod classes;
pub(super) mod comparison;
pub(super) mod dashboard;
pub(super) mod dominators;
pub(super) mod exports;
pub(super) mod inspector;
pub(super) mod leaks;
pub(super) mod mapping;
pub(crate) mod menus;
pub(super) mod state;
pub(super) mod toolbar;
pub(super) mod widgets;

#[cfg(debug_assertions)]
static DEBUG_MAPPING_IMPORTED: std::sync::atomic::AtomicBool =
    std::sync::atomic::AtomicBool::new(false);

use super::*;
use crate::studio;
use aps_memory::{ClassifierColumn, ClassifierRow, MemoryFilter, MemorySnapshot, SortDirection};
use gpui_kit::{
    component::input::{InputEvent, InputState},
    prelude::*,
    *,
};
use state::MemoryViewMode;
use std::sync::Arc;

pub(super) const MEMORY_INSTANCE_PAGE: usize = 500;
pub(super) const MEMORY_DOMINATOR_PAGE: usize = 500;
pub(super) const MEMORY_ARRAY_PAGE: usize = 200;

impl ImportWorkspace {
    pub(super) fn render_memory_profiler(
        &mut self,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let colors = studio::StudioColors::from_theme(cx);
        let mut root = div()
            .id("memory-profiler-workspace")
            .size_full()
            .min_h_0()
            .flex()
            .flex_col()
            .bg(colors.workspace)
            .text_color(colors.text);
        root = root
            .child(self.render_memory_toolbar(window, &colors, cx))
            .child(studio::divider(&colors))
            .child(self.render_memory_snapshot_tabs(&colors, cx))
            .child(studio::divider(&colors))
            .child(self.render_memory_evidence(&colors, cx));
        let content = match self.memory_state.view_mode {
            MemoryViewMode::Dashboard => self.render_memory_dashboard(&colors, cx),
            MemoryViewMode::Classes => self.render_memory_classes(window, &colors, cx),
            MemoryViewMode::Dominators => self.render_memory_dominators(window, &colors, cx),
            MemoryViewMode::Diff => self.render_memory_comparison(&colors, cx),
            MemoryViewMode::BitmapDump => self.render_memory_bitmaps(&colors, cx),
            MemoryViewMode::NativeHeap => self.render_memory_native_heap(&colors, cx),
            MemoryViewMode::SampledArt => self.render_memory_art_samples(&colors, cx),
            MemoryViewMode::LeakAnalysis => self.render_memory_leak_analysis(&colors, cx),
        };
        root.child(content)
            .child(self.render_memory_status(&colors, cx))
            .into_any_element()
    }

    // ---- view / filter -------------------------------------------------

    pub(super) fn memory_set_view_mode(&mut self, mode: MemoryViewMode, cx: &mut Context<Self>) {
        if self.memory_state.view_mode == mode {
            return;
        }
        self.memory_state.view_mode = mode;
        if mode == MemoryViewMode::Dominators
            && self.memory_state.snapshot.is_some()
            && self.memory_state.dominator_roots.is_none()
        {
            self.memory_load_dominators(None, cx);
        }
        if mode == MemoryViewMode::Diff && !self.memory_state.comparison_loading {
            self.memory_compare_snapshots(cx);
        }
        cx.notify();
    }

    pub(super) fn memory_apply_filter(&mut self, filter: MemoryFilter, cx: &mut Context<Self>) {
        if self.memory_state.filter == filter {
            return;
        }
        let class_changed = self.memory_state.filter.heap != filter.heap
            || self.memory_state.filter.scope != filter.scope
            || self.memory_state.filter.leak_filter != filter.leak_filter
            || self.memory_state.filter.arrange_by != filter.arrange_by
            || self.memory_state.filter.search != filter.search
            || self.memory_state.filter.match_case != filter.match_case
            || self.memory_state.filter.use_regex != filter.use_regex;
        self.memory_state.filter = filter;
        if class_changed {
            self.memory_state.refresh_presenter();
            // The selected classifier may no longer be visible; keep the
            // selection only when its id is still present.
            if let Some(id) = self.memory_state.selected_classifier_id.clone() {
                if !self
                    .memory_state
                    .classifier_rows
                    .iter()
                    .any(|row| row.id == id)
                {
                    self.memory_state.selected_classifier_id = None;
                    self.memory_state.selected_class_label = None;
                    self.memory_state.selected_class_name = None;
                    self.memory_state.selected_class_heap = None;
                    self.memory_state.selected_class_names.clear();
                    self.memory_state.instances = None;
                    self.memory_state.selected_object = None;
                    self.memory_state.detail = None;
                }
            }
        }
        cx.notify();
    }

    pub(super) fn memory_set_heap_filter(&mut self, heap: Option<String>, cx: &mut Context<Self>) {
        let mut filter = self.memory_state.filter.clone();
        filter.heap = heap;
        self.memory_apply_filter(filter, cx);
    }

    pub(super) fn memory_set_scope(
        &mut self,
        scope: aps_memory::ClassScope,
        cx: &mut Context<Self>,
    ) {
        let mut filter = self.memory_state.filter.clone();
        filter.scope = scope;
        self.memory_apply_filter(filter, cx);
    }

    pub(super) fn memory_set_leak_filter(
        &mut self,
        leak_filter: aps_memory::LeakFilter,
        cx: &mut Context<Self>,
    ) {
        let mut filter = self.memory_state.filter.clone();
        filter.leak_filter = leak_filter;
        self.memory_apply_filter(filter, cx);
    }

    pub(super) fn memory_set_arrange(
        &mut self,
        arrange: aps_memory::ArrangeBy,
        cx: &mut Context<Self>,
    ) {
        let mut filter = self.memory_state.filter.clone();
        filter.arrange_by = arrange;
        self.memory_apply_filter(filter, cx);
    }

    pub(super) fn memory_set_search(&mut self, text: String, cx: &mut Context<Self>) {
        let mut filter = self.memory_state.filter.clone();
        filter.search = text;
        self.memory_apply_filter(filter, cx);
    }

    pub(super) fn memory_set_match_case(&mut self, value: bool, cx: &mut Context<Self>) {
        let mut filter = self.memory_state.filter.clone();
        filter.match_case = value;
        self.memory_apply_filter(filter, cx);
    }

    pub(super) fn memory_set_regex(&mut self, value: bool, cx: &mut Context<Self>) {
        let mut filter = self.memory_state.filter.clone();
        filter.use_regex = value;
        self.memory_apply_filter(filter, cx);
    }

    pub(super) fn memory_sort_classifier(
        &mut self,
        column: ClassifierColumn,
        cx: &mut Context<Self>,
    ) {
        if self.memory_state.sort_column == column {
            self.memory_state.sort_direction = match self.memory_state.sort_direction {
                SortDirection::Ascending => SortDirection::Descending,
                SortDirection::Descending => SortDirection::Ascending,
            };
        } else {
            self.memory_state.sort_column = column;
            self.memory_state.sort_direction = SortDirection::Descending;
        }
        self.memory_state.refresh_presenter();
        cx.notify();
    }

    pub(super) fn memory_toggle_classifier(&mut self, row: &ClassifierRow, cx: &mut Context<Self>) {
        self.memory_state.toggle_classifier(row);
        cx.notify();
    }

    pub(super) fn memory_set_dashboard_sort(
        &mut self,
        sort: state::DashboardSort,
        cx: &mut Context<Self>,
    ) {
        self.memory_state.dashboard_sort = sort;
        cx.notify();
    }

    pub(super) fn memory_highlight_class(&mut self, class_name: String, cx: &mut Context<Self>) {
        self.memory_state.highlighted_class = Some(class_name);
        cx.notify();
    }

    pub(super) fn memory_set_leak_tab(&mut self, tab: state::LeakTab, cx: &mut Context<Self>) {
        self.memory_state.leak_tab = tab;
        cx.notify();
    }

    // ---- class / instance selection ------------------------------------

    pub(super) fn memory_select_classifier(&mut self, row: ClassifierRow, cx: &mut Context<Self>) {
        if row.heap_name.is_some() && row.depth == 0 {
            self.memory_state.toggle_classifier(&row);
            cx.notify();
            return;
        }
        let class_names = classifier_class_names(&row);
        if class_names.is_empty() {
            self.memory_state.toggle_classifier(&row);
            cx.notify();
            return;
        }
        self.memory_state.selected_classifier_id = Some(row.id.clone());
        self.memory_state.selected_class_label = Some(row.label.clone());
        self.memory_state.selected_class_name = row.class_name.clone();
        self.memory_state.selected_class_heap = row
            .heap_name
            .clone()
            .or_else(|| self.memory_state.filter.heap.clone());
        self.memory_state.selected_class_names = class_names.clone();
        self.memory_state.instances = None;
        self.memory_state.instance_offset = 0;
        self.memory_state.selected_object = None;
        self.memory_state.detail = None;
        self.memory_state.history.clear();
        self.memory_state.history_index = None;
        self.memory_query_instances(class_names, 0, cx);
    }

    pub(super) fn memory_load_instance_page(&mut self, offset: usize, cx: &mut Context<Self>) {
        if self.memory_state.selected_class_names.is_empty() {
            return;
        }
        let names = self.memory_state.selected_class_names.clone();
        self.memory_query_instances(names, offset, cx);
    }

    fn memory_query_instances(
        &mut self,
        class_names: Vec<String>,
        offset: usize,
        cx: &mut Context<Self>,
    ) {
        let Some(Session::Memory(session)) = self.session.clone() else {
            return;
        };
        self.memory_state.selection_generation += 1;
        let generation = self.memory_state.selection_generation;
        let heap = self.memory_state.selected_class_heap.clone();
        let cancel = CancellationToken::new();
        self.memory_state.instances_loading = true;
        let task = cx.background_executor().spawn(async move {
            let mut session = session
                .lock()
                .map_err(|_| "Memory session is unavailable".to_owned())?;
            session
                .instances_page(
                    &class_names,
                    heap.as_deref(),
                    offset,
                    MEMORY_INSTANCE_PAGE,
                    &cancel,
                )
                .map(Arc::new)
                .map_err(|error| error.to_string())
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.memory_state.selection_generation != generation {
                    return;
                }
                this.memory_state.instances_loading = false;
                match result {
                    Ok(page) => {
                        this.memory_state.instance_offset = page.offset as usize;
                        this.memory_state.instances = Some(page);
                    }
                    Err(error) => this.memory_state.error = Some(error),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    pub(super) fn memory_select_instance(&mut self, object_id: u64, cx: &mut Context<Self>) {
        self.memory_state.selected_object = Some(object_id);
        self.memory_state.push_history(object_id);
        self.memory_load_inspector_object(object_id, 0, cx);
    }

    pub(super) fn memory_load_inspector_object(
        &mut self,
        object_id: u64,
        array_start: u64,
        cx: &mut Context<Self>,
    ) {
        let Some(Session::Memory(session)) = self.session.clone() else {
            return;
        };
        self.memory_state.detail_generation += 1;
        let generation = self.memory_state.detail_generation;
        let cancel = CancellationToken::new();
        self.memory_state.detail_loading = true;
        self.memory_state.detail = None;
        let task = cx.background_executor().spawn(async move {
            let mut session = session
                .lock()
                .map_err(|_| "Memory session is unavailable".to_owned())?;
            session
                .detail_page(object_id, array_start as usize, MEMORY_ARRAY_PAGE, &cancel)
                .map(|detail| detail.map(Arc::new))
                .map_err(|error| error.to_string())
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.memory_state.detail_generation != generation {
                    return;
                }
                this.memory_state.detail_loading = false;
                match result {
                    Ok(detail) => this.memory_state.detail = detail,
                    Err(error) => this.memory_state.error = Some(error),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    pub(super) fn memory_close_inspector(&mut self, cx: &mut Context<Self>) {
        self.memory_state.selected_object = None;
        self.memory_state.detail = None;
        self.memory_state.detail_loading = false;
        cx.notify();
    }

    pub(super) fn memory_set_inspector_tab(
        &mut self,
        tab: state::InspectorTab,
        cx: &mut Context<Self>,
    ) {
        self.memory_state.inspector_tab = tab;
        cx.notify();
    }

    pub(super) fn memory_set_nearest_root_only(&mut self, value: bool, cx: &mut Context<Self>) {
        self.memory_state.nearest_root_only = value;
        cx.notify();
    }

    // ---- dominators ----------------------------------------------------

    pub(super) fn memory_load_dominators(&mut self, parent: Option<u64>, cx: &mut Context<Self>) {
        let Some(Session::Memory(session)) = self.session.clone() else {
            return;
        };
        self.memory_state.dominator_generation += 1;
        let generation = self.memory_state.dominator_generation;
        if let Some(parent) = parent {
            if !self.memory_state.dominator_loading.insert(parent) {
                return;
            }
        }
        let cancel = CancellationToken::new();
        let task = cx.background_executor().spawn(async move {
            let mut session = session
                .lock()
                .map_err(|_| "Memory session is unavailable".to_owned())?;
            session
                .dominators_page(parent, 0, MEMORY_DOMINATOR_PAGE, &cancel)
                .map(Arc::new)
                .map_err(|error| error.to_string())
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.memory_state.dominator_generation != generation {
                    return;
                }
                if let Some(parent) = parent {
                    this.memory_state.dominator_loading.remove(&parent);
                }
                match result {
                    Ok(page) => {
                        let total = page.total;
                        if let Some(parent) = parent {
                            this.memory_state.dominator_children.insert(parent, page);
                        } else {
                            this.memory_state.dominator_roots = Some(page);
                        }
                        if let Some(parent) = parent {
                            this.memory_state
                                .dominator_child_totals
                                .insert(parent, total);
                        }
                    }
                    Err(error) => this.memory_state.dominator_error = Some(error),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    pub(super) fn memory_toggle_dominator(&mut self, object_id: u64, cx: &mut Context<Self>) {
        if self.memory_state.expanded_dominators.remove(&object_id) {
            cx.notify();
            return;
        }
        self.memory_state.expanded_dominators.insert(object_id);
        if !self
            .memory_state
            .dominator_children
            .contains_key(&object_id)
        {
            self.memory_load_dominators(Some(object_id), cx);
        }
        cx.notify();
    }

    pub(super) fn memory_select_dominator(&mut self, object_id: u64, cx: &mut Context<Self>) {
        self.memory_state.selected_dominator = Some(object_id);
        self.memory_select_instance(object_id, cx);
    }

    // ---- comparison ----------------------------------------------------

    pub(super) fn memory_set_comparison_pair(&mut self, a: u64, b: u64, cx: &mut Context<Self>) {
        self.memory_state.comparison_pair = Some((a, b));
        self.memory_compare_snapshots(cx);
    }

    pub(super) fn memory_compare_snapshots(&mut self, cx: &mut Context<Self>) {
        // An explicit pair is authoritative only while both tabs exist. The
        // default is the newest inactive snapshot as A against the active
        // snapshot as B; stale ids from a closed tab fall back to that default.
        let stored = self
            .memory_state
            .comparison_pair
            .filter(|(a, b)| self.memory_snapshot_pair(*a, *b).is_some());
        let pair = stored.or_else(|| {
            let tabs = self.memory_snapshot_tabs();
            let active = self.memory_snapshot_id?;
            menus::default_comparison_pair(&tabs, active)
        });
        let Some((a_id, b_id)) = pair else {
            self.memory_state.comparison = None;
            self.memory_state.comparison_error = Some(
                self.language
                    .text(TextKey::MemoryPageDiffUnavailable)
                    .to_owned(),
            );
            cx.notify();
            return;
        };
        self.memory_state.comparison_pair = Some((a_id, b_id));
        let Some((a, b, a_label, b_label)) = self.memory_snapshot_pair(a_id, b_id) else {
            self.memory_state.comparison = None;
            self.memory_state.comparison_error = Some(
                self.language
                    .text(TextKey::MemoryPageDiffUnavailable)
                    .to_owned(),
            );
            cx.notify();
            return;
        };
        if Arc::ptr_eq(&a, &b) {
            self.memory_state.comparison = None;
            self.memory_state.comparison_error = Some(
                self.language
                    .text(TextKey::MemoryDiffSameSnapshot)
                    .to_owned(),
            );
            cx.notify();
            return;
        }
        self.memory_state.comparison_generation += 1;
        let generation = self.memory_state.comparison_generation;
        self.memory_state.comparison_loading = true;
        self.memory_state.comparison_error = None;
        let task = cx
            .background_executor()
            .spawn(async move { comparison::compare(&a, &b, a_label, b_label) });
        cx.spawn(async move |this, cx| {
            let report = task.await;
            this.update(cx, |this, cx| {
                if this.memory_state.comparison_generation != generation {
                    return;
                }
                this.memory_state.comparison_loading = false;
                this.memory_state.comparison = Some(Arc::new(report));
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    fn memory_snapshot_pair(
        &self,
        a: u64,
        b: u64,
    ) -> Option<(Arc<MemorySnapshot>, Arc<MemorySnapshot>, String, String)> {
        let mut a_snapshot = None;
        let mut b_snapshot = None;
        let mut a_label = String::new();
        let mut b_label = String::new();
        for tab in &self.memory_snapshots {
            if tab.id == a {
                a_snapshot = tab.state.snapshot.clone();
                a_label = snapshot_label(&tab.path);
            }
            if tab.id == b {
                b_snapshot = tab.state.snapshot.clone();
                b_label = snapshot_label(&tab.path);
            }
        }
        if self.memory_snapshot_id == Some(a) {
            a_snapshot = self.memory_state.snapshot.clone();
            a_label = self.memory_state.snapshot_label.clone();
        }
        if self.memory_snapshot_id == Some(b) {
            b_snapshot = self.memory_state.snapshot.clone();
            b_label = self.memory_state.snapshot_label.clone();
        }
        Some((a_snapshot?, b_snapshot?, a_label, b_label))
    }

    // ---- search inputs -------------------------------------------------

    pub(super) fn memory_class_search_input(
        &mut self,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Entity<InputState> {
        if self.memory_state.search_input.is_none() {
            let language = self.language;
            let input = cx.new(|cx| {
                InputState::new(window, cx)
                    .placeholder(language.text(TextKey::MemoryPageFilterClasses))
                    .validate(|text, _| text.len() <= 256)
            });
            let subscription = cx.subscribe(&input, |this, input, event, cx| {
                if matches!(event, InputEvent::Change) {
                    let text = input.read(cx).value().to_string();
                    this.memory_set_search(text, cx);
                }
            });
            self.memory_state.search_input = Some(input);
            self.memory_state.search_subscription = Some(subscription);
        }
        let input = self
            .memory_state
            .search_input
            .clone()
            .expect("memory class search input");
        let language = self.language;
        if self.memory_state.search_language != Some(language) {
            self.memory_state.search_language = Some(language);
            input.update(cx, |state, cx| {
                state.set_placeholder(language.text(TextKey::MemoryPageFilterClasses), window, cx)
            });
        }
        let wanted = self.memory_state.filter.search.clone();
        if input.read(cx).value() != wanted {
            input.update(cx, |state, cx| state.set_value(wanted, window, cx));
        }
        input
    }

    pub(super) fn memory_dominator_search_input(
        &mut self,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Entity<InputState> {
        if self.memory_state.dominator_search_input.is_none() {
            let language = self.language;
            let input = cx.new(|cx| {
                InputState::new(window, cx)
                    .placeholder(language.text(TextKey::MemoryPageFilterClasses))
                    .validate(|text, _| text.len() <= 256)
            });
            let subscription = cx.subscribe(&input, |this, input, event, cx| {
                if matches!(event, InputEvent::Change) {
                    let text = input.read(cx).value().to_string();
                    this.memory_set_search(text, cx);
                }
            });
            self.memory_state.dominator_search_input = Some(input);
            self.memory_state.dominator_search_subscription = Some(subscription);
        }
        let input = self
            .memory_state
            .dominator_search_input
            .clone()
            .expect("memory dominator search input");
        let language = self.language;
        if self.memory_state.dominator_search_language != Some(language) {
            self.memory_state.dominator_search_language = Some(language);
            input.update(cx, |state, cx| {
                state.set_placeholder(language.text(TextKey::MemoryPageFilterClasses), window, cx)
            });
        }
        let wanted = self.memory_state.filter.search.clone();
        if input.read(cx).value() != wanted {
            input.update(cx, |state, cx| state.set_value(wanted, window, cx));
        }
        input
    }

    // ---- loading -------------------------------------------------------

    pub(super) fn memory_begin_load(&mut self, cx: &mut Context<Self>) {
        let Some(Session::Memory(session)) = self.session.clone() else {
            return;
        };
        let label = self
            .path
            .as_deref()
            .map(snapshot_label)
            .unwrap_or_else(|| "HPROF snapshot".to_owned());
        self.memory_state.load_generation += 1;
        let generation = self.memory_state.load_generation;
        self.memory_state.loading = true;
        self.memory_state.loading_message = Some(
            self.language
                .text(TextKey::MemoryPageLoadingSnapshot)
                .to_owned(),
        );
        self.memory_state.error = None;
        let cancel = self.cancel.clone();
        let task = cx.background_executor().spawn(async move {
            let mut session = session
                .lock()
                .map_err(|_| "Memory session is unavailable".to_owned())?;
            session
                .snapshot(&cancel)
                .map(Arc::new)
                .map_err(|error| error.to_string())
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.memory_state.load_generation != generation {
                    return;
                }
                this.memory_state.loading = false;
                this.memory_state.loading_message = None;
                match result {
                    Ok(snapshot) => {
                        this.memory_state.reset_for_snapshot(snapshot, label);
                        #[cfg(debug_assertions)]
                        this.memory_debug_seed(cx);
                        if this.memory_state.view_mode == MemoryViewMode::Dominators {
                            this.memory_load_dominators(None, cx);
                        }
                    }
                    Err(error) => this.memory_state.error = Some(error),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    // ---- verification seam ---------------------------------------------

    /// Debug-only launch seam used by native screenshot verification. It never
    /// runs in release builds and never fabricates evidence: it only selects a
    /// published view/row that a user could select by clicking.
    #[cfg(debug_assertions)]
    fn memory_debug_seed(&mut self, cx: &mut Context<Self>) {
        let Ok(view) = std::env::var("APS_MEMORY_VIEW") else {
            return;
        };
        let mode = match view.as_str() {
            "dashboard" | "overview" => MemoryViewMode::Dashboard,
            "classes" => MemoryViewMode::Classes,
            "dominators" => MemoryViewMode::Dominators,
            "diff" => MemoryViewMode::Diff,
            "leaks" => MemoryViewMode::LeakAnalysis,
            "bitmaps" => MemoryViewMode::BitmapDump,
            "native" => MemoryViewMode::NativeHeap,
            "art" => MemoryViewMode::SampledArt,
            _ => return,
        };
        self.memory_state.view_mode = mode;
        if mode == MemoryViewMode::Dominators {
            self.memory_load_dominators(None, cx);
        }
        if mode != MemoryViewMode::Classes {
            cx.notify();
            return;
        }
        if std::env::var_os("APS_MEMORY_EXPAND_HEAPS").is_some() {
            let rows = self.memory_state.classifier_rows.clone();
            for row in &rows {
                self.memory_state
                    .expanded_classifiers
                    .insert(row.id.clone(), true);
            }
            fn first_leaf(rows: &[ClassifierRow], wanted: Option<&str>) -> Option<ClassifierRow> {
                for row in rows {
                    if let Some(name) = row.class_name.as_ref() {
                        if wanted.is_none_or(|wanted| wanted == name) {
                            return Some(row.clone());
                        }
                    }
                    if let Some(found) = first_leaf(&row.children, wanted) {
                        return Some(found);
                    }
                }
                None
            }
            let wanted = std::env::var("APS_MEMORY_SELECT_CLASS").ok();
            if let Some(leaf) = first_leaf(&rows, wanted.as_deref()) {
                self.memory_select_classifier(leaf, cx);
            }
        }
        if let Ok(object) = std::env::var("APS_MEMORY_SELECT_OBJECT")
            && let Ok(object) = u64::from_str_radix(object.trim_start_matches("0x"), 16)
        {
            self.memory_select_instance(object, cx);
        }
        if matches!(
            std::env::var("APS_MEMORY_INSPECTOR_TAB").as_deref(),
            Ok("references")
        ) {
            self.memory_state.inspector_tab = state::InspectorTab::References;
        }
        if std::env::var_os("APS_MEMORY_NEAREST_ROOT").is_some() {
            self.memory_state.nearest_root_only = true;
        }
        if let Some(path) = std::env::var_os("APS_MEMORY_MAPPING")
            && !DEBUG_MAPPING_IMPORTED.swap(true, std::sync::atomic::Ordering::Relaxed)
        {
            self.memory_import_mapping(std::path::PathBuf::from(path), cx);
        }
        cx.notify();
    }

    // ---- AI context ----------------------------------------------------

    pub(super) fn memory_analysis_request(&self) -> Result<aps_ai::AnalysisRequest, String> {
        let Some(snapshot) = self.memory_state.snapshot.as_ref() else {
            return Err(self
                .language
                .text(TextKey::ImportUiImportBeforeAi)
                .to_owned());
        };
        let session = self.session_id.as_ref().ok_or_else(|| {
            self.language
                .text(TextKey::ImportUiImportBeforeAi)
                .to_owned()
        })?;
        let mut lines = Vec::new();
        lines.push(format!("Snapshot digest: {}", snapshot.source_digest));
        lines.push(format!(
            "Heap: {} objects, {} bytes shallow, {} classes; heaps: {}",
            snapshot.summary.object_count,
            snapshot.summary.shallow_size,
            snapshot.summary.class_count,
            snapshot.metadata.heaps.join(", ")
        ));
        lines.push(format!(
            "Leak suspects: {}; activity leaks: {}; lifecycle objects: {}; bitmap instances: {}",
            snapshot.findings.leak_suspects.total,
            snapshot.findings.activities.total,
            snapshot.findings.lifecycle.total,
            snapshot.findings.bitmaps.total
        ));
        let mut rows = Vec::new();
        for row in self
            .memory_state
            .classifier_rows
            .iter()
            .flat_map(flatten_classifier)
            .filter_map(|row| row.class_name.clone().map(|name| (name, row)))
            .take(16)
        {
            let (name, row) = row;
            lines.push(format!(
                "{}: count {}, shallow {}, retained {}",
                name,
                row.total_count,
                row.shallow_size,
                row.retained_size
                    .map_or_else(|| "unavailable".to_owned(), |value| value.to_string())
            ));
            rows.push(name);
        }
        let payload = aps_json::Value::Object(
            [
                (
                    "objectCount".to_owned(),
                    aps_json::Value::from(snapshot.summary.object_count),
                ),
                (
                    "shallowSize".to_owned(),
                    aps_json::Value::from(snapshot.summary.shallow_size),
                ),
                (
                    "classCount".to_owned(),
                    aps_json::Value::from(snapshot.summary.class_count),
                ),
                (
                    "classes".to_owned(),
                    aps_json::Value::Array(rows.into_iter().map(aps_json::Value::from).collect()),
                ),
            ]
            .into_iter()
            .collect(),
        );
        Ok(aps_ai::AnalysisRequest {
            session_id: session.clone(),
            run_id: format!("{session}-memory-g{}", self.generation.max(1)),
            generation: self.generation.max(1),
            profiler: "memory".into(),
            scope: "Offline HPROF snapshot, visible class aggregates only".into(),
            evidence: vec![aps_ai::Evidence {
                id: format!("memory-summary-{}", snapshot.source_digest),
                kind: "memory-histogram".into(),
                summary: lines.join("; "),
                payload,
            }],
            candidates: Vec::new(),
            user_question: None,
        })
    }
}

pub(super) fn snapshot_label(path: &std::path::Path) -> String {
    path.file_name()
        .and_then(|name| name.to_str())
        .filter(|name| !name.is_empty())
        .unwrap_or("HPROF snapshot")
        .to_owned()
}

pub(super) fn classifier_class_names(row: &ClassifierRow) -> Vec<String> {
    fn collect(row: &ClassifierRow, out: &mut Vec<String>) {
        if let Some(name) = row.class_name.as_ref() {
            if !out.contains(name) {
                out.push(name.clone());
            }
            return;
        }
        for child in &row.children {
            collect(child, out);
        }
    }
    let mut names = Vec::new();
    collect(row, &mut names);
    names
}

fn flatten_classifier(row: &ClassifierRow) -> Vec<ClassifierRow> {
    let mut out = vec![row.clone()];
    for child in &row.children {
        out.extend(flatten_classifier(child));
    }
    out
}

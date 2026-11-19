//! Typed Memory Profiler workspace state, mirroring the Compose page state.
//!
//! The UI renders from one published aps-memory snapshot plus bounded query
//! results from the isolated HPROF worker. No heap graph or raw object value
//! enters this state.
pub(crate) use aps_memory::{
    ClassDelta as ClassifierDelta, SnapshotComparison as ComparisonReport,
};
use aps_memory::{
    ClassListSummary, ClassifierColumn, ClassifierRow, DominatorsPage, InstanceDetail, InstanceRow,
    InstancesPage, MemoryFilter, MemorySnapshot, SortDirection,
};
use aps_ui_foundation::{Language, TextKey};
use gpui_kit::{component::input::InputState, *};
use std::collections::{BTreeMap, BTreeSet};
use std::rc::Rc;
use std::sync::Arc;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum MemoryViewMode {
    Dashboard,
    Classes,
    Dominators,
    Diff,
    BitmapDump,
    NativeHeap,
    SampledArt,
    LeakAnalysis,
}

impl MemoryViewMode {
    pub(super) const PRIMARY: [Self; 4] =
        [Self::Dashboard, Self::Classes, Self::Dominators, Self::Diff];

    pub(super) fn label(self) -> TextKey {
        match self {
            Self::Dashboard => TextKey::MemoryAppDashboardView,
            Self::Classes => TextKey::MemoryAppClassListView,
            Self::Dominators => TextKey::MemoryAppDominatorsView,
            Self::Diff => TextKey::MemoryAppDiffView,
            Self::BitmapDump => TextKey::MemoryAppBitmapDumpView,
            Self::NativeHeap => TextKey::MemoryAppNativeHeapView,
            Self::SampledArt => TextKey::MemoryAppSampledArtView,
            Self::LeakAnalysis => TextKey::MemoryAppMemoryLeaksView,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum InspectorTab {
    Fields,
    References,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum DashboardSort {
    Count,
    Shallow,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum LeakTab {
    Suspects,
    Activities,
    Lifecycle,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub(crate) enum MemoryTable {
    Dashboard,
    Classifier,
    Instances,
    Inspector,
    Dominators,
    Comparison,
    LeakSuspects,
    LeakActivities,
    LeakLifecycle,
}

#[derive(Clone, Copy, Debug)]
pub(crate) struct ColumnResizeOrigin {
    pub table: MemoryTable,
    pub column: usize,
    pub pointer_x: f32,
    pub width: f32,
}

impl LeakTab {
    pub(super) fn label(self) -> TextKey {
        match self {
            Self::Suspects => TextKey::MemoryPageLeakSuspects,
            Self::Activities => TextKey::MemoryPageActivityFragmentLeak,
            Self::Lifecycle => TextKey::MemoryPageLifecycleLeaks,
        }
    }
}

/// Which File > Export rows have live evidence for the published state.
/// `ImportWorkspace::memory_export_enabled` remains authoritative because a
/// raw HPROF copy additionally needs the retained source path and session.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub(crate) struct MemoryExportAvailability {
    pub raw_hprof: bool,
    pub histogram_csv: bool,
    pub snapshot_json: bool,
    pub class_instances_csv: bool,
    pub object_investigation_json: bool,
    pub diff_csv: bool,
    pub investigation_report: bool,
}

struct ClassifierRenderSource {
    roots: Vec<ClassifierRow>,
    leak_ids: BTreeSet<String>,
    duplicate_classes: BTreeSet<String>,
}

struct VisibleClassifier {
    path: Vec<usize>,
    expanded: bool,
}

/// The virtual list and its callbacks share one tree, not a subtree copy per row.
pub(super) struct ClassifierRenderRows {
    source: Rc<ClassifierRenderSource>,
    visible: Vec<VisibleClassifier>,
}

impl ClassifierRenderRows {
    pub(super) fn len(&self) -> usize {
        self.visible.len()
    }

    pub(super) fn is_empty(&self) -> bool {
        self.visible.is_empty()
    }

    pub(super) fn get(&self, index: usize) -> Option<&ClassifierRow> {
        let (root, children) = self.visible.get(index)?.path.split_first()?;
        let mut row = self.source.roots.get(*root)?;
        for child in children {
            row = row.children.get(*child)?;
        }
        Some(row)
    }

    pub(super) fn expanded(&self, index: usize) -> bool {
        self.visible.get(index).is_some_and(|row| row.expanded)
    }

    pub(super) fn is_leak(&self, row: &ClassifierRow) -> bool {
        self.source.leak_ids.contains(&row.id)
    }

    pub(super) fn is_duplicate(&self, row: &ClassifierRow) -> bool {
        row.class_name
            .as_deref()
            .is_some_and(|name| self.source.duplicate_classes.contains(name))
    }
}

struct ClassifierRenderCache {
    rows: Rc<ClassifierRenderRows>,
    expanded: BTreeMap<String, bool>,
    heap_filter_active: bool,
}

/// Immutable snapshot + presenter projections for the active HPROF tab.
pub(crate) struct MemoryState {
    pub(super) capture_artifact: Option<Arc<super::capture::transport::CapturedHeap>>,
    pub view_mode: MemoryViewMode,
    pub snapshot: Option<Arc<MemorySnapshot>>,
    pub snapshot_label: String,
    pub loading: bool,
    pub loading_message: Option<String>,
    pub error: Option<String>,
    pub notice: Option<String>,
    pub filter: MemoryFilter,
    pub sort_column: ClassifierColumn,
    pub sort_direction: SortDirection,
    pub classifier_rows: Vec<ClassifierRow>,
    classifier_render_cache: Option<ClassifierRenderCache>,
    pub class_summary: ClassListSummary,
    pub leak_classes: BTreeSet<String>,
    pub leak_object_ids: BTreeSet<u64>,
    pub duplicate_classes: BTreeSet<String>,
    /// True once an R8/ProGuard mapping.txt was imported for this snapshot.
    pub mapping_loaded: bool,
    /// Number of class mappings carried by the imported mapping.
    pub mapping_entries: usize,
    /// Snapshot/selection-bound export capabilities for the menu and status UI.
    pub export_availability: MemoryExportAvailability,
    /// Explicit expansion overrides keyed by classifier row id.
    pub expanded_classifiers: BTreeMap<String, bool>,
    pub selected_classifier_id: Option<String>,
    pub selected_class_label: Option<String>,
    pub selected_class_name: Option<String>,
    pub selected_class_heap: Option<String>,
    pub selected_class_names: Vec<String>,
    pub instances: Option<Arc<InstancesPage>>,
    pub instances_loading: bool,
    pub instance_offset: usize,
    pub selected_object: Option<u64>,
    pub detail: Option<Arc<InstanceDetail>>,
    pub detail_loading: bool,
    pub inspector_tab: InspectorTab,
    pub nearest_root_only: bool,
    pub inspector_nearest_auto_expand: bool,
    /// Explicit selection is scoped to the current object and inspector tab.
    pub inspector_selected_path: Option<String>,
    /// Inspector-local expansion (drill-in rows keep their chevron state).
    pub inspector_expanded: BTreeSet<String>,
    /// Array pages are independent for each expanded field path.
    pub inspector_field_array_offsets: BTreeMap<String, u64>,
    /// References-tree expansion is path-local so repeated object ids do not
    /// collapse or expand every occurrence at once.
    pub inspector_reference_expanded: BTreeSet<String>,
    /// Loaded child details used to render expanded References rows in place.
    pub inspector_reference_details: BTreeMap<(u64, u64), Arc<InstanceDetail>>,
    pub inspector_reference_loading: BTreeSet<(u64, u64)>,
    pub inspector_reference_generation: u64,
    /// 0 = name, 1 = depth, 2 = shallow, 3 = retained; ascending flag follows.
    pub inspector_sort_column: u8,
    pub inspector_sort_ascending: bool,
    pub history: Vec<u64>,
    pub history_index: Option<usize>,
    pub pinned: BTreeSet<u64>,
    pub dominator_roots: Option<Arc<DominatorsPage>>,
    pub dominator_children: BTreeMap<u64, Arc<DominatorsPage>>,
    pub dominator_child_totals: BTreeMap<u64, u64>,
    pub expanded_dominators: BTreeSet<u64>,
    pub dominator_loading: BTreeSet<u64>,
    pub dominator_error: Option<String>,
    pub selected_dominator: Option<u64>,
    pub dashboard_sort: DashboardSort,
    pub highlighted_class: Option<String>,
    pub leak_tab: LeakTab,
    pub comparison_pair: Option<(u64, u64)>,
    pub comparison: Option<Arc<ComparisonReport>>,
    pub comparison_loading: bool,
    pub comparison_error: Option<String>,
    pub search_input: Option<Entity<InputState>>,
    pub dominator_search_input: Option<Entity<InputState>>,
    pub search_subscription: Option<Subscription>,
    pub dominator_search_subscription: Option<Subscription>,
    pub search_language: Option<Language>,
    pub dominator_search_language: Option<Language>,
    pub load_generation: u64,
    pub selection_generation: u64,
    pub detail_generation: u64,
    pub comparison_generation: u64,
    pub dominator_generation: u64,
    /// Persistent list scroll positions so filters/expansion do not jump.
    pub classifier_scroll: UniformListScrollHandle,
    pub instance_scroll: UniformListScrollHandle,
    pub inspector_scroll: UniformListScrollHandle,
    pub dominator_scroll: UniformListScrollHandle,
    pub dashboard_scroll: UniformListScrollHandle,
    pub comparison_scroll: UniformListScrollHandle,
    pub leaks_scroll: UniformListScrollHandle,
    pub dashboard_column_widths: [f32; 4],
    pub classifier_column_widths: [f32; 7],
    pub instance_column_widths: [f32; 6],
    pub inspector_column_widths: [f32; 6],
    pub dominator_column_widths: [f32; 3],
    pub comparison_column_widths: [f32; 10],
    pub leak_suspects_column_widths: [f32; 4],
    pub leak_activities_column_widths: [f32; 5],
    pub leak_lifecycle_column_widths: [f32; 3],
    pub column_resize_origin: Option<ColumnResizeOrigin>,
}

impl Default for MemoryState {
    fn default() -> Self {
        Self::new()
    }
}

impl MemoryState {
    pub(crate) fn new() -> Self {
        Self {
            capture_artifact: None,
            view_mode: MemoryViewMode::Classes,
            snapshot: None,
            snapshot_label: String::new(),
            loading: false,
            loading_message: None,
            error: None,
            notice: None,
            filter: MemoryFilter::default(),
            sort_column: ClassifierColumn::RetainedSize,
            sort_direction: SortDirection::Descending,
            classifier_rows: Vec::new(),
            classifier_render_cache: None,
            class_summary: ClassListSummary::default(),
            leak_classes: BTreeSet::new(),
            leak_object_ids: BTreeSet::new(),
            duplicate_classes: BTreeSet::new(),
            mapping_loaded: false,
            mapping_entries: 0,
            export_availability: MemoryExportAvailability::default(),
            expanded_classifiers: BTreeMap::new(),
            selected_classifier_id: None,
            selected_class_label: None,
            selected_class_name: None,
            selected_class_heap: None,
            selected_class_names: Vec::new(),
            instances: None,
            instances_loading: false,
            instance_offset: 0,
            selected_object: None,
            detail: None,
            detail_loading: false,
            inspector_tab: InspectorTab::Fields,
            nearest_root_only: false,
            inspector_nearest_auto_expand: false,
            inspector_selected_path: None,
            inspector_expanded: BTreeSet::new(),
            inspector_field_array_offsets: BTreeMap::new(),
            inspector_reference_expanded: BTreeSet::new(),
            inspector_reference_details: BTreeMap::new(),
            inspector_reference_loading: BTreeSet::new(),
            inspector_reference_generation: 0,
            inspector_sort_column: 0,
            inspector_sort_ascending: true,
            history: Vec::new(),
            history_index: None,
            pinned: BTreeSet::new(),
            dominator_roots: None,
            dominator_children: BTreeMap::new(),
            dominator_child_totals: BTreeMap::new(),
            expanded_dominators: BTreeSet::new(),
            dominator_loading: BTreeSet::new(),
            dominator_error: None,
            selected_dominator: None,
            dashboard_sort: DashboardSort::Count,
            highlighted_class: None,
            leak_tab: LeakTab::Suspects,
            comparison_pair: None,
            comparison: None,
            comparison_loading: false,
            comparison_error: None,
            search_input: None,
            dominator_search_input: None,
            search_subscription: None,
            dominator_search_subscription: None,
            search_language: None,
            dominator_search_language: None,
            load_generation: 0,
            selection_generation: 0,
            detail_generation: 0,
            comparison_generation: 0,
            dominator_generation: 0,
            classifier_scroll: UniformListScrollHandle::new(),
            instance_scroll: UniformListScrollHandle::new(),
            inspector_scroll: UniformListScrollHandle::new(),
            dominator_scroll: UniformListScrollHandle::new(),
            dashboard_scroll: UniformListScrollHandle::new(),
            comparison_scroll: UniformListScrollHandle::new(),
            leaks_scroll: UniformListScrollHandle::new(),
            dashboard_column_widths: [220., 96., 112., 140.],
            classifier_column_widths: [220., 104., 104., 104., 104., 104., 104.],
            instance_column_widths: [220., 60., 104., 96., 120., 120.],
            inspector_column_widths: [180., 72., 68., 70., 76., 74.],
            dominator_column_widths: [220., 80., 80.],
            comparison_column_widths: [220., 96., 96., 96., 112., 112., 112., 112., 112., 112.],
            leak_suspects_column_widths: [220., 220., 78., 96.],
            leak_activities_column_widths: [220., 220., 78., 78., 96.],
            leak_lifecycle_column_widths: [108., 220., 78.],
            column_resize_origin: None,
        }
    }

    pub(super) fn loaded(&self) -> bool {
        self.snapshot.is_some()
    }

    /// Recomputes presenter projections after a snapshot or filter change.
    pub(super) fn refresh_presenter(&mut self) {
        self.classifier_render_cache = None;
        let Some(snapshot) = self.snapshot.clone() else {
            self.classifier_rows.clear();
            self.class_summary = ClassListSummary::default();
            self.leak_classes.clear();
            self.leak_object_ids.clear();
            self.duplicate_classes.clear();
            self.refresh_export_availability();
            return;
        };
        self.leak_classes = aps_memory::leak_class_names(&snapshot);
        self.leak_object_ids = aps_memory::leak_object_ids(&snapshot);
        self.duplicate_classes = aps_memory::duplicate_bitmap_classes(&snapshot);
        self.classifier_rows = aps_memory::classifier_rows(
            &snapshot,
            &self.filter,
            self.sort_column,
            self.sort_direction,
        );
        self.class_summary = aps_memory::class_list_summary(&snapshot, &self.filter);
        self.refresh_export_availability();
    }

    pub(super) fn select_inspector_row(&mut self, path: String) {
        self.inspector_selected_path = Some(path);
        self.inspector_nearest_auto_expand = false;
    }

    pub(in crate::import) fn invalidate_comparison(&mut self) {
        self.comparison_generation = self.comparison_generation.wrapping_add(1);
        self.comparison = None;
        self.comparison_loading = false;
        self.comparison_error = None;
        self.refresh_export_availability();
    }

    pub(super) fn select_comparison_pair(&mut self, pair: (u64, u64)) {
        if self.comparison_pair != Some(pair) {
            self.invalidate_comparison();
            self.comparison_pair = Some(pair);
        }
    }

    pub(super) fn apply_comparison_result(
        &mut self,
        generation: u64,
        pair: (u64, u64),
        result: Result<ComparisonReport, String>,
    ) -> bool {
        if self.comparison_generation != generation
            || self.comparison_pair != Some(pair)
            || !self.comparison_loading
        {
            return false;
        }
        self.comparison_loading = false;
        match result {
            Ok(report) => {
                self.comparison = Some(Arc::new(report));
                self.comparison_error = None;
            }
            Err(error) => {
                self.comparison = None;
                self.comparison_error = Some(error);
            }
        }
        self.refresh_export_availability();
        true
    }

    pub(super) fn current_comparison(
        &self,
        before: &MemorySnapshot,
        after: &MemorySnapshot,
    ) -> Option<Arc<ComparisonReport>> {
        if self.loading || self.comparison_loading || self.comparison_error.is_some() {
            return None;
        }
        let (a, b) = self.comparison_pair?;
        let report = self.comparison.as_ref()?;
        (a != b && report.matches_snapshots(before, after)).then(|| report.clone())
    }

    /// Recomputes the export-capability mirror after the snapshot or a
    /// selection-bound page changes. The workspace helper adds the raw-HPROF
    /// source-path/session precondition on top of this projection.
    pub(super) fn refresh_export_availability(&mut self) {
        let loaded = self.snapshot.is_some();
        self.export_availability = MemoryExportAvailability {
            raw_hprof: loaded,
            histogram_csv: loaded,
            snapshot_json: loaded,
            class_instances_csv: self.instances.is_some(),
            object_investigation_json: self.detail.is_some(),
            diff_csv: self.comparison.is_some(),
            investigation_report: loaded,
        };
    }

    /// Stop old callbacks from publishing after a tab is suspended or replaced.
    /// Published pages stay available when the same tab is restored.
    pub(in crate::import) fn invalidate_pending_queries(&mut self) {
        self.load_generation = self.load_generation.wrapping_add(1);
        self.selection_generation = self.selection_generation.wrapping_add(1);
        self.detail_generation = self.detail_generation.wrapping_add(1);
        self.inspector_reference_generation = self.inspector_reference_generation.wrapping_add(1);
        self.dominator_generation = self.dominator_generation.wrapping_add(1);
        self.comparison_generation = self.comparison_generation.wrapping_add(1);
        self.loading = false;
        self.loading_message = None;
        self.instances_loading = false;
        self.detail_loading = false;
        self.inspector_reference_loading.clear();
        self.dominator_loading.clear();
        self.comparison_loading = false;
    }

    pub(super) fn begin_dominator_query(&mut self, parent: Option<u64>) -> Option<u64> {
        if let Some(parent) = parent
            && !self.dominator_loading.insert(parent)
        {
            return None;
        }
        // Parent requests are independent; only snapshot replacement invalidates them.
        Some(self.dominator_generation)
    }

    pub(super) fn missing_dominator_queries(&self) -> Vec<Option<u64>> {
        let mut requests = Vec::new();
        if self.dominator_roots.is_none() {
            requests.push(None);
        }
        requests.extend(self.expanded_dominators.iter().filter_map(|&parent| {
            (!self.dominator_children.contains_key(&parent)
                && !self.dominator_loading.contains(&parent))
            .then_some(Some(parent))
        }));
        requests
    }

    pub(super) fn apply_dominator_query(
        &mut self,
        generation: u64,
        parent: Option<u64>,
        result: Result<Arc<DominatorsPage>, String>,
    ) -> bool {
        if self.dominator_generation != generation {
            return false;
        }
        if let Some(parent) = parent {
            self.dominator_loading.remove(&parent);
        }
        match result {
            Ok(page) => {
                let total = page.total;
                if let Some(parent) = parent {
                    self.dominator_children.insert(parent, page);
                    self.dominator_child_totals.insert(parent, total);
                } else {
                    self.dominator_roots = Some(page);
                }
            }
            Err(error) => self.dominator_error = Some(error),
        }
        true
    }

    pub(in crate::import) fn reset_for_snapshot(
        &mut self,
        snapshot: Arc<MemorySnapshot>,
        label: String,
    ) {
        self.invalidate_pending_queries();
        let history = std::mem::take(&mut self.history);
        let _ = history;
        self.view_mode = MemoryViewMode::Classes;
        self.snapshot = Some(snapshot);
        self.snapshot_label = label;
        self.loading = false;
        self.loading_message = None;
        self.error = None;
        self.notice = None;
        self.filter = MemoryFilter::default();
        self.filter.heap = self.snapshot.as_ref().and_then(|snapshot| {
            snapshot
                .per_heap_classes
                .keys()
                .find(|name| name.eq_ignore_ascii_case("app"))
                .cloned()
        });
        self.sort_column = ClassifierColumn::RetainedSize;
        self.sort_direction = SortDirection::Descending;
        self.expanded_classifiers.clear();
        self.selected_classifier_id = None;
        self.selected_class_label = None;
        self.selected_class_name = None;
        self.selected_class_heap = None;
        self.selected_class_names.clear();
        self.instances = None;
        self.instances_loading = false;
        self.instance_offset = 0;
        self.selected_object = None;
        self.detail = None;
        self.detail_loading = false;
        self.inspector_tab = InspectorTab::Fields;
        self.nearest_root_only = false;
        self.inspector_nearest_auto_expand = false;
        self.inspector_selected_path = None;
        self.inspector_expanded.clear();
        self.inspector_field_array_offsets.clear();
        self.inspector_reference_expanded.clear();
        self.inspector_reference_details.clear();
        self.inspector_reference_loading.clear();
        self.inspector_sort_column = 0;
        self.inspector_sort_ascending = true;
        self.history.clear();
        self.history_index = None;
        self.pinned.clear();
        self.dominator_roots = None;
        self.dominator_children.clear();
        self.dominator_child_totals.clear();
        self.expanded_dominators.clear();
        self.dominator_loading.clear();
        self.dominator_error = None;
        self.selected_dominator = None;
        self.dashboard_sort = DashboardSort::Count;
        self.highlighted_class = None;
        self.leak_tab = LeakTab::Suspects;
        self.mapping_loaded = false;
        self.mapping_entries = 0;
        self.comparison_pair = None;
        self.comparison = None;
        self.comparison_loading = false;
        self.comparison_error = None;
        self.refresh_presenter();
    }

    /// Visibility of a classifier row; heap roots default collapsed unless a
    /// heap filter is active, nested groups and leaves default expanded exactly
    /// like the Compose table.
    pub(super) fn classifier_expanded(&self, row: &ClassifierRow) -> bool {
        if let Some(value) = self.expanded_classifiers.get(&row.id) {
            return *value;
        }
        row.heap_name.is_none() || row.depth != 0 || self.filter.heap.is_some()
    }

    pub(super) fn toggle_classifier(&mut self, row: &ClassifierRow) {
        let next = !self.classifier_expanded(row);
        self.expanded_classifiers.insert(row.id.clone(), next);
    }

    /// Cached display-order paths into an immutable copy of the presenter tree.
    pub(super) fn visible_classifier_rows(&mut self) -> Rc<ClassifierRenderRows> {
        let heap_filter_active = self.filter.heap.is_some();
        // Debug restore writes overrides directly, so compare the exact map
        // rather than relying only on toggle_classifier to invalidate it.
        if let Some(cache) = &self.classifier_render_cache
            && cache.expanded == self.expanded_classifiers
            && cache.heap_filter_active == heap_filter_active
        {
            return cache.rows.clone();
        }
        let source = self.classifier_render_cache.as_ref().map_or_else(
            || {
                Rc::new(ClassifierRenderSource {
                    roots: self.classifier_rows.clone(),
                    leak_ids: classifier_leak_ids(&self.classifier_rows, &self.leak_classes),
                    duplicate_classes: self.duplicate_classes.clone(),
                })
            },
            |cache| cache.rows.source.clone(),
        );
        fn push(
            rows: &[ClassifierRow],
            state: &MemoryState,
            path: &mut Vec<usize>,
            out: &mut Vec<VisibleClassifier>,
        ) {
            for (index, row) in rows.iter().enumerate() {
                path.push(index);
                let expanded = !row.children.is_empty() && state.classifier_expanded(row);
                out.push(VisibleClassifier {
                    path: path.clone(),
                    expanded,
                });
                if expanded {
                    push(&row.children, state, path, out);
                }
                path.pop();
            }
        }
        let mut visible = Vec::new();
        push(&source.roots, self, &mut Vec::new(), &mut visible);
        let rows = Rc::new(ClassifierRenderRows { source, visible });
        self.classifier_render_cache = Some(ClassifierRenderCache {
            rows: rows.clone(),
            expanded: self.expanded_classifiers.clone(),
            heap_filter_active,
        });
        rows
    }

    pub(super) fn summary_metrics(&self) -> aps_memory::HeapSummary {
        let Some(snapshot) = self.snapshot.as_ref() else {
            return aps_memory::HeapSummary::default();
        };
        if self.view_mode == MemoryViewMode::Classes {
            aps_memory::HeapSummary {
                object_count: self.class_summary.total_count,
                shallow_size: self.class_summary.total_shallow_size,
                retained_size: self.class_summary.total_retained_size,
                class_count: self.class_summary.class_count,
                heap_segments: snapshot.summary.heap_segments.clone(),
            }
        } else {
            snapshot.summary.clone()
        }
    }

    pub(super) fn leak_suspect_count(&self) -> u64 {
        self.snapshot
            .as_ref()
            .map_or(0, |snapshot| snapshot.findings.leak_suspects.total)
    }

    pub(super) fn activity_count(&self) -> u64 {
        self.snapshot
            .as_ref()
            .map_or(0, |snapshot| snapshot.findings.activities.total)
    }

    pub(super) fn selected_class_instances(&self) -> &[InstanceRow] {
        self.instances
            .as_ref()
            .map_or(&[], |page| page.rows.as_slice())
    }

    pub(super) fn instance_count(&self) -> usize {
        self.instances.as_ref().map_or(0, |page| page.rows.len())
    }

    pub(super) fn push_history(&mut self, object_id: u64) {
        if self.history.last().copied() == Some(object_id) {
            return;
        }
        let truncate = self
            .history_index
            .map_or(self.history.len(), |index| index + 1);
        self.history.truncate(truncate);
        self.history.push(object_id);
        self.history_index = Some(self.history.len() - 1);
    }
}

/// Group warnings include leaking descendants even when the group is collapsed.
fn classifier_leak_ids(
    rows: &[ClassifierRow],
    leak_classes: &BTreeSet<String>,
) -> BTreeSet<String> {
    fn walk(
        row: &ClassifierRow,
        leak_classes: &BTreeSet<String>,
        out: &mut BTreeSet<String>,
    ) -> bool {
        let mut leak = row
            .class_name
            .as_deref()
            .is_some_and(|name| leak_classes.contains(name));
        for child in &row.children {
            leak |= walk(child, leak_classes, out);
        }
        if leak {
            out.insert(row.id.clone());
        }
        leak
    }
    let mut out = BTreeSet::new();
    for row in rows {
        walk(row, leak_classes, &mut out);
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_memory::{ArrangeBy, ClassScope, ClassStats, LeakFilter};

    fn dominator_page(parent: u64) -> Arc<DominatorsPage> {
        Arc::new(DominatorsPage {
            total: 1,
            offset: 0,
            rows: vec![aps_memory::DominatorRow {
                object_id: parent + 1,
                class_name: "Node".into(),
                shallow_size: 8,
                retained_size: 8,
                depth: 1,
                parent_object_id: Some(parent),
            }],
        })
    }

    #[::core::prelude::v1::test]
    fn concurrent_dominator_expansions_and_duplicate_requests_keep_both_results() {
        let mut state = MemoryState::new();
        let first = state.begin_dominator_query(Some(10)).unwrap();
        let second = state.begin_dominator_query(Some(20)).unwrap();
        assert_eq!(first, second, "independent parents share a snapshot epoch");
        assert_eq!(state.begin_dominator_query(Some(10)), None);
        assert_eq!(
            state.dominator_generation, first,
            "a duplicate must not invalidate its original request"
        );
        let first_page = dominator_page(10);
        let second_page = dominator_page(20);
        assert!(state.apply_dominator_query(second, Some(20), Ok(second_page.clone())));
        assert!(state.dominator_loading.contains(&10));
        assert!(!state.dominator_loading.contains(&20));
        assert!(state.apply_dominator_query(first, Some(10), Ok(first_page.clone())));
        assert!(state.dominator_loading.is_empty());
        assert_eq!(state.dominator_children[&10], first_page);
        assert_eq!(state.dominator_children[&20], second_page);
        assert_eq!(
            state.dominator_child_totals,
            BTreeMap::from([(10, 1), (20, 1)])
        );
    }

    #[::core::prelude::v1::test]
    fn dominator_failure_clears_only_its_request_and_allows_retry() {
        let mut state = MemoryState::new();
        let first = state.begin_dominator_query(Some(10)).unwrap();
        let second = state.begin_dominator_query(Some(20)).unwrap();
        assert!(state.apply_dominator_query(first, Some(10), Err("injected query error".into())));
        assert_eq!(
            state.dominator_error.as_deref(),
            Some("injected query error")
        );
        assert_eq!(state.dominator_loading, BTreeSet::from([20]));
        let retry = state.begin_dominator_query(Some(10)).unwrap();
        assert!(state.apply_dominator_query(retry, Some(10), Ok(dominator_page(10))));
        assert!(state.apply_dominator_query(second, Some(20), Ok(dominator_page(20))));
        assert!(state.dominator_loading.is_empty());
    }

    #[::core::prelude::v1::test]
    fn comparison_selection_invalidates_reports_exports_and_late_results() {
        let before = comparison_snapshot('a', 1);
        let after = comparison_snapshot('b', 2);
        let report =
            aps_memory::compare_snapshots(&before, &after, "A".into(), "B".into()).unwrap();
        let mut state = MemoryState::new();
        state.snapshot = Some(Arc::new(after.clone()));
        state.select_comparison_pair((1, 2));
        state.comparison_loading = true;
        let generation = state.comparison_generation;
        assert!(state.apply_comparison_result(generation, (1, 2), Ok(report.clone())));
        assert!(state.current_comparison(&before, &after).is_some());
        assert!(state.export_availability.diff_csv);

        state.select_comparison_pair((1, 3));
        assert!(state.comparison.is_none());
        assert!(!state.export_availability.diff_csv);
        assert!(!state.apply_comparison_result(generation, (1, 2), Ok(report)));
        assert!(state.current_comparison(&before, &after).is_none());

        state.comparison_loading = true;
        let current = state.comparison_generation;
        assert!(state.apply_comparison_result(current, (1, 3), Err("mapping mismatch".into())));
        assert_eq!(state.comparison_error.as_deref(), Some("mapping mismatch"));
        assert!(!state.export_availability.diff_csv);
    }

    fn comparison_snapshot(digest: char, count: u64) -> MemorySnapshot {
        let mut snapshot = MemorySnapshot {
            source_digest: digest.to_string().repeat(64),
            classes: vec![aps_memory::ClassStats {
                class_name: "example.C".into(),
                instance_count: count,
                shallow_size: count * 8,
                retained_size: Some(count * 16),
                ..aps_memory::ClassStats::default()
            }],
            ..MemorySnapshot::default()
        };
        snapshot.metadata.class_count = 1;
        snapshot
    }

    #[::core::prelude::v1::test]
    fn comparison_revalidates_endpoint_identity_and_rerun_generation() {
        let before = comparison_snapshot('a', 1);
        let after = comparison_snapshot('b', 2);
        let report =
            aps_memory::compare_snapshots(&before, &after, "A".into(), "B".into()).unwrap();
        let mut state = MemoryState::new();
        state.select_comparison_pair((1, 2));
        state.comparison_loading = true;
        let generation = state.comparison_generation;
        assert!(state.apply_comparison_result(generation, (1, 2), Ok(report.clone())));
        let mut remapped = after.clone();
        remapped.mapping_digest = Some("c".repeat(64));
        assert!(state.current_comparison(&before, &remapped).is_none());
        assert!(state.current_comparison(&after, &before).is_none());
        state.invalidate_comparison();
        assert_ne!(state.comparison_generation, generation);
        assert!(state.comparison.is_none());
        assert!(!state.apply_comparison_result(generation, (1, 2), Ok(report)));
    }

    #[::core::prelude::v1::test]
    fn invalidating_a_report_preserves_explicit_pair_and_delta_direction() {
        let a = comparison_snapshot('a', 1);
        let b = comparison_snapshot('b', 3);
        let report = aps_memory::compare_snapshots(&b, &a, "B".into(), "A".into()).unwrap();
        let mut state = MemoryState::new();
        state.select_comparison_pair((2, 1));
        for mode in [MemoryViewMode::Classes, MemoryViewMode::Diff] {
            state.view_mode = mode;
            state.comparison_loading = true;
            let generation = state.comparison_generation;
            assert!(state.apply_comparison_result(generation, (2, 1), Ok(report.clone())));
            let current = state.current_comparison(&b, &a).unwrap();
            assert_eq!(
                i128::from(current.rows[0].b_count) - i128::from(current.rows[0].a_count),
                -2
            );
            state.invalidate_comparison();
            assert_eq!(state.comparison_pair, Some((2, 1)));
            assert!(state.current_comparison(&b, &a).is_none());
            assert!(!state.apply_comparison_result(generation, (2, 1), Ok(report.clone())));
        }
    }

    #[::core::prelude::v1::test]
    fn suspending_a_snapshot_invalidates_pending_queries_but_preserves_published_data() {
        let mut state = MemoryState::new();
        state.reset_for_snapshot(snapshot(&["app.Widget"]), "original".into());
        let published = dominator_page(5);
        state.dominator_roots = Some(published.clone());
        state.loading = true;
        state.loading_message = Some("pending".into());
        state.instances_loading = true;
        state.detail_loading = true;
        state.comparison_loading = true;
        state.inspector_reference_loading.insert((42, 0));
        let old = state.begin_dominator_query(Some(10)).unwrap();
        let snapshot_before = state.snapshot.clone();
        state.invalidate_pending_queries();
        assert!(!state.loading && state.loading_message.is_none());
        assert!(!state.instances_loading && !state.detail_loading && !state.comparison_loading);
        assert!(state.inspector_reference_loading.is_empty() && state.dominator_loading.is_empty());
        assert_eq!(state.snapshot, snapshot_before);
        assert_eq!(state.dominator_roots, Some(published));
        assert!(!state.apply_dominator_query(old, Some(10), Ok(dominator_page(10))));
        let retry = state.begin_dominator_query(Some(10)).unwrap();
        assert_ne!(old, retry);
        assert!(!state.apply_dominator_query(old, Some(10), Err("stale".into())));
        assert!(
            state.dominator_loading.contains(&10),
            "old completion cannot clear the new request"
        );
        assert!(state.apply_dominator_query(retry, Some(10), Ok(dominator_page(10))));
        assert!(state.dominator_loading.is_empty());
    }

    #[::core::prelude::v1::test]
    fn replacing_snapshot_rejects_old_root_results_even_at_epoch_wrap() {
        let mut state = MemoryState::new();
        state.dominator_generation = u64::MAX;
        let old = state.begin_dominator_query(None).unwrap();
        state.reset_for_snapshot(snapshot(&["app.Replacement"]), "replacement".into());
        assert_eq!(state.dominator_generation, 0);
        assert!(!state.apply_dominator_query(old, None, Ok(dominator_page(10))));
        assert!(state.dominator_roots.is_none());
        let new = state.begin_dominator_query(None).unwrap();
        let roots = dominator_page(20);
        assert!(state.apply_dominator_query(new, None, Ok(roots.clone())));
        assert_eq!(state.dominator_roots, Some(roots));
    }

    #[::core::prelude::v1::test]
    fn restored_dominator_queries_resume_only_missing_pages_without_invalidating_siblings() {
        let mut state = MemoryState::new();
        state.dominator_roots = Some(dominator_page(1));
        state.expanded_dominators.extend([10, 20, 30]);
        let completed = dominator_page(10);
        state.dominator_children.insert(10, completed.clone());
        let pending = state.begin_dominator_query(Some(20)).unwrap();
        assert_eq!(state.missing_dominator_queries(), vec![Some(30)]);
        state.invalidate_pending_queries();
        assert_eq!(state.missing_dominator_queries(), vec![Some(20), Some(30)]);
        for parent in state.missing_dominator_queries() {
            let generation = state.begin_dominator_query(parent).unwrap();
            assert!(state.apply_dominator_query(
                generation,
                parent,
                Ok(dominator_page(parent.unwrap()))
            ));
        }
        assert!(!state.apply_dominator_query(pending, Some(20), Err("stale".into())));
        assert_eq!(state.dominator_children[&10], completed);
        assert!(state.missing_dominator_queries().is_empty());
        assert!(state.dominator_loading.is_empty());
    }

    fn classifier_tree() -> ClassifierRow {
        ClassifierRow {
            id: "heap:App".into(),
            label: "App".into(),
            heap_name: Some("App".into()),
            children: vec![ClassifierRow {
                id: "group:app".into(),
                label: "app".into(),
                depth: 1,
                children: vec![ClassifierRow {
                    id: "class:app.Widget".into(),
                    label: "Widget".into(),
                    class_name: Some("app.Widget".into()),
                    depth: 2,
                    ..ClassifierRow::default()
                }],
                ..ClassifierRow::default()
            }],
            ..ClassifierRow::default()
        }
    }

    fn snapshot(app_names: &[&str]) -> Arc<MemorySnapshot> {
        let classes = |names: &[&str]| -> Vec<ClassStats> {
            names
                .iter()
                .map(|name| ClassStats {
                    class_name: (*name).into(),
                    instance_count: 1,
                    shallow_size: 16,
                    retained_size: Some(16),
                    ..ClassStats::default()
                })
                .collect()
        };
        let app = classes(app_names);
        let zygote = classes(&["java.lang.String"]);
        Arc::new(MemorySnapshot {
            classes: app.iter().chain(&zygote).cloned().collect(),
            per_heap_classes: BTreeMap::from([("App".into(), app), ("Zygote".into(), zygote)]),
            ..MemorySnapshot::default()
        })
    }

    fn visible_rows(state: &mut MemoryState) -> Vec<ClassifierRow> {
        let rows = state.visible_classifier_rows();
        (0..rows.len())
            .map(|index| rows.get(index).unwrap().clone())
            .collect()
    }

    fn visible_ids(state: &mut MemoryState) -> Vec<String> {
        visible_rows(state).into_iter().map(|row| row.id).collect()
    }

    fn uncached_visible_rows(state: &MemoryState) -> Vec<ClassifierRow> {
        fn push(rows: &[ClassifierRow], state: &MemoryState, out: &mut Vec<ClassifierRow>) {
            for row in rows {
                out.push(row.clone());
                if !row.children.is_empty() && state.classifier_expanded(row) {
                    push(&row.children, state, out);
                }
            }
        }
        let mut rows = Vec::new();
        push(&state.classifier_rows, state, &mut rows);
        rows
    }

    fn visible_class_names(state: &mut MemoryState) -> Vec<String> {
        visible_rows(state)
            .into_iter()
            .filter_map(|row| row.class_name)
            .collect()
    }

    fn findings_snapshot() -> Arc<MemorySnapshot> {
        let mut sample = snapshot(&[
            "app.alpha.Widget",
            "app.beta.Other",
            "android.app.Activity",
            "android.graphics.Bitmap",
        ]);
        let sample_mut = Arc::make_mut(&mut sample);
        sample_mut.source_digest = "a".repeat(64);
        sample_mut.metadata.class_count = sample_mut.classes.len() as u64;
        sample_mut.findings.leak_suspects.total = 1;
        sample_mut.findings.leak_suspects.rows = vec![aps_memory::LeakSuspect {
            class_name: "app.alpha.Widget".into(),
            reason: "Static/singleton field Holder.widget retains an Activity".into(),
            activity_or_fragment_leak: true,
            ..aps_memory::LeakSuspect::default()
        }];
        sample_mut.findings.bitmaps.total = 2;
        sample_mut.findings.bitmaps.rows = (1..=2)
            .map(|object_id| aps_memory::BitmapInstance {
                object_id,
                class_name: "android.graphics.Bitmap".into(),
                bitmap_id: Some(42),
                ..aps_memory::BitmapInstance::default()
            })
            .collect();
        sample
    }

    #[::core::prelude::v1::test]
    fn classifier_cache_filter_transitions_match_uncached_rows_and_expected_classes() {
        let sample = findings_snapshot();
        let app = || MemoryFilter {
            heap: Some("App".into()),
            ..MemoryFilter::default()
        };
        let all_app = vec![
            "android.app.Activity",
            "android.graphics.Bitmap",
            "app.alpha.Widget",
            "app.beta.Other",
        ];
        let cases = [
            (app(), all_app.clone()),
            (
                MemoryFilter {
                    search: "widget".into(),
                    ..app()
                },
                vec!["app.alpha.Widget"],
            ),
            (
                MemoryFilter {
                    search: "widget".into(),
                    match_case: true,
                    ..app()
                },
                vec![],
            ),
            (
                MemoryFilter {
                    search: "^app\\.alpha\\.".into(),
                    use_regex: true,
                    ..app()
                },
                vec!["app.alpha.Widget"],
            ),
            (
                MemoryFilter {
                    search: "[".into(),
                    use_regex: true,
                    ..app()
                },
                vec![],
            ),
            (
                MemoryFilter {
                    scope: ClassScope::Project,
                    ..app()
                },
                vec!["app.alpha.Widget", "app.beta.Other"],
            ),
            (
                MemoryFilter {
                    scope: ClassScope::System,
                    ..app()
                },
                vec!["android.app.Activity", "android.graphics.Bitmap"],
            ),
            (
                MemoryFilter {
                    leak_filter: LeakFilter::AllIssues,
                    ..app()
                },
                vec!["android.graphics.Bitmap", "app.alpha.Widget"],
            ),
            (
                MemoryFilter {
                    leak_filter: LeakFilter::ActivityFragmentLeak,
                    ..app()
                },
                vec!["app.alpha.Widget"],
            ),
            (
                MemoryFilter {
                    leak_filter: LeakFilter::DuplicateBitmaps,
                    ..app()
                },
                vec!["android.graphics.Bitmap"],
            ),
            (
                MemoryFilter {
                    heap: Some("Zygote".into()),
                    ..MemoryFilter::default()
                },
                vec!["java.lang.String"],
            ),
            (
                MemoryFilter::default(),
                vec![
                    "android.app.Activity",
                    "android.graphics.Bitmap",
                    "app.alpha.Widget",
                    "app.beta.Other",
                    "java.lang.String",
                ],
            ),
            (
                MemoryFilter {
                    heap: Some("Missing".into()),
                    ..MemoryFilter::default()
                },
                vec![],
            ),
            (app(), all_app),
        ];
        for arrange_by in [ArrangeBy::Class, ArrangeBy::Package] {
            let mut state = MemoryState::new();
            state.reset_for_snapshot(sample.clone(), "filters".into());
            let mut previous = state.visible_classifier_rows();
            for (filter, expected) in &cases {
                state.filter = MemoryFilter {
                    arrange_by,
                    ..filter.clone()
                };
                state.refresh_presenter();
                for root in &state.classifier_rows {
                    state.expanded_classifiers.insert(root.id.clone(), true);
                }
                let current = state.visible_classifier_rows();
                assert!(!Rc::ptr_eq(&previous.source, &current.source));
                assert_eq!(visible_rows(&mut state), uncached_visible_rows(&state));
                let mut names = visible_class_names(&mut state);
                names.sort();
                assert_eq!(&names, expected, "{arrange_by:?}: {filter:?}");
                assert!(Rc::ptr_eq(&current, &state.visible_classifier_rows()));
                previous = current;
            }
        }
    }

    #[::core::prelude::v1::test]
    fn classifier_cache_metric_sorts_preserve_null_zero_and_new_row_indices() {
        let mut sample = snapshot(&["app.Alpha", "app.Beta", "app.Gamma"]);
        let sample_mut = Arc::make_mut(&mut sample);
        let classes = sample_mut.per_heap_classes.get_mut("App").unwrap();
        for (index, stats) in classes.iter_mut().enumerate() {
            stats.instance_count = [3, 1, 2][index];
            stats.shallow_size = [10, 30, 20][index];
            stats.native_size = [None, Some(0), Some(10)][index];
            stats.retained_native_size = [Some(5), None, Some(0)][index];
            stats.retained_size = [Some(30), Some(0), None][index];
        }
        let mut state = MemoryState::new();
        state.reset_for_snapshot(sample, "sort".into());
        let cases = [
            (
                ClassifierColumn::Name,
                ["app.Alpha", "app.Beta", "app.Gamma"],
                ["app.Gamma", "app.Beta", "app.Alpha"],
            ),
            (
                ClassifierColumn::TotalCount,
                ["app.Beta", "app.Gamma", "app.Alpha"],
                ["app.Alpha", "app.Gamma", "app.Beta"],
            ),
            (
                ClassifierColumn::ShallowSize,
                ["app.Alpha", "app.Gamma", "app.Beta"],
                ["app.Beta", "app.Gamma", "app.Alpha"],
            ),
            (
                ClassifierColumn::NativeSize,
                ["app.Beta", "app.Gamma", "app.Alpha"],
                ["app.Gamma", "app.Beta", "app.Alpha"],
            ),
            (
                ClassifierColumn::RetainedNativeSize,
                ["app.Gamma", "app.Alpha", "app.Beta"],
                ["app.Alpha", "app.Gamma", "app.Beta"],
            ),
            (
                ClassifierColumn::RetainedSize,
                ["app.Beta", "app.Alpha", "app.Gamma"],
                ["app.Alpha", "app.Beta", "app.Gamma"],
            ),
        ];
        for (column, ascending, descending) in cases {
            for (direction, expected) in [
                (SortDirection::Ascending, ascending),
                (SortDirection::Descending, descending),
            ] {
                let old_callback_rows = state.visible_classifier_rows();
                let old_callback_row = old_callback_rows.get(1).unwrap().clone();
                state.sort_column = column;
                state.sort_direction = direction;
                state.refresh_presenter();
                assert_eq!(
                    visible_class_names(&mut state),
                    expected,
                    "{column:?} {direction:?}"
                );
                assert_eq!(visible_rows(&mut state), uncached_visible_rows(&state));
                assert_eq!(old_callback_rows.get(1), Some(&old_callback_row));
                let current = state.visible_classifier_rows();
                let alpha = (0..current.len())
                    .map(|index| current.get(index).unwrap())
                    .find(|row| row.class_name.as_deref() == Some("app.Alpha"))
                    .unwrap();
                assert_eq!(alpha.native_size, None);
                assert_eq!(alpha.retained_native_size, Some(5));
            }
        }
    }

    #[::core::prelude::v1::test]
    fn classifier_cache_nested_expansion_survives_filter_sort_and_direct_restore() {
        let mut state = MemoryState::new();
        state.reset_for_snapshot(
            snapshot(&["app.alpha.Widget", "app.alpha.Other", "app.beta.Widget"]),
            "nested".into(),
        );
        state.filter.arrange_by = ArrangeBy::Package;
        state.sort_column = ClassifierColumn::Name;
        state.sort_direction = SortDirection::Ascending;
        state.refresh_presenter();
        let original = state.visible_classifier_rows();
        let group_index = (0..original.len())
            .find(|index| original.get(*index).unwrap().label == "alpha")
            .unwrap();
        let callback_row = original.get(group_index).unwrap().clone();
        let callback_classes = super::super::classifier_class_names(&callback_row);
        assert_eq!(callback_classes, ["app.alpha.Other", "app.alpha.Widget"]);
        state.toggle_classifier(&callback_row);
        assert_eq!(visible_class_names(&mut state), ["app.beta.Widget"]);
        assert_eq!(visible_rows(&mut state), uncached_visible_rows(&state));

        state.filter.search = "beta".into();
        state.refresh_presenter();
        let filtered = state.visible_classifier_rows();
        assert!(filtered.len() < original.len());
        state.filter.search.clear();
        state.sort_direction = SortDirection::Descending;
        state.refresh_presenter();
        assert_eq!(visible_class_names(&mut state), ["app.beta.Widget"]);
        assert_eq!(
            state.expanded_classifiers.get(&callback_row.id),
            Some(&false)
        );
        assert_eq!(original.get(group_index), Some(&callback_row));
        assert_eq!(
            super::super::classifier_class_names(original.get(group_index).unwrap()),
            callback_classes
        );

        state.expanded_classifiers = BTreeMap::from([(callback_row.id.clone(), true)]);
        assert_eq!(
            visible_class_names(&mut state),
            ["app.beta.Widget", "app.alpha.Widget", "app.alpha.Other"]
        );
        assert_eq!(visible_rows(&mut state), uncached_visible_rows(&state));
        state.expanded_classifiers.insert("heap:App".into(), false);
        assert_eq!(visible_ids(&mut state), ["heap:App"]);
        state.expanded_classifiers.remove("heap:App");
        assert_eq!(visible_rows(&mut state), uncached_visible_rows(&state));
        assert_eq!(visible_class_names(&mut state).len(), 3);
    }

    #[::core::prelude::v1::test]
    fn classifier_cache_refreshes_same_ids_metrics_and_no_per_heap_fallback() {
        let mut state = MemoryState::new();
        let first = snapshot(&["app.Widget"]);
        state.reset_for_snapshot(first.clone(), "same.hprof".into());
        let original = state.visible_classifier_rows();
        let mut replacement = (*first).clone();
        replacement.per_heap_classes.get_mut("App").unwrap()[0].shallow_size = 999;
        state.reset_for_snapshot(Arc::new(replacement), "same.hprof".into());
        let replaced = state.visible_classifier_rows();
        assert_eq!(original.get(1).unwrap().id, replaced.get(1).unwrap().id);
        assert_eq!(original.get(1).unwrap().shallow_size, 16);
        assert_eq!(replaced.get(1).unwrap().shallow_size, 999);
        assert!(!Rc::ptr_eq(&original.source, &replaced.source));

        let mut fallback = (*first).clone();
        fallback.per_heap_classes.clear();
        state.reset_for_snapshot(Arc::new(fallback), "same.hprof".into());
        assert!(state.filter.heap.is_none());
        assert_eq!(visible_rows(&mut state), uncached_visible_rows(&state));
        assert_eq!(visible_class_names(&mut state).len(), 2);
        state.snapshot = None;
        state.refresh_presenter();
        assert!(state.visible_classifier_rows().is_empty());
        assert!(state.leak_classes.is_empty());
        assert!(state.duplicate_classes.is_empty());
        state.reset_for_snapshot(first, "same.hprof".into());
        assert_eq!(visible_class_names(&mut state), ["app.Widget"]);
    }

    #[::core::prelude::v1::test]
    fn classifier_cache_tab_state_moves_keep_independent_expansion_and_markers() {
        let mut active = MemoryState::new();
        active.reset_for_snapshot(findings_snapshot(), "first".into());
        active.filter.arrange_by = ArrangeBy::Package;
        active.refresh_presenter();
        let first = active.visible_classifier_rows();
        let root = first.get(0).unwrap().clone();
        active.toggle_classifier(&root);
        let collapsed = active.visible_classifier_rows();
        let expected = uncached_visible_rows(&active);
        active.selected_classifier_id = Some(root.id.clone());
        let inactive = std::mem::take(&mut active);
        assert!(active.visible_classifier_rows().is_empty());

        active.reset_for_snapshot(snapshot(&["app.alpha.Widget"]), "second".into());
        let second = active.visible_classifier_rows();
        assert!(!second.is_leak(second.get(0).unwrap()));
        assert_eq!(active.expanded_classifiers.len(), 0);
        let other = std::mem::replace(&mut active, inactive);
        assert_eq!(active.snapshot_label, "first");
        assert_eq!(
            active.selected_classifier_id.as_deref(),
            Some(root.id.as_str())
        );
        assert_eq!(visible_rows(&mut active), expected);
        assert!(Rc::ptr_eq(&collapsed, &active.visible_classifier_rows()));
        assert!(collapsed.is_leak(collapsed.get(0).unwrap()));
        assert_eq!(other.snapshot_label, "second");
        assert_eq!(first.get(0).unwrap(), &root);
    }

    #[::core::prelude::v1::test]
    fn classifier_cache_refreshes_hidden_mapped_findings_without_changing_row_ids() {
        let mut sample = findings_snapshot();
        let sample_mut = Arc::make_mut(&mut sample);
        for stats in sample_mut
            .classes
            .iter_mut()
            .chain(sample_mut.per_heap_classes.values_mut().flatten())
        {
            if stats.class_name == "app.alpha.Widget" {
                stats.obfuscated_class_name = Some("a.b".into());
            }
        }
        sample_mut.findings.leak_suspects.rows[0].class_name = "a.b".into();
        sample_mut.findings.activities.total = 1;
        sample_mut.findings.activities.rows = vec![aps_memory::ActivityLeak {
            class_name: "android.app.Activity".into(),
            ..aps_memory::ActivityLeak::default()
        }];
        sample_mut.findings.lifecycle.total = 1;
        sample_mut.findings.lifecycle.rows = vec![aps_memory::LifecycleLeakObject {
            object_id: 73,
            class_name: "app.beta.Other".into(),
            ..aps_memory::LifecycleLeakObject::default()
        }];
        let mut state = MemoryState::new();
        state.reset_for_snapshot(sample.clone(), "mapped".into());
        state.filter.heap = None;
        state.filter.arrange_by = ArrangeBy::Package;
        state.refresh_presenter();
        let marked = state.visible_classifier_rows();
        assert_eq!(marked.len(), 2);
        let heap = marked.get(0).unwrap();
        assert!(marked.is_leak(heap));
        assert!(!marked.is_duplicate(heap));
        assert!(
            marked
                .source
                .leak_ids
                .contains("heap:App/class:app.alpha.Widget")
        );
        assert!(
            marked
                .source
                .leak_ids
                .contains("heap:App/class:android.app.Activity")
        );
        assert!(
            marked
                .source
                .leak_ids
                .contains("heap:App/class:app.beta.Other")
        );
        assert_eq!(state.leak_object_ids, BTreeSet::from([73]));
        let mut clean = (*sample).clone();
        clean.findings.leak_suspects.rows.clear();
        clean.findings.leak_suspects.total = 0;
        clean.findings.activities.rows.clear();
        clean.findings.activities.total = 0;
        clean.findings.lifecycle.rows.clear();
        clean.findings.lifecycle.total = 0;
        clean.findings.bitmaps.rows.pop();
        clean.findings.bitmaps.total = 1;
        state.snapshot = Some(Arc::new(clean));
        state.refresh_presenter();
        let cleared = state.visible_classifier_rows();
        assert_eq!(cleared.get(0).unwrap().id, heap.id);
        assert!(!cleared.is_leak(cleared.get(0).unwrap()));
        assert!(cleared.source.duplicate_classes.is_empty());
        assert!(state.leak_object_ids.is_empty());
        assert!(marked.is_leak(heap));
        assert!(
            marked
                .source
                .duplicate_classes
                .contains("android.graphics.Bitmap")
        );
        state.snapshot = Some(sample);
        state.refresh_presenter();
        state.expanded_classifiers.insert("heap:App".into(), true);
        let restored = state.visible_classifier_rows();
        assert_eq!(state.leak_object_ids, BTreeSet::from([73]));
        for index in 0..restored.len() {
            let row = restored.get(index).unwrap();
            if row.class_name.as_deref() == Some("app.alpha.Widget") {
                assert!(restored.is_leak(row));
            }
            if row.class_name.as_deref() == Some("android.graphics.Bitmap") {
                assert!(restored.is_duplicate(row));
                assert!(!restored.is_leak(row));
            }
        }
        assert_eq!(visible_rows(&mut state), uncached_visible_rows(&state));
    }

    #[::core::prelude::v1::test]
    fn classifier_cache_phase3_ai_reads_preserve_projection_and_findings() {
        use aps_ui_foundation::Destination;
        let sample = findings_snapshot();
        let mut workspace =
            super::super::ImportWorkspace::new(Destination::Memory, Language::English);
        workspace.session_id = Some("cache-regression".into());
        workspace
            .memory_state
            .reset_for_snapshot(sample.clone(), "AI".into());
        workspace.memory_state.filter.leak_filter = LeakFilter::AllIssues;
        workspace.memory_state.filter.arrange_by = ArrangeBy::Package;
        workspace.memory_state.refresh_presenter();
        let original = workspace.memory_state.visible_classifier_rows();
        let expected = uncached_visible_rows(&workspace.memory_state);
        let summary = workspace.memory_state.class_summary.clone();
        let findings = sample.findings.clone();
        for mode in [
            MemoryViewMode::Classes,
            MemoryViewMode::LeakAnalysis,
            MemoryViewMode::Diff,
        ] {
            workspace.memory_state.view_mode = mode;
            workspace.generation += 1;
            if mode == MemoryViewMode::Diff {
                assert!(!workspace.memory_ai_available());
                assert!(workspace.memory_analysis_request().is_err());
            } else {
                assert!(workspace.memory_ai_available());
                let request = workspace.memory_analysis_request().unwrap();
                assert!(
                    request
                        .evidence
                        .iter()
                        .any(|row| row.kind == "memory-lifecycle-rule")
                );
            }
            assert!(Rc::ptr_eq(
                &original,
                &workspace.memory_state.visible_classifier_rows()
            ));
            assert_eq!(visible_rows(&mut workspace.memory_state), expected);
            assert_eq!(workspace.memory_state.class_summary, summary);
            assert_eq!(
                workspace.memory_state.snapshot.as_ref().unwrap().findings,
                findings
            );
            assert!(Arc::ptr_eq(
                workspace.memory_state.snapshot.as_ref().unwrap(),
                &sample
            ));
        }
        workspace.memory_state.loading = true;
        assert!(workspace.memory_analysis_request().is_err());
        assert!(Rc::ptr_eq(
            &original,
            &workspace.memory_state.visible_classifier_rows()
        ));
        workspace.memory_state.loading = false;
        workspace.memory_state.filter.leak_filter = LeakFilter::DuplicateBitmaps;
        workspace.memory_state.refresh_presenter();
        assert_eq!(
            visible_class_names(&mut workspace.memory_state),
            ["android.graphics.Bitmap"]
        );
        assert!(!Rc::ptr_eq(
            &original.source,
            &workspace.memory_state.visible_classifier_rows().source
        ));
    }

    #[::core::prelude::v1::test]
    fn classifier_leak_markers_include_groups_with_hidden_leaking_descendants() {
        let heap = classifier_tree();
        assert_eq!(
            classifier_leak_ids(&[heap.clone()], &BTreeSet::from(["app.Widget".into()])),
            BTreeSet::from([
                "heap:App".into(),
                "group:app".into(),
                "class:app.Widget".into()
            ]),
        );
        assert!(classifier_leak_ids(&[heap], &BTreeSet::new()).is_empty());
    }

    #[::core::prelude::v1::test]
    fn classifier_visibility_preserves_heap_defaults_and_complete_group_children() {
        let mut state = MemoryState::new();
        let heap = classifier_tree();
        state.classifier_rows = vec![heap.clone()];
        assert_eq!(visible_rows(&mut state), vec![heap.clone()]);
        assert!(state.classifier_expanded(&heap.children[0]));

        state.filter.heap = Some("App".into());
        let rows = visible_rows(&mut state);
        assert_eq!(
            rows,
            [
                heap.clone(),
                heap.children[0].clone(),
                heap.children[0].children[0].clone()
            ]
        );
        assert_eq!(rows[0].children, heap.children);
        assert_eq!(
            rows[1].children[0].class_name.as_deref(),
            Some("app.Widget")
        );
    }

    #[::core::prelude::v1::test]
    fn classifier_toggle_and_restored_overrides_update_visibility() {
        let mut state = MemoryState::new();
        let heap = classifier_tree();
        let group = heap.children[0].clone();
        state.classifier_rows = vec![heap.clone()];
        assert_eq!(visible_ids(&mut state), ["heap:App"]);

        state.toggle_classifier(&heap);
        assert_eq!(
            visible_ids(&mut state),
            ["heap:App", "group:app", "class:app.Widget"]
        );
        state.toggle_classifier(&group);
        assert_eq!(visible_ids(&mut state), ["heap:App", "group:app"]);
        state.toggle_classifier(&heap);
        assert_eq!(visible_ids(&mut state), ["heap:App"]);

        // The debug restore path writes overrides without using toggle.
        state.expanded_classifiers.insert(heap.id.clone(), true);
        assert_eq!(visible_ids(&mut state), ["heap:App", "group:app"]);
        state.expanded_classifiers.clear();
        assert_eq!(visible_ids(&mut state), ["heap:App"]);
    }

    #[::core::prelude::v1::test]
    fn classifier_refresh_tracks_filter_sort_and_snapshot_removal() {
        let mut state = MemoryState::new();
        state.reset_for_snapshot(snapshot(&["app.Beta", "app.Alpha"]), "first".into());
        assert_eq!(state.filter.heap.as_deref(), Some("App"));
        state.sort_column = ClassifierColumn::Name;
        state.sort_direction = SortDirection::Ascending;
        state.refresh_presenter();
        assert_eq!(
            visible_ids(&mut state),
            [
                "heap:App",
                "heap:App/class:app.Alpha",
                "heap:App/class:app.Beta"
            ]
        );

        state.sort_direction = SortDirection::Descending;
        state.refresh_presenter();
        assert_eq!(
            visible_ids(&mut state),
            [
                "heap:App",
                "heap:App/class:app.Beta",
                "heap:App/class:app.Alpha"
            ]
        );
        state.filter.search = "Alpha".into();
        state.refresh_presenter();
        assert_eq!(
            visible_ids(&mut state),
            ["heap:App", "heap:App/class:app.Alpha"]
        );
        state.filter = MemoryFilter::default();
        state.sort_direction = SortDirection::Ascending;
        state.refresh_presenter();
        assert_eq!(visible_ids(&mut state), ["heap:App", "heap:Zygote"]);

        state.snapshot = None;
        state.refresh_presenter();
        assert!(visible_rows(&mut state).is_empty());
        assert_eq!(state.class_summary, ClassListSummary::default());
    }

    #[::core::prelude::v1::test]
    fn classifier_reset_replaces_rows_and_clears_expansion_and_selection() {
        let mut state = MemoryState::new();
        state.reset_for_snapshot(snapshot(&["app.Old"]), "first".into());
        assert_eq!(
            visible_ids(&mut state),
            ["heap:App", "heap:App/class:app.Old"]
        );
        state.expanded_classifiers.insert("heap:App".into(), false);
        state.selected_classifier_id = Some("class:app.Old".into());
        state.selected_class_names = vec!["app.Old".into()];
        state.reset_for_snapshot(snapshot(&["app.New"]), "second".into());
        assert_eq!(
            visible_ids(&mut state),
            ["heap:App", "heap:App/class:app.New"]
        );
        assert_eq!(state.snapshot_label, "second");
        assert!(state.expanded_classifiers.is_empty());
        assert!(state.selected_classifier_id.is_none());
        assert!(state.selected_class_names.is_empty());
    }

    #[::core::prelude::v1::test]
    fn classifier_render_cache_reuses_rows_and_tree_across_expansion_only_changes() {
        let mut state = MemoryState::new();
        let heap = classifier_tree();
        state.classifier_rows = vec![heap.clone()];
        let collapsed = state.visible_classifier_rows();
        state.selected_classifier_id = Some(heap.id.clone());
        state.classifier_column_widths[0] += 20.;
        let repeated = state.visible_classifier_rows();
        assert!(Rc::ptr_eq(&collapsed, &repeated));
        assert!(!collapsed.expanded(0));

        state.toggle_classifier(&heap);
        let expanded = state.visible_classifier_rows();
        assert!(!Rc::ptr_eq(&collapsed, &expanded));
        assert!(Rc::ptr_eq(&collapsed.source, &expanded.source));
        assert!(expanded.expanded(0));
        assert!(expanded.expanded(1));
        assert!(!expanded.expanded(2));
        assert_eq!(collapsed.len(), 1);
        assert_eq!(expanded.len(), 3);
        assert_eq!(expanded.get(0), Some(&heap));
        assert_eq!(expanded.get(1), Some(&heap.children[0]));
        assert!(expanded.get(expanded.len()).is_none());

        state.expanded_classifiers.clear();
        let restored = state.visible_classifier_rows();
        assert!(!Rc::ptr_eq(&expanded, &restored));
        assert_eq!(restored.len(), 1);
        state.filter.heap = Some("App".into());
        let filtered = state.visible_classifier_rows();
        assert!(!Rc::ptr_eq(&restored, &filtered));
        assert!(Rc::ptr_eq(&restored.source, &filtered.source));
        assert_eq!(filtered.len(), 3);
    }

    #[::core::prelude::v1::test]
    fn classifier_render_cache_refreshes_findings_and_preserves_old_callback_data() {
        let mut sample = snapshot(&["app.Widget", "android.graphics.Bitmap"]);
        let findings = &mut Arc::make_mut(&mut sample).findings;
        findings.leak_suspects.total = 12;
        findings.leak_suspects.rows = vec![aps_memory::LeakSuspect {
            class_name: "app.Widget".into(),
            ..aps_memory::LeakSuspect::default()
        }];
        findings.bitmaps.total = 2;
        findings.bitmaps.rows = (1..=2)
            .map(|object_id| aps_memory::BitmapInstance {
                object_id,
                class_name: "android.graphics.Bitmap".into(),
                bitmap_id: Some(42),
                ..aps_memory::BitmapInstance::default()
            })
            .collect();
        let mut state = MemoryState::new();
        state.reset_for_snapshot(sample.clone(), "first".into());
        let original = state.visible_classifier_rows();
        assert!(original.is_leak(original.get(0).unwrap()));
        for index in 1..original.len() {
            let row = original.get(index).unwrap();
            assert_eq!(
                original.is_leak(row),
                row.class_name.as_deref() == Some("app.Widget")
            );
            assert_eq!(
                original.is_duplicate(row),
                row.class_name.as_deref() == Some("android.graphics.Bitmap")
            );
        }
        assert!(Arc::ptr_eq(state.snapshot.as_ref().unwrap(), &sample));
        assert_eq!(state.leak_suspect_count(), 12);

        let mut clean = (*sample).clone();
        clean.findings = aps_memory::FindingsSnapshot::default();
        state.snapshot = Some(Arc::new(clean));
        state.refresh_presenter();
        let refreshed = state.visible_classifier_rows();
        assert!(!Rc::ptr_eq(&original.source, &refreshed.source));
        assert!(Rc::ptr_eq(&refreshed, &state.visible_classifier_rows()));
        for index in 0..refreshed.len() {
            let row = refreshed.get(index).unwrap();
            assert!(!refreshed.is_leak(row));
            assert!(!refreshed.is_duplicate(row));
        }
        assert!(original.is_leak(original.get(0).unwrap()));
        assert_eq!(state.leak_suspect_count(), 0);
        state.reset_for_snapshot(snapshot(&["app.Replacement"]), "second".into());
        let replaced = state.visible_classifier_rows();
        assert!(!Rc::ptr_eq(&refreshed.source, &replaced.source));
        assert_eq!(original.get(0).unwrap().children.len(), 2);
    }

    #[::core::prelude::v1::test]
    fn classifier_cached_rows_match_original_flattening_for_class_and_package_filters() {
        let mut state = MemoryState::new();
        let sample = snapshot(&["app.alpha.Widget", "app.alpha.Other", "app.beta.Widget"]);
        for arrange_by in [aps_memory::ArrangeBy::Class, aps_memory::ArrangeBy::Package] {
            for heap in [None, Some("App".to_owned())] {
                for direction in [SortDirection::Ascending, SortDirection::Descending] {
                    state.reset_for_snapshot(sample.clone(), "parity".into());
                    state.filter.arrange_by = arrange_by;
                    state.filter.heap = heap.clone();
                    state.sort_column = ClassifierColumn::Name;
                    state.sort_direction = direction;
                    state.refresh_presenter();
                    for expanded in [None, Some(true), Some(false), None] {
                        state.expanded_classifiers.clear();
                        if let Some(expanded) = expanded {
                            for root in &state.classifier_rows {
                                state.expanded_classifiers.insert(root.id.clone(), expanded);
                            }
                        }
                        assert_eq!(visible_rows(&mut state), uncached_visible_rows(&state));
                    }
                }
            }
        }
    }
}

//! Typed Memory Profiler workspace state, mirroring the Compose page state.
//!
//! The UI renders from one published aps-memory snapshot plus bounded query
//! results from the isolated HPROF worker. No heap graph or raw object value
//! enters this state.
use aps_memory::{
    ClassListSummary, ClassifierColumn, ClassifierRow, DominatorsPage, InstanceDetail, InstanceRow,
    InstancesPage, MemoryFilter, MemorySnapshot, SortDirection,
};
use aps_ui_foundation::{Language, TextKey};
use gpui_kit::{component::input::InputState, *};
use std::collections::{BTreeMap, BTreeSet};
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

impl LeakTab {
    pub(super) fn label(self) -> TextKey {
        match self {
            Self::Suspects => TextKey::MemoryPageLeakSuspects,
            Self::Activities => TextKey::MemoryPageActivityFragmentLeak,
            Self::Lifecycle => TextKey::MemoryPageLifecycleLeaks,
        }
    }
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(crate) struct ClassifierDelta {
    pub class_name: String,
    pub a_count: u64,
    pub b_count: u64,
    pub a_retained: Option<u64>,
    pub b_retained: Option<u64>,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(crate) struct ComparisonReport {
    pub a_label: String,
    pub b_label: String,
    pub a_digest: String,
    pub b_digest: String,
    pub rows: Vec<ClassifierDelta>,
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
    pub warnings_open: bool,
    pub filter: MemoryFilter,
    pub sort_column: ClassifierColumn,
    pub sort_direction: SortDirection,
    pub classifier_rows: Vec<ClassifierRow>,
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
    /// Inspector-local expansion (drill-in rows keep their chevron state).
    pub inspector_expanded: BTreeSet<u64>,
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
            warnings_open: false,
            filter: MemoryFilter::default(),
            sort_column: ClassifierColumn::RetainedSize,
            sort_direction: SortDirection::Descending,
            classifier_rows: Vec::new(),
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
            inspector_expanded: BTreeSet::new(),
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
        }
    }

    pub(super) fn loaded(&self) -> bool {
        self.snapshot.is_some()
    }

    /// Recomputes presenter projections after a snapshot or filter change.
    pub(super) fn refresh_presenter(&mut self) {
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

    pub(super) fn reset_for_snapshot(&mut self, snapshot: Arc<MemorySnapshot>, label: String) {
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
        self.inspector_expanded.clear();
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

    /// Flattened visible classifier rows in display order.
    pub(super) fn visible_classifier_rows(&self) -> Vec<ClassifierRow> {
        fn push(rows: &[ClassifierRow], state: &MemoryState, out: &mut Vec<ClassifierRow>) {
            for row in rows {
                out.push(row.clone());
                if !row.children.is_empty() && state.classifier_expanded(row) {
                    push(&row.children, state, out);
                }
            }
        }
        let mut out = Vec::new();
        push(&self.classifier_rows, self, &mut out);
        out
    }

    pub(super) fn summary_metrics(&self) -> aps_memory::HeapSummary {
        let Some(snapshot) = self.snapshot.as_ref() else {
            return aps_memory::HeapSummary::default();
        };
        if self.view_mode == MemoryViewMode::Classes {
            aps_memory::HeapSummary {
                object_count: self.class_summary.total_count,
                shallow_size: self.class_summary.total_shallow_size,
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

//! Typed presenter projections shared by every Memory Profiler view.
//!
//! This is a direct port of MemoryProfilerPresenter.kt: class/package
//! grouping, heap grouping, scope and leak filters, case/regex search, the
//! Android Studio classifier columns, and the null-vs-zero summary rules.
//! All functions take plain data so the GPUI layer stays free of engine types.

use crate::model::{ClassStats, LifecycleLeakObject, MemorySnapshot};
use crate::regex_lite;
use std::cmp::Ordering;
use std::collections::{BTreeMap, BTreeSet};

/// Framework/VM/system classes by package prefix; everything else is treated
/// as app/project code, exactly like the Compose oracle.
const SYSTEM_PREFIXES: [&str; 17] = [
    "android.",
    "androidx.",
    "java.",
    "javax.",
    "kotlin.",
    "kotlinx.",
    "com.android.",
    "dalvik.",
    "sun.",
    "jdk.",
    "org.json.",
    "org.xml.",
    "org.w3c.",
    "org.apache.harmony.",
    "junit.",
    "groovy.",
    "scala.",
];

/// Preferred display order for heap rows, matching MemoryHeapNames.ordered.
const ORDERED_HEAP_NAMES: [&str; 4] = ["App", "Image", "Zygote", "Default"];

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum ClassScope {
    #[default]
    All,
    Project,
    System,
}

/// Leak-analysis filter for the class list.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum LeakFilter {
    #[default]
    None,
    AllIssues,
    ActivityFragmentLeak,
    DuplicateBitmaps,
}

/// Class-list ordering: by class name or by package.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum ArrangeBy {
    #[default]
    Class,
    Package,
}

/// Android Studio classifier columns, in their canonical order.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum ClassifierColumn {
    #[default]
    Name,
    ModuleName,
    Allocations,
    Deallocations,
    TotalCount,
    NativeSize,
    ShallowSize,
    RetainedNativeSize,
    RetainedSize,
    AllocationsSize,
    DeallocationsSize,
    ShallowSizeChange,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum SortDirection {
    #[default]
    Ascending,
    Descending,
}

/// Dashboard histogram sorting (Count / Shallow), matching the Compose card.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum DashboardSort {
    #[default]
    Count,
    Shallow,
}

/// Current class-list filter state. Defaults match an unfiltered class list.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct MemoryFilter {
    pub heap: Option<String>,
    pub scope: ClassScope,
    pub leak_filter: LeakFilter,
    pub arrange_by: ArrangeBy,
    pub search: String,
    pub match_case: bool,
    pub use_regex: bool,
}

/// One node of the classifier tree shown by the class list.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct ClassifierRow {
    pub id: String,
    pub label: String,
    pub class_name: Option<String>,
    pub total_count: u64,
    pub native_size: Option<u64>,
    pub shallow_size: u64,
    pub retained_native_size: Option<u64>,
    pub retained_size: Option<u64>,
    pub module_name: Option<String>,
    pub allocations: Option<u64>,
    pub deallocations: Option<u64>,
    pub allocations_size: Option<u64>,
    pub deallocations_size: Option<u64>,
    pub shallow_size_change: Option<u64>,
    pub depth: u32,
    pub children: Vec<ClassifierRow>,
    pub heap_names: Vec<String>,
    pub heap_name: Option<String>,
}

impl ClassifierRow {
    /// A leaf row is a concrete class; group/heap rows have no class name.
    pub fn is_leaf(&self) -> bool {
        self.class_name.is_some()
    }
}

/// Aggregate metrics for the class-list summary bar.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct ClassListSummary {
    pub class_count: u64,
    pub leak_count: u64,
    pub duplicate_bitmap_count: u64,
    pub total_count: u64,
    pub total_native_size: Option<u64>,
    pub total_retained_native_size: Option<u64>,
    pub total_shallow_size: u64,
    pub total_retained_size: Option<u64>,
}

/// Framework/VM/system classes by package prefix.
pub fn is_system_class(class_name: &str) -> bool {
    let lower = class_name.to_lowercase();
    SYSTEM_PREFIXES
        .iter()
        .any(|prefix| lower.starts_with(prefix))
}

fn short_name(class_name: &str) -> &str {
    match class_name.rfind('.') {
        Some(index) => &class_name[index + 1..],
        None => class_name,
    }
}

fn package_of(class_name: &str) -> &str {
    match class_name.rfind('.') {
        Some(index) if index > 0 => &class_name[..index],
        _ => "",
    }
}

/// Leaf label: short class name, package in parentheses, mapped alias in
/// brackets, exactly like Compose classifierClassLabel.
pub fn classifier_class_label(stats: &ClassStats) -> String {
    let package = package_of(&stats.class_name);
    let mut label = short_name(&stats.class_name).to_owned();
    if !package.is_empty() {
        label.push_str(" (");
        label.push_str(package);
        label.push(')');
    }
    if let Some(obfuscated) = &stats.obfuscated_class_name {
        label.push_str(" [");
        label.push_str(obfuscated);
        label.push(']');
    }
    label
}

/// Class warnings include both HPROF names and the mapped names shown in the
/// class table.
pub fn leak_class_names(snapshot: &MemorySnapshot) -> BTreeSet<String> {
    let mut names = BTreeSet::new();
    for suspect in &snapshot.findings.leak_suspects.rows {
        names.insert(suspect.class_name.clone());
    }
    for activity in &snapshot.findings.activities.rows {
        names.insert(activity.class_name.clone());
    }
    for object in &snapshot.findings.lifecycle.rows {
        names.insert(object.class_name.clone());
    }
    with_class_name_aliases(snapshot, names)
}

/// Original snapshot-local object IDs that should carry a leak warning in
/// instance lists. LeakCanary object IDs are not available in Rust yet.
pub fn leak_object_ids(snapshot: &MemorySnapshot) -> BTreeSet<u64> {
    snapshot
        .findings
        .lifecycle
        .rows
        .iter()
        .map(|object| object.object_id)
        .collect()
}

fn activity_fragment_leak_class_names(snapshot: &MemorySnapshot) -> BTreeSet<String> {
    let mut names = BTreeSet::new();
    for activity in &snapshot.findings.activities.rows {
        names.insert(activity.class_name.clone());
    }
    for suspect in &snapshot.findings.leak_suspects.rows {
        if suspect.activity_or_fragment_leak {
            names.insert(suspect.class_name.clone());
        }
    }
    for object in &snapshot.findings.lifecycle.rows {
        names.insert(object.class_name.clone());
    }
    with_class_name_aliases(snapshot, names)
}

fn with_class_name_aliases(snapshot: &MemorySnapshot, names: BTreeSet<String>) -> BTreeSet<String> {
    let mut result = names.clone();
    for stats in &snapshot.classes {
        let mapped = stats
            .obfuscated_class_name
            .as_ref()
            .is_some_and(|obfuscated| names.contains(obfuscated));
        if names.contains(&stats.class_name) || mapped {
            result.insert(stats.class_name.clone());
            if let Some(obfuscated) = &stats.obfuscated_class_name {
                result.insert(obfuscated.clone());
            }
        }
    }
    result
}

/// Bitmap classes sharing Perfetto storage identity, or dimensions when that
/// identity is absent. The snapshot retains the first bounded bitmap rows.
pub fn duplicate_bitmap_classes(snapshot: &MemorySnapshot) -> BTreeSet<String> {
    let mut counts: BTreeMap<(String, String), u64> = BTreeMap::new();
    for bitmap in &snapshot.findings.bitmaps.rows {
        let signature = if let Some(bitmap_id) = bitmap.bitmap_id {
            format!(
                "{}:{}",
                bitmap.bitmap_source_id.as_deref().unwrap_or("null"),
                bitmap_id
            )
        } else {
            let (Some(width), Some(height)) = (bitmap.width, bitmap.height) else {
                continue;
            };
            format!("{width}:{height}")
        };
        *counts
            .entry((bitmap.class_name.clone(), signature))
            .or_default() += 1;
    }
    counts
        .into_iter()
        .filter(|(_, count)| *count > 1)
        .map(|((class_name, _), _)| class_name)
        .collect()
}

fn base_classes<'a>(snapshot: &'a MemorySnapshot, filter: &MemoryFilter) -> &'a [ClassStats] {
    match &filter.heap {
        Some(heap) => snapshot
            .per_heap_classes
            .get(heap)
            .map_or(&[], Vec::as_slice),
        None => snapshot.classes.as_slice(),
    }
}

/// Class rows shown in the class table after every filter is applied.
pub fn displayed_classes(snapshot: &MemorySnapshot, filter: &MemoryFilter) -> Vec<ClassStats> {
    displayed(snapshot, filter, base_classes(snapshot, filter))
}

fn displayed(
    snapshot: &MemorySnapshot,
    filter: &MemoryFilter,
    base: &[ClassStats],
) -> Vec<ClassStats> {
    let native_size_by_class = native_size_by_class(snapshot, base, filter.heap.is_none());
    let leak_classes = leak_class_names(snapshot);
    let activity_classes = activity_fragment_leak_class_names(snapshot);
    let duplicate_classes = duplicate_bitmap_classes(snapshot);
    let mut classes: Vec<ClassStats> = base
        .iter()
        .filter(|stats| scope_matches(filter.scope, &stats.class_name))
        .filter(|stats| {
            leak_matches(
                filter.leak_filter,
                &stats.class_name,
                &leak_classes,
                &activity_classes,
                &duplicate_classes,
            )
        })
        .filter(|stats| search_matches(filter, &stats.class_name))
        .map(|stats| {
            let mut stats = stats.clone();
            stats.native_size = native_size_by_class.get(&stats.class_name).copied();
            stats
        })
        .collect();
    classes.sort_by(|left, right| arrange_compare(left, right, filter.arrange_by));
    classes
}

fn native_size_by_class(
    snapshot: &MemorySnapshot,
    base: &[ClassStats],
    include_bitmap_evidence: bool,
) -> BTreeMap<String, u64> {
    let mut result: BTreeMap<String, u64> = base
        .iter()
        .filter_map(|stats| {
            stats
                .native_size
                .map(|native| (stats.class_name.clone(), native))
        })
        .collect();
    if !include_bitmap_evidence {
        return result;
    }
    let mut bitmaps_by_class: BTreeMap<&str, Vec<u64>> = BTreeMap::new();
    for bitmap in &snapshot.findings.bitmaps.rows {
        bitmaps_by_class
            .entry(bitmap.class_name.as_str())
            .or_default();
        if let Some(native) = bitmap.native_size_bytes {
            bitmaps_by_class
                .entry(bitmap.class_name.as_str())
                .or_default()
                .push(native);
        }
    }
    for (class_name, known) in bitmaps_by_class {
        if result.contains_key(class_name) || known.is_empty() {
            continue;
        }
        result.insert(class_name.to_owned(), known.iter().sum());
    }
    result
}

fn scope_matches(scope: ClassScope, class_name: &str) -> bool {
    match scope {
        ClassScope::All => true,
        ClassScope::Project => !is_system_class(class_name),
        ClassScope::System => is_system_class(class_name),
    }
}

fn leak_matches(
    filter: LeakFilter,
    class_name: &str,
    leak_classes: &BTreeSet<String>,
    activity_classes: &BTreeSet<String>,
    duplicate_classes: &BTreeSet<String>,
) -> bool {
    match filter {
        LeakFilter::None => true,
        LeakFilter::AllIssues => {
            leak_classes.contains(class_name) || duplicate_classes.contains(class_name)
        }
        LeakFilter::ActivityFragmentLeak => activity_classes.contains(class_name),
        LeakFilter::DuplicateBitmaps => duplicate_classes.contains(class_name),
    }
}

fn search_matches(filter: &MemoryFilter, class_name: &str) -> bool {
    if filter.search.trim().is_empty() {
        return true;
    }
    if filter.use_regex {
        return regex_lite::contains(&filter.search, !filter.match_case, class_name);
    }
    if filter.match_case {
        class_name.contains(&filter.search)
    } else {
        class_name
            .to_lowercase()
            .contains(&filter.search.to_lowercase())
    }
}

fn arrange_compare(left: &ClassStats, right: &ClassStats, arrange_by: ArrangeBy) -> Ordering {
    match arrange_by {
        ArrangeBy::Class => left
            .class_name
            .to_lowercase()
            .cmp(&right.class_name.to_lowercase())
            .then_with(|| right.instance_count.cmp(&left.instance_count)),
        ArrangeBy::Package => package_of(&left.class_name)
            .to_lowercase()
            .cmp(&package_of(&right.class_name).to_lowercase())
            .then_with(|| {
                left.class_name
                    .to_lowercase()
                    .cmp(&right.class_name.to_lowercase())
            }),
    }
}

/// Classifier tree rows for the current filter. When per-heap histograms are
/// available the roots are heap rows in MemoryHeapNames order; otherwise the
/// tree is the selected classes only.
pub fn classifier_rows(
    snapshot: &MemorySnapshot,
    filter: &MemoryFilter,
    sort: ClassifierColumn,
    direction: SortDirection,
) -> Vec<ClassifierRow> {
    let heaps: Vec<&String> = match &filter.heap {
        Some(heap) => snapshot
            .per_heap_classes
            .get_key_value(heap)
            .map(|(k, _)| k)
            .into_iter()
            .collect(),
        None => snapshot.per_heap_classes.keys().collect(),
    };
    if heaps.is_empty() {
        let classes = displayed_classes(snapshot, filter);
        return build_classifier_rows(classes, filter.arrange_by, sort, direction);
    }
    let ordered = ordered_heaps(&heaps);
    let roots: Vec<ClassifierRow> = ordered
        .into_iter()
        .map(|heap| {
            let Some(base) = snapshot.per_heap_classes.get(heap) else {
                return ClassifierRow::default();
            };
            let scoped = MemoryFilter {
                heap: Some(heap.clone()),
                ..filter.clone()
            };
            let classes = displayed(snapshot, &scoped, base);
            let summary = summary_for(snapshot, &scoped, &classes);
            ClassifierRow {
                id: format!("heap:{heap}"),
                label: format!("{heap} heap"),
                class_name: None,
                total_count: summary.total_count,
                native_size: summary.total_native_size,
                shallow_size: summary.total_shallow_size,
                retained_native_size: summary.total_retained_native_size,
                retained_size: summary.total_retained_size,
                module_name: None,
                allocations: None,
                deallocations: None,
                allocations_size: None,
                deallocations_size: None,
                shallow_size_change: None,
                depth: 0,
                children: build_classifier_rows(classes, filter.arrange_by, sort, direction)
                    .into_iter()
                    .map(|row| row.in_heap(heap))
                    .collect(),
                heap_names: vec![heap.clone()],
                heap_name: Some(heap.clone()),
            }
        })
        .collect();
    sort_rows(roots, sort, direction)
}

fn ordered_heaps<'a>(heaps: &[&'a String]) -> Vec<&'a String> {
    let mut ordered: Vec<&String> = ORDERED_HEAP_NAMES
        .iter()
        .filter_map(|name| heaps.iter().copied().find(|heap| heap.as_str() == *name))
        .collect();
    let mut extras: Vec<&String> = heaps
        .iter()
        .copied()
        .filter(|heap| !ORDERED_HEAP_NAMES.contains(&heap.as_str()))
        .collect();
    extras.sort();
    ordered.extend(extras);
    ordered
}

impl ClassifierRow {
    fn in_heap(mut self, heap: &str) -> Self {
        self.id = format!("heap:{heap}/{}", self.id);
        self.depth += 1;
        self.heap_name = Some(heap.to_owned());
        self.heap_names = vec![heap.to_owned()];
        self.children = self
            .children
            .into_iter()
            .map(|row| row.in_heap(heap))
            .collect();
        self
    }
}

fn build_classifier_rows(
    classes: Vec<ClassStats>,
    arrange_by: ArrangeBy,
    sort: ClassifierColumn,
    direction: SortDirection,
) -> Vec<ClassifierRow> {
    match arrange_by {
        ArrangeBy::Class => {
            let rows = classes.iter().map(|stats| leaf_row(stats, 0)).collect();
            sort_rows(rows, sort, direction)
        }
        ArrangeBy::Package => {
            let mut root = TreeNode::new(String::new());
            for stats in &classes {
                insert_package(&mut root, &package_path(&stats.class_name), stats);
            }
            let rows = root
                .children
                .values()
                .map(|node| node.to_row(0, &node.label, sort, direction))
                .collect();
            sort_rows(rows, sort, direction)
        }
    }
}

fn package_path(class_name: &str) -> Vec<String> {
    let segments: Vec<&str> = class_name.split('.').collect();
    if segments.len() <= 1 {
        return vec!["<default package>".to_owned()];
    }
    segments[..segments.len() - 1]
        .iter()
        .map(|segment| (*segment).to_owned())
        .collect()
}

fn insert_package(node: &mut TreeNode, path: &[String], stats: &ClassStats) {
    let Some((first, rest)) = path.split_first() else {
        node.leaves.push(stats.clone());
        return;
    };
    let child = node
        .children
        .entry(first.clone())
        .or_insert_with(|| TreeNode::new(first.clone()));
    insert_package(child, rest, stats);
}

struct TreeNode {
    label: String,
    children: BTreeMap<String, TreeNode>,
    leaves: Vec<ClassStats>,
}

impl TreeNode {
    fn new(label: String) -> Self {
        Self {
            label,
            children: BTreeMap::new(),
            leaves: Vec::new(),
        }
    }

    fn to_row(
        &self,
        depth: u32,
        path: &str,
        sort: ClassifierColumn,
        direction: SortDirection,
    ) -> ClassifierRow {
        let children = self.children.values().map(|child| {
            child.to_row(
                depth + 1,
                &format!("{path}.{}", child.label),
                sort,
                direction,
            )
        });
        let leaves = self.leaves.iter().map(|stats| leaf_row(stats, depth + 1));
        let mut rows: Vec<ClassifierRow> = children.collect();
        rows.extend(leaves);
        let rows = sort_rows(rows, sort, direction);
        let total_count = rows.iter().map(|row| row.total_count).sum();
        let shallow_size = rows.iter().map(|row| row.shallow_size).sum();
        let heap_names = union_heap_names(&rows);
        ClassifierRow {
            id: format!("group:{path}"),
            label: self.label.clone(),
            class_name: None,
            total_count,
            native_size: sum_if_all_known(rows.iter().map(|row| row.native_size)),
            shallow_size,
            retained_native_size: sum_if_all_known(rows.iter().map(|row| row.retained_native_size)),
            retained_size: sum_if_all_known(rows.iter().map(|row| row.retained_size)),
            module_name: None,
            allocations: sum_if_all_known(rows.iter().map(|row| row.allocations)),
            deallocations: sum_if_all_known(rows.iter().map(|row| row.deallocations)),
            allocations_size: sum_if_all_known(rows.iter().map(|row| row.allocations_size)),
            deallocations_size: sum_if_all_known(rows.iter().map(|row| row.deallocations_size)),
            shallow_size_change: sum_if_all_known(rows.iter().map(|row| row.shallow_size_change)),
            depth,
            children: rows,
            heap_names,
            heap_name: None,
        }
    }
}

fn union_heap_names(rows: &[ClassifierRow]) -> Vec<String> {
    let mut names = Vec::new();
    for row in rows {
        for name in &row.heap_names {
            if !names.contains(name) {
                names.push(name.clone());
            }
        }
    }
    names
}

fn sum_if_all_known(values: impl Iterator<Item = Option<u64>>) -> Option<u64> {
    let mut total = 0u64;
    for value in values {
        total = total.checked_add(value?)?;
    }
    Some(total)
}

fn leaf_row(stats: &ClassStats, depth: u32) -> ClassifierRow {
    ClassifierRow {
        id: format!("class:{}", stats.class_name),
        label: classifier_class_label(stats),
        class_name: Some(stats.class_name.clone()),
        total_count: stats.total_count.unwrap_or(stats.instance_count),
        native_size: stats.native_size,
        shallow_size: stats.shallow_size,
        retained_native_size: stats.reachable_native_size,
        retained_size: stats.retained_size,
        module_name: stats.module_name.clone(),
        allocations: None,
        deallocations: None,
        allocations_size: None,
        deallocations_size: None,
        shallow_size_change: None,
        depth,
        children: Vec::new(),
        heap_names: stats.heap_names.clone(),
        heap_name: None,
    }
}

/// Compare rows with null-last semantics; retained metrics order unavailable
/// values as zero without changing their displayed value.
fn compare_optional<T: Ord>(
    left: Option<T>,
    right: Option<T>,
    direction: SortDirection,
) -> Ordering {
    match (left, right) {
        (None, None) => Ordering::Equal,
        (None, Some(_)) => Ordering::Greater,
        (Some(_), None) => Ordering::Less,
        (Some(left), Some(right)) => match direction {
            SortDirection::Ascending => left.cmp(&right),
            SortDirection::Descending => right.cmp(&left),
        },
    }
}

fn row_compare(
    left: &ClassifierRow,
    right: &ClassifierRow,
    column: ClassifierColumn,
    direction: SortDirection,
) -> Ordering {
    match column {
        ClassifierColumn::Name => {
            let left_name = left
                .class_name
                .as_deref()
                .map_or(left.label.as_str(), short_name);
            let right_name = right
                .class_name
                .as_deref()
                .map_or(right.label.as_str(), short_name);
            compare_optional(Some(left_name), Some(right_name), direction)
        }
        ClassifierColumn::ModuleName => compare_optional(
            left.module_name.as_deref().map(str::to_lowercase),
            right.module_name.as_deref().map(str::to_lowercase),
            direction,
        ),
        ClassifierColumn::Allocations => {
            compare_optional(left.allocations, right.allocations, direction)
        }
        ClassifierColumn::Deallocations => {
            compare_optional(left.deallocations, right.deallocations, direction)
        }
        ClassifierColumn::TotalCount => {
            compare_optional(Some(left.total_count), Some(right.total_count), direction)
        }
        ClassifierColumn::NativeSize => {
            compare_optional(left.native_size, right.native_size, direction)
        }
        ClassifierColumn::ShallowSize => {
            compare_optional(Some(left.shallow_size), Some(right.shallow_size), direction)
        }
        ClassifierColumn::RetainedNativeSize => compare_optional(
            Some(left.retained_native_size.unwrap_or(0)),
            Some(right.retained_native_size.unwrap_or(0)),
            direction,
        ),
        ClassifierColumn::RetainedSize => compare_optional(
            Some(left.retained_size.unwrap_or(0)),
            Some(right.retained_size.unwrap_or(0)),
            direction,
        ),
        ClassifierColumn::AllocationsSize => {
            compare_optional(left.allocations_size, right.allocations_size, direction)
        }
        ClassifierColumn::DeallocationsSize => {
            compare_optional(left.deallocations_size, right.deallocations_size, direction)
        }
        ClassifierColumn::ShallowSizeChange => compare_optional(
            left.shallow_size_change,
            right.shallow_size_change,
            direction,
        ),
    }
}

fn sort_rows(
    mut rows: Vec<ClassifierRow>,
    column: ClassifierColumn,
    direction: SortDirection,
) -> Vec<ClassifierRow> {
    rows.sort_by(|left, right| row_compare(left, right, column, direction));
    rows
}

/// Aggregate summary for the current filter. Retained totals are unavailable
/// while a class filter is active or any displayed class lacks retained
/// evidence; native totals require evidence for every displayed class.
pub fn class_list_summary(snapshot: &MemorySnapshot, filter: &MemoryFilter) -> ClassListSummary {
    let displayed = displayed_classes(snapshot, filter);
    summary_for(snapshot, filter, &displayed)
}

fn summary_for(
    snapshot: &MemorySnapshot,
    filter: &MemoryFilter,
    displayed: &[ClassStats],
) -> ClassListSummary {
    let mut displayed_names: BTreeSet<&str> = BTreeSet::new();
    for stats in displayed {
        displayed_names.insert(stats.class_name.as_str());
        if let Some(obfuscated) = &stats.obfuscated_class_name {
            displayed_names.insert(obfuscated.as_str());
        }
    }
    let known_native_sizes: Vec<u64> = displayed
        .iter()
        .filter_map(|stats| stats.native_size)
        .collect();
    let known_reachable_shallow: Vec<u64> = displayed
        .iter()
        .filter_map(|stats| stats.reachable_shallow_size)
        .collect();
    let known_reachable_native: Vec<u64> = displayed
        .iter()
        .filter_map(|stats| stats.reachable_native_size)
        .collect();
    let class_filter_active = filter.scope != ClassScope::All
        || filter.leak_filter != LeakFilter::None
        || !filter.search.is_empty();
    let leak_count = snapshot
        .findings
        .lifecycle
        .rows
        .iter()
        .filter(|candidate| lifecycle_matches(candidate, filter, &displayed_names))
        .count() as u64;
    let duplicate_classes = duplicate_bitmap_classes(snapshot);
    let duplicate_bitmap_count = displayed
        .iter()
        .filter(|stats| duplicate_classes.contains(&stats.class_name))
        .count() as u64;
    ClassListSummary {
        class_count: displayed.len() as u64,
        leak_count,
        duplicate_bitmap_count,
        total_count: displayed.iter().map(|stats| stats.instance_count).sum(),
        total_native_size: sum_all(&known_native_sizes, displayed.len()),
        total_retained_native_size: if class_filter_active {
            None
        } else {
            sum_all(&known_reachable_native, displayed.len())
        },
        total_shallow_size: displayed.iter().map(|stats| stats.shallow_size).sum(),
        total_retained_size: if class_filter_active {
            None
        } else {
            sum_all(&known_reachable_shallow, displayed.len())
        },
    }
}

fn lifecycle_matches(
    candidate: &LifecycleLeakObject,
    filter: &MemoryFilter,
    displayed_names: &BTreeSet<&str>,
) -> bool {
    let heap_matches =
        filter.heap.is_none() || filter.heap.as_deref() == candidate.heap_name.as_deref();
    heap_matches && displayed_names.contains(candidate.class_name.as_str())
}

fn sum_all(values: &[u64], displayed: usize) -> Option<u64> {
    if values.is_empty() || values.len() != displayed {
        return None;
    }
    let mut total = 0u64;
    for value in values {
        total = total.checked_add(*value)?;
    }
    Some(total)
}

/// Dashboard histogram ordering: metric descending, class name ascending.
pub fn sort_histogram_classes(classes: &[ClassStats], by: DashboardSort) -> Vec<ClassStats> {
    let mut sorted = classes.to_vec();
    match by {
        DashboardSort::Count => sorted.sort_by(|left, right| {
            right
                .instance_count
                .cmp(&left.instance_count)
                .then_with(|| left.class_name.cmp(&right.class_name))
        }),
        DashboardSort::Shallow => sorted.sort_by(|left, right| {
            right
                .shallow_size
                .cmp(&left.shallow_size)
                .then_with(|| left.class_name.cmp(&right.class_name))
        }),
    }
    sorted
}

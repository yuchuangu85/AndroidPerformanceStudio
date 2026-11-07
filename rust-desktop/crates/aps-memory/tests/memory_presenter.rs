//! Presenter parity tests ported from MemoryProfilerPresenterTest.kt.

use aps_memory::{
    ArrangeBy, BitmapInstance, ClassListSummary, ClassScope, ClassStats, ClassifierColumn,
    DashboardSort, FindingsPage, FindingsSnapshot, HeapSummary, LeakFilter, LeakSuspect,
    LifecycleLeakObject, MemoryFilter, MemorySnapshot, SnapshotMetadata, SortDirection,
    class_list_summary, classifier_rows, displayed_classes, duplicate_bitmap_classes,
    leak_class_names, leak_object_ids, sort_histogram_classes,
};
use std::collections::BTreeMap;

fn class(name: &str, count: u64, shallow: u64) -> ClassStats {
    ClassStats {
        class_name: name.to_owned(),
        instance_count: count,
        shallow_size: shallow,
        ..ClassStats::default()
    }
}

fn snapshot_data(
    classes: Vec<ClassStats>,
    per_heap_classes: BTreeMap<String, Vec<ClassStats>>,
    findings: FindingsSnapshot,
) -> MemorySnapshot {
    MemorySnapshot {
        source_digest: "digest".into(),
        metadata: SnapshotMetadata::default(),
        summary: HeapSummary::default(),
        classes,
        per_heap_classes,
        findings,
    }
}

fn bitmap(
    class_name: &str,
    width: Option<u32>,
    height: Option<u32>,
    bitmap_id: Option<u64>,
    source_id: Option<&str>,
) -> BitmapInstance {
    BitmapInstance {
        object_id: 1,
        class_name: class_name.to_owned(),
        width,
        height,
        estimated_pixel_bytes: None,
        retained_size: None,
        bitmap_id,
        bitmap_source_id: source_id.map(str::to_owned),
        native_size_bytes: None,
    }
}

fn sample_classes() -> Vec<ClassStats> {
    vec![
        class("java.lang.String", 12, 2_048),
        class("kotlin.ByteArray", 12, 4_096),
        class("android.graphics.Bitmap", 3, 4_096),
        class("com.example.Tiny", 1, 8),
    ]
}

fn names(classes: &[ClassStats]) -> Vec<&str> {
    classes
        .iter()
        .map(|stats| stats.class_name.as_str())
        .collect()
}

#[test]
fn leak_aliases_cover_mapped_class_names() {
    let mut mapped = class("com.example.MainActivity", 1, 8);
    mapped.obfuscated_class_name = Some("a.b".into());
    let snapshot = snapshot_data(
        vec![mapped],
        BTreeMap::new(),
        FindingsSnapshot {
            leak_suspects: FindingsPage {
                total: 1,
                rows: vec![LeakSuspect {
                    class_name: "a.b".into(),
                    reason: "destroyed Activity remains reachable".into(),
                    instance_count: 1,
                    retained_size: Some(100),
                    activity_or_fragment_leak: true,
                }],
            },
            ..FindingsSnapshot::default()
        },
    );
    let names = leak_class_names(&snapshot);
    assert!(names.contains("com.example.MainActivity"));
    assert!(names.contains("a.b"));
}

#[test]
fn leak_object_ids_are_snapshot_local_lifecycle_ids() {
    let snapshot = snapshot_data(
        Vec::new(),
        BTreeMap::new(),
        FindingsSnapshot {
            lifecycle: FindingsPage {
                total: 2,
                rows: vec![
                    LifecycleLeakObject {
                        object_id: 7,
                        class_name: "sample.Activity".into(),
                        heap_name: Some("App".into()),
                    },
                    LifecycleLeakObject {
                        object_id: 9,
                        class_name: "sample.Other".into(),
                        heap_name: None,
                    },
                ],
            },
            ..FindingsSnapshot::default()
        },
    );
    assert_eq!(
        leak_object_ids(&snapshot),
        BTreeMap::from([(7, ()), (9, ())])
            .into_keys()
            .collect::<std::collections::BTreeSet<_>>()
    );
}

#[test]
fn duplicate_bitmap_classes_use_storage_identity_then_dimensions() {
    let snapshot = snapshot_data(
        Vec::new(),
        BTreeMap::new(),
        FindingsSnapshot {
            bitmaps: FindingsPage {
                total: 7,
                rows: vec![
                    bitmap("android.graphics.Bitmap", None, None, Some(1), Some("2")),
                    bitmap("android.graphics.Bitmap", None, None, Some(1), Some("2")),
                    bitmap("com.example.Png", Some(100), Some(100), None, None),
                    bitmap("com.example.Png", Some(100), Some(100), None, None),
                    bitmap("solo.Bmp", Some(3), Some(4), None, None),
                    bitmap("null.Source", None, None, Some(5), None),
                    bitmap("null.Source", None, None, Some(5), None),
                ],
            },
            ..FindingsSnapshot::default()
        },
    );
    let duplicates = duplicate_bitmap_classes(&snapshot);
    assert!(duplicates.contains("android.graphics.Bitmap"));
    assert!(duplicates.contains("com.example.Png"));
    assert!(duplicates.contains("null.Source"));
    assert!(!duplicates.contains("solo.Bmp"));
    assert_eq!(duplicates.len(), 3);
}

#[test]
fn scope_filter_keeps_only_project_classes() {
    let snapshot = snapshot_data(
        sample_classes(),
        BTreeMap::new(),
        FindingsSnapshot::default(),
    );
    let filter = MemoryFilter {
        scope: ClassScope::Project,
        ..MemoryFilter::default()
    };
    assert_eq!(
        names(&displayed_classes(&snapshot, &filter)),
        vec!["com.example.Tiny"]
    );
    let filter = MemoryFilter {
        scope: ClassScope::System,
        ..MemoryFilter::default()
    };
    assert_eq!(
        names(&displayed_classes(&snapshot, &filter)),
        vec![
            "android.graphics.Bitmap",
            "java.lang.String",
            "kotlin.ByteArray"
        ]
    );
}

#[test]
fn leak_filter_keeps_activity_fragment_candidates() {
    let snapshot = snapshot_data(
        sample_classes(),
        BTreeMap::new(),
        FindingsSnapshot {
            leak_suspects: FindingsPage {
                total: 1,
                rows: vec![LeakSuspect {
                    class_name: "com.example.Tiny".into(),
                    reason: String::new(),
                    instance_count: 1,
                    retained_size: None,
                    activity_or_fragment_leak: true,
                }],
            },
            ..FindingsSnapshot::default()
        },
    );
    let filter = MemoryFilter {
        leak_filter: LeakFilter::ActivityFragmentLeak,
        ..MemoryFilter::default()
    };
    assert_eq!(
        names(&displayed_classes(&snapshot, &filter)),
        vec!["com.example.Tiny"]
    );
}

#[test]
fn lifecycle_candidates_stay_visible_under_issue_filters() {
    let snapshot = snapshot_data(
        vec![class("sample.Leak", 2, 16), class("sample.Healthy", 1, 8)],
        BTreeMap::new(),
        FindingsSnapshot {
            lifecycle: FindingsPage {
                total: 2,
                rows: vec![
                    LifecycleLeakObject {
                        object_id: 1,
                        class_name: "sample.Leak".into(),
                        heap_name: Some("App".into()),
                    },
                    LifecycleLeakObject {
                        object_id: 2,
                        class_name: "sample.Leak".into(),
                        heap_name: Some("App".into()),
                    },
                ],
            },
            ..FindingsSnapshot::default()
        },
    );
    for leak_filter in [LeakFilter::ActivityFragmentLeak, LeakFilter::AllIssues] {
        let filter = MemoryFilter {
            leak_filter,
            ..MemoryFilter::default()
        };
        assert_eq!(
            names(&displayed_classes(&snapshot, &filter)),
            vec!["sample.Leak"]
        );
        assert_eq!(class_list_summary(&snapshot, &filter).leak_count, 2);
    }
}

#[test]
fn duplicate_bitmap_filter_keeps_repeated_bitmaps() {
    let snapshot = snapshot_data(
        vec![class("android.graphics.Bitmap", 2, 16)],
        BTreeMap::new(),
        FindingsSnapshot {
            bitmaps: FindingsPage {
                total: 2,
                rows: vec![
                    bitmap("android.graphics.Bitmap", Some(10), Some(10), None, None),
                    bitmap("android.graphics.Bitmap", Some(10), Some(10), None, None),
                ],
            },
            ..FindingsSnapshot::default()
        },
    );
    let filter = MemoryFilter {
        leak_filter: LeakFilter::DuplicateBitmaps,
        ..MemoryFilter::default()
    };
    assert_eq!(
        names(&displayed_classes(&snapshot, &filter)),
        vec!["android.graphics.Bitmap"]
    );
}

#[test]
fn search_filters_case_and_regex() {
    let snapshot = snapshot_data(
        sample_classes(),
        BTreeMap::new(),
        FindingsSnapshot::default(),
    );
    let filter = MemoryFilter {
        search: "STRING".into(),
        ..MemoryFilter::default()
    };
    assert_eq!(
        names(&displayed_classes(&snapshot, &filter)),
        vec!["java.lang.String"]
    );
    let filter = MemoryFilter {
        search: "string".into(),
        match_case: true,
        ..MemoryFilter::default()
    };
    assert!(displayed_classes(&snapshot, &filter).is_empty());
    let filter = MemoryFilter {
        search: r"com\..*".into(),
        use_regex: true,
        ..MemoryFilter::default()
    };
    assert_eq!(
        names(&displayed_classes(&snapshot, &filter)),
        vec!["com.example.Tiny"]
    );
    let filter = MemoryFilter {
        search: "(".into(),
        use_regex: true,
        ..MemoryFilter::default()
    };
    assert!(displayed_classes(&snapshot, &filter).is_empty());
    let filter = MemoryFilter {
        search: "(Activity|Fragment)$".into(),
        use_regex: true,
        ..MemoryFilter::default()
    };
    assert!(displayed_classes(&snapshot, &filter).is_empty());
}

#[test]
fn regex_supports_anchors_classes_and_alternation() {
    let snapshot = snapshot_data(
        vec![
            class("java.lang.String", 1, 1),
            class("sample.MainActivity", 1, 1),
            class("sample.FragmentThing", 1, 1),
        ],
        BTreeMap::new(),
        FindingsSnapshot::default(),
    );
    for (pattern, expected) in [
        ("^java", "java.lang.String"),
        ("[A-Z][a-z]+Activity$", "sample.MainActivity"),
        ("(Activity|Fragment)Thing$", "sample.FragmentThing"),
        ("lang\\.Str", "java.lang.String"),
    ] {
        let filter = MemoryFilter {
            search: pattern.into(),
            use_regex: true,
            ..MemoryFilter::default()
        };
        assert_eq!(
            names(&displayed_classes(&snapshot, &filter)),
            vec![expected],
            "pattern {pattern}"
        );
    }
}

#[test]
fn arrange_by_class_and_package_sort_alphabetically() {
    let snapshot = snapshot_data(
        sample_classes(),
        BTreeMap::new(),
        FindingsSnapshot::default(),
    );
    let filter = MemoryFilter {
        arrange_by: ArrangeBy::Class,
        ..MemoryFilter::default()
    };
    assert_eq!(
        names(&displayed_classes(&snapshot, &filter)),
        vec![
            "android.graphics.Bitmap",
            "com.example.Tiny",
            "java.lang.String",
            "kotlin.ByteArray"
        ]
    );
    let filter = MemoryFilter {
        arrange_by: ArrangeBy::Package,
        ..MemoryFilter::default()
    };
    assert_eq!(
        names(&displayed_classes(&snapshot, &filter)),
        vec![
            "android.graphics.Bitmap",
            "com.example.Tiny",
            "java.lang.String",
            "kotlin.ByteArray"
        ]
    );
}

#[test]
fn class_list_summary_tracks_native_and_retained_evidence() {
    let mut native_one = class("android.graphics.Bitmap", 1, 16);
    native_one.native_size = Some(40_000);
    let snapshot = snapshot_data(
        vec![native_one],
        BTreeMap::new(),
        FindingsSnapshot::default(),
    );
    let summary = class_list_summary(&snapshot, &MemoryFilter::default());
    assert_eq!(
        summary,
        ClassListSummary {
            class_count: 1,
            leak_count: 0,
            duplicate_bitmap_count: 0,
            total_count: 1,
            total_native_size: Some(40_000),
            total_retained_native_size: None,
            total_shallow_size: 16,
            total_retained_size: None,
        }
    );

    let snapshot = snapshot_data(
        sample_classes(),
        BTreeMap::new(),
        FindingsSnapshot::default(),
    );
    let summary = class_list_summary(&snapshot, &MemoryFilter::default());
    assert_eq!(summary.class_count, 4);
    assert_eq!(summary.total_count, 28);
    assert_eq!(summary.total_native_size, None);
    assert_eq!(summary.total_shallow_size, 2_048 + 4_096 + 4_096 + 8);
    assert_eq!(summary.total_retained_size, None);
}

#[test]
fn class_list_summary_keeps_zero_native_size_as_evidence() {
    let mut zero = class("com.example.Zero", 1, 8);
    zero.native_size = Some(0);
    let snapshot = snapshot_data(vec![zero], BTreeMap::new(), FindingsSnapshot::default());
    let summary = class_list_summary(&snapshot, &MemoryFilter::default());
    assert_eq!(summary.total_native_size, Some(0));
}

#[test]
fn native_size_is_enriched_from_bitmap_evidence() {
    let snapshot = snapshot_data(
        vec![class("android.graphics.Bitmap", 1, 16)],
        BTreeMap::new(),
        FindingsSnapshot {
            bitmaps: FindingsPage {
                total: 1,
                rows: vec![BitmapInstance {
                    native_size_bytes: Some(40_000),
                    ..bitmap("android.graphics.Bitmap", Some(100), Some(100), None, None)
                }],
            },
            ..FindingsSnapshot::default()
        },
    );
    let displayed = displayed_classes(&snapshot, &MemoryFilter::default());
    assert_eq!(displayed[0].native_size, Some(40_000));
    // Heap-scoped rows never re-use all-heap bitmap evidence.
    let per_heap = BTreeMap::from([(
        "Zygote".to_owned(),
        vec![class("android.graphics.Bitmap", 1, 16)],
    )]);
    let snapshot = snapshot_data(Vec::new(), per_heap, snapshot.findings.clone());
    let filter = MemoryFilter {
        heap: Some("Zygote".into()),
        ..MemoryFilter::default()
    };
    assert_eq!(displayed_classes(&snapshot, &filter)[0].native_size, None);
}

#[test]
fn filtered_summary_does_not_claim_retained_evidence() {
    let mut first = class("com.example.Item", 2, 80);
    first.retained_size = Some(100);
    first.reachable_shallow_size = Some(80);
    first.reachable_native_size = Some(12);
    let mut second = class("com.example.Other", 1, 20);
    second.retained_size = Some(25);
    second.reachable_shallow_size = Some(20);
    second.reachable_native_size = Some(3);
    let snapshot = snapshot_data(
        vec![first, second],
        BTreeMap::new(),
        FindingsSnapshot::default(),
    );

    let summary = class_list_summary(&snapshot, &MemoryFilter::default());
    assert_eq!(summary.total_retained_size, Some(100));
    assert_eq!(summary.total_retained_native_size, Some(15));

    let filtered = MemoryFilter {
        search: "Item".into(),
        ..MemoryFilter::default()
    };
    let summary = class_list_summary(&snapshot, &filtered);
    assert_eq!(summary.total_retained_size, None);
    assert_eq!(summary.total_retained_native_size, None);

    let scoped = MemoryFilter {
        scope: ClassScope::Project,
        ..MemoryFilter::default()
    };
    assert_eq!(
        class_list_summary(&snapshot, &scoped).total_retained_size,
        None
    );

    let unknown_snapshot = snapshot_data(
        vec![ClassStats::default()],
        BTreeMap::new(),
        FindingsSnapshot::default(),
    );
    assert_eq!(
        class_list_summary(&unknown_snapshot, &MemoryFilter::default()).total_retained_size,
        None
    );
}

#[test]
fn lifecycle_leak_count_follows_heap_and_displayed_classes() {
    let findings = FindingsSnapshot {
        lifecycle: FindingsPage {
            total: 3,
            rows: vec![
                LifecycleLeakObject {
                    object_id: 1,
                    class_name: "sample.Leak".into(),
                    heap_name: Some("App".into()),
                },
                LifecycleLeakObject {
                    object_id: 2,
                    class_name: "sample.Leak".into(),
                    heap_name: Some("App".into()),
                },
                LifecycleLeakObject {
                    object_id: 3,
                    class_name: "sample.Leak".into(),
                    heap_name: Some("Zygote".into()),
                },
            ],
        },
        ..FindingsSnapshot::default()
    };
    let per_heap = BTreeMap::from([("App".to_owned(), vec![class("sample.Leak", 3, 24)])]);
    let snapshot = snapshot_data(vec![class("sample.Leak", 3, 24)], per_heap, findings);
    assert_eq!(
        class_list_summary(&snapshot, &MemoryFilter::default()).leak_count,
        3
    );
    let app = MemoryFilter {
        heap: Some("App".into()),
        ..MemoryFilter::default()
    };
    assert_eq!(class_list_summary(&snapshot, &app).leak_count, 2);
    let missing = MemoryFilter {
        search: "Other".into(),
        ..MemoryFilter::default()
    };
    assert_eq!(class_list_summary(&snapshot, &missing).leak_count, 0);
}

#[test]
fn package_grouping_builds_hierarchy_with_class_leaves() {
    let snapshot = snapshot_data(
        vec![
            class("com.example.alpha.First", 2, 20),
            class("com.example.beta.Second", 3, 30),
        ],
        BTreeMap::new(),
        FindingsSnapshot::default(),
    );
    let rows = classifier_rows(
        &snapshot,
        &MemoryFilter {
            arrange_by: ArrangeBy::Package,
            ..MemoryFilter::default()
        },
        ClassifierColumn::Name,
        SortDirection::Ascending,
    );
    let com = rows.single();
    assert_eq!(com.id, "group:com");
    assert_eq!(com.label, "com");
    assert_eq!(com.depth, 0);
    assert_eq!(com.total_count, 5);
    assert_eq!(com.shallow_size, 50);
    let example = com.children.single();
    assert_eq!(example.label, "example");
    assert_eq!(
        example
            .children
            .iter()
            .map(|row| row.label.as_str())
            .collect::<Vec<_>>(),
        vec!["alpha", "beta"]
    );
    let alpha = &example.children[0];
    assert_eq!(
        alpha.children.single().class_name.as_deref(),
        Some("com.example.alpha.First")
    );
}

#[test]
fn classifier_class_label_uses_short_name_package_and_alias() {
    let rows = classifier_rows(
        &snapshot_data(
            vec![ClassStats {
                class_name: "com.example.Tiny".into(),
                instance_count: 1,
                shallow_size: 8,
                obfuscated_class_name: Some("a.b".into()),
                ..ClassStats::default()
            }],
            BTreeMap::new(),
            FindingsSnapshot::default(),
        ),
        &MemoryFilter::default(),
        ClassifierColumn::Name,
        SortDirection::Ascending,
    );
    assert_eq!(rows.single().label, "Tiny (com.example) [a.b]");
}

#[test]
fn heap_grouping_builds_ordered_edges_with_in_heap_ids() {
    let per_heap = BTreeMap::from([
        ("Zygote".to_owned(), vec![class("zygote.Shared", 1, 10)]),
        (
            "App".to_owned(),
            vec![class("app.Main", 2, 20), class("app.Other", 1, 5)],
        ),
        ("Custom".to_owned(), vec![class("custom.Only", 1, 1)]),
    ]);
    let snapshot = snapshot_data(Vec::new(), per_heap, FindingsSnapshot::default());
    let rows = classifier_rows(
        &snapshot,
        &MemoryFilter::default(),
        ClassifierColumn::Name,
        SortDirection::Ascending,
    );
    assert_eq!(
        rows.iter()
            .map(|row| row.label.as_str())
            .collect::<Vec<_>>(),
        vec!["App heap", "Custom heap", "Zygote heap"]
    );
    let app = &rows[0];
    assert_eq!(app.id, "heap:App");
    assert_eq!(app.heap_name.as_deref(), Some("App"));
    assert_eq!(app.depth, 0);
    assert_eq!(app.total_count, 3);
    assert_eq!(app.shallow_size, 25);
    assert_eq!(app.heap_names, vec!["App"]);
    assert_eq!(app.children[0].depth, 1);
    assert_eq!(app.children[0].heap_name.as_deref(), Some("App"));
    assert!(app.children[0].id.starts_with("heap:App/"));
    assert!(app.children[0].is_leaf());

    let scoped = MemoryFilter {
        heap: Some("Zygote".into()),
        ..MemoryFilter::default()
    };
    let rows = classifier_rows(
        &snapshot,
        &scoped,
        ClassifierColumn::Name,
        SortDirection::Ascending,
    );
    assert_eq!(rows.len(), 1);
    assert_eq!(rows[0].label, "Zygote heap");
    assert_eq!(
        rows[0].children.single().class_name.as_deref(),
        Some("zygote.Shared")
    );
}

#[test]
fn classifier_rows_without_per_heap_data_are_flat() {
    let snapshot = snapshot_data(
        vec![class("b.Second", 1, 2), class("a.First", 2, 3)],
        BTreeMap::new(),
        FindingsSnapshot::default(),
    );
    let rows = classifier_rows(
        &snapshot,
        &MemoryFilter::default(),
        ClassifierColumn::TotalCount,
        SortDirection::Descending,
    );
    assert_eq!(
        rows.iter()
            .map(|row| row.class_name.as_deref().unwrap())
            .collect::<Vec<_>>(),
        vec!["a.First", "b.Second"]
    );
}

#[test]
fn retained_columns_order_unavailable_values_as_zero() {
    let mut known = class("a.Known", 1, 1);
    known.retained_size = Some(5);
    let unknown = class("b.Unknown", 1, 1);
    let snapshot = snapshot_data(
        vec![known, unknown],
        BTreeMap::new(),
        FindingsSnapshot::default(),
    );

    let rows = classifier_rows(
        &snapshot,
        &MemoryFilter::default(),
        ClassifierColumn::RetainedSize,
        SortDirection::Descending,
    );
    assert_eq!(
        rows.iter()
            .map(|row| row.class_name.as_deref().unwrap())
            .collect::<Vec<_>>(),
        vec!["a.Known", "b.Unknown"]
    );

    let rows = classifier_rows(
        &snapshot,
        &MemoryFilter::default(),
        ClassifierColumn::RetainedSize,
        SortDirection::Ascending,
    );
    assert_eq!(
        rows.iter()
            .map(|row| row.class_name.as_deref().unwrap())
            .collect::<Vec<_>>(),
        vec!["b.Unknown", "a.Known"]
    );
    assert_eq!(rows[0].retained_size, None);
}

#[test]
fn sort_histogram_classes_sorts_count_and_shallow() {
    let classes = sample_classes();
    assert_eq!(
        names(&sort_histogram_classes(&classes, DashboardSort::Count)),
        vec![
            "java.lang.String",
            "kotlin.ByteArray",
            "android.graphics.Bitmap",
            "com.example.Tiny"
        ]
    );
    assert_eq!(
        names(&sort_histogram_classes(&classes, DashboardSort::Shallow)),
        vec![
            "android.graphics.Bitmap",
            "kotlin.ByteArray",
            "java.lang.String",
            "com.example.Tiny"
        ]
    );
}

#[test]
fn classifier_column_enum_keeps_android_studio_order() {
    let columns = [
        ClassifierColumn::Name,
        ClassifierColumn::ModuleName,
        ClassifierColumn::Allocations,
        ClassifierColumn::Deallocations,
        ClassifierColumn::TotalCount,
        ClassifierColumn::NativeSize,
        ClassifierColumn::ShallowSize,
        ClassifierColumn::RetainedNativeSize,
        ClassifierColumn::RetainedSize,
        ClassifierColumn::AllocationsSize,
        ClassifierColumn::DeallocationsSize,
        ClassifierColumn::ShallowSizeChange,
    ];
    assert_eq!(columns.len(), 12);
}

trait Single<T> {
    fn single(self) -> T;
}

impl<T: Clone> Single<T> for &[T] {
    fn single(self) -> T {
        assert_eq!(self.len(), 1, "expected exactly one item");
        self[0].clone()
    }
}

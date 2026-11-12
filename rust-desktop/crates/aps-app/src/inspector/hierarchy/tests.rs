use super::{HierarchyCache, HierarchySnapshot, PreviewCache};
use crate::inspector::{
    MAX_CANVAS_NODES, hierarchy_content_width, hierarchy_label, hierarchy_numbers,
};
use aps_contracts::SourceDigest;
use aps_layout_inspector::{
    Bounds, Capabilities, ComposeEvidence, ComposeNodeEvidence, DisplayInfo, FrameIdentity,
    InspectorArchive, LayoutNode, LayoutWindow, NodeKind,
};
use aps_ui_foundation::LayoutPreferences;
use std::{collections::BTreeSet, hint::black_box, sync::Arc, time::Instant};

const BOUNDS: Bounds = Bounds {
    left: 0,
    top: 0,
    right: 1080,
    bottom: 2400,
};

fn node(id: &str, depth: usize, parent_index: Option<usize>) -> LayoutNode {
    LayoutNode {
        id: id.into(),
        kind: NodeKind::View,
        class_name: "example.Widget".into(),
        parent_index,
        depth,
        bounds: BOUNDS,
        visible: true,
        alpha: 1.0,
        resource_name: None,
        text: None,
        semantics_role: None,
        attributes: Default::default(),
    }
}

fn archive(windows: Vec<Vec<LayoutNode>>) -> Arc<InspectorArchive> {
    Arc::new(InspectorArchive {
        archive_version: 1,
        frame: FrameIdentity {
            source_digest: SourceDigest::from_bytes([7; 32]),
            package_name: "example.hierarchy".into(),
            captured_at_epoch_millis: 1,
            protocol_major: 1,
            protocol_minor: 1,
        },
        display: DisplayInfo {
            width_px: 1080,
            height_px: 2400,
            density: 3.0,
        },
        capabilities: Capabilities {
            view_hierarchy: true,
            compose_semantics: false,
            screenshots: false,
            timeline: false,
        },
        windows: windows
            .into_iter()
            .enumerate()
            .map(|(index, nodes)| LayoutWindow {
                id: format!("window-{index}"),
                title: None,
                kind: "ACTIVITY".into(),
                bounds: BOUNDS,
                nodes,
            })
            .collect(),
        default_window_id: "window-0".into(),
        screenshot: None,
        screenshot_png: None,
        compose: None,
        export_payload: Default::default(),
    })
}

fn default_snapshot(
    cache: &mut HierarchyCache,
    archive: &Arc<InspectorArchive>,
) -> Arc<HierarchySnapshot> {
    cache
        .get(
            archive,
            0,
            LayoutPreferences::default(),
            true,
            "",
            &BTreeSet::new(),
        )
        .expect("fixture window exists")
}

fn rich_nodes(count: usize) -> Vec<LayoutNode> {
    (0..count)
        .map(|index| {
            let mut sample = node(
                &format!("node-{index}"),
                usize::from(index != 0),
                (index != 0).then_some(0),
            );
            sample.class_name = format!("example.rich.RichHierarchyWidget{index}");
            sample.resource_name = Some(format!("example.hierarchy:id/rich_widget_{index}"));
            sample.text = Some(format!(
                "Captured runtime text {index}: {}",
                "text ".repeat(32)
            ));
            sample.semantics_role = Some("Button".into());
            sample.attributes.content_description = Some(format!("Description for {index}"));
            for property in 0..4 {
                sample.attributes.raw_properties.insert(
                    format!("runtime:property-{property}"),
                    format!("{index}-{property}-{}", "property ".repeat(16)),
                );
            }
            sample
                .attributes
                .semantic_properties
                .insert("role".into(), "Button".into());
            sample
        })
        .collect()
}

fn filter_archive() -> Arc<InspectorArchive> {
    let mut nodes = vec![
        node("root", 0, None),
        node("branch", 1, Some(0)),
        node("collapsed-child", 2, Some(1)),
        node("invisible", 1, Some(0)),
        node("invisible-child", 2, Some(3)),
        node("tail", 1, Some(0)),
        node("searchable-id", 2, Some(5)),
    ];
    nodes[2].class_name = "example.MatchLabel".into();
    nodes[3].class_name = "example.MatchHidden".into();
    nodes[3].visible = false;
    nodes[4].class_name = "example.MatchLabel".into();
    nodes[5].class_name = "example.MatchButton".into();
    nodes[6].resource_name = Some("example:id/match_resource".into());
    archive(vec![nodes])
}

#[test]
fn identical_requests_reuse_snapshot_and_row_storage() {
    let archive = archive(vec![vec![node("root", 0, None)]]);
    let mut cache = HierarchyCache::default();
    let first = default_snapshot(&mut cache, &archive);
    let second = default_snapshot(&mut cache, &archive);
    assert!(Arc::ptr_eq(&first, &second));
    assert!(Arc::ptr_eq(&first.rows, &second.rows));
    assert!(Arc::ptr_eq(&archive, &second.archive));
    assert_eq!(second.rows.as_slice(), &[0]);
    assert_eq!(second.numbers, ["0-0"]);
    assert_eq!(second.display_depths, [0]);
}

#[test]
fn ten_thousand_rich_nodes_share_archive_data_across_warm_lookups() {
    let archive = archive(vec![rich_nodes(10_000)]);
    let mut cache = HierarchyCache::default();
    let first = default_snapshot(&mut cache, &archive);
    assert_eq!(first.rows.len(), 10_000);
    assert_eq!(first.visible_nodes, 10_000);
    assert_eq!(first.numbers.last().unwrap(), "1-9998");
    assert!(first.rows.iter().copied().eq(0..10_000));
    assert!(
        first
            .display_depths
            .iter()
            .copied()
            .eq(archive.windows[0].nodes.iter().map(|node| node.depth))
    );
    for _ in 0..16 {
        let warm = default_snapshot(&mut cache, &archive);
        assert!(Arc::ptr_eq(&first, &warm));
        assert!(Arc::ptr_eq(&first.rows, &warm.rows));
        let shared_nodes = &warm.archive.windows[warm.window_index].nodes;
        let original_nodes = &archive.windows[0].nodes;
        assert_eq!(shared_nodes.as_ptr(), original_nodes.as_ptr());
        for index in [0, 1, 4999, 9999] {
            let shared = &shared_nodes[index];
            let original = &original_nodes[index];
            assert!(std::ptr::eq(shared, original));
            assert_eq!(
                shared.text.as_ref().unwrap().as_ptr(),
                original.text.as_ref().unwrap().as_ptr()
            );
            assert!(std::ptr::eq(
                &shared.attributes.raw_properties,
                &original.attributes.raw_properties
            ));
            assert_eq!(
                shared.attributes.raw_properties["runtime:property-0"].as_ptr(),
                original.attributes.raw_properties["runtime:property-0"].as_ptr(),
            );
            assert!(std::ptr::eq(
                &shared.attributes.semantic_properties,
                &original.attributes.semantic_properties
            ));
        }
    }
}

#[test]
fn replacing_archive_with_same_digest_rebuilds_from_new_pointer() {
    let original = archive(vec![vec![node("old-root", 0, None)]]);
    let replacement = archive(vec![vec![
        node("new-root", 0, None),
        node("new-child", 1, Some(0)),
    ]]);
    assert_eq!(
        original.frame.source_digest,
        replacement.frame.source_digest
    );
    let mut cache = HierarchyCache::default();
    let before = default_snapshot(&mut cache, &original);
    let after = default_snapshot(&mut cache, &replacement);
    assert!(!Arc::ptr_eq(&before, &after));
    assert!(Arc::ptr_eq(&after.archive, &replacement));
    assert_eq!(after.rows.as_slice(), &[0, 1]);
    assert_eq!(after.visible_nodes, 2);
    assert_eq!(after.archive.windows[0].nodes[0].id, "new-root");
    assert_eq!(before.archive.windows[0].nodes[0].id, "old-root");
}

#[test]
fn switching_windows_rebuilds_and_missing_window_does_not_poison_cache() {
    let archive = archive(vec![
        vec![node("root", 0, None)],
        vec![node("dialog", 0, None), node("child", 1, Some(0))],
    ]);
    let mut cache = HierarchyCache::default();
    let first = default_snapshot(&mut cache, &archive);
    let preferences = LayoutPreferences::default();
    let collapsed = BTreeSet::new();
    let dialog = cache
        .get(&archive, 1, preferences, true, "", &collapsed)
        .unwrap();
    assert!(!Arc::ptr_eq(&first, &dialog));
    assert_eq!(dialog.window_index, 1);
    assert_eq!(dialog.rows.as_slice(), &[0, 1]);
    assert_eq!(dialog.numbers, ["0-0", "1-0"]);
    assert!(
        cache
            .get(&archive, 2, preferences, true, "", &collapsed)
            .is_none()
    );
    let still_dialog = cache
        .get(&archive, 1, preferences, true, "", &collapsed)
        .unwrap();
    assert!(Arc::ptr_eq(&dialog, &still_dialog));
    let back = default_snapshot(&mut cache, &archive);
    assert!(!Arc::ptr_eq(&dialog, &back));
    assert_eq!(back.window_index, 0);
    assert_eq!(back.rows.as_slice(), &[0]);
}

#[test]
fn search_collapse_and_invisible_subtrees_preserve_full_preorder_numbers() {
    let archive = filter_archive();
    let mut cache = HierarchyCache::default();
    let all = default_snapshot(&mut cache, &archive);
    let expected_numbers = ["0-0", "1-0", "2-0", "1-1", "2-1", "1-2", "2-2"];
    assert_eq!(all.numbers, expected_numbers);
    assert_eq!(all.visible_nodes, 6);
    let preferences = LayoutPreferences {
        hide_invisible_hierarchy: true,
        ..Default::default()
    };
    let mut collapsed = BTreeSet::new();
    let visible = cache
        .get(&archive, 0, preferences, true, "", &collapsed)
        .unwrap();
    assert!(!Arc::ptr_eq(&all, &visible));
    assert_eq!(visible.rows.as_slice(), &[0, 1, 2, 5, 6]);
    assert_eq!(visible.visible_nodes, 6);
    collapsed.insert("branch".into());
    cache.invalidate();
    let folded = cache
        .get(&archive, 0, preferences, true, "", &collapsed)
        .unwrap();
    assert_eq!(folded.rows.as_slice(), &[0, 1, 5, 6]);
    let searched = cache
        .get(&archive, 0, preferences, true, "match", &collapsed)
        .unwrap();
    assert!(!Arc::ptr_eq(&folded, &searched));
    assert_eq!(searched.rows.as_slice(), &[5, 6]);
    assert_eq!(searched.numbers, expected_numbers);
    assert_eq!(
        searched
            .rows
            .iter()
            .map(|index| searched.numbers[*index].as_str())
            .collect::<Vec<_>>(),
        ["1-2", "2-2"]
    );
    let trimmed = cache
        .get(&archive, 0, preferences, true, "  match  ", &collapsed)
        .unwrap();
    assert!(Arc::ptr_eq(&searched, &trimmed));
}

#[test]
fn lowercase_search_matches_class_id_and_resource_without_renumbering() {
    let archive = filter_archive();
    let mut cache = HierarchyCache::default();
    for (query, expected_rows) in [
        ("matchbutton", vec![5]),
        ("searchable-id", vec![6]),
        ("match_resource", vec![6]),
        ("no-such-node", vec![]),
    ] {
        let snapshot = cache
            .get(
                &archive,
                0,
                LayoutPreferences::default(),
                true,
                query,
                &BTreeSet::new(),
            )
            .unwrap();
        assert_eq!(
            snapshot.rows.as_slice(),
            expected_rows.as_slice(),
            "query: {query}"
        );
        assert_eq!(snapshot.numbers[5], "1-2");
        assert_eq!(snapshot.numbers[6], "2-2");
    }
}

#[test]
fn label_preferences_rebuild_snapshot_and_content_width() {
    let mut sample = node("root", 0, None);
    sample.class_name = "example.LongHierarchyWidget".into();
    sample.resource_name = Some("example:id/very_long_resource_identifier_for_width".into());
    let archive = archive(vec![vec![sample]]);
    let mut cache = HierarchyCache::default();
    let baseline = default_snapshot(&mut cache, &archive);
    let sample = &archive.windows[0].nodes[0];
    for (preferences, label, wider) in [
        (
            LayoutPreferences {
                hide_indices: true,
                ..Default::default()
            },
            "id/very_long_resource_identifier_for_width  LongHierarchyWidget",
            false,
        ),
        (
            LayoutPreferences {
                show_ids: false,
                ..Default::default()
            },
            "0-0  LongHierarchyWidget",
            false,
        ),
        (
            LayoutPreferences {
                show_layer_buttons: true,
                ..Default::default()
            },
            "0-0  id/very_long_resource_identifier_for_width  LongHierarchyWidget",
            true,
        ),
    ] {
        let baseline = default_snapshot(&mut cache, &archive);
        let updated = cache
            .get(&archive, 0, preferences, true, "", &BTreeSet::new())
            .unwrap();
        assert!(!Arc::ptr_eq(&baseline, &updated));
        assert_eq!(updated.rows.as_slice(), baseline.rows.as_slice());
        assert_eq!(updated.numbers, baseline.numbers);
        assert_eq!(
            hierarchy_label(sample, &updated.numbers[0], preferences),
            label
        );
        assert_eq!(updated.content_width > baseline.content_width, wider);
        assert_ne!(updated.content_width, baseline.content_width);
        if preferences.show_layer_buttons {
            assert!((updated.content_width - baseline.content_width - 24.0).abs() < 0.01);
        }
    }
    assert!(baseline.content_width > 0.0);
}

#[test]
fn unrelated_preferences_do_not_rebuild_hierarchy() {
    let archive = archive(vec![vec![node("root", 0, None)]]);
    let mut cache = HierarchyCache::default();
    let baseline = default_snapshot(&mut cache, &archive);
    let preferences = LayoutPreferences {
        hide_invisible_findings: true,
        show_system_composables: false,
        show_visible_bounds: false,
        hit_smallest: false,
        archive_multiplier: 2,
        normal_color: 0xff010203,
        hover_color: 0xff040506,
        selected_color: 0xff070809,
        ..Default::default()
    };
    let updated = cache
        .get(&archive, 0, preferences, true, "", &BTreeSet::new())
        .unwrap();
    assert!(Arc::ptr_eq(&baseline, &updated));
}

fn with_compose_evidence(
    mut archive: Arc<InspectorArchive>,
    flags: impl IntoIterator<Item = (i64, bool)>,
) -> Arc<InspectorArchive> {
    let mutable = Arc::get_mut(&mut archive).unwrap();
    mutable.capabilities.compose_semantics = true;
    mutable.compose = Some(ComposeEvidence {
        frame_id: "frame-1".into(),
        generation: 1,
        mode: "snapshot".into(),
        privacy: "full".into(),
        declared_completeness: "complete".into(),
        capabilities: vec![],
        coverage: vec![],
        coverage_record_count: 0,
        truncation_count: 0,
        details_available: false,
        nodes: flags
            .into_iter()
            .map(|(node_id, system_created)| {
                (
                    node_id,
                    ComposeNodeEvidence {
                        node_id,
                        anchor_hash: 0,
                        name: format!("Composable{node_id}"),
                        source: None,
                        recompose_count: None,
                        skip_count: None,
                        system_created,
                        flags: vec![],
                    },
                )
            })
            .collect(),
        details: Default::default(),
        state_reads: Default::default(),
    });
    archive
}

#[test]
fn system_composable_filter_uses_latched_flag_and_keeps_user_descendants() {
    let mut nodes = vec![
        node("root", 0, None),
        node("compose-inspection:1", 1, Some(0)),
        node("compose-inspection:2", 2, Some(1)),
        node("compose-inspection:3", 1, Some(0)),
        node("1", 1, Some(0)),
    ];
    for sample in &mut nodes[1..4] {
        sample.kind = NodeKind::Compose;
    }
    let archive = with_compose_evidence(archive(vec![nodes]), [(1, true), (2, false)]);
    let mut cache = HierarchyCache::default();
    let preferences = LayoutPreferences {
        show_system_composables: false,
        ..Default::default()
    };
    let shown = cache
        .get(&archive, 0, preferences, true, "", &BTreeSet::new())
        .unwrap();
    assert_eq!(shown.rows.as_slice(), &[0, 1, 2, 3, 4]);
    assert_eq!(shown.display_depths, [0, 1, 2, 1, 1]);
    let hidden = cache
        .get(&archive, 0, preferences, false, "", &BTreeSet::new())
        .unwrap();
    assert!(!Arc::ptr_eq(&shown, &hidden));
    // Only evidenced Compose system nodes are removed, not their children,
    // nodes missing sidecar evidence, or View nodes with parseable numeric ids.
    assert_eq!(hidden.rows.as_slice(), &[0, 2, 3, 4]);
    assert_eq!(hidden.display_depths, [0, 1, 1, 1, 1]);
    assert_eq!(hidden.numbers, shown.numbers);
    let unlatched = cache
        .get(
            &archive,
            0,
            LayoutPreferences::default(),
            false,
            "",
            &BTreeSet::new(),
        )
        .unwrap();
    assert!(Arc::ptr_eq(&hidden, &unlatched));
}

fn deep_compose_archive() -> Arc<InspectorArchive> {
    let mut nodes = vec![node("root", 0, None), node("host", 1, Some(0))];
    let mut flags = Vec::new();
    for depth in 2..80 {
        let mut sample = node(
            &format!("compose-inspection:{depth}"),
            depth,
            Some(depth - 1),
        );
        sample.kind = NodeKind::Compose;
        if depth == 79 {
            sample.class_name = "example.AppContent".into();
        }
        nodes.push(sample);
        flags.push((depth as i64, depth != 2 && depth != 79));
    }
    nodes.push(node("hosted-view", 80, Some(79)));
    let mut sibling = node("compose-inspection:80", 79, Some(78));
    sibling.kind = NodeKind::Compose;
    sibling.class_name = "example.AppSibling".into();
    nodes.push(sibling);
    nodes.push(node("view-sibling", 2, Some(1)));
    with_compose_evidence(archive(vec![nodes]), flags)
}

#[test]
fn hidden_compose_wrappers_do_not_indent_app_nodes_or_hosted_views() {
    let archive = deep_compose_archive();
    let mut cache = HierarchyCache::default();
    let shown = default_snapshot(&mut cache, &archive);
    let hidden = cache
        .get(
            &archive,
            0,
            LayoutPreferences::default(),
            false,
            "",
            &BTreeSet::new(),
        )
        .unwrap();
    assert_eq!(hidden.rows.as_slice(), &[0, 1, 2, 79, 80, 81, 82]);
    assert_eq!(hidden.display_depths[79], 3);
    assert_eq!(hidden.display_depths[80], 4);
    assert_eq!(hidden.display_depths[81], 3);
    assert_eq!(hidden.display_depths[82], 2);
    assert_eq!(&hidden.display_depths[..3], &[0, 1, 2]);
    assert_eq!(shown.display_depths[79], 79);
    assert_eq!(shown.display_depths[80], 80);
    assert!(hidden.content_width < shown.content_width);
    assert_eq!(hidden.numbers, shown.numbers);
    assert!(Arc::ptr_eq(&hidden.archive, &archive));
    let original = &hidden.archive.windows[0].nodes;
    assert_eq!(original[79].depth, 79);
    assert_eq!(original[79].parent_index, Some(78));
    assert_eq!(original[80].parent_index, Some(79));
    assert_eq!(original[81].parent_index, Some(78));
}

#[test]
fn query_and_collapse_keep_projected_depths_and_measure_only_displayed_rows() {
    let archive = deep_compose_archive();
    let mut cache = HierarchyCache::default();
    let preferences = LayoutPreferences::default();
    let all = cache
        .get(&archive, 0, preferences, false, "", &BTreeSet::new())
        .unwrap();
    let matched = cache
        .get(
            &archive,
            0,
            preferences,
            false,
            "appcontent",
            &BTreeSet::new(),
        )
        .unwrap();
    assert_eq!(matched.rows.as_slice(), &[79]);
    assert_eq!(matched.display_depths, all.display_depths);
    assert_eq!(
        matched.content_width,
        hierarchy_content_width(
            &archive.windows[0].nodes,
            &[79],
            &all.display_depths,
            preferences
        )
    );
    cache.invalidate();
    let folded = cache
        .get(
            &archive,
            0,
            preferences,
            false,
            "",
            &BTreeSet::from(["compose-inspection:2".into()]),
        )
        .unwrap();
    assert_eq!(folded.rows.as_slice(), &[0, 1, 2, 82]);
    assert_eq!(folded.display_depths, all.display_depths);
    assert!(folded.content_width < all.content_width);
    let unmatched = cache
        .get(
            &archive,
            0,
            preferences,
            false,
            "no-such-node",
            &BTreeSet::new(),
        )
        .unwrap();
    assert!(unmatched.rows.is_empty());
    assert_eq!(unmatched.content_width, 0.0);
}

#[test]
fn hidden_system_root_promotes_children_without_affecting_other_roots() {
    let mut nodes = vec![
        node("compose-inspection:1", 0, None),
        node("compose-inspection:2", 1, Some(0)),
        node("compose-inspection:3", 2, Some(1)),
        node("other-root", 0, None),
        node("other-child", 1, Some(3)),
    ];
    for sample in &mut nodes[..3] {
        sample.kind = NodeKind::Compose;
    }
    nodes[0].class_name = "SystemWrapper".repeat(100);
    let archive = with_compose_evidence(archive(vec![nodes]), [(1, true), (2, false), (3, false)]);
    let mut cache = HierarchyCache::default();
    let shown = default_snapshot(&mut cache, &archive);
    let hidden = cache
        .get(
            &archive,
            0,
            LayoutPreferences::default(),
            false,
            "",
            &BTreeSet::new(),
        )
        .unwrap();
    assert_eq!(hidden.rows.as_slice(), &[1, 2, 3, 4]);
    assert_eq!(hidden.display_depths, [0, 0, 1, 0, 1]);
    assert!(hidden.content_width < 200.0);
    assert!(shown.content_width > 1000.0);
}

#[test]
#[ignore = "requires APS_INSPECTOR_COMPOSE_FIXTURE pointing to a real Compose capture archive"]
fn real_compose_archive_projects_hidden_ancestor_depths() {
    let path =
        std::env::var_os("APS_INSPECTOR_COMPOSE_FIXTURE").expect("Compose archive fixture path");
    let archive = Arc::new(
        InspectorArchive::open(
            std::path::Path::new(&path),
            &aps_platform::CancellationToken::new(),
        )
        .unwrap(),
    );
    let preferences = LayoutPreferences {
        hide_invisible_hierarchy: false,
        ..Default::default()
    };
    let mut cache = HierarchyCache::default();
    let shown = cache
        .get(&archive, 0, preferences, true, "", &BTreeSet::new())
        .unwrap();
    let hidden = cache
        .get(&archive, 0, preferences, false, "", &BTreeSet::new())
        .unwrap();
    let retained: BTreeSet<_> = hidden.rows.iter().copied().collect();
    let nodes = &archive.windows[0].nodes;
    for &index in hidden.rows.iter() {
        let mut expected_depth = 0;
        let mut parent = nodes[index].parent_index;
        while let Some(index) = parent {
            expected_depth += usize::from(retained.contains(&index));
            parent = nodes[index].parent_index;
        }
        assert_eq!(
            hidden.display_depths[index], expected_depth,
            "{}",
            nodes[index].id
        );
    }
    assert!(hidden.rows.len() < shown.rows.len());
    let raw_max = nodes.iter().map(|node| node.depth).max().unwrap();
    let display_max = hidden
        .rows
        .iter()
        .map(|&index| hidden.display_depths[index])
        .max()
        .unwrap();
    assert!(display_max < raw_max);
    assert!(hidden.content_width < shown.content_width);
    assert_eq!(hidden.numbers, shown.numbers);
    eprintln!(
        "Real Compose capture: {} -> {} rows, depth {} -> {}, width {:.1} -> {:.1} px",
        shown.rows.len(),
        hidden.rows.len(),
        raw_max,
        display_max,
        shown.content_width,
        hidden.content_width
    );
}

#[test]
fn collapse_changes_require_explicit_invalidation_then_rebuild() {
    let archive = filter_archive();
    let mut cache = HierarchyCache::default();
    cache.invalidate();
    let expanded = default_snapshot(&mut cache, &archive);
    let mut collapsed = BTreeSet::from(["branch".into()]);
    let unchanged = cache
        .get(
            &archive,
            0,
            LayoutPreferences::default(),
            true,
            "",
            &collapsed,
        )
        .unwrap();
    assert!(Arc::ptr_eq(&expanded, &unchanged));
    cache.invalidate();
    let folded = cache
        .get(
            &archive,
            0,
            LayoutPreferences::default(),
            true,
            "",
            &collapsed,
        )
        .unwrap();
    assert!(!Arc::ptr_eq(&expanded, &folded));
    assert_eq!(folded.rows.as_slice(), &[0, 1, 3, 4, 5, 6]);
    assert_eq!(folded.numbers, expanded.numbers);
    collapsed.clear();
    cache.invalidate();
    let reopened = cache
        .get(
            &archive,
            0,
            LayoutPreferences::default(),
            true,
            "",
            &collapsed,
        )
        .unwrap();
    assert!(!Arc::ptr_eq(&folded, &reopened));
    assert_eq!(reopened.rows.as_slice(), expanded.rows.as_slice());
}

#[test]
fn preview_warm_lookup_and_arc_clones_share_rich_node_storage() {
    let archive = archive(vec![rich_nodes(128)]);
    let mut cache = PreviewCache::default();
    let hidden = BTreeSet::new();
    let first = cache.get(&archive, 0, &hidden, &None).unwrap();
    assert_eq!(first.len(), 128);
    let cloned = first.clone();
    assert!(Arc::ptr_eq(&first, &cloned));
    for _ in 0..16 {
        let warm = cache
            .get(&archive.clone(), 0, &hidden.clone(), &None)
            .unwrap();
        assert!(Arc::ptr_eq(&first, &warm));
        assert_eq!(first.as_ptr(), warm.as_ptr());
        assert_eq!(
            first[127].1.text.as_ref().unwrap().as_ptr(),
            warm[127].1.text.as_ref().unwrap().as_ptr()
        );
        assert_eq!(
            first[127].1.attributes.raw_properties["runtime:property-0"].as_ptr(),
            warm[127].1.attributes.raw_properties["runtime:property-0"].as_ptr(),
        );
    }
}

#[test]
fn preview_hidden_parent_prunes_descendants_without_pruning_siblings() {
    let archive = archive(vec![vec![
        node("root", 0, None),
        node("branch", 1, Some(0)),
        node("child", 2, Some(1)),
        node("grandchild", 3, Some(2)),
        node("sibling", 1, Some(0)),
        node("sibling-child", 2, Some(4)),
    ]]);
    let mut cache = PreviewCache::default();
    let hidden = BTreeSet::from(["branch".into()]);
    let visible = cache
        .get(&archive, 0, &hidden, &Some("grandchild".into()))
        .unwrap();
    assert_eq!(
        visible.iter().map(|(index, _)| *index).collect::<Vec<_>>(),
        [0, 4, 5]
    );
    assert_eq!(
        visible
            .iter()
            .map(|(_, node)| node.id.as_str())
            .collect::<Vec<_>>(),
        ["root", "sibling", "sibling-child"]
    );
    let none = cache
        .get(&archive, 0, &BTreeSet::from(["root".into()]), &None)
        .unwrap();
    assert!(none.is_empty());
}

#[test]
fn preview_hides_system_composable_itself_but_keeps_user_descendant_hit() {
    let mut nodes = vec![
        node("root", 0, None),
        node("compose-inspection:1", 1, Some(0)),
        node("compose-inspection:2", 2, Some(1)),
    ];
    nodes[1].kind = NodeKind::Compose;
    nodes[2].kind = NodeKind::Compose;
    nodes[1].class_name = "Box".into();
    nodes[2].class_name = "ScreenContent".into();
    let archive = with_compose_evidence(archive(vec![nodes]), [(1, true), (2, false)]);
    let window = &archive.windows[0];
    let (visible, total) = super::super::canvas_nodes(
        &window.nodes,
        &BTreeSet::new(),
        None,
        archive.display.width_px,
        archive.display.height_px,
        archive.compose.as_ref(),
        false,
    );
    assert_eq!(total, 2);
    assert_eq!(
        visible.iter().map(|(index, _)| *index).collect::<Vec<_>>(),
        [0, 2]
    );
    assert_eq!(
        super::super::canvas_hit(&visible, 10.0, 10.0, true).as_deref(),
        Some("compose-inspection:2")
    );
}

#[test]
fn preview_selection_beyond_canvas_budget_is_retained_in_preorder() {
    let archive = archive(vec![rich_nodes(MAX_CANVAS_NODES + 2)]);
    let mut cache = PreviewCache::default();
    let hidden = BTreeSet::new();
    let unselected = cache.get(&archive, 0, &hidden, &None).unwrap();
    assert_eq!(unselected.len(), MAX_CANVAS_NODES);
    assert_eq!(unselected.last().unwrap().0, MAX_CANVAS_NODES - 1);
    let selected_index = MAX_CANVAS_NODES + 1;
    let selected = Some(format!("node-{selected_index}"));
    let selected_nodes = cache.get(&archive, 0, &hidden, &selected).unwrap();
    assert!(!Arc::ptr_eq(&unselected, &selected_nodes));
    assert_eq!(selected_nodes.len(), MAX_CANVAS_NODES);
    assert_eq!(selected_nodes.last().unwrap().0, selected_index);
    assert_eq!(
        selected_nodes.last().unwrap().1.id,
        selected.as_deref().unwrap()
    );
    assert!(selected_nodes.windows(2).all(|pair| pair[0].0 < pair[1].0));
    let warm = cache.get(&archive, 0, &hidden, &selected.clone()).unwrap();
    assert!(Arc::ptr_eq(&selected_nodes, &warm));
    let cleared = cache.get(&archive, 0, &hidden, &None).unwrap();
    assert!(!Arc::ptr_eq(&selected_nodes, &cleared));
    assert_eq!(cleared.last().unwrap().0, MAX_CANVAS_NODES - 1);
}

#[test]
fn preview_archive_identity_and_window_changes_rebuild_cached_nodes() {
    let original = archive(vec![
        vec![node("main", 0, None)],
        vec![node("dialog", 0, None)],
    ]);
    let replacement = archive(vec![vec![node("replacement", 0, None)]]);
    assert_eq!(
        original.frame.source_digest,
        replacement.frame.source_digest
    );
    let mut cache = PreviewCache::default();
    let hidden = BTreeSet::new();
    let main = cache.get(&original, 0, &hidden, &None).unwrap();
    let dialog = cache.get(&original, 1, &hidden, &None).unwrap();
    assert!(!Arc::ptr_eq(&main, &dialog));
    assert_eq!(dialog[0].1.id, "dialog");
    assert!(cache.get(&original, 2, &hidden, &None).is_none());
    let still_dialog = cache.get(&original, 1, &hidden, &None).unwrap();
    assert!(Arc::ptr_eq(&dialog, &still_dialog));
    let back = cache.get(&original, 0, &hidden, &None).unwrap();
    assert!(!Arc::ptr_eq(&dialog, &back));
    assert_eq!(back[0].1.id, "main");
    let replaced = cache.get(&replacement, 0, &hidden, &None).unwrap();
    assert!(!Arc::ptr_eq(&back, &replaced));
    assert_eq!(replaced[0].1.id, "replacement");
    assert_eq!(main[0].1.id, "main");
}

#[test]
fn preview_hidden_and_selected_keys_compare_values_and_rebuild_on_change() {
    let archive = archive(vec![vec![
        node("root", 0, None),
        node("a", 1, Some(0)),
        node("b", 1, Some(0)),
    ]]);
    let mut cache = PreviewCache::default();
    let mut hidden = BTreeSet::from(["a".into()]);
    let mut selected = Some("root".into());
    let first = cache.get(&archive, 0, &hidden, &selected).unwrap();
    let equal_values = cache
        .get(&archive, 0, &hidden.clone(), &selected.clone())
        .unwrap();
    assert!(Arc::ptr_eq(&first, &equal_values));
    hidden.clear();
    hidden.insert("b".into());
    let changed_hidden = cache.get(&archive, 0, &hidden, &selected).unwrap();
    assert!(!Arc::ptr_eq(&first, &changed_hidden));
    assert_eq!(
        changed_hidden
            .iter()
            .map(|(index, _)| *index)
            .collect::<Vec<_>>(),
        [0, 1]
    );
    selected = Some("a".into());
    let changed_selected = cache.get(&archive, 0, &hidden, &selected).unwrap();
    assert!(!Arc::ptr_eq(&changed_hidden, &changed_selected));
    hidden.clear();
    let shown = cache.get(&archive, 0, &hidden, &selected).unwrap();
    assert!(!Arc::ptr_eq(&changed_selected, &shown));
    assert_eq!(shown.len(), 3);
}

#[test]
fn production_hierarchy_rows_borrow_shared_snapshot_without_cloning_nodes() {
    let source = include_str!("../../inspector.rs");
    let production = source.split("#[cfg(test)]").next().unwrap();
    let pane = production
        .split_once("fn hierarchy_pane(")
        .unwrap()
        .1
        .split_once("fn hierarchy_row(")
        .unwrap()
        .0;
    let pane: String = pane.split_whitespace().collect();
    assert!(pane.contains("snapshot:Option<Arc<hierarchy::HierarchySnapshot>>"));
    assert!(pane.contains("hierarchy_list::render(snapshot,"));
    assert!(!pane.contains(".nodes.clone()"));
    assert!(!pane.contains("hierarchy_numbers("));
    assert!(!pane.contains("hierarchy_content_width("));

    let list = include_str!("../hierarchy_list.rs");
    let list: String = list.split_whitespace().collect();
    assert!(list.contains("snapshot:Arc<HierarchySnapshot>"));
    assert!(list.contains("&snapshot.archive.windows[snapshot.window_index].nodes"));
    assert!(list.contains("snapshot.rows.get(row_index)"));
    assert!(list.contains("snapshot.numbers.get(global_index)"));
    assert!(list.contains("snapshot.display_depths.get(global_index)"));
    assert!(!list.contains(".nodes.clone()"));
    assert!(!list.contains("nodes.to_vec()"));
    let row = production
        .split_once("fn hierarchy_row(")
        .unwrap()
        .1
        .split_once("row.into_any_element()")
        .unwrap()
        .0;
    assert!(row.contains("let indent = hierarchy_indent(display_depth);"));
    assert!(!row.contains("node.depth"));
}

#[test]
fn production_render_uses_preview_cache_and_passes_arc_nodes_to_geometry() {
    let source = include_str!("../../inspector.rs");
    let production = source.split("#[cfg(test)]").next().unwrap();
    let root = production
        .split_once("impl Render for InspectorWorkspace {")
        .unwrap()
        .1
        .split_once("\n}\n")
        .unwrap()
        .0;
    let root: String = root.split_whitespace().collect();
    assert!(root.contains("lethierarchy=self.hierarchy_snapshot(cx);"));
    assert!(root.contains("hierarchy_pane(language,&colors,hierarchy,"));
    assert_eq!(
        root.matches("self.preview_cache.get_with_system_composables(")
            .count(),
        1
    );
    assert!(root.contains("self.preview_cache.get_with_system_composables("));
    assert!(root.contains("self.frame_show_system_composables"));
    assert!(root.contains("self.preview_pane(language,&colors,archive.clone(),preview_nodes,"));
    assert!(!root.contains("canvas_nodes("));
    assert!(!root.contains(".nodes.clone()"));

    let preview = production
        .split_once("fn preview_pane(")
        .unwrap()
        .1
        .split_once("fn geometry_canvas(")
        .unwrap()
        .0;
    let preview: String = preview.split_whitespace().collect();
    assert!(preview.contains("nodes:Arc<Vec<(usize,LayoutNode)>>"));
    assert!(preview.contains("self.geometry_canvas(nodes,"));
    assert!(!preview.contains("canvas_nodes("));
    let geometry = production
        .split_once("fn geometry_canvas(")
        .unwrap()
        .1
        .split_once("\nfn section_header(")
        .unwrap()
        .0;
    let geometry: String = geometry.split_whitespace().collect();
    assert!(geometry.contains("nodes:Arc<Vec<(usize,LayoutNode)>>"));
    assert!(geometry.contains("lethit_nodes=nodes.clone();"));
    assert!(geometry.contains("lethover_nodes=nodes.clone();"));
    assert!(!geometry.contains("canvas_nodes("));

    let cache = include_str!("../hierarchy.rs");
    assert_eq!(cache.matches("super::canvas_nodes").count(), 1);
}

#[test]
#[ignore = "quantitative CPU-preparation microbenchmark; run explicitly with --ignored --nocapture"]
fn hierarchy_cpu_preparation_microbenchmark() {
    const LEGACY_ITERATIONS: usize = 32;
    const WARM_ITERATIONS: usize = 4096;
    let archive = archive(vec![rich_nodes(10_000)]);
    let preferences = LayoutPreferences::default();
    let collapsed = BTreeSet::new();
    let mut cache = HierarchyCache::default();
    let expected = default_snapshot(&mut cache, &archive);

    let start = Instant::now();
    for _ in 0..LEGACY_ITERATIONS {
        let nodes = black_box(&archive.windows[0].nodes).clone();
        let numbers = hierarchy_numbers(black_box(&nodes));
        // This fixture has no filters or collapsed subtrees, matching the warm lookup.
        let rows: Vec<usize> = black_box(&nodes)
            .iter()
            .enumerate()
            .map(|(index, _)| index)
            .collect();
        let depths: Vec<_> = nodes.iter().map(|node| node.depth).collect();
        let width = hierarchy_content_width(
            black_box(&nodes),
            black_box(&rows),
            black_box(&depths),
            black_box(preferences),
        );
        let visible_nodes = nodes.iter().filter(|node| node.visible).count();
        assert_eq!(rows.len(), expected.rows.len());
        black_box((nodes, numbers, width, rows, visible_nodes));
    }
    let legacy = start.elapsed();

    let start = Instant::now();
    for _ in 0..WARM_ITERATIONS {
        let snapshot = cache
            .get(
                black_box(&archive),
                0,
                black_box(preferences),
                true,
                "",
                black_box(&collapsed),
            )
            .unwrap();
        black_box(snapshot);
    }
    let warm = start.elapsed();
    eprintln!(
        "Hierarchy CPU preparation only (10,000 rich nodes; excludes GPUI layout/paint):\n\
         legacy nodes.clone + numbers + width + row indices + visible count: {LEGACY_ITERATIONS} iterations, {:.3} ms total, {:.1} ns/render\n\
         warm cache lookup: {WARM_ITERATIONS} iterations, {:.3} ms total, {:.1} ns/lookup",
        legacy.as_secs_f64() * 1000.0,
        legacy.as_secs_f64() * 1e9 / LEGACY_ITERATIONS as f64,
        warm.as_secs_f64() * 1000.0,
        warm.as_secs_f64() * 1e9 / WARM_ITERATIONS as f64,
    );
}

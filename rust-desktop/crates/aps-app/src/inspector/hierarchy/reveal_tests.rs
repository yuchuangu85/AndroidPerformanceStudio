use super::{HierarchyCache, reveal_ancestors};
use aps_contracts::SourceDigest;
use aps_layout_inspector::{
    Bounds, Capabilities, DisplayInfo, FrameIdentity, InspectorArchive, LayoutNode, LayoutWindow,
    NodeKind,
};
use aps_ui_foundation::LayoutPreferences;
use std::{collections::BTreeSet, sync::Arc};

const BOUNDS: Bounds = Bounds {
    left: 0,
    top: 0,
    right: 100,
    bottom: 100,
};

fn node(index: usize, parent_index: Option<usize>, depth: usize) -> LayoutNode {
    LayoutNode {
        id: index.to_string(),
        kind: NodeKind::View,
        class_name: "example.View".into(),
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

fn branches() -> Vec<LayoutNode> {
    vec![
        node(0, None, 0),
        node(1, Some(0), 1),
        node(2, Some(1), 2),
        node(3, Some(2), 3),
        node(4, Some(0), 1),
        node(5, Some(4), 2),
    ]
}

fn archive(nodes: Vec<LayoutNode>) -> Arc<InspectorArchive> {
    Arc::new(InspectorArchive {
        archive_version: 1,
        frame: FrameIdentity {
            source_digest: SourceDigest::from_bytes([7; 32]),
            package_name: "example.reveal".into(),
            captured_at_epoch_millis: 1,
            protocol_major: 1,
            protocol_minor: 1,
        },
        display: DisplayInfo {
            width_px: 100,
            height_px: 100,
            density: 1.0,
        },
        capabilities: Capabilities {
            view_hierarchy: true,
            compose_semantics: false,
            screenshots: false,
            timeline: false,
        },
        windows: vec![LayoutWindow {
            id: "window".into(),
            title: None,
            kind: "ACTIVITY".into(),
            bounds: BOUNDS,
            nodes,
        }],
        default_window_id: "window".into(),
        screenshot: None,
        screenshot_png: None,
        compose: None,
        export_payload: Default::default(),
    })
}

fn collapsed(ids: &[&str]) -> BTreeSet<String> {
    ids.iter().map(|id| (*id).to_owned()).collect()
}

#[test]
fn deep_ancestors_expand_once_without_expanding_the_selected_row() {
    let nodes: Vec<_> = (0usize..256)
        .map(|index| node(index, index.checked_sub(1), index))
        .collect();
    let mut folded: BTreeSet<_> = nodes.iter().map(|node| node.id.clone()).collect();
    folded.insert("unrelated".into());
    assert!(reveal_ancestors(&nodes, 255, &mut folded));
    assert_eq!(folded, collapsed(&["255", "unrelated"]));
    assert!(!reveal_ancestors(&nodes, 255, &mut folded));
}

#[test]
fn selected_descendants_and_unrelated_branches_stay_collapsed() {
    let mut folded = collapsed(&["0", "1", "2", "3", "4", "5", "orphan"]);
    assert!(reveal_ancestors(&branches(), 2, &mut folded));
    assert_eq!(folded, collapsed(&["2", "3", "4", "5", "orphan"]));
    assert!(!reveal_ancestors(&branches(), 2, &mut folded));
}

#[test]
fn roots_invalid_indices_and_invalid_parent_edges_are_bounded() {
    let mut nodes = vec![
        node(0, None, 0),
        node(1, Some(usize::MAX), 1),
        node(2, Some(3), 1),
        node(3, None, 0),
    ];
    let original = collapsed(&["0", "1", "2", "3"]);
    let mut folded = original.clone();
    assert!(!reveal_ancestors(&[], 0, &mut folded));
    for index in [0, 1, 2, 3, 4, usize::MAX] {
        assert!(!reveal_ancestors(&nodes, index, &mut folded));
        assert_eq!(folded, original);
    }
    nodes[0].parent_index = Some(2);
    assert!(!reveal_ancestors(&nodes, 0, &mut folded));
    nodes[3].parent_index = Some(1);
    assert!(reveal_ancestors(&nodes, 3, &mut folded));
    assert_eq!(folded, collapsed(&["0", "2", "3"]));
}

#[test]
fn self_and_multi_node_cycles_stop_at_the_first_non_backward_edge() {
    let mut nodes = vec![
        node(0, None, 0),
        node(1, Some(1), 1),
        node(2, Some(1), 2),
        node(3, Some(2), 3),
    ];
    let mut folded = collapsed(&["0", "1", "2", "3"]);
    assert!(!reveal_ancestors(&nodes, 1, &mut folded));
    assert_eq!(folded, collapsed(&["0", "1", "2", "3"]));
    nodes[1].parent_index = Some(2);
    assert!(reveal_ancestors(&nodes, 3, &mut folded));
    assert_eq!(folded, collapsed(&["0", "3"]));
    assert!(!reveal_ancestors(&nodes, 3, &mut folded));
}

#[test]
fn expansion_invalidation_reveals_selected_position_preserving_numbers_and_sharing() {
    let archive = archive(branches());
    let mut cache = HierarchyCache::default();
    let preferences = LayoutPreferences::default();
    let mut folded = collapsed(&["1", "2", "4"]);
    let before = cache
        .get(&archive, 0, preferences, true, "", &folded)
        .unwrap();
    assert_eq!(before.rows.as_slice(), &[0, 1, 4]);
    assert!(!before.rows.contains(&2));
    assert!(reveal_ancestors(&archive.windows[0].nodes, 2, &mut folded));
    cache.invalidate();
    let after = cache
        .get(&archive, 0, preferences, true, "", &folded)
        .unwrap();
    assert!(!Arc::ptr_eq(&before, &after));
    assert_eq!(after.rows.as_slice(), &[0, 1, 2, 4]);
    assert_eq!(after.rows.iter().position(|&index| index == 2), Some(2));
    assert_eq!(after.numbers, before.numbers);
    assert_eq!(after.numbers, ["0-0", "1-0", "2-0", "3-0", "1-1", "2-1"]);
    assert_eq!(folded, collapsed(&["2", "4"]));
    assert!(Arc::ptr_eq(&after.archive, &archive));
    assert!(!reveal_ancestors(&archive.windows[0].nodes, 2, &mut folded));
    let warm = cache
        .get(&archive, 0, preferences, true, "", &folded)
        .unwrap();
    assert!(Arc::ptr_eq(&after, &warm));
    assert!(Arc::ptr_eq(&after.rows, &warm.rows));
}

fn source_block<'a>(source: &'a str, marker: &str) -> &'a str {
    let section = &source[source.find(marker).expect("source marker exists")..];
    let start = section.find('{').expect("block exists");
    let mut depth = 0;
    for (offset, ch) in section[start..].char_indices() {
        match ch {
            '{' => depth += 1,
            '}' => depth -= 1,
            _ => continue,
        }
        if depth == 0 {
            return &section[..start + offset + 1];
        }
    }
    panic!("unterminated source block: {marker}");
}

#[test]
fn preview_and_findings_share_nearest_reveal_without_treating_drags_as_clicks() {
    let compact = |source: &str| source.split_whitespace().collect::<String>();
    let source = compact(include_str!("../../inspector.rs"));
    let selection = source.find("this.selected=canvas_hit(").unwrap();
    let start = source[..selection].rfind(".on_mouse_up(").unwrap();
    let click = source_block(&source[start..], ".on_mouse_up(");
    let selection = click.find("this.selected=canvas_hit(").unwrap();
    let guard = click.find("ifdrag.moved{return;}").unwrap();
    let reveal = click.find("this.reveal_selected_node(window,cx);").unwrap();
    assert!(guard < selection && selection < reveal);
    let method = source_block(&source, "fnreveal_selected_node(");
    let signature = method.split('{').next().unwrap();
    assert!(signature.contains("window:&mutWindow"));
    assert!(signature.contains("cx:&mutContext<Self>"));
    let expansion = source_block(method, "ifhierarchy::reveal_ancestors(");
    assert!(expansion.contains("self.hierarchy_cache.invalidate();"));
    assert!(method.contains(".scroll_to_item(position,ScrollStrategy::Nearest)"));
    assert!(!method.contains("ScrollStrategy::Strict"));
    let findings = compact(include_str!("../findings.rs"));
    let select = source_block(&findings, "fnselect_finding(");
    let selection = select.find("self.selected=").unwrap();
    let reveal = select
        .find("self.reveal_selected_node(window,cx);")
        .unwrap();
    assert!(selection < reveal);
    assert!(!select.contains(".scroll_to_item("));
}

#[test]
fn hierarchy_list_reserves_both_scrollbar_gutters_for_the_last_row() {
    let source = include_str!("../hierarchy_list.rs");
    assert!(source.contains("const SCROLLBAR_INSET: f32 = 12.0"));
    assert!(source.contains(".pr(px(SCROLLBAR_INSET))"));
    assert!(source.contains(".pb(px(SCROLLBAR_INSET))"));
    assert!(source.contains("ScrollbarAxis::Both"));
}

#[test]
fn horizontal_reveal_keeps_deep_compose_layout_names_in_view() {
    let width = 308.0;
    let (left, right) = super::super::hierarchy_label_bounds(
        &node(79, Some(78), 79),
        "79-0",
        79,
        LayoutPreferences::default(),
    );
    assert!(left > width);
    let offset = super::reveal_horizontal_offset(left, right, width, right + 32.0, 0.0);
    assert!(offset < 0.0);
    assert!(left + offset >= 0.0);
    assert!(right + offset <= width);
    assert_eq!(
        super::reveal_horizontal_offset(left, right, width, right + 32.0, offset),
        offset,
        "repeated preview clicks must not move an already visible label",
    );
}

#[test]
fn horizontal_reveal_returns_to_shallow_names_and_bounds_long_names() {
    assert_eq!(
        super::reveal_horizontal_offset(24.0, 180.0, 308.0, 900.0, -500.0),
        -24.0
    );
    assert_eq!(
        super::reveal_horizontal_offset(24.0, 180.0, 308.0, 900.0, 0.0),
        0.0
    );
    assert_eq!(
        super::reveal_horizontal_offset(500.0, 1100.0, 180.0, 1200.0, 0.0),
        -500.0
    );
    assert_eq!(
        super::reveal_horizontal_offset(500.0, 600.0, 0.0, 900.0, 0.0),
        0.0
    );
}

use super::{hierarchy_content_width, hierarchy_display_depths, hierarchy_indent};
use aps_layout_inspector::{Bounds, ComposeEvidence, ComposeNodeEvidence, LayoutNode, NodeKind};
use aps_ui_foundation::LayoutPreferences;

fn sample(index: usize, depth: usize, parent_index: Option<usize>, kind: NodeKind) -> LayoutNode {
    LayoutNode {
        id: if kind == NodeKind::Compose {
            format!("compose-inspection:{index}")
        } else {
            format!("view:{index}")
        },
        kind,
        class_name: "example.Widget".into(),
        parent_index,
        depth,
        bounds: Bounds {
            left: 0,
            top: 0,
            right: 100,
            bottom: 100,
        },
        visible: true,
        alpha: 1.0,
        resource_name: None,
        text: None,
        semantics_role: None,
        attributes: Default::default(),
    }
}

fn fixture() -> (Vec<LayoutNode>, ComposeEvidence) {
    let mut nodes = vec![
        sample(0, 0, None, NodeKind::View),
        sample(1, 1, Some(0), NodeKind::View),
    ];
    for depth in 2..80 {
        nodes.push(sample(depth, depth, Some(depth - 1), NodeKind::Compose));
    }
    nodes.push(sample(80, 80, Some(79), NodeKind::View));
    nodes.push(sample(81, 79, Some(78), NodeKind::Compose));
    nodes.push(sample(82, 2, Some(1), NodeKind::View));
    let evidence = ComposeEvidence {
        frame_id: "frame".into(),
        generation: 1,
        mode: "snapshot".into(),
        privacy: "full".into(),
        declared_completeness: "complete".into(),
        capabilities: vec![],
        coverage: vec![],
        coverage_record_count: 0,
        truncation_count: 0,
        details_available: false,
        nodes: (2..80)
            .map(|id| {
                (
                    id,
                    ComposeNodeEvidence {
                        node_id: id,
                        anchor_hash: 0,
                        name: "Widget".into(),
                        source: None,
                        recompose_count: None,
                        skip_count: None,
                        system_created: (3..79).contains(&id),
                        flags: vec![],
                    },
                )
            })
            .collect(),
        details: Default::default(),
        state_reads: Default::default(),
    };
    (nodes, evidence)
}

#[::core::prelude::v1::test]
fn hidden_wrappers_promote_descendants_without_shifting_siblings_or_native_hosts() {
    let (nodes, evidence) = fixture();
    let depths = hierarchy_display_depths(&nodes, Some(&evidence), false);
    assert_eq!(&depths[..3], &[0, 1, 2]);
    assert_eq!(&depths[79..], &[3, 4, 3, 2]);
    assert_eq!(nodes[79].depth, 79);
    assert_eq!(nodes[79].parent_index, Some(78));
    assert_eq!(nodes[80].parent_index, Some(79));
    assert_eq!(nodes[81].parent_index, Some(78));
    assert_eq!(hierarchy_indent(depths[79]), 50.0);
}

#[::core::prelude::v1::test]
fn full_tree_and_missing_sidecar_keep_the_captured_depths() {
    let (nodes, evidence) = fixture();
    let original: Vec<_> = nodes.iter().map(|node| node.depth).collect();
    assert_eq!(
        hierarchy_display_depths(&nodes, Some(&evidence), true),
        original
    );
    assert_eq!(hierarchy_display_depths(&nodes, None, false), original);
    assert!(hierarchy_display_depths(&[], None, false).is_empty());
}

#[::core::prelude::v1::test]
fn hidden_system_root_does_not_shift_an_independent_root() {
    let (mut nodes, mut evidence) = fixture();
    nodes.truncate(2);
    nodes[0] = sample(2, 0, None, NodeKind::Compose);
    nodes[1] = sample(3, 1, Some(0), NodeKind::Compose);
    nodes.push(sample(4, 2, Some(1), NodeKind::Compose));
    nodes.push(sample(5, 0, None, NodeKind::View));
    nodes.push(sample(6, 1, Some(3), NodeKind::View));
    evidence.nodes.get_mut(&2).unwrap().system_created = true;
    evidence.nodes.get_mut(&3).unwrap().system_created = false;
    evidence.nodes.get_mut(&4).unwrap().system_created = false;
    assert_eq!(
        hierarchy_display_depths(&nodes, Some(&evidence), false),
        [0, 0, 1, 0, 1]
    );
}

#[::core::prelude::v1::test]
fn horizontal_extent_uses_projected_depths_and_only_the_displayed_rows() {
    let (mut nodes, evidence) = fixture();
    nodes[3].class_name = "SystemWrapper".repeat(100);
    let projected = hierarchy_display_depths(&nodes, Some(&evidence), false);
    let original = hierarchy_display_depths(&nodes, Some(&evidence), true);
    let preferences = LayoutPreferences::default();
    let displayed = [0, 1, 2, 79, 80, 81, 82];
    let width = hierarchy_content_width(&nodes, &displayed, &projected, preferences);
    assert!(width < 250.0);
    assert!(width < hierarchy_content_width(&nodes, &displayed, &original, preferences));
    assert_eq!(
        hierarchy_content_width(&nodes, &[], &projected, preferences),
        0.0
    );
    let filtered = hierarchy_content_width(&nodes, &[79], &projected, preferences);
    assert_eq!(width - filtered, 14.0);
}

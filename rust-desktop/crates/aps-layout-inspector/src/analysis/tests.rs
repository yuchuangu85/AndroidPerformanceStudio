use super::*;
use crate::{Bounds, Capabilities, DisplayInfo, FrameIdentity, LayoutNode, NodeKind};
use aps_contracts::SourceDigest;

fn node(
    id: &str,
    parent_index: Option<usize>,
    depth: usize,
    bounds: Bounds,
    visible: bool,
) -> LayoutNode {
    LayoutNode {
        id: id.into(),
        kind: NodeKind::Compose,
        class_name: "Example".into(),
        parent_index,
        depth,
        bounds,
        visible,
        alpha: 1.0,
        resource_name: None,
        text: None,
        semantics_role: None,
        attributes: Default::default(),
    }
}

fn archive(nodes: Vec<LayoutNode>) -> InspectorArchive {
    InspectorArchive {
        archive_version: 1,
        frame: FrameIdentity {
            source_digest: SourceDigest::from_bytes([0; 32]),
            package_name: "example.test".into(),
            captured_at_epoch_millis: 1,
            protocol_major: 1,
            protocol_minor: 0,
        },
        display: DisplayInfo {
            width_px: 100,
            height_px: 100,
            density: 1.0,
        },
        capabilities: Capabilities {
            view_hierarchy: true,
            compose_semantics: true,
            screenshots: false,
            timeline: false,
        },
        windows: vec![LayoutWindow {
            id: "window:legacy".into(),
            kind: "OTHER".into(),
            bounds: Bounds {
                left: 0,
                top: 0,
                right: 100,
                bottom: 100,
            },
            nodes,
        }],
        default_window_id: "window:legacy".into(),
        screenshot: None,
        screenshot_png: None,
        compose: None,
        export_payload: Default::default(),
    }
}

#[test]
fn reports_compose_oracle_rules_from_captured_nodes() {
    let mut nodes = vec![node(
        "root",
        None,
        0,
        Bounds {
            left: 0,
            top: 0,
            right: 100,
            bottom: 100,
        },
        true,
    )];
    nodes.push(node(
        "hidden",
        Some(0),
        1,
        Bounds {
            left: 0,
            top: 0,
            right: 20,
            bottom: 20,
        },
        false,
    ));
    for index in 0..11 {
        nodes.push(node(
            &format!("child-{index}"),
            Some(0),
            1,
            Bounds {
                left: index * 2,
                top: 0,
                right: 100,
                bottom: 100,
            },
            true,
        ));
    }
    let report = archive(nodes).analyze_window("window:legacy").unwrap();

    assert!(
        report.findings.iter().any(|finding| {
            finding.node_index == 1 && finding.rule == LayoutRule::InvisibleNode
        })
    );
    assert!(report.findings.iter().any(|finding| {
        finding.node_index == 0
            && finding.rule
                == (LayoutRule::ExcessiveChildren {
                    count: 12,
                    threshold: 10,
                })
    }));
    assert!(report.findings.iter().any(|finding| {
        finding.node_index == 0
            && finding.rule
                == (LayoutRule::OverlappingSiblings {
                    count: 12,
                    ratio_percent: 80,
                })
    }));
}

#[test]
fn does_not_invent_recomposition_or_screenshot_findings() {
    let report = archive(vec![node(
        "root",
        None,
        0,
        Bounds {
            left: 0,
            top: 0,
            right: 100,
            bottom: 100,
        },
        true,
    )])
    .analyze_window("window:legacy")
    .unwrap();

    assert!(report.findings.is_empty());
}

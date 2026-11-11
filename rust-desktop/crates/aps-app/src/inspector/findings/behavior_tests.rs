use super::*;
use aps_layout_inspector::{Bounds, LayoutFinding};

pub(super) fn fixture() -> (LayoutWindow, LayoutAnalysis) {
    let nodes = (0..1_000)
        .map(|index| LayoutNode {
            id: format!("node-{index}"),
            kind: NodeKind::View,
            class_name: format!("example.Widget{index}"),
            parent_index: (index > 0).then_some(0),
            depth: usize::from(index > 0),
            bounds: Bounds {
                left: 0,
                top: 0,
                right: 100,
                bottom: 100,
            },
            visible: index != 0,
            alpha: 1.0,
            resource_name: None,
            text: None,
            semantics_role: None,
            attributes: Default::default(),
        })
        .collect();
    let report = LayoutAnalysis {
        hierarchy_available: true,
        node_count: 1_000,
        findings: (0..500)
            .map(|index| LayoutFinding {
                node_index: index,
                severity: match index % 3 {
                    0 => FindingSeverity::Info,
                    1 => FindingSeverity::Warning,
                    _ => FindingSeverity::Error,
                },
                rule: LayoutRule::InvisibleNode,
            })
            .collect(),
        ..Default::default()
    };
    (
        LayoutWindow {
            id: "window".into(),
            kind: "ACTIVITY".into(),
            bounds: Bounds {
                left: 0,
                top: 0,
                right: 100,
                bottom: 100,
            },
            nodes,
        },
        report,
    )
}

#[::core::prelude::v1::test]
fn filtering_preserves_report_indices_and_excludes_invisible_ancestors() {
    let (mut window, report) = fixture();
    let all = project(&report, Some(&window), false);
    assert_eq!(all.len(), 500);
    assert!(
        all.iter()
            .enumerate()
            .all(|(index, row)| row.index == index)
    );
    assert_eq!(
        all.iter()
            .filter(|row| tone_matches(row.finding.severity, FindingsTone::Info))
            .count(),
        167
    );
    assert_eq!(
        all.iter()
            .filter(|row| tone_matches(row.finding.severity, FindingsTone::Warning))
            .count(),
        167
    );
    assert_eq!(
        all.iter()
            .filter(|row| tone_matches(row.finding.severity, FindingsTone::Error))
            .count(),
        166
    );
    assert!(project(&report, Some(&window), true).is_empty());
    window.nodes[0].visible = true;
    window.nodes[7].visible = false;
    let visible = project(&report, Some(&window), true);
    assert_eq!(visible.len(), 499);
    assert!(!visible.iter().any(|row| row.index == 7));
    assert_eq!(visible[7].index, 8);
}

#[::core::prelude::v1::test]
fn all_structural_rules_keep_full_problem_and_description_text_in_both_locales() {
    let (window, _) = fixture();
    for language in [Language::English, Language::SimplifiedChinese] {
        for rule in [
            LayoutRule::InvisibleNode,
            LayoutRule::ExcessiveChildren {
                count: 28,
                threshold: 10,
            },
            LayoutRule::OverlappingSiblings {
                count: 14,
                ratio_percent: 80,
            },
            LayoutRule::DeepHierarchy {
                depth: 79,
                threshold: 10,
            },
        ] {
            let (problem, description) = format_finding(language, rule, &window.nodes[12]);
            assert!(!problem.is_empty());
            assert!(!description.is_empty());
            assert_ne!(problem, description);
            if rule == LayoutRule::InvisibleNode {
                assert!(description.contains("example.Widget12"));
            }
        }
    }
}

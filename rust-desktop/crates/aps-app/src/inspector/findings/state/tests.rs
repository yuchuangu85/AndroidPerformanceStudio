use super::*;
use aps_contracts::SourceDigest;
use aps_layout_inspector::{
    Bounds, Capabilities, DisplayInfo, FindingSeverity, FrameIdentity, InspectorArchive,
    LayoutFinding, LayoutNode, LayoutRule, LayoutWindow, NodeKind,
};
use aps_ui_foundation::{Language, TextKey};
use gpui_kit::{ListOffset, px};
use std::{hint::black_box, sync::Arc, time::Instant};

const NODE_COUNT: usize = 10_000;
const FINDING_COUNT: usize = 500;
const BOUNDS: Bounds = Bounds {
    left: 0,
    top: 0,
    right: 100,
    bottom: 100,
};

fn archive(windows: Vec<LayoutWindow>) -> Arc<InspectorArchive> {
    Arc::new(InspectorArchive {
        archive_version: 1,
        frame: FrameIdentity {
            source_digest: SourceDigest::from_bytes([7; 32]),
            package_name: "example.findings-state".into(),
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
        default_window_id: windows
            .first()
            .map(|window| window.id.clone())
            .unwrap_or_default(),
        windows,
        screenshot: None,
        screenshot_png: None,
        compose: None,
        export_payload: Default::default(),
    })
}

fn large_fixture() -> (Arc<InspectorArchive>, Arc<LayoutAnalysis>) {
    let (mut window, mut report) = super::super::behavior_tests::fixture();
    let template = window.nodes[1].clone();
    window
        .nodes
        .extend((window.nodes.len()..NODE_COUNT).map(|index| {
            let mut node = template.clone();
            node.id = format!("node-{index}");
            node.class_name = format!("example.Widget{index}");
            node
        }));
    report.node_count = window.nodes.len();
    assert_eq!(window.nodes.len(), NODE_COUNT);
    assert_eq!(report.findings.len(), FINDING_COUNT);
    (archive(vec![window]), Arc::new(report))
}

fn report_for(nodes: usize, findings: Vec<LayoutFinding>) -> Arc<LayoutAnalysis> {
    Arc::new(LayoutAnalysis {
        hierarchy_available: true,
        node_count: nodes,
        findings,
        ..Default::default()
    })
}

fn finding(node_index: usize, severity: FindingSeverity) -> LayoutFinding {
    LayoutFinding {
        node_index,
        severity,
        rule: LayoutRule::InvisibleNode,
    }
}

fn node(id: &str, parent_index: Option<usize>, depth: usize, visible: bool) -> LayoutNode {
    LayoutNode {
        id: id.into(),
        kind: NodeKind::View,
        class_name: format!("example.{id}"),
        parent_index,
        depth,
        bounds: BOUNDS,
        visible,
        alpha: 1.0,
        resource_name: None,
        text: None,
        semantics_role: None,
        attributes: Default::default(),
    }
}

fn assert_same_rows(left: &Snapshot, right: &Snapshot) {
    assert_eq!(left.rows.len(), right.rows.len());
    for (left, right) in left.rows.iter().zip(&right.rows) {
        assert_eq!(left.index, right.index);
        assert_eq!(left.severity, right.severity);
        assert_eq!(left.problem, right.problem);
        assert_eq!(left.description, right.description);
        assert_eq!(left.node_label, right.node_label);
    }
}

#[::core::prelude::v1::test]
fn warm_prepare_shares_snapshot_preserves_anchor_and_full_text() {
    let (archive, report) = large_fixture();
    let window = &archive.windows[0];
    let mut state = UiState::default();
    let first = state.prepare(
        Some(&archive),
        0,
        &report,
        Language::English,
        false,
        FindingsTone::All,
    );
    assert!(Arc::ptr_eq(&first.report, &report));
    assert!(Arc::ptr_eq(first.archive.as_ref().unwrap(), &archive));
    assert_eq!(first.rows.len(), FINDING_COUNT);
    assert_eq!(first.count(FindingsTone::Info), 167);
    assert_eq!(first.count(FindingsTone::Warning), 167);
    assert_eq!(first.count(FindingsTone::Error), 166);

    let expected_row = &first.rows[12];
    let (problem, description) = format_finding(
        Language::English,
        report.findings[12].rule,
        &window.nodes[12],
    );
    assert_eq!(expected_row.index, 12);
    assert_eq!(expected_row.node_label, "1-11");
    assert_eq!(expected_row.problem.as_ref(), problem.as_str());
    assert_eq!(expected_row.description.as_ref(), description.as_str());

    let numbers = hierarchy_numbers(&window.nodes);
    for row in &first.rows {
        let (problem, description) = format_finding(
            Language::English,
            report.findings[row.index].rule,
            &window.nodes[row.index],
        );
        assert_eq!(row.problem.as_ref(), problem.as_str());
        assert_eq!(row.description.as_ref(), description.as_str());
        assert_eq!(row.node_label.as_ref(), numbers[row.index].as_str());
    }

    let anchor = ListOffset {
        item_ix: 321,
        offset_in_item: px(7.),
    };
    state.scroll.scroll_to(anchor);
    assert_eq!(state.scroll.logical_scroll_top(), anchor);
    for _ in 0..16 {
        let warm = state.prepare(
            Some(&archive),
            0,
            &report,
            Language::English,
            false,
            FindingsTone::All,
        );
        assert!(Arc::ptr_eq(&first, &warm));
        assert_eq!(state.scroll.logical_scroll_top(), anchor);
        assert_same_rows(&first, &warm);
    }
}

#[::core::prelude::v1::test]
fn tone_switch_reuses_snapshot_but_resets_count_and_scroll_top() {
    let (archive, report) = large_fixture();
    let mut state = UiState::default();
    let all = state.prepare(
        Some(&archive),
        0,
        &report,
        Language::English,
        false,
        FindingsTone::All,
    );
    state.scroll.scroll_to(ListOffset {
        item_ix: 321,
        offset_in_item: px(7.),
    });

    let info = state.prepare(
        Some(&archive),
        0,
        &report,
        Language::English,
        false,
        FindingsTone::Info,
    );
    assert!(Arc::ptr_eq(&all, &info));
    assert_eq!(info.count(FindingsTone::Info), 167);
    assert_eq!(info.indices(FindingsTone::Info).len(), 167);
    assert_eq!(state.scroll.item_count(), 167);
    assert_eq!(state.scroll.logical_scroll_top(), ListOffset::default());
}

#[::core::prelude::v1::test]
fn invisible_parent_and_missing_node_are_excluded_without_changing_report_indices() {
    let archive = archive(vec![LayoutWindow {
        id: "window".into(),
        title: None,
        kind: "ACTIVITY".into(),
        bounds: BOUNDS,
        nodes: vec![
            node("root", None, 0, false),
            node("child", Some(0), 1, true),
        ],
    }]);
    let report = report_for(
        2,
        vec![
            finding(1, FindingSeverity::Info),
            finding(usize::MAX, FindingSeverity::Warning),
        ],
    );
    let mut state = UiState::default();

    let visible = state.prepare(
        Some(&archive),
        0,
        &report,
        Language::English,
        false,
        FindingsTone::All,
    );
    assert_eq!(visible.rows.len(), 1);
    assert_eq!(visible.rows[0].index, 0);
    assert_eq!(visible.rows[0].node_label, "1-0");

    let hidden = state.prepare(
        Some(&archive),
        0,
        &report,
        Language::English,
        true,
        FindingsTone::All,
    );
    assert!(hidden.rows.is_empty());
    assert_eq!(hidden.empty, TextKey::InspectorFindingsFiltered);

    let mut bounded_report = (*report).clone();
    bounded_report.omitted_findings = 2;
    bounded_report.overlap_parents_skipped = 3;
    let bounded_report = Arc::new(bounded_report);
    let bounded = state.prepare(
        Some(&archive),
        0,
        &bounded_report,
        Language::English,
        false,
        FindingsTone::All,
    );
    assert!(
        bounded
            .bounded
            .as_deref()
            .is_some_and(|text| text.contains("2") && text.contains("3"))
    );
    assert_eq!(state.scroll.item_count(), FINDING_COUNT + 1);
}

#[::core::prelude::v1::test]
fn same_count_report_language_window_and_archive_replacements_rebuild_snapshot() {
    let (archive, report) = large_fixture();

    let mut warm_state = UiState::default();
    let first = warm_state.prepare(
        Some(&archive),
        0,
        &report,
        Language::English,
        false,
        FindingsTone::All,
    );
    let warm = warm_state.prepare(
        Some(&archive),
        0,
        &report,
        Language::English,
        false,
        FindingsTone::All,
    );
    assert!(Arc::ptr_eq(&first, &warm));

    let mut changed_report = (*report).clone();
    changed_report.findings[12].rule = LayoutRule::DeepHierarchy {
        depth: 12,
        threshold: 10,
    };
    let changed_report = Arc::new(changed_report);
    let rebuilt_report = warm_state.prepare(
        Some(&archive),
        0,
        &changed_report,
        Language::English,
        false,
        FindingsTone::All,
    );
    assert!(!Arc::ptr_eq(&warm, &rebuilt_report));
    assert_eq!(rebuilt_report.rows.len(), first.rows.len());

    let rebuilt_language = warm_state.prepare(
        Some(&archive),
        0,
        &changed_report,
        Language::SimplifiedChinese,
        false,
        FindingsTone::All,
    );
    assert!(!Arc::ptr_eq(&rebuilt_report, &rebuilt_language));
    assert_eq!(rebuilt_language.rows.len(), rebuilt_report.rows.len());

    let mut second_window = archive.windows[0].clone();
    second_window.id = "window-1".into();
    second_window.nodes[12].class_name = "example.SecondWindowWidget12".into();
    let two_windows = archive(vec![archive.windows[0].clone(), second_window]);
    let first_window = warm_state.prepare(
        Some(&two_windows),
        0,
        &changed_report,
        Language::English,
        false,
        FindingsTone::All,
    );
    let second_window_snapshot = warm_state.prepare(
        Some(&two_windows),
        1,
        &changed_report,
        Language::English,
        false,
        FindingsTone::All,
    );
    assert!(!Arc::ptr_eq(&first_window, &second_window_snapshot));
    assert_eq!(
        second_window_snapshot.rows[12].description.as_ref(),
        format_finding(
            Language::English,
            changed_report.findings[12].rule,
            &two_windows.windows[1].nodes[12],
        )
        .1
    );

    let archive_replacement = Arc::new((*two_windows).clone());
    let replaced_archive = warm_state.prepare(
        Some(&archive_replacement),
        1,
        &changed_report,
        Language::English,
        false,
        FindingsTone::All,
    );
    assert!(!Arc::ptr_eq(&second_window_snapshot, &replaced_archive));
    assert_eq!(
        replaced_archive.rows.len(),
        second_window_snapshot.rows.len()
    );
}

#[::core::prelude::v1::test]
fn findings_renderer_keeps_lazy_variable_height_contract() {
    let source = include_str!("../../findings.rs");
    let state_source = include_str!("../state.rs");
    let body = source
        .split_once("pub(super) fn body(")
        .and_then(|(_, rest)| rest.split_once("fn empty_key("))
        .map(|(body, _)| body)
        .expect("findings body source");
    let row = source
        .split_once("fn finding_row(")
        .and_then(|(_, rest)| rest.split_once("impl InspectorWorkspace"))
        .map(|(row, _)| row)
        .expect("finding row source");

    assert!(body.contains("let rows = list(scroll.clone(), move |slot"));
    assert!(!body.contains("uniform_list("));
    assert!(!body.contains("for projected in"));
    assert!(!body.contains("collect::<Vec<_>>()"));
    assert!(row.contains(".min_h(px(ROW_MIN_HEIGHT))"));
    assert!(row.contains(".line_height(px(ROW_LINE_HEIGHT))"));
    assert!(!row.contains("truncate"));
    assert!(!row.contains("ellipsis"));
    assert!(source.contains("const ROW_MIN_HEIGHT: f32 = 32.0;"));
    assert!(source.contains("const ROW_LINE_HEIGHT: f32 = 16.0;"));
    assert!(state_source.contains("list_state.reset_with_uniform_height"));
}

#[::core::prelude::v1::test]
#[ignore]
fn benchmark_findings_cpu_preparation_without_time_assertions() {
    let (archive, report) = large_fixture();
    let window = &archive.windows[0];
    let mut checksum = 0usize;

    let cold_start = Instant::now();
    for _ in 0..4096 {
        let projected = project(&report, Some(window), false);
        let numbers = hierarchy_numbers(&window.nodes);
        for projected in projected {
            let node = &window.nodes[projected.finding.node_index];
            let (problem, description) =
                format_finding(Language::English, projected.finding.rule, node);
            checksum = checksum
                .wrapping_add(projected.index)
                .wrapping_add(problem.len())
                .wrapping_add(description.len())
                .wrapping_add(numbers[projected.finding.node_index].len());
        }
    }
    let cold_elapsed = cold_start.elapsed();

    let mut state = UiState::default();
    black_box(state.prepare(
        Some(&archive),
        0,
        &report,
        Language::English,
        false,
        FindingsTone::All,
    ));
    let warm_start = Instant::now();
    for _ in 0..4096 {
        let snapshot = state.prepare(
            Some(&archive),
            0,
            &report,
            Language::English,
            false,
            FindingsTone::All,
        );
        checksum = checksum.wrapping_add(black_box(snapshot.rows.len()));
    }
    let warm_elapsed = warm_start.elapsed();

    println!(
        "findings CPU preparation only: prior_project={cold_elapsed:?}, warm_prepare={warm_elapsed:?}, checksum={checksum}"
    );
}

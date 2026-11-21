use super::*;
use aps_layout_inspector::{FindingSeverity, LayoutRule};
use gpui_kit::component::scroll::{Scrollbar, ScrollbarMode};

mod state;
pub(super) use state::{Snapshot, UiState};

pub(super) const PROBLEM_COLUMN_WIDTH: f32 = 180.0;
pub(super) const SCROLLBAR_GUTTER: f32 = 16.0;
const ROW_MIN_HEIGHT: f32 = 32.0;
const ROW_LINE_HEIGHT: f32 = 16.0;

#[cfg(test)]
mod behavior_tests;

#[derive(Clone, Copy)]
pub(super) struct ProjectedFinding {
    pub(super) index: usize,
    pub(super) finding: aps_layout_inspector::LayoutFinding,
}

pub(super) fn project(
    report: &LayoutAnalysis,
    window: Option<&LayoutWindow>,
    hide_invisible: bool,
) -> Vec<ProjectedFinding> {
    report
        .findings
        .iter()
        .copied()
        .enumerate()
        .filter(|(_, finding)| {
            window.is_some_and(|window| {
                window.nodes.get(finding.node_index).is_some_and(|node| {
                    !hide_invisible
                        || (node.visible
                            && node.alpha > 0.0
                            && !has_invisible_ancestor(window, finding.node_index))
                })
            })
        })
        .map(|(index, finding)| ProjectedFinding { index, finding })
        .collect()
}

pub(super) fn body(
    language: Language,
    colors: &StudioColors,
    snapshot: Arc<Snapshot>,
    scroll: ListState,
    tone: FindingsTone,
    selected_finding: Option<usize>,
    cx: &mut Context<InspectorWorkspace>,
) -> AnyElement {
    let owner = cx.entity();
    let palette = *colors;
    let rows = list(scroll.clone(), move |slot, _, _| {
        let indices = snapshot.indices(tone);
        if let Some(&index) = indices.get(slot) {
            return finding_row(
                &snapshot.rows[index],
                language,
                &palette,
                selected_finding,
                owner.clone(),
            );
        }
        let message: SharedString = if slot == 0 && indices.is_empty() {
            language.text(snapshot.empty).into()
        } else {
            snapshot.bounded.clone().unwrap_or_default()
        };
        div()
            .w_full()
            .px(px(16.))
            .pr(px(16. + SCROLLBAR_GUTTER))
            .py(px(6.))
            .min_h(px(ROW_MIN_HEIGHT))
            .text_size(px(studio::FONT_SUPPORTING))
            .line_height(px(ROW_LINE_HEIGHT))
            .text_color(palette.muted_text)
            .child(studio::selectable(message))
            .into_any_element()
    })
    .h_full()
    .w_full();
    let body = div()
        .relative()
        .flex_1()
        .min_h_0()
        .w_full()
        .overflow_hidden()
        .child(rows)
        .child(
            div().absolute().inset_0().child(
                Scrollbar::vertical(&scroll)
                    .id("inspector-findings-scrollbar")
                    .mode(ScrollbarMode::Always)
                    .viewport_from_layout(),
            ),
        );
    div()
        .flex_1()
        .min_h_0()
        .flex()
        .flex_col()
        .child(body)
        .child(
            div()
                .flex_shrink_0()
                .px(px(16.))
                .py(px(4.))
                .text_size(px(studio::FONT_SUPPORTING))
                .text_color(colors.muted_text)
                .child(crate::studio::selectable(
                    language.text(TextKey::InspectorFindingsScope),
                )),
        )
        .into_any_element()
}

fn finding_row(
    row: &state::PreparedFinding,
    language: Language,
    colors: &StudioColors,
    selected_finding: Option<usize>,
    owner: Entity<InspectorWorkspace>,
) -> AnyElement {
    let index = row.index;
    div()
        .id(("inspector-finding", index))
        .w_full()
        .min_h(px(ROW_MIN_HEIGHT))
        .flex()
        .items_start()
        .gap(px(8.))
        .px(px(16.))
        .pr(px(16. + SCROLLBAR_GUTTER))
        .py(px(6.))
        .text_size(px(studio::FONT_SUPPORTING))
        .line_height(px(ROW_LINE_HEIGHT))
        .bg(if selected_finding == Some(index) {
            colors.selected_row
        } else {
            colors.panel
        })
        .border_b_1()
        .border_color(colors.border)
        .cursor_pointer()
        .on_click(move |_, window, cx| {
            owner.update(cx, |this, cx| this.select_finding(index, window, cx));
        })
        .child(
            div()
                .w(px(76.))
                .flex_shrink_0()
                .font_weight(FontWeight::BOLD)
                .text_color(severity_color(row.severity, colors))
                .child(studio::selectable(severity_label(language, row.severity))),
        )
        .child(
            div()
                .id(("inspector-finding-problem", index))
                .w(px(PROBLEM_COLUMN_WIDTH))
                .flex_shrink_0()
                .child(studio::selectable(row.problem.clone())),
        )
        .child(
            div()
                .id(("inspector-finding-description", index))
                .flex_1()
                .min_w_0()
                .text_color(colors.secondary_text)
                .child(studio::selectable(row.description.clone())),
        )
        .child(
            div()
                .w(px(96.))
                .flex_shrink_0()
                .text_color(colors.muted_text)
                .child(studio::selectable(row.node_label.clone())),
        )
        .into_any_element()
}
fn empty_key(report: &LayoutAnalysis, has_window: bool) -> TextKey {
    if !report.hierarchy_available || !has_window {
        TextKey::InspectorFindingsNoHierarchy
    } else if !report.findings.is_empty() {
        TextKey::InspectorFindingsFiltered
    } else if report.omitted_findings > 0 || report.overlap_parents_skipped > 0 {
        TextKey::InspectorFindingsPartialEmpty
    } else {
        TextKey::InspectorFindingsEmpty
    }
}

fn tone_matches(severity: FindingSeverity, tone: FindingsTone) -> bool {
    matches!(
        (severity, tone),
        (_, FindingsTone::All)
            | (FindingSeverity::Info, FindingsTone::Info)
            | (FindingSeverity::Warning, FindingsTone::Warning)
            | (FindingSeverity::Error, FindingsTone::Error)
    )
}

fn severity_color(severity: FindingSeverity, colors: &StudioColors) -> Rgba {
    match severity {
        FindingSeverity::Info => colors.info,
        FindingSeverity::Warning => colors.warning,
        FindingSeverity::Error => colors.error,
    }
}

fn severity_label(language: Language, severity: FindingSeverity) -> &'static str {
    language.text(match severity {
        FindingSeverity::Info => TextKey::InspectorFindingSeverityInfo,
        FindingSeverity::Warning => TextKey::InspectorFindingSeverityWarning,
        FindingSeverity::Error => TextKey::InspectorFindingSeverityError,
    })
}

fn format_finding(language: Language, rule: LayoutRule, node: &LayoutNode) -> (String, String) {
    match rule {
        LayoutRule::InvisibleNode => (
            language.text(TextKey::InspectorFindingInvisibleNode).into(),
            language.format(
                TextKey::InspectorFindingInvisibleNodeMessage,
                &[("class_name", &node.class_name)],
            ),
        ),
        LayoutRule::ExcessiveChildren { count, threshold } => (
            language
                .text(TextKey::InspectorFindingExcessiveChildren)
                .into(),
            language.format(
                TextKey::InspectorFindingExcessiveChildrenMessage,
                &[("count", &count), ("threshold", &threshold)],
            ),
        ),
        LayoutRule::OverlappingSiblings {
            count,
            ratio_percent,
        } => (
            language
                .text(TextKey::InspectorFindingOverlappingSiblings)
                .into(),
            language.format(
                TextKey::InspectorFindingOverlappingSiblingsMessage,
                &[("count", &count), ("ratio_percent", &ratio_percent)],
            ),
        ),
        LayoutRule::DeepHierarchy { depth, threshold } => (
            language.text(TextKey::InspectorFindingDeepHierarchy).into(),
            language.format(
                TextKey::InspectorFindingDeepHierarchyMessage,
                &[("depth", &depth), ("threshold", &threshold)],
            ),
        ),
    }
}

impl InspectorWorkspace {
    fn select_finding(&mut self, index: usize, window: &mut Window, cx: &mut Context<Self>) {
        let Some(finding) = self.findings.findings.get(index) else {
            return;
        };
        let Some(window_data) = self
            .archive
            .as_ref()
            .and_then(|archive| archive.windows.get(self.window))
        else {
            return;
        };
        let Some(node) = window_data.nodes.get(finding.node_index) else {
            return;
        };
        self.selected = Some(node.id.clone());
        self.selected_finding = Some(index);
        self.hovered = None;
        self.reveal_selected_node(window, cx);
        cx.notify();
    }
}

fn has_invisible_ancestor(window: &LayoutWindow, index: usize) -> bool {
    let mut parent = window.nodes[index].parent_index;
    while let Some(parent_index) = parent {
        let node = &window.nodes[parent_index];
        if !node.visible || node.alpha <= 0.0 {
            return true;
        }
        parent = node.parent_index;
    }
    false
}

#[cfg(test)]
mod tests {
    use super::*;

    #[::core::prelude::v1::test]
    fn findings_problem_and_description_have_distinct_localized_headers() {
        for (language, problem, description) in [
            (Language::English, "Problem", "Description"),
            (
                Language::SimplifiedChinese,
                "\u{95ee}\u{9898}",
                "\u{63cf}\u{8ff0}",
            ),
        ] {
            assert_eq!(language.text(TextKey::InspectorFindingsProblem), problem);
            assert_eq!(
                language.text(TextKey::InspectorFindingsDescription),
                description
            );
        }
    }

    #[::core::prelude::v1::test]
    fn findings_header_and_rows_keep_problem_and_description_in_separate_columns() {
        let source = include_str!("../inspector.rs");
        let header = source
            .split_once("fn findings_table_header(")
            .unwrap()
            .1
            .split_once("fn findings_pane(")
            .unwrap()
            .0;
        let columns = [
            "TextKey::InspectorFindingsSeverity",
            "TextKey::InspectorFindingsProblem",
            "TextKey::InspectorFindingsDescription",
            "TextKey::InspectorFindingsNode",
        ];
        let positions: Vec<_> = columns
            .iter()
            .map(|key| header.find(key).unwrap())
            .collect();
        assert!(positions.windows(2).all(|pair| pair[0] < pair[1]));
        assert!(header.contains(".w(px(findings::PROBLEM_COLUMN_WIDTH))"));

        let source = include_str!("findings.rs");
        let body = source.split_once("fn empty_key(").unwrap().0;
        let problem = body
            .split_once(".id((\"inspector-finding-problem\"")
            .unwrap()
            .1;
        let (problem, description) = problem
            .split_once(".id((\"inspector-finding-description\"")
            .unwrap();
        assert!(problem.contains(".w(px(PROBLEM_COLUMN_WIDTH))"));
        assert!(
            problem.contains("studio::selectable(row.problem.clone())")
                || problem.contains("studio::selectable(title)")
        );
        assert!(!problem.contains("studio::selectable(details)"));
        assert!(description.contains(".flex_1()"));
        assert!(description.contains(".min_w_0()"));
        assert!(description.contains("studio::selectable(row.description.clone())"));
        assert!(body.contains("this.select_finding(index, window, cx)"));
    }
}

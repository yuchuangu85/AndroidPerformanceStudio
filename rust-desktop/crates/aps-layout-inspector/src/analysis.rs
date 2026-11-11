//! Offline rules from shared-kernel/analysis-engine/{LayoutAnalyzer,AnalysisModels}.kt.
//! Results describe captured nodes only, not GPU cost or runtime recomposition.

use crate::{Bounds, InspectorArchive, InspectorError, LayoutWindow};
use std::collections::VecDeque;

pub const MAX_LAYOUT_FINDINGS: usize = 500;
pub const MAX_SIBLING_PAIR_CHECKS: usize = 250_000;
const MAX_DEPTH: usize = 10;
const MAX_CHILDREN: usize = 10;
const MIN_OVERLAPPING_SIBLINGS: usize = 3;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum FindingSeverity {
    Info,
    Warning,
    Error,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum LayoutRule {
    InvisibleNode,
    ExcessiveChildren { count: usize, threshold: usize },
    OverlappingSiblings { count: usize, ratio_percent: usize },
    DeepHierarchy { depth: usize, threshold: usize },
}

impl LayoutRule {
    pub fn id(self) -> &'static str {
        match self {
            Self::InvisibleNode => "layout.invisible-node",
            Self::ExcessiveChildren { .. } => "layout.excessive-children",
            Self::OverlappingSiblings { .. } => "layout.overlapping-siblings",
            Self::DeepHierarchy { .. } => "layout.deep-hierarchy",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct LayoutFinding {
    /// Index into the immutable window analyzed by `analyze_window`.
    pub node_index: usize,
    pub severity: FindingSeverity,
    pub rule: LayoutRule,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct LayoutAnalysis {
    pub hierarchy_available: bool,
    pub node_count: usize,
    /// Like Compose, the root counts as depth 1 (LayoutNode stores depth 0).
    pub max_depth: usize,
    pub widest_level: usize,
    pub findings: Vec<LayoutFinding>,
    pub omitted_findings: usize,
    /// No overlap finding is emitted for a parent whose pair scan was skipped.
    pub overlap_parents_skipped: usize,
}

impl LayoutAnalysis {
    fn push(&mut self, node_index: usize, severity: FindingSeverity, rule: LayoutRule) {
        if self.findings.len() < MAX_LAYOUT_FINDINGS {
            self.findings.push(LayoutFinding {
                node_index,
                severity,
                rule,
            });
        } else {
            self.omitted_findings += 1;
        }
    }
}

impl InspectorArchive {
    /// Runs only the four structural Compose rules against one captured window.
    /// Screenshots and Compose detail/completeness declarations are not inputs.
    pub fn analyze_window(&self, window_id: &str) -> Result<LayoutAnalysis, InspectorError> {
        let window = self
            .windows
            .iter()
            .find(|window| window.id == window_id)
            .ok_or(InspectorError::WindowMissing)?;
        if !self.capabilities.view_hierarchy || window.nodes.is_empty() {
            return Ok(LayoutAnalysis::default());
        }
        analyze(window)
    }
}

fn analyze(window: &LayoutWindow) -> Result<LayoutAnalysis, InspectorError> {
    let nodes = &window.nodes;
    let mut children = vec![Vec::new(); nodes.len()];
    let mut roots = Vec::new();
    let mut root_for_node = vec![0; nodes.len()];
    let mut root_depth = vec![0; nodes.len()];
    let mut nodes_per_depth = vec![0; nodes.len()];
    for (index, node) in nodes.iter().enumerate() {
        match node.parent_index {
            Some(parent) if parent < index && nodes[parent].depth + 1 == node.depth => {
                children[parent].push(index);
                root_for_node[index] = root_for_node[parent];
            }
            None if node.depth == 0 => {
                roots.push(index);
                root_for_node[index] = index;
            }
            _ => return Err(InspectorError::InvalidSnapshot),
        }
        if node.depth >= nodes.len() {
            return Err(InspectorError::InvalidSnapshot);
        }
        nodes_per_depth[node.depth] += 1;
        let depth = &mut root_depth[root_for_node[index]];
        *depth = (*depth).max(node.depth + 1);
    }
    let mut report = LayoutAnalysis {
        hierarchy_available: true,
        node_count: nodes.len(),
        max_depth: root_depth.iter().copied().max().unwrap_or(0),
        widest_level: nodes_per_depth.into_iter().max().unwrap_or(0),
        ..LayoutAnalysis::default()
    };
    // Breadth-first ordering matches LayoutAnalyzer, independently of storage order.
    let mut pending: VecDeque<_> = roots.iter().copied().collect();
    let mut pairs_remaining = MAX_SIBLING_PAIR_CHECKS;
    while let Some(index) = pending.pop_front() {
        let node = &nodes[index];
        if !node.visible || node.alpha <= 0.0 {
            report.push(index, FindingSeverity::Info, LayoutRule::InvisibleNode);
        }
        let siblings = &children[index];
        if siblings.len() > MAX_CHILDREN {
            report.push(
                index,
                FindingSeverity::Warning,
                LayoutRule::ExcessiveChildren {
                    count: siblings.len(),
                    threshold: MAX_CHILDREN,
                },
            );
        }
        if siblings.len() >= MIN_OVERLAPPING_SIBLINGS {
            let pairs = siblings.len().saturating_mul(siblings.len() - 1) / 2;
            if pairs > pairs_remaining {
                report.overlap_parents_skipped += 1;
            } else {
                pairs_remaining -= pairs;
                let mut overlapping = vec![false; siblings.len()];
                for first in 0..siblings.len() {
                    for second in first + 1..siblings.len() {
                        if substantially_overlaps(
                            nodes[siblings[first]].bounds,
                            nodes[siblings[second]].bounds,
                        ) {
                            overlapping[first] = true;
                            overlapping[second] = true;
                        }
                    }
                }
                let count = overlapping.into_iter().filter(|overlaps| *overlaps).count();
                if count >= MIN_OVERLAPPING_SIBLINGS {
                    report.push(
                        index,
                        FindingSeverity::Warning,
                        LayoutRule::OverlappingSiblings {
                            count,
                            ratio_percent: 80,
                        },
                    );
                }
            }
        }
        pending.extend(siblings.iter().copied());
    }
    for root in roots {
        if root_depth[root] > MAX_DEPTH {
            report.push(
                root,
                FindingSeverity::Warning,
                LayoutRule::DeepHierarchy {
                    depth: root_depth[root],
                    threshold: MAX_DEPTH,
                },
            );
        }
    }
    Ok(report)
}

fn substantially_overlaps(first: Bounds, second: Bounds) -> bool {
    // Widen before subtraction/multiplication: valid coordinates can span i32.
    let area = |bounds: Bounds| {
        (i64::from(bounds.right) - i64::from(bounds.left)).max(0) as u128
            * (i64::from(bounds.bottom) - i64::from(bounds.top)).max(0) as u128
    };
    let smaller_area = area(first).min(area(second));
    let width = (i64::from(first.right.min(second.right)) - i64::from(first.left.max(second.left)))
        .max(0) as u128;
    let height = (i64::from(first.bottom.min(second.bottom)) - i64::from(first.top.max(second.top)))
        .max(0) as u128;
    smaller_area != 0 && width * height * 100 >= smaller_area * 80
}

#[cfg(test)]
mod tests;

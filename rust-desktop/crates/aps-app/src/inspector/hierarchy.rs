use super::{hierarchy_content_width, hierarchy_display_depths, hierarchy_numbers, row_matches};
use aps_layout_inspector::{InspectorArchive, LayoutNode};
use aps_ui_foundation::LayoutPreferences;
use std::{collections::BTreeSet, sync::Arc};

/// Presentation-only hierarchy root. The captured archive remains unchanged;
/// this state limits the tree and canvas to one contiguous preorder subtree.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub(super) struct IsolationState {
    pub(super) root_id: Option<String>,
}

impl IsolationState {
    pub(super) fn active(&self) -> bool {
        self.root_id.is_some()
    }

    pub(super) fn isolate(&self, node_id: Option<&str>, nodes: &[LayoutNode]) -> Self {
        Self {
            root_id: node_id
                .filter(|id| nodes.iter().any(|node| node.id == *id))
                .map(str::to_owned),
        }
    }

    pub(super) fn parent(&self, nodes: &[LayoutNode]) -> Self {
        let Some(index) = self.root_index(nodes) else {
            return Self::default();
        };
        Self {
            root_id: nodes
                .get(index)
                .and_then(|node| node.parent_index)
                .and_then(|parent| nodes.get(parent))
                .map(|node| node.id.clone()),
        }
    }

    pub(super) fn sanitize(&self, nodes: &[LayoutNode]) -> Self {
        self.isolate(self.root_id.as_deref(), nodes)
    }

    pub(super) fn clear(&self) -> Self {
        Self::default()
    }

    pub(super) fn root_index(&self, nodes: &[LayoutNode]) -> Option<usize> {
        self.root_id
            .as_deref()
            .and_then(|id| nodes.iter().position(|node| node.id == id))
    }

    pub(super) fn range(&self, nodes: &[LayoutNode]) -> Option<(usize, usize, usize)> {
        let start = self.root_index(nodes)?;
        let depth = nodes[start].depth;
        let end = nodes
            .iter()
            .enumerate()
            .skip(start + 1)
            .find(|(_, node)| node.depth <= depth)
            .map_or(nodes.len(), |(index, _)| index);
        Some((start, end, depth))
    }
}

pub(super) fn reveal_ancestors(
    nodes: &[LayoutNode],
    mut index: usize,
    collapsed: &mut BTreeSet<String>,
) -> bool {
    let mut changed = false;
    while let Some(parent) = nodes.get(index).and_then(|node| node.parent_index) {
        let Some(node) = nodes.get(parent).filter(|_| parent < index) else {
            break;
        };
        changed |= collapsed.remove(&node.id);
        index = parent;
    }
    changed
}

#[derive(Default)]
pub(super) struct HierarchyCache {
    snapshot: Option<Arc<HierarchySnapshot>>,
}

pub(super) struct HierarchySnapshot {
    pub(super) archive: Arc<InspectorArchive>,
    pub(super) window_index: usize,
    pub(super) rows: Arc<Vec<usize>>,
    pub(super) numbers: Vec<String>,
    pub(super) content_width: f32,
    pub(super) display_depths: Vec<usize>,
    pub(super) visible_nodes: usize,
    isolation_root: Option<String>,
    preferences: (bool, bool, bool, bool),
    show_system_composables: bool,
    query: String,
}

impl HierarchyCache {
    pub(super) fn invalidate(&mut self) {
        self.snapshot = None;
    }

    pub(super) fn get(
        &mut self,
        archive: &Arc<InspectorArchive>,
        window_index: usize,
        preferences: LayoutPreferences,
        show_system_composables: bool,
        query: &str,
        collapsed: &BTreeSet<String>,
    ) -> Option<Arc<HierarchySnapshot>> {
        self.get_with_isolation(
            archive,
            window_index,
            preferences,
            show_system_composables,
            query,
            collapsed,
            None,
        )
    }

    pub(super) fn get_with_isolation(
        &mut self,
        archive: &Arc<InspectorArchive>,
        window_index: usize,
        preferences: LayoutPreferences,
        show_system_composables: bool,
        query: &str,
        collapsed: &BTreeSet<String>,
        isolation_root: Option<&str>,
    ) -> Option<Arc<HierarchySnapshot>> {
        let query = query.trim();
        let isolation_root = isolation_root.map(str::to_owned);
        let preference_key = (
            preferences.hide_invisible_hierarchy,
            preferences.hide_indices,
            preferences.show_ids,
            preferences.show_layer_buttons,
        );
        if let Some(snapshot) = &self.snapshot
            && Arc::ptr_eq(&snapshot.archive, archive)
            && snapshot.window_index == window_index
            && snapshot.preferences == preference_key
            && snapshot.show_system_composables == show_system_composables
            && snapshot.query == query
            && snapshot.isolation_root == isolation_root
        {
            return Some(snapshot.clone());
        }
        let window = archive.windows.get(window_index)?;
        let mut rows = Vec::with_capacity(window.nodes.len());
        let display_depths = hierarchy_display_depths(
            &window.nodes,
            archive.compose.as_ref(),
            show_system_composables,
        );
        let isolation = IsolationState {
            root_id: isolation_root.clone(),
        };
        let (start_index, end_index, _) = isolation
            .range(&window.nodes)
            .map_or((0, window.nodes.len(), 0), |(start, end, depth)| {
                (start, end, depth)
            });
        let isolation_base_depth = isolation
            .range(&window.nodes)
            .map(|(start, _, _)| display_depths[start]);
        let mut display_depths = display_depths;
        if let Some(base_depth) = isolation_base_depth {
            for depth in &mut display_depths[start_index..end_index] {
                *depth = depth.saturating_sub(base_depth);
            }
        }
        let mut skip_depth = None;
        for (index, node) in window
            .nodes
            .iter()
            .enumerate()
            .skip(start_index)
            .take(end_index.saturating_sub(start_index))
        {
            let hidden_system = super::hidden_system_composable(
                node,
                archive.compose.as_ref(),
                show_system_composables,
            );
            if let Some(depth) = skip_depth {
                if node.depth > depth {
                    continue;
                }
                skip_depth = None;
            }
            if preferences.hide_invisible_hierarchy && !node.visible {
                skip_depth = Some(node.depth);
                continue;
            }
            if hidden_system {
                continue;
            }
            if collapsed.contains(&node.id)
                && window
                    .nodes
                    .get(index + 1)
                    .is_some_and(|next| next.parent_index == Some(index) && node.depth < next.depth)
            {
                skip_depth = Some(node.depth);
            }
            if !query.is_empty() && !row_matches(node, query) {
                continue;
            }
            rows.push(index);
        }
        // Keep the immutable archive shared with the virtual list; rebuilding
        // visible rows must not clone node properties or repeat on scroll.
        let content_width =
            hierarchy_content_width(&window.nodes, &rows, &display_depths, preferences);
        let snapshot = Arc::new(HierarchySnapshot {
            archive: archive.clone(),
            window_index,
            rows: Arc::new(rows),
            numbers: hierarchy_numbers(&window.nodes),
            content_width,
            display_depths,
            visible_nodes: window.nodes[start_index..end_index]
                .iter()
                .filter(|node| node.visible)
                .count(),
            isolation_root,
            preferences: preference_key,
            show_system_composables,
            query: query.to_owned(),
        });
        self.snapshot = Some(snapshot.clone());
        Some(snapshot)
    }
}

#[derive(Default)]
pub(super) struct PreviewCache {
    snapshot: Option<PreviewSnapshot>,
}

struct PreviewSnapshot {
    archive: Arc<InspectorArchive>,
    window_index: usize,
    hidden: BTreeSet<String>,
    selected: Option<String>,
    isolation_root: Option<String>,
    show_system_composables: bool,
    nodes: Arc<Vec<(usize, LayoutNode)>>,
}

impl PreviewCache {
    #[cfg(test)]
    pub(super) fn get(
        &mut self,
        archive: &Arc<InspectorArchive>,
        window_index: usize,
        hidden: &BTreeSet<String>,
        selected: &Option<String>,
    ) -> Option<Arc<Vec<(usize, LayoutNode)>>> {
        self.get_with_system_composables(archive, window_index, hidden, selected, true)
    }

    pub(super) fn get_with_system_composables(
        &mut self,
        archive: &Arc<InspectorArchive>,
        window_index: usize,
        hidden: &BTreeSet<String>,
        selected: &Option<String>,
        show_system_composables: bool,
    ) -> Option<Arc<Vec<(usize, LayoutNode)>>> {
        self.get_with_isolation(
            archive,
            window_index,
            hidden,
            selected,
            show_system_composables,
            None,
        )
    }

    pub(super) fn get_with_isolation(
        &mut self,
        archive: &Arc<InspectorArchive>,
        window_index: usize,
        hidden: &BTreeSet<String>,
        selected: &Option<String>,
        show_system_composables: bool,
        isolation_root: Option<&str>,
    ) -> Option<Arc<Vec<(usize, LayoutNode)>>> {
        let isolation_root = isolation_root.map(str::to_owned);
        if let Some(snapshot) = &self.snapshot
            && Arc::ptr_eq(&snapshot.archive, archive)
            && snapshot.window_index == window_index
            && snapshot.hidden == *hidden
            && snapshot.selected == *selected
            && snapshot.isolation_root == isolation_root
            && snapshot.show_system_composables == show_system_composables
        {
            return Some(snapshot.nodes.clone());
        }
        let window = archive.windows.get(window_index)?;
        let (nodes, _) = super::canvas_nodes_with_isolation(
            &window.nodes,
            hidden,
            selected.as_deref(),
            archive.display.width_px,
            archive.display.height_px,
            archive.compose.as_ref(),
            show_system_composables,
            isolation_root.as_deref(),
        );
        let nodes = Arc::new(nodes);
        self.snapshot = Some(PreviewSnapshot {
            archive: archive.clone(),
            window_index,
            hidden: hidden.clone(),
            selected: selected.clone(),
            isolation_root,
            show_system_composables,
            nodes: nodes.clone(),
        });
        Some(nodes)
    }
}

pub(super) fn reveal_horizontal_offset(
    left: f32,
    right: f32,
    viewport_width: f32,
    content_width: f32,
    current_x: f32,
) -> f32 {
    if viewport_width <= 0.0 || !left.is_finite() || !right.is_finite() {
        return current_x;
    }
    let max_x = -(content_width - viewport_width).max(0.0);
    let label_width = (right - left).max(0.0);
    let current_left = left + current_x;
    let current_right = right + current_x;
    let next = if label_width > viewport_width {
        if current_left < 0.0 || current_left >= viewport_width {
            -left
        } else {
            current_x
        }
    } else if current_left < 0.0 {
        -left
    } else if current_right > viewport_width {
        viewport_width - right
    } else {
        current_x
    };
    next.clamp(max_x, 0.0)
}

#[cfg(test)]
mod reveal_tests;
#[cfg(test)]
mod tests;

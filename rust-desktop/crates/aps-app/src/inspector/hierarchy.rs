use super::{hierarchy_content_width, hierarchy_display_depths, hierarchy_numbers, row_matches};
use aps_layout_inspector::{InspectorArchive, LayoutNode, NodeKind};
use aps_ui_foundation::LayoutPreferences;
use std::{collections::BTreeSet, sync::Arc};

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
        let query = query.trim();
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
        let mut skip_depth = None;
        for (index, node) in window.nodes.iter().enumerate() {
            let hidden_system = !show_system_composables
                && node.kind == NodeKind::Compose
                && super::compose_node_id(&node.id)
                    .and_then(|id| archive.compose.as_ref()?.nodes.get(&id))
                    .is_some_and(|compose_node| compose_node.system_created);
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
            visible_nodes: window.nodes.iter().filter(|node| node.visible).count(),
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
    nodes: Arc<Vec<(usize, LayoutNode)>>,
}

impl PreviewCache {
    pub(super) fn get(
        &mut self,
        archive: &Arc<InspectorArchive>,
        window_index: usize,
        hidden: &BTreeSet<String>,
        selected: &Option<String>,
    ) -> Option<Arc<Vec<(usize, LayoutNode)>>> {
        if let Some(snapshot) = &self.snapshot
            && Arc::ptr_eq(&snapshot.archive, archive)
            && snapshot.window_index == window_index
            && snapshot.hidden == *hidden
            && snapshot.selected == *selected
        {
            return Some(snapshot.nodes.clone());
        }
        let window = archive.windows.get(window_index)?;
        let (nodes, _) = super::canvas_nodes(
            &window.nodes,
            hidden,
            selected.as_deref(),
            archive.display.width_px,
            archive.display.height_px,
        );
        let nodes = Arc::new(nodes);
        self.snapshot = Some(PreviewSnapshot {
            archive: archive.clone(),
            window_index,
            hidden: hidden.clone(),
            selected: selected.clone(),
            nodes: nodes.clone(),
        });
        Some(nodes)
    }
}

#[cfg(test)]
mod reveal_tests;
#[cfg(test)]
mod tests;

use super::{InspectorWorkspace, StudioColors, hierarchy::HierarchySnapshot, hierarchy_row};
use aps_ui_foundation::{Language, LayoutPreferences};
use gpui_kit::{
    component::scroll::{Scrollbar, ScrollbarAxis, ScrollbarMode},
    prelude::*,
    *,
};
use std::{collections::BTreeSet, sync::Arc};

pub(super) const SCROLLBAR_INSET: f32 = 12.0;

pub(super) fn render(
    snapshot: Arc<HierarchySnapshot>,
    width: f32,
    hidden: &BTreeSet<String>,
    collapsed: &BTreeSet<String>,
    selected: &Option<String>,
    preferences: LayoutPreferences,
    owner: Entity<InspectorWorkspace>,
    language: Language,
    colors: StudioColors,
    scroll: UniformListScrollHandle,
) -> AnyElement {
    let content_width = snapshot.content_width.max(width);
    let hidden = hidden.clone();
    let collapsed = collapsed.clone();
    let selected = selected.clone();
    let list = uniform_list(
        "inspector-hierarchy-rows",
        snapshot.rows.len(),
        move |range, _, cx| {
            let nodes = &snapshot.archive.windows[snapshot.window_index].nodes;
            range
                .filter_map(|row_index| {
                    let global_index = *snapshot.rows.get(row_index)?;
                    let node = nodes.get(global_index)?;
                    let number = snapshot.numbers.get(global_index)?.as_str();
                    let display_depth = *snapshot.display_depths.get(global_index)?;
                    Some(hierarchy_row(
                        row_index,
                        global_index,
                        number,
                        content_width,
                        node,
                        nodes,
                        &colors,
                        &hidden,
                        &collapsed,
                        &selected,
                        preferences,
                        display_depth,
                        &owner,
                        language,
                        cx,
                    ))
                })
                .collect()
        },
    )
    .with_horizontal_sizing_behavior(ListHorizontalSizingBehavior::Unconstrained)
    .h_full()
    .w_full()
    // Keep the final row out from under the overlaid vertical/horizontal
    // scrollbar hitboxes, matching the editor's scroll gutter contract.
    .pr(px(SCROLLBAR_INSET))
    .pb(px(SCROLLBAR_INSET))
    .track_scroll(&scroll);

    div()
        .relative()
        .flex_1()
        .min_h_0()
        .w_full()
        .child(list)
        .child(
            div().absolute().inset_0().child(
                Scrollbar::new(&scroll)
                    .id("inspector-hierarchy-scrollbar")
                    .axis(ScrollbarAxis::Both)
                    .mode(ScrollbarMode::Always)
                    .viewport_from_layout(),
            ),
        )
        .into_any_element()
}

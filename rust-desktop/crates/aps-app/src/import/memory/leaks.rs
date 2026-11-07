//! Leak Analysis page.
//!
//! HPROF heuristic findings are the only leak evidence available in this
//! build: Leak Suspects, Activity/Fragment lifecycle candidates and
//! object-level lifecycle candidates. LeakCanary agent reports are not
//! available and are presented as an explicit, non-simulated section.
use super::ImportWorkspace;
use super::state::LeakTab;
use crate::studio::{self, FONT_SUPPORTING, StudioColors};
use aps_memory::{ActivityLeak, ClassifierRow, LeakSuspect, LifecycleLeakObject, MemorySnapshot};
use aps_ui_foundation::{Language, TextKey};
use gpui_kit::{
    component::scroll::{Scrollbar, ScrollbarAxis},
    prelude::*,
    *,
};
use std::rc::Rc;

const LEAK_ROW_HEIGHT: f32 = 22.;
const LEAK_OBJECT_ID_WIDTH: f32 = 108.;
const LEAK_COUNT_WIDTH: f32 = 78.;
const LEAK_RETAINED_WIDTH: f32 = 96.;

const LEAK_TABS: [LeakTab; 3] = [LeakTab::Suspects, LeakTab::Activities, LeakTab::Lifecycle];

/// One bounded row of the active HPROF finding kind.
#[derive(Clone)]
enum LeakRowData {
    Suspect(LeakSuspect),
    Activity(ActivityLeak),
    Lifecycle(LifecycleLeakObject),
}

impl ImportWorkspace {
    pub(super) fn render_memory_leak_analysis(
        &mut self,
        colors: &StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let active = self.memory_state.leak_tab;
        let snapshot = self.memory_state.snapshot.clone();
        let totals = finding_totals(snapshot.as_deref());
        let total = match active {
            LeakTab::Suspects => totals[0],
            LeakTab::Activities => totals[1],
            LeakTab::Lifecycle => totals[2],
        };
        let rows: Rc<Vec<LeakRowData>> = Rc::new(rows_for(active, snapshot.as_deref()));
        let entity = cx.entity();

        let summary = div()
            .w_full()
            .flex()
            .items_center()
            .gap(px(8.))
            .child(studio::section_title(
                language.text(TextKey::MemoryPageMemoryLeaks),
                colors,
            ))
            .child(
                div().flex_1().min_w_0().child(
                    studio::supporting(language.text(TextKey::MemoryPageMemoryLeaksDetail), colors)
                        .whitespace_nowrap()
                        .text_ellipsis(),
                ),
            )
            .child(
                studio::compact_text_button(
                    "memory-leak-analyze",
                    language.text(TextKey::MemoryPageLeakAnalyze),
                    false,
                    false,
                    colors,
                )
                .tooltip(language.text(TextKey::MemoryPageLeakcanaryUnavailable)),
            )
            .child(studio::supporting(
                language.format(
                    TextKey::MemoryPageLeakAnalysisStatus,
                    &[(
                        "arg_1",
                        &language.text(TextKey::MemoryPageLeakAnalysisNotRun),
                    )],
                ),
                colors,
            ));

        let notes = div()
            .w_full()
            .flex()
            .flex_wrap()
            .items_center()
            .gap(px(8.))
            .child(studio::supporting(
                language.text(TextKey::MemoryPageOfflineEvidence),
                colors,
            ))
            .child(
                div().flex_1().min_w_0().child(
                    studio::supporting(language.text(TextKey::MemoryPageEvidenceBoundary), colors)
                        .whitespace_nowrap()
                        .text_ellipsis(),
                ),
            );

        let mut tabs = div().w_full().flex().items_center().gap(px(4.));
        for (index, tab) in LEAK_TABS.into_iter().enumerate() {
            let label = format!("{} ({})", language.text(tab.label()), totals[index]);
            tabs = tabs.child(
                studio::tab_button(("memory-leak-tab", index), label, tab == active, colors)
                    .on_click(cx.listener(move |this, _, _, cx| this.memory_set_leak_tab(tab, cx))),
            );
        }
        let start = usize::from(!rows.is_empty());
        tabs = tabs.child(div().flex_1()).child(studio::supporting(
            language.format(
                TextKey::MemoryPageMemoryPageRange,
                &[("arg_1", &start), ("arg_2", &rows.len()), ("arg_3", &total)],
            ),
            colors,
        ));

        let mut table = studio::card(colors).flex_1().min_h_0().gap(px(4.)).child(
            div()
                .w_full()
                .flex()
                .items_center()
                .gap(px(8.))
                .child(studio::section_title(
                    language.text(TextKey::MemoryPageLeakFindings),
                    colors,
                ))
                .child(studio::supporting(
                    language.format(TextKey::MemoryPageCountValue, &[("arg_1", &rows.len())]),
                    colors,
                )),
        );
        if rows.is_empty() {
            table = table.child(div().flex_1().min_h_0().w_full().p(px(8.)).child(
                studio::supporting(language.text(empty_key(snapshot.as_deref())), colors),
            ));
        } else {
            table = table.child(leak_table_header(active, language, colors));
            let colors_copy = *colors;
            let rows_for_list = rows.clone();
            let entity = entity.clone();
            let list = uniform_list("memory-leak-rows", rows_for_list.len(), {
                let rows = rows_for_list.clone();
                move |range, _, _| {
                    let mut items = Vec::with_capacity(range.len());
                    for index in range {
                        if let Some(row) = rows.get(index) {
                            items.push(leak_row(
                                index,
                                row,
                                entity.clone(),
                                language,
                                &colors_copy,
                            ));
                        }
                    }
                    items
                }
            })
            .h_full()
            .w_full()
            .track_scroll(&self.memory_state.leaks_scroll);
            table = table.child(
                div()
                    .relative()
                    .flex_1()
                    .min_h_0()
                    .w_full()
                    .child(list)
                    .child(vertical_scrollbar(
                        "memory-leak-rows-scrollbar",
                        &self.memory_state.leaks_scroll,
                    )),
            );
        }

        studio::page(colors)
            .id("memory-leak-analysis-page")
            .h_auto()
            .flex_1()
            .min_h_0()
            .child(summary)
            .child(notes)
            .child(tabs)
            .child(table)
            .child(leakcanary_section(language, colors))
            .into_any_element()
    }

    /// Resolves a finding's class to a classifier leaf so the Classes workspace
    /// opens its instances; unresolved classes (filtered hierarchy or obfuscated
    /// evidence) deliberately do nothing rather than fabricate a selection.
    fn open_leak_class(&mut self, class_name: &str, cx: &mut Context<Self>) {
        let Some(row) = classifier_row_for_class(&self.memory_state.classifier_rows, class_name)
        else {
            return;
        };
        self.memory_select_classifier(row, cx);
    }
}

fn finding_totals(snapshot: Option<&MemorySnapshot>) -> [u64; 3] {
    snapshot.map_or([0; 3], |snapshot| {
        [
            snapshot.findings.leak_suspects.total,
            snapshot.findings.activities.total,
            snapshot.findings.lifecycle.total,
        ]
    })
}

fn rows_for(tab: LeakTab, snapshot: Option<&MemorySnapshot>) -> Vec<LeakRowData> {
    let Some(snapshot) = snapshot else {
        return Vec::new();
    };
    match tab {
        LeakTab::Suspects => snapshot
            .findings
            .leak_suspects
            .rows
            .iter()
            .cloned()
            .map(LeakRowData::Suspect)
            .collect(),
        LeakTab::Activities => snapshot
            .findings
            .activities
            .rows
            .iter()
            .cloned()
            .map(LeakRowData::Activity)
            .collect(),
        LeakTab::Lifecycle => snapshot
            .findings
            .lifecycle
            .rows
            .iter()
            .cloned()
            .map(LeakRowData::Lifecycle)
            .collect(),
    }
}

fn empty_key(snapshot: Option<&MemorySnapshot>) -> TextKey {
    let Some(snapshot) = snapshot else {
        return TextKey::MemoryPageMemoryLeaksNoHeap;
    };
    if snapshot.findings.leak_suspects.total == 0
        && snapshot.findings.activities.total == 0
        && snapshot.findings.lifecycle.total == 0
    {
        return TextKey::MemoryPageMemoryLeaksNoCandidates;
    }
    TextKey::MemoryPageLeakNoMatchingFindings
}

fn classifier_row_for_class(rows: &[ClassifierRow], class_name: &str) -> Option<ClassifierRow> {
    for row in rows {
        if row.is_leaf() && row.class_name.as_deref() == Some(class_name) {
            return Some(row.clone());
        }
        if let Some(found) = classifier_row_for_class(&row.children, class_name) {
            return Some(found);
        }
    }
    None
}

fn leak_table_header(tab: LeakTab, language: Language, colors: &StudioColors) -> AnyElement {
    let mut header = studio::table_header_row(colors).w_full();
    match tab {
        LeakTab::Suspects => {
            header = header
                .child(studio::table_header_cell(
                    None,
                    language.text(TextKey::MemoryPageClassName),
                    colors,
                ))
                .child(studio::table_header_cell(
                    None,
                    language.text(TextKey::ImportUiReason),
                    colors,
                ))
                .child(studio::table_header_cell(
                    Some(LEAK_COUNT_WIDTH),
                    language.text(TextKey::MemoryPageInstances),
                    colors,
                ))
                .child(studio::table_header_cell(
                    Some(LEAK_RETAINED_WIDTH),
                    language.text(TextKey::MemoryPageRetained),
                    colors,
                ));
        }
        LeakTab::Activities => {
            header = header
                .child(studio::table_header_cell(
                    None,
                    language.text(TextKey::MemoryPageClassName),
                    colors,
                ))
                .child(studio::table_header_cell(
                    None,
                    language.text(TextKey::MemoryPageActivityFragmentLeak),
                    colors,
                ))
                .child(studio::table_header_cell(
                    Some(LEAK_COUNT_WIDTH),
                    language.text(TextKey::MemoryPageLiveInstances),
                    colors,
                ))
                .child(studio::table_header_cell(
                    Some(LEAK_COUNT_WIDTH),
                    language.text(TextKey::MemoryPageDestroyedInstances),
                    colors,
                ))
                .child(studio::table_header_cell(
                    Some(LEAK_RETAINED_WIDTH),
                    language.text(TextKey::MemoryPageRetained),
                    colors,
                ));
        }
        LeakTab::Lifecycle => {
            header = header
                .child(studio::table_header_cell(
                    Some(LEAK_OBJECT_ID_WIDTH),
                    language.text(TextKey::MemoryPageObjectId),
                    colors,
                ))
                .child(studio::table_header_cell(
                    None,
                    language.text(TextKey::MemoryPageClassName),
                    colors,
                ))
                .child(studio::table_header_cell(
                    Some(LEAK_COUNT_WIDTH),
                    language.text(TextKey::MemoryPageHeap),
                    colors,
                ));
        }
    }
    header.into_any_element()
}

fn leak_row(
    index: usize,
    row: &LeakRowData,
    entity: Entity<ImportWorkspace>,
    language: Language,
    colors: &StudioColors,
) -> AnyElement {
    let mut element = div()
        .id(("memory-leak-row", index))
        .h(px(LEAK_ROW_HEIGHT))
        .w_full()
        .flex_shrink_0()
        .flex()
        .items_center()
        .gap(px(4.))
        .px(px(8.))
        .cursor_pointer();
    match row {
        LeakRowData::Suspect(suspect) => {
            let class_name = suspect.class_name.clone();
            let target = class_name.clone();
            element = element
                .child(cell(class_name, None, colors.text))
                .child(cell(suspect.reason.clone(), None, colors.secondary_text))
                .child(cell(
                    suspect.instance_count.to_string(),
                    Some(LEAK_COUNT_WIDTH),
                    colors.text,
                ))
                .child(cell(
                    super::auxiliary::memory_optional_bytes(suspect.retained_size),
                    Some(LEAK_RETAINED_WIDTH),
                    colors.secondary_text,
                ))
                .on_click(move |_, _, cx| {
                    let _ = entity.update(cx, |this, cx| this.open_leak_class(&target, cx));
                });
        }
        LeakRowData::Activity(activity) => {
            let class_name = activity.class_name.clone();
            let target = class_name.clone();
            element = element
                .child(cell(class_name, None, colors.text))
                .child(cell(
                    language.text(TextKey::MemoryPageActivityFragmentLeak),
                    None,
                    colors.secondary_text,
                ))
                .child(cell(
                    activity.live_instance_count.to_string(),
                    Some(LEAK_COUNT_WIDTH),
                    colors.text,
                ))
                .child(cell(
                    activity.destroyed_instance_count.to_string(),
                    Some(LEAK_COUNT_WIDTH),
                    colors.text,
                ))
                .child(cell(
                    super::auxiliary::memory_optional_bytes(activity.retained_size),
                    Some(LEAK_RETAINED_WIDTH),
                    colors.secondary_text,
                ))
                .on_click(move |_, _, cx| {
                    let _ = entity.update(cx, |this, cx| this.open_leak_class(&target, cx));
                });
        }
        LeakRowData::Lifecycle(object) => {
            let object_id = object.object_id;
            element = element
                .child(cell(
                    format!("0x{object_id:x}"),
                    Some(LEAK_OBJECT_ID_WIDTH),
                    colors.secondary_text,
                ))
                .child(cell(object.class_name.clone(), None, colors.text))
                .child(cell(
                    object.heap_name.clone().unwrap_or_else(|| "—".to_owned()),
                    Some(LEAK_COUNT_WIDTH),
                    colors.secondary_text,
                ))
                .on_click(move |_, _, cx| {
                    let _ =
                        entity.update(cx, |this, cx| this.memory_select_instance(object_id, cx));
                });
        }
    }
    element.into_any_element()
}

fn cell(text: impl Into<SharedString>, width: Option<f32>, color: Rgba) -> Div {
    let mut cell = div()
        .text_size(px(FONT_SUPPORTING))
        .text_color(color)
        .whitespace_nowrap()
        .text_ellipsis();
    if let Some(width) = width {
        cell = cell.w(px(width)).flex_shrink_0();
    } else {
        cell = cell.flex_1().min_w_0();
    }
    cell.child(crate::studio::selectable(text.into()))
}

fn leakcanary_section(language: Language, colors: &StudioColors) -> AnyElement {
    studio::card(colors)
        .flex_shrink_0()
        .gap(px(4.))
        .child(
            div()
                .w_full()
                .flex()
                .items_center()
                .gap(px(8.))
                .child(studio::section_title(
                    language.text(TextKey::MemoryPageLeakcanary),
                    colors,
                ))
                .child(studio::supporting(
                    language.text(TextKey::MemoryPageLeakLiveMonitoring),
                    colors,
                ))
                .child(studio::supporting(
                    language.text(TextKey::MemoryPageLeakLiveDisconnected),
                    colors,
                ))
                .child(studio::supporting(
                    language.text(TextKey::MemoryPageAgentNotConnected),
                    colors,
                ))
                .child(div().flex_1())
                .child(
                    studio::compact_text_button(
                        "memory-leakcanary-start",
                        language.text(TextKey::MemoryPageLeakLiveStart),
                        false,
                        false,
                        colors,
                    )
                    .tooltip(language.text(TextKey::MemoryPageLeakcanaryUnavailable)),
                )
                .child(
                    studio::compact_text_button(
                        "memory-leakcanary-stop",
                        language.text(TextKey::MemoryPageLeakLiveStop),
                        false,
                        false,
                        colors,
                    )
                    .tooltip(language.text(TextKey::MemoryPageLeakcanaryUnavailable)),
                ),
        )
        .child(studio::supporting(
            language.text(TextKey::MemoryPageLeakcanaryUnavailable),
            colors,
        ))
        .child(studio::supporting(
            language.text(TextKey::MemoryPageLiveMonitoringNotAvailable),
            colors,
        ))
        .child(studio::supporting(
            language.text(TextKey::MemoryPageLeakLiveEvidence),
            colors,
        ))
        .child(studio::supporting(
            language.text(TextKey::MemoryPageNoLiveEvents),
            colors,
        ))
        .into_any_element()
}

fn vertical_scrollbar(id: &'static str, handle: &UniformListScrollHandle) -> AnyElement {
    div()
        .absolute()
        .inset_0()
        .child(
            Scrollbar::new(handle)
                .id(id)
                .axis(ScrollbarAxis::Vertical)
                .viewport_from_layout(),
        )
        .into_any_element()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[::core::prelude::v1::test]
    fn classifier_leaf_resolution_requires_a_concrete_class() {
        let mut leaf = ClassifierRow::default();
        leaf.id = "leaf".to_owned();
        leaf.class_name = Some("com.example.Leak".to_owned());
        leaf.depth = 2;
        let group = ClassifierRow {
            id: "group".to_owned(),
            children: vec![leaf],
            ..ClassifierRow::default()
        };
        assert_eq!(
            classifier_row_for_class(&[group], "com.example.Leak").map(|row| row.id),
            Some("leaf".to_owned())
        );
        assert!(classifier_row_for_class(&[], "com.example.Leak").is_none());
        // Group rows carry no class name and must stay expansion-only targets.
        let grouped = ClassifierRow {
            id: "group".to_owned(),
            label: "com.example.Leak".to_owned(),
            ..ClassifierRow::default()
        };
        assert!(classifier_row_for_class(&[grouped], "com.example.Leak").is_none());
    }

    #[::core::prelude::v1::test]
    fn empty_state_distinguishes_missing_snapshot_from_no_candidates() {
        assert_eq!(empty_key(None), TextKey::MemoryPageMemoryLeaksNoHeap);
        let snapshot = MemorySnapshot::default();
        assert_eq!(
            empty_key(Some(&snapshot)),
            TextKey::MemoryPageMemoryLeaksNoCandidates
        );
    }
}

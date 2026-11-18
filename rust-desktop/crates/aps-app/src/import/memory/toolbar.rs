//! Memory Profiler toolbar, snapshot tabs, evidence popover and status bar.
//!
//! Layout mirrors the Compose MemoryProfilerMainPage: capture actions and view
//! modes in a 40 dp command bar, a 32 dp snapshot tab row and the operation status bar.
use super::super::*;
use super::ImportWorkspace;
use super::state::MemoryViewMode;
use super::widgets::{self, toolbar_button};
use crate::studio::{self, StudioColors};
use aps_ui_foundation::{TextKey, typography};
use gpui_kit::{component::popover::Popover, prelude::*, *};

impl ImportWorkspace {
    pub(super) fn render_memory_toolbar(
        &mut self,
        _window: &mut Window,
        colors: &StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let loaded = self.memory_state.snapshot.is_some();
        let mut row = div()
            .id("memory-profiler-toolbar")
            .h(px(40.))
            .flex_shrink_0()
            .flex()
            .items_center()
            .gap_1()
            .px(px(8.))
            .bg(colors.toolbar)
            .overflow_x_scrollbar()
            .child(
                toolbar_button(
                    "memory-import-hprof",
                    language.text(TextKey::MemoryPageImportHprof),
                    self.can_open_home_artifact(),
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.pick(cx))),
            )
            .child(
                toolbar_button(
                    "memory-dump-heap",
                    language.text(TextKey::MemoryPageDumpHeap),
                    self.memory_capture_block_reason().is_none(),
                    false,
                    colors,
                )
                .tooltip(self.memory_capture_tooltip())
                .on_click(cx.listener(|this, _, _, cx| this.capture_memory_heap(cx))),
            )
            .child(
                toolbar_button(
                    "memory-capture-native-heap",
                    language.text(TextKey::MemoryPageCaptureNativeHeap),
                    self.advanced_memory_ready(),
                    false,
                    colors,
                )
                .tooltip(self.memory_capture_tooltip())
                .on_click(cx.listener(|this, _, _, cx| {
                    this.capture_advanced_memory(super::advanced::AdvancedMemoryKind::Native, cx)
                })),
            )
            .child(
                toolbar_button(
                    "memory-record-art-samples",
                    language.text(TextKey::MemoryAppRecordSampledArt),
                    self.advanced_memory_ready(),
                    false,
                    colors,
                )
                .tooltip(self.memory_capture_tooltip())
                .on_click(cx.listener(|this, _, _, cx| {
                    this.capture_advanced_memory(super::advanced::AdvancedMemoryKind::Art, cx)
                })),
            )
            .child(
                toolbar_button(
                    "memory-dump-bitmaps",
                    language.text(TextKey::MemoryPageDumpBitmaps),
                    self.advanced_memory_ready(),
                    false,
                    colors,
                )
                .tooltip(self.memory_capture_tooltip())
                .on_click(cx.listener(|this, _, _, cx| {
                    this.capture_advanced_memory(super::advanced::AdvancedMemoryKind::Bitmap, cx)
                })),
            )
            .child(
                toolbar_button(
                    "memory-inject-agent",
                    language.text(TextKey::MemoryAppInjectLeakCanaryAgent),
                    false,
                    false,
                    colors,
                )
                .tooltip(language.text(TextKey::MemoryPageCaptureUnavailable)),
            )
            .child(div().w(px(8.)).flex_shrink_0());
        let current = self.memory_state.view_mode;
        for mode in MemoryViewMode::PRIMARY {
            row = row.child(
                toolbar_button(
                    ("memory-view", mode as usize),
                    language.text(mode.label()),
                    true,
                    current == mode,
                    colors,
                )
                .on_click(cx.listener(move |this, _, _, cx| this.memory_set_view_mode(mode, cx))),
            );
        }
        for mode in [
            MemoryViewMode::BitmapDump,
            MemoryViewMode::NativeHeap,
            MemoryViewMode::SampledArt,
            MemoryViewMode::LeakAnalysis,
        ] {
            let enabled = match mode {
                MemoryViewMode::BitmapDump | MemoryViewMode::NativeHeap => true,
                MemoryViewMode::SampledArt => true,
                MemoryViewMode::LeakAnalysis => loaded,
                _ => loaded,
            };
            row = row.child(
                toolbar_button(
                    ("memory-aux-view", mode as usize),
                    language.text(mode.label()),
                    enabled,
                    current == mode,
                    colors,
                )
                .on_click(cx.listener(move |this, _, _, cx| this.memory_set_view_mode(mode, cx))),
            );
        }
        row = row
            .child(div().flex_1())
            .child(
                toolbar_button(
                    "memory-mapping",
                    language.text(TextKey::MemoryPageMemoryMapping),
                    self.memory_mapping_enabled(),
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.memory_pick_mapping(cx))),
            )
            .child(
                toolbar_button(
                    "memory-run-ai-analysis",
                    language.text(TextKey::FoundationAiAnalysis),
                    self.memory_ai_available(),
                    false,
                    colors,
                )
                .on_click(cx.listener(|_, _, _, cx| cx.emit(crate::evidence::AnalysisRequested))),
            );
        row.into_any_element()
    }

    pub(super) fn render_memory_snapshot_tabs(
        &mut self,
        colors: &StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let tabs = self.memory_snapshot_tabs();
        if tabs.is_empty() {
            return div().h(px(0.)).flex_shrink_0().into_any_element();
        }
        let mut row = div()
            .id("memory-snapshot-tabs")
            .h(px(32.))
            .flex_shrink_0()
            .flex()
            .items_center()
            .gap(px(8.))
            .px(px(8.))
            .bg(colors.toolbar)
            .overflow_x_scrollbar()
            .child(
                div()
                    .text_size(px(typography::BODY))
                    .line_height(px(16.))
                    .text_color(colors.secondary_text)
                    .child(language.text(TextKey::MemoryPageSnapshots)),
            );
        for (id, label, active) in tabs {
            let mut tab = div()
                .id(("memory-snapshot-tab", id))
                .h(px(28.))
                .min_w(px(120.))
                .max_w(px(260.))
                .flex()
                .items_center()
                .border_1()
                .border_color(colors.border)
                .rounded(px(0.))
                .bg(if active {
                    colors.selected_row
                } else {
                    colors.panel
                })
                .cursor_pointer()
                .child(
                    div()
                        .flex_1()
                        .min_w_0()
                        .px(px(8.))
                        .text_size(px(typography::BODY))
                        .line_height(px(16.))
                        .font_weight(if active {
                            FontWeight::SEMIBOLD
                        } else {
                            FontWeight::NORMAL
                        })
                        .text_color(colors.text)
                        .whitespace_nowrap()
                        .text_ellipsis()
                        .child(label),
                );
            tab = tab.child(
                widgets::icon_button(
                    ("memory-snapshot-close", id),
                    "×",
                    !self.memory_state.loading,
                    active,
                    colors,
                )
                .on_click(cx.listener(move |this, _, _, cx| {
                    cx.stop_propagation();
                    this.close_memory_snapshot(id, cx);
                })),
            );
            tab = tab
                .on_click(cx.listener(move |this, _, _, cx| this.select_memory_snapshot(id, cx)));
            row = row.child(tab);
        }
        row.into_any_element()
    }

    pub(super) fn render_memory_evidence_button(&self, colors: &StudioColors) -> AnyElement {
        let language = self.language;
        let button = toolbar_button(
            "memory-evidence-button",
            language.text(TextKey::MemoryPageMemoryEvidence),
            self.memory_state.snapshot.is_some(),
            false,
            colors,
        );
        let Some(snapshot) = &self.memory_state.snapshot else {
            return button.into_any_element();
        };
        let mut lines = vec![self.memory_state.snapshot_label.clone()];
        lines.extend(evidence_lines(
            snapshot,
            self.memory_state.mapping_loaded,
            self.memory_state.mapping_entries,
            language,
        ));
        let text = lines.join("\n");
        let copy_text = text.clone();
        let colors = *colors;
        Popover::new("memory-evidence-popover")
            .anchor(Anchor::TopRight)
            .trigger(button)
            .content(move |_, _, _| {
                let copy_text = copy_text.clone();
                div()
                    .id("memory-evidence-details")
                    .w(px(440.))
                    .max_h(px(360.))
                    .flex()
                    .flex_col()
                    .gap(px(8.))
                    .child(
                        div()
                            .flex()
                            .items_center()
                            .justify_between()
                            .child(studio::section_title(
                                language.text(TextKey::MemoryPageMemoryEvidence),
                                &colors,
                            ))
                            .child(
                                toolbar_button(
                                    "memory-evidence-copy",
                                    language.text(TextKey::ShellNotificationCopyAll),
                                    true,
                                    false,
                                    &colors,
                                )
                                .on_click(move |_, _, cx| {
                                    cx.write_to_clipboard(ClipboardItem::new_string(
                                        copy_text.clone(),
                                    ));
                                }),
                            ),
                    )
                    .child(
                        div()
                            .id("memory-evidence-scroll")
                            .min_h_0()
                            .overflow_y_scrollbar()
                            .text_size(px(typography::SUPPORTING))
                            .line_height(px(16.))
                            .text_color(colors.secondary_text)
                            .child(studio::selectable(text.clone())),
                    )
            })
            .into_any_element()
    }

    pub(super) fn render_memory_status(
        &mut self,
        colors: &StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let loading = self.memory_state.loading || self.busy;
        let error = self
            .memory_state
            .error
            .clone()
            .or_else(|| self.error.as_ref().map(|error| error.render(self.language)));
        let notice = self.memory_state.notice.clone().or_else(|| {
            self.notice
                .as_ref()
                .map(|notice| notice.render(self.language))
        });
        // The footer is permanent chrome: it anchors the workspace bottom edge
        // and keeps the snapshot identity visible while no task is running.
        let mut row = div()
            .id("memory-profiler-status-bar")
            .h(px(29.))
            .flex_shrink_0()
            .flex()
            .items_center()
            .gap_2()
            .px(px(16.))
            .border_t_1()
            .border_color(colors.border)
            .bg(colors.toolbar)
            .overflow_x_scrollbar();
        if loading {
            row = row
                .child(
                    div()
                        .text_size(px(typography::SUPPORTING))
                        .text_color(colors.secondary_text)
                        .child(format!(
                            "{}…",
                            self.memory_state
                                .loading_message
                                .clone()
                                .unwrap_or_else(|| {
                                    language.text(TextKey::MemoryPageWorking).to_owned()
                                })
                        )),
                )
                .child(
                    toolbar_button(
                        "memory-cancel-load",
                        language.text(TextKey::ImportCancelOperation),
                        true,
                        false,
                        colors,
                    )
                    .on_click(cx.listener(|this, _, _, cx| this.cancel_work(cx))),
                );
        }
        if let Some(error) = &error {
            row = row.child(
                div()
                    .text_size(px(typography::SUPPORTING))
                    .text_color(colors.error)
                    .child(error.clone()),
            );
        }
        if let Some(notice) = &notice {
            row = row.child(
                div()
                    .text_size(px(typography::SUPPORTING))
                    .text_color(colors.warning)
                    .child(notice.clone()),
            );
        }
        if !loading && error.is_none() && notice.is_none() {
            let idle = match self.memory_state.snapshot.as_ref() {
                Some(snapshot) => {
                    let counts = language.format(
                        TextKey::MemoryPageSnapshotCounts,
                        &[
                            ("arg_1", &snapshot.metadata.class_count),
                            ("arg_2", &snapshot.metadata.instance_count),
                            ("arg_3", &snapshot.metadata.warnings.len()),
                        ],
                    );
                    match self.memory_state.snapshot_label.as_str() {
                        "" => counts,
                        label => format!("{label} · {counts}"),
                    }
                }
                None => language
                    .text(TextKey::MemoryPageMemoryNoSnapshot)
                    .to_owned(),
            };
            row = row.child(
                div()
                    .text_size(px(typography::SUPPORTING))
                    .text_color(colors.muted_text)
                    .child(idle),
            );
        }
        row.child(div().flex_1())
            .children(self.import_duration_text().map(|value| {
                studio::supporting(value, colors)
                    .flex_shrink_0()
                    .whitespace_nowrap()
            }))
            .into_any_element()
    }
}

fn evidence_lines(
    snapshot: &aps_memory::MemorySnapshot,
    mapping_loaded: bool,
    mapping_entries: usize,
    language: aps_ui_foundation::Language,
) -> Vec<String> {
    let mut lines = vec![
        format!(
            "{}: {}",
            language.text(TextKey::MemoryPageEvidenceIdentity),
            snapshot.source_digest
        ),
        format!(
            "{}: {} classes · {} objects · {} arrays",
            language.text(TextKey::MemoryPageEvidenceCounts),
            snapshot.metadata.class_count,
            snapshot.metadata.instance_count,
            snapshot.metadata.object_array_count + snapshot.metadata.primitive_array_count
        ),
        format!(
            "{}: {}",
            language.text(TextKey::MemoryPageEvidenceHeaps),
            snapshot.metadata.heaps.join(", ")
        ),
        format!(
            "{}: {}",
            language.text(TextKey::MemoryPageEvidenceEngine),
            snapshot.metadata.format
        ),
        language
            .text(TextKey::MemoryPageEvidenceBoundary)
            .to_owned(),
    ];
    for warning in &snapshot.metadata.warnings {
        lines.push(format!(
            "{}: {warning}",
            language.text(TextKey::MemoryPageWarning)
        ));
    }
    if mapping_loaded {
        lines.push(format!(
            "{}: {} ({} entries)",
            language.text(TextKey::MemoryPageMemoryMapping),
            language.text(TextKey::MemoryPageMappingLoadedNote),
            mapping_entries
        ));
    } else {
        lines.push(format!(
            "{}: {}",
            language.text(TextKey::MemoryPageMemoryMapping),
            language.text(TextKey::MemoryAppMappingNotLoadedHint)
        ));
    }
    lines
}

#[cfg(test)]
mod tests {
    use super::evidence_lines;
    use aps_memory::MemorySnapshot;
    use aps_ui_foundation::Language;

    #[test]
    fn evidence_keeps_all_warnings_and_mapping_status() {
        let mut snapshot = MemorySnapshot::default();
        snapshot.source_digest = "digest".into();
        snapshot.metadata.warnings = (0..12).map(|index| format!("warning-{index}")).collect();

        let lines = evidence_lines(&snapshot, true, 42, Language::English);
        for index in 0..12 {
            assert!(
                lines
                    .iter()
                    .any(|line| line.contains(&format!("warning-{index}")))
            );
        }
        assert!(lines.iter().any(|line| line.contains("digest")));
        assert!(lines.iter().any(|line| line.contains("42")));
    }
}

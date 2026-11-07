//! Memory Profiler toolbar, snapshot tabs, evidence strip and status bar.
use super::super::*;
use super::ImportWorkspace;
use super::state::MemoryViewMode;
use crate::studio::{self, StudioColors};
use aps_ui_foundation::{Destination, TextKey, typography};
use gpui_kit::{prelude::*, *};

impl ImportWorkspace {
    pub(super) fn render_memory_toolbar(
        &mut self,
        _window: &mut Window,
        colors: &StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let loaded = self.memory_state.snapshot.is_some();
        let busy = self.memory_state.loading;
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
                studio::compact_text_button(
                    "memory-import-hprof",
                    language.text(TextKey::MemoryAppImportHprof),
                    !busy,
                    true,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.pick(cx))),
            )
            .child(studio::compact_text_button(
                "memory-dump-heap",
                language.text(TextKey::MemoryPageDumpHeap),
                false,
                false,
                colors,
            ))
            .child(studio::compact_text_button(
                "memory-capture-native-heap",
                language.text(TextKey::MemoryPageCaptureNativeHeap),
                false,
                false,
                colors,
            ))
            .child(studio::compact_text_button(
                "memory-record-art-samples",
                language.text(TextKey::MemoryAppRecordSampledArt),
                false,
                false,
                colors,
            ))
            .child(studio::compact_text_button(
                "memory-dump-bitmaps",
                language.text(TextKey::MemoryPageDumpBitmaps),
                false,
                false,
                colors,
            ))
            .child(studio::compact_text_button(
                "memory-inject-agent",
                language.text(TextKey::MemoryAppInjectLeakCanaryAgent),
                false,
                false,
                colors,
            ))
            .child(div().w(px(8.)).flex_shrink_0());
        let current = self.memory_state.view_mode;
        for mode in MemoryViewMode::PRIMARY {
            row = row.child(
                studio::compact_text_button(
                    ("memory-view", mode as usize),
                    language.text(mode.label()),
                    loaded,
                    false,
                    colors,
                )
                .selected(current == mode)
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
                MemoryViewMode::BitmapDump | MemoryViewMode::NativeHeap => false,
                MemoryViewMode::SampledArt => false,
                MemoryViewMode::LeakAnalysis => loaded,
                _ => loaded,
            };
            row = row.child(
                studio::compact_text_button(
                    ("memory-aux-view", mode as usize),
                    language.text(mode.label()),
                    enabled,
                    false,
                    colors,
                )
                .selected(current == mode)
                .on_click(cx.listener(move |this, _, _, cx| this.memory_set_view_mode(mode, cx))),
            );
        }
        row = row
            .child(div().flex_1())
            .child(
                studio::compact_text_button(
                    "memory-mapping",
                    language.text(TextKey::MemoryPageMemoryMapping),
                    false,
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| {
                    this.memory_state.notice = Some(
                        this.language
                            .text(TextKey::MemoryPageMappingUnavailable)
                            .to_owned(),
                    );
                    cx.notify();
                })),
            )
            .child(
                studio::compact_text_button(
                    "memory-run-ai-analysis",
                    language.text(TextKey::FoundationAiAnalysis),
                    loaded,
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, window, cx| {
                    let request = this.memory_analysis_request();
                    if let Some(studio) = this.studio.clone() {
                        studio
                            .update(cx, |studio, cx| {
                                studio.present_ai_request(Destination::Memory, request, window, cx)
                            })
                            .ok();
                    }
                })),
            )
            .child(
                studio::compact_text_button(
                    "memory-close-snapshot",
                    language.text(TextKey::CommonClose),
                    loaded,
                    false,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.close(cx))),
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
            return div().h(px(32.)).flex_shrink_0().into_any_element();
        }
        let mut row = div()
            .id("memory-snapshot-tabs")
            .h(px(32.))
            .flex_shrink_0()
            .flex()
            .items_center()
            .gap_2()
            .px(px(8.))
            .overflow_x_scrollbar()
            .child(studio::supporting(
                language.text(TextKey::MemoryPageSnapshots),
                colors,
            ));
        for (id, label, active) in tabs {
            row = row.child(
                div()
                    .flex()
                    .items_center()
                    .h(px(28.))
                    .min_w(px(120.))
                    .max_w(px(260.))
                    .border_1()
                    .border_color(if active { colors.accent } else { colors.border })
                    .rounded(px(2.))
                    .bg(if active {
                        colors.selected_row
                    } else {
                        colors.panel
                    })
                    .child(
                        studio::compact_text_button(
                            ("memory-snapshot-tab", id),
                            label,
                            !self.memory_state.loading,
                            false,
                            colors,
                        )
                        .selected(active)
                        .on_click(
                            cx.listener(move |this, _, _, cx| this.select_memory_snapshot(id, cx)),
                        ),
                    )
                    .child(
                        studio::compact_text_button(
                            ("memory-snapshot-close", id),
                            "×",
                            true,
                            false,
                            colors,
                        )
                        .on_click(
                            cx.listener(move |this, _, _, cx| this.close_memory_snapshot(id, cx)),
                        ),
                    ),
            );
        }
        row.into_any_element()
    }

    pub(super) fn render_memory_evidence(
        &mut self,
        colors: &StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let Some(snapshot) = self.memory_state.snapshot.clone() else {
            return div().into_any_element();
        };
        let open = self.memory_state.warnings_open;
        let warning_count = snapshot.metadata.warnings.len();
        let mut strip = div()
            .id("memory-evidence")
            .flex_shrink_0()
            .flex()
            .flex_col()
            .child(
                div()
                    .id("memory-evidence-toggle")
                    .flex()
                    .items_center()
                    .gap_2()
                    .px(px(12.))
                    .py(px(4.))
                    .cursor_pointer()
                    .child(studio::supporting(if open { "▾" } else { "▸" }, colors))
                    .child(studio::supporting(
                        language.text(TextKey::MemoryPageMemoryEvidence),
                        colors,
                    ))
                    .child(div().flex_1().min_w_0().child(studio::supporting(
                        self.memory_state.snapshot_label.clone(),
                        colors,
                    )))
                    .child(studio::supporting(warning_count.to_string(), colors))
                    .on_click(cx.listener(|this, _, _, cx| {
                        this.memory_state.warnings_open = !this.memory_state.warnings_open;
                        cx.notify();
                    })),
            );
        if open {
            let mut panel = div()
                .id("memory-evidence-details")
                .max_h(px(160.))
                .overflow_y_scrollbar()
                .px(px(8.))
                .pb(px(6.))
                .flex()
                .flex_col()
                .gap(px(2.))
                .child(studio::section_title(
                    language.text(TextKey::MemoryPageMemoryEvidence),
                    colors,
                ));
            for line in evidence_lines(&snapshot, language) {
                panel = panel.child(studio::supporting(line, colors));
            }
            strip = strip.child(panel);
        }
        strip.into_any_element()
    }

    pub(super) fn render_memory_status(
        &mut self,
        colors: &StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let loading = self.memory_state.loading;
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
        if !loading && error.is_none() && notice.is_none() {
            return div().into_any_element();
        }
        let mut row = div()
            .id("memory-profiler-status-bar")
            .h(px(26.))
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
                .child(studio::supporting(
                    format!(
                        "{}…",
                        self.memory_state
                            .loading_message
                            .clone()
                            .unwrap_or_else(|| language
                                .text(TextKey::MemoryPageWorking)
                                .to_owned())
                    ),
                    colors,
                ))
                .child(
                    studio::compact_text_button(
                        "memory-cancel-load",
                        language.text(TextKey::ImportCancelOperation),
                        true,
                        false,
                        colors,
                    )
                    .on_click(cx.listener(|this, _, _, cx| this.cancel_work(cx))),
                );
        }
        if let Some(error) = error {
            row = row.child(
                div()
                    .text_size(px(typography::SUPPORTING))
                    .text_color(colors.error)
                    .child(crate::studio::selectable(error)),
            );
        }
        if let Some(notice) = notice {
            row = row.child(
                div()
                    .text_size(px(typography::SUPPORTING))
                    .text_color(colors.warning)
                    .child(crate::studio::selectable(notice)),
            );
        }
        row.into_any_element()
    }
}

fn evidence_lines(
    snapshot: &aps_memory::MemorySnapshot,
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
    for warning in snapshot.metadata.warnings.iter().take(8) {
        lines.push(format!(
            "{}: {warning}",
            language.text(TextKey::MemoryPageWarning)
        ));
    }
    lines
}

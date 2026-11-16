//! Expandable evidence sections for the selected frame.
use super::*;
use crate::frame::detail_data::{DetailSectionKey, frame_detail_sections};

pub(super) fn detail_body(
    this: &FrameWorkspace,
    frame: &AnalyzedFrame,
    colors: &StudioColors,
    cx: &Context<FrameWorkspace>,
) -> AnyElement {
    let language = this.language;
    let mut details = div()
        .id("frame-detail-body")
        .flex_grow(1.2)
        .flex_basis(relative(0.))
        .min_w_0()
        .h_full()
        .pr(px(FRAME_DETAIL_SCROLLBAR_GUTTER))
        .overflow_y_scrollbar()
        .flex()
        .flex_col()
        .gap(px(6.));

    for section in frame_detail_sections(frame, language) {
        let key = section.key;
        let open = this.ui.detail_expanded.contains(&key);
        let id = match key {
            DetailSectionKey::Summary => "frame-detail-summary-toggle",
            DetailSectionKey::Timing => "frame-detail-timing-toggle",
            DetailSectionKey::Stages => "frame-detail-stages-toggle",
            DetailSectionKey::Jank => "frame-detail-jank-toggle",
            DetailSectionKey::Context => "frame-detail-context-toggle",
        };
        let label = format!(
            "{} {}",
            if open { "▾" } else { "▸" },
            language.text(section.title)
        );
        details = details.child(
            studio::compact_text_button(id, label, true, false, colors)
                .w_full()
                .on_click(cx.listener(move |this, _, _, cx| {
                    if !this.ui.detail_expanded.remove(&key) {
                        this.ui.detail_expanded.insert(key);
                    }
                    cx.notify();
                })),
        );
        if open {
            if key == DetailSectionKey::Stages && !frame.sample.stages.values().is_empty() {
                let maximum = model::maximum_stage_ns(&frame.sample.stages);
                for (index, (name, duration)) in
                    frame.sample.stages.values().into_iter().enumerate()
                {
                    details = details.child(stage_row(name, duration, maximum, index, colors));
                }
                if let Some(stage) = &frame.largest_reported_stage {
                    details = details.child(
                        div()
                            .w_full()
                            .text_size(px(FONT_SUPPORTING))
                            .text_color(colors.secondary_text)
                            .child(crate::studio::selectable(language.format(
                                TextKey::FrameStageInvestigationHint,
                                &[("value1", stage)],
                            ))),
                    );
                }
            } else {
                for row in section.rows {
                    details = details.child(evidence_row(row.label, row.value, colors));
                }
            }
        }
    }
    if this.perfetto_trace_file.is_some() {
        details = details.child(
            studio::compact_text_button(
                "frame-open-trace-from-detail",
                language.text(TextKey::FrameOpenTraceInPerfetto),
                true,
                false,
                colors,
            )
            .on_click(cx.listener(|this, _, _, cx| this.open_trace(cx))),
        );
    }
    let evidence = super::super::detail_data::copy_detail_evidence(frame, language);
    let frame_id = frame.sample.frame_id;
    details = details
        .child(
            studio::compact_text_button(
                "frame-copy-evidence",
                language.text(TextKey::FrameCopyEvidence),
                true,
                false,
                colors,
            )
            .on_click(cx.listener(move |this, _, _, cx| {
                cx.write_to_clipboard(ClipboardItem::new_string(evidence.clone()));
                this.ui.copy_feedback = Some((frame_id, true));
                cx.notify();
            })),
        )
        .child(
            div()
                .w_full()
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.secondary_text)
                .child(crate::studio::selectable(
                    language.text(TextKey::FrameCopyEvidencePrivacyHint),
                )),
        );
    if let Some((id, succeeded)) = this.ui.copy_feedback {
        if id == frame_id {
            details = details.child(
                div()
                    .w_full()
                    .text_size(px(FONT_SUPPORTING))
                    .text_color(if succeeded {
                        colors.success
                    } else {
                        colors.error
                    })
                    .child(crate::studio::selectable(language.text(if succeeded {
                        TextKey::FrameEvidenceCopied
                    } else {
                        TextKey::FrameClipboardUnavailable
                    }))),
            );
        }
    }
    details.into_any_element()
}

fn evidence_row(label: String, value: String, colors: &StudioColors) -> AnyElement {
    div()
        .w_full()
        .min_w_0()
        .flex()
        .flex_col()
        .gap(px(2.))
        .child(
            div()
                .w_full()
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.secondary_text)
                .child(crate::studio::selectable(label)),
        )
        .child(
            div()
                .w_full()
                .text_size(px(FONT_SUPPORTING))
                .text_color(colors.text)
                .child(crate::studio::selectable(value)),
        )
        .child(studio::divider(colors))
        .into_any_element()
}

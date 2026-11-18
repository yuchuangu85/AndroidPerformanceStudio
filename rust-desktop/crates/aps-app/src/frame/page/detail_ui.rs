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
    let probes = super::super::perfetto::probes(&frame.sample);
    let has_probe = !probes.is_empty();
    if frame.sample.source == FrameSource::Perfetto {
        details = details.child(evidence_row(
            language.text(TextKey::FrameProbeOverlap).into(),
            language
                .text(if probes.is_empty() {
                    TextKey::FrameProbeMissing
                } else {
                    TextKey::FrameProbeOverlapHint
                })
                .into(),
            colors,
        ));
        let mut locate_markers = std::collections::BTreeSet::new();
        let mut optimize_markers = std::collections::BTreeSet::new();
        for p in probes {
            let marker = p.marker.clone();
            let name = this
                .probe_labels
                .get(&p.marker)
                .map(|name| format!("{name} · {}", p.marker))
                .unwrap_or(p.marker);
            let cpu = model::format_millis(p.scheduled_ns);
            let wall = model::format_millis(Some(p.overlap_ns));
            details = details.child(evidence_row(
                name,
                language.format(
                    TextKey::FrameProbeTimes,
                    &[
                        ("tid", &p.tid),
                        ("role", &p.role.label()),
                        ("wall", &wall),
                        ("cpu", &cpu),
                    ],
                ),
                colors,
            ));
            if !this.is_loading
                && !this.is_capturing
                && this.probe_labels.contains_key(&marker)
                && locate_markers.insert(marker.clone())
            {
                let locate = marker.clone();
                details = details.child(
                    studio::compact_text_button(
                        ("frame-locate-probe", locate_markers.len()),
                        language.text(TextKey::FrameProbeLocate),
                        true,
                        false,
                        colors,
                    )
                    .on_click(cx.listener(move |_, _, _, cx| {
                        cx.emit(crate::frame::FrameProbeAction::Locate(locate.clone()))
                    })),
                );
            }
            if this.probe_binding().is_some()
                && frame.sample.package_name.is_some()
                && !frame.sample.states.contains_key("aps.trace.omitted")
                && p.role != aps_perfetto::frame_probes::ThreadRole::Other
                && optimize_markers.insert(marker.clone())
            {
                details = details.child(
                    studio::compact_text_button(
                        ("frame-optimize", optimize_markers.len()),
                        language.text(TextKey::FrameOptimize),
                        true,
                        false,
                        colors,
                    )
                    .on_click(cx.listener(move |_, _, _, cx| {
                        cx.emit(crate::frame::FrameProbeAction::Optimize(marker.clone()))
                    })),
                );
            }
        }
        if frame
            .sample
            .states
            .get("aps.trace.omitted")
            .and_then(|s| s.parse::<usize>().ok())
            .is_some_and(|n| n > 0)
        {
            details = details.child(studio::selectable(
                language.text(TextKey::FrameProbeLimited),
            ));
        }
    }
    if frame.sample.source == FrameSource::Perfetto
        && this
            .current_session
            .as_ref()
            .is_some_and(|s| s.package_name.is_some() && s.device_serial.is_some())
        && !this.is_loading
        && !this.is_capturing
    {
        details = details.child(
            studio::compact_text_button(
                "frame-compare-runs",
                language.text(TextKey::FrameCompareTitle),
                true,
                false,
                colors,
            )
            .on_click(cx.listener(|f, _, w, cx| f.show_comparison(w, cx))),
        );
    }
    let can_patch = this.probe_binding().is_some();
    if can_patch || (has_probe && !this.is_loading && !this.is_capturing) {
        details = details.child(
            studio::compact_text_button(
                "frame-probe-plan",
                language.text(if has_probe {
                    TextKey::FrameProbeInterpret
                } else {
                    TextKey::FrameProbeAi
                }),
                true,
                false,
                colors,
            )
            .on_click(cx.listener(|_, _, _, cx| cx.emit(crate::frame::FrameProbeAction::Plan))),
        );
    }
    if can_patch {
        details = details.child(
            studio::compact_text_button(
                "frame-probe-review",
                language.text(TextKey::FrameProbeReview),
                true,
                false,
                colors,
            )
            .on_click(cx.listener(|_, _, _, cx| cx.emit(crate::frame::FrameProbeAction::Patch))),
        );
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

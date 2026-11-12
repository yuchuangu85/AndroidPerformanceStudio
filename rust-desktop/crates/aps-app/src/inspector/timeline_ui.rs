use super::*;

impl InspectorWorkspace {
    fn select_timeline_frame(&mut self, id: u64, cx: &mut Context<Self>) {
        if self.busy
            || self.capturing
            || self.timeline.selected().is_some_and(|frame| frame.id == id)
        {
            return;
        }
        let Some(frame) = self
            .timeline
            .frames()
            .iter()
            .find(|frame| frame.id == id)
            .cloned()
        else {
            return;
        };
        self.pause_auto_scan();
        match frame
            .archive
            .analyze_window(&frame.archive.default_window_id)
        {
            Ok(findings) => {
                self.timeline.select(id);
                self.generation += 1;
                self.display_timeline_frame(Some(frame), findings, cx);
            }
            Err(error) => {
                self.error = Some(error.to_string());
                cx.notify();
            }
        }
    }

    fn close_timeline_frame(&mut self, id: u64, cx: &mut Context<Self>) {
        if self.busy || self.capturing {
            return;
        }
        self.pause_auto_scan();
        if self.timeline.selected().is_none_or(|frame| frame.id != id) {
            if self.timeline.remove(id) {
                self.timeline_reveal_selected = true;
                cx.notify();
            }
            return;
        }
        // Validate the replacement before discarding the displayed evidence.
        let mut history = self.timeline.clone();
        history.remove(id);
        let frame = history.selected().cloned();
        let findings = frame.as_ref().map_or_else(
            || Ok(LayoutAnalysis::default()),
            |frame| {
                frame
                    .archive
                    .analyze_window(&frame.archive.default_window_id)
            },
        );
        match findings {
            Ok(findings) => {
                self.timeline = history;
                self.generation += 1;
                self.display_timeline_frame(frame, findings, cx);
            }
            Err(error) => {
                self.error = Some(error.to_string());
                cx.notify();
            }
        }
    }

    fn scroll_timeline(&mut self, right: bool, cx: &mut Context<Self>) {
        let offset = self.timeline_scroll.offset();
        let distance = (f32::from(self.timeline_scroll.bounds().size.width) * 0.8).max(24.);
        let maximum = f32::from(self.timeline_scroll.max_offset().x).max(0.);
        let delta = if right { -distance } else { distance };
        self.timeline_scroll.set_offset(point(
            px((f32::from(offset.x) + delta).clamp(-maximum, 0.)),
            px(0.),
        ));
        cx.notify();
    }

    pub(super) fn timeline_strip(
        &mut self,
        colors: &StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        if self.timeline.frames().is_empty() {
            return div().h(px(0.)).flex_shrink_0().into_any_element();
        }
        let language = self.language;
        let selected = self.timeline.selected().map(|frame| frame.id);
        if std::mem::take(&mut self.timeline_reveal_selected)
            && let Some(index) = self
                .timeline
                .frames()
                .iter()
                .position(|frame| Some(frame.id) == selected)
        {
            self.timeline_scroll.scroll_to_item(index);
        }
        let enabled = !self.busy && !self.capturing;
        let tabs = self
            .timeline
            .frames()
            .iter()
            .enumerate()
            .map(|(position, frame)| {
                let id = frame.id;
                let active = selected == Some(id);
                let count: usize = frame
                    .archive
                    .windows
                    .iter()
                    .map(|window| window.nodes.len())
                    .sum();
                let source = language.text(match frame.source {
                    timeline::Source::Offline => TextKey::InspectorOfflineArchive,
                    timeline::Source::Adb => TextKey::InspectorCompatibleView,
                    timeline::Source::Agent => TextKey::InspectorAgentView,
                    timeline::Source::Compose => TextKey::InspectorComposeAgent,
                });
                let label = format!(
                    "{} · {source} · {count} {}",
                    language.format(
                        TextKey::InspectorTimelineFrame,
                        &[("index", &(position + 1))],
                    ),
                    language.text(TextKey::InspectorNodes),
                );
                div()
                    .id(("inspector-timeline-tab", id))
                    .h(px(24.))
                    .flex_shrink_0()
                    .flex()
                    .items_center()
                    .rounded(px(4.))
                    .border_1()
                    .border_color(if active { colors.accent } else { colors.border })
                    .bg(if active {
                        colors.selected_row
                    } else {
                        colors.panel
                    })
                    .child(
                        Button::new(("inspector-timeline-select", id))
                            .ghost()
                            .h_full()
                            .px(px(8.))
                            .rounded_none()
                            .text_color(if active { colors.text } else { colors.accent })
                            .disabled(!enabled)
                            .accessibility_label(label.clone())
                            .tooltip(format!("{source} · {}", frame.archive.frame.package_name))
                            .child(
                                div()
                                    .text_size(px(studio::FONT_SUPPORTING))
                                    .whitespace_nowrap()
                                    .child(label),
                            )
                            .on_click(cx.listener(move |this, _, _, cx| {
                                this.select_timeline_frame(id, cx)
                            })),
                    )
                    .child(
                        Button::new(("inspector-timeline-close", id))
                            .ghost()
                            .h(px(18.))
                            .w(px(18.))
                            .p(px(0.))
                            .mr(px(2.))
                            .icon(IconName::Close)
                            .tooltip(language.text(TextKey::CommonClose))
                            .disabled(!enabled)
                            .on_click(cx.listener(move |this, _, _, cx| {
                                cx.stop_propagation();
                                this.close_timeline_frame(id, cx);
                            })),
                    )
            })
            .collect::<Vec<_>>();
        let scroll = div()
            .id("inspector-timeline-tabs")
            .flex_1()
            .min_w_0()
            .h_full()
            .flex()
            .items_center()
            .gap(px(6.))
            .pb(px(6.))
            .overflow_x_scroll()
            .track_scroll(&self.timeline_scroll)
            .children(tabs)
            .horizontal_scrollbar(&self.timeline_scroll);
        let can_scroll = self.timeline.frames().len() > 1;
        div()
            .id("inspector-timeline")
            .h(px(34.))
            .w_full()
            .min_w_0()
            .flex_shrink_0()
            .flex()
            .items_center()
            .gap(px(6.))
            .px(px(8.))
            .border_t_1()
            .border_color(colors.border)
            .bg(colors.toolbar)
            .child(
                div()
                    .flex_shrink_0()
                    .text_size(px(studio::FONT_SUPPORTING))
                    .text_color(colors.secondary_text)
                    .child(language.text(TextKey::InspectorTimeline)),
            )
            .child(
                toolbar_icon_button(
                    "inspector-timeline-left",
                    IconName::ChevronLeft,
                    language.text(TextKey::InspectorTimelineScrollLeft),
                    false,
                    can_scroll,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.scroll_timeline(false, cx))),
            )
            .child(scroll)
            .child(
                toolbar_icon_button(
                    "inspector-timeline-right",
                    IconName::ChevronRight,
                    language.text(TextKey::InspectorTimelineScrollRight),
                    false,
                    can_scroll,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.scroll_timeline(true, cx))),
            )
            .into_any_element()
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn history_navigation_never_starts_capture_or_republishes_a_frame() {
        let source = include_str!("timeline_ui.rs");
        let navigation = source
            .split_once("fn select_timeline_frame(")
            .unwrap()
            .1
            .split_once("fn scroll_timeline(")
            .unwrap()
            .0;
        assert!(!navigation.contains("publish_protocol_frame("));
        assert!(!navigation.contains("capture_compose("));
        assert!(!navigation.contains(".refresh("));
        assert!(navigation.contains("self.busy || self.capturing"));
        assert!(navigation.contains("self.display_timeline_frame("));
    }

    #[test]
    fn strip_uses_a_persistent_horizontal_scroll_handle_and_reserved_gutter() {
        let source = include_str!("timeline_ui.rs");
        let strip = source
            .split_once("pub(super) fn timeline_strip(")
            .unwrap()
            .1
            .split_once("#[cfg(test)]")
            .unwrap()
            .0;
        assert!(strip.contains(".track_scroll(&self.timeline_scroll)"));
        assert!(strip.contains(".horizontal_scrollbar(&self.timeline_scroll)"));
        assert!(strip.contains("self.timeline_scroll.scroll_to_item(index)"));
        assert!(strip.contains(".pb(px(6.))"));
        assert!(strip.contains("self.timeline.frames().is_empty()"));
        let owner = include_str!("../inspector.rs");
        assert!(owner.contains(
            ".child(workspace)\n            .child(timeline)\n            .child(status)"
        ));
    }
}

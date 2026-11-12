//! Explicit, frame-bound native save flow. This never starts device operations.
use super::*;

#[derive(Default)]
pub(super) struct ExportUi {
    confirmation: Option<ExportRequest>,
    pub(super) active: bool,
    pub(super) notice: Option<NoticeSnapshot>,
}

impl ExportUi {
    pub(super) fn invalidate(&mut self) {
        self.confirmation = None;
        self.active = false;
    }
}

#[derive(Clone)]
struct ExportRequest {
    archive: Arc<InspectorArchive>,
    generation: u64,
    cancel: CancellationToken,
}

impl ExportRequest {
    fn current(&self, archive: Option<&Arc<InspectorArchive>>, generation: u64) -> bool {
        !self.cancel.is_cancelled()
            && self.generation == generation
            && archive.is_some_and(|archive| Arc::ptr_eq(archive, &self.archive))
    }
}

impl InspectorWorkspace {
    /// Native File/Export callback entry. First asks for sensitive-evidence
    /// consent, then opens the native save picker. No implicit export path.
    pub fn export_archive(&mut self, cx: &mut Context<Self>) {
        if self.busy || self.capturing || self.export.active {
            return;
        }
        let Some(archive) = self.archive.clone() else {
            return;
        };
        self.pause_auto_scan();
        self.cancel.cancel();
        self.cancel = CancellationToken::new();
        self.generation += 1;
        self.export.confirmation = Some(ExportRequest {
            archive,
            generation: self.generation,
            cancel: self.cancel.clone(),
        });
        self.export.active = true;
        self.busy = true;
        self.error = None;
        cx.notify();
    }

    fn dismiss_export(&mut self, cx: &mut Context<Self>) {
        let Some(request) = self.export.confirmation.take() else {
            return;
        };
        if request.current(self.archive.as_ref(), self.generation) {
            self.busy = false;
        }
        request.cancel.cancel();
        self.export.active = false;
        cx.notify();
    }

    fn pick_export_destination(&mut self, cx: &mut Context<Self>) {
        let Some(request) = self.export.confirmation.take() else {
            return;
        };
        if !request.current(self.archive.as_ref(), self.generation) {
            self.export.active = false;
            cx.notify();
            return;
        }
        let directory = aps_platform::home_directory().unwrap_or_else(|_| std::env::temp_dir());
        let picker = cx.prompt_for_new_path(&directory, Some("inspector-capture.zip"));
        cx.spawn(async move |this, cx| {
            let chosen = picker.await;
            let target = this
                .update(cx, |this, cx| {
                    if !request.current(this.archive.as_ref(), this.generation) {
                        return None;
                    }
                    match chosen {
                        Ok(Ok(Some(path))) => Some(path),
                        other => {
                            this.busy = false;
                            this.export.active = false;
                            if !matches!(other, Ok(Ok(None))) {
                                this.error = Some(
                                    this.language.text(TextKey::ProfilerFilePickerFailed).into(),
                                );
                            }
                            cx.notify();
                            None
                        }
                    }
                })
                .ok()
                .flatten();
            let Some(target) = target else {
                return;
            };
            let archive = request.archive.clone();
            let cancel = request.cancel.clone();
            let result = cx
                .background_executor()
                .spawn(async move {
                    archive
                        .export_archive(&target, &cancel)
                        .map(|summary| (target, summary))
                })
                .await;
            let _ = this.update(cx, |this, cx| {
                if !request.current(this.archive.as_ref(), this.generation) {
                    return;
                }
                this.busy = false;
                this.export.active = false;
                match result {
                    Ok((path, summary)) => {
                        this.export.notice = Some(NoticeSnapshot::info(this.language.format(
                            TextKey::InspectorExportSaved,
                            &[
                                (
                                    "path",
                                    &crate::evidence::clipped(&path.display().to_string(), 4096),
                                ),
                                ("bytes", &summary.size_bytes),
                            ],
                        )));
                    }
                    Err(error) => {
                        this.error = Some(
                            this.language
                                .format(TextKey::InspectorExportFailed, &[("detail", &error)]),
                        )
                    }
                }
                cx.notify();
            });
        })
        .detach();
        cx.notify();
    }
}

pub(super) fn confirmation_dialog(
    language: Language,
    colors: &StudioColors,
    state: &ExportUi,
    entity: Entity<InspectorWorkspace>,
) -> Option<AnyElement> {
    state.confirmation.as_ref()?;
    let dismiss = entity.clone();
    let confirm = entity.clone();
    Some(
        div()
            .id("inspector-export-backdrop")
            .absolute()
            .inset_0()
            .flex()
            .items_center()
            .justify_center()
            .bg(rgba(0x00000052))
            .on_click(move |_, _, cx| entity.update(cx, |this, cx| this.dismiss_export(cx)))
            .child(
                div()
                    .id("inspector-export-confirmation")
                    .w(px(460.))
                    .max_w_full()
                    .p(px(12.))
                    .bg(colors.panel)
                    .border_1()
                    .border_color(colors.strong_border)
                    .rounded(px(studio::metrics::CARD_RADIUS))
                    .on_click(|_, _, cx| cx.stop_propagation())
                    .child(
                        div()
                            .flex()
                            .flex_col()
                            .gap(px(10.))
                            .child(
                                div()
                                    .font_weight(FontWeight::SEMIBOLD)
                                    .child(language.text(TextKey::InspectorExportTitle)),
                            )
                            .child(crate::studio::selectable(
                                language.text(TextKey::InspectorExportSensitive),
                            ))
                            .child(
                                div()
                                    .flex()
                                    .justify_end()
                                    .gap(px(8.))
                                    .child(
                                        Button::new("inspector-export-cancel")
                                            .ghost()
                                            .label(language.text(TextKey::InspectorExportCancel))
                                            .on_click(move |_, _, cx| {
                                                dismiss
                                                    .update(cx, |this, cx| this.dismiss_export(cx))
                                            }),
                                    )
                                    .child(
                                        Button::new("inspector-export-confirm")
                                            .primary()
                                            .label(language.text(TextKey::InspectorExportConfirm))
                                            .on_click(move |_, _, cx| {
                                                confirm.update(cx, |this, cx| {
                                                    this.pick_export_destination(cx)
                                                })
                                            }),
                                    ),
                            ),
                    ),
            )
            .into_any_element(),
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    fn archive() -> Arc<InspectorArchive> {
        Arc::new(InspectorArchive {
            archive_version: 1,
            frame: aps_layout_inspector::FrameIdentity {
                source_digest: aps_contracts::SourceDigest::from_bytes([1; 32]),
                package_name: "example.test".into(),
                captured_at_epoch_millis: 1,
                protocol_major: 1,
                protocol_minor: 0,
            },
            display: DisplayInfo {
                width_px: 1,
                height_px: 1,
                density: 1.0,
            },
            capabilities: aps_layout_inspector::Capabilities {
                view_hierarchy: true,
                compose_semantics: false,
                screenshots: false,
                timeline: false,
            },
            windows: Vec::new(),
            default_window_id: String::new(),
            screenshot: None,
            screenshot_png: None,
            compose: None,
            export_payload: Default::default(),
        })
    }

    #[::core::prelude::v1::test]
    fn export_request_rejects_replaced_frames_stale_generations_and_cancellation() {
        let original = archive();
        let request = ExportRequest {
            archive: original.clone(),
            generation: 7,
            cancel: CancellationToken::new(),
        };
        assert!(request.current(Some(&original), 7));
        assert!(!request.current(Some(&original), 8));
        assert!(!request.current(Some(&archive()), 7));
        assert!(!request.current(None, 7));
        request.cancel.cancel();
        assert!(!request.current(Some(&original), 7));
    }

    #[::core::prelude::v1::test]
    fn export_has_an_explicit_save_picker_and_checks_frame_before_and_after_io() {
        let source = include_str!("export.rs")
            .split("#[cfg(test)]")
            .next()
            .unwrap();
        let picker = source.find("prompt_for_new_path").unwrap();
        let work = source.find("background_executor").unwrap();
        assert!(picker < work);
        assert!(
            source[..picker].contains("request.current(self.archive.as_ref(), self.generation)")
        );
        assert!(
            source[picker..work]
                .contains("request.current(this.archive.as_ref(), this.generation)")
        );
        assert!(source[work..].contains("request.current(this.archive.as_ref(), this.generation)"));
        assert!(!source.contains("capture_agent("));
        assert!(!source.contains("capture_compatible("));
    }

    #[::core::prelude::v1::test]
    fn export_consent_and_invalidation_do_not_change_capture_evidence() {
        let source = include_str!("export.rs")
            .split("#[cfg(test)]")
            .next()
            .unwrap();
        let begin = source
            .split("pub fn export_archive(")
            .nth(1)
            .unwrap()
            .split("fn dismiss_export(")
            .next()
            .unwrap();
        assert!(begin.contains("confirmation = Some(ExportRequest"));
        assert!(!begin.contains("prompt_for_new_path"));
        assert!(!begin.contains("self.archive ="));
        let mut state = ExportUi {
            active: true,
            notice: Some(NoticeSnapshot::info("previous export")),
            ..Default::default()
        };
        state.invalidate();
        assert!(!state.active);
        assert!(state.confirmation.is_none());
        assert_eq!(state.notice.as_ref().unwrap().message, "previous export");
    }
}

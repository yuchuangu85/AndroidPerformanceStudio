//! Frame -> user-selected, verified **local** source snapshot -> review-only patch.
use super::{FrameWorkspace, trace_patch};
use crate::studio::{self, StudioColors};
use aps_source::SourceLocation;
use aps_ui_foundation::{Language, TextKey};
use gpui_kit::{
    component::{WindowExt, scroll::ScrollableElement},
    prelude::*,
    *,
};
use std::{io::Write, path::Path, sync::Arc};

pub(crate) struct ProbeDraft {
    location: SourceLocation,
    text: String,
    methods: Vec<(usize, String)>,
}
impl ProbeDraft {
    pub(crate) fn new(location: SourceLocation, text: String) -> Result<Self, String> {
        if aps_source::digest_text(&text) != location.content_hash {
            return Err("Source content no longer matches its snapshot.".into());
        }
        let methods = trace_patch::trace_methods(&location.relative_path, &text)?;
        Ok(Self {
            location,
            text,
            methods,
        })
    }
}
pub(crate) fn show_probe_draft(
    draft: ProbeDraft,
    id: u64,
    lang: Language,
    frame: Entity<FrameWorkspace>,
    window: &mut Window,
    cx: &mut App,
) {
    let package = frame
        .read(cx)
        .current_session
        .as_ref()
        .and_then(|s| s.package_name.clone());
    let draft = Arc::new(draft);
    window.open_dialog(cx, move |dialog, _, cx| {
        let colors = StudioColors::from_theme(cx);
        let mut list = div()
            .id("frame-probe-methods")
            .max_h(px(440.))
            .overflow_y_scrollbar()
            .flex()
            .flex_col()
            .gap(px(6.))
            .child(studio::selectable(
                lang.format(TextKey::FrameProbeChoose, &[("id", &id)]),
            ))
            .child(studio::selectable(format!(
                "{} @ {}",
                draft.location.relative_path,
                &draft.location.snapshot_id.to_hex()[..12]
            )));
        if draft.methods.is_empty() {
            list = list.child(studio::selectable(lang.text(TextKey::FrameProbeNoMethods)));
        }
        for (line, name) in &draft.methods {
            let label = format!("{name} : {line}");
            let line = *line;
            let draft = draft.clone();
            let frame = frame.clone();
            let name = name.clone();
            let package = package.clone();
            list = list.child(
                studio::compact_text_button(
                    ("frame-probe-method", line),
                    label,
                    true,
                    false,
                    &colors,
                )
                .on_click(move |_, window, cx| {
                    match trace_patch::trace_patch(&draft.location.relative_path, &draft.text, line)
                    {
                        Ok(p) => {
                            window.close_dialog(cx);
                            let p_marker = p.marker.clone();
                            let instrumented_hash = p.instrumented;
                            preview(
                                p,
                                super::probe_receipt::Receipt {
                                    marker: p_marker,
                                    location: draft.location.clone(),
                                    line,
                                    method: name.clone(),
                                    package: package.clone(),
                                    instrumented: instrumented_hash,
                                },
                                id,
                                lang,
                                frame.clone(),
                                window,
                                cx,
                            );
                        }
                        Err(err) => {
                            window.close_dialog(cx);
                            frame.update(cx, |f, cx| {
                                f.error = Some(err);
                                cx.notify();
                            });
                        }
                    }
                }),
            );
        }
        dialog
            .title(lang.text(TextKey::FrameProbeReview))
            .w(px(620.))
            .child(list)
    });
}
fn preview(
    p: trace_patch::TracePatch,
    receipt: super::probe_receipt::Receipt,
    id: u64,
    lang: Language,
    frame: Entity<FrameWorkspace>,
    window: &mut Window,
    cx: &mut App,
) {
    let label = format!(
        "{}:{} · {}",
        receipt.location.relative_path, receipt.line, receipt.method
    );
    let patch = Arc::new(p);
    window.open_dialog(cx, move |dialog, _, cx| {
        let colors = StudioColors::from_theme(cx);
        let copy = patch.clone();
        let save = patch.clone();
        let owner = frame.clone();
        let copied = frame.clone();
        let copy_receipt = receipt.clone();
        let save_receipt = receipt.clone();
        dialog
            .title(lang.text(TextKey::FrameProbePreview))
            .w(px(700.))
            .child(
                div()
                    .id("frame-probe-preview")
                    .max_h(px(460.))
                    .overflow_y_scrollbar()
                    .flex()
                    .flex_col()
                    .gap(px(8.))
                    .child(studio::selectable(format!(
                        "#{id} · {label} · {}",
                        patch.marker
                    )))
                    .child(studio::selectable(
                        lang.text(TextKey::FrameProbeInstructions),
                    ))
                    .child(studio::selectable(patch.diff.clone())),
            )
            .footer(
                div()
                    .flex()
                    .gap(px(8.))
                    .child(
                        studio::compact_text_button(
                            "frame-probe-copy",
                            lang.text(TextKey::FrameProbeCopy),
                            true,
                            false,
                            &colors,
                        )
                        .on_click(move |_, _, cx| {
                            cx.write_to_clipboard(ClipboardItem::new_string(copy.diff.clone()));
                            copied.update(cx, |f, cx| f.save_probe_receipt(&copy_receipt, cx));
                        }),
                    )
                    .child(
                        studio::compact_text_button(
                            "frame-probe-export",
                            lang.text(TextKey::FrameProbeExport),
                            true,
                            true,
                            &colors,
                        )
                        .on_click(move |_, _, cx| {
                            let p = save.clone();
                            owner.update(cx, |_, cx| {
                                export(
                                    p.diff.clone(),
                                    format!(
                                        "frame-{}.patch",
                                        p.marker.trim_start_matches("APS.Frame/")
                                    ),
                                    Some(save_receipt.clone()),
                                    lang,
                                    cx,
                                )
                            });
                        }),
                    ),
            )
    });
}

pub(crate) fn show_optimization(
    output: &super::ReviewDiff,
    lang: Language,
    frame: Entity<FrameWorkspace>,
    window: &mut Window,
    cx: &mut App,
) {
    let label = output.label.clone();
    let diff = output.diff.clone();
    window.open_dialog(cx, move |dlg, _, cx| {
        let colors = StudioColors::from_theme(cx);
        let copy = diff.clone();
        let save = diff.clone();
        let f = frame.clone();
        dlg.title(lang.text(TextKey::FrameOptReview))
            .w(px(720.))
            .child(
                div()
                    .id("frame-opt-preview")
                    .max_h(px(470.))
                    .overflow_y_scrollbar()
                    .flex()
                    .flex_col()
                    .child(studio::selectable(label.clone()))
                    .child(studio::selectable(lang.text(TextKey::FrameOptReviewHelp)))
                    .child(studio::selectable(diff.clone())),
            )
            .footer(
                div()
                    .flex()
                    .gap(px(8.))
                    .child(
                        studio::compact_text_button(
                            "frame-opt-copy",
                            lang.text(TextKey::FrameProbeCopy),
                            true,
                            false,
                            &colors,
                        )
                        .on_click(move |_, _, cx| {
                            cx.write_to_clipboard(ClipboardItem::new_string(copy.clone()))
                        }),
                    )
                    .child(
                        studio::compact_text_button(
                            "frame-opt-export",
                            lang.text(TextKey::FrameProbeExport),
                            true,
                            true,
                            &colors,
                        )
                        .on_click(move |_, _, cx| {
                            f.update(cx, |_, cx| {
                                export(
                                    save.clone(),
                                    "frame-optimization.patch".into(),
                                    None,
                                    lang,
                                    cx,
                                )
                            })
                        }),
                    ),
            )
    });
}
fn write_patch(path: &Path, diff: &str) -> std::io::Result<()> {
    if path.extension().and_then(|v| v.to_str()) != Some("patch") {
        return Err(std::io::ErrorKind::InvalidInput.into());
    }
    std::fs::OpenOptions::new()
        .write(true)
        .create_new(true)
        .open(path)?
        .write_all(diff.as_bytes())
}
fn export(
    diff: String,
    name: String,
    receipt: Option<super::probe_receipt::Receipt>,
    lang: Language,
    cx: &mut Context<FrameWorkspace>,
) {
    let dir = aps_platform::home_directory().unwrap_or_else(|_| std::env::temp_dir());

    let picker = cx.prompt_for_new_path(&dir, Some(&name));
    cx.spawn(async move |this, cx| {
        let success = match picker.await {
            Ok(Ok(Some(path))) => {
                cx.background_executor()
                    .spawn(async move { write_patch(&path, &diff).is_ok() })
                    .await
            }
            Ok(Ok(None)) => return,
            _ => false,
        };
        let _ = this.update(cx, |f, cx| {
            if success {
                if let Some(r) = &receipt {
                    f.save_probe_receipt(r, cx);
                }
                f.operation_status =
                    Some(super::model::FrameOperationStatus::Exported { file_name: name });
            } else {
                f.error = Some(lang.text(TextKey::FrameProbeExportFailed).into());
            }
            cx.notify();
        });
    })
    .detach();
}
#[cfg(test)]
mod tests {
    use super::*;
    #[::core::prelude::v1::test]
    fn export_cannot_overwrite_source_or_an_existing_patch() {
        let t = aps_platform::TempWorkspace::create(&std::env::temp_dir()).unwrap();
        let p = t.path().join("out.patch");
        assert!(write_patch(&t.path().join("Feed.kt"), "bad").is_err());
        write_patch(&p, "first").unwrap();
        assert!(write_patch(&p, "replace").is_err());
        assert_eq!(std::fs::read_to_string(p).unwrap(), "first");
    }
}

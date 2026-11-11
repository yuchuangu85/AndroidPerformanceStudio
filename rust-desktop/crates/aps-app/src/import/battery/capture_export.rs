//! Explicit export of the exact active live report's immutable capture evidence.
use super::*;
use aps_battery::BatteryCaptureArtifact;
use std::io::{Cursor, Write};
use zip::{CompressionMethod, ZipWriter, write::SimpleFileOptions};

impl ImportWorkspace {
    pub(in crate::import) fn battery_capture_artifact(
        &self,
    ) -> Option<Arc<BatteryCaptureArtifact>> {
        let Some(Session::Battery(report)) = &self.session else {
            return None;
        };
        let live = self
            .battery_shell_ui
            .capture
            .artifact
            .as_ref()
            .filter(|artifact| report.live_capture && artifact.session_id() == report.session_id)
            .cloned();
        live.or_else(|| {
            self.battery_shell_ui
                .restored_artifact
                .as_ref()
                .filter(|(owner, artifact)| {
                    Arc::ptr_eq(owner, report) && artifact.session_id() == report.session_id
                })
                .map(|(_, artifact)| artifact.clone())
        })
    }

    pub(in crate::import) fn save_battery_capture_bundle(&mut self, cx: &mut Context<Self>) {
        if self.kind != Destination::Battery || self.busy || self.export_busy {
            return;
        }
        let Some(artifact) = self.battery_capture_artifact() else {
            self.error = Some(TextKey::BatteryCaptureBundleUnavailable.into());
            cx.notify();
            return;
        };
        self.battery_shell_ui.capture.cancel_export();
        let cancel = CancellationToken::new();
        self.battery_shell_ui.capture.export_cancel = Some(cancel.clone());
        self.export_busy = true;
        self.export_generation += 1;
        let generation = self.export_generation;
        let publication_generation = self.generation;
        let directory = aps_platform::home_directory().unwrap_or_else(|_| PathBuf::from("."));
        let filename = format!("{}.zip", artifact.session_id());
        let picker = cx.prompt_for_new_path(&directory, Some(&filename));
        cx.spawn(async move |this, cx| {
            let chosen = picker.await;
            let Some(path) = this
                .update(cx, |this, cx| {
                    if this.export_generation != generation {
                        return None;
                    }
                    let still_current = this.generation == publication_generation
                        && !cancel.is_cancelled()
                        && this
                            .battery_capture_artifact()
                            .is_some_and(|current| Arc::ptr_eq(&current, &artifact));
                    if !still_current {
                        this.export_busy = false;
                        this.battery_shell_ui.capture.cancel_export();
                        cx.notify();
                        return None;
                    }
                    match chosen {
                        Ok(Ok(Some(path))) => Some(path),
                        Ok(Err(_)) => {
                            this.error = Some(TextKey::ImportUiSavePickerFailed.into());
                            this.export_busy = false;
                            this.battery_shell_ui.capture.cancel_export();
                            cx.notify();
                            None
                        }
                        _ => {
                            this.export_busy = false;
                            this.battery_shell_ui.capture.cancel_export();
                            cx.notify();
                            None
                        }
                    }
                })
                .ok()
                .flatten()
            else {
                return;
            };
            let write_cancel = cancel.clone();
            let result = cx
                .background_executor()
                .spawn(async move {
                    let bytes = encode_bundle(&artifact, &write_cancel)?;
                    if write_cancel.is_cancelled() {
                        return Err("Battery evidence export cancelled".to_owned());
                    }
                    // Reuse the shared create-new writer: no overwrite, 4 MiB cap,
                    // private file permissions and failed-write partial cleanup.
                    export::write_new_file(&path, &bytes)?;
                    Ok::<_, String>(path)
                })
                .await;
            this.update(cx, |this, cx| {
                if this.export_generation != generation {
                    return;
                }
                this.export_busy = false;
                this.battery_shell_ui.capture.export_cancel = None;
                if this.generation != publication_generation || cancel.is_cancelled() {
                    cx.notify();
                    return;
                }
                match result {
                    Ok(path) => {
                        this.notice = Some(UiText::format(
                            TextKey::BatteryCaptureBundleSaved,
                            [("path", path.display().to_string().into())],
                        ))
                    }
                    Err(error) => this.error = Some(error.into()),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }
}

fn encode_bundle(
    artifact: &BatteryCaptureArtifact,
    cancel: &CancellationToken,
) -> Result<Vec<u8>, String> {
    let mut zip = ZipWriter::new(Cursor::new(Vec::new()));
    let options = SimpleFileOptions::default()
        .compression_method(CompressionMethod::Stored)
        .unix_permissions(0o600);
    let mut total = 0usize;
    for (name, bytes) in artifact.entries() {
        if cancel.is_cancelled() {
            return Err("Battery evidence export cancelled".into());
        }
        total = total
            .checked_add(bytes.len())
            .ok_or("Battery export byte budget overflow")?;
        // Reserve enough room for the three fixed ZIP headers and directory.
        if total > export::MAX_EXPORT_BYTES.saturating_sub(4096) {
            return Err("Battery evidence bundle exceeds the 4 MiB export limit".into());
        }
        zip.start_file(name, options)
            .map_err(|_| "Cannot create Battery bundle entry")?;
        zip.write_all(bytes)
            .map_err(|_| "Cannot encode Battery bundle entry")?;
    }
    let bytes = zip
        .finish()
        .map_err(|_| "Cannot finish Battery evidence bundle")?
        .into_inner();
    if cancel.is_cancelled() || bytes.len() > export::MAX_EXPORT_BYTES {
        return Err("Battery evidence export cancelled or exceeded its byte budget".into());
    }
    Ok(bytes)
}

//! Whole-session Battery analysis exports, independent of the visible run page.
use super::*;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum BatterySessionFormat {
    Json,
    Csv,
}

impl BatterySessionFormat {
    fn extension(self) -> &'static str {
        match self {
            Self::Json => "json",
            Self::Csv => "csv",
        }
    }
}

impl ImportWorkspace {
    pub(in crate::import) fn save_battery_session(
        &mut self,
        format: BatterySessionFormat,
        cx: &mut Context<Self>,
    ) {
        if self.kind != Destination::Battery || self.busy || self.export_busy {
            return;
        }
        let Some(Session::Battery(report)) = self.session.clone() else {
            return;
        };
        self.export_busy = true;
        self.export_generation = self.export_generation.wrapping_add(1);
        let generation = self.export_generation;
        let publication_generation = self.generation;
        let cancel = self.cancel.clone();
        let directory = self.path.as_deref().and_then(Path::parent).map_or_else(
            || aps_platform::home_directory().unwrap_or_else(|_| PathBuf::from(".")),
            Path::to_path_buf,
        );
        let filename = format!("{}-analysis.{}", report.session_id, format.extension());
        let picker = cx.prompt_for_new_path(&directory, Some(&filename));
        cx.spawn(async move |this, cx| {
            let chosen = picker.await;
            let path = this
                .update(cx, |this, cx| {
                    if this.export_generation != generation
                        || this.generation != publication_generation
                        || cancel.is_cancelled()
                    {
                        this.export_busy = false;
                        cx.notify();
                        return None;
                    }
                    match chosen {
                        Ok(Ok(Some(path))) => Some(path),
                        Ok(Err(_)) => {
                            this.error = Some(TextKey::ImportUiSavePickerFailed.into());
                            this.export_busy = false;
                            cx.notify();
                            None
                        }
                        _ => {
                            this.export_busy = false;
                            cx.notify();
                            None
                        }
                    }
                })
                .ok()
                .flatten();
            let Some(path) = path else {
                return;
            };
            let result = cx
                .background_executor()
                .spawn(async move {
                    if cancel.is_cancelled() {
                        return Err("Battery session export cancelled".to_owned());
                    }
                    let bytes = match format {
                        BatterySessionFormat::Json => {
                            report.to_json(aps_battery::MAX_INPUT_BYTES)?
                        }
                        BatterySessionFormat::Csv => report.to_csv(aps_battery::MAX_INPUT_BYTES)?,
                    };
                    crate::import::export::write_new_file(&path, &bytes)?;
                    Ok::<_, String>(path)
                })
                .await;
            let _ = this.update(cx, |this, cx| {
                if this.export_generation != generation {
                    return;
                }
                this.export_busy = false;
                match result {
                    Ok(path) => {
                        this.notice = Some(UiText::format(
                            TextKey::NetworkSessionSaved,
                            [("path", path.display().to_string().into())],
                        ))
                    }
                    Err(error) => this.error = Some(error.into()),
                }
                cx.notify();
            });
        })
        .detach();
        cx.notify();
    }
}

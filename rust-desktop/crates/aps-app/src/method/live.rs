use super::capture::{CaptureControl, CaptureError, CaptureTarget, Phase};
use super::*;
use aps_adb::{AdbDevice, AndroidApplicationProcess};
use std::time::Duration;

pub(super) struct LiveCaptureUi {
    serial: Option<String>,
    pid: Option<u32>,
    target: Option<CaptureTarget>,
    seconds: u32,
    pub(super) control: Option<Arc<CaptureControl>>,
    pub(super) status: Option<TextKey>,
    pub(super) warning: Option<String>,
    pub(super) saved: Option<PathBuf>,
}

impl Default for LiveCaptureUi {
    fn default() -> Self {
        Self {
            serial: None,
            pid: None,
            target: None,
            seconds: capture::DEFAULT_SECONDS,
            control: None,
            status: None,
            warning: None,
            saved: None,
        }
    }
}

impl Drop for LiveCaptureUi {
    fn drop(&mut self) {
        if let Some(control) = &self.control {
            control.cancel.cancel();
        }
    }
}

impl MethodWorkspace {
    pub(crate) fn sync_shared_target(
        &mut self,
        serial: Option<String>,
        pid: Option<u32>,
        devices: &[AdbDevice],
        processes: &[AndroidApplicationProcess],
        cx: &mut Context<Self>,
    ) {
        let next = selected_target(serial.as_deref(), pid, devices, processes);
        if self.capture.target != next || self.capture.serial != serial || self.capture.pid != pid {
            if let Some(control) = &self.capture.control {
                control.cancel.cancel();
                self.capture.status = Some(TextKey::MethodCaptureTargetChanged);
            }
            self.capture.serial = serial;
            self.capture.pid = pid;
            self.capture.target = next;
            cx.notify();
        }
    }

    pub(super) fn can_capture(&self) -> bool {
        !self.busy && self.capture.control.is_none() && self.capture.target.is_some()
    }

    pub(crate) fn start_capture(&mut self, cx: &mut Context<Self>) {
        if !self.can_capture() {
            return;
        }
        let target = self.capture.target.clone().expect("capture target checked");
        self.busy = true;
        self.error = None;
        self.capture.warning = None;
        self.capture.saved = None;
        self.capture.status = Some(TextKey::MethodCapturePreparing);
        self.generation = self.generation.wrapping_add(1);
        let generation = self.generation;
        let control = Arc::new(CaptureControl::default());
        self.capture.control = Some(control.clone());
        let seconds = self.capture.seconds;
        let task_control = control.clone();
        let task_target = target.clone();
        let task = cx
            .background_executor()
            .spawn(async move { capture::capture(&task_target, seconds, &task_control) });
        let poll_control = control.clone();
        cx.spawn(async move |this, cx| {
            loop {
                cx.background_executor()
                    .timer(Duration::from_millis(100))
                    .await;
                let keep_polling = this
                    .update(cx, |this, cx| {
                        if this.generation != generation
                            || !this
                                .capture
                                .control
                                .as_ref()
                                .is_some_and(|active| Arc::ptr_eq(active, &poll_control))
                        {
                            return false;
                        }
                        if !poll_control.cancel.is_cancelled() {
                            let phase = phase_key(poll_control.phase());
                            if this.capture.status != Some(phase) {
                                this.capture.status = Some(phase);
                                cx.notify();
                            }
                        }
                        true
                    })
                    .unwrap_or(false);
                if !keep_polling {
                    break;
                }
            }
        })
        .detach();
        cx.spawn(async move |this, cx| {
            let report = task.await;
            this.update(cx, |this, cx| {
                if !this
                    .capture
                    .control
                    .as_ref()
                    .is_some_and(|active| Arc::ptr_eq(active, &control))
                {
                    return;
                }
                this.capture.control = None;
                this.capture.warning = report.cleanup_warning;
                if this.generation != generation {
                    if let Err(CaptureError::Diagnostic(error)) = report.result {
                        this.error = Some(this.capture_error(CaptureError::Diagnostic(error)));
                    }
                    cx.notify();
                    return;
                }
                if control.cancel.is_cancelled() || this.capture.target.as_ref() != Some(&target) {
                    this.busy = false;
                    if this.capture.status != Some(TextKey::MethodCaptureTargetChanged) {
                        this.capture.status = Some(TextKey::MethodCaptureCancelled);
                    }
                    // A failed stop is material even when the user cancelled.
                    if let Err(CaptureError::Diagnostic(error)) = report.result {
                        this.error = Some(this.capture_error(CaptureError::Diagnostic(error)));
                    }
                    this.refresh_language(cx);
                } else {
                    match report.result {
                        Ok(path) => {
                            this.capture.saved = Some(path.clone());
                            this.capture.status = Some(TextKey::MethodCaptureImporting);
                            this.open(path, generation, cx);
                        }
                        Err(error) => {
                            this.busy = false;
                            this.capture.status = None;
                            this.error = Some(this.capture_error(error));
                            this.refresh_language(cx);
                        }
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    fn capture_error(&self, error: CaptureError) -> String {
        let error = match error {
            CaptureError::Cancelled => self
                .language
                .text(TextKey::MethodCaptureCancelled)
                .to_owned(),
            CaptureError::SdkUnknown => self
                .language
                .text(TextKey::MethodCaptureSdkUnknown)
                .to_owned(),
            CaptureError::UnsupportedApi(api) => self
                .language
                .format(TextKey::MethodCaptureUnsupportedApi, &[("api", &api)]),
            CaptureError::Diagnostic(error) => error,
        };
        self.language
            .format(TextKey::MethodCaptureFailed, &[("error", &error)])
    }

    fn stop_capture(&mut self, cx: &mut Context<Self>) {
        if let Some(control) = &self.capture.control {
            control.request_stop();
            cx.notify();
        }
    }

    fn cancel_capture(&mut self, cx: &mut Context<Self>) {
        if let Some(control) = &self.capture.control {
            control.cancel.cancel();
            self.capture.status = Some(TextKey::MethodCaptureCancelled);
            cx.notify();
        }
    }

    pub(super) fn capture_toolbar(&self, cx: &mut Context<Self>) -> impl IntoElement {
        let language = self.language;
        let active = self.capture.control.as_ref();
        let can_stop = active.is_some_and(|control| {
            !control.cancel.is_cancelled()
                && matches!(control.phase(), Phase::Preparing | Phase::Recording)
        });
        let seconds = self.capture.seconds;
        let duration = language.format(
            TextKey::MethodCaptureDurationSeconds,
            &[("seconds", &seconds)],
        );
        div()
            .id("method-capture-toolbar")
            .flex()
            .flex_wrap()
            .items_center()
            .gap_2()
            .child(
                Button::new("method-capture-start")
                    .icon(IconName::Play)
                    .label(language.text(TextKey::MethodCaptureStart))
                    .disabled(!self.can_capture())
                    .on_click(cx.listener(|this, _, _, cx| this.start_capture(cx))),
            )
            .child(
                Button::new("method-capture-stop")
                    .icon(IconName::Square)
                    .tooltip(language.text(TextKey::MethodCaptureStop))
                    .disabled(!can_stop)
                    .on_click(cx.listener(|this, _, _, cx| this.stop_capture(cx))),
            )
            .child(
                Button::new("method-capture-cancel")
                    .icon(IconName::Close)
                    .tooltip(language.text(TextKey::MethodCaptureCancel))
                    .disabled(active.is_none_or(|control| control.cancel.is_cancelled()))
                    .on_click(cx.listener(|this, _, _, cx| this.cancel_capture(cx))),
            )
            .child(
                Button::new("method-duration-minus")
                    .icon(IconName::Minus)
                    .tooltip(duration.clone())
                    .disabled(self.busy || active.is_some() || seconds <= 1)
                    .on_click(cx.listener(|this, _, _, cx| {
                        this.capture.seconds = this.capture.seconds.saturating_sub(5).max(1);
                        cx.notify();
                    })),
            )
            .child(
                div()
                    .w(px(145.))
                    .flex_shrink_0()
                    .child(crate::studio::selectable(duration.clone())),
            )
            .child(
                Button::new("method-duration-plus")
                    .icon(IconName::Plus)
                    .tooltip(duration)
                    .disabled(self.busy || active.is_some() || seconds >= capture::MAX_SECONDS)
                    .on_click(cx.listener(|this, _, _, cx| {
                        this.capture.seconds = (this.capture.seconds + 5).min(capture::MAX_SECONDS);
                        cx.notify();
                    })),
            )
    }

    pub(super) fn capture_status(&self) -> String {
        let target = self.capture.target.as_ref().map(|target| {
            format!(
                "{} / {} ({})",
                target.serial, target.process.name, target.process.pid
            )
        });
        match self.capture.status {
            Some(status) => format!(
                "{}{}",
                self.language.text(status),
                target.map_or(String::new(), |target| format!(" | {target}"))
            ),
            None => target.unwrap_or_else(|| {
                self.language
                    .text(TextKey::MethodCaptureSelectOnlineTarget)
                    .to_owned()
            }),
        }
    }
}

fn selected_target(
    serial: Option<&str>,
    pid: Option<u32>,
    devices: &[AdbDevice],
    processes: &[AndroidApplicationProcess],
) -> Option<CaptureTarget> {
    let serial = serial?;
    if !devices
        .iter()
        .any(|device| device.serial == serial && device.is_online())
    {
        return None;
    }
    let process = processes
        .iter()
        .find(|process| Some(process.pid) == pid && process.pid > 0)?
        .clone();
    Some(CaptureTarget {
        serial: serial.to_owned(),
        process,
    })
}

fn phase_key(phase: Phase) -> TextKey {
    match phase {
        Phase::Preparing => TextKey::MethodCapturePreparing,
        Phase::Recording => TextKey::MethodCaptureRecording,
        Phase::Stopping => TextKey::MethodCaptureStopping,
        Phase::Verifying => TextKey::MethodCaptureVerifying,
        Phase::Pulling => TextKey::MethodCapturePulling,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    #[::core::prelude::v1::test]
    fn shared_target_requires_online_selected_device_and_exact_process() {
        let devices =
            aps_adb::parse_devices("List of devices attached\nonline device\noffline offline\n")
                .unwrap();
        let processes = vec![AndroidApplicationProcess {
            pid: 42,
            parent_pid: 1,
            user: "u0_a1".into(),
            name: "app".into(),
            package_name: "app".into(),
        }];
        assert!(selected_target(Some("online"), Some(42), &devices, &processes).is_some());
        for (serial, pid) in [
            (None, Some(42)),
            (Some("offline"), Some(42)),
            (Some("missing"), Some(42)),
            (Some("online"), None),
            (Some("online"), Some(43)),
        ] {
            assert!(selected_target(serial, pid, &devices, &processes).is_none());
        }
    }
}

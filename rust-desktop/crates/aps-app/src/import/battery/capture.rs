//! Shared-target authorization and publication for read-only Battery capture.
use super::*;
use aps_adb::{AdbClient, AdbDevice, AdbLocator, AndroidApplicationProcess};
use aps_battery::{
    BatteryCaptureArtifact, BatteryCaptureConfig, BatteryCaptureControl, BatteryCaptureMode,
    BatteryCaptureTarget,
};
use gpui_kit::component::radio::Radio;

#[derive(Default)]
pub(super) struct BatteryCaptureUi {
    serial: Option<String>,
    pid: Option<u32>,
    target: Option<BatteryCaptureTarget>,
    config: BatteryCaptureConfig,
    workload_id: String,
    workload_input: Option<Entity<InputState>>,
    workload_subscription: Option<Subscription>,
    revision: u64,
    control: Option<BatteryCaptureControl>,
    pub(super) artifact: Option<Arc<BatteryCaptureArtifact>>,
    pub(super) export_cancel: Option<CancellationToken>,
    pub(super) in_progress: bool,
}
impl Drop for BatteryCaptureUi {
    fn drop(&mut self) {
        if let Some(control) = self.control.take() {
            control.cancel();
        }
        self.cancel_export();
    }
}
impl BatteryCaptureUi {
    pub(super) fn cancel_export(&mut self) {
        if let Some(cancel) = self.export_cancel.take() {
            cancel.cancel();
        }
    }
    pub(super) fn stopping(&self) -> bool {
        self.control
            .as_ref()
            .is_some_and(BatteryCaptureControl::is_stopping)
    }
    fn revoke(&mut self) {
        if let Some(control) = self.control.take() {
            control.cancel();
        }
        self.cancel_export();
        self.in_progress = false;
        self.revision = self.revision.wrapping_add(1);
    }
    fn select(&mut self, serial: Option<String>, pid: Option<u32>) -> bool {
        if self.serial == serial && self.pid == pid {
            return false;
        }
        self.revoke();
        self.serial = serial;
        self.pid = pid;
        self.target = None;
        true
    }
    fn apply_lists(
        &mut self,
        devices: &[AdbDevice],
        processes: &[AndroidApplicationProcess],
    ) -> bool {
        let next = self
            .serial
            .as_ref()
            .zip(self.pid)
            .and_then(|(serial, pid)| {
                let process = processes.iter().find(|process| process.pid == pid)?;
                (pid > 0
                    && devices
                        .iter()
                        .any(|device| &device.serial == serial && device.is_online()))
                .then(|| BatteryCaptureTarget {
                    serial: serial.clone(),
                    pid,
                    process_name: process.name.clone(),
                    package_name: process.package_name.clone(),
                })
            });
        if next == self.target {
            return false;
        }
        self.revoke();
        self.target = next;
        true
    }
}

impl ImportWorkspace {
    pub(crate) fn set_battery_capture_target(
        &mut self,
        serial: Option<String>,
        pid: Option<u32>,
        cx: &mut Context<Self>,
    ) {
        let running = self.battery_shell_ui.capture.in_progress;
        if self.battery_shell_ui.capture.select(serial, pid) && running {
            self.cancel_work(cx);
        }
        cx.notify();
    }
    pub(crate) fn apply_battery_capture_targets(
        &mut self,
        devices: &[AdbDevice],
        processes: &[AndroidApplicationProcess],
        cx: &mut Context<Self>,
    ) {
        let running = self.battery_shell_ui.capture.in_progress;
        if self
            .battery_shell_ui
            .capture
            .apply_lists(devices, processes)
            && running
        {
            self.cancel_work(cx);
        }
        cx.notify();
    }
    /// Parent calls this when opening/closing/importing/cancelling the shared
    /// workspace. It also revokes any pending approval dialog's revision.
    pub(in crate::import) fn invalidate_battery_capture(&mut self) {
        self.battery_shell_ui.capture.revoke();
    }
    pub(in crate::import) fn battery_capture_ready(&self) -> bool {
        self.kind == Destination::Battery
            && !self.busy
            && !self.export_busy
            && !self.battery_shell_ui.capture.in_progress
            && self.battery_shell_ui.capture.target.is_some()
    }
    fn battery_target_text(&self, target: &BatteryCaptureTarget) -> String {
        self.language.format(
            TextKey::BatteryCaptureTarget,
            &[
                ("package", &target.package_name),
                ("process", &target.process_name),
                ("pid", &target.pid),
                ("serial", &target.serial),
            ],
        )
    }
    pub(super) fn battery_capture_tooltip(&self) -> String {
        self.battery_shell_ui.capture.target.as_ref().map_or_else(
            || {
                self.language
                    .text(TextKey::BatteryCaptureSelectTarget)
                    .to_owned()
            },
            |target| self.battery_target_text(target),
        )
    }
    pub(super) fn battery_capture_status(&self) -> Option<String> {
        let capture = &self.battery_shell_ui.capture;
        if !capture.in_progress {
            return None;
        }
        if capture.stopping() {
            return Some(
                self.language
                    .text(TextKey::BatteryCaptureStopRequested)
                    .into(),
            );
        }
        let progress = capture.control.as_ref()?.progress();
        Some(self.language.format(
            TextKey::BatteryCaptureRunning,
            &[
                ("run", &progress.iteration),
                ("runs", &capture.config.run_count()),
                ("samples", &progress.snapshots),
                ("seconds", &progress.elapsed_seconds),
            ],
        ))
    }
    pub(crate) fn capture_battery(&mut self, window: &mut Window, cx: &mut Context<Self>) {
        if !self.battery_capture_ready() {
            self.notice = Some(
                if self.busy {
                    TextKey::ImportLoadingQuerying
                } else {
                    TextKey::BatteryCaptureSelectTarget
                }
                .into(),
            );
            cx.notify();
            return;
        }
        let capture = &self.battery_shell_ui.capture;
        let target = capture.target.clone().expect("checked Battery target");
        let target_text = self.battery_target_text(&target);
        let config = capture.config;
        let workload = capture.workload_id.clone();
        let revision = capture.revision;
        let language = self.language;
        let entity = cx.entity().downgrade();
        window.open_dialog(cx, move |dialog, _, cx| {
            let colors = studio::StudioColors::from_theme(cx);
            let target = target.clone();
            let entity = entity.clone();
            let config_text = format!(
                "{} · {} · {} · {} · {} · {}",
                language.text(mode_key(config.mode)),
                language.format(
                    TextKey::BatteryConfigDurationValue,
                    &[("value", &config.duration_seconds)]
                ),
                language.format(
                    TextKey::BatteryConfigPollingValue,
                    &[("value", &config.polling_interval())]
                ),
                language.format(
                    TextKey::BatteryConfigRunsValue,
                    &[("value", &config.run_count())]
                ),
                language.format(
                    TextKey::BatteryConfigCooldownValue,
                    &[(
                        "value",
                        &if config.mode == BatteryCaptureMode::Repeated {
                            config.cooldown_seconds
                        } else {
                            0
                        }
                    )]
                ),
                language.format(
                    TextKey::BatteryWorkloadValue,
                    &[(
                        "workload",
                        &if workload.trim().is_empty() {
                            language.text(TextKey::BatteryUnknown).to_owned()
                        } else {
                            workload.clone()
                        }
                    )]
                )
            );
            dialog
                .title(language.text(TextKey::BatteryRunExperiment))
                .w(px(560.))
                .centered()
                .child(
                    div()
                        .flex()
                        .flex_col()
                        .gap(px(8.))
                        .child(note(target_text.clone(), &colors))
                        .child(note(config_text, &colors))
                        .child(note(language.text(TextKey::BatteryCaptureConsent), &colors)),
                )
                .footer(
                    div().flex().justify_end().child(
                        studio::compact_text_button(
                            "battery-confirm-capture",
                            language.text(TextKey::BatteryCaptureConfirm),
                            true,
                            true,
                            &colors,
                        )
                        .on_click(move |_, window, cx| {
                            entity
                                .update(cx, |this, cx| {
                                    this.begin_battery_capture(target.clone(), config, revision, cx)
                                })
                                .ok();
                            window.close_dialog(cx);
                        }),
                    ),
                )
        });
    }

    fn begin_battery_capture(
        &mut self,
        target: BatteryCaptureTarget,
        config: BatteryCaptureConfig,
        revision: u64,
        cx: &mut Context<Self>,
    ) {
        if !self.battery_capture_ready()
            || self.battery_shell_ui.capture.revision != revision
            || self.battery_shell_ui.capture.target.as_ref() != Some(&target)
            || self.battery_shell_ui.capture.config != config
        {
            self.notice =
                Some(TextKey::ImportOperationCancelledPreviousPublishedPageRetained.into());
            cx.notify();
            return;
        }
        if let Err(error) = config.validate() {
            self.error = Some(error.into());
            cx.notify();
            return;
        }
        self.cancel.cancel();
        self.cancel = CancellationToken::new();
        self.generation += 1;
        self.export_generation += 1;
        self.export_busy = false;
        self.busy = true;
        self.error = None;
        self.notice = None;
        let generation = self.generation;
        let cancel = self.cancel.clone();
        let control = BatteryCaptureControl::new(cancel.clone());
        let workload = self.battery_shell_ui.capture.workload_id.clone();
        self.battery_shell_ui.capture.control = Some(control.clone());
        self.battery_shell_ui.capture.in_progress = true;
        let task_target = target.clone();
        let task = cx.background_executor().spawn(async move {
            let location = AdbLocator::new()
                .locate(None)
                .map_err(|error| error.to_string())?;
            let output = aps_battery::capture_live_with_workload(
                &AdbClient::new(location),
                &task_target,
                config,
                &control,
                (!workload.trim().is_empty()).then_some(workload.as_str()),
            )?;
            let metrics = report_metrics(&output.report);
            let runs = output.report.runs().len();
            let artifact = Arc::new(output.artifact);
            let report = Arc::new(output.report);
            let session = Session::Battery(report);
            let page = page_for(&session, TraceView::Processes, 0, None, &cancel)
                .map_err(|error| error.render(Language::English))?;
            Ok::<_, String>((session, page, metrics, runs, output.snapshots, artifact))
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.generation != generation
                    || this.battery_shell_ui.capture.revision != revision
                    || !this.battery_shell_ui.capture.in_progress
                    || this.battery_shell_ui.capture.target.as_ref() != Some(&target)
                {
                    return;
                }
                this.busy = false;
                this.battery_shell_ui.capture.in_progress = false;
                this.battery_shell_ui.capture.control = None;
                match result {
                    Ok((session, page, metrics, runs, samples, artifact)) => {
                        this.path = None;
                        this.session_id = match &session {
                            Session::Battery(report) => Some(report.session_id.clone()),
                            _ => None,
                        };
                        this.session = Some(session);
                        this.battery_shell_ui.capture.artifact = Some(artifact);
                        this.page = Some(page);
                        this.battery_metrics = Some(metrics);
                        this.battery_ui.reset();
                        this.battery_workspace_ui = Default::default();
                        this.battery_shell_ui.warnings_offset = 0;
                        this.publish_battery_run_page();
                        this.import_started = None;
                        this.import_duration = None;
                        this.notice = Some(UiText::format(
                            TextKey::BatteryCaptureCompleted,
                            [
                                ("runs", runs.to_string().into()),
                                ("samples", samples.to_string().into()),
                            ],
                        ));
                    }
                    Err(error) => this.error = Some(error.into()),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        let executor = cx.background_executor().clone();
        cx.spawn(async move |this, cx| {
            loop {
                executor.timer(Duration::from_millis(250)).await;
                let done = this
                    .update(cx, |this, cx| {
                        if this.generation != generation
                            || !this.battery_shell_ui.capture.in_progress
                        {
                            return true;
                        }
                        cx.notify();
                        false
                    })
                    .unwrap_or(true);
                if done {
                    break;
                }
            }
        })
        .detach();
        cx.notify();
    }
    pub(crate) fn stop_battery_capture(&mut self, cx: &mut Context<Self>) {
        if let Some(control) = &self.battery_shell_ui.capture.control {
            control.stop_and_analyze();
            cx.notify();
        }
    }

    pub(super) fn battery_capture_configuration(
        &mut self,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if self.battery_shell_ui.capture.workload_input.is_none() {
            let workload = self.battery_shell_ui.capture.workload_id.clone();
            let language = self.language;
            let input = cx.new(|cx| {
                InputState::new(window, cx)
                    .placeholder(language.text(TextKey::BatteryWorkloadId))
                    .default_value(workload)
                    .validate(|value, _| value.len() <= 128 && !value.chars().any(char::is_control))
            });
            self.battery_shell_ui.capture.workload_subscription =
                Some(cx.subscribe(&input, |this, input, event, cx| {
                    if matches!(event, InputEvent::Change)
                        && !this.busy
                        && !this.battery_shell_ui.capture.in_progress
                    {
                        let workload = input.read(cx).value().to_string();
                        if this.battery_shell_ui.capture.workload_id != workload {
                            this.battery_shell_ui.capture.revoke();
                            this.battery_shell_ui.capture.workload_id = workload;
                            cx.notify();
                        }
                    }
                }));
            self.battery_shell_ui.capture.workload_input = Some(input);
        }
        let workload_input = self
            .battery_shell_ui
            .capture
            .workload_input
            .clone()
            .expect("initialized workload input");
        let entity = cx.entity().downgrade();
        let language = self.language;
        window.open_dialog(cx, move |dialog, _, cx| {
            let colors = studio::StudioColors::from_theme(cx);
            let Some(entity) = entity.upgrade() else {
                return dialog;
            };
            let this = entity.read(cx);
            let config = this.battery_shell_ui.capture.config;
            let enabled = !this.busy && !this.battery_shell_ui.capture.in_progress;
            let mut controls = div()
                .flex()
                .flex_col()
                .gap(px(8.))
                .text_size(px(studio::FONT_BODY))
                .child(note(
                    language.text(TextKey::BatteryCaptureConfigurationHint),
                    &colors,
                ))
                .child(note(language.text(TextKey::BatteryWorkloadId), &colors))
                .child(Input::new(&workload_input).disabled(!enabled).w_full());
            let mut modes = div().flex().flex_wrap().gap(px(8.));
            for (index, mode) in [
                BatteryCaptureMode::Interactive,
                BatteryCaptureMode::Timed,
                BatteryCaptureMode::Repeated,
                BatteryCaptureMode::Online,
            ]
            .into_iter()
            .enumerate()
            {
                let entity = entity.clone();
                modes = modes.child(
                    Radio::new(("battery-config-mode", index))
                        .label(language.text(mode_key(mode)))
                        .checked(config.mode == mode)
                        .disabled(!enabled)
                        .on_click(move |_, _, cx| {
                            entity.update(cx, |this, cx| {
                                if this.busy {
                                    return;
                                }
                                this.battery_shell_ui.capture.revoke();
                                this.battery_shell_ui.capture.config.mode = mode;
                                if mode == BatteryCaptureMode::Online {
                                    let config = &mut this.battery_shell_ui.capture.config;
                                    config.polling_seconds = config.polling_seconds.max(30);
                                    config.duration_seconds = config.duration_seconds.max(30);
                                }
                                cx.notify();
                            });
                        }),
                );
            }
            controls = controls.child(modes);
            for (row, key, values, current) in [
                (
                    0usize,
                    TextKey::BatteryConfigDuration,
                    vec![15u32, 30, 60, 120, 300, 600],
                    config.duration_seconds,
                ),
                (
                    1,
                    TextKey::BatteryConfigPolling,
                    vec![5, 10, 15, 30, 60],
                    config.polling_seconds,
                ),
                (
                    2,
                    TextKey::BatteryConfigRuns,
                    vec![1, 3, 5, 10],
                    config.runs,
                ),
                (
                    3,
                    TextKey::BatteryConfigCooldown,
                    vec![0, 15, 30, 60, 120],
                    config.cooldown_seconds,
                ),
            ] {
                let applicable =
                    enabled && (row < 2 || config.mode == BatteryCaptureMode::Repeated);
                let mut choices = div()
                    .flex()
                    .flex_wrap()
                    .gap(px(6.))
                    .child(note(language.text(key), &colors));
                for (index, value) in values.into_iter().enumerate() {
                    let entity = entity.clone();
                    choices = choices.child(
                        studio::compact_text_button(
                            (SharedString::from(format!("battery-option-{row}")), index),
                            value.to_string(),
                            applicable
                                && !(config.mode == BatteryCaptureMode::Online
                                    && row < 2
                                    && value < 30),
                            current == value,
                            &colors,
                        )
                        .on_click(move |_, _, cx| {
                            entity.update(cx, |this, cx| {
                                if this.busy {
                                    return;
                                }
                                let mode = this.battery_shell_ui.capture.config.mode;
                                if row >= 2 && mode != BatteryCaptureMode::Repeated
                                    || mode == BatteryCaptureMode::Online && row < 2 && value < 30
                                {
                                    return;
                                }
                                this.battery_shell_ui.capture.revoke();
                                let config = &mut this.battery_shell_ui.capture.config;
                                match row {
                                    0 => config.duration_seconds = value,
                                    1 => config.polling_seconds = value,
                                    2 => config.runs = value,
                                    _ => config.cooldown_seconds = value,
                                }
                                cx.notify();
                            });
                        }),
                    );
                }
                controls = controls.child(choices);
            }
            dialog
                .title(language.text(TextKey::BatteryConfiguration))
                .w(px(600.))
                .centered()
                .child(controls)
                .footer(
                    div().flex().justify_end().child(
                        studio::compact_text_button(
                            "battery-config-done",
                            language.text(TextKey::BatteryDone),
                            true,
                            true,
                            &colors,
                        )
                        .on_click(|_, window, cx| window.close_dialog(cx)),
                    ),
                )
        });
    }
}

fn mode_key(mode: BatteryCaptureMode) -> TextKey {
    match mode {
        BatteryCaptureMode::Interactive => TextKey::BatteryModeInteractive,
        BatteryCaptureMode::Timed => TextKey::BatteryModeTimed,
        BatteryCaptureMode::Repeated => TextKey::BatteryModeRepeated,
        BatteryCaptureMode::Online => TextKey::BatteryModeOnline,
    }
}

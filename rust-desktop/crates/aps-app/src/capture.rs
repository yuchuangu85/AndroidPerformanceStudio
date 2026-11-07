//! CPU capture is never triggered by navigation or planning. A single explicit
//! confirmation consumes a freshly observed plan and starts the displayed run.
use aps_adb::{AdbClient, AdbLocator};
use aps_capture::{
    CaptureController, CaptureHandle, CaptureOutput, CaptureState, CpuCaptureRequest,
    PlannedCapture,
};
use aps_platform::CancellationToken;
use aps_ui_foundation::{Language, TextKey, typography};
use gpui_kit::{
    component::{
        ActiveTheme, Disableable,
        button::{Button, ButtonVariants},
        input::{Input, InputState},
    },
    prelude::*,
    *,
};
use std::{
    path::PathBuf,
    sync::atomic::{AtomicU64, Ordering},
    time::{Duration, SystemTime, UNIX_EPOCH},
};

static NEXT_RUN: AtomicU64 = AtomicU64::new(0);
/// Trusted shell can subscribe and explicitly import this captured protobuf.
/// Raw perf.data is never mislabeled as an importable protobuf profile.
#[derive(Clone)]
pub struct CaptureCompleted(pub CaptureOutput);
impl EventEmitter<CaptureCompleted> for CaptureWorkspace {}

pub struct CaptureWorkspace {
    language: Language,
    artifact_root: Option<PathBuf>,
    serial: Entity<InputState>,
    pid: Entity<InputState>,
    duration: Entity<InputState>,
    default_duration_seconds: u32,
    default_frequency_hz: u32,
    pending_defaults: Option<(u32, u32)>,
    frequency: Entity<InputState>,
    converter: Entity<InputState>,
    plan: Option<PlannedCapture>,
    handle: Option<CaptureHandle>,
    output: Option<CaptureOutput>,
    busy: bool,
    generation: u64,
    cancel: CancellationToken,
    error: Option<String>,
    cancellation_notice: bool,
    shared_target_serial: Option<String>,
    shared_target_pid: Option<u32>,
}
impl CaptureWorkspace {
    /// Only future unplanned captures use changed defaults; frozen/running plans
    /// retain the values the user confirmed.
    pub fn set_defaults(
        &mut self,
        duration: u32,
        frequency: u32,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if self.busy || self.plan.is_some() {
            self.pending_defaults = Some((duration, frequency));
            return;
        }
        self.pending_defaults = None;
        let old_duration = self.default_duration_seconds;
        let old_frequency = self.default_frequency_hz;
        self.default_duration_seconds = duration;
        self.default_frequency_hz = frequency;
        if duration != old_duration
            && self.duration.read(cx).value().as_str() == old_duration.to_string()
        {
            self.duration.update(cx, |input, cx| {
                input.set_value(duration.to_string(), window, cx)
            });
        }
        if frequency != old_frequency
            && self.frequency.read(cx).value().as_str() == old_frequency.to_string()
        {
            self.frequency.update(cx, |input, cx| {
                input.set_value(frequency.to_string(), window, cx)
            });
        }
        cx.notify();
    }

    pub fn new(
        language: Language,
        artifact_root: Option<PathBuf>,
        default_duration_seconds: u32,
        default_frequency_hz: u32,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Self {
        Self {
            language,
            artifact_root,
            default_duration_seconds,
            default_frequency_hz,
            pending_defaults: None,
            serial: cx.new(|cx| {
                InputState::new(window, cx)
                    .placeholder(language.text(TextKey::CaptureAdbSerialPlaceholder))
            }),
            pid: cx.new(|cx| {
                InputState::new(window, cx)
                    .placeholder(language.text(TextKey::CapturePidPlaceholder))
            }),
            duration: cx.new(|cx| {
                InputState::new(window, cx)
                    .placeholder(language.text(TextKey::CaptureDurationSecondsPlaceholder))
                    .default_value(default_duration_seconds.to_string())
            }),
            frequency: cx.new(|cx| {
                InputState::new(window, cx)
                    .placeholder(language.text(TextKey::CaptureFrequencyHzPlaceholder))
                    .default_value(default_frequency_hz.to_string())
            }),
            converter: cx.new(|cx| {
                InputState::new(window, cx).placeholder(
                    language
                        .text(TextKey::CaptureOptionalAbsoluteHostSimpleperfExecutablePlaceholder),
                )
            }),
            plan: None,
            handle: None,
            output: None,
            busy: false,
            generation: 0,
            cancel: CancellationToken::new(),
            error: None,
            cancellation_notice: false,
            shared_target_serial: None,
            shared_target_pid: None,
        }
    }

    /// The window title bar owns the shared ADB target. The planner follows it
    /// only when the device or PID input is left empty, so explicit text wins.
    pub fn set_shared_target(
        &mut self,
        serial: Option<String>,
        pid: Option<u32>,
        cx: &mut Context<Self>,
    ) {
        if self.shared_target_serial == serial && self.shared_target_pid == pid {
            return;
        }
        self.shared_target_serial = serial;
        self.shared_target_pid = pid;
        cx.notify();
    }
    pub fn set_language(
        &mut self,
        language: Language,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        self.language = language;
        self.serial.update(cx, |state, cx| {
            state.set_placeholder(
                language.text(TextKey::CaptureAdbSerialPlaceholder),
                window,
                cx,
            )
        });
        self.pid.update(cx, |state, cx| {
            state.set_placeholder(language.text(TextKey::CapturePidPlaceholder), window, cx)
        });
        self.duration.update(cx, |state, cx| {
            state.set_placeholder(
                language.text(TextKey::CaptureDurationSecondsPlaceholder),
                window,
                cx,
            )
        });
        self.frequency.update(cx, |state, cx| {
            state.set_placeholder(
                language.text(TextKey::CaptureFrequencyHzPlaceholder),
                window,
                cx,
            )
        });
        self.converter.update(cx, |state, cx| {
            state.set_placeholder(
                language.text(TextKey::CaptureOptionalAbsoluteHostSimpleperfExecutablePlaceholder),
                window,
                cx,
            )
        });
        cx.notify();
    }
    fn plan(&mut self, cx: &mut Context<Self>) {
        if self.busy {
            return;
        }
        let Some(root) = self.artifact_root.clone() else {
            self.error = Some(
                self.language
                    .text(TextKey::DetailsCaptureArtifactRootUnavailable)
                    .into(),
            );
            cx.notify();
            return;
        };
        let request = (|| {
            let parse = |input: &Entity<InputState>, default| -> Result<u32, String> {
                let text = input.read(cx).value();
                let text = text.trim();
                if text.is_empty() {
                    Ok(default)
                } else {
                    text.parse().map_err(|_| {
                        self.language
                            .text(TextKey::DetailsCaptureParametersPositiveDecimalIntegers)
                            .into()
                    })
                }
            };
            let converter = self.converter.read(cx).value().trim().to_owned();
            let typed_serial = self.serial.read(cx).value().trim().to_owned();
            let serial = if typed_serial.is_empty() {
                self.shared_target_serial.clone().unwrap_or_default()
            } else {
                typed_serial
            };
            let typed_pid = self.pid.read(cx).value().trim().to_owned();
            let pid = if typed_pid.is_empty() {
                self.shared_target_pid.unwrap_or(0)
            } else {
                typed_pid.parse().map_err(|_| {
                    self.language
                        .text(TextKey::DetailsCaptureParametersPositiveDecimalIntegers)
                        .to_owned()
                })?
            };
            Ok::<_, String>(CpuCaptureRequest {
                run_id: new_run_id(),
                serial,
                pid,
                duration_seconds: parse(&self.duration, 10)?,
                frequency_hz: parse(&self.frequency, 99)?,
                converter: (!converter.is_empty()).then(|| PathBuf::from(converter)),
            })
        })();
        let request = match request {
            Ok(request) => request,
            Err(error) => {
                self.error = Some(error);
                cx.notify();
                return;
            }
        };
        self.cancel.cancel();
        self.cancel = CancellationToken::new();
        let cancel = self.cancel.clone();
        self.generation += 1;
        let generation = self.generation;
        self.plan = None;
        self.handle = None;
        self.output = None;
        self.error = None;
        self.busy = true;
        self.cancellation_notice = false;
        let task = cx.background_executor().spawn(async move {
            let location = AdbLocator::new()
                .locate(None)
                .map_err(|error| error.to_string())?;
            CaptureController::new(AdbClient::new(location), root)?.plan_cpu(request, &cancel)
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.generation != generation {
                    return;
                }
                this.busy = false;
                match result {
                    Ok(plan) => this.plan = Some(plan),
                    Err(error) => this.error = Some(error),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }
    fn confirm(&mut self, cx: &mut Context<Self>) {
        if self.busy {
            return;
        }
        let Some(plan) = self.plan.take() else {
            return;
        };
        let target = plan.target().clone();
        let confirmed = match plan.confirm(
            &target.serial,
            target.pid,
            &target.boot_id,
            target.start_ticks,
        ) {
            Ok(confirmed) => confirmed,
            Err(error) => {
                self.error = Some(error);
                cx.notify();
                return;
            }
        };
        let handle = confirmed.handle();
        self.handle = Some(handle.clone());
        self.busy = true;
        self.error = None;
        self.generation += 1;
        let generation = self.generation;
        let task = cx
            .background_executor()
            .spawn(async move { confirmed.run() });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.generation != generation {
                    return;
                }
                this.busy = false;
                match result {
                    Ok(output) => {
                        this.output = Some(output.clone());
                        cx.emit(CaptureCompleted(output));
                    }
                    Err(error) => this.error = Some(error),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        // Poll only the shared status; never execute device commands here.
        let executor = cx.background_executor().clone();
        cx.spawn(async move |this, cx| {
            loop {
                executor.timer(Duration::from_millis(150)).await;
                let done = this
                    .update(cx, |this, cx| {
                        if this.generation != generation || !this.busy {
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
    fn cancel(&mut self, cx: &mut Context<Self>) {
        self.cancel.cancel();
        if let Some(handle) = &self.handle {
            handle.cancel();
            self.cancellation_notice = true;
        }
        self.plan = None;
        cx.notify();
    }
    fn clear(&mut self, cx: &mut Context<Self>) {
        if self.busy {
            self.cancel(cx);
            return;
        }
        self.plan = None;
        self.output = None;
        self.error = None;
        self.handle = None;
        // Cancellation warning intentionally remains until the next plan.
        cx.notify();
    }
}
impl Drop for CaptureWorkspace {
    fn drop(&mut self) {
        self.cancel.cancel();
        if let Some(handle) = &self.handle {
            handle.cancel();
        }
    }
}
fn new_run_id() -> String {
    let micros = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |d| d.as_micros());
    format!(
        "r{micros:x}-{:x}-{:x}",
        std::process::id(),
        NEXT_RUN.fetch_add(1, Ordering::Relaxed)
    )
}
impl Render for CaptureWorkspace {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        if !self.busy
            && self.plan.is_none()
            && let Some((duration, frequency)) = self.pending_defaults.take()
        {
            self.set_defaults(duration, frequency, window, cx);
        }
        let lang = self.language;
        let busy = self.busy;
        let mut body = div()
            .flex()
            .flex_col()
            .gap_3()
            .p_4()
            .child(
                div()
                    .text_size(px(typography::TITLE))
                    .child(crate::studio::selectable(
                        lang.text(TextKey::CaptureCpuCapture),
                    )),
            )
            .child(crate::studio::selectable(
                lang.text(TextKey::CaptureInspectIsReadOnlyOnlyTheExplicit),
            ))
            .child(
                div()
                    .flex()
                    .gap_2()
                    .child(Input::new(&self.serial).disabled(busy))
                    .child(Input::new(&self.pid).disabled(busy)),
            )
            .child(
                div()
                    .flex()
                    .gap_2()
                    .child(Input::new(&self.duration).disabled(busy))
                    .child(Input::new(&self.frequency).disabled(busy)),
            )
            .child(Input::new(&self.converter).disabled(busy))
            .child(
                div()
                    .flex()
                    .gap_2()
                    .child(
                        Button::new("capture-plan")
                            .primary()
                            .label(lang.text(TextKey::CaptureInspectTargetPreparePlan))
                            .disabled(busy || self.artifact_root.is_none())
                            .on_click(cx.listener(|this, _, _, cx| this.plan(cx))),
                    )
                    .child(
                        Button::new("capture-cancel")
                            .label(lang.text(TextKey::CaptureCancelHostOperation))
                            .disabled(!busy)
                            .on_click(cx.listener(|this, _, _, cx| this.cancel(cx))),
                    )
                    .child(
                        Button::new("capture-clear")
                            .label(lang.text(TextKey::CaptureClearResult))
                            .disabled(busy)
                            .on_click(cx.listener(|this, _, _, cx| this.clear(cx))),
                    ),
            )
            .children(self.error.clone().map(|error| {
                div()
                    .text_color(cx.theme().danger)
                    .child(crate::studio::selectable(error))
            }));
        if busy {
            body = body.child(self.handle.as_ref().map_or_else(
                || lang.text(TextKey::CaptureObservingTarget).to_owned(),
                |handle| {
                    lang.format(
                        TextKey::DetailsCaptureState,
                        &[("state", &lang.text(capture_state_key(handle.state())))],
                    )
                },
            ));
        }
        if let Some(plan) = &self.plan {
            let target = plan.target();
            let request = plan.request();
            body = body
                .child(lang.format(
                    TextKey::DetailsCaptureTarget,
                    &[
                        ("serial", &target.serial),
                        ("pid", &target.pid),
                        ("process_name", &target.process_name),
                    ],
                ))
                .child(lang.format(
                    TextKey::DetailsCaptureProcessIdentity,
                    &[
                        ("boot_id", &target.boot_id),
                        ("start_ticks", &target.start_ticks),
                    ],
                ))
                .child(lang.format(
                    TextKey::DetailsCaptureSampling,
                    &[
                        ("duration_seconds", &request.duration_seconds),
                        ("frequency_hz", &request.frequency_hz),
                    ],
                ))
                .child(lang.format(
                    TextKey::DetailsCaptureRemotePath,
                    &[("path", &format!("{}/perf.data", plan.remote_directory()))],
                ))
                .child(lang.format(
                    TextKey::DetailsCaptureLocalPath,
                    &[("path", &plan.local_directory().display())],
                ))
                .child(lang.format(
                    TextKey::DetailsCaptureConverter,
                    &[(
                        "converter",
                        &request.converter.as_ref().map_or_else(
                            || lang.text(TextKey::DetailsCaptureNoConverter).to_owned(),
                            |path| path.display().to_string(),
                        ),
                    )],
                ))
                .child(
                    div()
                        .text_color(cx.theme().danger)
                        .child(crate::studio::selectable(
                            lang.text(TextKey::CaptureConfirmingCreatesAPrivateLocalRunAnd),
                        )),
                )
                .child(
                    Button::new("capture-confirm")
                        .primary()
                        .label(lang.text(TextKey::CaptureConfirmDisplayedTargetAndStartCapture))
                        .disabled(busy || plan.is_expired())
                        .on_click(cx.listener(|this, _, _, cx| this.confirm(cx))),
                );
        }
        if self.cancellation_notice {
            body = body.child(div().text_color(cx.theme().danger).child(
                crate::studio::selectable(
                    lang.text(TextKey::CaptureCaptureCancellationRequestedTheExactRemotePid),
                ),
            ));
        }
        if let Some(output) = &self.output {
            body = body
                .child(lang.format(
                    TextKey::DetailsCaptureRawArtifact,
                    &[
                        ("path", &output.raw.path.display()),
                        ("size_bytes", &output.raw.size_bytes),
                        ("digest", &output.raw.digest),
                    ],
                ))
                .child(lang.format(
                    TextKey::DetailsCaptureRetainedRemote,
                    &[("path", &output.retained_remote_path)],
                ));
            if let Some(pb) = &output.protobuf {
                body = body.child(lang.format(
                    TextKey::DetailsCaptureProtobufArtifact,
                    &[
                        ("path", &pb.path.display()),
                        ("size_bytes", &pb.size_bytes),
                        ("digest", &pb.digest),
                    ],
                ));
            }
        }
        body
    }
}
fn capture_state_key(state: CaptureState) -> TextKey {
    match state {
        CaptureState::Planned => TextKey::DetailsCaptureStatePlanned,
        CaptureState::Confirmed => TextKey::DetailsCaptureStateConfirmed,
        CaptureState::Rechecking => TextKey::DetailsCaptureStateRechecking,
        CaptureState::Preparing => TextKey::DetailsCaptureStatePreparing,
        CaptureState::Recording => TextKey::DetailsCaptureStateRecording,
        CaptureState::Pulling => TextKey::DetailsCaptureStatePulling,
        CaptureState::Converting => TextKey::DetailsCaptureStateConverting,
        CaptureState::Stopping => TextKey::DetailsCaptureStateStopping,
        CaptureState::Complete => TextKey::DetailsCaptureStateComplete,
        CaptureState::Cancelled => TextKey::DetailsCaptureStateCancelled,
        CaptureState::CancellationUnverified => TextKey::DetailsCaptureStateCancellationUnverified,
        CaptureState::Failed => TextKey::DetailsCaptureStateFailed,
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[::core::prelude::v1::test]
    fn run_ids_are_distinct_and_restricted_to_safe_characters() {
        let first = new_run_id();
        let second = new_run_id();
        assert_ne!(first, second);
        for run in [first, second] {
            assert!(run.len() <= 64);
            assert!(run.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-'));
        }
    }
}

/// In-window menu bar commands for the Capture workspace. Each screen publishes
/// only the commands its own functionality supports.
#[derive(Clone, PartialEq, Eq)]
pub(crate) enum CaptureMenuCommand {
    /// Inspect the target and freeze the displayed plan. Read-only.
    Plan,
    /// Consume the fresh plan and start the one explicit capture.
    Confirm,
    /// Stop the running capture at the exact remote PID.
    Cancel,
    /// Drop the retained plan/result without starting any device work.
    Clear,
}

impl crate::shell::menubar::MenuHost for CaptureWorkspace {
    type Command = CaptureMenuCommand;

    fn menu_groups(
        &self,
        _language: Language,
    ) -> Vec<crate::shell::menubar::MenuGroup<Self::Command>> {
        use crate::shell::menubar::MenuGroup;
        let busy = self.busy;
        // Same preconditions as the page buttons: a plan needs a writable
        // artifact root and confirmation needs an unexpired plan.
        let can_plan = !busy && self.artifact_root.is_some();
        let can_confirm = !busy && self.plan.as_ref().is_some_and(|plan| !plan.is_expired());
        vec![
            MenuGroup::new(TextKey::ShellMenuSession)
                .item_when(
                    can_plan,
                    TextKey::CaptureInspectTargetPreparePlan,
                    CaptureMenuCommand::Plan,
                )
                .item_when(
                    can_confirm,
                    TextKey::CaptureConfirmDisplayedTargetAndStartCapture,
                    CaptureMenuCommand::Confirm,
                )
                .separator()
                .item_when(
                    busy,
                    TextKey::CaptureCancelHostOperation,
                    CaptureMenuCommand::Cancel,
                )
                .item_when(
                    !busy,
                    TextKey::CaptureClearResult,
                    CaptureMenuCommand::Clear,
                ),
        ]
    }

    fn run_menu_command(
        &mut self,
        command: Self::Command,
        _window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        match command {
            CaptureMenuCommand::Plan => self.plan(cx),
            CaptureMenuCommand::Confirm => self.confirm(cx),
            CaptureMenuCommand::Cancel => self.cancel(cx),
            CaptureMenuCommand::Clear => self.clear(cx),
        }
    }
}

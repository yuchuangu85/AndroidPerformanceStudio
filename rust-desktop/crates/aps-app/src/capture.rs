//! CPU capture is never triggered by navigation or planning. A single explicit
//! confirmation consumes a freshly observed plan and starts the displayed run.
use crate::notifications::{NoticeSnapshot, NoticeSource};
use aps_adb::{AdbClient, AdbLocator};
use aps_capture::{
    CallGraphMode, CaptureCompletion, CaptureController, CaptureDuration, CaptureHandle,
    CaptureOutput, CaptureState, CpuCaptureRequest, EventScope, PlannedCapture,
    SamplingConfiguration, SamplingRate, SamplingTemplate,
};
use aps_platform::CancellationToken;
use aps_ui_foundation::{Language, TextKey, typography};
use gpui_kit::{
    component::{
        ActiveTheme, Disableable, IconName,
        button::{Button, ButtonVariants},
        input::{Input, InputEvent, InputState},
        radio::Radio,
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

pub(crate) struct CaptureDefaultsChanged;
impl EventEmitter<CaptureDefaultsChanged> for CaptureWorkspace {}

/// A snapshot of the shared input entities, not a second editable settings store.
/// Invalid text stays in those inputs until the user changes it or applies a template.
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct CpuCaptureDraft {
    pub event: String,
    pub rate: String,
    pub duration: String,
    pub period_mode: bool,
    pub call_graph: CallGraphMode,
    pub scope: EventScope,
    pub template: Option<SamplingTemplate>,
}

impl CpuCaptureDraft {
    pub(crate) fn new(duration: u32, frequency: u32) -> Self {
        let configuration = SamplingConfiguration::default();
        Self {
            event: configuration.event,
            rate: frequency.to_string(),
            duration: duration.to_string(),
            period_mode: false,
            call_graph: configuration.call_graph,
            scope: configuration.scope,
            template: None,
        }
    }

    pub(crate) fn apply_template(&mut self, template: SamplingTemplate, app_scoped: bool) {
        let configuration = template.create(app_scoped);
        self.event = configuration.event;
        self.rate = configuration.rate.value().to_string();
        self.duration = SamplingTemplate::DURATION_SECONDS.to_string();
        self.period_mode = configuration.rate.is_period();
        self.call_graph = configuration.call_graph;
        self.scope = configuration.scope;
        self.template = Some(template);
    }

    fn sampling(&self) -> Result<SamplingConfiguration, TextKey> {
        let value = positive_decimal(&self.rate).ok_or(if self.period_mode {
            TextKey::CaptureInvalidPeriod
        } else {
            TextKey::CaptureInvalidFrequency
        })?;
        let rate = if self.period_mode {
            if value > i64::MAX as u64 {
                return Err(TextKey::CaptureInvalidPeriod);
            }
            SamplingRate::Period(value)
        } else {
            if value > 4000 {
                return Err(TextKey::CaptureInvalidFrequency);
            }
            SamplingRate::Frequency(value as u32)
        };
        let configuration = SamplingConfiguration {
            event: self.event.clone(),
            rate,
            call_graph: self.call_graph,
            scope: self.scope,
        };
        configuration
            .validate()
            .map_err(|_| TextKey::CaptureInvalidEvent)?;
        Ok(configuration)
    }

    pub(crate) fn validate(&self) -> Result<(), TextKey> {
        self.sampling()?;
        self.capture_duration()?;
        Ok(())
    }

    fn capture_duration(&self) -> Result<CaptureDuration, TextKey> {
        CaptureDuration::parse(&self.duration).map_err(|_| TextKey::CaptureInvalidDuration)
    }

    /// Only the existing duration/Hz preferences are persisted. Period counts
    /// never become frequency defaults. Manual/fractional durations stay live
    /// without being rounded into the existing whole-second preference.
    pub(crate) fn persistable_defaults(&self) -> (Option<u32>, Option<u32>) {
        let frequency = if self.period_mode {
            None
        } else {
            positive_decimal(&self.rate)
                .filter(|value| *value <= 4000)
                .map(|value| value as u32)
        };
        (
            self.capture_duration()
                .ok()
                .and_then(CaptureDuration::whole_seconds),
            frequency,
        )
    }

    fn update_defaults(&mut self, previous: (u32, u32), next: (u32, u32)) {
        if self.template.is_some() {
            return;
        }
        if self.persistable_defaults().0.is_some() && self.duration == previous.0.to_string() {
            self.duration = next.0.to_string();
        }
        if self.persistable_defaults().1.is_some() && self.rate == previous.1.to_string() {
            self.rate = next.1.to_string();
        }
    }

    pub(crate) fn request(
        &self,
        run_id: String,
        serial: String,
        pid: u32,
        converter: Option<PathBuf>,
    ) -> Result<CpuCaptureRequest, TextKey> {
        Ok(CpuCaptureRequest {
            run_id,
            serial,
            pid,
            duration: self.capture_duration()?,
            sampling: self.sampling()?,
            converter,
        })
    }
}

fn positive_decimal(text: &str) -> Option<u64> {
    (!text.is_empty() && text.bytes().all(|byte| byte.is_ascii_digit()))
        .then(|| text.parse::<u64>().ok())
        .flatten()
        .filter(|value| *value > 0)
}

#[derive(Debug, PartialEq, Eq)]
struct CaptureControls {
    can_stop: bool,
    can_cancel: bool,
}

fn capture_controls(busy: bool, state: Option<&CaptureState>) -> CaptureControls {
    CaptureControls {
        can_stop: busy && state == Some(&CaptureState::Recording),
        can_cancel: busy
            && !matches!(
                state,
                Some(
                    CaptureState::Complete
                        | CaptureState::Cancelled
                        | CaptureState::Failed
                        | CaptureState::CancellationUnverified
                        | CaptureState::StopUnverified
                )
            ),
    }
}

fn converter_prefill(
    current: &str,
    selected: Option<PathBuf>,
    busy: bool,
    frozen: bool,
) -> Option<PathBuf> {
    if busy || frozen || !current.trim().is_empty() {
        None
    } else {
        selected
    }
}

fn can_publish_plan(current_generation: u64, generation: u64, cancel: &CancellationToken) -> bool {
    current_generation == generation && !cancel.is_cancelled()
}

fn resolve_target(
    typed_serial: &str,
    typed_pid: &str,
    shared_serial: Option<&str>,
    shared_pid: Option<u32>,
) -> Result<(String, u32), TextKey> {
    let serial = if typed_serial.trim().is_empty() {
        shared_serial.unwrap_or_default()
    } else {
        typed_serial.trim()
    };
    let pid = if typed_pid.trim().is_empty() {
        shared_pid
    } else {
        positive_decimal(typed_pid.trim()).and_then(|value| value.try_into().ok())
    };
    let pid = pid
        .filter(|value| *value > 0)
        .ok_or(TextKey::CaptureInvalidTarget)?;
    aps_adb::validate_serial(serial).map_err(|_| TextKey::CaptureInvalidTarget)?;
    Ok((serial.to_owned(), pid))
}

fn target_is_app_scoped(
    serial: &str,
    pid: u32,
    selected_serial: Option<&str>,
    processes: &[aps_adb::AndroidApplicationProcess],
) -> bool {
    selected_serial == Some(serial)
        && processes
            .iter()
            .any(|process| process.pid == pid && !process.package_name.is_empty())
}

pub struct CaptureWorkspace {
    language: Language,
    artifact_root: Option<PathBuf>,
    serial: Entity<InputState>,
    pid: Entity<InputState>,
    duration: Entity<InputState>,
    default_duration_seconds: u32,
    default_frequency_hz: u32,
    pending_defaults: Option<(u32, u32)>,
    event: Entity<InputState>,
    rate: Entity<InputState>,
    period_mode: bool,
    call_graph: CallGraphMode,
    scope: EventScope,
    template: Option<SamplingTemplate>,
    converter: Entity<InputState>,
    plan: Option<PlannedCapture>,
    frozen_request: Option<CpuCaptureRequest>,
    frozen_template: Option<SamplingTemplate>,
    handle: Option<CaptureHandle>,
    output: Option<CaptureOutput>,
    busy: bool,
    generation: u64,
    cancel: CancellationToken,
    error: Option<String>,
    cancellation_notice: bool,
    shared_target_serial: Option<String>,
    shared_target_pid: Option<u32>,
    _subscriptions: Vec<Subscription>,
}
impl CaptureWorkspace {
    pub(crate) fn draft(&self, cx: &App) -> CpuCaptureDraft {
        CpuCaptureDraft {
            event: self.event.read(cx).value().to_string(),
            rate: self.rate.read(cx).value().to_string(),
            duration: self.duration.read(cx).value().to_string(),
            period_mode: self.period_mode,
            call_graph: self.call_graph,
            scope: self.scope,
            template: self.template,
        }
    }

    pub(crate) fn sampling_inputs(&self) -> [Entity<InputState>; 3] {
        [self.event.clone(), self.rate.clone(), self.duration.clone()]
    }

    pub(crate) fn can_edit_configuration(&self) -> bool {
        !self.busy
    }

    pub(crate) fn set_converter_if_empty(
        &mut self,
        selected: Option<PathBuf>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        let selected = converter_prefill(
            self.converter.read(cx).value().as_str(),
            selected,
            self.busy,
            self.plan.is_some() || self.frozen_request.is_some(),
        );
        if let Some(path) = selected {
            self.converter.update(cx, |input, cx| {
                input.set_value(path.to_string_lossy().into_owned(), window, cx)
            });
            cx.notify();
        }
    }

    fn controls(&self) -> CaptureControls {
        let state = self.handle.as_ref().map(CaptureHandle::state);
        let mut controls = capture_controls(self.busy, state.as_ref());
        controls.can_stop &= self.handle.as_ref().is_some_and(CaptureHandle::can_stop);
        controls
    }

    fn draft_target(&self, cx: &App) -> Result<(String, u32), TextKey> {
        resolve_target(
            self.serial.read(cx).value().as_str(),
            self.pid.read(cx).value().as_str(),
            self.shared_target_serial.as_deref(),
            self.shared_target_pid,
        )
    }

    pub(crate) fn has_target(&self, cx: &App) -> bool {
        self.draft_target(cx).is_ok()
    }

    /// Only the existing title-bar discovery can establish app-scoped defaults.
    /// A manually entered, undiscovered PID is not assumed to be an app.
    pub(crate) fn template_app_scoped(
        &self,
        selected_serial: Option<&str>,
        processes: &[aps_adb::AndroidApplicationProcess],
        cx: &App,
    ) -> bool {
        self.draft_target(cx).is_ok_and(|(serial, pid)| {
            target_is_app_scoped(&serial, pid, selected_serial, processes)
        })
    }

    /// The editable draft never mutates a prepared or running request.
    pub(crate) fn set_call_graph(&mut self, mode: CallGraphMode, cx: &mut Context<Self>) {
        if self.can_edit_configuration() && self.call_graph != mode {
            self.invalidate_prepared_plan();
            self.call_graph = mode;
            cx.notify();
        }
    }

    pub(crate) fn set_scope(&mut self, scope: EventScope, cx: &mut Context<Self>) {
        if self.can_edit_configuration() && self.scope != scope {
            self.invalidate_prepared_plan();
            self.scope = scope;
            cx.notify();
        }
    }

    pub(crate) fn set_period_mode(&mut self, period: bool, cx: &mut Context<Self>) {
        if self.can_edit_configuration() && self.period_mode != period {
            self.invalidate_prepared_plan();
            self.period_mode = period;
            cx.emit(CaptureDefaultsChanged);
            cx.notify();
        }
    }

    pub(crate) fn apply_template(
        &mut self,
        template: SamplingTemplate,
        app_scoped: bool,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        if !self.can_edit_configuration() {
            return;
        }
        self.invalidate_prepared_plan();
        let mut draft = self.draft(cx);
        draft.apply_template(template, app_scoped);
        self.period_mode = draft.period_mode;
        self.call_graph = draft.call_graph;
        self.scope = draft.scope;
        self.template = draft.template;
        self.write_inputs(&draft, window, cx);
        cx.notify();
    }

    fn write_inputs(&self, draft: &CpuCaptureDraft, window: &mut Window, cx: &mut Context<Self>) {
        for (input, value) in [
            (&self.event, &draft.event),
            (&self.rate, &draft.rate),
            (&self.duration, &draft.duration),
        ] {
            if input.read(cx).value().as_str() != value.as_str() {
                input.update(cx, |input, cx| input.set_value(value.clone(), window, cx));
            }
        }
    }

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
        let mut draft = self.draft(cx);
        draft.update_defaults(
            (self.default_duration_seconds, self.default_frequency_hz),
            (duration, frequency),
        );
        self.default_duration_seconds = duration;
        self.default_frequency_hz = frequency;
        self.write_inputs(&draft, window, cx);
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
        let draft = CpuCaptureDraft::new(default_duration_seconds, default_frequency_hz);
        let event = cx.new(|cx| {
            InputState::new(window, cx)
                .placeholder(language.text(TextKey::SettingsUiEvent))
                .default_value(draft.event.clone())
        });
        let rate = cx.new(|cx| {
            InputState::new(window, cx)
                .placeholder(language.text(TextKey::SettingsUiSampling))
                .default_value(draft.rate.clone())
        });
        let duration = cx.new(|cx| {
            InputState::new(window, cx)
                .placeholder(language.text(TextKey::CaptureDurationSecondsPlaceholder))
                .default_value(draft.duration.clone())
        });
        let serial = cx.new(|cx| {
            InputState::new(window, cx)
                .placeholder(language.text(TextKey::CaptureAdbSerialPlaceholder))
        });
        let pid = cx.new(|cx| {
            InputState::new(window, cx).placeholder(language.text(TextKey::CapturePidPlaceholder))
        });
        let converter = cx.new(|cx| {
            InputState::new(window, cx).placeholder(
                language.text(TextKey::CaptureOptionalAbsoluteHostSimpleperfExecutablePlaceholder),
            )
        });
        let subscriptions = [&event, &rate, &duration, &serial, &pid, &converter]
            .into_iter()
            .map(|input| {
                cx.subscribe(input, |this, _, event: &InputEvent, cx| {
                    if matches!(event, InputEvent::Change) {
                        this.invalidate_plan_on_draft_change(cx);
                    }
                })
            })
            .collect();
        Self {
            language,
            artifact_root,
            default_duration_seconds,
            default_frequency_hz,
            pending_defaults: None,
            call_graph: draft.call_graph,
            scope: draft.scope,
            template: None,
            period_mode: false,
            event,
            rate,
            duration,
            serial,
            pid,
            converter,
            plan: None,
            frozen_request: None,
            frozen_template: None,
            handle: None,
            output: None,
            busy: false,
            generation: 0,
            cancel: CancellationToken::new(),
            error: None,
            cancellation_notice: false,
            shared_target_serial: None,
            shared_target_pid: None,
            _subscriptions: subscriptions,
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
        self.invalidate_prepared_plan();
        self.shared_target_serial = serial;
        self.shared_target_pid = pid;
        cx.notify();
    }

    fn invalidate_prepared_plan(&mut self) {
        if self.busy && self.handle.is_some() {
            return;
        }
        if self.plan.is_some() || (self.busy && self.handle.is_none()) {
            self.generation = self.generation.wrapping_add(1);
            self.cancel.cancel();
            self.busy = false;
            self.plan = None;
            self.frozen_request = None;
            self.frozen_template = None;
            self.error = None;
        }
    }

    fn invalidate_plan_on_draft_change(&mut self, cx: &mut Context<Self>) {
        self.invalidate_prepared_plan();
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
        self.event.update(cx, |state, cx| {
            state.set_placeholder(language.text(TextKey::SettingsUiEvent), window, cx)
        });
        self.rate.update(cx, |state, cx| {
            state.set_placeholder(language.text(TextKey::SettingsUiSampling), window, cx)
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
        let draft = self.draft(cx);
        let request = (|| {
            let converter = self.converter.read(cx).value().trim().to_owned();
            let (serial, pid) = self.draft_target(cx)?;
            draft.request(
                new_run_id(),
                serial,
                pid,
                (!converter.is_empty()).then(|| PathBuf::from(converter)),
            )
        })();
        let request = match request {
            Ok(request) => request,
            Err(error) => {
                self.error = Some(self.language.text(error).into());
                cx.notify();
                return;
            }
        };
        self.cancel.cancel();
        self.cancel = CancellationToken::new();
        let cancel = self.cancel.clone();
        let publication_cancel = cancel.clone();
        self.generation += 1;
        let generation = self.generation;
        self.plan = None;
        self.frozen_request = None;
        self.frozen_template = None;
        self.handle = None;
        self.error = None;
        self.busy = true;
        self.cancellation_notice = false;
        let task = cx.background_executor().spawn(async move {
            let mut request = request;
            if request.converter.is_none() {
                request.converter = crate::cpu::resolve_simpleperf(None).ok();
            }
            let location = AdbLocator::new()
                .locate(None)
                .map_err(|error| error.to_string())?;
            CaptureController::new(AdbClient::new(location), root)?.plan_cpu(request, &cancel)
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if !can_publish_plan(this.generation, generation, &publication_cancel) {
                    if this.generation == generation {
                        this.busy = false;
                        cx.notify();
                    }
                    return;
                }
                this.busy = false;
                match result {
                    Ok(plan) => {
                        this.frozen_request = Some(plan.request().clone());
                        this.frozen_template = draft.template;
                        this.plan = Some(plan);
                    }
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
    fn stop(&mut self, cx: &mut Context<Self>) {
        if self.busy && self.handle.as_ref().is_some_and(CaptureHandle::stop) {
            cx.notify();
        }
    }
    fn cancel(&mut self, cx: &mut Context<Self>) {
        let accepted = if let Some(handle) = &self.handle {
            handle.cancel()
        } else if self.busy && !self.cancel.is_cancelled() {
            self.cancel.cancel();
            true
        } else {
            false
        };
        if !accepted {
            return;
        }
        self.cancellation_notice = true;
        self.plan = None;
        if self.handle.is_none() {
            self.frozen_request = None;
            self.frozen_template = None;
        }
        cx.notify();
    }
    fn clear(&mut self, cx: &mut Context<Self>) {
        if self.busy {
            self.cancel(cx);
            return;
        }
        self.plan = None;
        self.frozen_request = None;
        self.frozen_template = None;
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
        let controls = self.controls();
        let draft = self.draft(cx);
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
                div().flex().flex_wrap().gap_2().children(
                    [false, true]
                        .into_iter()
                        .enumerate()
                        .map(|(index, period)| {
                            Radio::new(("capture-rate-mode", index))
                                .label(lang.text(if period {
                                    TextKey::SettingsUiPeriod
                                } else {
                                    TextKey::SettingsUiFrequency
                                }))
                                .checked(draft.period_mode == period)
                                .disabled(busy)
                                .on_click(cx.listener(move |this, checked: &bool, _, cx| {
                                    if *checked {
                                        this.set_period_mode(period, cx);
                                    }
                                }))
                        }),
                ),
            )
            .child(lang.text(TextKey::SettingsUiEvent))
            .child(Input::new(&self.event).disabled(busy))
            .child(
                div()
                    .flex()
                    .flex_wrap()
                    .gap_2()
                    .child(
                        div()
                            .flex_1()
                            .min_w(px(160.))
                            .child(lang.text(TextKey::SettingsUiDurationSecondsHint))
                            .child(Input::new(&self.duration).disabled(busy)),
                    )
                    .child(
                        div()
                            .flex_1()
                            .min_w(px(160.))
                            .child(lang.text(if draft.period_mode {
                                TextKey::SettingsUiEventsPerSample
                            } else {
                                TextKey::CaptureFrequencyHzPlaceholder
                            }))
                            .child(Input::new(&self.rate).disabled(busy)),
                    ),
            )
            .child(lang.text(TextKey::CaptureDurationLimit))
            .when_some(draft.validate().err(), |body, error| {
                body.child(div().text_color(cx.theme().danger).child(lang.text(error)))
            })
            .child(Input::new(&self.converter).disabled(busy))
            .child(
                div()
                    .flex()
                    .flex_wrap()
                    .gap_2()
                    .child(
                        Button::new("capture-plan")
                            .primary()
                            .label(lang.text(TextKey::CaptureInspectTargetPreparePlan))
                            .disabled(busy || self.artifact_root.is_none())
                            .on_click(cx.listener(|this, _, _, cx| this.plan(cx))),
                    )
                    .child(
                        Button::new("capture-stop")
                            .icon(IconName::Square)
                            .label(lang.text(TextKey::CaptureStopAndSave))
                            .disabled(!controls.can_stop)
                            .on_click(cx.listener(|this, _, _, cx| this.stop(cx))),
                    )
                    .child(
                        Button::new("capture-cancel")
                            .icon(IconName::Close)
                            .label(lang.text(TextKey::CaptureCancelHostOperation))
                            .disabled(!controls.can_cancel)
                            .on_click(cx.listener(|this, _, _, cx| this.cancel(cx))),
                    )
                    .child(
                        Button::new("capture-clear")
                            .label(lang.text(TextKey::CaptureClearResult))
                            .disabled(busy)
                            .on_click(cx.listener(|this, _, _, cx| this.clear(cx))),
                    ),
            );
        if busy || self.handle.is_some() {
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
        if let Some(request) = &self.frozen_request {
            body = body
                .child(lang.text(TextKey::CaptureFrozenConfiguration))
                .children(frozen_sampling_lines(lang, request, self.frozen_template));
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
                .child(lang.text(TextKey::CaptureSavedOutput))
                .child(lang.text(completion_label(output.completion)))
                .child(lang.format(
                    TextKey::DetailsCaptureRawArtifact,
                    &[
                        ("path", &output.raw.path.display()),
                        ("size_bytes", &output.raw.size_bytes),
                        ("digest", &output.raw.digest),
                    ],
                ));
            if let Some(path) = &output.retained_remote_path {
                body = body
                    .child(lang.format(TextKey::DetailsCaptureRetainedRemote, &[("path", path)]));
            } else {
                body = body.child(lang.text(TextKey::CaptureOwnedRemoteFilesCleaned));
            }
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
        div()
            .id("cpu-capture-scroll")
            .size_full()
            .overflow_y_scroll()
            .child(body)
    }
}

fn frozen_sampling_lines(
    language: Language,
    request: &CpuCaptureRequest,
    template: Option<SamplingTemplate>,
) -> Vec<String> {
    vec![
        language.format(
            TextKey::CaptureTemplate,
            &[(
                "template",
                &language
                    .text(template.map_or(TextKey::CaptureCustomConfiguration, template_label)),
            )],
        ),
        language.format(TextKey::CaptureEvent, &[("event", &request.sampling.event)]),
        language.format(
            if request.sampling.rate.is_period() {
                TextKey::CapturePeriod
            } else {
                TextKey::CaptureFrequency
            },
            &[("value", &request.sampling.rate.value())],
        ),
        request.duration.seconds_argument().map_or_else(
            || language.text(TextKey::CaptureDurationManual).to_owned(),
            |seconds| language.format(TextKey::CaptureDuration, &[("seconds", &seconds)]),
        ),
        language.format(
            TextKey::CaptureScope,
            &[("scope", &language.text(scope_label(request.sampling.scope)))],
        ),
        language.format(
            TextKey::CaptureCallGraph,
            &[(
                "mode",
                &language.text(call_graph_label(request.sampling.call_graph)),
            )],
        ),
    ]
}

pub(crate) fn template_label(template: SamplingTemplate) -> TextKey {
    match template {
        SamplingTemplate::AppCpuBasic => TextKey::SettingsUiTemplateBasic,
        SamplingTemplate::UiThreadFocus => TextKey::SettingsUiTemplateUi,
        SamplingTemplate::NativeHotspot => TextKey::SettingsUiTemplateNative,
        SamplingTemplate::LowOverhead => TextKey::SettingsUiTemplateLow,
        SamplingTemplate::SystemProcess => TextKey::SettingsUiTemplateSystem,
    }
}

pub(crate) fn template_description(template: SamplingTemplate) -> TextKey {
    match template {
        SamplingTemplate::AppCpuBasic => TextKey::SettingsUiTemplateAppCpuBasicDescription,
        SamplingTemplate::UiThreadFocus => TextKey::SettingsUiTemplateUiThreadFocusDescription,
        SamplingTemplate::NativeHotspot => TextKey::SettingsUiTemplateNativeHotspotDescription,
        SamplingTemplate::LowOverhead => TextKey::SettingsUiTemplateLowOverheadDescription,
        SamplingTemplate::SystemProcess => TextKey::SettingsUiTemplateSystemProcessDescription,
    }
}

pub(crate) fn scope_label(scope: EventScope) -> TextKey {
    match scope {
        EventScope::User => TextKey::SettingsUiUserScope,
        EventScope::Kernel => TextKey::SettingsUiKernelScope,
        EventScope::Both => TextKey::SettingsUiBothScope,
    }
}

pub(crate) fn call_graph_label(mode: CallGraphMode) -> TextKey {
    match mode {
        CallGraphMode::Dwarf => TextKey::SettingsUiDwarf,
        CallGraphMode::FramePointer => TextKey::SettingsUiFramePointer,
        CallGraphMode::None => TextKey::SettingsUiNone,
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
        CaptureState::StopUnverified => TextKey::CaptureStateStopUnverified,
        CaptureState::Failed => TextKey::DetailsCaptureStateFailed,
    }
}

fn completion_label(completion: CaptureCompletion) -> TextKey {
    match completion {
        CaptureCompletion::RecorderExited => TextKey::CaptureCompletedRecorderExited,
        CaptureCompletion::StopRequested => TextKey::CaptureCompletedStopRequested,
    }
}
impl NoticeSource for CaptureWorkspace {
    fn notice_snapshot(&self) -> Vec<NoticeSnapshot> {
        let mut notices = Vec::new();
        if let Some(error) = &self.error {
            notices.push(NoticeSnapshot::error(crate::evidence::clipped(error, 8192)));
        }
        if self.cancellation_notice {
            let message = self
                .language
                .text(TextKey::CaptureCaptureCancellationRequestedTheExactRemotePid);
            notices.push(NoticeSnapshot::info(crate::evidence::clipped(
                message, 8192,
            )));
        }
        notices.retain(|notice| !notice.message.trim().is_empty());
        notices.truncate(8);
        notices
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn request(draft: &CpuCaptureDraft) -> Result<CpuCaptureRequest, TextKey> {
        draft.request("run-1".into(), "device".into(), 42, None)
    }

    #[::core::prelude::v1::test]
    fn persisted_defaults_initialize_the_same_draft_used_by_requests() {
        let draft = CpuCaptureDraft::new(37, 123);
        let request = request(&draft).unwrap();
        assert_eq!(
            request.duration,
            CaptureDuration::Timed(Duration::from_secs(37))
        );
        assert_eq!(request.sampling.rate, SamplingRate::Frequency(123));
        assert_eq!(request.sampling.event, "cpu-clock");
        assert_eq!(request.sampling.scope, EventScope::Both);
        assert_eq!(request.sampling.call_graph, CallGraphMode::Dwarf);
        assert_eq!(draft.persistable_defaults(), (Some(37), Some(123)));
    }

    #[::core::prelude::v1::test]
    fn all_five_templates_replace_the_draft_with_actual_compose_values() {
        assert_eq!(SamplingTemplate::ALL.len(), 5);
        for app_scoped in [true, false] {
            for template in SamplingTemplate::ALL {
                let mut draft = CpuCaptureDraft::new(17, 99);
                draft.event = "invalid event".into();
                draft.rate = "0".into();
                draft.duration.clear();
                draft.period_mode = true;
                draft.call_graph = CallGraphMode::None;
                draft.apply_template(template, app_scoped);
                let request = request(&draft).unwrap();
                let (event, frequency, call_graph) = match template {
                    SamplingTemplate::AppCpuBasic | SamplingTemplate::UiThreadFocus => {
                        ("cpu-clock", 1000, CallGraphMode::Dwarf)
                    }
                    SamplingTemplate::NativeHotspot => ("cpu-cycles", 1000, CallGraphMode::Dwarf),
                    SamplingTemplate::LowOverhead => {
                        ("cpu-clock", 100, CallGraphMode::FramePointer)
                    }
                    SamplingTemplate::SystemProcess => {
                        ("cpu-clock", 400, CallGraphMode::FramePointer)
                    }
                };
                assert_eq!(draft.template, Some(template));
                assert!(!draft.period_mode);
                assert_eq!(
                    request.duration,
                    CaptureDuration::Timed(Duration::from_secs(10))
                );
                assert_eq!(request.sampling.event, event);
                assert_eq!(request.sampling.rate, SamplingRate::Frequency(frequency));
                assert_eq!(request.sampling.call_graph, call_graph);
                assert_eq!(
                    request.sampling.scope,
                    if app_scoped {
                        EventScope::User
                    } else {
                        EventScope::Both
                    }
                );
                assert_eq!(
                    request.pid, 42,
                    "a template cannot retarget a process or thread"
                );
                assert_eq!(request.serial, "device");
                assert_eq!(request.converter, None);
            }
        }
    }

    #[::core::prelude::v1::test]
    fn editable_event_rate_scope_and_callgraph_reach_each_new_request() {
        for scope in EventScope::ALL {
            for period in [false, true] {
                for call_graph in CallGraphMode::ALL {
                    let mut draft = CpuCaptureDraft::new(12, 321);
                    draft.event = "sched:sched_switch".into();
                    draft.period_mode = period;
                    draft.call_graph = call_graph;
                    draft.scope = scope;
                    let request = request(&draft).unwrap();
                    let modifier = match scope {
                        EventScope::User => ":u",
                        EventScope::Kernel => ":k",
                        EventScope::Both => "",
                    };
                    let mut expected = vec![
                        "-e".to_owned(),
                        format!("sched:sched_switch{modifier}"),
                        if period { "-c" } else { "-f" }.into(),
                        "321".into(),
                    ];
                    expected.extend(
                        call_graph
                            .arguments()
                            .iter()
                            .map(|argument| (*argument).to_owned()),
                    );
                    assert_eq!(request.sampling.record_arguments().unwrap(), expected);
                    assert_eq!(
                        request.duration,
                        CaptureDuration::Timed(Duration::from_secs(12))
                    );
                    assert_eq!(request.sampling.scope, scope);
                }
            }
        }
    }

    #[::core::prelude::v1::test]
    fn frequency_and_period_bounds_are_checked_without_changing_any_input() {
        for period in [false, true] {
            for text in [
                "",
                "0",
                "-1",
                "+1",
                "1.5",
                " 99",
                "99 ",
                "1e3",
                "NaN",
                "9223372036854775808",
                "18446744073709551616",
                "１２",
            ] {
                let mut draft = CpuCaptureDraft::new(10, 99);
                draft.period_mode = period;
                draft.rate = text.into();
                let before = draft.clone();
                assert!(request(&draft).is_err(), "period={period}: {text:?}");
                assert_eq!(draft, before);
                assert_eq!(draft.persistable_defaults().1, None);
            }
        }
        let mut draft = CpuCaptureDraft::new(10, 4001);
        assert!(request(&draft).is_err());
        assert_eq!(draft.rate, "4001");
        draft.period_mode = true;
        assert_eq!(
            request(&draft).unwrap().sampling.rate,
            SamplingRate::Period(4001)
        );
        assert_eq!(draft.persistable_defaults().1, None);
        draft.rate = i64::MAX.to_string();
        assert_eq!(
            request(&draft).unwrap().sampling.rate,
            SamplingRate::Period(i64::MAX as u64)
        );
        draft.period_mode = false;
        assert!(request(&draft).is_err());
        assert_eq!(
            draft.rate,
            i64::MAX.to_string(),
            "changing mode cannot clamp a period to Hz"
        );
        for frequency in [1, 4000] {
            draft.rate = frequency.to_string();
            assert_eq!(
                request(&draft).unwrap().sampling.rate,
                SamplingRate::Frequency(frequency)
            );
        }
    }

    #[::core::prelude::v1::test]
    fn manual_and_fractional_duration_reach_requests_without_rounding_or_editing_the_draft() {
        for blank in ["", " ", "\t\n"] {
            let mut draft = CpuCaptureDraft::new(10, 99);
            draft.duration = blank.into();
            let before = draft.clone();
            assert!(draft.validate().is_ok());
            let request = request(&draft).unwrap();
            assert_eq!(request.duration, CaptureDuration::Manual);
            assert_eq!(request.duration.seconds_argument(), None);
            assert_eq!(draft.persistable_defaults(), (None, Some(99)));
            assert_eq!(draft, before);
        }
        for (text, duration, argument, persisted) in [
            ("0.25", Duration::from_millis(250), "0.25", None),
            ("1.5", Duration::from_millis(1500), "1.5", None),
            ("1e-3", Duration::from_millis(1), "0.001", None),
            (" 0.25 ", Duration::from_millis(250), "0.25", None),
            ("10.0", Duration::from_secs(10), "10", Some(10)),
            ("1", Duration::from_secs(1), "1", Some(1)),
            ("300", Duration::from_secs(300), "300", Some(300)),
        ] {
            let mut draft = CpuCaptureDraft::new(10, 99);
            draft.duration = text.into();
            let before = draft.clone();
            assert!(draft.validate().is_ok(), "{text:?}");
            let request = request(&draft).unwrap();
            assert_eq!(request.duration, CaptureDuration::Timed(duration));
            assert_eq!(
                request.duration.seconds_argument().as_deref(),
                Some(argument)
            );
            assert_eq!(draft.persistable_defaults(), (persisted, Some(99)));
            assert_eq!(draft, before);
        }
    }

    #[::core::prelude::v1::test]
    fn invalid_duration_is_preserved_and_rejected_without_reusing_previous_defaults() {
        for text in [
            "0",
            "-0",
            "301",
            "300.000000001",
            "-1",
            "NaN",
            "inf",
            "-inf",
            "Infinity",
            "0.25s",
            "1;id",
            "1e-20",
            "18446744073709551616",
        ] {
            let mut draft = CpuCaptureDraft::new(10, 99);
            draft.duration = text.into();
            assert_eq!(
                request(&draft).unwrap_err(),
                TextKey::CaptureInvalidDuration
            );
            assert_eq!(draft.duration, text);
            assert_eq!(draft.persistable_defaults().0, None);
        }
    }

    #[::core::prelude::v1::test]
    fn invalid_event_is_preserved_and_rejected_before_planning() {
        for event in [
            "",
            "cpu clock",
            " cpu-clock",
            "cpu-clock ",
            "-e",
            "cpu-clock;id",
            "$(id)",
            "cpu-clock\n",
            "事件",
        ] {
            let mut draft = CpuCaptureDraft::new(10, 99);
            draft.event = event.into();
            assert_eq!(request(&draft).unwrap_err(), TextKey::CaptureInvalidEvent);
            assert_eq!(draft.event, event);
        }
        let mut draft = CpuCaptureDraft::new(10, 99);
        draft.event = "a".repeat(257);
        assert_eq!(request(&draft).unwrap_err(), TextKey::CaptureInvalidEvent);
        draft.event = "a".repeat(256);
        assert!(request(&draft).is_ok());
    }

    #[::core::prelude::v1::test]
    fn later_draft_edits_and_templates_do_not_change_frozen_requests_or_summaries() {
        let mut draft = CpuCaptureDraft::new(10, 99);
        draft.apply_template(SamplingTemplate::NativeHotspot, true);
        draft.period_mode = true;
        draft.rate = "8192".into();
        draft.duration = "29".into();
        draft.scope = EventScope::Kernel;
        draft.call_graph = CallGraphMode::None;
        let prepared = request(&draft).unwrap();
        let running = prepared.clone();
        let template = draft.template;
        let before = frozen_sampling_lines(Language::English, &prepared, template);
        draft.apply_template(SamplingTemplate::LowOverhead, false);
        draft.rate = "".into();
        assert!(request(&draft).is_err());
        for language in [Language::English, Language::SimplifiedChinese] {
            assert_eq!(
                frozen_sampling_lines(language, &prepared, template),
                frozen_sampling_lines(language, &running, template)
            );
            let lines = frozen_sampling_lines(language, &running, template);
            assert_eq!(lines.len(), 6);
            for value in [
                "cpu-cycles",
                "8192",
                "29",
                language.text(TextKey::SettingsUiKernelScope),
                language.text(TextKey::SettingsUiNone),
                language.text(TextKey::SettingsUiTemplateNative),
            ] {
                assert!(
                    lines.iter().any(|line| line.contains(value)),
                    "missing frozen {value}"
                );
            }
            assert!(
                lines
                    .iter()
                    .all(|line| !line.contains('{') && !line.contains('}'))
            );
        }
        assert_eq!(
            before,
            frozen_sampling_lines(Language::English, &prepared, template)
        );
        draft.rate = "100".into();
        assert_eq!(
            request(&draft).unwrap().sampling.rate,
            SamplingRate::Frequency(100)
        );
        assert_eq!(prepared.sampling.rate, SamplingRate::Period(8192));
        assert_eq!(prepared.sampling.scope, EventScope::Kernel);
        assert_eq!(
            prepared.duration,
            CaptureDuration::Timed(Duration::from_secs(29))
        );
    }

    #[::core::prelude::v1::test]
    fn frozen_manual_and_fractional_confirmations_do_not_follow_later_draft_edits() {
        for text in ["", "0.25"] {
            let mut draft = CpuCaptureDraft::new(10, 99);
            draft.duration = text.into();
            let prepared = request(&draft).unwrap();
            let running = prepared.clone();
            draft.duration = "300".into();
            let next = request(&draft).unwrap();
            assert_ne!(next.duration, prepared.duration);
            for language in [Language::English, Language::SimplifiedChinese] {
                let expected = if text.is_empty() {
                    language.text(TextKey::CaptureDurationManual).to_owned()
                } else {
                    language.format(TextKey::CaptureDuration, &[("seconds", &"0.25")])
                };
                let lines = frozen_sampling_lines(language, &prepared, None);
                assert!(lines.contains(&expected));
                assert_eq!(lines, frozen_sampling_lines(language, &running, None));
                assert!(!frozen_sampling_lines(language, &next, None).contains(&expected));
            }
        }
    }

    #[::core::prelude::v1::test]
    fn new_defaults_only_replace_unedited_default_values_not_periods_or_templates() {
        let mut pristine = CpuCaptureDraft::new(10, 99);
        pristine.update_defaults((10, 99), (20, 200));
        assert_eq!(pristine.duration, "20");
        assert_eq!(pristine.rate, "200");
        for defaults in [(0, 0), (301, 4001)] {
            let mut invalid = CpuCaptureDraft::new(defaults.0, defaults.1);
            let before = invalid.clone();
            invalid.update_defaults(defaults, (20, 200));
            assert_eq!(
                invalid, before,
                "even invalid inherited defaults remain visible"
            );
        }
        for text in ["", " ", "0.25", "1.5", "0", "4001", "100", " 99"] {
            let mut draft = CpuCaptureDraft::new(10, 99);
            draft.rate = text.into();
            draft.duration = text.into();
            let before = draft.clone();
            draft.update_defaults((10, 99), (20, 200));
            assert_eq!(draft, before);
        }
        let mut period = CpuCaptureDraft::new(10, 99);
        period.period_mode = true;
        period.update_defaults((10, 99), (20, 200));
        assert_eq!(period.rate, "99");
        let mut template = CpuCaptureDraft::new(10, 99);
        template.apply_template(SamplingTemplate::AppCpuBasic, true);
        let before = template.clone();
        template.update_defaults((10, 1000), (20, 200));
        assert_eq!(template, before);
    }

    #[::core::prelude::v1::test]
    fn templates_use_only_discovered_context_for_the_effective_device_and_pid() {
        let processes = vec![aps_adb::AndroidApplicationProcess {
            pid: 42,
            parent_pid: 1,
            name: "com.example.app:worker".into(),
            package_name: "com.example.app".into(),
            user: "u0_a123".into(),
        }];
        let (serial, pid) = resolve_target("", "", Some("device"), Some(42)).unwrap();
        assert!(target_is_app_scoped(
            &serial,
            pid,
            Some("device"),
            &processes
        ));
        assert!(!target_is_app_scoped(
            "other-device",
            pid,
            Some("device"),
            &processes
        ));
        assert!(!target_is_app_scoped(
            &serial,
            43,
            Some("device"),
            &processes
        ));
        assert!(!target_is_app_scoped(&serial, pid, None, &processes));
        assert!(!target_is_app_scoped(&serial, pid, Some("device"), &[]));
        assert_eq!(
            resolve_target("manual", "43", Some("device"), Some(42)).unwrap(),
            ("manual".into(), 43)
        );
        for invalid_pid in ["0", "-1", "bad", "4294967296"] {
            assert!(resolve_target("", invalid_pid, Some("device"), Some(42)).is_err());
        }
        assert!(resolve_target("", "", None, Some(42)).is_err());
    }

    #[::core::prelude::v1::test]
    fn sampling_labels_and_errors_describe_manual_fractional_and_stop_contracts_in_both_locales() {
        for language in [Language::English, Language::SimplifiedChinese] {
            for key in [
                TextKey::CaptureInvalidDuration,
                TextKey::CaptureInvalidEvent,
                TextKey::CaptureInvalidFrequency,
                TextKey::CaptureInvalidPeriod,
                TextKey::CaptureInvalidTarget,
                TextKey::SettingsUiEventsPerSample,
                TextKey::CaptureStopAndSave,
                TextKey::CaptureDurationManual,
                TextKey::CaptureStateStopUnverified,
                TextKey::CaptureCompletedRecorderExited,
                TextKey::CaptureCompletedStopRequested,
                TextKey::CaptureSavedOutput,
            ] {
                assert!(!language.text(key).is_empty());
            }
            for template in SamplingTemplate::ALL {
                assert!(!language.text(template_label(template)).is_empty());
                assert!(!language.text(template_description(template)).is_empty());
            }
            for scope in EventScope::ALL {
                assert!(!language.text(scope_label(scope)).is_empty());
            }
            let draft = CpuCaptureDraft::new(10, 99);
            let lines = frozen_sampling_lines(language, &request(&draft).unwrap(), None);
            assert!(lines.iter().any(|line| line.contains("99")));
            assert!(
                lines
                    .iter()
                    .any(|line| line.contains(language.text(TextKey::CaptureCustomConfiguration)))
            );
            let (manual, fractional, saved, no_result) = match language {
                Language::SimplifiedChinese => ("手动停止", "小数", "已保存", "不会发布"),
                _ => ("manual stop", "fractions", "saved", "will be published"),
            };
            for key in [
                TextKey::SettingsUiDurationSecondsHint,
                TextKey::CaptureDurationSecondsPlaceholder,
                TextKey::CaptureDurationManual,
            ] {
                assert!(language.text(key).contains(manual));
            }
            assert!(
                language
                    .text(TextKey::CaptureDurationLimit)
                    .contains(fractional)
            );
            assert!(
                language
                    .text(TextKey::CaptureInvalidDuration)
                    .contains(fractional)
            );
            assert!(language.text(TextKey::CaptureDurationManual).contains("24"));
            for completion in [
                CaptureCompletion::RecorderExited,
                CaptureCompletion::StopRequested,
            ] {
                assert!(language.text(completion_label(completion)).contains(saved));
            }
            let cancel =
                language.text(TextKey::CaptureCaptureCancellationRequestedTheExactRemotePid);
            assert!(cancel.contains(no_result));
            assert_ne!(
                language.text(TextKey::CaptureCancelHostOperation),
                language.text(TextKey::CaptureStopAndSave)
            );
            for key in [
                TextKey::CaptureDurationManual,
                TextKey::CaptureStateStopUnverified,
                TextKey::CaptureStopAndSave,
                TextKey::CaptureCompletedRecorderExited,
                TextKey::CaptureCompletedStopRequested,
            ] {
                assert!(key.parameters().is_empty());
                assert!(!language.text(key).contains('{'));
            }
        }
    }

    #[::core::prelude::v1::test]
    fn stop_is_recording_only_but_cancel_remains_available_through_stopping_and_import_work() {
        for state in [
            CaptureState::Planned,
            CaptureState::Confirmed,
            CaptureState::Rechecking,
            CaptureState::Preparing,
            CaptureState::Recording,
            CaptureState::Stopping,
            CaptureState::Pulling,
            CaptureState::Converting,
            CaptureState::Complete,
            CaptureState::Cancelled,
            CaptureState::CancellationUnverified,
            CaptureState::StopUnverified,
            CaptureState::Failed,
        ] {
            assert_eq!(
                capture_controls(false, Some(&state)),
                CaptureControls {
                    can_stop: false,
                    can_cancel: false
                }
            );
            let active = capture_controls(true, Some(&state));
            assert_eq!(
                active.can_stop,
                state == CaptureState::Recording,
                "{state:?}"
            );
            assert_eq!(
                active.can_cancel,
                matches!(
                    state,
                    CaptureState::Planned
                        | CaptureState::Confirmed
                        | CaptureState::Rechecking
                        | CaptureState::Preparing
                        | CaptureState::Recording
                        | CaptureState::Stopping
                        | CaptureState::Pulling
                        | CaptureState::Converting
                ),
                "{state:?}"
            );
            for language in [Language::English, Language::SimplifiedChinese] {
                assert!(!language.text(capture_state_key(state.clone())).is_empty());
            }
        }
        assert_eq!(
            capture_controls(true, None),
            CaptureControls {
                can_stop: false,
                can_cancel: true
            }
        );
        assert_eq!(
            capture_controls(false, None),
            CaptureControls {
                can_stop: false,
                can_cancel: false
            }
        );
        assert_eq!(
            capture_state_key(CaptureState::StopUnverified),
            TextKey::CaptureStateStopUnverified
        );
    }

    #[::core::prelude::v1::test]
    fn converter_prefill_only_changes_blank_idle_unplanned_inputs_without_resolving_tools() {
        let selected = PathBuf::from("/selected/host/simpleperf");
        for blank in ["", " \t"] {
            assert_eq!(
                converter_prefill(blank, Some(selected.clone()), false, false),
                Some(selected.clone())
            );
            assert_eq!(converter_prefill(blank, None, false, false), None);
        }
        for busy in [false, true] {
            for frozen in [false, true] {
                assert_eq!(
                    converter_prefill("/explicit/simpleperf", Some(selected.clone()), busy, frozen),
                    None
                );
                if busy || frozen {
                    assert_eq!(
                        converter_prefill("", Some(selected.clone()), busy, frozen),
                        None
                    );
                }
            }
        }
    }

    #[::core::prelude::v1::test]
    fn late_ok_plan_is_rejected_using_its_captured_token_not_a_newer_operation_token() {
        let captured = CancellationToken::new();
        let background = captured.clone();
        assert!(can_publish_plan(7, 7, &captured));
        assert!(!can_publish_plan(8, 7, &captured));
        background.cancel();
        let latest = CancellationToken::new();
        assert!(!can_publish_plan(7, 7, &captured));
        assert!(!can_publish_plan(8, 7, &captured));
        assert!(can_publish_plan(8, 8, &latest));

        // A new plan or its rejected completion must not discard the last
        // successful artifact; only explicit Clear or successful capture replaces it.
        let source = include_str!("capture.rs");
        let planning = source
            .split("fn plan(&mut self,")
            .nth(1)
            .unwrap()
            .split("fn confirm(&mut self,")
            .next()
            .unwrap();
        assert!(!planning.contains("self.output = None"));
        assert!(!planning.contains("this.output ="));
        assert!(planning.contains("let publication_cancel = cancel.clone()"));
        assert!(
            planning.contains("can_publish_plan(this.generation, generation, &publication_cancel)")
        );
    }

    #[::core::prelude::v1::test]
    fn call_graph_labels_and_confirmation_are_localized_for_every_mode() {
        for language in [Language::English, Language::SimplifiedChinese] {
            for mode in CallGraphMode::ALL {
                let label = language.text(call_graph_label(mode));
                assert!(!label.is_empty());
                let confirmation = language.format(TextKey::CaptureCallGraph, &[("mode", &label)]);
                assert!(confirmation.contains(label));
                assert!(!confirmation.contains("{mode}"));
            }
        }
    }

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
    /// Finish recording and save output after verified completion.
    Stop,
    /// Abort the current operation without publishing a new capture result.
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
        let controls = self.controls();
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
                    controls.can_stop,
                    TextKey::CaptureStopAndSave,
                    CaptureMenuCommand::Stop,
                )
                .item_when(
                    controls.can_cancel,
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
            CaptureMenuCommand::Stop => self.stop(cx),
            CaptureMenuCommand::Cancel => self.cancel(cx),
            CaptureMenuCommand::Clear => self.clear(cx),
        }
    }
}

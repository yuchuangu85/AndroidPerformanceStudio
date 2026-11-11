//! Explicit target review, background experiments and atomic page publication.
//! A title-bar PID disappearing after our own Cold launch is not cancellation.
use super::*;
use aps_adb::{AdbDevice, AndroidApplicationProcess};
use aps_startup::capture::{
    StartupCaptureArtifact, StartupCaptureConfig, StartupCaptureTarget, capture_startup,
};
use std::sync::atomic::AtomicU32;

#[derive(Clone)]
struct ReviewedExperiment {
    target: StartupCaptureTarget,
    config: StartupCaptureConfig,
}

pub(in crate::import) struct StartupCaptureUi {
    pub(super) reports: super::reports::ReportHistory,
    serial: Option<String>,
    pid: Option<u32>,
    devices: Vec<AdbDevice>,
    processes: Vec<AndroidApplicationProcess>,
    target: Option<StartupCaptureTarget>,
    config: StartupCaptureConfig,
    review: Option<ReviewedExperiment>,
    active_target: Option<StartupCaptureTarget>,
    cancel: Option<CancellationToken>,
    export_cancel: Option<CancellationToken>,
    progress: Arc<AtomicU32>,
    artifact: Option<Arc<StartupCaptureArtifact>>,
    pub(in crate::import) in_progress: bool,
}

impl Default for StartupCaptureUi {
    fn default() -> Self {
        Self {
            reports: Default::default(),
            serial: None,
            pid: None,
            devices: Vec::new(),
            processes: Vec::new(),
            target: None,
            config: StartupCaptureConfig::default(),
            review: None,
            active_target: None,
            cancel: None,
            export_cancel: None,
            progress: Arc::new(AtomicU32::new(0)),
            artifact: None,
            in_progress: false,
        }
    }
}

impl Drop for StartupCaptureUi {
    fn drop(&mut self) {
        self.cancel_pending();
    }
}

impl StartupCaptureUi {
    /// Pending state and published evidence have separate lifetimes. Cancelling
    /// a new experiment must not remove the old report's owned evidence files.
    pub(in crate::import) fn cancel_pending(&mut self) {
        self.cancel_export();
        if let Some(cancel) = self.cancel.take() {
            cancel.cancel();
        }
        self.in_progress = false;
        self.review = None;
        self.active_target = None;
    }

    pub(super) fn cancel_export(&mut self) {
        if let Some(cancel) = self.export_cancel.take() {
            cancel.cancel();
        }
    }

    pub(in crate::import) fn clear_published(&mut self) {
        self.cancel_pending();
        self.artifact = None;
    }

    fn reconcile(&mut self) -> bool {
        let online = self.serial.as_ref().is_some_and(|serial| {
            self.devices
                .iter()
                .any(|device| &device.serial == serial && device.is_online())
        });
        self.target = self
            .serial
            .as_ref()
            .zip(self.pid)
            .and_then(|(serial, pid)| {
                let process = self.processes.iter().find(|process| process.pid == pid)?;
                (online && pid > 0).then(|| StartupCaptureTarget {
                    serial: serial.clone(),
                    pid,
                    package_name: process.package_name.clone(),
                    process_name: process.name.clone(),
                })
            });
        if self
            .review
            .as_ref()
            .is_some_and(|review| self.target.as_ref() != Some(&review.target))
        {
            self.review = None;
        }
        self.active_target.as_ref().is_some_and(|active| {
            // PID=None and a new PID for the same package are expected when
            // Cold force-stops/restarts it. The backend pins boot/user/install.
            self.serial.as_deref() != Some(&active.serial)
                || !online
                || self
                    .target
                    .as_ref()
                    .is_some_and(|target| target.package_name != active.package_name)
        })
    }
}

struct LoadedStartup {
    artifact: Arc<StartupCaptureArtifact>,
    session: Arc<StartupSession>,
    page: PageData,
    elapsed: Duration,
}

fn load_capture(
    experiment: &ReviewedExperiment,
    root: &Path,
    cancel: &CancellationToken,
    progress: &AtomicU32,
) -> Result<LoadedStartup, UiText> {
    let captured = capture_startup(
        &experiment.target,
        &experiment.config,
        root,
        cancel,
        progress,
    )?;
    // Only post-capture publication work is import cost, never the experiment.
    let started = Instant::now();
    let session = Arc::new(captured.session);
    let page = super::load_page(
        &session,
        &StartupQuery {
            limit: PAGE,
            ..Default::default()
        },
        cancel,
    )?;
    Ok(LoadedStartup {
        artifact: Arc::new(captured.artifact),
        session,
        page,
        elapsed: started.elapsed(),
    })
}

impl ImportWorkspace {
    pub(crate) fn set_startup_capture_target(
        &mut self,
        serial: Option<String>,
        pid: Option<u32>,
        cx: &mut Context<Self>,
    ) {
        if self.startup_capture.serial == serial && self.startup_capture.pid == pid {
            return;
        }
        self.startup_capture.serial = serial;
        self.startup_capture.pid = pid;
        if self.startup_capture.reconcile() && self.startup_capture.in_progress {
            self.startup_capture.cancel_pending();
            self.cancel_work(cx);
        }
        cx.notify();
    }

    pub(crate) fn apply_startup_capture_targets(
        &mut self,
        devices: &[AdbDevice],
        processes: &[AndroidApplicationProcess],
        cx: &mut Context<Self>,
    ) {
        self.startup_capture.devices = devices.to_vec();
        self.startup_capture.processes = processes.to_vec();
        if self.startup_capture.reconcile() && self.startup_capture.in_progress {
            self.startup_capture.cancel_pending();
            self.cancel_work(cx);
        }
        cx.notify();
    }

    /// All entry points, including shortcuts, first show the exact experiment
    /// and lifecycle side effects. Merely opening/choosing a target does no work.
    pub(crate) fn start_startup_capture(&mut self, cx: &mut Context<Self>) {
        if self.kind != Destination::Startup || self.busy || self.export_busy {
            return;
        }
        let Some(target) = self.startup_capture.target.clone() else {
            self.notice = Some(TextKey::StartupLiveSelectTarget.into());
            cx.notify();
            return;
        };
        self.startup_capture.review = Some(ReviewedExperiment {
            target,
            config: self.startup_capture.config.clone(),
        });
        self.error = None;
        self.notice = None;
        cx.notify();
    }

    fn confirm_startup_capture(&mut self, cx: &mut Context<Self>) {
        if self.kind != Destination::Startup || self.busy || self.export_busy {
            return;
        }
        let Some(experiment) = self.startup_capture.review.take() else {
            return;
        };
        if self.startup_capture.target.as_ref() != Some(&experiment.target)
            || self.startup_capture.config != experiment.config
        {
            self.notice = Some(TextKey::StartupLiveSelectTarget.into());
            cx.notify();
            return;
        }
        let root = crate::settings_path()
            .ok()
            .and_then(|path| path.parent().map(|path| path.join("startup-captures")));
        let Some(root) = root else {
            self.error = Some("Startup capture storage is unavailable.".into());
            cx.notify();
            return;
        };
        self.cancel.cancel();
        self.cancel = CancellationToken::new();
        self.generation += 1;
        self.export_generation += 1;
        self.export_busy = false;
        self.busy = true;
        self.error = None;
        self.notice = None;
        self.import_started = Some(Instant::now());
        self.startup_capture.cancel = Some(self.cancel.clone());
        self.startup_capture.active_target = Some(experiment.target.clone());
        self.startup_capture.in_progress = true;
        self.startup_capture.progress = Arc::new(AtomicU32::new(0));
        let progress = self.startup_capture.progress.clone();
        let cancel = self.cancel.clone();
        let generation = self.generation;
        let task = cx
            .background_executor()
            .spawn(async move { load_capture(&experiment, &root, &cancel, &progress) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.generation != generation || !this.startup_capture.in_progress {
                    return;
                }
                this.busy = false;
                this.startup_capture.cancel = None;
                this.startup_capture.active_target = None;
                this.startup_capture.in_progress = false;
                this.import_started = None;
                match result {
                    Ok(loaded) => {
                        this.path = Some(loaded.artifact.path.clone());
                        this.startup_capture.artifact = Some(loaded.artifact);
                        this.session = Some(Session::Startup(loaded.session));
                        this.page = Some(loaded.page);
                        this.session_id = Some(new_session_id());
                        this.startup_mode_filter = None;
                        this.startup_page_offset = 0;
                        this.selected_startup_run_id = None;
                        this.import_duration = Some(loaded.elapsed);
                        this.publish_startup_selection();
                        this.notice = Some(TextKey::StartupLiveNotice.into());
                    }
                    Err(error) => {
                        this.error = Some(error);
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        // Background progress is bounded to one integer and stops on generation
        // changes, cancellation, completion, or a dropped workspace.
        cx.spawn(async move |this, cx| {
            loop {
                cx.background_executor()
                    .timer(Duration::from_millis(500))
                    .await;
                let active = this
                    .update(cx, |this, cx| {
                        if this.generation != generation || !this.startup_capture.in_progress {
                            return false;
                        }
                        cx.notify();
                        true
                    })
                    .unwrap_or(false);
                if !active {
                    break;
                }
            }
        })
        .detach();
        cx.notify();
    }

    /// Live evidence is scratch-owned. User exports must outlive the displayed
    /// report, including when its owner is replaced while the picker is open.
    pub(in crate::import) fn save_startup_page(
        &mut self,
        format: export::Format,
        cx: &mut Context<Self>,
    ) {
        if self.kind != Destination::Startup || self.export_busy || self.busy {
            return;
        }
        let Some(Session::Startup(session)) = self.session.clone() else {
            return;
        };
        self.startup_capture.cancel_export();
        let cancel = CancellationToken::new();
        self.startup_capture.export_cancel = Some(cancel.clone());
        self.export_busy = true;
        self.export_generation += 1;
        let generation = self.export_generation;
        let publication_generation = self.generation;
        // Retain ownership across both the native picker and the background
        // write, rather than leaving a dangling path when a report is closed.
        let artifact = self.startup_capture.artifact.clone();
        let capture_root = artifact
            .as_ref()
            .and_then(|artifact| artifact.path.parent().and_then(Path::parent))
            .map(Path::to_path_buf)
            .or_else(|| {
                crate::settings_path()
                    .ok()
                    .and_then(|path| path.parent().map(|path| path.join("startup-captures")))
            });
        let directory = if artifact.is_none() {
            self.path
                .as_ref()
                .and_then(|path| path.parent())
                .map(Path::to_path_buf)
        } else {
            None
        }
        .or_else(|| aps_platform::home_directory().ok())
        .unwrap_or_else(|| PathBuf::from("."));
        let filename = format!("aps-startup-report.{}", format.extension());
        let picker = cx.prompt_for_new_path(&directory, Some(&filename));
        cx.spawn(async move |this, cx| {
            let chosen = picker.await;
            let Some(path) = this
                .update(cx, |this, cx| {
                    if this.export_generation != generation {
                        return None;
                    }
                    if this.generation != publication_generation || cancel.is_cancelled() {
                        this.export_busy = false;
                        this.startup_capture.cancel_export();
                        cx.notify();
                        return None;
                    }
                    match chosen {
                        Ok(Ok(Some(path))) => Some(path),
                        Ok(Err(_)) => {
                            this.error = Some(TextKey::ImportUiSavePickerFailed.into());
                            this.export_busy = false;
                            this.startup_capture.cancel_export();
                            cx.notify();
                            None
                        }
                        _ => {
                            this.export_busy = false;
                            this.startup_capture.cancel_export();
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
                    let _artifact_owner = artifact;
                    if write_cancel.is_cancelled() {
                        return Err(UiText::from(TextKey::ImportUiImportCancelled));
                    }
                    validate_export_destination(&path, capture_root.as_deref())?;
                    let bytes = match format {
                        export::Format::Json => super::reports::encode_json(&session)?,
                        export::Format::Csv => super::reports::encode_csv(&session)?,
                    };
                    if write_cancel.is_cancelled() {
                        return Err(UiText::from(TextKey::ImportUiImportCancelled));
                    }
                    export::write_new_file(&path, &bytes).map_err(export_error)?;
                    Ok::<_, UiText>(path)
                })
                .await;
            this.update(cx, |this, cx| {
                if this.export_generation != generation {
                    return;
                }
                this.export_busy = false;
                this.startup_capture.export_cancel = None;
                if this.generation != publication_generation || cancel.is_cancelled() {
                    cx.notify();
                    return;
                }
                match result {
                    Ok(path) => {
                        this.notice = Some(UiText::format(
                            TextKey::ImportUiSavedPage,
                            [("path", path.display().to_string().into())],
                        ));
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

    pub(super) fn render_startup_capture_config(
        &self,
        colors: &studio::StudioColors,
        cx: &mut Context<Self>,
    ) -> AnyElement {
        let language = self.language;
        let state = &self.startup_capture;
        let config = &state.config;
        let editable = !self.busy && !self.export_busy && state.review.is_none();
        let button =
            |id, label: String| studio::compact_text_button(id, label, editable, false, colors);
        let controls = div()
            .flex()
            .flex_wrap()
            .gap(px(4.))
            .child(
                button(
                    "startup-live-mode",
                    language.format(
                        TextKey::StartupLiveMode,
                        &[("mode", &mode_label(config.mode, language))],
                    ),
                )
                .on_click(cx.listener(|this, _, _, cx| {
                    this.startup_capture.config.mode = match this.startup_capture.config.mode {
                        StartupMode::Cold => StartupMode::Warm,
                        StartupMode::Warm => StartupMode::Hot,
                        _ => StartupMode::Cold,
                    };
                    cx.notify();
                })),
            )
            .child(
                button(
                    "startup-live-iterations",
                    language.format(
                        TextKey::StartupLiveIterations,
                        &[("count", &config.iterations)],
                    ),
                )
                .on_click(cx.listener(|this, _, _, cx| {
                    this.startup_capture.config.iterations =
                        cycle(this.startup_capture.config.iterations, &[1, 3, 5, 10, 20]);
                    cx.notify();
                })),
            )
            .child(
                button(
                    "startup-live-timeout",
                    language.format(
                        TextKey::StartupLiveTimeout,
                        &[("seconds", &config.launch_timeout.as_secs())],
                    ),
                )
                .on_click(cx.listener(|this, _, _, cx| {
                    this.startup_capture.config.launch_timeout = Duration::from_secs(cycle(
                        this.startup_capture.config.launch_timeout.as_secs(),
                        &[15, 30, 60],
                    ));
                    cx.notify();
                })),
            )
            .child(
                button(
                    "startup-live-settle",
                    language.format(
                        TextKey::StartupLiveSettle,
                        &[("milliseconds", &config.settle_delay.as_millis())],
                    ),
                )
                .on_click(cx.listener(|this, _, _, cx| {
                    this.startup_capture.config.settle_delay = Duration::from_millis(cycle(
                        this.startup_capture.config.settle_delay.as_millis() as u64,
                        &[300, 500, 1000, 2000],
                    ));
                    cx.notify();
                })),
            )
            .child(
                button(
                    "startup-live-event-wait",
                    language.format(
                        TextKey::StartupLiveEventWait,
                        &[("seconds", &config.fully_drawn_wait.as_secs())],
                    ),
                )
                .on_click(cx.listener(|this, _, _, cx| {
                    this.startup_capture.config.fully_drawn_wait = Duration::from_secs(cycle(
                        this.startup_capture.config.fully_drawn_wait.as_secs(),
                        &[0, 2, 5, 10],
                    ));
                    cx.notify();
                })),
            );
        let displayed_target = state
            .active_target
            .as_ref()
            .or_else(|| state.review.as_ref().map(|r| &r.target))
            .or(state.target.as_ref());
        let target_label = displayed_target
            .map(|target| {
                language.format(
                    TextKey::StartupLiveTarget,
                    &[
                        ("package", &target.package_name),
                        ("pid", &target.pid),
                        ("serial", &target.serial),
                    ],
                )
            })
            .unwrap_or_else(|| language.text(TextKey::StartupLiveSelectTarget).into());
        let mut panel = studio::card(colors)
            .child(studio::section_title(
                language.text(TextKey::StartupScenario),
                colors,
            ))
            .child(note(target_label, colors.text))
            .child(controls)
            .child(note(
                language.text(TextKey::StartupLiveConfiguration),
                colors.secondary_text,
            ));
        if state.in_progress {
            panel = panel
                .child(note(
                    language.format(
                        TextKey::StartupLiveProgress,
                        &[
                            ("completed", &state.progress.load(Ordering::Acquire)),
                            ("total", &config.iterations),
                        ],
                    ),
                    colors.info,
                ))
                .child(
                    studio::compact_text_button(
                        "startup-live-cancel",
                        language.text(TextKey::ImportCancelOperation),
                        true,
                        false,
                        colors,
                    )
                    .on_click(cx.listener(|this, _, _, cx| {
                        this.startup_capture.cancel_pending();
                        this.cancel_work(cx);
                    })),
                );
        } else if state.review.is_some() {
            panel = panel
                .child(note(
                    language.text(TextKey::StartupLiveAuthorization),
                    colors.warning,
                ))
                .child(
                    div()
                        .flex()
                        .flex_wrap()
                        .gap(px(4.))
                        .child(
                            studio::compact_text_button(
                                "startup-live-confirm",
                                language.text(TextKey::StartupLiveConfirm),
                                !self.busy && !self.export_busy,
                                true,
                                colors,
                            )
                            .on_click(
                                cx.listener(|this, _, _, cx| this.confirm_startup_capture(cx)),
                            ),
                        )
                        .child(
                            studio::compact_text_button(
                                "startup-live-cancel-review",
                                language.text(TextKey::StartupLiveCancelReview),
                                true,
                                false,
                                colors,
                            )
                            .on_click(cx.listener(|this, _, _, cx| {
                                this.startup_capture.review = None;
                                cx.notify();
                            })),
                        ),
                );
        } else {
            panel = panel.child(
                studio::compact_text_button(
                    "startup-live-review",
                    language.text(TextKey::StartupLivePrepare),
                    editable && state.target.is_some(),
                    true,
                    colors,
                )
                .on_click(cx.listener(|this, _, _, cx| this.start_startup_capture(cx))),
            );
        }
        panel.into_any_element()
    }
}

fn cycle<T: Copy + PartialEq>(value: T, values: &[T]) -> T {
    values[values
        .iter()
        .position(|candidate| *candidate == value)
        .map_or(0, |i| (i + 1) % values.len())]
}

fn validate_export_destination(path: &Path, capture_root: Option<&Path>) -> Result<(), UiText> {
    if !path.is_absolute() || path.file_name().is_none() {
        return Err("Startup export requires an absolute destination filename.".into());
    }
    let parent = path
        .parent()
        .ok_or_else(|| UiText::from("Startup export destination has no parent directory."))?
        .canonicalize()
        .map_err(|_| UiText::from("Startup export destination directory is unavailable."))?;
    if let Some(root) = capture_root {
        match root.canonicalize() {
            Ok(root) if parent.starts_with(&root) => {
                return Err("Choose a destination outside the temporary Startup capture directory; its contents are removed when the report is closed.".into());
            }
            Ok(_) => {}
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(_) => return Err("Cannot verify the Startup export destination boundary.".into()),
        }
    }
    Ok(())
}

pub(super) fn is_live_run(run: &StartupRun) -> bool {
    run.origin == aps_startup::StartupEvidenceOrigin::LiveCapture
}

pub(super) fn provenance_fields(run: &StartupRun) -> Vec<(&'static str, String)> {
    if !is_live_run(run) {
        return Vec::new();
    }
    [
        "experimentId",
        "serial",
        "bootId",
        "userId",
        "packageUid",
        "versionCode",
        "codePath",
        "firstInstallTime",
        "lastUpdateTime",
        "component",
        "selectedPid",
        "pidsBefore",
        "pidsAfter",
        "eventScope",
        "amStartEvidence",
        "eventEvidence",
    ]
    .into_iter()
    .filter_map(|key| {
        run.imported_fields
            .get(key)
            .map(|value| (key, value.clone()))
    })
    .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn state() -> StartupCaptureUi {
        let mut state = StartupCaptureUi::default();
        state.serial = Some("device-one".into());
        state.pid = Some(42);
        state.devices = vec![AdbDevice {
            serial: "device-one".into(),
            state: aps_adb::DeviceState::Device,
            raw_state: "device".into(),
            attributes: BTreeMap::new(),
            status_detail: None,
        }];
        state.processes = vec![AndroidApplicationProcess {
            pid: 42,
            parent_pid: 1,
            user: "u0_a123".into(),
            name: "dev.sample".into(),
            package_name: "dev.sample".into(),
        }];
        assert!(!state.reconcile());
        state
    }

    #[::core::prelude::v1::test]
    fn discovery_does_not_authorize_or_start_a_capture() {
        let state = state();
        assert!(state.target.is_some());
        assert!(state.review.is_none());
        assert!(state.active_target.is_none());
        assert!(!state.in_progress);
        assert!(state.cancel.is_none());
        assert!(state.artifact.is_none());
    }

    #[::core::prelude::v1::test]
    fn reviewed_target_changes_revoke_the_unconfirmed_experiment() {
        let mut state = state();
        state.review = Some(ReviewedExperiment {
            target: state.target.clone().unwrap(),
            config: state.config.clone(),
        });
        state.pid = None;
        assert!(!state.reconcile());
        assert!(state.review.is_none());
        assert!(!state.in_progress);
    }

    #[::core::prelude::v1::test]
    fn cold_restart_pid_loss_is_not_target_change_but_disconnect_is() {
        let mut state = state();
        state.active_target = state.target.clone();
        state.in_progress = true;
        state.pid = None;
        state.processes.clear();
        assert!(!state.reconcile());
        state.devices[0].state = aps_adb::DeviceState::Offline;
        assert!(state.reconcile());
    }

    #[::core::prelude::v1::test]
    fn selecting_another_package_invalidates_the_active_target() {
        let mut state = state();
        state.active_target = state.target.clone();
        state.processes[0].package_name = "other.app".into();
        state.processes[0].name = "other.app".into();
        assert!(state.reconcile());
    }

    #[::core::prelude::v1::test]
    fn cancellation_revokes_pending_capture_and_export_without_erasing_target() {
        let mut state = state();
        let capture = CancellationToken::new();
        let export = CancellationToken::new();
        state.cancel = Some(capture.clone());
        state.export_cancel = Some(export.clone());
        state.active_target = state.target.clone();
        state.in_progress = true;
        state.cancel_pending();
        assert!(capture.is_cancelled());
        assert!(export.is_cancelled());
        assert!(state.target.is_some());
        assert!(!state.in_progress);
        let source = include_str!("capture.rs");
        let pending = source
            .split("fn cancel_pending(")
            .nth(1)
            .unwrap()
            .split("fn cancel_export(")
            .next()
            .unwrap();
        assert!(!pending.contains("artifact = None"));
        assert!(!pending.contains("page = None"));
    }

    #[::core::prelude::v1::test]
    fn export_destinations_cannot_enter_the_owned_capture_tree() {
        static NEXT: AtomicU32 = AtomicU32::new(0);
        let root = std::env::temp_dir().join(format!(
            "aps-startup-export-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        let captures = root.join("startup-captures");
        let owned = captures.join("job-one");
        std::fs::create_dir_all(&owned).unwrap();
        assert!(validate_export_destination(&root.join("saved.csv"), Some(&captures)).is_ok());
        assert!(validate_export_destination(&captures.join("saved.csv"), Some(&captures)).is_err());
        assert!(validate_export_destination(&owned.join("saved.csv"), Some(&captures)).is_err());
        assert!(validate_export_destination(Path::new("relative.csv"), Some(&captures)).is_err());
        #[cfg(unix)]
        {
            let alias = root.join("alias");
            std::os::unix::fs::symlink(&owned, &alias).unwrap();
            assert!(
                validate_export_destination(&alias.join("saved.csv"), Some(&captures)).is_err()
            );
        }
        std::fs::remove_dir_all(root).unwrap();
    }
}

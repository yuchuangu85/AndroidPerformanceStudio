//! Explicit Java heap capture from the shared device/process target.
pub(super) mod transport;

use super::super::*;
use aps_adb::{AdbDevice, AndroidApplicationProcess};
use transport::{CapturedHeap, HeapCaptureTarget};

#[derive(Default)]
pub(in crate::import) struct MemoryCaptureUi {
    serial: Option<String>,
    pid: Option<u32>,
    target: Option<HeapCaptureTarget>,
    pub(in crate::import) in_progress: bool,
}

impl MemoryCaptureUi {
    fn select(&mut self, serial: Option<String>, pid: Option<u32>) -> bool {
        if self.serial == serial && self.pid == pid {
            return false;
        }
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
                let online = devices
                    .iter()
                    .any(|device| &device.serial == serial && device.is_online());
                let process = processes.iter().find(|process| process.pid == pid)?;
                (online && pid > 0).then(|| HeapCaptureTarget {
                    serial: serial.clone(),
                    pid,
                    process_name: process.name.clone(),
                    package_name: process.package_name.clone(),
                })
            });
        let changed = self.target != next;
        self.target = next;
        changed
    }
}

struct LoadedHeap {
    artifact: Arc<CapturedHeap>,
    session: Session,
    page: PageData,
    snapshot: Arc<aps_memory::MemorySnapshot>,
    import_duration: Duration,
}

fn load_capture(
    target: &HeapCaptureTarget,
    root: &Path,
    cancel: &CancellationToken,
) -> Result<LoadedHeap, UiText> {
    if !memory_worker_executable()?.is_file() {
        return Err(aps_memory::MemoryError::WorkerUnavailable
            .to_string()
            .into());
    }
    let artifact = Arc::new(transport::capture_heap(target, root, cancel)?);
    let started = Instant::now();
    let (session, page) = open_selected(
        Destination::Memory,
        &artifact.path,
        TraceView::Processes,
        cancel,
    )?;
    let Session::Memory(worker) = &session else {
        unreachable!("Memory importer returns a memory session");
    };
    let snapshot = worker
        .lock()
        .map_err(|_| UiText::from("Memory session is unavailable"))?
        .snapshot(cancel)
        .map_err(|error| UiText::from(error.to_string()))?;
    Ok(LoadedHeap {
        artifact,
        session,
        page,
        snapshot: Arc::new(snapshot),
        import_duration: started.elapsed(),
    })
}

impl ImportWorkspace {
    pub(crate) fn set_memory_capture_target(
        &mut self,
        serial: Option<String>,
        pid: Option<u32>,
        cx: &mut Context<Self>,
    ) {
        if self.memory_capture.select(serial, pid) && self.memory_capture.in_progress {
            self.cancel_work(cx);
        }
        cx.notify();
    }

    pub(crate) fn apply_memory_capture_targets(
        &mut self,
        devices: &[AdbDevice],
        processes: &[AndroidApplicationProcess],
        cx: &mut Context<Self>,
    ) {
        if self.memory_capture.apply_lists(devices, processes) && self.memory_capture.in_progress {
            self.cancel_work(cx);
        }
        cx.notify();
    }

    pub(super) fn memory_capture_block_reason(&self) -> Option<UiText> {
        if self.busy
            || self.export_busy
            || self.memory_state.loading
            || self.memory_state.instances_loading
            || self.memory_state.detail_loading
            || self.memory_state.comparison_loading
            || !self.memory_state.dominator_loading.is_empty()
        {
            return Some(TextKey::MemoryPageWorking.into());
        }
        if self.memory_snapshot_tabs().len() >= snapshots::MAX_OPEN_MEMORY_SNAPSHOTS {
            return Some(snapshots::memory_tab_limit_message(self.language));
        }
        self.memory_capture
            .target
            .is_none()
            .then(|| TextKey::MemoryPageCaptureSelectTarget.into())
    }

    pub(super) fn memory_capture_tooltip(&self) -> String {
        if let Some(reason) = self.memory_capture_block_reason() {
            return reason.render(self.language);
        }
        let target = self
            .memory_capture
            .target
            .as_ref()
            .expect("capture target is available");
        self.language.format(
            TextKey::MemoryPageCaptureTarget,
            &[
                ("process", &target.process_name),
                ("pid", &target.pid),
                ("serial", &target.serial),
            ],
        )
    }

    pub(crate) fn capture_memory_heap(&mut self, cx: &mut Context<Self>) {
        if self.kind != Destination::Memory {
            return;
        }
        if let Some(reason) = self.memory_capture_block_reason() {
            self.notice = Some(reason);
            cx.notify();
            return;
        }
        let root = crate::settings_path()
            .ok()
            .and_then(|path| path.parent().map(|path| path.join("memory-captures")));
        let Some(root) = root else {
            self.error = Some(TextKey::MemoryPageCaptureStorageUnavailable.into());
            cx.notify();
            return;
        };
        let target = self
            .memory_capture
            .target
            .clone()
            .expect("capture target is available");
        self.cancel.cancel();
        self.cancel = CancellationToken::new();
        self.generation += 1;
        let generation = self.generation;
        let cancel = self.cancel.clone();
        self.busy = true;
        self.memory_capture.in_progress = true;
        self.error = None;
        self.notice = None;
        self.memory_state.error = None;
        self.memory_state.notice = None;
        self.memory_state.loading_message = Some(self.language.format(
            TextKey::MemoryAppDumpingHeapFor,
            &[("arg_1", &target.process_name)],
        ));
        let task_target = target.clone();
        let task = cx
            .background_executor()
            .spawn(async move { load_capture(&task_target, &root, &cancel) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.generation != generation
                    || !this.memory_capture.in_progress
                    || this.memory_capture.target.as_ref() != Some(&target)
                {
                    return;
                }
                this.busy = false;
                this.memory_capture.in_progress = false;
                this.memory_state.loading_message = None;
                match result {
                    Ok(loaded) => {
                        let Some(id) = snapshots::new_memory_snapshot_id() else {
                            this.error =
                                Some(snapshots::memory_identity_exhausted_message(this.language));
                            cx.notify();
                            return;
                        };
                        if let Some(mut previous) = this.take_active_memory_snapshot() {
                            // Pending old-tab queries cannot publish into the new snapshot.
                            previous.state.instances_loading = false;
                            previous.state.detail_loading = false;
                            previous.state.comparison_loading = false;
                            previous.state.dominator_loading.clear();
                            this.memory_snapshots.push(previous);
                        }
                        this.path = Some(loaded.artifact.path.clone());
                        this.session = Some(loaded.session);
                        this.page = Some(loaded.page);
                        this.session_id = Some(new_session_id());
                        this.memory_snapshot_id = Some(id);
                        this.memory_state = MemoryState::new();
                        this.memory_state.reset_for_snapshot(
                            loaded.snapshot,
                            super::snapshot_label(&loaded.artifact.path),
                        );
                        this.memory_state.capture_artifact = Some(loaded.artifact);
                        this.memory_state.notice = Some(this.language.format(
                            TextKey::MemoryPageCaptureComplete,
                            &[("process", &target.process_name), ("pid", &target.pid)],
                        ));
                        this.import_started = None;
                        this.import_duration = Some(loaded.import_duration);
                    }
                    Err(error) => {
                        this.error = Some(UiText::format(
                            TextKey::MemoryPageCaptureFailed,
                            [("detail", error)],
                        ));
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn devices() -> Vec<AdbDevice> {
        aps_adb::parse_devices("serial device model:Pixel_8\n").unwrap()
    }

    fn processes() -> Vec<AndroidApplicationProcess> {
        vec![AndroidApplicationProcess {
            pid: 42,
            parent_pid: 1,
            name: "example.app:worker".into(),
            package_name: "example.app".into(),
            user: "u0_a123".into(),
        }]
    }

    #[::core::prelude::v1::test]
    fn selection_enables_capture_only_after_a_matching_online_process_is_published() {
        let mut workspace = ImportWorkspace::new(Destination::Memory, Language::English);
        assert!(workspace.memory_capture_block_reason().is_some());
        workspace
            .memory_capture
            .select(Some("serial".into()), Some(42));
        assert!(workspace.memory_capture_block_reason().is_some());
        workspace
            .memory_capture
            .apply_lists(&devices(), &processes());
        assert!(workspace.memory_capture_block_reason().is_none());
        assert!(
            workspace
                .memory_capture_tooltip()
                .contains("example.app:worker")
        );
        assert!(workspace.memory_capture_tooltip().contains("42"));
        workspace.busy = true;
        assert!(workspace.memory_capture_block_reason().is_some());
        workspace.busy = false;
        workspace.export_busy = true;
        assert!(workspace.memory_capture_block_reason().is_some());
        workspace.export_busy = false;
        workspace.memory_state.instances_loading = true;
        assert!(workspace.memory_capture_block_reason().is_some());
    }

    #[::core::prelude::v1::test]
    fn offline_missing_and_changed_processes_revoke_the_frozen_target() {
        let mut ui = MemoryCaptureUi::default();
        ui.select(Some("serial".into()), Some(42));
        assert!(ui.apply_lists(&devices(), &processes()));
        assert!(!ui.apply_lists(&devices(), &processes()));
        let mut changed = processes();
        changed[0].name = "other.app".into();
        changed[0].package_name = "other.app".into();
        assert!(ui.apply_lists(&devices(), &changed));
        assert_eq!(ui.target.as_ref().unwrap().process_name, "other.app");
        let offline = aps_adb::parse_devices("serial offline model:Pixel_8\n").unwrap();
        assert!(ui.apply_lists(&offline, &processes()));
        assert!(ui.target.is_none());
        ui.apply_lists(&devices(), &processes());
        assert!(ui.apply_lists(&devices(), &[]));
        assert!(ui.target.is_none());
        ui.apply_lists(&devices(), &processes());
        assert!(ui.select(None, None));
        assert!(ui.target.is_none());
        assert!(ui.serial.is_none());
        assert!(ui.pid.is_none());
    }

    #[::core::prelude::v1::test]
    fn heap_capture_is_wired_to_shared_selection_and_an_explicit_click() {
        let toolbar = include_str!("toolbar.rs");
        let heap_button = toolbar
            .split("\"memory-dump-heap\"")
            .nth(1)
            .unwrap()
            .split("\"memory-capture-native-heap\"")
            .next()
            .unwrap();
        assert!(heap_button.contains("self.memory_capture_block_reason().is_none()"));
        assert!(heap_button.contains("this.capture_memory_heap(cx)"));
        let shell = include_str!("../../shell.rs");
        assert!(shell.contains("memory.set_memory_capture_target(serial.clone(), pid, cx)"));
        assert!(
            shell.contains(
                "memory.apply_memory_capture_targets(&event.devices, &event.processes, cx)"
            )
        );
        let capture = include_str!("capture.rs")
            .split("#[cfg(test)]")
            .next()
            .unwrap();
        assert!(capture.contains("this.generation != generation"));
        assert!(capture.contains("this.memory_capture.target.as_ref() != Some(&target)"));
        assert!(capture.contains("this.memory_state.capture_artifact = Some(loaded.artifact)"));
        assert_eq!(
            include_str!("../memory.rs")
                .matches("this.memory_snapshot_id != snapshot_id")
                .count(),
            4
        );
    }
}

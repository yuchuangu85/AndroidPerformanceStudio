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
    pub(in crate::import::memory) fn target(&self) -> Option<&HeapCaptureTarget> {
        self.target.as_ref()
    }
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

static HISTORY_COMMIT: Mutex<()> = Mutex::new(());

struct HistoryCopy {
    path: PathBuf,
    remove_on_drop: bool,
}

impl Drop for HistoryCopy {
    fn drop(&mut self) {
        if self.remove_on_drop {
            let _ = std::fs::remove_file(&self.path);
        }
    }
}

fn persist_history_copy(source: &Path, root: &Path, digest: &str) -> Result<HistoryCopy, String> {
    let expected = SourceDigest::from_hex(digest)?;
    if !root.is_absolute() {
        return Err("Memory history requires an absolute Rust-owned directory".into());
    }
    aps_platform::create_private_directory(root).map_err(|error| error.to_string())?;
    let destination = root.join(format!("{}.hprof", expected.to_hex()));
    let verify_existing = || -> Result<(), String> {
        let metadata =
            std::fs::symlink_metadata(&destination).map_err(|error| error.to_string())?;
        if !metadata.is_file()
            || metadata.is_symlink()
            || SourceDigest::hash_file(&destination, aps_memory::MAX_INPUT_BYTES)? != expected
        {
            return Err("Retained Memory history artifact does not match its digest".into());
        }
        Ok(())
    };
    if std::fs::symlink_metadata(&destination).is_ok() {
        verify_existing()?;
        return Ok(HistoryCopy {
            path: destination,
            remove_on_drop: false,
        });
    }
    let staging = aps_platform::TempWorkspace::create(root).map_err(|error| error.to_string())?;
    let copied = staging.path().join("complete.hprof");
    super::exports::write_verified_copy(source, &copied, digest)?;
    // Publish only the complete verified inode, without replacing an existing copy.
    let created = match std::fs::hard_link(&copied, &destination) {
        Ok(()) => true,
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {
            verify_existing()?;
            false
        }
        Err(error) => return Err(error.to_string()),
    };
    Ok(HistoryCopy {
        path: destination,
        remove_on_drop: created,
    })
}

fn commit_history(source: &Path, root: &Path, digest: &str) -> Result<PathBuf, String> {
    let _guard = HISTORY_COMMIT
        .lock()
        .map_err(|_| "Memory history transaction is unavailable")?;
    let mut retained =
        persist_history_copy(source, &root.join("memory-history-artifacts"), digest)?;
    crate::home_recent::RecentPathStore::new(root.join("recent-artifacts")).record(
        crate::home_recent::RecordedFeature::Memory,
        crate::home_recent::RecentPathEntry {
            path: retained.path.clone(),
            package_name: None,
            captured_at_unix_ms: None,
        },
    )?;
    retained.remove_on_drop = false;
    Ok(retained.path.clone())
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
                        let history_artifact = loaded.artifact.clone();
                        let history_digest = loaded.snapshot.source_digest.clone();
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
                        this.retain_memory_capture_history(history_artifact, history_digest, cx);
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

    fn retain_memory_capture_history(
        &mut self,
        artifact: Arc<CapturedHeap>,
        digest: String,
        cx: &mut Context<Self>,
    ) {
        let snapshot_id = self.memory_snapshot_id;
        let root = crate::settings_path()
            .ok()
            .and_then(|path| path.parent().map(PathBuf::from));
        let task = cx.background_executor().spawn(async move {
            let root = root.ok_or("Memory history storage is unavailable")?;
            commit_history(&artifact.path, &root, &digest)
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(_) => {
                        if let Some(studio) = &this.studio {
                            studio
                                .update(cx, |studio, cx| studio.refresh_home_recent(cx))
                                .ok();
                        }
                    }
                    Err(error) => this.publish_memory_history_error(snapshot_id, error),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }
    fn publish_memory_history_error(&mut self, snapshot_id: Option<u64>, error: String) {
        if snapshot_id.is_some() && self.memory_snapshot_id == snapshot_id {
            self.memory_state.notice = Some(error);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[::core::prelude::v1::test]
    fn captured_memory_history_survives_scratch_cleanup_and_is_deduplicated() {
        let root = aps_platform::TempWorkspace::create(&std::env::temp_dir()).unwrap();
        let scratch = aps_platform::TempWorkspace::create(root.path()).unwrap();
        let source = scratch.path().join("capture.raw.hprof");
        std::fs::write(&source, b"verified captured heap fixture").unwrap();
        let digest = SourceDigest::hash_file(&source, aps_memory::MAX_INPUT_BYTES)
            .unwrap()
            .to_hex();
        let retained = root.path().join("memory-history-artifacts");
        let path = commit_history(&source, root.path(), &digest).unwrap();
        assert_eq!(commit_history(&source, root.path(), &digest).unwrap(), path);
        assert_eq!(std::fs::read_dir(&retained).unwrap().count(), 1);
        drop(scratch);
        assert!(!source.exists());
        assert_eq!(
            std::fs::read(&path).unwrap(),
            b"verified captured heap fixture"
        );
        let history =
            crate::home_recent::RecentPathStore::new(root.path().join("recent-artifacts"));
        assert_eq!(
            history
                .load(crate::home_recent::RecordedFeature::Memory, 1)
                .unwrap()[0]
                .path,
            path
        );
        history
            .clear(crate::home_recent::RecordedFeature::Memory)
            .unwrap();
        assert!(path.is_file());
    }

    #[::core::prelude::v1::test]
    fn memory_history_rejects_mutated_source_and_never_replaces_mismatched_copy() {
        let root = aps_platform::TempWorkspace::create(&std::env::temp_dir()).unwrap();
        let source = root.path().join("capture.raw.hprof");
        std::fs::write(&source, b"original heap").unwrap();
        let digest = SourceDigest::hash_file(&source, aps_memory::MAX_INPUT_BYTES)
            .unwrap()
            .to_hex();
        let retained = root.path().join("memory-history-artifacts");
        std::fs::write(&source, b"mutated heap").unwrap();
        assert!(commit_history(&source, root.path(), &digest).is_err());
        assert_eq!(std::fs::read_dir(&retained).unwrap().count(), 0);
        std::fs::write(&source, b"original heap").unwrap();
        let path = commit_history(&source, root.path(), &digest).unwrap();
        std::fs::write(&path, b"corrupt retained heap").unwrap();
        assert!(commit_history(&source, root.path(), &digest).is_err());
        assert_eq!(std::fs::read(&path).unwrap(), b"corrupt retained heap");
        assert_eq!(std::fs::read_dir(&retained).unwrap().count(), 1);
    }

    #[::core::prelude::v1::test]
    fn failed_history_registration_rolls_back_only_a_newly_retained_heap() {
        let root = aps_platform::TempWorkspace::create(&std::env::temp_dir()).unwrap();
        let source = root.path().join("captured.hprof");
        std::fs::write(&source, b"captured heap").unwrap();
        let digest = SourceDigest::hash_file(&source, aps_memory::MAX_INPUT_BYTES)
            .unwrap()
            .to_hex();
        let index_root = root.path().join("recent-artifacts");
        std::fs::create_dir(&index_root).unwrap();
        let index = index_root.join("home-recent-memory-v1.json");
        std::fs::write(&index, b"corrupt history").unwrap();
        let destination = root
            .path()
            .join("memory-history-artifacts")
            .join(format!("{digest}.hprof"));
        assert!(commit_history(&source, root.path(), &digest).is_err());
        assert!(!destination.exists());
        assert_eq!(std::fs::read(&source).unwrap(), b"captured heap");
        assert_eq!(std::fs::read(&index).unwrap(), b"corrupt history");
        std::fs::remove_file(&index).unwrap();
        assert_eq!(
            commit_history(&source, root.path(), &digest).unwrap(),
            destination
        );
        std::fs::write(&index, b"corrupt again").unwrap();
        assert!(commit_history(&source, root.path(), &digest).is_err());
        assert_eq!(std::fs::read(destination).unwrap(), b"captured heap");
    }

    #[::core::prelude::v1::test]
    fn delayed_history_errors_cannot_replace_another_snapshots_notice() {
        let mut workspace = ImportWorkspace::new(Destination::Memory, Language::English);
        workspace.memory_snapshot_id = Some(7);
        workspace.memory_state.notice = Some("current snapshot".into());
        workspace.publish_memory_history_error(Some(6), "old failure".into());
        assert_eq!(
            workspace.memory_state.notice.as_deref(),
            Some("current snapshot")
        );
        workspace.memory_snapshot_id = None;
        workspace.publish_memory_history_error(Some(7), "closed failure".into());
        workspace.publish_memory_history_error(None, "no origin".into());
        assert_eq!(
            workspace.memory_state.notice.as_deref(),
            Some("current snapshot")
        );
        workspace.memory_snapshot_id = Some(7);
        workspace.publish_memory_history_error(Some(7), "current failure".into());
        assert_eq!(
            workspace.memory_state.notice.as_deref(),
            Some("current failure")
        );
    }

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

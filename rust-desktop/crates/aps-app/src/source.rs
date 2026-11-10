//! Source Workspaces: Compose-parity three-pane source browser.
//!
//! Compose (desktop-viewer/desktop-app/.../SourceWorkspacesPage.kt and the
//! source-workspace module) is the behavior oracle. The page keeps one explicit
//! local source root per workspace, an immutable cached snapshot, a directory
//! tree and a verified read-only viewer. Source text never leaves the machine.

mod ai_context;
mod code_viewer;
mod layout;
mod lsp;
mod page;
mod permissions;
mod service;
mod tree;

use crate::notifications::{NoticeSnapshot, NoticeSource};
use aps_ai::AnalysisRequest;
use aps_source::{
    PerformanceEvidenceId, ResolutionCandidate, ResolutionConfidence, SourceFile, SourceLocation,
    SourceProviderConfig, SourceRange, SourceResolutionEvidence, SourceSnapshot,
    SourceWorkspace as WorkspaceRecord, SourceWorkspaceId, SourceWorkspacePhase,
    VerifiedSourceContent,
};
use aps_ui_foundation::{Language, TextKey};
use gpui_kit::{prelude::*, *};
use std::{
    collections::HashMap,
    path::{Path, PathBuf},
    sync::{Arc, Mutex},
    time::Duration,
};

const PROGRESS_POLL: Duration = Duration::from_millis(80);

pub struct SourceWorkspace {
    pub(in crate::source) language: Language,
    pub(in crate::source) paths: service::SourcePaths,
    pub(in crate::source) workspaces: Vec<WorkspaceRecord>,
    pub(in crate::source) list_loaded: bool,
    pub(in crate::source) list_generation: u64,
    pub(in crate::source) list_error: Option<String>,
    pub(in crate::source) permissions: permissions::SourcePermissions,
    pub(in crate::source) permissions_error: Option<String>,
    /// Workspace id hex currently running an index/refresh cycle.
    pub(in crate::source) sync_in_flight: HashMap<String, service::Progress>,
    pub(in crate::source) add_in_flight: bool,
    pub(in crate::source) add_display_name: Option<String>,
    pub(in crate::source) add_progress: Option<service::Progress>,
    pub(in crate::source) selected_workspace: Option<SourceWorkspaceId>,
    pub(in crate::source) snapshot: Option<SourceSnapshot>,
    /// Snapshot and file list that own the selected location. Unlike
    /// `snapshot`/`files`, these can be an older immutable snapshot after a
    /// Sync replaced the active one (Compose resolves the browser snapshot by
    /// the selected location first).
    pub(in crate::source) location_snapshot: Option<SourceSnapshot>,
    pub(in crate::source) location_files: Vec<SourceFile>,
    pub(in crate::source) files: Vec<SourceFile>,
    pub(in crate::source) files_loaded: bool,
    pub(in crate::source) files_generation: u64,
    pub(in crate::source) selected_file: Option<String>,
    pub(in crate::source) selected_location: Option<SourceLocation>,
    pub(in crate::source) content: Option<VerifiedSourceContent>,
    pub(in crate::source) content_generation: u64,
    pub(in crate::source) lsp_enabled: bool,
    pub(in crate::source) lsp_usages: Vec<lsp::Location>,
    lsp: lsp::LspManager,
    pub(in crate::source) candidate: Option<ResolutionCandidate>,
    pub(in crate::source) pending_location: Option<SourceLocation>,
    pub(in crate::source) page_error: Option<String>,
    pub(in crate::source) studio: Option<WeakEntity<crate::shell::Studio>>,
    pub(in crate::source) ui: page::PageUi,
}

impl SourceWorkspace {
    pub fn new(
        language: Language,
        metadata_path: Option<PathBuf>,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) -> Self {
        let paths = service::SourcePaths::from_metadata_path(metadata_path.as_deref());
        let (permissions, permissions_error) = match paths.data_dir.as_ref() {
            Some(directory) => {
                match permissions::SourcePermissions::load(&directory.join(permissions::FILE_NAME))
                {
                    Ok(permissions) => (permissions, None),
                    Err(error) => (permissions::SourcePermissions::default(), Some(error)),
                }
            }
            None => (permissions::SourcePermissions::default(), None),
        };
        let lsp_data_dir = paths
            .data_dir
            .clone()
            .unwrap_or_else(std::env::temp_dir)
            .join("source-lsp");
        let mut this = Self {
            language,
            paths,
            workspaces: Vec::new(),
            list_loaded: false,
            list_generation: 0,
            list_error: None,
            permissions,
            permissions_error,
            sync_in_flight: HashMap::new(),
            add_in_flight: false,
            add_display_name: None,
            add_progress: None,
            selected_workspace: None,
            snapshot: None,
            location_snapshot: None,
            location_files: Vec::new(),
            files: Vec::new(),
            files_loaded: false,
            files_generation: 0,
            selected_file: None,
            selected_location: None,
            content: None,
            content_generation: 0,
            lsp_enabled: false,
            lsp_usages: Vec::new(),
            lsp: lsp::LspManager::new(lsp_data_dir),
            candidate: None,
            pending_location: None,
            page_error: None,
            studio: None,
            ui: page::PageUi::new(window, cx),
        };
        this.reload_workspaces(cx);
        this
    }

    pub fn set_studio(&mut self, studio: WeakEntity<crate::shell::Studio>) {
        self.studio = Some(studio);
    }

    pub fn set_lsp_enabled(&mut self, enabled: bool, cx: &mut Context<Self>) {
        if self.lsp_enabled == enabled {
            return;
        }
        self.lsp_enabled = enabled;
        if !enabled {
            self.lsp.shutdown();
            self.lsp_usages.clear();
        }
        self.ui.set_lsp_enabled(enabled, cx);
        cx.notify();
    }

    pub fn set_language(
        &mut self,
        language: Language,
        window: &mut Window,
        cx: &mut Context<Self>,
    ) {
        self.language = language;
        self.ui.set_language(language, window, cx);
        cx.notify();
    }

    /// Runs the workspace reload without blocking the foreground executor.
    pub(super) fn reload_workspaces(&mut self, cx: &mut Context<Self>) {
        self.list_generation += 1;
        let generation = self.list_generation;
        if self.paths.database.is_none() {
            self.list_loaded = true;
            self.list_error = Some(
                self.language
                    .text(TextKey::SourceLocalMetadataIsUnavailableRegistrationsLastOnly)
                    .to_owned(),
            );
            cx.notify();
            return;
        }
        let paths = self.paths.clone();
        let task = cx
            .background_executor()
            .spawn(async move { service::load_workspaces(&paths) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.list_generation != generation {
                    return;
                }
                this.list_loaded = true;
                match result {
                    Ok(mut records) => {
                        this.overlay_permissions(&mut records);
                        this.workspaces = records;
                        this.list_error = None;
                    }
                    Err(error) => this.list_error = Some(error),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn overlay_permissions(&self, records: &mut [WorkspaceRecord]) {
        for record in records.iter_mut() {
            record.allow_ai_source_upload = self.permissions.allowed(&record.id.to_hex());
        }
    }

    fn merge_workspace(&mut self, mut record: WorkspaceRecord) {
        record.allow_ai_source_upload = self.permissions.allowed(&record.id.to_hex());
        match self
            .workspaces
            .iter_mut()
            .find(|existing| existing.id == record.id)
        {
            Some(existing) => *existing = record,
            None => self.workspaces.push(record),
        }
    }

    fn failure(&self, error: impl std::fmt::Display) -> String {
        self.language.format(
            TextKey::SourceOperationFailed,
            &[("message", &format!("{error}"))],
        )
    }

    /// "+ Local": opens the directory picker, then registers and indexes.
    pub(super) fn pick_local_workspace(&mut self, cx: &mut Context<Self>) {
        if self.add_in_flight {
            return;
        }
        if self.paths.database.is_none() {
            self.page_error = Some(
                self.language
                    .text(TextKey::SourceLocalMetadataIsUnavailableRegistrationsLastOnly)
                    .to_owned(),
            );
            cx.notify();
            return;
        }
        let prompt: SharedString = self
            .language
            .text(TextKey::SourceChooseLocalDirectory)
            .into();
        let picker = cx.prompt_for_paths(PathPromptOptions {
            files: false,
            directories: true,
            multiple: false,
            prompt: Some(prompt),
        });
        cx.spawn(async move |this, cx| {
            let result = picker.await;
            this.update(cx, |this, cx| match result {
                Ok(Ok(Some(paths))) if paths.len() == 1 => {
                    this.start_add_local(paths[0].clone(), cx);
                }
                Ok(Err(error)) => {
                    this.page_error = Some(this.failure(error));
                    cx.notify();
                }
                _ => {}
            })
            .ok();
        })
        .detach();
    }

    fn start_add_local(&mut self, root: PathBuf, cx: &mut Context<Self>) {
        if self.add_in_flight {
            return;
        }
        let display_name = root
            .file_name()
            .and_then(|name| name.to_str())
            .filter(|name| !name.is_empty())
            .unwrap_or("source")
            .to_owned();
        let paths = self.paths.clone();
        let pending_name = display_name.clone();
        let (sender, receiver) = std::sync::mpsc::channel::<service::Progress>();
        let slot = Arc::new(Mutex::new(None::<Result<WorkspaceRecord, String>>));
        let background_slot = slot.clone();
        let task = cx.background_executor().spawn(async move {
            let result = service::add_local(&paths, root, pending_name, &mut |progress| {
                let _ = sender.send(progress);
            });
            *background_slot.lock().expect("add slot") = Some(result);
        });
        self.add_in_flight = true;
        self.add_display_name = Some(display_name);
        self.add_progress = Some(service::Progress {
            phase: SourceWorkspacePhase::Registering,
            progress: 0.0,
            message: None,
        });
        cx.notify();
        let executor = cx.background_executor().clone();
        cx.spawn(async move |this, cx| {
            let _keep_alive = task;
            loop {
                let updates: Vec<service::Progress> = receiver.try_iter().collect();
                let done = slot.lock().expect("add slot").take();
                let finished = this
                    .update(cx, |this, cx| {
                        if let Some(progress) = updates.last() {
                            this.add_progress = Some(progress.clone());
                        }
                        if let Some(result) = done {
                            this.add_in_flight = false;
                            this.add_display_name = None;
                            this.add_progress = None;
                            match result {
                                Ok(_workspace) => this.reload_workspaces(cx),
                                Err(error) => this.page_error = Some(this.failure(error)),
                            }
                            cx.notify();
                            return true;
                        }
                        if !updates.is_empty() {
                            cx.notify();
                        }
                        false
                    })
                    .unwrap_or(true);
                if finished {
                    break;
                }
                executor.timer(PROGRESS_POLL).await;
            }
        })
        .detach();
    }

    /// "Sync": rebuilds the immutable snapshot for one workspace in the background.
    pub(super) fn sync_workspace(&mut self, id: SourceWorkspaceId, cx: &mut Context<Self>) {
        let key = id.to_hex();
        if self.sync_in_flight.contains_key(&key) {
            return;
        }
        let paths = self.paths.clone();
        let (sender, receiver) = std::sync::mpsc::channel::<service::Progress>();
        let slot = Arc::new(Mutex::new(None::<Result<WorkspaceRecord, String>>));
        let background_slot = slot.clone();
        let task = cx.background_executor().spawn(async move {
            let result = service::refresh(&paths, id, &mut |progress| {
                let _ = sender.send(progress);
            });
            *background_slot.lock().expect("sync slot") = Some(result);
        });
        self.sync_in_flight.insert(
            key.clone(),
            service::Progress {
                phase: SourceWorkspacePhase::Registering,
                progress: 0.0,
                message: None,
            },
        );
        cx.notify();
        let executor = cx.background_executor().clone();
        cx.spawn(async move |this, cx| {
            let _keep_alive = task;
            loop {
                let updates: Vec<service::Progress> = receiver.try_iter().collect();
                let done = slot.lock().expect("sync slot").take();
                let finished = this
                    .update(cx, |this, cx| {
                        let had_updates = !updates.is_empty();
                        for progress in updates {
                            if this.sync_in_flight.contains_key(&key) {
                                this.apply_sync_progress(id, progress);
                            }
                        }
                        if let Some(result) = done {
                            this.sync_in_flight.remove(&key);
                            match result {
                                Ok(workspace) => {
                                    this.merge_workspace(workspace);
                                    if this.selected_workspace == Some(id) {
                                        this.load_snapshot(id, cx);
                                    }
                                }
                                Err(error) => this.page_error = Some(this.failure(error)),
                            }
                            cx.notify();
                            return true;
                        }
                        if had_updates {
                            cx.notify();
                        }
                        false
                    })
                    .unwrap_or(true);
                if finished {
                    break;
                }
                executor.timer(PROGRESS_POLL).await;
            }
        })
        .detach();
    }

    fn apply_sync_progress(&mut self, id: SourceWorkspaceId, progress: service::Progress) {
        if let Some(record) = self
            .workspaces
            .iter_mut()
            .find(|workspace| workspace.id == id)
        {
            record.phase = progress.phase;
            record.progress = progress.progress;
            record.message = progress.message.clone();
        }
        self.sync_in_flight.insert(id.to_hex(), progress);
    }

    pub(super) fn remove_workspace(&mut self, id: SourceWorkspaceId, cx: &mut Context<Self>) {
        let paths = self.paths.clone();
        let task = cx
            .background_executor()
            .spawn(async move { service::remove(&paths, id) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(()) => {
                        if this.selected_workspace == Some(id) {
                            this.clear_selection();
                        }
                        this.reload_workspaces(cx);
                    }
                    Err(error) => this.page_error = Some(this.failure(error)),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Toggles the per-workspace AI source-upload grant in the Rust-owned policy.
    pub(super) fn toggle_ai_source_upload(
        &mut self,
        id: SourceWorkspaceId,
        cx: &mut Context<Self>,
    ) {
        let Some(directory) = self.paths.data_dir.clone() else {
            self.page_error = Some(
                self.language
                    .text(TextKey::SourceLocalMetadataIsUnavailableRegistrationsLastOnly)
                    .to_owned(),
            );
            cx.notify();
            return;
        };
        let hex = id.to_hex();
        let allowed = !self.permissions.allowed(&hex);
        let path = directory.join(permissions::FILE_NAME);
        let result = self
            .permissions
            .with_allowed(&hex, allowed)
            .and_then(|draft| draft.save(&path).map(|_| draft));
        match result {
            Ok(next) => {
                self.permissions = next;
                self.permissions_error = None;
                for workspace in self.workspaces.iter_mut() {
                    if workspace.id == id {
                        workspace.allow_ai_source_upload = allowed;
                    }
                }
            }
            Err(error) => {
                self.permissions_error = Some(error.clone());
                self.page_error = Some(self.failure(error));
            }
        }
        cx.notify();
    }

    pub(super) fn select_workspace(&mut self, id: SourceWorkspaceId, cx: &mut Context<Self>) {
        // Compose's onSelect clears the file/location/content state even when
        // the already-selected card is clicked again.
        self.selected_workspace = Some(id);
        self.clear_file_selection();
        self.load_snapshot(id, cx);
        cx.notify();
    }

    fn clear_selection(&mut self) {
        self.selected_workspace = None;
        self.clear_file_selection();
    }

    fn clear_file_selection(&mut self) {
        self.selected_file = None;
        self.selected_location = None;
        self.content = None;
        self.candidate = None;
        self.pending_location = None;
        self.snapshot = None;
        self.location_snapshot = None;
        self.location_files.clear();
        self.files.clear();
        self.files_loaded = false;
        self.files_generation += 1;
        self.content_generation += 1;
    }

    fn load_snapshot(&mut self, id: SourceWorkspaceId, cx: &mut Context<Self>) {
        self.files_generation += 1;
        let generation = self.files_generation;
        let snapshot_id = self
            .workspaces
            .iter()
            .find(|workspace| workspace.id == id)
            .and_then(|workspace| workspace.active_snapshot_id);
        let Some(snapshot_id) = snapshot_id else {
            self.snapshot = None;
            self.files.clear();
            self.files_loaded = true;
            cx.notify();
            return;
        };
        let paths = self.paths.clone();
        let task = cx.background_executor().spawn(async move {
            let snapshot = service::snapshot(&paths, snapshot_id)?;
            let files = service::files(&paths, snapshot_id)?;
            Ok::<_, String>((snapshot, files))
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.files_generation != generation {
                    return;
                }
                match result {
                    Ok((snapshot, files)) => {
                        this.snapshot = snapshot;
                        this.files = files;
                        this.files_loaded = true;
                        if let Some(location) = this.pending_location.clone() {
                            let matches = this
                                .snapshot
                                .as_ref()
                                .map(|snapshot| snapshot.id == location.snapshot_id)
                                .unwrap_or(false);
                            if matches {
                                this.pending_location = None;
                                this.show_location(location, cx);
                            }
                        }
                    }
                    Err(error) => {
                        this.files_loaded = true;
                        this.page_error = Some(this.failure(error));
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// Opens one indexed file, optionally focused on a line range.
    pub(super) fn open_file(
        &mut self,
        relative_path: String,
        range: Option<SourceRange>,
        cx: &mut Context<Self>,
    ) {
        let (Some(workspace_id), Some(snapshot)) = (self.selected_workspace, self.snapshot.clone())
        else {
            return;
        };
        let Some(file) = self
            .files
            .iter()
            .find(|file| file.relative_path == relative_path)
            .cloned()
        else {
            return;
        };
        let location = SourceLocation {
            workspace_id,
            snapshot_id: snapshot.id,
            relative_path: file.relative_path.clone(),
            range,
            content_hash: file.content_hash,
        };
        self.show_location(location, cx);
    }

    fn show_location(&mut self, location: SourceLocation, cx: &mut Context<Self>) {
        self.selected_file = Some(location.relative_path.clone());
        self.selected_location = Some(location.clone());
        self.pending_location = None;
        self.candidate = self
            .candidate
            .as_ref()
            .filter(|candidate| candidate.location == location)
            .cloned();
        self.ui.expand_ancestors(&location.relative_path);
        self.read_location(location, cx);
        cx.notify();
    }

    fn read_location(&mut self, location: SourceLocation, cx: &mut Context<Self>) {
        self.content_generation += 1;
        let generation = self.content_generation;
        self.content = None;
        if self.candidate.is_some() {
            // Keep a candidate only when it still belongs to this location.
            let matches = self
                .selected_location
                .as_ref()
                .map(|selected| {
                    self.candidate
                        .as_ref()
                        .map(|candidate| &candidate.location == selected)
                        .unwrap_or(false)
                })
                .unwrap_or(false);
            if !matches {
                self.candidate = None;
            }
        }
        let paths = self.paths.clone();
        let read = location.clone();
        let task = cx.background_executor().spawn(async move {
            let content = service::read(&paths, &read)?;
            let snapshot = service::snapshot(&paths, read.snapshot_id)?;
            let files = service::files(&paths, read.snapshot_id)?;
            Ok::<_, String>((content, snapshot, files))
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.content_generation != generation {
                    return;
                }
                match result {
                    Ok((content, snapshot, files)) => {
                        this.location_snapshot = snapshot;
                        this.location_files = files;
                        this.content = Some(content);
                        this.page_error = None;
                    }
                    Err(error) => {
                        this.content = None;
                        this.page_error = Some(this.failure(error));
                    }
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// "Copy location": "<workspace>@<revision>:<path>:<line>" as in Compose.
    pub(super) fn copy_location(&mut self, cx: &mut Context<Self>) {
        let (Some(location), Some(workspace)) =
            (self.selected_location.clone(), self.selected_workspace)
        else {
            return;
        };
        let Some(record) = self.workspaces.iter().find(|record| record.id == workspace) else {
            return;
        };
        let revision = self
            .snapshot
            .as_ref()
            .map(|snapshot| snapshot.immutable_revision.clone())
            .unwrap_or_default();
        let line = location
            .range
            .as_ref()
            .map(|range| range.start_line)
            .unwrap_or(1);
        let value = format!(
            "{}@{}:{}:{}",
            record.display_name, revision, location.relative_path, line
        );
        cx.write_to_clipboard(ClipboardItem::new_string(value));
    }

    pub(super) fn open_location_in_ide(&mut self, cx: &mut Context<Self>) {
        let (Some(location), Some(workspace)) =
            (self.selected_location.clone(), self.selected_workspace)
        else {
            return;
        };
        let Some(record) = self.workspaces.iter().find(|record| record.id == workspace) else {
            return;
        };
        let SourceProviderConfig::Local { root } = &record.config else {
            self.page_error = Some(self.failure("only local source workspaces can open in an IDE"));
            cx.notify();
            return;
        };
        let path = root.join(&location.relative_path);
        open_external_path(&path);
    }

    /// Opens a persisted resolution candidate (AI finding / archived result).
    /// Compose only lets non-Weak candidates navigate; the same rule applies.
    pub(crate) fn open_candidate_hex(&mut self, id: &str, cx: &mut Context<Self>) {
        let Ok(id) = aps_source::ResolutionCandidateId::from_hex(id) else {
            self.page_error = Some(self.failure("source candidate identity is invalid"));
            cx.notify();
            return;
        };
        if self.paths.database.is_none() {
            self.page_error = Some(
                self.language
                    .text(TextKey::SourceLocalMetadataIsUnavailableRegistrationsLastOnly)
                    .to_owned(),
            );
            cx.notify();
            return;
        }
        let paths = self.paths.clone();
        let task = cx
            .background_executor()
            .spawn(async move { service::candidate(&paths, id) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(Some(candidate)) if candidate.confidence != ResolutionConfidence::Weak => {
                        let location = candidate.location.clone();
                        this.navigate_to_source(location, cx);
                        this.candidate = Some(candidate);
                    }
                    Ok(Some(_)) => {
                        this.page_error =
                            Some(this.language.text(TextKey::SourceCandidateWeak).to_owned());
                    }
                    Ok(None) => {
                        this.page_error = Some(
                            this.language
                                .text(TextKey::SourceCandidateUnavailable)
                                .to_owned(),
                        );
                    }
                    Err(error) => this.page_error = Some(this.failure(error)),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    /// External navigation from CPU/AI/profile evidence.
    pub(crate) fn navigate_to_source(&mut self, location: SourceLocation, cx: &mut Context<Self>) {
        self.selected_workspace = Some(location.workspace_id);
        self.selected_file = Some(location.relative_path.clone());
        self.selected_location = Some(location.clone());
        self.content = None;
        self.candidate = None;
        self.ui.expand_ancestors(&location.relative_path);
        self.pending_location = Some(location.clone());
        self.load_snapshot(location.workspace_id, cx);
        self.read_location(location, cx);
        cx.notify();
    }

    /// CPU symbol navigation: resolves the symbol across indexed local snapshots.
    pub fn navigate_to_symbol(&mut self, symbol: String, resource: String, cx: &mut Context<Self>) {
        let workspace_ids: Vec<SourceWorkspaceId> = self
            .workspaces
            .iter()
            .filter(|workspace| workspace.active_snapshot_id.is_some())
            .map(|workspace| workspace.id)
            .collect();
        if workspace_ids.is_empty() || self.paths.database.is_none() {
            self.page_error = Some(
                self.language
                    .text(TextKey::SourceOpenFromCpuRequiresIndex)
                    .to_owned(),
            );
            cx.notify();
            return;
        }
        let paths = self.paths.clone();
        let evidence = navigation_evidence(&symbol, &resource);
        let task = cx.background_executor().spawn(async move {
            let candidates = service::resolve(&paths, &workspace_ids, &evidence)?;
            let chosen = candidates
                .iter()
                .find(|candidate| candidate.confidence != ResolutionConfidence::Weak)
                .cloned();
            let Some(chosen) = chosen else {
                return Ok::<_, String>(None);
            };
            let snapshot = service::snapshot(&paths, chosen.location.snapshot_id)?;
            let files = service::files(&paths, chosen.location.snapshot_id)?;
            let _ = service::save_candidates(&paths, &candidates);
            let content = service::read(&paths, &chosen.location).ok();
            Ok(Some((chosen, snapshot, files, content)))
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(Some((candidate, snapshot, files, content))) => {
                        this.selected_workspace = Some(candidate.location.workspace_id);
                        this.selected_file = Some(candidate.location.relative_path.clone());
                        this.selected_location = Some(candidate.location.clone());
                        this.snapshot = snapshot.clone();
                        this.location_snapshot = snapshot;
                        this.files = files.clone();
                        this.location_files = files;
                        this.files_loaded = true;
                        this.files_generation += 1;
                        this.content_generation += 1;
                        this.content = content;
                        this.candidate = Some(candidate.clone());
                        this.pending_location = None;
                        this.page_error = None;
                        this.ui.expand_ancestors(&candidate.location.relative_path);
                    }
                    Ok(None) => {
                        this.page_error = Some(
                            this.language
                                .text(TextKey::SourceOpenFromCpuNoSymbolDeclaration)
                                .to_owned(),
                        );
                    }
                    Err(error) => this.page_error = Some(this.failure(error)),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    pub(in crate::source) fn handle_lsp_click(
        &mut self,
        identifier: String,
        line: usize,
        character: usize,
        references: bool,
        cx: &mut Context<Self>,
    ) {
        if !self.lsp_enabled {
            return;
        }
        let Some(location) = self.selected_location.clone() else {
            return;
        };
        let Some(content) = self.content.as_ref() else {
            return;
        };
        let Some(workspace) = self
            .workspaces
            .iter()
            .find(|workspace| workspace.id == location.workspace_id)
        else {
            return;
        };
        let SourceProviderConfig::Local { root } = &workspace.config else {
            return;
        };
        let Some(language_id) = language_id_for_path(&location.relative_path) else {
            self.page_error = Some(self.language.text(TextKey::SourceLspUnavailable).to_owned());
            cx.notify();
            return;
        };
        eprintln!(
            "APS-LSP click identifier={:?} line={} character={} file={}",
            identifier, line, character, location.relative_path
        );
        let root = root.clone();
        let file_path = root.join(&location.relative_path);
        let text = content.text.clone();
        let manager = self.lsp.clone();
        let kind = if references {
            lsp::NavigationKind::References
        } else {
            lsp::NavigationKind::Definition
        };
        let task = cx.background_executor().spawn(async move {
            manager.navigate(
                &root,
                language_id,
                &file_path,
                &text,
                line as u32,
                character as u32,
                kind,
                identifier,
            )
        });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                match result {
                    Ok(result) if result.kind == lsp::NavigationKind::Definition => {
                        if let Some(target) = result.locations.first() {
                            this.open_lsp_location(target.clone(), cx);
                        } else {
                            this.page_error = Some(
                                this.language
                                    .text(TextKey::SourceLspNoDefinition)
                                    .to_owned(),
                            );
                        }
                    }
                    Ok(result) => {
                        this.lsp_usages = dedupe_locations(result.locations)
                            .into_iter()
                            .take(200)
                            .collect();
                        if this.lsp_usages.is_empty() {
                            this.page_error =
                                Some(this.language.text(TextKey::SourceLspNoUsages).to_owned());
                        } else {
                            this.page_error = None;
                        }
                    }
                    Err(error) => this.page_error = Some(this.lsp_error(error)),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    pub(in crate::source) fn open_lsp_usage(&mut self, index: usize, cx: &mut Context<Self>) {
        let Some(location) = self.lsp_usages.get(index).cloned() else {
            return;
        };
        self.open_lsp_location(location, cx);
    }

    pub(in crate::source) fn clear_lsp_usages(&mut self, cx: &mut Context<Self>) {
        self.lsp_usages.clear();
        cx.notify();
    }

    fn open_lsp_location(&mut self, location: lsp::Location, cx: &mut Context<Self>) {
        let Some(root) = self.local_root() else {
            return;
        };
        // Language servers commonly return canonical paths; the workspace
        // root may be reached through a symlink (for example /tmp on macOS).
        let canonical_root = std::fs::canonicalize(&root).unwrap_or_else(|_| root.clone());
        let canonical_target =
            std::fs::canonicalize(&location.path).unwrap_or_else(|_| location.path.clone());
        let relative_path = match canonical_target.strip_prefix(&canonical_root) {
            Ok(path) => path.to_string_lossy().replace('\\', "/"),
            Err(_) => {
                self.page_error = Some(
                    self.language
                        .text(TextKey::SourceLspNoDefinition)
                        .to_owned(),
                );
                return;
            }
        };
        let Some(file) = self
            .location_files
            .iter()
            .chain(self.files.iter())
            .find(|file| file.relative_path == relative_path)
            .cloned()
        else {
            self.page_error = Some(
                self.language
                    .text(TextKey::SourceLspNoDefinition)
                    .to_owned(),
            );
            return;
        };
        let Some(workspace_id) = self.selected_workspace.or_else(|| {
            self.location_snapshot
                .as_ref()
                .map(|snapshot| snapshot.workspace_id)
        }) else {
            return;
        };
        let line = location.line.saturating_add(1);
        let target = SourceLocation {
            workspace_id,
            snapshot_id: file.snapshot_id,
            relative_path: file.relative_path,
            range: Some(SourceRange {
                start_line: line,
                end_line: line,
            }),
            content_hash: file.content_hash,
        };
        self.lsp_usages.clear();
        self.navigate_to_source(target, cx);
    }

    fn local_root(&self) -> Option<PathBuf> {
        let workspace_id = self.selected_location.as_ref()?.workspace_id;
        let workspace = self
            .workspaces
            .iter()
            .find(|workspace| workspace.id == workspace_id)?;
        match &workspace.config {
            SourceProviderConfig::Local { root } => Some(root.clone()),
            _ => None,
        }
    }

    fn lsp_error(&self, error: String) -> String {
        if error.contains("not set")
            || error.contains("no local language server")
            || error.contains("cannot start language server")
        {
            self.language.text(TextKey::SourceLspUnavailable).to_owned()
        } else {
            error
        }
    }

    fn selected_file_record(&self) -> Option<&SourceFile> {
        let location = self.selected_location.as_ref()?;
        self.location_files
            .iter()
            .find(|file| {
                file.snapshot_id == location.snapshot_id
                    && file.relative_path == location.relative_path
            })
            .or_else(|| {
                self.files
                    .iter()
                    .find(|file| file.relative_path == location.relative_path)
            })
    }

    fn selected_workspace_record(&self) -> Option<&WorkspaceRecord> {
        let id = self.selected_workspace?;
        self.workspaces.iter().find(|workspace| workspace.id == id)
    }

    fn verified_selection(&self) -> Option<(&WorkspaceRecord, &SourceSnapshot, &SourceFile)> {
        let location = self.selected_location.as_ref()?;
        let content = self.content.as_ref()?;
        if content.location != *location {
            return None;
        }
        let workspace = self.selected_workspace_record()?;
        let snapshot = self.location_snapshot.as_ref()?;
        if snapshot.id != location.snapshot_id
            || workspace.id != location.workspace_id
            || snapshot.workspace_id != workspace.id
        {
            return None;
        }
        let file = self.selected_file_record()?;
        if file.snapshot_id != location.snapshot_id
            || file.relative_path != location.relative_path
            || file.content_hash != location.content_hash
        {
            return None;
        }
        Some((workspace, snapshot, file))
    }

    pub(crate) fn can_analyze(&self) -> bool {
        self.verified_selection().is_some()
    }

    pub(crate) fn analysis_request(&self) -> Result<AnalysisRequest, String> {
        let (workspace, snapshot, file) = self
            .verified_selection()
            .ok_or_else(|| "Select a verified source file before running AI analysis".to_owned())?;
        let location = self
            .selected_location
            .as_ref()
            .ok_or_else(|| "Select a verified source file before running AI analysis".to_owned())?;
        let content = self
            .content
            .as_ref()
            .ok_or_else(|| "Source content is still loading".to_owned())?;
        ai_context::analysis_request(
            workspace,
            snapshot,
            file,
            location,
            content,
            self.candidate.as_ref(),
            self.permissions.allowed(&workspace.id.to_hex()),
        )
    }
}

fn language_id_for_path(relative_path: &str) -> Option<&'static str> {
    let extension = relative_path.rsplit('.').next()?.to_ascii_lowercase();
    match extension.as_str() {
        "java" => Some("java"),
        "kt" | "kts" => Some("kotlin"),
        "c" => Some("c"),
        "cc" | "cpp" | "cxx" | "h" | "hh" | "hpp" | "hxx" => Some("cpp"),
        "m" => Some("objective-c"),
        "mm" => Some("objective-cpp"),
        _ => None,
    }
}

fn dedupe_locations(mut locations: Vec<lsp::Location>) -> Vec<lsp::Location> {
    locations.sort_by(|left, right| left.path.cmp(&right.path).then(left.line.cmp(&right.line)));
    locations.dedup();
    locations
}

fn navigation_evidence(symbol: &str, resource: &str) -> Vec<SourceResolutionEvidence> {
    let class_name = symbol.rsplit_once('.').map(|(class, _)| class.to_owned());
    let method_name = symbol.rsplit('.').next().unwrap_or(symbol).to_owned();
    let resource_path = (!resource.trim().is_empty()).then(|| resource.trim().to_owned());
    let managed = PerformanceEvidenceId::from_digest(aps_source::digest_text(&format!(
        "managed:{symbol}:{resource}"
    )));
    let native = PerformanceEvidenceId::from_digest(aps_source::digest_text(&format!(
        "native:{symbol}:{resource}"
    )));
    vec![
        SourceResolutionEvidence::ManagedSymbol {
            id: managed,
            class_name,
            method_name: method_name.clone(),
            signature: None,
            resource_path: resource_path.clone(),
        },
        SourceResolutionEvidence::NativeSymbol {
            id: native,
            symbol_name: symbol.to_owned(),
            library_path: None,
            build_id: None,
            source_path: resource_path,
            source_line: None,
        },
    ]
}

fn open_external_path(path: &Path) {
    #[cfg(target_os = "macos")]
    let mut command = std::process::Command::new("open");
    #[cfg(target_os = "windows")]
    let mut command = {
        let mut command = std::process::Command::new("cmd");
        command.arg("/C").arg("start").arg("");
        command
    };
    #[cfg(all(unix, not(target_os = "macos")))]
    let mut command = std::process::Command::new("xdg-open");
    let _ = command.arg(path).spawn();
}

impl Render for SourceWorkspace {
    fn render(&mut self, window: &mut Window, cx: &mut Context<Self>) -> impl IntoElement {
        page::render(self, window, cx)
    }
}

impl NoticeSource for SourceWorkspace {
    fn notice_snapshot(&self) -> Vec<NoticeSnapshot> {
        let mut notices = Vec::new();
        if let Some(error) = &self.page_error {
            notices.push(NoticeSnapshot::error(crate::evidence::clipped(error, 8192)));
        }
        if let Some(error) = &self.list_error {
            notices.push(NoticeSnapshot::error(crate::evidence::clipped(error, 8192)));
        }
        if let Some(error) = &self.permissions_error {
            notices.push(NoticeSnapshot::error(crate::evidence::clipped(error, 8192)));
        }
        notices.retain(|notice| !notice.message.trim().is_empty());
        notices.truncate(8);
        notices
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[::core::prelude::v1::test]
    fn symbol_navigation_evidence_splits_class_method_and_resource() {
        let evidence = navigation_evidence("com.example.Widget.render", "src/Widget.kt");
        assert_eq!(evidence.len(), 2);
        match &evidence[0] {
            SourceResolutionEvidence::ManagedSymbol {
                class_name,
                method_name,
                resource_path,
                ..
            } => {
                assert_eq!(class_name.as_deref(), Some("com.example.Widget"));
                assert_eq!(method_name, "render");
                assert_eq!(resource_path.as_deref(), Some("src/Widget.kt"));
            }
            other => panic!("unexpected evidence: {other:?}"),
        }
        match &evidence[1] {
            SourceResolutionEvidence::NativeSymbol {
                symbol_name,
                source_path,
                source_line,
                ..
            } => {
                assert_eq!(symbol_name, "com.example.Widget.render");
                assert_eq!(source_path.as_deref(), Some("src/Widget.kt"));
                assert!(source_line.is_none());
            }
            other => panic!("unexpected evidence: {other:?}"),
        }
        assert_ne!(evidence[0].id(), evidence[1].id());
        let unqualified = navigation_evidence("render", "");
        match &unqualified[0] {
            SourceResolutionEvidence::ManagedSymbol { class_name, .. } => {
                assert!(class_name.is_none())
            }
            other => panic!("unexpected evidence: {other:?}"),
        }
    }
}

/// In-window menu bar commands for the Source workspace. Each screen publishes
/// only the commands its own functionality supports.
#[derive(Clone, PartialEq, Eq)]
pub(crate) enum SourceMenuCommand {
    AddLocal,
    ReloadWorkspaces,
    SyncSelected,
    RemoveSelected,
    ClearSelection,
    CopyLocation,
    OpenInIde,
}

impl crate::shell::menubar::MenuHost for SourceWorkspace {
    type Command = SourceMenuCommand;

    fn menu_groups(
        &self,
        _language: aps_ui_foundation::Language,
    ) -> Vec<crate::shell::menubar::MenuGroup<Self::Command>> {
        use crate::shell::menubar::MenuGroup;

        let selected = self.selected_workspace_record();
        let syncing = selected
            .is_some_and(|workspace| self.sync_in_flight.contains_key(&workspace.id.to_hex()));
        let local_workspace = selected.is_some_and(|workspace| {
            matches!(&workspace.config, SourceProviderConfig::Local { .. })
        });
        let has_location = self.selected_location.is_some();
        vec![
            MenuGroup::new(TextKey::ShellMenuWorkspace)
                .item_when(
                    !self.add_in_flight,
                    TextKey::SourceAddLocal,
                    SourceMenuCommand::AddLocal,
                )
                .item(
                    TextKey::SourceReloadWorkspaces,
                    SourceMenuCommand::ReloadWorkspaces,
                )
                .separator()
                .item_when(
                    selected.is_some() && !syncing,
                    TextKey::SourceSync,
                    SourceMenuCommand::SyncSelected,
                )
                .item_when(
                    selected.is_some(),
                    TextKey::SourceRemove,
                    SourceMenuCommand::RemoveSelected,
                )
                .separator()
                .item_when(
                    selected.is_some() || self.selected_file.is_some() || has_location,
                    TextKey::SourceClearSelection,
                    SourceMenuCommand::ClearSelection,
                ),
            MenuGroup::new(TextKey::ShellMenuView)
                .item_when(
                    selected.is_some() && has_location,
                    TextKey::SourceCopyLocation,
                    SourceMenuCommand::CopyLocation,
                )
                .item_when(
                    local_workspace && has_location,
                    TextKey::SourceOpenInIde,
                    SourceMenuCommand::OpenInIde,
                ),
        ]
    }

    fn run_menu_command(
        &mut self,
        command: Self::Command,
        _window: &mut gpui_kit::Window,
        cx: &mut gpui_kit::Context<Self>,
    ) {
        match command {
            SourceMenuCommand::AddLocal => self.pick_local_workspace(cx),
            SourceMenuCommand::ReloadWorkspaces => self.reload_workspaces(cx),
            SourceMenuCommand::SyncSelected => {
                if let Some(id) = self.selected_workspace {
                    self.sync_workspace(id, cx);
                }
            }
            SourceMenuCommand::RemoveSelected => {
                if let Some(id) = self.selected_workspace {
                    self.remove_workspace(id, cx);
                }
            }
            SourceMenuCommand::ClearSelection => {
                self.clear_selection();
                cx.notify();
            }
            SourceMenuCommand::CopyLocation => self.copy_location(cx),
            SourceMenuCommand::OpenInIde => self.open_location_in_ide(cx),
        }
    }
}

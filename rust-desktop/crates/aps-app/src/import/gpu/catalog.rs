use super::*;
use aps_gpu::GpuArtifactStore;

#[cfg(test)]
mod tests;

/// One serialized store per GPU workspace; every mutation reloads the committed
/// index so a cancelled UI publication cannot later overwrite a committed edit.
#[derive(Clone)]
pub(super) struct Catalog {
    store: GpuArtifactStore,
    gate: Arc<Mutex<()>>,
}

pub(super) struct CatalogSnapshot {
    pub index: GpuArtifactIndex,
    pub records: Vec<GpuRecord>,
}

pub(super) enum CatalogChange {
    Import(PathBuf),
    Relocate(SourceDigest, PathBuf),
    Remove(SourceDigest),
}

impl Catalog {
    pub fn new(data_directory: &Path) -> Self {
        Self {
            store: GpuArtifactStore::new(data_directory.join("gpu-integration/artifacts.json")),
            gate: Arc::new(Mutex::new(())),
        }
    }

    pub fn load(&self, cancel: &CancellationToken) -> Result<CatalogSnapshot, UiText> {
        let _guard = self
            .gate
            .lock()
            .map_err(|_| TextKey::GpuCatalogLoadFailed)?;
        let index = self
            .store
            .load()
            .map_err(|_| TextKey::GpuCatalogLoadFailed)?;
        snapshot(index, cancel)
    }

    pub fn apply(
        &self,
        change: CatalogChange,
        cancel: &CancellationToken,
    ) -> Result<(CatalogSnapshot, Option<SourceDigest>), UiText> {
        let _guard = self
            .gate
            .lock()
            .map_err(|_| TextKey::GpuCatalogLoadFailed)?;
        check_cancel(cancel)?;
        // A malformed/unreadable catalog is never treated as an empty index.
        let mut index = self
            .store
            .load()
            .map_err(|_| TextKey::GpuCatalogLoadFailed)?;
        let selected = match change {
            CatalogChange::Import(path) => Some(
                index
                    .import(&path, cancel)
                    .map_err(|_| TextKey::GpuImportFailed)?,
            ),
            CatalogChange::Relocate(digest, path) => {
                index
                    .relocate(digest, &path, cancel)
                    .map_err(|_| TextKey::GpuRelocateMismatch)?;
                Some(digest)
            }
            CatalogChange::Remove(digest) => {
                if !index.remove(digest) {
                    return Err(TextKey::GpuCatalogEntryMissing.into());
                }
                index.artifacts().first().map(|artifact| artifact.sha256)
            }
        };
        let snapshot = snapshot(index, cancel)?;
        check_cancel(cancel)?;
        self.store
            .save(&snapshot.index)
            .map_err(|_| TextKey::GpuCatalogSaveFailed)?;
        Ok((snapshot, selected))
    }
}

fn snapshot(
    index: GpuArtifactIndex,
    cancel: &CancellationToken,
) -> Result<CatalogSnapshot, UiText> {
    let mut records = Vec::with_capacity(index.len());
    for artifact in index.artifacts() {
        let location = artifact
            .resolve_location(cancel)
            .map_err(|_| TextKey::GpuVerifyFailed)?;
        records.push(GpuRecord {
            artifact: Arc::new(artifact.clone()),
            status: Some(location.status),
        });
    }
    check_cancel(cancel)?;
    Ok(CatalogSnapshot { index, records })
}

fn check_cancel(cancel: &CancellationToken) -> Result<(), UiText> {
    if cancel.is_cancelled() {
        Err(TextKey::GpuOperationCancelled.into())
    } else {
        Ok(())
    }
}

impl ImportWorkspace {
    pub(super) fn gpu_restore_catalog(&mut self, cx: &mut Context<Self>) {
        if self.busy || self.gpu.catalog_task.is_some() {
            return;
        }
        self.gpu.catalog_started = true;
        if self.gpu.catalog.is_none() {
            self.gpu.catalog = crate::settings_path()
                .ok()
                .and_then(|path| path.parent().map(Catalog::new));
        }
        let Some(catalog) = self.gpu.catalog.clone() else {
            self.gpu.catalog_failed = true;
            self.error = Some(TextKey::GpuNoDataDirectory.into());
            return;
        };
        let (generation, cancel) = self.gpu_begin(cx);
        self.gpu.catalog_task = Some(generation);
        let task = cx
            .background_executor()
            .spawn(async move { catalog.load(&cancel) });
        cx.spawn(async move |this, cx| {
            let outcome = task.await;
            this.update(cx, |this, cx| {
                if !this.gpu_catalog_finished(generation, cx) {
                    return;
                }
                this.busy = false;
                match outcome {
                    Ok(snapshot) => {
                        let selected = snapshot
                            .index
                            .artifacts()
                            .first()
                            .map(|artifact| artifact.sha256.to_hex());
                        this.gpu_accept_catalog(snapshot);
                        if let Some(id) = selected {
                            this.gpu_select(id, cx);
                        }
                        this.notice = Some(TextKey::GpuIndexLoaded.into());
                    }
                    Err(error) => this.gpu_catalog_error(error),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    pub(super) fn gpu_catalog_finished(&mut self, generation: u64, cx: &mut Context<Self>) -> bool {
        if self.gpu.catalog_task != Some(generation) {
            return false;
        }
        self.gpu.catalog_task = None;
        if self.generation == generation {
            return true;
        }
        // Cancellation may race an atomic rename. Reload before another edit,
        // and never publish the stale worker's selection or availability.
        self.gpu.catalog_started = false;
        if !self.busy {
            self.gpu_restore_catalog(cx);
        }
        false
    }

    pub(super) fn gpu_accept_catalog(&mut self, snapshot: CatalogSnapshot) {
        self.gpu.index = snapshot.index;
        self.gpu.artifacts = snapshot.records;
        self.gpu.catalog_failed = false;
        self.gpu.catalog_started = true;
        self.gpu.rail_offset = 0;
        self.gpu.artifact_offset = 0;
        self.gpu
            .import_durations
            .retain(|id, _| self.gpu.artifacts.iter().any(|record| record.id() == *id));
        if self.gpu.artifacts.is_empty() {
            self.gpu.selected = None;
            self.session = None;
            self.session_id = None;
            self.page = None;
            self.path = None;
            self.import_duration = None;
            self.import_started = None;
        }
    }

    pub(super) fn gpu_catalog_error(&mut self, error: UiText) {
        if matches!(error, UiText::Message(TextKey::GpuCatalogLoadFailed, _)) {
            self.gpu.catalog_failed = true;
        }
        self.error = Some(error);
    }

    pub(super) fn gpu_remove(&mut self, cx: &mut Context<Self>) {
        if self.busy || self.gpu.catalog_failed || self.gpu.catalog_task.is_some() {
            return;
        }
        let Some(record) = self.gpu.selected_record() else {
            return;
        };
        let digest = record.artifact.sha256;
        let Some(catalog) = self.gpu.catalog.clone() else {
            return;
        };
        let (generation, cancel) = self.gpu_begin(cx);
        self.gpu.catalog_task = Some(generation);
        let task = cx
            .background_executor()
            .spawn(async move { catalog.apply(CatalogChange::Remove(digest), &cancel) });
        cx.spawn(async move |this, cx| {
            let outcome = task.await;
            this.update(cx, |this, cx| {
                if !this.gpu_catalog_finished(generation, cx) {
                    return;
                }
                this.busy = false;
                match outcome {
                    Ok((snapshot, selected)) => {
                        this.gpu_accept_catalog(snapshot);
                        if let Some(id) = selected {
                            this.gpu_select(id.to_hex(), cx);
                        }
                        this.notice = Some(TextKey::GpuArtifactRemoved.into());
                    }
                    Err(error) => this.gpu_catalog_error(error),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
    }
}

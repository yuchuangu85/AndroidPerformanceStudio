use super::*;
use std::fs;
use std::sync::atomic::{AtomicU64, Ordering};

static NEXT: AtomicU64 = AtomicU64::new(0);
struct Fixture(PathBuf);
impl Fixture {
    fn new() -> Self {
        let root = std::env::temp_dir().join(format!(
            "aps-gpu-catalog-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::create_dir(&root).unwrap();
        Self(root)
    }
    fn file(&self, name: &str, contents: &[u8]) -> PathBuf {
        let path = self.0.join(name);
        fs::write(&path, contents).unwrap();
        path.canonicalize().unwrap()
    }
    fn catalog(&self) -> Catalog {
        Catalog::new(&self.0)
    }
    fn store_path(&self) -> PathBuf {
        self.0.join("gpu-integration/artifacts.json")
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

#[::core::prelude::v1::test]
fn restores_order_identity_and_alternative_locations_without_owning_files() {
    let fixture = Fixture::new();
    let first = fixture.file("first.gfxtrace", b"frame evidence");
    let second = fixture.file("second.pftrace", b"trace evidence");
    let copy = fixture.file("renamed.pdf", b"frame evidence");
    let cancel = CancellationToken::new();
    let catalog = fixture.catalog();
    assert!(catalog.load(&cancel).unwrap().index.is_empty());
    assert!(!fixture.store_path().exists());
    let (_, a) = catalog
        .apply(CatalogChange::Import(first.clone()), &cancel)
        .unwrap();
    let (_, b) = catalog
        .apply(CatalogChange::Import(second), &cancel)
        .unwrap();
    let (snapshot, merged) = catalog
        .apply(CatalogChange::Import(copy.clone()), &cancel)
        .unwrap();
    assert_eq!(merged, a);
    assert_eq!(snapshot.index.len(), 2);
    let restored = fixture.catalog().load(&cancel).unwrap();
    assert_eq!(restored.records[0].artifact.sha256, a.unwrap());
    assert_eq!(restored.records[1].artifact.sha256, b.unwrap());
    assert_eq!(
        restored.records[0].artifact.kind,
        ArtifactKind::AgiFrameProfile
    );
    assert_eq!(restored.records[0].artifact.open_route, OpenRoute::Agi);
    assert_eq!(
        restored.records[0].artifact.locations(),
        &[copy.clone(), first.clone()]
    );
    fs::remove_file(copy).unwrap();
    assert_eq!(
        fixture.catalog().load(&cancel).unwrap().records[0].status,
        Some(LocationStatus::Available)
    );
    fs::write(&first, b"other evidence").unwrap();
    assert_eq!(
        fixture.catalog().load(&cancel).unwrap().records[0].status,
        Some(LocationStatus::ContentChanged)
    );
}

#[::core::prelude::v1::test]
fn relocation_preserves_order_and_remove_persists_without_deleting_sources() {
    let fixture = Fixture::new();
    let first = fixture.file("first.gfxtrace", b"frame");
    let second = fixture.file("second.pftrace", b"trace");
    let copy = fixture.file("relocated.pdf", b"frame");
    let wrong = fixture.file("wrong.gfxtrace", b"wrong");
    let cancel = CancellationToken::new();
    let catalog = fixture.catalog();
    let (_, a) = catalog
        .apply(CatalogChange::Import(first.clone()), &cancel)
        .unwrap();
    let (_, b) = catalog
        .apply(CatalogChange::Import(second.clone()), &cancel)
        .unwrap();
    let a = a.unwrap();
    let b = b.unwrap();
    let committed = fs::read(fixture.store_path()).unwrap();
    assert!(
        catalog
            .apply(CatalogChange::Relocate(a, wrong), &cancel)
            .is_err()
    );
    assert_eq!(fs::read(fixture.store_path()).unwrap(), committed);
    let (snapshot, selected) = catalog
        .apply(CatalogChange::Relocate(a, copy.clone()), &cancel)
        .unwrap();
    assert_eq!(selected, Some(a));
    assert_eq!(snapshot.records[0].artifact.sha256, b);
    assert_eq!(snapshot.records[1].artifact.sha256, a);
    let rebound = fixture.catalog().load(&cancel).unwrap();
    assert_eq!(
        rebound.records[1].artifact.locations(),
        &[copy.clone(), first.clone()]
    );
    assert_eq!(rebound.records[1].artifact.open_route, OpenRoute::Agi);
    let (snapshot, selected) = catalog.apply(CatalogChange::Remove(a), &cancel).unwrap();
    assert_eq!(selected, Some(b));
    assert_eq!(snapshot.index.len(), 1);
    assert!(first.is_file() && copy.is_file());
    assert!(catalog.apply(CatalogChange::Remove(a), &cancel).is_err());
    let (snapshot, selected) = catalog.apply(CatalogChange::Remove(b), &cancel).unwrap();
    assert!(selected.is_none());
    assert!(snapshot.index.is_empty());
    assert!(fixture.catalog().load(&cancel).unwrap().index.is_empty());
    assert!(second.is_file());
}

#[::core::prelude::v1::test]
fn corrupt_catalog_is_never_overwritten_by_import_relocation_or_removal() {
    let fixture = Fixture::new();
    let file = fixture.file("capture.pftrace", b"trace");
    let cancel = CancellationToken::new();
    let catalog = fixture.catalog();
    let (_, digest) = catalog
        .apply(CatalogChange::Import(file.clone()), &cancel)
        .unwrap();
    fs::write(fixture.store_path(), b"{broken catalog").unwrap();
    assert!(catalog.load(&cancel).is_err());
    for change in [
        CatalogChange::Import(file.clone()),
        CatalogChange::Relocate(digest.unwrap(), file),
        CatalogChange::Remove(digest.unwrap()),
    ] {
        let error = catalog.apply(change, &cancel).err().unwrap();
        assert!(matches!(
            error,
            UiText::Message(TextKey::GpuCatalogLoadFailed, _)
        ));
        assert_eq!(fs::read(fixture.store_path()).unwrap(), b"{broken catalog");
    }
}

#[::core::prelude::v1::test]
fn failed_save_and_cancelled_changes_keep_previous_publication_and_store() {
    let fixture = Fixture::new();
    let first = fixture.file("first.pftrace", b"first");
    let second = fixture.file("second.pftrace", b"second");
    let catalog = fixture.catalog();
    let cancel = CancellationToken::new();
    let (published, digest) = catalog
        .apply(CatalogChange::Import(first), &cancel)
        .unwrap();
    let committed = fs::read(fixture.store_path()).unwrap();
    let cancelled = CancellationToken::new();
    cancelled.cancel();
    assert!(
        catalog
            .apply(CatalogChange::Remove(digest.unwrap()), &cancelled)
            .is_err()
    );
    assert_eq!(fs::read(fixture.store_path()).unwrap(), committed);
    // A symlinked parent is rejected by the platform's private-directory writer.
    #[cfg(unix)]
    {
        let parent = fixture.0.join("gpu-integration");
        let backup = fixture.0.join("committed");
        fs::rename(&parent, &backup).unwrap();
        std::os::unix::fs::symlink(&backup, &parent).unwrap();
        let error = catalog
            .apply(CatalogChange::Import(second), &cancel)
            .err()
            .unwrap();
        assert!(matches!(
            error,
            UiText::Message(TextKey::GpuCatalogSaveFailed, _)
        ));
        assert_eq!(fs::read(fixture.store_path()).unwrap(), committed);
    }
    #[cfg(not(unix))]
    {
        let _ = second;
    }
    assert_eq!(published.index.len(), 1);
    assert_eq!(published.records[0].artifact.sha256, digest.unwrap());
}

#[::core::prelude::v1::test]
fn workspace_publication_clears_removed_evidence_and_restores_selected_identity() {
    let fixture = Fixture::new();
    let file = fixture.file("capture.pftrace", b"trace");
    let cancel = CancellationToken::new();
    let catalog = fixture.catalog();
    let (snapshot, digest) = catalog.apply(CatalogChange::Import(file), &cancel).unwrap();
    let mut workspace = ImportWorkspace::new(Destination::Gpu, Language::English);
    workspace.gpu_accept_catalog(snapshot);
    let record = workspace.gpu.artifacts[0].clone();
    workspace.gpu.selected = Some(record.id());
    workspace.gpu_publish_record(&record);
    assert!(workspace.session.is_some());
    assert_eq!(workspace.session_id.as_deref(), Some(record.id().as_str()));
    assert!(workspace.path.is_some());
    assert!(workspace.import_duration.is_none());
    let (snapshot, _) = catalog
        .apply(CatalogChange::Remove(digest.unwrap()), &cancel)
        .unwrap();
    workspace.gpu_accept_catalog(snapshot);
    assert!(workspace.gpu.selected_record().is_none());
    assert!(workspace.session.is_none());
    assert!(workspace.session_id.is_none());
    assert!(workspace.page.is_none());
    assert!(workspace.path.is_none());
}

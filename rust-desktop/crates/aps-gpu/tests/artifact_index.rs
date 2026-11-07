use aps_gpu::{ArtifactKind, GpuArtifact, GpuArtifactIndex, GpuError, LocationStatus, OpenRoute};
use aps_platform::CancellationToken;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
static NEXT: AtomicU64 = AtomicU64::new(0);
struct File(PathBuf);
impl File {
    fn new(extension: &str, bytes: &[u8]) -> Self {
        let path = std::env::temp_dir().join(format!(
            "aps-gpu-artifact-{}-{}.{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed),
            extension
        ));
        OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(&path)
            .unwrap()
            .write_all(bytes)
            .unwrap();
        Self(path)
    }
}
impl Drop for File {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.0);
    }
}

#[test]
fn duplicate_locations_survive_and_primary_absence_does_not_hide_a_copy() {
    let first = File::new("gfxtrace", b"same");
    let second = File::new("gfxtrace", b"same");
    let cancel = CancellationToken::new();
    let mut index = GpuArtifactIndex::new();
    let digest = index.import(&first.0, &cancel).unwrap();
    assert_eq!(index.import(&second.0, &cancel).unwrap(), digest);
    assert_eq!(index.len(), 1);
    let artifact = index.get(digest).unwrap();
    assert_eq!(artifact.locations().len(), 2);
    assert_eq!(artifact.kind, ArtifactKind::AgiFrameProfile);
    assert_eq!(artifact.open_route, OpenRoute::Agi);
    fs::remove_file(&second.0).unwrap();
    let resolved = artifact.resolve_location(&cancel).unwrap();
    assert_eq!(resolved.status, LocationStatus::Available);
    assert_eq!(resolved.path, Some(first.0.canonicalize().unwrap()));
}

#[test]
fn same_size_content_change_and_relocation_do_not_replace_identity() {
    let file = File::new("agi", b"same");
    let replacement = File::new("agi", b"same");
    let wrong = File::new("agi", b"else");
    let cancel = CancellationToken::new();
    let mut artifact = GpuArtifact::import(&file.0, &cancel).unwrap();
    let original = artifact.sha256;
    fs::write(&file.0, b"else").unwrap();
    assert_eq!(
        artifact.resolve_location(&cancel).unwrap().status,
        LocationStatus::ContentChanged
    );
    assert_eq!(
        artifact.relocate(&wrong.0, &cancel),
        Err(GpuError::DigestMismatch)
    );
    artifact.relocate(&replacement.0, &cancel).unwrap();
    assert_eq!(artifact.sha256, original);
    assert_eq!(artifact.locations().len(), 2);
    assert_eq!(
        artifact.resolve_location(&cancel).unwrap().status,
        LocationStatus::Available
    );
}

#[test]
fn unknown_evidence_is_not_claimed_as_agi_and_pages_are_bounded() {
    let file = File::new("opaque", b"unknown");
    let cancel = CancellationToken::new();
    let mut index = GpuArtifactIndex::new();
    let digest = index.import(&file.0, &cancel).unwrap();
    assert_eq!(index.get(digest).unwrap().open_route, OpenRoute::None);
    assert_eq!(index.page(0, 1).unwrap().len(), 1);
    assert!(index.page(9, 1).unwrap().is_empty());
    assert!(matches!(index.page(0, 201), Err(GpuError::InvalidPage)));
    cancel.cancel();
    assert!(matches!(
        GpuArtifact::import(&file.0, &cancel),
        Err(GpuError::Cancelled)
    ));
}

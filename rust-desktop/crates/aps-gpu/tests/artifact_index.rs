use aps_gpu::{
    ArtifactKind, GpuArtifact, GpuArtifactIndex, GpuCaptureContext, GpuError, GraphicsApi,
    LocationStatus, OpenRoute, WARNING_UNKNOWN_ARTIFACT,
};
use aps_platform::CancellationToken;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{Duration, UNIX_EPOCH};
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
    assert_eq!(
        index.get(digest).unwrap().warnings,
        vec![WARNING_UNKNOWN_ARTIFACT.to_owned()]
    );
    assert_eq!(index.page(0, 1).unwrap().len(), 1);
    assert!(index.page(9, 1).unwrap().is_empty());
    assert!(matches!(index.page(0, 201), Err(GpuError::InvalidPage)));
    cancel.cancel();
    assert!(matches!(
        GpuArtifact::import(&file.0, &cancel),
        Err(GpuError::Cancelled)
    ));
}

#[test]
fn capture_identity_is_recorded_and_related_only_by_device_or_package() {
    let cancel = CancellationToken::new();
    let device = aps_gpu::parse_device_context(
        "serial-1",
        "[ro.product.model]: [Pixel 9]\n[ro.opengles.version]: [196610]\n",
        "",
    );
    let context = GpuCaptureContext {
        device: Some(device),
        package_name: Some("com.example.app".into()),
        graphics_api: Some(GraphicsApi::OpenGlEs),
        graphics_implementation: Some("EGL: angle".into()),
        captured_at: Some(UNIX_EPOCH + Duration::from_secs(1_700_000_000)),
        warnings: Vec::new(),
    };
    let first = File::new("frame.gfxtrace", b"first capture");
    let second = File::new("frame.pftrace", b"second capture");
    let unrelated = File::new("other.pftrace", b"third capture");
    let mut index = GpuArtifactIndex::new();
    let a = index
        .import_capture(
            &first.0,
            Some(&context),
            Some("3.3.3"),
            Some("baseline"),
            &cancel,
        )
        .unwrap();
    let b = index
        .import_capture(&second.0, Some(&context), None, None, &cancel)
        .unwrap();
    let c = index.import(&unrelated.0, &cancel).unwrap();
    let artifact = index.get(a).unwrap();
    assert_eq!(artifact.agi_version.as_deref(), Some("3.3.3"));
    assert_eq!(artifact.package_name.as_deref(), Some("com.example.app"));
    assert_eq!(artifact.graphics_api, Some(GraphicsApi::OpenGlEs));
    assert_eq!(
        artifact.graphics_implementation.as_deref(),
        Some("EGL: angle")
    );
    assert_eq!(
        artifact.captured_at,
        Some(UNIX_EPOCH + Duration::from_secs(1_700_000_000))
    );
    assert_eq!(
        artifact
            .device
            .as_ref()
            .map(|device| device.serial.as_str()),
        Some("serial-1")
    );
    assert_eq!(artifact.notes.as_deref(), Some("baseline"));
    assert!(artifact.imported_at.is_some());
    let related: Vec<_> = index
        .related(a)
        .iter()
        .map(|artifact| artifact.sha256)
        .collect();
    assert_eq!(related, vec![b]);
    assert!(index.related(c).is_empty());
    assert!(index.related(a).len() == 1);
}

#[test]
fn duplicate_content_keeps_recorded_identity_and_gains_a_verified_location() {
    let cancel = CancellationToken::new();
    let first = File::new("frame.gfxtrace", b"same bytes");
    let duplicate = File::new("renamed.gfxtrace", b"same bytes");
    let mut index = GpuArtifactIndex::new();
    let digest = index
        .import_capture(&first.0, None, Some("3.3.3"), None, &cancel)
        .unwrap();
    assert_eq!(
        index
            .import_capture(&duplicate.0, None, Some("9.9.9"), None, &cancel)
            .unwrap(),
        digest
    );
    let artifact = index.get(digest).unwrap();
    assert_eq!(artifact.agi_version.as_deref(), Some("3.3.3"));
    assert_eq!(artifact.locations().len(), 2);
    assert_eq!(artifact.locations()[0], duplicate.0.canonicalize().unwrap());
}

#[test]
fn remove_is_catalog_only_and_relocate_is_digest_bound() {
    let first = File::new("gfxtrace", b"same");
    let replacement = File::new("renamed.pdf", b"same");
    let wrong = File::new("wrong.gfxtrace", b"else");
    let cancel = CancellationToken::new();
    let mut index = GpuArtifactIndex::new();
    let digest = index.import(&first.0, &cancel).unwrap();
    assert_eq!(
        index.relocate(digest, &wrong.0, &cancel),
        Err(GpuError::DigestMismatch)
    );
    index.relocate(digest, &replacement.0, &cancel).unwrap();
    assert_eq!(
        index.get(digest).unwrap().locations(),
        &[
            replacement.0.canonicalize().unwrap(),
            first.0.canonicalize().unwrap()
        ]
    );
    assert!(index.remove(digest));
    assert!(index.is_empty());
    assert!(first.0.is_file() && replacement.0.is_file() && wrong.0.is_file());
    assert!(!index.remove(digest));
    assert_eq!(
        index.relocate(digest, &replacement.0, &cancel),
        Err(GpuError::NotIndexed)
    );
}

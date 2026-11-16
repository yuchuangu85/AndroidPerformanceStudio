use aps_gpu::{AgiLaunchMode, AgiToolchain, GpuArtifactStore, GpuCaptureContext, GraphicsApi};
use aps_platform::CancellationToken;
use std::path::PathBuf;
use std::time::{Duration, UNIX_EPOCH};

#[test]
fn empty_store_roundtrips_without_creating_external_artifacts() {
    let root = std::env::temp_dir().join(format!("aps-gpu-empty-store-{}", std::process::id()));
    let path = root.join("artifacts.json");
    std::fs::create_dir_all(&root).unwrap();
    let store = GpuArtifactStore::new(&path);
    let index = store.load().unwrap();
    assert!(index.is_empty());
    store.save(&index).unwrap();
    assert_eq!(store.load().unwrap().len(), 0);
    std::fs::remove_dir_all(root).unwrap();
}

#[test]
fn store_rejects_relative_or_duplicate_locations_and_roundtrips_catalog_changes() {
    let root = std::env::temp_dir().join(format!("aps-gpu-store-{}", std::process::id()));
    let path = root.join("artifacts.json");
    std::fs::create_dir_all(&root).unwrap();
    let source = root.join("capture.pftrace");
    std::fs::write(&source, b"trace").unwrap();
    let cancel = CancellationToken::new();
    let mut index = aps_gpu::GpuArtifactIndex::new();
    let digest = index.import(&source, &cancel).unwrap();
    let store = GpuArtifactStore::new(&path);
    store.save(&index).unwrap();
    assert_eq!(
        store.load().unwrap().get(digest).unwrap().locations(),
        &[source.canonicalize().unwrap()]
    );
    let original = std::fs::read(&path).unwrap();
    let mut document = aps_json::parse_slice(&original, aps_json::Limits::default()).unwrap();
    document["artifacts"][0]["locations"] = aps_json::Value::Array(vec!["relative.pftrace".into()]);
    std::fs::write(
        &path,
        aps_json::to_vec(&document, aps_json::Limits::default()).unwrap(),
    )
    .unwrap();
    assert!(store.load().is_err());
    let absolute = source.canonicalize().unwrap().to_str().unwrap().to_owned();
    document["artifacts"][0]["locations"] =
        aps_json::Value::Array(vec![absolute.clone().into(), absolute.clone().into()]);
    std::fs::write(
        &path,
        aps_json::to_vec(&document, aps_json::Limits::default()).unwrap(),
    )
    .unwrap();
    assert!(store.load().is_err());
    document["artifacts"][0]["locations"] = aps_json::Value::Array(vec![absolute.into()]);
    let duplicate = document["artifacts"][0].clone();
    document["artifacts"]
        .as_array_mut()
        .unwrap()
        .push(duplicate);
    std::fs::write(
        &path,
        aps_json::to_vec(&document, aps_json::Limits::default()).unwrap(),
    )
    .unwrap();
    assert!(store.load().is_err());
    let file = std::fs::File::create(&path).unwrap();
    file.set_len(16 * 1024 * 1024 + 1).unwrap();
    assert!(store.load().is_err());
    assert!(index.remove(digest));
    store.save(&index).unwrap();
    assert!(store.load().unwrap().is_empty());
    assert!(source.is_file());
    std::fs::remove_dir_all(root).unwrap();
}

#[test]
fn store_roundtrips_capture_metadata_without_inventing_capture_time() {
    let root = std::env::temp_dir().join(format!("aps-gpu-metadata-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&root);
    std::fs::create_dir_all(&root).unwrap();
    let path = root.join("artifacts.json");
    let source = root.join("frame.gfxtrace");
    std::fs::write(&source, b"opaque capture").unwrap();
    let capture = UNIX_EPOCH + Duration::from_secs(1_700_000_000);
    let context = GpuCaptureContext {
        device: Some(aps_gpu::parse_device_context(
            "serial-1",
            "[ro.product.model]: [Pixel 9]\n[ro.hardware.egl]: [angle]\n[ro.opengles.version]: [196610]\n",
            "",
        )),
        package_name: Some("com.example.app".into()),
        graphics_api: Some(GraphicsApi::OpenGlEs),
        graphics_implementation: Some("EGL: angle".into()),
        captured_at: Some(capture),
        warnings: Vec::new(),
    };
    let cancel = CancellationToken::new();
    let mut index = aps_gpu::GpuArtifactIndex::new();
    let digest = index
        .import_capture(
            &source,
            Some(&context),
            Some("3.3.3"),
            Some("baseline"),
            &cancel,
        )
        .unwrap();
    let store = GpuArtifactStore::new(&path);
    store.save(&index).unwrap();
    let restored = store.load().unwrap();
    let artifact = restored.get(digest).unwrap();
    assert_eq!(artifact.agi_version.as_deref(), Some("3.3.3"));
    assert_eq!(artifact.package_name.as_deref(), Some("com.example.app"));
    assert_eq!(artifact.graphics_api, Some(GraphicsApi::OpenGlEs));
    assert_eq!(
        artifact.graphics_implementation.as_deref(),
        Some("EGL: angle")
    );
    assert_eq!(artifact.captured_at, Some(capture));
    assert!(artifact.imported_at.is_some());
    assert_eq!(artifact.notes.as_deref(), Some("baseline"));
    let device = artifact.device.as_ref().unwrap();
    assert_eq!(device.serial, "serial-1");
    assert_eq!(device.model.as_deref(), Some("Pixel 9"));
    assert_eq!(device.graphics_apis, vec![GraphicsApi::OpenGlEs]);
    assert!(
        device
            .evidence
            .iter()
            .any(|value| value.contains("ro.opengles.version"))
    );
    assert_eq!(artifact.locations(), &[source.canonicalize().unwrap()]);
    std::fs::remove_dir_all(root).unwrap();
}

#[test]
fn file_import_without_a_capture_context_records_no_capture_time() {
    let root = std::env::temp_dir().join(format!("aps-gpu-no-capture-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&root);
    std::fs::create_dir_all(&root).unwrap();
    let source = root.join("frame.gfxtrace");
    std::fs::write(&source, b"opaque capture").unwrap();
    let cancel = CancellationToken::new();
    let mut index = aps_gpu::GpuArtifactIndex::new();
    let digest = index
        .import_capture(&source, None, Some("3.3.3"), None, &cancel)
        .unwrap();
    let store = GpuArtifactStore::new(root.join("artifacts.json"));
    store.save(&index).unwrap();
    let restored = store.load().unwrap();
    let artifact = restored.get(digest).unwrap();
    assert!(artifact.captured_at.is_none());
    assert!(artifact.modified_at.is_some());
    assert!(artifact.device.is_none() && artifact.package_name.is_none());
    assert_eq!(artifact.agi_version.as_deref(), Some("3.3.3"));
    std::fs::remove_dir_all(root).unwrap();
}

#[test]
fn legacy_version_one_store_still_loads_without_capture_metadata() {
    let root = std::env::temp_dir().join(format!("aps-gpu-legacy-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&root);
    std::fs::create_dir_all(&root).unwrap();
    let path = root.join("artifacts.json");
    let source = root.join("legacy.pftrace");
    std::fs::write(&source, b"trace").unwrap();
    let digest = "ab".repeat(32);
    let document = format!(
        "{{\"version\":1,\"artifacts\":[{{\"kind\":\"perfettoTrace\",\"sha256\":\"{digest}\",\"sizeBytes\":5,\"modifiedAtMs\":0,\"openRoute\":\"perfetto\",\"locations\":[\"{}\"]}}]}}",
        source.canonicalize().unwrap().display()
    );
    std::fs::write(&path, document).unwrap();
    let store = GpuArtifactStore::new(&path);
    let index = store.load().unwrap();
    assert_eq!(index.len(), 1);
    let artifact = &index.artifacts()[0];
    assert_eq!(artifact.open_route, aps_gpu::OpenRoute::Perfetto);
    assert!(artifact.captured_at.is_none());
    assert!(artifact.device.is_none());
    assert!(artifact.imported_at.is_none());
    assert!(artifact.warnings.is_empty());
    std::fs::remove_dir_all(root).unwrap();
}

#[test]
fn capability_probe_rejects_missing_executable() {
    let result = AgiToolchain::probe(
        &PathBuf::from("/definitely/missing/agi"),
        &CancellationToken::new(),
    );
    assert!(result.is_err());
}

#[test]
fn launch_modes_are_explicit_and_non_authorizing_by_default() {
    assert_ne!(AgiLaunchMode::Unsupported, AgiLaunchMode::VerifiedCli);
}

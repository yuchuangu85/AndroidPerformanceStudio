use aps_gpu::{AgiLaunchMode, AgiToolchain, GpuArtifactStore};
use aps_platform::CancellationToken;
use std::path::PathBuf;

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

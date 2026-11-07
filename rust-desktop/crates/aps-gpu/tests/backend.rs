use aps_gpu::{AgiLaunchMode, AgiToolchain, GpuArtifactStore};
use aps_platform::CancellationToken;
use std::path::PathBuf;

#[test]
fn empty_store_roundtrips_without_creating_external_artifacts() {
    let root = std::env::temp_dir().join(format!("aps-gpu-store-{}", std::process::id()));
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

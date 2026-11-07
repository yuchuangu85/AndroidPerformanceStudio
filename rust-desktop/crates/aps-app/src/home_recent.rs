//! Passive, bounded Home history. No profile parsing, source discovery, data
//! migration or persistence occurs here. Activation delegates to the CPU page's
//! existing explicit reopen path, including full content-digest revalidation.
use crate::cpu::{
    CPU_MEDIA_TYPE, CPU_SESSION_PREFIX, cpu_artifact, cpu_snapshot_path, validate_cpu_session,
};
use aps_contracts::ArtifactId;
use aps_storage::{PageRequest, QueryBinding, Storage};
use std::path::Path;

pub const MAX_HOME_RECENT_ITEMS: usize = 6;
const SCAN_LIMIT: usize = 200;
const SCAN_PAGE_SIZE: usize = 20;

#[derive(Debug, Clone)]
pub struct HomeRecentCpu {
    pub binding: QueryBinding,
    pub artifact_id: ArtifactId,
    pub byte_length: u64,
}

#[derive(Debug, Clone)]
pub struct HomeRecentItem {
    pub title: String,
    pub package_name: Option<String>,
    pub cpu: HomeRecentCpu,
}

/// Inspect at most 200 recent session rows to publish at most six entries.
/// Missing/invalid snapshot files are omitted. A malformed index is an error,
/// never interpreted as empty history or repaired by this passive reader.
pub fn load_recent(
    metadata_path: &Path,
    snapshot_root: &Path,
    limit: usize,
) -> Result<Vec<HomeRecentItem>, String> {
    let limit = limit.min(MAX_HOME_RECENT_ITEMS);
    if limit == 0 {
        return Ok(Vec::new());
    }
    // A missing Rust-owned database is the ordinary first-launch empty state.
    match std::fs::symlink_metadata(metadata_path) {
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
        Err(_) => return Err("cannot inspect Home recent metadata database".into()),
        Ok(_) => {}
    }
    let storage = Storage::open_read_only(metadata_path)?;
    let mut items = Vec::with_capacity(limit);
    let mut offset = 0;
    while offset < SCAN_LIMIT && items.len() < limit {
        let rows = storage.list_recent_sessions_read_only(PageRequest {
            offset,
            limit: SCAN_PAGE_SIZE.min(SCAN_LIMIT - offset),
        })?;
        let scanned = rows.len();
        if scanned == 0 {
            break;
        }
        for summary in rows {
            if !summary.id.as_str().starts_with(CPU_SESSION_PREFIX) {
                continue;
            }
            if summary.artifact_count != 1 {
                return Err("Home recent CPU session has invalid artifact references".into());
            }
            let session = storage
                .session(&summary.id)?
                .ok_or("Home recent CPU session disappeared")?;
            if session.generation != summary.generation || session.artifact_ids.len() != 1 {
                return Err("Home recent CPU session changed during discovery".into());
            }
            let artifact_id = session.artifact_ids[0].clone();
            let artifact = storage
                .artifact(&artifact_id)?
                .ok_or("Home recent CPU artifact is missing from index")?;
            let expected = cpu_artifact(artifact.source_digest, artifact.byte_length)?;
            if artifact.media_type != CPU_MEDIA_TYPE || artifact_id != expected.id {
                return Err("Home recent CPU artifact identity or media type is invalid".into());
            }
            validate_cpu_session(&session, artifact.source_digest, &artifact_id)
                .map_err(|error| error.display(aps_ui_foundation::Language::English))?;
            let binding = QueryBinding {
                session_id: session.id,
                generation: session.generation,
                source_digest: artifact.source_digest,
            };
            storage.validate_binding(&binding)?;
            let snapshot = cpu_snapshot_path(snapshot_root, binding.source_digest)
                .map_err(|error| error.display(aps_ui_foundation::Language::English))?;
            let metadata = match std::fs::symlink_metadata(&snapshot) {
                Ok(metadata) => metadata,
                // Missing or inaccessible snapshots are not usable recent items.
                Err(_) => continue,
            };
            // No hash or parser here: passive Home loading never reads the full
            // profile. Explicit open repeats the authoritative digest checks.
            if metadata.file_type().is_symlink()
                || !metadata.is_file()
                || metadata.len() != artifact.byte_length
                || metadata.len() > aps_cpu::DEFAULT_INPUT_LIMIT as u64
            {
                continue;
            }
            items.push(HomeRecentItem {
                title: session.label,
                // CPU import metadata has no verified package/capture time.
                // Import/index timestamps must not impersonate capture time.
                package_name: None,
                cpu: HomeRecentCpu {
                    binding,
                    artifact_id,
                    byte_length: artifact.byte_length,
                },
            });
            if items.len() == limit {
                break;
            }
        }
        offset += scanned;
        if scanned < SCAN_PAGE_SIZE {
            break;
        }
    }
    storage.validate_read_only_snapshot()?;
    Ok(items)
}

#[cfg(test)]
mod tests {
    use super::load_recent;
    use aps_contracts::{ProfilerSession, ProfilerSessionId, SourceDigest};
    use aps_storage::{DATABASE_FILE_NAME, Storage};
    use std::{
        path::PathBuf,
        sync::atomic::{AtomicU64, Ordering},
    };
    static NEXT: AtomicU64 = AtomicU64::new(0);
    struct Fixture {
        root: PathBuf,
    }
    impl Fixture {
        fn new() -> Self {
            let root = std::env::temp_dir().join(format!(
                "aps-home-recent-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            std::fs::create_dir(&root).unwrap();
            Self { root }
        }
        fn database(&self) -> PathBuf {
            self.root.join(DATABASE_FILE_NAME)
        }
        fn snapshots(&self) -> PathBuf {
            self.root.join("cpu-artifacts")
        }
        fn populate(&self, label_override: Option<&str>, snapshot: bool) {
            let digest = SourceDigest::from_bytes([7; 32]);
            let artifact = crate::cpu::cpu_artifact(digest, 4).unwrap();
            let mut storage = Storage::open(self.database()).unwrap();
            storage.put_artifact(&artifact, 123).unwrap();
            let mut session = ProfilerSession::new(
                ProfilerSessionId::new(format!("cpu-import-v1:{}", digest.to_hex())).unwrap(),
            );
            session.artifacts.push(artifact.id);
            storage
                .create_session(
                    &session,
                    label_override.unwrap_or(&format!("CPU import · {}", &digest.to_hex()[..16])),
                    123,
                )
                .unwrap();
            drop(storage);
            if snapshot {
                std::fs::create_dir(self.snapshots()).unwrap();
                // Not a protobuf and intentionally not its digest: passive Home
                // discovery is metadata/stat-only; explicit open owns hashing.
                std::fs::write(
                    self.snapshots()
                        .join(format!("{}.simpleperf.pb", digest.to_hex())),
                    b"stat",
                )
                .unwrap();
            }
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.root);
        }
    }
    #[::core::prelude::v1::test]
    fn first_launch_does_not_create_metadata_or_snapshots() {
        let fixture = Fixture::new();
        assert!(
            load_recent(&fixture.database(), &fixture.snapshots(), 6)
                .unwrap()
                .is_empty()
        );
        assert!(std::fs::read_dir(&fixture.root).unwrap().next().is_none());
    }
    #[::core::prelude::v1::test]
    fn discovery_is_metadata_only_and_does_not_invent_package_or_capture_time() {
        let fixture = Fixture::new();
        fixture.populate(None, true);
        let before = std::fs::read(fixture.database()).unwrap();
        let items = load_recent(&fixture.database(), &fixture.snapshots(), 6).unwrap();
        assert_eq!(items.len(), 1);
        assert_eq!(items[0].title, "CPU import · 0707070707070707");
        assert_eq!(items[0].package_name, None);
        assert_eq!(std::fs::read(fixture.database()).unwrap(), before);
    }
    #[::core::prelude::v1::test]
    fn missing_saved_profile_is_filtered_but_index_corruption_is_an_error() {
        let missing = Fixture::new();
        missing.populate(None, false);
        assert!(
            load_recent(&missing.database(), &missing.snapshots(), 6)
                .unwrap()
                .is_empty()
        );
        let malformed = Fixture::new();
        malformed.populate(Some("not the canonical CPU label"), true);
        assert!(load_recent(&malformed.database(), &malformed.snapshots(), 6).is_err());
    }
}

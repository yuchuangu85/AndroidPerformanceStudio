use aps_contracts::*;
use aps_storage::*;
use rusqlite::Connection;
use std::path::PathBuf;
use std::sync::atomic::{AtomicUsize, Ordering};
static NEXT: AtomicUsize = AtomicUsize::new(0);
struct Fixture(PathBuf);
impl Fixture {
    fn new() -> Self {
        let path = std::env::temp_dir().join(format!(
            "aps-sqlite-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::create_dir(&path).unwrap();
        Self(path)
    }
    fn path(&self) -> PathBuf {
        self.0.join(DATABASE_FILE_NAME)
    }
    fn storage(&self) -> Storage {
        Storage::open(self.path()).unwrap()
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.0);
    }
}
fn artifact(id: &str) -> CaptureArtifact {
    let digest = SourceDigest::from_bytes([42; 32]);
    CaptureArtifact::new(CaptureArtifact {
        id: ArtifactId::new(id).unwrap(),
        source_digest: digest,
        byte_length: 12,
        media_type: "application/x-simpleperf".into(),
        provenance: Provenance::new(
            "Android Simpleperf",
            vec![
                ProvenanceStep::new(
                    ProvenanceAction::Import,
                    "APS local import",
                    Some(digest),
                    digest,
                )
                .unwrap(),
            ],
        )
        .unwrap(),
        capabilities: Capabilities::default(),
        completeness: ArtifactCompleteness::unknown("Original capture request unavailable")
            .unwrap(),
        clock: ClockDomain::new(
            ClockDomainId::new("trace-a").unwrap(),
            ClockKind::Trace,
            "immutable trace identity",
        )
        .unwrap(),
        device: None,
        process: None,
    })
    .unwrap()
}
#[test]
fn artifact_digest_and_provenance_are_immutable_and_survive_reopen() {
    let fixture = Fixture::new();
    let mut storage = fixture.storage();
    assert_eq!(storage.schema_version().unwrap(), 2);
    let value = artifact("artifact:1");
    storage.put_artifact(&value, 10).unwrap();
    storage.put_artifact(&value, 20).unwrap();
    let mut changed = value.clone();
    changed.provenance.producer = "Different producer".into();
    assert!(storage.put_artifact(&changed, 30).is_err());
    drop(storage);
    let storage = fixture.storage();
    let stored = storage.artifact(&value.id).unwrap().unwrap();
    assert_eq!(stored.source_digest, value.source_digest);
    assert_eq!(stored.created_unix_ms, 10);
    assert_eq!(
        stored.metadata["provenance"]["producer"],
        "Android Simpleperf"
    );
    assert_eq!(
        storage
            .list_artifacts(PageRequest::default())
            .unwrap()
            .total,
        1
    );
}
#[test]
fn session_creation_and_artifact_references_commit_atomically() {
    let fixture = Fixture::new();
    let mut storage = fixture.storage();
    let value = artifact("artifact:1");
    storage.put_artifact(&value, 1).unwrap();
    let mut session = ProfilerSession::new(ProfilerSessionId::new("session:1").unwrap());
    session.artifacts = vec![value.id.clone(), ArtifactId::new("missing").unwrap()];
    assert!(storage.create_session(&session, "Incomplete", 2).is_err());
    assert!(storage.session(&session.id).unwrap().is_none());
    session.artifacts.pop();
    storage.create_session(&session, "Complete", 3).unwrap();
    assert_eq!(
        storage.session(&session.id).unwrap().unwrap().artifact_ids,
        [value.id]
    );
}
#[test]
fn recent_sessions_and_generation_cas_reject_stale_results_and_delete_only_metadata() {
    let fixture = Fixture::new();
    let original = fixture.0.join("original.protobuf");
    std::fs::write(&original, b"original bytes").unwrap();
    let mut storage = fixture.storage();
    let value = artifact("artifact:1");
    storage.put_artifact(&value, 1).unwrap();
    let mut first = ProfilerSession::new(ProfilerSessionId::new("session:first").unwrap());
    first.artifacts.push(value.id.clone());
    storage.create_session(&first, "First", 10).unwrap();
    let second = ProfilerSession::new(ProfilerSessionId::new("session:second").unwrap());
    storage.create_session(&second, "Second", 20).unwrap();
    storage.open_session(&first.id, 0, 30).unwrap();
    assert_eq!(
        storage.list_sessions(PageRequest::default()).unwrap().rows[0].id,
        first.id
    );
    let mut binding = QueryBinding {
        session_id: first.id.clone(),
        generation: 0,
        source_digest: value.source_digest,
    };
    storage.validate_binding(&binding).unwrap();
    storage.advance_generation(&first.id, 0, 1).unwrap();
    assert!(storage.validate_binding(&binding).is_err());
    assert!(storage.advance_generation(&first.id, 0, 2).is_err());
    binding.generation = 1;
    storage.validate_binding(&binding).unwrap();
    assert!(storage.delete_session_metadata(&first.id, 0).is_err());
    storage.delete_session_metadata(&first.id, 1).unwrap();
    assert!(storage.session(&first.id).unwrap().is_none());
    assert!(storage.artifact(&value.id).unwrap().is_some());
    assert!(original.is_file());
}
#[test]
fn foreign_and_newer_databases_are_rejected_without_dropping_existing_data() {
    let fixture = Fixture::new();
    {
        let connection = Connection::open(fixture.path()).unwrap();
        connection
            .execute_batch(
                "CREATE TABLE unrelated(value TEXT);INSERT INTO unrelated VALUES('keep');",
            )
            .unwrap();
    }
    assert!(Storage::open(fixture.path()).is_err());
    let connection = Connection::open(fixture.path()).unwrap();
    assert_eq!(
        connection
            .query_row("SELECT value FROM unrelated", [], |row| row
                .get::<_, String>(0))
            .unwrap(),
        "keep"
    );
    drop(connection);
    let newer = Fixture::new();
    drop(newer.storage());
    {
        let connection = Connection::open(newer.path()).unwrap();
        connection
            .execute_batch("PRAGMA user_version=999;")
            .unwrap();
    }
    assert!(Storage::open(newer.path()).is_err());
    let connection = Connection::open(newer.path()).unwrap();
    assert_eq!(
        connection
            .query_row("PRAGMA user_version", [], |row| row.get::<_, i64>(0))
            .unwrap(),
        999
    );
}
#[test]
fn failed_v2_migration_rolls_back_schema_and_preserves_artifacts() {
    let fixture = Fixture::new();
    {
        let mut storage = fixture.storage();
        storage.put_artifact(&artifact("keep"), 1).unwrap();
    }
    {
        let connection = Connection::open(fixture.path()).unwrap();
        connection.execute_batch("DROP TABLE source_snapshots;DROP TABLE source_workspaces;DROP INDEX sessions_recent;CREATE TABLE source_snapshots(conflicting TEXT);PRAGMA user_version=1;").unwrap();
    }
    assert!(Storage::open(fixture.path()).is_err());
    let connection = Connection::open(fixture.path()).unwrap();
    assert_eq!(
        connection
            .query_row("PRAGMA user_version", [], |row| row.get::<_, i64>(0))
            .unwrap(),
        1
    );
    assert_eq!(
        connection
            .query_row(
                "SELECT count(*) FROM artifacts WHERE id='keep'",
                [],
                |row| row.get::<_, i64>(0)
            )
            .unwrap(),
        1
    );
    assert_eq!(
        connection
            .query_row(
                "SELECT count(*) FROM sqlite_master WHERE name='source_workspaces'",
                [],
                |row| row.get::<_, i64>(0)
            )
            .unwrap(),
        0
    );
}
#[test]
fn source_workspace_and_snapshot_metadata_are_foreign_key_bound_and_immutable() {
    let fixture = Fixture::new();
    let mut storage = fixture.storage();
    let workspace = SourceWorkspaceMetadata {
        workspace_id: "ab".repeat(32),
        display_name: "Selected project".into(),
        local_root: fixture.0.to_str().unwrap().into(),
        created_unix_ms: 1,
    };
    let snapshot = SourceSnapshotMetadata {
        snapshot_id: "cd".repeat(32),
        workspace_id: workspace.workspace_id.clone(),
        manifest_digest: SourceDigest::from_bytes([5; 32]),
        file_count: 5,
        indexed_bytes: 100,
        index_version: 1,
        manifest_complete: false,
        created_unix_ms: 2,
    };
    assert!(storage.put_source_snapshot(&snapshot).is_err());
    storage.put_source_workspace(&workspace).unwrap();
    storage.put_source_snapshot(&snapshot).unwrap();
    // Reopening an unchanged root/snapshot at a later observation time is
    // idempotent; the first registration timestamp remains authoritative.
    let mut later_workspace = workspace.clone();
    later_workspace.created_unix_ms = 10;
    storage.put_source_workspace(&later_workspace).unwrap();
    let mut later_snapshot = snapshot.clone();
    later_snapshot.created_unix_ms = 11;
    storage.put_source_snapshot(&later_snapshot).unwrap();
    let mut changed = snapshot.clone();
    changed.file_count = 6;
    assert!(storage.put_source_snapshot(&changed).is_err());
    let mut renamed = workspace.clone();
    renamed.display_name = "Different registration".into();
    assert!(storage.put_source_workspace(&renamed).is_err());
    assert_eq!(
        storage
            .list_source_workspaces(PageRequest::default())
            .unwrap()
            .rows,
        [workspace.clone()]
    );
    assert_eq!(
        storage
            .list_source_snapshots(&workspace.workspace_id, PageRequest::default())
            .unwrap()
            .rows,
        [snapshot]
    );
    assert!(
        storage
            .list_sessions(PageRequest {
                offset: 0,
                limit: 101
            })
            .is_err()
    );
}

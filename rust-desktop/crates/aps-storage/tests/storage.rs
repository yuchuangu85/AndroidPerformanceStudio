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
    assert_eq!(
        storage.schema_version().unwrap(),
        aps_storage::SCHEMA_VERSION
    );
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
fn source_repository_round_trips_workspace_snapshot_files_symbols_and_candidates() {
    use aps_source::{
        PerformanceEvidenceId, ResolutionCandidate, ResolutionCandidateId, ResolutionConfidence,
        SourceFile, SourceLanguage, SourceProviderConfig, SourceRepository, SourceSnapshot,
        SourceSnapshotId, SourceSymbol, SourceSymbolKind, SourceWorkspace, SourceWorkspaceId,
        SourceWorkspacePhase,
    };
    let fixture = Fixture::new();
    let mut storage = fixture.storage();
    let workspace_id = SourceWorkspaceId::from_hex(&"ab".repeat(32)).unwrap();
    let snapshot_id = SourceSnapshotId::from_hex(&"cd".repeat(32)).unwrap();
    let workspace = SourceWorkspace {
        id: workspace_id,
        display_name: "Selected project".into(),
        config: SourceProviderConfig::Local {
            root: fixture.0.clone(),
        },
        active_snapshot_id: Some(snapshot_id),
        phase: SourceWorkspacePhase::Ready,
        progress: 1.0,
        message: Some("2 files · 2 symbols".into()),
        allow_ai_source_upload: true,
    };
    let snapshot = SourceSnapshot {
        id: snapshot_id,
        workspace_id,
        immutable_revision: "a".repeat(40),
        dirty_content_digest: Some(SourceDigest::from_bytes([7; 32])),
        manifest_hash: SourceDigest::from_bytes([8; 32]),
        created_unix_ms: 3,
        index_version: 4,
        index_complete: true,
    };
    let files = vec![
        SourceFile {
            snapshot_id,
            relative_path: "app/src/App.kt".into(),
            language: SourceLanguage::Kotlin,
            content_hash: SourceDigest::from_bytes([9; 32]),
            size_bytes: 12,
        },
        SourceFile {
            snapshot_id,
            relative_path: "app/src/Zed.kt".into(),
            language: SourceLanguage::Kotlin,
            content_hash: SourceDigest::from_bytes([10; 32]),
            size_bytes: 13,
        },
    ];
    let symbols = vec![
        SourceSymbol {
            snapshot_id,
            relative_path: "app/src/Zed.kt".into(),
            kind: SourceSymbolKind::Function,
            qualified_name: "demo.zed".into(),
            signature: Some("value: Int".into()),
            start_line: 4,
            end_line: 4,
        },
        SourceSymbol {
            snapshot_id,
            relative_path: "app/src/App.kt".into(),
            kind: SourceSymbolKind::Type,
            qualified_name: "demo.App".into(),
            signature: None,
            start_line: 2,
            end_line: 2,
        },
    ];
    // A snapshot cannot exist without its workspace (foreign key).
    assert!(storage.save_snapshot(&snapshot, &files, &symbols).is_err());
    storage.save_workspace(&workspace).unwrap();
    storage.save_workspace(&workspace).unwrap();
    storage.save_snapshot(&snapshot, &files, &symbols).unwrap();

    assert_eq!(
        storage.workspace(workspace_id).unwrap(),
        Some(workspace.clone())
    );
    assert_eq!(storage.workspaces().unwrap(), vec![workspace.clone()]);
    assert_eq!(
        storage.snapshot(snapshot_id).unwrap(),
        Some(snapshot.clone())
    );
    // Repository ordering is width-independent: relative path, then start line.
    assert_eq!(
        storage
            .files(snapshot_id)
            .unwrap()
            .iter()
            .map(|file| file.relative_path.as_str())
            .collect::<Vec<_>>(),
        ["app/src/App.kt", "app/src/Zed.kt"]
    );
    assert_eq!(
        storage
            .symbols(snapshot_id)
            .unwrap()
            .iter()
            .map(|symbol| symbol.qualified_name.as_str())
            .collect::<Vec<_>>(),
        ["demo.App", "demo.zed"]
    );

    let candidate = ResolutionCandidate {
        id: ResolutionCandidateId::from_hex(&"ef".repeat(32)).unwrap(),
        evidence_id: PerformanceEvidenceId::from_hex(&"12".repeat(32)).unwrap(),
        location: aps_source::SourceLocation {
            workspace_id,
            snapshot_id,
            relative_path: "app/src/App.kt".into(),
            range: Some(aps_source::SourceRange {
                start_line: 2,
                end_line: 2,
            }),
            content_hash: SourceDigest::from_bytes([9; 32]),
        },
        confidence: ResolutionConfidence::Probable,
        reasons: vec![
            "Qualified type matched".into(),
            "Build identity not verified".into(),
        ],
        index_version: 4,
        index_complete: true,
    };
    let unknown = ResolutionCandidate {
        location: aps_source::SourceLocation {
            snapshot_id: SourceSnapshotId::from_hex(&"34".repeat(32)).unwrap(),
            ..candidate.location.clone()
        },
        id: ResolutionCandidateId::from_hex(&"56".repeat(32)).unwrap(),
        ..candidate.clone()
    };
    assert!(storage.save_candidates(&[unknown]).is_err());
    storage.save_candidates(&[candidate.clone()]).unwrap();
    assert_eq!(
        storage.candidate(candidate.id).unwrap(),
        Some(candidate.clone())
    );

    // Removing a workspace cascades snapshots, files, symbols and candidates.
    storage.delete_workspace(workspace_id).unwrap();
    assert!(storage.workspace(workspace_id).unwrap().is_none());
    assert!(storage.snapshot(snapshot_id).unwrap().is_none());
    assert!(storage.files(snapshot_id).unwrap().is_empty());
    assert!(storage.symbols(snapshot_id).unwrap().is_empty());
    assert!(storage.candidate(candidate.id).unwrap().is_none());
    assert!(
        storage
            .list_sessions(PageRequest {
                offset: 0,
                limit: 101,
            })
            .is_err()
    );
}

#[test]
fn streaming_snapshot_writes_and_bounded_lookups_match_the_resolver() {
    use aps_source::{
        BuildIdentityMatch, InMemorySourceRepository, IndexedSourceResolver, PerformanceEvidenceId,
        SourceFile, SourceLanguage, SourceProviderConfig, SourceRepository,
        SourceResolutionEvidence, SourceSnapshot, SourceSnapshotId, SourceSymbol, SourceSymbolKind,
        SourceWorkspace, SourceWorkspaceId, SourceWorkspacePhase, SymbolNameSeparator,
    };
    let fixture = Fixture::new();
    let mut storage = fixture.storage();
    let workspace_id = SourceWorkspaceId::from_hex(&"71".repeat(32)).unwrap();
    let snapshot_id = SourceSnapshotId::from_hex(&"72".repeat(32)).unwrap();
    let workspace = SourceWorkspace {
        id: workspace_id,
        display_name: "Streaming".into(),
        config: SourceProviderConfig::Local {
            root: fixture.0.clone(),
        },
        active_snapshot_id: Some(snapshot_id),
        phase: SourceWorkspacePhase::Ready,
        progress: 1.0,
        message: None,
        allow_ai_source_upload: true,
    };
    storage.save_workspace(&workspace).unwrap();
    let snapshot = SourceSnapshot {
        id: snapshot_id,
        workspace_id,
        immutable_revision: "b".repeat(40),
        dirty_content_digest: None,
        manifest_hash: SourceDigest::from_bytes([11; 32]),
        created_unix_ms: 5,
        index_version: 6,
        index_complete: true,
    };
    let app = "src/main/java/demo/App.java";
    let xml = "src/main/res/layout/activity_main.xml";
    let native = "src/main/cpp/native-lib.cpp";
    let files = vec![
        SourceFile {
            snapshot_id,
            relative_path: app.into(),
            language: SourceLanguage::Java,
            content_hash: SourceDigest::from_bytes([12; 32]),
            size_bytes: 100,
        },
        SourceFile {
            snapshot_id,
            relative_path: xml.into(),
            language: SourceLanguage::Xml,
            content_hash: SourceDigest::from_bytes([13; 32]),
            size_bytes: 50,
        },
        SourceFile {
            snapshot_id,
            relative_path: native.into(),
            language: SourceLanguage::Cpp,
            content_hash: SourceDigest::from_bytes([14; 32]),
            size_bytes: 25,
        },
    ];
    let symbols = vec![
        SourceSymbol {
            snapshot_id,
            relative_path: app.into(),
            kind: SourceSymbolKind::Type,
            qualified_name: "demo.App".into(),
            signature: None,
            start_line: 1,
            end_line: 1,
        },
        SourceSymbol {
            snapshot_id,
            relative_path: app.into(),
            kind: SourceSymbolKind::Function,
            qualified_name: "demo.App.render".into(),
            signature: Some("View".into()),
            start_line: 10,
            end_line: 12,
        },
        SourceSymbol {
            snapshot_id,
            relative_path: xml.into(),
            kind: SourceSymbolKind::Resource,
            qualified_name: "layout/activity_main".into(),
            signature: None,
            start_line: 1,
            end_line: 1,
        },
        SourceSymbol {
            snapshot_id,
            relative_path: native.into(),
            kind: SourceSymbolKind::NativeSymbol,
            qualified_name: "native::add".into(),
            signature: Some("int".into()),
            start_line: 3,
            end_line: 3,
        },
    ];
    storage.begin_snapshot(&snapshot).unwrap();
    storage.append_files(snapshot_id, &files[..2]).unwrap();
    storage.append_files(snapshot_id, &files[2..]).unwrap();
    storage.append_symbols(snapshot_id, &symbols[..3]).unwrap();
    storage.append_symbols(snapshot_id, &symbols[3..]).unwrap();

    assert_eq!(
        storage
            .file(snapshot_id, app)
            .unwrap()
            .unwrap()
            .relative_path,
        app
    );
    assert_eq!(
        storage
            .files_named(snapshot_id, "App.java", 8)
            .unwrap()
            .len(),
        1
    );
    assert_eq!(
        storage
            .files_with_suffix(snapshot_id, app, 8)
            .unwrap()
            .len(),
        1
    );
    assert_eq!(
        storage
            .symbols_qualified(snapshot_id, SourceSymbolKind::Type, "demo.App", 8)
            .unwrap()
            .len(),
        1
    );
    assert_eq!(
        storage
            .symbols_with_short_name(
                snapshot_id,
                &[SourceSymbolKind::Function, SourceSymbolKind::Method],
                "render",
                SymbolNameSeparator::Dot,
                8,
            )
            .unwrap()
            .len(),
        1
    );
    assert_eq!(
        storage
            .symbols_with_short_name(
                snapshot_id,
                &[SourceSymbolKind::NativeSymbol],
                "add",
                SymbolNameSeparator::DoubleColon,
                8,
            )
            .unwrap()
            .len(),
        1
    );
    assert_eq!(
        storage.symbols_in_file(snapshot_id, app, 8).unwrap().len(),
        2
    );
    assert_eq!(
        storage
            .symbols_with_short_name(
                snapshot_id,
                &[SourceSymbolKind::Function],
                "missing",
                SymbolNameSeparator::Dot,
                8,
            )
            .unwrap()
            .len(),
        0
    );

    // The persistence-backed overrides and the in-memory repository must
    // produce the same deterministic resolver candidates.
    let mut memory = InMemorySourceRepository::default();
    memory.save_workspace(&workspace).unwrap();
    memory.save_snapshot(&snapshot, &files, &symbols).unwrap();
    let evidence = vec![
        SourceResolutionEvidence::TypeName {
            id: PerformanceEvidenceId::from_hex(&"81".repeat(32)).unwrap(),
            qualified_name: "demo.App".into(),
        },
        SourceResolutionEvidence::ManagedSymbol {
            id: PerformanceEvidenceId::from_hex(&"82".repeat(32)).unwrap(),
            class_name: Some("demo.App".into()),
            method_name: "render".into(),
            signature: None,
            resource_path: None,
        },
        SourceResolutionEvidence::AndroidResource {
            id: PerformanceEvidenceId::from_hex(&"83".repeat(32)).unwrap(),
            resource_type: "layout".into(),
            resource_name: "activity_main".into(),
        },
        SourceResolutionEvidence::NativeSymbol {
            id: PerformanceEvidenceId::from_hex(&"84".repeat(32)).unwrap(),
            symbol_name: "native::add".into(),
            library_path: None,
            build_id: None,
            source_path: Some("/src/main/cpp/native-lib.cpp".into()),
            source_line: Some(3),
        },
    ];
    let resolver = IndexedSourceResolver;
    let stored = resolver
        .resolve(
            &storage,
            &[snapshot_id],
            &evidence,
            BuildIdentityMatch::Unverified,
            usize::MAX,
        )
        .unwrap();
    let expected = resolver
        .resolve(
            &memory,
            &[snapshot_id],
            &evidence,
            BuildIdentityMatch::Unverified,
            usize::MAX,
        )
        .unwrap();
    assert_eq!(stored.len(), expected.len());
    assert_eq!(
        stored
            .iter()
            .map(|candidate| (
                candidate.location.clone(),
                candidate.confidence,
                candidate.reasons.clone()
            ))
            .collect::<Vec<_>>(),
        expected
            .iter()
            .map(|candidate| (
                candidate.location.clone(),
                candidate.confidence,
                candidate.reasons.clone()
            ))
            .collect::<Vec<_>>()
    );

    storage.delete_snapshot(snapshot_id).unwrap();
    assert!(storage.snapshot(snapshot_id).unwrap().is_none());
    assert!(storage.files(snapshot_id).unwrap().is_empty());
}

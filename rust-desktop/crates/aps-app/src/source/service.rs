//! Blocking source-workspace service used from the background executor.
//!
//! Owned by the source-data workstream; the Lead's runtime calls exactly these
//! functions. Implementations mirror Compose DefaultSourceWorkspaceService.kt.

use aps_contracts::SourceDigest;
use aps_source::{
    BuildIdentityMatch, ContentAddressedSourceCache, IndexedSourceResolver, ResolutionCandidate,
    ResolutionCandidateId, SourceContentState, SourceFile, SourceLanguage, SourceLocation,
    SourceProviderConfig, SourceProviderKind, SourceProviderRegistry, SourceRepository,
    SourceResolutionEvidence, SourceSnapshot, SourceSnapshotId, SourceWorkspace, SourceWorkspaceId,
    SourceWorkspacePhase, StructuralSourceIndexer, VerifiedSourceContent, digest_bytes,
    digest_text,
};
use aps_storage::Storage;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::{SystemTime, UNIX_EPOCH};

/// Compose updates live progress at most every 100 indexed files.
const PROGRESS_UPDATE_INTERVAL: usize = 100;
/// Files are flushed to SQLite in bounded batches so indexing a large tree never
/// accumulates every file or symbol in RAM.
const FILE_BATCH_SIZE: usize = 64;
const SYMBOL_BATCH_SIZE: usize = 20_000;
const CACHE_DIRECTORY: &str = "source-cache";

static WORKSPACE_COUNTER: AtomicU64 = AtomicU64::new(0);

#[derive(Debug, Clone)]
pub(super) struct SourcePaths {
    pub database: Option<PathBuf>,
    pub data_dir: Option<PathBuf>,
}

impl SourcePaths {
    pub(super) fn from_metadata_path(path: Option<&Path>) -> Self {
        Self {
            database: path.map(Path::to_path_buf),
            data_dir: path.and_then(Path::parent).map(Path::to_path_buf),
        }
    }

    pub(super) fn cache_root(&self) -> Result<PathBuf, String> {
        self.data_dir
            .as_ref()
            .map(|root| root.join(CACHE_DIRECTORY))
            .ok_or_else(|| "local source metadata is unavailable".into())
    }
}

#[derive(Debug, Clone, PartialEq)]
pub(super) struct Progress {
    pub phase: SourceWorkspacePhase,
    pub progress: f32,
    pub message: Option<String>,
}

pub(super) fn load_workspaces(paths: &SourcePaths) -> Result<Vec<SourceWorkspace>, String> {
    open(paths)?.workspaces()
}

/// Runtime lookup retained for the Lead's source entity contract.
#[allow(dead_code)]
pub(super) fn workspace(
    paths: &SourcePaths,
    id: SourceWorkspaceId,
) -> Result<Option<SourceWorkspace>, String> {
    open(paths)?.workspace(id)
}

pub(super) fn add_local(
    paths: &SourcePaths,
    root: PathBuf,
    display_name: String,
    progress: &mut dyn FnMut(Progress),
) -> Result<SourceWorkspace, String> {
    let display_name = display_name.trim().to_owned();
    if display_name.is_empty() {
        return Err("source workspace name is required".into());
    }
    let config = SourceProviderConfig::Local { root };
    config.validate()?;
    let workspace = SourceWorkspace {
        id: new_workspace_id(&config),
        display_name,
        config,
        active_snapshot_id: None,
        phase: SourceWorkspacePhase::Registering,
        progress: 0.0,
        message: None,
        allow_ai_source_upload: false,
    };
    {
        let mut storage = open(paths)?;
        storage.save_workspace(&workspace)?;
    }
    refresh(paths, workspace.id, progress)
}

pub(super) fn refresh(
    paths: &SourcePaths,
    id: SourceWorkspaceId,
    progress: &mut dyn FnMut(Progress),
) -> Result<SourceWorkspace, String> {
    let mut storage = open(paths)?;
    let initial = storage
        .workspace(id)?
        .ok_or_else(|| format!("unknown source workspace: {}", id.to_hex()))?;
    let registry = SourceProviderRegistry::local();
    let cache = ContentAddressedSourceCache::new(paths.cache_root()?);
    let indexer = StructuralSourceIndexer;
    let mut pending_snapshot: Option<SourceSnapshotId> = None;
    let outcome = (|| -> Result<SourceWorkspace, String> {
        publish(
            &mut storage,
            &initial,
            SourceWorkspacePhase::ResolvingRevision,
            0.05,
            None,
            progress,
        )?;
        let provider = registry.provider_for(initial.config.kind())?;
        let revision = provider.resolve_revision(&initial.config)?;
        publish(
            &mut storage,
            &initial,
            SourceWorkspacePhase::BuildingManifest,
            0.1,
            Some(revision.chars().take(12).collect()),
            progress,
        )?;
        let provider_files = provider.list_files(&initial.config, &revision)?;
        let manifest_text = provider_files
            .iter()
            .map(|file| {
                format!(
                    "{}:{}:{}",
                    file.relative_path,
                    file.content_hash
                        .map(SourceDigest::to_hex)
                        .unwrap_or_default(),
                    file.size_bytes
                )
            })
            .collect::<Vec<_>>()
            .join("\n");
        let manifest_hash = digest_text(&manifest_text);
        let snapshot_id = SourceSnapshotId::from_digest(digest_text(&format!(
            "{}:{}:{}",
            initial.id.to_hex(),
            revision,
            manifest_hash.to_hex()
        )));
        let created_unix_ms = now_unix_ms()?;
        let (immutable_revision, dirty_content_digest) = split_revision(&revision)?;
        let snapshot = SourceSnapshot {
            id: snapshot_id,
            workspace_id: initial.id,
            immutable_revision,
            dirty_content_digest,
            manifest_hash,
            created_unix_ms,
            index_version: created_unix_ms,
            index_complete: true,
        };
        // Refreshing an unchanged tree keeps the same content-addressed
        // snapshot. Reuse the indexed rows instead of rewriting (and briefly
        // exposing an incomplete active snapshot).
        if storage
            .snapshot(snapshot_id)?
            .is_some_and(|existing| existing.index_complete)
        {
            let (files_count, symbols_count) = storage.snapshot_counts(snapshot_id)?;
            let mut ready = initial.clone();
            ready.active_snapshot_id = Some(snapshot_id);
            ready.phase = SourceWorkspacePhase::Ready;
            ready.progress = 1.0;
            ready.message = Some(format!("{files_count} files · {symbols_count} symbols"));
            storage.save_workspace(&ready)?;
            progress(Progress {
                phase: ready.phase,
                progress: ready.progress,
                message: ready.message.clone(),
            });
            return Ok(ready);
        }
        storage.begin_snapshot(&snapshot)?;
        pending_snapshot = Some(snapshot_id);
        let mut file_batch = Vec::new();
        let mut symbol_batch = Vec::new();
        let mut file_count = 0usize;
        let mut symbol_count = 0usize;
        for (index, provider_file) in provider_files.iter().enumerate() {
            let content =
                provider.read_file(&initial.config, &revision, &provider_file.relative_path)?;
            let content_hash = cache.put(&content)?;
            let text = String::from_utf8_lossy(&content).into_owned();
            let source_file = SourceFile {
                snapshot_id,
                relative_path: provider_file.relative_path.clone(),
                language: SourceLanguage::from_path(&provider_file.relative_path),
                content_hash,
                size_bytes: content.len() as u64,
            };
            let mut indexed = indexer.index(&source_file, &text, usize::MAX);
            for symbol in &mut indexed {
                if let Some(signature) = symbol.signature.as_mut() {
                    *signature = bounded_signature(signature);
                    if signature.is_empty() {
                        symbol.signature = None;
                    }
                }
            }
            symbol_count += indexed.len();
            symbol_batch.extend(indexed);
            file_batch.push(source_file);
            file_count += 1;
            let last = index + 1 == provider_files.len();
            let flush = last
                || index % FILE_BATCH_SIZE == FILE_BATCH_SIZE - 1
                || symbol_batch.len() >= SYMBOL_BATCH_SIZE;
            if flush {
                storage.append_files(snapshot_id, &file_batch)?;
                storage.append_symbols(snapshot_id, &symbol_batch)?;
                file_batch.clear();
                symbol_batch.clear();
            }
            if last || index % PROGRESS_UPDATE_INTERVAL == 0 {
                let fraction = (index + 1) as f32 / provider_files.len() as f32;
                publish(
                    &mut storage,
                    &initial,
                    SourceWorkspacePhase::Indexing,
                    0.1 + 0.85 * fraction,
                    Some(format!(
                        "{}/{} · {} symbols",
                        index + 1,
                        provider_files.len(),
                        symbol_count
                    )),
                    progress,
                )?;
            }
        }
        if !file_batch.is_empty() || !symbol_batch.is_empty() {
            storage.append_files(snapshot_id, &file_batch)?;
            storage.append_symbols(snapshot_id, &symbol_batch)?;
        }
        pending_snapshot = None;
        let mut ready = initial.clone();
        ready.active_snapshot_id = Some(snapshot_id);
        ready.phase = SourceWorkspacePhase::Ready;
        ready.progress = 1.0;
        ready.message = Some(format!("{file_count} files · {symbol_count} symbols"));
        storage.save_workspace(&ready)?;
        progress(Progress {
            phase: ready.phase,
            progress: ready.progress,
            message: ready.message.clone(),
        });
        Ok(ready)
    })();
    match outcome {
        Ok(workspace) => Ok(workspace),
        Err(error) => {
            if let Some(snapshot_id) = pending_snapshot {
                let _ = storage.delete_snapshot(snapshot_id);
            }
            let mut failed = initial.clone();
            failed.phase = SourceWorkspacePhase::Failed;
            failed.progress = 0.0;
            failed.message = Some(if error.is_empty() {
                "source refresh failed".into()
            } else {
                error
            });
            storage.save_workspace(&failed).map_err(|save_error| {
                format!(
                    "source refresh failed: {}; cannot persist failure state: {save_error}",
                    failed.message.as_deref().unwrap_or("source refresh failed")
                )
            })?;
            progress(Progress {
                phase: failed.phase,
                progress: failed.progress,
                message: failed.message.clone(),
            });
            Ok(failed)
        }
    }
}

pub(super) fn remove(paths: &SourcePaths, id: SourceWorkspaceId) -> Result<(), String> {
    open(paths)?.delete_workspace(id)
}

pub(super) fn read(
    paths: &SourcePaths,
    location: &SourceLocation,
) -> Result<VerifiedSourceContent, String> {
    let storage = open(paths)?;
    let cache = ContentAddressedSourceCache::new(paths.cache_root()?);
    let content = cache.read(location.content_hash)?;
    let workspace = storage.workspace(location.workspace_id)?.ok_or_else(|| {
        format!(
            "unknown source workspace: {}",
            location.workspace_id.to_hex()
        )
    })?;
    let state = if matches!(workspace.config, SourceProviderConfig::Local { .. }) {
        let registry = SourceProviderRegistry::local();
        let provider = registry.provider_for(SourceProviderKind::Local)?;
        match provider.read_file(&workspace.config, "current", &location.relative_path) {
            Ok(current) if digest_bytes(&current) == location.content_hash => {
                SourceContentState::Current
            }
            _ => SourceContentState::Stale,
        }
    } else {
        SourceContentState::Current
    };
    Ok(VerifiedSourceContent {
        location: location.clone(),
        text: String::from_utf8_lossy(&content).into_owned(),
        state,
    })
}

pub(super) fn snapshot(
    paths: &SourcePaths,
    id: SourceSnapshotId,
) -> Result<Option<SourceSnapshot>, String> {
    open(paths)?.snapshot(id)
}

pub(super) fn files(paths: &SourcePaths, id: SourceSnapshotId) -> Result<Vec<SourceFile>, String> {
    open(paths)?.files(id)
}

/// Persisted candidate lookup retained for AI rebind flows.
#[allow(dead_code)]
pub(super) fn candidate(
    paths: &SourcePaths,
    id: ResolutionCandidateId,
) -> Result<Option<ResolutionCandidate>, String> {
    open(paths)?.candidate(id)
}

pub(super) fn save_candidates(
    paths: &SourcePaths,
    candidates: &[ResolutionCandidate],
) -> Result<(), String> {
    open(paths)?.save_candidates(candidates)
}

pub(super) fn resolve(
    paths: &SourcePaths,
    workspace_ids: &[SourceWorkspaceId],
    evidence: &[SourceResolutionEvidence],
) -> Result<Vec<ResolutionCandidate>, String> {
    let storage = open(paths)?;
    let mut snapshot_ids = Vec::new();
    for id in workspace_ids {
        let Some(workspace) = storage.workspace(*id)? else {
            continue;
        };
        if workspace.config.kind() != SourceProviderKind::Local {
            continue;
        }
        if let Some(snapshot_id) = workspace.active_snapshot_id {
            snapshot_ids.push(snapshot_id);
        }
    }
    if snapshot_ids.is_empty() {
        return Ok(Vec::new());
    }
    IndexedSourceResolver.resolve(
        &storage,
        &snapshot_ids,
        evidence,
        BuildIdentityMatch::Unverified,
        // Compose returns every deterministic candidate; navigation only uses
        // the first usable one, but candidate surfaces need the full set.
        usize::MAX,
    )
}

fn open(paths: &SourcePaths) -> Result<Storage, String> {
    let database = paths
        .database
        .as_ref()
        .ok_or_else(|| "local source metadata is unavailable".to_owned())?;
    Storage::open(database)
}

fn publish(
    storage: &mut Storage,
    initial: &SourceWorkspace,
    phase: SourceWorkspacePhase,
    progress_value: f32,
    message: Option<String>,
    progress: &mut dyn FnMut(Progress),
) -> Result<(), String> {
    let mut workspace = initial.clone();
    workspace.phase = phase;
    workspace.progress = progress_value;
    workspace.message = message;
    storage.save_workspace(&workspace)?;
    progress(Progress {
        phase,
        progress: progress_value,
        message: workspace.message,
    });
    Ok(())
}

/// Kotlin/Java parameter lists can span lines. The storage contract keeps
/// signatures control-free and bounded; normalize instead of failing a whole
/// project index for a multi-line parameter list.
fn bounded_signature(value: &str) -> String {
    let mut normalized = String::with_capacity(value.len().min(16 * 1024));
    for character in value.chars() {
        let character = if character.is_control() {
            ' '
        } else {
            character
        };
        if normalized.len() + character.len_utf8() > 16 * 1024 {
            break;
        }
        normalized.push(character);
    }
    normalized.trim().to_owned()
}

fn split_revision(revision: &str) -> Result<(String, Option<SourceDigest>), String> {
    match revision.split_once("-dirty-") {
        Some((commit, dirty)) => {
            let digest = if dirty.is_empty() {
                None
            } else {
                Some(
                    SourceDigest::from_hex(dirty)
                        .map_err(|_| "source revision dirty digest is invalid".to_owned())?,
                )
            };
            Ok((commit.to_owned(), digest))
        }
        None => Ok((revision.to_owned(), None)),
    }
}

fn now_unix_ms() -> Result<u64, String> {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_millis() as u64)
        .map_err(|_| "system clock is before the Unix epoch".to_owned())
}

fn new_workspace_id(config: &SourceProviderConfig) -> SourceWorkspaceId {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|duration| duration.as_nanos())
        .unwrap_or_default();
    let counter = WORKSPACE_COUNTER.fetch_add(1, Ordering::Relaxed);
    let identity = format!(
        "aps-source-workspace-v1\u{0}{}\u{0}{nanos}\u{0}{counter}\u{0}{}",
        std::process::id(),
        provider_identity(config)
    );
    SourceWorkspaceId::from_digest(digest_text(&identity))
}

fn provider_identity(config: &SourceProviderConfig) -> String {
    match config {
        SourceProviderConfig::Local { root } => format!("local:{}", root.display()),
        SourceProviderConfig::GitHub {
            owner, repository, ..
        } => format!("github:{owner}/{repository}"),
        SourceProviderConfig::Aosp { project, .. } => format!("aosp:{project}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_source::{
        PerformanceEvidenceId, ResolutionConfidence, SourceContentState, SourceLocation,
        SourceResolutionEvidence, SourceSnapshotId, SourceWorkspacePhase, digest_text,
    };
    use std::sync::atomic::{AtomicU64, Ordering};

    static NEXT: AtomicU64 = AtomicU64::new(0);

    struct Fixture {
        root: PathBuf,
    }

    impl Fixture {
        fn new() -> Self {
            let root = std::env::temp_dir().join(format!(
                "aps-source-service-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            std::fs::create_dir_all(&root).unwrap();
            Self { root }
        }

        fn paths(&self) -> SourcePaths {
            SourcePaths {
                database: Some(self.root.join("aps-metadata.sqlite3")),
                data_dir: Some(self.root.clone()),
            }
        }

        fn project(&self) -> PathBuf {
            self.root.join("project")
        }

        fn write(&self, relative: &str, text: &str) {
            let path = self.project().join(relative);
            std::fs::create_dir_all(path.parent().unwrap()).unwrap();
            std::fs::write(path, text).unwrap();
        }
    }

    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.root);
        }
    }

    const KOTLIN: &str =
        "package sample\n\nclass Widget {\n    fun render(value: Int) = value\n}\n";
    const XML: &str = "<LinearLayout><TextView android:id=\"@+id/title\" /></LinearLayout>\n";

    fn location(
        workspace: &SourceWorkspace,
        snapshot: &SourceSnapshot,
        file: &SourceFile,
    ) -> SourceLocation {
        SourceLocation {
            workspace_id: workspace.id,
            snapshot_id: snapshot.id,
            relative_path: file.relative_path.clone(),
            range: None,
            content_hash: file.content_hash,
        }
    }

    #[test]
    fn add_refresh_and_read_round_trip_matches_compose_service() {
        let fixture = Fixture::new();
        fixture.write("src/main/kotlin/sample/Widget.kt", KOTLIN);
        fixture.write("src/main/res/layout/widget.xml", XML);
        fixture.write("build/generated/Ignored.kt", "class Ignored\n");
        fixture.write("README.md", "# ignored extension\n");
        let paths = fixture.paths();
        let mut changes = Vec::new();
        let workspace = add_local(
            &paths,
            fixture.project(),
            "Fixture".into(),
            &mut |progress| changes.push(progress),
        )
        .unwrap();
        assert_eq!(workspace.phase, SourceWorkspacePhase::Ready);
        assert_eq!(workspace.progress, 1.0);
        assert_eq!(workspace.message.as_deref(), Some("2 files · 3 symbols"));
        let phases: Vec<SourceWorkspacePhase> = changes.iter().map(|change| change.phase).collect();
        assert_eq!(
            phases.first(),
            Some(&SourceWorkspacePhase::ResolvingRevision)
        );
        assert_eq!(phases.last(), Some(&SourceWorkspacePhase::Ready));
        assert!(phases.contains(&SourceWorkspacePhase::Indexing));

        let snap = snapshot(&paths, workspace.active_snapshot_id.unwrap())
            .unwrap()
            .unwrap();
        // Snapshot identity is sha256("<workspace>:<revision>:<manifest>").
        let revision = match snap.dirty_content_digest {
            Some(digest) => format!("{}-dirty-{}", snap.immutable_revision, digest.to_hex()),
            None => snap.immutable_revision.clone(),
        };
        let expected = SourceSnapshotId::from_digest(digest_text(&format!(
            "{}:{}:{}",
            workspace.id.to_hex(),
            revision,
            snap.manifest_hash.to_hex()
        )));
        assert_eq!(snap.id, expected);

        let file_rows = files(&paths, snap.id).unwrap();
        assert_eq!(
            file_rows
                .iter()
                .map(|file| file.relative_path.as_str())
                .collect::<Vec<_>>(),
            [
                "src/main/kotlin/sample/Widget.kt",
                "src/main/res/layout/widget.xml"
            ]
        );
        assert!(file_rows.iter().all(|file| file.size_bytes > 0));

        let kotlin = file_rows
            .iter()
            .find(|file| file.language == SourceLanguage::Kotlin)
            .unwrap();
        let location = location(&workspace, &snap, kotlin);
        let current = read(&paths, &location).unwrap();
        assert_eq!(current.state, SourceContentState::Current);
        assert!(current.text.contains("class Widget"));

        fixture.write(
            "src/main/kotlin/sample/Widget.kt",
            "package sample\nclass Changed\n",
        );
        let stale = read(&paths, &location).unwrap();
        assert_eq!(stale.state, SourceContentState::Stale);
        assert!(stale.text.contains("class Widget"));

        // Deterministic evidence resolution + candidate persistence.
        let evidence = vec![SourceResolutionEvidence::TypeName {
            id: PerformanceEvidenceId::from_digest(digest_text("type")),
            qualified_name: "sample.Widget".into(),
        }];
        let candidates = resolve(&paths, &[workspace.id], &evidence).unwrap();
        assert!(!candidates.is_empty());
        assert_eq!(candidates[0].confidence, ResolutionConfidence::Probable);
        assert_eq!(
            candidates[0].reasons,
            vec![
                "Qualified type matched".to_owned(),
                "Build identity not verified".to_owned()
            ]
        );
        assert_eq!(
            candidates[0].location.relative_path,
            "src/main/kotlin/sample/Widget.kt"
        );
        save_candidates(&paths, &candidates).unwrap();
        let stored = candidate(&paths, candidates[0].id).unwrap().unwrap();
        assert_eq!(stored.location, candidates[0].location);

        remove(&paths, workspace.id).unwrap();
        assert!(load_workspaces(&paths).unwrap().is_empty());
        assert!(snapshot(&paths, snap.id).unwrap().is_none());
        assert!(files(&paths, snap.id).unwrap().is_empty());
        assert!(read(&paths, &location).is_err());
    }

    #[test]
    fn empty_project_reaches_ready_with_zero_counts() {
        let fixture = Fixture::new();
        std::fs::create_dir_all(fixture.project()).unwrap();
        let paths = fixture.paths();
        let workspace = add_local(&paths, fixture.project(), "Empty".into(), &mut |_| {}).unwrap();
        assert_eq!(workspace.phase, SourceWorkspacePhase::Ready);
        assert_eq!(workspace.message.as_deref(), Some("0 files · 0 symbols"));
        let snap = snapshot(&paths, workspace.active_snapshot_id.unwrap())
            .unwrap()
            .unwrap();
        assert!(files(&paths, snap.id).unwrap().is_empty());
    }

    #[test]
    fn refresh_failure_is_persisted_and_keeps_the_previous_snapshot() {
        let fixture = Fixture::new();
        fixture.write("src/Main.kt", KOTLIN);
        let paths = fixture.paths();
        let workspace =
            add_local(&paths, fixture.project(), "Fixture".into(), &mut |_| {}).unwrap();
        let previous = workspace.active_snapshot_id.unwrap();
        std::fs::remove_dir_all(fixture.project()).unwrap();
        let mut changes = Vec::new();
        let failed = refresh(&paths, workspace.id, &mut |progress| changes.push(progress)).unwrap();
        assert_eq!(failed.phase, SourceWorkspacePhase::Failed);
        assert_eq!(failed.progress, 0.0);
        assert_eq!(failed.active_snapshot_id, Some(previous));
        assert!(failed.message.is_some());
        assert_eq!(
            load_workspaces(&paths).unwrap()[0].phase,
            SourceWorkspacePhase::Failed
        );
        assert_eq!(changes.last().unwrap().phase, SourceWorkspacePhase::Failed);
    }

    #[test]
    fn missing_database_reports_metadata_unavailable() {
        let fixture = Fixture::new();
        let paths = SourcePaths {
            database: None,
            data_dir: Some(fixture.root.clone()),
        };
        assert!(load_workspaces(&paths).is_err());
        assert!(add_local(&paths, fixture.project(), "Fixture".into(), &mut |_| {}).is_err());
        assert!(remove(&paths, SourceWorkspaceId::from_digest(digest_text("ws"))).is_err());
        assert!(resolve(&paths, &[], &[]).is_err());
    }

    #[test]
    #[ignore = "manual large-workspace smoke; requires APS_AOSP_SMOKE_DIR/APS_AOSP_ROOT"]
    fn manual_aosp_streaming_smoke() {
        let data_dir = PathBuf::from(
            std::env::var("APS_AOSP_SMOKE_DIR").expect("APS_AOSP_SMOKE_DIR is required"),
        );
        let root = std::env::var("APS_AOSP_ROOT").expect("APS_AOSP_ROOT is required");
        let paths = SourcePaths {
            database: Some(data_dir.join("aps-metadata.sqlite3")),
            data_dir: Some(data_dir.clone()),
        };
        let workspace = load_workspaces(&paths)
            .unwrap()
            .into_iter()
            .find(|workspace| {
                matches!(&workspace.config, SourceProviderConfig::Local { root: candidate } if candidate == &root)
            })
            .expect("AOSP workspace is missing from the smoke database");
        let started = std::time::Instant::now();
        let mut last_bucket = -1i32;
        let refreshed = refresh(&paths, workspace.id, &mut |progress| {
            let bucket = (progress.progress * 10.0) as i32;
            if bucket != last_bucket {
                last_bucket = bucket;
                eprintln!(
                    "progress {:?} {:.1}% {}",
                    progress.phase,
                    progress.progress * 100.0,
                    progress.message.as_deref().unwrap_or("")
                );
            }
        })
        .unwrap();
        eprintln!(
            "elapsed {:?} phase {:?} message {:?}",
            started.elapsed(),
            refreshed.phase,
            refreshed.message
        );
        let snapshot_id = refreshed
            .active_snapshot_id
            .expect("successful refresh publishes a snapshot");
        let storage = Storage::open(&data_dir.join("aps-metadata.sqlite3")).unwrap();
        let (files, symbols) = storage.snapshot_counts(snapshot_id).unwrap();
        eprintln!("stored files {files} symbols {symbols}");
        let rss = std::process::Command::new("ps")
            .args(["-o", "rss=", "-p", &std::process::id().to_string()])
            .output()
            .ok()
            .and_then(|output| String::from_utf8(output.stdout).ok())
            .map(|value| value.trim().to_owned())
            .unwrap_or_default();
        eprintln!("test process RSS {rss} KiB");
        assert_eq!(refreshed.phase, SourceWorkspacePhase::Ready);
        assert!(files > 1000);
        assert!(symbols > 0);

        // Bounded lookups: resolve representative evidence without loading the
        // 1.5M-symbol snapshot into memory.
        let java_hash = |value: &str| {
            let mut hash: i32 = 0;
            for unit in value.encode_utf16() {
                hash = hash.wrapping_mul(31).wrapping_add(i32::from(unit));
            }
            hash.wrapping_abs() as u32
        };
        let evidence = [
            (
                "type",
                SourceResolutionEvidence::TypeName {
                    id: PerformanceEvidenceId::from_hex(&"91".repeat(32)).unwrap(),
                    qualified_name: "android.widget.FrameLayout".into(),
                },
            ),
            (
                "method",
                SourceResolutionEvidence::ManagedSymbol {
                    id: PerformanceEvidenceId::from_hex(&"92".repeat(32)).unwrap(),
                    class_name: Some("android.widget.FrameLayout".into()),
                    method_name: "onLayout".into(),
                    signature: None,
                    resource_path: None,
                },
            ),
            (
                "file-line",
                SourceResolutionEvidence::SourceFileLine {
                    id: PerformanceEvidenceId::from_hex(&"93".repeat(32)).unwrap(),
                    file_name: "FrameLayout.java".into(),
                    package_hash: java_hash("android.widget"),
                    line: 10,
                },
            ),
        ];
        let mut total_candidates = 0usize;
        for (label, item) in evidence {
            let started = std::time::Instant::now();
            let candidates = resolve(&paths, &[workspace.id], &[item]).unwrap();
            eprintln!(
                "lookup {label}: {} candidates in {:?}",
                candidates.len(),
                started.elapsed()
            );
            if let Some(first) = candidates.first() {
                eprintln!(
                    "  first: {}:{}",
                    first.location.relative_path,
                    first.location.range.map_or(0, |range| range.start_line)
                );
            }
            total_candidates += candidates.len();
        }
        let rss = std::process::Command::new("ps")
            .args(["-o", "rss=", "-p", &std::process::id().to_string()])
            .output()
            .ok()
            .and_then(|output| String::from_utf8(output.stdout).ok())
            .map(|value| value.trim().to_owned())
            .unwrap_or_default();
        eprintln!("test process RSS after lookups {rss} KiB");
        assert!(total_candidates > 0);
    }
}

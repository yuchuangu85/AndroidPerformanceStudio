//! Rust-owned SQLite persistence for the Compose-parity Source Workspace model.
use crate::{Storage, json};
use aps_contracts::SourceDigest;
use aps_source::{
    PerformanceEvidenceId, ResolutionCandidate, ResolutionCandidateId, ResolutionConfidence,
    SourceFile, SourceLanguage, SourceProviderConfig, SourceProviderKind, SourceRepository,
    SourceSnapshot, SourceSnapshotId, SourceSymbol, SourceSymbolKind, SourceWorkspace,
    SourceWorkspaceId, SourceWorkspacePhase,
};
use rusqlite::{OptionalExtension, TransactionBehavior, params};

const MAX_PATH_BYTES: usize = 4096;
const MAX_REASON_BYTES: usize = 512;
const MAX_REASONS: usize = 32;
const MAX_WORKSPACES: usize = 10_000;
const MAX_FILES: usize = 100_000;
const MAX_SYMBOLS: usize = 1_000_000;

fn bounded(value: &str, label: &str, limit: usize) -> Result<(), String> {
    if value.len() > limit || value.chars().any(char::is_control) {
        return Err(format!(
            "{label} must be control-free text within {limit} bytes"
        ));
    }
    Ok(())
}

fn required_text(value: &str, label: &str, limit: usize) -> Result<(), String> {
    aps_contracts::validate_text(value, label, limit)
}

fn non_negative(value: i64, label: &str) -> Result<u64, String> {
    u64::try_from(value).map_err(|_| format!("stored {label} must not be negative"))
}

fn sql_integer(value: u64, label: &str) -> Result<i64, String> {
    i64::try_from(value).map_err(|_| format!("{label} exceeds SQLite integer range"))
}

fn canonical_digest(value: &str, label: &str) -> Result<SourceDigest, String> {
    let digest = SourceDigest::from_hex(value)?;
    if digest.to_hex() != value {
        return Err(format!("{label} must be canonical lowercase SHA-256 hex"));
    }
    Ok(digest)
}

fn source_path(value: &str) -> Result<(), String> {
    if value.is_empty()
        || value.len() > MAX_PATH_BYTES
        || value.starts_with('/')
        || value.contains(['\\', ':', '\0'])
        || value.chars().any(char::is_control)
    {
        return Err("stored source path must be a bounded relative slash path".into());
    }
    if value
        .split('/')
        .any(|segment| segment.is_empty() || segment == "." || segment == "..")
    {
        return Err("stored source path contains an unsafe component".into());
    }
    Ok(())
}

fn provider_kind_name(kind: SourceProviderKind) -> &'static str {
    match kind {
        SourceProviderKind::Local => "Local",
        SourceProviderKind::GitHub => "GitHub",
        SourceProviderKind::Aosp => "Aosp",
    }
}

fn parse_provider_kind(value: &str) -> Result<SourceProviderKind, String> {
    match value {
        "Local" => Ok(SourceProviderKind::Local),
        "GitHub" => Ok(SourceProviderKind::GitHub),
        "Aosp" => Ok(SourceProviderKind::Aosp),
        _ => Err("stored source provider kind is unknown".into()),
    }
}

fn phase_name(phase: SourceWorkspacePhase) -> &'static str {
    match phase {
        SourceWorkspacePhase::Registering => "Registering",
        SourceWorkspacePhase::ResolvingRevision => "ResolvingRevision",
        SourceWorkspacePhase::BuildingManifest => "BuildingManifest",
        SourceWorkspacePhase::Indexing => "Indexing",
        SourceWorkspacePhase::Ready => "Ready",
        SourceWorkspacePhase::Partial => "Partial",
        SourceWorkspacePhase::Failed => "Failed",
    }
}

fn parse_phase(value: &str) -> Result<SourceWorkspacePhase, String> {
    match value {
        "Registering" => Ok(SourceWorkspacePhase::Registering),
        "ResolvingRevision" => Ok(SourceWorkspacePhase::ResolvingRevision),
        "BuildingManifest" => Ok(SourceWorkspacePhase::BuildingManifest),
        "Indexing" => Ok(SourceWorkspacePhase::Indexing),
        "Ready" => Ok(SourceWorkspacePhase::Ready),
        "Partial" => Ok(SourceWorkspacePhase::Partial),
        "Failed" => Ok(SourceWorkspacePhase::Failed),
        _ => Err("stored source workspace phase is unknown".into()),
    }
}

fn language_name(language: SourceLanguage) -> &'static str {
    match language {
        SourceLanguage::Kotlin => "Kotlin",
        SourceLanguage::Java => "Java",
        SourceLanguage::Xml => "Xml",
        SourceLanguage::C => "C",
        SourceLanguage::Cpp => "Cpp",
        SourceLanguage::Other => "Other",
    }
}

fn parse_language(value: &str) -> Result<SourceLanguage, String> {
    match value {
        "Kotlin" => Ok(SourceLanguage::Kotlin),
        "Java" => Ok(SourceLanguage::Java),
        "Xml" => Ok(SourceLanguage::Xml),
        "C" => Ok(SourceLanguage::C),
        "Cpp" => Ok(SourceLanguage::Cpp),
        "Other" => Ok(SourceLanguage::Other),
        _ => Err("stored source language is unknown".into()),
    }
}

fn symbol_kind_name(kind: SourceSymbolKind) -> &'static str {
    match kind {
        SourceSymbolKind::Package => "Package",
        SourceSymbolKind::Type => "Type",
        SourceSymbolKind::Function => "Function",
        SourceSymbolKind::Method => "Method",
        SourceSymbolKind::Resource => "Resource",
        SourceSymbolKind::NativeSymbol => "NativeSymbol",
    }
}

fn parse_symbol_kind(value: &str) -> Result<SourceSymbolKind, String> {
    match value {
        "Package" => Ok(SourceSymbolKind::Package),
        "Type" => Ok(SourceSymbolKind::Type),
        "Function" => Ok(SourceSymbolKind::Function),
        "Method" => Ok(SourceSymbolKind::Method),
        "Resource" => Ok(SourceSymbolKind::Resource),
        "NativeSymbol" => Ok(SourceSymbolKind::NativeSymbol),
        _ => Err("stored source symbol kind is unknown".into()),
    }
}

fn confidence_name(confidence: ResolutionConfidence) -> &'static str {
    match confidence {
        ResolutionConfidence::Exact => "Exact",
        ResolutionConfidence::Probable => "Probable",
        ResolutionConfidence::Weak => "Weak",
    }
}

fn parse_confidence(value: &str) -> Result<ResolutionConfidence, String> {
    match value {
        "Exact" => Ok(ResolutionConfidence::Exact),
        "Probable" => Ok(ResolutionConfidence::Probable),
        "Weak" => Ok(ResolutionConfidence::Weak),
        _ => Err("stored resolution confidence is unknown".into()),
    }
}

struct ConfigColumns {
    kind: &'static str,
    local_root: Option<String>,
    github_owner: Option<String>,
    github_repository: Option<String>,
    provider_ref: Option<String>,
    credential_key: Option<String>,
    aosp_project: Option<String>,
}

fn config_columns(config: &SourceProviderConfig) -> Result<ConfigColumns, String> {
    config.validate()?;
    let mut columns = ConfigColumns {
        kind: provider_kind_name(config.kind()),
        local_root: None,
        github_owner: None,
        github_repository: None,
        provider_ref: None,
        credential_key: None,
        aosp_project: None,
    };
    match config {
        SourceProviderConfig::Local { root } => {
            let root = root
                .to_str()
                .ok_or_else(|| "local source root must be valid UTF-8".to_owned())?;
            required_text(root, "local source root", MAX_PATH_BYTES)?;
            columns.local_root = Some(root.to_owned());
        }
        SourceProviderConfig::GitHub {
            owner,
            repository,
            git_ref,
            credential_key,
        } => {
            columns.github_owner = Some(owner.clone());
            columns.github_repository = Some(repository.clone());
            columns.provider_ref = Some(git_ref.clone());
            columns.credential_key = credential_key.clone();
        }
        SourceProviderConfig::Aosp { project, git_ref } => {
            columns.provider_ref = Some(git_ref.clone());
            columns.aosp_project = Some(project.clone());
        }
    }
    Ok(columns)
}

fn decode_config(
    kind: &str,
    local_root: Option<String>,
    github_owner: Option<String>,
    github_repository: Option<String>,
    provider_ref: Option<String>,
    credential_key: Option<String>,
    aosp_project: Option<String>,
) -> Result<SourceProviderConfig, String> {
    let config = match parse_provider_kind(kind)? {
        SourceProviderKind::Local => SourceProviderConfig::Local {
            root: std::path::PathBuf::from(
                local_root.ok_or_else(|| "stored local source root is missing".to_owned())?,
            ),
        },
        SourceProviderKind::GitHub => SourceProviderConfig::GitHub {
            owner: github_owner.ok_or_else(|| "stored GitHub owner is missing".to_owned())?,
            repository: github_repository
                .ok_or_else(|| "stored GitHub repository is missing".to_owned())?,
            git_ref: provider_ref.ok_or_else(|| "stored GitHub ref is missing".to_owned())?,
            credential_key,
        },
        SourceProviderKind::Aosp => SourceProviderConfig::Aosp {
            project: aosp_project.ok_or_else(|| "stored AOSP project is missing".to_owned())?,
            git_ref: provider_ref.ok_or_else(|| "stored AOSP ref is missing".to_owned())?,
        },
    };
    config.validate()?;
    Ok(config)
}

type WorkspaceRow = (
    String,
    String,
    String,
    Option<String>,
    Option<String>,
    Option<String>,
    Option<String>,
    Option<String>,
    Option<String>,
    Option<String>,
    String,
    f64,
    Option<String>,
    bool,
);

fn workspace_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<WorkspaceRow> {
    Ok((
        row.get(0)?,
        row.get(1)?,
        row.get(2)?,
        row.get(3)?,
        row.get(4)?,
        row.get(5)?,
        row.get(6)?,
        row.get(7)?,
        row.get(8)?,
        row.get(9)?,
        row.get(10)?,
        row.get(11)?,
        row.get(12)?,
        row.get(13)?,
    ))
}

fn decode_workspace(row: WorkspaceRow) -> Result<SourceWorkspace, String> {
    required_text(&row.1, "stored source workspace name", 256)?;
    if !row.11.is_finite() || !(0.0..=1.0).contains(&row.11) {
        return Err("stored source workspace progress must be finite within 0..=1".into());
    }
    if let Some(message) = &row.12 {
        bounded(message, "stored source workspace message", 1024)?;
    }
    Ok(SourceWorkspace {
        id: SourceWorkspaceId::from_hex(&row.0)?,
        display_name: row.1,
        config: decode_config(&row.2, row.3, row.4, row.5, row.6, row.7, row.8)?,
        active_snapshot_id: row
            .9
            .as_deref()
            .map(SourceSnapshotId::from_hex)
            .transpose()?,
        phase: parse_phase(&row.10)?,
        progress: row.11 as f32,
        message: row.12,
        allow_ai_source_upload: row.13,
    })
}

const WORKSPACE_COLUMNS: &str = "id,display_name,provider_kind,local_root,github_owner,github_repository,provider_ref,credential_key,aosp_project,active_snapshot_id,phase,progress,message,allow_ai_source_upload";

impl SourceRepository for Storage {
    fn save_workspace(&mut self, workspace: &SourceWorkspace) -> Result<(), String> {
        required_text(
            &workspace.display_name,
            "source workspace display name",
            256,
        )?;
        if !workspace.progress.is_finite() || !(0.0..=1.0).contains(&workspace.progress) {
            return Err("source workspace progress must be finite within 0..=1".into());
        }
        if let Some(message) = &workspace.message {
            bounded(message, "source workspace message", 1024)?;
        }
        let columns = config_columns(&workspace.config)?;
        let id = workspace.id.to_hex();
        let active_snapshot_id = workspace.active_snapshot_id.map(|id| id.to_hex());
        let transaction = self
            .connection
            .transaction_with_behavior(TransactionBehavior::Immediate)
            .map_err(|_| "cannot begin source workspace transaction")?;
        transaction
            .execute(
                "INSERT INTO source_workspaces(id,display_name,provider_kind,local_root,github_owner,github_repository,provider_ref,credential_key,aosp_project,active_snapshot_id,phase,progress,message,allow_ai_source_upload) VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14) ON CONFLICT(id) DO UPDATE SET display_name=excluded.display_name,provider_kind=excluded.provider_kind,local_root=excluded.local_root,github_owner=excluded.github_owner,github_repository=excluded.github_repository,provider_ref=excluded.provider_ref,credential_key=excluded.credential_key,aosp_project=excluded.aosp_project,active_snapshot_id=excluded.active_snapshot_id,phase=excluded.phase,progress=excluded.progress,message=excluded.message,allow_ai_source_upload=excluded.allow_ai_source_upload",
                params![
                    id,
                    workspace.display_name,
                    columns.kind,
                    columns.local_root,
                    columns.github_owner,
                    columns.github_repository,
                    columns.provider_ref,
                    columns.credential_key,
                    columns.aosp_project,
                    active_snapshot_id,
                    phase_name(workspace.phase),
                    f64::from(workspace.progress),
                    workspace.message,
                    workspace.allow_ai_source_upload,
                ],
            )
            .map_err(|_| "cannot persist source workspace metadata")?;
        transaction
            .commit()
            .map_err(|_| "cannot commit source workspace metadata".into())
    }

    fn workspace(&self, id: SourceWorkspaceId) -> Result<Option<SourceWorkspace>, String> {
        let raw = self
            .connection
            .query_row(
                &format!("SELECT {WORKSPACE_COLUMNS} FROM source_workspaces WHERE id=?1"),
                [id.to_hex()],
                workspace_row,
            )
            .optional()
            .map_err(|_| "cannot read source workspace metadata")?;
        raw.map(decode_workspace).transpose()
    }

    fn workspaces(&self) -> Result<Vec<SourceWorkspace>, String> {
        let mut statement = self
            .connection
            .prepare(&format!(
                "SELECT {WORKSPACE_COLUMNS} FROM source_workspaces ORDER BY display_name,id LIMIT 10001"
            ))
            .map_err(|_| "cannot prepare source workspace listing")?;
        let values = statement
            .query_map([], workspace_row)
            .map_err(|_| "cannot query source workspaces")?;
        let mut workspaces = Vec::new();
        for value in values {
            workspaces.push(decode_workspace(
                value.map_err(|_| "invalid source workspace row")?,
            )?);
        }
        if workspaces.len() > MAX_WORKSPACES {
            return Err("stored source workspaces exceed the supported bound".into());
        }
        Ok(workspaces)
    }

    fn delete_workspace(&mut self, id: SourceWorkspaceId) -> Result<(), String> {
        let transaction = self
            .connection
            .transaction_with_behavior(TransactionBehavior::Immediate)
            .map_err(|_| "cannot begin source workspace removal transaction")?;
        transaction
            .execute("DELETE FROM source_workspaces WHERE id=?1", [id.to_hex()])
            .map_err(|_| "cannot remove source workspace metadata")?;
        transaction
            .commit()
            .map_err(|_| "cannot commit source workspace removal".into())
    }

    fn save_snapshot(
        &mut self,
        snapshot: &SourceSnapshot,
        files: &[SourceFile],
        symbols: &[SourceSymbol],
    ) -> Result<(), String> {
        let snapshot_id = snapshot.id.to_hex();
        bounded(
            &snapshot.immutable_revision,
            "source immutable revision",
            MAX_PATH_BYTES,
        )?;
        let created_ms = sql_integer(snapshot.created_unix_ms, "source snapshot timestamp")?;
        let index_version = sql_integer(snapshot.index_version, "source index version")?;
        if files.len() > MAX_FILES {
            return Err("source snapshot exceeds the supported file bound".into());
        }
        if symbols.len() > MAX_SYMBOLS {
            return Err("source snapshot exceeds the supported symbol bound".into());
        }
        for file in files {
            if file.snapshot_id != snapshot.id {
                return Err("source file belongs to a different snapshot".into());
            }
            source_path(&file.relative_path)?;
            sql_integer(file.size_bytes, "source file size")?;
        }
        for item in symbols {
            if item.snapshot_id != snapshot.id {
                return Err("source symbol belongs to a different snapshot".into());
            }
            source_path(&item.relative_path)?;
            required_text(&item.qualified_name, "source symbol qualified name", 4096)?;
            if let Some(signature) = &item.signature {
                bounded(signature, "source symbol signature", 16 * 1024)?;
            }
            if item.start_line == 0 || item.end_line < item.start_line {
                return Err("source symbol line range is invalid".into());
            }
            sql_integer(item.start_line as u64, "source symbol start line")?;
            sql_integer(item.end_line as u64, "source symbol end line")?;
        }
        let transaction = self
            .connection
            .transaction_with_behavior(TransactionBehavior::Immediate)
            .map_err(|_| "cannot begin source snapshot transaction")?;
        transaction
            .execute(
                "INSERT OR REPLACE INTO source_snapshots(id,workspace_id,immutable_revision,dirty_content_digest,manifest_hash,created_ms,index_version,index_complete) VALUES(?1,?2,?3,?4,?5,?6,?7,?8)",
                params![
                    snapshot_id,
                    snapshot.workspace_id.to_hex(),
                    snapshot.immutable_revision,
                    snapshot.dirty_content_digest.map(|digest| digest.to_hex()),
                    snapshot.manifest_hash.to_hex(),
                    created_ms,
                    index_version,
                    snapshot.index_complete,
                ],
            )
            .map_err(|_| "cannot persist source snapshot; workspace must exist")?;
        transaction
            .execute(
                "DELETE FROM source_files WHERE snapshot_id=?1",
                [&snapshot_id],
            )
            .map_err(|_| "cannot replace source snapshot files")?;
        transaction
            .execute(
                "DELETE FROM source_symbols WHERE snapshot_id=?1",
                [&snapshot_id],
            )
            .map_err(|_| "cannot replace source snapshot symbols")?;
        {
            let mut statement = transaction
                .prepare("INSERT INTO source_files(snapshot_id,relative_path,language,content_hash,size_bytes) VALUES(?1,?2,?3,?4,?5)")
                .map_err(|_| "cannot prepare source file insertion")?;
            for file in files {
                statement
                    .execute(params![
                        &snapshot_id,
                        file.relative_path,
                        language_name(file.language),
                        file.content_hash.to_hex(),
                        sql_integer(file.size_bytes, "source file size")?,
                    ])
                    .map_err(|_| "cannot persist source snapshot file")?;
            }
        }
        {
            let mut statement = transaction
                .prepare("INSERT INTO source_symbols(snapshot_id,relative_path,kind,qualified_name,signature,start_line,end_line) VALUES(?1,?2,?3,?4,?5,?6,?7)")
                .map_err(|_| "cannot prepare source symbol insertion")?;
            for item in symbols {
                statement
                    .execute(params![
                        &snapshot_id,
                        item.relative_path,
                        symbol_kind_name(item.kind),
                        item.qualified_name,
                        item.signature,
                        item.start_line as i64,
                        item.end_line as i64,
                    ])
                    .map_err(|_| "cannot persist source snapshot symbol")?;
            }
        }
        transaction
            .commit()
            .map_err(|_| "cannot commit source snapshot metadata".into())
    }

    fn snapshot(&self, id: SourceSnapshotId) -> Result<Option<SourceSnapshot>, String> {
        let raw: Option<(String, String, String, Option<String>, String, i64, i64, bool)> = self
            .connection
            .query_row(
                "SELECT id,workspace_id,immutable_revision,dirty_content_digest,manifest_hash,created_ms,index_version,index_complete FROM source_snapshots WHERE id=?1",
                [id.to_hex()],
                |row| {
                    Ok((
                        row.get(0)?,
                        row.get(1)?,
                        row.get(2)?,
                        row.get(3)?,
                        row.get(4)?,
                        row.get(5)?,
                        row.get(6)?,
                        row.get(7)?,
                    ))
                },
            )
            .optional()
            .map_err(|_| "cannot read source snapshot metadata")?;
        raw.map(|raw| {
            Ok(SourceSnapshot {
                id: SourceSnapshotId::from_hex(&raw.0)?,
                workspace_id: SourceWorkspaceId::from_hex(&raw.1)?,
                immutable_revision: {
                    bounded(&raw.2, "stored source immutable revision", MAX_PATH_BYTES)?;
                    raw.2
                },
                dirty_content_digest: raw
                    .3
                    .as_deref()
                    .map(|value| canonical_digest(value, "stored source dirty digest"))
                    .transpose()?,
                manifest_hash: canonical_digest(&raw.4, "stored source manifest hash")?,
                created_unix_ms: non_negative(raw.5, "source snapshot timestamp")?,
                index_version: non_negative(raw.6, "source index version")?,
                index_complete: raw.7,
            })
        })
        .transpose()
    }

    fn files(&self, snapshot_id: SourceSnapshotId) -> Result<Vec<SourceFile>, String> {
        let mut statement = self
            .connection
            .prepare("SELECT relative_path,language,content_hash,size_bytes FROM source_files WHERE snapshot_id=?1 ORDER BY relative_path LIMIT 100001")
            .map_err(|_| "cannot prepare source file listing")?;
        let values = statement
            .query_map([snapshot_id.to_hex()], |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                    row.get::<_, i64>(3)?,
                ))
            })
            .map_err(|_| "cannot query source files")?;
        let mut files = Vec::new();
        for value in values {
            let raw = value.map_err(|_| "invalid source file row")?;
            source_path(&raw.0)?;
            if files.len() == MAX_FILES {
                return Err("stored source files exceed the supported bound".into());
            }
            files.push(SourceFile {
                snapshot_id,
                relative_path: raw.0,
                language: parse_language(&raw.1)?,
                content_hash: canonical_digest(&raw.2, "stored source content hash")?,
                size_bytes: non_negative(raw.3, "source file size")?,
            });
        }
        Ok(files)
    }

    fn symbols(&self, snapshot_id: SourceSnapshotId) -> Result<Vec<SourceSymbol>, String> {
        let mut statement = self
            .connection
            .prepare("SELECT relative_path,kind,qualified_name,signature,start_line,end_line FROM source_symbols WHERE snapshot_id=?1 ORDER BY relative_path,start_line LIMIT 1000001")
            .map_err(|_| "cannot prepare source symbol listing")?;
        let values = statement
            .query_map([snapshot_id.to_hex()], |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                    row.get::<_, Option<String>>(3)?,
                    row.get::<_, i64>(4)?,
                    row.get::<_, i64>(5)?,
                ))
            })
            .map_err(|_| "cannot query source symbols")?;
        let mut symbols = Vec::new();
        for value in values {
            let raw = value.map_err(|_| "invalid source symbol row")?;
            source_path(&raw.0)?;
            required_text(&raw.2, "stored source symbol qualified name", 4096)?;
            if symbols.len() == MAX_SYMBOLS {
                return Err("stored source symbols exceed the supported bound".into());
            }
            let start_line = non_negative(raw.4, "source symbol start line")?;
            let end_line = non_negative(raw.5, "source symbol end line")?;
            if start_line == 0 || end_line < start_line {
                return Err("stored source symbol line range is invalid".into());
            }
            symbols.push(SourceSymbol {
                snapshot_id,
                relative_path: raw.0,
                kind: parse_symbol_kind(&raw.1)?,
                qualified_name: raw.2,
                signature: raw.3,
                start_line: start_line as usize,
                end_line: end_line as usize,
            });
        }
        Ok(symbols)
    }

    fn save_candidates(&mut self, candidates: &[ResolutionCandidate]) -> Result<(), String> {
        for candidate in candidates {
            if candidate
                .location
                .range
                .as_ref()
                .is_some_and(|range| range.start_line == 0 || range.end_line < range.start_line)
            {
                return Err("resolution candidate line range is invalid".into());
            }
            source_path(&candidate.location.relative_path)?;
            if candidate.reasons.len() > MAX_REASONS {
                return Err("resolution candidate has too many reasons".into());
            }
            for reason in &candidate.reasons {
                bounded(reason, "resolution candidate reason", MAX_REASON_BYTES)?;
            }
            sql_integer(
                candidate.index_version,
                "resolution candidate index version",
            )?;
        }
        let transaction = self
            .connection
            .transaction_with_behavior(TransactionBehavior::Immediate)
            .map_err(|_| "cannot begin resolution candidate transaction")?;
        {
            let mut statement = transaction
                .prepare("INSERT OR REPLACE INTO source_candidates(id,evidence_id,workspace_id,snapshot_id,relative_path,start_line,end_line,content_hash,confidence,reasons,index_version,index_complete) VALUES(?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12)")
                .map_err(|_| "cannot prepare resolution candidate insertion")?;
            for candidate in candidates {
                let reasons = json::encode(&aps_json::Value::Array(
                    candidate
                        .reasons
                        .iter()
                        .map(|reason| aps_json::Value::String(reason.clone()))
                        .collect(),
                ))?;
                let range = candidate.location.range;
                statement
                    .execute(params![
                        candidate.id.to_hex(),
                        candidate.evidence_id.to_hex(),
                        candidate.location.workspace_id.to_hex(),
                        candidate.location.snapshot_id.to_hex(),
                        candidate.location.relative_path,
                        range.map(|range| range.start_line as i64),
                        range.map(|range| range.end_line as i64),
                        candidate.location.content_hash.to_hex(),
                        confidence_name(candidate.confidence),
                        reasons,
                        sql_integer(
                            candidate.index_version,
                            "resolution candidate index version"
                        )?,
                        candidate.index_complete,
                    ])
                    .map_err(|_| "cannot persist resolution candidate; snapshot must exist")?;
            }
        }
        transaction
            .commit()
            .map_err(|_| "cannot commit resolution candidate metadata".into())
    }

    fn candidate(&self, id: ResolutionCandidateId) -> Result<Option<ResolutionCandidate>, String> {
        let raw: Option<(
            String,
            String,
            String,
            String,
            Option<i64>,
            Option<i64>,
            String,
            String,
            String,
            i64,
            bool,
        )> = self
            .connection
            .query_row(
                "SELECT evidence_id,workspace_id,snapshot_id,relative_path,start_line,end_line,content_hash,confidence,reasons,index_version,index_complete FROM source_candidates WHERE id=?1",
                [id.to_hex()],
                |row| {
                    Ok((
                        row.get(0)?,
                        row.get(1)?,
                        row.get(2)?,
                        row.get(3)?,
                        row.get(4)?,
                        row.get(5)?,
                        row.get(6)?,
                        row.get(7)?,
                        row.get(8)?,
                        row.get(9)?,
                        row.get(10)?,
                    ))
                },
            )
            .optional()
            .map_err(|_| "cannot read resolution candidate metadata")?;
        let Some(raw) = raw else {
            return Ok(None);
        };
        source_path(&raw.3)?;
        let range = match (raw.4, raw.5) {
            (Some(start_line), Some(end_line)) => {
                let start = non_negative(start_line, "resolution candidate start line")?;
                let end = non_negative(end_line, "resolution candidate end line")?;
                if start == 0 || end < start {
                    return Err("stored resolution candidate line range is invalid".into());
                }
                Some(aps_source::SourceRange {
                    start_line: start as usize,
                    end_line: end as usize,
                })
            }
            (None, None) => None,
            _ => return Err("stored resolution candidate line range is incomplete".into()),
        };
        let reasons_value = json::decode(&raw.8)?;
        let reasons_array = reasons_value
            .as_array()
            .ok_or_else(|| "stored resolution candidate reasons must be a JSON array".to_owned())?;
        if reasons_array.len() > MAX_REASONS {
            return Err("stored resolution candidate has too many reasons".into());
        }
        let mut reasons = Vec::with_capacity(reasons_array.len());
        for value in reasons_array {
            let reason = value
                .as_str()
                .ok_or_else(|| "stored resolution candidate reason must be a string".to_owned())?;
            bounded(
                reason,
                "stored resolution candidate reason",
                MAX_REASON_BYTES,
            )?;
            reasons.push(reason.to_owned());
        }
        Ok(Some(ResolutionCandidate {
            id,
            evidence_id: PerformanceEvidenceId::from_hex(&raw.0)?,
            location: aps_source::SourceLocation {
                workspace_id: SourceWorkspaceId::from_hex(&raw.1)?,
                snapshot_id: SourceSnapshotId::from_hex(&raw.2)?,
                relative_path: raw.3,
                range,
                content_hash: canonical_digest(&raw.6, "stored candidate content hash")?,
            },
            confidence: parse_confidence(&raw.7)?,
            reasons,
            index_version: non_negative(raw.9, "resolution candidate index version")?,
            index_complete: raw.10,
        }))
    }
}

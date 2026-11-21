//! Rust-desktop-only SQLite metadata. No credential/source/blob persistence API.
mod json;
mod model;
mod read_only;
mod schema;
mod sources;
use aps_contracts::{
    ArtifactId, CaptureArtifact, ProfilerSession, ProfilerSessionId, SourceDigest,
};
pub use model::*;
use rusqlite::{Connection, OpenFlags, OptionalExtension, TransactionBehavior, params};
use std::path::{Path, PathBuf};
use std::time::Duration;

pub struct Storage {
    connection: Connection,
    path: PathBuf,
    read_only_fingerprint: Option<std::fs::Metadata>,
}
impl Storage {
    /// Caller supplies the independent application metadata directory. Parent
    /// directory must already exist; this never discovers Compose databases.
    pub fn open(path: impl AsRef<Path>) -> Result<Self, String> {
        let path = path.as_ref();
        if !path.is_absolute() {
            return Err("metadata database path must be explicitly absolute".into());
        }
        if path.file_name().and_then(|name| name.to_str()) != Some(DATABASE_FILE_NAME) {
            return Err(
                "Rust metadata requires its independent aps-metadata.sqlite3 filename".into(),
            );
        }
        let parent = path
            .parent()
            .ok_or("metadata database requires an explicit parent directory")?;
        if !parent.is_dir() {
            return Err("metadata parent directory must already exist".into());
        }
        let mut wal = path.as_os_str().to_os_string();
        wal.push("-wal");
        let mut shm = path.as_os_str().to_os_string();
        shm.push("-shm");
        for candidate in [path.to_path_buf(), PathBuf::from(wal), PathBuf::from(shm)] {
            match std::fs::symlink_metadata(candidate) {
                Ok(metadata) if metadata.file_type().is_symlink() || !metadata.is_file() => {
                    return Err(
                        "metadata database or sidecar is not a regular non-symlink file".into(),
                    );
                }
                Err(error) if error.kind() != std::io::ErrorKind::NotFound => {
                    return Err("cannot inspect metadata database path".into());
                }
                _ => {}
            }
        }
        if !path.exists() {
            let mut options = std::fs::OpenOptions::new();
            options.write(true).create_new(true);
            #[cfg(unix)]
            {
                use std::os::unix::fs::OpenOptionsExt;
                options.mode(0o600);
            }
            match options.open(path) {
                Ok(_) => {}
                Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => {}
                Err(_) => return Err("cannot create private metadata database file".into()),
            }
        }
        let mut connection = Connection::open_with_flags(
            path,
            OpenFlags::SQLITE_OPEN_READ_WRITE
                | OpenFlags::SQLITE_OPEN_CREATE
                | OpenFlags::SQLITE_OPEN_NO_MUTEX,
        )
        .map_err(|_| "cannot open Rust metadata database")?;
        connection
            .set_limit(rusqlite::limits::Limit::SQLITE_LIMIT_LENGTH, 1024 * 1024)
            .map_err(|_| "cannot limit SQLite row/text/blob length")?;
        connection
            .busy_timeout(Duration::from_secs(5))
            .map_err(|_| "cannot configure metadata busy timeout")?;
        connection
            .execute_batch("PRAGMA foreign_keys=ON; PRAGMA trusted_schema=OFF;")
            .map_err(|_| "cannot configure metadata safety pragmas")?;
        schema::initialize(&mut connection)?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o600))
                .map_err(|_| "cannot restrict metadata database permissions")?;
        }

        let journal: String = connection
            .query_row("PRAGMA journal_mode=WAL", [], |row| row.get(0))
            .map_err(|_| "cannot enable metadata WAL mode")?;
        if !journal.eq_ignore_ascii_case("wal") {
            return Err("metadata filesystem does not support required WAL mode".into());
        }
        connection.execute_batch("PRAGMA synchronous=FULL; PRAGMA wal_autocheckpoint=1000; PRAGMA journal_size_limit=8388608; PRAGMA secure_delete=ON;").map_err(|_|"cannot configure metadata durability policy")?;
        Ok(Self {
            connection,
            path: path.to_path_buf(),
            read_only_fingerprint: None,
        })
    }
    pub fn path(&self) -> &Path {
        &self.path
    }
    pub fn schema_version(&self) -> Result<i64, String> {
        self.connection
            .query_row("PRAGMA user_version", [], |row| row.get(0))
            .map_err(|_| "cannot read metadata schema version".into())
    }
    pub fn put_artifact(
        &mut self,
        artifact: &CaptureArtifact,
        created_unix_ms: u64,
    ) -> Result<(), String> {
        artifact.validate()?;
        let created = model::timestamp(created_unix_ms)?;
        let length = i64::try_from(artifact.byte_length)
            .map_err(|_| "artifact length exceeds SQLite integer range")?;
        let metadata = json::encode(&json::artifact(artifact))?;
        let transaction = self
            .connection
            .transaction_with_behavior(TransactionBehavior::Immediate)
            .map_err(|_| "cannot begin artifact metadata transaction")?;
        let existing: Option<String> = transaction
            .query_row(
                "SELECT metadata_json FROM artifacts WHERE id=?1",
                [artifact.id.as_str()],
                |row| row.get(0),
            )
            .optional()
            .map_err(|_| "cannot inspect existing artifact metadata")?;
        if let Some(existing) = existing {
            if existing != metadata {
                return Err(
                    "artifact identity is immutable; conflicting content or provenance rejected"
                        .into(),
                );
            }
            return Ok(());
        }
        transaction.execute("INSERT INTO artifacts(id,source_digest,byte_length,media_type,created_ms,metadata_json)VALUES(?1,?2,?3,?4,?5,?6)",params![artifact.id.as_str(),artifact.source_digest.to_hex(),length,artifact.media_type,created,metadata]).map_err(|_|"cannot insert immutable artifact metadata")?;
        transaction
            .commit()
            .map_err(|_| "cannot commit artifact metadata".into())
    }
    pub fn artifact(&self, id: &ArtifactId) -> Result<Option<StoredArtifact>, String> {
        let raw=self.connection.query_row("SELECT id,source_digest,byte_length,media_type,created_ms,metadata_json FROM artifacts WHERE id=?1",[id.as_str()],artifact_row).optional().map_err(|_|"cannot read artifact metadata")?;
        raw.map(decode_artifact).transpose()
    }
    pub fn list_artifacts(&self, page: PageRequest) -> Result<Page<StoredArtifact>, String> {
        let read = self
            .connection
            .unchecked_transaction()
            .map_err(|_| "cannot begin metadata read snapshot")?;
        page.validate()?;
        let total = count(&read, "SELECT count(*) FROM artifacts")?;
        let mut statement=read.prepare("SELECT id,source_digest,byte_length,media_type,created_ms,metadata_json FROM artifacts ORDER BY created_ms DESC,id LIMIT ?1 OFFSET ?2").map_err(|_|"cannot prepare artifact page")?;
        let results = statement
            .query_map(params![page.limit as i64, page.offset as i64], artifact_row)
            .map_err(|_| "cannot query artifact page")?;
        let mut rows = Vec::new();
        let mut metadata_bytes = 0usize;
        for row in results {
            let raw = row.map_err(|_| "cannot decode artifact row")?;
            metadata_bytes = metadata_bytes
                .checked_add(raw.5.len())
                .ok_or("artifact page byte overflow")?;
            if metadata_bytes > 8 * 1024 * 1024 {
                return Err(
                    "artifact page exceeds 8 MiB metadata budget; request a smaller page".into(),
                );
            }
            rows.push(decode_artifact(raw)?);
        }
        Ok(Page {
            total,
            offset: page.offset,
            rows,
        })
    }
    pub fn create_session(
        &mut self,
        session: &ProfilerSession,
        label: &str,
        now_unix_ms: u64,
    ) -> Result<(), String> {
        session.validate()?;
        aps_contracts::validate_text(label, "session label", 256)?;
        let now = model::timestamp(now_unix_ms)?;
        let process = session
            .process
            .as_ref()
            .map(|process| json::encode(&json::process(process)))
            .transpose()?;
        let transaction = self
            .connection
            .transaction_with_behavior(TransactionBehavior::Immediate)
            .map_err(|_| "cannot begin session metadata transaction")?;
        transaction.execute("INSERT INTO profiler_sessions(id,label,device_id,process_json,generation,created_ms,last_opened_ms)VALUES(?1,?2,?3,?4,0,?5,?5)",params![session.id.as_str(),label,session.device.as_ref().map(|id|id.as_str()),process,now]).map_err(|_|"cannot create session metadata; identity may already exist")?;
        for (position, artifact) in session.artifacts.iter().enumerate() {
            transaction.execute("INSERT INTO session_artifacts(session_id,artifact_id,position)VALUES(?1,?2,?3)",params![session.id.as_str(),artifact.as_str(),position as i64]).map_err(|_|"cannot bind session artifact; no partial session was committed")?;
        }
        transaction
            .commit()
            .map_err(|_| "cannot commit session metadata".into())
    }
    pub fn session(&self, id: &ProfilerSessionId) -> Result<Option<StoredSession>, String> {
        let read = self
            .connection
            .unchecked_transaction()
            .map_err(|_| "cannot begin metadata read snapshot")?;
        let raw:Option<(String,String,Option<String>,Option<String>,i64,i64,i64)>=read.query_row("SELECT id,label,device_id,process_json,generation,created_ms,last_opened_ms FROM profiler_sessions WHERE id=?1",[id.as_str()],|row|Ok((row.get(0)?,row.get(1)?,row.get(2)?,row.get(3)?,row.get(4)?,row.get(5)?,row.get(6)?))).optional().map_err(|_|"cannot read profiler session")?;
        let Some(raw) = raw else {
            return Ok(None);
        };
        aps_contracts::validate_text(&raw.1, "stored session label", 256)?;
        let mut statement=read.prepare("SELECT artifact_id FROM session_artifacts WHERE session_id=?1 ORDER BY position LIMIT 10001").map_err(|_|"cannot prepare session artifact references")?;
        let values = statement
            .query_map([id.as_str()], |row| row.get::<_, String>(0))
            .map_err(|_| "cannot read session artifact references")?;
        let mut artifact_ids = Vec::new();
        for value in values {
            artifact_ids.push(ArtifactId::new(
                value.map_err(|_| "invalid session artifact reference")?,
            )?);
            if artifact_ids.len() > 10_000 {
                return Err("stored session exceeds artifact reference budget".into());
            }
        }
        Ok(Some(StoredSession {
            id: ProfilerSessionId::new(raw.0)?,
            label: raw.1,
            artifact_ids,
            device_id: raw.2,
            process_identity: raw.3.as_deref().map(json::decode).transpose()?,
            generation: nonnegative(raw.4)?,
            created_unix_ms: nonnegative(raw.5)?,
            last_opened_unix_ms: nonnegative(raw.6)?,
        }))
    }
    pub fn open_session(
        &mut self,
        id: &ProfilerSessionId,
        expected_generation: u64,
        now_unix_ms: u64,
    ) -> Result<StoredSession, String> {
        let expected = model::generation(expected_generation)?;
        let now = model::timestamp(now_unix_ms)?;
        let transaction = self
            .connection
            .transaction_with_behavior(TransactionBehavior::Immediate)
            .map_err(|_| "cannot begin recent-session transaction")?;
        let changed=transaction.execute("UPDATE profiler_sessions SET last_opened_ms=max(last_opened_ms,?1) WHERE id=?2 AND generation=?3",params![now,id.as_str(),expected]).map_err(|_|"cannot update recent session")?;
        if changed != 1 {
            return Err("profiler session is missing or query generation is stale".into());
        }
        // The same connection retains the write lock until the returned snapshot
        // is fetched, preventing another writer changing generation in between.
        let raw=transaction.query_row("SELECT label,device_id,process_json,generation,created_ms,last_opened_ms FROM profiler_sessions WHERE id=?1",[id.as_str()],|row|Ok((row.get::<_,String>(0)?,row.get::<_,Option<String>>(1)?,row.get::<_,Option<String>>(2)?,row.get::<_,i64>(3)?,row.get::<_,i64>(4)?,row.get::<_,i64>(5)?))).map_err(|_|"cannot read opened session")?;
        let mut statement=transaction.prepare("SELECT artifact_id FROM session_artifacts WHERE session_id=?1 ORDER BY position LIMIT 10001").map_err(|_|"cannot prepare opened session references")?;
        let values = statement
            .query_map([id.as_str()], |row| row.get::<_, String>(0))
            .map_err(|_| "cannot read opened session references")?;
        let mut artifacts = Vec::new();
        for value in values {
            artifacts.push(ArtifactId::new(
                value.map_err(|_| "invalid opened artifact identity")?,
            )?);
            if artifacts.len() > 10_000 {
                return Err("session reference budget exceeded".into());
            }
        }
        drop(statement);
        aps_contracts::validate_text(&raw.0, "stored session label", 256)?;
        if let Some(device) = &raw.1 {
            aps_contracts::DeviceTargetId::new(device.clone())?;
        }
        let result = StoredSession {
            id: id.clone(),
            label: raw.0,
            device_id: raw.1,
            process_identity: raw.2.as_deref().map(json::decode).transpose()?,
            generation: nonnegative(raw.3)?,
            created_unix_ms: nonnegative(raw.4)?,
            last_opened_unix_ms: nonnegative(raw.5)?,
            artifact_ids: artifacts,
        };
        transaction
            .commit()
            .map_err(|_| "cannot commit recent-session metadata")?;
        Ok(result)
    }
    pub fn list_sessions(&self, page: PageRequest) -> Result<Page<SessionSummary>, String> {
        let read = self
            .connection
            .unchecked_transaction()
            .map_err(|_| "cannot begin metadata read snapshot")?;
        page.validate()?;
        let total = count(&read, "SELECT count(*) FROM profiler_sessions")?;
        let mut statement=read.prepare("SELECT s.id,s.label,s.device_id,s.generation,s.created_ms,s.last_opened_ms,(SELECT count(*) FROM session_artifacts a WHERE a.session_id=s.id) FROM profiler_sessions s ORDER BY s.last_opened_ms DESC,s.id LIMIT ?1 OFFSET ?2").map_err(|_|"cannot prepare recent sessions")?;
        let values = statement
            .query_map(params![page.limit as i64, page.offset as i64], |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, Option<String>>(2)?,
                    row.get::<_, i64>(3)?,
                    row.get::<_, i64>(4)?,
                    row.get::<_, i64>(5)?,
                    row.get::<_, i64>(6)?,
                ))
            })
            .map_err(|_| "cannot query recent sessions")?;
        let mut rows = Vec::new();
        for value in values {
            let raw = value.map_err(|_| "invalid recent-session row")?;
            aps_contracts::validate_text(&raw.1, "session label", 256)?;
            rows.push(SessionSummary {
                id: ProfilerSessionId::new(raw.0)?,
                label: raw.1,
                device_id: raw.2,
                generation: nonnegative(raw.3)?,
                created_unix_ms: nonnegative(raw.4)?,
                last_opened_unix_ms: nonnegative(raw.5)?,
                artifact_count: usize::try_from(nonnegative(raw.6)?)
                    .map_err(|_| "artifact count exceeds platform")?,
            });
        }
        Ok(Page {
            total,
            offset: page.offset,
            rows,
        })
    }
    pub fn advance_generation(
        &mut self,
        id: &ProfilerSessionId,
        expected: u64,
        next: u64,
    ) -> Result<(), String> {
        if next <= expected {
            return Err("query generation must strictly advance".into());
        }
        let next = model::generation(next)?;
        let expected = model::generation(expected)?;
        let changed = self
            .connection
            .execute(
                "UPDATE profiler_sessions SET generation=?1 WHERE id=?2 AND generation=?3",
                params![next, id.as_str(), expected],
            )
            .map_err(|_| "cannot advance session query generation")?;
        if changed != 1 {
            return Err("session generation compare-and-swap rejected stale state".into());
        }
        Ok(())
    }
    pub fn validate_binding(&self, binding: &QueryBinding) -> Result<(), String> {
        let generation = model::generation(binding.generation)?;
        let matches:i64=self.connection.query_row("SELECT count(*) FROM profiler_sessions s JOIN session_artifacts sa ON sa.session_id=s.id JOIN artifacts a ON a.id=sa.artifact_id WHERE s.id=?1 AND s.generation=?2 AND a.source_digest=?3",params![binding.session_id.as_str(),generation,binding.source_digest.to_hex()],|row|row.get(0)).map_err(|_|"cannot verify session query binding")?;
        if matches == 0 {
            Err("stale generation or source digest is not bound to session".into())
        } else {
            Ok(())
        }
    }
    /// Deletes session/link metadata only. Artifacts, original source files,
    /// capture directories and source-workspace files are never removed.
    pub fn delete_session_metadata(
        &mut self,
        id: &ProfilerSessionId,
        expected_generation: u64,
    ) -> Result<(), String> {
        let changed = self
            .connection
            .execute(
                "DELETE FROM profiler_sessions WHERE id=?1 AND generation=?2",
                params![id.as_str(), model::generation(expected_generation)?],
            )
            .map_err(|_| "cannot delete session metadata")?;
        if changed != 1 {
            return Err("session metadata is missing or generation is stale".into());
        }
        Ok(())
    }
}
type ArtifactRow = (String, String, i64, String, i64, String);
fn artifact_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<ArtifactRow> {
    Ok((
        row.get(0)?,
        row.get(1)?,
        row.get(2)?,
        row.get(3)?,
        row.get(4)?,
        row.get(5)?,
    ))
}
fn decode_artifact(raw: ArtifactRow) -> Result<StoredArtifact, String> {
    let id = ArtifactId::new(raw.0)?;
    let source_digest = SourceDigest::from_hex(&raw.1)?;
    let metadata = json::decode(&raw.5)?;
    if metadata
        .get("schemaVersion")
        .and_then(aps_json::Value::as_u64)
        != Some(1)
        || metadata.get("id").and_then(aps_json::Value::as_str) != Some(id.as_str())
        || metadata
            .get("sourceDigest")
            .and_then(aps_json::Value::as_str)
            != Some(raw.1.as_str())
    {
        return Err("stored artifact document identity does not match indexed metadata".into());
    }
    if metadata.get("byteLength").and_then(aps_json::Value::as_u64) != Some(nonnegative(raw.2)?)
        || metadata.get("mediaType").and_then(aps_json::Value::as_str) != Some(raw.3.as_str())
    {
        return Err("stored artifact size/media metadata does not match indexed columns".into());
    }
    aps_contracts::validate_text(&raw.3, "stored media type", 256)?;
    Ok(StoredArtifact {
        id,
        source_digest,
        byte_length: nonnegative(raw.2)?,
        media_type: raw.3,
        created_unix_ms: nonnegative(raw.4)?,
        metadata,
    })
}
fn nonnegative(value: i64) -> Result<u64, String> {
    u64::try_from(value).map_err(|_| "stored metadata integer is negative".into())
}
fn count(connection: &Connection, query: &str) -> Result<usize, String> {
    let count: i64 = connection
        .query_row(query, [], |row| row.get(0))
        .map_err(|_| "cannot count metadata rows")?;
    usize::try_from(count).map_err(|_| "stored metadata count exceeds platform bounds".into())
}

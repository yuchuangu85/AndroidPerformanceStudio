//! Passive metadata discovery: never initializes a database or touches sidecars.
use crate::{APPLICATION_ID, DATABASE_FILE_NAME, SCHEMA_VERSION, Storage};
use rusqlite::{Connection, OpenFlags, OptionalExtension};
use std::{
    path::{Path, PathBuf},
    time::Duration,
};

impl Storage {
    /// Opens only a checkpointed, existing Rust metadata database, without
    /// migration, permission changes, file creation, or WAL/SHM writes.
    ///
    /// SQLite's ordinary READ_ONLY mode may create a WAL shared-memory sidecar.
    /// `immutable=1` avoids that, but ignores WAL records. Consequently this
    /// passive reader refuses a nonempty WAL instead of publishing stale data.
    /// The caller may retry after the writer closes/checkpoints the database.
    pub fn open_read_only(path: impl AsRef<Path>) -> Result<Self, String> {
        let path = path.as_ref();
        validate_path(path)?;
        let before = std::fs::symlink_metadata(path)
            .map_err(|_| "cannot inspect existing metadata database")?;
        if before.file_type().is_symlink() || !before.is_file() {
            return Err("metadata database must be an existing regular non-symlink file".into());
        }
        validate_sidecars(path)?;
        // SQLite's NOFOLLOW flag rejects any path whose ancestor is a symbolic
        // link (macOS /var and /tmp are symlinked), so resolve ancestors before
        // opening. The database itself was already rejected above if it is a
        // symlink, and the identity check below still uses the caller's path.
        let canonical = std::fs::canonicalize(path)
            .map_err(|_| "cannot resolve existing metadata database path")?;
        let uri = immutable_uri(&canonical)?;
        let connection = Connection::open_with_flags(
            uri,
            OpenFlags::SQLITE_OPEN_READ_ONLY
                | OpenFlags::SQLITE_OPEN_URI
                | OpenFlags::SQLITE_OPEN_NO_MUTEX
                | OpenFlags::SQLITE_OPEN_NOFOLLOW,
        )
        .map_err(|_| "cannot open existing Rust metadata read-only")?;
        connection
            .set_limit(rusqlite::limits::Limit::SQLITE_LIMIT_LENGTH, 1024 * 1024)
            .map_err(|_| "cannot limit read-only metadata row length")?;
        connection
            .busy_timeout(Duration::from_secs(1))
            .map_err(|_| "cannot configure read-only metadata timeout")?;
        connection
            .execute_batch(
                "PRAGMA query_only=ON; PRAGMA trusted_schema=OFF; PRAGMA foreign_keys=ON;",
            )
            .map_err(|_| "cannot configure read-only metadata safeguards")?;
        let application: i64 = connection
            .query_row("PRAGMA application_id", [], |row| row.get(0))
            .map_err(|_| "cannot read existing metadata identity")?;
        let version: i64 = connection
            .query_row("PRAGMA user_version", [], |row| row.get(0))
            .map_err(|_| "cannot read existing metadata schema version")?;
        if application != APPLICATION_ID || version != SCHEMA_VERSION {
            return Err("existing metadata identity/schema is incompatible; passive reader cannot migrate it".into());
        }
        for (table, query) in [
            (
                "artifacts",
                "SELECT id,source_digest,byte_length,media_type,created_ms,metadata_json FROM artifacts LIMIT 0",
            ),
            (
                "profiler_sessions",
                "SELECT id,label,device_id,process_json,generation,created_ms,last_opened_ms FROM profiler_sessions LIMIT 0",
            ),
            (
                "session_artifacts",
                "SELECT session_id,artifact_id,position FROM session_artifacts LIMIT 0",
            ),
            (
                "source_workspaces",
                "SELECT id,display_name,provider_kind,local_root,github_owner,github_repository,provider_ref,credential_key,aosp_project,active_snapshot_id,phase,progress,message,allow_ai_source_upload FROM source_workspaces LIMIT 0",
            ),
            (
                "source_snapshots",
                "SELECT id,workspace_id,immutable_revision,dirty_content_digest,manifest_hash,created_ms,index_version,index_complete FROM source_snapshots LIMIT 0",
            ),
            (
                "source_files",
                "SELECT snapshot_id,relative_path,language,content_hash,size_bytes FROM source_files LIMIT 0",
            ),
            (
                "source_symbols",
                "SELECT snapshot_id,relative_path,kind,qualified_name,signature,start_line,end_line FROM source_symbols LIMIT 0",
            ),
            (
                "source_candidates",
                "SELECT id,evidence_id,workspace_id,snapshot_id,relative_path,start_line,end_line,content_hash,confidence,reasons,index_version,index_complete FROM source_candidates LIMIT 0",
            ),
        ] {
            let kind: String = connection
                .query_row(
                    "SELECT type FROM sqlite_master WHERE name=?1",
                    [table],
                    |row| row.get(0),
                )
                .map_err(|_| "existing metadata schema is incomplete")?;
            if kind != "table" {
                return Err(
                    "existing metadata schema contains an unexpected non-table object".into(),
                );
            }
            connection
                .prepare(query)
                .map_err(|_| "existing metadata schema columns are incompatible")?;
        }
        validate_query_indexes(&connection)?;
        validate_sidecars(path)?;
        let after = std::fs::symlink_metadata(path)
            .map_err(|_| "metadata database disappeared during passive open")?;
        if !same_file(&before, &after) {
            return Err("metadata database changed during passive open; retry the read".into());
        }
        Ok(Self {
            connection,
            path: path.to_path_buf(),
            read_only_fingerprint: Some(after),
        })
    }
    /// Bounded recent-session discovery without a full-table COUNT. The query
    /// requires the known ordering index and caps each artifact-reference count
    /// at two (one is valid for Home CPU items; two is already an error).
    pub fn list_recent_sessions_read_only(
        &self,
        page: crate::PageRequest,
    ) -> Result<Vec<crate::SessionSummary>, String> {
        page.validate()?;
        self.validate_read_only_snapshot()?;
        let mut statement = self.connection.prepare("SELECT s.id,s.label,s.device_id,s.generation,s.created_ms,s.last_opened_ms,(SELECT count(*) FROM (SELECT 1 FROM session_artifacts a INDEXED BY sqlite_autoindex_session_artifacts_1 WHERE a.session_id=s.id LIMIT 2)) FROM profiler_sessions s INDEXED BY sessions_recent ORDER BY s.last_opened_ms DESC,s.id LIMIT ?1 OFFSET ?2")
            .map_err(|_| "cannot prepare bounded passive recent sessions")?;
        let values = statement
            .query_map(
                rusqlite::params![page.limit as i64, page.offset as i64],
                |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, String>(1)?,
                        row.get::<_, Option<String>>(2)?,
                        row.get::<_, i64>(3)?,
                        row.get::<_, i64>(4)?,
                        row.get::<_, i64>(5)?,
                        row.get::<_, i64>(6)?,
                    ))
                },
            )
            .map_err(|_| "cannot query bounded passive recent sessions")?;
        let mut rows = Vec::with_capacity(page.limit);
        for value in values {
            let raw = value.map_err(|_| "invalid passive recent-session row")?;
            aps_contracts::validate_text(&raw.1, "session label", 256)?;
            if let Some(device) = &raw.2 {
                aps_contracts::DeviceTargetId::new(device.clone())?;
            }
            rows.push(crate::SessionSummary {
                id: aps_contracts::ProfilerSessionId::new(raw.0)?,
                label: raw.1,
                device_id: raw.2,
                generation: crate::nonnegative(raw.3)?,
                created_unix_ms: crate::nonnegative(raw.4)?,
                last_opened_unix_ms: crate::nonnegative(raw.5)?,
                artifact_count: usize::try_from(crate::nonnegative(raw.6)?)
                    .map_err(|_| "passive artifact count exceeds platform")?,
            });
        }
        self.validate_read_only_snapshot()?;
        Ok(rows)
    }

    /// Recheck before publishing a passive batch. An active writer invalidates
    /// the immutable read rather than letting Home publish an unchecked view.
    pub fn validate_read_only_snapshot(&self) -> Result<(), String> {
        let before = self
            .read_only_fingerprint
            .as_ref()
            .ok_or("snapshot validation requires a passive read-only connection")?;
        validate_sidecars(&self.path)?;
        let after = std::fs::symlink_metadata(&self.path)
            .map_err(|_| "metadata database disappeared during passive discovery")?;
        if !same_file(before, &after) {
            return Err("metadata changed during passive discovery; retry the read".into());
        }
        Ok(())
    }
}

/// Checking column existence alone is insufficient: same-column counterfeit
/// tables without their primary/ordering indexes can turn LIMITed projections
/// and single-ID lookups into unbounded scans. Require the actual schema keys
/// (including collation, order, uniqueness, origin and non-partial coverage).
fn validate_query_indexes(connection: &Connection) -> Result<(), String> {
    for (table, name, origin, unique, keys) in [
        (
            "artifacts",
            "sqlite_autoindex_artifacts_1",
            "pk",
            true,
            &[("id", 0)][..],
        ),
        (
            "artifacts",
            "artifacts_digest",
            "c",
            false,
            &[("source_digest", 0)][..],
        ),
        (
            "profiler_sessions",
            "sqlite_autoindex_profiler_sessions_1",
            "pk",
            true,
            &[("id", 0)][..],
        ),
        (
            "profiler_sessions",
            "sessions_recent",
            "c",
            false,
            &[("last_opened_ms", 1), ("id", 0)][..],
        ),
        (
            "session_artifacts",
            "sqlite_autoindex_session_artifacts_1",
            "pk",
            true,
            &[("session_id", 0), ("artifact_id", 0)][..],
        ),
        (
            "session_artifacts",
            "sqlite_autoindex_session_artifacts_2",
            "u",
            true,
            &[("session_id", 0), ("position", 0)][..],
        ),
        (
            "source_workspaces",
            "sqlite_autoindex_source_workspaces_1",
            "pk",
            true,
            &[("id", 0)][..],
        ),
        (
            "source_snapshots",
            "sqlite_autoindex_source_snapshots_1",
            "pk",
            true,
            &[("id", 0)][..],
        ),
        (
            "source_snapshots",
            "source_snapshots_workspace",
            "c",
            false,
            &[("workspace_id", 0)][..],
        ),
        (
            "source_files",
            "sqlite_autoindex_source_files_1",
            "pk",
            true,
            &[("snapshot_id", 0), ("relative_path", 0)][..],
        ),
        (
            "source_symbols",
            "source_symbols_name",
            "c",
            false,
            &[("snapshot_id", 0), ("qualified_name", 0)][..],
        ),
        (
            "source_candidates",
            "sqlite_autoindex_source_candidates_1",
            "pk",
            true,
            &[("id", 0)][..],
        ),
        (
            "source_candidates",
            "source_candidates_snapshot",
            "c",
            false,
            &[("snapshot_id", 0)][..],
        ),
    ] {
        validate_index(connection, table, name, origin, unique, keys)?;
    }
    Ok(())
}

fn validate_index(
    connection: &Connection,
    table: &str,
    name: &str,
    origin: &str,
    unique: bool,
    keys: &[(&str, i64)],
) -> Result<(), String> {
    // Table-valued pragmas bind names as data, so even schema-controlled index
    // names never become SQL. LIMIT budgets bound introspection results too.
    let index: Option<(i64, String, i64)> = connection
        .query_row(
            "SELECT \"unique\",origin,partial FROM pragma_index_list(?1) WHERE name=?2 LIMIT 1",
            rusqlite::params![table, name],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        )
        .optional()
        .map_err(|_| "cannot inspect required metadata index")?;
    if index != Some((i64::from(unique), origin.to_owned(), 0)) {
        return Err(format!(
            "required metadata key/index is missing or incompatible: {table}/{name}"
        ));
    }
    let mut statement = connection
        .prepare(
            "SELECT name,\"desc\",coll,\"key\" FROM pragma_index_xinfo(?1) ORDER BY seqno LIMIT 33",
        )
        .map_err(|_| "cannot inspect metadata index keys")?;
    let values = statement
        .query_map([name], |row| {
            Ok((
                row.get::<_, Option<String>>(0)?,
                row.get::<_, i64>(1)?,
                row.get::<_, String>(2)?,
                row.get::<_, i64>(3)?,
            ))
        })
        .map_err(|_| "cannot query metadata index keys")?;
    let mut actual = Vec::new();
    let mut inspected = 0;
    for value in values {
        inspected += 1;
        if inspected > 32 {
            return Err("metadata index exceeds supported key budget".into());
        }
        let (column, descending, collation, key) =
            value.map_err(|_| "invalid metadata index key")?;
        if key == 1 {
            actual.push((
                column.ok_or("expression metadata index keys are incompatible")?,
                descending,
                collation,
            ));
        }
    }
    let expected: Vec<_> = keys
        .iter()
        .map(|(column, descending)| ((*column).to_owned(), *descending, "BINARY".to_owned()))
        .collect();
    if actual != expected {
        return Err(format!(
            "required metadata index has incompatible ordering or collation: {table}/{name}"
        ));
    }
    Ok(())
}

fn validate_path(path: &Path) -> Result<(), String> {
    if !path.is_absolute()
        || path.file_name().and_then(|name| name.to_str()) != Some(DATABASE_FILE_NAME)
    {
        return Err(
            "read-only metadata requires an absolute Rust-owned aps-metadata.sqlite3 path".into(),
        );
    }
    let parent = path
        .parent()
        .ok_or("read-only metadata requires a parent directory")?;
    let metadata = std::fs::symlink_metadata(parent)
        .map_err(|_| "cannot inspect metadata parent directory")?;
    if metadata.file_type().is_symlink() || !metadata.is_dir() {
        return Err("read-only metadata parent must be a non-symlink directory".into());
    }
    Ok(())
}

fn validate_sidecars(path: &Path) -> Result<(), String> {
    for suffix in ["-wal", "-shm", "-journal"] {
        let mut name = path.as_os_str().to_os_string();
        name.push(suffix);
        match std::fs::symlink_metadata(PathBuf::from(name)) {
            Ok(metadata) if metadata.file_type().is_symlink() || !metadata.is_file() => {
                return Err("metadata sidecar is not a regular non-symlink file".into());
            }
            Ok(metadata) if suffix != "-shm" && metadata.len() != 0 => {
                return Err("metadata writer has pending journal data; passive read requires a checkpointed database".into());
            }
            Ok(_) => {}
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(_) => return Err("cannot inspect metadata sidecar".into()),
        }
    }
    Ok(())
}

fn immutable_uri(path: &Path) -> Result<String, String> {
    use std::fmt::Write;
    let text = path
        .to_str()
        .ok_or("read-only SQLite path must be valid UTF-8")?;
    let mut uri = String::from("file:");
    for byte in text.bytes() {
        if byte.is_ascii_alphanumeric() || b"/-._~:".contains(&byte) {
            uri.push(char::from(byte));
        } else {
            write!(uri, "%{byte:02X}").map_err(|_| "cannot encode SQLite read-only path")?;
        }
    }
    uri.push_str("?mode=ro&immutable=1");
    Ok(uri)
}

fn same_file(before: &std::fs::Metadata, after: &std::fs::Metadata) -> bool {
    if after.file_type().is_symlink()
        || !after.is_file()
        || before.len() != after.len()
        || before.modified().is_err()
        || after.modified().is_err()
        || before.modified().ok() != after.modified().ok()
    {
        return false;
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        before.dev() == after.dev()
            && before.ino() == after.ino()
            && before.ctime() == after.ctime()
            && before.ctime_nsec() == after.ctime_nsec()
    }
    #[cfg(not(unix))]
    {
        true
    }
}

#[cfg(test)]
mod tests {
    use crate::{DATABASE_FILE_NAME, Storage};
    use std::{
        path::PathBuf,
        sync::atomic::{AtomicU64, Ordering},
    };
    static NEXT: AtomicU64 = AtomicU64::new(0);
    struct Fixture(PathBuf);
    impl Fixture {
        fn new() -> Self {
            let root = std::env::temp_dir().join(format!(
                "aps-read-only-{}-{}-%?#",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            std::fs::create_dir(&root).unwrap();
            Self(root)
        }
        fn path(&self) -> PathBuf {
            self.0.join(DATABASE_FILE_NAME)
        }
        fn names(&self) -> Vec<std::ffi::OsString> {
            let mut names: Vec<_> = std::fs::read_dir(&self.0)
                .unwrap()
                .map(|entry| entry.unwrap().file_name())
                .collect();
            names.sort();
            names
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn existing_database_is_read_without_creation_mutation_or_write_access() {
        let fixture = Fixture::new();
        drop(Storage::open(fixture.path()).unwrap());
        let before = std::fs::read(fixture.path()).unwrap();
        let metadata = std::fs::metadata(fixture.path()).unwrap();
        let names = fixture.names();
        let mut storage = Storage::open_read_only(fixture.path()).unwrap();
        assert_eq!(storage.schema_version().unwrap(), crate::SCHEMA_VERSION);
        assert!(
            storage
                .list_recent_sessions_read_only(crate::PageRequest::default())
                .unwrap()
                .is_empty()
        );
        assert!(
            storage
                .advance_generation(
                    &aps_contracts::ProfilerSessionId::new("session:readonly").unwrap(),
                    0,
                    1
                )
                .is_err()
        );
        drop(storage);
        assert_eq!(std::fs::read(fixture.path()).unwrap(), before);
        assert_eq!(fixture.names(), names);
        assert_eq!(
            std::fs::metadata(fixture.path())
                .unwrap()
                .modified()
                .unwrap(),
            metadata.modified().unwrap()
        );
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            assert_eq!(
                std::fs::metadata(fixture.path())
                    .unwrap()
                    .permissions()
                    .mode(),
                metadata.permissions().mode()
            );
        }
    }

    #[test]
    fn missing_database_is_not_created() {
        let fixture = Fixture::new();
        assert!(Storage::open_read_only(fixture.path()).is_err());
        assert!(fixture.names().is_empty());
    }

    #[test]
    fn foreign_or_old_schema_is_never_migrated() {
        for (application, version) in [
            (0, 0),
            (crate::APPLICATION_ID, 1),
            (crate::APPLICATION_ID + 1, crate::SCHEMA_VERSION),
        ] {
            let fixture = Fixture::new();
            let connection = rusqlite::Connection::open(fixture.path()).unwrap();
            connection
                .pragma_update(None, "application_id", application)
                .unwrap();
            connection
                .pragma_update(None, "user_version", version)
                .unwrap();
            drop(connection);
            let before = std::fs::read(fixture.path()).unwrap();
            assert!(Storage::open_read_only(fixture.path()).is_err());
            assert_eq!(std::fs::read(fixture.path()).unwrap(), before);
        }
    }

    #[test]
    fn pending_wal_is_not_ignored_or_changed() {
        let fixture = Fixture::new();
        let storage = Storage::open(fixture.path()).unwrap();
        storage
            .connection
            .execute_batch(
                "CREATE TABLE readonly_test(value INTEGER); INSERT INTO readonly_test VALUES(1)",
            )
            .unwrap();
        let wal = fixture.0.join(format!("{DATABASE_FILE_NAME}-wal"));
        let before = std::fs::read(&wal).unwrap();
        assert!(!before.is_empty());
        assert!(Storage::open_read_only(fixture.path()).is_err());
        assert_eq!(std::fs::read(&wal).unwrap(), before);
    }

    #[test]
    fn passive_discovery_rejects_missing_order_index_instead_of_sorting_unbounded_rows() {
        let fixture = Fixture::new();
        let storage = Storage::open(fixture.path()).unwrap();
        storage
            .connection
            .execute_batch("DROP INDEX sessions_recent")
            .unwrap();
        drop(storage);
        assert!(Storage::open_read_only(fixture.path()).is_err());
    }

    #[test]
    fn same_column_tables_without_required_primary_keys_are_rejected_without_mutation() {
        for replacement in [
            "DROP TABLE session_artifacts; CREATE TABLE session_artifacts(session_id TEXT,artifact_id TEXT,position INTEGER)",
            "DROP TABLE session_artifacts; CREATE TABLE session_artifacts(session_id TEXT,artifact_id TEXT,position INTEGER,PRIMARY KEY(artifact_id,session_id),UNIQUE(session_id,position))",
            "DROP TABLE session_artifacts; CREATE TABLE session_artifacts(session_id TEXT,artifact_id TEXT,position INTEGER,PRIMARY KEY(session_id,artifact_id))",
            "DROP TABLE artifacts; CREATE TABLE artifacts(id TEXT,source_digest TEXT,byte_length INTEGER,media_type TEXT,created_ms INTEGER,metadata_json TEXT); CREATE INDEX artifacts_digest ON artifacts(source_digest)",
            "DROP TABLE profiler_sessions; CREATE TABLE profiler_sessions(id TEXT,label TEXT,device_id TEXT,process_json TEXT,generation INTEGER,created_ms INTEGER,last_opened_ms INTEGER); CREATE INDEX sessions_recent ON profiler_sessions(last_opened_ms DESC,id)",
        ] {
            let fixture = Fixture::new();
            let storage = Storage::open(fixture.path()).unwrap();
            storage.connection.execute_batch(replacement).unwrap();
            drop(storage);
            let before = std::fs::read(fixture.path()).unwrap();
            assert!(
                Storage::open_read_only(fixture.path()).is_err(),
                "accepted counterfeit schema: {replacement}"
            );
            assert_eq!(std::fs::read(fixture.path()).unwrap(), before);
        }
    }

    #[test]
    fn partial_and_wrong_collation_indexes_are_rejected() {
        for replacement in [
            "DROP INDEX artifacts_digest; CREATE INDEX artifacts_digest ON artifacts(source_digest) WHERE byte_length>0",
            "DROP INDEX sessions_recent; CREATE INDEX sessions_recent ON profiler_sessions(last_opened_ms DESC,id COLLATE NOCASE)",
        ] {
            let fixture = Fixture::new();
            let storage = Storage::open(fixture.path()).unwrap();
            storage.connection.execute_batch(replacement).unwrap();
            drop(storage);
            assert!(
                Storage::open_read_only(fixture.path()).is_err(),
                "accepted incompatible index: {replacement}"
            );
        }
    }

    #[cfg(unix)]
    #[test]
    fn symlink_database_and_sidecars_are_rejected() {
        use std::os::unix::fs::symlink;
        let fixture = Fixture::new();
        drop(Storage::open(fixture.path()).unwrap());
        let real = fixture.0.join("real.sqlite3");
        std::fs::rename(fixture.path(), &real).unwrap();
        symlink(&real, fixture.path()).unwrap();
        assert!(Storage::open_read_only(fixture.path()).is_err());
        std::fs::remove_file(fixture.path()).unwrap();
        std::fs::rename(&real, fixture.path()).unwrap();
        symlink(
            fixture.path(),
            fixture.0.join(format!("{DATABASE_FILE_NAME}-shm")),
        )
        .unwrap();
        assert!(Storage::open_read_only(fixture.path()).is_err());
    }
}

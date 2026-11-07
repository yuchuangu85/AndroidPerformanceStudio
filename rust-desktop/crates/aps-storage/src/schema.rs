use crate::{APPLICATION_ID, SCHEMA_VERSION};
use rusqlite::{Connection, TransactionBehavior};
pub(crate) fn initialize(connection: &mut Connection) -> Result<(), String> {
    let transaction = connection
        .transaction_with_behavior(TransactionBehavior::Immediate)
        .map_err(|_| "cannot lock metadata database for migration")?;
    let application: i64 = transaction
        .query_row("PRAGMA application_id", [], |row| row.get(0))
        .map_err(|_| "cannot read metadata database identity")?;
    let version: i64 = transaction
        .query_row("PRAGMA user_version", [], |row| row.get(0))
        .map_err(|_| "cannot read metadata schema version")?;
    if version > SCHEMA_VERSION || version < 0 {
        return Err(
            "metadata schema is newer than this application; database left unchanged".into(),
        );
    }
    if application != APPLICATION_ID {
        let count: i64 = transaction
            .query_row(
                "SELECT count(*) FROM sqlite_master WHERE name NOT LIKE 'sqlite_%'",
                [],
                |row| row.get(0),
            )
            .map_err(|_| "cannot inspect metadata schema")?;
        if application != 0 || version != 0 || count != 0 {
            return Err("selected database is not the Rust desktop metadata database".into());
        }
    }
    if version < 1 {
        transaction.execute_batch("CREATE TABLE artifacts(id TEXT PRIMARY KEY,source_digest TEXT NOT NULL,byte_length INTEGER NOT NULL CHECK(byte_length>=0),media_type TEXT NOT NULL,created_ms INTEGER NOT NULL CHECK(created_ms>=0),metadata_json TEXT NOT NULL CHECK(length(CAST(metadata_json AS BLOB))<=131072));
        CREATE INDEX artifacts_digest ON artifacts(source_digest);
        CREATE TABLE profiler_sessions(id TEXT PRIMARY KEY,label TEXT NOT NULL,device_id TEXT,process_json TEXT,generation INTEGER NOT NULL DEFAULT 0 CHECK(generation>=0),created_ms INTEGER NOT NULL CHECK(created_ms>=0),last_opened_ms INTEGER NOT NULL CHECK(last_opened_ms>=0));
        CREATE TABLE session_artifacts(session_id TEXT NOT NULL REFERENCES profiler_sessions(id) ON DELETE CASCADE,artifact_id TEXT NOT NULL REFERENCES artifacts(id) ON DELETE RESTRICT,position INTEGER NOT NULL CHECK(position>=0),PRIMARY KEY(session_id,artifact_id),UNIQUE(session_id,position));
        PRAGMA user_version=1;").map_err(|_|"metadata schema migration v1 failed; transaction rolled back")?;
    }
    if version < 2 {
        transaction.execute_batch("CREATE INDEX sessions_recent ON profiler_sessions(last_opened_ms DESC,id);
        CREATE TABLE source_workspaces(workspace_id TEXT PRIMARY KEY,display_name TEXT NOT NULL,local_root TEXT NOT NULL,created_ms INTEGER NOT NULL CHECK(created_ms>=0));
        CREATE TABLE source_snapshots(snapshot_id TEXT PRIMARY KEY,workspace_id TEXT NOT NULL REFERENCES source_workspaces(workspace_id) ON DELETE RESTRICT,manifest_digest TEXT NOT NULL,file_count INTEGER NOT NULL CHECK(file_count>=0),indexed_bytes INTEGER NOT NULL CHECK(indexed_bytes>=0),index_version INTEGER NOT NULL CHECK(index_version>0),manifest_complete INTEGER NOT NULL CHECK(manifest_complete IN(0,1)),created_ms INTEGER NOT NULL CHECK(created_ms>=0));
        CREATE INDEX snapshots_workspace ON source_snapshots(workspace_id,created_ms DESC,snapshot_id);
        PRAGMA user_version=2;").map_err(|_|"metadata schema migration v2 failed; transaction rolled back")?;
    }
    if version < 3 {
        // The Rust-owned source tables were replaced by the Compose-parity
        // workspace/snapshot/file/symbol/candidate model. Only these source
        // tables are recreated; artifacts and sessions are untouched.
        transaction
            .execute_batch(
                "DROP TABLE IF EXISTS source_snapshots;
        DROP TABLE IF EXISTS source_workspaces;
        CREATE TABLE source_workspaces(
          id TEXT PRIMARY KEY,
          display_name TEXT NOT NULL,
          provider_kind TEXT NOT NULL,
          local_root TEXT,
          github_owner TEXT,
          github_repository TEXT,
          provider_ref TEXT,
          credential_key TEXT,
          aosp_project TEXT,
          active_snapshot_id TEXT,
          phase TEXT NOT NULL,
          progress REAL NOT NULL CHECK(progress>=0 AND progress<=1),
          message TEXT,
          allow_ai_source_upload INTEGER NOT NULL CHECK(allow_ai_source_upload IN(0,1))
        );
        CREATE TABLE source_snapshots(
          id TEXT PRIMARY KEY,
          workspace_id TEXT NOT NULL REFERENCES source_workspaces(id) ON DELETE CASCADE,
          immutable_revision TEXT NOT NULL,
          dirty_content_digest TEXT,
          manifest_hash TEXT NOT NULL,
          created_ms INTEGER NOT NULL CHECK(created_ms>=0),
          index_version INTEGER NOT NULL CHECK(index_version>=0),
          index_complete INTEGER NOT NULL CHECK(index_complete IN(0,1))
        );
        CREATE INDEX source_snapshots_workspace ON source_snapshots(workspace_id);
        CREATE TABLE source_files(
          snapshot_id TEXT NOT NULL REFERENCES source_snapshots(id) ON DELETE CASCADE,
          relative_path TEXT NOT NULL,
          language TEXT NOT NULL,
          content_hash TEXT NOT NULL,
          size_bytes INTEGER NOT NULL CHECK(size_bytes>=0),
          PRIMARY KEY(snapshot_id,relative_path)
        );
        CREATE TABLE source_symbols(
          snapshot_id TEXT NOT NULL REFERENCES source_snapshots(id) ON DELETE CASCADE,
          relative_path TEXT NOT NULL,
          kind TEXT NOT NULL,
          qualified_name TEXT NOT NULL,
          signature TEXT,
          start_line INTEGER NOT NULL CHECK(start_line>=1),
          end_line INTEGER NOT NULL CHECK(end_line>=start_line)
        );
        CREATE INDEX source_symbols_name ON source_symbols(snapshot_id,qualified_name);
        CREATE TABLE source_candidates(
          id TEXT PRIMARY KEY,
          evidence_id TEXT NOT NULL,
          workspace_id TEXT NOT NULL,
          snapshot_id TEXT NOT NULL REFERENCES source_snapshots(id) ON DELETE CASCADE,
          relative_path TEXT NOT NULL,
          start_line INTEGER,
          end_line INTEGER,
          content_hash TEXT NOT NULL,
          confidence TEXT NOT NULL,
          reasons TEXT NOT NULL,
          index_version INTEGER NOT NULL CHECK(index_version>=0),
          index_complete INTEGER NOT NULL CHECK(index_complete IN(0,1))
        );
        CREATE INDEX source_candidates_snapshot ON source_candidates(snapshot_id);
        PRAGMA user_version=3;",
            )
            .map_err(|_| "metadata schema migration v3 failed; transaction rolled back")?;
    }
    if version < 4 {
        // Bounded source lookups: file-local symbol fetch and qualified kind
        // lookup. No table layout changes, so existing indexed snapshots stay
        // valid; only indexes are added.
        transaction
            .execute_batch(
                "CREATE INDEX IF NOT EXISTS source_symbols_file ON source_symbols(snapshot_id,relative_path,start_line);
        CREATE INDEX IF NOT EXISTS source_symbols_kind_name ON source_symbols(snapshot_id,kind,qualified_name);
        PRAGMA user_version=4;",
            )
            .map_err(|_| "metadata schema migration v4 failed; transaction rolled back")?;
    }
    if version < 5 {
        // Persisted short-name columns power the method/native resolver without
        // scanning every symbol row. Existing rows are backfilled before the
        // indexes are built, so the migration touches only the source table.
        transaction
            .execute_batch(
                "ALTER TABLE source_symbols ADD COLUMN dot_name TEXT;
        ALTER TABLE source_symbols ADD COLUMN native_name TEXT;",
            )
            .map_err(|_| "metadata schema migration v5 failed; transaction rolled back")?;
        let mut last_rowid = 0i64;
        loop {
            let rows: Vec<(i64, String)> = {
                let mut statement = transaction
                    .prepare(
                        "SELECT rowid,qualified_name FROM source_symbols WHERE dot_name IS NULL AND rowid>?1 ORDER BY rowid LIMIT 5000",
                    )
                    .map_err(|_| "cannot prepare source symbol backfill")?;
                statement
                    .query_map([last_rowid], |row| Ok((row.get(0)?, row.get(1)?)))
                    .map_err(|_| "cannot read source symbols for backfill")?
                    .collect::<Result<Vec<_>, _>>()
                    .map_err(|_| "cannot decode source symbols for backfill")?
            };
            let Some(last) = rows.last() else { break };
            last_rowid = last.0;
            let mut update = transaction
                .prepare("UPDATE source_symbols SET dot_name=?1,native_name=?2 WHERE rowid=?3")
                .map_err(|_| "cannot prepare source symbol short-name update")?;
            for (rowid, qualified_name) in rows {
                update
                    .execute(rusqlite::params![
                        crate::sources::dot_short_name(&qualified_name),
                        crate::sources::native_short_name(&qualified_name),
                        rowid
                    ])
                    .map_err(|_| "cannot store source symbol short name")?;
            }
        }
        transaction
            .execute_batch(
                "CREATE INDEX IF NOT EXISTS source_symbols_dot_name ON source_symbols(snapshot_id,dot_name);
        CREATE INDEX IF NOT EXISTS source_symbols_native_name ON source_symbols(snapshot_id,native_name);
        PRAGMA user_version=5;",
            )
            .map_err(|_| "metadata schema migration v5 failed; transaction rolled back")?;
    }
    for query in [
        "SELECT id,source_digest,byte_length,media_type,created_ms,metadata_json FROM artifacts LIMIT 0",
        "SELECT id,label,device_id,process_json,generation,created_ms,last_opened_ms FROM profiler_sessions LIMIT 0",
        "SELECT session_id,artifact_id,position FROM session_artifacts LIMIT 0",
        "SELECT id,display_name,provider_kind,local_root,github_owner,github_repository,provider_ref,credential_key,aosp_project,active_snapshot_id,phase,progress,message,allow_ai_source_upload FROM source_workspaces LIMIT 0",
        "SELECT id,workspace_id,immutable_revision,dirty_content_digest,manifest_hash,created_ms,index_version,index_complete FROM source_snapshots LIMIT 0",
        "SELECT snapshot_id,relative_path,language,content_hash,size_bytes FROM source_files LIMIT 0",
        "SELECT snapshot_id,relative_path,kind,qualified_name,signature,start_line,end_line FROM source_symbols LIMIT 0",
        "SELECT id,evidence_id,workspace_id,snapshot_id,relative_path,start_line,end_line,content_hash,confidence,reasons,index_version,index_complete FROM source_candidates LIMIT 0",
    ] {
        transaction.prepare(query).map_err(
            |_| "metadata database schema is incomplete or incompatible; migration rolled back",
        )?;
    }
    transaction
        .pragma_update(None, "application_id", APPLICATION_ID)
        .map_err(|_| "cannot bind metadata database identity")?;
    transaction
        .commit()
        .map_err(|_| "cannot commit metadata migration".to_owned())
}

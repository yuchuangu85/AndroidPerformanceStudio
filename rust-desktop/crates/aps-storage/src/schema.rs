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
    for query in [
        "SELECT id,source_digest,byte_length,media_type,created_ms,metadata_json FROM artifacts LIMIT 0",
        "SELECT id,label,device_id,process_json,generation,created_ms,last_opened_ms FROM profiler_sessions LIMIT 0",
        "SELECT session_id,artifact_id,position FROM session_artifacts LIMIT 0",
        "SELECT workspace_id,display_name,local_root,created_ms FROM source_workspaces LIMIT 0",
        "SELECT snapshot_id,workspace_id,manifest_digest,file_count,indexed_bytes,index_version,manifest_complete,created_ms FROM source_snapshots LIMIT 0",
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

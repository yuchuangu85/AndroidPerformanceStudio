use crate::{
    Page, PageRequest, SourceSnapshotMetadata, SourceWorkspaceMetadata, Storage, count, model,
};
use aps_contracts::SourceDigest;
use rusqlite::{OptionalExtension, TransactionBehavior, params};
impl Storage {
    /// Forget one registration and its snapshot metadata atomically. Source
    /// roots, source files, profiler sessions and artifacts are never touched.
    pub fn remove_source_workspace(&mut self, workspace_id: &str) -> Result<(), String> {
        model::digest_id(workspace_id)?;
        let transaction = self
            .connection
            .transaction_with_behavior(TransactionBehavior::Immediate)
            .map_err(|_| "cannot begin source workspace removal transaction")?;
        transaction
            .execute(
                "DELETE FROM source_snapshots WHERE workspace_id=?1",
                [workspace_id],
            )
            .map_err(|_| "cannot remove source snapshot metadata")?;
        transaction
            .execute(
                "DELETE FROM source_workspaces WHERE workspace_id=?1",
                [workspace_id],
            )
            .map_err(|_| "cannot remove source workspace metadata")?;
        transaction
            .commit()
            .map_err(|_| "cannot commit source workspace removal".into())
    }

    pub fn put_source_workspace(&mut self, value: &SourceWorkspaceMetadata) -> Result<(), String> {
        value.validate()?;
        let transaction = self
            .connection
            .transaction_with_behavior(TransactionBehavior::Immediate)
            .map_err(|_| "cannot begin source workspace metadata transaction")?;
        let existing:Option<(String,String,i64)>=transaction.query_row("SELECT display_name,local_root,created_ms FROM source_workspaces WHERE workspace_id=?1",[&value.workspace_id],|row|Ok((row.get(0)?,row.get(1)?,row.get(2)?))).optional().map_err(|_|"cannot inspect source workspace metadata")?;
        if let Some(existing) = existing {
            // Creation time belongs to the first registration. Re-opening the
            // same explicitly selected root must not rebind its identity merely
            // because this caller observed it at a later time.
            if existing.0 != value.display_name || existing.1 != value.local_root {
                return Err(
                    "source workspace identity is already bound to different metadata".into(),
                );
            }
            return Ok(());
        }
        transaction.execute("INSERT INTO source_workspaces(workspace_id,display_name,local_root,created_ms)VALUES(?1,?2,?3,?4)",params![value.workspace_id,value.display_name,value.local_root,model::timestamp(value.created_unix_ms)?]).map_err(|_|"cannot insert source workspace metadata")?;
        transaction
            .commit()
            .map_err(|_| "cannot commit source workspace metadata".into())
    }
    pub fn list_source_workspaces(
        &self,
        page: PageRequest,
    ) -> Result<Page<SourceWorkspaceMetadata>, String> {
        let read = self
            .connection
            .unchecked_transaction()
            .map_err(|_| "cannot begin source metadata read snapshot")?;
        page.validate()?;
        let total = count(&read, "SELECT count(*) FROM source_workspaces")?;
        let mut statement=read.prepare("SELECT workspace_id,display_name,local_root,created_ms FROM source_workspaces ORDER BY created_ms DESC,workspace_id LIMIT ?1 OFFSET ?2").map_err(|_|"cannot prepare source workspace page")?;
        let values = statement
            .query_map(params![page.limit as i64, page.offset as i64], |row| {
                Ok(SourceWorkspaceMetadata {
                    workspace_id: row.get(0)?,
                    display_name: row.get(1)?,
                    local_root: row.get(2)?,
                    created_unix_ms: row.get::<_, i64>(3)? as u64,
                })
            })
            .map_err(|_| "cannot read source workspace metadata")?;
        let mut rows = Vec::new();
        for value in values {
            let value = value.map_err(|_| "invalid source workspace metadata")?;
            value.validate()?;
            rows.push(value);
        }
        Ok(Page {
            total,
            offset: page.offset,
            rows,
        })
    }
    pub fn put_source_snapshot(&mut self, value: &SourceSnapshotMetadata) -> Result<(), String> {
        value.validate()?;
        let transaction = self
            .connection
            .transaction_with_behavior(TransactionBehavior::Immediate)
            .map_err(|_| "cannot begin source snapshot metadata transaction")?;
        let existing:Option<(String,String,i64,i64,i64,bool,i64)>=transaction.query_row("SELECT workspace_id,manifest_digest,file_count,indexed_bytes,index_version,manifest_complete,created_ms FROM source_snapshots WHERE snapshot_id=?1",[&value.snapshot_id],|row|Ok((row.get(0)?,row.get(1)?,row.get(2)?,row.get(3)?,row.get(4)?,row.get(5)?,row.get(6)?))).optional().map_err(|_|"cannot inspect source snapshot metadata")?;
        if let Some(existing) = existing {
            // A refresh may rediscover the same immutable manifest. Preserve
            // its first creation time, but reject altered structural metadata.
            if existing.0 != value.workspace_id
                || existing.1 != value.manifest_digest.to_hex()
                || existing.2 != value.file_count as i64
                || existing.3 != value.indexed_bytes as i64
                || existing.4 != i64::from(value.index_version)
                || existing.5 != value.manifest_complete
            {
                return Err("source snapshot identity is immutable and cannot be rebound".into());
            }
            return Ok(());
        }
        transaction.execute("INSERT INTO source_snapshots(snapshot_id,workspace_id,manifest_digest,file_count,indexed_bytes,index_version,manifest_complete,created_ms)VALUES(?1,?2,?3,?4,?5,?6,?7,?8)",params![value.snapshot_id,value.workspace_id,value.manifest_digest.to_hex(),value.file_count as i64,value.indexed_bytes as i64,i64::from(value.index_version),value.manifest_complete,model::timestamp(value.created_unix_ms)?]).map_err(|_|"cannot insert source snapshot; workspace must exist")?;
        transaction
            .commit()
            .map_err(|_| "cannot commit source snapshot metadata".into())
    }
    pub fn list_source_snapshots(
        &self,
        workspace_id: &str,
        page: PageRequest,
    ) -> Result<Page<SourceSnapshotMetadata>, String> {
        let read = self
            .connection
            .unchecked_transaction()
            .map_err(|_| "cannot begin source metadata read snapshot")?;
        model::digest_id(workspace_id)?;
        page.validate()?;
        let total: i64 = read
            .query_row(
                "SELECT count(*) FROM source_snapshots WHERE workspace_id=?1",
                [workspace_id],
                |row| row.get(0),
            )
            .map_err(|_| "cannot count source snapshots")?;
        let mut statement=read.prepare("SELECT snapshot_id,workspace_id,manifest_digest,file_count,indexed_bytes,index_version,manifest_complete,created_ms FROM source_snapshots WHERE workspace_id=?1 ORDER BY created_ms DESC,snapshot_id LIMIT ?2 OFFSET ?3").map_err(|_|"cannot prepare source snapshot page")?;
        let values = statement
            .query_map(
                params![workspace_id, page.limit as i64, page.offset as i64],
                |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, String>(1)?,
                        row.get::<_, String>(2)?,
                        row.get::<_, i64>(3)?,
                        row.get::<_, i64>(4)?,
                        row.get::<_, i64>(5)?,
                        row.get::<_, bool>(6)?,
                        row.get::<_, i64>(7)?,
                    ))
                },
            )
            .map_err(|_| "cannot read source snapshot page")?;
        let mut rows = Vec::new();
        for value in values {
            let raw = value.map_err(|_| "invalid source snapshot row")?;
            let value = SourceSnapshotMetadata {
                snapshot_id: raw.0,
                workspace_id: raw.1,
                manifest_digest: SourceDigest::from_hex(&raw.2)?,
                file_count: u64::try_from(raw.3).map_err(|_| "negative source file count")?,
                indexed_bytes: u64::try_from(raw.4).map_err(|_| "negative source byte count")?,
                index_version: u32::try_from(raw.5).map_err(|_| "invalid source index version")?,
                manifest_complete: raw.6,
                created_unix_ms: u64::try_from(raw.7)
                    .map_err(|_| "negative source snapshot timestamp")?,
            };
            value.validate()?;
            rows.push(value);
        }
        Ok(Page {
            total: usize::try_from(total).map_err(|_| "source snapshot count exceeds platform")?,
            offset: page.offset,
            rows,
        })
    }
}

#[cfg(test)]
mod removal_tests {
    use super::*;
    use crate::DATABASE_FILE_NAME;
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicUsize, Ordering};

    static NEXT: AtomicUsize = AtomicUsize::new(0);
    struct Fixture(PathBuf);
    impl Fixture {
        fn new() -> Self {
            let root = std::env::temp_dir().join(format!(
                "aps-source-removal-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            std::fs::create_dir(&root).unwrap();
            Self(root)
        }
        fn storage(&self) -> Storage {
            Storage::open(self.0.join(DATABASE_FILE_NAME)).unwrap()
        }
        fn register(&self, storage: &mut Storage, id: u8) -> String {
            let workspace_id = SourceDigest::from_bytes([id; 32]).to_hex();
            storage
                .put_source_workspace(&SourceWorkspaceMetadata {
                    workspace_id: workspace_id.clone(),
                    display_name: format!("workspace-{id}"),
                    local_root: self.0.to_str().unwrap().into(),
                    created_unix_ms: 1,
                })
                .unwrap();
            storage
                .put_source_snapshot(&SourceSnapshotMetadata {
                    snapshot_id: SourceDigest::from_bytes([id + 10; 32]).to_hex(),
                    workspace_id: workspace_id.clone(),
                    manifest_digest: SourceDigest::from_bytes([id + 10; 32]),
                    file_count: 1,
                    indexed_bytes: 5,
                    index_version: 1,
                    manifest_complete: true,
                    created_unix_ms: 1,
                })
                .unwrap();
            workspace_id
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn removal_is_scoped_idempotent_and_never_deletes_source_or_cpu_sessions() {
        let fixture = Fixture::new();
        let original = fixture.0.join("Main.kt");
        std::fs::write(&original, "class Main").unwrap();
        let mut storage = fixture.storage();
        let first = fixture.register(&mut storage, 1);
        let second = fixture.register(&mut storage, 2);
        storage.connection.execute("INSERT INTO profiler_sessions(id,label,generation,created_ms,last_opened_ms) VALUES('cpu-keep','CPU session',0,1,1)", []).unwrap();

        storage.remove_source_workspace(&first).unwrap();
        storage.remove_source_workspace(&first).unwrap();
        assert_eq!(
            storage.list_source_workspaces(PageRequest::default()).unwrap().rows[0].workspace_id,
            second
        );
        assert_eq!(
            storage.list_source_snapshots(&first, PageRequest::default()).unwrap().total,
            0
        );
        assert_eq!(
            storage.list_source_snapshots(&second, PageRequest::default()).unwrap().total,
            1
        );
        assert_eq!(storage.list_sessions(PageRequest::default()).unwrap().total, 1);
        assert_eq!(std::fs::read_to_string(original).unwrap(), "class Main");
    }

    #[test]
    fn invalid_id_is_rejected_and_failed_removal_rolls_back_snapshot_deletion() {
        let fixture = Fixture::new();
        let mut storage = fixture.storage();
        let id = fixture.register(&mut storage, 3);
        assert!(storage.remove_source_workspace("not-a-workspace-id").is_err());
        storage.connection.execute_batch("CREATE TRIGGER reject_source_removal BEFORE DELETE ON source_workspaces BEGIN SELECT RAISE(ABORT, 'reject test removal'); END;").unwrap();
        assert!(storage.remove_source_workspace(&id).is_err());
        assert_eq!(storage.list_source_workspaces(PageRequest::default()).unwrap().total, 1);
        assert_eq!(storage.list_source_snapshots(&id, PageRequest::default()).unwrap().total, 1);
    }
}

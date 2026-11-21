//! Rust-owned, explicit per-workspace source-upload policy. This document stores
//! only canonical workspace digests with `true`; it contains no roots or source.
//! Parsing/writing errors are fail-closed and never mutate the caller's policy.
use aps_contracts::SourceDigest;
use aps_json::{Limits, Map, Value};
use std::collections::BTreeSet;
use std::fs::{File, Metadata};
use std::io::Read;
use std::path::Path;

pub(super) const FILE_NAME: &str = "source-upload-permissions-v1.json";
const MAX_ENTRIES: usize = 1024;
const MAX_BYTES: u64 = 128 * 1024;

#[derive(Debug, Default, Clone, PartialEq, Eq)]
pub(super) struct SourcePermissions {
    allowed_workspaces: BTreeSet<String>,
}

impl SourcePermissions {
    pub(super) fn load(path: &Path) -> Result<Self, String> {
        let parent = checked_parent(path)?;
        let before = match checked_file(path)? {
            None => return Ok(Self::default()),
            Some(metadata) => metadata,
        };
        let mut file = File::open(path).map_err(|_| "cannot open source upload policy")?;
        let handle = file
            .metadata()
            .map_err(|_| "cannot inspect source upload policy handle")?;
        if !same_metadata(&before, &handle) {
            return Err("source upload policy changed before reading".into());
        }
        let mut bytes = Vec::new();
        file.by_ref()
            .take(MAX_BYTES + 1)
            .read_to_end(&mut bytes)
            .map_err(|_| "cannot read source upload policy")?;
        let after_handle = file
            .metadata()
            .map_err(|_| "cannot inspect source upload policy handle")?;
        let after = checked_file(path)?.ok_or("source upload policy disappeared while reading")?;
        let after_parent = checked_parent(path)?;
        if !same_metadata(&before, &after_handle)
            || !same_metadata(&before, &after)
            || !same_directory(&parent, &after_parent)
        {
            return Err("source upload policy changed while reading".into());
        }
        Self::decode(&bytes)
    }

    pub(super) fn allowed(&self, id: &str) -> bool {
        valid_id(id).is_ok() && self.allowed_workspaces.contains(id)
    }

    /// Returns a new draft. The caller publishes it only after `save` succeeds.
    pub(super) fn with_allowed(&self, id: &str, allowed: bool) -> Result<Self, String> {
        valid_id(id)?;
        let mut next = self.clone();
        if allowed {
            if next.allowed_workspaces.len() == MAX_ENTRIES && !next.allowed_workspaces.contains(id)
            {
                return Err("source upload policy exceeds its workspace budget".into());
            }
            next.allowed_workspaces.insert(id.to_owned());
        } else {
            next.allowed_workspaces.remove(id);
        }
        Ok(next)
    }

    pub(super) fn save(&self, path: &Path) -> Result<(), String> {
        // atomic_write otherwise creates missing directories. This policy layer
        // deliberately requires the caller's existing Rust-owned directory.
        let parent = checked_parent(path)?;
        checked_file(path)?;
        let bytes = self.encode()?;
        aps_platform::atomic_write(path, &bytes)
            .map_err(|_| "cannot atomically save source upload policy")?;
        let after_parent = checked_parent(path)?;
        if !same_directory(&parent, &after_parent) {
            return Err("source upload policy directory changed during save".into());
        }
        checked_file(path)?.ok_or("source upload policy disappeared after save")?;
        Ok(())
    }

    fn decode(bytes: &[u8]) -> Result<Self, String> {
        let root = aps_json::parse_slice(bytes, limits())
            .map_err(|_| "source upload policy is malformed or exceeds its bounds")?;
        let root = root
            .as_object()
            .ok_or("source upload policy must be an object")?;
        if root.len() != 2
            || root.get("version").and_then(Value::as_u64) != Some(1)
            || !root.contains_key("workspaces")
        {
            return Err("source upload policy version or fields are unsupported".into());
        }
        let workspaces = root["workspaces"]
            .as_object()
            .ok_or("source upload policy workspaces must be an object")?;
        if workspaces.len() > MAX_ENTRIES {
            return Err("source upload policy exceeds its workspace budget".into());
        }
        let mut allowed_workspaces = BTreeSet::new();
        for (id, value) in workspaces {
            valid_id(id)?;
            if value != &Value::Bool(true) {
                return Err("source upload policy records must be explicit true grants".into());
            }
            allowed_workspaces.insert(id.clone());
        }
        Ok(Self { allowed_workspaces })
    }

    fn encode(&self) -> Result<Vec<u8>, String> {
        if self.allowed_workspaces.len() > MAX_ENTRIES {
            return Err("source upload policy exceeds its workspace budget".into());
        }
        let mut workspaces = Map::new();
        for id in &self.allowed_workspaces {
            valid_id(id)?;
            workspaces.insert(id.clone(), Value::Bool(true));
        }
        let mut root = Map::new();
        root.insert("version".into(), Value::from(1u64));
        root.insert("workspaces".into(), Value::Object(workspaces));
        aps_json::to_vec(&Value::Object(root), limits())
            .map_err(|_| "cannot encode bounded source upload policy".into())
    }
}

fn limits() -> Limits {
    Limits {
        max_bytes: MAX_BYTES,
        max_depth: 3,
        max_nodes: MAX_ENTRIES + 4,
        max_string_bytes: 64,
        max_collection_items: MAX_ENTRIES,
    }
}

fn valid_id(id: &str) -> Result<(), String> {
    let digest = SourceDigest::from_hex(id)?;
    if digest.to_hex() != id {
        return Err("source upload policy workspace IDs must be canonical lowercase SHA256".into());
    }
    Ok(())
}

fn checked_parent(path: &Path) -> Result<Metadata, String> {
    if !path.is_absolute() || path.file_name().and_then(|name| name.to_str()) != Some(FILE_NAME) {
        return Err("source upload policy requires its Rust-owned absolute filename".into());
    }
    let parent = path
        .parent()
        .ok_or("source upload policy has no parent directory")?;
    let metadata = std::fs::symlink_metadata(parent)
        .map_err(|_| "source upload policy parent must already exist")?;
    if metadata.file_type().is_symlink() || !metadata.is_dir() {
        return Err("source upload policy parent must be a non-symlink directory".into());
    }
    Ok(metadata)
}

fn checked_file(path: &Path) -> Result<Option<Metadata>, String> {
    match std::fs::symlink_metadata(path) {
        Ok(metadata) if metadata.file_type().is_symlink() || !metadata.is_file() => {
            Err("source upload policy must be a regular non-symlink file".into())
        }
        Ok(metadata) if metadata.len() > MAX_BYTES => {
            Err("source upload policy exceeds its byte budget".into())
        }
        Ok(metadata) => Ok(Some(metadata)),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(_) => Err("cannot inspect source upload policy".into()),
    }
}

fn same_metadata(left: &Metadata, right: &Metadata) -> bool {
    left.is_file()
        && right.is_file()
        && left.len() == right.len()
        && left.modified().ok() == right.modified().ok()
        && same_directory(left, right)
}

fn same_directory(left: &Metadata, right: &Metadata) -> bool {
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        left.dev() == right.dev() && left.ino() == right.ino()
    }
    #[cfg(not(unix))]
    {
        left.is_dir() == right.is_dir() && left.created().ok() == right.created().ok()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicUsize, Ordering};
    static NEXT: AtomicUsize = AtomicUsize::new(0);

    struct Fixture(PathBuf);
    impl Fixture {
        fn new() -> Self {
            let path = std::env::temp_dir().join(format!(
                "aps-source-permissions-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            std::fs::create_dir(&path).unwrap();
            Self(path)
        }
        fn path(&self) -> PathBuf {
            self.0.join(FILE_NAME)
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn missing_policy_defaults_blocked_and_explicit_grants_roundtrip_without_source_data() {
        let fixture = Fixture::new();
        let id = SourceDigest::from_bytes([1; 32]).to_hex();
        let blocked = SourcePermissions::load(&fixture.path()).unwrap();
        assert!(!blocked.allowed(&id));
        let allowed = blocked.with_allowed(&id, true).unwrap();
        assert!(!blocked.allowed(&id));
        allowed.save(&fixture.path()).unwrap();
        assert_eq!(SourcePermissions::load(&fixture.path()).unwrap(), allowed);
        let bytes = std::fs::read_to_string(fixture.path()).unwrap();
        assert!(bytes.contains(&id));
        assert!(!bytes.contains("root"));
        let revoked = allowed.with_allowed(&id, false).unwrap();
        assert!(!revoked.allowed(&id));
        revoked.save(&fixture.path()).unwrap();
        assert_eq!(
            SourcePermissions::load(&fixture.path()).unwrap(),
            SourcePermissions::default()
        );
    }

    #[test]
    fn malformed_duplicate_unknown_or_nonexplicit_grants_fail_closed() {
        let id = SourceDigest::from_bytes([1; 32]).to_hex();
        for bytes in [
            br#"[]"#.to_vec(),
            br#"{"version":2,"workspaces":{}}"#.to_vec(),
            br#"{"version":1,"workspaces":{},"extra":true}"#.to_vec(),
            br#"{"version":1,"version":1,"workspaces":{}}"#.to_vec(),
            br#"{"version":1,"workspaces":{"invalid":true}}"#.to_vec(),
            format!(r#"{{"version":1,"workspaces":{{"{id}":false}}}}"#).into_bytes(),
            format!(r#"{{"version":1,"workspaces":{{"{id}":"true"}}}}"#).into_bytes(),
            format!(r#"{{"version":1,"workspaces":{{"{id}":true,"{id}":true}}}}"#).into_bytes(),
        ] {
            assert!(SourcePermissions::decode(&bytes).is_err());
        }
        assert!(!SourcePermissions::default().allowed("invalid"));
        assert!(
            SourcePermissions::default()
                .with_allowed("invalid", true)
                .is_err()
        );
        assert!(
            SourcePermissions::default()
                .with_allowed(&"AB".repeat(32), true)
                .is_err()
        );
    }

    #[test]
    fn byte_and_collection_limits_are_enforced() {
        assert!(SourcePermissions::decode(&vec![b' '; MAX_BYTES as usize + 1]).is_err());
        let mut permissions = SourcePermissions::default();
        for index in 0..MAX_ENTRIES {
            let mut digest = [0; 32];
            digest[..8].copy_from_slice(&(index as u64).to_be_bytes());
            permissions = permissions
                .with_allowed(&SourceDigest::from_bytes(digest).to_hex(), true)
                .unwrap();
        }
        assert!(
            permissions
                .with_allowed(&SourceDigest::from_bytes([255; 32]).to_hex(), true)
                .is_err()
        );
        assert!(permissions.encode().unwrap().len() <= MAX_BYTES as usize);
    }

    #[test]
    fn failed_save_preserves_previous_immutable_policy_and_requires_existing_parent() {
        let fixture = Fixture::new();
        let id = SourceDigest::from_bytes([2; 32]).to_hex();
        let blocked = SourcePermissions::default();
        let draft = blocked.with_allowed(&id, true).unwrap();
        assert!(
            draft
                .save(&fixture.0.join("missing").join(FILE_NAME))
                .is_err()
        );
        assert!(!fixture.0.join("missing").exists());
        assert!(!blocked.allowed(&id));
        assert!(draft.allowed(&id));
        std::fs::create_dir(fixture.path()).unwrap();
        assert!(draft.save(&fixture.path()).is_err());
        assert!(SourcePermissions::load(&fixture.path()).is_err());
    }

    #[test]
    fn oversized_files_and_foreign_filenames_are_not_overwritten() {
        let fixture = Fixture::new();
        let oversized = vec![b' '; MAX_BYTES as usize + 1];
        std::fs::write(fixture.path(), &oversized).unwrap();
        assert!(SourcePermissions::load(&fixture.path()).is_err());
        assert!(SourcePermissions::default().save(&fixture.path()).is_err());
        assert_eq!(std::fs::read(fixture.path()).unwrap(), oversized);
        let foreign = fixture.0.join("compose-settings.json");
        assert!(SourcePermissions::default().save(&foreign).is_err());
        assert!(!foreign.exists());
    }

    #[cfg(unix)]
    #[test]
    fn symlink_file_and_parent_are_rejected_without_mutating_the_target() {
        use std::os::unix::fs::symlink;
        let fixture = Fixture::new();
        let target = fixture.0.join("keep.json");
        std::fs::write(&target, "keep").unwrap();
        symlink(&target, fixture.path()).unwrap();
        assert!(SourcePermissions::load(&fixture.path()).is_err());
        assert!(SourcePermissions::default().save(&fixture.path()).is_err());
        assert_eq!(std::fs::read_to_string(&target).unwrap(), "keep");
        let linked_parent = fixture.0.join("linked-parent");
        symlink(&fixture.0, &linked_parent).unwrap();
        assert!(SourcePermissions::load(&linked_parent.join(FILE_NAME)).is_err());
        assert!(
            SourcePermissions::default()
                .save(&linked_parent.join(FILE_NAME))
                .is_err()
        );
    }
}

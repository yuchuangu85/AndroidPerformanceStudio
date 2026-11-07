use crate::digest_bytes;
use aps_contracts::SourceDigest;
use std::path::PathBuf;

/// Content-addressed immutable source cache: <root>/<hash[0..2]>/<hash[2..]>.
/// Writes are atomic same-directory renames; reads verify the stored hash.
pub struct ContentAddressedSourceCache {
    root: PathBuf,
}

impl ContentAddressedSourceCache {
    pub fn new(root: PathBuf) -> Self {
        Self { root }
    }

    pub fn put(&self, bytes: &[u8]) -> Result<SourceDigest, String> {
        let hash = digest_bytes(bytes);
        let path = self.path(hash);
        if path.is_file() {
            return Ok(hash);
        }
        let parent = path
            .parent()
            .ok_or_else(|| "source cache object has no parent directory".to_owned())?;
        std::fs::create_dir_all(parent)
            .map_err(|_| "cannot create source cache directory".to_owned())?;
        aps_platform::atomic_write(&path, bytes)
            .map_err(|_| "cannot write cached source content".to_owned())?;
        Ok(hash)
    }

    pub fn read(&self, hash: SourceDigest) -> Result<Vec<u8>, String> {
        let path = self.path(hash);
        let metadata = std::fs::symlink_metadata(&path)
            .map_err(|_| format!("cached source is unavailable: {}", hash.to_hex()))?;
        if !metadata.is_file() {
            return Err(format!(
                "cached source is not a regular file: {}",
                hash.to_hex()
            ));
        }
        let bytes = std::fs::read(&path)
            .map_err(|_| format!("cannot read cached source: {}", hash.to_hex()))?;
        if digest_bytes(&bytes) != hash {
            return Err(format!("cached source hash mismatch: {}", hash.to_hex()));
        }
        Ok(bytes)
    }

    pub fn contains(&self, hash: SourceDigest) -> bool {
        self.path(hash).is_file()
    }

    fn path(&self, hash: SourceDigest) -> PathBuf {
        let hex = hash.to_hex();
        self.root.join(&hex[..2]).join(&hex[2..])
    }
}

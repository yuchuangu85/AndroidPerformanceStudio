use crate::digest_bytes;
use aps_contracts::SourceDigest;
use std::fs::{File, Metadata};
use std::io::Read;
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

    /// Bounded immutable read for source-upload contexts. Check both the open
    /// handle and pathname so replacement/growth cannot bypass the byte cap.
    pub fn read_bounded(&self, hash: SourceDigest, max_bytes: u64) -> Result<Vec<u8>, String> {
        let limit = max_bytes.min(crate::MAX_SOURCE_FILE_BYTES);
        let path = self.path(hash);
        let before =
            std::fs::symlink_metadata(&path).map_err(|_| "E_MEMORY_SOURCE_CACHE_UNAVAILABLE")?;
        if !before.is_file() {
            return Err("E_MEMORY_SOURCE_CACHE_IDENTITY".into());
        }
        if before.len() > limit {
            return Err("E_MEMORY_SOURCE_CACHE_LIMIT".into());
        }
        let mut file = File::open(&path).map_err(|_| "E_MEMORY_SOURCE_CACHE_UNAVAILABLE")?;
        let opened = file
            .metadata()
            .map_err(|_| "E_MEMORY_SOURCE_CACHE_IDENTITY")?;
        if !same_file(&before, &opened) {
            return Err("E_MEMORY_SOURCE_CACHE_IDENTITY".into());
        }
        let mut bytes = Vec::new();
        file.by_ref()
            .take(limit + 1)
            .read_to_end(&mut bytes)
            .map_err(|_| "E_MEMORY_SOURCE_CACHE_UNAVAILABLE")?;
        if bytes.len() as u64 > limit {
            return Err("E_MEMORY_SOURCE_CACHE_LIMIT".into());
        }
        let handle = file
            .metadata()
            .map_err(|_| "E_MEMORY_SOURCE_CACHE_IDENTITY")?;
        let after =
            std::fs::symlink_metadata(&path).map_err(|_| "E_MEMORY_SOURCE_CACHE_IDENTITY")?;
        if !same_file(&before, &handle) || !same_file(&before, &after) {
            return Err("E_MEMORY_SOURCE_CACHE_IDENTITY".into());
        }
        if digest_bytes(&bytes) != hash {
            return Err("E_MEMORY_SOURCE_CACHE_HASH".into());
        }
        Ok(bytes)
    }

    fn path(&self, hash: SourceDigest) -> PathBuf {
        let hex = hash.to_hex();
        self.root.join(&hex[..2]).join(&hex[2..])
    }
}

fn same_file(left: &Metadata, right: &Metadata) -> bool {
    let same = left.is_file()
        && right.is_file()
        && left.len() == right.len()
        && left.modified().ok() == right.modified().ok();
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        same && left.dev() == right.dev()
            && left.ino() == right.ino()
            && left.ctime() == right.ctime()
            && left.ctime_nsec() == right.ctime_nsec()
    }
    #[cfg(not(unix))]
    {
        same && left.created().ok() == right.created().ok()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU64, Ordering};

    static NEXT: AtomicU64 = AtomicU64::new(0);

    struct Fixture(PathBuf);
    impl Fixture {
        fn new() -> Self {
            let root = std::env::temp_dir().join(format!(
                "aps-bounded-source-cache-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            std::fs::create_dir(&root).unwrap();
            Self(root)
        }
        fn cache(&self) -> ContentAddressedSourceCache {
            ContentAddressedSourceCache::new(self.0.clone())
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn bounded_read_accepts_exact_limit_and_rejects_larger_objects() {
        let fixture = Fixture::new();
        let cache = fixture.cache();
        let hash = cache.put(b"source").unwrap();
        assert_eq!(cache.read_bounded(hash, 6).unwrap(), b"source");
        assert_eq!(
            cache.read_bounded(hash, 5).unwrap_err(),
            "E_MEMORY_SOURCE_CACHE_LIMIT"
        );
        let huge = vec![0; crate::MAX_SOURCE_FILE_BYTES as usize + 1];
        let hash = cache.put(&huge).unwrap();
        assert_eq!(
            cache.read_bounded(hash, u64::MAX).unwrap_err(),
            "E_MEMORY_SOURCE_CACHE_LIMIT"
        );
    }

    #[test]
    fn bounded_read_rejects_missing_tampered_and_non_regular_objects() {
        let fixture = Fixture::new();
        let cache = fixture.cache();
        let hash = cache.put(b"source").unwrap();
        std::fs::write(cache.path(hash), b"tamper").unwrap();
        assert_eq!(
            cache.read_bounded(hash, 6).unwrap_err(),
            "E_MEMORY_SOURCE_CACHE_HASH"
        );
        std::fs::remove_file(cache.path(hash)).unwrap();
        assert_eq!(
            cache.read_bounded(hash, 6).unwrap_err(),
            "E_MEMORY_SOURCE_CACHE_UNAVAILABLE"
        );
        std::fs::create_dir(cache.path(hash)).unwrap();
        assert_eq!(
            cache.read_bounded(hash, 6).unwrap_err(),
            "E_MEMORY_SOURCE_CACHE_IDENTITY"
        );
    }

    #[cfg(unix)]
    #[test]
    fn bounded_read_rejects_symlinks_even_to_valid_content() {
        let fixture = Fixture::new();
        let cache = fixture.cache();
        let hash = cache.put(b"source").unwrap();
        let target = fixture.0.join("target");
        std::fs::rename(cache.path(hash), &target).unwrap();
        std::os::unix::fs::symlink(target, cache.path(hash)).unwrap();
        assert_eq!(
            cache.read_bounded(hash, 6).unwrap_err(),
            "E_MEMORY_SOURCE_CACHE_IDENTITY"
        );
    }
}

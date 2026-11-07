use std::fs::{File, Metadata};
use std::io::Read;
use std::path::Path;

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub struct SourceDigest([u8; 32]);
impl SourceDigest {
    pub fn from_bytes(bytes: [u8; 32]) -> Self {
        Self(bytes)
    }
    pub fn from_hex(hex: &str) -> Result<Self, String> {
        if hex.len() != 64 || !hex.bytes().all(|byte| byte.is_ascii_hexdigit()) {
            return Err("sourceDigest must be a 64-character SHA-256 hex digest".into());
        }
        let mut bytes = [0u8; 32];
        for (index, byte) in bytes.iter_mut().enumerate() {
            *byte = u8::from_str_radix(&hex[index * 2..index * 2 + 2], 16)
                .map_err(|_| "invalid SHA-256 hex")?;
        }
        Ok(Self(bytes))
    }
    pub fn as_bytes(&self) -> &[u8; 32] {
        &self.0
    }
    pub fn to_hex(self) -> String {
        const HEX: &[u8; 16] = b"0123456789abcdef";
        let mut result = String::with_capacity(64);
        for byte in self.0 {
            result.push(HEX[(byte >> 4) as usize] as char);
            result.push(HEX[(byte & 15) as usize] as char);
        }
        result
    }
    /// Streams at most max_bytes content bytes through the existing SHA-256
    /// implementation. One extra byte may be consumed only to detect oversize
    /// input at the EOF boundary; no prefix digest is returned on overflow.
    /// This is an input bound, not cancellation or a read-duration deadline.
    pub fn hash_reader(reader: impl Read, max_bytes: u64) -> Result<Self, String> {
        let hex = aps_hprof_engine::cache::sha256_reader(reader, max_bytes)
            .map_err(|_| "cannot hash source within input byte limit")?;
        Self::from_hex(&hex)
    }

    /// Hashes one read-only source handle with a hard streaming byte budget and
    /// verifies both handle/path metadata before and after reading. A digest
    /// identifies bytes read, not authorization or integrity of a later read.
    pub fn hash_file(path: impl AsRef<Path>, max_bytes: u64) -> Result<Self, String> {
        let path = path.as_ref();
        let mut file = File::open(path).map_err(|_| "cannot open digest source")?;
        let metadata = file
            .metadata()
            .map_err(|_| "cannot inspect digest source")?;
        if !metadata.is_file() || metadata.len() > max_bytes {
            return Err("digest source is not a regular file within the byte limit".into());
        }
        let before_path = std::fs::metadata(path).map_err(|_| "digest source disappeared")?;
        if !same_source_metadata(&metadata, &before_path) {
            return Err("digest source path identity changed before hashing".into());
        }
        let digest = Self::hash_reader(&mut file, max_bytes)?;
        let after_handle = file
            .metadata()
            .map_err(|_| "cannot inspect open digest source")?;
        let after_path = std::fs::metadata(path).map_err(|_| "digest source disappeared")?;
        if !same_source_metadata(&metadata, &after_handle)
            || !same_source_metadata(&metadata, &after_path)
        {
            return Err("digest source changed while hashing".into());
        }
        Ok(digest)
    }
}
impl std::fmt::Display for SourceDigest {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(&self.to_hex())
    }
}

fn same_source_metadata(before: &Metadata, after: &Metadata) -> bool {
    if !after.is_file()
        || before.len() != after.len()
        || before.modified().ok() != after.modified().ok()
        || before.created().ok() != after.created().ok()
    {
        return false;
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        if before.dev() != after.dev()
            || before.ino() != after.ino()
            || before.ctime() != after.ctime()
            || before.ctime_nsec() != after.ctime_nsec()
        {
            return false;
        }
    }
    true
}

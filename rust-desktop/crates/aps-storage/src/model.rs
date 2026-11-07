use aps_contracts::{ArtifactId, ProfilerSessionId, SourceDigest};
use aps_json::Value;
pub const DATABASE_FILE_NAME: &str = "aps-metadata.sqlite3";
pub const SCHEMA_VERSION: i64 = 3;
pub const APPLICATION_ID: i64 = 0x41505352;
pub const MAX_PAGE_SIZE: usize = 100;
pub const MAX_METADATA_BYTES: usize = 128 * 1024;
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct PageRequest {
    pub offset: usize,
    pub limit: usize,
}
impl Default for PageRequest {
    fn default() -> Self {
        Self {
            offset: 0,
            limit: 50,
        }
    }
}
impl PageRequest {
    pub fn validate(&self) -> Result<(), String> {
        if self.limit > MAX_PAGE_SIZE
            || self.offset > i64::MAX as usize
            || self.offset.checked_add(self.limit).is_none()
        {
            Err("metadata page exceeds supported bounds".into())
        } else {
            Ok(())
        }
    }
}
#[derive(Debug, Clone, PartialEq)]
pub struct Page<T> {
    pub total: usize,
    pub offset: usize,
    pub rows: Vec<T>,
}
#[derive(Debug, Clone, PartialEq)]
pub struct StoredArtifact {
    pub id: ArtifactId,
    pub source_digest: SourceDigest,
    pub byte_length: u64,
    pub media_type: String,
    pub created_unix_ms: u64,
    pub metadata: Value,
}
#[derive(Debug, Clone, PartialEq)]
pub struct StoredSession {
    pub id: ProfilerSessionId,
    pub label: String,
    pub artifact_ids: Vec<ArtifactId>,
    pub device_id: Option<String>,
    pub process_identity: Option<Value>,
    pub generation: u64,
    pub created_unix_ms: u64,
    pub last_opened_unix_ms: u64,
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct QueryBinding {
    pub session_id: ProfilerSessionId,
    pub generation: u64,
    pub source_digest: SourceDigest,
}
pub(crate) fn timestamp(value: u64) -> Result<i64, String> {
    i64::try_from(value).map_err(|_| "metadata timestamp exceeds signed i64".into())
}
pub(crate) fn generation(value: u64) -> Result<i64, String> {
    i64::try_from(value).map_err(|_| "metadata generation exceeds signed i64".into())
}
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SessionSummary {
    pub id: ProfilerSessionId,
    pub label: String,
    pub artifact_count: usize,
    pub device_id: Option<String>,
    pub generation: u64,
    pub created_unix_ms: u64,
    pub last_opened_unix_ms: u64,
}

//! Rust-owned session catalog. Publish immutable evidence first, then atomically
//! replace the index; a failed replacement never destroys the previous report.
use crate::{BatteryCaptureArtifact, BatteryReport, MAX_INPUT_BYTES};
use aps_json::{Limits, Map, Value};
use aps_platform::{atomic_write, create_private_directory};
use std::{
    collections::BTreeSet,
    fs,
    io::Read,
    path::{Path, PathBuf},
    sync::{
        Arc, Mutex,
        atomic::{AtomicU64, Ordering},
    },
    time::{SystemTime, UNIX_EPOCH},
};

const MAX_SESSIONS: usize = 1000;
const INDEX_BYTES: u64 = 16 * 1024 * 1024;
const EVIDENCE_BYTES: u64 = 72 * 1024 * 1024;
static NEXT_FILE: AtomicU64 = AtomicU64::new(0);

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StoredBatterySession {
    /// Opaque catalog identity, independent of a producer's session ID.
    pub key: String,
    pub session_id: String,
    pub package_name: Option<String>,
    pub created_at: Option<String>,
    pub run_count: usize,
    pub captured: bool,
}
impl StoredBatterySession {
    pub fn matches(&self, query: &str) -> bool {
        let query = query.trim().to_lowercase();
        query.is_empty()
            || [
                self.session_id.as_str(),
                self.package_name.as_deref().unwrap_or(""),
                self.created_at.as_deref().unwrap_or(""),
            ]
            .iter()
            .any(|text| text.to_lowercase().contains(&query))
    }
}

pub struct BatterySavedSession {
    pub metadata: StoredBatterySession,
    /// Restoring local evidence does not make this a current live observation.
    pub report: BatteryReport,
    pub artifact: Option<BatteryCaptureArtifact>,
}

#[derive(Clone)]
pub struct BatterySessionStore {
    root: PathBuf,
    mutex: Arc<Mutex<()>>,
}
impl BatterySessionStore {
    pub fn new(root: PathBuf) -> Self {
        Self {
            root,
            mutex: Arc::new(Mutex::new(())),
        }
    }

    pub fn list(&self) -> Result<Vec<StoredBatterySession>, String> {
        let _guard = self
            .mutex
            .lock()
            .map_err(|_| "Battery session store lock unavailable")?;
        Ok(self
            .read_index()?
            .into_iter()
            .map(|entry| entry.metadata)
            .collect())
    }

    /// Imported reports and owned captures have separate identities even when
    /// an external producer reuses a capture's session ID.
    pub fn save(
        &self,
        report: &BatteryReport,
        artifact: Option<&BatteryCaptureArtifact>,
    ) -> Result<StoredBatterySession, String> {
        if report.live_capture && artifact.is_none() {
            return Err("Cannot retain a live Battery report without its capture evidence".into());
        }
        let analysis = if let Some(artifact) = artifact {
            artifact.validate_report(report)?;
            artifact.entries()[1].1.to_vec()
        } else {
            report.to_json(MAX_INPUT_BYTES)?
        };
        let bundle = artifact
            .map(|artifact| -> Result<Value, String> {
                let entries = artifact.entries();
                Ok(object([
                    ("manifest", text_bytes(entries[0].1)?),
                    ("snapshots", text_bytes(entries[2].1)?),
                ]))
            })
            .transpose()?;
        let bytes = aps_json::to_vec(
            &object([
                ("version", 2.into()),
                (
                    "analysis",
                    if artifact.is_some() {
                        text_bytes(&analysis)?
                    } else {
                        aps_json::parse_slice(&analysis, Limits::default())
                            .map_err(|error| error.to_string())?
                    },
                ),
                ("bundle", bundle.unwrap_or(Value::Null)),
            ]),
            evidence_limits(),
        )
        .map_err(|error| format!("Battery saved evidence: {error}"))?;
        let _guard = self
            .mutex
            .lock()
            .map_err(|_| "Battery session store lock unavailable")?;
        let mut entries = self.read_index()?;
        let captured = artifact.is_some();
        let previous = entries.iter().position(|entry| {
            entry.metadata.session_id == report.session_id && entry.metadata.captured == captured
        });
        if previous.is_none() && entries.len() >= MAX_SESSIONS {
            return Err(
                "Battery session catalog is full; remove a saved session before retaining another"
                    .into(),
            );
        }
        let file = new_key()?;
        let metadata = StoredBatterySession {
            key: previous.map_or_else(|| file.clone(), |index| entries[index].metadata.key.clone()),
            session_id: report.session_id.clone(),
            package_name: report.package_name.clone(),
            created_at: report.created_at.clone(),
            run_count: report.runs().len(),
            captured,
        };
        let old_file = previous.map(|index| entries.remove(index).file);
        entries.insert(
            0,
            Entry {
                metadata: metadata.clone(),
                file: file.clone(),
                digest: evidence_digest(&bytes, EVIDENCE_BYTES as usize)?,
                bytes: bytes.len() as u64,
            },
        );
        let index = encode_index(&entries)?;
        let evidence_path = self.root.join(format!("{file}.json"));
        match fs::symlink_metadata(&evidence_path) {
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            _ => {
                return Err("Battery evidence destination already exists or is unavailable".into());
            }
        }
        atomic_write(&evidence_path, &bytes)
            .map_err(|error| format!("Cannot save Battery evidence: {error}"))?;
        if let Err(error) = atomic_write(&self.root.join("sessions.json"), &index) {
            let _ = fs::remove_file(&evidence_path);
            return Err(format!("Cannot save Battery session catalog: {error}"));
        }
        if let Some(file) = old_file {
            let _ = fs::remove_file(self.root.join(format!("{file}.json")));
        }
        Ok(metadata)
    }

    pub fn load(&self, key: &str) -> Result<BatterySavedSession, String> {
        let _guard = self
            .mutex
            .lock()
            .map_err(|_| "Battery session store lock unavailable")?;
        let entry = self
            .read_index()?
            .into_iter()
            .find(|entry| entry.metadata.key == key)
            .ok_or("Battery saved session not found")?;
        let path = self.root.join(format!("{}.json", entry.file));
        let bytes = read_bounded(&path, EVIDENCE_BYTES)?;
        if bytes.len() as u64 != entry.bytes
            || evidence_digest(&bytes, EVIDENCE_BYTES as usize)? != entry.digest
        {
            return Err("Battery saved evidence digest or length mismatch".into());
        }
        let root = aps_json::parse_slice(&bytes, evidence_limits())
            .map_err(|error| format!("Cannot restore Battery evidence: {error}"))?;
        if root["version"].as_u64() != Some(2) {
            return Err("Unsupported Battery saved evidence version".into());
        }
        // Only capture analysis needs byte-for-byte retention. Imported JSON
        // stays structured so the report's full byte budget is not one string.
        let analysis = match (&root["analysis"], entry.metadata.captured) {
            (Value::String(text), true) => text.as_bytes().to_vec(),
            (value @ Value::Object(_), false) => {
                aps_json::to_vec(value, Limits::default()).map_err(|error| error.to_string())?
            }
            _ => return Err("Battery analysis encoding does not match its provenance".into()),
        };
        let report = BatteryReport::parse_json(&analysis)?;
        if report.session_id != entry.metadata.session_id
            || report.package_name != entry.metadata.package_name
            || report.created_at != entry.metadata.created_at
            || report.runs().len() != entry.metadata.run_count
        {
            return Err("Battery catalog metadata does not match saved evidence".into());
        }
        let artifact = if entry.metadata.captured {
            let bundle = root
                .get("bundle")
                .ok_or("Missing Battery capture evidence")?;
            Some(BatteryCaptureArtifact::from_stored_entries(
                bundle["manifest"]
                    .as_str()
                    .ok_or("Missing Battery capture manifest")?
                    .as_bytes(),
                &analysis,
                bundle["snapshots"]
                    .as_str()
                    .ok_or("Missing Battery snapshot evidence")?
                    .as_bytes(),
            )?)
        } else {
            if !root.get("bundle").is_some_and(Value::is_null) {
                return Err("Imported Battery session cannot acquire capture provenance".into());
            }
            None
        };
        Ok(BatterySavedSession {
            metadata: entry.metadata,
            report,
            artifact,
        })
    }

    /// Only the Rust-owned copy is removed, never an imported source file.
    pub fn remove(&self, key: &str) -> Result<(), String> {
        let _guard = self
            .mutex
            .lock()
            .map_err(|_| "Battery session store lock unavailable")?;
        let mut entries = self.read_index()?;
        let Some(index) = entries.iter().position(|entry| entry.metadata.key == key) else {
            return Err("Battery saved session not found".into());
        };
        let entry = entries.remove(index);
        let bytes = encode_index(&entries)?;
        atomic_write(&self.root.join("sessions.json"), &bytes)
            .map_err(|error| format!("Cannot remove Battery session: {error}"))?;
        match fs::remove_file(self.root.join(format!("{}.json", entry.file))) {
            Ok(()) => Ok(()),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(()),
            Err(error) => Err(format!(
                "Battery session removed from catalog, but owned evidence cleanup failed: {error}"
            )),
        }
    }

    fn read_index(&self) -> Result<Vec<Entry>, String> {
        create_private_directory(&self.root)
            .map_err(|error| format!("Cannot open private Battery session directory: {error}"))?;
        let path = self.root.join("sessions.json");
        match fs::symlink_metadata(&path) {
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(Vec::new()),
            _ => require_file(&path)?,
        }
        let root = aps_json::parse_slice(&read_bounded(&path, INDEX_BYTES)?, index_limits())
            .map_err(|error| format!("Battery session catalog: {error}"))?;
        if root["version"].as_u64() != Some(2) {
            return Err("Unsupported Battery session catalog version".into());
        }
        let rows = root["sessions"]
            .as_array()
            .filter(|rows| rows.len() <= MAX_SESSIONS)
            .ok_or("Invalid Battery session catalog entries")?;
        let mut keys = BTreeSet::new();
        let mut files = BTreeSet::new();
        let mut identities = BTreeSet::new();
        rows.iter()
            .map(|row| {
                let key = field(row, "key")?;
                let file = field(row, "file")?;
                if !valid_key(&key)
                    || !valid_key(&file)
                    || !keys.insert(key.clone())
                    || !files.insert(file.clone())
                {
                    return Err("Invalid or duplicate Battery storage identity".into());
                }
                let session_id = field(row, "sessionId")?;
                let captured = row["captured"]
                    .as_bool()
                    .ok_or("Invalid Battery session origin")?;
                if !identities.insert((session_id.clone(), captured)) {
                    return Err("Duplicate Battery session identity".into());
                }
                let run_count = row["runCount"]
                    .as_u64()
                    .filter(|count| (1..=100_000).contains(count))
                    .ok_or("Invalid Battery saved run count")?
                    as usize;
                let digest = field(row, "sha256")?;
                if digest.len() != 64 || !digest.bytes().all(|byte| byte.is_ascii_hexdigit()) {
                    return Err("Invalid Battery evidence digest".into());
                }
                Ok(Entry {
                    file,
                    digest,
                    bytes: row["bytes"]
                        .as_u64()
                        .filter(|bytes| (1..=EVIDENCE_BYTES).contains(bytes))
                        .ok_or("Invalid Battery evidence length")?,
                    metadata: StoredBatterySession {
                        key,
                        session_id,
                        package_name: nullable_field(row, "packageName")?,
                        created_at: nullable_field(row, "createdAt")?,
                        run_count,
                        captured,
                    },
                })
            })
            .collect()
    }
}

struct Entry {
    metadata: StoredBatterySession,
    file: String,
    digest: String,
    bytes: u64,
}
fn new_key() -> Result<String, String> {
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| "Battery storage clock unavailable")?
        .as_nanos();
    Ok(format!(
        "{nanos:032x}-{:08x}-{:016x}",
        std::process::id(),
        NEXT_FILE.fetch_add(1, Ordering::Relaxed)
    ))
}
fn valid_key(key: &str) -> bool {
    key.len() == 58
        && key.bytes().enumerate().all(|(index, byte)| match index {
            32 | 41 => byte == b'-',
            _ => byte.is_ascii_hexdigit(),
        })
}
fn object<const N: usize>(fields: [(&str, Value); N]) -> Value {
    Value::Object(
        fields
            .into_iter()
            .map(|(key, value)| (key.into(), value))
            .collect::<Map<_, _>>(),
    )
}
fn text_bytes(bytes: &[u8]) -> Result<Value, String> {
    String::from_utf8(bytes.to_vec())
        .map(Value::String)
        .map_err(|_| "Battery evidence must be UTF-8".into())
}
fn field(value: &Value, key: &str) -> Result<String, String> {
    value[key]
        .as_str()
        .filter(|text| !text.is_empty() && text.len() <= 4096)
        .map(str::to_owned)
        .ok_or_else(|| format!("Invalid Battery catalog {key}"))
}
fn nullable_field(value: &Value, key: &str) -> Result<Option<String>, String> {
    match value.get(key) {
        Some(Value::Null) => Ok(None),
        Some(_) => field(value, key).map(Some),
        None => Err(format!("Missing Battery catalog {key}")),
    }
}
fn require_file(path: &std::path::Path) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        match fs::symlink_metadata(parent) {
            Ok(metadata) if metadata.is_dir() && !metadata.is_symlink() => {}
            _ => return Err("Battery session directory must be a real owned directory".into()),
        }
    }
    let metadata = fs::symlink_metadata(path)
        .map_err(|error| format!("Cannot inspect Battery session file: {error}"))?;
    if !metadata.is_file() || metadata.is_symlink() {
        return Err("Battery session evidence must be a regular owned file".into());
    }
    Ok(())
}
fn read_bounded(path: &Path, max_bytes: u64) -> Result<Vec<u8>, String> {
    require_file(path)?;
    let file = fs::File::open(path).map_err(|error| error.to_string())?;
    if file.metadata().map_err(|error| error.to_string())?.len() > max_bytes {
        return Err("Battery session file exceeds its byte budget".into());
    }
    let mut bytes = Vec::new();
    file.take(max_bytes + 1)
        .read_to_end(&mut bytes)
        .map_err(|error| error.to_string())?;
    if bytes.len() as u64 > max_bytes {
        return Err("Battery session file exceeds its byte budget".into());
    }
    Ok(bytes)
}
fn index_limits() -> Limits {
    Limits {
        max_bytes: INDEX_BYTES,
        max_collection_items: MAX_SESSIONS,
        ..Limits::default()
    }
}
fn evidence_limits() -> Limits {
    Limits {
        max_bytes: EVIDENCE_BYTES,
        max_string_bytes: 4 * 1024 * 1024,
        ..Limits::default()
    }
}
fn encode_index(entries: &[Entry]) -> Result<Vec<u8>, String> {
    let rows = entries
        .iter()
        .map(|entry| {
            let metadata = &entry.metadata;
            object([
                ("key", metadata.key.clone().into()),
                ("file", entry.file.clone().into()),
                ("sha256", entry.digest.clone().into()),
                ("bytes", entry.bytes.into()),
                ("sessionId", metadata.session_id.clone().into()),
                (
                    "packageName",
                    metadata
                        .package_name
                        .clone()
                        .map_or(Value::Null, Value::String),
                ),
                (
                    "createdAt",
                    metadata
                        .created_at
                        .clone()
                        .map_or(Value::Null, Value::String),
                ),
                ("runCount", metadata.run_count.into()),
                ("captured", metadata.captured.into()),
            ])
        })
        .collect::<Vec<_>>();
    aps_json::to_vec(
        &object([("version", 2.into()), ("sessions", rows.into())]),
        index_limits(),
    )
    .map_err(|error| format!("Battery session catalog: {error}"))
}

/// Content identity only, not authentication of a writable local catalog.
/// Reuses the std-only SHA-256 core from hprof-engine-rust/src/cache.rs without
/// introducing its engine dependency. Hash the same bounded bytes we parse.
pub(crate) fn evidence_digest(bytes: &[u8], max_bytes: usize) -> Result<String, String> {
    use std::fmt::Write as _;
    if bytes.len() > max_bytes || bytes.len() as u64 > u64::MAX / 8 {
        return Err("Battery digest source exceeds its byte budget".into());
    }
    let mut state = [
        0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab,
        0x5be0cd19,
    ];
    let (blocks, remainder) = bytes.as_chunks::<64>();
    for block in blocks {
        sha256_compress(&mut state, block);
    }
    let mut tail = [0u8; 128];
    tail[..remainder.len()].copy_from_slice(remainder);
    tail[remainder.len()] = 0x80;
    let length = if remainder.len() < 56 { 64 } else { 128 };
    tail[length - 8..length].copy_from_slice(&(bytes.len() as u64 * 8).to_be_bytes());
    for block in tail[..length].as_chunks::<64>().0 {
        sha256_compress(&mut state, block);
    }
    let mut hex = String::with_capacity(64);
    for word in state {
        write!(hex, "{word:08x}").expect("write digest to String");
    }
    Ok(hex)
}

fn sha256_compress(state: &mut [u32; 8], block: &[u8]) {
    const K: [u32; 64] = [
        0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4,
        0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe,
        0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f,
        0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
        0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc,
        0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
        0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116,
        0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
        0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7,
        0xc67178f2,
    ];
    let mut w = [0u32; 64];
    for index in 0..16 {
        w[index] = u32::from_be_bytes(block[index * 4..index * 4 + 4].try_into().unwrap());
    }
    for index in 16..64 {
        let a = w[index - 15];
        let b = w[index - 2];
        w[index] = (a.rotate_right(7) ^ a.rotate_right(18) ^ (a >> 3))
            .wrapping_add(w[index - 16])
            .wrapping_add(w[index - 7])
            .wrapping_add(b.rotate_right(17) ^ b.rotate_right(19) ^ (b >> 10));
    }
    let [mut a, mut b, mut c, mut d, mut e, mut f, mut g, mut h] = *state;
    for index in 0..64 {
        let temp1 = h
            .wrapping_add(e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25))
            .wrapping_add((e & f) ^ ((!e) & g))
            .wrapping_add(K[index])
            .wrapping_add(w[index]);
        let temp2 = (a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22))
            .wrapping_add((a & b) ^ (a & c) ^ (b & c));
        h = g;
        g = f;
        f = e;
        e = d.wrapping_add(temp1);
        d = c;
        c = b;
        b = a;
        a = temp1.wrapping_add(temp2);
    }
    for (word, addition) in state.iter_mut().zip([a, b, c, d, e, f, g, h]) {
        *word = word.wrapping_add(addition);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_platform::TempWorkspace;

    fn store() -> (TempWorkspace, BatterySessionStore) {
        let workspace = TempWorkspace::create(&std::env::temp_dir()).unwrap();
        let store = BatterySessionStore::new(workspace.path().join("sessions"));
        (workspace, store)
    }

    fn imported() -> BatteryReport {
        BatteryReport::parse_json(
            br#"{
            "schemaVersion":1,"sessionId":"import-fixture","packageName":"example.app",
            "createdAt":"2026-11-11T00:01:02Z","uid":10001,
            "runs":[{"runId":"run-a","iteration":1,"durationMs":5000,"networkBytes":0,
                "wakelocks":[],"warnings":[] }]
        }"#,
        )
        .unwrap()
    }

    fn index_value(store: &BatterySessionStore) -> Value {
        aps_json::read(&store.root.join("sessions.json"), index_limits()).unwrap()
    }

    fn write_index(store: &BatterySessionStore, value: &Value) {
        atomic_write(
            &store.root.join("sessions.json"),
            &aps_json::to_vec(value, index_limits()).unwrap(),
        )
        .unwrap();
    }

    fn evidence_path(store: &BatterySessionStore) -> PathBuf {
        let index = index_value(store);
        store.root.join(format!(
            "{}.json",
            index["sessions"][0]["file"].as_str().unwrap()
        ))
    }

    fn rewrite_evidence(store: &BatterySessionStore, value: &Value) {
        let bytes = aps_json::to_vec(value, evidence_limits()).unwrap();
        atomic_write(&evidence_path(store), &bytes).unwrap();
        let mut index = index_value(store);
        index["sessions"][0]["sha256"] = evidence_digest(&bytes, EVIDENCE_BYTES as usize)
            .unwrap()
            .into();
        index["sessions"][0]["bytes"] = bytes.len().into();
        write_index(store, &index);
    }

    #[test]
    fn imported_roundtrip_remains_derived_and_persists_after_reopen() {
        let (_workspace, store) = store();
        assert!(store.list().unwrap().is_empty());
        let metadata = store.save(&imported(), None).unwrap();
        assert!(!metadata.captured);
        assert_eq!(metadata.session_id, "import-fixture");
        assert_eq!(metadata.package_name.as_deref(), Some("example.app"));
        assert_eq!(metadata.created_at.as_deref(), Some("2026-11-11T00:01:02Z"));
        assert_eq!(metadata.run_count, 1);
        assert!(metadata.matches("EXAMPLE"));
        assert!(metadata.matches("2026-11"));
        assert!(!metadata.matches("no-match"));
        let reopened = BatterySessionStore::new(store.root.clone());
        assert_eq!(reopened.list().unwrap(), vec![metadata.clone()]);
        let saved = reopened.load(&metadata.key).unwrap();
        assert_eq!(saved.metadata, metadata);
        assert!(!saved.report.live_capture);
        assert!(saved.artifact.is_none());
        assert_eq!(saved.report.runs()[0].duration_ms, 5000);
        assert_eq!(saved.report.runs()[0].network_bytes, Some(0));
        assert!(saved.report.runs()[0].has_timer_evidence(crate::ResourceKind::Wakelock));
        assert!(!saved.report.runs()[0].has_timer_evidence(crate::ResourceKind::Job));
    }

    #[test]
    fn captured_roundtrip_preserves_exact_bundle_and_can_be_saved_again_without_live_flag() {
        let (_workspace, store) = store();
        let (report, artifact) = BatteryCaptureArtifact::test_capture();
        let metadata = store.save(&report, Some(&artifact)).unwrap();
        assert!(metadata.captured);
        assert_eq!(metadata.session_id, artifact.session_id());
        let reopened = BatterySessionStore::new(store.root.clone());
        let saved = reopened.load(&metadata.key).unwrap();
        assert!(!saved.report.live_capture);
        assert_eq!(saved.metadata, metadata);
        assert_eq!(
            saved.artifact.as_ref().unwrap().entries(),
            artifact.entries()
        );
        let replaced = reopened
            .save(&saved.report, saved.artifact.as_ref())
            .unwrap();
        assert_eq!(replaced, metadata);
        assert_eq!(reopened.list().unwrap(), vec![metadata.clone()]);
        let saved = BatterySessionStore::new(store.root.clone())
            .load(&metadata.key)
            .unwrap();
        assert!(!saved.report.live_capture);
        assert_eq!(saved.artifact.unwrap().entries(), artifact.entries());
    }

    #[test]
    fn imported_and_captured_producer_ids_have_distinct_replaceable_identities() {
        let (_workspace, store) = store();
        let (report, artifact) = BatteryCaptureArtifact::test_capture();
        let imported = BatteryReport::parse_json(artifact.entries()[1].1).unwrap();
        let capture_metadata = store.save(&report, Some(&artifact)).unwrap();
        let import_metadata = store.save(&imported, None).unwrap();
        assert_eq!(capture_metadata.session_id, import_metadata.session_id);
        assert_ne!(capture_metadata.key, import_metadata.key);
        assert!(!import_metadata.captured);
        let mut imported = imported;
        imported.package_name = Some("replacement.app".into());
        let old_files = store
            .read_index()
            .unwrap()
            .into_iter()
            .map(|entry| entry.file)
            .collect::<Vec<_>>();
        let replaced = store.save(&imported, None).unwrap();
        assert_eq!(replaced.key, import_metadata.key);
        assert_eq!(replaced.package_name.as_deref(), Some("replacement.app"));
        assert_eq!(store.list().unwrap().len(), 2);
        assert_eq!(
            store
                .load(&capture_metadata.key)
                .unwrap()
                .artifact
                .unwrap()
                .entries(),
            artifact.entries()
        );
        let new_files = store
            .read_index()
            .unwrap()
            .into_iter()
            .map(|entry| entry.file)
            .collect::<Vec<_>>();
        assert!(
            old_files
                .iter()
                .filter(|file| !new_files.contains(file))
                .all(|file| !store.root.join(format!("{file}.json")).exists())
        );
        assert_eq!(fs::read_dir(&store.root).unwrap().count(), 3);
    }

    #[test]
    fn live_report_without_bundle_and_mismatched_report_are_never_published() {
        let (_workspace, store) = store();
        let (mut report, artifact) = BatteryCaptureArtifact::test_capture();
        assert!(store.save(&report, None).is_err());
        assert!(store.list().unwrap().is_empty());
        let metadata = store.save(&report, Some(&artifact)).unwrap();
        let index = fs::read(store.root.join("sessions.json")).unwrap();
        report.package_name = Some("wrong.app".into());
        assert!(store.save(&report, Some(&artifact)).is_err());
        assert_eq!(fs::read(store.root.join("sessions.json")).unwrap(), index);
        let mut saved = store.load(&metadata.key).unwrap();
        saved.report.warnings.push("changed evidence".into());
        assert!(store.save(&saved.report, saved.artifact.as_ref()).is_err());
        assert_eq!(store.list().unwrap(), vec![metadata]);
    }

    #[test]
    fn remove_only_deletes_managed_copy_and_persists_after_reopen() {
        let (workspace, store) = store();
        let source = workspace.path().join("import-source.json");
        let source_bytes = imported().to_json(MAX_INPUT_BYTES).unwrap();
        atomic_write(&source, &source_bytes).unwrap();
        let metadata = store
            .save(
                &BatteryReport::open(&source, MAX_INPUT_BYTES as u64).unwrap(),
                None,
            )
            .unwrap();
        let evidence = evidence_path(&store);
        let unrelated = store.root.join("unmanaged.json");
        atomic_write(&unrelated, b"unrelated").unwrap();
        assert!(store.remove("../import-source").is_err());
        store.remove(&metadata.key).unwrap();
        assert!(!evidence.exists());
        assert_eq!(fs::read(&source).unwrap(), source_bytes);
        assert_eq!(fs::read(&unrelated).unwrap(), b"unrelated");
        let reopened = BatterySessionStore::new(store.root.clone());
        assert!(reopened.list().unwrap().is_empty());
        assert!(reopened.load(&metadata.key).is_err());
        assert!(reopened.remove(&metadata.key).is_err());
    }

    #[test]
    fn corrupt_index_traversal_duplicates_and_invalid_fields_fail_closed() {
        let (_workspace, store) = store();
        let metadata = store.save(&imported(), None).unwrap();
        let original = index_value(&store);
        let index_path = store.root.join("sessions.json");
        atomic_write(&index_path, br#"{"version":2,"version":2,"sessions":[]}"#).unwrap();
        assert!(store.list().is_err());
        assert!(store.save(&imported(), None).is_err());
        for (field, value) in [
            ("key", "../escape".into()),
            ("file", "../escape".into()),
            ("file", "/tmp/escape".into()),
            ("file", "-".repeat(58).into()),
            ("sessionId", "".into()),
            ("runCount", 0.into()),
            ("runCount", 100001.into()),
            ("captured", "yes".into()),
            ("packageName", "".into()),
            ("sha256", "x".repeat(64).into()),
            ("bytes", 0.into()),
            ("bytes", (EVIDENCE_BYTES + 1).into()),
        ] {
            let mut index = original.clone();
            index["sessions"][0]
                .as_object_mut()
                .unwrap()
                .insert(field.into(), value);
            write_index(&store, &index);
            assert!(store.list().is_err(), "listed invalid {field}");
            assert!(store.load(&metadata.key).is_err(), "loaded invalid {field}");
            assert!(
                store.remove(&metadata.key).is_err(),
                "removed invalid {field}"
            );
        }
        let mut index = original.clone();
        let row = index["sessions"][0].clone();
        index["sessions"].as_array_mut().unwrap().push(row);
        write_index(&store, &index);
        assert!(store.list().is_err());
        let mut index = original.clone();
        index["version"] = 1.into();
        write_index(&store, &index);
        assert!(store.list().is_err());
        write_index(&store, &original);
        store.load(&metadata.key).unwrap();
    }

    #[test]
    fn all_catalog_metadata_including_capture_origin_is_checked_against_evidence() {
        let (_workspace, store) = store();
        let metadata = store.save(&imported(), None).unwrap();
        let original = index_value(&store);
        for (field, value) in [
            ("sessionId", "different".into()),
            ("packageName", "different.app".into()),
            ("createdAt", "2026-11-12T00:01:02Z".into()),
            ("runCount", 2.into()),
            ("captured", true.into()),
        ] {
            let mut index = original.clone();
            index["sessions"][0]
                .as_object_mut()
                .unwrap()
                .insert(field.into(), value);
            write_index(&store, &index);
            assert!(
                store.load(&metadata.key).is_err(),
                "accepted changed {field}"
            );
        }
        write_index(&store, &original);
        let (report, artifact) = BatteryCaptureArtifact::test_capture();
        let metadata = store.save(&report, Some(&artifact)).unwrap();
        let mut index = index_value(&store);
        index["sessions"][0]["captured"] = false.into();
        write_index(&store, &index);
        assert!(store.load(&metadata.key).is_err());
    }

    #[test]
    fn same_length_content_tamper_truncation_and_missing_evidence_are_not_adopted() {
        let (_workspace, store) = store();
        let metadata = store.save(&imported(), None).unwrap();
        let path = evidence_path(&store);
        let original = fs::read(&path).unwrap();
        let tampered = String::from_utf8(original.clone())
            .unwrap()
            .replace("5000", "6000");
        assert_eq!(tampered.len(), original.len());
        atomic_write(&path, tampered.as_bytes()).unwrap();
        assert!(store.load(&metadata.key).err().unwrap().contains("digest"));
        atomic_write(&path, &original[..original.len() - 1]).unwrap();
        assert!(store.load(&metadata.key).is_err());
        fs::remove_file(&path).unwrap();
        assert!(store.load(&metadata.key).is_err());
        store.remove(&metadata.key).unwrap();
    }

    #[test]
    fn oversized_index_and_evidence_fail_before_reading_their_contents() {
        let (_workspace, store) = store();
        let metadata = store.save(&imported(), None).unwrap();
        fs::OpenOptions::new()
            .write(true)
            .open(evidence_path(&store))
            .unwrap()
            .set_len(EVIDENCE_BYTES + 1)
            .unwrap();
        assert!(
            store
                .load(&metadata.key)
                .err()
                .unwrap()
                .contains("byte budget")
        );
        fs::OpenOptions::new()
            .write(true)
            .open(store.root.join("sessions.json"))
            .unwrap()
            .set_len(INDEX_BYTES + 1)
            .unwrap();
        assert!(store.list().err().unwrap().contains("byte budget"));
    }

    #[test]
    fn captured_inner_evidence_is_validated_even_if_outer_digest_is_rewritten() {
        let (_workspace, store) = store();
        let (report, artifact) = BatteryCaptureArtifact::test_capture();
        let metadata = store.save(&report, Some(&artifact)).unwrap();
        let original = aps_json::read(&evidence_path(&store), evidence_limits()).unwrap();
        let mut evidence = original.clone();
        let snapshots = evidence["bundle"]["snapshots"]
            .as_str()
            .unwrap()
            .replace("selected-boot", "another-boot");
        evidence["bundle"]["snapshots"] = snapshots.into();
        rewrite_evidence(&store, &evidence);
        assert!(store.load(&metadata.key).is_err());
        let mut evidence = original.clone();
        let analysis = evidence["analysis"]
            .as_str()
            .unwrap()
            .replace("5000", "6000");
        evidence["analysis"] = analysis.into();
        rewrite_evidence(&store, &evidence);
        assert!(store.load(&metadata.key).is_err());
        let mut evidence = original;
        evidence["version"] = 1.into();
        rewrite_evidence(&store, &evidence);
        assert!(store.load(&metadata.key).is_err());
        evidence["version"] = 2.into();
        evidence["bundle"] = Value::Null;
        rewrite_evidence(&store, &evidence);
        assert!(store.load(&metadata.key).is_err());
    }

    #[cfg(unix)]
    #[test]
    fn private_files_and_symlink_boundaries_are_enforced() {
        use std::os::unix::fs::{PermissionsExt, symlink};
        let (workspace, store) = store();
        let metadata = store.save(&imported(), None).unwrap();
        assert_eq!(
            fs::metadata(&store.root).unwrap().permissions().mode() & 0o777,
            0o700
        );
        for entry in fs::read_dir(&store.root).unwrap() {
            let entry = entry.unwrap();
            assert_eq!(
                entry.metadata().unwrap().permissions().mode() & 0o777,
                0o600
            );
            assert!(!entry.file_name().to_string_lossy().ends_with(".tmp"));
        }
        let source = workspace.path().join("source.json");
        let evidence = evidence_path(&store);
        fs::rename(&evidence, &source).unwrap();
        let bytes = fs::read(&source).unwrap();
        symlink(&source, &evidence).unwrap();
        assert!(store.load(&metadata.key).is_err());
        store.remove(&metadata.key).unwrap();
        assert_eq!(fs::read(&source).unwrap(), bytes);
        let linked_root = workspace.path().join("linked-root");
        symlink(&store.root, &linked_root).unwrap();
        let linked = BatterySessionStore::new(linked_root);
        assert!(linked.list().is_err());
        assert!(linked.save(&imported(), None).is_err());
        let index_path = store.root.join("sessions.json");
        fs::remove_file(&index_path).unwrap();
        symlink(&source, &index_path).unwrap();
        assert!(store.list().is_err());
        assert!(store.save(&imported(), None).is_err());
        assert_eq!(fs::read(&source).unwrap(), bytes);
    }

    #[test]
    fn sha256_known_vectors_padding_and_budget() {
        for (input, expected) in [
            (
                b"".as_slice(),
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ),
            (
                b"abc",
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ),
            (
                b"abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq",
                "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            ),
        ] {
            assert_eq!(evidence_digest(input, input.len()).unwrap(), expected);
            if !input.is_empty() {
                assert!(evidence_digest(input, input.len() - 1).is_err());
            }
        }
        assert_eq!(
            evidence_digest(&vec![b'a'; 1_000_000], 1_000_000).unwrap(),
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"
        );
    }
}

use crate::{AiError, AnalysisResult, AuthorizedRun, identifier};
use aps_contracts::SourceDigest;
use aps_json::Value;
use std::path::{Path, PathBuf};
use std::time::Instant;

pub const AUDIT_FILE_NAME: &str = "aps-ai-audit-v1.json";
const MAX_RECORDS: usize = 1000;
const MAX_BYTES: u64 = 2 * 1024 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AuditState {
    Running,
    Completed,
    Cancelled,
    Failed,
}

impl AuditState {
    fn as_str(self) -> &'static str {
        match self {
            Self::Running => "running",
            Self::Completed => "completed",
            Self::Cancelled => "cancelled",
            Self::Failed => "failed",
        }
    }
    fn parse(value: &str) -> Result<Self, AiError> {
        match value {
            "running" => Ok(Self::Running),
            "completed" => Ok(Self::Completed),
            "cancelled" => Ok(Self::Cancelled),
            "failed" => Ok(Self::Failed),
            _ => Err(AiError::Persistence),
        }
    }
}

/// Only bounded correlation metadata; never credential values/references,
/// endpoint URLs, question, evidence, source paths/snippets or provider output.
#[derive(Clone)]
pub struct AuditRecord {
    session_id: String,
    run_id: String,
    generation: u64,
    provider_id: String,
    request_digest: SourceDigest,
    state: AuditState,
    evidence_count: usize,
    source_candidate_count: usize,
    finding_count: usize,
}

impl AuditRecord {
    pub fn session_id(&self) -> &str {
        &self.session_id
    }
    pub fn run_id(&self) -> &str {
        &self.run_id
    }
    pub fn generation(&self) -> u64 {
        self.generation
    }
    pub fn provider_id(&self) -> &str {
        &self.provider_id
    }
    pub fn request_digest(&self) -> SourceDigest {
        self.request_digest
    }
    pub fn state(&self) -> AuditState {
        self.state
    }
    pub fn evidence_count(&self) -> usize {
        self.evidence_count
    }
    pub fn source_candidate_count(&self) -> usize {
        self.source_candidate_count
    }
    pub fn finding_count(&self) -> usize {
        self.finding_count
    }

    fn value(&self) -> Value {
        super::model::object([
            ("sessionId", self.session_id.clone().into()),
            ("runId", self.run_id.clone().into()),
            ("generation", self.generation.into()),
            ("providerId", self.provider_id.clone().into()),
            ("requestDigest", self.request_digest.to_hex().into()),
            ("state", self.state.as_str().into()),
            ("evidenceCount", (self.evidence_count as u64).into()),
            (
                "sourceCandidateCount",
                (self.source_candidate_count as u64).into(),
            ),
            ("findingCount", (self.finding_count as u64).into()),
        ])
    }

    fn parse(value: &Value) -> Result<Self, AiError> {
        let fields = [
            "sessionId",
            "runId",
            "generation",
            "providerId",
            "requestDigest",
            "state",
            "evidenceCount",
            "sourceCandidateCount",
            "findingCount",
        ];
        exact(value, &fields)?;
        let record = Self {
            session_id: string(value, "sessionId")?.into(),
            run_id: string(value, "runId")?.into(),
            generation: integer(value, "generation", u64::MAX)?,
            provider_id: string(value, "providerId")?.into(),
            request_digest: SourceDigest::from_hex(string(value, "requestDigest")?)
                .map_err(|_| AiError::Persistence)?,
            state: AuditState::parse(string(value, "state")?)?,
            evidence_count: integer(value, "evidenceCount", 100)? as usize,
            source_candidate_count: integer(value, "sourceCandidateCount", 100)? as usize,
            finding_count: integer(value, "findingCount", 12)? as usize,
        };
        for id in [&record.session_id, &record.run_id, &record.provider_id] {
            identifier(id).map_err(|_| AiError::Persistence)?;
        }
        if record.generation == 0
            || record.evidence_count == 0
            || record.state != AuditState::Completed && record.finding_count != 0
        {
            return Err(AiError::Persistence);
        }
        Ok(record)
    }
}

/// A single trusted host owns this bounded independent audit file. It is not a
/// credentials database, resumable approval store, cross-process lock or result
/// cache. Reopening never restores authority or runs a request.
pub struct SessionStore {
    path: PathBuf,
    records: Vec<AuditRecord>,
}

impl SessionStore {
    pub fn open(path: impl AsRef<Path>) -> Result<Self, AiError> {
        let path = path.as_ref();
        if !path.is_absolute()
            || path.file_name().and_then(|name| name.to_str()) != Some(AUDIT_FILE_NAME)
        {
            return Err(AiError::Persistence);
        }
        check_path(path)?;
        let records = match std::fs::symlink_metadata(path) {
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => Vec::new(),
            Err(_) => return Err(AiError::Persistence),
            Ok(_) => {
                let value =
                    aps_json::read(path, audit_limits()).map_err(|_| AiError::Persistence)?;
                exact(&value, &["schemaVersion", "records"])?;
                if integer(&value, "schemaVersion", 1)? != 1 {
                    return Err(AiError::Persistence);
                }
                let values = value
                    .get("records")
                    .and_then(Value::as_array)
                    .ok_or(AiError::Persistence)?;
                if values.len() > MAX_RECORDS {
                    return Err(AiError::Persistence);
                }
                let records: Vec<_> = values
                    .iter()
                    .map(AuditRecord::parse)
                    .collect::<Result<_, _>>()?;
                let mut identities = std::collections::BTreeSet::new();
                if records.iter().any(|record| {
                    !identities.insert((record.session_id.as_str(), record.run_id.as_str()))
                }) {
                    return Err(AiError::Persistence);
                }
                records
            }
        };
        Ok(Self {
            path: path.into(),
            records,
        })
    }

    pub fn records(&self) -> &[AuditRecord] {
        &self.records
    }

    /// Call after explicit authorization and before transport. A previous audit
    /// row, including Running from a crash, never grants permission to retry.
    pub fn begin(&mut self, run: &AuthorizedRun) -> Result<(), AiError> {
        if run.cancellation.is_cancelled() {
            return Err(AiError::Cancelled);
        }
        if Instant::now() >= run.expires {
            return Err(AiError::ApprovalRequired);
        }
        if self.records.len() >= MAX_RECORDS
            || self.records.iter().any(|record| {
                record.session_id == run.prepared.session_id()
                    && record.run_id == run.prepared.run_id()
            })
        {
            return Err(AiError::Persistence);
        }
        let mut records = self.records.clone();
        records.push(AuditRecord {
            session_id: run.prepared.session_id().into(),
            run_id: run.prepared.run_id().into(),
            generation: run.prepared.generation(),
            provider_id: run.prepared.provider.id().into(),
            request_digest: run.prepared.request_digest(),
            state: AuditState::Running,
            evidence_count: run.prepared.evidence_count(),
            source_candidate_count: run.prepared.request.candidates.len(),
            finding_count: 0,
        });
        self.replace(records)
    }

    pub fn complete(&mut self, result: &AnalysisResult) -> Result<(), AiError> {
        self.transition(
            &result.session_id,
            &result.run_id,
            result.generation,
            AuditState::Completed,
            result.findings.len(),
        )
    }

    pub fn fail(
        &mut self,
        session: &str,
        run: &str,
        generation: u64,
        error: AiError,
    ) -> Result<(), AiError> {
        let state = if matches!(error, AiError::Cancelled | AiError::StaleRun) {
            AuditState::Cancelled
        } else {
            AuditState::Failed
        };
        self.transition(session, run, generation, state, 0)
    }

    /// Removes only the explicitly selected audit metadata, never source files,
    /// provider state, credentials or imported performance artifacts.
    pub fn delete_session_metadata(&mut self, session: &str) -> Result<(), AiError> {
        identifier(session)?;
        let records = self
            .records
            .iter()
            .filter(|record| record.session_id != session)
            .cloned()
            .collect();
        self.replace(records)
    }

    fn transition(
        &mut self,
        session: &str,
        run: &str,
        generation: u64,
        state: AuditState,
        count: usize,
    ) -> Result<(), AiError> {
        if count > 12 {
            return Err(AiError::Persistence);
        }
        let mut records = self.records.clone();
        let record = records
            .iter_mut()
            .find(|record| {
                record.session_id == session
                    && record.run_id == run
                    && record.generation == generation
            })
            .ok_or(AiError::Persistence)?;
        if record.state != AuditState::Running {
            return Err(AiError::Persistence);
        }
        record.state = state;
        record.finding_count = count;
        self.replace(records)
    }

    fn replace(&mut self, records: Vec<AuditRecord>) -> Result<(), AiError> {
        check_path(&self.path)?;
        let value = super::model::object([
            ("schemaVersion", 1.into()),
            (
                "records",
                Value::Array(records.iter().map(AuditRecord::value).collect()),
            ),
        ]);
        let bytes = aps_json::to_vec(&value, audit_limits()).map_err(|_| AiError::Persistence)?;
        aps_platform::atomic_write(&self.path, &bytes).map_err(|_| AiError::Persistence)?;
        self.records = records;
        Ok(())
    }
}

fn check_path(path: &Path) -> Result<(), AiError> {
    // Inspect every existing ancestor; do not silently follow a linked legacy
    // preferences directory. This is validation, not an OS race-proof sandbox.
    for ancestor in path.ancestors() {
        match std::fs::symlink_metadata(ancestor) {
            Ok(metadata)
                if metadata.is_symlink()
                    || (ancestor == path && !metadata.is_file())
                    || (ancestor != path && !metadata.is_dir()) =>
            {
                return Err(AiError::Persistence);
            }
            Ok(_) => {}
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
            Err(_) => return Err(AiError::Persistence),
        }
    }
    Ok(())
}

fn audit_limits() -> aps_json::Limits {
    aps_json::Limits {
        max_bytes: MAX_BYTES,
        max_depth: 8,
        max_nodes: 16_000,
        max_string_bytes: 128,
        max_collection_items: MAX_RECORDS,
    }
}
fn exact(value: &Value, fields: &[&str]) -> Result<(), AiError> {
    let value = value.as_object().ok_or(AiError::Persistence)?;
    if value.len() != fields.len() || fields.iter().any(|field| !value.contains_key(*field)) {
        return Err(AiError::Persistence);
    }
    Ok(())
}
fn string<'a>(value: &'a Value, field: &str) -> Result<&'a str, AiError> {
    value
        .get(field)
        .and_then(Value::as_str)
        .ok_or(AiError::Persistence)
}
fn integer(value: &Value, field: &str, max: u64) -> Result<u64, AiError> {
    value
        .get(field)
        .and_then(Value::as_u64)
        .filter(|value| *value <= max)
        .ok_or(AiError::Persistence)
}

use crate::model::{INSTRUCTIONS, json_limits, object};
use crate::{AiError, AnalysisRequest, ProviderConfig};
use aps_contracts::SourceDigest;
use aps_json::Value;
use aps_platform::CancellationToken;
use std::collections::{BTreeMap, BTreeSet};
use std::sync::Arc;
use std::time::{Duration, Instant};

pub struct PreparedRun {
    pub(crate) request: AnalysisRequest,
    pub(crate) provider: ProviderConfig,
    pub(crate) body: Vec<u8>,
    pub(crate) digest: SourceDigest,
    pub(crate) streaming: bool,
}

impl PreparedRun {
    /// Freezes exactly the context/model/endpoint/credential reference to display
    /// in host preflight. Construction is not a user trigger or network approval.
    pub fn new(
        request: AnalysisRequest,
        provider: ProviderConfig,
        streaming: bool,
    ) -> Result<Self, AiError> {
        request.validate()?;
        let payload = aps_json::to_vec(&request.payload(), json_limits(512 * 1024))
            .map_err(|_| AiError::InputLimit)?;
        let payload = String::from_utf8(payload).map_err(|_| AiError::InvalidContext)?;
        let body = object([
            ("model", provider.model().into()),
            ("stream", streaming.into()),
            ("temperature", 0.into()),
            ("max_tokens", 8192.into()),
            ("response_format", object([("type", "json_object".into())])),
            (
                "messages",
                Value::Array(vec![
                    object([("role", "system".into()), ("content", INSTRUCTIONS.into())]),
                    object([("role", "user".into()), ("content", payload.into())]),
                ]),
            ),
        ]);
        let body =
            aps_json::to_vec(&body, json_limits(1024 * 1024)).map_err(|_| AiError::InputLimit)?;
        let frozen = object([
            ("provider", provider.id().into()),
            ("endpoint", provider.endpoint().into()),
            (
                "credentialReference",
                provider.credential_reference().as_str().into(),
            ),
            (
                "body",
                String::from_utf8(body.clone())
                    .map_err(|_| AiError::InvalidContext)?
                    .into(),
            ),
        ]);
        let frozen = aps_json::to_vec(&frozen, json_limits(2 * 1024 * 1024))
            .map_err(|_| AiError::InputLimit)?;
        let digest = SourceDigest::hash_reader(frozen.as_slice(), 2 * 1024 * 1024)
            .map_err(|_| AiError::InputLimit)?;
        Ok(Self {
            request,
            provider,
            body,
            digest,
            streaming,
        })
    }
    /// The complete immutable JSON request body, for trusted local approval UI.
    /// It contains sensitive evidence/source text, never the Authorization header.
    /// Do not put this data in logs, audit metadata or the clipboard implicitly.
    pub fn review_body(&self) -> &str {
        // Constructed exclusively by UTF-8 JSON serialization in `new`.
        std::str::from_utf8(&self.body).expect("JSON request body is UTF-8")
    }
    pub fn request_digest(&self) -> SourceDigest {
        self.digest
    }
    pub fn session_id(&self) -> &str {
        &self.request.session_id
    }
    pub fn run_id(&self) -> &str {
        &self.request.run_id
    }
    pub fn generation(&self) -> u64 {
        self.request.generation
    }
    pub fn provider(&self) -> &ProviderConfig {
        &self.provider
    }
    pub fn source_candidate_ids(&self) -> impl Iterator<Item = &str> {
        self.request
            .candidates
            .iter()
            .map(|candidate| candidate.id.as_str())
    }
    pub fn evidence_count(&self) -> usize {
        self.request.evidence.len()
    }
    /// Read-only exact frozen scope for the trusted preflight/approval UI.
    pub fn scope(&self) -> &str {
        &self.request.scope
    }
    pub fn profiler(&self) -> &str {
        &self.request.profiler
    }
    pub fn evidence(&self) -> &[crate::Evidence] {
        &self.request.evidence
    }
    pub fn source_candidates(&self) -> &[crate::SourceCandidate] {
        &self.request.candidates
    }
    pub fn is_streaming(&self) -> bool {
        self.streaming
    }
}

#[derive(Clone, Default)]
pub struct ApprovalScope {
    pub network: bool,
    pub source_upload: bool,
    pub source_candidate_ids: BTreeSet<String>,
}

impl ApprovalScope {
    pub fn network_only() -> Self {
        Self {
            network: true,
            ..Self::default()
        }
    }
    pub fn with_sources(ids: impl IntoIterator<Item = String>) -> Self {
        Self {
            network: true,
            source_upload: true,
            source_candidate_ids: ids.into_iter().collect(),
        }
    }
}

/// Host-issued, ephemeral and nonserializable. Provider text cannot construct it.
pub struct Approval {
    issuer: Arc<()>,
    sequence: u64,
    digest: SourceDigest,
    expires: Instant,
}

pub struct AuthorizedRun {
    pub(crate) prepared: PreparedRun,
    pub(crate) cancellation: CancellationToken,
    pub(crate) expires: Instant,
}

impl AuthorizedRun {
    pub fn session_id(&self) -> &str {
        self.prepared.session_id()
    }
    pub fn run_id(&self) -> &str {
        self.prepared.run_id()
    }
    pub fn generation(&self) -> u64 {
        self.prepared.generation()
    }
    pub fn request_digest(&self) -> SourceDigest {
        self.prepared.request_digest()
    }
}

/// This belongs to the trusted host lane. Prompt fields, hashes and user-visible
/// labels never grant authority: only a genuine UI approval handler may call
/// approve_explicit after presenting this frozen run and source scope.
pub struct RunCoordinator {
    active: Option<(String, u64, CancellationToken)>,
    generations: BTreeMap<String, u64>,
    approvals: BTreeMap<u64, SourceDigest>,
    next: u64,
    issuer: Arc<()>,
    authorized_run: Option<String>,
}

impl Default for RunCoordinator {
    fn default() -> Self {
        Self::new()
    }
}

impl RunCoordinator {
    pub fn new() -> Self {
        Self {
            active: None,
            generations: BTreeMap::new(),
            approvals: BTreeMap::new(),
            next: 0,
            issuer: Arc::new(()),
            authorized_run: None,
        }
    }

    pub fn activate(&mut self, session: &str, generation: u64) -> Result<(), AiError> {
        crate::identifier(session)?;
        if generation == 0
            || self
                .generations
                .get(session)
                .is_some_and(|old| generation <= *old)
            || self.generations.len() >= 1000 && !self.generations.contains_key(session)
        {
            return Err(AiError::StaleRun);
        }
        self.cancel();
        self.generations.insert(session.into(), generation);
        self.active = Some((session.into(), generation, CancellationToken::new()));
        Ok(())
    }

    pub fn cancel(&mut self) {
        if let Some((_, _, cancellation)) = &self.active {
            cancellation.cancel();
        }
        self.active = None;
        self.approvals.clear();
        self.authorized_run = None;
    }

    pub fn approve_explicit(
        &mut self,
        prepared: &PreparedRun,
        scope: ApprovalScope,
        ttl: Duration,
    ) -> Result<Approval, AiError> {
        self.match_active(prepared)?;
        if self.authorized_run.is_some() {
            return Err(AiError::StaleRun);
        }
        let expected: BTreeSet<_> = prepared.source_candidate_ids().map(str::to_owned).collect();
        if !scope.network
            || !expected.is_empty() && !scope.source_upload
            || scope.source_candidate_ids != expected
            || ttl.is_zero()
            || ttl > Duration::from_secs(300)
            || self.approvals.len() >= 128
        {
            return Err(AiError::ApprovalRequired);
        }
        let expires = Instant::now()
            .checked_add(ttl)
            .ok_or(AiError::ApprovalRequired)?;
        self.next = self.next.checked_add(1).ok_or(AiError::ApprovalRequired)?;
        self.approvals.insert(self.next, prepared.digest);
        Ok(Approval {
            issuer: self.issuer.clone(),
            sequence: self.next,
            digest: prepared.digest,
            expires,
        })
    }

    pub fn authorize(
        &mut self,
        prepared: PreparedRun,
        approval: Approval,
    ) -> Result<AuthorizedRun, AiError> {
        if !Arc::ptr_eq(&approval.issuer, &self.issuer) || self.authorized_run.is_some() {
            return Err(AiError::ApprovalRequired);
        }
        // Remove before checking so a failed/mismatched token cannot be retried.
        let issued = self
            .approvals
            .remove(&approval.sequence)
            .ok_or(AiError::ApprovalRequired)?;
        if issued != prepared.digest
            || approval.digest != prepared.digest
            || Instant::now() >= approval.expires
        {
            return Err(AiError::ApprovalRequired);
        }
        let cancellation = self.match_active(&prepared)?.clone();
        self.authorized_run = Some(prepared.run_id().into());
        self.approvals.clear();
        Ok(AuthorizedRun {
            prepared,
            cancellation,
            expires: approval.expires,
        })
    }

    /// Gate publication again after awaiting transport: a result from a closed
    /// session or superseded generation must not overwrite current UI state.
    pub fn accept_result(&self, result: &crate::AnalysisResult) -> Result<(), AiError> {
        let Some((session, generation, token)) = &self.active else {
            return Err(AiError::StaleRun);
        };
        if token.is_cancelled()
            || session != &result.session_id
            || *generation != result.generation
            || self.authorized_run.as_deref() != Some(result.run_id.as_str())
        {
            return Err(AiError::StaleRun);
        }
        Ok(())
    }

    fn match_active(&self, prepared: &PreparedRun) -> Result<&CancellationToken, AiError> {
        let Some((session, generation, cancellation)) = &self.active else {
            return Err(AiError::StaleRun);
        };
        if session != prepared.session_id()
            || *generation != prepared.generation()
            || cancellation.is_cancelled()
        {
            return Err(AiError::StaleRun);
        }
        Ok(cancellation)
    }
}

impl Drop for RunCoordinator {
    fn drop(&mut self) {
        self.cancel();
    }
}

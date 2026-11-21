//! Facade between the AI Analysis GPUI workspace/settings page and the domain.
//!
//! The runtime owns saved preferences, connection metadata, credential access,
//! conversation persistence and explicit one-shot runs. Rendering never blocks:
//! network and database work happens on the caller's background executor, and a
//! late response is only published while it still matches the active run.

use crate::conversation::{
    CONVERSATION_DATABASE_FILE_NAME, ConversationStore, ConversationTurn, StoredChatTurn,
    StoredResult, TranscriptTurn,
};
use crate::platform::{
    AiPreferences, AiSecret, AiSecretStore, AiSettingsStore, CredentialError, CredentialKind,
    ModelCatalogCache, ProviderKind, ReportFormat, ReportLanguage,
};
use crate::{AnalysisFinding, AnalysisRequest, AnalysisResult};
use std::fmt;
use std::path::{Path, PathBuf};
use std::sync::atomic::AtomicBool;
use std::sync::{Arc, Mutex};

pub use crate::task::RunStatus;
use crate::task::{AnalysisOutcome, ReportOutcome};

/// Sidebar row for one persisted conversation.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SessionSummary {
    pub id: String,
    pub title: String,
    pub updated_at_millis: i64,
    pub pinned: bool,
    pub turn_count: usize,
    pub status: Option<RunStatus>,
    pub archived: bool,
}

/// One assistant/user turn of a conversation.
#[derive(Clone)]
pub struct TurnRecord {
    pub run_id: String,
    pub generation: u64,
    pub status: RunStatus,
    pub question: Option<String>,
    pub result: Option<AnalysisResult>,
    pub timestamp_millis: i64,
    pub artifact_hashes: Vec<String>,
}

/// Read-only restore projection; restore never replays a run.
#[derive(Clone, Default)]
pub struct RestoredConversation {
    pub turns: Vec<TurnRecord>,
    pub last_run_id: Option<String>,
    pub last_status: Option<RunStatus>,
    pub artifact_hash: Option<String>,
}

/// Provider connection metadata plus credential presence (never the secret).
#[derive(Debug, Clone)]
pub struct ConnectionState {
    pub kind: ProviderKind,
    pub endpoint: String,
    pub models_endpoint: String,
    pub model: String,
    pub credential_kind: CredentialKind,
    pub revision: String,
    pub has_credential: bool,
}

/// Import result projected for the settings page.
#[derive(Debug, Clone, Default)]
pub struct ImportedConnection {
    pub endpoint: Option<String>,
    pub model: Option<String>,
    pub credential_kind: CredentialKind,
    pub has_secret: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ImportFailure {
    NotFound,
    Malformed,
    PermissionDenied,
    UnsupportedAuth,
    UnsupportedConfig,
}

impl fmt::Display for ImportFailure {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::NotFound => "E_AI_IMPORT_NOT_FOUND",
            Self::Malformed => "E_AI_IMPORT_MALFORMED",
            Self::PermissionDenied => "E_AI_IMPORT_DENIED",
            Self::UnsupportedAuth => "E_AI_IMPORT_AUTH",
            Self::UnsupportedConfig => "E_AI_IMPORT_UNSUPPORTED",
        })
    }
}

/// Evidence/source rows shown in the preflight approval card.
#[derive(Debug, Clone)]
pub struct ApprovalEvidence {
    pub id: String,
    pub summary: String,
}

#[derive(Debug, Clone, Default)]
pub struct ApprovalSource {
    pub id: String,
    pub relative_path: String,
    pub snapshot_id: Option<String>,
    pub content_digest: Option<String>,
    pub start_line: Option<u32>,
    pub end_line: Option<u32>,
    pub snippet: Option<String>,
    pub resolution_confidence: Option<String>,
    pub content_state: Option<String>,
    pub snippet_truncated: bool,
}

#[derive(Debug, Clone)]
pub struct ApprovalHistoryTurn {
    pub question: Option<String>,
    pub answer: String,
}

/// Exact frozen facts displayed before an explicit one-shot approval. This is
/// display data only: the executable payload stays inside the frozen run.
#[derive(Debug, Clone)]
pub struct ApprovalView {
    pub run_id: String,
    pub session_id: String,
    pub provider_id: String,
    pub provider_kind: ProviderKind,
    pub model: String,
    pub agent_id: String,
    pub evidence_count: usize,
    pub candidate_count: usize,
    pub payload_bytes: usize,
    pub planned_provider_calls: usize,
    pub planned_scripts: usize,
    pub report_format: ReportFormat,
    pub report_language: ReportLanguage,
    pub question: Option<String>,
    pub evidence: Vec<ApprovalEvidence>,
    pub sources: Vec<ApprovalSource>,
    pub history: Vec<ApprovalHistoryTurn>,
    pub payload_hash: String,
    pub permissions: Vec<String>,
}

/// A frozen prepared run. Only this runtime can execute it, and only once.
pub struct FrozenRun {
    pub view: ApprovalView,
    pub(crate) token: u64,
    pub(crate) conversation_id: String,
    cancellation: aps_platform::CancellationToken,
}

impl fmt::Debug for FrozenRun {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("FrozenRun")
            .field("run_id", &self.view.run_id)
            .finish()
    }
}

/// Input for prepare. The context field is the profiler-published bounded
/// evidence for evidence-bound runs; None means ordinary direct chat (aps.chat).
#[derive(Clone)]
pub struct PrepareOptions {
    pub conversation_id: String,
    pub context: Option<AnalysisRequest>,
    pub question: Option<String>,
    pub include_source: bool,
    pub direct_chat: bool,
    pub parent_turn: Option<String>,
    pub parent_conversation: Option<String>,
    pub report_format: ReportFormat,
    pub report_language: ReportLanguage,
    pub agent_id: String,
}

/// One rejected-provider failure. The code is the stable E_ identifier the
/// conversation stores; the detail is a bounded, credential-screened diagnostic
/// for the local window and is never persisted, logged or sent anywhere.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProviderFailure {
    pub code: String,
    pub detail: Option<String>,
}

impl ProviderFailure {
    pub fn new(code: impl Into<String>) -> Self {
        Self {
            code: code.into(),
            detail: None,
        }
    }

    pub fn with_detail(code: impl Into<String>, detail: impl Into<String>) -> Self {
        Self {
            code: code.into(),
            detail: Some(detail.into()),
        }
    }
}

impl From<&str> for ProviderFailure {
    fn from(code: &str) -> Self {
        Self::new(code)
    }
}

impl From<String> for ProviderFailure {
    fn from(code: String) -> Self {
        Self::new(code)
    }
}

impl fmt::Display for ProviderFailure {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.code)
    }
}

/// Errors surfaced as stable E_ codes. Only `Provider` may carry a diagnostic
/// detail, and that detail stays local to the window that showed the failure.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum RuntimeError {
    Unavailable,
    Configuration,
    Credential,
    Context,
    Prepare,
    Storage,
    Transport,
    Timeout,
    HttpStatus(u16),
    Provider(ProviderFailure),
    Cancelled,
    Run,
    Artifact,
}

impl RuntimeError {
    /// Local diagnostic for a rejected provider answer: why it was rejected and
    /// a bounded excerpt of what the model returned. Never persisted.
    pub fn detail(&self) -> Option<&str> {
        match self {
            Self::Provider(failure) => failure.detail.as_deref(),
            _ => None,
        }
    }

    /// Retryable failures are network/timeout/rate-limit cases; schema,
    /// credential, approval and configuration failures are permanent.
    pub fn retryable(&self) -> bool {
        self.run_status() == RunStatus::RetryableFailure
    }

    pub fn run_status(&self) -> RunStatus {
        match self {
            Self::Cancelled => crate::AiError::Cancelled,
            Self::Transport => crate::AiError::Transport,
            Self::Timeout => crate::AiError::Timeout,
            Self::HttpStatus(status) => crate::AiError::HttpStatus(*status),
            _ => return RunStatus::PermanentFailure,
        }
        .run_status()
    }
}

impl fmt::Display for RuntimeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::Unavailable => "E_AI_UNAVAILABLE",
            Self::Configuration => "E_AI_CONFIGURATION",
            Self::Credential => "E_AI_CREDENTIAL",
            Self::Context => "E_AI_CONTEXT",
            Self::Prepare => "E_AI_PREPARE",
            Self::Storage => "E_AI_STORAGE",
            Self::Transport => "E_AI_TRANSPORT",
            Self::Timeout => "E_PROVIDER_TIMEOUT",
            Self::HttpStatus(_) => "E_AI_HTTP_STATUS",
            Self::Provider(failure) => failure.code.as_str(),
            Self::Cancelled => "E_AI_CANCELLED",
            Self::Run => "E_AI_RUN",
            Self::Artifact => "E_AI_ARTIFACT",
        })
    }
}
impl std::error::Error for RuntimeError {}

struct ImportedSecret {
    endpoint: String,
    secret: Arc<AiSecret>,
}

/// The single facade used by the AI window and settings page.
pub struct AiRuntime {
    data_dir: PathBuf,
    settings: Mutex<AiSettingsStore>,
    secrets: Mutex<AiSecretStore>,
    catalog: ModelCatalogCache,
    conversations: Mutex<Option<ConversationStore>>,
    /// Secrets imported from local configuration and held until Save. `Arc`
    /// shares the one secret value with model discovery without making it cloneable.
    pending_imports:
        Mutex<std::collections::BTreeMap<(ProviderKind, CredentialKind), ImportedSecret>>,
    client: Mutex<Option<Arc<dyn http_client::HttpClient>>>,
    runs: Mutex<RunState>,
    next_token: std::sync::atomic::AtomicU64,
    artifacts_dir: PathBuf,
}

#[derive(Default)]
struct RunState {
    prepared: Option<PreparedSlot>,
    executing: Option<ExecutingRun>,
}

struct ExecutingRun {
    token: u64,
    cancellation: aps_platform::CancellationToken,
}

struct ExecutingRunGuard<'a> {
    runtime: &'a AiRuntime,
    run: ExecutingRun,
}

impl Drop for ExecutingRunGuard<'_> {
    fn drop(&mut self) {
        self.run.cancellation.cancel();
        if let Ok(mut runs) = self.runtime.runs.lock()
            && runs
                .executing
                .as_ref()
                .is_some_and(|run| run.token == self.run.token)
        {
            runs.executing = None;
        }
    }
}

struct PreparedSlot {
    token: u64,
    cancellation: aps_platform::CancellationToken,
    conversation_id: String,
    generation: u64,
    view: ApprovalView,
    prompt: crate::chat::Prompt,
    provider: ProviderKind,
    endpoint: String,
    model: String,
    report_format: ReportFormat,
    report_language: ReportLanguage,
    allow_artifacts: bool,
    request: AnalysisRequest,
}

/// Material for one frozen, not-yet-approved request. Kept private so the UI can
/// display the approval facts without being able to rewrite the executable body.
struct FrozenMaterial {
    prompt: crate::chat::Prompt,
    provider: ProviderKind,
    endpoint: String,
    model: String,
    report_format: ReportFormat,
    report_language: ReportLanguage,
    allow_artifacts: bool,
    request: AnalysisRequest,
}

impl PreparedSlot {
    fn take_material(self) -> FrozenMaterial {
        FrozenMaterial {
            prompt: self.prompt,
            provider: self.provider,
            endpoint: self.endpoint,
            model: self.model,
            report_format: self.report_format,
            report_language: self.report_language,
            allow_artifacts: self.allow_artifacts,
            request: self.request,
        }
    }
}

impl AiRuntime {
    /// Open Rust-owned AI state under an explicitly supplied data directory
    /// (never a Compose directory). Missing directories are created privately.
    pub fn open(data_dir: impl AsRef<Path>) -> Result<Arc<Self>, RuntimeError> {
        Self::open_with_secret_store(data_dir, AiSecretStore::platform())
    }

    /// Host-selected secret backend (the macOS Keychain in production, memory in
    /// tests). No other code path can choose where secrets live.
    pub fn open_with_secret_store(
        data_dir: impl AsRef<Path>,
        secrets: AiSecretStore,
    ) -> Result<Arc<Self>, RuntimeError> {
        let data_dir = data_dir.as_ref().to_path_buf();
        aps_platform::create_private_directory(&data_dir).map_err(|_| RuntimeError::Storage)?;
        let settings = AiSettingsStore::open(data_dir.join("ai-settings.json"))
            .map_err(|_| RuntimeError::Configuration)?;
        let conversations_dir = data_dir.join("conversations");
        aps_platform::create_private_directory(&conversations_dir)
            .map_err(|_| RuntimeError::Storage)?;
        let mut conversations =
            ConversationStore::open(conversations_dir.join(CONVERSATION_DATABASE_FILE_NAME))
                .map_err(|_| RuntimeError::Storage)?;
        let _ = conversations.recover_interrupted();
        // Compose prune-on-open: bounded count plus age window, pinned protected.
        let preferences = settings.preferences().clone();
        let older_than = crate::platform::now_millis()
            .saturating_sub(i64::from(preferences.retention_days) * 86_400_000);
        let _ = conversations.retention(
            preferences.retention_conversations as usize,
            older_than,
            true,
        );
        let artifacts_dir = data_dir.join("artifacts");
        aps_platform::create_private_directory(&artifacts_dir)
            .map_err(|_| RuntimeError::Storage)?;
        Ok(Arc::new(Self {
            data_dir,
            settings: Mutex::new(settings),
            secrets: Mutex::new(secrets),
            catalog: ModelCatalogCache::new(),
            conversations: Mutex::new(Some(conversations)),
            pending_imports: Mutex::new(std::collections::BTreeMap::new()),
            client: Mutex::new(None),
            runs: Mutex::new(RunState::default()),
            next_token: std::sync::atomic::AtomicU64::new(1),
            artifacts_dir,
        }))
    }

    pub fn set_http_client(
        &self,
        client: Arc<dyn http_client::HttpClient>,
    ) -> Result<(), RuntimeError> {
        *self.client.lock().map_err(|_| RuntimeError::Unavailable)? = Some(client);
        Ok(())
    }

    pub fn data_dir(&self) -> &Path {
        &self.data_dir
    }
    pub fn artifacts_dir(&self) -> &Path {
        &self.artifacts_dir
    }

    pub fn preferences(&self) -> AiPreferences {
        self.settings
            .lock()
            .map(|store| store.preferences().clone())
            .unwrap_or_default()
    }

    pub fn update_preferences(
        &self,
        edit: impl FnOnce(&mut AiPreferences),
    ) -> Result<AiPreferences, RuntimeError> {
        let mut store = self
            .settings
            .lock()
            .map_err(|_| RuntimeError::Unavailable)?;
        store
            .update(edit)
            .map_err(|_| RuntimeError::Configuration)?;
        Ok(store.preferences().clone())
    }

    /// Connection metadata + credential presence for the settings card.
    pub fn connection(&self, kind: ProviderKind) -> ConnectionState {
        let preferences = self.preferences();
        let connection = preferences.connection(kind);
        let has_credential = self
            .secrets
            .lock()
            .map(|store| store.has(kind, connection.credential_kind))
            .unwrap_or(false);
        ConnectionState {
            kind,
            endpoint: connection.endpoint.clone(),
            models_endpoint: connection.models_endpoint.clone(),
            model: connection.model.clone(),
            credential_kind: connection.credential_kind,
            revision: connection.revision.clone(),
            has_credential,
        }
    }

    pub fn has_credential(&self, kind: ProviderKind) -> bool {
        let credential_kind = self.preferences().connection(kind).credential_kind;
        self.secrets
            .lock()
            .map(|store| store.has(kind, credential_kind))
            .unwrap_or(false)
    }

    pub fn set_credential(
        &self,
        kind: ProviderKind,
        credential_kind: CredentialKind,
        secret: &str,
    ) -> Result<(), RuntimeError> {
        if secret.trim().is_empty() {
            return Err(RuntimeError::Credential);
        }
        let secret = AiSecret::new(secret).map_err(|_| RuntimeError::Credential)?;
        self.secrets
            .lock()
            .map_err(|_| RuntimeError::Unavailable)?
            .set(kind, credential_kind, &secret)
            .map_err(|error| match error {
                CredentialError::Invalid => RuntimeError::Credential,
                _ => RuntimeError::Unavailable,
            })
    }

    pub fn clear_credential(&self, kind: ProviderKind) -> Result<(), RuntimeError> {
        let credential_kind = self.preferences().connection(kind).credential_kind;
        self.secrets
            .lock()
            .map_err(|_| RuntimeError::Unavailable)?
            .clear(kind, credential_kind)
            .map_err(|_| RuntimeError::Unavailable)
    }

    /// Read the selected user's static Codex/Claude/openai-compatible
    /// configuration and hold any imported secret until Save consumes it. Never
    /// runs credential helpers, never reads legacy application settings and
    /// never writes a file or the credential store.
    pub fn import_local_config(
        &self,
        kind: ProviderKind,
    ) -> Result<ImportedConnection, ImportFailure> {
        let home = aps_platform::home_directory().map_err(|_| ImportFailure::NotFound)?;
        let env: std::collections::BTreeMap<String, String> = std::env::vars().collect();
        self.import_local_config_from(kind, &home, &env)
    }

    /// Explicit-boundary core used by the wrapper above and by tests. The home
    /// directory and environment are supplied by the caller, never read here.
    fn import_local_config_from(
        &self,
        kind: ProviderKind,
        home: &Path,
        env: &std::collections::BTreeMap<String, String>,
    ) -> Result<ImportedConnection, ImportFailure> {
        let imported =
            crate::platform::import::import_local_config(kind.local_config_source(), home, env)
                .map_err(map_import_failure)?;
        let has_secret = {
            let mut pending = self
                .pending_imports
                .lock()
                .map_err(|_| ImportFailure::PermissionDenied)?;
            // A fresh import replaces any earlier pending secret for this
            // connection, so an obsolete one can never be saved later.
            pending.retain(|(provider, _), _| *provider != kind);
            match imported.api_key {
                Some(secret) => {
                    pending.insert(
                        (kind, imported.credential_kind),
                        ImportedSecret {
                            endpoint: imported
                                .endpoint
                                .clone()
                                .ok_or(ImportFailure::UnsupportedConfig)?,
                            secret: Arc::new(secret),
                        },
                    );
                    true
                }
                None => false,
            }
        };
        Ok(ImportedConnection {
            endpoint: imported.endpoint,
            model: imported.model,
            credential_kind: imported.credential_kind,
            has_secret,
        })
    }

    /// Save connection metadata and an optional replacement secret, rotate the
    /// connection revision, update the active provider/model and cache models.
    pub fn save_connection(
        &self,
        kind: ProviderKind,
        endpoint: &str,
        models_endpoint: &str,
        model: &str,
        credential_kind: CredentialKind,
        api_key: Option<&str>,
        models: Option<&[String]>,
    ) -> Result<(), RuntimeError> {
        let (endpoint, models_endpoint, model) = (
            endpoint.trim().to_owned(),
            models_endpoint.trim().to_owned(),
            model.trim().to_owned(),
        );
        let mut preferences = self.preferences();
        let saved = preferences.connection(kind);
        let matches_saved =
            saved.endpoint.trim() == endpoint && saved.credential_kind == credential_kind;
        {
            let connection = preferences.connection_mut(kind);
            connection.endpoint = endpoint.clone();
            connection.models_endpoint = models_endpoint.clone();
            connection.model = model.clone();
            connection.credential_kind = credential_kind;
            connection.revision = crate::platform::rotate_revision();
        }
        preferences.active_provider = kind;
        preferences.model = model.clone();
        preferences
            .validate()
            .map_err(|_| RuntimeError::Configuration)?;
        {
            let mut pending = self
                .pending_imports
                .lock()
                .map_err(|_| RuntimeError::Unavailable)?;
            let imported = pending.get(&(kind, credential_kind));
            if let Some(api_key) = api_key {
                self.set_credential(kind, credential_kind, api_key)?;
            } else if let Some(imported) = imported.filter(|i| i.endpoint == endpoint) {
                self.secrets
                    .lock()
                    .map_err(|_| RuntimeError::Unavailable)?
                    .set(kind, credential_kind, &imported.secret)
                    .map_err(|_| RuntimeError::Credential)?;
            } else if !matches_saved {
                // Metadata-only saves are permitted, but cannot rebind an existing
                // credential to a different endpoint without an explicit replacement.
                if imported.is_some()
                    || self
                        .secrets
                        .lock()
                        .map_err(|_| RuntimeError::Unavailable)?
                        .read(kind, credential_kind)
                        .map_err(|_| RuntimeError::Credential)?
                        .is_some()
                {
                    return Err(RuntimeError::Credential);
                }
            }
            pending.retain(|(provider, _), _| *provider != kind);
        }
        let revision = preferences.connection(kind).revision.clone();
        self.settings
            .lock()
            .map_err(|_| RuntimeError::Unavailable)?
            .save(preferences)
            .map_err(|_| RuntimeError::Configuration)?;
        if let Some(models) = models {
            let _ = self.catalog.store(crate::platform::CachedCatalog {
                provider: kind,
                revision,
                endpoint,
                models: models.to_vec(),
                saved_at_millis: crate::platform::now_millis(),
            });
        }
        Ok(())
    }

    /// Cached model catalog for the current connection revision.
    pub fn cached_models(&self, kind: ProviderKind) -> Option<Vec<String>> {
        let revision = self.preferences().connection(kind).revision.clone();
        self.catalog.get(kind, &revision)
    }

    /// Discover models over the injected HTTP client. The caller runs this on a
    /// background executor; errors are stable codes, never raw bodies.
    pub async fn load_models(
        &self,
        kind: ProviderKind,
        refresh: bool,
    ) -> Result<Vec<String>, String> {
        if !refresh {
            if let Some(models) = self.cached_models(kind) {
                return Ok(models);
            }
        }
        let preferences = self.preferences();
        let connection = preferences.connection(kind).clone();
        crate::platform::validate_connection(&connection)
            .map_err(|_| "E_AI_MODEL_CATALOG_INVALID_ENDPOINT".to_owned())?;
        let client = self
            .client
            .lock()
            .map_err(|_| "E_AI_UNAVAILABLE".to_owned())?
            .clone()
            .ok_or_else(|| "E_AI_UNAVAILABLE".to_owned())?;
        let credential = self
            .secrets
            .lock()
            .map_err(|_| "E_AI_MODEL_CATALOG_CREDENTIAL".to_owned())?
            .read(kind, connection.credential_kind)
            .map_err(|_| "E_AI_MODEL_CATALOG_CREDENTIAL".to_owned())?
            .ok_or_else(|| "E_AI_MODEL_CATALOG_CREDENTIAL".to_owned())?;
        let models_endpoint = if connection.models_endpoint.trim().is_empty() {
            None
        } else {
            Some(connection.models_endpoint.as_str())
        };
        let models = crate::platform::catalog::discover(
            client,
            kind,
            &connection.endpoint,
            models_endpoint,
            connection.credential_kind,
            &credential,
        )
        .await
        .map_err(|error| error.to_string())?;
        let _ = self.catalog.store(crate::platform::CachedCatalog {
            provider: kind,
            revision: connection.revision.clone(),
            endpoint: connection.endpoint.clone(),
            models: models.clone(),
            saved_at_millis: crate::platform::now_millis(),
        });
        Ok(models)
    }

    /// Discover models for the settings page's unsaved connection draft. The
    /// draft owns the endpoint pair, credential kind and any typed key, so this
    /// never writes the saved connection or its catalog cache. A typed key wins,
    /// then a matching pending import, then the matching saved connection.
    pub async fn load_draft_models(
        &self,
        kind: ProviderKind,
        endpoint: &str,
        models_endpoint: &str,
        credential_kind: CredentialKind,
        api_key: Option<&str>,
    ) -> Result<Vec<String>, String> {
        let endpoint = endpoint.trim();
        crate::platform::validate_endpoint(endpoint)
            .map_err(|_| "E_AI_MODEL_CATALOG_INVALID_ENDPOINT".to_owned())?;
        let models_endpoint = models_endpoint.trim();
        if !models_endpoint.is_empty() {
            crate::platform::validate_endpoint(models_endpoint)
                .map_err(|_| "E_AI_MODEL_CATALOG_INVALID_ENDPOINT".to_owned())?;
        }
        // Resolve the credential before any await so no lock is held across I/O.
        let credential_error = || "E_AI_MODEL_CATALOG_CREDENTIAL".to_owned();
        let credential = if let Some(key) = api_key.map(str::trim).filter(|key| !key.is_empty()) {
            Arc::new(AiSecret::new(key).map_err(|_| credential_error())?)
        } else {
            let pending = self
                .pending_imports
                .lock()
                .map_err(|_| credential_error())?
                .get(&(kind, credential_kind))
                .filter(|i| i.endpoint == endpoint)
                .map(|i| i.secret.clone());
            if let Some(imported) = pending {
                imported
            } else {
                let preferences = self.preferences();
                let saved = preferences.connection(kind);
                if saved.endpoint.trim() != endpoint || saved.credential_kind != credential_kind {
                    return Err(credential_error());
                }
                Arc::new(
                    self.secrets
                        .lock()
                        .map_err(|_| credential_error())?
                        .read(kind, credential_kind)
                        .map_err(|_| credential_error())?
                        .ok_or_else(credential_error)?,
                )
            }
        };
        let client = self
            .client
            .lock()
            .map_err(|_| "E_AI_UNAVAILABLE".to_owned())?
            .clone()
            .ok_or_else(|| "E_AI_UNAVAILABLE".to_owned())?;
        let models = crate::platform::catalog::discover(
            client,
            kind,
            endpoint,
            (!models_endpoint.is_empty()).then_some(models_endpoint),
            credential_kind,
            &credential,
        )
        .await
        .map_err(|error| error.to_string())?;
        Ok(models)
    }

    pub fn select_model(&self, kind: ProviderKind, model: &str) -> Result<(), RuntimeError> {
        let model = model.trim();
        if model.is_empty() || model.len() > 256 || model.chars().any(char::is_control) {
            return Err(RuntimeError::Configuration);
        }
        self.update_preferences(|preferences| {
            preferences.active_provider = kind;
            preferences.model = model.to_owned();
            preferences.connection_mut(kind).model = model.to_owned();
        })?;
        Ok(())
    }

    // ------------------------------------------------------------------
    // Conversations
    // ------------------------------------------------------------------

    pub fn sessions(&self, include_archived: bool) -> Vec<SessionSummary> {
        let Ok(guard) = self.conversations.lock() else {
            return Vec::new();
        };
        let Some(store) = guard.as_ref() else {
            return Vec::new();
        };
        store.summaries(include_archived).unwrap_or_default()
    }

    pub fn transcript(&self, conversation_id: &str) -> Vec<TurnRecord> {
        let Ok(guard) = self.conversations.lock() else {
            return Vec::new();
        };
        let Some(store) = guard.as_ref() else {
            return Vec::new();
        };
        store
            .transcript(conversation_id)
            .unwrap_or_default()
            .iter()
            .map(turn_record)
            .collect()
    }

    pub fn new_conversation_id(&self) -> String {
        crate::platform::new_opaque_id("conversation")
    }

    pub fn restore(&self, conversation_id: &str) -> Option<RestoredConversation> {
        let turns = self.transcript(conversation_id);
        let last = turns.last()?;
        Some(RestoredConversation {
            last_run_id: Some(last.run_id.clone()),
            last_status: Some(last.status),
            artifact_hash: last.artifact_hashes.first().cloned(),
            turns,
        })
    }

    pub fn rename_conversation(&self, id: &str, title: &str) -> Result<(), String> {
        self.with_store_mut(|store| store.rename_conversation(id, title))
    }
    pub fn pin_conversation(&self, id: &str, pinned: bool) -> Result<(), String> {
        self.with_store_mut(|store| store.pin_conversation(id, pinned))
    }
    pub fn archive_conversation(&self, id: &str, archived: bool) -> Result<(), String> {
        self.with_store_mut(|store| store.archive_conversation(id, archived))
    }
    pub fn delete_conversation(&self, id: &str) -> Result<(), String> {
        self.with_store_mut(|store| store.delete_chat_conversation(id).map(|_| ()))
    }
    pub fn fork_conversation(
        &self,
        id: &str,
        parent_turn: Option<String>,
    ) -> Result<String, String> {
        let _ = parent_turn;
        // A fork is a new conversation with the parent recorded when the first
        // question is prepared; the sidebar only shows persisted conversations.
        let exists = self.with_store_mut(|store| store.record(id))?.is_some();
        if !exists {
            return Err("E_CONVERSATION_UNAVAILABLE".to_owned());
        }
        Ok(self.new_conversation_id())
    }
    pub fn clear_history(&self) -> Result<(), String> {
        self.with_store_mut(|store| store.clear_history())
    }

    /// Compose "Retain at most 100 conversations / 30 days": drop conversations
    /// older than the window and beyond the count, keeping pinned ones. Returns
    /// the number of removed conversations.
    pub fn retain_history(&self, max_conversations: u32, days: u32) -> Result<usize, String> {
        let older_than = crate::platform::now_millis().saturating_sub(i64::from(days) * 86_400_000);
        self.with_store_mut(|store| store.retention(max_conversations as usize, older_than, true))
    }

    fn with_store_mut<T>(
        &self,
        operation: impl FnOnce(&mut ConversationStore) -> Result<T, String>,
    ) -> Result<T, String> {
        let mut guard = self.store_mut()?;
        let store = guard.as_mut().ok_or("E_STORAGE_UNAVAILABLE")?;
        operation(store)
    }

    fn store_mut(&self) -> Result<std::sync::MutexGuard<'_, Option<ConversationStore>>, String> {
        self.conversations
            .lock()
            .map_err(|_| "E_STORAGE_UNAVAILABLE".to_owned())
    }

    fn store(&self) -> Result<std::sync::MutexGuard<'_, Option<ConversationStore>>, String> {
        self.conversations
            .lock()
            .map_err(|_| "E_STORAGE_UNAVAILABLE".to_owned())
    }

    fn persist_success(
        &self,
        conversation_id: &str,
        run_id: &str,
        generation: u64,
        result: &AnalysisResult,
        artifact_hashes: Vec<String>,
    ) -> Result<(), String> {
        let mut guard = self.store_mut()?;
        let store = guard.as_mut().ok_or("E_STORAGE_UNAVAILABLE")?;
        let mut record = store
            .record(conversation_id)?
            .ok_or("E_CONVERSATION_UNAVAILABLE")?;
        let turn = record
            .turns
            .iter_mut()
            .find(|turn| turn.run_id == run_id)
            .ok_or("E_RESULT_TURN")?;
        turn.status = RunStatus::Succeeded;
        turn.finding_ids = result
            .findings
            .iter()
            .map(|finding| finding.id.clone())
            .collect();
        let stored = StoredResult::from_analysis(
            conversation_id,
            generation,
            result,
            crate::platform::now_millis(),
            artifact_hashes,
        )?;
        store.complete(&record, &stored)
    }

    fn record_failure(
        &self,
        conversation_id: &str,
        run_id: &str,
        status: RunStatus,
        code: &str,
    ) -> Result<(), String> {
        let mut guard = self.store_mut()?;
        let store = guard.as_mut().ok_or("E_STORAGE_UNAVAILABLE")?;
        store.save_failed(conversation_id, run_id, status, code)
    }

    // ------------------------------------------------------------------
    // Runs
    // ------------------------------------------------------------------

    pub fn prepare(&self, options: PrepareOptions) -> Result<FrozenRun, RuntimeError> {
        let preferences = self.preferences();
        let provider = preferences.active_provider;
        let connection = preferences.connection(provider).clone();
        crate::platform::validate_connection(&connection)
            .map_err(|_| RuntimeError::Configuration)?;
        let model = if preferences.model.trim().is_empty() {
            connection.model.trim().to_owned()
        } else {
            preferences.model.trim().to_owned()
        };
        if model.is_empty() {
            return Err(RuntimeError::Configuration);
        }
        let agent_id = if options.direct_chat {
            "aps.chat".to_owned()
        } else if crate::platform::agent(&options.agent_id).is_some()
            && options.agent_id != "aps.chat"
        {
            options.agent_id.clone()
        } else {
            "aps.main".to_owned()
        };
        if crate::platform::agent(&agent_id).is_none() {
            return Err(RuntimeError::Configuration);
        }
        let mut effective_context = if options.direct_chat {
            None
        } else {
            options.context.clone()
        };
        if !options.include_source {
            if let Some(context) = &mut effective_context {
                context.clear_source_context();
            }
        }
        if let Some(context) = &mut effective_context {
            crate::mcp::hydrate(context).map_err(|_| RuntimeError::Context)?;
        }
        let context = effective_context.as_ref();
        let history = if context.is_some_and(AnalysisRequest::excludes_chat_history) {
            Vec::new()
        } else {
            self.conversation_context(&options.conversation_id)
        };
        let prompt = crate::chat::build(
            context,
            options.question.as_deref(),
            &history,
            options.include_source,
            options.report_language,
        )
        .map_err(|_| RuntimeError::Context)?;
        let mut request = match context {
            Some(context) => {
                let mut request = context.clone();
                request.user_question = prompt.question.clone();
                request
            }
            None => AnalysisRequest {
                session_id: prompt.session_id.clone(),
                run_id: "aps-chat".to_owned(),
                generation: 1,
                profiler: "chat".to_owned(),
                scope: "General conversation".to_owned(),
                evidence: Vec::new(),
                candidates: Vec::new(),
                user_question: prompt.question.clone(),
                mcp: None,
            },
        };
        let mut permissions = vec!["PROVIDER_NETWORK".to_owned()];
        if !options.direct_chat {
            permissions.push("READ_EVIDENCE".to_owned());
        }
        if !request.candidates.is_empty() {
            permissions.push("READ_SOURCE_CANDIDATES".to_owned());
            permissions.push("SEND_SOURCE_SNIPPETS".to_owned());
        }
        let allow_artifacts = !options.direct_chat && preferences.allow_artifacts;
        if allow_artifacts {
            permissions.push("WRITE_ARTIFACT".to_owned());
        }
        let payload = aps_json::to_vec(&prompt.payload, crate::model::json_limits(1024 * 1024))
            .map_err(|_| RuntimeError::Context)?;
        let payload_hash = crate::platform::new_opaque_id("ai-payload");
        let mut digest_material = Vec::with_capacity(payload.len() + 256);
        digest_material.extend_from_slice(b"aps-ai-run-v1\n");
        digest_material.extend_from_slice(provider.id().as_bytes());
        digest_material.push(b'\n');
        digest_material.extend_from_slice(model.as_bytes());
        digest_material.push(b'\n');
        digest_material.extend_from_slice(agent_id.as_bytes());
        digest_material.push(b'\n');
        digest_material.extend_from_slice(options.report_format.id().as_bytes());
        digest_material.push(b'\n');
        digest_material.extend_from_slice(options.report_language.id().as_bytes());
        digest_material.push(b'\n');
        digest_material.extend_from_slice(&payload);
        let digest =
            aps_contracts::SourceDigest::hash_reader(digest_material.as_slice(), 2 * 1024 * 1024)
                .map_err(|_| RuntimeError::Context)?;
        let run_id = crate::platform::new_opaque_id("ai-run");
        // Context identity excludes the question, prior dialogue and language so
        // follow-up questions stay bound to the same canonical conversation.
        let context_hash = {
            let mut material = prompt.payload.clone();
            if let aps_json::Value::Object(object) = &mut material {
                object.remove("untrustedUserQuestion");
                object.remove("untrustedConversationContext");
                object.remove("outputLanguage");
            }
            let encoded = aps_json::to_vec(&material, crate::model::json_limits(1024 * 1024))
                .map_err(|_| RuntimeError::Context)?;
            aps_contracts::SourceDigest::hash_reader(encoded.as_slice(), 2 * 1024 * 1024)
                .map_err(|_| RuntimeError::Context)?
                .to_hex()
        };
        let evidence_ids: Vec<String> = request
            .evidence
            .iter()
            .map(|item| item.id.clone())
            .collect();
        let candidate_ids: Vec<String> = request
            .candidates
            .iter()
            .map(|item| item.id.clone())
            .collect();
        // Supersession and publication share this lock, including persistence.
        let mut runs = self.runs.lock().map_err(|_| RuntimeError::Unavailable)?;
        let generation = match self.persist_prepared(
            &options.conversation_id,
            &prompt.session_id,
            &context_hash,
            options.parent_conversation.as_deref(),
            &run_id,
            prompt.question.as_deref(),
            evidence_ids,
            candidate_ids,
        ) {
            Ok(generation) => generation,
            Err(_) => return Err(RuntimeError::Storage),
        };
        request.run_id = run_id.clone();
        request.generation = generation;
        let view = ApprovalView {
            run_id: run_id.clone(),
            session_id: prompt.session_id.clone(),
            provider_id: provider.id().to_owned(),
            provider_kind: provider,
            model: model.clone(),
            agent_id: agent_id.clone(),
            evidence_count: prompt.evidence_count,
            candidate_count: prompt.candidate_count,
            payload_bytes: payload.len(),
            planned_provider_calls: 1,
            planned_scripts: 0,
            report_format: options.report_format,
            report_language: options.report_language,
            question: prompt.question.clone(),
            evidence: prompt
                .payload
                .get("performanceEvidence")
                .and_then(aps_json::Value::as_array)
                .map(|rows| {
                    rows.iter()
                        .map(|row| ApprovalEvidence {
                            id: row
                                .get("id")
                                .and_then(aps_json::Value::as_str)
                                .unwrap_or_default()
                                .to_owned(),
                            summary: row
                                .get("summary")
                                .and_then(aps_json::Value::as_str)
                                .unwrap_or_default()
                                .to_owned(),
                        })
                        .collect()
                })
                .unwrap_or_default(),
            sources: request
                .candidates
                .iter()
                .map(|candidate| {
                    let binding = if request.is_memory_hprof() {
                        request
                            .evidence
                            .iter()
                            .find(|evidence| {
                                evidence.kind == "memory-source-binding"
                                    && evidence
                                        .payload
                                        .get("candidateId")
                                        .and_then(aps_json::Value::as_str)
                                        == Some(candidate.id.as_str())
                            })
                            .map(|evidence| &evidence.payload)
                    } else {
                        None
                    };
                    ApprovalSource {
                        id: candidate.id.clone(),
                        relative_path: candidate.relative_path.clone(),
                        snapshot_id: Some(candidate.snapshot_id.clone()),
                        content_digest: Some(candidate.content_digest.to_hex()),
                        start_line: candidate.start_line,
                        end_line: candidate.end_line,
                        snippet: candidate.snippet.clone(),
                        resolution_confidence: binding
                            .and_then(|value| value.get("resolutionConfidence"))
                            .and_then(aps_json::Value::as_str)
                            .map(str::to_owned),
                        content_state: binding
                            .and_then(|value| value.get("sourceContentState"))
                            .and_then(aps_json::Value::as_str)
                            .map(str::to_owned),
                        snippet_truncated: binding
                            .and_then(|value| value.get("snippetTruncated"))
                            .and_then(aps_json::Value::as_bool)
                            .unwrap_or(false),
                    }
                })
                .collect(),
            history: history
                .iter()
                .map(|turn| ApprovalHistoryTurn {
                    question: turn.question.clone(),
                    answer: turn.answer.clone(),
                })
                .collect(),
            payload_hash: digest.to_hex(),
            permissions,
        };
        let _ = payload_hash;
        let token = self
            .next_token
            .fetch_add(1, std::sync::atomic::Ordering::Relaxed);
        let cancellation = aps_platform::CancellationToken::new();
        if let Some(run) = runs.executing.take() {
            run.cancellation.cancel();
        }
        if let Some(previous) = runs.prepared.replace(PreparedSlot {
            token,
            cancellation: cancellation.clone(),
            conversation_id: options.conversation_id.clone(),
            generation,
            view: view.clone(),
            prompt,
            provider,
            endpoint: connection.endpoint.clone(),
            model,
            report_format: options.report_format,
            report_language: options.report_language,
            allow_artifacts,
            request,
        }) {
            previous.cancellation.cancel();
        }
        Ok(FrozenRun {
            view,
            token,
            conversation_id: options.conversation_id,
            cancellation,
        })
    }

    fn persist_prepared(
        &self,
        conversation_id: &str,
        session_id: &str,
        context_hash: &str,
        parent_conversation_id: Option<&str>,
        run_id: &str,
        question: Option<&str>,
        evidence_ids: Vec<String>,
        candidate_ids: Vec<String>,
    ) -> Result<u64, String> {
        let mut guard = self.store_mut()?;
        let store = guard.as_mut().ok_or("E_STORAGE_UNAVAILABLE")?;
        let mut record = match store.record(conversation_id)? {
            Some(record) => record,
            None => store.create_conversation(
                conversation_id,
                session_id,
                context_hash,
                parent_conversation_id,
            )?,
        };
        if record.analysis_session_id != session_id {
            return Err("E_IMMUTABLE_CONTEXT".into());
        }
        if let Some(last) = record.turns.last() {
            if !last.status.terminal() {
                store.save_failed(
                    conversation_id,
                    &last.run_id,
                    RunStatus::Cancelled,
                    "E_AI_SUPERSEDED",
                )?;
                record = store
                    .record(conversation_id)?
                    .ok_or("E_CONVERSATION_UNAVAILABLE")?;
            }
        }
        let generation = record.turns.len() as u64 + 1;
        record.turns.push(ConversationTurn {
            run_id: run_id.to_owned(),
            generation,
            status: RunStatus::AwaitingApproval,
            evidence_ids,
            candidate_ids,
            finding_ids: Vec::new(),
            error_code: None,
        });
        let turn = StoredChatTurn::new(
            conversation_id,
            run_id,
            generation,
            question.map(str::to_owned),
            crate::platform::now_millis(),
        )?;
        store.save_prepared(&record, &turn)?;
        Ok(generation)
    }

    /// Execute a frozen run. The approval is consumed exactly once; cancellation
    /// or a newer generation prevents publication of a late response.
    pub async fn execute(&self, frozen: FrozenRun) -> Result<AnalysisResult, RuntimeError> {
        self.execute_with_outcome(frozen)
            .await
            .map(|outcome| outcome.result)
    }

    /// As execute, additionally distinguishing a disabled report, committed
    /// report and report failure without changing the approved model outcome.
    pub async fn execute_with_outcome(
        &self,
        frozen: FrozenRun,
    ) -> Result<AnalysisOutcome, RuntimeError> {
        let conversation_id = frozen.conversation_id.clone();
        let run_id = frozen.view.run_id.clone();
        let result = self.execute_inner(frozen).await;
        if let Err(error) = &result {
            let _ = self.record_failure(
                &conversation_id,
                &run_id,
                error.run_status(),
                &error.to_string(),
            );
        }
        result
    }

    async fn execute_inner(&self, frozen: FrozenRun) -> Result<AnalysisOutcome, RuntimeError> {
        let token = frozen.token;
        let cancellation = frozen.cancellation;
        let slot = {
            let mut runs = self.runs.lock().map_err(|_| RuntimeError::Unavailable)?;
            if cancellation.is_cancelled() {
                return Err(RuntimeError::Cancelled);
            }
            let slot = match runs.prepared.as_ref() {
                Some(slot) if slot.token == token => runs.prepared.take(),
                _ => None,
            }
            .ok_or(RuntimeError::Run)?;
            runs.executing = Some(ExecutingRun {
                token,
                cancellation: cancellation.clone(),
            });
            slot
        };
        let guard = ExecutingRunGuard {
            runtime: self,
            run: ExecutingRun {
                token,
                cancellation,
            },
        };
        let result = self.execute_prepared(slot, &guard.run.cancellation).await;
        match result {
            Err(_) if guard.run.cancellation.is_cancelled() => Err(RuntimeError::Cancelled),
            result => result,
        }
    }

    async fn execute_prepared(
        &self,
        slot: PreparedSlot,
        cancellation: &aps_platform::CancellationToken,
    ) -> Result<AnalysisOutcome, RuntimeError> {
        let token = slot.token;
        let conversation_id = slot.conversation_id.clone();
        let run_id = slot.view.run_id.clone();
        let generation = slot.generation;
        let material = slot.take_material();
        let client = self
            .client
            .lock()
            .map_err(|_| RuntimeError::Unavailable)?
            .clone()
            .ok_or(RuntimeError::Unavailable)?;
        let provider_kind = material.provider;
        let credential_kind = self.preferences().connection(provider_kind).credential_kind;
        let credential = self
            .secrets
            .lock()
            .map_err(|_| RuntimeError::Credential)?
            .read(provider_kind, credential_kind)
            .map_err(|_| RuntimeError::Credential)?
            .ok_or(RuntimeError::Credential)?;
        let payload = aps_json::to_vec(
            &material.prompt.payload,
            crate::model::json_limits(1024 * 1024),
        )
        .map_err(|_| RuntimeError::Context)?;
        let result = if provider_kind == ProviderKind::Claude {
            let executable = crate::claude::find_executable()
                .ok_or_else(|| RuntimeError::Provider("E_PROVIDER_MISSING_RUNTIME".into()))?;
            let work_root = self.data_dir.join("tmp");
            aps_platform::create_private_directory(&work_root)
                .map_err(|_| RuntimeError::Storage)?;
            let payload = String::from_utf8(payload).map_err(|_| RuntimeError::Context)?;
            let schema = aps_json::to_vec(
                &material.prompt.schema,
                crate::model::json_limits(1024 * 1024),
            )
            .map_err(|_| RuntimeError::Context)?;
            let schema = String::from_utf8(schema).map_err(|_| RuntimeError::Context)?;
            let structured = crate::claude::execute(
                &executable,
                &work_root,
                &material.model,
                &material.prompt.instructions,
                &payload,
                &schema,
                &material.endpoint,
                credential_kind,
                &credential,
                cancellation,
            )
            .map_err(map_claude_error)?;
            AnalysisResult::validate(structured.as_bytes(), &material.request, &material.model)
                .map_err(|error| {
                    rejected_answer(error, structured.as_bytes(), &material.model, &credential)
                })?
        } else {
            let input = String::from_utf8(payload).map_err(|_| RuntimeError::Context)?;
            let request = crate::responses::ResponsesRequest {
                endpoint: &material.endpoint,
                model: &material.model,
                instructions: &material.prompt.instructions,
                input: &input,
                schema_name: material.prompt.schema_name,
                schema: &material.prompt.schema,
            };
            let response = crate::responses::execute(&client, &credential, &request, cancellation)
                .await
                .map_err(map_responses_error)?;
            AnalysisResult::validate(response.text.as_bytes(), &material.request, &response.model)
                .map_err(|error| {
                rejected_answer(
                    error,
                    response.text.as_bytes(),
                    &response.model,
                    &credential,
                )
            })?
        };
        // Linearize success with cancellation and newer approvals before writing
        // either an artifact or the conversation result. No I/O await holds this lock.
        let mut runs = self.runs.lock().map_err(|_| RuntimeError::Unavailable)?;
        if cancellation.is_cancelled()
            || !runs
                .executing
                .as_ref()
                .is_some_and(|run| run.token == token)
        {
            return Err(RuntimeError::Cancelled);
        }
        let report = if material.allow_artifacts {
            ReportOutcome::from_publication(
                material.report_format,
                crate::report::archive(&self.artifacts_dir, &result, material.report_format),
            )
        } else {
            ReportOutcome::NotRequested
        };
        let artifact_hashes = match &report {
            ReportOutcome::Written(receipt) => vec![receipt.sha256.clone()],
            _ => Vec::new(),
        };
        let _ = material.report_language;
        let _ = self.persist_success(
            &conversation_id,
            &run_id,
            generation,
            &result,
            artifact_hashes,
        );
        runs.executing = None;
        Ok(AnalysisOutcome { result, report })
    }

    pub fn cancel(&self) {
        if let Ok(mut runs) = self.runs.lock() {
            if let Some(run) = runs.executing.take() {
                run.cancellation.cancel();
            }
            if let Some(slot) = runs.prepared.take() {
                slot.cancellation.cancel();
                let _ = self.record_failure(
                    &slot.conversation_id,
                    &slot.view.run_id,
                    RunStatus::Cancelled,
                    "E_AI_CANCELLED",
                );
            }
        }
    }

    fn conversation_context(&self, conversation_id: &str) -> Vec<crate::chat::ChatContextTurn> {
        self.transcript(conversation_id)
            .into_iter()
            .filter_map(|turn| {
                let result = turn.result?;
                if !turn.status.terminal() || turn.status != RunStatus::Succeeded {
                    return None;
                }
                Some(crate::chat::ChatContextTurn {
                    run_id: turn.run_id,
                    question: turn.question,
                    answer: take_chars(result.summary.trim(), crate::chat::MAX_HISTORY_TEXT),
                })
            })
            .rev()
            .take(crate::chat::MAX_HISTORY_TURNS)
            .collect::<Vec<_>>()
            .into_iter()
            .rev()
            .collect()
    }

    fn history(&self, conversation_id: &str) -> Vec<TurnRecord> {
        self.transcript(conversation_id)
    }

    pub fn artifact_path(&self, hash: &str) -> Option<PathBuf> {
        if hash.len() != 64 || !hash.bytes().all(|byte| byte.is_ascii_hexdigit()) {
            return None;
        }
        for extension in ["html", "md"] {
            let path = self.artifacts_dir.join(format!("{hash}.{extension}"));
            if path.is_file() {
                return Some(path);
            }
        }
        None
    }
}

fn take_chars(value: &str, limit: usize) -> String {
    value.chars().take(limit).collect()
}

fn turn_record(turn: &TranscriptTurn) -> TurnRecord {
    let result = turn.result.as_ref().map(stored_to_analysis);
    TurnRecord {
        run_id: turn.run_id.clone(),
        generation: turn.generation,
        status: turn.status,
        question: turn.question.clone(),
        result,
        timestamp_millis: turn.created_at_millis,
        artifact_hashes: turn
            .result
            .as_ref()
            .map(|stored| stored.artifact_hashes.clone())
            .unwrap_or_default(),
    }
}

fn stored_to_analysis(stored: &StoredResult) -> AnalysisResult {
    AnalysisResult {
        session_id: stored.session_id.clone(),
        run_id: stored.run_id.clone(),
        generation: stored.generation,
        model: stored.model.clone(),
        summary: stored.summary.clone(),
        findings: stored
            .findings
            .iter()
            .map(|finding| AnalysisFinding {
                id: finding.id.clone(),
                severity: finding.severity,
                title: finding.title.clone(),
                explanation: finding.explanation.clone(),
                recommendation: finding.recommendation.clone(),
                analysis_confidence: finding.confidence,
                evidence_ids: finding.evidence_ids.clone(),
                candidate_ids: finding.candidate_ids.clone(),
            })
            .collect(),
    }
}

fn map_import_failure(failure: crate::platform::import::LocalImportFailure) -> ImportFailure {
    use crate::platform::import::LocalImportFailure;
    match failure {
        LocalImportFailure::NotFound => ImportFailure::NotFound,
        LocalImportFailure::Malformed => ImportFailure::Malformed,
        LocalImportFailure::PermissionDenied => ImportFailure::PermissionDenied,
        LocalImportFailure::UnsupportedAuth => ImportFailure::UnsupportedAuth,
        LocalImportFailure::UnsupportedConfig => ImportFailure::UnsupportedConfig,
    }
}

/// Attach a bounded, credential-screened diagnostic to a rejected answer. The
/// error code stays the stable E_ identifier; only failures the user can act on
/// carry the rejected text, and it never leaves this process.
fn rejected_answer(
    error: crate::AiError,
    answer: &[u8],
    model: &str,
    credential: &AiSecret,
) -> RuntimeError {
    let code = error.to_string();
    match error {
        crate::AiError::InvalidResponse
        | crate::AiError::UnknownReference
        | crate::AiError::ResponseLimit => {
            let reason = crate::model::rejection_reason(answer);
            let excerpt = redact_credential(crate::model::rejection_excerpt(answer), credential);
            RuntimeError::Provider(ProviderFailure::with_detail(
                code,
                format!("model {model}: {reason}\n\n{excerpt}"),
            ))
        }
        crate::AiError::SecretInResponse => RuntimeError::Provider(ProviderFailure::with_detail(
            code,
            format!("model {model}: answer contained the stored credential and was discarded"),
        )),
        _ => RuntimeError::Provider(ProviderFailure::new(code)),
    }
}

/// Replace any occurrence of the stored credential in local diagnostic text.
fn redact_credential(text: String, credential: &AiSecret) -> String {
    let secret = String::from_utf8_lossy(credential.as_bytes()).into_owned();
    if secret.is_empty() || !text.contains(&secret) {
        return text;
    }
    text.replace(&secret, "[redacted]")
}

fn map_responses_error(error: crate::responses::ResponsesError) -> RuntimeError {
    use crate::responses::ResponsesError;
    match error {
        ResponsesError::Credential => RuntimeError::Credential,
        ResponsesError::Configuration => RuntimeError::Configuration,
        ResponsesError::Cancelled => RuntimeError::Cancelled,
        ResponsesError::Transport => RuntimeError::Transport,
        ResponsesError::Timeout => RuntimeError::Timeout,
        ResponsesError::HttpStatus(status) => RuntimeError::HttpStatus(status),
        ResponsesError::Limit => RuntimeError::Provider("E_AI_RESPONSE_LIMIT".into()),
        other => RuntimeError::Provider(other.to_string().into()),
    }
}

fn map_claude_error(error: crate::claude::ClaudeError) -> RuntimeError {
    use crate::claude::ClaudeError;
    match error {
        ClaudeError::Timeout => RuntimeError::Timeout,
        ClaudeError::Cancelled => RuntimeError::Cancelled,
        ClaudeError::ProcessExit => RuntimeError::Provider("E_PROVIDER_PROCESS_EXIT".into()),
        ClaudeError::Protocol => RuntimeError::Provider("E_PROVIDER_PROTOCOL".into()),
        ClaudeError::OutputLimit => RuntimeError::Provider("E_PROVIDER_OUTPUT_LIMIT".into()),
        other => RuntimeError::Provider(other.to_string().into()),
    }
}

/// Debug-only evidence that the runtime is not silently stubbed once the
/// implementation lands; never used to change product behavior.
pub fn runtime_available() -> bool {
    !cfg!(test)
}

static _RUNNING: AtomicBool = AtomicBool::new(false);

#[cfg(test)]
mod tests {
    use super::*;
    use http_client::{AsyncBody, Request, Response, Url};

    fn workspace_runtime() -> (aps_platform::TempWorkspace, Arc<AiRuntime>) {
        let root = std::env::temp_dir().canonicalize().unwrap();
        let workspace = aps_platform::TempWorkspace::create(&root).unwrap();
        let runtime =
            AiRuntime::open_with_secret_store(workspace.path().join("ai"), AiSecretStore::memory())
                .unwrap();
        (workspace, runtime)
    }

    fn memory_options(context: AnalysisRequest, include_source: bool) -> PrepareOptions {
        PrepareOptions {
            conversation_id: "memory-conversation".into(),
            context: Some(context),
            question: Some("Explain current memory findings".into()),
            include_source,
            direct_chat: false,
            parent_turn: None,
            parent_conversation: None,
            report_format: ReportFormat::Markdown,
            report_language: ReportLanguage::English,
            agent_id: "aps.main".into(),
        }
    }

    fn configure_test_provider(runtime: &AiRuntime) {
        runtime
            .update_preferences(|preferences| {
                preferences.active_provider = ProviderKind::Custom;
                preferences.model = "test-model".into();
                preferences.connection_mut(ProviderKind::Custom).model = "test-model".into();
                preferences.connection_mut(ProviderKind::Custom).endpoint =
                    "https://example.com/v1/responses".into();
            })
            .unwrap();
    }

    #[test]
    fn memory_prepare_projects_invalid_sources_before_validation_and_freeze() {
        let (_workspace, runtime) = workspace_runtime();
        configure_test_provider(&runtime);
        let mut context = crate::model::tests::source_request();
        context.candidates[0].relative_path = "../PRIVATE_SOURCE".into();
        context.evidence[2].payload = aps_json::Value::Null;
        assert!(matches!(
            runtime.prepare(memory_options(context.clone(), true)),
            Err(RuntimeError::Context)
        ));
        let frozen = runtime.prepare(memory_options(context, false)).unwrap();
        assert_eq!(frozen.view.candidate_count, 0);
        assert_eq!(frozen.view.evidence_count, 2);
        assert!(frozen.view.sources.is_empty());
        assert!(frozen.view.history.is_empty());
        assert!(
            !frozen
                .view
                .permissions
                .iter()
                .any(|p| p == "SEND_SOURCE_SNIPPETS" || p == "READ_SOURCE_CANDIDATES")
        );
        let runs = runtime.runs.lock().unwrap();
        let slot = runs.prepared.as_ref().unwrap();
        assert_eq!(slot.request.validate(), Ok(()));
        assert!(slot.request.scope.starts_with("HPROF-only"));
        assert!(
            slot.prompt
                .instructions
                .contains(crate::model::MEMORY_HPROF_INSTRUCTIONS)
        );
        let encoded =
            aps_json::to_vec(&slot.prompt.payload, crate::model::json_limits(1024 * 1024)).unwrap();
        let encoded = String::from_utf8(encoded).unwrap();
        assert!(!encoded.contains("memory-source-binding"));
        assert!(!encoded.contains("PRIVATE_SOURCE"));
        assert!(!encoded.contains("immutable source excerpts"));
        assert!(encoded.contains("memory-leak-suspect"));
    }

    #[test]
    fn memory_prepare_freezes_valid_sources_and_omits_actual_prior_history_from_preflight() {
        let (_workspace, runtime) = workspace_runtime();
        configure_test_provider(&runtime);
        let context = crate::model::tests::source_request();
        let first = runtime
            .prepare(memory_options(context.clone(), true))
            .unwrap();
        assert_eq!(first.view.candidate_count, 1);
        assert_eq!(first.view.evidence_count, 3);
        assert_eq!(first.view.sources[0].id, "source");
        let source = &first.view.sources[0];
        assert_eq!(source.snapshot_id.as_deref(), Some("snapshot"));
        assert_eq!(
            source.content_digest,
            Some(context.candidates[0].content_digest.to_hex())
        );
        assert_eq!(source.start_line, Some(10));
        assert_eq!(source.end_line, Some(11));
        assert_eq!(source.snippet, context.candidates[0].snippet);
        assert_eq!(source.resolution_confidence.as_deref(), Some("PROBABLE"));
        assert_eq!(source.content_state.as_deref(), Some("CURRENT"));
        assert!(source.snippet_truncated);
        assert!(
            first
                .view
                .permissions
                .iter()
                .any(|p| p == "SEND_SOURCE_SNIPPETS")
        );
        // Seed a successful older turn without calling a provider.
        {
            let mut store = runtime.store_mut().unwrap();
            let store = store.as_mut().unwrap();
            let mut record = store.record("memory-conversation").unwrap().unwrap();
            record.turns.last_mut().unwrap().status = RunStatus::Succeeded;
            let result = AnalysisResult {
                session_id: context.session_id.clone(),
                run_id: first.view.run_id,
                generation: 1,
                model: "test-model".into(),
                summary: "PRIVATE_HISTORY_SOURCE".into(),
                findings: Vec::new(),
            };
            let result = StoredResult::from_analysis(
                "memory-conversation",
                1,
                &result,
                crate::platform::now_millis(),
                Vec::new(),
            )
            .unwrap();
            store.complete(&record, &result).unwrap();
        }
        assert_eq!(runtime.conversation_context("memory-conversation").len(), 1);
        for include_source in [true, false] {
            let frozen = runtime
                .prepare(memory_options(context.clone(), include_source))
                .unwrap();
            assert!(frozen.view.history.is_empty());
            assert_eq!(frozen.view.candidate_count, usize::from(include_source));
            let runs = runtime.runs.lock().unwrap();
            let slot = runs.prepared.as_ref().unwrap();
            assert_eq!(slot.request.candidates.len(), usize::from(include_source));
            assert!(
                slot.prompt
                    .payload
                    .get("untrustedConversationContext")
                    .is_none()
            );
            let expected = if include_source {
                crate::model::MEMORY_HPROF_SOURCE_INSTRUCTIONS
            } else {
                crate::model::MEMORY_HPROF_INSTRUCTIONS
            };
            assert!(slot.prompt.instructions.contains(expected));
            let encoded =
                aps_json::to_vec(&slot.prompt.payload, crate::model::json_limits(1024 * 1024))
                    .unwrap();
            assert!(
                !String::from_utf8(encoded)
                    .unwrap()
                    .contains("PRIVATE_HISTORY_SOURCE")
            );
        }
        let mut stale = context;
        stale.evidence[2].payload["sourceContentState"] = "STALE".into();
        stale.evidence[2].payload["snippetTruncated"] = false.into();
        let frozen = runtime.prepare(memory_options(stale, true)).unwrap();
        assert_eq!(
            frozen.view.sources[0].content_state.as_deref(),
            Some("STALE")
        );
        assert!(!frozen.view.sources[0].snippet_truncated);
        assert_eq!(
            first.view.sources[0].content_state.as_deref(),
            Some("CURRENT")
        );
        assert!(first.view.sources[0].snippet_truncated);
    }

    #[test]
    fn phase3_preflight_is_source_free_and_uses_native_heap_contract() {
        let (_workspace, runtime) = workspace_runtime();
        configure_test_provider(&runtime);
        let context = AnalysisRequest {
            session_id: "phase3-session".into(),
            run_id: "phase3-run".into(),
            generation: 1,
            profiler: "memory".into(),
            scope: "Native heap sampled evidence".into(),
            evidence: vec![crate::Evidence {
                id: "native".into(),
                kind: "memory-native-heap".into(),
                summary: "Sampled native heap delta".into(),
                payload: crate::model::tests::native_heap_phase3_payload(),
            }],
            candidates: Vec::new(),
            user_question: Some("Explain the sampled native delta".into()),
            mcp: None,
        };
        let frozen = runtime.prepare(memory_options(context, true)).unwrap();
        assert_eq!(frozen.view.candidate_count, 0);
        assert!(frozen.view.sources.is_empty());
        assert!(frozen.view.history.is_empty());
        let runs = runtime.runs.lock().unwrap();
        let slot = runs.prepared.as_ref().unwrap();
        assert_eq!(
            slot.request.memory_phase3_mode(),
            Some("memory-native-heap")
        );
        assert!(slot.prompt.instructions.contains("not Java retained size"));
        assert!(
            slot.prompt
                .instructions
                .contains("not represent the exact live heap")
        );
    }

    fn codex_home(workspace: &aps_platform::TempWorkspace, config: &str) -> PathBuf {
        let home = workspace.path().join("home");
        let directory = home.join(".codex");
        std::fs::create_dir_all(&directory).unwrap();
        std::fs::write(directory.join("config.toml"), config).unwrap();
        home
    }

    const GATEWAY: &str = "model = \"gpt-5\"\nmodel_provider = \"custom\"\n[model_providers.custom]\nbase_url = \"https://gateway.example/v1\"\nexperimental_bearer_token = \"sk-test-1234567890\"\n";

    #[test]
    fn custom_import_holds_the_gateway_secret_until_save() {
        let (workspace, runtime) = workspace_runtime();
        let home = codex_home(&workspace, GATEWAY);
        let imported = runtime
            .import_local_config_from(
                ProviderKind::Custom,
                &home,
                &std::collections::BTreeMap::new(),
            )
            .unwrap();
        assert_eq!(
            imported.endpoint.as_deref(),
            Some("https://gateway.example/v1/responses")
        );
        assert_eq!(imported.model.as_deref(), Some("gpt-5"));
        assert_eq!(imported.credential_kind, CredentialKind::BearerToken);
        assert!(imported.has_secret);
        // The page only sees has_secret; the credential store gains the value when
        // Save consumes the pending import without a replacement key.
        assert!(!runtime.has_credential(ProviderKind::Custom));
        runtime
            .save_connection(
                ProviderKind::Custom,
                "https://gateway.example/v1/responses",
                "",
                "gpt-5",
                CredentialKind::BearerToken,
                None,
                None,
            )
            .unwrap();
        assert!(runtime.has_credential(ProviderKind::Custom));
    }

    #[test]
    fn import_without_a_local_configuration_reports_not_found() {
        let (workspace, runtime) = workspace_runtime();
        let home = workspace.path().join("home");
        std::fs::create_dir_all(&home).unwrap();
        assert_eq!(
            runtime
                .import_local_config_from(
                    ProviderKind::Custom,
                    &home,
                    &std::collections::BTreeMap::new()
                )
                .err()
                .unwrap(),
            ImportFailure::NotFound
        );
        assert!(!runtime.has_credential(ProviderKind::Custom));
    }

    #[test]
    fn a_later_import_without_a_secret_drops_the_stale_pending_one() {
        let (workspace, runtime) = workspace_runtime();
        let home = codex_home(&workspace, GATEWAY);
        assert!(
            runtime
                .import_local_config_from(
                    ProviderKind::Custom,
                    &home,
                    &std::collections::BTreeMap::new()
                )
                .unwrap()
                .has_secret
        );
        std::fs::write(
            home.join(".codex").join("config.toml"),
            "model = \"gpt-5\"\nmodel_provider = \"custom\"\n[model_providers.custom]\nbase_url = \"https://gateway.example/v1\"\n",
        )
        .unwrap();
        assert!(
            !runtime
                .import_local_config_from(
                    ProviderKind::Custom,
                    &home,
                    &std::collections::BTreeMap::new()
                )
                .unwrap()
                .has_secret
        );
        runtime
            .save_connection(
                ProviderKind::Custom,
                "https://gateway.example/v1/responses",
                "",
                "gpt-5",
                CredentialKind::BearerToken,
                None,
                None,
            )
            .unwrap();
        assert!(!runtime.has_credential(ProviderKind::Custom));
    }

    struct CatalogClient {
        body: &'static str,
        requests: Mutex<Vec<String>>,
    }

    impl CatalogClient {
        fn new(body: &'static str) -> Arc<Self> {
            Arc::new(Self {
                body,
                requests: Mutex::new(Vec::new()),
            })
        }
    }

    impl http_client::HttpClient for CatalogClient {
        fn user_agent(&self) -> Option<&http_client::http::HeaderValue> {
            None
        }
        fn proxy(&self) -> Option<&Url> {
            None
        }
        fn send(
            &self,
            request: Request<AsyncBody>,
        ) -> futures::future::BoxFuture<'static, http_client::Result<Response<AsyncBody>>> {
            self.requests.lock().unwrap().push(format!(
                "{} {} {}",
                request.method(),
                request.uri(),
                request
                    .headers()
                    .get(http_client::http::header::AUTHORIZATION)
                    .and_then(|value| value.to_str().ok())
                    .unwrap_or("-")
            ));
            let body = self.body.as_bytes().to_vec();
            Box::pin(async move {
                Ok(Response::builder()
                    .status(200)
                    .header(http_client::http::header::CONTENT_TYPE, "application/json")
                    .body(AsyncBody::from(body))
                    .unwrap())
            })
        }
    }

    #[test]
    fn execution_cleans_owned_state_on_every_exit_and_cannot_replay_approval() {
        let (_workspace, runtime) = workspace_runtime();
        configure_test_provider(&runtime);
        let success =
            r#"{"model":"test-model","output_text":"{\"summary\":\"ok\",\"findings\":[]}"}"#;
        for (body, credential, expected) in [
            (None, false, Err(RuntimeError::Unavailable)),
            (Some("{}"), false, Err(RuntimeError::Credential)),
            (
                Some("{}"),
                true,
                Err(RuntimeError::Provider("E_AI_RESPONSE_SCHEMA".into())),
            ),
            (Some(success), true, Ok(())),
        ] {
            let client = body.map(CatalogClient::new);
            if let Some(client) = &client {
                runtime.set_http_client(client.clone()).unwrap();
            }
            if credential {
                runtime
                    .set_credential(ProviderKind::Custom, CredentialKind::ApiKey, "fake-secret")
                    .unwrap();
            }
            let frozen = runtime
                .prepare(PrepareOptions {
                    conversation_id: "cleanup-conversation".into(),
                    context: None,
                    question: Some("Explain".into()),
                    include_source: false,
                    direct_chat: true,
                    parent_turn: None,
                    parent_conversation: None,
                    report_format: ReportFormat::Markdown,
                    report_language: ReportLanguage::English,
                    agent_id: "aps.chat".into(),
                })
                .unwrap();
            // Public FrozenRun is deliberately not Clone; simulate a replay here.
            let replay = FrozenRun {
                view: frozen.view.clone(),
                token: frozen.token,
                conversation_id: frozen.conversation_id.clone(),
                cancellation: frozen.cancellation.clone(),
            };
            let result = futures::executor::block_on(runtime.execute(frozen));
            assert_eq!(result.map(|_| ()), expected);
            assert!(replay.cancellation.is_cancelled());
            {
                let runs = runtime.runs.lock().unwrap();
                assert!(runs.prepared.is_none());
                assert!(runs.executing.is_none());
            }
            let requests = client
                .as_ref()
                .map(|client| client.requests.lock().unwrap().len());
            assert!(matches!(
                futures::executor::block_on(runtime.execute_inner(replay)),
                Err(RuntimeError::Cancelled)
            ));
            assert_eq!(
                client
                    .as_ref()
                    .map(|client| client.requests.lock().unwrap().len()),
                requests
            );
        }
    }

    #[test]
    fn execution_releases_token_when_result_persistence_is_unavailable() {
        let (_workspace, runtime) = workspace_runtime();
        configure_test_provider(&runtime);
        runtime
            .set_http_client(CatalogClient::new(
                r#"{"model":"test-model","output_text":"{\"summary\":\"ok\",\"findings\":[]}"}"#,
            ))
            .unwrap();
        runtime
            .set_credential(ProviderKind::Custom, CredentialKind::ApiKey, "fake-secret")
            .unwrap();
        let frozen = runtime
            .prepare(memory_options(crate::model::tests::source_request(), false))
            .unwrap();
        let cancellation = frozen.cancellation.clone();
        runtime.conversations.lock().unwrap().take();
        // Persistence remains best-effort; unavailable storage must not retain a token.
        assert!(futures::executor::block_on(runtime.execute(frozen)).is_ok());
        assert!(cancellation.is_cancelled());
        let runs = runtime.runs.lock().unwrap();
        assert!(runs.prepared.is_none());
        assert!(runs.executing.is_none());
    }

    #[test]
    fn draft_catalog_uses_typed_then_imported_then_saved_for_the_matching_endpoint() {
        let (workspace, runtime) = workspace_runtime();
        let kind = ProviderKind::Custom;
        let bearer = CredentialKind::BearerToken;
        let saved = "https://old.example/v1/responses";
        let imported = "https://gateway.example/v1/responses";
        runtime
            .save_connection(
                kind,
                saved,
                "",
                "gpt-5",
                bearer,
                Some("sk-test-saved-key"),
                None,
            )
            .unwrap();
        runtime
            .import_local_config_from(
                kind,
                &codex_home(&workspace, GATEWAY),
                &std::collections::BTreeMap::new(),
            )
            .unwrap();

        let client = CatalogClient::new(r#"{"data":[{"id":"test-model"}]}"#);
        runtime.set_http_client(client.clone()).unwrap();
        let load = |url, key| {
            futures::executor::block_on(runtime.load_draft_models(kind, url, "", bearer, key))
        };
        assert!(load(imported, None).is_ok());
        assert!(client.requests.lock().unwrap()[0].ends_with("Bearer sk-test-1234567890"));
        assert!(load(imported, Some("sk-test-typed-key")).is_ok());
        assert!(client.requests.lock().unwrap()[1].ends_with("Bearer sk-test-typed-key"));
        assert!(load(saved, None).is_ok());
        assert!(client.requests.lock().unwrap()[2].ends_with("Bearer sk-test-saved-key"));
        let edited = "https://other.example/v1/responses";
        assert_eq!(
            load(edited, None).unwrap_err(),
            "E_AI_MODEL_CATALOG_CREDENTIAL"
        );
        assert_eq!(client.requests.lock().unwrap().len(), 3);
        assert!(
            runtime
                .save_connection(kind, edited, "", "gpt-5", bearer, None, None)
                .is_err()
        );
        assert!(load(imported, None).is_ok()); // Failed Save did not consume the import.
        assert!(client.requests.lock().unwrap()[3].ends_with("Bearer sk-test-1234567890"));
    }

    #[test]
    fn imported_secret_discovers_models_for_the_unsaved_draft() {
        let (workspace, runtime) = workspace_runtime();
        runtime
            .save_connection(
                ProviderKind::Custom,
                "https://gateway.example/v1/responses",
                "",
                "gpt-5",
                CredentialKind::BearerToken,
                Some("sk-test-old-key"),
                None,
            )
            .unwrap();
        let home = codex_home(&workspace, GATEWAY);
        assert!(
            runtime
                .import_local_config_from(
                    ProviderKind::Custom,
                    &home,
                    &std::collections::BTreeMap::new()
                )
                .unwrap()
                .has_secret
        );
        let client = CatalogClient::new(r#"{"data":[{"id":"gpt-5"}]}"#);
        runtime.set_http_client(client.clone()).unwrap();
        let models = futures::executor::block_on(runtime.load_draft_models(
            ProviderKind::Custom,
            "https://gateway.example/v1/responses",
            "",
            CredentialKind::BearerToken,
            None,
        ))
        .unwrap();
        assert_eq!(models, vec!["gpt-5".to_owned()]);
        let requests = client.requests.lock().unwrap();
        assert_eq!(requests.len(), 1);
        assert!(
            requests[0].starts_with("GET https://gateway.example/v1/models"),
            "{}",
            requests[0]
        );
        assert!(requests[0].contains("Bearer sk-test-1234567890"));
        // Discovery never consumes the pending import: Save still applies it.
        runtime
            .save_connection(
                ProviderKind::Custom,
                "https://gateway.example/v1/responses",
                "",
                "gpt-5",
                CredentialKind::BearerToken,
                None,
                None,
            )
            .unwrap();
        assert!(runtime.has_credential(ProviderKind::Custom));
    }

    #[test]
    fn saving_a_connection_publishes_its_catalog_to_the_chat_menu() {
        let (_workspace, runtime) = workspace_runtime();
        let models = vec!["gpt-5".to_owned(), "o3".to_owned()];
        runtime
            .save_connection(
                ProviderKind::Custom,
                "https://gateway.example/v1/responses",
                "",
                "gpt-5",
                CredentialKind::ApiKey,
                Some("sk-test-1234567890"),
                Some(&models),
            )
            .unwrap();
        // The AI chat menu reads the saved connection's catalog before any
        // network request, and no HTTP client is needed for this path.
        let cached = futures::executor::block_on(runtime.load_models(ProviderKind::Custom, false))
            .expect("the saved catalog is readable without a provider request");
        assert_eq!(cached, models);
    }

    #[test]
    fn draft_discovery_without_a_credential_fails_closed() {
        let (_workspace, runtime) = workspace_runtime();
        runtime
            .set_http_client(CatalogClient::new(r#"{"data":[{"id":"gpt-5"}]}"#))
            .unwrap();
        let error = futures::executor::block_on(runtime.load_draft_models(
            ProviderKind::Custom,
            "https://gateway.example/v1/responses",
            "",
            CredentialKind::ApiKey,
            None,
        ))
        .err()
        .unwrap();
        assert_eq!(error, "E_AI_MODEL_CATALOG_CREDENTIAL");
    }

    #[test]
    fn rejected_answer_detail_is_bounded_screened_and_never_the_displayed_code() {
        use crate::AiError;
        let credential = AiSecret::new("sk-test-secret-value").unwrap();
        let answer = format!(
            "{{\"summary\":\"{}\",\"findings\":[],\"confidence\":0.4}}",
            "x".repeat(4_000)
        );
        let failure = rejected_answer(
            AiError::InvalidResponse,
            answer.as_bytes(),
            "test-model",
            &credential,
        );
        assert_eq!(failure.to_string(), "E_AI_RESPONSE_SCHEMA");
        assert_eq!(failure.run_status(), RunStatus::PermanentFailure);
        let detail = failure.detail().expect("rejection carries a diagnostic");
        assert!(detail.contains("test-model"));
        assert!(detail.contains("unexpected: confidence"));
        assert!(detail.contains("characters omitted"));
        assert!(detail.len() < 8_000);

        let leaked = format!(
            "{{\"summary\":\"{}\",\"findings\":[]}}",
            "sk-test-secret-value"
        );
        let failure = rejected_answer(
            AiError::InvalidResponse,
            leaked.as_bytes(),
            "test-model",
            &credential,
        );
        let detail = failure.detail().unwrap();
        assert!(!detail.contains("sk-test-secret-value"));
        assert!(detail.contains("[redacted]"));

        let discarded = rejected_answer(
            AiError::SecretInResponse,
            b"sk-test-secret-value",
            "test-model",
            &credential,
        );
        assert!(!discarded.detail().unwrap().contains("sk-test-secret-value"));

        assert_eq!(
            rejected_answer(AiError::Transport, b"ignored", "m", &credential).detail(),
            None
        );
    }
}

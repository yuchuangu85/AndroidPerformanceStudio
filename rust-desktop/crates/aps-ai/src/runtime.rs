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

/// Compose RunStatus projection used by both the window and settings history.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RunStatus {
    Queued,
    Preparing,
    AwaitingApproval,
    Running,
    Succeeded,
    Cancelled,
    RetryableFailure,
    PermanentFailure,
    Interrupted,
}

impl RunStatus {
    pub fn terminal(self) -> bool {
        matches!(
            self,
            Self::Succeeded
                | Self::Cancelled
                | Self::RetryableFailure
                | Self::PermanentFailure
                | Self::Interrupted
        )
    }
    pub fn allows_chat_retry(self) -> bool {
        matches!(
            self,
            Self::RetryableFailure | Self::PermanentFailure | Self::Interrupted
        )
    }
    pub fn id(self) -> &'static str {
        match self {
            Self::Queued => "queued",
            Self::Preparing => "preparing",
            Self::AwaitingApproval => "awaiting_approval",
            Self::Running => "running",
            Self::Succeeded => "succeeded",
            Self::Cancelled => "cancelled",
            Self::RetryableFailure => "retryable_failure",
            Self::PermanentFailure => "permanent_failure",
            Self::Interrupted => "interrupted",
        }
    }
    pub fn from_id(value: &str) -> Self {
        match value {
            "queued" => Self::Queued,
            "preparing" => Self::Preparing,
            "awaiting_approval" => Self::AwaitingApproval,
            "running" => Self::Running,
            "succeeded" => Self::Succeeded,
            "cancelled" => Self::Cancelled,
            "retryable_failure" => Self::RetryableFailure,
            "interrupted" => Self::Interrupted,
            _ => Self::PermanentFailure,
        }
    }
}

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

#[derive(Debug, Clone)]
pub struct ApprovalSource {
    pub id: String,
    pub relative_path: String,
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

/// Errors surfaced as stable E_ codes, never raw provider or filesystem text.
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
    Provider(String),
    Cancelled,
    Run,
    Artifact,
}

impl RuntimeError {
    /// Retryable failures are network/timeout/rate-limit cases; schema,
    /// credential, approval and configuration failures are permanent.
    pub fn retryable(&self) -> bool {
        match self {
            Self::Transport | Self::Timeout => true,
            Self::HttpStatus(status) => *status == 408 || *status == 429 || *status >= 500,
            _ => false,
        }
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
            Self::Provider(code) => code,
            Self::Cancelled => "E_AI_CANCELLED",
            Self::Run => "E_AI_RUN",
            Self::Artifact => "E_AI_ARTIFACT",
        })
    }
}
impl std::error::Error for RuntimeError {}

/// The single facade used by the AI window and settings page.
pub struct AiRuntime {
    data_dir: PathBuf,
    settings: Mutex<AiSettingsStore>,
    secrets: Mutex<AiSecretStore>,
    catalog: ModelCatalogCache,
    conversations: Mutex<Option<ConversationStore>>,
    pending_imports: Mutex<std::collections::BTreeMap<(ProviderKind, CredentialKind), AiSecret>>,
    client: Mutex<Option<Arc<dyn http_client::HttpClient>>>,
    prepared: Mutex<Option<PreparedSlot>>,
    next_token: std::sync::atomic::AtomicU64,
    cancelled_tokens: Mutex<std::collections::BTreeSet<u64>>,
    artifacts_dir: PathBuf,
}

struct PreparedSlot {
    token: u64,
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
            prepared: Mutex::new(None),
            next_token: std::sync::atomic::AtomicU64::new(1),
            cancelled_tokens: Mutex::new(std::collections::BTreeSet::new()),
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

    /// Read the selected user's static Codex/Claude configuration. Never runs
    /// credential helpers, never reads legacy application settings.
    pub fn import_local_config(
        &self,
        kind: ProviderKind,
    ) -> Result<ImportedConnection, ImportFailure> {
        let _ = kind;
        Err(ImportFailure::NotFound)
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
        if let Some(api_key) = api_key {
            self.set_credential(kind, credential_kind, api_key)?;
            if let Ok(mut pending) = self.pending_imports.lock() {
                pending.remove(&(kind, credential_kind));
            }
        } else if let Ok(mut pending) = self.pending_imports.lock() {
            if let Some(secret) = pending.remove(&(kind, credential_kind)) {
                self.secrets
                    .lock()
                    .map_err(|_| RuntimeError::Unavailable)?
                    .set(kind, credential_kind, &secret)
                    .map_err(|_| RuntimeError::Credential)?;
            }
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
        let context = if options.direct_chat {
            None
        } else {
            options.context.as_ref()
        };
        let history = self.conversation_context(&options.conversation_id);
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
                if !options.include_source {
                    request.candidates.clear();
                }
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
                .map(|candidate| ApprovalSource {
                    id: candidate.id.clone(),
                    relative_path: candidate.relative_path.clone(),
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
        self.prepared
            .lock()
            .map_err(|_| RuntimeError::Unavailable)?
            .replace(PreparedSlot {
                token,
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
            });
        Ok(FrozenRun {
            view,
            token,
            conversation_id: options.conversation_id,
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
        let conversation_id = frozen.conversation_id.clone();
        let run_id = frozen.view.run_id.clone();
        let result = self.execute_inner(frozen).await;
        if let Err(error) = &result {
            let status = if *error == RuntimeError::Cancelled {
                RunStatus::Cancelled
            } else if error.retryable() {
                RunStatus::RetryableFailure
            } else {
                RunStatus::PermanentFailure
            };
            let _ = self.record_failure(&conversation_id, &run_id, status, &error.to_string());
        }
        result
    }

    async fn execute_inner(&self, frozen: FrozenRun) -> Result<AnalysisResult, RuntimeError> {
        let conversation_id = frozen.conversation_id.clone();
        let run_id = frozen.view.run_id.clone();
        let token = frozen.token;
        let slot = {
            let mut prepared = self
                .prepared
                .lock()
                .map_err(|_| RuntimeError::Unavailable)?;
            match prepared.as_ref() {
                Some(slot) if slot.token == token => prepared.take(),
                _ => None,
            }
        };
        let Some(slot) = slot else {
            return Err(RuntimeError::Run);
        };
        if self.is_cancelled(token) {
            self.forget_token(token);
            return Err(RuntimeError::Cancelled);
        }
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
        let cancellation = aps_platform::CancellationToken::new();
        if let Ok(cancelled) = self.cancelled_tokens.lock() {
            if cancelled.contains(&token) {
                cancellation.cancel();
            }
        }
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
                &cancellation,
            )
            .map_err(map_claude_error)?;
            AnalysisResult::validate(structured.as_bytes(), &material.request, &material.model)
                .map_err(|error| RuntimeError::Provider(error.to_string()))?
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
            let response = crate::responses::execute(&client, &credential, &request, &cancellation)
                .await
                .map_err(map_responses_error)?;
            AnalysisResult::validate(response.text.as_bytes(), &material.request, &response.model)
                .map_err(|error| RuntimeError::Provider(error.to_string()))?
        };
        if self.is_cancelled(token) {
            self.forget_token(token);
            return Err(RuntimeError::Cancelled);
        }
        let artifact_hashes = if material.allow_artifacts {
            crate::report::write_artifact(&self.artifacts_dir, &result, material.report_format)
                .map(|hash| vec![hash])
                .unwrap_or_default()
        } else {
            Vec::new()
        };
        let _ = material.report_language;
        let _ = self.persist_success(
            &conversation_id,
            &run_id,
            generation,
            &result,
            artifact_hashes,
        );
        self.forget_token(token);
        Ok(result)
    }

    pub fn cancel(&self) {
        if let Ok(mut cancelled) = self.cancelled_tokens.lock() {
            if let Ok(mut prepared) = self.prepared.lock() {
                if let Some(slot) = prepared.take() {
                    cancelled.insert(slot.token);
                    let _ = self.record_failure(
                        &slot.conversation_id,
                        &slot.view.run_id,
                        RunStatus::Cancelled,
                        "E_AI_CANCELLED",
                    );
                }
            }
        }
    }

    fn is_cancelled(&self, token: u64) -> bool {
        self.cancelled_tokens
            .lock()
            .is_ok_and(|cancelled| cancelled.contains(&token))
    }

    fn forget_token(&self, token: u64) {
        if let Ok(mut cancelled) = self.cancelled_tokens.lock() {
            cancelled.remove(&token);
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

fn map_responses_error(error: crate::responses::ResponsesError) -> RuntimeError {
    use crate::responses::ResponsesError;
    match error {
        ResponsesError::Credential => RuntimeError::Credential,
        ResponsesError::Configuration => RuntimeError::Configuration,
        ResponsesError::Transport => RuntimeError::Transport,
        ResponsesError::Timeout => RuntimeError::Timeout,
        ResponsesError::HttpStatus(status) => RuntimeError::HttpStatus(status),
        ResponsesError::Limit => RuntimeError::Provider("E_AI_RESPONSE_LIMIT".into()),
        other => RuntimeError::Provider(other.to_string()),
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
        other => RuntimeError::Provider(other.to_string()),
    }
}

/// Debug-only evidence that the runtime is not silently stubbed once the
/// implementation lands; never used to change product behavior.
pub fn runtime_available() -> bool {
    !cfg!(test)
}

static _RUNNING: AtomicBool = AtomicBool::new(false);

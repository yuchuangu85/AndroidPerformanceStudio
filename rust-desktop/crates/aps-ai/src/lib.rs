//! Provider-neutral, explicitly approved AI analysis. No implicit config discovery,
//! credential persistence, arbitrary tools or unrestricted script execution.
mod approval;
mod catalog;
mod chat;
mod claude;
pub mod conversation;
mod model;
mod persistence;
pub mod platform;
mod report;
mod responses;
pub mod runtime;
mod sse;
mod transport;

pub use approval::{Approval, ApprovalScope, AuthorizedRun, PreparedRun, RunCoordinator};
pub use catalog::{CredentialReference, ModelDescriptor, ProviderCatalog, ProviderConfig};
pub use conversation::{
    ChatMetadata, ChatTurnRecord, ConversationRecord, ConversationStore, ConversationTurn,
    StoredChatTurn, StoredFinding, StoredResult, TranscriptTurn,
};
pub use model::{
    AnalysisFinding, AnalysisRequest, AnalysisResult, Evidence, FindingSeverity, SourceCandidate,
};
pub use persistence::{AUDIT_FILE_NAME, AuditRecord, AuditState, SessionStore};
pub use platform::catalog::{
    ModelCatalogError, claude_models_url, discover as discover_models, openai_models_url,
};
pub use platform::import::{ImportedConfig, LocalImportFailure, import_local_config};
pub use platform::{
    AgentDescriptor, AiPreferences, AiSecret, AiSecretStore, AiSettingsStore, BUILTIN_AGENTS,
    CachedCatalog, ConnectionConfig, CredentialError, CredentialKind, ModelCatalogCache,
    ProviderKind, ReportFormat, ReportLanguage, SettingsError,
};
pub use sse::{SseDecoder, StreamEvent, StreamLimits};
pub use transport::{CredentialResolver, OneShotCredential, OpenAiGateway, SecretCredential};

use std::fmt;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AiError {
    InvalidConfiguration,
    InvalidContext,
    InputLimit,
    ApprovalRequired,
    StaleRun,
    Cancelled,
    Timeout,
    CredentialUnavailable,
    Transport,
    HttpStatus(u16),
    InvalidResponse,
    ResponseLimit,
    TruncatedResponse,
    UnknownReference,
    SecretInResponse,
    Persistence,
}

impl fmt::Display for AiError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::InvalidConfiguration => "E_AI_CONFIGURATION",
            Self::InvalidContext => "E_AI_CONTEXT",
            Self::InputLimit => "E_AI_INPUT_LIMIT",
            Self::ApprovalRequired => "E_AI_APPROVAL_REQUIRED",
            Self::StaleRun => "E_AI_STALE_RUN",
            Self::Cancelled => "E_AI_CANCELLED",
            Self::Timeout => "E_AI_TIMEOUT",
            Self::CredentialUnavailable => "E_AI_CREDENTIAL_UNAVAILABLE",
            Self::Transport => "E_AI_TRANSPORT",
            Self::HttpStatus(_) => "E_AI_HTTP_STATUS",
            Self::InvalidResponse => "E_AI_RESPONSE_SCHEMA",
            Self::ResponseLimit => "E_AI_RESPONSE_LIMIT",
            Self::TruncatedResponse => "E_AI_RESPONSE_TRUNCATED",
            Self::UnknownReference => "E_AI_UNKNOWN_REFERENCE",
            Self::SecretInResponse => "E_AI_SECRET_IN_RESPONSE",
            Self::Persistence => "E_AI_PERSISTENCE",
        })
    }
}
impl std::error::Error for AiError {}

fn identifier(value: &str) -> Result<(), AiError> {
    if value.is_empty()
        || value.len() > 128
        || !value
            .bytes()
            .all(|value| value.is_ascii_alphanumeric() || b"._:-".contains(&value))
    {
        return Err(AiError::InvalidContext);
    }
    Ok(())
}

fn bounded_text(value: &str, limit: usize) -> Result<(), AiError> {
    if value.trim().is_empty()
        || value.len() > limit
        || value
            .chars()
            .any(|value| value.is_control() && !matches!(value, '\n' | '\r' | '\t'))
    {
        return Err(AiError::InvalidContext);
    }
    Ok(())
}

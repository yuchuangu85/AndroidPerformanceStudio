use crate::{validate_text, MAX_METADATA_TEXT_BYTES};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ErrorCode {
    InvalidInput,
    Unsupported,
    Io,
    CorruptArtifact,
    ResourceLimit,
    Cancelled,
    StaleGeneration,
    IdentityMismatch,
    Protocol,
    Unavailable,
    AuthorizationRequired,
}
impl ErrorCode {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::InvalidInput => "E_INVALID_INPUT",
            Self::Unsupported => "E_UNSUPPORTED",
            Self::Io => "E_IO",
            Self::CorruptArtifact => "E_CORRUPT_ARTIFACT",
            Self::ResourceLimit => "E_RESOURCE_LIMIT",
            Self::Cancelled => "E_CANCELLED",
            Self::StaleGeneration => "E_STALE_GENERATION",
            Self::IdentityMismatch => "E_IDENTITY_MISMATCH",
            Self::Protocol => "E_PROTOCOL",
            Self::Unavailable => "E_UNAVAILABLE",
            Self::AuthorizationRequired => "E_AUTHORIZATION_REQUIRED",
        }
    }
    pub fn from_code(code: &str) -> Result<Self, String> {
        [
            Self::InvalidInput,
            Self::Unsupported,
            Self::Io,
            Self::CorruptArtifact,
            Self::ResourceLimit,
            Self::Cancelled,
            Self::StaleGeneration,
            Self::IdentityMismatch,
            Self::Protocol,
            Self::Unavailable,
            Self::AuthorizationRequired,
        ]
        .into_iter()
        .find(|item| item.as_str() == code)
        .ok_or_else(|| "unknown error code".into())
    }
}
/// Only bounded, public-safe descriptions belong here. Raw stderr, provider
/// config, source content, credentials and host paths must stay out of details.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ApsError {
    pub code: ErrorCode,
    pub message: String,
    pub retryable: bool,
}
impl ApsError {
    pub fn new(
        code: ErrorCode,
        message: impl Into<String>,
        retryable: bool,
    ) -> Result<Self, String> {
        let error = Self {
            code,
            message: message.into(),
            retryable,
        };
        error.validate()?;
        Ok(error)
    }
    pub fn validate(&self) -> Result<(), String> {
        validate_text(&self.message, "error message", MAX_METADATA_TEXT_BYTES)
    }
}
impl std::fmt::Display for ApsError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        write!(formatter, "{}: {}", self.code.as_str(), self.message)
    }
}
impl std::error::Error for ApsError {}

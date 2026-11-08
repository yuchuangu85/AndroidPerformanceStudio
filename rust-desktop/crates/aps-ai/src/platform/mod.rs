//! Provider connections, saved AI preferences and credential access for the
//! AI Analysis workspace. Secrets are never part of `AiPreferences` and never
//! appear in Debug/Display output; macOS keeps them in the login Keychain,
//! other platforms keep them in memory only (Compose's InMemory fallback).

pub mod catalog;
pub mod import;

use aps_json::{Limits, Map, Value};
use std::collections::BTreeMap;
use std::fmt;
use std::path::{Path, PathBuf};
use std::time::{Duration, Instant};

/// Compose `AiProviderConnection` provider ids.
#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord, Hash)]
pub enum ProviderKind {
    Codex,
    Claude,
    Custom,
}

impl ProviderKind {
    pub fn id(self) -> &'static str {
        match self {
            Self::Codex => "codex",
            Self::Claude => "claude",
            Self::Custom => "openai",
        }
    }
    pub fn from_id(value: &str) -> Option<Self> {
        match value {
            "codex" => Some(Self::Codex),
            "claude" => Some(Self::Claude),
            "openai" | "custom" => Some(Self::Custom),
            _ => None,
        }
    }
    pub fn label(self) -> &'static str {
        match self {
            Self::Codex => "Codex",
            Self::Claude => "Claude",
            Self::Custom => "Custom (OpenAI compatible)",
        }
    }
    pub fn saves_locally(self) -> bool {
        self != Self::Custom
    }
    pub fn default_endpoint(self) -> &'static str {
        match self {
            Self::Codex | Self::Custom => "https://api.openai.com/v1/responses",
            Self::Claude => "https://api.anthropic.com",
        }
    }
    pub fn saved_models_endpoint_key(self) -> &'static str {
        match self {
            Self::Codex => "codex.modelsEndpoint",
            Self::Claude => "claude.modelsEndpoint",
            Self::Custom => "custom.modelsEndpoint",
        }
    }
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, PartialOrd, Ord)]
pub enum CredentialKind {
    #[default]
    ApiKey,
    BearerToken,
}

impl CredentialKind {
    pub fn id(self) -> &'static str {
        match self {
            Self::ApiKey => "API_KEY",
            Self::BearerToken => "BEARER_TOKEN",
        }
    }
    pub fn from_id(value: &str) -> Self {
        if value == "BEARER_TOKEN" {
            Self::BearerToken
        } else {
            Self::ApiKey
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReportFormat {
    Html,
    Markdown,
}

impl ReportFormat {
    pub fn id(self) -> &'static str {
        match self {
            Self::Html => "html",
            Self::Markdown => "markdown",
        }
    }
    pub fn from_id(value: &str) -> Self {
        if value == "markdown" {
            Self::Markdown
        } else {
            Self::Html
        }
    }
    pub fn label(self) -> &'static str {
        match self {
            Self::Html => "HTML (.html)",
            Self::Markdown => "Markdown (.md)",
        }
    }
    pub fn extension(self) -> &'static str {
        match self {
            Self::Html => "html",
            Self::Markdown => "md",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReportLanguage {
    Auto,
    English,
    SimplifiedChinese,
}

impl ReportLanguage {
    pub fn id(self) -> &'static str {
        match self {
            Self::Auto => "auto",
            Self::English => "en",
            Self::SimplifiedChinese => "zh-CN",
        }
    }
    pub fn from_id(value: &str) -> Self {
        match value {
            "en" => Self::English,
            "zh-CN" => Self::SimplifiedChinese,
            _ => Self::Auto,
        }
    }
    pub fn instruction(self) -> &'static str {
        match self {
            Self::Auto => {
                "Write the response in the language of untrustedUserQuestion when present; otherwise follow the report scope language."
            }
            Self::English => {
                "Write summary, finding titles, explanations, recommendations, and report prose in English. Preserve identifiers verbatim."
            }
            Self::SimplifiedChinese => {
                "Write summary, finding titles, explanations, recommendations, and report prose in Simplified Chinese. Preserve identifiers verbatim."
            }
        }
    }
}

/// Metadata-only connection configuration; the secret itself lives in the
/// platform keychain and is addressed by provider/credential kind.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ConnectionConfig {
    pub kind: ProviderKind,
    pub endpoint: String,
    pub models_endpoint: String,
    pub model: String,
    pub credential_kind: CredentialKind,
    pub revision: String,
}

impl ConnectionConfig {
    pub fn new(kind: ProviderKind) -> Self {
        Self {
            kind,
            endpoint: kind.default_endpoint().to_owned(),
            models_endpoint: String::new(),
            model: String::new(),
            credential_kind: CredentialKind::ApiKey,
            revision: String::new(),
        }
    }
}

/// Saved preferences shared by the AI window and the AI settings page. Every
/// field is a preference only; approvals are still one-shot and explicit.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AiPreferences {
    pub active_provider: ProviderKind,
    pub model: String,
    pub report_format: ReportFormat,
    pub report_language: ReportLanguage,
    pub allow_artifacts: bool,
    pub allow_scripts: bool,
    pub agent_id: String,
    pub retention_conversations: u32,
    pub retention_days: u32,
    pub connections: BTreeMap<ProviderKind, ConnectionConfig>,
}

impl Default for AiPreferences {
    fn default() -> Self {
        let mut connections = BTreeMap::new();
        for kind in [
            ProviderKind::Codex,
            ProviderKind::Claude,
            ProviderKind::Custom,
        ] {
            connections.insert(kind, ConnectionConfig::new(kind));
        }
        Self {
            active_provider: ProviderKind::Custom,
            model: String::new(),
            report_format: ReportFormat::Html,
            report_language: ReportLanguage::Auto,
            allow_artifacts: true,
            allow_scripts: false,
            agent_id: "aps.main".to_owned(),
            retention_conversations: 100,
            retention_days: 30,
            connections,
        }
    }
}

impl AiPreferences {
    pub fn connection(&self, kind: ProviderKind) -> &ConnectionConfig {
        // Defaults are inserted for every kind by `Default`; decoding repairs gaps.
        &self.connections[&kind]
    }
    pub fn connection_mut(&mut self, kind: ProviderKind) -> &mut ConnectionConfig {
        self.connections
            .entry(kind)
            .or_insert_with(|| ConnectionConfig::new(kind))
    }
    pub fn active_connection(&self) -> &ConnectionConfig {
        self.connection(self.active_provider)
    }
    pub fn validate(&self) -> Result<(), SettingsError> {
        if self.model.len() > 256 || self.model.chars().any(char::is_control) {
            return Err(SettingsError::Invalid("invalid model"));
        }
        if !matches!(self.agent_id.as_str(), "aps.main" | "aps.chat") {
            return Err(SettingsError::Invalid("unknown agent"));
        }
        if !(1..=10_000).contains(&self.retention_conversations) {
            return Err(SettingsError::Invalid("invalid conversation retention"));
        }
        if !(1..=3650).contains(&self.retention_days) {
            return Err(SettingsError::Invalid("invalid retention days"));
        }
        for connection in self.connections.values() {
            validate_connection(connection)?;
        }
        Ok(())
    }
}

/// Compose `validateAiConfiguration`: HTTPS (or exact loopback HTTP), no
/// userinfo/query/fragment, valid port and a bounded model id.
pub fn validate_connection(connection: &ConnectionConfig) -> Result<(), SettingsError> {
    validate_endpoint(&connection.endpoint)?;
    if !connection.models_endpoint.trim().is_empty() {
        validate_endpoint(&connection.models_endpoint)?;
    }
    if connection.endpoint.len() > 2048 || connection.models_endpoint.len() > 2048 {
        return Err(SettingsError::Invalid("endpoint too long"));
    }
    if connection.model.len() > 256 || connection.model.chars().any(char::is_control) {
        return Err(SettingsError::Invalid("invalid model"));
    }
    if connection.revision.len() > 128 {
        return Err(SettingsError::Invalid("invalid revision"));
    }
    Ok(())
}

pub fn validate_endpoint(endpoint: &str) -> Result<(), SettingsError> {
    let trimmed = endpoint.trim();
    if trimmed.is_empty() || trimmed.len() > 2048 {
        return Err(SettingsError::Invalid("endpoint required"));
    }
    let url =
        http_client::Url::parse(trimmed).map_err(|_| SettingsError::Invalid("invalid endpoint"))?;
    if !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
        || url.host_str().is_none()
    {
        return Err(SettingsError::Invalid("invalid endpoint"));
    }
    let local = matches!(
        url.host_str(),
        Some("localhost" | "127.0.0.1" | "[::1]" | "::1")
    );
    if url.scheme() != "https" && !(url.scheme() == "http" && local) {
        return Err(SettingsError::Invalid(
            "endpoint must use HTTPS or loopback HTTP",
        ));
    }
    Ok(())
}

#[derive(Debug)]
pub enum SettingsError {
    Io(std::io::Error),
    Invalid(&'static str),
    TooLarge,
}

impl fmt::Display for SettingsError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Io(error) => write!(f, "AI settings: {error}"),
            Self::Invalid(reason) => write!(f, "Invalid AI settings: {reason}"),
            Self::TooLarge => f.write_str("AI settings exceed the size budget"),
        }
    }
}
impl std::error::Error for SettingsError {}

impl From<std::io::Error> for SettingsError {
    fn from(error: std::io::Error) -> Self {
        Self::Io(error)
    }
}

const SETTINGS_LIMIT: u64 = 512 * 1024;

/// Rust-owned, versioned JSON preference file. Writes are atomic; a malformed
/// file is reported and never silently overwritten.
pub struct AiSettingsStore {
    path: PathBuf,
    preferences: AiPreferences,
}

impl AiSettingsStore {
    pub fn open(path: impl Into<PathBuf>) -> Result<Self, SettingsError> {
        let path = path.into();
        let preferences = match std::fs::read(&path) {
            Ok(bytes) if bytes.len() as u64 <= SETTINGS_LIMIT => decode_preferences(&bytes)?,
            Ok(_) => return Err(SettingsError::TooLarge),
            Err(error) if error.kind() == std::io::ErrorKind::NotFound => AiPreferences::default(),
            Err(error) => return Err(error.into()),
        };
        Ok(Self { path, preferences })
    }
    pub fn path(&self) -> &Path {
        &self.path
    }
    pub fn preferences(&self) -> &AiPreferences {
        &self.preferences
    }
    pub fn save(&mut self, preferences: AiPreferences) -> Result<(), SettingsError> {
        preferences.validate()?;
        let bytes = encode_preferences(&preferences)?;
        aps_platform::atomic_write(&self.path, &bytes)?;
        self.preferences = preferences;
        Ok(())
    }
    pub fn update(&mut self, edit: impl FnOnce(&mut AiPreferences)) -> Result<(), SettingsError> {
        let mut next = self.preferences.clone();
        edit(&mut next);
        self.save(next)
    }
}

fn json_limits() -> Limits {
    Limits {
        max_bytes: SETTINGS_LIMIT,
        max_depth: 16,
        max_nodes: 20_000,
        max_string_bytes: 8_192,
        max_collection_items: 512,
    }
}

fn encode_preferences(preferences: &AiPreferences) -> Result<Vec<u8>, SettingsError> {
    let connections = preferences
        .connections
        .values()
        .map(|connection| {
            let mut object = Map::new();
            object.insert("kind".into(), connection.kind.id().into());
            object.insert("endpoint".into(), connection.endpoint.clone().into());
            object.insert(
                "modelsEndpoint".into(),
                connection.models_endpoint.clone().into(),
            );
            object.insert("model".into(), connection.model.clone().into());
            object.insert(
                "credentialKind".into(),
                connection.credential_kind.id().into(),
            );
            object.insert("revision".into(), connection.revision.clone().into());
            Value::Object(object)
        })
        .collect();
    let mut root = Map::new();
    root.insert("version".into(), Value::from(1u64));
    root.insert(
        "activeProvider".into(),
        preferences.active_provider.id().into(),
    );
    root.insert("model".into(), preferences.model.clone().into());
    root.insert("reportFormat".into(), preferences.report_format.id().into());
    root.insert(
        "reportLanguage".into(),
        preferences.report_language.id().into(),
    );
    root.insert(
        "allowArtifacts".into(),
        Value::Bool(preferences.allow_artifacts),
    );
    root.insert(
        "allowScripts".into(),
        Value::Bool(preferences.allow_scripts),
    );
    root.insert("agent".into(), preferences.agent_id.clone().into());
    root.insert(
        "retentionConversations".into(),
        Value::from(preferences.retention_conversations),
    );
    root.insert(
        "retentionDays".into(),
        Value::from(preferences.retention_days),
    );
    root.insert("connections".into(), Value::Array(connections));
    aps_json::to_vec(&Value::Object(root), json_limits()).map_err(|_| SettingsError::TooLarge)
}

fn decode_preferences(bytes: &[u8]) -> Result<AiPreferences, SettingsError> {
    let root = aps_json::parse_slice(bytes, json_limits())
        .map_err(|_| SettingsError::Invalid("malformed preferences"))?;
    let object = root
        .as_object()
        .ok_or(SettingsError::Invalid("malformed preferences"))?;
    if object.get("version").and_then(Value::as_u64) != Some(1) {
        return Err(SettingsError::Invalid("unsupported settings version"));
    }
    let mut preferences = AiPreferences::default();
    preferences.active_provider = object
        .get("activeProvider")
        .and_then(Value::as_str)
        .and_then(ProviderKind::from_id)
        .unwrap_or(preferences.active_provider);
    preferences.model = bounded_string(object.get("model"), 256, "")?;
    preferences.report_format = object
        .get("reportFormat")
        .and_then(Value::as_str)
        .map(ReportFormat::from_id)
        .unwrap_or(preferences.report_format);
    preferences.report_language = object
        .get("reportLanguage")
        .and_then(Value::as_str)
        .map(ReportLanguage::from_id)
        .unwrap_or(preferences.report_language);
    preferences.allow_artifacts = object
        .get("allowArtifacts")
        .and_then(Value::as_bool)
        .unwrap_or(preferences.allow_artifacts);
    preferences.allow_scripts = object
        .get("allowScripts")
        .and_then(Value::as_bool)
        .unwrap_or(false);
    preferences.agent_id = bounded_string(object.get("agent"), 32, "aps.main")?;
    preferences.retention_conversations = object
        .get("retentionConversations")
        .and_then(Value::as_u64)
        .and_then(|value| u32::try_from(value).ok())
        .unwrap_or(preferences.retention_conversations);
    preferences.retention_days = object
        .get("retentionDays")
        .and_then(Value::as_u64)
        .and_then(|value| u32::try_from(value).ok())
        .unwrap_or(preferences.retention_days);
    if let Some(rows) = object.get("connections").and_then(Value::as_array) {
        for row in rows.iter().take(16) {
            let Some(row) = row.as_object() else { continue };
            let Some(kind) = row
                .get("kind")
                .and_then(Value::as_str)
                .and_then(ProviderKind::from_id)
            else {
                continue;
            };
            let connection = preferences.connection_mut(kind);
            connection.endpoint =
                bounded_string(row.get("endpoint"), 2048, kind.default_endpoint())?;
            connection.models_endpoint = bounded_string(row.get("modelsEndpoint"), 2048, "")?;
            connection.model = bounded_string(row.get("model"), 256, "")?;
            connection.credential_kind = row
                .get("credentialKind")
                .and_then(Value::as_str)
                .map(CredentialKind::from_id)
                .unwrap_or(CredentialKind::ApiKey);
            connection.revision = bounded_string(row.get("revision"), 128, "")?;
        }
    }
    preferences.validate()?;
    Ok(preferences)
}

fn bounded_string(
    value: Option<&Value>,
    limit: usize,
    default: &str,
) -> Result<String, SettingsError> {
    let Some(value) = value else {
        return Ok(default.to_owned());
    };
    let text = value
        .as_str()
        .ok_or(SettingsError::Invalid("expected a string"))?;
    if text.len() > limit || text.chars().any(char::is_control) {
        return Err(SettingsError::Invalid("string is outside bounds"));
    }
    Ok(text.to_owned())
}

// ---------------------------------------------------------------------------
// Credentials
// ---------------------------------------------------------------------------

/// An opaque secret. No Debug/Display/Clone/serialization: the only accessor
/// hands borrowed bytes to a trusted transport. Best-effort zeroization is not
/// cryptographic; OS/TLS copies are outside this crate's control.
pub struct AiSecret(Vec<u8>);

impl AiSecret {
    pub fn new(value: &str) -> Result<Self, CredentialError> {
        let value = value.trim();
        if value.len() < 8 || value.len() > 8192 || value.chars().any(char::is_control) {
            return Err(CredentialError::Invalid);
        }
        Ok(Self(value.as_bytes().to_vec()))
    }
    pub fn as_bytes(&self) -> &[u8] {
        &self.0
    }
    pub fn is_empty(&self) -> bool {
        self.0.is_empty()
    }
}

impl Drop for AiSecret {
    fn drop(&mut self) {
        self.0.fill(0);
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CredentialError {
    Invalid,
    Unavailable,
    Keychain,
}

impl fmt::Display for CredentialError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::Invalid => "E_AI_CREDENTIAL_INVALID",
            Self::Unavailable => "E_AI_CREDENTIAL_UNAVAILABLE",
            Self::Keychain => "E_AI_KEYCHAIN",
        })
    }
}
impl std::error::Error for CredentialError {}

trait SecretBackend: Send {
    fn has(&self, slot: &str) -> bool;
    fn set(&self, slot: &str, secret: &AiSecret) -> Result<(), CredentialError>;
    fn read(&self, slot: &str) -> Result<Option<AiSecret>, CredentialError>;
    fn clear(&self, slot: &str) -> Result<(), CredentialError>;
}

struct MemoryBackend {
    values: std::sync::Mutex<BTreeMap<String, Vec<u8>>>,
}

impl SecretBackend for MemoryBackend {
    fn has(&self, slot: &str) -> bool {
        self.values
            .lock()
            .is_ok_and(|values| values.get(slot).is_some_and(|value| !value.is_empty()))
    }
    fn set(&self, slot: &str, secret: &AiSecret) -> Result<(), CredentialError> {
        self.values
            .lock()
            .map_err(|_| CredentialError::Unavailable)?
            .insert(slot.to_owned(), secret.as_bytes().to_vec());
        Ok(())
    }
    fn read(&self, slot: &str) -> Result<Option<AiSecret>, CredentialError> {
        let bytes = self
            .values
            .lock()
            .map_err(|_| CredentialError::Unavailable)?
            .get(slot)
            .cloned();
        Ok(bytes
            .and_then(|bytes| String::from_utf8(bytes).ok())
            .and_then(|text| AiSecret::new(&text).ok()))
    }
    fn clear(&self, slot: &str) -> Result<(), CredentialError> {
        if let Ok(mut values) = self.values.lock() {
            if let Some(mut value) = values.remove(slot) {
                value.fill(0);
            }
        }
        Ok(())
    }
}

/// macOS login Keychain through the bounded `security` helper. Exit codes and
/// output are reduced to stable errors; secret argv exposure to the local
/// process list is an accepted limitation of this helper.
struct KeychainBackend {
    service: String,
}

const KEYCHAIN_SERVICE: &str = "com.androidperformancestudio.rust.ai";

impl KeychainBackend {
    fn run(&self, args: &[&str], ignore_failure: bool) -> Result<String, CredentialError> {
        let output = std::process::Command::new("/usr/bin/security")
            .args(args)
            .stdin(std::process::Stdio::null())
            .output()
            .map_err(|_| CredentialError::Unavailable)?;
        if !ignore_failure && !output.status.success() {
            return Err(CredentialError::Keychain);
        }
        if output.stdout.len() > 16 * 1024 {
            return Err(CredentialError::Keychain);
        }
        String::from_utf8(output.stdout).map_err(|_| CredentialError::Keychain)
    }
}

impl SecretBackend for KeychainBackend {
    fn has(&self, slot: &str) -> bool {
        self.read(slot).ok().flatten().is_some()
    }
    fn set(&self, slot: &str, secret: &AiSecret) -> Result<(), CredentialError> {
        let value = std::str::from_utf8(secret.as_bytes()).map_err(|_| CredentialError::Invalid)?;
        self.run(
            &[
                "add-generic-password",
                "-U",
                "-s",
                &self.service,
                "-a",
                slot,
                "-w",
                value,
            ],
            false,
        )
        .map(|_| ())
    }
    fn read(&self, slot: &str) -> Result<Option<AiSecret>, CredentialError> {
        let output = self.run(
            &[
                "find-generic-password",
                "-s",
                &self.service,
                "-a",
                slot,
                "-w",
            ],
            true,
        )?;
        let text = output.trim();
        if text.is_empty() {
            return Ok(None);
        }
        Ok(Some(AiSecret::new(text)?))
    }
    fn clear(&self, slot: &str) -> Result<(), CredentialError> {
        match self.run(
            &["delete-generic-password", "-s", &self.service, "-a", slot],
            false,
        ) {
            Ok(_) => Ok(()),
            Err(CredentialError::Keychain) => Ok(()),
            Err(error) => Err(error),
        }
    }
}

/// Explicit connection-scoped secret storage. Callers pass provider + credential
/// kind; no environment, file, keychain scan or legacy settings are consulted.
pub struct AiSecretStore {
    backend: Box<dyn SecretBackend>,
}

impl AiSecretStore {
    /// macOS uses the login Keychain; other platforms keep secrets in memory for
    /// this process only, matching Compose's InMemoryCredentialStore fallback.
    pub fn platform() -> Self {
        if cfg!(target_os = "macos") {
            Self {
                backend: Box::new(KeychainBackend {
                    service: KEYCHAIN_SERVICE.to_owned(),
                }),
            }
        } else {
            Self::memory()
        }
    }
    pub fn memory() -> Self {
        Self {
            backend: Box::new(MemoryBackend {
                values: std::sync::Mutex::new(BTreeMap::new()),
            }),
        }
    }
    fn slot(provider: ProviderKind, kind: CredentialKind) -> String {
        format!("{}:{}", provider.id(), kind.id())
    }
    pub fn has(&self, provider: ProviderKind, kind: CredentialKind) -> bool {
        self.backend.has(&Self::slot(provider, kind))
    }
    pub fn set(
        &mut self,
        provider: ProviderKind,
        kind: CredentialKind,
        secret: &AiSecret,
    ) -> Result<(), CredentialError> {
        self.backend.set(&Self::slot(provider, kind), secret)
    }
    pub fn read(
        &self,
        provider: ProviderKind,
        kind: CredentialKind,
    ) -> Result<Option<AiSecret>, CredentialError> {
        self.backend.read(&Self::slot(provider, kind))
    }
    pub fn clear(
        &mut self,
        provider: ProviderKind,
        kind: CredentialKind,
    ) -> Result<(), CredentialError> {
        self.backend.clear(&Self::slot(provider, kind))
    }
}

// ---------------------------------------------------------------------------
// Built-in agents (static descriptors only; no package registry or scripts)
// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy)]
pub struct AgentDescriptor {
    pub id: &'static str,
    pub label: &'static str,
    pub description: &'static str,
    pub permissions: &'static [&'static str],
}

pub const BUILTIN_AGENTS: &[AgentDescriptor] = &[
    AgentDescriptor {
        id: "aps.main",
        label: "aps.main",
        description: "Evidence-bound performance reviewer for a frozen profiler analysis.",
        permissions: &[
            "READ_EVIDENCE",
            "PROVIDER_NETWORK",
            "READ_SOURCE_CANDIDATES",
            "SEND_SOURCE_SNIPPETS",
            "WRITE_ARTIFACT",
        ],
    },
    AgentDescriptor {
        id: "aps.chat",
        label: "aps.chat",
        description: "Restricted conversational assistant without performance evidence, source or tools.",
        permissions: &["PROVIDER_NETWORK"],
    },
];

pub fn agent(id: &str) -> Option<&'static AgentDescriptor> {
    BUILTIN_AGENTS.iter().find(|agent| agent.id == id)
}

/// Monotonic-enough opaque conversation id, derived from a SHA-256 digest so the
/// UI never invents identity from time or paths alone.
pub fn new_opaque_id(domain: &str) -> String {
    use std::sync::atomic::{AtomicU64, Ordering};
    static NEXT: AtomicU64 = AtomicU64::new(0);
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |value| value.as_nanos() as u64);
    let sequence = NEXT.fetch_add(1, Ordering::Relaxed);
    let process = std::process::id() as u64;
    let mut material = Vec::with_capacity(40);
    material.extend_from_slice(domain.as_bytes());
    material.extend_from_slice(&nanos.to_le_bytes());
    material.extend_from_slice(&sequence.to_le_bytes());
    material.extend_from_slice(&process.to_le_bytes());
    aps_contracts::SourceDigest::hash_reader(material.as_slice(), 4096)
        .map(|digest| digest.to_hex().chars().take(32).collect())
        .unwrap_or_else(|_| format!("conversation-{nanos}-{sequence}"))
}

/// Small helper used by the settings page to show when a model list was saved.
pub fn now_millis() -> i64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |value| {
            i64::try_from(value.as_millis()).unwrap_or(i64::MAX)
        })
}

/// Compose keeps a connection revision so stale asynchronous catalog results
/// cannot be saved against a newer connection. Any save rotates it.
pub fn rotate_revision() -> String {
    new_opaque_id("ai-connection-revision")
}

/// Bounded catalog cache record; the HTTP discovery itself lives in `catalog`.
#[derive(Debug, Clone)]
pub struct CachedCatalog {
    pub provider: ProviderKind,
    pub revision: String,
    pub endpoint: String,
    pub models: Vec<String>,
    pub saved_at_millis: i64,
}

pub struct ModelCatalogCache {
    entries: std::sync::Mutex<BTreeMap<ProviderKind, CachedCatalog>>,
    ttl: Duration,
    loaded_at: Instant,
}

impl Default for ModelCatalogCache {
    fn default() -> Self {
        Self::new()
    }
}

impl ModelCatalogCache {
    pub fn new() -> Self {
        Self {
            entries: std::sync::Mutex::new(BTreeMap::new()),
            ttl: Duration::from_secs(24 * 60 * 60),
            loaded_at: Instant::now(),
        }
    }
    pub fn get(&self, provider: ProviderKind, revision: &str) -> Option<Vec<String>> {
        let entries = self.entries.lock().ok()?;
        let entry = entries.get(&provider)?;
        if entry.revision != revision || self.loaded_at.elapsed() > self.ttl {
            return None;
        }
        Some(entry.models.clone())
    }
    pub fn store(&self, catalog: CachedCatalog) -> Result<(), SettingsError> {
        if catalog.models.len() > 10_000 {
            return Err(SettingsError::Invalid("model catalog too large"));
        }
        if catalog.models.iter().any(|model| {
            model.is_empty() || model.len() > 256 || model.chars().any(char::is_control)
        }) {
            return Err(SettingsError::Invalid("invalid model catalog"));
        }
        self.entries
            .lock()
            .map_err(|_| SettingsError::Invalid("catalog cache poisoned"))?
            .insert(catalog.provider, catalog);
        Ok(())
    }
    pub fn clear(&self, provider: ProviderKind) {
        if let Ok(mut entries) = self.entries.lock() {
            entries.remove(&provider);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn preferences_round_trip_preserves_connections() {
        let dir = std::env::temp_dir().join(format!("aps-ai-settings-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        std::fs::create_dir_all(&dir).unwrap();
        let path = dir.join("ai.json");
        let mut store = AiSettingsStore::open(&path).unwrap();
        let mut preferences = AiPreferences::default();
        preferences.active_provider = ProviderKind::Claude;
        preferences.model = "claude-3-7-sonnet".into();
        preferences.report_format = ReportFormat::Markdown;
        preferences.report_language = ReportLanguage::English;
        preferences.connection_mut(ProviderKind::Claude).endpoint =
            "https://api.anthropic.com".into();
        store.save(preferences.clone()).unwrap();
        let reopened = AiSettingsStore::open(&path).unwrap();
        assert_eq!(reopened.preferences().active_provider, ProviderKind::Claude);
        assert_eq!(reopened.preferences().report_format, ReportFormat::Markdown);
        assert_eq!(
            reopened
                .preferences()
                .connection(ProviderKind::Claude)
                .endpoint,
            "https://api.anthropic.com"
        );
        let _ = std::fs::remove_dir_all(&dir);
    }

    #[test]
    fn connection_validation_rejects_plain_http_and_userinfo() {
        let mut connection = ConnectionConfig::new(ProviderKind::Custom);
        connection.endpoint = "http://example.com/v1/responses".into();
        assert!(validate_connection(&connection).is_err());
        connection.endpoint = "http://127.0.0.1:8123/v1/responses".into();
        assert!(validate_connection(&connection).is_ok());
        connection.endpoint = "https://user:pass@example.com/v1".into();
        assert!(validate_connection(&connection).is_err());
    }

    #[test]
    fn memory_secret_store_never_returns_after_clear() {
        let mut store = AiSecretStore::memory();
        let secret = AiSecret::new("sk-test-1234567890").unwrap();
        store
            .set(ProviderKind::Codex, CredentialKind::ApiKey, &secret)
            .unwrap();
        assert!(store.has(ProviderKind::Codex, CredentialKind::ApiKey));
        assert!(!store.has(ProviderKind::Claude, CredentialKind::ApiKey));
        store
            .clear(ProviderKind::Codex, CredentialKind::ApiKey)
            .unwrap();
        assert!(!store.has(ProviderKind::Codex, CredentialKind::ApiKey));
    }
}

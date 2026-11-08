//! Bounded read-only import of Codex and Claude local static configuration.
//!
//! Ports Compose LocalAiConfigurationImporter and its BoundedLocalConfigToml
//! helper. Only the selected user's static files are read: CODEX_HOME/config.toml
//! (+ auth.json) and CLAUDE_CONFIG_DIR/settings.json. Credential helpers are
//! never executed, no OAuth flow is started, nothing is written and no network
//! is used. All reads are symlink-rejecting, stay at or below 256 KiB and are
//! decoded as strict UTF-8. Failure taxonomy is stable: NotFound / Malformed /
//! PermissionDenied / UnsupportedAuth / UnsupportedConfig, all mapped to
//! E_AI_IMPORT_* codes.

use crate::platform::{AiSecret, CredentialKind, ProviderKind};
use aps_json::Value;
use std::collections::{BTreeMap, BTreeSet};
use std::fmt;
use std::io::Read;
use std::path::{Component, Path, PathBuf};

const MAX_FILE_BYTES: u64 = 256 * 1024;
const MAX_FIELD_LENGTH: usize = 4096;
const MAX_SECRET_LENGTH: usize = 8192;
const MAX_MODEL_LENGTH: usize = 128;
const ANTHROPIC_BASE: &str = "https://api.anthropic.com";
const OAUTH_FIELDS: [&str; 5] = [
    "tokens",
    "oauth",
    "id_token",
    "access_token",
    "refresh_token",
];

type ImportResult<T> = Result<T, LocalImportFailure>;

/// Static configuration imported from one provider's local files.
///
/// The endpoint is always validated when present; the API key is optional so an
/// imported endpoint without a key never borrows another connection's secret.
/// Debug deliberately redacts the credential.
pub struct ImportedConfig {
    pub source: ProviderKind,
    pub endpoint: Option<String>,
    pub model: Option<String>,
    pub api_key: Option<AiSecret>,
    pub credential_kind: CredentialKind,
}

impl fmt::Debug for ImportedConfig {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("ImportedConfig")
            .field("source", &self.source)
            .field("endpoint", &"<redacted>")
            .field("model", &"<redacted>")
            .field(
                "api_key",
                &self.api_key.as_ref().map_or("<none>", |_| "<redacted>"),
            )
            .field("credential_kind", &self.credential_kind)
            .finish()
    }
}

/// Stable Compose import failure taxonomy. No path, raw config text or helper
/// output is attached.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LocalImportFailure {
    NotFound,
    Malformed,
    PermissionDenied,
    UnsupportedAuth,
    UnsupportedConfig,
}

impl fmt::Display for LocalImportFailure {
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
impl std::error::Error for LocalImportFailure {}

/// Import Codex (ProviderKind::Codex) or Claude (ProviderKind::Claude) static
/// configuration. home is the selected user's home directory; env is an
/// explicit environment map so tests and callers never read the process
/// environment implicitly. ProviderKind::Custom has no local configuration.
pub fn import_local_config(
    source: ProviderKind,
    home: &Path,
    env: &BTreeMap<String, String>,
) -> Result<ImportedConfig, LocalImportFailure> {
    match source {
        ProviderKind::Codex => read_codex(home, env),
        ProviderKind::Claude => read_claude(home, env),
        ProviderKind::Custom => Err(LocalImportFailure::UnsupportedConfig),
    }
}

// ---------------------------------------------------------------------------
// Codex
// ---------------------------------------------------------------------------

fn read_codex(home: &Path, env: &BTreeMap<String, String>) -> ImportResult<ImportedConfig> {
    let directory = config_directory(home, env, "CODEX_HOME", ".codex")?;
    let root = match read_file(&directory.join("config.toml"))? {
        Some(text) => parse_toml(&text)?,
        None => TomlTable::default(),
    };
    let connection = CodexConnection::new(&root, env)?;
    let declarations =
        CodexCredentialDeclarations::new(connection.provider, connection.requires_openai_auth)?;
    let has_helper = root.has_helper() || connection.provider.is_some_and(TomlTable::has_helper);
    let credential = match declarations.read(env)? {
        Some(credential) => credential,
        None => read_official_codex_credential(&directory, &connection, has_helper, env)?,
    };
    connection.metadata.complete(credential, has_helper)
}

struct CodexConnection<'a> {
    provider: Option<&'a TomlTable>,
    requires_openai_auth: bool,
    accepts_official_credential: bool,
    reads_auth_file: bool,
    metadata: ConnectionMetadata,
}

impl<'a> CodexConnection<'a> {
    fn new(root: &'a TomlTable, env: &BTreeMap<String, String>) -> ImportResult<Self> {
        let profile = selected_profile(root)?;
        let profile_model = match profile {
            Some(profile) => profile.text("model")?,
            None => None,
        };
        let model = model(
            profile_model
                .or(root.text("model")?)
                .or_else(|| env.get("OPENAI_MODEL").cloned()),
        )?;
        let profile_provider = match profile {
            Some(profile) => profile.text("model_provider")?,
            None => None,
        };
        let provider_id = field(profile_provider.or(root.text("model_provider")?).as_deref())?
            .unwrap_or_else(|| "openai".to_owned());
        let provider = selected_provider(root, &provider_id)?;
        let configured_base = match provider {
            Some(provider) => provider.text("base_url")?,
            None => None,
        };
        let environment_base = if provider_id == "openai" && configured_base.is_none() {
            env.get("OPENAI_BASE_URL").cloned()
        } else {
            None
        };
        let explicit_base = configured_base.or_else(|| environment_base.clone());
        let base = if provider_id != "openai" && explicit_base.is_none() {
            return Err(LocalImportFailure::UnsupportedConfig);
        } else {
            endpoint(
                explicit_base
                    .as_deref()
                    .unwrap_or("https://api.openai.com/v1"),
            )?
        };
        let trimmed = base.trim_end_matches('/');
        let responses_endpoint = if trimmed.ends_with("/responses") {
            trimmed.to_owned()
        } else {
            format!("{trimmed}/responses")
        };
        let requires_openai_auth = match provider {
            Some(provider) => provider.boolean("requires_openai_auth")?.unwrap_or(false),
            None => false,
        };
        let official_endpoint = is_official_openai(&base)?;
        let accepts_official_credential = requires_openai_auth
            || provider_id == "openai" && (official_endpoint || environment_base.is_some());
        let reads_auth_file = requires_openai_auth || official_endpoint;
        Ok(Self {
            provider,
            requires_openai_auth,
            accepts_official_credential,
            reads_auth_file,
            metadata: ConnectionMetadata {
                source: ProviderKind::Codex,
                endpoint: responses_endpoint,
                model,
                explicit_endpoint: explicit_base.is_some(),
            },
        })
    }
}

fn selected_profile(root: &TomlTable) -> ImportResult<Option<&TomlTable>> {
    let Some(name) = field(root.text("profile")?.as_deref())? else {
        return Ok(None);
    };
    let Some(profiles) = root.table("profiles")? else {
        return Err(LocalImportFailure::UnsupportedConfig);
    };
    match profiles.table(&name)? {
        Some(profile) => Ok(Some(profile)),
        None => Err(LocalImportFailure::UnsupportedConfig),
    }
}

fn selected_provider<'a>(
    root: &'a TomlTable,
    provider_id: &str,
) -> ImportResult<Option<&'a TomlTable>> {
    let selected = match root.table("model_providers")? {
        Some(providers) => providers.table(provider_id)?,
        None => None,
    };
    if selected.is_none() && provider_id != "openai" {
        return Err(LocalImportFailure::UnsupportedConfig);
    }
    if let Some(provider) = selected {
        if let Some(wire_api) = provider.text("wire_api")? {
            if wire_api != "responses" {
                return Err(LocalImportFailure::UnsupportedConfig);
            }
        }
    }
    Ok(selected)
}

struct CodexCredentialDeclarations {
    env_key: Option<String>,
    literal_header: Option<String>,
    header_environment: Option<String>,
    bearer_token: Option<String>,
}

impl CodexCredentialDeclarations {
    fn new(provider: Option<&TomlTable>, requires_openai_auth: bool) -> ImportResult<Self> {
        // requires_openai_auth deliberately disables env_key, including its lookup.
        let env_key = if requires_openai_auth {
            None
        } else {
            match provider {
                Some(provider) => provider.text("env_key")?,
                None => None,
            }
        };
        let literal_header = match provider {
            Some(provider) => match provider.table("http_headers")? {
                Some(headers) => headers.authorization()?,
                None => None,
            },
            None => None,
        };
        let header_environment = match provider {
            Some(provider) => match provider.table("env_http_headers")? {
                Some(headers) => headers.authorization()?,
                None => None,
            },
            None => None,
        };
        let bearer_token = match provider {
            Some(provider) => provider.text("experimental_bearer_token")?,
            None => None,
        };
        let declared = [
            env_key.is_some(),
            literal_header.is_some(),
            header_environment.is_some(),
            bearer_token.is_some(),
        ]
        .into_iter()
        .filter(|declared| *declared)
        .count();
        // A provider's explicit bearer token does not require borrowing an
        // OpenAI login credential.
        let conflicting_openai_headers =
            requires_openai_auth && (literal_header.is_some() || header_environment.is_some());
        if declared > 1 || conflicting_openai_headers {
            return Err(LocalImportFailure::UnsupportedConfig);
        }
        Ok(Self {
            env_key,
            literal_header,
            header_environment,
            bearer_token,
        })
    }

    fn read(&self, env: &BTreeMap<String, String>) -> ImportResult<Option<Credential>> {
        if let Some(env_key) = &self.env_key {
            let name = environment_name(env_key)?;
            return Ok(Some(Credential {
                value: secret(env.get(&name).map(String::as_str))?,
                kind: CredentialKind::ApiKey,
                unsupported_auth: false,
            }));
        }
        if let Some(header_environment) = &self.header_environment {
            let name = environment_name(header_environment)?;
            return Ok(Some(Credential {
                value: authorization(env.get(&name).map(String::as_str))?,
                kind: CredentialKind::BearerToken,
                unsupported_auth: false,
            }));
        }
        if let Some(literal_header) = &self.literal_header {
            return Ok(Some(Credential {
                value: authorization(Some(literal_header))?,
                kind: CredentialKind::BearerToken,
                unsupported_auth: false,
            }));
        }
        if let Some(bearer_token) = &self.bearer_token {
            return Ok(Some(Credential {
                value: secret(Some(bearer_token))?,
                kind: CredentialKind::BearerToken,
                unsupported_auth: false,
            }));
        }
        Ok(None)
    }
}

fn read_official_codex_credential(
    directory: &Path,
    connection: &CodexConnection<'_>,
    has_helper: bool,
    env: &BTreeMap<String, String>,
) -> ImportResult<Credential> {
    if !connection.accepts_official_credential {
        return Ok(Credential::none());
    }
    if let Some(value) = secret(env.get("OPENAI_API_KEY").map(String::as_str))? {
        return Ok(Credential {
            value: Some(value),
            kind: CredentialKind::ApiKey,
            unsupported_auth: false,
        });
    }
    if connection.reads_auth_file {
        read_codex_auth_file(directory, has_helper)
    } else {
        Ok(Credential::none())
    }
}

fn read_codex_auth_file(directory: &Path, has_helper: bool) -> ImportResult<Credential> {
    let auth = match read_file(&directory.join("auth.json"))? {
        Some(text) => Some(json_object(&text)?),
        None => None,
    };
    // Keep short-circuiting: helper/OAuth markers do not require inspecting
    // other auth values.
    let mut unsupported_auth = has_helper;
    if !unsupported_auth {
        unsupported_auth = auth
            .as_ref()
            .is_some_and(|auth| OAUTH_FIELDS.iter().any(|field| auth.get(*field).is_some()));
    }
    if !unsupported_auth {
        unsupported_auth = match &auth {
            Some(auth) => json_string(auth, "auth_mode")?.as_deref() == Some("chatgpt"),
            None => false,
        };
    }
    let value = match &auth {
        Some(auth) => secret(json_string(auth, "OPENAI_API_KEY")?.as_deref())?,
        None => None,
    };
    Ok(Credential {
        value,
        kind: CredentialKind::ApiKey,
        unsupported_auth,
    })
}

// ---------------------------------------------------------------------------
// Claude
// ---------------------------------------------------------------------------

fn read_claude(home: &Path, env: &BTreeMap<String, String>) -> ImportResult<ImportedConfig> {
    let directory = config_directory(home, env, "CLAUDE_CONFIG_DIR", ".claude")?;
    let root = match read_file(&directory.join("settings.json"))? {
        Some(text) => Some(json_object(&text)?),
        None => None,
    };
    let settings = match &root {
        Some(root) => json_object_field(root, "env")?,
        None => None,
    };
    let connection_keys = [
        "ANTHROPIC_API_KEY",
        "ANTHROPIC_AUTH_TOKEN",
        "ANTHROPIC_BASE_URL",
    ];
    // Select one complete connection group; never attach an inherited process
    // key to a settings-provided gateway.
    let use_settings = settings.is_some_and(|settings| {
        connection_keys
            .iter()
            .any(|key| settings.get(*key).is_some())
    });
    let connection = |name: &str| -> ImportResult<Option<String>> {
        if use_settings {
            match settings {
                Some(settings) => json_string(settings, name),
                None => Ok(None),
            }
        } else {
            Ok(env.get(name).cloned())
        }
    };
    let explicit_base = connection("ANTHROPIC_BASE_URL")?;
    let endpoint = endpoint(explicit_base.as_deref().unwrap_or(ANTHROPIC_BASE))?;
    let settings_model = match settings {
        Some(settings) => json_string(settings, "ANTHROPIC_MODEL")?,
        None => None,
    };
    let root_model = match &root {
        Some(root) => json_string(root, "model")?,
        None => None,
    };
    let model = model(
        settings_model
            .or(root_model)
            .or_else(|| env.get("ANTHROPIC_MODEL").cloned()),
    )?;
    let metadata = ConnectionMetadata {
        source: ProviderKind::Claude,
        endpoint,
        model,
        explicit_endpoint: explicit_base.is_some(),
    };
    let token = connection("ANTHROPIC_AUTH_TOKEN")?;
    let key = if token.is_none() {
        connection("ANTHROPIC_API_KEY")?
    } else {
        None
    };
    let value = secret(token.as_deref().or(key.as_deref()))?;
    let oauth = value.as_ref().is_some_and(|value| {
        value
            .as_bytes()
            .starts_with_ignore_ascii_case(b"sk-ant-oat")
    });
    let credential = Credential {
        value: if oauth { None } else { value },
        kind: if token.is_some() {
            CredentialKind::BearerToken
        } else {
            CredentialKind::ApiKey
        },
        unsupported_auth: oauth,
    };
    let has_helper = match &root {
        Some(root) => root.get("apiKeyHelper").is_some(),
        None => false,
    };
    metadata.complete(credential, has_helper)
}

trait StartsWithIgnoreAsciiCase {
    fn starts_with_ignore_ascii_case(&self, prefix: &[u8]) -> bool;
}

impl StartsWithIgnoreAsciiCase for [u8] {
    fn starts_with_ignore_ascii_case(&self, prefix: &[u8]) -> bool {
        self.len() >= prefix.len() && self[..prefix.len()].eq_ignore_ascii_case(prefix)
    }
}

// ---------------------------------------------------------------------------
// Shared connection metadata / credential
// ---------------------------------------------------------------------------

struct ConnectionMetadata {
    source: ProviderKind,
    endpoint: String,
    model: Option<String>,
    explicit_endpoint: bool,
}

impl ConnectionMetadata {
    fn complete(self, credential: Credential, has_helper: bool) -> ImportResult<ImportedConfig> {
        if credential.value.is_none() && !(self.explicit_endpoint || self.model.is_some()) {
            let unsupported = has_helper || credential.unsupported_auth;
            return Err(if unsupported {
                LocalImportFailure::UnsupportedAuth
            } else {
                LocalImportFailure::NotFound
            });
        }
        Ok(ImportedConfig {
            source: self.source,
            endpoint: Some(self.endpoint),
            model: self.model,
            api_key: credential.value,
            credential_kind: credential.kind,
        })
    }
}

struct Credential {
    value: Option<AiSecret>,
    kind: CredentialKind,
    unsupported_auth: bool,
}

impl Credential {
    fn none() -> Self {
        Self {
            value: None,
            kind: CredentialKind::ApiKey,
            unsupported_auth: false,
        }
    }
}

// ---------------------------------------------------------------------------
// Field validation
// ---------------------------------------------------------------------------

fn field(value: Option<&str>) -> ImportResult<Option<String>> {
    let Some(value) = value else {
        return Ok(None);
    };
    if value.trim().is_empty()
        || value.len() > MAX_FIELD_LENGTH
        || value.chars().any(char::is_control)
    {
        return Err(LocalImportFailure::Malformed);
    }
    Ok(Some(value.to_owned()))
}

fn secret(value: Option<&str>) -> ImportResult<Option<AiSecret>> {
    let Some(value) = value else {
        return Ok(None);
    };
    if value.is_empty()
        || value.len() > MAX_SECRET_LENGTH
        || !value.bytes().all(|byte| (b'!'..=b'~').contains(&byte))
    {
        return Err(LocalImportFailure::Malformed);
    }
    AiSecret::new(value)
        .map(Some)
        .map_err(|_| LocalImportFailure::Malformed)
}

fn model(value: Option<String>) -> ImportResult<Option<String>> {
    let value = field(value.as_deref())?;
    if value
        .as_ref()
        .is_some_and(|value| value.len() > MAX_MODEL_LENGTH)
    {
        return Err(LocalImportFailure::Malformed);
    }
    Ok(value)
}

fn authorization(value: Option<&str>) -> ImportResult<Option<AiSecret>> {
    let Some(value) = value else {
        return Ok(None);
    };
    let prefix = "Bearer ";
    if !value
        .as_bytes()
        .starts_with_ignore_ascii_case(prefix.as_bytes())
    {
        return Err(LocalImportFailure::UnsupportedAuth);
    }
    secret(Some(&value[prefix.len()..]))
}

fn environment_name(value: &str) -> ImportResult<String> {
    let mut chars = value.chars();
    let valid = value.len() <= 128
        && match chars.next() {
            Some(first) => first.is_ascii_alphabetic() || first == '_',
            None => false,
        }
        && chars.all(|character| character.is_ascii_alphanumeric() || character == '_');
    if !valid {
        return Err(LocalImportFailure::Malformed);
    }
    Ok(value.to_owned())
}

fn endpoint(value: &str) -> ImportResult<String> {
    let value = field(Some(value))?.ok_or(LocalImportFailure::Malformed)?;
    if value.chars().any(char::is_whitespace) {
        return Err(LocalImportFailure::Malformed);
    }
    crate::platform::validate_endpoint(&value).map_err(|_| LocalImportFailure::Malformed)?;
    let url = http_client::Url::parse(&value).map_err(|_| LocalImportFailure::Malformed)?;
    if url.port() == Some(0) {
        return Err(LocalImportFailure::Malformed);
    }
    Ok(value)
}

fn is_official_openai(value: &str) -> ImportResult<bool> {
    let url = http_client::Url::parse(value).map_err(|_| LocalImportFailure::Malformed)?;
    let path = url.path().trim_end_matches('/');
    Ok(url.scheme() == "https"
        && url
            .host_str()
            .is_some_and(|host| host.eq_ignore_ascii_case("api.openai.com"))
        && url.port_or_known_default() == Some(443)
        && matches!(path, "/v1" | "/v1/responses"))
}

// ---------------------------------------------------------------------------
// Bounded read-only file access
// ---------------------------------------------------------------------------

fn config_directory(
    home: &Path,
    env: &BTreeMap<String, String>,
    variable: &str,
    default_name: &str,
) -> ImportResult<PathBuf> {
    let Some(configured) = env.get(variable) else {
        let home = std::path::absolute(home).map_err(|_| LocalImportFailure::Malformed)?;
        return Ok(normalize_lexical(
            &normalize_lexical(&home).join(default_name),
        ));
    };
    let configured = field(Some(configured))?.ok_or(LocalImportFailure::Malformed)?;
    let path = PathBuf::from(&configured);
    if !path.is_absolute() {
        return Err(LocalImportFailure::UnsupportedConfig);
    }
    Ok(normalize_lexical(&path))
}

fn normalize_lexical(path: &Path) -> PathBuf {
    let mut prefix = None;
    let mut parts = Vec::new();
    for component in path.components() {
        match component {
            Component::Prefix(value) => prefix = Some(value.as_os_str().to_owned()),
            Component::RootDir | Component::CurDir => {}
            Component::ParentDir => {
                parts.pop();
            }
            Component::Normal(part) => parts.push(part.to_owned()),
        }
    }
    let mut result = PathBuf::new();
    if let Some(prefix) = prefix {
        result.push(prefix);
    }
    result.push(std::path::MAIN_SEPARATOR_STR);
    for part in parts {
        result.push(part);
    }
    result
}

fn read_file(path: &Path) -> ImportResult<Option<String>> {
    if let Some(parent) = path.parent() {
        let mut current = PathBuf::new();
        for component in parent.components() {
            current.push(component.as_os_str());
            match std::fs::symlink_metadata(&current) {
                Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
                Err(error) if error.kind() == std::io::ErrorKind::PermissionDenied => {
                    return Err(LocalImportFailure::PermissionDenied);
                }
                Err(_) => return Err(LocalImportFailure::Malformed),
                Ok(metadata) => {
                    if !metadata.is_dir() || metadata.file_type().is_symlink() {
                        return Err(LocalImportFailure::Malformed);
                    }
                }
            }
        }
    }
    let before = match std::fs::symlink_metadata(path) {
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(None),
        Err(error) if error.kind() == std::io::ErrorKind::PermissionDenied => {
            return Err(LocalImportFailure::PermissionDenied);
        }
        Err(_) => return Err(LocalImportFailure::Malformed),
        Ok(metadata) => metadata,
    };
    if !before.is_file() || before.file_type().is_symlink() || before.len() > MAX_FILE_BYTES {
        return Err(LocalImportFailure::Malformed);
    }
    let before_identity = file_identity(&before);
    let mut file = std::fs::File::open(path).map_err(|error| match error.kind() {
        std::io::ErrorKind::NotFound => LocalImportFailure::NotFound,
        std::io::ErrorKind::PermissionDenied => LocalImportFailure::PermissionDenied,
        _ => LocalImportFailure::Malformed,
    })?;
    let handle_metadata = file.metadata().map_err(|_| LocalImportFailure::Malformed)?;
    if !handle_metadata.is_file() || file_identity(&handle_metadata) != before_identity {
        return Err(LocalImportFailure::Malformed);
    }
    let mut bytes = Vec::new();
    file.by_ref()
        .take(MAX_FILE_BYTES + 1)
        .read_to_end(&mut bytes)
        .map_err(|error| match error.kind() {
            std::io::ErrorKind::PermissionDenied => LocalImportFailure::PermissionDenied,
            _ => LocalImportFailure::Malformed,
        })?;
    if bytes.len() as u64 > MAX_FILE_BYTES {
        return Err(LocalImportFailure::Malformed);
    }
    let after = std::fs::symlink_metadata(path).map_err(|_| LocalImportFailure::Malformed)?;
    if !after.is_file() || file_identity(&after) != before_identity {
        return Err(LocalImportFailure::Malformed);
    }
    String::from_utf8(bytes)
        .map(Some)
        .map_err(|_| LocalImportFailure::Malformed)
}

#[cfg(unix)]
fn file_identity(metadata: &std::fs::Metadata) -> (u64, u64) {
    use std::os::unix::fs::MetadataExt;
    (metadata.dev(), metadata.ino())
}

#[cfg(not(unix))]
fn file_identity(metadata: &std::fs::Metadata) -> (u64, u64) {
    (metadata.len(), 0)
}

// ---------------------------------------------------------------------------
// Bounded JSON helpers
// ---------------------------------------------------------------------------

fn json_limits() -> aps_json::Limits {
    aps_json::Limits {
        max_bytes: MAX_FILE_BYTES,
        max_depth: 32,
        max_nodes: 4096,
        max_string_bytes: MAX_FIELD_LENGTH,
        max_collection_items: 256,
    }
}

fn json_object(text: &str) -> ImportResult<Value> {
    let value = aps_json::parse_slice(text.as_bytes(), json_limits())
        .map_err(|_| LocalImportFailure::Malformed)?;
    if value.as_object().is_none() {
        return Err(LocalImportFailure::Malformed);
    }
    Ok(value)
}

fn json_string(object: &Value, key: &str) -> ImportResult<Option<String>> {
    match object.get(key) {
        None => Ok(None),
        Some(Value::String(value)) => Ok(Some(value.clone())),
        Some(_) => Err(LocalImportFailure::Malformed),
    }
}

fn json_object_field<'a>(object: &'a Value, key: &str) -> ImportResult<Option<&'a Value>> {
    match object.get(key) {
        None => Ok(None),
        Some(value) if value.as_object().is_some() => Ok(Some(value)),
        Some(_) => Err(LocalImportFailure::Malformed),
    }
}

// ---------------------------------------------------------------------------
// Bounded TOML subset (port of Compose BoundedLocalConfigToml)
// ---------------------------------------------------------------------------

const MAX_TOML_ENTRIES: usize = 4096;
const MAX_KEY_DEPTH: usize = 16;
const MAX_KEY_LENGTH: usize = 256;
const MAX_VALUE_DEPTH: usize = 16;
const MAX_CLOSING_QUOTES: usize = 5;
const DELIMITER_LENGTH: usize = 3;
const CONNECTION_ROOTS: [&str; 5] = [
    "profiles",
    "model_providers",
    "model",
    "model_provider",
    "profile",
];

#[derive(Debug, Clone)]
enum TomlValue {
    String { value: String, multiline: bool },
    Boolean(bool),
    Other,
    Table(TomlTable),
    ArrayTable(Vec<TomlTable>),
}

#[derive(Debug, Clone, Default)]
struct TomlTable {
    values: BTreeMap<String, TomlValue>,
    inline: bool,
}

impl TomlTable {
    fn text(&self, key: &str) -> ImportResult<Option<String>> {
        match self.values.get(key) {
            None => Ok(None),
            Some(TomlValue::String { value, multiline }) => {
                if *multiline {
                    Err(LocalImportFailure::UnsupportedConfig)
                } else {
                    Ok(Some(value.clone()))
                }
            }
            Some(_) => Err(LocalImportFailure::Malformed),
        }
    }

    fn boolean(&self, key: &str) -> ImportResult<Option<bool>> {
        match self.values.get(key) {
            None => Ok(None),
            Some(TomlValue::Boolean(value)) => Ok(Some(*value)),
            Some(_) => Err(LocalImportFailure::Malformed),
        }
    }

    fn table(&self, key: &str) -> ImportResult<Option<&TomlTable>> {
        match self.values.get(key) {
            None => Ok(None),
            Some(TomlValue::Table(table)) => Ok(Some(table)),
            Some(_) => Err(LocalImportFailure::Malformed),
        }
    }

    fn has_helper(&self) -> bool {
        self.values.keys().any(|key| {
            matches!(
                key.as_str(),
                "api_key_helper" | "credential_helper" | "apiKeyHelper"
            )
        })
    }

    fn authorization(&self) -> ImportResult<Option<String>> {
        let keys: Vec<&String> = self
            .values
            .keys()
            .filter(|key| key.eq_ignore_ascii_case("Authorization"))
            .collect();
        if keys.len() > 1 {
            return Err(LocalImportFailure::UnsupportedConfig);
        }
        match keys.first() {
            Some(key) => self.text(key),
            None => Ok(None),
        }
    }
}

fn parse_toml(input: &str) -> ImportResult<TomlTable> {
    TomlParser {
        scanner: TomlScanner { input, position: 0 },
        entries: 0,
        root: TomlTable::default(),
        sections: BTreeSet::new(),
    }
    .parse()
}

struct TomlParser<'a> {
    scanner: TomlScanner<'a>,
    entries: usize,
    root: TomlTable,
    sections: BTreeSet<Vec<String>>,
}

impl TomlParser<'_> {
    fn parse(mut self) -> ImportResult<TomlTable> {
        let mut current: Vec<String> = Vec::new();
        self.scanner.whitespace(true);
        while self.scanner.peek(0).is_some() {
            if self.scanner.take('[') {
                current = self.section()?;
            } else {
                self.assignment(&current, 0)?;
            }
            self.scanner.whitespace(false);
            if let Some(next) = self.scanner.peek(0) {
                if next != '\r' && next != '\n' {
                    return Err(LocalImportFailure::Malformed);
                }
            }
            self.scanner.whitespace(true);
        }
        Ok(self.root)
    }

    fn section(&mut self) -> ImportResult<Vec<String>> {
        let array = self.scanner.take('[');
        let path = self.key_path()?;
        self.scanner.expect(']')?;
        if array {
            self.scanner.expect(']')?;
            self.array_table(&path)?;
            return Ok(path);
        }
        table_at(&mut self.root, &path)?;
        if !self.sections.insert(path.clone()) {
            return Err(LocalImportFailure::Malformed);
        }
        Ok(path)
    }

    fn array_table(&mut self, path: &[String]) -> ImportResult<()> {
        if path
            .first()
            .is_some_and(|root| CONNECTION_ROOTS.contains(&root.as_str()))
        {
            return Err(LocalImportFailure::UnsupportedConfig);
        }
        self.count_entry()?;
        let (last, parents) = path.split_last().ok_or(LocalImportFailure::Malformed)?;
        let parent = table_at(&mut self.root, parents)?;
        let entry = parent
            .values
            .entry(last.clone())
            .or_insert_with(|| TomlValue::ArrayTable(Vec::new()));
        match entry {
            TomlValue::ArrayTable(items) => {
                items.push(TomlTable::default());
                Ok(())
            }
            _ => Err(LocalImportFailure::Malformed),
        }
    }

    fn key_path(&mut self) -> ImportResult<Vec<String>> {
        let mut keys = Vec::new();
        loop {
            self.scanner.whitespace(false);
            let key = if matches!(self.scanner.peek(0), Some('"' | '\'')) {
                self.string(false)?.0
            } else {
                let bare = self.scanner.read_while(is_bare_key_character);
                if bare.is_empty() {
                    return Err(LocalImportFailure::Malformed);
                }
                bare
            };
            if keys.len() >= MAX_KEY_DEPTH || key.len() > MAX_KEY_LENGTH {
                return Err(LocalImportFailure::Malformed);
            }
            keys.push(key);
            self.scanner.whitespace(false);
            if !self.scanner.take('.') {
                break;
            }
        }
        Ok(keys)
    }

    fn assignment(&mut self, current: &[String], depth: usize) -> ImportResult<()> {
        let (path, value) = self.assignment_value(depth)?;
        let base = table_at(&mut self.root, current)?;
        insert_at(base, &path, value)
    }

    fn assignment_into(&mut self, table: &mut TomlTable, depth: usize) -> ImportResult<()> {
        let (path, value) = self.assignment_value(depth)?;
        insert_at(table, &path, value)
    }

    fn assignment_value(&mut self, depth: usize) -> ImportResult<(Vec<String>, TomlValue)> {
        let path = self.key_path()?;
        self.scanner.expect('=')?;
        self.scanner.whitespace(false);
        let value = self.value(depth)?;
        Ok((path, value))
    }

    fn value(&mut self, depth: usize) -> ImportResult<TomlValue> {
        if depth > MAX_VALUE_DEPTH {
            return Err(LocalImportFailure::Malformed);
        }
        self.count_entry()?;
        match self.scanner.peek(0) {
            Some('"' | '\'') => {
                let (value, multiline) = self.string(true)?;
                Ok(TomlValue::String { value, multiline })
            }
            Some('{') => Ok(TomlValue::Table(self.inline_table(depth + 1)?)),
            Some('[') => {
                self.array(depth + 1)?;
                Ok(TomlValue::Other)
            }
            _ => self.scanner.atom(),
        }
    }

    fn array(&mut self, depth: usize) -> ImportResult<()> {
        self.scanner.expect('[')?;
        self.scanner.whitespace(true);
        while !self.scanner.take(']') {
            self.value(depth)?;
            self.scanner.whitespace(true);
            if self.scanner.take(']') {
                break;
            }
            self.scanner.expect(',')?;
            self.scanner.whitespace(true);
        }
        Ok(())
    }

    fn inline_table(&mut self, depth: usize) -> ImportResult<TomlTable> {
        self.scanner.expect('{')?;
        let mut table = TomlTable {
            values: BTreeMap::new(),
            inline: true,
        };
        self.scanner.whitespace(false);
        if !self.scanner.take('}') {
            loop {
                self.assignment_into(&mut table, depth)?;
                self.scanner.whitespace(false);
                if self.scanner.take('}') {
                    break;
                }
                self.scanner.expect(',')?;
            }
        }
        Ok(table)
    }

    fn string(&mut self, allow_multiline: bool) -> ImportResult<(String, bool)> {
        let quote = self.scanner.read()?;
        let multiline = self.open_multiline(quote, allow_multiline)?;
        let mut result = String::new();
        loop {
            let character = self.scanner.read()?;
            if character == quote {
                if self.close_quotes(quote, multiline, &mut result)? {
                    return Ok((result, multiline));
                }
            } else if character == '\\' && quote == '"' {
                self.escape(multiline, &mut result)?;
            } else {
                self.append_character(character, multiline, &mut result)?;
            }
        }
    }

    fn open_multiline(&mut self, quote: char, allowed: bool) -> ImportResult<bool> {
        let multiline = self.scanner.peek(0) == Some(quote) && self.scanner.peek(1) == Some(quote);
        if multiline {
            if !allowed {
                return Err(LocalImportFailure::UnsupportedConfig);
            }
            self.scanner.read()?;
            self.scanner.read()?;
            if self.scanner.take('\r') {
                self.scanner.expect('\n')?;
            } else {
                self.scanner.take('\n');
            }
        }
        Ok(multiline)
    }

    fn close_quotes(
        &mut self,
        quote: char,
        multiline: bool,
        result: &mut String,
    ) -> ImportResult<bool> {
        if !multiline {
            return Ok(true);
        }
        let mut count = 1;
        while self.scanner.take(quote) {
            count += 1;
        }
        if count > MAX_CLOSING_QUOTES {
            return Err(LocalImportFailure::Malformed);
        }
        let closed = count >= DELIMITER_LENGTH;
        let extra = if closed {
            count - DELIMITER_LENGTH
        } else {
            count
        };
        for _ in 0..extra {
            result.push(quote);
        }
        Ok(closed)
    }

    fn escape(&mut self, multiline: bool, result: &mut String) -> ImportResult<()> {
        if multiline && matches!(self.scanner.peek(0), Some(' ' | '\t' | '\r' | '\n')) {
            self.skip_continuation()?;
            return Ok(());
        }
        match self.scanner.read()? {
            'b' => result.push('\u{8}'),
            't' => result.push('\t'),
            'n' => result.push('\n'),
            'f' => result.push('\u{c}'),
            'r' => result.push('\r'),
            '"' => result.push('"'),
            '\\' => result.push('\\'),
            'u' => result.push(self.unicode(4)?),
            'U' => result.push(self.unicode(8)?),
            _ => return Err(LocalImportFailure::Malformed),
        }
        Ok(())
    }

    fn skip_continuation(&mut self) -> ImportResult<()> {
        let mut newline = false;
        while matches!(self.scanner.peek(0), Some(' ' | '\t' | '\r' | '\n')) {
            match self.scanner.read()? {
                '\n' => newline = true,
                '\r' => {
                    self.scanner.expect('\n')?;
                    newline = true;
                }
                _ => {}
            }
        }
        if !newline {
            return Err(LocalImportFailure::Malformed);
        }
        Ok(())
    }

    fn unicode(&mut self, length: usize) -> ImportResult<char> {
        let mut digits = String::with_capacity(length);
        for _ in 0..length {
            digits.push(self.scanner.read()?);
        }
        if !digits
            .chars()
            .all(|character| character.is_ascii_hexdigit())
        {
            return Err(LocalImportFailure::Malformed);
        }
        let value = u32::from_str_radix(&digits, 16).map_err(|_| LocalImportFailure::Malformed)?;
        match char::from_u32(value) {
            Some(character) if !(0xd800..=0xdfff).contains(&value) => Ok(character),
            _ => Err(LocalImportFailure::Malformed),
        }
    }

    fn append_character(
        &mut self,
        character: char,
        multiline: bool,
        result: &mut String,
    ) -> ImportResult<()> {
        let line_ending = matches!(character, '\n' | '\r');
        if multiline && line_ending {
            if character == '\r' {
                self.scanner.expect('\n')?;
            }
            result.push('\n');
        } else {
            let forbidden_control = character.is_control() && character != '\t';
            if line_ending || forbidden_control {
                return Err(LocalImportFailure::Malformed);
            }
            result.push(character);
        }
        Ok(())
    }

    fn count_entry(&mut self) -> ImportResult<()> {
        self.entries += 1;
        if self.entries > MAX_TOML_ENTRIES {
            return Err(LocalImportFailure::Malformed);
        }
        Ok(())
    }
}

fn insert_at(table: &mut TomlTable, path: &[String], value: TomlValue) -> ImportResult<()> {
    let (last, parents) = path.split_last().ok_or(LocalImportFailure::Malformed)?;
    let parent = table_at(table, parents)?;
    if parent.values.insert(last.clone(), value).is_some() {
        return Err(LocalImportFailure::Malformed);
    }
    Ok(())
}

fn table_at<'a>(table: &'a mut TomlTable, path: &[String]) -> ImportResult<&'a mut TomlTable> {
    let mut current = table;
    for key in path {
        let entry = current
            .values
            .entry(key.clone())
            .or_insert_with(|| TomlValue::Table(TomlTable::default()));
        current = match entry {
            TomlValue::Table(table) => table,
            TomlValue::ArrayTable(items) => {
                items.last_mut().ok_or(LocalImportFailure::Malformed)?
            }
            _ => return Err(LocalImportFailure::Malformed),
        };
        if current.inline {
            return Err(LocalImportFailure::Malformed);
        }
    }
    Ok(current)
}

struct TomlScanner<'a> {
    input: &'a str,
    position: usize,
}

impl TomlScanner<'_> {
    fn peek(&self, offset: usize) -> Option<char> {
        self.input[self.position..].chars().nth(offset)
    }

    fn read(&mut self) -> ImportResult<char> {
        let character = self.peek(0).ok_or(LocalImportFailure::Malformed)?;
        self.position += character.len_utf8();
        Ok(character)
    }

    fn take(&mut self, character: char) -> bool {
        if self.peek(0) == Some(character) {
            self.position += character.len_utf8();
            true
        } else {
            false
        }
    }

    fn expect(&mut self, character: char) -> ImportResult<()> {
        if self.take(character) {
            Ok(())
        } else {
            Err(LocalImportFailure::Malformed)
        }
    }

    fn read_while(&mut self, predicate: impl Fn(char) -> bool) -> String {
        let start = self.position;
        while self.peek(0).is_some_and(&predicate) {
            let character = self.read().expect("peeked character");
            if character == '\0' {
                break;
            }
        }
        self.input[start..self.position].to_owned()
    }

    fn whitespace(&mut self, multiline: bool) {
        loop {
            match self.peek(0) {
                Some(' ' | '\t') => {
                    self.position += 1;
                }
                Some('\r' | '\n') => {
                    if multiline {
                        self.position += 1;
                    } else {
                        return;
                    }
                }
                Some('#') => {
                    self.read_while(|character| character != '\r' && character != '\n');
                }
                _ => return,
            }
        }
    }

    fn atom(&mut self) -> ImportResult<TomlValue> {
        let atom = self.read_while(|character| {
            !character.is_whitespace() && !matches!(character, ',' | ']' | '}' | '#')
        });
        match atom.as_str() {
            "true" => Ok(TomlValue::Boolean(true)),
            "false" => Ok(TomlValue::Boolean(false)),
            _ if is_toml_number(&atom) => Ok(TomlValue::Other),
            _ => Err(LocalImportFailure::Malformed),
        }
    }
}

fn is_bare_key_character(character: char) -> bool {
    character.is_ascii_alphanumeric() || matches!(character, '_' | '-')
}

fn is_toml_number(atom: &str) -> bool {
    let unsigned = atom.strip_prefix(['+', '-']).unwrap_or(atom);
    if matches!(unsigned, "inf" | "nan") {
        return true;
    }
    let mut characters = unsigned.chars();
    match characters.next() {
        Some(first) if first.is_ascii_digit() => characters.all(|character| {
            character.is_ascii_alphanumeric() || matches!(character, '_' | '.' | ':' | '+' | '-')
        }),
        _ => false,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_platform::TempWorkspace;

    const VALID_KEY: &str = "sk-test-1234567890";

    fn environment(pairs: &[(&str, &str)]) -> BTreeMap<String, String> {
        pairs
            .iter()
            .map(|(key, value)| ((*key).to_owned(), (*value).to_owned()))
            .collect()
    }

    fn workspace() -> (TempWorkspace, PathBuf) {
        let root = std::env::temp_dir().canonicalize().unwrap();
        let workspace = TempWorkspace::create(&root).unwrap();
        let home = workspace.path().join("home");
        std::fs::create_dir_all(&home).unwrap();
        (workspace, home)
    }

    fn write(path: &Path, text: &str) {
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(path, text).unwrap();
    }

    fn codex_config(home: &Path, text: &str) {
        write(&home.join(".codex").join("config.toml"), text);
    }

    fn codex_auth(home: &Path, text: &str) {
        write(&home.join(".codex").join("auth.json"), text);
    }

    fn claude_settings(home: &Path, text: &str) {
        write(&home.join(".claude").join("settings.json"), text);
    }

    fn key_bytes(imported: &ImportedConfig) -> &[u8] {
        imported.api_key.as_ref().unwrap().as_bytes()
    }

    #[test]
    fn codex_imports_official_config_and_auth_file() {
        let (_workspace, home) = workspace();
        codex_config(&home, "model = \"gpt-5\"\n");
        codex_auth(&home, r#"{"OPENAI_API_KEY":"sk-test-1234567890"}"#);
        let imported = import_local_config(ProviderKind::Codex, &home, &BTreeMap::new()).unwrap();
        assert_eq!(imported.source, ProviderKind::Codex);
        assert_eq!(
            imported.endpoint.as_deref(),
            Some("https://api.openai.com/v1/responses")
        );
        assert_eq!(imported.model.as_deref(), Some("gpt-5"));
        assert_eq!(imported.credential_kind, CredentialKind::ApiKey);
        assert_eq!(key_bytes(&imported), VALID_KEY.as_bytes());
    }

    #[test]
    fn codex_process_key_takes_precedence_over_auth_file() {
        let (_workspace, home) = workspace();
        codex_auth(&home, r#"{"OPENAI_API_KEY":"sk-auth-1234567890"}"#);
        let env = environment(&[("OPENAI_API_KEY", "sk-env-1234567890")]);
        let imported = import_local_config(ProviderKind::Codex, &home, &env).unwrap();
        assert_eq!(key_bytes(&imported), b"sk-env-1234567890");
    }

    #[test]
    fn codex_selected_profile_overrides_root_model() {
        let (_workspace, home) = workspace();
        codex_config(
            &home,
            "profile = \"work\"\nmodel = \"root-model\"\n[profiles.work]\nmodel = \"profile-model\"\n",
        );
        codex_auth(&home, r#"{"OPENAI_API_KEY":"sk-test-1234567890"}"#);
        let imported = import_local_config(ProviderKind::Codex, &home, &BTreeMap::new()).unwrap();
        assert_eq!(imported.model.as_deref(), Some("profile-model"));
    }

    #[test]
    fn codex_without_any_configuration_is_not_found() {
        let (_workspace, home) = workspace();
        assert_eq!(
            import_local_config(ProviderKind::Codex, &home, &BTreeMap::new())
                .err()
                .unwrap(),
            LocalImportFailure::NotFound
        );
    }

    #[test]
    fn codex_malformed_toml_is_rejected() {
        let (_workspace, home) = workspace();
        codex_config(&home, "model = ");
        assert_eq!(
            import_local_config(ProviderKind::Codex, &home, &BTreeMap::new())
                .err()
                .unwrap(),
            LocalImportFailure::Malformed
        );
    }

    #[test]
    fn codex_credential_helpers_and_oauth_are_rejected() {
        let (_workspace, home) = workspace();
        codex_config(&home, "api_key_helper = \"helper\"\n");
        assert_eq!(
            import_local_config(ProviderKind::Codex, &home, &BTreeMap::new())
                .err()
                .unwrap(),
            LocalImportFailure::UnsupportedAuth
        );

        let (_workspace, home) = workspace();
        codex_auth(&home, r#"{"tokens":{"access_token":"oauth"}}"#);
        assert_eq!(
            import_local_config(ProviderKind::Codex, &home, &BTreeMap::new())
                .err()
                .unwrap(),
            LocalImportFailure::UnsupportedAuth
        );

        let (_workspace, home) = workspace();
        codex_auth(&home, r#"{"auth_mode":"chatgpt"}"#);
        assert_eq!(
            import_local_config(ProviderKind::Codex, &home, &BTreeMap::new())
                .err()
                .unwrap(),
            LocalImportFailure::UnsupportedAuth
        );
    }

    #[test]
    fn codex_custom_provider_reads_declared_environment_key() {
        let (_workspace, home) = workspace();
        codex_config(
            &home,
            "model = \"gpt-5\"\nmodel_provider = \"custom\"\n[model_providers.custom]\nbase_url = \"https://gateway.example/v1\"\nenv_key = \"MY_KEY\"\n",
        );
        let env = environment(&[("MY_KEY", VALID_KEY)]);
        let imported = import_local_config(ProviderKind::Codex, &home, &env).unwrap();
        assert_eq!(
            imported.endpoint.as_deref(),
            Some("https://gateway.example/v1/responses")
        );
        assert_eq!(imported.credential_kind, CredentialKind::ApiKey);
        assert_eq!(key_bytes(&imported), VALID_KEY.as_bytes());
    }

    #[test]
    fn codex_rejects_unsupported_provider_configurations() {
        let (_workspace, home) = workspace();
        codex_config(
            &home,
            "model_provider = \"custom\"\n[model_providers.custom]\nbase_url = \"https://gateway.example/v1\"\nwire_api = \"chat\"\n",
        );
        assert_eq!(
            import_local_config(ProviderKind::Codex, &home, &BTreeMap::new())
                .err()
                .unwrap(),
            LocalImportFailure::UnsupportedConfig
        );

        let (_workspace, home) = workspace();
        codex_config(&home, "model_provider = \"custom\"\n");
        assert_eq!(
            import_local_config(ProviderKind::Codex, &home, &BTreeMap::new())
                .err()
                .unwrap(),
            LocalImportFailure::UnsupportedConfig
        );

        let (_workspace, home) = workspace();
        codex_config(
            &home,
            "model_provider = \"custom\"\n[model_providers.custom]\nenv_key = \"MY_KEY\"\n",
        );
        assert_eq!(
            import_local_config(ProviderKind::Codex, &home, &BTreeMap::new())
                .err()
                .unwrap(),
            LocalImportFailure::UnsupportedConfig
        );
    }

    #[test]
    fn codex_rejects_relative_config_directory() {
        let (_workspace, home) = workspace();
        let env = environment(&[("CODEX_HOME", "relative/codex")]);
        assert_eq!(
            import_local_config(ProviderKind::Codex, &home, &env)
                .err()
                .unwrap(),
            LocalImportFailure::UnsupportedConfig
        );
    }

    #[test]
    fn codex_rejects_invalid_remote_endpoint() {
        let (_workspace, home) = workspace();
        codex_config(
            &home,
            "model = \"gpt-5\"\nmodel_provider = \"custom\"\n[model_providers.custom]\nbase_url = \"http://example.com/v1\"\nenv_key = \"MY_KEY\"\n",
        );
        let env = environment(&[("MY_KEY", VALID_KEY)]);
        assert_eq!(
            import_local_config(ProviderKind::Codex, &home, &env)
                .err()
                .unwrap(),
            LocalImportFailure::Malformed
        );
    }

    #[cfg(unix)]
    #[test]
    fn codex_rejects_symlinked_config_files() {
        use std::os::unix::fs::symlink;
        let (_workspace, home) = workspace();
        let real = home.join("real.toml");
        write(&real, "model = \"gpt-5\"\n");
        let directory = home.join(".codex");
        std::fs::create_dir_all(&directory).unwrap();
        symlink(&real, directory.join("config.toml")).unwrap();
        codex_auth(&home, r#"{"OPENAI_API_KEY":"sk-test-1234567890"}"#);
        assert_eq!(
            import_local_config(ProviderKind::Codex, &home, &BTreeMap::new())
                .err()
                .unwrap(),
            LocalImportFailure::Malformed
        );
    }

    #[test]
    fn codex_rejects_oversized_configuration() {
        let (_workspace, home) = workspace();
        codex_config(&home, &format!("model = \"{}\"\n", "x".repeat(300 * 1024)));
        assert_eq!(
            import_local_config(ProviderKind::Codex, &home, &BTreeMap::new())
                .err()
                .unwrap(),
            LocalImportFailure::Malformed
        );
    }

    #[test]
    fn codex_rejects_oversized_model_identifier() {
        let (_workspace, home) = workspace();
        codex_config(&home, &format!("model = \"{}\"\n", "m".repeat(129)));
        assert_eq!(
            import_local_config(ProviderKind::Codex, &home, &BTreeMap::new())
                .err()
                .unwrap(),
            LocalImportFailure::Malformed
        );
    }

    #[cfg(unix)]
    #[test]
    fn codex_reports_permission_denied() {
        use std::os::unix::fs::PermissionsExt;
        let (_workspace, home) = workspace();
        let path = home.join(".codex").join("config.toml");
        write(&path, "model = \"gpt-5\"\n");
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o000)).unwrap();
        let result = import_local_config(ProviderKind::Codex, &home, &BTreeMap::new());
        std::fs::set_permissions(&path, std::fs::Permissions::from_mode(0o600)).unwrap();
        if let Err(failure) = result {
            assert_eq!(failure, LocalImportFailure::PermissionDenied);
        }
    }

    #[test]
    fn claude_settings_connection_group_is_used_whole() {
        let (_workspace, home) = workspace();
        claude_settings(
            &home,
            r#"{"env":{"ANTHROPIC_BASE_URL":"https://gateway.example/anthropic","ANTHROPIC_AUTH_TOKEN":"sk-ant-abcdefgh","ANTHROPIC_MODEL":"claude-sonnet-4"},"model":"claude-root"}"#,
        );
        let env = environment(&[("ANTHROPIC_API_KEY", "sk-inherited-1234567890")]);
        let imported = import_local_config(ProviderKind::Claude, &home, &env).unwrap();
        assert_eq!(
            imported.endpoint.as_deref(),
            Some("https://gateway.example/anthropic")
        );
        assert_eq!(imported.model.as_deref(), Some("claude-sonnet-4"));
        assert_eq!(imported.credential_kind, CredentialKind::BearerToken);
        assert_eq!(key_bytes(&imported), b"sk-ant-abcdefgh");
    }

    #[test]
    fn claude_settings_gateway_never_borrows_an_inherited_key() {
        let (_workspace, home) = workspace();
        claude_settings(
            &home,
            r#"{"env":{"ANTHROPIC_BASE_URL":"https://gateway.example/anthropic"}}"#,
        );
        let env = environment(&[("ANTHROPIC_API_KEY", VALID_KEY)]);
        let imported = import_local_config(ProviderKind::Claude, &home, &env).unwrap();
        assert_eq!(
            imported.endpoint.as_deref(),
            Some("https://gateway.example/anthropic")
        );
        assert!(imported.api_key.is_none());
    }

    #[test]
    fn claude_falls_back_to_process_environment() {
        let (_workspace, home) = workspace();
        let env = environment(&[
            ("ANTHROPIC_API_KEY", VALID_KEY),
            ("ANTHROPIC_MODEL", "claude-3-7-sonnet"),
        ]);
        let imported = import_local_config(ProviderKind::Claude, &home, &env).unwrap();
        assert_eq!(
            imported.endpoint.as_deref(),
            Some("https://api.anthropic.com")
        );
        assert_eq!(imported.model.as_deref(), Some("claude-3-7-sonnet"));
        assert_eq!(imported.credential_kind, CredentialKind::ApiKey);
        assert_eq!(key_bytes(&imported), VALID_KEY.as_bytes());
    }

    #[test]
    fn claude_oauth_tokens_are_rejected() {
        let (_workspace, home) = workspace();
        claude_settings(
            &home,
            r#"{"env":{"ANTHROPIC_AUTH_TOKEN":"sk-ant-oat01-abcdefgh"}}"#,
        );
        assert_eq!(
            import_local_config(ProviderKind::Claude, &home, &BTreeMap::new())
                .err()
                .unwrap(),
            LocalImportFailure::UnsupportedAuth
        );
    }

    #[test]
    fn claude_malformed_settings_are_rejected() {
        let (_workspace, home) = workspace();
        claude_settings(&home, "{");
        assert_eq!(
            import_local_config(ProviderKind::Claude, &home, &BTreeMap::new())
                .err()
                .unwrap(),
            LocalImportFailure::Malformed
        );

        let (_workspace, home) = workspace();
        claude_settings(&home, r#"{"env":"not-an-object"}"#);
        assert_eq!(
            import_local_config(ProviderKind::Claude, &home, &BTreeMap::new())
                .err()
                .unwrap(),
            LocalImportFailure::Malformed
        );

        let (_workspace, home) = workspace();
        claude_settings(
            &home,
            r#"{"env":{"ANTHROPIC_API_KEY":"sk-test-1234567890"},"model":5}"#,
        );
        assert_eq!(
            import_local_config(ProviderKind::Claude, &home, &BTreeMap::new())
                .err()
                .unwrap(),
            LocalImportFailure::Malformed
        );
    }

    #[test]
    fn custom_provider_has_no_local_configuration() {
        let (_workspace, home) = workspace();
        assert_eq!(
            import_local_config(ProviderKind::Custom, &home, &BTreeMap::new())
                .err()
                .unwrap(),
            LocalImportFailure::UnsupportedConfig
        );
    }

    #[test]
    fn failure_codes_are_stable() {
        assert_eq!(
            LocalImportFailure::NotFound.to_string(),
            "E_AI_IMPORT_NOT_FOUND"
        );
        assert_eq!(
            LocalImportFailure::Malformed.to_string(),
            "E_AI_IMPORT_MALFORMED"
        );
        assert_eq!(
            LocalImportFailure::PermissionDenied.to_string(),
            "E_AI_IMPORT_DENIED"
        );
        assert_eq!(
            LocalImportFailure::UnsupportedAuth.to_string(),
            "E_AI_IMPORT_AUTH"
        );
        assert_eq!(
            LocalImportFailure::UnsupportedConfig.to_string(),
            "E_AI_IMPORT_UNSUPPORTED"
        );
    }
}

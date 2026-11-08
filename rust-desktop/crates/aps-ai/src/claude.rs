//! No-tools Claude CLI provider, ported from the Compose ClaudeCliProvider.
//!
//! The CLI is discovered explicitly on PATH/homebrew locations; version and
//! required flags are probed before a run. The untrusted payload is delivered on
//! stdin, the work directory is private and temporary, no sessions are persisted,
//! and every tool/MCP source is disabled. A missing or incompatible CLI is
//! reported as an unavailable provider instead of falling back to a different one.

use aps_platform::{CancellationToken, ProcessError, ProcessRequest, ProcessRunner};
use std::collections::BTreeMap;
use std::ffi::OsString;
use std::fmt;
use std::path::{Path, PathBuf};
use std::time::Duration;

const PROBE_TIMEOUT: Duration = Duration::from_secs(5);
const RUN_TIMEOUT: Duration = Duration::from_secs(120);
const MAX_PROBE_BYTES: usize = 64 * 1024;
const MAX_RUN_BYTES: usize = 1024 * 1024 + 64 * 1024;

const REQUIRED_FLAGS: &[&str] = &[
    "--bare",
    "--tools",
    "--disallowedTools",
    "--strict-mcp-config",
    "--mcp-config",
    "--json-schema",
    "--setting-sources",
    "--no-session-persistence",
    "--disable-slash-commands",
    "--model",
    "--system-prompt",
];

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ClaudeError {
    MissingRuntime,
    IncompatibleVersion,
    UnsupportedCapability,
    MissingCredential,
    ProcessExit,
    Protocol,
    Timeout,
    Cancelled,
    OutputLimit,
    Spawn,
}

impl fmt::Display for ClaudeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::MissingRuntime => "E_PROVIDER_MISSING_RUNTIME",
            Self::IncompatibleVersion => "E_PROVIDER_INCOMPATIBLE_VERSION",
            Self::UnsupportedCapability => "E_PROVIDER_UNSUPPORTED_CAPABILITY",
            Self::MissingCredential => "E_PROVIDER_MISSING_CREDENTIAL",
            Self::ProcessExit => "E_PROVIDER_PROCESS_EXIT",
            Self::Protocol => "E_PROVIDER_PROTOCOL",
            Self::Timeout => "E_PROVIDER_TIMEOUT",
            Self::Cancelled => "E_PROVIDER_CANCELLED",
            Self::OutputLimit => "E_PROVIDER_OUTPUT_LIMIT",
            Self::Spawn => "E_PROVIDER_START",
        })
    }
}
impl std::error::Error for ClaudeError {}

/// Locate the CLI on PATH plus the usual user install locations. Finder's
/// restricted PATH is not assumed. No shell is executed.
pub fn find_executable() -> Option<PathBuf> {
    let mut candidates: Vec<PathBuf> = Vec::new();
    if let Some(path) = std::env::var_os("PATH") {
        for directory in std::env::split_paths(&path) {
            if !directory.as_os_str().is_empty() {
                candidates.push(directory.join("claude"));
            }
        }
    }
    if let Some(home) = std::env::var_os("HOME") {
        let home = PathBuf::from(home);
        candidates.push(home.join(".local/bin/claude"));
    }
    candidates.extend([
        PathBuf::from("/opt/homebrew/bin/claude"),
        PathBuf::from("/usr/local/bin/claude"),
    ]);
    candidates.into_iter().find(|candidate| {
        std::fs::metadata(candidate)
            .map(|metadata| metadata.is_file())
            .unwrap_or(false)
    })
}

fn claude_environment(
    endpoint: &str,
    credential_kind: crate::platform::CredentialKind,
    credential: &crate::platform::AiSecret,
) -> Result<BTreeMap<OsString, OsString>, ClaudeError> {
    let secret = std::str::from_utf8(credential.as_bytes())
        .map_err(|_| ClaudeError::MissingCredential)?
        .to_owned();
    let mut environment = BTreeMap::new();
    let key = match credential_kind {
        crate::platform::CredentialKind::ApiKey => "ANTHROPIC_API_KEY",
        crate::platform::CredentialKind::BearerToken => "ANTHROPIC_AUTH_TOKEN",
    };
    environment.insert(OsString::from(key), OsString::from(secret));
    let trimmed = endpoint.trim().trim_end_matches('/');
    if !trimmed.is_empty()
        && trimmed != "https://api.anthropic.com"
        && trimmed != "https://api.anthropic.com/v1"
    {
        environment.insert(
            OsString::from("ANTHROPIC_BASE_URL"),
            OsString::from(trimmed),
        );
    }
    Ok(environment)
}

/// Probe a compatible CLI. Returns the reported version string.
pub fn discover(
    executable: &Path,
    work_root: &Path,
    token: &CancellationToken,
) -> Result<String, ClaudeError> {
    if !std::fs::metadata(executable).is_ok_and(|metadata| metadata.is_file()) {
        return Err(ClaudeError::MissingRuntime);
    }
    let work = aps_platform::TempWorkspace::create(work_root).map_err(|_| ClaudeError::Spawn)?;
    let mut version = ProcessRequest::new(executable);
    version.arguments.push(OsString::from("--version"));
    version.cwd = Some(work.path().to_path_buf());
    version.timeout = PROBE_TIMEOUT;
    version.stdout_limit = MAX_PROBE_BYTES;
    version.stderr_limit = MAX_PROBE_BYTES;
    version.env_clear = true;
    let output = ProcessRunner
        .run(&version, token)
        .map_err(|error| map_process_error(&error))?;
    let text = String::from_utf8(output.stdout).map_err(|_| ClaudeError::IncompatibleVersion)?;
    let version = text.trim();
    let compatible = version
        .strip_prefix("2.1.")
        .and_then(|rest| rest.split(' ').next())
        .and_then(|patch| patch.parse::<u32>().ok())
        .is_some_and(|patch| patch >= 286)
        && version.ends_with("(Claude Code)");
    if !compatible || !output.status.success() {
        return Err(ClaudeError::IncompatibleVersion);
    }
    let mut help = ProcessRequest::new(executable);
    help.arguments.push(OsString::from("--help"));
    help.cwd = Some(work.path().to_path_buf());
    help.timeout = PROBE_TIMEOUT;
    help.stdout_limit = MAX_PROBE_BYTES;
    help.stderr_limit = MAX_PROBE_BYTES;
    help.env_clear = true;
    let output = ProcessRunner
        .run(&help, token)
        .map_err(|error| map_process_error(&error))?;
    if !output.status.success() {
        return Err(ClaudeError::UnsupportedCapability);
    }
    let help = String::from_utf8_lossy(&output.stdout);
    if REQUIRED_FLAGS.iter().any(|flag| !help.contains(flag)) {
        return Err(ClaudeError::UnsupportedCapability);
    }
    Ok(version.to_owned())
}

/// Run one explicit approved request and return the raw structured_output JSON.
pub fn execute(
    executable: &Path,
    work_root: &Path,
    model: &str,
    instructions: &str,
    payload_json: &str,
    schema_json: &str,
    endpoint: &str,
    credential_kind: crate::platform::CredentialKind,
    credential: &crate::platform::AiSecret,
    token: &CancellationToken,
) -> Result<String, ClaudeError> {
    let environment = claude_environment(endpoint, credential_kind, credential)?;
    discover(executable, work_root, token)?;
    let work = aps_platform::TempWorkspace::create(work_root).map_err(|_| ClaudeError::Spawn)?;
    let mut request = ProcessRequest::new(executable);
    request.arguments = [
        "--bare",
        "--print",
        "--output-format",
        "json",
        "--json-schema",
        schema_json,
        "--tools",
        "",
        "--disallowedTools",
        "mcp__*",
        "--strict-mcp-config",
        "--mcp-config",
        "{\"mcpServers\":{}}",
        "--setting-sources",
        "",
        "--no-session-persistence",
        "--disable-slash-commands",
        "--max-turns",
        "1",
        "--model",
        model,
        "--system-prompt",
        instructions,
    ]
    .into_iter()
    .map(OsString::from)
    .collect();
    request.cwd = Some(work.path().to_path_buf());
    request.timeout = RUN_TIMEOUT;
    request.stdout_limit = MAX_RUN_BYTES;
    request.stderr_limit = MAX_RUN_BYTES;
    request.env_clear = true;
    request.environment = environment;
    request.stdin = Some(payload_json.as_bytes().to_vec());
    let output = ProcessRunner
        .run(&request, token)
        .map_err(|error| map_process_error(&error))?;
    if !output.status.success() {
        return Err(ClaudeError::ProcessExit);
    }
    if output.stdout.len() > MAX_RUN_BYTES {
        return Err(ClaudeError::OutputLimit);
    }
    parse_envelope(&output.stdout)
}

fn map_process_error(error: &ProcessError) -> ClaudeError {
    match error {
        ProcessError::Cancelled => ClaudeError::Cancelled,
        ProcessError::Timeout => ClaudeError::Timeout,
        ProcessError::OutputLimit(_) => ClaudeError::OutputLimit,
        ProcessError::InvalidRequest(_)
        | ProcessError::Io { .. }
        | ProcessError::OutputNotClosed => ClaudeError::Spawn,
    }
}

/// Parse the CLI result envelope: type=result, is_error=false, subtype=success,
/// structured_output present. Anything else is a protocol failure.
fn parse_envelope(stdout: &[u8]) -> Result<String, ClaudeError> {
    let value = aps_json::parse_slice(
        stdout,
        aps_json::Limits {
            max_bytes: MAX_RUN_BYTES as u64,
            max_depth: 24,
            max_nodes: 100_000,
            max_string_bytes: MAX_RUN_BYTES,
            max_collection_items: 10_000,
        },
    )
    .map_err(|_| ClaudeError::Protocol)?;
    let object = value.as_object().ok_or(ClaudeError::Protocol)?;
    if object.get("type").and_then(aps_json::Value::as_str) != Some("result")
        || object.get("is_error").and_then(aps_json::Value::as_bool) != Some(false)
        || object.get("subtype").and_then(aps_json::Value::as_str) != Some("success")
    {
        return Err(ClaudeError::Protocol);
    }
    let structured = object
        .get("structured_output")
        .and_then(aps_json::Value::as_object)
        .ok_or(ClaudeError::Protocol)?;
    aps_json::to_vec(
        &aps_json::Value::Object(structured.clone()),
        aps_json::Limits {
            max_bytes: MAX_RUN_BYTES as u64,
            max_depth: 24,
            max_nodes: 100_000,
            max_string_bytes: MAX_RUN_BYTES,
            max_collection_items: 10_000,
        },
    )
    .map_err(|_| ClaudeError::Protocol)
    .and_then(|bytes| String::from_utf8(bytes).map_err(|_| ClaudeError::Protocol))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_a_success_envelope_only() {
        let success = br#"{"type":"result","is_error":false,"subtype":"success","structured_output":{"summary":"ok","findings":[]}}"#;
        let parsed = parse_envelope(success).unwrap();
        assert!(parsed.contains("\"summary\":\"ok\""));
        let failure =
            br#"{"type":"result","is_error":true,"subtype":"success","structured_output":{}}"#;
        assert_eq!(parse_envelope(failure).unwrap_err(), ClaudeError::Protocol);
        assert_eq!(
            parse_envelope(b"not json").unwrap_err(),
            ClaudeError::Protocol
        );
    }

    #[test]
    fn environment_uses_the_selected_credential_kind() {
        let credential = crate::platform::AiSecret::new("sk-ant-test-123").unwrap();
        let key = claude_environment(
            "https://api.anthropic.com",
            crate::platform::CredentialKind::ApiKey,
            &credential,
        )
        .unwrap();
        assert_eq!(
            key.get(&OsString::from("ANTHROPIC_API_KEY")).unwrap(),
            "sk-ant-test-123"
        );
        assert!(!key.contains_key(&OsString::from("ANTHROPIC_BASE_URL")));
        let token = claude_environment(
            "https://gateway.example.com",
            crate::platform::CredentialKind::BearerToken,
            &credential,
        )
        .unwrap();
        assert_eq!(
            token.get(&OsString::from("ANTHROPIC_AUTH_TOKEN")).unwrap(),
            "sk-ant-test-123"
        );
        assert_eq!(
            token.get(&OsString::from("ANTHROPIC_BASE_URL")).unwrap(),
            "https://gateway.example.com"
        );
    }
}

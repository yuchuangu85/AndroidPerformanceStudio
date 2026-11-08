//! OpenAI Responses API client for the Codex and Custom connections.
//!
//! Non-streaming, tool-free, strict JSON schema. Mirrors the Compose
//! OpenAiResponsesClient behavior: `store=false`, empty tools, tool_choice none,
//! parallel tool calls off, max_output_tokens, instructions + input + text format.
//! Responses that contain tools, refusals, incomplete phases or the resolved
//! credential are rejected before anything can be published.

use crate::platform::AiSecret;
use aps_json::Value;
use aps_platform::CancellationToken;
use futures::AsyncReadExt;
use http_client::{AsyncBody, HttpClient, HttpRequestExt, Method, RedirectPolicy, Request, http};
use std::fmt;
use std::sync::Arc;
use std::time::Duration;

const TOTAL_TIMEOUT: Duration = Duration::from_secs(120);
const ENVELOPE_LIMIT: usize = 1024 * 1024;
const MAX_OUTPUT_TOKENS: u32 = 8192;

pub struct ResponsesRequest<'a> {
    pub endpoint: &'a str,
    pub model: &'a str,
    pub instructions: &'a str,
    pub input: &'a str,
    pub schema_name: &'a str,
    pub schema: &'a Value,
}

pub struct ResponsesAnswer {
    pub text: String,
    pub model: String,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ResponsesError {
    Credential,
    Configuration,
    Transport,
    Timeout,
    HttpStatus(u16),
    InvalidResponse,
    Incomplete,
    ToolResponse,
    Phase,
    MissingOutput,
    Refusal,
    Content,
    Limit,
    SecretInResponse,
}

impl fmt::Display for ResponsesError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::Credential => "E_AI_CREDENTIAL_UNAVAILABLE",
            Self::Configuration => "E_AI_CONFIGURATION",
            Self::Transport => "E_AI_TRANSPORT",
            Self::Timeout => "E_PROVIDER_TIMEOUT",
            Self::HttpStatus(_) => "E_AI_HTTP_STATUS",
            Self::InvalidResponse => "E_AI_RESPONSE_SCHEMA",
            Self::Incomplete => "E_AI_RESPONSE_INCOMPLETE",
            Self::ToolResponse => "E_AI_TOOL_RESPONSE",
            Self::Phase => "E_AI_RESPONSE_PHASE",
            Self::MissingOutput => "E_AI_FINAL_OUTPUT_MISSING",
            Self::Refusal => "E_AI_REFUSAL",
            Self::Content => "E_AI_RESPONSE_CONTENT",
            Self::Limit => "E_AI_RESPONSE_LIMIT",
            Self::SecretInResponse => "E_AI_SECRET_IN_RESPONSE",
        })
    }
}
impl std::error::Error for ResponsesError {}

pub fn request_body(request: &ResponsesRequest<'_>) -> Vec<u8> {
    let body = crate::chat::object([
        ("model", request.model.into()),
        ("store", Value::Bool(false)),
        ("tools", Value::Array(Vec::new())),
        ("tool_choice", "none".into()),
        ("parallel_tool_calls", Value::Bool(false)),
        ("max_output_tokens", Value::from(MAX_OUTPUT_TOKENS)),
        ("instructions", request.instructions.into()),
        ("input", request.input.into()),
        (
            "text",
            crate::chat::object([(
                "format",
                crate::chat::object([
                    ("type", "json_schema".into()),
                    ("name", request.schema_name.into()),
                    ("strict", Value::Bool(true)),
                    ("schema", request.schema.clone()),
                ]),
            )]),
        ),
    ]);
    aps_json::to_vec(&body, crate::model::json_limits(1024 * 1024)).unwrap_or_default()
}

/// Run one explicit, already-approved request. The credential is read only here
/// and is never logged or included in error text.
pub async fn execute(
    client: &Arc<dyn HttpClient>,
    credential: &AiSecret,
    request: &ResponsesRequest<'_>,
    token: &CancellationToken,
) -> Result<ResponsesAnswer, ResponsesError> {
    if credential.is_empty() {
        return Err(ResponsesError::Credential);
    }
    let body = request_body(request);
    if body.is_empty() {
        return Err(ResponsesError::Configuration);
    }
    let authorization = credential_header(credential)?;
    let request = Request::builder()
        .method(Method::POST)
        .uri(request.endpoint)
        .header(http::header::AUTHORIZATION, authorization)
        .header(http::header::CONTENT_TYPE, "application/json")
        .header(http::header::ACCEPT, "application/json")
        .follow_redirects(RedirectPolicy::NoFollow)
        .timeout(TOTAL_TIMEOUT)
        .body(AsyncBody::from(body))
        .map_err(|_| ResponsesError::Configuration)?;
    let response = select_or_timeout(client.send(request), token).await?;
    let response = response.map_err(|_| ResponsesError::Transport)?;
    let status = response.status().as_u16();
    if status != 200 {
        return Err(match status {
            408 | 504 => ResponsesError::Timeout,
            _ => ResponsesError::HttpStatus(status),
        });
    }
    let content_type = response
        .headers()
        .get(http::header::CONTENT_TYPE)
        .and_then(|value| value.to_str().ok())
        .unwrap_or_default()
        .to_owned();
    if !content_type
        .split(';')
        .next()
        .unwrap_or("")
        .trim()
        .eq_ignore_ascii_case("application/json")
    {
        return Err(ResponsesError::InvalidResponse);
    }
    let mut body = Vec::new();
    let mut stream = response.into_body();
    let mut buffer = [0u8; 8192];
    loop {
        if token.is_cancelled() {
            return Err(ResponsesError::Transport);
        }
        let read = stream
            .read(&mut buffer)
            .await
            .map_err(|_| ResponsesError::Transport)?;
        if read == 0 {
            break;
        }
        if body.len() + read > ENVELOPE_LIMIT {
            return Err(ResponsesError::Limit);
        }
        body.extend_from_slice(&buffer[..read]);
    }
    if credential_appears(credential, &body) {
        return Err(ResponsesError::SecretInResponse);
    }
    let (text, model) = parse_output(&body)?;
    if credential_appears(credential, text.as_bytes())
        || credential_appears(credential, model.as_bytes())
    {
        return Err(ResponsesError::SecretInResponse);
    }
    Ok(ResponsesAnswer { text, model })
}

fn credential_header(credential: &AiSecret) -> Result<http::HeaderValue, ResponsesError> {
    let mut bytes = b"Bearer ".to_vec();
    bytes.extend_from_slice(credential.as_bytes());
    let mut value =
        http::HeaderValue::from_bytes(&bytes).map_err(|_| ResponsesError::Credential)?;
    bytes.fill(0);
    value.set_sensitive(true);
    Ok(value)
}

fn credential_appears(credential: &AiSecret, haystack: &[u8]) -> bool {
    let needle = credential.as_bytes();
    !needle.is_empty()
        && haystack
            .windows(needle.len())
            .any(|window| window == needle)
}

async fn select_or_timeout<T>(
    operation: impl std::future::Future<Output = T>,
    token: &CancellationToken,
) -> Result<T, ResponsesError> {
    use futures::future::{Either, select};
    use futures::pin_mut;
    let monitor = async {
        loop {
            if token.is_cancelled() {
                return;
            }
            async_io::Timer::after(Duration::from_millis(25)).await;
        }
    };
    pin_mut!(operation, monitor);
    match select(operation, monitor).await {
        Either::Left((value, _)) => Ok(value),
        Either::Right(((), _)) => Err(ResponsesError::Transport),
    }
}

/// Extract the single final answer text from a Responses envelope. Rejects
/// tools, refusals, incomplete phases and ambiguous multiple final messages.
pub fn parse_output(body: &[u8]) -> Result<(String, String), ResponsesError> {
    let root = aps_json::parse_slice(body, crate::model::json_limits(ENVELOPE_LIMIT as u64))
        .map_err(|_| ResponsesError::InvalidResponse)?;
    let object = root.as_object().ok_or(ResponsesError::InvalidResponse)?;
    if object.get("error").is_some() {
        return Err(ResponsesError::InvalidResponse);
    }
    let status = object.get("status").and_then(Value::as_str);
    if !matches!(status, None | Some("completed")) {
        return Err(ResponsesError::Incomplete);
    }
    let model = object
        .get("model")
        .and_then(Value::as_str)
        .ok_or(ResponsesError::InvalidResponse)?;
    if model.is_empty() || model.len() > 256 || model.chars().any(char::is_control) {
        return Err(ResponsesError::InvalidResponse);
    }
    if let Some(output) = object.get("output").and_then(Value::as_array) {
        let mut messages = Vec::new();
        for item in output {
            let item = item.as_object().ok_or(ResponsesError::InvalidResponse)?;
            match item.get("type").and_then(Value::as_str) {
                Some("reasoning") => {}
                Some("message") => messages.push(item),
                _ => return Err(ResponsesError::ToolResponse),
            }
        }
        let explicit_final = messages
            .iter()
            .filter(|message| message.get("phase").and_then(Value::as_str) == Some("final_answer"))
            .copied()
            .collect::<Vec<_>>();
        let candidates: Vec<_> = if !explicit_final.is_empty() {
            explicit_final
        } else {
            messages
                .iter()
                .filter(|message| {
                    !matches!(
                        message.get("phase").and_then(Value::as_str),
                        Some("commentary")
                    )
                })
                .copied()
                .collect()
        };
        for message in &messages {
            if !matches!(
                message.get("phase").and_then(Value::as_str),
                None | Some("commentary") | Some("final_answer")
            ) {
                return Err(ResponsesError::Phase);
            }
        }
        if candidates.len() != 1 {
            return Err(ResponsesError::MissingOutput);
        }
        let message = candidates[0];
        let content = message
            .get("content")
            .and_then(Value::as_array)
            .ok_or(ResponsesError::MissingOutput)?;
        let mut text = String::new();
        for item in content {
            let item = item.as_object().ok_or(ResponsesError::MissingOutput)?;
            match item.get("type").and_then(Value::as_str) {
                Some("refusal") => return Err(ResponsesError::Refusal),
                Some("output_text") => {
                    let value = item
                        .get("text")
                        .and_then(Value::as_str)
                        .ok_or(ResponsesError::MissingOutput)?;
                    text.push_str(value);
                }
                _ => return Err(ResponsesError::Content),
            }
        }
        if text.is_empty() || text.len() > 256 * 1024 {
            return Err(if text.is_empty() {
                ResponsesError::MissingOutput
            } else {
                ResponsesError::Limit
            });
        }
        return Ok((text, model.to_owned()));
    }
    if let Some(text) = object.get("output_text").and_then(Value::as_str) {
        if text.is_empty() || text.len() > 256 * 1024 {
            return Err(ResponsesError::Limit);
        }
        return Ok((text.to_owned(), model.to_owned()));
    }
    Err(ResponsesError::MissingOutput)
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_json::Value;

    fn envelope(content_type: &str, text: &str) -> Vec<u8> {
        let body = crate::chat::object([
            ("status", "completed".into()),
            ("model", "test-model".into()),
            (
                "output",
                Value::Array(vec![crate::chat::object([
                    ("type", "message".into()),
                    (
                        "content",
                        Value::Array(vec![crate::chat::object([
                            ("type", content_type.into()),
                            ("text", text.into()),
                        ])]),
                    ),
                ])]),
            ),
        ]);
        aps_json::to_vec(&body, crate::model::json_limits(1024 * 1024)).unwrap()
    }

    #[test]
    fn extracts_single_final_answer() {
        let (text, model) = parse_output(&envelope("output_text", "hello")).unwrap();
        assert_eq!(text, "hello");
        assert_eq!(model, "test-model");
    }

    #[test]
    fn rejects_refusal_and_tools() {
        assert_eq!(
            parse_output(&envelope("refusal", "")).unwrap_err(),
            ResponsesError::Refusal
        );
        let tool = crate::chat::object([
            ("status", "completed".into()),
            ("model", "m".into()),
            (
                "output",
                Value::Array(vec![crate::chat::object([(
                    "type",
                    "function_call".into(),
                )])]),
            ),
        ]);
        let bytes = aps_json::to_vec(&tool, crate::model::json_limits(1024 * 1024)).unwrap();
        assert_eq!(
            parse_output(&bytes).unwrap_err(),
            ResponsesError::ToolResponse
        );
    }

    #[test]
    fn request_body_freezes_tools_off_and_strict_schema() {
        let schema = Value::Object(Default::default());
        let body = request_body(&ResponsesRequest {
            endpoint: "https://example.com/v1/responses",
            model: "m",
            instructions: "i",
            input: "{}",
            schema_name: "s",
            schema: &schema,
        });
        let value = aps_json::parse_slice(&body, crate::model::json_limits(1024 * 1024)).unwrap();
        assert_eq!(value.get("store").and_then(Value::as_bool), Some(false));
        assert_eq!(
            value.get("tool_choice").and_then(Value::as_str),
            Some("none")
        );
        assert_eq!(value.get("stream"), None);
    }
}

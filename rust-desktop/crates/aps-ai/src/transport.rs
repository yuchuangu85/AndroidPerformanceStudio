use crate::{
    AiError, AnalysisResult, AuthorizedRun, CredentialReference, SseDecoder, StreamEvent,
    StreamLimits, bounded_text,
};
use aps_json::Value;
use aps_platform::CancellationToken;
use futures::{
    AsyncReadExt, Future,
    future::{BoxFuture, Either, select},
    pin_mut,
};
use http_client::{
    AsyncBody, HttpClient, HttpRequestExt, Method, RedirectPolicy, Request, Response, http,
};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

const TOTAL_TIMEOUT: Duration = Duration::from_secs(120);
const IDLE_TIMEOUT: Duration = Duration::from_secs(30);
const ENVELOPE_LIMIT: usize = 1024 * 1024;

/// Ephemeral secret returned only to the transport lane. Not Debug, Clone or
/// Serialize, and no public text accessor. Memory clearing is best effort, not a
/// guarantee of zeroization of copies made by the OS/TLS/HTTP implementation.
pub struct SecretCredential(Vec<u8>);

impl SecretCredential {
    pub fn new(bytes: Vec<u8>) -> Result<Self, AiError> {
        let value = Self(bytes);
        if value.0.len() < 8
            || value.0.len() > 4096
            || !value.0.iter().all(|byte| byte.is_ascii_graphic())
        {
            return Err(AiError::CredentialUnavailable);
        }
        Ok(value)
    }

    fn authorization_header(&self) -> Result<http::HeaderValue, AiError> {
        let mut bytes = b"Bearer ".to_vec();
        bytes.extend_from_slice(&self.0);
        let value = http::HeaderValue::from_bytes(&bytes);
        bytes.fill(0);
        let mut value = value.map_err(|_| AiError::CredentialUnavailable)?;
        value.set_sensitive(true);
        Ok(value)
    }

    fn appears_in(&self, bytes: &[u8]) -> bool {
        bytes.windows(self.0.len()).any(|window| window == self.0)
    }

    fn appears_in_value(&self, value: &Value) -> bool {
        match value {
            Value::String(value) => self.appears_in(value.as_bytes()),
            Value::Array(values) => values.iter().any(|value| self.appears_in_value(value)),
            Value::Object(values) => values.iter().any(|(key, value)| {
                self.appears_in(key.as_bytes()) || self.appears_in_value(value)
            }),
            _ => false,
        }
    }
}

impl Drop for SecretCredential {
    fn drop(&mut self) {
        self.0.fill(0);
    }
}

/// Inject a trusted host resolver (e.g. a separate keychain adapter). This crate
/// never scans environment variables, legacy settings, .env files or keychains.
pub trait CredentialResolver: Send + Sync {
    fn resolve<'a>(
        &'a self,
        reference: &'a CredentialReference,
    ) -> BoxFuture<'a, Result<SecretCredential, AiError>>;
}

/// User-supplied memory-only credential, bound to exactly one opaque reference.
/// No discovery, persistence, clone accessor or second resolution is available.
/// Intended for an explicit masked host UI field; clearing all upstream text
/// copies remains the caller's responsibility.
pub struct OneShotCredential {
    reference: CredentialReference,
    secret: Mutex<Option<SecretCredential>>,
}
impl OneShotCredential {
    pub fn new(reference: CredentialReference, secret: SecretCredential) -> Self {
        Self {
            reference,
            secret: Mutex::new(Some(secret)),
        }
    }
}
impl CredentialResolver for OneShotCredential {
    fn resolve<'a>(
        &'a self,
        reference: &'a CredentialReference,
    ) -> BoxFuture<'a, Result<SecretCredential, AiError>> {
        Box::pin(async move {
            if reference != &self.reference {
                return Err(AiError::CredentialUnavailable);
            }
            self.secret
                .lock()
                .map_err(|_| AiError::CredentialUnavailable)?
                .take()
                .ok_or(AiError::CredentialUnavailable)
        })
    }
}

/// OpenAI-compatible Chat Completions transport; not an unrestricted agent.
/// Requires a trusted HTTP client honoring NoFollow, cancellation/drop and
/// sensitive-header semantics. Constructing the gateway does not make requests.
pub struct OpenAiGateway {
    client: Arc<dyn HttpClient>,
    credentials: Arc<dyn CredentialResolver>,
}

impl OpenAiGateway {
    pub fn new(client: Arc<dyn HttpClient>, credentials: Arc<dyn CredentialResolver>) -> Self {
        Self {
            client,
            credentials,
        }
    }

    pub async fn execute(&self, run: AuthorizedRun) -> Result<AnalysisResult, AiError> {
        self.execute_with_progress(run, |_| {}).await
    }

    /// Progress is a byte count only. Raw provider text cannot reach UI state
    /// before schema/reference/secret validation has succeeded. Invoke on the
    /// host background executor; callers must not block the GPUI render thread.
    pub async fn execute_with_progress(
        &self,
        run: AuthorizedRun,
        mut progress: impl FnMut(usize),
    ) -> Result<AnalysisResult, AiError> {
        if Instant::now() >= run.expires {
            return Err(AiError::ApprovalRequired);
        }
        let deadline = Instant::now()
            .checked_add(TOTAL_TIMEOUT)
            .ok_or(AiError::Timeout)?;
        let prepared = run.prepared;
        let token = run.cancellation;
        let credential = guarded(
            self.credentials
                .resolve(prepared.provider.credential_reference()),
            &token,
            deadline,
        )
        .await??;
        check_cancelled(&token)?;
        if Instant::now() >= run.expires {
            return Err(AiError::ApprovalRequired);
        }
        // Resolve only after approval, then prevent this credential from being
        // accidentally included as evidence/question/source/model content.
        if credential.appears_in_value(&prepared.request.payload())
            || credential.appears_in(prepared.provider.model().as_bytes())
        {
            return Err(AiError::InvalidContext);
        }
        let request = Request::builder()
            .method(Method::POST)
            .uri(prepared.provider.endpoint())
            .header(
                http::header::AUTHORIZATION,
                credential.authorization_header()?,
            )
            .header(http::header::CONTENT_TYPE, "application/json")
            .header(
                http::header::ACCEPT,
                if prepared.streaming {
                    "text/event-stream"
                } else {
                    "application/json"
                },
            )
            .follow_redirects(RedirectPolicy::NoFollow)
            .timeout(TOTAL_TIMEOUT)
            .body(AsyncBody::from(prepared.body.clone()))
            .map_err(|_| AiError::InvalidConfiguration)?;
        let response = guarded(self.client.send(request), &token, deadline)
            .await?
            .map_err(|_| AiError::Transport)?;
        if response.status() != http::StatusCode::OK {
            return Err(AiError::HttpStatus(response.status().as_u16()));
        }
        let (content, effective_model) = if prepared.streaming {
            read_stream(response, &token, deadline, &mut progress).await?
        } else {
            validate_headers(&response, "application/json", ENVELOPE_LIMIT)?;
            let bytes =
                read_bounded(response.into_body(), ENVELOPE_LIMIT, &token, deadline).await?;
            if credential.appears_in(&bytes) {
                return Err(AiError::SecretInResponse);
            }
            let value = aps_json::parse_slice(&bytes, envelope_limits())
                .map_err(|_| AiError::InvalidResponse)?;
            if credential.appears_in_value(&value) {
                return Err(AiError::SecretInResponse);
            }
            decode_completion(&value)?
        };
        check_cancelled(&token)?;
        if credential.appears_in(content.as_bytes())
            || credential.appears_in(effective_model.as_bytes())
        {
            return Err(AiError::SecretInResponse);
        }
        let decoded =
            aps_json::parse_slice(content.as_bytes(), super::model::json_limits(256 * 1024))
                .map_err(|_| AiError::InvalidResponse)?;
        if credential.appears_in_value(&decoded) {
            return Err(AiError::SecretInResponse);
        }
        let result =
            AnalysisResult::validate(content.as_bytes(), &prepared.request, &effective_model)?;
        check_cancelled(&token)?;
        Ok(result)
    }
}

fn decode_completion(value: &Value) -> Result<(String, String), AiError> {
    if value.get("error").is_some()
        || value.get("object").and_then(Value::as_str) != Some("chat.completion")
    {
        return Err(AiError::InvalidResponse);
    }
    let model = value
        .get("model")
        .and_then(Value::as_str)
        .ok_or(AiError::InvalidResponse)?;
    bounded_text(model, 256).map_err(|_| AiError::InvalidResponse)?;
    let choices = value
        .get("choices")
        .and_then(Value::as_array)
        .ok_or(AiError::InvalidResponse)?;
    if choices.len() != 1 || choices[0].get("index").and_then(Value::as_u64) != Some(0) {
        return Err(AiError::InvalidResponse);
    }
    match choices[0].get("finish_reason").and_then(Value::as_str) {
        Some("stop") => {}
        Some("length") => return Err(AiError::TruncatedResponse),
        _ => return Err(AiError::InvalidResponse),
    }
    let message = choices[0]
        .get("message")
        .and_then(Value::as_object)
        .ok_or(AiError::InvalidResponse)?;
    if message.get("role").and_then(Value::as_str) != Some("assistant") {
        return Err(AiError::InvalidResponse);
    }
    for key in ["refusal", "tool_calls", "function_call"] {
        if message.get(key).is_some_and(|value| !value.is_null()) {
            return Err(AiError::InvalidResponse);
        }
    }
    let content = message
        .get("content")
        .and_then(Value::as_str)
        .ok_or(AiError::InvalidResponse)?;
    if content.is_empty() || content.len() > 256 * 1024 {
        return Err(AiError::ResponseLimit);
    }
    Ok((content.into(), model.into()))
}

fn validate_headers(
    response: &Response<AsyncBody>,
    expected_type: &str,
    limit: usize,
) -> Result<(), AiError> {
    let content_type = response
        .headers()
        .get(http::header::CONTENT_TYPE)
        .and_then(|value| value.to_str().ok())
        .ok_or(AiError::InvalidResponse)?;
    if !content_type
        .split(';')
        .next()
        .unwrap_or("")
        .trim()
        .eq_ignore_ascii_case(expected_type)
    {
        return Err(AiError::InvalidResponse);
    }
    if let Some(length) = response.headers().get(http::header::CONTENT_LENGTH) {
        let length = length
            .to_str()
            .ok()
            .and_then(|value| value.parse::<u64>().ok())
            .ok_or(AiError::InvalidResponse)?;
        if length > limit as u64 {
            return Err(AiError::ResponseLimit);
        }
    }
    Ok(())
}

async fn read_stream(
    response: Response<AsyncBody>,
    token: &CancellationToken,
    deadline: Instant,
    progress: &mut impl FnMut(usize),
) -> Result<(String, String), AiError> {
    let limits = StreamLimits::default();
    validate_headers(&response, "text/event-stream", limits.max_wire_bytes)?;
    let mut body = response.into_body();
    let mut decoder = SseDecoder::new(limits)?;
    let mut content = String::new();
    let mut buffer = [0; 8192];
    loop {
        let count = guarded(body.read(&mut buffer), token, idle_deadline(deadline)?)
            .await?
            .map_err(|_| AiError::Transport)?;
        if count == 0 {
            break;
        }
        for event in decoder.push(&buffer[..count])? {
            if let StreamEvent::TextDelta(delta) = event {
                content.push_str(&delta);
            }
        }
        check_cancelled(token)?;
        progress(decoder.content_bytes());
    }
    let model = decoder
        .effective_model()
        .ok_or(AiError::InvalidResponse)?
        .to_owned();
    decoder.finish()?;
    if content.is_empty() {
        return Err(AiError::InvalidResponse);
    }
    Ok((content, model))
}

async fn read_bounded(
    mut body: AsyncBody,
    limit: usize,
    token: &CancellationToken,
    deadline: Instant,
) -> Result<Vec<u8>, AiError> {
    let mut bytes = Vec::new();
    let mut buffer = [0; 8192];
    loop {
        let count = guarded(body.read(&mut buffer), token, idle_deadline(deadline)?)
            .await?
            .map_err(|_| AiError::Transport)?;
        if count == 0 {
            return Ok(bytes);
        }
        if bytes.len().saturating_add(count) > limit {
            return Err(AiError::ResponseLimit);
        }
        bytes.extend_from_slice(&buffer[..count]);
    }
}

fn envelope_limits() -> aps_json::Limits {
    aps_json::Limits {
        max_string_bytes: 256 * 1024,
        ..super::model::json_limits(ENVELOPE_LIMIT as u64)
    }
}

fn idle_deadline(deadline: Instant) -> Result<Instant, AiError> {
    Ok(deadline.min(
        Instant::now()
            .checked_add(IDLE_TIMEOUT)
            .ok_or(AiError::Timeout)?,
    ))
}

fn check_cancelled(token: &CancellationToken) -> Result<(), AiError> {
    if token.is_cancelled() {
        Err(AiError::Cancelled)
    } else {
        Ok(())
    }
}

async fn guarded<T>(
    operation: impl Future<Output = T>,
    token: &CancellationToken,
    deadline: Instant,
) -> Result<T, AiError> {
    check_cancelled(token)?;
    if Instant::now() >= deadline {
        return Err(AiError::Timeout);
    }
    let monitor = async {
        loop {
            check_cancelled(token)?;
            let remaining = deadline
                .checked_duration_since(Instant::now())
                .ok_or(AiError::Timeout)?;
            async_io::Timer::after(remaining.min(Duration::from_millis(25))).await;
        }
        #[allow(unreachable_code)]
        Ok::<(), AiError>(())
    };
    pin_mut!(operation, monitor);
    match select(operation, monitor).await {
        Either::Left((value, _)) => {
            check_cancelled(token)?;
            if Instant::now() >= deadline {
                return Err(AiError::Timeout);
            }
            Ok(value)
        }
        Either::Right((result, _)) => {
            result?;
            Err(AiError::Timeout)
        }
    }
}

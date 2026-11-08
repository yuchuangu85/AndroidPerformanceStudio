//! Bounded provider model-catalog discovery.
//!
//! Ports Compose OpenAiModelCatalog and ClaudeModelCatalog over an injected
//! http_client client: GET only, JSON accept, NoFollow redirects, bounded body
//! (1 MiB), bounded wire header (2 MiB), 30 s total timeout and no retries.
//! Discovery never streams, never logs a body and never stores a credential.
//! Errors are stable E_ codes only.

use crate::platform::{AiSecret, CredentialKind, ProviderKind};
use aps_json::Value;
use futures::AsyncReadExt;
use http_client::{
    AsyncBody, HttpClient, HttpRequestExt, Method, RedirectPolicy, Request, Response, Url, http,
};
use std::collections::BTreeSet;
use std::fmt;
use std::sync::Arc;
use std::time::Duration;

const MAX_CATALOG_BYTES: usize = 1024 * 1024;
const MAX_WIRE_BYTES: u64 = 2 * 1024 * 1024;
const MAX_CATALOG_MODELS: usize = 10_000;
const MAX_MODEL_ID_CHARS: usize = 256;
const MAX_CLAUDE_PAGES: usize = 100;
const CATALOG_TIMEOUT: Duration = Duration::from_secs(30);

/// Stable catalog failure taxonomy. No response body, URL or credential is
/// attached.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ModelCatalogError {
    InvalidEndpoint,
    HtmlResponse,
    InvalidResponse,
    Authentication,
    RateLimit,
    Timeout,
    Network,
    Http(u16),
    Credential,
}

impl fmt::Display for ModelCatalogError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::InvalidEndpoint => f.write_str("E_AI_MODEL_CATALOG_INVALID_ENDPOINT"),
            Self::HtmlResponse => f.write_str("E_AI_MODEL_CATALOG_HTML_RESPONSE"),
            Self::InvalidResponse => f.write_str("E_AI_MODEL_CATALOG_INVALID_RESPONSE"),
            Self::Authentication => f.write_str("E_AI_MODEL_CATALOG_AUTHENTICATION"),
            Self::RateLimit => f.write_str("E_AI_MODEL_CATALOG_RATE_LIMIT"),
            Self::Timeout => f.write_str("E_AI_MODEL_CATALOG_TIMEOUT"),
            Self::Network => f.write_str("E_AI_MODEL_CATALOG_NETWORK"),
            Self::Http(_) => f.write_str("E_AI_HTTP_STATUS"),
            Self::Credential => f.write_str("E_AI_MODEL_CATALOG_CREDENTIAL"),
        }
    }
}
impl std::error::Error for ModelCatalogError {}

/// OpenAI/Codex model list endpoint. A /responses suffix is replaced by the
/// sibling /models path; any other base (a gateway directory such as /proxy/v1)
/// gains /models without losing its prefix. An explicit models endpoint must
/// share scheme, host and effective port with the responses endpoint.
pub fn openai_models_url(
    endpoint: &str,
    models_endpoint_override: Option<&str>,
) -> Result<Url, ModelCatalogError> {
    openai_catalog_urls(endpoint, models_endpoint_override).map(|(models, _)| models)
}

/// Claude model list endpoint. /models stays, /messages becomes the sibling
/// /models, /v1 gains /models and any other base gains /v1/models.
pub fn claude_models_url(endpoint: &str) -> Result<Url, ModelCatalogError> {
    let normalized = endpoint.trim().trim_end_matches('/');
    // Compose validates through OpenAiModelCatalog.validateEndpoints.
    openai_models_url(normalized, None)?;
    let uri = parse_catalog_endpoint(normalized)?;
    let final_segment = decoded_final_segment(uri.path());
    let value = match final_segment.as_str() {
        "/models" => normalized.to_owned(),
        "/messages" => format!("{}/models", base_before_last_segment(normalized)),
        "/v1" => format!("{normalized}/models"),
        _ => format!("{normalized}/v1/models"),
    };
    Url::parse(&value).map_err(|_| ModelCatalogError::InvalidEndpoint)
}

/// Discover model ids for one provider connection. The caller runs this on a
/// background executor; it performs at most one GET for OpenAI/Codex (plus the
/// single documented Responses-gateway fallback) or one paginated sequence for
/// Claude. No retries and no streaming.
pub async fn discover(
    client: Arc<dyn HttpClient>,
    provider: ProviderKind,
    endpoint: &str,
    models_endpoint: Option<&str>,
    credential_kind: CredentialKind,
    credential: &AiSecret,
) -> Result<Vec<String>, ModelCatalogError> {
    if credential.is_empty() {
        return Err(ModelCatalogError::Credential);
    }
    let header = CredentialHeader::new(provider, credential_kind, credential)?;
    bounded(
        discover_inner(client, provider, endpoint, models_endpoint, header),
        CATALOG_TIMEOUT,
    )
    .await
}

async fn discover_inner(
    client: Arc<dyn HttpClient>,
    provider: ProviderKind,
    endpoint: &str,
    models_endpoint: Option<&str>,
    header: CredentialHeader,
) -> Result<Vec<String>, ModelCatalogError> {
    match provider {
        ProviderKind::Claude => discover_claude(client, endpoint, &header).await,
        ProviderKind::Codex | ProviderKind::Custom => {
            discover_openai(client, endpoint, models_endpoint, &header).await
        }
    }
}

async fn discover_openai(
    client: Arc<dyn HttpClient>,
    endpoint: &str,
    models_endpoint: Option<&str>,
    header: &CredentialHeader,
) -> Result<Vec<String>, ModelCatalogError> {
    let (models_url, fallback) = openai_catalog_urls(endpoint, models_endpoint)?;
    let first = request_json(client.clone(), &models_url, header, None).await;
    match first.and_then(|body| parse_openai_models(&body)) {
        Ok(models) => Ok(models),
        Err(ModelCatalogError::HtmlResponse) => match fallback {
            Some(fallback_url) => {
                // Root Responses gateways may serve a website at /models and
                // the API at /v1/models.
                let body = request_json(client, &fallback_url, header, None).await?;
                parse_openai_models(&body)
            }
            None => Err(ModelCatalogError::HtmlResponse),
        },
        Err(error) => Err(error),
    }
}

async fn discover_claude(
    client: Arc<dyn HttpClient>,
    endpoint: &str,
    header: &CredentialHeader,
) -> Result<Vec<String>, ModelCatalogError> {
    let models_url = claude_models_url(endpoint)?;
    let mut models = Vec::new();
    let mut identities = BTreeSet::new();
    let mut cursors = BTreeSet::new();
    let mut cursor: Option<String> = None;
    for _ in 0..MAX_CLAUDE_PAGES {
        let body = request_json(client.clone(), &models_url, header, cursor.as_deref()).await?;
        let page = parse_claude_page(&body)?;
        let page_empty = page.models.is_empty();
        if page.models.len() > MAX_CATALOG_MODELS - models.len() {
            return Err(ModelCatalogError::InvalidResponse);
        }
        for model in page.models {
            if !identities.insert(model.clone()) {
                return Err(ModelCatalogError::InvalidResponse);
            }
            models.push(model);
        }
        if !page.has_more {
            models.sort();
            return Ok(models);
        }
        let next = page.last_id.ok_or(ModelCatalogError::InvalidResponse)?;
        if page_empty || !cursors.insert(next.clone()) {
            return Err(ModelCatalogError::InvalidResponse);
        }
        cursor = Some(next);
    }
    Err(ModelCatalogError::InvalidResponse)
}

// ---------------------------------------------------------------------------
// Endpoints
// ---------------------------------------------------------------------------

fn openai_catalog_urls(
    endpoint: &str,
    models_endpoint_override: Option<&str>,
) -> Result<(Url, Option<Url>), ModelCatalogError> {
    let normalized = endpoint.trim().trim_end_matches('/');
    let endpoint_url = parse_catalog_endpoint(normalized)?;
    let explicit = models_endpoint_override
        .map(str::trim)
        .filter(|value| !value.is_empty());
    let models = if let Some(explicit) = explicit {
        let models = parse_catalog_endpoint(explicit)?;
        if endpoint_url.scheme() != models.scheme()
            || endpoint_url.host_str() != models.host_str()
            || effective_catalog_port(&endpoint_url) != effective_catalog_port(&models)
        {
            return Err(ModelCatalogError::InvalidEndpoint);
        }
        models
    } else {
        // A base such as /proxy/v1 is a directory, not a file to replace via
        // relative resolution. Decode only the final raw segment so encoded
        // prefixes and literal encoded slashes stay intact.
        let final_segment = decoded_final_segment(endpoint_url.path());
        let base = if final_segment == "/responses" {
            base_before_last_segment(normalized)
        } else {
            normalized
        };
        let value = if final_segment == "/models" {
            normalized.to_owned()
        } else {
            format!("{base}/models")
        };
        Url::parse(&value).map_err(|_| ModelCatalogError::InvalidEndpoint)?
    };
    let fallback = if explicit.is_none() && endpoint_url.path() == "/responses" {
        Some(
            models
                .join("/v1/models")
                .map_err(|_| ModelCatalogError::InvalidEndpoint)?,
        )
    } else {
        None
    };
    Ok((models, fallback))
}

fn parse_catalog_endpoint(value: &str) -> Result<Url, ModelCatalogError> {
    let url = Url::parse(value).map_err(|_| ModelCatalogError::InvalidEndpoint)?;
    if url.host_str().is_none_or(str::is_empty)
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
        || url.port() == Some(0)
    {
        return Err(ModelCatalogError::InvalidEndpoint);
    }
    let host = url.host_str().unwrap_or_default().to_ascii_lowercase();
    let loopback = matches!(host.as_str(), "localhost" | "127.0.0.1" | "::1" | "[::1]");
    let safe_transport = url.scheme() == "https" || url.scheme() == "http" && loopback;
    if !safe_transport {
        return Err(ModelCatalogError::InvalidEndpoint);
    }
    Ok(url)
}

fn effective_catalog_port(url: &Url) -> Option<u16> {
    url.port_or_known_default()
}

fn base_before_last_segment(value: &str) -> &str {
    value.rsplit_once('/').map_or(value, |(base, _)| base)
}

fn decoded_final_segment(path: &str) -> String {
    let raw = path.rsplit('/').next().unwrap_or("");
    format!("/{}", percent_decode(raw))
}

fn percent_decode(raw: &str) -> String {
    let bytes = raw.as_bytes();
    let mut result = Vec::with_capacity(bytes.len());
    let mut index = 0;
    while index < bytes.len() {
        if bytes[index] == b'%' && index + 2 < bytes.len() {
            if let (Some(high), Some(low)) =
                (hex_value(bytes[index + 1]), hex_value(bytes[index + 2]))
            {
                result.push(high * 16 + low);
                index += 3;
                continue;
            }
        }
        result.push(bytes[index]);
        index += 1;
    }
    String::from_utf8_lossy(&result).into_owned()
}

fn hex_value(byte: u8) -> Option<u8> {
    match byte {
        b'0'..=b'9' => Some(byte - b'0'),
        b'a'..=b'f' => Some(byte - b'a' + 10),
        b'A'..=b'F' => Some(byte - b'A' + 10),
        _ => None,
    }
}

// ---------------------------------------------------------------------------
// Transport
// ---------------------------------------------------------------------------

struct CredentialHeader {
    name: http::HeaderName,
    value: http::HeaderValue,
    anthropic: bool,
}

impl CredentialHeader {
    fn new(
        provider: ProviderKind,
        credential_kind: CredentialKind,
        credential: &AiSecret,
    ) -> Result<Self, ModelCatalogError> {
        let bytes = credential.as_bytes();
        if bytes.is_empty() || !bytes.iter().all(u8::is_ascii_graphic) {
            return Err(ModelCatalogError::Credential);
        }
        let bearer = !matches!(
            (provider, credential_kind),
            (ProviderKind::Claude, CredentialKind::ApiKey)
        );
        let name = if bearer {
            http::header::AUTHORIZATION
        } else {
            http::HeaderName::from_static("x-api-key")
        };
        let mut value_bytes = Vec::with_capacity(bytes.len() + 7);
        if bearer {
            value_bytes.extend_from_slice(b"Bearer ");
        }
        value_bytes.extend_from_slice(bytes);
        let mut value = http::HeaderValue::from_bytes(&value_bytes)
            .map_err(|_| ModelCatalogError::Credential)?;
        value.set_sensitive(true);
        Ok(Self {
            name,
            value,
            anthropic: provider == ProviderKind::Claude,
        })
    }
}

async fn request_json(
    client: Arc<dyn HttpClient>,
    url: &Url,
    header: &CredentialHeader,
    after_id: Option<&str>,
) -> Result<Vec<u8>, ModelCatalogError> {
    let mut url = url.clone();
    if let Some(after_id) = after_id {
        url.query_pairs_mut().append_pair("after_id", after_id);
    }
    let mut builder = Request::builder()
        .method(Method::GET)
        .uri(url.as_str())
        .header(http::header::ACCEPT, "application/json")
        .header(header.name.clone(), header.value.clone());
    if header.anthropic {
        builder = builder.header("anthropic-version", "2023-06-01");
    }
    let request = builder
        .follow_redirects(RedirectPolicy::NoFollow)
        .timeout(CATALOG_TIMEOUT)
        .body(AsyncBody::default())
        .map_err(|_| ModelCatalogError::InvalidResponse)?;
    let response = client
        .send(request)
        .await
        .map_err(|_| ModelCatalogError::Network)?;
    let status = response.status();
    if !status.is_success() {
        return Err(status_error(&response, status, &url));
    }
    if let Some(length) = response.headers().get(http::header::CONTENT_LENGTH) {
        if let Some(length) = length
            .to_str()
            .ok()
            .and_then(|value| value.parse::<u64>().ok())
        {
            if length > MAX_WIRE_BYTES {
                return Err(ModelCatalogError::InvalidResponse);
            }
        }
    }
    read_bounded(response.into_body()).await
}

fn status_error(
    response: &Response<AsyncBody>,
    status: http::StatusCode,
    requested: &Url,
) -> ModelCatalogError {
    if status.is_redirection() {
        return cross_host_redirect(response, requested)
            .unwrap_or(ModelCatalogError::Http(status.as_u16()));
    }
    match status.as_u16() {
        401 | 403 => ModelCatalogError::Authentication,
        408 | 504 => ModelCatalogError::Timeout,
        429 => ModelCatalogError::RateLimit,
        code => ModelCatalogError::Http(code),
    }
}

fn cross_host_redirect(
    response: &Response<AsyncBody>,
    requested: &Url,
) -> Option<ModelCatalogError> {
    let location = response.headers().get(http::header::LOCATION)?;
    let location = location.to_str().ok()?;
    let target = requested.join(location).ok()?;
    let same_origin = requested.scheme() == target.scheme()
        && requested.host_str() == target.host_str()
        && effective_catalog_port(requested) == effective_catalog_port(&target);
    if same_origin {
        None
    } else {
        Some(ModelCatalogError::InvalidEndpoint)
    }
}

async fn read_bounded(mut body: AsyncBody) -> Result<Vec<u8>, ModelCatalogError> {
    let mut bytes = Vec::new();
    let mut buffer = [0u8; 8192];
    loop {
        let count = body
            .read(&mut buffer)
            .await
            .map_err(|_| ModelCatalogError::Network)?;
        if count == 0 {
            return Ok(bytes);
        }
        if bytes.len().saturating_add(count) > MAX_CATALOG_BYTES {
            return Err(ModelCatalogError::InvalidResponse);
        }
        bytes.extend_from_slice(&buffer[..count]);
    }
}

async fn bounded<T>(
    operation: impl std::future::Future<Output = Result<T, ModelCatalogError>>,
    duration: Duration,
) -> Result<T, ModelCatalogError> {
    use futures::future::{Either, select};
    let timer = async_io::Timer::after(duration);
    futures::pin_mut!(operation);
    futures::pin_mut!(timer);
    match select(operation, timer).await {
        Either::Left((result, _)) => result,
        Either::Right(_) => Err(ModelCatalogError::Timeout),
    }
}

// ---------------------------------------------------------------------------
// Parsing
// ---------------------------------------------------------------------------

fn json_limits() -> aps_json::Limits {
    aps_json::Limits {
        max_bytes: MAX_CATALOG_BYTES as u64,
        max_depth: 8,
        max_nodes: 128 * 1024,
        max_string_bytes: MAX_MODEL_ID_CHARS,
        max_collection_items: MAX_CATALOG_MODELS + 1,
    }
}

fn catalog_payload(body: &[u8]) -> Result<&str, ModelCatalogError> {
    let text = std::str::from_utf8(body).map_err(|_| ModelCatalogError::InvalidResponse)?;
    Ok(text
        .trim_start_matches(|character: char| character.is_whitespace() || character == '\u{feff}'))
}

fn parse_json_object(payload: &str) -> Result<Value, ModelCatalogError> {
    let value = aps_json::parse_slice(payload.as_bytes(), json_limits())
        .map_err(|_| ModelCatalogError::InvalidResponse)?;
    if value.as_object().is_none() {
        return Err(ModelCatalogError::InvalidResponse);
    }
    Ok(value)
}

fn model_id(entry: &Value) -> Result<String, ModelCatalogError> {
    let id = entry
        .get("id")
        .and_then(Value::as_str)
        .ok_or(ModelCatalogError::InvalidResponse)?;
    if id.trim().is_empty() || id.len() > MAX_MODEL_ID_CHARS || id.chars().any(char::is_control) {
        return Err(ModelCatalogError::InvalidResponse);
    }
    Ok(id.to_owned())
}

fn parse_openai_models(body: &[u8]) -> Result<Vec<String>, ModelCatalogError> {
    let payload = catalog_payload(body)?;
    if is_html_document(payload) {
        return Err(ModelCatalogError::HtmlResponse);
    }
    let root = parse_json_object(payload)?;
    let data = root
        .get("data")
        .and_then(Value::as_array)
        .ok_or(ModelCatalogError::InvalidResponse)?;
    if data.len() > MAX_CATALOG_MODELS {
        return Err(ModelCatalogError::InvalidResponse);
    }
    let mut identities = BTreeSet::new();
    let mut models = Vec::new();
    for entry in data {
        let id = model_id(entry)?;
        if !identities.insert(id.clone()) {
            return Err(ModelCatalogError::InvalidResponse);
        }
        if is_likely_responses_text_model(&id) {
            models.push(id);
        }
    }
    models.sort();
    Ok(models)
}

fn is_html_document(payload: &str) -> bool {
    let lower: Vec<u8> = payload
        .bytes()
        .map(|byte| byte.to_ascii_lowercase())
        .collect();
    if lower.starts_with(b"<!doctype") {
        let mut index = b"<!doctype".len();
        let start = index;
        while index < lower.len() && lower[index].is_ascii_whitespace() {
            index += 1;
        }
        if index == start {
            return false;
        }
        let after = &lower[index..];
        return after.starts_with(b"html")
            && after
                .get(4)
                .is_some_and(|byte| byte.is_ascii_whitespace() || *byte == b'>');
    }
    if lower.starts_with(b"<html") {
        return lower
            .get(5)
            .is_some_and(|byte| byte.is_ascii_whitespace() || *byte == b'>');
    }
    false
}

fn is_likely_responses_text_model(model_id: &str) -> bool {
    // Inspect the model family without dropping the gateway namespace from the
    // returned ID.
    let last_segment = model_id.rsplit('/').next().unwrap_or(model_id);
    let normalized = last_segment
        .strip_prefix("ft:")
        .unwrap_or(last_segment)
        .to_ascii_lowercase();
    let text_family = normalized.starts_with("gpt-")
        || normalized.strip_prefix('o').is_some_and(|rest| {
            let mut characters = rest.chars();
            characters
                .next()
                .is_some_and(|character| character.is_ascii_digit())
                && characters.next().is_none_or(|character| character == '-')
        });
    // /v1/models has no capability field; exclude known incompatible families.
    let incompatible = [
        "audio",
        "image",
        "realtime",
        "transcribe",
        "tts",
        "search",
        "embedding",
        "moderation",
        "chatgpt",
        "deep-research",
    ]
    .iter()
    .any(|needle| normalized.contains(needle));
    text_family && !incompatible
}

struct ClaudePage {
    models: Vec<String>,
    has_more: bool,
    last_id: Option<String>,
}

fn parse_claude_page(body: &[u8]) -> Result<ClaudePage, ModelCatalogError> {
    let payload = catalog_payload(body)?;
    if payload.starts_with('<') {
        return Err(ModelCatalogError::HtmlResponse);
    }
    let root = parse_json_object(payload)?;
    let data = root
        .get("data")
        .and_then(Value::as_array)
        .ok_or(ModelCatalogError::InvalidResponse)?;
    let mut models = Vec::with_capacity(data.len());
    for entry in data {
        models.push(model_id(entry)?);
    }
    let has_more = match root.get("has_more") {
        None => false,
        Some(Value::Bool(value)) => *value,
        Some(_) => return Err(ModelCatalogError::InvalidResponse),
    };
    let last_id = match root.get("last_id") {
        None => None,
        Some(Value::String(value)) => {
            if value.trim().is_empty()
                || value.len() > MAX_MODEL_ID_CHARS
                || value.chars().any(char::is_control)
            {
                return Err(ModelCatalogError::InvalidResponse);
            }
            Some(value.clone())
        }
        Some(_) => return Err(ModelCatalogError::InvalidResponse),
    };
    Ok(ClaudePage {
        models,
        has_more,
        last_id,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use futures::executor::block_on;
    use futures::future::BoxFuture;
    use std::collections::VecDeque;
    use std::sync::Mutex;

    struct RecordedRequest {
        uri: String,
        method: http::Method,
        headers: http::HeaderMap,
        no_follow: bool,
    }

    struct FakeClient {
        responses: Mutex<VecDeque<Response<AsyncBody>>>,
        requests: Mutex<Vec<RecordedRequest>>,
    }

    impl FakeClient {
        fn new(responses: Vec<Response<AsyncBody>>) -> Arc<Self> {
            Arc::new(Self {
                responses: Mutex::new(responses.into()),
                requests: Mutex::new(Vec::new()),
            })
        }
    }

    impl HttpClient for FakeClient {
        fn user_agent(&self) -> Option<&http::HeaderValue> {
            None
        }
        fn proxy(&self) -> Option<&Url> {
            None
        }
        fn send(
            &self,
            request: Request<AsyncBody>,
        ) -> BoxFuture<'static, http_client::Result<Response<AsyncBody>>> {
            let recorded = RecordedRequest {
                uri: request.uri().to_string(),
                method: request.method().clone(),
                headers: request.headers().clone(),
                no_follow: request.extensions().get::<RedirectPolicy>()
                    == Some(&RedirectPolicy::NoFollow),
            };
            self.requests.lock().unwrap().push(recorded);
            let response = self
                .responses
                .lock()
                .unwrap()
                .pop_front()
                .expect("fake response");
            Box::pin(async move { Ok(response) })
        }
    }

    fn json_response(status: u16, body: &str) -> Response<AsyncBody> {
        Response::builder()
            .status(status)
            .header(http::header::CONTENT_TYPE, "application/json")
            .body(AsyncBody::from(body.to_owned()))
            .unwrap()
    }

    fn redirect_response(location: &str) -> Response<AsyncBody> {
        Response::builder()
            .status(302)
            .header(http::header::LOCATION, location)
            .body(AsyncBody::from(String::new()))
            .unwrap()
    }

    fn client_for(bodies: Vec<(u16, &str)>) -> Arc<FakeClient> {
        FakeClient::new(
            bodies
                .into_iter()
                .map(|(status, body)| json_response(status, body))
                .collect(),
        )
    }

    fn secret() -> AiSecret {
        AiSecret::new("sk-test-1234567890").unwrap()
    }

    #[test]
    fn openai_models_url_matches_compose_shapes() {
        for (endpoint, expected) in [
            (
                "https://api.openai.com/v1/responses",
                "https://api.openai.com/v1/models",
            ),
            (
                "https://api.openai.com/v1",
                "https://api.openai.com/v1/models",
            ),
            (
                "https://api.openai.com/v1/models",
                "https://api.openai.com/v1/models",
            ),
            (
                "https://gateway.example/proxy/v1",
                "https://gateway.example/proxy/v1/models",
            ),
            (
                "https://gateway.example/proxy/v1/",
                "https://gateway.example/proxy/v1/models",
            ),
            (
                "https://api.openai.com/responses",
                "https://api.openai.com/models",
            ),
        ] {
            assert_eq!(
                openai_models_url(endpoint, None).unwrap().as_str(),
                expected
            );
        }
        assert_eq!(
            openai_models_url(
                "https://api.openai.com/v1/responses",
                Some("https://api.openai.com/custom/models")
            )
            .unwrap()
            .as_str(),
            "https://api.openai.com/custom/models"
        );
        assert_eq!(
            openai_models_url(
                "https://api.openai.com/v1/responses",
                Some("https://other.example/v1/models")
            )
            .err()
            .unwrap(),
            ModelCatalogError::InvalidEndpoint
        );
        assert_eq!(
            openai_models_url(
                "https://api.openai.com/v1/responses",
                Some("https://api.openai.com:8443/v1/models")
            )
            .err()
            .unwrap(),
            ModelCatalogError::InvalidEndpoint
        );
        for endpoint in [
            "http://example.com/v1",
            "https://user:secret@example.com/v1",
            "https://example.com/v1?key=1",
            "https://example.com/v1#fragment",
        ] {
            assert_eq!(
                openai_models_url(endpoint, None).err().unwrap(),
                ModelCatalogError::InvalidEndpoint
            );
        }
    }

    #[test]
    fn claude_models_url_matches_compose_shapes() {
        for (endpoint, expected) in [
            (
                "https://api.anthropic.com",
                "https://api.anthropic.com/v1/models",
            ),
            (
                "https://api.anthropic.com/",
                "https://api.anthropic.com/v1/models",
            ),
            (
                "https://api.anthropic.com/v1",
                "https://api.anthropic.com/v1/models",
            ),
            (
                "https://api.anthropic.com/v1/messages",
                "https://api.anthropic.com/v1/models",
            ),
            (
                "https://api.anthropic.com/v1/models",
                "https://api.anthropic.com/v1/models",
            ),
            (
                "https://gateway.example/anthropic",
                "https://gateway.example/anthropic/v1/models",
            ),
        ] {
            assert_eq!(claude_models_url(endpoint).unwrap().as_str(), expected);
        }
    }

    #[test]
    fn openai_discovery_filters_and_sorts_model_families() {
        let body = r#"{"data":[{"id":"gpt-5"},{"id":"o3"},{"id":"gpt-image-1"},{"id":"text-embedding-3-large"},{"id":"ft:gpt-4o:acme"},{"id":"openai/gpt-4.1"},{"id":"o1-mini"},{"id":"gpt-4o-audio-preview"}]}"#;
        let client = client_for(vec![(200, body)]);
        let models = block_on(discover(
            client.clone(),
            ProviderKind::Codex,
            "https://api.openai.com/v1/responses",
            None,
            CredentialKind::ApiKey,
            &secret(),
        ))
        .unwrap();
        assert_eq!(
            models,
            vec!["ft:gpt-4o:acme", "gpt-5", "o1-mini", "o3", "openai/gpt-4.1"]
        );
        let requests = client.requests.lock().unwrap();
        assert_eq!(requests.len(), 1);
        assert_eq!(requests[0].method, http::Method::GET);
        assert_eq!(requests[0].uri, "https://api.openai.com/v1/models");
        assert!(requests[0].no_follow);
        let authorization = requests[0]
            .headers
            .get(http::header::AUTHORIZATION)
            .unwrap();
        assert_eq!(authorization.to_str().unwrap(), "Bearer sk-test-1234567890");
        assert!(authorization.is_sensitive());
        assert_eq!(
            requests[0]
                .headers
                .get(http::header::ACCEPT)
                .unwrap()
                .to_str()
                .unwrap(),
            "application/json"
        );
    }

    #[test]
    fn openai_root_responses_gateway_falls_back_from_html_models_page() {
        let client = client_for(vec![
            (200, "<!doctype html><html><body>marketing</body></html>"),
            (200, r#"{"data":[{"id":"gpt-5"}]}"#),
        ]);
        let models = block_on(discover(
            client.clone(),
            ProviderKind::Custom,
            "https://api.openai.com/responses",
            None,
            CredentialKind::ApiKey,
            &secret(),
        ))
        .unwrap();
        assert_eq!(models, vec!["gpt-5"]);
        let requests = client.requests.lock().unwrap();
        assert_eq!(requests.len(), 2);
        assert_eq!(requests[0].uri, "https://api.openai.com/models");
        assert_eq!(requests[1].uri, "https://api.openai.com/v1/models");
    }

    #[test]
    fn openai_html_without_fallback_reports_html_response() {
        let client = client_for(vec![(200, "<html><body>not the api</body></html>")]);
        let error = block_on(discover(
            client.clone(),
            ProviderKind::Custom,
            "https://api.openai.com/v1/responses",
            None,
            CredentialKind::ApiKey,
            &secret(),
        ))
        .err()
        .unwrap();
        assert_eq!(error, ModelCatalogError::HtmlResponse);
        assert_eq!(error.to_string(), "E_AI_MODEL_CATALOG_HTML_RESPONSE");
        assert_eq!(client.requests.lock().unwrap().len(), 1);
    }

    #[test]
    fn explicit_models_endpoint_is_used_after_validation() {
        let client = client_for(vec![(200, r#"{"data":[{"id":"gpt-5"}]}"#)]);
        let models = block_on(discover(
            client.clone(),
            ProviderKind::Custom,
            "https://api.openai.com/v1/responses",
            Some("https://api.openai.com/custom/models"),
            CredentialKind::ApiKey,
            &secret(),
        ))
        .unwrap();
        assert_eq!(models, vec!["gpt-5"]);
        assert_eq!(
            client.requests.lock().unwrap()[0].uri,
            "https://api.openai.com/custom/models"
        );

        let client = client_for(Vec::new());
        let error = block_on(discover(
            client.clone(),
            ProviderKind::Custom,
            "https://api.openai.com/v1/responses",
            Some("https://evil.example/models"),
            CredentialKind::ApiKey,
            &secret(),
        ))
        .err()
        .unwrap();
        assert_eq!(error, ModelCatalogError::InvalidEndpoint);
        assert!(client.requests.lock().unwrap().is_empty());
    }

    #[test]
    fn http_statuses_map_to_stable_errors() {
        for (status, expected) in [
            (401, ModelCatalogError::Authentication),
            (403, ModelCatalogError::Authentication),
            (408, ModelCatalogError::Timeout),
            (429, ModelCatalogError::RateLimit),
            (504, ModelCatalogError::Timeout),
            (503, ModelCatalogError::Http(503)),
        ] {
            let client = client_for(vec![(status, "{}")]);
            let error = block_on(discover(
                client,
                ProviderKind::Custom,
                "https://api.openai.com/v1/responses",
                None,
                CredentialKind::ApiKey,
                &secret(),
            ))
            .err()
            .unwrap();
            assert_eq!(error, expected);
        }
        assert_eq!(ModelCatalogError::Http(503).to_string(), "E_AI_HTTP_STATUS");
    }

    #[test]
    fn redirects_are_rejected_without_following() {
        for (location, expected) in [
            (
                "https://evil.example/v1/models",
                ModelCatalogError::InvalidEndpoint,
            ),
            ("/v1/models", ModelCatalogError::Http(302)),
        ] {
            let client = FakeClient::new(vec![redirect_response(location)]);
            let error = block_on(discover(
                client,
                ProviderKind::Custom,
                "https://api.openai.com/v1/responses",
                None,
                CredentialKind::ApiKey,
                &secret(),
            ))
            .err()
            .unwrap();
            assert_eq!(error, expected);
        }
    }

    #[test]
    fn invalid_bodies_are_rejected() {
        for body in [
            r#"{"data":["#,
            r#"{"data":[{"id":5}]}"#,
            r#"{"data":[{"id":"gpt-5"},{"id":"gpt-5"}]}"#,
            "[]",
            r#"{"models":[]}"#,
        ] {
            let client = client_for(vec![(200, body)]);
            assert_eq!(
                block_on(discover(
                    client,
                    ProviderKind::Custom,
                    "https://api.openai.com/v1/responses",
                    None,
                    CredentialKind::ApiKey,
                    &secret(),
                ))
                .err()
                .unwrap(),
                ModelCatalogError::InvalidResponse
            );
        }
    }

    #[test]
    fn claude_pagination_uses_api_key_and_cursor() {
        let client = client_for(vec![
            (
                200,
                r#"{"data":[{"id":"claude-b"}],"has_more":true,"last_id":"cursor-1"}"#,
            ),
            (200, r#"{"data":[{"id":"claude-a"}]}"#),
        ]);
        let models = block_on(discover(
            client.clone(),
            ProviderKind::Claude,
            "https://api.anthropic.com",
            None,
            CredentialKind::ApiKey,
            &secret(),
        ))
        .unwrap();
        assert_eq!(models, vec!["claude-a", "claude-b"]);
        let requests = client.requests.lock().unwrap();
        assert_eq!(requests.len(), 2);
        assert_eq!(requests[0].uri, "https://api.anthropic.com/v1/models");
        assert!(
            requests[1]
                .uri
                .starts_with("https://api.anthropic.com/v1/models?after_id=")
        );
        assert_eq!(
            requests[0]
                .headers
                .get("anthropic-version")
                .unwrap()
                .to_str()
                .unwrap(),
            "2023-06-01"
        );
        assert_eq!(
            requests[0]
                .headers
                .get("x-api-key")
                .unwrap()
                .to_str()
                .unwrap(),
            "sk-test-1234567890"
        );
        assert!(
            requests[0]
                .headers
                .get(http::header::AUTHORIZATION)
                .is_none()
        );
    }

    #[test]
    fn claude_bearer_token_uses_authorization_header() {
        let client = client_for(vec![(200, r#"{"data":[{"id":"claude-sonnet-4"}]}"#)]);
        block_on(discover(
            client.clone(),
            ProviderKind::Claude,
            "https://api.anthropic.com",
            None,
            CredentialKind::BearerToken,
            &secret(),
        ))
        .unwrap();
        let requests = client.requests.lock().unwrap();
        assert!(requests[0].headers.get("x-api-key").is_none());
        let authorization = requests[0]
            .headers
            .get(http::header::AUTHORIZATION)
            .unwrap();
        assert_eq!(authorization.to_str().unwrap(), "Bearer sk-test-1234567890");
        assert!(authorization.is_sensitive());
    }

    #[test]
    fn claude_repeated_cursor_is_rejected() {
        let client = client_for(vec![
            (
                200,
                r#"{"data":[{"id":"claude-a"}],"has_more":true,"last_id":"same"}"#,
            ),
            (
                200,
                r#"{"data":[{"id":"claude-b"}],"has_more":true,"last_id":"same"}"#,
            ),
        ]);
        assert_eq!(
            block_on(discover(
                client,
                ProviderKind::Claude,
                "https://api.anthropic.com",
                None,
                CredentialKind::ApiKey,
                &secret(),
            ))
            .err()
            .unwrap(),
            ModelCatalogError::InvalidResponse
        );
    }

    #[test]
    fn claude_pagination_bounds_models_and_pages() {
        let mut pages = Vec::new();
        for index in 0..(MAX_CLAUDE_PAGES + 1) {
            pages.push((
                200,
                format!(
                    r#"{{"data":[{{"id":"claude-{index}"}}],"has_more":true,"last_id":"cursor-{index}"}}"#
                ),
            ));
        }
        let client = FakeClient::new(
            pages
                .into_iter()
                .map(|(status, body)| json_response(status, &body))
                .collect(),
        );
        assert_eq!(
            block_on(discover(
                client,
                ProviderKind::Claude,
                "https://api.anthropic.com",
                None,
                CredentialKind::ApiKey,
                &secret(),
            ))
            .err()
            .unwrap(),
            ModelCatalogError::InvalidResponse
        );
    }

    #[test]
    fn non_ascii_credential_is_rejected_without_a_request() {
        let client = client_for(Vec::new());
        let credential = AiSecret::new("sk-test-\u{00fc}12345678").unwrap();
        assert_eq!(
            block_on(discover(
                client.clone(),
                ProviderKind::Custom,
                "https://api.openai.com/v1/responses",
                None,
                CredentialKind::ApiKey,
                &credential,
            ))
            .err()
            .unwrap(),
            ModelCatalogError::Credential
        );
        assert!(client.requests.lock().unwrap().is_empty());
    }
}

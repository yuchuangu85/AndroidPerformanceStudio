//! Deferred test sources: do not run until the full rewrite implementation is ready.
use aps_ai::*;
use aps_contracts::SourceDigest;
use aps_json::Value;
use futures::{executor::block_on, future::BoxFuture};
use http_client::{AsyncBody, HttpClient, Request, Response, Url, http};
use std::sync::{
    Arc, Mutex,
    atomic::{AtomicUsize, Ordering},
};
use std::time::Duration;

fn object<const N: usize>(pairs: [(&str, Value); N]) -> Value {
    Value::Object(
        pairs
            .into_iter()
            .map(|(key, value)| (key.into(), value))
            .collect(),
    )
}
fn provider() -> ProviderConfig {
    ProviderConfig::new(
        "test",
        "http://127.0.0.1:12345/v1",
        "test-model",
        CredentialReference::new("keychain:test").unwrap(),
    )
    .unwrap()
}
fn request() -> AnalysisRequest {
    AnalysisRequest {
        session_id: "session-1".into(),
        run_id: "run-1".into(),
        generation: 1,
        profiler: "cpu".into(),
        scope: "Selected bounded summary".into(),
        evidence: vec![Evidence {
            id: "ev-1".into(),
            kind: "cpu-summary".into(),
            summary: "One observed sample".into(),
            payload: object([("samples", 1.into())]),
        }],
        candidates: Vec::new(),
        user_question: None,
    }
}
fn prepared(stream: bool) -> PreparedRun {
    PreparedRun::new(request(), provider(), stream).unwrap()
}
fn authorize(stream: bool) -> (RunCoordinator, AuthorizedRun) {
    let mut coordinator = RunCoordinator::new();
    coordinator.activate("session-1", 1).unwrap();
    let prepared = prepared(stream);
    let approval = coordinator
        .approve_explicit(
            &prepared,
            ApprovalScope::network_only(),
            Duration::from_secs(120),
        )
        .unwrap();
    let run = coordinator.authorize(prepared, approval).unwrap();
    (coordinator, run)
}
fn answer() -> &'static str {
    r#"{"summary":"Observed one sample","findings":[{"id":"finding-1","severity":"INFO","title":"Limited evidence","explanation":"One sample is insufficient","recommendation":"Collect more evidence","analysisConfidence":0.5,"performanceEvidenceIds":["ev-1"],"sourceCandidateIds":[]}]}"#
}
fn completion(content: &str) -> Vec<u8> {
    let value = object([
        ("object", "chat.completion".into()),
        ("model", "effective-model".into()),
        (
            "choices",
            Value::Array(vec![object([
                ("index", 0.into()),
                ("finish_reason", "stop".into()),
                (
                    "message",
                    object([("role", "assistant".into()), ("content", content.into())]),
                ),
            ])]),
        ),
    ]);
    aps_json::to_vec(&value, aps_json::Limits::default()).unwrap()
}
fn chunk(content: Option<&str>, finish: Option<&str>) -> String {
    let delta = match content {
        Some(content) => object([("content", content.into())]),
        None => object([]),
    };
    let value = object([
        ("id", "completion-1".into()),
        ("model", "effective-model".into()),
        ("object", "chat.completion.chunk".into()),
        (
            "choices",
            Value::Array(vec![object([
                ("index", 0.into()),
                ("delta", delta),
                (
                    "finish_reason",
                    finish.map_or(Value::Null, |value| value.into()),
                ),
            ])]),
        ),
    ]);
    format!(
        "data: {}\r\n\r\n",
        String::from_utf8(aps_json::to_vec(&value, aps_json::Limits::default()).unwrap()).unwrap()
    )
}

#[test]
fn provider_endpoint_policy_and_catalog_are_metadata_only() {
    let reference = CredentialReference::new("keychain:test").unwrap();
    for rejected in [
        "http://example.com/v1",
        "file:///tmp/key",
        "https://user:secret@example.com/v1",
        "https://example.com/v1?key=secret",
        "https://example.com/v1#fragment",
    ] {
        assert!(ProviderConfig::new("test", rejected, "model", reference.clone()).is_err());
    }
    assert_eq!(
        provider().endpoint(),
        "http://127.0.0.1:12345/v1/chat/completions"
    );
    let models = ProviderCatalog::parse_models(br#"{"data":[{"id":"provider/model"}]}"#).unwrap();
    assert!(!models[0].supports_streaming && !models[0].supports_json_schema);
    assert!(
        ProviderCatalog::parse_models(br#"{"data":[{"id":"duplicate"},{"id":"duplicate"}]}"#)
            .is_err()
    );
    assert!(ProviderCatalog::parse_models(br#"{"data":[],"data":[]}"#).is_err());
}

#[test]
fn prepared_context_rejects_duplicate_ids_and_nonrelative_sources() {
    let mut duplicate = request();
    duplicate.evidence.push(Evidence {
        id: "ev-1".into(),
        kind: "cpu".into(),
        summary: "duplicate".into(),
        payload: Value::Null,
    });
    assert!(PreparedRun::new(duplicate, provider(), false).is_err());
    for path in ["../secret", "/absolute", "C:\\secret", "relative/../secret"] {
        let mut value = request();
        value.candidates.push(SourceCandidate {
            id: "candidate-1".into(),
            workspace_id: "workspace-1".into(),
            snapshot_id: "snapshot-1".into(),
            content_digest: SourceDigest::from_bytes([1; 32]),
            relative_path: path.into(),
            start_line: Some(1),
            end_line: Some(2),
            snippet: Some("source".into()),
        });
        assert!(PreparedRun::new(value, provider(), false).is_err());
    }
}

#[test]
fn nested_payload_uses_final_request_budget_not_small_string_budget() {
    let mut value = request();
    value.evidence = (0..8)
        .map(|index| Evidence {
            id: format!("evidence-{index}"),
            kind: "bounded".into(),
            summary: "bounded".into(),
            payload: object([("text", "x".repeat(12_000).into())]),
        })
        .collect();
    assert!(PreparedRun::new(value, provider(), false).is_ok());
}

#[test]
fn network_and_exact_source_scope_require_one_use_explicit_approval() {
    let mut value = request();
    value.candidates.push(SourceCandidate {
        id: "candidate-1".into(),
        workspace_id: "workspace-1".into(),
        snapshot_id: "snapshot-1".into(),
        content_digest: SourceDigest::from_bytes([1; 32]),
        relative_path: "src/Main.kt".into(),
        start_line: Some(1),
        end_line: Some(2),
        snippet: None,
    });
    let prepared = PreparedRun::new(value, provider(), false).unwrap();
    let mut coordinator = RunCoordinator::new();
    coordinator.activate("session-1", 1).unwrap();
    assert!(matches!(
        coordinator.approve_explicit(
            &prepared,
            ApprovalScope::network_only(),
            Duration::from_secs(1)
        ),
        Err(AiError::ApprovalRequired)
    ));
    let approval = coordinator
        .approve_explicit(
            &prepared,
            ApprovalScope::with_sources(["candidate-1".into()]),
            Duration::from_secs(1),
        )
        .unwrap();
    assert!(coordinator.authorize(prepared, approval).is_ok());
}

#[test]
fn approval_tokens_are_bound_to_their_issuer_not_only_sequence_and_hash() {
    let prepared_a = prepared(false);
    let prepared_b = prepared(false);
    let mut a = RunCoordinator::new();
    let mut b = RunCoordinator::new();
    a.activate("session-1", 1).unwrap();
    b.activate("session-1", 1).unwrap();
    let token_a = a
        .approve_explicit(
            &prepared_a,
            ApprovalScope::network_only(),
            Duration::from_secs(60),
        )
        .unwrap();
    let token_b = b
        .approve_explicit(
            &prepared_b,
            ApprovalScope::network_only(),
            Duration::from_secs(60),
        )
        .unwrap();
    assert!(matches!(
        b.authorize(prepared_a, token_a),
        Err(AiError::ApprovalRequired)
    ));
    assert!(b.authorize(prepared_b, token_b).is_ok());
}

#[test]
fn approval_cannot_be_reused_for_changed_input_or_old_generation() {
    let mut coordinator = RunCoordinator::new();
    coordinator.activate("session-1", 1).unwrap();
    let frozen = prepared(false);
    let approval = coordinator
        .approve_explicit(
            &frozen,
            ApprovalScope::network_only(),
            Duration::from_secs(60),
        )
        .unwrap();
    let mut changed = request();
    changed.user_question = Some("Different input".into());
    assert!(matches!(
        coordinator.authorize(
            PreparedRun::new(changed, provider(), false).unwrap(),
            approval
        ),
        Err(AiError::ApprovalRequired)
    ));
    assert!(coordinator.activate("session-1", 1).is_err());
    coordinator.activate("session-1", 2).unwrap();
    assert!(matches!(
        coordinator.approve_explicit(
            &frozen,
            ApprovalScope::network_only(),
            Duration::from_secs(60)
        ),
        Err(AiError::StaleRun)
    ));
}

#[test]
fn sse_arbitrary_utf8_crlf_chunks_require_stop_and_done() {
    let wire = format!(
        "\u{feff}: heartbeat\r\n\r\n{}{}data: [DONE]\r\n\r\n",
        chunk(Some("中文"), None),
        chunk(None, Some("stop"))
    );
    let mut decoder = SseDecoder::new(StreamLimits::default()).unwrap();
    let mut text = String::new();
    let mut finished = 0;
    for byte in wire.as_bytes() {
        for event in decoder.push(&[*byte]).unwrap() {
            match event {
                StreamEvent::TextDelta(delta) => text.push_str(&delta),
                StreamEvent::Finished => finished += 1,
            }
        }
    }
    assert_eq!(text, "中文");
    assert_eq!(finished, 1);
    assert_eq!(decoder.effective_model(), Some("effective-model"));
    decoder.finish().unwrap();
}

#[test]
fn sse_never_succeeds_on_truncated_or_malformed_streams() {
    for wire in [
        chunk(Some("partial"), None),
        format!("{}data: [DONE]\n\n", chunk(Some("partial"), None)),
        "data: [DONE]\n\n".into(),
        "data: {\"choices\":[]}\n\n".into(),
    ] {
        let mut decoder = SseDecoder::new(StreamLimits::default()).unwrap();
        let pushed = decoder.push(wire.as_bytes());
        assert!(pushed.is_err() || decoder.finish().is_err());
    }
    let mut decoder = SseDecoder::new(StreamLimits::default()).unwrap();
    assert!(matches!(
        decoder.push(chunk(None, Some("length")).as_bytes()),
        Err(AiError::TruncatedResponse)
    ));
}

#[test]
fn sse_enforces_resource_and_identity_bounds() {
    let mut decoder = SseDecoder::new(StreamLimits {
        max_content_bytes: 2,
        ..Default::default()
    })
    .unwrap();
    assert!(matches!(
        decoder.push(chunk(Some("中文"), None).as_bytes()),
        Err(AiError::ResponseLimit)
    ));
    let mut decoder = SseDecoder::new(StreamLimits::default()).unwrap();
    decoder.push(chunk(Some("a"), None).as_bytes()).unwrap();
    assert!(
        decoder
            .push(
                chunk(Some("b"), None)
                    .replace("completion-1", "completion-2")
                    .as_bytes()
            )
            .is_err()
    );
    let mut decoder = SseDecoder::new(StreamLimits::default()).unwrap();
    let wire = format!(
        "{}{}data: [DONE]\n\ndata: {{}}\n\n",
        chunk(Some("x"), None),
        chunk(None, Some("stop"))
    );
    assert!(decoder.push(wire.as_bytes()).is_err());
}

struct TestResolver {
    calls: AtomicUsize,
}
impl CredentialResolver for TestResolver {
    fn resolve<'a>(
        &'a self,
        _: &'a CredentialReference,
    ) -> BoxFuture<'a, Result<SecretCredential, AiError>> {
        self.calls.fetch_add(1, Ordering::Relaxed);
        Box::pin(async { SecretCredential::new(b"test-secret-not-real".to_vec()) })
    }
}
struct TestClient {
    response: Mutex<Option<Response<AsyncBody>>>,
    calls: AtomicUsize,
}
impl HttpClient for TestClient {
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
        self.calls.fetch_add(1, Ordering::Relaxed);
        assert_eq!(request.method(), http::Method::POST);
        assert_eq!(
            request.extensions().get::<http_client::RedirectPolicy>(),
            Some(&http_client::RedirectPolicy::NoFollow)
        );
        assert!(request.headers()[http::header::AUTHORIZATION].is_sensitive());
        let response = self.response.lock().unwrap().take().unwrap();
        Box::pin(async move { Ok(response) })
    }
}
fn mock_gateway(
    body: Vec<u8>,
    media: &str,
    status: u16,
) -> (OpenAiGateway, Arc<TestResolver>, Arc<TestClient>) {
    let resolver = Arc::new(TestResolver {
        calls: AtomicUsize::new(0),
    });
    let client = Arc::new(TestClient {
        response: Mutex::new(Some(
            Response::builder()
                .status(status)
                .header(http::header::CONTENT_TYPE, media)
                .body(AsyncBody::from(body))
                .unwrap(),
        )),
        calls: AtomicUsize::new(0),
    });
    (
        OpenAiGateway::new(client.clone(), resolver.clone()),
        resolver,
        client,
    )
}

#[test]
fn real_transport_path_uses_injected_fake_only_and_validates_result() {
    let (coordinator, run) = authorize(false);
    let (gateway, resolver, client) =
        mock_gateway(completion(answer()), "application/json; charset=utf-8", 200);
    let result = block_on(gateway.execute(run)).unwrap();
    assert_eq!(result.model, "effective-model");
    assert_eq!(result.findings.len(), 1);
    coordinator.accept_result(&result).unwrap();
    assert_eq!(resolver.calls.load(Ordering::Relaxed), 1);
    assert_eq!(client.calls.load(Ordering::Relaxed), 1);
}

#[test]
fn gateway_rejects_unknown_refs_extra_navigable_fields_and_credential_echo() {
    for content in [
        answer().replace("ev-1", "invented"),
        answer().replace(
            "\"id\":\"finding-1\"",
            "\"id\":\"finding-1\",\"path\":\"invented.kt\"",
        ),
        answer().replace("Observed one sample", "test-secret-not-real"),
        answer().replace("Observed one sample", "test-secret-not-rea\\u006c"),
    ] {
        let (_coordinator, run) = authorize(false);
        let (gateway, _, _) = mock_gateway(completion(&content), "application/json", 200);
        assert!(block_on(gateway.execute(run)).is_err());
    }
}

#[test]
fn gateway_stream_and_nonstream_share_final_validation_and_cancel_before_secret_lookup() {
    let (mut coordinator, run) = authorize(true);
    let wire = format!(
        "{}{}data: [DONE]\n\n",
        chunk(Some(answer()), None),
        chunk(None, Some("stop"))
    );
    let (gateway, _, _) = mock_gateway(wire.into_bytes(), "text/event-stream", 200);
    let result = block_on(gateway.execute(run)).unwrap();
    coordinator.activate("session-1", 2).unwrap();
    assert!(matches!(
        coordinator.accept_result(&result),
        Err(AiError::StaleRun)
    ));
    let (mut coordinator, run) = authorize(false);
    coordinator.cancel();
    let (gateway, resolver, client) = mock_gateway(completion(answer()), "application/json", 200);
    assert!(matches!(
        block_on(gateway.execute(run)),
        Err(AiError::Cancelled)
    ));
    assert_eq!(resolver.calls.load(Ordering::Relaxed), 0);
    assert_eq!(client.calls.load(Ordering::Relaxed), 0);
}

#[test]
fn gateway_does_not_follow_redirects_or_expose_http_error_body() {
    let (_coordinator, run) = authorize(false);
    let (gateway, _, _) = mock_gateway(
        b"sensitive provider diagnostics".to_vec(),
        "text/plain",
        302,
    );
    assert!(matches!(
        block_on(gateway.execute(run)),
        Err(AiError::HttpStatus(302))
    ));
    assert_eq!(AiError::HttpStatus(302).to_string(), "E_AI_HTTP_STATUS");
}

#[test]
fn audit_round_trip_never_persists_evidence_source_question_or_answer() {
    let scratch =
        aps_platform::TempWorkspace::create(&std::env::temp_dir().canonicalize().unwrap()).unwrap();
    let path = scratch.path().join("aps-ai-audit-v1.json");
    let (_coordinator, run) = authorize(false);
    let mut store = SessionStore::open(&path).unwrap();
    store.begin(&run).unwrap();
    assert_eq!(store.records()[0].state(), AuditState::Running);
    let (gateway, _, _) = mock_gateway(completion(answer()), "application/json", 200);
    let result = block_on(gateway.execute(run)).unwrap();
    store.complete(&result).unwrap();
    let mut reopened = SessionStore::open(&path).unwrap();
    assert_eq!(reopened.records()[0].state(), AuditState::Completed);
    assert_eq!(reopened.records()[0].finding_count(), 1);
    let saved = std::fs::read_to_string(&path).unwrap();
    for forbidden in [
        "One observed sample",
        "Observed one sample",
        "127.0.0.1",
        "test-secret",
        "keychain:",
        "performanceEvidence",
    ] {
        assert!(!saved.contains(forbidden));
    }
    reopened.delete_session_metadata("session-1").unwrap();
    assert!(reopened.records().is_empty());
    assert!(SessionStore::open(scratch.path().join("legacy-settings.json")).is_err());
}

#[test]
fn exact_review_body_matches_the_transport_frozen_json() {
    let prepared = prepared(false);
    let body = aps_json::parse_slice(
        prepared.review_body().as_bytes(),
        aps_json::Limits {
            max_bytes: 1024 * 1024,
            max_string_bytes: 1024 * 1024,
            ..aps_json::Limits::default()
        },
    )
    .unwrap();
    assert_eq!(
        body.get("model").and_then(Value::as_str),
        Some("test-model")
    );
    assert_eq!(body.get("stream").and_then(Value::as_bool), Some(false));
    let messages = body.get("messages").and_then(Value::as_array).unwrap();
    let context = messages[1].get("content").and_then(Value::as_str).unwrap();
    assert!(context.contains("One observed sample"));
    assert!(!prepared.review_body().contains("Authorization"));
    assert!(!prepared.review_body().contains("test-secret"));
}

#[test]
fn memory_credential_is_bound_to_one_reference_and_consumed_only_once() {
    let reference = CredentialReference::new("memory:explicit").unwrap();
    let resolver = OneShotCredential::new(
        reference.clone(),
        SecretCredential::new(b"test-secret".to_vec()).unwrap(),
    );
    let wrong = CredentialReference::new("memory:other").unwrap();
    assert!(matches!(
        block_on(resolver.resolve(&wrong)),
        Err(AiError::CredentialUnavailable)
    ));
    assert!(block_on(resolver.resolve(&reference)).is_ok());
    assert!(matches!(
        block_on(resolver.resolve(&reference)),
        Err(AiError::CredentialUnavailable)
    ));
}

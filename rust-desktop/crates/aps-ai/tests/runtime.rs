//! Runtime freeze/approval and cancellation tests. Provider operations use only
//! the injected, controllable in-process fake client below.

use aps_ai::platform::{AiSecretStore, CredentialKind, ProviderKind, ReportFormat, ReportLanguage};
use aps_ai::runtime::{AiRuntime, FrozenRun, PrepareOptions, RunStatus, RuntimeError};
use aps_ai::{AnalysisRequest, AnalysisResult, Evidence, SourceCandidate};
use aps_contracts::SourceDigest;
use aps_json::Value;
use futures::channel::oneshot;
use futures::future::{BoxFuture, Either, select};
use futures::{AsyncRead, Future};
use http_client::{AsyncBody, HttpClient, Request, Response, Url, http};
use std::collections::VecDeque;
use std::pin::Pin;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::task::{Context, Poll};
use std::time::Duration;

fn runtime() -> (std::path::PathBuf, std::sync::Arc<AiRuntime>) {
    let directory = std::env::temp_dir().join(format!(
        "aps-ai-runtime-{}-{}",
        std::process::id(),
        aps_ai::platform::new_opaque_id("test")
    ));
    std::fs::create_dir_all(&directory).unwrap();
    let runtime = AiRuntime::open_with_secret_store(&directory, AiSecretStore::memory()).unwrap();
    runtime
        .update_preferences(|preferences| {
            preferences.active_provider = ProviderKind::Custom;
            preferences.model = "test-model".into();
            preferences.connection_mut(ProviderKind::Custom).model = "test-model".into();
            preferences.connection_mut(ProviderKind::Custom).endpoint =
                "https://example.com/v1/responses".into();
        })
        .unwrap();
    (directory, runtime)
}

fn context() -> AnalysisRequest {
    AnalysisRequest {
        session_id: "session".into(),
        run_id: "run".into(),
        generation: 1,
        profiler: "frame".into(),
        scope: "frame evidence".into(),
        evidence: vec![Evidence {
            id: "frame:1".into(),
            kind: "frame".into(),
            summary: "frame took 24ms".into(),
            payload: {
                let mut map = aps_json::Map::new();
                map.insert("durationMs".into(), Value::from(24u64));
                Value::Object(map)
            },
        }],
        candidates: vec![SourceCandidate {
            id: "candidate:1".into(),
            workspace_id: "workspace".into(),
            snapshot_id: "snapshot".into(),
            content_digest: SourceDigest::hash_reader(b"source".as_slice(), 1024).unwrap(),
            relative_path: "app/src/main/Render.kt".into(),
            start_line: Some(12),
            end_line: Some(14),
            snippet: None,
        }],
        user_question: None,
        mcp: None,
    }
}

#[test]
fn direct_chat_freezes_aps_chat_without_evidence_or_sources() {
    let (directory, runtime) = runtime();
    let frozen = runtime
        .prepare(PrepareOptions {
            conversation_id: "conversation".into(),
            context: None,
            question: Some("Explain frame jank".into()),
            include_source: false,
            direct_chat: true,
            parent_turn: None,
            parent_conversation: None,
            report_format: ReportFormat::Html,
            report_language: ReportLanguage::Auto,
            agent_id: "aps.main".into(),
        })
        .unwrap();
    assert_eq!(frozen.view.agent_id, "aps.chat");
    assert_eq!(frozen.view.evidence_count, 0);
    assert_eq!(frozen.view.candidate_count, 0);
    assert_eq!(frozen.view.permissions, vec!["PROVIDER_NETWORK".to_owned()]);
    assert_eq!(frozen.view.planned_scripts, 0);
    assert_eq!(frozen.view.payload_hash.len(), 64);
    let _ = std::fs::remove_dir_all(directory);
}

#[test]
fn analysis_hides_sources_without_consent_and_requires_evidence() {
    let (directory, runtime) = runtime();
    let frozen = runtime
        .prepare(PrepareOptions {
            conversation_id: "conversation".into(),
            context: Some(context()),
            question: Some("Why".into()),
            include_source: false,
            direct_chat: false,
            parent_turn: None,
            parent_conversation: None,
            report_format: ReportFormat::Html,
            report_language: ReportLanguage::English,
            agent_id: "aps.main".into(),
        })
        .unwrap();
    assert_eq!(frozen.view.agent_id, "aps.main");
    assert_eq!(frozen.view.evidence_count, 1);
    assert_eq!(frozen.view.candidate_count, 0);
    assert!(
        frozen
            .view
            .permissions
            .contains(&"READ_EVIDENCE".to_owned())
    );
    assert!(
        !frozen
            .view
            .permissions
            .contains(&"SEND_SOURCE_SNIPPETS".to_owned())
    );

    let frozen = runtime
        .prepare(PrepareOptions {
            conversation_id: "conversation".into(),
            context: Some(context()),
            question: None,
            include_source: true,
            direct_chat: false,
            parent_turn: None,
            parent_conversation: None,
            report_format: ReportFormat::Markdown,
            report_language: ReportLanguage::Auto,
            agent_id: "aps.main".into(),
        })
        .unwrap();
    assert_eq!(frozen.view.candidate_count, 1);
    let source = &frozen.view.sources[0];
    assert_eq!(source.snapshot_id.as_deref(), Some("snapshot"));
    assert_eq!(
        source.content_digest,
        Some(context().candidates[0].content_digest.to_hex())
    );
    assert_eq!(source.start_line, Some(12));
    assert_eq!(source.end_line, Some(14));
    assert!(source.snippet.is_none());
    assert!(source.resolution_confidence.is_none());
    assert!(source.content_state.is_none());
    assert!(!source.snippet_truncated);
    assert!(
        frozen
            .view
            .permissions
            .contains(&"SEND_SOURCE_SNIPPETS".to_owned())
    );
    assert!(
        frozen
            .view
            .permissions
            .contains(&"WRITE_ARTIFACT".to_owned())
    );
    let _ = std::fs::remove_dir_all(directory);
}

#[test]
fn prepare_requires_a_model_and_refuses_direct_chat_without_question() {
    let (directory, runtime) = runtime();
    let result = runtime.prepare(PrepareOptions {
        conversation_id: "conversation".into(),
        context: None,
        question: None,
        include_source: false,
        direct_chat: true,
        parent_turn: None,
        parent_conversation: None,
        report_format: ReportFormat::Html,
        report_language: ReportLanguage::Auto,
        agent_id: "aps.main".into(),
    });
    assert!(result.is_err());
    runtime
        .update_preferences(|preferences| {
            preferences.model.clear();
            preferences
                .connection_mut(ProviderKind::Custom)
                .model
                .clear();
        })
        .unwrap();
    let result = runtime.prepare(PrepareOptions {
        conversation_id: "conversation".into(),
        context: Some(context()),
        question: Some("Why".into()),
        include_source: false,
        direct_chat: false,
        parent_turn: None,
        parent_conversation: None,
        report_format: ReportFormat::Html,
        report_language: ReportLanguage::Auto,
        agent_id: "aps.main".into(),
    });
    assert!(result.is_err());
    let _ = std::fs::remove_dir_all(directory);
}

#[test]
fn cancellation_invalidates_a_frozen_run_without_a_provider_request() {
    let (directory, runtime) = runtime();
    let client = FakeClient::install(&runtime);
    let frozen = runtime
        .prepare(PrepareOptions {
            conversation_id: "conversation".into(),
            context: None,
            question: Some("Explain".into()),
            include_source: false,
            direct_chat: true,
            parent_turn: None,
            parent_conversation: None,
            report_format: ReportFormat::Html,
            report_language: ReportLanguage::Auto,
            agent_id: "aps.main".into(),
        })
        .unwrap();
    runtime.cancel();
    let result = futures::executor::block_on(runtime.execute(frozen));
    assert!(matches!(result, Err(RuntimeError::Cancelled)));
    assert_eq!(client.calls.load(Ordering::SeqCst), 0);
    assert_cancelled_without_publication(&runtime, 0);
    let _ = std::fs::remove_dir_all(directory);
}

#[derive(Default)]
struct OperationProbe {
    polled: AtomicBool,
    dropped: AtomicBool,
}

struct PendingResponse {
    receiver: oneshot::Receiver<Response<AsyncBody>>,
    probe: Arc<OperationProbe>,
}

impl Drop for PendingResponse {
    fn drop(&mut self) {
        self.probe.dropped.store(true, Ordering::SeqCst);
    }
}

#[derive(Default)]
struct FakeClient {
    responses: Mutex<VecDeque<PendingResponse>>,
    calls: AtomicUsize,
}

impl FakeClient {
    fn install(runtime: &AiRuntime) -> Arc<Self> {
        let client = Arc::new(Self::default());
        runtime.set_http_client(client.clone()).unwrap();
        runtime
            .set_credential(
                ProviderKind::Custom,
                CredentialKind::ApiKey,
                "fake-test-secret",
            )
            .unwrap();
        client
    }

    fn queue(&self) -> (oneshot::Sender<Response<AsyncBody>>, Arc<OperationProbe>) {
        let (sender, receiver) = oneshot::channel();
        let probe = Arc::new(OperationProbe::default());
        self.responses.lock().unwrap().push_back(PendingResponse {
            receiver,
            probe: probe.clone(),
        });
        (sender, probe)
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
        assert_eq!(request.method(), http::Method::POST);
        assert_eq!(request.uri(), "https://example.com/v1/responses");
        self.calls.fetch_add(1, Ordering::SeqCst);
        let mut response = self
            .responses
            .lock()
            .unwrap()
            .pop_front()
            .expect("approved request");
        Box::pin(async move {
            response.probe.polled.store(true, Ordering::SeqCst);
            Ok((&mut response.receiver)
                .await
                .expect("fake response released"))
        })
    }
}

struct ControlledBody {
    receiver: oneshot::Receiver<Vec<u8>>,
    cursor: Option<futures::io::Cursor<Vec<u8>>>,
    probe: Arc<OperationProbe>,
    on_eof: Option<Box<dyn FnOnce() + Send + Sync>>,
}

impl AsyncRead for ControlledBody {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buffer: &mut [u8],
    ) -> Poll<std::io::Result<usize>> {
        let this = self.get_mut();
        this.probe.polled.store(true, Ordering::SeqCst);
        if this.cursor.is_none() {
            let bytes = match Pin::new(&mut this.receiver).poll(cx) {
                Poll::Pending => return Poll::Pending,
                Poll::Ready(bytes) => bytes.expect("fake body released"),
            };
            this.cursor = Some(futures::io::Cursor::new(bytes));
        }
        let result = Pin::new(this.cursor.as_mut().unwrap()).poll_read(cx, buffer);
        if matches!(result, Poll::Ready(Ok(0)))
            && let Some(on_eof) = this.on_eof.take()
        {
            on_eof();
        }
        result
    }
}

impl Drop for ControlledBody {
    fn drop(&mut self) {
        self.probe.dropped.store(true, Ordering::SeqCst);
    }
}

fn analysis_options() -> PrepareOptions {
    PrepareOptions {
        conversation_id: "conversation".into(),
        context: Some(context()),
        question: Some("Why".into()),
        include_source: false,
        direct_chat: false,
        parent_turn: None,
        parent_conversation: None,
        report_format: ReportFormat::Markdown,
        report_language: ReportLanguage::English,
        agent_id: "aps.main".into(),
    }
}

fn prepare_analysis(runtime: &AiRuntime) -> FrozenRun {
    let frozen = runtime.prepare(analysis_options()).unwrap();
    assert!(
        frozen
            .view
            .permissions
            .iter()
            .any(|p| p == "WRITE_ARTIFACT")
    );
    frozen
}

fn answer() -> Vec<u8> {
    serde_json::to_vec(&serde_json::json!({
        "status": "completed",
        "model": "test-model",
        "output_text": r#"{"summary":"Validated fake answer","findings":[]}"#,
    }))
    .unwrap()
}

fn response(body: impl Into<AsyncBody>) -> Response<AsyncBody> {
    Response::builder()
        .status(200)
        .header(http::header::CONTENT_TYPE, "application/json")
        .body(body.into())
        .unwrap()
}

async fn finish(
    execution: impl Future<Output = Result<AnalysisResult, RuntimeError>>,
) -> Result<AnalysisResult, RuntimeError> {
    let timeout = async_io::Timer::after(Duration::from_secs(2));
    futures::pin_mut!(execution, timeout);
    match select(execution, timeout).await {
        Either::Left((result, _)) => result,
        Either::Right(_) => panic!("provider operation did not finish promptly"),
    }
}

fn assert_cancelled_without_publication(runtime: &AiRuntime, index: usize) {
    let turns = runtime.transcript("conversation");
    assert_eq!(turns[index].status, RunStatus::Cancelled);
    assert!(turns[index].result.is_none());
    assert!(turns[index].artifact_hashes.is_empty());
    assert_eq!(
        std::fs::read_dir(runtime.artifacts_dir()).unwrap().count(),
        0
    );
}

#[test]
fn cancellation_reaches_dispatched_operation_after_prepared_slot_is_consumed() {
    let (directory, runtime) = runtime();
    let client = FakeClient::install(&runtime);
    let (reply, probe) = client.queue();
    let frozen = prepare_analysis(&runtime);
    futures::executor::block_on(async {
        let mut execution = Box::pin(runtime.execute(frozen));
        assert!(futures::poll!(execution.as_mut()).is_pending());
        assert!(probe.polled.load(Ordering::SeqCst));
        runtime.cancel();
        assert!(matches!(
            finish(execution).await,
            Err(RuntimeError::Cancelled)
        ));
    });
    assert!(probe.dropped.load(Ordering::SeqCst));
    assert!(reply.send(response(answer())).is_err());
    assert_eq!(client.calls.load(Ordering::SeqCst), 1);
    assert_cancelled_without_publication(&runtime, 0);
    let _ = std::fs::remove_dir_all(directory);
}

#[test]
fn cancellation_interrupts_a_stalled_response_body() {
    let (directory, runtime) = runtime();
    let client = FakeClient::install(&runtime);
    let (reply, _) = client.queue();
    let (body_reply, receiver) = oneshot::channel();
    let probe = Arc::new(OperationProbe::default());
    assert!(
        reply
            .send(response(AsyncBody::from_reader(ControlledBody {
                receiver,
                cursor: None,
                probe: probe.clone(),
                on_eof: None,
            })))
            .is_ok()
    );
    let frozen = prepare_analysis(&runtime);
    futures::executor::block_on(async {
        let mut execution = Box::pin(runtime.execute(frozen));
        assert!(futures::poll!(execution.as_mut()).is_pending());
        assert!(probe.polled.load(Ordering::SeqCst));
        runtime.cancel();
        assert!(matches!(
            finish(execution).await,
            Err(RuntimeError::Cancelled)
        ));
    });
    assert!(probe.dropped.load(Ordering::SeqCst));
    assert!(body_reply.send(answer()).is_err());
    assert_cancelled_without_publication(&runtime, 0);
    let _ = std::fs::remove_dir_all(directory);
}

#[test]
fn cancellation_wins_when_valid_response_finishes_in_the_same_poll() {
    let (directory, runtime) = runtime();
    let client = FakeClient::install(&runtime);
    let (reply, _) = client.queue();
    let (body_reply, receiver) = oneshot::channel();
    let cancelling_runtime = runtime.clone();
    assert!(body_reply.send(answer()).is_ok());
    assert!(
        reply
            .send(response(AsyncBody::from_reader(ControlledBody {
                receiver,
                cursor: None,
                probe: Arc::new(OperationProbe::default()),
                on_eof: Some(Box::new(move || cancelling_runtime.cancel())),
            })))
            .is_ok()
    );
    let frozen = prepare_analysis(&runtime);
    assert!(matches!(
        futures::executor::block_on(finish(runtime.execute(frozen))),
        Err(RuntimeError::Cancelled)
    ));
    assert_cancelled_without_publication(&runtime, 0);
    let _ = std::fs::remove_dir_all(directory);
}

#[test]
fn superseded_late_response_cannot_publish_or_clear_newer_operation_cancellation() {
    let (directory, runtime) = runtime();
    let client = FakeClient::install(&runtime);
    let (old_reply, old_probe) = client.queue();
    let (new_reply, new_probe) = client.queue();
    let frozen = prepare_analysis(&runtime);
    futures::executor::block_on(async {
        let mut old = Box::pin(runtime.execute(frozen));
        assert!(futures::poll!(old.as_mut()).is_pending());
        assert!(old_probe.polled.load(Ordering::SeqCst));
        let newer = prepare_analysis(&runtime);
        let mut new = Box::pin(runtime.execute(newer));
        assert!(futures::poll!(new.as_mut()).is_pending());
        assert!(new_probe.polled.load(Ordering::SeqCst));
        assert!(old_reply.send(response(answer())).is_ok());
        assert!(matches!(finish(old).await, Err(RuntimeError::Cancelled)));
        assert_cancelled_without_publication(&runtime, 0);
        runtime.cancel();
        assert!(matches!(finish(new).await, Err(RuntimeError::Cancelled)));
    });
    assert!(new_probe.dropped.load(Ordering::SeqCst));
    assert!(new_reply.send(response(answer())).is_err());
    assert_cancelled_without_publication(&runtime, 1);
    assert_eq!(client.calls.load(Ordering::SeqCst), 2);
    let _ = std::fs::remove_dir_all(directory);
}

#[test]
fn cancelling_old_operation_does_not_cancel_newer_success_or_its_artifact() {
    let (directory, runtime) = runtime();
    let client = FakeClient::install(&runtime);
    let (old_reply, _) = client.queue();
    let (new_reply, _) = client.queue();
    let frozen = prepare_analysis(&runtime);
    futures::executor::block_on(async {
        let mut old = Box::pin(runtime.execute(frozen));
        assert!(futures::poll!(old.as_mut()).is_pending());
        runtime.cancel();
        let newer = prepare_analysis(&runtime);
        let new_run_id = newer.view.run_id.clone();
        let mut new = Box::pin(runtime.execute(newer));
        assert!(futures::poll!(new.as_mut()).is_pending());
        assert!(old_reply.send(response(answer())).is_ok());
        assert!(matches!(finish(old).await, Err(RuntimeError::Cancelled)));
        assert_cancelled_without_publication(&runtime, 0);
        assert!(new_reply.send(response(answer())).is_ok());
        let result = finish(new).await.unwrap();
        assert_eq!(result.run_id, new_run_id);
        assert_eq!(result.generation, 2);
    });
    let turns = runtime.transcript("conversation");
    assert_eq!(turns[0].status, RunStatus::Cancelled);
    assert!(turns[0].result.is_none());
    assert!(turns[0].artifact_hashes.is_empty());
    assert_eq!(turns[1].status, RunStatus::Succeeded);
    assert!(turns[1].result.is_some());
    assert_eq!(turns[1].artifact_hashes.len(), 1);
    assert!(
        runtime
            .artifact_path(&turns[1].artifact_hashes[0])
            .is_some()
    );
    assert_eq!(
        std::fs::read_dir(runtime.artifacts_dir()).unwrap().count(),
        1
    );
    assert_eq!(client.calls.load(Ordering::SeqCst), 2);
    let _ = std::fs::remove_dir_all(directory);
}

#[test]
fn superseded_approval_cannot_consume_newer_one_shot_approval() {
    let (directory, runtime) = runtime();
    let client = FakeClient::install(&runtime);
    let (reply, _) = client.queue();
    let old = prepare_analysis(&runtime);
    let new = prepare_analysis(&runtime);
    assert!(matches!(
        futures::executor::block_on(runtime.execute(old)),
        Err(RuntimeError::Cancelled)
    ));
    assert_eq!(client.calls.load(Ordering::SeqCst), 0);
    assert!(reply.send(response(answer())).is_ok());
    assert!(futures::executor::block_on(finish(runtime.execute(new))).is_ok());
    assert_eq!(client.calls.load(Ordering::SeqCst), 1);
    let _ = std::fs::remove_dir_all(directory);
}

#[test]
fn dropping_execution_future_releases_only_its_provider_operation() {
    let (directory, runtime) = runtime();
    let client = FakeClient::install(&runtime);
    let (reply, probe) = client.queue();
    let frozen = prepare_analysis(&runtime);
    futures::executor::block_on(async {
        let mut execution = Box::pin(runtime.execute(frozen));
        assert!(futures::poll!(execution.as_mut()).is_pending());
        drop(execution);
    });
    assert!(probe.dropped.load(Ordering::SeqCst));
    assert!(reply.send(response(answer())).is_err());
    let (reply, _) = client.queue();
    let frozen = prepare_analysis(&runtime);
    assert!(reply.send(response(answer())).is_ok());
    assert!(futures::executor::block_on(finish(runtime.execute(frozen))).is_ok());
    assert_eq!(client.calls.load(Ordering::SeqCst), 2);
    let _ = std::fs::remove_dir_all(directory);
}

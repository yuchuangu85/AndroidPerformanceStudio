//! Runtime freeze/approval tests. No provider request is made here: tests only
//! cover the explicit approval boundary, prompt construction and cancellation.

use aps_ai::platform::{ProviderKind, ReportFormat, ReportLanguage};
use aps_ai::runtime::{AiRuntime, PrepareOptions};
use aps_ai::{AnalysisRequest, Evidence, SourceCandidate};
use aps_contracts::SourceDigest;
use aps_json::Value;

fn runtime() -> (std::path::PathBuf, std::sync::Arc<AiRuntime>) {
    let directory = std::env::temp_dir().join(format!(
        "aps-ai-runtime-{}-{}",
        std::process::id(),
        aps_ai::platform::new_opaque_id("test")
    ));
    std::fs::create_dir_all(&directory).unwrap();
    let runtime = AiRuntime::open(&directory).unwrap();
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
    runtime
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
    let result = futures::executor::block_on(async {
        // Re-preparing replaces the slot; the old token is gone, so execution
        // without the client fails at the approval boundary instead of sending.
        let frozen = runtime
            .prepare(PrepareOptions {
                conversation_id: "conversation".into(),
                context: None,
                question: Some("Explain again".into()),
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
        runtime.execute(frozen).await
    });
    assert!(result.is_err());
    let _ = std::fs::remove_dir_all(directory);
}

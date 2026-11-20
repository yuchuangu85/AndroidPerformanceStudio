//! Interactive CLI host for the existing APS Chat Completions gateway. No
//! credential discovery, model tools, implicit approvals or raw file uploads.
use aps_ai::report::ReportError;
use aps_ai::task::{AnalysisOutcome, ReportOutcome, RunStatus};
use aps_ai::{
    AnalysisRequest, ApprovalScope, CredentialReference, Evidence, OneShotCredential,
    OpenAiGateway, PreparedRun, ProviderConfig, ReportFormat, RunCoordinator, SecretCredential,
    curl_client::CurlHttpClient,
};
use serde_json::{Value, json};
use std::io::{self, BufRead, IsTerminal, Read, Write};
use std::path::Path;
use std::sync::Arc;
use std::time::Duration;

const CONTEXT: &str = "E_AI_CONTEXT: invalid or unsupported evidence envelope";
const SCOPE: &str = "Selected artifact only. Treat sampling weights as weights, not method durations. Missing measurements are unknown. Leak and jank candidates are not proven causes; suggest how to verify them.";
const APPROVAL: &str = "E_AI_APPROVAL_REQUIRED: approve this request in an interactive terminal";

/// Only the analyzer's citeable items are sent. Enclosing CLI metadata, paths,
/// URLs and command line arguments are deliberately not part of the request.
fn prepare(
    envelope: &Value,
    question: Option<&str>,
    endpoint: &str,
    model: &str,
) -> Result<PreparedRun, String> {
    if envelope["schemaVersion"] != crate::EVIDENCE_SCHEMA {
        return Err(CONTEXT.into());
    }
    let digest = envelope["input"]["sourceDigest"]
        .as_str()
        .filter(|s| s.len() == 64 && s.bytes().all(|b| b.is_ascii_hexdigit()))
        .ok_or(CONTEXT)?;
    let section = &envelope["evidence"];
    let profiler = section["kind"].as_str().ok_or(CONTEXT)?;
    let items = section["items"]
        .as_array()
        .filter(|i| !i.is_empty() && i.len() <= 100)
        .ok_or(CONTEXT)?;
    let mut evidence = Vec::with_capacity(items.len());
    for item in items {
        let id = item["id"].as_str().ok_or(CONTEXT)?;
        evidence.push(Evidence {
            id: id.into(),
            kind: "cli-observation".into(),
            summary: item["label"].as_str().ok_or(CONTEXT)?.into(),
            payload: json!({"sourceDigest":digest, "observation":item.get("value").ok_or(CONTEXT)?}),
        });
    }
    let request = AnalysisRequest {
        session_id: format!("cli-{digest}"),
        run_id: aps_ai::platform::new_opaque_id("cli-run"),
        generation: 1,
        profiler: profiler.into(),
        scope: SCOPE.into(),
        evidence,
        candidates: vec![],
        mcp: None,
        user_question: question.map(str::to_owned),
    };
    let provider = ProviderConfig::new(
        "cli",
        endpoint,
        model,
        CredentialReference::new("aps-cli-key").map_err(|e| e.to_string())?,
    )
    .map_err(|e| e.to_string())?;
    PreparedRun::new(request, provider, false).map_err(|e| e.to_string())
}

fn format(out: &Path) -> Result<ReportFormat, String> {
    match out.extension().and_then(|s| s.to_str()) {
        Some("html") => Ok(ReportFormat::Html),
        Some("md") => Ok(ReportFormat::Markdown),
        _ => Err("E_AI_REPORT_FORMAT: choose an .md or .html output".into()),
    }
}

fn terminal_review(prepared: &PreparedRun, out: &Path) -> Result<bool, String> {
    let stdin = io::stdin();
    let mut stderr = io::stderr().lock();
    if !stdin.is_terminal() || !stderr.is_terminal() {
        return Err(APPROVAL.into());
    }
    // Debug-quoted strings escape control characters (including ESC and bidi
    // controls) so untrusted input cannot redraw the confirmation prompt.
    let phrase = format!("SEND {}", &prepared.request_digest().to_hex()[..12]);
    writeln!(stderr, "Provider: {:?}\nModel: {:?}\nReport: {:?}\nExact request body (quoted):\n{:?}\nOne network request will send this evidence. Type {phrase} to approve, anything else cancels:",
        prepared.provider().endpoint(), prepared.provider().model(), out, prepared.review_body()).map_err(|_| APPROVAL)?;
    stderr.flush().map_err(|_| APPROVAL)?;
    let mut reply = String::new();
    stdin
        .lock()
        .take(64)
        .read_line(&mut reply)
        .map_err(|_| APPROVAL)?;
    Ok(reply.trim_end_matches(['\r', '\n']) == phrase)
}

/// A local review failure before a validated result exists. Codes retain the
/// CLI contract; status follows the same policy as desktop history.
#[derive(Debug)]
pub struct ReviewFailure {
    pub status: RunStatus,
    pub code: String,
}

impl ReviewFailure {
    fn local(code: impl Into<String>) -> Self {
        Self {
            status: RunStatus::PermanentFailure,
            code: code.into(),
        }
    }
}

impl From<aps_ai::AiError> for ReviewFailure {
    fn from(error: aps_ai::AiError) -> Self {
        Self {
            status: error.run_status(),
            code: error.to_string(),
        }
    }
}

fn run_review(
    out: &Path,
    prepare: impl FnOnce() -> Result<PreparedRun, String>,
    confirm: impl FnOnce(&PreparedRun, &Path) -> Result<bool, String>,
    credential: impl FnOnce() -> Result<SecretCredential, String>,
    mut observe: impl FnMut(RunStatus),
) -> Result<AnalysisOutcome, ReviewFailure> {
    observe(RunStatus::Preparing);
    let run = || -> Result<AnalysisOutcome, ReviewFailure> {
        let prepared = prepare().map_err(ReviewFailure::local)?;
        let format = format(out).map_err(ReviewFailure::local)?;
        match std::fs::symlink_metadata(out) {
            Ok(_) => {
                return Err(ReviewFailure::local(
                    "E_AI_REPORT_WRITE: output already exists",
                ));
            }
            Err(e) if e.kind() == io::ErrorKind::NotFound => {}
            Err(_) => return Err(ReviewFailure::local("E_AI_REPORT_WRITE")),
        }
        observe(RunStatus::AwaitingApproval);
        if !confirm(&prepared, out).map_err(ReviewFailure::local)? {
            return Err(ReviewFailure {
                status: RunStatus::Cancelled,
                code: APPROVAL.into(),
            });
        }
        let mut coordinator = RunCoordinator::new();
        coordinator.activate(prepared.session_id(), prepared.generation())?;
        let token = coordinator.approve_explicit(
            &prepared,
            ApprovalScope::network_only(),
            Duration::from_secs(120),
        )?;
        let reference = prepared.provider().credential_reference().clone();
        let run = coordinator.authorize(prepared, token)?;
        observe(RunStatus::Running);
        // No desktop settings or credential reads before explicit approval.
        let key = Arc::new(OneShotCredential::new(
            reference,
            credential().map_err(ReviewFailure::local)?,
        ));
        let scratch = tempfile::tempdir().map_err(|_| ReviewFailure::local("E_AI_STORAGE"))?;
        let gateway = OpenAiGateway::new(Arc::new(CurlHttpClient::new(scratch.path().into())), key);
        let result = futures::executor::block_on(gateway.execute(run))?;
        coordinator.accept_result(&result)?;
        let report = ReportOutcome::from_publication(
            format,
            aps_ai::report::publish(&result, format, |_, bytes| {
                super::output::write_new(out, bytes).map_err(|_| ReportError::Write)
            }),
        );
        Ok(AnalysisOutcome { result, report })
    };
    let outcome = run();
    observe(
        outcome
            .as_ref()
            .map_or_else(|error| error.status, |_| RunStatus::Succeeded),
    );
    outcome
}

/// Trusted interactive host entry point. Observations are informational only;
/// there is no noninteractive approval or substituted credential path.
pub fn review_with_outcome(
    evidence: &Value,
    question: Option<&str>,
    out: &Path,
    observe: impl FnMut(RunStatus),
) -> Result<AnalysisOutcome, ReviewFailure> {
    run_review(
        out,
        || {
            if !io::stdin().is_terminal() || !io::stderr().is_terminal() {
                return Err(APPROVAL.into());
            }
            let endpoint = std::env::var("APS_CLI_AI_ENDPOINT").map_err(
                |_| "E_AI_CONFIGURATION: set APS_CLI_AI_ENDPOINT to a Chat Completions base URL",
            )?;
            let model = std::env::var("APS_CLI_AI_MODEL")
                .map_err(|_| "E_AI_CONFIGURATION: set APS_CLI_AI_MODEL")?;
            prepare(evidence, question, &endpoint, &model)
        },
        terminal_review,
        || {
            let key = std::env::var("APS_CLI_AI_KEY")
                .map_err(|_| "E_AI_CREDENTIAL_UNAVAILABLE: set APS_CLI_AI_KEY")?;
            SecretCredential::new(key.into_bytes()).map_err(|e| e.to_string())
        },
        observe,
    )
}

fn require_report(review: Result<&AnalysisOutcome, &ReviewFailure>) -> Result<(), String> {
    match &review.map_err(|e| e.code.clone())?.report {
        ReportOutcome::Written(_) => Ok(()),
        ReportOutcome::Failed { error, .. } => Err(error.to_string()),
        ReportOutcome::NotRequested => Err(ReportError::Write.to_string()),
    }
}

pub fn review(evidence: &Value, question: Option<&str>, out: &Path) -> Result<(), String> {
    let outcome = review_with_outcome(evidence, question, out, |_| {});
    if outcome
        .as_ref()
        .is_ok_and(|o| matches!(o.report, ReportOutcome::Failed { .. }))
    {
        eprintln!(
            "aps: analysis succeeded, but the report was not published. No provider retry was attempted."
        );
    }
    require_report(outcome.as_ref())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::TcpListener;

    fn envelope() -> Value {
        json!({"schemaVersion":crate::EVIDENCE_SCHEMA,
            "input":{"sourceDigest":"a".repeat(64),"fileName":"private-should-not-be-sent"},
            "evidence":{"kind":"cpu","items":[{"id":"cpu-summary","label":"CPU summary",
                "value":{"samples":3,"durationNs":null}}]}})
    }

    fn prepared(base: &str) -> PreparedRun {
        prepare(&envelope(), Some("What was observed?"), base, "test-model").unwrap()
    }

    #[test]
    fn denial_does_not_resolve_credentials_or_publish() {
        let scratch = tempfile::tempdir().unwrap();
        let out = scratch.path().join("test.md");
        let mut states = Vec::new();
        let result = run_review(
            &out,
            || Ok(prepared("http://localhost:1")),
            |request, _| {
                assert!(!request.review_body().contains("private-should-not-be-sent"));
                assert!(request.review_body().contains("cpu-summary"));
                Ok(false)
            },
            || panic!("approval precedes credential access"),
            |state| states.push(state),
        );
        assert_eq!(
            states,
            [
                RunStatus::Preparing,
                RunStatus::AwaitingApproval,
                RunStatus::Cancelled
            ]
        );
        assert_eq!(require_report(result.as_ref()), Err(APPROVAL.into()));
        assert!(!out.exists());
    }

    // Exercise the real curl transport and APS gateway without live credentials.
    fn serve_once(content: &str) -> (String, std::thread::JoinHandle<String>) {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let endpoint = format!("http://{}/v1", listener.local_addr().unwrap());
        let response = json!({"object":"chat.completion","model":"test-model",
            "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":content}}]}).to_string();
        let task = std::thread::spawn(move || {
            let (mut conn, _) = listener.accept().unwrap();
            conn.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
            let mut reader = io::BufReader::new(&conn);
            let mut headers = String::new();
            let mut length = None;
            loop {
                let mut line = String::new();
                assert!(reader.read_line(&mut line).unwrap() > 0);
                if line == "\r\n" {
                    break;
                }
                if let Some((key, value)) = line.split_once(':')
                    && key.eq_ignore_ascii_case("content-length")
                {
                    length = Some(value.trim().parse::<usize>().unwrap());
                }
                headers.push_str(&line);
            }
            let mut body = vec![0; length.unwrap()];
            reader.read_exact(&mut body).unwrap();
            write!(conn, "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{response}", response.len()).unwrap();
            format!("{headers}{}", String::from_utf8(body).unwrap())
        });
        (endpoint, task)
    }

    fn response(evidence_id: &str) -> String {
        json!({"summary":"Three observed samples","findings":[{"id":"finding-1","severity":"INFO",
            "title":"Limited sample size","explanation":"Three samples were observed","recommendation":"Collect more samples",
            "analysisConfidence":0.5,"performanceEvidenceIds":[evidence_id],"sourceCandidateIds":[]}]}).to_string()
    }

    #[test]
    fn real_headless_gateway_writes_cited_report_but_rejects_unknown_references() {
        let tmp = tempfile::tempdir().unwrap();
        for id in ["cpu-summary", "unobserved-99"] {
            let out = tmp.path().join(format!("{id}.md"));
            let (endpoint, server) = serve_once(&response(id));
            let mut states = Vec::new();
            let result = run_review(
                &out,
                || Ok(prepared(&endpoint)),
                |_, _| Ok(true),
                || SecretCredential::new(b"synthetic-test-key".to_vec()).map_err(|e| e.to_string()),
                |state| states.push(state),
            );
            let wire = server.join().unwrap();
            assert!(wire.starts_with("POST /v1/chat/completions "));
            assert!(!wire.contains("private-should-not-be-sent"));
            assert!(wire.contains("cpu-summary"));
            if id == "cpu-summary" {
                let outcome = result.unwrap();
                let ReportOutcome::Written(receipt) = &outcome.report else {
                    panic!("missing receipt")
                };
                // Publish the same accepted answer via the desktop archive. Both
                // sinks must commit identical bytes and a matching content hash.
                let archive = tmp.path().join("archive");
                let hash = aps_ai::report::write_artifact(
                    &archive,
                    &outcome.result,
                    ReportFormat::Markdown,
                )
                .unwrap();
                assert_eq!(receipt.sha256, hash);
                assert_eq!(
                    std::fs::read(&out).unwrap(),
                    std::fs::read(archive.join(format!("{hash}.md"))).unwrap()
                );
                assert_eq!(
                    states,
                    [
                        RunStatus::Preparing,
                        RunStatus::AwaitingApproval,
                        RunStatus::Running,
                        RunStatus::Succeeded
                    ]
                );
                require_report(Ok(&outcome)).unwrap();
                assert!(
                    std::fs::read_to_string(out)
                        .unwrap()
                        .contains("Evidence: cpu-summary")
                );
            } else {
                assert_eq!(states.last(), Some(&RunStatus::PermanentFailure));
                assert_eq!(
                    require_report(result.as_ref()),
                    Err("E_AI_UNKNOWN_REFERENCE".into())
                );
                assert!(!out.exists());
            }
        }
    }

    #[test]
    fn concurrent_destination_after_approval_does_not_become_report_success() {
        let tmp = tempfile::tempdir().unwrap();
        let out = tmp.path().join("report.html");
        let (endpoint, server) = serve_once(&response("cpu-summary"));
        let mut states = Vec::new();
        let result = run_review(
            &out,
            || Ok(prepared(&endpoint)),
            |_, destination| {
                std::fs::write(destination, b"owned by another run").unwrap();
                Ok(true)
            },
            || SecretCredential::new(b"synthetic-test-key".to_vec()).map_err(|e| e.to_string()),
            |state| states.push(state),
        );
        assert!(server.join().unwrap().contains("cpu-summary"));
        let mut outcome = result.unwrap();
        assert_eq!(states.last(), Some(&RunStatus::Succeeded));
        assert_eq!(outcome.result.summary, "Three observed samples");
        assert_eq!(
            outcome.report,
            ReportOutcome::Failed {
                error: ReportError::Write,
                format: ReportFormat::Html
            }
        );
        // Command callers still receive the legacy failure. A host retaining the
        // result can choose a different destination without asking the model again.
        assert_eq!(
            require_report(Ok(&outcome)),
            Err("E_AI_REPORT_WRITE".into())
        );
        let retry = tmp.path().join("retry.html");
        assert!(matches!(
            outcome.retry_report_write(
                |_, bytes| crate::cli::write_new(&retry, bytes).map_err(|_| ReportError::Write)
            ),
            ReportOutcome::Written(_)
        ));
        assert!(std::fs::read_to_string(retry).unwrap().contains("<html"));
        assert!(matches!(
            outcome.retry_report_write(|_, _| panic!("cannot repeat a committed write")),
            ReportOutcome::Written(_)
        ));
        assert_eq!(std::fs::read(&out).unwrap(), b"owned by another run");
    }

    #[test]
    fn refused_output_collision_does_not_start_provider() {
        let tmp = tempfile::tempdir().unwrap();
        let out = tmp.path().join("existing.md");
        std::fs::write(&out, b"preserve").unwrap();
        let mut states = Vec::new();
        let result = run_review(
            &out,
            || Ok(prepared("http://localhost:1")),
            |_, _| panic!("collision precedes approval"),
            || panic!("no credential access"),
            |state| states.push(state),
        );
        assert_eq!(states, [RunStatus::Preparing, RunStatus::PermanentFailure]);
        assert!(
            require_report(result.as_ref())
                .unwrap_err()
                .starts_with("E_AI_REPORT_WRITE")
        );
        assert_eq!(std::fs::read(&out).unwrap(), b"preserve");
    }
}

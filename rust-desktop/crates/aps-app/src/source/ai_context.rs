//! Bounded, metadata-only source selection evidence for the AI preflight.
//!
//! Mirrors Compose SourceWorkspaceAiContext.kt: source text is never published
//! to the AI context, only verified snapshot/file identity and line bounds.

use crate::evidence::request;
use aps_ai::{AnalysisRequest, SourceCandidate};
use aps_json::Value;
use aps_source::{
    ResolutionCandidate, ResolutionConfidence, SourceContentState, SourceFile, SourceLocation,
    SourceSnapshot, SourceWorkspace, VerifiedSourceContent,
};

const SCOPE: &str = "One verified, hash-bound source selection metadata; no source text, no working-tree content and no path discovery.";

#[allow(clippy::too_many_arguments)]
pub(super) fn analysis_request(
    workspace: &SourceWorkspace,
    snapshot: &SourceSnapshot,
    file: &SourceFile,
    location: &SourceLocation,
    content: &VerifiedSourceContent,
    candidate: Option<&ResolutionCandidate>,
    source_upload_allowed: bool,
) -> Result<AnalysisRequest, String> {
    if content.location != *location {
        return Err("the selected source content no longer matches its location".into());
    }
    if snapshot.id != location.snapshot_id || snapshot.workspace_id != location.workspace_id {
        return Err("the selected source snapshot does not own the location".into());
    }
    if file.snapshot_id != location.snapshot_id
        || file.relative_path != location.relative_path
        || file.content_hash != location.content_hash
    {
        return Err("the indexed source file does not match the location".into());
    }
    let line_count = content.text.lines().count().max(1);
    if let Some(range) = &location.range
        && (range.start_line < 1
            || range.end_line < range.start_line
            || range.end_line > line_count)
    {
        return Err("the selected line range is not inside the verified content".into());
    }
    let state = match content.state {
        SourceContentState::Current => "CURRENT",
        SourceContentState::Stale => "STALE",
    };
    let payload = crate::evidence::object([
        ("evidenceType", Value::from("source-metadata")),
        ("performanceMeasurement", Value::from(false)),
        (
            "snapshotId",
            Value::from(format!("source-snapshot:{}", snapshot.id.to_hex())),
        ),
        ("contentHash", Value::from(location.content_hash.to_hex())),
        (
            "language",
            Value::from(format!("{:?}", file.language).to_uppercase()),
        ),
        ("contentState", Value::from(state)),
        ("sizeBytes", Value::from(file.size_bytes)),
        ("lineCount", Value::from(line_count as u64)),
        (
            "startLine",
            location
                .range
                .as_ref()
                .map(|range| Value::from(range.start_line as u64))
                .unwrap_or(Value::Null),
        ),
        (
            "endLine",
            location
                .range
                .as_ref()
                .map(|range| Value::from(range.end_line as u64))
                .unwrap_or(Value::Null),
        ),
    ]);
    let summary = match content.state {
        SourceContentState::Current => {
            "Verified snapshot source metadata only; not performance measurement or current working-tree contents"
        }
        SourceContentState::Stale => {
            "Verified immutable snapshot source metadata only; the current working-tree file has changed"
        }
    };
    let mut request = request("source-workspace", SCOPE, summary, payload)?;
    // Compose publishes a source-specific evidence identity rather than the
    // generic `selection` id used by other profilers.
    request.evidence[0].id = "source-selection".into();
    request.evidence[0].kind = "source-workspace-selection".into();
    request.session_id = format!("source-{}", snapshot.id.to_hex());
    request.run_id = match candidate {
        Some(candidate) => format!("source-selection-{}", candidate.id.to_hex()),
        None => format!("source-file-{}", file.content_hash.to_hex()),
    };
    let _ = workspace;
    if let Some(candidate) = candidate.filter(|candidate| {
        source_upload_allowed
            && candidate.location == *location
            && candidate.confidence != ResolutionConfidence::Weak
    }) {
        let (start_line, end_line) = location
            .range
            .as_ref()
            .map(|range| (range.start_line, range.end_line))
            .unwrap_or((1, line_count));
        request.candidates = vec![SourceCandidate {
            id: candidate.id.to_hex(),
            workspace_id: location.workspace_id.to_hex(),
            snapshot_id: location.snapshot_id.to_hex(),
            content_digest: location.content_hash,
            relative_path: location.relative_path.clone(),
            start_line: Some(start_line as u32),
            end_line: Some(end_line as u32),
            snippet: None,
        }];
    }
    Ok(request)
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_source::{
        PerformanceEvidenceId, ResolutionCandidateId, SourceProviderConfig, SourceRange,
        SourceSnapshotId, SourceWorkspaceId, digest_text,
    };
    use std::path::PathBuf;

    fn snapshot() -> SourceSnapshot {
        SourceSnapshot {
            id: SourceSnapshotId::from_digest(digest_text("snapshot")),
            workspace_id: SourceWorkspaceId::from_digest(digest_text("workspace")),
            immutable_revision: "unversioned".into(),
            dirty_content_digest: None,
            manifest_hash: digest_text("manifest"),
            created_unix_ms: 1,
            index_version: 1,
            index_complete: true,
        }
    }

    fn workspace() -> SourceWorkspace {
        let snapshot = snapshot();
        SourceWorkspace {
            id: snapshot.workspace_id,
            display_name: "Fixture source".into(),
            config: SourceProviderConfig::Local {
                root: PathBuf::from("/tmp/fixture"),
            },
            active_snapshot_id: Some(snapshot.id),
            phase: aps_source::SourceWorkspacePhase::Ready,
            progress: 1.0,
            message: None,
            allow_ai_source_upload: false,
        }
    }

    fn file() -> SourceFile {
        SourceFile {
            snapshot_id: snapshot().id,
            relative_path: "src/Main.kt".into(),
            language: aps_source::SourceLanguage::Kotlin,
            content_hash: digest_text("class Main { fun run() = 1 }"),
            size_bytes: 27,
        }
    }

    fn location() -> SourceLocation {
        let file = file();
        SourceLocation {
            workspace_id: snapshot().workspace_id,
            snapshot_id: snapshot().id,
            relative_path: file.relative_path,
            range: Some(SourceRange {
                start_line: 2,
                end_line: 2,
            }),
            content_hash: file.content_hash,
        }
    }

    fn content(text: &str, state: SourceContentState) -> VerifiedSourceContent {
        VerifiedSourceContent {
            location: location(),
            text: text.into(),
            state,
        }
    }

    fn make_candidate(
        confidence: ResolutionConfidence,
        location: &SourceLocation,
    ) -> ResolutionCandidate {
        ResolutionCandidate {
            id: ResolutionCandidateId::from_digest(digest_text("candidate")),
            evidence_id: PerformanceEvidenceId::from_digest(digest_text("evidence")),
            location: location.clone(),
            confidence,
            reasons: vec!["Qualified type matched".into()],
            index_version: 1,
            index_complete: true,
        }
    }

    fn text() -> String {
        "package sample\nclass Main { fun run() = 1 }\n".into()
    }

    #[test]
    fn verified_selection_publishes_bounded_metadata_without_source_text() {
        let file = file();
        let location = location();
        let content = content(&text(), SourceContentState::Current);
        let candidate = make_candidate(ResolutionConfidence::Probable, &location);
        let request = analysis_request(
            &workspace(),
            &snapshot(),
            &file,
            &location,
            &content,
            Some(&candidate),
            true,
        )
        .unwrap();
        assert_eq!(request.profiler, "source-workspace");
        assert_eq!(request.evidence.len(), 1);
        assert_eq!(request.evidence[0].id, "source-selection");
        assert_eq!(request.evidence[0].kind, "source-workspace-selection");
        assert_eq!(request.candidates.len(), 1);
        let published = request.candidates[0].clone();
        assert_eq!(published.relative_path, "src/Main.kt");
        assert_eq!(published.start_line, Some(2));
        assert_eq!(published.end_line, Some(2));
        assert!(published.snippet.is_none());
        let serialized =
            aps_json::to_vec(&request.evidence[0].payload, aps_json::Limits::default())
                .map(String::from_utf8)
                .unwrap()
                .unwrap();
        assert!(serialized.contains("source-metadata"));
        assert!(serialized.contains("\"startLine\":2"));
        assert!(!serialized.contains("class Main"));
        assert!(!serialized.contains("src/Main.kt"));
        assert!(!request.evidence[0].summary.contains("class Main"));
    }

    #[test]
    fn blocked_upload_and_weak_candidates_publish_no_candidate() {
        let file = file();
        let location = location();
        let content = content(&text(), SourceContentState::Current);
        let candidate = make_candidate(ResolutionConfidence::Probable, &location);
        let blocked = analysis_request(
            &workspace(),
            &snapshot(),
            &file,
            &location,
            &content,
            Some(&candidate),
            false,
        )
        .unwrap();
        assert!(blocked.candidates.is_empty());
        let weak = analysis_request(
            &workspace(),
            &snapshot(),
            &file,
            &location,
            &content,
            Some(&make_candidate(ResolutionConfidence::Weak, &location)),
            true,
        )
        .unwrap();
        assert!(weak.candidates.is_empty());
    }

    #[test]
    fn identity_mismatches_fail_closed() {
        let file = file();
        let location = location();
        let content = content(&text(), SourceContentState::Current);

        let mut mismatched_content = content.clone();
        mismatched_content.location.relative_path = "src/Other.kt".into();
        assert!(
            analysis_request(
                &workspace(),
                &snapshot(),
                &file,
                &location,
                &mismatched_content,
                None,
                false
            )
            .is_err()
        );

        let mut mismatched_file = file.clone();
        mismatched_file.content_hash = digest_text("different");
        assert!(
            analysis_request(
                &workspace(),
                &snapshot(),
                &mismatched_file,
                &location,
                &content,
                None,
                false
            )
            .is_err()
        );

        let mut foreign_snapshot = snapshot();
        foreign_snapshot.workspace_id =
            SourceWorkspaceId::from_digest(digest_text("other-workspace"));
        assert!(
            analysis_request(
                &workspace(),
                &foreign_snapshot,
                &file,
                &location,
                &content,
                None,
                false
            )
            .is_err()
        );
    }

    #[test]
    fn ranges_outside_the_verified_content_fail_closed() {
        let file = file();
        let mut location = location();
        location.range = Some(SourceRange {
            start_line: 36,
            end_line: 36,
        });
        assert!(
            analysis_request(
                &workspace(),
                &snapshot(),
                &file,
                &location,
                &content(&text(), SourceContentState::Current),
                None,
                false
            )
            .is_err()
        );
    }

    #[test]
    fn whole_file_selection_has_no_line_range_and_stale_state_is_reported() {
        let file = file();
        let mut location = location();
        location.range = None;
        let mut content = content(&text(), SourceContentState::Stale);
        content.location = location.clone();
        let request = analysis_request(
            &workspace(),
            &snapshot(),
            &file,
            &location,
            &content,
            None,
            false,
        )
        .unwrap();
        let serialized =
            aps_json::to_vec(&request.evidence[0].payload, aps_json::Limits::default())
                .map(String::from_utf8)
                .unwrap()
                .unwrap();
        assert!(serialized.contains("\"startLine\":null"));
        assert!(serialized.contains("\"contentState\":\"STALE\""));
        assert!(request.evidence[0].summary.contains("immutable snapshot"));
    }
}

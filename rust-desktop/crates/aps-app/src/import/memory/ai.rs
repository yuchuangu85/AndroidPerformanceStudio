//! Only published HPROF leak findings enter AI; the heap and source stay local.

use super::ImportWorkspace;
use super::state::MemoryViewMode;
use crate::evidence::object;
use aps_ai::{AnalysisRequest, Evidence};
use aps_contracts::SourceDigest;
use aps_json::{Limits, Value};
use aps_memory::{ActivityLeak, LeakSuspect, LifecycleLeakObject, MemorySnapshot, ObjectReference};
use aps_ui_foundation::TextKey;
use gpui_kit::EventEmitter;

impl EventEmitter<crate::evidence::AnalysisRequested> for ImportWorkspace {}

const MAX_ROWS_PER_KIND: usize = 32;
const MAX_CHAIN_NODES: usize = 16;
const MAX_EVIDENCE_BYTES: u64 = 16 * 1024;
const MAX_ROW_BYTES: u64 = 4 * 1024;
const SCOPE: &str = "HPROF heuristic leak candidates only; no source or raw heap. Counts overlap; retained sizes describe representative objects, not class totals. Missing chains and omitted findings remain unknown.";

fn limits(max_bytes: u64) -> Limits {
    Limits {
        max_bytes,
        max_depth: 16,
        max_nodes: 20_000,
        max_string_bytes: 2_048,
        max_collection_items: 512,
    }
}

impl ImportWorkspace {
    pub(super) fn memory_ai_available(&self) -> bool {
        if self.memory_state.loading || self.session_id.is_none() {
            return false;
        }
        if self.memory_state.view_mode == MemoryViewMode::Diff {
            return self.memory_current_comparison().is_ok();
        }
        if self.memory_state.view_mode == MemoryViewMode::NativeHeap {
            return self.memory_advanced.native_phase3_input().is_some();
        }
        if matches!(
            self.memory_state.view_mode,
            MemoryViewMode::SampledArt | MemoryViewMode::BitmapDump
        ) {
            return false;
        }
        self.memory_state.snapshot.as_ref().is_some_and(|snapshot| {
            !snapshot.findings.leak_suspects.rows.is_empty()
                || !snapshot.findings.activities.rows.is_empty()
                || !snapshot.findings.lifecycle.rows.is_empty()
        })
    }

    pub(super) fn memory_leak_analysis_request(&self) -> Result<AnalysisRequest, String> {
        if matches!(
            self.memory_state.view_mode,
            MemoryViewMode::SampledArt | MemoryViewMode::BitmapDump
        ) {
            return Err(self.language.text(TextKey::MemoryLiveAiUnavailable).into());
        }
        if self.memory_state.loading {
            return Err(self.language.text(TextKey::MemoryAiNotReady).into());
        }
        if self.memory_state.view_mode == MemoryViewMode::Diff {
            let report = self.memory_current_comparison()?;
            let session = self.session_id.as_deref().ok_or_else(|| {
                self.language
                    .text(TextKey::ImportUiImportBeforeAi)
                    .to_owned()
            })?;
            return build_comparison_request(session, self.generation, &report).map_err(|error| {
                self.language
                    .format(TextKey::MemoryDiffAiEvidenceFailed, &[("error", &error)])
            });
        }
        if self.memory_state.view_mode == MemoryViewMode::NativeHeap {
            if self.memory_advanced.native_phase3_input().is_none() {
                return Err(self.language.text(TextKey::MemoryLiveAiUnavailable).into());
            }
            return self.memory_native_analysis_request();
        }
        let snapshot = self.memory_state.snapshot.as_ref().ok_or_else(|| {
            self.language
                .text(TextKey::ImportUiImportBeforeAi)
                .to_owned()
        })?;
        let session = self.session_id.as_ref().ok_or_else(|| {
            self.language
                .text(TextKey::ImportUiImportBeforeAi)
                .to_owned()
        })?;
        let mut request = build_request(session, self.generation, snapshot)
            .map_err(|key| self.language.text(key).to_owned())?;
        if let Some(evidence) = super::phase3::build_lifecycle_rule_evidence(snapshot)
            .map_err(|error| error.to_string())?
        {
            request.evidence.push(evidence);
            request.scope =
                "HPROF deterministic lifecycle retention rule; not proof of a leak".into();
        }
        request.mcp = self.memory_mcp_context(snapshot);
        Ok(request)
    }

    fn memory_mcp_context(&self, snapshot: &MemorySnapshot) -> Option<aps_ai::McpContext> {
        let app = std::env::current_exe().ok()?;
        build_mcp_context(self.path.as_ref()?, &app, snapshot)
    }

    fn memory_native_analysis_request(&self) -> Result<AnalysisRequest, String> {
        let session = self.session_id.as_ref().ok_or_else(|| {
            self.language
                .text(TextKey::ImportUiImportBeforeAi)
                .to_owned()
        })?;
        let Some((digest, page, coverage)) = self.memory_advanced.native_phase3_input() else {
            return Err(self
                .language
                .text(TextKey::ImportUiImportBeforeAi)
                .to_owned());
        };
        let rows = page
            .rows
            .iter()
            .take(32)
            .map(|row| super::phase3::NativeHeapEvidenceRow {
                leaf_frame: row.leaf_frame.clone(),
                sample_rows: row.sample_rows,
                allocated_bytes: row.allocated_bytes,
                net_bytes: row.net_bytes,
                coverage: super::phase3::NativeHeapRowCoverage::Sampled,
            })
            .collect::<Vec<_>>();
        let evidence = super::phase3::build_native_heap_evidence(
            aps_perfetto::heap_profile::HeapProfileKind::Native,
            &digest,
            &rows,
            &coverage,
        )
        .map_err(|error| error.to_string())?;
        Ok(AnalysisRequest {
            session_id: session.clone(),
            run_id: format!("{session}-memory-native-g{}", self.generation.max(1)),
            generation: self.generation.max(1),
            profiler: "memory".into(),
            scope: "Sampled native heapprofd evidence; separate from Java HPROF retention".into(),
            evidence: vec![evidence],
            candidates: Vec::new(),
            user_question: None,
            mcp: None,
        })
    }

    pub(crate) fn memory_ai_binding(&self) -> Option<(String, u64, String)> {
        Some((
            self.session_id.clone()?,
            self.generation.max(1),
            self.memory_state.snapshot.as_ref()?.source_digest.clone(),
        ))
    }
}

fn build_mcp_context(
    hprof: &std::path::Path,
    app: &std::path::Path,
    snapshot: &MemorySnapshot,
) -> Option<aps_ai::McpContext> {
    // The MCP server currently reopens without mapping. Do not combine its raw
    // class names with a snapshot published by a mapped worker session.
    if snapshot.mapping_digest.is_some() {
        return None;
    }
    let digest = SourceDigest::from_hex(&snapshot.source_digest).ok()?;
    let hprof = hprof.canonicalize().ok()?;
    let worker = app.with_file_name(if cfg!(target_os = "windows") {
        "aps-memory-worker.exe"
    } else {
        "aps-memory-worker"
    });
    let command = app.with_file_name(if cfg!(target_os = "windows") {
        "aps-mcp.exe"
    } else {
        "aps-mcp"
    });
    (worker.is_file() && command.is_file()).then(|| aps_ai::McpContext {
        command,
        arguments: vec![
            "--hprof".into(),
            hprof.to_string_lossy().into_owned(),
            "--worker".into(),
            worker.to_string_lossy().into_owned(),
        ],
        source_digest: digest.to_hex(),
    })
}

fn build_comparison_request(
    session: &str,
    generation: u64,
    report: &aps_memory::SnapshotComparison,
) -> Result<AnalysisRequest, String> {
    let evidence = super::phase3::build_comparison_growth_evidence(report)?;
    Ok(AnalysisRequest {
        session_id: session.into(),
        run_id: format!("{session}-memory-diff-g{}", generation.max(1)),
        generation: generation.max(1),
        profiler: "memory".into(),
        scope: "Published A/B HPROF class growth only; mapping identities matched; build identity unverified; no cross-dump object identity".into(),
        evidence,
        candidates: Vec::new(),
        user_question: None,
        mcp: None,
    })
}

fn coverage(snapshot: &MemorySnapshot, included: [usize; 3]) -> Value {
    let findings = &snapshot.findings;
    let page = |total: u64, loaded: usize, included: usize| {
        object([
            ("total", total.into()),
            ("loaded", (loaded as u64).into()),
            ("included", (included as u64).into()),
            ("omitted", (total - included as u64).into()),
            ("truncated", (total > included as u64).into()),
        ])
    };
    object([
        ("policyVersion", "aps-memory-leaks-v1".into()),
        ("sourceDigest", snapshot.source_digest.clone().into()),
        ("heuristicOnly", true.into()),
        ("objectIdentityScope", "single-dump".into()),
        ("sizeUnit", "bytes".into()),
        (
            "parserWarningCount",
            (snapshot.metadata.warnings.len() as u64).into(),
        ),
        (
            "leakSuspects",
            page(
                findings.leak_suspects.total,
                findings.leak_suspects.rows.len(),
                included[0],
            ),
        ),
        (
            "activityCandidates",
            page(
                findings.activities.total,
                findings.activities.rows.len(),
                included[1],
            ),
        ),
        (
            "lifecycleCandidates",
            page(
                findings.lifecycle.total,
                findings.lifecycle.rows.len(),
                included[2],
            ),
        ),
    ])
}

fn reference(row: &ObjectReference) -> Value {
    object([
        // Hex strings preserve all 64 bits through provider JSON parsers.
        ("objectId", format!("0x{:x}", row.object_id).into()),
        ("className", row.class_name.clone().into()),
        (
            "field",
            row.field_name.clone().map_or(Value::Null, Value::from),
        ),
        (
            "gcRootKind",
            row.root_kind.clone().map_or(Value::Null, Value::from),
        ),
    ])
}

fn chain(rows: &[ObjectReference]) -> Value {
    let included = rows.len().min(MAX_CHAIN_NODES);
    let prefix = if rows.len() > MAX_CHAIN_NODES {
        MAX_CHAIN_NODES / 2
    } else {
        included
    };
    object([
        ("totalNodes", (rows.len() as u64).into()),
        ("omittedNodes", ((rows.len() - included) as u64).into()),
        (
            "rootPathPrefix",
            Value::Array(rows[..prefix].iter().map(reference).collect()),
        ),
        // The suffix is NOT adjacent to the prefix when nodes were omitted.
        (
            "targetPathSuffix",
            Value::Array(
                rows[rows.len() - (included - prefix)..]
                    .iter()
                    .map(reference)
                    .collect(),
            ),
        ),
    ])
}

fn suspect(row: &LeakSuspect) -> Value {
    object([
        ("className", row.class_name.clone().into()),
        ("reason", row.reason.clone().into()),
        ("instanceCount", row.instance_count.into()),
        (
            "retainedSizeBytes",
            row.retained_size.map_or(Value::Null, Value::from),
        ),
        ("lifecycleCandidate", row.activity_or_fragment_leak.into()),
        ("referenceChain", chain(&row.reference_chain)),
    ])
}

fn activity(row: &ActivityLeak) -> Value {
    object([
        ("className", row.class_name.clone().into()),
        ("liveInstanceCount", row.live_instance_count.into()),
        (
            "destroyedInstanceCount",
            row.destroyed_instance_count.into(),
        ),
        (
            "retainedSizeBytes",
            row.retained_size.map_or(Value::Null, Value::from),
        ),
        ("referenceChain", chain(&row.reference_chain)),
    ])
}

fn lifecycle(row: &LifecycleLeakObject) -> Value {
    object([
        ("objectId", format!("0x{:x}", row.object_id).into()),
        ("className", row.class_name.clone().into()),
        (
            "heapName",
            row.heap_name.clone().map_or(Value::Null, Value::from),
        ),
    ])
}

fn evidence(
    snapshot: &MemorySnapshot,
    kind: &str,
    summary: &str,
    payload: Value,
) -> Option<Evidence> {
    let bytes = aps_json::to_vec(&payload, limits(MAX_ROW_BYTES)).ok()?;
    let mut identity = snapshot.source_digest.as_bytes().to_vec();
    identity.push(0);
    identity.extend_from_slice(kind.as_bytes());
    identity.push(0);
    identity.extend_from_slice(&bytes);
    let digest = SourceDigest::hash_reader(identity.as_slice(), MAX_ROW_BYTES + 256).ok()?;
    let row = Evidence {
        id: format!("{kind}:{}", digest.to_hex()),
        kind: kind.into(),
        summary: summary.into(),
        payload,
    };
    aps_json::to_vec(&evidence_value(&row), limits(MAX_ROW_BYTES)).ok()?;
    Some(row)
}

fn evidence_value(row: &Evidence) -> Value {
    object([
        ("id", row.id.clone().into()),
        ("kind", row.kind.clone().into()),
        ("summary", row.summary.clone().into()),
        ("payload", row.payload.clone()),
    ])
}

fn evidence_fits(evidence: &[Evidence]) -> bool {
    let payload = Value::Array(evidence.iter().map(evidence_value).collect());
    aps_json::to_vec(&payload, limits(MAX_EVIDENCE_BYTES)).is_ok()
}

fn build_request(
    session: &str,
    generation: u64,
    snapshot: &MemorySnapshot,
) -> Result<AnalysisRequest, TextKey> {
    SourceDigest::from_hex(&snapshot.source_digest)
        .map_err(|_| TextKey::MemoryAiInvalidEvidence)?;
    let findings = &snapshot.findings;
    for (total, loaded) in [
        (
            findings.leak_suspects.total,
            findings.leak_suspects.rows.len(),
        ),
        (findings.activities.total, findings.activities.rows.len()),
        (findings.lifecycle.total, findings.lifecycle.rows.len()),
    ] {
        if loaded as u64 > total || loaded > aps_memory::MAX_PAGE_SIZE {
            return Err(TextKey::MemoryAiInvalidEvidence);
        }
    }
    let header = |included| {
        evidence(
            snapshot,
            "memory-hprof-leak-findings",
            "HPROF heuristic coverage; not proof of leaks",
            coverage(snapshot, included),
        )
    };
    let mut evidence = vec![header([0; 3]).ok_or(TextKey::MemoryAiInvalidEvidence)?];
    let mut included = [0usize; 3];
    let mut seen = std::collections::BTreeSet::new();
    // Rotate through kinds so the byte budget does not hide an entire category.
    for index in 0..MAX_ROWS_PER_KIND {
        let rows = [
            findings.leak_suspects.rows.get(index).map(|row| {
                (
                    "memory-leak-suspect",
                    "Heuristic retention candidate",
                    suspect(row),
                )
            }),
            findings.activities.rows.get(index).map(|row| {
                (
                    "memory-activity-candidate",
                    "Activity class; size and path are for one representative",
                    activity(row),
                )
            }),
            findings.lifecycle.rows.get(index).map(|row| {
                (
                    "memory-lifecycle-candidate",
                    "Snapshot-local Activity/Fragment lifecycle candidate",
                    lifecycle(row),
                )
            }),
        ];
        for (kind_index, row) in rows.into_iter().enumerate() {
            let Some((kind, summary, payload)) = row else {
                continue;
            };
            let Some(row) = self::evidence(snapshot, kind, summary, payload) else {
                continue;
            };
            if seen.contains(&row.id) {
                continue;
            }
            included[kind_index] += 1;
            evidence[0] = header(included).ok_or(TextKey::MemoryAiInvalidEvidence)?;
            evidence.push(row);
            if evidence_fits(&evidence) {
                seen.insert(evidence.last().unwrap().id.clone());
            } else {
                evidence.pop();
                included[kind_index] -= 1;
                evidence[0] = header(included).ok_or(TextKey::MemoryAiInvalidEvidence)?;
            }
        }
    }
    if evidence.len() == 1 {
        return Err(
            if findings.leak_suspects.total == 0
                && findings.activities.total == 0
                && findings.lifecycle.total == 0
            {
                TextKey::MemoryAiNoFindings
            } else {
                TextKey::MemoryAiInvalidEvidence
            },
        );
    }
    Ok(AnalysisRequest {
        session_id: session.into(),
        run_id: format!("{session}-memory-leaks-g{}", generation.max(1)),
        generation: generation.max(1),
        profiler: "memory".into(),
        scope: SCOPE.into(),
        evidence,
        candidates: Vec::new(),
        user_question: None,
        mcp: None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_memory::{BitmapInstance, ClassStats, FindingsPage, FindingsSnapshot};
    use aps_ui_foundation::Destination;

    fn fixture() -> MemorySnapshot {
        MemorySnapshot {
            source_digest: "a".repeat(64),
            findings: FindingsSnapshot {
                leak_suspects: FindingsPage {
                    total: 1,
                    rows: vec![LeakSuspect {
                        class_name: "sample.Screen".into(),
                        reason: "Static field retains a Context".into(),
                        instance_count: 2,
                        retained_size: Some(4096),
                        reference_chain: vec![
                            ObjectReference {
                                object_id: u64::MAX,
                                class_name: "sample.Holder".into(),
                                root_kind: Some("JNI Global".into()),
                                ..ObjectReference::default()
                            },
                            ObjectReference {
                                object_id: 42,
                                class_name: "sample.Screen".into(),
                                field_name: Some("activity".into()),
                                ..ObjectReference::default()
                            },
                        ],
                        ..LeakSuspect::default()
                    }],
                },
                ..FindingsSnapshot::default()
            },
            ..MemorySnapshot::default()
        }
    }

    #[test]
    fn only_leak_findings_leave_memory_with_exact_values() {
        let mut snapshot = fixture();
        snapshot.classes.push(ClassStats {
            class_name: "PRIVATE_HISTOGRAM".into(),
            ..ClassStats::default()
        });
        snapshot.findings.bitmaps.rows.push(BitmapInstance {
            class_name: "PRIVATE_BITMAP".into(),
            ..BitmapInstance::default()
        });
        snapshot
            .metadata
            .warnings
            .push("PRIVATE_RAW_WARNING".into());
        let request = build_request("session", 7, &snapshot).unwrap();
        assert!(request.candidates.is_empty());
        assert_eq!(request.evidence.len(), 2);
        assert_eq!(request.evidence[0].payload["parserWarningCount"], 1);
        let row = &request.evidence[1].payload;
        assert_eq!(row["className"], "sample.Screen");
        assert_eq!(
            row["reason"],
            snapshot.findings.leak_suspects.rows[0].reason
        );
        assert_eq!(row["retainedSizeBytes"], 4096);
        assert_eq!(row["instanceCount"], 2);
        assert_eq!(
            row["referenceChain"]["rootPathPrefix"][0]["objectId"],
            "0xffffffffffffffff"
        );
        assert_eq!(
            row["referenceChain"]["rootPathPrefix"][0]["gcRootKind"],
            "JNI Global"
        );
        assert_eq!(
            row["referenceChain"]["rootPathPrefix"][1]["field"],
            "activity"
        );
        let text = format!(
            "{:?}",
            request
                .evidence
                .iter()
                .map(|e| &e.payload)
                .collect::<Vec<_>>()
        );
        for excluded in [
            "PRIVATE_",
            "objectCount",
            "shallowSize",
            "bitmapInstances",
            "sourceSnippet",
        ] {
            assert!(!text.contains(excluded));
        }
        assert!(evidence_fits(&request.evidence));
    }

    #[test]
    fn all_three_kinds_preserve_unknowns_and_do_not_add_retained_sizes() {
        let mut snapshot = fixture();
        snapshot.findings.activities = FindingsPage {
            total: 1,
            rows: vec![ActivityLeak {
                class_name: "sample.Screen".into(),
                live_instance_count: 3,
                destroyed_instance_count: 2,
                retained_size: None,
                ..ActivityLeak::default()
            }],
        };
        snapshot.findings.lifecycle = FindingsPage {
            total: 1,
            rows: vec![LifecycleLeakObject {
                object_id: u64::MAX,
                class_name: "sample.Screen".into(),
                heap_name: None,
            }],
        };
        let request = build_request("session", 1, &snapshot).unwrap();
        assert_eq!(request.evidence.len(), 4);
        assert!(request.evidence[2].payload["retainedSizeBytes"].is_null());
        assert_eq!(request.evidence[2].payload["liveInstanceCount"], 3);
        assert_eq!(request.evidence[2].payload["destroyedInstanceCount"], 2);
        assert_eq!(
            request.evidence[3].payload["objectId"],
            "0xffffffffffffffff"
        );
        assert!(
            request.evidence[3]
                .payload
                .get("retainedSizeBytes")
                .is_none()
        );
    }

    #[test]
    fn identities_bind_mapping_results_and_snapshot_not_just_dump_hash() {
        let mut snapshot = fixture();
        let first = build_request("session", 1, &snapshot).unwrap();
        snapshot.findings.leak_suspects.rows[0].class_name = "deobfuscated.Screen".into();
        let mapped = build_request("session", 1, &snapshot).unwrap();
        assert_ne!(first.evidence[1].id, mapped.evidence[1].id);
        snapshot.source_digest = "b".repeat(64);
        let other = build_request("session", 1, &snapshot).unwrap();
        assert_ne!(mapped.evidence[1].id, other.evidence[1].id);
        assert_eq!(
            other.evidence[1].id,
            build_request("session", 1, &snapshot).unwrap().evidence[1].id
        );
    }

    #[test]
    fn mcp_context_requires_an_unmapped_snapshot_and_available_companions() {
        let workspace = aps_platform::TempWorkspace::create(&std::env::temp_dir()).unwrap();
        let app = workspace.path().join("aps-app");
        let hprof = workspace.path().join("snapshot.hprof");
        let worker = workspace.path().join(if cfg!(target_os = "windows") {
            "aps-memory-worker.exe"
        } else {
            "aps-memory-worker"
        });
        let server = workspace.path().join(if cfg!(target_os = "windows") {
            "aps-mcp.exe"
        } else {
            "aps-mcp"
        });
        std::fs::write(&hprof, b"local test input; never executed").unwrap();
        std::fs::write(&worker, b"").unwrap();
        let mut snapshot = fixture();
        assert!(build_mcp_context(&hprof, &app, &snapshot).is_none());
        std::fs::write(&server, b"").unwrap();
        let context = build_mcp_context(&hprof, &app, &snapshot).unwrap();
        assert_eq!(context.source_digest, snapshot.source_digest);
        assert_eq!(
            context.arguments[1],
            hprof.canonicalize().unwrap().to_string_lossy()
        );
        snapshot.mapping_digest = Some("c".repeat(64));
        assert!(build_mcp_context(&hprof, &app, &snapshot).is_none());
        snapshot.mapping_digest = None;
        snapshot.source_digest = "invalid".into();
        assert!(build_mcp_context(&hprof, &app, &snapshot).is_none());
    }

    #[test]
    fn frozen_provider_body_has_only_current_leak_evidence_and_no_source_scope() {
        let request = build_request("session", 1, &fixture()).unwrap();
        let provider = aps_ai::ProviderConfig::new(
            "test",
            "http://127.0.0.1:12345/v1",
            "test-model",
            aps_ai::CredentialReference::new("keychain:test").unwrap(),
        )
        .unwrap();
        let prepared = aps_ai::PreparedRun::new(request, provider, false).unwrap();
        assert_eq!(prepared.source_candidates().len(), 0);
        let body: Value = serde_json::from_str(prepared.review_body()).unwrap();
        let payload: Value =
            serde_json::from_str(body["messages"][1]["content"].as_str().unwrap()).unwrap();
        assert!(payload["sourceCandidates"].as_array().unwrap().is_empty());
        let evidence = payload["performanceEvidence"].as_array().unwrap();
        assert_eq!(evidence.len(), 2);
        assert_eq!(evidence[0]["kind"], "memory-hprof-leak-findings");
        assert_eq!(evidence[1]["kind"], "memory-leak-suspect");
        assert_eq!(evidence[1]["payload"]["retainedSizeBytes"], 4096);
        assert_eq!(
            evidence[1]["payload"]["referenceChain"]["rootPathPrefix"][1]["field"],
            "activity"
        );
    }

    #[test]
    fn overlarge_rows_are_omitted_without_clipping_and_other_kinds_survive() {
        let mut snapshot = fixture();
        snapshot.findings.leak_suspects.rows[0].reason = "x".repeat(10_000);
        snapshot.findings.lifecycle = FindingsPage {
            total: 1,
            rows: vec![LifecycleLeakObject {
                object_id: 42,
                class_name: "sample.Screen".into(),
                heap_name: None,
            }],
        };
        let request = build_request("session", 1, &snapshot).unwrap();
        assert_eq!(request.evidence.len(), 2);
        assert_eq!(request.evidence[1].kind, "memory-lifecycle-candidate");
        let suspects = &request.evidence[0].payload["leakSuspects"];
        assert_eq!(suspects["included"], 0);
        assert_eq!(suspects["omitted"], 1);
        assert_eq!(suspects["truncated"], true);
    }

    #[test]
    fn per_row_budget_includes_the_entire_transmitted_envelope() {
        let payload = object([
            ("first", "x".repeat(2000).into()),
            ("second", "y".repeat(2000).into()),
        ]);
        assert!(aps_json::to_vec(&payload, limits(MAX_ROW_BYTES)).is_ok());
        assert!(
            evidence(
                &fixture(),
                "memory-leak-suspect",
                "Heuristic retention candidate",
                payload
            )
            .is_none()
        );
        let request = build_request("session", 1, &fixture()).unwrap();
        for row in &request.evidence {
            assert!(aps_json::to_vec(&evidence_value(row), limits(MAX_ROW_BYTES)).is_ok());
        }
    }

    #[test]
    fn bounded_rows_keep_full_source_totals_and_gapped_chains() {
        let mut snapshot = fixture();
        let template = snapshot.findings.leak_suspects.rows[0].clone();
        snapshot.findings.leak_suspects = FindingsPage {
            total: 500,
            rows: (0..200)
                .map(|i| LeakSuspect {
                    class_name: format!("Screen{i}"),
                    ..template.clone()
                })
                .collect(),
        };
        let request = build_request("session", 1, &snapshot).unwrap();
        let count = request.evidence.len() - 1;
        assert!(count > 0 && count <= MAX_ROWS_PER_KIND);
        let coverage = &request.evidence[0].payload["leakSuspects"];
        assert_eq!(coverage["total"], 500);
        assert_eq!(coverage["loaded"], 200);
        assert_eq!(coverage["included"], count);
        assert_eq!(coverage["omitted"], 500 - count);
        assert!(evidence_fits(&request.evidence));
        let references: Vec<_> = (0..40)
            .map(|i| ObjectReference {
                object_id: i,
                ..ObjectReference::default()
            })
            .collect();
        let chain = chain(&references);
        assert_eq!(chain["totalNodes"], 40);
        assert_eq!(chain["omittedNodes"], 24);
        assert_eq!(chain["rootPathPrefix"][7]["objectId"], "0x7");
        assert_eq!(chain["targetPathSuffix"][0]["objectId"], "0x20");
    }

    #[test]
    fn empty_inconsistent_invalid_digest_and_overlarge_evidence_fail_closed() {
        let mut snapshot = fixture();
        snapshot.findings.leak_suspects.rows.clear();
        snapshot.findings.leak_suspects.total = 0;
        assert!(matches!(
            build_request("session", 1, &snapshot),
            Err(TextKey::MemoryAiNoFindings)
        ));
        snapshot = fixture();
        snapshot.findings.leak_suspects.total = 0;
        assert!(matches!(
            build_request("session", 1, &snapshot),
            Err(TextKey::MemoryAiInvalidEvidence)
        ));
        snapshot = fixture();
        snapshot.source_digest = "not-a-digest".into();
        assert!(build_request("session", 1, &snapshot).is_err());
        snapshot = fixture();
        snapshot.findings.leak_suspects.rows[0].reason = "x".repeat(10_000);
        assert!(matches!(
            build_request("session", 1, &snapshot),
            Err(TextKey::MemoryAiInvalidEvidence)
        ));
    }

    #[test]
    fn workspace_readiness_does_not_send_an_old_or_unrelated_snapshot() {
        use aps_ui_foundation::Language;
        let mut workspace = ImportWorkspace::new(Destination::Memory, Language::English);
        assert!(!workspace.memory_ai_available());
        workspace.session_id = Some("session".into());
        workspace.memory_state.snapshot = Some(std::sync::Arc::new(fixture()));
        for view in [
            MemoryViewMode::Classes,
            MemoryViewMode::Dashboard,
            MemoryViewMode::Dominators,
            MemoryViewMode::LeakAnalysis,
        ] {
            workspace.memory_state.view_mode = view;
            assert!(workspace.memory_ai_available());
            assert!(workspace.memory_analysis_request().is_ok());
        }
        workspace.memory_state.loading = true;
        assert!(!workspace.memory_ai_available());
        assert!(workspace.memory_analysis_request().is_err());
        workspace.memory_state.loading = false;
        for view in [
            MemoryViewMode::NativeHeap,
            MemoryViewMode::SampledArt,
            MemoryViewMode::BitmapDump,
        ] {
            workspace.memory_state.view_mode = view;
            assert!(!workspace.memory_ai_available());
            assert!(workspace.memory_analysis_request().is_err());
        }
    }

    #[test]
    fn diff_requires_a_published_comparison_even_when_active_snapshot_has_findings() {
        use aps_ui_foundation::Language;
        let mut workspace =
            ImportWorkspace::new(aps_ui_foundation::Destination::Memory, Language::English);
        workspace.session_id = Some("active-C".into());
        workspace.memory_state.snapshot = Some(std::sync::Arc::new(fixture()));
        workspace.memory_state.view_mode = MemoryViewMode::Diff;
        for pair in [None, Some((1, 2)), Some((1, 1))] {
            workspace.memory_state.comparison_pair = pair;
            assert!(!workspace.memory_ai_available());
            assert!(workspace.memory_analysis_request().is_err());
        }
    }

    fn diff_report() -> aps_memory::SnapshotComparison {
        let mut before = fixture();
        before.source_digest = "1".repeat(64);
        before.metadata.class_count = 1;
        before.classes = vec![aps_memory::ClassStats {
            class_name: "example.DiffOnly".into(),
            instance_count: 1,
            shallow_size: 8,
            retained_size: Some(16),
            ..aps_memory::ClassStats::default()
        }];
        let mut after = before.clone();
        after.source_digest = "2".repeat(64);
        after.classes[0].instance_count = 3;
        after.classes[0].shallow_size = 24;
        after.classes[0].retained_size = Some(48);
        aps_memory::compare_snapshots(&before, &after, "A.hprof".into(), "B.hprof".into()).unwrap()
    }

    #[test]
    fn diff_provider_body_uses_only_published_pair_and_does_not_enrich_active_c() {
        let report = diff_report();
        let request = build_comparison_request("active-C", 5, &report).unwrap();
        assert!(request.mcp.is_none());
        assert!(!request.is_memory_hprof());
        assert_eq!(request.memory_phase3_mode(), Some("memory-hprof-growth"));
        assert_eq!(request.evidence.len(), 1);
        assert_eq!(
            request.evidence[0].payload["beforeSnapshotId"],
            report.a_digest
        );
        assert_eq!(
            request.evidence[0].payload["afterSnapshotId"],
            report.b_digest
        );
        let provider = aps_ai::ProviderConfig::new(
            "test",
            "http://127.0.0.1:12345/v1",
            "test-model",
            aps_ai::CredentialReference::new("keychain:test").unwrap(),
        )
        .unwrap();
        let prepared = aps_ai::PreparedRun::new(request, provider, false).unwrap();
        let body: Value = serde_json::from_str(prepared.review_body()).unwrap();
        let payload: Value =
            serde_json::from_str(body["messages"][1]["content"].as_str().unwrap()).unwrap();
        let evidence = payload["performanceEvidence"].as_array().unwrap();
        assert_eq!(evidence.len(), 1);
        assert_eq!(evidence[0]["kind"], "memory-hprof-growth");
        assert_eq!(
            evidence[0]["payload"]["classDeltas"][0]["className"],
            "example.DiffOnly"
        );
        assert_eq!(
            evidence[0]["payload"]["classDeltas"][0]["instanceCountDelta"],
            2
        );
        assert!(payload["sourceCandidates"].as_array().unwrap().is_empty());
    }

    #[test]
    fn diff_evidence_errors_propagate_without_a_leak_only_fallback() {
        let mut report = diff_report();
        report.a_digest = report.b_digest.clone();
        assert!(build_comparison_request("session", 1, &report).is_err());
        let mut report = diff_report();
        report.b_mapping_digest = Some("a".repeat(64));
        assert!(build_comparison_request("session", 1, &report).is_err());
        let mut report = diff_report();
        report.rows[0].class_name = "x".repeat(2049);
        assert!(build_comparison_request("session", 1, &report).is_err());
    }

    #[test]
    fn source_lookup_identity_tracks_session_generation_and_hprof_digest() {
        use aps_ui_foundation::Language;
        let mut workspace = ImportWorkspace::new(Destination::Memory, Language::English);
        assert!(workspace.memory_ai_binding().is_none());
        workspace.session_id = Some("session".into());
        workspace.memory_state.snapshot = Some(std::sync::Arc::new(fixture()));
        let request = workspace.memory_analysis_request().unwrap();
        let first = workspace.memory_ai_binding().unwrap();
        assert_eq!(
            first,
            (request.session_id, request.generation, "a".repeat(64))
        );
        workspace.generation += 2;
        assert_ne!(workspace.memory_ai_binding().unwrap(), first);
        workspace.generation = first.1;
        workspace.session_id = Some("other-session".into());
        assert_ne!(workspace.memory_ai_binding().unwrap(), first);
        workspace.session_id = Some(first.0.clone());
        let mut other = fixture();
        other.source_digest = "b".repeat(64);
        workspace.memory_state.snapshot = Some(std::sync::Arc::new(other));
        assert_ne!(workspace.memory_ai_binding().unwrap(), first);
    }
}

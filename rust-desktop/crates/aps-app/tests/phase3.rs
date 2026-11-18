#[path = "../src/import/memory/phase3.rs"]
mod phase3;

use aps_json::Value;
use aps_memory::{
    ActivityLeak, ClassStats, FindingsPage, LeakSuspect, LifecycleLeakObject, MemorySnapshot,
};
use aps_perfetto::heap_profile::HeapProfileKind;

fn digest(byte: u8) -> String {
    format!("{:02x}", byte).repeat(32)
}
fn snapshot(source_digest: &str) -> MemorySnapshot {
    MemorySnapshot {
        source_digest: source_digest.into(),
        ..MemorySnapshot::default()
    }
}
fn class(name: &str, count: u64, retained: Option<u64>) -> ClassStats {
    ClassStats {
        class_name: name.into(),
        instance_count: count,
        retained_size: retained,
        ..ClassStats::default()
    }
}
fn payload_value<'a>(evidence: &'a aps_ai::Evidence, key: &str) -> &'a Value {
    &evidence.payload[key]
}

#[test]
fn growth_reports_count_retained_delta_and_unknowns() {
    let mut before = snapshot(&digest(0x11));
    before.metadata.class_count = 2;
    before.classes = vec![class("A", 2, Some(100)), class("Unknown", 1, None)];
    let mut after = snapshot(&digest(0x22));
    after.metadata.class_count = 3;
    after.classes = vec![
        class("A", 5, Some(260)),
        class("Unknown", 4, None),
        class("New", 1, Some(7)),
    ];
    let evidence = phase3::build_growth_evidence(&before, &after, "before", "after")
        .unwrap()
        .remove(0);
    assert_eq!(evidence.kind, "memory-hprof-growth");
    let rows = evidence.payload["classDeltas"].as_array().unwrap();
    let a = rows.iter().find(|row| row["className"] == "A").unwrap();
    assert_eq!(a["instanceCountDelta"], 3);
    assert_eq!(a["retainedBytesDelta"], 160);
    let unknown = rows
        .iter()
        .find(|row| row["className"] == "Unknown")
        .unwrap();
    assert!(unknown["retainedDeltaBytes"].is_null());
    assert!(unknown["retainedBytesDelta"].is_null());
    assert_eq!(evidence.payload["coverage"]["total"], 3);
}
#[test]
fn growth_rejects_invalid_or_same_digest() {
    let mut before = snapshot(&digest(0x11));
    before.metadata.class_count = 0;
    let after = snapshot(&digest(0x11));
    assert!(phase3::build_growth_evidence(&before, &after, "before", "after").is_err());
    before.source_digest = "invalid".into();
    let mut after = snapshot(&digest(0x22));
    after.metadata.class_count = 0;
    assert!(phase3::build_growth_evidence(&before, &after, "before", "after").is_err());
}
#[test]
fn lifecycle_rules_report_candidate_and_unknown_statuses() {
    let mut snapshot = snapshot(&digest(0x33));
    snapshot.findings.activities = FindingsPage {
        total: 1,
        rows: vec![ActivityLeak {
            class_name: "ScreenActivity".into(),
            destroyed_instance_count: 2,
            ..ActivityLeak::default()
        }],
    };
    snapshot.findings.lifecycle = FindingsPage {
        total: 1,
        rows: vec![LifecycleLeakObject {
            class_name: "DetailsFragment".into(),
            ..LifecycleLeakObject::default()
        }],
    };
    snapshot.findings.leak_suspects = FindingsPage {
        total: 2,
        rows: vec![LeakSuspect {
            reason: "Static/singleton field Foo.context retains a Context".into(),
            ..LeakSuspect::default()
        }],
    };
    let evidence = phase3::build_lifecycle_rule_evidence(&snapshot)
        .unwrap()
        .unwrap();
    let rules = evidence.payload["rules"].as_array().unwrap();
    assert_eq!(evidence.kind, "memory-lifecycle-rule");
    assert_eq!(rules[0]["status"], "CANDIDATE");
    assert_eq!(rules[1]["status"], "CANDIDATE");
    assert_eq!(rules[2]["status"], "CANDIDATE");
    assert_eq!(rules[3]["status"], "UNKNOWN");
    assert_eq!(rules[0]["count"], 1);
    assert!(
        rules[0]["evidenceIds"].as_array().unwrap()[0]
            .as_str()
            .unwrap()
            .contains("memory-activity-candidate")
    );
}
#[test]
fn shark_unavailable_has_no_chain_and_chain_is_bounded() {
    let unavailable = phase3::build_external_shark_evidence(&phase3::ExternalSharkEvidenceInput {
        status: phase3::SharkStatus::Unavailable,
        evidence_digest: digest(0x44),
        source_artifact_digest: digest(0x55),
        chain: Vec::new(),
    })
    .unwrap();
    assert_eq!(unavailable.kind, "memory-leakcanary-shark");
    assert_eq!(unavailable.payload["status"], "UNAVAILABLE");
    assert_eq!(unavailable.payload["coverage"]["rawDataAvailable"], false);
    assert!(unavailable.payload["chain"].as_array().unwrap().is_empty());
    assert!(
        unavailable.payload["sourceCandidates"]
            .as_array()
            .unwrap()
            .is_empty()
    );
    let node = phase3::SharkChainNode {
        class_name: "A".into(),
        field_name: Some("field".into()),
        node_kind: "INSTANCE".into(),
    };
    let too_long = phase3::ExternalSharkEvidenceInput {
        status: phase3::SharkStatus::Available,
        evidence_digest: digest(0x44),
        source_artifact_digest: digest(0x55),
        chain: vec![node; 17],
    };
    assert!(phase3::build_external_shark_evidence(&too_long).is_err());
}
#[test]
fn native_evidence_preserves_sampled_semantics_without_java_retained() {
    let evidence = phase3::build_native_heap_evidence(
        HeapProfileKind::Native,
        &digest(0x66),
        &[phase3::NativeHeapEvidenceRow {
            leaf_frame: Some("native_malloc".into()),
            sample_rows: 4,
            allocated_bytes: 4096,
            net_bytes: -1024,
            coverage: phase3::NativeHeapRowCoverage::Sampled,
        }],
        &phase3::NativeHeapEvidenceCoverage {
            rows_loaded: 1,
            rows_included: 1,
            has_more: false,
            sampling_interval_bytes: Some(4096),
        },
    )
    .unwrap();
    assert_eq!(evidence.kind, "memory-native-heap");
    assert_eq!(evidence.payload["heapProfileKind"], "NATIVE");
    assert_eq!(evidence.payload["sampling"]["sampled"], true);
    assert!(evidence.payload.get("retainedSizeBytes").is_none());
    assert!(evidence.payload.get("javaRetainedBytes").is_none());
    assert_eq!(evidence.payload["rows"][0]["netBytes"], -1024);
    assert_eq!(evidence.payload["rows"][0]["coverage"], "SAMPLED");
    assert!(
        phase3::build_native_heap_evidence(
            HeapProfileKind::Art,
            &digest(0x66),
            &[],
            &phase3::NativeHeapEvidenceCoverage {
                rows_loaded: 0,
                rows_included: 0,
                has_more: false,
                sampling_interval_bytes: None
            }
        )
        .is_err()
    );
}

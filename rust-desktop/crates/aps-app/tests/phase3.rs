#[path = "../src/import/memory/phase3.rs"]
mod phase3;

use aps_json::Value;
use aps_memory::{
    ActivityLeak, ClassDelta, ClassStats, FindingsPage, LeakSuspect, LifecycleLeakObject,
    MemorySnapshot, SnapshotComparison, compare_snapshots,
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
fn comparison(rows: Vec<ClassDelta>) -> SnapshotComparison {
    let loaded = rows.len() as u64;
    SnapshotComparison {
        a_label: "before".into(),
        b_label: "after".into(),
        a_digest: digest(0x11),
        b_digest: digest(0x22),
        a_mapping_digest: None,
        b_mapping_digest: None,
        a_class_count: loaded,
        b_class_count: loaded,
        a_loaded_classes: loaded,
        b_loaded_classes: loaded,
        rows,
    }
}
fn comparison_row(name: &str) -> ClassDelta {
    ClassDelta {
        class_name: name.into(),
        a_count: 1,
        b_count: 2,
        a_shallow: 10,
        b_shallow: 20,
        a_retained: Some(100),
        b_retained: Some(200),
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
    assert_eq!(evidence.payload["policyVersion"], "aps-memory-growth-v1");
    assert_eq!(evidence.payload["beforeSnapshotId"], before.source_digest);
    assert_eq!(evidence.payload["afterSnapshotId"], after.source_digest);
    assert!(evidence.id.len() <= 128);
    let rows = evidence.payload["classDeltas"].as_array().unwrap();
    let a = rows.iter().find(|row| row["className"] == "A").unwrap();
    assert_eq!(a["instanceCountDelta"], 3);
    assert_eq!(a["retainedBytesDelta"], 160);
    let unknown = rows
        .iter()
        .find(|row| row["className"] == "Unknown")
        .unwrap();
    assert!(unknown.get("retainedDeltaBytes").is_none());
    assert!(unknown["retainedBytesDelta"].is_null());
    assert_eq!(evidence.payload["coverage"]["total"], 3);
    let report = compare_snapshots(&before, &after, "before".into(), "after".into()).unwrap();
    let frozen = phase3::build_comparison_growth_evidence(&report)
        .unwrap()
        .remove(0);
    assert_eq!(evidence.payload, frozen.payload);
    assert_eq!(evidence.id, frozen.id);
    for (published, row) in rows.iter().zip(&report.rows) {
        assert_eq!(
            published["className"].as_str(),
            Some(row.class_name.as_str())
        );
        assert_eq!(published.as_object().unwrap().len(), 3);
    }
    assert_eq!(evidence.payload.as_object().unwrap().len(), 5);
}

#[test]
fn growth_preserves_frozen_row_order_and_signed_nullable_deltas() {
    let mut z = comparison_row("Z");
    z.a_count = 8;
    z.b_count = 3;
    z.a_retained = None;
    let mut a = comparison_row("A");
    a.a_retained = Some(200);
    a.b_retained = Some(100);
    let mut m = comparison_row("M");
    m.a_retained = Some(0);
    m.b_retained = Some(999);
    let report = comparison(vec![z, a, m]);
    let frozen = report.clone();
    let evidence = phase3::build_comparison_growth_evidence(&report)
        .unwrap()
        .remove(0);
    let rows = evidence.payload["classDeltas"].as_array().unwrap();
    assert_eq!(rows.len(), 3);
    assert_eq!(rows[0]["className"], "Z");
    assert_eq!(rows[0]["instanceCountDelta"], -5);
    assert!(rows[0]["retainedBytesDelta"].is_null());
    assert_eq!(rows[1]["className"], "A");
    assert_eq!(rows[1]["instanceCountDelta"], 1);
    assert_eq!(rows[1]["retainedBytesDelta"], -100);
    assert_eq!(rows[2]["className"], "M");
    assert_eq!(rows[2]["retainedBytesDelta"], 999);
    assert_eq!(report, frozen);
}

#[test]
fn growth_keeps_first_32_frozen_rows_with_explicit_coverage() {
    let report = comparison(
        (0..40)
            .rev()
            .map(|index| comparison_row(&format!("Class{index:02}")))
            .collect(),
    );
    let evidence = phase3::build_comparison_growth_evidence(&report)
        .unwrap()
        .remove(0);
    let rows = evidence.payload["classDeltas"].as_array().unwrap();
    assert_eq!(rows.len(), 32);
    for (published, row) in rows.iter().zip(&report.rows) {
        assert_eq!(
            published["className"].as_str(),
            Some(row.class_name.as_str())
        );
    }
    assert_eq!(evidence.payload["coverage"]["total"], 40);
    assert_eq!(evidence.payload["coverage"]["included"], 32);
    assert_eq!(evidence.payload["coverage"]["omitted"], 8);
    assert_eq!(evidence.payload["coverage"]["truncated"], true);
}

#[test]
fn growth_rejects_wrong_or_malformed_frozen_mapping_identity() {
    for (a, b) in [
        (None, Some(digest(0x33))),
        (Some(digest(0x33)), None),
        (Some(digest(0x33)), Some(digest(0x44))),
        (Some("invalid".into()), Some("invalid".into())),
        (Some(digest(0x33)), Some("invalid".into())),
    ] {
        let mut report = comparison(vec![comparison_row("A")]);
        report.a_mapping_digest = a;
        report.b_mapping_digest = b;
        let error = phase3::build_comparison_growth_evidence(&report)
            .err()
            .unwrap();
        assert!(error.contains("mapping"), "{error}");
    }
    let mut report = comparison(vec![comparison_row("A")]);
    report.a_mapping_digest = Some(digest(0x33));
    report.b_mapping_digest = Some(digest(0x33));
    let evidence = phase3::build_comparison_growth_evidence(&report)
        .unwrap()
        .remove(0);
    let same = phase3::build_comparison_growth_evidence(&report)
        .unwrap()
        .remove(0);
    assert_eq!(evidence.id, same.id);
    report.a_mapping_digest = None;
    report.b_mapping_digest = None;
    let unmapped = phase3::build_comparison_growth_evidence(&report)
        .unwrap()
        .remove(0);
    assert_ne!(evidence.id, unmapped.id);
}

#[test]
fn growth_rejects_invalid_or_same_frozen_source_digests() {
    for (a, b) in [
        ("invalid".into(), digest(0x22)),
        (digest(0x11), "invalid".into()),
        (digest(0x11), digest(0x11)),
        (digest(0xab), digest(0xab).to_uppercase()),
    ] {
        let mut report = comparison(Vec::new());
        report.a_digest = a;
        report.b_digest = b;
        assert!(phase3::build_comparison_growth_evidence(&report).is_err());
    }
}

#[test]
fn growth_rejects_inconsistent_frozen_endpoint_coverage() {
    let valid = comparison(vec![comparison_row("A"), comparison_row("B")]);
    let mut malformed = Vec::new();
    let mut report = valid.clone();
    report.a_class_count = 1;
    malformed.push(report);
    let mut report = valid.clone();
    report.b_class_count = 1;
    malformed.push(report);
    let mut report = valid.clone();
    report.a_class_count = 3;
    report.a_loaded_classes = 3;
    malformed.push(report);
    let mut report = valid.clone();
    report.a_loaded_classes = 0;
    report.b_loaded_classes = 1;
    malformed.push(report);
    let mut report = valid.clone();
    report.a_loaded_classes = 1;
    malformed.push(report);
    let mut report = valid.clone();
    report.b_loaded_classes = 1;
    malformed.push(report);
    let mut report = valid.clone();
    report.rows.push(ClassDelta {
        class_name: "Empty".into(),
        ..ClassDelta::default()
    });
    malformed.push(report);
    let mut report = valid;
    report.a_class_count = u64::MAX;
    report.a_loaded_classes = u64::MAX;
    malformed.push(report);
    for report in malformed {
        let error = phase3::build_comparison_growth_evidence(&report)
            .err()
            .unwrap();
        assert!(error.contains("coverage"), "{error}");
    }
    let evidence = phase3::build_comparison_growth_evidence(&comparison(Vec::new()))
        .unwrap()
        .remove(0);
    assert!(
        evidence.payload["classDeltas"]
            .as_array()
            .unwrap()
            .is_empty()
    );
    assert_eq!(evidence.payload["coverage"]["truncated"], false);
}

#[test]
fn growth_rejects_malformed_frozen_labels_and_class_names() {
    for invalid in [
        "".to_owned(),
        " \t\n".into(),
        "bad\0text".into(),
        "x".repeat(2049),
    ] {
        let valid = comparison(vec![comparison_row("A")]);
        let mut report = valid.clone();
        report.a_label = invalid.clone();
        assert!(phase3::build_comparison_growth_evidence(&report).is_err());
        let mut report = valid.clone();
        report.b_label = invalid.clone();
        assert!(phase3::build_comparison_growth_evidence(&report).is_err());
        let mut report = valid;
        report.rows[0].class_name = invalid;
        assert!(phase3::build_comparison_growth_evidence(&report).is_err());
    }
    let report = comparison(vec![comparison_row("A"), comparison_row("A")]);
    assert!(phase3::build_comparison_growth_evidence(&report).is_err());
}

#[test]
fn growth_rejects_overflow_even_in_omitted_frozen_rows() {
    for metric in ["count", "shallow", "retained"] {
        let mut report = comparison(
            (0..33)
                .map(|index| comparison_row(&format!("Class{index:02}")))
                .collect(),
        );
        let row = report.rows.last_mut().unwrap();
        match metric {
            "count" => row.b_count = u64::MAX,
            "shallow" => row.b_shallow = u64::MAX,
            "retained" => row.b_retained = Some(u64::MAX),
            _ => unreachable!(),
        }
        let error = phase3::build_comparison_growth_evidence(&report)
            .err()
            .unwrap();
        assert!(error.contains(metric), "{error}");
        assert!(error.contains("signed JSON number range"), "{error}");
    }
}

#[test]
fn growth_propagates_canonical_comparison_failure() {
    let mut before = snapshot(&digest(0x11));
    before.classes = vec![class("A", 1, None)];
    let after = snapshot(&digest(0x22));
    let expected = compare_snapshots(&before, &after, "before".into(), "after".into())
        .err()
        .unwrap()
        .to_string();
    let error = phase3::build_growth_evidence(&before, &after, "before", "after")
        .err()
        .unwrap();
    assert_eq!(error, expected);
}

#[test]
fn growth_propagates_delta_overflow_from_a_valid_comparison() {
    let mut before = snapshot(&digest(0x11));
    before.classes = vec![class("A", 0, None)];
    before.metadata.class_count = 1;
    let mut after = snapshot(&digest(0x22));
    after.classes = vec![class("A", u64::MAX, None)];
    after.metadata.class_count = 1;
    let report = compare_snapshots(&before, &after, "before".into(), "after".into()).unwrap();
    let expected = phase3::build_comparison_growth_evidence(&report)
        .err()
        .unwrap();
    assert!(expected.contains("signed JSON number range"), "{expected}");
    let error = phase3::build_growth_evidence(&before, &after, "before", "after")
        .err()
        .unwrap();
    assert_eq!(error, expected);
}

#[test]
fn growth_propagates_oversized_payload_without_dropping_rows() {
    let before = snapshot(&digest(0x11));
    let mut after = snapshot(&digest(0x22));
    after.classes = (0..32)
        .map(|index| class(&format!("Class{index:02}{}", "x".repeat(600)), 1, None))
        .collect();
    after.metadata.class_count = 32;
    let report = compare_snapshots(&before, &after, "before".into(), "after".into()).unwrap();
    let expected = phase3::build_comparison_growth_evidence(&report)
        .err()
        .unwrap();
    assert!(expected.contains("16 KiB JSON budget"), "{expected}");
    let error = phase3::build_growth_evidence(&before, &after, "before", "after")
        .err()
        .unwrap();
    assert_eq!(error, expected);
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

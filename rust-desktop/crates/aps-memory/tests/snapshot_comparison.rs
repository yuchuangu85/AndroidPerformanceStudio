use aps_memory::{
    ClassStats, MemorySnapshot, SnapshotComparison, SnapshotComparisonError, SnapshotMetadata,
    compare_snapshots,
};

fn class(name: &str, count: u64, shallow: u64, retained: Option<u64>) -> ClassStats {
    ClassStats {
        class_name: name.into(),
        instance_count: count,
        shallow_size: shallow,
        retained_size: retained,
        ..ClassStats::default()
    }
}

fn snapshot(digest: &str, classes: Vec<ClassStats>) -> MemorySnapshot {
    MemorySnapshot {
        source_digest: digest.repeat(64),
        metadata: SnapshotMetadata {
            class_count: classes.len() as u64,
            ..SnapshotMetadata::default()
        },
        classes,
        ..MemorySnapshot::default()
    }
}

fn compare(
    a: &MemorySnapshot,
    b: &MemorySnapshot,
) -> Result<SnapshotComparison, SnapshotComparisonError> {
    compare_snapshots(a, b, "before.hprof".into(), "after.hprof".into())
}

#[test]
fn comparison_preserves_b_minus_a_and_known_absolute_retained_order() {
    let a = snapshot(
        "a",
        vec![
            class("Alpha", 2, 64, Some(500)),
            class("Beta", 5, 160, Some(900)),
            class("Gone", 3, 96, Some(90)),
            class("NoChange", 1, 8, Some(20)),
            class("Unknown", 1, 8, None),
            class("Zeta", 1, 32, Some(50)),
        ],
    );
    let b = snapshot(
        "b",
        vec![
            class("Added", 4, 128, Some(10)),
            class("Alpha", 5, 320, Some(650)),
            class("Beta", 1, 32, Some(500)),
            class("NoChange", 1, 8, Some(20)),
            class("Unknown", 2, 16, Some(40)),
            class("Zeta", 2, 64, Some(450)),
        ],
    );
    let report = compare(&a, &b).unwrap();
    assert_eq!(report.a_label, "before.hprof");
    assert_eq!(report.b_label, "after.hprof");
    assert_eq!(report.a_digest, a.source_digest);
    assert_eq!(report.b_digest, b.source_digest);
    assert_eq!(report.a_mapping_digest, None);
    assert_eq!(report.b_mapping_digest, None);
    assert_eq!(
        report
            .rows
            .iter()
            .map(|row| row.class_name.as_str())
            .collect::<Vec<_>>(),
        [
            "Beta", "Zeta", "Alpha", "NoChange", "Added", "Gone", "Unknown"
        ]
    );
    let alpha = &report.rows[2];
    assert_eq!(i128::from(alpha.b_count) - i128::from(alpha.a_count), 3);
    assert_eq!(
        i128::from(alpha.b_shallow) - i128::from(alpha.a_shallow),
        256
    );
    assert_eq!(alpha.a_retained, Some(500));
    assert_eq!(alpha.b_retained, Some(650));
    let beta = &report.rows[0];
    assert_eq!(i128::from(beta.b_count) - i128::from(beta.a_count), -4);
    assert_eq!(
        i128::from(beta.b_shallow) - i128::from(beta.a_shallow),
        -128
    );
}

#[test]
fn absent_classes_and_missing_retained_metrics_stay_unavailable() {
    let a = snapshot(
        "a",
        vec![
            class("Gone", 3, 96, Some(90)),
            class("MissingB", 2, 64, Some(50)),
        ],
    );
    let b = snapshot(
        "b",
        vec![
            class("Added", 4, 128, Some(10)),
            class("MissingB", 4, 128, None),
        ],
    );
    let report = compare(&a, &b).unwrap();
    let added = &report.rows[0];
    assert_eq!(added.class_name, "Added");
    assert_eq!(
        (added.a_count, added.a_shallow, added.a_retained),
        (0, 0, None)
    );
    assert_eq!(
        (added.b_count, added.b_shallow, added.b_retained),
        (4, 128, Some(10))
    );
    let gone = &report.rows[1];
    assert_eq!(gone.class_name, "Gone");
    assert_eq!(
        (gone.b_count, gone.b_shallow, gone.b_retained),
        (0, 0, None)
    );
    let missing = &report.rows[2];
    assert_eq!(missing.class_name, "MissingB");
    assert_eq!(missing.a_retained, Some(50));
    assert_eq!(missing.b_retained, None);
}

#[test]
fn comparison_keeps_full_unsigned_metric_range() {
    let a = snapshot("a", vec![class("Huge", u64::MAX, u64::MAX, Some(u64::MAX))]);
    let b = snapshot("b", vec![class("Huge", 0, 0, Some(0))]);
    let report = compare(&a, &b).unwrap();
    let row = &report.rows[0];
    assert_eq!(
        i128::from(row.b_count) - i128::from(row.a_count),
        -i128::from(u64::MAX)
    );
    assert_eq!(
        i128::from(row.b_shallow) - i128::from(row.a_shallow),
        -i128::from(u64::MAX)
    );
    assert_eq!(row.a_retained, Some(u64::MAX));
    assert_eq!(row.b_retained, Some(0));
}

#[test]
fn mappings_must_both_be_absent_or_have_the_same_valid_digest() {
    for (a_mapping, b_mapping, expected) in [
        (None, None, None),
        (Some("c"), Some("c"), None),
        (Some("c"), Some("C"), None),
        (
            Some("c"),
            Some("d"),
            Some(SnapshotComparisonError::MappingMismatch),
        ),
        (
            Some("c"),
            None,
            Some(SnapshotComparisonError::MappingMismatch),
        ),
        (
            None,
            Some("c"),
            Some(SnapshotComparisonError::MappingMismatch),
        ),
    ] {
        let mut a = snapshot("a", vec![]);
        let mut b = snapshot("b", vec![]);
        a.mapping_digest = a_mapping.map(|digest| digest.repeat(64));
        b.mapping_digest = b_mapping.map(|digest| digest.repeat(64));
        let result = compare(&a, &b);
        if let Some(error) = expected {
            assert_eq!(result, Err(error));
        } else {
            let report = result.unwrap();
            assert_eq!(report.a_mapping_digest, a.mapping_digest);
            assert_eq!(report.b_mapping_digest, b.mapping_digest);
            assert!(report.matches_snapshots(&a, &b));
        }
    }
}

#[test]
fn same_source_is_rejected_even_with_different_hex_case_or_mapping() {
    let a = snapshot("a", vec![]);
    for digest in ["a", "A"] {
        let mut b = snapshot(digest, vec![]);
        assert_eq!(compare(&a, &b), Err(SnapshotComparisonError::SameSnapshot));
        b.mapping_digest = Some("c".repeat(64));
        assert_eq!(compare(&a, &b), Err(SnapshotComparisonError::SameSnapshot));
    }
}

#[test]
fn invalid_source_and_mapping_digests_fail_closed() {
    for invalid in [
        String::new(),
        "digest".into(),
        "a".repeat(63),
        "a".repeat(65),
        "g".repeat(64),
        "\u{e9}".repeat(32),
    ] {
        for endpoint in 0..4 {
            let mut a = snapshot("a", vec![]);
            let mut b = snapshot("b", vec![]);
            match endpoint {
                0 => a.source_digest = invalid.clone(),
                1 => b.source_digest = invalid.clone(),
                2 => a.mapping_digest = Some(invalid.clone()),
                _ => b.mapping_digest = Some(invalid.clone()),
            }
            assert_eq!(
                compare(&a, &b),
                Err(SnapshotComparisonError::InvalidIdentity)
            );
        }
    }
}

#[test]
fn coverage_uses_metadata_totals_and_keeps_partial_loaded_counts() {
    let mut a = snapshot("a", vec![class("Alpha", 1, 8, None)]);
    let mut b = snapshot("b", vec![]);
    a.metadata.class_count = 8;
    b.metadata.class_count = 12;
    let report = compare(&a, &b).unwrap();
    assert_eq!((report.a_class_count, report.b_class_count), (8, 12));
    assert_eq!((report.a_loaded_classes, report.b_loaded_classes), (1, 0));
    assert!(report.matches_snapshots(&a, &b));

    a.metadata.class_count = 0;
    assert_eq!(
        compare(&a, &b),
        Err(SnapshotComparisonError::InvalidCoverage)
    );
    a.metadata.class_count = 1;
    b.classes.push(class("Beta", 1, 8, None));
    b.metadata.class_count = 0;
    assert_eq!(
        compare(&a, &b),
        Err(SnapshotComparisonError::InvalidCoverage)
    );
}

#[test]
fn report_matching_checks_endpoint_order_mapping_and_coverage_not_labels_or_rows() {
    let mut a = snapshot("a", vec![class("Alpha", 1, 8, Some(8))]);
    let mut b = snapshot("b", vec![class("Alpha", 2, 16, Some(16))]);
    a.mapping_digest = Some("c".repeat(64));
    b.mapping_digest = Some("c".repeat(64));
    let mut report = compare(&a, &b).unwrap();
    assert!(report.matches_snapshots(&a, &b));
    assert!(!report.matches_snapshots(&b, &a));
    report.a_label.clear();
    report.b_label.clear();
    report.rows.clear();
    assert!(report.matches_snapshots(&a, &b));

    let mut changed = a.clone();
    changed.source_digest = "d".repeat(64);
    assert!(!report.matches_snapshots(&changed, &b));
    changed = a.clone();
    changed.metadata.class_count += 1;
    assert!(!report.matches_snapshots(&changed, &b));
    changed = a.clone();
    changed.classes.clear();
    assert!(!report.matches_snapshots(&changed, &b));
    changed = a.clone();
    changed.mapping_digest = Some("e".repeat(64));
    let mut changed_b = b.clone();
    changed_b.mapping_digest = changed.mapping_digest.clone();
    assert!(!report.matches_snapshots(&changed, &changed_b));
    changed.mapping_digest = None;
    changed_b.mapping_digest = None;
    assert!(!report.matches_snapshots(&changed, &changed_b));
}

#[test]
fn report_matching_rejects_invalid_report_and_endpoint_identities_or_coverage() {
    let a = snapshot("a", vec![class("Alpha", 1, 8, None)]);
    let b = snapshot("b", vec![class("Beta", 1, 8, None)]);
    let report = compare(&a, &b).unwrap();
    assert!(!SnapshotComparison::default().matches_snapshots(&a, &b));

    for field in 0..10 {
        let mut changed = report.clone();
        match field {
            0 => changed.a_digest.clear(),
            1 => changed.b_digest = changed.a_digest.clone(),
            2 => changed.a_mapping_digest = Some(String::new()),
            3 => changed.b_mapping_digest = Some("c".repeat(64)),
            4 => changed.a_class_count += 1,
            5 => changed.b_class_count += 1,
            6 => changed.a_loaded_classes += 1,
            7 => changed.b_loaded_classes += 1,
            8 => changed.a_class_count = 0,
            _ => changed.b_class_count = 0,
        }
        assert!(!changed.matches_snapshots(&a, &b));
    }

    for endpoint in 0..6 {
        let mut changed_a = a.clone();
        let mut changed_b = b.clone();
        let mut changed_report = report.clone();
        match endpoint {
            0 => changed_a.source_digest.clear(),
            1 => changed_b.source_digest.clear(),
            2 => changed_a.mapping_digest = Some(String::new()),
            3 => changed_b.mapping_digest = Some(String::new()),
            4 => {
                changed_a.metadata.class_count = 0;
                changed_report.a_class_count = 0;
            }
            _ => {
                changed_b.metadata.class_count = 0;
                changed_report.b_class_count = 0;
            }
        }
        assert!(!changed_report.matches_snapshots(&changed_a, &changed_b));
    }
}

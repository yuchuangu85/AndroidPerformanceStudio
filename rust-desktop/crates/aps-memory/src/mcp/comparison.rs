//! Read-only pagination and bounded, signed JSON deltas for the frozen comparison.
use aps_memory::{ClassDelta, SnapshotComparison};
use serde_json::{Value, json};

pub(super) fn comparison_args(value: &Value) -> Result<(usize, usize), (i64, String)> {
    let invalid = || {
        (
            -32602,
            "Snapshot diff accepts only offset >= 0 and limit from 1 to 100".into(),
        )
    };
    let arguments = value.as_object().ok_or_else(invalid)?;
    if arguments
        .keys()
        .any(|key| !matches!(key.as_str(), "offset" | "limit"))
    {
        return Err(invalid());
    }
    let read = |key: &str, default: u64| -> Result<usize, (i64, String)> {
        let number = match arguments.get(key) {
            None => default,
            Some(value) => value.as_u64().ok_or_else(invalid)?,
        };
        usize::try_from(number).map_err(|_| invalid())
    };
    let offset = read("offset", 0)?;
    let limit = read("limit", 50)?;
    if !(1..=100).contains(&limit) {
        return Err(invalid());
    }
    Ok((offset, limit))
}

fn delta(after: u64, before: u64) -> Result<i64, String> {
    i64::try_from(i128::from(after) - i128::from(before))
        .map_err(|_| "Snapshot diff exceeds the signed JSON delta range".into())
}

pub(super) fn validate_deltas(row: &ClassDelta) -> Result<(), String> {
    delta(row.b_count, row.a_count)?;
    delta(row.b_shallow, row.a_shallow)?;
    if let Some((before, after)) = row.a_retained.zip(row.b_retained) {
        delta(after, before)?;
    }
    Ok(())
}

pub(super) fn comparison_page(
    report: &SnapshotComparison,
    offset: usize,
    limit: usize,
) -> Result<Value, String> {
    let rows = report
        .rows
        .iter()
        .skip(offset)
        .take(limit)
        .map(|row| {
            Ok(json!({
                "className": row.class_name,
                "beforeCount": row.a_count,
                "afterCount": row.b_count,
                "countDelta": delta(row.b_count, row.a_count)?,
                "beforeShallowSizeBytes": row.a_shallow,
                "afterShallowSizeBytes": row.b_shallow,
                "shallowSizeDeltaBytes": delta(row.b_shallow, row.a_shallow)?,
                "beforeRetainedSizeBytes": row.a_retained,
                "afterRetainedSizeBytes": row.b_retained,
                "retainedSizeDeltaBytes": row.a_retained.zip(row.b_retained)
                    .map(|(before, after)| delta(after, before)).transpose()?,
            }))
        })
        .collect::<Result<Vec<_>, String>>()?;
    let total = report.rows.len();
    let included = rows.len();
    Ok(json!({
        "policyVersion": "aps-memory-snapshot-diff-v1",
        "direction": "B-A",
        "beforeSnapshotId": report.a_digest,
        "afterSnapshotId": report.b_digest,
        "beforeMappingDigest": report.a_mapping_digest,
        "afterMappingDigest": report.b_mapping_digest,
        "total": total,
        "offset": offset,
        "limit": limit,
        "hasMore": offset.saturating_add(included) < total,
        "coverage": {
            "beforeClassCount": report.a_class_count,
            "afterClassCount": report.b_class_count,
            "beforeLoadedClasses": report.a_loaded_classes,
            "afterLoadedClasses": report.b_loaded_classes,
            "included": included,
            "omitted": total - included,
            "truncated": included < total,
        },
        "rows": rows,
        "evidenceBoundary": "Class-name matching only; build identity unverified; no cross-dump object identity; retained sizes may overlap and unavailable retained deltas remain unknown",
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_memory::compare_snapshots;

    #[test]
    fn diff_arguments_reject_bad_types_ranges_and_arbitrary_source_paths() {
        assert_eq!(comparison_args(&json!({})).unwrap(), (0, 50));
        assert_eq!(
            comparison_args(&json!({"offset":4,"limit":100})).unwrap(),
            (4, 100)
        );
        for value in [
            Value::Null,
            json!([]),
            json!({"offset":-1}),
            json!({"offset":1.5}),
            json!({"offset":"0"}),
            json!({"limit":0}),
            json!({"limit":101}),
            json!({"limit":null}),
            json!({"limit":true}),
            json!({"path":"other.hprof"}),
            json!({"beforeSnapshotId":"a".repeat(64)}),
        ] {
            assert_eq!(comparison_args(&value).unwrap_err().0, -32602);
        }
    }

    fn comparison_fixture() -> SnapshotComparison {
        let snapshot = |digest: &str, entries: &[(&str, u64)]| aps_memory::MemorySnapshot {
            source_digest: digest.repeat(64),
            metadata: aps_memory::SnapshotMetadata {
                class_count: entries.len() as u64,
                ..Default::default()
            },
            classes: entries
                .iter()
                .map(|(name, count)| aps_memory::ClassStats {
                    class_name: (*name).into(),
                    instance_count: *count,
                    shallow_size: count * 8,
                    retained_size: Some(count * 16),
                    ..Default::default()
                })
                .collect(),
            ..Default::default()
        };
        compare_snapshots(
            &snapshot("a", &[("Drop", 2), ("Grew", 1), ("Shrank", 5)]),
            &snapshot("b", &[("Add", 3), ("Grew", 3), ("Shrank", 2)]),
            "A".into(),
            "B".into(),
        )
        .unwrap()
    }

    #[test]
    fn diff_pages_keep_frozen_order_source_identity_direction_and_unknown_retained() {
        let report = comparison_fixture();
        let page = comparison_page(&report, 1, 2).unwrap();
        assert_eq!(page["policyVersion"], "aps-memory-snapshot-diff-v1");
        assert_eq!(page["direction"], "B-A");
        assert_eq!(page["beforeSnapshotId"], "a".repeat(64));
        assert_eq!(page["afterSnapshotId"], "b".repeat(64));
        assert!(page["beforeMappingDigest"].is_null());
        assert_eq!(page["total"], 4);
        assert_eq!(page["offset"], 1);
        assert_eq!(page["hasMore"], true);
        assert_eq!(page["coverage"]["included"], 2);
        assert_eq!(page["coverage"]["omitted"], 2);
        assert_eq!(page["coverage"]["beforeLoadedClasses"], 3);
        assert_eq!(page["rows"][0]["className"], "Grew");
        assert_eq!(page["rows"][0]["countDelta"], 2);
        assert_eq!(page["rows"][0]["shallowSizeDeltaBytes"], 16);
        assert_eq!(page["rows"][0]["retainedSizeDeltaBytes"], 32);
        assert_eq!(page["rows"][1]["className"], "Add");
        assert!(page["rows"][1]["beforeRetainedSizeBytes"].is_null());
        assert!(page["rows"][1]["retainedSizeDeltaBytes"].is_null());
        let first = comparison_page(&report, 0, 1).unwrap();
        assert_eq!(first["rows"][0]["className"], "Shrank");
        assert_eq!(first["rows"][0]["countDelta"], -3);
        assert_eq!(first["rows"][0]["retainedSizeDeltaBytes"], -48);
        assert!(!page.to_string().contains("objectId"));
    }

    #[test]
    fn past_end_and_empty_comparisons_keep_explicit_page_coverage() {
        let report = comparison_fixture();
        let page = comparison_page(&report, usize::MAX, 50).unwrap();
        assert_eq!(page["rows"], json!([]));
        assert_eq!(page["hasMore"], false);
        assert_eq!(page["coverage"]["included"], 0);
        assert_eq!(page["coverage"]["omitted"], 4);
        assert_eq!(page["coverage"]["truncated"], true);
        let mut empty = report;
        empty.rows.clear();
        let page = comparison_page(&empty, 0, 50).unwrap();
        assert_eq!(page["total"], 0);
        assert_eq!(page["hasMore"], false);
        assert_eq!(page["coverage"]["truncated"], false);
    }

    #[test]
    fn comparison_deltas_fail_instead_of_overflowing_or_fabricating_unknown_values() {
        assert_eq!(delta(i64::MAX as u64, 0).unwrap(), i64::MAX);
        assert_eq!(delta(0, i64::MAX as u64).unwrap(), -i64::MAX);
        assert!(delta(u64::MAX, 0).is_err());
        assert!(delta(0, u64::MAX).is_err());
        for row in [
            ClassDelta {
                a_count: u64::MAX,
                ..Default::default()
            },
            ClassDelta {
                b_shallow: u64::MAX,
                ..Default::default()
            },
            ClassDelta {
                a_retained: Some(0),
                b_retained: Some(u64::MAX),
                ..Default::default()
            },
        ] {
            assert!(validate_deltas(&row).is_err());
        }
        let unknown = ClassDelta {
            a_retained: None,
            b_retained: Some(u64::MAX),
            ..Default::default()
        };
        assert!(validate_deltas(&unknown).is_ok());
    }
}

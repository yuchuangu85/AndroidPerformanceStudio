//! Class-level comparison of immutable, comparably mapped HPROF snapshots.
//! Classes are joined by name only; object IDs never cross snapshots.

use crate::MemorySnapshot;
use std::collections::BTreeMap;
use std::fmt;

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct ClassDelta {
    pub class_name: String,
    pub a_count: u64,
    pub b_count: u64,
    pub a_shallow: u64,
    pub b_shallow: u64,
    pub a_retained: Option<u64>,
    pub b_retained: Option<u64>,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct SnapshotComparison {
    pub a_label: String,
    pub b_label: String,
    pub a_digest: String,
    pub b_digest: String,
    pub a_mapping_digest: Option<String>,
    pub b_mapping_digest: Option<String>,
    pub a_class_count: u64,
    pub b_class_count: u64,
    pub a_loaded_classes: u64,
    pub b_loaded_classes: u64,
    pub rows: Vec<ClassDelta>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SnapshotComparisonError {
    InvalidIdentity,
    SameSnapshot,
    MappingMismatch,
    InvalidCoverage,
}

impl fmt::Display for SnapshotComparisonError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::InvalidIdentity => "Snapshot source or mapping SHA-256 identity is invalid",
            Self::SameSnapshot => "Snapshot comparison requires distinct HPROF sources",
            Self::MappingMismatch => "Snapshot class mappings are not comparable",
            Self::InvalidCoverage => "Snapshot loaded classes exceed the metadata class total",
        })
    }
}

impl std::error::Error for SnapshotComparisonError {}

fn valid_digest(digest: &str) -> bool {
    digest.len() == 64 && digest.bytes().all(|byte| byte.is_ascii_hexdigit())
}

fn same_mapping(a: Option<&str>, b: Option<&str>) -> bool {
    match (a, b) {
        (None, None) => true,
        (Some(a), Some(b)) => a.eq_ignore_ascii_case(b),
        _ => false,
    }
}

fn validate_identities(
    a_digest: &str,
    b_digest: &str,
    a_mapping_digest: Option<&str>,
    b_mapping_digest: Option<&str>,
) -> Result<(), SnapshotComparisonError> {
    if !valid_digest(a_digest)
        || !valid_digest(b_digest)
        || a_mapping_digest.is_some_and(|digest| !valid_digest(digest))
        || b_mapping_digest.is_some_and(|digest| !valid_digest(digest))
    {
        return Err(SnapshotComparisonError::InvalidIdentity);
    }
    if a_digest.eq_ignore_ascii_case(b_digest) {
        return Err(SnapshotComparisonError::SameSnapshot);
    }
    if !same_mapping(a_mapping_digest, b_mapping_digest) {
        return Err(SnapshotComparisonError::MappingMismatch);
    }
    Ok(())
}

fn loaded_classes(snapshot: &MemorySnapshot) -> Result<u64, SnapshotComparisonError> {
    let loaded = u64::try_from(snapshot.classes.len())
        .map_err(|_| SnapshotComparisonError::InvalidCoverage)?;
    if snapshot.metadata.class_count < loaded {
        return Err(SnapshotComparisonError::InvalidCoverage);
    }
    Ok(loaded)
}

impl SnapshotComparison {
    /// Checks immutable endpoint identities and coverage, not labels or rows.
    pub fn matches_snapshots(&self, a: &MemorySnapshot, b: &MemorySnapshot) -> bool {
        if validate_identities(
            &self.a_digest,
            &self.b_digest,
            self.a_mapping_digest.as_deref(),
            self.b_mapping_digest.as_deref(),
        )
        .is_err()
            || validate_identities(
                &a.source_digest,
                &b.source_digest,
                a.mapping_digest.as_deref(),
                b.mapping_digest.as_deref(),
            )
            .is_err()
        {
            return false;
        }
        let (Ok(a_loaded), Ok(b_loaded)) = (loaded_classes(a), loaded_classes(b)) else {
            return false;
        };
        self.a_digest.eq_ignore_ascii_case(&a.source_digest)
            && self.b_digest.eq_ignore_ascii_case(&b.source_digest)
            && same_mapping(
                self.a_mapping_digest.as_deref(),
                a.mapping_digest.as_deref(),
            )
            && same_mapping(
                self.b_mapping_digest.as_deref(),
                b.mapping_digest.as_deref(),
            )
            && self.a_class_count == a.metadata.class_count
            && self.b_class_count == b.metadata.class_count
            && self.a_loaded_classes == a_loaded
            && self.b_loaded_classes == b_loaded
    }
}

fn retained_delta(row: &ClassDelta) -> Option<i128> {
    Some(i128::from(row.b_retained?) - i128::from(row.a_retained?))
}

/// Projects B - A by class name, sorted by known absolute retained delta
/// descending, then class name. Missing retained evidence stays unavailable.
pub fn compare_snapshots(
    a: &MemorySnapshot,
    b: &MemorySnapshot,
    a_label: String,
    b_label: String,
) -> Result<SnapshotComparison, SnapshotComparisonError> {
    validate_identities(
        &a.source_digest,
        &b.source_digest,
        a.mapping_digest.as_deref(),
        b.mapping_digest.as_deref(),
    )?;
    let a_loaded_classes = loaded_classes(a)?;
    let b_loaded_classes = loaded_classes(b)?;

    let mut merged: BTreeMap<String, ClassDelta> = BTreeMap::new();
    for class in &a.classes {
        let row = merged.entry(class.class_name.clone()).or_default();
        row.a_count = class.instance_count;
        row.a_shallow = class.shallow_size;
        row.a_retained = class.retained_size;
    }
    for class in &b.classes {
        let row = merged.entry(class.class_name.clone()).or_default();
        row.b_count = class.instance_count;
        row.b_shallow = class.shallow_size;
        row.b_retained = class.retained_size;
    }
    let mut rows = merged
        .into_iter()
        .map(|(class_name, row)| ClassDelta { class_name, ..row })
        .collect::<Vec<_>>();
    rows.sort_by(|left, right| {
        retained_delta(left)
            .map(i128::unsigned_abs)
            .is_none()
            .cmp(&retained_delta(right).map(i128::unsigned_abs).is_none())
            .then_with(|| {
                let left_delta = retained_delta(left).map(i128::unsigned_abs).unwrap_or(0);
                let right_delta = retained_delta(right).map(i128::unsigned_abs).unwrap_or(0);
                right_delta.cmp(&left_delta)
            })
            .then_with(|| left.class_name.cmp(&right.class_name))
    });
    Ok(SnapshotComparison {
        a_label,
        b_label,
        a_digest: a.source_digest.clone(),
        b_digest: b.source_digest.clone(),
        a_mapping_digest: a.mapping_digest.clone(),
        b_mapping_digest: b.mapping_digest.clone(),
        a_class_count: a.metadata.class_count,
        b_class_count: b.metadata.class_count,
        a_loaded_classes,
        b_loaded_classes,
        rows,
    })
}

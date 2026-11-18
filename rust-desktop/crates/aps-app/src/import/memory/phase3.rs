//! Phase 3 memory evidence adapters.
//!
//! These adapters accept already bounded data and emit bounded evidence. They
//! do not read artifacts, walk object graphs, or create source candidates.

use aps_ai::Evidence;
use aps_contracts::SourceDigest;
use aps_json::{Limits, Map, Value};
use aps_memory::MemorySnapshot;
use aps_perfetto::heap_profile::HeapProfileKind;
use std::collections::BTreeMap;

const MAX_BYTES: u64 = 16 * 1024;
const MAX_ROWS: usize = 32;
const MAX_CHAIN_NODES: usize = 16;
const MAX_TEXT: usize = 2048;

fn limits() -> Limits {
    Limits {
        max_bytes: MAX_BYTES,
        max_depth: 16,
        max_nodes: 20_000,
        max_string_bytes: MAX_TEXT,
        max_collection_items: 512,
    }
}
fn object(values: impl IntoIterator<Item = (&'static str, Value)>) -> Value {
    Value::Object(
        values
            .into_iter()
            .map(|(key, value)| (key.to_owned(), value))
            .collect::<Map<_, _>>(),
    )
}
fn text(value: &str, name: &str) -> Result<(), String> {
    if value.is_empty()
        || value.len() > MAX_TEXT
        || value
            .chars()
            .any(|c| c.is_control() && !matches!(c, '\n' | '\r' | '\t'))
    {
        return Err(format!("{name} is outside the bounded text contract"));
    }
    Ok(())
}
fn digest(value: &str, name: &str) -> Result<String, String> {
    Ok(SourceDigest::from_hex(value)
        .map_err(|_| format!("invalid {name}"))?
        .to_hex())
}
fn delta(after: u64, before: u64, name: &str) -> Result<i64, String> {
    i64::try_from(i128::from(after) - i128::from(before))
        .map_err(|_| format!("{name} exceeds the signed JSON number range"))
}
fn finish(id: String, kind: &str, summary: &str, payload: Value) -> Result<Evidence, String> {
    aps_json::to_vec(&payload, limits())
        .map_err(|_| "memory evidence exceeds the 16 KiB JSON budget".to_owned())?;
    Ok(Evidence {
        id,
        kind: kind.to_owned(),
        summary: summary.to_owned(),
        payload,
    })
}

fn coverage(total: u64, included: usize) -> Value {
    object([
        ("total", Value::from(total)),
        ("included", Value::from(included as u64)),
        (
            "omitted",
            Value::from(total.saturating_sub(included as u64)),
        ),
        ("truncated", (total > included as u64).into()),
    ])
}

/// Builds class-level HPROF growth evidence. Object identity is never emitted.
pub(crate) fn build_growth_evidence(
    before: &MemorySnapshot,
    after: &MemorySnapshot,
    before_label: &str,
    after_label: &str,
) -> Result<Vec<Evidence>, String> {
    let before_digest = digest(&before.source_digest, "before source digest")?;
    let after_digest = digest(&after.source_digest, "after source digest")?;
    if before_digest == after_digest {
        return Err("growth evidence requires two different snapshot digests".into());
    }
    text(before_label, "before label")?;
    text(after_label, "after label")?;
    let mut merged = BTreeMap::<String, (Option<usize>, Option<usize>)>::new();
    for (index, row) in before.classes.iter().enumerate() {
        text(&row.class_name, "class name")?;
        merged.entry(row.class_name.clone()).or_default().0 = Some(index);
    }
    for (index, row) in after.classes.iter().enumerate() {
        text(&row.class_name, "class name")?;
        merged.entry(row.class_name.clone()).or_default().1 = Some(index);
    }
    let mut rows = Vec::with_capacity(merged.len());
    for (class_name, (before_index, after_index)) in merged {
        let before = before_index.map(|index| &before.classes[index]);
        let after = after_index.map(|index| &after.classes[index]);
        let before_count = before.map_or(0, |row| row.instance_count);
        let after_count = after.map_or(0, |row| row.instance_count);
        let before_shallow = before.map_or(0, |row| row.shallow_size);
        let after_shallow = after.map_or(0, |row| row.shallow_size);
        let before_retained = before.and_then(|row| row.retained_size);
        let after_retained = after.and_then(|row| row.retained_size);
        let retained_delta = before_retained
            .zip(after_retained)
            .map(|(b, a)| delta(a, b, "retained delta"))
            .transpose()?;
        rows.push((
            class_name,
            before_count,
            after_count,
            delta(after_count, before_count, "count delta")?,
            before_shallow,
            after_shallow,
            delta(after_shallow, before_shallow, "shallow delta")?,
            before_retained,
            after_retained,
            retained_delta,
        ));
    }
    let total_rows = rows.len();
    rows.sort_by(|left, right| {
        right
            .9
            .map(i64::unsigned_abs)
            .cmp(&left.9.map(i64::unsigned_abs))
            .then_with(|| right.3.unsigned_abs().cmp(&left.3.unsigned_abs()))
            .then_with(|| left.0.cmp(&right.0))
    });
    rows.truncate(MAX_ROWS);
    let before_loaded = before.classes.len() as u64;
    let after_loaded = after.classes.len() as u64;
    if before.metadata.class_count < before_loaded || after.metadata.class_count < after_loaded {
        return Err("snapshot class coverage is inconsistent".into());
    }
    let class_deltas = rows
        .iter()
        .map(|row| {
            object([
                ("className", row.0.clone().into()),
                ("instanceCountDelta", row.3.into()),
                (
                    "retainedBytesDelta",
                    row.9.map(Value::from).unwrap_or(Value::Null),
                ),
            ])
        })
        .collect::<Vec<_>>();
    let _ = (before_label, after_label, before_loaded, after_loaded);
    let payload = object([
        ("policyVersion", "aps-memory-growth-v1".into()),
        ("beforeSnapshotId", before_digest.clone().into()),
        ("afterSnapshotId", after_digest.clone().into()),
        ("classDeltas", class_deltas.into()),
        (
            "coverage",
            coverage(total_rows as u64, total_rows.min(MAX_ROWS)),
        ),
    ]);
    Ok(vec![finish(
        format!("memory-hprof-growth:{before_digest}:{after_digest}"),
        "memory-hprof-growth",
        "Class-level HPROF growth comparison; retained deltas may be unknown.",
        payload,
    )?])
}

#[derive(Clone, Copy)]
enum RuleStatus {
    Candidate,
    NoMatch,
    Unknown,
}
impl RuleStatus {
    fn as_str(self) -> &'static str {
        match self {
            Self::Candidate => "CANDIDATE",
            Self::NoMatch => "NO_MATCH",
            Self::Unknown => "UNKNOWN",
        }
    }
}
fn rule(id: &'static str, status: RuleStatus, ids: Vec<String>, count: usize) -> Value {
    object([
        ("ruleId", id.into()),
        ("status", status.as_str().into()),
        ("evidenceIds", ids.into()),
        ("count", (count as u64).into()),
    ])
}
fn fragment(name: &str) -> bool {
    name.ends_with("Fragment") || name.contains(".Fragment$")
}
fn static_reason(reason: &str) -> bool {
    reason.starts_with("Static/singleton field ")
}
fn handler_reason(reason: &str) -> bool {
    reason.contains(" retains an Activity through ")
}
fn candidate_id(kind: &str, digest: &str, index: usize) -> String {
    format!("{kind}:{digest}:{index}")
}
fn evidence_ids<T>(kind: &str, digest: &str, rows: &[(usize, &T)]) -> Vec<String> {
    rows.iter()
        .map(|(index, _)| candidate_id(kind, digest, *index))
        .collect()
}

/// Builds deterministic lifecycle rules from the existing findings only.
pub(crate) fn build_lifecycle_rule_evidence(
    snapshot: &MemorySnapshot,
) -> Result<Option<Evidence>, String> {
    let source_digest = digest(&snapshot.source_digest, "snapshot source digest")?;
    let findings = &snapshot.findings;
    if findings.activities.total == 0
        && findings.lifecycle.total == 0
        && findings.leak_suspects.total == 0
    {
        return Ok(None);
    }
    let activities = findings
        .activities
        .rows
        .iter()
        .enumerate()
        .filter(|(_, row)| row.destroyed_instance_count > 0)
        .collect::<Vec<_>>();
    let fragments = findings
        .lifecycle
        .rows
        .iter()
        .enumerate()
        .filter(|(_, row)| fragment(&row.class_name))
        .collect::<Vec<_>>();
    let statics = findings
        .leak_suspects
        .rows
        .iter()
        .enumerate()
        .filter(|(_, row)| static_reason(&row.reason))
        .collect::<Vec<_>>();
    let handlers = findings
        .leak_suspects
        .rows
        .iter()
        .enumerate()
        .filter(|(_, row)| handler_reason(&row.reason))
        .collect::<Vec<_>>();
    let activity_complete = findings.activities.total <= findings.activities.rows.len() as u64;
    let lifecycle_complete = findings.lifecycle.total <= findings.lifecycle.rows.len() as u64;
    let suspects_complete =
        findings.leak_suspects.total <= findings.leak_suspects.rows.len() as u64;
    let status = |matched: bool, complete: bool| {
        if matched {
            RuleStatus::Candidate
        } else if complete {
            RuleStatus::NoMatch
        } else {
            RuleStatus::Unknown
        }
    };
    let payload = object([
        ("snapshotDigest", source_digest.clone().into()),
        ("sourceCandidates", Value::Array(Vec::new())),
        (
            "coverage",
            object([
                ("activityTotal", findings.activities.total.into()),
                (
                    "activityRowsLoaded",
                    (findings.activities.rows.len() as u64).into(),
                ),
                ("lifecycleTotal", findings.lifecycle.total.into()),
                (
                    "lifecycleRowsLoaded",
                    (findings.lifecycle.rows.len() as u64).into(),
                ),
                ("suspectTotal", findings.leak_suspects.total.into()),
                (
                    "suspectRowsLoaded",
                    (findings.leak_suspects.rows.len() as u64).into(),
                ),
            ]),
        ),
        (
            "rules",
            vec![
                rule(
                    "destroyed-activity-retained",
                    status(!activities.is_empty(), activity_complete),
                    evidence_ids("memory-activity-candidate", &source_digest, &activities),
                    activities.len(),
                ),
                rule(
                    "detached-fragment-retained",
                    status(!fragments.is_empty(), lifecycle_complete),
                    evidence_ids("memory-lifecycle-candidate", &source_digest, &fragments),
                    fragments.len(),
                ),
                rule(
                    "static-context-retained",
                    status(!statics.is_empty(), suspects_complete),
                    evidence_ids("memory-leak-suspect", &source_digest, &statics),
                    statics.len(),
                ),
                rule(
                    "handler-activity-retained",
                    status(!handlers.is_empty(), suspects_complete),
                    evidence_ids("memory-leak-suspect", &source_digest, &handlers),
                    handlers.len(),
                ),
            ]
            .into(),
        ),
    ]);
    Ok(Some(finish(
        format!("memory-lifecycle-rule:{source_digest}"),
        "memory-lifecycle-rule",
        "Deterministic lifecycle rules derived from existing HPROF findings; no AI inference.",
        payload,
    )?))
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum NativeHeapRowCoverage {
    Sampled,
    Partial,
    Unknown,
}
impl NativeHeapRowCoverage {
    fn as_str(self) -> &'static str {
        match self {
            Self::Sampled => "SAMPLED",
            Self::Partial => "PARTIAL",
            Self::Unknown => "UNKNOWN",
        }
    }
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct NativeHeapEvidenceRow {
    pub leaf_frame: Option<String>,
    pub sample_rows: u64,
    pub allocated_bytes: i64,
    pub net_bytes: i64,
    pub coverage: NativeHeapRowCoverage,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct NativeHeapEvidenceCoverage {
    pub rows_loaded: u64,
    pub rows_included: u64,
    pub has_more: bool,
    pub sampling_interval_bytes: Option<u64>,
}

/// Builds native heapprofd evidence and rejects ART/Java sampled input.
pub(crate) fn build_native_heap_evidence(
    kind: HeapProfileKind,
    trace_digest: &str,
    rows: &[NativeHeapEvidenceRow],
    coverage: &NativeHeapEvidenceCoverage,
) -> Result<Evidence, String> {
    if kind != HeapProfileKind::Native {
        return Err("native heap evidence requires HeapProfileKind::Native".into());
    }
    let trace_digest = digest(trace_digest, "trace digest")?;
    if rows.len() > MAX_ROWS || coverage.rows_included != rows.len() as u64 {
        return Err("native heap rows exceed the bounded coverage contract".into());
    }
    if coverage.rows_included > coverage.rows_loaded {
        return Err("native heap coverage includes more rows than loaded".into());
    }
    for row in rows {
        if let Some(frame) = &row.leaf_frame {
            text(frame, "native leaf frame")?;
        }
    }
    let payload = object([
        ("traceDigest", trace_digest.clone().into()), ("heapProfileKind", "NATIVE".into()),
        ("sampling", object([("sampled", true.into()), ("caveat", "heapprofd values are sampled accounting over the recorded trace, not the full live heap".into()), ("samplingIntervalBytes", coverage.sampling_interval_bytes.map(Value::from).unwrap_or(Value::Null))])),
        ("coverage", object([("rowsLoaded", coverage.rows_loaded.into()), ("rowsIncluded", coverage.rows_included.into()), ("rowsOmitted", (coverage.rows_loaded - coverage.rows_included).into()), ("hasMore", coverage.has_more.into()), ("rowLimit", (MAX_ROWS as u64).into())])),
        ("rows", rows.iter().map(|row| object([("leafFrame", row.leaf_frame.clone().map(Value::from).unwrap_or(Value::Null)), ("sampleRows", row.sample_rows.into()), ("allocatedBytes", row.allocated_bytes.into()), ("netBytes", row.net_bytes.into()), ("coverage", row.coverage.as_str().into())])).collect::<Vec<_>>().into()),
    ]);
    finish(
        format!("memory-native-heap:{trace_digest}"),
        "memory-native-heap",
        "Native heapprofd sampled accounting; not Java retained-heap evidence.",
        payload,
    )
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum SharkStatus {
    Available,
    Unavailable,
    Failed,
}
impl SharkStatus {
    fn as_str(self) -> &'static str {
        match self {
            Self::Available => "AVAILABLE",
            Self::Unavailable => "UNAVAILABLE",
            Self::Failed => "FAILED",
        }
    }
    fn has_raw_chain(self) -> bool {
        matches!(self, Self::Available)
    }
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct SharkChainNode {
    pub class_name: String,
    pub field_name: Option<String>,
    pub node_kind: String,
}
#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct ExternalSharkEvidenceInput {
    pub status: SharkStatus,
    pub evidence_digest: String,
    pub source_artifact_digest: String,
    pub chain: Vec<SharkChainNode>,
}

/// Builds strict external Shark status/chain evidence with no source candidates.
pub(crate) fn build_external_shark_evidence(
    input: &ExternalSharkEvidenceInput,
) -> Result<Evidence, String> {
    let evidence_digest = digest(&input.evidence_digest, "Shark evidence digest")?;
    let artifact_digest = digest(&input.source_artifact_digest, "Shark artifact digest")?;
    if input.chain.len() > MAX_CHAIN_NODES {
        return Err("Shark reference chain exceeds the 16-node bound".into());
    }
    if !input.status.has_raw_chain() && !input.chain.is_empty() {
        return Err("unavailable Shark status cannot carry raw chain nodes".into());
    }
    for node in &input.chain {
        text(&node.class_name, "Shark class name")?;
        text(&node.node_kind, "Shark node kind")?;
        if let Some(field) = &node.field_name {
            text(field, "Shark field name")?;
        }
    }
    let chain = input
        .chain
        .iter()
        .map(|node| {
            object([
                ("className", node.class_name.clone().into()),
                (
                    "fieldName",
                    node.field_name
                        .clone()
                        .map(Value::from)
                        .unwrap_or(Value::Null),
                ),
                ("nodeKind", node.node_kind.clone().into()),
            ])
        })
        .collect::<Vec<_>>();
    let payload = object([
        ("status", input.status.as_str().into()),
        ("evidenceDigest", evidence_digest.clone().into()),
        (
            "source",
            object([
                ("kind", "external-artifact".into()),
                ("artifactDigest", artifact_digest.into()),
            ]),
        ),
        (
            "coverage",
            object([
                ("rawDataAvailable", input.status.has_raw_chain().into()),
                ("chainNodesIncluded", (chain.len() as u64).into()),
                ("chainNodeLimit", (MAX_CHAIN_NODES as u64).into()),
            ]),
        ),
        ("chain", chain.into()),
        ("sourceCandidates", Value::Array(Vec::new())),
    ]);
    finish(
        format!("memory-leakcanary-shark:{evidence_digest}"),
        "memory-leakcanary-shark",
        "External LeakCanary Shark status and bounded chain metadata; no source candidates.",
        payload,
    )
}

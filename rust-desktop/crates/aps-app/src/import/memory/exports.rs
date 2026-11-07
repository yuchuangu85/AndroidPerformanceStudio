//! Bounded HPROF exports for the Memory Profiler File > Export menu.
//!
//! Every text/JSON export is built in memory under the shared 4 MiB export
//! budget and committed with the create-new/no-overwrite writer. The raw HPROF
//! copy streams the retained source bytes after re-verifying size and digest,
//! and cleans up a partial file. Standard HPROF, native heap, ART sample and
//! bitmap exports remain disabled rows because no adapter produces them.
use super::ImportWorkspace;
use super::state::ComparisonReport;
use crate::import::{Session, export};
use aps_contracts::SourceDigest;
use aps_json::{Limits, Map, Value};
use aps_memory::{
    ClassStats, InstanceDetail, InstanceField, InstancesPage, MemorySnapshot, ObjectReference,
};
use aps_ui_foundation::{Destination, TextKey};
use gpui_kit::prelude::*;
use std::path::{Path, PathBuf};

const EXPORT_ANALYSIS_VERSION: &str = "offline-hprof-viewer-v1";
const MAX_REPORT_ROWS: usize = 50;

/// One runnable File > Export row. Native heap, ART sample, standard HPROF and
/// bitmap exports are intentionally absent: no Rust adapter produces them.
#[derive(Clone, Copy, Debug, PartialEq, Eq, Hash)]
pub(crate) enum MemoryExportKind {
    RawHprof,
    HistogramCsv,
    SnapshotJson,
    ClassInstancesCsv,
    ObjectInvestigationJson,
    DiffCsv,
    InvestigationReport,
}

impl MemoryExportKind {
    pub(crate) fn default_filename(self) -> &'static str {
        match self {
            Self::RawHprof => "heap-raw.hprof",
            Self::HistogramCsv => "class-histogram.csv",
            Self::SnapshotJson => "heap-snapshot.json",
            Self::ClassInstancesCsv => "class-instances.csv",
            Self::ObjectInvestigationJson => "object-investigation.json",
            Self::DiffCsv => "class-diff.csv",
            Self::InvestigationReport => "memory-investigation.md",
        }
    }
}

// ---------------------------------------------------------------------------
// Bounded encoders (pure; unit-tested without a GPUI window).
// ---------------------------------------------------------------------------

fn object(entries: impl IntoIterator<Item = (&'static str, Value)>) -> Value {
    Value::Object(
        entries
            .into_iter()
            .map(|(key, value)| (key.to_owned(), value))
            .collect::<Map<_, _>>(),
    )
}

fn json_limits() -> Limits {
    Limits {
        max_bytes: export::MAX_EXPORT_BYTES as u64,
        max_nodes: 2_000_000,
        max_string_bytes: 1024 * 1024,
        max_collection_items: 1_000_000,
        max_depth: 32,
    }
}

fn encode_json(value: &Value, message: &str) -> Result<Vec<u8>, String> {
    aps_json::to_vec(value, json_limits()).map_err(|_| message.to_owned())
}

fn push_text(output: &mut String, text: &str) -> Result<(), String> {
    if output.len().saturating_add(text.len()) > export::MAX_EXPORT_BYTES {
        return Err("Export exceeds the 4 MiB byte budget".into());
    }
    output.push_str(text);
    Ok(())
}

fn push_line(output: &mut String, line: &str) -> Result<(), String> {
    push_text(output, line)?;
    push_text(output, "\n")
}

/// Quotes one CSV cell and neutralizes spreadsheet formulas exactly like the
/// shared current-page CSV writer (JSON stays the lossless export).
fn push_csv_field(output: &mut String, value: &str) -> Result<(), String> {
    let formula = value.starts_with(['=', '+', '-', '@', '\t', '\r'])
        && !value.parse::<f64>().is_ok_and(f64::is_finite);
    push_text(output, "\"")?;
    if formula {
        push_text(output, "'")?;
    }
    let mut from = 0;
    for (index, ch) in value.char_indices() {
        if ch == '"' {
            push_text(output, &value[from..index])?;
            push_text(output, "\"\"")?;
            from = index + 1;
        }
    }
    push_text(output, &value[from..])?;
    push_text(output, "\"")
}

fn push_csv_row(output: &mut String, values: &[String]) -> Result<(), String> {
    for (index, value) in values.iter().enumerate() {
        if index != 0 {
            push_text(output, ",")?;
        }
        push_csv_field(output, value)?;
    }
    push_text(output, "\r\n")
}

fn finish_text(output: String) -> Result<Vec<u8>, String> {
    if output.len() > export::MAX_EXPORT_BYTES {
        return Err("Export exceeds the 4 MiB byte budget".into());
    }
    Ok(output.into_bytes())
}

fn optional_number(value: Option<u64>) -> String {
    value.map_or_else(String::new, |value| value.to_string())
}

/// Complete class histogram CSV, matching the Compose export columns.
pub(super) fn histogram_csv(snapshot: &MemorySnapshot) -> Result<Vec<u8>, String> {
    let mut output = String::new();
    push_csv_row(
        &mut output,
        &[
            "className".to_owned(),
            "instanceCount".to_owned(),
            "shallowSizeBytes".to_owned(),
            "retainedSizeBytes".to_owned(),
        ],
    )?;
    for class in &snapshot.classes {
        push_csv_row(
            &mut output,
            &[
                class.class_name.clone(),
                class.instance_count.to_string(),
                class.shallow_size.to_string(),
                optional_number(class.retained_size),
            ],
        )?;
    }
    finish_text(output)
}

/// Metadata + summary + complete class list, bounded by the JSON limits.
pub(super) fn snapshot_json(snapshot: &MemorySnapshot) -> Result<Vec<u8>, String> {
    let classes: Vec<Value> = snapshot
        .classes
        .iter()
        .map(|class| {
            object([
                ("className", class.class_name.clone().into()),
                ("instanceCount", class.instance_count.into()),
                ("shallowSizeBytes", class.shallow_size.into()),
                (
                    "retainedSizeBytes",
                    class
                        .retained_size
                        .map_or(Value::Null, |value| value.into()),
                ),
                (
                    "obfuscatedClassName",
                    class
                        .obfuscated_class_name
                        .clone()
                        .map_or(Value::Null, Value::from),
                ),
                (
                    "nativeSizeBytes",
                    class.native_size.map_or(Value::Null, |value| value.into()),
                ),
                (
                    "totalCount",
                    class.total_count.map_or(Value::Null, |value| value.into()),
                ),
                (
                    "hierarchyDepth",
                    class
                        .hierarchy_depth
                        .map_or(Value::Null, |value| value.into()),
                ),
                (
                    "heapNames",
                    Value::Array(
                        class
                            .heap_names
                            .iter()
                            .map(|heap| Value::from(heap.as_str()))
                            .collect(),
                    ),
                ),
            ])
        })
        .collect();
    let segments: Vec<Value> = snapshot
        .summary
        .heap_segments
        .iter()
        .map(|segment| {
            object([
                ("name", segment.name.clone().into()),
                ("sizeBytes", segment.size_bytes.into()),
                ("objectCount", segment.object_count.into()),
            ])
        })
        .collect();
    let payload = object([
        ("format", "aps-memory-snapshot-v1".into()),
        ("analysisVersion", EXPORT_ANALYSIS_VERSION.into()),
        ("sourceFileDigest", snapshot.source_digest.clone().into()),
        (
            "limitation",
            "Point-in-time heap evidence; no allocation timeline or cross-dump object identity. Class retained sizes may overlap."
                .into(),
        ),
        (
            "metadata",
            object([
                ("format", snapshot.metadata.format.clone().into()),
                ("idSize", snapshot.metadata.id_size.into()),
                (
                    "timestampMillis",
                    snapshot
                        .metadata
                        .timestamp_millis
                        .map_or(Value::Null, |value| value.into()),
                ),
                (
                    "heaps",
                    Value::Array(
                        snapshot
                            .metadata
                            .heaps
                            .iter()
                            .map(|heap| Value::from(heap.as_str()))
                            .collect(),
                    ),
                ),
                ("classCount", snapshot.metadata.class_count.into()),
                ("instanceCount", snapshot.metadata.instance_count.into()),
                (
                    "objectArrayCount",
                    snapshot.metadata.object_array_count.into(),
                ),
                (
                    "primitiveArrayCount",
                    snapshot.metadata.primitive_array_count.into(),
                ),
                ("rootCount", snapshot.metadata.root_count.into()),
                (
                    "warnings",
                    Value::Array(
                        snapshot
                            .metadata
                            .warnings
                            .iter()
                            .map(|warning| Value::from(warning.as_str()))
                            .collect(),
                    ),
                ),
                (
                    "cacheStatus",
                    snapshot
                        .metadata
                        .cache_status
                        .clone()
                        .map_or(Value::Null, Value::from),
                ),
            ]),
        ),
        (
            "summary",
            object([
                ("objectCount", snapshot.summary.object_count.into()),
                ("shallowSizeBytes", snapshot.summary.shallow_size.into()),
                ("classCount", snapshot.summary.class_count.into()),
                ("heapSegments", Value::Array(segments)),
            ]),
        ),
        ("classes", Value::Array(classes)),
    ]);
    encode_json(&payload, "Snapshot export exceeds the 4 MiB JSON budget")
}

/// CSV for the currently published instance page. The leading comment row
/// states that this is a bounded page, not the complete instance set.
pub(super) fn class_instances_csv(
    page: &InstancesPage,
    snapshot_digest: &str,
    selection: &str,
) -> Result<Vec<u8>, String> {
    let mut output = String::new();
    push_line(
        &mut output,
        &format!(
            "# bounded class instance page; snapshotDigest={snapshot_digest}; selection={selection}; offset={}; rows={}; total={}",
            page.offset,
            page.rows.len(),
            page.total
        ),
    )?;
    push_csv_row(
        &mut output,
        &[
            "objectId".to_owned(),
            "className".to_owned(),
            "shallowSizeBytes".to_owned(),
            "retainedSizeBytes".to_owned(),
        ],
    )?;
    for row in &page.rows {
        push_csv_row(
            &mut output,
            &[
                format!("0x{:x}", row.object_id),
                row.class_name
                    .clone()
                    .unwrap_or_else(|| selection.to_owned()),
                if row.shallow_size_known {
                    row.shallow_size.to_string()
                } else {
                    String::new()
                },
                optional_number(row.retained_size),
            ],
        )?;
    }
    finish_text(output)
}

fn field_json(field: &InstanceField) -> Value {
    object([
        ("name", field.name.clone().into()),
        ("displayValue", field.display_value.clone().into()),
        (
            "targetObjectId",
            field
                .target_object_id
                .map_or(Value::Null, |value| value.into()),
        ),
        (
            "targetClassName",
            field
                .target_class_name
                .clone()
                .map_or(Value::Null, Value::from),
        ),
    ])
}

fn reference_json(reference: &ObjectReference) -> Value {
    object([
        ("targetObjectId", reference.object_id.into()),
        ("targetClassName", reference.class_name.clone().into()),
    ])
}

/// Object investigation evidence for the current inspector detail.
pub(super) fn object_investigation_json(
    detail: &InstanceDetail,
    snapshot: &MemorySnapshot,
    mapping_loaded: bool,
) -> Result<Vec<u8>, String> {
    let fields: Vec<Value> = detail.fields.iter().map(field_json).collect();
    let references: Vec<Value> = detail.references.iter().map(field_json).collect();
    let chain: Vec<Value> = detail.reference_chain.iter().map(reference_json).collect();
    let payload = object([
        ("analysisVersion", EXPORT_ANALYSIS_VERSION.into()),
        ("sourceFileDigest", snapshot.source_digest.clone().into()),
        ("mappingLoaded", mapping_loaded.into()),
        (
            "limitation",
            "Object identity is local to this snapshot; retention is not proof of a leak.".into(),
        ),
        ("objectId", detail.object_id.into()),
        ("className", detail.class_name.clone().into()),
        (
            "shallowSize",
            if detail.shallow_size_known {
                detail.shallow_size.into()
            } else {
                Value::Null
            },
        ),
        (
            "nativeSize",
            detail.native_size.map_or(Value::Null, |value| value.into()),
        ),
        (
            "retainedSize",
            detail
                .retained_size
                .map_or(Value::Null, |value| value.into()),
        ),
        (
            "depth",
            detail.depth.map_or(Value::Null, |value| value.into()),
        ),
        ("isArray", detail.is_array.into()),
        (
            "elementCount",
            detail
                .element_count
                .map_or(Value::Null, |value| value.into()),
        ),
        ("omitted", detail.omitted.into()),
        ("fields", Value::Array(fields)),
        ("references", Value::Array(references)),
        ("referenceChain", Value::Array(chain)),
    ]);
    encode_json(
        &payload,
        "Object investigation export exceeds the 4 MiB JSON budget",
    )
}

fn signed(value: i128) -> String {
    value.to_string()
}

/// Class-level diff CSV. The leading comment row carries the A/B identity; the
/// table itself matches the Compose diff columns.
pub(super) fn diff_csv(report: &ComparisonReport) -> Result<Vec<u8>, String> {
    let mut output = String::new();
    push_line(
        &mut output,
        &format!(
            "# class-level diff; A={}; A digest={}; B={}; B digest={}; analysisVersion={EXPORT_ANALYSIS_VERSION}; limitation=class-name matching only; no cross-dump object identity",
            report.a_label, report.a_digest, report.b_label, report.b_digest
        ),
    )?;
    push_csv_row(
        &mut output,
        &[
            "className".to_owned(),
            "beforeCount".to_owned(),
            "afterCount".to_owned(),
            "countDelta".to_owned(),
            "beforeRetainedSizeBytes".to_owned(),
            "afterRetainedSizeBytes".to_owned(),
            "retainedSizeDeltaBytes".to_owned(),
        ],
    )?;
    for row in &report.rows {
        let count_delta = i128::from(row.b_count) - i128::from(row.a_count);
        let retained_delta = row
            .b_retained
            .zip(row.a_retained)
            .map(|(after, before)| i128::from(after) - i128::from(before))
            .map_or_else(String::new, signed);
        push_csv_row(
            &mut output,
            &[
                row.class_name.clone(),
                row.a_count.to_string(),
                row.b_count.to_string(),
                signed(count_delta),
                optional_number(row.a_retained),
                optional_number(row.b_retained),
                retained_delta,
            ],
        )?;
    }
    finish_text(output)
}

fn markdown_cell(value: &str) -> String {
    value.replace('|', "\\|")
}

fn retained_total(snapshot: &MemorySnapshot) -> u64 {
    snapshot
        .classes
        .iter()
        .filter_map(|class| class.retained_size)
        .sum()
}

fn top_classes<'a>(snapshot: &'a MemorySnapshot) -> Vec<&'a ClassStats> {
    let mut classes: Vec<&ClassStats> = snapshot.classes.iter().collect();
    classes.sort_by(|left, right| {
        right
            .retained_size
            .unwrap_or(0)
            .cmp(&left.retained_size.unwrap_or(0))
            .then_with(|| left.class_name.cmp(&right.class_name))
    });
    classes.truncate(20);
    classes
}

/// Investigation report Markdown: snapshot evidence, findings, top classes and
/// the optional class-level diff.
pub(super) fn investigation_report_markdown(
    snapshot: &MemorySnapshot,
    report: Option<&ComparisonReport>,
    mapping_loaded: bool,
    mapping_entries: usize,
) -> Result<Vec<u8>, String> {
    let mut output = String::new();
    push_line(&mut output, "# Memory Profiler Investigation Report")?;
    push_line(&mut output, "")?;
    push_line(
        &mut output,
        &format!("- Analysis version: {EXPORT_ANALYSIS_VERSION}"),
    )?;
    push_line(
        &mut output,
        &format!("- Source digest: {}", snapshot.source_digest),
    )?;
    push_line(
        &mut output,
        &if mapping_loaded {
            format!("- Mapping: loaded ({mapping_entries} class mappings)")
        } else {
            "- Mapping: not loaded".to_owned()
        },
    )?;
    push_line(
        &mut output,
        &format!(
            "- Format: {} ({} byte ids)",
            snapshot.metadata.format, snapshot.metadata.id_size
        ),
    )?;
    push_line(
        &mut output,
        &format!(
            "- Classes: {}; objects: {}; shallow size: {} bytes; retained (class sums): {} bytes",
            snapshot.metadata.class_count,
            snapshot.metadata.instance_count,
            snapshot.summary.shallow_size,
            retained_total(snapshot)
        ),
    )?;
    push_line(
        &mut output,
        &format!("- Heaps: {}", snapshot.metadata.heaps.join(", ")),
    )?;
    push_line(&mut output, "")?;
    push_line(&mut output, "## Evidence limitations")?;
    push_line(&mut output, "")?;
    push_line(
        &mut output,
        "This report describes one heap-dump evidence boundary. Object IDs are not stable across snapshots, retained paths are evidence of retention rather than proof of a leak, and class retained sizes may overlap.",
    )?;
    push_line(&mut output, "")?;
    push_line(&mut output, "## Findings")?;
    push_line(&mut output, "")?;
    push_line(
        &mut output,
        &format!(
            "- Leak suspects: {} (heuristics; manual verification required)",
            snapshot.findings.leak_suspects.total
        ),
    )?;
    for suspect in snapshot.findings.leak_suspects.rows.iter().take(10) {
        push_line(
            &mut output,
            &format!(
                "  - {}: {} ({} instances, retained {})",
                markdown_cell(&suspect.class_name),
                markdown_cell(&suspect.reason),
                suspect.instance_count,
                suspect.retained_size.map_or_else(
                    || "unavailable".to_owned(),
                    |value| format!("{value} bytes")
                )
            ),
        )?;
    }
    push_line(
        &mut output,
        &format!(
            "- Activity/Fragment lifecycle candidates: {}",
            snapshot.findings.activities.total
        ),
    )?;
    for activity in snapshot.findings.activities.rows.iter().take(10) {
        push_line(
            &mut output,
            &format!(
                "  - {}: {} live, {} destroyed, retained {}",
                markdown_cell(&activity.class_name),
                activity.live_instance_count,
                activity.destroyed_instance_count,
                activity.retained_size.map_or_else(
                    || "unavailable".to_owned(),
                    |value| format!("{value} bytes")
                )
            ),
        )?;
    }
    push_line(
        &mut output,
        &format!("- Lifecycle objects: {}", snapshot.findings.lifecycle.total),
    )?;
    for object in snapshot.findings.lifecycle.rows.iter().take(10) {
        push_line(
            &mut output,
            &format!(
                "  - {} 0x{:x} (heap {})",
                markdown_cell(&object.class_name),
                object.object_id,
                object.heap_name.as_deref().unwrap_or("unknown")
            ),
        )?;
    }
    push_line(
        &mut output,
        &format!("- Bitmap instances: {}", snapshot.findings.bitmaps.total),
    )?;
    for bitmap in snapshot.findings.bitmaps.rows.iter().take(10) {
        push_line(
            &mut output,
            &format!(
                "  - {} 0x{:x}: {}x{}, estimated {} bytes",
                markdown_cell(&bitmap.class_name),
                bitmap.object_id,
                bitmap
                    .width
                    .map_or_else(|| "?".to_owned(), |value| value.to_string()),
                bitmap
                    .height
                    .map_or_else(|| "?".to_owned(), |value| value.to_string()),
                bitmap
                    .estimated_pixel_bytes
                    .map_or_else(|| "unavailable".to_owned(), |value| value.to_string())
            ),
        )?;
    }
    push_line(&mut output, "")?;
    push_line(&mut output, "## Top classes by retained size")?;
    push_line(&mut output, "")?;
    push_line(&mut output, "| Class | Count | Shallow | Retained |")?;
    push_line(&mut output, "| --- | ---: | ---: | ---: |")?;
    for class in top_classes(snapshot) {
        push_line(
            &mut output,
            &format!(
                "| {} | {} | {} | {} |",
                markdown_cell(&class.class_name),
                class.instance_count,
                class.shallow_size,
                class
                    .retained_size
                    .map_or_else(|| "unavailable".to_owned(), |value| value.to_string())
            ),
        )?;
    }
    if let Some(report) = report {
        push_line(&mut output, "")?;
        push_line(&mut output, "## Class-level heap diff")?;
        push_line(&mut output, "")?;
        push_line(
            &mut output,
            &format!(
                "- Snapshot A: {} (digest {})",
                markdown_cell(&report.a_label),
                report.a_digest
            ),
        )?;
        push_line(
            &mut output,
            &format!(
                "- Snapshot B: {} (digest {})",
                markdown_cell(&report.b_label),
                report.b_digest
            ),
        )?;
        push_line(&mut output, "")?;
        push_line(
            &mut output,
            "The diff is class-level; it does not establish stable object identity across snapshots.",
        )?;
        push_line(&mut output, "")?;
        push_line(&mut output, "| Class | Before | After | Delta |")?;
        push_line(&mut output, "| --- | ---: | ---: | ---: |")?;
        for row in report.rows.iter().take(MAX_REPORT_ROWS) {
            push_line(
                &mut output,
                &format!(
                    "| {} | {} | {} | {} |",
                    markdown_cell(&row.class_name),
                    row.a_count,
                    row.b_count,
                    signed(i128::from(row.b_count) - i128::from(row.a_count))
                ),
            )?;
        }
    }
    if !snapshot.metadata.warnings.is_empty() {
        push_line(&mut output, "")?;
        push_line(&mut output, "## Warnings")?;
        push_line(&mut output, "")?;
        for warning in snapshot.metadata.warnings.iter().take(20) {
            push_line(&mut output, &format!("- {}", markdown_cell(warning)))?;
        }
    }
    finish_text(output)
}

// ---------------------------------------------------------------------------
// Filesystem commits.
// ---------------------------------------------------------------------------

/// Streams the retained HPROF source to a new file after re-verifying size and
/// digest, then verifies the copy. Never overwrites and never leaves a partial
/// file behind.
fn write_raw_hprof_copy(source: &Path, target: &Path, expected_digest: &str) -> Result<(), String> {
    let metadata = std::fs::metadata(source)
        .map_err(|_| "Cannot inspect the retained HPROF source".to_owned())?;
    if !metadata.is_file() || metadata.len() > aps_memory::MAX_INPUT_BYTES {
        return Err("The retained HPROF source is missing or exceeds the import budget".into());
    }
    let digest = SourceDigest::hash_file(source, aps_memory::MAX_INPUT_BYTES)
        .map_err(|_| "Cannot re-verify the retained HPROF source digest".to_owned())?;
    if digest.to_hex() != expected_digest {
        return Err(
            "The retained HPROF bytes changed since the snapshot was opened; export aborted".into(),
        );
    }
    if !target.is_absolute() {
        return Err("Export requires an absolute destination within its byte budget".into());
    }
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut output = options.open(target).map_err(|_| {
        "Export destination already exists or cannot be created; select a new filename".to_owned()
    })?;
    let mut input = std::fs::File::open(source)
        .map_err(|_| "Cannot reopen the retained HPROF source".to_owned())?;
    let copied = std::io::copy(&mut input, &mut output).and_then(|_| output.sync_all());
    if copied.is_err() {
        drop(output);
        let _ = std::fs::remove_file(target);
        return Err("Cannot finish selected export; partial output cleanup was attempted".into());
    }
    drop(output);
    let copied_digest = SourceDigest::hash_file(target, aps_memory::MAX_INPUT_BYTES)
        .map_err(|_| "Cannot verify the exported HPROF copy".to_owned())?;
    if copied_digest.to_hex() != expected_digest {
        let _ = std::fs::remove_file(target);
        return Err("The exported HPROF copy does not match the verified snapshot digest".into());
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// Menu availability and export flow.
// ---------------------------------------------------------------------------

impl ImportWorkspace {
    /// Authoritative export availability for the live workspace state.
    pub(crate) fn memory_export_enabled(&self, kind: MemoryExportKind) -> bool {
        if self.kind != Destination::Memory
            || self.busy
            || self.export_busy
            || self.memory_state.loading
        {
            return false;
        }
        let loaded = self.memory_state.snapshot.is_some();
        match kind {
            MemoryExportKind::RawHprof => {
                loaded && self.path.is_some() && matches!(self.session, Some(Session::Memory(_)))
            }
            MemoryExportKind::HistogramCsv
            | MemoryExportKind::SnapshotJson
            | MemoryExportKind::InvestigationReport => loaded,
            MemoryExportKind::ClassInstancesCsv => loaded && self.memory_state.instances.is_some(),
            MemoryExportKind::ObjectInvestigationJson => {
                loaded && self.memory_state.detail.is_some()
            }
            MemoryExportKind::DiffCsv => loaded && self.memory_state.comparison.is_some(),
        }
    }

    /// Prompts for a destination and commits one bounded export on the
    /// background executor. Notices and failures land in the memory status bar.
    pub(crate) fn memory_export(&mut self, kind: MemoryExportKind, cx: &mut Context<Self>) {
        if !self.memory_export_enabled(kind) {
            return;
        }
        let language = self.language;
        let directory = self
            .path
            .as_ref()
            .and_then(|path| path.parent())
            .map(Path::to_path_buf)
            .unwrap_or_else(|| PathBuf::from("."));
        let source_path = self.path.clone();
        let session = self.session.clone();
        let snapshot = self.memory_state.snapshot.clone();
        let instances = self.memory_state.instances.clone();
        let detail = self.memory_state.detail.clone();
        let comparison = self.memory_state.comparison.clone();
        let selection = self
            .memory_state
            .selected_class_label
            .clone()
            .or_else(|| self.memory_state.selected_class_name.clone())
            .unwrap_or_else(|| "class".to_owned());
        let mapping_loaded = self.memory_state.mapping_loaded;
        let mapping_entries = self.memory_state.mapping_entries;
        self.export_busy = true;
        self.export_generation += 1;
        let generation = self.export_generation;
        self.memory_state.error = None;
        self.memory_state.notice = None;
        let picker = cx.prompt_for_new_path(&directory, Some(kind.default_filename()));
        cx.spawn(async move |this, cx| {
            let chosen = picker.await;
            let Some(target) = this
                .update(cx, |this, cx| {
                    if this.export_generation != generation {
                        return None;
                    }
                    match chosen {
                        Ok(Ok(Some(path))) => Some(path),
                        Ok(Err(_)) => {
                            this.memory_state.error =
                                Some(language.text(TextKey::ImportUiSavePickerFailed).to_owned());
                            this.export_busy = false;
                            cx.notify();
                            None
                        }
                        _ => {
                            this.export_busy = false;
                            cx.notify();
                            None
                        }
                    }
                })
                .ok()
                .flatten()
            else {
                return;
            };
            let result = cx
                .background_executor()
                .spawn(async move {
                    match kind {
                        MemoryExportKind::RawHprof => {
                            let session = session.ok_or_else(|| {
                                "The active snapshot has no memory session".to_owned()
                            })?;
                            let Session::Memory(session) = session else {
                                return Err(
                                    "The active snapshot is not an HPROF session".to_owned()
                                );
                            };
                            let digest = {
                                let session = session.lock().map_err(|_| {
                                    "The active memory session is unavailable".to_owned()
                                })?;
                                session.source_digest().to_owned()
                            };
                            let source = source_path.ok_or_else(|| {
                                "The active snapshot has no retained HPROF source path".to_owned()
                            })?;
                            write_raw_hprof_copy(&source, &target, &digest)?;
                        }
                        _ => {
                            let snapshot = snapshot.ok_or_else(|| {
                                "The active snapshot is unavailable for export".to_owned()
                            })?;
                            let bytes = match kind {
                                MemoryExportKind::HistogramCsv => histogram_csv(&snapshot)?,
                                MemoryExportKind::SnapshotJson => snapshot_json(&snapshot)?,
                                MemoryExportKind::ClassInstancesCsv => {
                                    let instances = instances.ok_or_else(|| {
                                        "Export the instance page selection first".to_owned()
                                    })?;
                                    class_instances_csv(
                                        &instances,
                                        &snapshot.source_digest,
                                        &selection,
                                    )?
                                }
                                MemoryExportKind::ObjectInvestigationJson => {
                                    let detail = detail.ok_or_else(|| {
                                        "Select an object in the inspector first".to_owned()
                                    })?;
                                    object_investigation_json(&detail, &snapshot, mapping_loaded)?
                                }
                                MemoryExportKind::DiffCsv => {
                                    let comparison = comparison.ok_or_else(|| {
                                        "Compare two snapshots before exporting the diff".to_owned()
                                    })?;
                                    diff_csv(&comparison)?
                                }
                                MemoryExportKind::InvestigationReport => {
                                    investigation_report_markdown(
                                        &snapshot,
                                        comparison.as_deref(),
                                        mapping_loaded,
                                        mapping_entries,
                                    )?
                                }
                                MemoryExportKind::RawHprof => unreachable!("handled above"),
                            };
                            export::write_new_file(&target, &bytes)
                                .map_err(|error| error.to_string())?;
                        }
                    }
                    Ok::<PathBuf, String>(target)
                })
                .await;
            this.update(cx, |this, cx| {
                if this.export_generation != generation {
                    return;
                }
                this.export_busy = false;
                match result {
                    Ok(path) => {
                        this.memory_state.notice = Some(language.format(
                            TextKey::ImportUiSavedPage,
                            &[("path", &path.display().to_string())],
                        ));
                        this.memory_state.refresh_export_availability();
                    }
                    Err(error) => this.memory_state.error = Some(error),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_memory::{
        ActivityLeak, BitmapInstance, ClassStats, FindingsPage, FindingsSnapshot, HeapSegment,
        HeapSummary, LeakSuspect, LifecycleLeakObject, MemorySnapshot, SnapshotMetadata,
    };

    fn snapshot() -> MemorySnapshot {
        MemorySnapshot {
            source_digest: "a".repeat(64),
            metadata: SnapshotMetadata {
                format: "JAVA PROFILE 1.0.3".into(),
                id_size: 8,
                timestamp_millis: Some(1),
                heaps: vec!["App".into()],
                class_count: 2,
                instance_count: 3,
                object_array_count: 1,
                primitive_array_count: 1,
                root_count: 1,
                warnings: vec!["truncated record".into()],
                cache_status: Some("disabled".into()),
            },
            summary: HeapSummary {
                shallow_size: 4096,
                object_count: 3,
                class_count: 2,
                heap_segments: vec![HeapSegment {
                    name: "App".into(),
                    size_bytes: 4096,
                    object_count: 3,
                }],
            },
            classes: vec![
                ClassStats {
                    class_name: "com.example.C".into(),
                    instance_count: 2,
                    shallow_size: 1024,
                    retained_size: Some(2048),
                    obfuscated_class_name: Some("a.b.C".into()),
                    ..ClassStats::default()
                },
                ClassStats {
                    class_name: "java.lang.String".into(),
                    instance_count: 1,
                    shallow_size: 32,
                    retained_size: Some(64),
                    ..ClassStats::default()
                },
            ],
            per_heap_classes: Default::default(),
            findings: FindingsSnapshot {
                leak_suspects: FindingsPage {
                    total: 1,
                    rows: vec![LeakSuspect {
                        class_name: "com.example.C".into(),
                        reason: "static field retains a Context".into(),
                        instance_count: 1,
                        retained_size: Some(2048),
                        activity_or_fragment_leak: false,
                    }],
                },
                bitmaps: FindingsPage {
                    total: 1,
                    rows: vec![BitmapInstance {
                        object_id: 7,
                        class_name: "android.graphics.Bitmap".into(),
                        width: Some(10),
                        height: Some(20),
                        estimated_pixel_bytes: Some(800),
                        retained_size: Some(64),
                        bitmap_id: Some(1),
                        bitmap_source_id: None,
                        native_size_bytes: None,
                    }],
                },
                activities: FindingsPage {
                    total: 1,
                    rows: vec![ActivityLeak {
                        class_name: "com.example.Main".into(),
                        live_instance_count: 2,
                        destroyed_instance_count: 1,
                        retained_size: Some(100),
                    }],
                },
                lifecycle: FindingsPage {
                    total: 1,
                    rows: vec![LifecycleLeakObject {
                        object_id: 9,
                        class_name: "com.example.Main".into(),
                        heap_name: Some("App".into()),
                    }],
                },
            },
        }
    }

    #[test]
    fn histogram_csv_has_complete_classes_and_kotlin_columns() {
        let csv = String::from_utf8(histogram_csv(&snapshot()).unwrap()).unwrap();
        let lines: Vec<_> = csv.lines().collect();
        assert_eq!(
            lines[0],
            "\"className\",\"instanceCount\",\"shallowSizeBytes\",\"retainedSizeBytes\""
        );
        assert_eq!(lines.len(), 3);
        assert!(lines[1].contains("\"com.example.C\",\"2\",\"1024\",\"2048\""));
    }

    #[test]
    fn snapshot_json_is_bounded_and_keeps_identity() {
        let bytes = snapshot_json(&snapshot()).unwrap();
        assert!(bytes.len() <= export::MAX_EXPORT_BYTES);
        let json = aps_json::parse_slice(&bytes, Limits::default()).unwrap();
        assert_eq!(
            json["analysisVersion"].as_str(),
            Some(EXPORT_ANALYSIS_VERSION)
        );
        assert_eq!(json["metadata"]["idSize"].as_u64(), Some(8));
        assert_eq!(json["summary"]["objectCount"].as_u64(), Some(3));
        assert_eq!(
            json["classes"].as_array().map(Vec::len),
            Some(2),
            "complete class list is exported"
        );
        assert_eq!(
            json["classes"][0]["className"].as_str(),
            Some("com.example.C")
        );
    }

    #[test]
    fn snapshot_json_fails_instead_of_truncating_over_budget() {
        let mut snapshot = snapshot();
        snapshot.classes = (0..50_000)
            .map(|index| ClassStats {
                class_name: format!("com.example.Class{index}{}", "x".repeat(48)),
                instance_count: 1,
                shallow_size: 1,
                ..ClassStats::default()
            })
            .collect();
        assert!(snapshot_json(&snapshot).is_err());
    }

    #[test]
    fn class_instances_csv_states_the_bounded_page() {
        let page = aps_memory::InstancesPage {
            total: 42,
            offset: 500,
            rows: vec![aps_memory::InstanceRow {
                object_id: 0xFFFF_FFFF_FFFF_FFFF,
                index: 1,
                shallow_size: 16,
                retained_size: None,
                depth: Some(1),
                reachable: true,
                native_size: None,
                shallow_size_known: true,
                class_name: Some("com.example.C".into()),
                represented_class_name: None,
            }],
        };
        let csv = String::from_utf8(
            class_instances_csv(&page, &"a".repeat(64), "com.example.C").unwrap(),
        )
        .unwrap();
        let mut lines = csv.lines();
        let header = lines.next().unwrap();
        assert!(header.starts_with("# bounded class instance page"));
        assert!(header.contains("offset=500"));
        assert!(header.contains("rows=1"));
        assert!(header.contains("total=42"));
        assert_eq!(
            lines.next().unwrap(),
            "\"objectId\",\"className\",\"shallowSizeBytes\",\"retainedSizeBytes\""
        );
        assert!(lines.next().unwrap().contains("\"0xffffffffffffffff\""));
    }

    #[test]
    fn object_investigation_json_keeps_evidence_and_null_unavailable_metrics() {
        let snapshot = snapshot();
        let detail = InstanceDetail {
            object_id: 7,
            class_name: "com.example.C".into(),
            shallow_size: 16,
            retained_size: None,
            depth: Some(2),
            is_array: false,
            element_count: None,
            array_start: 0,
            array_page_size: 200,
            fields: vec![InstanceField {
                name: "next".into(),
                display_value: "0x8 · com.example.C".into(),
                target_object_id: Some(8),
                target_class_name: Some("com.example.C".into()),
                ..InstanceField::default()
            }],
            reference_chain: vec![ObjectReference {
                object_id: 8,
                class_name: "com.example.C".into(),
            }],
            references: Vec::new(),
            shallow_size_known: true,
            native_size: None,
            value_kind: aps_memory::HeapValueKind::Object,
            represented_class_name: None,
            string_preview: None,
            retained_native_size: None,
            omitted: false,
        };
        let bytes = object_investigation_json(&detail, &snapshot, true).unwrap();
        let json = aps_json::parse_slice(&bytes, Limits::default()).unwrap();
        assert_eq!(json["objectId"].as_u64(), Some(7));
        assert_eq!(json["retainedSize"], Value::Null);
        assert_eq!(json["mappingLoaded"].as_bool(), Some(true));
        assert_eq!(json["fields"][0]["name"].as_str(), Some("next"));
        assert_eq!(
            json["referenceChain"][0]["targetObjectId"].as_u64(),
            Some(8)
        );
    }

    #[test]
    fn diff_csv_carries_identity_and_signed_deltas() {
        let report = ComparisonReport {
            a_label: "before.hprof".into(),
            b_label: "after.hprof".into(),
            a_digest: "a".repeat(64),
            b_digest: "b".repeat(64),
            rows: vec![super::super::state::ClassifierDelta {
                class_name: "com.example.C".into(),
                a_count: 2,
                b_count: 5,
                a_retained: Some(10),
                b_retained: Some(25),
            }],
        };
        let csv = String::from_utf8(diff_csv(&report).unwrap()).unwrap();
        let mut lines = csv.lines();
        let identity = lines.next().unwrap();
        assert!(identity.contains("A=before.hprof"));
        assert!(identity.contains("B=after.hprof"));
        assert!(lines.next().unwrap().starts_with("\"className\""));
        let row = lines.next().unwrap();
        assert!(row.contains("\"countDelta\",\"3\"") || row.contains("\"3\""));
        assert!(row.contains("\"15\""));
    }

    #[test]
    fn investigation_report_contains_findings_top_classes_and_diff() {
        let snapshot = snapshot();
        let report = ComparisonReport {
            a_label: "before.hprof".into(),
            b_label: "after.hprof".into(),
            a_digest: "a".repeat(64),
            b_digest: "b".repeat(64),
            rows: Vec::new(),
        };
        let markdown = String::from_utf8(
            investigation_report_markdown(&snapshot, Some(&report), true, 3).unwrap(),
        )
        .unwrap();
        assert!(markdown.starts_with("# Memory Profiler Investigation Report"));
        assert!(markdown.contains("- Mapping: loaded (3 class mappings)"));
        assert!(markdown.contains("## Findings"));
        assert!(markdown.contains("Leak suspects: 1"));
        assert!(markdown.contains("## Top classes by retained size"));
        assert!(markdown.contains("| com.example.C | 2 | 1024 | 2048 |"));
        assert!(markdown.contains("## Class-level heap diff"));
    }

    #[test]
    fn markdown_cells_escape_table_pipes() {
        let mut snapshot = snapshot();
        snapshot.classes[0].class_name = "weird|Name".into();
        let markdown =
            String::from_utf8(investigation_report_markdown(&snapshot, None, false, 0).unwrap())
                .unwrap();
        assert!(markdown.contains("weird\\|Name"));
        assert!(markdown.contains("- Mapping: not loaded"));
    }
}

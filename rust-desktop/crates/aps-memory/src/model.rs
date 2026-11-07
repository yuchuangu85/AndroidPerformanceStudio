//! Typed, bounded Memory Profiler domain models.
//!
//! Every value here is plain data decoded from the isolated HPROF worker. No
//! heap graph, raw object field payloads, or engine handles cross this module;
//! unavailable evidence is represented as None rather than a fabricated zero.

use crate::MemoryError;
use aps_hprof_engine::wire::Value;
use std::collections::BTreeMap;

fn invalid() -> MemoryError {
    MemoryError::InvalidResponse
}

fn lookup<'a>(value: &'a Value, key: &str) -> Option<&'a Value> {
    value.as_map().ok()?.get(key)
}

fn required<'a>(value: &'a Value, key: &str) -> Result<&'a Value, MemoryError> {
    lookup(value, key).ok_or_else(invalid)
}

fn as_u64(value: &Value) -> Result<u64, MemoryError> {
    u64::try_from(value.as_int().map_err(|_| invalid())?).map_err(|_| invalid())
}

fn as_u32(value: &Value) -> Result<u32, MemoryError> {
    u32::try_from(as_u64(value)?).map_err(|_| invalid())
}

/// HPROF object identifiers are unsigned values carried as signed integers by
/// the engine; reinterpret the bit pattern instead of rejecting large ids.
fn as_object_id(value: &Value) -> Result<u64, MemoryError> {
    Ok(value.as_int().map_err(|_| invalid())? as u64)
}

fn as_string(value: &Value) -> Result<String, MemoryError> {
    value.as_str().map(str::to_owned).map_err(|_| invalid())
}

fn as_bool(value: &Value) -> Result<bool, MemoryError> {
    value.as_bool().map_err(|_| invalid())
}

fn required_u64(value: &Value, key: &str) -> Result<u64, MemoryError> {
    as_u64(required(value, key)?)
}

fn required_object_id(value: &Value, key: &str) -> Result<u64, MemoryError> {
    as_object_id(required(value, key)?)
}

fn required_u32(value: &Value, key: &str) -> Result<u32, MemoryError> {
    as_u32(required(value, key)?)
}

fn required_string(value: &Value, key: &str) -> Result<String, MemoryError> {
    as_string(required(value, key)?)
}

fn required_bool(value: &Value, key: &str) -> Result<bool, MemoryError> {
    as_bool(required(value, key)?)
}

fn optional_u64(value: &Value, key: &str) -> Result<Option<u64>, MemoryError> {
    match lookup(value, key) {
        None | Some(Value::Null) => Ok(None),
        Some(value) => as_u64(value).map(Some),
    }
}

fn optional_object_id(value: &Value, key: &str) -> Result<Option<u64>, MemoryError> {
    match lookup(value, key) {
        None | Some(Value::Null) => Ok(None),
        Some(value) => as_object_id(value).map(Some),
    }
}

fn optional_u32(value: &Value, key: &str) -> Result<Option<u32>, MemoryError> {
    optional_u64(value, key)?
        .map(|value| u32::try_from(value).map_err(|_| invalid()))
        .transpose()
}

fn optional_usize(value: &Value, key: &str) -> Result<Option<usize>, MemoryError> {
    optional_u64(value, key)?
        .map(|value| usize::try_from(value).map_err(|_| invalid()))
        .transpose()
}

fn optional_string(value: &Value, key: &str) -> Result<Option<String>, MemoryError> {
    match lookup(value, key) {
        None | Some(Value::Null) => Ok(None),
        Some(value) => as_string(value).map(Some),
    }
}

/// Bitmap source identity is emitted as an integer by the HPROF engine but is
/// only ever used as opaque grouping evidence, so both wire shapes are kept.
fn optional_stringish(value: &Value, key: &str) -> Result<Option<String>, MemoryError> {
    match lookup(value, key) {
        None | Some(Value::Null) => Ok(None),
        Some(Value::String(text)) => Ok(Some(text.clone())),
        Some(value) => Ok(Some(value.as_int().map_err(|_| invalid())?.to_string())),
    }
}

fn optional_list<'a>(value: &'a Value, key: &str) -> Result<&'a [Value], MemoryError> {
    match lookup(value, key) {
        None | Some(Value::Null) => Ok(&[]),
        Some(value) => value.as_list().map_err(|_| invalid()),
    }
}

fn required_list<'a>(value: &'a Value, key: &str) -> Result<&'a [Value], MemoryError> {
    required(value, key)?.as_list().map_err(|_| invalid())
}

fn decode_list<T>(
    values: &[Value],
    decode: impl Fn(&Value) -> Result<T, MemoryError>,
) -> Result<Vec<T>, MemoryError> {
    values.iter().map(decode).collect()
}

/// One HPROF heap segment (App / Image / Zygote / Default / ...).
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct HeapSegment {
    pub name: String,
    pub size_bytes: u64,
    pub object_count: u64,
}

impl HeapSegment {
    pub(crate) fn from_value(value: &Value) -> Result<Self, MemoryError> {
        Ok(Self {
            name: required_string(value, "name")?,
            size_bytes: required_u64(value, "sizeBytes")?,
            object_count: required_u64(value, "objectCount")?,
        })
    }
}

/// Whole-heap summary from the engine histogram payload.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct HeapSummary {
    pub shallow_size: u64,
    pub object_count: u64,
    pub class_count: u64,
    pub heap_segments: Vec<HeapSegment>,
}

impl HeapSummary {
    pub(crate) fn from_value(value: &Value) -> Result<Self, MemoryError> {
        Ok(Self {
            shallow_size: required_u64(value, "shallowSize")?,
            object_count: required_u64(value, "objectCount")?,
            class_count: required_u64(value, "classCount")?,
            heap_segments: decode_list(
                optional_list(value, "heapSegments")?,
                HeapSegment::from_value,
            )?,
        })
    }
}

/// Immutable metadata reported when the worker opened the HPROF source.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct SnapshotMetadata {
    pub format: String,
    pub id_size: u8,
    pub timestamp_millis: Option<u64>,
    pub heaps: Vec<String>,
    pub class_count: u64,
    pub instance_count: u64,
    pub object_array_count: u64,
    pub primitive_array_count: u64,
    pub root_count: u64,
    pub warnings: Vec<String>,
    pub cache_status: Option<String>,
}

impl SnapshotMetadata {
    pub(crate) fn from_value(value: &Value) -> Result<Self, MemoryError> {
        let id_size = required_u32(value, "idSize")?;
        if id_size != 4 && id_size != 8 {
            return Err(invalid());
        }
        let mut warnings = Vec::new();
        for warning in optional_list(value, "warnings")? {
            warnings.push(required_string(warning, "message")?);
        }
        Ok(Self {
            format: required_string(value, "format")?,
            id_size: id_size as u8,
            timestamp_millis: optional_u64(value, "timestampMillis")?,
            heaps: decode_list(optional_list(value, "heaps")?, as_string)?,
            class_count: required_u64(value, "classCount")?,
            instance_count: required_u64(value, "instanceCount")?,
            object_array_count: required_u64(value, "objectArrayCount")?,
            primitive_array_count: required_u64(value, "primitiveArrayCount")?,
            root_count: required_u64(value, "rootCount")?,
            warnings,
            cache_status: optional_string(value, "cacheStatus")?,
        })
    }
}

/// One histogram class row. Optional metrics stay None when the source has
/// no evidence; HPROF never reports native or allocation-recording metrics.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct ClassStats {
    pub class_name: String,
    pub instance_count: u64,
    pub shallow_size: u64,
    pub retained_size: Option<u64>,
    pub native_size: Option<u64>,
    pub retained_native_size: Option<u64>,
    pub reachable_shallow_size: Option<u64>,
    pub reachable_native_size: Option<u64>,
    pub obfuscated_class_name: Option<String>,
    pub module_name: Option<String>,
    pub total_count: Option<u64>,
    pub heap_names: Vec<String>,
    pub hierarchy_depth: Option<u32>,
}

impl ClassStats {
    pub(crate) fn from_value(value: &Value) -> Result<Self, MemoryError> {
        Ok(Self {
            class_name: required_string(value, "className")?,
            instance_count: required_u64(value, "instanceCount")?,
            shallow_size: required_u64(value, "shallowSize")?,
            retained_size: optional_u64(value, "retainedSize")?,
            native_size: optional_u64(value, "nativeSize")?,
            retained_native_size: optional_u64(value, "retainedNativeSize")?,
            reachable_shallow_size: optional_u64(value, "reachableShallowSize")?,
            reachable_native_size: optional_u64(value, "reachableNativeSize")?,
            obfuscated_class_name: optional_string(value, "obfuscatedClassName")?,
            module_name: optional_string(value, "moduleName")?,
            total_count: optional_u64(value, "totalCount")?,
            heap_names: decode_list(optional_list(value, "heapNames")?, as_string)?,
            hierarchy_depth: optional_u32(value, "hierarchyDepth")?,
        })
    }
}

pub(crate) fn decode_classes(value: &Value) -> Result<Vec<ClassStats>, MemoryError> {
    decode_list(
        value.as_list().map_err(|_| invalid())?,
        ClassStats::from_value,
    )
}

/// Heuristic leak candidate. Never a confirmed leak; the UI states that.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct LeakSuspect {
    pub class_name: String,
    pub reason: String,
    pub instance_count: u64,
    pub retained_size: Option<u64>,
    pub activity_or_fragment_leak: bool,
}

impl LeakSuspect {
    pub(crate) fn from_value(value: &Value) -> Result<Self, MemoryError> {
        Ok(Self {
            class_name: required_string(value, "className")?,
            reason: required_string(value, "reason")?,
            instance_count: required_u64(value, "instanceCount")?,
            retained_size: optional_u64(value, "retainedSize")?,
            activity_or_fragment_leak: required_bool(value, "activityOrFragmentLeak")?,
        })
    }
}

/// One Bitmap instance with bounded, estimated pixel evidence.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct BitmapInstance {
    pub object_id: u64,
    pub class_name: String,
    pub width: Option<u32>,
    pub height: Option<u32>,
    pub estimated_pixel_bytes: Option<u64>,
    pub retained_size: Option<u64>,
    pub bitmap_id: Option<u64>,
    pub bitmap_source_id: Option<String>,
    pub native_size_bytes: Option<u64>,
}

impl BitmapInstance {
    pub(crate) fn from_value(value: &Value) -> Result<Self, MemoryError> {
        Ok(Self {
            object_id: required_object_id(value, "objectId")?,
            class_name: required_string(value, "className")?,
            width: optional_u32(value, "width")?,
            height: optional_u32(value, "height")?,
            estimated_pixel_bytes: optional_u64(value, "estimatedPixelBytes")?,
            retained_size: optional_u64(value, "retainedSize")?,
            bitmap_id: optional_object_id(value, "bitmapId")?,
            bitmap_source_id: optional_stringish(value, "bitmapSourceId")?,
            native_size_bytes: optional_u64(value, "nativeSizeBytes")?,
        })
    }
}

/// Per-Activity-class lifecycle candidate summary.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct ActivityLeak {
    pub class_name: String,
    pub live_instance_count: u64,
    pub destroyed_instance_count: u64,
    pub retained_size: Option<u64>,
}

impl ActivityLeak {
    pub(crate) fn from_value(value: &Value) -> Result<Self, MemoryError> {
        Ok(Self {
            class_name: required_string(value, "className")?,
            live_instance_count: required_u64(value, "liveInstanceCount")?,
            destroyed_instance_count: required_u64(value, "destroyedInstanceCount")?,
            retained_size: optional_u64(value, "retainedSize")?,
        })
    }
}

/// Object-level Activity/Fragment lifecycle candidate; IDs are snapshot-local.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct LifecycleLeakObject {
    pub object_id: u64,
    pub class_name: String,
    pub heap_name: Option<String>,
}

impl LifecycleLeakObject {
    pub(crate) fn from_value(value: &Value) -> Result<Self, MemoryError> {
        Ok(Self {
            object_id: required_object_id(value, "objectId")?,
            class_name: required_string(value, "className")?,
            heap_name: optional_string(value, "heapName")?,
        })
    }
}

/// A bounded findings page: total is the full match count, rows is the
/// bounded first page.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FindingsPage<T> {
    pub total: u64,
    pub rows: Vec<T>,
}

impl<T> Default for FindingsPage<T> {
    fn default() -> Self {
        Self {
            total: 0,
            rows: Vec::new(),
        }
    }
}

pub(crate) fn decode_findings_page<T>(
    value: &Value,
    decode: impl Fn(&Value) -> Result<T, MemoryError>,
) -> Result<FindingsPage<T>, MemoryError> {
    let total = required_u64(value, "total")?;
    let rows = decode_list(required_list(value, "rows")?, decode)?;
    if rows.len() > crate::MAX_PAGE_SIZE {
        return Err(invalid());
    }
    Ok(FindingsPage { total, rows })
}

/// The first bounded rows of every HPROF finding kind.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct FindingsSnapshot {
    pub leak_suspects: FindingsPage<LeakSuspect>,
    pub bitmaps: FindingsPage<BitmapInstance>,
    pub activities: FindingsPage<ActivityLeak>,
    pub lifecycle: FindingsPage<LifecycleLeakObject>,
}

impl FindingsSnapshot {
    pub(crate) fn from_value(value: &Value) -> Result<Self, MemoryError> {
        Ok(Self {
            leak_suspects: decode_page(required(value, "leakSuspects")?, LeakSuspect::from_value)?,
            bitmaps: decode_page(
                required(value, "bitmapInstances")?,
                BitmapInstance::from_value,
            )?,
            activities: decode_page(required(value, "activityLeaks")?, ActivityLeak::from_value)?,
            lifecycle: decode_page(
                required(value, "lifecycleLeakObjects")?,
                LifecycleLeakObject::from_value,
            )?,
        })
    }
}

fn decode_page<T>(
    value: &Value,
    decode: impl Fn(&Value) -> Result<T, MemoryError>,
) -> Result<FindingsPage<T>, MemoryError> {
    decode_findings_page(value, decode)
}

/// One immutable, bounded read of an open HPROF session.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct MemorySnapshot {
    pub source_digest: String,
    pub metadata: SnapshotMetadata,
    pub summary: HeapSummary,
    pub classes: Vec<ClassStats>,
    pub per_heap_classes: BTreeMap<String, Vec<ClassStats>>,
    pub findings: FindingsSnapshot,
}

impl MemorySnapshot {
    /// Decodes the worker's combined snapshot payload. The caller has already
    /// verified the source digest and the response budget.
    pub(crate) fn decode(
        payload: &Value,
        metadata: &SnapshotMetadata,
        source_digest: &str,
    ) -> Result<Self, MemoryError> {
        let summary = HeapSummary::from_value(required(payload, "summary")?)?;
        let classes = decode_classes(required(payload, "classes")?)?;
        let omitted =
            lookup(payload, "perHeapOmitted").and_then(|value| value.as_bool().ok()) == Some(true);
        let mut per_heap_classes = BTreeMap::new();
        if !omitted && let Some(Value::Map(heaps)) = lookup(payload, "perHeap") {
            for (heap, classes) in heaps {
                per_heap_classes.insert(heap.clone(), decode_classes(classes)?);
            }
        }
        Ok(Self {
            source_digest: source_digest.to_owned(),
            metadata: metadata.clone(),
            summary,
            classes,
            per_heap_classes,
            findings: FindingsSnapshot::from_value(required(payload, "findings")?)?,
        })
    }
}

/// One instance row of a class/group instance list.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct InstanceRow {
    pub object_id: u64,
    pub index: u64,
    pub shallow_size: u64,
    pub retained_size: Option<u64>,
    pub depth: Option<u32>,
    pub reachable: bool,
    pub native_size: Option<u64>,
    pub shallow_size_known: bool,
    pub class_name: Option<String>,
    pub represented_class_name: Option<String>,
}

impl InstanceRow {
    pub(crate) fn from_value(value: &Value) -> Result<Self, MemoryError> {
        Ok(Self {
            object_id: required_object_id(value, "objectId")?,
            index: required_u64(value, "index")?,
            shallow_size: required_u64(value, "shallowSize")?,
            retained_size: optional_u64(value, "retainedSize")?,
            depth: optional_u32(value, "depth")?,
            reachable: required_bool(value, "reachable")?,
            native_size: optional_u64(value, "nativeSize")?,
            shallow_size_known: required_bool(value, "shallowSizeKnown")?,
            class_name: optional_string(value, "className")?,
            represented_class_name: optional_string(value, "representedClassName")?,
        })
    }
}

/// One bounded page of an instance list.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct InstancesPage {
    pub total: u64,
    pub offset: u64,
    pub rows: Vec<InstanceRow>,
}

impl InstancesPage {
    pub(crate) fn from_value(value: &Value, requested_offset: usize) -> Result<Self, MemoryError> {
        Ok(Self {
            total: required_u64(value, "total")?,
            offset: optional_u64(value, "offset")?.unwrap_or(requested_offset as u64),
            rows: decode_list(required_list(value, "rows")?, InstanceRow::from_value)?,
        })
    }
}

/// Kind of a heap value; the engine's STRING/NULL values fold into the
/// spec's coarser variants because the inspector only needs reference shape.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum HeapValueKind {
    #[default]
    Unknown,
    Primitive,
    Object,
    Class,
    Array,
}

impl HeapValueKind {
    pub fn from_engine_name(name: &str) -> Self {
        match name {
            "PRIMITIVE" => Self::Primitive,
            "OBJECT" | "STRING" => Self::Object,
            "CLASS" => Self::Class,
            "ARRAY" => Self::Array,
            _ => Self::Unknown,
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Self::Unknown => "UNKNOWN",
            Self::Primitive => "PRIMITIVE",
            Self::Object => "OBJECT",
            Self::Class => "CLASS",
            Self::Array => "ARRAY",
        }
    }
}

impl std::fmt::Display for HeapValueKind {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(self.as_str())
    }
}

impl AsRef<str> for HeapValueKind {
    fn as_ref(&self) -> &str {
        self.as_str()
    }
}

/// One edge of the shortest reference chain to a GC root.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct ObjectReference {
    pub object_id: u64,
    pub class_name: String,
}

impl ObjectReference {
    pub(crate) fn from_value(value: &Value) -> Result<Self, MemoryError> {
        Ok(Self {
            object_id: required_object_id(value, "targetObjectId")?,
            class_name: optional_string(value, "targetClassName")?
                .unwrap_or_else(|| "<unknown>".into()),
        })
    }
}

/// One instance field row, either a primitive display value or an object edge.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct InstanceField {
    pub name: String,
    pub display_value: String,
    pub target_object_id: Option<u64>,
    pub target_class_name: Option<String>,
    pub depth: Option<u32>,
    pub value_kind: HeapValueKind,
    pub shallow_size: Option<u64>,
    pub retained_size: Option<u64>,
    pub native_size: Option<u64>,
    pub retained_native_size: Option<u64>,
    pub represented_class_name: Option<String>,
    pub string_preview: Option<String>,
    pub child_count: Option<u64>,
}

impl InstanceField {
    pub(crate) fn from_value(value: &Value) -> Result<Self, MemoryError> {
        Ok(Self {
            name: required_string(value, "name")?,
            display_value: required_string(value, "displayValue")?,
            target_object_id: optional_object_id(value, "targetObjectId")?,
            target_class_name: optional_string(value, "targetClassName")?,
            depth: optional_u32(value, "depth")?,
            value_kind: optional_string(value, "valueKind")?
                .as_deref()
                .map_or(HeapValueKind::Unknown, HeapValueKind::from_engine_name),
            shallow_size: optional_u64(value, "shallowSize")?,
            retained_size: optional_u64(value, "retainedSize")?,
            native_size: optional_u64(value, "nativeSize")?,
            retained_native_size: optional_u64(value, "retainedNativeSize")?,
            represented_class_name: optional_string(value, "representedClassName")?,
            string_preview: optional_string(value, "stringPreview")?,
            child_count: optional_u64(value, "childCount")?,
        })
    }
}

/// Bounded detail for one selected heap object. omitted reports that the
/// worker's presentation budget clipped a nested list or reference chain.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct InstanceDetail {
    pub object_id: u64,
    pub class_name: String,
    pub shallow_size: u64,
    pub retained_size: Option<u64>,
    pub depth: Option<u32>,
    pub is_array: bool,
    pub element_count: Option<u64>,
    pub array_start: u64,
    pub array_page_size: u64,
    pub fields: Vec<InstanceField>,
    pub reference_chain: Vec<ObjectReference>,
    pub references: Vec<InstanceField>,
    pub shallow_size_known: bool,
    pub native_size: Option<u64>,
    pub value_kind: HeapValueKind,
    pub represented_class_name: Option<String>,
    pub string_preview: Option<String>,
    pub retained_native_size: Option<u64>,
    pub omitted: bool,
}

impl InstanceDetail {
    pub(crate) fn from_value(
        value: &Value,
        requested_object_id: u64,
        requested_array_start: usize,
        requested_limit: usize,
    ) -> Result<Self, MemoryError> {
        let is_array = match lookup(value, "isArray") {
            Some(value) => as_bool(value)?,
            None => false,
        };
        Ok(Self {
            object_id: optional_object_id(value, "objectId")?.unwrap_or(requested_object_id),
            class_name: optional_string(value, "className")?.unwrap_or_default(),
            shallow_size: required_u64(value, "shallowSize")?,
            retained_size: optional_u64(value, "retainedSize")?,
            depth: optional_u32(value, "depth")?,
            is_array,
            element_count: optional_u64(value, "elementCount")?,
            array_start: optional_usize(value, "fieldOffset")?.unwrap_or(requested_array_start)
                as u64,
            array_page_size: optional_usize(value, "arrayPageSize")?.unwrap_or(requested_limit)
                as u64,
            fields: decode_list(optional_list(value, "fields")?, InstanceField::from_value)?,
            reference_chain: decode_list(
                optional_list(value, "referenceChain")?,
                ObjectReference::from_value,
            )?,
            references: decode_list(
                optional_list(value, "references")?,
                InstanceField::from_value,
            )?,
            shallow_size_known: match lookup(value, "shallowSizeKnown") {
                Some(value) => as_bool(value)?,
                None => true,
            },
            native_size: optional_u64(value, "nativeSize")?,
            value_kind: optional_string(value, "valueKind")?
                .as_deref()
                .map_or(HeapValueKind::Unknown, HeapValueKind::from_engine_name),
            represented_class_name: optional_string(value, "representedClassName")?,
            string_preview: optional_string(value, "stringPreview")?,
            retained_native_size: optional_u64(value, "retainedNativeSize")?,
            omitted: lookup(value, "presentationOmissions")
                .and_then(|value| value.as_list().ok())
                .is_some_and(|omissions| !omissions.is_empty()),
        })
    }
}

/// One node of the retained-size dominator tree; depth is the dominator depth.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct DominatorRow {
    pub object_id: u64,
    pub class_name: String,
    pub shallow_size: u64,
    pub retained_size: u64,
    pub depth: u32,
    pub parent_object_id: Option<u64>,
}

impl DominatorRow {
    pub(crate) fn from_value(value: &Value) -> Result<Self, MemoryError> {
        Ok(Self {
            object_id: required_object_id(value, "objectId")?,
            class_name: required_string(value, "className")?,
            shallow_size: required_u64(value, "shallowSize")?,
            retained_size: required_u64(value, "retainedSize")?,
            depth: required_u32(value, "depth")?,
            parent_object_id: optional_object_id(value, "parentObjectId")?,
        })
    }
}

/// One bounded page of dominator children.
#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct DominatorsPage {
    pub total: u64,
    pub offset: u64,
    pub rows: Vec<DominatorRow>,
}

impl DominatorsPage {
    pub(crate) fn from_value(value: &Value, requested_offset: usize) -> Result<Self, MemoryError> {
        Ok(Self {
            total: required_u64(value, "total")?,
            offset: optional_u64(value, "offset")?.unwrap_or(requested_offset as u64),
            rows: decode_list(required_list(value, "rows")?, DominatorRow::from_value)?,
        })
    }
}

use super::{MemorySummary, PAGE, PageData, PageUi, UiText};
use aps_memory::{FindingKind, HistogramQuery, MemorySession, MemoryValue as Value};
use aps_platform::CancellationToken;
use aps_ui_foundation::TextKey;
use std::sync::{Arc, Mutex};

#[derive(Clone, Debug, PartialEq, Eq)]
pub(super) enum MemoryView {
    Histogram,
    FilteredHistogram(HistogramQuery),
    Instances(String),
    Detail(u64),
    Dominators(Option<u64>),
    Findings,
    FindingCategory(FindingKind),
}

#[derive(Clone, Default)]
pub(super) struct MemoryDetail {
    pub class_name: String,
    pub object_id: String,
    pub shallow_size: String,
    pub retained_size: String,
    pub depth: String,
    pub value_kind: String,
    pub string_preview: Option<String>,
    pub gc_chain: Vec<(String, String, Option<u64>)>,
    pub inbound: Vec<(String, String, Option<u64>)>,
    pub omitted: bool,
}

fn references(value: &Value, key: &str) -> Vec<(String, String, Option<u64>)> {
    let inbound = key == "references";
    value
        .get(key)
        .ok()
        .and_then(|v| v.as_list().ok())
        .into_iter()
        .flatten()
        .take(64)
        .map(|row| {
            let name = row
                .get(if inbound { "name" } else { "fieldName" })
                .ok()
                .and_then(|v| v.as_str().ok())
                .unwrap_or("—");
            let class = row
                .get("targetClassName")
                .ok()
                .and_then(|v| v.as_str().ok())
                .unwrap_or("—");
            (
                name.to_owned(),
                class.to_owned(),
                object_id(row, "targetObjectId"),
            )
        })
        .collect()
}

pub(super) fn page(
    session: &Arc<Mutex<MemorySession>>,
    view: &MemoryView,
    offset: usize,
    cancel: &CancellationToken,
) -> Result<PageData, UiText> {
    let mut session = session
        .lock()
        .map_err(|_| TextKey::ImportUiMemorySessionUnavailable)?;
    let value = match view {
        MemoryView::Histogram => session.histogram(None, offset, PAGE, cancel),
        MemoryView::FilteredHistogram(query) => {
            session.histogram_query(None, query, offset, PAGE, cancel)
        }
        MemoryView::Instances(name) => {
            session.instances(std::slice::from_ref(name), None, offset, PAGE, cancel)
        }
        MemoryView::Detail(id) => session.detail(*id, offset, PAGE, cancel),
        MemoryView::Dominators(parent) => session.dominators(*parent, offset, PAGE, cancel),
        MemoryView::Findings => session.findings(FindingKind::LeakSuspects, offset, PAGE, cancel),
        MemoryView::FindingCategory(kind) => session.findings(*kind, offset, PAGE, cancel),
    }
    .map_err(|error| error.to_string())?;
    if value == Value::Null {
        return Err(TextKey::ImportUiMemoryObjectMissing.into());
    }
    let map = value
        .as_map()
        .map_err(|_| TextKey::ImportUiInvalidMemoryResponse)?;
    let detail = matches!(view, MemoryView::Detail(_));
    let list = map
        .get(if detail { "fields" } else { "rows" })
        .and_then(|v| v.as_list().ok())
        .ok_or(TextKey::ImportUiMemoryRowsMissing)?;
    let total = map
        .get(if detail { "fieldTotal" } else { "total" })
        .and_then(|v| v.as_int().ok())
        .and_then(|v| usize::try_from(v).ok())
        .ok_or(TextKey::ImportUiMemoryCountMissing)?;
    let (title, fields): (String, &[&str]) = match view {
        MemoryView::Histogram | MemoryView::FilteredHistogram(_) => (
            "Class histogram".into(),
            &["className", "instanceCount", "shallowSize", "retainedSize"],
        ),
        MemoryView::Instances(name) => (
            format!("Instances · {name}"),
            &[
                "objectId",
                "className",
                "shallowSize",
                "retainedSize",
                "heapName",
            ],
        ),
        MemoryView::Detail(id) => (
            format!("Object 0x{id:x} · {}", field(&value, "className")),
            &[
                "name",
                "displayValue",
                "targetClassName",
                "shallowSize",
                "retainedSize",
            ],
        ),
        MemoryView::Dominators(parent) => (
            format!(
                "Dominator children · {}",
                parent
                    .map(|id| format!("0x{id:x}"))
                    .unwrap_or_else(|| "roots".into())
            ),
            &["objectId", "className", "shallowSize", "retainedSize"],
        ),
        MemoryView::Findings | MemoryView::FindingCategory(FindingKind::LeakSuspects) => (
            "Leak suspects — not confirmed leaks".into(),
            &["className", "reason", "instanceCount", "retainedSize"],
        ),
        MemoryView::FindingCategory(FindingKind::BitmapInstances) => (
            "Bitmap instances · estimated pixel bytes".into(),
            &[
                "objectId",
                "className",
                "width",
                "height",
                "estimatedPixelBytes",
                "retainedSize",
            ],
        ),
        MemoryView::FindingCategory(FindingKind::ActivityLeaks) => (
            "Activity lifecycle candidates — manual verification required".into(),
            &[
                "className",
                "liveInstanceCount",
                "destroyedInstanceCount",
                "retainedSize",
            ],
        ),
        MemoryView::FindingCategory(FindingKind::LifecycleLeakObjects) => (
            "Reachable lifecycle objects — not confirmed leaks".into(),
            &["objectId", "className", "heapName"],
        ),
    };
    let display_title = match view {
        MemoryView::Histogram | MemoryView::FilteredHistogram(_) => {
            TextKey::ImportUiMemoryHistogram.into()
        }
        MemoryView::Instances(name) => UiText::format(
            TextKey::ImportUiMemoryInstances,
            [("class", name.clone().into())],
        ),
        MemoryView::Detail(id) => UiText::format(
            TextKey::ImportUiMemoryObject,
            [
                ("id", format!("0x{id:x}").into()),
                ("class", display_field(&value, "className")),
            ],
        ),
        MemoryView::Dominators(parent) => UiText::format(
            TextKey::ImportUiMemoryDominators,
            [(
                "parent",
                parent.map_or_else(
                    || TextKey::ImportUiMemoryRoots.into(),
                    |id| format!("0x{id:x}").into(),
                ),
            )],
        ),
        MemoryView::Findings | MemoryView::FindingCategory(_) => title.clone().into(),
    };
    let columns: Vec<_> = fields.iter().filter_map(|key| column_title(key)).collect();
    let mut ui = PageUi::new(
        UiText::format(
            TextKey::ImportUiMemorySummary,
            [
                ("title", display_title),
                ("total", total.to_string().into()),
            ],
        ),
        if columns.len() == fields.len() {
            &columns
        } else {
            &[]
        },
    )
    .warning(0, TextKey::ImportUiMemoryWorkerWarning.into());
    if matches!(
        view,
        MemoryView::Histogram | MemoryView::FilteredHistogram(_)
    ) {
        if let Ok(summary) = value.get("summary") {
            let heap_segments = summary
                .get("heapSegments")
                .ok()
                .and_then(|value| value.as_list().ok())
                .into_iter()
                .flatten()
                .filter_map(|segment| {
                    Some((
                        segment.get("name").ok()?.as_str().ok()?.to_owned(),
                        nonnegative(segment, "sizeBytes")?,
                        nonnegative(segment, "objectCount")?,
                    ))
                })
                .take(32)
                .collect();
            ui.memory_summary = Some(MemorySummary {
                shallow_size: nonnegative(summary, "shallowSize"),
                object_count: nonnegative(summary, "objectCount"),
                class_count: nonnegative(summary, "classCount"),
                heap_segments,
            });
        }
    }
    if detail {
        let omitted = map
            .get("presentationOmissions")
            .and_then(|v| v.as_list().ok())
            .is_some_and(|v| !v.is_empty())
            || ["referenceChain", "references"].into_iter().any(|key| {
                value
                    .get(key)
                    .ok()
                    .and_then(|v| v.as_list().ok())
                    .is_some_and(|v| v.len() > 64)
            });
        ui.memory_detail = Some(MemoryDetail {
            class_name: field(&value, "className"),
            object_id: field(&value, "objectId"),
            shallow_size: field(&value, "shallowSize"),
            retained_size: field(&value, "retainedSize"),
            depth: field(&value, "depth"),
            value_kind: field(&value, "valueKind"),
            string_preview: value
                .get("stringPreview")
                .ok()
                .and_then(|v| v.as_str().ok())
                .map(str::to_owned),
            gc_chain: references(&value, "referenceChain"),
            inbound: references(&value, "references"),
            omitted,
        });
    }
    let mut actions = Vec::with_capacity(list.len());
    let rows = list
        .iter()
        .enumerate()
        .map(|(index, row)| {
            actions.push(match view {
                MemoryView::Histogram | MemoryView::FilteredHistogram(_) => row
                    .get("className")
                    .ok()
                    .and_then(|v| v.as_str().ok())
                    .map(|name| MemoryView::Instances(name.to_owned())),
                MemoryView::Detail(_) => object_id(row, "targetObjectId")
                    .filter(|id| *id != 0)
                    .map(MemoryView::Detail),
                MemoryView::Dominators(_) => {
                    object_id(row, "objectId").map(|id| MemoryView::Dominators(Some(id)))
                }
                MemoryView::Findings
                | MemoryView::FindingCategory(
                    FindingKind::LeakSuspects | FindingKind::ActivityLeaks,
                ) => row
                    .get("className")
                    .ok()
                    .and_then(|v| v.as_str().ok())
                    .map(|name| MemoryView::Instances(name.to_owned())),
                _ => object_id(row, "objectId").map(MemoryView::Detail),
            });
            fields
                .iter()
                .enumerate()
                .map(|(column, key)| {
                    if !matches!(
                        row.get(key),
                        Ok(Value::Int(_) | Value::String(_) | Value::Bool(_))
                    ) {
                        ui.cells
                            .insert((index, column), TextKey::ImportUiUnavailable.into());
                    }
                    field(row, key)
                })
                .collect()
        })
        .collect();
    let mut warnings = vec![
        "The heap graph stays in a controlled worker. Pages do not prove Android Studio parity."
            .into(),
    ];
    if let Some(omissions) = map
        .get("presentationOmissions")
        .and_then(|v| v.as_list().ok())
    {
        if !omissions.is_empty() {
            ui.warnings.insert(
                warnings.len(),
                UiText::format(
                    TextKey::ImportUiMemoryOmissions,
                    [("count", omissions.len().to_string().into())],
                ),
            );
            warnings.push(format!(
                "Presentation budget omitted {} nested values or lists; details are incomplete.",
                omissions.len()
            ));
        }
    }
    Ok(PageData {
        ui,
        summary: format!("{title} · {total} rows"),
        columns: fields.iter().map(|s| (*s).into()).collect(),
        rows,
        offset,
        has_more: offset.saturating_add(list.len()) < total,
        warnings,
        row_actions: actions,
    })
}

fn column_title(field: &str) -> Option<TextKey> {
    Some(match field {
        "className" => TextKey::ImportUiClassName,
        "instanceCount" => TextKey::ImportUiInstanceCount,
        "shallowSize" => TextKey::ImportUiShallowSize,
        "retainedSize" => TextKey::ImportUiRetainedSize,
        "objectId" => TextKey::ImportUiObjectId,
        "heapName" => TextKey::ImportUiHeapName,
        "name" => TextKey::ImportUiName,
        "displayValue" => TextKey::ImportUiValue,
        "targetClassName" => TextKey::ImportUiTargetClass,
        "reason" => TextKey::ImportUiReason,
        "confidence" => TextKey::ImportUiConfidence,
        _ => return None,
    })
}

fn display_field(value: &Value, key: &str) -> UiText {
    match value.get(key) {
        Ok(Value::Int(_) | Value::String(_) | Value::Bool(_)) => field(value, key).into(),
        _ => TextKey::ImportUiUnavailable.into(),
    }
}

fn object_id(value: &Value, key: &str) -> Option<u64> {
    value.get(key).ok()?.as_int().ok().map(|id| id as u64)
}

fn nonnegative(value: &Value, key: &str) -> Option<u64> {
    value
        .get(key)
        .ok()?
        .as_int()
        .ok()
        .and_then(|n| u64::try_from(n).ok())
}
fn field(value: &Value, key: &str) -> String {
    match value.get(key) {
        Ok(Value::Int(value)) if key.ends_with("ObjectId") || key == "objectId" => {
            format!("0x{:x}", *value as u64)
        }
        Ok(Value::Int(value)) => value.to_string(),
        Ok(Value::String(value)) => value.clone(),
        Ok(Value::Bool(value)) => value.to_string(),
        _ => "unavailable".into(),
    }
}

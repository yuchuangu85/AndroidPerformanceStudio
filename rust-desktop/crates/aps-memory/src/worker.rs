//! Trusted child-process adapter around the retained HPROF engine. Pagination
//! takes place here, before framing, never by loading a full heap into the UI.

use crate::{
    MAX_HISTOGRAM_SEARCH_BYTES, MAX_INPUT_BYTES, MAX_MAPPING_BYTES, MAX_MAPPING_ENTRIES,
    MAX_PAGE_SIZE, MAX_RESPONSE_BYTES, MAX_SNAPSHOT_BYTES, MAX_UI_PAGE_SIZE, page, page_with,
    write_response,
};
use aps_hprof_engine::{
    Session, cache,
    wire::{self, Value},
};
use std::collections::BTreeMap;
use std::io::{self, Read, Write};
use std::path::Path;

pub fn serve(reader: &mut impl Read, writer: &mut impl Write) -> io::Result<()> {
    let mut session = Session::default();
    let mut digest = String::new();
    while let Some(bytes) = wire::read_frame(reader).map_err(|_| invalid())? {
        let request = wire::decode(&bytes).map_err(|_| invalid())?;
        let id = request
            .get("id")
            .and_then(Value::as_int)
            .map_err(|_| invalid())?;
        // The combined snapshot read is one deliberate, bounded response; every
        // other operation keeps the 2 MiB page-response ceiling.
        let snapshot = request.get("op").and_then(Value::as_str) == Ok("snapshot");
        if request.get("op").and_then(Value::as_str) == Ok("open") {
            digest = request
                .get("sourceDigest")
                .and_then(Value::as_str)
                .map_err(|_| invalid())?
                .to_owned();
        }
        let result = respond(&mut session, &request);
        let response = match result {
            Ok(value) => value,
            Err(()) => error(id, &digest),
        };
        // A single response cannot force a large allocation in the UI process.
        // Encoding up to the engine's 32 MiB ceiling happens only in this child.
        let max_response = if snapshot {
            MAX_SNAPSHOT_BYTES
        } else {
            MAX_RESPONSE_BYTES
        };
        let response = match wire::encode(&response) {
            Ok(bytes) if bytes.len() <= max_response => response,
            _ => error(id, &digest),
        };
        write_response(writer, &response)?;
    }
    Ok(())
}

fn invalid() -> io::Error {
    io::ErrorKind::InvalidData.into()
}
fn error(id: i64, digest: &str) -> Value {
    Value::map([
        ("version", wire::VERSION.into()),
        ("id", id.into()),
        ("sourceDigest", digest.into()),
        ("status", "error".into()),
        ("error", "Memory engine rejected the input or query".into()),
    ])
}

fn respond(session: &mut Session, request: &Value) -> Result<Value, ()> {
    if request
        .get("version")
        .and_then(Value::as_int)
        .map_err(|_| ())?
        != wire::VERSION
    {
        return Err(());
    }
    let operation = request.get("op").and_then(Value::as_str).map_err(|_| ())?;
    let args = request
        .get("args")
        .and_then(Value::as_map)
        .map_err(|_| ())?;
    if operation == "snapshot" {
        return snapshot_response(session, request, args);
    }
    if operation == "open" {
        let path = args.get("path").ok_or(())?.as_str().map_err(|_| ())?;
        let digest = args
            .get("sourceDigest")
            .ok_or(())?
            .as_str()
            .map_err(|_| ())?;
        let meta = std::fs::metadata(path).map_err(|_| ())?;
        if !meta.is_file()
            || meta.len() > MAX_INPUT_BYTES
            || cache::sha256_file(Path::new(path)).map_err(|_| ())? != digest
        {
            return Err(());
        }
        // Keep this worker read-only: no cacheDir, graphDir or arbitrary storage.
        if args.get("cacheDir").is_some_and(|v| *v != Value::Null)
            || args
                .get("graphStorage")
                .and_then(|v| v.as_str().ok())
                .is_some_and(|v| v != "memory")
        {
            return Err(());
        }
        // A deobfuscation map is bounded evidence, not an arbitrary payload.
        if let Some(mapping) = args.get("mapping") {
            let mapping = mapping.as_map().map_err(|_| ())?;
            if mapping.len() > MAX_MAPPING_ENTRIES {
                return Err(());
            }
            let mut bytes = 0usize;
            for (raw, original) in mapping {
                if raw.is_empty() {
                    return Err(());
                }
                let original = original.as_str().map_err(|_| ())?;
                if original.is_empty() {
                    return Err(());
                }
                bytes = bytes.checked_add(raw.len() + original.len()).ok_or(())?;
                if bytes > MAX_MAPPING_BYTES {
                    return Err(());
                }
            }
        }
    }
    let detail_offset = if operation == "detail" {
        Some(index(args, "arrayStart")?)
    } else {
        None
    };
    let limit = match operation {
        "histogram" | "findings" => {
            let offset = index(args, "offset")?;
            let limit = index(args, "limit")?;
            page(offset, limit).map_err(|_| ())?;
            Some(limit)
        }
        // Instance and dominator views page at 500 rows.
        "instances" | "dominators" => {
            let offset = index(args, "offset")?;
            let limit = index(args, "limit")?;
            page_with(offset, limit, MAX_UI_PAGE_SIZE).map_err(|_| ())?;
            Some(limit)
        }
        "detail" => {
            let limit = index(args, "arrayLimit")?;
            page(detail_offset.ok_or(())?, limit).map_err(|_| ())?;
            Some(limit)
        }
        "open" => None,
        _ => return Err(()),
    };
    if operation == "histogram" {
        let search = args
            .get("search")
            .map(Value::as_str)
            .transpose()
            .map_err(|_| ())?
            .unwrap_or("");
        let sort = args
            .get("sort")
            .map(Value::as_str)
            .transpose()
            .map_err(|_| ())?
            .unwrap_or("count");
        let _ = args
            .get("matchCase")
            .map(Value::as_bool)
            .transpose()
            .map_err(|_| ())?;
        if search.len() > MAX_HISTOGRAM_SEARCH_BYTES
            || !matches!(sort, "count" | "shallow" | "retained" | "name")
        {
            return Err(());
        }
    }
    let (response, _) = session.respond(request);
    let Value::Map(mut root) = response else {
        return Err(());
    };
    if root.get("status").and_then(|v| v.as_str().ok()) != Some("ok") {
        return Err(());
    }
    if operation == "open" {
        let path = args.get("path").ok_or(())?.as_str().map_err(|_| ())?;
        let digest = args
            .get("sourceDigest")
            .ok_or(())?
            .as_str()
            .map_err(|_| ())?;
        if cache::sha256_file(Path::new(path)).map_err(|_| ())? != digest {
            return Err(());
        }
    }
    let payload = root.get_mut("payload").ok_or(())?;
    match operation {
        "histogram" => {
            let offset = index(args, "offset")?;
            let search = args
                .get("search")
                .map(Value::as_str)
                .transpose()
                .map_err(|_| ())?
                .unwrap_or("");
            let match_case = args
                .get("matchCase")
                .map(Value::as_bool)
                .transpose()
                .map_err(|_| ())?
                .unwrap_or(false);
            let sort = args
                .get("sort")
                .map(Value::as_str)
                .transpose()
                .map_err(|_| ())?
                .unwrap_or("count");
            let map = as_map_mut(payload)?;
            let classes = map.remove("classes").ok_or(())?;
            let list = match classes {
                Value::List(values) => values,
                _ => return Err(()),
            };
            let (rows, total) =
                histogram_rows(list, search, match_case, sort, offset, limit.ok_or(())?)?;
            map.insert("rows".into(), Value::List(rows));
            map.insert("total".into(), (total as i64).into());
            map.insert("offset".into(), (offset as i64).into());
        }
        "findings" => {
            let kind = args.get("kind").ok_or(())?.as_str().map_err(|_| ())?;
            if !matches!(
                kind,
                "leakSuspects" | "bitmapInstances" | "activityLeaks" | "lifecycleLeakObjects"
            ) {
                return Err(());
            }
            let offset = index(args, "offset")?;
            let list = as_map_mut(payload)?.remove(kind).ok_or(())?;
            let list = match list {
                Value::List(values) => values,
                _ => return Err(()),
            };
            let total = list.len();
            *payload = Value::map([
                ("kind", kind.into()),
                ("total", (total as i64).into()),
                ("offset", (offset as i64).into()),
                (
                    "rows",
                    Value::List(
                        list.into_iter()
                            .skip(offset)
                            .take(limit.ok_or(())?)
                            .collect(),
                    ),
                ),
            ]);
        }
        "detail" if *payload != Value::Null => {
            let map = as_map_mut(payload)?;
            let is_array = map.get("isArray").ok_or(())?.as_bool().map_err(|_| ())?;
            let offset = detail_offset.ok_or(())?;
            let fields = map.remove("fields").ok_or(())?;
            let fields = match fields {
                Value::List(values) => values,
                _ => return Err(()),
            };
            let total = if is_array {
                map.get("elementCount").cloned().unwrap_or(Value::Null)
            } else {
                (fields.len() as i64).into()
            };
            let fields = if is_array {
                fields
            } else {
                fields
                    .into_iter()
                    .skip(offset)
                    .take(limit.ok_or(())?)
                    .collect()
            };
            map.insert("fields".into(), Value::List(fields));
            map.insert("fieldTotal".into(), total);
            map.insert("fieldOffset".into(), (offset as i64).into());
        }
        "dominators" => {
            as_map_mut(payload)?.insert("offset".into(), (index(args, "offset")? as i64).into());
        }
        _ => {}
    }
    // Every nested list and string has its own presentation cap. Record exact
    // omitted paths/counts; a clipped chain is never represented as complete.
    let mut omissions = Vec::new();
    bounded(payload, "payload", &mut omissions, 0)?;
    if let Value::Map(payload) = payload {
        payload.insert("presentationOmissions".into(), Value::List(omissions));
    }
    Ok(Value::Map(root))
}

/// One bounded, consistent snapshot: run each engine aggregation exactly once,
/// then emit the smallest payload that preserves the contract. Findings keep
/// full totals with only their first page; per-heap histograms are dropped
/// before the response can exceed the presentation budget.
fn snapshot_response(
    session: &mut Session,
    request: &Value,
    args: &BTreeMap<String, Value>,
) -> Result<Value, ()> {
    let id = request.get("id").and_then(Value::as_int).map_err(|_| ())?;
    let digest = request
        .get("sourceDigest")
        .and_then(Value::as_str)
        .map_err(|_| ())?;
    let heaps = match args.get("heaps") {
        Some(value) => value.as_list().map_err(|_| ())?.to_vec(),
        None => Vec::new(),
    };
    if heaps.len() > 64 {
        return Err(());
    }
    let mut heap_names = Vec::new();
    for heap in heaps {
        let name = heap.as_str().map_err(|_| ())?;
        if name.len() > 4096 {
            return Err(());
        }
        heap_names.push(name.to_owned());
    }
    let histogram = engine_call(
        session,
        id,
        digest,
        "histogram",
        Value::map([("heapName", Value::Null)]),
    )?;
    let findings = engine_call(session, id, digest, "findings", Value::map([]))?;
    let Value::Map(mut histogram) = histogram else {
        return Err(());
    };
    let summary = histogram.remove("summary").ok_or(())?;
    let classes = histogram.remove("classes").ok_or(())?;
    let findings = truncate_findings(findings)?;
    let mut base_size = 1024usize;
    for part in [&summary, &classes, &findings] {
        base_size = base_size.checked_add(encoded_len(part)?).ok_or(())?;
    }
    let budget = MAX_SNAPSHOT_BYTES.saturating_sub(8 * 1024);
    if base_size > budget {
        return Ok(ok_response(
            id,
            digest,
            Value::map([("exceeded", true.into())]),
        ));
    }
    let mut per_heap = BTreeMap::new();
    let mut per_heap_size = 0usize;
    for name in &heap_names {
        let payload = engine_call(
            session,
            id,
            digest,
            "histogram",
            Value::map([("heapName", name.as_str().into())]),
        )?;
        let Value::Map(mut payload) = payload else {
            return Err(());
        };
        let heap_summary = payload.remove("summary").ok_or(())?;
        let heap_classes = payload.remove("classes").ok_or(())?;
        per_heap_size = per_heap_size
            .checked_add(encoded_len(&heap_summary)?)
            .ok_or(())?
            .checked_add(encoded_len(&heap_classes)?)
            .ok_or(())?
            .checked_add(name.len() + 64)
            .ok_or(())?;
        per_heap.insert(name.clone(), heap_classes);
    }
    let per_heap_omitted = base_size.saturating_add(per_heap_size) > budget;
    let mut payload = BTreeMap::new();
    payload.insert("summary".into(), summary);
    payload.insert("classes".into(), classes);
    payload.insert("perHeapOmitted".into(), per_heap_omitted.into());
    if !per_heap_omitted {
        payload.insert("perHeap".into(), Value::Map(per_heap));
    }
    payload.insert("findings".into(), findings);
    Ok(ok_response(id, digest, Value::Map(payload)))
}

/// Runs one engine operation through the shared session dispatcher. The
/// worker never reaches into the engine directly; the payload stays bounded by
/// the engine's own per-response budgets.
fn engine_call(
    session: &mut Session,
    id: i64,
    digest: &str,
    operation: &str,
    args: Value,
) -> Result<Value, ()> {
    let request = Value::map([
        ("version", wire::VERSION.into()),
        ("id", id.into()),
        ("op", operation.into()),
        ("sourceDigest", digest.into()),
        ("args", args),
    ]);
    let (response, _) = session.respond(&request);
    let Value::Map(mut root) = response else {
        return Err(());
    };
    if root.get("status").and_then(|value| value.as_str().ok()) != Some("ok") {
        return Err(());
    }
    root.remove("payload").ok_or(())
}

/// Findings keep their full totals but only the first bounded page of rows.
fn truncate_findings(value: Value) -> Result<Value, ()> {
    let Value::Map(mut findings) = value else {
        return Err(());
    };
    let mut out = BTreeMap::new();
    for kind in [
        "leakSuspects",
        "bitmapInstances",
        "activityLeaks",
        "lifecycleLeakObjects",
    ] {
        let list = findings.remove(kind).ok_or(())?;
        let Value::List(mut rows) = list else {
            return Err(());
        };
        let total = rows.len() as i64;
        rows.truncate(MAX_PAGE_SIZE);
        out.insert(
            kind.into(),
            Value::map([("total", total.into()), ("rows", Value::List(rows))]),
        );
    }
    Ok(Value::Map(out))
}

fn encoded_len(value: &Value) -> Result<usize, ()> {
    wire::encode(value).map(|bytes| bytes.len()).map_err(|_| ())
}

fn ok_response(id: i64, digest: &str, payload: Value) -> Value {
    Value::map([
        ("version", wire::VERSION.into()),
        ("id", id.into()),
        ("sourceDigest", digest.into()),
        ("status", "ok".into()),
        ("payload", payload),
    ])
}

/// The engine's histogram owns full-heap aggregation. This projection runs
/// only in the child and pages *after* filtering/sorting all class summaries.
/// The original summary remains whole-heap evidence, not a filtered subtotal.
fn histogram_rows(
    classes: Vec<Value>,
    search: &str,
    match_case: bool,
    sort: &str,
    offset: usize,
    limit: usize,
) -> Result<(Vec<Value>, usize), ()> {
    if search.len() > MAX_HISTOGRAM_SEARCH_BYTES
        || !matches!(sort, "count" | "shallow" | "retained" | "name")
    {
        return Err(());
    }
    struct ClassRow {
        value: Value,
        name: String,
        count: i64,
        shallow: i64,
        retained: i64,
    }
    let needle = (!match_case).then(|| search.to_lowercase());
    let mut matches = Vec::new();
    for value in classes {
        let name = value
            .get("className")
            .and_then(Value::as_str)
            .map_err(|_| ())?
            .to_owned();
        let count = value
            .get("instanceCount")
            .and_then(Value::as_int)
            .map_err(|_| ())?;
        let shallow = value
            .get("shallowSize")
            .and_then(Value::as_int)
            .map_err(|_| ())?;
        let retained = value
            .get("retainedSize")
            .and_then(Value::as_int)
            .map_err(|_| ())?;
        if count < 0 || shallow < 0 || retained < 0 {
            return Err(());
        }
        if !(if match_case {
            name.contains(search)
        } else {
            name.to_lowercase().contains(needle.as_deref().ok_or(())?)
        }) {
            continue;
        }
        matches.push(ClassRow {
            value,
            name,
            count,
            shallow,
            retained,
        });
    }
    matches.sort_unstable_by(|a, b| {
        let by_metric = match sort {
            "count" => b.count.cmp(&a.count),
            "shallow" => b.shallow.cmp(&a.shallow),
            "retained" => b.retained.cmp(&a.retained),
            "name" => std::cmp::Ordering::Equal,
            _ => unreachable!("validated sort"),
        };
        by_metric.then_with(|| a.name.cmp(&b.name))
    });
    let total = matches.len();
    Ok((
        matches
            .into_iter()
            .skip(offset)
            .take(limit)
            .map(|row| row.value)
            .collect(),
        total,
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn row(name: &str, count: i64, shallow: i64, retained: i64) -> Value {
        Value::map([
            ("className", name.into()),
            ("instanceCount", count.into()),
            ("shallowSize", shallow.into()),
            ("retainedSize", retained.into()),
        ])
    }

    fn names(rows: Vec<Value>) -> Vec<String> {
        rows.into_iter()
            .map(|row| row.get("className").unwrap().as_str().unwrap().to_owned())
            .collect()
    }

    #[test]
    fn whole_histogram_filters_and_sorts_before_paging() {
        let rows = vec![
            row("app.One", 2, 40, 80),
            row("sys.Other", 100, 100, 100),
            row("app.Two", 3, 10, 20),
            row("app.Three", 3, 90, 5),
        ];
        let (page, total) = histogram_rows(rows.clone(), "app.", true, "count", 1, 1).unwrap();
        assert_eq!(total, 3);
        assert_eq!(names(page), vec!["app.Two"]);
        assert_eq!(
            names(
                histogram_rows(rows.clone(), "APP.", false, "shallow", 0, 4)
                    .unwrap()
                    .0
            ),
            vec!["app.Three", "app.One", "app.Two"]
        );
        assert_eq!(
            names(
                histogram_rows(rows.clone(), "app.", true, "retained", 0, 4)
                    .unwrap()
                    .0
            ),
            vec!["app.One", "app.Two", "app.Three"]
        );
        assert_eq!(
            names(histogram_rows(rows, "app.", true, "name", 0, 4).unwrap().0),
            vec!["app.One", "app.Three", "app.Two"]
        );
    }

    #[test]
    fn snapshot_findings_keep_totals_and_bound_rows() {
        let rows = |count: usize| {
            Value::List(
                (0..count)
                    .map(|index| Value::map([("objectId", (index as i64).into())]))
                    .collect(),
            )
        };
        let findings = Value::map([
            ("leakSuspects", rows(201)),
            ("bitmapInstances", rows(0)),
            ("activityLeaks", rows(3)),
            ("lifecycleLeakObjects", rows(500)),
        ]);
        let truncated = truncate_findings(findings).unwrap();
        for (kind, total) in [
            ("leakSuspects", 201i64),
            ("bitmapInstances", 0),
            ("activityLeaks", 3),
            ("lifecycleLeakObjects", 500),
        ] {
            let page = truncated.get(kind).unwrap();
            assert_eq!(page.get("total").unwrap().as_int().unwrap(), total);
            let rows = page.get("rows").unwrap().as_list().unwrap();
            assert!(rows.len() <= MAX_PAGE_SIZE);
            assert_eq!(rows.len() as i64, total.min(MAX_PAGE_SIZE as i64));
        }
    }

    #[test]
    fn presentation_bound_allows_five_hundred_top_level_rows() {
        let mut omissions = Vec::new();
        let mut payload = Value::map([(
            "rows",
            Value::List((0..MAX_UI_PAGE_SIZE).map(|_| Value::Null).collect()),
        )]);
        bounded(&mut payload, "payload", &mut omissions, 0).unwrap();
        assert!(omissions.is_empty());
        assert_eq!(
            payload.get("rows").unwrap().as_list().unwrap().len(),
            MAX_UI_PAGE_SIZE
        );

        let mut omissions = Vec::new();
        let mut nested = Value::map([(
            "references",
            Value::List((0..300).map(|_| Value::Null).collect()),
        )]);
        bounded(&mut nested, "payload", &mut omissions, 0).unwrap();
        assert_eq!(omissions.len(), 1);
        assert_eq!(
            nested.get("references").unwrap().as_list().unwrap().len(),
            MAX_PAGE_SIZE
        );
    }

    #[test]
    fn empty_and_invalid_queries_do_not_invent_evidence() {
        let rows = vec![row("app.One", 2, 40, 80)];
        assert_eq!(
            histogram_rows(rows.clone(), "missing", true, "count", 0, 10)
                .unwrap()
                .1,
            0
        );
        assert!(
            histogram_rows(
                rows.clone(),
                &"x".repeat(MAX_HISTOGRAM_SEARCH_BYTES + 1),
                true,
                "count",
                0,
                10
            )
            .is_err()
        );
        assert!(histogram_rows(rows.clone(), "", false, "unspecified", 0, 10).is_err());
        assert!(histogram_rows(vec![row("bad", -1, 0, 0)], "", false, "count", 0, 10).is_err());
        assert_eq!(
            names(histogram_rows(rows, "", false, "count", 3, 10).unwrap().0),
            Vec::<String>::new()
        );
    }
}

fn as_map_mut(value: &mut Value) -> Result<&mut BTreeMap<String, Value>, ()> {
    match value {
        Value::Map(map) => Ok(map),
        _ => Err(()),
    }
}
fn index(args: &BTreeMap<String, Value>, key: &str) -> Result<usize, ()> {
    usize::try_from(args.get(key).ok_or(())?.as_int().map_err(|_| ())?).map_err(|_| ())
}

fn bounded(
    value: &mut Value,
    path: &str,
    omissions: &mut Vec<Value>,
    depth: usize,
) -> Result<(), ()> {
    if depth > 32 || omissions.len() > 4096 {
        return Err(());
    }
    match value {
        Value::List(values) => {
            // The top-level rows list is already bounded by the requested UI
            // page; nested row lists keep the 200-entry presentation cap.
            let visible_limit = if path == "payload.rows" {
                MAX_UI_PAGE_SIZE
            } else {
                MAX_PAGE_SIZE
            };
            if values.len() > visible_limit {
                omissions.push(Value::map([
                    ("path", path.into()),
                    ("originalCount", (values.len() as i64).into()),
                    ("visibleCount", (visible_limit as i64).into()),
                ]));
                values.truncate(visible_limit);
            }
            for (index, value) in values.iter_mut().enumerate() {
                bounded(value, &format!("{path}[{index}]"), omissions, depth + 1)?;
            }
        }
        Value::Map(values) => {
            for (key, value) in values.iter_mut() {
                bounded(value, &format!("{path}.{key}"), omissions, depth + 1)?;
            }
        }
        Value::String(text) if text.len() > 4096 => {
            let original = text.len();
            let mut end = 4096;
            while !text.is_char_boundary(end) {
                end -= 1;
            }
            text.truncate(end);
            omissions.push(Value::map([
                ("path", path.into()),
                ("originalBytes", (original as i64).into()),
                ("visibleBytes", (end as i64).into()),
            ]));
        }
        _ => {}
    }
    Ok(())
}

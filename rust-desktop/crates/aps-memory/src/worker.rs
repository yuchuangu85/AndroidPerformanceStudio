//! Trusted child-process adapter around the retained HPROF engine. Pagination
//! takes place here, before framing, never by loading a full heap into the UI.

use crate::{
    MAX_HISTOGRAM_SEARCH_BYTES, MAX_INPUT_BYTES, MAX_MAPPING_BYTES, MAX_MAPPING_ENTRIES,
    MAX_PAGE_SIZE, MAX_RESPONSE_BYTES, MAX_SNAPSHOT_BYTES, MAX_UI_PAGE_SIZE, encode_response, page,
    page_with,
};
use aps_hprof_engine::{
    Session, cache,
    wire::{self, Value},
};
use std::collections::BTreeMap;
use std::io::{self, Read, Write};
use std::path::Path;

const MAX_HISTOGRAM_CACHE_BYTES: usize = 2 * MAX_SNAPSHOT_BYTES;

#[derive(Default)]
struct HistogramCache {
    source_digest: String,
    entries: BTreeMap<String, Box<[u8]>>,
    bytes: usize,
}

impl HistogramCache {
    fn new(digest: &str) -> Self {
        Self {
            source_digest: digest.to_owned(),
            ..Self::default()
        }
    }

    fn clear(&mut self) {
        self.entries.clear();
        self.source_digest.clear();
        self.bytes = 0;
    }

    fn insert(&mut self, heap: &str, payload: &Value) {
        if let Some(previous) = self.entries.remove(heap) {
            self.bytes -= previous.len() + heap.len() + 128;
        }
        let Ok(bytes) = wire::encode(payload) else {
            return;
        };
        // Encoded projections bound retained allocation, including entry/key
        // overhead. Reserve room for the response envelope on later fallback.
        let allocation = bytes.len().saturating_add(heap.len()).saturating_add(128);
        if bytes.len() > MAX_SNAPSHOT_BYTES - 1024
            || allocation > MAX_HISTOGRAM_CACHE_BYTES.saturating_sub(self.bytes)
            || self.entries.len() >= 64
        {
            return;
        }
        self.bytes += allocation;
        self.entries
            .insert(heap.to_owned(), bytes.into_boxed_slice());
    }

    fn get(&self, digest: &str, heap: &str) -> Result<Option<Value>, ()> {
        if self.entries.is_empty() {
            return Ok(None);
        }
        if self.source_digest != digest {
            return Err(());
        }
        self.entries
            .get(heap)
            .map(|bytes| wire::decode(bytes).map_err(|_| ()))
            .transpose()
    }
}

pub fn serve(reader: &mut impl Read, writer: &mut impl Write) -> io::Result<()> {
    let mut session = Session::default();
    let mut histogram_cache = HistogramCache::default();
    let mut digest = String::new();
    while let Some(bytes) = wire::read_frame(reader).map_err(|_| invalid())? {
        let request = wire::decode(&bytes).map_err(|_| invalid())?;
        let id = request
            .get("id")
            .and_then(Value::as_int)
            .map_err(|_| invalid())?;
        // The combined snapshot read is one deliberate, bounded response; every
        // other operation keeps the 2 MiB page-response ceiling.
        let snapshot = matches!(
            request.get("op").and_then(Value::as_str),
            Ok("snapshot" | "heapHistogram")
        );
        if request.get("op").and_then(Value::as_str) == Ok("open") {
            digest = request
                .get("sourceDigest")
                .and_then(Value::as_str)
                .map_err(|_| invalid())?
                .to_owned();
        }
        let result = respond(&mut session, &mut histogram_cache, &request);
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
        write_worker_response(writer, &response, id, &digest, max_response)?;
    }
    Ok(())
}

fn write_worker_response(
    writer: &mut impl Write,
    response: &Value,
    id: i64,
    digest: &str,
    max_response: usize,
) -> io::Result<()> {
    let bytes = encode_response(response, max_response)
        .or_else(|_| encode_response(&error(id, digest), max_response))?;
    wire::write_encoded(writer, &bytes).map_err(|_| io::ErrorKind::BrokenPipe.into())
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

fn respond(
    session: &mut Session,
    cache: &mut HistogramCache,
    request: &Value,
) -> Result<Value, ()> {
    let result = respond_inner(session, cache, request);
    if result.is_err() {
        cache.clear();
    }
    result
}

fn respond_inner(
    session: &mut Session,
    cache: &mut HistogramCache,
    request: &Value,
) -> Result<Value, ()> {
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
        cache.clear();
        return snapshot_response(session, cache, request, args);
    }
    if matches!(
        operation,
        "retainedSummary" | "heapRetainedSummary" | "checkSource"
    ) {
        let id = request.get("id").and_then(Value::as_int).map_err(|_| ())?;
        let digest = request
            .get("sourceDigest")
            .and_then(Value::as_str)
            .map_err(|_| ())?;
        let payload = if operation == "checkSource" {
            engine_call(session, id, digest, "checkSource", Value::map([]))?
        } else {
            let heap = if operation == "heapRetainedSummary" {
                Some(args.get("heapName").ok_or(())?.as_str().map_err(|_| ())?)
            } else {
                None
            };
            retained_summary_response(session, request, heap)?
        };
        return Ok(ok_response(id, digest, payload));
    }
    if operation == "heapHistogram" {
        let id = request.get("id").and_then(Value::as_int).map_err(|_| ())?;
        let digest = request
            .get("sourceDigest")
            .and_then(Value::as_str)
            .map_err(|_| ())?;
        let heap = args.get("heapName").ok_or(())?.as_str().map_err(|_| ())?;
        if heap.is_empty() || heap.len() > 4096 {
            return Err(());
        }
        let payload = if let Some(payload) = cache.get(digest, heap)? {
            engine_call(session, id, digest, "checkSource", Value::map([]))?;
            payload
        } else {
            engine_call(
                session,
                id,
                digest,
                "histogram",
                Value::map([("heapName", heap.into())]),
            )?
        };
        return Ok(ok_response(id, digest, payload));
    }
    if operation == "open" {
        cache.clear();
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
            let object_id = args.get("objectId").ok_or(())?.as_int().map_err(|_| ())?;
            let id = request.get("id").and_then(Value::as_int).map_err(|_| ())?;
            let digest = request
                .get("sourceDigest")
                .and_then(Value::as_str)
                .map_err(|_| ())?;
            let identity = engine_call(
                session,
                id,
                digest,
                "identity",
                Value::map([("objectId", object_id.into())]),
            )?;
            let heap_name = identity.get("heapName").cloned().unwrap_or(Value::Null);
            let map = as_map_mut(payload)?;
            map.insert("heapName".into(), heap_name);
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
    cache: &mut HistogramCache,
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
    heap_names.sort();
    heap_names.dedup();
    let bundle = engine_call(
        session,
        id,
        digest,
        "histogramBundle",
        Value::map([(
            "heapNames",
            Value::List(heap_names.iter().cloned().map(Value::from).collect()),
        )]),
    )?;
    let Value::Map(mut bundle) = bundle else {
        return Err(());
    };
    let histogram = bundle.remove("all").ok_or(())?;
    let Value::Map(mut heap_histograms) = bundle.remove("perHeap").ok_or(())? else {
        return Err(());
    };
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
    let mut per_heap_size = 0usize;
    for name in &heap_names {
        let payload = heap_histograms.get(name).ok_or(())?;
        let heap_summary = payload.get("summary").map_err(|_| ())?;
        let heap_classes = payload.get("classes").map_err(|_| ())?;
        per_heap_size = per_heap_size
            .checked_add(encoded_len(heap_summary)?)
            .ok_or(())?
            .checked_add(encoded_len(heap_classes)?)
            .ok_or(())?
            .checked_add(2 * name.len() + 64)
            .ok_or(())?;
    }
    let per_heap_omitted = base_size.saturating_add(per_heap_size) > budget;
    let mut payload = BTreeMap::new();
    let retained = retained_summary_response(session, request, None)?;
    let mut summary = summary;
    as_map_mut(&mut summary)?.insert(
        "retainedSize".into(),
        retained.get("retainedSize").map_err(|_| ())?.clone(),
    );
    payload.insert("summary".into(), summary);
    payload.insert("classes".into(), classes);
    payload.insert("perHeapOmitted".into(), per_heap_omitted.into());
    if per_heap_omitted {
        *cache = HistogramCache::new(digest);
        for name in &heap_names {
            cache.insert(name, heap_histograms.get(name).ok_or(())?);
        }
    } else {
        let mut per_heap = BTreeMap::new();
        let mut per_heap_summaries = BTreeMap::new();
        for name in heap_names {
            let Value::Map(mut heap) = heap_histograms.remove(&name).ok_or(())? else {
                return Err(());
            };
            per_heap.insert(name.clone(), heap.remove("classes").ok_or(())?);
            per_heap_summaries.insert(name, heap.remove("summary").ok_or(())?);
        }
        payload.insert("perHeap".into(), Value::Map(per_heap));
        payload.insert("perHeapSummaries".into(), Value::Map(per_heap_summaries));
    }
    payload.insert("findings".into(), findings);
    engine_call(session, id, digest, "checkSource", Value::map([]))?;
    Ok(ok_response(id, digest, Value::Map(payload)))
}

fn retained_summary_response(
    session: &mut Session,
    request: &Value,
    heap_name: Option<&str>,
) -> Result<Value, ()> {
    let id = request.get("id").and_then(Value::as_int).map_err(|_| ())?;
    let digest = request
        .get("sourceDigest")
        .and_then(Value::as_str)
        .map_err(|_| ())?;
    engine_call(
        session,
        id,
        digest,
        "retainedSummary",
        Value::map([("heapName", heap_name.into())]),
    )
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
    fn omitted_histogram_cache_is_bounded_and_source_scoped() {
        let mut cache = HistogramCache::new("source-a");
        let payload = Value::map([("classes", Value::List(Vec::new()))]);
        cache.insert("App", &payload);
        assert_eq!(cache.get("source-a", "App").unwrap(), Some(payload.clone()));
        assert!(cache.get("source-b", "App").is_err());

        // Valid wire values can still exceed the presentation/cache budgets.
        let large = Value::List(vec!["x".repeat(wire::MAX_STRING).into(); 4]);
        cache.insert("App", &large);
        assert_eq!(cache.get("source-a", "App").unwrap(), None);
        for index in 0..10 {
            cache.insert(&format!("heap-{index}"), &large);
        }
        assert!(cache.bytes <= MAX_HISTOGRAM_CACHE_BYTES);
        assert!(cache.entries.len() <= 64);
        cache.clear();
        assert_eq!(cache.bytes, 0);
        assert!(cache.entries.is_empty());
    }

    #[test]
    fn cached_histogram_payloads_do_not_exceed_the_aggregate_budget() {
        let mut cache = HistogramCache::new("source");
        let payload = Value::List(vec!["x".repeat(wire::MAX_STRING).into(); 3]);
        for index in 0..6 {
            cache.insert(&format!("heap-{index}"), &payload);
        }
        assert!(cache.bytes <= MAX_HISTOGRAM_CACHE_BYTES);
        assert_eq!(cache.entries.len(), 2);
        assert_eq!(cache.get("source", "heap-0").unwrap(), Some(payload));
        assert_eq!(cache.get("source", "heap-5").unwrap(), None);
    }

    struct SourceFixture(std::path::PathBuf);

    impl SourceFixture {
        fn open() -> (Self, Session, String) {
            use std::sync::atomic::{AtomicU64, Ordering};
            static NEXT: AtomicU64 = AtomicU64::new(0);
            let path = std::env::temp_dir().join(format!(
                "aps-worker-cache-{}-{}.hprof",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            let mut bytes = b"JAVA PROFILE 1.0.3\0".to_vec();
            bytes.extend(4u32.to_be_bytes());
            bytes.extend(1u64.to_be_bytes());
            std::fs::write(&path, bytes).unwrap();
            let digest = cache::sha256_file(&path).unwrap();
            let mut session = Session::default();
            engine_call(
                &mut session,
                1,
                &digest,
                "open",
                Value::map([
                    ("path", path.to_str().unwrap().into()),
                    ("sourceDigest", digest.as_str().into()),
                    ("mapping", Value::map([])),
                    ("cacheDir", Value::Null),
                    ("graphStorage", "memory".into()),
                ]),
            )
            .unwrap();
            (Self(path), session, digest)
        }
    }

    impl Drop for SourceFixture {
        fn drop(&mut self) {
            let _ = std::fs::remove_file(&self.0);
        }
    }

    fn heap_request(digest: &str) -> Value {
        Value::map([
            ("version", wire::VERSION.into()),
            ("id", 2i64.into()),
            ("op", "heapHistogram".into()),
            ("sourceDigest", digest.into()),
            ("args", Value::map([("heapName", "App".into())])),
        ])
    }

    #[test]
    fn cached_fallback_matches_engine_and_rejects_changed_source() {
        for replace in [false, true] {
            let (file, mut session, digest) = SourceFixture::open();
            let mut cache = HistogramCache::new(&digest);
            let request = heap_request(&digest);
            let expected = respond(&mut session, &mut cache, &request).unwrap();
            cache.insert("App", expected.get("payload").unwrap());
            assert_eq!(
                respond(&mut session, &mut cache, &request).unwrap(),
                expected
            );
            assert!(!cache.entries.is_empty());
            let mut marked = expected.get("payload").unwrap().as_map().unwrap().clone();
            marked.insert("cacheHitMarker".into(), true.into());
            cache.insert("App", &Value::Map(marked));
            assert_eq!(
                respond(&mut session, &mut cache, &request)
                    .unwrap()
                    .get("payload")
                    .unwrap()
                    .get("cacheHitMarker")
                    .unwrap(),
                &Value::Bool(true)
            );
            if replace {
                let replacement = file.0.with_extension("replacement");
                std::fs::copy(&file.0, &replacement).unwrap();
                std::fs::rename(replacement, &file.0).unwrap();
            } else {
                std::fs::OpenOptions::new()
                    .write(true)
                    .open(&file.0)
                    .unwrap()
                    .set_len(0)
                    .unwrap();
            }
            assert!(respond(&mut session, &mut cache, &request).is_err());
            assert!(cache.entries.is_empty());
            assert_eq!(cache.bytes, 0);
        }
    }

    #[test]
    fn corrupt_or_wrong_source_cache_entries_fail_closed() {
        let (_file, mut session, digest) = SourceFixture::open();
        let mut cache = HistogramCache::new(&digest);
        let request = heap_request(&digest);
        let expected = respond(&mut session, &mut cache, &request).unwrap();
        cache.insert("App", expected.get("payload").unwrap());
        cache.entries.get_mut("App").unwrap()[0] = 255;
        assert!(respond(&mut session, &mut cache, &request).is_err());
        assert!(cache.entries.is_empty());
        cache = HistogramCache::new("wrong-source");
        cache.insert("App", expected.get("payload").unwrap());
        assert!(respond(&mut session, &mut cache, &request).is_err());
        assert!(cache.entries.is_empty());
    }

    fn decoded_frame(bytes: Vec<u8>) -> Value {
        let mut reader = io::Cursor::new(bytes);
        let frame = wire::read_frame(&mut reader).unwrap().unwrap();
        assert!(wire::read_frame(&mut reader).unwrap().is_none());
        wire::decode(&frame).unwrap()
    }

    #[test]
    fn worker_response_encodes_once_and_preserves_limit_errors() {
        let response = ok_response(17, "digest", Value::List(vec![42i64.into()]));
        let expected = wire::encode(&response).unwrap();
        let mut output = Vec::new();
        write_worker_response(&mut output, &response, 17, "digest", expected.len()).unwrap();
        assert_eq!(&output[4..], expected);

        let large = ok_response(17, "digest", "x".repeat(MAX_RESPONSE_BYTES).into());
        let unencodable = ok_response(17, "digest", "x".repeat(wire::MAX_STRING + 1).into());
        for value in [large, unencodable] {
            let mut output = Vec::new();
            write_worker_response(&mut output, &value, 17, "digest", MAX_RESPONSE_BYTES).unwrap();
            assert_eq!(decoded_frame(output), error(17, "digest"));
        }
        let mut output = Vec::new();
        assert_eq!(
            write_worker_response(&mut output, &response, 17, "digest", 1)
                .unwrap_err()
                .kind(),
            io::ErrorKind::InvalidData
        );
        assert!(output.is_empty());
    }

    #[test]
    fn worker_response_propagates_write_and_flush_failures() {
        struct FailingWriter(bool);
        impl Write for FailingWriter {
            fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
                if self.0 {
                    Ok(bytes.len())
                } else {
                    Err(io::ErrorKind::Other.into())
                }
            }
            fn flush(&mut self) -> io::Result<()> {
                Err(io::ErrorKind::Other.into())
            }
        }
        for fail_on_flush in [false, true] {
            assert_eq!(
                write_worker_response(
                    &mut FailingWriter(fail_on_flush),
                    &error(1, "digest"),
                    1,
                    "digest",
                    MAX_RESPONSE_BYTES,
                )
                .unwrap_err()
                .kind(),
                io::ErrorKind::BrokenPipe
            );
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

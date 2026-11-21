//! Bounded MCP evidence for a single memory snapshot.
use aps_hprof_engine::wire::Value as WireValue;
use aps_memory::{FindingKind, HistogramQuery, HistogramSort, MemorySession};
use aps_platform::CancellationToken;
use serde_json::{Map, Value, json};

pub(super) fn snapshot_summary(
    session: &mut MemorySession,
    digest: &str,
    cancel: &CancellationToken,
) -> Result<Value, String> {
    let histogram = session
        .histogram(None, 0, 1, cancel)
        .map_err(|error| error.to_string())?;
    Ok(
        json!({"sourceDigest":digest,"metadata":wire_json(session.metadata()),"summary":wire_json(histogram.get("summary").map_err(|error| error.to_string())?),"evidenceBoundary":"offline HPROF; unavailable metrics remain unknown"}),
    )
}

pub(super) fn leak_findings(
    session: &mut MemorySession,
    digest: &str,
    cancel: &CancellationToken,
) -> Result<Value, String> {
    let mut findings = Map::new();
    for kind in [
        FindingKind::LeakSuspects,
        FindingKind::ActivityLeaks,
        FindingKind::LifecycleLeakObjects,
    ] {
        let value = session
            .findings(kind, 0, 32, cancel)
            .map_err(|error| error.to_string())?;
        let key = match kind {
            FindingKind::LeakSuspects => "leakSuspects",
            FindingKind::ActivityLeaks => "activityLeaks",
            FindingKind::LifecycleLeakObjects => "lifecycleLeakObjects",
            FindingKind::BitmapInstances => "bitmapInstances",
        };
        findings.insert(key.to_owned(), wire_json(&value));
    }
    Ok(
        json!({"sourceDigest":digest,"heuristicOnly":true,"findings":findings,"evidenceBoundary":"candidates are not confirmed leaks; omitted rows remain unknown"}),
    )
}

pub(super) fn class_histogram(
    session: &mut MemorySession,
    args: &Value,
    cancel: &CancellationToken,
) -> Result<Value, String> {
    let search = args.get("search").and_then(Value::as_str).unwrap_or("");
    if search.len() > 1024 {
        return Err("search exceeds MCP limit".into());
    }
    let limit = args
        .get("limit")
        .and_then(Value::as_u64)
        .unwrap_or(50)
        .min(100) as usize;
    let query = HistogramQuery {
        search: search.to_owned(),
        match_case: false,
        sort: HistogramSort::RetainedDescending,
    };
    let histogram = session
        .histogram_query(
            args.get("heapName").and_then(Value::as_str),
            &query,
            0,
            limit,
            cancel,
        )
        .map_err(|error| error.to_string())?;
    let Value::Object(mut payload) = wire_json(&histogram) else {
        return Err("invalid histogram payload".into());
    };
    payload.insert("sourceDigest".into(), session.source_digest().into());
    Ok(Value::Object(payload))
}

pub(super) fn object_detail(
    session: &mut MemorySession,
    args: &Value,
    cancel: &CancellationToken,
) -> Result<Value, String> {
    let raw = args
        .get("objectId")
        .and_then(Value::as_str)
        .ok_or("objectId must be a hexadecimal string")?;
    let object_id = raw
        .strip_prefix("0x")
        .unwrap_or(raw)
        .parse::<u64>()
        .map_err(|_| "objectId is invalid")?;
    let offset = args
        .get("offset")
        .and_then(Value::as_u64)
        .unwrap_or(0)
        .min(1000) as usize;
    let limit = args
        .get("limit")
        .and_then(Value::as_u64)
        .unwrap_or(100)
        .min(100) as usize;
    session
        .detail(object_id, offset, limit, cancel)
        .map(|value| wire_json(&value))
        .map_err(|error| error.to_string())
}

fn wire_json(value: &WireValue) -> Value {
    match value {
        WireValue::Null => Value::Null,
        WireValue::Bool(value) => Value::Bool(*value),
        WireValue::Int(value) => json!(value),
        WireValue::String(value) => Value::String(value.clone()),
        WireValue::List(values) => Value::Array(values.iter().map(wire_json).collect()),
        WireValue::Map(values) => Value::Object(
            values
                .iter()
                .map(|(key, value)| (key.clone(), wire_json(value)))
                .collect(),
        ),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn wire_values_convert_to_json_without_paths_or_raw_frames() {
        let value = WireValue::map([
            ("sourceDigest", "a".repeat(64).into()),
            (
                "rows",
                WireValue::List(vec![1i64.into(), "candidate".into()]),
            ),
        ]);
        let json = wire_json(&value);
        assert_eq!(json["rows"][0], 1);
        assert_eq!(json["rows"][1], "candidate");
        assert!(json.get("path").is_none());
    }
}

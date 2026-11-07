//! Metadata-only trace AI context.
//!
//! Compose parity with TraceAiContext.traceAiContext: the selected diagnostic
//! result is projected to numeric columns only. Thread names, process names,
//! arbitrary text cells, SQL, paths and trace identity strings never leave this
//! module. The payload is bounded JSON and the evidence id is a SHA-256 binding
//! of artifact identity, query id and the serialized payload.

use aps_ai::{AnalysisRequest, Evidence};
use aps_contracts::SourceDigest;
use aps_json::{Limits, Map, Number, Value};
use aps_perfetto::{DiagnosticQuery, DiagnosticResult};

const MAX_INCLUDED_ROWS: usize = 20;
const MAX_PAYLOAD_BYTES: u64 = 8 * 1024;
const MAX_STRING_BYTES: usize = 2_048;
const EVIDENCE_KIND: &str = "trace-selection";
const EVIDENCE_SUMMARY: &str = "Selected diagnostic numeric results; query coverage may be limited";

fn payload_limits() -> Limits {
    Limits {
        max_bytes: MAX_PAYLOAD_BYTES,
        max_depth: 8,
        max_nodes: 10_000,
        max_string_bytes: MAX_STRING_BYTES,
        max_collection_items: 128,
    }
}

fn columns_match(query: &DiagnosticQuery, result: &DiagnosticResult) -> bool {
    result.columns.len() == query.columns.len()
        && result
            .columns
            .iter()
            .zip(query.columns.iter())
            .all(|(result_column, query_column)| result_column == query_column)
}

fn numeric_column(name: &str) -> bool {
    !name.is_empty()
        && name
            .bytes()
            .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || byte == b'_')
        && (name.ends_with("_ns")
            || name.ends_with("_ms")
            || name.ends_with("_count")
            || name.ends_with("_bytes")
            || matches!(name, "cpu" | "count" | "frequency" | "value"))
}

fn numeric_columns(columns: &[String]) -> Vec<(usize, String)> {
    columns
        .iter()
        .enumerate()
        .filter(|(_, name)| numeric_column(name))
        .map(|(index, name)| (index, name.clone()))
        .collect()
}

fn unit(name: &str) -> &'static str {
    if name.ends_with("_ns") {
        "nanoseconds"
    } else if name.ends_with("_ms") {
        "milliseconds"
    } else if name.ends_with("_bytes") {
        "bytes"
    } else if name.ends_with("_count") || name == "count" {
        "count"
    } else {
        "query-defined"
    }
}

fn numeric_cell(row: &[Option<String>], index: usize) -> Value {
    row.get(index)
        .and_then(|value| value.as_deref())
        .map(str::trim)
        .and_then(|value| value.parse::<f64>().ok())
        .filter(|value| value.is_finite())
        .and_then(Number::from_f64)
        .map_or(Value::Null, Value::Number)
}

fn payload(
    query: &DiagnosticQuery,
    result: &DiagnosticResult,
    numeric: &[(usize, String)],
) -> Value {
    let included_rows = result.rows.len().min(MAX_INCLUDED_ROWS);
    let mut object = Map::new();
    object.insert("queryId".into(), Value::String(query.id.to_owned()));
    object.insert(
        "queryCategory".into(),
        Value::String(query.category.name().to_owned()),
    );
    object.insert("returnedRows".into(), Value::from(result.rows.len() as u64));
    object.insert("includedRows".into(), Value::from(included_rows as u64));
    object.insert(
        "omittedReturnedRows".into(),
        Value::from(result.rows.len().saturating_sub(MAX_INCLUDED_ROWS) as u64),
    );
    object.insert("queryMayBeLimited".into(), Value::Bool(true));
    object.insert(
        "omittedTextColumns".into(),
        Value::from(result.columns.len().saturating_sub(numeric.len()) as u64),
    );
    let mut units = Map::new();
    for (_, name) in numeric {
        units.insert(name.clone(), Value::String(unit(name).to_owned()));
    }
    object.insert("units".into(), Value::Object(units));
    let rows = result
        .rows
        .iter()
        .take(MAX_INCLUDED_ROWS)
        .map(|row| {
            let mut values = Map::new();
            for (index, name) in numeric {
                values.insert(name.clone(), numeric_cell(row, *index));
            }
            Value::Object(values)
        })
        .collect();
    object.insert("rows".into(), Value::Array(rows));
    Value::Object(object)
}

/// Builds the bounded, numeric-only AI evidence for one selected diagnostic
/// result. Fail closed where Compose returns a null context.
pub(in crate::trace) fn analysis_request(
    artifact_id: &str,
    query: &DiagnosticQuery,
    result: &DiagnosticResult,
) -> Result<AnalysisRequest, String> {
    if !columns_match(query, result) {
        return Err("Diagnostic result does not match the selected query columns".to_owned());
    }
    let numeric = numeric_columns(&result.columns);
    if numeric.is_empty() {
        return Err("Diagnostic result has no numeric columns for AI evidence".to_owned());
    }
    let payload = payload(query, result, &numeric);
    let payload_bytes = aps_json::to_vec(&payload, payload_limits())
        .map_err(|_| "Selected diagnostic evidence exceeds the AI context budget")?;
    let mut identity =
        Vec::with_capacity(artifact_id.len() + query.id.len() + payload_bytes.len() + 2);
    identity.extend_from_slice(artifact_id.as_bytes());
    identity.push(0);
    identity.extend_from_slice(query.id.as_bytes());
    identity.push(0);
    identity.extend_from_slice(&payload_bytes);
    let digest =
        SourceDigest::hash_reader(identity.as_slice(), MAX_PAYLOAD_BYTES.saturating_add(4_096))
            .map_err(|_| "Cannot bind the selected diagnostic evidence".to_owned())?;
    Ok(AnalysisRequest {
        session_id: "trace-view".to_owned(),
        run_id: "trace-selection".to_owned(),
        generation: 1,
        profiler: "trace".to_owned(),
        scope: "Trace diagnostic evidence".to_owned(),
        evidence: vec![Evidence {
            id: format!("{EVIDENCE_KIND}:{}", digest.to_hex()),
            kind: EVIDENCE_KIND.to_owned(),
            summary: EVIDENCE_SUMMARY.to_owned(),
            payload,
        }],
        candidates: Vec::new(),
        user_question: None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_perfetto::PINNED_VERSION;

    fn query(id: &str) -> &'static DiagnosticQuery {
        aps_perfetto::diagnostic_query(id).expect("catalog query")
    }

    fn result_for(query: &DiagnosticQuery, rows: Vec<Vec<Option<String>>>) -> DiagnosticResult {
        DiagnosticResult {
            query_id: query.id,
            schema_version: PINNED_VERSION,
            columns: query
                .columns
                .iter()
                .map(|column| (*column).to_owned())
                .collect(),
            rows,
            truncated: false,
        }
    }

    fn payload_text(request: &AnalysisRequest) -> String {
        let payload = &request.evidence[0].payload;
        String::from_utf8(aps_json::to_vec(payload, Limits::default()).unwrap()).unwrap()
    }

    #[test]
    fn projection_is_numeric_only_and_caps_rows() {
        let query = query("cpu_hotspots");
        assert_eq!(
            query.columns,
            ["thread_name", "process_name", "slice_count", "total_dur_ms"].as_slice()
        );
        let rows = (0..22)
            .map(|index| {
                vec![
                    Some(format!("private-thread-name-{index}")),
                    Some("com.example.private".to_owned()),
                    Some("7".to_owned()),
                    Some("12.5".to_owned()),
                ]
            })
            .collect();
        let result = result_for(query, rows);

        let request = analysis_request("artifact-1", query, &result).unwrap();
        assert_eq!(request.evidence.len(), 1);
        let evidence = &request.evidence[0];
        assert_eq!(evidence.kind, "trace-selection");
        assert_eq!(evidence.summary, EVIDENCE_SUMMARY);
        assert_eq!(request.generation, 1);
        assert!(request.candidates.is_empty());
        let payload = &evidence.payload;
        assert_eq!(payload["queryId"].as_str(), Some("cpu_hotspots"));
        assert_eq!(payload["queryCategory"].as_str(), Some("CPU"));
        assert_eq!(payload["returnedRows"].as_u64(), Some(22));
        assert_eq!(payload["includedRows"].as_u64(), Some(20));
        assert_eq!(payload["omittedReturnedRows"].as_u64(), Some(2));
        assert_eq!(payload["omittedTextColumns"].as_u64(), Some(2));
        assert_eq!(payload["queryMayBeLimited"].as_bool(), Some(true));
        assert_eq!(
            payload["units"]["total_dur_ms"].as_str(),
            Some("milliseconds")
        );
        assert_eq!(payload["units"]["slice_count"].as_str(), Some("count"));
        let projected = payload["rows"].as_array().unwrap();
        assert_eq!(projected.len(), 20);
        assert_eq!(projected[0]["total_dur_ms"].as_f64(), Some(12.5));
        assert_eq!(projected[0]["slice_count"].as_f64(), Some(7.0));
        assert!(projected[0].get("thread_name").is_none());
        assert!(projected[0].get("process_name").is_none());

        let text = payload_text(&request);
        assert!(!text.contains("private-thread-name"));
        assert!(!text.contains("com.example.private"));
        assert!(!text.contains("thread_name"));
        assert!(!text.contains("process_name"));
        assert!(!text.contains("SELECT"));
        assert!(!text.contains("sql"));
    }

    #[test]
    fn columns_drift_fails_closed() {
        let query = query("cpu_hotspots");
        let mut result = result_for(query, vec![vec![Some("x".into()); query.columns.len()]]);
        result.columns = vec!["thread_name".into()];
        assert!(analysis_request("artifact-1", query, &result).is_err());
        result.columns = query
            .columns
            .iter()
            .map(|column| (*column).to_owned())
            .chain(std::iter::once("other".to_owned()))
            .collect();
        assert!(analysis_request("artifact-1", query, &result).is_err());
    }

    #[test]
    fn text_only_results_fail_closed() {
        let query = query("mem_counters");
        let result = result_for(
            query,
            vec![
                query
                    .columns
                    .iter()
                    .map(|_| Some("text".to_owned()))
                    .collect();
                3
            ],
        );
        assert!(numeric_columns(&result.columns).is_empty());
        assert!(analysis_request("artifact-1", query, &result).is_err());
    }

    #[test]
    fn numeric_predicate_matches_compose() {
        for name in [
            "total_dur_ms",
            "duration_ns",
            "slice_count",
            "memory_bytes",
            "cpu",
            "count",
            "frequency",
            "value",
        ] {
            assert!(numeric_column(name), "{name} should be numeric");
        }
        for name in [
            "thread_name",
            "process_name",
            "name",
            "GPU",
            "Freq_Mhz",
            "avg_kb",
            "janky_frames",
            "a-b",
            "",
            "123abc",
        ] {
            assert!(!numeric_column(name), "{name} should not be numeric");
        }
    }

    #[test]
    fn evidence_id_binds_artifact_query_and_payload() {
        let query = query("cpu_hotspots");
        let rows = || {
            vec![vec![
                Some("thread".into()),
                Some("process".into()),
                Some("1".into()),
                Some("2.5".into()),
            ]]
        };
        let first = analysis_request("artifact-1", query, &result_for(query, rows())).unwrap();
        let second = analysis_request("artifact-2", query, &result_for(query, rows())).unwrap();
        let same = analysis_request("artifact-1", query, &result_for(query, rows())).unwrap();
        assert_ne!(first.evidence[0].id, second.evidence[0].id);
        assert_eq!(first.evidence[0].id, same.evidence[0].id);
        assert!(first.evidence[0].id.starts_with("trace-selection:"));
        assert_eq!(first.evidence[0].id.len(), "trace-selection:".len() + 64);

        let other_rows = vec![vec![
            Some("thread".into()),
            Some("process".into()),
            Some("3".into()),
            Some("4.5".into()),
        ]];
        let changed =
            analysis_request("artifact-1", query, &result_for(query, other_rows)).unwrap();
        assert_ne!(first.evidence[0].id, changed.evidence[0].id);
    }

    #[test]
    fn non_finite_and_missing_cells_become_null_not_text() {
        let query = query("cpu_hotspots");
        let result = result_for(
            query,
            vec![vec![
                Some("thread".into()),
                Some("process".into()),
                Some("NaN".into()),
                None,
            ]],
        );
        let request = analysis_request("artifact-1", query, &result).unwrap();
        let row = &request.evidence[0].payload["rows"][0];
        assert!(row["slice_count"].is_null());
        assert!(row["total_dur_ms"].is_null());
    }
}

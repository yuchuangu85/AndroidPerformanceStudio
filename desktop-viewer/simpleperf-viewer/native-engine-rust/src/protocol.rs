use crate::query::{project, Query, QueryPage};
use crate::simpleperf::{parse_reader_with_limit, SimpleperfStream, MAX_STREAM_BYTES};
use crate::wire::{self, Value};
use crate::AnalysisResult;
use std::fs::File;
use std::io::{BufReader, Read, Write};
use std::path::Path;

pub fn input_limit_from_env() -> Result<usize, String> {
    match std::env::var("APS_SIMPLEPERF_MAX_INPUT_BYTES") {
        Err(std::env::VarError::NotPresent) => Ok(MAX_STREAM_BYTES),
        Err(_) => Err("APS_SIMPLEPERF_MAX_INPUT_BYTES is not valid UTF-8".into()),
        Ok(value) => value
            .parse::<usize>()
            .ok()
            .filter(|limit| *limit > 0 && *limit < isize::MAX as usize)
            .ok_or_else(|| {
                "APS_SIMPLEPERF_MAX_INPUT_BYTES must be a positive bounded integer".into()
            }),
    }
}

pub fn analyze_path(path: &Path, limit: usize) -> Result<AnalysisResult, String> {
    Ok(crate::analyze(&read_path(path, limit)?))
}

pub fn query_path(path: &Path, limit: usize, query: &Query) -> Result<QueryPage, String> {
    query.validate()?;
    project(&read_path(path, limit)?, query)
}

fn read_path(path: &Path, limit: usize) -> Result<SimpleperfStream, String> {
    if limit == 0 || limit >= isize::MAX as usize {
        return Err("input limit must be positive and below isize::MAX".into());
    }
    let file = File::open(path).map_err(|e| format!("open simpleperf input: {e}"))?;
    let metadata = file
        .metadata()
        .map_err(|e| format!("inspect simpleperf input: {e}"))?;
    if !metadata.is_file() {
        return Err("input must be a regular file".into());
    }
    if metadata.len() > limit as u64 {
        return Err(format!(
            "input is {} bytes; limit is {limit} bytes",
            metadata.len()
        ));
    }
    // Cap read-ahead as well as logical consumption, including an EOF probe.
    let mut reader = BufReader::new(file.take(limit as u64 + 1));
    parse_reader_with_limit(&mut reader, limit).map_err(|e| e.to_string())
}

/// One-shot CLI response: encode completely before writing any success bytes.
pub fn write_query_page(writer: &mut impl Write, page: QueryPage) -> Result<(), String> {
    let mut rows = Vec::with_capacity(page.rows.len());
    for row in page.rows {
        rows.push(Value::map([
            ("id", row.id.into()),
            ("parentId", row.parent_id.into()),
            ("depth", wire::number(row.depth as u64)?),
            ("name", row.name.into()),
            ("resource", row.resource.into()),
            ("inclusiveWeight", wire::number(row.inclusive_weight)?),
            ("selfWeight", wire::number(row.self_weight)?),
            ("sampleCount", wire::number(row.sample_count)?),
            ("selfSampleCount", wire::number(row.self_sample_count)?),
        ]));
    }
    wire::write_frame(
        writer,
        &Value::map([
            ("version", wire::VERSION.into()),
            ("status", "ok".into()),
            (
                "payload",
                Value::map([
                    ("total", wire::number(page.total as u64)?),
                    ("offset", wire::number(page.offset as u64)?),
                    ("rows", Value::List(rows)),
                    ("weightType", page.weight_type.as_str().into()),
                    ("direction", page.direction.as_str().into()),
                ]),
            ),
        ]),
    )
}

fn error(id: i64, message: String) -> Value {
    Value::map([
        ("version", wire::VERSION.into()),
        ("id", id.into()),
        ("status", "error".into()),
        ("error", message.into()),
    ])
}

fn summary_value(result: &AnalysisResult) -> Result<Value, String> {
    let s = &result.summary;
    Ok(Value::map([
        ("formatVersion", (s.format_version as u32).into()),
        ("recordCount", wire::number(s.record_count as u64)?),
        ("sampleCount", wire::number(s.sample_count)?),
        ("lostSampleCount", wire::number(s.lost_sample_count)?),
        ("lostEventCount", wire::number(s.lost_event_count)?),
        ("totalEventCount", wire::number(s.total_event_count)?),
        ("fileCount", wire::number(s.file_count as u64)?),
        ("threadCount", wire::number(s.thread_count as u64)?),
    ]))
}

fn payload(result: AnalysisResult) -> Result<Value, String> {
    let summary = summary_value(&result)?;
    // Flat parent indexes avoid imposing the wire's nesting budget on call depth.
    let mut rows = Vec::new();
    let mut pending: Vec<_> = result
        .call_tree
        .into_iter()
        .rev()
        .map(|n| (n, -1i64))
        .collect();
    while let Some((node, parent)) = pending.pop() {
        let index = rows.len() as i64;
        rows.push(Value::map([
            ("parentIndex", parent.into()),
            ("name", node.name.into()),
            ("resource", node.resource.into()),
            ("fileId", node.file_id.into()),
            ("symbolId", (node.symbol_id as i64).into()),
            ("virtualAddress", node.virtual_address.to_string().into()),
            ("inclusiveWeight", wire::number(node.inclusive_weight)?),
            ("selfWeight", wire::number(node.self_weight)?),
            ("sampleCount", wire::number(node.sample_count)?),
            ("selfSampleCount", wire::number(node.self_sample_count)?),
        ]));
        pending.extend(node.children.into_iter().rev().map(|n| (n, index)));
    }
    Ok(Value::map([
        ("summary", summary),
        ("callTree", Value::List(rows)),
    ]))
}

fn dispatch(request: &Value, limit: usize) -> Result<(Value, bool), String> {
    if request.get("version")?.as_int()? != wire::VERSION {
        return Err("unsupported protocol version; expected 1".into());
    }
    request.get("id")?.as_int()?;
    let args = request.get("args")?.as_map()?;
    match request.get("op")?.as_str()? {
        "ping" => Ok((
            Value::map([
                ("engine", "aps-simpleperf-engine".into()),
                ("experimental", true.into()),
            ]),
            false,
        )),
        "close" => Ok((Value::Null, true)),
        "analyze" => {
            let path = args.get("path").ok_or("missing argument path")?.as_str()?;
            Ok((payload(analyze_path(Path::new(path), limit)?)?, false))
        }
        _ => Err("unsupported operation".into()),
    }
}

pub fn serve_binary(reader: &mut impl Read, writer: &mut impl Write) -> Result<(), String> {
    let limit = input_limit_from_env()?;
    while let Some(bytes) = wire::read_frame(reader)? {
        let request = wire::decode(&bytes);
        let id = request
            .as_ref()
            .ok()
            .and_then(|r| r.get("id").ok())
            .and_then(|v| v.as_int().ok())
            .unwrap_or(0);
        let outcome = request.and_then(|r| dispatch(&r, limit));
        let (response, close) = match outcome {
            Ok((payload, close)) => (
                Value::map([
                    ("version", wire::VERSION.into()),
                    ("id", id.into()),
                    ("status", "ok".into()),
                    ("payload", payload),
                ]),
                close,
            ),
            Err(message) => (error(id, message), false),
        };
        let encoded = wire::encode(&response).or_else(|e| wire::encode(&error(id, e)))?;
        wire::write_encoded(writer, &encoded)?;
        if close {
            break;
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    #[test]
    fn requests_are_owned_correlated_and_recover_after_errors() {
        let mut input = Vec::new();
        for (version, id, op) in [(2, -1, "ping"), (1, 7, "ping"), (1, 8, "close")] {
            wire::write_frame(
                &mut input,
                &Value::map([
                    ("version", Value::Int(version)),
                    ("id", Value::Int(id)),
                    ("op", op.into()),
                    ("args", Value::map([])),
                ]),
            )
            .unwrap();
        }
        let mut output = Vec::new();
        serve_binary(&mut Cursor::new(input), &mut output).unwrap();
        let mut output = Cursor::new(output);
        for (id, status) in [(-1, "error"), (7, "ok"), (8, "ok")] {
            let response = wire::decode(&wire::read_frame(&mut output).unwrap().unwrap()).unwrap();
            assert_eq!(response.get("id").unwrap().as_int().unwrap(), id);
            assert_eq!(response.get("status").unwrap().as_str().unwrap(), status);
        }
        assert!(wire::read_frame(&mut output).unwrap().is_none());
    }

    #[test]
    fn clean_eof_is_not_a_truncated_frame() {
        serve_binary(&mut Cursor::new([]), &mut Vec::new()).unwrap();
        assert!(serve_binary(&mut Cursor::new([0u8]), &mut Vec::new()).is_err());
    }
}

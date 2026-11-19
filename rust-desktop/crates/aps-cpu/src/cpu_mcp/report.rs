//! An inline report artifact. The MCP process never chooses a destination,
//! writes a file, or grants access to the original profile's bytes.
use super::{query_from_args, query_schema, BoundProfile, MAX_PAGE_LIMIT};
use aps_contracts::SourceDigest;
use serde_json::{json, Value};
use std::io::{self, Write};

const MAX_ARTIFACT_BYTES: usize = 128 * 1024;
const REPORT_SCHEMA: &str = "aps-cpu-mcp-report-v1";
type Error = (i32, String);

pub(super) fn schema() -> Value {
    let mut schema = query_schema(MAX_PAGE_LIMIT);
    let fields = schema["properties"].as_object_mut().expect("query schema");
    fields.remove("offset");
    fields.insert(
        "format".into(),
        json!({"type":"string","enum":["json","csv"],"default":"json"}),
    );
    schema
}

pub(super) fn call(current: &mut BoundProfile, args: &Value) -> Result<Value, Error> {
    let mut fields = args
        .as_object()
        .ok_or_else(|| (-32602, "report arguments must be an object".into()))?
        .clone();
    if fields.keys().any(|k| {
        !matches!(
            k.as_str(),
            "format" | "event" | "startNs" | "endNs" | "weightType" | "direction" | "limit"
        )
    }) {
        return Err((-32602, "unsupported report argument".into()));
    }
    let csv = match fields.remove("format") {
        None => false,
        Some(value) => match value.as_str() {
            Some("json") => false,
            Some("csv") => true,
            _ => return Err((-32602, "report format must be json or csv".into())),
        },
    };

    // Both sections must use the same limit (the call tree normally defaults to 200).
    fields.entry("limit").or_insert(json!(50));
    let args = Value::Object(fields);
    let query = query_from_args(&args, MAX_PAGE_LIMIT).map_err(|e| (-32602, e))?;
    if query
        .event_types
        .iter()
        .any(|e| !current.session.events().contains(e))
    {
        return Err((-32602, "event is absent from the bound CPU profile".into()));
    }
    let mut top = current.top_functions(&args).map_err(|e| (-32001, e))?;
    let mut tree = current.call_tree(&args).map_err(|e| (-32001, e))?;
    let digest = current.digest.to_hex();
    let effective_query = top["query"].take();
    let coverage = json!({"topFunctions":coverage(&top), "callTree":coverage(&tree)});
    let summary = current.session.summary();
    let mut doc = json!({
        "schemaVersion":REPORT_SCHEMA,
        "sourceDigest":digest,
        // The counters in this section describe the entire source, not the filtered rows.
        "profileSummary":{
            "inputFormat":current.input_format,
            "sampleCount":summary.sample_count,"threadCount":summary.thread_count,
            "firstTimestampNs":summary.first_timestamp_nanos,"lastTimestampNs":summary.last_timestamp_nanos,
            "reportedSampleCount":summary.reported_sample_count,"lostSampleCount":summary.lost_sample_count,
            "lostRate":(summary.reported_sample_count > 0).then(|| summary.lost_rate())
        },
        "query":effective_query, "coverage":coverage,
        "resourcePathsRedacted":true,
        "methodDurationAvailable":false
    });
    let mut top_rows = top["rows"].take();
    let mut tree_rows = tree["rows"].take();
    for rows in [&mut top_rows, &mut tree_rows] {
        for row in rows.as_array_mut().expect("bounded query rows") {
            if row["name"]
                .as_str()
                .is_some_and(|name| name.contains(['/', '\\']))
            {
                row["name"] = json!("<path-like symbol>");
            }
        }
    }
    let mut output = ArtifactBuffer::default();
    if csv {
        csv_report(&mut output, &doc, &top_rows, &tree_rows).map_err(|_| budget_error())?;
    } else {
        doc["topFunctions"] = top_rows;
        doc["callTree"] = tree_rows;
        serde_json::to_writer(&mut output, &doc).map_err(|_| budget_error())?;
    }
    let bytes = output.0;
    let identity = SourceDigest::hash_reader(bytes.as_slice(), MAX_ARTIFACT_BYTES as u64)
        .map_err(|e| (-32001, e))?;
    Ok(json!({
        "format":if csv {"csv"} else {"json"},
        "mimeType":if csv {"text/csv"} else {"application/json"},
        "encoding":"utf-8","byteLength":bytes.len(),"artifactSha256":identity.to_hex(),
        "query":effective_query,"coverage":coverage,
        "content":String::from_utf8(bytes).expect("UTF-8 JSON or CSV")
    }))
}

fn coverage(section: &Value) -> Value {
    let total = section["total"].as_u64().expect("query total");
    let returned = section["rows"].as_array().expect("query rows").len() as u64;
    json!({"total":total,"returned":returned,"omitted":total-returned})
}

fn budget_error() -> Error {
    (
        -32002,
        "report exceeds the 128 KiB artifact budget; reduce limit or narrow the query".into(),
    )
}

#[derive(Default)]
struct ArtifactBuffer(Vec<u8>);

impl Write for ArtifactBuffer {
    fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
        if bytes.len() > MAX_ARTIFACT_BYTES - self.0.len() {
            return Err(io::Error::other("report byte budget exceeded"));
        }
        self.0.extend_from_slice(bytes);
        Ok(bytes.len())
    }
    fn flush(&mut self) -> io::Result<()> {
        Ok(())
    }
}

// RFC-style quoted cells, CRLF record boundaries. Formula-looking strings
// get a leading apostrophe in the CSV rendition only; JSON preserves labels.
fn csv_cell(output: &mut ArtifactBuffer, text: &str) -> io::Result<()> {
    output.write_all(b"\"")?;
    let trimmed =
        text.trim_start_matches(|c: char| c.is_whitespace() || c.is_control() || c == '\u{feff}');
    if text.starts_with(['\r', '\n', '\t'])
        || trimmed.starts_with(['=', '+', '-', '@', '＝', '＋', '－', '＠'])
    {
        output.write_all(b"'")?;
    }
    for (i, part) in text.split('"').enumerate() {
        if i != 0 {
            output.write_all(b"\"\"")?;
        }
        output.write_all(part.as_bytes())?;
    }
    output.write_all(b"\"")
}

const CSV_FIELDS: [&str; 10] = [
    "id",
    "parentId",
    "depth",
    "name",
    "resource",
    "inclusiveWeight",
    "selfWeight",
    "sampleCount",
    "selfSampleCount",
    "threadCount",
];

fn csv_report(
    output: &mut ArtifactBuffer,
    meta: &Value,
    top: &Value,
    tree: &Value,
) -> io::Result<()> {
    output.write_all(b"section,metadataJson")?;
    for key in CSV_FIELDS {
        write!(output, ",{key}")?;
    }
    output.write_all(b"\r\nmetadata,")?;
    csv_cell(output, &serde_json::to_string(meta)?)?;
    for _ in CSV_FIELDS {
        output.write_all(b",")?;
    }
    output.write_all(b"\r\n")?;
    for (section, rows) in [("topFunctions", top), ("callTree", tree)] {
        for row in rows.as_array().expect("query rows") {
            write!(output, "{section},")?;
            for key in CSV_FIELDS {
                output.write_all(b",")?;
                match &row[key] {
                    Value::String(text) => csv_cell(output, text)?,
                    Value::Number(n) => write!(output, "{n}")?,
                    Value::Null => {}
                    _ => unreachable!("unexpected CPU row scalar"),
                }
            }
            output.write_all(b"\r\n")?;
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn spreadsheet_formulas_cannot_become_active_cells() {
        for text in [
            "=x",
            "+x",
            "-x",
            "@x",
            " \t=x",
            "\tuser",
            "\ruser",
            "\nuser",
            "\u{feff}@x",
            "＝x",
        ] {
            let mut b = ArtifactBuffer::default();
            csv_cell(&mut b, text).unwrap();
            assert!(b.0.starts_with(b"\"'"));
        }
        let mut b = ArtifactBuffer::default();
        csv_cell(&mut b, "雪, \"hot\"\nnext").unwrap();
        assert_eq!(String::from_utf8(b.0).unwrap(), "\"雪, \"\"hot\"\"\nnext\"");
    }

    #[test]
    fn oversized_artifact_cannot_be_partially_published() {
        let mut b = ArtifactBuffer::default();
        b.write_all(&vec![b'x'; MAX_ARTIFACT_BYTES]).unwrap();
        assert!(b.write_all(b"!").is_err());
        assert_eq!(b.0.len(), MAX_ARTIFACT_BYTES);
    }
}

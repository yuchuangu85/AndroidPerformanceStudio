//! Explicit current-page export. This is not an all-pages or raw-artifact export.
use super::PageData;
use aps_json::{Limits, Map, Value};
use aps_ui_foundation::{Destination, Language};
use std::io::Write;
use std::path::Path;

pub(super) const MAX_EXPORT_BYTES: usize = 4 * 1024 * 1024;
#[derive(Clone, Copy)]
pub(super) enum Format {
    Csv,
    Json,
}
impl Format {
    pub(super) fn extension(self) -> &'static str {
        match self {
            Self::Csv => "csv",
            Self::Json => "json",
        }
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
pub(super) fn encode_page(page: &PageData, format: Format) -> Result<Vec<u8>, String> {
    match format {
        Format::Json => aps_json::to_vec(
            &object([
                ("format", "aps-current-page-v1".into()),
                ("summary", page.summary.clone().into()),
                ("offset", page.offset.into()),
                ("hasMore", page.has_more.into()),
                ("columns", page.columns.clone().into()),
                ("rows", page.rows.clone().into()),
                ("warnings", page.warnings.clone().into()),
            ]),
            Limits {
                max_bytes: MAX_EXPORT_BYTES as u64,
                max_nodes: 100_000,
                max_string_bytes: MAX_EXPORT_BYTES,
                ..Limits::default()
            },
        )
        .map_err(|_| "Current page exceeds JSON export bounds".into()),
        Format::Csv => {
            let mut output = String::new();
            for row in std::iter::once(&page.columns).chain(page.rows.iter()) {
                for (index, value) in row.iter().enumerate() {
                    if index != 0 {
                        push(&mut output, ",")?;
                    }
                    csv_field(&mut output, value)?;
                }
                push(&mut output, "\r\n")?;
            }
            Ok(output.into_bytes())
        }
    }
}
fn push(output: &mut String, value: &str) -> Result<(), String> {
    if value.len() > MAX_EXPORT_BYTES.saturating_sub(output.len()) {
        return Err("Current page exceeds CSV export byte budget".into());
    }
    output.push_str(value);
    Ok(())
}
fn csv_field(output: &mut String, value: &str) -> Result<(), String> {
    // Quoting alone does not prevent spreadsheet formula execution. JSON retains
    // exact cells; CSV explicitly prefixes formula-like text with an apostrophe.
    let formula = value.starts_with(['=', '+', '-', '@', '\t', '\r'])
        && !value.parse::<f64>().is_ok_and(f64::is_finite);
    push(output, "\"")?;
    if formula {
        push(output, "'")?;
    }
    let mut from = 0;
    for (index, ch) in value.char_indices() {
        if ch == '"' {
            push(output, &value[from..index])?;
            push(output, "\"\"")?;
            from = index + 1;
        }
    }
    push(output, &value[from..])?;
    push(output, "\"")
}
/// Never overwrites the source, existing exports or symlink destinations. The
/// native picker chooses a location, but filesystem existence wins at commit.
pub(super) fn write_new_file(path: &Path, bytes: &[u8]) -> Result<(), String> {
    if !path.is_absolute() || bytes.len() > MAX_EXPORT_BYTES {
        return Err("Export requires an absolute destination within its byte budget".into());
    }
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    let mut file = options.open(path).map_err(
        |_| "Export destination already exists or cannot be created; select a new filename",
    )?;
    if file.write_all(bytes).and_then(|_| file.sync_all()).is_err() {
        drop(file);
        let _ = std::fs::remove_file(path);
        return Err("Cannot finish selected export; partial output cleanup was attempted".into());
    }
    Ok(())
}
fn clipped(text: &str, max_bytes: usize) -> String {
    if text.len() <= max_bytes {
        return text.into();
    }
    let mut end = max_bytes.saturating_sub(3).min(text.len());
    while !text.is_char_boundary(end) {
        end -= 1;
    }
    format!("{}…", &text[..end])
}
pub(super) fn analysis_request(
    kind: Destination,
    session: &str,
    generation: u64,
    page: &PageData,
) -> Result<aps_ai::AnalysisRequest, String> {
    let profiler = match kind {
        Destination::Memory => "memory",
        Destination::Network => "network",
        Destination::Trace => "perfetto",
        Destination::Frame => "frame",
        Destination::Startup => "startup",
        Destination::Battery => "battery",
        Destination::Benchmark => "benchmark",
        Destination::Gpu => "gpu",
        _ => return Err("Workspace has no imported profiler evidence".into()),
    };
    // Only the existing minimized/summary projection is eligible. No source path,
    // heap field/value text or network private detail enters this request.
    let rows: Vec<Value> = page
        .rows
        .iter()
        .take(16)
        .map(|row| {
            Value::Array(
                row.iter()
                    .take(8)
                    .map(|cell| clipped(cell, 128).into())
                    .collect(),
            )
        })
        .collect();
    let payload = object([
        ("projectionPolicy", "aps-current-page-minimized-v1".into()),
        ("offset", page.offset.into()), ("publishedRows", page.rows.len().into()),
        ("hasMore", page.has_more.into()), ("evidenceRowLimit", 16.into()),
        ("columns", page.columns.iter().take(8).map(|v| clipped(v, 128)).collect::<Vec<_>>().into()),
        ("rows", Value::Array(rows)),
        ("omissions", "At most 16 rows, 8 columns and 128 UTF-8 bytes per cell; heap fields and private traffic excluded".into()),
    ]);
    aps_json::to_vec(
        &payload,
        Limits {
            max_bytes: 32 * 1024,
            max_string_bytes: 2048,
            ..Limits::default()
        },
    )
    .map_err(|_| "Evidence page exceeds AI context budget")?;
    Ok(aps_ai::AnalysisRequest {
        session_id: session.into(),
        run_id: format!("{session}-g{generation}"),
        generation: generation.max(1),
        profiler: profiler.into(),
        scope: format!(
            "Published {} page at offset {}; bounded minimized projection only",
            kind.label(Language::English),
            page.offset
        ),
        evidence: vec![aps_ai::Evidence {
            id: format!("page-{generation}"),
            kind: "profiler-page".into(),
            summary: clipped(&page.summary, 2048),
            payload,
        }],
        candidates: Vec::new(),
        user_question: None,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    fn page() -> PageData {
        PageData {
            ui: super::super::PageUi::default(),
            summary: "measured summary".into(),
            columns: vec!["name".into(), "weight".into()],
            rows: vec![
                vec!["a,\"b\n中".into(), "-5".into()],
                vec!["=HYPERLINK(\"secret\")".into(), "3".into()],
            ],
            offset: 100,
            has_more: true,
            warnings: vec!["Partial page".into()],
            row_actions: Vec::new(),
        }
    }
    #[test]
    fn csv_quotes_delimiters_preserves_numeric_values_and_neutralizes_formulas() {
        let csv = String::from_utf8(encode_page(&page(), Format::Csv).unwrap()).unwrap();
        assert!(csv.contains("\"a,\"\"b\n中\",\"-5\""));
        assert!(csv.contains("\"'=HYPERLINK(\"\"secret\"\")\""));
    }
    #[test]
    fn json_retains_exact_cells_and_explicit_page_contract() {
        let encoded = encode_page(&page(), Format::Json).unwrap();
        let json = aps_json::parse_slice(&encoded, Limits::default()).unwrap();
        assert_eq!(json.get("offset").and_then(Value::as_u64), Some(100));
        assert_eq!(json.get("hasMore").and_then(Value::as_bool), Some(true));
        assert_eq!(json["rows"][1][0].as_str(), Some("=HYPERLINK(\"secret\")"));
    }
    #[test]
    fn locale_display_metadata_does_not_change_exports_or_ai_evidence() {
        use super::super::{PageUi, UiText};
        use aps_ui_foundation::TextKey;

        let mut page = page();
        let json_before = encode_page(&page, Format::Json).unwrap();
        let csv_before = encode_page(&page, Format::Csv).unwrap();
        let evidence_before = analysis_request(Destination::Network, "locale-test", 1, &page)
            .unwrap()
            .evidence;
        page.ui = PageUi::new(
            TextKey::ImportUiMemoryHistogram.into(),
            &[TextKey::ImportUiName, TextKey::ImportUiMetric],
        )
        .warning(0, TextKey::ImportUiMemoryWorkerWarning.into());
        page.ui
            .cells
            .insert((0, 1), UiText::from(TextKey::ImportUiUnavailable));
        assert_ne!(
            page.display_summary(Language::SimplifiedChinese),
            page.summary
        );
        assert_ne!(
            page.display_columns(Language::SimplifiedChinese),
            page.columns.join("  |  ")
        );
        assert!(
            page.display_row(0, Language::SimplifiedChinese)
                .starts_with(&page.rows[0][0])
        );
        assert_eq!(encode_page(&page, Format::Json).unwrap(), json_before);
        assert_eq!(encode_page(&page, Format::Csv).unwrap(), csv_before);
        let evidence_after = analysis_request(Destination::Network, "locale-test", 1, &page)
            .unwrap()
            .evidence;
        assert_eq!(evidence_before[0].payload, evidence_after[0].payload);
        assert_eq!(evidence_before[0].summary, evidence_after[0].summary);
    }
    #[test]
    fn evidence_is_bounded_and_has_no_source_locator() {
        let mut page = page();
        page.rows = vec![vec!["中".repeat(1000); 30]; 200];
        let request = analysis_request(Destination::Network, "import-test", 4, &page).unwrap();
        assert_eq!(request.generation, 4);
        assert!(request.candidates.is_empty());
        let rows = request.evidence[0].payload["rows"].as_array().unwrap();
        assert_eq!(rows.len(), 16);
        assert_eq!(rows[0].as_array().unwrap().len(), 8);
        assert!(rows[0][0].as_str().unwrap().len() <= 128);
    }
    #[test]
    fn csv_budget_fails_without_partial_export_result() {
        let mut page = page();
        page.rows = vec![vec!["x".repeat(MAX_EXPORT_BYTES + 1)]];
        assert!(encode_page(&page, Format::Csv).is_err());
    }
    #[test]
    fn existing_export_destination_is_never_overwritten() {
        let path = std::env::temp_dir().join(format!(
            "aps-export-{}-{}",
            std::process::id(),
            super::super::NEXT_SESSION.fetch_add(1, std::sync::atomic::Ordering::Relaxed)
        ));
        std::fs::write(&path, b"original").unwrap();
        assert!(write_new_file(&path, b"changed").is_err());
        assert_eq!(std::fs::read(&path).unwrap(), b"original");
        std::fs::remove_file(path).unwrap();
    }
}

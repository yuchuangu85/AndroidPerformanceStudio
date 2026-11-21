//! Dependency-free PDF 1.4 writer for the release report (port of `BenchmarkPdfExporter.kt`).
//!
//! The workspace must hand a release engineer a portable artifact without adding
//! a PDF toolchain, so this writes a minimal PDF 1.4 document (Helvetica, A4)
//! directly. Base-14 fonts only carry WinAnsi, so characters outside printable
//! ASCII are replaced with '?'; the JSON/CSV exports keep the exact text.
use std::path::Path;

use crate::compare::{Classification, RegressionReport, ReleaseGateResult};
use crate::util::{format_general, format_percent};

const PAGE_WIDTH: usize = 595;
const PAGE_HEIGHT: usize = 842;
const MARGIN: usize = 40;
const FONT_SIZE: usize = 9;
const LINE_HEIGHT: usize = 12;
const LINES_PER_PAGE: usize = 60;
const PAGE_FIRST_OBJECT_ID: usize = 3;
const CASE_WIDTH: usize = 34;
const METRIC_WIDTH: usize = 20;
const VALUE_WIDTH: usize = 11;
const DELTA_WIDTH: usize = 9;
const CLASSIFICATION_WIDTH: usize = 14;
const CONFIDENCE_WIDTH: usize = 10;

/// Report body as plain lines, exposed so tests assert the content without parsing the PDF.
pub fn render_pdf_lines(report: &RegressionReport, gate: &ReleaseGateResult) -> Vec<String> {
    let mut lines = Vec::new();
    lines.push("Android Performance Studio - Benchmark Regression Report".to_string());
    lines.push(String::new());
    lines.push(format!("Baseline run:   {}", report.baseline_run_id));
    lines.push(format!("Current run:    {}", report.current_run_id));
    lines.push(format!(
        "Created at:     {}",
        created_at_display(&report.created_at)
    ));
    lines.push(format!(
        "Gate decision:  {}   (policy v{})",
        gate.decision.label(),
        gate.policy_version
    ));
    for reason in &gate.reasons {
        lines.push(format!("Reason:         {}", reason.label()));
    }
    lines.push(format!(
        "Comparisons:    {}   Regressions: {}   Blocking: {}",
        report.comparisons.len(),
        regression_count(report),
        gate.blocking_comparisons.len()
    ));
    lines.push(String::new());
    lines.push("Compatibility issues".to_string());
    if report.compatibility_issues.is_empty() {
        lines.push("  none".to_string());
    } else {
        for issue in &report.compatibility_issues {
            lines.push(format!(
                "  {}: {} -> {}{}",
                issue.field,
                issue.baseline.as_deref().unwrap_or("-"),
                issue.current.as_deref().unwrap_or("-"),
                if issue.hard { "  [hard]" } else { "" }
            ));
        }
    }
    lines.push(String::new());
    lines.push(format!(
        "{}{}{}{}{}{}confidence",
        pad("case", CASE_WIDTH),
        pad("metric", METRIC_WIDTH),
        pad("baseline", VALUE_WIDTH),
        pad("current", VALUE_WIDTH),
        pad("delta %", DELTA_WIDTH),
        pad("classification", CLASSIFICATION_WIDTH),
    ));
    lines.push("-".repeat(
        CASE_WIDTH
            + METRIC_WIDTH
            + VALUE_WIDTH * 2
            + DELTA_WIDTH
            + CLASSIFICATION_WIDTH
            + CONFIDENCE_WIDTH,
    ));
    for comparison in &report.comparisons {
        lines.push(format!(
            "{}{}{}{}{}{}{}",
            pad(&comparison.case_identity, CASE_WIDTH),
            pad(&comparison.metric_name, METRIC_WIDTH),
            pad(&format_value(comparison.baseline_value), VALUE_WIDTH),
            pad(&format_value(comparison.current_value), VALUE_WIDTH),
            pad(
                &format_delta(comparison.relative_delta_percent),
                DELTA_WIDTH
            ),
            pad(comparison.classification.label(), CLASSIFICATION_WIDTH),
            comparison.confidence.label(),
        ));
    }
    lines
}

pub fn write_pdf(
    report: &RegressionReport,
    gate: &ReleaseGateResult,
    output: &Path,
) -> Result<(), String> {
    write_lines(&render_pdf_lines(report, gate), output)
}

fn regression_count(report: &RegressionReport) -> usize {
    report
        .comparisons
        .iter()
        .filter(|comparison| comparison.classification == Classification::Regressed)
        .count()
}

fn write_lines(lines: &[String], output: &Path) -> Result<(), String> {
    let empty_page: Vec<String> = Vec::new();
    let pages: Vec<&[String]> = if lines.is_empty() {
        vec![empty_page.as_slice()]
    } else {
        lines.chunks(LINES_PER_PAGE).collect()
    };
    let page_object_ids: Vec<usize> = (0..pages.len())
        .map(|index| PAGE_FIRST_OBJECT_ID + index)
        .collect();
    let font_object_id = PAGE_FIRST_OBJECT_ID + pages.len();
    let content_object_ids: Vec<usize> = (0..pages.len())
        .map(|index| font_object_id + 1 + index)
        .collect();

    let mut objects: Vec<String> = Vec::new();
    objects.push("<< /Type /Catalog /Pages 2 0 R >>".to_string());
    objects.push(format!(
        "<< /Type /Pages /Kids [{}] /Count {} >>",
        page_object_ids
            .iter()
            .map(|id| format!("{id} 0 R"))
            .collect::<Vec<_>>()
            .join(" "),
        pages.len()
    ));
    for (index, _) in pages.iter().enumerate() {
        objects.push(format!(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 {} {}] /Resources << /Font << /F1 {} 0 R >> >> /Contents {} 0 R >>",
            PAGE_WIDTH, PAGE_HEIGHT, font_object_id, content_object_ids[index]
        ));
    }
    objects.push(
        "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>"
            .to_string(),
    );
    for page_lines in &pages {
        let stream = content_stream(page_lines);
        objects.push(format!(
            "<< /Length {} >>\nstream\n{}\nendstream",
            stream.len(),
            stream
        ));
    }

    let mut builder = String::from("%PDF-1.4\n");
    let mut offsets: Vec<usize> = Vec::new();
    for (index, body) in objects.iter().enumerate() {
        offsets.push(builder.len());
        builder.push_str(&format!("{} 0 obj\n{}\nendobj\n", index + 1, body));
    }
    let xref_offset = builder.len();
    builder.push_str(&format!("xref\n0 {}\n", objects.len() + 1));
    builder.push_str("0000000000 65535 f \n");
    for offset in &offsets {
        builder.push_str(&format!("{offset:010} 00000 n \n"));
    }
    builder.push_str(&format!(
        "trailer\n<< /Size {} /Root 1 0 R >>\n",
        objects.len() + 1
    ));
    builder.push_str(&format!("startxref\n{xref_offset}\n%%EOF\n"));

    if let Some(parent) = output.parent()
        && !parent.as_os_str().is_empty()
    {
        std::fs::create_dir_all(parent)
            .map_err(|_| format!("Cannot create PDF directory: {}", parent.display()))?;
    }
    std::fs::write(output, builder.as_bytes()).map_err(|error| format!("Cannot write PDF: {error}"))
}

fn content_stream(lines: &[String]) -> String {
    let mut stream = String::new();
    stream.push_str(&format!(
        "BT\n/F1 {FONT_SIZE} Tf\n{LINE_HEIGHT} TL\n1 0 0 1 {MARGIN} {} Tm\n",
        PAGE_HEIGHT - MARGIN
    ));
    for line in lines {
        stream.push('(');
        stream.push_str(&escape(line));
        stream.push_str(") Tj T*\n");
    }
    stream.push_str("ET");
    stream
}

fn escape(value: &str) -> String {
    let mut escaped = String::new();
    for character in value.chars() {
        match character {
            '\\' => escaped.push_str("\\\\"),
            '(' => escaped.push_str("\\("),
            ')' => escaped.push_str("\\)"),
            character if (32..=126).contains(&(character as u32)) => escaped.push(character),
            _ => escaped.push('?'),
        }
    }
    escaped
}

fn format_value(value: Option<f64>) -> String {
    match value {
        None => "-".to_string(),
        Some(value) => format_general(value, 4),
    }
}

fn format_delta(value: Option<f64>) -> String {
    match value {
        None => "-".to_string(),
        Some(value) => format_percent(value),
    }
}

fn pad(value: &str, width: usize) -> String {
    let length = value.chars().count();
    if length >= width {
        let mut truncated: String = value.chars().take(width - 1).collect();
        truncated.push(' ');
        truncated
    } else {
        let mut padded = value.to_string();
        padded.extend(std::iter::repeat_n(' ', width - length));
        padded
    }
}

fn created_at_display(created_at: &str) -> String {
    // RFC3339 "2026-11-08T07:38:49Z" -> "2026-11-08 07:38:49" (Compose formats the instant locally).
    if created_at.len() >= 19 {
        let mut text = created_at[..19].to_string();
        if let Some(index) = text.find('T') {
            text.replace_range(index..=index, " ");
        }
        text
    } else {
        created_at.to_string()
    }
}

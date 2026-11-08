//! Verified HTML/Markdown report artifacts for validated AI results.
//!
//! Only the schema-validated AnalysisResult is written; raw provider output is
//! never persisted or replayed. Files are SHA-256 addressed under the Rust-owned
//! artifact directory, written atomically and only reopened by catalogued hash.

use crate::AnalysisResult;
use crate::platform::ReportFormat;
use aps_contracts::SourceDigest;
use std::fmt;
use std::path::{Path, PathBuf};

const MAX_REPORT_BYTES: u64 = 2 * 1024 * 1024;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ReportError {
    Render,
    Write,
    Limit,
}

impl fmt::Display for ReportError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::Render => "E_AI_REPORT_RENDER",
            Self::Write => "E_AI_REPORT_WRITE",
            Self::Limit => "E_AI_REPORT_LIMIT",
        })
    }
}
impl std::error::Error for ReportError {}

/// Render and catalog one report. Returns the lowercase SHA-256 hex digest.
pub fn write_artifact(
    directory: &Path,
    result: &AnalysisResult,
    format: ReportFormat,
) -> Result<String, ReportError> {
    let body = match format {
        ReportFormat::Html => render_html(result),
        ReportFormat::Markdown => render_markdown(result),
    };
    if body.is_empty() || body.len() as u64 > MAX_REPORT_BYTES {
        return Err(if body.is_empty() {
            ReportError::Render
        } else {
            ReportError::Limit
        });
    }
    let digest = SourceDigest::hash_reader(body.as_bytes(), MAX_REPORT_BYTES)
        .map_err(|_| ReportError::Limit)?;
    let hash = digest.to_hex();
    aps_platform::create_private_directory(directory).map_err(|_| ReportError::Write)?;
    let path = directory.join(format!("{hash}.{}", format.extension()));
    aps_platform::atomic_write(&path, body.as_bytes()).map_err(|_| ReportError::Write)?;
    Ok(hash)
}

pub fn artifact_path(directory: &Path, hash: &str) -> Option<PathBuf> {
    if hash.len() != 64 || !hash.bytes().all(|byte| byte.is_ascii_hexdigit()) {
        return None;
    }
    for extension in ["html", "md"] {
        let path = directory.join(format!("{hash}.{extension}"));
        if path.is_file() {
            return Some(path);
        }
    }
    None
}

pub fn render_markdown(result: &AnalysisResult) -> String {
    let mut out = String::with_capacity(result.summary.len() + 1024);
    out.push_str("# Android Performance Analysis\n\n");
    out.push_str(result.summary.trim());
    out.push('\n');
    if result.findings.is_empty() {
        out.push_str("\n_No structured findings were produced._\n");
    }
    for finding in &result.findings {
        out.push_str(&format!(
            "\n## [{}] {}\n\n{}\n",
            severity_label(finding.severity),
            finding.title.trim(),
            finding.explanation.trim()
        ));
        if !finding.recommendation.trim().is_empty() {
            out.push_str(&format!(
                "\n**Recommendation:** {}\n",
                finding.recommendation.trim()
            ));
        }
        out.push_str(&format!(
            "\n- Analysis confidence: {:.2}\n",
            finding.analysis_confidence
        ));
        if !finding.evidence_ids.is_empty() {
            out.push_str(&format!(
                "- Evidence: {}\n",
                finding.evidence_ids.join(", ")
            ));
        }
        if !finding.candidate_ids.is_empty() {
            out.push_str(&format!(
                "- Source candidates: {}\n",
                finding.candidate_ids.join(", ")
            ));
        }
    }
    out.push_str(&format!(
        "\n---\n\nModel: {}\nProvider session: {}\nRun: {}\n",
        escape_markdown(&result.model),
        escape_markdown(&result.session_id),
        escape_markdown(&result.run_id)
    ));
    out
}

pub fn render_html(result: &AnalysisResult) -> String {
    let mut out = String::with_capacity(result.summary.len() + 4096);
    out.push_str("<!doctype html>\n<html lang=\"en\"><head><meta charset=\"utf-8\">");
    out.push_str("<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; style-src 'unsafe-inline'\">");
    out.push_str("<title>Android Performance Analysis</title><style>");
    out.push_str("body{font:14px/1.6 -apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;margin:32px auto;max-width:880px;padding:0 16px;color:#111}");
    out.push_str("h1{font-size:24px}h2{font-size:18px;margin-top:28px}code{font-family:ui-monospace,monospace}");
    out.push_str(".severity{display:inline-block;border:1px solid #999;border-radius:4px;padding:0 6px;font-size:12px;margin-right:8px}");
    out.push_str(".meta{color:#666;font-size:12px;margin-top:32px;border-top:1px solid #ddd;padding-top:12px}");
    out.push_str(
        ".rec{background:#f5f5f5;border-left:3px solid #888;padding:8px 12px;margin:8px 0}",
    );
    out.push_str("</style></head><body>");
    out.push_str("<h1>Android Performance Analysis</h1>");
    out.push_str(&format!("<p>{}</p>", escape_html(result.summary.trim())));
    if result.findings.is_empty() {
        out.push_str("<p><em>No structured findings were produced.</em></p>");
    }
    for finding in &result.findings {
        out.push_str(&format!(
            "<h2><span class=\"severity\">{}</span>{}</h2>",
            severity_label(finding.severity),
            escape_html(finding.title.trim())
        ));
        out.push_str(&format!(
            "<p>{}</p>",
            escape_html(finding.explanation.trim())
        ));
        if !finding.recommendation.trim().is_empty() {
            out.push_str(&format!(
                "<div class=\"rec\"><strong>Recommendation:</strong> {}</div>",
                escape_html(finding.recommendation.trim())
            ));
        }
        out.push_str(&format!(
            "<p><code>analysis confidence {:.2}</code></p>",
            finding.analysis_confidence
        ));
        if !finding.evidence_ids.is_empty() {
            out.push_str(&format!(
                "<p>Evidence: {}</p>",
                escape_html(&finding.evidence_ids.join(", "))
            ));
        }
        if !finding.candidate_ids.is_empty() {
            out.push_str(&format!(
                "<p>Source candidates: {}</p>",
                escape_html(&finding.candidate_ids.join(", "))
            ));
        }
    }
    out.push_str(&format!(
        "<div class=\"meta\">Model: {}<br>Provider session: {}<br>Run: {}</div>",
        escape_html(&result.model),
        escape_html(&result.session_id),
        escape_html(&result.run_id)
    ));
    out.push_str("</body></html>");
    out
}

fn severity_label(severity: crate::FindingSeverity) -> &'static str {
    match severity {
        crate::FindingSeverity::Info => "INFO",
        crate::FindingSeverity::Warning => "WARNING",
        crate::FindingSeverity::Error => "ERROR",
    }
}

fn escape_html(value: &str) -> String {
    let mut out = String::with_capacity(value.len());
    for character in value.chars() {
        match character {
            '&' => out.push_str("&amp;"),
            '<' => out.push_str("&lt;"),
            '>' => out.push_str("&gt;"),
            '"' => out.push_str("&quot;"),
            '\'' => out.push_str("&#39;"),
            _ => out.push(character),
        }
    }
    out
}

fn escape_markdown(value: &str) -> String {
    value.replace('\n', " ").replace('\r', " ")
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{AnalysisFinding, FindingSeverity};

    fn result() -> AnalysisResult {
        AnalysisResult {
            session_id: "session".into(),
            run_id: "run".into(),
            generation: 1,
            model: "test-model".into(),
            summary: "Frame took <24ms> & missed.".into(),
            findings: vec![AnalysisFinding {
                id: "finding-1".into(),
                severity: FindingSeverity::Warning,
                title: "Frame deadline".into(),
                explanation: "Explained".into(),
                recommendation: "Do <less> work".into(),
                analysis_confidence: 0.8,
                evidence_ids: vec!["frame-1".into()],
                candidate_ids: Vec::new(),
            }],
        }
    }

    #[test]
    fn renders_escaped_html_and_markdown() {
        let html = render_html(&result());
        assert!(html.contains("&lt;24ms&gt;"));
        assert!(!html.contains("<24ms>"));
        let markdown = render_markdown(&result());
        assert!(markdown.contains("## [WARNING] Frame deadline"));
        assert!(markdown.contains("Analysis confidence: 0.80"));
    }

    #[test]
    fn writes_content_addressed_artifact_atomically() {
        let directory = std::env::temp_dir().join(format!("aps-ai-reports-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&directory);
        let hash = write_artifact(&directory, &result(), ReportFormat::Markdown).unwrap();
        assert_eq!(hash.len(), 64);
        let path = artifact_path(&directory, &hash).unwrap();
        assert_eq!(
            std::fs::read_to_string(&path).unwrap(),
            render_markdown(&result())
        );
        assert!(artifact_path(&directory, "../../etc/passwd").is_none());
        let _ = std::fs::remove_dir_all(&directory);
    }
}

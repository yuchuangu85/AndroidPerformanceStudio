//! Compose TraceSliceSearch port: bounded, escaped slice-name search.
//!
//! The only user input that can reach SQL is the search term. It is filtered
//! of ISO control characters, trimmed, bounded to MAX_TERM_LENGTH characters,
//! lowercased and embedded as a single-quoted SQLite literal with quotes
//! doubled. Everything else in the statement is fixed text.

use crate::diagnostics::DiagnosticCategory;

pub const MAX_TERM_LENGTH: usize = 120;
pub const RESULT_LIMIT: usize = 200;
pub const COLUMNS: &[&str] = &[
    "timestamp_ns",
    "duration_ns",
    "slice_name",
    "thread_name",
    "process_name",
];

pub const TIMESTAMP_COLUMN: usize = 0;
pub const DURATION_COLUMN: usize = 1;
pub const NAME_COLUMN: usize = 2;
pub const THREAD_COLUMN: usize = 3;
pub const PROCESS_COLUMN: usize = 4;

/// The escaped search statement and its declared schema.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SliceSearchQuery {
    pub id: &'static str,
    pub category: DiagnosticCategory,
    pub title: &'static str,
    pub description: &'static str,
    pub columns: &'static [&'static str],
    pub sql: String,
    /// Sanitized term actually searched (control-filtered, trimmed, bounded).
    pub term: String,
    /// True when the user term was cut to MAX_TERM_LENGTH characters.
    pub term_truncated: bool,
}

/// Builds the bounded read-only slice name search. Port of Compose
/// TraceSliceSearch; query(term) fixes the limit at RESULT_LIMIT.
pub struct TraceSliceSearch;

impl TraceSliceSearch {
    /// Compose TraceSliceSearch.query: returns None when the term is blank.
    pub fn query(term: &str) -> Option<SliceSearchQuery> {
        Self::query_with_limit(term, RESULT_LIMIT)
    }

    /// Same statement with an explicit bounded limit. Limits outside
    /// 1..=RESULT_LIMIT are clamped so callers cannot widen the result bound.
    pub fn query_with_limit(term: &str, limit: usize) -> Option<SliceSearchQuery> {
        let (term, term_truncated) = sanitize(term)?;
        let limit = limit.clamp(1, RESULT_LIMIT);
        // The term is embedded as a single-quoted SQLite literal; doubling
        // quotes is the complete escape.
        let literal = term.to_lowercase().replace('\'', "''");
        let sql = format!(
            "SELECT slice.ts AS timestamp_ns,\n       slice.dur AS duration_ns,\n       slice.name AS slice_name,\n       thread.name AS thread_name,\n       process.name AS process_name\nFROM slice\nLEFT JOIN thread_track ON thread_track.id = slice.track_id\nLEFT JOIN thread ON thread.utid = thread_track.utid\nLEFT JOIN process ON process.upid = thread.upid\nWHERE instr(lower(slice.name), '{literal}') > 0\nORDER BY slice.dur DESC\nLIMIT {limit}"
        );
        Some(SliceSearchQuery {
            id: "slice_search",
            category: DiagnosticCategory::Cpu,
            title: "Slice search",
            description: "Slices whose name contains the search term",
            columns: COLUMNS,
            sql,
            term,
            term_truncated,
        })
    }
}

/// One decoded search row. Empty name/thread_label mean the trace cell was
/// SQL NULL; the UI substitutes its localized placeholder.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SearchHit {
    pub timestamp_nanos: i64,
    pub duration_nanos: Option<i64>,
    pub name: String,
    pub thread_label: String,
}

/// Bounded search outcome: hits plus whether the declared result bound was
/// reached (more matching slices may exist).
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SliceSearchResult {
    pub term: String,
    pub hits: Vec<SearchHit>,
    pub truncated: bool,
}

/// Port of the Compose search-turn-into-hits projection: rows without a
/// parseable timestamp are dropped; thread and process are joined with
/// " · " when both are known.
pub fn decode_search_hits(rows: &[Vec<Option<String>>]) -> Vec<SearchHit> {
    rows.iter()
        .filter_map(|row| {
            let timestamp_nanos = parse_i64(row.get(TIMESTAMP_COLUMN))?;
            let duration_nanos = parse_i64(row.get(DURATION_COLUMN));
            let name = row
                .get(NAME_COLUMN)
                .and_then(|value| value.clone())
                .unwrap_or_default();
            let thread_label = [THREAD_COLUMN, PROCESS_COLUMN]
                .into_iter()
                .filter_map(|column| row.get(column))
                .filter_map(|value| value.as_deref())
                .filter(|value| !value.trim().is_empty())
                .collect::<Vec<_>>()
                .join(" · ");
            Some(SearchHit {
                timestamp_nanos,
                duration_nanos,
                name,
                thread_label,
            })
        })
        .collect()
}

fn parse_i64(value: Option<&Option<String>>) -> Option<i64> {
    value?
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .and_then(|value| value.parse::<i64>().ok())
}

/// Compose sanitization: drop ISO control characters, trim, then bound.
fn sanitize(term: &str) -> Option<(String, bool)> {
    let filtered: String = term
        .chars()
        .filter(|character| !character.is_control())
        .collect();
    let trimmed = filtered.trim();
    if trimmed.is_empty() {
        return None;
    }
    let mut sanitized = String::new();
    let mut truncated = false;
    for (index, character) in trimmed.chars().enumerate() {
        if index >= MAX_TERM_LENGTH {
            truncated = true;
            break;
        }
        sanitized.push(character);
    }
    Some((sanitized, truncated))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn blanks_are_rejected_and_control_characters_are_dropped() {
        assert!(TraceSliceSearch::query("   ").is_none());
        assert!(TraceSliceSearch::query("\n\t\u{0000}\u{007f}").is_none());
        let query = TraceSliceSearch::query("Choreographer' OR 1=1 --\n").expect("query");
        assert!(
            query
                .sql
                .contains("instr(lower(slice.name), 'choreographer'' or 1=1 --') > 0")
        );
        assert_eq!(query.columns, COLUMNS);
        assert_eq!(query.term, "Choreographer' OR 1=1 --");
        assert!(!query.term_truncated);
    }

    #[test]
    fn terms_are_bounded_and_marked_truncated() {
        let query = TraceSliceSearch::query(&"x".repeat(500)).expect("query");
        assert!(
            query
                .sql
                .contains(&format!("'{}'", "x".repeat(MAX_TERM_LENGTH)))
        );
        assert!(query.term_truncated);
        assert_eq!(query.term.chars().count(), MAX_TERM_LENGTH);
    }

    #[test]
    fn search_sql_is_read_only_and_bounded() {
        let query = TraceSliceSearch::query("main").expect("query");
        assert!(query.sql.starts_with("SELECT "));
        assert!(query.sql.ends_with(&format!("LIMIT {RESULT_LIMIT}")));
        assert!(!query.sql.to_lowercase().contains(" like "));
        assert!(!query.sql.contains(';'));
        assert_eq!(query.category, DiagnosticCategory::Cpu);
        assert_eq!(query.id, "slice_search");
        // An explicit limit is clamped into the declared bound.
        let narrow = TraceSliceSearch::query_with_limit("main", 1).expect("query");
        assert!(narrow.sql.ends_with("LIMIT 1"));
        let wide = TraceSliceSearch::query_with_limit("main", 10_000).expect("query");
        assert!(wide.sql.ends_with(&format!("LIMIT {RESULT_LIMIT}")));
    }

    #[test]
    fn hits_drop_missing_timestamps_and_join_thread_labels() {
        let rows = vec![
            vec![
                Some("10".to_owned()),
                Some("5".to_owned()),
                Some("draw".to_owned()),
                Some("main".to_owned()),
                Some("app".to_owned()),
            ],
            vec![
                Some("11".to_owned()),
                None,
                Some("idle".to_owned()),
                Some("RenderThread".to_owned()),
                None,
            ],
            vec![
                None,
                Some("1".to_owned()),
                Some("lost".to_owned()),
                None,
                None,
            ],
            vec![
                Some("not-a-number".to_owned()),
                None,
                Some("lost".to_owned()),
                None,
                None,
            ],
        ];
        let hits = decode_search_hits(&rows);
        assert_eq!(hits.len(), 2);
        assert_eq!(
            hits[0],
            SearchHit {
                timestamp_nanos: 10,
                duration_nanos: Some(5),
                name: "draw".to_owned(),
                thread_label: "main · app".to_owned(),
            }
        );
        assert_eq!(hits[1].timestamp_nanos, 11);
        assert_eq!(hits[1].duration_nanos, None);
        assert_eq!(hits[1].thread_label, "RenderThread");
    }
}

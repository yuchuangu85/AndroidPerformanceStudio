//! Compose perfetto-analysis parity checks that read the Kotlin oracle at test
//! time. They skip cleanly when the oracle files are not present (for example a
//! packaged source build without the desktop-viewer tree).

use aps_perfetto::{
    CORE_PRESET_ID, DIAGNOSTIC_QUERIES, DiagnosticCategory, MAX_DIAGNOSTIC_ROWS, PINNED_VERSION,
    diagnostic_preset, diagnostic_presets, diagnostic_queries, diagnostic_query,
    queries_for_category,
};
use std::fs;
use std::path::{Path, PathBuf};

fn repository_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..")
}

fn oracle_root() -> PathBuf {
    repository_root().join(
        "desktop-viewer/perfetto-viewer/perfetto-analysis/src/main/kotlin/com/androidperformancestudio/perfetto/analysis",
    )
}

fn read_oracle(name: &str) -> Option<String> {
    fs::read_to_string(oracle_root().join(name)).ok()
}

#[test]
fn catalog_invariants_match_the_compose_test_expectations() {
    let ids: Vec<&str> = diagnostic_queries().iter().map(|query| query.id).collect();
    assert_eq!(ids.len(), 35, "the Compose catalog declares 35 diagnostics");
    let mut unique = ids.clone();
    unique.sort_unstable();
    unique.dedup();
    assert_eq!(unique.len(), ids.len(), "diagnostic ids must be unique");
    assert_eq!(diagnostic_queries().len(), DIAGNOSTIC_QUERIES.len());

    for query in diagnostic_queries() {
        assert!(!query.title.is_empty(), "{}", query.id);
        assert!(!query.description.is_empty(), "{}", query.id);
        assert!(!query.columns.is_empty(), "{}", query.id);
        let mut columns = query.columns.to_vec();
        columns.sort_unstable();
        columns.dedup();
        assert_eq!(columns.len(), query.columns.len(), "{}", query.id);

        // Read-only statements only: fixed catalog SQL, no user interpolation.
        let sql = query.sql;
        assert!(
            sql.starts_with("SELECT ") || sql.starts_with("INCLUDE ") || sql.starts_with("WITH "),
            "{}",
            query.id
        );
        assert!(
            sql.to_lowercase()
                .ends_with(&format!("limit {}", trailing_limit(sql))),
            "{}",
            query.id
        );
        assert!(!sql.to_lowercase().contains(" like "), "{}", query.id);
        assert!(!sql.contains("READ_FILE"), "{}", query.id);
        assert!(!sql.contains('{') && !sql.contains('}'), "{}", query.id);
        assert!(!sql.contains("'$"), "{}", query.id);
        // Multiple statements are allowed only as leading INCLUDE PERFETTO
        // MODULE lines; the final statement must remain the fixed SELECT.
        for line in sql.lines() {
            if !line.contains(';') {
                continue;
            }
            let trimmed = line.trim();
            assert!(
                trimmed.starts_with("INCLUDE PERFETTO MODULE ")
                    && trimmed.ends_with(';')
                    && trimmed.matches(';').count() == 1,
                "{} has an unexpected statement separator: {}",
                query.id,
                trimmed
            );
        }

        // Declared timestamp/duration helpers stay inside the declared columns.
        if let Some(index) = query.timestamp_column() {
            let name = query.columns[index];
            assert!(
                name == "timestamp_ns" || name == "ts" || name.ends_with("_timestamp_ns"),
                "{}",
                query.id
            );
        }
        if let Some(index) = query.duration_column() {
            assert_eq!(query.columns[index], "duration_ns", "{}", query.id);
        }
    }

    assert!(query_presence("sched_switch"));
    assert!(query_presence("binder_ipc_long_tail"));
    assert!(query_presence("art_gc_events"));
    assert!(query_presence("thermal_throttling"));

    // Compose schema compatibility expects the standard-library diagnostics to
    // declare their own modules/tables.
    let by_id = |id: &str| diagnostic_query(id).expect("query");
    assert!(by_id("sched_switch").sql.contains("sched_slice"));
    assert!(by_id("run_queue").sql.contains("LEAD(ts) OVER"));
    assert!(
        by_id("cpu_freq_dist")
            .sql
            .contains("INCLUDE PERFETTO MODULE linux.cpu.frequency;")
    );
    assert!(by_id("binder_ipc_long_tail").sql.contains("PERCENTILE"));
    assert!(
        by_id("binder_wait_chain")
            .sql
            .contains("android_sync_binder_thread_state_by_txn")
    );
    assert!(
        by_id("frame_jank")
            .sql
            .contains("INCLUDE PERFETTO MODULE android.frames.per_frame_metrics;")
    );
    assert!(
        by_id("mem_counters")
            .sql
            .contains("INCLUDE PERFETTO MODULE linux.memory.process;")
    );
    assert!(
        by_id("power_rails")
            .sql
            .contains("android_power_rails_counters")
    );
}

fn trailing_limit(sql: &str) -> usize {
    let last = sql
        .lines()
        .last()
        .expect("at least one line")
        .trim()
        .to_owned();
    assert!(last.starts_with("LIMIT "), "last line: {last}");
    last[6..].trim().parse().expect("numeric LIMIT")
}

fn query_presence(id: &str) -> bool {
    diagnostic_query(id).is_some()
}

#[test]
fn presets_reference_existing_queries_in_compose_order() {
    let presets = diagnostic_presets();
    let ids: Vec<&str> = presets.iter().map(|preset| preset.id).collect();
    assert_eq!(
        ids,
        vec![
            "core",
            "jank",
            "scheduling",
            "binder",
            "io",
            "memory",
            "power",
            "all"
        ]
    );
    assert_eq!(CORE_PRESET_ID, "core");

    for preset in presets {
        assert!(!preset.query_ids.is_empty(), "{}", preset.id);
        let mut unique = preset.query_ids.to_vec();
        unique.sort_unstable();
        unique.dedup();
        assert_eq!(unique.len(), preset.query_ids.len(), "{}", preset.id);
        assert_eq!(
            preset.queries().count(),
            preset.query_ids.len(),
            "{} references an unknown query",
            preset.id
        );
        for id in preset.query_ids {
            assert!(diagnostic_query(id).is_some(), "{} -> {}", preset.id, id);
        }
    }

    // Compose test: the core preset is the documented commercial list, in order.
    let core_titles: Vec<&str> = presets[0].queries().map(|query| query.title).collect();
    assert_eq!(
        core_titles,
        vec![
            "CPU Scheduling Hotspots",
            "CPU Frequency Distribution",
            "Binder Transaction Latency",
            "Frame Jank Detection",
            "Memory Usage Timeline",
            "Input Event Latency",
            "Thread State Breakdown",
            "Thread Wakeup Latency",
        ]
    );
    let all = presets.last().expect("all preset");
    assert_eq!(all.query_ids.len(), diagnostic_queries().len());
    assert_eq!(all.queries().count(), diagnostic_queries().len());

    // Compose byId falls back to core.
    assert_eq!(diagnostic_preset(Some("missing")).id, "core");
    assert_eq!(diagnostic_preset(None).id, "core");
    assert_eq!(diagnostic_preset(Some("jank")).id, "jank");
}

#[test]
fn categories_match_the_compose_analysis_enum() {
    // Compose enum constant names are the payload used in AI context.
    assert_eq!(DiagnosticCategory::Cpu.name(), "CPU");
    assert_eq!(DiagnosticCategory::Binder.name(), "BINDER");
    assert_eq!(DiagnosticCategory::Graphics.name(), "GRAPHICS");
    assert_eq!(DiagnosticCategory::Memory.name(), "MEMORY");
    assert_eq!(DiagnosticCategory::Input.name(), "INPUT");
    assert_eq!(DiagnosticCategory::Io.name(), "IO");
    assert_eq!(DiagnosticCategory::Runtime.name(), "RUNTIME");
    assert_eq!(DiagnosticCategory::Power.name(), "POWER");
    assert_eq!(DiagnosticCategory::Cpu.display_name(), "CPU Scheduling");
    assert_eq!(
        DiagnosticCategory::Binder.display_name(),
        "Binder Transactions"
    );
    assert_eq!(
        DiagnosticCategory::Graphics.display_name(),
        "Graphics Pipeline"
    );
    assert_eq!(DiagnosticCategory::Memory.display_name(), "Memory");
    assert_eq!(DiagnosticCategory::Input.display_name(), "Input Latency");
    assert_eq!(DiagnosticCategory::Io.display_name(), "I/O and SQLite");
    assert_eq!(DiagnosticCategory::Runtime.display_name(), "ART Runtime");
    assert_eq!(
        DiagnosticCategory::Power.display_name(),
        "Thermal and Power"
    );
    assert_eq!(queries_for_category(DiagnosticCategory::Cpu).count(), 9);
    assert_eq!(queries_for_category(DiagnosticCategory::Binder).count(), 5);
    assert_eq!(
        queries_for_category(DiagnosticCategory::Graphics).count(),
        3
    );
    assert_eq!(queries_for_category(DiagnosticCategory::Io).count(), 6);
    assert_eq!(queries_for_category(DiagnosticCategory::Runtime).count(), 5);
    assert_eq!(queries_for_category(DiagnosticCategory::Power).count(), 5);
    assert_eq!(queries_for_category(DiagnosticCategory::Memory).count(), 1);
    assert_eq!(queries_for_category(DiagnosticCategory::Input).count(), 1);
}

#[test]
fn pinned_schema_version_is_reported() {
    assert_eq!(PINNED_VERSION, "v59.1");
    let result = aps_perfetto::DiagnosticResult {
        query_id: "cpu_hotspots",
        schema_version: PINNED_VERSION,
        columns: vec!["thread_name".to_owned()],
        rows: vec![],
        truncated: false,
    };
    assert_eq!(result.schema_version, "v59.1");
    assert_eq!(MAX_DIAGNOSTIC_ROWS, 2000);
}

#[test]
fn catalog_matches_the_compose_oracle_source() {
    let Some(diagnostics) = read_oracle("PerfettoDiagnostics.kt") else {
        eprintln!("skipping: Compose PerfettoDiagnostics.kt is not present");
        return;
    };
    let parsed = parse_diagnostics(&diagnostics);
    assert_eq!(
        parsed.len(),
        diagnostic_queries().len(),
        "Compose oracle and Rust catalog sizes differ"
    );
    for (expected, actual) in parsed.iter().zip(diagnostic_queries()) {
        assert_eq!(expected.id, actual.id, "id");
        assert_eq!(
            expected.category, actual.category,
            "{} category",
            expected.id
        );
        assert_eq!(expected.title, actual.title, "{} title", expected.id);
        assert_eq!(
            expected.description, actual.description,
            "{} description",
            expected.id
        );
        let actual_columns: Vec<&str> = actual.columns.to_vec();
        assert_eq!(expected.columns, actual_columns, "{} columns", expected.id);
        assert_eq!(expected.sql, actual.sql, "{} sql", expected.id);
    }

    let Some(presets) = read_oracle("PerfettoDiagnosticPresets.kt") else {
        eprintln!("skipping: Compose PerfettoDiagnosticPresets.kt is not present");
        return;
    };
    let parsed_presets = parse_presets(&presets);
    assert_eq!(parsed_presets.len(), diagnostic_presets().len());
    for (expected, actual) in parsed_presets.iter().zip(diagnostic_presets()) {
        assert_eq!(expected.id, actual.id);
        assert_eq!(expected.title, actual.title);
        assert_eq!(
            expected.query_ids,
            actual.query_ids.to_vec(),
            "{}",
            expected.id
        );
    }
}

struct OracleQuery {
    id: String,
    category: DiagnosticCategory,
    title: String,
    description: String,
    columns: Vec<String>,
    sql: String,
}

struct OraclePreset {
    id: String,
    title: String,
    query_ids: Vec<String>,
}

fn parse_diagnostics(source: &str) -> Vec<OracleQuery> {
    let chunks: Vec<&str> = source.split("DiagnosticQuery(").skip(2).collect();
    let mut queries = Vec::new();
    for chunk in chunks {
        let Some(id) = unquote_field(chunk, "id = ") else {
            continue;
        };
        let category = category_after(chunk, "category = DiagnosticCategory.")
            .unwrap_or_else(|| panic!("category for {id}"));
        let title = unquote_field(chunk, "title = ").unwrap_or_else(|| panic!("title for {id}"));
        let description = unquote_field(chunk, "description = ")
            .unwrap_or_else(|| panic!("description for {id}"));
        let columns = columns_after(chunk).unwrap_or_else(|| panic!("columns for {id}"));
        let sql = sql_after(chunk).unwrap_or_else(|| panic!("sql for {id}"));
        queries.push(OracleQuery {
            id,
            category,
            title,
            description,
            columns,
            sql,
        });
    }
    queries
}

fn parse_presets(source: &str) -> Vec<OraclePreset> {
    let chunks: Vec<&str> = source.split("DiagnosticPreset(").skip(2).collect();
    let mut presets = Vec::new();
    for chunk in chunks {
        let Some(id) = unquote_field(chunk, "id = ") else {
            continue;
        };
        let title = unquote_field(chunk, "title = ").unwrap_or_else(|| panic!("title for {id}"));
        if let Some(list) = slice_between(chunk, "listOf(", ")") {
            presets.push(OraclePreset {
                id,
                title,
                query_ids: quoted_all(list),
            });
        } else if chunk.contains("PerfettoDiagnostics.all.map(DiagnosticQuery::id)") {
            let ids: Vec<String> = diagnostic_queries()
                .iter()
                .map(|query| query.id.to_owned())
                .collect();
            presets.push(OraclePreset {
                id,
                title,
                query_ids: ids,
            });
        }
    }
    presets
}

fn unquote_field(text: &str, key: &str) -> Option<String> {
    let start = text.find(key)? + key.len();
    let rest = &text[start..];
    let open = rest.find('"')? + 1;
    let close = rest[open..].find('"')? + open;
    Some(rest[open..close].to_owned())
}

fn category_after(text: &str, key: &str) -> Option<DiagnosticCategory> {
    let start = text.find(key)? + key.len();
    let rest = &text[start..];
    let end = rest
        .find(|character: char| !character.is_ascii_alphanumeric())
        .unwrap_or(rest.len());
    Some(match &rest[..end] {
        "CPU" => DiagnosticCategory::Cpu,
        "BINDER" => DiagnosticCategory::Binder,
        "GRAPHICS" => DiagnosticCategory::Graphics,
        "MEMORY" => DiagnosticCategory::Memory,
        "INPUT" => DiagnosticCategory::Input,
        "IO" => DiagnosticCategory::Io,
        "RUNTIME" => DiagnosticCategory::Runtime,
        "POWER" => DiagnosticCategory::Power,
        other => panic!("unknown Compose category {other}"),
    })
}

fn columns_after(text: &str) -> Option<Vec<String>> {
    let start = text.find("columns =")?;
    let rest = &text[start..];
    let list = slice_between(rest, "listOf(", ")")?;
    Some(quoted_all(list))
}

fn sql_after(text: &str) -> Option<String> {
    let start = text.find("sql =")?;
    let rest = &text[start..];
    let open = rest.find("\"\"\"")? + 3;
    let close = rest[open..].find("\"\"\"")? + open;
    Some(kotlin_trim_indent(&rest[open..close]))
}

fn slice_between<'a>(text: &'a str, open: &str, close: &str) -> Option<&'a str> {
    let start = text.find(open)? + open.len();
    let end = text[start..].find(close)? + start;
    Some(&text[start..end])
}

fn quoted_all(text: &str) -> Vec<String> {
    let mut values = Vec::new();
    let mut chars = text.chars();
    while let Some(character) = chars.next() {
        if character != '"' {
            continue;
        }
        let mut value = String::new();
        for character in chars.by_ref() {
            if character == '"' {
                break;
            }
            value.push(character);
        }
        values.push(value);
    }
    values
}

fn kotlin_trim_indent(raw: &str) -> String {
    let mut lines: Vec<&str> = raw.split('\n').collect();
    if lines.first().is_some_and(|line| line.trim().is_empty()) {
        lines.remove(0);
    }
    if lines.last().is_some_and(|line| line.trim().is_empty()) {
        lines.pop();
    }
    let indent = lines
        .iter()
        .filter(|line| !line.trim().is_empty())
        .map(|line| line.len() - line.trim_start().len())
        .min()
        .unwrap_or(0);
    lines
        .iter()
        .map(|line| {
            if line.trim().is_empty() {
                String::new()
            } else {
                line.get(indent..).unwrap_or("").to_owned()
            }
        })
        .collect::<Vec<_>>()
        .join("\n")
}

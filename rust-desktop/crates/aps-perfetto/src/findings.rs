//! Compose DiagnosticFindings port: fixed APS thresholds over query rows.
//!
//! Thresholds are fixed heuristics; they never invent a value the query did not
//! report. Localized metric text lives in the UI (the engine only reports the
//! metric kind/value/total/subject), mirroring the Compose oracle.

use crate::diagnostics::{DiagnosticQuery, DiagnosticResult};

pub const JANK_CRITICAL_RATIO: f64 = 0.05;
pub const BINDER_CRITICAL_MS: f64 = 100.0;
pub const BINDER_WARNING_MS: f64 = 16.0;
pub const BLOCKED_CRITICAL_MS: f64 = 500.0;
pub const BLOCKED_WARNING_MS: f64 = 100.0;
pub const INPUT_CRITICAL_MS: f64 = 200.0;
pub const INPUT_WARNING_MS: f64 = 100.0;
pub const WAKEUP_WARNING_US: f64 = 5_000.0;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DiagnosticFindingLevel {
    Critical,
    Warning,
    Info,
    /// The query ran but the trace recorded no rows for it.
    NoData,
}

/// The aggregate a finding is derived from; the UI localizes it.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum DiagnosticMetricKind {
    JankyFrames,
    MaxLatencyMs,
    MaxBlockedMs,
    MaxWakeupUs,
    PeakRssKb,
    TopCpuMs,
    TopFrequencyMhz,
    TopStateMs,
    Rows,
}

#[derive(Clone, Debug, PartialEq)]
pub struct DiagnosticMetric {
    pub kind: DiagnosticMetricKind,
    pub value: f64,
    pub total: Option<f64>,
    pub subject: Option<String>,
}

#[derive(Clone, Debug, PartialEq)]
pub struct DiagnosticFinding {
    pub level: DiagnosticFindingLevel,
    pub metric: DiagnosticMetric,
    /// True when the level came from an APS threshold rather than a
    /// trace-provided classification.
    pub heuristic: bool,
}

/// Derives a severity and headline aggregate from the rows a diagnostic
/// actually returned. Port of Compose DiagnosticFindings.evaluate.
///
/// Returns None only when result.columns does not match the query's declared
/// schema (a failed-closed pairing); otherwise every query, known or unknown,
/// yields a finding (Rows for unknown ids, NoData for 0 rows).
pub fn evaluate_finding(
    query: &DiagnosticQuery,
    result: &DiagnosticResult,
) -> Option<DiagnosticFinding> {
    if result.columns.len() != query.columns.len()
        || result
            .columns
            .iter()
            .zip(query.columns.iter())
            .any(|(left, right)| left != right)
    {
        return None;
    }
    if result.rows.is_empty() {
        return Some(DiagnosticFinding {
            level: DiagnosticFindingLevel::NoData,
            metric: DiagnosticMetric {
                kind: DiagnosticMetricKind::Rows,
                value: 0.0,
                total: None,
                subject: None,
            },
            heuristic: false,
        });
    }
    Some(match query.id {
        "frame_jank" => frame_jank(result),
        "binder_latency" => max_threshold(
            result,
            "max_dur_ms",
            "client",
            DiagnosticMetricKind::MaxLatencyMs,
            Some(BINDER_CRITICAL_MS),
            BINDER_WARNING_MS,
        ),
        "main_thread_blocked" => max_threshold(
            result,
            "max_blocked_ms",
            "thread_name",
            DiagnosticMetricKind::MaxBlockedMs,
            Some(BLOCKED_CRITICAL_MS),
            BLOCKED_WARNING_MS,
        ),
        "input_latency" => max_threshold(
            result,
            "max_dur_ms",
            "process_name",
            DiagnosticMetricKind::MaxLatencyMs,
            Some(INPUT_CRITICAL_MS),
            INPUT_WARNING_MS,
        ),
        "wakeup_latency" => max_threshold(
            result,
            "max_latency_us",
            "thread_name",
            DiagnosticMetricKind::MaxWakeupUs,
            None,
            WAKEUP_WARNING_US,
        ),
        "mem_counters" => informational(
            result,
            "max_kb",
            "process_name",
            DiagnosticMetricKind::PeakRssKb,
            false,
        ),
        "cpu_hotspots" => informational(
            result,
            "total_dur_ms",
            "thread_name",
            DiagnosticMetricKind::TopCpuMs,
            false,
        ),
        "cpu_freq_dist" => informational(
            result,
            "freq_mhz",
            "cpu",
            DiagnosticMetricKind::TopFrequencyMhz,
            true,
        ),
        "thread_states" => informational(
            result,
            "total_dur_ms",
            "scheduler_state",
            DiagnosticMetricKind::TopStateMs,
            true,
        ),
        _ => rows(result),
    })
}

fn frame_jank(result: &DiagnosticResult) -> DiagnosticFinding {
    let janky = sum(result, "janky_frames");
    let total = sum(result, "frame_count");
    let (Some(janky), Some(total)) = (janky, total) else {
        return rows(result);
    };
    if total <= 0.0 {
        return rows(result);
    }
    let level = if janky / total >= JANK_CRITICAL_RATIO {
        DiagnosticFindingLevel::Critical
    } else if janky > 0.0 {
        DiagnosticFindingLevel::Warning
    } else {
        DiagnosticFindingLevel::Info
    };
    // was_jank is classified by FrameTimeline itself; only the critical ratio
    // is an APS threshold, but the severity is still heuristic.
    DiagnosticFinding {
        level,
        metric: DiagnosticMetric {
            kind: DiagnosticMetricKind::JankyFrames,
            value: janky,
            total: Some(total),
            subject: None,
        },
        heuristic: true,
    }
}

fn max_threshold(
    result: &DiagnosticResult,
    column: &str,
    subject_column: &str,
    kind: DiagnosticMetricKind,
    critical: Option<f64>,
    warning: f64,
) -> DiagnosticFinding {
    let Some((value, subject)) = max_with_subject(result, column, subject_column) else {
        return rows(result);
    };
    let level = if critical.is_some_and(|critical| value >= critical) {
        DiagnosticFindingLevel::Critical
    } else if value >= warning {
        DiagnosticFindingLevel::Warning
    } else {
        DiagnosticFindingLevel::Info
    };
    DiagnosticFinding {
        level,
        metric: DiagnosticMetric {
            kind,
            value,
            total: None,
            subject,
        },
        heuristic: true,
    }
}

fn informational(
    result: &DiagnosticResult,
    column: &str,
    subject_column: &str,
    kind: DiagnosticMetricKind,
    first_row: bool,
) -> DiagnosticFinding {
    let pair = if first_row {
        result.rows.first().and_then(|row| {
            number(row, result, column).map(|value| (value, text(row, result, subject_column)))
        })
    } else {
        max_with_subject(result, column, subject_column)
    };
    let Some((value, subject)) = pair else {
        return rows(result);
    };
    DiagnosticFinding {
        level: DiagnosticFindingLevel::Info,
        metric: DiagnosticMetric {
            kind,
            value,
            total: None,
            subject,
        },
        heuristic: false,
    }
}

fn rows(result: &DiagnosticResult) -> DiagnosticFinding {
    DiagnosticFinding {
        level: DiagnosticFindingLevel::Info,
        metric: DiagnosticMetric {
            kind: DiagnosticMetricKind::Rows,
            value: result.rows.len() as f64,
            total: None,
            subject: None,
        },
        heuristic: false,
    }
}

fn sum(result: &DiagnosticResult, column: &str) -> Option<f64> {
    let index = result.columns.iter().position(|name| name == column)?;
    let values: Vec<f64> = result
        .rows
        .iter()
        .filter_map(|row| {
            row.get(index)
                .and_then(|value| number_value(value.as_deref()))
        })
        .collect();
    if values.is_empty() {
        None
    } else {
        Some(values.iter().sum())
    }
}

/// First row holding the maximum value wins, matching Kotlin maxByOrNull.
fn max_with_subject(
    result: &DiagnosticResult,
    column: &str,
    subject_column: &str,
) -> Option<(f64, Option<String>)> {
    let column_index = result.columns.iter().position(|name| name == column)?;
    let subject_index = result
        .columns
        .iter()
        .position(|name| name == subject_column);
    let mut best: Option<(f64, Option<String>)> = None;
    for row in &result.rows {
        let Some(value) = row
            .get(column_index)
            .and_then(|value| number_value(value.as_deref()))
        else {
            continue;
        };
        if best.as_ref().is_none_or(|(current, _)| value > *current) {
            let subject = subject_index
                .and_then(|index| row.get(index))
                .and_then(|value| value.as_deref())
                .filter(|value| !value.trim().is_empty())
                .map(str::to_owned);
            best = Some((value, subject));
        }
    }
    best
}

fn number(row: &[Option<String>], result: &DiagnosticResult, column: &str) -> Option<f64> {
    let index = result.columns.iter().position(|name| name == column)?;
    row.get(index)
        .and_then(|value| number_value(value.as_deref()))
}

fn text(row: &[Option<String>], result: &DiagnosticResult, column: &str) -> Option<String> {
    let index = result.columns.iter().position(|name| name == column)?;
    row.get(index)
        .and_then(|value| value.as_deref())
        .filter(|value| !value.trim().is_empty())
        .map(str::to_owned)
}

fn number_value(value: Option<&str>) -> Option<f64> {
    value
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .and_then(|value| value.parse::<f64>().ok())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::PINNED_VERSION;
    use crate::diagnostics::diagnostic_query;

    fn result(query: &DiagnosticQuery, rows: Vec<Vec<Option<&str>>>) -> DiagnosticResult {
        DiagnosticResult {
            query_id: query.id,
            schema_version: PINNED_VERSION,
            columns: query
                .columns
                .iter()
                .map(|column| (*column).to_owned())
                .collect(),
            rows: rows
                .into_iter()
                .map(|row| {
                    row.into_iter()
                        .map(|value| value.map(str::to_owned))
                        .collect()
                })
                .collect(),
            truncated: false,
        }
    }

    fn diagnostic(id: &str) -> &'static DiagnosticQuery {
        diagnostic_query(id).expect("catalog query")
    }

    #[test]
    fn empty_results_are_no_data() {
        let query = diagnostic("frame_jank");
        let finding = evaluate_finding(query, &result(query, Vec::new())).expect("finding");
        assert_eq!(finding.level, DiagnosticFindingLevel::NoData);
        assert_eq!(finding.metric.kind, DiagnosticMetricKind::Rows);
        assert_eq!(finding.metric.value, 0.0);
        assert!(!finding.heuristic);
    }

    #[test]
    fn frame_jank_uses_frame_timeline_counts_across_processes() {
        let query = diagnostic("frame_jank");
        let critical = result(
            query,
            vec![
                vec![
                    Some("app"),
                    Some("100"),
                    Some("12"),
                    Some("8"),
                    Some("40"),
                    Some("24"),
                ],
                vec![
                    Some("launcher"),
                    Some("100"),
                    Some("0"),
                    Some("7"),
                    Some("9"),
                    Some("0"),
                ],
            ],
        );
        let finding = evaluate_finding(query, &critical).expect("finding");
        assert_eq!(finding.level, DiagnosticFindingLevel::Critical);
        assert_eq!(finding.metric.kind, DiagnosticMetricKind::JankyFrames);
        assert_eq!(finding.metric.value, 12.0);
        assert_eq!(finding.metric.total, Some(200.0));
        assert!(finding.heuristic);

        let warning = result(
            query,
            vec![vec![
                Some("app"),
                Some("1000"),
                Some("2"),
                Some("8"),
                Some("40"),
                Some("24"),
            ]],
        );
        assert_eq!(
            evaluate_finding(query, &warning).expect("finding").level,
            DiagnosticFindingLevel::Warning
        );
        let smooth = result(
            query,
            vec![vec![
                Some("app"),
                Some("1000"),
                Some("0"),
                Some("8"),
                Some("12"),
                Some("0"),
            ]],
        );
        assert_eq!(
            evaluate_finding(query, &smooth).expect("finding").level,
            DiagnosticFindingLevel::Info
        );
    }

    #[test]
    fn latency_thresholds_pick_the_worst_row_and_keep_the_subject() {
        let query = diagnostic("binder_latency");
        let finding = evaluate_finding(
            query,
            &result(
                query,
                vec![
                    vec![
                        Some("app"),
                        Some("system_server"),
                        Some("4"),
                        Some("3"),
                        Some("18.5"),
                    ],
                    vec![
                        Some("app"),
                        Some("surfaceflinger"),
                        Some("2"),
                        Some("1"),
                        Some("2"),
                    ],
                ],
            ),
        )
        .expect("finding");
        assert_eq!(finding.level, DiagnosticFindingLevel::Warning);
        assert_eq!(finding.metric.kind, DiagnosticMetricKind::MaxLatencyMs);
        assert_eq!(finding.metric.value, 18.5);
        assert_eq!(finding.metric.subject.as_deref(), Some("app"));
        assert!(finding.heuristic);

        let critical = evaluate_finding(
            query,
            &result(
                query,
                vec![vec![
                    Some("app"),
                    Some("system_server"),
                    Some("4"),
                    Some("3"),
                    Some("120"),
                ]],
            ),
        )
        .expect("finding");
        assert_eq!(critical.level, DiagnosticFindingLevel::Critical);

        let wakeup = diagnostic("wakeup_latency");
        let finding = evaluate_finding(
            wakeup,
            &result(
                wakeup,
                vec![vec![
                    Some("main"),
                    Some("app"),
                    Some("3"),
                    Some("100"),
                    Some("5000"),
                ]],
            ),
        )
        .expect("finding");
        assert_eq!(finding.level, DiagnosticFindingLevel::Warning);
        assert_eq!(finding.metric.kind, DiagnosticMetricKind::MaxWakeupUs);
    }

    #[test]
    fn informational_metrics_match_compose_columns() {
        let memory = diagnostic("mem_counters");
        let finding = evaluate_finding(
            memory,
            &result(
                memory,
                vec![
                    vec![Some("app"), Some("100"), Some("2048"), Some("1024")],
                    vec![Some("system"), Some("10"), Some("512"), Some("256")],
                ],
            ),
        )
        .expect("finding");
        assert_eq!(finding.metric.kind, DiagnosticMetricKind::PeakRssKb);
        assert_eq!(finding.metric.value, 2048.0);
        assert_eq!(finding.metric.subject.as_deref(), Some("app"));
        assert!(!finding.heuristic);

        let frequency = diagnostic("cpu_freq_dist");
        let finding = evaluate_finding(
            frequency,
            &result(
                frequency,
                vec![
                    vec![Some("0"), Some("1800"), Some("4"), Some("1.0")],
                    vec![Some("1"), Some("2400"), Some("2"), Some("0.5")],
                ],
            ),
        )
        .expect("finding");
        assert_eq!(finding.metric.kind, DiagnosticMetricKind::TopFrequencyMhz);
        // firstRow = true in Compose: the first row is reported, not the max.
        assert_eq!(finding.metric.value, 1800.0);
        assert_eq!(finding.metric.subject.as_deref(), Some("0"));
    }

    #[test]
    fn unparsable_aggregates_fall_back_to_row_counts() {
        let query = diagnostic("frame_jank");
        let finding = evaluate_finding(
            query,
            &result(query, vec![vec![Some("app"), None, None, None, None, None]]),
        )
        .expect("finding");
        assert_eq!(finding.metric.kind, DiagnosticMetricKind::Rows);
        assert_eq!(finding.metric.value, 1.0);
        assert!(!finding.heuristic);
    }

    #[test]
    fn unknown_queries_and_schema_mismatch() {
        let unknown = DiagnosticQuery {
            id: "unknown_diagnostic",
            category: crate::DiagnosticCategory::Cpu,
            title: "Unknown",
            description: "Unknown",
            columns: &["a"],
            sql: "SELECT 1 AS a LIMIT 1",
        };
        let finding =
            evaluate_finding(&unknown, &result(&unknown, vec![vec![Some("x")]])).expect("finding");
        assert_eq!(finding.metric.kind, DiagnosticMetricKind::Rows);

        let mut mismatched = result(&unknown, vec![vec![Some("x")]]);
        mismatched.columns = vec!["other".to_owned()];
        assert!(evaluate_finding(&unknown, &mismatched).is_none());
    }
}

//! Pure presentation projections for the startup workspace.
//!
//! Imported durations remain evidence, not a reconstructed phase timeline. In
//! particular, Activity Manager `TotalTime` never fills a missing TTID/TTFD.

use aps_startup::{StartupMode, StartupRun};

pub(super) const MAIN_RAIL_BREAKPOINT: f32 = 1100.;
pub(super) const RAIL_WIDTH: f32 = 300.;
pub(super) const RUN_TABLE_MIN_WIDTH: f32 = 760.;
pub(super) const RUN_COLUMN_WEIGHTS: [f32; 7] = [0.7, 1., 1., 1., 1., 1.1, 0.8];

/// Resolve selection by stable run identity within the currently visible page.
/// A new filter/page may exclude the old selection; the first visible run then
/// provides a deterministic detail selection without reusing Frame's row index.
pub(super) fn selected_run<'a>(rows: &'a [StartupRun], id: Option<&str>) -> Option<&'a StartupRun> {
    id.and_then(|id| rows.iter().find(|run| run.id == id))
        .or_else(|| rows.first())
}

pub(super) fn metric_ms(value: Option<u64>) -> String {
    value.map_or_else(|| "—".to_owned(), |value| format!("{value} ms"))
}

/// Unscaled median absolute deviation of independently available TTID samples.
/// Missing TTID and metrics without a declared evidence source are excluded;
/// TotalTime, TTFD and requested launch mode cannot supply substitute samples.
pub(super) fn median_absolute_deviation(runs: &[StartupRun]) -> Option<f64> {
    ttid_mad(runs.iter())
}

/// Compute dispersion over the complete requested-mode cohort, not just the
/// bounded page currently displayed by the run table.
pub(super) fn filtered_mad(runs: &[StartupRun], requested: Option<StartupMode>) -> Option<f64> {
    ttid_mad(
        runs.iter()
            .filter(|run| requested.is_none_or(|mode| run.requested_mode == mode)),
    )
}

fn ttid_mad<'a>(runs: impl Iterator<Item = &'a StartupRun>) -> Option<f64> {
    let mut samples: Vec<f64> = runs
        .filter(|run| run.ttid.source.is_some())
        .filter_map(|run| run.ttid.milliseconds.map(|value| value as f64))
        .collect();
    let center = median(&mut samples)?;
    let mut deviations: Vec<f64> = samples.iter().map(|value| (value - center).abs()).collect();
    median(&mut deviations)
}

fn median(values: &mut [f64]) -> Option<f64> {
    if values.is_empty() {
        return None;
    }
    values.sort_unstable_by(f64::total_cmp);
    // Match StartupAnalyzer.statistics and the Rust summary: nearest-rank
    // percentile(0.5), including the lower middle of an even-sized cohort.
    Some(values[values.len().div_ceil(2) - 1])
}

/// Preserved export declarations, in a stable display order. These fields are
/// not independently verified by the Rust importer. Unknown CSV columns are
/// deliberately excluded instead of becoming arbitrary application UI content.
pub(super) fn evidence_fields(run: &StartupRun) -> Vec<(&'static str, String)> {
    [
        "compilerFilter",
        "compilationVerified",
        "profileSource",
        "profileSourceDeclared",
        "baselineProfileArtifact",
        "deviceModel",
        "apiLevel",
        "emulator",
        "batteryPercent",
        "charging",
        "thermalStatus",
        "traceFile",
        "traceCaptured",
        "traceTruncated",
        "traceFailure",
    ]
    .into_iter()
    .filter_map(|key| {
        run.imported_fields
            .get(key)
            .filter(|value| !value.trim().is_empty())
            .map(|value| (key, value.clone()))
    })
    .collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_startup::{DurationMetric, MetricSource, StartupMode};
    use std::collections::BTreeMap;

    fn run(id: &str, ttid: Option<u64>) -> StartupRun {
        StartupRun {
            iteration: 1,
            id: id.to_owned(),
            package: None,
            requested_mode: StartupMode::Cold,
            observed_mode: StartupMode::Unknown,
            status: None,
            activity: None,
            this_time_ms: None,
            total_time_ms: Some(9000),
            wait_time_ms: None,
            complete: false,
            ttid: DurationMetric {
                milliseconds: ttid,
                source: ttid.map(|_| MetricSource::ImportedCsv),
                unavailable_reason: ttid.is_none().then(|| "No Displayed evidence".to_owned()),
            },
            ttfd: DurationMetric {
                milliseconds: None,
                source: None,
                unavailable_reason: Some("No reportFullyDrawn evidence".to_owned()),
            },
            warnings: Vec::new(),
            imported_fields: BTreeMap::new(),
        }
    }

    #[test]
    fn layout_constants_match_compose_reference() {
        assert_eq!(MAIN_RAIL_BREAKPOINT, 1100.);
        assert_eq!(RAIL_WIDTH, 300.);
        assert_eq!(RUN_TABLE_MIN_WIDTH, 760.);
        assert_eq!(RUN_COLUMN_WEIGHTS, [0.7, 1., 1., 1., 1., 1.1, 0.8]);
    }

    #[test]
    fn stable_run_identity_survives_reordering() {
        let rows = [run("second", Some(200)), run("first", Some(100))];
        assert_eq!(selected_run(&rows, Some("first")).unwrap().id, "first");
    }

    #[test]
    fn missing_or_filtered_selection_uses_first_visible_run() {
        let rows = [run("visible", None), run("other", None)];
        assert_eq!(selected_run(&rows, None).unwrap().id, "visible");
        assert_eq!(selected_run(&rows, Some("off-page")).unwrap().id, "visible");
        assert!(selected_run(&[], Some("off-page")).is_none());
    }

    #[test]
    fn missing_duration_is_not_zero_and_zero_remains_a_real_sample() {
        assert_eq!(metric_ms(None), "—");
        assert_eq!(metric_ms(Some(0)), "0 ms");
        assert_eq!(metric_ms(Some(u64::MAX)), format!("{} ms", u64::MAX));
        assert_eq!(median_absolute_deviation(&[run("zero", Some(0))]), Some(0.));
    }

    #[test]
    fn mad_uses_available_ttid_only_and_handles_even_samples() {
        let rows = [
            run("one", Some(100)),
            run("two", Some(200)),
            run("three", Some(300)),
            run("four", Some(400)),
            run("missing", None),
        ];
        assert_eq!(median_absolute_deviation(&rows), Some(100.));
        assert_eq!(median_absolute_deviation(&rows[..3]), Some(100.));
    }

    #[test]
    fn total_time_or_unsourced_value_cannot_fill_missing_ttid() {
        let missing = run("missing", None);
        let mut unsourced = run("unsourced", Some(100));
        unsourced.ttid.source = None;
        assert_eq!(median_absolute_deviation(&[missing, unsourced]), None);
        assert_eq!(median_absolute_deviation(&[]), None);
    }

    #[test]
    fn extreme_available_samples_do_not_overflow_mad() {
        let rows = [
            run("low", Some(0)),
            run("mid", Some(u64::MAX / 2)),
            run("high", Some(u64::MAX)),
        ];
        let mad = median_absolute_deviation(&rows).unwrap();
        assert!(mad.is_finite());
        assert!(mad > 0.);
    }

    #[test]
    fn filtered_mad_uses_requested_mode_not_observed_mode() {
        let mut first = run("cold-one", Some(100));
        first.observed_mode = StartupMode::Warm;
        let mut second = run("cold-two", Some(300));
        second.observed_mode = StartupMode::Warm;
        let mut warm = run("warm", Some(9000));
        warm.requested_mode = StartupMode::Warm;
        warm.observed_mode = StartupMode::Cold;
        let rows = [first, second, warm, run("missing", None)];
        assert_eq!(filtered_mad(&rows, Some(StartupMode::Cold)), Some(0.));
        assert_eq!(filtered_mad(&rows, Some(StartupMode::Warm)), Some(0.));
        assert_eq!(filtered_mad(&rows, Some(StartupMode::Hot)), None);
        assert_eq!(filtered_mad(&rows, None), median_absolute_deviation(&rows));
    }

    #[test]
    fn filtered_mad_has_no_total_time_fallback_for_missing_cohort_evidence() {
        let rows = [run("missing-one", None), run("missing-two", None)];
        assert_eq!(filtered_mad(&rows, Some(StartupMode::Cold)), None);
        assert_eq!(filtered_mad(&[], None), None);
    }

    #[test]
    fn cohort_mad_includes_runs_beyond_the_visible_page() {
        let rows = [
            run("one", Some(100)),
            run("two", Some(200)),
            run("three", Some(300)),
            run("four", Some(400)),
        ];
        assert_eq!(filtered_mad(&rows, Some(StartupMode::Cold)), Some(100.));
        assert_eq!(filtered_mad(&rows[..1], Some(StartupMode::Cold)), Some(0.));
    }

    #[test]
    fn evidence_is_whitelisted_ordered_and_preserved_verbatim() {
        let mut row = run("one", Some(100));
        row.imported_fields = [
            ("traceFailure", "Original diagnostic\nsecond line"),
            ("compilerFilter", "speed-profile"),
            ("profileSourceDeclared", "false"),
            ("deviceModel", "  Imported Model  "),
            ("unknownFutureField", "not application UI"),
            ("ttidUnavailableReason", "not a compiler/environment field"),
            ("apiLevel", " \t "),
        ]
        .into_iter()
        .map(|(key, value)| (key.to_owned(), value.to_owned()))
        .collect();
        assert_eq!(
            evidence_fields(&row),
            vec![
                ("compilerFilter", "speed-profile".to_owned()),
                ("profileSourceDeclared", "false".to_owned()),
                ("deviceModel", "  Imported Model  ".to_owned()),
                (
                    "traceFailure",
                    "Original diagnostic\nsecond line".to_owned()
                ),
            ]
        );
        assert!(evidence_fields(&run("empty", None)).is_empty());
    }
}

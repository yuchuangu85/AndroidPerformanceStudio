//! Offline startup evidence: am-start output, scoped events and existing CSV exports.
mod csv;
mod parser;

use std::collections::BTreeMap;
use std::fs::File;
use std::io::Read;
use std::path::Path;

pub const MAX_INPUT_BYTES: u64 = 64 * 1024 * 1024;
pub const MAX_RUNS: usize = 100_000;
pub const MAX_PAGE_SIZE: usize = 512;

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum StartupMode {
    Cold,
    Warm,
    Hot,
    #[default]
    Unknown,
}

impl StartupMode {
    pub fn parse(value: &str) -> Self {
        match value.trim().to_ascii_uppercase().as_str() {
            "COLD" => Self::Cold,
            "WARM" => Self::Warm,
            "HOT" => Self::Hot,
            _ => Self::Unknown,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MetricSource {
    EventLog,
    ImportedCsv,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DurationMetric {
    pub milliseconds: Option<u64>,
    pub source: Option<MetricSource>,
    pub unavailable_reason: Option<String>,
}

impl DurationMetric {
    fn missing(reason: &str) -> Self {
        Self {
            milliseconds: None,
            source: None,
            unavailable_reason: Some(reason.into()),
        }
    }

    fn observed(milliseconds: u64, source: MetricSource) -> Self {
        Self {
            milliseconds: Some(milliseconds),
            source: Some(source),
            unavailable_reason: None,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StartupRun {
    pub iteration: u32,
    pub id: String,
    pub package: Option<String>,
    pub requested_mode: StartupMode,
    pub observed_mode: StartupMode,
    pub status: Option<String>,
    pub activity: Option<String>,
    pub this_time_ms: Option<u64>,
    pub total_time_ms: Option<u64>,
    pub wait_time_ms: Option<u64>,
    pub complete: bool,
    /// Platform Displayed duration only; never TotalTime/agent-first-frame fallback.
    pub ttid: DurationMetric,
    /// reportFullyDrawn evidence only; absence stays absent, never TTID fallback.
    pub ttfd: DurationMetric,
    pub warnings: Vec<String>,
    /// Original export fields preserve compilation/environment evidence without
    /// pretending they are verified or sufficient for a comparable cohort.
    pub imported_fields: BTreeMap<String, String>,
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct Statistics {
    pub count: usize,
    pub missing: usize,
    pub minimum_ms: Option<u64>,
    pub maximum_ms: Option<u64>,
    pub mean_ms: Option<f64>,
    pub p50_ms: Option<u64>,
    pub p90_ms: Option<u64>,
    pub p95_ms: Option<u64>,
}

#[derive(Debug, Clone, Default, PartialEq)]
pub struct StartupSummary {
    pub runs: usize,
    pub unknown_observed_modes: usize,
    pub requested_observed_mismatches: usize,
    pub ttid: Statistics,
    pub ttfd: Statistics,
    pub total_time: Statistics,
}

#[derive(Debug, Clone)]
pub struct StartupQuery {
    pub requested_mode: Option<StartupMode>,
    pub observed_mode: Option<StartupMode>,
    pub has_ttid: Option<bool>,
    pub offset: usize,
    pub limit: usize,
}

impl Default for StartupQuery {
    fn default() -> Self {
        Self {
            requested_mode: None,
            observed_mode: None,
            has_ttid: None,
            offset: 0,
            limit: 100,
        }
    }
}

#[derive(Debug, Clone)]
pub struct StartupPage {
    pub total: usize,
    pub offset: usize,
    pub rows: Vec<StartupRun>,
    pub summary: StartupSummary,
}

pub struct StartupSession {
    runs: Vec<StartupRun>,
    summary: StartupSummary,
    warnings: Vec<String>,
}

impl StartupSession {
    pub fn open_am_start(
        path: &Path,
        package: &str,
        requested: StartupMode,
        max_bytes: u64,
    ) -> Result<Self, String> {
        Self::parse_am_start(&read_text(path, max_bytes)?, package, requested)
    }

    pub fn parse_am_start(
        text: &str,
        package: &str,
        requested: StartupMode,
    ) -> Result<Self, String> {
        let run = parser::am_start(text, package, requested)?;
        Self::from_runs(vec![run])
    }

    pub fn open_csv(path: &Path, max_bytes: u64) -> Result<Self, String> {
        Self::parse_csv(&read_text(path, max_bytes)?)
    }

    /// Reads the existing StartupCsvExporter schema by named columns, including
    /// RFC4180 quoted commas/newlines. It does not invent missing cohort identity.
    pub fn parse_csv(text: &str) -> Result<Self, String> {
        Self::from_runs(csv::parse(text)?)
    }

    /// An event-log file must already be scoped to this one launch. Multiple
    /// matching events are ambiguous and rejected rather than taking the latest.
    pub fn with_event_log(mut self, text: &str) -> Result<Self, String> {
        if self.runs.len() != 1 {
            return Err("Event log requires exactly one launch run".into());
        }
        let run = &mut self.runs[0];
        let package = run
            .package
            .as_deref()
            .ok_or("Event log requires an explicit target package")?;
        let (ttid, ttfd) = parser::event_log(text, package)?;
        run.ttid = ttid;
        run.ttfd = ttfd;
        if let (Some(ttid), Some(ttfd)) = (run.ttid.milliseconds, run.ttfd.milliseconds)
            && ttfd < ttid
        {
            return Err(
                "Fully drawn duration precedes displayed duration; evidence is inconsistent".into(),
            );
        }
        self.summary = summarize(self.runs.iter());
        Ok(self)
    }

    fn from_runs(runs: Vec<StartupRun>) -> Result<Self, String> {
        if runs.is_empty() || runs.len() > MAX_RUNS {
            return Err("Startup input requires 1..100000 runs".into());
        }
        let summary = summarize(runs.iter());
        let mut warnings = vec!["Durations are launch-relative milliseconds, not absolute timestamps; cross-clock phase correlation is unavailable for these imports.".into()];
        if runs.len() < 5 {
            warnings.push("Too few measured runs for a stable startup comparison.".into());
        }
        if runs.iter().any(|run| run.package.is_none()) {
            warnings.push("CSV lacks device/package/session identity; no cross-session comparability is claimed.".into());
        }
        Ok(Self {
            runs,
            summary,
            warnings,
        })
    }

    pub fn runs(&self) -> &[StartupRun] {
        &self.runs
    }
    pub fn summary(&self) -> &StartupSummary {
        &self.summary
    }
    pub fn warnings(&self) -> &[String] {
        &self.warnings
    }

    pub fn page(&self, query: &StartupQuery) -> Result<StartupPage, String> {
        if query.limit > MAX_PAGE_SIZE || query.offset.checked_add(query.limit).is_none() {
            return Err("Startup page exceeds supported bounds".into());
        }
        let selected: Vec<_> = self
            .runs
            .iter()
            .filter(|run| {
                query
                    .requested_mode
                    .is_none_or(|mode| mode == run.requested_mode)
                    && query
                        .observed_mode
                        .is_none_or(|mode| mode == run.observed_mode)
                    && query
                        .has_ttid
                        .is_none_or(|has| has == run.ttid.milliseconds.is_some())
            })
            .collect();
        Ok(StartupPage {
            total: selected.len(),
            offset: query.offset,
            rows: selected
                .iter()
                .skip(query.offset)
                .take(query.limit)
                .map(|run| (*run).clone())
                .collect(),
            summary: summarize(selected.into_iter()),
        })
    }
}

fn read_text(path: &Path, max_bytes: u64) -> Result<String, String> {
    if max_bytes == 0 || max_bytes > MAX_INPUT_BYTES {
        return Err("Startup input limit must be between 1 and 64 MiB".into());
    }
    let file = File::open(path).map_err(|_| "Cannot open startup input")?;
    if !file
        .metadata()
        .map_err(|_| "Cannot inspect startup input")?
        .is_file()
    {
        return Err("Startup input must be a regular file".into());
    }
    let mut text = String::new();
    file.take(max_bytes + 1)
        .read_to_string(&mut text)
        .map_err(|_| "Startup input must be bounded UTF-8 text")?;
    if text.len() as u64 > max_bytes {
        return Err("Startup input exceeds byte limit".into());
    }
    Ok(text)
}

fn summarize<'a>(runs: impl Iterator<Item = &'a StartupRun>) -> StartupSummary {
    let runs: Vec<_> = runs.collect();
    StartupSummary {
        runs: runs.len(),
        unknown_observed_modes: runs
            .iter()
            .filter(|run| run.observed_mode == StartupMode::Unknown)
            .count(),
        requested_observed_mismatches: runs
            .iter()
            .filter(|run| {
                run.requested_mode != StartupMode::Unknown
                    && run.observed_mode != StartupMode::Unknown
                    && run.requested_mode != run.observed_mode
            })
            .count(),
        ttid: statistics(runs.iter().map(|run| run.ttid.milliseconds)),
        ttfd: statistics(runs.iter().map(|run| run.ttfd.milliseconds)),
        total_time: statistics(runs.iter().map(|run| run.total_time_ms)),
    }
}

fn statistics(values: impl Iterator<Item = Option<u64>>) -> Statistics {
    let mut missing = 0;
    let mut values: Vec<_> = values
        .filter_map(|value| {
            if value.is_none() {
                missing += 1;
            }
            value
        })
        .collect();
    values.sort_unstable();
    let percentile = |p: usize| {
        values
            .get((values.len() * p).div_ceil(100).saturating_sub(1))
            .copied()
    };
    Statistics {
        count: values.len(),
        missing,
        minimum_ms: values.first().copied(),
        maximum_ms: values.last().copied(),
        mean_ms: (!values.is_empty())
            .then(|| values.iter().map(|value| *value as f64).sum::<f64>() / values.len() as f64),
        p50_ms: percentile(50),
        p90_ms: percentile(90),
        p95_ms: percentile(95),
    }
}

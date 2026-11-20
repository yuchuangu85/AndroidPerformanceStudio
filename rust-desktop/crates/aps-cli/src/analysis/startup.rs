//! Source-bound Startup projections. The domain parses the report once; only
//! fixed enums and numeric observations survive into a headless binding.
use aps_contracts::SourceDigest;
use aps_startup::{StartupMode, StartupSession, Statistics};
use serde_json::{Value, json};

pub(crate) const DEFAULT_LIMIT: usize = 8;
pub(crate) const MAX_LIMIT: usize = 16;
pub(crate) const MODES: &[&str] = &["cold", "warm", "hot", "unknown"];
pub(crate) const MISSING_METRICS: &[&str] = &["ttid", "ttfd"];

#[derive(Clone, Copy)]
pub(crate) enum MissingMetric {
    Ttid,
    Ttfd,
}

pub(crate) fn mode(value: &str) -> Result<StartupMode, &'static str> {
    match value {
        "cold" => Ok(StartupMode::Cold),
        "warm" => Ok(StartupMode::Warm),
        "hot" => Ok(StartupMode::Hot),
        "unknown" => Ok(StartupMode::Unknown),
        _ => Err("startup mode must be cold, warm, hot or unknown"),
    }
}

pub(crate) fn missing_metric(value: &str) -> Result<MissingMetric, &'static str> {
    match value {
        "ttid" => Ok(MissingMetric::Ttid),
        "ttfd" => Ok(MissingMetric::Ttfd),
        _ => Err("missing metric must be ttid or ttfd"),
    }
}

pub(crate) struct Query {
    pub(crate) requested: Option<StartupMode>,
    pub(crate) observed: Option<StartupMode>,
    pub(crate) missing: Option<MissingMetric>,
    pub(crate) offset: usize,
    pub(crate) limit: usize,
}

struct Row {
    iteration: u32,
    requested: StartupMode,
    observed: StartupMode,
    ttid_ms: Option<u64>,
    ttfd_ms: Option<u64>,
    this_time_ms: Option<u64>,
    total_time_ms: Option<u64>,
    wait_time_ms: Option<u64>,
}

pub(crate) struct StartupDetails {
    rows: Vec<Row>,
    input_format: &'static str,
}

pub(super) fn looks_like_csv(bytes: &[u8]) -> bool {
    bytes
        .split(|&b| matches!(b, b'\n' | b'\r'))
        .next()
        .is_some_and(|header| {
            header.len() <= 8192
                && header
                    .windows(b"requestedType".len())
                    .any(|w| w == b"requestedType")
        })
}

pub(super) fn csv(bytes: &[u8]) -> Result<(Value, StartupDetails), String> {
    let text = std::str::from_utf8(bytes).map_err(|_| "E_STARTUP_CSV")?;
    let session = StartupSession::parse_csv(text).map_err(|_| "E_STARTUP_CSV")?;
    Ok(project(session, "startup-csv"))
}

pub(super) fn report_json(bytes: &[u8]) -> Result<(Value, StartupDetails), String> {
    let session = aps_startup::report::parse_json(bytes).map_err(|_| "E_STARTUP_JSON")?;
    Ok(project(session, "startup-json-v1"))
}

fn project(session: StartupSession, input_format: &'static str) -> (Value, StartupDetails) {
    fn metric(m: &Statistics) -> Value {
        json!({"observed":m.count,"missing":m.missing,"p50Ms":m.p50_ms,"p95Ms":m.p95_ms,"meanMs":m.mean_ms})
    }
    let s = session.summary();
    let projection = json!({"kind":"startup","inputFormat":input_format,"summary":{
        "runCount":s.runs,"unknownObservedModes":s.unknown_observed_modes,
        "requestedObservedMismatches":s.requested_observed_mismatches,
        "ttid":metric(&s.ttid),"ttfd":metric(&s.ttfd),"amTotalTime":metric(&s.total_time)
    }});
    let rows = session
        .runs()
        .iter()
        .map(|run| Row {
            iteration: run.iteration,
            requested: run.requested_mode,
            observed: run.observed_mode,
            ttid_ms: run.ttid.milliseconds,
            ttfd_ms: run.ttfd.milliseconds,
            this_time_ms: run.this_time_ms,
            total_time_ms: run.total_time_ms,
            wait_time_ms: run.wait_time_ms,
        })
        .collect();
    (projection, StartupDetails { rows, input_format })
}

fn mode_name(mode: StartupMode) -> &'static str {
    match mode {
        StartupMode::Cold => "cold",
        StartupMode::Warm => "warm",
        StartupMode::Hot => "hot",
        StartupMode::Unknown => "unknown",
    }
}

impl StartupDetails {
    pub(crate) fn page(&self, q: &Query, digest: SourceDigest) -> Result<Value, &'static str> {
        if !(1..=MAX_LIMIT).contains(&q.limit) {
            return Err("startup run limit must be between 1 and 16");
        }
        let mut total = 0;
        let mut items = Vec::new();
        let digest = digest.to_hex();
        for (index, run) in self.rows.iter().enumerate().filter(|(_, run)| {
            q.requested.is_none_or(|mode| mode == run.requested)
                && q.observed.is_none_or(|mode| mode == run.observed)
                && q.missing.is_none_or(|metric| match metric {
                    MissingMetric::Ttid => run.ttid_ms.is_none(),
                    MissingMetric::Ttfd => run.ttfd_ms.is_none(),
                })
        }) {
            if total >= q.offset && items.len() < q.limit {
                items.push(json!({
                    "id":format!("startup-run:{digest}:{index}"),"label":"Imported startup run",
                    "value":{"runIndex":index,"iteration":run.iteration,"inputFormat":self.input_format,
                        "requestedMode":mode_name(run.requested),"observedMode":mode_name(run.observed),
                        "ttidMs":run.ttid_ms,"ttfdMs":run.ttfd_ms,"amThisTimeMs":run.this_time_ms,
                        "amTotalTimeMs":run.total_time_ms,"amWaitTimeMs":run.wait_time_ms}
                }));
            }
            total += 1;
        }
        if q.offset > total {
            return Err("startup run offset exceeds matching row count");
        }
        let end = q.offset + items.len();
        Ok(
            json!({"items":items,"total":total,"offset":q.offset,"nextOffset":(end < total).then_some(end)}),
        )
    }
}

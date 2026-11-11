//! Descriptive session comparisons require explicit experiment and counter evidence.
use crate::report::{
    integer, object, optional_bool, optional_text, optional_value, required_text, unsigned32,
};
use crate::{
    BatteryCaptureConfig, BatteryCaptureMode, BatteryDelta, BatteryReport, BatterySnapshot,
    DeltaAvailability, DeviceState, SnapshotIdentity,
};
use aps_json::Value;
use std::collections::BTreeMap;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BatterySnapshotEvidence {
    pub identity: SnapshotIdentity,
    pub statistics_period: Option<String>,
    pub powered: Option<bool>,
    pub uid_records_present: bool,
    pub network_bytes: BTreeMap<String, Option<u64>>,
}

impl BatterySnapshotEvidence {
    pub(crate) fn from_snapshot(snapshot: &BatterySnapshot) -> Self {
        Self {
            identity: snapshot.identity.clone(),
            statistics_period: snapshot.stats_period.clone(),
            powered: snapshot.device.powered,
            uid_records_present: snapshot.uid_records_present,
            network_bytes: snapshot.network_bytes.clone(),
        }
    }

    fn snapshot(&self) -> BatterySnapshot {
        BatterySnapshot {
            identity: self.identity.clone(),
            stats_period: self.statistics_period.clone(),
            device: DeviceState {
                powered: self.powered,
                ..Default::default()
            },
            timers: BTreeMap::new(),
            network_bytes: self.network_bytes.clone(),
            modeled_energy_mah: BTreeMap::new(),
            uid_records_present: self.uid_records_present,
            warnings: Vec::new(),
        }
    }

    fn from_value(value: &Value) -> Result<Self, String> {
        let counters = value
            .get("networkBytes")
            .and_then(Value::as_object)
            .ok_or("Battery snapshot evidence lacks network counters")?;
        if counters.len() > 32 {
            return Err("Battery snapshot evidence exceeds counter budget".into());
        }
        let mut network_bytes = BTreeMap::new();
        for key in counters.keys() {
            if key.is_empty() || key.len() > 128 {
                return Err("Invalid Battery snapshot counter name".into());
            }
            network_bytes.insert(key.clone(), integer(&value["networkBytes"], key)?);
        }
        Ok(Self {
            identity: SnapshotIdentity {
                uid: unsigned32(value, "uid")?.ok_or("Missing Battery snapshot UID")?,
                shared_uid: optional_bool(value, "sharedUid")?
                    .ok_or("Missing Battery shared UID evidence")?,
                device_id: required_text(value, "deviceLocalId")?,
                boot_id: optional_text(value, "bootId")?,
                elapsed_ms: integer(value, "elapsedMs")?.ok_or("Missing Battery snapshot clock")?,
            },
            statistics_period: optional_text(value, "statisticsPeriod")?,
            powered: optional_bool(value, "powered")?,
            uid_records_present: optional_bool(value, "uidRecordsPresent")?
                .ok_or("Missing Battery UID coverage")?,
            network_bytes,
        })
    }

    fn to_value(&self) -> Value {
        object([
            ("uid", self.identity.uid.into()),
            ("sharedUid", self.identity.shared_uid.into()),
            ("deviceLocalId", self.identity.device_id.clone().into()),
            ("bootId", optional_value(self.identity.boot_id.as_deref())),
            ("elapsedMs", self.identity.elapsed_ms.into()),
            (
                "statisticsPeriod",
                optional_value(self.statistics_period.as_deref()),
            ),
            ("powered", optional_value(self.powered)),
            ("uidRecordsPresent", self.uid_records_present.into()),
            (
                "networkBytes",
                Value::Object(
                    self.network_bytes
                        .iter()
                        .map(|(key, value)| (key.clone(), optional_value(*value)))
                        .collect(),
                ),
            ),
        ])
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BatterySnapshotInterval {
    pub before: BatterySnapshotEvidence,
    pub after: BatterySnapshotEvidence,
    pub charging_states: Vec<Option<bool>>,
}

impl BatterySnapshotInterval {
    pub(crate) fn from_snapshots(
        before: &BatterySnapshot,
        after: &BatterySnapshot,
        charging_states: Vec<Option<bool>>,
    ) -> Self {
        Self {
            before: BatterySnapshotEvidence::from_snapshot(before),
            after: BatterySnapshotEvidence::from_snapshot(after),
            charging_states,
        }
    }
    pub(crate) fn from_value(value: &Value) -> Result<Option<Self>, String> {
        if value.is_null() {
            return Ok(None);
        }
        let charging_states = match value.get("chargingStates") {
            None | Some(Value::Null) => Vec::new(),
            Some(Value::Array(values)) if values.len() <= 1024 => values
                .iter()
                .map(|value| {
                    if value.is_null() {
                        Ok(None)
                    } else {
                        value
                            .as_bool()
                            .map(Some)
                            .ok_or("Invalid Battery charging sample".to_owned())
                    }
                })
                .collect::<Result<Vec<_>, _>>()?,
            Some(_) => return Err("Invalid Battery charging sample array".into()),
        };
        Ok(Some(Self {
            before: BatterySnapshotEvidence::from_value(&value["before"])?,
            after: BatterySnapshotEvidence::from_value(&value["after"])?,
            charging_states,
        }))
    }
    pub(crate) fn to_value(&self) -> Value {
        object([
            ("before", self.before.to_value()),
            ("after", self.after.to_value()),
            (
                "chargingStates",
                self.charging_states
                    .iter()
                    .map(|value| optional_value(*value))
                    .collect::<Vec<_>>()
                    .into(),
            ),
        ])
    }
}

pub(crate) fn config_value(config: BatteryCaptureConfig) -> Value {
    object([
        ("mode", config.mode.as_str().into()),
        ("durationCeilingSeconds", config.duration_seconds.into()),
        ("pollingSeconds", config.polling_interval().into()),
        ("requestedRuns", config.run_count().into()),
        (
            "cooldownSeconds",
            if config.mode == BatteryCaptureMode::Repeated {
                config.cooldown_seconds
            } else {
                0
            }
            .into(),
        ),
    ])
}

pub(crate) fn parse_config(value: &Value) -> Result<Option<BatteryCaptureConfig>, String> {
    if value.is_null() {
        return Ok(None);
    }
    let config = BatteryCaptureConfig {
        mode: match required_text(value, "mode")?.as_str() {
            "INTERACTIVE" => BatteryCaptureMode::Interactive,
            "TIMED" => BatteryCaptureMode::Timed,
            "REPEATED" => BatteryCaptureMode::Repeated,
            "ONLINE" => BatteryCaptureMode::Online,
            _ => return Err("Invalid Battery comparison capture mode".into()),
        },
        duration_seconds: unsigned32(value, "durationCeilingSeconds")?
            .ok_or("Missing Battery duration ceiling")?,
        polling_seconds: unsigned32(value, "pollingSeconds")?
            .ok_or("Missing Battery polling interval")?,
        runs: unsigned32(value, "requestedRuns")?.ok_or("Missing Battery requested runs")?,
        cooldown_seconds: unsigned32(value, "cooldownSeconds")?
            .ok_or("Missing Battery cooldown")?,
    };
    config.validate()?;
    if (config.mode != BatteryCaptureMode::Repeated
        && (config.runs != 1 || config.cooldown_seconds != 0))
        || (config.mode == BatteryCaptureMode::Online && config.polling_seconds < 30)
    {
        return Err("Battery comparison configuration must use effective capture settings".into());
    }
    Ok(Some(config))
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum BaselineIssue {
    MissingIdentity,
    DifferentIdentity,
    MissingWorkload,
    DifferentWorkload,
    MissingConfiguration,
    MissingCompletion,
    DifferentConfiguration,
    IncompleteRuns,
    MissingSnapshots,
    InvalidSnapshots,
    MissingCharging,
    DifferentCharging,
    MissingNetwork,
    ZeroBaseline,
}

#[derive(Debug, Clone, PartialEq)]
pub struct BatteryReportComparison {
    pub comparable: bool,
    pub baseline_network_median: Option<u64>,
    pub current_network_median: Option<u64>,
    pub network_bytes_change: Option<i128>,
    pub network_percent_change: Option<f64>,
    pub reasons: Vec<BaselineIssue>,
}

fn network_median(report: &BatteryReport) -> Option<u64> {
    let mut values = report
        .runs()
        .iter()
        .filter_map(|run| run.network_bytes)
        .collect::<Vec<_>>();
    values.sort_unstable();
    // Compose uses nearest rank, including the lower middle sample for even N.
    values
        .get(values.len().div_ceil(2).saturating_sub(1))
        .copied()
}

fn evidence_issues(report: &BatteryReport, reasons: &mut Vec<BaselineIssue>) -> Option<bool> {
    if report.device_local_id.as_deref().is_none_or(str::is_empty)
        || report.package_name.as_deref().is_none_or(str::is_empty)
        || report.uid.is_none()
        || !matches!(
            report.attribution_scope.as_deref(),
            Some("UID" | "SHARED_UID")
        )
    {
        reasons.push(BaselineIssue::MissingIdentity);
    }
    if report
        .workload_id
        .as_deref()
        .is_none_or(|value| value.trim().is_empty())
    {
        reasons.push(BaselineIssue::MissingWorkload);
    }
    if let Some(config) = report.configuration {
        if config.validate().is_err()
            || report.capture_mode.as_deref() != Some(config.mode.as_str())
        {
            reasons.push(BaselineIssue::DifferentConfiguration);
        }
        if report.runs().len() != config.run_count() as usize
            || report
                .runs()
                .iter()
                .any(|run| run.duration_ms < u64::from(config.duration_seconds) * 1000)
        {
            reasons.push(BaselineIssue::IncompleteRuns);
        }
    } else {
        reasons.push(BaselineIssue::MissingConfiguration);
    }
    match report.stopped_early {
        None => reasons.push(BaselineIssue::MissingCompletion),
        Some(true) => reasons.push(BaselineIssue::IncompleteRuns),
        Some(false) => {}
    }
    let mut charging = None;
    let mut previous: Option<&BatterySnapshotEvidence> = None;
    for (index, run) in report.runs().iter().enumerate() {
        if run.iteration as usize != index + 1 || run.duration_ms == 0 {
            reasons.push(BaselineIssue::InvalidSnapshots);
        }
        let Some(interval) = &run.snapshot_interval else {
            reasons.push(BaselineIssue::MissingSnapshots);
            continue;
        };
        let delta = BatteryDelta::between(&interval.before.snapshot(), &interval.after.snapshot());
        if interval.charging_states.len() < 2
            || interval.charging_states.iter().any(Option::is_none)
        {
            reasons.push(BaselineIssue::MissingCharging);
        }
        if interval.charging_states.first().copied() != Some(interval.before.powered)
            || interval.charging_states.last().copied() != Some(interval.after.powered)
        {
            reasons.push(BaselineIssue::InvalidSnapshots);
        }
        if interval
            .charging_states
            .iter()
            .any(|state| *state != interval.before.powered)
        {
            reasons.push(BaselineIssue::DifferentCharging);
        }
        if delta.availability != DeltaAvailability::Available
            || delta.duration_ms != Some(run.duration_ms)
            || delta.network_total_bytes != run.network_bytes
            || [
                "mobile_rx",
                "mobile_tx",
                "wifi_rx",
                "wifi_tx",
                "bluetooth_rx",
                "bluetooth_tx",
            ]
            .iter()
            .any(|key| {
                !interval.before.network_bytes.contains_key(*key)
                    || !interval.after.network_bytes.contains_key(*key)
            })
            || interval
                .before
                .identity
                .boot_id
                .as_deref()
                .is_none_or(str::is_empty)
            || interval
                .after
                .identity
                .boot_id
                .as_deref()
                .is_none_or(str::is_empty)
            || interval
                .before
                .statistics_period
                .as_deref()
                .is_none_or(str::is_empty)
            || interval
                .after
                .statistics_period
                .as_deref()
                .is_none_or(str::is_empty)
            || previous.is_some_and(|previous| {
                previous.identity.boot_id != interval.before.identity.boot_id
                    || previous.statistics_period != interval.before.statistics_period
                    || previous.identity.elapsed_ms > interval.before.identity.elapsed_ms
            })
        {
            reasons.push(BaselineIssue::InvalidSnapshots);
        }
        for snapshot in [&interval.before, &interval.after] {
            if Some(snapshot.identity.uid) != report.uid
                || Some(snapshot.identity.device_id.as_str()) != report.device_local_id.as_deref()
                || snapshot.identity.shared_uid
                    != (report.attribution_scope.as_deref() == Some("SHARED_UID"))
            {
                reasons.push(BaselineIssue::InvalidSnapshots);
            }
            match snapshot.powered {
                None => reasons.push(BaselineIssue::MissingCharging),
                Some(powered) => {
                    if charging.is_some_and(|previous| previous != powered) {
                        reasons.push(BaselineIssue::DifferentCharging);
                    }
                    charging = Some(powered);
                }
            }
        }
        previous = Some(&interval.after);
    }
    if report.runs().iter().any(|run| run.network_bytes.is_none()) {
        reasons.push(BaselineIssue::MissingNetwork);
    }
    charging
}

/// Missing evidence is useful to display even before a second report exists.
pub fn baseline_issues(report: &BatteryReport) -> Vec<BaselineIssue> {
    let mut reasons = Vec::new();
    evidence_issues(report, &mut reasons);
    let mut unique = Vec::new();
    for reason in reasons {
        if !unique.contains(&reason) {
            unique.push(reason);
        }
    }
    unique
}

/// Cross-session boot/accumulator equality is not required: each run must prove
/// its own continuous interval. This is a descriptive difference, not a causal
/// regression verdict, nor a claim that thermal conditions were controlled.
pub fn compare_reports(
    baseline: &BatteryReport,
    current: &BatteryReport,
) -> BatteryReportComparison {
    let mut reasons = Vec::new();
    let baseline_charging = evidence_issues(baseline, &mut reasons);
    let current_charging = evidence_issues(current, &mut reasons);
    if baseline.device_local_id != current.device_local_id
        || baseline.package_name != current.package_name
        || baseline.uid != current.uid
        || baseline.attribution_scope != current.attribution_scope
    {
        reasons.push(BaselineIssue::DifferentIdentity);
    }
    if baseline.workload_id != current.workload_id {
        reasons.push(BaselineIssue::DifferentWorkload);
    }
    if baseline.configuration.map(config_value) != current.configuration.map(config_value) {
        reasons.push(BaselineIssue::DifferentConfiguration);
    }
    if baseline_charging != current_charging {
        reasons.push(BaselineIssue::DifferentCharging);
    }
    let comparable = reasons.is_empty();
    let baseline_network_median = network_median(baseline);
    let current_network_median = network_median(current);
    let network_bytes_change = baseline_network_median
        .zip(current_network_median)
        .filter(|_| comparable)
        .map(|(baseline, current)| i128::from(current) - i128::from(baseline));
    let network_percent_change = baseline_network_median
        .zip(network_bytes_change)
        .filter(|(baseline, _)| *baseline > 0)
        .map(|(baseline, change)| change as f64 / baseline as f64 * 100.);
    if comparable && baseline_network_median == Some(0) {
        reasons.push(BaselineIssue::ZeroBaseline);
    }
    let mut unique = Vec::new();
    for reason in reasons {
        if !unique.contains(&reason) {
            unique.push(reason);
        }
    }
    BatteryReportComparison {
        comparable,
        baseline_network_median,
        current_network_median,
        network_bytes_change,
        network_percent_change,
        reasons: unique,
    }
}

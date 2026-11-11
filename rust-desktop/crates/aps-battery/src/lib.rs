//! Read-only Battery snapshots, explicitly requested capture and conservative UID differences.
mod baseline;
mod capture;
mod parser;
mod report;
mod sessions;

pub use baseline::{
    BaselineIssue, BatteryReportComparison, BatterySnapshotEvidence, BatterySnapshotInterval,
    baseline_issues, compare_reports,
};
pub use capture::{
    BatteryCaptureArtifact, BatteryCaptureConfig, BatteryCaptureControl, BatteryCaptureMode,
    BatteryCaptureOutcome, BatteryCaptureProgress, BatteryCaptureTarget, capture_live,
    capture_live_with_workload,
};
pub use parser::{parse_device_state, parse_snapshot};
pub use report::{
    BatteryReport, BatteryReportRun, BatteryRunObservation, ImportedEnergy,
    ImportedPerformanceWindow,
};
pub use sessions::{BatterySavedSession, BatterySessionStore, StoredBatterySession};
use std::collections::{BTreeMap, BTreeSet};

pub const MAX_INPUT_BYTES: usize = 64 * 1024 * 1024;
pub const MAX_PAGE_SIZE: usize = 512;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SnapshotIdentity {
    pub uid: u32,
    pub shared_uid: bool,
    pub device_id: String,
    pub boot_id: Option<String>,
    /// Absolute elapsed time since the same device boot, not host wall-clock time.
    pub elapsed_ms: u64,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct DeviceState {
    pub level_percent: Option<u8>,
    pub temperature_tenths_celsius: Option<i32>,
    pub voltage_millivolts: Option<u32>,
    pub powered: Option<bool>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, PartialOrd, Ord)]
pub enum ResourceKind {
    Wakelock,
    Alarm,
    Job,
    Sensor,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ResourceTimer {
    pub kind: ResourceKind,
    pub name: String,
    pub duration_ms: u64,
    pub count: u64,
}

#[derive(Debug, Clone)]
pub struct BatterySnapshot {
    pub identity: SnapshotIdentity,
    pub stats_period: Option<String>,
    pub device: DeviceState,
    pub timers: BTreeMap<(ResourceKind, String), ResourceTimer>,
    /// None means missing, not a measured zero. Keys identify byte counters.
    pub network_bytes: BTreeMap<String, Option<u64>>,
    /// System-model mAh attributed to the selected UID, not hardware energy.
    pub modeled_energy_mah: BTreeMap<String, f64>,
    pub uid_records_present: bool,
    pub warnings: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum DeltaAvailability {
    Available,
    UnknownIdentity,
    IncompatibleIdentity,
}

#[derive(Debug, Clone)]
pub struct TimerDelta {
    pub kind: ResourceKind,
    pub name: String,
    pub duration_ms: Option<u64>,
    pub count: Option<u64>,
    pub unavailable_reason: Option<String>,
}

#[derive(Debug, Clone)]
pub struct BatteryDelta {
    pub availability: DeltaAvailability,
    pub duration_ms: Option<u64>,
    pub uid: u32,
    pub device_id: String,
    pub shared_uid: bool,
    pub timers: Vec<TimerDelta>,
    pub network_bytes: BTreeMap<String, Option<u64>>,
    pub network_total_bytes: Option<u64>,
    pub modeled_energy_mah: BTreeMap<String, Option<f64>>,
    pub device_level_change: Option<i16>,
    pub warnings: Vec<String>,
}

impl BatteryDelta {
    /// Counters require the same explicit device/boot/stats-period and target UID.
    /// Missing identity fails closed instead of assuming a continuous accumulator.
    pub fn between(before: &BatterySnapshot, after: &BatterySnapshot) -> Self {
        let mut warnings = Vec::new();
        let identity_changed = before.identity.uid != after.identity.uid
            || before.identity.device_id != after.identity.device_id
            || matches!((&before.identity.boot_id, &after.identity.boot_id), (Some(a), Some(b)) if a != b)
            || matches!((&before.stats_period, &after.stats_period), (Some(a), Some(b)) if a != b)
            || after.identity.elapsed_ms < before.identity.elapsed_ms;
        let identity_missing = before.identity.device_id.is_empty()
            || after.identity.device_id.is_empty()
            || before.identity.boot_id.is_none()
            || after.identity.boot_id.is_none()
            || before.stats_period.is_none()
            || after.stats_period.is_none()
            || !before.uid_records_present
            || !after.uid_records_present;
        let availability = if identity_changed {
            DeltaAvailability::IncompatibleIdentity
        } else if identity_missing {
            DeltaAvailability::UnknownIdentity
        } else {
            DeltaAvailability::Available
        };
        let allowed = availability == DeltaAvailability::Available;
        if !allowed {
            warnings.push("Cumulative differences unavailable: device, boot, stats period and UID continuity must all be established.".into());
        }
        if before.identity.shared_uid || after.identity.shared_uid {
            warnings.push("Shared UID attribution does not establish ownership by a single package or component.".into());
        }
        if before.device.powered != after.device.powered {
            warnings.push("Charging state changed or became unavailable between snapshots.".into());
        }
        let mut timers = Vec::new();
        for key in before
            .timers
            .keys()
            .chain(after.timers.keys())
            .collect::<BTreeSet<_>>()
        {
            let delta = before
                .timers
                .get(key)
                .zip(after.timers.get(key))
                .filter(|_| allowed)
                .and_then(|(before, after)| {
                    Some((
                        after.duration_ms.checked_sub(before.duration_ms)?,
                        after.count.checked_sub(before.count)?,
                    ))
                });
            timers.push(TimerDelta {
                kind: key.0,
                name: key.1.clone(),
                duration_ms: delta.map(|value| value.0),
                count: delta.map(|value| value.1),
                unavailable_reason: delta
                    .is_none()
                    .then(|| "Counter missing, reset, or snapshot continuity unproven".into()),
            });
        }
        timers.sort_by(|a, b| b.duration_ms.cmp(&a.duration_ms).then(a.name.cmp(&b.name)));
        let mut network_bytes = BTreeMap::new();
        for key in before
            .network_bytes
            .keys()
            .chain(after.network_bytes.keys())
            .collect::<BTreeSet<_>>()
        {
            let delta = before
                .network_bytes
                .get(key)
                .copied()
                .flatten()
                .zip(after.network_bytes.get(key).copied().flatten())
                .filter(|_| allowed)
                .and_then(|(before, after)| after.checked_sub(before));
            network_bytes.insert(key.clone(), delta);
        }
        let network_total_bytes = if network_bytes.len() == 6 {
            network_bytes
                .values()
                .try_fold(0u64, |sum, value| sum.checked_add((*value)?))
        } else {
            None
        };
        let mut modeled_energy_mah = BTreeMap::new();
        for key in before
            .modeled_energy_mah
            .keys()
            .chain(after.modeled_energy_mah.keys())
            .collect::<BTreeSet<_>>()
        {
            let delta = before
                .modeled_energy_mah
                .get(key)
                .zip(after.modeled_energy_mah.get(key))
                .filter(|_| allowed)
                .map(|(before, after)| after - before)
                .filter(|value| value.is_finite() && *value >= 0.0);
            modeled_energy_mah.insert(key.clone(), delta);
        }
        if modeled_energy_mah.values().any(Option::is_none)
            || network_bytes.values().any(Option::is_none)
            || timers.iter().any(|timer| timer.duration_ms.is_none())
        {
            warnings.push("Some cumulative counters are missing or reset; unavailable values are not substituted with zero.".into());
        }
        Self {
            availability,
            duration_ms: allowed.then(|| after.identity.elapsed_ms - before.identity.elapsed_ms),
            uid: after.identity.uid,
            device_id: after.identity.device_id.clone(),
            shared_uid: before.identity.shared_uid || after.identity.shared_uid,
            timers,
            network_bytes,
            network_total_bytes,
            modeled_energy_mah,
            // Device charge-level change is not attributed UID energy consumption.
            device_level_change: before
                .device
                .level_percent
                .zip(after.device.level_percent)
                .map(|(before, after)| i16::from(after) - i16::from(before)),
            warnings,
        }
    }

    pub fn page(
        &self,
        kind: Option<ResourceKind>,
        offset: usize,
        limit: usize,
    ) -> Result<TimerPage, String> {
        if limit > MAX_PAGE_SIZE || offset.checked_add(limit).is_none() {
            return Err("Battery page exceeds supported bounds".into());
        }
        let selected: Vec<_> = self
            .timers
            .iter()
            .filter(|timer| kind.is_none_or(|kind| timer.kind == kind))
            .collect();
        Ok(TimerPage {
            total: selected.len(),
            offset,
            rows: selected
                .into_iter()
                .skip(offset)
                .take(limit)
                .cloned()
                .collect(),
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BatteryCohort {
    pub device_id: String,
    pub workload_id: String,
    pub charging: Option<bool>,
    /// Explicit experiment-defined thermal band, not a guessed temperature.
    pub thermal_band: Option<String>,
    pub requested_duration_ms: u64,
}

/// Descriptive comparison of two explicitly equal experiment cohorts. It does
/// not claim statistical regression significance or code-level causality.
pub fn compare_cohort(
    baseline: &BatteryDelta,
    baseline_cohort: &BatteryCohort,
    current: &BatteryDelta,
    current_cohort: &BatteryCohort,
) -> BatteryComparison {
    let mut reasons = Vec::new();
    if baseline_cohort != current_cohort
        || baseline_cohort.device_id.is_empty()
        || baseline_cohort.workload_id.is_empty()
        || baseline_cohort.charging.is_none()
        || baseline_cohort
            .thermal_band
            .as_ref()
            .is_none_or(String::is_empty)
        || baseline_cohort.requested_duration_ms == 0
    {
        reasons.push(
            "Device, workload, charging, thermal and duration cohort must be explicit and equal."
                .into(),
        );
    }
    if baseline.device_id != baseline_cohort.device_id
        || current.device_id != current_cohort.device_id
    {
        reasons.push("Experiment cohort device does not match snapshot device.".into());
    }
    if baseline.availability != DeltaAvailability::Available
        || current.availability != DeltaAvailability::Available
        || baseline.uid != current.uid
        || baseline.shared_uid != current.shared_uid
    {
        reasons.push("UID attribution or snapshot continuity is incompatible.".into());
    }
    if baseline.duration_ms != Some(baseline_cohort.requested_duration_ms)
        || current.duration_ms != Some(current_cohort.requested_duration_ms)
    {
        reasons.push("Measured duration differs from the declared comparable experiment.".into());
    }
    let comparable = reasons.is_empty();
    BatteryComparison {
        comparable,
        network_bytes_change: baseline
            .network_total_bytes
            .zip(current.network_total_bytes)
            .filter(|_| comparable)
            .map(|(a, b)| i128::from(b) - i128::from(a)),
        modeled_energy_mah_change: baseline
            .modeled_energy_mah
            .get("total")
            .copied()
            .flatten()
            .zip(current.modeled_energy_mah.get("total").copied().flatten())
            .filter(|_| comparable)
            .map(|(a, b)| b - a),
        reasons,
    }
}

#[derive(Debug, Clone)]
pub struct TimerPage {
    pub total: usize,
    pub offset: usize,
    pub rows: Vec<TimerDelta>,
}

#[derive(Debug, Clone)]
pub struct BatteryComparison {
    pub comparable: bool,
    pub network_bytes_change: Option<i128>,
    pub modeled_energy_mah_change: Option<f64>,
    pub reasons: Vec<String>,
}

pub fn compare(baseline: &BatteryDelta, current: &BatteryDelta) -> BatteryComparison {
    let mut reasons = Vec::new();
    if baseline.availability != DeltaAvailability::Available
        || current.availability != DeltaAvailability::Available
    {
        reasons.push("Snapshot continuity is not proven for both runs.".into());
    }
    if baseline.uid != current.uid || baseline.shared_uid != current.shared_uid {
        reasons.push("UID attribution differs.".into());
    }
    if baseline.duration_ms != current.duration_ms {
        reasons.push(
            "Experiment durations differ; raw cumulative totals are not directly comparable."
                .into(),
        );
    }
    // A BatteryDelta alone lacks workload, device and thermal cohort identity.
    reasons.push(
        "Device/workload/thermal cohort evidence is required for a qualified regression decision."
            .into(),
    );
    BatteryComparison {
        comparable: false,
        network_bytes_change: if reasons.len() == 1 {
            baseline
                .network_total_bytes
                .zip(current.network_total_bytes)
                .map(|(a, b)| i128::from(b) - i128::from(a))
        } else {
            None
        },
        modeled_energy_mah_change: if reasons.len() == 1 {
            baseline
                .modeled_energy_mah
                .get("total")
                .copied()
                .flatten()
                .zip(current.modeled_energy_mah.get("total").copied().flatten())
                .map(|(a, b)| b - a)
        } else {
            None
        },
        reasons,
    }
}

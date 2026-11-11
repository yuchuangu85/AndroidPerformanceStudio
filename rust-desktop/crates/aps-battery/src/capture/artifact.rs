//! Immutable, minimized evidence owned by one successful capture. There is no
//! default disk persistence and no whole-device raw dumpsys text in this bundle.
use super::*;
use crate::sessions::evidence_digest;
use aps_json::{Limits, Map, Number, Value};

const MAX_ANALYSIS_BYTES: usize = 1024 * 1024;
const MAX_LEDGER_BYTES: usize = 2 * 1024 * 1024;
const MAX_SNAPSHOT_BYTES: usize = 256 * 1024;
const MAX_MANIFEST_BYTES: usize = 32 * 1024;

/// Private immutable bytes bind export to the exact report session. The UI may
/// retain this behind Arc; cancellation/failure drops only the unpublished copy.
pub struct BatteryCaptureArtifact {
    session_id: String,
    manifest: Vec<u8>,
    analysis: Vec<u8>,
    snapshots: Vec<u8>,
}
impl BatteryCaptureArtifact {
    pub fn session_id(&self) -> &str {
        &self.session_id
    }
    /// Fixed flat names only; callers cannot inject paths into the archive.
    pub fn entries(&self) -> [(&'static str, &[u8]); 3] {
        [
            ("manifest.json", &self.manifest),
            ("analysis.json", &self.analysis),
            ("snapshots.jsonl", &self.snapshots),
        ]
    }
    pub(super) fn build(
        report: &BatteryReport,
        capture: Capture<'_>,
        config: BatteryCaptureConfig,
        host_completed_unix_nanos: u128,
        stopped_early: bool,
    ) -> Result<Self, String> {
        let Capture {
            target,
            identity,
            installation,
            packages,
            ledger,
            ..
        } = capture;
        let analysis = report.to_json(MAX_ANALYSIS_BYTES)?;
        let manifest = encode(
            &object([
                ("format", "aps-battery-minimized-capture-v1".into()),
                ("sessionId", report.session_id.clone().into()),
                ("hostCompletedUnixNanos", host_completed_unix_nanos.to_string().into()),
                ("deviceSerial", target.serial.clone().into()),
                ("bootId", identity.boot_id.clone().into()),
                ("pid", target.pid.into()),
                ("processStartTicks", identity.start_ticks.into()),
                ("processName", target.process_name.clone().into()),
                ("packageName", target.package_name.clone().into()),
                ("uid", identity.uid.into()),
                ("uidPackages", packages.iter().cloned().collect::<Vec<_>>().into()),
                ("installation", object([
                    ("codePath", installation.code_path.clone().into()),
                    ("versionCode", installation.version_code.into()),
                    ("lastUpdateTime", installation.last_update_time.clone().into()),
                    ("verification", "PackageManager metadata at capture boundaries; not APK-content or signature verification".into()),
                ])),
                ("configuration", object([
                    ("mode", config.mode.as_str().into()),
                    ("durationCeilingSeconds", config.duration_seconds.into()),
                    ("pollingSeconds", config.polling_interval().into()),
                    ("requestedRuns", config.run_count().into()),
                    ("cooldownSeconds", if config.mode == BatteryCaptureMode::Repeated {config.cooldown_seconds} else {0}.into()),
                ])),
                ("workloadId", optional(report.workload_id.clone())),
                ("completedRuns", report.runs().len().into()),
                ("snapshotCount", ledger.count.into()),
                ("stoppedEarly", stopped_early.into()),
                ("analysisBytes", analysis.len().into()),
                ("analysisSha256", evidence_digest(&analysis, MAX_ANALYSIS_BYTES)?.into()),
                ("snapshotBytes", ledger.bytes.len().into()),
                ("snapshotsSha256", evidence_digest(&ledger.bytes, MAX_LEDGER_BYTES)?.into()),
                ("observationSources", vec![
                    "dumpsys batterystats --checkin: parsed selected-UID v9 cumulative records",
                    "dumpsys batterystats: optional selected-UID system-model energy",
                    "dumpsys battery: device battery state",
                    "dumpsys thermalservice: optional unambiguous device-wide thermal status",
                    "/proc: boot ID, selected PID UID/start ticks and device uptime",
                    "cmd package list packages -U --user: observed UID package set",
                    "dumpsys package: selected active PackageManager installation metadata",
                ].into()),
                ("evidenceBoundary", "Parsed selected-UID cumulative counters and device battery/thermal observations only. No raw whole-device dump, history, other UID counters, APK bytes or signatures. Sequential reads are not atomic workload intervals. Analysis JSON can be imported independently; import does not restore live provenance.".into()),
            ]),
            MAX_MANIFEST_BYTES,
        )?;
        Self::from_stored_entries(&manifest, &analysis, &ledger.bytes)
    }

    /// Rehydrates bounded, internally matched minimized evidence. Content
    /// digests do not establish live provenance or authorize recapture.
    pub fn from_stored_entries(
        manifest: &[u8],
        analysis: &[u8],
        snapshots: &[u8],
    ) -> Result<Self, String> {
        if manifest.len() > MAX_MANIFEST_BYTES
            || analysis.len() > MAX_ANALYSIS_BYTES
            || snapshots.len() > MAX_LEDGER_BYTES
        {
            return Err("Stored Battery capture evidence exceeds its byte budget".into());
        }
        let root = aps_json::parse_slice(
            manifest,
            Limits {
                max_bytes: MAX_MANIFEST_BYTES as u64,
                max_string_bytes: 4096,
                ..Limits::default()
            },
        )
        .map_err(|error| format!("Stored Battery manifest: {error}"))?;
        let session_id = stored_text(&root, "sessionId")?;
        let report = BatteryReport::parse_json(analysis)?;
        if root["format"].as_str() != Some("aps-battery-minimized-capture-v1")
            || report.session_id != session_id
            || root["analysisBytes"].as_u64() != Some(analysis.len() as u64)
            || root["snapshotBytes"].as_u64() != Some(snapshots.len() as u64)
            || root["analysisSha256"].as_str()
                != Some(evidence_digest(analysis, MAX_ANALYSIS_BYTES)?.as_str())
            || root["snapshotsSha256"].as_str()
                != Some(evidence_digest(snapshots, MAX_LEDGER_BYTES)?.as_str())
        {
            return Err("Stored Battery capture evidence does not match its report".into());
        }
        validate_provenance(&root, &report, snapshots)?;
        Ok(Self {
            session_id: session_id.into(),
            manifest: manifest.to_vec(),
            analysis: analysis.to_vec(),
            snapshots: snapshots.to_vec(),
        })
    }

    pub(crate) fn validate_report(&self, report: &BatteryReport) -> Result<(), String> {
        // Parsing adds an explicit derived-report warning. Compare restored
        // projections on both sides without altering the original export bytes.
        let expected = if report.live_capture {
            self.analysis.clone()
        } else {
            BatteryReport::parse_json(&self.analysis)?.to_json(crate::MAX_INPUT_BYTES)?
        };
        if report.to_json(crate::MAX_INPUT_BYTES)? != expected {
            return Err("Battery capture evidence does not match the published report".into());
        }
        Ok(())
    }
}

fn stored_text<'a>(value: &'a Value, key: &str) -> Result<&'a str, String> {
    value[key]
        .as_str()
        .filter(|text| {
            !text.is_empty() && text.len() <= 4096 && !text.chars().any(char::is_control)
        })
        .ok_or_else(|| format!("Invalid stored Battery {key}"))
}

fn stored_u32(value: &Value, key: &str) -> Result<u32, String> {
    value[key]
        .as_u64()
        .and_then(|value| value.try_into().ok())
        .ok_or_else(|| format!("Invalid stored Battery {key}"))
}

fn validate_provenance(
    root: &Value,
    report: &BatteryReport,
    snapshots: &[u8],
) -> Result<(), String> {
    let serial = stored_text(root, "deviceSerial")?;
    let boot = stored_text(root, "bootId")?;
    let package = stored_text(root, "packageName")?;
    let uid = stored_u32(root, "uid")?;
    stored_text(root, "processName")?;
    let packages = root["uidPackages"]
        .as_array()
        .ok_or("Missing Battery UID packages")?;
    let unique = packages
        .iter()
        .filter_map(Value::as_str)
        .collect::<BTreeSet<_>>();
    if uid == 0
        || stored_u32(root, "pid")? == 0
        || root["processStartTicks"]
            .as_u64()
            .is_none_or(|ticks| ticks == 0)
        || stored_text(root, "hostCompletedUnixNanos")?
            .parse::<u128>()
            .ok()
            .is_none_or(|time| time == 0)
        || unique.len() != packages.len()
        || !unique.contains(package)
        || unique
            .iter()
            .any(|name| name.is_empty() || name.len() > 4096 || name.chars().any(char::is_control))
        || report.uid != Some(uid)
        || report.package_name.as_deref() != Some(package)
        || report.device_local_id.as_deref() != Some(serial)
        || report.attribution_scope.as_deref()
            != Some(if unique.len() > 1 {
                "SHARED_UID"
            } else {
                "UID"
            })
    {
        return Err("Stored Battery target provenance does not match analysis".into());
    }
    let installation = &root["installation"];
    if !stored_text(installation, "codePath")?.starts_with('/')
        || installation["versionCode"].as_u64().is_none()
        || !valid_installation_time(stored_text(installation, "lastUpdateTime")?)
    {
        return Err("Invalid stored Battery installation provenance".into());
    }
    let configuration = &root["configuration"];
    let mode = match configuration["mode"].as_str() {
        Some("INTERACTIVE") => BatteryCaptureMode::Interactive,
        Some("TIMED") => BatteryCaptureMode::Timed,
        Some("REPEATED") => BatteryCaptureMode::Repeated,
        Some("ONLINE") => BatteryCaptureMode::Online,
        _ => return Err("Invalid stored Battery capture mode".into()),
    };
    let config = BatteryCaptureConfig {
        mode,
        duration_seconds: stored_u32(configuration, "durationCeilingSeconds")?,
        polling_seconds: stored_u32(configuration, "pollingSeconds")?,
        runs: stored_u32(configuration, "requestedRuns")?,
        cooldown_seconds: stored_u32(configuration, "cooldownSeconds")?,
    };
    config.validate()?;
    let stopped = root["stoppedEarly"]
        .as_bool()
        .ok_or("Missing Battery stop provenance")?;
    if report.capture_mode.as_deref() != Some(mode.as_str())
        || report.configuration.is_some_and(|report_config| {
            crate::baseline::config_value(report_config) != *configuration
        })
        || report
            .stopped_early
            .is_some_and(|report_stopped| report_stopped != stopped)
        || root
            .get("workloadId")
            .is_some_and(|value| value.as_str() != report.workload_id.as_deref())
        || root["completedRuns"].as_u64() != Some(report.runs().len() as u64)
        || report.runs().len() > config.run_count() as usize
        || (!stopped && report.runs().len() != config.run_count() as usize)
        || (mode != BatteryCaptureMode::Repeated
            && (config.runs != 1 || config.cooldown_seconds != 0))
        || (mode == BatteryCaptureMode::Online && config.polling_seconds < 30)
    {
        return Err("Stored Battery capture configuration does not match analysis".into());
    }
    let text = std::str::from_utf8(snapshots).map_err(|_| "Invalid Battery snapshot UTF-8")?;
    if !text.ends_with('\n') {
        return Err("Stored Battery snapshot ledger is incomplete".into());
    }
    let mut count = 0;
    let mut run_index = 0;
    let mut run_count = 0;
    let mut first_uptime = 0;
    let mut last_uptime = 0;
    let mut last_row = None;
    for line in text.split_terminator('\n') {
        count += 1;
        if count > MAX_SNAPSHOTS || line.len() > MAX_SNAPSHOT_BYTES {
            return Err("Stored Battery snapshot ledger exceeds its budget".into());
        }
        let row = aps_json::parse_slice(
            line.as_bytes(),
            Limits {
                max_bytes: MAX_SNAPSHOT_BYTES as u64,
                max_string_bytes: 4096,
                max_nodes: 100_000,
                ..Limits::default()
            },
        )
        .map_err(|error| format!("Stored Battery snapshot: {error}"))?;
        let iteration = stored_u32(&row, "iteration")?;
        if iteration != report.runs()[run_index].iteration {
            validate_run(report, run_index, run_count, first_uptime, last_uptime)?;
            validate_interval_end(report, run_index, run_count, last_row.as_ref())?;
            run_index += 1;
            if report
                .runs()
                .get(run_index)
                .is_none_or(|run| run.iteration != iteration)
            {
                return Err("Stored Battery snapshot run order mismatch".into());
            }
            run_count = 0;
        }
        let uptime = row["deviceUptimeMs"]
            .as_u64()
            .ok_or("Invalid Battery snapshot clock")?;
        if row["schemaVersion"].as_u64() != Some(1)
            || row["ordinal"].as_u64() != Some(count as u64)
            || row["uid"].as_u64() != Some(uid as u64)
            || row["deviceSerial"].as_str() != Some(serial)
            || row["bootId"].as_str() != Some(boot)
            || row["sharedUid"].as_bool() != Some(unique.len() > 1)
            || (count > 1 && uptime < last_uptime)
        {
            return Err("Stored Battery snapshot identity or order mismatch".into());
        }
        validate_snapshot_fields(&row)?;
        if let Some(interval) = &report.runs()[run_index].snapshot_interval {
            if run_count == 0 {
                validate_interval_edge(&interval.before, &row, false)?;
            }
            if interval.charging_states.get(run_count).copied()
                != Some(row["deviceBattery"]["powered"].as_bool())
            {
                return Err(
                    "Battery interval charging samples do not match the capture ledger".into(),
                );
            }
        }
        if run_count == 0 {
            first_uptime = uptime;
        }
        last_uptime = uptime;
        run_count += 1;
        last_row = Some(row);
    }
    validate_run(report, run_index, run_count, first_uptime, last_uptime)?;
    validate_interval_end(report, run_index, run_count, last_row.as_ref())?;
    if run_index + 1 != report.runs().len() || root["snapshotCount"].as_u64() != Some(count as u64)
    {
        return Err("Stored Battery snapshot count does not match analysis".into());
    }
    Ok(())
}

fn validate_interval_end(
    report: &BatteryReport,
    index: usize,
    count: usize,
    last: Option<&Value>,
) -> Result<(), String> {
    if let Some(interval) = &report.runs()[index].snapshot_interval {
        if interval.charging_states.len() != count {
            return Err("Battery interval sample count does not match the capture ledger".into());
        }
        validate_interval_edge(
            &interval.after,
            last.ok_or("Missing Battery interval final snapshot")?,
            true,
        )?;
    }
    Ok(())
}

fn validate_interval_edge(
    edge: &crate::BatterySnapshotEvidence,
    row: &Value,
    allow_omitted_counters: bool,
) -> Result<(), String> {
    if edge.identity.uid as u64 != row["uid"].as_u64().unwrap_or(u64::MAX)
        || Some(edge.identity.device_id.as_str()) != row["deviceSerial"].as_str()
        || edge.identity.boot_id.as_deref() != row["bootId"].as_str()
        || Some(edge.identity.shared_uid) != row["sharedUid"].as_bool()
        || Some(edge.identity.elapsed_ms) != row["deviceUptimeMs"].as_u64()
        || edge.statistics_period.as_deref() != row["statisticsPeriod"].as_str()
        || edge.powered != row["deviceBattery"]["powered"].as_bool()
        || Some(edge.uid_records_present) != row["uidRecordsPresent"].as_bool()
        || edge.network_bytes.len()
            != row["networkBytes"]
                .as_object()
                .map_or(0, |values| values.len())
        || edge.network_bytes.iter().any(|(key, value)| {
            // Intermediate reset/missing observations may deliberately suppress
            // a final counter; the minimized ledger keeps its original reading.
            !(allow_omitted_counters && value.is_none())
                && *value != row["networkBytes"][key].as_u64()
        })
    {
        return Err("Battery interval evidence does not match the capture ledger".into());
    }
    Ok(())
}

fn valid_installation_time(value: &str) -> bool {
    value.len() == 19
        && value.bytes().enumerate().all(|(index, byte)| match index {
            4 | 7 => byte == b'-',
            10 => byte == b' ',
            13 | 16 => byte == b':',
            _ => byte.is_ascii_digit(),
        })
}

fn validate_run(
    report: &BatteryReport,
    index: usize,
    count: usize,
    first: u64,
    last: u64,
) -> Result<(), String> {
    if count < 2 || last.checked_sub(first) != Some(report.runs()[index].duration_ms) {
        return Err("Stored Battery snapshot interval does not match analysis".into());
    }
    Ok(())
}

fn validate_snapshot_fields(row: &Value) -> Result<(), String> {
    let timers = row["timers"]
        .as_array()
        .ok_or("Missing Battery snapshot timers")?;
    let mut names = BTreeSet::new();
    for timer in timers {
        let kind = stored_text(timer, "kind")?;
        let name = timer["name"]
            .as_str()
            .filter(|name| !name.is_empty() && name.len() <= 4096)
            .ok_or("Invalid Battery snapshot timer name")?;
        if !matches!(
            kind,
            "partial-wakelock" | "alarm-count-only" | "job" | "sensor"
        ) || !names.insert((kind, name))
            || timer["durationMs"].as_u64().is_none()
            || timer["count"].as_u64().is_none()
        {
            return Err("Invalid Battery snapshot timer".into());
        }
    }
    let network = row["networkBytes"]
        .as_object()
        .ok_or("Missing Battery snapshot network")?;
    let energy = row["systemModeledEnergyMah"]
        .as_object()
        .ok_or("Missing Battery snapshot energy")?;
    let battery = row["deviceBattery"]
        .as_object()
        .ok_or("Missing Battery snapshot device state")?;
    if network
        .values()
        .any(|value| !value.is_null() && value.as_u64().is_none())
        || energy.values().any(|value| {
            value
                .as_f64()
                .is_none_or(|value| !value.is_finite() || value < 0.)
        })
        || !row["uidRecordsPresent"].is_boolean()
        || !matches!(
            row.get("statisticsPeriod"),
            Some(Value::String(_)) | Some(Value::Null)
        )
        || row["warnings"]
            .as_array()
            .is_none_or(|warnings| warnings.iter().any(|value| !value.is_string()))
        || !matches!(row.get("deviceThermalStatus"), Some(Value::Null))
            && row["deviceThermalStatus"]
                .as_u64()
                .is_none_or(|value| value > 6)
        || ["levelPercent", "voltageMillivolts"].iter().any(|key| {
            battery
                .get(*key)
                .is_none_or(|value| !value.is_null() && value.as_u64().is_none())
        })
        || battery
            .get("temperatureTenthsCelsius")
            .is_none_or(|value| !value.is_null() && value.as_i64().is_none())
        || battery
            .get("powered")
            .is_none_or(|value| !value.is_null() && !value.is_boolean())
    {
        return Err("Invalid Battery snapshot fields".into());
    }
    Ok(())
}

#[derive(Default)]
pub(super) struct SnapshotLedger {
    bytes: Vec<u8>,
    count: usize,
}
impl SnapshotLedger {
    pub(super) fn append(
        &mut self,
        iteration: u32,
        snapshot: &BatterySnapshot,
        thermal_status: Option<u32>,
    ) -> Result<(), String> {
        let timers = snapshot
            .timers
            .values()
            .map(|timer| {
                object([
                    (
                        "kind",
                        match timer.kind {
                            ResourceKind::Wakelock => "partial-wakelock",
                            ResourceKind::Alarm => "alarm-count-only",
                            ResourceKind::Job => "job",
                            ResourceKind::Sensor => "sensor",
                        }
                        .into(),
                    ),
                    ("name", timer.name.clone().into()),
                    ("durationMs", timer.duration_ms.into()),
                    ("count", timer.count.into()),
                ])
            })
            .collect::<Vec<_>>();
        let network = Value::Object(
            snapshot
                .network_bytes
                .iter()
                .map(|(name, value)| (name.clone(), optional(*value)))
                .collect(),
        );
        let energy = snapshot
            .modeled_energy_mah
            .iter()
            .map(|(name, value)| {
                let value = Number::from_f64(*value).ok_or("Non-finite Battery modeled energy")?;
                Ok((name.clone(), Value::Number(value)))
            })
            .collect::<Result<Map<_, _>, String>>()?;
        let line = encode(
            &object([
                ("schemaVersion", 1.into()),
                ("ordinal", (self.count + 1).into()),
                ("iteration", iteration.into()),
                ("uid", snapshot.identity.uid.into()),
                ("sharedUid", snapshot.identity.shared_uid.into()),
                ("deviceSerial", snapshot.identity.device_id.clone().into()),
                ("bootId", optional(snapshot.identity.boot_id.as_deref())),
                ("deviceUptimeMs", snapshot.identity.elapsed_ms.into()),
                (
                    "statisticsPeriod",
                    optional(snapshot.stats_period.as_deref()),
                ),
                ("uidRecordsPresent", snapshot.uid_records_present.into()),
                ("timers", timers.into()),
                ("networkBytes", network),
                ("systemModeledEnergyMah", Value::Object(energy)),
                (
                    "deviceBattery",
                    object([
                        ("levelPercent", optional(snapshot.device.level_percent)),
                        (
                            "temperatureTenthsCelsius",
                            optional(snapshot.device.temperature_tenths_celsius),
                        ),
                        (
                            "voltageMillivolts",
                            optional(snapshot.device.voltage_millivolts),
                        ),
                        ("powered", optional(snapshot.device.powered)),
                    ]),
                ),
                ("deviceThermalStatus", optional(thermal_status)),
                ("warnings", snapshot.warnings.clone().into()),
            ]),
            MAX_SNAPSHOT_BYTES,
        )?;
        if self
            .bytes
            .len()
            .checked_add(line.len() + 1)
            .is_none_or(|length| length > MAX_LEDGER_BYTES)
        {
            return Err("Battery minimized snapshot ledger exceeds its 2 MiB budget; capture was not published. Reduce duration or increase polling interval.".into());
        }
        self.bytes.extend_from_slice(&line);
        self.bytes.push(b'\n');
        self.count += 1;
        Ok(())
    }
}

fn object<const N: usize>(entries: [(&str, Value); N]) -> Value {
    Value::Object(
        entries
            .into_iter()
            .map(|(name, value)| (name.into(), value))
            .collect(),
    )
}
fn optional<T: Into<Value>>(value: Option<T>) -> Value {
    value.map_or(Value::Null, Into::into)
}
fn encode(value: &Value, max_bytes: usize) -> Result<Vec<u8>, String> {
    aps_json::to_vec(
        value,
        Limits {
            max_bytes: max_bytes as u64,
            max_nodes: 100_000,
            max_string_bytes: 4096,
            ..Limits::default()
        },
    )
    .map_err(|error| format!("Battery minimized evidence bundle: {error}"))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::BTreeMap;

    fn snapshot() -> BatterySnapshot {
        BatterySnapshot {
            identity: SnapshotIdentity {
                uid: 10001,
                shared_uid: false,
                device_id: "selected-device".into(),
                boot_id: Some("selected-boot".into()),
                elapsed_ms: 1000,
            },
            stats_period: Some("period".into()),
            device: crate::DeviceState::default(),
            timers: BTreeMap::new(),
            network_bytes: BTreeMap::from([("wifi_rx".into(), Some(0)), ("wifi_tx".into(), None)]),
            modeled_energy_mah: BTreeMap::new(),
            uid_records_present: true,
            warnings: Vec::new(),
        }
    }

    impl BatteryCaptureArtifact {
        pub(crate) fn test_capture() -> (BatteryReport, Self) {
            let before = snapshot();
            let mut after = before.clone();
            after.identity.elapsed_ms = 6000;
            after.network_bytes.insert("wifi_rx".into(), Some(100));
            let mut report = BatteryReport::from_live_snapshots(
                "capture-fixture",
                "example.app",
                "TIMED",
                &[BatteryRunObservation {
                    iteration: 1,
                    before: before.clone(),
                    after: after.clone(),
                    temperatures_tenths_celsius: Vec::new(),
                    thermal_statuses: Vec::new(),
                    charging_states: vec![before.device.powered, after.device.powered],
                }],
            )
            .unwrap();
            report.configuration = Some(BatteryCaptureConfig {
                mode: BatteryCaptureMode::Timed,
                duration_seconds: 5,
                polling_seconds: 5,
                runs: 1,
                cooldown_seconds: 0,
            });
            report.workload_id = Some("fixture-workload".into());
            report.stopped_early = Some(false);
            let mut ledger = SnapshotLedger::default();
            ledger.append(1, &before, None).unwrap();
            ledger.append(1, &after, None).unwrap();
            let adb = AdbClient::new(aps_adb::AdbLocation {
                executable: "unused-fixture-adb".into(),
                source: aps_adb::AdbLocationSource::ExplicitExecutable,
            });
            let control = BatteryCaptureControl::new(CancellationToken::new());
            let target = BatteryCaptureTarget {
                serial: "selected-device".into(),
                pid: 42,
                process_name: "example.app".into(),
                package_name: "example.app".into(),
            };
            let started = Instant::now();
            let capture = Capture {
                adb: &adb,
                target: &target,
                control: &control,
                identity: ProcessIdentity {
                    boot_id: "selected-boot".into(),
                    uid: 10001,
                    start_ticks: 10,
                },
                packages: BTreeSet::from(["example.app".into()]),
                installation: InstallationIdentity {
                    code_path: "/data/app/example".into(),
                    version_code: 42,
                    last_update_time: "2026-11-11 00:01:02".into(),
                },
                deadline: started + Duration::from_secs(60),
                started,
                snapshots: 2,
                period: Some("period".into()),
                ledger,
            };
            let artifact = Self::build(
                &report,
                capture,
                BatteryCaptureConfig {
                    duration_seconds: 5,
                    polling_seconds: 5,
                    ..BatteryCaptureConfig::default()
                },
                123456789,
                false,
            )
            .unwrap();
            (report, artifact)
        }
    }

    fn encode_manifest(value: &Value) -> Vec<u8> {
        encode(value, MAX_MANIFEST_BYTES).unwrap()
    }

    #[test]
    fn capture_rehydrates_exact_bytes_but_analysis_is_not_live() {
        let (report, artifact) = BatteryCaptureArtifact::test_capture();
        assert!(report.live_capture);
        let entries = artifact.entries();
        let restored =
            BatteryCaptureArtifact::from_stored_entries(entries[0].1, entries[1].1, entries[2].1)
                .unwrap();
        assert_eq!(restored.entries(), entries);
        let derived = BatteryReport::parse_json(entries[1].1).unwrap();
        assert!(!derived.live_capture);
        assert_eq!(derived.configuration, report.configuration);
        assert_eq!(derived.workload_id, report.workload_id);
        assert_eq!(derived.stopped_early, report.stopped_early);
        assert_eq!(
            derived.runs()[0].snapshot_interval,
            report.runs()[0].snapshot_interval
        );
        restored.validate_report(&derived).unwrap();
    }

    #[test]
    fn comparison_evidence_cannot_diverge_from_capture_provenance_even_with_new_digest() {
        let (_, artifact) = BatteryCaptureArtifact::test_capture();
        let entries = artifact.entries();
        for change in 0..7 {
            let mut analysis = aps_json::parse_slice(entries[1].1, Limits::default()).unwrap();
            match change {
                0 => analysis["workloadId"] = "different-workload".into(),
                1 => analysis["configuration"]["durationCeilingSeconds"] = 10.into(),
                2 => analysis["stoppedEarly"] = true.into(),
                3 => analysis["runs"][0]["snapshotInterval"]["before"]["powered"] = false.into(),
                4 => {
                    analysis["runs"][0]["snapshotInterval"]["before"]["statisticsPeriod"] =
                        "other".into()
                }
                5 => {
                    analysis["runs"][0]["snapshotInterval"]["after"]["networkBytes"]["wifi_rx"] =
                        101.into()
                }
                _ => {
                    analysis["runs"][0]["snapshotInterval"]["chargingStates"] =
                        vec![false, false].into()
                }
            }
            let analysis = encode(&analysis, MAX_ANALYSIS_BYTES).unwrap();
            let mut manifest = aps_json::parse_slice(entries[0].1, Limits::default()).unwrap();
            manifest["analysisBytes"] = analysis.len().into();
            manifest["analysisSha256"] = evidence_digest(&analysis, MAX_ANALYSIS_BYTES)
                .unwrap()
                .into();
            assert!(
                BatteryCaptureArtifact::from_stored_entries(
                    &encode_manifest(&manifest),
                    &analysis,
                    entries[2].1
                )
                .is_err(),
                "accepted mutation {change}"
            );
        }
    }

    #[test]
    fn manifest_provenance_lengths_and_digests_are_required() {
        let (_, artifact) = BatteryCaptureArtifact::test_capture();
        let entries = artifact.entries();
        let original = aps_json::parse_slice(entries[0].1, Limits::default()).unwrap();
        for (field, value) in [
            ("format", "other".into()),
            ("sessionId", "other".into()),
            ("packageName", "other.app".into()),
            ("deviceSerial", "other".into()),
            ("bootId", "other".into()),
            ("uid", 10002.into()),
            ("pid", 0.into()),
            ("processStartTicks", 0.into()),
            ("hostCompletedUnixNanos", "invalid".into()),
            ("analysisBytes", 0.into()),
            ("snapshotBytes", 0.into()),
            ("snapshotCount", 0.into()),
            ("completedRuns", 2.into()),
            ("analysisSha256", "0".repeat(64).into()),
            ("snapshotsSha256", "0".repeat(64).into()),
            ("uidPackages", vec!["other.app"].into()),
            ("installation", Value::Null),
            ("configuration", Value::Null),
            ("stoppedEarly", Value::Null),
        ] {
            let mut manifest = original.clone();
            manifest
                .as_object_mut()
                .unwrap()
                .insert(field.into(), value);
            assert!(
                BatteryCaptureArtifact::from_stored_entries(
                    &encode_manifest(&manifest),
                    entries[1].1,
                    entries[2].1
                )
                .is_err(),
                "accepted {field}"
            );
        }
        let mut manifest = original;
        manifest.as_object_mut().unwrap().remove("analysisSha256");
        assert!(
            BatteryCaptureArtifact::from_stored_entries(
                &encode_manifest(&manifest),
                entries[1].1,
                entries[2].1
            )
            .is_err()
        );
    }

    #[test]
    fn malformed_or_mismatched_ledgers_fail_even_with_updated_digest() {
        let (_, artifact) = BatteryCaptureArtifact::test_capture();
        let entries = artifact.entries();
        let text = std::str::from_utf8(entries[2].1).unwrap();
        for snapshots in [
            "garbage\n".to_owned(),
            String::new(),
            text.trim_end().to_owned(),
            text.replace("\"ordinal\":2", "\"ordinal\":1"),
            text.replace("\"iteration\":1", "\"iteration\":2"),
            text.replace("\"uid\":10001", "\"uid\":10002"),
            text.replace("selected-boot", "another-boot"),
            text.replace("\"deviceUptimeMs\":6000", "\"deviceUptimeMs\":5000"),
            text.replace("\"sharedUid\":false", "\"sharedUid\":true"),
            text.replace("\"timers\":[]", "\"timers\":null"),
            text.replace("\"wifi_rx\":100", "\"wifi_rx\":\"bad\""),
            format!("{text}\n"),
        ] {
            let mut manifest = aps_json::parse_slice(entries[0].1, Limits::default()).unwrap();
            manifest["snapshotBytes"] = snapshots.len().into();
            manifest["snapshotsSha256"] = evidence_digest(snapshots.as_bytes(), MAX_LEDGER_BYTES)
                .unwrap()
                .into();
            assert!(
                BatteryCaptureArtifact::from_stored_entries(
                    &encode_manifest(&manifest),
                    entries[1].1,
                    snapshots.as_bytes()
                )
                .is_err(),
                "accepted {snapshots}"
            );
        }
        assert!(
            BatteryCaptureArtifact::from_stored_entries(
                entries[0].1,
                entries[1].1,
                &vec![b'x'; MAX_LEDGER_BYTES + 1]
            )
            .is_err()
        );
    }

    #[test]
    fn ledger_preserves_zero_and_unknown_without_raw_device_text() {
        let mut ledger = SnapshotLedger::default();
        ledger.append(1, &snapshot(), None).unwrap();
        assert_eq!(ledger.count, 1);
        assert_eq!(ledger.bytes.last(), Some(&b'\n'));
        let row = aps_json::parse_slice(&ledger.bytes, Limits::default()).unwrap();
        assert_eq!(row["networkBytes"]["wifi_rx"].as_u64(), Some(0));
        assert!(row["networkBytes"]["wifi_tx"].is_null());
        assert!(row["deviceThermalStatus"].is_null());
        assert!(row.get("rawCheckin").is_none());
        assert_eq!(row["deviceUptimeMs"].as_u64(), Some(1000));
    }

    #[test]
    fn ledger_budget_failure_does_not_append_or_increment() {
        let mut ledger = SnapshotLedger {
            bytes: vec![b'x'; MAX_LEDGER_BYTES],
            count: 2,
        };
        assert!(ledger.append(1, &snapshot(), Some(0)).is_err());
        assert_eq!(ledger.bytes.len(), MAX_LEDGER_BYTES);
        assert_eq!(ledger.count, 2);
        let mut oversized = snapshot();
        oversized.warnings.push("x".repeat(4097));
        let mut empty = SnapshotLedger::default();
        assert!(empty.append(1, &oversized, Some(0)).is_err());
        assert!(empty.bytes.is_empty());
        assert_eq!(empty.count, 0);
    }
}

use crate::{MAX_PAGE_SIZE, ResourceKind, ResourceTimer};
use aps_json::{Limits, Value};
use std::collections::BTreeSet;
use std::path::Path;

/// Imported derived report, not a reconstructable pair of raw Battery Snapshots.
pub struct BatteryReport {
    pub session_id: String,
    pub uid: Option<u32>,
    pub attribution_scope: Option<String>,
    pub package_name: Option<String>,
    pub device_local_id: Option<String>,
    pub capture_mode: Option<String>,
    pub capability_level: Option<String>,
    /// Exported capture timestamp, never inferred from the imported file.
    pub created_at: Option<String>,
    pub warnings: Vec<String>,
    runs: Vec<BatteryReportRun>,
}

#[derive(Debug, Clone)]
pub struct BatteryReportRun {
    pub id: String,
    pub iteration: u32,
    pub duration_ms: u64,
    pub network_bytes: Option<u64>,
    pub timers: Vec<ResourceTimer>,
    /// Source confidences in the same order as `timers`; no confidence is inferred.
    pub timer_confidence: Vec<Option<String>>,
    /// Distinguishes an explicitly empty counter list from an omitted list.
    pub timer_arrays_present: BTreeSet<ResourceKind>,
    pub energy: Vec<ImportedEnergy>,
    pub peak_temperature_celsius: Option<f64>,
    pub max_thermal_status: Option<u32>,
    pub throttled_samples: Option<u64>,
    /// None means not supplied; Some(empty) is an explicitly empty window list.
    pub performance_windows: Option<Vec<ImportedPerformanceWindow>>,
    pub warnings: Vec<String>,
}

impl BatteryReportRun {
    pub fn has_timer_evidence(&self, kind: ResourceKind) -> bool {
        self.timer_arrays_present.contains(&kind)
    }
}

#[derive(Debug, Clone)]
pub struct ImportedPerformanceWindow {
    pub started_at: Option<String>,
    pub ended_at: Option<String>,
    pub duration_ms: Option<u64>,
    pub peak_temperature_celsius: Option<f64>,
    pub max_thermal_status: Option<u32>,
    pub throttled: Option<bool>,
}

#[derive(Debug, Clone)]
pub struct ImportedEnergy {
    pub component: String,
    pub energy_mah: Option<f64>,
    pub energy_uws: Option<u64>,
    pub source: String,
    pub scope: String,
    pub confidence: String,
}

impl BatteryReport {
    pub fn open(path: &Path, max_bytes: u64) -> Result<Self, String> {
        let root = aps_json::read(
            path,
            Limits {
                max_bytes,
                ..Limits::default()
            },
        )
        .map_err(|error| error.to_string())?;
        Self::from_value(root)
    }

    pub fn parse_json(bytes: &[u8]) -> Result<Self, String> {
        Self::from_value(
            aps_json::parse_slice(bytes, Limits::default()).map_err(|error| error.to_string())?,
        )
    }

    fn from_value(root: Value) -> Result<Self, String> {
        if root.get("schemaVersion").and_then(Value::as_u64) != Some(1) {
            return Err("Unsupported battery report schema".into());
        }
        let session_id = required_text(&root, "sessionId")?;
        let rows = root
            .get("runs")
            .and_then(Value::as_array)
            .ok_or("Battery report lacks runs")?;
        if rows.is_empty() || rows.len() > 100_000 {
            return Err("Battery report requires 1..100000 runs".into());
        }
        let mut ids = std::collections::HashSet::new();
        let mut runs = Vec::new();
        for row in rows {
            let id = required_text(row, "runId")?;
            if !ids.insert(id.clone()) {
                return Err("Duplicate battery run identity".into());
            }
            let iteration = integer(row, "iteration")?
                .and_then(|value| u32::try_from(value).ok())
                .filter(|value| *value > 0)
                .ok_or("Invalid battery iteration")?;
            let duration_ms = integer(row, "durationMs")?.ok_or("Missing battery duration")?;
            let mut timers = Vec::new();
            let mut timer_confidence = Vec::new();
            let mut timer_arrays_present = BTreeSet::new();
            for (name, kind) in [
                ("wakelocks", ResourceKind::Wakelock),
                ("alarms", ResourceKind::Alarm),
                ("jobs", ResourceKind::Job),
                ("sensors", ResourceKind::Sensor),
            ] {
                if row.get(name).is_some() {
                    // optional_array below rejects explicit null or malformed arrays.
                    timer_arrays_present.insert(kind);
                }
                for timer in optional_array(row, name)? {
                    timers.push(ResourceTimer {
                        kind,
                        name: required_text(timer, "name")?,
                        duration_ms: integer(timer, "durationMs")?
                            .ok_or("Missing timer duration")?,
                        count: integer(timer, "count")?.ok_or("Missing timer count")?,
                    });
                    timer_confidence.push(optional_text(timer, "confidence")?);
                }
            }
            let mut energy = Vec::new();
            for value in optional_array(row, "energy")? {
                energy.push(ImportedEnergy {
                    component: required_text(value, "component")?,
                    energy_mah: number(value, "energyMah")?,
                    energy_uws: integer(value, "energyUws")?,
                    source: required_text(value, "source")?,
                    scope: required_text(value, "scope")?,
                    confidence: required_text(value, "confidence")?,
                });
            }
            runs.push(BatteryReportRun {
                id,
                iteration,
                duration_ms,
                network_bytes: integer(row, "networkBytes")?,
                timers,
                timer_confidence,
                timer_arrays_present,
                energy,
                peak_temperature_celsius: finite_number(row, "peakTemperatureCelsius")?,
                max_thermal_status: unsigned32(row, "maxThermalStatus")?,
                throttled_samples: integer(row, "throttledSamples")?,
                performance_windows: performance_windows(row)?,
                warnings: strings(row, "warnings")?,
            });
        }
        let mut warnings = strings(&root, "warnings")?;
        warnings.push("Derived battery report lacks raw snapshot continuity; UID counters and measured-zero semantics cannot be reconstructed from omitted fields.".into());
        Ok(Self {
            session_id,
            uid: integer(&root, "uid")?
                .map(u32::try_from)
                .transpose()
                .map_err(|_| "Battery UID out of range")?,
            attribution_scope: optional_text(&root, "attributionScope")?,
            package_name: optional_text(&root, "packageName")?,
            device_local_id: optional_text(&root, "deviceLocalId")?,
            capture_mode: optional_text(&root, "captureMode")?,
            capability_level: optional_text(&root, "capabilityLevel")?,
            created_at: optional_instant(&root, "createdAt")?,
            warnings,
            runs,
        })
    }

    pub fn runs(&self) -> &[BatteryReportRun] {
        &self.runs
    }

    pub fn page(&self, offset: usize, limit: usize) -> Result<Vec<BatteryReportRun>, String> {
        if limit > MAX_PAGE_SIZE || offset.checked_add(limit).is_none() {
            return Err("Battery page exceeds supported bounds".into());
        }
        Ok(self.runs.iter().skip(offset).take(limit).cloned().collect())
    }
}

fn required_text(value: &Value, key: &str) -> Result<String, String> {
    value
        .get(key)
        .and_then(Value::as_str)
        .filter(|value| !value.is_empty() && value.len() <= 4096)
        .map(str::to_owned)
        .ok_or_else(|| format!("Invalid battery {key}"))
}
fn integer(value: &Value, key: &str) -> Result<Option<u64>, String> {
    match value.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(value) => value
            .as_u64()
            .map(Some)
            .ok_or_else(|| format!("Invalid unsigned battery {key}")),
    }
}
fn unsigned32(value: &Value, key: &str) -> Result<Option<u32>, String> {
    integer(value, key)?
        .map(u32::try_from)
        .transpose()
        .map_err(|_| format!("Battery {key} out of range"))
}
fn optional_text(value: &Value, key: &str) -> Result<Option<String>, String> {
    match value.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(_) => required_text(value, key).map(Some),
    }
}
fn optional_bool(value: &Value, key: &str) -> Result<Option<bool>, String> {
    match value.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(value) => value
            .as_bool()
            .map(Some)
            .ok_or_else(|| format!("Invalid battery boolean {key}")),
    }
}
fn finite_number(value: &Value, key: &str) -> Result<Option<f64>, String> {
    match value.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(value) => value
            .as_f64()
            .filter(|value| value.is_finite())
            .map(Some)
            .ok_or_else(|| format!("Invalid battery {key}")),
    }
}
fn number(value: &Value, key: &str) -> Result<Option<f64>, String> {
    let number = finite_number(value, key)?;
    if number.is_some_and(|value| value < 0.) {
        return Err(format!("Invalid battery {key}"));
    }
    Ok(number)
}

fn performance_windows(row: &Value) -> Result<Option<Vec<ImportedPerformanceWindow>>, String> {
    if row.get("performanceWindows").is_none() {
        return Ok(None);
    }
    // The shared JSON boundary already bounds total nodes and collection sizes.
    optional_array(row, "performanceWindows")?
        .iter()
        .map(|window| {
            if !window.is_object() {
                return Err("Invalid battery performance window".into());
            }
            Ok(ImportedPerformanceWindow {
                started_at: optional_instant(window, "startedAt")?,
                ended_at: optional_instant(window, "endedAt")?,
                duration_ms: integer(window, "durationMs")?,
                peak_temperature_celsius: finite_number(window, "peakTemperatureCelsius")?,
                max_thermal_status: unsigned32(window, "maxThermalStatus")?,
                throttled: optional_bool(window, "throttled")?,
            })
        })
        .collect::<Result<Vec<_>, _>>()
        .map(Some)
}

fn optional_instant(value: &Value, key: &str) -> Result<Option<String>, String> {
    let text = optional_text(value, key)?;
    if text
        .as_deref()
        .is_some_and(|value| !valid_exported_instant(value))
    {
        return Err(format!("Invalid battery timestamp {key}"));
    }
    Ok(text)
}

/// The exporter uses java.time.Instant: UTC with optional nanosecond precision.
/// Validate calendar and clock fields without inventing another clock or adding
/// a date-time dependency. Keep the source text verbatim for display.
fn valid_exported_instant(text: &str) -> bool {
    let Some((date, time)) = text
        .strip_suffix('Z')
        .and_then(|value| value.split_once('T'))
    else {
        return false;
    };
    let Some((year_month, day)) = date.rsplit_once('-') else {
        return false;
    };
    let Some((year, month)) = year_month.rsplit_once('-') else {
        return false;
    };
    let year_digits = year.strip_prefix(['+', '-']).unwrap_or(year);
    if year_digits.len() < 4
        || year_digits.len() > 10
        || !year_digits.bytes().all(|b| b.is_ascii_digit())
    {
        return false;
    }
    let Ok(year) = year.parse::<i64>() else {
        return false;
    };
    let digits = |value: &str, length: usize| {
        (value.len() == length && value.bytes().all(|b| b.is_ascii_digit()))
            .then(|| value.parse::<u32>().ok())
            .flatten()
    };
    let Some(month) = digits(month, 2) else {
        return false;
    };
    let Some(day) = digits(day, 2) else {
        return false;
    };
    let leap = year % 4 == 0 && (year % 100 != 0 || year % 400 == 0);
    let max_day = match month {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        2 => {
            if leap {
                29
            } else {
                28
            }
        }
        _ => return false,
    };
    if day == 0 || day > max_day {
        return false;
    }
    let (clock, fraction) = time
        .split_once('.')
        .map_or((time, None), |(clock, fraction)| (clock, Some(fraction)));
    if fraction.is_some_and(|value| {
        value.is_empty() || value.len() > 9 || !value.bytes().all(|b| b.is_ascii_digit())
    }) {
        return false;
    }
    let mut fields = clock.split(':');
    let hour = fields.next().and_then(|value| digits(value, 2));
    let minute = fields.next().and_then(|value| digits(value, 2));
    let second = fields.next().and_then(|value| digits(value, 2));
    hour.is_some_and(|value| value < 24)
        && minute.is_some_and(|value| value < 60)
        && second.is_some_and(|value| value < 60)
        && fields.next().is_none()
}
fn optional_array<'a>(value: &'a Value, key: &str) -> Result<&'a [Value], String> {
    match value.get(key) {
        None => Ok(&[]),
        Some(Value::Array(values)) => Ok(values),
        _ => Err(format!("Invalid battery array {key}")),
    }
}
fn strings(value: &Value, key: &str) -> Result<Vec<String>, String> {
    optional_array(value, key)?
        .iter()
        .map(|value| {
            value
                .as_str()
                .map(str::to_owned)
                .ok_or_else(|| format!("Invalid battery text array {key}"))
        })
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn report(root_fields: &str, run_fields: &str) -> Result<BatteryReport, String> {
        BatteryReport::parse_json(
            format!(
                r#"{{"schemaVersion":1,"sessionId":"imported",{root_fields}"runs":[{{"runId":"run-1","iteration":1,"durationMs":1000{run_fields}}}]}}"#
            )
            .as_bytes(),
        )
    }

    #[test]
    fn legacy_report_does_not_invent_optional_metadata_or_empty_counter_evidence() {
        let report = report("", "").unwrap();
        assert!(report.device_local_id.is_none());
        assert!(report.capture_mode.is_none());
        assert!(report.capability_level.is_none());
        assert!(report.created_at.is_none());
        let run = &report.runs()[0];
        assert!(run.timer_arrays_present.is_empty());
        assert!(run.timer_confidence.is_empty());
        assert!(!run.has_timer_evidence(ResourceKind::Wakelock));
        assert!(run.performance_windows.is_none());
        assert!(run.throttled_samples.is_none());
        assert!(
            report
                .warnings
                .iter()
                .any(|warning| warning.contains("measured-zero"))
        );
    }

    #[test]
    fn compose_exporter_v1_field_names_and_nullable_thermal_values_are_preserved() {
        // Match BatteryJsonExporter.document/run/timers: config.mode is exported
        // under captureMode, not mode, and optional thermal fields are JSON null.
        let report = BatteryReport::parse_json(
            br#"{
            "schemaVersion":1,"sessionId":"source-session",
            "deviceLocalId":"battery-local-device","packageName":"example.app",
            "uid":10001,"attributionScope":"UID","captureMode":"REPEATED",
            "capabilityLevel":"RESOURCE_FULL","createdAt":"2026-11-07T01:02:03Z",
            "runs":[{
                "runId":"source-run","iteration":1,"durationMs":1000,"networkBytes":0,
                "peakTemperatureCelsius":null,"maxThermalStatus":null,"throttledSamples":0,
                "performanceWindows":[{
                    "startedAt":"2026-11-07T01:02:03Z","endedAt":"2026-11-07T01:02:04Z",
                    "durationMs":1000,"peakTemperatureCelsius":null,
                    "maxThermalStatus":null,"throttled":false
                }],
                "wakelocks":[{"name":"lock","durationMs":4,"count":2,"confidence":"EXACT"}],
                "alarms":[],"jobs":[],"sensors":[],
                "energy":[{"component":"cpu","energyMah":0.25,"energyUws":100,
                    "source":"SYSTEM_MODEL","scope":"UID","confidence":"MODELED"}],
                "warnings":[]
            }],"warnings":[]
        }"#,
        )
        .unwrap();
        assert_eq!(report.session_id, "source-session");
        assert_eq!(
            report.device_local_id.as_deref(),
            Some("battery-local-device")
        );
        assert_eq!(report.capture_mode.as_deref(), Some("REPEATED"));
        assert_eq!(report.capability_level.as_deref(), Some("RESOURCE_FULL"));
        assert_eq!(report.created_at.as_deref(), Some("2026-11-07T01:02:03Z"));
        let run = &report.runs()[0];
        assert_eq!(run.id, "source-run");
        assert_eq!(run.network_bytes, Some(0));
        assert!(run.peak_temperature_celsius.is_none());
        assert!(run.max_thermal_status.is_none());
        assert_eq!(run.throttled_samples, Some(0));
        assert_eq!(run.timer_arrays_present.len(), 4);
        assert_eq!(run.timer_confidence, [Some("EXACT".into())]);
        let window = &run.performance_windows.as_ref().unwrap()[0];
        assert_eq!(window.duration_ms, Some(1000));
        assert!(window.peak_temperature_celsius.is_none());
        assert!(window.max_thermal_status.is_none());
        assert_eq!(window.throttled, Some(false));
        let energy = &run.energy[0];
        assert_eq!(energy.source, "SYSTEM_MODEL");
        assert_eq!(energy.scope, "UID");
        assert_eq!(energy.confidence, "MODELED");
        assert_eq!(energy.energy_mah, Some(0.25));
        assert_eq!(energy.energy_uws, Some(100));
    }

    #[test]
    fn config_mode_is_not_an_exported_capture_mode_alias() {
        let legacy = report(r#""mode":"TIMED","#, "").unwrap();
        assert!(legacy.capture_mode.is_none());
        let exported = report(r#""mode":"TIMED","captureMode":"ONLINE","#, "").unwrap();
        assert_eq!(exported.capture_mode.as_deref(), Some("ONLINE"));
    }

    #[test]
    fn explicit_empty_arrays_are_distinct_from_omitted_arrays() {
        let report = report(
            "",
            r#", "wakelocks":[],"jobs":[],"performanceWindows":[],"throttledSamples":0"#,
        )
        .unwrap();
        let run = &report.runs()[0];
        assert!(run.has_timer_evidence(ResourceKind::Wakelock));
        assert!(run.has_timer_evidence(ResourceKind::Job));
        assert!(!run.has_timer_evidence(ResourceKind::Alarm));
        assert!(!run.has_timer_evidence(ResourceKind::Sensor));
        assert!(run.timers.is_empty());
        assert!(run.performance_windows.as_ref().unwrap().is_empty());
        assert_eq!(run.throttled_samples, Some(0));
    }

    #[test]
    fn source_metadata_and_thermal_windows_are_preserved_without_clock_conversion() {
        let report = report(
            r#""deviceLocalId":"local-device","captureMode":"ONLINE","capabilityLevel":"PARTIAL","createdAt":"2026-11-07T01:02:03.123Z","uid":10001,"attributionScope":"SHARED_UID","packageName":"example.app","#,
            r#", "peakTemperatureCelsius":-3.5,"maxThermalStatus":0,"throttledSamples":2,"performanceWindows":[{"startedAt":"2026-11-07T01:02:03.123Z","endedAt":"2026-11-07T01:02:04.123Z","durationMs":1000,"peakTemperatureCelsius":-2.5,"maxThermalStatus":3,"throttled":false}]"#,
        ).unwrap();
        assert_eq!(report.device_local_id.as_deref(), Some("local-device"));
        assert_eq!(report.capture_mode.as_deref(), Some("ONLINE"));
        assert_eq!(report.capability_level.as_deref(), Some("PARTIAL"));
        assert_eq!(
            report.created_at.as_deref(),
            Some("2026-11-07T01:02:03.123Z")
        );
        assert_eq!(report.attribution_scope.as_deref(), Some("SHARED_UID"));
        let run = &report.runs()[0];
        assert_eq!(run.peak_temperature_celsius, Some(-3.5));
        assert_eq!(run.max_thermal_status, Some(0));
        assert_eq!(run.throttled_samples, Some(2));
        let window = &run.performance_windows.as_ref().unwrap()[0];
        assert_eq!(
            window.started_at.as_deref(),
            Some("2026-11-07T01:02:03.123Z")
        );
        assert_eq!(window.ended_at.as_deref(), Some("2026-11-07T01:02:04.123Z"));
        assert_eq!(window.duration_ms, Some(1000));
        assert_eq!(window.peak_temperature_celsius, Some(-2.5));
        assert_eq!(window.max_thermal_status, Some(3));
        assert_eq!(window.throttled, Some(false));
    }

    #[test]
    fn confidences_follow_timer_order_without_changing_shared_timer_type() {
        let report = report("", r#", "wakelocks":[{"name":"lock","durationMs":2,"count":1,"confidence":"INFERRED"}],"alarms":[{"name":"alarm","durationMs":0,"count":2}],"sensors":[{"name":"sensor","durationMs":3,"count":1,"confidence":"EXACT"}]"#).unwrap();
        let run = &report.runs()[0];
        assert_eq!(run.timers.len(), run.timer_confidence.len());
        assert_eq!(run.timers[0].kind, ResourceKind::Wakelock);
        assert_eq!(run.timers[1].kind, ResourceKind::Alarm);
        assert_eq!(run.timers[2].kind, ResourceKind::Sensor);
        assert_eq!(
            run.timer_confidence,
            [Some("INFERRED".into()), None, Some("EXACT".into())]
        );
    }

    #[test]
    fn unknown_window_fields_remain_missing_instead_of_becoming_zero() {
        let report = report("", r#", "performanceWindows":[{}]"#).unwrap();
        let window = &report.runs()[0].performance_windows.as_ref().unwrap()[0];
        assert!(window.started_at.is_none());
        assert!(window.ended_at.is_none());
        assert!(window.duration_ms.is_none());
        assert!(window.peak_temperature_celsius.is_none());
        assert!(window.max_thermal_status.is_none());
        assert!(window.throttled.is_none());
    }

    #[test]
    fn malformed_present_metadata_is_rejected() {
        for fields in [
            r#""deviceLocalId":12,"#,
            r#""captureMode":true,"#,
            r#""capabilityLevel":"","#,
            r#""createdAt":"yesterday","#,
            r#""createdAt":"2026-02-29T00:00:00Z","#,
            r#""packageName":{},"#,
            r#""attributionScope":[],"#,
        ] {
            assert!(report(fields, "").is_err(), "{fields}");
        }
    }

    #[test]
    fn malformed_present_run_evidence_is_rejected() {
        for fields in [
            r#", "wakelocks":null"#,
            r#", "performanceWindows":null"#,
            r#", "performanceWindows":{}"#,
            r#", "performanceWindows":[false]"#,
            r#", "performanceWindows":[{"durationMs":-1}]"#,
            r#", "performanceWindows":[{"durationMs":1.5}]"#,
            r#", "performanceWindows":[{"maxThermalStatus":4294967296}]"#,
            r#", "performanceWindows":[{"throttled":"false"}]"#,
            r#", "performanceWindows":[{"startedAt":"2026-11-07T25:00:00Z"}]"#,
            r#", "performanceWindows":[{"peakTemperatureCelsius":"cold"}]"#,
            r#", "throttledSamples":-1"#,
            r#", "throttledSamples":18446744073709551616"#,
            r#", "maxThermalStatus":4294967296"#,
            r#", "peakTemperatureCelsius":"cold""#,
            r#", "wakelocks":[{"name":"lock","durationMs":0,"count":0,"confidence":1}]"#,
            r#", "energy":[{"component":"CPU","energyMah":-1,"source":"MODEL","scope":"UID","confidence":"MODELED"}]"#,
        ] {
            assert!(report("", fields).is_err(), "{fields}");
        }
    }

    #[test]
    fn timestamps_validate_calendar_precision_and_clock_fields() {
        for valid in [
            "2024-02-29T23:59:59Z",
            "2026-11-07T01:02:03.123456789Z",
            "+10000-01-01T00:00:00Z",
            "-0001-01-01T00:00:00Z",
        ] {
            assert!(valid_exported_instant(valid), "{valid}");
        }
        for invalid in [
            "2026-02-29T00:00:00Z",
            "2024-04-31T00:00:00Z",
            "2026-11-07T00:60:00Z",
            "2026-11-07T00:00:60Z",
            "2026-11-07T00:00:00.Z",
            "2026-11-07T00:00:00.1234567890Z",
            "2026-11-07T00:00:00",
            "2026-11-07T00:00:00Zjunk",
        ] {
            assert!(!valid_exported_instant(invalid), "{invalid}");
        }
    }
}

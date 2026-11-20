//! Bounded reader for the existing Startup Analyzer schema-v1 JSON report.
//! Import preserves producer fields for desktop re-export, but does not grant
//! authority to reopen paths, trust raw device identity, or claim live capture.
use crate::{
    CompilationEvidence, Confidence, EnvironmentEvidence, MetricEvidence, MetricSource, Milestone,
    RunContext, RunEvidence, StartupMode, StartupRun, StartupSession,
};
use aps_json::{Limits, Value};
use std::{collections::BTreeMap, path::Path};

/// Internal key used by the desktop exporter to replay structured imports.
pub const ORIGINAL_RUN_FIELD: &str = "__startup_json_run";

fn limits() -> Limits {
    Limits {
        max_bytes: crate::MAX_INPUT_BYTES,
        ..Limits::default()
    }
}

/// An input dispatch hint, not proof of a valid report. Check the version
/// field and the distinctive first-row keys to distinguish other APS formats
/// that also use a versioned `runs` array.
pub fn looks_like_json(root: &Value) -> bool {
    root.get("schemaVersion").is_some()
        && root
            .get("runs")
            .and_then(Value::as_array)
            .and_then(|runs| runs.first())
            .is_some_and(|run| {
                ["requestedType", "observedType", "rawEvidence"]
                    .iter()
                    .all(|key| run.get(key).is_some())
            })
}

pub fn parse_json(bytes: &[u8]) -> Result<StartupSession, String> {
    decode(aps_json::parse_slice(bytes, limits()).map_err(|e| e.to_string())?)
}

pub fn open_json(path: &Path) -> Result<StartupSession, String> {
    decode(aps_json::read(path, limits()).map_err(|e| e.to_string())?)
}

fn object(value: &Value) -> Result<(), String> {
    value
        .as_object()
        .map(|_| ())
        .ok_or_else(|| "Startup JSON object required".into())
}
fn string(value: &Value, key: &str) -> Result<Option<String>, String> {
    match value.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(Value::String(v)) => Ok(Some(v.clone())),
        _ => Err(format!("Startup JSON {key} must be a string")),
    }
}
fn required(value: &Value, key: &str) -> Result<String, String> {
    string(value, key)?.ok_or_else(|| format!("Startup JSON is missing {key}"))
}
fn number(value: &Value, key: &str) -> Result<Option<u64>, String> {
    match value.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(v) => v
            .as_u64()
            .filter(|v| *v <= i64::MAX as u64)
            .map(Some)
            .ok_or_else(|| {
                format!("Startup JSON {key} must be a nonnegative signed-64-bit integer")
            }),
    }
}
fn boolean(value: &Value, key: &str) -> Result<Option<bool>, String> {
    match value.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(Value::Bool(v)) => Ok(Some(*v)),
        _ => Err(format!("Startup JSON {key} must be boolean")),
    }
}
fn strings(value: &Value, key: &str) -> Result<Vec<String>, String> {
    match value.get(key) {
        None => Ok(Vec::new()),
        Some(Value::Array(values)) => values
            .iter()
            .map(|v| {
                v.as_str()
                    .map(str::to_owned)
                    .ok_or_else(|| format!("Startup JSON {key} must contain strings"))
            })
            .collect(),
        _ => Err(format!("Startup JSON {key} must be an array")),
    }
}
fn optional_object<'a>(value: &'a Value, key: &str) -> Result<Option<&'a Value>, String> {
    match value.get(key) {
        None | Some(Value::Null) => Ok(None),
        Some(v) => {
            object(v)?;
            Ok(Some(v))
        }
    }
}
fn confidence(value: &str) -> Result<Confidence, String> {
    match value {
        "EXACT" => Ok(Confidence::Exact),
        "ESTIMATED" => Ok(Confidence::Estimated),
        "INFERRED" => Ok(Confidence::Inferred),
        "UNAVAILABLE" => Ok(Confidence::Unavailable),
        _ => Err("Unknown Startup evidence confidence".into()),
    }
}
fn source(value: String) -> Result<String, String> {
    if ["AM_START", "AGENT", "EVENT_LOG", "PERFETTO"].contains(&value.as_str()) {
        Ok(value)
    } else {
        Err("Unknown Startup evidence source".into())
    }
}
fn mode(value: &Value, key: &str) -> Result<StartupMode, String> {
    let name = required(value, key)?;
    if !["COLD", "WARM", "HOT", "UNKNOWN"].contains(&name.as_str()) {
        return Err("Unknown startup mode".into());
    }
    Ok(StartupMode::parse(&name))
}

fn metric(
    value: Option<&Value>,
    duration: Option<u64>,
    missing: &str,
) -> Result<MetricEvidence, String> {
    let Some(value) = value else {
        return Ok(MetricEvidence {
            source: duration.map(|_| "EVENT_LOG".into()),
            confidence: if duration.is_some() {
                Confidence::Exact
            } else {
                Confidence::Unavailable
            },
            unavailable_reason: duration.is_none().then(|| missing.to_owned()),
        });
    };
    object(value)?;
    Ok(MetricEvidence {
        source: string(value, "source")?.map(source).transpose()?,
        confidence: confidence(
            &string(value, "confidence")?.unwrap_or_else(|| "UNAVAILABLE".into()),
        )?,
        unavailable_reason: string(value, "unavailableReason")?,
    })
}

fn decode(root: Value) -> Result<StartupSession, String> {
    object(&root)?;
    if number(&root, "schemaVersion")? != Some(1) {
        return Err("Unsupported Startup Profiler schema version".into());
    }
    let runs = root["runs"]
        .as_array()
        .ok_or("Startup JSON requires a runs array")?;
    if runs.is_empty() || runs.len() > crate::MAX_RUNS {
        return Err("Startup JSON requires 1..100000 runs".into());
    }
    let mut parsed = Vec::with_capacity(runs.len());
    let mut evidence = BTreeMap::new();
    for value in runs {
        object(value)?;
        let iteration = number(value, "iteration")?
            .and_then(|v| u32::try_from(v).ok())
            .filter(|v| *v > 0 && *v <= i32::MAX as u32)
            .ok_or("Startup iteration must be a positive 32-bit integer")?;
        let id = required(value, "runId")?;
        let raw =
            optional_object(value, "rawEvidence")?.ok_or("Startup JSON requires rawEvidence")?;
        required(raw, "amStartOutput")?;
        string(raw, "eventLogOutput")?;
        string(raw, "compilationOutput")?;
        let ttid = number(value, "displayedTimeMs")?;
        let ttfd = number(value, "fullyDrawnTimeMs")?;
        let metrics = optional_object(value, "metricEvidence")?;
        let ttid_evidence = metric(
            metrics.and_then(|m| m.get("ttid")).filter(|v| !v.is_null()),
            ttid,
            "No displayed event was observed.",
        )?;
        let ttfd_evidence = metric(
            metrics.and_then(|m| m.get("ttfd")).filter(|v| !v.is_null()),
            ttfd,
            "The app did not call reportFullyDrawn() during this run.",
        )?;
        let context = optional_object(value, "context")?
            .map(|c| -> Result<_, String> {
                let package_name = required(c, "packageName")?;
                let component_name = required(c, "componentName")?;
                let local = string(c, "deviceLocalId")?;
                // Legacy raw serials cannot be promoted into salted local identity.
                if local.is_none() {
                    required(c, "deviceSerial")?;
                    return Ok(None);
                }
                let local = local.unwrap();
                if local.len() != 64
                    || !local
                        .bytes()
                        .all(|b| b.is_ascii_hexdigit() && !b.is_ascii_uppercase())
                {
                    return Err("Startup deviceLocalId must be a lowercase SHA-256 value".into());
                }
                Ok(Some(RunContext {
                    device_local_id: local,
                    package_name,
                    component_name,
                }))
            })
            .transpose()?
            .flatten();
        let compilation = optional_object(value, "compilationEvidence")?
            .map(|c| -> Result<_, String> {
                let requested_mode = required(c, "requestedMode")?;
                let profile_source =
                    string(c, "profileSource")?.unwrap_or_else(|| "UNVERIFIED".into());
                if !["CURRENT", "RESET", "VERIFY", "SPEED_PROFILE", "SPEED"]
                    .contains(&requested_mode.as_str())
                    || ![
                        "UNVERIFIED",
                        "BASELINE_PROFILE_PLUGIN",
                        "MACROBENCHMARK",
                        "BUILD_VARIANT",
                    ]
                    .contains(&profile_source.as_str())
                {
                    return Err("Unknown Startup compilation mode or Profile source".into());
                }
                Ok(CompilationEvidence {
                    requested_mode,
                    profile_source,
                    compiler_filter_after: string(c, "compilerFilterAfter")?,
                    profile_state_after: string(c, "profileStateAfter")?,
                    verified: boolean(c, "verified")?.unwrap_or(false),
                    profile_source_declared: boolean(c, "profileSourceDeclared")?.unwrap_or(false),
                })
            })
            .transpose()?;
        let environment = optional_object(value, "environmentEvidence")?
            .map(|e| -> Result<_, String> {
                let small = |key| -> Result<_, String> {
                    number(e, key)?
                        .map(|v| {
                            u32::try_from(v).map_err(|_| format!("Startup {key} exceeds bounds"))
                        })
                        .transpose()
                };
                let captured_at = string(e, "capturedAt")?;
                if captured_at.as_ref().is_some_and(|v| !valid_instant(v)) {
                    return Err("Startup capturedAt must be a UTC ISO-8601 instant".into());
                }
                Ok(EnvironmentEvidence {
                    device_model: string(e, "deviceModel")?,
                    api_level: small("apiLevel")?,
                    emulator: boolean(e, "emulator")?,
                    battery_percent: small("batteryPercent")?,
                    charging: boolean(e, "charging")?,
                    thermal_status: small("thermalStatus")?,
                    captured_at,
                    failures: strings(e, "failures")?,
                })
            })
            .transpose()?;
        let milestones = match value.get("milestones") {
            None => Vec::new(),
            Some(Value::Array(values)) => values
                .iter()
                .map(|m| -> Result<_, String> {
                    object(m)?;
                    let kind = required(m, "kind")?;
                    if ![
                        "PROCESS_START",
                        "INITIALIZER_ENTER",
                        "AGENT_READY",
                        "ACTIVITY_PRE_CREATE",
                        "ACTIVITY_CREATED",
                        "ACTIVITY_STARTED",
                        "ACTIVITY_RESUMED",
                        "FIRST_FRAME",
                        "FIRST_DRAW_CALLBACK",
                        "FULLY_DRAWN",
                    ]
                    .contains(&kind.as_str())
                    {
                        return Err("Unknown Startup milestone kind".into());
                    }
                    Ok(Milestone {
                        kind,
                        elapsed_realtime_ns: number(m, "elapsedRealtimeNs")?,
                        source: source(required(m, "source")?)?,
                        confidence: confidence(&required(m, "confidence")?)?,
                    })
                })
                .collect::<Result<Vec<_>, _>>()?,
            _ => return Err("Startup milestones must be an array".into()),
        };
        let metadata = RunEvidence {
            context,
            compilation,
            environment,
            ttid: ttid_evidence,
            ttfd: ttfd_evidence,
            milestones,
        };
        let duration = |milliseconds, e: &MetricEvidence| crate::DurationMetric {
            milliseconds,
            source: e.source.as_ref().map(|_| MetricSource::ImportedJson),
            unavailable_reason: e.unavailable_reason.clone(),
        };
        let mut fields = BTreeMap::new();
        fields.insert(ORIGINAL_RUN_FIELD.into(), value.to_string());
        fields.insert(
            "agentAvailable".into(),
            boolean(value, "agentAvailable")?
                .unwrap_or(false)
                .to_string(),
        );
        fields.insert(
            "diagnostics".into(),
            strings(value, "diagnostics")?.join("; "),
        );
        for (section, pairs) in [
            (
                "compilationEvidence",
                &[
                    ("compilerFilterAfter", "compilerFilter"),
                    ("verified", "compilationVerified"),
                    ("profileSource", "profileSource"),
                    ("profileSourceDeclared", "profileSourceDeclared"),
                    ("baselineProfileArtifact", "baselineProfileArtifact"),
                ][..],
            ),
            (
                "environmentEvidence",
                &[
                    ("deviceModel", "deviceModel"),
                    ("apiLevel", "apiLevel"),
                    ("emulator", "emulator"),
                    ("batteryPercent", "batteryPercent"),
                    ("charging", "charging"),
                    ("thermalStatus", "thermalStatus"),
                ][..],
            ),
            (
                "traceEvidence",
                &[
                    ("file", "traceFile"),
                    ("captured", "traceCaptured"),
                    ("truncated", "traceTruncated"),
                    ("failureReason", "traceFailure"),
                ][..],
            ),
        ] {
            if let Some(section) = optional_object(value, section)? {
                for (key, name) in pairs {
                    if let Some(v) = section.get(*key).filter(|v| !v.is_null()) {
                        fields.insert(
                            (*name).into(),
                            v.as_str()
                                .map(str::to_owned)
                                .unwrap_or_else(|| v.to_string()),
                        );
                    }
                }
            }
        }
        parsed.push(StartupRun {
            origin: crate::StartupEvidenceOrigin::ImportedJson,
            iteration,
            id: id.clone(),
            package: metadata.context.as_ref().map(|c| c.package_name.clone()),
            requested_mode: mode(value, "requestedType")?,
            observed_mode: mode(value, "observedType")?,
            status: None,
            activity: metadata.context.as_ref().map(|c| c.component_name.clone()),
            this_time_ms: number(value, "thisTimeMs")?,
            total_time_ms: number(value, "totalTimeMs")?,
            wait_time_ms: number(value, "waitTimeMs")?,
            complete: false,
            ttid: duration(ttid, &metadata.ttid),
            ttfd: duration(ttfd, &metadata.ttfd),
            warnings: strings(value, "warnings")?,
            imported_fields: fields,
        });
        if evidence.insert(id, metadata).is_some() {
            return Err("Startup JSON run IDs must be unique".into());
        }
    }
    StartupSession::from_report(parsed, evidence, strings(&root, "warnings")?)
}

fn valid_instant(value: &str) -> bool {
    let bytes = value.as_bytes();
    if bytes.len() < 20
        || !value.is_ascii()
        || bytes[4] != b'-'
        || bytes[7] != b'-'
        || bytes[10] != b'T'
        || bytes[13] != b':'
        || bytes[16] != b':'
        || bytes.last() != Some(&b'Z')
    {
        return false;
    }
    let part = |start, end| value[start..end].parse::<u32>().ok();
    let (Some(year), Some(month), Some(day), Some(hour), Some(minute), Some(second)) = (
        part(0, 4),
        part(5, 7),
        part(8, 10),
        part(11, 13),
        part(14, 16),
        part(17, 19),
    ) else {
        return false;
    };
    let days = match month {
        4 | 6 | 9 | 11 => 30,
        2 => {
            if year % 4 == 0 && (year % 100 != 0 || year % 400 == 0) {
                29
            } else {
                28
            }
        }
        1..=12 => 31,
        _ => return false,
    };
    day > 0
        && day <= days
        && hour < 24
        && minute < 60
        && second < 60
        && (bytes.len() == 20
            || (bytes[19] == b'.'
                && (22..=30).contains(&bytes.len())
                && bytes[20..bytes.len() - 1].iter().all(u8::is_ascii_digit)))
}

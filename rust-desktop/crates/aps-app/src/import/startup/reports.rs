//! Compose schema-v1 report boundary. Reuse the application's strict JSON parser
//! without adding JSON dependencies to the startup analysis crate.
use super::*;
use aps_json::{Limits, Value};
use aps_startup::{
    CompilationEvidence, Confidence, EnvironmentEvidence, MetricEvidence, Milestone, RunContext,
    RunEvidence,
};
use std::collections::BTreeMap;

const ORIGINAL_RUN: &str = "__startup_json_run";

fn limits() -> Limits {
    Limits {
        max_bytes: aps_startup::MAX_INPUT_BYTES,
        ..Limits::default()
    }
}

pub(in crate::import) fn open_session(path: &Path) -> Result<StartupSession, String> {
    if path
        .extension()
        .is_some_and(|e| e.eq_ignore_ascii_case("json"))
    {
        decode(aps_json::read(path, limits()).map_err(|e| e.to_string())?)
    } else {
        StartupSession::open_csv(path, aps_startup::MAX_INPUT_BYTES)
    }
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

pub(super) fn decode(root: Value) -> Result<StartupSession, String> {
    object(&root)?;
    if number(&root, "schemaVersion")? != Some(1) {
        return Err("Unsupported Startup Profiler schema version".into());
    }
    let runs = root["runs"]
        .as_array()
        .ok_or("Startup JSON requires a runs array")?;
    if runs.is_empty() || runs.len() > aps_startup::MAX_RUNS {
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
        let duration = |milliseconds, e: &MetricEvidence| aps_startup::DurationMetric {
            milliseconds,
            source: e.source.as_ref().map(|_| MetricSource::ImportedJson),
            unavailable_reason: e.unavailable_reason.clone(),
        };
        let mut fields = BTreeMap::new();
        fields.insert(ORIGINAL_RUN.into(), value.to_string());
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
            origin: aps_startup::StartupEvidenceOrigin::ImportedJson,
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

/// Full-cohort schema-v1 export, independent of the visible page and filter.
pub(super) fn encode_json(session: &StartupSession) -> Result<Vec<u8>, UiText> {
    let runs = session.runs().iter().map(|run| -> Result<_, UiText> {
        if let Some(original) = run.imported_fields.get(ORIGINAL_RUN) {
            let mut value = aps_json::parse_slice(original.as_bytes(), limits()).map_err(|e| UiText::from(e.to_string()))?;
            // Never emit a legacy raw serial as a pseudonymous device identity.
            if value.get("context").is_some() && session.evidence(&run.id).is_none_or(|e| e.context.is_none()) {
                value.as_object_mut().unwrap().remove("context");
            }
            return Ok(value);
        }
        // Legacy CSV/capture exports retain measurements, not fabricated cohort evidence.
        let metric = |m: &aps_startup::DurationMetric| serde_json::json!({ "source": if m.source == Some(MetricSource::EventLog) { Some("EVENT_LOG") } else { None },
            "confidence": if m.source == Some(MetricSource::EventLog) { "EXACT" } else { "UNAVAILABLE" }, "unavailableReason": m.unavailable_reason });
        Ok(serde_json::json!({"iteration": run.iteration, "runId": run.id, "requestedType": mode_name(run.requested_mode), "observedType": mode_name(run.observed_mode),
            "totalTimeMs": run.total_time_ms, "thisTimeMs": run.this_time_ms, "waitTimeMs": run.wait_time_ms,
            "displayedTimeMs": run.ttid.milliseconds, "fullyDrawnTimeMs": run.ttfd.milliseconds,
            "agentAvailable": false, "warnings": run.warnings, "diagnostics": [], "milestones": [],
            "metricEvidence": {"ttid": metric(&run.ttid), "ttfd": metric(&run.ttfd)}, "rawEvidence": {"amStartOutput": ""}}))
    }).collect::<Result<Vec<_>, _>>()?;
    aps_json::to_vec(
        &serde_json::json!({"schemaVersion": 1, "warnings": session.warnings(), "runs": runs}),
        limits(),
    )
    .map_err(|e| UiText::from(e.to_string()))
}

pub(super) fn encode_csv(session: &StartupSession) -> Result<Vec<u8>, UiText> {
    let mut output = String::from(
        "iteration,runId,requestedType,observedType,totalTimeMs,thisTimeMs,waitTimeMs,displayedTimeMs,fullyDrawnTimeMs,agentAvailable,warnings,ttidSource,ttidUnavailableReason,ttfdSource,ttfdUnavailableReason,compilerFilter,compilationVerified,profileSource,profileSourceDeclared,baselineProfileArtifact,deviceModel,apiLevel,emulator,batteryPercent,charging,thermalStatus,traceFile,traceCaptured,traceTruncated,traceFailure,diagnostics\n",
    );
    let optional = |v: Option<u64>| v.map(|v| v.to_string()).unwrap_or_default();
    for run in session.runs() {
        let metric = |which: bool| {
            session
                .evidence(&run.id)
                .map(|e| if which { &e.ttid } else { &e.ttfd })
        };
        let mut row = vec![
            run.iteration.to_string(),
            run.id.clone(),
            mode_name(run.requested_mode).into(),
            mode_name(run.observed_mode).into(),
            optional(run.total_time_ms),
            optional(run.this_time_ms),
            optional(run.wait_time_ms),
            optional(run.ttid.milliseconds),
            optional(run.ttfd.milliseconds),
            run.imported_fields
                .get("agentAvailable")
                .cloned()
                .unwrap_or_else(|| "false".into()),
            run.warnings.join("; "),
            metric(true)
                .and_then(|e| e.source.clone())
                .unwrap_or_default(),
            run.ttid.unavailable_reason.clone().unwrap_or_default(),
            metric(false)
                .and_then(|e| e.source.clone())
                .unwrap_or_default(),
            run.ttfd.unavailable_reason.clone().unwrap_or_default(),
        ];
        row.extend(
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
                "diagnostics",
            ]
            .map(|k| run.imported_fields.get(k).cloned().unwrap_or_default()),
        );
        output.push_str(
            &row.iter()
                .map(|v| format!("\"{}\"", v.replace('"', "\"\"")))
                .collect::<Vec<_>>()
                .join(","),
        );
        output.push('\n');
        if output.len() as u64 > aps_startup::MAX_INPUT_BYTES {
            return Err("Startup CSV export exceeds byte budget".into());
        }
    }
    Ok(output.into_bytes())
}

fn mode_name(mode: StartupMode) -> &'static str {
    match mode {
        StartupMode::Cold => "COLD",
        StartupMode::Warm => "WARM",
        StartupMode::Hot => "HOT",
        StartupMode::Unknown => "UNKNOWN",
    }
}

#[derive(Default)]
pub(super) struct ReportHistory {
    pub current: Option<Arc<StartupSession>>,
    pub baseline: Option<Arc<StartupSession>>,
    pub comparison: Option<aps_startup::Comparison>,
    pub pending: bool,
}

pub(super) struct PreparedReport {
    pub session: Arc<StartupSession>,
    pub page: PageData,
    pub comparison: Option<aps_startup::Comparison>,
}

pub(super) fn prepare_report(
    path: &Path,
    baseline: Option<&StartupSession>,
    cancel: &CancellationToken,
) -> Result<PreparedReport, UiText> {
    if cancel.is_cancelled() {
        return Err(TextKey::ImportUiImportCancelled.into());
    }
    let session = Arc::new(super::open_session(path)?);
    let page = load_page(
        &session,
        &StartupQuery {
            limit: PAGE,
            ..Default::default()
        },
        cancel,
    )?;
    let comparison = baseline.map(|b| session.compare(b, 5.0)).transpose()?;
    if cancel.is_cancelled() {
        return Err(TextKey::ImportUiImportCancelled.into());
    }
    Ok(PreparedReport {
        session,
        page,
        comparison,
    })
}

impl ReportHistory {
    pub fn publish(&mut self, session: Arc<StartupSession>) {
        if self
            .current
            .as_ref()
            .is_some_and(|current| Arc::ptr_eq(current, &session))
        {
            return;
        }
        self.baseline = self.current.replace(session);
        self.comparison = None;
        self.pending = self.baseline.is_some();
    }
}

impl ImportWorkspace {
    pub(super) fn startup_baseline(&self, colors: &studio::StudioColors) -> AnyElement {
        use aps_startup::ComparisonStatus as Status;
        let language = self.language;
        let reports = &self.startup_capture.reports;
        let mut panel = studio::card(colors).child(studio::section_title(
            language.text(TextKey::StartupBaseline),
            colors,
        ));
        for (label, session) in [
            (TextKey::StartupComparisonCurrent, &reports.current),
            (TextKey::StartupComparisonPrevious, &reports.baseline),
        ] {
            let Some(session) = session else {
                continue;
            };
            let summary = session.summary();
            panel = panel.child(note(
                language.format(
                    TextKey::StartupComparisonCohort,
                    &[
                        ("cohort", &language.text(label)),
                        ("count", &summary.ttid.count),
                        ("missing", &summary.ttid.missing),
                        ("ttid", &metric_ms(summary.ttid.p50_ms)),
                        ("ttfd", &metric_ms(summary.ttfd.p50_ms)),
                        ("source", &cohort_origin(session.runs())),
                    ],
                ),
                colors.secondary_text,
            ));
            let identities: std::collections::BTreeSet<_> = session
                .runs()
                .iter()
                .filter_map(|r| session.evidence(&r.id).and_then(|e| e.context.as_ref()))
                .map(|c| {
                    format!(
                        "{} · {} · {}",
                        c.device_local_id, c.package_name, c.component_name
                    )
                })
                .collect();
            for identity in identities.iter().take(4) {
                panel = panel.child(note(bounded(identity, language), colors.secondary_text));
            }
        }
        let Some(comparison) = &reports.comparison else {
            return panel
                .child(note(
                    language.text(if reports.baseline.is_some() {
                        TextKey::StartupComparisonPending
                    } else {
                        TextKey::StartupBaselineUnavailable
                    }),
                    colors.secondary_text,
                ))
                .into_any_element();
        };
        let status = match comparison.status {
            Status::Incompatible => TextKey::StartupComparisonIncompatible,
            Status::Insufficient => TextKey::StartupComparisonInsufficient,
            Status::Regression => TextKey::StartupComparisonRegression,
            Status::Improvement => TextKey::StartupComparisonImprovement,
            Status::NoChange => TextKey::StartupComparisonNoChange,
            Status::Inconclusive => TextKey::StartupComparisonInconclusive,
        };
        let ms = |v: Option<f64>| {
            v.map(|v| format!("{v:+.3} ms"))
                .unwrap_or_else(|| "—".into())
        };
        let interval = comparison.confidence_interval_ms;
        panel = panel.child(note(
            language.format(
                TextKey::StartupComparisonResult,
                &[
                    ("status", &language.text(status)),
                    ("difference", &ms(comparison.median_difference_ms)),
                    ("low", &ms(interval.map(|i| i.0))),
                    ("high", &ms(interval.map(|i| i.1))),
                ],
            ),
            if comparison.status == Status::Regression {
                colors.error
            } else {
                colors.text
            },
        ));
        panel = panel.child(note(
            language.format(
                TextKey::StartupComparisonChange,
                &[
                    (
                        "percent",
                        &comparison
                            .percent_difference
                            .map(|v| format!("{v:+.1}%"))
                            .unwrap_or_else(|| "—".into()),
                    ),
                    ("threshold", &ms(comparison.practical_threshold_ms)),
                ],
            ),
            colors.secondary_text,
        ));
        for reason in &comparison.reasons {
            panel = panel.child(note(reason.clone(), colors.warning));
        }
        for phase in &comparison.phase_differences {
            panel = panel.child(note(
                format!(
                    "{}: {:+.3} ms · {:?}",
                    phase.name, phase.median_difference_ms, phase.confidence
                ),
                colors.secondary_text,
            ));
        }
        panel.into_any_element()
    }

    pub(in crate::import) fn pick_startup_report(&mut self, cx: &mut Context<Self>) {
        if self.busy {
            return;
        }
        self.startup_capture.cancel_pending();
        let generation = self.generation;
        self.busy = true;
        let picker = cx.prompt_for_paths(PathPromptOptions {
            files: true,
            directories: false,
            multiple: false,
            prompt: Some(self.language.text(TextKey::StartupImportReport).into()),
        });
        cx.spawn(async move |this, cx| {
            let result = picker.await;
            this.update(cx, |this, cx| {
                if this.generation != generation {
                    return;
                }
                this.busy = false;
                match result {
                    Ok(Ok(Some(paths))) if paths.len() == 1 => {
                        this.open_startup_report(paths[0].clone(), cx)
                    }
                    Ok(Err(_)) => this.error = Some(TextKey::ImportUiFilePickerFailed.into()),
                    _ => {}
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    /// Parent Home/shared file entrypoints can dispatch here before generic CSV import.
    pub(in crate::import) fn open_startup_report(&mut self, path: PathBuf, cx: &mut Context<Self>) {
        if self.busy || self.kind != Destination::Startup {
            return;
        }
        self.startup_capture.cancel_pending();
        self.cancel.cancel();
        self.cancel = CancellationToken::new();
        self.generation += 1;
        self.export_generation += 1;
        self.export_busy = false;
        let generation = self.generation;
        let cancel = self.cancel.clone();
        let baseline = match &self.session {
            Some(Session::Startup(s)) => Some(s.clone()),
            _ => None,
        };
        self.busy = true;
        self.error = None;
        let started = Instant::now();
        self.import_started = Some(started);
        let input = path.clone();
        let task = cx
            .background_executor()
            .spawn(async move { prepare_report(&input, baseline.as_deref(), &cancel) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                if this.generation != generation || this.cancel.is_cancelled() {
                    return;
                }
                this.busy = false;
                this.import_started = None;
                match result {
                    Ok(PreparedReport {
                        session,
                        page,
                        comparison,
                    }) => {
                        this.startup_capture.clear_published();
                        this.path = Some(path);
                        this.session = Some(Session::Startup(session));
                        this.page = Some(page);
                        this.session_id = Some(new_session_id());
                        this.selected_startup_run_id = None;
                        this.publish_startup_selection();
                        this.startup_capture.reports.comparison = comparison;
                        this.startup_capture.reports.pending = false;
                        this.import_duration = Some(started.elapsed());
                        this.notice = None;
                    }
                    Err(error) => this.error = Some(error),
                }
                cx.notify();
            })
            .ok();
        })
        .detach();
        cx.notify();
    }

    pub(super) fn schedule_startup_comparison(&mut self, cx: &mut Context<Self>) {
        if self.session.is_none() {
            self.startup_capture.reports = ReportHistory::default();
        }
        let reports = &mut self.startup_capture.reports;
        if !reports.pending {
            return;
        }
        reports.pending = false;
        let Some((current, baseline)) = reports.current.clone().zip(reports.baseline.clone())
        else {
            return;
        };
        let compared = current.clone();
        let task = cx
            .background_executor()
            .spawn(async move { compared.compare(&baseline, 5.0) });
        cx.spawn(async move |this, cx| {
            let result = task.await;
            this.update(cx, |this, cx| {
                let reports = &mut this.startup_capture.reports;
                if reports
                    .current
                    .as_ref()
                    .is_some_and(|s| Arc::ptr_eq(s, &current))
                {
                    match result {
                        Ok(comparison) => reports.comparison = Some(comparison),
                        Err(error) => this.error = Some(error.into()),
                    }
                    cx.notify();
                }
            })
            .ok();
        })
        .detach();
    }
}

//! Whole-session exports never reuse a filtered or paged UI projection.
use super::*;
use aps_json::{Limits, Value};
use std::{
    fs::OpenOptions,
    io::{BufWriter, Write},
};
use zip::{CompressionMethod, ZipWriter, write::SimpleFileOptions};

const MAX_SESSION_EXPORT_BYTES: usize = 128 * 1024 * 1024;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum NetworkExport {
    Json,
    Csv,
    PartialHar,
    RawBundle,
}

impl NetworkExport {
    fn extension(self) -> &'static str {
        match self {
            Self::Json => "json",
            Self::Csv => "csv",
            Self::PartialHar => "har",
            Self::RawBundle => "zip",
        }
    }
}

struct BoundedWriter<W> {
    inner: W,
    remaining: usize,
}
impl<W: Write> Write for BoundedWriter<W> {
    fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
        if bytes.len() > self.remaining {
            return Err(std::io::Error::other(
                "Network session exceeds the export byte budget",
            ));
        }
        let count = self.inner.write(bytes)?;
        self.remaining -= count;
        Ok(count)
    }
    fn flush(&mut self) -> std::io::Result<()> {
        self.inner.flush()
    }
}

fn json(value: &Value) -> Result<Vec<u8>, String> {
    aps_json::to_vec(value, Limits::default()).map_err(|error| error.to_string())
}

fn header_json(headers: &[(String, String)]) -> Value {
    Value::Array(
        headers
            .iter()
            .map(|(name, value)| serde_json::json!({"name": name, "value": value}))
            .collect(),
    )
}

fn entry_json(entry: &aps_network::NetworkEntry, detail: &aps_network::EntryDetail) -> Value {
    serde_json::json!({
        "index": entry.index,
        "method": entry.method,
        "url": entry.redacted_url,
        "status": entry.status,
        "durationMs": entry.duration_ms,
        "requestBytes": entry.request_bytes,
        "responseBytes": entry.response_bytes,
        "protocol": entry.protocol,
        "outcome": entry.outcome.label(),
        "connectionUse": entry.connection_use.name(),
        "startedAt": entry.started_at_ms.map(aps_network::format_rfc3339_ms),
        "requestHeaders": header_json(&detail.request_headers),
        "responseHeaders": header_json(&detail.response_headers),
        "tlsVersion": detail.tls_version,
        "cipherSuite": detail.cipher_suite,
        "failureType": detail.failure_type,
        "phases": detail.phases.iter().map(|phase| serde_json::json!({
            "kind": phase.kind.name(),
            "durationMs": phase.duration_ms,
            "availability": phase.availability.name(),
            "confidence": phase.confidence.name(),
            "parentKind": phase.parent.map(|parent| parent.name()),
        })).collect::<Vec<_>>(),
    })
}

fn timing(detail: &aps_network::EntryDetail, kind: aps_network::PhaseKind) -> f64 {
    detail
        .phases
        .iter()
        .find(|phase| phase.kind == kind)
        .and_then(|phase| phase.duration_ms)
        .unwrap_or(-1.0)
}

fn partial_har_entry(
    entry: &aps_network::NetworkEntry,
    detail: &aps_network::EntryDetail,
) -> Value {
    let mut value = serde_json::json!({
        "time": entry.duration_ms.unwrap_or(-1.0),
        "request": {
            "method": entry.method,
            "url": entry.redacted_url,
            "httpVersion": entry.protocol.clone().unwrap_or_default(),
            "headers": header_json(&detail.request_headers),
            "cookies": [],
            "queryString": [],
            "headersSize": -1,
            "bodySize": entry.request_bytes.map(Value::from).unwrap_or_else(|| (-1).into()),
        },
        "response": {
            "status": entry.status,
            "statusText": "",
            "httpVersion": entry.protocol.clone().unwrap_or_default(),
            "headers": header_json(&detail.response_headers),
            "cookies": [],
            "redirectURL": "",
            "headersSize": -1,
            "bodySize": entry.response_bytes.map(Value::from).unwrap_or_else(|| (-1).into()),
            "content": {"size": -1, "mimeType": ""},
        },
        "cache": {},
        "timings": {
            "blocked": timing(detail, aps_network::PhaseKind::DispatcherQueue),
            "dns": timing(detail, aps_network::PhaseKind::Dns),
            "connect": timing(detail, aps_network::PhaseKind::Connect),
            "ssl": timing(detail, aps_network::PhaseKind::Tls),
            "send": timing(detail, aps_network::PhaseKind::RequestBody),
            "wait": timing(detail, aps_network::PhaseKind::ServerWait),
            "receive": timing(detail, aps_network::PhaseKind::ResponseBody),
        },
        "_aps": {
            "partial": true,
            "policy": aps_network::MINIMIZATION_POLICY,
            "originalIndex": entry.index,
            "startedDateTimeAvailable": entry.started_at_ms.is_some(),
            "outcome": entry.outcome.label(),
            "connectionUse": entry.connection_use.name(),
            "bodiesCaptured": false,
        },
    });
    if let Some(started) = entry.started_at_ms {
        value["startedDateTime"] = serde_json::json!(aps_network::format_rfc3339_ms(started));
    }
    value
}

fn analysis_json(analysis: &aps_network::NetworkAnalysis) -> Value {
    serde_json::json!({
        "completedCount": analysis.completed_count,
        "failedCount": analysis.failed_count,
        "cancelledCount": analysis.cancelled_count,
        "incompleteCount": analysis.incomplete_count,
        "inFlightCount": analysis.in_flight_count,
        "connectionReuse": {
            "new": analysis.new_connection_count,
            "reused": analysis.reused_connection_count,
            "unknown": analysis.unknown_connection_count,
        },
        "p90Ms": analysis.p90_ms,
        "invalidTimingCount": analysis.invalid_timing_count,
        "startedAt": analysis.started_at_ms.map(aps_network::format_rfc3339_ms),
        "endedAt": analysis.ended_at_ms.map(aps_network::format_rfc3339_ms),
        "phases": analysis.phase_summaries.iter().map(|phase| serde_json::json!({
            "kind": phase.kind.name(),
            "sampleCount": phase.sample_count,
            "missingCount": phase.missing_count,
            "medianMs": phase.median_ms,
            "p95Ms": phase.p95_ms,
        })).collect::<Vec<_>>(),
    })
}

fn capture_metadata(session: &HarSession) -> Value {
    session.observed().map_or(Value::Null, |capture| {
        let concurrency = capture.concurrency();
        serde_json::json!({
            "processId": capture.process_id,
            "processStartTicks": capture.process_start_ticks,
            "startMonotonicNs": capture.start_monotonic_ns,
            "endMonotonicNs": capture.end_monotonic_ns,
            "eventCount": capture.event_count, "droppedEvents": capture.dropped_events,
            "sequenceGaps": capture.sequence_gaps,
            "excludedPreboundaryEvents": capture.excluded_preboundary_events,
            "unknownEvents": capture.unknown_events, "discardedEvents": capture.discarded_events,
            "omittedCallEvents": capture.omitted_call_events,
            "stopAcknowledged": capture.stop_acknowledged, "limitReached": capture.limit_reached,
            "rawEventsRetained": false,
            "analysis": {
                "concurrency": {
                    "includedCalls": concurrency.included_calls,
                    "excludedCalls": concurrency.excluded_calls,
                    "peak": concurrency.peak,
                    "peakNs": concurrency.peak_ns,
                    "average": concurrency.average,
                    "timeline": concurrency.timeline.iter().map(|(time, active)|
                        serde_json::json!({"timeNs": time, "activeCalls": active})
                    ).collect::<Vec<_>>(),
                },
            },
        })
    })
}

fn encode_session(
    session: &HarSession,
    format: NetworkExport,
    output: impl Write,
    cancel: &CancellationToken,
) -> Result<(), String> {
    let mut output = BoundedWriter {
        inner: output,
        remaining: MAX_SESSION_EXPORT_BYTES,
    };
    let write_error = |error: std::io::Error| error.to_string();
    if format == NetworkExport::Csv {
        output
            .write_all(
                b"index,method,url,status,duration_ms,request_bytes,response_bytes,protocol,outcome,connection,source\r\n",
            )
            .map_err(write_error)?;
        let source = if session.observed().is_some() {
            "agent"
        } else {
            "har"
        };
        session.visit_entries(cancel, |entry| {
            let row = [
                entry.index.to_string(),
                entry.method.into(),
                entry.redacted_url.clone(),
                entry.status.to_string(),
                entry
                    .duration_ms
                    .map(|value| value.to_string())
                    .unwrap_or_default(),
                entry
                    .request_bytes
                    .map(|value| value.to_string())
                    .unwrap_or_default(),
                entry
                    .response_bytes
                    .map(|value| value.to_string())
                    .unwrap_or_default(),
                entry.protocol.clone().unwrap_or_default(),
                entry.outcome.label().into(),
                entry.connection_use.name().to_ascii_lowercase(),
                source.into(),
            ];
            for (index, cell) in row.iter().enumerate() {
                if index > 0 {
                    output.write_all(b",").map_err(write_error)?;
                }
                let cell = cell.replace('"', "\"\"");
                write!(output, "\"{cell}\"").map_err(write_error)?;
            }
            output.write_all(b"\r\n").map_err(write_error)
        })?;
    } else if format == NetworkExport::Json {
        let summary = session.summary();
        let analysis = session
            .analysis(cancel)
            .map_err(|error| error.to_string())?;
        let metadata = serde_json::json!({
            "schemaVersion": 2,
            "format": "aps-network-session-v2",
            "scope": "whole-session",
            "sourceDigest": summary.source_digest.to_string(),
            "minimizationPolicy": summary.minimization_policy,
            "entryCount": summary.entry_count,
            "sourceFormatVersion": summary.har_version,
            "summary": {
                "requestBytes": summary.request_bytes.total_bytes,
                "responseBytes": summary.response_bytes.total_bytes,
                "meanDurationMs": summary.mean_duration_ms,
                "maxDurationMs": summary.max_duration_ms,
                "missingDurationCount": summary.missing_duration_count,
                "invalidDurationCount": summary.invalid_duration_count,
            },
            "analysis": analysis_json(&analysis),
            "capture": capture_metadata(session),
            "omitted": ["cookies", "bodies", "raw header values", "user info", "URL paths", "query values"],
        });
        output.write_all(b"{\"session\":").map_err(write_error)?;
        output.write_all(&json(&metadata)?).map_err(write_error)?;
        output.write_all(b",\"entries\":[").map_err(write_error)?;
        let mut first = true;
        session.visit_details(cancel, |entry, detail| {
            if !first {
                output.write_all(b",").map_err(write_error)?;
            }
            first = false;
            output
                .write_all(&json(&entry_json(entry, detail))?)
                .map_err(write_error)
        })?;
        output.write_all(b"]}").map_err(write_error)?;
    } else if format == NetworkExport::PartialHar {
        let summary = session.summary();
        let analysis = session
            .analysis(cancel)
            .map_err(|error| error.to_string())?;
        let metadata = serde_json::json!({
            "sourceDigest": summary.source_digest.to_string(),
            "minimizationPolicy": summary.minimization_policy,
            "analysis": analysis_json(&analysis),
            "capture": capture_metadata(session),
            "omitted": ["cookies", "bodies", "raw header values", "user info", "URL paths", "query values"],
        });
        output
            .write_all(
                b"{\"log\":{\"version\":\"1.2\",\"creator\":{\"name\":\"Android Performance Studio\",\"version\":\"1\"},\"_aps\":",
            )
            .map_err(write_error)?;
        output.write_all(&json(&metadata)?).map_err(write_error)?;
        output.write_all(b",\"entries\":[").map_err(write_error)?;
        let mut first = true;
        session.visit_details(cancel, |entry, detail| {
            if !first {
                output.write_all(b",").map_err(write_error)?;
            }
            first = false;
            output
                .write_all(&json(&partial_har_entry(entry, detail))?)
                .map_err(write_error)
        })?;
        output.write_all(b"]}}").map_err(write_error)?;
    } else {
        return Err("Use the raw bundle writer for archive exports".into());
    }
    if cancel.is_cancelled() {
        return Err("Network export cancelled".into());
    }
    output.flush().map_err(write_error)
}

fn write_raw_bundle(
    session: &HarSession,
    path: &Path,
    cancel: &CancellationToken,
) -> Result<(), String> {
    let result = write_raw_bundle_inner(session, path, cancel);
    if result.is_err() {
        let _ = std::fs::remove_file(path);
    }
    result
}

fn write_raw_bundle_inner(
    session: &HarSession,
    path: &Path,
    cancel: &CancellationToken,
) -> Result<(), String> {
    let mut report = Vec::new();
    encode_session(session, NetworkExport::Json, &mut report, cancel)?;
    let file = open_destination(path)?;
    let mut zip = ZipWriter::new(BufWriter::new(file));
    let options = SimpleFileOptions::default().compression_method(CompressionMethod::Deflated);
    zip.start_file("network-session.json", options)
        .map_err(|error| error.to_string())?;
    zip.write_all(&report).map_err(|error| error.to_string())?;
    zip.start_file("raw-events.json", options)
        .map_err(|error| error.to_string())?;
    // Agent raw event bytes are not retained by this adapter; the archive says so
    // instead of fabricating a raw stream.
    zip.write_all(b"[]").map_err(|error| error.to_string())?;
    zip.start_file("manifest.txt", options)
        .map_err(|error| error.to_string())?;
    let summary = session.summary();
    let manifest = format!(
        "schema=2\nsession={}\npolicy={}\nentries={}\nraw_events=not_retained\nsource={}\nbodies=excluded\ncookies=excluded\n",
        summary.source_digest,
        summary.minimization_policy,
        summary.entry_count,
        if session.observed().is_some() {
            "agent"
        } else {
            "har"
        },
    );
    zip.write_all(manifest.as_bytes())
        .map_err(|error| error.to_string())?;
    zip.finish().map_err(|error| error.to_string())?;
    Ok(())
}

fn open_destination(path: &Path) -> Result<std::fs::File, String> {
    if !path.is_absolute() {
        return Err("Network export requires an absolute destination".into());
    }
    let mut options = OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    options.open(path).map_err(|_| {
        "Select a new export filename; the destination exists or cannot be created".into()
    })
}

fn write_session(
    session: &HarSession,
    format: NetworkExport,
    path: &Path,
    cancel: &CancellationToken,
) -> Result<(), String> {
    if format == NetworkExport::RawBundle {
        return write_raw_bundle(session, path, cancel);
    }
    let file = open_destination(path)?;
    let mut output = BufWriter::new(file);
    let result = encode_session(session, format, &mut output, cancel).and_then(|()| {
        output
            .get_ref()
            .sync_all()
            .map_err(|error| error.to_string())
    });
    drop(output);
    if result.is_err() {
        let _ = std::fs::remove_file(path);
    }
    result
}

impl ImportWorkspace {
    pub(in crate::import) fn save_network_session(
        &mut self,
        format: NetworkExport,
        cx: &mut Context<Self>,
    ) {
        if self.busy || self.export_busy {
            return;
        }
        let Some(Session::Network(session)) = self.session.clone() else {
            return;
        };
        self.export_busy = true;
        self.export_generation = self.export_generation.wrapping_add(1);
        let generation = self.export_generation;
        let cancel = self.cancel.clone();
        let filename = format!("network-session.{}", format.extension());
        let directory = self
            .path
            .as_deref()
            .and_then(Path::parent)
            .unwrap_or(Path::new("."));
        let picker = cx.prompt_for_new_path(directory, Some(&filename));
        cx.spawn(async move |this, cx| {
            let chosen = picker.await;
            let path = this
                .update(cx, |this, cx| {
                    if this.export_generation != generation {
                        return None;
                    }
                    match chosen {
                        Ok(Ok(Some(path))) => Some(path),
                        other => {
                            this.export_busy = false;
                            if matches!(other, Ok(Err(_))) {
                                this.error = Some(TextKey::ImportUiSavePickerFailed.into());
                            }
                            cx.notify();
                            None
                        }
                    }
                })
                .ok()
                .flatten();
            let Some(path) = path else {
                return;
            };
            let result = cx
                .background_executor()
                .spawn(
                    async move { write_session(&session, format, &path, &cancel).map(|()| path) },
                )
                .await;
            let _ = this.update(cx, |this, cx| {
                if this.export_generation != generation {
                    return;
                }
                this.export_busy = false;
                match result {
                    Ok(path) => {
                        this.notice = Some(UiText::format(
                            TextKey::NetworkSessionSaved,
                            [("path", path.display().to_string().into())],
                        ))
                    }
                    Err(error) => this.error = Some(error.into()),
                }
                cx.notify();
            });
        })
        .detach();
        cx.notify();
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_network::{EntryOutcome, ImportLimits};
    use aps_platform::TempWorkspace;
    use std::io::Read as _;

    fn har_entry(index: usize) -> String {
        format!(
            "{{\"startedDateTime\":\"2026-11-16T22:00:00.000Z\",\"time\":12.5,\"request\":{{\"method\":\"GET\",\"url\":\"https://alice:pw@example.test/private/{index}?token=secret&page=2\",\"headers\":[{{\"name\":\"Authorization\",\"value\":\"Bearer secret\"}},{{\"name\":\"Content-Type\",\"value\":\"application/json\"}}],\"bodySize\":3}},\"response\":{{\"status\":200,\"bodySize\":9,\"httpVersion\":\"h2\",\"headers\":[{{\"name\":\"Content-Type\",\"value\":\"application/json\"}}],\"content\":{{\"size\":18}}}},\"timings\":{{\"blocked\":1,\"dns\":2,\"connect\":3,\"ssl\":4,\"send\":5,\"wait\":10,\"receive\":6}}}}"
        )
    }

    fn har_session(count: usize) -> (TempWorkspace, HarSession) {
        let workspace = TempWorkspace::create(&std::env::temp_dir()).unwrap();
        let entries: Vec<_> = (0..count).map(har_entry).collect();
        let document = format!(
            "{{\"log\":{{\"version\":\"1.2\",\"creator\":{{\"name\":\"fixture\"}},\"entries\":[{}]}}}}",
            entries.join(",")
        );
        let path = workspace.path().join("session.har");
        std::fs::write(&path, document).unwrap();
        let session =
            HarSession::open(&path, ImportLimits::default(), &CancellationToken::new()).unwrap();
        (workspace, session)
    }

    fn observed_session(count: usize) -> HarSession {
        let url = "https://example.test/<redacted-path>?q=<redacted>";
        HarSession::from_observed(ObservedCapture {
            origin: aps_network::ObservedOrigin::Agent,
            process_id: 123,
            process_start_ticks: 1,
            device_boot_id: "11111111-1111-1111-1111-111111111111".into(),
            start_monotonic_ns: 0,
            end_monotonic_ns: 100,
            event_count: (count * 2) as u64,
            dropped_events: 2,
            sequence_gaps: 1,
            excluded_preboundary_events: 0,
            unknown_events: 0,
            discarded_events: 0,
            omitted_call_events: 0,
            stop_acknowledged: true,
            limit_reached: false,
            requests: (0..count)
                .map(|index| aps_network::ObservedRequest {
                    entry: aps_network::NetworkEntry {
                        index: index as u64,
                        method: "GET",
                        status: 500,
                        duration_ms: Some(0.0001),
                        request_bytes: None,
                        response_bytes: Some(5),
                        redacted_url: url.into(),
                        protocol: Some("h2".into()),
                        outcome: EntryOutcome::Completed,
                        connection_use: aps_network::ConnectionUse::Unknown,
                        started_at_ms: None,
                    },
                    outcome: ObservedOutcome::Completed,
                    start_ns: Some(0),
                    end_ns: Some(100),
                    protocol: Some("h2"),
                    redacted_url: url.into(),
                    tls_version: None,
                    cipher_suite: None,
                    cancellation_requested: false,
                    phases_truncated: 0,
                    phases: Vec::new(),
                })
                .collect(),
        })
        .unwrap()
    }

    #[::core::prelude::v1::test]
    fn session_exports_all_entries_not_only_the_current_ui_page() {
        let (_workspace, session) = har_session(251);
        let mut bytes = Vec::new();
        encode_session(
            &session,
            NetworkExport::Json,
            &mut bytes,
            &CancellationToken::new(),
        )
        .unwrap();
        let report = aps_json::parse_slice(&bytes, Limits::default()).unwrap();
        assert_eq!(report["session"]["scope"], "whole-session");
        assert_eq!(report["session"]["entryCount"], 251);
        assert_eq!(report["session"]["analysis"]["completedCount"], 251);
        assert_eq!(report["entries"].as_array().unwrap().len(), 251);
        let last = &report["entries"][250];
        assert_eq!(last["status"], 200);
        assert!(last["url"].as_str().unwrap().contains("example.test"));
        assert!(last["url"].as_str().unwrap().contains("<redacted-path>"));
        assert_eq!(last["outcome"], "completed");
        assert_eq!(last["connectionUse"], "NEW");
        let headers = last["requestHeaders"].as_array().unwrap();
        assert!(headers.iter().any(|header| {
            header["name"] == "Authorization" && header["value"] == "<redacted>"
        }));
        let text = String::from_utf8(bytes).unwrap();
        for secret in ["alice", "pw@", "private/", "token=secret", "Bearer secret"] {
            assert!(!text.contains(secret), "leaked {secret}");
        }
    }

    #[::core::prelude::v1::test]
    fn partial_har_carries_redacted_identity_timings_and_header_metadata() {
        let (_workspace, session) = har_session(1);
        let mut bytes = Vec::new();
        encode_session(
            &session,
            NetworkExport::PartialHar,
            &mut bytes,
            &CancellationToken::new(),
        )
        .unwrap();
        let report = aps_json::parse_slice(&bytes, Limits::default()).unwrap();
        let entry = &report["log"]["entries"][0];
        assert_eq!(entry["startedDateTime"], "2026-11-16T22:00:00Z");
        assert_eq!(entry["_aps"]["startedDateTimeAvailable"], true);
        assert_eq!(
            entry["request"]["url"],
            "https://<redacted>@example.test/<redacted-path>?token=<redacted>&page=<redacted>"
        );
        assert_eq!(entry["timings"]["wait"], 10.0);
        assert_eq!(entry["timings"]["dns"], 2.0);
        let headers = entry["request"]["headers"].as_array().unwrap();
        assert!(headers.iter().any(|header| {
            header["name"] == "Authorization" && header["value"] == "<redacted>"
        }));
        assert!(entry["response"]["content"].get("text").is_none());
        assert_eq!(entry["response"]["content"]["size"], -1);
        assert!(
            report["log"]["_aps"]["capture"]
                .get("deviceBootId")
                .is_none()
        );
    }

    #[::core::prelude::v1::test]
    fn observed_session_never_fabricates_wall_time() {
        let session = observed_session(3);
        let mut bytes = Vec::new();
        encode_session(
            &session,
            NetworkExport::PartialHar,
            &mut bytes,
            &CancellationToken::new(),
        )
        .unwrap();
        let report = aps_json::parse_slice(&bytes, Limits::default()).unwrap();
        let entry = &report["log"]["entries"][0];
        assert!(entry.get("startedDateTime").is_none());
        assert_eq!(entry["_aps"]["startedDateTimeAvailable"], false);
        assert_eq!(
            entry["request"]["url"],
            "https://example.test/<redacted-path>?q=<redacted>"
        );
        assert!(entry["request"]["headers"].as_array().unwrap().is_empty());
    }

    #[::core::prelude::v1::test]
    fn csv_includes_all_rows_and_unknown_values_are_empty() {
        let session = observed_session(201);
        let mut bytes = Vec::new();
        encode_session(
            &session,
            NetworkExport::Csv,
            &mut bytes,
            &CancellationToken::new(),
        )
        .unwrap();
        let csv = String::from_utf8(bytes).unwrap();
        assert_eq!(csv.lines().count(), 202);
        assert!(
            csv.contains(
                "\"500\",\"0.0001\",\"\",\"5\",\"h2\",\"completed\",\"unknown\",\"agent\""
            )
        );
    }

    #[::core::prelude::v1::test]
    fn raw_bundle_contains_report_manifest_and_no_fabricated_raw_events() {
        let workspace = TempWorkspace::create(&std::env::temp_dir()).unwrap();
        let (_har, session) = har_session(2);
        let path = workspace.path().join("bundle.zip");
        write_session(
            &session,
            NetworkExport::RawBundle,
            &path,
            &CancellationToken::new(),
        )
        .unwrap();
        let file = std::fs::File::open(&path).unwrap();
        let mut archive = zip::ZipArchive::new(file).unwrap();
        assert!(archive.by_name("network-session.json").is_ok());
        assert!(archive.by_name("manifest.txt").is_ok());
        let mut manifest = String::new();
        archive
            .by_name("manifest.txt")
            .unwrap()
            .read_to_string(&mut manifest)
            .unwrap();
        assert!(manifest.contains("raw_events=not_retained"));
        let mut raw = String::new();
        archive
            .by_name("raw-events.json")
            .unwrap()
            .read_to_string(&mut raw)
            .unwrap();
        assert_eq!(raw, "[]");
    }

    #[::core::prelude::v1::test]
    fn cancellation_existing_destinations_and_byte_bounds_fail_closed() {
        let cancel = CancellationToken::new();
        cancel.cancel();
        let (_har, session) = har_session(1);
        assert!(encode_session(&session, NetworkExport::Json, Vec::new(), &cancel).is_err());
        let mut writer = BoundedWriter {
            inner: Vec::new(),
            remaining: 2,
        };
        assert!(writer.write_all(b"abc").is_err());
        assert!(writer.inner.is_empty());
        let workspace = TempWorkspace::create(&std::env::temp_dir()).unwrap();
        let path = workspace.path().join("report.json");
        std::fs::write(&path, b"original").unwrap();
        assert!(
            write_session(
                &session,
                NetworkExport::Json,
                &path,
                &CancellationToken::new()
            )
            .is_err()
        );
        assert_eq!(std::fs::read(&path).unwrap(), b"original");
        let cancelled = workspace.path().join("cancelled.json");
        assert!(write_session(&session, NetworkExport::Json, &cancelled, &cancel).is_err());
        assert!(!cancelled.exists());
    }
}

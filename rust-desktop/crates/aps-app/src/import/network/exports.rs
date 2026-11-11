//! Whole-session exports never reuse a filtered or paged UI projection.
use super::*;
use aps_json::{Limits, Value};
use std::{
    fs::OpenOptions,
    io::{BufWriter, Write},
};

const MAX_SESSION_EXPORT_BYTES: usize = 128 * 1024 * 1024;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(crate) enum NetworkExport {
    Json,
    Csv,
    PartialHar,
}

impl NetworkExport {
    fn extension(self) -> &'static str {
        match self {
            Self::Json => "json",
            Self::Csv => "csv",
            Self::PartialHar => "har",
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

fn observed_request(session: &HarSession, entry: &aps_network::NetworkEntry) -> Value {
    let Some(request) = session
        .observed()
        .and_then(|capture| capture.request(entry.index))
    else {
        return Value::Null;
    };
    serde_json::json!({
        "outcome": request.outcome.label(),
        "startNs": request.start_ns,
        "endNs": request.end_ns,
        "protocol": request.protocol,
        "cancellationRequested": request.cancellation_requested,
        "phasesTruncated": request.phases_truncated,
        "phases": request.phases.iter().map(|phase| serde_json::json!({
            "kind": phase.kind.label(), "startNs": phase.start_ns,
            "endNs": phase.end_ns, "failed": phase.failed,
        })).collect::<Vec<_>>(),
    })
}

fn entry_json(session: &HarSession, entry: &aps_network::NetworkEntry) -> Value {
    serde_json::json!({
        "index": entry.index, "method": entry.method, "status": entry.status,
        "durationMs": entry.duration_ms, "requestBytes": entry.request_bytes,
        "responseBytes": entry.response_bytes, "url": entry.minimized_url,
        "observation": observed_request(session, entry),
    })
}

fn partial_har_entry(session: &HarSession, entry: &aps_network::NetworkEntry) -> Value {
    // The minimized source has no wall-clock epoch. Do not synthesize the HAR
    // startedDateTime field from a monotonic timestamp or host export time.
    serde_json::json!({
        "time": entry.duration_ms.unwrap_or(-1.0),
        "request": {"method": entry.method, "url": entry.minimized_url,
            "httpVersion": "", "headers": [], "cookies": [], "queryString": [],
            "headersSize": -1, "bodySize": entry.request_bytes.map(Value::from).unwrap_or((-1).into())},
        "response": {"status": entry.status, "statusText": "", "httpVersion": "",
            "headers": [], "cookies": [], "redirectURL": "", "headersSize": -1,
            "bodySize": entry.response_bytes.map(Value::from).unwrap_or((-1).into()),
            "content": {"size": -1, "mimeType": ""}},
        "cache": {}, "timings": {"send": -1, "wait": -1, "receive": -1},
        "_aps": {"partial": true, "originalIndex": entry.index,
            "startedDateTimeAvailable": false, "observation": observed_request(session, entry)},
    })
}

fn capture_metadata(session: &HarSession) -> Value {
    session.observed().map_or(Value::Null, |capture| {
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
                b"index,method,url,outcome,status,duration_ms,request_bytes,response_bytes\r\n",
            )
            .map_err(write_error)?;
        session.visit_entries(cancel, |entry| {
            let outcome = session
                .observed()
                .and_then(|capture| capture.request(entry.index))
                .map(|request| request.outcome.label())
                .unwrap_or("");
            let row = [
                entry.index.to_string(),
                entry.method.into(),
                entry.minimized_url.into(),
                outcome.into(),
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
    } else {
        let summary = session.summary();
        let metadata = serde_json::json!({
            "format": "aps-network-session-v1", "scope": "whole-session",
            "sourceDigest": summary.source_digest.to_string(),
            "minimizationPolicy": summary.minimization_policy,
            "entryCount": summary.entry_count, "capture": capture_metadata(session),
            "omitted": ["raw URLs", "headers", "cookies", "bodies", "wall-clock timestamps"],
        });
        if format == NetworkExport::Json {
            output.write_all(b"{\"metadata\":").map_err(write_error)?;
            output.write_all(&json(&metadata)?).map_err(write_error)?;
            output.write_all(b",\"entries\":[").map_err(write_error)?;
        } else {
            output.write_all(b"{\"log\":{\"version\":\"1.2\",\"creator\":{\"name\":\"AndroidPerformanceStudio\",\"version\":\"1\"},\"_aps\":").map_err(write_error)?;
            output.write_all(&json(&metadata)?).map_err(write_error)?;
            output.write_all(b",\"entries\":[").map_err(write_error)?;
        }
        let mut first = true;
        session.visit_entries(cancel, |entry| {
            if !first {
                output.write_all(b",").map_err(write_error)?;
            }
            first = false;
            let value = if format == NetworkExport::Json {
                entry_json(session, entry)
            } else {
                partial_har_entry(session, entry)
            };
            output.write_all(&json(&value)?).map_err(write_error)
        })?;
        output
            .write_all(if format == NetworkExport::Json {
                b"]}"
            } else {
                b"]}}"
            })
            .map_err(write_error)?;
    }
    if cancel.is_cancelled() {
        return Err("Network export cancelled".into());
    }
    output.flush().map_err(write_error)
}

fn write_session(
    session: &HarSession,
    format: NetworkExport,
    path: &Path,
    cancel: &CancellationToken,
) -> Result<(), String> {
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
    let file = options
        .open(path)
        .map_err(|_| "Select a new export filename; the destination exists or cannot be created")?;
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
    fn fixture(count: usize) -> HarSession {
        HarSession::from_observed(ObservedCapture {
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
                        minimized_url: MINIMIZED_URL,
                    },
                    outcome: ObservedOutcome::Completed,
                    start_ns: Some(0),
                    end_ns: Some(100),
                    protocol: Some("h2"),
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
        let session = fixture(251);
        let mut bytes = Vec::new();
        encode_session(
            &session,
            NetworkExport::Json,
            &mut bytes,
            &CancellationToken::new(),
        )
        .unwrap();
        let report = aps_json::parse_slice(&bytes, Limits::default()).unwrap();
        assert_eq!(report["entries"].as_array().unwrap().len(), 251);
        assert_eq!(report["metadata"]["scope"], "whole-session");
        assert_eq!(report["metadata"]["capture"]["droppedEvents"], 2);
        assert_eq!(
            report["entries"][250]["observation"]["outcome"],
            "completed"
        );
        assert_eq!(report["entries"][250]["status"], 500);
    }
    #[::core::prelude::v1::test]
    fn partial_har_does_not_fabricate_wall_time_or_raw_content() {
        let mut bytes = Vec::new();
        encode_session(
            &fixture(1),
            NetworkExport::PartialHar,
            &mut bytes,
            &CancellationToken::new(),
        )
        .unwrap();
        let report = aps_json::parse_slice(&bytes, Limits::default()).unwrap();
        let entry = &report["log"]["entries"][0];
        assert!(entry.get("startedDateTime").is_none());
        assert_eq!(entry["_aps"]["startedDateTimeAvailable"], false);
        assert_eq!(entry["request"]["url"], MINIMIZED_URL);
        assert!(entry["request"]["headers"].as_array().unwrap().is_empty());
        assert!(entry["response"]["content"].get("text").is_none());
        assert_eq!(entry["response"]["content"]["size"], -1);
        assert!(
            report["log"]["_aps"]["capture"]
                .get("deviceBootId")
                .is_none()
        );
    }
    #[::core::prelude::v1::test]
    fn csv_includes_all_rows_and_unknown_values_are_empty() {
        let mut bytes = Vec::new();
        encode_session(
            &fixture(201),
            NetworkExport::Csv,
            &mut bytes,
            &CancellationToken::new(),
        )
        .unwrap();
        let csv = String::from_utf8(bytes).unwrap();
        assert_eq!(csv.lines().count(), 202);
        assert!(csv.contains("\"completed\",\"500\",\"0.0001\",\"\",\"5\""));
    }
    #[::core::prelude::v1::test]
    fn cancellation_existing_destinations_and_byte_bounds_fail_closed() {
        let cancel = CancellationToken::new();
        cancel.cancel();
        assert!(encode_session(&fixture(1), NetworkExport::Json, Vec::new(), &cancel).is_err());
        let mut writer = BoundedWriter {
            inner: Vec::new(),
            remaining: 2,
        };
        assert!(writer.write_all(b"abc").is_err());
        assert!(writer.inner.is_empty());
        let workspace = aps_platform::TempWorkspace::create(&std::env::temp_dir()).unwrap();
        let path = workspace.path().join("report.json");
        std::fs::write(&path, b"original").unwrap();
        assert!(
            write_session(
                &fixture(1),
                NetworkExport::Json,
                &path,
                &CancellationToken::new()
            )
            .is_err()
        );
        assert_eq!(std::fs::read(&path).unwrap(), b"original");
        let cancelled = workspace.path().join("cancelled.json");
        assert!(write_session(&fixture(1), NetworkExport::Json, &cancelled, &cancel).is_err());
        assert!(!cancelled.exists());
    }
}

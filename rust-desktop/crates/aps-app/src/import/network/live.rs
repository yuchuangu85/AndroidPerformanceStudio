//! Feature-owned consent, capture lifetime and observed-evidence presentation.
//! Shared target selection is discovery, never consent. Every publication is
//! bound to the selected descriptor and this live run's independent generation.
use super::*;
use aps_adb::{AdbDevice, AndroidApplicationProcess};
use aps_network::{ObservedCapture, ObservedOutcome, ObservedPhaseKind};
use capture::{CaptureTarget, CaptureUpdate, LiveControl, LiveSnapshot};

#[derive(Default)]
pub(super) struct NetworkLiveUi {
    serial: Option<String>,
    pid: Option<u32>,
    target: Option<CaptureTarget>,
    active_target: Option<CaptureTarget>,
    consent: Option<Consent>,
    control: Option<Arc<LiveControl>>,
    retiring: Option<Arc<LiveControl>>,
    has_published_snapshot: bool,
    generation: u64,
    backup: Option<PublicationBackup>,
    session_id: Option<String>,
}
struct Consent {
    target: CaptureTarget,
    created: Instant,
}
struct PublicationBackup {
    session: Option<Session>,
    path: Option<PathBuf>,
    page: Option<PageData>,
    session_id: Option<String>,
    selected: Option<Vec<String>>,
    query: EntryQuery,
    published_query: EntryQuery,
    total: u64,
    percentiles: Option<DurationPercentiles>,
    duration: Option<Duration>,
    observed_target: Option<CaptureTarget>,
}

impl NetworkLiveUi {
    pub(super) fn reset(&mut self) {
        if let Some(control) = self.control.take() {
            control.cancel();
            self.retiring = Some(control);
        }
        self.generation = self.generation.wrapping_add(1);
        self.consent = None;
        self.backup = None;
        self.session_id = None;
        self.active_target = None;
        self.has_published_snapshot = false;
    }
}
impl Drop for NetworkLiveUi {
    fn drop(&mut self) {
        if let Some(control) = &self.control {
            control.cancel();
        }
    }
}

impl ImportWorkspace {
    pub(crate) fn set_network_capture_target(
        &mut self,
        serial: Option<String>,
        pid: Option<u32>,
        cx: &mut Context<Self>,
    ) {
        if self.network.live.serial != serial || self.network.live.pid != pid {
            self.invalidate_network_live(cx);
            self.network.live.serial = serial;
            self.network.live.pid = pid;
            self.network.live.target = None;
        }
        cx.notify();
    }

    pub(crate) fn apply_network_capture_targets(
        &mut self,
        devices: &[AdbDevice],
        processes: &[AndroidApplicationProcess],
        cx: &mut Context<Self>,
    ) {
        let next = self
            .network
            .live
            .serial
            .as_ref()
            .zip(self.network.live.pid)
            .and_then(|(serial, pid)| {
                if !devices
                    .iter()
                    .any(|device| &device.serial == serial && device.is_online())
                {
                    return None;
                }
                let process = processes
                    .iter()
                    .find(|process| process.pid == pid && pid > 0)?;
                Some(CaptureTarget {
                    serial: serial.clone(),
                    pid,
                    process_name: process.name.clone(),
                    package_name: process.package_name.clone(),
                    user: process.user.clone(),
                })
            });
        if self.network.live.target != next {
            self.invalidate_network_live(cx);
            self.network.live.target = next;
        }
        cx.notify();
    }

    pub(crate) fn network_live_busy(&self) -> bool {
        self.network.live.control.is_some()
    }

    /// Called before generic import/pick/open/close/cancel mutates this workspace.
    /// Restores the previously published artifact, cancels only this run, and
    /// revokes consent. Its old worker can only finish owned cleanup afterward.
    pub(crate) fn invalidate_network_live(&mut self, cx: &mut Context<Self>) {
        let active = self.network.live.control.is_some();
        let backup = self.network.live.backup.take();
        self.network.live.reset();
        if active {
            self.cancel.cancel();
            self.cancel = CancellationToken::new();
            self.generation = self.generation.wrapping_add(1);
            self.export_generation = self.export_generation.wrapping_add(1);
            self.export_busy = false;
            self.busy = false;
        }
        if let Some(backup) = backup {
            self.session = backup.session;
            self.path = backup.path;
            self.page = backup.page;
            self.session_id = backup.session_id;
            self.selected_network = backup.selected;
            self.network.query = backup.query;
            self.network.published_query = backup.published_query;
            self.network.total = backup.total;
            self.network.percentiles = backup.percentiles;
            self.import_duration = backup.duration;
            self.network.observed_target = backup.observed_target;
            self.import_started = None;
        }
        cx.notify();
    }

    /// No device command or forward is issued until the separate Approve action.
    pub(crate) fn network_start_live(&mut self, cx: &mut Context<Self>) {
        if self.kind != Destination::Network
            || self.busy
            || self.export_busy
            || self.network_live_busy()
        {
            return;
        }
        if self
            .network
            .live
            .retiring
            .as_ref()
            .is_some_and(|control| !control.finished())
        {
            self.notice = Some(TextKey::NetworkLiveStopping.into());
            cx.notify();
            return;
        }
        self.network.live.retiring = None;
        let Some(target) = self.network.live.target.clone() else {
            self.notice = Some(TextKey::NetworkLiveSelectTarget.into());
            cx.notify();
            return;
        };
        self.network.live.consent = Some(Consent {
            target,
            created: Instant::now(),
        });
        self.error = None;
        self.notice = None;
        cx.notify();
    }

    fn network_approve_live(&mut self, cx: &mut Context<Self>) {
        if self.busy || self.export_busy || self.network_live_busy() {
            return;
        }
        let Some(consent) = self.network.live.consent.take() else {
            return;
        };
        if consent.created.elapsed() > Duration::from_secs(60)
            || self.network.live.target.as_ref() != Some(&consent.target)
        {
            self.notice = Some(TextKey::NetworkLiveSelectTarget.into());
            cx.notify();
            return;
        }
        self.network.live.backup = Some(PublicationBackup {
            session: self.session.clone(),
            path: self.path.clone(),
            page: self.page.clone(),
            session_id: self.session_id.clone(),
            selected: self.selected_network.clone(),
            query: self.network.query.clone(),
            published_query: self.network.published_query.clone(),
            total: self.network.total,
            percentiles: self.network.percentiles,
            duration: self.import_duration,
            observed_target: self.network.observed_target.clone(),
        });
        self.cancel.cancel();
        self.cancel = CancellationToken::new();
        self.generation = self.generation.wrapping_add(1);
        self.export_generation = self.export_generation.wrapping_add(1);
        self.network.live.generation = self.network.live.generation.wrapping_add(1);
        self.network.live.session_id = Some(new_session_id());
        self.network.live.active_target = Some(consent.target.clone());
        let generation = self.network.live.generation;
        let control = Arc::new(LiveControl::default());
        self.network.live.control = Some(control.clone());
        self.busy = true;
        self.error = None;
        self.notice = None;
        self.import_started = None;
        self.selected_network = None;
        // The worker owns transport and scoped resource cleanup. Dropping the
        // entity cancels its control; it cannot orphan a UI publication task.
        cx.background_executor()
            .spawn(async move { capture::run(consent.target, control) })
            .detach();
        self.network_schedule_live_poll(generation, cx);
        cx.notify();
    }

    pub(crate) fn network_stop_live(&mut self, cx: &mut Context<Self>) {
        if let Some(control) = &self.network.live.control {
            control.stop();
        }
        cx.notify();
    }

    pub(crate) fn network_cancel_live(&mut self, cx: &mut Context<Self>) {
        self.invalidate_network_live(cx);
        self.error = None;
        self.notice = Some(TextKey::NetworkLiveCancelled.into());
        cx.notify();
    }

    fn network_schedule_live_poll(&mut self, generation: u64, cx: &mut Context<Self>) {
        cx.spawn(async move |this, cx| {
            cx.background_executor()
                .timer(Duration::from_millis(200))
                .await;
            this.update(cx, |this, cx| {
                if this.network.live.generation != generation || !this.network_live_busy() {
                    return;
                }
                let update = this
                    .network
                    .live
                    .control
                    .as_ref()
                    .and_then(|control| control.take_update());
                match update {
                    Some(CaptureUpdate::Snapshot(snapshot)) => this.publish_live_network(snapshot),
                    Some(CaptureUpdate::Finished(result)) => {
                        match result {
                            Ok(snapshot) => {
                                this.publish_live_network(snapshot);
                                this.busy = false;
                                this.network.live.control = None;
                                this.network.live.backup = None;
                                this.network.live.session_id = None;
                                // Live observation duration is not a file-import cost.
                                this.import_started = None;
                                this.import_duration = None;
                                this.notice = Some(TextKey::NetworkLiveComplete.into());
                            }
                            Err(error) => {
                                this.invalidate_network_live(cx);
                                this.error = Some(UiText::format(
                                    TextKey::NetworkLiveFailed,
                                    [("detail", error.into())],
                                ));
                            }
                        }
                        cx.notify();
                        return;
                    }
                    None => {}
                }
                this.network_schedule_live_poll(generation, cx);
                cx.notify();
            })
            .ok();
        })
        .detach();
    }

    fn publish_live_network(&mut self, snapshot: LiveSnapshot) {
        self.import_started = None;
        self.import_duration = None;
        self.network.live.has_published_snapshot = true;
        self.network.observed_target = self.network.live.active_target.clone();
        self.session = Some(Session::Network(snapshot.session));
        self.path = None;
        self.session_id = self.network.live.session_id.clone();
        self.generation = self.generation.wrapping_add(1);
        self.publish_network(snapshot.publication, EntryQuery::default());
    }

    pub(super) fn network_live_consent_visible(&self) -> bool {
        self.network.live.consent.is_some()
    }

    pub(super) fn network_live_status(&self) -> TextKey {
        if self
            .network
            .live
            .control
            .as_ref()
            .is_some_and(|control| control.stopping())
        {
            TextKey::NetworkLiveStopping
        } else if self.network.live.has_published_snapshot {
            TextKey::NetworkLiveCapturing
        } else {
            TextKey::NetworkLiveConnecting
        }
    }

    pub(super) fn network_live_consent_panel(
        &self,
        colors: &studio::StudioColors,
        cx: &mut Context<Self>,
    ) -> Option<AnyElement> {
        let consent = self.network.live.consent.as_ref()?;
        let target = &consent.target;
        let language = self.language;
        Some(
            panel(colors)
                .id("network-live-consent")
                .p(px(10.))
                .gap(px(8.))
                .child(studio::supporting(
                    language.format(
                        TextKey::NetworkLiveConfirm,
                        &[
                            ("package", &target.package_name),
                            ("pid", &target.pid),
                            ("serial", &target.serial),
                        ],
                    ),
                    colors,
                ))
                .child(studio::supporting(
                    language.text(TextKey::NetworkLiveCaptureLimits),
                    colors,
                ))
                .child(
                    div()
                        .flex()
                        .gap(px(8.))
                        .flex_wrap()
                        .child(
                            studio::compact_text_button(
                                "network-live-approve",
                                language.text(TextKey::NetworkLiveApprove),
                                !self.busy,
                                true,
                                colors,
                            )
                            .on_click(cx.listener(|this, _, _, cx| this.network_approve_live(cx))),
                        )
                        .child(
                            studio::compact_text_button(
                                "network-live-dismiss",
                                language.text(TextKey::NetworkLiveCancel),
                                true,
                                false,
                                colors,
                            )
                            .on_click(cx.listener(|this, _, _, cx| this.network_cancel_live(cx))),
                        ),
                )
                .into_any_element(),
        )
    }
}

pub(super) fn project_observed(session: &HarSession, page: &mut PageData, matching: u64) {
    let Some(capture) = session.observed() else {
        return;
    };
    page.summary = format!(
        "{} matching / {} observed OkHttp calls · events {} · dropped {} · sequence gaps {} · minimized snapshot SHA-256 {} · {}",
        matching,
        capture.requests.len(),
        capture.event_count,
        capture.dropped_events,
        capture.sequence_gaps,
        session.summary().source_digest,
        session.summary().minimization_policy
    );
    page.columns.extend(
        [
            "Outcome",
            "Protocol",
            "Evidence source",
            "Start offset ms",
            "End offset ms",
            "Observed phase records",
            "Incomplete phase records",
            "Cancellation callback observed",
            "Truncated phase records",
            "Phase evidence (device-relative ms)",
        ]
        .into_iter()
        .map(str::to_owned),
    );
    for row in &mut page.rows {
        let request = row
            .first()
            .and_then(|index| index.parse::<u64>().ok())
            .and_then(|index| capture.request(index));
        if let Some(request) = request {
            let offset = |time: Option<u64>| {
                time.and_then(|time| time.checked_sub(capture.start_monotonic_ns))
                    .map_or_else(
                        || "unavailable".into(),
                        |ns| (ns as f64 / 1_000_000.).to_string(),
                    )
            };
            row.extend([
                request.outcome.label().into(),
                request.protocol.unwrap_or("unavailable").into(),
                "APS Network Agent v2".into(),
                offset(request.start_ns),
                offset(request.end_ns),
                request.phases.len().to_string(),
                request
                    .phases
                    .iter()
                    .filter(|phase| phase.start_ns.is_none() || phase.end_ns.is_none())
                    .count()
                    .to_string(),
                request.cancellation_requested.to_string(),
                request.phases_truncated.to_string(),
                phase_evidence(request, capture.start_monotonic_ns),
            ]);
        } else {
            row.extend(["unavailable"; 10].into_iter().map(str::to_owned));
        }
    }
    page.warnings = vec![
        "APS Agent v2; instrumented OkHttp only. WebView, Cronet, URLConnection, native sockets and unconfigured clients excluded.".into(),
        format!("Agent dropped events {}; missing sequence numbers {}; incomplete requests {}. Loss counters may overlap; no completeness claim.", capture.dropped_events, capture.sequence_gaps, capture.incomplete_requests()),
        "Observed body-end byte subtotals are not complete transferred wire bytes; absent measurements remain unknown.".into(),
        "SHA-256 identifies this minimized immutable snapshot, not the original Agent stream. Raw URL, headers, cookies, body and arbitrary diagnostics were not retained.".into(),
        format!("STOP acknowledged {}; budget reached {}; excluded pre-boundary events {}; unknown event kinds {}; truncated phase records {}; budget-discarded events {}; omitted-call events {}.",
            capture.stop_acknowledged, capture.limit_reached, capture.excluded_preboundary_events, capture.unknown_events,
            capture.requests.iter().map(|request| request.phases_truncated).sum::<u64>(), capture.discarded_events, capture.omitted_call_events),
    ];
    page.ui = PageUi::default()
        .warning(0, TextKey::NetworkLiveCoverage.into())
        .warning(2, TextKey::NetworkLiveBodyBytesNote.into())
        .warning(3, TextKey::NetworkLiveSnapshotEvidenceNote.into());
}

fn phase_evidence(request: &aps_network::ObservedRequest, boundary: u64) -> String {
    // At most 64 records per request, containing only allowlisted phase names
    // and numeric evidence. Missing endpoints are JSON null, never invented.
    let offset = |time: Option<u64>| {
        time.and_then(|time| time.checked_sub(boundary))
            .map(|ns| ns as f64 / 1_000_000.)
    };
    serde_json::Value::Array(
        request
            .phases
            .iter()
            .map(|phase| {
                serde_json::json!({
                    "phase": phase.kind.label(),
                    "start_ms": offset(phase.start_ns),
                    "end_ms": offset(phase.end_ns),
                    "duration_ms": phase.duration_ms(),
                    "failed": phase.failed,
                })
            })
            .collect(),
    )
    .to_string()
}

pub(super) fn coverage_panel(
    capture: &ObservedCapture,
    summary: &HarSummary,
    target: Option<&CaptureTarget>,
    language: Language,
    colors: &studio::StudioColors,
) -> Stateful<Div> {
    panel(colors)
        .id("network-live-coverage")
        .p(px(8.))
        .gap(px(6.))
        .child(studio::supporting(
            language.text(TextKey::NetworkLiveCoverage),
            colors,
        ))
        .when_some(target, |panel, target| {
            panel.child(studio::supporting(
                language.format(
                    TextKey::NetworkLiveTarget,
                    &[
                        ("package", &target.package_name),
                        ("pid", &target.pid),
                        ("serial", &target.serial),
                    ],
                ),
                colors,
            ))
        })
        .child(
            studio::supporting(counts(capture, language), colors).text_color(
                if capture.dropped_events > 0 || capture.sequence_gaps > 0 {
                    colors.warning
                } else {
                    colors.secondary_text
                },
            ),
        )
        .child(studio::supporting(
            language.format(
                TextKey::NetworkLiveSnapshotDigest,
                &[("digest", &summary.source_digest)],
            ),
            colors,
        ))
        .when(capture.limit_reached, |panel| {
            panel.child(
                studio::supporting(language.text(TextKey::NetworkLiveLimitReached), colors)
                    .text_color(colors.warning),
            )
        })
}

pub(super) fn counts(capture: &ObservedCapture, language: Language) -> String {
    language.format(
        TextKey::NetworkLiveCounts,
        &[
            ("events", &capture.event_count),
            ("dropped", &capture.dropped_events),
            ("gaps", &capture.sequence_gaps),
            ("incomplete", &capture.incomplete_requests()),
        ],
    )
}

pub(super) fn activity_panel(
    capture: &ObservedCapture,
    language: Language,
    colors: &studio::StudioColors,
) -> Stateful<Div> {
    panel(colors)
        .id("network-live-activity")
        .p(px(8.))
        .gap(px(6.))
        .child(panel_header(
            language.text(TextKey::NetworkActivity),
            colors,
        ))
        .child(studio::supporting(
            language.format(
                TextKey::NetworkLiveActivity,
                &[
                    (
                        "duration",
                        &duration(Some(
                            (capture.end_monotonic_ns - capture.start_monotonic_ns) as f64
                                / 1_000_000.,
                        )),
                    ),
                    ("active", &capture.outcome_count(ObservedOutcome::InFlight)),
                    (
                        "completed",
                        &capture.outcome_count(ObservedOutcome::Completed),
                    ),
                    ("failed", &capture.outcome_count(ObservedOutcome::Failed)),
                    (
                        "cancelled",
                        &capture.outcome_count(ObservedOutcome::Cancelled),
                    ),
                ],
            ),
            colors,
        ))
        .child(studio::supporting(
            language.text(TextKey::NetworkLiveBodyBytesNote),
            colors,
        ))
}

fn phase_label(kind: ObservedPhaseKind, language: Language) -> &'static str {
    language.text(match kind {
        ObservedPhaseKind::Dns => TextKey::NetworkDetailDns,
        ObservedPhaseKind::Connect => TextKey::NetworkDetailConnect,
        ObservedPhaseKind::Tls => TextKey::NetworkDetailTls,
        ObservedPhaseKind::RequestHeaders => TextKey::NetworkDetailRequestHeaders,
        ObservedPhaseKind::RequestBody => TextKey::NetworkDetailRequestBody,
        ObservedPhaseKind::ResponseHeaders => TextKey::NetworkDetailResponseHeaders,
        ObservedPhaseKind::ResponseBody => TextKey::NetworkDetailResponseBody,
        ObservedPhaseKind::Proxy => TextKey::NetworkLiveProxy,
    })
}

pub(super) fn request_detail(
    capture: &ObservedCapture,
    row: Option<&[String]>,
    language: Language,
    colors: &studio::StudioColors,
) -> AnyElement {
    let request = row
        .and_then(|row| row.first())
        .and_then(|index| index.parse::<u64>().ok())
        .and_then(|index| capture.request(index));
    let mut view = panel(colors)
        .id("network-live-request-detail")
        .size_full()
        .min_h_0()
        .min_w_0()
        .p(px(8.))
        .gap(px(6.))
        .child(panel_header(
            language.text(TextKey::NetworkDetailTitle),
            colors,
        ));
    let Some(request) = request else {
        return view
            .child(studio::supporting(
                language.text(TextKey::NetworkEmpty),
                colors,
            ))
            .into_any_element();
    };
    view = view
        .child(studio::supporting(
            format!(
                "#{} · {} · {} · {}",
                request.entry.index,
                request.entry.method,
                outcome_label(request.outcome, language),
                request.protocol.unwrap_or("—")
            ),
            colors,
        ))
        .child(studio::supporting(MINIMIZED_URL, colors));
    let mut phases = div()
        .flex_1()
        .min_h_0()
        .overflow_y_scrollbar()
        .flex()
        .flex_col()
        .gap(px(5.));
    for (index, phase) in request.phases.iter().enumerate() {
        let status = if phase.failed {
            language.text(TextKey::NetworkLivePhaseFailed)
        } else {
            ""
        };
        phases = phases.child(
            div()
                .flex()
                .gap(px(6.))
                .min_w_0()
                .child(
                    studio::supporting(
                        format!("{}. {}", index + 1, phase_label(phase.kind, language)),
                        colors,
                    )
                    .flex_1(),
                )
                .child(studio::supporting(
                    format!("{} {status}", duration(phase.duration_ms())),
                    colors,
                )),
        );
    }
    view.child(phases)
        .child(studio::supporting(
            language.text(TextKey::NetworkLivePhasePartial),
            colors,
        ))
        .child(studio::supporting(
            language.text(TextKey::NetworkLiveNoContent),
            colors,
        ))
        .into_any_element()
}

pub(super) fn outcome_label(outcome: ObservedOutcome, language: Language) -> &'static str {
    // Canonical outcome values remain stable in exports; the existing detail
    // status resources do not conflate HTTP error classes with call failures.
    language.text(match outcome {
        ObservedOutcome::InFlight => TextKey::NetworkOutcomeInFlight,
        ObservedOutcome::Completed => TextKey::NetworkOutcomeCompleted,
        ObservedOutcome::Failed => TextKey::NetworkOutcomeFailed,
        ObservedOutcome::Cancelled => TextKey::NetworkOutcomeCancelled,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_network::{NetworkEntry, ObservedPhase, ObservedRequest};

    #[::core::prelude::v1::test]
    fn observed_projection_exports_real_minimized_endpoints_and_unknowns() {
        let request = ObservedRequest {
            entry: NetworkEntry {
                index: 1,
                method: "GET",
                status: 500,
                duration_ms: Some(3.),
                request_bytes: None,
                response_bytes: Some(20),
                minimized_url: MINIMIZED_URL,
            },
            outcome: ObservedOutcome::Completed,
            start_ns: Some(1_000_000),
            end_ns: Some(4_000_000),
            protocol: Some("h2"),
            cancellation_requested: true,
            phases_truncated: 2,
            phases: vec![
                ObservedPhase {
                    kind: ObservedPhaseKind::Dns,
                    start_ns: Some(2_000_000),
                    end_ns: Some(3_000_000),
                    failed: false,
                },
                ObservedPhase {
                    kind: ObservedPhaseKind::Connect,
                    start_ns: None,
                    end_ns: Some(3_500_000),
                    failed: true,
                },
            ],
        };
        let session = HarSession::from_observed(ObservedCapture {
            process_id: 123,
            process_start_ticks: 10,
            device_boot_id: "01234567-89ab-cdef-0123-456789abcdef".into(),
            start_monotonic_ns: 1_000_000,
            end_monotonic_ns: 5_000_000,
            event_count: 6,
            dropped_events: 0,
            sequence_gaps: 0,
            excluded_preboundary_events: 0,
            unknown_events: 0,
            discarded_events: 0,
            omitted_call_events: 0,
            stop_acknowledged: true,
            limit_reached: false,
            requests: vec![request],
        })
        .unwrap();
        let publication = super::super::load_page(
            &session,
            0,
            &EntryQuery::default(),
            None,
            &CancellationToken::new(),
        )
        .unwrap();
        let page = publication.page;
        assert_eq!(page.columns.len(), 17);
        assert_eq!(page.rows[0].len(), page.columns.len());
        assert_eq!(
            &page.rows[0][7..16],
            [
                "completed",
                "h2",
                "APS Network Agent v2",
                "0",
                "3",
                "2",
                "1",
                "true",
                "2"
            ]
        );
        let phases: serde_json::Value = serde_json::from_str(&page.rows[0][16]).unwrap();
        assert_eq!(phases[0]["start_ms"], 1.);
        assert_eq!(phases[0]["end_ms"], 2.);
        assert_eq!(phases[0]["duration_ms"], 1.);
        assert!(phases[1]["start_ms"].is_null());
        assert!(phases[1]["duration_ms"].is_null());
        assert_eq!(phases[1]["failed"], true);
        assert!(!format!("{:?}", page.rows).contains("01234567-89ab"));
        assert!(
            page.warnings
                .iter()
                .any(|warning| warning.contains("not the original Agent stream"))
        );
    }
}

//! Minimized Agent observations. This is neither HAR synthesis nor proof of
//! full device traffic. URLs are redacted on the device boundary before they
//! reach this contract; only allowlisted values and numeric event evidence are
//! retained.
use super::*;

pub const OBSERVED_MINIMIZATION_POLICY: &str = "aps-network-observed-minimized-v2";
pub const MAX_OBSERVED_REQUESTS: usize = 20_000;
pub const MAX_OBSERVED_PHASES: usize = 64;
pub const MAX_TLS_TEXT_CHARS: usize = 64;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ObservedOutcome {
    InFlight,
    Completed,
    Failed,
    Cancelled,
}
impl ObservedOutcome {
    pub fn label(self) -> &'static str {
        match self {
            Self::InFlight => "in-flight",
            Self::Completed => "completed",
            Self::Failed => "failed",
            Self::Cancelled => "cancelled",
        }
    }
    fn entry_outcome(self) -> EntryOutcome {
        match self {
            Self::InFlight => EntryOutcome::InFlight,
            Self::Completed => EntryOutcome::Completed,
            Self::Failed => EntryOutcome::Failed,
            Self::Cancelled => EntryOutcome::Cancelled,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, PartialOrd, Ord)]
pub enum ObservedPhaseKind {
    Proxy,
    Dns,
    Connect,
    Tls,
    RequestHeaders,
    RequestBody,
    ResponseHeaders,
    ResponseBody,
}
impl ObservedPhaseKind {
    pub fn label(self) -> &'static str {
        match self {
            Self::Proxy => "proxy",
            Self::Dns => "dns",
            Self::Connect => "connect",
            Self::Tls => "tls",
            Self::RequestHeaders => "request-headers",
            Self::RequestBody => "request-body",
            Self::ResponseHeaders => "response-headers",
            Self::ResponseBody => "response-body",
        }
    }
}

#[derive(Clone, Debug)]
pub struct ObservedPhase {
    pub kind: ObservedPhaseKind,
    pub start_ns: Option<u64>,
    pub end_ns: Option<u64>,
    pub failed: bool,
}
impl ObservedPhase {
    pub fn duration_ms(&self) -> Option<f64> {
        self.end_ns?
            .checked_sub(self.start_ns?)
            .map(|ns| ns as f64 / 1_000_000.)
    }
}

#[derive(Clone, Debug)]
pub struct ObservedRequest {
    pub entry: NetworkEntry,
    pub outcome: ObservedOutcome,
    pub start_ns: Option<u64>,
    pub end_ns: Option<u64>,
    pub protocol: Option<&'static str>,
    /// Redacted on the Agent transport; never a raw URL.
    pub redacted_url: String,
    pub tls_version: Option<String>,
    pub cipher_suite: Option<String>,
    pub cancellation_requested: bool,
    pub phases_truncated: u64,
    pub phases: Vec<ObservedPhase>,
}

#[derive(Clone, Debug)]
pub struct ObservedCapture {
    pub process_id: u32,
    pub process_start_ticks: u64,
    /// Validated kernel boot UUID. Not included in exported presentation rows.
    pub device_boot_id: String,
    pub start_monotonic_ns: u64,
    pub end_monotonic_ns: u64,
    pub event_count: u64,
    pub dropped_events: u64,
    /// Missing sequence numbers, separately reported from the Agent counter.
    /// These can describe the same loss and must not be added together.
    pub sequence_gaps: u64,
    pub excluded_preboundary_events: u64,
    pub unknown_events: u64,
    /// Received events deliberately not retained after local capture budgets.
    pub discarded_events: u64,
    /// Events for additional calls beyond the bounded request index.
    pub omitted_call_events: u64,
    pub stop_acknowledged: bool,
    pub limit_reached: bool,
    pub requests: Vec<ObservedRequest>,
}

/// Concurrency of calls with both observed endpoints. Intervals are half-open;
/// calls with missing or invalid endpoints are excluded, not treated as idle.
#[derive(Clone, Debug, PartialEq)]
pub struct ObservedConcurrency {
    pub included_calls: usize,
    pub excluded_calls: usize,
    pub peak: usize,
    pub peak_ns: Option<u64>,
    pub average: Option<f64>,
    pub timeline: Vec<(u64, usize)>,
}

/// Statistics over recorded phase occurrences, not an estimate of missing events.
#[derive(Clone, Debug, PartialEq)]
pub struct ObservedPhaseSummary {
    pub kind: ObservedPhaseKind,
    pub sample_count: usize,
    pub missing_count: usize,
    pub median_ms: Option<f64>,
    pub p95_ms: Option<f64>,
}

fn connection_use(phases: &[ObservedPhase]) -> ConnectionUse {
    if phases.is_empty() {
        return ConnectionUse::Unknown;
    }
    if phases.iter().any(|phase| {
        matches!(
            phase.kind,
            ObservedPhaseKind::Dns | ObservedPhaseKind::Connect | ObservedPhaseKind::Tls
        )
    }) {
        ConnectionUse::New
    } else {
        ConnectionUse::Reused
    }
}

fn valid_redacted_url(url: &str) -> bool {
    !url.is_empty() && url.chars().count() <= MAX_REDACTED_URL_CHARS && redact_url(url) == url
}

fn valid_tls_text(value: &Option<String>) -> bool {
    value.as_deref().is_none_or(|value| {
        !value.is_empty()
            && value.chars().count() <= MAX_TLS_TEXT_CHARS
            && !value.chars().any(char::is_control)
    })
}

impl ObservedCapture {
    pub fn phase_summaries(&self) -> Vec<ObservedPhaseSummary> {
        let mut groups: BTreeMap<ObservedPhaseKind, (Vec<f64>, usize)> = BTreeMap::new();
        for phase in self.requests.iter().flat_map(|request| &request.phases) {
            let group = groups.entry(phase.kind).or_default();
            match phase.duration_ms() {
                Some(duration) => group.0.push(duration),
                None => group.1 += 1,
            }
        }
        groups
            .into_iter()
            .map(|(kind, (mut durations, missing_count))| {
                durations.sort_by(f64::total_cmp);
                let percentile = |p: f64| {
                    (!durations.is_empty())
                        .then(|| durations[((durations.len() - 1) as f64 * p) as usize])
                };
                ObservedPhaseSummary {
                    kind,
                    sample_count: durations.len(),
                    missing_count,
                    median_ms: percentile(0.5),
                    p95_ms: percentile(0.95),
                }
            })
            .collect()
    }

    pub fn concurrency(&self) -> ObservedConcurrency {
        let mut events = Vec::new();
        for request in &self.requests {
            if let (Some(start), Some(end)) = (request.start_ns, request.end_ns)
                && end > start
            {
                events.push((start, 1i8));
                events.push((end, -1i8));
            }
        }
        events.sort_unstable(); // end before start at the same timestamp
        let included_calls = events.len() / 2;
        let mut result = ObservedConcurrency {
            included_calls,
            excluded_calls: self.requests.len() - included_calls,
            peak: 0,
            peak_ns: None,
            average: None,
            timeline: Vec::with_capacity(events.len()),
        };
        if let Some(&(first, _)) = events.first() {
            let mut last = first;
            let mut active = 0usize;
            let mut weighted = 0f64;
            for (time, delta) in events {
                weighted += active as f64 * (time - last) as f64;
                if delta < 0 {
                    active -= 1;
                } else {
                    active += 1;
                }
                result.timeline.push((time, active));
                if active > result.peak {
                    result.peak = active;
                    result.peak_ns = Some(time);
                }
                last = time;
            }
            result.average = Some(weighted / (last - first) as f64);
        }
        result
    }

    pub fn request(&self, index: u64) -> Option<&ObservedRequest> {
        // HarSession::from_observed validates strict original-index order.
        self.requests
            .binary_search_by_key(&index, |request| request.entry.index)
            .ok()
            .and_then(|position| self.requests.get(position))
    }

    pub fn outcome_count(&self, outcome: ObservedOutcome) -> u64 {
        self.requests
            .iter()
            .filter(|request| request.outcome == outcome)
            .count() as u64
    }

    pub fn incomplete_requests(&self) -> u64 {
        self.requests
            .iter()
            .filter(|request| {
                request.start_ns.is_none()
                    || request.end_ns.is_none()
                    || request.phases_truncated > 0
                    || request
                        .phases
                        .iter()
                        .any(|phase| phase.start_ns.is_none() || phase.end_ns.is_none())
            })
            .count() as u64
    }

    pub(super) fn project(&self) -> Result<(HarSummary, Vec<NetworkEntry>), NetworkError> {
        if self.process_id == 0
            || self.process_start_ticks == 0
            || self.device_boot_id.len() != 36
            || !self
                .device_boot_id
                .bytes()
                .enumerate()
                .all(|(index, byte)| {
                    if [8, 13, 18, 23].contains(&index) {
                        byte == b'-'
                    } else {
                        byte.is_ascii_hexdigit()
                    }
                })
            || self.end_monotonic_ns < self.start_monotonic_ns
            || self.requests.len() > MAX_OBSERVED_REQUESTS
            || self.event_count > 220_500
        {
            return Err(NetworkError::InvalidLimit);
        }
        let mut status_families = BTreeMap::new();
        let mut entries = Vec::with_capacity(self.requests.len());
        let mut request_bytes = ByteTotals {
            known_count: 0,
            unknown_count: 0,
            total_bytes: 0,
        };
        let mut response_bytes = ByteTotals {
            known_count: 0,
            unknown_count: 0,
            total_bytes: 0,
        };
        let mut duration_sum = 0.;
        let mut duration_count = 0;
        let mut max_duration: Option<f64> = None;
        // A canonical, length-delimited numeric/allowlisted snapshot, not raw
        // JSON or a claimed digest of the original Agent transport.
        let mut canonical = Vec::new();
        canonical.extend_from_slice(OBSERVED_MINIMIZATION_POLICY.as_bytes());
        canonical.extend_from_slice(self.device_boot_id.as_bytes());
        for number in [
            self.process_id as u64,
            self.process_start_ticks,
            self.start_monotonic_ns,
            self.end_monotonic_ns,
            self.event_count,
            self.dropped_events,
            self.sequence_gaps,
            self.excluded_preboundary_events,
            self.unknown_events,
            self.discarded_events,
            self.omitted_call_events,
            u64::from(self.stop_acknowledged),
            u64::from(self.limit_reached),
        ] {
            canonical.extend_from_slice(&number.to_be_bytes());
        }
        let mut previous_index = None;
        for request in &self.requests {
            let entry = &request.entry;
            if previous_index.is_some_and(|previous| entry.index <= previous)
                || !matches!(
                    entry.method,
                    "GET"
                        | "POST"
                        | "PUT"
                        | "PATCH"
                        | "DELETE"
                        | "HEAD"
                        | "OPTIONS"
                        | "CONNECT"
                        | "TRACE"
                        | "OTHER"
                )
                || !valid_redacted_url(&request.redacted_url)
                || entry.redacted_url != request.redacted_url
                || !valid_tls_text(&request.tls_version)
                || !valid_tls_text(&request.cipher_suite)
                || !(entry.status == 0 || (100..600).contains(&entry.status))
                || request.phases.len() > MAX_OBSERVED_PHASES
                || request.protocol.is_some_and(|protocol| {
                    !matches!(
                        protocol,
                        "http/1.0" | "http/1.1" | "h2" | "h2_prior_knowledge" | "spdy/3.1" | "quic"
                    )
                })
            {
                return Err(NetworkError::InvalidHar);
            }
            previous_index = Some(entry.index);
            let valid_time = |time: Option<u64>| {
                time.is_none_or(|time| {
                    (self.start_monotonic_ns..=self.end_monotonic_ns).contains(&time)
                })
            };
            if !valid_time(request.start_ns)
                || !valid_time(request.end_ns)
                || request
                    .start_ns
                    .zip(request.end_ns)
                    .is_some_and(|(start, end)| end < start)
                || request.phases.iter().any(|phase| {
                    !valid_time(phase.start_ns)
                        || !valid_time(phase.end_ns)
                        || phase
                            .start_ns
                            .zip(phase.end_ns)
                            .is_some_and(|(start, end)| end < start)
                })
            {
                return Err(NetworkError::InvalidHar);
            }
            let expected_duration = request
                .start_ns
                .zip(request.end_ns)
                .map(|(start, end)| (end - start) as f64 / 1_000_000.);
            if entry.duration_ms != expected_duration {
                return Err(NetworkError::InvalidHar);
            }
            let family = if entry.status == 0 {
                "unknown".into()
            } else {
                format!("{}xx", entry.status / 100)
            };
            *status_families.entry(family).or_insert(0) += 1;
            for (value, totals) in [
                (entry.request_bytes, &mut request_bytes),
                (entry.response_bytes, &mut response_bytes),
            ] {
                match value {
                    Some(value) => {
                        totals.known_count += 1;
                        totals.total_bytes = totals.total_bytes.saturating_add(value);
                    }
                    None => totals.unknown_count += 1,
                }
            }
            if let Some(duration) = entry.duration_ms {
                duration_count += 1;
                duration_sum += duration;
                max_duration =
                    Some(max_duration.map_or(duration, |previous| previous.max(duration)));
            }
            canonical.extend_from_slice(&entry.index.to_be_bytes());
            canonical.push(entry.method.len() as u8);
            canonical.extend_from_slice(entry.method.as_bytes());
            canonical.extend_from_slice(&entry.status.to_be_bytes());
            canonical.push(request.redacted_url.len() as u8);
            canonical.extend_from_slice(request.redacted_url.as_bytes());
            for value in [
                request.start_ns,
                request.end_ns,
                entry.request_bytes,
                entry.response_bytes,
            ] {
                canonical.push(u8::from(value.is_some()));
                canonical.extend_from_slice(&value.unwrap_or_default().to_be_bytes());
            }
            canonical.push(request.outcome as u8);
            canonical.push(u8::from(request.cancellation_requested));
            let protocol = request.protocol.unwrap_or("");
            canonical.push(protocol.len() as u8);
            canonical.extend_from_slice(protocol.as_bytes());
            for value in [&request.tls_version, &request.cipher_suite] {
                match value {
                    Some(value) => {
                        canonical.push(value.len() as u8);
                        canonical.extend_from_slice(value.as_bytes());
                    }
                    None => canonical.push(0),
                }
            }
            canonical.extend_from_slice(&request.phases_truncated.to_be_bytes());
            canonical.push(request.phases.len() as u8);
            for phase in &request.phases {
                canonical.push(phase.kind as u8);
                canonical.push(u8::from(phase.failed));
                for value in [phase.start_ns, phase.end_ns] {
                    canonical.push(u8::from(value.is_some()));
                    canonical.extend_from_slice(&value.unwrap_or_default().to_be_bytes());
                }
            }
            entries.push(NetworkEntry {
                redacted_url: request.redacted_url.clone(),
                protocol: request.protocol.map(str::to_owned),
                outcome: request.outcome.entry_outcome(),
                connection_use: connection_use(&request.phases),
                ..entry.clone()
            });
        }
        let digest = SourceDigest::hash_reader(canonical.as_slice(), 64 * 1024 * 1024)
            .map_err(|_| NetworkError::InvalidLimit)?;
        let entry_count = entries.len() as u64;
        Ok((
            HarSummary {
                har_version: "agent-v2".into(),
                entry_count,
                status_families,
                request_bytes,
                response_bytes,
                decoded_response_bytes: ByteTotals {
                    known_count: 0,
                    unknown_count: entry_count,
                    total_bytes: 0,
                },
                mean_duration_ms: (duration_count > 0)
                    .then(|| duration_sum / duration_count as f64),
                max_duration_ms: max_duration,
                valid_duration_count: duration_count,
                missing_duration_count: entry_count - duration_count,
                invalid_duration_count: 0,
                minimization_policy: OBSERVED_MINIMIZATION_POLICY,
                source_digest: digest,
            },
            entries,
        ))
    }
}

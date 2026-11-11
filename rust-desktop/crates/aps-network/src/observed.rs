//! Minimized Agent observations. This is neither HAR synthesis nor proof of
//! full device traffic. Only allowlisted values and numeric event evidence are
//! retained; arbitrary Agent strings never belong in these contracts.
use super::*;

pub const OBSERVED_MINIMIZATION_POLICY: &str = "aps-network-observed-minimized-v1";
pub const MAX_OBSERVED_REQUESTS: usize = 20_000;
pub const MAX_OBSERVED_PHASES: usize = 64;

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

impl ObservedCapture {
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
                || entry.minimized_url != MINIMIZED_URL
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
            entries.push(entry.clone());
        }
        let digest = SourceDigest::hash_reader(canonical.as_slice(), 64 * 1024 * 1024)
            .map_err(|_| NetworkError::InvalidLimit)?;
        let entry_count = entries.len() as u64;
        Ok((
            HarSummary {
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

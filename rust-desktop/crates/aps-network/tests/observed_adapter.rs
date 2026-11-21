use aps_network::{
    ConnectionUse, EntryOutcome, EntryQuery, EntrySort, HarSession, NetworkEntry, ObservedCapture,
    ObservedOutcome, ObservedRequest, OutcomeFilter, StatusFilter,
};
use aps_platform::CancellationToken;

const REDACTED_URL: &str = "https://example.test/<redacted-path>?q=<redacted>";

fn request(
    index: u64,
    outcome: ObservedOutcome,
    status: u16,
    phases: Vec<aps_network::ObservedPhase>,
) -> ObservedRequest {
    let end_ns = (outcome != ObservedOutcome::InFlight).then_some(3_000_000);
    ObservedRequest {
        entry: NetworkEntry {
            index,
            method: "GET",
            status,
            duration_ms: end_ns.map(|_| 2.),
            request_bytes: None,
            response_bytes: None,
            redacted_url: REDACTED_URL.into(),
            protocol: Some("h2".into()),
            outcome: EntryOutcome::Completed,
            connection_use: ConnectionUse::Unknown,
            started_at_ms: None,
        },
        outcome,
        start_ns: Some(1_000_000),
        end_ns,
        protocol: Some("h2"),
        redacted_url: REDACTED_URL.into(),
        tls_version: None,
        cipher_suite: None,
        cancellation_requested: outcome == ObservedOutcome::Cancelled,
        phases_truncated: 0,
        phases,
    }
}

fn observed_session() -> HarSession {
    let requests = [
        (ObservedOutcome::Completed, 500),
        (ObservedOutcome::Failed, 0),
        (ObservedOutcome::Completed, 204),
        (ObservedOutcome::Cancelled, 0),
        (ObservedOutcome::InFlight, 0),
    ]
    .into_iter()
    .enumerate()
    .map(|(index, (outcome, status))| request(index as u64 + 1, outcome, status, Vec::new()))
    .collect();
    HarSession::from_observed(ObservedCapture {
        origin: aps_network::ObservedOrigin::Agent,
        process_id: 123,
        process_start_ticks: 10,
        device_boot_id: "01234567-89ab-cdef-0123-456789abcdef".into(),
        start_monotonic_ns: 1_000_000,
        end_monotonic_ns: 4_000_000,
        event_count: 9,
        dropped_events: 0,
        sequence_gaps: 0,
        excluded_preboundary_events: 0,
        unknown_events: 0,
        discarded_events: 0,
        omitted_call_events: 0,
        stop_acknowledged: true,
        limit_reached: false,
        requests,
    })
    .unwrap()
}

#[test]
fn concurrency_excludes_inflight_and_uses_half_open_intervals() {
    let session = observed_session();
    let mut capture = session.observed().unwrap().clone();
    capture.requests[0].start_ns = Some(1);
    capture.requests[0].end_ns = Some(3);
    capture.requests[1].start_ns = Some(2);
    capture.requests[1].end_ns = Some(3);
    capture.requests[2].start_ns = Some(3);
    capture.requests[2].end_ns = Some(4);
    capture.requests[3].start_ns = Some(4);
    capture.requests[3].end_ns = Some(4);
    let summary = capture.concurrency();
    assert_eq!((summary.included_calls, summary.excluded_calls), (3, 2));
    assert_eq!((summary.peak, summary.peak_ns), (2, Some(2)));
    assert_eq!(summary.average, Some(4.0 / 3.0));
    assert_eq!(
        summary.timeline,
        vec![(1, 1), (2, 2), (3, 1), (3, 0), (3, 1), (4, 0)]
    );
}

#[test]
fn observed_outcomes_filter_the_whole_session_before_paging() {
    let session = observed_session();
    let cancel = CancellationToken::new();
    let query = EntryQuery {
        outcome: OutcomeFilter::Completed,
        sort: EntrySort::DurationDescending,
        ..EntryQuery::default()
    };
    let first = session.query_entries(0, 1, &query, &cancel).unwrap();
    let next = session.query_entries(1, 1, &query, &cancel).unwrap();
    assert_eq!(first.total, 2);
    assert_eq!(next.total, 2);
    assert_eq!(first.rows[0].index, 1);
    assert_eq!(first.rows[0].status, 500);
    assert_eq!(next.rows[0].index, 3);
    assert_eq!(first.source_digest, next.source_digest);
}

#[test]
fn transport_outcomes_are_independent_of_http_status() {
    let session = observed_session();
    let cancel = CancellationToken::new();
    for (outcome, expected) in [
        (OutcomeFilter::Failed, 2),
        (OutcomeFilter::Cancelled, 4),
        (OutcomeFilter::InFlight, 5),
    ] {
        let page = session
            .query_entries(
                0,
                10,
                &EntryQuery {
                    outcome,
                    ..EntryQuery::default()
                },
                &cancel,
            )
            .unwrap();
        assert_eq!(page.total, 1);
        assert_eq!(page.rows[0].index, expected);
    }
    let page = session
        .query_entries(
            0,
            10,
            &EntryQuery {
                outcome: OutcomeFilter::Completed,
                status: StatusFilter::ServerError5xx,
                ..EntryQuery::default()
            },
            &cancel,
        )
        .unwrap();
    assert_eq!(page.total, 1);
    assert_eq!(page.rows[0].index, 1);
}

#[test]
fn analysis_reports_outcomes_connection_use_and_timing_coverage() {
    let session = observed_session();
    let cancel = CancellationToken::new();
    let analysis = session.analysis(&cancel).unwrap();
    assert_eq!(
        (
            analysis.completed_count,
            analysis.failed_count,
            analysis.cancelled_count,
            analysis.in_flight_count
        ),
        (2, 1, 1, 1)
    );
    assert_eq!(
        (
            analysis.new_connection_count,
            analysis.reused_connection_count,
            analysis.unknown_connection_count
        ),
        (0, 0, 5)
    );
    let expected_p90 = 2.0;
    assert_eq!(analysis.p90_ms, Some(expected_p90));
    assert_eq!(analysis.started_at_ms, None);
}

#[test]
fn domains_and_activity_use_redacted_authority_and_observed_intervals() {
    let session = observed_session();
    let cancel = CancellationToken::new();
    let domains = session
        .domains(&EntryQuery::default(), 10, &cancel)
        .unwrap();
    assert_eq!(domains.len(), 1);
    assert_eq!(domains[0].domain.as_deref(), Some("example.test"));
    assert_eq!(domains[0].call_count, 5);
    assert_eq!(domains[0].failure_count, 1);
    assert_eq!(domains[0].transferred_bytes, None);
    let activity = session.activity(4, &cancel).unwrap().unwrap();
    assert_eq!(activity.timed_calls, 4);
    assert_eq!(activity.omitted_calls, 1);
    assert_eq!(activity.peak_concurrency, 4);
    assert_eq!(activity.buckets.len(), 4);
}

#[test]
fn observed_detail_exposes_phase_evidence_and_tls_without_headers() {
    let session = observed_session();
    let cancel = CancellationToken::new();
    let mut capture = session.observed().unwrap().clone();
    capture.requests[1].phases.push(aps_network::ObservedPhase {
        kind: aps_network::ObservedPhaseKind::Dns,
        start_ns: Some(1_100_000),
        end_ns: Some(1_300_000),
        failed: false,
    });
    capture.requests[1].tls_version = Some("TLSv1.3".into());
    capture.requests[1].cipher_suite = Some("TLS_AES_128_GCM_SHA256".into());
    let session = HarSession::from_observed(capture).unwrap();
    let detail = session.entry_detail(2, &cancel).unwrap().unwrap();
    assert!(detail.request_headers.is_empty() && detail.response_headers.is_empty());
    assert_eq!(detail.tls_version.as_deref(), Some("TLSv1.3"));
    assert_eq!(
        detail.cipher_suite.as_deref(),
        Some("TLS_AES_128_GCM_SHA256")
    );
    assert!(
        detail
            .phases
            .iter()
            .any(|phase| phase.kind == aps_network::PhaseKind::Dns
                && phase.duration_ms == Some(0.2)
                && phase.confidence == aps_network::PhaseConfidence::Exact)
    );
    assert!(
        detail
            .phases
            .iter()
            .any(|phase| phase.kind == aps_network::PhaseKind::Total)
    );
}

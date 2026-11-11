use aps_network::{
    EntryQuery, EntrySort, HarSession, MINIMIZED_URL, NetworkEntry, ObservedCapture,
    ObservedOutcome, ObservedRequest, OutcomeFilter, StatusFilter,
};
use aps_platform::CancellationToken;

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
    .map(|(index, (outcome, status))| {
        let end_ns = (outcome != ObservedOutcome::InFlight).then_some(3_000_000);
        ObservedRequest {
            entry: NetworkEntry {
                index: index as u64 + 1,
                method: "GET",
                status,
                duration_ms: end_ns.map(|_| 2.),
                request_bytes: None,
                response_bytes: None,
                minimized_url: MINIMIZED_URL,
            },
            outcome,
            start_ns: Some(1_000_000),
            end_ns,
            protocol: Some("h2"),
            cancellation_requested: outcome == ObservedOutcome::Cancelled,
            phases_truncated: 0,
            phases: Vec::new(),
        }
    })
    .collect();
    HarSession::from_observed(ObservedCapture {
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

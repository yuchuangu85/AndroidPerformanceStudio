use aps_network::{
    EntryQuery, EntrySort, HarSession, ImportLimits, MAX_PAGE_SIZE, MAX_QUERY_BYTES, MINIMIZED_URL,
    NetworkError, StatusFilter,
};
use aps_platform::CancellationToken;
use std::fs::{self, File, OpenOptions};
use std::io::Write;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering};

static NEXT: AtomicU64 = AtomicU64::new(0);
struct Fixture(PathBuf);
impl Fixture {
    fn new(bytes: &[u8]) -> Self {
        let path = std::env::temp_dir().join(format!(
            "aps-network-adapter-{}-{}.har",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(&path)
            .unwrap()
            .write_all(bytes)
            .unwrap();
        Self(path)
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.0);
    }
}

const ENTRY: &str = r#"{"startedDateTime":"secret-timestamp","time":12.5,"request":{"method":"GET","url":"https://alice:password@example.test/users/private?token=topsecret#secret","bodySize":3,"headers":[{"name":"Authorization","value":"Bearer credential"}],"postData":{"text":"sensitive body"}},"response":{"status":200,"bodySize":9,"content":{"size":18,"text":"private response"}},"timings":{"wait":10}}"#;
fn har(entries: &[&str]) -> String {
    format!(
        r#"{{"log":{{"version":"1.2","creator":{{"name":"secret creator","version":"1"}},"entries":[{}]}}}}"#,
        entries.join(",")
    )
}

#[test]
fn summary_and_pages_reuse_the_engine_without_exposing_traffic() {
    let file = Fixture::new(har(&[ENTRY, ENTRY, ENTRY]).as_bytes());
    let cancel = CancellationToken::new();
    let session = HarSession::open(&file.0, ImportLimits::default(), &cancel).unwrap();
    assert_eq!(session.summary().entry_count, 3);
    assert_eq!(session.summary().response_bytes.total_bytes, 27);
    let page = session.entries(1, 1, &cancel).unwrap();
    assert_eq!(page.total, 3);
    assert_eq!(page.rows.len(), 1);
    assert_eq!(page.rows[0].index, 1);
    assert_eq!(page.rows[0].minimized_url, MINIMIZED_URL);
    assert_eq!(page.rows[0].duration_ms, Some(12.5));
    let presentation = format!("{:?}{:?}", session.summary(), page);
    for secret in [
        "alice",
        "password",
        "example.test",
        "users/private",
        "topsecret",
        "Authorization",
        "credential",
        "sensitive body",
        "private response",
        "secret-timestamp",
        "secret creator",
    ] {
        assert!(!presentation.contains(secret), "leaked {secret}");
    }
}

#[test]
fn arbitrary_method_is_not_a_secret_escape_hatch() {
    let entry = ENTRY.replace("\"GET\"", "\"super-secret-method\"");
    let file = Fixture::new(har(&[&entry]).as_bytes());
    let cancel = CancellationToken::new();
    let session = HarSession::open(&file.0, ImportLimits::default(), &cancel).unwrap();
    assert_eq!(
        session.entries(0, 1, &cancel).unwrap().rows[0].method,
        "OTHER"
    );
}

#[test]
fn invalid_tail_and_lowered_entry_budget_discard_results() {
    let bad = Fixture::new(format!("{} private trailing", har(&[ENTRY])).as_bytes());
    let cancel = CancellationToken::new();
    assert!(matches!(
        HarSession::open(&bad.0, ImportLimits::default(), &cancel),
        Err(NetworkError::InvalidHar)
    ));
    let file = Fixture::new(har(&[ENTRY, ENTRY]).as_bytes());
    assert!(matches!(
        HarSession::open(
            &file.0,
            ImportLimits {
                max_entries: 1,
                ..ImportLimits::default()
            },
            &cancel
        ),
        Err(NetworkError::InvalidHar)
    ));
}

#[test]
fn page_source_and_cancel_boundaries_are_explicit() {
    let file = Fixture::new(har(&[ENTRY]).as_bytes());
    let cancel = CancellationToken::new();
    let session = HarSession::open(&file.0, ImportLimits::default(), &cancel).unwrap();
    assert!(matches!(
        session.entries(0, 0, &cancel),
        Err(NetworkError::InvalidPage)
    ));
    assert!(matches!(
        session.entries(0, MAX_PAGE_SIZE + 1, &cancel),
        Err(NetworkError::InvalidPage)
    ));
    assert!(session.entries(10, 1, &cancel).unwrap().rows.is_empty());
    OpenOptions::new()
        .append(true)
        .open(&file.0)
        .unwrap()
        .write_all(b" ")
        .unwrap();
    assert!(matches!(
        session.entries(0, 1, &cancel),
        Err(NetworkError::SourceChanged)
    ));
    cancel.cancel();
    assert!(matches!(
        session.entries(0, 1, &cancel),
        Err(NetworkError::Cancelled)
    ));
}

#[test]
fn whole_session_query_filters_before_paging_not_after() {
    let entries = (0..235)
        .map(|index| {
            ENTRY
                .replace(
                    "\"GET\"",
                    if index % 2 == 0 {
                        "\"POST\""
                    } else {
                        "\"GET\""
                    },
                )
                .replace(
                    "\"status\":200",
                    if index < 130 {
                        "\"status\":200"
                    } else {
                        "\"status\":503"
                    },
                )
        })
        .collect::<Vec<_>>();
    let raw = har(&entries.iter().map(String::as_str).collect::<Vec<_>>());
    let file = Fixture::new(raw.as_bytes());
    let cancel = CancellationToken::new();
    let session = HarSession::open(&file.0, ImportLimits::default(), &cancel).unwrap();
    let query = EntryQuery {
        status: StatusFilter::ServerError5xx,
        ..EntryQuery::default()
    };
    let first = session.query_entries(0, 100, &query, &cancel).unwrap();
    assert_eq!(first.total, 105);
    assert_eq!(first.rows[0].index, 130);
    assert_eq!(first.rows.last().unwrap().index, 229);
    let second = session.query_entries(100, 100, &query, &cancel).unwrap();
    assert_eq!(second.total, 105);
    assert_eq!(second.offset, 100);
    assert_eq!(
        second.rows.iter().map(|row| row.index).collect::<Vec<_>>(),
        [230, 231, 232, 233, 234]
    );
    assert_eq!(second.source_digest, session.summary().source_digest);
    let method_query = EntryQuery {
        method: Some("POST".into()),
        search: "  pOsT  ".into(),
        ..query
    };
    let page = session
        .query_entries(0, 100, &method_query, &cancel)
        .unwrap();
    assert_eq!(page.total, 53);
    assert!(
        page.rows
            .iter()
            .all(|row| row.method == "POST" && row.status == 503)
    );
}

#[test]
fn query_sort_is_global_stable_and_unknown_values_sort_last() {
    let entries = [
        ENTRY
            .replace("\"time\":12.5", "\"time\":-1")
            .replace("\"bodySize\":9", "\"bodySize\":-1"),
        ENTRY.replace("\"time\":12.5", "\"time\":500"),
        ENTRY.replace("\"time\":12.5", "\"time\":500"),
        ENTRY
            .replace("\"time\":12.5", "\"time\":0")
            .replace("\"bodySize\":9", "\"bodySize\":100"),
        ENTRY.replace("\"time\":12.5", "\"time\":-3"),
    ];
    let file =
        Fixture::new(har(&entries.iter().map(String::as_str).collect::<Vec<_>>()).as_bytes());
    let cancel = CancellationToken::new();
    let session = HarSession::open(&file.0, ImportLimits::default(), &cancel).unwrap();
    let duration = EntryQuery {
        sort: EntrySort::DurationDescending,
        ..EntryQuery::default()
    };
    let page = session.query_entries(0, 5, &duration, &cancel).unwrap();
    assert_eq!(
        page.rows.iter().map(|row| row.index).collect::<Vec<_>>(),
        [1, 2, 3, 0, 4]
    );
    assert_eq!(page.rows[3].duration_ms, None);
    assert_eq!(page.rows[4].duration_ms, None);
    let bytes = EntryQuery {
        sort: EntrySort::ResponseBytesDescending,
        ..EntryQuery::default()
    };
    let page = session.query_entries(1, 2, &bytes, &cancel).unwrap();
    assert_eq!(page.total, 5);
    assert_eq!(
        page.rows.iter().map(|row| row.index).collect::<Vec<_>>(),
        [1, 2]
    );
    assert_eq!(
        session.query_entries(4, 1, &bytes, &cancel).unwrap().rows[0].index,
        0
    );
}

#[test]
fn unknown_status_does_not_become_a_success_or_failure_outcome() {
    let unknown = ENTRY.replace("\"status\":200", "\"status\":0");
    let client = ENTRY.replace("\"status\":200", "\"status\":404");
    let file = Fixture::new(har(&[ENTRY, &unknown, &client]).as_bytes());
    let cancel = CancellationToken::new();
    let session = HarSession::open(&file.0, ImportLimits::default(), &cancel).unwrap();
    for (status, index) in [
        (StatusFilter::Success2xx3xx, 0),
        (StatusFilter::Unknown, 1),
        (StatusFilter::ClientError4xx, 2),
    ] {
        let page = session
            .query_entries(
                0,
                10,
                &EntryQuery {
                    status,
                    ..EntryQuery::default()
                },
                &cancel,
            )
            .unwrap();
        assert_eq!(page.total, 1);
        assert_eq!(page.rows[0].index, index);
    }
}

#[test]
fn query_never_searches_or_returns_raw_traffic_or_producer_values() {
    let file = Fixture::new(har(&[ENTRY]).as_bytes());
    let cancel = CancellationToken::new();
    let session = HarSession::open(&file.0, ImportLimits::default(), &cancel).unwrap();
    let page = session
        .query_entries(0, 100, &EntryQuery::default(), &cancel)
        .unwrap();
    let presentation = format!("{page:?}");
    for secret in [
        "alice",
        "password",
        "example.test",
        "topsecret",
        "credential",
        "sensitive body",
        "private response",
        "secret-timestamp",
        "secret creator",
    ] {
        assert!(!presentation.contains(secret));
        let query = EntryQuery {
            search: secret.into(),
            ..EntryQuery::default()
        };
        assert_eq!(
            session
                .query_entries(0, 100, &query, &cancel)
                .unwrap()
                .total,
            0
        );
    }
    assert_eq!(
        session
            .query_entries(
                0,
                100,
                &EntryQuery {
                    search: "minimized-url".into(),
                    ..EntryQuery::default()
                },
                &cancel
            )
            .unwrap()
            .total,
        1
    );
}

#[test]
fn query_input_and_cached_index_source_boundaries_fail_closed() {
    let file = Fixture::new(har(&[ENTRY]).as_bytes());
    let cancel = CancellationToken::new();
    let session = HarSession::open(&file.0, ImportLimits::default(), &cancel).unwrap();
    let default = EntryQuery::default();
    session.query_entries(0, 10, &default, &cancel).unwrap();
    for query in [
        EntryQuery {
            search: "x".repeat(MAX_QUERY_BYTES + 1),
            ..default.clone()
        },
        EntryQuery {
            method: Some("arbitrary-secret-method".into()),
            ..default.clone()
        },
    ] {
        assert!(matches!(
            session.query_entries(0, 10, &query, &cancel),
            Err(NetworkError::InvalidQuery)
        ));
    }
    for (offset, limit) in [(0, 0), (0, MAX_PAGE_SIZE + 1), (usize::MAX, 1)] {
        assert!(matches!(
            session.query_entries(offset, limit, &default, &cancel),
            Err(NetworkError::InvalidPage)
        ));
    }
    assert!(
        session
            .query_entries(20, 10, &default, &cancel)
            .unwrap()
            .rows
            .is_empty()
    );
    // Preserve length and mtime: digest validation must invalidate an already
    // populated minimized index, not only the normal page cache.
    let stamp = fs::metadata(&file.0).unwrap().modified().unwrap();
    fs::write(
        &file.0,
        har(&[&ENTRY.replace("\"status\":200", "\"status\":503")]),
    )
    .unwrap();
    File::options()
        .write(true)
        .open(&file.0)
        .unwrap()
        .set_modified(stamp)
        .unwrap();
    assert!(matches!(
        session.query_entries(0, 10, &default, &cancel),
        Err(NetworkError::SourceChanged)
    ));
    cancel.cancel();
    assert!(matches!(
        session.query_entries(0, 10, &default, &cancel),
        Err(NetworkError::Cancelled)
    ));
}

#[test]
fn duration_percentiles_interpolate_known_producer_timings() {
    let entries = [0, 10, 20, 30]
        .map(|duration| ENTRY.replace("\"time\":12.5", &format!("\"time\":{duration}")));
    let file =
        Fixture::new(har(&entries.iter().map(String::as_str).collect::<Vec<_>>()).as_bytes());
    let cancel = CancellationToken::new();
    let session = HarSession::open(&file.0, ImportLimits::default(), &cancel).unwrap();
    // Populate the shared index through a different API first.
    session
        .query_entries(0, 2, &EntryQuery::default(), &cancel)
        .unwrap();
    let percentiles = session.duration_percentiles(&cancel).unwrap();
    assert_eq!(percentiles.sample_count, 4);
    assert_eq!(percentiles.missing_count, 0);
    assert_eq!(percentiles.median_ms, Some(15.0));
    assert!((percentiles.p95_ms.unwrap() - 28.5).abs() < 0.000_001);
}

#[test]
fn duration_percentiles_preserve_empty_singleton_and_missing_semantics() {
    let cancel = CancellationToken::new();
    let empty = Fixture::new(har(&[]).as_bytes());
    let session = HarSession::open(&empty.0, ImportLimits::default(), &cancel).unwrap();
    let values = session.duration_percentiles(&cancel).unwrap();
    assert_eq!(values.sample_count, 0);
    assert_eq!(values.missing_count, 0);
    assert_eq!(values.median_ms, None);
    assert_eq!(values.p95_ms, None);
    let absent = ENTRY.replace("\"time\":12.5,", "");
    let unknown = ENTRY.replace("\"time\":12.5", "\"time\":-1");
    let invalid = ENTRY.replace("\"time\":12.5", "\"time\":-3");
    let mixed = Fixture::new(har(&[ENTRY, &absent, &unknown, &invalid]).as_bytes());
    let session = HarSession::open(&mixed.0, ImportLimits::default(), &cancel).unwrap();
    let values = session.duration_percentiles(&cancel).unwrap();
    assert_eq!(values.sample_count, 1);
    assert_eq!(values.missing_count, 3);
    assert_eq!(values.median_ms, Some(12.5));
    assert_eq!(values.p95_ms, Some(12.5));
    let missing = Fixture::new(har(&[&absent, &unknown, &invalid]).as_bytes());
    let session = HarSession::open(&missing.0, ImportLimits::default(), &cancel).unwrap();
    let values = session.duration_percentiles(&cancel).unwrap();
    assert_eq!(values.sample_count, 0);
    assert_eq!(values.missing_count, 3);
    assert_eq!(values.median_ms, None);
    assert_eq!(values.p95_ms, None);
}

#[test]
fn cached_duration_percentiles_revalidate_source_and_cancellation() {
    let file = Fixture::new(har(&[ENTRY]).as_bytes());
    let cancel = CancellationToken::new();
    let session = HarSession::open(&file.0, ImportLimits::default(), &cancel).unwrap();
    assert_eq!(
        session.duration_percentiles(&cancel).unwrap().median_ms,
        Some(12.5)
    );
    let stamp = fs::metadata(&file.0).unwrap().modified().unwrap();
    fs::write(
        &file.0,
        har(&[&ENTRY.replace("\"time\":12.5", "\"time\":22.5")]),
    )
    .unwrap();
    File::options()
        .write(true)
        .open(&file.0)
        .unwrap()
        .set_modified(stamp)
        .unwrap();
    assert!(matches!(
        session.duration_percentiles(&cancel),
        Err(NetworkError::SourceChanged)
    ));
    cancel.cancel();
    assert!(matches!(
        session.duration_percentiles(&cancel),
        Err(NetworkError::Cancelled)
    ));
}

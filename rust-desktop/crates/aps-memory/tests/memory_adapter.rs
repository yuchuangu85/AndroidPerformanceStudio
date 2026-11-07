use aps_hprof_engine::{
    cache::sha256_file,
    wire::{self, Value},
};
use aps_memory::{
    FindingKind, HistogramQuery, HistogramSort, MAX_HISTOGRAM_SEARCH_BYTES, MemoryError,
    MemorySession, MemoryValue, worker,
};
use aps_platform::CancellationToken;
use std::fs::{self, OpenOptions};
use std::io::{Cursor, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

static NEXT: AtomicU64 = AtomicU64::new(0);
struct Fixture(PathBuf);
impl Fixture {
    fn empty_heap() -> Self {
        let path = std::env::temp_dir().join(format!(
            "aps-memory-adapter-{}-{}.hprof",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        let mut bytes = b"JAVA PROFILE 1.0.3\0".to_vec();
        bytes.extend(4u32.to_be_bytes());
        bytes.extend(1u64.to_be_bytes());
        OpenOptions::new()
            .create_new(true)
            .write(true)
            .open(&path)
            .unwrap()
            .write_all(&bytes)
            .unwrap();
        Self(path)
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.0);
    }
}

#[test]
fn isolated_session_exposes_empty_real_engine_views_and_releases_worker() {
    let file = Fixture::empty_heap();
    let digest = sha256_file(&file.0).unwrap();
    let cancel = CancellationToken::new();
    let executable = Path::new(env!("CARGO_BIN_EXE_aps-memory-worker"));
    let mut session = MemorySession::open(executable, &file.0, &digest, &cancel).unwrap();
    assert_eq!(
        session.metadata().get("classCount").unwrap(),
        &MemoryValue::Int(0)
    );
    assert_eq!(
        session
            .histogram(None, 0, 1, &cancel)
            .unwrap()
            .get("total")
            .unwrap(),
        &Value::Int(0)
    );
    assert!(
        session
            .instances(&["missing.Class".into()], None, 0, 1, &cancel)
            .unwrap()
            .get("rows")
            .unwrap()
            .as_list()
            .unwrap()
            .is_empty()
    );
    assert!(
        session
            .dominators(None, 0, 1, &cancel)
            .unwrap()
            .get("rows")
            .unwrap()
            .as_list()
            .unwrap()
            .is_empty()
    );
    assert_eq!(session.detail(123, 0, 1, &cancel).unwrap(), Value::Null);
    assert!(
        session
            .findings(FindingKind::LeakSuspects, 0, 1, &cancel)
            .unwrap()
            .get("rows")
            .unwrap()
            .as_list()
            .unwrap()
            .is_empty()
    );
    assert_eq!(
        session.histogram(None, 0, 201, &cancel),
        Err(MemoryError::InvalidPage)
    );
    cancel.cancel();
    assert_eq!(
        session.histogram(None, 0, 1, &cancel),
        Err(MemoryError::Cancelled)
    );
    assert_eq!(
        session.histogram(None, 0, 1, &CancellationToken::new()),
        Err(MemoryError::Closed)
    );
}

#[test]
fn worker_checks_digest_and_never_reflects_engine_error_or_path() {
    let file = Fixture::empty_heap();
    let digest = "0".repeat(64);
    let request = Value::map([
        ("version", 1i64.into()),
        ("id", 1i64.into()),
        ("op", "open".into()),
        ("sourceDigest", digest.clone().into()),
        (
            "args",
            Value::map([
                ("path", file.0.to_str().unwrap().into()),
                ("sourceDigest", digest.into()),
                ("mapping", Value::map([])),
                ("cacheDir", Value::Null),
                ("graphStorage", "memory".into()),
            ]),
        ),
    ]);
    let mut input = Vec::new();
    wire::write_frame(&mut input, &request).unwrap();
    let mut output = Vec::new();
    worker::serve(&mut Cursor::new(input), &mut output).unwrap();
    let response =
        wire::decode(&wire::read_frame(&mut Cursor::new(output)).unwrap().unwrap()).unwrap();
    assert_eq!(response.get("status").unwrap().as_str().unwrap(), "error");
    assert!(!format!("{response:?}").contains(file.0.to_str().unwrap()));
}

#[test]
fn pre_cancelled_open_does_not_need_a_worker() {
    let file = Fixture::empty_heap();
    let cancel = CancellationToken::new();
    cancel.cancel();
    assert!(matches!(
        MemorySession::open(
            Path::new("missing-worker"),
            &file.0,
            &"0".repeat(64),
            &cancel
        ),
        Err(MemoryError::Cancelled)
    ));
}

#[test]
fn whole_histogram_query_is_worker_owned_and_empty_heap_stays_empty() {
    let file = Fixture::empty_heap();
    let digest = sha256_file(&file.0).unwrap();
    let cancel = CancellationToken::new();
    let executable = Path::new(env!("CARGO_BIN_EXE_aps-memory-worker"));
    let mut session = MemorySession::open(executable, &file.0, &digest, &cancel).unwrap();
    let query = HistogramQuery {
        search: "Activity".into(),
        match_case: true,
        sort: HistogramSort::ShallowDescending,
    };
    let page = session
        .histogram_query(None, &query, 0, 10, &cancel)
        .unwrap();
    assert_eq!(page.get("total").unwrap().as_int().unwrap(), 0);
    assert!(page.get("rows").unwrap().as_list().unwrap().is_empty());
    let summary = page.get("summary").unwrap();
    assert_eq!(summary.get("classCount").unwrap().as_int().unwrap(), 0);
    let invalid = HistogramQuery {
        search: "x".repeat(MAX_HISTOGRAM_SEARCH_BYTES + 1),
        ..query
    };
    assert_eq!(
        session.histogram_query(None, &invalid, 0, 10, &cancel),
        Err(MemoryError::InvalidInput)
    );
    // Rejection at the parent boundary does not kill a valid live session.
    assert_eq!(
        session
            .histogram(None, 0, 10, &cancel)
            .unwrap()
            .get("total")
            .unwrap()
            .as_int()
            .unwrap(),
        0
    );
}

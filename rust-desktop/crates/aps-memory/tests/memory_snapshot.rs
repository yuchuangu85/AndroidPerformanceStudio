//! End-to-end typed snapshot session tests against a real (empty) HPROF input.

use aps_hprof_engine::{cache::sha256_file, wire};
use aps_memory::{MAX_UI_PAGE_SIZE, MemoryError, MemorySession, worker};
use aps_platform::CancellationToken;
use std::fs::{self, OpenOptions};
use std::io::Write;
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

static NEXT: AtomicU64 = AtomicU64::new(0);

struct Fixture(PathBuf);

impl Fixture {
    fn empty_heap() -> Self {
        let path = std::env::temp_dir().join(format!(
            "aps-memory-snapshot-{}-{}.hprof",
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

fn open() -> (Fixture, MemorySession, CancellationToken) {
    let file = Fixture::empty_heap();
    let digest = sha256_file(&file.0).unwrap();
    let cancel = CancellationToken::new();
    let executable = Path::new(env!("CARGO_BIN_EXE_aps-memory-worker"));
    let session = MemorySession::open(executable, &file.0, &digest, &cancel).unwrap();
    (file, session, cancel)
}

#[test]
fn snapshot_decodes_metadata_summary_classes_and_empty_findings() {
    let (_file, mut session, cancel) = open();
    let snapshot = session.snapshot(&cancel).unwrap();

    assert_eq!(snapshot.source_digest, session.source_digest());
    assert_eq!(snapshot.metadata.format, "JAVA PROFILE 1.0.3");
    assert_eq!(snapshot.metadata.id_size, 4);
    assert_eq!(snapshot.metadata.class_count, 0);
    assert_eq!(snapshot.metadata.instance_count, 0);
    assert_eq!(snapshot.metadata.root_count, 0);
    assert_eq!(snapshot.metadata.cache_status.as_deref(), Some("disabled"));
    assert_eq!(snapshot.summary, aps_memory::HeapSummary::default());
    assert!(snapshot.classes.is_empty());
    for classes in snapshot.per_heap_classes.values() {
        assert!(classes.is_empty());
    }
    assert_eq!(snapshot.findings.leak_suspects.total, 0);
    assert!(snapshot.findings.leak_suspects.rows.is_empty());
    assert_eq!(snapshot.findings.bitmaps.total, 0);
    assert_eq!(snapshot.findings.activities.total, 0);
    assert_eq!(snapshot.findings.lifecycle.total, 0);

    // A snapshot read is not terminal: bounded queries still work.
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
    assert_eq!(session.snapshot(&cancel).unwrap(), snapshot);
}

#[test]
fn typed_pages_keep_bounded_empty_evidence() {
    let (_file, mut session, cancel) = open();

    let page = session
        .instances_page(
            &["missing.Class".to_owned()],
            None,
            0,
            MAX_UI_PAGE_SIZE,
            &cancel,
        )
        .unwrap();
    assert_eq!(page.total, 0);
    assert!(page.rows.is_empty());

    assert_eq!(session.detail_page(123, 0, 200, &cancel).unwrap(), None);

    let dominators = session
        .dominators_page(None, 0, MAX_UI_PAGE_SIZE, &cancel)
        .unwrap();
    assert_eq!(dominators.total, 0);
    assert!(dominators.rows.is_empty());

    assert_eq!(
        session.leak_suspects_page(0, 200, &cancel).unwrap().total,
        0
    );
    assert_eq!(session.bitmaps_page(0, 200, &cancel).unwrap().total, 0);
    assert_eq!(
        session.activity_leaks_page(0, 200, &cancel).unwrap().total,
        0
    );
    assert_eq!(
        session
            .lifecycle_objects_page(0, 200, &cancel)
            .unwrap()
            .total,
        0
    );
}

#[test]
fn typed_page_validation_matches_the_ui_contract() {
    let (_file, mut session, cancel) = open();
    assert_eq!(
        session.instances_page(&[], None, 0, 1, &cancel),
        Err(MemoryError::InvalidInput)
    );
    assert_eq!(
        session.instances_page(&["x".repeat(4097)], None, 0, 1, &cancel),
        Err(MemoryError::InvalidInput)
    );
    assert_eq!(
        session.instances_page(
            &["a.Class".to_owned()],
            None,
            0,
            MAX_UI_PAGE_SIZE + 1,
            &cancel
        ),
        Err(MemoryError::InvalidPage)
    );
    assert_eq!(
        session.instances_page(&["a.Class".to_owned()], None, 0, 0, &cancel),
        Err(MemoryError::InvalidPage)
    );
    assert_eq!(
        session.detail_page(1, 0, 201, &cancel),
        Err(MemoryError::InvalidPage)
    );
    assert_eq!(
        session.dominators_page(None, 0, MAX_UI_PAGE_SIZE + 1, &cancel),
        Err(MemoryError::InvalidPage)
    );
    // Repeated validation does not kill a live session.
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

#[test]
fn cancelled_snapshot_stops_the_worker_explicitly() {
    let (_file, mut session, cancel) = open();
    cancel.cancel();
    assert_eq!(session.snapshot(&cancel), Err(MemoryError::Cancelled));
    assert_eq!(
        session.histogram(None, 0, 1, &CancellationToken::new()),
        Err(MemoryError::Closed)
    );
}

#[test]
fn worker_rejects_snapshot_with_unbounded_heap_list() {
    let request = wire::Value::map([
        ("version", 1i64.into()),
        ("id", 1i64.into()),
        ("op", "snapshot".into()),
        ("sourceDigest", "0".repeat(64).into()),
        (
            "args",
            wire::Value::map([(
                "heaps",
                wire::Value::List(
                    (0..65)
                        .map(|index| format!("heap-{index}").into())
                        .collect(),
                ),
            )]),
        ),
    ]);
    let mut input = Vec::new();
    wire::write_frame(&mut input, &request).unwrap();
    let mut output = Vec::new();
    worker::serve(&mut std::io::Cursor::new(input), &mut output).unwrap();
    let response = wire::decode(
        &wire::read_frame(&mut std::io::Cursor::new(output))
            .unwrap()
            .unwrap(),
    )
    .unwrap();
    assert_eq!(response.get("status").unwrap().as_str().unwrap(), "error");
}

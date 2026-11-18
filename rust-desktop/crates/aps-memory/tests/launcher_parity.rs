//! Optional regression against the local launcher dump used for Studio comparison.
use aps_hprof_engine::cache::sha256_file;
use aps_memory::{MemoryFilter, MemorySession, class_list_summary};
use aps_platform::CancellationToken;
use std::path::Path;

// Independently verified with Studio perflib; see HPROF_FUNCTIONAL_VALIDATION.md.
const LAUNCHER_HPROF_SHA256: &str =
    "5d0fa5f467c20a4fee38c6a3f9cba2caaa9d97539a5ae813cbb3284f53e7277a";

#[test]
#[ignore = "requires APS_LAUNCHER_HPROF pointing to the comparison dump"]
fn app_instance_page_latency() {
    let path = std::env::var_os("APS_LAUNCHER_HPROF").expect("set APS_LAUNCHER_HPROF");
    let path = Path::new(&path);
    let cancel = CancellationToken::new();
    let digest = sha256_file(path).unwrap();
    assert_eq!(
        digest, LAUNCHER_HPROF_SHA256,
        "APS_LAUNCHER_HPROF must match the independently verified fixture"
    );
    let mut session = MemorySession::open(
        Path::new(env!("CARGO_BIN_EXE_aps-memory-worker")),
        path,
        &digest,
        &cancel,
    )
    .unwrap();
    for name in ["java.lang.Object[]", "java.lang.String", "missing.Class"] {
        let mut first = None;
        for offset in [0, 500, 0] {
            let start = std::time::Instant::now();
            let page = session
                .instances_page(&[name.to_owned()], Some("App"), offset, 500, &cancel)
                .unwrap();
            println!(
                "{name} offset={offset} total={} rows={} elapsed_ms={:.2}",
                page.total,
                page.rows.len(),
                start.elapsed().as_secs_f64() * 1000.0
            );
            if offset == 0 {
                if let Some(previous) = &first {
                    assert_eq!(previous, &page);
                }
                first = Some(page);
            }
        }
    }
}

#[test]
#[ignore = "requires APS_LAUNCHER_HPROF pointing to the comparison dump"]
fn app_heap_remains_available_when_combined_snapshot_exceeds_budget() {
    let path = std::env::var_os("APS_LAUNCHER_HPROF").expect("set APS_LAUNCHER_HPROF");
    let path = Path::new(&path);
    let cancel = CancellationToken::new();
    let digest = sha256_file(path).unwrap();
    assert_eq!(
        digest, LAUNCHER_HPROF_SHA256,
        "APS_LAUNCHER_HPROF must match the independently verified fixture"
    );
    let mut session = MemorySession::open(
        Path::new(env!("CARGO_BIN_EXE_aps-memory-worker")),
        path,
        &digest,
        &cancel,
    )
    .unwrap();
    let snapshot = session.snapshot(&cancel).unwrap();
    assert!(snapshot.summary.retained_size.is_some());
    let app = snapshot
        .per_heap_classes
        .get("App")
        .expect("App heap available");
    assert_eq!(app.len(), 5_378);
    assert_eq!(
        app.iter().map(|row| row.native_size).sum::<Option<u64>>(),
        Some(34_945_672)
    );
    assert_eq!(
        app.iter().map(|row| row.instance_count).sum::<u64>(),
        2_073_125
    );
    // Studio Rabbit 3 / 2026.2.3 RC 1 legacy perflib, independently measured.
    assert_eq!(
        app.iter().map(|row| row.shallow_size).sum::<u64>(),
        104_666_905
    );
    let object_arrays = app
        .iter()
        .find(|row| row.class_name == "java.lang.Object[]")
        .unwrap();
    assert_eq!(object_arrays.instance_count, 86_385);
    assert_eq!(object_arrays.shallow_size, 2_825_900);
    // Perflib dominator union, not the overlapping sum of per-object retention.
    assert_eq!(object_arrays.retained_size, Some(109_271_977));
}

#[test]
#[ignore = "requires APS_LAUNCHER_HPROF pointing to the comparison dump"]
fn app_heap_retained_summary_is_available() {
    let path = std::env::var_os("APS_LAUNCHER_HPROF").expect("set APS_LAUNCHER_HPROF");
    let path = Path::new(&path);
    let cancel = CancellationToken::new();
    let digest = sha256_file(path).unwrap();
    assert_eq!(
        digest, LAUNCHER_HPROF_SHA256,
        "APS_LAUNCHER_HPROF must match the independently verified fixture"
    );
    let mut session = MemorySession::open(
        Path::new(env!("CARGO_BIN_EXE_aps-memory-worker")),
        path,
        &digest,
        &cancel,
    )
    .unwrap();
    let snapshot = session.snapshot(&cancel).unwrap();
    let app = snapshot
        .per_heap_classes
        .get("App")
        .expect("App heap available");
    let summary = class_list_summary(
        &snapshot,
        &MemoryFilter {
            heap: Some("App".into()),
            ..MemoryFilter::default()
        },
    );
    assert!(
        summary.total_retained_size.is_some(),
        "App retained size must be available"
    );
    assert_eq!(
        summary.total_retained_size,
        app.iter().map(|row| row.reachable_shallow_size).sum()
    );
    println!(
        "App retained shallow bytes: {}",
        summary.total_retained_size.unwrap()
    );
}

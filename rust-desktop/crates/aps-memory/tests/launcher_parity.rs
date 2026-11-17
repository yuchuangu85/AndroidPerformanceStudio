//! Optional regression against the local launcher dump used for Studio comparison.
use aps_hprof_engine::cache::sha256_file;
use aps_memory::{MemoryFilter, MemorySession, class_list_summary};
use aps_platform::CancellationToken;
use std::path::Path;

#[test]
#[ignore = "requires APS_LAUNCHER_HPROF pointing to the comparison dump"]
fn app_instance_page_latency() {
    let path = std::env::var_os("APS_LAUNCHER_HPROF").expect("set APS_LAUNCHER_HPROF");
    let path = Path::new(&path);
    let cancel = CancellationToken::new();
    let digest = sha256_file(path).unwrap();
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
    assert_eq!(
        app.iter().map(|row| row.shallow_size).sum::<u64>(),
        109_063_260
    );
    let object_arrays = app
        .iter()
        .find(|row| row.class_name == "java.lang.Object[]")
        .unwrap();
    assert_eq!(object_arrays.shallow_size, 3_862_520);
    // Current engine baseline; Studio Rabbit reports 93,703,506 for this row.
    assert_eq!(object_arrays.retained_size, Some(93_547_032));
}

#[test]
#[ignore = "requires APS_LAUNCHER_HPROF pointing to the comparison dump"]
fn app_heap_retained_summary_is_available() {
    let path = std::env::var_os("APS_LAUNCHER_HPROF").expect("set APS_LAUNCHER_HPROF");
    let path = Path::new(&path);
    let cancel = CancellationToken::new();
    let digest = sha256_file(path).unwrap();
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

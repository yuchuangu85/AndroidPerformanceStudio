use aps_contracts::SourceDigest;
use aps_memory::{
    ClassifierColumn, FindingKind, HistogramQuery, HistogramSort, MemoryFilter, MemorySession,
    SortDirection,
};
use aps_platform::CancellationToken;
use serde_json::json;
use std::{path::Path, time::Instant};

fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args: Vec<_> = std::env::args().collect();
    if args.len() < 4 || (matches!(args[1].as_str(), "verify" | "verify-full") && args.len() != 5) {
        return Err("usage: hprof_import_benchmark <label> <hprof> <worker>, or verify[-full] <hprof> <old-worker> <new-worker>".into());
    }
    let source = Path::new(&args[2]);
    if matches!(args[1].as_str(), "verify" | "verify-full") {
        verify(
            source,
            Path::new(&args[3]),
            Path::new(&args[4]),
            args[1] == "verify-full",
        )?;
        return Ok(());
    }
    let worker = Path::new(&args[3]);
    let start = Instant::now();
    let digest = SourceDigest::hash_file(source, aps_memory::MAX_INPUT_BYTES)?;
    let hash = start.elapsed();
    let cancel = CancellationToken::new();
    let phase = Instant::now();
    let mut session = MemorySession::open(worker, source, &digest.to_hex(), &cancel)?;
    let open = phase.elapsed();
    let phase = Instant::now();
    let snapshot = session.snapshot(&cancel)?;
    let snapshot_time = phase.elapsed();
    let phase = Instant::now();
    let mut filter = MemoryFilter::default();
    filter.heap = snapshot
        .per_heap_classes
        .keys()
        .find(|heap| heap.eq_ignore_ascii_case("app"))
        .cloned();
    let leaks = aps_memory::leak_class_names(&snapshot);
    let ids = aps_memory::leak_object_ids(&snapshot);
    let duplicates = aps_memory::duplicate_bitmap_classes(&snapshot);
    let rows = aps_memory::classifier_rows(
        &snapshot,
        &filter,
        ClassifierColumn::RetainedSize,
        SortDirection::Descending,
    );
    let summary = aps_memory::class_list_summary(&snapshot, &filter);
    std::hint::black_box((&leaks, &ids, &duplicates, &rows, &summary));
    let presenter = phase.elapsed();
    let total = start.elapsed();
    println!(
        "{}",
        json!({
            "label":args[1], "hash_ms":hash.as_secs_f64()*1000., "worker_open_ms":open.as_secs_f64()*1000.,
            "snapshot_ms":snapshot_time.as_secs_f64()*1000., "presenter_ms":presenter.as_secs_f64()*1000.,
            "total_ms":total.as_secs_f64()*1000., "digest":digest.to_hex(), "classes":snapshot.classes.len(),
            "objects":snapshot.summary.object_count, "leaks":snapshot.findings.leak_suspects.total,
            "bitmaps":snapshot.findings.bitmaps.total, "heap_count":snapshot.metadata.heaps.len()
        })
    );
    Ok(())
}

fn verify(
    source: &Path,
    before: &Path,
    after: &Path,
    exhaustive: bool,
) -> Result<(), Box<dyn std::error::Error>> {
    let digest = SourceDigest::hash_file(source, aps_memory::MAX_INPUT_BYTES)?.to_hex();
    let cancel = CancellationToken::new();
    let mut old = MemorySession::open(before, source, &digest, &cancel)?;
    let mut new = MemorySession::open(after, source, &digest, &cancel)?;
    assert!(old.metadata() == new.metadata(), "metadata mismatch");
    let old_snapshot = old.snapshot(&cancel)?;
    let new_snapshot = new.snapshot(&cancel)?;
    assert!(
        old_snapshot.summary == new_snapshot.summary,
        "summary mismatch"
    );
    assert!(
        old_snapshot.classes == new_snapshot.classes,
        "all-heap class rows mismatch"
    );
    assert!(
        old_snapshot.per_heap_classes == new_snapshot.per_heap_classes,
        "per-heap class rows mismatch"
    );
    assert!(
        old_snapshot.per_heap_summaries == new_snapshot.per_heap_summaries,
        "per-heap summaries mismatch"
    );
    assert!(
        old_snapshot.findings == new_snapshot.findings,
        "findings mismatch"
    );
    assert!(
        old_snapshot == new_snapshot,
        "complete typed snapshot mismatch"
    );
    let mut checks = 2usize;
    for heap in std::iter::once(None).chain(
        old_snapshot
            .metadata
            .heaps
            .iter()
            .map(|heap| Some(heap.as_str())),
    ) {
        for offset in [0, 200] {
            assert!(
                old.histogram(heap, offset, 200, &cancel)?
                    == new.histogram(heap, offset, 200, &cancel)?,
                "histogram page mismatch"
            );
            checks += 1;
        }
        let mut filter = MemoryFilter::default();
        filter.heap = heap.map(str::to_owned);
        assert!(
            aps_memory::classifier_rows(
                &old_snapshot,
                &filter,
                ClassifierColumn::RetainedSize,
                SortDirection::Descending
            ) == aps_memory::classifier_rows(
                &new_snapshot,
                &filter,
                ClassifierColumn::RetainedSize,
                SortDirection::Descending
            ),
            "presenter tree mismatch"
        );
        assert!(
            aps_memory::class_list_summary(&old_snapshot, &filter)
                == aps_memory::class_list_summary(&new_snapshot, &filter),
            "presenter summary mismatch"
        );
        checks += 2;
    }
    let mut selected = Vec::new();
    for name in [
        "java.lang.String",
        "android.graphics.Bitmap",
        "sun.misc.Cleaner",
        "int[]",
        "[I",
    ] {
        for offset in [0, 500] {
            let old_page = old.instances_page(&[name.to_owned()], None, offset, 500, &cancel)?;
            let new_page = new.instances_page(&[name.to_owned()], None, offset, 500, &cancel)?;
            assert!(old_page == new_page, "instances page mismatch");
            if offset == 0
                && let Some(row) = old_page.rows.first()
            {
                selected.push(row.object_id);
            }
            checks += 1;
        }
    }
    let old_roots = old.dominators_page(None, 0, 500, &cancel)?;
    assert!(
        old_roots == new.dominators_page(None, 0, 500, &cancel)?,
        "root dominators mismatch"
    );
    checks += 1;
    for offset in [500, old_roots.total.saturating_sub(500) as usize] {
        assert!(
            old.dominators_page(None, offset, 500, &cancel)?
                == new.dominators_page(None, offset, 500, &cancel)?,
            "root dominator page mismatch"
        );
        checks += 1;
    }
    for id in selected.iter().copied().take(4) {
        assert!(
            old.detail_page(id, 0, 200, &cancel)? == new.detail_page(id, 0, 200, &cancel)?,
            "object detail/chain/inbound mismatch"
        );
        assert!(
            old.dominators_page(Some(id), 0, 500, &cancel)?
                == new.dominators_page(Some(id), 0, 500, &cancel)?,
            "dominator children mismatch"
        );
        checks += 2;
    }
    for kind in [
        FindingKind::LeakSuspects,
        FindingKind::BitmapInstances,
        FindingKind::ActivityLeaks,
        FindingKind::LifecycleLeakObjects,
    ] {
        for offset in [0, 200] {
            assert!(
                old.findings(kind, offset, 200, &cancel)?
                    == new.findings(kind, offset, 200, &cancel)?,
                "findings page mismatch"
            );
            checks += 1;
        }
    }
    let mut instance_rows = 0usize;
    let mut dominator_rows = 0usize;
    let mut finding_rows = 0usize;
    if exhaustive {
        for name in [
            "java.lang.String",
            "android.graphics.Bitmap",
            "sun.misc.Cleaner",
            "java.lang.Object[]",
            "int[]",
        ] {
            let mut offset = 0usize;
            loop {
                let old_page = old.instances_page(&[name.into()], None, offset, 500, &cancel)?;
                let new_page = new.instances_page(&[name.into()], None, offset, 500, &cancel)?;
                assert!(old_page == new_page, "full instance pagination mismatch");
                checks += 1;
                let count = old_page.rows.len();
                instance_rows += count;
                offset += count;
                if offset >= old_page.total as usize {
                    break;
                }
                assert!(count != 0, "instance pagination stopped before total");
            }
        }
        let mut offset = 0usize;
        let mut retained = 0u64;
        loop {
            let old_page = old.dominators_page(None, offset, 500, &cancel)?;
            let new_page = new.dominators_page(None, offset, 500, &cancel)?;
            assert!(
                old_page == new_page,
                "full visible-root pagination mismatch"
            );
            checks += 1;
            for row in &old_page.rows {
                retained = retained
                    .checked_add(row.retained_size)
                    .ok_or("retained summary overflow")?;
            }
            let count = old_page.rows.len();
            dominator_rows += count;
            offset += count;
            if offset >= old_page.total as usize {
                break;
            }
            assert!(count != 0, "dominator pagination stopped before total");
        }
        assert_eq!(
            (dominator_rows != 0).then_some(retained),
            new_snapshot.summary.retained_size
        );
        checks += 1;
        for kind in [
            FindingKind::LeakSuspects,
            FindingKind::BitmapInstances,
            FindingKind::ActivityLeaks,
            FindingKind::LifecycleLeakObjects,
        ] {
            let mut offset = 0usize;
            loop {
                let old_page = old.findings(kind, offset, 200, &cancel)?;
                let new_page = new.findings(kind, offset, 200, &cancel)?;
                assert!(old_page == new_page, "full findings pagination mismatch");
                checks += 1;
                let count = old_page.get("rows")?.as_list()?.len();
                let total = usize::try_from(old_page.get("total")?.as_int()?)?;
                finding_rows += count;
                offset += count;
                if offset >= total {
                    break;
                }
                assert!(count != 0, "findings pagination stopped before total");
            }
        }
        for heap in std::iter::once(None).chain(
            old_snapshot
                .metadata
                .heaps
                .iter()
                .map(|heap| Some(heap.as_str())),
        ) {
            for sort in [
                HistogramSort::CountDescending,
                HistogramSort::ShallowDescending,
                HistogramSort::RetainedDescending,
                HistogramSort::ClassNameAscending,
            ] {
                for (search, match_case) in [
                    ("java", false),
                    ("BITMAP", false),
                    ("String", true),
                    ("__not_present__", false),
                ] {
                    let query = HistogramQuery {
                        search: search.into(),
                        match_case,
                        sort,
                    };
                    assert!(
                        old.histogram_query(heap, &query, 0, 200, &cancel)?
                            == new.histogram_query(heap, &query, 0, 200, &cancel)?,
                        "histogram filter/sort mismatch"
                    );
                    checks += 1;
                }
            }
        }
    }
    println!(
        "{}",
        json!({"result":"exact_equal", "checks":checks, "classes":old_snapshot.classes.len(),
        "objects":old_snapshot.summary.object_count, "heaps":old_snapshot.metadata.heaps,
        "source_digest":digest, "selected_details":selected.len().min(4), "exhaustive":exhaustive,
        "instance_rows_compared":instance_rows, "visible_dominator_rows_compared":dominator_rows, "finding_rows_compared":finding_rows,
        "comparison":"full typed snapshot, presenter trees/summaries, histogram/instance/dominator/findings pages, object fields/chains/inbound references"})
    );
    Ok(())
}

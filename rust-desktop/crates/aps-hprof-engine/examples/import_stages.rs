//! Source-bound, read-only timing of the real engine without process or GUI overhead.
use aps_hprof_engine::{Engine, cache, model::Heap, wire::Value};
use serde_json::json;
use std::{collections::BTreeMap, path::Path, time::Instant};

fn sum_metric(rows: &Value, key: &str) -> Result<i64, String> {
    rows.as_list()?.iter().try_fold(0i64, |sum, row| {
        sum.checked_add(row.get(key)?.as_int()?)
            .ok_or_else(|| format!("{key} sum overflow"))
    })
}

fn main() -> Result<(), String> {
    let args: Vec<_> = std::env::args().collect();
    if args.len() != 3 {
        return Err("usage: import_stages <hprof> <expected-sha256>".into());
    }
    let path = Path::new(&args[1]);
    let digest = cache::sha256_file(path)?;
    if digest != args[2] {
        return Err("HPROF digest does not match the authorized benchmark input".into());
    }

    let start = Instant::now();
    let heap = Heap::open(path)?;
    let parsed = start.elapsed();
    let objects = heap.objects.len();
    let classes = heap.classes.len();
    let roots = heap.roots.len();
    let engine = Engine::new(heap, BTreeMap::new())?;
    let core_ready = start.elapsed();
    let histograms = engine.histogram_bundle(&engine.heap.heaps)?;
    let histograms_ready = start.elapsed();
    let findings = engine.findings()?;
    let projection_ready = start.elapsed();
    std::hint::black_box((&engine, &histograms, &findings));

    let all = histograms.get("all")?;
    let app_name = engine
        .heap
        .heaps
        .iter()
        .find(|name| name.eq_ignore_ascii_case("app"))
        .ok_or("missing App heap")?;
    let app = histograms.get("perHeap")?.get(app_name)?;
    let app_summary = app.get("summary")?;
    let nodes = engine.analysis.retained.len();
    let edges = engine.analysis.graph.offsets.last().copied().unwrap_or(0);
    let native = sum_metric(all.get("classes")?, "nativeSize")?;
    engine.heap.check_source()?;
    engine.analysis.graph.check_storage()?;
    if cache::sha256_file(path)? != digest {
        return Err("HPROF bytes changed during benchmark".into());
    }
    println!(
        "{}",
        json!({
            "implementation":"rust-release-engine",
            "source_digest":digest,
            "source_bytes":engine.heap.file_size,
            "parse_ms":parsed.as_secs_f64()*1000.,
            "analysis_and_indexes_ms":(core_ready-parsed).as_secs_f64()*1000.,
            "core_ready_ms":core_ready.as_secs_f64()*1000.,
            "histograms_ms":(histograms_ready-core_ready).as_secs_f64()*1000.,
            "findings_ms":(projection_ready-histograms_ready).as_secs_f64()*1000.,
            "projection_ready_ms":projection_ready.as_secs_f64()*1000.,
            "ordinary_objects":objects,
            "class_objects":classes,
            "nodes":nodes,
            "root_records":roots,
            "edges":edges,
            "native_bytes":native,
            "app_objects":app_summary.get("objectCount")?.as_int()?,
            "app_shallow_bytes":app_summary.get("shallowSize")?.as_int()?,
            "app_classes":app_summary.get("classCount")?.as_int()?,
            "boundary":"hash validation and teardown outside internal timings; GUI, worker IPC and snapshot encoding excluded"
        })
    );
    Ok(())
}

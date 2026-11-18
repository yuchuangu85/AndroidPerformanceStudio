//! Snapshot parity against the original individual engine queries.

use aps_hprof_engine::{
    Engine,
    cache::sha256_file,
    model::Heap,
    wire::{self, Value},
};
use aps_memory::{MAX_PAGE_SIZE, worker};
use std::{
    collections::BTreeMap,
    fs,
    io::Cursor,
    path::PathBuf,
    sync::atomic::{AtomicU64, Ordering},
    time::Instant,
};

struct Fixture(PathBuf);
impl Fixture {
    fn new() -> Self {
        fn record(bytes: &mut Vec<u8>, tag: u8, body: &[u8]) {
            bytes.push(tag);
            bytes.extend(0u32.to_be_bytes());
            bytes.extend((body.len() as u32).to_be_bytes());
            bytes.extend(body);
        }
        let mut bytes = b"JAVA PROFILE 1.0.3\0".to_vec();
        bytes.extend(4u32.to_be_bytes());
        bytes.extend(1u64.to_be_bytes());
        for (id, text) in [
            (10u32, "sample/Node"),
            (11, "sample/Leaf"),
            (12, "next"),
            (13, "App"),
            (14, "Zygote"),
        ] {
            let mut body = id.to_be_bytes().to_vec();
            body.extend(text.as_bytes());
            record(&mut bytes, 1, &body);
        }
        let mut heap = Vec::new();
        for (id, name, has_field) in [(1u32, 10u32, true), (2, 11, false)] {
            let mut body = id.to_be_bytes().to_vec();
            body.extend(id.to_be_bytes());
            body.extend(0u32.to_be_bytes());
            body.extend(name.to_be_bytes());
            record(&mut bytes, 2, &body);
            heap.push(0x20);
            heap.extend(id.to_be_bytes());
            heap.extend([0; 4 + 6 * 4]);
            heap.extend(24u32.to_be_bytes());
            heap.extend([0; 4]);
            heap.extend(u16::from(has_field).to_be_bytes());
            if has_field {
                heap.extend(12u32.to_be_bytes());
                heap.push(2);
            }
        }
        for (segment, name, objects) in [
            (
                1u32,
                13u32,
                vec![(100u32, 1u32, Some(101u32), true), (102, 2, None, false)],
            ),
            (2, 14, vec![(101, 1, Some(0), false), (200, 2, None, true)]),
        ] {
            heap.push(0xfe);
            heap.extend(segment.to_be_bytes());
            heap.extend(name.to_be_bytes());
            for (id, class, target, root) in objects {
                heap.push(0x21);
                heap.extend(id.to_be_bytes());
                heap.extend([0; 4]);
                heap.extend(class.to_be_bytes());
                heap.extend(if target.is_some() { 4u32 } else { 0 }.to_be_bytes());
                if let Some(target) = target {
                    heap.extend(target.to_be_bytes());
                }
                if root {
                    heap.push(0xff);
                    heap.extend(id.to_be_bytes());
                }
            }
        }
        record(&mut bytes, 0x1c, &heap);
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let path = std::env::temp_dir().join(format!(
            "aps-snapshot-opt-{}-{}.hprof",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        fs::write(&path, bytes).unwrap();
        Self(path)
    }
    fn open_request(&self, digest: &str) -> Value {
        request(
            "open",
            digest,
            Value::map([
                ("path", self.0.to_str().unwrap().into()),
                ("sourceDigest", digest.into()),
                ("mapping", Value::map([])),
                ("cacheDir", Value::Null),
                ("graphStorage", "memory".into()),
            ]),
        )
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.0);
    }
}

fn request(op: &str, digest: &str, args: Value) -> Value {
    Value::map([
        ("version", wire::VERSION.into()),
        ("id", 1i64.into()),
        ("op", op.into()),
        ("sourceDigest", digest.into()),
        ("args", args),
    ])
}

fn run(requests: &[Value]) -> Vec<Value> {
    let mut input = Vec::new();
    for request in requests {
        wire::write_frame(&mut input, request).unwrap();
    }
    let mut output = Vec::new();
    worker::serve(&mut Cursor::new(input), &mut output).unwrap();
    let mut reader = Cursor::new(output);
    let mut responses = Vec::new();
    while let Some(frame) = wire::read_frame(&mut reader).unwrap() {
        let response = wire::decode(&frame).unwrap();
        assert_eq!(
            response.get("status").unwrap().as_str().unwrap(),
            "ok",
            "{response:?}"
        );
        responses.push(response.get("payload").unwrap().clone());
    }
    responses
}

fn retained_oracle(engine: &Engine, heap: Option<&str>) -> Value {
    let mut offset = 0;
    let mut sum = None::<i64>;
    loop {
        let page = engine.dominators(None, offset, 500).unwrap();
        let rows = page.get("rows").unwrap().as_list().unwrap();
        for row in rows {
            let belongs = match heap {
                None => true,
                Some(heap) => {
                    engine
                        .identity(row.get("objectId").unwrap().as_int().unwrap() as u64)
                        .unwrap()
                        .get("heapName")
                        .unwrap()
                        .as_str()
                        .ok()
                        == Some(heap)
                }
            };
            if belongs {
                sum = Some(
                    sum.unwrap_or(0)
                        .checked_add(row.get("retainedSize").unwrap().as_int().unwrap())
                        .unwrap(),
                );
            }
        }
        offset += rows.len();
        if offset >= page.get("total").unwrap().as_int().unwrap() as usize {
            break;
        }
        assert!(!rows.is_empty());
    }
    Value::map([("retainedSize", sum.into())])
}

#[test]
fn snapshot_and_retained_summaries_match_individual_engine_queries() {
    let file = Fixture::new();
    let digest = sha256_file(&file.0).unwrap();
    let engine = Engine::new(Heap::open(&file.0).unwrap(), BTreeMap::new()).unwrap();
    let mut requests = vec![
        file.open_request(&digest),
        request(
            "snapshot",
            &digest,
            Value::map([("heaps", Value::List(vec!["App".into(), "Zygote".into()]))]),
        ),
        request("retainedSummary", &digest, Value::map([])),
    ];
    for heap in ["App", "Zygote", "missing"] {
        requests.push(request(
            "heapRetainedSummary",
            &digest,
            Value::map([("heapName", heap.into())]),
        ));
        requests.push(request(
            "heapHistogram",
            &digest,
            Value::map([("heapName", heap.into())]),
        ));
    }
    let responses = run(&requests);
    let snapshot = &responses[1];
    let histogram = engine.histogram(None).unwrap();
    assert_eq!(
        snapshot.get("classes").unwrap(),
        histogram.get("classes").unwrap()
    );
    let mut summary = histogram.get("summary").unwrap().as_map().unwrap().clone();
    summary.insert(
        "retainedSize".into(),
        retained_oracle(&engine, None)
            .get("retainedSize")
            .unwrap()
            .clone(),
    );
    assert_eq!(snapshot.get("summary").unwrap(), &Value::Map(summary));
    assert_eq!(responses[2], retained_oracle(&engine, None));
    assert_eq!(snapshot.get("perHeapOmitted").unwrap(), &Value::Bool(false));
    for (index, heap) in ["App", "Zygote", "missing"].into_iter().enumerate() {
        let expected = engine.histogram(Some(heap)).unwrap();
        assert_eq!(
            responses[3 + index * 2],
            retained_oracle(&engine, Some(heap))
        );
        assert_eq!(responses[4 + index * 2], expected);
        if heap != "missing" {
            assert_eq!(
                snapshot.get("perHeap").unwrap().get(heap).unwrap(),
                expected.get("classes").unwrap()
            );
            assert_eq!(
                snapshot.get("perHeapSummaries").unwrap().get(heap).unwrap(),
                expected.get("summary").unwrap()
            );
        }
    }
    let findings = engine.findings().unwrap();
    for kind in [
        "leakSuspects",
        "bitmapInstances",
        "activityLeaks",
        "lifecycleLeakObjects",
    ] {
        let rows = findings.get(kind).unwrap().as_list().unwrap();
        let actual = snapshot.get("findings").unwrap().get(kind).unwrap();
        assert_eq!(actual.get("total").unwrap(), &Value::Int(rows.len() as i64));
        assert_eq!(
            actual.get("rows").unwrap().as_list().unwrap(),
            &rows[..rows.len().min(MAX_PAGE_SIZE)]
        );
    }
}

#[test]
fn duplicate_heap_names_do_not_change_the_complete_snapshot() {
    let file = Fixture::new();
    let digest = sha256_file(&file.0).unwrap();
    let responses = run(&[
        file.open_request(&digest),
        request(
            "snapshot",
            &digest,
            Value::map([("heaps", Value::List(vec!["App".into(), "Zygote".into()]))]),
        ),
        request(
            "snapshot",
            &digest,
            Value::map([(
                "heaps",
                Value::List(vec!["Zygote".into(), "App".into(), "App".into()]),
            )]),
        ),
    ]);
    assert_eq!(responses[1], responses[2]);
}

#[test]
#[ignore = "requires APS_LAUNCHER_HPROF pointing to the comparison dump"]
fn launcher_worker_fallback_matches_full_scalar_payloads() {
    let path =
        PathBuf::from(std::env::var_os("APS_LAUNCHER_HPROF").expect("set APS_LAUNCHER_HPROF"));
    let digest = sha256_file(&path).unwrap();
    let engine = Engine::new(Heap::open(&path).unwrap(), BTreeMap::new()).unwrap();
    let heaps = engine
        .metadata()
        .unwrap()
        .get("heaps")
        .unwrap()
        .as_list()
        .unwrap()
        .iter()
        .map(|value| value.as_str().unwrap().to_owned())
        .collect::<Vec<_>>();
    let start = Instant::now();
    let all = engine.histogram(None).unwrap();
    let per_heap = heaps
        .iter()
        .map(|name| (name.clone(), engine.histogram(Some(name)).unwrap()))
        .collect::<BTreeMap<_, _>>();
    let scalar_ms = start.elapsed().as_secs_f64() * 1000.0;
    let start = Instant::now();
    let bundle = engine.histogram_bundle(&heaps).unwrap();
    let bundle_ms = start.elapsed().as_secs_f64() * 1000.0;
    assert!(
        bundle.get("all").unwrap() == &all,
        "all-heap bundle differs from scalar"
    );
    assert!(
        bundle.get("perHeap").unwrap().as_map().unwrap() == &per_heap,
        "per-heap bundle differs from scalar"
    );
    drop(bundle);
    let start = Instant::now();
    let retained = retained_oracle(&engine, None);
    let paged_retained_ms = start.elapsed().as_secs_f64() * 1000.0;
    let start = Instant::now();
    assert_eq!(engine.retained_summary(None).unwrap(), retained);
    let direct_retained_ms = start.elapsed().as_secs_f64() * 1000.0;
    let mut requests = vec![
        request(
            "open",
            &digest,
            Value::map([
                ("path", path.to_str().unwrap().into()),
                ("sourceDigest", digest.as_str().into()),
                ("mapping", Value::map([])),
                ("cacheDir", Value::Null),
                ("graphStorage", "memory".into()),
            ]),
        ),
        request(
            "snapshot",
            &digest,
            Value::map([(
                "heaps",
                Value::List(heaps.iter().cloned().map(Value::from).collect()),
            )]),
        ),
    ];
    for heap in &heaps {
        requests.push(request(
            "heapHistogram",
            &digest,
            Value::map([("heapName", heap.as_str().into())]),
        ));
    }
    let start = Instant::now();
    let responses = run(&requests);
    let worker_ms = start.elapsed().as_secs_f64() * 1000.0;
    let snapshot = &responses[1];
    assert_eq!(snapshot.get("perHeapOmitted").unwrap(), &Value::Bool(true));
    assert!(
        snapshot.get("classes").unwrap() == all.get("classes").unwrap(),
        "worker all-heap classes differ"
    );
    let mut summary = all.get("summary").unwrap().as_map().unwrap().clone();
    summary.insert(
        "retainedSize".into(),
        retained.get("retainedSize").unwrap().clone(),
    );
    assert_eq!(snapshot.get("summary").unwrap(), &Value::Map(summary));
    for (index, heap) in heaps.iter().enumerate() {
        assert!(
            &responses[index + 2] == per_heap.get(heap).unwrap(),
            "cached fallback for {heap} differs from scalar"
        );
    }
    let findings = engine.findings().unwrap();
    for kind in [
        "leakSuspects",
        "bitmapInstances",
        "activityLeaks",
        "lifecycleLeakObjects",
    ] {
        let expected = findings.get(kind).unwrap().as_list().unwrap();
        let actual = snapshot.get("findings").unwrap().get(kind).unwrap();
        assert_eq!(
            actual.get("total").unwrap(),
            &Value::Int(expected.len() as i64)
        );
        assert!(
            actual.get("rows").unwrap().as_list().unwrap()
                == &expected[..expected.len().min(MAX_PAGE_SIZE)],
            "{kind} evidence differs"
        );
    }
    let app_shallow = per_heap
        .get("App")
        .unwrap()
        .get("summary")
        .unwrap()
        .get("shallowSize")
        .unwrap()
        .as_int()
        .unwrap();
    println!(
        "launcher full-payload parity passed: App shallow={app_shallow}; serial_histograms_ms={scalar_ms:.3} bundle_ms={bundle_ms:.3} paged_retained_ms={paged_retained_ms:.3} direct_retained_ms={direct_retained_ms:.3} worker_open_snapshot_fallback_ms={worker_ms:.3}"
    );
}

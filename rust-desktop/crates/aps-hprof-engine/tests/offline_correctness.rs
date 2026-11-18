use aps_hprof_engine::{
    Engine,
    analysis::{self, NONE},
    model::Heap,
    wire::Value,
};
use std::{
    collections::BTreeMap,
    path::PathBuf,
    sync::atomic::{AtomicU64, Ordering},
};

// Encode actual HPROF records independently of parser and cache internals.
struct Dump {
    bytes: Vec<u8>,
    heap: Vec<u8>,
    next: u32,
}
impl Dump {
    fn new() -> Self {
        let mut bytes = b"JAVA PROFILE 1.0.3\0".to_vec();
        bytes.extend(4u32.to_be_bytes());
        bytes.extend(0u64.to_be_bytes());
        Self {
            bytes,
            heap: Vec::new(),
            next: 10_000,
        }
    }
    fn record(&mut self, tag: u8, body: &[u8]) {
        self.bytes.push(tag);
        self.bytes.extend(0u32.to_be_bytes());
        self.bytes.extend((body.len() as u32).to_be_bytes());
        self.bytes.extend(body);
    }
    fn string(&mut self, name: &str) -> u32 {
        let id = self.next;
        self.next += 1;
        let mut body = id.to_be_bytes().to_vec();
        body.extend(name.as_bytes());
        self.record(1, &body);
        id
    }
    fn class(&mut self, id: u32, name: &str, parent: u32, size: u32, fields: &[(&str, u8)]) {
        let name_id = self.string(name);
        let mut body = 0u32.to_be_bytes().to_vec();
        body.extend(id.to_be_bytes());
        body.extend(0u32.to_be_bytes());
        body.extend(name_id.to_be_bytes());
        self.record(2, &body);
        self.heap.push(0x20);
        self.heap.extend(id.to_be_bytes());
        self.heap.extend(0u32.to_be_bytes());
        self.heap.extend(parent.to_be_bytes());
        self.heap.extend([0; 20]);
        self.heap.extend(size.to_be_bytes());
        self.heap.extend([0; 4]);
        self.heap.extend((fields.len() as u16).to_be_bytes());
        for &(name, ty) in fields {
            let field_id = self.string(name);
            self.heap.extend(field_id.to_be_bytes());
            self.heap.push(ty);
        }
    }
    fn instance(&mut self, id: u32, class: u32, data: &[u8]) {
        self.heap.push(0x21);
        self.heap.extend(id.to_be_bytes());
        self.heap.extend(0u32.to_be_bytes());
        self.heap.extend(class.to_be_bytes());
        self.heap.extend((data.len() as u32).to_be_bytes());
        self.heap.extend(data);
    }
    fn root(&mut self, tag: u8, id: u32) {
        self.heap.push(tag);
        self.heap.extend(id.to_be_bytes());
    }
    fn finish(mut self) -> Fixture {
        let heap = std::mem::take(&mut self.heap);
        self.record(0x1c, &heap);
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let path = std::env::temp_dir().join(format!(
            "aps-parity-{}-{}.hprof",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::write(&path, self.bytes).unwrap();
        Fixture(path)
    }
}
struct Fixture(PathBuf);
impl Fixture {
    fn engine(&self) -> Engine {
        Engine::new(Heap::open(&self.0).unwrap(), BTreeMap::new()).unwrap()
    }
}
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}
fn node(engine: &Engine, id: u64) -> usize {
    analysis::node_of(&engine.heap, id).unwrap() as usize
}

#[test]
fn instances_index_combines_classes_with_the_same_mapped_name() {
    let mut dump = Dump::new();
    dump.class(1, "a", 0, 8, &[]);
    dump.class(2, "b", 0, 8, &[]);
    dump.instance(10, 1, &[]);
    dump.instance(11, 2, &[]);
    dump.instance(12, 1, &[]);
    let fixture = dump.finish();
    let mapping = BTreeMap::from([
        ("a".to_owned(), "mapped.Item".to_owned()),
        ("b".to_owned(), "mapped.Item".to_owned()),
    ]);
    let engine = Engine::new(Heap::open(&fixture.0).unwrap(), mapping).unwrap();
    let page = engine
        .instances(&["mapped.Item".into()], None, 1, 2, true, None)
        .unwrap();
    assert_eq!(page.get("total").unwrap().as_int().unwrap(), 3);
    let rows = page.get("rows").unwrap().as_list().unwrap();
    assert_eq!(
        rows.iter()
            .map(|row| row.get("objectId").unwrap().as_int().unwrap())
            .collect::<Vec<_>>(),
        vec![11, 12]
    );
    assert_eq!(
        rows.iter()
            .map(|row| row.get("index").unwrap().as_int().unwrap())
            .collect::<Vec<_>>(),
        vec![2, 3]
    );
}

#[test]
fn instances_keep_signed_id_order_and_anchor_position() {
    let mut dump = Dump::new();
    dump.class(1, "Item", 0, 8, &[]);
    for id in [10, 11, 12] {
        dump.instance(id, 1, &[]);
    }
    let fixture = dump.finish();
    let mut heap = Heap::open(&fixture.0).unwrap();
    for (object, id) in heap.objects.iter_mut().zip([5, u64::MAX, 1u64 << 63]) {
        object.id = id;
    }
    heap.object_by_id = heap
        .objects
        .iter()
        .enumerate()
        .map(|(i, object)| (object.id, i))
        .collect();
    let engine = Engine::new(heap, BTreeMap::new()).unwrap();
    let page = engine
        .instances(&["Item".into()], None, 0, 2, true, Some(u64::MAX))
        .unwrap();
    assert_eq!(page.get("total").unwrap().as_int().unwrap(), 3);
    assert_eq!(page.get("offset").unwrap().as_int().unwrap(), 0);
    let rows = page.get("rows").unwrap().as_list().unwrap();
    assert_eq!(
        rows.iter()
            .map(|row| row.get("objectId").unwrap().as_int().unwrap())
            .collect::<Vec<_>>(),
        vec![i64::MIN, -1]
    );
    let page = engine
        .instances(&["Item".into()], None, 2, 2, true, None)
        .unwrap();
    let rows = page.get("rows").unwrap().as_list().unwrap();
    assert_eq!(rows[0].get("objectId").unwrap().as_int().unwrap(), 5);
    assert_eq!(rows[0].get("index").unwrap().as_int().unwrap(), 3);
}

#[test]
fn instances_pages_match_full_scan_across_classes_heaps_and_anchors() {
    let mut dump = Dump::new();
    dump.class(1, "java.lang.Class", 0, 24, &[]);
    dump.class(2, "a.Node", 0, 8, &[]);
    dump.class(3, "a.Node", 0, 12, &[]);
    dump.class(4, "b.Other", 0, 4, &[]);
    let app = dump.string("app");
    let zygote = dump.string("zygote");
    dump.heap.push(0xfe);
    dump.heap.extend(0u32.to_be_bytes());
    dump.heap.extend(app.to_be_bytes());
    for (id, class) in [(10, 2), (12, 4), (14, 3), (18, 2)] {
        dump.instance(id, class, &[]);
    }
    dump.heap.push(0x23);
    dump.heap.extend(20u32.to_be_bytes());
    dump.heap.extend(0u32.to_be_bytes());
    dump.heap.extend(2u32.to_be_bytes());
    dump.heap.push(8);
    dump.heap.extend([1, 2]);
    dump.heap.push(0xfe);
    dump.heap.extend(0u32.to_be_bytes());
    dump.heap.extend(zygote.to_be_bytes());
    for (id, class) in [(11, 2), (13, 4), (15, 3), (19, 4)] {
        dump.instance(id, class, &[]);
    }
    let fixture = dump.finish();
    let engine = fixture.engine();
    let selections = [
        vec!["a.Node".to_owned()],
        vec![
            "a.Node".to_owned(),
            "b.Other".to_owned(),
            "a.Node".to_owned(),
        ],
        vec!["byte[]".to_owned(), "a.Node".to_owned()],
        vec!["missing".to_owned()],
    ];
    for names in selections {
        for heap in [None, Some("App"), Some("Zygote"), Some("missing")] {
            for ordinary in [false, true] {
                let mut nodes: Vec<u32> =
                    (0..engine.analysis.retained.len() as u32)
                        .filter(|&node| {
                            names.iter().any(|name| name == engine.group_name(node))
                                && (heap.is_none() || engine.heap_name(node) == heap)
                                && (!ordinary
                                    || engine.heap.objects.get(node as usize).is_some_and(
                                        |object| {
                                            matches!(
                                                object.kind,
                                                aps_hprof_engine::model::ObjectKind::Instance
                                            )
                                        },
                                    ))
                        })
                        .collect();
                nodes.sort_unstable_by_key(|&node| analysis::id_of(&engine.heap, node) as i64);
                for anchor in [None, Some(10), Some(11), Some(999)] {
                    for offset in [0, 2, 4, 20] {
                        let limit = 2;
                        let actual = engine
                            .instances(&names, heap, offset, limit, ordinary, anchor)
                            .unwrap();
                        let expected_offset = anchor
                            .and_then(|id| {
                                nodes
                                    .iter()
                                    .position(|&n| analysis::id_of(&engine.heap, n) == id)
                            })
                            .map(|position| position / limit * limit)
                            .unwrap_or(offset);
                        let mut ordinals = BTreeMap::new();
                        let mut expected = Vec::new();
                        for (position, &node) in nodes.iter().enumerate() {
                            let ordinal = ordinals.entry(engine.group_name(node)).or_insert(0i64);
                            *ordinal += 1;
                            if (expected_offset..expected_offset.saturating_add(limit))
                                .contains(&position)
                            {
                                expected
                                    .push((analysis::id_of(&engine.heap, node) as i64, *ordinal));
                            }
                        }
                        let rows: Vec<_> = actual
                            .get("rows")
                            .unwrap()
                            .as_list()
                            .unwrap()
                            .iter()
                            .map(|row| {
                                (
                                    row.get("objectId").unwrap().as_int().unwrap(),
                                    row.get("index").unwrap().as_int().unwrap(),
                                )
                            })
                            .collect();
                        assert_eq!(
                            actual.get("total").unwrap().as_int().unwrap(),
                            nodes.len() as i64
                        );
                        assert_eq!(
                            actual.get("offset").unwrap().as_int().unwrap(),
                            expected_offset as i64
                        );
                        assert_eq!(
                            rows, expected,
                            "names={names:?} heap={heap:?} ordinary={ordinary} anchor={anchor:?} offset={offset}"
                        );
                    }
                }
            }
        }
    }
}

#[test]
fn reachable_shallow_histogram_uses_object_heap_and_excludes_unreachable() {
    let mut dump = Dump::new();
    dump.class(1, "java.lang.Class", 0, 24, &[]);
    dump.class(2, "Node", 0, 8, &[("child", 2)]);
    let app = dump.string("app");
    let zygote = dump.string("zygote");
    dump.heap.push(0xfe);
    dump.heap.extend(0u32.to_be_bytes());
    dump.heap.extend(app.to_be_bytes());
    dump.instance(10, 2, &11u32.to_be_bytes());
    dump.instance(12, 2, &0u32.to_be_bytes());
    dump.root(0xff, 10);
    dump.heap.push(0xfe);
    dump.heap.extend(0u32.to_be_bytes());
    dump.heap.extend(zygote.to_be_bytes());
    dump.instance(11, 2, &0u32.to_be_bytes());
    let fixture = dump.finish();
    let engine = fixture.engine();
    for (heap, expected) in [(None, 16), (Some("App"), 8), (Some("Zygote"), 8)] {
        for histogram in [
            engine.histogram(heap).unwrap(),
            engine.raw_histogram(heap).unwrap(),
        ] {
            let rows = histogram.get("classes").unwrap().as_list().unwrap();
            let row = rows
                .iter()
                .find(|row| row.get("className").unwrap().as_str().unwrap() == "Node")
                .unwrap();
            assert_eq!(
                row.get("reachableShallowSize").unwrap().as_int().unwrap(),
                expected
            );
        }
    }
}

#[test]
fn reachable_shallow_reports_unknown_only_for_reachable_unknown_objects() {
    for reachable in [false, true] {
        let mut dump = Dump::new();
        dump.class(1, "java.lang.Class", 0, 24, &[]);
        dump.instance(10, 99, &[]);
        if reachable {
            dump.root(0xff, 10);
        }
        let fixture = dump.finish();
        let engine = fixture.engine();
        let histogram = engine.histogram(None).unwrap();
        let rows = histogram.get("classes").unwrap().as_list().unwrap();
        let unknown = rows
            .iter()
            .find(|row| row.get("className").unwrap().as_str().unwrap() == "<unknown>")
            .unwrap();
        assert_eq!(
            unknown.get("reachableShallowSize").unwrap(),
            &if reachable {
                Value::Null
            } else {
                Value::Int(0)
            }
        );
    }
}

#[test]
fn class_static_storage_uses_declared_widths_in_detail_and_retention() {
    let mut dump = Dump::new();
    dump.class(1, "java.lang.Class", 0, 24, &[]);
    let mut static_names = Vec::new();
    for ty in [2, 4, 8, 5, 9, 6, 10, 7, 11] {
        static_names.push((dump.string(&format!("field{ty}")), ty));
    }
    dump.class(2, "Represented", 0, 0, &[]);
    // Replace the empty static/instance field tables with independent records.
    dump.heap.truncate(dump.heap.len() - 4);
    dump.heap.extend((static_names.len() as u16).to_be_bytes());
    for (name, ty) in static_names {
        dump.heap.extend(name.to_be_bytes());
        dump.heap.push(ty);
        let width = match ty {
            2 | 6 | 10 => 4,
            4 | 8 => 1,
            5 | 9 => 2,
            7 | 11 => 8,
            _ => unreachable!(),
        };
        dump.heap.extend(vec![0; width]);
    }
    dump.heap.extend(0u16.to_be_bytes());
    dump.class(3, "Owner", 0, 8, &[("type", 2)]);
    dump.instance(10, 3, &2u32.to_be_bytes());
    dump.root(0xff, 10);
    let fixture = dump.finish();
    let engine = fixture.engine();
    // Base24 + object4 + bool/byte2 + char/short4 + float/int8 + double/long16.
    assert_eq!(engine.shallow(node(&engine, 2) as u32), 58);
    assert_eq!(engine.analysis.retained[node(&engine, 2)], 58);
    assert_eq!(engine.analysis.retained[node(&engine, 10)], 66);
    assert_eq!(
        engine
            .detail(2, 0, 20)
            .unwrap()
            .get("shallowSize")
            .unwrap()
            .as_int()
            .unwrap(),
        58
    );
    assert_eq!(engine.shallow(node(&engine, 1) as u32), 24);
}

#[test]
fn repeated_root_records_preserve_metadata_but_not_traversal_seeds() {
    let mut dump = Dump::new();
    dump.class(1, "Node", 0, 8, &[("child", 2)]);
    dump.instance(10, 1, &11u32.to_be_bytes());
    dump.instance(11, 1, &0u32.to_be_bytes());
    for _ in 0..5 {
        dump.root(0x03, 10);
        dump.heap.extend(7u32.to_be_bytes());
        dump.heap.extend(2u32.to_be_bytes());
        dump.root(0x01, 10);
        dump.heap.extend(99u32.to_be_bytes());
    }
    let fixture = dump.finish();
    let engine = fixture.engine();
    assert_eq!(engine.heap.roots.len(), 10);
    for kind in ["JAVA_FRAME", "JNI_GLOBAL"] {
        assert_eq!(
            engine
                .heap
                .roots
                .iter()
                .filter(|r| r.id == 10 && r.kind == kind)
                .count(),
            5
        );
    }
    assert_eq!(engine.analysis.traversal.roots.len(), 1);
    assert_eq!(engine.analysis.traversal.depth[node(&engine, 11)], 1);
    assert_eq!(engine.analysis.retained[node(&engine, 10)], 16);
}

#[test]
fn total_retained_includes_native_owner_and_dominated_bytes_once() {
    let mut dump = Dump::new();
    dump.class(1, "java.lang.ref.Reference", 0, 4, &[("referent", 2)]);
    dump.class(2, "sun.misc.Cleaner", 1, 8, &[("thunk", 2)]);
    dump.class(
        3,
        "libcore.util.NativeAllocationRegistry$CleanerThunk",
        0,
        4,
        &[("this$0", 2)],
    );
    dump.class(
        4,
        "libcore.util.NativeAllocationRegistry",
        0,
        8,
        &[("size", 11)],
    );
    dump.class(5, "Owner", 0, 8, &[]);
    dump.class(6, "Parent", 0, 4, &[("child", 2)]);
    let mut cleaner = 11u32.to_be_bytes().to_vec();
    cleaner.extend(13u32.to_be_bytes());
    dump.instance(10, 2, &cleaner);
    dump.instance(11, 3, &12u32.to_be_bytes());
    dump.instance(12, 4, &4096u64.to_be_bytes());
    dump.instance(13, 5, &[]);
    dump.instance(14, 6, &13u32.to_be_bytes());
    dump.root(0xff, 10);
    dump.root(0xff, 14);
    let fixture = dump.finish();
    let engine = fixture.engine();
    // Perflib resetRetainedSize starts with getSize() + nativeSize.
    assert_eq!(engine.analysis.retained[node(&engine, 13)], 4104);
    assert_eq!(engine.analysis.retained[node(&engine, 14)], 4108);
    assert_eq!(engine.analysis.retained[node(&engine, 10)], 20);
    let histogram = engine.histogram(None).unwrap();
    let rows = histogram.get("classes").unwrap().as_list().unwrap();
    let reachable: i64 = rows
        .iter()
        .map(|row| row.get("reachableShallowSize").unwrap().as_int().unwrap())
        .sum();
    assert_eq!(reachable, 32);
    let detail = engine.detail(14, 0, 20).unwrap();
    assert_eq!(detail.get("retainedSize").unwrap().as_int().unwrap(), 4108);
    assert_eq!(
        detail.get("retainedNativeSize").unwrap().as_int().unwrap(),
        4096
    );
    let owner = engine.detail(13, 0, 20).unwrap();
    assert_eq!(owner.get("shallowSize").unwrap().as_int().unwrap(), 8);
    assert_eq!(owner.get("nativeSize").unwrap().as_int().unwrap(), 4096);
}

#[test]
fn native_attribution_preserves_unavailable_zero_and_positive_sizes() {
    for native_size in [None, Some(0u64), Some(4096)] {
        let mut dump = Dump::new();
        dump.class(
            1,
            "sun.misc.Cleaner",
            0,
            8,
            &[("referent", 2), ("thunk", 2)],
        );
        dump.class(
            2,
            "libcore.util.NativeAllocationRegistry$CleanerThunk",
            0,
            4,
            &[("this$0", 2)],
        );
        dump.class(
            3,
            "libcore.util.NativeAllocationRegistry",
            0,
            8,
            if native_size.is_some() {
                &[("size", 11)]
            } else {
                &[]
            },
        );
        dump.class(4, "Owner", 0, 8, &[]);
        let mut cleaner = 13u32.to_be_bytes().to_vec();
        cleaner.extend(11u32.to_be_bytes());
        dump.instance(10, 1, &cleaner);
        dump.instance(11, 2, &12u32.to_be_bytes());
        let registry = native_size
            .map(|size| size.to_be_bytes().to_vec())
            .unwrap_or_default();
        dump.instance(12, 3, &registry);
        dump.instance(13, 4, &[]);
        dump.root(0xff, 13);
        let fixture = dump.finish();
        let engine = fixture.engine();
        let expected_native = native_size
            .map(|size| Value::Int(size as i64))
            .unwrap_or(Value::Null);
        let expected_retained = 8 + native_size.unwrap_or(0);

        let detail = engine.detail(13, 0, 20).unwrap();
        assert_eq!(detail.get("nativeSize").unwrap(), &expected_native);
        assert_eq!(detail.get("retainedNativeSize").unwrap(), &expected_native);
        assert_eq!(
            detail.get("retainedSize").unwrap().as_int().unwrap() as u64,
            expected_retained
        );

        let histogram = engine.histogram(None).unwrap();
        let owner = histogram
            .get("classes")
            .unwrap()
            .as_list()
            .unwrap()
            .iter()
            .find(|row| row.get("className").unwrap().as_str().unwrap() == "Owner")
            .unwrap();
        assert_eq!(owner.get("nativeSize").unwrap(), &expected_native);
        assert_eq!(owner.get("retainedNativeSize").unwrap(), &expected_native);
        assert_eq!(owner.get("reachableNativeSize").unwrap(), &expected_native);

        let instances = engine
            .instances(&["Owner".into()], None, 0, 20, true, None)
            .unwrap();
        let owner = &instances.get("rows").unwrap().as_list().unwrap()[0];
        assert_eq!(owner.get("nativeSize").unwrap(), &expected_native);
        assert_eq!(owner.get("retainedNativeSize").unwrap(), &expected_native);

        // Standalone analysis builders must retain the same native bytes as Engine.
        let heap = Heap::open(&fixture.0).unwrap();
        let standalone = analysis::Analysis::build(&heap).unwrap();
        assert_eq!(standalone.retained, engine.analysis.retained);
        assert_eq!(standalone.immediate, engine.analysis.immediate);
        assert_eq!(standalone.retained[node(&engine, 13)], expected_retained);
    }
}

#[test]
fn class_bytes_are_shared_by_detail_and_retention() {
    let mut dump = Dump::new();
    dump.class(1, "java.lang.Class", 0, 24, &[]);
    dump.class(2, "Owner", 0, 8, &[("type", 2)]);
    dump.class(3, "Represented", 0, 100, &[]);
    dump.instance(10, 2, &3u32.to_be_bytes());
    dump.root(0xff, 10);
    let fixture = dump.finish();
    let engine = fixture.engine();
    let represented = node(&engine, 3);
    assert_eq!(
        engine
            .detail(3, 0, 20)
            .unwrap()
            .get("shallowSize")
            .unwrap()
            .as_int()
            .unwrap(),
        24
    );
    assert_eq!(engine.analysis.retained[represented], 24);
    assert_eq!(engine.analysis.retained[node(&engine, 10)], 32);
    assert_eq!(
        engine.analysis.immediate[represented],
        node(&engine, 10) as u32
    );
}

#[test]
fn unreachable_is_provenance_not_a_strong_root() {
    let mut dump = Dump::new();
    dump.class(1, "Node", 0, 8, &[("child", 2)]);
    dump.instance(10, 1, &11u32.to_be_bytes());
    dump.instance(11, 1, &0u32.to_be_bytes());
    dump.instance(12, 1, &0u32.to_be_bytes());
    dump.root(0x90, 10);
    dump.root(0x90, 12);
    dump.root(0xff, 12);
    let fixture = dump.finish();
    let engine = fixture.engine();
    assert_eq!(engine.heap.roots[0].kind, "UNREACHABLE");
    for id in [10, 11] {
        let n = node(&engine, id);
        assert_eq!(engine.analysis.traversal.depth[n], NONE);
        assert_eq!(engine.analysis.immediate[n], NONE);
        assert_eq!(
            engine.chain(n as u32, &engine.analysis.traversal).unwrap(),
            Value::List(vec![])
        );
    }
    let chain = engine
        .chain(node(&engine, 12) as u32, &engine.analysis.traversal)
        .unwrap();
    assert_eq!(
        chain.as_list().unwrap()[0]
            .get("fieldName")
            .unwrap()
            .as_str()
            .unwrap(),
        "GC Root (UNKNOWN)"
    );
}

#[test]
fn slash_reference_names_do_not_make_weak_referents_strong() {
    for reference in [
        "WeakReference",
        "SoftReference",
        "PhantomReference",
        "CustomReference",
    ] {
        let mut dump = Dump::new();
        dump.class(1, "java/lang/ref/Reference", 0, 8, &[("referent", 2)]);
        dump.class(2, &format!("java/lang/ref/{reference}"), 1, 8, &[]);
        dump.class(3, "Holder", 0, 8, &[("referent", 2)]);
        dump.class(4, "Target", 0, 12, &[]);
        dump.instance(10, 2, &20u32.to_be_bytes());
        dump.instance(11, 3, &21u32.to_be_bytes());
        dump.instance(20, 4, &[]);
        dump.instance(21, 4, &[]);
        dump.root(0xff, 10);
        dump.root(0xff, 11);
        let fixture = dump.finish();
        let engine = fixture.engine();
        assert_eq!(
            engine.analysis.traversal.depth[node(&engine, 20)],
            NONE,
            "{reference}"
        );
        assert_eq!(engine.analysis.retained[node(&engine, 10)], 8);
        assert_eq!(engine.analysis.traversal.depth[node(&engine, 21)], 1);
        assert_eq!(engine.analysis.retained[node(&engine, 11)], 20);
    }
}

#[test]
fn slash_native_registry_names_attribute_owner_bytes() {
    let mut dump = Dump::new();
    dump.class(
        1,
        "sun/misc/Cleaner",
        0,
        8,
        &[("referent", 2), ("thunk", 2)],
    );
    dump.class(
        2,
        "libcore/util/NativeAllocationRegistry$CleanerThunk",
        0,
        4,
        &[("this$0", 2)],
    );
    dump.class(
        3,
        "libcore/util/NativeAllocationRegistry",
        0,
        8,
        &[("size", 11)],
    );
    dump.class(4, "Owner", 0, 8, &[]);
    let mut cleaner = 13u32.to_be_bytes().to_vec();
    cleaner.extend(11u32.to_be_bytes());
    dump.instance(10, 1, &cleaner);
    dump.instance(11, 2, &12u32.to_be_bytes());
    dump.instance(12, 3, &4096u64.to_be_bytes());
    dump.instance(13, 4, &[]);
    let fixture = dump.finish();
    let engine = fixture.engine();
    assert_eq!(
        engine
            .detail(13, 0, 20)
            .unwrap()
            .get("nativeSize")
            .unwrap()
            .as_int()
            .unwrap(),
        4096
    );
}

#[test]
fn identity_matching_preserves_raw_array_and_descriptor_shape() {
    let mut dump = Dump::new();
    for (id, name) in [
        (1, "java/lang/Class"),
        (2, "[[Ljava/lang/String;"),
        (3, "[I"),
        (4, "java/lang/String[][]"),
        (5, "Lexample/Thing;"),
    ] {
        dump.class(id, name, 0, 16, &[]);
    }
    let fixture = dump.finish();
    let engine = fixture.engine();
    assert_eq!(
        engine.class_names,
        [
            "java/lang/Class",
            "[[Ljava/lang/String;",
            "[I",
            "java/lang/String[][]",
            "Lexample/Thing;"
        ]
    );
    assert_eq!(engine.shallow(node(&engine, 1) as u32), 16);
    assert_eq!(
        analysis::assignable(&engine.heap, "java.lang.Class"),
        [true, false, false, false, false]
    );
    assert_eq!(
        analysis::assignable(&engine.heap, "java.lang.String"),
        [false; 5]
    );
}

#[test]
fn shadowed_primitive_fields_keep_both_slots() {
    let mut dump = Dump::new();
    dump.class(1, "Base", 0, 4, &[("count", 10)]);
    dump.class(2, "Derived", 1, 8, &[("count", 10)]);
    let mut data = 7u32.to_be_bytes().to_vec();
    data.extend(9u32.to_be_bytes());
    dump.instance(10, 2, &data);
    let fixture = dump.finish();
    let engine = fixture.engine();
    let detail = engine.detail(10, 0, 20).unwrap();
    assert_eq!(detail.get("fields").unwrap().as_list().unwrap().len(), 2);
    assert_eq!(
        engine
            .heap
            .values(&engine.heap.objects[0], 0, 2)
            .unwrap()
            .iter()
            .map(|v| v.bits)
            .collect::<Vec<_>>(),
        [7, 9]
    );
}

// Keep the value-decoding graph path as a test-only oracle for the specialized
// reference scanner. Compare both target order and original field/array slots.
fn value_graph_rows(heap: &Heap, names: &[String]) -> Vec<Vec<(u32, u32)>> {
    use aps_hprof_engine::model::ObjectKind;
    let reference_classes = analysis::assignable_named(heap, names, "java.lang.ref.Reference");
    let mut rows = Vec::new();
    for object in &heap.objects {
        let mut row = Vec::new();
        if matches!(object.kind, ObjectKind::PrimitiveArray { .. }) {
            rows.push(row);
            continue;
        }
        let instance = object.kind == ObjectKind::Instance;
        let layout = heap.fields_for(object.class_id);
        let weak = instance
            && heap.class_by_id.get(&object.class_id).is_some_and(|&i| {
                let name = names
                    .get(i)
                    .unwrap_or(&heap.classes[i].name)
                    .replace('/', ".");
                reference_classes[i]
                    || matches!(
                        name.as_str(),
                        "java.lang.ref.WeakReference"
                            | "java.lang.ref.SoftReference"
                            | "java.lang.ref.PhantomReference"
                    )
            });
        let count = if instance {
            analysis::observed_fields(heap, object, &layout)
        } else {
            object.count as usize
        };
        for start in (0..count).step_by(analysis::READ_CHUNK as usize) {
            let limit = (count - start).min(analysis::READ_CHUNK as usize) as u32;
            let values = heap.values(object, start as u32, limit).unwrap();
            assert_eq!(values.len(), limit as usize);
            for (local, value) in values.into_iter().enumerate() {
                if value.ty == 2
                    && value.bits != 0
                    && !(weak && value.name == "referent")
                    && let Some(target) = analysis::node_of(heap, value.bits)
                {
                    row.push((target, (start + local) as u32));
                }
            }
        }
        if instance {
            row.sort_by(|a, b| {
                layout[a.1 as usize]
                    .name
                    .cmp(&layout[b.1 as usize].name)
                    .then_with(|| {
                        (analysis::id_of(heap, a.0) as i64)
                            .cmp(&(analysis::id_of(heap, b.0) as i64))
                    })
            });
        }
        rows.push(row);
    }
    for class in &heap.classes {
        let mut fields: Vec<_> = class
            .static_fields
            .iter()
            .enumerate()
            .filter(|(_, value)| value.ty == 2 && value.bits != 0)
            .collect();
        fields.sort_by(|(ai, a), (bi, b)| {
            a.name
                .cmp(&b.name)
                .then_with(|| (a.bits as i64).cmp(&(b.bits as i64)))
                .then(ai.cmp(bi))
        });
        rows.push(
            fields
                .into_iter()
                .filter_map(|(slot, value)| {
                    analysis::node_of(heap, value.bits).map(|target| (target, slot as u32))
                })
                .collect(),
        );
    }
    rows
}

fn assert_graph_matches_values(heap: &Heap, names: &[String]) {
    use aps_hprof_engine::{edges::GraphStorage, model::ObjectKind};
    let expected = value_graph_rows(heap, names);
    #[cfg(unix)]
    let temp = std::env::temp_dir();
    #[cfg(unix)]
    let storages = [GraphStorage::Memory, GraphStorage::Disk(&temp)];
    #[cfg(not(unix))]
    let storages = [GraphStorage::Memory];
    for storage in storages {
        let graph = analysis::Graph::build_named_with_storage(heap, names, storage).unwrap();
        assert_eq!(graph.offsets.len(), expected.len() + 1);
        let mut offset = 0;
        for (source, row) in expected.iter().enumerate() {
            assert_eq!(graph.offsets[source] as usize, offset);
            let actual: Vec<_> = graph
                .range(source as u32)
                .map(|edge| graph.edge(edge).unwrap())
                .collect();
            assert_eq!(
                &actual,
                row,
                "source object 0x{:x}",
                analysis::id_of(heap, source as u32)
            );
            for &(_, slot) in row {
                let expected_name = if let Some(object) = heap.objects.get(source) {
                    if object.kind == ObjectKind::ObjectArray {
                        format!("[{slot}]")
                    } else {
                        heap.fields_for(object.class_id)[slot as usize].name.clone()
                    }
                } else {
                    heap.classes[source - heap.objects.len()].static_fields[slot as usize]
                        .name
                        .clone()
                };
                assert_eq!(
                    graph.field_name(heap, source as u32, slot).unwrap(),
                    expected_name
                );
            }
            offset += row.len();
        }
        assert_eq!(graph.offsets[expected.len()] as usize, offset);
        graph.check_storage().unwrap();
    }
}

#[test]
fn graph_reference_scan_matches_values_for_inheritance_short_payloads_and_statics() {
    let mut dump = Dump::new();
    dump.class(
        1,
        "Base",
        0,
        32,
        &[
            ("shadow", 2),
            ("byte", 8),
            ("null", 2),
            ("short", 9),
            ("class", 2),
            ("int", 10),
            ("double", 7),
            ("dangling", 2),
            ("flag", 4),
        ],
    );
    dump.class(
        2,
        "Node",
        1,
        58,
        &[
            ("z", 2),
            ("char", 5),
            ("shadow", 2),
            ("float", 6),
            ("long", 11),
            ("a", 2),
        ],
    );
    dump.class(3, "Target", 0, 8, &[]);
    let mut data = Vec::new();
    data.extend(41u32.to_be_bytes());
    data.extend(40u16.to_be_bytes());
    data.extend(40u32.to_be_bytes());
    data.extend(40u32.to_be_bytes());
    data.extend(40u64.to_be_bytes());
    data.extend(3u32.to_be_bytes());
    data.extend(41u32.to_be_bytes());
    data.push(40);
    data.extend(0u32.to_be_bytes());
    data.extend(40u16.to_be_bytes());
    data.extend(3u32.to_be_bytes());
    data.extend(40u32.to_be_bytes());
    data.extend(40u64.to_be_bytes());
    data.extend(999u32.to_be_bytes());
    data.push(1);
    assert_eq!(data.len(), 58);
    for (index, length) in [0, 4, 6, 10, 14, 22, 26, 30, 31, 35, 37, 41, 45, 53, 57, 58]
        .into_iter()
        .enumerate()
    {
        dump.instance(100 + index as u32, 2, &data[..length]);
    }
    data.extend([0xde, 0xad, 0xbe, 0xef]);
    dump.instance(120, 2, &data); // Fully decoded fields plus ignored trailing bytes.
    dump.instance(121, 999, &[0; 19]); // Missing class has no known reference slots.
    dump.instance(40, 3, &[]);
    dump.instance(41, 3, &[]);
    let fixture = dump.finish();
    let mut heap = Heap::open(&fixture.0).unwrap();
    let ci = heap.class_by_id[&3];
    heap.classes[ci].static_fields = vec![
        aps_hprof_engine::model::Value {
            name: "same".into(),
            ty: 2,
            bits: 41,
        },
        aps_hprof_engine::model::Value {
            name: "same".into(),
            ty: 2,
            bits: 40,
        },
        aps_hprof_engine::model::Value {
            name: "primitive".into(),
            ty: 11,
            bits: 40,
        },
        aps_hprof_engine::model::Value {
            name: "null".into(),
            ty: 2,
            bits: 0,
        },
        aps_hprof_engine::model::Value {
            name: "dangling".into(),
            ty: 2,
            bits: 999,
        },
        aps_hprof_engine::model::Value {
            name: "class".into(),
            ty: 2,
            bits: 1,
        },
    ];
    assert_graph_matches_values(&heap, &[]);
    let rows = value_graph_rows(&heap, &[]);
    let complete = heap.object_by_id[&120];
    assert_eq!(
        rows[complete]
            .iter()
            .map(|&(_, slot)| slot)
            .collect::<Vec<_>>(),
        [5, 10, 2, 6, 0]
    );
    assert!(rows[heap.object_by_id[&121]].is_empty());

    // Cached/public indexes need not enumerate payloads in file-offset order.
    heap.objects.reverse();
    heap.object_by_id = heap
        .objects
        .iter()
        .enumerate()
        .map(|(index, object)| (object.id, index))
        .collect();
    assert_graph_matches_values(&heap, &[]);
}

#[test]
fn graph_reference_scan_keeps_weak_filtering_for_raw_slash_and_mapped_classes() {
    for mode in 0..3 {
        let mut dump = Dump::new();
        let originals = [
            "java.lang.ref.Reference",
            "sample.CustomReference",
            "java.lang.ref.WeakReference",
            "Holder",
            "Target",
        ];
        let names: Vec<_> = originals
            .iter()
            .enumerate()
            .map(|(i, name)| match mode {
                0 => name.to_string(),
                1 => name.replace('.', "/"),
                _ => format!("obfuscated_{i}"),
            })
            .collect();
        dump.class(1, &names[0], 0, 4, &[("referent", 2)]);
        dump.class(
            2,
            &names[1],
            1,
            16,
            &[("referent", 2), ("strong", 2), ("marker", 10)],
        );
        dump.class(3, &names[2], 0, 4, &[("referent", 2)]);
        dump.class(4, &names[3], 0, 4, &[("referent", 2)]);
        dump.class(5, &names[4], 0, 8, &[]);
        let data: Vec<_> = [40u32, 41, 40, 40]
            .into_iter()
            .flat_map(u32::to_be_bytes)
            .collect();
        dump.instance(10, 2, &data);
        dump.instance(11, 3, &40u32.to_be_bytes());
        dump.instance(12, 4, &40u32.to_be_bytes());
        dump.instance(40, 5, &[]);
        dump.instance(41, 5, &[]);
        let fixture = dump.finish();
        let heap = Heap::open(&fixture.0).unwrap();
        let mapped = if mode == 2 {
            originals.iter().map(|s| s.to_string()).collect()
        } else {
            Vec::new()
        };
        assert_graph_matches_values(&heap, &mapped);
        let rows = value_graph_rows(&heap, &mapped);
        assert_eq!(
            rows[heap.object_by_id[&10]],
            [(analysis::node_of(&heap, 41).unwrap(), 1)]
        );
        assert!(rows[heap.object_by_id[&11]].is_empty());
        assert_eq!(
            rows[heap.object_by_id[&12]],
            [(analysis::node_of(&heap, 40).unwrap(), 0)]
        );
    }
}

fn select_heap(dump: &mut Dump, serial: u32, name: &str) {
    let name_id = dump.string(name);
    dump.heap.push(0xfe);
    dump.heap.extend(serial.to_be_bytes());
    dump.heap.extend(name_id.to_be_bytes());
}

#[test]
fn histogram_cross_heap_merged_ancestors_are_filter_local() {
    let mut dump = Dump::new();
    dump.class(1, "a", 0, 8, &[("next", 2)]);
    dump.class(2, "b", 0, 8, &[("next", 2)]);
    select_heap(&mut dump, 1, "app");
    dump.instance(10, 1, &11u32.to_be_bytes());
    dump.instance(12, 1, &13u32.to_be_bytes());
    select_heap(&mut dump, 2, "image");
    dump.instance(11, 2, &12u32.to_be_bytes());
    dump.instance(13, 2, &0u32.to_be_bytes());
    dump.instance(14, 1, &0u32.to_be_bytes());
    dump.root(0xff, 10);
    let fixture = dump.finish();
    let engine = Engine::new(
        Heap::open(&fixture.0).unwrap(),
        BTreeMap::from([("a".into(), "Item".into()), ("b".into(), "Item".into())]),
    )
    .unwrap();
    for (heap, count, retained, reachable) in [
        (None, 7, 40, 32), // The two represented class objects also group as Item.
        (Some("App"), 2, 32, 16),
        (Some("Image"), 3, 32, 16),
    ] {
        let histogram = engine.histogram(heap).unwrap();
        let item = histogram
            .get("classes")
            .unwrap()
            .as_list()
            .unwrap()
            .iter()
            .find(|row| row.get("className").unwrap().as_str().unwrap() == "Item")
            .unwrap();
        assert_eq!(item.get("instanceCount").unwrap().as_int().unwrap(), count);
        assert_eq!(
            item.get("retainedSize").unwrap().as_int().unwrap(),
            retained
        );
        assert_eq!(
            item.get("reachableShallowSize").unwrap().as_int().unwrap(),
            reachable
        );
        assert_eq!(item.get("nativeSize").unwrap(), &Value::Null);
    }
    assert!(
        engine
            .histogram(Some("missing"))
            .unwrap()
            .get("classes")
            .unwrap()
            .as_list()
            .unwrap()
            .is_empty()
    );
    let heaps = vec![
        "Image".into(),
        "App".into(),
        "missing".into(),
        "Default".into(),
        "App".into(),
    ];
    let bundle = engine.histogram_bundle(&heaps).unwrap();
    assert_eq!(bundle.get("all").unwrap(), &engine.histogram(None).unwrap());
    for heap in &heaps {
        assert_eq!(
            bundle.get("perHeap").unwrap().get(heap).unwrap(),
            &engine.histogram(Some(heap)).unwrap()
        );
    }
    assert!(engine.histogram_bundle(&vec!["App".into(); 65]).is_err());
    assert!(engine.histogram_bundle(&["x".repeat(4097)]).is_err());
}

fn paged_visible_root_retained(engine: &Engine, heap: Option<&str>) -> Option<u64> {
    let mut offset = 0;
    let mut sum = None;
    loop {
        let page = engine.dominators(None, offset, 128).unwrap();
        let rows = page.get("rows").unwrap().as_list().unwrap();
        for row in rows {
            let object_id = row.get("objectId").unwrap().as_int().unwrap() as u64;
            if heap.is_some_and(|heap| {
                engine
                    .identity(object_id)
                    .unwrap()
                    .get("heapName")
                    .unwrap()
                    .as_str()
                    .ok()
                    != Some(heap)
            }) {
                continue;
            }
            let retained = row.get("retainedSize").unwrap().as_int().unwrap() as u64;
            sum = Some(sum.unwrap_or(0u64).checked_add(retained).unwrap());
        }
        offset += rows.len();
        if offset >= page.get("total").unwrap().as_int().unwrap() as usize {
            break;
        }
    }
    sum
}

#[test]
fn retained_summary_uses_visible_roots_and_keeps_empty_unavailable() {
    let mut dump = Dump::new();
    dump.class(1, "Node", 0, 8, &[("next", 2)]);
    select_heap(&mut dump, 1, "app");
    dump.instance(10, 1, &11u32.to_be_bytes());
    select_heap(&mut dump, 2, "image");
    dump.instance(11, 1, &0u32.to_be_bytes());
    dump.instance(12, 1, &0u32.to_be_bytes());
    dump.root(0xff, 10);
    let fixture = dump.finish();
    let engine = fixture.engine();
    assert_eq!(paged_visible_root_retained(&engine, None), Some(24));
    assert_eq!(paged_visible_root_retained(&engine, Some("App")), Some(16));
    assert_eq!(paged_visible_root_retained(&engine, Some("Image")), Some(8));
    assert_eq!(paged_visible_root_retained(&engine, Some("missing")), None);
    for heap in [None, Some("App"), Some("Image"), Some("missing")] {
        let expected = paged_visible_root_retained(&engine, heap)
            .map(|value| Value::Int(value as i64))
            .unwrap_or(Value::Null);
        assert_eq!(
            engine
                .retained_summary(heap)
                .unwrap()
                .get("retainedSize")
                .unwrap(),
            &expected
        );
    }

    let mut dump = Dump::new();
    dump.class(1, "Node", 0, 0, &[]);
    for id in 100..710 {
        dump.instance(id, 1, &[]);
    }
    let fixture = dump.finish();
    let engine = fixture.engine();
    assert_eq!(paged_visible_root_retained(&engine, None), Some(0));
    assert_eq!(
        engine
            .dominators(None, 0, 500)
            .unwrap()
            .get("total")
            .unwrap()
            .as_int()
            .unwrap(),
        610
    );
    assert_eq!(
        engine
            .retained_summary(None)
            .unwrap()
            .get("retainedSize")
            .unwrap(),
        &Value::Int(0)
    );

    let fixture = Dump::new().finish();
    let engine = fixture.engine();
    assert_eq!(paged_visible_root_retained(&engine, None), None);
    assert_eq!(
        engine
            .retained_summary(None)
            .unwrap()
            .get("retainedSize")
            .unwrap(),
        &Value::Null
    );
}

#[test]
fn aggregate_operations_keep_source_and_digest_guards() {
    let mut dump = Dump::new();
    dump.class(1, "Node", 0, 8, &[]);
    dump.instance(10, 1, &[]);
    let fixture = dump.finish();
    let digest = aps_hprof_engine::cache::sha256_file(&fixture.0).unwrap();
    let request = |operation: &str, args: Value, identity: &str| {
        Value::map([
            ("version", 1i64.into()),
            ("id", 1i64.into()),
            ("op", operation.into()),
            ("sourceDigest", identity.into()),
            ("args", args),
        ])
    };
    let mut session = aps_hprof_engine::Session::default();
    let (opened, _) = session.respond(&request(
        "open",
        Value::map([
            ("path", fixture.0.to_str().unwrap().into()),
            ("sourceDigest", digest.as_str().into()),
            ("mapping", Value::map([])),
            ("cacheDir", Value::Null),
        ]),
        &digest,
    ));
    assert_eq!(opened.get("status").unwrap().as_str().unwrap(), "ok");
    let operations = [
        ("checkSource", Value::map([])),
        ("retainedSummary", Value::map([("heapName", Value::Null)])),
        (
            "histogramBundle",
            Value::map([("heapNames", Value::List(vec!["Default".into()]))]),
        ),
    ];
    for (operation, args) in &operations {
        let (response, _) = session.respond(&request(operation, args.clone(), &digest));
        assert_eq!(response.get("status").unwrap().as_str().unwrap(), "ok");
        let (response, _) = session.respond(&request(operation, args.clone(), &"0".repeat(64)));
        assert_eq!(response.get("status").unwrap().as_str().unwrap(), "error");
    }
    std::fs::write(&fixture.0, b"changed").unwrap();
    for (operation, args) in operations {
        let (response, _) = session.respond(&request(operation, args, &digest));
        assert_eq!(response.get("status").unwrap().as_str().unwrap(), "error");
    }
}

#[test]
fn retained_summary_rejects_overflow_instead_of_publishing_wrapped_metrics() {
    let mut dump = Dump::new();
    dump.class(1, "Node", 0, 8, &[]);
    dump.instance(10, 1, &[]);
    dump.instance(11, 1, &[]);
    let fixture = dump.finish();
    let mut engine = fixture.engine();
    let first = node(&engine, 10);
    let second = node(&engine, 11);
    engine.analysis.retained[first] = u64::MAX;
    engine.analysis.retained[second] = 1;
    assert!(engine.retained_summary(None).is_err());
}

#[test]
fn hidden_class_parent_keeps_visible_roots_and_filter_local_histograms() {
    let mut dump = Dump::new();
    dump.class(1, "java.lang.Class", 0, 16, &[]);
    dump.class(2, "Holder", 0, 8, &[]);
    dump.class(3, "Node", 0, 8, &[("next", 2)]);
    select_heap(&mut dump, 1, "app");
    dump.instance(10, 3, &11u32.to_be_bytes());
    select_heap(&mut dump, 2, "image");
    dump.instance(11, 3, &0u32.to_be_bytes());
    dump.instance(12, 3, &0u32.to_be_bytes());
    dump.root(0xff, 2);
    let fixture = dump.finish();
    let mut heap = Heap::open(&fixture.0).unwrap();
    let holder = heap.class_by_id[&2];
    heap.classes[holder]
        .static_fields
        .push(aps_hprof_engine::model::Value {
            name: "static retained".into(),
            ty: 2,
            bits: 10,
        });
    let engine = Engine::new(heap, BTreeMap::new()).unwrap();
    assert_eq!(
        engine.analysis.immediate[node(&engine, 10)],
        analysis::node_of(&engine.heap, 2).unwrap()
    );
    assert_eq!(paged_visible_root_retained(&engine, None), Some(24));
    for (heap, retained) in [(None, 24), (Some("App"), 16), (Some("Image"), 8)] {
        assert_eq!(
            engine
                .retained_summary(heap)
                .unwrap()
                .get("retainedSize")
                .unwrap(),
            &Value::Int(retained)
        );
    }
    for (heap, retained, count) in [(None, 40, 4), (Some("App"), 16, 1), (Some("Image"), 16, 2)] {
        let histogram = engine.histogram(heap).unwrap();
        let row = histogram
            .get("classes")
            .unwrap()
            .as_list()
            .unwrap()
            .iter()
            .find(|row| row.get("className").unwrap().as_str().unwrap() == "Node")
            .unwrap();
        assert_eq!(row.get("retainedSize").unwrap(), &Value::Int(retained));
        assert_eq!(row.get("instanceCount").unwrap(), &Value::Int(count));
    }
}

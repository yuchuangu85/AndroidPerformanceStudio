use aps_hprof_engine::{
    analysis::{id_of, node_of, NONE},
    model::Heap,
    query::{original_name, Engine},
    wire::{self, Value},
    Session,
};
use std::collections::{BTreeMap, HashMap};
use std::fs::{self, OpenOptions};
use std::io::{Cursor, Write};
use std::path::PathBuf;
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicU64, Ordering};

static NEXT: AtomicU64 = AtomicU64::new(0);
struct File(PathBuf);
impl Drop for File {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.0);
    }
}
struct Fixture {
    bytes: Vec<u8>,
    dump: Vec<u8>,
    strings: HashMap<String, u64>,
    width: usize,
}
impl Fixture {
    fn new(width: usize) -> Self {
        let mut bytes = b"JAVA PROFILE 1.0.3\0".to_vec();
        bytes.extend((width as u32).to_be_bytes());
        bytes.extend(1234u64.to_be_bytes());
        Self {
            bytes,
            dump: vec![],
            strings: HashMap::new(),
            width,
        }
    }
    fn id(&self, id: u64) -> Vec<u8> {
        if self.width == 4 {
            (id as u32).to_be_bytes().to_vec()
        } else {
            id.to_be_bytes().to_vec()
        }
    }
    fn record(&mut self, tag: u8, data: &[u8]) {
        self.bytes.push(tag);
        self.bytes.extend(0u32.to_be_bytes());
        self.bytes.extend((data.len() as u32).to_be_bytes());
        self.bytes.extend(data);
    }
    fn string(&mut self, name: &str) -> u64 {
        if let Some(&id) = self.strings.get(name) {
            return id;
        }
        let id = 0x10000 + self.strings.len() as u64;
        let mut data = self.id(id);
        data.extend(name.as_bytes());
        self.record(1, &data);
        self.strings.insert(name.into(), id);
        id
    }
    fn value(&self, ty: u8, bits: u64) -> Vec<u8> {
        match ty {
            2 => self.id(bits),
            4 | 8 => vec![bits as u8],
            5 | 9 => (bits as u16).to_be_bytes().to_vec(),
            6 | 10 => (bits as u32).to_be_bytes().to_vec(),
            7 | 11 => bits.to_be_bytes().to_vec(),
            _ => panic!("test type"),
        }
    }
    fn class(
        &mut self,
        id: u64,
        name: &str,
        parent: u64,
        size: u32,
        fields: &[(&str, u8)],
        statics: &[(&str, u8, u64)],
    ) {
        let name_id = self.string(name);
        let mut load = 1u32.to_be_bytes().to_vec();
        load.extend(self.id(id));
        load.extend(0u32.to_be_bytes());
        load.extend(self.id(name_id));
        self.record(2, &load);
        let mut data = vec![0x20];
        data.extend(self.id(id));
        data.extend(0u32.to_be_bytes());
        data.extend(self.id(parent));
        data.extend(vec![0; self.width * 5]);
        data.extend(size.to_be_bytes());
        data.extend(0u16.to_be_bytes());
        data.extend((statics.len() as u16).to_be_bytes());
        for &(name, ty, bits) in statics {
            let name_id = self.string(name);
            data.extend(self.id(name_id));
            data.push(ty);
            data.extend(self.value(ty, bits));
        }
        data.extend((fields.len() as u16).to_be_bytes());
        for &(name, ty) in fields {
            let name_id = self.string(name);
            data.extend(self.id(name_id));
            data.push(ty);
        }
        self.dump.extend(data);
    }
    fn instance(&mut self, id: u64, class: u64, values: &[(u8, u64)]) {
        let payload: Vec<_> = values
            .iter()
            .flat_map(|(ty, bits)| self.value(*ty, *bits))
            .collect();
        let mut data = vec![0x21];
        data.extend(self.id(id));
        data.extend(0u32.to_be_bytes());
        data.extend(self.id(class));
        data.extend((payload.len() as u32).to_be_bytes());
        data.extend(payload);
        self.dump.extend(data);
    }
    fn array(&mut self, id: u64, class: u64, ids: &[u64]) {
        let mut data = vec![0x22];
        data.extend(self.id(id));
        data.extend(0u32.to_be_bytes());
        data.extend((ids.len() as u32).to_be_bytes());
        data.extend(self.id(class));
        for &id in ids {
            data.extend(self.id(id));
        }
        self.dump.extend(data);
    }
    fn primitive(&mut self, id: u64, ty: u8, values: &[u64], has_data: bool) {
        let mut data = vec![if has_data { 0x23 } else { 0xc3 }];
        data.extend(self.id(id));
        data.extend(0u32.to_be_bytes());
        data.extend((values.len() as u32).to_be_bytes());
        data.push(ty);
        if has_data {
            for &bits in values {
                data.extend(self.value(ty, bits));
            }
        }
        self.dump.extend(data);
    }
    fn root(&mut self, id: u64) {
        self.dump.push(5);
        self.dump.extend(self.id(id));
    }
    fn heap(&mut self, name: &str) {
        let id = self.string(name);
        self.dump.push(0xfe);
        self.dump.extend(1u32.to_be_bytes());
        self.dump.extend(self.id(id));
    }
    fn file(mut self) -> File {
        self.record(0x1c, &self.dump.clone());
        let path = std::env::temp_dir().join(format!(
            "aps-rust-query-{}-{}.hprof",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&path)
            .unwrap();
        file.write_all(&self.bytes).unwrap();
        File(path)
    }
}
fn rich() -> File {
    let mut f = Fixture::new(8);
    f.heap("app");
    f.class(100, "java.lang.Class", 0, 24, &[], &[]);
    f.class(101, "a", 0, 16, &[("next", 2), ("x", 10)], &[]);
    f.class(
        102,
        "java.lang.ref.Reference",
        0,
        8,
        &[("referent", 2)],
        &[],
    );
    f.class(103, "java.lang.ref.WeakReference", 102, 8, &[], &[]);
    f.class(104, "[La;", 0, 0, &[], &[]);
    f.class(
        105,
        "java.lang.String",
        0,
        24,
        &[("value", 2), ("offset", 10), ("count", 10)],
        &[],
    );
    f.class(
        106,
        "android.app.Activity",
        0,
        4,
        &[("mDestroyed", 4), ("mFinished", 4)],
        &[],
    );
    f.class(107, "q.a", 106, 24, &[("held", 2)], &[]);
    f.class(
        108,
        "androidx.fragment.app.Fragment",
        0,
        8,
        &[("mFragmentManager", 2)],
        &[],
    );
    f.class(
        109,
        "sample.Holder",
        0,
        64,
        &[
            ("first", 2),
            ("second", 2),
            ("weak", 2),
            ("text", 2),
            ("activity", 2),
            ("fragment", 2),
            ("bitmap", 2),
            ("values", 2),
        ],
        &[("singleton", 2, 1), ("flag", 10, 42)],
    );
    f.class(
        110,
        "android.graphics.Bitmap",
        0,
        32,
        &[
            ("mWidth", 10),
            ("mHeight", 10),
            ("mRowBytes", 10),
            ("mId", 11),
        ],
        &[],
    );
    f.class(111, "b", 0, 16, &[("next", 2), ("x", 10)], &[]);
    f.instance(
        1,
        109,
        &[
            (2, 2),
            (2, 2),
            (2, 4),
            (2, 5),
            (2, 6),
            (2, 7),
            (2, 8),
            (2, 9),
        ],
    );
    f.instance(2, 101, &[(2, 3), (10, u64::MAX)]);
    f.instance(3, 101, &[(2, 0), (10, 12)]);
    f.instance(4, 103, &[(2, 10)]);
    f.instance(5, 105, &[(2, 11), (10, 1), (10, 3)]);
    f.instance(6, 107, &[(2, 0), (4, 1), (4, 0)]);
    f.instance(7, 108, &[(2, 0)]);
    f.instance(8, 110, &[(10, 2), (10, 3), (10, 16), (11, 77)]);
    f.array(9, 104, &[2, 2, 0, u64::MAX]);
    f.instance(10, 101, &[(2, 0), (10, 0)]);
    f.primitive(11, 5, &[120, 104, 101, 108, 108, 111], true);
    f.primitive(12, 8, &[0; 5], false);
    f.instance(u64::MAX, 111, &[(2, 0), (10, 0)]);
    f.root(109);
    f.file()
}
fn engine(file: &File, mapping: &[(&str, &str)]) -> Engine {
    Engine::new(
        Heap::open(&file.0).unwrap(),
        mapping
            .iter()
            .map(|(a, b)| (a.to_string(), b.to_string()))
            .collect(),
    )
    .unwrap()
}
fn field<'a>(value: &'a Value, key: &str) -> &'a Value {
    value.get(key).unwrap()
}
fn integer(value: &Value, key: &str) -> i64 {
    field(value, key).as_int().unwrap()
}
fn list<'a>(value: &'a Value, key: &str) -> &'a [Value] {
    field(value, key).as_list().unwrap()
}
fn class<'a>(histogram: &'a Value, name: &str) -> &'a Value {
    list(histogram, "classes")
        .iter()
        .find(|v| field(v, "className").as_str().unwrap() == name)
        .unwrap()
}

#[test]
fn graph_weak_edges_class_objects_dedup_and_nullable_metrics() {
    let file = rich();
    let e = engine(&file, &[]);
    let node = |id| node_of(&e.heap, id).unwrap();
    assert_eq!(e.analysis.retained[node(2) as usize], 32);
    assert_eq!(e.analysis.retained[node(10) as usize], 16);
    assert_eq!(e.analysis.traversal.depth[node(10) as usize], NONE);
    assert_eq!(e.analysis.immediate[node(1) as usize], node(109));
    let histogram = e.histogram(Some("App")).unwrap();
    let a = class(&histogram, "a");
    assert_eq!(integer(a, "instanceCount"), 4);
    assert_eq!(integer(a, "shallowSize"), 72);
    assert_eq!(integer(a, "retainedSize"), 48);
    assert_eq!(field(a, "nativeSize"), &Value::Null);
    let summary = field(&histogram, "summary");
    assert_eq!(
        integer(summary, "objectCount"),
        (e.heap.objects.len() + e.heap.classes.len()) as i64
    );
    assert!(integer(&list(summary, "heapSegments")[0], "sizeBytes") > 0);
    assert_eq!(e.identity(999).unwrap(), Value::Null);
}

#[test]
fn signed_global_paging_anchor_and_true_immediate_children() {
    let file = rich();
    let e = engine(&file, &[("a", "Original"), ("b", "Original")]);
    let page = e
        .instances(&["Original".into()], None, 0, 2, false, None)
        .unwrap();
    assert_eq!(integer(&list(&page, "rows")[0], "objectId"), -1);
    assert_eq!(integer(&list(&page, "rows")[1], "index"), 2);
    let anchor = e
        .instances(&["Original".into()], None, 0, 2, false, Some(3))
        .unwrap();
    assert_eq!(integer(&anchor, "offset"), 2);
    assert_eq!(integer(&list(&anchor, "rows")[0], "objectId"), 3);
    let ordinary = e
        .instances(&["Original".into()], None, 0, 4096, true, None)
        .unwrap();
    assert_eq!(integer(&ordinary, "total"), 4);
    assert!(e.instances(&[], None, 0, 4097, false, None).is_err());
    let children = e.dominators(Some(2), 0, 20).unwrap();
    assert_eq!(integer(&children, "total"), 1);
    let row = &list(&children, "rows")[0];
    assert_eq!(integer(row, "objectId"), 3);
    assert_eq!(integer(row, "parentObjectId"), 2);
    assert_eq!(integer(row, "depth"), 3);
    let roots = e.dominators(None, 0, 4096).unwrap();
    assert!(!list(&roots, "rows")
        .iter()
        .any(|r| integer(r, "objectId") == 3));
    assert!(list(&roots, "rows")
        .iter()
        .any(|r| integer(r, "objectId") == 1
            && integer(r, "parentObjectId") == 109
            && integer(r, "depth") == 1));
    assert!(list(&roots, "rows").iter().all(|r| e
        .heap
        .object_by_id
        .contains_key(&(integer(r, "objectId") as u64))));
    assert_eq!(integer(&e.dominators(Some(999), 0, 1).unwrap(), "total"), 0);
}

#[test]
fn merged_class_pages_preserve_heap_filtered_per_class_ordinals() {
    let mut f = Fixture::new(8);
    f.heap("app");
    f.class(100, "A", 0, 8, &[], &[]);
    f.class(101, "B", 0, 8, &[], &[]);
    for id in 1..=8 {
        f.instance(id, if id == 6 || id == 8 { 101 } else { 100 }, &[]);
    }
    f.heap("zygote");
    f.instance(u64::MAX, 100, &[]);
    f.instance(u64::MAX - 1, 101, &[]);
    let file = f.file();
    let e = engine(&file, &[]);
    let names = vec!["A".into(), "B".into()];
    let merged = e.instances(&names, Some("App"), 0, 8, true, None).unwrap();
    assert_eq!(
        list(&merged, "rows")
            .iter()
            .map(|row| integer(row, "objectId"))
            .collect::<Vec<_>>(),
        vec![1, 2, 3, 4, 5, 6, 7, 8]
    );
    assert_eq!(
        list(&merged, "rows")
            .iter()
            .map(|row| integer(row, "index"))
            .collect::<Vec<_>>(),
        vec![1, 2, 3, 4, 5, 1, 6, 2]
    );
    let page = e.instances(&names, Some("App"), 5, 2, true, None).unwrap();
    assert_eq!(
        list(&page, "rows")
            .iter()
            .map(|row| integer(row, "index"))
            .collect::<Vec<_>>(),
        vec![1, 6]
    );
    let anchor = e
        .instances(&names, Some("App"), 0, 3, true, Some(8))
        .unwrap();
    assert_eq!(integer(&anchor, "offset"), 6);
    assert_eq!(
        list(&anchor, "rows")
            .iter()
            .map(|row| integer(row, "index"))
            .collect::<Vec<_>>(),
        vec![6, 2]
    );
    let all = e.instances(&names, None, 0, 20, true, None).unwrap();
    let rows = list(&all, "rows");
    assert_eq!(integer(&rows[0], "objectId"), -2);
    assert_eq!(integer(&rows[0], "index"), 1);
    assert_eq!(integer(&rows[2], "objectId"), 1);
    assert_eq!(integer(&rows[2], "index"), 2);
}

#[test]
fn raw_histograms_preserve_many_to_one_groups_and_parser_names() {
    let file = rich();
    let e = engine(&file, &[("a", "Original"), ("b", "Original")]);
    let raw = e.raw_histogram(None).unwrap();
    assert_eq!(integer(class(&raw, "a"), "instanceCount"), 4);
    assert_eq!(integer(class(&raw, "b"), "instanceCount"), 2);
    assert_eq!(
        integer(
            class(&e.histogram(None).unwrap(), "Original"),
            "instanceCount"
        ),
        6
    );
    assert_eq!(
        field(
            class(&e.histogram(None).unwrap(), "Original"),
            "obfuscatedClassName"
        ),
        &Value::Null
    );
    assert_eq!(e.heap.classes[e.heap.class_by_id[&101]].name, "a");
    assert_eq!(e.group_name(node_of(&e.heap, 9).unwrap()), "[LOriginal;");
    let mapping = BTreeMap::from([("a.b".into(), "example.Real".into())]);
    assert_eq!(original_name("a.b[][]", &mapping), "example.Real[][]");
    assert_eq!(original_name("[[La/b;", &mapping), "[[Lexample/Real;");
    assert_eq!(original_name("[[I", &mapping), "[[I");
}

#[test]
fn typed_detail_nulls_static_fields_strings_arrays_and_grouped_inbound() {
    let file = rich();
    let e = engine(&file, &[]);
    let detail = e.detail(2, 0, 200).unwrap();
    let fields = list(&detail, "fields");
    assert_eq!(field(&fields[0], "name").as_str().unwrap(), "next");
    assert_eq!(field(&fields[1], "displayValue").as_str().unwrap(), "-1");
    assert_eq!(
        field(&fields[1], "valueKind").as_str().unwrap(),
        "PRIMITIVE"
    );
    assert_eq!(integer(&fields[1], "shallowSize"), 4);
    let inbound = list(&detail, "references");
    assert_eq!(inbound.len(), 2);
    assert_eq!(
        field(&inbound[0], "name").as_str().unwrap(),
        "first, second"
    );
    assert_eq!(field(&inbound[1], "name").as_str().unwrap(), "Index 0, 1");
    assert_eq!(list(&detail, "referenceChain").len(), 3);
    let weak_target = e.detail(10, 0, 200).unwrap();
    assert!(list(&weak_target, "references").is_empty());
    assert!(list(&weak_target, "referenceChain").is_empty());
    let string = e.detail(5, 0, 200).unwrap();
    assert_eq!(field(&string, "stringPreview").as_str().unwrap(), "\"hel\"");
    let class = e.detail(109, 0, 200).unwrap();
    assert_eq!(
        field(&class, "className").as_str().unwrap(),
        "java.lang.Class"
    );
    assert!(list(&class, "fields")
        .iter()
        .any(|r| field(r, "name").as_str().unwrap() == "static flag"
            && field(r, "displayValue").as_str().unwrap() == "42"));
    let array = e.detail(9, 2, 2).unwrap();
    assert_eq!(integer(&array, "elementCount"), 4);
    assert_eq!(integer(&array, "arrayPageSize"), 2);
    let null = &list(&array, "fields")[0];
    assert_eq!(field(null, "valueKind").as_str().unwrap(), "NULL");
    assert_eq!(integer(null, "shallowSize"), 0);
    assert!(list(&e.detail(9, 100, 2).unwrap(), "fields").is_empty());
    let nodata = e.detail(12, 0, 200).unwrap();
    assert!(list(&nodata, "fields").is_empty());
    assert_eq!(integer(&nodata, "elementCount"), 5);
    assert_eq!(field(&nodata, "valueKind").as_str().unwrap(), "ARRAY");
    assert!(e.detail(2, 0, 201).is_err());
    assert_eq!(e.detail(999, 0, 200).unwrap(), Value::Null);
}

#[test]
fn reference_class_fallback_is_preserved_for_instance_and_static_fields_only() {
    for width in [4, 8] {
        let mut f = Fixture::new(width);
        f.class(100, "Target", 0, 8, &[], &[]);
        f.class(
            101,
            "Holder",
            0,
            40,
            &[
                ("nil", 2),
                ("unknown", 2),
                ("known", 2),
                ("klass", 2),
                ("number", 10),
            ],
            &[
                ("nil", 2, 0),
                ("unknown", 2, 999_999),
                ("known", 2, 2),
                ("klass", 2, 100),
            ],
        );
        f.class(102, "[LTarget;", 0, 0, &[], &[]);
        f.instance(1, 101, &[(2, 0), (2, 999_999), (2, 2), (2, 100), (10, 42)]);
        f.instance(2, 100, &[]);
        f.array(3, 102, &[0, 999_999, 2, 100]);
        let file = f.file();
        let e = engine(&file, &[]);
        for id in [1, 101] {
            let detail = e.detail(id, 0, 200).unwrap();
            let fields = list(&detail, "fields");
            assert_eq!(
                field(&fields[0], "targetClassName").as_str().unwrap(),
                "<unknown>"
            );
            assert_eq!(
                field(&fields[1], "targetClassName").as_str().unwrap(),
                "<unknown>"
            );
            assert_eq!(
                field(&fields[2], "targetClassName").as_str().unwrap(),
                "Target"
            );
            assert_eq!(
                field(&fields[3], "targetClassName").as_str().unwrap(),
                "java.lang.Class"
            );
            assert_eq!(field(&fields[0], "displayValue").as_str().unwrap(), "null");
            assert_eq!(field(&fields[1], "shallowSize"), &Value::Null);
            assert_eq!(field(&fields[1], "retainedSize"), &Value::Null);
            if id == 1 {
                assert_eq!(field(&fields[4], "targetClassName"), &Value::Null);
            }
        }
        let array = e.detail(3, 0, 200).unwrap();
        let fields = list(&array, "fields");
        assert_eq!(field(&fields[0], "targetClassName"), &Value::Null);
        assert_eq!(field(&fields[1], "targetClassName"), &Value::Null);
        assert_eq!(
            field(&fields[2], "targetClassName").as_str().unwrap(),
            "Target"
        );
        assert_eq!(
            field(&fields[3], "targetClassName").as_str().unwrap(),
            "java.lang.Class"
        );
        assert_eq!(
            field(
                &e.object_field("unknown", 999_999).unwrap(),
                "targetClassName"
            ),
            &Value::Null
        );
    }
}

#[test]
fn visible_dominator_roots_exclude_classes_but_preserve_hidden_parents_and_depth() {
    for width in [4, 8] {
        let mut f = Fixture::new(width);
        f.class(100, "java.lang.Object", 0, 8, &[], &[]);
        f.class(101, "java.lang.Class", 100, 24, &[], &[]);
        f.class(102, "a.Node", 100, 64, &[("next", 2), ("meta", 2)], &[]);
        f.class(104, "[La.Node;", 100, 0, &[], &[]);
        f.class(113, "Singleton", 100, 0, &[], &[("root", 2, 14)]);
        f.class(114, "HiddenClass", 100, 0, &[], &[]);
        f.instance(1, 102, &[(2, 2), (2, 114)]);
        f.instance(2, 102, &[(2, 0), (2, 0)]);
        f.instance(4, 102, &[(2, 0), (2, 0)]);
        f.array(14, 104, &[1]);
        f.root(113);
        let file = f.file();
        let e = engine(&file, &[]);
        let roots = e.dominators(None, 0, 100).unwrap();
        assert_eq!(integer(&roots, "total"), 2);
        let rows = list(&roots, "rows");
        assert_eq!(
            rows.iter()
                .map(|r| integer(r, "objectId"))
                .collect::<Vec<_>>(),
            vec![4, 14]
        );
        assert_eq!(integer(&rows[0], "depth"), 0);
        assert_eq!(field(&rows[0], "parentObjectId"), &Value::Null);
        assert_eq!(integer(&rows[1], "depth"), 1);
        assert_eq!(integer(&rows[1], "parentObjectId"), 113);
        assert!(integer(&rows[1], "retainedSize") > integer(&rows[0], "retainedSize"));
        for offset in 0..2 {
            let page = e.dominators(None, offset, 1).unwrap();
            assert_eq!(integer(&page, "total"), 2);
            assert_eq!(list(&page, "rows"), &rows[offset..offset + 1]);
        }
        let beyond = e.dominators(None, 2, 1).unwrap();
        assert_eq!(integer(&beyond, "total"), 2);
        assert!(list(&beyond, "rows").is_empty());
        let children = e.dominators(Some(1), 0, 100).unwrap();
        assert_eq!(integer(&children, "total"), 1);
        let child = &list(&children, "rows")[0];
        assert_eq!(integer(child, "objectId"), 2);
        assert_eq!(integer(child, "depth"), 3);
        assert_eq!(integer(child, "parentObjectId"), 1);
        assert_eq!(
            list(&e.dominators(Some(113), 0, 100).unwrap(), "rows"),
            &rows[1..]
        );
        assert_eq!(
            e.analysis.immediate[node_of(&e.heap, 114).unwrap() as usize],
            node_of(&e.heap, 1).unwrap()
        );
        assert_eq!(
            field(&e.identity(114).unwrap(), "kind").as_str().unwrap(),
            "CLASS"
        );
        assert!(list(&e.histogram(None).unwrap(), "classes")
            .iter()
            .any(|row| field(row, "className").as_str().unwrap() == "HiddenClass"));
    }
}

#[test]
fn lifecycle_bitmap_findings_mapping_and_explicit_absence() {
    let file = rich();
    let e = engine(&file, &[("q.a", "sample.MainActivity")]);
    let result = e.findings().unwrap();
    assert_eq!(list(&result, "lifecycleLeakObjects").len(), 2);
    let activity = &list(&result, "activityLeaks")[0];
    assert_eq!(
        field(activity, "className").as_str().unwrap(),
        "sample.MainActivity"
    );
    assert_eq!(integer(activity, "destroyedInstanceCount"), 1);
    assert_eq!(integer(activity, "liveInstanceCount"), 1);
    let bitmap = &list(&result, "bitmapInstances")[0];
    assert_eq!(integer(bitmap, "estimatedPixelBytes"), 48);
    assert_eq!(integer(bitmap, "bitmapId"), 77);
    assert_eq!(field(bitmap, "nativeSizeBytes"), &Value::Null);
    assert!(list(&result, "leakSuspects")
        .iter()
        .all(|v| field(v, "requiresManualVerification") == &Value::Bool(true)));
}

#[test]
fn typed_primitives_short_instances_and_rootless_depth_are_preserved() {
    for width in [4, 8] {
        let mut f = Fixture::new(width);
        f.class(100, "Partial", 0, 16, &[("x", 10), ("next", 2)], &[]);
        f.instance(1, 100, &[(10, 9)]);
        for (id, ty, value) in [
            (2, 4, 1),
            (3, 8, 255),
            (4, 9, 65535),
            (5, 10, 0xffff_ffff),
            (6, 6, 1.5f32.to_bits() as u64),
            (7, 7, f64::INFINITY.to_bits()),
            (8, 11, u64::MAX),
        ] {
            f.primitive(id, ty, &[value], true);
        }
        let file = f.file();
        let e = engine(&file, &[]);
        assert_eq!(list(&e.detail(1, 0, 200).unwrap(), "fields").len(), 1);
        assert_eq!(field(&e.detail(1, 0, 200).unwrap(), "depth"), &Value::Null);
        for (id, expected) in [
            (2, "true"),
            (3, "-1"),
            (4, "-1"),
            (5, "-1"),
            (6, "1.5"),
            (7, "Infinity"),
            (8, "-1"),
        ] {
            assert_eq!(
                field(
                    &list(&e.detail(id, 0, 200).unwrap(), "fields")[0],
                    "displayValue"
                )
                .as_str()
                .unwrap(),
                expected
            );
        }
        for (node, object) in e.heap.objects.iter().enumerate() {
            assert_eq!(e.analysis.retained[node], object.shallow_size);
            assert_eq!(id_of(&e.heap, node as u32), object.id);
        }
    }
}

fn request(id: i64, op: &str, args: Value) -> Value {
    Value::map([
        ("version", 1i64.into()),
        ("id", id.into()),
        ("op", op.into()),
        ("args", args),
    ])
}
fn open(file: &File) -> Value {
    request(
        1,
        "open",
        Value::map([
            ("path", file.0.to_str().unwrap().into()),
            ("sourceDigest", "snapshot-sha256".into()),
            ("mapping", Value::map([])),
        ]),
    )
}

#[test]
fn session_rejects_reopen_digest_mismatch_and_source_changes() {
    let file = rich();
    let mut session = Session::default();
    let response = session.respond(&open(&file)).0;
    assert_eq!(field(&response, "status").as_str().unwrap(), "ok");
    assert_eq!(
        field(&response, "sourceDigest").as_str().unwrap(),
        "snapshot-sha256"
    );
    assert_eq!(
        field(&session.respond(&open(&file)).0, "status")
            .as_str()
            .unwrap(),
        "error"
    );
    let raw = session
        .respond(&request(
            4,
            "rawHistogram",
            Value::map([("heapName", Value::Null)]),
        ))
        .0;
    assert_eq!(field(&raw, "status").as_str().unwrap(), "ok");
    let wrong = request(
        5,
        "identity",
        Value::map([("objectId", 2i64.into()), ("sourceDigest", "wrong".into())]),
    );
    assert!(field(&session.respond(&wrong).0, "error")
        .as_str()
        .unwrap()
        .contains("mismatch"));
    OpenOptions::new()
        .append(true)
        .open(&file.0)
        .unwrap()
        .write_all(&[0])
        .unwrap();
    let changed = session
        .respond(&request(
            6,
            "identity",
            Value::map([("objectId", 2i64.into())]),
        ))
        .0;
    assert!(field(&changed, "error")
        .as_str()
        .unwrap()
        .contains("changed"));
    let (response, close) = session.respond(&request(7, "close", Value::map([])));
    assert!(close);
    assert_eq!(field(&response, "payload"), &Value::Null);
}

#[test]
fn actual_sidecar_uses_only_framed_stdout_and_exits_on_close() {
    let file = rich();
    let mut input = Vec::new();
    for value in [
        open(&file),
        request(
            2,
            "detail",
            Value::map([
                ("objectId", 2i64.into()),
                ("arrayStart", 0i64.into()),
                ("arrayLimit", 200i64.into()),
            ]),
        ),
        request(3, "close", Value::map([])),
    ] {
        wire::write_frame(&mut input, &value).unwrap();
    }
    let mut child = Command::new(env!("CARGO_BIN_EXE_aps-hprof-engine"))
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
        .unwrap();
    child.stdin.take().unwrap().write_all(&input).unwrap();
    let output = child.wait_with_output().unwrap();
    assert!(
        output.status.success(),
        "{}",
        String::from_utf8_lossy(&output.stderr)
    );
    assert!(output.stderr.is_empty());
    let mut frames = Cursor::new(output.stdout);
    for id in [1, 2, 3] {
        let response = wire::decode(&wire::read_frame(&mut frames).unwrap().unwrap()).unwrap();
        assert_eq!(integer(&response, "id"), id);
        assert_eq!(field(&response, "status").as_str().unwrap(), "ok");
    }
    assert!(wire::read_frame(&mut frames).unwrap().is_none());
}

#[test]
fn shortest_roots_exclude_missing_lifecycle_fields_and_root_objects() {
    let mut f = Fixture::new(8);
    f.class(100, "android.app.Activity", 0, 1, &[("mDestroyed", 4)], &[]);
    f.class(
        101,
        "androidx.fragment.app.Fragment",
        0,
        8,
        &[("mFragmentManager", 2)],
        &[],
    );
    f.class(102, "Holder", 0, 16, &[("a", 2), ("z", 2)], &[]);
    f.instance(1, 102, &[(2, 2), (2, 3)]);
    f.instance(2, 102, &[(2, 4), (2, 0)]);
    f.instance(3, 102, &[(2, 0), (2, 4)]);
    f.instance(4, 100, &[(4, 1)]);
    f.instance(5, 100, &[(4, 1)]);
    f.instance(6, 101, &[]);
    f.instance(7, 100, &[(4, 1)]);
    f.root(1);
    f.root(5);
    f.root(6);
    let file = f.file();
    let e = engine(&file, &[]);
    let result = e.findings().unwrap();
    let lifecycle = list(&result, "lifecycleLeakObjects");
    assert_eq!(lifecycle.len(), 1);
    assert_eq!(integer(&lifecycle[0], "objectId"), 4);
    assert_eq!(integer(&e.detail(4, 0, 200).unwrap(), "depth"), 2);
    let detail = e.detail(4, 0, 200).unwrap();
    let chain = list(&detail, "referenceChain");
    assert_eq!(chain.len(), 3);
    assert_eq!(field(&chain[1], "fieldName").as_str().unwrap(), "a");
    assert_eq!(field(&e.detail(7, 0, 200).unwrap(), "depth"), &Value::Null);
}

#[test]
fn heap_filtered_retained_dedup_uses_only_selected_ancestors() {
    let mut f = Fixture::new(4);
    f.heap("zygote");
    f.class(100, "A", 0, 8, &[("next", 2)], &[]);
    f.instance(1, 100, &[(2, 2)]);
    f.root(1);
    f.heap("app");
    f.instance(2, 100, &[(2, 3)]);
    f.instance(3, 100, &[(2, 0)]);
    let file = f.file();
    let e = engine(&file, &[]);
    assert_eq!(
        integer(class(&e.histogram(None).unwrap(), "A"), "retainedSize"),
        24
    );
    assert_eq!(
        integer(
            class(&e.histogram(Some("App")).unwrap(), "A"),
            "retainedSize"
        ),
        16
    );
    assert_eq!(
        integer(
            class(&e.raw_histogram(Some("App")).unwrap(), "A"),
            "instanceCount"
        ),
        2
    );
    assert_eq!(
        integer(
            field(&e.histogram(Some("absent")).unwrap(), "summary"),
            "objectCount"
        ),
        0
    );
}

#[test]
fn arrays_have_numeric_inspector_bfs_and_lexical_deep_analysis_bfs() {
    let mut f = Fixture::new(8);
    f.class(100, "[LHolder;", 0, 0, &[], &[]);
    f.class(101, "Holder", 0, 8, &[("next", 2)], &[]);
    f.class(102, "android.graphics.Bitmap", 0, 16, &[], &[]);
    let mut elements = vec![0; 11];
    elements[2] = 2;
    elements[10] = 10;
    f.array(1, 100, &elements);
    f.instance(2, 101, &[(2, 3)]);
    f.instance(10, 101, &[(2, 3)]);
    f.instance(3, 102, &[]);
    f.root(1);
    let file = f.file();
    let e = engine(&file, &[]);
    let detail = e.detail(3, 0, 200).unwrap();
    assert_eq!(
        integer(&list(&detail, "referenceChain")[1], "targetObjectId"),
        2
    );
    let findings = e.findings().unwrap();
    let bitmap = &list(&findings, "bitmapInstances")[0];
    assert_eq!(
        integer(&list(bitmap, "referenceChain")[1], "targetObjectId"),
        10
    );
    assert_eq!(field(bitmap, "estimatedPixelBytes"), &Value::Null);
}

#[test]
fn inherited_shadowed_primitives_keep_last_declaration_without_losing_object_fields() {
    let mut f = Fixture::new(8);
    f.class(100, "Parent", 0, 16, &[("x", 10), ("same", 2)], &[]);
    f.class(101, "Child", 100, 32, &[("x", 10), ("same", 10)], &[]);
    f.instance(1, 101, &[(10, 1), (10, 2), (10, 3), (2, 0)]);
    let file = f.file();
    let e = engine(&file, &[]);
    let detail = e.detail(1, 0, 200).unwrap();
    let fields = list(&detail, "fields");
    assert_eq!(fields.len(), 3);
    assert_eq!(field(&fields[0], "name").as_str().unwrap(), "same");
    assert_eq!(field(&fields[1], "displayValue").as_str().unwrap(), "3");
    assert_eq!(field(&fields[2], "valueKind").as_str().unwrap(), "NULL");
}

#[test]
fn committed_fixture_analysis_and_queries_match_legacy_counts() {
    let path = PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../parser-hprof/src/test/resources/hprof/android-converted-sample.hprof");
    let e = Engine::new(Heap::open(&path).unwrap(), BTreeMap::new()).unwrap();
    let histogram = e.histogram(None).unwrap();
    assert_eq!(integer(field(&histogram, "summary"), "objectCount"), 2);
    assert_eq!(integer(field(&histogram, "summary"), "shallowSize"), 24);
    let rows = e
        .instances(
            &["com.example.ConvertedSample".into()],
            None,
            0,
            10,
            false,
            None,
        )
        .unwrap();
    assert_eq!(integer(&rows, "total"), 2);
    for row in list(&rows, "rows") {
        assert_ne!(
            e.detail(integer(row, "objectId") as u64, 0, 200).unwrap(),
            Value::Null
        );
    }
}

#[test]
fn oversized_complete_inbound_result_returns_error_not_empty_or_partial_success() {
    let mut f = Fixture::new(4);
    let name = "x".repeat(16 * 1024);
    f.class(100, "Holder", 0, 4, &[(name.as_str(), 2)], &[]);
    f.class(101, "Target", 0, 8, &[], &[]);
    f.instance(1, 101, &[]);
    for id in 2000..4100 {
        f.instance(id, 100, &[(2, 1)]);
    }
    let file = f.file();
    let mut input = Vec::new();
    for value in [
        open(&file),
        request(
            22,
            "detail",
            Value::map([
                ("objectId", 1i64.into()),
                ("arrayStart", 0i64.into()),
                ("arrayLimit", 200i64.into()),
            ]),
        ),
        request(23, "close", Value::map([])),
    ] {
        wire::write_frame(&mut input, &value).unwrap();
    }
    let mut output = Vec::new();
    aps_hprof_engine::serve(&mut Cursor::new(input), &mut output).unwrap();
    let mut frames = Cursor::new(output);
    let opened = wire::decode(&wire::read_frame(&mut frames).unwrap().unwrap()).unwrap();
    assert_eq!(field(&opened, "status").as_str().unwrap(), "ok");
    let result = wire::decode(&wire::read_frame(&mut frames).unwrap().unwrap()).unwrap();
    assert_eq!(integer(&result, "id"), 22);
    assert_eq!(field(&result, "status").as_str().unwrap(), "error");
    assert!(field(&result, "error")
        .as_str()
        .unwrap()
        .contains("not truncated"));
    assert!(result.get("payload").is_err());
    let close = wire::decode(&wire::read_frame(&mut frames).unwrap().unwrap()).unwrap();
    assert_eq!(integer(&close, "id"), 23);
}

#[test]
#[ignore = "requires APS_HPROF_FIXTURE; private fixture is never committed"]
fn local_large_heap_smoke() {
    let path = std::env::var_os("APS_HPROF_FIXTURE").expect("set APS_HPROF_FIXTURE");
    let started = std::time::Instant::now();
    let heap = Heap::open(std::path::Path::new(&path)).unwrap();
    let parsed = started.elapsed();
    let e = Engine::new(heap, BTreeMap::new()).unwrap();
    let analyzed = started.elapsed();
    let histogram = e.histogram(Some("App")).unwrap();
    let summary = field(&histogram, "summary");
    let findings = e.findings().unwrap();
    eprintln!("RUST_HPROF parsedMs={} analyzedMs={} totalMs={} classes={} objects={} appClasses={} appObjects={} appShallow={} lifecycleLeaks={} bitmaps={}", parsed.as_millis(), analyzed.as_millis(), started.elapsed().as_millis(), e.heap.classes.len(), e.heap.objects.len(), integer(summary, "classCount"), integer(summary, "objectCount"), integer(summary, "shallowSize"), list(&findings, "lifecycleLeakObjects").len(), list(&findings, "bitmapInstances").len());
    assert_eq!(
        e.heap.objects.len() + e.heap.classes.len(),
        integer(field(&e.histogram(None).unwrap(), "summary"), "objectCount") as usize
    );
    for name in ["java.lang.String", "android.graphics.Bitmap"] {
        let page = e
            .instances(&[name.to_owned()], Some("App"), 0, 3, false, None)
            .unwrap();
        if let Some(row) = list(&page, "rows").first() {
            assert_ne!(
                e.detail(integer(row, "objectId") as u64, 0, 200).unwrap(),
                Value::Null
            );
        }
    }
}

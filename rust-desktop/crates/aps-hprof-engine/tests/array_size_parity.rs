use aps_hprof_engine::{Engine, analysis, model::Heap};
use std::{
    collections::BTreeMap,
    path::PathBuf,
    sync::atomic::{AtomicU64, Ordering},
};

struct Dump {
    bytes: Vec<u8>,
    heap: Vec<u8>,
    id_size: usize,
    next: u64,
}

impl Dump {
    fn new(id_size: usize) -> Self {
        let mut bytes = b"JAVA PROFILE 1.0.3\0".to_vec();
        bytes.extend((id_size as u32).to_be_bytes());
        bytes.extend(0u64.to_be_bytes());
        Self {
            bytes,
            heap: Vec::new(),
            id_size,
            next: 10_000,
        }
    }

    fn id(id_size: usize, value: u64, bytes: &mut Vec<u8>) {
        bytes.extend_from_slice(&value.to_be_bytes()[8 - id_size..]);
    }

    fn record(&mut self, tag: u8, body: &[u8]) {
        self.bytes.push(tag);
        self.bytes.extend(0u32.to_be_bytes());
        self.bytes.extend((body.len() as u32).to_be_bytes());
        self.bytes.extend(body);
    }

    fn string(&mut self, name: &str) -> u64 {
        let id = self.next;
        self.next += 1;
        let mut body = Vec::new();
        let id_size = self.id_size;
        Self::id(id_size, id, &mut body);
        body.extend(name.as_bytes());
        self.record(1, &body);
        id
    }

    fn class(&mut self, id: u64, name: &str) {
        self.class_with_fields(id, name, 0, &[]);
    }

    fn class_with_fields(&mut self, id: u64, name: &str, parent: u64, fields: &[(&str, u8)]) {
        let name_id = self.string(name);
        let mut body = 0u32.to_be_bytes().to_vec();
        let id_size = self.id_size;
        Self::id(id_size, id, &mut body);
        body.extend([0; 4]);
        Self::id(id_size, name_id, &mut body);
        self.record(2, &body);
        self.heap.push(0x20);
        Self::id(self.id_size, id, &mut self.heap);
        self.heap.extend([0; 4]);
        Self::id(self.id_size, parent, &mut self.heap);
        self.heap.extend(vec![0; self.id_size * 5]);
        self.heap.extend(24u32.to_be_bytes());
        self.heap.extend([0; 4]);
        self.heap.extend((fields.len() as u16).to_be_bytes());
        for &(name, ty) in fields {
            let name_id = self.string(name);
            Self::id(self.id_size, name_id, &mut self.heap);
            self.heap.push(ty);
        }
    }

    fn instance(&mut self, id: u64, class_id: u64, bytes: &[u8]) {
        self.heap.push(0x21);
        Self::id(self.id_size, id, &mut self.heap);
        self.heap.extend([0; 4]);
        Self::id(self.id_size, class_id, &mut self.heap);
        self.heap.extend((bytes.len() as u32).to_be_bytes());
        self.heap.extend(bytes);
    }

    fn object_array(&mut self, id: u64, class_id: u64, items: &[u64]) {
        self.heap.push(0x22);
        Self::id(self.id_size, id, &mut self.heap);
        self.heap.extend([0; 4]);
        self.heap.extend((items.len() as u32).to_be_bytes());
        Self::id(self.id_size, class_id, &mut self.heap);
        for &item in items {
            Self::id(self.id_size, item, &mut self.heap);
        }
    }

    fn primitive_array(&mut self, id: u64, ty: u8, count: u32) {
        self.heap.push(0x23);
        Self::id(self.id_size, id, &mut self.heap);
        self.heap.extend([0; 4]);
        self.heap.extend(count.to_be_bytes());
        self.heap.push(ty);
        self.heap
            .resize(self.heap.len() + count as usize * primitive_width(ty), 0);
    }

    fn root(&mut self, id: u64) {
        self.heap.push(0xff);
        Self::id(self.id_size, id, &mut self.heap);
    }

    fn finish(mut self) -> Fixture {
        let heap = std::mem::take(&mut self.heap);
        self.record(0x1c, &heap);
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let path = std::env::temp_dir().join(format!(
            "aps-array-parity-{}-{}.hprof",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::write(&path, self.bytes).unwrap();
        Fixture(path)
    }
}

fn primitive_width(ty: u8) -> usize {
    match ty {
        4 | 8 => 1,
        5 | 9 => 2,
        6 | 10 => 4,
        7 | 11 => 8,
        _ => unreachable!(),
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
fn arrays_match_perflib_payload_sizing_for_four_and_eight_byte_ids() {
    for id_size in [4, 8] {
        let mut dump = Dump::new(id_size);
        dump.class(1, "[Ljava/lang/Object;");
        for (index, (ty, width)) in [
            (4, 1),
            (5, 2),
            (6, 4),
            (7, 8),
            (8, 1),
            (9, 2),
            (10, 4),
            (11, 8),
        ]
        .into_iter()
        .enumerate()
        {
            let id = 100 + index as u64;
            dump.primitive_array(id, ty, 3);
            if index >= 2 {
                dump.root(id);
            }
            assert_eq!(width, primitive_width(ty));
        }
        dump.object_array(200, 1, &[100, 101]);
        dump.object_array(201, 1, &[]);
        dump.primitive_array(202, 6, 0);
        dump.primitive_array(300, 4, 3);
        dump.primitive_array(301, 5, 2);
        dump.object_array(203, 1, &[300, 301]);
        dump.root(200);
        dump.root(201);
        dump.root(202);
        dump.root(203);

        let engine = dump.finish().engine();
        for (index, width) in [1u64, 2, 4, 8, 1, 2, 4, 8].into_iter().enumerate() {
            let id = 100 + index as u64;
            let shallow = width * 3;
            assert_eq!(
                engine.shallow(node(&engine, id) as u32),
                shallow,
                "type index {index}, id size {id_size}"
            );
            assert_eq!(engine.analysis.retained[node(&engine, id)], shallow);
        }
        assert_eq!(
            engine.shallow(node(&engine, 200) as u32),
            (id_size * 2) as u64
        );
        assert_eq!(
            engine.analysis.retained[node(&engine, 200)],
            (id_size * 2) as u64 + 9
        );
        assert_eq!(engine.shallow(node(&engine, 201) as u32), 0);
        assert_eq!(engine.analysis.retained[node(&engine, 201)], 0);
        assert_eq!(engine.shallow(node(&engine, 202) as u32), 0);
        assert_eq!(engine.analysis.retained[node(&engine, 202)], 0);
        assert_eq!(
            engine.shallow(node(&engine, 203) as u32),
            (id_size * 2) as u64
        );
        assert_eq!(
            engine.analysis.retained[node(&engine, 203)],
            (id_size * 2) as u64 + 7
        );
    }
}

#[test]
fn graph_reference_scan_streams_large_arrays_and_preserves_four_and_eight_byte_slots() {
    use aps_hprof_engine::edges::GraphStorage;
    for id_size in [4, 8] {
        let negative_target = if id_size == 8 {
            0x8000_0000_0000_0029
        } else {
            41
        };
        let mut dump = Dump::new(id_size);
        dump.class(1, "[Ljava/lang/Object;");
        dump.class_with_fields(2, "Base", 0, &[("same", 2)]);
        dump.class_with_fields(3, "Derived", 2, &[("same", 2), ("int", 10), ("same", 2)]);
        dump.primitive_array(40, 10, 1);
        dump.primitive_array(negative_target, 8, 1);
        dump.primitive_array(42, 11, 20_000); // Skip a payload larger than the read buffer.
        let mut instance = Vec::new();
        Dump::id(id_size, 40, &mut instance);
        instance.extend(40u32.to_be_bytes()); // Must not be mistaken for a reference.
        Dump::id(id_size, negative_target, &mut instance);
        Dump::id(id_size, 40, &mut instance);
        dump.instance(100, 3, &instance);
        let items: Vec<_> = (0..20_003)
            .map(|index| match index % 5 {
                0 => 40,
                1 => negative_target,
                2 => 0,
                3 => 999,
                _ => 3, // Class objects are valid reference targets.
            })
            .collect();
        dump.object_array(200, 1, &items);
        dump.object_array(201, 1, &[]);
        dump.object_array(202, 1, &[negative_target, 40]);
        let fixture = dump.finish();
        let heap = Heap::open(&fixture.0).unwrap();
        #[cfg(unix)]
        let temp = std::env::temp_dir();
        #[cfg(unix)]
        let storages = [GraphStorage::Memory, GraphStorage::Disk(&temp)];
        #[cfg(not(unix))]
        let storages = [GraphStorage::Memory];
        for storage in storages {
            let graph = analysis::Graph::build_named_with_storage(&heap, &[], storage).unwrap();
            for (source, object) in heap.objects.iter().enumerate() {
                use aps_hprof_engine::model::ObjectKind;
                let mut expected = Vec::new();
                if object.kind == ObjectKind::ObjectArray {
                    for start in (0..object.count).step_by(4096) {
                        let values = heap.values(object, start, 4096).unwrap();
                        for (local, value) in values.into_iter().enumerate() {
                            if value.bits != 0
                                && let Some(target) = analysis::node_of(&heap, value.bits)
                            {
                                expected.push((target, start + local as u32));
                            }
                        }
                    }
                } else if object.kind == ObjectKind::Instance {
                    let layout = heap.fields_for(object.class_id);
                    for (slot, value) in heap
                        .values(object, 0, 4096)
                        .unwrap()
                        .into_iter()
                        .enumerate()
                    {
                        if value.ty == 2
                            && let Some(target) = analysis::node_of(&heap, value.bits)
                        {
                            expected.push((target, slot as u32));
                        }
                    }
                    expected.sort_by(|a, b| {
                        layout[a.1 as usize]
                            .name
                            .cmp(&layout[b.1 as usize].name)
                            .then_with(|| {
                                (analysis::id_of(&heap, a.0) as i64)
                                    .cmp(&(analysis::id_of(&heap, b.0) as i64))
                            })
                    });
                }
                let actual: Vec<_> = graph
                    .range(source as u32)
                    .map(|edge| graph.edge(edge).unwrap())
                    .collect();
                assert_eq!(
                    actual, expected,
                    "id_size={id_size}, source=0x{:x}",
                    object.id
                );
                for &(_, slot) in &actual {
                    let name = graph.field_name(&heap, source as u32, slot).unwrap();
                    assert_eq!(
                        name,
                        if object.kind == ObjectKind::ObjectArray {
                            format!("[{slot}]")
                        } else {
                            "same".to_string()
                        }
                    );
                }
            }
            let array = heap.object_by_id[&200] as u32;
            assert!(graph.range(array).len() > 4096);
            assert_eq!(graph.range(heap.object_by_id[&201] as u32).len(), 0);
            let derived = heap.object_by_id[&100] as u32;
            let first = graph.edge(graph.range(derived).start).unwrap();
            assert_eq!(
                analysis::id_of(&heap, first.0),
                if id_size == 8 { negative_target } else { 40 }
            );
            assert_eq!(first.1, if id_size == 8 { 2 } else { 0 });
        }
    }
}

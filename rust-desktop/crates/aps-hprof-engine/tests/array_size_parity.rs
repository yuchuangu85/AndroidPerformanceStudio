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
        self.heap.extend(vec![0; self.id_size * 6]);
        self.heap.extend(24u32.to_be_bytes());
        self.heap.extend([0; 4]);
        self.heap.extend(0u16.to_be_bytes());
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
        self.heap.resize(self.heap.len() + count as usize * primitive_width(ty), 0);
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
        for (index, (ty, width)) in [(4, 1), (5, 2), (6, 4), (7, 8), (8, 1), (9, 2), (10, 4), (11, 8)]
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
            assert_eq!(engine.shallow(node(&engine, id) as u32), shallow, "type index {index}, id size {id_size}");
            assert_eq!(engine.analysis.retained[node(&engine, id)], shallow);
        }
        assert_eq!(engine.shallow(node(&engine, 200) as u32), (id_size * 2) as u64);
        assert_eq!(engine.analysis.retained[node(&engine, 200)], (id_size * 2) as u64 + 9);
        assert_eq!(engine.shallow(node(&engine, 201) as u32), 0);
        assert_eq!(engine.analysis.retained[node(&engine, 201)], 0);
        assert_eq!(engine.shallow(node(&engine, 202) as u32), 0);
        assert_eq!(engine.analysis.retained[node(&engine, 202)], 0);
        assert_eq!(engine.shallow(node(&engine, 203) as u32), (id_size * 2) as u64);
        assert_eq!(engine.analysis.retained[node(&engine, 203)], (id_size * 2) as u64 + 7);
    }
}

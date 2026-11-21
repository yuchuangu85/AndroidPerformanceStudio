use aps_hprof_engine::{
    analysis,
    model::{Heap, Object, ObjectKind, Value},
    native::{map_native_sizes, retained_native_sizes},
};
use std::{
    path::PathBuf,
    sync::atomic::{AtomicU64, Ordering},
    time::Instant,
};

// Keep the pre-batch algorithm as an independent compatibility oracle.
fn legacy_native_sizes(heap: &Heap) -> Result<Option<Vec<u64>>, String> {
    fn values(heap: &Heap, object: &Object) -> Result<Vec<Value>, String> {
        let count = analysis::observed_fields(heap, object, &heap.fields_for(object.class_id));
        heap.values(
            object,
            0,
            u32::try_from(count).map_err(|_| "too many fields")?,
        )
    }
    fn reference(values: &[Value], name: &str) -> Option<u64> {
        values
            .iter()
            .find(|v| v.name == name && v.ty == 2)
            .map(|v| v.bits)
    }
    fn exact(heap: &Heap, object: &Object, name: &str) -> bool {
        heap.class_by_id.get(&object.class_id).is_some_and(|&i| {
            heap.classes[i]
                .name
                .bytes()
                .map(|byte| if byte == b'/' { b'.' } else { byte })
                .eq(name.bytes())
        })
    }
    let mut result = vec![0; heap.objects.len() + heap.classes.len()];
    let mut found = false;
    for cleaner in &heap.objects {
        if cleaner.kind != ObjectKind::Instance || !exact(heap, cleaner, "sun.misc.Cleaner") {
            continue;
        }
        let fields = values(heap, cleaner)?;
        let Some(owner) = reference(&fields, "referent").filter(|id| *id != 0) else {
            continue;
        };
        let Some(thunk) = reference(&fields, "thunk").filter(|id| *id != 0) else {
            continue;
        };
        let Some(&thunk) = heap.object_by_id.get(&thunk) else {
            continue;
        };
        let thunk = &heap.objects[thunk];
        if !exact(
            heap,
            thunk,
            "libcore.util.NativeAllocationRegistry$CleanerThunk",
        ) {
            continue;
        }
        let Some(registry) = values(heap, thunk)?
            .into_iter()
            .find(|v| v.ty == 2 && v.bits != 0)
            .map(|v| v.bits)
        else {
            continue;
        };
        let Some(&registry) = heap.object_by_id.get(&registry) else {
            continue;
        };
        let registry = &heap.objects[registry];
        if !exact(heap, registry, "libcore.util.NativeAllocationRegistry") {
            continue;
        }
        let Some(size) = values(heap, registry)?
            .into_iter()
            .find(|v| v.name == "size" && v.ty == 11)
            .map(|v| v.bits)
        else {
            continue;
        };
        let Some(&owner) = heap.object_by_id.get(&owner) else {
            continue;
        };
        result[owner] = size;
        found = true;
    }
    heap.check_source()?;
    Ok(found.then_some(result))
}

struct Dump {
    bytes: Vec<u8>,
    heap: Vec<u8>,
    id_size: u8,
    next: u64,
}

impl Dump {
    fn new(id_size: u8) -> Self {
        let mut bytes = b"JAVA PROFILE 1.0.3\0".to_vec();
        bytes.extend(u32::from(id_size).to_be_bytes());
        bytes.extend(0u64.to_be_bytes());
        Self {
            bytes,
            heap: Vec::new(),
            id_size,
            next: 100_000,
        }
    }

    fn id(&self, id: u64) -> Vec<u8> {
        id.to_be_bytes()[8 - usize::from(self.id_size)..].to_vec()
    }

    fn record(&mut self, tag: u8, bytes: &[u8]) {
        self.bytes.push(tag);
        self.bytes.extend(0u32.to_be_bytes());
        self.bytes.extend((bytes.len() as u32).to_be_bytes());
        self.bytes.extend(bytes);
    }

    fn string(&mut self, name: &str) -> u64 {
        let id = self.next;
        self.next += 1;
        let mut bytes = self.id(id);
        bytes.extend(name.as_bytes());
        self.record(1, &bytes);
        id
    }

    fn class(&mut self, id: u64, name: &str, parent: u64, fields: &[(&str, u8)]) {
        let name = self.string(name);
        let mut bytes = 0u32.to_be_bytes().to_vec();
        bytes.extend(self.id(id));
        bytes.extend(0u32.to_be_bytes());
        bytes.extend(self.id(name));
        self.record(2, &bytes);
        self.heap.push(0x20);
        self.heap.extend(self.id(id));
        self.heap.extend(0u32.to_be_bytes());
        self.heap.extend(self.id(parent));
        self.heap.extend(vec![0; usize::from(self.id_size) * 5]);
        self.heap.extend(16u32.to_be_bytes());
        self.heap.extend([0; 4]);
        self.heap.extend((fields.len() as u16).to_be_bytes());
        for &(name, ty) in fields {
            let name = self.string(name);
            self.heap.extend(self.id(name));
            self.heap.push(ty);
        }
    }

    fn instance(&mut self, id: u64, class: u64, bytes: &[u8]) {
        self.heap.push(0x21);
        self.heap.extend(self.id(id));
        self.heap.extend(0u32.to_be_bytes());
        self.heap.extend(self.id(class));
        self.heap.extend((bytes.len() as u32).to_be_bytes());
        self.heap.extend(bytes);
    }

    fn basic_classes(&mut self) {
        self.class(1, "sun.misc.Cleaner", 0, &[("referent", 2), ("thunk", 2)]);
        self.class(
            2,
            "libcore.util.NativeAllocationRegistry$CleanerThunk",
            0,
            &[("first", 2), ("second", 2)],
        );
        self.class(
            3,
            "libcore.util.NativeAllocationRegistry",
            0,
            &[("size", 11)],
        );
        self.class(4, "Owner", 0, &[]);
    }

    fn refs(&mut self, id: u64, class: u64, refs: &[u64]) {
        let bytes: Vec<_> = refs.iter().flat_map(|&id| self.id(id)).collect();
        self.instance(id, class, &bytes);
    }

    fn array(&mut self, id: u64, class: u64, refs: &[u64]) {
        self.heap.push(0x22);
        self.heap.extend(self.id(id));
        self.heap.extend(0u32.to_be_bytes());
        self.heap.extend((refs.len() as u32).to_be_bytes());
        self.heap.extend(self.id(class));
        for &id in refs {
            self.heap.extend(self.id(id));
        }
    }

    fn finish(mut self) -> Fixture {
        let heap = std::mem::take(&mut self.heap);
        self.record(0x1c, &heap);
        static NEXT: AtomicU64 = AtomicU64::new(0);
        let path = std::env::temp_dir().join(format!(
            "aps-native-batch-{}-{}.hprof",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::write(&path, self.bytes).unwrap();
        Fixture(path)
    }
}

struct Fixture(PathBuf);
impl Drop for Fixture {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.0);
    }
}

fn compare(heap: &Heap) -> Option<Vec<u64>> {
    let expected = legacy_native_sizes(heap).unwrap();
    assert_eq!(map_native_sizes(heap).unwrap(), expected);
    expected
}

#[test]
fn native_batch_matches_legacy_for_inheritance_shadowing_and_first_nonnull() {
    for id_size in [4, 8] {
        let mut dump = Dump::new(id_size);
        dump.class(
            1,
            "sun/misc/Cleaner",
            5,
            &[("padding", 8), ("referent", 2), ("thunk", 2)],
        );
        dump.class(5, "Reference", 0, &[("referent", 2), ("thunk", 2)]);
        dump.class(
            2,
            "libcore/util/NativeAllocationRegistry$CleanerThunk",
            6,
            &[("padding", 10), ("null", 2)],
        );
        dump.class(6, "ThunkBase", 0, &[("registry", 2), ("ignored", 2)]);
        dump.class(
            3,
            "libcore/util/NativeAllocationRegistry",
            7,
            &[("size", 10), ("size", 11)],
        );
        dump.class(7, "RegistryBase", 0, &[("size", 11)]);
        dump.class(4, "Owner", 0, &[]);
        for id in [10, 11] {
            dump.instance(id, 4, &[]);
        }
        let mut cleaner = vec![9];
        for id in [10, 30, 11, 31] {
            cleaner.extend(dump.id(id));
        }
        cleaner.extend([0xaa; 3]); // Trailing bytes are not additional fields.
        dump.instance(20, 1, &cleaner);
        let mut thunk = 8u32.to_be_bytes().to_vec();
        for id in [0, 40, 41] {
            thunk.extend(dump.id(id));
        }
        dump.instance(30, 2, &thunk);
        let mut registry = 1u32.to_be_bytes().to_vec();
        registry.extend(u64::MAX.to_be_bytes());
        registry.extend(17u64.to_be_bytes());
        dump.instance(40, 3, &registry);
        dump.instance(41, 3, &99u32.to_be_bytes()); // Ends before either long field.
        let fixture = dump.finish();
        let heap = Heap::open(&fixture.0).unwrap();
        let sizes = compare(&heap).unwrap();
        assert_eq!(sizes[heap.object_by_id[&10]], u64::MAX);
        assert_eq!(sizes[heap.object_by_id[&11]], 0);
    }
}

#[test]
fn native_batch_preserves_original_cleaner_order_even_when_offsets_are_reordered() {
    for id_size in [4, 8] {
        let mut dump = Dump::new(id_size);
        dump.basic_classes();
        dump.instance(10, 4, &[]);
        dump.refs(20, 1, &[10, 30]);
        dump.refs(21, 1, &[10, 31]);
        dump.refs(30, 2, &[0, 40]);
        dump.refs(31, 2, &[41, 40]);
        dump.instance(40, 3, &77u64.to_be_bytes());
        dump.instance(41, 3, &0u64.to_be_bytes());
        let fixture = dump.finish();
        let mut heap = Heap::open(&fixture.0).unwrap();
        assert_eq!(compare(&heap).unwrap()[heap.object_by_id[&10]], 0);
        heap.objects.reverse();
        heap.object_by_id = heap
            .objects
            .iter()
            .enumerate()
            .map(|(i, o)| (o.id, i))
            .collect();
        assert_eq!(compare(&heap).unwrap()[heap.object_by_id[&10]], 77);
    }
}

#[test]
fn native_batch_preserves_missing_zero_short_dangling_and_invalid_chains() {
    for id_size in [4, 8] {
        for case in 0..16 {
            let mut dump = Dump::new(id_size);
            dump.basic_classes();
            dump.instance(10, 4, &[]);
            let owner = match case {
                1 => 0,
                2 => 999,
                3 => 4,
                _ => 10,
            };
            let thunk = match case {
                4 => 0,
                5 => 999,
                6 => 10,
                _ => 30,
            };
            if case == 7 {
                dump.refs(20, 1, &[owner]);
            } else if case == 8 {
                dump.instance(20, 1, &[]);
            } else {
                dump.refs(20, 1, &[owner, thunk]);
            }
            let refs: &[u64] = match case {
                9 => &[0, 0],
                10 => &[999, 40],
                11 => &[10, 40],
                12 => &[20, 40],
                13 => &[],
                _ => &[0, 40],
            };
            dump.refs(30, 2, refs);
            dump.instance(40, 3, if case == 14 { &[] } else { &[0; 8] });
            let fixture = dump.finish();
            let mut heap = Heap::open(&fixture.0).unwrap();
            if case == 15 {
                heap.class_by_id.remove(&2);
            }
            let result = compare(&heap);
            if case == 0 {
                assert!(result.is_some());
            } else {
                assert!(result.is_none(), "case {case}");
            }
        }
    }
}

#[test]
fn native_batch_keeps_null_shadowed_fields_and_inheritance_cycles() {
    for id_size in [4, 8] {
        let mut dump = Dump::new(id_size);
        dump.class(1, "sun.misc.Cleaner", 5, &[("referent", 2), ("thunk", 2)]);
        dump.class(5, "Cycle", 1, &[("referent", 2), ("thunk", 2)]);
        dump.class(
            2,
            "libcore.util.NativeAllocationRegistry$CleanerThunk",
            0,
            &[("registry", 2)],
        );
        dump.class(
            3,
            "libcore.util.NativeAllocationRegistry",
            0,
            &[("size", 11), ("size", 11)],
        );
        dump.class(4, "Owner", 0, &[]);
        dump.instance(10, 4, &[]);
        dump.refs(20, 1, &[0, 30, 10, 30]);
        dump.refs(21, 1, &[10, 0, 10, 30]);
        dump.refs(30, 2, &[40]);
        let mut bytes = 0u64.to_be_bytes().to_vec();
        bytes.extend(100u64.to_be_bytes());
        dump.instance(40, 3, &bytes);
        let fixture = dump.finish();
        assert!(compare(&Heap::open(&fixture.0).unwrap()).is_none());
    }
}

#[test]
fn native_batch_rejects_changed_source_and_keeps_lazy_values_checked() {
    let mut dump = Dump::new(4);
    dump.basic_classes();
    dump.instance(10, 4, &[]);
    dump.refs(20, 1, &[10, 30]);
    dump.refs(30, 2, &[0, 40]);
    dump.instance(40, 3, &123u64.to_be_bytes());
    let fixture = dump.finish();
    let heap = Heap::open(&fixture.0).unwrap();
    compare(&heap);
    assert!(
        heap.values(&heap.objects[heap.object_by_id[&40]], 0, 1)
            .is_ok()
    );
    let replacement = fixture.0.with_extension("replacement");
    std::fs::copy(&fixture.0, &replacement).unwrap();
    std::fs::rename(replacement, &fixture.0).unwrap();
    assert!(map_native_sizes(&heap).is_err());
    assert!(
        heap.values(&heap.objects[heap.object_by_id[&40]], 0, 1)
            .is_err()
    );
}

#[test]
fn native_batch_retains_unsigned_sizes_and_checked_retention_overflow() {
    assert!(retained_native_sizes(&[u64::MAX, 1], &[analysis::NONE, 0], &[0, 1]).is_err());
    assert_eq!(
        retained_native_sizes(&[u64::MAX], &[analysis::NONE], &[0]).unwrap(),
        [u64::MAX]
    );
}

#[test]
fn native_batch_preserves_array_owners_and_malformed_array_chain_semantics() {
    for id_size in [4, 8] {
        for array_registry in [true, false] {
            let mut dump = Dump::new(id_size);
            dump.basic_classes();
            dump.array(10, 4, &[]);
            dump.refs(20, 1, &[10, 30]);
            let mut refs = vec![0, 40];
            refs.resize(id_size as usize * 2, 0);
            dump.array(30, 2, &refs);
            if array_registry {
                dump.array(40, 3, &refs);
            } else {
                dump.instance(40, 3, &99u64.to_be_bytes());
            }
            let fixture = dump.finish();
            let heap = Heap::open(&fixture.0).unwrap();
            let sizes = compare(&heap);
            if array_registry {
                assert!(sizes.is_none());
            } else {
                assert_eq!(sizes.unwrap()[heap.object_by_id[&10]], 99);
            }
        }
    }
}

#[test]
fn native_batch_preserves_missing_superclasses_and_excludes_subclass_identities() {
    for id_size in [4, 8] {
        for missing in [false, true] {
            let mut dump = Dump::new(id_size);
            dump.basic_classes();
            dump.class(5, "CleanerSubclass", 1, &[]);
            dump.instance(10, 4, &[]);
            dump.refs(20, 1, &[10, 30]);
            dump.refs(21, 5, &[10, 30]);
            dump.refs(30, 2, &[0, 40]);
            dump.instance(40, 3, &5u64.to_be_bytes());
            let fixture = dump.finish();
            let mut heap = Heap::open(&fixture.0).unwrap();
            if missing {
                heap.classes[heap.class_by_id[&1]].super_id = 999;
                heap.classes[heap.class_by_id[&2]].super_id = 999;
                heap.classes[heap.class_by_id[&3]].super_id = 999;
            }
            assert_eq!(compare(&heap).unwrap()[heap.object_by_id[&10]], 5);
        }
    }
}

#[test]
fn native_batch_matches_legacy_with_shared_thunks_registries_and_scrambled_records() {
    for id_size in [4, 8] {
        let mut dump = Dump::new(id_size);
        dump.basic_classes();
        for index in 0..257 {
            dump.instance(1_000 + index, 4, &[]);
        }
        for index in 0..37 {
            dump.instance(2_000 + index, 3, &(index * 23u64).to_be_bytes());
        }
        for index in 0..107 {
            dump.refs(3_000 + index, 2, &[0, 2_000 + index % 37]);
        }
        for index in (0..1_000).rev() {
            dump.refs(
                4_000 + index,
                1,
                &[1_000 + index % 257, 3_000 + index % 107],
            );
        }
        let fixture = dump.finish();
        let mut heap = Heap::open(&fixture.0).unwrap();
        compare(&heap);
        heap.objects
            .sort_unstable_by_key(|o| o.id.wrapping_mul(0x9e37_79b9));
        heap.object_by_id = heap
            .objects
            .iter()
            .enumerate()
            .map(|(i, o)| (o.id, i))
            .collect();
        compare(&heap);
    }
}

#[test]
fn native_batch_preserves_full_eight_byte_ids_and_partial_field_read_bounds() {
    let mut dump = Dump::new(8);
    dump.basic_classes();
    let owner = u64::MAX - 10;
    let cleaner = u64::MAX - 20;
    let thunk = u64::MAX - 30;
    let registry = u64::MAX - 40;
    dump.instance(owner, 4, &[]);
    dump.refs(cleaner, 1, &[owner, thunk]);
    dump.refs(thunk, 2, &[0, registry]);
    dump.instance(registry, 3, &u64::MAX.to_be_bytes());
    let fixture = dump.finish();
    let mut heap = Heap::open(&fixture.0).unwrap();
    assert_eq!(compare(&heap).unwrap()[heap.object_by_id[&owner]], u64::MAX);
    let registry_index = heap.object_by_id[&registry];
    for short in 0..8 {
        heap.objects[registry_index].count = short;
        assert!(
            compare(&heap).is_none(),
            "must not read beyond the declared record"
        );
    }
    heap.objects[registry_index].count = 8;
    let thunk_index = heap.object_by_id[&thunk];
    heap.objects[thunk_index].kind = ObjectKind::PrimitiveArray {
        ty: 8,
        has_data: false,
    };
    assert!(legacy_native_sizes(&heap).unwrap_err().contains("NODATA"));
    assert!(map_native_sizes(&heap).unwrap_err().contains("NODATA"));
}

#[test]
#[ignore = "explicit real-file release benchmark; set APS_NATIVE_BENCH_HPROF"]
fn real_hprof_native_benchmark() {
    let path = std::env::var_os("APS_NATIVE_BENCH_HPROF").expect("APS_NATIVE_BENCH_HPROF");
    let heap = Heap::open(&PathBuf::from(path)).unwrap();
    let expected = legacy_native_sizes(&heap).unwrap();
    let assignments = expected
        .as_ref()
        .map_or(0, |v| v.iter().filter(|&&n| n != 0).count());
    let checksum = expected
        .as_ref()
        .map_or(0u128, |v| v.iter().map(|&n| u128::from(n)).sum());
    println!(
        "native fixture bytes={} objects={} nonzero_owners={assignments} checksum={checksum}",
        heap.file_size,
        heap.objects.len()
    );
    let mut old = Vec::new();
    let mut new = Vec::new();
    for iteration in 0..9 {
        // Alternate order so neither implementation always receives the warmer cache.
        for legacy in if iteration % 2 == 0 {
            [true, false]
        } else {
            [false, true]
        } {
            let started = Instant::now();
            let actual = if legacy {
                legacy_native_sizes(&heap)
            } else {
                map_native_sizes(&heap)
            }
            .unwrap();
            let elapsed = started.elapsed().as_micros();
            assert_eq!(actual, expected);
            std::hint::black_box(actual);
            if iteration >= 2 {
                if legacy {
                    old.push(elapsed);
                } else {
                    new.push(elapsed);
                }
            }
        }
    }
    println!("native legacy_us={old:?} batch_us={new:?}");
    old.sort_unstable();
    new.sort_unstable();
    println!(
        "native median legacy_us={} batch_us={} speedup={:.3}",
        old[3],
        new[3],
        old[3] as f64 / new[3] as f64
    );
}

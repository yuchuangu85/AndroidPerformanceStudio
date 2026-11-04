// Compile the owned parser/model independently of the sidecar's transport modules.
#[path = "../src/model.rs"]
#[allow(dead_code)]
mod model;
#[path = "../src/parser.rs"]
mod parser;

use model::{Heap, ObjectKind, Value};
use std::fs::{self, OpenOptions};
use std::io::{Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

static NEXT_FILE: AtomicU64 = AtomicU64::new(0);

struct FixtureFile(PathBuf);

impl FixtureFile {
    fn new(bytes: &[u8]) -> Self {
        let path = std::env::temp_dir().join(format!(
            "aps-rust-parser-{}-{}.hprof",
            std::process::id(),
            NEXT_FILE.fetch_add(1, Ordering::Relaxed)
        ));
        let mut file = OpenOptions::new()
            .write(true)
            .create_new(true)
            .open(&path)
            .unwrap();
        file.write_all(bytes).unwrap();
        Self(path)
    }

    fn open(&self) -> Heap {
        Heap::open(&self.0).unwrap()
    }
}

impl Drop for FixtureFile {
    fn drop(&mut self) {
        let _ = fs::remove_file(&self.0);
    }
}

struct Fixture {
    id_size: u8,
    bytes: Vec<u8>,
}

impl Fixture {
    fn new(id_size: u8) -> Self {
        let mut bytes = b"JAVA PROFILE 1.0.3\0".to_vec();
        bytes.extend(u32::from(id_size).to_be_bytes());
        bytes.extend(1234_u64.to_be_bytes());
        Self { id_size, bytes }
    }

    fn id(&self, value: u64) -> Vec<u8> {
        if self.id_size == 4 {
            (value as u32).to_be_bytes().to_vec()
        } else {
            value.to_be_bytes().to_vec()
        }
    }

    fn record(&mut self, tag: u8, payload: &[u8]) {
        self.bytes.push(tag);
        self.bytes.extend(0_u32.to_be_bytes());
        self.bytes.extend((payload.len() as u32).to_be_bytes());
        self.bytes.extend(payload);
    }

    fn string(&mut self, id: u64, name: &str) {
        let mut payload = self.id(id);
        payload.extend(name.as_bytes());
        self.record(1, &payload);
    }

    fn load(&mut self, id: u64, name_id: u64) {
        let mut payload = 1_u32.to_be_bytes().to_vec();
        payload.extend(self.id(id));
        payload.extend(0_u32.to_be_bytes());
        payload.extend(self.id(name_id));
        self.record(2, &payload);
    }

    fn class(
        &self,
        id: u64,
        super_id: u64,
        size: u32,
        fields: &[(u64, u8)],
        statics: &[(u64, u8, u64)],
    ) -> Vec<u8> {
        let mut bytes = vec![0x20];
        bytes.extend(self.id(id));
        bytes.extend(0_u32.to_be_bytes());
        bytes.extend(self.id(super_id));
        bytes.extend(self.id(99));
        bytes.extend(vec![0; self.id_size as usize * 4]);
        bytes.extend(size.to_be_bytes());
        bytes.extend(0_u16.to_be_bytes());
        bytes.extend((statics.len() as u16).to_be_bytes());
        for &(name, ty, bits) in statics {
            bytes.extend(self.id(name));
            bytes.push(ty);
            bytes.extend(self.value(ty, bits));
        }
        bytes.extend((fields.len() as u16).to_be_bytes());
        for &(name, ty) in fields {
            bytes.extend(self.id(name));
            bytes.push(ty);
        }
        bytes
    }

    fn instance(&self, id: u64, class_id: u64, payload: &[u8]) -> Vec<u8> {
        let mut bytes = vec![0x21];
        bytes.extend(self.id(id));
        bytes.extend(0_u32.to_be_bytes());
        bytes.extend(self.id(class_id));
        bytes.extend((payload.len() as u32).to_be_bytes());
        bytes.extend(payload);
        bytes
    }

    fn objects(&self, id: u64, class_id: u64, ids: &[u64]) -> Vec<u8> {
        let mut bytes = vec![0x22];
        bytes.extend(self.id(id));
        bytes.extend(0_u32.to_be_bytes());
        bytes.extend((ids.len() as u32).to_be_bytes());
        bytes.extend(self.id(class_id));
        for &id in ids {
            bytes.extend(self.id(id));
        }
        bytes
    }

    fn primitive(&self, id: u64, ty: u8, count: u32, payload: Option<&[u8]>) -> Vec<u8> {
        let mut bytes = vec![if payload.is_some() { 0x23 } else { 0xc3 }];
        bytes.extend(self.id(id));
        bytes.extend(0_u32.to_be_bytes());
        bytes.extend(count.to_be_bytes());
        bytes.push(ty);
        if let Some(data) = payload {
            bytes.extend(data);
        }
        bytes
    }

    fn heap_info(&self, name_id: u64) -> Vec<u8> {
        let mut bytes = vec![0xfe];
        bytes.extend(1_u32.to_be_bytes());
        bytes.extend(self.id(name_id));
        bytes
    }

    fn root(&self, tag: u8, id: u64) -> Vec<u8> {
        let mut bytes = vec![tag];
        bytes.extend(self.id(id));
        match tag {
            1 => bytes.extend(self.id(123)),
            2 | 3 | 8 | 0x8e => bytes.extend([0; 8]),
            4 | 6 => bytes.extend([0; 4]),
            _ => {}
        }
        bytes
    }

    fn value(&self, ty: u8, bits: u64) -> Vec<u8> {
        match ty {
            2 => self.id(bits),
            4 | 8 => vec![bits as u8],
            5 | 9 => (bits as u16).to_be_bytes().to_vec(),
            6 | 10 => (bits as u32).to_be_bytes().to_vec(),
            7 | 11 => bits.to_be_bytes().to_vec(),
            _ => panic!("unsupported fixture type"),
        }
    }

    fn file(&self) -> FixtureFile {
        FixtureFile::new(&self.bytes)
    }
}

fn object(heap: &Heap, id: u64) -> &model::Object {
    &heap.objects[heap.object_by_id[&id]]
}

#[test]
fn reads_headers_and_rejects_bad_id_widths() {
    for id_size in [4, 8] {
        let fixture = Fixture::new(id_size).file();
        let heap = fixture.open();
        assert_eq!(heap.format, "JAVA PROFILE 1.0.3");
        assert_eq!(heap.timestamp, 1234);
        assert_eq!(heap.id_size, id_size);
        assert_eq!(heap.file_size, fs::metadata(&fixture.0).unwrap().len());
        assert!(heap.path.is_absolute());
        assert!(heap.objects.is_empty());
        assert!(heap.heaps.is_empty());
        heap.check_source().unwrap();
    }
    let fixture = Fixture::new(16).file();
    assert!(Heap::open(&fixture.0).unwrap_err().contains("id size 16"));
    for bytes in [vec![], vec![b'x'; 300], b"JAVA PROFILE 1.0.3".to_vec()] {
        assert!(Heap::open(&FixtureFile::new(&bytes).0).is_err());
    }
}

#[test]
fn load_class_only_preserves_classes_without_assigning_heaps() {
    for id_size in [4, 8] {
        let mut fixture = Fixture::new(id_size);
        fixture.string(1, "Placeholder");
        fixture.load(10, 1);
        let file = fixture.file();
        let heap = file.open();
        assert_eq!(heap.classes.len(), 1);
        assert_eq!(heap.classes[0].name, "Placeholder");
        assert_eq!(heap.classes[0].heap, usize::MAX);
        assert!(heap.heaps.is_empty());
        assert!(heap.objects.is_empty());
        heap.check_source().unwrap();

        fixture.record(0x0c, &fixture.class(10, 0, 24, &[], &[]));
        let file = fixture.file();
        let heap = file.open();
        assert_eq!(heap.classes.len(), 1);
        assert_eq!(heap.heaps, ["Default"]);
        assert_eq!(heap.classes[0].heap, 0);
    }
}

#[test]
fn heap_info_and_roots_alone_do_not_make_heaps_available() {
    for id_size in [4, 8] {
        let mut fixture = Fixture::new(id_size);
        let mut dump = Vec::new();
        for (id, name) in [(1, "app"), (2, "image"), (3, "zygote"), (4, "other")] {
            fixture.string(id, name);
            dump.extend(fixture.heap_info(id));
        }
        fixture.load(10, 99);
        dump.extend(fixture.root(5, 10));
        fixture.record(0x1c, &dump);
        let file = fixture.file();
        let heap = file.open();
        assert!(heap.heaps.is_empty());
        assert_eq!(heap.classes.len(), 1);
        assert_eq!(heap.classes[0].heap, usize::MAX);
        assert_eq!(heap.roots.len(), 1);
        heap.check_source().unwrap();
    }
}

#[test]
fn mixed_placeholders_and_dumped_classes_only_expose_assigned_heaps() {
    for id_size in [4, 8] {
        let mut fixture = Fixture::new(id_size);
        fixture.string(1, "app");
        fixture.string(2, "image");
        fixture.string(3, "zygote");
        fixture.string(4, "other");
        fixture.load(10, 99);
        fixture.load(11, 99);
        let mut dump = fixture.heap_info(2);
        dump.extend(fixture.heap_info(1));
        dump.extend(fixture.class(11, 0, 24, &[], &[]));
        dump.extend(fixture.instance(20, 10, &[]));
        dump.extend(fixture.heap_info(3));
        dump.extend(fixture.class(12, 0, 8, &[], &[]));
        dump.extend(fixture.heap_info(4));
        fixture.record(0x1c, &dump);
        fixture.load(11, 99); // A later LOAD_CLASS must not reset the dump's heap.
        fixture.load(12, 99);
        fixture.load(13, 99);
        let file = fixture.file();
        let heap = file.open();
        assert_eq!(heap.classes.len(), 4);
        assert_eq!(heap.heaps, ["App", "Zygote"]);
        assert_eq!(heap.classes[heap.class_by_id[&10]].heap, usize::MAX);
        assert_eq!(heap.classes[heap.class_by_id[&11]].heap, 0);
        assert_eq!(heap.classes[heap.class_by_id[&12]].heap, 1);
        assert_eq!(heap.classes[heap.class_by_id[&13]].heap, usize::MAX);
        assert_eq!(object(&heap, 20).heap, 0);
        assert!(heap
            .heaps
            .get(heap.classes[heap.class_by_id[&10]].heap)
            .is_none());
        heap.check_source().unwrap();
    }
}

#[test]
fn resolves_late_classes_strings_sizes_and_verbatim_names() {
    for id_size in [4, 8] {
        let mut fixture = Fixture::new(id_size);
        let class_id = if id_size == 4 {
            0xf1234567
        } else {
            0xf123456789abcdef
        };
        let mut dump = fixture.instance(7, class_id, &[1, 2]);
        dump.extend(fixture.objects(8, class_id, &[7, 0, class_id]));
        dump.extend(fixture.class(class_id, 0, 40, &[], &[]));
        fixture.record(0x0c, &dump);
        fixture.load(class_id, 1);
        fixture.string(1, "[Lcom/example/LateClass;");
        let file = fixture.file();
        let heap = file.open();
        let class = &heap.classes[heap.class_by_id[&class_id]];
        assert_eq!(class.name, "[Lcom/example/LateClass;");
        assert_eq!(class.loader_id, 99);
        assert_eq!(class.instance_size, 40);
        assert_eq!(object(&heap, 7).count, 2);
        assert_eq!(object(&heap, 7).shallow_size, 40);
        assert_eq!(object(&heap, 8).count, 3);
        assert_eq!(
            object(&heap, 8).shallow_size,
            if id_size == 4 { 24 } else { 40 }
        );
        assert!(heap.warnings.is_empty());
        assert!(heap.values(object(&heap, 7), 0, 10).unwrap().is_empty());
        let page = heap.values(object(&heap, 8), 1, 10).unwrap();
        assert_eq!(
            page,
            vec![
                Value {
                    name: "[1]".into(),
                    ty: 2,
                    bits: 0
                },
                Value {
                    name: "[2]".into(),
                    ty: 2,
                    bits: class_id
                },
            ]
        );
    }
}

#[test]
fn inherits_fields_decodes_legacy_bits_and_prefixes_statics() {
    for id_size in [4, 8] {
        let mut fixture = Fixture::new(id_size);
        let types = [2, 4, 5, 6, 7, 8, 9, 10, 11];
        let bits = [
            0,
            0xff,
            0xffff,
            0xffff_ffff,
            u64::MAX,
            0x80,
            0x8000,
            0x8000_0000,
            0x8000_0000_0000_0000,
        ];
        let expected = [
            0,
            255,
            65535,
            u64::MAX,
            u64::MAX,
            128,
            32768,
            0xffff_ffff_8000_0000,
            0x8000_0000_0000_0000,
        ];
        let fields: Vec<_> = types
            .iter()
            .enumerate()
            .map(|(i, &ty)| (100 + i as u64, ty))
            .collect();
        let payload: Vec<_> = types
            .iter()
            .zip(bits)
            .flat_map(|(&ty, value)| fixture.value(ty, value))
            .collect();
        let mut dump = fixture.class(
            10,
            11,
            64,
            &fields[..4],
            &[(100, 2, 20), (107, 10, 0xffff_ffff)],
        );
        dump.extend(fixture.class(11, 0, 48, &fields[4..], &[]));
        dump.extend(fixture.instance(20, 10, &payload));
        dump.extend(fixture.instance(21, 10, &[]));
        fixture.record(0x1c, &dump);
        for (i, _) in types.iter().enumerate() {
            fixture.string(100 + i as u64, &format!("field{i}"));
        }
        let file = fixture.file();
        let heap = file.open();
        let all = heap.values(object(&heap, 20), 0, 100).unwrap();
        assert_eq!(
            all.iter().map(|value| value.bits).collect::<Vec<_>>(),
            expected
        );
        assert_eq!(all.iter().map(|value| value.ty).collect::<Vec<_>>(), types);
        assert_eq!(all[8].name, "field8");
        assert_eq!(heap.values(object(&heap, 20), 3, 2).unwrap(), all[3..5]);
        assert_eq!(heap.fields_for(10).len(), 9);
        assert!(heap.values(object(&heap, 21), 0, 100).unwrap().is_empty());
        let class = &heap.classes[heap.class_by_id[&10]];
        assert_eq!(
            class.static_fields[0],
            Value {
                name: "static field0".into(),
                ty: 2,
                bits: 20
            }
        );
        assert_eq!(
            class.static_fields[1],
            Value {
                name: "static field7".into(),
                ty: 10,
                bits: u64::MAX
            }
        );
    }
}

#[test]
fn android_heaps_and_all_roots_continue_across_segments() {
    let tags = [
        1, 2, 3, 4, 5, 6, 7, 8, 0x89, 0x8a, 0x8b, 0x8c, 0x8d, 0x8e, 0x90, 0xff,
    ];
    let kinds = [
        "JNI_GLOBAL",
        "JNI_LOCAL",
        "JAVA_FRAME",
        "NATIVE_STACK",
        "STICKY_CLASS",
        "THREAD_BLOCK",
        "MONITOR_USED",
        "THREAD_OBJECT",
        "INTERNED_STRING",
        "FINALIZING",
        "DEBUGGER",
        "REFERENCE_CLEANUP",
        "VM_INTERNAL",
        "JNI_MONITOR",
        "UNREACHABLE",
        "UNKNOWN",
    ];
    for id_size in [4, 8] {
        let mut fixture = Fixture::new(id_size);
        fixture.string(1, "APP heap");
        fixture.string(2, "boot IMAGE heap");
        fixture.string(3, "zygote space");
        fixture.string(4, "unrecognized");
        let mut dump = fixture.heap_info(1);
        dump.extend(fixture.class(10, 0, 24, &[], &[]));
        dump.extend(fixture.instance(20, 10, &[]));
        for &tag in &tags {
            dump.extend(fixture.root(tag, 20));
        }
        dump.extend(fixture.root(1, 20)); // Same id/kind is deduplicated.
        dump.extend(fixture.root(0xff, 0)); // Null roots are ignored.
        fixture.record(0x1c, &dump);
        let mut dump = fixture.instance(21, 10, &[]); // Active heap crosses segments.
        dump.extend(fixture.heap_info(2));
        dump.extend(fixture.class(11, 0, 8, &[], &[]));
        dump.extend(fixture.primitive(30, 8, 10, None));
        dump.extend(fixture.heap_info(3));
        dump.extend(fixture.instance(22, 10, &[]));
        dump.extend(fixture.heap_info(4));
        dump.extend(fixture.instance(23, 10, &[]));
        fixture.record(0x1c, &dump);
        fixture.record(0x2c, &[]);
        let file = fixture.file();
        let heap = file.open();
        assert_eq!(heap.heaps, ["App", "Image", "Zygote", "Default"]);
        assert_eq!(heap.classes[heap.class_by_id[&10]].heap, 0);
        assert_eq!(heap.classes[heap.class_by_id[&11]].heap, 1);
        assert_eq!(object(&heap, 21).heap, 0);
        assert_eq!(object(&heap, 30).heap, 1);
        assert_eq!(object(&heap, 22).heap, 2);
        assert_eq!(object(&heap, 23).heap, 3);
        assert_eq!(
            heap.roots
                .iter()
                .map(|root| root.kind.as_str())
                .collect::<Vec<_>>(),
            kinds
        );
        assert!(heap.warnings.is_empty());
        assert_eq!(
            object(&heap, 30).shallow_size,
            if id_size == 4 { 22 } else { 26 }
        );
        assert!(heap
            .values(object(&heap, 30), 0, 1)
            .unwrap_err()
            .contains("unavailable"));
    }
}

#[test]
fn primitive_arrays_match_all_type_bits_and_header_sizes() {
    let cases = [
        (4, 255, 255),
        (5, 65535, 65535),
        (6, 0x8000_0000, 0xffff_ffff_8000_0000),
        (7, u64::MAX, u64::MAX),
        (8, 255, 255),
        (9, 65535, 65535),
        (10, 0xffff_ffff, u64::MAX),
        (11, u64::MAX, u64::MAX),
    ];
    for id_size in [4, 8] {
        let mut fixture = Fixture::new(id_size);
        let mut dump = Vec::new();
        for (i, &(ty, bits, _)) in cases.iter().enumerate() {
            let mut data = fixture.value(ty, 0);
            data.extend(fixture.value(ty, bits));
            dump.extend(fixture.primitive(100 + i as u64, ty, 2, Some(&data)));
        }
        fixture.record(0x0c, &dump);
        let file = fixture.file();
        let heap = file.open();
        for (i, &(ty, _, expected)) in cases.iter().enumerate() {
            let object = object(&heap, 100 + i as u64);
            let width = match ty {
                4 | 8 => 1,
                5 | 9 => 2,
                6 | 10 => 4,
                _ => 8,
            };
            let header = if id_size == 8 || ty == 7 || ty == 11 {
                16
            } else {
                12
            };
            assert_eq!(object.shallow_size, header + 2 * width);
            assert_eq!(
                heap.values(object, 1, 10).unwrap(),
                vec![Value {
                    name: "[1]".into(),
                    ty,
                    bits: expected
                }]
            );
        }
    }
}

#[test]
fn constant_pool_values_are_skipped_without_losing_class_fields() {
    let mut fixture = Fixture::new(4);
    let mut class = fixture.class(10, 0, 24, &[(100, 10)], &[]);
    let constant_offset = 1 + 4 + 4 + 6 * 4 + 4;
    let mut pool = 2_u16.to_be_bytes().to_vec();
    pool.extend(1_u16.to_be_bytes());
    pool.push(2);
    pool.extend(fixture.id(99));
    pool.extend(2_u16.to_be_bytes());
    pool.push(11);
    pool.extend(123_u64.to_be_bytes());
    class.splice(constant_offset..constant_offset + 2, pool);
    class.extend(fixture.instance(20, 10, &42_u32.to_be_bytes()));
    fixture.record(0x0c, &class);
    let file = fixture.file();
    let heap = file.open();
    let values = heap.values(object(&heap, 20), 0, 1).unwrap();
    assert_eq!(values[0].name, "<field-100>");
    assert_eq!(values[0].bits, 42);
}

#[test]
fn unknown_subrecord_stops_only_current_segment_with_explicit_offset() {
    let mut fixture = Fixture::new(4);
    fixture.record(0x7f, &[1, 2, 3]);
    let segment_offset = fixture.bytes.len() as u64;
    let mut dump = vec![0x77];
    dump.extend(fixture.instance(20, 10, &[]));
    fixture.record(0x1c, &dump);
    fixture.record(0x1c, &fixture.instance(21, 10, &[]));
    let file = fixture.file();
    let heap = file.open();
    assert_eq!(heap.objects.len(), 1);
    assert_eq!(heap.objects[0].id, 21);
    assert_eq!(heap.warnings.len(), 2);
    assert!(heap.warnings[0].message.contains("Unknown top-level"));
    assert!(heap.warnings[1]
        .message
        .contains("stopped parsing current segment"));
    assert_eq!(heap.warnings[1].offset, Some(segment_offset + 9));
}

#[test]
fn skips_standard_non_heap_records() {
    let mut fixture = Fixture::new(4);
    for tag in [3, 4, 5, 6, 7, 0x0a, 0x0b, 0x0d, 0x0e, 0x2c] {
        fixture.record(tag, &[]);
    }
    let file = fixture.file();
    let heap = file.open();
    assert!(heap.warnings.is_empty());
}

#[test]
fn rejects_every_truncation_of_a_complete_heap_record() {
    let mut fixture = Fixture::new(8);
    let mut dump = fixture.class(10, 0, 32, &[(100, 10)], &[(100, 2, 20)]);
    dump.extend(fixture.instance(20, 10, &42_u32.to_be_bytes()));
    dump.extend(fixture.objects(21, 10, &[20]));
    dump.extend(fixture.primitive(22, 11, 2, Some(&[0; 16])));
    fixture.record(0x0c, &dump);
    let header_len = Fixture::new(8).bytes.len();
    for end in header_len + 1..fixture.bytes.len() {
        let file = FixtureFile::new(&fixture.bytes[..end]);
        assert!(
            Heap::open(&file.0).is_err(),
            "accepted truncated file at {end}"
        );
    }
    fixture.file().open();
}

#[test]
fn rejects_negative_counts_invalid_types_and_segment_boundary_overruns() {
    for id_size in [4, 8] {
        let fixture = Fixture::new(id_size);
        let mut invalid = vec![
            fixture.primitive(10, 8, u32::MAX, None),
            fixture.primitive(10, 8, u32::MAX, Some(&[])),
            fixture.primitive(10, 2, 1, Some(&fixture.id(20))),
            fixture.primitive(10, 99, 0, None),
            fixture.primitive(10, 11, i32::MAX as u32, Some(&[])),
        ];
        let mut instance = fixture.instance(10, 20, &[]);
        let offset = 1 + 2 * id_size as usize + 4;
        instance[offset..offset + 4].copy_from_slice(&u32::MAX.to_be_bytes());
        invalid.push(instance);
        let mut array = fixture.objects(10, 20, &[]);
        let offset = 1 + id_size as usize + 4;
        array[offset..offset + 4].copy_from_slice(&u32::MAX.to_be_bytes());
        invalid.push(array);
        // Payload bytes from the next top-level record must not satisfy this array.
        invalid.push(fixture.primitive(10, 11, 2, Some(&[0; 1])));
        for dump in invalid {
            let mut fixture = Fixture::new(id_size);
            fixture.record(0x0c, &dump);
            fixture.record(0x04, &[0; 32]);
            assert!(Heap::open(&fixture.file().0).is_err());
        }
    }
}

#[test]
fn rejects_partial_typed_instance_fields_but_preserves_empty_legacy_instances() {
    let mut fixture = Fixture::new(4);
    let mut dump = fixture.class(10, 0, 24, &[(100, 10)], &[]);
    dump.extend(fixture.instance(20, 10, &[0, 1]));
    fixture.record(0x0c, &dump);
    assert!(Heap::open(&fixture.file().0)
        .unwrap_err()
        .contains("instance field"));
}

#[test]
fn handles_missing_metadata_and_inheritance_cycles() {
    let mut fixture = Fixture::new(4);
    fixture.load(10, 99);
    let mut dump = fixture.class(10, 11, 8, &[(100, 8)], &[]);
    dump.extend(fixture.class(11, 10, 8, &[(101, 8)], &[]));
    dump.extend(fixture.instance(20, 10, &[1, 2]));
    dump.extend(fixture.instance(21, 999, &[1, 2, 3]));
    fixture.record(0x0c, &dump);
    let file = fixture.file();
    let heap = file.open();
    assert_eq!(heap.classes[heap.class_by_id[&10]].name, "<unknown>");
    assert_eq!(heap.fields_for(10).len(), 2);
    assert_eq!(heap.values(object(&heap, 20), 0, 100).unwrap().len(), 2);
    assert_eq!(object(&heap, 21).shallow_size, 3);
    assert!(heap.values(object(&heap, 21), 0, 100).unwrap().is_empty());
}

#[test]
fn values_pages_are_bounded_and_reject_forged_objects() {
    let mut fixture = Fixture::new(4);
    fixture.record(0x0c, &fixture.primitive(10, 8, 5000, Some(&vec![42; 5000])));
    let file = fixture.file();
    let heap = file.open();
    let array = object(&heap, 10);
    assert_eq!(heap.values(array, 100, 4096).unwrap().len(), 4096);
    assert_eq!(heap.values(array, 4999, 4096).unwrap().len(), 1);
    assert!(heap.values(array, 5000, 1).unwrap().is_empty());
    assert!(heap.values(array, 0, 0).unwrap().is_empty());
    assert!(heap.values(array, 5001, 1).is_err());
    assert!(heap.values(array, 0, 4097).is_err());
    let mut forged = array.clone();
    forged.payload_offset += 1;
    assert!(heap
        .values(&forged, 0, 1)
        .unwrap_err()
        .contains("does not belong"));
}

#[test]
fn source_changes_invalidate_lazy_values_including_empty_queries() {
    let mut fixture = Fixture::new(4);
    fixture.record(0x0c, &fixture.primitive(10, 8, 3, Some(&[1, 2, 3])));
    for modification in 0..4 {
        let file = fixture.file();
        let heap = file.open();
        heap.values(object(&heap, 10), 0, 3).unwrap(); // Fill the read buffer first.
        match modification {
            0 => OpenOptions::new()
                .write(true)
                .open(&file.0)
                .unwrap()
                .set_len(1)
                .unwrap(),
            1 => {
                let replacement = fixture.file();
                fs::rename(&replacement.0, &file.0).unwrap();
            }
            2 => {
                fs::remove_file(&file.0).unwrap();
            }
            _ => {
                let mut changed = OpenOptions::new().write(true).open(&file.0).unwrap();
                changed.seek(SeekFrom::End(-1)).unwrap();
                changed.write_all(&[99]).unwrap();
                changed.sync_all().unwrap();
            }
        }
        assert!(heap.check_source().is_err());
        assert!(heap.values(object(&heap, 10), 0, 0).is_err());
        assert!(heap.values(object(&heap, 10), 0, 3).is_err());
    }
}

#[test]
fn committed_android_converted_fixture_matches_legacy_expectations() {
    let path = Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../parser-hprof/src/test/resources/hprof/android-converted-sample.hprof");
    let heap = Heap::open(&path).unwrap();
    assert_eq!(heap.format, "JAVA PROFILE 1.0.2");
    assert_eq!(heap.id_size, 4);
    assert_eq!(heap.classes.len(), 1);
    assert_eq!(heap.classes[0].name, "com.example.ConvertedSample");
    assert_eq!(heap.objects.len(), 1);
    assert_eq!(heap.objects[0].kind, ObjectKind::Instance);
    assert_eq!(heap.objects[0].class_id, heap.classes[0].id);
    assert_eq!(heap.objects[0].shallow_size, 24);
    assert!(heap.values(&heap.objects[0], 0, 100).unwrap().is_empty());
    assert!(heap.warnings.is_empty());
}

#[test]
fn large_sparse_arrays_retain_only_offsets_and_read_tail_on_demand() {
    let count = 8_000_000_u32;
    let mut fixture = Fixture::new(4);
    let prefix = fixture.primitive(10, 8, count, Some(&[]));
    let mut record = vec![0x0c];
    record.extend(0_u32.to_be_bytes());
    record.extend((prefix.len() as u32 + count).to_be_bytes());
    record.extend(prefix);
    fixture.bytes.extend(record);
    let file = fixture.file();
    let mut writer = OpenOptions::new().write(true).open(&file.0).unwrap();
    writer
        .set_len(fixture.bytes.len() as u64 + u64::from(count))
        .unwrap();
    writer.seek(SeekFrom::End(-1)).unwrap();
    writer.write_all(&[255]).unwrap();
    drop(writer);
    let heap = file.open();
    assert_eq!(heap.objects.len(), 1);
    assert_eq!(heap.objects[0].count, count);
    assert_eq!(
        heap.values(&heap.objects[0], count - 1, 1).unwrap()[0].bits,
        255
    );
    assert!(std::mem::size_of::<model::Object>() <= 80);
}

#[test]
fn oversized_unused_strings_are_not_materialized_but_names_have_a_budget() {
    let mut fixture = Fixture::new(4);
    let mut string = fixture.id(1);
    string.resize(4 + 1024 * 1024 + 1, b'x');
    fixture.record(1, &string);
    fixture.file().open();
    fixture.load(10, 1);
    assert!(Heap::open(&fixture.file().0)
        .unwrap_err()
        .contains("metadata string"));
}

#[test]
fn duplicate_object_ids_fail_instead_of_publishing_an_ambiguous_index() {
    let mut fixture = Fixture::new(4);
    let mut dump = fixture.instance(10, 20, &[]);
    dump.extend(fixture.instance(10, 20, &[]));
    fixture.record(0x0c, &dump);
    assert!(Heap::open(&fixture.file().0)
        .unwrap_err()
        .contains("Duplicate"));
}

#[test]
fn truncated_string_load_class_root_and_class_metadata_are_errors() {
    for id_size in [4, 8] {
        let mut fixture = Fixture::new(id_size);
        fixture.record(1, &[0; 3]);
        assert!(Heap::open(&fixture.file().0).is_err());
        let mut fixture = Fixture::new(id_size);
        fixture.record(2, &[0; 4]);
        assert!(Heap::open(&fixture.file().0).is_err());
        let fixture = Fixture::new(id_size);
        let root = fixture.root(1, 10);
        let class = fixture.class(10, 0, 8, &[(100, 10)], &[(100, 2, 20)]);
        for subrecord in [root, class] {
            for length in 1..subrecord.len() {
                let mut fixture = Fixture::new(id_size);
                fixture.record(0x0c, &subrecord[..length]);
                assert!(
                    Heap::open(&fixture.file().0).is_err(),
                    "accepted metadata truncation at {length}"
                );
            }
        }
    }
}

#[test]
fn rejects_non_regular_sources() {
    assert!(Heap::open(&std::env::temp_dir())
        .unwrap_err()
        .contains("regular file"));
    assert!(Heap::open(Path::new("/nonexistent/aps-parser-fixture.hprof")).is_err());
}

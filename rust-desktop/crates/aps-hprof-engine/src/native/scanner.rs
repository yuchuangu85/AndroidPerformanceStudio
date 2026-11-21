//! Selective native fields over the exclusively borrowed original source buffer.
use crate::model::{Field, Heap, ObjectKind};
use crate::references::{CHECK_BYTES, CHECK_OBJECTS, ReferenceCursor};
use std::{cell::RefMut, fs::File, io::BufReader};

#[derive(Clone, Copy, PartialEq, Eq)]
pub(super) enum Role {
    Other,
    Cleaner,
    Thunk,
    Registry,
}

impl Role {
    pub(super) fn for_name(name: &str) -> Self {
        for (role, expected) in [
            (Self::Cleaner, "sun.misc.Cleaner"),
            (
                Self::Thunk,
                "libcore.util.NativeAllocationRegistry$CleanerThunk",
            ),
            (Self::Registry, "libcore.util.NativeAllocationRegistry"),
        ] {
            if crate::model::class_name_matches(name, expected) {
                return role;
            }
        }
        Self::Other
    }
}

#[derive(Clone, Copy)]
enum Selected {
    Referent,
    Thunk,
    Reference,
    Size,
}

#[derive(Clone, Copy)]
struct ReadField {
    offset: u64,
    width: u8,
    selected: Selected,
}

struct Layout {
    fields: Vec<ReadField>,
    ends: Vec<u64>,
}

impl Layout {
    fn new(fields: &[Field], id_size: u8, role: Role) -> Result<Self, String> {
        let mut result = Self {
            fields: Vec::new(),
            ends: Vec::with_capacity(fields.len()),
        };
        let mut offset = 0u64;
        let (mut referent, mut thunk, mut size) = (false, false, false);
        for field in fields {
            let width = crate::parser::width(field.ty, id_size)? as u8;
            let selected = match role {
                Role::Cleaner if field.ty == 2 && field.name == "referent" && !referent => {
                    referent = true;
                    Some(Selected::Referent)
                }
                Role::Cleaner if field.ty == 2 && field.name == "thunk" && !thunk => {
                    thunk = true;
                    Some(Selected::Thunk)
                }
                Role::Thunk if field.ty == 2 => Some(Selected::Reference),
                Role::Registry if field.ty == 11 && field.name == "size" && !size => {
                    size = true;
                    Some(Selected::Size)
                }
                _ => None,
            };
            if let Some(selected) = selected {
                result.fields.push(ReadField {
                    offset,
                    width,
                    selected,
                });
            }
            offset = offset
                .checked_add(u64::from(width))
                .ok_or("native field layout overflow")?;
            result.ends.push(offset);
        }
        Ok(result)
    }
}

#[derive(Default)]
pub(super) struct Fields {
    pub(super) referent: Option<u64>,
    pub(super) thunk: Option<u64>,
    pub(super) first_reference: Option<u64>,
    pub(super) size: Option<u64>,
}

pub(super) struct NativeScanner<'a> {
    heap: &'a Heap,
    reader: RefMut<'a, BufReader<File>>,
    cursor: ReferenceCursor,
    roles: Vec<Role>,
    layouts: Vec<Option<Layout>>,
    objects_since_check: usize,
    skipped_bytes_since_check: u64,
    #[cfg(test)]
    checks: std::cell::Cell<usize>,
}

impl<'a> NativeScanner<'a> {
    pub(super) fn new(heap: &'a Heap) -> Result<Self, String> {
        let mut reader = heap
            .source
            .reader
            .try_borrow_mut()
            .map_err(|_| "HPROF source reader is busy")?;
        heap.source.check_file(&heap.path, reader.get_ref())?;
        let cursor = ReferenceCursor::new(&mut *reader, heap.file_size, heap.id_size)?;
        Ok(Self {
            heap,
            reader,
            cursor,
            roles: heap
                .classes
                .iter()
                .map(|class| Role::for_name(&class.name))
                .collect(),
            layouts: heap.classes.iter().map(|_| None).collect(),
            objects_since_check: 0,
            skipped_bytes_since_check: 0,
            #[cfg(test)]
            checks: std::cell::Cell::new(1),
        })
    }

    pub(super) fn role(&self, index: usize) -> Role {
        self.heap
            .class_by_id
            .get(&self.heap.objects[index].class_id)
            .map_or(Role::Other, |&class| self.roles[class])
    }

    pub(super) fn observe(&mut self, index: usize) -> Result<(), String> {
        let object = self
            .heap
            .objects
            .get(index)
            .ok_or("native object index out of bounds")?;
        let bytes = match object.kind {
            ObjectKind::Instance => u64::from(object.count),
            ObjectKind::ObjectArray => u64::from(object.count)
                .checked_mul(u64::from(self.heap.id_size))
                .ok_or("native array size overflow")?,
            ObjectKind::PrimitiveArray {
                has_data: false, ..
            } => 0,
            ObjectKind::PrimitiveArray { ty, has_data: true } => u64::from(object.count)
                .checked_mul(crate::parser::width(ty, self.heap.id_size)?)
                .ok_or("native array size overflow")?,
        };
        if object
            .payload_offset
            .checked_add(bytes)
            .is_none_or(|end| end > self.heap.file_size)
        {
            return Err("native payload exceeds source size".into());
        }
        self.objects_since_check += 1;
        self.skipped_bytes_since_check = self.skipped_bytes_since_check.saturating_add(bytes);
        self.check_batch()
    }

    pub(super) fn scan(
        &mut self,
        index: usize,
        emit: impl FnOnce(&Self, Fields) -> Result<(), String>,
    ) -> Result<(), String> {
        let result = self
            .read_fields(index)
            .and_then(|fields| emit(self, fields));
        if result.is_err() {
            self.check_source()?;
        }
        result
    }

    fn read_fields(&mut self, index: usize) -> Result<Fields, String> {
        self.observe(index)?;
        let object = &self.heap.objects[index];
        let mut result = Fields::default();
        let Some(&class) = self.heap.class_by_id.get(&object.class_id) else {
            return Ok(result);
        };
        if self.layouts[class].is_none() {
            self.layouts[class] = Some(Layout::new(
                &self.heap.fields_for(object.class_id),
                self.heap.id_size,
                self.roles[class],
            )?);
        }
        let layout = self.layouts[class].as_ref().unwrap();
        if object.kind != ObjectKind::Instance {
            // Preserve the old values() semantics even for malformed array chains:
            // array slots have no named fields, but a Thunk can expose references.
            let count = layout
                .ends
                .partition_point(|&end| end <= u64::from(object.count));
            if count != 0
                && matches!(
                    object.kind,
                    ObjectKind::PrimitiveArray {
                        has_data: false,
                        ..
                    }
                )
            {
                return Err("HPROF primitive array data is unavailable (NODATA record)".into());
            }
            if object.kind == ObjectKind::ObjectArray && self.roles[class] == Role::Thunk {
                for slot in 0..count.min(object.count as usize) {
                    let offset = (slot as u64)
                        .checked_mul(u64::from(self.heap.id_size))
                        .ok_or("native array offset overflow")?;
                    let id = self.read_at(object.payload_offset, offset, self.heap.id_size)?;
                    if id != 0 {
                        result.first_reference = Some(id);
                        break;
                    }
                }
            }
            return Ok(result);
        }
        for field in 0..layout.fields.len() {
            let field = self.layouts[class].as_ref().unwrap().fields[field];
            if field
                .offset
                .checked_add(u64::from(field.width))
                .is_none_or(|end| end > u64::from(object.count))
            {
                break;
            }
            let bits = self.read_at(object.payload_offset, field.offset, field.width)?;
            match field.selected {
                Selected::Referent => result.referent = Some(bits),
                Selected::Thunk => result.thunk = Some(bits),
                Selected::Size => result.size = Some(bits),
                Selected::Reference if bits != 0 => {
                    result.first_reference = Some(bits);
                    break;
                }
                Selected::Reference => {}
            }
        }
        Ok(result)
    }

    fn read_at(&mut self, base: u64, offset: u64, width: u8) -> Result<u64, String> {
        let position = base
            .checked_add(offset)
            .ok_or("native field offset overflow")?;
        self.cursor.advance_to(&mut *self.reader, position)?;
        self.check_batch()?;
        let bits = self.cursor.read_bits(&mut *self.reader, width)?;
        self.check_batch()?;
        Ok(bits)
    }

    fn check_batch(&mut self) -> Result<(), String> {
        if self.objects_since_check >= CHECK_OBJECTS
            || self
                .cursor
                .bytes_since_check
                .saturating_add(self.skipped_bytes_since_check)
                >= CHECK_BYTES
        {
            self.check_source()?;
            self.objects_since_check = 0;
            self.skipped_bytes_since_check = 0;
            self.cursor.bytes_since_check = 0;
        }
        Ok(())
    }

    fn check_source(&self) -> Result<(), String> {
        #[cfg(test)]
        self.checks.set(self.checks.get() + 1);
        self.heap
            .source
            .check_file(&self.heap.path, self.reader.get_ref())
    }

    pub(super) fn finish(self) -> Result<(), String> {
        self.check_source()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{Class, Object, Source};
    use std::{
        collections::HashMap,
        fs::{self, OpenOptions},
        io::{BufRead, Seek, SeekFrom, Write},
        path::PathBuf,
        sync::atomic::{AtomicU64, Ordering},
    };

    struct Fixture(PathBuf);
    impl Fixture {
        fn new() -> Self {
            static NEXT: AtomicU64 = AtomicU64::new(0);
            let path = std::env::temp_dir().join(format!(
                "aps-native-source-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            fs::create_dir(&path).unwrap();
            Self(path)
        }

        fn heap(&self, count: usize) -> Heap {
            let path = self.0.join("source");
            let bytes: Vec<_> = (0..count)
                .flat_map(|_| [0u64.to_be_bytes(), 7u64.to_be_bytes()].concat())
                .collect();
            fs::write(&path, &bytes).unwrap();
            let objects: Vec<_> = (0..count)
                .map(|index| Object {
                    id: index as u64 + 10,
                    class_id: 1,
                    kind: ObjectKind::Instance,
                    payload_offset: index as u64 * 16,
                    count: 16,
                    heap: 0,
                    shallow_size: 16,
                })
                .collect();
            Heap {
                source: Source::open(&path).unwrap(),
                path,
                format: "JAVA PROFILE 1.0.3".into(),
                id_size: 4,
                timestamp: 0,
                file_size: bytes.len() as u64,
                classes: vec![Class {
                    id: 1,
                    name: "libcore.util.NativeAllocationRegistry".into(),
                    super_id: 0,
                    instance_size: 16,
                    loader_id: 0,
                    fields: vec![
                        Field {
                            name: "ignored".into(),
                            ty: 11,
                        },
                        Field {
                            name: "size".into(),
                            ty: 11,
                        },
                    ],
                    static_fields: Vec::new(),
                    heap: 0,
                }],
                object_by_id: objects.iter().enumerate().map(|(i, o)| (o.id, i)).collect(),
                objects,
                roots: Vec::new(),
                heaps: vec!["App".into()],
                warnings: Vec::new(),
                class_by_id: HashMap::from([(1, 0)]),
            }
        }
    }

    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn native_scan_reuses_original_buffer_and_one_cached_layout_for_many_records() {
        let fixture = Fixture::new();
        let heap = fixture.heap(100_000);
        heap.source.reader.borrow_mut().fill_buf().unwrap();
        let buffered = heap.source.reader.borrow().buffer().len();
        let mut scanner = NativeScanner::new(&heap).unwrap();
        assert_eq!(scanner.reader.buffer().len(), buffered);
        assert!(
            heap.values(&heap.objects[0], 0, 2)
                .unwrap_err()
                .contains("busy")
        );
        for index in 0..heap.objects.len() {
            scanner
                .scan(index, |_, fields| {
                    assert_eq!(fields.size, Some(7));
                    Ok(())
                })
                .unwrap();
            if index < 2 {
                assert_eq!(scanner.reader.buffer().len(), buffered - 16 * (index + 1));
            }
        }
        assert_eq!(scanner.layouts.iter().filter(|v| v.is_some()).count(), 1);
        assert!(scanner.checks.get() > 1);
        assert!(
            scanner.checks.get() < 100,
            "checks are bounded batches, not per-record stats"
        );
        scanner.finish().unwrap();
        assert_eq!(heap.values(&heap.objects[0], 0, 2).unwrap()[1].bits, 7);
    }

    #[test]
    fn native_object_batches_check_skipped_primitive_and_no_selected_field_records() {
        for primitive in [true, false] {
            let fixture = Fixture::new();
            let mut heap = fixture.heap(CHECK_OBJECTS + 1);
            heap.classes[0].fields.clear();
            if primitive {
                for object in &mut heap.objects {
                    object.kind = ObjectKind::PrimitiveArray {
                        ty: 8,
                        has_data: true,
                    };
                }
            }
            let mut scanner = NativeScanner::new(&heap).unwrap();
            scanner.scan(0, |_, _| Ok(())).unwrap();
            assert!(scanner.reader.buffer().is_empty());
            OpenOptions::new()
                .write(true)
                .open(&heap.path)
                .unwrap()
                .set_len(0)
                .unwrap();
            for index in 1..CHECK_OBJECTS - 1 {
                scanner.scan(index, |_, _| Ok(())).unwrap();
            }
            assert!(scanner.scan(CHECK_OBJECTS - 1, |_, _| Ok(())).is_err());
        }
    }

    #[test]
    fn native_byte_batches_cover_skipped_payloads_and_large_cursor_gaps() {
        for primitive in [true, false] {
            let fixture = Fixture::new();
            let mut heap = fixture.heap(CHECK_BYTES as usize / 16 + 1);
            heap.classes[0].fields.clear();
            heap.objects[0].count = CHECK_BYTES as u32;
            if primitive {
                heap.objects[0].kind = ObjectKind::PrimitiveArray {
                    ty: 8,
                    has_data: true,
                };
            }
            let mut scanner = NativeScanner::new(&heap).unwrap();
            fs::write(&heap.path, []).unwrap();
            assert!(
                scanner.observe(0).is_err(),
                "one large skipped record crosses the byte budget"
            );
        }
        let fixture = Fixture::new();
        let heap = fixture.heap(CHECK_BYTES as usize / 16 + 2);
        let mut scanner = NativeScanner::new(&heap).unwrap();
        scanner.scan(0, |_, _| Ok(())).unwrap();
        let mut file = OpenOptions::new().write(true).open(&heap.path).unwrap();
        std::thread::sleep(std::time::Duration::from_millis(2));
        file.seek(SeekFrom::End(-1)).unwrap();
        file.write_all(&[9]).unwrap();
        assert!(
            scanner
                .scan(heap.objects.len() - 1, |_, _| panic!(
                    "must check before emitting"
                ))
                .is_err()
        );
    }

    #[test]
    fn native_cached_bytes_do_not_hide_truncation_and_callback_failures_check_identity() {
        let fixture = Fixture::new();
        let heap = fixture.heap(CHECK_OBJECTS + 1);
        let mut scanner = NativeScanner::new(&heap).unwrap();
        scanner.scan(0, |_, _| Ok(())).unwrap();
        assert!(!scanner.reader.buffer().is_empty());
        OpenOptions::new()
            .write(true)
            .open(&heap.path)
            .unwrap()
            .set_len(0)
            .unwrap();
        for index in 1..CHECK_OBJECTS - 1 {
            scanner.scan(index, |_, _| Ok(())).unwrap();
        }
        assert!(scanner.scan(CHECK_OBJECTS - 1, |_, _| Ok(())).is_err());
        drop(scanner);

        let heap = fixture.heap(2);
        let mut scanner = NativeScanner::new(&heap).unwrap();
        let error = scanner
            .scan(0, |_, _| Err("callback failed".into()))
            .unwrap_err();
        assert_eq!(error, "callback failed");
        assert_eq!(scanner.checks.get(), 2);
        let error = scanner
            .scan(1, |_, _| {
                fs::write(&heap.path, []).unwrap();
                Err("callback failed".into())
            })
            .unwrap_err();
        assert!(error.contains("source changed"));
        assert!(scanner.finish().is_err());
    }

    #[test]
    fn native_finish_checks_empty_scans_path_replacements_and_original_handle() {
        let fixture = Fixture::new();
        let heap = fixture.heap(0);
        let scanner = NativeScanner::new(&heap).unwrap();
        let replacement = fixture.0.join("replacement");
        fs::write(&replacement, []).unwrap();
        fs::rename(&replacement, &heap.path).unwrap();
        assert!(scanner.finish().is_err());
        assert!(NativeScanner::new(&heap).is_err());

        let heap = fixture.heap(2);
        let original = OpenOptions::new().write(true).open(&heap.path).unwrap();
        let mut scanner = NativeScanner::new(&heap).unwrap();
        scanner.scan(0, |_, _| Ok(())).unwrap();
        original.set_len(0).unwrap();
        assert!(scanner.finish().is_err());

        let heap = fixture.heap(2);
        let mut scanner = NativeScanner::new(&heap).unwrap();
        scanner.scan(0, |_, _| Ok(())).unwrap();
        fs::copy(&heap.path, &replacement).unwrap();
        fs::rename(&replacement, &heap.path).unwrap();
        assert!(scanner.finish().is_err());
    }

    #[test]
    fn native_scan_rejects_invalid_payload_bounds_without_panicking() {
        let fixture = Fixture::new();
        let mut heap = fixture.heap(1);
        heap.objects[0].payload_offset = u64::MAX;
        let mut scanner = NativeScanner::new(&heap).unwrap();
        assert!(scanner.scan(0, |_, _| Ok(())).is_err());
        assert!(scanner.scan(usize::MAX, |_, _| Ok(())).is_err());
        scanner.finish().unwrap();
    }
}

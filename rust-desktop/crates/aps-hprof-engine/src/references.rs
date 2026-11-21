//! Bounded, buffered reference-only reads for building the strong graph.
//!
//! The original source handle is exclusively borrowed for the scan. Path and
//! handle identities are checked on entry, every bounded batch, and on finish;
//! no graph may be published before finish succeeds. Worker SHA-256 checks and
//! the independently checked, lazy `Heap::values` API remain unchanged.
use crate::model::{Field, Heap, ObjectKind};
use std::cell::RefMut;
use std::fs::File;
use std::io::{BufRead, BufReader, Read, Seek};

pub(crate) const CHECK_OBJECTS: usize = 4096;
pub(crate) const CHECK_BYTES: u64 = 1024 * 1024;
const FORWARD_READ_BYTES: u64 = 64 * 1024;

struct ReferenceField {
    offset: u64,
    slot: u32,
}

struct ReferenceLayout {
    fields: Vec<ReferenceField>,
}

impl ReferenceLayout {
    fn new(fields: &[Field], id_size: u8) -> Result<Self, String> {
        let mut references = Vec::new();
        let mut offset = 0u64;
        for (slot, field) in fields.iter().enumerate() {
            if field.ty == 2 {
                references
                    .try_reserve(1)
                    .map_err(|_| "cannot allocate reference field layout")?;
                references.push(ReferenceField {
                    offset,
                    slot: u32::try_from(slot).map_err(|_| "too many instance fields")?,
                });
            }
            offset = offset
                .checked_add(crate::parser::width(field.ty, id_size)?)
                .ok_or("HPROF field layout size overflow")?;
        }
        Ok(Self { fields: references })
    }
}

pub(crate) struct ReferenceScanner<'a> {
    heap: &'a Heap,
    reader: RefMut<'a, BufReader<File>>,
    cursor: ReferenceCursor,
    layouts: Vec<ReferenceLayout>,
    objects_since_check: usize,
    #[cfg(test)]
    checks: std::cell::Cell<usize>,
}

impl<'a> ReferenceScanner<'a> {
    pub(crate) fn new(heap: &'a Heap, layouts: &[Vec<Field>]) -> Result<Self, String> {
        if layouts.len() != heap.classes.len() {
            return Err("reference layouts do not match HPROF classes".into());
        }
        let layouts = layouts
            .iter()
            .map(|fields| ReferenceLayout::new(fields, heap.id_size))
            .collect::<Result<Vec<_>, _>>()?;
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
            layouts,
            objects_since_check: 0,
            #[cfg(test)]
            checks: std::cell::Cell::new(1),
        })
    }

    /// Slots retain the original subclass-before-superclass field/array order.
    /// Null references are skipped; weak/dangling filtering belongs to Graph.
    pub(crate) fn scan_object(
        &mut self,
        index: usize,
        emit: impl FnMut(u32, u64) -> Result<(), String>,
    ) -> Result<(), String> {
        let result = self.read_object(index, emit);
        if result.is_err() {
            self.check_source()?;
        }
        result
    }

    fn read_object(
        &mut self,
        index: usize,
        mut emit: impl FnMut(u32, u64) -> Result<(), String>,
    ) -> Result<(), String> {
        let object = self
            .heap
            .objects
            .get(index)
            .ok_or("HPROF reference object index out of bounds")?;
        let bytes = match object.kind {
            ObjectKind::Instance => u64::from(object.count),
            ObjectKind::ObjectArray => u64::from(object.count)
                .checked_mul(u64::from(self.heap.id_size))
                .ok_or("HPROF object array payload size overflow")?,
            ObjectKind::PrimitiveArray { ty, has_data } => {
                if has_data {
                    u64::from(object.count)
                        .checked_mul(crate::parser::width(ty, self.heap.id_size)?)
                        .ok_or("HPROF primitive array payload size overflow")?
                } else {
                    0
                }
            }
        };
        if object
            .payload_offset
            .checked_add(bytes)
            .is_none_or(|end| end > self.heap.file_size)
        {
            return Err("HPROF reference payload exceeds source size".into());
        }
        match object.kind {
            ObjectKind::Instance => {
                if let Some(&class) = self.heap.class_by_id.get(&object.class_id) {
                    for field in 0..self.layouts[class].fields.len() {
                        let reference = &self.layouts[class].fields[field];
                        if reference
                            .offset
                            .checked_add(u64::from(self.heap.id_size))
                            .is_none_or(|end| end > bytes)
                        {
                            // Short instances ending at a field boundary are allowed.
                            break;
                        }
                        let slot = reference.slot;
                        let position = object.payload_offset + reference.offset;
                        self.advance_to(position)?;
                        let id = self.read_id()?;
                        if id != 0 {
                            emit(slot, id)?;
                        }
                    }
                }
            }
            ObjectKind::ObjectArray if object.count != 0 => {
                self.advance_to(object.payload_offset)?;
                for slot in 0..object.count {
                    let id = self.read_id()?;
                    if id != 0 {
                        emit(slot, id)?;
                    }
                }
            }
            ObjectKind::ObjectArray | ObjectKind::PrimitiveArray { .. } => {}
        }
        self.objects_since_check += 1;
        self.check_batch()
    }

    fn advance_to(&mut self, position: u64) -> Result<(), String> {
        self.cursor.advance_to(&mut *self.reader, position)?;
        self.check_batch()
    }

    fn read_id(&mut self) -> Result<u64, String> {
        let id = self.cursor.read_id(&mut *self.reader)?;
        self.check_batch()?;
        Ok(id)
    }

    fn check_batch(&mut self) -> Result<(), String> {
        if self.objects_since_check >= CHECK_OBJECTS || self.cursor.bytes_since_check >= CHECK_BYTES
        {
            self.check_source()?;
            self.objects_since_check = 0;
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

    pub(crate) fn finish(self) -> Result<(), String> {
        self.check_source()
    }
}

/// Logical cursor over the existing buffer, not a new reader per object/value.
pub(crate) struct ReferenceCursor {
    position: u64,
    size: u64,
    id_size: u8,
    pub(crate) bytes_since_check: u64,
}

impl ReferenceCursor {
    pub(crate) fn new(reader: &mut impl Seek, size: u64, id_size: u8) -> Result<Self, String> {
        if !matches!(id_size, 4 | 8) {
            return Err("invalid HPROF reference ID size".into());
        }
        let position = reader
            .stream_position()
            .map_err(|e| format!("Cannot locate HPROF reference reader: {e}"))?;
        Ok(Self {
            position,
            size,
            id_size,
            bytes_since_check: 0,
        })
    }

    pub(crate) fn advance_to(
        &mut self,
        reader: &mut (impl BufRead + Seek),
        to: u64,
    ) -> Result<(), String> {
        if to > self.size {
            return Err("HPROF reference offset exceeds source size".into());
        }
        let distance = to.abs_diff(self.position);
        if to < self.position || distance >= FORWARD_READ_BYTES {
            // Non-monotonic cached indexes remain supported. Large uninterpreted
            // payloads can be skipped without reading them into memory.
            let delta = i64::try_from(i128::from(to) - i128::from(self.position))
                .map_err(|_| "HPROF reference seek distance overflow")?;
            reader
                .seek_relative(delta)
                .map_err(|e| format!("Cannot seek HPROF references: {e}"))?;
        } else {
            let mut remaining = distance as usize;
            while remaining != 0 {
                let available = reader
                    .fill_buf()
                    .map_err(|e| format!("Cannot read HPROF references: {e}"))?
                    .len();
                if available == 0 {
                    return Err("Truncated HPROF reference payload".into());
                }
                let consumed = available.min(remaining);
                reader.consume(consumed);
                remaining -= consumed;
            }
        }
        self.position = to;
        self.bytes_since_check = self.bytes_since_check.saturating_add(distance);
        Ok(())
    }

    fn read_id(&mut self, reader: &mut impl Read) -> Result<u64, String> {
        self.read_bits(reader, self.id_size)
    }

    pub(crate) fn read_bits(&mut self, reader: &mut impl Read, width: u8) -> Result<u64, String> {
        if !matches!(width, 1 | 2 | 4 | 8) {
            return Err("invalid HPROF scalar width".into());
        }
        let end = self
            .position
            .checked_add(u64::from(width))
            .filter(|&end| end <= self.size)
            .ok_or("HPROF scalar exceeds source size")?;
        let mut bytes = [0u8; 8];
        reader
            .read_exact(&mut bytes[8 - width as usize..])
            .map_err(|e| format!("Cannot read HPROF scalar: {e}"))?;
        self.position = end;
        self.bytes_since_check = self.bytes_since_check.saturating_add(u64::from(width));
        Ok(u64::from_be_bytes(bytes))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::model::{Class, Object, Source};
    use std::collections::HashMap;
    use std::fs::{self, OpenOptions};
    use std::io::{self, Cursor, SeekFrom, Write};
    use std::path::PathBuf;
    use std::sync::atomic::{AtomicU64, Ordering};

    struct Fixture(PathBuf);

    impl Fixture {
        fn new() -> Self {
            static NEXT: AtomicU64 = AtomicU64::new(0);
            let path = std::env::temp_dir().join(format!(
                "aps-reference-scan-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            fs::create_dir(&path).unwrap();
            Self(path)
        }

        fn heap(&self, count: u32, array: bool) -> Heap {
            let path = self.0.join("source");
            let bytes: Vec<_> = (0..count).flat_map(|_| 7u32.to_be_bytes()).collect();
            fs::write(&path, &bytes).unwrap();
            let objects = if array {
                vec![Object {
                    id: 10,
                    class_id: 1,
                    kind: ObjectKind::ObjectArray,
                    payload_offset: 0,
                    count,
                    heap: 0,
                    shallow_size: bytes.len() as u64,
                }]
            } else {
                (0..count)
                    .map(|i| Object {
                        id: 10 + u64::from(i),
                        class_id: 1,
                        kind: ObjectKind::Instance,
                        payload_offset: u64::from(i) * 4,
                        count: 4,
                        heap: 0,
                        shallow_size: 4,
                    })
                    .collect()
            };
            Heap {
                source: Source::open(&path).unwrap(),
                path,
                format: "JAVA PROFILE 1.0.3".into(),
                id_size: 4,
                timestamp: 0,
                file_size: bytes.len() as u64,
                classes: vec![Class {
                    id: 1,
                    name: "Node".into(),
                    super_id: 0,
                    instance_size: 4,
                    loader_id: 0,
                    fields: vec![Field {
                        name: "next".into(),
                        ty: 2,
                    }],
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

    fn layouts(heap: &Heap) -> Vec<Vec<Field>> {
        heap.classes.iter().map(|c| heap.fields_for(c.id)).collect()
    }

    #[derive(Default)]
    struct CountingIo {
        bytes: Cursor<Vec<u8>>,
        reads: usize,
        seeks: usize,
        fail_read: bool,
        fail_seek: bool,
    }

    impl Read for CountingIo {
        fn read(&mut self, out: &mut [u8]) -> io::Result<usize> {
            self.reads += 1;
            if self.fail_read {
                return Err(io::Error::other("injected reference read failure"));
            }
            self.bytes.read(out)
        }
    }

    impl Seek for CountingIo {
        fn seek(&mut self, to: SeekFrom) -> io::Result<u64> {
            self.seeks += 1;
            if self.fail_seek {
                return Err(io::Error::other("injected reference seek failure"));
            }
            self.bytes.seek(to)
        }
    }

    #[test]
    fn hundred_thousand_small_records_preserve_buffer_without_per_object_seeks() {
        for id_size in [4, 8] {
            let count = 100_000u64;
            let stride = 32u64;
            let mut bytes = vec![0u8; (count * stride) as usize];
            for i in 0..count {
                let begin = (i * stride + 13) as usize;
                bytes[begin..begin + id_size as usize]
                    .copy_from_slice(&(i + 1).to_be_bytes()[8 - id_size as usize..]);
            }
            let size = bytes.len() as u64;
            let io = CountingIo {
                bytes: Cursor::new(bytes),
                ..CountingIo::default()
            };
            let mut reader = BufReader::with_capacity(64 * 1024, io);
            assert!(!reader.fill_buf().unwrap().is_empty());
            let buffered = reader.buffer().len();
            let mut cursor = ReferenceCursor::new(&mut reader, size, id_size).unwrap();
            assert_eq!(reader.buffer().len(), buffered);
            for i in 0..count {
                cursor.advance_to(&mut reader, i * stride + 13).unwrap();
                assert_eq!(cursor.read_id(&mut reader).unwrap(), i + 1);
            }
            assert_eq!(
                reader.get_ref().seeks,
                1,
                "only initial stream_position may seek"
            );
            assert!(reader.get_ref().reads <= size.div_ceil(64 * 1024) as usize + 1);
            assert_eq!(reader.capacity(), 64 * 1024);
        }
    }

    #[test]
    fn mixed_native_id_and_long_reads_keep_buffer_without_record_seeks() {
        for id_size in [4, 8] {
            let count = 100_000u64;
            let mut bytes = vec![0; count as usize * 32];
            for index in 0..count {
                let base = index as usize * 32;
                bytes[base + 1..base + 1 + id_size as usize]
                    .copy_from_slice(&(index + 1).to_be_bytes()[8 - id_size as usize..]);
                bytes[base + 17..base + 25].copy_from_slice(&(u64::MAX - index).to_be_bytes());
            }
            let size = bytes.len() as u64;
            let io = CountingIo {
                bytes: Cursor::new(bytes),
                ..CountingIo::default()
            };
            let mut reader = BufReader::with_capacity(64 * 1024, io);
            reader.fill_buf().unwrap();
            let mut cursor = ReferenceCursor::new(&mut reader, size, id_size).unwrap();
            for index in 0..count {
                cursor.advance_to(&mut reader, index * 32 + 1).unwrap();
                assert_eq!(cursor.read_bits(&mut reader, id_size).unwrap(), index + 1);
                cursor.advance_to(&mut reader, index * 32 + 17).unwrap();
                assert_eq!(cursor.read_bits(&mut reader, 8).unwrap(), u64::MAX - index);
            }
            assert_eq!(reader.get_ref().seeks, 1);
            assert!(reader.get_ref().reads <= size.div_ceil(64 * 1024) as usize + 1);
            assert!(cursor.read_bits(&mut reader, 3).is_err());
            cursor.advance_to(&mut reader, size - 4).unwrap();
            assert!(cursor.read_bits(&mut reader, 8).is_err());
        }
    }

    #[test]
    fn cursor_supports_non_monotonic_offsets_large_skips_and_fallible_io() {
        let mut bytes = vec![0; (FORWARD_READ_BYTES * 2 + 8) as usize];
        bytes[8..16].copy_from_slice(&9u64.to_be_bytes());
        bytes[FORWARD_READ_BYTES as usize * 2..].copy_from_slice(&13u64.to_be_bytes());
        let size = bytes.len() as u64;
        let io = CountingIo {
            bytes: Cursor::new(bytes),
            ..CountingIo::default()
        };
        let mut reader = BufReader::with_capacity(64 * 1024, io);
        let mut cursor = ReferenceCursor::new(&mut reader, size, 8).unwrap();
        cursor
            .advance_to(&mut reader, FORWARD_READ_BYTES * 2)
            .unwrap();
        assert_eq!(cursor.read_id(&mut reader).unwrap(), 13);
        assert_eq!(
            reader.get_ref().reads,
            1,
            "large uninterpreted payload is not read"
        );
        cursor.advance_to(&mut reader, 8).unwrap();
        assert_eq!(cursor.read_id(&mut reader).unwrap(), 9);
        assert!(cursor.advance_to(&mut reader, size + 1).is_err());
        cursor.advance_to(&mut reader, size - 1).unwrap();
        assert!(cursor.read_id(&mut reader).is_err());
        reader.get_mut().fail_seek = true;
        assert!(cursor.advance_to(&mut reader, 0).is_err());

        let mut reader = BufReader::new(CountingIo {
            fail_read: true,
            ..CountingIo::default()
        });
        let mut cursor = ReferenceCursor::new(&mut reader, 8, 8).unwrap();
        assert!(cursor.read_id(&mut reader).is_err());
        assert!(ReferenceCursor::new(&mut reader, 8, 3).is_err());
    }

    #[test]
    fn checkpoints_scale_by_batch_not_by_object_and_keep_lazy_values_available() {
        let fixture = Fixture::new();
        let heap = fixture.heap(100_000, false);
        let mut scanner = ReferenceScanner::new(&heap, &layouts(&heap)).unwrap();
        for i in 0..heap.objects.len() {
            scanner
                .scan_object(i, |slot, id| {
                    assert_eq!((slot, id), (0, 7));
                    Ok(())
                })
                .unwrap();
        }
        assert!(scanner.checks.get() > 1);
        assert!(scanner.checks.get() < 100, "no per-object metadata probes");
        scanner.finish().unwrap();
        assert_eq!(heap.values(&heap.objects[0], 0, 1).unwrap()[0].bits, 7);
    }

    #[test]
    fn object_batch_detects_truncation_even_while_cached_bytes_are_available() {
        let fixture = Fixture::new();
        let heap = fixture.heap(CHECK_OBJECTS as u32 + 1, false);
        let mut scanner = ReferenceScanner::new(&heap, &layouts(&heap)).unwrap();
        scanner.scan_object(0, |_, _| Ok(())).unwrap();
        assert!(!scanner.reader.buffer().is_empty());
        OpenOptions::new()
            .write(true)
            .open(&heap.path)
            .unwrap()
            .set_len(0)
            .unwrap();
        let mut failed = false;
        for i in 1..CHECK_OBJECTS {
            if scanner.scan_object(i, |_, _| Ok(())).is_err() {
                failed = true;
                break;
            }
        }
        assert!(
            failed,
            "a full object batch cannot accept a truncated source"
        );
    }

    #[test]
    fn byte_batch_detects_in_place_changes_during_one_long_array() {
        let fixture = Fixture::new();
        let heap = fixture.heap((CHECK_BYTES / 4 + 2) as u32, true);
        let mut scanner = ReferenceScanner::new(&heap, &layouts(&heap)).unwrap();
        let mut seen = 0;
        let result = scanner.scan_object(0, |_, _| {
            if seen == 0 {
                std::thread::sleep(std::time::Duration::from_millis(2));
                let mut file = OpenOptions::new().write(true).open(&heap.path).unwrap();
                file.seek(SeekFrom::End(-1)).unwrap();
                file.write_all(&[8]).unwrap();
            }
            seen += 1;
            Ok(())
        });
        assert!(result.is_err());
        assert!(
            seen < heap.objects[0].count,
            "check before finishing a single long array"
        );
        assert!(scanner.checks.get() > 1);
    }

    #[test]
    fn finish_rejects_path_replacement_even_when_content_is_identical() {
        let fixture = Fixture::new();
        let heap = fixture.heap(4, false);
        let mut scanner = ReferenceScanner::new(&heap, &layouts(&heap)).unwrap();
        scanner.scan_object(0, |_, _| Ok(())).unwrap();
        let replacement = fixture.0.join("replacement");
        std::thread::sleep(std::time::Duration::from_millis(2));
        fs::write(&replacement, fs::read(&heap.path).unwrap()).unwrap();
        fs::remove_file(&heap.path).unwrap();
        fs::rename(&replacement, &heap.path).unwrap();
        assert!(scanner.finish().is_err());
        assert!(ReferenceScanner::new(&heap, &layouts(&heap)).is_err());
    }

    #[test]
    fn primitive_only_batches_still_check_identity_without_reading_values() {
        let fixture = Fixture::new();
        let mut heap = fixture.heap(CHECK_OBJECTS as u32 + 1, false);
        for object in &mut heap.objects {
            object.kind = ObjectKind::PrimitiveArray {
                ty: 8,
                has_data: true,
            };
        }
        let mut scanner = ReferenceScanner::new(&heap, &layouts(&heap)).unwrap();
        scanner
            .scan_object(0, |_, _| panic!("no references"))
            .unwrap();
        fs::write(&heap.path, []).unwrap();
        for i in 1..CHECK_OBJECTS - 1 {
            scanner
                .scan_object(i, |_, _| panic!("no references"))
                .unwrap();
        }
        assert!(
            scanner
                .scan_object(CHECK_OBJECTS - 1, |_, _| Ok(()))
                .is_err()
        );
    }

    #[test]
    fn edge_callback_errors_propagate_without_consuming_other_objects() {
        let fixture = Fixture::new();
        let heap = fixture.heap(2, false);
        let mut scanner = ReferenceScanner::new(&heap, &layouts(&heap)).unwrap();
        let error = scanner.scan_object(0, |_, _| Err("injected graph edge failure".into()));
        assert_eq!(error.unwrap_err(), "injected graph edge failure");
        assert_eq!(scanner.checks.get(), 2);
        let error = scanner.scan_object(0, |_, _| {
            fs::write(&heap.path, []).unwrap();
            Err("injected graph edge failure".into())
        });
        assert!(error.unwrap_err().contains("source changed"));
    }

    #[test]
    fn finish_checks_empty_and_primitive_only_scans_and_rejects_bad_bounds() {
        let fixture = Fixture::new();
        let mut heap = fixture.heap(0, false);
        let scanner = ReferenceScanner::new(&heap, &layouts(&heap)).unwrap();
        fs::write(&heap.path, [0]).unwrap();
        assert!(scanner.finish().is_err());

        heap = fixture.heap(4, false);
        heap.objects.truncate(1);
        heap.objects[0].kind = ObjectKind::PrimitiveArray {
            ty: 8,
            has_data: true,
        };
        let mut scanner = ReferenceScanner::new(&heap, &layouts(&heap)).unwrap();
        scanner
            .scan_object(0, |_, _| panic!("primitive data is not decoded"))
            .unwrap();
        fs::remove_file(&heap.path).unwrap();
        assert!(scanner.finish().is_err());

        heap = fixture.heap(1, false);
        heap.objects[0].payload_offset = u64::MAX;
        let mut scanner = ReferenceScanner::new(&heap, &layouts(&heap)).unwrap();
        assert!(scanner.scan_object(0, |_, _| Ok(())).is_err());
        assert!(scanner.scan_object(usize::MAX, |_, _| Ok(())).is_err());
    }
}

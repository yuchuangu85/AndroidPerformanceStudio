use crate::model::{Class, Field, Heap, Object, ObjectKind, Root, Source, Value, Warning};
use std::collections::{HashMap, HashSet};
use std::fs::File;
use std::io::{BufReader, Read, Seek, SeekFrom};
use std::path::Path;

const MAX_HEADER_BYTES: usize = 256;
const MAX_NAME_BYTES: u64 = 1024 * 1024;
const MAX_PAGE_VALUES: u32 = 4096;
const HEAP_NAMES: [&str; 4] = ["App", "Image", "Zygote", "Default"];

pub(crate) fn open(path: &Path) -> Result<Heap, String> {
    let path = if path.is_absolute() {
        path.to_path_buf()
    } else {
        std::env::current_dir()
            .map_err(|e| format!("Cannot resolve HPROF path: {e}"))?
            .join(path)
    };
    let source = Source::open(&path)?;
    let file_size = source.len();
    let (format, id_size, timestamp, mut state) = {
        let mut file = source.reader.borrow_mut();
        let mut reader = Reader::new(&mut file, 0, file_size, 0)?;
        let format = reader.header()?;
        let id_size = match reader.u32()? {
            4 => 4,
            8 => 8,
            size => return Err(format!("Unsupported HPROF id size {size}")),
        };
        reader.id_size = id_size;
        let timestamp = reader.u64()?;
        let mut state = State::new();
        while reader.pos < file_size {
            let offset = reader.pos;
            reader.require(9)?;
            let tag = reader.u8()?;
            reader.skip(4)?;
            let length = u64::from(reader.u32()?);
            let end = reader.checked_end(length)?;
            reader.end = end;
            parse_record(tag, &mut reader, &mut state, offset)?;
            reader.seek(end)?;
            reader.end = file_size;
        }
        state.resolve(&mut reader)?;
        (format, id_size, timestamp, state)
    };
    source.check(&path)?;
    state.classes.sort_by_key(|class| class.id as i64);
    let classes: Vec<Class> = state.classes.into_iter().map(|raw| raw.resolved).collect();
    let class_by_id: HashMap<_, _> = classes
        .iter()
        .enumerate()
        .map(|(i, class)| (class.id, i))
        .collect();
    // Resolve sizes only after all class dumps, including those following instances.
    for object in &mut state.objects {
        if object.kind == ObjectKind::Instance {
            if let Some(&i) = class_by_id.get(&object.class_id) {
                if classes[i].instance_size > 0 {
                    object.shallow_size = classes[i].instance_size;
                }
            }
        }
    }
    let mut heap = Heap {
        path,
        format,
        id_size,
        timestamp,
        file_size,
        classes,
        objects: state.objects,
        roots: state.roots,
        heaps: Vec::new(),
        warnings: state.warnings,
        class_by_id,
        object_by_id: state.object_by_id,
        source,
    };
    compact_heaps(&mut heap);
    validate_instance_lengths(&heap)?;
    heap.check_source()?;
    Ok(heap)
}

pub(crate) fn validate_cached(heap: &Heap) -> Result<(), String> {
    if heap.file_size != heap.source.len() || !matches!(heap.id_size, 4 | 8) {
        return Err("Invalid cached HPROF header or source size".into());
    }
    for class in &heap.classes {
        if class.id == 0 || (class.heap != usize::MAX && class.heap >= heap.heaps.len()) {
            return Err("Invalid cached HPROF class identity or heap".into());
        }
        for field in &class.fields {
            width(field.ty, heap.id_size)?;
        }
        for value in &class.static_fields {
            width(value.ty, heap.id_size)?;
        }
    }
    for object in &heap.objects {
        if object.id == 0
            || heap.class_by_id.contains_key(&object.id)
            || object.heap >= heap.heaps.len()
        {
            return Err("Invalid cached HPROF object identity or heap".into());
        }
        let size = match object.kind {
            ObjectKind::Instance => u64::from(object.count),
            ObjectKind::ObjectArray => u64::from(object.count) * u64::from(heap.id_size),
            ObjectKind::PrimitiveArray { ty, has_data } => {
                if ty == 2 {
                    return Err("Invalid cached HPROF primitive array type".into());
                }
                let size = u64::from(object.count) * width(ty, heap.id_size)?;
                if has_data {
                    size
                } else {
                    0
                }
            }
        };
        if object
            .payload_offset
            .checked_add(size)
            .is_none_or(|end| end > heap.file_size)
        {
            return Err("Invalid cached HPROF payload offset".into());
        }
    }
    validate_instance_lengths(heap)
}

pub(crate) fn values(
    heap: &Heap,
    object: &Object,
    start: u32,
    limit: u32,
) -> Result<Vec<Value>, String> {
    heap.check_source()?;
    let result = read_values(heap, object, start, limit);
    heap.check_source()?;
    result
}

fn read_values(heap: &Heap, object: &Object, start: u32, limit: u32) -> Result<Vec<Value>, String> {
    if limit > MAX_PAGE_VALUES {
        return Err(format!(
            "HPROF value page exceeds {MAX_PAGE_VALUES} elements"
        ));
    }
    if heap
        .object_by_id
        .get(&object.id)
        .and_then(|&i| heap.objects.get(i))
        != Some(object)
    {
        return Err("Object does not belong to this HPROF index".into());
    }
    let mut file = heap
        .source
        .reader
        .try_borrow_mut()
        .map_err(|_| "HPROF source reader is busy")?;
    let mut reader = Reader::new(
        &mut file,
        object.payload_offset,
        heap.file_size,
        heap.id_size,
    )?;
    let mut result = Vec::new();
    match object.kind {
        ObjectKind::Instance => {
            reader.end = reader.checked_end(u64::from(object.count))?;
            let fields = heap.fields_for(object.class_id);
            for (index, field) in fields.into_iter().enumerate() {
                if reader.pos == reader.end {
                    break;
                }
                if index < start as usize {
                    reader.skip(width(field.ty, heap.id_size)?)?;
                } else {
                    if result.len() == limit as usize {
                        break;
                    }
                    result.push(Value {
                        bits: reader.value(field.ty)?,
                        name: field.name,
                        ty: field.ty,
                    });
                }
            }
        }
        ObjectKind::ObjectArray | ObjectKind::PrimitiveArray { .. } => {
            if start > object.count {
                return Err("HPROF array start exceeds element count".into());
            }
            let count = limit.min(object.count - start);
            let ty = match object.kind {
                ObjectKind::ObjectArray => 2,
                ObjectKind::PrimitiveArray {
                    has_data: false, ..
                } if count > 0 => {
                    return Err("HPROF primitive array data is unavailable (NODATA record)".into());
                }
                ObjectKind::PrimitiveArray { ty, .. } => ty,
                ObjectKind::Instance => unreachable!(),
            };
            let element_width = width(ty, heap.id_size)?;
            let skip = checked_product(start, element_width, reader.pos)?;
            // NODATA arrays have no payload to seek into, including empty pages.
            if count == 0 {
                return Ok(result);
            }
            reader.skip(skip)?;
            reader.require(checked_product(count, element_width, reader.pos)?)?;
            for index in start..start + count {
                result.push(Value {
                    name: format!("[{index}]"),
                    ty,
                    bits: reader.value(ty)?,
                });
            }
        }
    }
    Ok(result)
}

fn compact_heaps(heap: &mut Heap) {
    let mut used = [false; 4];
    for class in &heap.classes {
        if class.heap != usize::MAX {
            used[class.heap] = true;
        }
    }
    for object in &heap.objects {
        used[object.heap] = true;
    }
    let mut mapping = [0; 4];
    let mut names = Vec::new();
    for (index, &name) in HEAP_NAMES.iter().enumerate() {
        if used[index] {
            mapping[index] = names.len();
            names.push(name.to_string());
        }
    }
    for class in &mut heap.classes {
        if class.heap != usize::MAX {
            class.heap = mapping[class.heap];
        }
    }
    for object in &mut heap.objects {
        object.heap = mapping[object.heap];
    }
    heap.heaps = names;
}

fn validate_instance_lengths(heap: &Heap) -> Result<(), String> {
    let mut boundaries: HashMap<u64, Vec<u64>> = HashMap::new();
    for object in &heap.objects {
        if object.kind != ObjectKind::Instance {
            continue;
        }
        if let std::collections::hash_map::Entry::Vacant(entry) = boundaries.entry(object.class_id)
        {
            let mut offsets = vec![0_u64];
            for field in heap.fields_for(object.class_id) {
                let next = offsets
                    .last()
                    .unwrap()
                    .checked_add(width(field.ty, heap.id_size)?)
                    .ok_or("HPROF field layout size overflow")?;
                offsets.push(next);
            }
            entry.insert(offsets);
        }
        let offsets = &boundaries[&object.class_id];
        let count = u64::from(object.count);
        // Legacy allows an empty/short field list at a field boundary and trailing bytes,
        // but never a partially stored typed value.
        if count < *offsets.last().unwrap() && offsets.binary_search(&count).is_err() {
            return Err(format!(
                "Truncated HPROF instance field at offset {}",
                object.payload_offset
            ));
        }
    }
    Ok(())
}

#[derive(Clone, Copy)]
struct StringLocation {
    offset: u64,
    length: u64,
}

struct RawClass {
    id: u64,
    fields: Vec<(u64, u8)>,
    statics: Vec<(u64, u8, u64)>,
    resolved: Class,
}

struct State {
    strings: HashMap<u64, StringLocation>,
    names: HashMap<u64, u64>,
    classes: Vec<RawClass>,
    class_by_id: HashMap<u64, usize>,
    objects: Vec<Object>,
    object_by_id: HashMap<u64, usize>,
    roots: Vec<Root>,
    root_set: HashSet<(u64, &'static str)>,
    warnings: Vec<Warning>,
    current_heap: usize,
}

impl State {
    fn new() -> Self {
        Self {
            strings: HashMap::new(),
            names: HashMap::new(),
            classes: Vec::new(),
            class_by_id: HashMap::new(),
            objects: Vec::new(),
            object_by_id: HashMap::new(),
            roots: Vec::new(),
            root_set: HashSet::new(),
            warnings: Vec::new(),
            current_heap: 3,
        }
    }

    fn class_index(&mut self, id: u64) -> usize {
        if let Some(&index) = self.class_by_id.get(&id) {
            return index;
        }
        let index = self.classes.len();
        self.class_by_id.insert(id, index);
        self.classes.push(RawClass {
            id,
            fields: Vec::new(),
            statics: Vec::new(),
            resolved: Class {
                id,
                name: "<unknown>".into(),
                super_id: 0,
                instance_size: 0,
                loader_id: 0,
                fields: Vec::new(),
                static_fields: Vec::new(),
                heap: usize::MAX,
            },
        });
        index
    }

    fn add_object(&mut self, object: Object) -> Result<(), String> {
        if self
            .object_by_id
            .insert(object.id, self.objects.len())
            .is_some()
        {
            return Err(format!("Duplicate HPROF object id 0x{:x}", object.id));
        }
        self.objects.push(object);
        Ok(())
    }

    fn resolve(&mut self, reader: &mut Reader<'_>) -> Result<(), String> {
        let mut cache: HashMap<u64, String> = HashMap::new();
        for class in &mut self.classes {
            if let Some(location) = self
                .names
                .get(&class.id)
                .and_then(|id| self.strings.get(id))
            {
                // HprofParser preserves class-name strings verbatim, including descriptors.
                class.resolved.name = reader.string_at(*location)?;
            }
            for &(id, ty) in &class.fields {
                let name = resolve_field_name(reader, &self.strings, &mut cache, id)?;
                class.resolved.fields.push(Field { name, ty });
            }
            for &(id, ty, bits) in &class.statics {
                let name = resolve_field_name(reader, &self.strings, &mut cache, id)?;
                class.resolved.static_fields.push(Value {
                    name: format!("static {name}"),
                    ty,
                    bits,
                });
            }
        }
        Ok(())
    }
}

fn resolve_field_name(
    reader: &mut Reader<'_>,
    strings: &HashMap<u64, StringLocation>,
    cache: &mut HashMap<u64, String>,
    id: u64,
) -> Result<String, String> {
    if let Some(name) = cache.get(&id) {
        return Ok(name.clone());
    }
    let name = match strings.get(&id) {
        Some(location) => reader.string_at(*location)?,
        None => format!("<field-{}>", id as i64),
    };
    cache.insert(id, name.clone());
    Ok(name)
}

fn parse_record(
    tag: u8,
    reader: &mut Reader<'_>,
    state: &mut State,
    offset: u64,
) -> Result<(), String> {
    match tag {
        0x01 => {
            let id = reader.id()?;
            state.strings.insert(
                id,
                StringLocation {
                    offset: reader.pos,
                    length: reader.end - reader.pos,
                },
            );
        }
        0x02 => {
            reader.skip(4)?;
            let id = reader.id()?;
            reader.skip(4)?;
            let name_id = reader.id()?;
            state.names.insert(id, name_id);
            state.class_index(id);
        }
        0x0c | 0x1c => parse_heap(reader, state)?,
        // Standard non-heap records carry no object data required by this index.
        0x03..=0x07 | 0x0a | 0x0b | 0x0d | 0x0e | 0x2c => {}
        _ => state.warnings.push(Warning {
            message: format!("Unknown top-level HPROF record tag 0x{tag:x} skipped"),
            offset: Some(offset),
        }),
    }
    Ok(())
}

fn parse_heap(reader: &mut Reader<'_>, state: &mut State) -> Result<(), String> {
    let segment_start = reader.pos;
    while reader.pos < reader.end {
        let offset = reader.pos;
        let tag = reader.u8()?;
        if let Some((kind, extra_words, extra_ids)) = root_kind(tag) {
            let id = reader.id()?;
            reader.skip(extra_words * 4 + extra_ids * u64::from(reader.id_size))?;
            if id != 0 && state.root_set.insert((id, kind)) {
                state.roots.push(Root {
                    id,
                    kind: kind.into(),
                });
            }
            continue;
        }
        match tag {
            0xfe => {
                reader.skip(4)?;
                let name_id = reader.id()?;
                let name = state
                    .strings
                    .get(&name_id)
                    .map(|&loc| reader.string_at(loc))
                    .transpose()?;
                state.current_heap = normalize_heap(name.as_deref());
            }
            0x20 => parse_class(reader, state)?,
            0x21 => {
                let id = reader.id()?;
                reader.skip(4)?;
                let class_id = reader.id()?;
                let count = reader.count("instance byte count")?;
                let payload_offset = reader.pos;
                reader.skip(u64::from(count))?;
                state.add_object(Object {
                    id,
                    class_id,
                    kind: ObjectKind::Instance,
                    payload_offset,
                    count,
                    heap: state.current_heap,
                    shallow_size: u64::from(count),
                })?;
            }
            0x22 => {
                let id = reader.id()?;
                reader.skip(4)?;
                let count = reader.count("object array element count")?;
                let class_id = reader.id()?;
                let payload_offset = reader.pos;
                let size = checked_product(count, u64::from(reader.id_size), payload_offset)?;
                reader.skip(size)?;
                state.add_object(Object {
                    id,
                    class_id,
                    kind: ObjectKind::ObjectArray,
                    payload_offset,
                    count,
                    heap: state.current_heap,
                    shallow_size: size
                        .checked_add(if reader.id_size == 4 { 12 } else { 16 })
                        .ok_or("HPROF object array size overflow")?,
                })?;
            }
            0x23 | 0xc3 => {
                let id = reader.id()?;
                reader.skip(4)?;
                let count = reader.count("primitive array element count")?;
                let ty = reader.u8()?;
                if ty == 2 {
                    return Err(format!(
                        "Invalid HPROF primitive array type {ty} at offset {}",
                        reader.pos - 1
                    ));
                }
                let size = checked_product(count, width(ty, reader.id_size)?, reader.pos)?;
                let payload_offset = reader.pos;
                let has_data = tag == 0x23;
                if has_data {
                    reader.skip(size)?;
                }
                let header = if reader.id_size == 8 || ty == 7 || ty == 11 {
                    16
                } else {
                    12
                };
                state.add_object(Object {
                    id,
                    class_id: 0,
                    kind: ObjectKind::PrimitiveArray { ty, has_data },
                    payload_offset,
                    count,
                    heap: state.current_heap,
                    shallow_size: size
                        .checked_add(header)
                        .ok_or("HPROF primitive array size overflow")?,
                })?;
            }
            _ => {
                state.warnings.push(Warning {
                    message: format!("Unknown heap dump sub-record tag 0x{tag:x} at segment offset {}; stopped parsing current segment", offset - segment_start),
                    offset: Some(offset),
                });
                break;
            }
        }
    }
    Ok(())
}

fn parse_class(reader: &mut Reader<'_>, state: &mut State) -> Result<(), String> {
    let id = reader.id()?;
    reader.skip(4)?;
    let super_id = reader.id()?;
    let loader_id = reader.id()?;
    reader.skip(4 * u64::from(reader.id_size))?;
    let instance_size = u64::from(reader.count("class instance size")?);
    let constant_count = reader.u16()?;
    for _ in 0..constant_count {
        reader.skip(2)?;
        let ty = reader.u8()?;
        reader.skip(width(ty, reader.id_size)?)?;
    }
    let static_count = reader.u16()?;
    let mut statics = Vec::new();
    for _ in 0..static_count {
        let name_id = reader.id()?;
        let ty = reader.u8()?;
        let bits = reader.value(ty)?;
        statics.push((name_id, ty, bits));
    }
    let field_count = reader.u16()?;
    let mut fields = Vec::new();
    for _ in 0..field_count {
        let name_id = reader.id()?;
        let ty = reader.u8()?;
        width(ty, reader.id_size)?;
        fields.push((name_id, ty));
    }
    let index = state.class_index(id);
    let class = &mut state.classes[index];
    class.fields = fields;
    class.statics = statics;
    class.resolved.super_id = super_id;
    class.resolved.loader_id = loader_id;
    class.resolved.instance_size = instance_size;
    class.resolved.heap = state.current_heap;
    Ok(())
}

fn normalize_heap(name: Option<&str>) -> usize {
    let name = name.unwrap_or("").to_lowercase();
    if name.contains("image") {
        1
    } else if name.contains("zygote") {
        2
    } else if name.contains("app") {
        0
    } else {
        3
    }
}

fn root_kind(tag: u8) -> Option<(&'static str, u64, u64)> {
    Some(match tag {
        0x01 => ("JNI_GLOBAL", 0, 1),
        0x02 => ("JNI_LOCAL", 2, 0),
        0x03 => ("JAVA_FRAME", 2, 0),
        0x04 => ("NATIVE_STACK", 1, 0),
        0x05 => ("STICKY_CLASS", 0, 0),
        0x06 => ("THREAD_BLOCK", 1, 0),
        0x07 => ("MONITOR_USED", 0, 0),
        0x08 => ("THREAD_OBJECT", 2, 0),
        0x89 => ("INTERNED_STRING", 0, 0),
        0x8a => ("FINALIZING", 0, 0),
        0x8b => ("DEBUGGER", 0, 0),
        0x8c => ("REFERENCE_CLEANUP", 0, 0),
        0x8d => ("VM_INTERNAL", 0, 0),
        0x8e => ("JNI_MONITOR", 2, 0),
        0x90 => ("UNREACHABLE", 0, 0),
        0xff => ("UNKNOWN", 0, 0),
        _ => return None,
    })
}

fn width(ty: u8, id_size: u8) -> Result<u64, String> {
    match ty {
        2 => Ok(u64::from(id_size)),
        4 | 8 => Ok(1),
        5 | 9 => Ok(2),
        6 | 10 => Ok(4),
        7 | 11 => Ok(8),
        _ => Err(format!("Unknown HPROF value type {ty}")),
    }
}

fn checked_product(count: u32, width: u64, offset: u64) -> Result<u64, String> {
    u64::from(count)
        .checked_mul(width)
        .ok_or_else(|| format!("HPROF payload size overflow at offset {offset}"))
}

struct Reader<'a> {
    file: &'a mut BufReader<File>,
    pos: u64,
    end: u64,
    id_size: u8,
}

impl<'a> Reader<'a> {
    fn new(file: &'a mut BufReader<File>, pos: u64, end: u64, id_size: u8) -> Result<Self, String> {
        if pos > end {
            return Err("HPROF payload offset exceeds source size".into());
        }
        file.seek(SeekFrom::Start(pos))
            .map_err(|e| format!("Cannot seek HPROF: {e}"))?;
        Ok(Self {
            file,
            pos,
            end,
            id_size,
        })
    }

    fn header(&mut self) -> Result<String, String> {
        let mut bytes = Vec::new();
        for _ in 0..=MAX_HEADER_BYTES {
            let byte = self.u8()?;
            if byte == 0 {
                return Ok(String::from_utf8_lossy(&bytes).into_owned());
            }
            bytes.push(byte);
        }
        Err("HPROF header is too large or missing NUL terminator".into())
    }

    fn require(&self, count: u64) -> Result<(), String> {
        self.checked_end(count).map(|_| ())
    }

    fn checked_end(&self, count: u64) -> Result<u64, String> {
        let end = self
            .pos
            .checked_add(count)
            .ok_or_else(|| format!("HPROF offset overflow at {}", self.pos))?;
        if end > self.end {
            return Err(format!(
                "Truncated HPROF at offset {}: need {count} bytes, have {}",
                self.pos,
                self.end - self.pos
            ));
        }
        Ok(end)
    }

    fn seek(&mut self, position: u64) -> Result<(), String> {
        if position > self.end {
            return Err(format!("Truncated HPROF at offset {position}"));
        }
        let delta = i64::try_from(i128::from(position) - i128::from(self.pos))
            .map_err(|_| "HPROF seek distance overflow")?;
        // seek_relative preserves the fixed-size read buffer for nearby field reads.
        self.file
            .seek_relative(delta)
            .map_err(|e| format!("Cannot seek HPROF at {position}: {e}"))?;
        self.pos = position;
        Ok(())
    }

    fn skip(&mut self, count: u64) -> Result<(), String> {
        self.seek(self.checked_end(count)?)
    }

    fn bytes<const N: usize>(&mut self) -> Result<[u8; N], String> {
        self.require(N as u64)?;
        let mut bytes = [0; N];
        self.file
            .read_exact(&mut bytes)
            .map_err(|e| format!("Truncated HPROF at offset {}: {e}", self.pos))?;
        self.pos += N as u64;
        Ok(bytes)
    }

    fn u8(&mut self) -> Result<u8, String> {
        Ok(self.bytes::<1>()?[0])
    }
    fn u16(&mut self) -> Result<u16, String> {
        Ok(u16::from_be_bytes(self.bytes()?))
    }
    fn u32(&mut self) -> Result<u32, String> {
        Ok(u32::from_be_bytes(self.bytes()?))
    }
    fn u64(&mut self) -> Result<u64, String> {
        Ok(u64::from_be_bytes(self.bytes()?))
    }

    fn count(&mut self, label: &str) -> Result<u32, String> {
        let offset = self.pos;
        let count = self.u32()?;
        if count > i32::MAX as u32 {
            return Err(format!("Negative HPROF {label} at offset {offset}"));
        }
        Ok(count)
    }

    fn id(&mut self) -> Result<u64, String> {
        match self.id_size {
            4 => self.u32().map(u64::from),
            8 => self.u64(),
            size => Err(format!("Cannot read HPROF id with invalid size {size}")),
        }
    }

    fn value(&mut self, ty: u8) -> Result<u64, String> {
        match ty {
            2 => self.id(),
            4 | 8 => self.u8().map(u64::from),
            5 | 9 => self.u16().map(u64::from),
            6 | 10 => self.u32().map(|value| value as i32 as i64 as u64),
            7 | 11 => self.u64(),
            _ => Err(format!(
                "Unknown HPROF value type {ty} at offset {}",
                self.pos
            )),
        }
    }

    fn string_at(&mut self, location: StringLocation) -> Result<String, String> {
        if location.length > MAX_NAME_BYTES {
            return Err(format!(
                "HPROF metadata string at offset {} exceeds {MAX_NAME_BYTES} bytes",
                location.offset
            ));
        }
        let end = location
            .offset
            .checked_add(location.length)
            .ok_or("HPROF string offset overflow")?;
        let mut bytes = Vec::new();
        let length =
            usize::try_from(location.length).map_err(|_| "HPROF string length overflow")?;
        bytes
            .try_reserve_exact(length)
            .map_err(|e| format!("Cannot allocate HPROF metadata string: {e}"))?;
        bytes.resize(length, 0);
        // String locations were bounded when indexing their top-level records.
        let source = Reader::new(self.file, location.offset, end, self.id_size)?;
        source
            .file
            .read_exact(&mut bytes)
            .map_err(|e| format!("Truncated HPROF string at offset {}: {e}", location.offset))?;
        self.file
            .seek(SeekFrom::Start(self.pos))
            .map_err(|e| format!("Cannot restore HPROF reader: {e}"))?;
        Ok(String::from_utf8_lossy(&bytes).into_owned())
    }
}

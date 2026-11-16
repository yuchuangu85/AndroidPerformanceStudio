use std::cell::RefCell;
use std::collections::{HashMap, HashSet};
use std::fs::{File, Metadata};
use std::io::BufReader;
use std::path::{Path, PathBuf};

#[derive(Debug)]
pub struct Heap {
    pub path: PathBuf,
    pub format: String,
    pub id_size: u8,
    pub timestamp: u64,
    pub file_size: u64,
    pub classes: Vec<Class>,
    pub objects: Vec<Object>,
    pub roots: Vec<Root>,
    pub heaps: Vec<String>,
    pub warnings: Vec<Warning>,
    pub class_by_id: HashMap<u64, usize>,
    pub object_by_id: HashMap<u64, usize>,
    pub(crate) source: Source,
}

#[derive(Clone, Debug)]
pub struct Class {
    pub id: u64,
    pub name: String,
    pub super_id: u64,
    pub instance_size: u64,
    pub loader_id: u64,
    pub fields: Vec<Field>,
    pub static_fields: Vec<Value>,
    /// Index into Heap.heaps, or usize::MAX when no CLASS_DUMP assigned a heap.
    pub heap: usize,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Object {
    pub id: u64,
    pub class_id: u64,
    pub kind: ObjectKind,
    pub payload_offset: u64,
    /// Raw field bytes for instances; element count for arrays.
    pub count: u32,
    pub heap: usize,
    pub shallow_size: u64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum ObjectKind {
    Instance,
    ObjectArray,
    PrimitiveArray { ty: u8, has_data: bool },
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Field {
    pub name: String,
    pub ty: u8,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Value {
    pub name: String,
    pub ty: u8,
    pub bits: u64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Root {
    pub id: u64,
    pub kind: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Warning {
    pub message: String,
    pub offset: Option<u64>,
}

impl Heap {
    pub fn open(path: &Path) -> Result<Heap, String> {
        crate::parser::open(path)
    }

    #[allow(clippy::too_many_arguments)]
    pub(crate) fn from_cached(
        path: PathBuf,
        source: Source,
        format: String,
        id_size: u8,
        timestamp: u64,
        file_size: u64,
        classes: Vec<Class>,
        objects: Vec<Object>,
        roots: Vec<Root>,
        heaps: Vec<String>,
        warnings: Vec<Warning>,
    ) -> Result<Heap, String> {
        let class_by_id = classes
            .iter()
            .enumerate()
            .map(|(index, class)| (class.id, index))
            .collect::<HashMap<_, _>>();
        let object_by_id = objects
            .iter()
            .enumerate()
            .map(|(index, object)| (object.id, index))
            .collect::<HashMap<_, _>>();
        if class_by_id.len() != classes.len() || object_by_id.len() != objects.len() {
            return Err("HPROF cache contains duplicate object identity".into());
        }
        let heap = Heap {
            path,
            format,
            id_size,
            timestamp,
            file_size,
            classes,
            objects,
            roots,
            heaps,
            warnings,
            class_by_id,
            object_by_id,
            source,
        };
        crate::parser::validate_cached(&heap)?;
        Ok(heap)
    }

    /// Returns a bounded page. Missing primitive-array data is an explicit error.
    pub fn values(&self, object: &Object, start: u32, limit: u32) -> Result<Vec<Value>, String> {
        crate::parser::values(self, object, start, limit)
    }

    /// HPROF stores subclass fields before superclass fields.
    pub fn fields_for(&self, class_id: u64) -> Vec<Field> {
        let mut fields = Vec::new();
        let mut visited = HashSet::new();
        let mut current = class_id;
        while current != 0 && visited.insert(current) {
            let Some(class) = self.class_by_id.get(&current).map(|&i| &self.classes[i]) else {
                break;
            };
            fields.extend(class.fields.iter().cloned());
            current = class.super_id;
        }
        fields
    }

    pub fn check_source(&self) -> Result<(), String> {
        self.source.check(&self.path)
    }
}

#[derive(Debug)]
pub(crate) struct Source {
    pub(crate) reader: RefCell<BufReader<File>>,
    identity: Metadata,
}

impl Source {
    pub(crate) fn open(path: &Path) -> Result<Self, String> {
        let file = File::open(path).map_err(|e| format!("Cannot open HPROF source: {e}"))?;
        let identity = file
            .metadata()
            .map_err(|e| format!("Cannot stat HPROF source: {e}"))?;
        if !identity.is_file() {
            return Err("HPROF source is not a regular file".into());
        }
        let source = Self {
            reader: RefCell::new(BufReader::with_capacity(64 * 1024, file)),
            identity,
        };
        source.check(path)?;
        Ok(source)
    }

    pub(crate) fn len(&self) -> u64 {
        self.identity.len()
    }

    pub(crate) fn check(&self, path: &Path) -> Result<(), String> {
        let current = std::fs::metadata(path)
            .map_err(|e| format!("HPROF source changed or is unavailable: {e}"))?;
        let reader = self
            .reader
            .try_borrow()
            .map_err(|_| "HPROF source reader is busy")?;
        let opened = reader
            .get_ref()
            .metadata()
            .map_err(|e| format!("Cannot stat open HPROF source: {e}"))?;
        if !same_metadata(&self.identity, &current) || !same_metadata(&self.identity, &opened) {
            return Err("HPROF source changed since opening".into());
        }
        Ok(())
    }
}

fn same_metadata(expected: &Metadata, actual: &Metadata) -> bool {
    if !actual.is_file()
        || actual.len() != expected.len()
        || actual.modified().ok() != expected.modified().ok()
        || actual.created().ok() != expected.created().ok()
    {
        return false;
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        if actual.dev() != expected.dev()
            || actual.ino() != expected.ino()
            || actual.ctime() != expected.ctime()
            || actual.ctime_nsec() != expected.ctime_nsec()
        {
            return false;
        }
    }
    true
}

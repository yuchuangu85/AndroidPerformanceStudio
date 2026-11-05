//! Ephemeral strong edges, independent of the persistent parser index.
use std::cell::RefCell;
use std::fs::File;
use std::io::{BufWriter, Read, Seek, SeekFrom, Write};
use std::path::Path;

const EDGE_BYTES: usize = 8;
pub const BLOCK_BYTES: usize = 64 * 1024;

#[derive(Clone, Copy, Default)]
pub enum GraphStorage<'a> {
    #[default]
    Memory,
    Disk(&'a Path),
}

pub struct EdgeStore {
    data: Data,
    len: usize,
}

enum Data {
    Memory(Vec<(u32, u32)>),
    Disk(RefCell<Disk>),
}

struct Disk {
    file: File,
    block: usize,
    cache: Vec<u8>,
}

pub(crate) enum EdgeWriter {
    Memory(Vec<(u32, u32)>),
    Disk { writer: BufWriter<File>, len: usize },
}

impl EdgeWriter {
    pub(crate) fn new(storage: GraphStorage<'_>) -> Result<Self, String> {
        match storage {
            GraphStorage::Memory => Ok(Self::Memory(Vec::new())),
            GraphStorage::Disk(directory) => Ok(Self::Disk {
                writer: BufWriter::with_capacity(BLOCK_BYTES, temporary_file(directory)?),
                len: 0,
            }),
        }
    }

    pub(crate) fn len(&self) -> usize {
        match self {
            Self::Memory(edges) => edges.len(),
            Self::Disk { len, .. } => *len,
        }
    }

    pub(crate) fn push(&mut self, target: u32, slot: u32) -> Result<(), String> {
        if self.len() >= (u32::MAX - 1) as usize {
            return Err("heap exceeds compact edge index capacity".into());
        }
        match self {
            Self::Memory(edges) => {
                edges
                    .try_reserve(1)
                    .map_err(|_| "cannot allocate graph edges")?;
                edges.push((target, slot));
            }
            Self::Disk { writer, len } => {
                let mut bytes = [0; EDGE_BYTES];
                bytes[..4].copy_from_slice(&target.to_le_bytes());
                bytes[4..].copy_from_slice(&slot.to_le_bytes());
                writer.write_all(&bytes).map_err(io_error)?;
                *len += 1;
            }
        }
        Ok(())
    }

    pub(crate) fn finish(self) -> Result<EdgeStore, String> {
        let len = self.len();
        let data = match self {
            Self::Memory(edges) => Data::Memory(edges),
            Self::Disk { writer, .. } => {
                let file = writer.into_inner().map_err(|e| io_error(e.into_error()))?;
                let mut cache = Vec::new();
                cache
                    .try_reserve_exact(BLOCK_BYTES)
                    .map_err(|_| "cannot allocate graph block cache")?;
                cache.resize(BLOCK_BYTES, 0);
                Data::Disk(RefCell::new(Disk {
                    file,
                    block: usize::MAX,
                    cache,
                }))
            }
        };
        let store = EdgeStore { data, len };
        store.check()?;
        Ok(store)
    }
}

fn io_error(error: std::io::Error) -> String {
    format!("HPROF graph edge I/O error: {error}")
}

impl EdgeStore {
    pub fn from_edges(edges: Vec<(u32, u32)>, storage: GraphStorage<'_>) -> Result<Self, String> {
        let mut writer = EdgeWriter::new(storage)?;
        for (target, slot) in edges {
            writer.push(target, slot)?;
        }
        writer.finish()
    }

    pub fn get(&self, edge: usize) -> Result<(u32, u32), String> {
        if edge >= self.len {
            return Err("HPROF graph edge index out of bounds".into());
        }
        match &self.data {
            Data::Memory(edges) => edges
                .get(edge)
                .copied()
                .ok_or_else(|| "missing graph edge".into()),
            Data::Disk(disk) => {
                let mut disk = disk
                    .try_borrow_mut()
                    .map_err(|_| "graph edge reader is busy")?;
                let byte = edge as u64 * EDGE_BYTES as u64;
                let block = (byte / BLOCK_BYTES as u64) as usize;
                if disk.block != block {
                    disk.block = usize::MAX;
                    check_length(&disk.file, self.len)?;
                    let start = block as u64 * BLOCK_BYTES as u64;
                    let length = (self.len as u64 * EDGE_BYTES as u64 - start)
                        .min(BLOCK_BYTES as u64) as usize;
                    disk.file.seek(SeekFrom::Start(start)).map_err(io_error)?;
                    let Disk { file, cache, .. } = &mut *disk;
                    file.read_exact(&mut cache[..length]).map_err(io_error)?;
                    disk.block = block;
                }
                let offset = (byte % BLOCK_BYTES as u64) as usize;
                let bytes = &disk.cache[offset..offset + EDGE_BYTES];
                Ok((
                    u32::from_le_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]),
                    u32::from_le_bytes([bytes[4], bytes[5], bytes[6], bytes[7]]),
                ))
            }
        }
    }

    /// Check at operation boundaries as well as block misses, including cached pages.
    pub fn check(&self) -> Result<(), String> {
        if let Data::Disk(disk) = &self.data {
            let disk = disk.try_borrow().map_err(|_| "graph edge reader is busy")?;
            check_length(&disk.file, self.len)?;
        }
        Ok(())
    }
}

fn check_length(file: &File, len: usize) -> Result<(), String> {
    if file.metadata().map_err(io_error)?.len() != len as u64 * EDGE_BYTES as u64 {
        return Err("HPROF graph edge file length changed or is truncated".into());
    }
    Ok(())
}

#[cfg(unix)]
fn temporary_file(directory: &Path) -> Result<File, String> {
    use std::fs::OpenOptions;
    use std::os::unix::fs::{DirBuilderExt, OpenOptionsExt, PermissionsExt};
    use std::sync::atomic::{AtomicU64, Ordering};
    static NEXT: AtomicU64 = AtomicU64::new(0);
    match std::fs::symlink_metadata(directory) {
        Ok(metadata) if !metadata.is_dir() || metadata.file_type().is_symlink() => {
            return Err("HPROF graphDir must be a real directory".into());
        }
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => {
            std::fs::DirBuilder::new()
                .mode(0o700)
                .create(directory)
                .map_err(io_error)?;
        }
        Err(e) => return Err(io_error(e)),
        _ => {}
    }
    // The host owns the directory. Never chmod it, sweep it, or delete other files.
    for _ in 0..64 {
        let path = directory.join(format!(
            ".aps-edges-{}-{}",
            std::process::id(),
            NEXT.fetch_add(1, Ordering::Relaxed)
        ));
        let file = match OpenOptions::new()
            .read(true)
            .write(true)
            .create_new(true)
            .mode(0o600)
            .open(&path)
        {
            Ok(file) => file,
            Err(e) if e.kind() == std::io::ErrorKind::AlreadyExists => continue,
            Err(e) => return Err(io_error(e)),
        };
        // Unlink before writing heap data, so even SIGKILL cannot leave edges behind.
        std::fs::remove_file(&path).map_err(io_error)?;
        if file.metadata().map_err(io_error)?.permissions().mode() & 0o077 != 0 {
            return Err("HPROF graph edge file is not owner-only".into());
        }
        return Ok(file);
    }
    Err("cannot create unique HPROF graph edge file".into())
}

#[cfg(not(unix))]
fn temporary_file(_directory: &Path) -> Result<File, String> {
    Err(
        "disk graph storage requires Unix owner-only anonymous temporary files on this platform"
            .into(),
    )
}

#[cfg(all(test, unix))]
mod tests {
    use super::*;

    #[test]
    fn bounded_blocks_random_access_and_truncation_are_fallible() {
        let edges: Vec<_> = (0..40_000).map(|i| (i, i * 2)).collect();
        let store = EdgeStore::from_edges(edges.clone(), GraphStorage::Disk(&std::env::temp_dir()))
            .unwrap();
        for index in [0, 8191, 8192, 39_999, 1, 16_384, 8192] {
            assert_eq!(store.get(index).unwrap(), edges[index]);
        }
        assert!(store.get(edges.len()).is_err());
        let Data::Disk(disk) = &store.data else {
            panic!("expected disk")
        };
        assert_eq!(disk.borrow().cache.len(), BLOCK_BYTES);
        let file = disk.borrow().file.try_clone().unwrap();
        store.get(0).unwrap();
        file.set_len(3).unwrap();
        assert!(store.check().unwrap_err().contains("truncated"));
        assert!(store.get(8192).unwrap_err().contains("truncated"));
    }

    #[test]
    fn owner_only_unlinked_file_survives_only_by_handle() {
        use std::os::unix::fs::{MetadataExt, PermissionsExt};
        let file = temporary_file(&std::env::temp_dir()).unwrap();
        let metadata = file.metadata().unwrap();
        assert_eq!(metadata.permissions().mode() & 0o777, 0o600);
        assert_eq!(metadata.nlink(), 0);
    }

    #[test]
    fn buffered_write_failure_and_corrupt_targets_are_errors_not_panics() {
        let mut writer = EdgeWriter::Disk {
            writer: BufWriter::with_capacity(BLOCK_BYTES, File::open("/dev/null").unwrap()),
            len: 0,
        };
        writer.push(1, 0).unwrap();
        assert!(writer.finish().err().unwrap().contains("I/O error"));

        let store =
            EdgeStore::from_edges(vec![(1, 0)], GraphStorage::Disk(&std::env::temp_dir())).unwrap();
        let Data::Disk(disk) = &store.data else {
            panic!("expected disk")
        };
        let mut file = disk.borrow().file.try_clone().unwrap();
        file.seek(SeekFrom::Start(0)).unwrap();
        file.write_all(&u32::MAX.to_le_bytes()).unwrap();
        let graph = crate::analysis::Graph {
            offsets: vec![0, 1, 1],
            edges: store,
            layouts: vec![],
        };
        assert!(graph.edge(0).unwrap_err().contains("target out of bounds"));
        let walk = crate::analysis::Traversal {
            depth: vec![0, 1],
            predecessor: vec![u32::MAX, 0],
            slot: vec![0, 0],
            roots: std::collections::HashMap::from([(0, 0)]),
        };
        assert!(crate::analysis::dominators(&graph, &walk, &[1, 1])
            .unwrap_err()
            .contains("target out of bounds"));
    }
}

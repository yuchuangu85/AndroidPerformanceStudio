//! Versioned, source-bound persistence for the compact HPROF parser index.
//!
//! The cache stores parser metadata and source offsets only. It never stores a
//! complete object graph or query result and is safe to discard at any time.

use crate::model::{Class, Field, Heap, Object, ObjectKind, Root, Source, Value, Warning};
use std::fs::{self, OpenOptions};
use std::io::{BufWriter, Read, Seek, SeekFrom, Write};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicU64, Ordering};

const MAGIC: &[u8; 8] = b"APSHIDX\0";
const VERSION: u32 = 2;
const MAX_STRING: u32 = 16 * 1024 * 1024;
const SHA256_BYTES: usize = 32;
const IO_BUFFER_BYTES: usize = 64 * 1024;
static NEXT: AtomicU64 = AtomicU64::new(0);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Status {
    Hit,
    Miss,
    Disabled,
}

impl Status {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Hit => "hit",
            Self::Miss => "miss",
            Self::Disabled => "disabled",
        }
    }
}

/// Opens a source using a discardable cache. Cache failures never prevent the
/// source from being parsed, but a source digest/identity change is explicit.
pub fn open_or_parse(
    path: &Path,
    digest: &str,
    directory: &Path,
) -> Result<(Heap, Status), String> {
    validate_digest(digest)?;
    let digest = digest.to_ascii_lowercase();
    let source = Source::open(path)?;
    if digest_source(&source, path)? != digest {
        return Err("HPROF source digest does not match the requested sourceDigest".into());
    }

    let cache_path = if prepare_directory(directory).is_ok() {
        cache_path(directory, &digest).ok()
    } else {
        None
    };
    if let Some(cache_path) = cache_path.as_deref() {
        if let Ok(Some(heap)) = load(cache_path, path, &digest, source.len()) {
            source.check(path)?;
            heap.check_source()?;
            return Ok((heap, Status::Hit));
        }
    }

    let heap = Heap::open(path)?;
    source.check(path)?;
    if let Some(cache_path) = cache_path {
        // The index is an optimization. A full or partial cache write failure
        // must not turn a valid parsed HPROF into an open failure.
        let _ = write(&cache_path, &heap, &digest);
    }
    source.check(path)?;
    heap.check_source()?;
    Ok((heap, Status::Miss))
}

pub fn evict(directory: &Path, digest: &str) -> Result<(), String> {
    validate_digest(digest)?;
    if !directory.exists() && !is_symlink(directory)? {
        return Ok(());
    }
    prepare_directory(directory)?;
    let path = cache_path(directory, &digest.to_ascii_lowercase())?;
    if is_symlink(&path)? {
        return Err("Refusing to evict a symlinked HPROF cache entry".into());
    }
    match fs::remove_file(path) {
        Ok(()) => {}
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {}
        Err(error) => return Err(format!("Cannot evict HPROF cache: {error}")),
    }
    Ok(())
}

pub(crate) fn validate_digest(digest: &str) -> Result<(), String> {
    if digest.len() != 64 || !digest.bytes().all(|b| b.is_ascii_hexdigit()) {
        return Err("sourceDigest must be a 64-character SHA-256 hex digest".into());
    }
    Ok(())
}

fn cache_path(directory: &Path, digest: &str) -> Result<PathBuf, String> {
    validate_digest(digest)?;
    Ok(directory.join(format!("{digest}.idx")))
}

fn prepare_directory(directory: &Path) -> Result<(), String> {
    match fs::symlink_metadata(directory) {
        Ok(metadata) if metadata.file_type().is_symlink() => {
            return Err("Refusing a symlinked HPROF cache directory".into())
        }
        Ok(metadata) if !metadata.is_dir() => {
            return Err("HPROF cache path is not a directory".into())
        }
        Ok(_) => {}
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => {
            let mut builder = fs::DirBuilder::new();
            builder.recursive(true);
            #[cfg(unix)]
            {
                use std::os::unix::fs::DirBuilderExt;
                builder.mode(0o700);
            }
            builder
                .create(directory)
                .map_err(|e| format!("Cannot create HPROF cache directory: {e}"))?;
        }
        Err(error) => return Err(format!("Cannot inspect HPROF cache directory: {error}")),
    }
    if is_symlink(directory)? {
        return Err("Refusing a symlinked HPROF cache directory".into());
    }
    restrict_directory_permissions(directory)?;
    Ok(())
}

fn is_symlink(path: &Path) -> Result<bool, String> {
    match fs::symlink_metadata(path) {
        Ok(metadata) => Ok(metadata.file_type().is_symlink()),
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => Ok(false),
        Err(error) => Err(format!("Cannot inspect HPROF cache path: {error}")),
    }
}

#[cfg(unix)]
fn restrict_directory_permissions(path: &Path) -> Result<(), String> {
    use std::os::unix::fs::{MetadataExt, PermissionsExt};
    let metadata = fs::symlink_metadata(path)
        .map_err(|e| format!("Cannot inspect HPROF cache directory: {e}"))?;
    if metadata.file_type().is_symlink() || !metadata.is_dir() {
        return Err("HPROF cache directory is not a real directory".into());
    }
    fs::set_permissions(path, fs::Permissions::from_mode(0o700))
        .map_err(|e| format!("Cannot restrict HPROF cache directory permissions: {e}"))?;
    if metadata.mode() & 0o077 != 0 {
        // The chmod above is intentional; re-read so a platform/filesystem
        // refusing it fails closed instead of publishing a broad directory.
        let mode = fs::symlink_metadata(path)
            .map_err(|e| format!("Cannot verify HPROF cache directory permissions: {e}"))?
            .mode();
        if mode & 0o077 != 0 {
            return Err("HPROF cache directory is not owner-only".into());
        }
    }
    Ok(())
}

#[cfg(not(unix))]
fn restrict_directory_permissions(path: &Path) -> Result<(), String> {
    if is_symlink(path)? {
        return Err("Refusing a symlinked HPROF cache directory".into());
    }
    Ok(())
}

fn load(
    path: &Path,
    source_path: &Path,
    digest: &str,
    source_size: u64,
) -> Result<Option<Heap>, String> {
    if is_symlink(path)? {
        return Ok(None);
    }
    if !safe_file_permissions(path)? {
        return Ok(None);
    }
    let cache_source = Source::open(path)?;
    let result = {
        let mut buffer = cache_source.reader.borrow_mut();
        read_index(
            &mut *buffer,
            source_path,
            digest,
            source_size,
            cache_source.len(),
        )?
    };
    cache_source.check(path)?;
    if is_symlink(path)? || !safe_file_permissions(path)? {
        return Ok(None);
    }
    Ok(result)
}

fn read_index(
    file: &mut (impl Read + Seek),
    source_path: &Path,
    digest: &str,
    source_size: u64,
    length: u64,
) -> Result<Option<Heap>, String> {
    // Validate integrity before decoding any counts or allocating metadata.
    let Some(body_length) = length.checked_sub(SHA256_BYTES as u64) else {
        return Ok(None);
    };
    let checksum = hash_reader(file, Some(body_length))?;
    let mut expected = [0; SHA256_BYTES];
    file.read_exact(&mut expected)
        .map_err(|e| format!("read HPROF cache checksum: {e}"))?;
    if checksum != expected {
        return Ok(None);
    }
    file.seek(SeekFrom::Start(0))
        .map_err(|e| format!("seek HPROF cache: {e}"))?;
    let mut reader = Reader {
        file,
        hash: Sha256::new(),
        remaining: body_length,
    };
    if &reader.scalar::<8>()? != MAGIC || reader.u32()? != VERSION || reader.string()? != digest {
        return Ok(None);
    }
    if reader.u64()? != source_size {
        return Ok(None);
    }
    let format = reader.string()?;
    let id_size = reader.u8()?;
    if id_size != 4 && id_size != 8 {
        return Ok(None);
    }
    let timestamp = reader.u64()?;
    let file_size = reader.u64()?;
    if file_size != source_size {
        return Ok(None);
    }
    let classes = read_classes(&mut reader)?;
    let objects = read_objects(&mut reader)?;
    let roots = read_roots(&mut reader)?;
    let heaps = reader.strings()?;
    let warnings = read_warnings(&mut reader)?;
    if !reader.finish()? {
        return Ok(None);
    }
    let source = Source::open(source_path)?;
    Ok(Some(Heap::from_cached(
        source_path.to_path_buf(),
        source,
        format,
        id_size,
        timestamp,
        file_size,
        classes,
        objects,
        roots,
        heaps,
        warnings,
    )?))
}

fn write(path: &Path, heap: &Heap, digest: &str) -> Result<(), String> {
    heap.check_source()?;
    if is_symlink(path)? {
        return Err("Refusing to replace a symlinked HPROF cache entry".into());
    }
    let temporary = path.with_extension(format!(
        "{}-{}.tmp",
        std::process::id(),
        NEXT.fetch_add(1, Ordering::Relaxed)
    ));
    let mut owned = false;
    let result = (|| {
        let mut options = OpenOptions::new();
        options.write(true).create_new(true);
        #[cfg(unix)]
        {
            use std::os::unix::fs::OpenOptionsExt;
            options.mode(0o600);
        }
        let mut file = options
            .open(&temporary)
            .map_err(|e| format!("Cannot create HPROF cache: {e}"))?;
        owned = true;
        let mut writer = Writer::new(&mut file);
        writer.bytes(MAGIC)?;
        writer.u32(VERSION)?;
        writer.string(digest)?;
        writer.u64(heap.file_size)?;
        writer.string(&heap.format)?;
        writer.u8(heap.id_size)?;
        writer.u64(heap.timestamp)?;
        writer.u64(heap.file_size)?;
        write_classes(&mut writer, &heap.classes)?;
        write_objects(&mut writer, &heap.objects)?;
        write_roots(&mut writer, &heap.roots)?;
        writer.strings(&heap.heaps)?;
        write_warnings(&mut writer, &heap.warnings)?;
        // Flush body and checksum before sync, close and atomic publication.
        writer.finish()?;
        file.sync_all()
            .map_err(|e| format!("Cannot flush HPROF cache: {e}"))?;
        heap.check_source()?;
        drop(file);
        publish(&temporary, path, heap, digest)
    })();
    if owned && result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result
}

fn publish(temporary: &Path, target: &Path, heap: &Heap, digest: &str) -> Result<(), String> {
    if is_symlink(target)? {
        return Err("Refusing to publish over a symlinked HPROF cache entry".into());
    }
    match fs::rename(temporary, target) {
        Ok(()) => Ok(()),
        Err(_) if fs::symlink_metadata(target).is_ok() => {
            publish_existing(temporary, target, heap, digest)
        }
        Err(error) => Err(format!("Cannot publish HPROF cache: {error}")),
    }
}

// Windows may reject replacement rename, or another writer may win. Accept a
// verified winner, otherwise replace only a regular invalid entry and retry once.
fn publish_existing(
    temporary: &Path,
    target: &Path,
    heap: &Heap,
    digest: &str,
) -> Result<(), String> {
    let valid = || {
        matches!(
            load(target, &heap.path, digest, heap.file_size),
            Ok(Some(_))
        )
    };
    if valid() {
        return fs::remove_file(temporary)
            .map_err(|e| format!("discard HPROF cache temporary: {e}"));
    }
    if is_symlink(target)?
        || !fs::symlink_metadata(target)
            .map(|m| m.is_file())
            .unwrap_or(false)
    {
        return Err("Refusing to replace a non-regular HPROF cache entry".into());
    }
    fs::remove_file(target).map_err(|e| format!("remove invalid HPROF cache: {e}"))?;
    match fs::rename(temporary, target) {
        Ok(()) => Ok(()),
        Err(_) if valid() => {
            fs::remove_file(temporary).map_err(|e| format!("discard HPROF cache temporary: {e}"))
        }
        Err(error) => Err(format!("Cannot publish HPROF cache: {error}")),
    }
}

/// Streams the content digest from one identity-checked source handle.
pub fn sha256_file(path: &Path) -> Result<String, String> {
    digest_source(&Source::open(path)?, path)
}

fn digest_source(source: &Source, path: &Path) -> Result<String, String> {
    source.check(path)?;
    let result = {
        let mut reader = source.reader.borrow_mut();
        reader
            .seek(SeekFrom::Start(0))
            .map_err(|e| format!("Cannot seek HPROF source: {e}"))?;
        hash_reader(&mut *reader, None)?
    };
    source.check(path)?;
    Ok(hex(&result))
}

fn hash_reader(reader: &mut impl Read, length: Option<u64>) -> Result<[u8; 32], String> {
    let mut hash = Sha256::new();
    let mut buffer = [0u8; 128 * 1024];
    let mut remaining = length.unwrap_or(u64::MAX);
    loop {
        let limit = remaining.min(buffer.len() as u64) as usize;
        if limit == 0 {
            break;
        }
        let count = reader
            .read(&mut buffer[..limit])
            .map_err(|e| format!("Cannot hash HPROF data: {e}"))?;
        if count == 0 {
            if length.is_some() && remaining != 0 {
                return Err("Truncated HPROF cache".into());
            }
            break;
        }
        hash.update(&buffer[..count]);
        remaining -= count as u64;
    }
    Ok(hash.finish())
}

fn hex(bytes: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut result = String::with_capacity(bytes.len() * 2);
    for &byte in bytes {
        result.push(HEX[(byte >> 4) as usize] as char);
        result.push(HEX[(byte & 0x0f) as usize] as char);
    }
    result
}

// SHA-256 is kept local so the cache integrity contract remains std-only.
#[derive(Clone)]
struct Sha256 {
    state: [u32; 8],
    buffer: [u8; 64],
    buffered: usize,
    length: u64,
}
impl Sha256 {
    fn new() -> Self {
        Self {
            state: [
                0x6a09e667, 0xbb67ae85, 0x3c6ef372, 0xa54ff53a, 0x510e527f, 0x9b05688c, 0x1f83d9ab,
                0x5be0cd19,
            ],
            buffer: [0; 64],
            buffered: 0,
            length: 0,
        }
    }
    fn update(&mut self, bytes: &[u8]) {
        self.length = self
            .length
            .checked_add(bytes.len() as u64)
            .expect("SHA-256 input length overflow");
        let mut offset = 0;
        if self.buffered != 0 {
            let take = (64 - self.buffered).min(bytes.len());
            self.buffer[self.buffered..self.buffered + take].copy_from_slice(&bytes[..take]);
            self.buffered += take;
            offset += take;
            if self.buffered == 64 {
                let block = self.buffer;
                self.compress(&block);
                self.buffered = 0;
            }
        }
        while offset + 64 <= bytes.len() {
            self.compress(&bytes[offset..offset + 64]);
            offset += 64;
        }
        if offset < bytes.len() {
            self.buffer[..bytes.len() - offset].copy_from_slice(&bytes[offset..]);
            self.buffered = bytes.len() - offset;
        }
    }
    fn finish(mut self) -> [u8; 32] {
        let bit_length = self.length * 8;
        self.buffer[self.buffered] = 0x80;
        self.buffered += 1;
        if self.buffered > 56 {
            self.buffer[self.buffered..].fill(0);
            let block = self.buffer;
            self.compress(&block);
            self.buffered = 0;
        }
        self.buffer[self.buffered..56].fill(0);
        self.buffer[56..].copy_from_slice(&bit_length.to_be_bytes());
        let block = self.buffer;
        self.compress(&block);
        let mut result = [0; 32];
        for (index, word) in self.state.iter().enumerate() {
            result[index * 4..index * 4 + 4].copy_from_slice(&word.to_be_bytes());
        }
        result
    }
    fn compress(&mut self, block: &[u8]) {
        const K: [u32; 64] = [
            0x428a2f98, 0x71374491, 0xb5c0fbcf, 0xe9b5dba5, 0x3956c25b, 0x59f111f1, 0x923f82a4,
            0xab1c5ed5, 0xd807aa98, 0x12835b01, 0x243185be, 0x550c7dc3, 0x72be5d74, 0x80deb1fe,
            0x9bdc06a7, 0xc19bf174, 0xe49b69c1, 0xefbe4786, 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f,
            0x4a7484aa, 0x5cb0a9dc, 0x76f988da, 0x983e5152, 0xa831c66d, 0xb00327c8, 0xbf597fc7,
            0xc6e00bf3, 0xd5a79147, 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc,
            0x53380d13, 0x650a7354, 0x766a0abb, 0x81c2c92e, 0x92722c85, 0xa2bfe8a1, 0xa81a664b,
            0xc24b8b70, 0xc76c51a3, 0xd192e819, 0xd6990624, 0xf40e3585, 0x106aa070, 0x19a4c116,
            0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3, 0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3,
            0x748f82ee, 0x78a5636f, 0x84c87814, 0x8cc70208, 0x90befffa, 0xa4506ceb, 0xbef9a3f7,
            0xc67178f2,
        ];
        let mut w = [0u32; 64];
        for index in 0..16 {
            w[index] = u32::from_be_bytes(block[index * 4..index * 4 + 4].try_into().unwrap());
        }
        for index in 16..64 {
            let a = w[index - 15];
            let b = w[index - 2];
            w[index] = a.rotate_right(7) ^ a.rotate_right(18) ^ (a >> 3);
            w[index] = w[index]
                .wrapping_add(w[index - 16])
                .wrapping_add(w[index - 7])
                .wrapping_add(b.rotate_right(17) ^ b.rotate_right(19) ^ (b >> 10));
        }
        let mut a = self.state[0];
        let mut b = self.state[1];
        let mut c = self.state[2];
        let mut d = self.state[3];
        let mut e = self.state[4];
        let mut f = self.state[5];
        let mut g = self.state[6];
        let mut h = self.state[7];
        for index in 0..64 {
            let s1 = e.rotate_right(6) ^ e.rotate_right(11) ^ e.rotate_right(25);
            let ch = (e & f) ^ ((!e) & g);
            let temp1 = h
                .wrapping_add(s1)
                .wrapping_add(ch)
                .wrapping_add(K[index])
                .wrapping_add(w[index]);
            let s0 = a.rotate_right(2) ^ a.rotate_right(13) ^ a.rotate_right(22);
            let maj = (a & b) ^ (a & c) ^ (b & c);
            let temp2 = s0.wrapping_add(maj);
            h = g;
            g = f;
            f = e;
            e = d.wrapping_add(temp1);
            d = c;
            c = b;
            b = a;
            a = temp1.wrapping_add(temp2);
        }
        self.state[0] = self.state[0].wrapping_add(a);
        self.state[1] = self.state[1].wrapping_add(b);
        self.state[2] = self.state[2].wrapping_add(c);
        self.state[3] = self.state[3].wrapping_add(d);
        self.state[4] = self.state[4].wrapping_add(e);
        self.state[5] = self.state[5].wrapping_add(f);
        self.state[6] = self.state[6].wrapping_add(g);
        self.state[7] = self.state[7].wrapping_add(h);
    }
}

struct Writer<W: Write> {
    file: BufWriter<W>,
    hash: Sha256,
}
impl<W: Write> Writer<W> {
    fn new(file: W) -> Self {
        Self {
            file: BufWriter::with_capacity(IO_BUFFER_BYTES, file),
            hash: Sha256::new(),
        }
    }

    fn finish(mut self) -> Result<(), String> {
        let checksum = self.hash.finish();
        self.file
            .write_all(&checksum)
            .map_err(|e| format!("write HPROF cache checksum: {e}"))?;
        self.file
            .flush()
            .map_err(|e| format!("flush HPROF cache: {e}"))
    }

    fn bytes(&mut self, value: &[u8]) -> Result<(), String> {
        self.hash.update(value);
        self.file
            .write_all(value)
            .map_err(|e| format!("write HPROF cache: {e}"))
    }
    fn u8(&mut self, value: u8) -> Result<(), String> {
        self.bytes(&[value])
    }
    fn u32(&mut self, value: u32) -> Result<(), String> {
        self.bytes(&value.to_be_bytes())
    }
    fn u64(&mut self, value: u64) -> Result<(), String> {
        self.bytes(&value.to_be_bytes())
    }
    fn len(&mut self, value: usize) -> Result<(), String> {
        self.u32(
            u32::try_from(value).map_err(|_| "HPROF cache collection is too large".to_string())?,
        )
    }
    fn index(&mut self, value: usize) -> Result<(), String> {
        self.u32(if value == usize::MAX {
            u32::MAX
        } else {
            u32::try_from(value).map_err(|_| "HPROF cache index is too large".to_string())?
        })
    }
    fn string(&mut self, value: &str) -> Result<(), String> {
        let bytes = value.as_bytes();
        if bytes.len() > MAX_STRING as usize {
            return Err("HPROF cache string is too large".into());
        }
        self.u32(bytes.len() as u32)?;
        self.bytes(bytes)
    }
    fn strings(&mut self, values: &[String]) -> Result<(), String> {
        self.len(values.len())?;
        for value in values {
            self.string(value)?;
        }
        Ok(())
    }
}
struct Reader<R: Read> {
    file: R,
    hash: Sha256,
    remaining: u64,
}
impl<R: Read> Reader<R> {
    fn read_body(&mut self, value: &mut [u8]) -> Result<(), String> {
        if value.len() as u64 > self.remaining {
            return Err("Truncated HPROF cache value".into());
        }
        self.file
            .read_exact(value)
            .map_err(|e| format!("read HPROF cache: {e}"))?;
        self.hash.update(value);
        self.remaining -= value.len() as u64;
        Ok(())
    }

    fn scalar<const N: usize>(&mut self) -> Result<[u8; N], String> {
        let mut value = [0; N];
        self.read_body(&mut value)?;
        Ok(value)
    }

    fn bytes(&mut self, length: usize) -> Result<Vec<u8>, String> {
        if length as u64 > self.remaining {
            return Err("Truncated HPROF cache value".into());
        }
        let mut value = Vec::new();
        value
            .try_reserve_exact(length)
            .map_err(|_| "Cannot allocate HPROF cache value")?;
        value.resize(length, 0);
        self.read_body(&mut value)?;
        Ok(value)
    }
    fn finish(&mut self) -> Result<bool, String> {
        if self.remaining != 0 {
            return Ok(false);
        }
        let mut expected = [0; SHA256_BYTES];
        self.file
            .read_exact(&mut expected)
            .map_err(|e| format!("read HPROF cache checksum: {e}"))?;
        let mut trailing = [0];
        if self
            .file
            .read(&mut trailing)
            .map_err(|e| format!("read HPROF cache: {e}"))?
            != 0
        {
            return Ok(false);
        }
        Ok(expected == self.hash.clone().finish())
    }
    fn u8(&mut self) -> Result<u8, String> {
        Ok(self.scalar::<1>()?[0])
    }
    fn u32(&mut self) -> Result<u32, String> {
        Ok(u32::from_be_bytes(self.scalar()?))
    }
    fn u64(&mut self) -> Result<u64, String> {
        Ok(u64::from_be_bytes(self.scalar()?))
    }
    fn len(&mut self) -> Result<usize, String> {
        let value = self.u32()?;
        if value as u64 > self.remaining {
            return Err("HPROF cache collection is too large".into());
        }
        Ok(value as usize)
    }
    fn index(&mut self) -> Result<usize, String> {
        let value = self.u32()?;
        Ok(if value == u32::MAX {
            usize::MAX
        } else {
            value as usize
        })
    }
    fn string(&mut self) -> Result<String, String> {
        let length = self.u32()?;
        if length > MAX_STRING {
            return Err("HPROF cache string is too large".into());
        }
        String::from_utf8(self.bytes(length as usize)?)
            .map_err(|_| "HPROF cache contains invalid UTF-8".into())
    }
    fn strings(&mut self) -> Result<Vec<String>, String> {
        let count = self.len()?;
        let mut result = Vec::new();
        for _ in 0..count {
            result.push(self.string()?);
        }
        Ok(result)
    }
}

// Serialization helpers remain intentionally simple and bounded.
fn write_classes(w: &mut Writer<impl Write>, classes: &[Class]) -> Result<(), String> {
    w.len(classes.len())?;
    for class in classes {
        w.u64(class.id)?;
        w.string(&class.name)?;
        w.u64(class.super_id)?;
        w.u64(class.instance_size)?;
        w.u64(class.loader_id)?;
        w.index(class.heap)?;
        w.len(class.fields.len())?;
        for field in &class.fields {
            w.string(&field.name)?;
            w.u8(field.ty)?;
        }
        w.len(class.static_fields.len())?;
        for value in &class.static_fields {
            write_value(w, value)?;
        }
    }
    Ok(())
}
fn read_classes(r: &mut Reader<impl Read>) -> Result<Vec<Class>, String> {
    let count = r.len()?;
    let mut result = Vec::new();
    for _ in 0..count {
        let id = r.u64()?;
        let name = r.string()?;
        let super_id = r.u64()?;
        let instance_size = r.u64()?;
        let loader_id = r.u64()?;
        let heap = r.index()?;
        let count = r.len()?;
        let mut fields = Vec::new();
        for _ in 0..count {
            fields.push(Field {
                name: r.string()?,
                ty: r.u8()?,
            });
        }
        let count = r.len()?;
        let mut static_fields = Vec::new();
        for _ in 0..count {
            static_fields.push(read_value(r)?);
        }
        result.push(Class {
            id,
            name,
            super_id,
            instance_size,
            loader_id,
            fields,
            static_fields,
            heap,
        });
    }
    Ok(result)
}
fn write_objects(w: &mut Writer<impl Write>, objects: &[Object]) -> Result<(), String> {
    w.len(objects.len())?;
    for object in objects {
        w.u64(object.id)?;
        w.u64(object.class_id)?;
        w.u64(object.payload_offset)?;
        w.u32(object.count)?;
        w.index(object.heap)?;
        w.u64(object.shallow_size)?;
        match object.kind {
            ObjectKind::Instance => w.u8(0)?,
            ObjectKind::ObjectArray => w.u8(1)?,
            ObjectKind::PrimitiveArray { ty, has_data } => {
                w.u8(2)?;
                w.u8(ty)?;
                w.u8(has_data as u8)?;
            }
        }
    }
    Ok(())
}
fn read_objects(r: &mut Reader<impl Read>) -> Result<Vec<Object>, String> {
    let count = r.len()?;
    let mut result = Vec::new();
    for _ in 0..count {
        let id = r.u64()?;
        let class_id = r.u64()?;
        let payload_offset = r.u64()?;
        let count = r.u32()?;
        let heap = r.index()?;
        let shallow_size = r.u64()?;
        let kind = match r.u8()? {
            0 => ObjectKind::Instance,
            1 => ObjectKind::ObjectArray,
            2 => ObjectKind::PrimitiveArray {
                ty: r.u8()?,
                has_data: r.u8()? != 0,
            },
            _ => return Err("invalid HPROF cache object kind".into()),
        };
        result.push(Object {
            id,
            class_id,
            kind,
            payload_offset,
            count,
            heap,
            shallow_size,
        });
    }
    Ok(result)
}
fn write_roots(w: &mut Writer<impl Write>, roots: &[Root]) -> Result<(), String> {
    w.len(roots.len())?;
    for root in roots {
        w.u64(root.id)?;
        w.string(&root.kind)?;
    }
    Ok(())
}
fn read_roots(r: &mut Reader<impl Read>) -> Result<Vec<Root>, String> {
    let count = r.len()?;
    let mut result = Vec::new();
    for _ in 0..count {
        result.push(Root {
            id: r.u64()?,
            kind: r.string()?,
        });
    }
    Ok(result)
}
fn write_warnings(w: &mut Writer<impl Write>, warnings: &[Warning]) -> Result<(), String> {
    w.len(warnings.len())?;
    for warning in warnings {
        w.string(&warning.message)?;
        w.u8(warning.offset.is_some() as u8)?;
        if let Some(offset) = warning.offset {
            w.u64(offset)?;
        }
    }
    Ok(())
}
fn read_warnings(r: &mut Reader<impl Read>) -> Result<Vec<Warning>, String> {
    let count = r.len()?;
    let mut result = Vec::new();
    for _ in 0..count {
        let message = r.string()?;
        let offset = if r.u8()? != 0 { Some(r.u64()?) } else { None };
        result.push(Warning { message, offset });
    }
    Ok(result)
}
fn write_value(w: &mut Writer<impl Write>, value: &Value) -> Result<(), String> {
    w.string(&value.name)?;
    w.u8(value.ty)?;
    w.u64(value.bits)
}
fn read_value(r: &mut Reader<impl Read>) -> Result<Value, String> {
    Ok(Value {
        name: r.string()?,
        ty: r.u8()?,
        bits: r.u64()?,
    })
}

#[cfg(unix)]
fn safe_file_permissions(path: &Path) -> Result<bool, String> {
    use std::os::unix::fs::MetadataExt;
    let metadata =
        fs::symlink_metadata(path).map_err(|e| format!("Cannot inspect HPROF cache: {e}"))?;
    Ok(!metadata.file_type().is_symlink() && metadata.is_file() && metadata.mode() & 0o077 == 0)
}
#[cfg(not(unix))]
fn safe_file_permissions(path: &Path) -> Result<bool, String> {
    Ok(!is_symlink(path)? && fs::metadata(path).map(|m| m.is_file()).unwrap_or(false))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{self, BufReader, Cursor};

    #[derive(Default)]
    struct CountingIo {
        cursor: Cursor<Vec<u8>>,
        reads: usize,
        writes: usize,
        flushes: usize,
        fail_flush: bool,
    }

    impl Read for CountingIo {
        fn read(&mut self, bytes: &mut [u8]) -> io::Result<usize> {
            self.reads += 1;
            self.cursor.read(bytes)
        }
    }

    impl Write for CountingIo {
        fn write(&mut self, bytes: &[u8]) -> io::Result<usize> {
            self.writes += 1;
            self.cursor.write(bytes)
        }

        fn flush(&mut self) -> io::Result<()> {
            self.flushes += 1;
            if self.fail_flush {
                return Err(io::Error::other("injected cache flush failure"));
            }
            Ok(())
        }
    }

    impl Seek for CountingIo {
        fn seek(&mut self, position: SeekFrom) -> io::Result<u64> {
            self.cursor.seek(position)
        }
    }

    #[test]
    fn hundred_thousand_small_objects_use_bounded_buffered_io_and_stack_scalars() {
        let objects: Vec<_> = (1..=100_000)
            .map(|id| Object {
                id,
                class_id: 9,
                kind: ObjectKind::Instance,
                payload_offset: id * 8,
                count: 8,
                heap: 0,
                shallow_size: 16,
            })
            .collect();
        let mut io = CountingIo::default();
        let mut writer = Writer::new(&mut io);
        assert_eq!(writer.file.capacity(), IO_BUFFER_BYTES);
        write_objects(&mut writer, &objects).unwrap();
        writer.finish().unwrap();
        assert_eq!(io.flushes, 1, "finish must flush before seek or sync");

        let length = io.cursor.get_ref().len();
        assert_eq!(length, 4 + objects.len() * 41 + SHA256_BYTES);
        // Seven scalar writes per object must coalesce into buffer-sized writes.
        let max_writes = length.div_ceil(IO_BUFFER_BYTES - 8);
        assert!(
            io.writes <= max_writes,
            "{} writes exceeds {max_writes}",
            io.writes
        );

        io.seek(SeekFrom::Start(0)).unwrap();
        let mut buffered = BufReader::with_capacity(IO_BUFFER_BYTES, &mut io);
        assert_eq!(buffered.capacity(), IO_BUFFER_BYTES);
        let mut reader = Reader {
            file: &mut buffered,
            hash: Sha256::new(),
            remaining: (length - SHA256_BYTES) as u64,
        };
        assert_eq!(read_objects(&mut reader).unwrap(), objects);
        assert!(reader.finish().unwrap(), "body and checksum must roundtrip");
        drop(buffered);
        let max_reads = length.div_ceil(IO_BUFFER_BYTES) + 1;
        assert!(
            io.reads <= max_reads,
            "{} reads exceeds {max_reads}",
            io.reads
        );
    }

    #[test]
    fn buffered_finish_propagates_flush_failure() {
        let mut io = CountingIo {
            fail_flush: true,
            ..CountingIo::default()
        };
        let mut writer = Writer::new(&mut io);
        writer.u64(123).unwrap();
        assert!(writer
            .finish()
            .unwrap_err()
            .contains("injected cache flush failure"));
        assert_eq!(io.flushes, 1);
    }

    struct Fixture(PathBuf);
    impl Fixture {
        fn new() -> Self {
            let root = std::env::temp_dir().join(format!(
                "aps-cache-unit-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ));
            fs::create_dir(&root).unwrap();
            let mut bytes = b"JAVA PROFILE 1.0.3\0".to_vec();
            bytes.extend(8u32.to_be_bytes());
            bytes.extend(1234u64.to_be_bytes());
            fs::write(root.join("source.hprof"), bytes).unwrap();
            Self(root)
        }
        fn source(&self) -> PathBuf {
            self.0.join("source.hprof")
        }
    }
    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = fs::remove_dir_all(&self.0);
        }
    }

    #[test]
    fn existing_entry_publication_accepts_verified_winner_and_replaces_corruption() {
        let fixture = Fixture::new();
        let source = fixture.source();
        let digest = sha256_file(&source).unwrap();
        let directory = fixture.0.join("cache");
        let (heap, _) = open_or_parse(&source, &digest, &directory).unwrap();
        let target = directory.join(format!("{digest}.idx"));
        let temporary = directory.join("incoming.tmp");
        let original = fs::read(&target).unwrap();
        fs::copy(&target, &temporary).unwrap();
        // Exercise Windows replacement-conflict handling even on Unix.
        publish_existing(&temporary, &target, &heap, &digest).unwrap();
        assert!(!temporary.exists());
        assert_eq!(fs::read(&target).unwrap(), original);
        fs::copy(&target, &temporary).unwrap();
        fs::write(&target, b"broken winner").unwrap();
        publish_existing(&temporary, &target, &heap, &digest).unwrap();
        assert!(!temporary.exists());
        assert_eq!(fs::read(&target).unwrap(), original);
        assert_eq!(
            open_or_parse(&source, &digest, &directory).unwrap().1,
            Status::Hit
        );
    }

    #[test]
    fn changed_source_identity_prevents_hashing_old_handle_or_publishing_old_heap() {
        let fixture = Fixture::new();
        let source = fixture.source();
        let digest = sha256_file(&source).unwrap();
        let retained_source = Source::open(&source).unwrap();
        let heap = Heap::open(&source).unwrap();
        let replacement = fixture.0.join("replacement");
        let mut bytes = fs::read(&source).unwrap();
        *bytes.last_mut().unwrap() ^= 1;
        fs::write(&replacement, &bytes).unwrap();
        fs::remove_file(&source).unwrap();
        fs::rename(&replacement, &source).unwrap();
        assert!(digest_source(&retained_source, &source).is_err());
        let target = fixture.0.join("must-not-publish.idx");
        assert!(write(&target, &heap, &digest).is_err());
        assert!(!target.exists());
    }

    #[test]
    fn checksummed_unsupported_version_is_discarded_and_rebuilt() {
        let fixture = Fixture::new();
        let source = fixture.source();
        let digest = sha256_file(&source).unwrap();
        let directory = fixture.0.join("cache");
        open_or_parse(&source, &digest, &directory).unwrap();
        let target = directory.join(format!("{digest}.idx"));
        let mut bytes = fs::read(&target).unwrap();
        bytes[8..12].copy_from_slice(&1u32.to_be_bytes());
        let body = bytes.len() - SHA256_BYTES;
        let mut hash = Sha256::new();
        hash.update(&bytes[..body]);
        bytes[body..].copy_from_slice(&hash.finish());
        fs::write(&target, bytes).unwrap();
        assert_eq!(
            open_or_parse(&source, &digest, &directory).unwrap().1,
            Status::Miss
        );
        assert_eq!(
            open_or_parse(&source, &digest, &directory).unwrap().1,
            Status::Hit
        );
    }

    #[test]
    fn sha256_known_vectors() {
        for (input, expected) in [
            (
                b"".as_slice(),
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ),
            (
                b"abc".as_slice(),
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            ),
            (
                b"abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".as_slice(),
                "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1",
            ),
        ] {
            for chunk_size in [1, 3, 7, 55, 56, 63, 64, 65, 128] {
                let mut hash = Sha256::new();
                for chunk in input.chunks(chunk_size) {
                    hash.update(chunk);
                }
                assert_eq!(hex(&hash.finish()), expected);
            }
        }
        let mut hash = Sha256::new();
        for _ in 0..1000 {
            hash.update(&[b'a'; 1000]);
        }
        assert_eq!(
            hex(&hash.finish()),
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"
        );
    }
}

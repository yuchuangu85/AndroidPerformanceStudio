//! API 35 Bitmap.dumpData extraction. Parsing and PNG payload validation run in
//! a short-lived worker, not the GPUI process. Only DumpData.buffers references
//! are images; arbitrary PNG-looking byte arrays and normal Bitmap findings are
//! never promoted to bitmap-dump evidence. No new codec dependency is required.
use aps_hprof_engine::{
    cache,
    wire::{self, Value},
};
use aps_platform::{CancellationToken, ProcessRequest, ProcessRunner};
use std::{
    collections::BTreeMap,
    fs::{self, File, OpenOptions},
    io::{self, Read, Seek, SeekFrom, Write},
    path::{Path, PathBuf},
    time::Duration,
};

pub const MAX_BITMAP_IMAGES: usize = 1000;
const MAX_IMAGE_BYTES: u64 = 32 * 1024 * 1024;
const MAX_TOTAL_BYTES: u64 = 256 * 1024 * 1024;
const MAX_RECORDS: usize = 1_000_000;

#[derive(Clone, Debug)]
pub struct BitmapImage {
    pub index: u32,
    pub array_id: String,
    pub width: u32,
    pub height: u32,
    pub png_bytes: u64,
    pub sha256: String,
    pub path: PathBuf,
}
#[derive(Clone, Debug)]
pub struct BitmapDump {
    pub source_digest: String,
    pub recorded: u64,
    pub discovered: u64,
    pub omitted: u64,
    pub images: Vec<BitmapImage>,
}

pub fn extract_in_worker(
    worker: &Path,
    source: &Path,
    directory: &Path,
    cancel: &CancellationToken,
) -> Result<BitmapDump, String> {
    if cancel.is_cancelled() {
        return Err("Bitmap extraction cancelled".into());
    }
    if !worker.is_file() {
        return Err("Build aps-memory-worker before extracting bitmap images".into());
    }
    // Never open/hash the untrusted input in the host: a path can be replaced
    // by a FIFO or grow while being read. All such I/O belongs to the child,
    // which ProcessRunner can kill on cancellation or its overall deadline.
    let before = fs::symlink_metadata(source).map_err(|_| "Invalid bitmap source")?;
    if !source.is_absolute()
        || before.is_symlink()
        || !before.is_file()
        || before.len() > crate::MAX_INPUT_BYTES
    {
        return Err(
            "Select a regular non-symlink bitmap HPROF within the 8 GiB input limit".into(),
        );
    }
    let mut request = ProcessRequest::new(worker)
        .arg("--bitmap-extract")
        .arg(source)
        .arg(directory)
        .arg("compute");
    request.timeout = Duration::from_secs(600);
    request.stdout_limit = crate::MAX_RESPONSE_BYTES;
    request.stderr_limit = 1024;
    let out = ProcessRunner
        .run(&request, cancel)
        .map_err(|e| format!("Bitmap extraction worker: {e}"))?;
    if !out.status.success() {
        return Err("Bitmap dump metadata or PNG payloads are unsupported, truncated or invalid; ordinary HPROF is not an image dump".into());
    }
    let mut input = out.stdout.as_slice();
    let frame = wire::read_frame(&mut input)
        .map_err(|_| "Invalid bitmap response frame")?
        .ok_or("Missing bitmap response")?;
    if !input.is_empty() {
        return Err("Unexpected trailing bitmap response data".into());
    }
    let value = wire::decode(&frame).map_err(|_| "Invalid bitmap response")?;
    let number = |v: &Value, key: &str| -> Result<u64, String> {
        v.get(key)
            .and_then(Value::as_int)
            .ok()
            .and_then(|n| n.try_into().ok())
            .ok_or_else(|| "Invalid bitmap metadata".into())
    };
    let text = |v: &Value, key: &str| -> Result<String, String> {
        v.get(key)
            .and_then(Value::as_str)
            .map(str::to_owned)
            .map_err(|_| "Invalid bitmap metadata".into())
    };
    let digest = text(&value, "sourceDigest")?;
    let after = fs::symlink_metadata(source).map_err(|_| "Bitmap source unavailable")?;
    if digest.len() != 64
        || !digest.bytes().all(|b| b.is_ascii_hexdigit())
        || !same_file(&before, &after)
    {
        return Err("Bitmap HPROF changed during extraction".into());
    }
    let rows = value
        .get("images")
        .and_then(Value::as_list)
        .map_err(|_| "Invalid bitmap image list")?;
    if rows.len() > MAX_BITMAP_IMAGES {
        return Err("Bitmap response exceeds image budget".into());
    }
    let mut images = Vec::new();
    for row in rows {
        let index = u32::try_from(number(row, "index")?).map_err(|_| "Invalid bitmap index")?;
        let path = directory.join(format!("bitmap-{index}.png"));
        let sha256 = text(row, "sha256")?;
        let size = number(row, "pngBytes")?;
        let meta = fs::symlink_metadata(&path).map_err(|_| "Bitmap image is unavailable")?;
        if meta.is_symlink()
            || !meta.is_file()
            || meta.len() != size
            || size > MAX_IMAGE_BYTES
            || sha256.len() != 64
            || !sha256.bytes().all(|b| b.is_ascii_hexdigit())
        {
            return Err("Bitmap extracted image changed or exceeded its budget".into());
        }
        images.push(BitmapImage {
            index,
            array_id: text(row, "arrayId")?,
            width: u32::try_from(number(row, "width")?).map_err(|_| "Invalid bitmap width")?,
            height: u32::try_from(number(row, "height")?).map_err(|_| "Invalid bitmap height")?,
            png_bytes: size,
            sha256,
            path,
        });
    }
    Ok(BitmapDump {
        source_digest: digest,
        recorded: number(&value, "recorded")?,
        discovered: number(&value, "discovered")?,
        omitted: number(&value, "omitted")?,
        images,
    })
}

/// Entrypoint used only by the dedicated worker mode. Output must be an empty
/// private directory supplied by the parent, never an arbitrary overwrite sink.
pub fn serve_extract(
    source: &Path,
    directory: &Path,
    digest: &str,
    writer: &mut impl Write,
) -> Result<(), String> {
    let meta = fs::symlink_metadata(source).map_err(|_| "Invalid bitmap source")?;
    if !source.is_absolute()
        || !directory.is_absolute()
        || meta.is_symlink()
        || !meta.is_file()
        || meta.len() > crate::MAX_INPUT_BYTES
        || (digest != "compute"
            && (digest.len() != 64 || !digest.bytes().all(|b| b.is_ascii_hexdigit())))
    {
        return Err("Invalid bitmap source".into());
    }
    let actual = cache::sha256_file(source).map_err(|_| "Invalid bitmap source")?;
    if digest != "compute" && actual != digest {
        return Err("Bitmap source digest mismatch".into());
    }
    let digest = actual.as_str();
    let output = fs::symlink_metadata(directory).map_err(|_| "Invalid bitmap output directory")?;
    if output.is_symlink()
        || !output.is_dir()
        || fs::read_dir(directory)
            .map_err(|_| "Invalid bitmap directory")?
            .next()
            .is_some()
    {
        return Err("Bitmap output directory must be empty and non-symlink".into());
    }
    let mut parser = Parser::open(source).map_err(|_| "Invalid bitmap HPROF header")?;
    let result = parser
        .extract(directory)
        .map_err(|_| "Invalid bitmap dump metadata or PNG")?;
    let after = fs::symlink_metadata(source).map_err(|_| "Bitmap source changed")?;
    if !same_file(&meta, &after)
        || cache::sha256_file(source).map_err(|_| "Bitmap source changed")? != digest
    {
        return Err("Bitmap source changed".into());
    }
    let rows = result
        .images
        .into_iter()
        .map(|image| {
            Value::map([
                ("index", (image.index as i64).into()),
                ("arrayId", image.array_id.into()),
                ("width", (image.width as i64).into()),
                ("height", (image.height as i64).into()),
                ("pngBytes", (image.png_bytes as i64).into()),
                ("sha256", image.sha256.into()),
            ])
        })
        .collect();
    let response = Value::map([
        ("sourceDigest", digest.into()),
        ("recorded", (result.recorded as i64).into()),
        ("discovered", (result.discovered as i64).into()),
        ("omitted", (result.omitted as i64).into()),
        ("images", Value::List(rows)),
    ]);
    let bytes = wire::encode(&response).map_err(|_| "Invalid bitmap response")?;
    if bytes.len() > crate::MAX_RESPONSE_BYTES {
        return Err("Bitmap response budget exceeded".into());
    }
    wire::write_frame(writer, &response).map_err(|_| "Cannot write bitmap response".to_owned())
}

fn invalid() -> io::Error {
    io::ErrorKind::InvalidData.into()
}
fn same_file(a: &fs::Metadata, b: &fs::Metadata) -> bool {
    if b.is_symlink()
        || !b.is_file()
        || a.len() != b.len()
        || a.modified().ok() != b.modified().ok()
    {
        return false;
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        if a.dev() != b.dev() || a.ino() != b.ino() {
            return false;
        }
    }
    true
}
struct Parser {
    file: File,
    size: u64,
    start: u64,
    id: u64,
}
impl Parser {
    fn open(path: &Path) -> io::Result<Self> {
        let file = File::open(path)?;
        let size = file.metadata()?.len();
        let mut p = Self {
            file,
            size,
            start: 0,
            id: 0,
        };
        let mut end = 0;
        while end < 256 && p.num(end, 1)? != 0 {
            end += 1;
        }
        if end == 256 || !p.bytes(0, end as usize)?.starts_with(b"JAVA PROFILE") {
            return Err(invalid());
        }
        p.id = p.num(end + 1, 4)?;
        if !matches!(p.id, 4 | 8) {
            return Err(invalid());
        }
        p.start = end + 13;
        if p.start > size {
            return Err(invalid());
        }
        Ok(p)
    }
    fn bytes(&mut self, pos: u64, count: usize) -> io::Result<Vec<u8>> {
        if pos.checked_add(count as u64).is_none_or(|e| e > self.size) {
            return Err(invalid());
        }
        let mut out = vec![0; count];
        self.file.seek(SeekFrom::Start(pos))?;
        self.file.read_exact(&mut out)?;
        Ok(out)
    }
    fn num(&mut self, pos: u64, width: u64) -> io::Result<u64> {
        if width > 8 {
            return Err(invalid());
        }
        Ok(self
            .bytes(pos, width as usize)?
            .into_iter()
            .fold(0, |v, b| (v << 8) | b as u64))
    }
    fn width(&self, ty: u64) -> io::Result<u64> {
        match ty {
            2 => Ok(self.id),
            4 | 8 => Ok(1),
            5 | 9 => Ok(2),
            6 | 10 => Ok(4),
            7 | 11 => Ok(8),
            _ => Err(invalid()),
        }
    }
    fn class_end(&mut self, body: u64) -> io::Result<u64> {
        let mut p = body + 7 * self.id + 8;
        let count = self.num(p, 2)?;
        p += 2;
        for _ in 0..count {
            let ty = self.num(p + 2, 1)?;
            p += 3 + self.width(ty)?;
        }
        let count = self.num(p, 2)?;
        p += 2;
        for _ in 0..count {
            let ty = self.num(p + self.id, 1)?;
            p += self.id + 1 + self.width(ty)?;
        }
        let count = self.num(p, 2)?;
        Ok(p + 2 + count * (self.id + 1))
    }
    fn heap_end(&mut self, tag: u64, b: u64) -> io::Result<u64> {
        Ok(match tag {
            0xff | 0x05 | 0x07 | 0x89..=0x8d | 0x90 => b + self.id,
            0x01 => b + self.id * 2,
            0x02 | 0x03 | 0x08 | 0x8e => b + self.id + 8,
            0x04 | 0x06 => b + self.id + 4,
            0xfe => b + 4 + self.id,
            0x20 => self.class_end(b)?,
            0x21 => {
                let p = b + 2 * self.id + 4;
                p + 4 + self.num(p, 4)?
            }
            0x22 => {
                let p = b + self.id + 4;
                p + 4 + self.id + self.num(p, 4)? * self.id
            }
            0x23 | 0xc3 => {
                let p = b + self.id + 4;
                let count = self.num(p, 4)?;
                let ty = self.num(p + 4, 1)?;
                p + 5
                    + if tag == 0x23 {
                        count * self.width(ty)?
                    } else {
                        0
                    }
            }
            _ => return Err(invalid()),
        })
    }
    fn top(
        &mut self,
        mut visit: impl FnMut(&mut Self, u64, u64, u64) -> io::Result<()>,
    ) -> io::Result<()> {
        let mut p = self.start;
        while p < self.size {
            if self.size - p < 9 {
                return Err(invalid());
            }
            let tag = self.num(p, 1)?;
            let end = p + 9 + self.num(p + 5, 4)?;
            if end > self.size {
                return Err(invalid());
            }
            visit(self, tag, p + 9, end)?;
            p = end;
        }
        Ok(())
    }
    fn heap(
        &mut self,
        mut visit: impl FnMut(&mut Self, u64, u64, u64) -> io::Result<()>,
    ) -> io::Result<()> {
        self.top(|s, tag, mut p, end| {
            if tag != 0x0c && tag != 0x1c {
                return Ok(());
            }
            while p < end {
                let tag = s.num(p, 1)?;
                let next = s.heap_end(tag, p + 1)?;
                if next <= p || next > end {
                    return Err(invalid());
                }
                visit(s, tag, p + 1, next)?;
                p = next;
            }
            Ok(())
        })
    }
    fn extract(&mut self, directory: &Path) -> io::Result<BitmapDump> {
        let names = [
            "android.graphics.Bitmap",
            "android.graphics.Bitmap$DumpData",
            "dumpData",
            "buffers",
            "count",
            "max",
        ];
        let mut strings = BTreeMap::new();
        let mut classes = BTreeMap::new();
        self.top(|s, tag, b, end| {
            if tag == 1 && end - b >= s.id && end - b - s.id <= 128 {
                let id = s.num(b, s.id)?;
                // HPROF's unrelated names can be modified UTF-8. Only these ASCII
                // metadata names are relevant; do not reject an otherwise valid
                // dump because a different class name uses surrogate encoding.
                if let Ok(value) = String::from_utf8(s.bytes(b + s.id, (end - b - s.id) as usize)?)
                {
                    let value = value.replace('/', ".");
                    if let Some(index) = names.iter().position(|n| *n == value) {
                        strings.insert(id, index);
                    }
                }
                if strings.len() > MAX_RECORDS {
                    return Err(invalid());
                }
            } else if tag == 2 {
                if end - b != 8 + 2 * s.id {
                    return Err(invalid());
                }
                let id = s.num(b + 4, s.id)?;
                let name = s.num(b + 8 + s.id, s.id)?;
                if classes.insert(id, name).is_some() || classes.len() > MAX_RECORDS {
                    return Err(invalid());
                }
            }
            Ok(())
        })?;
        let class = |name: usize| {
            classes
                .iter()
                .find(|(_, v)| strings.get(v) == Some(&name))
                .map(|(k, _)| *k)
                .ok_or_else(invalid)
        };
        let bitmap = class(0)?;
        let dump_class = class(1)?;
        let mut dump_id = 0;
        let mut fields = Vec::new();
        self.heap(|s, tag, b, _| {
            if tag != 0x20 {
                return Ok(());
            }
            let id = s.num(b, s.id)?;
            if id != bitmap && id != dump_class {
                return Ok(());
            }
            let mut p = b + 7 * s.id + 8;
            let n = s.num(p, 2)?;
            p += 2;
            for _ in 0..n {
                let ty = s.num(p + 2, 1)?;
                p += 3 + s.width(ty)?;
            }
            let n = s.num(p, 2)?;
            p += 2;
            for _ in 0..n {
                let name = s.num(p, s.id)?;
                let ty = s.num(p + s.id, 1)?;
                p += s.id + 1;
                if id == bitmap && strings.get(&name) == Some(&2) && ty == 2 {
                    dump_id = s.num(p, s.id)?;
                }
                p += s.width(ty)?;
            }
            let n = s.num(p, 2)?;
            p += 2;
            if id == dump_class {
                for _ in 0..n {
                    let name = s.num(p, s.id)?;
                    let ty = s.num(p + s.id, 1)?;
                    let key = strings.get(&name).copied();
                    if (key == Some(3) && ty != 2) || (matches!(key, Some(4 | 5)) && ty != 10) {
                        return Err(invalid());
                    }
                    fields.push((key, s.width(ty)?));
                    p += s.id + 1;
                }
            }
            Ok(())
        })?;
        if dump_id == 0 || fields.is_empty() {
            return Err(invalid());
        }
        let (mut count, mut max, mut buffers) = (None, None, 0);
        self.heap(|s, tag, b, end| {
            if tag != 0x21 || s.num(b, s.id)? != dump_id {
                return Ok(());
            }
            if s.num(b + s.id + 4, s.id)? != dump_class {
                return Err(invalid());
            }
            let mut p = b + 2 * s.id + 8;
            for (name, width) in &fields {
                if p + width > end {
                    return Err(invalid());
                }
                let value = s.num(p, *width)?;
                p += width;
                match name {
                    Some(3) => buffers = value,
                    Some(4) => count = Some(value),
                    Some(5) => max = Some(value),
                    _ => {}
                }
            }
            Ok(())
        })?;
        let recorded = count.ok_or_else(invalid)?;
        let discovered = max.ok_or_else(invalid)?;
        if recorded > MAX_RECORDS as u64 || discovered < recorded || buffers == 0 {
            return Err(invalid());
        }
        let mut arrays = BTreeMap::<u64, Vec<u32>>::new();
        let mut found = false;
        self.heap(|s, tag, b, _| {
            if tag != 0x22 || s.num(b, s.id)? != buffers {
                return Ok(());
            }
            if found {
                return Err(invalid());
            }
            found = true;
            let n = s.num(b + s.id + 4, 4)?;
            if n > MAX_RECORDS as u64 || recorded > n {
                return Err(invalid());
            }
            let p = b + 2 * s.id + 8;
            for index in 0..recorded.min(MAX_BITMAP_IMAGES as u64) {
                let id = s.num(p + index * s.id, s.id)?;
                if id != 0 {
                    arrays.entry(id).or_default().push(index as u32 + 1);
                }
            }
            Ok(())
        })?;
        if !found {
            return Err(invalid());
        }
        let mut images = Vec::new();
        let mut total = 0u64;
        self.heap(|s, tag, b, _| {
            if tag != 0x23 {
                return Ok(());
            }
            let id = s.num(b, s.id)?;
            let Some(indexes) = arrays.get(&id) else {
                return Ok(());
            };
            let p = b + s.id + 4;
            let length = s.num(p, 4)?;
            if s.num(p + 4, 1)? != 8 || length > MAX_IMAGE_BYTES {
                return Ok(());
            }
            let payload = p + 5;
            let Some((width, height)) = s.png(payload, length)? else {
                return Ok(());
            };
            for index in indexes {
                if total.saturating_add(length) > MAX_TOTAL_BYTES {
                    return Ok(());
                }
                let path = directory.join(format!("bitmap-{index}.png"));
                let mut options = OpenOptions::new();
                options.write(true).create_new(true);
                #[cfg(unix)]
                {
                    use std::os::unix::fs::OpenOptionsExt;
                    options.mode(0o600);
                }
                let mut output = options.open(&path)?;
                s.file.seek(SeekFrom::Start(payload))?;
                if io::copy(&mut (&mut s.file).take(length), &mut output)? != length {
                    return Err(invalid());
                }
                output.flush()?;
                let sha256 = cache::sha256_file(&path).map_err(|_| invalid())?;
                images.push(BitmapImage {
                    index: *index,
                    array_id: format!("0x{id:x}"),
                    width,
                    height,
                    png_bytes: length,
                    sha256,
                    path,
                });
                total += length;
            }
            Ok(())
        })?;
        images.sort_by_key(|i| i.index);
        let omitted = recorded.saturating_sub(images.len() as u64);
        Ok(BitmapDump {
            source_digest: String::new(),
            recorded,
            discovered,
            omitted,
            images,
        })
    }
    fn png(&mut self, offset: u64, length: u64) -> io::Result<Option<(u32, u32)>> {
        if length < 45 || self.bytes(offset, 8)? != b"\x89PNG\r\n\x1a\n" {
            return Ok(None);
        }
        let end = offset + length;
        let mut p = offset + 8;
        let (mut width, mut height) = (0, 0);
        let mut idat = false;
        while p + 12 <= end {
            let n = self.num(p, 4)?;
            if n > MAX_IMAGE_BYTES || p + 12 + n > end {
                return Ok(None);
            }
            let ty = self.bytes(p + 4, 4)?;
            let stored = self.num(p + 8 + n, 4)? as u32;
            let mut crc = 0xffff_ffffu32;
            let mut cursor = p + 4;
            let mut remaining = n + 4;
            while remaining > 0 {
                let count = remaining.min(65536) as usize;
                for byte in self.bytes(cursor, count)? {
                    crc = (crc >> 8) ^ CRC[((crc ^ byte as u32) & 255) as usize];
                }
                cursor += count as u64;
                remaining -= count as u64;
            }
            if !crc != stored {
                return Ok(None);
            }
            if p == offset + 8 {
                if ty != b"IHDR" || n != 13 {
                    return Ok(None);
                }
                width = self.num(p + 8, 4)? as u32;
                height = self.num(p + 12, 4)? as u32;
                if width == 0
                    || height == 0
                    || width > 16384
                    || height > 16384
                    || u64::from(width) * u64::from(height) > 16_777_216
                {
                    return Ok(None);
                }
            }
            if ty == b"IDAT" {
                idat = true;
            }
            p += n + 12;
            if ty == b"IEND" {
                return Ok((p == end && n == 0 && idat).then_some((width, height)));
            }
        }
        Ok(None)
    }
}
const fn crc_table() -> [u32; 256] {
    let mut out = [0; 256];
    let mut i = 0;
    while i < 256 {
        let mut c = i as u32;
        let mut bit = 0;
        while bit < 8 {
            c = if c & 1 != 0 {
                0xedb88320 ^ (c >> 1)
            } else {
                c >> 1
            };
            bit += 1;
        }
        out[i] = c;
        i += 1;
    }
    out
}
const CRC: [u32; 256] = crc_table();

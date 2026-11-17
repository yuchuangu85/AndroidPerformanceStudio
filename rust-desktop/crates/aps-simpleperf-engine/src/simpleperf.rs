use std::collections::BTreeMap;
use std::fmt;
use std::io::{self, Read};

pub const MAGIC: &[u8; 10] = b"SIMPLEPERF";
pub const SUPPORTED_VERSION: u16 = 1;
pub const MAX_RECORD_BYTES: usize = 16 * 1024 * 1024;
pub const MAX_STREAM_BYTES: usize = 256 * 1024 * 1024;
pub const MAX_CALLCHAIN_FRAMES: usize = 512;
pub const MAX_RECORDS: usize = 1_000_000;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SimpleperfStream {
    pub version: u16,
    pub records: Vec<Record>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Record {
    Sample(Sample),
    Lost { sample_count: u64, lost_count: u64 },
    File(FileRecord),
    Thread(ThreadRecord),
    MetaInfo(MetaInfo),
    ContextSwitch(ContextSwitch),
    Unknown,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Sample {
    pub time: u64,
    pub thread_id: i32,
    pub event_count: u64,
    pub event_type_id: u32,
    pub callchain: Vec<Frame>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Frame {
    pub virtual_address: u64,
    pub file_id: u32,
    pub symbol_id: i32,
    pub execution_type: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FileRecord {
    pub id: u32,
    pub path: String,
    pub symbols: Vec<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ThreadRecord {
    pub thread_id: u32,
    pub process_id: u32,
    pub name: String,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MetaInfo {
    pub event_types: Vec<String>,
    pub app_package_name: Option<String>,
    pub app_type: Option<String>,
    pub android_sdk_version: Option<String>,
    pub android_build_type: Option<String>,
    pub trace_offcpu: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ContextSwitch {
    pub switch_on: bool,
    pub time: u64,
    pub thread_id: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SimpleperfError(pub String);

impl fmt::Display for SimpleperfError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(&self.0)
    }
}

impl std::error::Error for SimpleperfError {}

pub fn parse_stream(bytes: &[u8]) -> Result<SimpleperfStream, SimpleperfError> {
    parse_stream_with_limit(bytes, MAX_STREAM_BYTES)
}

pub fn parse_stream_with_limit(
    bytes: &[u8],
    limit: usize,
) -> Result<SimpleperfStream, SimpleperfError> {
    if bytes.len() > limit {
        return Err(SimpleperfError(format!(
            "simpleperf stream exceeds input limit {limit} bytes"
        )));
    }
    parse_reader_with_limit(&mut &bytes[..], limit)
}

/// Reads one bounded protobuf record at a time, reusing its encoded buffer.
/// Decoded records remain in memory so lookups may follow samples.
pub fn parse_reader_with_limit(
    reader: &mut impl Read,
    limit: usize,
) -> Result<SimpleperfStream, SimpleperfError> {
    let mut reader = BoundedReader {
        reader,
        position: 0,
        limit,
    };
    let mut magic = [0; MAGIC.len()];
    reader.exact(&mut magic, "SIMPLEPERF magic")?;
    if &magic != MAGIC {
        return Err(SimpleperfError("invalid SIMPLEPERF magic".into()));
    }
    let mut version = [0; 2];
    reader.exact(&mut version, "version")?;
    let version = u16::from_le_bytes(version);
    if version != SUPPORTED_VERSION {
        return Err(SimpleperfError(format!(
            "unsupported SIMPLEPERF version {version}"
        )));
    }

    let mut records = Vec::new();
    let mut payload = Vec::new();
    loop {
        let mut size = [0; 4];
        reader.exact(&mut size, "record length")?;
        let size = u32::from_le_bytes(size) as usize;
        if size == 0 {
            reader.require_eof()?;
            break;
        }
        if size > MAX_RECORD_BYTES {
            return Err(SimpleperfError(format!(
                "record is {size} bytes; limit is {MAX_RECORD_BYTES}"
            )));
        }
        if records.len() >= MAX_RECORDS {
            return Err(SimpleperfError("record count exceeds 1000000".into()));
        }
        reader.check_budget(size)?;
        payload
            .try_reserve_exact(size.saturating_sub(payload.len()))
            .map_err(|_| SimpleperfError("cannot allocate record buffer".into()))?;
        payload.resize(size, 0);
        reader.exact(&mut payload, "record payload")?;
        records.push(parse_record(&payload)?);
    }
    Ok(SimpleperfStream { version, records })
}

struct BoundedReader<'a, R> {
    reader: &'a mut R,
    position: usize,
    limit: usize,
}

impl<R: Read> BoundedReader<'_, R> {
    fn check_budget(&self, size: usize) -> Result<(), SimpleperfError> {
        if size > self.limit.saturating_sub(self.position) {
            return Err(SimpleperfError(format!(
                "simpleperf stream exceeds input limit {} bytes",
                self.limit
            )));
        }
        Ok(())
    }

    fn exact(&mut self, bytes: &mut [u8], label: &str) -> Result<(), SimpleperfError> {
        self.check_budget(bytes.len())?;
        self.reader.read_exact(bytes).map_err(|error| {
            let message = if error.kind() == io::ErrorKind::UnexpectedEof {
                format!("truncated {label}")
            } else {
                format!("read {label}: {error}")
            };
            SimpleperfError(format!("{message} at byte {}", self.position))
        })?;
        self.position += bytes.len();
        Ok(())
    }

    fn require_eof(&mut self) -> Result<(), SimpleperfError> {
        // One-byte probe also detects growth beyond a file's metadata/byte cap.
        loop {
            match self.reader.read(&mut [0]) {
                Ok(0) => return Ok(()),
                Ok(_) => {
                    return Err(SimpleperfError(
                        "trailing bytes after SIMPLEPERF terminator".into(),
                    ));
                }
                Err(error) if error.kind() == io::ErrorKind::Interrupted => continue,
                Err(error) => return Err(SimpleperfError(format!("read terminator EOF: {error}"))),
            }
        }
    }
}

fn parse_record(bytes: &[u8]) -> Result<Record, SimpleperfError> {
    let mut cursor = Cursor::new(bytes, 0);
    let mut record = Record::Unknown;
    while cursor.position < bytes.len() {
        let (field, wire) = cursor.key()?;
        if field <= 6 && wire == 2 {
            let nested = cursor.bytes("record value")?;
            record = match field {
                1 => Record::Sample(parse_sample(nested)?),
                2 => {
                    let (sample_count, lost_count) = parse_lost(nested)?;
                    Record::Lost {
                        sample_count,
                        lost_count,
                    }
                }
                3 => Record::File(parse_file(nested)?),
                4 => Record::Thread(parse_thread(nested)?),
                5 => Record::MetaInfo(parse_meta_info(nested)?),
                6 => Record::ContextSwitch(parse_context_switch(nested)?),
                _ => unreachable!(),
            };
        } else {
            cursor.skip(wire)?;
        }
    }
    Ok(record)
}

fn parse_sample(bytes: &[u8]) -> Result<Sample, SimpleperfError> {
    let mut cursor = Cursor::new(bytes, 0);
    let mut sample = Sample {
        time: 0,
        thread_id: 0,
        event_count: 0,
        event_type_id: 0,
        callchain: Vec::new(),
    };
    while cursor.position < bytes.len() {
        let (field, wire) = cursor.key()?;
        match (field, wire) {
            (1, 0) => sample.time = cursor.varint("sample time")?,
            (2, 0) => sample.thread_id = cursor.varint("thread id")? as i32,
            (3, 2) => {
                if sample.callchain.len() >= MAX_CALLCHAIN_FRAMES {
                    return Err(cursor.error("callchain exceeds 512 frames"));
                }
                sample
                    .callchain
                    .push(parse_frame(cursor.bytes("callchain entry")?)?);
            }
            (4, 0) => sample.event_count = cursor.varint("event count")?,
            (5, 0) => sample.event_type_id = cursor.varint("event type id")? as u32,
            _ => cursor.skip(wire)?,
        }
    }
    Ok(sample)
}

fn parse_frame(bytes: &[u8]) -> Result<Frame, SimpleperfError> {
    let mut cursor = Cursor::new(bytes, 0);
    let mut frame = Frame {
        virtual_address: 0,
        file_id: 0,
        symbol_id: -1,
        execution_type: 0,
    };
    while cursor.position < bytes.len() {
        let (field, wire) = cursor.key()?;
        match (field, wire) {
            (1, 0) => frame.virtual_address = cursor.varint("frame address")?,
            (2, 0) => frame.file_id = cursor.varint("frame file id")? as u32,
            (3, 0) => frame.symbol_id = cursor.varint("frame symbol id")? as i32,
            (4, 0) => frame.execution_type = cursor.varint("frame execution type")? as u32,
            _ => cursor.skip(wire)?,
        }
    }
    Ok(frame)
}

fn parse_lost(bytes: &[u8]) -> Result<(u64, u64), SimpleperfError> {
    let mut cursor = Cursor::new(bytes, 0);
    let mut sample_count = 0;
    let mut lost_count = 0;
    while cursor.position < bytes.len() {
        let (field, wire) = cursor.key()?;
        match (field, wire) {
            (1, 0) => sample_count = cursor.varint("lost sample count")?,
            (2, 0) => lost_count = cursor.varint("lost count")?,
            _ => cursor.skip(wire)?,
        }
    }
    Ok((sample_count, lost_count))
}

fn parse_file(bytes: &[u8]) -> Result<FileRecord, SimpleperfError> {
    let mut cursor = Cursor::new(bytes, 0);
    let mut record = FileRecord {
        id: 0,
        path: String::new(),
        symbols: Vec::new(),
    };
    while cursor.position < bytes.len() {
        let (field, wire) = cursor.key()?;
        match (field, wire) {
            (1, 0) => record.id = cursor.varint("file id")? as u32,
            (2, 2) => record.path = cursor.string("file path")?,
            (3, 2) => record.symbols.push(cursor.string("symbol")?),
            _ => cursor.skip(wire)?,
        }
    }
    Ok(record)
}

fn parse_thread(bytes: &[u8]) -> Result<ThreadRecord, SimpleperfError> {
    let mut cursor = Cursor::new(bytes, 0);
    let mut record = ThreadRecord {
        thread_id: 0,
        process_id: 0,
        name: String::new(),
    };
    while cursor.position < bytes.len() {
        let (field, wire) = cursor.key()?;
        match (field, wire) {
            (1, 0) => record.thread_id = cursor.varint("thread id")? as u32,
            (2, 0) => record.process_id = cursor.varint("process id")? as u32,
            (3, 2) => record.name = cursor.string("thread name")?,
            _ => cursor.skip(wire)?,
        }
    }
    Ok(record)
}

fn parse_meta_info(bytes: &[u8]) -> Result<MetaInfo, SimpleperfError> {
    let mut cursor = Cursor::new(bytes, 0);
    let mut record = MetaInfo {
        event_types: Vec::new(),
        app_package_name: None,
        app_type: None,
        android_sdk_version: None,
        android_build_type: None,
        trace_offcpu: false,
    };
    while cursor.position < bytes.len() {
        let (field, wire) = cursor.key()?;
        match (field, wire) {
            (1, 2) => record.event_types.push(cursor.string("event type")?),
            (2, 2) => record.app_package_name = Some(cursor.string("package name")?),
            (3, 2) => record.app_type = Some(cursor.string("app type")?),
            (4, 2) => record.android_sdk_version = Some(cursor.string("sdk version")?),
            (5, 2) => record.android_build_type = Some(cursor.string("build type")?),
            (6, 0) => record.trace_offcpu = cursor.varint("trace offcpu")? != 0,
            _ => cursor.skip(wire)?,
        }
    }
    Ok(record)
}

fn parse_context_switch(bytes: &[u8]) -> Result<ContextSwitch, SimpleperfError> {
    let mut cursor = Cursor::new(bytes, 0);
    let mut record = ContextSwitch {
        switch_on: false,
        time: 0,
        thread_id: 0,
    };
    while cursor.position < bytes.len() {
        let (field, wire) = cursor.key()?;
        match (field, wire) {
            (1, 0) => record.switch_on = cursor.varint("switch on")? != 0,
            (2, 0) => record.time = cursor.varint("context switch time")?,
            (3, 0) => record.thread_id = cursor.varint("context switch thread id")? as u32,
            _ => cursor.skip(wire)?,
        }
    }
    Ok(record)
}

struct Cursor<'a> {
    bytes: &'a [u8],
    position: usize,
    base_offset: usize,
}

impl<'a> Cursor<'a> {
    fn new(bytes: &'a [u8], base_offset: usize) -> Self {
        Self {
            bytes,
            position: 0,
            base_offset,
        }
    }

    fn error(&self, message: impl Into<String>) -> SimpleperfError {
        SimpleperfError(format!(
            "{} at byte {}",
            message.into(),
            self.base_offset + self.position
        ))
    }

    fn take(&mut self, length: usize, label: &str) -> Result<&'a [u8], SimpleperfError> {
        let end = self
            .position
            .checked_add(length)
            .ok_or_else(|| self.error("length overflow"))?;
        let bytes = self
            .bytes
            .get(self.position..end)
            .ok_or_else(|| self.error(format!("truncated {label}")))?;
        self.position = end;
        Ok(bytes)
    }

    fn varint(&mut self, label: &str) -> Result<u64, SimpleperfError> {
        let mut value = 0u64;
        for shift in (0..70).step_by(7) {
            let byte = self.take(1, label)?[0];
            if shift == 63 && byte > 1 {
                return Err(self.error(format!("overflowing {label} varint")));
            }
            value |= ((byte & 0x7f) as u64) << shift;
            if byte & 0x80 == 0 {
                return Ok(value);
            }
        }
        Err(self.error(format!("invalid {label} varint")))
    }

    fn key(&mut self) -> Result<(u32, u8), SimpleperfError> {
        let key = self.varint("field key")?;
        let field = (key >> 3) as u32;
        let wire = (key & 7) as u8;
        if field == 0 || key >> 3 > 0x1fff_ffff {
            return Err(self.error("protobuf field number is zero"));
        }
        Ok((field, wire))
    }

    fn bytes(&mut self, label: &str) -> Result<&'a [u8], SimpleperfError> {
        let length = self.varint(label)? as usize;
        self.take(length, label)
    }

    fn string(&mut self, label: &str) -> Result<String, SimpleperfError> {
        let bytes = self.bytes(label)?;
        String::from_utf8(bytes.to_vec())
            .map_err(|_| self.error(format!("invalid UTF-8 in {label}")))
    }

    fn skip(&mut self, wire: u8) -> Result<(), SimpleperfError> {
        match wire {
            0 => {
                self.varint("unknown field")?;
            }
            1 => {
                self.take(8, "fixed64")?;
            }
            2 => {
                let length = self.varint("unknown field length")? as usize;
                self.take(length, "unknown field")?;
            }
            5 => {
                self.take(4, "fixed32")?;
            }
            _ => return Err(self.error(format!("unsupported protobuf wire type {wire}"))),
        }
        Ok(())
    }
}

pub fn symbol_name(files: &BTreeMap<u32, FileRecord>, frame: &Frame) -> String {
    files
        .get(&frame.file_id)
        .and_then(|file| {
            usize::try_from(frame.symbol_id)
                .ok()
                .and_then(|id| file.symbols.get(id))
        })
        .cloned()
        .unwrap_or_else(|| "<unknown-symbol>".into())
}

pub fn file_path(files: &BTreeMap<u32, FileRecord>, frame: &Frame) -> String {
    files
        .get(&frame.file_id)
        .map(|file| file.path.clone())
        .unwrap_or_else(|| format!("<unknown-file:{}>", frame.file_id))
}

#[cfg(test)]
mod tests {
    use super::*;

    fn varint(mut value: u64) -> Vec<u8> {
        let mut bytes = Vec::new();
        loop {
            let mut byte = (value & 0x7f) as u8;
            value >>= 7;
            if value != 0 {
                byte |= 0x80;
            }
            bytes.push(byte);
            if value == 0 {
                return bytes;
            }
        }
    }
    fn field(number: u8, wire: u8, payload: &[u8]) -> Vec<u8> {
        let mut bytes = varint(((number as u64) << 3) | wire as u64);
        if wire == 2 {
            bytes.extend(varint(payload.len() as u64));
        }
        bytes.extend(payload);
        bytes
    }
    fn record(payload: &[u8]) -> Vec<u8> {
        let mut bytes = (payload.len() as u32).to_le_bytes().to_vec();
        bytes.extend(payload);
        bytes
    }
    fn stream(records: &[Vec<u8>]) -> Vec<u8> {
        let mut bytes = MAGIC.to_vec();
        bytes.extend(SUPPORTED_VERSION.to_le_bytes());
        for payload in records {
            bytes.extend(record(payload));
        }
        bytes.extend(0u32.to_le_bytes());
        bytes
    }

    #[test]
    fn parses_lookup_records_before_sample_and_unknown_fields() {
        let file = [
            field(1, 0, &varint(7)),
            field(2, 2, b"libfoo.so"),
            field(3, 2, b"main"),
        ]
        .concat();
        let thread = [
            field(1, 0, &varint(42)),
            field(2, 0, &varint(7)),
            field(3, 2, b"main"),
        ]
        .concat();
        let frame = [
            field(1, 0, &varint(16)),
            field(2, 0, &varint(7)),
            field(3, 0, &varint(0)),
            field(99, 0, &varint(1)),
        ]
        .concat();
        let sample = [
            field(1, 0, &varint(100)),
            field(2, 0, &varint(42)),
            field(3, 2, &frame),
            field(4, 0, &varint(3)),
        ]
        .concat();
        let bytes = stream(&[
            field(3, 2, &file),
            field(4, 2, &thread),
            field(1, 2, &sample),
        ]);
        let parsed = parse_stream(&bytes).unwrap();
        assert_eq!(parsed.version, 1);
        assert!(matches!(parsed.records[0], Record::File(_)));
        assert!(matches!(
            parsed.records[2],
            Record::Sample(Sample { event_count: 3, .. })
        ));
    }

    #[test]
    fn rejects_bad_magic_version_and_truncation() {
        assert!(parse_stream(b"bad").unwrap_err().0.contains("magic"));
        let mut version = MAGIC.to_vec();
        version.extend(2u16.to_le_bytes());
        assert!(parse_stream(&version).unwrap_err().0.contains("version"));
        let mut truncated = MAGIC.to_vec();
        truncated.extend(1u16.to_le_bytes());
        truncated.extend(4u32.to_le_bytes());
        assert!(parse_stream(&truncated)
            .unwrap_err()
            .0
            .contains("record payload"));
    }
}

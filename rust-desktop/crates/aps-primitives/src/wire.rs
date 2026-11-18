//! Version-one typed values. The same bounds apply to decoding and encoding.
use std::collections::BTreeMap;
use std::io::{Read, Write};

pub const VERSION: i64 = 1;
pub const MAX_FRAME: usize = 32 * 1024 * 1024;
pub const MAX_DEPTH: usize = 64;
pub const MAX_VALUES: usize = 1_000_000;
pub const MAX_STRING: usize = 4 * 1024 * 1024;

#[derive(Clone, Debug, PartialEq)]
pub enum Value {
    Null,
    Bool(bool),
    Int(i64),
    String(String),
    List(Vec<Value>),
    Map(BTreeMap<String, Value>),
}

impl Value {
    pub fn map<const N: usize>(entries: [(&str, Value); N]) -> Self {
        Self::Map(
            entries
                .into_iter()
                .map(|(k, v)| (k.to_owned(), v))
                .collect(),
        )
    }
    pub fn get(&self, key: &str) -> Result<&Value, String> {
        self.as_map()?
            .get(key)
            .ok_or_else(|| format!("missing field {key}"))
    }
    pub fn as_map(&self) -> Result<&BTreeMap<String, Value>, String> {
        match self {
            Self::Map(v) => Ok(v),
            _ => Err("expected map".into()),
        }
    }
    pub fn as_str(&self) -> Result<&str, String> {
        match self {
            Self::String(v) => Ok(v),
            _ => Err("expected string".into()),
        }
    }
    pub fn as_int(&self) -> Result<i64, String> {
        match self {
            Self::Int(v) => Ok(*v),
            _ => Err("expected integer".into()),
        }
    }
    pub fn as_bool(&self) -> Result<bool, String> {
        match self {
            Self::Bool(v) => Ok(*v),
            _ => Err("expected boolean".into()),
        }
    }
    pub fn as_list(&self) -> Result<&[Value], String> {
        match self {
            Self::List(v) => Ok(v),
            _ => Err("expected list".into()),
        }
    }
    pub fn nullable_str(&self) -> Result<Option<&str>, String> {
        match self {
            Self::Null => Ok(None),
            _ => self.as_str().map(Some),
        }
    }
}

impl From<String> for Value {
    fn from(v: String) -> Self {
        Self::String(v)
    }
}
impl From<&str> for Value {
    fn from(v: &str) -> Self {
        Self::String(v.to_owned())
    }
}
impl From<bool> for Value {
    fn from(v: bool) -> Self {
        Self::Bool(v)
    }
}
impl From<i64> for Value {
    fn from(v: i64) -> Self {
        Self::Int(v)
    }
}
impl From<u32> for Value {
    fn from(v: u32) -> Self {
        Self::Int(v as i64)
    }
}
impl<T: Into<Value>> From<Option<T>> for Value {
    fn from(v: Option<T>) -> Self {
        v.map(Into::into).unwrap_or(Self::Null)
    }
}

pub fn number(v: u64) -> Result<Value, String> {
    i64::try_from(v)
        .map(Value::Int)
        .map_err(|_| "metric exceeds signed i64".into())
}

fn budget(depth: usize, remaining: &mut usize) -> Result<(), String> {
    if depth > MAX_DEPTH {
        return Err("typed value nesting limit exceeded".into());
    }
    *remaining = remaining
        .checked_sub(1)
        .ok_or("typed value count limit exceeded")?;
    Ok(())
}

struct Decoder<'a> {
    bytes: &'a [u8],
    position: usize,
    remaining: usize,
}
impl<'a> Decoder<'a> {
    fn take(&mut self, n: usize) -> Result<&'a [u8], String> {
        let end = self.position.checked_add(n).ok_or("length overflow")?;
        let value = self
            .bytes
            .get(self.position..end)
            .ok_or("truncated typed value")?;
        self.position = end;
        Ok(value)
    }
    fn count(&mut self) -> Result<usize, String> {
        let b = self.take(4)?;
        Ok(u32::from_be_bytes([b[0], b[1], b[2], b[3]]) as usize)
    }
    fn text(&mut self) -> Result<String, String> {
        let count = self.count()?;
        if count > MAX_STRING {
            return Err("string exceeds 4 MiB".into());
        }
        let s = std::str::from_utf8(self.take(count)?).map_err(|_| "invalid UTF-8")?;
        Ok(s.to_owned())
    }
    fn value(&mut self, depth: usize) -> Result<Value, String> {
        budget(depth, &mut self.remaining)?;
        match self.take(1)?[0] {
            0 => Ok(Value::Null),
            1 => Ok(Value::Bool(false)),
            2 => Ok(Value::Bool(true)),
            3 => {
                let b = self.take(8)?;
                Ok(Value::Int(i64::from_be_bytes([
                    b[0], b[1], b[2], b[3], b[4], b[5], b[6], b[7],
                ])))
            }
            4 => self.text().map(Value::String),
            5 => {
                let n = self.count()?;
                if n > self.remaining || n > self.bytes.len() - self.position {
                    return Err("excessive list count".into());
                }
                let mut values = Vec::new();
                values
                    .try_reserve(n)
                    .map_err(|_| "cannot allocate wire list")?;
                for _ in 0..n {
                    values.push(self.value(depth + 1)?);
                }
                Ok(Value::List(values))
            }
            6 => {
                let n = self.count()?;
                if n > self.remaining || n > (self.bytes.len() - self.position) / 5 {
                    return Err("excessive map count".into());
                }
                let mut values = BTreeMap::new();
                for _ in 0..n {
                    let key = self.text()?;
                    if values.contains_key(&key) {
                        return Err("duplicate map key".into());
                    }
                    values.insert(key, self.value(depth + 1)?);
                }
                Ok(Value::Map(values))
            }
            _ => Err("unknown typed value tag".into()),
        }
    }
}

pub fn decode(bytes: &[u8]) -> Result<Value, String> {
    if bytes.len() > MAX_FRAME {
        return Err("frame exceeds 32 MiB".into());
    }
    let mut decoder = Decoder {
        bytes,
        position: 0,
        remaining: MAX_VALUES,
    };
    let value = decoder.value(0)?;
    if decoder.position != bytes.len() {
        return Err("trailing typed value bytes".into());
    }
    Ok(value)
}

fn append(bytes: &mut Vec<u8>, value: &[u8]) -> Result<(), String> {
    if value.len() > MAX_FRAME.saturating_sub(bytes.len()) {
        return Err("response exceeds 32 MiB; result is not truncated".into());
    }
    bytes
        .try_reserve(value.len())
        .map_err(|_| "cannot allocate wire response")?;
    bytes.extend_from_slice(value);
    Ok(())
}
fn text(bytes: &mut Vec<u8>, value: &str) -> Result<(), String> {
    if value.len() > MAX_STRING {
        return Err("string exceeds 4 MiB; result is not truncated".into());
    }
    let n = u32::try_from(value.len()).map_err(|_| "string too long")?;
    append(bytes, &n.to_be_bytes())?;
    append(bytes, value.as_bytes())
}
fn encode_value(
    value: &Value,
    bytes: &mut Vec<u8>,
    depth: usize,
    remaining: &mut usize,
) -> Result<(), String> {
    budget(depth, remaining)?;
    match value {
        Value::Null => append(bytes, &[0]),
        Value::Bool(false) => append(bytes, &[1]),
        Value::Bool(true) => append(bytes, &[2]),
        Value::Int(v) => {
            append(bytes, &[3])?;
            append(bytes, &v.to_be_bytes())
        }
        Value::String(v) => {
            append(bytes, &[4])?;
            text(bytes, v)
        }
        Value::List(v) => {
            if v.len() > *remaining {
                return Err("excessive list count".into());
            }
            append(bytes, &[5])?;
            append(bytes, &(v.len() as u32).to_be_bytes())?;
            for item in v {
                encode_value(item, bytes, depth + 1, remaining)?;
            }
            Ok(())
        }
        Value::Map(v) => {
            if v.len() > *remaining {
                return Err("excessive map count".into());
            }
            append(bytes, &[6])?;
            append(bytes, &(v.len() as u32).to_be_bytes())?;
            for (key, item) in v {
                text(bytes, key)?;
                encode_value(item, bytes, depth + 1, remaining)?;
            }
            Ok(())
        }
    }
}
pub fn encode(value: &Value) -> Result<Vec<u8>, String> {
    let mut bytes = Vec::new();
    let mut remaining = MAX_VALUES;
    encode_value(value, &mut bytes, 0, &mut remaining)?;
    Ok(bytes)
}

pub fn read_frame(reader: &mut impl Read) -> Result<Option<Vec<u8>>, String> {
    let mut header = [0u8; 4];
    let mut count = 0;
    while count < 4 {
        match reader.read(&mut header[count..]) {
            Ok(0) if count == 0 => return Ok(None),
            Ok(0) => return Err("truncated frame header".into()),
            Ok(n) => count += n,
            Err(e) if e.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(e) => return Err(format!("read frame header: {e}")),
        }
    }
    let length = u32::from_be_bytes(header) as usize;
    if length == 0 || length > MAX_FRAME {
        return Err("invalid frame length (1..32 MiB required)".into());
    }
    let mut bytes = Vec::new();
    bytes
        .try_reserve_exact(length)
        .map_err(|_| "cannot allocate input frame")?;
    bytes.resize(length, 0);
    reader
        .read_exact(&mut bytes)
        .map_err(|e| format!("truncated frame body: {e}"))?;
    Ok(Some(bytes))
}

pub fn write_frame(writer: &mut impl Write, value: &Value) -> Result<(), String> {
    write_encoded(writer, &encode(value)?)
}
pub fn write_encoded(writer: &mut impl Write, bytes: &[u8]) -> Result<(), String> {
    if bytes.is_empty() || bytes.len() > MAX_FRAME {
        return Err("invalid output frame length".into());
    }
    writer
        .write_all(&(bytes.len() as u32).to_be_bytes())
        .and_then(|_| writer.write_all(bytes))
        .and_then(|_| writer.flush())
        .map_err(|e| format!("write frame: {e}"))
}

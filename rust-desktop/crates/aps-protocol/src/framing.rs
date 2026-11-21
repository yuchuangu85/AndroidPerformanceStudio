use crate::{wire, Request, Response, Value};
use std::io::{Read, Write};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FrameLimits {
    pub max_frame_bytes: usize,
    pub max_string_bytes: usize,
    pub max_values: usize,
    pub max_depth: usize,
}
impl Default for FrameLimits {
    fn default() -> Self {
        Self {
            max_frame_bytes: 4 * 1024 * 1024,
            max_string_bytes: 256 * 1024,
            max_values: 100_000,
            max_depth: 32,
        }
    }
}
impl FrameLimits {
    pub fn validate(&self) -> Result<(), String> {
        if self.max_frame_bytes == 0
            || self.max_frame_bytes > wire::MAX_FRAME
            || self.max_string_bytes == 0
            || self.max_string_bytes > wire::MAX_STRING
            || self.max_values == 0
            || self.max_values > wire::MAX_VALUES
            || self.max_depth > wire::MAX_DEPTH
        {
            return Err("frame limits must be nonzero and within the codec hard limits".into());
        }
        Ok(())
    }
    pub fn validate_value(&self, value: &Value) -> Result<(), String> {
        self.validate()?;
        let mut remaining = self.max_values;
        let mut remaining_bytes = self.max_frame_bytes;
        inspect(value, self, 0, &mut remaining, &mut remaining_bytes)
    }
}
fn consume_bytes(remaining: &mut usize, count: usize) -> Result<(), String> {
    *remaining = remaining
        .checked_sub(count)
        .ok_or("protocol encoded byte limit exceeded")?;
    Ok(())
}
fn inspect(
    value: &Value,
    limits: &FrameLimits,
    depth: usize,
    remaining: &mut usize,
    bytes: &mut usize,
) -> Result<(), String> {
    if depth > limits.max_depth {
        return Err("protocol value depth limit exceeded".into());
    }
    *remaining = remaining
        .checked_sub(1)
        .ok_or("protocol value count limit exceeded")?;
    consume_bytes(bytes, 1)?;
    match value {
        Value::Int(_) => consume_bytes(bytes, 8)?,
        Value::String(value) => {
            if value.len() > limits.max_string_bytes {
                return Err("protocol string limit exceeded".into());
            }
            consume_bytes(bytes, 4)?;
            consume_bytes(bytes, value.len())?;
        }
        Value::List(values) => {
            consume_bytes(bytes, 4)?;
            if values.len() > *remaining {
                return Err("protocol value count limit exceeded".into());
            }
            for value in values {
                inspect(value, limits, depth + 1, remaining, bytes)?;
            }
        }
        Value::Map(values) => {
            consume_bytes(bytes, 4)?;
            if values.len() > *remaining {
                return Err("protocol value count limit exceeded".into());
            }
            for (key, value) in values {
                if key.len() > limits.max_string_bytes {
                    return Err("protocol key string limit exceeded".into());
                }
                consume_bytes(bytes, 4)?;
                consume_bytes(bytes, key.len())?;
                inspect(value, limits, depth + 1, remaining, bytes)?;
            }
        }
        _ => {}
    }
    Ok(())
}

pub fn encode_value(value: &Value, limits: FrameLimits) -> Result<Vec<u8>, String> {
    limits.validate_value(value)?;
    let bytes = wire::encode(value)?;
    if bytes.len() > limits.max_frame_bytes {
        return Err("encoded frame exceeds configured byte limit".into());
    }
    Ok(bytes)
}
pub fn decode_value(bytes: &[u8], limits: FrameLimits) -> Result<Value, String> {
    limits.validate()?;
    if bytes.is_empty() || bytes.len() > limits.max_frame_bytes {
        return Err("frame body exceeds configured byte limit or is empty".into());
    }
    let value = wire::decode(bytes)?;
    limits.validate_value(&value)?;
    Ok(value)
}
fn read_value(reader: &mut impl Read, limits: FrameLimits) -> Result<Option<Value>, String> {
    limits.validate()?;
    let mut header = [0u8; 4];
    let mut filled = 0;
    while filled < 4 {
        match reader.read(&mut header[filled..]) {
            Ok(0) if filled == 0 => return Ok(None),
            Ok(0) => return Err("truncated protocol frame header".into()),
            Ok(count) => filled += count,
            Err(error) if error.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(_) => return Err("cannot read protocol frame header".into()),
        }
    }
    let length = u32::from_be_bytes(header) as usize;
    if length == 0 || length > limits.max_frame_bytes {
        return Err("protocol frame length outside configured byte limit".into());
    }
    let mut bytes = Vec::new();
    bytes
        .try_reserve_exact(length)
        .map_err(|_| "cannot allocate bounded protocol frame")?;
    bytes.resize(length, 0);
    reader
        .read_exact(&mut bytes)
        .map_err(|_| "truncated or unreadable protocol frame body")?;
    decode_value(&bytes, limits).map(Some)
}
fn write_value(writer: &mut impl Write, value: &Value, limits: FrameLimits) -> Result<(), String> {
    // Complete validation and encoding happen before the first write, so a
    // rejected envelope cannot leave a partial *successfully encoded* frame.
    // Physical I/O errors can still leave partial bytes; close that channel.
    let bytes = encode_value(value, limits)?;
    wire::write_encoded(writer, &bytes)
}
pub fn read_request(
    reader: &mut impl Read,
    limits: FrameLimits,
) -> Result<Option<Request>, String> {
    read_value(reader, limits)?
        .map(|value| Request::from_value(&value, limits))
        .transpose()
}
pub fn read_response(
    reader: &mut impl Read,
    limits: FrameLimits,
) -> Result<Option<Response>, String> {
    read_value(reader, limits)?
        .map(|value| Response::from_value(&value, limits))
        .transpose()
}
pub fn write_request(
    writer: &mut impl Write,
    request: &Request,
    limits: FrameLimits,
) -> Result<(), String> {
    request.validate(limits)?;
    write_value(writer, &request.to_value(), limits)
}
pub fn write_response(
    writer: &mut impl Write,
    response: &Response,
    limits: FrameLimits,
) -> Result<(), String> {
    response.validate(limits)?;
    write_value(writer, &response.to_value(), limits)
}

//! Shared strict JSON boundary: no dropped arrays/bools or duplicate-key ambiguity.
pub use serde_json::{Map, Number, Value};

use serde::de::{DeserializeSeed, Error as _, MapAccess, SeqAccess, Visitor};
use std::cell::Cell;
use std::fmt;
use std::fs::File;
use std::io::Read;
use std::path::Path;

#[derive(Debug, Clone, Copy)]
pub struct Limits {
    pub max_bytes: u64,
    pub max_depth: usize,
    pub max_nodes: usize,
    pub max_string_bytes: usize,
    pub max_collection_items: usize,
}

impl Default for Limits {
    fn default() -> Self {
        Self {
            max_bytes: 64 * 1024 * 1024,
            max_depth: 64,
            max_nodes: 1_000_000,
            max_string_bytes: 1024 * 1024,
            max_collection_items: 100_000,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum JsonError {
    InvalidLimits,
    Input,
    TooLarge,
    InvalidDocument,
    StructureLimit,
}

impl fmt::Display for JsonError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str(match self {
            Self::InvalidLimits => "JSON limits are invalid",
            Self::Input => "Cannot read selected JSON input",
            Self::TooLarge => "JSON byte budget exceeded",
            Self::InvalidDocument => {
                "JSON document is malformed, ambiguous or exceeds structure budget"
            }
            Self::StructureLimit => "JSON structure exceeds configured budget",
        })
    }
}
impl std::error::Error for JsonError {}

fn validate_limits(limits: Limits) -> Result<(), JsonError> {
    if limits.max_bytes == 0
        || limits.max_bytes > 512 * 1024 * 1024
        || limits.max_depth == 0
        || limits.max_depth > 64
        || limits.max_nodes == 0
        || limits.max_nodes > 4_000_000
        || limits.max_string_bytes == 0
        || limits.max_string_bytes > 16 * 1024 * 1024
        || limits.max_collection_items == 0
        || limits.max_collection_items > 1_000_000
    {
        return Err(JsonError::InvalidLimits);
    }
    Ok(())
}

pub fn read(path: &Path, limits: Limits) -> Result<Value, JsonError> {
    validate_limits(limits)?;
    let file = File::open(path).map_err(|_| JsonError::Input)?;
    let metadata = file.metadata().map_err(|_| JsonError::Input)?;
    if !metadata.is_file() {
        return Err(JsonError::Input);
    }
    if metadata.len() > limits.max_bytes {
        return Err(JsonError::TooLarge);
    }
    let mut bytes = Vec::new();
    file.take(limits.max_bytes + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| JsonError::Input)?;
    parse_slice(&bytes, limits)
}

pub fn parse_slice(bytes: &[u8], limits: Limits) -> Result<Value, JsonError> {
    validate_limits(limits)?;
    parse_validated_slice(bytes, limits, false)
}

/// Opt-in for nested hierarchy archives, where each node adds an object and a
/// children array. The ordinary read/parse/write APIs keep their 64-level cap.
/// All other strict budgets, including duplicate-key rejection, are unchanged.
pub fn parse_slice_with_extended_depth(bytes: &[u8], limits: Limits) -> Result<Value, JsonError> {
    if limits.max_depth > 320 {
        return Err(JsonError::InvalidLimits);
    }
    validate_limits(Limits {
        max_depth: limits.max_depth.min(64),
        ..limits
    })?;
    parse_validated_slice(bytes, limits, true)
}

fn parse_validated_slice(bytes: &[u8], limits: Limits, extended: bool) -> Result<Value, JsonError> {
    if bytes.len() as u64 > limits.max_bytes {
        return Err(JsonError::TooLarge);
    }
    let remaining = Cell::new(limits.max_nodes);
    let mut deserializer = serde_json::Deserializer::from_slice(bytes);
    if extended {
        // Seed checks the explicit depth budget before every value. Disable
        // serde_json's independent 128-container cap only on this bounded path.
        deserializer.disable_recursion_limit();
    }
    let value = Seed {
        limits,
        remaining: &remaining,
        depth: 0,
    }
    .deserialize(&mut deserializer)
    .map_err(|_| JsonError::InvalidDocument)?;
    deserializer.end().map_err(|_| JsonError::InvalidDocument)?;
    Ok(value)
}

pub fn to_vec(value: &Value, limits: Limits) -> Result<Vec<u8>, JsonError> {
    validate_limits(limits)?;
    let remaining = Cell::new(limits.max_nodes);
    check(value, limits, &remaining, 0)?;
    let mut bytes = LimitedWriter {
        bytes: Vec::new(),
        limit: limits.max_bytes,
    };
    serde_json::to_writer(&mut bytes, value).map_err(|_| JsonError::TooLarge)?;
    Ok(bytes.bytes)
}

fn check(
    value: &Value,
    limits: Limits,
    remaining: &Cell<usize>,
    depth: usize,
) -> Result<(), JsonError> {
    if depth > limits.max_depth || remaining.get() == 0 {
        return Err(JsonError::StructureLimit);
    }
    remaining.set(remaining.get() - 1);
    match value {
        Value::String(value) if value.len() > limits.max_string_bytes => {
            return Err(JsonError::StructureLimit);
        }
        Value::Array(values) => {
            if values.len() > limits.max_collection_items {
                return Err(JsonError::StructureLimit);
            }
            for value in values {
                check(value, limits, remaining, depth + 1)?;
            }
        }
        Value::Object(values) => {
            if values.len() > limits.max_collection_items {
                return Err(JsonError::StructureLimit);
            }
            for (key, value) in values {
                if key.len() > limits.max_string_bytes {
                    return Err(JsonError::StructureLimit);
                }
                check(value, limits, remaining, depth + 1)?;
            }
        }
        _ => {}
    }
    Ok(())
}

struct LimitedWriter {
    bytes: Vec<u8>,
    limit: u64,
}
impl std::io::Write for LimitedWriter {
    fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
        if (self.bytes.len() as u64).saturating_add(bytes.len() as u64) > self.limit {
            return Err(std::io::Error::other("JSON byte budget exceeded"));
        }
        self.bytes.extend_from_slice(bytes);
        Ok(bytes.len())
    }
    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

struct Seed<'a> {
    limits: Limits,
    remaining: &'a Cell<usize>,
    depth: usize,
}
impl<'de> DeserializeSeed<'de> for Seed<'_> {
    type Value = Value;
    fn deserialize<D: serde::Deserializer<'de>>(self, deserializer: D) -> Result<Value, D::Error> {
        if self.depth > self.limits.max_depth || self.remaining.get() == 0 {
            return Err(D::Error::custom("JSON structure budget exceeded"));
        }
        self.remaining.set(self.remaining.get() - 1);
        deserializer.deserialize_any(self)
    }
}

impl<'de> Visitor<'de> for Seed<'_> {
    type Value = Value;
    fn expecting(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str("bounded unambiguous JSON")
    }
    fn visit_bool<E: serde::de::Error>(self, value: bool) -> Result<Value, E> {
        Ok(Value::Bool(value))
    }
    fn visit_i64<E: serde::de::Error>(self, value: i64) -> Result<Value, E> {
        Ok(Value::Number(value.into()))
    }
    fn visit_u64<E: serde::de::Error>(self, value: u64) -> Result<Value, E> {
        Ok(Value::Number(value.into()))
    }
    fn visit_f64<E: serde::de::Error>(self, value: f64) -> Result<Value, E> {
        Number::from_f64(value)
            .map(Value::Number)
            .ok_or_else(|| E::custom("nonfinite JSON number"))
    }
    fn visit_str<E: serde::de::Error>(self, value: &str) -> Result<Value, E> {
        if value.len() > self.limits.max_string_bytes {
            return Err(E::custom("JSON string budget exceeded"));
        }
        Ok(Value::String(value.into()))
    }
    fn visit_string<E: serde::de::Error>(self, value: String) -> Result<Value, E> {
        if value.len() > self.limits.max_string_bytes {
            return Err(E::custom("JSON string budget exceeded"));
        }
        Ok(Value::String(value))
    }
    fn visit_none<E: serde::de::Error>(self) -> Result<Value, E> {
        Ok(Value::Null)
    }
    fn visit_unit<E: serde::de::Error>(self) -> Result<Value, E> {
        Ok(Value::Null)
    }
    fn visit_seq<A: SeqAccess<'de>>(self, mut sequence: A) -> Result<Value, A::Error> {
        let mut values = Vec::new();
        while let Some(value) = sequence.next_element_seed(Seed {
            limits: self.limits,
            remaining: self.remaining,
            depth: self.depth + 1,
        })? {
            if values.len() >= self.limits.max_collection_items {
                return Err(A::Error::custom("JSON collection budget exceeded"));
            }
            values.push(value);
        }
        Ok(Value::Array(values))
    }
    fn visit_map<A: MapAccess<'de>>(self, mut object: A) -> Result<Value, A::Error> {
        let mut values = Map::new();
        while let Some(key) = object.next_key::<String>()? {
            if key.len() > self.limits.max_string_bytes
                || values.len() >= self.limits.max_collection_items
            {
                return Err(A::Error::custom("JSON object budget exceeded"));
            }
            if values.contains_key(&key) {
                return Err(A::Error::custom("duplicate JSON key"));
            }
            let value = object.next_value_seed(Seed {
                limits: self.limits,
                remaining: self.remaining,
                depth: self.depth + 1,
            })?;
            values.insert(key, value);
        }
        Ok(Value::Object(values))
    }
}

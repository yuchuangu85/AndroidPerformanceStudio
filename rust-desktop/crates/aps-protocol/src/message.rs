use crate::{FrameLimits, Value};
use aps_contracts::{ApsError, ErrorCode, ProfilerSessionId, RequestId, SourceDigest};
use std::collections::BTreeMap;

pub const PROTOCOL_VERSION: i64 = 1;
pub const MAX_OPERATION_BYTES: usize = 64;
#[derive(Debug, Clone, PartialEq)]
pub struct Request {
    pub protocol_version: i64,
    pub request_id: RequestId,
    pub source_digest: SourceDigest,
    pub session_id: ProfilerSessionId,
    pub generation: u64,
    pub operation: String,
    pub args: Value,
}
impl Request {
    pub fn new(
        request_id: RequestId,
        source_digest: SourceDigest,
        session_id: ProfilerSessionId,
        generation: u64,
        operation: impl Into<String>,
        args: Value,
    ) -> Result<Self, String> {
        let value = Self {
            protocol_version: PROTOCOL_VERSION,
            request_id,
            source_digest,
            session_id,
            generation,
            operation: operation.into(),
            args,
        };
        value.validate(FrameLimits::default())?;
        Ok(value)
    }
    pub fn validate(&self, limits: FrameLimits) -> Result<(), String> {
        version_generation(self.protocol_version, self.generation)?;
        if self.operation.is_empty()
            || self.operation.len() > MAX_OPERATION_BYTES
            || !self
                .operation
                .bytes()
                .all(|byte| byte.is_ascii_alphanumeric() || b"._-".contains(&byte))
        {
            return Err("operation must contain 1..64 ASCII letters/digits or ._-".into());
        }
        self.args.as_map()?;
        limits.validate_value(&self.args)?;
        limits.validate_value(&self.to_value())
    }
    pub fn to_value(&self) -> Value {
        Value::map([
            ("protocolVersion", Value::Int(self.protocol_version)),
            ("requestId", self.request_id.as_str().into()),
            ("sourceDigest", self.source_digest.to_hex().into()),
            ("sessionId", self.session_id.as_str().into()),
            ("generation", Value::Int(self.generation as i64)),
            ("operation", self.operation.clone().into()),
            ("args", self.args.clone()),
        ])
    }
    pub fn from_value(value: &Value, limits: FrameLimits) -> Result<Self, String> {
        limits.validate_value(value)?;
        let map = exact_fields(
            value,
            &[
                "protocolVersion",
                "requestId",
                "sourceDigest",
                "sessionId",
                "generation",
                "operation",
                "args",
            ],
        )?;
        let request = Self {
            protocol_version: integer(map, "protocolVersion")?,
            request_id: RequestId::new(text(map, "requestId")?)?,
            source_digest: SourceDigest::from_hex(text(map, "sourceDigest")?)?,
            session_id: ProfilerSessionId::new(text(map, "sessionId")?)?,
            generation: unsigned(map, "generation")?,
            operation: text(map, "operation")?.into(),
            args: field(map, "args")?.clone(),
        };
        request.validate(limits)?;
        Ok(request)
    }
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ResponseStatus {
    Ok,
    Error,
    Cancelled,
}
impl ResponseStatus {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Ok => "ok",
            Self::Error => "error",
            Self::Cancelled => "cancelled",
        }
    }
}
#[derive(Debug, Clone, PartialEq)]
pub struct Response {
    pub protocol_version: i64,
    pub request_id: RequestId,
    pub source_digest: SourceDigest,
    pub session_id: ProfilerSessionId,
    pub generation: u64,
    pub status: ResponseStatus,
    pub payload: Value,
    pub error: Option<ApsError>,
}
impl Response {
    fn base(
        request: &Request,
        status: ResponseStatus,
        payload: Value,
        error: Option<ApsError>,
    ) -> Result<Self, String> {
        request.validate(FrameLimits::default())?;
        let response = Self {
            protocol_version: PROTOCOL_VERSION,
            request_id: request.request_id.clone(),
            source_digest: request.source_digest,
            session_id: request.session_id.clone(),
            generation: request.generation,
            status,
            payload,
            error,
        };
        response.validate(FrameLimits::default())?;
        Ok(response)
    }
    pub fn ok(request: &Request, payload: Value) -> Result<Self, String> {
        Self::base(request, ResponseStatus::Ok, payload, None)
    }
    pub fn error(request: &Request, error: ApsError) -> Result<Self, String> {
        Self::base(request, ResponseStatus::Error, Value::Null, Some(error))
    }
    /// A worker's cooperative cancellation acknowledgement, not proof that an
    /// OS process has exited or temporary files have been cleaned up.
    pub fn cancelled(request: &Request) -> Result<Self, String> {
        Self::base(
            request,
            ResponseStatus::Cancelled,
            Value::Null,
            Some(ApsError::new(
                ErrorCode::Cancelled,
                "Operation cancelled",
                false,
            )?),
        )
    }
    pub fn validate(&self, limits: FrameLimits) -> Result<(), String> {
        version_generation(self.protocol_version, self.generation)?;
        limits.validate_value(&self.payload)?;
        match (self.status, &self.error, &self.payload) {
            (ResponseStatus::Ok, None, _) => {}
            (ResponseStatus::Error, Some(error), Value::Null) => error.validate()?,
            (ResponseStatus::Cancelled, Some(error), Value::Null)
                if error.code == ErrorCode::Cancelled =>
            {
                error.validate()?
            }
            _ => return Err("response status, payload and error are inconsistent".into()),
        }
        limits.validate_value(&self.to_value())
    }
    pub fn validate_for(&self, request: &Request) -> Result<(), String> {
        request.validate(FrameLimits::default())?;
        self.validate(FrameLimits::default())?;
        if self.request_id != request.request_id {
            return Err("response request ID mismatch".into());
        }
        if self.session_id != request.session_id {
            return Err("response profiler session mismatch".into());
        }
        if self.source_digest != request.source_digest {
            return Err("response source digest mismatch".into());
        }
        if self.generation != request.generation {
            return Err("stale or unexpected response generation".into());
        }
        Ok(())
    }
    pub fn to_value(&self) -> Value {
        let error = self
            .error
            .as_ref()
            .map(|error| {
                Value::map([
                    ("code", error.code.as_str().into()),
                    ("message", error.message.clone().into()),
                    ("retryable", error.retryable.into()),
                ])
            })
            .unwrap_or(Value::Null);
        Value::map([
            ("protocolVersion", Value::Int(self.protocol_version)),
            ("requestId", self.request_id.as_str().into()),
            ("sourceDigest", self.source_digest.to_hex().into()),
            ("sessionId", self.session_id.as_str().into()),
            ("generation", Value::Int(self.generation as i64)),
            ("status", self.status.as_str().into()),
            ("payload", self.payload.clone()),
            ("error", error),
        ])
    }
    pub fn from_value(value: &Value, limits: FrameLimits) -> Result<Self, String> {
        limits.validate_value(value)?;
        let map = exact_fields(
            value,
            &[
                "protocolVersion",
                "requestId",
                "sourceDigest",
                "sessionId",
                "generation",
                "status",
                "payload",
                "error",
            ],
        )?;
        let error = match field(map, "error")? {
            Value::Null => None,
            value => {
                let fields = exact_fields(value, &["code", "message", "retryable"])?;
                Some(ApsError::new(
                    ErrorCode::from_code(text(fields, "code")?)?,
                    text(fields, "message")?,
                    field(fields, "retryable")?.as_bool()?,
                )?)
            }
        };
        let response = Self {
            protocol_version: integer(map, "protocolVersion")?,
            request_id: RequestId::new(text(map, "requestId")?)?,
            source_digest: SourceDigest::from_hex(text(map, "sourceDigest")?)?,
            session_id: ProfilerSessionId::new(text(map, "sessionId")?)?,
            generation: unsigned(map, "generation")?,
            status: match text(map, "status")? {
                "ok" => ResponseStatus::Ok,
                "error" => ResponseStatus::Error,
                "cancelled" => ResponseStatus::Cancelled,
                _ => return Err("unknown response status".into()),
            },
            payload: field(map, "payload")?.clone(),
            error,
        };
        response.validate(limits)?;
        Ok(response)
    }
}
fn version_generation(version: i64, generation: u64) -> Result<(), String> {
    if version != PROTOCOL_VERSION {
        return Err("unsupported protocolVersion (expected 1)".into());
    }
    if generation > i64::MAX as u64 {
        return Err("generation exceeds signed i64 wire range".into());
    }
    Ok(())
}
fn exact_fields<'a>(
    value: &'a Value,
    fields: &[&str],
) -> Result<&'a BTreeMap<String, Value>, String> {
    let map = value.as_map()?;
    if map.len() != fields.len() || map.keys().any(|key| !fields.contains(&key.as_str())) {
        return Err("protocol envelope fields do not match the exact schema".into());
    }
    Ok(map)
}
fn field<'a>(map: &'a BTreeMap<String, Value>, key: &str) -> Result<&'a Value, String> {
    map.get(key)
        .ok_or_else(|| format!("missing protocol field {key}"))
}
fn integer(map: &BTreeMap<String, Value>, key: &str) -> Result<i64, String> {
    field(map, key)?.as_int()
}
fn unsigned(map: &BTreeMap<String, Value>, key: &str) -> Result<u64, String> {
    u64::try_from(integer(map, key)?).map_err(|_| format!("{key} must be nonnegative"))
}
fn text<'a>(map: &'a BTreeMap<String, Value>, key: &str) -> Result<&'a str, String> {
    field(map, key)?.as_str()
}

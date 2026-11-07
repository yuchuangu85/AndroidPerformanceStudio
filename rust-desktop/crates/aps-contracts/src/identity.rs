use crate::{validate_text, MAX_METADATA_TEXT_BYTES};
use std::fmt;

pub const MAX_ID_BYTES: usize = 128;
fn validate_id(value: &str) -> Result<(), String> {
    if value.is_empty()
        || value.len() > MAX_ID_BYTES
        || !value
            .bytes()
            .all(|byte| byte.is_ascii_alphanumeric() || b"._:-".contains(&byte))
    {
        return Err("ID must contain 1..128 ASCII letters/digits or ._:-".into());
    }
    Ok(())
}
macro_rules! identity {
    ($name:ident) => {
        #[derive(Debug, Clone, PartialEq, Eq, PartialOrd, Ord, Hash)]
        pub struct $name(String);
        impl $name {
            pub fn new(value: impl Into<String>) -> Result<Self, String> {
                let value = value.into();
                validate_id(&value)?;
                Ok(Self(value))
            }
            pub fn as_str(&self) -> &str {
                &self.0
            }
        }
        impl fmt::Display for $name {
            fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
                formatter.write_str(&self.0)
            }
        }
    };
}
identity!(ArtifactId);
identity!(ProfilerSessionId);
identity!(DeviceTargetId);
identity!(RequestId);
identity!(ClockDomainId);
identity!(CapabilityId);

/// Deterministic local IDs, not random UUIDs. The caller must supply a namespace
/// never reused across persistent runs; PID/time alone do not prove uniqueness.
#[derive(Debug)]
pub struct IdSequence {
    namespace: String,
    next: u64,
}
impl IdSequence {
    pub fn new(namespace: impl Into<String>) -> Result<Self, String> {
        let namespace = namespace.into();
        validate_id(&namespace)?;
        if namespace.len() > 90 {
            return Err("ID sequence namespace must fit within 90 bytes".into());
        }
        Ok(Self { namespace, next: 0 })
    }
    pub fn next_id(&mut self, kind: &str) -> Result<String, String> {
        validate_id(kind)?;
        if kind.len() > 16 {
            return Err("ID kind exceeds 16 bytes".into());
        }
        let next = self.next.checked_add(1).ok_or("ID sequence exhausted")?;
        let id = format!("{}:{kind}:{next:016x}", self.namespace);
        validate_id(&id)?;
        self.next = next;
        Ok(id)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeviceTarget {
    pub id: DeviceTargetId,
    /// An ADB routing identifier. It is not an authorization or lifecycle state.
    pub serial: String,
    pub transport_id: Option<u64>,
    pub model: Option<String>,
}
impl DeviceTarget {
    pub fn new(id: DeviceTargetId, serial: impl Into<String>) -> Result<Self, String> {
        let device = Self {
            id,
            serial: serial.into(),
            transport_id: None,
            model: None,
        };
        device.validate()?;
        Ok(device)
    }
    pub fn validate(&self) -> Result<(), String> {
        validate_text(&self.serial, "device serial", 256)?;
        if self.serial.chars().any(char::is_whitespace) {
            return Err("device serial must not contain whitespace".into());
        }
        if self.transport_id == Some(0) {
            return Err("transport ID must be nonzero when known".into());
        }
        if let Some(model) = &self.model {
            validate_text(model, "device model", MAX_METADATA_TEXT_BYTES)?;
        }
        Ok(())
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProcessStartIdentity {
    /// Immutable OS boot identity associated with the start counter.
    pub boot_id: String,
    /// Raw /proc start ticks (not milliseconds or host wall time).
    pub start_ticks: u64,
}
impl ProcessStartIdentity {
    pub fn new(boot_id: impl Into<String>, start_ticks: u64) -> Result<Self, String> {
        let value = Self {
            boot_id: boot_id.into(),
            start_ticks,
        };
        value.validate()?;
        Ok(value)
    }
    pub fn validate(&self) -> Result<(), String> {
        validate_text(&self.boot_id, "boot identity", 256)
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProcessIdentity {
    pub device: DeviceTargetId,
    pub pid: u32,
    pub process_name: String,
    pub package_name: Option<String>,
    pub start: Option<ProcessStartIdentity>,
}
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProcessIdentityMatch {
    SameInstance,
    DifferentInstance,
    WeakIdentity,
}
impl ProcessIdentity {
    pub fn new(
        device: DeviceTargetId,
        pid: u32,
        process_name: impl Into<String>,
    ) -> Result<Self, String> {
        let value = Self {
            device,
            pid,
            process_name: process_name.into(),
            package_name: None,
            start: None,
        };
        value.validate()?;
        Ok(value)
    }
    pub fn validate(&self) -> Result<(), String> {
        if self.pid == 0 {
            return Err("process PID must be nonzero".into());
        }
        validate_text(&self.process_name, "process name", MAX_METADATA_TEXT_BYTES)?;
        if let Some(package) = &self.package_name {
            validate_text(package, "package name", MAX_METADATA_TEXT_BYTES)?;
        }
        if let Some(start) = &self.start {
            start.validate()?;
        }
        Ok(())
    }
    /// PID equality cannot establish process-instance identity after reuse.
    pub fn compare(&self, other: &Self) -> Result<ProcessIdentityMatch, String> {
        self.validate()?;
        other.validate()?;
        if self.device != other.device
            || self.pid != other.pid
            || self.process_name != other.process_name
        {
            return Ok(ProcessIdentityMatch::DifferentInstance);
        }
        if self
            .package_name
            .as_ref()
            .zip(other.package_name.as_ref())
            .is_some_and(|(a, b)| a != b)
        {
            return Ok(ProcessIdentityMatch::DifferentInstance);
        }
        Ok(match (&self.start, &other.start) {
            (Some(a), Some(b)) if a == b => ProcessIdentityMatch::SameInstance,
            (Some(_), Some(_)) => ProcessIdentityMatch::DifferentInstance,
            _ => ProcessIdentityMatch::WeakIdentity,
        })
    }
}

//! Read-only Android target discovery using explicitly selected ADB commands.
//!
//! Methods are blocking and must run off the UI thread. No package install,
//! root, push, port forwarding or capture mutation is exposed by this adapter.

mod client;
mod locator;
mod model;

pub use client::AdbClient;
pub use locator::{AdbLocation, AdbLocationSource, AdbLocator};
pub use model::{
    AdbDevice, AndroidApplicationProcess, AndroidPackage, AndroidProcess, DeviceProperties,
    DeviceSnapshot, DeviceState, TargetSnapshot, parse_devices, parse_packages, parse_processes,
    parse_properties, validate_serial,
};

use aps_platform::ProcessError;
use std::fmt;

#[derive(Debug)]
pub enum AdbError {
    NotFound,
    ExplicitExecutableInvalid,
    InvalidSerial,
    Process(ProcessError),
    CommandFailed(Option<i32>),
    MalformedOutput(&'static str),
}

impl fmt::Display for AdbError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::NotFound => f.write_str(
                "ADB not found; configure an executable or install Android SDK platform-tools",
            ),
            Self::ExplicitExecutableInvalid => {
                f.write_str("The configured ADB path is not a usable executable")
            }
            Self::InvalidSerial => f.write_str("Invalid ADB device serial"),
            Self::Process(error) => write!(f, "ADB: {error}"),
            Self::CommandFailed(code) => write!(f, "ADB command failed (exit {code:?})"),
            Self::MalformedOutput(message) => write!(f, "Invalid ADB output: {message}"),
        }
    }
}

impl std::error::Error for AdbError {
    fn source(&self) -> Option<&(dyn std::error::Error + 'static)> {
        match self {
            Self::Process(error) => Some(error),
            _ => None,
        }
    }
}

impl From<ProcessError> for AdbError {
    fn from(error: ProcessError) -> Self {
        Self::Process(error)
    }
}

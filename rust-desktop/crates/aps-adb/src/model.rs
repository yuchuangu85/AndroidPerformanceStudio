use crate::AdbError;
use std::collections::{BTreeMap, HashSet};

const MAX_PARSE_BYTES: usize = 4 * 1024 * 1024;
const MAX_PARSE_ROWS: usize = 65_536;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DeviceState {
    Device,
    Offline,
    Unauthorized,
    NoPermissions,
    Unknown,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AdbDevice {
    pub serial: String,
    pub state: DeviceState,
    pub raw_state: String,
    pub attributes: BTreeMap<String, String>,
    pub status_detail: Option<String>,
}

impl AdbDevice {
    pub fn model(&self) -> &str {
        ["model", "product", "device"]
            .into_iter()
            .filter_map(|key| self.attributes.get(key))
            .find(|value| usable_label(value))
            .map(String::as_str)
            .unwrap_or(&self.serial)
    }

    pub fn display_name(&self) -> String {
        let model = self.model();
        if model == self.serial {
            self.serial.clone()
        } else {
            format!("{model} ({})", self.serial)
        }
    }

    pub fn is_online(&self) -> bool {
        self.state == DeviceState::Device
    }
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct DeviceProperties {
    pub manufacturer: Option<String>,
    pub model: Option<String>,
    pub android_release: Option<String>,
    pub sdk_level: Option<u32>,
    pub abi: Option<String>,
}

/// PID is intentionally not treated as a durable process-instance identity.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AndroidProcess {
    pub pid: u32,
    pub parent_pid: u32,
    pub user: String,
    pub name: String,
}

/// One installed package reported by the device; a bounded, sorted projection.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AndroidPackage {
    pub package_name: String,
}

/// Two sequential read-only observations of a target, not an atomic platform
/// snapshot: the installed packages and the current process table.
#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct TargetSnapshot {
    pub packages: Vec<AndroidPackage>,
    pub processes: Vec<AndroidProcess>,
}

/// A process resolved to the application package that owns it.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AndroidApplicationProcess {
    pub pid: u32,
    pub parent_pid: u32,
    pub user: String,
    pub name: String,
    pub package_name: String,
}

impl TargetSnapshot {
    /// Resolves each process to a known package. Android remote processes carry a
    /// `{package}:{suffix}` name; platform/native services owned by no installed
    /// package are intentionally omitted. Order is PID ascending.
    pub fn application_processes(&self) -> Vec<AndroidApplicationProcess> {
        let mut packages: Vec<&str> = self
            .packages
            .iter()
            .map(|package| package.package_name.as_str())
            .filter(|package_name| !package_name.is_empty())
            .collect();
        packages.sort_unstable();
        packages.dedup();
        let mut processes: Vec<_> = self
            .processes
            .iter()
            .filter_map(|process| {
                let package_name = owning_package(&process.name, &packages)?;
                Some(AndroidApplicationProcess {
                    pid: process.pid,
                    parent_pid: process.parent_pid,
                    user: process.user.clone(),
                    name: process.name.clone(),
                    package_name: package_name.to_owned(),
                })
            })
            .collect();
        processes.sort_by_key(|process| process.pid);
        processes
    }
}

fn owning_package<'a>(name: &str, packages: &[&'a str]) -> Option<&'a str> {
    if let Ok(index) = packages.binary_search(&name) {
        return Some(packages[index]);
    }
    packages.iter().copied().find(|package_name| {
        name.strip_prefix(package_name)
            .is_some_and(|remote| remote.starts_with(':'))
    })
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DeviceSnapshot {
    pub serial: String,
    pub properties: DeviceProperties,
    pub processes: Vec<AndroidProcess>,
}

pub fn validate_serial(serial: &str) -> Result<(), AdbError> {
    if serial.is_empty()
        || serial.len() > 1024
        || serial.starts_with('-')
        || !serial.bytes().all(|byte| {
            byte.is_ascii_alphanumeric() || matches!(byte, b'_' | b'.' | b':' | b'[' | b']' | b'-')
        })
    {
        return Err(AdbError::InvalidSerial);
    }
    Ok(())
}

pub fn parse_devices(output: &str) -> Result<Vec<AdbDevice>, AdbError> {
    check_output(output)?;
    let mut devices = Vec::new();
    let mut serials = HashSet::new();
    for line in output
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty())
    {
        if line == "List of devices attached"
            || line.starts_with("* daemon")
            || line.starts_with("adb server version")
            || line.starts_with("ADB server didn't ACK")
        {
            continue;
        }
        let Some((serial, details)) = line.split_once(char::is_whitespace) else {
            return Err(AdbError::MalformedOutput("device row lacks state"));
        };
        let details = details.trim();
        if serial.is_empty()
            || serial.len() > 1024
            || details.is_empty()
            || serial.chars().any(char::is_control)
        {
            return Err(AdbError::MalformedOutput("invalid device row"));
        }
        if !serials.insert(serial.to_owned()) {
            return Err(AdbError::MalformedOutput("duplicate device serial"));
        }
        if let Some(detail) = details.strip_prefix("no permissions") {
            devices.push(AdbDevice {
                serial: serial.to_owned(),
                state: DeviceState::NoPermissions,
                raw_state: "no permissions".into(),
                attributes: BTreeMap::new(),
                status_detail: Some(detail.trim().to_owned()).filter(|detail| !detail.is_empty()),
            });
            continue;
        }
        validate_serial(serial).map_err(|_| AdbError::MalformedOutput("invalid device serial"))?;
        let mut tokens = details.split_whitespace();
        let raw_state = tokens
            .next()
            .ok_or(AdbError::MalformedOutput("missing device state"))?;
        let state = match raw_state {
            "device" => DeviceState::Device,
            "offline" => DeviceState::Offline,
            "unauthorized" => DeviceState::Unauthorized,
            _ => DeviceState::Unknown,
        };
        let attributes = tokens
            .filter_map(|token| token.split_once(':'))
            .filter(|(key, value)| !key.is_empty() && !value.is_empty())
            .map(|(key, value)| (key.to_owned(), value.to_owned()))
            .collect();
        devices.push(AdbDevice {
            serial: serial.to_owned(),
            state,
            raw_state: raw_state.to_owned(),
            attributes,
            status_detail: None,
        });
    }
    Ok(devices)
}

pub fn parse_properties(output: &str) -> Result<DeviceProperties, AdbError> {
    check_output(output)?;
    let mut values = BTreeMap::new();
    let mut skipping_multiline = false;
    for line in output
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty())
    {
        if skipping_multiline {
            skipping_multiline = !line.ends_with(']');
            continue;
        }
        let Some((key, value)) = line
            .strip_prefix('[')
            .and_then(|line| line.split_once("]: ["))
        else {
            return Err(AdbError::MalformedOutput("invalid getprop row"));
        };
        let retained = matches!(
            key,
            "ro.product.manufacturer"
                | "ro.product.model"
                | "ro.build.version.release"
                | "ro.build.version.sdk"
                | "ro.product.cpu.abi"
        );
        let Some(value) = value.strip_suffix(']') else {
            // Boot-history and other unrelated Android properties can contain
            // newlines. Skip their complete value without retaining its text.
            if retained {
                return Err(AdbError::MalformedOutput(
                    "multiline device identity property",
                ));
            }
            skipping_multiline = true;
            continue;
        };
        if retained && values.insert(key, value.trim()).is_some() {
            return Err(AdbError::MalformedOutput(
                "duplicate device identity property",
            ));
        }
    }
    if skipping_multiline {
        return Err(AdbError::MalformedOutput("unterminated getprop value"));
    }
    let value = |key: &str| {
        values
            .get(key)
            .filter(|value| usable_label(value))
            .map(|value| (*value).to_owned())
    };
    Ok(DeviceProperties {
        manufacturer: value("ro.product.manufacturer"),
        model: value("ro.product.model"),
        android_release: value("ro.build.version.release"),
        sdk_level: values
            .get("ro.build.version.sdk")
            .and_then(|value| value.parse().ok()),
        abi: value("ro.product.cpu.abi"),
    })
}

/// Parses `cmd package list packages` rows. Non-package rows and blank lines
/// are ignored; names are trimmed, deduplicated and sorted for Compose parity.
pub fn parse_packages(output: &str) -> Result<Vec<AndroidPackage>, AdbError> {
    check_output(output)?;
    let mut packages: Vec<AndroidPackage> = output
        .lines()
        .filter_map(|line| line.trim().strip_prefix("package:"))
        .map(str::trim)
        .filter(|package_name| !package_name.is_empty())
        .map(|package_name| AndroidPackage {
            package_name: package_name.to_owned(),
        })
        .collect();
    packages.sort_by(|left, right| left.package_name.cmp(&right.package_name));
    packages.dedup();
    Ok(packages)
}

pub fn parse_processes(output: &str) -> Result<Vec<AndroidProcess>, AdbError> {
    check_output(output)?;
    let mut lines = output
        .lines()
        .map(str::trim)
        .filter(|line| !line.is_empty());
    let header: Vec<_> = lines
        .next()
        .ok_or(AdbError::MalformedOutput("missing process header"))?
        .split_whitespace()
        .collect();
    if header != ["PID", "PPID", "USER", "NAME"] {
        return Err(AdbError::MalformedOutput("unsupported process columns"));
    }
    let mut processes = Vec::new();
    let mut pids = HashSet::new();
    for line in lines {
        // Toybox/Android variants may preserve spaces in the NAME/command
        // column. The first three columns are fixed by the explicit -o
        // request; keep the remainder intact instead of rejecting a valid
        // process row because its command contains whitespace.
        let mut remainder = line;
        let mut columns = Vec::with_capacity(4);
        for _ in 0..3 {
            remainder = remainder.trim_start();
            let end = remainder
                .find(char::is_whitespace)
                .unwrap_or(remainder.len());
            columns.push(&remainder[..end]);
            remainder = &remainder[end..];
        }
        columns.push(remainder.trim());
        if columns.len() != 4 || columns[3].trim().is_empty() {
            return Err(AdbError::MalformedOutput("invalid process row"));
        }
        let pid = columns[0]
            .parse::<u32>()
            .ok()
            .filter(|pid| *pid > 0)
            .ok_or(AdbError::MalformedOutput("invalid process PID"))?;
        let parent_pid = columns[1]
            .parse::<u32>()
            .map_err(|_| AdbError::MalformedOutput("invalid parent PID"))?;
        if !pids.insert(pid) {
            return Err(AdbError::MalformedOutput("duplicate process PID"));
        }
        processes.push(AndroidProcess {
            pid,
            parent_pid,
            user: columns[2].into(),
            name: columns[3].into(),
        });
    }
    processes.sort_by_key(|process| process.pid);
    Ok(processes)
}

fn usable_label(value: &str) -> bool {
    !value.trim().is_empty()
        && !["unknown", "<unknown>", "null", "n/a", "na"]
            .iter()
            .any(|invalid| value.eq_ignore_ascii_case(invalid))
}

fn check_output(output: &str) -> Result<(), AdbError> {
    if output.len() > MAX_PARSE_BYTES
        || output.lines().take(MAX_PARSE_ROWS + 1).count() > MAX_PARSE_ROWS
    {
        return Err(AdbError::MalformedOutput("output exceeds parser budget"));
    }
    if output
        .chars()
        .any(|character| character.is_control() && !matches!(character, '\n' | '\r' | '\t'))
    {
        return Err(AdbError::MalformedOutput(
            "output contains control characters",
        ));
    }
    Ok(())
}

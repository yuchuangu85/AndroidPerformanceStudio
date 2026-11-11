//! Blocking, read-only Android observations. Constructing config/control does not
//! contact a device. Call `capture_live` only after explicit target approval and
//! off the UI thread. No app launches, statistics reset, remote files or agents.
mod artifact;
mod installation;
use crate::{
    BatteryDelta, BatteryReport, BatteryRunObservation, BatterySnapshot, DeltaAvailability,
    ResourceKind, SnapshotIdentity, parse_snapshot,
};
use aps_adb::{AdbClient, validate_serial};
use aps_platform::{CancellationToken, ProcessRequest, ProcessRunner};
pub use artifact::BatteryCaptureArtifact;
use artifact::SnapshotLedger;
use installation::{InstallationIdentity, read_installation};
use std::{
    collections::BTreeSet,
    sync::{
        Arc, Mutex,
        atomic::{AtomicBool, AtomicU64, Ordering},
    },
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

const COMMAND_TIMEOUT: Duration = Duration::from_secs(15);
const CONTROL_INTERVAL: Duration = Duration::from_millis(50);
const MAX_SNAPSHOTS: usize = 1024;
static NEXT_CAPTURE: AtomicU64 = AtomicU64::new(0);

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BatteryCaptureTarget {
    pub serial: String,
    pub pid: u32,
    pub process_name: String,
    pub package_name: String,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub enum BatteryCaptureMode {
    Interactive,
    #[default]
    Timed,
    Repeated,
    Online,
}
impl BatteryCaptureMode {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Interactive => "INTERACTIVE",
            Self::Timed => "TIMED",
            Self::Repeated => "REPEATED",
            Self::Online => "ONLINE",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct BatteryCaptureConfig {
    pub mode: BatteryCaptureMode,
    /// All modes have this per-run upper bound, including Interactive and Online.
    pub duration_seconds: u32,
    pub polling_seconds: u32,
    pub runs: u32,
    pub cooldown_seconds: u32,
}
impl Default for BatteryCaptureConfig {
    fn default() -> Self {
        Self {
            mode: BatteryCaptureMode::Timed,
            duration_seconds: 30,
            polling_seconds: 5,
            runs: 3,
            cooldown_seconds: 15,
        }
    }
}
impl BatteryCaptureConfig {
    pub fn validate(self) -> Result<(), String> {
        if !(5..=600).contains(&self.duration_seconds)
            || !(5..=60).contains(&self.polling_seconds)
            || !(1..=10).contains(&self.runs)
            || self.cooldown_seconds > 120
        {
            return Err("Battery capture requires duration 5–600 s, polling 5–60 s, 1–10 runs and cooldown 0–120 s.".into());
        }
        if self.mode == BatteryCaptureMode::Online && self.duration_seconds < 30 {
            return Err("Low-frequency online Battery capture requires a bounded duration of at least 30 seconds.".into());
        }
        let polls = self.duration_seconds.div_ceil(self.polling_interval()) + 1;
        if polls as usize * self.run_count() as usize > MAX_SNAPSHOTS {
            return Err("Battery configuration exceeds the bounded snapshot budget; reduce runs or increase polling interval.".into());
        }
        Ok(())
    }
    pub fn run_count(self) -> u32 {
        if self.mode == BatteryCaptureMode::Repeated {
            self.runs
        } else {
            1
        }
    }
    pub fn polling_interval(self) -> u32 {
        if self.mode == BatteryCaptureMode::Online {
            self.polling_seconds.max(30)
        } else {
            self.polling_seconds
        }
    }
}

#[derive(Debug, Clone, Default)]
pub struct BatteryCaptureProgress {
    pub iteration: u32,
    pub completed_runs: u32,
    pub snapshots: usize,
    /// Host operation progress, not a precise device workload clock.
    pub elapsed_seconds: u64,
    pub cooling_down: bool,
    pub finished: bool,
}

#[derive(Clone)]
pub struct BatteryCaptureControl {
    cancel: CancellationToken,
    stop: Arc<AtomicBool>,
    progress: Arc<Mutex<BatteryCaptureProgress>>,
}
impl BatteryCaptureControl {
    pub fn new(cancel: CancellationToken) -> Self {
        Self {
            cancel,
            stop: Arc::new(AtomicBool::new(false)),
            progress: Arc::new(Mutex::new(BatteryCaptureProgress::default())),
        }
    }
    /// Stop-and-analyze takes a final identity-checked observation. Unlike cancel,
    /// it can publish completed evidence; no partial in-flight bytes are parsed.
    pub fn stop_and_analyze(&self) {
        self.stop.store(true, Ordering::Release);
    }
    pub fn cancel(&self) {
        self.cancel.cancel();
    }
    pub fn is_stopping(&self) -> bool {
        self.stop.load(Ordering::Acquire)
    }
    pub fn progress(&self) -> BatteryCaptureProgress {
        self.progress
            .lock()
            .map(|value| value.clone())
            .unwrap_or_default()
    }
    fn update(&self, update: impl FnOnce(&mut BatteryCaptureProgress)) {
        if let Ok(mut progress) = self.progress.lock() {
            update(&mut progress);
        }
    }
}

pub struct BatteryCaptureOutcome {
    pub report: BatteryReport,
    pub artifact: BatteryCaptureArtifact,
    pub stopped_early: bool,
    pub snapshots: usize,
}

#[derive(Clone, PartialEq, Eq)]
struct ProcessIdentity {
    boot_id: String,
    uid: u32,
    start_ticks: u64,
}
struct Observation {
    snapshot: BatterySnapshot,
    thermal_status: Option<u32>,
}
struct Capture<'a> {
    adb: &'a AdbClient,
    target: &'a BatteryCaptureTarget,
    control: &'a BatteryCaptureControl,
    identity: ProcessIdentity,
    packages: BTreeSet<String>,
    installation: InstallationIdentity,
    deadline: Instant,
    started: Instant,
    snapshots: usize,
    period: Option<String>,
    ledger: SnapshotLedger,
}

/// No privileged fallback is attempted. Permission failures, process exit/PID
/// reuse, package/UID changes and reboots fail the capture without publication.
pub fn capture_live(
    adb: &AdbClient,
    target: &BatteryCaptureTarget,
    config: BatteryCaptureConfig,
    control: &BatteryCaptureControl,
) -> Result<BatteryCaptureOutcome, String> {
    capture_live_with_workload(adb, target, config, control, None)
}

pub fn capture_live_with_workload(
    adb: &AdbClient,
    target: &BatteryCaptureTarget,
    config: BatteryCaptureConfig,
    control: &BatteryCaptureControl,
    workload_id: Option<&str>,
) -> Result<BatteryCaptureOutcome, String> {
    if workload_id.is_some_and(|value| {
        value.trim().is_empty() || value.len() > 128 || value.chars().any(char::is_control)
    }) {
        return Err(
            "Battery workload ID must contain 1..128 bytes without control characters".into(),
        );
    }
    let result = capture_inner(adb, target, config, control, workload_id);
    control.update(|progress| progress.finished = true);
    result
}

fn capture_inner(
    adb: &AdbClient,
    target: &BatteryCaptureTarget,
    config: BatteryCaptureConfig,
    control: &BatteryCaptureControl,
    workload_id: Option<&str>,
) -> Result<BatteryCaptureOutcome, String> {
    config.validate()?;
    validate_serial(&target.serial).map_err(|error| error.to_string())?;
    if target.pid == 0
        || target.package_name.is_empty()
        || target.package_name.len() > 255
        || !target.package_name.split('.').all(|part| {
            !part.is_empty()
                && part
                    .bytes()
                    .all(|value| value.is_ascii_alphanumeric() || value == b'_')
        })
        || target.process_name.is_empty()
        || target.process_name.len() > 4096
        || target.process_name.chars().any(char::is_control)
    {
        return Err("Invalid selected Battery application target.".into());
    }
    check_cancelled(control)?;
    let started = Instant::now();
    // Read-only commands have individual deadlines; this is an independent,
    // finite session deadline that also covers discovery, polls and cooldowns.
    let maximum_seconds = u64::from(config.duration_seconds) * u64::from(config.run_count())
        + u64::from(config.cooldown_seconds) * u64::from(config.run_count() - 1)
        + 180 * u64::from(config.run_count())
        + 60;
    let deadline = started + Duration::from_secs(maximum_seconds);
    let client = adb.clone().with_timeout(COMMAND_TIMEOUT);
    let devices = client
        .list_devices(&control.cancel)
        .map_err(|error| error.to_string())?;
    if !devices
        .iter()
        .any(|device| device.serial == target.serial && device.is_online())
    {
        return Err(
            "Selected Battery device is disconnected or unauthorized; refresh the target selector."
                .into(),
        );
    }
    let identity = read_identity(&client, target, control, deadline)?.0;
    if identity.uid == 0 {
        return Err("Battery checkin UID 0 also contains global device records and cannot establish application attribution.".into());
    }
    let selected = client
        .target_snapshot(&target.serial, &control.cancel)
        .map_err(|error| error.to_string())?
        .application_processes();
    if !selected.iter().any(|process| {
        process.pid == target.pid
            && process.name == target.process_name
            && process.package_name == target.package_name
    }) {
        return Err(
            "Selected Battery application process changed; refresh the target selector.".into(),
        );
    }
    let packages = uid_packages(&client, target, &identity, control, deadline)?;
    let installation = read_installation(&client, target, control, deadline)?;
    let mut capture = Capture {
        adb: &client,
        target,
        control,
        identity,
        packages,
        installation,
        deadline,
        started,
        snapshots: 0,
        period: None,
        ledger: SnapshotLedger::default(),
    };
    capture.verify_packages()?;
    let mut observations = Vec::new();
    for iteration in 1..=config.run_count() {
        control.update(|progress| {
            progress.iteration = iteration;
            progress.cooling_down = false;
        });
        let before = capture.snapshot(iteration)?;
        let mut previous = before.snapshot.clone();
        let mut invalid_timers = BTreeSet::<(ResourceKind, String)>::new();
        let mut invalid_network = BTreeSet::new();
        let mut invalid_energy = BTreeSet::new();
        let mut temperatures = before
            .snapshot
            .device
            .temperature_tenths_celsius
            .into_iter()
            .collect::<Vec<_>>();
        let mut thermal_statuses = before.thermal_status.into_iter().collect::<Vec<_>>();
        let mut charging_states = vec![before.snapshot.device.powered];
        let run_started = Instant::now();
        let duration = Duration::from_secs(u64::from(config.duration_seconds));
        let after = loop {
            let remaining = duration.saturating_sub(run_started.elapsed());
            capture
                .wait(remaining.min(Duration::from_secs(u64::from(config.polling_interval()))))?;
            let mut next = capture.snapshot(iteration)?;
            temperatures.extend(next.snapshot.device.temperature_tenths_celsius);
            thermal_statuses.extend(next.thermal_status);
            charging_states.push(next.snapshot.device.powered);
            let continuity = BatteryDelta::between(&previous, &next.snapshot);
            if continuity.availability == DeltaAvailability::IncompatibleIdentity
                || before.snapshot.stats_period.is_some() && next.snapshot.stats_period.is_none()
                || before.snapshot.uid_records_present && !next.snapshot.uid_records_present
            {
                return Err("Battery accumulator reset or became unavailable during capture; the previous report is retained.".into());
            }
            // An intermediate missing/decreasing counter cannot regain apparent
            // continuity by exceeding the baseline before the final poll.
            invalid_timers.extend(
                continuity
                    .timers
                    .into_iter()
                    .filter(|timer| timer.duration_ms.is_none() || timer.count.is_none())
                    .map(|timer| (timer.kind, timer.name)),
            );
            invalid_network.extend(
                continuity
                    .network_bytes
                    .into_iter()
                    .filter(|(_, value)| value.is_none())
                    .map(|(key, _)| key),
            );
            invalid_energy.extend(
                continuity
                    .modeled_energy_mah
                    .into_iter()
                    .filter(|(_, value)| value.is_none())
                    .map(|(key, _)| key),
            );
            previous = next.snapshot.clone();
            if control.is_stopping() || run_started.elapsed() >= duration {
                for key in &invalid_timers {
                    next.snapshot.timers.remove(key);
                }
                for key in &invalid_network {
                    next.snapshot.network_bytes.insert(key.clone(), None);
                }
                for key in &invalid_energy {
                    next.snapshot.modeled_energy_mah.remove(key);
                }
                if !invalid_timers.is_empty()
                    || !invalid_network.is_empty()
                    || !invalid_energy.is_empty()
                {
                    next.snapshot.warnings.push("Counters with missing or reset intermediate observations remain unavailable for the entire run, even if later values exceed the baseline.".into());
                }
                break next;
            }
        };
        observations.push(BatteryRunObservation {
            iteration,
            before: before.snapshot,
            after: after.snapshot,
            temperatures_tenths_celsius: temperatures,
            thermal_statuses,
            charging_states,
        });
        control.update(|progress| progress.completed_runs = iteration);
        if control.is_stopping() {
            break;
        }
        if iteration < config.run_count() && config.cooldown_seconds > 0 {
            control.update(|progress| progress.cooling_down = true);
            capture.wait(Duration::from_secs(u64::from(config.cooldown_seconds)))?;
            if control.is_stopping() {
                break;
            }
        }
    }
    check_cancelled(control)?;
    capture.verify_packages()?;
    let sequence = NEXT_CAPTURE
        .try_update(Ordering::Relaxed, Ordering::Relaxed, |id| id.checked_add(1))
        .map_err(|_| "Battery capture identity exhausted")?;
    let timestamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| "Host clock cannot create a unique Battery session identity")?
        .as_nanos();
    let session_id = format!("battery-{}-{timestamp:x}-{sequence:x}", std::process::id());
    let mut report = BatteryReport::from_live_snapshots(
        &session_id,
        &target.package_name,
        config.mode.as_str(),
        &observations,
    )?;
    report.workload_id = workload_id.map(str::to_owned);
    report.configuration = crate::baseline::parse_config(&crate::baseline::config_value(config))?;
    report.warnings.push("Only explicitly selected UID cumulative statistics are compared. No global reset, app launch, agent injection or device setting change was performed.".into());
    report.warnings.push("Temperature is the observed battery sensor state, not CPU/GPU temperature. Thermal status is device-wide; energy is the system model, not measured hardware energy.".into());
    report.warnings.push("Installation continuity uses matching PackageManager codePath, versionCode and lastUpdateTime at capture boundaries. APK bytes/signatures and unchanged sibling packages in a shared UID are not independently verified.".into());
    if config.mode == BatteryCaptureMode::Online {
        report.warnings.push("Low-frequency online mode uses bounded full snapshots at intervals of at least 30 seconds; it is not continuous hardware monitoring.".into());
    }
    let stopped_early = control.is_stopping();
    report.stopped_early = Some(stopped_early);
    if stopped_early {
        report.warnings.push("Stopped by the user; only completed observations are published. Runs are not duration-matched to other experiments.".into());
    }
    let snapshots = capture.snapshots;
    let artifact =
        BatteryCaptureArtifact::build(&report, capture, config, timestamp, stopped_early)?;
    check_cancelled(control)?;
    Ok(BatteryCaptureOutcome {
        report,
        artifact,
        stopped_early,
        snapshots,
    })
}

impl Capture<'_> {
    fn wait(&self, duration: Duration) -> Result<(), String> {
        let until = Instant::now() + duration;
        loop {
            check_cancelled(self.control)?;
            if Instant::now() >= self.deadline {
                return Err("Battery capture session timed out.".into());
            }
            self.control
                .update(|progress| progress.elapsed_seconds = self.started.elapsed().as_secs());
            if self.control.is_stopping() || Instant::now() >= until {
                return Ok(());
            }
            thread::sleep(
                until
                    .saturating_duration_since(Instant::now())
                    .min(CONTROL_INTERVAL),
            );
        }
    }
    fn verify_packages(&self) -> Result<(), String> {
        let (identity, _) = read_identity(self.adb, self.target, self.control, self.deadline)?;
        if identity != self.identity {
            return Err(
                "Battery target process or device boot changed; the result was not published."
                    .into(),
            );
        }
        if uid_packages(
            self.adb,
            self.target,
            &identity,
            self.control,
            self.deadline,
        )? != self.packages
        {
            return Err(
                "Battery UID/package attribution changed; the result was not published.".into(),
            );
        }
        if read_installation(self.adb, self.target, self.control, self.deadline)?
            != self.installation
        {
            return Err(
                "Battery selected package installation changed; the result was not published."
                    .into(),
            );
        }
        // The PackageManager/UID observations must themselves be bracketed by
        // the same process lifetime, not just compared with an earlier PID read.
        if read_identity(self.adb, self.target, self.control, self.deadline)?.0 != self.identity {
            return Err(
                "Battery process identity changed while verifying installation metadata.".into(),
            );
        }
        Ok(())
    }
    fn snapshot(&mut self, iteration: u32) -> Result<Observation, String> {
        if self.snapshots >= MAX_SNAPSHOTS {
            return Err("Battery snapshot budget exceeded.".into());
        }
        let (before, _) = read_identity(self.adb, self.target, self.control, self.deadline)?;
        if before != self.identity {
            return Err("Battery target PID, UID or device boot changed.".into());
        }
        let checkin = read(
            self.adb,
            &self.target.serial,
            &["dumpsys", "batterystats", "--checkin"],
            8 * 1024 * 1024,
            self.control,
            self.deadline,
        )?;
        if !checkin.lines().any(|line| line.starts_with("9,")) {
            return Err("Device returned no supported BatteryStats checkin records.".into());
        }
        let mut warnings = Vec::new();
        let report = self.optional(
            &["dumpsys", "batterystats"],
            8 * 1024 * 1024,
            "System-model energy report unavailable; no energy estimate was invented.",
            &mut warnings,
        )?;
        let battery = read(
            self.adb,
            &self.target.serial,
            &["dumpsys", "battery"],
            128 * 1024,
            self.control,
            self.deadline,
        )?;
        let thermal = self.optional(
            &["dumpsys", "thermalservice"],
            1024 * 1024,
            "Device thermal status unavailable; no throttling state was invented.",
            &mut warnings,
        )?;
        let (after, elapsed_ms) =
            read_identity(self.adb, self.target, self.control, self.deadline)?;
        if before != after {
            return Err("Battery target identity changed during a snapshot.".into());
        }
        let mut snapshot = parse_snapshot(
            &checkin,
            &report,
            &battery,
            SnapshotIdentity {
                uid: after.uid,
                shared_uid: self.packages.len() > 1,
                device_id: self.target.serial.clone(),
                boot_id: Some(after.boot_id),
                elapsed_ms,
            },
        )?;
        if self
            .period
            .as_ref()
            .is_some_and(|period| snapshot.stats_period.as_ref() != Some(period))
        {
            return Err(
                "Battery statistics accumulator changed or became unavailable during the session."
                    .into(),
            );
        }
        if self.period.is_none() {
            self.period = snapshot.stats_period.clone();
        }
        snapshot.warnings.extend(warnings);
        let thermal_statuses = thermal
            .lines()
            .filter_map(|line| line.trim().strip_prefix("Thermal Status:"))
            .filter_map(|value| value.trim().parse::<u32>().ok())
            .filter(|value| *value <= 6)
            .collect::<BTreeSet<_>>();
        let thermal_status = if thermal_statuses.len() == 1 {
            thermal_statuses.first().copied()
        } else {
            None
        };
        if thermal_status.is_none() {
            snapshot.warnings.push("No unambiguous device-wide Thermal Status field; sensor-local statuses are not substituted.".into());
        }
        if snapshot.device.level_percent.is_none()
            && snapshot.device.temperature_tenths_celsius.is_none()
            && snapshot.device.powered.is_none()
        {
            return Err("Device returned no supported battery state fields.".into());
        }
        self.ledger.append(iteration, &snapshot, thermal_status)?;
        self.snapshots += 1;
        self.control.update(|progress| {
            progress.snapshots = self.snapshots;
            progress.elapsed_seconds = self.started.elapsed().as_secs();
        });
        Ok(Observation {
            snapshot,
            thermal_status,
        })
    }
    fn optional(
        &self,
        arguments: &[&str],
        limit: usize,
        warning: &str,
        warnings: &mut Vec<String>,
    ) -> Result<String, String> {
        match read(
            self.adb,
            &self.target.serial,
            arguments,
            limit,
            self.control,
            self.deadline,
        ) {
            Ok(value) => Ok(value),
            Err(error) => {
                check_cancelled(self.control)?;
                if Instant::now() >= self.deadline {
                    return Err(error);
                }
                warnings.push(warning.into());
                Ok(String::new())
            }
        }
    }
}

fn uid_packages(
    adb: &AdbClient,
    target: &BatteryCaptureTarget,
    identity: &ProcessIdentity,
    control: &BatteryCaptureControl,
    deadline: Instant,
) -> Result<BTreeSet<String>, String> {
    let user = (identity.uid / 100_000).to_string();
    let output = read(
        adb,
        &target.serial,
        &["cmd", "package", "list", "packages", "-U", "--user", &user],
        4 * 1024 * 1024,
        control,
        deadline,
    )?;
    let mut packages = BTreeSet::new();
    for line in output.lines() {
        let fields = line.split_whitespace().collect::<Vec<_>>();
        let package = fields
            .first()
            .and_then(|value| value.strip_prefix("package:"));
        let uid = fields
            .iter()
            .filter_map(|value| value.strip_prefix("uid:"))
            .filter_map(|value| value.parse::<u32>().ok())
            .next();
        if uid == Some(identity.uid)
            && let Some(package) = package
        {
            packages.insert(package.to_owned());
        }
    }
    if !packages.contains(&target.package_name) {
        return Err("Cannot establish the selected package's actual runtime UID; no attribution was guessed.".into());
    }
    Ok(packages)
}

fn read_identity(
    adb: &AdbClient,
    target: &BatteryCaptureTarget,
    control: &BatteryCaptureControl,
    deadline: Instant,
) -> Result<(ProcessIdentity, u64), String> {
    let pid = target.pid;
    // Only validated decimal PID paths enter this script. Each required read is
    // chained so a permission error cannot silently become an identity match.
    let script = format!(
        "cat /proc/sys/kernel/random/boot_id && printf '\\nAPS_STATUS\\n' && cat /proc/{pid}/status && printf '\\nAPS_STAT\\n' && cat /proc/{pid}/stat && printf '\\nAPS_UPTIME\\n' && cat /proc/uptime"
    );
    let output = read(
        adb,
        &target.serial,
        &[&script],
        128 * 1024,
        control,
        deadline,
    )?;
    let (boot, remaining) = output
        .split_once("\nAPS_STATUS\n")
        .ok_or("Battery device/process identity framing was missing")?;
    let boot = boot.trim();
    if boot.len() != 36
        || !boot
            .bytes()
            .all(|value| value.is_ascii_hexdigit() || value == b'-')
    {
        return Err("Battery device boot identity was unavailable.".into());
    }
    let (status, remaining) = remaining
        .split_once("\nAPS_STAT\n")
        .ok_or("Battery UID identity framing was missing")?;
    let uids = status
        .lines()
        .find_map(|line| line.strip_prefix("Uid:"))
        .ok_or("Battery process UID is unavailable")?
        .split_whitespace()
        .map(str::parse::<u32>)
        .collect::<Result<Vec<_>, _>>()
        .map_err(|_| "Invalid Battery process UID")?;
    if uids.len() != 4 || uids.iter().any(|uid| *uid != uids[0]) {
        return Err("Battery process has ambiguous real/effective UID attribution.".into());
    }
    let (stat, uptime) = remaining
        .split_once("\nAPS_UPTIME\n")
        .ok_or("Battery process start identity framing was missing")?;
    if stat
        .split_whitespace()
        .next()
        .and_then(|value| value.parse::<u32>().ok())
        != Some(pid)
    {
        return Err("Battery process PID identity mismatch.".into());
    }
    let start_ticks = stat
        .rsplit_once(')')
        .and_then(|(_, fields)| fields.split_whitespace().nth(19))
        .and_then(|value| value.parse::<u64>().ok())
        .filter(|value| *value > 0)
        .ok_or("Battery process start identity is unavailable")?;
    let time = uptime
        .split_whitespace()
        .next()
        .ok_or("Device uptime is missing")?;
    let (seconds, fraction) = time.split_once('.').unwrap_or((time, ""));
    if fraction.len() > 9 || !fraction.bytes().all(|value| value.is_ascii_digit()) {
        return Err("Invalid device uptime fraction.".into());
    }
    let millis = format!("{fraction:0<3}")[..3]
        .parse::<u64>()
        .map_err(|_| "Invalid device uptime")?;
    let elapsed_ms = seconds
        .parse::<u64>()
        .ok()
        .and_then(|value| value.checked_mul(1000))
        .and_then(|value| value.checked_add(millis))
        .ok_or("Invalid device uptime")?;
    Ok((
        ProcessIdentity {
            boot_id: boot.to_owned(),
            uid: uids[0],
            start_ticks,
        },
        elapsed_ms,
    ))
}

fn read(
    adb: &AdbClient,
    serial: &str,
    arguments: &[&str],
    limit: usize,
    control: &BatteryCaptureControl,
    deadline: Instant,
) -> Result<String, String> {
    check_cancelled(control)?;
    let remaining = deadline.saturating_duration_since(Instant::now());
    if remaining.is_zero() {
        return Err("Battery capture session timed out.".into());
    }
    let mut request = ProcessRequest::new(&adb.location().executable)
        .args(["-s", serial, "shell"])
        .args(arguments);
    request.timeout = COMMAND_TIMEOUT.min(remaining);
    request.stdout_limit = limit;
    request.stderr_limit = 64 * 1024;
    let output = ProcessRunner
        .run(&request, &control.cancel)
        .map_err(|error| format!("Battery observation failed: {error}"))?;
    if !output.status.success() {
        return Err(format!(
            "Battery read-only observation failed (exit {:?}); verify device authorization and application profiling access.",
            output.status.code()
        ));
    }
    let output = String::from_utf8(output.stdout)
        .map_err(|_| "Battery observation returned invalid UTF-8")?;
    if [
        "Permission Denial",
        "Permission denied",
        "Can't find service",
    ]
    .iter()
    .any(|prefix| output.trim_start().starts_with(prefix))
    {
        return Err(
            "Battery observation is unsupported or permission denied on the selected device."
                .into(),
        );
    }
    Ok(output)
}
fn check_cancelled(control: &BatteryCaptureControl) -> Result<(), String> {
    if control.cancel.is_cancelled() {
        Err("Battery capture cancelled; the previous report is retained.".into())
    } else {
        Ok(())
    }
}

//! One bounded, independent ADB sampling loop per selected Android device.
mod axis;
mod meminfo;
mod report;
mod view;

use super::ImportWorkspace;
use aps_adb::{AdbClient, AdbDevice, AdbLocator, AndroidApplicationProcess, validate_serial};
use aps_platform::{CancellationToken, ProcessRequest, ProcessRunner};
use aps_ui_foundation::TextKey;
use gpui_kit::*;
use meminfo::{MeminfoSample, MetricKey, MetricSection, parse_meminfo};
use std::{
    collections::{BTreeMap, VecDeque},
    path::{Path, PathBuf},
    sync::Arc,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

const POLL_INTERVAL: Duration = Duration::from_secs(1);
const COMMAND_TIMEOUT: Duration = Duration::from_secs(8);
const HISTORY_WINDOW: Duration = Duration::from_secs(120);
const HISTORY_POINTS: usize = 121;

pub(in crate::import) struct LiveCompareUi {
    devices: Vec<DeviceRow>,
    executable: Option<PathBuf>,
    discovering: bool,
    discovery_error: Option<String>,
    discovery_cancel: Option<CancellationToken>,
    session: Option<Session>,
    next_session: u64,
    section: MetricSection,
}

impl Default for LiveCompareUi {
    fn default() -> Self {
        Self {
            devices: Vec::new(),
            executable: None,
            discovering: false,
            discovery_error: None,
            discovery_cancel: None,
            session: None,
            next_session: 0,
            section: MetricSection::Summary,
        }
    }
}

impl Drop for LiveCompareUi {
    fn drop(&mut self) {
        if let Some(cancel) = &self.discovery_cancel {
            cancel.cancel();
        }
        if let Some(session) = &self.session {
            session.cancel.cancel();
        }
    }
}

#[derive(PartialEq, Eq)]
struct DeviceRow {
    device: AdbDevice,
    processes: Vec<AndroidApplicationProcess>,
    selected: Option<u32>,
    error: Option<String>,
}

#[derive(Clone)]
struct Target {
    serial: String,
    device_name: String,
    pid: u32,
    process_name: String,
    package_name: String,
}

#[derive(Clone)]
struct Session {
    id: u64,
    started: Instant,
    stopped_at: Option<Duration>,
    cancel: CancellationToken,
    series: Vec<Series>,
}

impl Session {
    fn running(&self) -> bool {
        self.stopped_at.is_none()
    }

    fn latest_elapsed(&self) -> Duration {
        self.stopped_at.unwrap_or_else(|| self.started.elapsed())
    }

    fn record(&mut self, id: u64, index: usize, outcome: Result<MeminfoSample, String>) -> bool {
        if self.id != id || !self.running() {
            return false;
        }
        self.series[index].record(self.started.elapsed(), outcome);
        true
    }

    fn stop(&mut self) {
        if self.running() {
            self.stopped_at = Some(self.started.elapsed());
            self.cancel.cancel();
        }
    }
}

#[derive(Clone)]
struct Series {
    target: Target,
    samples: VecDeque<TimedSample>,
    latest_raw: Option<String>,
    error: Option<String>,
}

#[derive(Clone)]
struct TimedSample {
    elapsed: Duration,
    metrics: Option<BTreeMap<MetricKey, u64>>,
}

impl Series {
    fn current_value(&self, key: &MetricKey) -> Option<u64> {
        if self.error.is_some() {
            return None;
        }
        self.samples.back()?.metrics.as_ref()?.get(key).copied()
    }

    fn new(target: Target) -> Self {
        Self {
            target,
            samples: VecDeque::new(),
            latest_raw: None,
            error: None,
        }
    }

    fn record(&mut self, elapsed: Duration, outcome: Result<MeminfoSample, String>) {
        let metrics = match outcome {
            Ok(sample) => {
                self.latest_raw = Some(sample.raw);
                self.error = None;
                Some(sample.metrics)
            }
            Err(error) => {
                self.latest_raw = None;
                self.error = Some(error);
                None
            }
        };
        self.samples.push_back(TimedSample { elapsed, metrics });
        let cutoff = elapsed.saturating_sub(HISTORY_WINDOW);
        while self.samples.len() > HISTORY_POINTS
            || self
                .samples
                .front()
                .is_some_and(|point| point.elapsed < cutoff)
        {
            self.samples.pop_front();
        }
    }
}

impl LiveCompareUi {
    fn running(&self) -> bool {
        self.session.as_ref().is_some_and(Session::running)
    }

    fn targets_editable(&self) -> bool {
        !self.running() && self.executable.is_some()
    }

    fn selected_targets(&self) -> Vec<Target> {
        self.devices
            .iter()
            .filter(|row| row.device.is_online() && row.error.is_none())
            .filter_map(|row| {
                let pid = row.selected?;
                let process = row.processes.iter().find(|process| process.pid == pid)?;
                Some(Target {
                    serial: row.device.serial.clone(),
                    device_name: row.device.display_name(),
                    pid,
                    process_name: process.name.clone(),
                    package_name: process.package_name.clone(),
                })
            })
            .collect()
    }

    fn update_devices(&mut self, mut devices: Vec<DeviceRow>) -> bool {
        // Retain only selections whose full process descriptor is still present
        // on the same device. Never silently pick another PID on refresh.
        for row in &mut devices {
            let retained = self
                .devices
                .iter()
                .find(|old| old.device.serial == row.device.serial)
                .and_then(|old| old.processes.iter().find(|p| Some(p.pid) == old.selected));
            row.selected = retained
                .filter(|process| row.processes.contains(process))
                .map(|process| process.pid);
        }
        if self.devices == devices {
            return false;
        }
        self.devices = devices;
        true
    }
}

impl ImportWorkspace {
    pub(super) fn memory_compare_enter(&mut self, cx: &mut Context<Self>) {
        // Discovery is explicit: entering the view refreshes once and the Refresh
        // button re-runs it. A background poll cannot detect attach/detach through
        // this ADB layer without re-running `cmd package list packages` and `ps`
        // for every device; that kept Refresh disabled and rebuilt each process
        // menu (closing an open search) while the operator was picking a target.
        self.memory_compare_refresh(cx);
    }

    fn memory_compare_refresh(&mut self, cx: &mut Context<Self>) {
        let state = &mut self.memory_compare;
        if state.discovering || state.running() {
            return;
        }
        state.discovering = true;
        let cancel = CancellationToken::new();
        state.discovery_cancel = Some(cancel.clone());
        let discovery = cx.background_executor().spawn({
            let cancel = cancel.clone();
            async move {
                let location = AdbLocator::new()
                    .locate(None)
                    .map_err(|error| error.to_string())?;
                let client = AdbClient::new(location).with_timeout(COMMAND_TIMEOUT);
                let devices = client
                    .list_devices(&cancel)
                    .map_err(|error| error.to_string())?;
                Ok::<_, String>((client, devices))
            }
        });
        cx.notify();
        cx.spawn(async move |this, cx| {
            let result = match discovery.await {
                Ok((client, devices)) => {
                    let executable = client.location().executable.clone();
                    let tasks = devices.into_iter().map(|device| {
                        let client = client.clone();
                        let cancel = cancel.clone();
                        cx.background_executor()
                            .spawn(async move { discover_processes(&client, device, &cancel) })
                    });
                    let devices = futures::future::join_all(tasks).await;
                    Ok((executable, devices))
                }
                Err(error) => Err(error),
            };
            let _ = this.update(cx, |this, cx| {
                let state = &mut this.memory_compare;
                state.discovering = false;
                state.discovery_cancel = None;
                // Once Start captured the selected targets, discovery may not
                // rebuild the controls or alter the target list for the active run.
                if !state.running() {
                    match result {
                        Ok((executable, devices)) => {
                            state.executable = Some(executable);
                            state.discovery_error = None;
                            state.update_devices(devices);
                        }
                        Err(error) => {
                            state.executable = None;
                            state.discovery_error = Some(error);
                        }
                    }
                }
                cx.notify();
            });
        })
        .detach();
    }

    fn memory_compare_select(&mut self, serial: &str, pid: Option<u32>, cx: &mut Context<Self>) {
        let state = &mut self.memory_compare;
        if !state.targets_editable() {
            return;
        }
        if let Some(row) = state
            .devices
            .iter_mut()
            .find(|row| row.device.serial == serial)
        {
            row.selected =
                pid.filter(|pid| row.processes.iter().any(|process| process.pid == *pid));
            cx.notify();
        }
    }

    fn memory_compare_start(&mut self, cx: &mut Context<Self>) {
        let state = &mut self.memory_compare;
        if !state.targets_editable() {
            return;
        }
        let Some(executable) = state.executable.clone() else {
            return;
        };
        let targets = state.selected_targets();
        if targets.len() < 2 {
            return;
        }

        let id = state.next_session;
        state.next_session = id.wrapping_add(1);
        let started = Instant::now();
        let cancel = CancellationToken::new();
        state.session = Some(Session {
            id,
            started,
            stopped_at: None,
            cancel: cancel.clone(),
            series: targets.iter().cloned().map(Series::new).collect(),
        });
        state.section = MetricSection::Summary;
        let executable = Arc::new(executable);
        for (index, target) in targets.into_iter().enumerate() {
            let executable = executable.clone();
            let cancel = cancel.clone();
            cx.spawn(async move |this, cx| {
                while !cancel.is_cancelled() {
                    let begun = Instant::now();
                    let outcome = cx
                        .background_executor()
                        .spawn({
                            let target = target.clone();
                            let executable = executable.clone();
                            let cancel = cancel.clone();
                            async move { read_sample(&executable, &target, &cancel) }
                        })
                        .await;
                    let accepted = this
                        .update(cx, |this, cx| {
                            let accepted = this
                                .memory_compare
                                .session
                                .as_mut()
                                .is_some_and(|session| session.record(id, index, outcome));
                            if accepted {
                                cx.notify();
                            }
                            accepted
                        })
                        .unwrap_or(false);
                    if !accepted {
                        break;
                    }
                    // Devices are independent. A timeout must not delay other devices
                    // or cause back-to-back probes on the stalled one.
                    let delay = POLL_INTERVAL
                        .saturating_sub(begun.elapsed())
                        .max(Duration::from_millis(100));
                    cx.background_executor().timer(delay).await;
                }
            })
            .detach();
        }
        cx.notify();
    }

    fn memory_compare_stop(&mut self, cx: &mut Context<Self>) {
        if let Some(session) = &mut self.memory_compare.session {
            session.stop();
            cx.notify();
        }
    }

    fn memory_compare_export_ready(&self) -> bool {
        !self.busy
            && !self.export_busy
            && self.memory_compare.session.as_ref().is_some_and(|session| {
                session
                    .series
                    .iter()
                    .any(|series| !series.samples.is_empty())
            })
    }

    fn memory_compare_export_html(&mut self, cx: &mut Context<Self>) {
        if !self.memory_compare_export_ready() {
            return;
        }
        let Some(session) = self.memory_compare.session.clone() else {
            return;
        };
        // Freeze the retained samples when the user clicks Export. A running
        // comparison can keep sampling while the picker and report are open.
        let elapsed = session.latest_elapsed();
        let directory = aps_platform::home_directory().unwrap_or_else(|_| PathBuf::from("."));
        let filename = format!(
            "{}-{}.html",
            safe_report_stem(
                session
                    .series
                    .first()
                    .map(|series| series.target.package_name.as_str())
                    .unwrap_or("memory"),
            ),
            report_timestamp(),
        );
        let picker = cx.prompt_for_new_path(&directory, Some(&filename));
        self.export_busy = true;
        self.export_generation = self.export_generation.wrapping_add(1);
        self.memory_state.notice = None;
        self.memory_state.error = None;
        let generation = self.export_generation;
        let language = self.language;
        cx.spawn(async move |this, cx| {
            let chosen = picker.await;
            let target = this
                .update(cx, |this, cx| {
                    if this.export_generation != generation {
                        return None;
                    }
                    match chosen {
                        Ok(Ok(Some(path))) => Some(path),
                        Ok(Err(_)) => {
                            this.memory_state.error =
                                Some(language.text(TextKey::ImportUiSavePickerFailed).to_owned());
                            this.export_busy = false;
                            cx.notify();
                            None
                        }
                        _ => {
                            this.export_busy = false;
                            cx.notify();
                            None
                        }
                    }
                })
                .ok()
                .flatten();
            let Some(target) = target else {
                return;
            };
            let result = cx
                .background_executor()
                .spawn(async move {
                    let target = html_report_path(target).ok_or_else(|| {
                        language
                            .text(TextKey::MemoryCompareReportExtension)
                            .to_owned()
                    })?;
                    let html = report::render_html(&session, elapsed, language)?;
                    crate::import::export::write_new_file(&target, html.as_bytes())?;
                    Ok::<_, String>(target)
                })
                .await;
            let _ = this.update(cx, |this, cx| {
                if this.export_generation != generation {
                    return;
                }
                this.export_busy = false;
                match result {
                    Ok(path) => {
                        this.memory_state.notice = Some(language.format(
                            TextKey::MemoryCompareReportSaved,
                            &[("path", &path.display())],
                        ));
                        this.memory_state.error = None;
                    }
                    Err(error) => this.memory_state.error = Some(error),
                }
                cx.notify();
            });
        })
        .detach();
        cx.notify();
    }
}

fn safe_report_stem(package: &str) -> String {
    let mut stem: String = package
        .chars()
        .map(|ch| {
            if ch.is_ascii_alphanumeric() || matches!(ch, '.' | '-' | '_') {
                ch
            } else {
                '_'
            }
        })
        .collect();
    while stem.ends_with('.') || stem.ends_with(' ') {
        stem.pop();
    }
    if stem.is_empty() || stem == "." || stem == ".." {
        "memory".into()
    } else {
        stem.chars().take(96).collect()
    }
}

fn report_timestamp() -> String {
    let millis = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_or(0, |duration| duration.as_millis() as i64);
    let days = millis.div_euclid(86_400_000);
    let day_millis = millis.rem_euclid(86_400_000);
    let (year, month, day) = civil_from_days(days);
    let hour = day_millis / 3_600_000;
    let minute = (day_millis / 60_000) % 60;
    let second = (day_millis / 1_000) % 60;
    format!("{year:04}{month:02}{day:02}-{hour:02}{minute:02}{second:02}")
}

fn civil_from_days(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let doe = z - era * 146_097;
    let yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365;
    let y = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = doy - (153 * mp + 2) / 5 + 1;
    let month = if mp < 10 { mp + 3 } else { mp - 9 };
    let year = if month <= 2 { y + 1 } else { y };
    (year, month as u32, day as u32)
}

fn html_report_path(mut path: PathBuf) -> Option<PathBuf> {
    let Some(extension) = path.extension() else {
        return path.set_extension("html").then_some(path);
    };
    let is_html = extension.eq_ignore_ascii_case("html") || extension.eq_ignore_ascii_case("htm");
    is_html.then_some(path)
}

fn discover_processes(
    client: &AdbClient,
    device: AdbDevice,
    cancel: &CancellationToken,
) -> DeviceRow {
    let mut row = DeviceRow {
        device,
        processes: Vec::new(),
        selected: None,
        error: None,
    };
    if row.device.is_online() {
        match client.target_snapshot(&row.device.serial, cancel) {
            Ok(snapshot) => row.processes = snapshot.application_processes(),
            Err(error) => row.error = Some(error.to_string()),
        }
    }
    row
}

fn sample_request(executable: &Path, target: &Target) -> Result<ProcessRequest, String> {
    validate_serial(&target.serial).map_err(|error| error.to_string())?;
    if target.pid == 0 {
        return Err("Invalid Android process ID".into());
    }
    let mut request = ProcessRequest::new(executable).args([
        "-s",
        &target.serial,
        "shell",
        "dumpsys",
        "meminfo",
        "-a",
        &target.pid.to_string(),
    ]);
    request.timeout = COMMAND_TIMEOUT;
    request.stdout_limit = 1024 * 1024;
    request.stderr_limit = 64 * 1024;
    Ok(request)
}

fn read_sample(
    executable: &Path,
    target: &Target,
    cancel: &CancellationToken,
) -> Result<MeminfoSample, String> {
    let request = sample_request(executable, target)?;
    let output = ProcessRunner
        .run(&request, cancel)
        .map_err(|error| error.to_string())?;
    if !output.status.success() {
        let detail = String::from_utf8_lossy(&output.stderr);
        let detail = detail
            .lines()
            .find(|line| !line.trim().is_empty())
            .unwrap_or_default();
        return if detail.is_empty() {
            Err(format!("adb meminfo exited with {}", output.status))
        } else {
            Err(detail.chars().take(240).collect())
        };
    }
    let text = String::from_utf8_lossy(&output.stdout);
    if let Some(message) = text
        .lines()
        .find(|line| line.trim_start().starts_with("No process found for:"))
    {
        return Err(message.trim().chars().take(240).collect());
    }
    parse_meminfo(&text, target.pid, &target.process_name)
}

#[cfg(test)]
mod tests {
    use super::*;
    use aps_adb::parse_devices;

    fn target(serial: &str, pid: u32) -> Target {
        Target {
            serial: serial.into(),
            device_name: serial.into(),
            pid,
            process_name: "com.example.app".into(),
            package_name: "com.example.app".into(),
        }
    }

    fn row(serial: &str, pid: u32, name: &str) -> DeviceRow {
        let device = parse_devices(&format!(
            "List of devices attached\n{serial} device model:Example\n"
        ))
        .unwrap()
        .remove(0);
        DeviceRow {
            device,
            processes: vec![AndroidApplicationProcess {
                pid,
                parent_pid: 1,
                user: "u0_a123".into(),
                name: name.into(),
                package_name: name.into(),
            }],
            selected: Some(pid),
            error: None,
        }
    }

    #[::core::prelude::v1::test]
    fn an_in_flight_refresh_does_not_disable_previously_discovered_targets() {
        let mut state = LiveCompareUi::default();
        state.discovering = true;
        assert!(
            !state.targets_editable(),
            "initial discovery has no published ADB target"
        );
        state.executable = Some("adb".into());
        state.devices = vec![row("first", 1, "com.same"), row("second", 2, "com.same")];
        assert_eq!(state.selected_targets().len(), 2);
        assert!(
            state.targets_editable(),
            "background refresh must not consume Start or process selection"
        );
    }

    #[::core::prelude::v1::test]
    fn same_or_different_apps_are_routed_to_their_own_serial_and_pid() {
        let mut ui = LiveCompareUi::default();
        ui.devices = vec![
            row("first", 90, "com.same"),
            row("second", 90, "com.same"),
            row("third", 91, "com.other"),
        ];
        let targets = ui.selected_targets();
        assert_eq!(targets.len(), 3);
        assert_eq!(targets[0].process_name, targets[1].process_name);
        assert_ne!(targets[0].process_name, targets[2].process_name);
        for target in targets {
            let request = sample_request(Path::new("adb"), &target).unwrap();
            let expected = [
                "-s",
                &target.serial,
                "shell",
                "dumpsys",
                "meminfo",
                "-a",
                &target.pid.to_string(),
            ];
            assert!(
                request
                    .arguments
                    .iter()
                    .map(|arg| arg.to_str().unwrap())
                    .eq(expected)
            );
        }
    }

    #[::core::prelude::v1::test]
    fn refresh_retains_only_the_same_descriptor_on_the_same_device() {
        let mut ui = LiveCompareUi::default();
        ui.devices = vec![
            row("first", 90, "com.same"),
            row("second", 90, "com.same"),
            row("third", 91, "com.other"),
        ];
        ui.update_devices(vec![
            row("second", 90, "com.changed"),
            row("first", 90, "com.same"),
            row("third", 92, "com.other"),
        ]);
        assert_eq!(
            ui.devices
                .iter()
                .map(|row| row.selected)
                .collect::<Vec<_>>(),
            [None, Some(90), None]
        );
    }

    #[::core::prelude::v1::test]
    fn an_unchanged_refresh_reports_no_change_and_keeps_every_selection() {
        // An identical discovery must not rebuild rows: rebuilding drops the
        // open process menu and the query the operator is typing.
        let mut ui = LiveCompareUi::default();
        ui.devices = vec![row("first", 90, "com.same"), row("second", 91, "com.other")];
        let unchanged = vec![row("first", 90, "com.same"), row("second", 91, "com.other")];
        assert!(!ui.update_devices(unchanged));
        assert_eq!(
            ui.devices
                .iter()
                .map(|row| row.selected)
                .collect::<Vec<_>>(),
            [Some(90), Some(91)]
        );
    }

    #[::core::prelude::v1::test]
    fn a_failed_device_adds_a_gap_clears_raw_and_does_not_affect_other_devices() {
        let mut first = Series::new(target("first", 42));
        let mut second = Series::new(target("second", 42));
        let sample = || {
            Ok(MeminfoSample {
                raw: "dumpsys".into(),
                metrics: BTreeMap::new(),
            })
        };
        first.record(Duration::ZERO, sample());
        second.record(Duration::ZERO, sample());
        first.record(POLL_INTERVAL, Err("device offline".into()));
        assert!(first.samples.front().unwrap().metrics.is_some());
        assert!(first.samples.back().unwrap().metrics.is_none());
        assert!(first.latest_raw.is_none());
        assert_eq!(first.error.as_deref(), Some("device offline"));
        assert_eq!(second.latest_raw.as_deref(), Some("dumpsys"));
        first.record(POLL_INTERVAL * 2, sample());
        assert!(first.error.is_none());
        assert!(first.samples.back().unwrap().metrics.is_some());
    }

    #[::core::prelude::v1::test]
    fn stopped_or_replaced_sessions_reject_late_worker_results() {
        let mut session = Session {
            id: 2,
            started: Instant::now(),
            stopped_at: None,
            cancel: CancellationToken::new(),
            series: vec![Series::new(target("first", 42))],
        };
        assert!(!session.record(1, 0, Err("old run".into())));
        assert!(session.record(2, 0, Err("active run".into())));
        session.stop();
        assert!(session.cancel.is_cancelled());
        assert!(!session.record(2, 0, Err("stopped run".into())));
        assert_eq!(session.series[0].samples.len(), 1);
        assert_eq!(session.series[0].error.as_deref(), Some("active run"));
    }

    #[::core::prelude::v1::test]
    fn report_filename_uses_safe_package_and_timestamp() {
        assert_eq!(
            safe_report_stem("com.example.app:remote"),
            "com.example.app_remote"
        );
        assert_eq!(safe_report_stem("C:/bad\\name"), "C__bad_name");
        assert!(report_timestamp().contains('-'));
    }

    #[::core::prelude::v1::test]
    fn export_target_has_an_html_extension() {
        assert_eq!(
            html_report_path("comparison".into()),
            Some("comparison.html".into())
        );
        assert_eq!(
            html_report_path("comparison.HTM".into()),
            Some("comparison.HTM".into())
        );
        assert!(html_report_path("comparison.svg".into()).is_none());
    }

    #[::core::prelude::v1::test]
    fn live_numeric_values_refresh_and_never_reuse_missing_observations() {
        let key = MetricKey {
            section: MetricSection::Summary,
            category: "Total".into(),
            measure: "PSS".into(),
            unit: meminfo::MetricUnit::Kib,
        };
        let mut series = Series::new(target("first", 42));
        assert_eq!(series.current_value(&key), None);
        for (seconds, value) in [(1, 123), (2, 456), (3, 0)] {
            series.record(
                Duration::from_secs(seconds),
                Ok(MeminfoSample {
                    metrics: BTreeMap::from([(key.clone(), value)]),
                    raw: String::new(),
                }),
            );
            assert_eq!(series.current_value(&key), Some(value));
        }
        series.record(Duration::from_secs(4), Err("offline".into()));
        assert_eq!(series.current_value(&key), None);
        series.record(
            Duration::from_secs(5),
            Ok(MeminfoSample {
                metrics: BTreeMap::new(),
                raw: String::new(),
            }),
        );
        assert_eq!(series.current_value(&key), None);
    }

    #[::core::prelude::v1::test]
    fn history_is_bounded_by_both_time_and_count() {
        let mut series = Series::new(target("device", 42));
        for tick in 0..(HISTORY_POINTS * 2) {
            series.record(Duration::from_millis(tick as u64), Err("test".into()));
        }
        assert_eq!(series.samples.len(), HISTORY_POINTS);
        series.record(HISTORY_WINDOW * 2, Err("test".into()));
        assert_eq!(series.samples.len(), 1);
    }
}

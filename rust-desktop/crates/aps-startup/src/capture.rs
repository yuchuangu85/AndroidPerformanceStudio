//! Explicit, bounded Android launch experiments. All operations are blocking and
//! must run off the UI thread after the caller obtains target-specific approval.
//! No application data, log buffers, compilation settings or Profile are reset.
use crate::{DurationMetric, StartupMode, StartupRun, StartupSession, parser};
use aps_adb::{AdbClient, AdbLocator, validate_serial};
use aps_platform::{CancellationToken, ProcessRequest, ProcessRunner, TempWorkspace, atomic_write};
use std::{
    collections::{BTreeMap, BTreeSet},
    path::{Path, PathBuf},
    sync::atomic::{AtomicU32, AtomicU64, Ordering},
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

const COMMAND_TIMEOUT: Duration = Duration::from_secs(10);
const MAX_EXPERIMENT: Duration = Duration::from_secs(15 * 60);
const POLL_INTERVAL: Duration = Duration::from_millis(250);
const OUTPUT_LIMIT: usize = 2 * 1024 * 1024;
static NEXT_EXPERIMENT: AtomicU64 = AtomicU64::new(0);

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StartupCaptureTarget {
    pub serial: String,
    /// The selected process is verified before the first lifecycle operation.
    /// Its PID is not pinned across intentional Cold-mode process restarts.
    pub pid: u32,
    pub package_name: String,
    pub process_name: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StartupCaptureConfig {
    pub mode: StartupMode,
    pub iterations: u32,
    pub launch_timeout: Duration,
    pub settle_delay: Duration,
    pub fully_drawn_wait: Duration,
}

impl Default for StartupCaptureConfig {
    fn default() -> Self {
        Self {
            mode: StartupMode::Cold,
            iterations: 5,
            launch_timeout: Duration::from_secs(30),
            settle_delay: Duration::from_millis(500),
            fully_drawn_wait: Duration::from_secs(2),
        }
    }
}

/// Retain this owner as long as the displayed report uses its evidence paths.
/// Drop removes only this experiment's exclusively created private directory.
pub struct StartupCaptureArtifact {
    pub path: PathBuf,
    _workspace: TempWorkspace,
}

pub struct CapturedStartup {
    pub session: StartupSession,
    pub artifact: StartupCaptureArtifact,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct InstallationIdentity {
    boot_id: String,
    user: u32,
    uid: u32,
    version: String,
    code_path: String,
    first_install: String,
    last_update: String,
}

struct AndroidRunner<'a> {
    client: AdbClient,
    target: &'a StartupCaptureTarget,
    cancel: &'a CancellationToken,
    deadline: Instant,
}

/// Runs only after explicit approval of `target` and `config`. Cancellation
/// prevents subsequent commands, but cannot roll back app lifecycle changes.
/// A failed/cancelled experiment is never returned as a successful partial one.
pub fn capture_startup(
    target: &StartupCaptureTarget,
    config: &StartupCaptureConfig,
    root: &Path,
    cancel: &CancellationToken,
    progress: &AtomicU32,
) -> Result<CapturedStartup, String> {
    validate(target, config, root)?;
    if cancel.is_cancelled() {
        return Err("Startup experiment was cancelled before device discovery.".into());
    }
    let location = AdbLocator::new().locate(None).map_err(|e| e.to_string())?;
    let runner = AndroidRunner {
        client: AdbClient::new(location).with_timeout(COMMAND_TIMEOUT),
        target,
        cancel,
        deadline: Instant::now() + MAX_EXPERIMENT,
    };
    runner.check()?;
    let devices = runner
        .client
        .list_devices(cancel)
        .map_err(|e| e.to_string())?;
    if !devices
        .iter()
        .any(|d| d.serial == target.serial && d.is_online())
    {
        return Err("The approved startup device is disconnected or unauthorized.".into());
    }
    let identity = runner.identity()?;
    runner.verify_selected_process(&identity)?;
    let component = runner.resolve_component(identity.user)?;
    runner.verify_identity(&identity)?;
    let workspace = TempWorkspace::create(root).map_err(|e| {
        format!(
            "Cannot create the private startup workspace ({:?}).",
            e.kind()
        )
    })?;
    let stamp = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map_err(|_| "Host clock predates the Unix epoch")?
        .as_nanos();
    let experiment_id = format!(
        "live-{stamp:x}-{:x}",
        NEXT_EXPERIMENT.fetch_add(1, Ordering::Relaxed)
    );
    let mut runs = Vec::with_capacity(config.iterations as usize);
    progress.store(0, Ordering::Release);

    for iteration in 1..=config.iterations {
        runner.verify_identity(&identity)?;
        let prefix = format!("run-{iteration:03}");
        let mut warnings = Vec::new();
        let mut environment = runner.environment(&mut warnings)?;
        // Environment probes can take time; revalidate immediately before the
        // first lifecycle operation rather than using the earlier snapshot.
        runner.verify_identity(&identity)?;
        // Warm/Hot preparation is deliberately not counted as a measured run.
        match config.mode {
            StartupMode::Cold => {
                runner.shell(
                    &[
                        "am",
                        "force-stop",
                        "--user",
                        &identity.user.to_string(),
                        &target.package_name,
                    ],
                    COMMAND_TIMEOUT,
                )?;
            }
            StartupMode::Warm | StartupMode::Hot => {
                if runner.package_pids(&identity)?.is_empty() {
                    let prepared = runner.launch(
                        &component,
                        identity.user,
                        StartupMode::Hot,
                        config.launch_timeout,
                    )?;
                    write_evidence(
                        workspace.path(),
                        &format!("{prefix}-preparation.txt"),
                        &prepared,
                    )?;
                    parse_successful_launch(&prepared, &target.package_name, StartupMode::Hot)
                        .map_err(|error| format!("Startup preparation failed: {error}"))?;
                }
                runner.shell(&["input", "keyevent", "HOME"], COMMAND_TIMEOUT)?;
            }
            StartupMode::Unknown => unreachable!("validated launch mode"),
        }
        runner.wait(config.settle_delay)?;
        runner.verify_identity(&identity)?;
        let pids_before = runner.package_pids(&identity)?;
        if config.mode == StartupMode::Cold && !pids_before.is_empty() {
            return Err("The approved package still has a process after force-stop; the Cold experiment was stopped.".into());
        }
        let before = runner.read_event_log(&mut warnings)?;
        let output = runner.launch(
            &component,
            identity.user,
            config.mode,
            config.launch_timeout,
        )?;
        write_evidence(workspace.path(), &format!("{prefix}-am-start.txt"), &output)?;
        let mut run = parse_successful_launch(&output, &target.package_name, config.mode)?;
        run.origin = crate::StartupEvidenceOrigin::LiveCapture;
        run.iteration = iteration;
        run.id = format!("{experiment_id}-{iteration}");
        let events =
            runner.collect_events(before.as_deref(), &mut warnings, config.fully_drawn_wait)?;
        write_evidence(
            workspace.path(),
            &format!("{prefix}-target-events.txt"),
            &events,
        )?;
        apply_scoped_events(&mut run, &events, identity.user, &mut warnings);
        let pids_after = runner.package_pids(&identity)?;
        runner.verify_identity(&identity)?;
        if pids_after.is_empty() {
            warnings.push("No target-package process was observed after launch. Platform timing is not proof that the app stayed running; no crash cause is inferred.".into());
        }
        if run.observed_mode == StartupMode::Unknown {
            warnings.push("Activity Manager did not provide a recognized LaunchState; requested mode and PID observations are not substituted.".into());
        } else if run.observed_mode != config.mode {
            warnings
                .push("Platform LaunchState differs from the requested experiment mode.".into());
        }
        warnings.push("Current compilation/Profile state was preserved, not independently verified. No comparable-cohort or Agent phase evidence is claimed.".into());
        environment.extend([
            ("captureSource".into(), "live-platform".into()),
            ("experimentId".into(), experiment_id.clone()),
            ("serial".into(), target.serial.clone()),
            ("bootId".into(), identity.boot_id.clone()),
            ("userId".into(), identity.user.to_string()),
            ("packageUid".into(), identity.uid.to_string()),
            ("versionCode".into(), identity.version.clone()),
            ("codePath".into(), identity.code_path.clone()),
            ("firstInstallTime".into(), identity.first_install.clone()),
            ("lastUpdateTime".into(), identity.last_update.clone()),
            ("component".into(), component.clone()),
            ("selectedPid".into(), target.pid.to_string()),
            ("pidsBefore".into(), join_pids(&pids_before)),
            ("pidsAfter".into(), join_pids(&pids_after)),
            ("compilationVerified".into(), "false".into()),
            ("profileSourceDeclared".into(), "false".into()),
            ("eventScope".into(), "new target event lines, exact user/component and Activity token; ambiguous or unmatched metrics unavailable".into()),
            ("amStartEvidence".into(), format!("{prefix}-am-start.txt")),
            ("eventEvidence".into(), format!("{prefix}-target-events.txt")),
        ]);
        run.imported_fields = environment;
        run.warnings.extend(warnings);
        runs.push(run);
        progress.store(iteration, Ordering::Release);
    }
    runner.check()?;
    let session = StartupSession::from_captured_runs(runs)?;
    let path = workspace.path().join("experiment.txt");
    let mut manifest = format!(
        "format=aps-live-startup-v1\nexperimentId={experiment_id}\npackage={}\nserial={}\nbootId={}\nuserId={}\npackageUid={}\nversionCode={}\ncomponent={component}\nrequestedMode={:?}\nruns={}\nlaunchTimeoutMs={}\nsettleDelayMs={}\nfullyDrawnWaitMs={}\ncompilationState=preserved-unverified\n",
        target.package_name,
        target.serial,
        identity.boot_id,
        identity.user,
        identity.uid,
        identity.version,
        config.mode,
        config.iterations,
        config.launch_timeout.as_millis(),
        config.settle_delay.as_millis(),
        config.fully_drawn_wait.as_millis()
    );
    for run in session.runs() {
        manifest.push_str(&format!(
            "\n[{}]\nobservedMode={:?}\nstatus={}\nTTID={:?}\nTTFD={:?}\n",
            run.id,
            run.observed_mode,
            run.status.as_deref().unwrap_or("unavailable"),
            run.ttid.milliseconds,
            run.ttfd.milliseconds
        ));
        for (key, value) in &run.imported_fields {
            manifest.push_str(&format!("{key}={value}\n"));
        }
        if let Some(reason) = &run.ttid.unavailable_reason {
            manifest.push_str(&format!("ttidUnavailableReason={reason}\n"));
        }
        if let Some(reason) = &run.ttfd.unavailable_reason {
            manifest.push_str(&format!("ttfdUnavailableReason={reason}\n"));
        }
        for warning in &run.warnings {
            manifest.push_str(&format!("warning={warning}\n"));
        }
    }
    write_evidence(workspace.path(), "experiment.txt", &manifest)?;
    runner.check()?;
    Ok(CapturedStartup {
        session,
        artifact: StartupCaptureArtifact {
            path,
            _workspace: workspace,
        },
    })
}

impl AndroidRunner<'_> {
    fn check(&self) -> Result<(), String> {
        if self.cancel.is_cancelled() {
            return Err("Startup experiment cancelled; completed app lifecycle changes are not rolled back.".into());
        }
        if Instant::now() >= self.deadline {
            return Err("Startup experiment exceeded its 15-minute total deadline.".into());
        }
        Ok(())
    }

    fn shell(&self, args: &[&str], timeout: Duration) -> Result<String, String> {
        self.shell_with_diagnostics(args, timeout, false)
    }

    fn shell_with_diagnostics(
        &self,
        args: &[&str],
        timeout: Duration,
        include_stderr: bool,
    ) -> Result<String, String> {
        self.check()?;
        // ADB joins remote argv through the device shell. Quote every token,
        // including '$' in a valid nested Activity class; never use sh -c.
        let mut request = ProcessRequest::new(&self.client.location().executable)
            .args(["-s", &self.target.serial, "shell"])
            .args(
                args.iter()
                    .map(|arg| format!("'{}'", arg.replace('\'', "'\\''"))),
            );
        request.timeout = timeout.min(self.deadline.saturating_duration_since(Instant::now()));
        request.stdout_limit = OUTPUT_LIMIT;
        request.stderr_limit = 64 * 1024;
        let output = ProcessRunner
            .run(&request, self.cancel)
            .map_err(|e| e.to_string())?;
        self.check()?;
        if !output.status.success() {
            return Err(format!(
                "Startup ADB command failed (exit {:?}); the prior report is retained.",
                output.status.code()
            ));
        }
        let mut stdout =
            String::from_utf8(output.stdout).map_err(|_| "Startup command output is not UTF-8")?;
        if include_stderr && !output.stderr.is_empty() {
            let stderr = String::from_utf8(output.stderr)
                .map_err(|_| "Startup command diagnostics are not UTF-8")?;
            if stdout.len().saturating_add(stderr.len()).saturating_add(1) > OUTPUT_LIMIT {
                return Err(
                    "Startup command and diagnostics exceed the output byte budget.".into(),
                );
            }
            stdout.push('\n');
            stdout.push_str(&stderr);
        }
        parser::validate_text(&stdout)?;
        Ok(stdout)
    }

    fn wait(&self, duration: Duration) -> Result<(), String> {
        let end = Instant::now() + duration;
        while Instant::now() < end {
            self.check()?;
            thread::sleep(
                end.saturating_duration_since(Instant::now())
                    .min(Duration::from_millis(25)),
            );
        }
        self.check()
    }

    fn identity(&self) -> Result<InstallationIdentity, String> {
        let boot_id = self
            .shell(&["cat", "/proc/sys/kernel/random/boot_id"], COMMAND_TIMEOUT)?
            .trim()
            .to_owned();
        if !valid_boot_id(&boot_id) {
            return Err("Cannot verify the device boot identity.".into());
        }
        let user: u32 = self
            .shell(&["am", "get-current-user"], COMMAND_TIMEOUT)?
            .trim()
            .parse()
            .map_err(|_| "Cannot verify the current Android user")?;
        let packages = self.shell(
            &[
                "cmd",
                "package",
                "list",
                "packages",
                "-U",
                "--user",
                &user.to_string(),
                &self.target.package_name,
            ],
            COMMAND_TIMEOUT,
        )?;
        let expected = format!("package:{}", self.target.package_name);
        let mut uids = packages.lines().filter_map(|line| {
            let mut fields = line.split_whitespace();
            if fields.next()? != expected {
                return None;
            }
            fields.find_map(|field| {
                field
                    .strip_prefix("uid:")
                    .and_then(|v| v.parse::<u32>().ok())
            })
        });
        let uid = uids
            .next()
            .ok_or("Cannot verify the approved package UID for the current Android user")?;
        if uids.next().is_some() || uid / 100_000 != user {
            return Err(
                "Package UID identity is ambiguous or belongs to a different Android user.".into(),
            );
        }
        let dump = self.shell(
            &["dumpsys", "package", &self.target.package_name],
            COMMAND_TIMEOUT,
        )?;
        // Ignore disabled/hidden system-package history and other package
        // sections. Updated system apps can have a second, older Package block.
        let package_lines = active_package_lines(&dump, &self.target.package_name)?;
        let first_user = package_lines
            .iter()
            .position(|line| {
                line.trim()
                    .strip_prefix("User ")
                    .and_then(|s| s.split_once(':'))
                    .is_some_and(|(id, _)| id.parse::<u32>().is_ok())
            })
            .unwrap_or(package_lines.len());
        let root_lines = &package_lines[..first_user];
        let current_user = format!("User {user}:");
        let user_lines = package_lines
            .iter()
            .position(|line| line.trim().starts_with(&current_user))
            .map(|start| {
                let indent = package_lines[start].len() - package_lines[start].trim_start().len();
                let end = package_lines
                    .iter()
                    .enumerate()
                    .skip(start + 1)
                    .find(|(_, line)| {
                        !line.trim().is_empty() && line.len() - line.trim_start().len() <= indent
                    })
                    .map_or(package_lines.len(), |(index, _)| index);
                &package_lines[start + 1..end]
            })
            .unwrap_or(&[]);
        let field = |name: &str| -> Result<String, String> {
            let key = format!("{name}=");
            let scoped_lines = if name == "firstInstallTime"
                && user_lines.iter().any(|line| line.trim().starts_with(&key))
            {
                user_lines
            } else {
                root_lines
            };
            let values: BTreeSet<_> = scoped_lines
                .iter()
                .filter_map(|line| line.trim().strip_prefix(&key))
                .map(|s| {
                    if name == "versionCode" {
                        s.split_whitespace().next().unwrap_or("")
                    } else {
                        s.trim()
                    }
                })
                .filter(|s| !s.is_empty() && s.len() <= 2048)
                .collect();
            if values.len() != 1 {
                return Err(format!(
                    "Package {name} identity is unavailable or ambiguous; refresh the target."
                ));
            }
            Ok(values
                .into_iter()
                .next()
                .expect("one identity value")
                .into())
        };
        Ok(InstallationIdentity {
            boot_id,
            user,
            uid,
            version: field("versionCode")?,
            code_path: field("codePath")?,
            first_install: field("firstInstallTime")?,
            last_update: field("lastUpdateTime")?,
        })
    }

    fn verify_identity(&self, identity: &InstallationIdentity) -> Result<(), String> {
        if &self.identity()? != identity {
            return Err("The device boot, Android user or package installation changed; the startup experiment was stopped.".into());
        }
        self.check()
    }

    fn verify_selected_process(&self, identity: &InstallationIdentity) -> Result<(), String> {
        self.check()?;
        let snapshot = self
            .client
            .target_snapshot(&self.target.serial, self.cancel)
            .map_err(|e| e.to_string())?;
        if !snapshot.application_processes().iter().any(|p| {
            p.pid == self.target.pid
                && p.package_name == self.target.package_name
                && p.name == self.target.process_name
        }) {
            return Err("The approved process is no longer associated with the target package. Select the current process and review again.".into());
        }
        let pids = self.package_pids(identity)?;
        if !pids.contains(&self.target.pid) {
            return Err("The approved process UID or Android user changed; select the current process again.".into());
        }
        self.check()
    }

    fn package_pids(&self, identity: &InstallationIdentity) -> Result<Vec<u32>, String> {
        let output = self.shell(&["ps", "-A", "-o", "PID,UID,NAME"], COMMAND_TIMEOUT)?;
        let mut lines = output.lines();
        if lines
            .next()
            .map(|line| line.split_whitespace().collect::<Vec<_>>())
            != Some(vec!["PID", "UID", "NAME"])
        {
            return Err("Cannot verify the startup process table header.".into());
        }
        let mut pids = Vec::new();
        for line in lines.filter(|line| !line.trim().is_empty()) {
            let fields: Vec<_> = line.split_whitespace().collect();
            if fields.len() != 3 {
                return Err("Cannot verify the startup process table format.".into());
            }
            let package = fields[2] == self.target.package_name
                || fields[2]
                    .strip_prefix(&self.target.package_name)
                    .is_some_and(|s| s.starts_with(':'));
            if package {
                let uid = fields[1]
                    .parse::<u32>()
                    .map_err(|_| "Process table did not report numeric UIDs")?;
                if uid == identity.uid {
                    pids.push(
                        fields[0]
                            .parse::<u32>()
                            .map_err(|_| "Invalid startup process PID")?,
                    );
                }
            }
        }
        pids.sort_unstable();
        Ok(pids)
    }

    fn resolve_component(&self, user: u32) -> Result<String, String> {
        let output = self.shell(
            &[
                "cmd",
                "package",
                "resolve-activity",
                "--brief",
                "--user",
                &user.to_string(),
                "-a",
                "android.intent.action.MAIN",
                "-c",
                "android.intent.category.LAUNCHER",
                &self.target.package_name,
            ],
            COMMAND_TIMEOUT,
        )?;
        let components: BTreeSet<_> = output
            .lines()
            .map(str::trim)
            .filter(|s| valid_component(s, &self.target.package_name))
            .collect();
        if components.len() != 1 {
            return Err("The approved package has no unambiguous launcher Activity for the current Android user.".into());
        }
        Ok(components.into_iter().next().expect("one component").into())
    }

    fn launch(
        &self,
        component: &str,
        user: u32,
        mode: StartupMode,
        timeout: Duration,
    ) -> Result<String, String> {
        let user = user.to_string();
        let args = launch_arguments(component, &user, mode);
        self.shell_with_diagnostics(&args, timeout, true)
    }

    fn read_event_log(&self, warnings: &mut Vec<String>) -> Result<Option<String>, String> {
        match self.shell(
            &["logcat", "-d", "-b", "events", "-v", "epoch", "-t", "2000"],
            COMMAND_TIMEOUT,
        ) {
            Ok(value) => Ok(Some(value)),
            Err(error) => {
                self.check()?;
                warnings.push(format!("Startup events unavailable: {error}"));
                Ok(None)
            }
        }
    }

    fn collect_events(
        &self,
        before: Option<&str>,
        warnings: &mut Vec<String>,
        wait: Duration,
    ) -> Result<String, String> {
        let Some(before) = before else {
            return Ok(String::new());
        };
        let previous: BTreeSet<_> = before.lines().collect();
        let end = Instant::now() + wait;
        let mut delta = BTreeSet::new();
        let mut bytes = 0usize;
        loop {
            let Some(after) = self.read_event_log(warnings)? else {
                break;
            };
            for line in after.lines() {
                if !previous.contains(line) && target_event(line, &self.target.package_name) {
                    if !delta.contains(line) {
                        bytes = bytes.saturating_add(line.len() + 1);
                        if bytes > OUTPUT_LIMIT {
                            return Err(
                                "New target startup events exceed the capture byte budget.".into(),
                            );
                        }
                        delta.insert(line.to_owned());
                    }
                }
            }
            if Instant::now() >= end {
                break;
            }
            self.wait(POLL_INTERVAL.min(end.saturating_duration_since(Instant::now())))?;
        }
        Ok(delta.into_iter().collect::<Vec<_>>().join("\n"))
    }

    fn environment(&self, warnings: &mut Vec<String>) -> Result<BTreeMap<String, String>, String> {
        let mut fields = BTreeMap::new();
        // Use the same cancellation/deadline-bounded transport as launches;
        // these optional probes must not extend the total experiment deadline.
        for (property, key) in [
            ("ro.product.model", "deviceModel"),
            ("ro.build.version.sdk", "apiLevel"),
            ("ro.kernel.qemu", "emulator"),
        ] {
            match self.shell(&["getprop", property], COMMAND_TIMEOUT) {
                Ok(value) => {
                    let value = value.trim();
                    let value = match key {
                        "apiLevel" => value.parse::<u32>().ok().map(|value| value.to_string()),
                        "emulator" => match value {
                            "1" => Some("true".to_owned()),
                            "0" => Some("false".to_owned()),
                            _ => None,
                        },
                        _ => (!value.is_empty() && value.len() <= 2048).then(|| value.to_owned()),
                    };
                    if let Some(value) = value {
                        fields.insert(key.into(), value);
                    }
                }
                Err(error) => {
                    self.check()?;
                    warnings.push(format!("{key} environment is unavailable: {error}"));
                }
            }
        }
        for (command, label) in [("battery", "battery"), ("thermalservice", "thermal")] {
            match self.shell(&["dumpsys", command], COMMAND_TIMEOUT) {
                Ok(output) if command == "battery" => {
                    let value = |key: &str| {
                        output
                            .lines()
                            .find_map(|line| line.trim().strip_prefix(key).map(str::trim))
                    };
                    if let (Some(level), Some(scale)) = (
                        value("level:").and_then(|s| s.parse::<u32>().ok()),
                        value("scale:").and_then(|s| s.parse::<u32>().ok()),
                    ) && scale > 0
                        && level <= scale
                    {
                        let percent = u64::from(level) * 100 / u64::from(scale);
                        fields.insert("batteryPercent".into(), percent.to_string());
                        if percent < 15 {
                            warnings.push("Battery level is below 15%; no cross-run environmental equivalence is assumed.".into());
                        }
                    }
                    let powered: Vec<_> = ["AC powered:", "USB powered:", "Wireless powered:"]
                        .into_iter()
                        .filter_map(value)
                        .collect();
                    if powered.iter().any(|s| *s == "true") {
                        fields.insert("charging".into(), "true".into());
                    } else if powered.len() == 3 && powered.iter().all(|s| *s == "false") {
                        fields.insert("charging".into(), "false".into());
                    }
                }
                Ok(output) => {
                    if let Some(status) = output.lines().find_map(|line| {
                        line.trim()
                            .strip_prefix("Thermal Status:")
                            .and_then(|s| s.trim().parse::<u32>().ok())
                    }) {
                        fields.insert("thermalStatus".into(), status.to_string());
                        if status >= 3 {
                            warnings.push("Device reports severe or higher thermal status; startup comparisons are not environmentally controlled.".into());
                        }
                    }
                }
                Err(e) => {
                    self.check()?;
                    warnings.push(format!("{label} environment is unavailable: {e}"));
                }
            }
        }
        self.check()?;
        Ok(fields)
    }
}

fn launch_arguments<'a>(component: &'a str, user: &'a str, mode: StartupMode) -> Vec<&'a str> {
    let mut args = vec![
        "am",
        "start",
        "-W",
        "--user",
        user,
        "-n",
        component,
        "-a",
        "android.intent.action.MAIN",
        "-c",
        "android.intent.category.LAUNCHER",
    ];
    if mode == StartupMode::Warm {
        // `--activity-new-task` is not an am Intent option. Preserve
        // NEW_TASK | CLEAR_TASK without force-stopping the warm process.
        args.extend(["-f", "0x10008000"]);
    }
    args
}

fn validate(
    target: &StartupCaptureTarget,
    config: &StartupCaptureConfig,
    root: &Path,
) -> Result<(), String> {
    validate_serial(&target.serial).map_err(|e| e.to_string())?;
    if target.pid == 0
        || !root.is_absolute()
        || !valid_package(&target.package_name)
        || !(target.process_name == target.package_name
            || target
                .process_name
                .strip_prefix(&target.package_name)
                .is_some_and(|s| s.starts_with(':')))
    {
        return Err("Startup capture requires an explicit installed app process and absolute private storage root.".into());
    }
    if !matches!(
        config.mode,
        StartupMode::Cold | StartupMode::Warm | StartupMode::Hot
    ) || !(1..=20).contains(&config.iterations)
        || !(Duration::from_secs(1)..=Duration::from_secs(60)).contains(&config.launch_timeout)
        || config.settle_delay > Duration::from_secs(5)
        || config.fully_drawn_wait > Duration::from_secs(10)
    {
        return Err(
            "Startup experiment configuration exceeds its bounded run, timeout or delay limits."
                .into(),
        );
    }
    Ok(())
}

fn valid_package(package: &str) -> bool {
    !package.is_empty()
        && package.len() <= 1024
        && package.split('.').all(|part| {
            !part.is_empty() && part.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'_')
        })
}

fn valid_boot_id(value: &str) -> bool {
    value.len() == 36
        && value.bytes().enumerate().all(|(index, byte)| {
            if matches!(index, 8 | 13 | 18 | 23) {
                byte == b'-'
            } else {
                byte.is_ascii_hexdigit()
            }
        })
}

fn parse_successful_launch(
    output: &str,
    package: &str,
    requested: StartupMode,
) -> Result<StartupRun, String> {
    let run = parser::am_start(output, package, requested)?;
    if !run
        .status
        .as_deref()
        .is_some_and(|status| status.eq_ignore_ascii_case("ok"))
        || !run.complete
        || !run
            .activity
            .as_deref()
            .is_some_and(|activity| valid_component(activity, package))
    {
        return Err("Activity Manager did not return a completed, successful launch of the approved package; the previous report is retained.".into());
    }
    Ok(run)
}

fn active_package_lines<'a>(dump: &'a str, package: &str) -> Result<Vec<&'a str>, String> {
    let lines: Vec<_> = dump.lines().collect();
    let section = lines
        .iter()
        .position(|line| line.trim() == "Packages:")
        .ok_or("Cannot identify the active installed-package metadata section")?;
    let section_indent = lines[section].len() - lines[section].trim_start().len();
    let section_end = lines
        .iter()
        .enumerate()
        .skip(section + 1)
        .find(|(_, line)| {
            !line.trim().is_empty() && line.len() - line.trim_start().len() <= section_indent
        })
        .map_or(lines.len(), |(index, _)| index);
    let header = format!("Package [{package}] ");
    let start = lines
        .iter()
        .enumerate()
        .take(section_end)
        .skip(section + 1)
        .find(|(_, line)| line.trim().starts_with(&header))
        .map(|(index, _)| index)
        .ok_or("Cannot identify the approved active package metadata")?;
    let indent = lines[start].len() - lines[start].trim_start().len();
    let end = lines
        .iter()
        .enumerate()
        .skip(start + 1)
        .find(|(_, line)| !line.trim().is_empty() && line.len() - line.trim_start().len() <= indent)
        .map_or(lines.len(), |(index, _)| index);
    Ok(lines[start + 1..end].to_vec())
}

fn valid_component(component: &str, package: &str) -> bool {
    component.split_once('/').is_some_and(|(owner, activity)| {
        owner == package
            && !activity.is_empty()
            && activity.len() <= 2048
            && activity
                .bytes()
                .all(|b| b.is_ascii_alphanumeric() || matches!(b, b'.' | b'_' | b'$'))
    })
}

fn target_event(line: &str, package: &str) -> bool {
    if !line.contains("wm_activity_launch_time") && !line.contains("wm_fully_drawn_time") {
        return false;
    }
    event_fields(line).is_some_and(|fields| valid_component(fields[2], package))
}

fn event_fields(line: &str) -> Option<Vec<&str>> {
    let payload = line.rsplit_once('[')?.1.split_once(']')?.0;
    let fields: Vec<_> = payload.split(',').map(str::trim).collect();
    (fields.len() == 4).then_some(fields)
}

fn canonical_component(component: &str) -> Option<String> {
    let (package, activity) = component.split_once('/')?;
    Some(if activity.starts_with('.') {
        format!("{package}/{package}{activity}")
    } else {
        component.into()
    })
}

fn apply_scoped_events(run: &mut StartupRun, events: &str, user: u32, warnings: &mut Vec<String>) {
    let missing = |reason| DurationMetric::missing(reason);
    let Some(activity) = run.activity.as_deref().and_then(canonical_component) else {
        warnings.push("No explicit launched Activity was returned; new event lines cannot be attributed to this launch.".into());
        return;
    };
    let candidates: Vec<_> = events
        .lines()
        .filter_map(|line| {
            let fields = event_fields(line)?;
            (fields[0].parse::<u32>().ok() == Some(user)
                && !fields[1].is_empty()
                && fields[1].len() <= 64
                && !fields[1].chars().any(char::is_whitespace)
                && canonical_component(fields[2]).as_deref() == Some(&activity))
            .then_some((line, fields[1]))
        })
        .collect();
    let displayed: Vec<_> = candidates
        .iter()
        .filter(|(line, _)| line.contains("wm_activity_launch_time"))
        .collect();
    if displayed.len() != 1 {
        let reason = "No unique new Displayed event and Activity token identifies this launch; target events remain unassigned.";
        run.ttid = missing(reason);
        run.ttfd = missing(reason);
        if displayed.len() > 1 {
            warnings.push(reason.into());
        }
        return;
    }
    let token = displayed[0].1;
    let scoped = candidates
        .iter()
        .filter(|(_, value)| *value == token)
        .map(|(line, _)| *line)
        .collect::<Vec<_>>()
        .join("\n");
    match parser::event_log(&scoped, run.package.as_deref().expect("captured package")) {
        Ok((ttid, ttfd))
            if ttfd
                .milliseconds
                .zip(ttid.milliseconds)
                .is_none_or(|(full, initial)| full >= initial) =>
        {
            run.ttid = ttid;
            run.ttfd = ttfd;
        }
        Ok(_) => {
            let reason = "Fully drawn evidence precedes Displayed; inconsistent event durations were not published.";
            run.ttid = missing(reason);
            run.ttfd = missing(reason);
            warnings.push(reason.into());
        }
        Err(error) => {
            run.ttid = missing("Ambiguous or malformed launch events");
            run.ttfd = missing("Ambiguous or malformed launch events");
            warnings.push(error);
        }
    }
}

fn write_evidence(root: &Path, name: &str, text: &str) -> Result<(), String> {
    if text.len() > OUTPUT_LIMIT {
        return Err("Startup evidence exceeds its bounded artifact size.".into());
    }
    atomic_write(&root.join(name), text.as_bytes())
        .map_err(|e| format!("Cannot write owned startup evidence ({:?}).", e.kind()))
}

fn join_pids(pids: &[u32]) -> String {
    pids.iter()
        .map(u32::to_string)
        .collect::<Vec<_>>()
        .join(",")
}

/// Deferred regression sources: all cases are pure or host scratch ownership;
/// none discovers ADB, runs a device command, or starts an Android application.
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn warm_launch_uses_supported_intent_flags_without_killing_the_process() {
        let args = launch_arguments("dev.sample/.MainActivity", "10", StartupMode::Warm);
        assert!(args.windows(2).any(|pair| pair == ["-f", "0x10008000"]));
        assert!(args.windows(2).any(|pair| pair == ["--user", "10"]));
        assert!(!args.contains(&"--activity-new-task"));
        assert!(!args.contains(&"-S"));
        for mode in [StartupMode::Cold, StartupMode::Hot] {
            assert!(!launch_arguments("dev.sample/.MainActivity", "0", mode).contains(&"-f"));
        }
    }

    const LAUNCH: &str = "Status: ok\nLaunchState: COLD\nActivity: dev.sample/.MainActivity\nTotalTime: 410\nWaitTime: 432\nComplete\n";

    fn run() -> StartupRun {
        parse_successful_launch(LAUNCH, "dev.sample", StartupMode::Cold).unwrap()
    }

    #[test]
    fn failed_or_incomplete_launch_is_not_a_publishable_experiment() {
        for output in [
            LAUNCH.replace("Status: ok", "Status: timeout"),
            LAUNCH.replace("Complete\n", ""),
            LAUNCH.replace("Activity: dev.sample/.MainActivity\n", ""),
            LAUNCH.replace("dev.sample/.MainActivity", "other.app/.MainActivity"),
            LAUNCH.replace("dev.sample/.MainActivity", "dev.sample/invalid activity"),
        ] {
            assert!(parse_successful_launch(&output, "dev.sample", StartupMode::Cold).is_err());
        }
        let unknown = LAUNCH.replace("LaunchState: COLD\n", "");
        assert_eq!(
            parse_successful_launch(&unknown, "dev.sample", StartupMode::Cold)
                .unwrap()
                .observed_mode,
            StartupMode::Unknown
        );
    }

    #[test]
    fn event_metrics_require_exact_user_activity_and_one_displayed_token() {
        let mut run = run();
        let mut warnings = Vec::new();
        apply_scoped_events(
            &mut run,
            "I/wm_activity_launch_time(1): [10,123,dev.sample/.MainActivity,900]\nI/wm_activity_launch_time(1): [0,123,dev.sample/.OtherActivity,800]\nI/wm_activity_launch_time(1): [0,456,dev.sample/dev.sample.MainActivity,210]\nI/wm_fully_drawn_time(1): [0,123,dev.sample/.MainActivity,999]\nI/wm_fully_drawn_time(1): [0,456,dev.sample/.MainActivity,540]\n",
            0,
            &mut warnings,
        );
        assert_eq!(run.ttid.milliseconds, Some(210));
        assert_eq!(run.ttfd.milliseconds, Some(540));
        assert_eq!(run.ttid.source, Some(crate::MetricSource::EventLog));
    }

    #[test]
    fn ambiguous_or_missing_displayed_cannot_borrow_another_launch_metric() {
        for events in [
            "I/wm_activity_launch_time(1): [0,123,dev.sample/.MainActivity,210]\nI/wm_activity_launch_time(1): [0,456,dev.sample/.MainActivity,310]\nI/wm_fully_drawn_time(1): [0,123,dev.sample/.MainActivity,540]",
            "I/wm_fully_drawn_time(1): [0,123,dev.sample/.MainActivity,540]",
            "I/wm_activity_launch_time(1): [0,,dev.sample/.MainActivity,210]",
            "I/wm_activity_launch_time(1): [0,123,dev.sample.other/.MainActivity,210]",
        ] {
            let mut run = run();
            apply_scoped_events(&mut run, events, 0, &mut Vec::new());
            assert_eq!(run.ttid.milliseconds, None);
            assert_eq!(run.ttfd.milliseconds, None);
            assert_eq!(run.total_time_ms, Some(410));
        }
    }

    #[test]
    fn malformed_or_reversed_durations_stay_unavailable() {
        for events in [
            "I/wm_activity_launch_time(1): [0,123,dev.sample/.MainActivity,210]\nI/wm_fully_drawn_time(1): [0,123,dev.sample/.MainActivity,100]",
            "I/wm_activity_launch_time(1): [0,123,dev.sample/.MainActivity,invalid]",
        ] {
            let mut run = run();
            apply_scoped_events(&mut run, events, 0, &mut Vec::new());
            assert_eq!(run.ttid.milliseconds, None);
            assert_eq!(run.ttfd.milliseconds, None);
        }
    }

    #[test]
    fn boot_identity_and_activity_are_structurally_bounded() {
        assert!(valid_boot_id("550e8400-e29b-41d4-a716-446655440000"));
        assert!(!valid_boot_id("550e8400ee29be41d4ea716e446655440000"));
        assert!(!valid_boot_id("------------------------------------"));
        assert!(valid_component("dev.sample/.Main$Nested", "dev.sample"));
        assert!(!valid_component("dev.sample.other/.Main", "dev.sample"));
        assert!(!valid_component("dev.sample/.Main;other", "dev.sample"));
    }

    #[test]
    fn dropping_an_artifact_removes_only_its_exclusively_owned_directory() {
        let root = std::env::temp_dir().join(format!(
            "aps-startup-owner-{}-{}",
            std::process::id(),
            NEXT_EXPERIMENT.fetch_add(1, Ordering::Relaxed)
        ));
        std::fs::create_dir(&root).unwrap();
        let unrelated = root.join("keep.txt");
        std::fs::write(&unrelated, b"unrelated").unwrap();
        let workspace = TempWorkspace::create(&root).unwrap();
        let owned = workspace.path().to_path_buf();
        let path = owned.join("experiment.txt");
        std::fs::write(&path, b"owned").unwrap();
        let artifact = StartupCaptureArtifact {
            path,
            _workspace: workspace,
        };
        drop(artifact);
        assert!(!owned.exists());
        assert_eq!(std::fs::read(&unrelated).unwrap(), b"unrelated");
        std::fs::remove_dir_all(root).unwrap();
    }
}

//! Macrobenchmark plan/command/runner (port of `MacrobenchmarkCommand.kt`,
//! `MacrobenchmarkProcessRunner.kt` and `BenchmarkResultLocator`).
//!
//! The runner never starts a device or Gradle by itself: it builds an exact,
//! reproducible command line, streams merged output, and stops on cancellation or
//! timeout without ever reporting a stopped run as succeeded.
use std::collections::BTreeMap;
use std::io::Read;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::Sender;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use crate::util::absolute_normalize;

pub const DEFAULT_MACROBENCHMARK_TASK: &str = ":macrobenchmark:connectedCheck";
const DISCOVERY_DEPTH: usize = 8;
const MAX_VISITED_ENTRIES: usize = 100_000;
const POLL_INTERVAL: Duration = Duration::from_millis(200);
const READER_GRACE: Duration = Duration::from_secs(5);
const STOPPED_EXIT_CODE: i32 = -1;
const SKIPPED_DIRECTORIES: [&str; 4] = [".git", "node_modules", ".gradle", ".idea"];

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum CompilationMode {
    #[default]
    None,
    Partial,
    Full,
    SpeedProfile,
}

impl CompilationMode {
    pub fn label(self) -> &'static str {
        match self {
            Self::None => "NONE",
            Self::Partial => "PARTIAL",
            Self::Full => "FULL",
            Self::SpeedProfile => "SPEED_PROFILE",
        }
    }

    pub(crate) fn property(self) -> &'static str {
        match self {
            Self::None => "none",
            Self::Partial => "partial",
            Self::Full => "full",
            Self::SpeedProfile => "speed_profile",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum StartupMode {
    #[default]
    Cold,
    Warm,
    Hot,
}

impl StartupMode {
    pub fn label(self) -> &'static str {
        match self {
            Self::Cold => "COLD",
            Self::Warm => "WARM",
            Self::Hot => "HOT",
        }
    }

    pub(crate) fn property(self) -> &'static str {
        match self {
            Self::Cold => "cold",
            Self::Warm => "warm",
            Self::Hot => "hot",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Default)]
pub enum Scenario {
    #[default]
    Startup,
    Scroll,
    Animation,
    PageSwitch,
}

impl Scenario {
    pub fn label(self) -> &'static str {
        match self {
            Self::Startup => "STARTUP",
            Self::Scroll => "SCROLL",
            Self::Animation => "ANIMATION",
            Self::PageSwitch => "PAGE_SWITCH",
        }
    }

    pub(crate) fn property(self) -> &'static str {
        match self {
            Self::Startup => "startup",
            Self::Scroll => "scroll",
            Self::Animation => "animation",
            Self::PageSwitch => "page_switch",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MacrobenchmarkPlan {
    pub project_root: PathBuf,
    pub gradle_task: String,
    pub benchmark_filter: Option<String>,
    pub compilation_mode: CompilationMode,
    pub startup_mode: StartupMode,
    pub scenario: Scenario,
    pub warmups: u32,
    pub iterations: u32,
    pub trace_directory: Option<PathBuf>,
}

impl Default for MacrobenchmarkPlan {
    fn default() -> Self {
        Self {
            project_root: PathBuf::new(),
            gradle_task: DEFAULT_MACROBENCHMARK_TASK.to_string(),
            benchmark_filter: None,
            compilation_mode: CompilationMode::None,
            startup_mode: StartupMode::Cold,
            scenario: Scenario::Startup,
            warmups: 2,
            iterations: 5,
            trace_directory: None,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MacrobenchmarkCommand {
    pub working_directory: PathBuf,
    pub executable: PathBuf,
    pub arguments: Vec<String>,
    pub environment: BTreeMap<String, String>,
}

impl MacrobenchmarkCommand {
    pub fn command_line(&self) -> String {
        std::iter::once(self.executable.to_string_lossy().into_owned())
            .chain(self.arguments.iter().cloned())
            .map(|value| {
                if value.chars().any(char::is_whitespace) {
                    format!("\"{}\"", value.replace('"', "\\\""))
                } else {
                    value
                }
            })
            .collect::<Vec<_>>()
            .join(" ")
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct MacrobenchmarkResult {
    pub exit_code: i32,
    pub output: String,
    pub timed_out: bool,
    pub cancelled: bool,
}

impl MacrobenchmarkResult {
    /// True only for a clean exit: a timeout or a cancellation is never a success.
    pub fn succeeded(&self) -> bool {
        !self.timed_out && !self.cancelled && self.exit_code == 0
    }
}

pub fn build_command(plan: &MacrobenchmarkPlan) -> Result<MacrobenchmarkCommand, String> {
    if !plan.project_root.is_dir() {
        return Err(format!(
            "Macrobenchmark project root is not a directory: {}",
            plan.project_root.display()
        ));
    }
    if !plan.gradle_task.starts_with(':') {
        return Err("gradleTask must be a Gradle task path".into());
    }
    if plan.warmups > 100 {
        return Err("warmups must be between 0 and 100".into());
    }
    if !(1..=100).contains(&plan.iterations) {
        return Err("iterations must be between 1 and 100".into());
    }
    let wrapper = plan.project_root.join(if cfg!(windows) {
        "gradlew.bat"
    } else {
        "gradlew"
    });
    if !wrapper.exists() {
        return Err(format!("Gradle wrapper not found: {}", wrapper.display()));
    }
    let trace_directory = plan
        .trace_directory
        .as_ref()
        .map(|path| absolute_normalize(path));
    let mut environment = BTreeMap::new();
    environment.insert(
        "APS_MACROBENCHMARK_COMPILATION_MODE".to_string(),
        plan.compilation_mode.property().to_string(),
    );
    environment.insert(
        "APS_MACROBENCHMARK_STARTUP_MODE".to_string(),
        plan.startup_mode.property().to_string(),
    );
    environment.insert(
        "APS_MACROBENCHMARK_SCENARIO".to_string(),
        plan.scenario.property().to_string(),
    );
    environment.insert(
        "APS_MACROBENCHMARK_WARMUPS".to_string(),
        plan.warmups.to_string(),
    );
    environment.insert(
        "APS_MACROBENCHMARK_ITERATIONS".to_string(),
        plan.iterations.to_string(),
    );
    if let Some(directory) = &trace_directory {
        environment.insert(
            "APS_MACROBENCHMARK_TRACE_DIR".to_string(),
            directory.to_string_lossy().into_owned(),
        );
    }
    let mut arguments = vec![
        plan.gradle_task.clone(),
        "--no-daemon".to_string(),
        "-Pandroidx.benchmark.output.enable=true".to_string(),
        format!(
            "-Paps.macrobenchmark.compilationMode={}",
            plan.compilation_mode.property()
        ),
        format!(
            "-Paps.macrobenchmark.startupMode={}",
            plan.startup_mode.property()
        ),
        format!("-Paps.macrobenchmark.scenario={}", plan.scenario.property()),
        format!("-Paps.macrobenchmark.warmups={}", plan.warmups),
        format!("-Paps.macrobenchmark.iterations={}", plan.iterations),
    ];
    if let Some(filter) = &plan.benchmark_filter {
        arguments.push(format!(
            "-Pandroid.testInstrumentationRunnerArguments.class={filter}"
        ));
    }
    if let Some(directory) = &trace_directory {
        arguments.push(format!(
            "-Paps.macrobenchmark.traceDirectory={}",
            directory.to_string_lossy()
        ));
    }
    Ok(MacrobenchmarkCommand {
        working_directory: plan.project_root.clone(),
        executable: wrapper,
        arguments,
        environment,
    })
}

/// Blocking runner: merged output, dedicated drain threads, 200 ms cancel/timeout poll.
pub fn run_macrobenchmark(
    command: &MacrobenchmarkCommand,
    timeout: Duration,
    cancel: Arc<AtomicBool>,
    on_output: &mut dyn FnMut(&str),
) -> MacrobenchmarkResult {
    let mut process = match Command::new(&command.executable)
        .args(&command.arguments)
        .current_dir(&command.working_directory)
        .envs(&command.environment)
        .stdin(Stdio::null())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .spawn()
    {
        Ok(process) => process,
        Err(error) => {
            return MacrobenchmarkResult {
                exit_code: STOPPED_EXIT_CODE,
                output: format!("Cannot start macrobenchmark: {error}"),
                timed_out: false,
                cancelled: false,
            };
        }
    };
    let output = Arc::new(Mutex::new(String::new()));
    let pending = Arc::new(Mutex::new(Vec::<String>::new()));
    let (sender, receiver) = std::sync::mpsc::channel::<()>();
    let mut readers = Vec::new();
    if let Some(reader) = process.stdout.take() {
        readers.push(spawn_reader(
            reader,
            Arc::clone(&output),
            Arc::clone(&pending),
            sender.clone(),
        ));
    }
    if let Some(reader) = process.stderr.take() {
        readers.push(spawn_reader(
            reader,
            Arc::clone(&output),
            Arc::clone(&pending),
            sender.clone(),
        ));
    }
    drop(sender);

    let deadline = Instant::now() + timeout;
    let mut status = None;
    let mut exited = false;
    let mut cancelled = false;
    loop {
        match process.try_wait() {
            Ok(Some(exit)) => {
                status = Some(exit);
                exited = true;
                break;
            }
            Ok(None) => {}
            Err(_) => break,
        }
        forward_pending(&pending, on_output);
        if cancel.load(Ordering::Relaxed) {
            cancelled = true;
            break;
        }
        if Instant::now() >= deadline {
            break;
        }
        std::thread::sleep(POLL_INTERVAL);
    }
    if status.is_none() {
        let _ = process.kill();
        if let Ok(exit) = process.wait() {
            status = Some(exit);
        }
    }
    forward_pending(&pending, on_output);
    let grace_deadline = Instant::now() + READER_GRACE;
    for _ in 0..readers.len() {
        let remaining = grace_deadline.saturating_duration_since(Instant::now());
        if receiver.recv_timeout(remaining).is_err() {
            break;
        }
    }
    forward_pending(&pending, on_output);
    let finished = exited && !cancelled;
    MacrobenchmarkResult {
        exit_code: if finished {
            status
                .and_then(|exit| exit.code())
                .unwrap_or(STOPPED_EXIT_CODE)
        } else {
            STOPPED_EXIT_CODE
        },
        output: output.lock().map(|text| text.clone()).unwrap_or_default(),
        timed_out: !finished && !cancelled,
        cancelled,
    }
}

fn spawn_reader<R: Read + Send + 'static>(
    mut reader: R,
    output: Arc<Mutex<String>>,
    pending: Arc<Mutex<Vec<String>>>,
    sender: Sender<()>,
) -> std::thread::JoinHandle<()> {
    std::thread::spawn(move || {
        let mut buffer = [0u8; 8192];
        loop {
            match reader.read(&mut buffer) {
                Ok(0) => break,
                Ok(read) => {
                    let chunk = String::from_utf8_lossy(&buffer[..read]).into_owned();
                    if let Ok(mut text) = output.lock() {
                        text.push_str(&chunk);
                    }
                    if let Ok(mut pending) = pending.lock() {
                        pending.push(chunk);
                    }
                }
                Err(_) => break,
            }
        }
        let _ = sender.send(());
    })
}

fn forward_pending(pending: &Mutex<Vec<String>>, on_output: &mut dyn FnMut(&str)) {
    let chunks: Vec<String> = pending
        .lock()
        .map(|mut pending| std::mem::take(&mut *pending))
        .unwrap_or_default();
    for chunk in chunks {
        on_output(&chunk);
    }
}

/// Explicit result path wins only when it is a regular file; otherwise the newest
/// `benchmarkData` / `additional_output` JSON under the project is used.
pub fn locate_benchmark_result(project_root: &Path, explicit: Option<&str>) -> Option<PathBuf> {
    if let Some(raw) = explicit.map(str::trim).filter(|raw| !raw.is_empty()) {
        let path = absolute_normalize(Path::new(raw));
        return path.is_file().then_some(path);
    }
    newest_benchmark_json(project_root)
}

fn newest_benchmark_json(project_root: &Path) -> Option<PathBuf> {
    if !project_root.is_dir() {
        return None;
    }
    let mut stack = vec![(project_root.to_path_buf(), 0usize)];
    let mut visited = 0usize;
    let mut newest: Option<(std::time::SystemTime, PathBuf)> = None;
    while let Some((directory, depth)) = stack.pop() {
        let Ok(entries) = std::fs::read_dir(&directory) else {
            continue;
        };
        let mut entries: Vec<_> = entries.filter_map(Result::ok).collect();
        entries.sort_by_key(|entry| entry.file_name());
        for entry in entries {
            visited += 1;
            if visited > MAX_VISITED_ENTRIES {
                return None;
            }
            let Ok(file_type) = entry.file_type() else {
                continue;
            };
            let path = entry.path();
            if file_type.is_dir() {
                // Files.Walk(root, 8): a directory at relative depth d has children at d + 1.
                if depth + 1 < DISCOVERY_DEPTH
                    && !SKIPPED_DIRECTORIES.contains(&entry.file_name().to_string_lossy().as_ref())
                {
                    stack.push((path, depth + 1));
                }
            } else if file_type.is_file() && is_benchmark_json(&path) {
                let modified = entry
                    .metadata()
                    .and_then(|metadata| metadata.modified())
                    .unwrap_or(std::time::UNIX_EPOCH);
                if newest
                    .as_ref()
                    .is_none_or(|(newest_time, _)| modified > *newest_time)
                {
                    newest = Some((modified, path));
                }
            }
        }
    }
    newest.map(|(_, path)| path)
}

fn is_benchmark_json(path: &Path) -> bool {
    let name = path
        .file_name()
        .and_then(|name| name.to_str())
        .unwrap_or_default();
    if !name.to_lowercase().ends_with(".json") {
        return false;
    }
    let text = path.to_string_lossy();
    text.contains("benchmarkData") || text.contains("additional_output")
}

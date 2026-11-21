//! AGI executable discovery, capability probing and explicit launch.
//!
//! Probing reads only the executable's `--version` and `--help` output. An
//! advertised argument is an argv contract observed in help text, not proof that
//! a capture or artifact-open run started or succeeded.

use aps_platform::{CancellationToken, ProcessError, ProcessRequest, ProcessRunner};
use std::collections::BTreeMap;
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::Duration;

const PROBE_TIMEOUT: Duration = Duration::from_secs(3);
const PROBE_OUTPUT_LIMIT: usize = 512 * 1024;
const STABLE_ARGUMENTS: [&str; 4] = ["--device", "--package", "--activity", "--capture"];
const ARTIFACT_OPEN_ARGUMENTS: [&str; 2] = ["--open-artifact", "--artifact"];

pub const WARNING_AGI_NOT_FOUND: &str =
    "Android GPU Inspector executable was not found. Configure its local path.";
pub const WARNING_VERSION_TIMED_OUT: &str =
    "AGI version probe timed out; GUI launch remains available.";
pub const WARNING_VERSION_UNKNOWN: &str = "AGI version could not be determined.";
pub const WARNING_HELP_TIMED_OUT: &str =
    "AGI help probe timed out; CLI capabilities were not verified.";
pub const WARNING_HELP_FAILED: &str = "AGI help probe failed; CLI capabilities were not verified.";
pub const WARNING_GUI_ONLY: &str =
    "No stable automation arguments were detected; AGI will be launched in GUI-only mode.";
pub const WARNING_ARTIFACT_OPEN_UNVERIFIED: &str = "Opening an artifact through this AGI executable was not verified; launch AGI and open it from the application.";
pub const WARNING_PREFERENCE_NOT_SAVED: &str =
    "AGI capability loaded, but its executable path could not be saved.";

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AgiLaunchMode {
    VerifiedCli,
    GuiOnly,
    Unsupported,
}

#[derive(Clone, Debug)]
pub struct AgiCapability {
    pub executable: Option<PathBuf>,
    pub version: Option<String>,
    pub launch_mode: AgiLaunchMode,
    pub artifact_open_supported: bool,
    pub artifact_open_argument: Option<String>,
    pub stable_arguments: Vec<String>,
    pub warnings: Vec<String>,
}

impl AgiCapability {
    pub fn launch_supported(&self) -> bool {
        self.launch_mode != AgiLaunchMode::Unsupported
    }

    /// No candidate executable exists; nothing is probed or launched.
    pub fn unavailable() -> Self {
        Self {
            executable: None,
            version: None,
            launch_mode: AgiLaunchMode::Unsupported,
            artifact_open_supported: false,
            artifact_open_argument: None,
            stable_arguments: Vec::new(),
            warnings: vec![WARNING_AGI_NOT_FOUND.into()],
        }
    }
}

#[derive(Debug)]
pub enum AgiToolchainError {
    InvalidExecutable,
    ProbeFailed,
    Cancelled,
    LaunchFailed,
    PreferenceFailed,
}
impl std::fmt::Display for AgiToolchainError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::InvalidExecutable => "AGI executable is invalid",
            Self::ProbeFailed => "AGI capability probe failed",
            Self::Cancelled => "AGI capability probe was cancelled",
            Self::LaunchFailed => "AGI GUI process could not be started",
            Self::PreferenceFailed => "AGI executable preference could not be saved",
        })
    }
}
impl std::error::Error for AgiToolchainError {}

pub struct AgiToolchain;
impl AgiToolchain {
    pub fn probe(
        executable: &Path,
        cancel: &CancellationToken,
    ) -> Result<AgiCapability, AgiToolchainError> {
        if cancel.is_cancelled() {
            return Err(AgiToolchainError::Cancelled);
        }
        let executable = regular_executable(executable)?;
        probe_executable(executable, cancel)
    }

    /// Explicit user-action adapter only: starts the configured regular
    /// executable with no shell and no artifact-path forwarding. Callers pass
    /// only advertised stable arguments; otherwise none are forwarded.
    pub fn launch(
        capability: &AgiCapability,
        arguments: &[String],
    ) -> Result<(), AgiToolchainError> {
        let executable = capability
            .executable
            .as_deref()
            .ok_or(AgiToolchainError::InvalidExecutable)?;
        if capability.launch_mode == AgiLaunchMode::Unsupported || !executable.is_absolute() {
            return Err(AgiToolchainError::InvalidExecutable);
        }
        let executable = regular_executable(executable)?;
        let safe: Vec<&str> = if capability.launch_mode == AgiLaunchMode::VerifiedCli {
            arguments
                .iter()
                .filter(|argument| {
                    argument.starts_with("--")
                        && capability
                            .stable_arguments
                            .iter()
                            .any(|stable| argument.split('=').next() == Some(stable.as_str()))
                })
                .map(String::as_str)
                .collect()
        } else {
            Vec::new()
        };
        spawn_gui(executable, &safe)
    }

    /// Opens one verified artifact only when the executable advertises a
    /// dedicated artifact argument. This is argv authority, not proof that the
    /// external application accepted or rendered the file.
    pub fn launch_artifact(
        capability: &AgiCapability,
        artifact: &Path,
    ) -> Result<(), AgiToolchainError> {
        let executable = capability
            .executable
            .as_deref()
            .ok_or(AgiToolchainError::InvalidExecutable)?;
        if capability.launch_mode == AgiLaunchMode::Unsupported {
            return Err(AgiToolchainError::InvalidExecutable);
        }
        if !capability.artifact_open_supported {
            return Err(AgiToolchainError::InvalidExecutable);
        }
        let argument = capability
            .artifact_open_argument
            .as_deref()
            .ok_or(AgiToolchainError::InvalidExecutable)?;
        let executable = regular_executable(executable)?;
        let artifact = artifact
            .canonicalize()
            .map_err(|_| AgiToolchainError::InvalidExecutable)?;
        if !artifact.is_file() {
            return Err(AgiToolchainError::InvalidExecutable);
        }
        let path = artifact
            .to_str()
            .ok_or(AgiToolchainError::InvalidExecutable)?;
        spawn_gui(executable, &[argument, path])
    }
}

/// Persisted choice of local AGI executable. The file holds exactly one path and
/// is replaced atomically; a malformed preference is treated as unset.
#[derive(Clone, Debug)]
pub struct AgiPreferences {
    path: PathBuf,
}

impl AgiPreferences {
    pub fn new(path: impl Into<PathBuf>) -> Self {
        Self { path: path.into() }
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    pub fn load(&self) -> Option<PathBuf> {
        let text = std::fs::read_to_string(&self.path).ok()?;
        let text = text.trim();
        (!text.is_empty()).then(|| PathBuf::from(text))
    }

    pub fn save(&self, executable: &Path) -> Result<(), AgiToolchainError> {
        let executable = executable
            .canonicalize()
            .map_err(|_| AgiToolchainError::InvalidExecutable)?;
        // The preference file is UTF-8 text; a non-UTF-8 path is refused rather
        // than written in a form `load` would silently drop. `atomic_write`
        // creates the private parent directory.
        let text = executable
            .to_str()
            .ok_or(AgiToolchainError::PreferenceFailed)?;
        aps_platform::atomic_write(&self.path, text.as_bytes())
            .map_err(|_| AgiToolchainError::PreferenceFailed)
    }

    /// Validates the selected executable, records the preference, then probes it.
    pub fn configure(
        &self,
        executable: &Path,
        cancel: &CancellationToken,
    ) -> Result<AgiCapability, AgiToolchainError> {
        let executable = regular_executable(executable)?;
        let mut capability = probe_executable(executable.clone(), cancel)?;
        // A successful probe stays usable when only the preference file cannot
        // be written; the warning keeps the failure visible.
        if self.save(&executable).is_err() {
            capability
                .warnings
                .push(WARNING_PREFERENCE_NOT_SAVED.into());
        }
        Ok(capability)
    }

    /// Uses the saved executable when it is still usable, otherwise the first
    /// usable explicit candidate. Returns `None` when nothing is usable; the
    /// caller then reports the documented not-found warning instead of probing.
    pub fn discover_from(
        &self,
        candidates: Vec<PathBuf>,
        cancel: &CancellationToken,
    ) -> Result<Option<AgiCapability>, AgiToolchainError> {
        let mut all = Vec::new();
        if let Some(saved) = self.load() {
            all.push(saved);
        }
        all.extend(candidates);
        for candidate in all {
            if !is_executable_file(&candidate) {
                continue;
            }
            match AgiToolchain::probe(&candidate, cancel) {
                Ok(capability) => return Ok(Some(capability)),
                Err(AgiToolchainError::InvalidExecutable) => continue,
                Err(error) => return Err(error),
            }
        }
        Ok(None)
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum HostOs {
    MacOs,
    Windows,
    Other,
}

impl HostOs {
    pub fn current() -> Self {
        if cfg!(target_os = "macos") {
            Self::MacOs
        } else if cfg!(target_os = "windows") {
            Self::Windows
        } else {
            Self::Other
        }
    }
}

/// Discovery order mirrors the Compose locator: explicit preference, then every
/// `PATH` directory, then platform defaults. Only candidate paths are produced;
/// none is probed until `AgiPreferences::discover_from` receives them.
#[derive(Clone, Debug)]
pub struct AgiLocator {
    environment: BTreeMap<String, String>,
    os: HostOs,
}

impl AgiLocator {
    pub fn from_environment() -> Self {
        Self {
            environment: std::env::vars_os()
                .map(|(key, value)| {
                    (
                        key.to_string_lossy().into_owned(),
                        value.to_string_lossy().into_owned(),
                    )
                })
                .collect(),
            os: HostOs::current(),
        }
    }

    pub fn candidates(&self) -> Vec<PathBuf> {
        let mut candidates = Vec::new();
        if let Some(path) = self.environment.get("PATH") {
            for directory in path.split(if self.os == HostOs::Windows { ';' } else { ':' }) {
                if directory.is_empty() {
                    continue;
                }
                for name in self.executable_names() {
                    candidates.push(Path::new(directory).join(name));
                }
            }
        }
        candidates.extend(self.default_candidates());
        candidates
    }

    fn executable_names(&self) -> [&'static str; 2] {
        if self.os == HostOs::Windows {
            ["agi.exe", "gapic.exe"]
        } else {
            ["agi", "gapic"]
        }
    }

    fn default_candidates(&self) -> Vec<PathBuf> {
        match self.os {
            HostOs::MacOs => {
                let mut candidates = vec![PathBuf::from(
                    "/Applications/Android GPU Inspector.app/Contents/MacOS/agi",
                )];
                if let Some(home) = self.environment.get("HOME").filter(|home| !home.is_empty()) {
                    candidates.push(
                        Path::new(home)
                            .join("Applications/Android GPU Inspector.app/Contents/MacOS/agi"),
                    );
                }
                candidates
            }
            HostOs::Windows => vec![
                PathBuf::from(
                    self.environment
                        .get("LOCALAPPDATA")
                        .map_or_else(String::new, Clone::clone),
                )
                .join("Android GPU Inspector/agi.exe"),
                PathBuf::from(
                    self.environment
                        .get("ProgramFiles")
                        .map_or_else(String::new, Clone::clone),
                )
                .join("Android GPU Inspector/agi.exe"),
            ],
            HostOs::Other => [
                "/opt/android-gpu-inspector/agi",
                "/opt/android-gpu-inspector/gapic",
                "/usr/local/bin/agi",
                "/usr/local/bin/gapic",
            ]
            .into_iter()
            .map(PathBuf::from)
            .collect(),
        }
    }
}

enum ProbeResponse {
    Output { stdout: Vec<u8>, stderr: Vec<u8> },
    Rejected,
    TimedOut,
}

impl ProbeResponse {
    /// Compose probes read both streams; some CLIs print usage to stderr.
    fn text(self) -> String {
        match self {
            Self::Output { stdout, stderr } => {
                let mut text = String::from_utf8_lossy(&stdout).into_owned();
                text.push('\n');
                text.push_str(&String::from_utf8_lossy(&stderr));
                text
            }
            Self::Rejected | Self::TimedOut => String::new(),
        }
    }
}

fn probe_executable(
    executable: PathBuf,
    cancel: &CancellationToken,
) -> Result<AgiCapability, AgiToolchainError> {
    let version_request = probe_request(&executable, "--version");
    let version_response = probe(&version_request, cancel)?;
    let version_timed_out = matches!(version_response, ProbeResponse::TimedOut);
    let version = version_response
        .text()
        .lines()
        .map(str::trim)
        .find(|line| !line.is_empty())
        .map(|line| line.chars().take(256).collect::<String>());
    let help_response = probe(&probe_request(&executable, "--help"), cancel)?;
    let help_timed_out = matches!(help_response, ProbeResponse::TimedOut);
    let help_failed = matches!(help_response, ProbeResponse::Rejected);
    let help = help_response.text();
    let stable_arguments: Vec<String> = STABLE_ARGUMENTS
        .into_iter()
        .filter(|argument| advertises(&help, argument))
        .map(str::to_owned)
        .collect();
    let artifact_open_argument = ARTIFACT_OPEN_ARGUMENTS
        .into_iter()
        .find(|argument| advertises(&help, argument))
        .map(str::to_owned);
    let launch_mode = if stable_arguments.is_empty() {
        AgiLaunchMode::GuiOnly
    } else {
        AgiLaunchMode::VerifiedCli
    };
    let mut warnings = Vec::new();
    if version_timed_out {
        warnings.push(WARNING_VERSION_TIMED_OUT.into());
    }
    if !version_timed_out && version.is_none() {
        warnings.push(WARNING_VERSION_UNKNOWN.into());
    }
    if help_timed_out {
        warnings.push(WARNING_HELP_TIMED_OUT.into());
    }
    if help_failed {
        warnings.push(WARNING_HELP_FAILED.into());
    }
    if stable_arguments.is_empty() {
        warnings.push(WARNING_GUI_ONLY.into());
    }
    if artifact_open_argument.is_none() {
        warnings.push(WARNING_ARTIFACT_OPEN_UNVERIFIED.into());
    }
    if cancel.is_cancelled() {
        return Err(AgiToolchainError::Cancelled);
    }
    Ok(AgiCapability {
        executable: Some(executable),
        version,
        launch_mode,
        artifact_open_supported: artifact_open_argument.is_some(),
        artifact_open_argument,
        stable_arguments,
        warnings,
    })
}

fn probe_request(executable: &Path, argument: &str) -> ProcessRequest {
    let mut request = ProcessRequest::new(executable).arg(argument);
    request.timeout = PROBE_TIMEOUT;
    request.stdout_limit = PROBE_OUTPUT_LIMIT;
    request.stderr_limit = PROBE_OUTPUT_LIMIT;
    request
}

fn probe(
    request: &ProcessRequest,
    cancel: &CancellationToken,
) -> Result<ProbeResponse, AgiToolchainError> {
    match ProcessRunner.run(request, cancel) {
        Ok(output) if output.status.success() => Ok(ProbeResponse::Output {
            stdout: output.stdout,
            stderr: output.stderr,
        }),
        Ok(_) => Ok(ProbeResponse::Rejected),
        Err(ProcessError::Timeout) => Ok(ProbeResponse::TimedOut),
        Err(ProcessError::Cancelled) if !cancel.is_cancelled() => Ok(ProbeResponse::Rejected),
        Err(ProcessError::Cancelled) => Err(AgiToolchainError::Cancelled),
        Err(_) => Ok(ProbeResponse::Rejected),
    }
}

/// Matches the Compose contract: a help line that starts with the argument and is
/// followed by nothing, whitespace, `=` or `,`. Prose mentions do not count.
fn advertises(help: &str, argument: &str) -> bool {
    help.lines().any(|line| {
        let line = line.trim_start();
        let Some(rest) = line.strip_prefix(argument) else {
            return false;
        };
        rest.is_empty()
            || rest.starts_with('=')
            || rest.starts_with(',')
            || rest.starts_with(char::is_whitespace)
    })
}

fn regular_executable(path: &Path) -> Result<PathBuf, AgiToolchainError> {
    let path = path
        .canonicalize()
        .map_err(|_| AgiToolchainError::InvalidExecutable)?;
    is_executable_file(&path)
        .then_some(path)
        .ok_or(AgiToolchainError::InvalidExecutable)
}

fn is_executable_file(path: &Path) -> bool {
    let Ok(metadata) = std::fs::metadata(path) else {
        return false;
    };
    if !metadata.is_file() {
        return false;
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if metadata.permissions().mode() & 0o111 == 0 {
            return false;
        }
    }
    true
}

/// Spawns the GUI with a reaper thread allocated before the child exists, so a
/// thread-start failure cannot leave an unowned child or a zombie. No shell is
/// involved; every argument is one argv element.
fn spawn_gui(executable: PathBuf, arguments: &[&str]) -> Result<(), AgiToolchainError> {
    let (sender, receiver) = std::sync::mpsc::sync_channel(1);
    let owned: Vec<String> = arguments.iter().map(|value| (*value).to_owned()).collect();
    std::thread::Builder::new()
        .name("aps-agi-gui".into())
        .spawn(move || {
            let child = Command::new(executable)
                .args(&owned)
                .stdin(Stdio::null())
                .stdout(Stdio::null())
                .stderr(Stdio::null())
                .spawn();
            match child {
                Ok(mut child) => {
                    let _ = sender.send(Ok(()));
                    let _ = child.wait();
                }
                Err(_) => {
                    let _ = sender.send(Err(()));
                }
            }
        })
        .map_err(|_| AgiToolchainError::LaunchFailed)?;
    receiver
        .recv()
        .map_err(|_| AgiToolchainError::LaunchFailed)?
        .map_err(|_| AgiToolchainError::LaunchFailed)
}

#[cfg(test)]
mod locator_tests {
    use super::*;

    #[test]
    fn candidates_prefer_path_directories_then_platform_defaults() {
        let locator = AgiLocator {
            environment: BTreeMap::from([
                ("PATH".to_string(), "/first:/second".to_string()),
                ("HOME".to_string(), "/home/tester".to_string()),
            ]),
            os: HostOs::MacOs,
        };
        let candidates = locator.candidates();
        assert_eq!(candidates[0], PathBuf::from("/first/agi"));
        assert_eq!(candidates[1], PathBuf::from("/first/gapic"));
        assert_eq!(candidates[2], PathBuf::from("/second/agi"));
        assert!(candidates.contains(&PathBuf::from(
            "/Applications/Android GPU Inspector.app/Contents/MacOS/agi"
        )));
        assert!(candidates.contains(&PathBuf::from(
            "/home/tester/Applications/Android GPU Inspector.app/Contents/MacOS/agi"
        )));
    }

    #[test]
    fn missing_home_never_produces_a_relative_candidate() {
        let locator = AgiLocator {
            environment: BTreeMap::new(),
            os: HostOs::MacOs,
        };
        assert!(locator.candidates().iter().all(|path| path.is_absolute()));
    }

    #[test]
    fn windows_candidates_use_the_platform_separator_and_defaults() {
        let locator = AgiLocator {
            environment: BTreeMap::from([
                ("PATH".to_string(), "C:\\tools;D:\\bin".to_string()),
                (
                    "LOCALAPPDATA".to_string(),
                    "C:\\Users\\tester\\AppData".to_string(),
                ),
                ("ProgramFiles".to_string(), "C:\\Program Files".to_string()),
            ]),
            os: HostOs::Windows,
        };
        let candidates = locator.candidates();
        // Only names and order are platform-independent here; path separators
        // follow the host that runs this test.
        assert_eq!(candidates.len(), 6);
        assert!(candidates[0].to_string_lossy().starts_with("C:\\tools"));
        assert!(candidates[0].to_string_lossy().ends_with("agi.exe"));
        assert!(candidates.iter().any(|path| {
            path.to_string_lossy().contains("Program Files")
                && path.to_string_lossy().ends_with("agi.exe")
        }));
    }

    #[test]
    fn only_exact_help_arguments_are_advertised() {
        let help = "usage:\n  --device <serial>\n  --capture=DIR\n  --package <name>\n";
        assert!(advertises(help, "--device"));
        assert!(advertises(help, "--capture"));
        assert!(advertises(help, "--package"));
        assert!(!advertises(help, "--activity"));
        assert!(!advertises("  --devices\n", "--device"));
        assert!(!advertises("prose --package x\n", "--package"));
    }
}

#[cfg(all(test, unix))]
mod discovery_tests {
    use super::*;
    use std::os::unix::fs::PermissionsExt;

    #[test]
    fn saved_executable_wins_and_can_be_restored() {
        let root = std::env::temp_dir().join(format!("aps-agi-discovery-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(&root).unwrap();
        let saved = root.join("saved");
        let found = root.join("agi");
        for path in [&saved, &found] {
            std::fs::write(path, "#!/bin/sh\nprintf 'AGI fixture\\n'\n").unwrap();
            std::fs::set_permissions(path, std::fs::Permissions::from_mode(0o700)).unwrap();
        }
        let preferences = AgiPreferences::new(root.join("preferences"));
        let cancel = CancellationToken::new();
        preferences.configure(&saved, &cancel).unwrap();
        let capability = preferences
            .discover_from(vec![found], &cancel)
            .unwrap()
            .unwrap();
        assert_eq!(capability.executable, Some(saved.canonicalize().unwrap()));
        assert_eq!(capability.launch_mode, AgiLaunchMode::GuiOnly);
        std::fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn advertised_arguments_are_detected_and_paths_forwarded_as_single_argv() {
        let root = std::env::temp_dir().join(format!("aps-agi-argv-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&root);
        std::fs::create_dir_all(&root).unwrap();
        let executable = root.join("agi");
        let marker = root.join("argv");
        std::fs::write(
            &executable,
            format!(
                "#!/bin/sh\ncase \"$1\" in\n--version) printf 'AGI 3.3.3\\n';;\n--help) printf '  --open-artifact <file>\\n  --device <serial>\\n';;\n*) printf '%s' \"$*\" > '{}';;\nesac\n",
                marker.display()
            ),
        )
        .unwrap();
        std::fs::set_permissions(&executable, std::fs::Permissions::from_mode(0o700)).unwrap();
        let capability = AgiToolchain::probe(&executable, &CancellationToken::new()).unwrap();
        assert_eq!(capability.launch_mode, AgiLaunchMode::VerifiedCli);
        assert_eq!(capability.stable_arguments, vec!["--device"]);
        assert_eq!(
            capability.artifact_open_argument.as_deref(),
            Some("--open-artifact")
        );
        let artifact = root.join("capture.gfxtrace");
        std::fs::write(&artifact, b"trace").unwrap();
        AgiToolchain::launch_artifact(&capability, &artifact).unwrap();
        for _ in 0..100 {
            if std::fs::read(&marker).is_ok_and(|value| {
                value
                    == format!(
                        "--open-artifact {}",
                        artifact.canonicalize().unwrap().display()
                    )
                    .as_bytes()
            }) {
                std::fs::remove_dir_all(root).unwrap();
                return;
            }
            std::thread::sleep(Duration::from_millis(10));
        }
        let recorded = std::fs::read_to_string(&marker).unwrap_or_default();
        std::fs::remove_dir_all(root).unwrap();
        panic!("artifact argv was not forwarded once: {recorded:?}");
    }
}

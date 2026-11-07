use aps_platform::{CancellationToken, ProcessError, ProcessRequest, ProcessRunner};
use std::path::{Path, PathBuf};
use std::process::{Command, Stdio};
use std::time::Duration;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum AgiLaunchMode {
    VerifiedCli,
    GuiOnly,
    Unsupported,
}

#[derive(Clone, Debug)]
pub struct AgiCapability {
    pub executable: PathBuf,
    pub version: Option<String>,
    pub launch_mode: AgiLaunchMode,
    pub artifact_open_supported: bool,
    pub stable_arguments: Vec<String>,
    pub warnings: Vec<String>,
}

#[derive(Debug)]
pub enum AgiToolchainError {
    InvalidExecutable,
    ProbeFailed,
    Cancelled,
    LaunchFailed,
}
impl std::fmt::Display for AgiToolchainError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(match self {
            Self::InvalidExecutable => "AGI executable is invalid",
            Self::ProbeFailed => "AGI capability probe failed",
            Self::Cancelled => "AGI capability probe was cancelled",
            Self::LaunchFailed => "AGI GUI process could not be started",
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
        let mut version_request = ProcessRequest::new(&executable).arg("--version");
        version_request.timeout = Duration::from_secs(10);
        version_request.stdout_limit = 64 * 1024;
        version_request.stderr_limit = 64 * 1024;
        let version = probe_output(&version_request, cancel)?
            .and_then(|output| String::from_utf8(output).ok())
            .and_then(|text| {
                text.lines()
                    .next()
                    .map(|line| line.trim().chars().take(256).collect())
            })
            .filter(|line: &String| !line.is_empty());
        let mut help_request = ProcessRequest::new(&executable).arg("--help");
        help_request.timeout = Duration::from_secs(10);
        help_request.stdout_limit = 512 * 1024;
        help_request.stderr_limit = 512 * 1024;
        let help_available = probe_output(&help_request, cancel)?.is_some();
        // Generic help prose is not a verified argv contract. No stable AGI
        // artifact-open syntax has been implemented, so only no-argument GUI
        // launch is offered after a successful version probe.
        let launch_mode = if version.is_some() {
            AgiLaunchMode::GuiOnly
        } else {
            AgiLaunchMode::Unsupported
        };
        let mut warnings = Vec::new();
        if version.is_none() {
            warnings.push("AGI version could not be verified.".into());
        }
        if !help_available {
            warnings.push("AGI help probe did not complete successfully.".into());
        }
        warnings.push("Stable AGI artifact-open arguments are not verified; open artifacts from the AGI application.".into());
        if cancel.is_cancelled() {
            return Err(AgiToolchainError::Cancelled);
        }
        Ok(AgiCapability {
            executable,
            version,
            launch_mode,
            artifact_open_supported: false,
            stable_arguments: Vec::new(),
            warnings,
        })
    }

    /// Explicit user-action adapter only: starts the configured regular
    /// executable with no arguments, no shell and no artifact-path forwarding.
    /// This is GUI process creation, not proof that AGI initialized successfully.
    /// macOS .app bundles are intentionally unsupported; select their executable.
    pub fn launch(capability: &AgiCapability) -> Result<(), AgiToolchainError> {
        if capability.launch_mode == AgiLaunchMode::Unsupported
            || !capability.executable.is_absolute()
        {
            return Err(AgiToolchainError::InvalidExecutable);
        }
        let executable = regular_executable(&capability.executable)?;
        let (sender, receiver) = std::sync::mpsc::sync_channel(1);
        // Allocate the reaper thread before spawning the process; thread-start
        // failure must not leave an unowned child or a zombie GUI process.
        std::thread::Builder::new()
            .name("aps-agi-gui".into())
            .spawn(move || {
                let child = Command::new(executable)
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
}

fn regular_executable(path: &Path) -> Result<PathBuf, AgiToolchainError> {
    let path = path
        .canonicalize()
        .map_err(|_| AgiToolchainError::InvalidExecutable)?;
    let metadata = std::fs::metadata(&path).map_err(|_| AgiToolchainError::InvalidExecutable)?;
    if !metadata.is_file() {
        return Err(AgiToolchainError::InvalidExecutable);
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if metadata.permissions().mode() & 0o111 == 0 {
            return Err(AgiToolchainError::InvalidExecutable);
        }
    }
    Ok(path)
}

fn probe_output(
    request: &ProcessRequest,
    cancel: &CancellationToken,
) -> Result<Option<Vec<u8>>, AgiToolchainError> {
    let result = ProcessRunner.run(request, cancel);
    if cancel.is_cancelled() || matches!(&result, Err(ProcessError::Cancelled)) {
        return Err(AgiToolchainError::Cancelled);
    }
    Ok(result
        .ok()
        .filter(|output| output.status.success())
        .map(|output| output.stdout))
}

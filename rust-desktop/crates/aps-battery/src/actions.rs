//! Explicitly authorized device actions. These are never invoked by a snapshot,
//! a poll or an import; the UI must obtain separate consent for destructive or
//! privacy-sensitive commands.
use aps_adb::validate_serial;
use aps_platform::{CancellationToken, ProcessError, ProcessRequest, ProcessRunner};
use std::path::Path;
use std::time::Duration;

const COMMAND_TIMEOUT: Duration = Duration::from_secs(20);
const BUGBREPORT_TIMEOUT: Duration = Duration::from_secs(10 * 60);
const OUTPUT_LIMIT: usize = 4 * 1024 * 1024;
pub const MAX_BUGBREPORT_BYTES: u64 = 1024 * 1024 * 1024;

/// Global device-wide BatteryStats reset. Destructive: callers must confirm it
/// separately from capture consent.
pub fn reset_batterystats(
    adb_executable: &Path,
    serial: &str,
    cancel: &CancellationToken,
) -> Result<(), String> {
    validate_serial(serial).map_err(|error| error.to_string())?;
    let mut request = ProcessRequest::new(adb_executable).args([
        "-s",
        serial,
        "shell",
        "dumpsys",
        "batterystats",
        "--reset",
    ]);
    request.timeout = COMMAND_TIMEOUT;
    request.stdout_limit = 256 * 1024;
    request.stderr_limit = 256 * 1024;
    let output = ProcessRunner.run(&request, cancel).map_err(action_error)?;
    if !output.status.success() {
        return Err("Device rejected the global BatteryStats reset.".into());
    }
    Ok(())
}

/// Generates a bugreport into a new regular file. Bugreports contain
/// privacy-sensitive device evidence; the destination is created exclusively
/// and is removed on failure.
pub fn generate_bugreport(
    adb_executable: &Path,
    serial: &str,
    destination: &Path,
    cancel: &CancellationToken,
) -> Result<u64, String> {
    validate_serial(serial).map_err(|error| error.to_string())?;
    if !destination.is_absolute() {
        return Err("Battery Historian input requires an absolute destination".into());
    }
    let destination_text = destination
        .to_str()
        .ok_or("Battery Historian input path is not valid UTF-8")?;
    let mut options = std::fs::OpenOptions::new();
    options.write(true).create_new(true);
    #[cfg(unix)]
    {
        use std::os::unix::fs::OpenOptionsExt;
        options.mode(0o600);
    }
    options.open(destination).map_err(|_| {
        "Select a new Battery Historian filename; the destination exists or cannot be created"
            .to_owned()
    })?;
    let mut request =
        ProcessRequest::new(adb_executable).args(["-s", serial, "bugreport", destination_text]);
    request.timeout = BUGBREPORT_TIMEOUT;
    request.stdout_limit = OUTPUT_LIMIT;
    request.stderr_limit = OUTPUT_LIMIT;
    let outcome = ProcessRunner.run(&request, cancel).map_err(action_error);
    let result = match outcome {
        Ok(output) if output.status.success() => std::fs::metadata(destination)
            .map_err(|_| "ADB reported success but the bugreport file is missing".to_owned())
            .and_then(|metadata| {
                let size = metadata.len();
                if !metadata.is_file() || size == 0 {
                    Err("ADB produced no usable bugreport file".into())
                } else if size > MAX_BUGBREPORT_BYTES {
                    Err("Bugreport exceeds the 1 GiB evidence limit".into())
                } else {
                    Ok(size)
                }
            }),
        Ok(_) => Err("ADB could not generate the bugreport.".into()),
        Err(error) => Err(error),
    };
    if result.is_err() {
        let _ = std::fs::remove_file(destination);
    }
    result
}

fn action_error(error: ProcessError) -> String {
    match error {
        ProcessError::Cancelled => "Battery device action was cancelled".into(),
        ProcessError::Timeout => "Battery device action timed out".into(),
        _ => "Battery device action failed".into(),
    }
}

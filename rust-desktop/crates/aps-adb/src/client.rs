use crate::{
    AdbDevice, AdbError, AdbLocation, AndroidPackage, AndroidProcess, DeviceProperties,
    DeviceSnapshot, TargetSnapshot, parse_devices, parse_packages, parse_processes,
    parse_properties, validate_serial,
};
use aps_platform::{CancellationToken, ProcessRequest, ProcessRunner};
use std::time::Duration;

#[derive(Clone)]
pub struct AdbClient {
    location: AdbLocation,
    timeout: Duration,
}

impl AdbClient {
    pub fn new(location: AdbLocation) -> Self {
        Self {
            location,
            timeout: Duration::from_secs(30),
        }
    }

    pub fn location(&self) -> &AdbLocation {
        &self.location
    }

    pub fn with_timeout(mut self, timeout: Duration) -> Self {
        self.timeout = timeout;
        self
    }

    pub fn list_devices(
        &self,
        cancellation: &CancellationToken,
    ) -> Result<Vec<AdbDevice>, AdbError> {
        let output = self.run(&["devices", "-l"], 1024 * 1024, cancellation)?;
        parse_devices(&output)
    }

    pub fn device_properties(
        &self,
        serial: &str,
        cancellation: &CancellationToken,
    ) -> Result<DeviceProperties, AdbError> {
        validate_serial(serial)?;
        let output = self.run(
            &["-s", serial, "shell", "getprop"],
            1024 * 1024,
            cancellation,
        )?;
        parse_properties(&output)
    }

    pub fn surfaceflinger_dump(
        &self,
        serial: &str,
        cancellation: &CancellationToken,
    ) -> Result<String, AdbError> {
        validate_serial(serial)?;
        self.run(
            &["-s", serial, "shell", "dumpsys", "SurfaceFlinger"],
            8 * 1024 * 1024,
            cancellation,
        )
    }

    pub fn list_packages(
        &self,
        serial: &str,
        cancellation: &CancellationToken,
    ) -> Result<Vec<AndroidPackage>, AdbError> {
        validate_serial(serial)?;
        let output = self.run(
            &["-s", serial, "shell", "cmd", "package", "list", "packages"],
            4 * 1024 * 1024,
            cancellation,
        )?;
        parse_packages(&output)
    }

    pub fn list_processes(
        &self,
        serial: &str,
        cancellation: &CancellationToken,
    ) -> Result<Vec<AndroidProcess>, AdbError> {
        validate_serial(serial)?;
        let output = self.run(
            &[
                "-s",
                serial,
                "shell",
                "ps",
                "-A",
                "-o",
                "PID,PPID,USER,NAME",
            ],
            4 * 1024 * 1024,
            cancellation,
        )?;
        parse_processes(&output)
    }

    /// Two sequential read-only observations, not an atomic platform snapshot or
    /// a proven process-instance identity. Cancellation is shared by both commands.
    pub fn device_snapshot(
        &self,
        serial: &str,
        cancellation: &CancellationToken,
    ) -> Result<DeviceSnapshot, AdbError> {
        validate_serial(serial)?;
        let properties = self.device_properties(serial, cancellation)?;
        let processes = self.list_processes(serial, cancellation)?;
        Ok(DeviceSnapshot {
            serial: serial.into(),
            properties,
            processes,
        })
    }

    /// Two sequential read-only observations of the target's installed packages
    /// and running processes. Not an atomic snapshot; cancellation is shared.
    pub fn target_snapshot(
        &self,
        serial: &str,
        cancellation: &CancellationToken,
    ) -> Result<TargetSnapshot, AdbError> {
        validate_serial(serial)?;
        let packages = self.list_packages(serial, cancellation)?;
        let processes = self.list_processes(serial, cancellation)?;
        Ok(TargetSnapshot {
            packages,
            processes,
        })
    }

    fn run(
        &self,
        arguments: &[&str],
        stdout_limit: usize,
        cancellation: &CancellationToken,
    ) -> Result<String, AdbError> {
        let mut request = ProcessRequest::new(&self.location.executable).args(arguments);
        request.timeout = self.timeout;
        request.stdout_limit = stdout_limit;
        request.stderr_limit = 256 * 1024;
        let output = ProcessRunner.run(&request, cancellation)?;
        if !output.status.success() {
            // stderr can include target data; callers receive a sanitized code only.
            return Err(AdbError::CommandFailed(output.status.code()));
        }
        String::from_utf8(output.stdout).map_err(|_| AdbError::MalformedOutput("expected UTF-8"))
    }
}

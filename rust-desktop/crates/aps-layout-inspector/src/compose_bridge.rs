//! Blocking Compose helper adapter. UI callers schedule every operation off-thread.
use crate::{InspectorArchive, InspectorProtocolFrame};
use aps_platform::{CancellationToken, ProcessRequest, ProcessRunner};
use serde_json::Value;
use std::{
    path::PathBuf,
    sync::{
        Arc,
        atomic::{AtomicBool, Ordering},
    },
    time::Duration,
};

pub struct ComposeBridge {
    helper: PathBuf,
    bundle: PathBuf,
    adb: PathBuf,
    directory: PathBuf,
    cache: PathBuf,
    receipt: Value,
}

impl ComposeBridge {
    pub fn receipt(&self) -> &Value {
        &self.receipt
    }

    pub fn preflight(
        adb: PathBuf,
        serial: &str,
        package: &str,
        pid: u32,
        cancel: &CancellationToken,
    ) -> Result<Self, String> {
        let checkout = PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../..");
        let helper = std::env::var_os("APS_COMPOSE_HELPER").map(PathBuf::from).unwrap_or_else(|| checkout.join(
            "desktop-viewer/layout-inspector/compose-inspection-host/build/install/aps-compose-inspection/bin/aps-compose-inspection"));
        let bundle = std::env::var_os("APS_COMPOSE_AGENT_BUNDLE")
            .map(PathBuf::from)
            .unwrap_or_else(|| {
                checkout.join(
            "desktop-viewer/layout-inspector/compose-agent-build/build/compose-agent-bundle")
            });
        if !helper.is_file() {
            return Err("Compose helper missing. Build desktop-viewer with ./gradlew :layout-inspector:compose-inspection-host:installDist, or configure APS_COMPOSE_HELPER to its distribution launcher (requires Java 17+).".into());
        }
        if !bundle.join("manifest.properties").is_file() {
            return Err("Compose agent bundle missing. Run :layout-inspector:compose-agent-build:composeAgentBundle or set APS_COMPOSE_AGENT_BUNDLE to a verified bundle.".into());
        }
        let directory = std::env::temp_dir().join(format!(
            "aps-compose-{}-{}",
            std::process::id(),
            uuid_suffix()
        ));
        std::fs::create_dir(&directory).map_err(|error| error.to_string())?;
        #[cfg(unix)]
        {
            use std::os::unix::fs::PermissionsExt;
            std::fs::set_permissions(&directory, std::fs::Permissions::from_mode(0o700))
                .map_err(|error| error.to_string())?;
        }
        let cache = aps_platform::AppDirectories::discover()
            .map_err(|error| error.to_string())?
            .cache
            .join("compose-inspector");
        std::fs::create_dir_all(&cache).map_err(|error| error.to_string())?;
        let mut bridge = Self {
            helper,
            bundle,
            adb,
            directory,
            cache,
            receipt: Value::Null,
        };
        let request = bridge.request("preflight", serial, package, pid);
        let output = ProcessRunner
            .run(&request, cancel)
            .map_err(|error| error.to_string())?;
        if !output.status.success() {
            return Err(helper_error(&output.stderr));
        }
        bridge.receipt =
            serde_json::from_slice(&output.stdout).map_err(|_| "Invalid Compose preflight JSON")?;
        let identity = &bridge.receipt["identity"];
        if identity["serial"].as_str() != Some(serial)
            || identity["packageName"].as_str() != Some(package)
            || identity["pid"].as_u64() != Some(pid as u64)
            || bridge.receipt["authorizationId"].as_str().is_none()
        {
            return Err("Compose preflight returned a different target".into());
        }
        Ok(bridge)
    }

    fn request(&self, operation: &str, serial: &str, package: &str, pid: u32) -> ProcessRequest {
        let mut request = ProcessRequest::new(&self.helper)
            .args([
                operation,
                "--serial",
                serial,
                "--package",
                package,
                "--pid",
                &pid.to_string(),
            ])
            .arg("--adb")
            .arg(&self.adb)
            .arg("--bundle")
            .arg(&self.bundle)
            .arg("--receipt")
            .arg(self.directory.join("receipt.json"))
            .arg("--cache")
            .arg(&self.cache);
        request.timeout = Duration::from_secs(300);
        request.stdout_limit = 64 * 1024;
        request.stderr_limit = 64 * 1024;
        request
    }

    /// Called only by the confirmation action, or refresh under that same target grant.
    pub fn capture(
        &self,
        generation: u64,
        cancel: &CancellationToken,
    ) -> Result<InspectorProtocolFrame, String> {
        let identity = &self.receipt["identity"];
        let serial = identity["serial"]
            .as_str()
            .ok_or("Missing receipt serial")?;
        let package = identity["packageName"]
            .as_str()
            .ok_or("Missing receipt package")?;
        let pid = identity["pid"].as_u64().ok_or("Missing receipt pid")? as u32;
        let marker = self.directory.join("cancel");
        let output = self.directory.join(format!("capture-{generation}.zip"));
        let _ = std::fs::remove_file(&marker);
        if cancel.is_cancelled() {
            return Err("Compose capture cancelled".into());
        }
        let request = self
            .request("capture", serial, package, pid)
            .arg("--authorize")
            .arg(
                self.receipt["authorizationId"]
                    .as_str()
                    .ok_or("Missing authorization identity")?,
            )
            .arg("--cancel-file")
            .arg(&marker)
            .arg("--output")
            .arg(&output);
        let finished = Arc::new(AtomicBool::new(false));
        let done = finished.clone();
        let cancellation = cancel.clone();
        let watcher = std::thread::spawn(move || {
            while !done.load(Ordering::Acquire) {
                if cancellation.is_cancelled() {
                    let _ = std::fs::write(&marker, b"cancel");
                    break;
                }
                std::thread::sleep(Duration::from_millis(50));
            }
        });
        // User cancellation is cooperative: SIGKILL cannot run the helper's use/close.
        // The runner's 300s emergency timeout still kills; device restart may then be needed.
        let result = ProcessRunner.run(&request, &CancellationToken::new());
        finished.store(true, Ordering::Release);
        let _ = watcher.join();
        let result = result.map_err(|error| {
            format!(
                "{error}. If injection was interrupted, restart the target app before retrying."
            )
        })?;
        if cancel.is_cancelled() {
            return Err("Compose capture cancelled after helper cleanup".into());
        }
        if !result.status.success() {
            return Err(helper_error(&result.stderr));
        }
        let archive = InspectorArchive::open(&output, cancel).map_err(|error| error.to_string());
        let _ = std::fs::remove_file(output);
        let archive = archive?;
        if archive.frame.package_name != package || archive.compose.is_none() {
            return Err("Compose helper archive target or sidecar mismatch".into());
        }
        Ok(InspectorProtocolFrame {
            archive,
            generation,
        })
    }
}

impl Drop for ComposeBridge {
    fn drop(&mut self) {
        let _ = std::fs::remove_dir_all(&self.directory);
    }
}

fn uuid_suffix() -> u128 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos()
}

fn helper_error(stderr: &[u8]) -> String {
    let text = String::from_utf8_lossy(stderr);
    format!(
        "Compose inspection failed: {}",
        text.chars().take(4000).collect::<String>()
    )
}

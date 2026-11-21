use crate::AdbError;
use std::collections::{BTreeMap, HashSet};
use std::ffi::{OsStr, OsString};
use std::path::{Path, PathBuf};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AdbLocationSource {
    ExplicitExecutable,
    ConfiguredSdk,
    AdbEnvironment,
    AndroidHome,
    AndroidSdkRoot,
    Path,
    DefaultSdk,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct AdbLocation {
    pub executable: PathBuf,
    pub source: AdbLocationSource,
}

/// Snapshot-based lookup: no configuration directories or credential files are read.
/// No bundle candidate is fabricated: packaged Rust ADB requires a separately
/// implemented, checksum-verified resource manifest before it can join this list.
pub struct AdbLocator {
    environment: BTreeMap<OsString, OsString>,
    home: Option<PathBuf>,
    sdk_root: Option<PathBuf>,
}

impl Default for AdbLocator {
    fn default() -> Self {
        Self::new()
    }
}

impl AdbLocator {
    pub fn new() -> Self {
        let environment: BTreeMap<_, _> = [
            "HOME",
            "USERPROFILE",
            "LOCALAPPDATA",
            "ADB_PATH",
            "ADB",
            "ANDROID_HOME",
            "ANDROID_SDK_ROOT",
            "PATH",
        ]
        .into_iter()
        .filter_map(|key| std::env::var_os(key).map(|value| (OsString::from(key), value)))
        .collect();
        let home = environment
            .get(OsStr::new("HOME"))
            .or_else(|| environment.get(OsStr::new("USERPROFILE")))
            .map(PathBuf::from)
            .filter(|path| path.is_absolute());
        Self {
            environment,
            home,
            sdk_root: configured_sdk().read().ok().and_then(|root| root.clone()),
        }
    }

    /// Explicit dependency injection for deterministic lookup and future adapters.
    pub fn with_environment(
        environment: BTreeMap<OsString, OsString>,
        home: Option<PathBuf>,
    ) -> Self {
        Self {
            environment,
            home,
            sdk_root: None,
        }
    }

    pub fn locate(&self, explicit: Option<&Path>) -> Result<AdbLocation, AdbError> {
        if let Some(executable) = explicit {
            return usable_executable(executable)
                .map(|executable| AdbLocation {
                    executable,
                    source: AdbLocationSource::ExplicitExecutable,
                })
                .ok_or(AdbError::ExplicitExecutableInvalid);
        }
        let mut candidates = Vec::new();
        if let Some(root) = &self.sdk_root {
            candidates.push((sdk_executable(root), AdbLocationSource::ConfiguredSdk));
        }
        for key in ["ADB_PATH", "ADB"] {
            if let Some(value) = self.environment_value(key) {
                candidates.push((PathBuf::from(value), AdbLocationSource::AdbEnvironment));
            }
        }
        for (key, source) in [
            ("ANDROID_HOME", AdbLocationSource::AndroidHome),
            ("ANDROID_SDK_ROOT", AdbLocationSource::AndroidSdkRoot),
        ] {
            if let Some(root) = self.environment_value(key) {
                candidates.push((sdk_executable(Path::new(root)), source));
            }
        }
        if let Some(path) = self.environment_value("PATH") {
            for root in std::env::split_paths(path).filter(|path| path.is_absolute()) {
                // Ignore empty/relative PATH elements rather than probing cwd.
                candidates.push((root.join(executable_name()), AdbLocationSource::Path));
            }
        }
        if let Some(home) = &self.home {
            #[cfg(target_os = "macos")]
            let roots = [home.join("Library/Android/sdk")];
            #[cfg(target_os = "windows")]
            let roots = [self
                .environment_value("LOCALAPPDATA")
                .map(PathBuf::from)
                .unwrap_or_else(|| home.join("AppData/Local"))
                .join("Android/Sdk")];
            #[cfg(not(any(target_os = "macos", target_os = "windows")))]
            let roots = [home.join("Android/Sdk"), home.join("Android/sdk")];
            for root in roots {
                candidates.push((sdk_executable(&root), AdbLocationSource::DefaultSdk));
            }
        }
        let mut seen = HashSet::new();
        for (path, source) in candidates {
            if !seen.insert(path.clone()) {
                continue;
            }
            if let Some(executable) = usable_executable(&path) {
                return Ok(AdbLocation { executable, source });
            }
        }
        Err(AdbError::NotFound)
    }

    fn environment_value(&self, name: &str) -> Option<&OsStr> {
        #[cfg(target_os = "windows")]
        let value = self
            .environment
            .iter()
            .find(|(key, _)| key.to_string_lossy().eq_ignore_ascii_case(name))
            .map(|(_, value)| value);
        #[cfg(not(target_os = "windows"))]
        let value = self.environment.get(OsStr::new(name));
        value
            .filter(|value| !value.is_empty())
            .map(OsString::as_os_str)
    }
}

fn executable_name() -> &'static str {
    if cfg!(target_os = "windows") {
        "adb.exe"
    } else {
        "adb"
    }
}

fn sdk_executable(root: &Path) -> PathBuf {
    root.join("platform-tools").join(executable_name())
}

fn usable_executable(path: &Path) -> Option<PathBuf> {
    let metadata = path.metadata().ok()?;
    if !metadata.is_file() {
        return None;
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        if metadata.permissions().mode() & 0o111 == 0 {
            return None;
        }
    }
    #[cfg(target_os = "windows")]
    if !path
        .extension()
        .and_then(OsStr::to_str)
        .is_some_and(|ext| ext.eq_ignore_ascii_case("exe"))
    {
        // Do not allow implicit cmd.exe interpretation of batch files.
        return None;
    }
    path.canonicalize().ok()
}

// Rust-owned application preference. Environment-injected locators remain fully
// deterministic and do not consult this process-wide desktop override.
fn configured_sdk() -> &'static std::sync::RwLock<Option<PathBuf>> {
    static ROOT: std::sync::OnceLock<std::sync::RwLock<Option<PathBuf>>> =
        std::sync::OnceLock::new();
    ROOT.get_or_init(|| std::sync::RwLock::new(None))
}

impl AdbLocator {
    /// Validate without launching ADB. Clearing restores normal environment lookup.
    pub fn configure_sdk(root: Option<&Path>) -> Result<Option<PathBuf>, String> {
        Self::configure_sdk_transaction(root, |_| Ok(()))
    }

    /// Freeze a validated path, persist it, and publish under one write lock.
    /// Failed validation/locking/persistence never publishes a new runtime root.
    /// The caller runs this blocking transaction away from the UI thread.
    pub fn configure_sdk_transaction(
        root: Option<&Path>,
        persist: impl FnOnce(Option<&Path>) -> Result<(), String>,
    ) -> Result<Option<PathBuf>, String> {
        let canonical = root
            .map(Self::validate_sdk)
            .transpose()
            .map_err(|error| error.to_string())?;
        let mut configured = configured_sdk()
            .write()
            .map_err(|_| "SDK preference lock is unavailable".to_owned())?;
        persist(canonical.as_deref())?;
        *configured = canonical.clone();
        Ok(canonical)
    }

    pub fn validate_sdk(root: &Path) -> Result<PathBuf, AdbError> {
        if !root.is_absolute()
            || !root.is_dir()
            || usable_executable(&sdk_executable(root)).is_none()
        {
            return Err(AdbError::ExplicitExecutableInvalid);
        }
        root.canonicalize()
            .map_err(|_| AdbError::ExplicitExecutableInvalid)
    }
}

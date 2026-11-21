//! Host simpleperf discovery shared by CPU import and capture.
use std::path::{Path, PathBuf};

pub(crate) fn executable_file(path: &Path) -> bool {
    let Ok(metadata) = std::fs::metadata(path) else {
        return false;
    };
    if !metadata.is_file() {
        return false;
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        metadata.permissions().mode() & 0o111 != 0
    }
    #[cfg(not(unix))]
    {
        true
    }
}

pub(crate) fn discover_simpleperf() -> Option<PathBuf> {
    if let Some(path) = std::env::var_os("APS_SIMPLEPERF") {
        let path = PathBuf::from(path);
        if path.is_absolute() && executable_file(&path) {
            return Some(path);
        }
    }
    if let Some(paths) = std::env::var_os("PATH") {
        for directory in std::env::split_paths(&paths) {
            let candidate = directory.join("simpleperf");
            if executable_file(&candidate) {
                return Some(candidate);
            }
        }
    }
    let mut roots = Vec::new();
    for key in ["ANDROID_NDK_HOME", "ANDROID_NDK_ROOT"] {
        if let Some(root) = std::env::var_os(key) {
            roots.push(PathBuf::from(root));
        }
    }
    for key in ["ANDROID_HOME", "ANDROID_SDK_ROOT"] {
        if let Some(root) = std::env::var_os(key) {
            if let Ok(entries) = std::fs::read_dir(PathBuf::from(root).join("ndk")) {
                let mut versions: Vec<PathBuf> = entries.flatten().map(|e| e.path()).collect();
                versions.sort();
                roots.extend(versions);
            }
        }
    }
    if let Some(home) = std::env::var_os("HOME") {
        if let Ok(entries) = std::fs::read_dir(PathBuf::from(home).join("Library/Android/sdk/ndk"))
        {
            let mut versions: Vec<PathBuf> = entries.flatten().map(|e| e.path()).collect();
            versions.sort();
            roots.extend(versions);
        }
    }
    for root in roots {
        for candidate in [
            root.join("simpleperf"),
            root.join("simpleperf/bin/darwin/x86_64/simpleperf"),
            root.join("simpleperf/bin/darwin/arm64/simpleperf"),
            root.join("simpleperf/bin/linux/x86_64/simpleperf"),
            root.join("simpleperf/bin/linux/arm64/simpleperf"),
            root.join("simpleperf/bin/windows/x86_64/simpleperf.exe"),
            root.join("toolchains/llvm/prebuilt/darwin-x86_64/bin/simpleperf"),
            root.join("toolchains/llvm/prebuilt/linux-x86_64/bin/simpleperf"),
        ] {
            if executable_file(&candidate) {
                return Some(candidate);
            }
        }
    }
    None
}

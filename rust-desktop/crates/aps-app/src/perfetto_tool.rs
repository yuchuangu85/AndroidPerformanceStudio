//! Shared pinned Perfetto Trace Processor resolution and external UI assets.

use aps_perfetto::TraceProcessor;
use aps_platform::CancellationToken;
use std::path::{Path, PathBuf};

/// Resolves the checksum-pinned trace_processor_shell shipped with the app, or
/// the repository copy in debug builds. Never falls back to PATH or downloads.
pub(crate) fn locate(cancel: &CancellationToken) -> Result<TraceProcessor, String> {
    let executable =
        std::env::current_exe().map_err(|_| "cannot locate the Rust app".to_owned())?;
    let directory = executable
        .parent()
        .ok_or_else(|| "the Rust app has no parent directory".to_owned())?;
    let resources = directory.join("resources");
    if resources.join("perfetto-tools").exists() {
        return TraceProcessor::from_packaged_resources(&resources, cancel)
            .map_err(|error| error.to_string());
    }
    #[cfg(debug_assertions)]
    {
        let root = Path::new(env!("CARGO_MANIFEST_DIR"))
            .ancestors()
            .nth(3)
            .ok_or_else(|| "cannot locate the repository root".to_owned())?;
        return TraceProcessor::from_repository(root, cancel).map_err(|error| error.to_string());
    }
    #[cfg(not(debug_assertions))]
    Err("the packaged Trace Processor is missing".to_owned())
}

/// Optional packaged Perfetto UI assets for the local viewer server.
pub(crate) fn viewer_assets_dir() -> Option<PathBuf> {
    if let Some(path) = std::env::var_os("APS_PERFETTO_UI_DIR") {
        let path = PathBuf::from(path);
        if path.join("index.html").is_file() {
            return Some(path);
        }
    }
    let executable = std::env::current_exe().ok()?;
    let candidate = executable.parent()?.join("resources/perfetto-ui");
    if candidate.join("index.html").is_file() {
        return Some(candidate);
    }

    // In a debug checkout the Perfetto submodule is the source of truth for
    // the local viewer assets. Packaged builds must use the copied resources
    // above and must not read arbitrary files from the repository.
    #[cfg(debug_assertions)]
    {
        let root = Path::new(env!("CARGO_MANIFEST_DIR")).ancestors().nth(3)?;
        let repository_assets = root.join("third_party/perfetto/out/ui/dist");
        if repository_assets.join("index.html").is_file() {
            return Some(repository_assets);
        }
    }

    None
}

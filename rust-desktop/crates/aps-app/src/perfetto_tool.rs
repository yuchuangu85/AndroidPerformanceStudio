//! Shared pinned Perfetto Trace Processor resolution.

use aps_perfetto::TraceProcessor;
use aps_platform::CancellationToken;
#[cfg(debug_assertions)]
use std::path::Path;

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

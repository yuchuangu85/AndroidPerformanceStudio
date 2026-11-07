//! Bounded host disassembly for one CPU frame symbol.
//!
//! Profile rows do not retain per-frame virtual addresses, so the host tool is
//! asked for a symbol-based view: resolve the session-local binary artifact,
//! run llvm-objdump/objdump for that symbol, then filter and cap its output.
//! Pure std plus the host-process boundary in aps-platform; no UI coupling.

use aps_platform::{CancellationToken, ProcessRequest, ProcessRunner};
use std::path::{Path, PathBuf};
use std::sync::atomic::{AtomicBool, Ordering};
use std::time::Duration;

pub const MAX_DISASSEMBLY_LINES: usize = 2000;
pub const MAX_DISASSEMBLY_BYTES: usize = 4 * 1024 * 1024;

const TOOL_TIMEOUT: Duration = Duration::from_secs(10);
const TOOL_STDERR_LIMIT: usize = 1024 * 1024;
const OBJDUMP_NAMES: [&str; 2] = ["llvm-objdump", "objdump"];
const NDK_HOST_TAGS: [&str; 4] = [
    "darwin-x86_64",
    "darwin-arm64",
    "linux-x86_64",
    "linux-arm64",
];

#[derive(Debug, Clone)]
pub struct Disassembly {
    pub symbol: String,
    pub binary: PathBuf,
    pub tool: PathBuf,
    pub lines: Vec<String>,
}

#[derive(Debug, Clone, Default)]
pub struct DisassemblyRequest {
    pub symbol: String,
    pub resource: String,
    pub binary_root: Option<PathBuf>,
    pub data_root: Option<PathBuf>,
}

/// Resolve the binary artifact then run llvm-objdump/objdump for one symbol.
/// Blocking; callers schedule it on the background executor.
pub fn resolve_disassembly(
    request: &DisassemblyRequest,
    cancelled: &AtomicBool,
) -> Result<Disassembly, String> {
    let symbol = request.symbol.trim();
    if symbol.is_empty() {
        return Err("A symbol is required for disassembly".into());
    }
    if cancelled.load(Ordering::Acquire) {
        return Err("Disassembly cancelled".into());
    }
    let binary = resolve_binary(request)?;
    let tool = locate_objdump().ok_or_else(|| {
        "llvm-objdump was not found; install Android NDK LLVM tools or set APS_LLVM_OBJDUMP"
            .to_string()
    })?;
    let mut process = ProcessRequest::new(&tool)
        .args(["--disassemble", "--source"])
        .arg(format!("--disassemble-symbols={symbol}"))
        .arg(&binary);
    process.timeout = TOOL_TIMEOUT;
    process.stdout_limit = MAX_DISASSEMBLY_BYTES;
    process.stderr_limit = TOOL_STDERR_LIMIT;
    let token = CancellationToken::new();
    let output = ProcessRunner
        .run(&process, &token)
        .map_err(|error| format!("Disassembly tool failed: {error}"))?;
    if cancelled.load(Ordering::Acquire) {
        return Err("Disassembly cancelled".into());
    }
    if !output.status.success() {
        return Err(format!(
            "Disassembly tool exited unsuccessfully ({})",
            output.status
        ));
    }
    let lines = bounded_lines(&output.stdout);
    if lines.is_empty() {
        return Err(format!("Disassembly produced no output for {symbol}"));
    }
    Ok(Disassembly {
        symbol: symbol.to_owned(),
        binary,
        tool,
        lines,
    })
}

/// True when some candidate binary exists for the request (for enabling UI).
#[allow(dead_code)] // exposed for host UI gating; the current panel resolves lazily.
pub fn binary_available(request: &DisassemblyRequest) -> bool {
    resolve_binary(request).is_ok()
}

/// Best-effort host tool discovery: APS_LLVM_OBJDUMP env, PATH (llvm-objdump, objdump), Android NDK
/// toolchains (ANDROID_NDK_HOME/ANDROID_NDK_ROOT, ANDROID_HOME/ANDROID_SDK_ROOT/ndk/*, ~/Library/Android/sdk/ndk/*)
/// with darwin/linux x86_64/arm64 prebuilt llvm-objdump paths. Executable regular files only.
pub fn locate_objdump() -> Option<PathBuf> {
    if let Some(configured) = std::env::var_os("APS_LLVM_OBJDUMP") {
        let configured = PathBuf::from(configured);
        if executable_file(&configured) {
            return Some(configured);
        }
    }
    if let Some(paths) = std::env::var_os("PATH") {
        let directories: Vec<PathBuf> = std::env::split_paths(&paths).collect();
        for name in OBJDUMP_NAMES {
            for directory in &directories {
                if let Some(candidate) = tool_in_directory(directory, name) {
                    return Some(candidate);
                }
            }
        }
    }
    for root in ndk_roots() {
        for host in NDK_HOST_TAGS {
            let candidate = root
                .join("toolchains/llvm/prebuilt")
                .join(host)
                .join("bin")
                .join("llvm-objdump");
            if executable_file(&candidate) {
                return Some(candidate);
            }
        }
    }
    None
}

fn resolve_binary(request: &DisassemblyRequest) -> Result<PathBuf, String> {
    let Some(relative) = safe_relative(&request.resource) else {
        return Err(if has_parent_segment(&request.resource) {
            "Unsafe binary resource path".into()
        } else {
            "A binary resource is required for disassembly".into()
        });
    };
    if let Some(root) = request.binary_root.as_deref() {
        for candidate in [
            root.join("binary_cache").join(&relative),
            root.join("symbols").join(&relative),
            root.join(&relative),
        ] {
            if let Some(binary) = secure_candidate(root, &candidate) {
                return Ok(binary);
            }
        }
    }
    if let Some(root) = request.data_root.as_deref() {
        for candidate in [
            root.join("binary_cache").join(&relative),
            root.join("symbols").join(&relative),
        ] {
            if let Some(binary) = secure_candidate(root, &candidate) {
                return Ok(binary);
            }
        }
    }
    Err(format!(
        "No binary artifact was found for {}",
        request.resource
    ))
}

/// Resolve one candidate while rejecting symlink escapes outside its root.
fn secure_candidate(root: &Path, candidate: &Path) -> Option<PathBuf> {
    let real_root = std::fs::canonicalize(root).ok()?;
    let real_candidate = std::fs::canonicalize(candidate).ok()?;
    if !real_candidate.starts_with(&real_root) {
        return None;
    }
    if !std::fs::metadata(&real_candidate).ok()?.is_file() {
        return None;
    }
    // Keep the root-relative spelling callers asked for; realpath only validates.
    Some(candidate.to_path_buf())
}

/// Strip leading separators and reject traversal segments; relative path only.
fn safe_relative(resource: &str) -> Option<PathBuf> {
    if has_parent_segment(resource) {
        return None;
    }
    let relative = resource.trim_start_matches('/');
    if relative.is_empty() {
        return None;
    }
    Some(PathBuf::from(relative))
}

fn has_parent_segment(resource: &str) -> bool {
    resource.split(['/', '\\']).any(|segment| segment == "..")
}

fn bounded_lines(stdout: &[u8]) -> Vec<String> {
    let text = String::from_utf8_lossy(stdout);
    let mut lines = Vec::new();
    let mut total = 0usize;
    for line in text.lines() {
        if line.trim().is_empty() {
            continue;
        }
        if lines.len() >= MAX_DISASSEMBLY_LINES {
            break;
        }
        let Some(next_total) = total.checked_add(line.len() + 1) else {
            break;
        };
        if next_total > MAX_DISASSEMBLY_BYTES {
            break;
        }
        total = next_total;
        lines.push(line.to_owned());
    }
    lines
}

fn ndk_roots() -> Vec<PathBuf> {
    let mut roots = Vec::new();
    for key in ["ANDROID_NDK_HOME", "ANDROID_NDK_ROOT"] {
        if let Some(root) = std::env::var_os(key) {
            roots.push(PathBuf::from(root));
        }
    }
    for key in ["ANDROID_HOME", "ANDROID_SDK_ROOT"] {
        if let Some(sdk) = std::env::var_os(key) {
            roots.extend(ndk_versions(&PathBuf::from(sdk).join("ndk")));
        }
    }
    if let Some(home) = std::env::var_os("HOME") {
        roots.extend(ndk_versions(
            &PathBuf::from(home).join("Library/Android/sdk/ndk"),
        ));
    }
    roots
}

fn ndk_versions(directory: &Path) -> Vec<PathBuf> {
    let Ok(entries) = std::fs::read_dir(directory) else {
        return Vec::new();
    };
    let mut versions: Vec<PathBuf> = entries
        .flatten()
        .map(|entry| entry.path())
        .filter(|path| path.is_dir())
        .collect();
    // Newest NDK directory first; names sort lexically by version segments.
    versions.sort();
    versions.reverse();
    versions
}

fn tool_in_directory(directory: &Path, name: &str) -> Option<PathBuf> {
    let candidate = directory.join(name);
    if executable_file(&candidate) {
        return Some(candidate);
    }
    #[cfg(windows)]
    {
        let candidate = directory.join(format!("{name}.exe"));
        if executable_file(&candidate) {
            return Some(candidate);
        }
    }
    None
}

fn executable_file(path: &Path) -> bool {
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

use crate::{
    digest_bytes, digest_text, validate_relative_source_path, ProviderSourceFile,
    SourceProviderConfig, SourceProviderKind, MAX_SOURCE_FILE_BYTES,
};
use aps_contracts::SourceDigest;
use aps_platform::{CancellationToken, ProcessRequest, ProcessRunner};
use std::collections::HashMap;
use std::path::{Component, Path, PathBuf};
use std::time::Duration;

/// Compose SourceProvider. Implementations are synchronous and blocking; the
/// desktop app calls them from the background executor only.
pub trait SourceProvider {
    fn kind(&self) -> SourceProviderKind;

    fn resolve_revision(&self, config: &SourceProviderConfig) -> Result<String, String>;

    fn list_files(
        &self,
        config: &SourceProviderConfig,
        revision: &str,
    ) -> Result<Vec<ProviderSourceFile>, String>;

    fn read_file(
        &self,
        config: &SourceProviderConfig,
        revision: &str,
        relative_path: &str,
    ) -> Result<Vec<u8>, String>;
}

/// Compose SourceProviderRegistry: exactly one provider per kind.
pub struct SourceProviderRegistry {
    providers: HashMap<SourceProviderKind, Box<dyn SourceProvider>>,
}

impl SourceProviderRegistry {
    pub fn new(providers: Vec<Box<dyn SourceProvider>>) -> Self {
        Self {
            providers: providers
                .into_iter()
                .map(|provider| (provider.kind(), provider))
                .collect(),
        }
    }

    pub fn local() -> Self {
        Self::new(vec![Box::new(LocalSourceProvider)])
    }

    pub fn provider_for(&self, kind: SourceProviderKind) -> Result<&dyn SourceProvider, String> {
        self.providers
            .get(&kind)
            .map(|provider| provider.as_ref())
            .ok_or_else(|| format!("no source provider registered for {kind:?}"))
    }
}

/// Local Git-aware provider. The root is used exactly as supplied; the walk is
/// explicitly root-bounded, never follows symlinks and enforces the Compose
/// 5 MiB per-file limit and extension allowlist.
pub struct LocalSourceProvider;

impl SourceProvider for LocalSourceProvider {
    fn kind(&self) -> SourceProviderKind {
        SourceProviderKind::Local
    }

    fn resolve_revision(&self, config: &SourceProviderConfig) -> Result<String, String> {
        let root = local_root(config)?;
        if !root.is_dir() {
            return Err(format!(
                "Local source root is not a directory: {}",
                root.display()
            ));
        }
        let commit = git_output(root, &["rev-parse", "HEAD"])
            .filter(|output| !output.is_empty())
            .unwrap_or_else(|| "unversioned".to_owned());
        let dirty = match git_output(root, &["status", "--porcelain"]) {
            Some(output) => !output.is_empty(),
            None => commit == "unversioned",
        };
        if !dirty {
            return Ok(commit);
        }
        let digest = content_digest(root)?;
        Ok(format!("{commit}-dirty-{}", digest.to_hex()))
    }

    fn list_files(
        &self,
        config: &SourceProviderConfig,
        _revision: &str,
    ) -> Result<Vec<ProviderSourceFile>, String> {
        let root = normalized_root(local_root(config)?)?;
        if !root.is_dir() {
            return Err(format!(
                "Local source root is not a directory: {}",
                root.display()
            ));
        }
        let mut files = Vec::new();
        let mut directories = vec![String::new()];
        while let Some(relative) = directories.pop() {
            let directory = if relative.is_empty() {
                root.clone()
            } else {
                root.join(&relative)
            };
            let entries = std::fs::read_dir(&directory)
                .map_err(|_| format!("cannot read source directory: {relative}"))?;
            for entry in entries {
                let entry =
                    entry.map_err(|_| format!("cannot read source directory: {relative}"))?;
                let name = entry
                    .file_name()
                    .into_string()
                    .map_err(|_| "source file name is not valid UTF-8".to_owned())?;
                if ignored_segment(&name) {
                    continue;
                }
                let relative_path = if relative.is_empty() {
                    name.clone()
                } else {
                    format!("{relative}/{name}")
                };
                if relative_path.len() > 4096 {
                    continue;
                }
                let metadata = std::fs::symlink_metadata(entry.path())
                    .map_err(|_| format!("cannot inspect source entry: {relative_path}"))?;
                if metadata.file_type().is_symlink() {
                    continue;
                }
                if metadata.is_dir() {
                    directories.push(relative_path);
                    continue;
                }
                if !metadata.is_file()
                    || metadata.len() > MAX_SOURCE_FILE_BYTES
                    || !allowed_extension(&name)
                {
                    continue;
                }
                files.push(ProviderSourceFile {
                    relative_path,
                    size_bytes: metadata.len(),
                    content_hash: None,
                });
            }
        }
        files.sort_by(|left, right| left.relative_path.cmp(&right.relative_path));
        Ok(files)
    }

    fn read_file(
        &self,
        config: &SourceProviderConfig,
        _revision: &str,
        relative_path: &str,
    ) -> Result<Vec<u8>, String> {
        let root = normalized_root(local_root(config)?)?;
        validate_relative_source_path(relative_path)?;
        let path = root.join(relative_path);
        let metadata = std::fs::symlink_metadata(&path)
            .map_err(|_| format!("source file is unavailable: {relative_path}"))?;
        if !metadata.is_file() {
            return Err(format!(
                "source file is not a regular file: {relative_path}"
            ));
        }
        if metadata.len() > MAX_SOURCE_FILE_BYTES {
            return Err(format!("source file exceeds size limit: {relative_path}"));
        }
        std::fs::read(&path).map_err(|_| format!("cannot read source file: {relative_path}"))
    }
}

fn local_root(config: &SourceProviderConfig) -> Result<&Path, String> {
    match config {
        SourceProviderConfig::Local { root } => Ok(root),
        _ => Err("LocalSourceProvider requires Local config".to_owned()),
    }
}

fn normalized_root(root: &Path) -> Result<PathBuf, String> {
    let absolute = if root.is_absolute() {
        root.to_path_buf()
    } else {
        std::env::current_dir()
            .map_err(|_| "cannot resolve the current directory".to_owned())?
            .join(root)
    };
    let mut normalized = PathBuf::new();
    for component in absolute.components() {
        match component {
            Component::CurDir => {}
            Component::ParentDir => {
                normalized.pop();
            }
            other => normalized.push(other.as_os_str()),
        }
    }
    Ok(normalized)
}

fn ignored_segment(name: &str) -> bool {
    crate::IGNORED_DIRECTORY_NAMES.contains(&name)
}

fn allowed_extension(name: &str) -> bool {
    let Some((_, extension)) = name.rsplit_once('.') else {
        return false;
    };
    crate::SourceLanguage::extension_list()
        .iter()
        .any(|allowed| extension.eq_ignore_ascii_case(allowed))
}

fn content_digest(root: &Path) -> Result<SourceDigest, String> {
    let files = LocalSourceProvider.list_files(
        &SourceProviderConfig::Local {
            root: root.to_path_buf(),
        },
        "current",
    )?;
    let mut lines = Vec::with_capacity(files.len());
    for file in files {
        let bytes = LocalSourceProvider.read_file(
            &SourceProviderConfig::Local {
                root: root.to_path_buf(),
            },
            "current",
            &file.relative_path,
        )?;
        lines.push(format!(
            "{}:{}",
            file.relative_path,
            digest_bytes(&bytes).to_hex()
        ));
    }
    Ok(digest_text(&lines.join("\n")))
}

fn git_output(root: &Path, arguments: &[&str]) -> Option<String> {
    let mut request = ProcessRequest::new("git");
    request.timeout = Duration::from_secs(10);
    request.stdout_limit = 64 * 1024;
    request.stderr_limit = 64 * 1024;
    request = request.arg("-C").arg(root.as_os_str());
    for argument in arguments {
        request = request.arg(argument);
    }
    match ProcessRunner.run(&request, &CancellationToken::new()) {
        Ok(output) if output.status.success() => {
            Some(String::from_utf8_lossy(&output.stdout).trim().to_owned())
        }
        _ => None,
    }
}

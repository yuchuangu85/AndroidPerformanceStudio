use crate::{
    index,
    model::*,
    paths::{checked_path, excluded_segment},
    snapshot::SourceSnapshot,
};
use aps_contracts::SourceDigest;
use std::fs::{File, Metadata};
use std::io::Read;
use std::path::{Path, PathBuf};

#[derive(Debug)]
pub struct LocalSourceWorkspace {
    root: PathBuf,
    id: SourceWorkspaceId,
    display_name: String,
    root_identity: Metadata,
}
impl LocalSourceWorkspace {
    /// Only an explicitly supplied directory is registered. No filesystem walk,
    /// Git invocation, credential discovery or network call happens here.
    pub fn register(
        root: impl AsRef<Path>,
        display_name: impl Into<String>,
    ) -> Result<Self, String> {
        let supplied = root.as_ref();
        let metadata = std::fs::symlink_metadata(supplied)
            .map_err(|_| "selected source root is unavailable")?;
        if metadata.file_type().is_symlink() || !metadata.is_dir() {
            return Err("source root must be an explicitly selected non-symlink directory".into());
        }
        let root = supplied
            .canonicalize()
            .map_err(|_| "cannot resolve selected source root")?;
        if root.parent().is_none() {
            return Err("filesystem root cannot be registered as a source workspace".into());
        }
        if let Some(home) = std::env::var_os("HOME") {
            if PathBuf::from(home).canonicalize().ok().as_ref() == Some(&root) {
                return Err(
                    "home directory cannot be registered wholesale as a source workspace".into(),
                );
            }
        }
        if root
            .file_name()
            .and_then(|value| value.to_str())
            .is_none_or(excluded_segment)
        {
            return Err("selected source root is excluded by source privacy policy".into());
        }
        let display_name = display_name.into();
        aps_contracts::validate_text(&display_name, "source workspace display name", 256)?;
        let identity_text = root.to_str().ok_or("source root must be valid UTF-8")?;
        let identity = format!("aps-local-workspace-v1\0{identity_text}");
        let id = SourceWorkspaceId(SourceDigest::hash_reader(
            identity.as_bytes(),
            identity.len() as u64,
        )?);
        let root_identity = std::fs::metadata(&root).map_err(|_| "source root is unavailable")?;
        Ok(Self {
            root,
            id,
            display_name,
            root_identity,
        })
    }
    pub fn id(&self) -> SourceWorkspaceId {
        self.id
    }
    pub fn display_name(&self) -> &str {
        &self.display_name
    }
    /// Local UI-only absolute root; exclude it from archives, prompts and logs.
    pub fn root(&self) -> &Path {
        &self.root
    }
    fn check_root(&self) -> Result<(), String> {
        let metadata = std::fs::symlink_metadata(&self.root)
            .map_err(|_| "source workspace root is unavailable")?;
        if !metadata.is_dir() || metadata.file_type().is_symlink() {
            return Err("source workspace root identity changed".into());
        }
        #[cfg(unix)]
        {
            use std::os::unix::fs::MetadataExt;
            if metadata.dev() != self.root_identity.dev()
                || metadata.ino() != self.root_identity.ino()
            {
                return Err("source workspace root identity changed".into());
            }
        }
        Ok(())
    }
    pub fn snapshot(&self, limits: SourceLimits) -> Result<SourceSnapshot, String> {
        limits.validate()?;
        self.check_root()?;
        let mut files = Vec::new();
        let mut symbols = Vec::new();
        let mut coverage = IndexCoverage::default();
        let mut ignore_rules = Vec::new();
        self.walk(
            "",
            0,
            limits,
            &mut files,
            &mut symbols,
            &mut coverage,
            &mut ignore_rules,
        )?;
        self.check_root()?;
        files.sort_by(|a, b| a.relative_path.cmp(&b.relative_path));
        symbols.sort_by(|a, b| {
            a.relative_path
                .cmp(&b.relative_path)
                .then_with(|| a.line.cmp(&b.line))
                .then_with(|| a.name.cmp(&b.name))
        });
        SourceSnapshot::create(self.id, files, symbols, coverage, limits)
    }
    fn walk(
        &self,
        relative: &str,
        depth: usize,
        limits: SourceLimits,
        files: &mut Vec<SourceFile>,
        symbols: &mut Vec<SourceSymbol>,
        coverage: &mut IndexCoverage,
        ignore_rules: &mut Vec<crate::ignore::IgnoreRule>,
    ) -> Result<(), String> {
        if depth > limits.max_depth {
            coverage.depth_budget_exhausted = true;
            return Ok(());
        }
        let directory = self.root.join(relative);
        let metadata =
            std::fs::symlink_metadata(&directory).map_err(|_| "source directory is unavailable")?;
        if metadata.file_type().is_symlink() || !metadata.is_dir() {
            coverage.excluded_entries += 1;
            return Ok(());
        }
        let canonical = directory
            .canonicalize()
            .map_err(|_| "source directory is unavailable")?;
        if !canonical.starts_with(&self.root) {
            return Err("source directory escaped registered workspace".into());
        }
        let mut local_rules = ignore_rules.clone();
        if crate::ignore::load(&self.root, relative, &mut local_rules).is_err() {
            coverage.unreadable_entries += 1;
            return Ok(());
        }
        let entries = match std::fs::read_dir(&directory) {
            Ok(entries) => entries,
            Err(_) => {
                coverage.unreadable_entries += 1;
                return Ok(());
            }
        };
        let mut names = Vec::new();
        let remaining = limits.max_entries.saturating_sub(coverage.visited_entries);
        for entry in entries {
            let entry = match entry {
                Ok(entry) => entry,
                Err(_) => {
                    coverage.unreadable_entries += 1;
                    continue;
                }
            };
            if names.len() == remaining {
                coverage.entry_budget_exhausted = true;
                return Ok(());
            }
            names.push(entry.file_name());
        }
        names.sort();
        for name in names {
            if coverage.visited_entries == limits.max_entries {
                coverage.entry_budget_exhausted = true;
                break;
            }
            coverage.visited_entries += 1;
            let Some(name) = name.to_str() else {
                coverage.excluded_entries += 1;
                continue;
            };
            if excluded_segment(name) {
                coverage.excluded_entries += 1;
                continue;
            }
            let path = if relative.is_empty() {
                name.to_owned()
            } else {
                format!("{relative}/{name}")
            };
            if path.len() > 1024 {
                coverage.excluded_entries += 1;
                continue;
            }
            let metadata = match std::fs::symlink_metadata(self.root.join(&path)) {
                Ok(value) => value,
                Err(_) => {
                    coverage.unreadable_entries += 1;
                    continue;
                }
            };
            if metadata.file_type().is_symlink() {
                coverage.excluded_entries += 1;
                continue;
            }
            if crate::ignore::excluded(&path, metadata.is_dir(), &local_rules) {
                coverage.excluded_entries += 1;
                continue;
            }
            if metadata.is_dir() {
                self.walk(
                    &path,
                    depth + 1,
                    limits,
                    files,
                    symbols,
                    coverage,
                    &mut local_rules,
                )?;
                continue;
            }
            let Some(language) = SourceLanguage::from_path(&path) else {
                coverage.excluded_entries += 1;
                continue;
            };
            if !metadata.is_file() {
                coverage.excluded_entries += 1;
                continue;
            }
            if files.len() == limits.max_files {
                coverage.file_budget_exhausted = true;
                coverage.omitted_files += 1;
                continue;
            }
            if metadata.len() > limits.max_file_bytes {
                coverage.omitted_files += 1;
                continue;
            }
            if metadata.len()
                > limits
                    .max_total_bytes
                    .saturating_sub(coverage.indexed_bytes)
            {
                coverage.byte_budget_exhausted = true;
                coverage.omitted_files += 1;
                continue;
            }
            let text = match self.read_text(&path, limits.max_file_bytes) {
                Ok(text) => text,
                Err(_) => {
                    coverage.unreadable_entries += 1;
                    continue;
                }
            };
            if text.len() as u64
                > limits
                    .max_total_bytes
                    .saturating_sub(coverage.indexed_bytes)
            {
                coverage.byte_budget_exhausted = true;
                coverage.omitted_files += 1;
                continue;
            }
            let hash = SourceDigest::hash_reader(text.as_bytes(), limits.max_file_bytes)?;
            let line_count = text.lines().count().max(1);
            let module = module_hint(&path);
            let (discovered, truncated) = index::symbols(
                &path,
                &text,
                language,
                limits.max_symbols.saturating_sub(symbols.len()),
            );
            coverage.symbol_budget_exhausted |= truncated;
            coverage.indexed_bytes += text.len() as u64;
            symbols.extend(discovered);
            files.push(SourceFile {
                relative_path: path,
                content_hash: hash,
                language,
                byte_length: text.len() as u64,
                line_count,
                module,
            });
            coverage.indexed_files = files.len();
            coverage.indexed_symbols = symbols.len();
        }
        Ok(())
    }
    pub fn read_location(
        &self,
        snapshot: &SourceSnapshot,
        location: &SourceLocation,
        limits: SnippetLimits,
    ) -> Result<SourceSnippet, String> {
        limits.validate()?;
        self.check_root()?;
        snapshot.validate_location(location)?;
        if snapshot.workspace_id() != self.id {
            return Err("source snapshot belongs to a different workspace".into());
        }
        let text = self.read_text(&location.relative_path, snapshot.limits().max_file_bytes)?;
        if SourceDigest::hash_reader(text.as_bytes(), snapshot.limits().max_file_bytes)?
            != location.content_hash
        {
            return Err("source content no longer matches snapshot; explicitly create and resolve a new snapshot".into());
        }
        let mut output = String::new();
        let mut end_line = location.start_line;
        let mut truncated = false;
        let mut appended_lines = 0usize;
        for (index, line) in text.split_inclusive('\n').enumerate() {
            let line_number = index + 1;
            if line_number < location.start_line {
                continue;
            }
            if line_number > location.end_line {
                break;
            }
            if appended_lines >= limits.max_lines
                || line.len() > limits.max_bytes.saturating_sub(output.len())
            {
                if appended_lines == 0 {
                    return Err("requested first source line exceeds snippet byte budget".into());
                }
                truncated = true;
                break;
            }
            output.push_str(line);
            appended_lines += 1;
            end_line = line_number;
        }

        self.check_root()?;
        Ok(SourceSnippet {
            location: location.clone(),
            text: output,
            total_file_lines: text.lines().count().max(1),
            start_line: location.start_line,
            end_line,
            truncated,
        })
    }
    fn read_text(&self, relative: &str, max_bytes: u64) -> Result<String, String> {
        self.check_root()?;
        let path = checked_path(&self.root, relative)?;
        let mut file = File::open(&path).map_err(|_| "source file is unavailable")?;
        let before = file.metadata().map_err(|_| "cannot inspect source file")?;
        if !before.is_file() || before.len() > max_bytes {
            return Err("source file exceeds byte budget or is not a regular file".into());
        }
        let path_before =
            std::fs::symlink_metadata(&path).map_err(|_| "source path is unavailable")?;
        if !same_file(&before, &path_before) {
            return Err("source file identity changed before reading".into());
        }
        let mut bytes = Vec::new();
        bytes
            .try_reserve_exact(before.len() as usize)
            .map_err(|_| "cannot allocate source buffer")?;
        (&mut file)
            .take(max_bytes + 1)
            .read_to_end(&mut bytes)
            .map_err(|_| "cannot read source file")?;
        if bytes.len() as u64 > max_bytes {
            return Err("source file exceeds streaming byte budget".into());
        }
        let after = file
            .metadata()
            .map_err(|_| "cannot inspect open source file")?;
        let checked = checked_path(&self.root, relative)?;
        let path_after = std::fs::symlink_metadata(checked)
            .map_err(|_| "source file is unavailable after reading")?;
        if !same_file(&before, &after) || !same_file(&before, &path_after) {
            return Err("source file identity changed while reading".into());
        }
        if bytes.contains(&0) {
            return Err("binary source content is not supported".into());
        }
        String::from_utf8(bytes).map_err(|_| "source content must be valid UTF-8".into())
    }
}
fn same_file(before: &Metadata, after: &Metadata) -> bool {
    if !after.is_file()
        || after.file_type().is_symlink()
        || before.len() != after.len()
        || before.modified().ok() != after.modified().ok()
    {
        return false;
    }
    #[cfg(unix)]
    {
        use std::os::unix::fs::MetadataExt;
        if before.dev() != after.dev()
            || before.ino() != after.ino()
            || before.ctime() != after.ctime()
            || before.ctime_nsec() != after.ctime_nsec()
        {
            return false;
        }
    }
    true
}
fn module_hint(path: &str) -> String {
    let parts: Vec<_> = path.split('/').collect();
    if let Some(index) = parts.iter().position(|part| *part == "src") {
        if index > 0 {
            return parts[..index].join("/");
        }
    }
    if parts.len() > 1 {
        parts[0].into()
    } else {
        "(root)".into()
    }
}

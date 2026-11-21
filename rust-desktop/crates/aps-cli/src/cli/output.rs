//! Terminal output and atomic, no-clobber file publication.
use std::fs;
use std::io::{self, Write};
use std::path::{Path, PathBuf};

pub(super) fn available_destination(path: &Path) -> Result<PathBuf, String> {
    match fs::symlink_metadata(path) {
        Err(error) if error.kind() == io::ErrorKind::NotFound => (),
        _ => {
            return Err(
                "E_OUTPUT: refusing to overwrite an existing or inaccessible destination".into(),
            );
        }
    }
    let name = path.file_name().ok_or("E_OUTPUT: invalid filename")?;
    let parent = path
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .unwrap_or(Path::new("."));
    let dir = fs::canonicalize(parent).map_err(|_| "E_OUTPUT: inaccessible output directory")?;
    if !dir.is_dir() {
        return Err("E_OUTPUT: expected directory".into());
    }
    Ok(dir.join(name))
}

pub(super) fn write_json(value: &serde_json::Value, dest: Option<&Path>) -> Result<(), String> {
    let mut bytes = serde_json::to_vec(value).map_err(|_| "E_EVIDENCE_ENCODING")?;
    bytes.push(b'\n');
    if let Some(path) = dest {
        return write_new(path, &bytes);
    }
    let mut out = io::stdout().lock();
    out.write_all(&bytes)
        .and_then(|_| out.flush())
        .map_err(|_| "E_OUTPUT: cannot write stdout".into())
}

/// Publish the complete file without ever truncating an existing destination
/// (including the selected input, hardlinks and dangling symlinks).
pub fn write_new(path: &Path, bytes: &[u8]) -> Result<(), String> {
    let parent = path
        .parent()
        .filter(|p| !p.as_os_str().is_empty())
        .unwrap_or(Path::new("."));
    let mut tmp = tempfile::NamedTempFile::new_in(parent)
        .map_err(|_| "E_OUTPUT: cannot stage file in selected directory")?;
    tmp.write_all(bytes)
        .and_then(|_| tmp.as_file().sync_all())
        .map_err(|_| "E_OUTPUT: cannot write complete file")?;
    tmp.persist_noclobber(path)
        .map_err(|_| "E_OUTPUT: destination exists or cannot be published")?;
    Ok(())
}

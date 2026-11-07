use crate::SourceLanguage;
use std::path::{Path, PathBuf};

pub fn validate_relative_source_path(relative: &str) -> Result<(), String> {
    if relative.is_empty()
        || relative.len() > 1024
        || relative.starts_with('/')
        || relative.contains(['\\', ':', '\0'])
        || relative.chars().any(char::is_control)
    {
        return Err("source path must be a bounded, plain relative slash path".into());
    }
    for segment in relative.split('/') {
        if excluded_segment(segment) {
            return Err("source path contains an excluded or unsafe component".into());
        }
    }
    if SourceLanguage::from_path(relative).is_none() {
        return Err("file type is not an allowed source language".into());
    }
    Ok(())
}
pub(crate) fn excluded_segment(segment: &str) -> bool {
    if segment.is_empty()
        || segment == "."
        || segment == ".."
        || segment.starts_with('.')
        || segment.ends_with('.')
        || segment.ends_with(' ')
    {
        return true;
    }
    let lower = segment.to_ascii_lowercase();
    matches!(
        lower.as_str(),
        "build"
            | "out"
            | "target"
            | "node_modules"
            | "vendor"
            | "credentials"
            | "secrets"
            | "private"
            | "keystore"
            | "keychain"
            | "id_rsa"
            | "id_ed25519"
            | "local.properties"
            | "google-services.json"
    ) || lower.contains("credential")
        || lower.contains("secret")
        || lower.contains("password")
        || lower.contains("token")
        || lower.ends_with(".pem")
        || lower.ends_with(".key")
        || lower.ends_with(".p12")
        || lower.ends_with(".pfx")
        || lower.ends_with(".jks")
        || lower.ends_with(".keystore")
}
/// Reject all symlink components, not just final symlinks. Returns only after
/// canonical containment checks; callers repeat after reading before publishing.
pub(crate) fn checked_path(root: &Path, relative: &str) -> Result<PathBuf, String> {
    validate_relative_source_path(relative)?;
    let mut path = root.to_path_buf();
    for (index, segment) in relative.split('/').enumerate() {
        path.push(segment);
        let metadata =
            std::fs::symlink_metadata(&path).map_err(|_| "source path is unavailable")?;
        if metadata.file_type().is_symlink() {
            return Err("source symlink access is not allowed".into());
        }
        if index + 1 < relative.split('/').count() && !metadata.is_dir() {
            return Err("source parent is not a directory".into());
        }
    }
    let canonical = path
        .canonicalize()
        .map_err(|_| "cannot resolve source path")?;
    if !canonical.starts_with(root) || !canonical.is_file() {
        return Err("source path escapes workspace or is not a regular file".into());
    }
    Ok(path)
}

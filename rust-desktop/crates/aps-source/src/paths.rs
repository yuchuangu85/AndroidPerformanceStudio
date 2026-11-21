use crate::SourceLanguage;

/// Reject traversal, absolute paths, alternate separators and unsupported file
/// types before any filesystem access. This is lexical validation, not a
/// race-proof OS sandbox: the local provider also checks symlinks and size.
pub fn validate_relative_source_path(relative: &str) -> Result<(), String> {
    if relative.is_empty()
        || relative.len() > 4096
        || relative.starts_with('/')
        || relative.contains(['\\', ':', '\0'])
        || relative.chars().any(char::is_control)
    {
        return Err("source path must be a bounded, plain relative slash path".into());
    }
    for segment in relative.split('/') {
        if segment.is_empty() || segment == "." || segment == ".." {
            return Err("source path contains an unsafe component".into());
        }
    }
    if !SourceLanguage::extension_list()
        .iter()
        .any(|extension| extension_matches(relative, extension))
    {
        return Err("file type is not an allowed source language".into());
    }
    Ok(())
}

fn extension_matches(path: &str, extension: &str) -> bool {
    path.rsplit('.')
        .next()
        .is_some_and(|value| value.eq_ignore_ascii_case(extension))
}

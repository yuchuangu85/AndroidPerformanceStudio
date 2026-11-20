use aps_analysis::frame::MAX_INPUT_BYTES;
use aps_contracts::SourceDigest;
use std::fs::{self, File};
use std::io::Read;
use std::path::Path;

pub(crate) fn read_bounded(path: &Path, max_bytes: u64) -> Result<Vec<u8>, String> {
    let metadata = fs::metadata(path).map_err(|_| "Cannot inspect selected frame input")?;
    require_regular_input(&metadata, max_bytes)?;
    let file = File::open(path).map_err(|_| "Cannot open selected frame input")?;
    require_regular_input(
        &file
            .metadata()
            .map_err(|_| "Cannot inspect open frame input")?,
        max_bytes,
    )?;
    let mut bytes = Vec::new();
    file.take(max_bytes + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| "Cannot read selected frame input")?;
    if bytes.len() as u64 > max_bytes {
        return Err("Frame input exceeds byte limit".into());
    }
    Ok(bytes)
}

fn require_regular_input(metadata: &fs::Metadata, max_bytes: u64) -> Result<(), String> {
    if !metadata.is_file() || metadata.len() == 0 || metadata.len() > max_bytes {
        return Err("Frame input must be a nonempty regular file within the byte limit".into());
    }
    Ok(())
}

pub(crate) fn check_source(path: &Path, expected: SourceDigest) -> Result<(), String> {
    check_digest(path, MAX_INPUT_BYTES, expected)
}

pub(crate) fn check_digest(
    path: &Path,
    max_bytes: u64,
    expected: SourceDigest,
) -> Result<(), String> {
    require_regular_input(
        &fs::metadata(path).map_err(|_| "Cannot inspect selected frame input")?,
        max_bytes,
    )?;
    if SourceDigest::hash_file(path, max_bytes)? != expected {
        return Err("Selected frame input digest changed".into());
    }
    Ok(())
}

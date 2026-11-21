//! Framework-independent capture, source identity and evidence contracts.
//! IDs, digests and capabilities are correlation data, never authorization.

mod artifact;
mod clock;
mod digest;
mod error;
mod identity;

pub use artifact::*;
pub use clock::*;
pub use digest::*;
pub use error::*;
pub use identity::*;

pub const MAX_METADATA_TEXT_BYTES: usize = 4096;
pub const MAX_CAPABILITIES: usize = 256;
pub const MAX_SESSION_ARTIFACTS: usize = 10_000;

pub fn validate_text(value: &str, label: &str, limit: usize) -> Result<(), String> {
    if value.is_empty() || value.len() > limit || value.chars().any(char::is_control) {
        return Err(format!(
            "{label} must be nonempty, control-free text within {limit} bytes"
        ));
    }
    Ok(())
}

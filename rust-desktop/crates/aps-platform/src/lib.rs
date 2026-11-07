//! Explicit host-process and independent Rust-desktop filesystem boundaries.

mod paths;
mod process;

pub use paths::{AppDirectories, TempWorkspace, atomic_write};
pub use process::{
    CancellationToken, MAX_OUTPUT_LIMIT, ProcessError, ProcessOutput, ProcessRequest,
    ProcessRunner, Stream,
};

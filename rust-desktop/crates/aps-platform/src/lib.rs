//! Explicit host-process and independent Rust-desktop filesystem boundaries.

mod paths;
mod process;

pub use paths::{
    AppDirectories, TempWorkspace, atomic_write, create_private_directory, home_directory,
};
pub use process::{
    CancellationToken, MAX_OUTPUT_LIMIT, ProcessError, ProcessOutput, ProcessRequest,
    ProcessRunner, Stream,
};

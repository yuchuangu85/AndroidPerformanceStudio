//! Explicit-root, local read-only Source Workspaces. No provider/network/AI
//! access or implicit home-directory scan; no full source copy is persisted.
mod ignore;
mod index;
mod model;
mod paths;
mod snapshot;
mod workspace;

pub use model::*;
pub use paths::validate_relative_source_path;
pub use snapshot::SourceSnapshot;
pub use workspace::LocalSourceWorkspace;

//! Compose-parity Source Workspace domain for the Rust desktop app.
//!
//! Models, content-addressed cache, local Git-aware provider, structural
//! indexer, in-memory repository and evidence resolver mirror
//! desktop-viewer/source-workspace. Only the local provider is implemented:
//! no network, credential discovery, implicit home scan or source upload.
//!
//! Source text is cached content-addressed under the Rust-owned application
//! data directory and is never published outside the machine by this crate.
mod cache;
mod hashing;
mod indexer;
mod model;
mod paths;
mod provider;
mod repository;
mod resolver;

pub use cache::ContentAddressedSourceCache;
pub use hashing::{digest_bytes, digest_text};
pub use indexer::StructuralSourceIndexer;
pub use model::*;
pub use paths::validate_relative_source_path;
pub use provider::{LocalSourceProvider, SourceProvider, SourceProviderRegistry};
pub use repository::{InMemorySourceRepository, SourceRepository};
pub use resolver::IndexedSourceResolver;

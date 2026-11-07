//! Bounded, identity-correlated worker envelopes. Envelope metadata, operation
//! names and cancellation flags are not sandbox/permission/kill authority.
//!
//! Reuse only the existing codec source, not HPROF engine dispatch or models.
mod framing;
mod lifecycle;
mod message;
mod schema;
#[path = "../../../../desktop-viewer/memory-profiler/hprof-engine-rust/src/wire.rs"]
pub mod wire;

pub use framing::*;
pub use lifecycle::*;
pub use message::*;
pub use schema::*;
pub use wire::Value;

//! Bounded, identity-correlated worker envelopes. Envelope metadata, operation
//! names and cancellation flags are not sandbox/permission/kill authority.
//!
//! Only the wire codec is shared with the engines, not dispatch or models.
mod framing;
mod lifecycle;
mod message;
mod schema;
pub use aps_primitives::wire;

pub use framing::*;
pub use lifecycle::*;
pub use message::*;
pub use schema::*;
pub use wire::Value;

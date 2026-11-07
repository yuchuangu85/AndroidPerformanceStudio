# aps-protocol

Bounded worker transport envelopes using the existing typed-value codec source
at `desktop-viewer/memory-profiler/hprof-engine-rust/src/wire.rs` via `#[path]`.
It does not import HPROF dispatch, reinterpret old engine requests as the new
protocol, or claim compatibility with the older `version/id/op` envelopes.
`aps-contracts` currently has a transitive HPROF dependency solely for digest reuse.

## Schema

Request has exactly `protocolVersion`, `requestId`, `sourceDigest`, `sessionId`,
`generation`, `operation`, and map-valued `args`. Response has exactly
`protocolVersion`, `requestId`, `sourceDigest`, `sessionId`, `generation`, `status`,
`payload`, and `error`. Protocol version is 1; generations fit nonnegative i64.
Digests normalize to 64-character SHA-256 lowercase hex. Typed IDs are bounded.
Unknown/missing/wrong-typed envelope fields and duplicate raw map keys fail closed.
`Response::validate_for` checks all correlation fields, not only request IDs.

Operation names are bounded identifiers, not an allowlist. Each implementing
handler should supply `OperationSchema` to reject unknown/missing/wrong-typed
arguments, then perform its own value, authorization and resource checks. A map
that passes envelope validation is not authority to execute its operation.

## Framing and budgets

Frames are four-byte big-endian byte lengths followed by one typed Value. Default
limits are 4 MiB encoded bytes, 256 KiB strings, 100,000 values, depth 32; callers
may narrow them within the existing codec's hard limits. Reads reject the length
before allocating the body. The legacy codec applies its hard decode limits;
configured smaller string/count/depth limits are checked immediately after that
bounded decode, not during its internal allocations. Encoding computes the exact
byte budget before encoding/writing. I/O failures can leave partial bytes: close
that channel rather than retrying into a partially written frame.

`GenerationGuard` rejects identity mismatches, stale generations and repeated
request IDs, with at most 4096 accepted IDs per generation. Advancing generation
requires explicit monotonically increasing state; it does not cancel old workers.

`CancellationState` is an atomic per-request cooperative lifecycle. Requesting
cancel does not mark a request cancelled until a performer acknowledges it.
Neither the flag nor a cancelled response proves OS termination, resource cleanup,
permission, credential access or sandbox enforcement. Those remain host runner
and operation-owner responsibilities.

Tests are written but not executed, following the user's rewrite-first instruction.

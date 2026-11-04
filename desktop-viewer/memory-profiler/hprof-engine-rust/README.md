# Rust HPROF Engine

The default, independently owned HPROF engine for Memory Profiler. The original
Kotlin parser and analyzer remain the selectable Legacy engine. Explicitly saved
engine preferences remain respected, and errors never silently select a different engine.

The crate uses the Rust standard library only. Instances and arrays retain source
offsets; values are read on demand. Object identity, packed strong-reference edges,
reachability, dominators and retained sizes stay in the child process. Kotlin
receives class metadata, findings and bounded instance/dominator pages, not a
reconstructed `HeapDump`.

This is a compact **in-memory** native graph/index, not a general disk-backed
out-of-core dominator engine. There is no persistent analysis-index cache yet.
Every snapshot is independently opened and analyzed. Large responses fail
explicitly rather than returning a truncated success.

## Build And Verify

Rust 1.85.1 or newer is required. From this directory:

```sh
cargo build --release --locked --offline
cargo test --locked --offline
cargo clippy --locked --offline --all-targets -- -D warnings
cargo fmt -- --check
```

From `desktop-viewer`, the integrated build and tests are:

```sh
./gradlew :memory-profiler:memory-app:buildRustHprofEngine
./gradlew :memory-profiler:memory-app:test
```

The development resolver accepts `aps.hprofEngine.path` or `APS_HPROF_ENGINE`.
Packaged binaries live in application resources under `hprof-engine/` with a
required sibling `.sha256` file. Cross-architecture packaging requires an
explicit matching prebuilt binary; it cannot reuse the host executable silently.

The application setting affects later loads. Open snapshots retain their own
engine until closed. Kotlin owns immutable content-addressed source leases; their
last release reclaims managed copies without deleting the original capture or
imported file. Cancellation and snapshot eviction close only the owned process.

See the [protocol and implementation record](../../docs/design/rust-hprof-engine-implementation.md)
for supported operations, feature parity and validation boundaries. Private HPROF
samples are opt-in local inputs and must never be committed or uploaded.

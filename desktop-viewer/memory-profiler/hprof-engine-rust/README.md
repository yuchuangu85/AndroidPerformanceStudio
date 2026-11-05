# Rust HPROF Engine

The default, independently owned HPROF engine for Memory Profiler. The original
Kotlin parser and analyzer remain the selectable Legacy engine. Explicitly saved
engine preferences remain respected, and errors never silently select a different engine.

The crate uses the Rust standard library only. Instances and arrays retain source
offsets; values are read on demand. Object identity, packed strong-reference edges,
reachability, dominators and retained sizes stay in the child process. Kotlin
receives class metadata, findings and bounded instance/dominator pages, not a
reconstructed `HeapDump`.

The default remains a compact **in-memory** native graph with an optional
persistent parser index. An opt-in ephemeral disk edge store is available, but
this is **not a fully out-of-core dominator engine**. Reopen restores classes,
objects, roots, warnings and source offsets into Rust memory; graph, reachability
and dominators are rebuilt for the current mapping on every open.
Large responses fail explicitly rather than returning a truncated success.

## Stage 2 Disk Edges (Opt-In)

`open.args.graphStorage` is optional and defaults to `"memory"`. `"disk"` requires
a nonempty `graphDir` naming a trusted host-managed directory. Unknown modes,
invalid types, missing directories' parents, non-directory paths, symlinked
directories, and file creation/read/write/flush failures return explicit errors.
There is no fallback to memory. An absent final directory is created with 0700
permissions; existing directory permissions and contents are not changed.

The backend can opt in using JVM property `aps.hprof.rust.graphStorage=disk`
(default `memory`), and supplies `dataRoot/hprof-graph-rust` separately from
`dataRoot/hprof-index-rust`. The snapshot adapter forwards `graphStorage` and
`graphDir` without exposing file data to Kotlin. The wire version is unchanged.

Targets and slots are interleaved little-endian u32 pairs in one private temporary
file. Construction appends array edges as bounded source pages are read; instance
edges are sorted per instance to preserve field-name/root-chain tie breaking,
then appended. This is **not** an in-memory graph followed by a spill. The disk
writer uses a fixed 64 KiB buffer, then the reader uses one 64 KiB block cache
independent of edge count. Root traversal, dominators, and inbound detail queries
share the same fallible edge API. Read failures, truncated files, and invalid
targets return errors rather than empty results or unchecked indexing. File
length is checked at operation boundaries and block misses, including before
queries whose requested page would otherwise be empty.

On Unix the file is exclusively created with 0600 permissions and immediately
unlinked **before any heap data is written**. Its open handle alone owns its
lifetime, so close, failed builds, EOF, process exit, and SIGKILL reclaim it.
No directory sweep occurs; neither source files, parser cache entries nor other
sessions' files are deleted. Disk mode is explicitly unsupported on non-Unix
platforms until an equivalent owner-only, delete-on-exit implementation is
available; memory mode remains available there. Application-owned parents are
required; this does not defend against a hostile same-user ancestor-path race.

Offsets, parser metadata, class layouts, root queues, reachability arrays,
dominator work arrays **including the reachable predecessor graph**, and query
sorting buffers are still in memory. Lexical array traversal retains per-array
ordering scratch, and inbound detail retains output-related scratch subject to
existing protocol limits. Streaming edges reduces edge-store residency, but no
whole-process peak-memory or runtime improvement has been measured or promised.
It must not be described as full out-of-core construction, analysis, or querying.
Parser cache format, checksum, sourceDigest authorization, and metadata validation
are unchanged. Tests compare both stores against the existing 3,000-graph
vertex-removal oracle and cover reachability, retained/immediate results, weak
references, mapping, paging, large cross-block arrays, I/O errors and cleanup.

## Stage 1 Cache Protocol

`open` accepts optional `cacheDir` (absent/null/empty disables caching) and returns
the existing bounded metadata plus `cacheStatus`: `disabled`, `miss`, or `hit`.
The Kotlin backend forwards its `hprof-index-rust` data directory. Version 2
binary entries are `<lowercase-sourceDigest>.idx`, separate from the Legacy record index.
They store compact parser metadata and offsets, not source payloads, graph edges,
retained sizes, mapping or Kotlin `HeapDump` data. Close releases Rust state but
retains the disk entry; reopen uses a new process and rebuilds analysis.

`sourceDigest` must be exactly 64 ASCII hex characters (either case; cache keys
are normalized to lowercase). With caching enabled Rust independently streams
SHA-256 from an open source handle and checks the actual content against that
digest. File size/mtime alone cannot authorize a hit. A wrong/stale source digest
is an explicit source identity error, not an empty or successful cached snapshot.
The existing `Source` metadata checks bracket hashing, parsing, cache reads and
writes, and check both the open handle and its current path. Kotlin's immutable
source lease remains required; this is not an independent source locking service.

Every entry has a trailing SHA-256 over the complete preceding header and index
body. Integrity is verified with bounded streaming buffers before decoding
collection counts, then again while decoding. Restored index types, identities,
heap indices and source payload ranges are validated. Unsupported versions,
truncation, bit corruption, invalid metadata and cache read failures cause a miss
and normal source parsing, followed by a best-effort rebuild. Cache directory,
permission, write, sync and publication failures do not prevent a valid source
from opening. `cacheStatus=miss` does not promise a cache was successfully written.
Source mutation/digest errors and invalid original HPROF still fail explicitly.

Directories/files are created owner-only on Unix (0700/0600); existing cache
directories are restricted to 0700 and broadly readable entries are not loaded.
Cache-directory and entry symlinks are refused, including eviction. Temporary
files use unique names plus exclusive creation and are synced and closed before
rename. If replacement rename fails on an existing entry (including Windows), a
verified concurrent winner is kept; an invalid regular entry is removed and
publication retried once. Any remaining conflict is a discardable write failure.

After open, `evict` accepts `cacheDir` and the session's `sourceDigest`, removes
only that entry and returns null. It is idempotent and does not close the active
snapshot or delete the source. There is no automatic LRU, size quota, cross-process
cache lease or fully out-of-core query engine in this stage. The checksum detects
corruption, not intentional forgery by a process already able to write as the
same user. Use trusted application-owned cache paths and parent directories;
pathname checks do not provide descriptor-relative isolation from a hostile
same-user process racing ancestor-directory replacement. Windows ACL policy and
native Windows execution still require platform validation; the publication
conflict branch is regression-tested independently on the local platform.

The std-only local SHA-256 has known-vector tests for empty input, `abc`, a
multi-block input and one million `a` bytes, including incremental chunk sizes.
Cache regressions cover non-truncating corruption, truncated/old entries,
same-size/same-mtime source changes, write failures, private permissions,
symlink rejection, concurrent writers and source identity changes.

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

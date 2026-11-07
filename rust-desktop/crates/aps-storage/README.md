# aps-storage

Rust-desktop-owned SQLite metadata, independent of Compose databases. This crate
adds no filesystem artifact deletion, raw SQL, BLOB, full heap, credential, API
key, prompt, raw source-content, or AI-response persistence API. It is not an
encrypted database: approved metadata (including explicitly registered absolute
local source roots and source identifiers) is readable to the local database
owner. Callers must not put secrets/source bodies into bounded labels, provenance
strings or other legitimate metadata fields; syntax validation is not secret detection.

## Dependency evidence and version

Pinned `rusqlite = "=0.37.0"` with only `bundled` and `limits` features; no floating
Git dependency. Official tag evidence inspected before implementation:

- [v0.37.0 Cargo.toml](https://github.com/rusqlite/rusqlite/blob/v0.37.0/Cargo.toml):
  MIT, edition 2021; `bundled` selects bundled libsqlite3 and modern bindings.
- [v0.37.0 README](https://github.com/rusqlite/rusqlite/blob/v0.37.0/README.md):
  recommends bundled SQLite for applications controlling their own databases.
- [transaction API](https://github.com/rusqlite/rusqlite/blob/v0.37.0/src/transaction.rs)
  and [limits API](https://github.com/rusqlite/rusqlite/blob/v0.37.0/src/limits.rs)
  confirm transactional migration and `SQLITE_LIMIT_LENGTH` controls.

This tag does not declare `rust-version` in its manifest; no minimum compiler
version is invented. The workspace's Rust 1.99 toolchain and platform C compiler
must be checked by the main integration build. Rusqlite is MIT; bundled SQLite
is public domain. Cargo.lock and packaged third-party notices remain leader-owned.

## Open and migrations

```rust
let mut storage = Storage::open(app_data_dir.join(DATABASE_FILE_NAME))?;
```

The path must be explicitly absolute, named `aps-metadata.sqlite3`, with an
existing caller-owned parent directory. It is not discovered from the current
Compose app, and no Compose database import/upgrade is attempted. Final DB/WAL/SHM
symlinks are rejected. New Unix DB files use mode 0600; established owned DB
permissions are tightened before enabling WAL. Parent-directory capability/race
isolation remains the platform owner's responsibility, not a SQLite sandbox.

SQLite application ID is `0x41505352`. A nonempty database without this identity,
a foreign application ID or a newer `user_version` is rejected before journal
mode changes. Versions 0→1→2 migrate in one IMMEDIATE transaction. Rollback leaves
prior tables/data/user_version intact on migration failure. Table/column probes
reject incomplete schemas before commit; the public API exposes no SQL executor.

Connection policy: foreign keys ON, trusted_schema OFF, busy timeout 5 s, required
WAL, synchronous FULL, 1000-page automatic checkpoint, 8 MiB journal-size target,
secure_delete ON and a 1 MiB SQLite text/blob/encoded-row limit. Journal size is
not a hard WAL disk cap: active readers can delay truncation/checkpoint. There is
no custom checkpoint scheduler, disk quota, backup or recovery tooling yet.

## Artifacts and sessions

`put_artifact(&CaptureArtifact, created_unix_ms)` validates the complete contract
and stores a bounded canonical metadata document (128 KiB max), including digest,
producer/provenance, capability/completeness, clock and optional process identity.
Existing artifact IDs cannot be rebound to conflicting content/provenance;
identical reinsertion preserves the original creation timestamp. No artifact
bytes or raw source are stored. `artifact` and `list_artifacts` return typed
indexed columns plus bounded JSON metadata; rows are not full heap snapshots.

`create_session(&ProfilerSession, label, now)` atomically writes the session and
ordered artifact references; a missing reference rolls the entire insertion back.
`session(id)` reads a consistent metadata snapshot. `open_session(id, expected,
now)` transactionally updates recency and returns the same generation snapshot.
`list_sessions(page)` returns small `SessionSummary` rows, not all linked artifact
metadata. `advance_generation(id, expected, next)` is monotonic compare-and-swap.

`validate_binding(&QueryBinding { session_id, generation, source_digest })` checks
current persisted generation and that an immutable artifact digest belongs to the
session. Revalidate before publishing async query results: validation is not a
lease that prevents future generation changes, a filesystem content rehash or
permission to execute the query.

`delete_session_metadata(id, expected_generation)` only removes session/link
metadata. It does **not** delete artifact metadata, imported files, capture
folders or source workspace files. There is deliberately no generic artifact or
source-directory deletion API.

All list APIs cap pages at 100, use stable ordering and a read transaction for
count/row consistency. Artifact pages also cap metadata payload at 8 MiB (request
a smaller page on overflow). Sessions cap full artifact references at 10,000.
Stored integer, JSON and identifier validation rejects malformed rows.

## Source metadata

`put_source_workspace` / `list_source_workspaces` persist explicitly registered
local workspace ID/name/root metadata. `put_source_snapshot` /
`list_source_snapshots(workspace_id, page)` store immutable manifest digest,
counts, index version and manifest completeness, foreign-key bound to a workspace.
Snapshot metadata is not a global source-tree immutability claim or full content
cache. No file body, credential field or arbitrary JSON extension is accepted.
Repeated registration of the same canonical local root or unchanged manifest
is idempotent across app restarts; the first creation timestamp remains stable.
Rebinding an ID to a different root/name, manifest digest, file count, index
version or completeness fails. The GPUI source workspace reads only this bounded
metadata listing at startup, never auto-indexes or opens saved roots; selecting a
saved entry re-registers its path, verifies workspace ID and creates a fresh
manifest before displaying source. Metadata failures remain visible but do not
disable the explicitly selected in-memory read-only workspace.
GitHub/AOSP provider metadata, full source-index tables, analysis/finding tables,
retention policies and compatibility import remain later migration work.

## Evidence boundary

Six integration test functions are written for immutable provenance, transactional
session insertion, recency/generation binding, metadata-only deletion, foreign/newer
database refusal, rollback after a deliberately failed migration and source
metadata foreign keys. They have **not been executed** under the user's instruction
to finish the rewrite before comprehensive testing. This worker did not compile
or run Cargo; main owns compilation and final verification.

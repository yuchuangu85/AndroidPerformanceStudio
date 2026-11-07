# aps-source

Compose-parity Source Workspace domain for the Rust desktop app. The behavior
oracle is `desktop-viewer/source-workspace` (models, providers, cache, indexer,
indexed resolver); the Rust app consumes this crate through
`aps-app/src/source/service.rs`.

Only the local provider is reachable from the UI. There is no network call,
credential discovery, implicit home scan, source upload or full source copy
outside the Rust-owned content-addressed cache.

## Model

- `SourceWorkspaceId`, `SourceSnapshotId`, `ResolutionCandidateId` and
  `PerformanceEvidenceId` are SHA-256 backed newtypes with canonical
  `to_hex`/`from_hex` and `from_digest`.
- `SourceProviderConfig` models `Local { root }`, `GitHub { owner, repository,
  git_ref, credential_key }` and `Aosp { project, git_ref }`; remote configs are
  validated and persisted for compatibility but have no provider implementation.
- `SourceWorkspace` carries display name, provider config, active snapshot id,
  phase (`Registering`, `ResolvingRevision`, `BuildingManifest`, `Indexing`,
  `Ready`, `Partial`, `Failed`), progress, message and the AI-upload consent flag.
- `SourceSnapshot` carries the immutable revision, optional dirty-content digest,
  manifest hash, creation timestamp, index version and completeness.
- `SourceFile`/`SourceSymbol`/`SourceLocation`/`ResolutionCandidate` mirror the
  Compose shapes; `ResolutionConfidence` has `Exact`, `Probable` and `Weak` and
  `BuildIdentityMatch` lets callers cap unverified results.

`INDEX_VERSION` is retained as a legacy format marker; snapshots use the
Compose index version (creation Unix milliseconds).

## Local provider

`LocalSourceProvider` implements `SourceProvider`:

- `resolve_revision` runs `git -C <root> rev-parse HEAD` through
  `aps_platform::ProcessRunner` (no shell, bounded output/timeout). Missing Git
  or a non-repository root yields `unversioned`. A dirty working tree (or the
  `unversioned` commit) appends `-dirty-<digest>`, where the digest is SHA-256
  over sorted `relative:sha256(content)` lines joined by newline.
- `list_files` walks the root without following symlinks, skips the Compose
  directory names (`.git`, `.gradle`, `.idea`, `build`, `out`,
  `node_modules`), accepts only `kt, kts, java, xml, c, cc, cpp, cxx, h, hh,
  hpp`, rejects non-regular files and files above 5 MiB, and sorts by relative
  path. Content hashes are not precomputed.
- `read_file` requires a bounded plain relative path that stays under the root,
  a regular non-symlink file and the same 5 MiB limit.

`SourceProviderRegistry` maps kinds to providers; `SourceProviderRegistry::local()`
contains the only implementation shipped today.

## Content cache

`ContentAddressedSourceCache` stores bytes at `<root>/<hash[0..2]>/<hash[2..]>`.
Writes go through `aps_platform::atomic_write` after creating the parent
directory; reads reject missing/non-regular objects and verify the stored hash.
The cache is never an implicit upload source.

## Indexer

`StructuralSourceIndexer::index` is a faithful port of
`StructuralSourceIndexer.kt` for Kotlin, Java, XML resources and C/C++
declarations. It reproduces the Compose regular-expression semantics exactly
(including their leftmost-match and line-number behavior) and is intentionally a
heuristic, not a compiler parser: comments/string bodies are not skipped and
complex syntax may be under-indexed. `max_symbols` truncates the result.

## Repository and resolver

`SourceRepository` is the Compose repository/index-view port. It is implemented
by `InMemorySourceRepository` here and by `aps_storage::Storage` (SQLite) in
the storage crate.

`IndexedSourceResolver::resolve` reproduces `IndexedSourceResolver.kt`: type,
Android resource, managed symbol, symbolizer source path, native symbol and
Compose file/package-hash evidence; deterministic candidate ids of
`sha256(snapshot:path:line:evidence)`; resolved from repository lookups only -
never AI-generated paths or lines. Under `BuildIdentityMatch::Unverified`
(the desktop flow) `Exact` candidates are downgraded to `Probable` with the raw
reason `Build identity not verified`; `SourceFileLine` matches use the Kotlin
`String.hashCode` of every package prefix, so they match Compose exactly.

## Tests

`tests/local_source.rs` covers hashing/ids, the provider, the cache, all four
indexers, repository cascades, resolver confidence and path policy. Tests are
authored but not executed per the rewrite policy; the compile gate is
`cargo check -p aps-source --all-targets --offline`.

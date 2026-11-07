# aps-source

Phase 6 **local-only subset** of Source Workspace / Source Snapshot / Source
Resolution / Source Viewer contracts. Implementation is based on the repository
CONTEXT and ADRs 0001–0021. No AI, HTTP, Git subprocess, credential discovery,
implicit home scan, source upload, or full source copy is performed.

## API

```rust
let workspace = LocalSourceWorkspace::register(selected_root, "My project")?;
let snapshot = workspace.snapshot(SourceLimits::default())?;
let page = snapshot.files(0, 100)?;
let location = snapshot.location(&page.rows[0].relative_path, 1, 20)?;
let snippet = workspace.read_location(&snapshot, &location, SnippetLimits::default())?;
let candidates = snapshot.resolve(&ResolutionQuery {
    symbol: Some("example.work".into()), ..ResolutionQuery::default()
})?;
snapshot.validate_candidate(&candidates[0])?;
```

Registration only stores an explicitly selected canonical directory. It rejects
filesystem/home roots and symlink roots. Source filenames are restricted to
Kotlin/Java/XML/C/C++/Rust; hidden paths, known build/cache trees and conservative
secret/credential/key/token filename patterns are excluded. This can exclude
legitimate code (for example token parsers); it does not guarantee arbitrary
allowed source text contains no secrets. Source authorization/upload remains a
separate, unimplemented policy, never inferred from registration.

All relative paths reject traversal, absolute paths, backslashes, colon/ADS paths,
hidden components and unsupported extensions. Traversal rejects symlinks at every
component and checks canonical containment. Reads validate file-handle/path
identity before/after reading and publish only verified bytes. This is portable
std filesystem validation, **not a race-proof openat/no-follow OS sandbox** against
an attacker concurrently replacing directory components. Such workspaces require
OS capability handles/isolation before they can be considered hostile-input safe.

## Snapshot identity and availability

A Source Snapshot is the SHA-256 identity of the frozen, bounded manifest of
individually read/hashed files, coverage, index version and budgets. It is **not
an atomic Git-tree snapshot**: files are observed sequentially and no Git revision
or global dirty-tree identity is claimed. It is not a retained full-content cache.
Each `SourceLocation` binds workspace/snapshot/relative path/file hash/line range.
The viewer rehashes full bounded file bytes and rejects changes, disappearance,
rebound roots or wrong snapshots rather than silently navigating newer content.
If old bytes are gone, explicit snapshot creation and re-resolution are required.

Defaults: 5000 files, 1 MiB/file, 64 MiB total indexed source, 50,000 visited
entries, depth 32 and 50,000 symbol hints. Every budget has an implementation hard
maximum; coverage exposes omissions, unreadable entries and exhausted budgets.
Directory enumeration exceeding the remaining entry budget omits that directory
as a whole rather than choosing an arbitrary filesystem-order subset. File pages
are at most 512 entries. No full source bodies remain in a completed snapshot.

A conservative scoped `.gitignore` subset excludes simple globs and paths,
including nested files. Negations never widen access; unsupported escape/bracket
syntax or an unsafe/oversized ignore file excludes that directory with an explicit
unreadable-coverage count. Global Git excludes and `.git/info/exclude` are not read,
and full Git-ignore parity is not claimed. This narrow support avoids reading
arbitrary user/global configuration during a source registration.

## Index and resolution confidence

The index extracts bounded declaration/resource hints while skipping common
comments/string bodies. It is not a compiler parser: complex generics, language
corner cases, Rust lifetime/raw-string syntax, macros, bindings and overload
resolution are not fully covered. Structural completeness is always false.
Module identity is a directory heuristic (before `src/`), not evaluated Gradle,
CMake or Cargo configuration. Candidates carry index version/completeness and
private originating evidence; validation re-resolves that evidence and rejects
altered paths, lines, hashes, IDs or confidence claims.

Only explicit matching symbol/basename/module evidence produces candidates;
there is no AI path/line generation. All symbol matches are at most **Probable**,
filename-only matches **Weak**. There is no Exact confidence or automatic direct
navigation: runtime/build identity has not been verified. User-selected files may
still be opened in the read-only viewer. Raw code/snippet line endings are kept;
snippet bytes/lines are bounded, with truncation explicit. An initial line larger
than the snippet budget is rejected instead of returning a misleading empty view.

The GPUI source workspace can publish exactly one user-selected
`SourceCandidate` to the bounded AI request contract. The candidate is
revalidated against its workspace/snapshot/content hash before preparation; the
optional snippet is limited by `SnippetLimits` and the AI context budget. The
workspace never becomes an implicit upload scope, and resolving or opening files
alone does not authorize network transmission.

## Remaining migration work

GitHub/AOSP providers, immutable revision/content-addressed caches, Git
revision+dirty identity, build-evidence mapping, compiler-quality structural
indexes, SQLite workspace persistence, background cancellation, credential-store
integration, source-upload preflight and complete cross-provider analysis binding
remain unimplemented. These are not implied by the local subset. No source is
automatically uploaded or persisted as a full copy. Absolute roots are exposed
only for local UI and must not enter reports/prompts/telemetry.

Tests are written but not compiled or executed, following the user's instruction
to complete the rewrite before comprehensive testing. Main owns integration and
compilation of this crate.

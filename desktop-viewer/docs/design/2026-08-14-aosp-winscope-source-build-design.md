# AOSP Winscope source build design

Status: Proposed (2026-08-14)

Related decisions:

- [ADR 0032: Build Winscope as a native Compose workspace](../../../docs/adr/0032-build-winscope-as-a-native-compose-workspace.md)
- [ADR 0033: Package upstream Winscope as an optional browser viewer](../../../docs/adr/0033-package-upstream-winscope-as-an-optional-browser-viewer.md)

## Summary

Adopt a hybrid model:

> Treat the vendored AOSP Winscope TypeScript source as the source of truth, keep a verified prebuilt distribution as a cache, rebuild incrementally through Gradle when the source changes, and require CI to prove that source and packaged output agree.

Do not remove the prebuilt distribution and run `npm ci` on every build. The current repository evidence is approximately:

- packaged Winscope distribution: 45 MiB;
- production source excluding large test fixtures: 6 MiB;
- generated protobuf inputs: 37 MiB;
- local `node_modules`: 643 MiB;
- cold npm install and production build: approximately 30 seconds.

This design ensures that product fixes are made in reviewable TypeScript without forcing every Kotlin or Compose build to reinstall the upstream npm dependency graph.

## Current problem

The current flow is:

```text
External AOSP-WinScope checkout
        |
        v
Apply maintained patch series
        |
        v
Webpack production build
        |
        v
third_party/aosp-winscope/dist
        |
        v
Desktop application packaging
```

This creates several maintenance problems:

1. The repository retains patches and compiled assets, but not the source being maintained.
2. Generated JavaScript is unsuitable for direct editing, review, or focused tests.
3. The patch series grows with each product fix and becomes harder to rebase during upstream upgrades.
4. The manifest, content hashes, entry point, and resource closure can drift.
5. ADR 0033 deliberately excludes npm from ordinary Gradle builds, so changing this workflow requires a superseding decision rather than an implicit build-script change.

## Goals

1. Make TypeScript the only editable implementation of the packaged upstream viewer.
2. Rebuild the viewer automatically when its relevant source inputs change.
3. Keep unchanged desktop builds fast and independent of npm downloads.
4. Produce identical browser resources for macOS, Windows, and Linux packages.
5. Preserve the existing view-only, loopback-only, offline Winscope runtime contract.
6. Keep source provenance, dependencies, generated protobufs, WASM, fonts, licenses, and packaged assets verifiable.
7. Prevent ordinary builds from mutating tracked files.

## Non-goals

- Replacing the native Compose Winscope Workspace.
- Embedding a browser runtime in the desktop application.
- Letting upstream Winscope own ADB capture.
- Automatically following upstream `HEAD`.
- Importing the entire AOSP checkout or committing `node_modules`.
- Building or packaging historical hashed browser bundles.
- Introducing a generic web-application build framework for a single bundled viewer.

## Target architecture

```text
third_party/aosp-winscope/source
          |
          +-- TypeScript and Angular source
          +-- package.json and package-lock.json
          +-- Webpack and TypeScript configuration
          +-- generated protobuf build inputs
          |
          v
    prepareWinscopeUi
       /        \
      /          \
source matches   source changed
manifest         or output missing
    |                  |
verify and reuse       npm/webpack build
prebuilt dist          and stage closure
      \                /
       \              /
        v            v
 build/generated/winscope-ui
          |
          v
 prepareProfilerAppResources
          |
          v
 desktop run, check, and package tasks
```

The build graph always contains Winscope preparation, but the expensive npm and Webpack work runs only when the source fingerprint differs from the verified prebuilt manifest.

## Repository layout

```text
third_party/aosp-winscope/
|-- source/
|   |-- package.json
|   |-- package-lock.json
|   |-- tsconfig.prod.json
|   |-- webpack.config.common.js
|   |-- webpack.config.prod.js
|   |-- scripts/
|   |   `-- build-offline.mjs
|   |-- src/
|   |   |-- app/
|   |   |-- common/
|   |   |-- parsers/
|   |   |-- viewers/
|   |   `-- ...
|   `-- deps_build/
|       `-- protos/
|-- vendor/
|   |-- material-design-icons/
|   `-- trace-processor/
|       |-- engine_bundle.js
|       |-- trace_processor.wasm
|       `-- trace_processor_memory64.wasm
|-- dist/
|-- manifest.json
|-- upstream.json
|-- LICENSE-AOSP.txt
`-- README.md
```

The import excludes:

- the upstream Git metadata;
- `node_modules`;
- historical `dist/prod` output;
- `winscope_proxy.py`;
- unrelated large test fixtures;
- AOSP build outputs not required by the offline viewer.

Tests and fixtures needed by maintained product behavior are imported explicitly rather than copying the full 75 MiB upstream fixture directory.

## Source and provenance

### Upstream metadata

`upstream.json` records the imported source:

```json
{
  "schemaVersion": 1,
  "repository": "https://android.googlesource.com/platform/development",
  "commit": "f41a8085fa0166967dd5ece55dce0796fd079e93",
  "importedAt": "2026-08-14",
  "sourceTreeSha256": "...",
  "packageLockSha256": "...",
  "generatedProtosSha256": "..."
}
```

The fingerprint algorithm sorts repository-relative paths and hashes both each path and its content. It excludes `node_modules`, `dist`, caches, editor files, and operating-system metadata.

### Local modifications

Product changes are made directly under:

```text
third_party/aosp-winscope/source/src
```

Examples include:

```text
source/src/viewers/components/hierarchy_component.ts
source/src/viewers/common/abstract_hierarchy_viewer_presenter.ts
source/src/app/components/trace_view_component.ts
```

The current patch series is materialized into the imported source during migration. After the source and generated output are verified, the patch file is removed as an active build input. Git history records local changes, while `upstream.json` records their upstream base.

Compiled files under `dist` are never edited manually.

## Responsibility boundaries

### Gradle

Gradle owns orchestration only:

- declare source, lockfile, vendor, manifest, and output inputs;
- determine whether preparation is up to date;
- select verified prebuilt assets or generated assets;
- connect Winscope preparation to desktop resource and packaging tasks;
- invoke the pinned Node build when required.

Gradle does not parse or rewrite generated JavaScript.

### Cross-platform Node build script

`source/scripts/build-offline.mjs` owns the upstream production build:

- remove the temporary output directory;
- invoke Webpack using the checked-in configuration;
- copy pinned local WASM, fonts, icons, and images;
- run offline preparation;
- remove collection proxy code, source maps, coverage instrumentation, and obsolete bundles;
- emit only the assets reachable from the generated entry point.

It uses Node filesystem APIs instead of the upstream `rm`, `cp`, and POSIX environment syntax so that a source rebuild works on Windows as well as macOS and Linux.

### Build and sync scripts

The scripts have separate responsibilities:

- `build-aosp-winscope`: build and stage output into a caller-provided generated directory;
- `sync-aosp-winscope`: explicitly replace tracked `dist` with verified generated output and update the manifest;
- `verify-aosp-winscope`: perform read-only provenance, closure, checksum, license, and network-reference validation.

Ordinary builds never run the sync operation.

## Gradle task design

### `prepareWinscopeUi`

This is the single dependency used by desktop resource preparation.

Algorithm:

1. Calculate the relevant source-tree fingerprint.
2. Compare it with `manifest.json`.
3. If it matches, verify tracked `dist` and copy it to `build/generated/winscope-ui`.
4. If it differs or generated output is missing, validate the Node toolchain and run the source build.
5. Verify the generated resource closure before publishing it as a task output.
6. Never write to tracked files.

Task graph:

```text
:desktop-app:run
:desktop-app:check
:desktop-app:package*
        |
        v
prepareProfilerAppResources
        |
        v
prepareWinscopeUi
```

### `installWinscopeDependencies`

Inputs:

- `package.json`;
- `package-lock.json`;
- Node version;
- host operating system and architecture.

The task runs `npm ci` only when the lockfile, Node version, or installation marker changes. npm downloads use:

```text
~/.gradle/caches/android-performance-studio/npm
```

The installation marker records the lockfile hash and Node version. `node_modules` remains ignored and is never packaged.

### `buildWinscopeUi`

Inputs:

- production TypeScript and styles;
- Webpack and TypeScript configuration;
- package manifests;
- generated protobufs;
- pinned fonts and Trace Processor runtime artifacts;
- the offline build script.

Output:

```text
desktop-viewer/desktop-app/build/generated/winscope-ui
```

The task is skipped when the verified prebuilt distribution matches the current source fingerprint.

### `syncWinscopeUi`

Developers run:

```shell
./desktop-viewer/gradlew :desktop-app:syncWinscopeUi
```

The task:

1. forces a source build;
2. verifies generated output;
3. atomically replaces tracked `dist`;
4. updates the manifest;
5. verifies the new tracked closure;
6. leaves Git commit and push decisions to the caller.

### `verifyWinscopeUiReproducible`

Release CI builds the viewer twice from clean work directories and compares:

- the two generated manifests;
- every generated asset hash;
- generated output against tracked `dist`.

Any difference fails the build.

## Manifest schema

The source-aware manifest uses a new schema:

```json
{
  "schemaVersion": 2,
  "upstreamCommit": "f41a8085fa0166967dd5ece55dce0796fd079e93",
  "sourceTreeSha256": "...",
  "packageLockSha256": "...",
  "nodeVersion": "v24.15.0",
  "buildScriptSha256": "...",
  "generatedProtosSha256": "...",
  "vendor": {
    "materialIconsSha256": "...",
    "traceProcessorWasmSha256": "...",
    "engineBundleSha256": "..."
  },
  "assets": {
    "index.html": {
      "bytes": 1541,
      "sha256": "..."
    }
  },
  "licenses": [
    "LICENSE-AOSP.txt",
    "LICENSE-MATERIAL-DESIGN-ICONS.txt",
    "third-party-licenses.txt"
  ]
}
```

`patchSha256` is removed after the patch is materialized because the vendored source becomes the maintained implementation.

## Node toolchain

The initial toolchain remains pinned to Node `v24.15.0`.

Node can be resolved from:

1. an explicit `-Pwinscope.node=/path/to/node` override; or
2. a repository installer that downloads a platform artifact with a pinned SHA-256 checksum.

Failure behavior:

- If source and tracked output agree, a developer without Node can use the verified prebuilt distribution.
- If source has changed, a missing or mismatched Node executable fails early with an actionable installation command.
- Release builds reject an unpinned Node version.
- npm versions come from the pinned Node distribution and dependency versions come only from `package-lock.json`.

## Verification and CI

### Fast checks

Every pull request runs:

```text
verify-aosp-winscope
Gradle :desktop-app:check
TypeScript compilation
format and lint checks for changed Winscope source
```

### Source-changing checks

When source, build configuration, dependency metadata, generated protobufs, or vendor inputs change, CI runs:

```text
npm ci
focused upstream unit tests
Webpack production build
resource closure verification
source/output consistency verification
```

### Browser smoke test

A small fixed Winscope Evidence Package verifies:

1. automatic same-origin evidence loading;
2. trace parsing;
3. hierarchy node selection;
4. Proto Dump updates after selection;
5. horizontal hierarchy overflow, including `scrollWidth > clientWidth`;
6. tree interaction after changing `scrollLeft`;
7. optional video playback and overlay text;
8. Material icon rendering;
9. original Winscope archive export;
10. absence of third-party network requests;
11. absence of requests to ports 5544 and 9167;
12. token invalidation after session shutdown or expiry.

Cross-platform packaging continues to consume the same verified browser resource closure.

## Local development flow

Edit source:

```shell
vim third_party/aosp-winscope/source/src/viewers/components/hierarchy_component.ts
```

Run the application:

```shell
cd desktop-viewer
./gradlew :desktop-app:run
```

Gradle detects the source fingerprint change and rebuilds the browser viewer into the generated resources directory.

Before committing:

```shell
./gradlew :desktop-app:syncWinscopeUi
python3 ../scripts/verify-aosp-winscope.py
git add ../third_party/aosp-winscope
```

Disallowed workflows:

- editing `dist/js/app.*.js`;
- manually changing asset hashes;
- copying an external checkout's complete `dist/prod` directory;
- packaging `winscope_proxy.py`;
- upgrading dependencies outside the lockfile update workflow.

## Upstream upgrade flow

1. Select and record a new upstream commit.
2. Import the build-required source into a temporary directory.
3. Compare it with the maintained source tree.
4. Port product changes individually instead of applying an accumulated monolithic patch.
5. Run focused TypeScript unit tests.
6. Run the browser smoke suite.
7. Regenerate the distribution, manifest, and license inventory.
8. Review package-size and runtime-network changes.
9. Commit the upstream upgrade separately from unrelated product features.

The viewer does not automatically follow upstream `HEAD`.

## ADR impact

This design requires a new ADR that partially supersedes ADR 0033.

The following ADR 0033 decision changes:

> Do not run the AOSP npm build from ordinary Gradle builds.

Replacement:

> Include Winscope source preparation in the Gradle task graph. Reuse the verified prebuilt cache when its source fingerprint matches; automatically rebuild when relevant source inputs change.

The following ADR 0033 decisions remain unchanged:

- the Compose Winscope Workspace remains primary;
- the upstream browser viewer remains optional and view-only;
- the desktop application owns capture and ADB;
- the browser viewer uses a tokenized loopback server;
- `winscope_proxy.py` is neither packaged nor started;
- telemetry and third-party runtime dependencies remain prohibited;
- only the current entry-point resource closure is packaged.

## Migration plan

### Phase 1: Lock current behavior

- Add the browser smoke test.
- Record the current distribution, manifest, package size, and network baseline.
- Keep existing resource verification passing.

### Phase 2: Import source

- Import the pinned production source and build-required generated inputs.
- Materialize the existing patch series into that source.
- Generate the initial source-tree fingerprint.
- Rebuild and confirm equivalent runtime behavior.

### Phase 3: Connect Gradle

- Add source-aware Winscope preparation tasks.
- Add npm and Node caching.
- Change `prepareProfilerAppResources` to consume generated output.
- Verify macOS, Windows, and Linux paths.

### Phase 4: Change the source of truth

- Remove the patch file as an active build input.
- Reject manual compiled-asset edits in CI.
- Require source, manifest, and tracked distribution consistency.

### Phase 5: Cleanup

- Delete obsolete sync paths.
- Remove duplicated runtime artifacts.
- Update repository documentation and developer commands.

## Risks and mitigations

### Larger repository

The source and generated protobuf inputs add repository size, but they do not enter application installers. Import only production source and required test fixtures.

### npm supply-chain and installation cost

Pin Node and the lockfile, cache npm downloads, run installation only when the lock changes, and require release rebuilds in controlled CI.

### Cross-platform upstream scripts

Do not invoke POSIX-only `rm`, `cp`, `find`, or inline environment syntax. Keep the product build path in the cross-platform Node build script.

### Non-deterministic generated output

Build twice in clean directories and compare complete manifests. A release cannot consume output that is not reproducible.

### Upstream divergence

Keep product changes small, covered by focused tests, and separated from upgrade commits. The upstream base remains explicit in `upstream.json`.

### Emergency fallback

An explicit prebuilt fallback may be retained for local development, but release builds reject it when the source fingerprint differs from the manifest.

## Acceptance criteria

1. Editing Winscope TypeScript and running `:desktop-app:run` produces the updated viewer.
2. An unchanged build does not run npm installation or Webpack.
3. npm downloads are reused after `gradle clean`.
4. A fresh clone can use verified prebuilt assets when source and manifest agree.
5. Release CI rebuilds from source and proves equality with tracked assets.
6. macOS, Windows, and Linux packages contain the same viewer asset hashes.
7. Manual edits to generated JavaScript fail verification.
8. The upstream commit, source, dependencies, protobufs, WASM, fonts, licenses, and packaged assets are traceable.
9. Vendored source does not increase the installed application size.
10. Hierarchy, Proto Dump, video, icons, automatic evidence loading, and export behavior pass regression checks.

## Decision recommendation

Use the hybrid source-plus-verified-cache model. It fixes maintainability at the source level while avoiding the operational cost and fragility of an unconditional full npm build on every desktop invocation.

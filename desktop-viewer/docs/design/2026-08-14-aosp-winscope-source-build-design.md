# AOSP-WinScope submodule integration design

Status: Implemented (2026-08-26)

Related decisions:

- [ADR 0032: Build Winscope as a native Compose workspace](../../../docs/adr/0032-build-winscope-as-a-native-compose-workspace.md)
- [ADR 0034: Use AOSP-WinScope as the upstream viewer submodule](../../../docs/adr/0034-use-aosp-winscope-as-the-upstream-viewer-submodule.md)

## Summary

Android Performance Studio consumes the upstream browser viewer from the
[`AOSP-WinScope`](https://github.com/yuchuangu85/AOSP-WinScope) repository as a
Git submodule at `third_party/aosp-winscope`. The native Kotlin capture,
analysis, and Compose workspace remains at `desktop-viewer/winscope` as defined
by ADR 0032.

The desktop build consumes the submodule's `dist/prod` output directly. It does
not keep a second copied distribution or a partial TypeScript source snapshot.
Gradle builds missing or stale production output from the pinned submodule and
verifies the packaged result through the submodule's own build contract.

## Implemented repository layout

```text
third_party/
`-- aosp-winscope/               Git submodule: yuchuangu85/AOSP-WinScope
    |-- scripts/build.py         production and verification entry point
    |-- ...                      browser viewer source and build inputs
    `-- dist/prod/               production resources consumed by APS

desktop-viewer/
|-- winscope/                    APS-owned native Kotlin/Compose workspace
|   |-- winscope-core/           capture, storage, analysis, domain model
|   |-- winscope-app/            Compose workspace and loopback browser host
|   `-- winscope-test-fixtures/  sanitized integration fixtures
`-- desktop-app/                 builds, verifies, and packages browser resources
```

The old vendored bundle, APS-owned manifest, patch series, and synchronization
scripts at `third_party/aosp-winscope` are replaced by the submodule checkout.
The path remains, but its source, build inputs, provenance, licenses, and
production output are now owned by AOSP-WinScope.

## Source and version ownership

- The superproject pins an exact AOSP-WinScope submodule commit. Builds never
  follow the remote default branch implicitly.
- Browser-viewer changes are developed and tested in AOSP-WinScope, then adopted
  here by updating the submodule pointer.
- APS-specific native capture and desktop integration remain in the
  superproject under `desktop-viewer/winscope`.
- `node_modules` and other local caches remain untracked and are never packaged.
- The submodule owns browser build configuration, dependency locks, provenance,
  licenses, and verification of its `dist/prod` release output.

This boundary avoids maintaining generated JavaScript or a forked source
snapshot in two places while keeping every APS revision reproducible through
its recorded submodule commit.

## Build and resource flow

```text
git submodule update --init --recursive
                 |
                 v
third_party/aosp-winscope (pinned commit)
                 |
     :desktop-app:prepareWinscopeUi
          /               \
 output current       output missing or stale
       |                       |
       |        scripts/build.py production --json
       |                       |
       +-----------+-----------+
                 |
                 v
third_party/aosp-winscope/dist/prod
                 |
       copy into desktop resources
                 |
  :desktop-app:verifyPackagedWinscopeUi
                 |
       scripts/build.py verify --json
                 |
                 v
desktop application resources/winscope-ui
                 |
                 v
loopback-only browser viewer
```

Gradle does not synthesize a fallback viewer. `prepareWinscopeUi` checks that:

1. the submodule checkout is present rather than an empty gitlink directory;
2. the submodule source and related build inputs are current relative to
   `third_party/aosp-winscope/dist/prod`;
3. missing or stale output is rebuilt by running
   `scripts/build.py production --json` in the submodule; and
4. the resulting production tree is copied into application resources.

`verifyPackagedWinscopeUi` then invokes `scripts/build.py verify --json` and
fails when the packaged viewer does not satisfy the AOSP-WinScope verification
contract. A missing submodule fails with an actionable initialization message;
missing or stale `dist/prod` is an automatic rebuild condition rather than a
manual prerequisite. The desktop build does not download or select a different
revision.

## Runtime boundary

The optional browser integration keeps the existing product and security
contract:

- the native Compose Winscope workspace is the primary APS experience;
- APS owns ADB, capture, session storage, and evidence eligibility;
- the browser viewer is optional and view-only;
- APS serves viewer resources and the current evidence package from an
  application-owned, tokenized loopback server;
- sensitive evidence requires the existing explicit browser-handoff consent;
- `winscope_proxy.py` is not started by APS and the viewer is not allowed to
  establish a second capture path; and
- packaged runtime resources remain local, with no required third-party
  network service.

Using a submodule changes source and build ownership, not the capture, privacy,
or loopback security boundary.

## Developer workflow

Clone or restore the pinned viewer checkout:

```shell
git submodule update --init --recursive third_party/aosp-winscope
```

Prepare and verify the browser resources through Gradle:

```shell
./desktop-viewer/gradlew -p desktop-viewer \
  :desktop-app:prepareWinscopeUi \
  :desktop-app:verifyPackagedWinscopeUi \
  --no-daemon
```

The preparation task runs the pinned submodule's production build only when
relevant source or build inputs changed or its output is missing. Manual
execution of `scripts/build.py production --json` is optional for focused
AOSP-WinScope development, not required for a normal APS build.

When adopting a new viewer version:

1. select and review an AOSP-WinScope commit;
2. update the submodule checkout and superproject gitlink;
3. let `prepareWinscopeUi` rebuild and `verifyPackagedWinscopeUi` validate the
   production resources;
4. run Kotlin integration tests and desktop resource/package checks; and
5. review runtime-network, license, package-size, and browser smoke evidence.

Do not copy `dist/prod` elsewhere in the superproject, edit compiled bundles as
an APS change, or restore the removed vendored synchronization workflow.

## Verification requirements

- A fresh recursive checkout automatically produces `dist/prod` from the pinned
  submodule revision through `prepareWinscopeUi`.
- A desktop resource build fails when the submodule is missing, while missing
  or stale production output triggers `scripts/build.py production --json`.
- `verifyPackagedWinscopeUi` runs `scripts/build.py verify --json` against the
  packaged viewer and fails on verification errors.
- The packaged desktop application contains the verified `dist/prod` resource
  closure and no duplicate browser distribution.
- Kotlin tests cover viewer eligibility, tokenized evidence serving, traversal
  rejection, expiry, and resource lookup.
- Browser smoke coverage verifies evidence loading, trace parsing, optional
  media, required WASM/WebGL behavior, and absence of unintended external
  runtime requests.
- macOS, Windows, and Linux packaging use the same pinned submodule commit.

## Migration outcome

The migration is complete when `third_party/aosp-winscope` is recorded as the
AOSP-WinScope gitlink, the native `desktop-viewer/winscope` modules remain
unchanged in ownership, desktop resources are sourced only from the submodule's
`dist/prod`, and the obsolete vendored manifest, patches, and synchronization
workflow are absent.

# Use AOSP-WinScope as the upstream viewer submodule

Status: Accepted (2026-08-26)

Supersedes: [ADR 0033](0033-package-upstream-winscope-as-an-optional-browser-viewer.md)
for upstream viewer source and build ownership. ADR 0033's optional-viewer
product and security decisions remain in force.

## Context

ADR 0033 introduced the optional, system-browser Winscope path using a curated
copy of upstream production resources under `third_party/aosp-winscope`. A
later source-build proposal expanded that package toward a second APS-maintained
copy of the upstream TypeScript source, build inputs, manifests, and generated
assets.

Android Performance Studio and AOSP-WinScope have separate repository
ownership. Keeping an APS-owned source snapshot or compiled bundle duplicates
history, requires synchronization scripts, and allows the reviewed upstream
revision and packaged browser resources to diverge. The existing
`third_party/aosp-winscope` dependency boundary is the appropriate location for
an explicitly pinned upstream repository. The native Compose workspace at
`desktop-viewer/winscope`, governed by ADR 0032, is a separate APS-owned module
and does not move.

## Decision

- Replace the vendored package at `third_party/aosp-winscope` with the
  `https://github.com/yuchuangu85/AOSP-WinScope` Git submodule and pin it through
  the superproject gitlink.
- Keep the APS-owned Kotlin capture, analysis, test-fixture, Compose workspace,
  and loopback-host modules at `desktop-viewer/winscope`.
- Consume `third_party/aosp-winscope/dist/prod` directly when preparing desktop
  application resources. `:desktop-app:prepareWinscopeUi` validates the
  submodule checkout and automatically runs
  `scripts/build.py production --json` when relevant submodule inputs changed
  or production output is missing or stale. Missing submodule source fails the
  build rather than selecting implicit fallback assets.
- Make `:desktop-app:verifyPackagedWinscopeUi` invoke the submodule's
  `scripts/build.py verify --json` command so packaging uses the upstream
  repository's verification contract.
- Remove the old APS-owned manifest, patch series, copied bundle, and
  repository-level synchronization scripts. Do not retain another generated
  browser distribution elsewhere in the superproject.
- Build and test browser-viewer changes in AOSP-WinScope. Adopt them in APS by
  updating the reviewed submodule commit and rerunning desktop integration and
  packaging verification.
- Preserve ADR 0033's optional-viewer runtime boundary: the native Compose
  workspace remains primary; APS owns ADB and capture; the upstream viewer is
  view-only; evidence is exposed only by the tokenized loopback server;
  sensitive browser handoff still requires consent; and no proxy or external
  runtime dependency is introduced.

## Consequences

- A recursive clone, or an explicit `git submodule update --init --recursive`,
  is required before building the desktop viewer.
- The pinned gitlink makes the browser source revision reviewable and
  reproducible without duplicating its history in APS.
- Developers must initialize the submodule. Gradle produces missing or stale
  `dist/prod` automatically through the build contract of the pinned
  AOSP-WinScope revision.
- Browser source, lockfiles, generated assets, provenance, and license
  maintenance belong to AOSP-WinScope; native Kotlin integration and desktop
  packaging checks belong to APS.
- Updating the submodule is an explicit dependency upgrade and must include
  browser smoke, runtime-network, license, package-size, and cross-platform
  packaging review.
- ADR 0032 and the `desktop-viewer/winscope` native workspace remain unchanged.

## Rejected alternatives

- **Continue vendoring the compiled closure:** duplicates generated output and
  preserves drift between the declared upstream version and packaged viewer.
- **Vendor the full TypeScript source in APS:** creates two editable sources of
  truth and duplicates dependency/provenance maintenance.
- **Download AOSP-WinScope during Gradle execution:** makes a normal build
  network-dependent and allows the downloaded revision to escape Git review.
- **Place the submodule at `desktop-viewer/winscope`:** displaces the existing
  native workspace, conflates upstream and APS ownership, and contradicts ADR
  0032.
- **Package a second fallback bundle:** can hide an uninitialized submodule or
  stale production output, defeating build-time validation.

## Superseded ADR 0033 decisions

This ADR replaces ADR 0033's upstream source and packaging mechanics: the
pinned patch series, curated copied resource closure, APS-owned manifest, and
manual synchronization workflow.

ADR 0033's product and security rationale is retained: native Compose remains
primary, the browser remains optional and view-only, APS remains the sole
capture owner, and evidence is served only through the constrained loopback
handoff.

# Rewrite the desktop application on Electron without a JVM

Status: Accepted (2026-09-10)

## Context

The product desktop application is a Kotlin / Compose Multiplatform Desktop
workstation (`desktop-viewer/`): about 80,494 lines of main Kotlin across 16
feature and shared modules, packaged with Gradle/jpackage into six native
installers. It captures Android performance data over ADB and analyzes it with
external tools (`simpleperf`, the pinned `trace_processor_shell` v57.2) plus
in-process parsers (HPROF, simpleperf protobuf, ART trace, FrameStats,
batterystats text).

Two facts make an Electron rewrite attractive. First, the heaviest visualization
surfaces are already web applications — the bundled Perfetto UI and Firefox
Profiler — reached today through a JVM loopback HTTP server plus the system
browser. Second, the repository's own three-solutions plan selects React +
TypeScript + Vite for its Web UI path, so one web frontend can serve both the
desktop and web surfaces instead of a third implementation.

The options for the hard logical modules were: rewrite everything in
TypeScript; keep the Kotlin analysis core as a JVM sidecar and rewrite only the
UI; or start mixed and migrate the sidecar away later. The owner has decided
against any JVM dependency in the target product.

## Decision

Rewrite the desktop application on Electron with no JVM in the target product.

- **D1 — Incremental (strangler) migration.** The Electron app and the existing
  Compose app coexist while features migrate one at a time and are accepted
  against golden fixtures and manual comparison. The Compose app is frozen as a
  reference implementation (oracle). **No product releases during the rewrite.**
- **D2 — No JVM; TypeScript first, Rust only as a performance fallback.** All
  Kotlin logic is reimplemented in TypeScript/Node. Existing native tools
  (`adb`, `trace_processor_shell`) are reused. Each performance-critical module
  (HPROF/bitmap parsing, simpleperf protobuf and ART trace parsing, dominator /
  leak analysis, flame-graph layout, large-tree and million-sample queries) gets
  a baseline-calibrated performance gate; only modules that fail it are
  reimplemented in Rust via `napi-rs` or WASM, behind the same interface.
- **D3 — Renderer stack** is React 19 + TypeScript + Vite (`electron-vite`),
  Zustand for UI state, TanStack Virtual for large trees.
- **D4 — Process model and security.** `contextIsolation: true`,
  `sandbox: true`, `nodeIntegration: false`; narrow `contextBridge` API with
  validated IPC; heavy parsing in `utilityProcess`/`worker_threads`; credentials
  via Electron `safeStorage`; third-party web UIs sandboxed without Node access.
- **D5 — Packaging without publishing.** electron-builder covers
  DMG/PKG/MSI/EXE/DEB/RPM but is used only for local and CI smoke builds during
  the rewrite. `release.yml` is left untouched, `electron-updater` is not
  wired up, and signing/notarization is deferred until a later release phase.

See `docs/design/2026-09-10-electron-rewrite-plan.md` for the full plan, and
[issue #21](https://github.com/yuchuangu85/AndroidPerformanceStudio/issues/21)
for the tracked PRD and acceptance criteria.

## Consequences

- The target product drops the JVM runtime and the Kotlin/Compose desktop
  stack, matching the existing Web UI direction and allowing one shared
  frontend.
- Correctness and performance risk concentrates in HPROF / simpleperf /
  protobuf parsing, which are the modules most likely to need Rust. Introducing
  Rust adds per-platform native builds and prebuilt artifacts that must be
  integrated with electron-builder and signing, so it is deliberately a
  fallback rather than the default.
- The existing Compose application must remain buildable (as the oracle) for the
  duration of the rewrite and receives no releases; consumed data formats
  (Java Preferences settings, per-feature SQLite databases under
  `~/.android-performance-studio/`, capture archives) remain compatibility
  obligations for the Electron implementation.
- The Android device-side Debug Agent stays Kotlin/Android and is out of scope.
- This decision should be revisited if many modules fail the performance gates,
  or if the Web UI path (three-solutions plan, option C) proceeds and the
  frontend is factored for sharing.

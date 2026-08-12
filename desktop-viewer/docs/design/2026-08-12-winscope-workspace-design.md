# Winscope Workspace Design

## Outcome

Add a first-class Winscope workspace to Android Performance Studio. It captures or imports Android 15+ Winscope Perfetto evidence, maps it through the pinned Trace Processor `v57.2`, and presents native Compose viewers for WindowManager, SurfaceFlinger, transactions, transitions, input, IME, ProtoLog, ViewCapture, EventLog CUJs, screenshots, and screen recordings.

The workspace does not embed AOSP Winscope, JCEF, or WebView and does not parse legacy Android 14 protobuf traces.

## Product boundaries

- Desktop targets: Windows 11, macOS, and Linux.
- Device baseline: Android 15+; live collection is capability-driven and normally requires `userdebug` or `eng` root access.
- Entry points: live trace, state snapshot, raw `.perfetto-trace` / `.pftrace`, and ZIP evidence packages.
- One active inspection session at a time. Recent sessions can be reopened.
- Raw evidence is authoritative. Kotlin projections are disposable and rebuilt from Trace Processor queries.
- AI analysis, legacy pre-Perfetto Winscope protobufs, gzip, and bugreport imports are out of scope. Command-exported Perfetto traces are supported regardless of filename extension.

## Module shape

`desktop-viewer/winscope` is an isolated composite build with three modules:

```text
winscope/
├── winscope-core/       capture, import, storage, SQL projections, domain models
├── winscope-app/        Compose workspace, timeline, viewers, menus
└── test-fixtures/       sanitized Android 15+ trace and fixture documentation
```

The core reuses `platform-core` for ADB, process execution, errors, and capture artifacts, and `platform-perfetto` for trace configuration and the artifact-scoped Trace Processor context. The app reuses `ui-components` and is published to the desktop application as `com.androidperformancestudio.winscope:winscope-app`.

## Capture

Before starting, query `perfetto --query` and compare actual registered data sources with the requested set. Android SDK is a live-capture gate, not an import gate. The application never runs `adb root` automatically; an available root transition is a separate explicit user action.

### Data sources

| Evidence | Perfetto source | Default |
| --- | --- | --- |
| WindowManager | `android.windowmanager` | On |
| SurfaceFlinger layers | `android.surfaceflinger.layers` | On |
| SurfaceFlinger transactions | `android.surfaceflinger.transactions` | On |
| Shell transitions | `com.android.wm.shell.transition` | On |
| EventLog CUJs | `android.log`, `LID_EVENTS` | On |
| IME | `android.inputmethod` | On |
| ViewCapture | `android.viewcapture` | On |
| ProtoLog | `android.protolog` | On, `WARN+`, no stacks |
| Input | `android.input.inputevent` | Off |
| Screen recording | separate `adb screenrecord` artifact | Off |

Input `TRACE_MODE_TRACE_ALL`, screen recording, and ProtoLog stack traces are sensitive and require explicit per-capture selection. “Enable all” selects every source and enables complete ProtoLog settings after a warning.

### Presets and lifecycle

- Balanced: 64 MiB ring buffer, WM debug/frame, SF active with input/composition/buffers.
- Full detail: 500 MB ring buffer, WM verbose/transaction, SF active with extra/HWC/virtual-display details.
- Default duration: 10 seconds; valid range: 1–600 seconds; the user can stop early.
- Maximum pulled trace: 1 GB.
- Unique device-side filenames prevent an interrupted session from overwriting evidence.
- Reconnection detects an unclaimed recent trace and offers recovery. Recovery and deletion are never automatic.
- A partial result is retained when at least WindowManager or SurfaceFlinger core evidence is inspectable; every missing requested capability has a limitation.

State Snapshot uses WindowManager and SurfaceFlinger dump modes and captures a screenshot by default.

## Import and storage

Imports are detected by content rather than extension. Raw Android 15+ Perfetto traces and ZIPs containing supported trace, PNG, MP4, and metadata entries are accepted. Archive extraction rejects traversal, excessive entries, and decompression bombs.

Duplicate ZIP evidence uses deterministic Winscope precedence with warnings: trace over dump, MP4 over screenshot, and the largest duplicate Perfetto trace. Unsupported entries remain listed in the import report.

- Captures and ZIP extraction live under `~/.android-performance-studio/winscope-sessions/`.
- Direct raw-trace imports remain references to user-owned files and are not copied.
- Deleting a session deletes only application-managed files.
- SHA-256 identity detects moved or modified raw evidence.
- A ZIP export includes raw trace, optional media, capture capabilities/limitations, annotations, and a versioned manifest.

Input, screenshot, recording, or ProtoLog stack evidence marks the package sensitive. Export requires confirmation and does not claim to redact opaque raw evidence. Device serials remain redacted by the existing capture-artifact contract.

## Analysis boundary

All Android 15+ parsing uses the pinned native Trace Processor `v57.2`. Public Winscope modules/tables are preferred; intrinsic table names are confined to the versioned query adapter when no public view exists. Kotlin never parses raw Winscope protobuf packets.

Import compatibility is based on the tables and columns actually present after Trace Processor ingestion. A future Android version is not rejected solely by SDK metadata. Missing tables or fields become explicit capabilities or unrecorded properties.

Queries project only the visible working set:

1. source availability and trace bounds;
2. timeline row summaries;
3. entries near the shared cursor;
4. hierarchy and properties for the selected state;
5. paged log/table/search results.

The application does not load the complete trace model into JVM memory. A 500 MB trace is the performance verification baseline.

## Time and identity

Perfetto monotonic trace time is the common time domain. Cross-source alignment requires explicit trace timestamps, VSync IDs, or screen-recording metadata. Missing mappings leave views independently navigable and visibly unsynchronized; nearest-time guessing is forbidden.

Cross-source selection uses explicit layer IDs, window tokens, SurfaceControl relationships, and transition participants. Similar names, rectangles, or timestamps produce candidates only, never identity.

## Native workspace

Available data creates dynamic tabs:

```text
WindowManager | SurfaceFlinger | Transactions | Transitions | Input |
IME | ProtoLog | ViewCapture | Search
```

Requested-but-missing sources appear in a capability panel rather than empty tabs. Dump tabs receive a `Dump` suffix.

### Shared timeline

- compact and expanded modes;
- source filtering, cursor, pan, zoom, reset, and timestamp navigation;
- left/right stepping on the active row;
- forward/backward playback and speed selection;
- space and media-key playback controls;
- bookmarks, CUJ markers, and SQL-result overlay rows;
- synchronized screenshot/video frame where verified metadata exists.

### Hierarchy and properties

Hierarchy defaults to all nodes, with invisible nodes de-emphasized. It supports visible-only, changed-only, flat mode, literal search, optional regular expressions, and parent-chain preservation. Diff colors distinguish new, changed, and removed nodes.

Properties provide a curated summary and searchable complete tree. Values absent from trace evidence display as unrecorded rather than being synthesized as `false`, `0`, or empty. Explicit Trace Processor defaults can be revealed. Current/previous differences are highlighted.

### 2D and 3D

The 2D view overlays selected bounds in display coordinates. The 3D stack view is a specialized Compose Canvas projection of rectangular bounds and Z order, not a general 3D engine. It supports pan, zoom, reset, 0–45 degree rotation, spacing, gradient, opacity, and wireframe drawing modes, plus display/window selection.

### Table viewers and search

Transactions, transitions, Input, IME, and ProtoLog use paged tables with timestamp links and a detail pane. ProtoLog source locations open the configured Source Workspace when the trace provides a deterministic file and line; otherwise the location remains copyable.

Global search accepts one read-only `SELECT` or `WITH` PerfettoSQL statement. Mutating, attaching, and multi-statement input is rejected before execution. A `ts` result column creates a timeline overlay. Recent and saved queries remain local.

### Existing-tool links

“Open in Perfetto” sends the same raw trace and current timestamp to the existing Perfetto workspace once. The workspaces do not maintain live bidirectional state.

## Verification

- Unit tests: capture config, source capabilities, partial completeness, SQL validation, archive safety/precedence, time/identity mapping, hierarchy filtering/diff, 2D/3D projection, timeline/playback, and storage deletion boundaries.
- Trace Processor integration: sanitized Android 15+ trace in `winscope/test-fixtures/src/main/resources/winscope/` covering all non-sensitive sources.
- Sensitive paths: generated Input/media metadata and temporary ZIPs only; no real sensitive fixture is committed.
- UI contract tests: dynamic tabs, missing-source explanations, accessibility semantics, localization, and desktop navigation.
- Performance: generated 500 MB fixture opens without loading all evidence into JVM memory and long operations remain cancellable.
- Build gates: `checkAll`, desktop tests, lint, detekt, and distributable resource verification.

The currently connected Android 14 user device cannot validate live Android 15 collection. Fixture and fake-ADB coverage are blocking; physical Android 15 `userdebug/eng` smoke validation remains an explicit non-blocking gap.

# Rust + GPUI Kit migration execution ledger

Goal: implement **the entire** `desktop-viewer/docs/design/rust-gpui-kit-migration-plan.md`.
Branch: `rewrite/rust-gpui`, based on `889c055d`.
Status: implementation in progress. This is not a reduced-scope MVP completion claim.

## Execution constraints

- Keep Compose as a behavior oracle/fallback; do not remove it before every
  final switch-over gate is proven.
- Android Agents remain Kotlin/Java. Third-party tools are reused, not rewritten.
- The user's latest instruction defers **all test execution** until all rewrite
  implementation is complete. Earlier agent checks, if any, predate that instruction
  and are not final acceptance evidence. Compilation checks are not test runs.
- No commit, push, release publication, credentials migration, source upload or
  device mutation is implied by this task.
- A visible navigation destination is not proof of profiler implementation.

## Plan requirements and next evidence

1. **Baseline/contracts (phase 0)** — pending: immutable representative and large
   CPU fixtures, Compose summary/tree/flame/stack oracles, startup/RSS/interaction
   timing scripts and observations. Collect performance observations in final
   validation, without inventing numbers during implementation.
2. **Desktop shell (phase 1)** — in progress: pinned workspace/toolchain, GPUI
   window, menus, icon, Home, Settings, theme/language, picker, clipboard, quit;
   native and minimal packages on all targets remain acceptance gates.
3. **Platform/data (phase 2)** — in progress: bounded process runner, tool/ADB
   resolution, cancel/reap, device catalog, independent data paths. Pending:
   session/artifact digest/provenance, SQLite migrations, tool lifecycle adapters.
4. **CPU slice (phase 3)** — in progress: existing Rust protobuf engine reuse,
   parse-once session, prepared query paging. Pending: durable native sessions,
   all Overview/Top Functions/Call Tree/Flame Graph/Stack Chart interactions,
   viewport aggregation/spatial hit testing, capture, Firefox export and parity.
5. **Memory/Network (phase 4)** — pending: engine adapters, paginated UI,
   redaction, bounded authorized content. The Perfetto Trace Analyzer and
   capture slices are tracked under the 2026-11-07 parity entry below.
6. **Other profilers/Inspector (phase 5)** — pending: Frame, Startup, Battery,
   GPU, Benchmark and desktop View/Compose inspection host; retain Android Agents.
7. **AI/Source (phase 6)** — pending: provider/SSE/catalog/request/storage,
   permissions, immutable source identity/index, deterministic navigation.
8. **Protocol/export boundaries** — pending: bounded version/digest/session/
   generation request and response envelope, child isolation, cancellation,
   JSON/CSV/PNG/Firefox exports.
9. **Comprehensive validation** — deferred until implementation is complete:
   domain, same-input parity, GPUI headless, native GUI, packaged release;
   macOS/Windows/Linux, IME/CJK/accessibility, notices, bundled tools, signing,
   cancellation/failure recovery, representative/large-profile resource budgets.
10. **Final switch** — not eligible: preserve Compose until every gate in
    section 9 of the plan is satisfied with fresh, requirement-matched evidence.

Every incomplete item remains part of the active goal. Compilation alone does not
close the corresponding feature, performance, platform or release gate.

## Implementation checkpoint — 2026-11-06

This checkpoint supersedes the coarse "pending" implementation labels above,
**not** the unfulfilled acceptance gates. No tests or GUI acceptance were run.

### Connected initial read/import workflows

- `aps-app/src/source.rs`: explicitly selected local source directory, bounded
  manifest/symbol index, file pages, deterministic candidates, hash-checked
  read-only snippets with line numbers and independent line paging. Metadata
  lookup is manifest-wide, not restricted to the current 100-file page or the
  first 512 files. Refresh creates a new snapshot; Close invalidates pending
  picker/index/read publication. No remote source retrieval or uploads.
- `aps-app/src/inspector.rs`: native archive import, window switching, paged
  hierarchy, geometry canvas and properties. Effective ancestor visibility/alpha
  and local hiding prune paint/hit inputs. Bounds are filtered before the canvas
  budget; an eligible selected node is retained beyond the 4,000-node budget.
  Canvas selection moves the hierarchy page. Three panes scroll horizontally on
  narrow windows, and tall displays scale without clipping the lower screen.
  Close invalidates picker/import completions. These are code-level fixes, not
  visual/native interaction acceptance.
- `aps-layout-inspector`: digest and parser consume the same frozen compressed
  byte sequence, eliminating independent path-reopen identity binding. Tradeoff:
  up to 172 MiB additional compressed input memory during parsing; not a measured
  low-RSS claim. Screenshot content is verified but not displayed; live capture,
  detailed Compose/runtime properties and multiframe parity remain incomplete.
- `aps-method-recording` + `aps-app/src/method.rs`: bounded binary-first ART
  fixture-layout v2/v3/v4/v5 import, genuine ENTER/EXIT/UNROLL replay, duration
  statistics, thread pages, Top Methods, call tree, interval/event/diagnostic
  pages and selected stack preview. Primary and CPU clocks remain distinct;
  method events are not CPU samples. Conventional text-first `.trace`, recording
  transport and graphical zoomable timeline remain incomplete. Closing prevents
  result publication but does not cooperatively interrupt bounded parser work.
- Shell entities retain separate CPU, Source, Inspector, Method and per-profiler
  import state across destination changes. Navigation labels describe an initial
  connected slice, not complete feature parity.
- Previously connected Memory object/dominator/leak paging, Battery report,
  Benchmark baseline comparison and GPU verification remain in the workspace.

### Parallel backend work integrated at compilation level

- `aps-ai`: frozen request/one-shot approval, provider policy, asynchronous Chat
  Completions/SSE transport, timeout/cancellation, validated results and audit
  metadata storage. Not wired into application approval/results UI. No credential
  access or real provider request was performed. Keychain, live model refresh,
  Responses API, chat history and result-body persistence remain incomplete.
- `aps-storage`: fixed migration commit error conversion found during workspace
  compilation. Storage durability/runtime behavior is not accepted yet.
- Capture-controller planning was not delivered as an `aps-capture` crate in this
  checkpoint. Device mutation/capture remains unimplemented, not silently mocked.

### Fresh evidence (compilation and static checks only)

From `rust-desktop/`:

- `cargo check --workspace --all-targets --offline`: passed, including compilation
  of test sources; **no test binary executed**. Fixed a Benchmark fixture format
  string and explicitly qualified Rust test attributes to avoid GPUI macro
  recursion during test-target compilation.
- `cargo clippy --offline -p aps-app -p aps-source -p aps-layout-inspector
  --all-targets --no-deps`: completed with style warnings. Strict `-D warnings`
  is not clean (source walker argument API and existing CPU/Memory nested `if`s;
  dependency-inclusive strict checking also reports Startup/Battery/Benchmark).
- Targeted formatting and whitespace checks recorded at handoff. Runtime,
  screenshots, same-input Compose parity, device capture, platform builds,
  packaging/signing, memory/performance measurements remain unexecuted.

### Next implementation dependencies

1. Actual capture controller and explicit device/process-instance confirmation,
   with bounded owned remote paths and verified Simpleperf conversion contract.
2. AI approval/provider/results UI and credential-reference adapter; connect
   profiler evidence to deterministic source candidates without implicit upload.
3. Durable session/storage integration, remaining export/tool/live Inspector
   paths, conventional ART formats and full profiler interactions.
4. Complete the migration implementation matrix before running the deferred
   comprehensive tests, native GUI, parity, performance and release gates.

## Implementation checkpoint — continued 2026-11-06

The user reiterated the **complete** migration target. This checkpoint adds
implementation slices, not a declaration that any phase or final gate is closed.
The previous prohibition on test execution remains in force until implementation
is complete; compilation of test targets is not test execution.

- Added `aps-capture`: explicit read-only CPU plan, single-use exact
  serial/PID/boot-ID/start-ticks confirmation, recheck before mutation, fixed
  Simpleperf argv, bounded 64 MiB `adb exec-out cat` transfer, digest/provenance
  and exact local partial-file cleanup. No broad `pkill` or implicit device
  command. Remote Simpleperf can continue for its finite duration on host cancel;
  optional converter output can grow before post-run size rejection. Therefore
  the GPUI capture page remains **read-only planning**; `confirm`/`run` are not
  exposed to the user yet. Perfetto capture and packaged tool resolution remain
  open.
- Added a GPUI AI page with frozen request/evidence/source candidate preflight
  only. No profiler currently prepares a run, no credential adapter or exact
  serialized-payload approval UI exists, and no approval/network call is
  available. The `aps-ai` transport implementation is not product-enabled.
- Source registration/snapshot metadata now persists in a separate Rust SQLite
  database. Recent entries list metadata in bounded pages; selecting one
  explicitly re-registers, revalidates its workspace identity and creates a
  fresh snapshot. Old paths are never auto-indexed on startup. Database errors
  are visible without blocking local viewing. This early metadata-only slice was
  superseded by the Source Workspaces Compose-parity slice (2026-11-07), which
  also retains content-addressed source bodies in the Rust-owned local cache.
  This is not a durable CPU profile session implementation.
- CPU UI now offers a bounded current-query Call Tree/Top Functions CSV copy
  action and invalidates a pending picker on Close. CSV is not Firefox/PNG/JSON
  export or an all-pages export. Fixed the raw `perf.data` guidance to match the
  existing Kotlin Simpleperf `report-sample` converter rather than an unverified
  `report_sample.py` claim.
- Navigation exposes AI preflight and capture planning with **Not migrated**
  status, not operational labels. The other routes remain initial slices only.

Fresh local static evidence: `cargo check --locked --workspace --all-targets
--offline` passed after updating the lockfile for `aps-capture`; `cargo fmt
--all` and `git diff --check` completed. Test binaries, device commands,
provider requests, GUI acceptance, cross-platform packages and parity were
**not run**. Compilation emits two dead-code warnings for the not-yet-connected
AI `present`/`has_preflight` methods. Strict workspace Clippy remains open.

Next blocked-by-implementation gates: safe end-to-end capture cancellation and
converter output bounding; trusted credential reference and exact AI approval
surface; CPU snapshot retention/reconciliation and full session parity; complete
profiler interactions, exports and live Inspector; same-input and multi-platform
validation. Do not remove Compose or claim full migration on the basis of these
slices.

### Follow-up implementation after the checkpoint above

- Before the content-addressed snapshot slice below, CPU import wrote only
  immutable SHA-256/length/provenance metadata and a content-addressed session
  association to Rust-only SQLite. Recent CPU metadata was manually refreshed
  and reopened only after explicit file reselection and digest/length
  revalidation. Background query/CSV publication rechecked the session
  generation binding. File checks before/after parse were not an atomic
  filesystem snapshot, and matching bytes shared one association rather than a
  history of import events. A race with Close could leave a committed metadata
  row but could not publish an old UI result.
- Generic artifact pickers now participate in generation invalidation, so Close
  cannot be undone by an old picker result. Rust settings data-root overrides
  must be absolute; no relative metadata/capture path is accepted.
- Perfetto import searches a verified packaged `resources/perfetto-tools` first;
  only debug builds may fall back to the source-checkout tool. Release builds
  never assume a developer checkout. Tool packaging across macOS/Windows/Linux
  and runtime use are not yet validated.

Fresh static evidence after this follow-up: `cargo fmt --all --check`,
`cargo check --locked --workspace --all-targets --offline`, and
`git diff --check` passed. Test source compiled but was **not executed**;
AI-preflight dead-code warnings remain because no trusted profiler run is wired.

### CPU content-addressed snapshot slice — 2026-11-06

- CPU imports now copy the verified SIMPLEPERF protobuf bytes into a separate,
  Rust-owned `cpu-artifacts/<sha256>.simpleperf.pb` directory using bounded
  reads and `atomic_write`. The persisted file is rechecked for regular-file
  identity, byte length and SHA-256 before the import is considered durable.
- Recent CPU metadata now offers two explicit paths: open the saved snapshot
  without the original source file, or reselect a source and rehash it. Snapshot
  reopening validates the SQLite session/generation binding first, rejects a
  missing, symlinked, resized or digest-mismatched snapshot, and keeps the
  internal snapshot path out of the user-facing source label.
- SQLite remains metadata-only: source paths and profile bytes are not stored in
  `aps-metadata.sqlite3`. Snapshot retention/quota and orphan reconciliation
  remain open release concerns; this slice does not establish resource-budget,
  cross-engine parity or packaged acceptance.
- The existing shell AI routing gap was also closed for Method Recording and
  Layout Inspector with bounded, path-free evidence projections. The AI
  provider/credential and network acceptance gates remain incomplete.

Fresh static evidence for this slice: `cargo check --locked --offline -p
aps-app` and rustfmt checks for the four touched app files passed. Test sources
were added for source-less snapshot reopen and tamper rejection but were **not
executed** under the active migration instruction. The only remaining compile
warning in `aps-app` is the pre-existing, not-yet-connected AI `present` /
`has_preflight` path.

### CPU Firefox/Gecko export slice — 2026-11-06

- `aps-cpu::CpuSession::export_firefox_profile()` now emits a bounded AOSP Gecko
  v24 JSON profile compressed as gzip. It builds per-thread sample tables,
  frame/stack/string tables, converts Simpleperf nanoseconds to Gecko
  milliseconds, preserves unknown-symbol address identity, and applies the
  existing Kernel/Native/DEX/OAT/Off-CPU/JIT category rules.
- The native exporter uses stored DEFLATE blocks implemented with the standard
  library, caps the final gzip output at 64 MiB, and never writes a destination.
  `aps-app` adds an explicit save action with absolute-path and create-new
  publication semantics, so an existing file is never overwritten and partial
  output is removed on failure.
- This closes the implementation/export boundary for the CPU slice but not the
  acceptance boundary: browser import, Kotlin same-input round-trip parity,
  large-profile memory/latency measurements, and packaged release validation
  remain open.

Fresh static evidence for this slice: `cargo fmt --all`,
`cargo check --locked --workspace --all-targets --offline`, and
`git diff --check` passed. No tests, browser import, GUI interaction or
packaged export validation was executed.

### AI provider catalog preflight slice — 2026-11-06

- The existing `aps-ai` approval/transport/result lifecycle was already wired
  into GPUI; this slice adds the missing catalog boundary instead of creating a
  second send state machine. `AiWorkspace` now owns an injected
  `ProviderCatalog` and rejects unregistered provider/model combinations before
  creating `PreparedRun`.
- The default catalog contains only metadata for the explicit loopback provider
  `local` at `http://127.0.0.1:12345/v1`, model `local-model`, and the opaque
  `memory:manual` credential reference. No secret is stored in the catalog and
  no `/models` or provider request is performed. Endpoint and credential
  reference must also match the registered provider, preventing free-text
  endpoint substitution under a registered provider ID.
- Streaming capability is checked against the registered model descriptor. The
  existing one-shot credential, explicit network/source approval, cancellation,
  response validation and audit metadata paths remain unchanged.

Fresh static evidence for this slice: `cargo fmt --all` and
`cargo check --locked --workspace --all-targets --offline` passed. The only
remaining `aps-app` warning is the existing unused `present` /
`has_preflight` alternate preflight entry. No provider request, credential
lookup, test, GUI or packaged validation was executed.

### CPU remote capture lease slice — 2026-11-06

- Confirmed CPU capture now starts the fixed Simpleperf command through a
  generated, restricted shell wrapper only to obtain its exact remote PID. The
  host then polls `/proc/<pid>/cmdline` with a bounded deadline and verifies the
  command still contains both `simpleperf` and the generated run artifact path
  before allowing the artifact pull.
- Host cancellation sends `SIGINT` only to that exact PID and waits up to five
  seconds for the remote process to disappear. Stop failure or PID identity
  change enters `CaptureState::CancellationUnverified`; it cannot be rewritten
  as ordinary successful cancellation. Local cleanup remains exact-file-only,
  and no broad remote cleanup or `pkill` is introduced.
- The remote artifact is still retained for explicit recovery, remote filesystem
  quota and packaged Simpleperf resolution remain open, and real-device stop
  behavior has not been accepted without runtime evidence.

Fresh static evidence for this slice: `cargo fmt --all`,
`cargo check --locked --workspace --all-targets --offline`, and
`git diff --check` passed. No device command, test, GUI, or package validation
was executed.

### Perfetto bounded query cache slice — 2026-11-06

- `TraceSession` now retains verified `QueryPage` results in a bounded
  session-owned cache keyed by `QueryPreset + offset + limit`; the source digest
  is implicit in the owning session and each page retains the digest in its
  contract. The cache holds at most eight pages and 2 MiB of cell text.
- Source metadata changes clear the cache and fail closed before query or cache
  publication. Existing fixed SQL presets, CSV schema validation, bounded text
  decoding, tool checksum/version checks, ProcessRunner cancellation and
  generation guards remain unchanged.
- This removes repeated identical page queries but does not yet create a warm
  Trace Processor RPC context or persistent native query index. Cache misses
  still use the existing bounded one-shot CLI process; runtime trace parity and
  performance evidence remain open.

Fresh static evidence for this slice: `cargo fmt --all`,
`cargo check --locked --workspace --all-targets --offline`, and
`git diff --check` passed. No trace processor process, test, GUI or packaged
validation was executed.

### Layout Inspector host protocol slice — 2026-11-06

- `aps-layout-inspector` now exposes `InspectorProtocolSession`, a host-side
  `aps-protocol` consumer for one bounded `inspector.frame.open` request. It
  binds `ProfilerSessionId`, source digest and strictly increasing generation,
  validates exact package/frame/window/node fields, checks parent/depth ancestry,
  and returns only the first bounded hierarchy page.
- The protocol path enforces 64 windows, 100,000 total nodes, 200 returned
  hierarchy rows, bounded text/values/depth through `FrameLimits`, and explicit
  cancellation/stale-generation/error responses. Runtime values, semantics,
  screenshots and raw properties remain omitted from the projection.
- This advances the desktop protocol-host boundary without claiming live device
  capture: Android Agents remain Kotlin/Java, no ADB/socket is opened, and the
  session is not yet wired to a real transport or the GPUI Inspector entity.

Fresh static evidence for this slice: `cargo fmt --all`,
`cargo check --locked --workspace --all-targets --offline`, and
`git diff --check` passed. Protocol regression test sources were added but not
executed under the active full-rewrite validation instruction.

### Layout Inspector protocol-to-UI publication slice — 2026-11-06

- `InspectorWorkspace` now publishes the validated
  `InspectorProtocolFrame` through one generation-safe `publish_protocol_frame`
  path. Local archive import uses the same publication path, so the protocol
  host and archive fallback share window selection, hierarchy offset, hidden
  nodes, selected node, canvas and properties state instead of maintaining
  divergent UI state transitions.
- Stale frame generations are rejected, cancellation remains owned by the
  background import task, and the bounded archive/protocol projection still
  omits runtime values, semantics, screenshots and raw properties.
- This connects the host contract to the GPUI Inspector entity but does not claim
  live capture: Android Agent/socket transport, ADB lifecycle, screenshot
  rendering and native device acceptance remain open.

Fresh static evidence for this slice: `cargo fmt --all`,
`cargo check --locked --workspace --all-targets --offline`, and
`git diff --check` passed. No tests, device transport, GUI or package
validation was executed.

### Inspector workspace empty-state repair — 2026-11-06

- The Rust Inspector used to return immediately when no archive was loaded,
  leaving only Import archive and Close visible. Hierarchy, Canvas, Properties,
  and the local status strip now render for empty, loading, and failure states.
- Pane containers have explicit available-size and shrink constraints. Hierarchy
  and property rows scroll independently; the geometry canvas fits and centers
  within its measured viewport, using the same transform for drawing and hits.
- Archive import, generation checks, selection, window switching, and local
  hidden-node behavior retain their existing ownership. No sample nodes or
  synthetic findings are added. Live transport, screenshot decoding, Timeline,
  and Findings parity remain open rather than being implied by empty panels.
- Regression sources cover always-present panes and portrait/landscape/zero-size
  canvas constraints. Per the rewrite validation instruction, these tests were
  authored and compilation-checked, not executed. `cargo check --all-targets`
  and a local debug build passed. Target-window screenshot and native interaction
  acceptance were attempted but not established.

### Trace Analyzer offline workspace slice — 2026-11-06

- The Rust Trace route now renders a dedicated Trace Analyzer workspace instead
  of the generic artifact table: import/cancel/close controls, bounded capture
  placeholder, Processes/Threads/Slices/CPU Scheduling query tabs, source/query
  metadata, paging, and a scrollable result surface.
- It reuses the verified `aps-perfetto` `TraceSession` and fixed `QueryPreset`
  contract. No arbitrary SQL, PATH fallback, raw tool output, device capture,
  or synthetic trace metrics are introduced.
- The workspace remains offline-first. Sessions/bookmarks, Result/Query/Search
  detail tabs, diagnostic insight catalog, external Perfetto UI server, and
  ADB trace capture remain pending parity work.

Fresh static evidence: `cargo fmt --all`, `cargo check --locked -p aps-app
--all-targets --offline`, and `git diff --check` passed. No tests or native GUI
acceptance were executed.

### Compose Trace Analyzer parity slice — 2026-11-07

- Scope: the Rust Trace route now mirrors the Compose Perfetto Trace Analyzer
  end to end instead of reusing the generic import table: workspace sessions
  rail and bookmarks, severity/category insight rail with preset scoping and
  filters, four summary cards, Result/Query/Search detail tabs, slice search,
  timestamp-anchored Perfetto UI launch, raw-trace and session-package export,
  capture page, device/capability refresh, AI preflight context and the
  bilingual copy catalogs (`values/trace.json`, `values-zh/trace.json`).
- `aps-perfetto` now owns the Compose diagnostic surface: a warm
  `trace_processor_shell` session (`server unix --daemonize` + `query --remote`,
  explicit `TraceSessionMode::Warm/OneShotFallback` and Drop-time daemon kill),
  the full 35-query catalog and 8 presets ported byte-for-byte from the Kotlin
  oracle, finding thresholds (`findings.rs`), bounded strict CSV decoding and
  the escaped `TraceSliceSearch`. `QueryPreset`/`QueryPage` stay unchanged for
  the GPU inspector; invalid traces still fail closed and no arbitrary SQL,
  PATH fallback, download or cloud connection exists.
- Capture ports `PerfettoConfigTextBuilder`, capability detection, Compose
  probe/template gating and the bounded adb push/start/stop/pull session
  (64 MiB `exec-out cat` bound, generation/cancel guards); the capture page
  renders the three Compose panels. Live hardware capture is not exercised in
  this slice and is documented as a device-dependent gap.
- Sessions/bookmarks use bounded, atomic JSON indexes with managed trace
  copies; session export writes `session.json` (including real device
  model/SDK from capture), `trace.pftrace` and `metadata.properties`; the
  external viewer binds loopback only and opens the system browser; AI context
  is numeric-only, row-capped and SHA-256-bound before the existing approval
  dialog, never including thread names, SQL or paths.
- Verification: `cargo test -p aps-perfetto --offline` = 34 passed (35-query
  parity asserts read the Kotlin oracle; pinned-tool integration runs the whole
  catalog against `synthetic-empty.pftrace`), `cargo test -p aps-app --offline`
  = 222 passed, `cargo test --workspace --offline --no-fail-fast` = 526
  passed/0 failed, `cargo check --workspace --all-targets --offline` and
  `rustfmt` clean. Native GUI checks on a synthetic empty trace covered import,
  preset execution, summary cards, insight filters, Result/Query/Search detail
  tabs, slice search, sessions/bookmark rows, capture panel, AI frozen payload
  review, wide and narrow layouts, and the English/Chinese switch (screenshots
  under `build/trace-analyzer/`).
- Remaining gaps: live ADB capture and a real device trace are unexercised;
  GUI cancel can leave a remote perfetto pid until its own duration; traces
  over 64 MiB fail closed; sessions/bookmarks are single-process locked; the
  viewer fallback page explains the loopback URL instead of embedding a cloud
  iframe; the device selector+refresh only appear while capture is open because
  the shell title bar already exposes the shared target selector.
### Compose Frame Analyzer parity slice — 2026-11-07

- Scope: the Rust Frame route now renders a dedicated Frame Analyzer workspace
  instead of the generic import table. It mirrors the Compose frame-profiler
  app: device/process target (shared title-bar host), Import Perfetto
  FrameTimeline, Associate Perfetto trace, Capture FrameTimeline, Open trace,
  Start/Stop online capture, AI preflight, status bar, filter row, summary
  cards with sparklines, timeline canvas (zoom/pan/hover/cluster marks/frame
  selection), frame detail with evidence copy/stage breakdown/layout handoff,
  jank clusters with sparklines, problem frames table, and File-menu gfxinfo
  import + CSV/JSON export.
- `aps-frame` gained Compose-faithful modules while keeping the legacy
  FrameSession API for the old import page: model.rs (FrameSource,
  ExpectedDurationSource, JankType, FrameStages, FrameSample,
  FrameCaptureSession, FrameSourceCapabilities), gfxinfo.rs (full
  GfxInfoFrameStatsParser port incl. warnings/unknown columns/deadline
  provenance), analysis.rs (FrameJankAnalyzer thresholds/severity/clusters,
  FrameAttributionAnalyzer, FrameTimelineClassification,
  FrameJankStatsCorrelator, FrameViewFilter/filter_analysis, format_millis)
  and frame_timeline.rs (Expected/Actual FrameTimeline row mapping and
  capability subtraction). Legacy root names stay; the four colliding Compose
  types live at aps_frame::analysis/model. 60 aps-frame tests pass, including
  Kotlin-test ports and a 35-row pinned-tool FrameTimeline check in the app.
- Runtime/UI: crates/aps-app/src/frame.rs owns the controller state machine
  (async device/process discovery, bounded imports, online capture polling,
  FrameTimeline capture, association, export, AI, viewer, layout handoff);
  frame/page.rs + frame/model.rs render the Compose screen and timeline with
  runtime-gated actions; values{,-zh}/frame.json carry all 129 Compose keys
  (named placeholders) plus 12 Rust-only display keys. The shell routes
  Destination::Frame to the workspace, keeps it in the title-bar target loop,
  invalidates AI on change, and adds File > Import gfxinfo FrameStats /
  Export CSV / Export JSON.
- Capture (frame/capture.rs): adb device/process discovery, FrameMetrics Agent
  socket protocol preferred with bounded framed batches/JankStats fields,
  gfxinfo polling fallback with Compose dedupe/warning rules, and bounded
  Android 12+ FrameTimeline capture with fixed commands, API gating and device
  temp cleanup. Support (frame/{sessions,export,ai,artifact,perfetto}.rs):
  bounded atomic JSON session store (Compose uses SQLite internally),
  Compose-exact CSV and JSON schemaVersion 3 exporters, numeric-only
  FrameAiContext bound into the existing approval dialog, capture-artifact
  metadata, and Perfetto FrameTimeline import over the pinned
  trace_processor via a new fixed-SQL aps-perfetto::frame_timeline query.
- Verification: `cargo test -p aps-frame --offline` = 60 passed;
  `cargo test -p aps-app --offline` = 276 passed;
  `cargo test --workspace --offline --no-fail-fast` = 634 passed / 0 failed;
  `cargo check --workspace --all-targets --offline` and `cargo fmt --all
  --check` clean. Native run with a synthetic 60-frame gfxinfo fixture: import,
  13 deadline misses across 9 clusters, timeline zoom/selection/hover,
  Deadline-misses filter, AI frozen payload review (summary counters + one
  selected frame, no states/paths), CSV export (61 lines, Compose columns) and
  JSON export (schemaVersion 3: 16 summary, 37 frame, 10 cluster, 18
  attribution keys), English + Chinese, plus the empty state; screenshots under
  build/frame-analyzer/.
- Gaps: no hardware was available, so live gfxinfo/agent-socket/FrameTimeline
  capture is unit-tested only; session persistence is bounded JSON rather than
  Compose's SQLite schema (internal format only); artifact missing-capability
  limitations are folded into provenance warnings; exported JSON maps are
  sorted (same keys/values, different insertion order); the 980px narrow
  layout truncates the fixed three-column body below Compose's 1280px minimum
  window; gpui has no floating tooltip, so problem-frame evidence is available
  through the detail copy action; the old generic Frame import page code is
  now unreachable and awaits cleanup.
### Source tree-sitter highlighting (Phase 1) — 2026-11-07

- The read-only Source viewer now uses tree-sitter for syntax highlighting
  (Kotlin, Java, XML, C, C++) instead of relying only on the line tokenizer.
  `tree-sitter 0.25` with `tree-sitter-java`, `tree-sitter-kotlin-ng`,
  `tree-sitter-xml`, `tree-sitter-c` and `tree-sitter-cpp`; the same One Dark
  palette and byte-offset token model are reused, so selection, search,
  virtualization and copy behavior are unchanged.
- Parsing runs on the background executor after the regex tokenizer paints the
  file, then replaces the span set when ready (generation-guarded, so switching
  files cannot apply a stale tree). The syntax tree is dropped inside the task:
  no AST cache and no project-wide parse state is retained; only token spans
  live in the viewer. Files above 2 MiB or languages without a grammar keep
  the existing line tokenizer.
- The tree pass covers cases the regex lexer could not: multi-line Java text
  blocks, nested Kotlin block comments, multi-line annotation/comment spans,
  tree-validated strings/numbers/keywords and XML tag/attribute/value
  structure. No index, resolver, jump, LSP or memory-index change is part of
  this phase; semantic navigation remains the existing heuristic symbol path.
- Verification: 12 `source::code_viewer` tests pass, including Java text
  blocks, nested Kotlin block comments, XML/C tokens, per-line token bounds and
  oversized fallback; `cargo test --workspace --offline --no-fail-fast` = 654
  passed/0 failed and `cargo fmt --all --check` is clean. Native open of the
  Kotlin fixture renders the syntax colors (screenshot under
  build/source-workspace/tree-sitter-highlighting.png).
- Measured on this debug build: a 100 KiB generated Java file highlights in
  ~45 ms, 500 KiB in ~218 ms, 1 MiB in ~445 ms (off the UI thread; the regex
  pass paints immediately). Resident memory is limited to the token lines and
  the transient tree is bounded by the 2 MiB parse cap; no per-file or
  per-project tree is kept.
### Source bounded index and streaming indexing (Phase 2) — 2026-11-07

- Indexing no longer accumulates every symbol in RAM. `service::refresh`
  resolves the manifest, opens the snapshot, then writes file/symbol batches
  (64 files or 20 000 symbols) through `Storage::begin_snapshot`,
  `append_files` and `append_symbols`; progress is still published between
  batches. The old one-shot `save_snapshot` API remains for bounded callers.
  Multi-line Kotlin/Java parameter lists are normalized to the storage
  signature contract instead of failing the whole workspace.
- The 1M-symbol indexing ceiling no longer applies to the streaming path.
  Real AOSP_Frameworks smoke on this host: 51 438 files and 1 566 133 symbols
  indexed in ~303 s (debug); the indexer process stayed at ~79 MB RSS, and the
  resulting metadata DB is ~1.1 GB. Before this change the same tree failed
  with `source snapshot exceeds the supported symbol bound`.
- Navigation no longer loads whole `files`/`symbols` vectors. The
  `SourceRepository` gained bounded lookups (`file`, `files_named`,
  `files_with_suffix`, `symbols_qualified`, `symbols_with_short_name`,
  `symbols_in_file`) with in-memory defaults; SQLite overrides them with
  indexed SQL and the resolver uses them. Candidate results are capped at
  256 per lookup. Schema v4 only adds `source_symbols_file` and
  `source_symbols_kind_name` indexes (no table rewrite), so existing snapshots
  remain valid.
- Unchanged trees reuse their content-addressed snapshot instead of rewriting
  it: AOSP refresh became ~14 s (manifest scan + counts) instead of a full
  re-index. Mid-index failures delete the incomplete snapshot and keep the
  previous active snapshot.
- Method/native short-name lookups use persisted `dot_name`/`native_name`
  columns (schema v5) with `(snapshot_id, dot_name)` and
  `(snapshot_id, native_name)` indexes. The v5 migration adds the columns and
  backfills every existing symbol row before building the indexes; no re-index
  is required. AOSP 1.5M-symbol snapshot (debug): managed method lookup with 156
  candidates dropped from ~639 ms to ~5.8 ms. Qualified type lookup stays
  bounded (1 candidate; the first query after opening the large metadata DB is a
  cold page-cache read, later queries are fast), file+package-hash ~17 ms, and
  process RSS moved from ~45 MB to ~48 MB.
- The legacy full-snapshot read/save APIs no longer enforce the 100k-file or
  1M-symbol compatibility caps (`save_snapshot`, `files`, `symbols`). They
  still materialize complete vectors, so large snapshots should use the
  bounded lookups; navigation, indexing and the file tree no longer depend on
  the capped reads. Removing the short-name scan and caps grew the AOSP
  metadata DB from ~1.1 GB to ~1.5 GB.
- Verification: `cargo test --workspace --offline --no-fail-fast` = 657
  passed/0 failed, `cargo check --workspace --all-targets` and
  `cargo fmt --all --check` clean; the storage test now compares SQLite
  lookups and resolver candidates against the in-memory repository, and the
  ignored `manual_aosp_streaming_smoke` records the large-tree evidence.

### Source semantic navigation (Phase 3) — 2026-11-08

- Settings > Configuration now has an opt-in **Semantic navigation (LSP)**
  switch. It is off by default. Turning it on shows an inline warning that a
  local language server process will start and may use hundreds of MB; only
  explicit **Enable** persists `sourceLspEnabled=true` (settings format v4),
  **Cancel** leaves it disabled. Older settings files load with it disabled.
- When enabled, Cmd/Ctrl-click (or Ctrl-click) an identifier in the source
  viewer to request `textDocument/definition`; Shift+Cmd/Ctrl-click requests
  `textDocument/references` and shows a bounded usage list (200 entries) above
  the viewer. Clicking a usage opens it; `×` closes the list. The modifier
  hint is shown in the source pane only while the feature is enabled.
- Servers are local-only: the client speaks JSON-RPC over stdio, keeps one
  session per workspace/language, sends the open document with `didOpen`, and
  waits with bounded timeouts. No source text leaves the machine and no
  server is bundled. Discovery: `APS_JDTLS` or `jdtls` on PATH for Java,
  `APS_KOTLIN_LSP` or `kotlin-lsp` for Kotlin, `APS_CLANGD` or `clangd` for
  C/C++; extra arguments via `APS_*_ARGS`. Missing servers produce the
  localized configuration hint instead of silently falling back.
- The LSP client handles canonical paths returned by servers (for example
  /private/tmp versus /tmp), UTF-16 positions, Location and LocationLink
  results, and kills the server when the feature is disabled or the app
  exits. Tree-sitter remains the highlighter; LSP is used only for semantic
  definition/reference requests.
- Verification: unit tests cover identifier/UTF-16/URI conversion, a scripted
  stdio server for initialize/definition/references, and an ignored clangd
  smoke test that resolves a definition; the settings warning and persistence
  were verified in the native app. Java/Kotlin servers are environment
  installations, not part of this repository. Final gate: 671 passed/0 failed,
  `cargo check --workspace --all-targets` and `cargo fmt --all --check` clean.
### Network digest-bound minimized page cache slice — 2026-11-06

- `HarSession::open` now computes and retains the selected HAR's SHA-256
  `SourceDigest`. `HarSummary` and every `EntryPage` expose that digest so
  minimized network evidence remains bound to the exact imported bytes.
- Entry pages use a session-owned bounded cache of at most eight pages and
  512 KiB of minimized cell text. Cache hits still recheck file metadata and
  SHA-256; metadata or digest changes clear the cache and fail closed. Raw URLs,
  headers, cookies, bodies and parser errors remain excluded from all cached
  values.
- This removes repeated identical page scans for the common pagination path and
  closes the same-stamp source replacement gap for publication. It is not yet a
  persisted content-addressed HAR index or Kotlin full-fidelity NetworkAnalyzer
  parity implementation.

Fresh static evidence for this slice: `cargo fmt --all`,
`cargo check --locked --workspace --all-targets --offline`, and
`git diff --check` passed. No tests, HAR runtime import, GUI or package
validation was executed.

### Memory bounded query response cache slice — 2026-11-06

- `MemorySession` now caches only validated bounded payloads for the existing
  `histogram`, `instances`, `detail`, `dominators` and `findings` operations.
  Keys are the exact encoded operation/argument values, so heap, page, filter
  and object identity remain part of the lookup contract.
- The cache is capped at eight entries and 2 MiB of encoded response payload;
  worker failure, cancellation, timeout and close clear it. No Heap/Engine or
  raw HPROF graph enters the UI process or the cache.
- Cache misses still perform the real worker query, and histogram/findings may
  still recompute full engine views inside the child. Persistent query indexes,
  child RSS quotas, same-input parity and runtime stress evidence remain open.

### Memory offline workspace reconstruction — 2026-11-07

- The GPUI Memory page now uses snapshot tabs and an evidence-bound analysis
  workspace instead of the old generic placeholder. Class search/sort is
  evaluated across the complete histogram in the isolated worker before paging;
  the resulting summary and heap segments retain whole-snapshot meaning.
- Class rows drill down to instances and object details. The inspector projects
  bounded fields, GC-root paths, inbound references and truncation notices.
  Dominator rows and four heuristic finding categories remain navigable. The
  compare mode computes bounded, class-level count/shallow/retained deltas from
  two independently loaded HPROF sessions; it never compares object identity.
- Unsupported live timeline/GC markers, native heap, Bitmap Dump, sampled ART,
  LeakCanary and unavailable HPROF metrics remain explicit gaps rather than
  simulated evidence. Heap/scope/regex/preset filters, pin/forward history,
  mapping import, auxiliary artifact adapters, full screenshot parity and
  packaged GUI acceptance are still open.

Static evidence: `cargo fmt --all -- --check`,
`cargo check --locked --workspace --all-targets --offline`, and
`git diff --check` passed after integration. Tests and native GUI interaction
were not executed under the rewrite validation policy.

Fresh static evidence for the Memory slice: `cargo fmt --all`,
`cargo check --locked --workspace --all-targets --offline`, and
`git diff --check` passed. No HPROF worker, test, GUI or package validation was
executed.

### Memory Profiler Compose-parity workspace — 2026-11-07

- Replaced the generic Memory import table with a typed GPUI workspace that
  follows the Compose Memory Profiler page: a command toolbar (Import HPROF,
  capture actions, Overview/Classes/Dominators/Diff tabs, Bitmaps/Native
  Heap/ART Samples/Leak Analysis entries, Mapping, AI analysis), snapshot tabs,
  the evidence/warnings strip, and a task/status bar.
- The Classes view now mirrors `MemoryInvestigationWorkspace` +
  `MemoryProfilerClassListView`: the primary filter bar (heap, class scope,
  leak filter, arrange-by, class search, match case, regex), eight KPI cards
  (Leaks, Duplicate Bitmaps, Classes, Count, Native Size, Shallow, Retained
  Native Size, Retained), the heap-distribution bar, a resizable classifier
  tree table (heap/package grouping, lazy expansion, sortable Instances /
  Native Size / Shallow / Retained / Heap columns, leak and duplicate-bitmap
  tints) and the instance list with 500-row bounded paging. Selecting instances
  opens the object inspector inline: Fields / References tabs, sorted rows,
  object drill-in with back/forward history, pin/unpin, copy object id, string
  preview, truncation notices, nearest-GC-root-only filtering and 200-element
  array paging.
- Dominators follows `MemoryDominatorWorkspace`: whole-snapshot KPI cards,
  heap distribution, a 190 px filters pane (search, match case, regex), a
  lazily expanded dominator tree with child pages and leaf/unknown totals, and
  the 330 px object inspector. Diff adds whole-snapshot KPI/distribution above
  the class-level A/B comparison table (Class, A/B counts, deltas, A/B retained,
  Δ retained) with signed deltas and no cross-dump object identity. Leak
  Analysis presents the HPROF heuristic findings (leak suspects, Activity /
  Fragment candidates, lifecycle objects) and marks LeakCanary agent/live data
  explicitly unavailable rather than simulated. Bitmaps, Native Heap and ART
  Samples use Compose-shaped pages with empty/unavailable states while their
  Rust artifact adapters and live capture paths are not implemented.
- New typed domain in `aps-memory`: `model.rs` (snapshot/metadata/class stats,
  findings, instance/detail/dominator pages), `presenter.rs` (Compose
  presenter parity: heap and package grouping, scope/leak/search filters,
  summary null-vs-zero rules, duplicate-bitmap identity, classifier sorting and
  dashboard ordering) and a dependency-free bounded regex subset. The worker
  gained one bounded `snapshot` read (metadata + all-heap histogram + per-heap
  histograms + first 200 rows of each finding kind, 16 MiB budget with
  per-heap fallback) plus typed 500-row instance/dominator pages and 200-row
  array/object detail pages. Object ids are handled as unsigned 64-bit values;
  missing metrics stay unavailable.
- Verification: `rustfmt` on the changed files, `cargo check --locked
  --workspace --all-targets --offline`, `cargo test -p aps-memory` (38
  domain tests), `cargo test -p aps-app memory::` (15
  tests) and `git diff --check` passed. Native window screenshots with a real
  8-byte-id HPROF fixture (built from the engine test fixture shape) covered
  Classes + expanded tree + instance list + object inspector, Overview,
  Dominators, Diff, Leak Analysis, Bitmaps, Native Heap and ART Samples under
  `build/memory-profiler/` (parity spec in
  `build/memory-profiler/parity-spec.md`).
- Remaining gaps: live device capture (Dump Heap / Capture Native Heap / Record
  ART Samples / Dump Bitmaps / Inject Agent) and mapping import are visibly
  disabled, not simulated; LeakCanary agent and live monitoring are not
  implemented; Native Heap / ART Samples / Bitmap artifact analysis adapters
  and bitmap capture sessions do not exist in Rust; finding tables are bounded
  to the worker's first 200 rows (totals exact); dominator child pages beyond
  the first 500 rows have no paging control; the comparison UI needs two open
  snapshots for data (verified in the unavailable state); full screenshot
  pixel comparison, packaged release and device acceptance remain open.


### Frame offline workspace reconstruction — 2026-11-07

- The GPUI Frame page now follows the Compose workspace structure: seven KPI
  cards, source/window/deadline controls, a Canvas-rendered duration timeline,
  and a lower clusters/problem-frames/selected-frame analysis row. Timeline
  paging, bounded zoom, hover and selection use the published query page rather
  than creating one element for every source frame.
- Window and deadline filters, range paging and zoom now execute through the
  background publication path. A published typed `FramePage` drives the KPI,
  timeline, table, details and export projection consistently; failed or
  cancelled queries restore the previously published filter/range state. The
  previous offset-reset bug and synchronous render-time full-session query were
  removed. `FrameSession::page` now materializes only the requested rows while
  computing the filtered summary in one pass.
- Selected-frame evidence includes source, IDs/timestamps, duration, known
  budget plus its provenance, deadline/missed-VSync state, flags, largest stage,
  all available stage timings, raw source-state labels, cross-page previous/next
  navigation and clipboard copy. Problem rows are drawn from the complete
  filtered result rather than only the visible timeline page. Consecutive
  deadline misses form evidence-only clusters with bounded rows and timeline
  markers; no root-cause attribution is inferred.
  Unknown budgets remain unavailable. Platform Jank/JankStats reasons, active
  refresh rate, cluster root-cause attribution, online capture, Layout Inspector
  correlation and Perfetto association remain explicit unavailable states for a
  standalone gfxinfo import.

Static validation for this slice uses formatting, locked offline all-target
compilation and diff checks. Tests, native GUI interaction, real-device capture,
large-input performance measurements and packaged acceptance remain separate
gates under the rewrite validation policy.

Fresh static evidence for the Frame slice: `cargo fmt --all -- --check`,
`cargo check --locked --workspace --all-targets --offline`, and
`git diff --check` passed. No test, native GUI, real-device capture, large-input
performance measurement or package validation was executed.

### Source-bound AI candidate slice — 2026-11-06

- Source Workspace now exposes a bounded `AnalysisRequest` after the user
  explicitly selects a deterministic resolution candidate. The request carries
  the candidate ID, workspace ID, immutable snapshot ID, content digest,
  relative path and validated line range; an optional hash-checked snippet is
  clipped to the AI context budget.
- Shell AI preparation now includes `SourceWorkspaces`. Selecting a directory,
  resolving a symbol, or opening a source file alone does not create an AI
  request. Only the clicked candidate is included, and the existing explicit
  source-upload consent remains required before transport authorization.
- No workspace-wide upload, remote source retrieval, credential discovery or
  source-body persistence was added. Candidate validation fails closed if the
  snapshot identity, content hash or deterministic resolution changes.

Fresh static evidence for this slice: `cargo fmt --all`,
`cargo check --locked --workspace --all-targets --offline`, and
`git diff --check` passed. No provider request, source upload, test, GUI or
packaged validation was executed.

### Settings subpages and general preferences slice — 2026-11-06

- Rust settings now use a backward-compatible version 2 preference format. Existing
  version 1 files migrate in memory with defaults for new fields; writes remain
  atomic and Rust-owned. Persisted general preferences include language, light/dark
  theme, display scale (100/110/125%), navigation label visibility, accent preset,
  and Simpleperf capture duration/frequency defaults.
- The GPUI Settings route now has General, Configuration, Simpleperf, AI and About
  subpages. General controls immediately apply theme/accent/font scale and persist;
  Simpleperf steppers persist bounded capture defaults used when the capture page
  is created. Configuration reports the real Rust data/metadata/capture roots;
  AI reports the registered loopback catalog and keeps credentials out of settings;
  About reports the Rust/GPUI build identity and migration status.
- This slice does not claim parity for legacy Android SDK/AGI configuration,
  keychain/provider persistence, custom color picker, or every Compose profiler
  setting. Those controls remain intentionally visible as bounded status or
  future boundaries rather than unimplemented side effects.

Fresh static evidence for this slice: `cargo fmt --all`,
`cargo check --locked --workspace --all-targets --offline`, and
`git diff --check` passed. `cargo run --locked -p aps-app` remained alive for
15 seconds after startup with no panic or runtime error; no tests or external
operations were executed.

### Layout Inspector Compose-layout port slice — 2026-11-06

- The GPUI Layout Inspector now mirrors the Compose `LayoutInspectorMainPage`
  composition instead of the previous minimal three-column page: a
  320 / flexible / 300 dp pane row
  with 7 dp splitters and 29 px panel headers, a 240 dp findings strip with
  severity chips and table header, and the 29 px status bar.
- Pane content is populated from the existing offline archive projection:
  hierarchy rows (20 px rows, 14 px indent, collapse plus search over id, class
  and resource name, per-row visibility toggle), the fit/zoom preview canvas
  with the device frame and sky-blue selected-red bounds, node properties with
  Layout/Drawing/Accessibility categories, and the AI entry in the findings
  header.
- No live capture, ADB, screenshot bitmap, semantics, findings detection or
  timeline data is produced. Those regions render bounded layout-only states
  ("offline", "not captured", zero finding counts) instead of synthetic content.
  The page keeps the single title-bar background rule and separates regions with
  borders and accent tints only.
- The shared page-title row and separate archive-import row remain omitted.
  A compact Inspector HeaderToolbar provides the offline status, disabled live
  capture controls, available window switching, and working pane visibility
  toggles. Archive import remains under File (Cmd/Ctrl+Shift+O), and the native
  Layout Inspector menu retains window switching and pane visibility actions.
  Errors and in-progress import feedback appear only while relevant.
- The shell hides the shared page header for the Layout Inspector route and lets
  the page own the full height, matching the Compose page, which starts at its
  40 px toolbar.

Fresh static/visual evidence for this slice: `cargo build --locked --offline -p
aps-app` passed and a debug run with a local one-frame fixture verified the
empty state, hierarchy, canvas, properties and findings regions in dark and
light themes. No test was executed, and no device, export, screenshot-decode or
packaged validation is claimed.

### Dedicated Network Profiler UI and whole-HAR queries — 2026-11-06

- Replaced the unfinished generic Network branch with
  `aps-app/src/import/network.rs`, following Compose's 40dp toolbar, five
  metric cards, coverage strip, activity region, Requests/Domains tabs,
  eight-column request table and scrollable request details. Network no longer
  has the shell's duplicate page-title/AI row; its own toolbar opens the same
  deferred AI preflight action. JSON/CSV save/copy retain the existing bounded,
  non-overwriting export implementation.
- The actual page-width container query stacks table/details below 1000dp and
  uses side-by-side panes above that breakpoint. Header and rows share a
  horizontally scrollable 900dp-minimum table, while table and details scroll
  vertically independently. Rows are keyboard-focusable buttons with evidence
  accessibility labels. English/Chinese text lives in paired `network.json`
  resource catalogs and follows shared theme/typography tokens.
- `aps-network` now provides a bounded minimized session index and
  `EntryQuery`/`query_entries`: status/method/search filters and stable duration
  or response-size sorting apply across the whole selected HAR before paging.
  The UI displays matching/session counts and rebinds selection by original
  entry index. Source metadata/digest validation and cancellation remain active;
  failed or cancelled work retains the prior published page/query state.
- Real median and P95 are calculated from finite nonnegative HAR durations
  using `(n - 1) * p` linear interpolation, with missing/invalid counts.
  Percentiles are reused within the immutable UI session rather than repeatedly
  sorted on each page. HTTP 4xx/5xx are not relabeled as failed calls; outcome,
  coverage and completeness stay Unknown when evidence is absent.
- The existing `aps-network-rust-minimized-v1` boundary remains unchanged:
  URLs/hosts, raw timestamps, headers, cookies, bodies and arbitrary methods
  are not exposed. Activity/domain/phase/content regions therefore show
  unavailable/not-retained states, not synthetic data. Live Agent capture and
  HAR export remain disabled. This is not full Network feature parity or a
  claim of visual/pixel equivalence with Compose.
- Added regression sources for whole-session multi-page queries, stable sort,
  unknown values, percentile interpolation, same-stamp source replacement,
  cancellation, no-secret egress, responsive breakpoint and UI numeric evidence.
  An independent read-only review found and prompted fixes for keyboard-only
  selection, a self-satisfying layout assertion and repeated percentile work;
  its focused recheck reported no blockers.

Validation for this continuation is format/static/compile/build only. Tests
remain unexecuted under the user's rewrite policy; no native visual, runtime HAR
import, export interaction, provider, device or packaged acceptance is claimed.
Focused Clippy completes with existing workspace warnings; no warning points to
the new Network UI/backend files. Evidence commands and logs:

```text
cargo fmt --all --check
cargo check --locked --workspace --all-targets --offline
cargo clippy --locked -p aps-network -p aps-app --all-targets --offline
cargo build --locked --offline -p aps-app
git diff --check
/tmp/aps-network-check.log
/tmp/aps-network-clippy.log
/tmp/aps-network-build.log
```

### Settings reference-shaped UI and preference wiring — 2026-11-07

This supersedes the earlier five-subpage Settings slice, **not** the final
feature/visual parity gates.

- Added six-section 176dp settings navigation and embedded reference icons;
  removed the old technical-directory cards and expanded About copy. General
  now includes System language/theme, all six display scale choices, a native
  sidebar-label switch, eight reference accent presets, HSV/rainbow hue controls,
  immediate validated hex drafts and reset. Theme changes follow OS appearance;
  custom ARGB accents feed shared workspace colors. Rem scaling is restored on
  startup as well as on changes; explicit-pixel profiler surfaces remain a gap.
- Added Android SDK Browse/Apply/Clear with path validation, canonicalization,
  bounded independent `android-sdk.conf`, atomic replacement and runtime ADB
  publication under one transaction. SDK settings take precedence in newly
  constructed locators; environment-injected locators stay deterministic. No
  settings navigation launches ADB or capture. Existing operations retain their
  planned executable. Legacy CPU/HAR engines are explicitly unavailable here.
- Added Layout Inspector view/hierarchy, capture archive and border-color cards.
  Invisible hierarchy filtering, indices/resource IDs/layer buttons, bounds,
  smallest-area/Z-order hit testing and all three ARGB colors feed the real
  offline workspace. Each border preset affects only its own field. Bounds
  toggles in the preview toolbar roundtrip back to preferences. Findings remain
  empty until a rules adapter exists. The archive multiplier is a future capture
  preference and never relaxes the fixed 172 MiB/156 MiB offline import limits.
- Added Simpleperf's six card groups, bounded defaults and native tooltip mode.
  New defaults apply only to untouched fields and are queued during frozen/running
  captures. Device event probing, capture templates, Period/scope/CallGraph
  changes and Firefox viewer engines remain explicitly disabled.
- Added fixed-title AI settings with persistent Provider/Agent/Skill/Policy tabs.
  The Provider panel edits and verifies actual session-local catalog inputs and
  locks them during preflight/running requests. It does not persist credentials,
  register arbitrary endpoints, refresh remote models or send requests. Extension
  import/trust and policy/report/history controls remain disabled with reasons;
  the UI is not a claim of Codex/Claude or package-management support.
- Version 3 settings preserve atomic writes, reject corrupt/unknown fields and
  accept v1/v2 with defaults. Added regression sources for compatibility, bounds,
  colors/HSV, SDK file safety and Inspector hit/visibility helpers. These sources
  were compiled, **not executed** under the rewrite policy.

Fresh evidence: `cargo fmt --all -- --check`,
`cargo check --locked --workspace --all-targets --offline`,
`cargo build --locked -p aps-app --offline`, and targeted `cargo clippy` completed;
Clippy/compiler warnings remain in the rewrite. Whitespace validation includes
untracked Rust files, not only `git diff --check`. Native GUI, screenshot diff,
real-device/provider, packaging and test acceptance are deferred. There is no
claim of complete settings behavior or pixel parity while these gaps remain.

### Home reference-shaped dashboard — 2026-11-07

- Replaced the old welcome/import/migration splash with two real metrics, four
  shortcuts, eleven ordered module cards and Source Workspaces last, five-column
  recent history, and date/device rail. Home no longer inherits the generic
  page-title/AI/captured-CPU strip. Native File actions reuse Home import handlers.
- Shared theme tokens and paired English/Chinese `home.json` catalogs drive
  presentation. Actual-container layout preserves 4dp workspace/gaps, 320dp rail,
  900dp two-column threshold, 92/78dp cards and reference main-column grid
  breakpoints. The whole page scrolls vertically; narrow recent tables scroll
  horizontally with header and body together.
- Shortcut clicks navigate only, except explicit Trace/HPROF native file pickers.
  Picker cancellation or a busy target never changes routes; only selecting a
  supported ordinary file enters the destination and its original import path.
- Home observes the shared ADB discovery without additional polling or selection
  side effects. Loading/failure/success-empty differ; online KPI excludes offline,
  unauthorized, no-permission and unknown devices while the list retains them.
- CPU recent discovery uses Rust-owned metadata, read-only immutable SQLite,
  strict schema/index checks and before/after file/journal validation. Missing
  metadata is empty without creating it; non-checkpointed WAL/journal and invalid
  indexes fail closed. At most 200 sessions are inspected for six usable snapshot
  entries. Passive discovery only stats snapshots; explicit Open delegates to
  the existing full binding/digest/reopen flow. No full-profile parsing occurs
  from entering Home, and no file/index/import time is relabeled capture time.
- Scope gap: Layout, Trace and Memory persistent recent adapters do not yet exist
  in Rust. The page explicitly describes CPU-only/partial coverage, including
  empty states. This is not complete four-source history or native visual parity.
- Added/compiled regression sources for responsive thresholds, module/shortcut
  routes, table weights, localized dates, device state/error separation, first
  launch without writes, corrupt/missing artifacts, immutable read-only database
  behavior and required indexes. No tests were executed.

Validation evidence is format/static/all-target compilation and app build only;
GUI/pixel matching, keyboard interaction, runtime imports, real-device and
packaged acceptance remain deferred. Logs: `/tmp/aps-home-check.log`,
`/tmp/aps-home-clippy.log`, `/tmp/aps-home-build.log`.

### Network UI fidelity continuation — 2026-11-07

This builds on the November 6 dedicated Network page; it is not a second generic
profiler implementation or a declaration of full capture/data parity.

- Reworked the main request controls into reference-shaped tabs, outcome/count
  and elastic search, with two primary rows below the actual 1000dp page-width
  breakpoint. Existing whole-session HTTP status/method/sort controls are in an
  expandable secondary region; collapsed active filters remain visible by count.
  Search drafts and original query/pagination semantics are preserved.
- Table header/body now share the reference's eight column weights, 900dp
  minimum-width horizontal viewport, semibold methods and HTTP 4xx/5xx error tone.
  Wide panes retain 1.8:1 and stacked panes 1:1.2 proportions.
- Toolbar export ordering and disabled raw-bundle/HAR/live-capture surfaces follow
  the reference with explicit unavailability reasons. Page exports keep their
  accurate minimized-page scope; no raw content is exposed to populate the UI.
- Detail uses the reference-shaped 36dp title/URL subtitle, method/outcome/bytes
  strip, confidence legend, HAR-entry card, ten timing phases with 74dp duration
  columns and unavailable badges, and four separate header/body content blocks.
  It does not claim a verified exchange chain, measured phase timings or captured
  empty content. Copy-details reconstructs a bounded minimized whitelist.
- Fixed short-window collapse: the shell no longer supplies a second vertical
  Network scroller, the toolbar remains fixed, natural page content scrolls, and
  the evidence panes have usable minimum heights. Only the detail title remains
  fixed; its summary, legends, phases, content, copy action and scope scroll as
  one unit so explanatory text cannot compress the evidence viewport to zero.
- Corrected transfer metrics: fully unknown sizes show `—`; partial coverage is
  explicitly a known subtotal; a known zero/successful empty session stays zero.
- Review found an existing HAR source-identity race: hashing a pathname separately
  from the parsed descriptor could bind mismatched content. The adapter now uses
  the same descriptor for cancellable hash/rewind/parse/re-hash and validates both
  handle and path metadata. Unix additionally checks device/inode/ctime. This is
  mutation detection, not a filesystem sandbox or a privileged-writer guarantee.
- Regression sources cover layout floors/natural-height ownership, exact column
  weights/status tones, unknown/partial byte summaries, controls/draft behavior,
  detail phase order/scrolling/minimized copy, same-stamp source replacement and
  hashing cancellation. Authored/compiled only; no test execution.

Validation commands: `cargo fmt --all --check`, locked offline workspace
all-target check, focused `aps-app`/`aps-network` Clippy and `aps-app` build,
plus whitespace checks. Logs: `/tmp/aps-network-ui-check.log`,
`/tmp/aps-network-ui-clippy.log`, `/tmp/aps-network-ui-build.log`.
Native visual, keyboard, real-device, live capture, provider and packaged
acceptance remain open. Domain/activity/phase/content data cannot be recovered
from `aps-network-rust-minimized-v1`; their explicit unavailable states remain.

### Battery reference-shaped workspace — 2026-11-07

The Battery route now owns a dedicated workspace rather than the shared import
summary/table. The current Compose Battery main page, session workspace, results
and selected-run panels are the reference; Compose implementations are unchanged.

- Compact fixed toolbar, session rail, actual-width responsive summary cards,
  source attribution, weighted six-column run table, eight resource tabs, evidence
  inspector, thermal/performance windows, diagnostics and a copyable fixed footer.
  Shared Studio colors, typography and 4dp spacing apply. The shell no longer
  adds a duplicate title/AI row or a second vertical viewport to Battery.
### Layout Inspector toolbar and window-menu alignment — 2026-11-07

The Layout Inspector header and the native window menu now follow the Compose
page instead of the earlier offline-only approximations. Compose remains the
behavior oracle; no Compose source changed.

- Capture target is a Compose `DropdownSelector` (accent outline, accent label,
  trailing chevron) labelled `目标: 前台应用` / `Target: Foreground app`, with
  `前台应用` and `系统界面` menu items, a check on the selected mode, and the
  label following the choice. The offline archive adapter has no live capture,
  so the selection is presentation state and starts no device operation.
- Manual/automatic scan is a single `SegmentedSelector(COMPACT_OUTLINED)`-style
  control (`刷新` / `自动扫描`, accent track, selected accent fill, hairline
  separators) pinned to the right end of the toolbar immediately before the
  trailing divider, matching Compose's `Refresh | Auto scan` placement. Both
  segments stay visibly disabled for the same reason the page states live
  capture is unavailable.
- The connection dot and label now share the Compose warning tone, and pane
  toggles use the Compose `PanelToggleButton` metrics (26 x 21, 3 px radius,
  18% accent tint when visible, no outline) instead of boxed icon buttons.
- The native window menu is now the Compose skeleton — File, View, Tools, Help
  plus the macOS application menu — and is route-aware: the shell rebuilds it on
  navigation, pane visibility changes and layout-preference changes. On the
  Layout Inspector route File publishes `打开…` and the disabled `导出归档…`
  placeholder (archive export is not implemented), View publishes the three
  pane toggles and the five view options with checked state bound to the shared
  `LayoutPreferences`, Tools publishes `自动扫描` (disabled), `显示层级 ID`
  (checked from preferences) and `下一个窗口`, and Help opens the AI workspace.
  Other routes keep the existing File import/export actions and show disabled
  View/Tools menus, so no previous action was removed.
- New locale keys live in both catalogs: `inspector_capture_target`,
  `inspector_refresh`, `inspector_auto_scan`, `shell_menu_view`,
  `shell_menu_tools`, `shell_menu_help` and `shell_menu_open`; the pane-toggle
  strings now use Compose's `隐藏左侧栏/隐藏底部栏/隐藏右侧栏` wording.
- Source-inspection regression updated: the native menu actions are asserted in
  `shell.rs`'s menu builder, and `main.rs` must not install menus directly.

Evidence: `cargo fmt --all`, `cargo check --locked --workspace --all-targets
--offline`, `cargo build --locked --offline -p aps-app`, and a native debug run
with screenshots of the empty page, the open capture-target dropdown (both
modes), the View menu checkmarks before/after a toggle, the Tools/Help menus and
the non-inspector File fallback. The native run used a workspace-local
`APS_RUST_DATA_DIR` copy of the Rust settings because the agent sandbox cannot
write the real application-support directory; settings reads, menu rebuilding,
preference persistence and the resulting banner-free UI were verified that way.
No test was executed; live capture, archive export and node-navigation tools
remain unimplemented.

### Layout Inspector canvas-header modes — 2026-11-07

The preview panel now exposes the two Compose canvas-header controls that were
missing from the Rust port. Compose remains the behavior oracle; no Compose
source changed.

- `仅应用` chip defaults to on and crops the preview source to the active
  window bounds, clamped to the display, exactly like
  `CanvasGeometry.sourceRect` / `CanvasWindowSource.sourceRect`; invalid or
  empty bounds fall back to the full display. Dimensions show the source size
  (`1080 × 2160` with the app window, `1080 × 2400` when off), the frame corner
  radius follows Compose (`12` on / `2` off), and pointer hit testing maps back
  into display space before `canvas_hit`, so selection stays accurate below the
  status bar offset.
- `小面积优先 / Z 序优先` chip toggles the persisted
  `LayoutPreferences.hit_smallest`, reusing the existing
  `InspectorPreferencesChanged` shell persistence and the existing smallest-area
  hit ordering. Compose labels and tints match: 18%/8% accent surface for
  app-only on/off, 14% for hit order, bold accent 4dp chip at 8 x 4 padding.
- New locale keys in both catalogs: `inspector_app_only_on`,
  `inspector_app_only_off`, `inspector_small_area_hits`,
  `inspector_z_order_hits`.
- Added a debug-only `APS_RUST_OPEN_INSPECTOR` launch seam (alongside the
  existing `APS_RUST_OPEN_HPROF` seam) so native visual checks can load a real
  archive zip without driving the platform file picker. Release builds never
  compile it.
- Authored (not executed, per the validation policy) regression coverage:
  `preview_source_crops_to_app_bounds_only_when_app_only_is_on`,
  `preview_source_clamps_app_bounds_and_falls_back_when_empty` and a
  source-inspection assertion for the two chip ids / `preview_source`.

Evidence: `cargo fmt --all --check`, `cargo check --locked --workspace
--all-targets --offline`, `cargo build --locked --offline -p aps-app`, and
native window captures with a locally generated one-frame archive:
`build/layout-inspector-toolbar/preview-canvas-chips.png` (app-only on,
1080 × 2160), `preview-app-only-off.png` (off, 1080 × 2400) and
`preview-hit-order-z.png` (`Z 序优先`). No test was executed; live capture,
screenshot display and node navigation remain unimplemented.

### Layout Inspector property parity, screenshot canvas and zoom overlay — 2026-11-07

The Node Properties pane now matches the Compose detail projection instead of the
initial structural subset, the imported screenshot is rendered in the canvas,
and the zoom control moved to Compose's bottom-right overlay. Compose remains
the behavior oracle; no Compose source changed.

- `aps-layout-inspector` now retains the bounded data those sections display:
  node `text` (<=64 KiB), `semanticsRole`, and the protocol `ViewAttributes`
  fields. `rawProperties`/`semanticProperties` are capped per node (256 entries,
  512-byte names, 8 KiB values) and by a 4 MiB archive-wide budget; later nodes
  keep structural attributes without property maps. Screenshot PNG bytes are
  retained after the existing digest/size validation for host-side decoding; the
  adapter still never decodes pixels. The crate README's old "runtime values not
  retained" boundary was updated accordingly.
- Properties pane categories are now Overview / Layout / Drawing / Accessibility
  / Evidence, each rendering Compose's sections and rows: IDENTITY
  (class/id/resource/text); LAYOUT (bounds, size, local layout bounds/size,
  visibility, tree depth, direct children, descendants, subtree depth, layout
  width/height, params class, measured/min size, padding, margin, scroll,
  layout requested); DRAWING (alpha, z, elevation, translation, rotation, scale,
  pivot, background/backgroundColor/foreground, clip bounds/children/toPadding,
  opaque, willNotDraw, hardware accelerated, layer type); INTERACTION (content
  description, semantics role, enabled/clickable/longClickable/focusable/focused/
  selected); EVIDENCE (render risks: overdraw estimate, subtree complexity,
  hidden descendants, blending, layer cost; plus sorted RAW PROPERTIES). Missing
  values render "—", floats use Kotlin `Float.toString` shape, dimensions map
  -1/-2 to MATCH_PARENT/WRAP_CONTENT, values use monospace 11 sp, and risk rows
  use Compose's warning/info tones. Section headers are 29 dp with a hairline.
  The five category tabs render through the same COMPACT_OUTLINED segmented
  track as the header Refresh/Auto scan control (single accent outline, selected
  accent fill with accentText, 1 px accent hairlines, equal-width items).
- Property rows now use Compose's alternating stripe backgrounds: even rows
  `detailRowDeep` (#1C1C1E dark, white light) and odd rows `detailRowLight`
  (#2C2C2E dark, white light), with a 6% tone tint layered over the stripe for
  warning/info rows. Stripe colors were added to `StudioColors` and the row
  index is scoped per section, matching `DetailRowStripe.usesDeepBackground`.
- Fixed a regression the new attribute retention introduced for previously
  saved archives: Compose encodes `EdgeInsets` with `encodeDefaults=false`, so
  zero sides are omitted, nullable/defaulted fields may be omitted or null, and
  Compose nodes keep `semanticProperties` on the node. Optional attribute
  readers now treat missing/null as absent, omitted EdgeInsets sides default to
  0, node-level semantic properties are merged into the Evidence map, and a
  malformed display-only attribute falls back to defaults instead of rejecting
  the archive (structural fields remain strict). Regression coverage was added
  for the omitted-inset/node-semantics shape, and a matching archive was loaded
  natively without the "snapshot structure is invalid" error.
- The canvas paints the decoded archive screenshot before the node overlays,
  using the same app-only source crop and 12/2 radius as the layout rectangles;
  the image is decoded once per published frame and any decode failure keeps the
  layout-only canvas. Android bounds selection/hover mapping is unchanged.
- Zoom/fit controls moved from a centered footer row into Compose's bottom-right
  overlay: radius 8, panel @90% fill, border @65%, padding 6/4, 24 x 24 buttons,
  42-wide monospace value, accent/muted glyph states, 50%-250% range in 25%
  steps. The footer row and its "geometry" label were removed.
- Locale keys were added to both catalogs for the new sections, labels, and risk
  values (`inspector_detail_section_*`, `inspector_detail_*`,
  `inspector_details_evidence`, `inspector_value_unavailable`,
  `inspector_no_high_overlap_pairs`, `inspector_overlap_pairs`,
  `inspector_subtree_complexity`, `inspector_blending_alpha`,
  `inspector_alpha_one`). `inspector_detail_alpha`/`inspector_detail_size`/
  `inspector_details_overview` now use Compose's wording.

Evidence: `cargo fmt --all --check`, `cargo check --locked -p
aps-layout-inspector --all-targets --offline`, `cargo check --locked -p aps-app
--bin aps-app --offline` and `cargo build --locked --offline -p aps-app --bin
aps-app`. The aps-app all-targets check is currently blocked by an unrelated
in-flight `shell/menubar.rs` test-target recursion-limit error in the shared
working tree; the binary target itself compiles. Native verification used a
locally generated archive with full attributes, text, raw properties and a
1080 x 2400 screenshot: screenshots are in
`build/layout-inspector-parity/`. No test was executed (per the deferred
validation policy); authored coverage includes the attribute-retention test and
the earlier preview-source/chip assertions.

### Route menu bar — 2026-11-07

The Compose window published a native File/View/Tools/Help menu per page. The
Rust shell now publishes the menus of the active route and, on macOS, installs
them as the native application menu bar at the top of the screen, keeping the
platform menu placement and native rendering (disabled rows, checkmarks and the
application menu). This departs from Compose in content, not in placement: no
Windows-style menu row is drawn inside the window on macOS. Other platforms
fall back to an in-window strip built from the same data.

- `shell/menubar.rs` owns the route menu model. `MenuGroup`, `MenuEntry` and
  `MenuLabel` build menu data (catalog keys and runtime strings such as imported
  HPROF filenames); `MenuHost` publishes a typed command enum per workspace; and
  `RouteMenuBuild` turns the groups into both the native `Menu` tree and the
  fallback strip rows. Menu rows carry a `RouteMenuAction` id into a dispatch
  table built with the menus, so typed commands run without string encoding.
  Menus are rebuilt from live workspace state and reinstalled only when the
  localized content signature changes, so notifications do not flicker the open
  menu.
- The shell composes `route_menu_build` per route: Home publishes its import
  surface, Settings publishes its sections, the Layout Inspector publishes
  archive, pane, view and window tools, and one shared Analyze menu plus the
  Help menu close every profiler route. Commands route back into the workspace
  that published them, so a route never sees another screen's state.
- Route menus: CPU File/View/Tools, Trace File/Trace/View/Session, Frame
  File/Frames/View, Memory File/View, and the import-backed workspaces
  (Network/Startup/Battery/Benchmark/GPU) publish one File menu with their own
  import, page-export and baseline/artifact rows. Source Workspaces publishes
  Workspace/View, and AI publishes Session. Menu rows use `item_when`/`check`
  against live state, so page exports stay disabled until a page exists, memory
  snapshot tabs appear by filename, and pane toggles show their current
  checkmark. The screens that only offer file/import operations share the File
  title so the command location stays predictable.
- Empty menus are not published. The macOS application menu stays first in the
  bar. Menu commands are dispatched through `window.defer`, so a workspace
  command can write back through its shell handle without re-entering the shell
  update that owns the menu action.
- Locale keys: shell menu titles (`shell_menu_profile`, `shell_menu_heap`,
  `shell_menu_frames`, `shell_menu_startup`, `shell_menu_battery`,
  `shell_menu_network`, `shell_menu_gpu`, `shell_menu_benchmark`,
  `shell_menu_trace`, `shell_menu_workspace`, `shell_menu_session`,
  `shell_menu_analyze`, `shell_menu_settings_page`) plus per-workspace action
  labels added to their own catalogs.
- `main.rs` installs no menus; `Studio::new` performs the first route menu
  refresh. `shell/menubar.rs` unit tests cover group composition, empty-group
  suppression, dispatch-id assignment and the content signature.
- Evidence: `cargo fmt --all`, `cargo check --locked --offline --workspace
  --all-targets`, `cargo test --locked --offline -p aps-app` (291 passed), and a
  native run with screenshots in `build/menubar-verification/` of the macOS
  menu bar for Home (File/Help), Trace (File/Trace/View/Session/Analyze/Help),
  CPU Profile (Profile/View/Tools/Analyze/Help with its native Profile menu
  open) and Settings, plus the absence of an in-window menu row.

### Compose Agent toggle and offline Compose inspection — 2026-11-07

The Layout Inspector header now exposes the Compose toolbar's Compose Agent
opt-in and uses it to surface Compose inspection data captured in the current
archive. Compose remains the behavior oracle; no Compose source changed.

- `Compose Agent` checkbox (18 dp box + 11 sp label) sits immediately before the
  Refresh/Auto scan segmented control, matching Compose's toolbar order. Offline
  builds cannot inject or authorize a live agent, so it is enabled only when the
  loaded archive contains `capture/compose-inspection.json`; otherwise it stays
  disabled and dimmed. Checking it is presentation state only and never starts
  a device operation.
- While checked, Compose nodes gain the Compose category between Accessibility
  and Evidence (categories without data stay hidden, as Compose does), and the
  Overview tab gains a COMPOSE SUMMARY section (source location, parameter
  count, modifier count, merged/unmerged semantics counts). The Compose category
  renders COMPOSE INSPECTION (mode/generation/completeness), COMPOSE SOURCE
  (file/line/offset/package hash), RECOMPOSITION (recompose/skip counts), STATE
  READS (recomposition number, value, invalidated flag, top frame), PARAMETERS,
  MODIFIERS, MERGED SEMANTICS, UNMERGED SEMANTICS (nested values flattened as
  dotted labels with type/value/truncation), and DETAIL COVERAGE records for the
  selected node. Node lookup uses the archive's `compose-inspection:<id>`
  prefix.
- `aps-layout-inspector` now retains that bounded per-node Compose projection
  from the sidecar: nodes (name, source, recompose/skip counts, systemCreated,
  flags), details (four value lists; <=8 depth, <=512 rows per list,
  <=200k retained values total, 4 KiB per string) and state-read groups
  (<=20k reads), plus the existing coverage records and capability states.
  `details_available` now reflects whether per-node data was retained. Optional
  sidecar parsing failures fall back to an empty projection instead of rejecting
  the saved archive.
- New locale keys cover the checkbox, category, sections, labels, and state-read
  row format in both catalogs.

Evidence: `cargo fmt --all`, `cargo check --offline -p aps-layout-inspector
--all-targets`, `cargo build --offline -p aps-app --bin aps-app`, and a native
run with an archive containing a v2 Compose inspection sidecar (root Card node,
parameters/modifiers/merged semantics, coverage and state reads). Screenshots:
`build/layout-inspector-parity/compose-agent-off.png` (disabled-looking
unchecked entry with sidecar present), `compose-agent-on.png` (checked box plus
COMPOSE SUMMARY) and `compose-category.png` (Compose category with source,
recomposition, state reads and parameter rows). No test was executed; live
Compose Agent injection, authorization, and recomposition controls remain
unimplemented.

### Hierarchy depth-index numbering — 2026-11-07

Hierarchy rows now use Compose's node numbering instead of a global preorder
counter. Compose remains the behavior oracle; no Compose source changed.

- The first token is `{depth}-{index}` with a per-depth preorder counter
  (`nextIndexByDepth`), so a root is `0-0`, its children `1-0`, `1-1`, and
  grandchildren `2-0`… The counter runs over the full preorder before
  collapsing/invisible/search filtering, so filtered rows keep their original
  number, exactly like Compose's `TreeRowModel.number`.
- The class token is now the simple class name (`FrameLayout` instead of
  `android.widget.FrameLayout`), and the `show-ids` token renders
  `id/{last resource segment}` (e.g. `id/content`) like Compose's
  `resourceLabel`; `hide-indices` still removes the number token.
- Authored (not executed) coverage: `hierarchy_numbers_use_per_depth_preorder`
  and the updated preference test asserting the `depth-index`, `id/...` and
  simple-class tokens.

Evidence: `cargo fmt --all --check`, `cargo check --offline -p aps-app
--bin aps-app`, `cargo build --offline -p aps-app --bin aps-app`, and a native
capture showing `0-0 FrameLayout / 1-0 Toolbar / 1-1 FrameLayout / 2-0
LinearLayout / 2-1 RecyclerView / 2-2 View / 2-3 Composable / 2-4 View` at
`build/layout-inspector-parity/hierarchy-depth-index.png`. No test was executed.

### Resizable inspector panes — 2026-11-07

The hierarchy, preview, properties and findings panes were fixed sizes; their
splitters only showed a resize cursor. They now drag like the Compose page.
Compose remains the behavior oracle; no Compose source changed.

- Hierarchy width and properties width are drag-resizable via the two vertical
  splitters; findings height via the horizontal splitter above the table.
  Composer drags start from the pointer-down position and apply deltas against
  the drag-start size, so clamped panes never accumulate drift. Sizes are session
  state only, matching Compose's `remember`-scoped `paneWidths` /
  `findingsHeightDp`.
- Clamps mirror Compose: hierarchy 180–640 dp, properties 200–600 dp, canvas
  keeps at least 320 dp (both widths are additionally limited by the window
  width at drag start), findings 56 dp up to 50% of the window height. The
  preview flexes between the panes, so all three resizes stay interactive.
- Splitter ids are now distinct (`inspector-splitter-hierarchy`,
  `inspector-splitter-properties`, `inspector-splitter-findings`); the
  workspace root owns the drag-move/up handlers and clears the drag state.

Evidence: `cargo fmt --all --check`, `cargo check --offline -p aps-app
--bin aps-app`, `cargo build --offline -p aps-app --bin aps-app`, plus native
synthetic drags on all three splitters: `build/layout-inspector-parity/pane-
resize-hierarchy.png` (hierarchy widened), `pane-resize-findings.png`
(findings taller), `pane-resize-properties.png` (properties wider). No test was
executed.

### Scrollable hierarchy and preview pan/scrollbars — 2026-11-08

The hierarchy list and the preview canvas were fixed-viewport with no visible
scroll chrome. Compose remains the behavior oracle; no Compose source changed.

- Hierarchy rows now live in one scroller on both axes, like Compose's
  `horizontalScroll` + `LazyColumn`: the uniform list uses unconstrained
  horizontal sizing, rows are sized to the longest estimated label
  (`hierarchy_content_width`), and vertical + horizontal Scrollbar overlays are
  always shown (`ScrollbarMode::Always`). Deep or long trees pan sideways and
  tall trees scroll vertically with visible bars.
- The preview canvas now has Compose `PreviewPanState` panning: wheel pans
  vertically (shift+wheel horizontally), left-drag pans the canvas (a press
  without movement still selects the node), and both scrollbars are painted at
  the canvas edges with thumbs that track the pan. Dragging inside the
  horizontal/vertical bar track jumps/pans directly. Pan is clamped to
  `+-(content - viewport) / 2` per axis and resets to centre when content fits;
  zoom buttons (`+`/`-`/Fit) clamp or reset the pan. Scrollbar thumbs stay
  visible at 100% as full-length tracks, matching the Compose adapter.
- Authored (not executed) coverage: `preview_pan_clamps_to_content_overflow`
  asserts the clamp/limit math for fitting and overflowing content.

Evidence: `cargo fmt --all --check`, `cargo check --offline -p aps-app
--bin aps-app`, `cargo build --offline -p aps-app --bin aps-app`, and native
captures: `build/layout-inspector-parity/hierarchy-scrollbars.png` (47-node
tree with both scrollbars) and `preview-pan-scrollbars.png` (250% preview panned
to a corner, hierarchy scrolled on both axes, thumb positions moved). No test
was executed.

### Selectable and copyable properties values — 2026-11-08

Node-properties values were plain text and could not be selected. Compose
`DetailRow` wraps the value in `SelectionContainer`; the Rust pane now reuses
the app's window-wide selectable-text helper the same way.

- Every detail-row value (Overview/Layout/Drawing/Accessibility/Compose
  categories, raw properties and section rows) is now rendered with
  `crate::studio::selectable`, i.e. a `SelectableText` that joins the shell's
  `TextSelectionLayer`. Drag to select, Cmd+C (secondary+C) to copy; selection
  can span multiple runs in the pane. Monospace font, tone colour, size and
  ellipsis stay inherited from the surrounding row, so layout and the
  alternate-row stripes are unchanged. Labels stay plain text, matching Compose
  where only the value is inside `SelectionContainer`.
- No new state or event plumbing: the existing global selection layer already
  installed by the shell owns highlight/copy behaviour for every selectable run
  in the window.

Evidence: `rustfmt --check` on the touched files, `cargo check --offline -p
aps-app --bin aps-app`, `cargo build --offline -p aps-app --bin aps-app`, and a
native run showing property values still render in place
(`build/layout-inspector-parity/selectable-properties.png`). Synthetic
select-and-copy could not be driven reliably in the concurrent second app
instance (another session keeps stealing key focus), so copy relies on the
already-shipped window selection layer rather than new code. No test was
executed.

### Right-click Copy menu on property rows — 2026-11-08

Selecting property text could already copy via the window selection layer, but
there was no discoverable action. Compose-style rows now expose a context menu.

- Each node-properties row carries a `ContextMenuExt` context menu with one
  action, `inspector_copy_value` (复制 / Copy). Right-clicking any row opens the
  popup at the pointer; choosing 复制 writes to the clipboard:
  - the current text selection when the window selection layer holds one, so a
    partially selected value copies just the selected substring;
  - otherwise the row's full value, so a right-click without a prior drag still
    copies the property.
- The menu is per-row (row element ids combine the English section title and
  row index), so duplicate values in different sections keep independent menu
  state. Labels remain non-selectable, matching Compose.
- Locale catalogs gained `inspector_copy_value` (Copy/复制).

Evidence: `rustfmt --check` on the touched file, `cargo check --offline -p
aps-app --bin aps-app`, `cargo build --offline -p aps-app --bin aps-app`, and a
native right-click capture showing the 复制 item next to a properties row
(`build/layout-inspector-parity/property-row-copy-menu.png`). Note: synthetic
left-click delivery into the menu popup is unreliable in this concurrent
session (the same synthetic click also fails to open the existing target
dropdown), so the menu-item activation itself was not reproduced end-to-end;
the action reuses the standard `cx.write_to_clipboard` path already used across
the app. No test was executed.

### Layout Inspector menu parity and window-menu shortcuts — 2026-11-08

The Layout Inspector window menu now mirrors Compose NativeViewerMenuBar instead
of exposing only the pane toggles.

- File publishes Open… (Cmd+O), Import (Import Screenshot…), Open Recent
  (No recent files, Clear Recent) and Export (Export Capture Archive…), plus
  Settings on non-macOS platforms; macOS keeps Settings in the application menu.
  Rust has no archive export, screenshot import or recent-archive persistence, so
  those rows render disabled exactly where Compose renders an unavailable
  capability instead of pretending the operation exists.
- View lists the three pane toggles first (Hide left/bottom/right panel, checked
  only while that pane is hidden, matching Compose checked = !visible) followed by
  the five display options in Compose grouping.
- Tools publishes Auto scan (disabled: live capture is unavailable), Previous
  Node, Next Node, Toggle Selected Node (enabled only with a node selected), Show
  hierarchy IDs and the Rust-only Next window row, with Compose separators.
- The menu model gained submenus (native MenuItem::submenu, flattened into a
  header plus rows in the non-macOS strip) and optional real GPUI actions per
  row. Rows backed by actions resolve their key equivalents in the macOS menu
  bar: Open… uses the route-aware OpenActiveRouteFile action (Cmd+O), pane
  toggles use ToggleInspectorHierarchy/Findings/Details (Cmd+1/2/3), tree
  navigation uses PreviousInspectorNode/NextInspectorNode/ToggleSelectedInspectorNode
  (Up/Down/Enter under the Inspector key context), display options and hierarchy
  IDs use the existing ToggleLayout* actions.
- Toggle Selected Node intentionally keeps its command and Enter binding without a
  shortcut label: the macOS backend renders the multi-character key name enter as
  a single letter, which would mislead. Up/Down render natively as arrows.
- Node navigation uses InspectorWorkspace.visible_rows, so collapsed subtrees and
  hidden invisible views are skipped like Compose adjacentNodeId; toggle expands
  or collapses the selected node (Compose toggleExpandable), not layer visibility.
- Evidence: cargo fmt --all, cargo check --locked --offline -p aps-app
  --all-targets, cargo test --locked --offline -p aps-app (298 passed, 2 ignored),
  plus native screenshots in build/menubar-verification/ of the File submenus,
  View menu with Cmd+1/2/3, Tools menu, and Cmd+1/2/3 actually hiding and
  restoring the three panes.


### Full-bleed, equal-width property category tabs — 2026-11-08

The category tabs under the properties header kept an 8 px horizontal inset,
so the segmented track floated inside the pane. The tabs now use the entire
pane width.

- Removed the tabs row's horizontal padding; the `COMPACT_OUTLINED` track is
  flush with the properties pane edges.
- Every tab keeps `flex_1` plus `min_w_0`, so the visible categories divide the
  full width evenly regardless of label length; the accent outline, selected
  fill, hairlines and separators are unchanged.

Evidence: `rustfmt --check`, `cargo check --offline -p aps-app --bin aps-app`,
`cargo build --offline -p aps-app --bin aps-app`, and a native capture showing
five equal tabs spanning the pane without side margins
(`build/layout-inspector-parity/property-tabs-full-bleed.png`). No test was
executed.

### Window-menu parity with Compose across every page — 2026-11-08

Every page that publishes a Compose PageMenu now publishes the same shape in
the Rust window menu (native bar on macOS, strip elsewhere). Shortcuts follow
Compose's reserved set: Open… Cmd+O (route-aware), Open Folder… Cmd+Shift+O
(CPU), primary Export Cmd+E (CPU report JSON, Trace session package), Settings
Cmd+, and the Layout Inspector tree keys (Cmd+1/2/3, Up/Down/Enter).

- CPU: File > Open…, Open Folder…, Open Recent (saved-session catalog rows),
  Export (Compose's three groups: report JSON with Cmd+E, session package,
  screenshot; raw protobuf, Firefox profile, folded stacks, PProf; simpleperf
  report, HTML report, open externally), Close session, Settings outside macOS;
  Tools > Configuration publishes Compose's three capture-settings rows
  (OpenCaptureSettings action) above the real Rust tools.
- Trace: File > Open…, Open Recent (session catalog), Export (session package
  with Cmd+E, raw trace), Close trace; existing Trace/View/Session groups kept.
- Frame: File > Open…, Import (Perfetto FrameTimeline, Associate Perfetto
  Trace), Export (CSV/JSON gated on an analysis), keeping the Frames/View
  groups with capture and timeline rows.
- Memory: File > Open…, Import (Java Heap, Native Heap, ART Sample, Mapping;
  only HPROF is wired in Rust so the rest stay disabled), Open Recent
  (disabled: no persisted memory recents), Export with Compose's three groups
  and all rows disabled because no memory export is wired to a page control;
  open-snapshot tabs and the View (view modes) group are kept.
- Battery: File > Open…, Open Recent (disabled), Export (page JSON/CSV live;
  raw evidence disabled), Tools > Reset global BatteryStats (disabled: no
  capture adapter).
- Startup: File > Open…, Export (page CSV/JSON).
- Network, Benchmark and GPU Inspector have no Compose page menu; they publish
  File > Open…, Open Recent (disabled), Export (save/copy page rows), plus the
  page's real state-driven rows (baseline actions, GPU open/show in files).
- Help now matches the host: User guide (opens the bundled docs-user site,
  disabled when absent) and AI analysis; Settings only outside macOS.
- Menu model: submenu rows can carry real GPUI actions
  (Submenu::item_action / item_action_when), which is how Cmd+E appears inside
  File > Export on macOS.
- Build fix: aps-ui-foundation/build.rs watched only the resources directory
  mtime, so catalog edits could leave TextKey generated from stale JSON; it now
  emits rerun-if-changed for every catalog file.
- Evidence: cargo fmt --all, cargo check --locked --offline --workspace
  --all-targets, cargo test --locked --offline -p aps-app (310 passed, 3
  ignored), and native screenshots in build/menubar-verification/ of the CPU
  File menu and Export submenu (Cmd+O / Cmd+Shift+O / Cmd+E), the Trace File
  menu and the Memory File menu.


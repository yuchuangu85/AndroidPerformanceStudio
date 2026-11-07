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
5. **Memory/Network/Perfetto (phase 4)** — pending: engine adapters, paginated
   UI, redaction, bounded authorized content, native Trace Processor lifecycle.
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
- Sessions use the reference's 1000dp breakpoint, 230dp wide rail and 220dp narrow
  card. Narrow short windows scroll the whole content to keep results accessible.
  The run table shares header/body horizontal scrolling below 580dp. Charts and
  the 290dp inspector stack below 860dp actual results width.
- Imports use the existing native picker, generation-bound publication and
  cancellation paths; native File import delegates to the same feature adapter
  and cancellation preserves navigation. Only the current imported report is
  listed; Rust does not invent a saved-session index, report status or capture
  time from file metadata. New closes the current imported report without
  touching its source, Compose storage or device state.
- Summary statistics follow the reference's nearest-rank quantiles and preserve
  signed finite temperatures. Omitted timer arrays/counters remain unknown,
  explicit empty arrays mean zero reported records, and alarm totals are not
  relabeled wakeup-only. Energy records with incompatible component/source/
  scope/confidence are not summed. Empty Energy remains an accessible resource
  tab; only an unavailable scalar energy card is omitted.
- Selection uses source run IDs and evidence indices, not duplicate names or
  filtered positions. Publication keeps the selected run in the displayed page.
  Resource search filters before bounded materialization; empty searches avoid
  building search documents, and only displayed rows clone evidence strings.
  Runs, resources, performance windows and diagnostics are all reachable through
  bounded 100-item pages. Standard Input/Button controls retain keyboard semantics.
- Schema-v1 import now preserves optional source metadata, timer confidence,
  throttled-sample counts and performance windows with strict type/range/calendar
  validation. Imported labels/diagnostics remain verbatim. Raw snapshots/history
  clocks are unavailable, not synthetic charts; aggregate network bytes are not
  request events or inferred Wi-Fi/mobile counters. A comparable baseline cannot
  be established from this derived schema alone.
- Configuration opens a real dismissible dialog showing disabled reference
  controls and explaining that no capture preferences are applied. Live capture,
  app launch, global reset, Historian/bugreport generation and raw bundles stay
  disabled with specific reasons. AI continues through explicit preflight, while
  JSON/CSV export labels accurately disclose the existing published-page format,
  not a reimportable session archive.
- Added regression sources for exported fields/nullability, strict malformed
  metadata, signed statistics, missing-versus-empty evidence, stable paging and
  selection, lazy row construction, resource tabs, display and layout thresholds.
  These are compiled only; test execution remains deferred by the rewrite policy.

Validation: `cargo fmt --all --check`, locked offline workspace all-target check,
focused Battery/App/Foundation Clippy, app build and whitespace checks. Logs:
`/tmp/aps-battery-ui-check.log`, `/tmp/aps-battery-ui-clippy.log`,
`/tmp/aps-battery-ui-build.log`. Existing unrelated workspace warnings remain;
no fresh Battery-file diagnostics are accepted. Native screenshots/pixel fidelity,
### Source Workspaces Compose-parity slice — 2026-11-07

- `aps-source` was rewritten as the Compose `source-workspace` domain:
  digest-backed workspace/snapshot/candidate/evidence IDs, local/GitHub/AOSP
  provider configs, the seven workspace phases, snapshot/file/symbol/range/
  location/candidate/content-state models, a content-addressed cache, a
  Git-aware local provider (`git rev-parse HEAD`, dirty digest over sorted
  `path:sha256` lines, symlink-safe walk, 5 MiB and extension allowlists), the
  `StructuralSourceIndexer` declaration port (validated pattern-for-pattern
  against the Compose JVM regexes), an in-memory `SourceRepository`, and
  `IndexedSourceResolver` with Compose confidence tiers, raw reason strings,
  deterministic candidate IDs, Java `String.hashCode` package matching and the
  `BuildIdentityMatch` Exact→Probable ceiling.
- `aps-storage` schema v3 adds workspaces/snapshots/files/symbols/candidates
  with cascades and bounded validation, implements
  `aps_source::SourceRepository` for `Storage`, and migrates dev databases by
  recreating only the old source tables (artifacts and sessions preserved). No
  Compose database or settings are read.
- `crates/aps-app/src/source/service.rs` implements the blocking
  `DefaultSourceWorkspaceService` flow: add/refresh/remove/read/load/snapshot/
  files/candidate/save_candidates/resolve, Compose's exact phase/progress/
  message sequence, snapshot id = sha256("<workspace>:<revision>:<manifest>"),
  cache-every-file indexing, and Current/Stale verification against the working
  tree. The DB is opened per call; failures persist the Failed phase and keep the
  previous snapshot.
- The GPUI page (`source/page.rs`, `source/tree.rs`, `source/layout.rs`,
  `source/code_viewer.rs`, `source/ai_context.rs`, `source/permissions.rs`)
  reproduces Compose's toolbar (+Local, AI Settings, Run AI Analysis; GitHub/AOSP
  hidden), three-pane container-query layout with 360/320/220/220/360 minimums
  and 7 px draggable splitters, workspace cards (provider·phase, progress,
  localized ready summary, Sync/AI-source/Remove), the 28 px file search row,
  20 px / 14 px-indent virtualized collapsed-first directory tree with
  language-colored icons and disclosure triangles, the source header with
  path:line, Copy location/Open in IDE, snapshot summary, localized candidate
  reasons and stale warning, and the One Dark viewer (verbatim palette and
  tokenizer, 56 px gutter, range band, 28 px search with n/m and prev/next,
  horizontal/vertical scrolling, drag selection and copy).
- AI: the page publishes bounded, metadata-only selection evidence (snapshot
  identity, content hash, language, content state, size, line count/range, no
  source text). A source candidate is included only while the workspace's
  Rust-owned upload grant is set; the existing AI preflight still requires its
  separate explicit source-consent approval before transport. The "AI Settings"
  button opens the existing Rust AI settings subpage.
- Localization: `values{,-zh}/source.json` replaced with the Compose
  `source_*` page/viewer keys (status/phase/confidence/reason/summary
  templates); no source text, SQL, paths or diagnostics are translated.

Verification (initial pass): `cargo fmt --all --check`, `cargo check --workspace
--all-targets --offline`, `cargo build -p aps-app` and a focused Clippy pass
completed. A native dark-English smoke run opened the page with a seeded local
fixture workspace (selected card, auto-expanded tree, C++ file open in the One
Dark viewer) without panic; capture at
`build/source-workspace/source-workspaces-preview.png`, fixture seeding script
beside it. The full interactive verification pass is recorded below.

### Source Workspaces verification pass — 2026-11-07

The user explicitly requested "检测所有功能" (verify every function) in this
pass, so the previously deferred test execution was performed for this slice.
Unrelated rewrite work keeps the deferred-test policy.

- `cargo test --workspace --offline --no-fail-fast`: **392 passed, 0 failed**
  across 69 test binaries. New source coverage: aps-source integration tests
  (provider/paths/cache/indexer/resolver/repository, 11), aps-storage
  repository+read-only+contract tests (8 + 6), aps-app unit tests including nine
  One Dark lexer/search tests, five AI-context contract tests, four service
  end-to-end tests (add/refresh/read Current+Stale/candidates/remove/failure),
  a runtime navigation-evidence test and two page localization-mapping tests,
  plus the existing tree/layout/permissions suites, and the localization
  catalog/source-scan suite (7).
- Defects found by detection and fixed:
  - The page's AI Analysis action panicked ("cannot read aps_app::source::
    SourceWorkspace while it is already being updated") because it re-read the
    updating entity from the shell; the page now computes the request in its own
    listener and applies it through `Studio::present_ai_request`.
  - The pending add card used the "+ Local" label; the runtime now carries the
    pending directory name and the live card shows it.
  - After Sync replaced the active snapshot, AI preflight and the snapshot
    summary used the new snapshot instead of the selected location's immutable
    one; the runtime now caches the selected location's snapshot and files
    (Compose resolves the browser snapshot from the location first).
  - AI evidence identity now uses Compose's source-specific
    `source-selection`/`source-workspace-selection` instead of the generic
    selection id, and resolver output is unbounded like Compose (the previous
    100-candidate cap was removed).
  - AI findings gained an "Open source" action per persisted candidate id; the
    shell selects the Source page and only non-Weak candidates navigate.
  - Pre-existing suite failures fixed while running tests: aps-ai now checks
    cancellation before resolving the credential; the aps-network HAR fixture
    gained the required `startedDateTime`; the settings color placeholder is a
    constant so the bi-lingual-source scan passes; the read-only metadata open
    canonicalizes symlinked ancestors (macOS /var, /tmp) before SQLite's
    NOFOLLOW open; the storage test compares `SCHEMA_VERSION`.
- Native interaction verification (isolated `/tmp/aps-src-preview` data root with
  a seeded local fixture; real OS mouse/keyboard events; screenshots under
  `build/source-workspace/`): workspace selection; Sync with live progress and
  retained selection; AI-upload toggle persisted to
  `source-upload-permissions-v1.json`; Copy location clipboard
  `<workspace>@<revision>:<path>:<line>`; directory expand/collapse; file
  search filtering paths and revealing ancestors; file switching with
  header/language/viewer update; code search n/m, Enter navigation and highlight;
  drag selection + Cmd/Ctrl+C copying exact source bytes; stale warning after a
  working-tree edit while immutable content stays readable; Remove cascading the
  database and showing the empty state; AI Settings opening the Rust AI settings
  page; AI Analysis freezing a metadata-only request (no source text; 0 source
  candidates without a candidate and 1 with a persisted candidate); CPU-style
  symbol navigation resolving a Probable candidate with Compose reason strings
  and highlighting its line; "+ Local" opening the native directory picker, and
  an end-to-end add showing the pending card with the real directory name and the
  Ready card afterwards.
- Not exercised interactively: Open in IDE (it would launch the user's default
  application), GitHub/AOSP providers (hidden in the Compose UI), Layout
  Inspector Compose-source and AI archived-source rebinding (still open), and
  packaged/IME/accessibility acceptance beyond real keyboard input and the
  native picker. This pass is functional and visual smoke verification, not the
  full acceptance matrix.

Honest gaps:
- Only the Local provider is implemented. Compose hides GitHub/AOSP behind
  `REMOTE_SOURCE_WORKSPACES_VISIBLE = false`; Rust lists only Local workspaces
  and a persisted remote row fails refresh with an explicit unavailable error.
- CPU symbol navigation and AI-result candidate opening are wired (non-Weak
  candidates only). Layout Inspector Compose-source candidates and
  archived-source rebinding still need their Rust UI surfaces.
- `SourceWorkspacePhase::Partial` exists but is never emitted (as in Compose).
- Workspace IDs are digest-derived instead of Compose UUIDs (not user-visible).

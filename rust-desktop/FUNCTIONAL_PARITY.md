# Compose Functional Parity Audit

Date: 2026-11-11. Status: **in progress, not full parity or replacement acceptance**.

The active objective is to test all functionality and implement missing Rust
workflows from Compose. All exposed Compose workflows remain in scope. A disabled
button, a navigation route, a source-string test, a successful import, or a green
workspace suite does not prove that its complete workflow is implemented.

## Evidence Rules

- Keep Compose and its data/interaction contracts as the behavior oracle.
- Use the same authorized artifact for cross-engine comparisons. Keep missing,
  ambiguous, sampled, bounded and partial evidence explicit.
- Separate domain regressions, same-input data parity, native interaction,
  device/Agent/tool execution, performance and packaged/platform acceptance.
- Preserve the dirty worktree and independent Rust data/configuration roots.
  No commit, push, install, root, permission changes, provider requests or source
  upload are implied by this audit.
- Current run artifacts and raw logs: `../build/rust-parity-audit-20261111/`.
  Earlier audit reports describe earlier source revisions, not current acceptance.
- Continued current evidence: `../build/rust-parity-continuation-20261111/REPORT.md`.
  Historical test-deferral instructions are superseded by the active test request.
- Latest capture/menu continuation:
  `../build/rust-parity-capture-recents-20261111/REPORT.md`.

## Connected In This Audit

- **Inspector findings:** ported the four deterministic Compose structural rules:
  invisible node, more than 10 direct children, at least three substantially
  overlapping siblings, and hierarchy depth above 10. Severity filtering, counts,
  node selection and ancestor reveal are connected. Retention and overlap work
  budgets are visible; these rules do not establish GPU cost or recomposition.
- **Method Recording:** shared targets, real `am profile` capture, bounded duration,
  stop/cancel, process-instance checks, owned artifact cleanup and existing page
  publication. Standard text-first ART v2/v3 now imports alongside the previous
  binary-first fixture formats. CPU-only shell actions were removed from this
  page. API discovery handles unrelated multiline Android properties.
- **Battery sessions:** private atomic, digest-bound saved evidence and catalog;
  background list/save/open/remove, search, restart restore and menu handoff.
  Imported/captured identities stay distinct. Restored reports are not live;
  the exact matching historical capture bundle can still be exported.
- **GPU catalog:** restore and revalidate locations after restart; persist import,
  digest-bound relocation and catalog-only removal without deleting source files.
  Corrupt catalogs are not silently replaced with empty catalogs.
- **Network exports:** whole-session minimized JSON/CSV and explicitly partial
  HAR exports, independent of the visible page/filter. Source mutation,
  cancellation, limits and create-new destination rules apply. Missing wall time,
  traffic content and unknown transport outcomes are not synthesized.
- **Capture corrections:** Warm launches use supported Intent flags preserving
  NEW_TASK/CLEAR_TASK semantics. CPU and shared Perfetto recorder repairs are
  tracked with their focused and device evidence in the run directory.
- **Network Agent build:** repaired the opt-in parent/child AGP/KGP classloader
  boundary without changing pinned dependency versions. Forced offline Kotlin
  compilation and desktop opt-out configuration have separate evidence.
- **CPU loss accounting:** ported the Compose distinction between parsed samples,
  producer-reported samples and lost samples through the native engine, UI and
  JSON/HTML/session exports. The existing populated device artifact now reports
  249 parsed / 249 reported / 0 lost, rather than false 249-sample loss.
- **CPU callgraph configuration:** DWARF, Frame Pointer and None now control the
  frozen request and actual recorder argv. Mode changes do not mutate a prepared
  plan; fake-execution lifecycle coverage exercises all three modes.
- **CPU sampling configuration:** five Compose templates, editable event,
  Frequency/Period and USER/KERNEL/BOTH now share one draft between Settings and
  capture planning and reach actual execution. An executed Compose oracle supplies
  46 committed comparison cases with source hashes. Period values are never saved
  as Hz; mode-only switches to Frequency now persist valid Hz defaults. Invalid
  inputs stay visible and cannot create a new plan. The CPU toolbar, Tools menu
  and capture shortcut now open the planner without starting recording.
- **Inspector recent archives:** async initial/entry reads, ten-file menus,
  duplicate-name disambiguation, reopen via the existing importer, and Clear Menu
  are connected. A UI-ordered write queue prevents delayed older records/loads
  from undoing a clear. Clear removes only Layout history, not archive bytes or
  other sources. These model/store/menu regressions do not prove native clicks.
- **Home history:** connected successful Layout/Trace/Memory import publication,
  existing captured Trace indexes, and the existing CPU adapter. Independent
  source failures, round-robin ordering, path deduplication, stale-file filtering
  and truthful capture timestamps have domain regression coverage. Native
  import/persistence succeeded; screenshot and click/reopen acceptance is blocked
  by the current locked macOS session, not counted as passed.
- **Captured Memory history:** retained verified copies and history registration
  commit together off the UI thread after successful publication. Registration
  failures roll back only newly created evidence, and delayed failures cannot
  replace the notice for another snapshot. History clearing does not delete evidence.

## Remaining Workflow Work

This inventory records concrete gaps found in source, not a claim that every
other action has already been accepted. Recheck implementation and runtime
evidence before closing each item.

### Shell, Home And Settings

- [x] Implement Home history beyond CPU for Layout/Trace/Memory, with bounded
  reads, missing/corrupt-source isolation and existing importer activation.
- [ ] Accept the four-source native Home reopen workflow, including locked/unlocked
  session recovery, selection, file-picker failures and narrow-window rendering.
- [x] Implement Inspector Open Recent and nondestructive Clear Menu using the
  shared Layout history, including async ordering and same-name labels.
- [ ] Complete CPU/Trace/Battery menu history and nondestructive clear, plus
  Memory saved-session menu behavior. Compose does not expose a clear workflow
  for Memory/Startup/Network; do not invent one as a missing requirement.
- [x] Connect Simpleperf templates, event, frequency/period and event scope through
  the shared draft, frozen confirmation and actual recorder arguments.
- [ ] Native persistence/reopen, menu, keyboard, high-DPI, IME/CJK, accessibility
  and narrow-window acceptance across the supported platforms.

### CPU

- [ ] Finish CPU capture parity beyond the connected sampling parameters:
  event capability discovery, remaining target forms, fractional/manual duration
  and Stop-and-save. Current duration is 1..300 integer seconds, frequency is
  1..4000 Hz, and cancellation still discards output rather than stopping to save.
- [ ] Correct session-folder opening and remaining raw protobuf, screenshot
  and Simpleperf report export actions.
- [x] Implement persisted Native Rust / bundled Firefox / online Firefox viewer
  selection and explicit open. Bundled mode serves private loopback data;
  online mode saves before opening the official site for manual import, without
  uploading. HTTP/security tests and a local browser fixture cover loading,
  function selection, Flame Graph and Stack Chart; native settings/dialog and
  cross-platform handoff acceptance remain part of the open gates below.
- [x] Add an APS-owned, read-only local Firefox query extension and asynchronously
  prepare AI evidence from the live browser view. The extension leaves upstream
  source/assets unchanged and rejects foreign profiles and stale document/node
  identities. Unit/HTTP tests and a real-browser host round trip cover querying
  and AI evidence validation, not provider execution or packaged/native acceptance.
- [ ] Same-input data and interaction comparisons for Overview, Top Functions,
  Call Tree, Flame Graph and Stack Chart, including large real profiles.
- [ ] Complete native populated capture/conversion/import, interaction and recovery
  acceptance. Backend populated evidence now has 249 real samples and verified
  exports; earlier zero-sample transport tests remain distinct evidence.

### Trace And Shared Perfetto Consumers

- [ ] Complete recent-list clearing and imported-artifact session package paths.
- [ ] Real Native Heap, ART and FrameTimeline data-source acceptance in addition
  to the shared recorder's sched-trace verification.
- [ ] Native browser handoff, bookmarks, timeline navigation, cancellation,
  disconnect and failure recovery on the supported device/platform matrix.

### Method Recording

- [ ] Graphical/zoomable timeline and flame-chart interaction parity; bounded
  interval tables alone are not that workflow.
- [ ] Producer/device format matrix beyond the verified standard v2/v3 paths.
- [ ] Native capture-button, target switching, stop/cancel and export workflows;
  backend capture/import and fake-transport checks do not replace these.

### Memory

- [ ] LeakCanary injection and its Agent/evidence lifecycle.
- [ ] Standard HPROF conversion/export and bitmap comparison workflows.
- [ ] End-to-end Native Heap, ART and Bitmap capture, analysis, export and
  reimport, not inferred from Java HPROF success.
- [ ] Large real-heap memory/cancellation/resource budgets and native details
  navigation across snapshots, references, dominators, diff and leak views.

### Frame And Startup

- [ ] Frame Agent/JankStats and FrameTimeline device workflows beyond offline
  parsers and compatible gfxinfo fallback; preserve source-specific semantics.
- [ ] Startup baseline comparison, compiler/profile preparation, phase evidence
  and validated Open Trace handoff matching Compose.
- [ ] Cold/Warm/Hot repeated experiments, stop/cancel, target changes, export and
  native page interactions. Missing Displayed/Fully drawn evidence stays missing.

### Battery And Network

- [ ] Battery baseline comparison, Historian/history/raw import workflows and
  the separately authorized global-reset workflow exposed by Compose.
- [ ] Battery session/catalog UI restart and capture-bundle roundtrip acceptance.
- [ ] Network authorized bounded traffic-detail/redaction parity and minimized
  session reimport/persistence; current exports are not lossless source archives.
- [ ] Real instrumented-client Agent capture/handshake/stop/drain tests. An Agent
  compile, HAR import, or uninstrumented sample cannot establish capture coverage.

### GPU And Benchmark

- [ ] Reliable GPU device/package/API/capture-time/AGI metadata and related
  artifact relationships; never substitute filesystem mtime for capture time.
- [ ] Verified AGI artifact-open/capture capability and actual tool execution.
  GUI-only/manual launch remains distinct from artifact-open automation.
- [ ] Benchmark real comparable report interaction and Gradle/device orchestration
  with deterministic report association, policies, exports and failure recovery.

### Inspector

- [ ] Continuous inspection/timeline/Auto Scan and its immutable cross-frame
  correspondence semantics.
- [ ] Archive export/roundtrip, screenshot import and recent-list workflows.
- [ ] Full Compose/JVMTI, recomposition and parameter paging native/device
  acceptance; structural findings and compiler reports do not prove them.
- [ ] Source-candidate and archived-source rebind interactions.

### AI And Source

- [ ] Agent/Skill package import, registry, trust/version/removal and execution
  workflows. Keep script execution unavailable until its real sandbox contract
  is implemented and qualified.
- [ ] Actual provider execution only with separate authorization; fake transport
  tests do not establish real-provider acceptance or permission to send data.
- [ ] Large source-workspace and native candidate/rebind acceptance. GitHub/AOSP
  buttons are currently hidden in Compose too; do not invent them as an exposed
  UI requirement or claim remote retrieval is implemented.

## Delivery Gates Still Open

- [ ] Representative immutable Compose/Rust data and performance baselines.
- [ ] GPUI focus/keyboard/selection/accessibility and native interaction matrix.
- [ ] macOS/Windows/Linux package/tool bundling, signing and notices.
- [ ] Large-profile RSS/latency and failure/cancellation budgets.
- [ ] Requirement-by-requirement final switch audit from the migration plan.

Compose must remain available until the complete scope has current evidence.

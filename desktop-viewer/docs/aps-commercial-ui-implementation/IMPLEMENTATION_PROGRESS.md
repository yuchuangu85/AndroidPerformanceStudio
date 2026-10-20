# Commercial UI — implementation and validation progress

This is a continuation checkpoint for the [phased implementation plan](README.md), not an assertion that the full commercial UI is finished.

## 2026-10-16: CPU Profiler reference workspace (page 03)

### Implemented

- `CpuProfilerWorkspace.kt` adds an evidence-only KPI strip above the activity timeline: Samples (lost
  count/rate), Threads (process count), Capture Duration, Effective Sample Rate, Lost / Invalid Samples (only
  when the quality summary records any) and Top Function Share (hottest self weight). CPU usage, energy and
  temperature are never shown because simpleperf samples do not carry them.
- Tabs are displayed as Overview | Flame Graph | Call Tree | Top Functions | Stack | Marker Chart | Marker
  Table through `CPU_PROFILER_TAB_ORDER`. The `ReportTab` enum and persisted selection are unchanged, and
  arrow-key tab navigation follows the display order.
- The toolbar gained Import, wired to the existing offline import/open-session flow. The tab row gained an
  Export menu over the existing report, session, Gecko, folded, pprof, HTML and screenshot actions.
- Flame Graph and Call Tree show a selected-path breadcrumb, and clicking a crumb reselects that frame. The
  flame canvas, layout, navigator, tooltip and details panel are untouched.
- The Detail Inspector shows Total % / Self % / Inclusive / Self / Samples / Threads, library, call path,
  top callees, and Focus flame / Focus call tree / Copy symbol actions.
- Top Functions columns are Function | Library / Path | Total % (with a share bar) | Inclusive | Self |
  Samples | Threads | Navigate. Rows highlight the selection, Up/Down change it, Enter focuses the flame
  graph and Shift+Enter focuses the call tree.
- Call Tree gained Samples / Threads columns, Expand all / Collapse all, and Firefox-style keys (Up/Down,
  Right expand/descend, Left collapse/ascend, `*` expand subtree, Enter focus flame).
- The AI Insight Drawer was already docked with model, evidence scope, confidence, explanation,
  recommendation and Open Source, so it was left as is.

### Verification

- `CpuProfilerWorkspaceTest` covers KPI derivation, hidden cards without evidence, the absence of CPU
  usage/energy/temperature text, tab order, export dispatch, breadcrumb and inspector actions, and keyboard
  navigation in both tables.
- Flame graph goldens pass unchanged. The `macos-device-target-*` goldens were regenerated for the new
  layout; they were already failing on HEAD before this change.
- `DeviceTargetPageBehaviorTest` has 4 failures that already exist on HEAD (it looks for the Settings /
  Back to home buttons that `b76571f3` hid) and are not addressed here.

## 2026-10-15: GPU Analysis Hub reference workspace (page 09)

### Implemented

- The hub now keeps the reference dashboard structure: an analysis toolbar, a four-card summary row,
  an artifact rail, a tabbed analysis center and two insight rails. The device/graphics-context panel
  and the documented artifact table (Kind | File | Device | Package | API | Captured | Status |
  Action) stay intact; the table renders through the shared `StudioDataTable`.
- Page 09 §14.4 keeps the reference frame-profile panels closed, so their regions render the
  documented replacements instead of fabricated metrics: artifact preview/metadata, related analysis,
  external tool capability and open-with-AGI. The tool-capability view names the five closed panels
  and points at AGI/Perfetto; no GPU counter, frame time, shader hotspot, render pass, draw call or
  overdraw value is invented.
- Summary cards count only local index facts: indexed artifacts with a kind breakdown, verified
  available, needs attention and the probed AGI capability. Availability still requires matching size
  and SHA-256, and opening still revalidates the authoritative indexed artifact.
- New: a hub tab/filter state model, `GpuHubModel.kt` derivations (summary, related-by-identity
  grouping, case-insensitive identity search, size/timestamp formatting) and four shared status
  vectors (`ic_stat_artifact`, `ic_stat_verified`, `ic_stat_attention`, `ic_stat_capability`).
- Launch AGI and Import GPU Trace moved from the shell header toolbar into the page analysis toolbar,
  matching page 09 §14.2 "Actions"; Refresh/Configure AGI remain in the header. `GpuIntegrationScreen`
  is retained only for its localization contract and is documented as superseded.
- Responsive: three panes at ≥1100dp with the analysis panel owning the remaining height and
  scrolling its own body; below that the rail and rails stack inside the page scroll, and the summary
  row wraps to two rows below 900dp. Verified at 1440x900, 1440x620, 1280x720, 1064x560.
- 46 EN/ZH strings added (76 → 122 keys); both bundles are key-parity tested.

### Fresh validation and evidence

- GPU module `checkAll` (test + ktlint + detekt for all five subprojects) passes. GPU presentation
  **37 tests**, GPU app **26 tests**, model/index/toolchain suites unchanged and green.
- New/updated suites: `GpuAnalysisHubScreenTest` (13 Compose cases: pane ordering, toolbar dispatch,
  index-only summary, rail search, rail selection, tab switching, recovery actions, device errors,
  device selection, stacked layout, short viewport, expanded panel, unavailable capability),
  `GpuHubModelTest` (9 derivations), `GpuWorkspaceStyleTest` (8 source contracts),
  `GpuIntegrationLocalizationTest` (5 including bundle key parity),
  `GpuAnalysisHubVisualCaptureTest` (2 renders, PNG only when `APS_GPU_HUB_SCREENSHOT` /
  `APS_GPU_HUB_SCREENSHOT_COMPACT` is exported).
- Automated renders reviewed against the reference: 1360x900 dark/Chinese and 1280x720 light/English.
  Evidence: `/tmp/aps-gpu-hub-dark.png`, `/tmp/aps-gpu-hub-compact.png`. This is not pixel-equivalence
  or native-window acceptance.

### Open gates

- `:desktop-app:test` could not run in this worktree: `battery-profiler/battery-app` has concurrent
  uncommitted changes that fail to compile (`Unresolved reference 'BatteryRunWorkspace'`) and blocks
  the desktop-app compile. The desktop-app GPU source contracts were checked by reading the assertions
  instead: `StartupStyleAdoptionTest` (no `StudioWorkspaceHeader(`/`startupWorkspaceColors(`/
  `ViewerTheme(` in `GpuAnalysisHubScreen.kt`), `ProfilerThemeSupportTest` (`HeaderToolbar(`,
  `LocalViewerColors.current`, no fixed hex colours) and `UnifiedDesktopShellTest` route wiring all hold.
- `ui-components:test` has one pre-existing failure unrelated to this slice:
  `UnifiedUiComponentsSourceTest` reports raw colour literals in five untouched `desktop-app` files.
  The new vectors pass `StudioIconRenderingTest`.
- No external AGI launch, artifact open, reveal or relocate was exercised against a real installation,
  and no real device graphics context was read. The reference's GPU counter, frame-timeline,
  render-pass, draw-call and overdraw panels remain intentionally unimplemented per page 09 §14.4.
- Changes remain uncommitted; this records verified local progress, not completion of the plan.

## 2026-10-14: Startup Profiler reference workspace — no date row

### Implemented

- Replaced the vertically stacked results-only presentation with the page 07 reference structure:
  compact mountain hero, Cold/Warm/Hot request tabs, scenario configuration, four colored metrics,
  multi-run waterfall, selectable run table and a 300dp evidence rail. No calendar/date card or row
  is rendered. Below 1100dp workspace width the rail stacks after the main content; both stay scrollable.
- All scenario controls use the existing controller: device/component selection, measured runs,
  compilation/Profile source, start/stop, advanced compilation warmups (preserving every old 0–10
  choice), timeout, Perfetto capture and practical comparison threshold. Native File menu, import/
  CSV/JSON actions, compilation confirmation, cancellation, progress and selectable status remain.
- Metrics are platform TTID, platform TTFD, Agent First Frame and TTID P95; P90, TotalTime, MAD,
  sample/missing counts, evidence confidence and low-tail-resolution warnings remain visible.
  Completely missing metrics display zero available samples without changing the inherited analyzer.
- Waterfalls use only matching same-source milestone endpoints and verified phase durations. Gaps
  remain gaps; platform TotalTime labels are independent measurements, not additive phase totals.
  First five runs are previewed and an out-of-preview selected run replaces the last preview row.
  Phase and platform-only rows both select runs; full run/phase evidence remains available in the rail.
- Baseline Profile success requires verified speed-profile compilation, explicitly declared Baseline
  Profile Plugin provenance, a recorded artifact and no preparation failure. Generic speed-profile
  requests, Macrobenchmark sources and missing evidence do not receive a Baseline-ready badge.
  Trace navigation retains local file validation plus correlation bounds, failures and limitations.
- Startup-only dark surfaces derive from shared theme/accent roles; light mode and other profiler
  palettes are unchanged. The app selector remains searchable after moving into presentation; its
  desktop source-contract test now points to the new control file.

### Fresh validation and evidence

- Eight Startup suites pass: presentation **27**, app **15**, analysis **8**, parser **3**, capture
  **4**, export **4**, storage **2**, protocol **2**: **65 tests**, zero failures/errors/skips.
  Three workspace regressions and four app-wiring regressions first failed against the old layout.
- Startup presentation/app `ktlintCheck`, changed-file Detekt, desktop Kotlin compilation and macOS
  `createDistributable` pass. Packaged Startup app/presentation JAR entries match their built entries.
- Full-module Detekt is not green: **13 existing presentation findings** and **1 existing app
  LongMethod**. All five implicated source/test files match HEAD byte-for-byte and were left untouched.
- Full desktop tests are **171 passing / 1 failing**. The remaining failure is the independently
  modified Network screen's theme source assertion (`ProfilerThemeSupportTest` requires a literal
  `MaterialTheme.colorScheme`). Startup's moved searchable-selector contract passes. Earlier Network
  compile errors were cleared by that separate work; no Network/Frame/GPU sources were edited here.
- Automated screenshots: 1336×904 dark/Chinese reference workspace, 1280×720 dark/Chinese workspace,
  and 1336×904 light/English workspace. Compared against the reference crop and generated a diagnostic
  raw pixel-diff; this is not pixel-equivalence acceptance. Reference illustration, icon treatment,
  density and unavailable sample recommendations still differ intentionally or remain visual gaps.
- Durable local evidence (ignored, not product assets):
  `.omx/artifacts/startup-profiler-ui/evidence-20261014/`. Screenshot comparison:
  `/tmp/aps-startup-20261014-comparison.png`; final integration log:
  `/tmp/aps-startup-final-integration2-20261014.log`.
- Native desktop interaction acceptance could not run: System Events reports `UI elements enabled =
  false`. The app image was built, not installed/launched or substituted for a running application.
  No fresh real-device startup experiment or compilation-state mutation was performed. No commit or
  push; this records bounded implementation and local evidence, not whole-app/release completion.

## 2026-10-14: Frame timeline compact padding and coordinate axes

- Removed the timeline's extra inner padding; it now uses the existing `StudioPanel` 16dp padding
  without changing shared panel tokens or other cards.
- Added a labeled frame-duration ruler (ms), a visible-frame ID axis and aligned dashed grid lines.
  The duration scale contains the longest visible frame; frame IDs come from the actual samples,
  and both axes update with paging and zoom. Axis values are not inferred frame budgets.
- Regressions cover panel inset, duration scales including extreme durations, small capture/window
  labels, paging/zoom updates and pointer selection with the axis gutter. Existing hover-caption
  stability and the single-row compact toolbar remain passing.
- Fixed the formatting issue that stopped the prior final validation. Freshly reran Frame
  presentation **27 tests**, Frame app **30 tests** and desktop app **170 tests**: **227 tests**, zero
  failures/errors/skips. Presentation `ktlintCheck`, changed-file Detekt, desktop compilation and
  `git diff --check` pass.
- Full presentation Detekt still reports **10 existing findings** in `FrameEvidenceText.kt`,
  `FrameEvidenceTextTest.kt` and `FrameClusterPresentationTest.kt`; those files match HEAD and were
  left untouched. Full-module static analysis is not green.
- Reviewed the new automated dark/Chinese screenshot against the reference for this timeline slice;
  it does not establish whole-page visual equivalence or acceptance in the running native desktop
  window. Evidence: `/tmp/aps-frame-timeline-axis-continued-final-20261014.log`,
  `/tmp/aps-frame-timeline-axis-full-detekt-continued-20261014.log` and
  `/tmp/aps-frame-timeline-axis-continued-20261014.png`.
- No application was restarted or installed, and no commit or push was performed. Existing GPU
  and Startup worktree changes were not modified in this continuation.

## 2026-10-14: Frame timeline inline hover caption

- Hovered frame details now follow the deadline-miss marker hint on the same line, separated by
  `|`. Leaving the canvas clears only the hover details, not the caption line's layout space.
- The caption remains a single line with ellipsis overflow. When there are no clusters, its blank
  line still reserves the same height, so hovering smooth frames does not move the analysis panels.
- Two real-pointer Compose regressions cover every frame in three-frame samples with and without
  clusters, including pointer exit, the hint/separator order, and unchanged canvas/panel bounds.
  Both failed before the fix, reproducing a 17px panel displacement, and pass afterward.
- Frame presentation **23 tests**, Frame app **30 tests** and desktop app **170 tests** pass with
  zero failures/errors/skips. Presentation `ktlintCheck`, changed-file Detekt, desktop compilation
  and `git diff --check` pass; the previously documented full-module Detekt findings remain open.
- Evidence: `/tmp/aps-frame-timeline-hover-final-20261014.log` and
  `/tmp/aps-frame-timeline-hover-20261014.png` (dark/Chinese automated rendering with frame #9
  hovered). This is automated verification, not acceptance in the running desktop application.
  No application was restarted or installed, and no commit or push was performed.

## 2026-10-14: Frame timeline single-row compact toolbar

- The timeline title, Previous/Next/Zoom in/Zoom out controls, and Within budget/Jank/Jank cluster
  legend now share one vertically centred row. The four controls reuse `ProfilerCompactButton`, the
  same 24dp shared component as Start Capture, instead of duplicating button styling.
- Existing enabled predicates, paging and zoom anchors are unchanged. A new 1280px Compose
  regression checks shared vertical centres, token-defined button height, initial disabled Previous,
  and the range transitions for Next, Zoom in, Zoom out and Previous. It failed before the change.
- Frame presentation **21 tests** pass with zero failures/errors/skips; `ktlintCheck`, changed-file
  Detekt and desktop Kotlin compilation pass. Frame app **30 tests** and desktop app **170 tests**
  remain passing (their tasks were up-to-date in the final verification run).
- Full Frame presentation Detekt is not green: the 10 existing findings documented below remain
  outside this localized toolbar change. `git diff --check` passes.
- Evidence: `/tmp/aps-frame-timeline-toolbar-final-20261014.log` and
  `/tmp/aps-frame-timeline-toolbar-20261014.png` (1440px dark/Chinese automated rendering).
  This is automated UI verification, not acceptance in the currently running application; no desktop
  application was restarted or installed in this slice. Changes remain uncommitted.

## 2026-10-14: Frame device discovery on workspace entry

- Fixed the retained-page refresh trigger: Frame previously ran device discovery only when its
  controller was first composed. The desktop route now supplies `isActive`; becoming active reruns
  discovery without replacing the retained controller or analysis. Hidden pages do not start this
  entry refresh, and the existing capture guard remains in force.
- The existing selection policy is preserved: automatically choose the sole online device, retain a
  still-online selection/process, and require an explicit choice when multiple devices are online.
- Added four controller regressions for initial automatic selection, connecting after an empty
  first visit, retaining selection without duplicate process discovery, and multiple-device safety;
  added the route/activation wiring regression. The wiring test failed before the fix and passed
  afterward.
- Frame app **30 tests** and desktop app **170 tests** pass with zero failures/errors/skips.
  Frame app `ktlintCheck` and full-module `detekt`, desktop compile and macOS distribution build pass.
- A temporary probe invoked the real default Frame controller and ADB backend (no capture started):
  one online `TCL T702W` was listed, selected serial matched, four profileable/debuggable processes
  loaded, and `errorMessage=null`. Evidence: `/tmp/aps-frame-device-final-20261014.log`.
- This verifies live device discovery/controller selection, not re-entry interaction in the currently
  running desktop window. A new application bundle was built; no running application was stopped,
  replaced or restarted. Changes remain uncommitted.

## 2026-10-14: Frame and GPU review fixes — local verification

### Implemented

- Frame restores the documented five core metrics: Frames, Deadline Miss Rate, P50, P95 and Worst.
  Platform Jank Signal Rate remains separate from deadline misses, alongside the observed refresh rate.
- Activity/UI-state attribution is available in a compact, initially collapsed section; expanding it
  retains the previous five-entry preview with frame counts, platform-jank counts and P95.
- The latest visual instruction takes precedence over the older two-pane text diagram: retain
  clusters, problem frames and frame detail as three adjacent panels, without adding a date row.
  Source/result filters and existing capture/import/trace/layout behavior remain in place.
- GPU artifact availability now requires matching size **and** SHA-256. Same-size replacements and
  unreadable files cannot be opened; a registered alternative can be used only after verification.
  Opening revalidates the authoritative indexed artifact instead of trusting cached UI availability
  or a caller-supplied path. Missing/changed artifacts keep Verify/Reveal/Relocate recovery actions.
- Regression coverage includes exact KPI values, collapsed attribution, unknown-budget rendering,
  1280px three-column bounds, a 10,001-frame timeline with selection/range/zoom, and the GPU viewer,
  integrity, busy-state and device-error action matrix.

### Fresh verification

- Re-executed all nine requested test suites (not only an up-to-date test result): Frame presentation
  20, Frame app 25, analysis 12, export 2; GPU artifact index 14, toolchain 9, app 26, presentation 6;
  desktop app 170. **284 tests, zero failures/errors/skips**.
- Frame presentation and the four changed GPU modules pass `ktlintCheck`;
  `:desktop-app:compileKotlin` and `:desktop-app:createDistributable` pass.
- GPU artifact index, toolchain, app and presentation pass full-module Detekt. Frame changed-file
  Detekt passes; full Frame presentation Detekt still reports **10 existing findings** in unchanged
  `FrameEvidenceText.kt`, `FrameEvidenceTextTest.kt` and `FrameClusterPresentationTest.kt`. Those files
  were compared byte-for-byte to HEAD and left untouched; full-module static analysis is not green.
- The built Frame presentation, GPU app and GPU presentation JARs match their packaged counterparts
  entry-for-entry (53, 83 and 54 entries). Container hashes differ because packaging rewrites ZIPs;
  no class/resource entry differs.
- Local evidence: `/tmp/aps-frame-gpu-fresh-tests-20261014.log`,
  `/tmp/aps-frame-gpu-final-20261014.log`, `/tmp/aps-frame-gpu-static-desktop-20261014.log`, and
  `/tmp/aps-frame-profiler-verified-20261014.png` (1440px dark/Chinese automated rendering).

### Open acceptance gates

- Automated bounds/rendering do not prove reference-image visual equivalence or native packaged-GUI
  acceptance; the reference's visual styling still needs acceptance on the actual application.
- The 10,001-frame test proves bounded navigation/rendering structure, not measured recomposition
  counts, interaction latency or a performance benchmark.
- No fresh real-device capture or external AGI launch/open acceptance was performed in this slice.
  The macOS app was built, not installed or substituted for an already running application.
- Source changes remain uncommitted; this entry records verified progress, not completion of the full
  commercial UI plan.

## 2026-10-14: In-app Macrobenchmark orchestration

### Implemented

- The Benchmark workspace can now execute the same plan the CLI exposes as `macro-run` without leaving
  the app: project root, Gradle task, benchmark filter, compilation mode, startup mode, scenario,
  warmups, iterations, trace directory, result JSON path and timeout, validated before a plan is built.
- `:benchmark-cli`'s runner gained a cancellation hook and a per-chunk output listener. The desktop
  caller polls the cancel flag (200 ms) and destroys the Gradle process, so "cancel" is a real stop
  rather than a UI-only state; a stopped run reports `cancelled` and is never reported as success.
- The dialog streams live output, reports exit code / timeout / cancellation, and on a clean exit
  imports the produced JSON into the history store and makes it the current run, comparing immediately
  when a baseline is already selected.
- Result discovery stays honest about where AndroidX writes the file: an explicit path is used only
  when it exists, otherwise the newest `benchmarkData` / additional-output JSON under the project is
  chosen (skipping `.git`, `node_modules`, `.gradle`, `.idea`).

### Verification

- `:benchmark-app:test` covers form validation (project / warmups / iterations / timeout), plan mapping
  and result discovery; `:benchmark-cli:test` now covers cancellation in addition to the timeout.
- All benchmark module tests plus `:desktop-app:compileKotlin` and `:desktop-app:test` pass.

## 2026-10-13: Benchmark & Reports workspace (page 10)

### Implemented

- `:benchmark-model` gains the versioned release gate contract from the plan (§15.4): `GateDecision`
  (PASS / FAIL / INCONCLUSIVE), `GateReason`, `ReleaseGatePolicy` (policy version, blocking metric
  patterns, regression allowance, minimum confidence, fail-on-regression, allow-inconclusive) and
  `ReleaseGateResult`, plus the trend types `MetricHistoryPoint` / `MetricHistory`. Per §2.3 the
  design's 86/100 score is **not** implemented: the gate decision replaces it.
- `:analysis-regression` gains `ReleaseGateEvaluator`: environment incompatibility outranks the
  regression verdict (→ INCONCLUSIVE), a regression below the policy minimum confidence can only make
  the gate inconclusive, and blocking patterns plus the allowed-regression count decide FAIL.
- `:benchmark-storage-sqlite` persists run identity (`version_name`, `git_commit`, `api_level`) with
  an in-place column migration, and adds `metricHistory(case, metric, limit)` and `listMetricKeys()`
  so the trend plots stored history instead of the current pair only.
- `:benchmark-export-adapters` adds `BenchmarkPdfExporter` (dependency-free PDF 1.4 writer),
  gate-aware `writeJson` / `writeCsv` overloads that carry the policy version and compatibility
  issues, and `BenchmarkReportIndexStore` (report catalogue; deleting an index entry never deletes
  the user's file).
- `:benchmark-presentation` replaces the comparison-card list with the commercial workspace:
  baseline/current selectors, compare/import/export PDF/export CSV/share/settings toolbar, four
  summary cards, the comparison table (search plus severity/status filters, verdict and confidence),
  a metric trend chart that flags mixed environments and low-sample points, and the Report Summary
  column (decision, reasons, counts, policy, recent reports, generate). The plan's inspector columns
  replace the mock's Owner/Status columns, which have no evidence source.
- `:benchmark-app` loads stored runs as selector options, re-parses their source JSON for a
  comparison, writes exports through the report index, and adds the compare-settings dialog.
- Application version bumped to `0.6.0`.

### Verification

- `:benchmark-model:test`, `:analysis-regression:test`, `:benchmark-storage-sqlite:test`,
  `:benchmark-export-adapters:test`, `:benchmark-presentation:test` and `:benchmark-app:test` pass,
  including the new `ReleaseGateEvaluatorTest`, `BenchmarkReportsStateTest`,
  `BenchmarkReportsLocalizationTest`, the metric-history store test and the PDF/CSV/JSON/index
  exporter tests.
- `:desktop-app:compileKotlin` and `:desktop-app:test` pass; `ProfilerThemeSupportTest` now covers
  all four new presentation files (theme roles, no fixed light-only colors).

## 2026-10-11: Compose inspection agent built entirely in-repo

### Implemented

- The four Compose inspection agent artifacts are now produced by
  `:layout-inspector:compose-agent-build` instead of a Bazel build inside a full AOSP Studio
  checkout: the three dexed jars (`service`, `payload`, `view-inspector`) and the per-ABI JVMTI
  agent `lib_ui_inspector_agent.so`.
- Four upstream snapshots are vendored with provenance: `third_party/aosp-ui-inspector`,
  `third_party/androidx-inspection` (the `androidx.inspection` API is not published to any public
  Maven repository), `third_party/aosp-transport-native` and `third_party/aosp-slicer`.
- Staging mirrors the snapshots, applies `third_party/aosp-ui-inspector-patches/` (the per-session
  token handshake), and retargets the Android Studio shaded protobuf onto the public runtime.
  Packaging mirrors the upstream Bazel targets: jarjar relocation of `kotlin`/`kotlinx.coroutines`,
  `d8` dexing, the `classes.dex` layout, and the payload supplying the shared runtime to the View
  inspector through its DexClassLoader parent.

### Verification

- `:compose-agent-build:composeAgentBundle` writes the bundle plus `manifest.properties`, and
  `:compose-agent-build:verifyComposeAgentBundle` asserts the layout, manifest keys, checksums and
  dexed jars.
- All three `.so` files report zero undefined `profiler::`/`slicer::` symbols and export
  `Agent_OnAttach`; every jar contains `classes.dex`; the payload carries `androidx.inspection` and
  the protobuf runtime but not the service bridge; the View inspector has no unrelocated `kotlin/`
  classes.
- `:desktop-app:test` still passes.
## 2026-10-11: Settings sidebar icons

### Implemented

- Every settings page now carries a leading icon in the sidebar through
  `SettingsPage.settingsIconResource`, rendered by the existing `SettingsSidebarRow` leading-icon
  slot.
- Four new vectors in the shared ui-components resources: general (sliders), configuration (folder),
  AI (sparkle) and about (info). Layout Inspector and Simpleperf reuse their navigation icons.
- The icon slot is a square 16dp box so the 24dp vectors are not stretched by the row height.

### Verification

- `:desktop-app:test` passes; `ApplicationSettingsDialogTest` checks every page maps to an icon and
  that all six vectors exist as SVG.
## 2026-10-11: Custom theme colour picker with hex entry and default reset

### Implemented

- `ApplicationUiSettings.themeColor` is now a raw `Color` instead of an enum constant. The eight
  presets stay as one-click swatches; custom colours persist as `#RRGGBB` (or `#AARRGGBB`), and the
  legacy preset keys ("royal_purple") still load, so existing profiles are unaffected.
- The Theme Color section splits into two columns: the eight presets in a two-column grid on the
  left, and the custom picker on the right with the hex field and a filled Default button below it.
- `ThemeColorPicker` is an in-app HSV picker: a saturation/value plane over a hue rail, both click-
  and drag-driven. HSV is held separately so dragging never loses the hue once saturation hits zero.
- The hex field accepts `#RGB`, `#RRGGBB` and `#AARRGGBB` (the `#` is optional) and applies valid
  drafts live; Default restores `DEFAULT_THEME_COLOR` (cornflower blue).

### Verification

- `:desktop-app:compileKotlin` and `:desktop-app:test` pass.
- `UnifiedSettingsStoreTest` covers hex parsing/formatting, the preset round trip and legacy keys;
  `ThemeColorPickerTest` covers the HSV ranges Compose requires; `ApplicationSettingsDialogTest`
  asserts the picker, hex field and default reset wiring.

## 2026-10-11: macOS title bar painted with the theme background

### Implemented

- `MacOsWindowChrome` sets `apple.awt.fullWindowContent`, `apple.awt.transparentTitleBar` and
  `apple.awt.windowTitleVisible=false` on the window root pane, so the native macOS title bar turns
  transparent, the content view covers it, and the native title text (which cannot be recoloured) is
  hidden. The close/minimize/maximize buttons stay native, so dragging, double-click zoom and the
  green full-screen button keep working.
- `MacOsTitleBar` paints the 28pt strip itself: `ViewerColors.canvasBackground` as the background,
  `ViewerColors.primaryText` for the title (so it inverts against the strip in either theme), and a
  `ViewerColors.border` hairline along the bottom edge.
- The strip is composed outside `ViewerTheme` so the fixed point height does not follow the user's
  display scale. Windows and Linux keep their native window frame unchanged.

### Verification

- `:desktop-app:compileKotlin` and `:desktop-app:test` pass.
- `MacOsWindowChromeTest` covers the platform check, the three AWT client properties, the hidden
  native title and the strip binding to the theme background, text and divider.

## 2026-10-11: Menu bar probe - measured cause of the remaining flicker

### Measurement

A throwaway probe drove 24 rapid destination switches in a real window (AWT is available in this
environment) and instrumented the native bar: a property-change listener on `window.jMenuBar` plus a
container listener counting component add/remove, sampling the visible menu count after every switch.

- `window.jMenuBar` changed only twice in total (window startup): the bar is never replaced or
  cleared any more.
- Destinations without a feature menu left the bar with **zero menus**, and crossing that boundary
  removed and re-added the top-level menu every time. A zero-menu native bar is what reads as a
  missing menu bar on macOS and flashes on every visit.
- Switching between pages with different menu structures recreates the top-level `JMenu` every time
  (new identity per switch), because each page's `Menu(...)` is a distinct composable call site.
  Wrapping the published content in `key(...)` did not change that; reusing one menu node needs a
  shared menu shape across pages, which is a larger refactor and was not attempted.

### Implemented

- `WindowMenuBarHost` takes a `fallbackMenu` that is composed whenever the active page publishes no
  menu, so the native bar always has at least one menu. `DesktopAppMainPage` passes a File menu built
  from app-level actions it already owns: open Trace file, analyze HPROF, settings.
- The same probe after the change reports `menus=1` at every sample, including Home and Settings.

### Verification

- Probe output before/after the change (recorded above).
- `ProfilerThemeSupportTest` asserts the fallback wiring so the empty bar cannot come back.
- `:desktop-app:test` passes.

## 2026-10-11: Simpleperf settings merged into one card page

### Implemented

- The Simpleperf entry in the settings sidebar no longer expands into per-section rows; the sidebar is
  a flat list again and Simpleperf is one destination.
- `CompleteSimpleperfSettingsContent` renders every `CaptureSettingsSection` as a card
  (`StudioPanel`) in one scrollable page, each card carrying its own category heading and description.
  The section deep link (`openSettings(SIMPLEPERF)` from the CPU profiler) now scrolls that card into
  view instead of selecting a nested page.
- `SimpleperfSettingsSectionContent` gained `embedded`: when the host owns scrolling and padding the
  section only contributes its header and body. `UserGuideSettingsPanel` gained `showHeader` so its
  embedded card does not repeat the section heading.

### Verification

- `UnifiedSettingsMigrationTest` now asserts the merged page: `items(CaptureSettingsSection.entries`,
  `embedded = true`, `StudioPanel(`, and the absence of `simpleperfExpanded` /
  `onSimpleperfSectionSelected`.
- `UiComponentsDependencyContractTest` was repointed at the current consumers of the shared drawables
  (the navigation icon mapping and the home page icons), because the settings page no longer uses the
  expand/collapse chevrons.
- Offscreen render of Settings -> Simpleperf shows the six category cards; the only repeated heading
  found was the user guide, which is fixed.
- `:desktop-app:test` and `:simpleperf-viewer:simpleperf-presentation:test` pass.

## 2026-10-11: Navigation rail defaults to collapsed and persists its state

### Implemented

- `ApplicationUiSettings` gained `navigationRailCollapsed` (default `true`), stored under
  `application.navigationRailCollapsed`.
- `CommercialAppShell` no longer owns a local `userCollapsed` flag. It takes the persisted value plus
  an `onNavigationRailCollapsedChange` callback, so toggling the rail writes through to the settings
  store and survives a restart.
- `DesktopAppMainPage` wires the preference through `updateApplicationSettings`, which already reports
  persistence failures on the settings page.

### Verification

- `UnifiedSettingsStoreTest`: a fresh profile loads collapsed, and saving `false`/`true` round-trips
  through the stored key.
- `CommercialAppShellTest`: the rail state is a persisted preference, the local flag is gone, and the
  toggle writes through instead of mutating local state.
- Offscreen render at 1600x900 - a width that used to auto-expand - shows the 53 px collapsed rail
  with the default preference, and 215 px when the stored preference is expanded.
- `:desktop-app:test` passes 157 tests.

## 2026-10-11: One native menu bar per window

### Problem

`window.jMenuBar` had three independent writers, so every navigation destroyed and rebuilt the native
menu bar:

- `WindowMenuBarHost` was keyed on `navigator.destination` and allocated a fresh `JMenuBar` per
  destination.
- Destinations without a feature menu (Home, Source Workspaces, Settings, Network, GPU, Benchmark)
  ran `window.jMenuBar = null`, which removes the app menu bar on macOS.
- The outgoing page's `onDispose` could clear the bar the incoming page had just installed, and the
  incoming page could capture `null` and install an empty bar.
- Those Swing writes happened during composition instead of in an effect.

### Implemented

- The window bar is hosted by **Compose's own `MenuBar` control**
  (`androidx.compose.ui.window.MenuBar`) inside `WindowMenuBarHost`. That control creates the
  `JMenuBar` once for its composition and only clears it when the window is disposed, so nothing
  manages the bar by hand any more.
- `ActiveWindowMenuBar` no longer composes menus onto the bar. While a page is the active
  destination it publishes its menu content through a window-scoped, state-backed holder; the
  control's content reads that holder, so navigating swaps the menus inside one composition instead
  of disposing it.
- The hand-rolled `JMenuBar` creation, the preload placeholder menus, the per-page ownership flag
  and the manual `rootPane.revalidate()/repaint()` calls are gone.

### Root cause, confirmed

Rapid switching still failed after a first attempt that only stopped recreating and clearing the bar.
Reading the Compose runtime and UI sources showed why: a disposed menu composition runs
`CompositionImpl.dispose()` -> `applier.clear()` -> `SwingApplier.onClear()` ->
**`JMenuBar.removeAll()`** (`Composition.kt`, `Menu.desktop.kt`). Composing one menu bar per page
therefore wiped every menu on the window bar on every navigation and rebuilt it, so the macOS screen
menu bar churned and could stay empty for a frame.

### Verification

- `ActiveWindowMenuBarTest` (new): menu readiness is reported exactly once per destination.
- `ProfilerThemeSupportTest` asserts the design guards: the shared menu bar module imports and uses
  `androidx.compose.ui.window.MenuBar`, and no longer constructs `JMenuBar()`, calls `setContent(`,
  clears `window.jMenuBar`, or tracks per-page ownership. Feature pages keep using
  `ActiveWindowMenuBar {` and must not import `androidx.compose.ui.window.MenuBar` themselves.
- `:desktop-app:test` passes 155 tests. The two `UnifiedUiComponentsSourceTest` failures remain
  pre-existing and unrelated (they assert on `HomeButton.kt` and raw colors in `SourceCodeViewer.kt`
  / `OneDarkSourceTheme.kt`).
- This repository has no application-level GUI test harness, and packaged GUI acceptance was already
  recorded as blocked, so the fix is covered by design-level unit tests and source contracts rather
  than by driving a real window.

## 2026-10-11: Source Workspaces added to the primary rail

### Implemented

- `AppDestination.SOURCE_WORKSPACES` is now a primary rail entry, placed after Benchmark Regression and
  before the divider that separates Settings, reusing the destination's existing title and icon.
- The command palette no longer adds it a second time; only Method Recording stays palette-only.

### Verification

- Rendered the shell: 源码工作区 appears in the expanded rail with the code-brackets icon.
- `CommercialAppShellTest` asserts the primary rail contains Source Workspaces and still excludes
  Method Recording; `AppHomePageLayoutTest` follows the simplified palette list.
- `:desktop-app:test` passes 155 tests, zero failures.
- This intentionally departs from the plan's "Source Workspaces does not occupy a primary navigation
  slot" note; the plan document was left unchanged.

## 2026-10-11: Compact recent-session rows

### Implemented

- The home page's recent-session rows no longer use a Material `TextButton` for "Open artifact"; the
  button's minimum height was what made every row tall. The action is now a plain clickable label with
  a small hit area, and the row's vertical padding dropped from 5 dp to 3 dp.

### Verification

- Rendered the home page before and after with the app's `LocalMinimumInteractiveComponentSize`
  (32 dp) applied, and measured the row pitch: 58 px -> 36 px, roughly 38% shorter. The first
  comparison used a render without that composition local, which overstated the before height.
- `AppHomePageLayoutTest` asserts the compact row (3 dp padding, no `TextButton`, clickable label).
- `:desktop-app:test` passes 154 tests, zero failures.

## 2026-10-11: Device refresh no longer flashes the loading state

### Implemented

- `AppHomePage` no longer clears `deviceOverview` before reloading it. Pressing "Refresh devices"
  now updates the panel in place; only the very first load shows the loading state, because the state
  still starts empty.

### Verification

- A temporary UI test clicked the refresh control and counted loading-state nodes. With the old code
  the count went 0 -> 1 -> 0 (the visible flash); with the fix it stayed 0 before, during, and after
  the refresh. The test was validated by temporarily restoring the bug.
- `AppHomePageLayoutTest` now asserts the effect never nulls the projection and that the initial
  state stays empty, so first-load feedback is preserved.
- `:desktop-app:test` passes 153 tests, zero failures.

## 2026-10-11: Hover titles on the collapsed rail

### Implemented

- While the rail is collapsed, every entry is wrapped in a desktop `TooltipArea` that reveals the
  destination title in a `RailTooltip` panel after 400 ms. The collapse/expand control gets the same
  treatment using its action label. Expanded entries keep their visible label and show no tooltip.

### Verification

- Temporary UI test: hovering the collapsed Home icon moved the count of `首页` text nodes from 0 to
  1 and the tooltip node rendered its label. The first attempt was invalid because it asserted on
  "Layout Inspector", which the home page also renders as a module card.
- The tooltip is drawn in a separate popup root, so whole-window captures exclude it; the tooltip
  node is captured instead under `build/ui-verification/rail-tooltip.png`.
- `CommercialAppShellTest` asserts the tooltip wiring and that the hover title is gated on the
  collapsed state.
- `:desktop-app:test` passes 152 tests, zero failures.

## 2026-10-11: Collapse control aligned with the navigation icon column

### Implemented

- The rail collapse/expand control now reuses the same geometry as `NavigationRailEntry`: the row
  padding applies only while expanded and the icon is centred while collapsed. Its chevron therefore
  sits on the navigation icon axis instead of floating in the middle of the 216 dp expanded rail.

### Verification

- Rendered both states and measured the bottom of the rail: the chevron cluster is x 24..29
  (centre 26.5) in the 216 dp expanded rail and in the 54 dp collapsed rail, matching every
  navigation icon and the settings gear.
- `CommercialAppShellTest` asserts that both the entry and the collapse control carry the collapsed
  centring rule.
- `:desktop-app:test` passes 151 tests, zero failures.

## 2026-10-10: Collapsed rail narrowed so centring does not move the icons

### Implemented

- `StudioTokens.navigationCollapsedWidth` is now 54 dp instead of 64 dp. With 8 dp rail padding and a
  centred 18 dp icon, 54 dp places the icon's left edge at the same x as the expanded row
  (8 dp rail padding + 10 dp row padding), so collapsing no longer shifts an icon while the icon is
  still centred in the rail. 64 dp cannot satisfy both: a centred icon there lands 5 dp right of the
  expanded position.
- This intentionally departs from the "Navigation Collapsed 64 dp" entry in the design plan's token
  table; the plan document was left unchanged.

### Verification

- Measured both renders: collapsed rail 53 px wide with the icon spanning x 20..33 (centre 26.5, rail
  centre 26.5) and the expanded icon starting at the same x = 20, so the icon neither moves nor sits
  off-centre.
- `StudioComponentsTest` now pins `navigationCollapsedWidth` at 54 dp; `:ui-components:test` and
  `:desktop-app:test` pass.

## 2026-10-10: Compact theme colour swatches

### Implemented

- Settings -> General theme colours are now a four-column grid of 38 dp chips with an 18 dp swatch
  dot and a 12 pt label, replacing two columns of 72 dp cards. The selected chip keeps its colour
  border, tinted container and check mark.

### Verification

- `ApplicationSettingsDialogTest` gained a case pinning the compact swatch metrics
  (`THEME_SWATCH_COLUMNS = 4`, `height(38.dp)`, `size(18.dp)`).
- `:desktop-app:test` passed 151 tests, zero failures.
- Offscreen render at 1400x700 confirmed the two-row swatch grid and the four settings dropdowns
  above it.

## 2026-10-10: Settings becomes a routed page instead of a dialog

### Implemented

- Added `AppDestination.SETTINGS`, so settings is a first-class destination composed in the retained
  content host with its own title, icon, rail entry and selected state.
- Converted `DesktopAppSettingsDialog` into `DesktopAppSettingsPage`: the `DialogWindow`, its dialog
  state, the modal surface and the "Done" footer are gone. The page fills the content host and leads
  with a "Settings" header above its own sub-sidebar.
- The rail settings entry now highlights like every other destination, and both `Cmd/Ctrl+,` and the
  macOS Preferences menu route to the same destination.
- Removed the `showSettings` flag; `openSettings(page)` records the requested sub-page and opens the
  destination, so feature pages can still deep-link into a section.
- Settings no longer needs a shell callback, so `CommercialAppShell.onOpenSettings` and the
  standalone `railSettingsIcon` accessor are gone; the entry uses the destination mapping.

### Verification

```sh
cd desktop-viewer
./gradlew :desktop-app:test --offline
```

- `:desktop-app:test` passed 150 tests, zero failures.
- Source tests that read the dialog file were repointed at `DesktopAppSettingsPage.kt`; the former
  "large and resizable window" case now asserts the page is routed and that no `DialogWindow` or
  `rememberDialogState` remains, and that `showSettings` is gone.
- Offscreen renders at 1400x760 confirmed the settings page inside the shell with the rail entry
  selected, for both the Chinese General page and the English About page.
- `docs/aps-commercial-ui-implementation/images/reports-settings-v2-*.png` remain untracked user
  references; the settings sub-sidebar still uses the existing sections rather than the nine in that
  design board.

## 2026-10-10: Shell top bar removed and settings moved into the rail

### Implemented

- Removed `StudioTopBar`. The shell is now rail + content + status bar, so feature content gets the
  full window height.
- Settings is a regular rail entry pinned at the bottom below a divider, and reuses the shared
  `NavigationRailEntry` row, so it collapses to the icon-only gear like every other entry. The
  `Cmd/Ctrl+,` shortcut and the macOS Preferences menu still open it.
- The Home rail entry now uses the short "Home" string through
  `AppDestination.navigationLabelResource`; `titleResource` keeps the product name for the window
  title and status bar.
- The command palette is still reachable with `Cmd/Ctrl+K`; the product name, destination, and
  artifact context remain in the status bar. The top bar's workspace-context chips are gone with it
  (the current call site only ever passed an empty context).
- Removed the now-dead `WorkspaceContext.contextLabels()` helper and the top-bar-only string imports.

### Verification

```sh
cd desktop-viewer
./gradlew :desktop-app:test --offline
```

- `:desktop-app:test` passed 150 tests, zero failures.
- `CommercialAppShellTest` now asserts the top bar is gone and that the settings entry, the divider,
  and the shared rail row exist; a new case covers `navigationLabelResource` for Home.
- `ProfilerThemeSupportTest` checks rail tokens instead of the removed top bar height.
- Offscreen renders of the full shell at 1500x1200 (expanded) and 1100x1200 (collapsed) confirmed the
  divider / settings / chevron order in both states.

## 2026-10-10: Home dashboard two-column redesign

### Scope

- Home page presentation only. The shell, routing, feature state, and the recent-artifact and device
  projections are unchanged.
- Reference: `images/01-home-dashboard.png`. The plan forbids inventing metrics, so only sections
  with a real data source are rendered.

### Implemented

- Two columns above 900 dp: a scrolling main column plus a 320 dp right rail. Narrower widths stack
  the rail below the main column instead of dropping it.
- Hero banner with the welcome title, subtitle, and reference quote over an accent gradient.
- KPI row reduced to the two real projections (connected devices; recent artifacts shown). Added an
  optional icon to `StudioMetricCard`; both cards now share one intrinsic height.
- Quick actions are four icon cards (Start Capture, Import Trace, Analyze HPROF, Run Startup Test)
  that route into existing capture/import flows. They stay a four-up row until the column narrows.
- Analysis modules reuse the shell navigation icons and destination titles in an adaptive 3/2/1 grid.
- Recent sessions table keeps only columns backed by real artifacts: name, package, type, started,
  action.
- Right rail: localized date card and the connected-devices panel (moved out of the main column).
- Sections without a data source (jank alerts, regression counts, recent reports, AI insights) are
  intentionally not rendered.
- Added 18 EN/ZH strings and four vectors (device, history, play, import).

### Verification

```sh
cd desktop-viewer
./gradlew :desktop-app:test --offline
./gradlew :ui-components:test --offline --tests "com.androidperformancestudio.ui.studio.StudioIconRenderingTest"
```

- `:desktop-app:test` passed 149 tests, zero failures, including the 9 rewritten
  `AppHomePageLayoutTest` cases.
- `StudioIconRenderingTest` now renders all 19 bundled vectors through `painterResource` and `Icon`
  and asserts each paints visible pixels.
- Offscreen renders at 1500x1400 for both locales confirmed the two-column layout, the four-up quick
  actions, equal-height KPI cards, and real recent-session rows.
- Three source-wiring tests that asserted the previous home markup
  (`RecentArtifactNavigationWiringTest`, `SourceWorkspaceLocalizationTest`) were updated to the new
  wiring while keeping their intent.
- The pre-existing unrelated failures noted in the previous entry are unchanged.

## 2026-10-10: Shell navigation rail icons and icon-only collapse

### Scope

- Presentation-only batch for the shell navigation rail. Controller, Model, capture, archive, and
  routing contracts are unchanged; no destination was added or removed.
- The reference is `images/01-home-dashboard.png`, which shows one line icon per navigation item and
  an icon-only rail.

### Implemented

- Added 15 SVG vectors under `ui-components/src/main/composeResources/drawable/`: one per
  `AppDestination` plus rail collapse/expand chevrons.
- `AppDestination.navigationIconResource` maps every destination to its icon, so an unmapped
  destination is a compile error.
- `StudioNavigationRail` now draws the projected icon beside the label. When the rail is collapsed
  it renders the icon alone and keeps the label as the icon `contentDescription`, replacing the
  previous first-character label truncation.
- Both states share one rail row component. Expanded rows start at a constant offset; the collapsed
  rail drops `contentPadding` to zero and centres the icon, so it lines up with the collapse control.
- The rail footer toggle is now a chevron icon (it already toggled collapse); the top bar control and
  the below-1280 dp auto-collapse are unchanged.
- Added `StudioTokens.navigationIconSize` (18 dp), `navigationIconGap` (10 dp), and
  `navigationItemHorizontalPadding` (10 dp), the fixed leading offset the icon keeps across collapse.

### Verification

```sh
cd desktop-viewer
./gradlew :desktop-app:test --offline
./gradlew :ui-components:test --offline --tests "com.androidperformancestudio.ui.studio.StudioNavigationIconTest"
```

- `:desktop-app:test` passed 145 tests, zero failures, including three new `CommercialAppShellTest`
  cases: distinct icon per destination, rail icon rendering plus icon-only collapse wiring, and the
  presence of every icon drawable.
- `StudioNavigationIconTest` renders all 15 vectors through `painterResource` and `Icon` and asserts
  each paints visible pixels, so a malformed SVG fails in tests instead of showing a blank rail.
- The shell was rendered offscreen at 1400x640 (expanded) and 900x640 (collapsed). The expanded rail
  shows icon plus label with the selected accent; the collapsed rail shows the icons alone.
- Measured the collapsed rail: every primary icon, the settings gear, and the collapse chevron share
  the same horizontal centre, so the collapsed rail is centred consistently.
- Pre-existing, unrelated failures were left untouched: `UnifiedUiComponentsSourceTest` (the
  `HomeButton.kt` accent assertion, and raw colors in `SourceCodeViewer.kt` / `OneDarkSourceTheme.kt`)
  and `:ui-components:detekt` (22 issues in files this batch did not modify).

## 2026-10-10: Phase 4 — Layout Inspector resize and visibility

### Scope and baseline

- Branch: `feat/aps-commercial-ui`; starting HEAD: `0f4f8b75`.
- The worktree was clean before this batch. No commit or push was requested or performed.
- This batch changes presentation layout only. Controller, Model, capture, archive formats, and source-resolution contracts are unchanged.
- Before editing, the existing `PaneLayoutTest` and `FindingsLayoutTest` passed: 15 tests, no failures.

### Implemented

- Fit and drag calculations now reserve width and splitters only for visible side panels.
- Hidden panel widths remain remembered preferences. Toggling visibility and fitting a smaller window do not overwrite those preferences.
- The hierarchy-only and properties-only workspaces use regular sizing whenever their own visible layout fits, rather than the three-panel minimum.
- Existing both-visible defaults, bounds, and drag directions are preserved.
- Added nine layout regression tests, including a feasible-width matrix for all four visibility combinations, hidden preferences, canvas minimums, drag clamps, and fit idempotence.
- Added a source-wiring regression test for visibility arguments in the workspace and both drag callbacks. This is not a live interaction test.

### Fresh verification

Run from `desktop-viewer`:

```sh
./gradlew \
  :layout-inspector:presentation:test \
  :layout-inspector:application:test \
  :layout-inspector:analysis-engine:test \
  :layout-inspector:protocol-model:test \
  :layout-inspector:compose-inspection-model:test \
  :layout-inspector:compose-inspection-host:test \
  :layout-inspector:adb-gateway:test \
  :desktop-app:test :desktop-app:createDistributable
```

- Passed 446 tests, zero failures/errors/skips: presentation 221, application 31, compose-inspection-host 12, adb-gateway 40, desktop-app 142.
- The other listed Layout Inspector test tasks are `NO-SOURCE`; they do not add coverage.
- `PaneLayout.kt`, `PaneLayoutTest.kt`, and `PanelVisibilityTest.kt` pass ktlint 1.5.0 using a temporary Gradle init-script task; the repository has no Layout Inspector ktlint task.
- The main page is not lint-clean. Comparing the unchanged HEAD copy with the modified file found 670 versus 657 violations, with no added diagnostic/message counts. Only the touched layout calls were locally formatted; unrelated file-wide lint debt was not swept.
- `git diff --check` passed. Independent read-only review found no defects within the visibility-aware layout scope; this is not runtime acceptance.
- The new macOS app image was built under `desktop-app/build/compose/binaries/main/app/AndroidPerfermanceStudio.app`.
- Compared all 360 ZIP entries in the packaged and built Layout Inspector presentation JARs: identical names and entry bytes. The changed `PaneLayout.class` and `LayoutInspectorMainPageKt.class` also match by SHA-256. The complete JAR files have different ZIP metadata/encoding and do not have equal whole-file hashes.

### Packaged GUI acceptance: blocked, not passed

- Regenerated the existing synthetic 10,000-node archive using `APS_SYNTHETIC_ARCHIVE_OUTPUT=/tmp/aps-synthetic-10k-20261010.apinspect` and reran the full presentation suite. This proves synthetic archive round-trip and presentation projection only, not real-device behavior or GUI performance.
- Launched the exact new app-image path. The process remained alive, and its thread dump reached the Compose application event loop.
- Native computer-use attachment to the exact path returned `cgWindowNotFound` on two attempts. Bundle-ID attachment was ambiguous because the installed app and new image share an ID. The optional foreground-launch API was unavailable.
- Consequently no screenshot, archive import, side-panel toggle/drag, or minimum-window interaction has been accepted. Process startup is not a GUI smoke-test pass. The QA-launched process was terminated after collecting the diagnostics.

### Open acceptance gates and continuation

- [ ] Packaged GUI: import the synthetic archive; verify hierarchy/canvas/details/findings selection, visibility toggles, and separator drags.
- [ ] Actual 1280×720 window, resizing, preferred-width restoration, and navigation-rail collapse interactions.
- [ ] Dark/light, language, display-scale, and shortcut behavior in the running workspace.
- [ ] Synthetic 10k-node UI responsiveness and real-device capture/archive behavior.
- [ ] Phase 4 panel extraction and visual redesign parity against the main plan, followed by the remaining phased migration. The other phases were not comprehensively re-audited in this batch.

Do not promote local tests, source-wiring checks, archive fixtures, or app-image creation into full commercial UI or release completion.


## 2026-10-15: Startup Profiler page 07 — functional reference completion

### Scope and baseline

- Branch `main`, starting HEAD `4b9ae57e45831b688e5f22992e98d5469aa200f1`; initially clean.
- Follow page 07 and its reference image together with the current `DESIGN.md` Startup adoption contract. Keep shared theme/density and omit the decorative banner/date card.
- Changes are scoped to Startup presentation, controller, report import and identity-based comparison. Concurrent Battery, Network, GPU and shared-icon work is not part of this batch and was preserved. No commit/push was requested.

### Implemented

- Kept Cold/Warm/Hot requested modes separate from observed run types; retained real TTID/TTFD/Agent First Frame/P95, sample counts, missing/confidence labels and percentile-resolution warnings.
- Added actual waterfall phase filtering and CSV export callbacks. Filtering preserves original offsets, gaps, independent clock domains and platform totals; changing the selected five-run preview resets unavailable phase filters.
- Added optional build-supplied Profile artifact/hash configuration and controller wiring. Switching away from Speed Profile clears mode-only provenance. UI distinguishes verified compiler state from declared Profile provenance; it does not install a Profile or verify its contents on-device.
- Replaced the old text-only comparison with a current/previous experiment table: real device model, TTID/TTFD/Agent First Frame/P95 and sample counts, plus both TTID evidence sources/confidences, missing samples, localized outcome, percent change, 95% BCa interval, practical threshold, phase differences and incompatibility reasons. Non-TTID values remain descriptive, not separately regression-qualified.
- Added an Import Comparison action. A successful second import retains the previous analysis as baseline; failed/stale imports preserve newer analysis and selection. Current `deviceLocalId` and legacy `deviceSerial` reports round-trip without re-exporting raw serials. Same-installation live/imported identities compare consistently; different devices/targets and invalid identities remain fail-closed.
- Corrected progress wording to experiment progress including compilation warmups. Stop remains available while configuration/import/export are locked.
- Preserved trace navigation and captured-file validation, correlation error bounds, scheduling/Binder/main-thread/frame slices, limitations, compilation confirmation, import/export and run selection.

### Verification

- Startup feature suite: **91 tests**, zero failures/errors/skips (presentation 41, app 20, analysis 10, export 9, capture 4, parser 3, storage 2, protocol 2; model has no test source).
- Presentation/app/analysis/export ktlint passed. Changed-file Detekt passed for all 16 changed/new Kotlin files, using a bounded temporary init script.
- Full Detekt is **not green**: 15 pre-existing presentation findings plus 1 pre-existing `StartupPerfettoEvidenceAnalyzer` long-method finding. All files containing the remaining findings are unchanged from the starting HEAD.
- Earlier desktop integration compilation and 7 Startup-related desktop tests passed. The latest integration rerun was blocked by concurrently edited Battery sources (`session_not_found`, `session_opened`, `sessions_load_failed` resource references; implicit `maxWidth` in `BatteryRunWorkspace`). Do not treat the earlier desktop pass as acceptance of the latest whole worktree.
- Inspected deterministic synthetic captures: dark Chinese, light English, 1280x720 content viewport, and scrolled comparison. Stacked 900px layout, running/disabled states, phase filtering/preview changes, Profile editor, real callbacks and trace-open failure are covered by Compose tests. Captures are rendering fixtures, not device measurements or packaged-GUI acceptance.
- Evidence: `.omx/artifacts/startup-profiler-ui/2026-10-15-functional-evidence/` (captures, logs, scoped Detekt source list/init script). `git diff --check` passed for the Startup scope.

### Explicit boundaries

- The comparison model currently supports current versus previous experiment, not the reference image's arbitrary multi-build/device comparison list. No unavailable build versions, competitor results, optimization diagnoses or KPI improvement arrows were fabricated.
- No real-device acquisition, packaged native-window interaction, signing or Release validation was performed in this batch. Those remain separate acceptance gates.

# Firefox Flame Graph compatibility matrix

Baseline reviewed on 2026-10-30: Firefox Profiler commit `a73f07c297386c77b880c3f4b9d07571953c535e` (2026-10-29).

The installed optional external viewer remains pinned independently in `third_party/firefox-profiler`.

This document is the retained compatibility contract, not a declaration that every row or
acceptance gate has passed. Updating either baseline requires explicit test and difference review.

Android Performance Studio differs in:

1. Native Compose presentation using Firefox-derived geometry and light/dark style rules within the application theme.
2. Android data terminology.
3. The Flame Graph has no horizontal zoom/pan. Stack Chart uses the same 150 CSS-pixel timeline
   gutter and 15 CSS-pixel right margin as the reviewed Firefox chart.

The native panel does not embed React or a WebView. Optional external Firefox Profiler launchers
are separate engines, not evidence of native-panel parity.

## Fixture Matrix

| Row | Coverage | Expected parity |
|---|---|---|
| mixed | Native and managed Android frames in one profile | Equivalent stack structure, search, selection, context actions, and details entry point |
| native | Shared-library frames | Native frames remain selectable, transformable, and resolvable to symbols/binaries |
| managed | ART/JVM frames | Managed frames use the managed implementation category and remain searchable |
| kernel | Kernel frames | Kernel frames survive projection and implementation filtering |
| recursive | Repeated callsites | Recursive stacks project without infinite expansion and can be collapsed |
| source-less | Binary exists without source | Details panel falls back to symbol/disassembly explanation instead of failing silently |
| million-sample | Large SQLite-backed profile | Projection is cancellable, latest-generation-wins, and viewport layout remains below frame budget |
| deep-stack | Deep call chains | SQLite stack loading and flame projection preserve frame order and remain queryable |

The checked fixture in `FirefoxFlameGraphFixtures` creates the first six rows directly. The P0 task
`./gradlew :simpleperf-test-fixtures:runFlameGraphPerformancePoc` records the million-sample and scroll/selection
performance evidence in `docs/poc-results/firefox-flame-graph-<platform>.json`.

## Query and Interaction Contract

- Canonical Android facts and SQLite remain authoritative. Views consume immutable projections;
  generation checks and cancellation prevent stale results from replacing the active query.
- Thread, committed/preview range, implementation, comma-separated stack search, ordered transforms,
  and per-tab forward/inverted direction share one query contract with Call Tree. Search filters sample
  stacks rather than only highlighting rectangles; implementation filtering precedes search and uses captured metadata,
  not display-name guesses. Empty filtered stacks are excluded without changing retained source facts.
- Forward and inverted projections preserve weights and stable call-node paths; sibling ordering
  follows the reviewed functional reference. Surviving selections remain valid; removed selections
  resolve to an eligible ancestor or clear rather than pointing at a different frame.
- Applicable transforms include focus call node/function/self/category, merge call node/function,
  drop function, collapse resource/recursion/direct recursion/function subtree, and undo/remove/clear.
  They operate on stacks, not rectangle visibility. Invalid transforms remain explainable and removable.
- Hover shows transient available facts; click selects, blank click clears, right click opens context
  actions, and double click or Enter opens source/disassembly/symbol details. Selection dismisses
  the hover tooltip. There is no click-to-focus or double-click reset of analysis state.
- Arrow keys navigate parent/child/siblings with direction-aware semantics; deep selection is scrolled
  into view. Flame Graph left/right navigation crosses parent boundaries within the same depth row.
  Copy, context actions, Escape dismissal, and transform commands remain keyboard accessible.
  Narrow-node eligibility follows the pinned query/navigation tests; text inputs retain character keys.
- Details use verified source mappings, then matching binaries for disassembly, then explicit symbol
  facts and an unavailability reason. Build-ID mismatch must never become a valid source/binary match.
  Independent, cancellable detail failures do not invalidate the projection.
- Tooltips/details show only available function, resource, category, implementation, inclusive/self
  weight, sample count, percentage, source/address and range facts. Sample/event weights are not
  exact elapsed function time.
- No samples, empty range, search/implementation/transform emptiness, incomplete data and projection
  failure retain distinct reasons and recovery actions. Session changes clear stale hover/menu/details.

## Visual and Performance Contract

- `FirefoxFlameGraphStyle` retains the pinned light/dark Photon mapping, row geometry, gaps,
  device-pixel snapping, labels, hover/selection/context states, tooltips and detail surfaces.
  Theme changes affect style, not call-stack semantics.
- The Canvas has a stable nonzero size, with visible-row virtualization and bounded layout/text/style
  caches. Normal/inverted orientation must remain correct. No manual 20,000-node paging is required.
- Hover, selection and scrolling must not rebuild the complete call tree or run SQLite projection.
  Work should scale with the viewport; benchmark deep stacks and high node cardinality as well as
  million-sample profiles.
- Virtual accessibility nodes cover eligible visible and selected content. Keyboard focus, semantic
  selection and painted identity agree; selection and errors are not communicated by color alone.

## Stack Chart Implementation

- Stack blocks carry shared call-node IDs and immutable ID/call-node/parent indexes. Distinct caller
  paths do not merge, and filtering no longer stretches the previous sample through excluded samples.
- Hover uses the shared call-node tooltip with the hovered interval's actual duration. Right click
  exposes shared transforms/copy, double click or Enter opens shared details, and Escape dismisses
  transient surfaces. Blank click clears the stack and shared call-node selection.
- Time-proportional and equal-stack-width modes share exact source identities. Search retains AND
  stack semantics and dims individual nonmatching functions using OR matching, except hover/selection.
- Stack-chart drag pans rather than commits a range. Q/Y and E/U zoom, W/K and S/J move vertically,
  A/H and D/L pan horizontally; Ctrl/Shift wheel zooms around the pointer. Horizontal viewport
  changes publish shared preview state, while the underlying stack blocks keep their committed range.
- Call Tree and Flame Graph keep independent inversion preferences; Stack Chart always uses forward
  data. Stack interaction metadata is committed-range data rather than preview-only flame nodes.
- Keyboard movement is frame-driven at 2000 CSS pixels/second with a 500-pixel per-frame cap.
  Pixel wheel/pan/drag retains fractions, stops on release/blur, and respects the pointer zoom anchor.
- Genuine `UserTiming` markers are prepended by name/overlap row, independent of marker search.
  No unsupported marker-to-call-node action is manufactured. Slash focuses the stack filter.
- Function search and per-frame dimming share symbol/resource/source/original-source matching.
  ART method-recording frames pass their captured source filename; absent metadata stays absent.
- Per-row time indexes serve hit testing. Visible geometry and semantic nodes follow a horizontal
  pixel budget, rather than creating an interactive Compose component for every source block.
- New verification entry points: `StackChartIdentityTest`, `StackChartSnapshotTest`,
  `StackChartLayoutTest`, `StackChartInteractionParityTest`, `StackChartVisualParityTest` and the
  high-fragmentation case in `LargeStackChartProjectionTest`. Test presence is not a result.

The query order is preview range, transforms, implementation, then search. Known boundaries still
requiring explicit review include source-map acquisition and persistence through the canonical SQLite
format, browser argument-value payloads and idle-sample producers, exact
native touchpad scaling, and native accessibility/performance on each supported host. The surrounding
APS shell is not a clone of Firefox's full browser workspace. Do not describe this matrix as full parity.

## Local Verification (2026-10-30)

- The complete 15-module CPU test matrix contains 739 tests: 729 pass, 10 fail, no errors/skips.
  All 116 focused chart/input/toolbar/transform/reference/report cases pass. The complete matrix
  includes capture/import/export, legacy SQLite, ART method recording and the desktop adapters.
- `:desktop-app:compileKotlin` passes. Ktlint passes for the six affected CPU modules;
  analysis, application, storage, visualization and ART parser Detekt pass.
- Broad presentation: 188 tests, 10 failures in DeviceTargetPageBehaviorTest (4),
  DeviceTargetVisualGoldenTest (5) and SimpleperfCardSurfaceTest (1). These are not a green
  all-presentation gate. Only the chart-local goldens were deliberately regenerated after review
  for the persistent transform row and browser-compatible label font; whole-device goldens were not replaced.
- Presentation Detekt still reports 15 findings in existing functions/style checks outside the
  chart changes. Its whole-module gate remains red.
- `StackChartVisualParityTest` produces 16 chart images across 900/1280 widths, both themes,
  English/Chinese and time/equal-width modes under `presentation/build/reports/cpu-parity/`.
  Native packaged input latency, real screen readers, Windows/Linux and GPU timing were not measured.

### Fixed Upstream Reference

- `build/reports/cpu-firefox-reference/` contains an independent build of upstream `a73f07c`,
  four light/dark Flame/Stack cases, 64 actual keyboard/wheel operations, 56 passing assertions
  and 16 browser screenshots. The loopback server/browser/temp source were closed/removed afterward.
  The submodule checkout/pin is unchanged. Existing node dependencies were reused, including five
  version differences; this is exact-source evidence, not an exact lockfile reproduction.
- The public `simpleperf-cpu-clock.trace.gz` fixture SHA-256 is
  `e808c0825f79fcc50ab83d5ddf68d186dfd435d0dc7a6598f5b68521602c879a`.
  The selected thread has 778 source samples. Firefox's zero interval and right-open full range
  produce 777 filtered samples; APS uses the same explicit bounds for the comparison, without
  deleting the terminal sample from canonical facts or globally altering the default APS range.
- The same-window Stack Chart comparison uses 1440x586 physical pixels, DPR 1 on macOS.
  Channel tolerance is 8. Complete-image mismatch is 6.9601% light / 6.8203% dark; row-fill
  scanlines at y=14 modulo 16 differ by 0.1022% in both themes. The optional pinned-reference
  check guards row-fill mismatch at 0.5%; the complete-image metric remains diagnostic, not
  a pixel-perfect acceptance claim. See `presentation/build/reports/cpu-parity/stack-reference-diff-*.json`.

## Verification Boundaries

### LoopOnce Same-Profile Repair (2026-10-31)

This is an additional **data/selection parity** acceptance, not a replacement for the earlier
`a73f07c` public-fixture visual baseline. The local browser reference for this investigation is
the current pinned `de9b76abb5570010e6c9aa801f0a2ae020cd8d6f` build.

- The hosted native controller now chooses a genuinely active initial thread and publishes that
  filter with the first result. Empty thread filters remain aggregate; timeline rows no longer
  pretend the first row is selected. Explicit selection and subsequent all-thread queries retain
  their actual scope.
- Native SQLite charts use unit sample weights like the SQLite-generated Gecko profile, without
  rewriting stored `event_count`. Snapshot/Stack Chart metadata carries the weight type. Tooltip
  Running/Self columns use that type; traced milliseconds use adjacent processed sample times,
  not event weights or `sampleCount * interval`. Non-time event weights are not labeled as ms.
- Stable function identity is based on display symbol and resource, not an import-local symbol
  row ID. Distinct call paths remain distinct. Unknown symbols retain address-qualified identity
  consistently in native queries and Gecko export. Inverted Self follows sampled roots; selection
  restoration uses path-termination weights rather than the inverted UI Self column.
- Gecko compact per-thread delta reconstruction and reverse selected-thread tie ordering are
  reproduced for traced timing, separately from original timestamp facts. Committed-range queries
  retain pre-range time history through a flat timing-only query, not out-of-range stack loading.
  Preview crops use their own final interval; search/transforms preserve excluded-sample gaps.
- Corrected an independent Gecko export bug: substring scanning of the combined function/resource
  location classified Kotlin method names containing `.ko` as Kernel. Export and native tooltip
  statistics now share actual-resource classification. Regenerated profiles are required to see
  corrected categories in previously generated `perf_data.json.gz`; original imported
  `gecko-profile.json.gz` byte-copy behavior remains unchanged.

**Real local acceptance:** copied the authorized SQLite fixture to an ignored new directory,
exported with the corrected exporter, and loaded it in the pinned local Firefox site. Browser
network access was restricted to loopback; all 11 probe assertions passed, 9 actual tooltips were
captured, and the browser/server/port were closed afterward. One existing upstream `waiting`
JavaScript error per page remains recorded; it did not prevent chart or tooltip observation.

- Default selected thread: 3,255 samples; its LoopOnce node has **3,242** samples, width
  **99.6006%**, header **4,042ms (100%)**.
- Main thread: 1,158 samples; three separate LoopOnce paths have **1,002 / 89 / 4** samples,
  widths **86.5285% / 7.6857% / 0.3454%**, headers **1,370ms (87%) / 89.6ms (7.7%) /
  4.0ms (0.3%)**. They are not one merged 1,095-sample node.
- All 11 threads: 5,542 samples; the four paths retain **3,242 / 1,002 / 89 / 4** samples,
  widths **58.4987% / 18.0801% / 1.6059% / 0.0722%**. The first header is
  **2,709ms (58%)**. Merged traced time differs from single-thread traced time by design.
- `LocalFirefoxLoopOnceParityTest` passes all three cases against actual browser expectations:
  full ancestry, samples/Self, inclusive/Self weights, width, complete formatted header, and
  category counts. Eight native Compose tooltip images were captured. Source SQLite and original
  gzip hashes are unchanged; regenerated per-thread sample timestamps/counts match the original.

Fresh 15-module CPU matrix: **789 tests, 778 passed, 10 failed, 1 skipped, 0 errors**. The private
browser oracle ran and passed in this matrix; the local export test was separately enabled and
passed (normally skipped without an explicit new output directory). The 10 retained failures are
the same DeviceTargetPageBehaviorTest (4), DeviceTargetVisualGoldenTest (5), and
SimpleperfCardSurfaceTest (1) gates listed above. New weight/identity/selection/tooltip regressions
are green. `:desktop-app:compileKotlin` passes after the concurrent Layout Inspector fix.

Ktlint passes for analysis, storage, export, application and presentation. Detekt passes for
analysis, storage, export and application; presentation still has **17 out-of-scope findings**,
including existing UI complexity/style checks and concurrent copy-message source-test lines.
Its full static gate is not green. Do not use this acceptance to close native performance,
accessibility, arbitrary original Gecko/diff-weight semantics, tooltip pixel/color fidelity, or
Windows/Linux rendering qualification.

Private browser artifacts, preservation checks and module counts are under
`build/reports/cpu-looponce-corrected/`; native observations/images are under
`presentation/build/reports/local-firefox-parity/`. Both are ignored and must remain private.

Reproduce only with an authorized local fixture and local expectations:

```sh
# From desktop-viewer. No fixture paths, credentials or private data belong in source control.
APS_CPU_PARITY_SESSION=/absolute/ignored/copied-session \
APS_CPU_PARITY_EXPECTATIONS=/absolute/ignored/expectations.properties \
./gradlew :simpleperf-viewer:simpleperf-presentation:test \
  --tests '*LocalFirefoxLoopOnceParityTest' --offline --console=plain
```

Existing verification entry points include `FirefoxFlameGraphFixturesTest`, query/transform/projection
tests, `FlameGraphComposeUiTest`, `FlameGraphAccessibilityTest`, `FlameGraphVisualGoldenTest`,
`FlameGraphTooltipVisualGoldenTest`, and `FlameGraphGoldenE2eTest`. Their presence is not a fresh test result.

Acceptance still requires evidence for all of the following:

- Functional matrix, filter ordering, stable identity, linked Call Tree selection, all transforms,
  source/disassembly/fallback, and reason-specific recovery.
- Deterministic light/dark visual review of toolbar, viewport, geometry, labels, tooltip, menu, details,
  empty/failure states and narrow/wide windows, plus clipping and final-fill contrast checks.
- Keyboard-only completion and actual platform screen-reader behavior, not just semantics assertions.
- Million-sample, deep-stack and high-cardinality projection/cancellation, bounded caches, UI-thread
  work, frame timing and native end-to-end input latency with a recorded reference environment.
- Focused unit/property/integration/Compose/Golden tests, formatting/static checks and capture/import/
  export/legacy-session regressions; independent Windows/Linux rendering and clean-runner validation.

The checked macOS JSON records CPU-side model preparation, not GPU presentation or native input latency.
Do not infer Windows/Linux pixel parity or accessibility acceptance from macOS goldens. The separate
[Stack Chart performance plan](../design/2026-10-29-stack-chart-performance-optimization-plan.md)
has partial chart-index/rendering implementation from 2026-10-30; its native performance acceptance
is still open and cannot be closed by Flame Graph benchmarks.

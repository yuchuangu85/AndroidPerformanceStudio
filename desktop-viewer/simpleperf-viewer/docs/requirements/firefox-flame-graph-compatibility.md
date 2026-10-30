# Firefox Flame Graph compatibility matrix

Baseline: Firefox Profiler commit `9dd90d380ee711f209c4dcd89beec244eb6d3654`.

Visual reference: Firefox Profiler commit `faaf1a14affd3c6d8b7342188371079b999abf5b`.

This document is the retained compatibility contract, not a declaration that every row or
acceptance gate has passed. Updating either baseline requires explicit test and difference review.

Android Performance Studio differs in:

1. Native Compose presentation using Firefox-derived geometry and light/dark style rules within the application theme.
2. Android data terminology.
3. The current native Flame Graph supports `W/A/S/D` horizontal viewport zoom/pan in addition to
   arrow-key node selection. This differs from the original no-horizontal-zoom Firefox contract;
   viewport navigation must not change sample weights, query semantics, or selected-node identity.

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
  and forward/inverted direction share one query contract with Call Tree. Search filters sample
  stacks rather than only highlighting rectangles; implementation filtering uses captured metadata,
  not display-name guesses. Empty filtered stacks are excluded without changing retained source facts.
- Forward and inverted projections preserve weights and stable call-node paths; sibling ordering
  follows the pinned functional reference. Surviving selections remain valid; removed selections
  resolve to an eligible ancestor or clear rather than pointing at a different frame.
- Applicable transforms include focus call node/function/self/category, merge call node/function,
  drop function, collapse resource/recursion/direct recursion/function subtree, and undo/remove/clear.
  They operate on stacks, not rectangle visibility. Invalid transforms remain explainable and removable.
- Hover shows transient available facts; click selects, blank click clears, right click opens context
  actions, and double click or Enter opens source/disassembly/symbol details. Selection dismisses
  the hover tooltip. There is no click-to-focus or double-click reset of analysis state.
- Arrow keys navigate parent/child/siblings with direction-aware semantics; deep selection is scrolled
  into view. Copy, context actions, Escape dismissal, and transform commands remain keyboard accessible.
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

## Verification Boundaries

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
remains **unimplemented** and cannot be closed by Flame Graph benchmarks.

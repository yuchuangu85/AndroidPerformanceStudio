# Firefox-Compatible Native Flame Graph Design

- Status: Approved design
- Date: 2026-07-15
- Product: Android Performance Studio Simpleperf Viewer
- Implementation target: Kotlin/JVM 21 and Compose Multiplatform Desktop
- Compatibility baseline: Firefox Profiler commit `9dd90d380ee711f209c4dcd89beec244eb6d3654`

## 1. Objective

Replace the current limited flame graph with a native Compose implementation that matches the applicable functionality and interaction semantics of Firefox Profiler's Flame Graph. The implementation keeps the Android Performance Studio visual theme and Android terminology; it is not a pixel-level clone.

This design specializes the Flame Graph work described by `2026-07-14-firefox-profiler-native-parity-design.md`. It preserves that design's canonical Android profile, SQLite-backed query engine, immutable view projections, and native Compose direction.

## 2. Product Decisions

The following decisions were confirmed during design review:

- Match Firefox Flame Graph functionality and interaction behavior while retaining the current Compose visual theme.
- Replace conflicting current interactions with Firefox behavior. In particular, remove horizontal zoom, click-to-focus, and double-click-to-reset.
- Double-click and Enter open source, disassembly, or a truthful symbol-detail fallback.
- Deliver in independently testable stages, with complete parity remaining the final requirement.
- Implement a native compatibility core rather than embedding Firefox Profiler in a WebView.

Rejected alternatives:

- A WebView embedding of Firefox Profiler would create a second UI runtime, duplicate state ownership, complicate offline packaging, and conflict with the native-theme requirement.
- Adding surface-level interactions to the existing projector would not reproduce filtering, inversion, transforms, or cross-panel query semantics.

## 3. Scope

The completed Flame Graph includes:

- committed and preview time ranges;
- single-thread and merged-thread analysis;
- text and implementation filtering;
- normal flame graph and inverted icicle graph;
- Firefox-compatible call-stack transforms;
- category-aware rendering, labels, selection, hover, and context-menu states;
- mouse, keyboard, clipboard, and screen-reader interaction;
- cross-panel call-node selection;
- source, disassembly, and symbol-detail navigation;
- explicit empty, unavailable, degraded, and failed states;
- automatic virtualization for large profiles.

The scope is functional and interaction parity for the Flame Graph and the shared query capabilities it requires. It does not add unrelated Firefox workspace panels, browser-only data, a React runtime, or pixel-identical styling.

## 4. Architecture

### 4.1 Ownership boundaries

The implementation is divided into four bounded units:

1. **Flame graph query state** owns the immutable user query and selection state.
2. **Call-stack query and transform engine** applies filtering and transforms to canonical samples.
3. **Flame graph projector** converts query results into compact immutable rows and node metadata.
4. **Compose flame graph panel** virtualizes, renders, hit-tests, and dispatches user intents without changing analysis data directly.

The existing `ReportController` must not grow into the query engine. It coordinates panel state and delegates semantic work through explicit interfaces. The current `ReportPage.kt` flame graph section is decomposed into a panel, toolbar, canvas, tooltip, context menu, details area, and empty-state components.

### 4.2 Single immutable state

`FlameGraphState` is the single source of truth for:

- selected process, thread, or merged-thread set;
- committed and preview time ranges;
- search terms;
- implementation filter;
- normal or inverted call-stack mode;
- ordered transform stack;
- selected, hovered, and context-menu call nodes;
- vertical viewport and keyboard focus;
- source, disassembly, or fallback-detail state;
- query generation and loading/error state.

Call Tree, Flame Graph, and later Stack Chart projections consume the same shared query selection. Panel-local hover, menu position, and scroll offset remain local presentation state.

### 4.3 Data flow

The semantic pipeline is:

```text
Canonical profile samples
 -> selected threads and committed/preview ranges
 -> implementation filtering
 -> text search filtering
 -> ordered stack transforms
 -> normal or inverted call-node table
 -> flame graph row and timing projection
 -> visible-row Compose layout
```

The exact ordering and edge behavior are locked by compatibility tests derived from the baseline Firefox selectors. Search filtering drops samples whose stacks do not match; stack transforms modify stack shape. Inversion is computed as a different call-tree projection, not by flipping already-rendered rectangles.

Every asynchronous query carries a generation identifier. Changing any semantic input cancels obsolete work where possible, and results from an older generation can never overwrite current state.

## 5. Data Contracts

### 5.1 Query contract

The query contract contains only stable identifiers and values. UI objects, Compose coordinates, and mutable collections are excluded. It records:

- profile and thread identifiers;
- normalized time intervals;
- parsed search terms;
- implementation classification;
- inversion mode;
- immutable transform descriptors;
- selected call-node identity when it remains valid.

Implementation classification comes from canonical frame metadata. Native, managed/runtime, kernel, and unknown frames remain distinguishable. The UI never guesses a classification from display text.

### 5.2 Projection contract

The projection uses compact immutable columnar storage for hot paths. It exposes:

- stable call-node identifier and parent identifier;
- function, resource, DSO, address, category, and implementation references;
- depth/row and normalized horizontal start/end;
- inclusive weight, self weight, sample count, and total percentage;
- source location and binary/disassembly lookup keys;
- selectable, transformable, source-available, and disassembly-available flags.

Rows own node ordering and normalized timing. Pixel coordinates, text bounds, and colors are derived by the Compose visualization layer.

### 5.3 Identity and ordering

- Call-node identities remain stable across equivalent projections and linked panels.
- If a selected node survives a query change, selection survives.
- If it is removed, selection moves to the nearest visible ancestor; if none exists, selection clears.
- Siblings use Firefox-compatible stable alphabetical function ordering rather than the current descending-weight order.
- Normal and inverted row generation have separate semantic implementations and caches.

## 6. Filtering and Transforms

### 6.1 Search

Search is a sample filter, not a paint-only highlight. It examines every frame in a sample stack and matches the applicable function, resource, DSO, source path, and URL fields. Multiple terms are accepted using Firefox's comma-separated syntax. Empty or whitespace-only terms have no effect.

Search combines with thread, time, implementation, inversion, and transform state through the shared query pipeline. Removing the search restores the unfiltered stack set without rebuilding the imported profile.

### 6.2 Implementation filtering

Implementation filtering changes stack shape and builds a new call tree. Samples whose filtered stack becomes empty are removed. Android-native implementation categories map truthfully from captured metadata; absent metadata remains `unknown` instead of being assigned to a convenient category.

### 6.3 Call-stack transforms

The context menu and keyboard commands support the applicable Firefox transform set:

- focus call node;
- focus function;
- focus function self;
- merge call node;
- merge function;
- drop function;
- collapse resource;
- collapse recursion;
- collapse direct recursion;
- collapse function subtree;
- focus category;
- undo the latest transform;
- remove an individual transform;
- clear all transforms.

Transforms operate on sample stacks in the query engine. They must not be implemented as rectangle hiding, viewport focus, or presentation-only filtering. Invalid transforms are reported and remain removable; they do not silently produce a misleading graph.

## 7. Interaction Contract

### 7.1 Pointer behavior

- Hovering a node shows a non-persistent tooltip.
- Clicking a node selects it and publishes the shared call-node selection.
- Clicking blank canvas clears the selection.
- Right-clicking a node opens the shared Call Node context menu for that node.
- Double-clicking a node opens its source, disassembly, or fallback details.
- The graph has no horizontal zoom or horizontal panning.
- Deep stacks use vertical scrolling; selection changes scroll the selected row into view.

The existing W/A/S/D navigation, Ctrl-wheel horizontal zoom, click-to-focus, and double-click reset are removed because they conflict with the compatibility contract.

### 7.2 Keyboard and clipboard behavior

- Left and Right select eligible siblings.
- Up and Down select the parent or widest eligible child according to normal/inverted orientation.
- Enter opens source, disassembly, or fallback details.
- Escape closes the active menu, tooltip, or details surface in that order.
- Copy copies the selected function name.
- Applicable Firefox transform shortcuts dispatch the same commands as the context menu.
- Nodes narrower than `0.001` of the viewport are skipped during keyboard navigation, matching the baseline selectable threshold.

Keyboard focus, visual selection, and accessibility selection describe the same node.

### 7.3 Tooltip

The tooltip presents only available facts:

- function name;
- category and implementation;
- resource, DSO, or source location;
- inclusive and self weight;
- sample count and percentage;
- preview-range timing when a preview range is active.

Selecting a node dismisses its hover tooltip. A tooltip must not remain pinned as an accidental second details panel.

## 8. Compose Presentation

### 8.1 Layout

The panel contains:

- a compact toolbar for threads, ranges, search, implementation filter, inversion, and transform status;
- a central vertically scrollable canvas;
- an anchored node context menu;
- a transient hover tooltip;
- a bottom source/disassembly/details surface;
- an in-canvas reason-specific empty or error state.

The panel uses existing typography, spacing, localization, and theme tokens. Firefox is the information and behavior reference, not the pixel reference.

### 8.2 Drawing

- Rows use a stable logical height and gap based on the baseline Firefox proportions.
- Normal graphs grow upward from the bottom; inverted graphs grow downward from the top.
- Nodes use category-aware colors with theme-aware foreground contrast.
- Hovered, selected, and context-menu nodes have distinct states.
- Function labels are measured, fitted, and truncated inside available rectangles.
- Rectangle edges and gaps are snapped to device pixels.
- Horizontally invisible or sub-pixel rectangles are skipped during painting without changing their statistical meaning.

### 8.3 Virtualization and caches

- Only vertically visible rows and an overscan margin are laid out and painted.
- The manual "render next 20,000" control is removed.
- Row structure, timing, text measurement, and color lookup use bounded caches with explicit invalidation.
- Hover and selection update presentation state without rebuilding the full call tree.
- Semantic projections are computed off the Compose UI thread and delivered as immutable snapshots.

## 9. Source, Disassembly, and Detail Fallback

Double-click and Enter resolve details in this order:

1. If a verified source mapping exists, open source and select the corresponding line and column.
2. Otherwise, if a matching binary can be resolved, open disassembly at the sampled address.
3. Otherwise, open a fallback panel showing function, DSO, address, library offset, Build ID, and a specific reason source/disassembly is unavailable.

Details lookup is asynchronous and independently cancellable. A lookup failure does not invalidate the flame graph or its selection. Mismatched Build IDs are reported and never treated as valid source or disassembly matches.

## 10. Empty, Degraded, and Error States

The panel distinguishes at least:

- the selected thread has no samples;
- the committed range has no samples;
- the preview range has no samples;
- search removed all samples;
- implementation filtering removed all stacks;
- transforms removed all stacks;
- profile data is incomplete;
- projection or index construction failed.

Each recoverable state offers its relevant action, such as clearing search, broadening a range, selecting another thread, or undoing transforms.

User-facing errors identify the failed stage and a recovery action. Raw process codes such as `PROCESS_EXIT_1` are diagnostic details, not primary messages. Diagnostic records include the profile, thread/range, transform summary, and underlying exception without exposing sensitive paths unnecessarily.

Changing profiles clears stale hover, context menu, selection, and detail state. Obsolete query and detail results are rejected by generation.

## 11. Accessibility

The canvas is paired with a virtual accessibility semantics layer for eligible visible/selected nodes. It provides:

- function, category, implementation, weight, sample count, and percentage descriptions;
- keyboard focus and selection actions;
- context-menu and open-details actions;
- consistent ordering with keyboard sibling/parent/child traversal;
- automatic focus visibility when navigating deep stacks.

All core analysis actions must be possible without a pointing device. Color is never the only indicator of selection, hover, category, or error state.

## 12. Performance Requirements

Performance verification covers profiles with at least one million samples, deep stacks, symbol-poor frames, and high call-node cardinality.

Required properties:

- pointer hover, selection, and scrolling do not trigger complete call-tree reconstruction;
- obsolete filters and transforms cancel or become harmless stale generations;
- UI-thread work is bounded by visible rows and nodes;
- projection caches are bounded and cleared on profile disposal;
- scrolling and hover remain responsive on the repository's reference large-profile fixture;
- no manual progressive-render action is required to reveal valid nodes.

Performance tests record projection latency, cancellation behavior, visible-node count, UI-thread work, and frame timing. Machine-sensitive timing thresholds are calibrated from a checked-in reference fixture and recorded test environment rather than hidden in implementation constants.

## 13. Verification Strategy

### 13.1 Compatibility matrix

Tests derived from the baseline Firefox implementation cover:

- normal and inverted graphs;
- thread, committed-range, preview-range, search, and implementation filtering;
- tooltip content and dismissal;
- click, blank-click, double-click, right-click, and clipboard behavior;
- parent, child, and sibling keyboard traversal;
- the complete transform menu and transform undo/clear behavior;
- linked Call Tree and Flame Graph selection;
- source, disassembly, and fallback details;
- reason-specific empty states;
- deep stacks, narrow nodes, recursion, missing symbols, and missing source.

### 13.2 Test layers

1. **Query unit tests** verify filter ordering, weights, identity, inversion, transforms, and stale-generation rejection.
2. **Projection and property tests** verify row ordering, containment, normalized bounds, stable identity, and absence of sibling overlap.
3. **Compose interaction tests** verify mouse, keyboard, menus, tooltips, focus, and details behavior.
4. **Cross-panel integration tests** verify shared totals, query state, and call-node selection.
5. **Golden profile tests** cover native, Java/Kotlin, kernel, mixed, recursive, unsymbolized, and source-less profiles.
6. **Performance tests** cover million-sample projection, deep-stack scrolling, rapid filtering, and cancellation.
7. **Accessibility tests** verify keyboard-only completion and semantics for selected and visible nodes.

Visual snapshots guard clipping, theme contrast, labels, and state styling. They do not enforce pixel identity with Firefox.

## 14. Delivery Stages

### Stage 1: Compatibility query core

- Add compatibility tests before changing existing semantics.
- Introduce shared immutable query state and generation control.
- Implement filtering, stable identity, normal projection, and inverted projection.
- Split semantic query work out of `ReportController` and `ReportPage.kt`.

### Stage 2: Compose canvas and navigation

- Implement visible-row virtualization, labels, category colors, pixel snapping, and state styling.
- Implement hover tooltip, selection, vertical scrolling, keyboard navigation, copy, and accessibility semantics.
- Remove manual node paging and conflicting legacy interactions.

### Stage 3: Filters and transforms

- Complete search and implementation controls.
- Implement every listed transform, context-menu action, shortcut, undo, and clear operation.
- Synchronize query and selection state with Call Tree.
- Add reason-specific empty states.

### Stage 4: Source and disassembly details

- Integrate source mappings and verified binary lookup.
- Add source and disassembly views.
- Add symbol-detail fallback and independent lookup failure handling.

### Stage 5: Performance and accessibility closure

- Validate million-sample, deep-stack, and high-cardinality fixtures.
- Tune bounded caches and background cancellation.
- Complete keyboard-only, screen-reader, regression, and user documentation coverage.

Each stage must leave the application buildable and tested. Stages are checkpoints, not reductions in final scope.

## 15. Definition of Complete

The work is complete only when:

- every applicable behavior in the compatibility matrix passes;
- search and transforms operate on sample stacks rather than painted rectangles;
- normal and inverted graphs use correct independent semantics;
- Call Tree and Flame Graph share query and selection state;
- source, disassembly, and truthful fallback details work as specified;
- no horizontal zoom, click-to-focus, double-click reset, or manual 20,000-node paging remains;
- million-sample interaction does not reconstruct the complete call tree on hover, selection, or scroll;
- missing samples, symbols, source, binaries, and failed projections have distinct actionable states;
- unit, property, integration, Compose UI, accessibility, performance, build, and static checks pass;
- user documentation describes the Firefox-compatible controls and Android-specific data mappings;
- the upstream compatibility baseline and intentional Android-specific differences are recorded.

## 16. Upstream References

The compatibility contract is based on the following Firefox Profiler sources at the recorded baseline:

- `src/components/flame-graph/FlameGraph.tsx`
- `src/components/flame-graph/Canvas.tsx`
- `src/components/flame-graph/ConnectedFlameGraph.tsx`
- `src/components/flame-graph/FlameGraphEmptyReasons.tsx`
- `src/profile-logic/flame-graph.ts`
- `src/test/components/FlameGraph.test.tsx`
- `docs-user/guide-filtering-call-trees.md`
- `docs-user/guide-stack-samples-and-call-trees.md`

Behavior may be re-evaluated against a newer upstream revision only through an explicit compatibility-baseline update with test changes and documented differences.

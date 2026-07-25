# Firefox Profiler Report Content Parity Design

**Status:** Approved design

**Date:** 2026-07-18

**Product:** Android Performance Studio Simpleperf Viewer

**Implementation target:** Kotlin/JVM 21 and Compose Multiplatform Desktop

## 1. Objective

Rebuild the native report content workspace to reproduce the Firefox Profiler information architecture and interaction model while keeping the existing Android profile model, SQLite storage, native Compose rendering, and truthful Simpleperf semantics.

The completed report workspace has:

- a persistent timeline at the top;
- a lower analysis tab strip containing Overview, Top Functions, Call Tree, Flame Graph, Stack Chart, Marker Chart, and Marker Table;
- a report-level `Show details` toggle aligned to the right of the tab strip;
- a shared stack toolbar containing All Frames, Script, Native, Invert Call Stack, and a Filter Stacks input;
- a lower content area that switches to the selected analysis panel without losing the active time range, filters, inversion state, selection context, or details visibility.

All seven tabs remain visible even when their underlying data was not collected or the current range contains no matching data.

## 2. Reference and Scope

The visual and behavioral reference remains the pinned Firefox Profiler baseline already adopted by the project:

- visual baseline commit: `faaf1a14affd3c6d8b7342188371079b999abf5b`;
- functional compatibility baseline: `9dd90d380ee711f209c4dcd89beec244eb6d3654`.

This work is a native Compose port. It does not embed Firefox Profiler, React, a browser engine, or a WebView.

### Included

- report-level timeline and resizable analysis split;
- seven fixed analysis tabs and their content switching behavior;
- shared frame filtering, stack inversion, and stack text filtering;
- global details visibility with panel-specific selected-item details;
- real Stack Chart, Marker Chart, and Marker Table projections;
- integration of the existing Diagnostics content into Overview;
- Firefox-style compact layout, visual states, empty states, and interaction density;
- cross-panel range, selection, and details synchronization;
- unit, projection, Compose behavior, and visual regression coverage.

### Excluded

- fabrication of Firefox or Gecko events that were not collected;
- hiding tabs merely because data is unavailable;
- using Flame Graph as a placeholder for Stack Chart;
- introducing a new runtime dependency;
- replacing the canonical Android profile model with the Gecko processed-profile model.

## 3. Chosen Approach

Implement a complete native report workspace on top of the existing immutable projection architecture.

Rejected alternatives:

1. A visual-only shell would make the tabs appear complete while leaving Stack Chart and markers as placeholders, violating the requirement that the corresponding content works.
2. Embedding Firefox Profiler would create a browser runtime, JavaScript bridge, duplicated state, packaging cost, and divergence from the native Compose architecture.

The architecture remains:

```text
Simpleperf / canonical Android records
                 |
                 v
          SQLite fact storage
                 |
                 v
   cancellable immutable projections
                 |
                 v
 timeline + selected analysis panel + details
```

## 4. Workspace Layout

The left-side report navigation is removed. The report result pane becomes a vertically structured Firefox-style workspace:

```text
+----------------------------------------------------------------+
| Timeline ruler, process/thread tracks, samples, and markers     |
| Range selection and a draggable lower resize boundary           |
+----------------------------------------------------------------+
| Overview  Top Functions  Call Tree  Flame Graph  Stack Chart    |
| Marker Chart  Marker Table                         Show details  |
+----------------------------------------------------------------+
| All Frames | Script | Native | Invert Call Stack | Filter Stacks|
+------------------------------------------------+---------------+
| Selected panel content                         | Details       |
|                                                | when visible  |
+------------------------------------------------+---------------+
```

Layout rules:

- The timeline is always present and is no longer a report tab.
- The timeline has a practical default height plus minimum and maximum bounds.
- A keyboard-accessible draggable divider resizes the timeline and lower analysis region.
- The seven analysis tabs are always visible in the specified order.
- `Show details` is aligned to the right of the tab strip and is report-global.
- When details are hidden, the selected panel expands to the full width.
- The shared stack toolbar sits below the tabs and above the selected panel content.
- Borders, spacing, typography, selection states, and surface colors follow the pinned Firefox visual baseline in light and dark themes.
- Narrow windows preserve every tab through compact sizing and horizontal overflow rather than silently dropping capabilities.

## 5. Shared Query Toolbar

The shared stack toolbar owns one report-level query state used by every compatible panel.

### Frame implementation choices

- **All Frames:** managed, native, kernel, and unknown frames.
- **Script:** Java, Kotlin, ART, JIT, and other managed-runtime frames.
- **Native:** ELF/native and kernel frames.
- Frames that cannot be classified remain visible only under All Frames.

The UI exposes exactly these Firefox-style choices. The analysis layer may retain more detailed internal categories, but the mapping is deterministic and tested.

### Invert Call Stack

- The control toggles forward and bottom-up stack relationships.
- Top Functions, Call Tree, Flame Graph, and Stack Chart consume the same direction state.
- Switching panels does not reset the direction.

### Filter Stacks

- The input matches functions, libraries/resources, and source information when available.
- Query updates are debounced and cancellable.
- The last stable projection remains visible while a replacement projection is loading.
- Empty results report the active filter and provide a clear recovery action.

### Applicability

- Overview reflects the committed time range and frame implementation filter where metrics are stack-derived.
- Top Functions, Call Tree, Flame Graph, and Stack Chart apply all shared stack controls.
- Marker Chart and Marker Table apply the committed time range but do not incorrectly apply frame implementation or stack direction filtering.
- The toolbar remains visually stable across tab changes; incompatible controls are disabled with truthful semantics rather than removed.

## 6. Analysis Panels

### 6.1 Overview

Overview presents:

- sample count, total event weight, thread count, selected time range, and data quality;
- compact top-thread and top-function summaries;
- the former Diagnostics content as findings below the summary;
- finding evidence and recommendations;
- navigation from a finding to the relevant timeline range, Call Tree node, or Flame Graph node.

The standalone Diagnostics tab is removed without deleting its data or navigation behavior.

### 6.2 Top Functions

Top Functions uses a compact Firefox-style virtualized table containing:

- total/inclusive weight;
- self/exclusive weight;
- sample count;
- function name;
- resource or library.

It supports sorting, selection, keyboard navigation, shared filtering, direction changes, and details synchronization. Row vertical spacing remains dense.

### 6.3 Call Tree

Call Tree provides:

- hierarchical expansion and collapse;
- forward and inverted projections;
- automatic ancestor expansion for search matches;
- selected-path highlighting;
- keyboard navigation and stable selection identity;
- context actions and navigation to related panels;
- right-side details for the selected node.

### 6.4 Flame Graph

The existing Firefox-style Flame Graph implementation remains authoritative for node geometry, colors, hover, selection, tooltips, context menus, keyboard navigation, and source/disassembly resolution.

Its duplicate direction, implementation, and search controls move into the report-level shared toolbar. Flame-specific transforms and transform navigation remain within the Flame Graph panel.

### 6.5 Stack Chart

Stack Chart is a real time-based projection, not a Flame Graph alias:

- horizontal position represents sample time;
- vertical position represents frame depth;
- adjacent samples with the same visible frame may be coalesced without changing selection semantics;
- the committed time range defines the viewport;
- frame implementation, stack inversion, and stack filtering are applied before layout;
- hover and selection identify the exact projected frame and sample interval;
- dragging a time interval commits a new shared range;
- selected frames update the global details region.

Rendering uses viewport-aware materialization and a batched Canvas so large profiles do not create one Compose node per frame rectangle.

### 6.6 Marker Chart

Marker Chart projects `profile_marker` facts into time lanes grouped by process/thread and schema/category where appropriate.

- Point markers and interval markers use distinct geometry.
- Hover exposes name, time, duration, thread, and schema.
- Clicking a marker selects it and updates details.
- Range interaction stays synchronized with the report timeline.
- Dense marker sets use lane packing and viewport filtering.

The panel distinguishes:

- marker data not collected;
- marker source unavailable or failed;
- no markers in the profile;
- no markers in the committed range;
- active marker filtering with no matches.

### 6.7 Marker Table

Marker Table shares its query and marker selection with Marker Chart. It is a virtualized, sortable table containing:

- marker name;
- start time;
- duration;
- process/thread;
- schema/category;
- compact payload summary.

Selecting a row locates and selects the marker on the timeline and Marker Chart. The details region shows the full structured payload. Empty and failure states use the same reason model as Marker Chart.

## 7. Details Region

`Show details` is a global report preference. Switching tabs preserves whether the details region is open, but details content is resolved from the current tab's current selection.

- Overview: selected metric or diagnostic finding evidence.
- Top Functions, Call Tree, Flame Graph, and Stack Chart: function, resource, category, inclusive/self weight, samples, thread information, address/source data, and the relevant call path.
- Marker Chart and Marker Table: name, start, end/duration, process/thread, schema, and formatted payload.

Rules:

- A selection from a previous tab is never rendered as if it belonged to the new tab.
- When the current panel has no selection, the region shows a compact selection prompt.
- Details lookup failures remain inside the details region and do not fail the report or panel.
- Existing source and disassembly resolution is reused for compatible frame selections.
- Closing details does not clear the selection; reopening restores the current panel's selected-item details.

## 8. State Model

The report-level state adds or formalizes:

- selected analysis tab;
- committed time range;
- timeline height;
- details visibility;
- frame implementation mode: All, Script, or Native;
- stack direction: Forward or Inverted;
- stack filter text;
- stable panel-specific selections;
- panel projection loading and failure states.

`ReportTab` contains only:

1. Overview
2. Top Functions
3. Call Tree
4. Flame Graph
5. Stack Chart
6. Marker Chart
7. Marker Table

Timeline becomes a report-level component, and Diagnostics becomes a section of Overview.

State transition rules:

- switching tabs preserves range, toolbar state, timeline height, and details visibility;
- panel-specific selection may be retained for returning to a tab but is scoped by panel type;
- changing a shared query invalidates only projections that consume that query;
- closing or opening a session resets session-derived state without corrupting saved UI preferences;
- reopening a recent session follows the same projection path as opening it directly.

## 9. Projection and Storage Changes

The canonical marker facts and `profile_marker` table remain the source of truth. New immutable view projections include:

- `StackChartSnapshot` with visible time spans, depth, frame identity, selection identity, and weights;
- `MarkerChartSnapshot` with lane assignments and point/interval geometry inputs;
- `MarkerTableSnapshot` with sortable rows and payload references;
- a shared marker availability and failure reason model.

Projection rules:

- queries are bounded by the committed time range;
- database work remains off the Compose UI thread;
- generation identifiers reject stale projection results;
- queries are cancellable and panel failures are isolated;
- marker payload JSON is not repeatedly parsed during Canvas drawing;
- point markers retain point semantics while interval markers use overlap semantics;
- Stack Chart aggregation never changes the reported totals or selected frame identity.

No panel performs direct SQLite access from a Composable.

## 10. Cross-Panel Interaction

- Committing a timeline range refreshes every compatible panel.
- Selecting a Marker Table row selects and locates the same marker in Marker Chart and the report timeline.
- Selecting a stack frame updates details and establishes a compatible selection for Call Tree, Flame Graph, and Stack Chart when stable identity permits.
- Switching frame implementation, inversion, or stack filtering updates all stack-compatible panels together.
- Tab changes never reopen the session database or discard the current analysis context.
- During projection replacement, the UI retains the previous stable result with a lightweight progress indication rather than flashing an empty panel.

## 11. Empty, Loading, and Failure States

The report must not collapse all data absences into a generic empty state.

- **Not collected:** explain that the source did not provide the data.
- **Empty profile:** the source exists but contains no records.
- **Empty range:** records exist outside the committed range.
- **Filtered empty:** active filters removed all matching records.
- **Unavailable/unauthorized:** preserve the underlying availability reason.
- **Projection failed:** show a panel-local retry action and diagnostic code.

A panel-local failure does not replace the entire report with `REPORT_QUERY_FAILED` when other projections remain usable.

## 12. Accessibility and Keyboard Behavior

- Tabs, toolbar choices, the details toggle, and the timeline divider expose selected/expanded/value semantics.
- Keyboard traversal follows the visible Firefox-style order.
- Call Tree, tables, Flame Graph, Stack Chart, and Marker Chart retain arrow-key navigation where applicable.
- Focus is visible in both themes and is never communicated by color alone.
- Canvas panels expose virtualized semantic nodes for visible and selected content.
- Compact controls maintain an accessible hit target without increasing visual row spacing.

## 13. Performance Constraints

- Timeline, Stack Chart, and Marker Chart use batched Canvas rendering.
- Tables and trees use virtualization.
- Rendering materializes only visible time ranges and rows.
- Search is debounced; queries are cancellable.
- Large marker payloads are loaded or formatted on selection rather than for every row.
- A profile with at least one million samples and high marker cardinality must remain interactive during pan, range selection, filtering, and tab changes.

## 14. Verification

### Unit and projection tests

- All/Script/Native classification, including unknown-frame behavior.
- Forward and inverted stack projection consistency.
- Stack filter matching and generation-based stale-result rejection.
- Stack Chart time position, depth, aggregation, filtering, and selection identity.
- Marker point semantics, interval overlap semantics, sorting, range filtering, and payload lookup.
- Marker availability states and panel-local failures.
- Details visibility and panel-scoped selection transitions.

### Compose behavior tests

- fixed seven-tab ordering and switching;
- persistent timeline and resizable split bounds;
- global details toggle persistence across tabs;
- shared toolbar state persistence and applicability;
- Marker Chart/Table linked selection;
- diagnostic navigation from Overview;
- old selection content never leaking into a newly selected tab;
- keyboard and accessibility behavior.

### Visual regression tests

- pinned Firefox baseline comparisons in light and dark themes;
- default, narrow, and wide windows;
- every panel's populated, empty, loading, selected, and failure states;
- open and closed details layouts;
- timeline at minimum, default, and maximum heights.

### Repository gates

- targeted module tests during implementation;
- Kotlin formatting and static analysis;
- complete `./gradlew checkAll --no-daemon`;
- existing Simpleperf open, recent-open, report projection, export, Flame Graph, and legacy-session regressions.

## 15. Completion Criteria

The work is complete when:

- the report presents the persistent timeline and exact seven-tab analysis structure;
- every tab switches to a functioning native view;
- Stack Chart and both marker panels are backed by real projections;
- all frame controls, inversion, filtering, range selection, and details synchronization work as specified;
- marker tabs remain visible with truthful no-data states;
- Diagnostics remains accessible through Overview;
- panel failures are isolated;
- the pinned Firefox visual behavior is covered by golden tests;
- the full repository verification suite passes with no known regression.

# Firefox Profiler Android-Native Parity Design

- Status: Approved design
- Date: 2026-07-14
- Product: Android Performance Studio Simpleperf Viewer
- Implementation target: Kotlin/JVM 21 and Compose Multiplatform Desktop

## 1. Objective

Bring the generally applicable analysis capabilities and interaction semantics of Firefox Profiler into Android Performance Studio as a native Compose Desktop experience. Simpleperf remains the primary source for CPU samples and call stacks. Android-specific producers supply the data that Firefox obtains from Gecko, including scheduling, Binder, frames, runtime events, memory, network activity, screenshots, and application markers.

This is functional and interaction parity, not a visual clone. The implementation follows the existing desktop theme, localization, packaging, and application-shell conventions.

## 2. Definition of Complete

Completion means Android-semantic parity rather than literal browser-data parity:

- General analysis capabilities are implemented natively: multi-track timelines, committed ranges, Call Tree, Flame Graph, Stack Chart, markers, counters, search, filters, stack transforms, cross-panel selection, comparison, and saved analysis state.
- Browser-only concepts are mapped to truthful Android equivalents where a reliable producer exists.
- Missing, unavailable, unauthorized, failed, empty, and inapplicable data are distinct states. The product never fabricates browser events or represents uncollected data as zero.
- Existing Overview and Diagnostics remain as Android Performance Studio enhancements.
- Existing V0.1 Simpleperf capture, import, analysis, and export workflows remain usable throughout delivery.

## 3. Architecture Decision

Use an expanded canonical normalized model backed by SQLite, with columnar immutable projections generated for individual views.

Rejected alternatives:

- Adopting Firefox Processed Profile as the canonical model would preserve Gecko-specific assumptions and make Android extensions secondary.
- Maintaining SQLite and Firefox models as equal long-lived sources of truth would introduce synchronization, cache invalidation, and storage complexity.
- Embedding or porting the React application would violate the selected native Compose Desktop direction.

The architecture is:

```text
Simpleperf / Perfetto / Android instrumentation
                    |
                    v
          Canonical Android Profile
                    |
                    v
       SQLite facts and query engine
                    |
                    v
      Immutable columnar view snapshots
                    |
                    v
 Timeline / Call Tree / Flame Graph / Stack Chart
 Markers / Counters / Network / Compare / Diagnostics
                    |
                    v
          Compose Desktop workspace
```

## 4. Canonical Profile Model

The canonical model records source facts rather than UI-specific derived state. It covers:

- process and thread identity, naming, registration, lifecycle, and grouping;
- samples, stacks, frames, functions, libraries, addresses, and source locations;
- event weights, CPU core, on-CPU and off-CPU state, and context switches;
- categories and subcategories with explicit provenance;
- typed markers and schema-defined payloads;
- counters and memory time series;
- Binder, sched, FrameTimeline, GC, JIT, and other Android slices;
- screenshots and their capture timestamps;
- clock domains, synchronization points, offsets, error bounds, and source intervals;
- lost records, unwind failures, unresolved symbols, truncation, and other quality evidence.

Simpleperf, Perfetto, and application instrumentation are separate input adapters. Each adapter normalizes time and writes to the same canonical contract without overriding facts owned by another producer.

SQLite uses explicit schema versions and tested forward migrations. Existing `.apsession` packages remain readable. Schema migrations operate through temporary artifacts and atomic replacement; a failed migration leaves the source session unchanged and available through the legacy read-only path.

## 5. Query and Transform Engine

A dedicated query and transform layer owns analysis semantics:

- nested committed ranges with backward and forward navigation;
- process, thread, event, category, implementation, and track filtering;
- function, resource, library, URL, and marker searches where the corresponding fields exist;
- focus, merge, drop, collapse, recursion collapse, implementation collapse, and invert transforms;
- Call Tree, reverse Call Tree, Flame Graph, Stack Chart, Marker Chart, Marker Table, Counter, Memory, Network, and comparison projections;
- cancellable queries, generation-based stale-result rejection, bounded caches, and explicit invalidation.

Compose panels consume immutable snapshots. They do not issue direct SQLite queries. The current `ReportController` is split into workspace state, a query coordinator, and bounded panel controllers. Large UI files are decomposed by panel and track ownership.

## 6. Workspace and Interaction Model

The report workspace contains:

- a global toolbar for profile metadata, search, committed-range history, import/export, and settings;
- a virtualized multi-process and multi-thread track area;
- CPU, sample, marker, counter, memory, screenshot, Binder, sched, FrameTimeline, GC, and JIT tracks when their data is available;
- lower analysis panels for Call Tree, Flame Graph, Stack Chart, Marker Chart, Marker Table, Counter/Memory, Network, Compare, Overview, and Diagnostics.

Interaction contracts:

- Selecting and committing a time range updates all compatible panels.
- Tracks can be shown, hidden, pinned, reordered, merged, or isolated.
- Samples, markers, call nodes, and ranges are bidirectionally linked across panels.
- Call Tree supports forward and inverted views, text search, category and implementation filters, and stack transforms.
- Keyboard shortcuts, context menus, breadcrumbs, stack copying, and source navigation are first-class and testable.
- During pan and zoom, rendering prioritizes responsiveness; exact aggregation may replace a provisional projection asynchronously.
- Browser-specific Network concepts are represented by captured Android network events. When no producer was enabled, the panel explains the missing producer rather than showing an empty result.

The visual language follows the existing Compose Desktop application. Firefox Profiler is the behavioral reference, not a pixel reference.

## 7. Data Flow and Failure Isolation

The processing sequence is:

```text
Capture raw artifacts
 -> parse each source independently
 -> calibrate clocks and validate quality
 -> atomically persist canonical facts
 -> build required indexes
 -> generate cancellable view projections
 -> render linked panels
```

Rules:

- Simpleperf owns CPU samples and call stacks. Perfetto enriches scheduling, frames, Binder, and counters without replacing Simpleperf facts.
- Every source records its clock domain, synchronization evidence, error bound, and valid interval.
- Sources that cannot be aligned reliably remain on isolated tracks with a visible quality warning.
- Failure of one source does not invalidate independently usable sources.
- The UI distinguishes no events, not collected, unavailable, unauthorized, parsing failed, and not applicable.
- Generation identifiers prevent results from obsolete queries from overwriting the current selection.
- Raw `perf.data`, traces, mappings, symbols, screenshots, command logs, and conversion evidence are retained.
- Unresolved frames display address and library offset. Supplying mappings, unstripped libraries, or `binary_cache` triggers re-symbolication without recapture.
- Network records, screenshots, and application markers remain local by default. Any future sharing workflow must explicitly disclose its privacy scope.

## 8. Delivery Stages

### Stage 1: Profile Core

- Expand the canonical model.
- Add SQLite v2 migrations and legacy-session compatibility.
- Introduce query cancellation, generation control, caching, and immutable projections.

### Stage 2: Core Analysis Parity

- Add the multi-track timeline and committed-range history.
- Complete Call Tree, reverse Call Tree, Flame Graph, and Stack Chart parity.
- Add search, category and implementation filters, invert, and stack transforms.

### Stage 3: Markers and Counters

- Add typed marker schemas and payloads.
- Add Marker Chart, Marker Table, counters, memory series, and application-defined markers.

### Stage 4: Android Enrichment

- Add Perfetto clock alignment and ingestion.
- Add sched, Binder, FrameTimeline, GC/JIT, screenshots, and Android network events.

### Stage 5: Symbols and Source

- Complete native, Java, ART, and JIT symbolication.
- Add ProGuard mapping, source locations, source navigation, disassembly, and re-symbolication.

### Stage 6: Comparison and Productization

- Add profile comparison and regression views.
- Persist analysis state and support complete import/export round trips.
- Complete accessibility, keyboard navigation, internationalization, packaging, and cross-platform performance work.

Stages are delivery checkpoints, not reductions in the final scope. Each checkpoint preserves a working Simpleperf product.

## 9. Verification

Verification uses three layers:

1. Deterministic unit and property tests for adapters, clock alignment, database migrations, queries, transforms, projections, and cache invalidation.
2. Golden profile integration tests using fixed Simpleperf, Perfetto, instrumented, and combined captures. Cross-panel totals, selections, ranges, and transform results must agree.
3. End-to-end and performance tests on macOS, Windows, and Linux, covering import, analysis, state persistence, export, reopen, and failure recovery.

Additional gates:

- Compare shared metrics against `simpleperf report`, Perfetto, and Firefox Profiler for equivalent inputs.
- Exercise at least one million samples, high marker cardinality, deep stacks, long captures, and symbol-poor profiles.
- Background queries must not block the Compose UI thread.
- Old `.apsession` fixtures must open after every schema change.
- Existing capture and report regression suites remain green at every stage.

## 10. Scope Boundaries

- No React, WebView, or embedded Firefox Profiler runtime is introduced.
- No browser-only event is fabricated.
- No cloud service is required for capture, storage, analysis, or symbolication.
- No new dependency is added without a separate justified decision.
- Work proceeds stage by stage with regression protection; “complete” is reached only when all six stages and their verification gates are satisfied.


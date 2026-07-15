# Firefox-Compatible Native Flame Graph Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the current limited flame graph with a native Compose implementation that matches the applicable functionality and interaction semantics of Firefox Profiler's Flame Graph.

**Architecture:** Add a dependency-free `profile-analysis` module that owns call-stack filtering, transforms, stable call-node identity, normal/inverted projection, and navigation. SQLite supplies immutable weighted stacks, `application` owns generation-safe panel state, `visualization` owns visible-row layout and drawing, and `presentation` owns the Firefox-compatible controls, menus, details, empty states, and accessibility surfaces.

**Tech Stack:** Kotlin 2.4, JVM 21, Compose Multiplatform Desktop 1.11.1, Material 3 1.9.0, SQLite JDBC 3.53.1.0, kotlinx-coroutines 1.11.0, kotlin.test, Gradle 9.5.1.

## Global Constraints

- Compatibility baseline is Firefox Profiler commit `9dd90d380ee711f209c4dcd89beec244eb6d3654`.
- Preserve the Android Performance Studio Compose theme and Android terminology; do not add React, WebView, or pixel-identical Firefox styling.
- Remove horizontal zoom, horizontal panning, click-to-focus, double-click reset, and manual 20,000-node paging from the flame graph.
- Search drops samples whose stacks do not match; comma-separated terms are AND-combined across stacks.
- Normal and inverted projections use independent call-tree semantics; never implement inversion by flipping rectangles.
- Call Tree and Flame Graph share query, transform, call-node identity, and selection state.
- No new runtime dependency. Task 12 adds only the Compose UI test artifact supplied by the already-adopted Compose 1.11.1 plugin and records that test-only decision in the commit.
- Keep old `.apsession` databases readable. No database migration is required for the core graph because the current schema already stores frame ID, function/symbol ID, address, resource path, execution type, sample category, weight, thread, and time.
- Every task follows red-green-refactor, runs its focused checks, and commits with repository Lore trailers.

## Planned File Structure

### New module: `profile-analysis`

- `CallStackContracts.kt` — immutable query, stack, frame, transform, node, row, and empty-reason contracts.
- `CallStackFilter.kt` — search and implementation filtering.
- `CallStackTransformer.kt` — Firefox-compatible stack transformations.
- `CallTreeProjector.kt` — stable call-node trie and forward/inverted weight aggregation.
- `FlameGraphRowProjector.kt` — alphabetical row ordering and normalized horizontal timing.
- `FlameGraphNavigator.kt` — parent, child, and sibling keyboard navigation.

### Existing modules

- `storage-sqlite/SQLiteFlameGraphStackQueries.kt` — loads immutable weighted call stacks from SQLite.
- `application/FlameGraphPanelState.kt` — panel state and generation-safe state transitions.
- `application/FlameGraphFrameDetailsResolver.kt` — source, disassembly, and symbol fallback resolution.
- `visualization/FlameGraphLayout.kt` — visible-row pixel layout and hit testing.
- `visualization/FlameGraphPalette.kt` — category and interaction-state colors.
- `visualization/FlameGraphCanvas.kt` — drawing and pointer intent dispatch.
- `presentation/FlameGraphPanel.kt` — panel assembly and vertical viewport.
- `presentation/FlameGraphToolbar.kt` — search, implementation, inversion, and transform controls.
- `presentation/FlameGraphContextMenu.kt` — call-node actions.
- `presentation/FlameGraphTooltip.kt` — transient hover facts.
- `presentation/FlameGraphDetailsPanel.kt` — source/disassembly/fallback content.
- `presentation/FlameGraphEmptyState.kt` — reason-specific recovery UI.
- `presentation/FlameGraphSemanticsOverlay.kt` — virtual accessibility nodes.

---

### Task 1: Add the analysis module and immutable contracts

**Files:**
- Modify: `settings.gradle.kts`
- Create: `profile-analysis/build.gradle.kts`
- Create: `profile-analysis/src/main/kotlin/com/androidperformancestudio/profileanalysis/CallStackContracts.kt`
- Create: `profile-analysis/src/test/kotlin/com/androidperformancestudio/profileanalysis/CallStackContractsTest.kt`

**Interfaces:**
- Produces: `CallStackAnalysisQuery`, `CallStackTransform`, `CallStackTable`, `CallNodeTable`, `FlameGraphRows`, and `FlameGraphSnapshot`.
- Produces: `FlameCallNodeId`, `FlameFunctionId`, `CallNodePath`, `FrameImplementation`, and `ImplementationFilter`.

- [ ] **Step 1: Write failing contract tests**

```kotlin
@Test
fun `search parser trims blanks and preserves Firefox comma order`() {
    assertEquals(listOf("render", "libc"), parseFlameSearchTerms(" render, ,libc "))
}

@Test
fun `call node path uses structural equality`() {
    assertEquals(
        CallNodePath(listOf(FlameFunctionId(1), FlameFunctionId(2))),
        CallNodePath(listOf(FlameFunctionId(1), FlameFunctionId(2))),
    )
}
```

- [ ] **Step 2: Run the focused test and confirm RED**

```bash
./gradlew :profile-analysis:test
```

Expected: Gradle fails because `:profile-analysis` and the contract types do not exist.

- [ ] **Step 3: Add the module and exact core contracts**

Add `include(":profile-analysis")`, create a module depending only on `:profile-model`, and define these public shapes:

```kotlin
@JvmInline value class FlameFunctionId(val value: Long)
@JvmInline value class FlameCallNodeId(val value: Long)
data class CallNodePath(val functions: List<FlameFunctionId>)
data class AnalysisTimeRange(val startNanosInclusive: Long, val endNanosExclusive: Long)

enum class CallStackDirection { FORWARD, INVERTED }
enum class FrameImplementation { NATIVE, MANAGED, KERNEL, UNKNOWN }
enum class ImplementationFilter { ALL, NATIVE, MANAGED, KERNEL, UNKNOWN }

data class CallStackFrame(
    val frameId: Long,
    val functionId: FlameFunctionId,
    val symbolName: String,
    val resource: String,
    val virtualAddress: Long,
    val implementation: FrameImplementation,
)

data class WeightedCallStack(
    val sampleId: Long,
    val timestampNanos: Long,
    val weight: Long,
    val threadKey: String,
    val category: String?,
    val subcategory: String?,
    val frameIdsRootToLeaf: List<Long>,
)

data class CallStackTable(
    val framesById: Map<Long, CallStackFrame>,
    val stacks: List<WeightedCallStack>,
) {
    fun frame(frameId: Long): CallStackFrame = checkNotNull(framesById[frameId])
}

sealed interface CallStackTransform {
    data class FocusCallNode(val path: CallNodePath) : CallStackTransform
    data class FocusFunction(val function: FlameFunctionId) : CallStackTransform
    data class FocusFunctionSelf(val function: FlameFunctionId) : CallStackTransform
    data class MergeCallNode(val path: CallNodePath) : CallStackTransform
    data class MergeFunction(val function: FlameFunctionId) : CallStackTransform
    data class DropFunction(val function: FlameFunctionId) : CallStackTransform
    data class CollapseResource(val resource: String) : CallStackTransform
    data class CollapseRecursion(val function: FlameFunctionId) : CallStackTransform
    data class CollapseDirectRecursion(val function: FlameFunctionId) : CallStackTransform
    data class CollapseFunctionSubtree(val function: FlameFunctionId) : CallStackTransform
    data class FocusCategory(val category: String) : CallStackTransform
}

data class CallStackAnalysisQuery(
    val previewRange: AnalysisTimeRange? = null,
    val searchText: String = "",
    val implementation: ImplementationFilter = ImplementationFilter.ALL,
    val direction: CallStackDirection = CallStackDirection.FORWARD,
    val transforms: List<CallStackTransform> = emptyList(),
)

class CallNodeTable(
    val ids: LongArray,
    val parentIndexes: IntArray,
    val frameIds: LongArray,
    val depths: IntArray,
    val inclusiveWeights: LongArray,
    val selfWeights: LongArray,
    val sampleCounts: LongArray,
    val threadCounts: IntArray,
    val categories: List<String?>,
    val framesById: Map<Long, CallStackFrame>,
) {
    private val pathIndex by lazy { CallNodePathIndex(this) }
    val size: Int get() = ids.size
    fun findByPath(path: CallNodePath): FlameCallNodeId? = pathIndex.find(path)
}

class FlameGraphRows(
    val nodeIndexesByRow: List<IntArray>,
    val starts: DoubleArray,
    val ends: DoubleArray,
    val startsAtBottom: Boolean,
)

enum class FlameGraphEmptyReason {
    THREAD_HAS_NO_SAMPLES,
    COMMITTED_RANGE_EMPTY,
    PREVIEW_RANGE_EMPTY,
    SEARCH_FILTERED_ALL,
    IMPLEMENTATION_FILTERED_ALL,
    TRANSFORMS_FILTERED_ALL,
    PROFILE_INCOMPLETE,
    PROJECTION_FAILED,
}

data class FlameGraphSnapshot(
    val query: CallStackAnalysisQuery,
    val callNodes: CallNodeTable,
    val rows: FlameGraphRows,
    val totalWeight: Long,
    val emptyReason: FlameGraphEmptyReason?,
    val invalidTransforms: List<CallStackTransform>,
)
```

Implement `CallNodePathIndex` as an internal immutable lookup built once per table; do not scan all nodes on every selection restoration.

- [ ] **Step 4: Run tests and static checks**

```bash
./gradlew :profile-analysis:test :profile-analysis:ktlintCheck :profile-analysis:detekt
```

Expected: all tasks exit 0.

- [ ] **Step 5: Commit**

```bash
git add settings.gradle.kts profile-analysis
git commit -m "Create a stable boundary for call-stack analysis" \
  -m "Constraint: Keep semantic analysis independent from Compose and SQLite.\nConfidence: high\nScope-risk: moderate\nTested: :profile-analysis:test, ktlintCheck, and detekt."
```

---

### Task 2: Load complete weighted stacks from SQLite

**Files:**
- Modify: `storage-sqlite/build.gradle.kts`
- Create: `storage-sqlite/src/main/kotlin/com/androidperformancestudio/storage/SQLiteFlameGraphStackQueries.kt`
- Create: `storage-sqlite/src/test/kotlin/com/androidperformancestudio/storage/SQLiteFlameGraphStackQueriesTest.kt`
- Modify: `profile-model/src/main/kotlin/com/androidperformancestudio/model/NormalizedProfile.kt`
- Modify: `parser-simpleperf-proto/src/main/kotlin/com/androidperformancestudio/parser/SimpleperfProfileNormalizer.kt`
- Modify: `parser-simpleperf-proto/src/test/kotlin/com/androidperformancestudio/parser/SimpleperfProfileNormalizerTest.kt`

**Interfaces:**
- Consumes: `CallStackTable`, `CallStackFrame`, and `WeightedCallStack` from Task 1.
- Produces: `internal fun SQLiteFlameGraphStackQueries.load(connection: Connection, query: ProfileQuery): CallStackTable`.

- [ ] **Step 1: Write failing storage and classification tests**

Create a fixture with two samples sharing frames and assert root-to-leaf ordering, timestamps, event weights, thread keys, category, addresses, function IDs, and resource paths. Add normalizer assertions that `[kernel.kallsyms]` becomes `ProfileExecutionType.KERNEL` and unknown files become `ProfileExecutionType.UNKNOWN`.

```kotlin
assertEquals(listOf("runLoop", "renderFrame"), stack.frameIdsRootToLeaf.map(table::frame).map { it.symbolName })
assertEquals(5L, stack.weight)
assertEquals(FrameImplementation.KERNEL, table.frame(kernelFrameId).implementation)
```

- [ ] **Step 2: Confirm RED**

```bash
./gradlew :parser-simpleperf-proto:test --tests '*SimpleperfProfileNormalizerTest' \
  :storage-sqlite:test --tests '*SQLiteFlameGraphStackQueriesTest'
```

Expected: compilation fails for the missing enum values and SQLite loader.

- [ ] **Step 3: Preserve truthful implementation metadata**

Extend `ProfileExecutionType` with `KERNEL` and `UNKNOWN`. In the normalizer, classify the captured file mapping before creating `ProfileFrame`:

```kotlin
private fun executionType(filePath: String, reported: ExecutionType): ProfileExecutionType =
    when {
        filePath == "[kernel.kallsyms]" || filePath.startsWith("[kernel.") -> ProfileExecutionType.KERNEL
        filePath.startsWith("<unknown-file:") -> ProfileExecutionType.UNKNOWN
        else -> reported.toProfileExecutionType()
    }
```

- [ ] **Step 4: Implement the SQLite stack loader**

Add `implementation(project(":profile-analysis"))` to `storage-sqlite/build.gradle.kts`. Use one recursive query ordered by `sample_id` and descending stored depth. Select `sample_id`, timestamp, event weight, canonical/legacy thread key, sample category, callsite/frame/symbol IDs, symbol name, resource path, virtual address, and execution type. Deduplicate `CallStackFrame` by frame ID and flush each sample into one immutable `WeightedCallStack`.

Map execution types exactly:

```kotlin
private fun String.toFrameImplementation(): FrameImplementation =
    when (this) {
        "NATIVE" -> FrameImplementation.NATIVE
        "INTERPRETED_JVM", "JIT_JVM", "ART" -> FrameImplementation.MANAGED
        "KERNEL" -> FrameImplementation.KERNEL
        else -> FrameImplementation.UNKNOWN
    }
```

- [ ] **Step 5: Verify focused modules**

```bash
./gradlew :profile-model:test :parser-simpleperf-proto:test :storage-sqlite:test \
  :profile-model:ktlintCheck :parser-simpleperf-proto:ktlintCheck :storage-sqlite:ktlintCheck
```

Expected: all tasks exit 0 and legacy-schema projection tests remain green.

- [ ] **Step 6: Commit**

```bash
git add profile-model parser-simpleperf-proto storage-sqlite
git commit -m "Preserve complete stacks for compatible flame analysis" \
  -m "Constraint: Read legacy and canonical databases without a schema migration.\nConfidence: high\nScope-risk: moderate\nTested: profile-model, parser-simpleperf-proto, and storage-sqlite tests plus ktlint."
```

---

### Task 3: Implement Firefox search and implementation filtering

**Files:**
- Create: `profile-analysis/src/main/kotlin/com/androidperformancestudio/profileanalysis/CallStackFilter.kt`
- Create: `profile-analysis/src/test/kotlin/com/androidperformancestudio/profileanalysis/CallStackFilterTest.kt`

**Interfaces:**
- Consumes: `CallStackTable` and `CallStackAnalysisQuery`.
- Produces: `CallStackFilter.apply(table, query): FilteredCallStacks` with stage counts used for empty reasons.

- [ ] **Step 1: Write failing compatibility tests**

Use stacks `root → render → draw`, `root → libc`, and `root → managedTick`. Assert preview-range filtering first, then Firefox search and implementation behavior:

```kotlin
assertEquals(
    listOf(renderStack),
    CallStackFilter.apply(table, CallStackAnalysisQuery(searchText = "render,draw")).table.stacks,
)
assertTrue(
    CallStackFilter.apply(table, CallStackAnalysisQuery(searchText = "render,libc"))
        .table.stacks.isEmpty(),
)
assertEquals(
    listOf(listOf("managedTick")),
    CallStackFilter.apply(table, CallStackAnalysisQuery(implementation = ImplementationFilter.MANAGED))
        .table.stackSymbols(),
)
assertEquals(
    listOf(renderStack),
    CallStackFilter.apply(
        table,
        CallStackAnalysisQuery(previewRange = AnalysisTimeRange(10, 20)),
    ).table.stacks,
)
```

Define the test-only `stackSymbols()` helper as `stacks.map { stack -> stack.frameIdsRootToLeaf.map { frame(it).symbolName } }` so assertions inspect the public table contract rather than transformer internals.

Also assert case-insensitive matching against function and resource, removal of empty filtered stacks, and no mutation of the input table.

- [ ] **Step 2: Confirm RED**

```bash
./gradlew :profile-analysis:test --tests '*CallStackFilterTest'
```

Expected: compilation fails because `CallStackFilter` and `FilteredCallStacks` do not exist.

- [ ] **Step 3: Implement exact filter stages**

Parse comma terms once. A stack matches only when every term matches at least one frame; a term matches a frame when symbol or resource contains it ignoring case. Implementation filtering removes nonmatching frames, preserves relative order, and drops stacks that become empty.

Return stage counts:

```kotlin
data class FilteredCallStacks(
    val table: CallStackTable,
    val inputStackCount: Int,
    val afterPreviewCount: Int,
    val afterSearchCount: Int,
    val afterImplementationCount: Int,
)
```

- [ ] **Step 4: Verify**

```bash
./gradlew :profile-analysis:test :profile-analysis:ktlintCheck :profile-analysis:detekt
```

Expected: all tasks exit 0.

- [ ] **Step 5: Commit**

```bash
git add profile-analysis
git commit -m "Make flame filters operate on sampled stacks" \
  -m "Constraint: Match Firefox comma-search AND semantics and stack-shaping implementation filters.\nConfidence: high\nScope-risk: narrow\nTested: :profile-analysis:test, ktlintCheck, and detekt."
```

---

### Task 4: Implement every approved call-stack transform

**Files:**
- Create: `profile-analysis/src/main/kotlin/com/androidperformancestudio/profileanalysis/CallStackTransformer.kt`
- Create: `profile-analysis/src/test/kotlin/com/androidperformancestudio/profileanalysis/CallStackTransformerTest.kt`
- Create: `profile-analysis/src/test/kotlin/com/androidperformancestudio/profileanalysis/FirefoxTransformCompatibilityTest.kt`

**Interfaces:**
- Consumes: `CallStackTable` and ordered `List<CallStackTransform>`.
- Produces: `TransformResult(table, appliedTransforms, invalidTransforms, inputStackCount, outputStackCount)`.

- [ ] **Step 1: Write failing transform matrix tests**

Build explicit stacks for branching and recursion:

```text
root → A → B → C
root → A → D
root → R → R → R → leaf
root → libX:a → libX:b → leaf
```

Use a parameterized case table with exact expected stacks for all eleven semantic transforms. Include assertions that ordered transforms compose, invalid call-node paths are returned in `invalidTransforms`, and an emptied stack is dropped.

```kotlin
data class TransformCase(
    val transform: CallStackTransform,
    val expected: List<List<String>>,
)
```

- [ ] **Step 2: Confirm RED**

```bash
./gradlew :profile-analysis:test --tests '*CallStackTransformerTest' --tests '*FirefoxTransformCompatibilityTest'
```

Expected: compilation fails for the missing transformer.

- [ ] **Step 3: Implement transforms as pure stack rewrites**

Apply transforms in list order. Preserve sample ID, weight, thread, and category while replacing only `frameIdsRootToLeaf`. Implement each transform in a focused private function named after its contract; never inspect pixel rows or selection state.

Use this result contract:

```kotlin
data class TransformResult(
    val table: CallStackTable,
    val appliedTransforms: List<CallStackTransform>,
    val invalidTransforms: List<CallStackTransform>,
    val inputStackCount: Int,
    val outputStackCount: Int,
)
```

- [ ] **Step 4: Verify transform semantics and quality gates**

```bash
./gradlew :profile-analysis:test :profile-analysis:ktlintCheck :profile-analysis:detekt
```

Expected: every transform case passes and static checks exit 0.

- [ ] **Step 5: Commit**

```bash
git add profile-analysis
git commit -m "Make call-stack transforms change analysis truthfully" \
  -m "Constraint: Apply the approved Firefox transform set to samples rather than rectangles.\nConfidence: high\nScope-risk: moderate\nTested: transform compatibility, profile-analysis unit tests, ktlint, and detekt."
```

---

### Task 5: Project stable forward and inverted call nodes and rows

**Files:**
- Create: `profile-analysis/src/main/kotlin/com/androidperformancestudio/profileanalysis/CallTreeProjector.kt`
- Create: `profile-analysis/src/main/kotlin/com/androidperformancestudio/profileanalysis/FlameGraphRowProjector.kt`
- Create: `profile-analysis/src/test/kotlin/com/androidperformancestudio/profileanalysis/CallTreeProjectorTest.kt`
- Create: `profile-analysis/src/test/kotlin/com/androidperformancestudio/profileanalysis/FlameGraphRowProjectorTest.kt`
- Create: `profile-analysis/src/test/kotlin/com/androidperformancestudio/profileanalysis/FlameGraphProjectionPropertyTest.kt`

**Interfaces:**
- Consumes: filtered/transformed `CallStackTable` and `CallStackDirection`.
- Produces: `CallTreeProjector.project(...) : CallNodeTable` and `FlameGraphRowProjector.project(...) : FlameGraphRows`.

- [ ] **Step 1: Write failing normal/inverted tests**

Assert normal roots use root-to-leaf stacks, inverted roots use leaf-to-root stacks, self weight is assigned to the terminal projected frame, siblings are alphabetically ordered, and a call path receives the same stable ID across equivalent filtered queries.

```kotlin
assertEquals(listOf("A", "B"), normal.rootNames())
assertEquals(listOf("leaf", "otherLeaf"), inverted.rootNames())
assertEquals(normal.idForPath("root", "render"), filtered.idForPath("root", "render"))
```

Implement `rootNames()` and `idForPath(vararg names)` as private test helpers that traverse the public `CallNodeTable` accessors. Production code exposes indexed node metadata and `findByPath(CallNodePath): FlameCallNodeId?`; it does not expose name-based lookup.

Property tests must verify `0.0 <= start < end <= 1.0`, children lie within parent bounds, siblings do not overlap, and root widths sum to one when total weight is positive.

- [ ] **Step 2: Confirm RED**

```bash
./gradlew :profile-analysis:test --tests '*CallTreeProjectorTest' \
  --tests '*FlameGraphRowProjectorTest' --tests '*FlameGraphProjectionPropertyTest'
```

Expected: compilation fails for missing projectors.

- [ ] **Step 3: Implement the deterministic call-node trie**

Create stable IDs from the ordered function path with collision detection. Aggregate inclusive weight, self weight, sample count, thread count, and dominant category. Traverse roots and children using `symbolName.lowercase()` followed by function ID as a deterministic tie-breaker.

- [ ] **Step 4: Implement row timing separately from call-node aggregation**

Assign normalized root intervals across total root weight, then align each child interval within its parent. Set `startsAtBottom = direction == CallStackDirection.FORWARD`; do not reverse or mirror already-generated rows.

- [ ] **Step 5: Verify**

```bash
./gradlew :profile-analysis:test :profile-analysis:ktlintCheck :profile-analysis:detekt
```

Expected: deterministic, property, and static tests pass.

- [ ] **Step 6: Commit**

```bash
git add profile-analysis
git commit -m "Give normal and inverted flames independent semantics" \
  -m "Constraint: Preserve stable call-node identity and alphabetical sibling order.\nConfidence: high\nScope-risk: moderate\nTested: projector unit/property tests, ktlint, and detekt."
```

---

### Task 6: Integrate the analysis projection with SQLite and report state

**Files:**
- Modify: `storage-sqlite/src/main/kotlin/com/androidperformancestudio/storage/ProfileProjectionModels.kt`
- Modify: `storage-sqlite/src/main/kotlin/com/androidperformancestudio/storage/SQLiteProfileProjectionQueries.kt`
- Modify: `storage-sqlite/src/main/kotlin/com/androidperformancestudio/storage/SQLiteSampleStore.kt`
- Modify: `storage-sqlite/src/test/kotlin/com/androidperformancestudio/storage/SQLiteProfileProjectionQueriesTest.kt`
- Modify: `application/build.gradle.kts`
- Create: `application/src/main/kotlin/com/androidperformancestudio/application/FlameGraphPanelState.kt`
- Modify: `application/src/main/kotlin/com/androidperformancestudio/application/ReportController.kt`
- Modify: `application/src/test/kotlin/com/androidperformancestudio/application/ReportControllerTest.kt`
- Modify: `application/src/test/kotlin/com/androidperformancestudio/application/ProfileQueryCoordinatorTest.kt`

**Interfaces:**
- Consumes: Task 2 loader and Tasks 3–5 analysis pipeline.
- Produces: `ProfileProjectionRequest.callStackAnalysis`, `ProfileProjectionSnapshot.flameGraph`, and `ReportState.flameGraph`.
- Produces controller methods `updateFlamePreviewRange`, `updateFlameSearch`, `updateImplementationFilter`, `updateCallStackDirection`, `applyTransform`, `removeTransform`, `clearTransforms`, and `selectCallNode`.

- [ ] **Step 1: Replace obsolete controller tests with failing parity tests**

Delete assertions that search only highlights, reverse Call Tree leaves Flame Graph forward, or selection focuses a horizontal viewport. Add tests that preview range and search reload the query, direction changes both linked panels, transforms survive unrelated refreshes, stale generations cannot publish, changing profiles clears transient state, and removed selection falls back to the nearest visible ancestor.

```kotlin
controller.updateFlameSearch("render,draw")
assertEquals("render,draw", controller.state.value.flameGraph.query.searchText)
assertEquals(CallStackDirection.INVERTED, controller.state.value.callTreeDirection)
```

- [ ] **Step 2: Confirm RED**

```bash
./gradlew :storage-sqlite:test --tests '*SQLiteProfileProjectionQueriesTest' \
  :application:test --tests '*ReportControllerTest' --tests '*ProfileQueryCoordinatorTest'
```

Expected: compilation or parity assertions fail against the old request and report state.

- [ ] **Step 3: Add the projection request and snapshot fields**

Add `implementation(project(":profile-analysis"))` to `application/build.gradle.kts`; storage and application both use the shared contracts directly.

Use these shapes:

```kotlin
data class ProfileProjectionRequest(
    val query: ProfileQuery = ProfileQuery(),
    val callStackAnalysis: CallStackAnalysisQuery = CallStackAnalysisQuery(),
    val timelineBucketCount: Int = 600,
    val topFunctionLimit: Int = 200,
    val topSearch: String = "",
    val topSort: TopFunctionSort = TopFunctionSort.INCLUSIVE_WEIGHT,
    val topDescending: Boolean = true,
)

data class ProfileProjectionSnapshot(
    val query: ProfileQuery,
    val flameGraph: FlameGraphSnapshot,
    val callTree: List<CallTreeNode>,
    val overview: ProfileOverview,
    val quality: DataQualitySummary,
    val tracks: List<ProfileTrackSnapshot>,
    val threads: List<ThreadSummary>,
    val timeline: List<TimelineBucket>,
    val topFunctions: List<TopFunction>,
    val sessionOverview: ProfileOverview = overview,
    val sessionThreads: List<ThreadSummary> = threads,
)
```

Build `callTree` from the same `CallNodeTable` as `flameGraph` so IDs and weights match. Change `ReportData.flameGraph` to `FlameGraphSnapshot`, delete `ReportFlameNode`, delete `List<CallTreeNode>.toFlameGraph()`, and update snapshot fixtures in workspace/coordinator tests.

- [ ] **Step 4: Add immutable application panel state**

```kotlin
data class FlameGraphPanelState(
    val query: CallStackAnalysisQuery = CallStackAnalysisQuery(),
    val selectedNodeId: FlameCallNodeId? = null,
    val hoveredNodeId: FlameCallNodeId? = null,
    val contextNodeId: FlameCallNodeId? = null,
    val invalidTransforms: List<CallStackTransform> = emptyList(),
)
```

Move the old `flameSearch` and `highlightedFlameNodeIds` fields out of `ReportState`. Replace storage `CallTreeDirection` with shared `CallStackDirection`, update controller actions through `copy`, increment the workspace generation only for semantic query changes, and preserve local hover/menu state without querying. A preview range is stored in `CallStackAnalysisQuery` and filters timestamped stacks after the committed `ProfileQuery` range is loaded.

- [ ] **Step 5: Verify integration and cancellation**

```bash
./gradlew :storage-sqlite:test :application:test \
  :storage-sqlite:ktlintCheck :storage-sqlite:detekt \
  :application:ktlintCheck :application:detekt
```

Expected: all tasks exit 0, including legacy read-only projection and stale-generation tests.

- [ ] **Step 6: Commit**

```bash
git add storage-sqlite application
git commit -m "Share one call-stack truth across report panels" \
  -m "Constraint: Keep generation cancellation and legacy session readability intact.\nConfidence: high\nScope-risk: broad\nTested: storage-sqlite and application tests, ktlint, and detekt."
```

---

### Task 7: Replace progressive horizontal projection with visible-row layout

**Files:**
- Modify: `visualization/build.gradle.kts`
- Delete: `visualization/src/main/kotlin/com/androidperformancestudio/visualization/FlameGraphProjector.kt`
- Create: `visualization/src/main/kotlin/com/androidperformancestudio/visualization/FlameGraphLayout.kt`
- Create: `visualization/src/main/kotlin/com/androidperformancestudio/visualization/FlameGraphPalette.kt`
- Replace: `visualization/src/test/kotlin/com/androidperformancestudio/visualization/FlameGraphProjectorTest.kt`
- Create: `visualization/src/test/kotlin/com/androidperformancestudio/visualization/FlameGraphLayoutTest.kt`
- Create: `visualization/src/test/kotlin/com/androidperformancestudio/visualization/FlameGraphPaletteTest.kt`

**Interfaces:**
- Consumes: `FlameGraphSnapshot` and `FlameGraphRows`.
- Produces: `FlameGraphLayout.layout(snapshot, viewport): VisibleFlameLayout` and `hitTest(layout, x, y)`.

- [ ] **Step 1: Write failing visible-row and pixel-layout tests**

Assert only requested rows plus one-row overscan are returned, normal rows are anchored to the bottom, inverted rows to the top, sub-pixel nodes are skipped only from drawing, rectangle edges are pixel-snapped, and hit testing returns the last painted node.

```kotlin
val layout = FlameGraphLayout.layout(snapshot, FlameViewport(widthPx = 800, heightPx = 160, scrollRow = 20))
assertEquals(19..30, layout.materializedRowRange)
assertFalse(layout.nodes.any { it.widthPx < 1f })
```

- [ ] **Step 2: Confirm RED**

```bash
./gradlew :visualization:test
```

Expected: old projector tests or missing new layout types fail.

- [ ] **Step 3: Implement immutable visible layout**

Add `implementation(project(":profile-analysis"))` to `visualization/build.gradle.kts`.

Define:

```kotlin
data class FlameViewport(
    val widthPx: Int,
    val heightPx: Int,
    val scrollRow: Int,
    val rowHeightPx: Float = 16f,
    val overscanRows: Int = 1,
)

data class VisibleFlameNode(
    val nodeIndex: Int,
    val nodeId: FlameCallNodeId,
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
)
```

Calculate X only from normalized row timing and full canvas width. Do not accept a horizontal viewport.

- [ ] **Step 4: Add category and state palette policy**

Map captured categories to stable theme-aware color roles and define separate selected, hovered, and context-node overlays. Test light/dark foreground contrast decisions as pure functions.

- [ ] **Step 5: Verify**

```bash
./gradlew :visualization:test :visualization:ktlintCheck :visualization:detekt
```

Expected: all tasks exit 0 and no progressive page/focus API remains.

- [ ] **Step 6: Commit**

```bash
git add visualization
git commit -m "Render flame rows without manual paging or horizontal zoom" \
  -m "Constraint: Bound layout work by visible rows while preserving hidden-node statistics.\nConfidence: high\nScope-risk: moderate\nTested: visualization unit tests, ktlint, and detekt."
```

---

### Task 8: Add Firefox pointer and keyboard navigation contracts

**Files:**
- Create: `profile-analysis/src/main/kotlin/com/androidperformancestudio/profileanalysis/FlameGraphNavigator.kt`
- Create: `profile-analysis/src/test/kotlin/com/androidperformancestudio/profileanalysis/FlameGraphNavigatorTest.kt`
- Modify: `visualization/src/main/kotlin/com/androidperformancestudio/visualization/FlameGraphCanvas.kt`
- Create: `visualization/src/test/kotlin/com/androidperformancestudio/visualization/FlameGraphInteractionTest.kt`

**Interfaces:**
- Produces: `FlameGraphNavigator.parent`, `widestChild`, `previousSibling`, and `nextSibling`.
- Produces: `FlameGraphCanvas(..., onIntent: (FlameGraphIntent) -> Unit)`.

- [ ] **Step 1: Write failing navigator tests**

Cover forward and inverted arrow mapping, widest-child selection, alphabetical siblings, skipping nodes narrower than `0.001`, root boundaries, and a selected node scrolling into view.

```kotlin
assertEquals(childB, FlameGraphNavigator.widestChild(table, root, minimumNormalizedWidth = 0.001))
assertEquals(siblingC, FlameGraphNavigator.nextSibling(table, siblingB, minimumNormalizedWidth = 0.001))
```

- [ ] **Step 2: Confirm RED**

```bash
./gradlew :profile-analysis:test --tests '*FlameGraphNavigatorTest' \
  :visualization:test --tests '*FlameGraphInteractionTest'
```

Expected: missing navigator and intent types fail compilation.

- [ ] **Step 3: Implement pure navigation**

Use call-node relationships and row widths only. Keep key-to-direction mapping in presentation, while `FlameGraphNavigator` exposes orientation-independent graph operations.

- [ ] **Step 4: Replace the canvas interaction API**

```kotlin
sealed interface FlameGraphIntent {
    data class Hover(val nodeId: FlameCallNodeId?) : FlameGraphIntent
    data class Select(val nodeId: FlameCallNodeId?) : FlameGraphIntent
    data class OpenContextMenu(val nodeId: FlameCallNodeId, val position: Offset) : FlameGraphIntent
    data class OpenDetails(val nodeId: FlameCallNodeId) : FlameGraphIntent
}
```

Dispatch hover enter/exit, blank click, node click, right-click, and node double-click. Draw fitted/truncated labels through cached `TextMeasurer`; remove `onReset` and all Perfetto navigation bindings from the flame canvas.

- [ ] **Step 5: Verify**

```bash
./gradlew :profile-analysis:test :visualization:test \
  :profile-analysis:ktlintCheck :visualization:ktlintCheck \
  :profile-analysis:detekt :visualization:detekt
```

Expected: all tasks exit 0.

- [ ] **Step 6: Commit**

```bash
git add profile-analysis visualization
git commit -m "Align flame navigation with the Firefox interaction model" \
  -m "Constraint: Double-click opens details and arrows navigate call-node relationships.\nConfidence: high\nScope-risk: moderate\nTested: navigator and visualization interaction tests plus static checks."
```

---

### Task 9: Extract and assemble the native Flame Graph panel

**Files:**
- Create: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/FlameGraphPanel.kt`
- Create: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/FlameGraphToolbar.kt`
- Create: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/FlameGraphTooltip.kt`
- Modify: `presentation/build.gradle.kts`
- Modify: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/ReportPage.kt:513-640`
- Modify: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/ReportActions.kt`
- Modify: `visualization/src/main/kotlin/com/androidperformancestudio/visualization/TimelineCanvas.kt`
- Create: `visualization/src/test/kotlin/com/androidperformancestudio/visualization/TimelineRangeInteractionTest.kt`
- Create: `presentation/src/test/kotlin/com/androidperformancestudio/presentation/FlameGraphPresenterTest.kt`
- Modify: `presentation/src/test/kotlin/com/androidperformancestudio/presentation/SimpleperfKeyboardShortcutTest.kt`

**Interfaces:**
- Consumes: `ReportState.flameGraph`, `ReportData.flameGraph`, and Task 8 intents.
- Produces: `FlameGraphPanel(state, snapshot, actions)` and toolbar callbacks for search, implementation, inversion, transform undo/clear, selection, hover, menu, and details.

- [ ] **Step 1: Write failing presentation reducer and shortcut tests**

Test that timeline drag publishes preview updates and commits on release, blank click clears selection, node click does not change horizontal scale, double-click emits open-details, Enter opens details, Escape closes the active transient surface, arrows select the expected node, Copy returns the selected function name, and selection updates vertical scroll.

- [ ] **Step 2: Confirm RED**

```bash
./gradlew :presentation:test --tests '*FlameGraphPresenterTest' --tests '*SimpleperfKeyboardShortcutTest'
```

Expected: tests fail because the old composable owns local viewport/search/selection state.

- [ ] **Step 3: Extract focused composables and state adapters**

Add a direct `implementation(project(":profile-analysis"))` dependency because presentation consumes the shared query and node contracts. Remove the private `FlameGraphReport` from `ReportPage.kt`. Assemble a compact toolbar, vertically scrollable canvas, transient tooltip, and details slot in `FlameGraphPanel.kt`. Keep only vertical `scrollRow` as local viewport state; semantic state comes from `ReportState`.

- [ ] **Step 4: Wire live semantic controls**

Search uses a cancellable 150 ms `LaunchedEffect` debounce before dispatching `updateFlameSearch`. Direction uses one Forward/Inverted control shared with Call Tree. Implementation choices are All, Native, Managed, Kernel, and Unknown. Undo and Clear display only when transforms exist.

Update `TimelineCanvas` to emit `onRangePreview` while dragging and `onRangeCommit` on release; clearing or committing the range clears preview state. `FlameGraphTooltip` displays available function, category, implementation, resource, inclusive/self weight, sample count, percentage, and preview-range weight.

- [ ] **Step 5: Verify**

```bash
./gradlew :presentation:test :presentation:ktlintCheck :presentation:detekt
```

Expected: all tasks exit 0 and `ReportPage.kt` no longer imports `WeightViewport`, `PerfettoNavigationBindings`, or `FlameGraphProjector`.

- [ ] **Step 6: Commit**

```bash
git add presentation
git commit -m "Move flame analysis into a focused native panel" \
  -m "Constraint: Keep semantic state in application and only vertical viewport state in Compose.\nConfidence: high\nScope-risk: moderate\nTested: presentation tests, ktlint, and detekt."
```

---

### Task 10: Add context transforms and cross-panel selection

**Files:**
- Create: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/FlameGraphContextMenu.kt`
- Modify: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/FlameGraphPanel.kt`
- Modify: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/ReportPage.kt:405-497`
- Modify: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/ReportActions.kt`
- Modify: `application/src/main/kotlin/com/androidperformancestudio/application/ReportController.kt`
- Create: `presentation/src/test/kotlin/com/androidperformancestudio/presentation/FlameGraphContextMenuTest.kt`
- Modify: `application/src/test/kotlin/com/androidperformancestudio/application/ReportControllerTest.kt`

**Interfaces:**
- Consumes: `CallStackTransform` and stable IDs from shared `CallNodeTable`.
- Produces: context-menu command mapping and linked Call Tree/Flame Graph `selectedCallNodeId`.

- [ ] **Step 1: Write failing menu and linked-selection tests**

Assert every approved transform is present only when valid for the target, Copy returns the function name, transform shortcuts dispatch the same command objects, selecting a Call Tree row selects the same flame node, and selecting a flame node expands/scrolls the Call Tree path without changing the query.

- [ ] **Step 2: Confirm RED**

```bash
./gradlew :application:test --tests '*ReportControllerTest' \
  :presentation:test --tests '*FlameGraphContextMenuTest'
```

Expected: missing menu commands and shared selection assertions fail.

- [ ] **Step 3: Implement the context menu as a command list**

Build menu entries from selected node metadata and dispatch the exact `CallStackTransform` values defined in Task 1. Include Focus node, Focus function, Focus self, Merge node, Merge function, Drop function, Collapse resource, Collapse recursion, Collapse direct recursion, Collapse subtree, Focus category, Copy, Undo, and Clear.

- [ ] **Step 4: Share selection with Call Tree**

Replace Call Tree's symbol-only focus click with stable call-node selection. Keep expansion local but derive the selected path from the shared node table. If a query removes selection, use the ancestor fallback produced by Task 6.

- [ ] **Step 5: Verify**

```bash
./gradlew :application:test :presentation:test \
  :application:ktlintCheck :presentation:ktlintCheck \
  :application:detekt :presentation:detekt
```

Expected: all tasks exit 0.

- [ ] **Step 6: Commit**

```bash
git add application presentation
git commit -m "Expose stack transforms through one linked call-node selection" \
  -m "Constraint: Context actions must rewrite samples and Call Tree/Flame Graph must agree.\nConfidence: high\nScope-risk: broad\nTested: application and presentation tests plus static checks."
```

---

### Task 11: Add reason-specific empty and error states

**Files:**
- Modify: `profile-analysis/src/main/kotlin/com/androidperformancestudio/profileanalysis/CallStackFilter.kt`
- Modify: `profile-analysis/src/main/kotlin/com/androidperformancestudio/profileanalysis/CallStackTransformer.kt`
- Modify: `profile-analysis/src/main/kotlin/com/androidperformancestudio/profileanalysis/CallTreeProjector.kt`
- Create: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/FlameGraphEmptyState.kt`
- Modify: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/SimpleperfLocalization.kt`
- Create: `presentation/src/test/kotlin/com/androidperformancestudio/presentation/FlameGraphEmptyStateTest.kt`
- Modify: `presentation/src/test/kotlin/com/androidperformancestudio/presentation/SimpleperfLocalizationTest.kt`

**Interfaces:**
- Produces exact `FlameGraphEmptyReason` values and `FlameGraphRecoveryAction` mappings.

- [ ] **Step 1: Write failing reason and localization tests**

Cover thread-no-samples, committed-range-empty, preview-range-empty, search-removed-all, implementation-removed-all, transforms-removed-all, incomplete-profile, and projection-failed. Assert each reason maps to one concrete message and recovery action.

```kotlin
assertEquals(FlameGraphRecoveryAction.CLEAR_SEARCH, recoveryAction(FlameGraphEmptyReason.SEARCH_FILTERED_ALL))
assertEquals(FlameGraphRecoveryAction.UNDO_TRANSFORM, recoveryAction(FlameGraphEmptyReason.TRANSFORMS_FILTERED_ALL))
```

- [ ] **Step 2: Confirm RED**

```bash
./gradlew :profile-analysis:test :presentation:test --tests '*FlameGraphEmptyStateTest' \
  --tests '*SimpleperfLocalizationTest'
```

Expected: stage reasons or translations are missing.

- [ ] **Step 3: Derive the first eliminating stage**

Carry stage counts from loader, filter, transform, and projector into `FlameGraphSnapshot.emptyReason`. Use this precedence: selected thread absent from session totals, committed query has zero while session has samples, preview has zero, search has zero, implementation has zero, transforms have zero, incomplete source stacks, then projection failure. Never infer all empty cases as a generic no-data result.

- [ ] **Step 4: Render actionable localized states**

Map recovery actions to existing controller callbacks: select thread, reset range, clear search, change implementation, undo transform, or retry projection. Add English and Simplified Chinese strings. Keep raw `PROCESS_EXIT_1` and exception text in diagnostic details rather than the primary message.

- [ ] **Step 5: Verify**

```bash
./gradlew :profile-analysis:test :presentation:test \
  :profile-analysis:ktlintCheck :presentation:ktlintCheck \
  :profile-analysis:detekt :presentation:detekt
```

Expected: all tasks exit 0.

- [ ] **Step 6: Commit**

```bash
git add profile-analysis presentation
git commit -m "Explain why flame analysis has no visible samples" \
  -m "Constraint: Distinguish recoverable query emptiness from data and projection failures.\nConfidence: high\nScope-risk: narrow\nTested: empty-state, localization, profile-analysis, presentation, and static checks."
```

---

### Task 12: Resolve source, disassembly, and truthful fallback details

**Files:**
- Create: `platform-toolchain/src/main/kotlin/com/androidperformancestudio/toolchain/AndroidLlvmToolLocator.kt`
- Create: `platform-toolchain/src/test/kotlin/com/androidperformancestudio/toolchain/AndroidLlvmToolLocatorTest.kt`
- Create: `application/src/main/kotlin/com/androidperformancestudio/application/FlameGraphFrameDetailsResolver.kt`
- Create: `application/src/test/kotlin/com/androidperformancestudio/application/FlameGraphFrameDetailsResolverTest.kt`
- Create: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/FlameGraphDetailsPanel.kt`
- Create: `presentation/src/test/kotlin/com/androidperformancestudio/presentation/FlameGraphDetailsPresenterTest.kt`
- Modify: `application/src/main/kotlin/com/androidperformancestudio/application/ReportController.kt`
- Modify: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/FlameGraphPanel.kt`

**Interfaces:**
- Produces: `FlameGraphFrameDetailsResolver.resolve(request): FlameGraphFrameDetails`.
- Produces: `Source`, `Disassembly`, and `SymbolFallback` detail states.

- [ ] **Step 1: Write failing tool and fallback tests**

Test locator precedence: configured NDK, `ANDROID_NDK_HOME`, SDK `ndk/*/toolchains/llvm/prebuilt/<host>/bin`, then PATH. Test source success, source miss with disassembly success, missing binary fallback, tool exit failure fallback, cancellation, and Build ID mismatch rejection.

- [ ] **Step 2: Confirm RED**

```bash
./gradlew :platform-toolchain:test --tests '*AndroidLlvmToolLocatorTest' \
  :application:test --tests '*FlameGraphFrameDetailsResolverTest' \
  :presentation:test --tests '*FlameGraphDetailsPresenterTest'
```

Expected: missing locator, resolver, and detail states fail compilation.

- [ ] **Step 3: Implement artifact and LLVM tool resolution**

Resolve recorded absolute resource paths under `session/symbols` and `session/binary_cache` without permitting path traversal. When expected and actual Build IDs are both available, compare normalized lowercase hex and reject mismatches. Locate `llvm-symbolizer`, `llvm-objdump`, and `llvm-readelf` without adding a dependency.

- [ ] **Step 4: Implement cancellable detail resolution**

Define the exact result and panel state contracts:

```kotlin
sealed interface FlameGraphFrameDetails {
    data class Source(
        val file: Path,
        val line: Int,
        val column: Int?,
        val text: List<String>,
    ) : FlameGraphFrameDetails

    data class Disassembly(
        val binary: Path,
        val address: Long,
        val text: List<String>,
    ) : FlameGraphFrameDetails

    data class SymbolFallback(
        val function: String,
        val resource: String,
        val address: Long,
        val libraryOffset: Long,
        val buildId: String?,
        val reason: String,
    ) : FlameGraphFrameDetails
}

sealed interface FlameGraphDetailsState {
    data object Closed : FlameGraphDetailsState
    data class Loading(val nodeId: FlameCallNodeId, val generation: ProfileGeneration) : FlameGraphDetailsState
    data class Ready(val value: FlameGraphFrameDetails) : FlameGraphDetailsState
}
```

Use an injected suspend process invocation around `JvmProcessRunner`. Run `llvm-symbolizer --obj=<binary> 0x<address>` first. When it returns a concrete source location, publish `Source`; otherwise run bounded `llvm-objdump --disassemble --source --start-address=<address-64> --stop-address=<address+128> <binary>`. On any unavailable path, publish `SymbolFallback(function, resource, address, libraryOffset, buildId, reason)`.

- [ ] **Step 5: Render the bottom details surface**

Add `details: FlameGraphDetailsState` to `FlameGraphPanelState` in this task. Double-click and Enter request details for the selected node. Escape closes the panel. Show source with selected line, monospaced disassembly, or fallback facts. Detail loading/failure must not replace the graph snapshot or clear selection; profile/query generation changes reject stale detail results.

- [ ] **Step 6: Verify**

```bash
./gradlew :platform-toolchain:test :application:test :presentation:test \
  :platform-toolchain:ktlintCheck :application:ktlintCheck :presentation:ktlintCheck \
  :platform-toolchain:detekt :application:detekt :presentation:detekt
```

Expected: all tasks exit 0 using fake process output; no host NDK is required for unit tests.

- [ ] **Step 7: Commit**

```bash
git add platform-toolchain application presentation
git commit -m "Open the best verified detail available for a flame frame" \
  -m "Constraint: Prefer source, then disassembly, then an explicit symbol fallback without breaking analysis.\nConfidence: high\nScope-risk: moderate\nTested: tool locator, details resolver/presenter, module tests, ktlint, and detekt."
```

---

### Task 13: Add accessibility semantics and Compose UI coverage

**Files:**
- Modify: `presentation/build.gradle.kts`
- Create: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/FlameGraphSemanticsOverlay.kt`
- Modify: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/FlameGraphPanel.kt`
- Create: `presentation/src/test/kotlin/com/androidperformancestudio/presentation/FlameGraphComposeUiTest.kt`
- Create: `presentation/src/test/kotlin/com/androidperformancestudio/presentation/FlameGraphAccessibilityTest.kt`

**Interfaces:**
- Consumes: visible/selected call nodes and Task 8 navigation.
- Produces: virtual semantic nodes with select, open-details, and open-menu actions.

- [ ] **Step 1: Add failing keyboard-only and semantics tests**

Using Compose Desktop UI tests, mount a small deterministic graph and verify search, inversion, hover dismissal after selection, blank deselection, arrow navigation, Enter details, context menu, Copy, transform dispatch, empty state action, and selected-node semantics.

```kotlin
onNodeWithContentDescription("renderFrame, 60%, Native").assertExists().performClick()
onNodeWithTag("flame-details").assertExists()
```

- [ ] **Step 2: Confirm RED before adding the test artifact**

```bash
./gradlew :presentation:test --tests '*FlameGraphComposeUiTest' --tests '*FlameGraphAccessibilityTest'
```

Expected: test compilation fails because the Compose UI test artifact and semantics overlay are absent.

- [ ] **Step 3: Add the test-only Compose artifact**

```kotlin
dependencies {
    testImplementation(compose.desktop.uiTestJUnit4)
}
```

This uses the existing Compose 1.11.1 distribution and does not change runtime packaging.

- [ ] **Step 4: Implement the virtual semantics overlay**

Expose semantic nodes for visible eligible nodes plus the selected node. Each description contains function, category, implementation, inclusive weight, sample count, and percentage. Match keyboard traversal order and scroll focus into view. Use shape/focus indicators in addition to color.

- [ ] **Step 5: Verify UI and accessibility tests**

```bash
./gradlew :presentation:test :presentation:ktlintCheck :presentation:detekt
```

Expected: all tasks exit 0 in headless CI.

- [ ] **Step 6: Commit**

```bash
git add presentation
git commit -m "Make flame analysis operable without a pointing device" \
  -m "Constraint: Add only the Compose test artifact matching the existing plugin version; runtime dependencies remain unchanged.\nConfidence: high\nScope-risk: moderate\nTested: Compose UI, accessibility, presentation unit, ktlint, and detekt checks."
```

---

### Task 14: Close performance, documentation, and full compatibility gates

**Files:**
- Modify: `test-fixtures/build.gradle.kts`
- Modify: `app-desktop/build.gradle.kts`
- Create: `test-fixtures/src/main/kotlin/com/androidperformancestudio/fixtures/FirefoxFlameGraphFixtures.kt`
- Create: `test-fixtures/src/test/kotlin/com/androidperformancestudio/fixtures/FirefoxFlameGraphFixturesTest.kt`
- Modify: `test-fixtures/src/poc/kotlin/com/androidperformancestudio/fixtures/P0PerformancePoc.kt`
- Create: `app-desktop/src/test/kotlin/com/androidperformancestudio/desktop/FlameGraphGoldenE2eTest.kt`
- Modify: `docs/user-guide.md`
- Create: `docs/firefox-flame-graph-compatibility.md`
- Modify: `docs/p0-performance-poc.md`
- Create: `docs/poc-results/firefox-flame-graph-macos-arm64.json`

**Interfaces:**
- Produces: deterministic mixed/native/managed/kernel/recursive/source-less and million-sample fixtures.
- Produces: `runFlameGraphPerformancePoc` verification task and recorded baseline output.

- [ ] **Step 1: Add failing fixture and end-to-end tests**

Create deterministic fixtures for all compatibility rows and assert import → query → transform → select → details fallback. Add a million-sample fixture with bounded symbol/cardinality growth and a deep-stack fixture exceeding the visible row count.

- [ ] **Step 2: Confirm RED**

```bash
./gradlew :test-fixtures:test --tests '*FirefoxFlameGraphFixturesTest' \
  :app-desktop:test --tests '*FlameGraphGoldenE2eTest'
```

Expected: missing fixtures and end-to-end test helpers fail compilation.

- [ ] **Step 3: Extend the reproducible performance POC**

Add `testImplementation(project(":test-fixtures"))` to `app-desktop`. Add `api(project(":profile-analysis"))` to `test-fixtures`, plus `:profile-analysis` and `:visualization` to the `poc` source-set implementation configuration. Register `runFlameGraphPerformancePoc`. Record import/projection latency, cancellation outcome, peak heap, visible-node count, hover/selection reconstruction count, and scroll frame timing. Fail if hover, selection, or scroll increments the full call-tree projection counter, or if manual paging is required.

- [ ] **Step 4: Update user and compatibility documentation**

Document search filtering, Forward/Inverted mode, mouse/keyboard behavior, every context transform, source/disassembly fallback, empty reasons, Android implementation mappings, and the exact upstream commit. Add an intentional-differences table containing only Compose styling and Android data terminology.

- [ ] **Step 5: Run focused performance and full verification**

```bash
./gradlew :test-fixtures:test :app-desktop:test
./gradlew :test-fixtures:runFlameGraphPerformancePoc
./gradlew checkAll --rerun-tasks
```

Expected: all commands exit 0; performance output is written under `docs/poc-results`; no test mentions horizontal flame zoom, double-click reset, highlight-only search, or progressive flame paging.

- [ ] **Step 6: Inspect dependency and legacy-session safety**

```bash
./gradlew :app-desktop:dependencies --configuration runtimeClasspath
./gradlew :storage-sqlite:test --tests '*SQLiteLegacyReadOnlyProjectionTest' --rerun-tasks
git diff --check
```

Expected: runtime dependency changes are limited to the new internal `:profile-analysis` project, the legacy projection test passes, and `git diff --check` reports no errors.

- [ ] **Step 7: Commit**

```bash
git add test-fixtures app-desktop docs
git commit -m "Lock Firefox-compatible flame behavior with reproducible evidence" \
  -m "Constraint: Final acceptance requires the full compatibility matrix, million-sample evidence, and legacy-session safety.\nConfidence: high\nScope-risk: broad\nTested: fixture/E2E tests, performance POC, checkAll, runtime dependency inspection, legacy projection, and diff check."
```

## Final Acceptance Checklist

- [ ] All applicable baseline Firefox Flame Graph behaviors have a passing compatibility test.
- [ ] Search, implementation filters, and transforms change sampled stacks and linked Call Tree results.
- [ ] Forward and inverted graphs share stable call-node identity but use independent aggregation semantics.
- [ ] Mouse, keyboard, clipboard, context menu, source/disassembly/fallback, and screen-reader flows pass.
- [ ] No horizontal flame viewport, focus zoom, double-click reset, highlight-only search, or manual page control remains.
- [ ] Million-sample hover, selection, and scroll do not rebuild the complete call tree.
- [ ] Old sessions open and all `checkAll` tasks pass from a clean build.
- [ ] Compatibility and user documentation name baseline commit `9dd90d380ee711f209c4dcd89beec244eb6d3654` and the intentional Android-specific differences.

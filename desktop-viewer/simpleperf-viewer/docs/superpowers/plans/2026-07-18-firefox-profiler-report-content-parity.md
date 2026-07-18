# Firefox Profiler Report Content Parity Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a native Firefox Profiler-style report workspace with a persistent timeline, seven functioning analysis tabs, shared stack controls, real Stack Chart and marker projections, and a globally persistent details region.

**Architecture:** Keep SQLite and the canonical Android profile as the source of truth. Add pure immutable Stack Chart projection in `profile-analysis`, isolated marker queries in `storage-sqlite`, report-level state and actions in `application`, and focused Compose panels in `presentation`; every query remains cancellable and stale generations remain rejected.

**Tech Stack:** Kotlin/JVM 21, Kotlin coroutines and Flow, SQLite JDBC 3.53.1.0, Compose Multiplatform Desktop 1.9.0, Compose UI tests 1.11.1, JUnit/Kotlin test.

## Global Constraints

- Visual baseline is Firefox Profiler commit `faaf1a14affd3c6d8b7342188371079b999abf5b`.
- Functional compatibility baseline is Firefox Profiler commit `9dd90d380ee711f209c4dcd89beec244eb6d3654`.
- Use native Compose; do not add React, WebView, a browser engine, or an embedded Firefox runtime.
- Add no runtime dependency.
- Keep Overview, Top Functions, Call Tree, Flame Graph, Stack Chart, Marker Chart, and Marker Table visible in that exact order.
- Do not fabricate marker or Firefox/Gecko events that were not collected.
- All Frames includes unknown frames; Script includes managed frames; Native includes ELF/native and kernel frames.
- A panel-local projection failure must not replace otherwise usable report content with a report-wide failure.
- Preserve open-session, recent-open, legacy-session, export, and existing Flame Graph behavior.
- Every implementation commit uses the repository Lore commit protocol.

## File Structure

### `profile-analysis`

- Modify `profile-analysis/src/main/kotlin/com/androidperformancestudio/profileanalysis/CallStackContracts.kt` — reduce the public implementation choices to All, Script, and Native.
- Modify `profile-analysis/src/main/kotlin/com/androidperformancestudio/profileanalysis/CallStackFilter.kt` — map Script to managed and Native to native plus kernel.
- Create `profile-analysis/src/main/kotlin/com/androidperformancestudio/profileanalysis/StackChartContracts.kt` — immutable Stack Chart blocks, stable ids, snapshots, and empty reasons.
- Create `profile-analysis/src/main/kotlin/com/androidperformancestudio/profileanalysis/StackChartProjector.kt` — time/depth projection and adjacent-block coalescing.
- Test with `CallStackFilterTest.kt` and new `StackChartProjectorTest.kt`.

### `storage-sqlite`

- Create `storage-sqlite/src/main/kotlin/com/androidperformancestudio/storage/PanelProjection.kt` — panel-local ready/failed result contract.
- Create `storage-sqlite/src/main/kotlin/com/androidperformancestudio/storage/MarkerProjectionModels.kt` — marker row, lane, snapshot, availability, and stable marker id.
- Create `storage-sqlite/src/main/kotlin/com/androidperformancestudio/storage/SQLiteMarkerProjectionQueries.kt` — range-aware point/interval marker query and lane assignment.
- Create `storage-sqlite/src/main/kotlin/com/androidperformancestudio/storage/CallStackTopFunctions.kt` — aggregate Top Functions from the same filtered stack table as Call Tree, Flame Graph, and Stack Chart.
- Modify `ProfileProjectionModels.kt` and `SQLiteProfileProjectionQueries.kt` — publish Stack Chart and marker projections without making their failures report-wide.
- Test with new `SQLiteMarkerProjectionQueriesTest.kt` and existing projection/legacy tests.

### `application`

- Create `application/src/main/kotlin/com/androidperformancestudio/application/ReportWorkspaceState.kt` — report tabs, details visibility, timeline size, panel selections, and shared call-stack query.
- Modify `FlameGraphPanelState.kt` — keep only Flame Graph transient state and transforms that are not report-global.
- Modify `ReportController.kt` — expose the seven-tab state/actions, shared query mutations, marker selection, timeline resize, details visibility, and new projections.
- Test with `ReportControllerTest.kt`, `ProfileWorkspaceControllerTest.kt`, and `ProjectionTestFixtures.kt`.

### `presentation`

- Create `FirefoxReportWorkspace.kt` — persistent timeline, resizable split, tab strip, toolbar, content/details split.
- Create `FirefoxReportTabs.kt` — exact tab order and Show details toggle.
- Create `FirefoxStackToolbar.kt` — All Frames, Script, Native, Invert Call Stack, and Filter Stacks.
- Create `FirefoxReportDetails.kt` — panel-scoped details dispatch and empty/failure content.
- Create `OverviewPanel.kt`, `TopFunctionsPanel.kt`, and `CallTreePanel.kt` — focused extractions from `ReportPage.kt`.
- Create `StackChartPanel.kt`, `StackChartCanvas.kt`, and `StackChartPresenter.kt` — Stack Chart UI and interaction logic.
- Create `MarkerChartPanel.kt`, `MarkerChartCanvas.kt`, `MarkerTablePanel.kt`, and `MarkerPresenter.kt` — marker UI and linked selection.
- Modify `ReportPage.kt`, `FirefoxTimeline.kt`, `FlameGraphPanel.kt`, `FlameGraphToolbar.kt`, `ReportActions.kt`, and `SimpleperfLocalization.kt` — assemble the new workspace and remove duplicated controls.
- Add focused behavior, accessibility, and golden tests rather than expanding `ReportWorkspaceBehaviorTest.kt` into an unmaintainable single file.

---

### Task 1: Replace the implementation filter with Firefox report choices

**Files:**
- Modify: `profile-analysis/src/main/kotlin/com/androidperformancestudio/profileanalysis/CallStackContracts.kt`
- Modify: `profile-analysis/src/main/kotlin/com/androidperformancestudio/profileanalysis/CallStackFilter.kt`
- Modify: `profile-analysis/src/test/kotlin/com/androidperformancestudio/profileanalysis/CallStackFilterTest.kt`
- Modify: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/FlameGraphToolbar.kt`
- Modify: `presentation/src/test/kotlin/com/androidperformancestudio/presentation/FirefoxFlameGraphChromeTest.kt`

**Interfaces:**
- Consumes: existing `FrameImplementation` and `CallStackAnalysisQuery`.
- Produces: `ImplementationFilter.ALL`, `ImplementationFilter.SCRIPT`, and `ImplementationFilter.NATIVE` for every later task.

- [ ] **Step 1: Replace implementation-filter tests with the approved three-choice contract**

Add these assertions to `CallStackFilterTest.kt` using its existing `tableOf` fixture:

```kotlin
@Test
fun `script retains only managed frames`() {
    val filtered = CallStackFilter.apply(mixedImplementationTable(), query(ImplementationFilter.SCRIPT))

    assertEquals(listOf(FrameImplementation.MANAGED), filtered.implementations())
}

@Test
fun `native retains native and kernel frames`() {
    val filtered = CallStackFilter.apply(mixedImplementationTable(), query(ImplementationFilter.NATIVE))

    assertEquals(
        setOf(FrameImplementation.NATIVE, FrameImplementation.KERNEL),
        filtered.implementations().toSet(),
    )
}

@Test
fun `unknown frames are visible only under all frames`() {
    assertTrue(CallStackFilter.apply(mixedImplementationTable(), query(ImplementationFilter.ALL)).hasUnknownFrame())
    assertFalse(CallStackFilter.apply(mixedImplementationTable(), query(ImplementationFilter.SCRIPT)).hasUnknownFrame())
    assertFalse(CallStackFilter.apply(mixedImplementationTable(), query(ImplementationFilter.NATIVE)).hasUnknownFrame())
}
```

- [ ] **Step 2: Run the focused test and verify the old enum cannot satisfy it**

Run: `./gradlew :profile-analysis:test --tests '*CallStackFilterTest' --no-daemon`

Expected: FAIL because `ImplementationFilter.SCRIPT` does not exist and Native excludes kernel frames.

- [ ] **Step 3: Replace the enum and exact matching rule**

Use this contract in `CallStackContracts.kt`:

```kotlin
enum class ImplementationFilter {
    ALL,
    SCRIPT,
    NATIVE,
}
```

Use this mapping in `CallStackFilter.kt`:

```kotlin
private fun FrameImplementation.matches(filter: ImplementationFilter): Boolean =
    when (filter) {
        ImplementationFilter.ALL -> true
        ImplementationFilter.SCRIPT -> this == FrameImplementation.MANAGED
        ImplementationFilter.NATIVE -> this == FrameImplementation.NATIVE || this == FrameImplementation.KERNEL
    }
```

Update toolbar labels to `All Frames`, `Script`, and `Native`; remove Managed, Kernel, and Unknown as selectable choices.

- [ ] **Step 4: Run analysis and presentation tests**

Run: `./gradlew :profile-analysis:test :presentation:test --tests '*CallStackFilterTest' --tests '*FirefoxFlameGraphChromeTest' --no-daemon`

Expected: PASS.

- [ ] **Step 5: Commit the contract change**

```text
Align frame filtering with the Firefox report contract

Constraint: Unknown frames remain visible only through All Frames.
Rejected: Separate kernel and unknown controls | The approved toolbar exposes only All Frames, Script, and Native
Confidence: high
Scope-risk: moderate
Tested: profile-analysis CallStackFilterTest and presentation FirefoxFlameGraphChromeTest
```

### Task 2: Add a pure, immutable Stack Chart projection

**Files:**
- Create: `profile-analysis/src/main/kotlin/com/androidperformancestudio/profileanalysis/StackChartContracts.kt`
- Create: `profile-analysis/src/main/kotlin/com/androidperformancestudio/profileanalysis/StackChartProjector.kt`
- Create: `profile-analysis/src/test/kotlin/com/androidperformancestudio/profileanalysis/StackChartProjectorTest.kt`

**Interfaces:**
- Consumes: `CallStackTable`, `CallStackAnalysisQuery`, `CallStackFilter`, `CallStackDirection`, and an exclusive viewport end.
- Produces: `StackChartProjector.project(CallStackTable, CallStackAnalysisQuery, Long): StackChartSnapshot`.

- [ ] **Step 1: Write failing projection tests**

Create tests covering time, direction, filtering, and coalescing:

```kotlin
@Test
fun `projects sample time horizontally and root depth vertically`() {
    val snapshot = StackChartProjector.project(twoSampleTable(), CallStackAnalysisQuery(), 31)

    assertEquals(10, snapshot.blocks.first().startNanos)
    assertEquals(20, snapshot.blocks.first().endNanosExclusive)
    assertEquals(0, snapshot.blocks.first().depth)
    assertEquals(31, snapshot.endNanosExclusive)
}

@Test
fun `inverted direction reverses visible frame depth`() {
    val query = CallStackAnalysisQuery(direction = CallStackDirection.INVERTED)

    assertEquals("leaf", StackChartProjector.project(twoSampleTable(), query, 31).frameAtDepth(0).symbolName)
}

@Test
fun `adjacent equal frames coalesce only for the same thread and depth`() {
    val snapshot = StackChartProjector.project(repeatedFrameTable(), CallStackAnalysisQuery(), 31)

    assertEquals(listOf(10L to 31L), snapshot.blocksAtDepth(0).map { it.startNanos to it.endNanosExclusive })
}
```

- [ ] **Step 2: Run the new test and verify the contracts are absent**

Run: `./gradlew :profile-analysis:test --tests '*StackChartProjectorTest' --no-daemon`

Expected: FAIL because `StackChartProjector` and `StackChartSnapshot` do not exist.

- [ ] **Step 3: Add immutable Stack Chart contracts**

Define these public types in `StackChartContracts.kt`:

```kotlin
@JvmInline
value class StackChartBlockId(val value: String)

data class StackChartBlock(
    val id: StackChartBlockId,
    val sampleId: Long,
    val startNanos: Long,
    val endNanosExclusive: Long,
    val depth: Int,
    val frameId: Long,
    val threadKey: String,
    val weight: Long,
)

enum class StackChartEmptyReason {
    NO_SAMPLES,
    RANGE_EMPTY,
    FILTERED_ALL,
}

data class StackChartSnapshot(
    val framesById: Map<Long, CallStackFrame>,
    val blocks: List<StackChartBlock>,
    val startNanos: Long?,
    val endNanosExclusive: Long?,
    val maxDepth: Int,
    val emptyReason: StackChartEmptyReason?,
)
```

Construct defensive immutable copies in the same style as `CallStackTable` and `FlameGraphSnapshot`.

- [ ] **Step 4: Implement deterministic projection and coalescing**

`StackChartProjector.project` must:

```kotlin
fun project(
    source: CallStackTable,
    query: CallStackAnalysisQuery,
    viewportEndNanosExclusive: Long,
): StackChartSnapshot
```

Apply `CallStackFilter`, then `CallStackTransformer` with `query.transforms`, reverse `frameIdsRootToLeaf` only for `INVERTED`, sort stacks by timestamp then sample id, use the next sample time as each sample's exclusive end, use `viewportEndNanosExclusive` for the last sample, and merge blocks only when frame id, thread key, and depth match and the previous end equals the next start. Build ids as `"${threadKey}:${depth}:${frameId}:${startNanos}"`.

- [ ] **Step 5: Run the projection tests and static checks**

Run: `./gradlew :profile-analysis:test :profile-analysis:check --no-daemon`

Expected: PASS.

- [ ] **Step 6: Commit the Stack Chart core**

```text
Give Stack Chart a truthful time-based projection

Constraint: Block aggregation must preserve frame identity, thread, depth, and exact covered interval.
Rejected: Reuse Flame Graph rows | Flame Graph width represents weight rather than sample time
Confidence: high
Scope-risk: moderate
Tested: profile-analysis StackChartProjectorTest and module check
```

### Task 3: Query canonical markers with truthful availability

**Files:**
- Create: `storage-sqlite/src/main/kotlin/com/androidperformancestudio/storage/PanelProjection.kt`
- Create: `storage-sqlite/src/main/kotlin/com/androidperformancestudio/storage/MarkerProjectionModels.kt`
- Create: `storage-sqlite/src/main/kotlin/com/androidperformancestudio/storage/SQLiteMarkerProjectionQueries.kt`
- Create: `storage-sqlite/src/test/kotlin/com/androidperformancestudio/storage/SQLiteMarkerProjectionQueriesTest.kt`

**Interfaces:**
- Consumes: schema-v2 `profile_marker`, `profile_thread`, `profile_process`, `ProfileQuery`, and legacy-schema detection.
- Produces: `PanelProjection<MarkerProjectionSnapshot>` and `SQLiteMarkerProjectionQueries.load(Connection, ProfileQuery, String)`.

- [ ] **Step 1: Write failing point, interval, range, and legacy tests**

Create tests with the existing canonical-store fixtures:

```kotlin
@Test
fun `point markers use one nanosecond exclusive extent`() {
    val snapshot = queryMarkers(pointMarker(start = 30), ProfileQuery())

    assertEquals(30, snapshot.markers.single().startNanos)
    assertEquals(31, snapshot.markers.single().endNanosExclusive)
}

@Test
fun `interval markers are selected by overlap`() {
    val snapshot = queryMarkers(intervalMarker(start = 10, end = 40), ProfileQuery(20, 30))

    assertEquals("draw", snapshot.markers.single().name)
}

@Test
fun `range without matching markers reports empty range`() {
    assertEquals(MarkerEmptyReason.RANGE_EMPTY, queryMarkers(pointMarker(30), ProfileQuery(40, 50)).emptyReason)
}

@Test
fun `marker search without matches reports filtered empty`() {
    assertEquals(MarkerEmptyReason.FILTERED_EMPTY, queryMarkers(pointMarker(30), ProfileQuery(), "missing").emptyReason)
}

@Test
fun `legacy database reports markers not collected`() {
    assertEquals(MarkerAvailability.NOT_COLLECTED, queryLegacyMarkers().availability)
}
```

- [ ] **Step 2: Run the focused storage test**

Run: `./gradlew :storage-sqlite:test --tests '*SQLiteMarkerProjectionQueriesTest' --no-daemon`

Expected: FAIL because marker projection types and query do not exist.

- [ ] **Step 3: Add panel and marker contracts**

Use these exact top-level contracts:

```kotlin
sealed interface PanelProjection<out T> {
    data class Ready<T>(val value: T) : PanelProjection<T>
    data class Failed(val code: String, val message: String) : PanelProjection<Nothing>
}

@JvmInline
value class ProfileMarkerId(val value: Long)

enum class MarkerAvailability { AVAILABLE, NOT_COLLECTED }
enum class MarkerEmptyReason { PROFILE_EMPTY, RANGE_EMPTY, FILTERED_EMPTY }

data class MarkerProjectionRow(
    val id: ProfileMarkerId,
    val sourceId: String,
    val processId: Int?,
    val threadId: Int?,
    val threadName: String?,
    val startNanos: Long,
    val endNanosExclusive: Long,
    val interval: Boolean,
    val schema: String,
    val name: String,
    val payloadJson: String,
)

data class MarkerLane(val key: String, val label: String, val markerIds: List<ProfileMarkerId>)

data class MarkerProjectionSnapshot(
    val availability: MarkerAvailability,
    val emptyReason: MarkerEmptyReason?,
    val markers: List<MarkerProjectionRow>,
    val lanes: List<MarkerLane>,
)
```

- [ ] **Step 4: Implement the SQLite query and deterministic lanes**

Use `end_nanos > rangeStart` for intervals and `start_nanos >= rangeStart` for points; use `start_nanos < rangeEnd` for both. Match a non-blank marker search against name, schema, and payload using escaped case-insensitive `LIKE`. Run bounded existence checks to distinguish Profile Empty, Range Empty, and Filtered Empty. Convert a point at `Long.MAX_VALUE` to the same end value and every other point to `start + 1`. Order by start, marker id. Group lanes by `sourceId`, process id, then thread id, with a global lane for null threads.

Catch no exception inside `SQLiteMarkerProjectionQueries`; isolation belongs to the caller in Task 4.

- [ ] **Step 5: Run marker, canonical-store, and legacy tests**

Run: `./gradlew :storage-sqlite:test --tests '*SQLiteMarkerProjectionQueriesTest' --tests '*SQLiteCanonicalProfileStoreTest' --tests '*SQLiteLegacyReadOnlyProjectionTest' --no-daemon`

Expected: PASS.

- [ ] **Step 6: Commit marker query support**

```text
Expose canonical markers without inventing missing data

Constraint: Point markers retain point semantics and legacy sessions report Not Collected.
Rejected: Hide marker tabs for empty sources | The approved report keeps all seven tabs visible
Confidence: high
Scope-risk: moderate
Tested: marker projection, canonical store, and legacy read-only tests
```

### Task 4: Publish Stack Chart and marker projections with failure isolation

**Files:**
- Modify: `storage-sqlite/src/main/kotlin/com/androidperformancestudio/storage/ProfileProjectionModels.kt`
- Modify: `storage-sqlite/src/main/kotlin/com/androidperformancestudio/storage/SQLiteProfileProjectionQueries.kt`
- Create: `storage-sqlite/src/main/kotlin/com/androidperformancestudio/storage/CallStackTopFunctions.kt`
- Modify: `storage-sqlite/src/test/kotlin/com/androidperformancestudio/storage/SQLiteProfileProjectionQueriesTest.kt`
- Modify: `application/src/test/kotlin/com/androidperformancestudio/application/ProjectionTestFixtures.kt`

**Interfaces:**
- Consumes: `StackChartProjector`, `SQLiteMarkerProjectionQueries`, and `PanelProjection`.
- Produces: `ProfileProjectionSnapshot.stackChart` and `ProfileProjectionSnapshot.markers`.

- [ ] **Step 1: Add failing projection-publication tests**

Add assertions that the core projection publishes both panels and that a marker-only SQL failure is represented locally:

```kotlin
@Test
fun `core projection publishes stack chart and marker snapshots`() {
    val snapshot = populatedStore().projectCore(ProfileProjectionRequest())

    assertIs<PanelProjection.Ready<StackChartSnapshot>>(snapshot.stackChart)
    assertIs<PanelProjection.Ready<MarkerProjectionSnapshot>>(snapshot.markers)
}

@Test
fun `marker projection failure preserves sample panels`() {
    val snapshot = storeWithUnreadableMarkerTable().projectCore(ProfileProjectionRequest())

    assertTrue(snapshot.flameGraph.callNodes.size > 0)
    assertIs<PanelProjection.Failed>(snapshot.markers)
}

@Test
fun `top functions use the shared script stack filter`() {
    val request = ProfileProjectionRequest(
        callStackAnalysis = CallStackAnalysisQuery(implementation = ImplementationFilter.SCRIPT),
    )

    assertEquals(listOf("managedFrame"), mixedStore().projectCore(request).topFunctions.map(TopFunction::symbolName))
}
```

- [ ] **Step 2: Run the projection test and verify fields are absent**

Run: `./gradlew :storage-sqlite:test --tests '*SQLiteProfileProjectionQueriesTest' --no-daemon`

Expected: FAIL because `stackChart` and `markers` are absent.

- [ ] **Step 3: Extend the immutable projection snapshot**

Add:

```kotlin
val stackChart: PanelProjection<StackChartSnapshot>
val markers: PanelProjection<MarkerProjectionSnapshot>
```

to `ProfileProjectionSnapshot` before its defaulted compatibility fields, add `markerSearch: String = ""` to `ProfileProjectionRequest`, and update every fixture explicitly.

- [ ] **Step 4: Share the loaded stack table and isolate optional panels**

Within one read transaction, load `CallStackTable` once. Apply `CallStackFilter` and transforms to produce Call Tree, Flame Graph, and Top Functions; pass the same loaded source plus the same immutable query to `StackChartProjector`, avoiding a second recursive SQLite stack query. `CallStackTopFunctions` aggregates inclusive weight once per function per sample, self weight from the visible leaf, sample count once per function per sample, and distinct thread count. It applies the existing sort and limit after aggregation. Wrap Stack Chart and marker projection separately:

```kotlin
private inline fun <T> isolatedPanel(
    code: String,
    project: () -> T,
): PanelProjection<T> =
    try {
        PanelProjection.Ready(project())
    } catch (cancelled: java.util.concurrent.CancellationException) {
        throw cancelled
    } catch (interrupted: SQLException) {
        if (interrupted.errorCode == SQLITE_INTERRUPT) throw interrupted
        PanelProjection.Failed(code, interrupted.message ?: code)
    } catch (failure: Exception) {
        PanelProjection.Failed(code, failure.message ?: code)
    }
```

Define `private const val SQLITE_INTERRUPT = 9`. Use codes `STACK_CHART_QUERY_FAILED` and `MARKER_QUERY_FAILED`. The rethrows preserve whole-generation cancellation while ordinary optional-panel SQL errors remain local.

- [ ] **Step 5: Run storage and application fixture compilation**

Run: `./gradlew :storage-sqlite:test :application:test --tests '*SQLiteProfileProjectionQueriesTest' --tests '*ProjectionTestFixtures*' --no-daemon`

Expected: PASS.

- [ ] **Step 6: Commit projection integration**

```text
Keep optional analysis failures inside their panels

Constraint: Cancellation still aborts the entire obsolete generation.
Rejected: Fail the whole report for marker or Stack Chart errors | Existing sample panels remain independently useful
Confidence: high
Scope-risk: broad
Tested: storage projection and application fixture tests
```

### Task 5: Move shared report controls into report-level state

**Files:**
- Create: `application/src/main/kotlin/com/androidperformancestudio/application/ReportWorkspaceState.kt`
- Modify: `application/src/main/kotlin/com/androidperformancestudio/application/FlameGraphPanelState.kt`
- Modify: `application/src/main/kotlin/com/androidperformancestudio/application/ReportController.kt`
- Modify: `application/src/test/kotlin/com/androidperformancestudio/application/ReportControllerTest.kt`

**Interfaces:**
- Consumes: the expanded `ProfileProjectionSnapshot`.
- Produces: seven `ReportTab` values, `ReportWorkspaceUiState`, panel-scoped selections, shared `callStackQuery`, and controller mutations used by presentation.

- [ ] **Step 1: Write state-transition tests before changing the enum**

Add:

```kotlin
@Test
fun `report tabs use the fixed Firefox order`() {
    assertEquals(
        listOf(OVERVIEW, TOP_FUNCTIONS, CALL_TREE, FLAME_GRAPH, STACK_CHART, MARKER_CHART, MARKER_TABLE),
        ReportTab.entries,
    )
}

@Test
fun `tab changes preserve global details and stack query`() = runTest {
    val controller = readyController()
    controller.setDetailsVisible(true)
    controller.updateImplementationFilter(ImplementationFilter.SCRIPT)
    controller.selectTab(ReportTab.MARKER_TABLE)

    assertTrue(controller.state.value.workspace.detailsVisible)
    assertEquals(ImplementationFilter.SCRIPT, controller.state.value.callStackQuery.implementation)
}

@Test
fun `closing details preserves panel selection`() = runTest {
    val controller = readyControllerWithMarkers()
    controller.selectMarker(ProfileMarkerId(7))
    controller.setDetailsVisible(false)

    assertEquals(ProfileMarkerId(7), controller.state.value.workspace.selections.markerId)
}
```

- [ ] **Step 2: Run the controller tests and verify the old state fails**

Run: `./gradlew :application:test --tests '*ReportControllerTest' --no-daemon`

Expected: FAIL because the new tabs and workspace state do not exist.

- [ ] **Step 3: Add the report workspace state contract**

Create:

```kotlin
enum class ReportTab {
    OVERVIEW,
    TOP_FUNCTIONS,
    CALL_TREE,
    FLAME_GRAPH,
    STACK_CHART,
    MARKER_CHART,
    MARKER_TABLE,
}

data class ReportWorkspaceUiState(
    val detailsVisible: Boolean = true,
    val timelineHeightDp: Int = 220,
    val markerSearchText: String = "",
    val selections: ReportPanelSelections = ReportPanelSelections(),
)

data class ReportPanelSelections(
    val overviewFindingRuleId: String? = null,
    val topFunctionKey: String? = null,
    val callNodeId: FlameCallNodeId? = null,
    val stackChartBlockId: StackChartBlockId? = null,
    val markerId: ProfileMarkerId? = null,
)
```

Move `ReportTab` out of `ReportController.kt`. Add `callStackQuery: CallStackAnalysisQuery` and `workspace: ReportWorkspaceUiState` to `ReportState`. Remove `topSearch` and `callTreeSearch`; both use `callStackQuery.searchText`. Remove `query` from `FlameGraphPanelState`; keep Flame Graph selection, hover, context, details loading, and invalid transforms. Stack transforms remain in the shared `callStackQuery` because they change the projected stacks.

- [ ] **Step 4: Add controller mutations and preserve session-safe projection behavior**

Add:

```kotlin
fun setDetailsVisible(visible: Boolean)
fun setTimelineHeightDp(heightDp: Int)
fun selectOverviewFinding(ruleId: String?)
fun selectTopFunction(key: String?)
fun selectCallNode(nodeId: FlameCallNodeId?)
fun selectStackChartBlock(blockId: StackChartBlockId?)
fun selectMarker(markerId: ProfileMarkerId?)
fun updateMarkerSearch(searchText: String)
fun updateTopFunctionSort(sort: TopFunctionSort, descending: Boolean)
```

Clamp height to `120..480`. Update search, implementation, direction, preview, and transform code to mutate `ReportState.callStackQuery`. Build `ProfileProjectionRequest.callStackAnalysis` from that shared query and `ProfileProjectionRequest.markerSearch` from `workspace.markerSearchText`. Clear hover/context when leaving Flame Graph, but do not close global details or clear panel selections.

- [ ] **Step 5: Publish new projections in `ReportData` and retain marker selection only when present**

Add `stackChart: PanelProjection<StackChartSnapshot>` and `markers: PanelProjection<MarkerProjectionSnapshot>` to `ReportData`. Remove the independent `topSearch` state and use `callStackQuery.searchText` so Top Functions consumes the same filtered stacks as the other stack panels. On publication, retain each panel selection only when its new ready projection still contains the selected identity; preserve a selection when that panel alone failed so Retry can restore its details.

- [ ] **Step 6: Run the application suite**

Run: `./gradlew :application:test :application:check --no-daemon`

Expected: PASS.

- [ ] **Step 7: Commit report-level state**

```text
Preserve one analysis context across every report tab

Constraint: Tab changes keep range, frame filter, inversion, stack filter, details visibility, and scoped selections.
Rejected: Store shared controls inside Flame Graph | They govern Call Tree, Stack Chart, and Top Functions too
Confidence: high
Scope-risk: broad
Tested: application ReportControllerTest and module check
```

### Task 6: Build the persistent timeline, tab strip, shared toolbar, and details split

**Files:**
- Create: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/FirefoxReportWorkspace.kt`
- Create: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/FirefoxReportTabs.kt`
- Create: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/FirefoxStackToolbar.kt`
- Create: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/FirefoxReportDetails.kt`
- Modify: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/ReportPage.kt`
- Modify: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/FirefoxTimeline.kt`
- Modify: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/ReportActions.kt`
- Create: `presentation/src/test/kotlin/com/androidperformancestudio/presentation/FirefoxReportWorkspaceTest.kt`

**Interfaces:**
- Consumes: `ReportState`, `ReportData`, and the Task 5 controller callbacks.
- Produces: the report shell used by every panel and test tags `report-timeline`, `report-tabs`, `stack-toolbar`, `report-content`, and `report-details`.

- [ ] **Step 1: Write failing Compose behavior tests**

Create tests asserting the persistent structure:

```kotlin
@Test
fun `timeline and seven tabs remain mounted while content switches`() = runComposeUiTest {
    setContent { ReportPage(readyState(), actions()) }

    onNodeWithTag("report-timeline").assertExists()
    ReportTab.entries.forEach { onNodeWithTag("report-tab-${it.name}").assertExists() }
    onNodeWithTag("report-tab-MARKER_TABLE").performClick()
    onNodeWithTag("report-timeline").assertExists()
    onNodeWithTag("marker-table-panel").assertExists()
}

@Test
fun `details toggle survives tab changes`() = runComposeUiTest {
    setContent { ReportPage(readyState(detailsVisible = true), actions()) }

    onNodeWithTag("show-details").performClick()
    onNodeWithTag("report-tab-CALL_TREE").performClick()
    onNodeWithTag("report-details").assertDoesNotExist()
}
```

- [ ] **Step 2: Run the workspace test and verify the old left navigation fails it**

Run: `./gradlew :presentation:test --tests '*FirefoxReportWorkspaceTest' --no-daemon`

Expected: FAIL because the persistent shell and tags are absent.

- [ ] **Step 3: Implement the shell with bounded resizing**

`FirefoxReportWorkspace` must render `TimelineReport` with `Modifier.height(state.workspace.timelineHeightDp.dp)`, a 4 dp drag divider, the tab strip, toolbar, and a `Row` containing selected content plus a 300 dp details region when visible. Dragging dispatches the clamped integer height through `onTimelineHeightDp`.

- [ ] **Step 4: Implement exact tab and toolbar controls**

Use `ReportTab.entries` in order. Give each tab `selected` semantics and `report-tab-${tab.name}`. The toolbar dispatches:

```kotlin
actions.onFlameImplementation(ImplementationFilter.ALL)
actions.onFlameImplementation(ImplementationFilter.SCRIPT)
actions.onFlameImplementation(ImplementationFilter.NATIVE)
actions.onCallTreeDirection(
    if (state.callStackQuery.direction == CallStackDirection.FORWARD) {
        CallStackDirection.INVERTED
    } else {
        CallStackDirection.FORWARD
    },
)
actions.onFlameSearch(text)
```

Keep the current debounced search draft behavior and move it from the Flame Graph-only toolbar.

- [ ] **Step 5: Extend `ReportActions` and remove the left navigation**

Replace `onTopFunctions: (String, TopFunctionSort, Boolean) -> Unit` with `onTopFunctionSort: (TopFunctionSort, Boolean) -> Unit`. Add `onDetailsVisible: (Boolean) -> Unit`, `onTimelineHeightDp: (Int) -> Unit`, `onSelectOverviewFinding: (String?) -> Unit`, `onSelectTopFunction: (String?) -> Unit`, `onSelectCallNode: (FlameCallNodeId?) -> Unit`, `onSelectStackChartBlock: (StackChartBlockId?) -> Unit`, `onSelectMarker: (ProfileMarkerId?) -> Unit`, and `onMarkerSearch: (String) -> Unit`. `ReportPage` delegates ready reports to `FirefoxReportWorkspace`; Closed, Loading, and Failed statuses remain unchanged.

- [ ] **Step 6: Run workspace and existing report behavior tests**

Run: `./gradlew :presentation:test --tests '*FirefoxReportWorkspaceTest' --tests '*ReportWorkspaceBehaviorTest' --no-daemon`

Expected: PASS after updating old assertions from left navigation to the new tab strip.

- [ ] **Step 7: Commit the report shell**

```text
Make the Firefox analysis structure persistent across tabs

Constraint: Timeline, tab order, shared controls, and details visibility remain stable while content changes.
Rejected: Keep the old left navigation | Timeline is report-global and the approved tabs are horizontal
Confidence: high
Scope-risk: broad
Tested: FirefoxReportWorkspaceTest and ReportWorkspaceBehaviorTest
```

### Task 7: Move existing reports into the new panel contract

**Files:**
- Create: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/OverviewPanel.kt`
- Create: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/TopFunctionsPanel.kt`
- Create: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/CallTreePanel.kt`
- Modify: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/ReportPage.kt`
- Modify: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/FlameGraphPanel.kt`
- Modify: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/FlameGraphToolbar.kt`
- Create: `presentation/src/test/kotlin/com/androidperformancestudio/presentation/FirefoxExistingPanelsTest.kt`

**Interfaces:**
- Consumes: the Task 6 shell and shared toolbar state.
- Produces: four functioning panels with no duplicate shared controls and Diagnostics merged into Overview.

- [ ] **Step 1: Write failing panel migration tests**

```kotlin
@Test
fun `overview contains diagnostics without a diagnostics tab`() = runComposeUiTest {
    setContent { ReportPage(readyStateWithFinding(), actions()) }

    onNodeWithText("Data quality").assertExists()
    onNodeWithText("Recommendations").assertExists()
    onNodeWithTag("report-tab-DIAGNOSTICS").assertDoesNotExist()
}

@Test
fun `flame graph has one shared filter toolbar`() = runComposeUiTest {
    setContent { ReportPage(flameState(), actions()) }

    onAllNodesWithTag("stack-toolbar").assertCountEquals(1)
}
```

- [ ] **Step 2: Run the new tests**

Run: `./gradlew :presentation:test --tests '*FirefoxExistingPanelsTest' --no-daemon`

Expected: FAIL because Diagnostics is separate and Flame Graph owns duplicate controls.

- [ ] **Step 3: Extract Overview, Top Functions, and Call Tree**

Move the existing Composables and their private helpers from `ReportPage.kt` into the three focused files without changing their calculation rules. Put `DiagnosticsReport` after Overview summaries and preserve `DiagnosticFinding.navigation`.

- [ ] **Step 4: Make panels consume the shared query**

Top Functions and Call Tree use `state.callStackQuery.searchText` and direction. Remove the standalone Call Tree search field. Keep sort controls in Top Functions because sorting is panel-specific.

- [ ] **Step 5: Remove duplicate Flame Graph query controls**

Delete implementation, direction, and search controls from `FlameGraphToolbar`; retain transform navigator, undo, clear, context menu, tooltip, and frame-details resolution. Pass `state.callStackQuery` separately to `FlameGraphPanel` while its `FlameGraphPanelState` carries only transient state.

- [ ] **Step 6: Run presentation regressions**

Run: `./gradlew :presentation:test --tests '*FirefoxExistingPanelsTest' --tests '*FlameGraph*Test' --tests '*ReportWorkspaceBehaviorTest' --no-daemon`

Expected: PASS.

- [ ] **Step 7: Commit the migrated panels**

```text
Unify existing reports under the Firefox workspace controls

Constraint: Existing calculation, navigation, and Flame Graph interaction semantics remain intact.
Rejected: Duplicate filters inside each panel | A single report query must govern compatible views
Confidence: high
Scope-risk: broad
Tested: existing-panel, Flame Graph, and report workspace presentation tests
```

### Task 8: Render and interact with the real Stack Chart

**Files:**
- Create: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/StackChartPresenter.kt`
- Create: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/StackChartCanvas.kt`
- Create: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/StackChartPanel.kt`
- Create: `presentation/src/test/kotlin/com/androidperformancestudio/presentation/StackChartPresenterTest.kt`
- Create: `presentation/src/test/kotlin/com/androidperformancestudio/presentation/StackChartComposeUiTest.kt`
- Create: `presentation/src/test/kotlin/com/androidperformancestudio/presentation/StackChartVisualGoldenTest.kt`

**Interfaces:**
- Consumes: `PanelProjection<StackChartSnapshot>`, committed range, `ReportPanelSelections.stackChartBlockId`, and details action.
- Produces: `StackChartPanel` with hover, selection, range commit, keyboard semantics, and `stack-chart-panel` test tag.

- [ ] **Step 1: Write presenter and geometry tests**

```kotlin
@Test
fun `maps time and depth into the visible viewport`() {
    val rect = StackChartPresenter.blockRect(block(20, 40, depth = 2), viewport(0, 100), width = 500f)

    assertEquals(100f, rect.left)
    assertEquals(200f, rect.right)
    assertEquals(32f, rect.top)
    assertEquals(48f, rect.bottom)
}

@Test
fun `hit test returns the topmost matching block`() {
    assertEquals(StackChartBlockId("selected"), StackChartPresenter.hitTest(overlappingBlocks(), 25, 2))
}
```

- [ ] **Step 2: Run tests and verify Stack Chart UI is absent**

Run: `./gradlew :presentation:test --tests '*StackChartPresenterTest' --tests '*StackChartComposeUiTest' --no-daemon`

Expected: FAIL because Stack Chart presentation files do not exist.

- [ ] **Step 3: Implement pure layout and hit testing**

Use 16 dp rows, the pinned Firefox frame colors, a one-device-pixel inter-block gap, device-pixel snapping, and horizontal coordinates:

```kotlin
val left = ((block.startNanos - viewport.startNanos).toDouble() / viewport.durationNanos * width).toFloat()
val right = ((block.endNanosExclusive - viewport.startNanos).toDouble() / viewport.durationNanos * width).toFloat()
```

Clamp both coordinates to the viewport and skip non-positive widths.

- [ ] **Step 4: Implement Canvas, hover, selection, and range commit**

Use one Canvas, materialize only blocks intersecting the viewport, draw fitted labels, expose visible blocks through a semantics overlay, and dispatch a valid drag range through `actions.onTimeRange(start, end)`. Clicking a block dispatches `actions.onSelectStackChartBlock(block.id)`; it updates details content without changing the global details-visible preference.

- [ ] **Step 5: Implement ready, empty, and failed panel states**

- `PanelProjection.Ready` with blocks renders Canvas.
- `NO_SAMPLES`, `RANGE_EMPTY`, and `FILTERED_ALL` render distinct messages and recovery actions.
- `PanelProjection.Failed` renders its code, message, and `Retry` action without replacing the report.

- [ ] **Step 6: Add light/dark golden tests and run them**

Run: `./gradlew :presentation:test --tests '*StackChart*Test' --no-daemon`

Expected: PASS and deterministic golden output for populated, selected, empty, and failed states.

- [ ] **Step 7: Commit Stack Chart UI**

```text
Make Stack Chart explain call stacks over time

Constraint: Horizontal geometry represents timestamps rather than aggregate weight.
Rejected: Alias the Flame Graph Canvas | The two panels encode different dimensions
Confidence: high
Scope-risk: broad
Tested: Stack Chart presenter, Compose UI, accessibility, and visual golden tests
```

### Task 9: Add linked Marker Chart, Marker Table, and marker details

**Files:**
- Create: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/MarkerPresenter.kt`
- Create: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/MarkerChartCanvas.kt`
- Create: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/MarkerChartPanel.kt`
- Create: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/MarkerTablePanel.kt`
- Modify: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/FirefoxTimeline.kt`
- Modify: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/FirefoxReportDetails.kt`
- Create: `presentation/src/test/kotlin/com/androidperformancestudio/presentation/MarkerPresenterTest.kt`
- Create: `presentation/src/test/kotlin/com/androidperformancestudio/presentation/MarkerPanelsComposeUiTest.kt`
- Create: `presentation/src/test/kotlin/com/androidperformancestudio/presentation/MarkerPanelsVisualGoldenTest.kt`

**Interfaces:**
- Consumes: one `PanelProjection<MarkerProjectionSnapshot>` and `ReportWorkspaceUiState.selections.markerId`.
- Produces: linked chart/table selection and marker-specific details.

- [ ] **Step 1: Write failing marker presenter and linked-selection tests**

```kotlin
@Test
fun `point and interval markers use distinct geometry`() {
    assertIs<MarkerGlyph.Point>(MarkerPresenter.glyph(pointMarker()))
    assertIs<MarkerGlyph.Interval>(MarkerPresenter.glyph(intervalMarker()))
}

@Test
fun `table selection is visible in marker chart after tab switch`() = runComposeUiTest {
    val harness = reportHarnessWithMarkers()
    setContent { harness.Content() }

    onNodeWithTag("report-tab-MARKER_TABLE").performClick()
    onNodeWithTag("marker-row-7").performClick()
    onNodeWithTag("report-tab-MARKER_CHART").performClick()
    onNodeWithTag("marker-glyph-7").assertIsSelected()
}
```

- [ ] **Step 2: Run marker presentation tests**

Run: `./gradlew :presentation:test --tests '*MarkerPresenterTest' --tests '*MarkerPanelsComposeUiTest' --no-daemon`

Expected: FAIL because marker panels do not exist.

- [ ] **Step 3: Implement marker geometry and chart virtualization**

Point markers render as a vertical pin/diamond centered at their timestamp. Interval markers render from start to end with a minimum visible width of one device pixel. Group rows by `MarkerLane`, cull markers outside the visible range, and use a single Canvas plus a semantics overlay.

- [ ] **Step 4: Implement the virtualized table and local sort state**

Render columns `Name`, `Start`, `Duration`, `Thread`, and `Schema`. Default sort is start ascending. Header clicks cycle ascending then descending for start, duration, name, thread, and schema. Use `LazyColumn` keyed by `ProfileMarkerId.value`.

- [ ] **Step 5: Implement linked selection and details**

Both panels dispatch `actions.onSelectMarker(id)`. Add a compact `Filter markers` field shared by Marker Chart and Marker Table; debounce it into `actions.onMarkerSearch(text)` and keep its draft across the two tabs. The details region looks up the selected row and renders name, start, end/duration, process/thread, schema, and pretty-printed JSON payload; invalid JSON remains visible as raw text with a non-fatal formatting message.

Extend `FirefoxTimeline` with marker lanes sourced from the same `MarkerProjectionSnapshot`. A Marker Table or Marker Chart selection highlights and scrolls the matching timeline marker into the current viewport; selecting the timeline glyph dispatches the same `onSelectMarker` action.

- [ ] **Step 6: Implement truthful empty and failure states**

Render separate copy for Not Collected, Profile Empty, Range Empty, Filtered Empty, and `MARKER_QUERY_FAILED`. Keep both tabs mounted and actionable in every state.

- [ ] **Step 7: Add golden tests and run all marker tests**

Run: `./gradlew :presentation:test --tests '*Marker*Test' --no-daemon`

Expected: PASS for light/dark populated, selected, not-collected, range-empty, and failed states.

- [ ] **Step 8: Commit marker panels**

```text
Keep marker analysis visible and truthful for every session

Constraint: Marker Chart and Marker Table share selection and distinguish uncollected data from an empty range.
Rejected: Hide empty marker tabs | Fixed visibility is part of the approved Firefox structure
Confidence: high
Scope-risk: broad
Tested: marker presenter, linked Compose UI, and visual golden tests
```

### Task 10: Complete details dispatch, localization, accessibility, and responsive behavior

**Files:**
- Modify: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/FirefoxReportDetails.kt`
- Modify: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/SimpleperfLocalization.kt`
- Create: `presentation/src/test/kotlin/com/androidperformancestudio/presentation/FirefoxReportAccessibilityTest.kt`
- Create: `presentation/src/test/kotlin/com/androidperformancestudio/presentation/FirefoxReportResponsiveTest.kt`
- Modify: `presentation/src/test/kotlin/com/androidperformancestudio/presentation/SimpleperfLocalizationTest.kt`
- Modify: `presentation/src/test/kotlin/com/androidperformancestudio/presentation/FlameGraphVisualGoldenTest.kt`

**Interfaces:**
- Consumes: every completed panel and panel-scoped selection.
- Produces: final details behavior, Chinese/English copy, keyboard behavior, and narrow/wide layout guarantees.

- [ ] **Step 1: Write failing cross-tab details and accessibility tests**

```kotlin
@Test
fun `details never leaks selection from the previous tab`() = runComposeUiTest {
    val harness = reportHarnessWithFunctionAndMarkerSelections()
    setContent { harness.Content() }

    onNodeWithTag("report-tab-FLAME_GRAPH").performClick()
    onNodeWithTag("flame-node-3").performClick()
    onNodeWithTag("report-tab-MARKER_TABLE").performClick()
    onNodeWithText("Function details").assertDoesNotExist()
    onNodeWithText("Select a marker to inspect details.").assertExists()
}

@Test
fun `timeline divider exposes bounded progress semantics`() = runComposeUiTest {
    setContent { ReportPage(readyState(), actions()) }

    onNodeWithTag("timeline-divider").assertRangeInfoEquals(ProgressBarRangeInfo(220f, 120f..480f))
}
```

- [ ] **Step 2: Run the new focused tests**

Run: `./gradlew :presentation:test --tests '*FirefoxReportAccessibilityTest' --tests '*FirefoxReportResponsiveTest' --no-daemon`

Expected: FAIL until details dispatch and semantics are complete.

- [ ] **Step 3: Finish panel-scoped details dispatch**

Dispatch by `state.selectedTab`. Resolve function details only from the selected stack panel, marker details only from the marker projection, and diagnostic details only from Overview selection. When no compatible selection exists, render the panel-specific selection prompt.

- [ ] **Step 4: Add localization keys and exact Chinese copy**

Add translations for the seven tabs, `显示详情`, `所有帧`, `脚本`, `原生`, `反转调用栈`, `过滤栈`, marker availability messages, Stack Chart messages, and details prompts. Extend `SimpleperfLocalizationTest` so every new English key has a Chinese value and no duplicate key exists.

- [ ] **Step 5: Complete keyboard and responsive behavior**

Tabs use arrow-key traversal; Space toggles details and stack choices; Enter activates a focused row/block/marker; Escape closes context or transient details without clearing global visibility. Narrow layouts keep tabs through horizontal scrolling, keep the filter input at least 140 dp wide, and never overlap the timeline or details region.

- [ ] **Step 6: Run presentation checks**

Run: `./gradlew :presentation:test :presentation:check --no-daemon`

Expected: PASS.

- [ ] **Step 7: Commit productization behavior**

```text
Make the Firefox report usable across themes, languages, and window sizes

Constraint: Compact visual density must retain keyboard, semantic, and hit-target accessibility.
Rejected: Drop controls at narrow widths | Every approved tab and filter remains reachable
Confidence: high
Scope-risk: moderate
Tested: presentation accessibility, responsive, localization, and module checks
```

### Task 11: Verify complete workflows and remove obsolete report code

**Files:**
- Modify: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/ReportPage.kt`
- Delete only after references reach zero: obsolete report-navigation and duplicate toolbar helpers inside `ReportPage.kt` and `FlameGraphToolbar.kt`
- Modify: `presentation/src/test/kotlin/com/androidperformancestudio/presentation/ReportWorkspaceBehaviorTest.kt`
- Modify: `application/src/test/kotlin/com/androidperformancestudio/application/ReportControllerTest.kt`
- Modify: `storage-sqlite/src/test/kotlin/com/androidperformancestudio/storage/SQLiteLegacyReadOnlyProjectionTest.kt`
- Modify: `app-desktop/src/test/kotlin/com/androidperformancestudio/desktop/CrossPlatformGoldenE2eTest.kt`
- Create: `profile-analysis/src/test/kotlin/com/androidperformancestudio/profileanalysis/LargeStackChartProjectionTest.kt`
- Create: `storage-sqlite/src/test/kotlin/com/androidperformancestudio/storage/LargeMarkerProjectionTest.kt`

**Interfaces:**
- Consumes: all prior tasks.
- Produces: a clean implementation with complete regression evidence and no obsolete navigation path.

- [ ] **Step 1: Add end-to-end assertions for open, recent-open, switching, and export**

Extend existing harnesses so a direct open and recent open both reach the persistent timeline, switch through all seven tabs, retain filter/details state, and still export the report and Gecko profile.

- [ ] **Step 2: Run targeted workflow tests before cleanup**

Run: `./gradlew :application:test :presentation:test :app-desktop:test --tests '*ReportControllerTest' --tests '*ReportWorkspaceBehaviorTest' --tests '*CrossPlatformGoldenE2eTest' --no-daemon`

Expected: PASS.

- [ ] **Step 3: Delete unreachable old navigation and duplicate helpers**

Use `rg -n "ReportNavigation|ReportTab.TIMELINE|ReportTab.DIAGNOSTICS|ImplementationFilter.MANAGED|ImplementationFilter.KERNEL|ImplementationFilter.UNKNOWN" . --glob '*.kt' --glob '!**/build/**'`. Remove obsolete production branches and update test fixtures until the command reports no matches other than migration-history documentation.

- [ ] **Step 4: Run formatting and static analysis**

Run: `./gradlew spotlessCheck detekt --no-daemon`

Expected: PASS with zero formatting or static-analysis violations.

- [ ] **Step 5: Prove bounded behavior at the required scale**

Generate one million deterministic weighted stacks across eight threads and verify Stack Chart projection plus viewport culling completes within the repository's 10-second test timeout after one warm-up projection. Insert 100,000 markers across point and interval forms and verify a 10-millisecond range query returns only intersecting rows and does not parse payload JSON. Keep timing assertions at the suite timeout boundary; assert bounded result sizes and correct totals separately so slow CI reports a clear failure.

Run: `./gradlew :profile-analysis:test :storage-sqlite:test --tests '*LargeStackChartProjectionTest' --tests '*LargeMarkerProjectionTest' --no-daemon`

Expected: PASS without out-of-memory failure or timeout.

- [ ] **Step 6: Run the complete repository verification gate**

Run: `./gradlew checkAll --no-daemon`

Expected: PASS, including profile-analysis, storage, application, presentation, export, desktop E2E, legacy-session, and golden tests.

- [ ] **Step 7: Review the final diff for scope and generated artifacts**

Run:

```bash
git status --short
git diff --check
git diff --stat
git diff --name-only | rg '(^|/)(build|\.gradle)/' && exit 1 || true
```

Expected: only source, test, golden baseline, localization, and approved documentation files are changed; whitespace validation passes.

- [ ] **Step 8: Commit final integration and cleanup**

```text
Finish the Firefox report workspace without parallel legacy paths

Constraint: Open, recent-open, analysis switching, legacy sessions, and export must pass the same final gate.
Rejected: Retain obsolete report navigation as fallback | Two active interaction models would drift
Confidence: high
Scope-risk: broad
Directive: Keep all seven tabs fixed and prove new panels with real projection data.
Tested: spotlessCheck, detekt, and complete checkAll
```

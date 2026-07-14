# View Display Options Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a persistent native View menu that can hide invisible hierarchy subtrees, hide findings for invisible views, and hide hierarchy indices.

**Architecture:** Keep capture and presenter models unchanged. Store immutable display preferences with the same `java.util.prefs.Preferences` pattern used by theme and language settings, then derive filtered hierarchy and findings projections through pure functions shared by rendering and keyboard navigation.

**Tech Stack:** Kotlin/JVM, Compose Multiplatform Desktop, Java Preferences API, JUnit 5, Gradle.

---

## File Structure

- Create `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewDisplayOptions.kt` for immutable option state, typed menu actions, and preference persistence.
- Create `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewDisplayProjection.kt` for pure hierarchy/finding filtering, severity summaries, and row-label formatting.
- Modify `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/NativeViewerMenuBar.kt` to model and render the View menu.
- Modify `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewerStrings.kt` to provide English and Simplified Chinese labels.
- Modify `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt` to load/save preferences and apply the projections consistently.
- Create focused tests in the matching `desktop-app/src/test/kotlin/dev/agentperf/desktop` package.

### Task 1: Persistent View Option State

**Files:**
- Create: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewDisplayOptions.kt`
- Create: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ViewDisplayOptionsTest.kt`

- [ ] **Step 1: Write failing option and persistence tests**

Test that all defaults are false, each enum option toggles only its matching field, missing values load as false, and saving then loading round-trips three independent keys:

```kotlin
class ViewDisplayOptionsTest {
    @Test
    fun `all view options default to disabled`() {
        assertEquals(
            ViewDisplayOptions(
                hideInvisibleHierarchyViews = false,
                hideInvisibleFindings = false,
                hideHierarchyIndices = false,
            ),
            ViewDisplayOptions(),
        )
    }

    @Test
    fun `each view option toggles independently`() {
        ViewDisplayOption.entries.forEach { option ->
            val toggled = ViewDisplayOptions().toggle(option)
            assertEquals(option == ViewDisplayOption.HIDE_INVISIBLE_HIERARCHY_VIEWS, toggled.hideInvisibleHierarchyViews)
            assertEquals(option == ViewDisplayOption.HIDE_INVISIBLE_FINDINGS, toggled.hideInvisibleFindings)
            assertEquals(option == ViewDisplayOption.HIDE_HIERARCHY_INDICES, toggled.hideHierarchyIndices)
        }
    }

    @Test
    fun `store round trips independent values and defaults missing values to false`() {
        val values = mutableMapOf<String, Boolean>()
        val store = ViewDisplayOptionsStore(
            readBoolean = { key, default -> values[key] ?: default },
            writeBoolean = values::set,
        )
        assertEquals(ViewDisplayOptions(), store.load())

        val expected = ViewDisplayOptions(
            hideInvisibleHierarchyViews = true,
            hideInvisibleFindings = false,
            hideHierarchyIndices = true,
        )
        store.save(expected)

        assertEquals(3, values.size)
        assertEquals(expected, store.load())
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
cd desktop-viewer
./gradlew :desktop-app:test --tests 'dev.agentperf.desktop.ViewDisplayOptionsTest'
```

Expected: compilation fails because `ViewDisplayOptions`, `ViewDisplayOption`, and `ViewDisplayOptionsStore` do not exist.

- [ ] **Step 3: Implement immutable state and resilient persistence**

Create the three-option enum, a data class with `toggle`, and a store whose `desktop()` factory reads and writes three `Preferences` Boolean keys. Wrap desktop reads and writes in `runCatching`; missing or failed reads use `false`.

```kotlin
internal enum class ViewDisplayOption {
    HIDE_INVISIBLE_HIERARCHY_VIEWS,
    HIDE_INVISIBLE_FINDINGS,
    HIDE_HIERARCHY_INDICES,
}

internal data class ViewDisplayOptions(
    val hideInvisibleHierarchyViews: Boolean = false,
    val hideInvisibleFindings: Boolean = false,
    val hideHierarchyIndices: Boolean = false,
) {
    fun toggle(option: ViewDisplayOption): ViewDisplayOptions = when (option) {
        ViewDisplayOption.HIDE_INVISIBLE_HIERARCHY_VIEWS ->
            copy(hideInvisibleHierarchyViews = !hideInvisibleHierarchyViews)
        ViewDisplayOption.HIDE_INVISIBLE_FINDINGS ->
            copy(hideInvisibleFindings = !hideInvisibleFindings)
        ViewDisplayOption.HIDE_HIERARCHY_INDICES ->
            copy(hideHierarchyIndices = !hideHierarchyIndices)
    }
}
```

The store writes all three values on `save` and reconstructs the complete value on `load`.

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the Task 1 Gradle command again. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit Task 1**

Commit with a Lore message recording the Java Preferences constraint, three-key choice, test command, confidence, and scope risk.

### Task 2: Pure Display Projections

**Files:**
- Create: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewDisplayProjection.kt`
- Create: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ViewDisplayProjectionTest.kt`

- [ ] **Step 1: Write failing projection tests**

Create flattened rows containing a visible root, an invisible branch with a nominally visible child, and a visible sibling. Assert:

```kotlin
assertEquals(
    listOf("root", "visible-sibling"),
    ViewDisplayProjection.hierarchyRows(rows, hideInvisible = true).map(TreeRowModel::id),
)
assertEquals(rows, ViewDisplayProjection.hierarchyRows(rows, hideInvisible = false))
```

Create findings for the hidden branch, its child, the visible sibling, and an unknown node. Assert filtering removes both hidden-subtree findings, retains the visible and unknown findings, and produces severity totals from the retained items.

Assert:

```kotlin
assertEquals("2-4  Button", ViewDisplayProjection.hierarchyLabel(row, hideIndex = false))
assertEquals("Button", ViewDisplayProjection.hierarchyLabel(row, hideIndex = true))
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
./gradlew :desktop-app:test --tests 'dev.agentperf.desktop.ViewDisplayProjectionTest'
```

Expected: compilation fails because `ViewDisplayProjection` does not exist.

- [ ] **Step 3: Implement the projections**

Implement:

```kotlin
internal object ViewDisplayProjection {
    fun hierarchyRows(rows: List<TreeRowModel>, hideInvisible: Boolean): List<TreeRowModel>
    fun findings(
        findings: List<FindingRowModel>,
        rows: List<TreeRowModel>,
        hideInvisible: Boolean,
    ): List<FindingRowModel>
    fun severitySummary(findings: List<FindingRowModel>): SeveritySummary
    fun hierarchyLabel(row: TreeRowModel, hideIndex: Boolean): String
}
```

Track the depth of the current invisible ancestor while scanning pre-order rows. Reuse the same excluded node-id set for finding filtering. Keep findings whose node id is absent from `rows`. Summarize by `FindingTone`, and emit either `"${row.number}  ${row.label}"` or `row.label`.

- [ ] **Step 4: Run Task 2 tests and verify GREEN**

Run the focused Task 2 command. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit Task 2**

Commit with a Lore message explaining why filtering stays outside `InspectorPresenter`.

### Task 3: Native View Menu and Localization

**Files:**
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewerStrings.kt`
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/NativeViewerMenuBar.kt`
- Modify: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/LanguagePreferenceTest.kt`
- Modify: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/NativeViewerMenuBarTest.kt`

- [ ] **Step 1: Write failing menu-model and localization tests**

Extend language assertions for:

```kotlin
assertEquals("视图", chinese.view)
assertEquals("View", english.view)
assertEquals("隐藏层级结构中的不可见视图", chinese.hideInvisibleHierarchyViews)
assertEquals("Hide invisible views in hierarchy", english.hideInvisibleHierarchyViews)
assertEquals("隐藏问题列表中的不可见视图内容", chinese.hideInvisibleFindings)
assertEquals("Hide invisible-view findings", english.hideInvisibleFindings)
assertEquals("隐藏层级索引", chinese.hideHierarchyIndices)
assertEquals("Hide hierarchy indices", english.hideHierarchyIndices)
```

Extend `NativeViewerMenuModel` tests to assert the View title, exact option order, labels, and checked states supplied by `ViewDisplayOptions`.

- [ ] **Step 2: Run tests and verify RED**

Run:

```bash
./gradlew :desktop-app:test \
  --tests 'dev.agentperf.desktop.LanguagePreferenceTest' \
  --tests 'dev.agentperf.desktop.NativeViewerMenuBarTest'
```

Expected: compilation or assertion failures for the missing View strings and menu model.

- [ ] **Step 3: Implement strings and menu model**

Add four localized string properties. Add:

```kotlin
internal data class NativeViewMenuItem(
    val option: ViewDisplayOption,
    val label: String,
    val checked: Boolean,
)
```

Extend `NativeViewerMenuModel` with `viewTitle` and `viewItems`, preserving Actions and Advanced. Populate the three items in enum order from the current `ViewDisplayOptions`.

- [ ] **Step 4: Render View between Actions and Advanced**

Extend `NativeViewerMenuBar` with `onViewOption: (ViewDisplayOption) -> Unit`. Render:

```kotlin
Menu(model.viewTitle) {
    model.viewItems.forEach { item ->
        CheckboxItem(
            text = item.label,
            checked = item.checked,
            onCheckedChange = { onViewOption(item.option) },
        )
    }
}
```

- [ ] **Step 5: Run Task 3 tests and verify GREEN**

Run the focused Task 3 command. Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Commit Task 3**

Commit with a Lore message recording menu ordering and localization coverage.

### Task 4: Apply and Persist Options in the Desktop UI

**Files:**
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt`
- Modify: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/HierarchyTreeStateTest.kt`

- [ ] **Step 1: Add a failing shared tree-display test**

Add a test for a wished-for `HierarchyTreeState.displayRows(rows, hideInvisible)` API. Assert it removes invisible subtrees before applying collapsed-node state. Add a navigation assertion that passes this shared result to `adjacentNodeId` and verifies down navigation selects the next displayed sibling.

- [ ] **Step 2: Run the navigation test and verify RED**

Run:

```bash
./gradlew :desktop-app:test --tests 'dev.agentperf.desktop.HierarchyTreeStateTest'
```

Expected: compilation fails because `HierarchyTreeState.displayRows` does not exist.

- [ ] **Step 3: Load, toggle, and save display options**

In `DesktopViewerApp`, create a remembered `ViewDisplayOptionsStore.desktop()`, initialize state from `load()`, and add a callback that toggles one option, updates Compose state, then saves the complete updated value.

- [ ] **Step 4: Implement and use one hierarchy display path**

Add:

```kotlin
fun displayRows(
    rows: List<TreeRowModel>,
    hideInvisible: Boolean,
): List<TreeRowModel> = visibleRows(
    ViewDisplayProjection.hierarchyRows(rows, hideInvisible),
)
```

Before calling `HierarchyTreeState.adjacentNodeId`, derive base rows through `ViewDisplayProjection.hierarchyRows`. Pass `ViewDisplayOptions` into `HierarchyPane` and render `treeState.displayRows`, so navigation and rendering share the same filtering order.

Replace the hard-coded tree text with:

```kotlin
ViewDisplayProjection.hierarchyLabel(
    row = row,
    hideIndex = viewDisplayOptions.hideHierarchyIndices,
)
```

- [ ] **Step 5: Apply finding filtering and displayed totals**

Pass `ViewDisplayOptions` into `FindingsPane`. Derive filtered findings through `ViewDisplayProjection.findings`, compute badges through `ViewDisplayProjection.severitySummary`, and render the filtered list. Keep the original presenter model and finding node numbers unchanged.

- [ ] **Step 6: Connect the native menu**

Pass the options to `NativeViewerMenuModel` and the toggle-and-save callback to `NativeViewerMenuBar.onViewOption`.

- [ ] **Step 7: Run focused desktop tests and verify GREEN**

Run:

```bash
./gradlew :desktop-app:test
```

Expected: all desktop-app tests pass.

- [ ] **Step 8: Commit Task 4**

Commit with a Lore message documenting persistence and shared projection use between UI and navigation.

### Task 5: Full Verification and Runtime Smoke Test

**Files:**
- No production changes expected.

- [ ] **Step 1: Run formatting and diff checks**

Run:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors; only planned files are changed before the final commit boundary.

- [ ] **Step 2: Run the complete test suite**

Run:

```bash
cd desktop-viewer
./gradlew test --rerun-tasks
```

Expected: `BUILD SUCCESSFUL` with no failed tests.

- [ ] **Step 3: Build the desktop distributable**

Run:

```bash
./gradlew :desktop-app:createDistributable
```

Expected: `BUILD SUCCESSFUL` and `desktop-app/build/compose/binaries/main/app/AgentPerf Inspector.app`.

- [ ] **Step 4: Smoke-test the native menu**

Run `./gradlew :desktop-app:run`. Verify through macOS accessibility inspection that the menu order is Actions, View, Advanced; View contains three checkbox items; toggling each updates the hierarchy or Findings immediately; restarting restores the checked states.

- [ ] **Step 5: Final repository check**

Run:

```bash
git status --short
git log --oneline -8
```

Expected: clean worktree and Lore-formatted feature commits.

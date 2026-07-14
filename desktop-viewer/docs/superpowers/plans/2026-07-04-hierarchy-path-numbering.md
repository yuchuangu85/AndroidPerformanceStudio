# Hierarchy Path Numbering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show one-based structural path numbers in HIERARCHY and reuse them in FINDINGS for direct node lookup.

**Architecture:** `InspectorPresenter` remains the single presentation boundary. Its depth-first traversal produces both `TreeRowModel.number` and a `nodeId -> number` lookup; finding models resolve numbers from that lookup, while Compose only renders prepared strings.

**Tech Stack:** Kotlin, Compose Desktop, JUnit 5, Gradle

---

### Task 1: Generate shared hierarchy path numbers

**Files:**
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/InspectorPresenter.kt`
- Test: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/InspectorPresenterTest.kt`

- [ ] **Step 1: Write the failing presenter tests**

Extend the depth-first test with:

```kotlin
assertEquals(
    listOf("1", "1.1", "1.2", "1.2.1", "1.2.2"),
    model.rows.map { it.number },
)
```

Extend the finding test with:

```kotlin
assertEquals("1.2.2", model.findings.single().nodeNumber)
assertEquals(
    model.rows.single { it.id == "legacy-placeholder" }.number,
    model.findings.single().nodeNumber,
)
```

Add an unknown-node fallback test using an explicit `AnalysisReport`:

```kotlin
@Test
fun `uses a placeholder when a finding node is absent from the snapshot`() {
    val state = InspectorState(
        snapshot = SampleSnapshots.dashboard,
        analysis = AnalysisReport(
            metrics = LayoutMetrics(nodeCount = 5, maxDepth = 3, widestLevel = 2),
            findings = listOf(
                Finding(
                    ruleId = "layout.test",
                    severity = Severity.INFO,
                    nodeId = "missing",
                    message = "测试问题",
                ),
            ),
        ),
    )

    assertEquals("—", InspectorPresenter.present(state).findings.single().nodeNumber)
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
cd desktop-viewer
./gradlew :desktop-app:test --tests dev.agentperf.desktop.InspectorPresenterTest --rerun-tasks
```

Expected: compilation fails because `TreeRowModel.number` and `FindingRowModel.nodeNumber` do not exist.

- [ ] **Step 3: Implement the minimal shared numbering source**

Add fields:

```kotlin
data class TreeRowModel(
    val id: String,
    val number: String,
    val label: String,
    val depth: Int,
    val selected: Boolean,
    val visible: Boolean,
)

data class FindingRowModel(
    val title: String,
    val nodeNumber: String,
    val nodeId: String,
    val message: String,
)
```

Build the lookup during traversal:

```kotlin
val nodeNumbers = mutableMapOf<String, String>()
val rows = buildList {
    state.snapshot?.root?.appendRows(
        target = this,
        number = "1",
        depth = 0,
        selectedNodeId = state.selectedNodeId,
        nodeNumbers = nodeNumbers,
    )
}
```

Resolve finding numbers:

```kotlin
nodeNumber = nodeNumbers[finding.nodeId] ?: "—"
```

Update traversal:

```kotlin
private fun UiNode.appendRows(
    target: MutableList<TreeRowModel>,
    number: String,
    depth: Int,
    selectedNodeId: String?,
    nodeNumbers: MutableMap<String, String>,
) {
    nodeNumbers.putIfAbsent(id, number)
    target += TreeRowModel(
        id = id,
        number = number,
        label = className.substringAfterLast('.'),
        depth = depth,
        selected = id == selectedNodeId,
        visible = visible && alpha > 0f,
    )
    children.forEachIndexed { index, child ->
        child.appendRows(
            target = target,
            number = "$number.${index + 1}",
            depth = depth + 1,
            selectedNodeId = selectedNodeId,
            nodeNumbers = nodeNumbers,
        )
    }
}
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the command from Step 2.

Expected: `InspectorPresenterTest` passes.

- [ ] **Step 5: Commit the presenter behavior**

Commit the presenter and test with a Lore-format message describing shared numbering, exact verification, and narrow scope.

### Task 2: Render numbers in both panels and verify live behavior

**Files:**
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt`

- [ ] **Step 1: Render the prepared HIERARCHY number**

Change the hierarchy row text from:

```kotlin
row.label
```

to:

```kotlin
"${row.number}  ${row.label}"
```

- [ ] **Step 2: Render the prepared FINDINGS number**

Change the finding row text to:

```kotlin
"[${finding.nodeNumber}]  ${finding.title}  ·  ${finding.message}"
```

The internal `nodeId` remains available in `FindingRowModel` but is no longer required in the visible line.

- [ ] **Step 3: Run focused and aggregate verification**

Run:

```bash
cd desktop-viewer
./gradlew :desktop-app:test --rerun-tasks
./gradlew clean test assemble
git -C .. diff --check
```

Expected: both Gradle commands report `BUILD SUCCESSFUL`; diff check has no output.

- [ ] **Step 4: Verify the connected device UI**

Run:

```bash
./gradlew :desktop-app:run
```

Expected:

- HIERARCHY displays `1`, `1.1`, `1.2`, and deeper paths.
- Each visible FINDINGS row starts with the same bracketed path as its matching hierarchy row.
- The application remains connected and CANVAS behavior is unchanged.

- [ ] **Step 5: Commit and push**

Commit the UI change with a Lore-format message, push `main` to `origin`, and confirm `HEAD` equals `origin/main`.

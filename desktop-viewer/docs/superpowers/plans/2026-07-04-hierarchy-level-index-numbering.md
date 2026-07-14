# Hierarchy Level-Index Numbering Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace dotted hierarchy paths with unique, zero-based `level-index` references shared by HIERARCHY and FINDINGS.

**Architecture:** Keep numbering in `InspectorPresenter`, where the flattened HIERARCHY rows and FINDINGS lookup are already produced together. During the existing preorder traversal, maintain one counter per depth, assign the next counter value to each node, and reuse the resulting string through the existing `nodeId -> number` map.

**Tech Stack:** Kotlin/JVM, Compose Desktop presentation models, JUnit 5, Gradle

---

## File Structure

- Modify `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/InspectorPresenter.kt`: generate `level-index` values with per-depth counters.
- Modify `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/InspectorPresenterTest.kt`: lock root, sibling, cross-parent same-depth, and FINDINGS mapping behavior.

### Task 1: Lock the Level-Index Contract with Presenter Tests

**Files:**
- Modify: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/InspectorPresenterTest.kt`

- [ ] **Step 1: Update the existing traversal expectations**

Replace the dotted-number assertion in `flattens tree rows in depth first display order` with:

```kotlin
assertEquals(
    listOf("0-0", "1-0", "1-1", "2-0", "2-1"),
    model.rows.map { it.number },
)
```

This proves zero-based levels and a single index sequence within each depth while preserving the current preorder row order.

- [ ] **Step 2: Update the existing FINDINGS mapping expectation**

In `presents selected node details and finding counts`, replace:

```kotlin
assertEquals("1.2.2", model.findings.single().nodeNumber)
```

with:

```kotlin
assertEquals("2-1", model.findings.single().nodeNumber)
```

Keep the following row-to-finding equality assertion unchanged so both panels remain tied to the same source.

- [ ] **Step 3: Add a regression test for same-depth nodes under different parents**

Add these imports:

```kotlin
import dev.agentperf.protocol.ViewNode
```

Add this test to `InspectorPresenterTest`:

```kotlin
@Test
fun `uses one global index sequence for each depth`() {
    val bounds = SampleSnapshots.dashboard.root.bounds
    val snapshot = SampleSnapshots.dashboard.copy(
        root = ViewNode(
            id = "root",
            className = "Root",
            bounds = bounds,
            children = listOf(
                ViewNode(
                    id = "left",
                    className = "Left",
                    bounds = bounds,
                    children = listOf(
                        ViewNode(id = "left-leaf", className = "Leaf", bounds = bounds),
                    ),
                ),
                ViewNode(
                    id = "right",
                    className = "Right",
                    bounds = bounds,
                    children = listOf(
                        ViewNode(id = "right-leaf", className = "Leaf", bounds = bounds),
                    ),
                ),
            ),
        ),
    )

    val model = InspectorPresenter.present(InspectorState(snapshot = snapshot))

    assertEquals(
        listOf("0-0", "1-0", "2-0", "1-1", "2-1"),
        model.rows.map { it.number },
    )
}
```

The preorder display remains `root`, left branch, then right branch, while both grandchildren consume the shared depth-two counter.

- [ ] **Step 4: Run the focused test and verify the new contract fails**

Run from `desktop-viewer`:

```bash
./gradlew :desktop-app:test --tests dev.agentperf.desktop.InspectorPresenterTest
```

Expected: FAIL because the presenter still emits dotted values beginning with `1`.

### Task 2: Generate Level-Index Numbers During Traversal

**Files:**
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/InspectorPresenter.kt`
- Test: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/InspectorPresenterTest.kt`

- [ ] **Step 1: Create per-depth counters for the current presentation**

At the start of `present`, add a counter map and stop passing the hard-coded root number:

```kotlin
val nodeNumbers = mutableMapOf<String, String>()
val nextIndexByDepth = mutableMapOf<Int, Int>()
val rows = buildList {
    state.snapshot?.root?.appendRows(
        target = this,
        depth = 0,
        selectedNodeId = state.selectedNodeId,
        nodeNumbers = nodeNumbers,
        nextIndexByDepth = nextIndexByDepth,
    )
}
```

- [ ] **Step 2: Assign each visited node the next index for its depth**

Replace the `number` argument in `appendRows` with the counter map and derive the number inside the function:

```kotlin
private fun UiNode.appendRows(
    target: MutableList<TreeRowModel>,
    depth: Int,
    selectedNodeId: String?,
    nodeNumbers: MutableMap<String, String>,
    nextIndexByDepth: MutableMap<Int, Int>,
) {
    val index = nextIndexByDepth.getOrDefault(depth, 0)
    nextIndexByDepth[depth] = index + 1
    val number = "$depth-$index"
    nodeNumbers.putIfAbsent(id, number)
    target += TreeRowModel(
        id = id,
        number = number,
        label = className.substringAfterLast('.'),
        depth = depth,
        selected = id == selectedNodeId,
        visible = visible && alpha > 0f,
    )
    children.forEach { child ->
        child.appendRows(
            target = target,
            depth = depth + 1,
            selectedNodeId = selectedNodeId,
            nodeNumbers = nodeNumbers,
            nextIndexByDepth = nextIndexByDepth,
        )
    }
}
```

No Compose code changes are required because HIERARCHY and FINDINGS already render the presenter-provided number.

- [ ] **Step 3: Run the focused presenter tests**

Run from `desktop-viewer`:

```bash
./gradlew :desktop-app:test --tests dev.agentperf.desktop.InspectorPresenterTest
```

Expected: PASS, including unknown-node fallback `[—]`.

- [ ] **Step 4: Commit the tested implementation**

```bash
git add \
  desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/InspectorPresenter.kt \
  desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/InspectorPresenterTest.kt
git commit -m "Make hierarchy references easier to scan" \
  -m "Constraint: Keep HIERARCHY and FINDINGS references generated from one snapshot traversal.
Rejected: Sibling-local indexes | They repeat under different parents.
Confidence: high
Scope-risk: narrow
Directive: Keep indexes zero-based and global within each depth.
Tested: ./gradlew :desktop-app:test --tests dev.agentperf.desktop.InspectorPresenterTest
Not-tested: Full project build is performed in the verification task."
```

### Task 3: Verify the Project and Runtime Presentation

**Files:**
- Verify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/InspectorPresenter.kt`
- Verify: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/InspectorPresenterTest.kt`

- [ ] **Step 1: Run the complete project test and build lifecycle**

Run from `desktop-viewer`:

```bash
./gradlew test build
```

Expected: `BUILD SUCCESSFUL` with all module tests passing.

- [ ] **Step 2: Validate the final diff and repository state**

Run from the repository root:

```bash
git diff --check
git status --short
```

Expected: no whitespace errors and no uncommitted implementation files.

- [ ] **Step 3: Launch the Desktop viewer for visual confirmation**

Run from `desktop-viewer`:

```bash
./gradlew :desktop-app:run
```

Expected: HIERARCHY displays compact values such as `0-0`, `1-0`, and `2-1`; any FINDINGS entry for the same node displays the identical value in brackets.

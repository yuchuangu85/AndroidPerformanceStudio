# Header and Findings Resize Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Halve the internal Desktop viewer header and default FINDINGS heights, then let users resize FINDINGS vertically within safe session-only limits.

**Architecture:** Add a pure `FindingsLayout` policy for height normalization and drag direction, following the existing `PaneLayout` pattern. `DesktopViewerApp` remembers the requested FINDINGS height, lays out the three-pane row and FINDINGS inside one height-aware column, and renders a dedicated vertical-drag separator above a scrollable findings list.

**Tech Stack:** Kotlin/JVM, Compose Multiplatform Desktop, JUnit 5, Gradle

---

## File Structure

- Create `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/FindingsLayout.kt`: default, minimum, maximum, fit, and vertical drag rules.
- Create `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/FindingsLayoutTest.kt`: pure unit tests for drag direction and constraints.
- Modify `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt`: shorter header, height-aware layout, horizontal resize separator, and scrollable findings rows.

### Task 1: Define and Test FINDINGS Height Rules

**Files:**
- Create: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/FindingsLayoutTest.kt`
- Create: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/FindingsLayout.kt`

- [ ] **Step 1: Write failing layout-policy tests**

Create `FindingsLayoutTest.kt`:

```kotlin
package dev.agentperf.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class FindingsLayoutTest {
    @Test
    fun `default height is half the previous findings height`() {
        assertEquals(89f, FindingsLayout.DEFAULT_HEIGHT_DP)
        assertEquals(89f, FindingsLayout.fit(FindingsLayout.DEFAULT_HEIGHT_DP, 800f))
    }

    @Test
    fun `dragging upward grows findings and dragging downward shrinks it`() {
        assertEquals(129f, FindingsLayout.drag(89f, deltaDp = -40f, availableHeightDp = 800f))
        assertEquals(56f, FindingsLayout.drag(89f, deltaDp = 40f, availableHeightDp = 800f))
    }

    @Test
    fun `height clamps to minimum and half the available content`() {
        assertEquals(56f, FindingsLayout.drag(89f, deltaDp = 1_000f, availableHeightDp = 600f))
        assertEquals(300f, FindingsLayout.drag(89f, deltaDp = -1_000f, availableHeightDp = 600f))
    }

    @Test
    fun `fitting remembered height reacts to window shrink`() {
        assertEquals(200f, FindingsLayout.fit(400f, availableHeightDp = 400f))
        assertEquals(56f, FindingsLayout.fit(89f, availableHeightDp = 100f))
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run from `desktop-viewer`:

```bash
./gradlew :desktop-app:test --tests dev.agentperf.desktop.FindingsLayoutTest
```

Expected: test compilation fails because `FindingsLayout` does not exist.

- [ ] **Step 3: Implement the minimal pure layout policy**

Create `FindingsLayout.kt`:

```kotlin
package dev.agentperf.desktop

internal object FindingsLayout {
    const val DEFAULT_HEIGHT_DP = 89f
    const val MIN_HEIGHT_DP = 56f
    const val SPLITTER_HEIGHT_DP = 7f
    private const val MAX_HEIGHT_RATIO = 0.5f

    fun fit(
        heightDp: Float,
        availableHeightDp: Float,
    ): Float {
        val maximumHeight = maxOf(MIN_HEIGHT_DP, availableHeightDp * MAX_HEIGHT_RATIO)
        return heightDp.coerceIn(MIN_HEIGHT_DP, maximumHeight)
    }

    fun drag(
        heightDp: Float,
        deltaDp: Float,
        availableHeightDp: Float,
    ): Float = fit(
        heightDp = heightDp - deltaDp,
        availableHeightDp = availableHeightDp,
    )
}
```

- [ ] **Step 4: Run the focused policy tests and verify GREEN**

Run from `desktop-viewer`:

```bash
./gradlew :desktop-app:test --tests dev.agentperf.desktop.FindingsLayoutTest
```

Expected: `BUILD SUCCESSFUL`; all four policy tests pass.

- [ ] **Step 5: Commit the tested layout policy**

```bash
git add \
  desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/FindingsLayout.kt \
  desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/FindingsLayoutTest.kt
git commit -m "Keep findings resizing within usable bounds" \
  -m "Constraint: The findings pane starts at half its previous height and remains reachable after window resize.
Rejected: Unbounded drag | It can hide the three-pane inspector or collapse findings completely.
Confidence: high
Scope-risk: narrow
Directive: Keep upward drag increasing findings height and normalize session state on window changes.
Tested: ./gradlew :desktop-app:test --tests dev.agentperf.desktop.FindingsLayoutTest
Not-tested: Compose integration is handled in the next task."
```

### Task 2: Integrate the Shorter Header and Vertical FINDINGS Splitter

**Files:**
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt`
- Test: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/FindingsLayoutTest.kt`

- [ ] **Step 1: Add vertical drag support and session state**

Add the vertical gesture import:

```kotlin
import androidx.compose.foundation.gestures.detectVerticalDragGestures
```

Next to `paneWidths`, remember the requested height:

```kotlin
var paneWidths by remember { mutableStateOf(PaneWidths()) }
var findingsHeightDp by remember { mutableStateOf(FindingsLayout.DEFAULT_HEIGHT_DP) }
```

- [ ] **Step 2: Make the content below the header height-aware**

Replace the current three-pane `BoxWithConstraints`, divider, and fixed `FindingsPane` with:

```kotlin
BoxWithConstraints(modifier = Modifier.weight(1f)) {
    val availableHeightDp = maxHeight.value
    val normalizedFindingsHeight = FindingsLayout.fit(findingsHeightDp, availableHeightDp)
    SideEffect {
        if (findingsHeightDp != normalizedFindingsHeight) {
            findingsHeightDp = normalizedFindingsHeight
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val availableWidthDp = maxWidth.value
            val normalizedPaneWidths = PaneLayout.fit(paneWidths, availableWidthDp)
            SideEffect {
                if (paneWidths != normalizedPaneWidths) {
                    paneWidths = normalizedPaneWidths
                }
            }
            Row(modifier = Modifier.fillMaxSize()) {
                HierarchyPane(
                    state = state,
                    onSelect = { id ->
                        if (store.selectNode(id)) state = store.state
                    },
                    modifier = Modifier.width(normalizedPaneWidths.hierarchy.dp).fillMaxHeight(),
                )
                ResizableSeparator { deltaDp ->
                    paneWidths = PaneLayout.dragHierarchy(
                        widths = PaneLayout.fit(paneWidths, availableWidthDp),
                        deltaDp = deltaDp,
                        availableWidthDp = availableWidthDp,
                    )
                }
                PreviewPane(state, Modifier.weight(1f).fillMaxHeight())
                ResizableSeparator { deltaDp ->
                    paneWidths = PaneLayout.dragProperties(
                        widths = PaneLayout.fit(paneWidths, availableWidthDp),
                        deltaDp = deltaDp,
                        availableWidthDp = availableWidthDp,
                    )
                }
                DetailsPane(
                    state,
                    Modifier.width(normalizedPaneWidths.properties.dp).fillMaxHeight(),
                )
            }
        }
        FindingsResizeSeparator { deltaDp ->
            findingsHeightDp = FindingsLayout.drag(
                heightDp = FindingsLayout.fit(findingsHeightDp, availableHeightDp),
                deltaDp = deltaDp,
                availableHeightDp = availableHeightDp,
            )
        }
        FindingsPane(
            state = state,
            modifier = Modifier.fillMaxWidth().height(normalizedFindingsHeight.dp),
        )
    }
}
```

This leaves the existing horizontal pane-width logic unchanged.

- [ ] **Step 3: Halve the internal header height**

Change only the header row height:

```kotlin
modifier = Modifier.fillMaxWidth().height(29.dp).background(Panel).padding(horizontal = 18.dp)
```

Keep all header content and styling unchanged.

- [ ] **Step 4: Add the horizontal resize separator**

Add this composable beside the existing `ResizableSeparator`:

```kotlin
@Composable
private fun FindingsResizeSeparator(onDrag: (Float) -> Unit) {
    val density = LocalDensity.current
    val currentOnDrag by rememberUpdatedState(onDrag)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(FindingsLayout.SPLITTER_HEIGHT_DP.dp)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.N_RESIZE_CURSOR)))
            .pointerInput(density) {
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    currentOnDrag(dragAmount / density.density)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Border))
    }
}
```

- [ ] **Step 5: Keep the FINDINGS summary fixed and make rows scrollable**

Replace the `if/else` content after the FINDINGS divider with:

```kotlin
if (model.findings.isEmpty()) {
    Text("No findings", color = Color(0xFF8490A3), modifier = Modifier.padding(16.dp))
} else {
    LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
        items(model.findings) { finding ->
            Text(
                "[${finding.nodeNumber}]  ${finding.title}  ·  ${finding.message}",
                color = Color(0xFFC7D0DE),
                fontSize = 12.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 7.dp),
            )
        }
    }
}
```

- [ ] **Step 6: Compile and run all Desktop tests**

Run from `desktop-viewer`:

```bash
./gradlew :desktop-app:test :desktop-app:build
```

Expected: `BUILD SUCCESSFUL`; existing pane-width, presenter, canvas, startup, and new findings-layout tests pass.

- [ ] **Step 7: Commit the Compose integration**

```bash
git add desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt
git commit -m "Give live inspection more vertical space" \
  -m "Constraint: Preserve existing header content, three-column resizing, and findings presentation.
Rejected: Collapsible findings drawer | The requested interaction is direct vertical dragging.
Confidence: high
Scope-risk: moderate
Directive: Keep findings rows scrollable when the panel is at its smaller default or minimum height.
Tested: ./gradlew :desktop-app:test :desktop-app:build
Not-tested: Android lint remains outside this Desktop layout change."
```

### Task 3: Verify the Runtime Layout

**Files:**
- Verify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt`
- Verify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/FindingsLayout.kt`
- Verify: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/FindingsLayoutTest.kt`

- [ ] **Step 1: Run all project tests and the Desktop build**

Run from `desktop-viewer`:

```bash
./gradlew test :desktop-app:build
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Validate committed changes and repository cleanliness**

Run from the repository root:

```bash
git diff --check
git status --short
git log -3 --oneline
```

Expected: no whitespace errors, no uncommitted implementation files, and both implementation commits visible.

- [ ] **Step 3: Restart the Desktop viewer**

Stop the currently running `:desktop-app:run` process, then run from `desktop-viewer`:

```bash
./gradlew :desktop-app:run
```

Expected: the latest Desktop viewer opens and reconnects to the current foreground Android application.

- [ ] **Step 4: Visually verify the requested layout**

Confirm:

- the internal `AgentPerf Desktop Viewer` header is half its previous height;
- FINDINGS starts at half its previous height;
- a horizontal separator is visible above FINDINGS;
- dragging upward grows FINDINGS and dragging downward shrinks it;
- the panel never becomes shorter than `56dp` or taller than half the available content;
- finding rows remain scrollable;
- HIERARCHY/CANVAS/PROPERTIES horizontal resizing remains unchanged.

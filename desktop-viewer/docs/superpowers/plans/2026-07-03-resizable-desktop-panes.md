# Resizable Desktop Panes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add session-only draggable separators for the HIERARCHY, CANVAS, and PROPERTIES columns while enforcing usable minimum widths.

**Architecture:** Keep pane widths as remembered dp values in `DesktopViewerApp`. Put all drag direction and clamping rules in a pure `PaneLayout` helper, while a focused Compose separator converts pointer pixels to dp and reports deltas to the parent.

**Tech Stack:** Kotlin 2.3, Compose Multiplatform Desktop 1.11, Compose pointer input, Java AWT resize cursor, JUnit 5, Gradle.

---

### Task 1: Pure pane-width calculations

**Files:**
- Create: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/PaneLayout.kt`
- Create: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/PaneLayoutTest.kt`

- [ ] **Step 1: Write failing drag-direction and boundary tests**

Create `PaneLayoutTest.kt`:

```kotlin
package dev.agentperf.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PaneLayoutTest {
    @Test
    fun `dragging separators changes only the adjacent side pane`() {
        val initial = PaneWidths()

        assertEquals(
            PaneWidths(hierarchy = 380f, properties = 300f),
            PaneLayout.dragHierarchy(initial, deltaDp = 80f, availableWidthDp = 1200f),
        )
        assertEquals(
            PaneWidths(hierarchy = 300f, properties = 260f),
            PaneLayout.dragProperties(initial, deltaDp = 40f, availableWidthDp = 1200f),
        )
    }

    @Test
    fun `dragging clamps side panes and preserves canvas minimum width`() {
        val initial = PaneWidths()

        assertEquals(
            PaneWidths(hierarchy = 180f, properties = 300f),
            PaneLayout.dragHierarchy(initial, deltaDp = -1000f, availableWidthDp = 1100f),
        )
        assertEquals(
            PaneWidths(hierarchy = 466f, properties = 300f),
            PaneLayout.dragHierarchy(initial, deltaDp = 1000f, availableWidthDp = 1100f),
        )
        assertEquals(
            PaneWidths(hierarchy = 300f, properties = 240f),
            PaneLayout.dragProperties(initial, deltaDp = 1000f, availableWidthDp = 1100f),
        )
        assertEquals(
            PaneWidths(hierarchy = 300f, properties = 466f),
            PaneLayout.dragProperties(initial, deltaDp = -1000f, availableWidthDp = 1100f),
        )
        val hierarchyMaximum =
            PaneLayout.dragHierarchy(initial, deltaDp = 1000f, availableWidthDp = 1100f)
        assertEquals(
            hierarchyMaximum,
            PaneLayout.dragHierarchy(hierarchyMaximum, deltaDp = 1000f, availableWidthDp = 1100f),
        )
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
cd desktop-viewer
./gradlew :desktop-app:test --tests 'dev.agentperf.desktop.PaneLayoutTest'
```

Expected: test compilation fails because `PaneWidths` and `PaneLayout` do not exist.

- [ ] **Step 3: Implement the minimal pure layout helper**

Create `PaneLayout.kt`:

```kotlin
package dev.agentperf.desktop

internal data class PaneWidths(
    val hierarchy: Float = 300f,
    val properties: Float = 300f,
)

internal object PaneLayout {
    const val HIERARCHY_MIN_WIDTH_DP = 180f
    const val PROPERTIES_MIN_WIDTH_DP = 240f
    const val CANVAS_MIN_WIDTH_DP = 320f
    const val SPLITTER_WIDTH_DP = 7f
    private const val SPLITTER_COUNT = 2

    fun dragHierarchy(
        widths: PaneWidths,
        deltaDp: Float,
        availableWidthDp: Float,
    ): PaneWidths {
        val maximum = maxOf(
            HIERARCHY_MIN_WIDTH_DP,
            availableWidthDp -
                widths.properties -
                CANVAS_MIN_WIDTH_DP -
                SPLITTER_WIDTH_DP * SPLITTER_COUNT,
        )
        return widths.copy(
            hierarchy = (widths.hierarchy + deltaDp)
                .coerceIn(HIERARCHY_MIN_WIDTH_DP, maximum),
        )
    }

    fun dragProperties(
        widths: PaneWidths,
        deltaDp: Float,
        availableWidthDp: Float,
    ): PaneWidths {
        val maximum = maxOf(
            PROPERTIES_MIN_WIDTH_DP,
            availableWidthDp -
                widths.hierarchy -
                CANVAS_MIN_WIDTH_DP -
                SPLITTER_WIDTH_DP * SPLITTER_COUNT,
        )
        return widths.copy(
            properties = (widths.properties - deltaDp)
                .coerceIn(PROPERTIES_MIN_WIDTH_DP, maximum),
        )
    }
}
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run:

```bash
./gradlew :desktop-app:test --tests 'dev.agentperf.desktop.PaneLayoutTest'
```

Expected: `PaneLayoutTest` passes with zero failures.

- [ ] **Step 5: Commit the pure model and tests**

```bash
git add desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/PaneLayout.kt \
  desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/PaneLayoutTest.kt
git commit -m "Keep pane resizing within usable inspector bounds" \
  -m "Constraint: Side panes cannot collapse and CANVAS retains 320dp when window width permits." \
  -m "Confidence: high" \
  -m "Scope-risk: narrow" \
  -m "Tested: PaneLayoutTest."
```

### Task 2: Compose draggable separators

**Files:**
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt:1-130`
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt:415-420`

- [ ] **Step 1: Add the pointer and cursor imports**

Add:

```kotlin
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import java.awt.Cursor
```

- [ ] **Step 2: Store session widths and measure the content row**

Inside `DesktopViewerApp`, before `MaterialTheme`, add:

```kotlin
var paneWidths by remember { mutableStateOf(PaneWidths()) }
```

Replace the current middle `Row(modifier = Modifier.weight(1f))` with:

```kotlin
BoxWithConstraints(modifier = Modifier.weight(1f)) {
    val availableWidthDp = maxWidth.value
    Row(modifier = Modifier.fillMaxSize()) {
        HierarchyPane(
            state = state,
            onSelect = { id ->
                if (store.selectNode(id)) state = store.state
            },
            modifier = Modifier.width(paneWidths.hierarchy.dp).fillMaxHeight(),
        )
        ResizableSeparator(
            onDrag = { deltaDp ->
                paneWidths = PaneLayout.dragHierarchy(
                    widths = paneWidths,
                    deltaDp = deltaDp,
                    availableWidthDp = availableWidthDp,
                )
            },
        )
        PreviewPane(state, Modifier.weight(1f).fillMaxHeight())
        ResizableSeparator(
            onDrag = { deltaDp ->
                paneWidths = PaneLayout.dragProperties(
                    widths = paneWidths,
                    deltaDp = deltaDp,
                    availableWidthDp = availableWidthDp,
                )
            },
        )
        DetailsPane(
            state = state,
            modifier = Modifier.width(paneWidths.properties.dp).fillMaxHeight(),
        )
    }
}
```

This keeps `paneWidths` in memory only. Do not add preferences, files, or saved-state APIs.

- [ ] **Step 3: Replace the passive separator with a draggable separator**

Replace `Separator()` with:

```kotlin
@Composable
private fun ResizableSeparator(onDrag: (Float) -> Unit) {
    val density = LocalDensity.current
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(PaneLayout.SPLITTER_WIDTH_DP.dp)
            .pointerHoverIcon(PointerIcon(Cursor(Cursor.E_RESIZE_CURSOR)))
            .pointerInput(density) {
                detectHorizontalDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount / density.density)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxHeight().width(1.dp).background(Border))
    }
}
```

- [ ] **Step 4: Compile and run all desktop tests**

Run:

```bash
./gradlew :desktop-app:test --rerun-tasks :desktop-app:assemble
```

Expected: all desktop tests pass and `:desktop-app:assemble` succeeds.

### Task 3: Documentation and desktop verification

**Files:**
- Modify: `desktop-viewer/README.md:54`

- [ ] **Step 1: Document the draggable panes**

Append to the live-view paragraph:

```markdown
The two vertical separators can be dragged to resize HIERARCHY and PROPERTIES for the current session; CANVAS uses the remaining width.
```

- [ ] **Step 2: Run complete verification**

Run:

```bash
cd desktop-viewer
./gradlew clean test assemble
git -C .. diff --check
```

Expected: Gradle reports `BUILD SUCCESSFUL` and `diff --check` exits zero.

- [ ] **Step 3: Verify the interaction in the Desktop application**

Run:

```bash
./gradlew :desktop-app:run
```

Confirm:

1. hovering either 7 dp splitter shows the horizontal resize cursor;
2. dragging the left splitter changes only HIERARCHY width;
3. dragging the right splitter changes only PROPERTIES width;
4. neither side pane can collapse below its minimum;
5. CANVAS remains usable and its screenshot and selected overlay stay aligned;
6. restarting the application restores both side panes to 300 dp.

- [ ] **Step 4: Commit the Compose interaction and documentation**

```bash
git add desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt \
  desktop-viewer/README.md
git commit -m "Let inspectors allocate space between desktop panes" \
  -m "Constraint: Width changes remain session-only and preserve existing inspector behavior." \
  -m "Rejected: Persisted ratios or a split-pane dependency | Both exceed the requested scope." \
  -m "Confidence: high" \
  -m "Scope-risk: narrow" \
  -m "Directive: Keep drag deltas in dp before applying PaneLayout bounds." \
  -m "Tested: clean test assemble; manual dual-splitter desktop verification."
```

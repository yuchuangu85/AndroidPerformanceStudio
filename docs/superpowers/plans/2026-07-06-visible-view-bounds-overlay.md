# Visible View Bounds Overlay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a persisted View-menu switch that draws every effectively visible node as a light-cyan canvas outline while always drawing the selected node last with a stronger red outline.

**Architecture:** Extend the existing persisted `ViewDisplayOptions` and native menu model, then add a pure `ViewBoundsOverlay` traversal that converts qualifying protocol nodes into clipped canvas rectangles through `CanvasGeometry`. Keep Compose responsible only for drawing the returned rectangles in the approved order and colors.

**Tech Stack:** Kotlin 2.3, Compose Multiplatform Desktop, Java Preferences, JUnit 5, Gradle.

---

## File Structure

- Modify `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewDisplayOptions.kt`: add and persist the overlay switch.
- Modify `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewerStrings.kt`: add English and Simplified Chinese menu labels.
- Modify `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/NativeViewerMenuBar.kt`: add the option to a separate View-menu group and render a separator.
- Create `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewBoundsOverlay.kt`: filter, traverse, clip, and map visible node bounds.
- Modify `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewerTheme.kt`: expose the approved light-cyan outline color.
- Modify `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt`: pass the option into `PreviewPane` and draw general outlines before the selected outline.
- Modify `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ViewDisplayOptionsTest.kt`: test defaults, independent toggles, and persistence.
- Modify `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/NativeViewerMenuBarTest.kt`: test ordering, grouping, labels, and checked state.
- Modify `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/LanguagePreferenceTest.kt`: test both localized labels.
- Create `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ViewBoundsOverlayTest.kt`: test effective visibility and mapped bounds.
- Modify `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ThemePreferenceTest.kt`: lock the approved palette color.
- Create `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/CanvasOverlayWiringTest.kt`: lock option gating, drawing order, and stroke strengths.

### Task 1: Persist and group the new View option

**Files:**
- Modify: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ViewDisplayOptionsTest.kt`
- Modify: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/NativeViewerMenuBarTest.kt`
- Modify: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/LanguagePreferenceTest.kt`
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewDisplayOptions.kt`
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewerStrings.kt`
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/NativeViewerMenuBar.kt`

- [ ] **Step 1: Write failing option and persistence expectations**

Update the default value in `ViewDisplayOptionsTest`:

```kotlin
ViewDisplayOptions(
    hideInvisibleHierarchyViews = false,
    hideInvisibleFindings = false,
    hideHierarchyIndices = false,
    showVisibleViewBounds = false,
)
```

Add this assertion inside the existing `each view option toggles independently` loop:

```kotlin
assertEquals(
    option == ViewDisplayOption.SHOW_VISIBLE_VIEW_BOUNDS,
    toggled.showVisibleViewBounds,
)
```

Change the persisted expected value to:

```kotlin
val expected = ViewDisplayOptions(
    hideInvisibleHierarchyViews = true,
    hideInvisibleFindings = false,
    hideHierarchyIndices = true,
    showVisibleViewBounds = true,
)
```

Change the stored-key assertion from `3` to `4`.

- [ ] **Step 2: Write failing menu and localization expectations**

In `NativeViewerMenuBarTest`, construct:

```kotlin
viewDisplayOptions = ViewDisplayOptions(
    hideInvisibleHierarchyViews = true,
    hideInvisibleFindings = false,
    hideHierarchyIndices = true,
    showVisibleViewBounds = true,
)
```

Replace the View-menu expectations with:

```kotlin
assertEquals(
    listOf(
        ViewDisplayOption.HIDE_INVISIBLE_HIERARCHY_VIEWS,
        ViewDisplayOption.HIDE_INVISIBLE_FINDINGS,
        ViewDisplayOption.HIDE_HIERARCHY_INDICES,
        ViewDisplayOption.SHOW_VISIBLE_VIEW_BOUNDS,
    ),
    model.viewItems.map { it.option },
)
assertEquals(
    listOf(
        "隐藏层级结构中的不可见视图",
        "隐藏问题列表中的不可见视图内容",
        "隐藏层级索引",
        "显示全部可见视图边缘",
    ),
    model.viewItems.map { it.label },
)
assertEquals(listOf(0, 0, 0, 1), model.viewItems.map { it.group })
assertEquals(listOf(true, false, true, true), model.viewItems.map { it.checked })
```

Add these assertions to `LanguagePreferenceTest`:

```kotlin
assertEquals("显示全部可见视图边缘", chinese.showVisibleViewBounds)
assertEquals("Show all visible view bounds", english.showVisibleViewBounds)
```

- [ ] **Step 3: Run focused tests and verify RED**

Run:

```bash
cd desktop-viewer
./gradlew :desktop-app:test \
  --tests 'dev.agentperf.desktop.ViewDisplayOptionsTest' \
  --tests 'dev.agentperf.desktop.NativeViewerMenuBarTest' \
  --tests 'dev.agentperf.desktop.LanguagePreferenceTest' \
  --console=plain
```

Expected: test compilation fails because `SHOW_VISIBLE_VIEW_BOUNDS`,
`showVisibleViewBounds`, and `NativeViewMenuItem.group` do not exist.

- [ ] **Step 4: Add and persist the option**

Extend `ViewDisplayOption`:

```kotlin
internal enum class ViewDisplayOption {
    HIDE_INVISIBLE_HIERARCHY_VIEWS,
    HIDE_INVISIBLE_FINDINGS,
    HIDE_HIERARCHY_INDICES,
    SHOW_VISIBLE_VIEW_BOUNDS,
}
```

Extend `ViewDisplayOptions` and its toggle:

```kotlin
internal data class ViewDisplayOptions(
    val hideInvisibleHierarchyViews: Boolean = false,
    val hideInvisibleFindings: Boolean = false,
    val hideHierarchyIndices: Boolean = false,
    val showVisibleViewBounds: Boolean = false,
) {
    fun toggle(option: ViewDisplayOption): ViewDisplayOptions = when (option) {
        ViewDisplayOption.HIDE_INVISIBLE_HIERARCHY_VIEWS ->
            copy(hideInvisibleHierarchyViews = !hideInvisibleHierarchyViews)
        ViewDisplayOption.HIDE_INVISIBLE_FINDINGS ->
            copy(hideInvisibleFindings = !hideInvisibleFindings)
        ViewDisplayOption.HIDE_HIERARCHY_INDICES ->
            copy(hideHierarchyIndices = !hideHierarchyIndices)
        ViewDisplayOption.SHOW_VISIBLE_VIEW_BOUNDS ->
            copy(showVisibleViewBounds = !showVisibleViewBounds)
    }
}
```

Add this value to `load()`:

```kotlin
showVisibleViewBounds = readBoolean(SHOW_VISIBLE_VIEW_BOUNDS_KEY, false),
```

Add this write to `save()`:

```kotlin
writeBoolean(SHOW_VISIBLE_VIEW_BOUNDS_KEY, options.showVisibleViewBounds)
```

Add this companion key:

```kotlin
private const val SHOW_VISIBLE_VIEW_BOUNDS_KEY = "view.showVisibleViewBounds"
```

- [ ] **Step 5: Add localized labels**

Add this property beside the existing View-menu strings:

```kotlin
val showVisibleViewBounds: String
    get() = text(
        "Show all visible view bounds",
        "显示全部可见视图边缘",
    )
```

Extend `viewOptionLabel`:

```kotlin
ViewDisplayOption.SHOW_VISIBLE_VIEW_BOUNDS -> showVisibleViewBounds
```

- [ ] **Step 6: Add a separate native menu group**

Extend the menu item:

```kotlin
internal data class NativeViewMenuItem(
    val option: ViewDisplayOption,
    val label: String,
    val group: Int,
    val checked: Boolean,
)
```

Inside the model mapping, add:

```kotlin
group = if (option == ViewDisplayOption.SHOW_VISIBLE_VIEW_BOUNDS) 1 else 0,
```

Extend the checked-state `when`:

```kotlin
ViewDisplayOption.SHOW_VISIBLE_VIEW_BOUNDS ->
    viewDisplayOptions.showVisibleViewBounds
```

Replace the native View-menu loop with:

```kotlin
model.viewItems.forEachIndexed { index, item ->
    if (index > 0 && model.viewItems[index - 1].group != item.group) {
        Separator()
    }
    CheckboxItem(
        text = item.label,
        checked = item.checked,
        onCheckedChange = { onViewOption(item.option) },
    )
}
```

- [ ] **Step 7: Run focused tests and verify GREEN**

Run the Step 3 command.

Expected: all option, menu, and localization tests pass.

- [ ] **Step 8: Commit the View option**

```bash
git add \
  desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewDisplayOptions.kt \
  desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewerStrings.kt \
  desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/NativeViewerMenuBar.kt \
  desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ViewDisplayOptionsTest.kt \
  desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/NativeViewerMenuBarTest.kt \
  desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/LanguagePreferenceTest.kt
git commit -m "Remember whether users want whole-hierarchy bounds" \
  -m $'Constraint: The overlay control belongs in a separate native View-menu group.\nConfidence: high\nScope-risk: narrow\nTested: ViewDisplayOptionsTest, NativeViewerMenuBarTest, and LanguagePreferenceTest'
```

### Task 2: Map effectively visible nodes to canvas rectangles

**Files:**
- Create: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ViewBoundsOverlayTest.kt`
- Create: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewBoundsOverlay.kt`

- [ ] **Step 1: Write failing traversal and mapping tests**

Create `ViewBoundsOverlayTest.kt`:

```kotlin
package dev.agentperf.desktop

import dev.agentperf.protocol.Bounds
import dev.agentperf.protocol.ViewNode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ViewBoundsOverlayTest {
    private val source = CropRect(left = 0, top = 0, width = 100, height = 100)
    private val destination = FloatRect(left = 0f, top = 0f, width = 200f, height = 200f)

    @Test
    fun `visible bounds are mapped clipped and selected node is excluded`() {
        val root = node(
            id = "root",
            bounds = Bounds(0, 0, 100, 100),
            children = listOf(
                node("selected", Bounds(10, 10, 30, 30)),
                node("partial", Bounds(80, 20, 120, 40)),
                node("outside", Bounds(120, 20, 140, 40)),
            ),
        )

        assertEquals(
            listOf(
                FloatRect(left = 0f, top = 0f, width = 200f, height = 200f),
                FloatRect(left = 160f, top = 40f, width = 40f, height = 40f),
            ),
            ViewBoundsOverlay.mappedVisibleBounds(
                root = root,
                selectedNodeId = "selected",
                source = source,
                destination = destination,
            ),
        )
    }

    @Test
    fun `visibility and alpha suppress descendants but invalid size does not`() {
        val survivingChild = node("surviving", Bounds(40, 40, 60, 60))
        val root = node(
            id = "root",
            bounds = Bounds(0, 0, 100, 100),
            children = listOf(
                node(
                    id = "hidden-parent",
                    bounds = Bounds(0, 0, 30, 30),
                    visible = false,
                    children = listOf(node("hidden-child", Bounds(5, 5, 20, 20))),
                ),
                node(
                    id = "transparent-parent",
                    bounds = Bounds(0, 0, 30, 30),
                    alpha = 0f,
                    children = listOf(node("transparent-child", Bounds(5, 5, 20, 20))),
                ),
                node(
                    id = "zero-parent",
                    bounds = Bounds(20, 20, 20, 40),
                    children = listOf(survivingChild),
                ),
            ),
        )

        assertEquals(
            listOf(FloatRect(left = 80f, top = 80f, width = 40f, height = 40f)),
            ViewBoundsOverlay.mappedVisibleBounds(
                root = root,
                selectedNodeId = "root",
                source = source,
                destination = destination,
            ),
        )
    }

    private fun node(
        id: String,
        bounds: Bounds,
        visible: Boolean = true,
        alpha: Float = 1f,
        children: List<ViewNode> = emptyList(),
    ): ViewNode = ViewNode(
        id = id,
        className = "android.view.View",
        bounds = bounds,
        visible = visible,
        alpha = alpha,
        children = children,
    )
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
cd desktop-viewer
./gradlew :desktop-app:test \
  --tests 'dev.agentperf.desktop.ViewBoundsOverlayTest' \
  --console=plain
```

Expected: test compilation fails because `ViewBoundsOverlay` does not exist.

- [ ] **Step 3: Implement the pure overlay traversal**

Create `ViewBoundsOverlay.kt`:

```kotlin
package dev.agentperf.desktop

import dev.agentperf.protocol.UiNode

internal object ViewBoundsOverlay {
    fun mappedVisibleBounds(
        root: UiNode,
        selectedNodeId: String?,
        source: CropRect,
        destination: FloatRect,
    ): List<FloatRect> = buildList {
        fun visit(
            node: UiNode,
            ancestorsVisible: Boolean,
            ancestorsAlpha: Float,
        ) {
            val effectivelyVisible = ancestorsVisible && node.visible
            val effectiveAlpha = ancestorsAlpha * node.alpha
            if (!effectivelyVisible || !(effectiveAlpha > 0f)) return

            if (
                node.id != selectedNodeId &&
                node.bounds.width > 0 &&
                node.bounds.height > 0
            ) {
                CanvasGeometry.mapBounds(
                    bounds = node.bounds,
                    source = source,
                    destination = destination,
                )?.let { mappedBounds -> add(mappedBounds) }
            }

            node.children.forEach { child ->
                visit(
                    node = child,
                    ancestorsVisible = effectivelyVisible,
                    ancestorsAlpha = effectiveAlpha,
                )
            }
        }

        visit(
            node = root,
            ancestorsVisible = true,
            ancestorsAlpha = 1f,
        )
    }
}
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run the Step 2 command.

Expected: both `ViewBoundsOverlayTest` tests pass.

- [ ] **Step 5: Commit the geometry helper**

```bash
git add \
  desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewBoundsOverlay.kt \
  desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ViewBoundsOverlayTest.kt
git commit -m "Derive canvas outlines from effectively visible nodes" \
  -m $'Constraint: Visibility and alpha must propagate through ancestors without suppressing children solely for zero-size parents.\nRejected: Precompute overlays in InspectorPresenter | Canvas cropping and scaling are presentation-specific.\nConfidence: high\nScope-risk: narrow\nTested: ViewBoundsOverlayTest'
```

### Task 3: Draw general bounds behind the selected outline

**Files:**
- Modify: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ThemePreferenceTest.kt`
- Create: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/CanvasOverlayWiringTest.kt`
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewerTheme.kt`
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt`

- [ ] **Step 1: Write failing palette and canvas-wiring tests**

Add this import to `ThemePreferenceTest.kt`:

```kotlin
import androidx.compose.ui.graphics.Color
```

Add this test:

```kotlin
@Test
fun `visible bounds use the approved light cyan in both themes`() {
    listOf(ViewerPalettes.forDark(false), ViewerPalettes.forDark(true)).forEach { palette ->
        assertEquals(Color(0xFF7DD3FC), palette.visibleViewBounds)
    }
}
```

Create `CanvasOverlayWiringTest.kt`:

```kotlin
package dev.agentperf.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CanvasOverlayWiringTest {
    @Test
    fun `general bounds are optional and selected bounds are drawn last`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt"),
        )
        val preview = source
            .substringAfter("private fun PreviewPane(")
            .substringBefore("internal fun canvasCornerRadiusDp")

        val generalBounds = preview.indexOf("ViewBoundsOverlay.mappedVisibleBounds")
        val selectedBounds = preview.indexOf("selectedBounds?.let")

        assertTrue(preview.contains("if (showVisibleViewBounds)"))
        assertTrue(generalBounds >= 0)
        assertTrue(selectedBounds > generalBounds)
        assertTrue(preview.contains("colors.visibleViewBounds.copy(alpha = 0.62f)"))
        assertTrue(preview.contains("Stroke(width = 1.dp.toPx())"))
        assertTrue(preview.contains("Stroke(width = 3.dp.toPx())"))
    }
}
```

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```bash
cd desktop-viewer
./gradlew :desktop-app:test \
  --tests 'dev.agentperf.desktop.ThemePreferenceTest' \
  --tests 'dev.agentperf.desktop.CanvasOverlayWiringTest' \
  --console=plain
```

Expected: compilation fails because `visibleViewBounds` does not exist; after
that field is added, `CanvasOverlayWiringTest` remains red until the canvas is
wired.

- [ ] **Step 3: Add the approved palette color**

Add this field to `ViewerColors` after `previewCanvas`:

```kotlin
val visibleViewBounds: Color,
```

Add this value to both the light and dark palettes:

```kotlin
visibleViewBounds = Color(0xFF7DD3FC),
```

- [ ] **Step 4: Pass the persisted option into PreviewPane**

Replace the current call with:

```kotlin
PreviewPane(
    state = state,
    showVisibleViewBounds = viewDisplayOptions.showVisibleViewBounds,
    modifier = Modifier.weight(1f).fillMaxHeight(),
)
```

Replace the function signature with:

```kotlin
@Composable
private fun PreviewPane(
    state: InspectorState,
    showVisibleViewBounds: Boolean,
    modifier: Modifier,
) {
```

- [ ] **Step 5: Draw general outlines before the selected outline**

Immediately after `drawImage(...)` and before `selectedBounds?.let`, add:

```kotlin
if (showVisibleViewBounds) {
    state.snapshot?.root?.let { root ->
        ViewBoundsOverlay.mappedVisibleBounds(
            root = root,
            selectedNodeId = state.selectedNodeId,
            source = source,
            destination = destination,
        ).forEach { overlay ->
            drawRect(
                color = colors.visibleViewBounds.copy(alpha = 0.62f),
                topLeft = Offset(overlay.left, overlay.top),
                size = Size(overlay.width, overlay.height),
                style = Stroke(width = 1.dp.toPx()),
            )
        }
    }
}
```

Change the selected outline stroke from:

```kotlin
style = Stroke(width = 2.dp.toPx()),
```

to:

```kotlin
style = Stroke(width = 3.dp.toPx()),
```

- [ ] **Step 6: Run focused tests and verify GREEN**

Run the Step 2 command plus:

```bash
./gradlew :desktop-app:test \
  --tests 'dev.agentperf.desktop.ViewBoundsOverlayTest' \
  --tests 'dev.agentperf.desktop.ViewDisplayOptionsTest' \
  --tests 'dev.agentperf.desktop.NativeViewerMenuBarTest' \
  --console=plain
```

Expected: all palette, wiring, geometry, persistence, and menu tests pass.

- [ ] **Step 7: Commit the canvas rendering**

```bash
git add \
  desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewerTheme.kt \
  desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt \
  desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ThemePreferenceTest.kt \
  desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/CanvasOverlayWiringTest.kt
git commit -m "Reveal every visible bound without weakening selection" \
  -m $'Constraint: General outlines stay light and optional while the selected red outline remains unconditional and topmost.\nConfidence: high\nScope-risk: moderate\nDirective: Keep whole-tree overlays independent from hierarchy filtering and expansion.\nTested: ThemePreferenceTest, CanvasOverlayWiringTest, ViewBoundsOverlayTest, ViewDisplayOptionsTest, and NativeViewerMenuBarTest'
```

### Task 4: Complete verification

**Files:**
- Verify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt`
- Verify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewBoundsOverlay.kt`
- Verify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewDisplayOptions.kt`

- [ ] **Step 1: Run the complete Gradle test suite**

Run:

```bash
cd desktop-viewer
./gradlew test --rerun-tasks --console=plain
```

Expected: `BUILD SUCCESSFUL` with zero failed tests.

- [ ] **Step 2: Build the Desktop application**

Run:

```bash
./gradlew :desktop-app:createDistributable --no-daemon --console=plain
```

Expected: `BUILD SUCCESSFUL` and a native application image under
`desktop-app/build/compose/binaries/main/app/`.

- [ ] **Step 3: Smoke-test Desktop startup**

Run:

```bash
./gradlew :desktop-app:run --no-daemon --console=plain
```

Launch the command with a bounded timeout. Confirm the Desktop UI starts
without an immediate error, then terminate only the launched process. No
Android device, captured hierarchy, or screenshot was available during this
verification, so actual visible-bounds rendering was not exercised.

- [ ] **Step 4: Inspect repository state**

Run:

```bash
git diff --check
git status --short --branch
git log -5 --format=full
```

Expected: no whitespace errors, only the implementation-plan file remains
uncommitted, and implementation commits include Lore trailers.

- [ ] **Step 5: Commit the implementation plan**

From the repository root:

```bash
git add docs/superpowers/plans/2026-07-06-visible-view-bounds-overlay.md
git commit -m "Preserve the visible-bounds implementation procedure" \
  -m $'Constraint: Visual smoke testing requires a captured Android hierarchy and screenshot.\nConfidence: high\nScope-risk: narrow\nTested: Full Gradle tests, desktop distributable build, desktop startup smoke, and git diff validation.\nNot-tested: Actual visible-bounds rendering because no captured Android hierarchy and screenshot were available during smoke testing.'
```

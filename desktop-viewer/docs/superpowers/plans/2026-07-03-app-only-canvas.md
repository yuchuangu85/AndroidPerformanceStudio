# App-Only Canvas Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the canvas fill available space with the foreground application's true aspect ratio, preserve accurate selection overlays, add a full-device toggle, and tighten hierarchy rows.

**Architecture:** Keep captured PNGs and protocol bounds unchanged. Add pure crop and coordinate-transform functions to `CanvasGeometry`, then let the Compose preview select either the hierarchy-root crop or the complete display as its source rectangle. UI state remains local to the desktop preview.

**Tech Stack:** Kotlin 2.3, Compose Multiplatform Desktop, Skia image drawing, JUnit 5, Gradle.

---

### Task 1: Crop and overlay geometry

**Files:**
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/CanvasGeometry.kt`
- Modify: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/CanvasGeometryTest.kt`

- [ ] **Step 1: Write failing source-rectangle tests**

Add tests that request an app-only crop from `Bounds(1508, 300, 2332, 1764)`, verify clamping at display edges, verify invalid bounds fall back to `CropRect(0, 0, 3840, 2160)`, and verify full-device mode always returns the full display.

```kotlin
assertEquals(
    CropRect(left = 1508, top = 300, width = 824, height = 1464),
    CanvasGeometry.sourceRect(appBounds, 3840, 2160, appOnly = true),
)
```

- [ ] **Step 2: Run the geometry tests and verify RED**

Run:

```bash
./gradlew :desktop-app:test --tests 'dev.agentperf.desktop.CanvasGeometryTest'
```

Expected: compilation fails because `CropRect` and `sourceRect` do not exist.

- [ ] **Step 3: Implement the minimal source-rectangle API**

Add:

```kotlin
data class CropRect(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

fun sourceRect(
    appBounds: Bounds?,
    displayWidth: Int,
    displayHeight: Int,
    appOnly: Boolean,
): CropRect
```

Clamp each edge to the display. Return the full display when app-only mode is disabled or the clamped rectangle has no positive area.

- [ ] **Step 4: Write failing cropped-overlay tests**

Test that absolute node bounds subtract the crop origin before scaling, partial bounds are clipped, and bounds outside the crop return `null`.

```kotlin
assertEquals(
    FloatRect(left = 50f, top = 25f, width = 100f, height = 50f),
    CanvasGeometry.mapBounds(nodeBounds, crop, destination),
)
```

- [ ] **Step 5: Run the geometry tests and verify RED**

Run the same targeted test command. Expected: compilation fails because the crop-aware `mapBounds` overload does not exist.

- [ ] **Step 6: Implement crop-aware mapping**

Add:

```kotlin
fun mapBounds(
    bounds: Bounds,
    source: CropRect,
    destination: FloatRect,
): FloatRect?
```

Intersect absolute bounds with `source`, return `null` for an empty intersection, subtract `source.left/top`, then scale by `destination.width/source.width` and `destination.height/source.height`.

- [ ] **Step 7: Run targeted tests and commit**

Run:

```bash
./gradlew :desktop-app:test --tests 'dev.agentperf.desktop.CanvasGeometryTest'
```

Expected: all `CanvasGeometryTest` cases pass.

Commit the geometry and tests with Lore trailers.

### Task 2: App-only preview and mode toggle

**Files:**
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt`

- [ ] **Step 1: Derive preview source and aspect ratio**

Inside `PreviewPane`, remember `appOnly = true`, calculate `source` with `CanvasGeometry.sourceRect`, and calculate `source.width.toFloat() / source.height`.

- [ ] **Step 2: Add the CANVAS toggle**

Extend `PanelTitle` to accept composable trailing content and render a clickable `仅应用 ON/OFF` control. Toggling changes only the local preview state.

- [ ] **Step 3: Size the surface from the real source ratio**

Use `BoxWithConstraints` to fit a preview of the source aspect ratio into available width and height. Retain a `390.dp` portrait width cap, but allow landscape sources to use the available canvas width.

- [ ] **Step 4: Draw the cropped screenshot**

Call the source/destination overload of `drawImage`:

```kotlin
drawImage(
    image = screenshot,
    srcOffset = IntOffset(source.left, source.top),
    srcSize = IntSize(source.width, source.height),
    dstOffset = IntOffset.Zero,
    dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()),
)
```

Map selected bounds with the crop-aware `CanvasGeometry.mapBounds` and draw only non-null overlays.

- [ ] **Step 5: Run desktop tests**

Run:

```bash
./gradlew :desktop-app:test
```

Expected: all desktop tests pass.

### Task 3: Compact hierarchy and end-to-end verification

**Files:**
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt`
- Modify: `desktop-viewer/README.md`

- [ ] **Step 1: Tighten hierarchy rows**

Change hierarchy item vertical padding from `7.dp` to `3.dp`. Preserve `12.sp` text, horizontal padding, depth indentation, and the full-row click target.

- [ ] **Step 2: Document the canvas option**

Update the README live-view description to state that app-only mode is the default and full-device mode remains available from the CANVAS toggle.

- [ ] **Step 3: Run full verification**

Run:

```bash
./gradlew clean test assemble
git -C .. diff --check
```

Expected: Gradle reports `BUILD SUCCESSFUL` and diff check exits zero.

- [ ] **Step 4: Verify on the connected emulator**

Launch `com.codemx.anrdemo` and `:desktop-app:run`. Confirm:

1. the CANVAS title reports the application crop dimensions;
2. the application fills the preview at `824 × 1464` ratio;
3. selected hierarchy nodes retain aligned red overlays;
4. toggling app-only off shows the complete `3840 × 2160` display;
5. hierarchy rows are visibly denser.

- [ ] **Step 5: Commit the implementation**

Commit source, tests, and documentation using the Lore protocol, including automated and emulator verification in `Tested:`.

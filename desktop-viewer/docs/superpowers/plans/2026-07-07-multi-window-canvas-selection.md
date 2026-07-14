# Multi-Window Canvas Selection Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let users switch among current-application windows, select and hover views directly on the canvas, see resource IDs in the hierarchy, and configure the three canvas border colors.

**Architecture:** Extend `LayoutSnapshot` with backward-compatible window records and make `InspectorState` project one active window into the existing hierarchy/details/findings surfaces. Collect process-owned Android windows in the Agent on API 29+, preserve ADB fallback multi-window entries, and keep a full-display screenshot as the common coordinate space. Isolate pointer hit testing, click cycling, hierarchy reveal, and color persistence into focused, unit-testable desktop types before wiring Compose UI.

**Tech Stack:** Kotlin 2.x, kotlinx.serialization, Android View APIs, `WindowInspector`, ADB, Compose Multiplatform Desktop, JUnit 5, Java Preferences.

---

## File Structure

### New production files

- `desktop-viewer/shared-kernel/android-agent-view/src/main/kotlin/dev/agentperf/android/view/WindowRootProvider.kt`
  - Enumerates process-owned root views and derives stable metadata without hidden APIs.
- `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/CanvasHitTester.kt`
  - Computes effective clipping, paint order, and the topmost hit path.
- `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/CanvasPointerSelection.kt`
  - Tracks hover and repeated-click ancestor cycling.
- `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/CanvasBorderColors.kt`
  - Defines validated ARGB colors and Java Preferences persistence.

### New test files

- `desktop-viewer/shared-kernel/android-agent-view/src/test/kotlin/dev/agentperf/android/view/WindowRootProviderTest.kt`
- `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/CanvasHitTesterTest.kt`
- `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/CanvasPointerSelectionTest.kt`
- `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/CanvasBorderColorsTest.kt`

### Existing files with focused changes

- `desktop-viewer/shared-kernel/protocol-model/src/main/kotlin/dev/agentperf/protocol/ProtocolModels.kt`
- `desktop-viewer/shared-kernel/protocol-model/src/test/kotlin/dev/agentperf/protocol/ProtocolCodecTest.kt`
- `desktop-viewer/shared-kernel/android-agent-view/src/main/kotlin/dev/agentperf/android/view/ActivityCaptureProvider.kt`
- `desktop-viewer/shared-kernel/android-agent-view/src/main/kotlin/dev/agentperf/android/view/LiveSnapshotFactory.kt`
- `desktop-viewer/shared-kernel/android-agent-view/src/main/kotlin/dev/agentperf/android/view/ViewTreeCollector.kt`
- `desktop-viewer/shared-kernel/android-agent-view/src/test/kotlin/dev/agentperf/android/view/LiveSnapshotFactoryTest.kt`
- `desktop-viewer/adb-gateway/src/main/kotlin/dev/agentperf/adb/VisibleWindowHierarchyParser.kt`
- `desktop-viewer/adb-gateway/src/main/kotlin/dev/agentperf/adb/AdbFallbackCapture.kt`
- `desktop-viewer/adb-gateway/src/main/kotlin/dev/agentperf/adb/LiveDeviceClient.kt`
- `desktop-viewer/adb-gateway/src/test/kotlin/dev/agentperf/adb/VisibleWindowHierarchyParserTest.kt`
- `desktop-viewer/adb-gateway/src/test/kotlin/dev/agentperf/adb/LiveDeviceClientTest.kt`
- `desktop-viewer/application/src/main/kotlin/dev/agentperf/application/InspectorState.kt`
- `desktop-viewer/application/src/main/kotlin/dev/agentperf/application/InspectorStore.kt`
- `desktop-viewer/application/src/test/kotlin/dev/agentperf/application/InspectorStoreTest.kt`
- `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/InspectorPresenter.kt`
- `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/HierarchyTreeState.kt`
- `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/NativeViewerMenuBar.kt`
- `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt`
- `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ThemeSettingsDialog.kt`
- `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewerStrings.kt`
- `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/InspectorPresenterTest.kt`
- `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/HierarchyTreeStateTest.kt`
- `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/NativeViewerMenuBarTest.kt`
- `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/LanguagePreferenceTest.kt`
- `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/CaptureArchiveServiceTest.kt`

## Task 1: Add the Backward-Compatible Window Protocol

**Files:**
- Modify: `desktop-viewer/shared-kernel/protocol-model/src/main/kotlin/dev/agentperf/protocol/ProtocolModels.kt`
- Modify: `desktop-viewer/shared-kernel/protocol-model/src/test/kotlin/dev/agentperf/protocol/ProtocolCodecTest.kt`

- [ ] **Step 1: Write failing protocol round-trip and legacy-decoding tests**

Add tests that construct two roots with namespaced node IDs and verify:

```kotlin
val snapshot = SampleSnapshots.dashboard.copy(
    windows = listOf(
        WindowSnapshot(
            id = "window:main",
            title = "MainActivity",
            type = WindowType.ACTIVITY,
            bounds = Bounds(0, 80, 1080, 2400),
            root = mainRoot,
        ),
        WindowSnapshot(
            id = "window:dialog",
            title = "Confirm",
            type = WindowType.DIALOG,
            bounds = Bounds(120, 700, 960, 1500),
            root = dialogRoot,
        ),
    ),
    defaultWindowId = "window:main",
    root = mainRoot,
)
assertEquals(snapshot, codec.decodeSnapshot(codec.encodeSnapshot(snapshot)))
```

Also decode an existing JSON fixture with no `windows` or `defaultWindowId` and assert
`decoded.effectiveWindows.single().root == decoded.root`.

- [ ] **Step 2: Run the focused protocol test and verify it fails**

Run:

```bash
cd desktop-viewer
./gradlew :shared-kernel:protocol-model:test --tests dev.agentperf.protocol.ProtocolCodecTest
```

Expected: compilation fails because `WindowSnapshot`, `WindowType`, and window projection do not exist.

- [ ] **Step 3: Add the serialized window types and legacy projection**

Add to `ProtocolModels.kt`:

```kotlin
@Serializable
enum class WindowType {
    ACTIVITY,
    DIALOG,
    POPUP,
    OTHER,
}

@Serializable
data class WindowSnapshot(
    val id: String,
    val title: String,
    val type: WindowType = WindowType.OTHER,
    val bounds: Bounds,
    val root: UiNode,
)
```

Extend `LayoutSnapshot` with defaulted fields:

```kotlin
val windows: List<WindowSnapshot> = emptyList(),
val defaultWindowId: String? = null,
```

Add:

```kotlin
val LayoutSnapshot.effectiveWindows: List<WindowSnapshot>
    get() = windows.ifEmpty {
        listOf(
            WindowSnapshot(
                id = LEGACY_WINDOW_ID,
                title = packageName.substringAfterLast('.'),
                bounds = root.bounds,
                root = root,
            ),
        )
    }

val LayoutSnapshot.effectiveDefaultWindowId: String
    get() = defaultWindowId
        ?.takeIf { id -> effectiveWindows.any { it.id == id } }
        ?: effectiveWindows.first().id

const val LEGACY_WINDOW_ID = "window:legacy"
```

Keep protocol major version `1`; the new defaulted fields are additive.

- [ ] **Step 4: Run focused tests and verify they pass**

Run the Task 1 command again.

Expected: all `ProtocolCodecTest` tests pass, including old JSON decoding.

- [ ] **Step 5: Commit the protocol boundary**

```bash
git add desktop-viewer/shared-kernel/protocol-model
git commit -m "Let captures describe every application window" \
  -m "Constraint: Preserve protocol-major compatibility with legacy single-root snapshots.
Rejected: Replacing root with windows | Existing viewers and archives require root.
Confidence: high
Scope-risk: moderate
Directive: Treat effectiveWindows as the only compatibility projection for callers.
Tested: :shared-kernel:protocol-model:test"
```

## Task 2: Make Inspector State Window-Aware

**Files:**
- Modify: `desktop-viewer/application/src/main/kotlin/dev/agentperf/application/InspectorState.kt`
- Modify: `desktop-viewer/application/src/main/kotlin/dev/agentperf/application/InspectorStore.kt`
- Modify: `desktop-viewer/application/src/test/kotlin/dev/agentperf/application/InspectorStoreTest.kt`

- [ ] **Step 1: Write failing state-transition tests**

Add a two-window snapshot fixture in `InspectorStoreTest` and verify:

```kotlin
store.loadCapture(snapshot, png)
assertEquals("window:main", store.state.selectedWindowId)
assertEquals(mainRoot, store.state.activeRoot)

assertTrue(store.selectNode("window:main/title"))
assertTrue(store.selectWindow("window:dialog"))
assertEquals("window:dialog/root", store.state.selectedNodeId)

assertTrue(store.selectNode("window:dialog/message"))
assertTrue(store.selectWindow("window:main"))
assertEquals("window:main/title", store.state.selectedNodeId)
```

Refresh with the dialog removed and assert the main window becomes active. Refresh with the
selected main child removed and assert the main root is selected.

- [ ] **Step 2: Run the application test and verify it fails**

```bash
cd desktop-viewer
./gradlew :application:test --tests dev.agentperf.application.InspectorStoreTest
```

Expected: compilation fails on `selectedWindowId`, `activeRoot`, and `selectWindow`.

- [ ] **Step 3: Project an active window in `InspectorState`**

Add:

```kotlin
val selectedWindowId: String? = null,
val selectedNodeIdsByWindow: Map<String, String> = emptyMap(),
val hoveredNodeId: String? = null,
```

Expose derived values:

```kotlin
val windows: List<WindowSnapshot>
    get() = snapshot?.effectiveWindows.orEmpty()

val activeWindow: WindowSnapshot?
    get() = windows.firstOrNull { it.id == selectedWindowId }
        ?: windows.firstOrNull()

val activeRoot: UiNode?
    get() = activeWindow?.root

val selectedNode: UiNode?
    get() = activeRoot?.findById(selectedNodeId)
```

Make `findById` an `internal` application-module extension so both state and store repair use the
same active-root search.

- [ ] **Step 4: Implement window selection and repair in `InspectorStore`**

Centralize loading in a repair function that:

```kotlin
val windows = snapshot.effectiveWindows
val selectedWindowId = previous.selectedWindowId
    ?.takeIf { id -> windows.any { it.id == id } }
    ?: snapshot.effectiveDefaultWindowId
val activeRoot = windows.first { it.id == selectedWindowId }.root
val rememberedNodeId = previous.selectedNodeIdsByWindow[selectedWindowId]
    ?.takeIf { activeRoot.findById(it) != null }
    ?: activeRoot.id
```

Add:

```kotlin
fun selectWindow(windowId: String): Boolean
fun setHoveredNode(nodeId: String?)
```

`selectNode` updates both `selectedNodeId` and `selectedNodeIdsByWindow[selectedWindowId]`.
Analysis always runs on `activeRoot`, and switching windows recomputes it immediately.

- [ ] **Step 5: Run the application tests and verify they pass**

Run the Task 2 command again.

Expected: all `InspectorStoreTest` tests pass.

- [ ] **Step 6: Commit window-aware state**

```bash
git add desktop-viewer/application
git commit -m "Keep inspection state scoped to the chosen window" \
  -m "Constraint: Existing hierarchy and findings surfaces consume one active root.
Rejected: Mixing all roots into one synthetic tree | Analysis and selection would cross window boundaries.
Confidence: high
Scope-risk: moderate
Directive: Repair window and node selection on every refreshed capture.
Tested: :application:test"
```

## Task 3: Collect All Agent-Owned Window Roots

**Files:**
- Create: `desktop-viewer/shared-kernel/android-agent-view/src/main/kotlin/dev/agentperf/android/view/WindowRootProvider.kt`
- Modify: `desktop-viewer/shared-kernel/android-agent-view/src/main/kotlin/dev/agentperf/android/view/ActivityCaptureProvider.kt`
- Modify: `desktop-viewer/shared-kernel/android-agent-view/src/main/kotlin/dev/agentperf/android/view/LiveSnapshotFactory.kt`
- Modify: `desktop-viewer/shared-kernel/android-agent-view/src/main/kotlin/dev/agentperf/android/view/ViewTreeCollector.kt`
- Modify: `desktop-viewer/shared-kernel/android-agent-view/src/test/kotlin/dev/agentperf/android/view/LiveSnapshotFactoryTest.kt`
- Create: `desktop-viewer/shared-kernel/android-agent-view/src/test/kotlin/dev/agentperf/android/view/WindowRootProviderTest.kt`

- [ ] **Step 1: Replace the local-coordinate factory test with failing screen-coordinate multi-window tests**

Verify a main root at `(100, 200)` and dialog root at `(240, 600)` remain in screen
coordinates, acquire namespaced IDs, and populate `snapshot.windows`:

```kotlin
assertEquals(Bounds(100, 200, 900, 1800), snapshot.root.bounds)
assertEquals(
    listOf("window:main/root", "window:dialog/root"),
    snapshot.windows.map { it.root.id },
)
assertEquals("window:main", snapshot.defaultWindowId)
```

- [ ] **Step 2: Run the Android Agent unit tests and verify they fail**

```bash
cd desktop-viewer
./gradlew :shared-kernel:android-agent-view:testDebugUnitTest
```

Expected: the new factory signature and multi-window assertions fail.

- [ ] **Step 3: Add injectable process-window enumeration**

Create:

```kotlin
internal data class WindowRoot(
    val id: String,
    val title: String,
    val type: WindowType,
    val view: View,
)

internal fun interface WindowRootProvider {
    fun roots(activity: Activity): List<WindowRoot>
}
```

The production provider:

- calls `WindowInspector.getGlobalWindowViews()` when `SDK_INT >= 29`;
- filters detached, invisible, or zero-size roots;
- uses `View.getUniqueDrawingId()` for `window:<drawing-id>`;
- reads `WindowManager.LayoutParams.title` when present;
- classifies the resumed Activity decor root as `ACTIVITY`, roots with
  `WindowManager.LayoutParams.TYPE_APPLICATION_PANEL` or
  `TYPE_APPLICATION_SUB_PANEL` as `POPUP`, other application roots as `DIALOG`,
  and everything else as `OTHER`;
- falls back to `activity.window.decorView.rootView` with ID `window:activity`.

Route API-level behavior through this pure helper and test it with string roots:

```kotlin
internal fun <T> processWindowRoots(
    sdkInt: Int,
    globalRoots: () -> List<T>,
    activityRoot: () -> T,
): List<T> =
    if (sdkInt >= 29) globalRoots().ifEmpty { listOf(activityRoot()) }
    else listOf(activityRoot())
```

`WindowRootProviderTest` asserts API 29 retains every global root, API 28 uses only the Activity
root, and an empty API 29 result falls back to the Activity root.

- [ ] **Step 4: Namespace collected node IDs**

Change `ViewTreeCollector` to:

```kotlin
fun collect(root: View, windowId: String): ViewNode =
    collect(root, "$windowId/root")
```

Preserve `getLocationOnScreen` bounds; do not translate roots to local coordinates.

- [ ] **Step 5: Build the multi-window snapshot**

Change `LiveSnapshotFactory.create` to accept:

```kotlin
windows: List<WindowSnapshot>,
defaultWindowId: String,
```

and set `root` to the default window root. Set protocol minor to `1`.

In `ActivityCaptureProvider`, enumerate roots on the main thread, collect each root, and use
the resumed Activity root as the default when present. Keep the existing PixelCopy image as a
transport fallback; Task 4 replaces it with full-display ADB capture when possible.

- [ ] **Step 6: Run Android Agent tests and verify they pass**

Run the Task 3 command again.

Expected: all Android Agent view tests pass on the local JVM target.

- [ ] **Step 7: Commit Agent collection**

```bash
git add desktop-viewer/shared-kernel/android-agent-view
git commit -m "Capture every window attached to the application process" \
  -m "Constraint: API 21 remains supported without hidden WindowManager access.
Rejected: Reflective global-root lookup | Public WindowInspector is available from API 29.
Confidence: medium
Scope-risk: broad
Directive: Keep all collected bounds in full-screen coordinates.
Tested: :shared-kernel:android-agent-view:testDebugUnitTest
Not-tested: Device-specific vendor window implementations"
```

## Task 4: Preserve Every ADB Window and a Full-Display Screenshot

**Files:**
- Modify: `desktop-viewer/adb-gateway/src/main/kotlin/dev/agentperf/adb/VisibleWindowHierarchyParser.kt`
- Modify: `desktop-viewer/adb-gateway/src/main/kotlin/dev/agentperf/adb/AdbFallbackCapture.kt`
- Modify: `desktop-viewer/adb-gateway/src/main/kotlin/dev/agentperf/adb/LiveDeviceClient.kt`
- Modify: `desktop-viewer/adb-gateway/src/test/kotlin/dev/agentperf/adb/VisibleWindowHierarchyParserTest.kt`
- Modify: `desktop-viewer/adb-gateway/src/test/kotlin/dev/agentperf/adb/LiveDeviceClientTest.kt`

- [ ] **Step 1: Write failing ADB multi-window tests**

Extend `multiWindowZip()` with two valid entries for the target package and one valid foreign
package entry. Assert:

```kotlin
val windows = VisibleWindowHierarchyParser.parseWindows(zipBytes, packageName)
assertEquals(2, windows.size)
assertEquals(
    listOf("MainActivity", "ConfirmDialog"),
    windows.map(WindowSnapshot::title),
)
assertTrue(windows.all { it.root.id.startsWith("${it.id}/root") })
```

In `LiveDeviceClientTest`, return an Agent frame with a window-local PNG and make
`exec-out screencap -p` return a larger PNG. Assert `capture()` returns the larger screenshot
and snapshot display dimensions.

- [ ] **Step 2: Run ADB tests and verify they fail**

```bash
cd desktop-viewer
./gradlew :adb-gateway:test
```

Expected: compilation fails because `parseWindows` and Agent-frame normalization are absent.

- [ ] **Step 3: Parse every matching ZIP entry**

Replace the largest-root selection with:

```kotlin
fun parseWindows(zipBytes: ByteArray, packageName: String): List<WindowSnapshot>
```

For each matching non-directory entry:

- read within `MAX_ENTRY_BYTES`;
- decode independently with `runCatching`;
- derive a stable sanitized ID from the entry token/name;
- namespace the decoded root IDs;
- retain the entry title after the package component;
- compute bounds from the decoded root.

Require at least one decoded matching window. Keep
`parse(zipBytes: ByteArray, packageName: String): ViewNode` as a compatibility function that
returns the default root only for callers that still need it during migration.

- [ ] **Step 4: Build fallback snapshots with all windows**

`AdbFallbackCapture.captureHierarchy()` returns `List<WindowSnapshot>`. Build the snapshot with
the first/largest Activity window as `root` and `defaultWindowId`. If visible-window parsing
fails completely, wrap the UI Automator root in one `window:uiautomator` record.

- [ ] **Step 5: Normalize Agent captures to an ADB full-display PNG**

In `ConnectedDeviceSession.capture()`:

1. read the Agent frame;
2. execute `AdbCommandFactory.captureScreenshot(serial)`;
3. when the command succeeds and PNG dimensions parse, decode the snapshot;
4. copy `display.widthPx` and `display.heightPx` from the full screenshot;
5. re-encode the snapshot and return the full screenshot;
6. fall back to the Agent frame if ADB screenshot capture fails.

Reuse `ProtocolCodec(supportedMajor = 1)` and `PngDimensions`; do not add a second PNG parser.

- [ ] **Step 6: Run ADB tests and verify they pass**

Run the Task 4 command again.

Expected: all ADB gateway tests pass.

- [ ] **Step 7: Commit synchronized fallback capture**

```bash
git add desktop-viewer/adb-gateway
git commit -m "Keep application windows in one screen coordinate space" \
  -m "Constraint: Dialog and popup roots require a full-display screenshot.
Rejected: Choosing only the largest visible-window entry | It discards valid application windows.
Confidence: high
Scope-risk: broad
Directive: Preserve the Agent frame when ADB screencap is unavailable.
Tested: :adb-gateway:test"
```

## Task 5: Expose Window Selection, Resource IDs, and Menu Order

**Files:**
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/InspectorPresenter.kt`
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/NativeViewerMenuBar.kt`
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt`
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewerStrings.kt`
- Modify: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/InspectorPresenterTest.kt`
- Modify: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/NativeViewerMenuBarTest.kt`
- Modify: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/LanguagePreferenceTest.kt`

- [ ] **Step 1: Write failing presenter and menu tests**

For a `ViewNode(resourceName = "com.example:id/title")`, assert:

```kotlin
assertEquals("id/title", model.rows.single().resourceLabel)
assertEquals("TextView", model.rows.single().label)
```

Assert `InspectorPresenter` exposes window choices and the active ID. In
`NativeViewerMenuBarTest`, read `NativeViewerMenuBar.kt` and assert source order:

```kotlin
val source = Files.readString(Path.of("src/main/kotlin/dev/agentperf/desktop/NativeViewerMenuBar.kt"))
assertTrue(source.indexOf("Menu(model.fileTitle)") < source.indexOf("Menu(model.actionsTitle)"))
assertTrue(source.indexOf("Menu(model.actionsTitle)") < source.indexOf("Menu(model.viewTitle)"))
```

- [ ] **Step 2: Run focused desktop tests and verify they fail**

```bash
cd desktop-viewer
./gradlew :desktop-app:test \
  --tests dev.agentperf.desktop.InspectorPresenterTest \
  --tests dev.agentperf.desktop.NativeViewerMenuBarTest \
  --tests dev.agentperf.desktop.LanguagePreferenceTest
```

Expected: compilation fails on resource/window/menu projection.

- [ ] **Step 3: Add resource and window presentation models**

Extend `TreeRowModel`:

```kotlin
val resourceLabel: String?,
```

Project only valid `ViewNode.resourceName` values:

```kotlin
resourceLabel = (this as? ViewNode)
    ?.resourceName
    ?.substringAfterLast('/')
    ?.takeIf(String::isNotBlank)
    ?.let { "id/$it" }
```

Add:

```kotlin
data class WindowChoiceModel(val id: String, val title: String)
```

and expose `windows` plus `selectedWindowId` from `InspectorScreenModel`.

- [ ] **Step 4: Render the resource ID before the class**

Update the hierarchy row text projection so its non-wrapping content is:

```kotlin
listOfNotNull(
    row.number.takeUnless { hideIndex },
    row.resourceLabel,
    row.label,
).joinToString("  ")
```

Keep horizontal scrolling and existing visibility colors.

- [ ] **Step 5: Add the header window dropdown**

Add a compact `WindowSelector` after the package name. It:

- shows `strings.noAvailableWindows` when empty;
- shows the active title;
- disables opening for zero or one item;
- invokes `onSelectWindow(windowId)` for a different choice.

Pass `store.selectWindow` from `DesktopViewerApp` and reset `HierarchyTreeState` only when the
window actually changes.

- [ ] **Step 6: Move File before Actions**

In `NativeViewerMenuBar`, emit:

```kotlin
Menu(model.fileTitle) {
    Item(
        text = model.importLabel,
        enabled = model.importEnabled,
        onClick = onImportArchive,
    )
    Item(
        text = model.exportLabel,
        enabled = model.exportEnabled,
        onClick = onExportArchive,
    )
}
Menu(model.actionsTitle) {
    model.actions.forEachIndexed { index, item ->
        if (index > 0 && model.actions[index - 1].group != item.group) Separator()
        val shortcut = item.shortcut?.let {
            KeyShortcut(key = it.key, ctrl = it.ctrl, meta = it.meta)
        }
        if (item.action.isToggleAction()) {
            CheckboxItem(
                text = item.label,
                checked = item.checked,
                enabled = item.enabled,
                shortcut = shortcut,
                onCheckedChange = { onAction(item.action) },
            )
        } else {
            Item(
                text = item.label,
                enabled = item.enabled,
                shortcut = shortcut,
                onClick = { onAction(item.action) },
            )
        }
    }
}
Menu(model.viewTitle) {
    model.viewItems.forEachIndexed { index, item ->
        if (index > 0 && model.viewItems[index - 1].group != item.group) Separator()
        CheckboxItem(
            text = item.label,
            checked = item.checked,
            onCheckedChange = { onViewOption(item.option) },
        )
    }
}
```

Do not change action groups or shortcuts.

- [ ] **Step 7: Add localized selector strings**

Add English and Simplified Chinese values for `No available windows` and any selector
accessibility label. Extend `LanguagePreferenceTest` to assert both translations.

- [ ] **Step 8: Run focused tests and verify they pass**

Run the Task 5 command again.

Expected: all selected desktop tests pass.

- [ ] **Step 9: Commit visible window controls**

```bash
git add desktop-viewer/desktop-app
git commit -m "Make window and resource identity visible during inspection" \
  -m "Constraint: Hierarchy rows remain one line and horizontally scrollable.
Rejected: Showing full package-qualified resource names | The requested format is id/name.
Confidence: high
Scope-risk: moderate
Directive: Keep File before Actions before View in the native menu bar.
Tested: focused presenter, menu, and localization tests"
```

## Task 6: Add Hit Testing, Hover, Click Cycling, and Tree Reveal

**Files:**
- Create: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/CanvasHitTester.kt`
- Create: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/CanvasPointerSelection.kt`
- Create: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/CanvasHitTesterTest.kt`
- Create: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/CanvasPointerSelectionTest.kt`
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/CanvasGeometry.kt`
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/HierarchyTreeState.kt`
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt`
- Modify: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/CanvasGeometryTest.kt`
- Modify: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/HierarchyTreeStateTest.kt`

- [ ] **Step 1: Write failing inverse-mapping and hit-order tests**

Add:

```kotlin
assertEquals(
    Offset(1700f, 500f),
    CanvasGeometry.unmapPoint(
        point = Offset(100f, 100f),
        source = CropRect(1500, 300, 800, 1600),
        destination = FloatRect(0f, 0f, 400f, 800f),
    ),
)
```

Construct overlapping children with differing `z`, elevation, child order, alpha, and parent
clipping. Assert `CanvasHitTester.hitPath(root, point)` returns IDs ordered from the topmost
deepest node through its ancestors.

- [ ] **Step 2: Write failing pointer-cycle and tree-reveal tests**

Verify:

```kotlin
val first = selection.click(point, listOf("leaf", "parent", "root"))
val second = selection.click(point, listOf("leaf", "parent", "root"))
assertEquals("leaf", first.selectedNodeId)
assertEquals("parent", second.selectedNodeId)
```

Move beyond the click tolerance and assert the next click returns `leaf`. Add
`HierarchyTreeState.reveal(nodeId, rows)` tests showing collapsed ancestors are removed while
unrelated collapsed branches remain collapsed.

- [ ] **Step 3: Run focused tests and verify they fail**

```bash
cd desktop-viewer
./gradlew :desktop-app:test \
  --tests dev.agentperf.desktop.CanvasGeometryTest \
  --tests dev.agentperf.desktop.CanvasHitTesterTest \
  --tests dev.agentperf.desktop.CanvasPointerSelectionTest \
  --tests dev.agentperf.desktop.HierarchyTreeStateTest
```

Expected: compilation fails on inverse mapping, hit testing, click cycling, and reveal.

- [ ] **Step 4: Implement inverse mapping**

Add:

```kotlin
fun unmapPoint(
    point: Offset,
    source: CropRect,
    destination: FloatRect,
): Offset? {
    if (point.x !in destination.left..destination.left + destination.width ||
        point.y !in destination.top..destination.top + destination.height
    ) return null
    return Offset(
        source.left + (point.x - destination.left) * source.width / destination.width,
        source.top + (point.y - destination.top) * source.height / destination.height,
    )
}
```

- [ ] **Step 5: Implement effective clipping and paint-order hit paths**

`CanvasHitTester.hitPath(root, point)` must:

- propagate parent visibility and alpha;
- intersect with root/source bounds;
- apply parent `clipChildren` and local `clipBounds`;
- sort children by `attributes.z ?: elevation ?: 0f`, then child index;
- traverse highest paint order first;
- return the deepest matching node followed by each of its matching ancestors through the root.

Do not filter on `clickable`.

- [ ] **Step 6: Implement hover and repeated-click state**

`CanvasPointerSelection` stores:

```kotlin
val hoveredNodeId: String?
val lastClickPoint: Offset?
val lastHitPath: List<String>
val clickIndex: Int
```

Use a four-device-pixel tolerance. `move` updates hover and resets the click cycle only after
movement beyond tolerance. `leave`, `windowChanged`, and `snapshotChanged` clear hover/cycle.

- [ ] **Step 7: Reveal collapsed ancestors without unnecessary scroll**

Implement `HierarchyTreeState.reveal` by reading row depths before the selected row and removing
every ancestor ID from `collapsedNodeIds`. Replace the current scroll target calculation with:

```kotlin
fun firstVisibleIndexForSelection(
    selectedIndex: Int,
    firstVisibleIndex: Int?,
    lastVisibleIndex: Int?,
): Int? {
    if (selectedIndex < 0) return null
    if (firstVisibleIndex == null || lastVisibleIndex == null) return selectedIndex
    if (selectedIndex in firstVisibleIndex..lastVisibleIndex) return null
    if (selectedIndex < firstVisibleIndex) return selectedIndex
    val visibleSpan = (lastVisibleIndex - firstVisibleIndex).coerceAtLeast(0)
    return (selectedIndex - visibleSpan).coerceAtLeast(0)
}
```

`HierarchyPane` calls `scrollToItem` with this returned first-visible index. A selected row above
the viewport appears at the top, a row below appears at the bottom, and a currently represented
row does not move.

- [ ] **Step 8: Wire pointer events into `PreviewPane`**

Pass `onHoverNode`, `onLeaveCanvas`, and `onSelectNode`. On `PointerEventType.Move`, inverse-map
the pointer and run hit testing. On `PointerEventType.Exit`, clear hover. On primary release,
advance the click cycle and select the returned ID.

Before selecting from canvas:

```kotlin
hierarchyTreeState = hierarchyTreeState.reveal(nodeId, model.rows)
selectNode(nodeId)
```

Draw hover after normal bounds and selected bounds last.

- [ ] **Step 9: Run focused tests and verify they pass**

Run the Task 6 command again.

Expected: all geometry, hit testing, cycling, and tree tests pass.

- [ ] **Step 10: Commit synchronized canvas navigation**

```bash
git add desktop-viewer/desktop-app
git commit -m "Let the canvas drive precise hierarchy selection" \
  -m "Constraint: Existing visible rows must not jump when selection changes.
Rejected: Selecting the smallest bounds only | Z order and clipping determine the visible target.
Confidence: high
Scope-risk: broad
Directive: Keep selected borders above hover borders and normal overlays.
Tested: focused canvas and hierarchy tests"
```

## Task 7: Add Persistent Canvas Border Color Settings

**Files:**
- Create: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/CanvasBorderColors.kt`
- Create: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/CanvasBorderColorsTest.kt`
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ThemeSettingsDialog.kt`
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt`
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewerStrings.kt`
- Modify: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/LanguagePreferenceTest.kt`

- [ ] **Step 1: Write failing color parsing and persistence tests**

Cover:

```kotlin
assertEquals(0xFF7DD3FC, CanvasArgb.parse("#7DD3FC")?.value)
assertEquals(0x807DD3FC, CanvasArgb.parse("#807DD3FC")?.value)
assertNull(CanvasArgb.parse("#GG0000"))
```

Use in-memory lambdas to verify three independent keys, invalid stored values falling back to
defaults, save/load round trips, and reset.

- [ ] **Step 2: Run focused color tests and verify they fail**

```bash
cd desktop-viewer
./gradlew :desktop-app:test --tests dev.agentperf.desktop.CanvasBorderColorsTest
```

Expected: compilation fails because the color value and store do not exist.

- [ ] **Step 3: Add validated ARGB values and persistence**

Define:

```kotlin
@JvmInline
value class CanvasArgb(val value: Long) {
    fun toComposeColor(): Color = Color(value.toULong())
    fun toHex(): String = "#%08X".format(value)

    companion object {
        fun parse(value: String): CanvasArgb? {
            val digits = value.trim().removePrefix("#")
            val argb = when (digits.length) {
                6 -> "FF$digits"
                8 -> digits
                else -> return null
            }
            if (!argb.matches(Regex("[0-9A-Fa-f]{8}"))) return null
            return CanvasArgb(argb.toLong(radix = 16))
        }
    }
}

data class CanvasBorderColors(
    val normal: CanvasArgb = CanvasArgb(0xFF7DD3FC),
    val hovered: CanvasArgb = CanvasArgb(0xFFF59E0B),
    val selected: CanvasArgb = CanvasArgb(0xFFEF4444),
)
```

`CanvasBorderColorStore.desktop()` uses keys `canvas.bounds.normal`,
`canvas.bounds.hovered`, and `canvas.bounds.selected` under the same package Preferences node.

- [ ] **Step 4: Add reusable settings controls**

Extend `SettingsDialog` with:

```kotlin
canvasBorderColors: CanvasBorderColors,
onCanvasBorderColorsChanged: (CanvasBorderColors) -> Unit,
```

Render three localized rows with:

- color swatch;
- a compact preset palette;
- an eight-digit ARGB text field;
- localized reset button.

Commit a text edit only when `CanvasArgb.parse` succeeds. Reset restores the corresponding
default value. Replace the current settings `Column` modifier with a bounded scrolling body:

```kotlin
modifier = Modifier
        .width(520.dp)
        .heightIn(max = 620.dp)
        .verticalScroll(rememberScrollState())
```

Immediately after the existing `LanguagePreferenceDropdown` call, insert:

```kotlin
Text(
    text = strings.canvasBorderColors,
    color = colors.secondaryText,
    fontSize = 12.sp,
    fontWeight = FontWeight.Bold,
)
CanvasColorSetting(
    label = strings.defaultViewBoundsColor,
    value = canvasBorderColors.normal,
    defaultValue = CanvasBorderColors().normal,
    onValueChanged = { onCanvasBorderColorsChanged(canvasBorderColors.copy(normal = it)) },
)
CanvasColorSetting(
    label = strings.hoveredViewBoundsColor,
    value = canvasBorderColors.hovered,
    defaultValue = CanvasBorderColors().hovered,
    onValueChanged = { onCanvasBorderColorsChanged(canvasBorderColors.copy(hovered = it)) },
)
CanvasColorSetting(
    label = strings.selectedViewBoundsColor,
    value = canvasBorderColors.selected,
    defaultValue = CanvasBorderColors().selected,
    onValueChanged = { onCanvasBorderColorsChanged(canvasBorderColors.copy(selected = it)) },
)
```

Define the row with:

```kotlin
@Composable
private fun CanvasColorSetting(
    label: String,
    value: CanvasArgb,
    defaultValue: CanvasArgb,
    onValueChanged: (CanvasArgb) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toHex()) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.width(150.dp), fontSize = 12.sp)
        Box(Modifier.size(22.dp).background(value.toComposeColor(), RoundedCornerShape(4.dp)))
        CanvasColorPreset.entries.forEach { preset ->
            Box(
                Modifier
                    .padding(start = 4.dp)
                    .size(18.dp)
                    .background(preset.argb.toComposeColor(), RoundedCornerShape(9.dp))
                    .clickable { onValueChanged(preset.argb) },
            )
        }
        BasicTextField(
            value = text,
            onValueChange = { updated ->
                text = updated
                CanvasArgb.parse(updated)?.let(onValueChanged)
            },
            singleLine = true,
            modifier = Modifier.padding(start = 8.dp).width(92.dp),
        )
        TextButton(onClick = { onValueChanged(defaultValue) }) {
            Text(LocalViewerStrings.current.reset)
        }
    }
}
```

Define `CanvasColorPreset` beside `CanvasBorderColors` so the settings row uses the same six
validated values:

```kotlin
enum class CanvasColorPreset(val argb: CanvasArgb) {
    SKY(CanvasArgb(0xFF7DD3FC)),
    AMBER(CanvasArgb(0xFFF59E0B)),
    RED(CanvasArgb(0xFFEF4444)),
    GREEN(CanvasArgb(0xFF22C55E)),
    PURPLE(CanvasArgb(0xFFA855F7)),
    WHITE(CanvasArgb(0xFFFFFFFF)),
}
```

- [ ] **Step 5: Wire colors into canvas rendering**

Load the store once in `DesktopViewerApp`, save every valid update, and pass the colors to
`PreviewPane`. Replace palette-bound normal and selected colors with:

```kotlin
canvasBorderColors.normal.toComposeColor()
canvasBorderColors.hovered.toComposeColor()
canvasBorderColors.selected.toComposeColor()
```

Retain the established stroke widths and layer ordering.

- [ ] **Step 6: Add localized settings strings**

Add and test English/Simplified Chinese strings for:

- Canvas border colors / 画布边框颜色
- Default view bounds / 默认视图边框
- Hovered view bounds / 鼠标悬停边框
- Selected view bounds / 点击选中边框
- Reset / 恢复默认
- Invalid color / 颜色格式无效

- [ ] **Step 7: Run focused settings tests and verify they pass**

```bash
cd desktop-viewer
./gradlew :desktop-app:test \
  --tests dev.agentperf.desktop.CanvasBorderColorsTest \
  --tests dev.agentperf.desktop.LanguagePreferenceTest
```

Expected: both test classes pass.

- [ ] **Step 8: Commit configurable border colors**

```bash
git add desktop-viewer/desktop-app
git commit -m "Let inspectors distinguish canvas states with chosen colors" \
  -m "Constraint: Colors must survive theme and process restarts.
Rejected: Theme-derived hover and selection colors | Users explicitly requested independent choices.
Confidence: high
Scope-risk: moderate
Directive: Validate ARGB input before updating preferences.
Tested: color parsing, persistence, and localization tests"
```

## Task 8: Archive Regression, Integrated Verification, and Final Commit

**Files:**
- Modify: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/CaptureArchiveServiceTest.kt`
- Modify: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/CanvasOverlayWiringTest.kt`
- Modify: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/HeaderMenuPlacementTest.kt`
- Modify: `docs/superpowers/plans/2026-07-07-multi-window-canvas-selection.md`

- [ ] **Step 1: Add a failing multi-window archive round-trip test**

Export a snapshot with two windows through `CaptureArchiveService`, import it, and assert:

```kotlin
assertEquals(snapshot.windows, imported.snapshot.windows)
assertEquals(snapshot.defaultWindowId, imported.snapshot.defaultWindowId)
assertArrayEquals(screenshot, imported.screenshotPng)
```

Keep the existing legacy archive tests unchanged.

- [ ] **Step 2: Update source-wiring regression tests**

Change `CanvasOverlayWiringTest` to assert normal, hover, then selected draw order and configured
color usage. Change `HeaderMenuPlacementTest` to assert the header includes `WindowSelector` and
that Actions/Files are not reintroduced beside auto-scan.

- [ ] **Step 3: Run the desktop test suite**

```bash
cd desktop-viewer
./gradlew :desktop-app:test
```

Expected: all desktop tests pass.

- [ ] **Step 4: Run the complete project verification**

```bash
cd desktop-viewer
./gradlew clean test
```

Expected: every module test task passes with zero failures.

- [ ] **Step 5: Run compilation and packaging verification**

```bash
cd desktop-viewer
./gradlew :shared-kernel:android-agent-view:assembleDebug \
  :samples:android-view-app:assembleDebug \
  :desktop-app:createDistributable
```

Expected: Agent and sample APKs compile and the current-host desktop distributable is created.

- [ ] **Step 6: Inspect the final diff and working tree**

```bash
git diff --check
git status --short
git diff --stat HEAD
```

Expected: no whitespace errors; only planned source/test/plan files are modified. The pre-existing
untracked `.superpowers/` directory remains untouched.

- [ ] **Step 7: Mark this plan complete and commit integration fixes**

Update every completed checkbox in this file, then:

```bash
git add desktop-viewer docs/superpowers/plans/2026-07-07-multi-window-canvas-selection.md
git commit -m "Complete synchronized multi-window canvas inspection" \
  -m "Constraint: Selection, analysis, archives, and canvas coordinates must remain window-consistent.
Rejected: Partial UI-only window switching | It would leave capture and archive data incomplete.
Confidence: high
Scope-risk: broad
Directive: Preserve legacy archive decoding and process-only window filtering.
Tested: clean test, Android debug assemblies, and current-host desktop distributable"
```

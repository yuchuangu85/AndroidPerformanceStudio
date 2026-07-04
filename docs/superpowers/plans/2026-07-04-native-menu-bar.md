# Native Menu Bar Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add localized operating-system Actions and Advanced menus and a native macOS Settings entry below About while preserving every existing in-window control.

**Architecture:** A shared pure presentation model determines action checked/enabled state for both header and native menus. `DesktopViewerApp` becomes a `WindowScope` composable that installs Compose Desktop `MenuBar`, while a testable Java Desktop adapter registers the macOS preferences callback and forwards it through a request counter to the existing settings dialog.

**Tech Stack:** Kotlin/JVM 17, Compose Desktop 1.11 Material 3, Java AWT Desktop PreferencesHandler, JUnit 5.

---

## File Structure

- Create `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewerActionUiState.kt`
  for shared checked/enabled decisions.
- Create `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ViewerActionUiStateTest.kt`
  to lock shared menu behavior.
- Create `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/NativeViewerMenuBar.kt`
  for Compose Desktop native Actions and Advanced menus.
- Create `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/NativeViewerMenuBarTest.kt`
  for native menu models and shortcut mapping.
- Create `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ApplicationSettingsMenuInstaller.kt`
  for Java Desktop preferences registration.
- Create `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ApplicationSettingsMenuInstallerTest.kt`
  for supported, unsupported, callback, and cleanup behavior.
- Modify `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/Main.kt`
  to install the application Settings handler and forward requests.
- Modify `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt`
  to expose `WindowScope`, observe settings requests, render `MenuBar`, and reuse
  shared action state in the header.

### Task 1: Share action checked and enabled state

**Files:**
- Create: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ViewerActionUiStateTest.kt`
- Create: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewerActionUiState.kt`
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt`

- [ ] **Step 1: Write failing presentation tests**

```kotlin
@Test
fun `toggle actions reflect scan and panel state`() {
    val visibility = PanelVisibility(
        showHierarchy = true,
        showFindings = false,
        showDetails = true,
    )

    assertEquals(
        ViewerActionUiState(enabled = true, checked = true),
        viewerActionUiState(
            ViewerAction.TOGGLE_AUTO_SCAN,
            selectedNodeId = null,
            autoScanEnabled = true,
            panelVisibility = visibility,
        ),
    )
    assertEquals(
        ViewerActionUiState(enabled = true, checked = false),
        viewerActionUiState(
            ViewerAction.TOGGLE_FINDINGS,
            selectedNodeId = null,
            autoScanEnabled = true,
            panelVisibility = visibility,
        ),
    )
}

@Test
fun `tree actions require a selected node`() {
    assertFalse(
        viewerActionUiState(
            ViewerAction.NEXT_NODE,
            selectedNodeId = null,
            autoScanEnabled = false,
            panelVisibility = PanelVisibility(),
        ).enabled,
    )
    assertTrue(
        viewerActionUiState(
            ViewerAction.NEXT_NODE,
            selectedNodeId = "root",
            autoScanEnabled = false,
            panelVisibility = PanelVisibility(),
        ).enabled,
    )
}
```

- [ ] **Step 2: Run tests and verify RED**

```bash
cd desktop-viewer
./gradlew :desktop-app:test --tests 'dev.agentperf.desktop.ViewerActionUiStateTest'
```

Expected: compilation fails because `ViewerActionUiState` and
`viewerActionUiState` do not exist.

- [ ] **Step 3: Implement the shared pure model**

```kotlin
internal data class ViewerActionUiState(
    val enabled: Boolean,
    val checked: Boolean,
)

internal fun viewerActionUiState(
    action: ViewerAction,
    selectedNodeId: String?,
    autoScanEnabled: Boolean,
    panelVisibility: PanelVisibility,
): ViewerActionUiState {
    val treeAction = action == ViewerAction.PREVIOUS_NODE ||
        action == ViewerAction.NEXT_NODE ||
        action == ViewerAction.TOGGLE_SELECTED_NODE
    val checked = when (action) {
        ViewerAction.TOGGLE_AUTO_SCAN -> autoScanEnabled
        ViewerAction.TOGGLE_HIERARCHY -> panelVisibility.showHierarchy
        ViewerAction.TOGGLE_FINDINGS -> panelVisibility.showFindings
        ViewerAction.TOGGLE_DETAILS -> panelVisibility.showDetails
        else -> false
    }
    return ViewerActionUiState(
        enabled = !treeAction || selectedNodeId != null,
        checked = checked,
    )
}
```

- [ ] **Step 4: Replace duplicate header calculations**

In `ViewerActionDropdown`, replace `isTreeAction` and `active` with:

```kotlin
val actionState = viewerActionUiState(
    action = item.action,
    selectedNodeId = state.selectedNodeId,
    autoScanEnabled = autoScanEnabled,
    panelVisibility = panelVisibility,
)
```

Use `actionState.checked` for the checkmark and `actionState.enabled` for the
menu item.

- [ ] **Step 5: Run tests and verify GREEN**

Run the Task 1 command and then:

```bash
./gradlew :desktop-app:test --tests 'dev.agentperf.desktop.ViewerActionMenuTest'
```

Expected: both test classes pass.

### Task 2: Define native Actions and Advanced menu models

**Files:**
- Create: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/NativeViewerMenuBarTest.kt`
- Create: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/NativeViewerMenuBar.kt`

- [ ] **Step 1: Write failing native menu model tests**

```kotlin
@Test
fun `native menu mirrors action ordering labels and groups`() {
    val strings = ViewerStrings.forLanguage(ViewerLanguage.SIMPLIFIED_CHINESE)
    val model = NativeViewerMenuModel(
        strings = strings,
        selectedNodeId = "root",
        autoScanEnabled = true,
        panelVisibility = PanelVisibility(),
        exportInProgress = false,
        isMacOs = true,
    )

    assertEquals("操作", model.actionsTitle)
    assertEquals(
        ViewerActionMenu.items(strings).map { it.action },
        model.actions.map { it.action },
    )
    assertEquals(ViewerAction.TOGGLE_AUTO_SCAN, model.actions.first().action)
    assertTrue(model.actions.first().checked)
    assertEquals("高级", model.advancedTitle)
    assertEquals("导出 Visible Window Views…", model.exportLabel)
    assertTrue(model.exportEnabled)
}

@Test
fun `native command shortcuts use the host primary modifier`() {
    assertEquals(
        NativeMenuShortcut(Key.R, ctrl = false, meta = true),
        viewerActionNativeShortcut(ViewerAction.TOGGLE_AUTO_SCAN, isMacOs = true),
    )
    assertEquals(
        NativeMenuShortcut(Key.R, ctrl = true, meta = false),
        viewerActionNativeShortcut(ViewerAction.TOGGLE_AUTO_SCAN, isMacOs = false),
    )
    assertNull(viewerActionNativeShortcut(ViewerAction.NEXT_NODE, isMacOs = true))
}
```

- [ ] **Step 2: Run tests and verify RED**

```bash
./gradlew :desktop-app:test --tests 'dev.agentperf.desktop.NativeViewerMenuBarTest'
```

Expected: compilation fails for the missing native menu model and shortcuts.

- [ ] **Step 3: Implement pure native menu models**

```kotlin
internal data class NativeMenuShortcut(
    val key: Key,
    val ctrl: Boolean,
    val meta: Boolean,
)

internal data class NativeActionMenuItem(
    val action: ViewerAction,
    val label: String,
    val group: Int,
    val enabled: Boolean,
    val checked: Boolean,
    val shortcut: NativeMenuShortcut?,
)

internal data class NativeViewerMenuModel(
    val actionsTitle: String,
    val actions: List<NativeActionMenuItem>,
    val advancedTitle: String,
    val exportLabel: String,
    val exportEnabled: Boolean,
)
```

Map `ViewerActionMenu.items(strings)` into native items using
`viewerActionUiState`. Native shortcuts are assigned only to the command
shortcuts already handled by `viewerCommandAction`: R, 1, 2, 3, and comma.
Arrow and Enter remain owned by hierarchy focus handling.

- [ ] **Step 4: Implement the Compose Desktop MenuBar**

Create:

```kotlin
@Composable
internal fun WindowScope.NativeViewerMenuBar(
    model: NativeViewerMenuModel,
    onAction: (ViewerAction) -> Unit,
    onExportVisibleWindowViews: () -> Unit,
)
```

Use `MenuBar`, `Menu`, `Item`, `CheckboxItem`, and `Separator`.

- Insert a separator whenever adjacent action groups differ.
- Use `CheckboxItem` for `TOGGLE_AUTO_SCAN`, `TOGGLE_HIERARCHY`,
  `TOGGLE_FINDINGS`, and `TOGGLE_DETAILS`.
- Use `Item` for other actions.
- Convert `NativeMenuShortcut` to `KeyShortcut`.
- Add Advanced with one export `Item`.

- [ ] **Step 5: Run tests and verify GREEN**

Run the Task 2 command.

Expected: native model and shortcut tests pass.

### Task 3: Register native macOS Settings below About

**Files:**
- Create: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/ApplicationSettingsMenuInstallerTest.kt`
- Create: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/ApplicationSettingsMenuInstaller.kt`

- [ ] **Step 1: Write failing registration tests**

```kotlin
@Test
fun `supported preferences action forwards settings request and unregisters`() {
    var handler: PreferencesHandler? = null
    var requests = 0
    val installer = ApplicationSettingsMenuInstaller(
        supported = { true },
        setHandler = { handler = it },
    )

    val registration = installer.install { requests += 1 }
    handler!!.handlePreferences(null)
    registration.close()

    assertEquals(1, requests)
    assertNull(handler)
}

@Test
fun `unsupported preferences action is a no op`() {
    var registrations = 0
    val installer = ApplicationSettingsMenuInstaller(
        supported = { false },
        setHandler = { registrations += 1 },
    )

    installer.install {}.close()

    assertEquals(0, registrations)
}
```

- [ ] **Step 2: Run tests and verify RED**

```bash
./gradlew :desktop-app:test --tests 'dev.agentperf.desktop.ApplicationSettingsMenuInstallerTest'
```

Expected: compilation fails because the installer does not exist.

- [ ] **Step 3: Implement the testable adapter**

```kotlin
internal class ApplicationSettingsMenuInstaller(
    private val supported: () -> Boolean,
    private val setHandler: (PreferencesHandler?) -> Unit,
) {
    fun install(onOpenSettings: () -> Unit): AutoCloseable {
        if (!supported()) return AutoCloseable {}
        return runCatching {
            setHandler(PreferencesHandler { onOpenSettings() })
            AutoCloseable { runCatching { setHandler(null) } }
        }.getOrElse { AutoCloseable {} }
    }

    companion object {
        fun desktop(): ApplicationSettingsMenuInstaller
    }
}
```

`desktop()` must return an unsupported installer when `Desktop.isDesktopSupported`
is false or `Desktop.getDesktop()` fails. Otherwise support is determined by
`Desktop.Action.APP_PREFERENCES`.

- [ ] **Step 4: Run tests and verify GREEN**

Run the Task 3 command.

Expected: all installer tests pass.

### Task 4: Wire the native menus to existing behavior

**Files:**
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/Main.kt`
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt`
- Create: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/SettingsRequestTest.kt`

- [ ] **Step 1: Write failing settings request test**

Define and test the pure request decision:

```kotlin
@Test
fun `positive native request opens the shared settings dialog`() {
    assertTrue(shouldOpenSettingsForRequest(1L))
    assertFalse(shouldOpenSettingsForRequest(0L))
}
```

- [ ] **Step 2: Run test and verify RED**

```bash
./gradlew :desktop-app:test --tests 'dev.agentperf.desktop.SettingsRequestTest'
```

Expected: compilation fails because `shouldOpenSettingsForRequest` does not
exist.

- [ ] **Step 3: Wire application preferences registration**

In `Main.kt`:

```kotlin
var settingsRequest by remember { mutableStateOf(0L) }
val settingsMenuInstaller = remember { ApplicationSettingsMenuInstaller.desktop() }
DisposableEffect(settingsMenuInstaller) {
    val registration = settingsMenuInstaller.install { settingsRequest += 1 }
    onDispose(registration::close)
}
```

Pass `settingsRequest` into `DesktopViewerApp`.

- [ ] **Step 4: Install MenuBar and observe settings requests**

Change the signature:

```kotlin
@Composable
fun WindowScope.DesktopViewerApp(settingsRequest: Long = 0L)
```

After `settingsVisible` is declared:

```kotlin
LaunchedEffect(settingsRequest) {
    if (shouldOpenSettingsForRequest(settingsRequest)) settingsVisible = true
}
```

Before the themed window content, install:

```kotlin
NativeViewerMenuBar(
    model = NativeViewerMenuModel(...),
    onAction = performAction,
    onExportVisibleWindowViews = exportVisibleWindowViews,
)
```

Keep `ViewerActionDropdown`, `AdvancedMenu`, and `SettingsButton` unchanged.

- [ ] **Step 5: Run desktop tests and verify GREEN**

```bash
./gradlew :desktop-app:test
```

Expected: all desktop tests pass.

### Task 5: Full verification and macOS smoke test

**Files:**
- No production changes expected.

- [ ] **Step 1: Run the complete suite**

```bash
./gradlew test --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`, 0 failed tasks.

- [ ] **Step 2: Launch the application**

```bash
./gradlew :desktop-app:run
```

Expected: the macOS menu bar contains the application menu, Actions, and
Advanced while the viewer header still contains 操作, 高级, and the gear.

- [ ] **Step 3: Verify native menu behavior**

Check:

- application menu order is About, Settings, separator;
- Settings opens the existing settings dialog;
- Actions toggles auto scan and panels once;
- Advanced opens the existing directory chooser;
- the header controls still perform the same actions.

- [ ] **Step 4: Verify repository state**

```bash
git diff --check
git status --short
```

Expected: only intentional source, tests, and documentation are tracked;
existing user-generated visible-window dump files remain untracked.

# Perfetto MacOS Workspace Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Perfetto Trace Analyzer's Material-card page with a compact Layout Inspector-style MacOS workspace while preserving all capture, diagnostics, and recent-session behavior.

**Architecture:** Introduce focused Perfetto presentation primitives for compact panels, fields, buttons, template rows, and status. Move ADB and device controls from `PerfettoCapturePage` into a 29dp workspace toolbar owned by `PerfettoWorkspace`, then compose template, configuration, recent-session, and diagnostics panels in an inspector-style layout.

**Tech Stack:** Kotlin 2.4, Compose Multiplatform Desktop 1.11.1, Material 3, kotlin.test, Gradle composite builds.

## Global Constraints

- Preserve capture, device discovery, trace opening, diagnostics, and recent-session behavior.
- Remove the `Perfetto Trace Analyzer` title.
- Place the home icon and ADB path in the same 29dp toolbar row.
- Use the existing application Material roles derived from the Layout Inspector palette.
- Use 1dp borders, 4dp radii, 11–12sp typography, and 22–24dp compact controls.
- Do not add dependencies or experimental Compose APIs.
- Do not extract a cross-profiler design system.

---

### Task 1: Lock the workspace structure

**Files:**
- Create: `desktop-viewer/perfetto-viewer/perfetto-app/src/test/kotlin/com/androidperformancestudio/perfetto/app/PerfettoWorkspaceLayoutTest.kt`
- Modify: `desktop-viewer/perfetto-viewer/perfetto-app/src/main/kotlin/com/androidperformancestudio/perfetto/app/PerfettoWorkspace.kt`

**Interfaces:**
- Consumes: Existing `PerfettoWorkspace`, `PerfettoCapturePage`, device discovery, sessions, and diagnostics callbacks.
- Produces: A workspace containing `PerfettoToolbar`, `PerfettoWorkspacePanel`, `RecentSessionsPanel`, and a lower `TraceDiagnosticsPanel`.

- [ ] **Step 1: Write the failing workspace structure test**

```kotlin
class PerfettoWorkspaceLayoutTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/perfetto/app/PerfettoWorkspace.kt"),
        )

    @Test
    fun `workspace uses compact inspector chrome without a page title`() {
        assertFalse(source.contains("Text(\"Perfetto Trace Analyzer\""))
        assertTrue(source.contains("private fun PerfettoToolbar("))
        assertTrue(source.contains(".height(29.dp)"))
        assertTrue(source.contains("PerfettoHomeButton("))
        assertTrue(source.contains("adbPath = adbPath"))
    }

    @Test
    fun `workspace composes template configuration sessions and diagnostics panels`() {
        assertTrue(source.contains("RecentSessionsPanel("))
        assertTrue(source.contains("TraceDiagnosticsPanel("))
        assertTrue(source.contains("PerfettoWorkspacePanel("))
        assertTrue(source.contains("Modifier.width(260.dp)"))
        assertTrue(source.contains("Modifier.width(320.dp)"))
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
cd desktop-viewer
./gradlew :perfetto-viewer:perfetto-app:test \
  --tests 'com.androidperformancestudio.perfetto.app.PerfettoWorkspaceLayoutTest'
```

Expected: FAIL because the title remains and the compact toolbar/panel functions do not exist.

- [ ] **Step 3: Add the minimal workspace shell**

In `PerfettoWorkspace.kt`:

- Replace the padded `Surface`/`Column` title region with a full-size background column.
- Add `PerfettoToolbar` with the exact signature:

```kotlin
@Composable
private fun PerfettoToolbar(
    onNavigateHome: (() -> Unit)?,
    adbPath: String,
    onAdbPathChange: (String) -> Unit,
    devices: List<PerfettoDevice>,
    selectedDeviceSerial: String?,
    onSelectDevice: (String) -> Unit,
    onRefreshDevices: () -> Unit,
)
```

- Keep `PerfettoHomeButton` first, followed by a separator, compact ADB field, device selector, refresh button, and status.
- Add a primary `Row` with a 260dp template panel, weighted configuration panel, and 320dp recent-sessions panel.
- Place diagnostics in a lower full-width panel only when `activeTraceFile != null`.
- Keep all existing callbacks and state transitions unchanged.

- [ ] **Step 4: Run the workspace test and verify GREEN**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit the workspace shell**

```bash
git add \
  desktop-viewer/perfetto-viewer/perfetto-app/src/main/kotlin/com/androidperformancestudio/perfetto/app/PerfettoWorkspace.kt \
  desktop-viewer/perfetto-viewer/perfetto-app/src/test/kotlin/com/androidperformancestudio/perfetto/app/PerfettoWorkspaceLayoutTest.kt
git commit -m "Give Perfetto an inspector-shaped desktop workspace" \
  -m "Constraint: Preserve capture and diagnostics behavior while replacing only page composition.
Confidence: high
Scope-risk: moderate
Tested: PerfettoWorkspaceLayoutTest."
```

### Task 2: Add compact Perfetto presentation primitives

**Files:**
- Create: `desktop-viewer/perfetto-viewer/perfetto-presentation/src/main/kotlin/com/androidperformancestudio/perfetto/presentation/PerfettoMacOsUi.kt`
- Create: `desktop-viewer/perfetto-viewer/perfetto-presentation/src/test/kotlin/com/androidperformancestudio/perfetto/presentation/PerfettoMacOsUiTest.kt`

**Interfaces:**
- Consumes: `MaterialTheme.colorScheme` and Compose primitives.
- Produces:
  - `PerfettoWorkspacePanel(title: String, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit)`
  - `PerfettoCompactTextField(value: String, onValueChange: (String) -> Unit, label: String, modifier: Modifier = Modifier)`
  - `PerfettoCompactButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, selected: Boolean = false)`
  - `PerfettoStatusDot(color: Color)`
  - `PerfettoPanelHeader(title: String)`

- [ ] **Step 1: Write the failing primitive style test**

```kotlin
class PerfettoMacOsUiTest {
    @Test
    fun `compact primitives use inspector dimensions and theme roles`() {
        val source =
            Files.readString(
                Path.of(
                    "src/main/kotlin/com/androidperformancestudio/perfetto/presentation/PerfettoMacOsUi.kt",
                ),
            )

        assertTrue(source.contains("RoundedCornerShape(4.dp)"))
        assertTrue(source.contains(".border(1.dp, MaterialTheme.colorScheme.outline"))
        assertTrue(source.contains(".height(24.dp)"))
        assertTrue(source.contains("fontSize = 11.sp"))
        assertTrue(source.contains("MaterialTheme.colorScheme.primaryContainer"))
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
cd desktop-viewer
./gradlew :perfetto-viewer:perfetto-presentation:test \
  --tests 'com.androidperformancestudio.perfetto.presentation.PerfettoMacOsUiTest'
```

Expected: FAIL because `PerfettoMacOsUi.kt` does not exist.

- [ ] **Step 3: Implement compact primitives**

Create `PerfettoMacOsUi.kt` using:

- `Surface` or `Column` with `MaterialTheme.colorScheme.surface`.
- A 1dp `outline` border and 4dp shape.
- `BasicTextField` inside a 24dp field with an 11sp label/value.
- Compact clickable text buttons with 24dp height.
- `primaryContainer` for selected controls.
- A 6dp circular status dot.

Do not introduce a new palette; consume existing theme roles.

- [ ] **Step 4: Run the primitive test and verify GREEN**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit presentation primitives**

```bash
git add \
  desktop-viewer/perfetto-viewer/perfetto-presentation/src/main/kotlin/com/androidperformancestudio/perfetto/presentation/PerfettoMacOsUi.kt \
  desktop-viewer/perfetto-viewer/perfetto-presentation/src/test/kotlin/com/androidperformancestudio/perfetto/presentation/PerfettoMacOsUiTest.kt
git commit -m "Keep Perfetto controls compact and theme-aware" \
  -m "Constraint: Match Layout Inspector dimensions without exporting a global design system.
Confidence: high
Scope-risk: narrow
Tested: PerfettoMacOsUiTest."
```

### Task 3: Convert capture content to inspector panels

**Files:**
- Modify: `desktop-viewer/perfetto-viewer/perfetto-presentation/src/main/kotlin/com/androidperformancestudio/perfetto/presentation/PerfettoCapturePage.kt`
- Create: `desktop-viewer/perfetto-viewer/perfetto-presentation/src/test/kotlin/com/androidperformancestudio/perfetto/presentation/PerfettoCapturePageLayoutTest.kt`

**Interfaces:**
- Consumes: Task 2 compact primitives.
- Produces:
  - `PerfettoTemplatePanel(...)`
  - `PerfettoConfigurationPanel(...)`
  - Existing `PerfettoCaptureConfig` callback behavior without ADB/device parameters in the rendered content.

- [ ] **Step 1: Write the failing capture layout test**

```kotlin
class PerfettoCapturePageLayoutTest {
    private val source =
        Files.readString(
            Path.of(
                "src/main/kotlin/com/androidperformancestudio/perfetto/presentation/PerfettoCapturePage.kt",
            ),
        )

    @Test
    fun `capture page no longer owns adb or device toolbar controls`() {
        assertFalse(source.contains("label = { Text(\"ADB Path\") }"))
        assertFalse(source.contains("Text(\"Device:\""))
        assertTrue(source.contains("PerfettoTemplatePanel("))
        assertTrue(source.contains("PerfettoConfigurationPanel("))
    }

    @Test
    fun `capture controls use compact primitives`() {
        assertTrue(source.contains("PerfettoCompactTextField("))
        assertTrue(source.contains("PerfettoCompactButton("))
        assertFalse(source.contains("Card("))
        assertFalse(source.contains("RadioButton("))
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
cd desktop-viewer
./gradlew :perfetto-viewer:perfetto-presentation:test \
  --tests 'com.androidperformancestudio.perfetto.presentation.PerfettoCapturePageLayoutTest'
```

Expected: FAIL because the capture page still contains ADB/device controls, cards, and radio buttons.

- [ ] **Step 3: Implement compact template and configuration panels**

- Remove `adbPath`, `onAdbPathChange`, `devices`, `onSelectDevice`, and `onRefreshDevices` rendering from `PerfettoCapturePage`.
- Preserve `selectedDeviceSerial` solely for capture validation and callback dispatch.
- Replace template radio rows with compact selected rows.
- Replace configuration fields and actions with Task 2 primitives.
- Preserve custom config validation, additional-category parsing, start/stop behavior, progress states, completed-trace opening, and failure messages.
- Keep the public callback type `onStartCapture: (PerfettoCaptureConfig, String) -> Unit`.

- [ ] **Step 4: Run presentation tests and verify GREEN**

Run:

```bash
cd desktop-viewer
./gradlew :perfetto-viewer:perfetto-presentation:test
```

Expected: PASS.

- [ ] **Step 5: Commit capture panels**

```bash
git add \
  desktop-viewer/perfetto-viewer/perfetto-presentation/src/main/kotlin/com/androidperformancestudio/perfetto/presentation/PerfettoCapturePage.kt \
  desktop-viewer/perfetto-viewer/perfetto-presentation/src/test/kotlin/com/androidperformancestudio/perfetto/presentation/PerfettoCapturePageLayoutTest.kt
git commit -m "Make Perfetto capture controls read like inspector panels" \
  -m "Constraint: Retain all capture validation and callback behavior.
Rejected: Material cards and radio buttons | They conflict with the compact desktop design.
Confidence: high
Scope-risk: moderate
Tested: Perfetto presentation tests."
```

### Task 4: Finish sessions, diagnostics, and visual verification

**Files:**
- Modify: `desktop-viewer/perfetto-viewer/perfetto-app/src/main/kotlin/com/androidperformancestudio/perfetto/app/PerfettoWorkspace.kt`
- Modify: `desktop-viewer/perfetto-viewer/perfetto-app/src/test/kotlin/com/androidperformancestudio/perfetto/app/PerfettoWorkspaceLayoutTest.kt`

**Interfaces:**
- Consumes: Task 2 compact primitives and the Task 3 capture panels.
- Produces: Complete MacOS-styled recent-session and diagnostics regions.

- [ ] **Step 1: Extend the failing workspace test**

Add assertions:

```kotlin
assertTrue(source.contains("private fun RecentSessionsPanel("))
assertTrue(source.contains("PerfettoCompactButton(label = \"Open\""))
assertTrue(source.contains("PerfettoCompactButton(label = \"Delete\""))
assertTrue(source.contains("private fun InitialTraceNotice("))
assertFalse(source.contains("Card("))
assertFalse(source.contains("OutlinedButton("))
```

- [ ] **Step 2: Run the workspace test and verify RED**

Run the Task 1 test command. Expected: FAIL while old cards/buttons remain.

- [ ] **Step 3: Complete compact styling**

- Render recent sessions as compact rows inside the right panel.
- Render the initial trace notice as a bordered inline strip.
- Wrap diagnostics in the lower panel and keep existing query behavior.
- Replace remaining workspace `Card` and `OutlinedButton` usage with compact primitives.
- Preserve Open, Delete, export, and diagnostics callbacks.

- [ ] **Step 4: Run all automated checks**

```bash
cd desktop-viewer
./gradlew :perfetto-viewer:checkAll :desktop-app:check
git diff --check
```

Expected: BUILD SUCCESSFUL and no whitespace errors.

- [ ] **Step 5: Perform visual smoke verification**

Launch the desktop application, open Perfetto, and verify:

- No `Perfetto Trace Analyzer` title.
- Home icon and ADB path share one 29dp row.
- Template, configuration, and sessions appear as three compact panels.
- Diagnostics spans the lower workspace when a trace is active.
- Light and dark modes both use readable theme roles.
- Start Capture remains enabled only with a selected online device.

- [ ] **Step 6: Commit completion**

```bash
git add \
  desktop-viewer/perfetto-viewer/perfetto-app/src/main/kotlin/com/androidperformancestudio/perfetto/app/PerfettoWorkspace.kt \
  desktop-viewer/perfetto-viewer/perfetto-app/src/test/kotlin/com/androidperformancestudio/perfetto/app/PerfettoWorkspaceLayoutTest.kt
git commit -m "Finish the complete Perfetto MacOS workspace" \
  -m "Constraint: Style every visible Perfetto region while preserving behavior.
Confidence: high
Scope-risk: moderate
Tested: Perfetto checkAll, desktop-app check, and visual smoke."
```

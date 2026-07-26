# Profiler MacOS Workspaces Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Memory, Frame, Startup, Battery, Network, GPU Inspector, and Benchmark Regression the same compact MacOS workspace chrome as Layout Inspector without changing profiler behavior.

**Architecture:** Extend the existing shared `desktop-ui` module with stateless toolbar controls, then replace only the workspace-level Material controls in each profiler application module. Domain screens, controllers, file dialogs, capture state, import/export behavior, and cross-tool navigation remain owned by their current modules.

**Tech Stack:** Kotlin 2.4, Compose Desktop, Material 3 theme roles, kotlin-test, Gradle 9.4.1

## Global Constraints

- Primary workspace toolbar height is exactly 32dp.
- Secondary configuration toolbar height is exactly 28dp.
- Compact buttons, selectors, and text fields are 24dp high.
- Controls use 4dp corner radii, 1dp outline borders, and 10–11sp text.
- Use existing Material theme roles; do not introduce a palette, theme, dependency, or experimental API.
- Preserve capture, import, export, analysis, file-dialog, and navigation behavior.
- Do not redesign profiler charts, tables, timelines, result panes, or controller APIs.
- Keep changes small and module-local after the shared UI primitive task.

---

## File Structure

### Shared UI

- Create `desktop-viewer/simpleperf-viewer/desktop-ui/src/main/kotlin/com/androidperformancestudio/ui/ProfilerMacOsControls.kt`
  - Owns toolbar, button, selector, text field, and status composables.
- Create `desktop-viewer/simpleperf-viewer/desktop-ui/src/test/kotlin/com/androidperformancestudio/ui/ProfilerMacOsControlsSourceTest.kt`
  - Locks dimensions and theme-role usage.
- Keep `ProfilerHomeButton.kt` focused on home navigation.

### Workspace application files

- Modify `desktop-viewer/memory-profiler/memory-app/src/main/kotlin/com/androidperformancestudio/memory/app/MemoryProfilerWorkspace.kt`
- Modify `desktop-viewer/frame-profiler/frame-app/src/main/kotlin/com/androidperformancestudio/frame/app/FrameProfilerWorkspace.kt`
- Modify `desktop-viewer/startup-profiler/startup-app/src/main/kotlin/com/androidperformancestudio/startup/app/StartupProfilerWorkspace.kt`
- Modify `desktop-viewer/battery-profiler/battery-app/src/main/kotlin/com/androidperformancestudio/battery/app/BatteryProfilerWorkspace.kt`
- Modify `desktop-viewer/network-profiler/network-app/src/main/kotlin/com/androidperformancestudio/network/app/NetworkProfilerWorkspace.kt`
- Modify `desktop-viewer/gpu-inspector-integration/gpu-integration-app/src/main/kotlin/com/androidperformancestudio/gpu/app/GpuIntegrationWorkspace.kt`
- Modify `desktop-viewer/benchmark-regression/benchmark-app/src/main/kotlin/com/androidperformancestudio/benchmark/app/BenchmarkRegressionWorkspace.kt`

Each workspace file retains controller/state ownership and composes shared chrome around its existing `*Screen` composable.

### Workspace layout tests

- Modify `desktop-viewer/memory-profiler/memory-app/src/test/kotlin/com/androidperformancestudio/memory/app/MemoryProfilerWorkspaceSourceTest.kt`
- Create matching `*WorkspaceSourceTest.kt` files in the app-module test packages for Frame, Startup, Battery, Network, GPU, and Benchmark.

---

### Task 1: Shared MacOS Toolbar Controls

**Files:**
- Create: `desktop-viewer/simpleperf-viewer/desktop-ui/src/main/kotlin/com/androidperformancestudio/ui/ProfilerMacOsControls.kt`
- Create: `desktop-viewer/simpleperf-viewer/desktop-ui/src/test/kotlin/com/androidperformancestudio/ui/ProfilerMacOsControlsSourceTest.kt`

**Interfaces:**
- Consumes: Compose `Modifier`, Material theme roles, `List<Pair<String, String>>`.
- Produces:
  - `public const val PROFILER_PRIMARY_TOOLBAR_HEIGHT_DP: Int = 32`
  - `public const val PROFILER_SECONDARY_TOOLBAR_HEIGHT_DP: Int = 28`
  - `@Composable public fun ProfilerMacOsToolbar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit)`
  - `@Composable public fun ProfilerMacOsSecondaryToolbar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit)`
  - `@Composable public fun ProfilerCompactButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, selected: Boolean = false)`
  - `@Composable public fun ProfilerCompactSelector(label: String, selectedLabel: String?, options: List<Pair<String, String>>, enabled: Boolean = true, modifier: Modifier = Modifier, onSelected: (String) -> Unit)`
  - `@Composable public fun ProfilerCompactTextField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, placeholder: String = "")`
  - `@Composable public fun ProfilerToolbarStatus(message: String?, error: String?, modifier: Modifier = Modifier)`

- [ ] **Step 1: Write the failing shared control source test**

```kotlin
package com.androidperformancestudio.ui

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertTrue

class ProfilerMacOsControlsSourceTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/ui/ProfilerMacOsControls.kt"),
        )

    @Test
    fun `shared controls lock the approved compact dimensions`() {
        assertTrue(source.contains("PROFILER_PRIMARY_TOOLBAR_HEIGHT_DP: Int = 32"))
        assertTrue(source.contains("PROFILER_SECONDARY_TOOLBAR_HEIGHT_DP: Int = 28"))
        assertTrue(source.contains(".height(24.dp)"))
        assertTrue(source.contains("RoundedCornerShape(4.dp)"))
        assertTrue(source.contains(".border(1.dp, MaterialTheme.colorScheme.outline"))
        assertTrue(source.contains("fontSize = 11.sp"))
    }

    @Test
    fun `shared controls expose all workspace primitives`() {
        assertTrue(source.contains("fun ProfilerMacOsToolbar("))
        assertTrue(source.contains("fun ProfilerMacOsSecondaryToolbar("))
        assertTrue(source.contains("fun ProfilerCompactButton("))
        assertTrue(source.contains("fun ProfilerCompactSelector("))
        assertTrue(source.contains("fun ProfilerCompactTextField("))
        assertTrue(source.contains("fun ProfilerToolbarStatus("))
    }
}
```

- [ ] **Step 2: Run the test and verify RED**

Run:

```bash
cd desktop-viewer
./gradlew :simpleperf-viewer:desktop-ui:test \
  --tests 'com.androidperformancestudio.ui.ProfilerMacOsControlsSourceTest'
```

Expected: FAIL because `ProfilerMacOsControls.kt` does not exist.

- [ ] **Step 3: Implement the stateless shared primitives**

Create the declared interfaces. Use:

```kotlin
public const val PROFILER_PRIMARY_TOOLBAR_HEIGHT_DP: Int = 32
public const val PROFILER_SECONDARY_TOOLBAR_HEIGHT_DP: Int = 28

@Composable
public fun ProfilerMacOsToolbar(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        Row(
            modifier =
                modifier
                    .fillMaxWidth()
                    .height(PROFILER_PRIMARY_TOOLBAR_HEIGHT_DP.dp)
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(horizontal = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
```

Implement the secondary toolbar with `surfaceVariant` and 28dp height. Implement the selector with a compact button plus `DropdownMenu`; render option labels with `DropdownMenuItem`. Implement the text field with `BasicTextField`, separate 10sp label text, a 24dp field, and a solid primary cursor. Implement status with error color precedence and one-line ellipsis.

- [ ] **Step 4: Run shared UI tests**

Run:

```bash
./gradlew :simpleperf-viewer:desktop-ui:test
```

Expected: PASS.

- [ ] **Step 5: Commit the shared primitive task**

```bash
git add \
  desktop-viewer/simpleperf-viewer/desktop-ui/src/main/kotlin/com/androidperformancestudio/ui/ProfilerMacOsControls.kt \
  desktop-viewer/simpleperf-viewer/desktop-ui/src/test/kotlin/com/androidperformancestudio/ui/ProfilerMacOsControlsSourceTest.kt
git commit -m "Keep profiler chrome consistent through shared controls" \
  -m "Constraint: Primary toolbars are 32dp and secondary configuration rows are 28dp
Rejected: Module-local copies | would drift across seven workspaces
Confidence: high
Scope-risk: moderate
Tested: :simpleperf-viewer:desktop-ui:test"
```

---

### Task 2: Memory, GPU, and Benchmark Single-Row Workspaces

**Files:**
- Modify: `desktop-viewer/memory-profiler/memory-app/src/main/kotlin/com/androidperformancestudio/memory/app/MemoryProfilerWorkspace.kt`
- Modify: `desktop-viewer/memory-profiler/memory-app/src/test/kotlin/com/androidperformancestudio/memory/app/MemoryProfilerWorkspaceSourceTest.kt`
- Modify: `desktop-viewer/gpu-inspector-integration/gpu-integration-app/src/main/kotlin/com/androidperformancestudio/gpu/app/GpuIntegrationWorkspace.kt`
- Create: `desktop-viewer/gpu-inspector-integration/gpu-integration-app/src/test/kotlin/com/androidperformancestudio/gpu/app/GpuIntegrationWorkspaceSourceTest.kt`
- Modify: `desktop-viewer/benchmark-regression/benchmark-app/src/main/kotlin/com/androidperformancestudio/benchmark/app/BenchmarkRegressionWorkspace.kt`
- Create: `desktop-viewer/benchmark-regression/benchmark-app/src/test/kotlin/com/androidperformancestudio/benchmark/app/BenchmarkRegressionWorkspaceSourceTest.kt`

**Interfaces:**
- Consumes: Task 1 shared controls.
- Produces: three single-row 32dp toolbars with unchanged screen/action wiring.

- [ ] **Step 1: Add failing workspace source assertions**

For Memory, replace the old height assertion with:

```kotlin
assertTrue(workspace.contains("ProfilerMacOsToolbar"))
assertTrue(workspace.contains("ProfilerCompactButton"))
assertFalse(workspace.contains("import androidx.compose.material3.OutlinedButton"))
assertFalse(workspace.contains("import androidx.compose.material3.Button"))
assertTrue(workspace.contains("MemoryProfilerScreen("))
```

Create the GPU test with:

```kotlin
package com.androidperformancestudio.gpu.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GpuIntegrationWorkspaceSourceTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/gpu/app/GpuIntegrationWorkspace.kt"),
        )

    @Test
    fun `workspace uses shared compact chrome without changing artifact screen`() {
        assertTrue(source.contains("ProfilerMacOsToolbar"))
        assertTrue(source.contains("ProfilerCompactButton"))
        assertTrue(source.contains("GpuIntegrationScreen("))
        assertFalse(source.contains("import androidx.compose.material3.OutlinedButton"))
        assertFalse(source.contains("import androidx.compose.material3.Button"))
    }
}
```

Create the Benchmark test with:

```kotlin
package com.androidperformancestudio.benchmark.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BenchmarkRegressionWorkspaceSourceTest {
    private val source =
        Files.readString(
            Path.of(
                "src/main/kotlin/com/androidperformancestudio/benchmark/app/BenchmarkRegressionWorkspace.kt",
            ),
        )

    @Test
    fun `workspace uses shared compact chrome without changing comparison screen`() {
        assertTrue(source.contains("ProfilerMacOsToolbar"))
        assertTrue(source.contains("ProfilerCompactButton"))
        assertTrue(source.contains("BenchmarkRegressionScreen("))
        assertFalse(source.contains("import androidx.compose.material3.OutlinedButton"))
        assertFalse(source.contains("import androidx.compose.material3.Button"))
    }
}
```

- [ ] **Step 2: Run the three source tests and verify RED**

```bash
./gradlew \
  :memory-profiler:memory-app:test \
  :gpu-inspector-integration:gpu-integration-app:test \
  :benchmark-regression:benchmark-app:test
```

Expected: new layout assertions fail against raw Material buttons.

- [ ] **Step 3: Replace the Memory toolbar**

Replace the manual `Row` and toolbar constants with `ProfilerMacOsToolbar`. Keep the same callbacks and enabled expressions. Render refresh and exports with `ProfilerCompactButton`. Keep `MemoryProfilerScreen`, dialog ownership, and controller calls unchanged. Add a 1dp `HorizontalDivider` immediately after the toolbar.

- [ ] **Step 4: Replace the GPU toolbar**

Use `ProfilerMacOsToolbar` with:

1. `ProfilerHomeButton`
2. refresh AGI
3. configure AGI
4. launch AGI
5. import artifact
6. `Spacer(Modifier.weight(1f))`
7. `ProfilerToolbarStatus(state.message, state.error)`

Keep `persist`, `open`, locator calls, artifact imports, and `GpuIntegrationScreen` unchanged.

- [ ] **Step 5: Replace the Benchmark toolbar**

Use `ProfilerMacOsToolbar` for current import, baseline import, report export, and Perfetto trace navigation. Add a trailing shared status using `state.message` and `state.error`. Keep parser, analyzer, database, export, threshold, and `BenchmarkRegressionScreen` behavior unchanged.

- [ ] **Step 6: Run affected tests**

```bash
./gradlew \
  :memory-profiler:memory-app:test \
  :gpu-inspector-integration:gpu-integration-app:test \
  :benchmark-regression:benchmark-app:test
```

Expected: PASS.

- [ ] **Step 7: Commit the single-row workspaces**

```bash
git add \
  desktop-viewer/memory-profiler/memory-app \
  desktop-viewer/gpu-inspector-integration/gpu-integration-app \
  desktop-viewer/benchmark-regression/benchmark-app
git commit -m "Reduce profiler chrome to one consistent desktop row" \
  -m "Constraint: Domain screens and controller behavior remain unchanged
Confidence: high
Scope-risk: moderate
Tested: memory-app, gpu-integration-app, and benchmark-app tests"
```

---

### Task 3: Frame and Network Interactive Toolbars

**Files:**
- Modify: `desktop-viewer/frame-profiler/frame-app/src/main/kotlin/com/androidperformancestudio/frame/app/FrameProfilerWorkspace.kt`
- Create: `desktop-viewer/frame-profiler/frame-app/src/test/kotlin/com/androidperformancestudio/frame/app/FrameProfilerWorkspaceSourceTest.kt`
- Modify: `desktop-viewer/network-profiler/network-app/src/main/kotlin/com/androidperformancestudio/network/app/NetworkProfilerWorkspace.kt`
- Create: `desktop-viewer/network-profiler/network-app/src/test/kotlin/com/androidperformancestudio/network/app/NetworkProfilerWorkspaceSourceTest.kt`

**Interfaces:**
- Consumes: shared button, selector, text field, toolbar, and status composables.
- Produces: compact target/capture toolbars with unchanged async behavior.

- [ ] **Step 1: Write failing Frame and Network source tests**

Frame assertions:

```kotlin
assertTrue(source.contains("ProfilerMacOsToolbar"))
assertTrue(source.contains("ProfilerCompactSelector"))
assertTrue(source.contains("ProfilerToolbarStatus"))
assertFalse(source.contains("private fun TargetSelector("))
assertFalse(source.contains("import androidx.compose.material3.OutlinedButton"))
assertFalse(source.contains("import androidx.compose.material3.Button"))
assertTrue(source.contains("FrameProfilerScreen("))
```

Network assertions:

```kotlin
assertTrue(source.contains("ProfilerMacOsToolbar"))
assertTrue(source.contains("ProfilerCompactTextField"))
assertTrue(source.contains("ProfilerToolbarStatus"))
assertFalse(source.contains("import androidx.compose.material3.OutlinedTextField"))
assertFalse(source.contains("import androidx.compose.material3.OutlinedButton"))
assertFalse(source.contains("import androidx.compose.material3.Button"))
assertTrue(source.contains("NetworkProfilerScreen("))
```

- [ ] **Step 2: Run tests and verify RED**

```bash
./gradlew \
  :frame-profiler:frame-app:test \
  :network-profiler:network-app:test
```

Expected: FAIL on shared-control assertions.

- [ ] **Step 3: Refactor Frame workspace controls**

Replace both local `TargetSelector` usages with `ProfilerCompactSelector`; delete the local selector composable and its dropdown imports. Use shared buttons for refresh, capture, import, and exports. Move `state.operationMessage` to `ProfilerToolbarStatus(message = state.operationMessage, error = state.errorMessage)`. Preserve stop-on-home, capture polling, file dialogs, export callbacks, and Layout Inspector handoff.

- [ ] **Step 4: Refactor Network workspace controls**

Use `ProfilerCompactTextField` for device serial and package with widths 180dp and 240dp. Use shared buttons for HAR import, live capture, and four export formats. Add a weighted spacer followed by `ProfilerToolbarStatus(state.message, state.error)`. Do not change local capture job lifecycle, polling, redaction messages, storage, or exporter calls.

- [ ] **Step 5: Run Frame and Network tests**

```bash
./gradlew \
  :frame-profiler:frame-app:test \
  :network-profiler:network-app:test
```

Expected: PASS.

- [ ] **Step 6: Commit interactive toolbar changes**

```bash
git add \
  desktop-viewer/frame-profiler/frame-app \
  desktop-viewer/network-profiler/network-app
git commit -m "Make interactive profiler controls fit desktop chrome" \
  -m "Constraint: Capture lifecycle and file formats remain unchanged
Rejected: New controller APIs | workspace styling does not require domain changes
Confidence: high
Scope-risk: moderate
Tested: frame-app and network-app tests"
```

---

### Task 4: Startup Primary and Secondary Toolbars

**Files:**
- Modify: `desktop-viewer/startup-profiler/startup-app/src/main/kotlin/com/androidperformancestudio/startup/app/StartupProfilerWorkspace.kt`
- Create: `desktop-viewer/startup-profiler/startup-app/src/test/kotlin/com/androidperformancestudio/startup/app/StartupProfilerWorkspaceSourceTest.kt`

**Interfaces:**
- Consumes: Task 1 toolbar, secondary toolbar, selector, button, and status.
- Produces: a 32dp action row plus a 28dp experiment configuration row.

- [ ] **Step 1: Write the failing Startup source test**

```kotlin
assertTrue(source.contains("ProfilerMacOsToolbar"))
assertTrue(source.contains("ProfilerMacOsSecondaryToolbar"))
assertTrue(source.contains("ProfilerCompactSelector"))
assertTrue(source.contains("ProfilerToolbarStatus"))
assertFalse(source.contains("private fun Selector("))
assertFalse(source.contains("import androidx.compose.material3.OutlinedButton"))
assertFalse(source.contains("import androidx.compose.material3.Button"))
assertTrue(source.contains("StartupProfilerScreen("))
assertTrue(source.contains("controller.runExperiment()"))
```

- [ ] **Step 2: Run the test and verify RED**

```bash
./gradlew :startup-profiler:startup-app:test
```

Expected: FAIL on shared toolbar assertions.

- [ ] **Step 3: Implement the 32dp primary toolbar**

Compose home, device selector, app/activity selector, refresh, run/stop, CSV export, and JSON export in `ProfilerMacOsToolbar`. Add trailing operation/error status. Keep experiment cancellation on home and `experimentJob` ownership unchanged.

- [ ] **Step 4: Implement the 28dp configuration toolbar**

Move startup type, compilation mode, warm-ups, measured runs, and timeout into `ProfilerMacOsSecondaryToolbar`. Replace the local selector helper with `ProfilerCompactSelector`, render `state.operationMessage` and `state.errorMessage` with `ProfilerToolbarStatus`, and delete the local selector implementation/imports.

- [ ] **Step 5: Run Startup tests**

```bash
./gradlew :startup-profiler:startup-app:test
```

Expected: PASS.

- [ ] **Step 6: Commit Startup chrome**

```bash
git add desktop-viewer/startup-profiler/startup-app
git commit -m "Separate startup actions from experiment configuration" \
  -m "Constraint: Primary toolbar is 32dp and configuration toolbar is 28dp
Confidence: high
Scope-risk: narrow
Tested: :startup-profiler:startup-app:test"
```

---

### Task 5: Battery Multi-Row Compact Chrome

**Files:**
- Modify: `desktop-viewer/battery-profiler/battery-app/src/main/kotlin/com/androidperformancestudio/battery/app/BatteryProfilerWorkspace.kt`
- Create: `desktop-viewer/battery-profiler/battery-app/src/test/kotlin/com/androidperformancestudio/battery/app/BatteryProfilerWorkspaceSourceTest.kt`

**Interfaces:**
- Consumes: all shared controls.
- Produces: one 32dp primary toolbar and two 28dp compact rows without changing Battery Historian confirmations.

- [ ] **Step 1: Write the failing Battery source test**

```kotlin
assertTrue(source.contains("ProfilerMacOsToolbar"))
assertTrue(source.contains("ProfilerMacOsSecondaryToolbar"))
assertTrue(source.contains("ProfilerCompactSelector"))
assertTrue(source.contains("ProfilerCompactButton"))
assertTrue(source.contains("ProfilerToolbarStatus"))
assertFalse(source.contains("private fun Selector("))
val workspaceChrome = source.substringBefore("if (confirmReset)")
assertFalse(workspaceChrome.contains("\n                OutlinedButton("))
assertFalse(workspaceChrome.contains("\n                Button("))
assertTrue(source.contains("BatteryProfilerScreen("))
assertTrue(source.contains("confirmBugreport = true"))
```

- [ ] **Step 2: Run Battery tests and verify RED**

```bash
./gradlew :battery-profiler:battery-app:test
```

Expected: FAIL on new chrome assertions.

- [ ] **Step 3: Build the Battery primary toolbar**

Use the primary toolbar for home, device selector, app selector, refresh, and run/cancel/stop-and-analyze. Preserve the existing `when` branch that selects interactive, online, or automatic capture behavior.

- [ ] **Step 4: Build the Battery configuration toolbar**

Use a secondary toolbar for capture mode, duration, polling interval, measured runs, and launch-app checkbox/label. Keep every `controller.updateConfig` expression unchanged.

- [ ] **Step 5: Build the Battery action strip**

Use a second `ProfilerMacOsSecondaryToolbar` for JSON, CSV, raw evidence, Battery Historian, and Advanced Reset Stats. Use `ProfilerToolbarStatus(state.operationMessage, state.errorMessage)` for operation/error text. Preserve confirmation dialogs, historian launch, raw-evidence export, and enabled rules.

- [ ] **Step 6: Run Battery tests**

```bash
./gradlew :battery-profiler:battery-app:test
```

Expected: PASS.

- [ ] **Step 7: Commit Battery chrome**

```bash
git add desktop-viewer/battery-profiler/battery-app
git commit -m "Keep battery experiments readable with layered toolbars" \
  -m "Constraint: Historian and evidence flows retain their confirmations
Rejected: One overflowing toolbar | clips at the desktop minimum width
Confidence: high
Scope-risk: moderate
Tested: :battery-profiler:battery-app:test"
```

---

### Task 6: Aggregate Verification and Visual Smoke

**Files:**
- Modify only files required to fix failures caused by Tasks 1–5.
- Do not change domain behavior to silence style or source tests.

**Interfaces:**
- Consumes: all completed workspace changes.
- Produces: verified desktop application with seven consistent workspaces.

- [ ] **Step 1: Run all affected app-module tests**

```bash
./gradlew \
  :simpleperf-viewer:desktop-ui:test \
  :memory-profiler:memory-app:test \
  :frame-profiler:frame-app:test \
  :startup-profiler:startup-app:test \
  :battery-profiler:battery-app:test \
  :network-profiler:network-app:test \
  :gpu-inspector-integration:gpu-integration-app:test \
  :benchmark-regression:benchmark-app:test
```

Expected: PASS.

- [ ] **Step 2: Run formatting and static checks**

```bash
git diff --check
./gradlew \
  :memory-profiler:memory-app:check \
  :frame-profiler:frame-app:check \
  :startup-profiler:startup-app:check \
  :battery-profiler:battery-app:check \
  :network-profiler:network-app:check \
  :gpu-inspector-integration:gpu-integration-app:check \
  :benchmark-regression:benchmark-app:check
```

Expected: PASS.

- [ ] **Step 3: Run the unified desktop check**

```bash
./gradlew :desktop-app:check
```

Expected: PASS.

- [ ] **Step 4: Perform the application smoke test**

Run:

```bash
./gradlew :desktop-app:run
```

Open each of the seven workspaces and verify:

- primary toolbar is 32dp
- home icon is the first item
- controls are not clipped at 1100px width
- Startup and Battery show 28dp secondary rows
- capture/import/export enablement matches state
- content screens remain visible below chrome

Stop the application after inspection.

- [ ] **Step 5: Review final diff**

```bash
git diff --stat
git diff --check
git status --short
```

Confirm no unrelated files were reverted and no temporary preview changes remain.

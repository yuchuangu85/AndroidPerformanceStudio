# Expandable Simpleperf Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the nested Simpleperf settings sidebar with an expandable Simpleperf tree in the unified settings sidebar and render the selected section in the right pane.

**Architecture:** The Simpleperf presentation module will expose a content-only section renderer backed by the existing shared panel implementation. `UnifiedSettingsDialog` will own expansion and active-section UI state, render section children below the Simpleperf parent, and pass the selected section to the content-only renderer.

**Tech Stack:** Kotlin, Jetpack Compose Desktop, Material 3, JUnit 5/Kotlin Test, Gradle.

## Global Constraints

- Preserve current setting values, persistence, device context, callbacks, and user-guide behavior.
- Keep General and Layout Inspector settings unchanged.
- Add no dependencies and introduce no new navigation framework.
- Keep the current complete Simpleperf surface available for the standalone capture settings dialog.

---

### Task 1: Expose a Content-Only Simpleperf Section

**Files:**
- Modify: `desktop-viewer/simpleperf-viewer/presentation/src/main/kotlin/com/androidperformancestudio/presentation/CaptureConfigurationWorkspace.kt`
- Test: `desktop-viewer/simpleperf-viewer/presentation/src/test/kotlin/com/androidperformancestudio/presentation/CaptureConfigurationWorkspaceTest.kt`

**Interfaces:**
- Consumes: `CaptureSettingsSection`, `CaptureSetup`, `FlameTooltipMode`, `SimpleperfEngine`, and the existing setting callbacks.
- Produces: `SimpleperfSettingsSectionContent(section: CaptureSettingsSection, ..., modifier: Modifier = Modifier)`.

- [ ] **Step 1: Write the failing presentation source test**

Add this assertion block to `settings dialog includes capture flame graph engine and user guide sections`:

```kotlin
assertTrue(source.contains("fun SimpleperfSettingsSectionContent("))
assertTrue(source.contains("section = section"))
assertTrue(source.contains("modifier = modifier"))
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
cd desktop-viewer
./gradlew :simpleperf-viewer:presentation:test \
  --tests 'com.androidperformancestudio.presentation.CaptureConfigurationWorkspaceTest'
```

Expected: FAIL because `SimpleperfSettingsSectionContent` does not exist.

- [ ] **Step 3: Refactor the shared panel and add the public renderer**

Change `SettingsPanel` from a `RowScope` extension to a normal composable with
an explicit modifier:

```kotlin
@Composable
private fun SettingsPanel(
    section: CaptureSettingsSection,
    setup: CaptureSetup?,
    availableEvents: List<String>,
    style: MacOsDeviceTargetStyle,
    enabled: Boolean,
    onSelectTemplate: (SamplingTemplate) -> Unit,
    onUpdate: (SamplingParameters) -> Unit,
    onDismiss: (() -> Unit)?,
    flameTooltipMode: FlameTooltipMode,
    onFlameTooltipModeChange: (FlameTooltipMode) -> Unit,
    simpleperfEngine: SimpleperfEngine,
    onSimpleperfEngineChange: (SimpleperfEngine) -> Unit,
    onOpenUserGuide: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxHeight().padding(22.dp)) {
        // Keep the existing title, subtitle, scrolling content, and section switch.
    }
}
```

Pass `Modifier.weight(1f)` from both existing `Row` callers. Then add:

```kotlin
@Composable
@Suppress("FunctionName", "LongParameterList", "ktlint:standard:function-naming")
fun SimpleperfSettingsSectionContent(
    section: CaptureSettingsSection,
    setup: CaptureSetup?,
    availableEvents: List<String>,
    enabled: Boolean,
    darkTheme: Boolean,
    flameTooltipMode: FlameTooltipMode,
    onFlameTooltipModeChange: (FlameTooltipMode) -> Unit,
    simpleperfEngine: SimpleperfEngine,
    onSimpleperfEngineChange: (SimpleperfEngine) -> Unit,
    onSelectTemplate: (SamplingTemplate) -> Unit,
    onUpdate: (SamplingParameters) -> Unit,
    onOpenUserGuide: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    SettingsPanel(
        section = section,
        setup = setup,
        availableEvents = availableEvents,
        style = macOsDeviceTargetStyle(darkTheme),
        enabled = enabled,
        onSelectTemplate = onSelectTemplate,
        onUpdate = onUpdate,
        onDismiss = null,
        flameTooltipMode = flameTooltipMode,
        onFlameTooltipModeChange = onFlameTooltipModeChange,
        simpleperfEngine = simpleperfEngine,
        onSimpleperfEngineChange = onSimpleperfEngineChange,
        onOpenUserGuide = onOpenUserGuide,
        modifier = modifier,
    )
}
```

- [ ] **Step 4: Run the focused test and verify GREEN**

Run:

```bash
cd desktop-viewer
./gradlew :simpleperf-viewer:presentation:test \
  --tests 'com.androidperformancestudio.presentation.CaptureConfigurationWorkspaceTest'
```

Expected: PASS.

- [ ] **Step 5: Commit the shared renderer**

```bash
git add \
  desktop-viewer/simpleperf-viewer/presentation/src/main/kotlin/com/androidperformancestudio/presentation/CaptureConfigurationWorkspace.kt \
  desktop-viewer/simpleperf-viewer/presentation/src/test/kotlin/com/androidperformancestudio/presentation/CaptureConfigurationWorkspaceTest.kt
git commit
```

Use a Lore commit describing why the content-only renderer is needed and the
focused presentation test that passed.

---

### Task 2: Move Simpleperf Section Navigation Into the Unified Sidebar

**Files:**
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/UnifiedSettingsDialog.kt`
- Test: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/UnifiedSettingsMigrationTest.kt`

**Interfaces:**
- Consumes: `SimpleperfSettingsSectionContent` from Task 1 and `CaptureSettingsSection.entries`.
- Produces: expandable Simpleperf sidebar rows and active-section state owned by `UnifiedSettingsDialog`.

- [ ] **Step 1: Replace the migration assertions with failing tree-layout assertions**

In `simpleperf complete settings and live capture context are embedded`, require:

```kotlin
assertTrue(settings.contains("fun SimpleperfSettingsSectionContent("))
assertTrue(dialog.contains("SimpleperfSettingsSectionContent("))
assertTrue(dialog.contains("simpleperfExpanded"))
assertTrue(dialog.contains("CaptureSettingsSection.entries.forEach"))
assertTrue(dialog.contains("onSimpleperfSectionSelected"))
assertFalse(dialog.contains("SimpleperfSettingsContent("))
```

Add `assertFalse` to the JUnit imports.

- [ ] **Step 2: Run the desktop migration test and verify RED**

Run:

```bash
cd desktop-viewer
./gradlew :desktop-app:test \
  --tests 'dev.agentperf.desktop.UnifiedSettingsMigrationTest'
```

Expected: FAIL because the unified dialog still embeds `SimpleperfSettingsContent`.

- [ ] **Step 3: Add expansion and active-section state**

Inside `UnifiedSettingsDialog`, add:

```kotlin
var simpleperfExpanded by remember {
    mutableStateOf(selectedPage == SettingsPage.SIMPLEPERF)
}
var activeSimpleperfSection by remember {
    mutableStateOf(simpleperfInitialSection)
}

LaunchedEffect(selectedPage, simpleperfInitialSection) {
    if (selectedPage == SettingsPage.SIMPLEPERF) {
        simpleperfExpanded = true
        activeSimpleperfSection = simpleperfInitialSection
    }
}
```

Pass the state and callbacks into `SettingsSidebar`. Selecting a child must call
`onPageSelected(SettingsPage.SIMPLEPERF)` and update
`activeSimpleperfSection`; collapsing must not reset it.

- [ ] **Step 4: Render the expandable sidebar tree**

Keep General and Layout Inspector top-level rows. Render Simpleperf through a
dedicated parent row with a disclosure glyph:

```kotlin
SettingsSidebarRow(
    label = "Simpleperf",
    selected = selectedPage == SettingsPage.SIMPLEPERF,
    leadingText = if (simpleperfExpanded) "⌄" else "›",
    onClick = onSimpleperfExpandedChange,
)
```

When expanded, iterate through all child sections:

```kotlin
CaptureSettingsSection.entries.forEach { section ->
    SettingsSidebarRow(
        label = section.settingsLabel(chinese),
        selected =
            selectedPage == SettingsPage.SIMPLEPERF &&
                section == selectedSimpleperfSection,
        indent = 24.dp,
        onClick = { onSimpleperfSectionSelected(section) },
    )
}
```

Add localized labels for all six children. Extract the repeated selected-row
background, text color, and padding into `SettingsSidebarRow`.

- [ ] **Step 5: Replace the nested Simpleperf surface**

Import and call `SimpleperfSettingsSectionContent` in
`CompleteSimpleperfSettingsContent`, passing `activeSimpleperfSection`. Remove
the `initialSection` parameter from that helper and remove the
`SimpleperfSettingsContent` import. Keep the missing-context message above the
section content.

- [ ] **Step 6: Run focused tests and verify GREEN**

Run:

```bash
cd desktop-viewer
./gradlew :desktop-app:test \
  --tests 'dev.agentperf.desktop.UnifiedSettingsMigrationTest'
./gradlew :simpleperf-viewer:presentation:test \
  --tests 'com.androidperformancestudio.presentation.CaptureConfigurationWorkspaceTest'
```

Expected: both test classes PASS.

- [ ] **Step 7: Commit the unified navigation**

```bash
git add \
  desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/UnifiedSettingsDialog.kt \
  desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/UnifiedSettingsMigrationTest.kt
git commit
```

Use a Lore commit describing the removal of duplicate navigation, preserved
selection behavior, and focused tests.

---

### Task 3: Full Verification

**Files:**
- Verify only; no planned source changes.

**Interfaces:**
- Consumes: completed presentation renderer and unified sidebar tree.
- Produces: completion evidence for the desktop settings redesign.

- [ ] **Step 1: Run Simpleperf presentation checks**

```bash
cd desktop-viewer
./gradlew :simpleperf-viewer:presentation:check --rerun-tasks
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 2: Run desktop application checks**

```bash
cd desktop-viewer
./gradlew :desktop-app:check --rerun-tasks
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Validate the final diff**

```bash
cd ..
git diff --check
git status --short
git log -3 --oneline
```

Expected: no whitespace errors, only intentional plan-tracking edits if any,
and both implementation commits visible.

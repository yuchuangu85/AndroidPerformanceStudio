# Compact Capture Configuration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Place sampling templates and editable sampling parameters side by side at normal desktop widths while reducing capture-page spacing and title emphasis.

**Architecture:** Keep all behavior in the existing presentation module. Add a small pure responsive-layout policy used by `CapturePage`, extract the template section into a focused composable, and make the existing parameter card accept its parent-provided width. Remove only the duplicated read-only summary card.

**Tech Stack:** Kotlin 2.4, Compose Multiplatform Desktop, Material 3, kotlin.test, Gradle.

## Global Constraints

- At widths of at least 900 dp, use a 36/64 horizontal template/parameter layout.
- Below 900 dp, stack the same panels vertically.
- Keep capture status and actions above the scrolling configuration area.
- Use `titleLarge` for the page title and tighter 16 dp outer padding with 10–12 dp major gaps.
- Do not change sampling semantics, actions, localization, dependencies, colors, or icons.

---

### Task 1: Lock responsive layout and hierarchy with regression tests

**Files:**
- Modify: `presentation/src/test/kotlin/com/androidperformancestudio/presentation/SimpleperfCapturePageTest.kt`
- Modify: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/CapturePage.kt`

**Interfaces:**
- Produces: `internal enum class CaptureConfigurationLayout { HORIZONTAL, STACKED }`
- Produces: `internal fun captureConfigurationLayout(availableWidth: Dp): CaptureConfigurationLayout`

- [ ] **Step 1: Write failing tests**

Add assertions that 900 dp and 1200 dp choose `HORIZONTAL`, 899 dp chooses `STACKED`, the page source uses `titleLarge` and `BoxWithConstraints`, and `CaptureDetails` no longer exists.

```kotlin
@Test
fun `capture configuration uses horizontal panels at normal desktop widths`() {
    assertEquals(CaptureConfigurationLayout.HORIZONTAL, captureConfigurationLayout(900.dp))
    assertEquals(CaptureConfigurationLayout.HORIZONTAL, captureConfigurationLayout(1200.dp))
    assertEquals(CaptureConfigurationLayout.STACKED, captureConfigurationLayout(899.dp))
}
```

- [ ] **Step 2: Run the focused test and confirm RED**

Run:

```bash
./gradlew :presentation:test --tests com.androidperformancestudio.presentation.SimpleperfCapturePageTest
```

Expected: compilation fails because `CaptureConfigurationLayout` and `captureConfigurationLayout` do not exist, or hierarchy assertions fail against the old vertical implementation.

- [ ] **Step 3: Add the minimal layout policy**

```kotlin
internal enum class CaptureConfigurationLayout { HORIZONTAL, STACKED }

internal fun captureConfigurationLayout(availableWidth: Dp): CaptureConfigurationLayout =
    if (availableWidth >= 900.dp) CaptureConfigurationLayout.HORIZONTAL else CaptureConfigurationLayout.STACKED
```

- [ ] **Step 4: Run the focused test and confirm GREEN for the policy**

Run the same focused Gradle command. Width-policy assertions pass; source assertions may remain red until Task 2.

---

### Task 2: Implement compact responsive Compose layout

**Files:**
- Modify: `presentation/src/main/kotlin/com/androidperformancestudio/presentation/CapturePage.kt`
- Test: `presentation/src/test/kotlin/com/androidperformancestudio/presentation/SimpleperfCapturePageTest.kt`

**Interfaces:**
- Consumes: `captureConfigurationLayout(maxWidth)` from Task 1.
- Produces: `SamplingTemplatePanel(...)` and `ResponsiveCaptureConfiguration(...)` composables.

- [ ] **Step 1: Replace the vertical configuration stack**

Use `BoxWithConstraints(Modifier.fillMaxWidth())`. For `HORIZONTAL`, create a `Row` with 12 dp spacing and weights `0.36f` and `0.64f`; for `STACKED`, use a `Column` with 12 dp spacing. Render templates through `SamplingTemplatePanel`, and render `AdvancedCaptureParameters` as the right/second panel.

- [ ] **Step 2: Tighten hierarchy and spacing**

Apply these exact changes:

```kotlin
Modifier.fillMaxSize().padding(16.dp)
Arrangement.spacedBy(12.dp)
Text("Capture Configuration", style = MaterialTheme.typography.titleLarge)
```

Use `bodySmall` for the selected target, 12 dp capture-card padding, 10 dp template-panel spacing, 10 dp template-card padding, and 12 dp advanced-card padding with 8 dp internal spacing. Use `FlowRow` for event suggestions so the narrower parameter column cannot clip long chip rows.

- [ ] **Step 3: Remove duplicated read-only details**

Delete `CaptureDetails(setup)` and the `CaptureDetails` composable. The editable advanced card remains the only parameter surface.

- [ ] **Step 4: Run focused and static verification**

```bash
./gradlew :presentation:test --tests com.androidperformancestudio.presentation.SimpleperfCapturePageTest
./gradlew :presentation:ktlintCheck :presentation:detekt
```

Expected: all commands exit 0.

- [ ] **Step 5: Run full verification and visual review**

```bash
./gradlew checkAll --rerun-tasks
```

Capture normal-width and narrow-width screenshots. Confirm no clipping or horizontal scrolling, templates and parameters share a row at normal width, narrow layout stacks, the title is visually subordinate to the app header, and Get data remains visible.

- [ ] **Step 6: Commit**

Commit the implementation and tests using the repository Lore trailers, including exact static/full-test and visual evidence.

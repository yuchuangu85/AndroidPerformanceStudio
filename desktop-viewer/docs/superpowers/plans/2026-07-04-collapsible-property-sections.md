# Collapsible Property Sections Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give right-pane property sections full-height clickable headers with independent collapse and expansion.

**Architecture:** Keep expansion state local to `DetailsPane` so presentation data remains stateless. Add a small immutable state model for deterministic independent toggling, then render each section header at the shared panel-header height and conditionally render its rows.

**Tech Stack:** Kotlin, Compose Multiplatform Desktop, JUnit 5

---

### Task 1: Expansion State

**Files:**
- Create: `desktop-app/src/main/kotlin/dev/agentperf/desktop/DetailSectionExpansionState.kt`
- Create: `desktop-app/src/test/kotlin/dev/agentperf/desktop/DetailSectionExpansionStateTest.kt`

- [ ] **Step 1: Write the failing test**

```kotlin
class DetailSectionExpansionStateTest {
    @Test
    fun `sections start expanded and toggle independently`() {
        val initial = DetailSectionExpansionState()
        val risksCollapsed = initial.toggle("RENDER RISKS")

        assertFalse(risksCollapsed.isExpanded("RENDER RISKS"))
        assertTrue(risksCollapsed.isExpanded("LAYOUT"))
        assertTrue(risksCollapsed.toggle("RENDER RISKS").isExpanded("RENDER RISKS"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
./gradlew :desktop-app:test --tests dev.agentperf.desktop.DetailSectionExpansionStateTest
```

Expected: compilation fails because `DetailSectionExpansionState` does not exist.

- [ ] **Step 3: Write minimal implementation**

```kotlin
internal data class DetailSectionExpansionState(
    private val collapsedTitles: Set<String> = emptySet(),
) {
    fun isExpanded(title: String): Boolean = title !in collapsedTitles

    fun toggle(title: String): DetailSectionExpansionState =
        copy(
            collapsedTitles = if (title in collapsedTitles) {
                collapsedTitles - title
            } else {
                collapsedTitles + title
            },
        )
}
```

- [ ] **Step 4: Run test to verify it passes**

Run the same targeted Gradle test and expect `BUILD SUCCESSFUL`.

### Task 2: Collapsible Section UI

**Files:**
- Modify: `desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt`

- [ ] **Step 1: Retain expansion state in `DetailsPane`**

Use one remembered `DetailSectionExpansionState` and pass the current expanded
value plus a toggle callback into every `DetailSection`.

- [ ] **Step 2: Render a shared-height clickable header**

Replace the section `Text` header with a `Row` whose height is
`PanelHeaderLayout.HEIGHT_DP.dp`, whose full width is clickable, and whose
typography matches `PanelTitle`.

- [ ] **Step 3: Draw the state indicator and conditionally render rows**

Draw a chevron with `Canvas`; render `section.rows` only when the section is
expanded.

- [ ] **Step 4: Run desktop tests**

```bash
./gradlew :desktop-app:test
```

Expected: `BUILD SUCCESSFUL`.

### Task 3: Verification

**Files:**
- No additional files.

- [ ] **Step 1: Run full verification**

```bash
git diff --check
./gradlew test
```

Expected: no whitespace errors and `BUILD SUCCESSFUL`.

- [ ] **Step 2: Restart the desktop application**

Stop the existing `:desktop-app:run` process, run:

```bash
./gradlew :desktop-app:run
```

Expected: the process remains running with no exception output.

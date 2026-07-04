# Theme Settings Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a persistent System/Light/Dark theme setting and apply it across the complete desktop viewer.

**Architecture:** Keep theme preference and storage independent of Compose, resolve System mode from `isSystemInDarkTheme()`, and inject a semantic viewer palette through a composition local. A modal Material 3 settings panel changes and persists the preference immediately.

**Tech Stack:** Kotlin, Compose Multiplatform Desktop, Material 3, Java Preferences, JUnit 5

---

### Task 1: Theme Preference and Persistence

**Files:**
- Create: `desktop-app/src/test/kotlin/dev/agentperf/desktop/ThemePreferenceTest.kt`
- Create: `desktop-app/src/main/kotlin/dev/agentperf/desktop/ThemePreference.kt`

- [ ] Write failing tests showing that absent and invalid stored values resolve
  to `SYSTEM`, explicit values round-trip, and System/Light/Dark resolve against
  the current system darkness.
- [ ] Run the targeted test and confirm compilation fails because the model is
  absent.
- [ ] Implement:

```kotlin
internal enum class ThemePreference {
    SYSTEM, LIGHT, DARK;

    fun resolveDark(systemDark: Boolean): Boolean = when (this) {
        SYSTEM -> systemDark
        LIGHT -> false
        DARK -> true
    }
}
```

- [ ] Add a `ThemePreferenceStore` with injected string reader/writer functions
  and a desktop factory backed by Java Preferences.
- [ ] Run the targeted test and confirm success.

### Task 2: Semantic Viewer Theme

**Files:**
- Create: `desktop-app/src/main/kotlin/dev/agentperf/desktop/ViewerTheme.kt`

- [ ] Define semantic dark and light palettes for panels, canvas, borders,
  primary/secondary/muted text, selection, sections, controls, and preview.
- [ ] Define `LocalViewerColors`.
- [ ] Implement `ViewerTheme(darkTheme, content)` using Material 3
  `darkColorScheme`/`lightColorScheme` and the palette composition local.

### Task 3: Settings Interface

**Files:**
- Create: `desktop-app/src/main/kotlin/dev/agentperf/desktop/ThemeSettingsDialog.kt`
- Modify: `desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt`

- [ ] Add a drawn gear button at the far right of the top header.
- [ ] Add a Material 3 modal settings panel with a `主题` section and three
  single-select rows.
- [ ] Load the preference once, save every selection immediately, and close the
  panel without changing the chosen value.
- [ ] Resolve `SYSTEM` using `isSystemInDarkTheme()` and wrap the application in
  `ViewerTheme`.

### Task 4: Full Palette Migration

**Files:**
- Modify: `desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt`

- [ ] Replace fixed panel, canvas, border, accent, text, selection, section,
  control, and preview colors with semantic `LocalViewerColors` fields.
- [ ] Preserve severity/status colors as semantic constants that remain
  readable in both themes.
- [ ] Compile desktop tests to catch missing palette access.

### Task 5: Verification

**Files:**
- No additional files.

- [ ] Run `git diff --check`.
- [ ] Run `./gradlew :desktop-app:test`.
- [ ] Run `./gradlew test --rerun-tasks`.
- [ ] Restart `./gradlew :desktop-app:run` and confirm the process stays alive
  without exception output.

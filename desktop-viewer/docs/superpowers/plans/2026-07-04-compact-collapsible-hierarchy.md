# Compact Collapsible Hierarchy Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the hierarchy tree one-third shorter per row, independently collapsible, single-line, and horizontally scrollable.

**Architecture:** Add child metadata to presenter rows and keep expansion state local to the hierarchy pane. Filter the existing depth-first rows through an immutable state model, preserving stable node numbers and selection behavior, while all rendered rows use one shared horizontal `ScrollState`.

**Tech Stack:** Kotlin, Compose Multiplatform Desktop, JUnit 5

---

### Task 1: Lock Row Metadata and Expansion Behavior

**Files:**
- Create: `desktop-app/src/test/kotlin/dev/agentperf/desktop/HierarchyTreeStateTest.kt`
- Modify: `desktop-app/src/test/kotlin/dev/agentperf/desktop/InspectorPresenterTest.kt`

- [ ] Add a failing test asserting the 24dp baseline becomes 16dp.
- [ ] Add a failing test asserting rows are expanded by default and collapsing a
  node hides only its descendants.
- [ ] Add a failing presenter assertion for `hasChildren`.
- [ ] Run the targeted tests and confirm missing production APIs cause failure.

### Task 2: Implement Tree Metadata and State

**Files:**
- Create: `desktop-app/src/main/kotlin/dev/agentperf/desktop/HierarchyTreeState.kt`
- Modify: `desktop-app/src/main/kotlin/dev/agentperf/desktop/InspectorPresenter.kt`

- [ ] Define the 24dp baseline, 16dp compact height, and 10sp font constants.
- [ ] Implement immutable collapsed-ID toggling and depth-first row filtering.
- [ ] Add `hasChildren` to `TreeRowModel` and derive it from real children.
- [ ] Run the targeted tests and confirm success.

### Task 3: Render Compact Collapsible Rows

**Files:**
- Modify: `desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt`

- [ ] Remember hierarchy expansion and horizontal scroll state.
- [ ] Render only visible rows at the fixed 16dp height.
- [ ] Draw a right/down chevron for child-bearing nodes and make only its hit
  target toggle expansion.
- [ ] Keep row selection on the row body.
- [ ] Render labels with `maxLines = 1`, `softWrap = false`, and shared
  horizontal scrolling.

### Task 4: Verify and Run

**Files:**
- No additional files.

- [ ] Run `git diff --check`.
- [ ] Run `./gradlew :desktop-app:test`.
- [ ] Run `./gradlew test`.
- [ ] Restart `./gradlew :desktop-app:run` and confirm no runtime exception.

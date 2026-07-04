# Layout Inspector Properties Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expand capture and the right-side inspector so selected nodes expose comprehensive layout, drawing, interaction, and structural rendering-risk information.

**Architecture:** Add an optional, defaulted `ViewAttributes` payload to `ViewNode` so protocol-major compatibility is preserved. Populate it from both the in-process Android collector and encoded ADB fallback, then derive selected-subtree complexity and structural-overlap indicators in the desktop presenter. Render the result as compact, scrollable sections with risk rows visually prioritized over descriptive properties.

**Tech Stack:** Kotlin, kotlinx.serialization, Android View APIs (API 21+), Compose Multiplatform Desktop, JUnit 5.

---

### Task 1: Protocol-compatible View attributes

**Files:**
- Modify: `shared-kernel/protocol-model/src/main/kotlin/dev/agentperf/protocol/ProtocolModels.kt`
- Modify: `shared-kernel/protocol-model/src/test/kotlin/dev/agentperf/protocol/ProtocolCodecTest.kt`

- [ ] Add a failing round-trip test containing edge insets, transforms, clipping, drawable identities, and interaction flags.
- [ ] Run `./gradlew :shared-kernel:protocol-model:test --tests dev.agentperf.protocol.ProtocolCodecTest`; expect failure because `ViewAttributes` does not exist.
- [ ] Add serializable `EdgeInsets` and `ViewAttributes` types whose fields all have safe defaults, then add `attributes: ViewAttributes = ViewAttributes()` to `ViewNode`.
- [ ] Re-run the protocol-model test and verify old JSON without `attributes` still decodes.

### Task 2: Android Agent collection

**Files:**
- Modify: `shared-kernel/android-agent-view/src/main/kotlin/dev/agentperf/android/view/ViewTreeCollector.kt`
- Modify: `shared-kernel/android-agent-view/src/test/kotlin/dev/agentperf/android/view/LiveSnapshotFactoryTest.kt`

- [ ] Add a failing preservation test showing snapshot coordinate translation leaves the new attributes unchanged.
- [ ] Run `./gradlew :shared-kernel:android-agent-view:testDebugUnitTest`; expect the new assertion to fail before collection support exists.
- [ ] Populate visibility state, transforms, Z/elevation, padding/margins, scroll, clipping, drawable class/color, drawing flags, layer type, and interaction/accessibility values from public API 21-safe View APIs.
- [ ] Re-run Android View unit tests and compile lint models to catch API-level mistakes.

### Task 3: Encoded ADB fallback attributes

**Files:**
- Modify: `adb-gateway/src/main/kotlin/dev/agentperf/adb/VisibleWindowHierarchyParser.kt`
- Modify: `adb-gateway/src/test/kotlin/dev/agentperf/adb/VisibleWindowHierarchyParserTest.kt`

- [ ] Extend the encoded fixture and add failing assertions for padding, transforms, elevation, clipping, drawing, and interaction fields.
- [ ] Run `./gradlew :adb-gateway:test --tests dev.agentperf.adb.VisibleWindowHierarchyParserTest`; expect attribute assertions to fail.
- [ ] Map available `ViewHierarchyEncoder` property names into `ViewAttributes`; leave unsupported values at defaults.
- [ ] Re-run the parser and fallback integration tests.

### Task 4: Selected-node diagnostics model

**Files:**
- Modify: `desktop-app/src/main/kotlin/dev/agentperf/desktop/InspectorPresenter.kt`
- Modify: `desktop-app/src/test/kotlin/dev/agentperf/desktop/InspectorPresenterTest.kt`

- [ ] Add failing tests for section rows and selected-subtree metrics: depth, descendants, subtree depth, invisible descendants, and overlapping child pairs.
- [ ] Run `./gradlew :desktop-app:test --tests dev.agentperf.desktop.InspectorPresenterTest`; expect missing diagnostics model failures.
- [ ] Introduce compact section/row models, derive structural rendering risks without claiming measured GPU overdraw, and prioritize warnings before informational properties.
- [ ] Re-run presenter tests.

### Task 5: Compact Layout Inspector-style right pane

**Files:**
- Modify: `desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt`
- Modify: `desktop-app/src/test/kotlin/dev/agentperf/desktop/InspectorPresenterTest.kt`

- [ ] Render a scrollable summary plus `RENDER RISKS`, `LAYOUT`, `DRAWING`, and `INTERACTION` sections using the presenter rows.
- [ ] Use warning/error colors only for structural overlap, excessive depth/subtree size, invisibility, alpha, and software-layer indicators.
- [ ] Keep missing fields explicit as unavailable rather than inventing values.
- [ ] Run `./gradlew :desktop-app:test --rerun-tasks`.

### Task 6: Verification and relaunch

**Files:**
- Verify all files above.

- [ ] Run `git diff --check`.
- [ ] Run `./gradlew test`.
- [ ] Run `./gradlew :adb-gateway:check :desktop-app:test`.
- [ ] Restart `./gradlew :desktop-app:run`.
- [ ] Record any unrelated pre-existing Android lint failures separately from this change.

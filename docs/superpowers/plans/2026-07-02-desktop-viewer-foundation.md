# Desktop Viewer Foundation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver the first runnable vertical slice of Desktop Viewer: a versioned snapshot protocol, deterministic layout analysis, ADB discovery/forwarding logic, a three-pane Compose Desktop inspector, and a zero-code debug Android Agent sample.

**Architecture:** Keep transport-neutral domain logic in JVM modules under `shared-kernel`, Android-only collection and startup code in Android library modules, and host orchestration/UI in `desktop-viewer`. The Android Agent exposes a debuggable-only local abstract socket; the desktop side reaches it exclusively through ADB forwarding. JSON is the initial wire format, with explicit protocol versions and capability negotiation.

**Tech Stack:** JDK 17 toolchain, Gradle 9.4.1, Kotlin 2.3.21, Android Gradle Plugin 9.2.0, Compose Multiplatform 1.11.1, kotlinx.serialization, kotlinx.coroutines, JUnit 5, AndroidX Startup, Android API 21–37.

---

## Delivery boundary

This plan implements a usable foundation rather than the entire long-term roadmap. The slice ends when:

- the desktop application launches with the approved tree/canvas/details/timeline layout;
- protocol fixtures can be serialized, parsed, analyzed, and rendered;
- ADB device parsing and forwarding commands are covered by tests;
- a debug sample app auto-registers the Agent without application code changes;
- the full Gradle test/build pipeline is green.

Live socket request handling, PixelCopy frame capture, SQLite report indexing, Compose semantics extraction, signing/notarization, and production telemetry remain follow-up milestones.

## Task 1: Bootstrap the multi-project build

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `gradle.properties`
- Create: `gradle/libs.versions.toml`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `gradlew`
- Create: `gradlew.bat`
- Modify: `.gitignore`

- [ ] Configure plugin management, Maven repositories, project names, and all foundation modules.
- [ ] Pin the documented compatibility matrix and JDK 17 toolchain.
- [ ] Generate the Gradle 9.4.1 wrapper and verify `./gradlew --version`.
- [ ] Run `./gradlew projects` and confirm every declared module is discoverable.
- [ ] Commit with a Lore-formatted build-foundation decision record.

## Task 2: Define and test the versioned protocol

**Files:**
- Create: `shared-kernel/protocol-model/build.gradle.kts`
- Create: `shared-kernel/protocol-model/src/main/kotlin/dev/agentperf/protocol/ProtocolModels.kt`
- Create: `shared-kernel/protocol-model/src/main/kotlin/dev/agentperf/protocol/ProtocolCodec.kt`
- Create: `shared-kernel/protocol-model/src/test/kotlin/dev/agentperf/protocol/ProtocolCodecTest.kt`

- [ ] RED: write tests for snapshot JSON round-trip, unknown minor fields, and unsupported major versions.
- [ ] Run `./gradlew :shared-kernel:protocol-model:test`; confirm failure is caused by missing protocol classes.
- [ ] GREEN: add the minimum serializable version, capability, node, bounds, snapshot, and codec types.
- [ ] Re-run the focused tests and then `./gradlew test`.
- [ ] Commit the protocol contract and compatibility policy.

## Task 3: Implement deterministic layout analysis

**Files:**
- Create: `shared-kernel/analysis-engine/build.gradle.kts`
- Create: `shared-kernel/analysis-engine/src/main/kotlin/dev/agentperf/analysis/LayoutAnalyzer.kt`
- Create: `shared-kernel/analysis-engine/src/main/kotlin/dev/agentperf/analysis/AnalysisModels.kt`
- Create: `shared-kernel/analysis-engine/src/test/kotlin/dev/agentperf/analysis/LayoutAnalyzerTest.kt`

- [ ] RED: write focused tests for depth, node count, widest level, invisible-node, deep-hierarchy, and excessive-child findings.
- [ ] Run the module test and verify expected assertion/compilation failures.
- [ ] GREEN: implement one tree traversal and stable rule IDs/severities.
- [ ] Re-run focused and aggregate tests.
- [ ] Commit the deterministic analysis engine.

## Task 4: Build the ADB gateway boundary

**Files:**
- Create: `desktop-viewer/adb-gateway/build.gradle.kts`
- Create: `desktop-viewer/adb-gateway/src/main/kotlin/dev/agentperf/adb/AdbModels.kt`
- Create: `desktop-viewer/adb-gateway/src/main/kotlin/dev/agentperf/adb/AdbOutputParser.kt`
- Create: `desktop-viewer/adb-gateway/src/main/kotlin/dev/agentperf/adb/AdbCommandFactory.kt`
- Create: `desktop-viewer/adb-gateway/src/test/kotlin/dev/agentperf/adb/AdbGatewayTest.kt`

- [ ] RED: test authorized/offline/unauthorized device parsing, package-safe `run-as`, and localabstract forwarding commands.
- [ ] Run the focused test and verify it fails because the gateway API is absent.
- [ ] GREEN: implement pure parsers and command construction without starting processes.
- [ ] Re-run focused and aggregate tests.
- [ ] Commit the host transport boundary.

## Task 5: Implement application state and fixture flow

**Files:**
- Create: `shared-kernel/test-fixtures/build.gradle.kts`
- Create: `shared-kernel/test-fixtures/src/main/kotlin/dev/agentperf/fixtures/SampleSnapshots.kt`
- Create: `desktop-viewer/application/build.gradle.kts`
- Create: `desktop-viewer/application/src/main/kotlin/dev/agentperf/application/InspectorState.kt`
- Create: `desktop-viewer/application/src/main/kotlin/dev/agentperf/application/InspectorStore.kt`
- Create: `desktop-viewer/application/src/test/kotlin/dev/agentperf/application/InspectorStoreTest.kt`

- [ ] RED: test fixture load, node selection, missing-node handling, and analysis propagation.
- [ ] Run the focused test and observe the intended missing-API failure.
- [ ] GREEN: implement immutable UI state and a small synchronous store over the protocol/analyzer.
- [ ] Re-run focused and aggregate tests.
- [ ] Commit the application-layer vertical flow.

## Task 6: Create the Compose Desktop inspector

**Files:**
- Create: `desktop-viewer/desktop-app/build.gradle.kts`
- Create: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/Main.kt`
- Create: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt`
- Create: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/InspectorPresenter.kt`
- Create: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/InspectorPresenterTest.kt`

- [ ] RED: test presentation rows, selected-node details, severity summaries, and empty states.
- [ ] Run the focused test and confirm failure from the absent presenter.
- [ ] GREEN: implement the presenter and the approved three-pane UI plus bottom findings/timeline region.
- [ ] Run desktop tests and `./gradlew :desktop-viewer:desktop-app:createDistributable`.
- [ ] Commit the runnable desktop inspector.

## Task 7: Add the zero-code debug Android Agent sample

**Files:**
- Create: `shared-kernel/android-agent-core/build.gradle.kts`
- Create: `shared-kernel/android-agent-core/src/main/AndroidManifest.xml`
- Create: `shared-kernel/android-agent-core/src/main/kotlin/dev/agentperf/android/core/AgentRuntime.kt`
- Create: `shared-kernel/android-agent-view/build.gradle.kts`
- Create: `shared-kernel/android-agent-view/src/main/AndroidManifest.xml`
- Create: `shared-kernel/android-agent-view/src/main/kotlin/dev/agentperf/android/view/ViewTreeCollector.kt`
- Create: `shared-kernel/android-agent-startup/build.gradle.kts`
- Create: `shared-kernel/android-agent-startup/src/main/AndroidManifest.xml`
- Create: `shared-kernel/android-agent-startup/src/main/kotlin/dev/agentperf/android/startup/AgentInitializer.kt`
- Create: `shared-kernel/android-agent-startup/src/test/kotlin/dev/agentperf/android/startup/AgentInitializerTest.kt`
- Create: `samples/android-view-app/build.gradle.kts`
- Create: `samples/android-view-app/src/main/AndroidManifest.xml`
- Create: `samples/android-view-app/src/main/kotlin/dev/agentperf/sample/MainActivity.kt`

- [ ] RED: test that runtime startup is disabled for non-debuggable apps and idempotent for debuggable apps.
- [ ] Run the focused unit test and verify the missing runtime behavior.
- [ ] GREEN: add an injectable debug gate, idempotent runtime state, View hierarchy collector, Startup initializer metadata, and sample activity.
- [ ] Run Android unit tests and assemble the debug sample APK.
- [ ] Commit the Android integration skeleton.

## Task 8: Document, verify, and package the foundation

**Files:**
- Modify: `desktop-viewer/README.md`
- Create: `desktop-viewer/docs/DEVELOPMENT.md`
- Create: `desktop-viewer/docs/PROTOCOL.md`

- [ ] Document prerequisites, commands, module boundaries, debug-only security constraints, and known limitations.
- [ ] Run `./gradlew clean test assemble`.
- [ ] Run `./gradlew :desktop-viewer:desktop-app:createDistributable`.
- [ ] Inspect `git diff --check` and `git status --short` for accidental or unrelated files.
- [ ] Commit documentation and verification evidence with a Lore-formatted message.

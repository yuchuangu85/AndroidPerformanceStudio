# Desktop Viewer Development Guide

## Versions and toolchains

- Use JDK 21 for the desktop build and runtime; `desktop-app` targets Java 21 bytecode.
- Gradle is pinned in the [wrapper](../../gradle/wrapper/gradle-wrapper.properties).
- Kotlin, Compose, AGP and library versions are pinned in the [version catalog](../../gradle/libs.versions.toml).
- Android Agent and sample modules declare their own SDK requirements; the View sample uses minSdk 21 and compile/target SDK 37.
- Android modules use AGP's built-in Kotlin. Do not re-add `org.jetbrains.kotlin.android`.

Build files are authoritative; do not maintain a second version table in documentation.

## Module boundaries

```text
layout-inspector/
  shared-kernel/
    protocol-model/         transport-neutral snapshot contract and codec
    analysis-engine/        deterministic metrics and findings
    test-fixtures/          reusable snapshot fixtures
    android-agent-core/     debug gate and authenticated local socket
    android-agent-view/     traditional View hierarchy collection
    android-agent-frame/    API 24+ FrameMetrics collection and bounded batches
    android-agent-startup/  zero-code AndroidX Startup integration
  adb-gateway/              inspector capture and transport over shared ADB primitives
  application/              inspector state and selection use cases
  presentation/             embeddable Compose Desktop inspector workspace
  samples/
    android-view-app/       API 21+ zero-code Agent integration sample
simpleperf-viewer/          isolated Simpleperf CPU profiler build
platform-core/              Capture Artifact, host process, and ADB contracts
platform-perfetto/          pinned Trace Processor lifecycle and typed query boundary
frame-profiler/             isolated FrameTimeline/Jank profiler build
ai-core/                    provider-neutral AI transport and structured results
source-workspace/           source snapshots, indexing, resolution and read-only viewing
ui-components/              shared Compose controls, theme and window integration
desktop-app/                process entry, native window, navigation and packaging
```

Dependencies flow inward: UI and platform adapters depend on application/domain modules; protocol and analysis modules do not depend on Android or Compose.

`platform-core/host-toolchain` owns host process execution, binary/text output, cancellation and timeouts.
`platform-core/adb-core` owns executable resolution and shared ADB primitives over immutable `DeviceTarget` values.
Feature adapters keep their capture lifecycle and protocol; they do not introduce another process runner or depend
on Simpleperf for shared ADB models. See [ADR-0007](../adr/0007-stateless-device-target-and-shared-adb.md).

Each immutable Capture or Import persists a versioned **Capture Artifact** envelope. The envelope records
Provenance, SHA-256, format, privacy-safe Device Target and Process Identity when known, Clock Domains and
bounded Clock Mappings, requested/available Capabilities, Artifact Completeness, limitations, and warnings.
Feature-specific HTTP calls, frame samples, heap graphs, method timelines, and battery snapshot deltas remain
in their existing domain models.

Perfetto-backed adapters use `platform-perfetto` with Trace Processor `v59.1`. A packaged or installed binary
must match the host checksum in `platform-perfetto/trace-processor-manifest.json`; an explicit override must
report the same version. There is no `PATH` fallback. Install the development binary with:

```bash
../scripts/install-trace-processor.sh
```

## Common commands

Run these commands from `desktop-viewer/`. Included builds have their own test and check tasks;
passing the root desktop task does not mean every feature or Android Agent was tested.

```bash
# Unified desktop integration and focused Layout Inspector tests
./gradlew :desktop-app:test
./gradlew :layout-inspector:application:test :layout-inspector:presentation:test

# All checks in the isolated Simpleperf build
./gradlew simpleperfCheck

# Android sample (requires the Android SDK and explicit opt-in)
./gradlew -PincludeLayoutInspectorAgents=true :layout-inspector-sample-app:assembleDebug

# Desktop app image for this OS
# Build the host-independent Android inspection runtime first (requires an Android SDK/NDK).
./gradlew :layout-inspector:compose-agent-build:verifyComposeAgentBundle --no-configuration-cache
./gradlew :desktop-app:createDistributable

# Compilation is separate from tests, native-window and device acceptance
./gradlew :desktop-app:compileKotlin
```

## Compose inspection runtime in desktop packages

Complete Compose inspection needs the verified `compose-agent-bundle`, not only the version-specific
Compose inspector downloaded at runtime. Desktop packaging copies this bundle into application
resources and checks all three device ABIs, the dexed JARs, and their manifest checksums. Packaging
fails if any required runtime file is missing or corrupt.

Release CI builds the runtime once on Linux x64 using a pinned Android SDK/NDK toolchain, transfers
it as an internal Actions artifact, and includes the same bundle in every native installer. Windows
and Linux arm64 packaging do not need their own Android SDK. Ordinary desktop configuration remains
SDK-independent; explicitly building the inspection agent still requires the toolchain.

The installed application resolves `compose-agent-bundle` from `compose.application.resources.dir`
before considering development working-directory paths. Explicit `agentperf.compose.agent.bundle`
or `APC_COMPOSE_AGENT_BUNDLE` configuration remains authoritative. Do not install a bundle at
`/compose-agent-bundle` to work around a missing packaged resource.

## Security invariants

1. Consumers add Agent artifacts with `debugImplementation`, never `implementation`.
2. `AgentInitializer` checks `ApplicationInfo.FLAG_DEBUGGABLE`.
3. The Agent listens on `LocalServerSocket`, not an IP socket.
4. The desktop obtains session metadata through `adb shell run-as`.
5. Every request must include the random session token.
6. ADB commands are constructed as argument vectors; device and package input is validated.

## Compatibility strategy

- Protocol major changes are incompatible.
- Unknown minor fields are ignored.
- The unified desktop application targets Java 21 bytecode; library targets are declared in their own builds.
- Traditional Views use public APIs available from API 21.
- FrameMetrics Agent capture uses public APIs on API 24+ and degrades to desktop-side `gfxinfo` polling otherwise.
- Screenshot capture and Compose semantics are intentionally separate adapters so unsupported platform/library versions can degrade without breaking View inspection.

## Next implementation milestones

1. Add authenticated snapshot request/response framing to the local socket.
2. Enumerate activity roots and serialize `ViewTreeCollector` output.
3. Add PixelCopy on API 26+ and Canvas fallback on API 21–25.
4. Add the optional Compose semantics adapter with current-plus-two-version compatibility tests.
5. Add SQLite report indexing and content-addressed artifacts.
6. Connect live device sessions to the desktop application store.

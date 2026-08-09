# Desktop Viewer Development Guide

## Version matrix

| Component | Version / range |
| --- | --- |
| Gradle | 9.4.1 |
| Kotlin | 2.3.21 |
| Android Gradle Plugin | 9.2.0 |
| Compose Multiplatform | 1.11.1 |
| Desktop JVM target | Java 17 bytecode |
| Android | minSdk 21, compile/target SDK 37 |

AGP 9.2 uses built-in Kotlin for Android modules. Do not re-add `org.jetbrains.kotlin.android`.

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
  adb-gateway/              pure ADB parsing and argument-vector construction
  application/              inspector state and selection use cases
  presentation/             embeddable Compose Desktop inspector workspace
  samples/
    android-view-app/       API 21+ zero-code Agent integration sample
simpleperf-viewer/          isolated Simpleperf CPU profiler build
platform-core/              Capture Artifact, host process, and ADB contracts
platform-perfetto/          pinned Trace Processor lifecycle and typed query boundary
frame-profiler/             isolated FrameTimeline/Jank profiler build
desktop-app/                  process entry, native window, and packaging
```

Dependencies flow inward: UI and platform adapters depend on application/domain modules; protocol and analysis modules do not depend on Android or Compose.

Each immutable Capture or Import persists a versioned **Capture Artifact** envelope. The envelope records
Provenance, SHA-256, format, privacy-safe Device Target and Process Identity when known, Clock Domains and
bounded Clock Mappings, requested/available Capabilities, Artifact Completeness, limitations, and warnings.
Feature-specific HTTP calls, frame samples, heap graphs, method timelines, and battery snapshot deltas remain
in their existing domain models.

Perfetto-backed adapters use `platform-perfetto` with Trace Processor `v57.2`. A packaged or installed binary
must match the host checksum in `platform-perfetto/trace-processor-manifest.json`; an explicit override must
report the same version. There is no `PATH` fallback. Install the development binary with:

```bash
../scripts/install-trace-processor.sh
```

## Common commands

```bash
# All JVM and Android unit tests
./gradlew test

# Android sample
./gradlew :layout-inspector:samples:android-view-app:assembleDebug

# Desktop app image for this OS
./gradlew :desktop-app:createDistributable

# Full local verification
./gradlew clean test assemble
```

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
- Desktop code targets Java 17 bytecode.
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

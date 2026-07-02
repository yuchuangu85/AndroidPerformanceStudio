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
shared-kernel/
  protocol-model/         transport-neutral snapshot contract and codec
  analysis-engine/        deterministic metrics and findings
  test-fixtures/          reusable snapshot fixtures
  android-agent-core/     debug gate and authenticated local socket
  android-agent-view/     traditional View hierarchy collection
  android-agent-startup/  zero-code AndroidX Startup integration

adb-gateway/              pure ADB parsing and argument-vector construction
application/              inspector state and selection use cases
desktop-app/              Compose Desktop presentation and native packaging
samples/
  android-view-app/       API 21+ zero-code Agent integration sample
```

Dependencies flow inward: UI and platform adapters depend on application/domain modules; protocol and analysis modules do not depend on Android or Compose.

## Common commands

```bash
# All JVM and Android unit tests
./gradlew test

# Android sample
./gradlew :samples:android-view-app:assembleDebug

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
- Screenshot capture and Compose semantics are intentionally separate adapters so unsupported platform/library versions can degrade without breaking View inspection.

## Next implementation milestones

1. Add authenticated snapshot request/response framing to the local socket.
2. Enumerate activity roots and serialize `ViewTreeCollector` output.
3. Add PixelCopy on API 26+ and Canvas fallback on API 21–25.
4. Add the optional Compose semantics adapter with current-plus-two-version compatibility tests.
5. Add SQLite report indexing and content-addressed artifacts.
6. Connect live device sessions to the desktop application store.

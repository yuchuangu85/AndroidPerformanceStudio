# AgentPerf Desktop Viewer

AgentPerf Desktop Viewer is a Compose Desktop inspector for Android layout snapshots. The current foundation release includes:

- a versioned JSON snapshot protocol;
- deterministic hierarchy metrics and findings;
- safe ADB device/output parsing and command construction;
- a three-pane hierarchy, canvas, and properties UI;
- a debug-only Android Agent using AndroidX Startup and an ADB-forwarded local abstract socket;
- an API 21–37 sample application.

## Run the desktop application

Prerequisites:

- macOS 13+, Windows 10 22H2/11, or Ubuntu 22.04/24.04;
- JDK 17 or newer (the repository currently builds with JDK 21 and emits Java 17 bytecode).

```bash
./gradlew :desktop-viewer:desktop-app:run
```

Create the native application image for the current OS:

```bash
./gradlew :desktop-viewer:desktop-app:createDistributable
```

## Try the Android sample

Set `ANDROID_HOME`, then build:

```bash
./gradlew :samples:android-view-app:assembleDebug
```

The sample declares the Agent only through `debugImplementation`. AndroidX Startup initializes it without changes to the sample `Application` or `Activity`.

```bash
adb install -r samples/android-view-app/build/outputs/apk/debug/android-view-app-debug.apk
adb shell am start -n dev.agentperf.sample/.MainActivity
adb shell run-as dev.agentperf.sample cat files/agentperf/session.json
```

The session file contains a random token and a `localabstract` socket name. No network permission, root, system signature, hidden API, or production-build integration is used.

## Current scope

The desktop UI currently renders a built-in snapshot fixture. The Android Agent accepts authenticated `PING` requests and records its session descriptor. Live snapshot transport, PixelCopy capture, Compose semantics, report persistence, and timeline diff are the next milestones.

See:

- [Architecture and product design](docs/2026-07-02-desktop-viewer-design.md)
- [Development guide](docs/DEVELOPMENT.md)
- [Protocol contract](docs/PROTOCOL.md)

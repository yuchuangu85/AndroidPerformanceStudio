# AgentPerf Desktop Viewer

AgentPerf Desktop Viewer is a Compose Desktop inspector for Android layout snapshots. The current foundation release includes:

- a versioned JSON snapshot protocol;
- deterministic hierarchy metrics and findings;
- safe ADB device/output parsing and command construction;
- a three-pane hierarchy, canvas, and properties UI;
- a debug-only Android Agent using AndroidX Startup and an ADB-forwarded local abstract socket;
- synchronized live View hierarchy and PNG capture from one authorized device;
- an API 21–37 sample application.

## Run the desktop application

Prerequisites:

- macOS 13+, Windows 10 22H2/11, or Ubuntu 22.04/24.04;
- JDK 17 or newer (the repository currently builds with JDK 21 and emits Java 17 bytecode).

```bash
./gradlew :desktop-app:run
```

Create the native application image for the current OS:

```bash
./gradlew :desktop-app:createDistributable
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

With exactly one authorized device connected and the sample Activity in the foreground, start the desktop application:

```bash
./gradlew :desktop-app:run
```

The viewer connects to `dev.agentperf.sample`, refreshes approximately once per second, and renders the captured window PNG with the selected View bounds overlaid. Connection failures remain visible in the header and are retried automatically.

## Current scope

The live path currently targets one authorized device and the sample package. Traditional View hierarchy and screenshots are supported. Arbitrary package selection, multiple-device selection, Compose semantics, report persistence, and timeline diff remain future work.

See:

- [Architecture and product design](docs/2026-07-02-desktop-viewer-design.md)
- [Development guide](docs/DEVELOPMENT.md)
- [Protocol contract](docs/PROTOCOL.md)

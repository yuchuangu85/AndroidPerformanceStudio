# AndroidPerfermanceStudio

AndroidPerfermanceStudio contains one root desktop application shell and two deliberately isolated
feature implementations:

- **Layout Inspector** — the Android layout snapshot inspector under `layout-inspector/`;
  its embeddable desktop UI is the `presentation/` module.
- **Simpleperf CPU Profiler** — an independently built capture and CPU profile analyzer under `simpleperf-viewer/`.

The Layout Inspector foundation includes:

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

Run the independently isolated Simpleperf CPU Profiler:

```bash
./gradlew simpleperfRun
```

Run its checks or create its native application image without changing the Layout Inspector build:

```bash
./gradlew simpleperfCheck
./gradlew simpleperfCreateDistributable
```

Directory ownership is explicit: `desktop-app/` owns the root executable, `layout-inspector/` owns
all layout inspection implementation, and `simpleperf-viewer/` owns the isolated CPU profiler
build. Layout Inspector and Simpleperf do not declare project dependencies on each other.

Create the native application image for the current OS:

```bash
./gradlew :desktop-app:createDistributable
```

## Try the Android sample

Set `ANDROID_HOME`, then build:

```bash
./gradlew :layout-inspector:samples:android-view-app:assembleDebug
```

The sample declares the Agent only through `debugImplementation`. AndroidX Startup initializes it without changes to the sample `Application` or `Activity`.

```bash
adb install -r layout-inspector/samples/android-view-app/build/outputs/apk/debug/android-view-app-debug.apk
adb shell am start -n dev.agentperf.sample/.MainActivity
adb shell run-as dev.agentperf.sample cat files/agentperf/session.json
```

The session file contains a random token and a `localabstract` socket name. No network permission, root, system signature, hidden API, or production-build integration is used.

With exactly one authorized device connected, start the desktop application:

```bash
./gradlew :desktop-app:run
```

The viewer follows the foreground application and reconnects automatically when the user switches apps. AndroidPerfermanceStudio-enabled debug builds use the Agent socket for high-fidelity capture. Other foreground applications fall back to `uiautomator dump` plus `screencap`, so newly installed debug apps appear without requiring AndroidPerfermanceStudio integration. CANVAS defaults to `仅应用 ON`, which crops to the real application bounds and maximizes that content at its native aspect ratio; switch it off to inspect the complete device or emulator display. The selected View bounds are overlaid on the matching screenshot coordinate space. Connection failures remain visible in the header and are retried automatically. The two vertical separators can be dragged to resize HIERARCHY and PROPERTIES for the current session; CANVAS uses the remaining width.

## Current scope

The live path currently targets one authorized device and its foreground application. Traditional View hierarchy and screenshots are supported. The ADB fallback is slower and only exposes nodes available through UI Automator. Multiple-device selection, Compose semantics, report persistence, and timeline diff remain future work.

See:

- [Architecture and product design](docs/2026-07-02-desktop-viewer-design.md)
- [Development guide](docs/DEVELOPMENT.md)
- [Protocol contract](docs/PROTOCOL.md)
- [Deferred AI analysis work](docs/ai-analysis-roadmap.md)

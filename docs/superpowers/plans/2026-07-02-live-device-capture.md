# Live Device Capture Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Display the connected Android sample application's synchronized View hierarchy and window screenshot in Desktop Viewer with an aligned selected-node overlay.

**Architecture:** Add a bounded capture frame codec shared by the Android Agent and desktop client. The Agent tracks the resumed Activity, captures its View tree and PNG in one operation, and serves it through the authenticated local socket; the desktop ADB gateway connects, polls frames, and publishes them to the existing inspector store and Compose UI.

**Tech Stack:** Kotlin 2.3.21, Android SDK 21–37, PixelCopy, Android LocalServerSocket, Java TCP sockets, kotlinx.serialization, Compose Desktop, Skia, JUnit 5.

---

### Task 1: Bounded capture response framing

**Files:**
- Create: `desktop-viewer/shared-kernel/protocol-model/src/main/kotlin/dev/agentperf/protocol/CaptureFrameCodec.kt`
- Create: `desktop-viewer/shared-kernel/protocol-model/src/test/kotlin/dev/agentperf/protocol/CaptureFrameCodecTest.kt`

- [ ] **Step 1: Write failing round-trip and malformed-length tests**

Define `CaptureFrame(snapshotJson: String, screenshotPng: ByteArray)` and tests that write/read a frame through byte-array streams, reject negative lengths, and reject lengths above explicit JSON/PNG limits.

- [ ] **Step 2: Run the protocol test and verify RED**

Run:

```bash
cd desktop-viewer
./gradlew :shared-kernel:protocol-model:test --tests '*CaptureFrameCodecTest' --console=plain
```

Expected: Kotlin compilation fails because `CaptureFrameCodec` does not exist.

- [ ] **Step 3: Implement the minimal bounded codec**

Use the response header:

```text
CAPTURE <json-byte-count> <png-byte-count>\n
```

Read the header byte-by-byte with a bounded line length, validate both counts before allocation, and use `readNBytes` semantics that reject truncated payloads.

- [ ] **Step 4: Run tests and verify GREEN**

Run the Task 1 command. Expected: all `CaptureFrameCodecTest` tests pass.

### Task 2: Authenticated Agent request routing

**Files:**
- Create: `desktop-viewer/shared-kernel/android-agent-core/src/main/kotlin/dev/agentperf/android/core/AgentRequestHandler.kt`
- Create: `desktop-viewer/shared-kernel/android-agent-core/src/test/kotlin/dev/agentperf/android/core/AgentRequestHandlerTest.kt`
- Modify: `desktop-viewer/shared-kernel/android-agent-core/src/main/kotlin/dev/agentperf/android/core/AgentServer.kt`

- [ ] **Step 1: Write failing request-handler tests**

Test these public contracts:

```kotlin
fun interface CaptureProvider {
    fun capture(): CaptureFrame
}

class AgentRequestHandler(
    private val token: String,
    private val captureProvider: CaptureProvider,
) {
    fun handle(request: String, output: OutputStream)
}
```

Cover valid `PING`, valid `CAPTURE`, invalid token, and capture failure encoded as a stable `ERROR` line.

- [ ] **Step 2: Run the core test and verify RED**

```bash
./gradlew :shared-kernel:android-agent-core:testDebugUnitTest \
  --tests '*AgentRequestHandlerTest' --console=plain
```

Expected: compilation fails because the handler contracts do not exist.

- [ ] **Step 3: Implement request routing and delegate AgentServer**

Keep `PONG 1.0` compatible. Make `AgentServer` construct one handler per session and delegate each accepted socket request without placing capture logic in the socket loop.

- [ ] **Step 4: Run core tests and verify GREEN**

Run the Task 2 command plus `:shared-kernel:android-agent-core:test`.

### Task 3: Foreground Activity View and screenshot capture

**Files:**
- Create: `desktop-viewer/shared-kernel/android-agent-view/src/main/kotlin/dev/agentperf/android/view/ResumedActivityTracker.kt`
- Create: `desktop-viewer/shared-kernel/android-agent-view/src/main/kotlin/dev/agentperf/android/view/ActivityCaptureProvider.kt`
- Create: `desktop-viewer/shared-kernel/android-agent-view/src/main/kotlin/dev/agentperf/android/view/LiveSnapshotFactory.kt`
- Create: `desktop-viewer/shared-kernel/android-agent-view/src/test/kotlin/dev/agentperf/android/view/LiveSnapshotFactoryTest.kt`
- Modify: `desktop-viewer/shared-kernel/android-agent-view/build.gradle.kts`
- Modify: `desktop-viewer/shared-kernel/android-agent-startup/build.gradle.kts`
- Modify: `desktop-viewer/shared-kernel/android-agent-startup/src/main/kotlin/dev/agentperf/android/startup/AgentInitializer.kt`

- [ ] **Step 1: Write the failing snapshot-factory test**

Given package, dimensions, density, timestamp, and a collected root node, assert protocol `1.0`, screenshot capability, display values, and root identity.

- [ ] **Step 2: Run the view test and verify RED**

```bash
./gradlew :shared-kernel:android-agent-view:testDebugUnitTest \
  --tests '*LiveSnapshotFactoryTest' --console=plain
```

Expected: compilation fails because `LiveSnapshotFactory` does not exist.

- [ ] **Step 3: Implement Activity tracking and capture**

Register `Application.ActivityLifecycleCallbacks`, retain the resumed Activity weakly, and execute capture on the main looper. Use `PixelCopy.request(window, ...)` on API 26+ and `decorView.draw(Canvas(bitmap))` on API 21–25. Encode lossless PNG, collect the decor tree, and return one `CaptureFrame`.

- [ ] **Step 4: Wire startup**

Make `android-agent-startup` depend on `android-agent-view`, register the tracker before starting `AgentServer`, and inject `ActivityCaptureProvider`.

- [ ] **Step 5: Run tests and assemble the sample**

```bash
./gradlew :shared-kernel:android-agent-view:test \
  :shared-kernel:android-agent-startup:test \
  :samples:android-view-app:assembleDebug --console=plain
```

Expected: build succeeds.

### Task 4: Desktop ADB live capture client

**Files:**
- Create: `desktop-viewer/adb-gateway/src/main/kotlin/dev/agentperf/adb/AdbProcessRunner.kt`
- Create: `desktop-viewer/adb-gateway/src/main/kotlin/dev/agentperf/adb/AgentSession.kt`
- Create: `desktop-viewer/adb-gateway/src/main/kotlin/dev/agentperf/adb/LiveDeviceClient.kt`
- Create: `desktop-viewer/adb-gateway/src/test/kotlin/dev/agentperf/adb/LiveDeviceClientTest.kt`
- Modify: `desktop-viewer/adb-gateway/build.gradle.kts`

- [ ] **Step 1: Write failing session and client tests**

Test session JSON parsing, exactly-one-authorized-device selection, explicit zero/multiple-device errors, ADB command sequencing, `PING` authentication, and decoding a real capture frame from a local `ServerSocket`.

- [ ] **Step 2: Run the gateway test and verify RED**

```bash
./gradlew :adb-gateway:test --tests '*LiveDeviceClientTest' --console=plain
```

Expected: compilation fails because the live client contracts do not exist.

- [ ] **Step 3: Implement the minimal client**

Run ADB with argument vectors, preserve stderr in failures, allocate a loopback port, install the forward, authenticate, and expose:

```kotlin
class LiveDeviceClient {
    fun connect(packageName: String): ConnectedDeviceSession
}

class ConnectedDeviceSession : AutoCloseable {
    fun capture(): CaptureFrame
}
```

Ensure `close()` removes the ADB forward.

- [ ] **Step 4: Run gateway tests and verify GREEN**

Run all `:adb-gateway:test` tests.

### Task 5: Inspector capture state and canvas geometry

**Files:**
- Modify: `desktop-viewer/application/src/main/kotlin/dev/agentperf/application/InspectorState.kt`
- Modify: `desktop-viewer/application/src/main/kotlin/dev/agentperf/application/InspectorStore.kt`
- Modify: `desktop-viewer/application/src/test/kotlin/dev/agentperf/application/InspectorStoreTest.kt`
- Create: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/CanvasGeometry.kt`
- Create: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/CanvasGeometryTest.kt`

- [ ] **Step 1: Write failing state and geometry tests**

Assert that loading a capture publishes PNG bytes while preserving the selected node when it still exists. Assert contain-fit image rectangle and bounds mapping for matching and mismatched aspect ratios.

- [ ] **Step 2: Run application and desktop tests and verify RED**

```bash
./gradlew :application:test :desktop-app:test --console=plain
```

Expected: compilation fails because capture loading and canvas geometry do not exist.

- [ ] **Step 3: Implement capture publication and pure geometry**

Add `screenshotPng`, `connectionStatus`, and `connectionError` to state. Add `loadCapture`, `connecting`, and `connectionFailed` store methods. Keep geometry free of Compose dependencies so its mapping is directly testable.

- [ ] **Step 4: Run tests and verify GREEN**

Run the Task 5 command and confirm existing selection/presenter tests still pass.

### Task 6: Compose polling and synchronized rendering

**Files:**
- Modify: `desktop-viewer/desktop-app/build.gradle.kts`
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt`
- Modify: `desktop-viewer/desktop-app/src/main/kotlin/dev/agentperf/desktop/InspectorPresenter.kt`
- Modify: `desktop-viewer/desktop-app/src/test/kotlin/dev/agentperf/desktop/InspectorPresenterTest.kt`
- Modify: `desktop-viewer/README.md`
- Modify: `desktop-viewer/docs/PROTOCOL.md`

- [ ] **Step 1: Write the failing presenter status test**

Assert connected, connecting, and failed status text/color semantics without testing Compose pixels.

- [ ] **Step 2: Run desktop tests and verify RED**

```bash
./gradlew :desktop-app:test --console=plain
```

Expected: the new presenter assertions fail.

- [ ] **Step 3: Implement the capture loop**

Add `adb-gateway` and coroutines dependencies. Use `LaunchedEffect` and `withContext(Dispatchers.IO)` to connect to `dev.agentperf.sample`, capture every second, update the store on the Compose thread, and close/reconnect sessions after failures.

- [ ] **Step 4: Render the PNG and overlay**

Decode PNG with Skia, draw it inside the contain-fit destination rectangle, and map `selectedNode.bounds` with the same geometry. Replace the fixture-only green status dot with actual connection status while retaining the fixture as a disconnected startup fallback only until the first live frame.

- [ ] **Step 5: Update protocol and usage documentation**

Document `CAPTURE`, binary response framing, live polling behavior, and the sample-first package limitation.

- [ ] **Step 6: Run full automated verification**

```bash
./gradlew clean test assemble --console=plain
```

Expected: build succeeds with all tests passing.

### Task 7: Physical-device end-to-end verification

**Files:**
- No source files.

- [ ] **Step 1: Install and launch**

```bash
adb -s b3b78d93 install -r \
  samples/android-view-app/build/outputs/apk/debug/android-view-app-debug.apk
adb -s b3b78d93 shell am force-stop dev.agentperf.sample
adb -s b3b78d93 shell am start -W -n dev.agentperf.sample/.MainActivity
```

- [ ] **Step 2: Verify the protocol directly**

Read the session descriptor, create an ADB forward, send authenticated `PING` and `CAPTURE`, and verify the returned JSON package plus non-empty PNG signature.

- [ ] **Step 3: Launch Desktop Viewer and inspect**

Run `./gradlew :desktop-app:run`, capture the desktop window, and verify that the device screenshot, hierarchy, and selected bounds are visible and aligned.

- [ ] **Step 4: Verify refresh and recovery**

Rotate or alter the sample UI and verify a refreshed frame. Force-stop/restart the sample and verify the desktop status transitions through failure back to connected.

- [ ] **Step 5: Review the final diff**

Run:

```bash
git diff --check
git status --short
```

Confirm no generated APK, screenshot, session token, or local configuration is tracked.

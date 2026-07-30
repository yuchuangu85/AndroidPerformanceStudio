# Live Device Capture Design

## Goal

Extend AgentPerf Desktop Viewer so it can automatically connect to the authorized Android device, capture the foreground debuggable application's current View hierarchy and window screenshot as one logical frame, and render the screenshot with the selected View bounds overlaid.

The first supported target is the existing `com.androidperformancestudio.sample` debug application. The design preserves the current debug-only, ADB-only security boundary and the existing `PING` handshake.

## Scope

### Included

- Track the resumed Activity inside the Android debug Agent.
- Capture traditional Android View hierarchy metadata.
- Capture the Activity window as PNG.
- Return hierarchy JSON and PNG through one authenticated `CAPTURE` request.
- Discover one authorized ADB device and connect to the sample package.
- Poll captures from the Desktop Viewer and reconnect after transient failures.
- Display the captured PNG at its native aspect ratio.
- Overlay the selected node bounds using the captured display coordinate system.
- Show connection and capture errors without terminating the desktop application.

### Excluded

- Compose semantics.
- Video encoding or high-frame-rate streaming.
- Multiple simultaneous devices.
- Arbitrary package selection UI.
- Production or non-debuggable application integration.
- Persistent reports and timeline diffing.

## Architecture

### Protocol model

`protocol-model` owns a bounded binary response frame shared by Android and Desktop:

```text
CAPTURE <snapshot-json-byte-count> <png-byte-count>\n
<snapshot JSON bytes><PNG bytes>
```

Both byte counts are validated before allocation. Error responses remain line-oriented:

```text
ERROR <stable-code> <human-readable-message>\n
```

The existing request and handshake remain compatible:

```text
PING <token>
PONG 1.0
```

The new authenticated request is:

```text
CAPTURE <token>
```

### Android Agent

`android-agent-view` provides a capture source with three responsibilities:

1. Track the currently resumed Activity with `Application.ActivityLifecycleCallbacks`.
2. Collect the Activity decor View hierarchy on the main thread.
3. Capture the Activity window:
   - API 26 and newer: `PixelCopy`.
   - API 21–25: draw the decor View into a Bitmap-backed Canvas.

The hierarchy and screenshot are produced in the same main-thread capture operation. The resulting `LayoutSnapshot` records the PNG dimensions and density, declares `viewHierarchy` and `screenshots` capabilities, and uses physical pixel bounds.

`android-agent-core` keeps socket ownership and authentication. It delegates capture production to an injected provider, encodes the snapshot with `ProtocolCodec`, and writes one bounded capture response.

`android-agent-startup` registers the Activity tracker and starts the server during AndroidX Startup initialization. Non-debuggable applications remain disabled.

### ADB gateway

The gateway adds a process boundary around the existing safe command construction:

- parse `adb devices -l`;
- select the single authorized device;
- read `files/agentperf/session.json` with `run-as`;
- create an ADB forward to the Agent's local abstract socket;
- authenticate with `PING`;
- request and decode `CAPTURE` responses.

The process runner and socket connector are injected so device selection, command failures, authentication, and frame decoding can be tested without a physical device.

### Desktop application

The Desktop Viewer starts a capture loop when its window opens:

1. Connect to the sample package.
2. Request a capture.
3. publish the snapshot and PNG into `InspectorStore`;
4. wait one second;
5. repeat until cancelled.

Transient errors update connection status, close the current forwarding session, wait, and reconnect. Blocking ADB and socket work runs on `Dispatchers.IO`.

The canvas decodes PNG bytes with Skia. It computes a contain-fit destination rectangle from the captured display dimensions, draws the image into that rectangle, and applies the identical scale and offset to the selected View bounds. This keeps overlays aligned across device aspect ratios and desktop window sizes.

## Data flow

```text
Desktop Viewer
  -> adb devices / run-as / forward
  -> TCP forwarded local socket
  -> CAPTURE <token>
  -> Agent capture provider
  -> resumed Activity decor View
  -> LayoutSnapshot + PNG
  -> bounded binary capture frame
  -> InspectorStore
  -> screenshot canvas + selected-bounds overlay
```

## Error handling

- No authorized device: show a disconnected status and retry.
- Multiple authorized devices: return an explicit selection error rather than choosing unpredictably.
- Package missing or not debuggable: surface the `run-as` failure and retry.
- No resumed Activity: return `NO_ACTIVITY`.
- Main-thread capture timeout: return `CAPTURE_TIMEOUT`.
- PixelCopy or PNG encoding failure: return `SCREENSHOT_FAILED`.
- Invalid token: return `UNAUTHORIZED`.
- Invalid or oversized frame lengths: reject the response and reconnect.
- Protocol incompatibility: preserve the existing major-version rejection.

No failure should block the Compose event thread or terminate the Desktop Viewer process.

## Security

- Agent artifacts remain `debugImplementation`.
- `AgentInitializer` keeps the `FLAG_DEBUGGABLE` gate.
- The Agent listens only on `LocalServerSocket`.
- Desktop access requires ADB, `run-as`, and the random session token.
- The protocol applies strict maximum JSON and PNG lengths.
- ADB invocations remain argument vectors with validated serial, package, socket, and port values.

## Testing

### Automated

- Protocol frame round-trip and malformed/oversized length rejection.
- Agent server request routing and authentication with an injected capture provider.
- ADB device selection, session parsing, forwarding, and error propagation.
- Live capture client handshake and response decoding through a local test socket.
- Inspector store publication of snapshot plus screenshot.
- Canvas contain-fit and selected-bounds coordinate mapping.
- Existing protocol, analysis, Agent runtime, ADB, application, and presenter tests.

### Physical device

Using the connected OnePlus PHK110:

1. Build and install the sample debug APK.
2. Launch `MainActivity`.
3. Verify the Agent session descriptor.
4. Launch Desktop Viewer.
5. Verify that the screenshot matches the device screen.
6. Select each visible hierarchy row and verify its overlay aligns.
7. Change the device UI or orientation and verify the next capture refreshes.
8. Confirm reconnect behavior after force-stopping and restarting the sample.

## Success criteria

- Desktop Viewer displays the connected sample application's current screenshot.
- The hierarchy comes from the same capture response as the screenshot.
- Selecting a hierarchy node draws an aligned overlay on the screenshot.
- Captures refresh approximately once per second without blocking desktop UI.
- Disconnects and unavailable foreground Activities are visible and recover automatically.
- Existing tests and full Gradle verification pass.

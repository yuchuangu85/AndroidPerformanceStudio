# AgentPerf Protocol v1

## Protocol versions

| Version | Identifier | Meaning | Export behavior |
| --- | --- | --- | --- |
| 1.0 | `1.0` | Legacy single-root snapshots. `windows` and `defaultWindowId` may be absent. | Accepted for import; normalized to the current protocol when exported again. |
| 1.1 | `1.1` | Multi-window snapshots with explicit `windows` and `defaultWindowId`. | Current export version. |

The code-level constants are `PROTOCOL_VERSION_1_0`, `PROTOCOL_VERSION_1_1`,
and `CURRENT_PROTOCOL_VERSION`. Each `ProtocolVersion` also exposes an
`identifier` such as `1.1` for display and diagnostics.

## Compatibility

The wire document carries:

```json
"protocolVersion": { "major": 1, "minor": 0 }
```

- A different major version is rejected before snapshot decoding.
- A newer minor version is accepted and unknown fields are ignored.
- The viewer must preserve an incompatible raw payload when report storage is implemented.

## Snapshot envelope

```json
{
  "protocolVersion": { "major": 1, "minor": 0 },
  "packageName": "dev.agentperf.sample",
  "capturedAtEpochMillis": 1750000000000,
  "display": {
    "widthPx": 1080,
    "heightPx": 2400,
    "density": 3.0
  },
  "capabilities": {
    "viewHierarchy": true,
    "composeSemantics": false,
    "screenshots": false,
    "timeline": false
  },
  "root": {
    "type": "view",
    "id": "root",
    "className": "android.widget.FrameLayout",
    "bounds": { "left": 0, "top": 0, "right": 1080, "bottom": 2400 },
    "visible": true,
    "alpha": 1.0,
    "children": []
  }
}
```

Node discriminator values are `view` and `compose`. Bounds are physical display pixels. Node IDs need only remain stable within one snapshot until a later protocol version defines cross-frame identity.

## Session bootstrap

The debug Agent writes `files/agentperf/session.json`:

```json
{
  "protocolMajor": 1,
  "protocolMinor": 0,
  "socketName": "agentperf.dev_agentperf_sample",
  "token": "<256-bit random hex token>"
}
```

The desktop reads this file with `run-as`, then executes:

```text
adb -s <serial> forward tcp:<port> localabstract:<socketName>
```

The foundation handshake is:

```text
PING <token>
PONG 1.0
```

Any missing or invalid token receives `ERROR UNAUTHORIZED Invalid session token`.

## Live capture

The desktop opens a new connection and sends:

```text
CAPTURE <token>
```

The Agent captures the resumed Activity's decor View hierarchy and window PNG as one logical frame. A successful response starts with an ASCII header:

```text
CAPTURE <snapshotJsonByteCount> <screenshotPngByteCount>\n
```

The header is followed immediately by exactly `snapshotJsonByteCount` UTF-8 JSON bytes and `screenshotPngByteCount` PNG bytes. The maximum accepted sizes are 8 MiB for JSON and 32 MiB for PNG. Receivers reject negative, oversized, malformed, and truncated payloads before publishing a frame.

Capture failures are line-oriented:

```text
ERROR <stableCode> <message>\n
```

Current stable codes include `UNAUTHORIZED`, `NO_ACTIVITY`, `NO_CONTENT`, `CAPTURE_TIMEOUT`, `CAPTURE_INTERRUPTED`, `CAPTURE_FAILED`, and `SCREENSHOT_FAILED`.

The screenshot dimensions match `display.widthPx` and `display.heightPx`. View bounds use the same physical-pixel coordinate system so the desktop can apply one contain-fit transform to both the PNG and selected-node overlay.

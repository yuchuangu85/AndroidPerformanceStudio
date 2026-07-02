# AgentPerf Protocol v1

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

Any missing or invalid token receives `UNAUTHORIZED`. Snapshot request framing will be added without weakening this bootstrap.

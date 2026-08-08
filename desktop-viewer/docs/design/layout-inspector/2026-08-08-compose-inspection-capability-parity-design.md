# Compose Inspection Capability Parity Design

## Goal

Provide the public stable Compose inspection evidence and workflows of Android Studio 2026.1.2 without copying its UI, private Transport integration, or device-mirroring stack. The existing reflection-based Semantics collector remains an explicit degraded mode; it is not full Compose inspection.

Public baseline:

- complete Compose, View, and hybrid hierarchy;
- parameters, Modifier, merged and unmerged Semantics;
- source callsite navigation;
- recomposition and skip observation, reset, stop, and highlighting;
- Deep Inspect, overlap selection, subtree/parent isolation, and snapshots.

Reference: [Android Layout Inspector](https://developer.android.com/studio/debug/layout-inspector.html), [Debug Compose UI](https://developer.android.com/develop/ui/compose/tooling/debug).

Device mirroring, remote input, Live Edit, reference-image overlays, and magnifier tools are outside this Compose capability boundary.

## Runtime architecture

Full mode attaches to an arbitrary debuggable Android process without application code changes:

1. preflight identifies device, package, PID, API, ABI, Compose version, artifact source, downloads, restarts, and device-setting changes;
2. the user explicitly authorizes attachment;
3. the host deploys the packaged per-ABI JVMTI agent and service/payload JARs;
4. the host resolves the exact Compose Inspector from `androidx.compose.ui:ui` before 1.5.0 or `ui-android` from 1.5.0;
5. ADB forwards only the authenticated localabstract agent socket;
6. the adapter converts upstream protobuf into the stable APS model;
7. stop, timeout, or failure removes forwards and staged files and restores device settings.

The reusable upstream base is pinned at AOSP tools/base revision `b1261356012800c0a93d18f03b060024e8162c2f`. Its unmodified source and provenance live in `third_party/aosp-ui-inspector`; APS adapters stay outside that directory.

One device/PID session supports all windows and Compose roots. A PID or Agent identity change ends the session and requires new authorization. A transient ADB disconnect may reconnect only to the same PID, Agent identity, and session token with bounded exponential backoff.

## Support contract

Protocol support requires API 29+, Compose UI 1.2+, a debuggable process, retained `META-INF/androidx.compose.*.version`, and an exact compatible Inspector. Preview or custom artifacts are usable but never certified, and no nearby-version fallback exists.

The first certification matrix is the full product of:

- Compose: 1.2.0, latest 1.4.x, latest 1.5.x, and 1.11.4;
- Android API: 29, 34, and 37;
- ABI: armeabi-v7a, arm64-v8a, and x86_64;
- pure Compose and View/Compose hybrid fixtures.

That is 36 target combinations, plus representative end-to-end checks on macOS arm64/x64, Windows x64, and Ubuntu x64. Uncertified but protocol-compatible targets are labelled as such. A full-mode failure never silently falls back to Semantics-only.

## Stable model

`LayoutSnapshot` protocol v1 remains unchanged. Rich evidence uses `ComposeInspectionDocument` schema v1:

- frame identity, upstream generation, mode, and verified capabilities;
- raw roots and nodes, including system-created composables;
- source package hash, file, line, offset, bounds, flags, anchor, and counts;
- on-demand parameters, Modifier, merged/unmerged Semantics;
- detail coverage and explicit truncations;
- observation interval, artifact identity, and archive privacy.

Live UI is a sequence of immutable Compose inspection frames. Tree, display state, capabilities, and statistics are frame-consistent. On-demand detail declares its frame; stale responses are discarded. Selection crosses frames only with a unique verified anchor/callsite identity.

System composables are archived and hidden only in the default display projection. Findings retain original IDs. The protocol-v1 projection removes hidden wrappers but keeps their application descendants.

## Detail and resource limits

Tree capture is live; details are requested for the selected node. Defaults follow the upstream protocol: recursion depth 2 and five initial iterable elements. Expansion uses pages of 50.

- frame: 100,000 nodes or 64 MiB decoded data;
- selected node: depth 10, 10,000 values, or 8 MiB;
- individual string: 64 KiB while retaining original length;
- all omissions use explicit `NOT_COLLECTED`, `TRUNCATED`, or failure state.

Resource-limited frames are inspectable but cannot pass full certification.

## Recomposition observation

Observation is off by default and starts only after an explicit action. Start clears counters and opens a new interval; stop freezes it; reset clears counts and starts a new interval. Process or identity changes never splice intervals.

Raw recompose/skip counts and interval duration are evidence. Heat color is a recent-rate presentation and is not archived. Skip counts carry no automatic quality judgement. State Reads and call stacks remain deferred experimental capabilities.

## Archives and privacy

Archive v1 remains the compatibility export containing the projected protocol-v1 tree. Released v1 readers reject unknown entries larger than 1 MiB, so archive v2 is required for the optional `capture/compose-inspection.json` sidecar up to 64 MiB. New readers accept v1 and v2.

Safe archives are the default and redact runtime values recursively while retaining type, shape, source, coverage, and truncation. Full-fidelity archives require explicit confirmation and are marked sensitive. Privacy level and detail coverage are independent: export does not silently traverse unrequested parameters; complete detail collection is a separate confirmed operation.

Archives never contain executable Agent/Inspector code, credentials, session tokens, device paths, or source bodies. Imported runtime values are not sent to AI automatically.

## Performance gates

- adaptive refresh: idle 1 Hz, changing up to 5 Hz, paused in background;
- cached attach P95 at most 5 seconds;
- 5,000 nodes: first frame P95 at most 2 seconds, changed frame P95 at most 1 second;
- selected first-level detail P95 at most 500 ms;
- without recomposition observation: idle device CPU below 1%, active average CPU increase below 5%, memory P95 below 50 MiB.

Upstream generation caching and delayed parameter extraction must be used. Data is never silently truncated to meet a performance gate.

## Delivery and release gate

1. prove dynamic injection, full evidence, three ABIs, multiple Compose versions, and cleanup;
2. land stable model, projection, capability/degradation states, and archive v2;
3. integrate tree, details, system toggle, Deep Inspect, isolation, and source navigation;
4. integrate recomposition observation and heat presentation;
5. complete privacy, compatibility, conflict, disconnect, restart, and cleanup tests;
6. compare evidence and workflows against Android Studio 2026.1.2 on the same fixtures.

Development remains behind an internal flag. It becomes the standard Compose entry only after the complete matrix and three-host gates pass; until then the product must not claim full Compose capability parity.

# Native Inspector capture: archive import, in-app agent and ADB capture

Reads the existing **ZIP** `agentperf-inspector-capture` archive versions 1 and 2,
not a replacement JSON format. The contracts are the retained Kotlin
`CaptureArchiveCodec.kt`, `CaptureArchive.kt`, `ProtocolModels.kt` and
`ComposeInspectionModels.kt`. Android Agents remain Kotlin/Java. Archive import
performs no ADB or injection. The separate blocking `capture_compatible` adapter
is agent-first, like the Compose host's `LiveDeviceClient`: for a foreground app
it reads the app's `files/agentperf/session.json` descriptor through
`adb shell run-as`, registers one loopback `adb forward` to the agent's abstract
socket, authenticates with the session token and pulls one bounded frame. An app
that is not debuggable, is not agent-enabled or no longer runs the agent makes
this path unavailable, and the capture falls back to ADB exactly like Compose's
`AgentUnavailableException` fallback; a malformed descriptor, forward failure,
authentication mismatch, remote `ERROR` response or invalid frame stays a hard
error. The adapter installs no helpers and changes no device settings. The host
provides manual refresh only, and publishes after every stage succeeds.

The in-app agent frame is a protocol `LayoutSnapshot` plus a PNG screenshot,
carried by the Kotlin `CaptureFrameCodec` framing (`CAPTURE <snapshotBytes>
<screenshotBytes>` after a `PING`/`PONG` handshake). Each capture uses its own
short-lived forward, which is removed again on success and failure; unlike
Compose's reusable session it does not keep a forwarding socket open between
refreshes. A single-window frame keeps the agent screenshot and the activity
window display extent; a multi-window frame re-captures the full display with
`screencap -p` and replaces the display extent, matching Compose's
`requiresFullDisplayScreenshot` path. The snapshot is projected through the same
bounded `parse_snapshot` ingestion as archives and is marked as an agent frame so
the host can distinguish it from an ADB projection. Frame verification is
stricter than Compose: screenshot dimensions must equal the final display extent,
and a failed multi-window `screencap` is an error instead of silently mixing a
window-sized image with a display-sized snapshot.

ADB fallback capture prefers the WindowManager runtime View dump: the device returns a
ZIP with one encoded hierarchy per visible window (Android
`ViewHierarchyEncoder` wire format), decoded into the same shape as the Compose
version's `VisibleWindowHierarchyParser` — real per-window View classes,
layout/drawing/interaction attributes and raw runtime properties, with absolute
bounds composed from window offsets, scroll and drawing translation. Window
identity, title/type, package filtering and System UI title/launcher-taskbar
matching follow the Kotlin oracle. When a window contains a
`ComposeView`/`AndroidComposeView`, the accessibility projection is compared as
Kotlin `expandShallowComposeHierarchy` does and adopted only when it carries
Compose semantics and more nodes; descendants of the host are then promoted to
bounded Compose nodes with the Kotlin semantic class/role/properties. Otherwise
`uiautomator dump` is the fallback projection. Neither ADB path is injected
Compose inspection (the separate `ComposeBridge` helper covers that opt-in).

Inspector capture uses the selected device's **foreground app**, independently
of the process selected for other profilers. Compose preflight discovers the
foreground package and running PID automatically; when personal/work-profile
copies share a process name, the focused ActivityRecord disambiguates the PID.
The existing helper still requires a debuggable Compose app, and injection still
requires the explicit confirmation for the discovered device/package/PID.
Foreground and PID checks around preflight/capture reject stale targets rather
than transferring authorization to another app. Discovery is read-only and
does not launch apps or attach an inspector.

An occupied `ui_inspector_<PID>` socket can belong to an active inspector or a
disconnected older APS agent, not necessarily another tool. Preflight waits
briefly for cleanup and then explains the conflict without connecting to or
terminating an unknown session. Older agents retain their five-minute idle
timeout; the updated bundled agent releases its socket when its authenticated
host disconnects, even if writing a shutdown acknowledgement fails. Recovery
never restarts the app automatically or changes its runtime state.

Runtime-dump and screenshot acquisition is sequential and non-atomic: display
size is re-read, the screenshot must agree with `wm size` (including rotation
transposition), and the target package is re-checked after capture. The System UI
target does not require a foreground app. Accessibility fallback bounds are
sanity-bounded instead of being clamped to the display, blank UIAutomator
text/resource/content descriptions become absent, and node/window limits are
enforced. ZIP input, entry, decoded-byte and entry-count budgets are bounded;
decoded depth is capped at 63 and 100k nodes across the dump so the archive
ingestion contract holds (children past those limits are dropped). Node/window
IDs are refresh-local, and app-only cropping is not strict package isolation.
XML uses cached `roxmltree` with DTD disabled and byte/depth/node limits; `png`
validates bounded screenshot decoding. No atomicity or Compose capability is
inferred from successful capture.

`InspectorArchive::open` freezes a bounded byte sequence from one file handle,
then hashes and parses that exact immutable buffer (no path reopen). This retains
up to an additional 172 MiB compressed input while parsing; it is not a low-RSS
streaming claim. Path replacement cannot bind another file to the parsed frame.
It validates the EOCD before ZIP metadata allocation:
single disk, 1–17 entries, <=1 MiB central directory, no ZIP64. It rejects unsafe
paths, duplicates, directories, symlinks, encrypted entries and methods other than
Stored/Deflate. Compressed archive <=172 MiB, total uncompressed payload <=156 MiB,
manifest <=256 KiB, snapshot <=108 MiB, Compose <=64 MiB, screenshot/raw ZIP <=32 MiB,
raw text <=8 MiB, reports/timeline <=4 MiB, unknown optional entries <=1 MiB.
Per-entry reads enforce a real output cap and consume EOF for CRC verification;
byte count and shared SHA-256 must match the manifest before any frame publishes.
No entry is extracted to disk, including embedded raw ZIPs. Unsupported required
entries, missing manifest declarations and incomplete raw ZIP/text pairs fail.

Protocol major 1 is accepted, newer minor versions preserve their version and
ignore unknown fields. Manifest and snapshot package/timestamp/version must
match; Compose sidecar package/timestamp must match the same frame. Original
window and node IDs/bounds, protocol version, default-window choice and flattened
parent/depth structure are retained. Identity is scoped to archive SHA-256 and
window; node IDs do not establish cross-frame correspondence. Limits include
64 windows, 100,000 total nodes, hierarchy depth <=128, strict JSON duplicate
rejection and structure budgets. Snapshot and Compose-sidecar JSON explicitly
allow <=288 JSON levels: each logical node adds an object and a children array,
plus envelope/leaf fields. A real 79-level Compose tree reaches 163 JSON levels
and must not be rejected by the shared JSON parser's default 64-level cap. That
default still applies to other module inputs and archive manifests. Imports
retain every node within these limits rather than silently flattening/truncating
deep trees. These intentionally bound imports more tightly than every Kotlin
configuration, so large/deep archives can be rejected rather than truncated.

UI-independent three-panel contracts are `hierarchy(window,offset,limit)` (<=200
nodes), `canvas(window)` (same physical-pixel display/window bounds), and
`properties(window,node)`. Nodes now retain the bounded structural projection
Compose displays: node text (<=64 KiB), semantics role, and the protocol
`ViewAttributes` fields used by the Layout/Drawing/Interaction sections. Raw and
semantic property maps are capped per node (256 entries, 512-byte names, 8 KiB
values) and by a 4 MiB archive-wide budget; once the budget is spent later nodes
keep structural attributes but no property map. The Compose inspection sidecar
now also yields a bounded per-node projection for the Compose category that the
host only renders while the Compose Agent toggle is on: node name/source/
recompose+skip counts/systemCreated/flags, parameters/modifiers/merged+unmerged
semantics (value depth <=8, <=512 rows per list, <=200k retained values total,
4 KiB per text value) and state-read groups (<=20k reads) plus the existing
coverage records. Malformed optional sidecar content falls back to an empty
projection instead of rejecting the archive. Screenshot bytes are retained after
the same integrity validation so the host can decode and draw the canvas image;
the adapter itself never decodes pixels.

Compose metadata retains frameId/generation/mode, producer-declared privacy and
completeness, capability availability and exact coverage identities/states/counts.
Details/parameters/modifiers/semantics are **not** loaded into presentation state,
and `details_available=false` is unconditional. A producer's `COMPLETE` or
`SAFE_REDACTED` declaration is not promoted to a claim that this adapter verified
full Compose capability or safe export. Coverage/truncation reasons and detailed
content remain outside this initial read model. Full-fidelity archives stay
sensitive even when the returned projection omits values. There is no export,
AI upload or persistence path here.

For read-only archive diagnosis without printing runtime values:
`cargo run --locked --offline -p aps-layout-inspector --example archive_probe -- <capture.zip>`.

All I/O/decompression/parsing is blocking: schedule it on a background executor.
Cancellation is checked while reading entries and around source digest scans;
hashing, ZIP metadata construction and JSON decoding do not offer a hard latency
guarantee. ZIP decode is in-process and has byte/structure caps, not an RSS/CPU
sandbox. Same-input Kotlin parity, malicious/archive fixtures, screenshots,
multiframe/Compose interaction, native UI and packaging need separate validation.

The host-side `InspectorProtocolSession` also accepts one bounded
`inspector.frame.open` request through the shared `aps-protocol` envelope. It
binds session ID, source digest and strictly increasing generation, validates an
exact package/frame/window/node schema, enforces 64-window/100,000-node/200-row
limits, and returns only the first bounded hierarchy page. Cancellation is
acknowledged through the protocol; no field implies device or process exit.
This is a host ingestion contract for an already-authorized Agent transport,
not a live ADB capture implementation. The GPUI Inspector entity consumes the
same validated frame publication contract as archive import, while Android
Agent/socket transport wiring remains a separate implementation gate.

The authorized archive dependency is pinned `zip = =9.4.0` (MIT, MSRV 1.88), with
default features disabled and only `deflate-flate2-zlib-rs`. Metadata and public
upstream security advisories were inspected on 2026-11-06; this is not an audit
or a claim that future advisories cannot affect the pin.

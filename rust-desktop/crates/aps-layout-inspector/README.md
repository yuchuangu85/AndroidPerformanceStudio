# Native Inspector archive and compatible ADB adapter

Reads the existing **ZIP** `agentperf-inspector-capture` archive versions 1 and 2,
not a replacement JSON format. The contracts are the retained Kotlin
`CaptureArchiveCodec.kt`, `CaptureArchive.kt`, `ProtocolModels.kt` and
`ComposeInspectionModels.kt`. Android Agents remain Kotlin/Java. Archive import
performs no ADB or injection. The separate blocking `capture_compatible` adapter
uses `aps-adb` location/serial validation and cancellable, bounded ProcessRunner
commands: `wm size`, `wm density`, UIAutomator XML to `/dev/tty`, and binary
`screencap -p`. It does not install helpers or change device settings. The host
provides manual refresh only, and publishes after every stage succeeds.

Compatible capture is a visible-package accessibility projection, not a selected
PID's runtime View tree or full Compose inspection. XML and screenshot are
sequential/non-atomic; display rotation, size, bounds and PNG decode budgets are
validated. Package identity comes from XML; ambiguous app roots are rejected.
Node/window IDs are refresh-local, and app-only cropping is not strict package
isolation. XML uses cached `roxmltree` with DTD disabled and byte/depth/node
limits; `png` validates bounded screenshot decoding. No atomicity or Compose
capability is inferred from successful capture.

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
64 windows, 100,000 total nodes, strict JSON duplicate rejection and structure
budgets. These intentionally bound imports more tightly than every Kotlin
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

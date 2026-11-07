# Verified GPU artifact index

Read-only metadata and SHA-256 indexing adapted from the existing Kotlin
`AgiArtifactIndexer` in `gpu-inspector-integration/agi-artifact-index`.
No AGI implementation, GPU decoder, device capture, process launch, HTML render,
file export, index persistence or recursive filesystem search is included.

`GpuArtifact::import` reads one explicitly selected regular file (8 GiB maximum),
detects a tentative format from extension/32-byte prefix and computes the shared
`SourceDigest`. Classification/open-route metadata does not prove an artifact's
parser validity, tool availability, GPU compatibility or permission to execute.
The raw artifact is not copied to UI state. Absolute paths are deliberately not
Debug-formatted; they remain local-only and must not enter telemetry or AI input.

`resolve_location` checks every registered location, byte size and SHA-256; a
missing first copy does not prevent another valid copy being selected. Distinct
states are Available, Missing, SizeChanged, ContentChanged and Unreadable.
`relocate` accepts only a user-selected copy with the same size and digest.
`GpuArtifactIndex::import` deduplicates by digest, keeps all bounded locations and
does not reinterpret a renamed duplicate as a different format. Limits are
10,000 artifacts, 64 locations each and 200 items per presentation page.

Hash scanning is synchronous; run imports and verification off the UI thread.
Cancellation is checked around hashing, not during it. Large-input hard cancel
latency, content mutation races, durable storage and same-input Kotlin parity
remain explicit qualification work, not implied by code presence.

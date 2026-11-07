# aps-frame

Offline Rust frame analysis for the existing `dumpsys gfxinfo ... framestats`
text input, including repeated `Window:` / `---PROFILEDATA---` sections, modern
named columns and independent trailing separators on headers and rows. Unknown
columns remain in the source row. Malformed rows are counted in explicit warnings;
an unrecognized file is rejected. Inputs are capped at 256 MiB / one million frames.

`FrameSession::open` / `parse_gfxinfo` parse once. `page(&FrameQuery)` filters by
window, half-open device-monotonic timestamp range and deadline verdict, with at
most 512 returned rows. Both whole-session and filtered summaries provide
classified/missing denominators, duration percentiles and deadline-miss rate.

Budget provenance is explicit: platform deadline, frame interval, inferred nearby
vsync interval (only 4–50 ms within the same window), or unknown. Nonzero gfxinfo
flags are ineligible for deadline classification. Missing/invalid timestamps do
not become zero-duration evidence. A frame deadline miss is not a platform jank
signal: gfxinfo imports leave `platform_jank` unknown and never fabricate its rate.

Scope remains offline gfxinfo, not FrameMetrics/JankStats Agent transport,
Perfetto FrameTimeline attribution, SurfaceFlinger causality, a cross-artifact
clock mapping, live capture or a complete Compose frame-workspace replacement.
Raw timestamp values belong to the imported device monotonic domain, never host
wall-clock time. No default 60 Hz assumption fills unknown budgets.

Tests have been written but not executed, following the request to defer testing
until the complete rewrite is implemented. Compilation, parity, real-device,
cross-platform and packaged-release validation are separate pending gates.

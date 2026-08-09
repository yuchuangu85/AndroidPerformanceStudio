# Capture artifacts and evidence status

Android Performance Studio keeps each captured or imported evidence file in its specialist profiler model,
and also records a small versioned **Capture Artifact** envelope. The evidence status shown by supported
profilers includes:

- the original producer (or **Unknown** for imports whose producer cannot be proven);
- format and SHA-256 content identity;
- privacy-safe device/process identity when available;
- available capabilities and whether the artifact is complete, partial, or unknown;
- limitations, warnings, and any explicit fallback used during analysis.

An Import records Android Performance Studio as the application that performed the import, not as the
original producer. Raw ADB serials are redacted from artifact metadata by default.

Perfetto Native Heap, Java Heap, FrameTimeline, and Startup root-cause evidence use the pinned Trace Processor
version supported by the application. If that tool is unavailable or incompatible, the UI reports an actionable
error. Native Heap can use its documented wire fallback only for tool availability/compatibility failures; a
damaged trace or SQL/schema failure is not silently reinterpreted.

FrameTimeline is authoritative for bounded Android 12+ frame trace imports. Live Frame Observation remains a
separate low-latency workflow. Startup Perfetto evidence is marked correlated only when a Clock Mapping with an
error bound of 5 ms or less is available.

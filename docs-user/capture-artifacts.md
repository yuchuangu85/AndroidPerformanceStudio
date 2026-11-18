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

### From a slow frame to a reviewed source change

1. Select a measured deadline miss or a platform-jank frame. **Ask AI where to trace** uses the selected frame's timings, not your project. A rendering stage is not a method measurement.
2. Open a local Kotlin or Java file in **Source Workspaces**, then return to the frame and generate a Trace patch for a synchronous method. Review the diff and copy it or save it to a new `.patch` file. Check it with `git apply --check` in the matching project before applying it yourself. APS does not edit, build or install your application.
3. Rebuild/install the instrumented app, refresh the local source snapshot and reselect the process. Use **Capture FrameTimeline** (Android 12+) while repeating the interaction. In frame details, compare the probe's overlap with the actual frame window and the observed CPU time on that thread. Missing CPU evidence is **unknown**, not zero. Overlapping or nested spans must not be added.
4. On target-scoped recaptures, **Open probe in source** uses the locally recorded export and file digest; no model or source-upload permission is needed. For a proposed edit, the same probe must overlap the main or render thread in at least two different problem display frames. Enable workspace source sharing, opt in to the excerpt for this run, and inspect the approval. Only the bounded method excerpt and current evidence are included, not previous chat. Outdated/ambiguous source, missing citations and invalid replacements do not produce a patch.
5. **Compare runs** keeps up to eight captures per group while the workspace is open. Add repeated baseline and after-change runs of the same interaction, device, process name and reported display settings. Re-adding the same trace is rejected. Read the median of per-run P95s, classified deadline misses and their percentage-point change alongside probe coverage. Imported traces without target provenance are useful for interpretation, not this comparison. Remeasure after removing probes; neither an overlap nor an improvement in one sample proves a general speedup.

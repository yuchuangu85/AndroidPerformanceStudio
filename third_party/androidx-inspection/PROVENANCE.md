# AndroidX Inspection provenance

The AOSP UI Inspector agent compiles against `androidx.inspection`, which is **not published to
Google Maven or Maven Central** — Android Studio ships it as an internal prebuilt
(`//prebuilts/tools/common/m2:androidx.inspection.inspection.1.0.0`). Its upstream source is public,
so APS vendors the Java API subset it needs and builds it here instead of copying a Studio artifact.

- Upstream: `platform/frameworks/support`, subdirectory `inspection/inspection`
- Revision: `4da03982d44f5a668172df67c920114ceca6dc2b` (branch `androidx-main`, verified 2026-10-28)
- Commit time: `2026-10-28T11:49:43Z`
- Upstream subtree tree: `a0eecdea404c2070a607e1d1f3b05dcaadf73fcd`
- Archive: `https://android.googlesource.com/platform/frameworks/support/+archive/4da03982d44f5a668172df67c920114ceca6dc2b/inspection/inspection.tar.gz`
- Archive SHA-256: `87c388d1ee338d846e13f13b48366a549fc1540e9835dc6bf54bcd1338739c69` (downloaded 2026-10-28)
- License: Apache License 2.0; original per-file notices are preserved.

`src/main/java/androidx/inspection/` is an unmodified copy of that archive's Java sources. The
upstream module also carries native sources (`src/main/native`) and a Robolectric/androidTest suite
that APS does not vendor yet.

The eight Java sources and `README-AOSP.md` (upstream `README.md`) are byte-identical to the previous
snapshot at `b2dcba37aea417626ed77a230e961f405d893c4c`; this refresh changes only the provenance pin.
Gitiles regenerates tar metadata when serving an archive, so the SHA-256 above identifies the
download used for this refresh, not a promise that later archive downloads have identical bytes.
The immutable commit and subtree tree identify the upstream source independently of that packaging.

## Why only the Java API is vendored

The AOSP UI Inspector payload and View inspector reference exactly six types from this artifact:
`ArtTooling`, `Connection`, `Inspector`, `InspectorEnvironment`, `InspectorExecutors` and
`InspectorFactory`. They never call `ArtToolingImpl`/`DefaultArtTooling`, which are the only
classes that `System.loadLibrary("art_tooling")`; the UI Inspector agent implements its own
`ArtToolingBridge` inside `lib_ui_inspector_agent.so` instead. The upstream native module is
therefore not required for the agent bundle.

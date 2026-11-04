# AOSP slicer provenance

The Compose inspection agent rewrites DEX entry points to capture array parameters, which needs the
`slicer` dex IR/instrumentation library.

- Upstream: `platform/tools/dexter`, subdirectory `slicer`
- Revision: `d992a222ec28b56efa29f9104db060379298049c` (branch `main`, verified 2026-10-28; unchanged)
- Commit time: `2025-02-26T20:23:35Z`
- Upstream subtree tree: `1e04f899c76580168f63e72763647d0d6d9ff95a`
- Archive: `https://android.googlesource.com/platform/tools/dexter/+archive/d992a222ec28b56efa29f9104db060379298049c/slicer.tar.gz`
- Archive SHA-256: `e25550daaa0b98dae11982429fdb59e4c530f8788acc9af3063fc64d57681936` (downloaded 2026-10-28)
- License: Apache License 2.0; original per-file notices are preserved.

`slicer/*.cc` and `slicer/export/**` are unmodified. The upstream `tests/` tree is not vendored.
Consumers add `slicer/export` (for `slicer/*.h`) and `slicer` (for `export/slicer/*.h`) to the
include path, matching the upstream CMake setup.

All 39 vendored files were compared byte-for-byte with the archive on 2026-10-28; no source update
was needed. Gitiles regenerates tar metadata when serving an archive, so the SHA-256 above identifies
this verification download. The immutable commit and subtree tree identify the upstream source
independently of that packaging.

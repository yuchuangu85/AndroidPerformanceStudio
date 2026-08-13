# Upstream Winscope viewer

This directory contains the offline, view-only AOSP Winscope distribution used by Android Performance Studio.

- AOSP source: commit `f41a8085fa0166967dd5ece55dce0796fd079e93`
- Product patch: `patches/0001-add-offline-session-viewer.patch`
- Integrity and provenance: `manifest.json`
- Packaged closure: `dist/` (only assets referenced by the generated entry point plus required local runtime files)

The patch removes capture UI, analytics, remote fonts, coverage instrumentation, and source maps; it adds same-origin automatic evidence loading and local icon fonts. `winscope_proxy.py` and historical hashed bundle copies are deliberately excluded.

Verify with `python3 scripts/verify-aosp-winscope.py`. Refresh from a compatible AOSP checkout with:

```shell
python3 scripts/sync-aosp-winscope.py /path/to/AOSP-WinScope
```

The checkout must provide the pinned generated protobuf inputs under `winscope/deps_build/protos`; their tree checksum is recorded in the manifest.

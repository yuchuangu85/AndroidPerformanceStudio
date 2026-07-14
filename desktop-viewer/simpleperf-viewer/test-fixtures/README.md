# Simpleperf test fixtures

## `aosp-perf.data`

- Source: AOSP `platform/system/extras/simpleperf/testdata/perf.data`
- Pinned revision: `0913958dce781fb91c415e666623e46d3c17b3e1`
- SHA-256: `cb3066f4050d84d3e204a37ca4c479113b7623b663c17a3ee8cae5a85b8238bf`
- Size: `136396` bytes
- License: Apache License 2.0 (AOSP source tree)

The binary is intentionally pinned instead of following AOSP `main`, so Golden results remain reproducible. Future fixtures must record their source revision, checksum, expected parser summary, Android version, ABI, Simpleperf version, and capture scenario when known.

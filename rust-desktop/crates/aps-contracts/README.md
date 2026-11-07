# aps-contracts

Framework-independent vocabulary and validation for Capture Artifacts, Profiler
Sessions, Device Targets, Process Identity, Clock Domains, Provenance,
Capabilities, Artifact Completeness and bounded public-safe errors. Names follow
`desktop-viewer/CONTEXT.md`; data structs are validated values, not an immutable
storage implementation. Call `validate()` after constructing or mutating public
fields, before persistence/publication or crossing process boundaries.

## Identity boundaries

- Typed IDs accept bounded ASCII identifiers; `IdSequence` uses an explicitly
  caller-owned namespace and checked sequence. It does not generate randomness
  or promise cross-run uniqueness if the namespace is reused.
- PID is never a process-instance ID. `compare()` only reports `SameInstance`
  when device/PID/name and explicit boot/start-tick identities match; missing
  start evidence reports `WeakIdentity`.
- `Capabilities` describe information a source can provide, not permission or
  evidence of a complete capture. Partial completeness identifies every missing
  requested capability with a bounded reason.
- Import preserves content digest and original producer in the provenance chain.
  Metadata does not authenticate that the claimed producer is genuine.
- Clock mappings are explicit equal-rate anchor mappings with a validity interval
  and error bound. They reject out-of-domain/out-of-range/overflow values rather
  than extrapolating or comparing unrelated raw timestamps.
- Error messages must be sanitized by the caller. Length/control validation is
  not a secret detector; never place raw stderr/configuration/credentials or
  source content into the public error message.

## SHA-256 reuse

`SourceDigest::hash_reader(reader, max_bytes)` and
`SourceDigest::hash_file(path, max_bytes)` reuse the cache's existing std-only
SHA-256 core through the new `aps_hprof_engine::cache::sha256_reader` API.
The reader API hashes no more than the configured content budget and consumes at
most one extra byte solely to distinguish exact EOF from overflow; it rejects
oversize input rather than returning a digest of its prefix. Interrupted reads
retry and all other I/O failures reject the digest. No temporary file is required
for archive entry bytes. Caller-owned buffered readers/decompressors can perform
additional internal work, so this is a logical input bound, not their CPU/RAM bound.

The file API hashes a single read-only handle and checks that handle and the path
against source metadata before/after the read, including Unix device/inode/ctime.
This is **not a read-duration deadline or cancellation mechanism**: a blocked or
hostile reader still needs a bounded worker. The digest identifies bytes read;
it neither authorizes future path access nor proves a later read has that content.

The temporary path dependency compiles HPROF code into the dependency graph even
though contracts call only its digest API. Extracting the existing SHA utility
into a small shared crate remains migration debt; no new SHA algorithm is added.
Standard-vector and contract test sources are present, but execution is deferred
by the user's rewrite-first instruction.

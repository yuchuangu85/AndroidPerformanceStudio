# Rust HPROF Engine Implementation

Status: implemented with local validation, 2026-10-30. The original proposal remains at
`desktop-viewer/.omx/plans/rust-hprof-engine.md`; this document does not replace it.

The additive 2026-11-05 parser-index cache increment is described in
[Rust Profiler Migration](rust-profiler-migration.md) and the
[engine cache contract](../../memory-profiler/hprof-engine-rust/README.md).
The evidence below remains dated evidence for the original implementation, not
a new release/package qualification of the cache increment.

## Requirements

- Keep the Kotlin HPROF parser, deep analyzer, tests and selectable Legacy path.
- Persist an application setting selecting Legacy or Rust for subsequent loads.
  Existing snapshots keep their own engine and state until closed.
- Support raw Android and standard/converted HPROF, 4/8-byte IDs, heap identity,
  classes, instances, typed fields, arrays, references, GC-root paths, dominators,
  class-level comparisons, mapping, findings, Bitmap metadata, Shark and exports.
- Rust owns its object index and graph. Never reconstruct a complete Kotlin
  HeapDump from a Rust snapshot. Keep source identity and nullable evidence.
- No silent fallback on corruption, source changes, cancellation or OOM. An
  unavailable explicitly selected engine is an actionable error, not Legacy success.
- User override on 2026-10-30: Rust is the default for new/unconfigured profiles,
  standalone pages and backend loads. Explicit saved engine choices remain respected;
  Kotlin Legacy and its complete implementation remain selectable.

## Execution

1. [x] Rust bounded parser/index and source-bound query/analysis engine.
2. [x] Kotlin transport and snapshot/query adapter; independent Legacy adapter.
3. [x] Backend, Controller, settings, source-bound Shark and streaming exports.
4. [x] Local differential fixtures, malformed inputs, lifecycle and memory checks.
5. [ ] Hosted platform matrix, signed release and multi-run performance qualification.

## Wire Protocol Version 1

The sidecar is `aps-hprof-engine`, stdin/stdout only. Stdout contains protocol
frames, stderr diagnostics only. Each frame is a big-endian u32 byte length
(maximum 32 MiB) followed by one structured value. Value tags: 0=null, 1=false,
2=true, 3=signed big-endian i64, 4=UTF-8 string (u32 byte length), 5=list (u32
count, values), 6=map (u32 count, string keys without tag, then values).
Decoders reject duplicate keys, trailing bytes, excessive nesting/counts and
oversize input. Object IDs are i64 bit patterns, never floating point.

Request: `{version:1,id:i64,op:string,args:map}`. Response:
`{version:1,id:i64,sourceDigest:string,status:"ok",payload:value}` or
`{version:1,id:i64,sourceDigest:string,status:"error",error:string}`.
The client validates version, request identity and source digest for every reply.
Each process owns one immutable, managed snapshot; close/eviction terminates it.

Operations and payloads (all nullable fields use null, not invented zeroes):

- `open`: args `path`, `sourceDigest` (64 hex SHA-256), `mapping` (obfuscated-to-original map),
  optional `cacheDir` (absent/null/empty disables caching), optional `graphStorage`
  (`memory`, the default, or Unix-only `disk`) and `graphDir` (required for disk).
  Returns metadata: `format`, `idSize`, `timestampMillis`, `classCount`
  (Legacy-compatible classes including LOAD_CLASS-only metadata), `instanceCount`, `objectArrayCount`, `primitiveArrayCount`,
  `rootCount`, `warnings` (`message`,`offset`), `heaps` (ordered names),
  `cacheStatus` (`disabled`, `miss`, `hit`).
- `histogram`: args `heapName` nullable. Returns `{summary:{objectCount,
  shallowSize,classCount,heapSegments:[{name,sizeBytes,objectCount}]},classes:[
  {className,instanceCount,shallowSize,retainedSize,obfuscatedClassName,
  hierarchyDepth,nativeSize,heapNames}]}`. Class results are metadata, not objects.
  `rawHistogram` returns the same shape before class mapping, for TP validation.
- `instances`: args `classNames` (list), `heapName` nullable, `offset`, `limit`
  (1..4096), `ordinaryOnly` boolean, optional `anchorObjectId`. Global signed-ID order;
  an anchor selects its containing page. Returns
  `{total,offset,rows:[{objectId,index,shallowSize,retainedSize,depth,reachable,
  nativeSize,shallowSizeKnown,className,representedClassName}]}`.
- `detail`: args `objectId`,`arrayStart`,`arrayLimit` (1..200). Returns null or
  InstanceQueryDetail's camelCase fields; `fields` and `references` use FieldValue
  camelCase fields. `referenceChain` rows are ObjectReference camelCase fields.
  Initial reference result is complete for compatibility, subject to frame limit;
  excessive results fail explicitly until paged UI continuation is implemented.
- `identity`: args `objectId`; returns null or `{className,groupingClassName,
  heapName,shallowSize,kind}`; kind INSTANCE, OBJECT_ARRAY, PRIMITIVE_ARRAY or CLASS.
- `dominators`: args `offset`,`limit`,`parentObjectId` nullable (null=root children);
  returns `{total,rows:[{objectId,className,
  shallowSize,retainedSize,depth,parentObjectId}]}` in depth/retained/class order.
  Like Legacy, visible rows exclude class metadata objects. Objects whose immediate
  parent is hidden class metadata are visible roots, keeping their true parent ID/depth.
- `findings`: returns `{leakSuspects,bitmapInstances,activityLeaks,
  lifecycleLeakObjects:[{objectId,className,heapName}]}` with Kotlin model names.
- `close`: returns null, flushes response and exits.
- `evict`: args `cacheDir`, `sourceDigest`; deletes only the matching disk cache
  entry. It does not close the current snapshot or delete its source file.

Source files are managed and hashed by Kotlin before opening; the sidecar rejects
source metadata changes. Shared file-lock leases cover all consumers, including
Shark, lazy arrays and exports. Last-lease release reclaims managed content without
deleting user artifacts. No private HPROF fixtures are committed.

With `cacheDir`, Rust independently verifies the actual source SHA-256 before
loading a checksummed, structurally validated parser index. Cache corruption and
write failures fall back to source parsing within the selected Rust engine, not
to a different engine. Cached indices are restored into memory. The second
increment can stream targets/slots to private temporary disk storage, while
offsets, predecessor graphs and dominator work arrays still remain in memory;
neither mode is complete out-of-core analysis. The frozen codec is now shared
through Kotlin `NativeValueWire`; existing HPROF wire entry points delegate to it.

## Evidence

Local evidence from macOS 27.2 arm64, Rust 1.99.1 and the Gradle JDK 21 toolchain:

- Rust: 49 passing tests; strict Clippy and `cargo fmt --check` pass. Dominators
  are checked against an independent vertex-removal oracle on 3,000 small graphs.
- Memory modules: 550 tests, zero failures, one existing ART test skipped because
  its relative Trace Processor path is unavailable. Parser, analysis, model,
  exports, presentation and app ktlint/Detekt gates pass.
- Application settings: 17 focused store/migration tests pass; desktop compilation
  passes. Presentation includes real Compose page, branch, failure/retry and
  localization tests. These are not native packaged-GUI acceptance.
- Local `:desktop-app:createDistributable` passes. The macOS arm64 application
  includes the Rust executable and SHA-256 file; a real framed open/histogram/close
  round trip against that packaged binary passes and exits cleanly. Local binary
  SHA-256: `4f208ab49fc13921a6b753a5664cab8ae9edc79fc829ff8465a70be81cd2b6a5`.
- Backend differential fixtures cover standard and raw Android formats, 4/8-byte
  IDs, mapping coalescing, nullable native metrics, typed fields, strings, arrays,
  weak edges, global instance pages, visible dominator trees, findings and exports.
- Lifetime tests cover missing engines, failed imports followed by mapping reload,
  explicit engine changes, cancelled dispatcher delivery, cross-process source
  leases, source mutation, protocol bounds and child failure/termination.
- Original Kotlin parser and deep-analysis source files remain unchanged; the
  Shark overload accepts source metadata without constructing an empty HeapDump.

### Representative Private Heap

The local-only `launcher.hprof` is 184,442,700 bytes, SHA-256
`5d0fa5f467c20a4fee38c6a3f9cba2caaa9d97539a5ae813cbb3284f53e7277a`.
It is not committed. Both engines produce the expected App heap: 5,378 classes,
2,073,125 objects, 109,063,260 shallow bytes and 41 lifecycle leak objects.
Instance/array investigation and dominator root queries pass in both engines.

With optional TP/Shark disabled, separate test JVM runs with `-Xmx4g` measured:

- Rust: import 23,305 ms; sampled process-tree peak RSS 1,023,168 KiB.
- Legacy: import 45,040 ms; sampled process-tree peak RSS 4,922,080 KiB.

These are single observations, not cold/warm P50/P95 or a release gate. The peak
is the maximum concurrent sum for the test JVM and its descendants, sampled with
`ps` at a nominal 100 ms plus sampling overhead; Gradle/build processes are not
included. Short peaks can be missed, shared pages can be counted repeatedly and
OS file-cache state was not controlled. No cross-platform ratio is implied.

A separate Rust run with actual TP and Shark enabled passes native/reachable
class metrics (34,945,672 native bytes, 34,944,078 reachable native bytes,
109,030,672 reachable shallow bytes), and Shark completes with 41 candidates and
41 leaks. A final rerun uses the committed test-heap property without an external
init script: import 26,465 ms; sampled total peak 4,128,032 KiB, including Shark's JVM work.
Do not describe the full workflow as using only the TP/Shark-disabled Rust peak.

Reproduce using `LocalHprofEngineAcceptanceTest`, `APS_HPROF_FIXTURE`,
`APS_HPROF_ENGINE_MODE=rust|legacy`, and optional `APS_HPROF_VERIFY_TP=true` /
`APS_HPROF_VERIFY_SHARK=true`. Set the real `PERFETTO_TRACE_PROCESSOR_PATH` and a
4 GiB test heap (`-PapsHprofTestMaxHeap=4g`) when enabling the private sample. No real-data test silently
uploads or incorporates its input into repository fixtures.

```sh
# From desktop-viewer; APS_HPROF_FIXTURE must point to the authorized local sample.
APS_HPROF_ENGINE_MODE=rust ./gradlew :memory-profiler:memory-app:test \
  --tests '*LocalHprofEngineAcceptanceTest' --rerun -PapsHprofTestMaxHeap=4g
```

### Remaining Boundaries

- The native graph is compact in-memory storage, not a persistent disk-backed
  analysis cache or a guarantee of bounded memory for arbitrarily large graphs.
- Requests/responses are bounded to 32 MiB. Oversized complete fields, references
  or findings fail explicitly; partial or empty success must not hide evidence.
- Rust 1.85.1 is pinned for release CI, but the local old-toolchain download was
  unavailable; local compiler validation used 1.99.1. Windows ACL behavior,
  Windows/Linux native execution, cross-architecture packages, hosted CI, signing
  and notarization require their respective environments.
- Rust is now the default by explicit user request, not a claim that the remaining
  platform/release qualification is complete. Legacy is preserved and remains selectable.

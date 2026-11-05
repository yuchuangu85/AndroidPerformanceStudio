# Rust Profiler Migration

Status: second implementation increment, not a completed backend replacement.

## Scope And Preservation

Migrate CPU Simpleperf parsing/projection, HPROF storage/index/cache, and large HAR
parsing/analysis independently. Keep the Kotlin parsers, analyzers and existing
data contracts until end-to-end parity and lifecycle tests justify removal.
No existing parser or analyzer is removed by this increment.

The implementation plan is additive:

1. Lock the retained behavior with existing regression tests and native unit tests.
2. Add independent Rust engines and bounded process protocols.
3. Compare the same immutable input through old and new engines (shadow mode).
4. Complete domain projections and paging without changing evidence semantics.
5. Measure representative workloads and qualify packaged binaries on each host.
6. Switch the accepted backend only after the full domain gates pass. Remove the
   old backend only in a separately reviewed change after those gates pass.

## Current Implementation

### CPU

- Rust bounded streaming framed Simpleperf protobuf parser and queryable call-tree projection.
- No registry dependencies; the existing HPROF typed wire source supplies the codec.
- Explicit input, record-count, frame-size and callchain-depth limits.
- Optional Kotlin import shadow mode checks raw record count and accepted sample
  count using the same SHA-256 input before/after processing.
- A `SUMMARY_MATCHED` result proves only those numeric summary fields. It does
  not prove call-tree, Stack Chart, Firefox, event-unit or timing parity.
- Kotlin parsing, normalization, SQLite and UI projections remain authoritative.
- Native parsing no longer retains a second complete source-byte buffer. Decoded
  records still reside in memory for late lookup resolution.
- `RustCpuProjectionClient` provides source-bound pages with thread/event filters,
  half-open time bounds, forward/inverted trees and sample/event weights. Event
  weights require a single event; unknown filters fail rather than widening scope.
- Each page currently runs a one-shot worker and reparses/reaggregates the input.
  Response size is bounded, but there is no persistent CPU snapshot or projection
  cache yet; this is not a claim of reduced end-to-end query latency.
- Native ancestry IDs are stable across pages and filters but belong to a native
  namespace. Their effective identity includes source digest and direction; they
  are not Kotlin `FlameCallNodeId` values and must not be mixed across snapshots.
- Basic synthetic cross-language tests compare complete named ancestry and
  inclusive/self/sample metrics against the retained Kotlin projector. This is
  not complete frame-canonicalization, transform or Firefox parity.

### HPROF

- Versioned parser-index persistence and reopen, with source offsets and metadata.
- Optional `cacheDir` in the existing framed protocol, and cache eviction.
- Kotlin forwards a cache directory; the Legacy engine remains selectable.
- Index caching is not an out-of-core graph engine. Indices, reachability,
  dominators and retained-size analysis still require native memory.
- Cache integrity, input identity and private storage are required before
  accepting restored indices: content SHA-256 is independently checked, index
  checksums and structural bounds are validated, and Unix cache paths are
  owner-only. Damaged or unwritable caches fall back to source parsing.
- Cache I/O uses bounded buffering and stack-based scalar decoding. A 100,000
  small-object regression counts I/O calls; this is not a latency/RSS benchmark.
- Opt-in `graphStorage=disk` streams strong-reference targets/slots into a private,
  immediately unlinked file. Traversal, dominators and inbound queries use the
  same fallible edge API with a fixed 64 KiB cache. Default remains `memory`.
- This disk-edge mode is Unix-only for now. Non-Unix hosts reject it explicitly;
  their default memory path remains available. Offsets, predecessor graph,
  dominator work arrays and sorting buffers still use memory.

### HAR

- Standard-library bounded streaming JSON reader, processing one HAR entry at a
  time. The full `entries` array is not retained by the native summary engine.
- HAR 1.1/1.2 core fields, phase statistics, status families, byte statistics,
  duration statistics and bounded slowest-entry summary.
- JSON syntax, Unicode, nesting, entry size/count and trailing-data checks.
- Optional Kotlin import shadow mode compares entry count without persisting
  URL, headers, bodies, names, native stderr or raw JSON summaries.
- Default shadow mode still uses existing Kotlin `HarParser` and `NetworkAnalyzer`
  for formal results and retains the complete HAR JSON tree. Its input decoder
  now stream-hashes bytes without retaining a complete raw byte copy, and checks
  cancellation during bounded reads, baseline selection and entry mapping.
- Experimental native mode uses a backpressured entry stream and two bounded
  Kotlin passes: determine the global time baseline, then map entries with the
  retained Kotlin mapper/redactors. Raw entry bytes are not spooled to disk.
- The native path avoids the whole-HAR JVM byte buffer/JSON tree, but retains the
  final call list. `NetworkAnalyzer` is still Kotlin, not a native replacement.
- Synthetic tests compare full redacted models and analyzer results across pages,
  missing/invalid timings, nested SSL, metadata order and malformed entries.
  Strict/lenient JSON, configured limits and arbitrary producer schemas are not
  claimed equivalent for a real-world HAR corpus.

### Shared Transport

- `NativeValueWire` extracts the frozen bounded codec formerly local to HPROF;
  the existing `RustHprofWire` entry points delegate without changing the wire.
- `NativeBinaryQuery` verifies input identity before/after a bounded one-shot
  worker, enforces exactly one response frame, propagates cancellation, and keeps
  native stderr out of results. Hashing is cancellable between bounded chunks.
- Worker arguments use the same canonical source path used for hashing, so
  retargeting the original symlink cannot substitute a different source.

## Opt-In Development Usage

From `desktop-viewer`:

```sh
./gradlew buildRustCpuEngine buildRustHarEngine
./gradlew rustEnginesCheck
```

Build tasks specify the native compiler target and use Cargo `--locked --offline`.
They do not package or enable the engines. Explicit paths avoid picking up an
unrelated executable from `PATH` or silently selecting a cross-target binary:

```sh
export APS_SIMPLEPERF_ENGINE=/absolute/native-engine-rust/target/<host-triple>/release/aps-simpleperf-engine
export APS_HAR_ENGINE=/absolute/native-engine-rust/target/<host-triple>/release/aps-har-engine
```

Equivalent JVM properties are `aps.simpleperfEngine.path` and `aps.harEngine.path`.
On Windows the executable names end in `.exe`. Do not set them to untrusted
binaries: these analysis workers are not arbitrary-code sandboxes.

HAR mode is selected explicitly with `-Daps.harEngine.mode=native-experimental`
(native failures are rejected) or `native-with-legacy-fallback` (visible fallback
warning). The default is `shadow`; cancellation never becomes fallback success.
HPROF disk edges require `-Daps.hprof.rust.graphStorage=disk` on a Unix host and
use the host's `hprof-graph-rust` directory. Private handles reclaim edge files on
close/failure/process exit; they never delete original captures or parser caches.
CPU query pages are currently exposed through the client API and native CLI,
not substituted into Compose's accepted SQLite/UI projection.

CPU imports write `rust-shadow.properties` inside the new session directory. HAR
imports write a report under the network data directory's `native-shadow/`.
These files contain only comparison status, source digest, metric names and
comparison scope. Engine failures do not replace accepted Kotlin results;
cancellation remains cancellation, not a fallback success.

Actual native/Kotlin smoke tests are opt-in and explicitly skipped when the
test binaries are not supplied:

```sh
APS_SIMPLEPERF_ENGINE_TEST=/absolute/aps-simpleperf-engine \
  ./gradlew :simpleperf-viewer:simpleperf-application:test --tests '*OfflineProfileImporterTest'
APS_HAR_ENGINE_TEST=/absolute/aps-har-engine \
  ./gradlew :network-profiler:network-app:test --tests '*RustHarShadowTest'
```

## Local Verification: 2026-11-05

On the current macOS arm64 host:

- `buildRustCpuEngine`, `buildRustHarEngine`, `rustEnginesCheck`: passed. The
  aggregate gate runs rustfmt, native unit/integration tests and strict Clippy.
- Rust tests: CPU 14 passed, HAR 13 passed, HPROF 64 passed. One pre-existing
  opt-in private large-heap test was ignored; no private artifact was loaded.
- Kotlin: 54 targeted tests passed with no skips, covering host process handling,
  shadow validation/cancellation, retained CPU import, actual native CPU/HAR
  summary comparison, network entry wiring and HPROF transport.
- `:desktop-app:compileKotlin`: passed.
- KtLint on the changed Kotlin files and `git diff --check`: passed. A broader
  Memory module lint invocation remains red on pre-existing AI-context/page
  formatting; those unrelated files were not changed.
- No native GUI, representative large-input RSS/latency, Windows/Linux runtime,
  CPU/HAR release packaging, signing or notarization acceptance was performed.

No existing implementation was removed. These are first-increment results;
second-increment evidence is recorded separately below.

## Second-Increment Gates

- [x] CPU bounded streaming record reads, query filters and paged native ancestry.
- [x] CPU basic forward/inverted/sample/event metrics compared with Kotlin using
  the actual native query worker on synthetic profiles.
- [x] Shared codec extraction with retained HPROF protocol regression coverage.
- [x] HAR optional entry-stream model adapter with Kotlin-owned redaction.
- [x] HPROF optional streamed disk edges and memory/disk graph-oracle comparison.
- [ ] Representative profile/HAR/heap corpus and RSS/latency qualification.
- [ ] Cross-platform native deployment and full default-backend replacement.

## Pending Gates

The first increment includes executable native unit tests, Kotlin process and
fallback tests, and opt-in native/Kotlin synthetic summary comparisons. These
must stay distinct from the remaining acceptance gates below.

- [ ] CPU stable ancestry IDs, sample/event/traced-time unit parity, all filters,
  forward/reverse trees, transforms, incomplete stacks and Stack Chart output.
- [ ] CPU streaming import/index ownership and bounded viewport/page queries.
- [ ] HPROF disk-backed graph/edge storage and genuinely bounded-memory dominators.
- [ ] HPROF cache quota, lifecycle/lease-aware reclamation and crash recovery.
- [ ] HAR redaction/model conversion, skipped-entry semantics, normalized clocks,
  missing/invalid timing semantics, percentiles, concurrency and connection reuse.
- [ ] Representative authorized large fixtures, RSS/latency benchmarks and
  cancellation/timeout/crash tests; no private artifacts in source control.
- [ ] CPU/HAR release packaging, executable architecture and digest validation,
  signing/notarization inventory and Windows/Linux runtime qualification.
- [ ] Full default-backend switch and separate legacy-removal decision.

## Second-Increment Verification: 2026-11-05

On the current macOS arm64 host, the latest focused Gradle run passed:

- Host/native transport: `NativeValueWireTest` 3, `NativeBinaryQueryTest` 4,
  `NativeSummaryShadowTest` 4.
- CPU: `RustCpuProjectionClientTest` 2, plus the retained parser, profile-model,
  profile-analysis and importer suites.
- HAR: `HarParserTest` 8, `NativeHarParserTest` 7,
  `HarImportParserTest` 7, and the network application/model suites. The run
  included real native HAR parity with zero skipped tests when the explicit
  native binary was configured.
- HPROF: `RustHprofParityTest` 14, `RustHprofProcessTest` 17,
  `RustHeapSnapshotSourceTest` 16. The parity test covers the committed rich
  fixture across standard/Android and 4/8-byte variants, including opt-in disk
  edges, parser cache reuse, pages, dominators and cleanup.
- `desktop-app:compileKotlin`: passed. Changed CPU Kotlin sources passed the
  focused KtLint/Detekt checks; parser-har focused KtLint/Detekt also passed.
- Native `rustEnginesCheck`: passed after the second increment, including Rust
  fmt, tests and strict Clippy for CPU, HAR and HPROF.

These are local source/test and synthetic-or-committed-fixture evidence only.
No Windows/Linux native execution, signed packaged app, large private HPROF
RSS benchmark, large HAR RSS/throughput benchmark, or release/notarization
qualification has been performed. No old Kotlin implementation was deleted or
made the default CPU/HAR backend.

Synthetic tests, scalar summary agreement, native unit tests, UI behavior,
private-artifact acceptance and cross-platform release qualification are distinct
evidence classes. No performance multiplier or production equivalence follows
from the first increment.

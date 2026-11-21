# HPROF Import Performance

Measured locally on November 18, 2026. This is an import/data-readiness result,
not a first-visible-frame, packaged-app, cross-platform or release-publication
qualification.

## Preserved Behavior

- The host and worker retain all three complete SHA-256 checks. Original file
  handle/path identity, bounded streaming reads and mutation detection remain.
- Reference and native readers check source identity at entry, bounded object
  and byte batches, completion and failure. Lazy object-value reads retain
  their independent checks.
- Native ownership preserves inherited/shadowed fields, first non-null thunk
  references, missing versus zero evidence and original last-Cleaner-wins order.
- Histograms preserve class-object inclusion, mapped class grouping,
  heap-local same-class-ancestor suppression, unknown sizes and native values.
- Retained summaries use the same visible dominator roots as paginated queries,
  including objects dominated by hidden class nodes.
- Snapshot publication remains complete and atomic. Findings are not deferred
  or represented as zero while pending. Filters and AI availability do not change.
- Response budgets, cancellation and timeouts remain. Omitted heap results are
  cached only as bounded encoded projections in the owning worker, never on disk.

## Implementation

1. `aps-hprof-engine/src/native/scanner.rs`: cache inherited layouts and read
   only selected references and integer sizes through the original buffered
   source. Cleaner, thunk and registry batches are sorted by file offset;
   assigning sizes follows original Cleaner order.
2. `aps-primitives/src/sha256.rs` (originally `aps-hprof-engine/src/sha256.rs`): use macOS CommonCrypto SHA-256, with the
   original portable implementation on other platforms. The ABI is checked
   against the installed Apple SDK. No new dependency or external command is
   required at runtime.
3. `aps-hprof-engine/src/histogram.rs`: compute all-heap and requested per-heap
   histograms in one node scan and one dominator traversal. Each heap retains
   independent ancestor counts. Ordinary histogram queries use the same engine.
4. `aps-memory/src/worker.rs`: reuse omitted per-heap projections from a
   source-scoped cache capped at 32 MiB and 64 entries. Cache hits still validate
   the source and graph. Retained summaries no longer construct paginated rows.
   Final responses are encoded once, not twice.
5. `aps-app/src/import/memory/state.rs` and `classes.rs`: share an immutable
   classifier tree and cache visible paths/warning markers across renders.
   Refresh, reset, expansion overrides and default heap visibility invalidate
   the relevant cache; existing callback data stays immutable.

## Measurement Method

- Input: `launcher.hprof`, 184,442,700 bytes.
- SHA-256: `5d0fa5f467c20a4fee38c6a3f9cba2caaa9d97539a5ae813cbb3284f53e7277a`.
- 2,466,900 heap objects and 34,506 represented class objects; the snapshot
  reports 2,501,406 total objects and 34,506 class aggregation rows across 3 heaps.
- Both runners use Release opt-level 3, Thin LTO and one codegen unit.
- Old runner and worker were preserved before these optimizations. The old
  runner retains the old host hashing implementation, rather than timing an old
  worker behind an already optimized host.
- Two warm-ups per binary, then seven sequential paired samples, alternating
  AB and BA order. No builds or test jobs were running during sampling.
- Existing OS page cache was not purged; these are repeated local imports,
  not cold-disk measurements. No confidence interval is inferred from seven samples.
- Timed work: host source digest, process/session open, complete typed snapshot
  including findings and any heap fallback, and default App-heap presenter work.
- Not timed: file picker, GPUI scheduling, control construction, layout, GPU
  presentation and the actual first visible frame.

## Paired Import Results

All values below are sample medians. Phase medians are not additive.

- Complete data/presenter readiness: **6,436.442 ms -> 3,155.108 ms**.
  **2.040x faster; 50.981% less time; 3,281.334 ms saved.**
- Host full-file SHA-256: **431.218 ms -> 61.984 ms**.
  6.957x faster; 85.626% less time. Includes file I/O and identity checks.
- Worker open, including its two hashes, parsing, graph, native ownership,
  retained analysis and query indexes: **4,876.753 ms -> 2,367.103 ms**.
  2.060x faster; 51.461% less time.
- Complete snapshot plus transfer/decoding/fallback: **1,124.816 ms -> 723.655 ms**.
  1.554x faster; 35.665% less time.
- Presenter-equivalent computation: **11.496 ms -> 12.300 ms**.
  No speedup is claimed for this sub-millisecond difference. It does not time
  the optimized GUI render cache.

Old complete-import samples, milliseconds:
`6328.127458, 6551.842583, 6340.189209, 6532.095333, 6436.441875, 6503.204917, 6350.741542`.

New complete-import samples, milliseconds:
`3105.785334, 3141.168959, 3155.107500, 3187.320417, 3150.678958, 3176.281708, 3194.078291`.

Separate component tests, not additions to the import totals:

- Native ownership, seven alternating samples with complete vector equality:
  **1,713.625 ms -> 82.116 ms**, 20.87x faster, 95.2% less time.
- SHA-256 on file contents already in memory, seven alternating samples:
  **430.357 ms -> 52.654 ms**, 8.17x faster, 87.77% less time.
- One diagnostic histogram sample: individual scopes 375.583 ms versus shared
  bundle 244.394 ms. One retained-summary sample: paginated 208.751 ms versus
  direct 0.550 ms. These single-sample values are not statistical speedup claims.

## Functional Evidence

- An independent preserved old worker and the optimized worker passed **47
  exact comparisons** on the real file: full typed snapshot, all class rows and
  all heap rows/summaries, complete first-page findings and totals, presenter
  trees/summaries, histogram/instance/dominator/findings pages, and four selected
  object details including fields, root chains and inbound references.
- Input digest, object/class counts, 76 leak suspects and 372 bitmaps matched.
  Counts are heuristic analysis output, not proof that objects leak.
- Core regression suite: 136 tests passed across `aps-hprof-engine`,
  `aps-memory` and `aps-contracts`; optional file/benchmark tests are separate.
- UI cache regressions: 8 tests passed for visibility, expansion, refresh,
  filtering, reset, warning markers and preservation of prior callback data.
- Real-file complete snapshot/fallback parity test passed.
- Final `cargo build --locked --release --workspace --bins` and the repository
  benchmark example build passed. Running that example repeated all 47 exact
  real-file comparisons successfully.
- The classifier render-cache change has regression coverage but no measured
  native GUI first-frame speedup. First-frame work remains outside Import time.

### Existing Validation Limits

Follow-up status: the historical launcher expectations below were independently
reconciled against Studio perflib and replaced with SHA-bound reference values.
All three launcher tests now pass. The two AI assertions and the brittle capture
source-count test were also resolved in subsequent work; a fresh full offline
post-repair workspace run passed 1,847 tests with no failures (14 ignored), and
all 124 focused Memory regressions passed. See
[functional validation](HPROF_FUNCTIONAL_VALIDATION.md) for the independent
reference identity, 2,098 exact API checks, complete analysis-array fingerprints,
async publication repairs and the remaining native-GUI automation boundary.
The bullets below retain the validation history from the performance run.

- The optional historical `launcher_parity` test expects App shallow bytes
  `109,063,260`; both the preserved pre-optimization worker and current worker
  produce `104,666,905`. The same assertion fails before and after; it was not
  weakened or rewritten to turn the test green. Other two historical launcher
  tests pass. The new full-output comparison establishes preservation of the
  current baseline, not resolution of the older Studio/fixture expectation.
- Strict Clippy still encounters the existing `collapsible_if` warnings.
  Clippy with only that lint excluded passed for the three changed core crates.
- Full offline workspace tests were also attempted. Concurrent, out-of-scope
  Phase 3 AI edits first caused missing-symbol compile errors; a retry compiled
  but failed two `aps-ai::model::tests` assertions:
  `clear_source_context_is_hprof_scoped_idempotent_and_preserves_phase1_scope`
  and `memory_hprof_rejects_source_candidates_only_in_this_mode`. Those AI files
  were not changed by this optimization. Do not report the entire workspace
  suite as green.
- Apple Silicon execution and local portable/native digest parity were tested;
  Intel macOS and other operating systems were not executed. A single SHA
  update larger than `u32::MAX` was not allocated for testing.

## Reproduce

Set `APS_LAUNCHER_HPROF` to the explicitly selected local file. Keep an old
worker and old benchmark runner before rebuilding if measuring both versions.

```sh
cargo build --locked --release --workspace --bins
cargo build --locked --release -p aps-app --example hprof_import_benchmark
target/release/examples/hprof_import_benchmark after "$APS_LAUNCHER_HPROF" target/release/aps-memory-worker
target/release/examples/hprof_import_benchmark verify "$APS_LAUNCHER_HPROF" "$APS_BASELINE_WORKER" target/release/aps-memory-worker
cargo test --locked -p aps-hprof-engine -p aps-memory -p aps-contracts
cargo test --locked -p aps-app import::memory::state::tests
```

The benchmark emits JSON timing/counts, not heap contents. `verify` compares
full results in memory and emits only a pass receipt. Comparing an old worker
using the new runner is valid for result equality, but it does not reproduce
the old host SHA cost for end-to-end timing.

The full local timing samples, pair order, preserved binaries and validation
logs for this run are under `/tmp/aps-hprof-optimization-20261118/`.

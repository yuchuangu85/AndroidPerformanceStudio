# HPROF Functional Validation

## Scope and Status

This records the independent, read-only correctness audit performed on
2026-11-18, followed by a minimal update to the optional launcher regression
tests and the mainline automated functional validation below. The independent
audit alone does not establish Memory UI, capture, mapping or dominator
interaction correctness; each validation scope is stated separately.

The reference oracle was the installed Android Studio legacy perflib analyzer,
not another build of this engine. Its results independently support the current
App shallow total of **104,666,905 bytes**. Merely observing agreement between
pre-optimization and optimized Rust workers was not used as correctness proof.

Only this report and `crates/aps-memory/tests/launcher_parity.rs` were changed for
the independent-reference lane. Product/runtime/GUI code and the tools baseline
were unchanged by that lane. The subsequent mainline async repairs are listed
below. The pre-existing dirty worktree was preserved.

## Input Identity

The explicitly authorized local `launcher.hprof` has:

- Length: **184,442,700 bytes**.
- HPROF ID width: **4 bytes**.
- SHA-256:
  `5d0fa5f467c20a4fee38c6a3f9cba2caaa9d97539a5ae813cbb3284f53e7277a`.

The input was hashed before and after reference analysis and rehashed before
updating the tests. All three optional launcher tests now require that exact
SHA-256 before starting a worker. A same-named but different dump cannot silently
reuse these golden values. The HPROF itself is not a repository fixture and was
not copied into this repository or uploaded.

## Verified Reference Identity

- Product: **Android Studio Rabbit 3 | 2026.2.3 RC 1**.
- Product code/build: **AI-262.10968.63.2623.16898116**.
- Channel: **RC**, not a stable-channel claim.
- Java and javac: the installation's bundled JBR. Java reports OpenJDK
  `25.0.4.1`, build `25.0.4.1+-16818597-b635.79`.
- Cargo used for the isolated Rust probe: `1.99.1 (5f94df478 2026-08-27)`.
- Rust reference comparison: HEAD
  `03c727bec6b96fd4f99f22ff920b43250fd32d4e` plus the existing dirty engine
  sources, identified by a checked source-hash manifest in the local audit.

The following installed-file SHA-256 values were verified against the unchanged
`tools/heap-parity/studio-baseline.json` before and after reference execution,
then verified again before updating these tests:

- `Resources/product-info.json`:
  `20fb94e6c71ea3527c86b003d1822e7eb048c6f8350e336e3015aaf76c2adbea`.
- `plugins/android/lib/sdk-tools.jar`:
  `9aed366191805dea00bc3e8abe4c7d37a6f80a9818e1e30bbea57ab5ac3d7864`.
- `plugins/android/lib/android-profilers.jar`:
  `5a21b8c9f3c41167b66e450fbc68bbe0b49b39c1cf0ac3dacbf7f447c9d714f6`.

The analyzer calls `Snapshot.createSnapshot`, its default
`NativeRegistryPostProcessor`, and `computeRetainedSizes` from the verified
bundled perflib. Inspection of the installed bytecode confirmed:

- `ArrayInstance.getSize` uses element count times element width, without adding
  an array header or alignment.
- `HeapDumpInstanceObject.getShallowSize` delegates to `Instance.getSize`.
- `HeapDumpInstanceObject.getRetainedSize` delegates to
  `Instance.getTotalRetainedSize`.
- The default snapshot postprocessor assigns NativeAllocationRegistry bytes to
  the Cleaner referent, rather than adding the bytes to the Cleaner itself.

These are properties of the verified legacy perflib load contract, not a claim
about physical ART allocation sizes or Studio's separate TraceProcessor backend.

## Existing Baseline Rejection

The tools manifest binds a different input SHA-256:

```text
de569d397b4cf510f8fbf0834d681b8606bf991598b0252d76cd60efad3ecfc8
```

Running `tools/heap-parity/compare.py` with the authorized launcher file correctly
failed at its input hash check, before analysis. The error reported that hash as
expected and `5d0fa5f467c20a4fee38c6a3f9cba2caaa9d97539a5ae813cbb3284f53e7277a`
as actual. The baseline manifest and its gate were not changed or bypassed.
Consequently, the historical whole pinned comparison is **not** reported passed.

Instead, the unchanged `StudioProbe.java` was run separately against the
explicitly authorized current file using the verified reference libraries. Its
stdout was consumed in memory to retain aggregates only. A temporary adapter
using the same perflib APIs added class-object totals, ID-keyed vector
fingerprints, native ownership aggregates and a dominator-union calculation.
It did not implement another HPROF parser.

## Fixed Aggregate Evidence

The following values were measured by perflib and matched by the current engine:

- App ordinary objects: **2,069,044**; shallow **104,177,152 bytes**.
- App class objects: **4,081**; shallow **489,753 bytes**.
- App total objects including class objects: **2,073,125**.
- App UI-projected class rows: **5,378**.
- App total shallow: **104,666,905 bytes**.
- App native ownership: **122,703 owners**, **34,945,672 bytes**.
- App `java.lang.Object[]` count: **86,385**.
- App `java.lang.Object[]` shallow: **2,825,900 bytes**.
- App `java.lang.Object[]` overlapping per-object retained sum:
  **175,106,267 bytes**. This is not the class union retained metric.
- App `java.lang.Object[]` union retained: **109,271,977 bytes**, independently
  summed over **4,683** nodes with no same-App/same-class dominator ancestor.
- All App object-array classes: **201,863 arrays**, shallow **5,300,716 bytes**.
- Zygote native ownership: **244 owners**, **25,395 bytes**.
- Image native ownership: **0 owners**, **0 bytes**.
- All heaps native ownership: **122,947 owners**, **34,971,067 bytes**.
- All heaps ordinary objects: **2,466,900**.
- All heaps objects including class objects: **2,501,406**.
- Raw root count: **390,650** on each side. Count agreement alone does not prove
  root-kind multiplicity, root strength or equivalent root paths.

The audit compared **3 heaps, 21 overlapping scopes, 210 scalar checks and 63
ID-keyed vector fingerprints**, without differences. The scopes include ordinary
instances, object arrays, primitive arrays, class objects and combined scopes.
Vector fingerprints cover the complete ordered ID-to-shallow, ID-to-retained and
ID-to-native-size mappings, including zeros. IDs are sorted unsigned, then each
ID and value are encoded as two big-endian u64s and hashed with SHA-256.

Selected fixed fingerprints, equal between reference and engine:

- App all-node shallow:
  `9384b9c8eb5e30e4f481cecb85b71271c9b5e09475e30af2f501b5d5e873691f`.
- App all-node retained:
  `9ae99adbf988344208b5fde87823e13305d4cb98fdeee39305a6c53d8230b2e9`.
- App all-node native:
  `8ddcf93ba75fd3dc6a0f4d18e25fa620a0d3c979c578504cbbda89732fb07321`.
- App `java.lang.Object[]` shallow:
  `9752fe9ca42b6132ac131ef7075754f4a4b117830d404326f1b35c336b576b91`.
- App `java.lang.Object[]` retained:
  `2039790f9ce5c90829667c79ce3b0ec3558458340229a7100a07d2d931c92daa`.

Additional complete per-class count/shallow/native projections matched for App
**5,378 rows**, Image **30,153 rows**, and Zygote **1,782 rows**. This projection
explicitly uses the UI's represented-class name for class objects, not runtime
`java.lang.Class`. Each class row is encoded by name-byte-length (BE u64), UTF-8
name, then count, shallow and native size (BE u64 each), ordered by class name.
Their SHA-256 fingerprints are:

- App: `629c5b3c5f3d020cf02a23c5c5f235d1c9d6bcd1ae8c344e684ffed90fc53cb7`.
- Image: `a038bd9322156c817df479d059d60d90afd563c01dc8876944a411434ca46657`.
- Zygote: `49fc5eff32a36ffabc0c741e77eb3650b8a914b1360188b6f0f5915b89c7ae21`.

## Historical Golden Reconciliation

Commit `c04d2281` removed the earlier 12/16-byte array-header additions and added
per-class-object static-field sizing, before the current import optimizations.
The recorded `java.lang.Class` instance size is zero in this input, so the
reference's 489,753 class-object bytes come from static-field storage.

Applying the old sizing policy to the current file exactly reconstructs the
historical App shallow expectation:

```text
104,666,905  current independent perflib result
+4,886,108  old 12/16-byte array-header additions
-  489,753  class-object bytes absent from the old sizing policy
-----------
109,063,260  historical assertion
```

The historical Object[] shallow value is likewise exactly reconstructed:

```text
2,825,900 + 86,385 * 12 = 3,862,520
```

Thus neither difference demonstrates lost objects or lost native ownership in
the optimization. The historical retained expectation **93,547,032** and old
Studio comment **93,703,506** are not supported by the current independently
executed reference contract. This audit establishes their obsolescence; it does
not attribute every historical retained-size algorithm change.

The optional test now uses the independently measured values **104,666,905**,
**2,825,900** and **109,271,977**, adds the Object[] count **86,385**, and preserves
the existing class-count, object-count and native-size assertions. All three
launcher tests reject an input SHA-256 mismatch before worker launch.

## Test Reproduction

From `rust-desktop`, with `APS_LAUNCHER_HPROF` set to the authorized local file:

```sh
APS_LAUNCHER_HPROF=/path/to/authorized/launcher.hprof \
  cargo test --offline --locked --release -p aps-memory \
  --test launcher_parity -- --ignored --test-threads=1 --nocapture
```

An isolated `CARGO_TARGET_DIR` may be used to avoid contending with mainline
builds. This command selects exactly these three ignored tests:

- `app_instance_page_latency`.
- `app_heap_remains_available_when_combined_snapshot_exceeds_budget`.
- `app_heap_retained_summary_is_available`.

### Executed Checks on 2026-11-18

The real-file command above was executed with
`CARGO_TARGET_DIR=/tmp/aps-heap-audit.MahR4a/launcher-target` and
`/opt/homebrew/bin/cargo`. All **3 passed; 0 failed; 0 ignored**, with a test-run
duration of **8.95 seconds**. This is regression execution time, not a new import
performance benchmark. The retained-summary test printed App reachable shallow
bytes **104,134,969**, consistent with the independent aggregate evidence.

A negative control selected the same three tests with the absolute path of the
existing `tools/heap-parity/studio-baseline.json` as input, without modifying it.
All three rejected that file with
`APS_LAUNCHER_HPROF must match the independently verified fixture`, before worker
open. Cargo's exit code **101** and **0 passed; 3 failed** were expected and
explicitly checked by the negative-control runner; this is not a failure of the
authorized real-file regression run. To reproduce from `rust-desktop`:

```sh
APS_LAUNCHER_HPROF="$(pwd)/../tools/heap-parity/studio-baseline.json" \
  cargo test --offline --locked --release -p aps-memory \
  --test launcher_parity -- --ignored --test-threads=1
```

Additional focused checks passed:

```sh
cargo clippy --offline --locked --release -p aps-memory \
  --test launcher_parity --no-deps -- -D warnings
rustfmt --check --edition 2024 crates/aps-memory/tests/launcher_parity.rs
```

Clippy used its own isolated
`CARGO_TARGET_DIR=/tmp/aps-heap-audit.MahR4a/launcher-lint-target`. Scoped
`git diff --check` passed. The tools baseline manifest, reference probe and
comparator hashes were checked unchanged. No whole-workspace or GUI test result
is inferred from these focused checks.

## Limits and Evidence Handling

- No runtime data defect was found in the independently checked scope; this is
  not an all-input guarantee. Matching SHA-256 vector fingerprints are strong
  whole-vector evidence, not persisted byte-for-byte vector diffs or a proof
  that hash collisions are mathematically impossible.
- No independent acceptance is claimed for complete graph-edge identity,
  root-kind multiplicity/strength, root-path tie-breaking, per-class
  retained-native projection, findings, mapping/dominator concurrency,
  cancellation/cache/error UI, device capture, layout, rendering or interaction.
- The 63 vector comparisons establish measured-value agreement for this input;
  they do not replace graph-edge diagnostics or GUI/capture tests handled by
  mainline. The full workspace suite was not run in this validation lane.
- No raw heap records, object-ID tables, primitive/string field values or raw
  comparison exports were persisted in this report. Only aggregate metrics,
  hashes and analyzer provenance are retained. Temporary probe compilation and
  execution stayed under an independent local `/tmp` directory; no heap data
  was uploaded or analyzed using unapproved external services.

## Mainline Follow-Up Validation

The independent-reference scope above is supplemented by the following mainline
checks on the same SHA-256-bound launcher file. These are distinct evidence
classes, not an expansion of what the Studio reference adapter itself measured.

### Complete Analysis-Array Comparison

`aps-hprof-engine/examples/analysis_fingerprint.rs` was compiled against both
the preserved pre-optimization engine and the current engine. It streams all
elements into canonical, length-prefixed text / big-endian unsigned-word hashes.
The reader has an independent byte-order and fragmented-read regression test.

All 13 fingerprint sections matched: object metadata, class/field layouts,
heap names, raw root records, graph offsets, every edge and field slot, reachable
depth, predecessor, root-path slots, retained size, immediate dominators, native
sizes and per-node shallow sizes. This includes **2,501,406 nodes**, **6,615,064
edges**, **390,650 raw root records**, and all **122,947 nonzero native owners**
with **34,971,067 attributed bytes**. This was not node sampling.

Receipts: `/tmp/aps-hprof-optimization-20261118/results/analysis-fingerprint-*.json`.

### Exhaustive Bounded-API Comparison

`hprof_import_benchmark verify-full` passed **2,098 exact comparisons** between
the preserved old worker and the current worker. Beyond the complete typed
snapshot and presenter data, it traversed:

- **493,686 instance rows** across all pages of five representative class groups:
  String, Bitmap, Cleaner, Object arrays and int arrays. This is not every class.
- All **495,672 visible dominator-root rows**, including their order and metadata.
- All **490 findings rows** across all four finding kinds and all their pages.
- Histogram sorting/search/case behavior for all four heap scopes, plus selected
  object fields, reference chains, inbound references and dominator children.
- The visible-root retained sum recomputed from the complete paged result.

Receipt: `/tmp/aps-hprof-optimization-20261118/results/verify-full.json`.

### Async Publication Repairs

Additional verification found and repaired existing UI races:

1. Mapping completion is now bound to the originating snapshot ID as well as
   workspace/load generations. A stale completion retires only its own busy
   state and cannot replace another tab's session, data, errors or notices.
2. Independent dominator-parent requests now share a snapshot epoch rather than
   invalidating each other. Duplicate requests do not invalidate the original;
   each completion clears only its own pending state, and errors permit retry.
3. Suspending or replacing a snapshot invalidates pending query epochs and
   clears pending indicators without discarding already-published pages.
   Returning to a tab cannot accept old callbacks or retain a permanent spinner.
   Snapshot reload callbacks also check the snapshot identity.
4. Restoring a tab, activating or reselecting Dominators, and mapping failure
   resume only missing dominator pages. Already-published pages are retained,
   and sibling requests remain independent. Mapping failure publishes its error
   and resumes missing queries only after validating the full request identity.

Regressions cover A-to-B switches with equal generations and file names,
reverse-order dominator completions, duplicates, failure/retry, preservation of
published pages, new-request isolation and generation wraparound. The former
global string-count capture assertion was replaced by per-publication guard
contracts with broken-guard negative cases, not by increasing its expected count.

### Fresh Suite and Native-GUI Boundary

- Final post-repair `cargo test --locked --offline --workspace`:
  **1,847 passed, 0 failed, 14 ignored**, across 122 test targets including
  doc-test targets. Optional
  file/device/manual tests remain separate from this default result.
- Focused `cargo test --locked --offline -p aps-app import::memory::`:
  **124 passed, 0 failed**. The two stale mapping source-contract assertions
  now check the complete guarded error/recovery block, including missing-query
  resumption and preservation of published data, rather than the obsolete
  one-line error arm.
- The three SHA-bound launcher tests were explicitly run: **3 passed**. Their
  new expected values come from the independent reference audit above; wrong
  SHA inputs are rejected before starting a worker. Repeating the three tests
  against the mainline Release worker also passed in **9.27 seconds**; this is
  test duration, not an import benchmark.
- Release app/worker builds, fingerprint-reader regression, focused Memory,
  mapping and cache tests passed. Cache regressions include 14 filter states,
  both class/package arrangements, six sortable columns in both directions,
  null-versus-zero, warning refresh, snapshot moves and old callback data.
- Final `cargo build --locked --offline --release --workspace --bins`,
  `cargo fmt --all -- --check`, and `git diff --check` passed. Full-workspace
  `cargo clippy --locked --offline --workspace --all-targets --no-deps`
  completed without errors but with warnings; this is not a zero-warning result.
  The authorized HPROF SHA-256 was rechecked unchanged after these validations.
- A separate Release instance was started with an isolated
  `APS_RUST_DATA_DIR`. Native interaction could not be accepted: the active
  session was locked (`loginwindow` foreground), and the automation safety
  check refused to send input. No unlock or bypass was attempted. The temporary
  instance was terminated; the user's original app and worker were preserved.
- After the session became unlocked, another isolated Release instance rendered
  Home and opened the native HPROF picker through accessibility. Synthetic input
  did not establish a completed file selection or import. A later foreground
  identity check refused further input, so the attempt was stopped without
  bypassing the guard. The temporary instance was terminated. This limited
  launch/picker check does not establish imported-page or view-switch acceptance.

Final test receipts are under `/tmp/aps-hprof-optimization-20261118/results/`:
`functional-final-verified-workspace-tests.log`,
`functional-final-memory-tests.log`, and `functional-final-launcher-tests.log`.
The final totals are also recorded in `functional-workspace-summary.json`.
Build, formatting and lint logs are `functional-final-verified-release.log`,
`functional-final-format.log`, and `functional-final-clippy.log` in that directory.

Therefore complete native mouse/keyboard/rendering acceptance remains open
despite the data and automated regression results. No full native-GUI,
device-capture or all-platform guarantee is inferred from the passing suite.

## Source Evidence

- `../tools/heap-parity/README.md`, lines 20-30: legacy reference contract and
  explicit coverage limits; its default export excludes class objects.
- `../tools/heap-parity/StudioProbe.java`, lines 11-21: actual reference parser,
  retained computation and overlapping per-object retained sum.
- `../tools/heap-parity/studio-baseline.json`, lines 3-11: pinned build, library
  identities and different baseline input SHA-256.
- `../tools/heap-parity/compare.py`, lines 98-103: reference/input identity gate.
- `crates/aps-hprof-engine/src/parser.rs`, lines 497-506 and 520-533: current
  payload-only object/primitive-array sizing.
- `crates/aps-hprof-engine/src/analysis.rs`, lines 49-63: class-object static-field
  sizing.
- `git show c04d2281 -- rust-desktop/crates/aps-hprof-engine/src/parser.rs
  rust-desktop/crates/aps-hprof-engine/src/analysis.rs`: prior sizing correction.

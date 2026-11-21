# Pinned Studio Heap Comparison

Run from the repository with the existing sample, without downloads or a full app build:

```sh
python3 -I tools/heap-parity/test_compare.py
python3 -I tools/heap-parity/compare.py \
  --studio-contents '/path/to/Android Studio.app/Contents' \
  --hprof '/path/to/sample.hprof'
```

The manifest pins AI-262.10968.63.2623.16898116, Rabbit 3 / 2026.2.3 **RC 1**, the actual installed product metadata and analyzer JAR hashes, and the agreed sample hash. This is not a stable-channel claim. Any identity mismatch aborts before analysis. Cargo must be `/opt/homebrew/bin/cargo` version 1.99.1; Java and javac come from that Studio installation. Cargo runs offline and locked.

Outputs are operational artifacts under ignored `build/heap-parity/`: `studio.json`, `rust.json`, and `comparison.json`. Override with `--out`. They contain object IDs, class names and metrics, but no primitive/string field values. Treat outputs as potentially sensitive heap metadata. The HPROF is read-only and rehashed after both analyzers finish. Scripts stay outside the input folder.

Exit 1 means measured differences. Exit 2 means compared fields matched but coverage is incomplete. There is deliberately no successful full-equality result while unsupported metrics remain. Errors also exit nonzero and print the concrete cause.

## Analyzer Provenance

`StudioProbe.java` directly calls `com.android.tools.perflib.heap.Snapshot.createSnapshot` and `computeRetainedSizes` from the pinned bundled `plugins/android/lib/sdk-tools.jar`. This is the actual legacy HPROF load backend, not Shark or an alternative parser. On this exact build, bundled `android-profilers.jar` exposes `HeapDumpCaptureObject.load(InMemoryBuffer)` and `HeapDumpInstanceObject` wrapping perflib `Instance`. `javap -c` confirms the adapter's `getShallowSize` directly delegates to `Instance.getSize`, and `getRetainedSize` to `Instance.getTotalRetainedSize`. The capture also exposes a separate TraceProcessor path; this probe does not claim to reproduce that path or the whole UI.

Bundled `ArrayInstance.getSize` computes element count times element width, without an added header or alignment. `ClassObj.addInstance` assigns class instance size for non-arrays. These facts explain size-policy differences; the harness itself does not change engine behavior.

## Comparison Coverage

Class rows use heap plus raw class ID, preserving duplicate names/class loaders. Only heap display capitalization is normalized. Primitive arrays have no class ID in Rust's parsed object records; the exporter resolves a unique matching runtime array class definition, leaving missing or ambiguous matches as class ID zero. It never guesses a loader. Class counts, shallow-byte sums and per-object retained-size sums are compared. Retained sums are not a union retained-size metric. Class objects are excluded from the histogram on both sides.

Raw root records retain their original analyzer kind in the extraction. The comparator maps the explicitly equivalent perflib enum aliases to HPROF names and compares each ID/kind multiplicity. Root strengths are null because RootObj does not expose a strength API; null must not be interpreted as strong or weak. Raw root counts are not counts of strong reachability seeds.

By default Studio selects the lowest two unsigned ordinary object IDs plus a deepest reachable object in each heap, then supplies exactly those IDs to Rust. Repeat `--object-id DECIMAL_ID` for explicit selection. Object rows include shallow and retained size, reachability depth, and root chains. Studio chains follow hard reverse references with decreasing computed GC-root distance and unsigned-ID tie-breaking. Rust exports its own traversal chain. Differences are not hidden when tie-breaking differs; this does not claim UI path ordering. Native size remains null in the Rust exporter, so native-size equality is unsupported. Missing fields, missing rows, null and zero are all distinguished.

The report records complete product metadata, pinned hashes, Java/Cargo versions, Rust revision and a deterministic tracked/untracked source manifest, rechecked after analysis. No HPROF contents or machine-local input paths are committed.

# aps-method-recording

Offline ART method-event import and duration analysis. Std-only. It does not
reinterpret Simpleperf sample counts as method calls, and is independent of
Compose/JVM storage.

## Public surface

- `MethodRecordingSession::parse(bytes)` / `open(path)` use default bounds.
- `import_bytes(bytes, ImportLimits)` / `import_file(path, ImportLimits)` allow
  tighter caller bounds, never values above the documented hard caps.
- `header()`, `summary()`, `diagnostics()`, `thread(id)`, `method(id)` expose
  immutable metadata and integrity state.
- `threads_page`, `methods_page`, `events_page`, `top_methods_page`,
  `call_tree_page`, `timeline_page` return at most 512 rows; zero-sized and
  over-limit pages fail. Offsets beyond the end return empty pages.
- `TimelineQuery` optionally selects a thread and half-open primary-clock
  window. Intersecting intervals retain original boundaries: no hidden
  clipping/reweighting. `stack(leaf_node_id)` resolves one selected path.
- Call-tree IDs remain stable for the session. Each thread has a synthetic
  root (`method_id: None`); descendant rows are in preorder. Catalogs retain
  event-only threads and unresolved method IDs rather than losing their data.

## Supported import layouts and evidence boundary

This ports the byte-level **fixture-supported layouts of the existing Kotlin
`parser-art-trace`**, rather than claiming support for every AOSP/Android trace
variant:

- Binary-first little-endian `SLOW` header, versions 2/3/4/5 only.
- v4/v5: 32-byte header with nanosecond start value; thread info packet 0,
  method info packet 1, delta entry block packet 2 and text summary packet 3.
  Entry blocks contain U24 record counts and U32 payload lengths. Signed
  method-word and unsigned timestamp LEB128 accumulators reset per block.
- v2/v3: 32-byte header with microsecond start value, optional following LF
  `*threads`/`*methods`/`*end` tables, then 10-/14-byte records. Absent tables
  use the validated data offset, at least 32. A nonzero record-size header
  field must match the supported version. Bare classic table IDs retain the
  existing hexadecimal-first interpretation.
- Action bits: 0 ENTER, 1 EXIT, 2 UNROLL; reserved action 3 is counted and
  reported, not treated as a call. Method words are shifted by two to obtain
  method IDs; streaming method-table IDs are already normalized indexes.

**Not implemented:** text-first conventional Android `.trace` variants,
other packet schemas or headers, legacy Dalvik v1, wraparound reconstruction,
agent capture transport, rooted-device capture, or external tool export.
Unknown streaming packets stop interpretation and yield a visible partial
result. Truncated headers/packets, invalid UTF-8, arithmetic overflow, and
budget violations fail import rather than returning plausible prefix data.
Trailing partial classic records and leftover block bytes are diagnosed.

## Time and call semantics

- v2 primary values are **thread CPU** nanoseconds, not wall time.
- v3/v5 retain separate primary wall and thread CPU values in this supported
  layout. v4 single-clock selection is **unspecified**: no wall/CPU claim is
  invented without producer clock metadata.
- Producer header start values stay separate from event-relative timestamps.
  They do not establish an epoch or comparability with Perfetto or another
  recording.
- ENTER counts are real call counts. Each matched EXIT increments normal
  completion count; UNROLL has a separate exception-unwind count. Unclosed
  or implicitly abandoned child frames have incomplete counts.
- Adjacent observed events produce a timeline interval only while that
  thread has a nonempty stack. Self time is charged to its leaf; inclusive
  duration sums invocation lifetimes over valid clock segments. Recursive
  invocations therefore contribute separately and method inclusive totals
  can exceed nonrecursive occupancy. Self time is not event/sample count.
- Unmatched exits do not pop unrelated frames. Ancestor exits mark open
  children incomplete before closing the matching ancestor.
- Final open frames stop at **their own thread's last event**, without
  inventing a tail to the global maximum time or capture-stop wall clock.
- Primary clock regressions mark open frames incomplete, clear that stack,
  and do not create negative or cross-discontinuity duration. Regressing
  CPU deltas are excluded while valid primary intervals remain available.
  Missing/invalid CPU deltas are diagnosed; returned numeric totals cover
  observed valid segments only, not fabricated replacement values.
- Root active totals sum active intervals across threads; they are **not
  process wall time**, nor a total obtained by adding recursive inclusive
  values.
- A `data-file-overflow=true` / `=1` summary marker records producer overflow.
  Lack of a marker or other diagnostic does not prove complete on-device
  capture, absence of sampling/overhead, or end-to-end format parity.

## Resource limits

Default: 64 MiB input, 1 million raw records, 100,000 methods, 16,384 threads,
16 MiB catalog/summary text, 16 KiB per text packet/line, depth 512,
1 million call-tree nodes (including thread roots), and 1,024 diagnostic
messages. Raw record budgets include ignored reserved actions. Diagnostic
counts retain all occurrences while representative messages are capped.

Hard ceilings: 512 MiB input, 4 million raw records/nodes, 1 million methods,
65,536 threads, 64 MiB table text, 65,535-byte text packets/lines, depth 2,048,
and 16,384 messages. The file reader uses 64 KiB scratch space and at most
one extra byte to prove that input exceeds its cap. Bounds cover input,
analysis topology, and returned rows; this is an eager bounded import, not a
claim of constant-memory indexing. Time totals use checked arithmetic.

Analysis does not clone complete stacks for every interval. A timeline slice
references a stable call-tree node; stack materialization happens only when
requested. Aggregated top-method rows and tree preorder are prepared once at
import. Timeline/event filter scans remain linear in imported rows, with
bounded output; there is no latency claim for unmeasured large captures.

## Validation status

`tests/offline_trace.rs` contains parser/projection, clock, malformed-input,
limits, pagination, recursion, mismatch and partial-capture regression sources.
Per the user instruction, this lane did **not** compile or execute tests while
implementation was in progress. Full-workspace validation is deferred to the
rewrite integration phase; these sources are not passing-test evidence.

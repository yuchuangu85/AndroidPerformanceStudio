# Experimental Simpleperf Rust Engine

CPU parser, legacy analysis and independent query projection. Kotlin implementations are
retained; this crate does not enable a default Rust engine or modify
desktop-app UI. No complete CPU, Kotlin, SQLite or Firefox projection parity is
claimed. There are no Cargo dependencies or external registry dependencies.
The existing std-only HPROF `wire.rs` is referenced as a source module, without
compiling or changing the unrelated heap engine. Formatting skips that module.

## CLI For Kotlin Shadow Validation

```
aps-simpleperf-engine summarize /absolute/path/profile.trace
```

`summarize-tsv` is an equivalent command used by the shared Kotlin shadow client.

On success stdout contains exactly these eight TSV keys, in this order, with
unsigned decimal values and a final newline:

```
formatVersion\t1
recordCount\t0
sampleCount\t0
lostSampleCount\t0
lostEventCount\t0
totalEventCount\t0
fileCount\t0
threadCount\t0
```

Here `\t` denotes a literal tab. No private symbols, paths, or names are printed
to stdout. Errors use stderr and nonzero exit, without a partial success TSV.
`lostSampleCount` sums `LostSituation.sample_count`; `lostEventCount` sums
`LostSituation.lost_count`. These raw producer counters are not derived loss
percentages and do not establish missing CPU time.

Default maximum input size: 256 MiB. Set `APS_SIMPLEPERF_MAX_INPUT_BYTES` to a
positive integer to override. Inputs must be regular files. Metadata is checked
before reading. A bounded buffered reader decodes one framed protobuf record at
a time and reuses its encoded buffer; there is no whole-file `read_to_end` byte
copy. Read-ahead is capped at limit+1 to catch growth. Decoded records still
remain in memory for late lookups, and query aggregation retains all selected
tree nodes. This is not out-of-core analysis or a bound on decoded memory.
Decoded memory can exceed the input limit. Individual
records are limited to 16 MiB, record count to 1,000,000 and callchains to 512.

## One-Shot Query

```
aps-simpleperf-engine query <path> <threadCsv|-> <eventCsv|-> <start|-> <end|-> <forward|inverted> <samples|events> <offset> <limit>
aps-simpleperf-engine query /absolute/path/profile.trace 101,102 cpu-cycles - - inverted events 0 512
```

Stdout is exactly one existing typed wire frame, not JSON or TSV. Its logical
value is:

```
{version:1,status:"ok",payload:{total:2,offset:0,rows:[
  {id:"aps-cpu-v1:forward/7:0:a:0",parentId:null,depth:0,name:"root",resource:"fixture.so",inclusiveWeight:1,selfWeight:0,sampleCount:1,selfSampleCount:0},
  {id:"aps-cpu-v1:forward/7:0:a:0/7:1:14:0",parentId:"aps-cpu-v1:forward/7:0:a:0",depth:1,name:"leaf",resource:"fixture.so",inclusiveWeight:1,selfWeight:1,sampleCount:1,selfSampleCount:1}
],weightType:"samples",direction:"forward"}}
```

- `threadCsv` contains signed i32 thread IDs, `eventCsv` exact event names. `-`
  selects all; duplicates have set semantics. Empty CSV elements, unknown IDs or
  names, invalid numbers and unknown modes fail, including in zero-row pages.
- Thread selection uses both sample IDs and thread records. The final metadata
  record defines event names; unnamed sample event IDs use `event#<id>`.
  Files, threads and event metadata may follow samples.
- Bounds are optional unsigned nanoseconds, with inclusive start and exclusive
  end. When both are supplied, `start < end` is required.
- `samples` weights every selected sample record as one; different events can
  be combined in sample units. `events` uses raw event_count and rejects a
  selection spanning multiple event IDs, even if later time/thread filters
  would leave only one. This includes an unspecified event filter on a
  multi-event profile. It never silently chooses an event or mixes units.
- Sample counters increment even for zero event_count. In either direction,
  selfWeight/selfSampleCount belong to the original sampled leaf. In inverted
  trees that leaf is the root, not the last caller. Empty callchains add no rows.
- Rows are deterministic preorder, siblings sorted by the numeric tuple
  `(file_id, symbol_id, virtual_address, execution_type)`. Depth starts at zero.
  All returned numeric fields are checked nonnegative signed i64 values.
- `limit` is 0..512, `offset` a nonnegative signed-i64-compatible integer.
  `total` counts all matching tree nodes before pagination. Rows have exactly
  `min(max(total-offset,0),limit)` entries, including for offsets beyond total.
  A page's parent may be outside that page; its full parentId remains present.
- IDs are native strings in `aps-cpu-v1:<direction>/...`, consisting of full
  ancestry with hexadecimal file ID, symbol ID (u32 bit pattern), address and
  execution type. They are collision-free within this native identity scheme,
  independent of record order, sibling insertion, filters, weights, display
  lookups and pagination. They are scoped to the profile's numeric identities,
  not portable identifiers across captures and **not Kotlin node IDs**.
- Parsing, catalog validation, aggregation and response encoding all complete
  before any success bytes are written. Invalid/truncated/oversized inputs,
  mixed event units, metric overflow or wire encoding limits fail with nonzero
  exit and stderr, without a partial success frame. Rows are not truncated to
  hide encoding failures. Transport write failures may still interrupt output.

This command is stateless and reparses the profile for each page. Stdio
`project`, cached sessions, UI/backend switching, Kotlin binary client changes,
Kotlin function-identity grouping, Kotlin node ID parity, transforms, off-CPU
duration and complete CPU/SQLite/Firefox projection parity are not implemented
here. The existing shadow/default behavior is unchanged.

## Framed Protocol

Default CLI (or `--binary`) serves stdin/stdout using the existing HPROF typed
wire: big-endian u32 frame length, then a typed Value. Max frame 32 MiB; strings
4 MiB; integers signed i64. All counts/lengths inside the wire are big endian.
Tags: null=0, false=1, true=2, i64=3, UTF-8 string=4, list=5, map=6.
String/list/map lengths are u32; map keys are length-prefixed UTF-8 without a tag.
See `../../memory-profiler/hprof-engine-rust/src/wire.rs` for the exact codec.

Logical values below are illustrative, NOT JSON transport:

```
{version:1, id:7, op:"analyze", args:{path:"/absolute/profile.trace"}}
{version:1, id:7, status:"ok", payload:{summary:{...}, callTree:[...]}}
{version:1, id:7, status:"error", error:"..."}
```

`ping` and `close` use `args:{}`. Errors preserve a decodable i64 id, otherwise
use 0. Clean EOF closes successfully. Invalid framing terminates; invalid typed
requests return an error and allow another request. Oversized responses and
metrics outside i64 fail explicitly, never silently truncate. Addresses are
unsigned decimal strings. No requests leak allocations or retain profile state.
JSONL/base64 are not implemented; use the typed codec, not the earlier draft.

`summary` uses the eight camelCase CLI keys. `callTree` is a flat preorder list
with parentIndex (-1 for roots), name, resource, fileId, symbolId, virtualAddress,
inclusiveWeight, selfWeight, sampleCount and selfSampleCount. Input Simpleperf
callchains are leaf-to-root; projection reverses them. Weight is raw event_count,
not wall time. Unknown symbols use placeholders; different addresses remain
separate. This legacy `analyze` tree aggregates all events/threads without filtering,
inversion, recursion transforms, off-CPU duration or stable Kotlin node IDs.
Do not compare mixed event weights as if they were one physical unit.

## Parser And Verification

Input is the existing Kotlin/AOSP `report-sample --protobuf` framed format,
not raw perf.data: magic `SIMPLEPERF` (10 ASCII bytes, no NUL), LE16 version 1,
LE32 record lengths, proto2 Record payloads, LE32 zero terminator. Lookup records
are resolved across the whole file, including lookups after samples. Only fields
needed for this experiment are decoded. Unwind results and mangled symbols are
not projected. The Record envelope is fully consumed: recognized oneof fields
use the last value, valid unknown fields are skipped, and malformed trailing
fields are rejected even after a recognized value. Bytes after the zero-length
terminator are rejected. This EOF requirement is deliberately stricter than the
old Kotlin reader, which stops at the terminator without checking trailing data.
The callchain budget is checked before decoding/pushing a 513th entry; accepted
512-frame recursive paths preserve each ancestry level in the basic tree.
Group wire types are not supported; this is not a complete proto2 engine.

Independent synthetic tests cover late lookup resolution, leaf/root direction,
identical ancestry aggregation, address/parent separation, self/inclusive
weights, empty stacks, raw loss counters, and the 512-frame recursion boundary.
Query tests also cover thread/event set intersection, half-open time bounds,
multi-event unit rejection, native ancestry IDs, deterministic pagination,
inverted leaf self attribution and zero-event-count sample counters. Real CLI
tests decode single typed frames, check exact page lengths and nonnegative i64
fields, and reject malformed inputs and encoding/metric overflow without stdout.
Streaming tests exercise fragmented/interrupted reads, every truncated prefix,
input caps before allocation, late malformed records and trailing-growth probes.
These tests do not establish Kotlin or complete CPU projection parity.

```
cargo build --locked --offline
cargo test --locked --offline
cargo fmt -- --check
cargo clippy --locked --offline --all-targets --no-deps -- -D warnings
```

The desktop root provides `buildRustCpuEngine`, `testRustCpuEngine` and the
aggregate `rustEnginesCheck`. Explicitly setting `APS_SIMPLEPERF_ENGINE` or
`aps.simpleperfEngine.path` enables numeric-summary shadow comparison during
imports, not a production backend switch. Kotlin remains the default engine.
No native packaging, production/private profile acceptance or performance claim
follows from synthetic tests or scalar summary agreement. See
`../../docs/design/rust-profiler-migration.md` for the migration gates.

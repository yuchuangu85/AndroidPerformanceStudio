# Process-isolated HPROF adapter

`aps-memory-worker` reuses the existing `aps-hprof-engine::Session` protocol and
its parser, compact heap index, graph, dominators, findings, typed wire values
and SHA-256 implementation. The UI-facing `MemorySession` owns only a child,
bounded protocol messages, metadata and the selected page. It never owns Engine
or Heap and never rebuilds a complete object graph in the UI process.

Build the worker with `cargo build -p aps-memory --bin aps-memory-worker` and pass
its explicit executable path to `MemorySession::open`. The caller supplies the
SHA-256 digest of the explicitly selected HPROF; the worker verifies it before
opening. Inputs are read-only, no cache or disk-graph writes are enabled, input
size is capped at 8 GiB, and children receive a cleared environment. A worker is
trusted application code, not an OS security sandbox; memory limits are process
isolation and protocol bounds, not a per-child RSS quota.

Blocking calls belong on the background executor. The owner kills and reaps the
direct child on cancellation, timeout, explicit close and Drop. Opening allows
600 seconds; queries 120 seconds. The parent allocates at most 2 MiB for a framed
response, pages contain 1–200 rows, class-name filters contain at most 64 names,
and nested lists/strings are capped at 200 items/4 KiB with explicit omissions.
Engine error text, stderr and source paths are not exposed to the UI.

`histogram`, `instances`, `detail`, `dominators` and `findings` are real engine
queries. The UI-facing session now keeps a bounded cache of up to eight encoded
query payloads and 2 MiB total, keyed by operation and exact bounded arguments;
worker failures, cancellation and close clear it. Histogram and findings cache
misses are still computed in full inside the child and then paginated there.
This does not meet a future persistent-query-index latency target, and the worker
can still have a large peak RSS. It does satisfy keeping the heap out of UI
memory.
Array details use engine paging; ordinary fields are paginated in the worker.
Truncated chains/references are explicitly reported by `presentationOmissions`
in the payload. Raw HPROF field/string details remain sensitive local evidence;
this crate implements no persistence, export, AI upload or implicit authorization.

Existing engine fixture parity, same-input Kotlin comparison, worker crash and
cancel stress, RSS measurements, packaging and cross-platform qualification are
separate validation gates, not implied by compiling this adapter.

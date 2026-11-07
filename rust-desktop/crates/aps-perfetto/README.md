# Pinned Trace Processor CLI adapter

This crate reuses the real Perfetto v59.1 executable. It never reimplements trace
parsing. Exact host SHA-256 values match the existing canonical
`desktop-viewer/platform-perfetto/trace-processor-manifest.json`; all five hosts
are declared, but host declarations are not cross-platform validation evidence.
The resolver accepts only explicit package/source-checkout paths, checks SHA-256
before executing `--version`, and rechecks the checksum before each query. There
is no unverified PATH fallback or download. Rust packaging must independently
include the pinned executable and its notices.

The verified CLI API is `trace_processor_shell query --format csv TRACE SQL`.
The actual pinned macOS arm64 executable's `--version` and `help query` metadata
were inspected; no trace analysis/test was executed in this implementation lane.
Upstream sources: `third_party/perfetto/src/trace_processor/shell/query_subcommand.cc`
and `csv_result_formatter.cc`. Existing Kotlin uses the same CSV CLI to query its
warm loopback RPC server. This Rust adapter deliberately starts one bounded
one-shot process for each query and has **no warm RPC context/cache yet**.

`TraceSession::open` accepts only an explicitly selected local regular file,
caps it at 8 GiB, binds its SHA-256 and checks mutation metadata before/after
queries. It does not claim the file is a parseable trace until a query succeeds.
Only the `QueryPreset` enum can construct SQL: TraceBounds, Processes, Threads,
Slices and CpuScheduling, with stable ordering and LIMIT/OFFSET. Each page has
at most 200 rows, with one lookahead row for `has_more`. No arbitrary SQL, network
URL input, SQL file access, debuginfod, device capture or cloud transport exists.

The CLI's CSV formatter does not escape arbitrary quote-containing trace text.
Presets hex-encode bounded name previews in SQL and decode them after CSV
validation; names are capped at 4096 characters. This also distinguishes SQL
NULL from an actual name of `[NULL]`. Returned trace strings are sensitive local
evidence; this crate supplies no export, persistence or AI upload authorization.

ProcessRunner provides bounded stdout (4 MiB), stderr (64 KiB, never displayed),
120-second query timeout, cancellation and child termination/reaping. Run every
operation on a background executor. Digest scanning currently checks cancellation
only before/after a synchronous hash; it does not provide hard cancellation
latency or a time quota during hashing. Ordinary length/mtime mutation checks
after open are not protection against malicious same-stamp file replacement.
`TraceSession` now retains a bounded LRU-like cache of up to eight verified
preset/offset/limit pages, capped at 2 MiB total. Cache entries are bound to the
session's source digest and are cleared when the source stamp changes. This
avoids repeating identical page queries while preserving the existing one-shot
process boundary. It is not a warm Trace Processor RPC context: cache misses
still launch a bounded CLI process and persistent contexts remain a future
performance/parity slice. CPU/Frame/Startup diagnostic SQL parity, large trace
RSS, runtime fixtures, native interaction and signed packaging remain separate
gates.

# aps-cpu

UI-independent, read-only session adapter around the existing `aps-simpleperf-engine`.

- Input: `SIMPLEPERF` protobuf export (`report_sample.py --protobuf`), not `perf.data`.
- Opens a regular file read-only, checks metadata and enforces a streaming input byte budget (128 MiB by default, at most the engine's 256 MiB cap).
- Retains parsed records for the session and caches one call-tree and one top-functions filter projection. Changing offset/limit only copies the requested page (at most 512 rows); changing filters rebuilds the projection once.
- Filters: thread IDs, event names, half-open nanosecond time range, forward/inverted tree, sample/event-count weight. Event-count weight requires a single underlying event type.
- Catalogs include sampled thread/event IDs missing declarations; raw names are never localized.
- `ProfileSummary::thread_count` counts the usable union of declared and sampled thread IDs, unlike the legacy `analyze()` summary which counts declarations only. Summary event names include fallback names. `total_event_count` is raw accounting, not a comparable metric across different event units; use a single-event query for analysis.

## Limits and incomplete migration

The byte budget is **not** a RAM bound: decoded records and an aggregate tree can expand significantly. The host application owns the separate content-addressed snapshot and session-metadata boundary; this crate does not persist profile bytes. There is no subprocess sandbox, persistent index, digest binding, capture, viewport rendering, cancellation, or Compose/Kotlin parity acceptance here. Close/drop releases the session; `clear_projection()` releases only the current cached projection. Native GUI, real large-profile performance, and packaged release acceptance remain separate gates.

Test sources exist under `tests/`, but executing tests is deferred by the user's full-rewrite-first instruction.

## CPU view models

`top_functions(query, offset, limit)` returns up to 512 rows sorted by self weight,
then inclusive weight and stable identity. A known function identity uses file ID,
symbol ID and execution type; unknown symbols retain address identity. Recursion
counts a function once per sample for inclusive weight/sample count; self weight
always belongs to the original sampled leaf. This is deliberately not a sum of
recursive call-tree row weights. Thread counts are actual distinct sampled IDs.

`flame_graph(query, viewport)` and
`flame_graph_in_range(query, viewport, start_fraction, end_fraction)` produce
weight-domain geometry. Children consume their actual inclusive weight and leave
parent self-weight gaps. Horizontal zoom is a normalized weight range, not time.
Subpixel blocks at a depth share a pixel aggregate; aggregate selection does not
pretend to identify a single function. Weights in a pixel aggregate describe its
source rows, not an exact visible/clipped weight.

`stack_chart(query, TimelineViewport)` shows one-pixel **sampled timestamp points**
in the original Simpleperf clock domain. It never joins samples into asserted
execution spans or invents a sample interval/duration. Distinct threads share
rows unless filtered explicitly; pixel collisions report aggregate source count,
time extrema and an optional thread/event only if all sources agree. This is not
yet parity with the legacy duration-oriented Stack Chart.

`ChartFrame::hit_test(x, y)` indexes rows by depth and binary-searches x; selection
returns the packet actually painted. It uses half-open bounds and rejects NaN.
`ChartFrame::selected(id)` resolves exact block/aggregate facts. One custom canvas
can paint the full packet with a single shared hit-test surface.

Chart output caps are 20,000 blocks, 16,384 pixels wide, 512 depths and 16 MiB of
reserved string payload. `truncated` and `omitted_blocks` expose cap omissions;
offscreen rows are not counted as omissions. Parsed records and full projection
caches remain in-memory, so output caps still do not bound process RAM. Event
filters and single-event event-count semantics are preserved.

## CSV and Firefox boundaries

`export_call_tree_csv(query)` and `export_top_functions_csv(query)` return UTF-8
text, at most 16 MiB, without filesystem writes. Pagination does not truncate an
export; active filters/weight/direction still apply. Quoting follows the existing
`ReportExportService` comma/quote/CR/LF escaping contract. Metric columns retain
`inclusive_weight`/`exclusive_weight` terminology. Native tree CSV exposes native
ancestry IDs, `self_sample_count`, `weight_type` and `direction`; it does not
fabricate missing per-node `thread_count` or claim legacy numeric-ID CSV parity.
Top-functions CSV retains the legacy leading six columns and appends explicit
self-sample/weight metadata. Raw symbol/path text is retained, not localized or
silently rewritten; treat CSV contents as untrusted data when opening spreadsheets.

`CpuSession::export_firefox_profile()` now emits a bounded AOSP Gecko v24
`application/json+gzip` payload with per-thread sample tables, frame/stack/string
tables, timestamp conversion, category inference and unknown-symbol address
normalization matching the existing `GeckoProfileExportService` contract. The
native writer uses stored DEFLATE blocks from the standard library and caps the
compressed output at 64 MiB; the GPUI host owns destination selection and
create-new publication. Browser import, same-input round-trip parity and large
profile resource acceptance remain separate gates.

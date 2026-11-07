# Minimized HAR adapter

`HarSession` reuses `aps-har-engine` for bounded streaming summary and raw-entry
transport. Its public rows contain only numeric metrics, an allowlisted method,
and `redacted://minimized-url`. No URL component (including path, query names,
userinfo or fragment), timestamp, header, body, cookie, producer string, arbitrary
method or raw parser error is returned or persisted. There is intentionally no
full-fidelity mode. This is stricter than the Kotlin URL redactor, consistent with
the deny-by-default boundary in ADR 0029; endpoint display parity is not claimed.

The selected file is opened read-only. Defaults: 512 MiB input, 100,000 entries,
200 rows/page; configurable input limits cannot exceed 2 GiB / 1,000,000 entries.
The engine additionally bounds each JSON value to 2 MiB and nesting to 64.
Pages consume the complete valid stream before publication; partial results are
discarded. Cancellation is checked during reads. Call all parsing off the UI thread.

`HarSession::open` now binds the selected source to a SHA-256 digest. Entry pages
retain only bounded minimized rows in a session-owned cache (up to eight pages
and 512 KiB); cache keys are offset/limit and every returned page carries the
source digest. Cache hits still recheck source metadata and digest. Length/mtime
remain a fast mutation check, while the digest closes same-stamp replacement for
publication; this is not yet a persisted content-addressed HAR index.

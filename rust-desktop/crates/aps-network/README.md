# Minimized network evidence adapter

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

## Explicit Agent observations

`HarSession::from_observed(ObservedCapture)` accepts a separate immutable,
memory-backed Agent source. It never synthesizes a HAR file or reuses HAR timing
as a claimed device observation. Its SHA-256 identifies canonical minimized
evidence, not the original event stream. No raw URL, headers, cookies, bodies,
TLS/cipher, arbitrary diagnostics, package name or device serial enters the
public request rows. Validated process-start/boot identity binds capture to one
process instance; kernel identity is excluded from presentation exports.

Observed sources retain at most 20,000 calls and 64 phase records per call.
Outcomes are real `Completed`, `Failed`, `Cancelled` or `InFlight` observations,
independent of HTTP status. `EntryQuery::outcome` is applied over the entire
observed session before sorting and paging. Minimized HAR accepts only `All`:
it rejects other outcome filters rather than inferring transport failures from
4xx/5xx responses. Search remains restricted to minimized fields.

The desktop Agent v2 capture requires explicit consent, a running debuggable
App with the Network Agent and instrumented OkHttp clients. It validates the
READY PID, process start ticks and device boot UUID, then periodically and
finally rechecks identity. It owns only its dynamically allocated ADB forward.
STOP freezes a sequence/time/loss boundary and drains within a deadline;
cancellation rejects racing publications and restores the prior publication.
Forward removal is identity-checked and read back; an ambiguous removal is not
retried against a potentially reused port.

The page/export includes allowlisted protocol, call outcome, device-relative
request/phase endpoints, nullable durations, cancellation evidence, phase
truncation and capture-loss counters. Missing/ambiguous endpoints remain unknown.
Body-end byte subtotals are not full wire traffic. Dropped-event and sequence-gap
counters may overlap and must not be added. WebView, Cronet, URLConnection,
native sockets and uninstrumented clients remain outside coverage. This is not
a full raw-traffic export or evidence of complete device network activity.

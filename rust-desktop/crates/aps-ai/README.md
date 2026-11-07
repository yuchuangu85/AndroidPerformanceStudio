# aps-ai

Explicitly approved, bounded OpenAI-compatible **Chat Completions** analysis.
This crate is an independent implementation, not a claim of complete Kotlin AI
parity or a configurable arbitrary-tool agent. It never executes model tools.

## Trusted host integration

1. Register explicit provider metadata with `ProviderConfig::new`. HTTPS is
   required except literal loopback/localhost HTTP. URL userinfo, query and
   fragment are rejected. The supplied base URL resolves to `/chat/completions`.
   No legacy settings, environment credentials or provider files are discovered.
2. A profiler adapter selects minimized `Evidence`; optional `SourceCandidate`s
   carry workspace, immutable snapshot, content digest and relative-path/line
   identity. Source resolution must happen in the deterministic source domain,
   not in a provider answer. Adapters own sensitive-data minimization: JSON
   bounds alone do not prove evidence is safe to upload.
3. Freeze `AnalysisRequest` and provider with `PreparedRun::new`. Present the
   provider/endpoint/model and exact evidence/source scope to the user. A
   digest/label is correlation, never permission.
4. The trusted UI handler calls `RunCoordinator::activate`, then
   `approve_explicit` only for a genuine matching user approval. Approvals are
   one-use, issuer-bound, expire within five minutes, and include the exact
   source candidate IDs. Source metadata is conservatively treated as an upload
   even if snippets are absent. Each active generation authorizes at most one
   run; start a higher generation to retry. Cancellation clears approvals and
   invalidates authorized in-flight work. Nothing is auto-approved.
5. Optionally `SessionStore::begin(&authorized)`, then await
   `OpenAiGateway::execute(authorized)` on a background executor. Inject a trusted
   `Arc<dyn http_client::HttpClient>` and `Arc<dyn CredentialResolver>`.
   The HTTP client must honor `RedirectPolicy::NoFollow`, sensitive headers and
   dropped request/body cancellation. No automatic redirect, retry, fallback
   endpoint or model substitution is implemented. Credentials resolve only
   after approval and never enter application metadata or result types.
6. Before publishing after await, call `coordinator.accept_result(&result)`;
   awaiting a request is not proof its session/generation is still selected.
   Update optional audit state with `complete`/`fail`.

The transport has a two-minute overall deadline, thirty-second body-idle
deadline and 25 ms cancellation polling. It caps full nonstream envelopes at
1 MiB, stream wire data at 2 MiB and analysis text at 256 KiB. Header limits are
checked before reading, with actual body accounting independent of headers.
Only HTTP 200 and matching JSON/SSE media types are accepted. Provider error
body text, URLs and underlying transport diagnostics are never exposed as
errors. An injected resolver is the only credential access path; OS keychain
integration belongs to the host and remains unimplemented here.

`SecretCredential` has no Debug/Clone/Serialize or text getter. Authorization
headers are marked sensitive; this is a trusted-client contract, not isolation
from the HTTP implementation. Best-effort buffer clearing is not cryptographic
zeroization. A response containing the resolved credential (including JSON
escaped string forms) is rejected before publication. This is not a universal
PII/secret detector. No raw stream text is sent to UI: progress callbacks carry
only a byte count until the whole answer passes validation.

## Decoder and result contracts

`SseDecoder` handles arbitrary byte/UTF-8/CRLF chunk boundaries, BOM, comments
and multiline data fields with independent wire/line/event/content/event-count
budgets. It rejects duplicate JSON keys, changing completion/model identities,
nonzero/multiple choices, tool/refusal output, malformed deltas, length-truncated
finishes, data after stop, missing stop and missing `[DONE]`. One optional final
usage chunk is allowed. EOF alone does not produce a successful result. This is
a strict Chat Completions dialect, **not** Responses API or arbitrary SSE.

Final JSON accepts exactly the declared summary/finding fields, at most twelve
findings and one hundred unique references per kind. Every finding requires
supplied evidence IDs. All candidate IDs must come from the frozen request;
there are no provider-created navigable file paths or line numbers. Prose stays
untrusted plain text and must not be interpreted as tool calls, permissions or
source links by the host.

`ProviderCatalog::parse_models` sanitizes IDs from an already obtained bounded
`/models` document. It does not make a request and does not infer streaming or
JSON schema capabilities from model names. Catalog network refresh, Responses
API, conversation history, plain direct chat, provider import UI and persisted
validated report reopening remain separate parity work.

## Audit persistence and validation boundary

`SessionStore` owns only the explicit absolute `aps-ai-audit-v1.json` path,
rejects linked file/ancestors, preserves old data on failed atomic replacement
and caps history at 1000 records. It stores only session/run/provider IDs,
generation, frozen request digest, state and counts. Questions, evidence, source
contents/paths, endpoints, credentials/references and provider answers are not
persisted. Reopening `running` metadata never resumes a request or restores an
approval. It is single-host-owned, not a cross-process transactional database or
an OS race-proof filesystem sandbox.

No live provider, keychain, device or hosted request has been exercised. Test
sources are deliberately not executed until the complete rewrite implementation
is ready, following the user's instruction. Compilation is owned by the parent
integration lane. Security tests, native approval UI acceptance and same-input
parity remain required evidence, not implied by these implemented boundaries.

## Explicit application execution surface

The GPUI application accepts only actual profiler-supplied `AnalysisRequest`s.
Provider ID/base URL/model/reference are entered explicitly and frozen before
approval. `PreparedRun::review_body()` exposes the **exact immutable JSON request
body** to the trusted local review panel; it is sensitive content, never audit
metadata or implicitly copied text. Endpoint and credential reference are shown
separately, and the Authorization header is never part of that body.

The user can enter a temporary API credential in a masked field. It is never
persisted, searched for, logged or restored; sending/cancelling clears the field
and its SDK editing history. `OneShotCredential` holds a noncloneable secret,
resolves only its exact opaque reference once, and then refuses a second lookup.
No OS keychain adapter is claimed. Upstream UI/TLS memory clearing remains best
effort. An injected trusted resolver remains supported by `OpenAiGateway`.

Only the real Send click issues one-use approval after separate endpoint/network
consent and (when applicable) exact source candidate upload consent. No request
is made while configuring, freezing or reviewing. The UI waits for validated
plain-text results, cancels its coordinator on close, and gates publication by
both a local epoch and the coordinator's session/run/generation check. A consumed
or cancelled request cannot simply be re-frozen into a second send; the profiler
must supply fresh evidence. No provider output becomes executable code or a new
source-navigation target.

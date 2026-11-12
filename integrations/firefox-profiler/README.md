# APS Firefox Profiler Query API, Version 1

This APS-owned, read-only extension lets the Rust desktop AI workflow pull data
from the **currently open local Firefox Profiler view**. It does not change the
upstream repository, add an upstream patch, or run an AI provider in the browser.
The online official viewer is not instrumented.

## Ownership And Updates

- `aps-query-api.js` lives outside `third_party/firefox-profiler`.
- The local host injects one external script into the HTTP response for the SPA
  shell. Neither `dist/index.html` nor upstream source files are written.
- The adapter reads the upstream console selectors and call-tree methods. It
  does not replace upstream functions, scrape the DOM, or dispatch Redux actions.
- These console interfaces are not a permanent upstream API contract. An
  incompatible upstream update returns `E_UPSTREAM_INCOMPATIBLE`; it must not
  silently use APS-native selection or match a function by its display name.
- Update/build the submodule with its existing workflow, then run the checks
  below. Any compatibility adjustment belongs in this directory or the APS host,
  not in the upstream checkout.

## Queries

The page exposes the frozen `window.APSProfilerQuery` object:

```js
const result = window.APSProfilerQuery.query({
  protocolVersion: 1,
  method: 'analysis',
  params: { limit: 12, maxDepth: 8 },
});
```

Available methods:

- `capabilities`: protocol version, method whitelist, and read-only limits.
- `context`: selected threads, preview and committed ranges in profile-relative
  milliseconds, tab, search, inversion, metric unit, total weight and selection.
- `threads`: bounded profile thread identities, with a truncation flag.
- `selection`: the selected call node, its ancestors and immediate children.
- `function`: the same detail for `params.nodeId`, or the current selected node.
- `callTree`: bounded depth-first rows, optionally below `params.rootNodeId`.
- `analysis`: context, selection and bounded call-tree rows in one snapshot.

Success is `{ok: true, revision, data}`; failure is
`{ok: false, code, message}`. Common errors are `E_NOT_READY`,
`E_UPSTREAM_INCOMPATIBLE`, `E_PROFILE_CHANGED`, `E_VIEW_UNSUPPORTED`, `E_STALE`,
`E_NODE`, `E_LIMIT`, and `E_PROTOCOL`.

Call-node queries are supported in Call Tree, Flame Graph and Stack Chart only.
A missing selection is `null`, not an invented function. Node IDs have the form
`fp:<documentId>:<revision>:<callNodeIndex>`. They are not APS-native IDs or stable
function identifiers. A page reload, thread/range/filter/selection change, or
call-tree change invalidates old IDs. Supply `expectedRevision` on follow-up
queries together with `params.expectedDocumentId`; a supplied `nodeId` or
`rootNodeId` already provides that document binding. A revision without a
document identity is rejected. Reacquire context after `E_STALE`.

Rows contain numeric inclusive/self weights and shares, unit, parent identity,
depth, function name, library basename, category and child availability. Units
are `samples`, `tracing-ms` or `bytes`, as reported by the upstream tree. Samples
are not exact method durations or scheduler evidence. Absolute library paths,
raw profiles, source contents, arbitrary Redux state and provider configuration
are not API fields. Symbols and thread names remain potentially sensitive data.

## Host Transport

`FirefoxViewer::query(method, params, expected_revision)` enqueues a read-only
command and returns a `QueryTicket`. Wait on the ticket off the UI thread.
Only the trusted Rust host can enqueue queries; there is no HTTP query-issuer
endpoint. The browser polls for queued commands and posts results:

- `GET /aps-query/<pageId>/adapter.js`: APS-owned external script.
- `POST /aps-query/<pageId>/attach`: `{documentId}` issued in this shell response.
- `GET /aps-query/<pageId>/poll`: consumes queued commands once.
- `POST /aps-query/<pageId>/result`: exact envelope
  `{protocolVersion, pageId, documentId, requestId, result}`.

Polling and POSTs require `X-APS-Query-Token`; polling also requires
`X-APS-Query-Document`. POSTs require the exact local Origin, JSON Content-Type
and bounded Content-Length. The server accepts loopback connections and an
allowlisted Host only, adds no CORS allowance and restricts CSP connections to
itself. These controls isolate web origins, not other processes with access to
the user's browser or local machine.

The mailbox allows eight pending requests, 8 KiB per command and 64 KiB per
result envelope. The adapter caps results at 60 KiB, rows at 100 and depth at 64.
The AI path requests a smaller subset and independently validates its allowlist
and the existing 32 KiB evidence budget. Timeouts, ticket drops, document/profile
replacement and server shutdown cancel outstanding work. A newer server-issued
document cancels the previous document's requests; superseded documents cannot
reclaim the bridge by retrying an attachment. Profile files have immutable,
viewer-specific names, so viewers sharing a storage root cannot replace each
other's data. Shutdown removes only the server's own profile file.
No profile data is read merely because the command queue is polled.

APS remains the AI request, provider, credential and conversation owner. Local
viewer AI errors stay explicit rather than falling back to unrelated native
state. Browser data is evidence, never instructions or provider configuration.

## Compatibility Checks

```sh
./scripts/firefox-profiler.sh verify
node --test integrations/firefox-profiler/aps-query-api.test.cjs
cd rust-desktop
cargo test --locked --offline -p aps-app firefox_viewer
cargo test --locked --offline -p aps-app firefox_ai
```

After an upstream upgrade, also open the same profile in the actual local page,
select a known call node, query it through the host, switch among the three
supported tabs, and verify numeric weights and thread/range context. Verify
follow-up IDs fail after changing the view or reloading, and verify that no
external network request or upstream working-tree change was introduced.
Unit tests with fake selectors alone do not prove upstream compatibility.

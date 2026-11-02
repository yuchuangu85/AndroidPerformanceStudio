# Live provider acceptance

This is a separately authorized, potentially billed integration check, not an ordinary unit
test and not a release qualification. No live request was made while adding the harness.
This CI harness has not been supplied an authorized environment key: live acceptance is
unavailable until the operator explicitly supplies one. Existing desktop Keychain references
are outside this harness and do not authorize it to read or reuse a credential.
Do not create credentials, inspect credential files, sign in, or call a provider to find one.

## Explicit selection

All three variables are mandatory for a live run:

- `APS_RUN_LIVE_PROVIDER=true` is the explicit host approval decision for one synthetic run.
- `APS_LIVE_PROVIDER` is exactly `openai`, `codex`, or `claude`.
- `APS_LIVE_MODEL` is an explicit model ID. There is no default, discovery-based substitution,
  trimming, alias rewriting, provider fallback, or automatic retry.

Credential values must already be provided by the CI secret environment or the operator's
environment. Never put their values into command arguments, this document, receipts, test
fixtures, `.env` files, shell tracing, source control, screenshots, or build logs.

- `openai`: `OPENAI_API_KEY` only, through the existing OpenAI client/gateway.
- `codex`: `OPENAI_API_KEY` only, plus a Codex-named model ID. The app must register its real
  `CodexResponsesProvider` with structured-analysis support. Until that implementation is
  available, an explicitly requested check fails. The label is **Codex (Responses API)**,
  not Codex CLI execution. No cached Codex credentials or managed-login files are read.
- `claude`: `ANTHROPIC_API_KEY` only and an installed, trusted Claude executable supported by
  `ClaudeCliProvider`. Bare execution does not use OAuth login or credential helpers.

Codex CLI 0.166 remains unsafe for this no-tools contract: disabling its feature switches
does not establish deny-all-tool execution. The harness never falls back to that CLI.
Both API selections use only `OpenAiResponsesClient.DEFAULT_ENDPOINT`, the public
`https://api.openai.com/v1/responses` endpoint. There is no endpoint override in this harness.
Local fixture gateways are offline tests, never evidence of live acceptance.

After supplying the three selection variables and the selected environment credential:

```sh
bash scripts/ai-provider-acceptance.sh --check
bash scripts/ai-provider-acceptance.sh
```

`--check` checks explicit selection and local executable/key presence only. It does not run
Gradle, probe a CLI version, validate authentication remotely, discover model availability,
or claim provider acceptance. A missing key, executable, Java runtime, provider selection,
or model is a nonzero failure. CI must not turn a requested failure into an allowed skip.

The script runs only `LiveProviderAcceptanceTest.liveProviderAcceptance` through the existing
desktop composite build, with one Gradle worker, no daemon/configuration cache/build cache,
no build scan, and forced task execution to avoid a stale cached pass. It suppresses raw
Gradle/provider output and preserves a nonzero build/test exit. Diagnose compilation
separately with live variables and credentials removed, not with `bash -x` or Gradle debug.
Do not parallelize live acceptance with the main build's serial tests.

## One bounded request

The harness opens an isolated temporary app profile, uses actual app provider registration,
and creates a real `MainAgentOrchestrator` with a real `ProviderBroker`. A guarded adapter
forwards to the app's actual broker; it cannot replace an unavailable provider with a fake.
It uses **`aps.simpleperf`**, never the multi-provider `aps.main` graph, and verifies that
the registered agent declares no child agents, tools or scripts before any approval.

The request contains exactly one `SIMPLEPERF` / `simpleperf-hotspots` evidence item: a synthetic
80-of-100 sample share. It contains no real capture, project name, path, source candidate,
snippet, device identity, repository context or conversation history. Only `READ_EVIDENCE`
and `PROVIDER_NETWORK` are allowed. No source or artifact permission is granted.

The host prepares the payload, checks its preview and exact permissions, explicitly approves
the returned approval request, then collects that one run. It never retries, including on
authentication, model, rate-limit, timeout, parsing, or assertion failure. An explicit new
invocation is a new authorization and may incur a new charge, including after a failed run.

The limits are intentionally small:

- Synthetic canonical input is at most 2 KiB; provider structured output is at most 4 KiB.
- The guarded provider lane has a 30-second wall-time limit; discovery plus execution has a
  65-second outer limit. Gradle compilation itself is not inside that provider deadline.
- The existing client is constructed with `maxOutputTokens = 512`. The API transport verifies
  `store: false`, no tools, and this token cap before its one HTTP send attempt. It permits no
  redirects, has a five-second connect timeout and a 16 KiB response-envelope cap.
- API responses must report a completed status, the exact supplied model ID, 1..2048 input
  tokens and 1..512 output tokens. Supply an exact ID that the service echoes; aliases that
  resolve to a different returned model fail rather than silently weakening model identity.
- Claude is one noninteractive no-tools provider invocation with the adapter's one-turn
  setting and bounded pipes. The requested output expectation is below 512 tokens. Its
  current public driver contract does **not** expose token usage, a hard token cap, or its
  internal HTTP count. These are explicit qualification gaps, not measured token guarantees.

The HTTP count is the harness transport's `sendAsync` attempt count, not a provider-side
billing receipt or proof against a trusted client's internal connection recovery. CLI
discovery may run bounded `--version` / `--help` probes; those are not model requests.

Broker validation and the shared strict structured-result codec run before acceptance.
The harness additionally requires one nonempty short finding, exactly the synthetic evidence
ID, no candidate IDs, finite 0..1 confidence, the exact session/model, a successful terminal
conversation status, exactly one broker invocation, one started event, and one structured
result. API providers also require exactly one observed transport attempt. Unknown references,
empty results, tool requests, text streaming, incomplete results and failed prerequisites fail.

## Metadata only

Conversation references live only in an in-memory `ConversationRepository`. The temporary
app's ordinary history remains empty: finding text is not written to SQLite, even temporarily.
The temporary profile is removed after shutdown. CLI prompts/output are handled only through
the existing bounded in-memory process adapter. No request, response, finding prose, raw
stdout/stderr, source text or credential is included in a receipt or test failure cause.

The receipt is allowlisted JSON: provider, runtime/API adapter version, exact requested model,
discovery status, invocation status, pass/fail status, broker invocation count, started-event
count, structured-result count, and transport-attempt count. API adapter versions are not CLI
versions. Claude's unobservable transport count is `null`, never fabricated as one. A started
event means the driver reached its start boundary, not proof that the provider billed a call.

By default the script prints this JSON and removes its temporary receipt. For a durable CI
artifact, set `APS_LIVE_RECEIPT` to a **new absolute filename** under an existing canonical
directory with no symlink ancestors. The test uses exclusive creation and private POSIX file
permissions where supported; it refuses to overwrite an earlier receipt. A failure before the
test starts may have no receipt and always leaves the runner failed. Never upload ordinary
provider logs as substitute evidence, and do not enable JVM HTTP/header debug logging.
The live method rejects enabled JVM HTTP-client/TLS debug logging before using credentials.

## Default suite and offline regressions

With `APS_RUN_LIVE_PROVIDER` absent or exactly `false`, the live method uses a real JUnit
assumption abort: it is **skipped**, not a passing early return. With the flag exactly `true`,
missing selection/auth/runtime/model support is a failure, never an assumption skip.
Other flag values fail closed to catch misspelled CI opt-ins.

The same file contains network-free fixtures for selection gates, zero calls before approval,
altered approval rejection, single invocation/replay rejection, unknown evidence/candidate
rejection, empty/oversized/nonfinite findings, HTTP body-cap cancellation, and receipt field allowlisting. The fixtures
cannot become the live provider factory. Main should run these tests serially after integrating
the actual provider changes. Neither Gradle nor billed requests were run for this addition.

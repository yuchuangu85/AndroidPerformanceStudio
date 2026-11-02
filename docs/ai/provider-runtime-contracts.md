# Provider runtime contracts

Evidence date: **2026-10-31**. Provider-specific types stay inside provider modules. The
Main Agent grants permissions; discovering a runtime or holding an approval DTO grants nothing.

## 2026-10-31 execution update

The configured `codex` provider means **the saved Codex Responses connection**, implemented
by `CodexResponsesProvider` and the existing `OpenAiAnalysisGateway`/`OpenAiResponsesClient`.
This is a distinct, visible transport choice, not automatic fallback from Codex CLI authentication.
It uses the configured API credential reference and exact saved model ID, including gateway namespaces;
the name need not contain `codex`. It requests no tools,
`store=false`, and a bounded `max_output_tokens`. Tool-bearing, incomplete, refused or commentary-only
output is not an analysis result. Typed `phase=final_answer` takes precedence over commentary;
independent acceptance parsing checks the same distinction.

Official references fetched through web retrieval on this date:

- [Responses tool choice](https://developers.openai.com/api/reference/python/resources/responses)
  documents the explicit `none` choice.
- [GPT-5.3-Codex model guide](https://developers.openai.com/api/docs/guides/latest-model?model=gpt-5.3-codex)
  documents API availability and assistant message phases.
- [Model lifecycle](https://developers.openai.com/api/docs/models/gpt-5.3-codex) marks this example
  model deprecated with shutdown scheduled for **2027-04-01**. It is an explicitly selected
  compatibility fixture, not a new default or latest-model recommendation.
- [Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs) documents
  strict schemas, refusals and incomplete output. Phase metadata matters when reconstructing
  assistant history; APS instead sends new bounded requests and does not replay raw history.

Direct shell retrieval still returned HTTP 403; successful web retrieval above is a separate
evidence surface from the earlier direct-fetch failures recorded below.

The installed CLI 0.166.0 was additionally probed against a synthetic loopback Responses service
with isolated HOME/CODEX_HOME, no real credentials/project data and all 193 reported feature switches
disabled. Its wire request still exposed `request_user_input` and `tool_choice=auto`. Therefore
`CodexCliProvider` remains unavailable; this probe is not a model request or a universal CLI sandbox proof.

Actual configured API acceptance made **one transport attempt each** for OpenAI and Codex using
synthetic evidence and the existing application CredentialStore. Both timed out without an HTTP
response. No retry/fallback or raw response/credential persistence occurred. These outcomes do not
qualify the account, model, endpoint reachability or successful inference. Raw metadata receipts
are archived under `.omx/verification/ai-platform-final-2026-10-31/`; their original `requestsSent`
counter denotes a client transport invocation, not proof of bytes received by the server.

## Official documentation and observed runtime evidence

- Claude CLI reference: <https://code.claude.com/docs/en/cli-reference.md> (fetched 2026-10-31).
  `--tools ""` disables all built-in tools; it does not disable MCP, so the adapter also
  uses `--disallowedTools "mcp__*"`, `--strict-mcp-config` and an empty MCP configuration.
- Claude headless execution: <https://code.claude.com/docs/en/headless.md> (fetched 2026-10-31).
  Documents stdin input, `--output-format json`, `--json-schema`, and the
  `structured_output` result field. `--bare` skips hooks, plugins, skills, MCP discovery,
  memory and CLAUDE.md; in bare mode OAuth/keychain credentials are **not** read.
  The adapter accepts an explicit `ANTHROPIC_API_KEY` or `ANTHROPIC_AUTH_TOKEN` connection,
  with an optional validated `ANTHROPIC_BASE_URL`. It never runs a configured credential helper.
  The documented fully bounded bare behavior requires v2.1.286 or later.
- Claude settings: <https://code.claude.com/docs/en/settings.md> (fetched 2026-10-31).
  Verifies the user settings location and `model` metadata. The resolver reads only a
  bounded settings file, returns the allowlisted model field, and never invokes hooks,
  helpers, MCP, or reads account/credential files.
- OpenAI attempted official pages: <https://developers.openai.com/codex/noninteractive>,
  <https://developers.openai.com/codex/app-server>,
  <https://developers.openai.com/codex/cli/reference>,
  <https://developers.openai.com/codex/config-reference> and their `.md` variants.
  All returned HTTP 403 in this environment on 2026-10-31; these are **not** claimed as
  successfully retrieved source evidence. No third-party description substitutes for them.
- Read-only installed-runtime probes: `codex --version` returned `codex-cli 0.166.0`;
  `codex exec --help` exposes read-only sandbox, JSONL and output-schema controls but no
  verified disable-all-tools contract. `codex app-server generate-json-schema --out <temp>`
  produced the installed protocol schema without model execution. Thread start includes
  sandbox/config, not a verified deny-all-built-in-tools field. Dynamic-tool declarations
  do not prove built-in tool denial.
- Read-only `claude --version` returned `2.1.308 (Claude Code)`; `claude --help` confirms
  the selected isolation/output flags. No real prompt, paid request, auth-file read or
  account/config content dump was performed by these probes.

## Supported execution

`ClaudeCliProvider` runs the official noninteractive executable by absolute path, version
and flag-gates it, uses a private empty working directory, sends bounded canonical evidence
on stdin (never argv), disables tools/skills/MCP, disables conversation persistence, fixes
the explicitly requested model and system prompt, and permits one turn. Only the runtime
inherits `ANTHROPIC_API_KEY`; provider model/metadata/events never contain its value.
No shell is used to assemble commands. A failed or unsupported run never falls back to a
second provider or performs an automatic retry. Retry is an explicit new host run/generation.

The JSON envelope must report a successful result and contain a structured output object.
The shared strict codec rejects extra result fields, invented evidence/candidate IDs,
invalid confidence, oversized result text and session/model mismatch. Broker result
validation remains an independent second check. Provider stdout/stderr are never shown
raw in UI errors or audit metadata. The process result's `toString` is redacted.

`CodexCliProvider` performs real version discovery but deliberately returns
`UNSUPPORTED_CAPABILITY`. A read-only Codex sandbox still permits reads and commands,
which does not meet this platform's no-tools analysis contract. It must **not** execute
`codex exec` under the misleading label of a safe read-only provider. Enabling Codex needs
a verified official no-tools API or an independently qualified external execution boundary.
This remains an explicit CLI gap. The separately selected Responses adapter above does not enable
or inherit the CLI's cached login, tools, hook or filesystem capabilities.

## Local metadata and credentials

The resolver checks existing, bounded, regular, non-symlink configuration paths and reports
absent, available, malformed, permission-denied or unsupported state. Only model/profile
metadata and `CredentialReference` values leave the resolver. Codex's top-level metadata
parser is deliberately a restricted subset, not a full TOML evaluator; it never executes or
follows config imports. `auth.json`, `.credentials.json`, `.claude.json`, keychain data and
OAuth files are never opened. Existence of a login file is not proof of a usable credential.
UI must render config-source paths masked rather than expose local home-directory identity.

### Explicit settings import

The Provider settings page selects **Codex**, **Claude**, or **Custom (OpenAI compatible)** and renders
only that connection's form. Switching and local imports edit isolated drafts; **Save and apply**
publishes the selected provider and model only after connection validation/storage succeeds. Custom
uses an independent saved endpoint/key after migration from the legacy OpenAI configuration.
Agent and execution-permission fields are merged from the latest settings rather than overwritten.
Connection persistence and active selection are applied together with rollback on activation failure;
unrecoverable rollback invalidates that connection instead of using a partial state. Before a first
Codex overwrite, an existing shared legacy OpenAI connection is preserved as independent Custom data.
Prepared requests retain a nonsecret connection revision; changing the saved connection invalidates
the old request rather than authorizing its payload for another endpoint. A new preflight is required.
The AI Settings page has separate **Read Codex Config** and **Read Claude Config** actions, shown
only for their corresponding selected provider. Custom connections are configured manually.
These are opt-in static configuration imports, separate from metadata discovery. Nothing is read
by this importer until the corresponding button is clicked; importing does not save credentials,
make a model request, execute a helper, or alter the original configuration. The fields remain a
masked draft until **Save**. Import failures preserve the previous draft and use stable localized
messages rather than file contents or exception text.

Codex imports read the selected provider/profile in `CODEX_HOME/config.toml` (default `~/.codex`).
Supported static connection fields include `base_url`, `env_key`, Bearer Authorization headers and
explicit Bearer tokens. Only the selected provider's declared credential is resolved; an unresolved
custom-provider key never borrows an unrelated OpenAI key. API-key-shaped `auth.json.OPENAI_API_KEY`
is a compatibility input, not a guarantee that every Codex credential-store implementation uses
that schema. OAuth/cache tokens are not imported. Base URLs become complete Responses endpoints;
unsupported API protocols and ambiguous credentials fail closed.

Claude imports read `CLAUDE_CONFIG_DIR/settings.json` (default `~/.claude`) and its static Anthropic
connection variables. User-settings connection fields and environment-only connections are kept
separate rather than mixing an endpoint from one with a key from the other. API keys and explicit
Bearer authentication remain distinct. OAuth subscription credentials and `apiKeyHelper` are not
executed or copied. Claude credentials are stored separately from OpenAI credentials, and the bare
CLI receives only the allowlisted connection environment through the existing bounded process
boundary. No credential value enters CLI arguments, normal preferences, history, or debug strings.

An imported connection with no exportable key can fill its model/endpoint, but cannot reuse an old
connection's saved key. A separate key is required before saving. API reachability and live billing
acceptance are not implied by a successful static import. The settings save uses the existing
credential store: macOS Keychain on macOS and session-only storage on other current desktop hosts.
Each saved Claude authentication kind is bound to its exact endpoint; changing the endpoint or
switching back to a credential from a different endpoint requires a new credential. Saved Claude
connections do not inherit process-level Anthropic credentials or a different gateway address.
Successful settings saves are observed across settings-store instances so an already open AI
workspace can use the new provider/model. An awaiting-approval or running analysis retains its
existing settings snapshot; the latest settings apply after that analysis reaches a terminal state.

### Independent model catalog URL

OpenAI-compatible and Codex Responses connections support an optional, separately configured
**Models URL** for model discovery. Leaving it blank starts with the existing derivation: an API
base retains its complete path prefix and appends `/models`; a full Responses endpoint uses the
sibling `/models` path. This compatibility rule is not a guarantee that every custom Responses
provider exposes that catalog route.

An explicit Models URL must use the same origin as the configured Responses endpoint: the same
scheme, host and effective port. Both URLs require HTTPS, except exact loopback HTTP addresses
for local development, and reject embedded user information, query strings and fragments. The
separate path does not authorize sending the saved credential to a different origin. Invalid
catalog configuration fails before credential resolution or transport.

An explicit Models URL is used exactly as configured, without alternative-route probes. Models URL
is connection metadata, not a second inference endpoint or a source of new execution permission.
Imports of a different connection and endpoint edits clear the previous catalog override. Re-reading
Codex configuration for the exact same Responses URL retains its current catalog URL.

When Models URL is blank and a root `/responses` gateway returns a successful, recognizable HTML document from
the derived `/models`, the catalog client makes exactly one additional GET to `/v1/models` on the
same origin. This bounded compatibility fallback never drops a configured proxy prefix, follows
no cross-origin route, changes no Responses URL, and saves no settings implicitly. It does not run
for explicit Models URLs, authentication/HTTP/network failures, XML, or malformed JSON. Failure of the
alternative stops after two total GETs; manual configuration remains available. There is no manual
retry button for the same route that was already attempted.

Live catalog acceptance uses the same JDK transport and catalog parser as the app, resolving the
existing credential only for its saved origin. It submits GETs only, never an inference request;
catalog success must not be reported as provider inference or release acceptance.

Users may still enter a model identifier manually. Catalog failure does not establish inference
failure, and a successful catalog response does not prove that the selected model supports the
required inference contract. HTML responses receive a stable, body-free diagnostic asking the
user to check Models URL, gateway routing or login; the message must not generically direct the
user to replace a valid Responses endpoint with `/v1`. Existing model selection remains intact
when discovery fails, and raw response bodies, credentials and parser details are not shown.

## Process and acceptance boundaries

`ControlledProviderProcess` bounds stdin/stdout/stderr, wall time, allowed environment names
and child lifetime. Bounded in-memory pipe pumps never persist prompts/stdout/stderr; process
subtrees receive terminate then force-kill on timeout/cancel/normal exit. These are transport
resource controls, **not** a Python/Node sandbox, filesystem confinement, or evidence that
an arbitrary executable is trusted. Only a user-configured trusted provider executable is
eligible; an Agent cannot choose a path or add environment names.

Fake-executable tests cover metadata and version/flag discovery, malformed JSON/result IDs,
permission rejection, explicit retry, stdout/stderr overflow, cancellation, descendant force
termination and missing/unsupported runtimes. They do not establish live credentials,
network reachability, billed provider success, OAuth behavior, Windows process behavior,
code signing, or hosted release qualification. The two failed configured API attempts described
above are recorded separately and are not substituted for successful live acceptance.

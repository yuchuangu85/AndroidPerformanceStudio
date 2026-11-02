# AI Platform

AI business/runtime modules above `ai-core`, with desktop bootstrap and a shared Compose conversation
window. This is a source-level continuation map, not a provider or release qualification report.
The implementation checklist and fresh validation results belong in
[`agent-platform-todo.md`](../../docs/ai/agent-platform-todo.md) and
[`agent-platform-todo-status.md`](../../docs/ai/agent-platform-todo-status.md); test totals are not
duplicated here.

## Ownership

The included projects are declared in [settings.gradle.kts](settings.gradle.kts):

- `ai-platform-model`: versioned Agent/Skill manifests, provider capabilities/events, permissions,
  approvals, conversation references and run-state contracts; reuses neutral `ai-core` contracts.
- `ai-platform-registry`: manifest/checksum validation, bundled/imported package installation,
  version selection and host-owned origin/trust/enablement/revocation state.
- `ai-platform-storage`: SQLite conversation/run references, bounded redacted structured results,
  execution audits, deletion/retention and interruption recovery.
- `ai-platform-providers`: provider registry/broker, OpenAI gateway adapter, bounded local config
  metadata discovery, controlled CLI processes and shared structured-result validation.
- `ai-platform-provider-claude`: capability-gated, no-tools Claude CLI structured-analysis adapter.
- `ai-platform-provider-codex`: explicit no-tools Responses model adapter and a separate fail-closed CLI adapter.
- `ai-platform-script-runtime`: Python/Node sandbox backend/discovery contracts and an inert,
  content/provenance-verified HTML/Markdown `ArtifactPublisher`.
- `ai-platform-runtime`: `MainAgentOrchestrator`, trusted manifest child/Skill/script graph execution,
  shared budgets, approval/cancellation and the state/history boundary in `ApplicationAiService`.
- `ai-platform-app`: `AiPlatformRuntime` desktop profile ownership, startup/shutdown, built-in
  installation, provider registration, settings and immutable `BusinessContext` registry.
- `ai-platform-presentation`: global `AiConversationWindowHost`, evidence/findings/history panes,
  provider and extension settings, policy controls and verified HTML report opening.

`ai-core` does not depend on the platform or Compose. Provider-specific code stays behind neutral
contracts, without Codex/Claude SDK dependencies in profiler business models. Profiler modules own
their minimized evidence projections; `desktop-app` owns composition and legacy integration.

## Desktop bootstrap and global Chat

[`AiPlatformRuntime.open(...)`](ai-platform-app/src/main/kotlin/com/androidperformancestudio/aiplatform/app/AiPlatformRuntime.kt)
initializes managed directories, acquires the exclusive profile lock, installs built-ins, registers
OpenAI and discovered CLI adapters, opens conversation storage and marks unfinished runs interrupted.
Startup, discovery and history restoration do not submit an analysis or replay unfinished work.
Shutdown cancels and joins owned work before closing history and releasing profile ownership.

[`DesktopAppMainPage`](../desktop-app/src/main/kotlin/com/androidperformancestudio/desktop/DesktopAppMainPage.kt)
boots that runtime and hosts one global conversation window, reached from the navigation rail,
application menu and keyboard action. The window binds the active page's immutable business context;
missing or expired evidence means unavailable, not unrestricted chat. It provides explicit
prepare/confirm/cancel/retry, evidence and source-candidate navigation, an Agent/Skill timeline,
read-only history restoration and opt-in verified HTML artifacts.

Unified application AI settings contain Provider/Agent/Skill/Policy categories. Provider is the former
Connection page with a Codex/Claude/Custom selector and one connection card. Local imports and manual
configuration are drafts until Save and apply publishes the selected provider/model. Custom gets an
independent connection after migration from existing OpenAI settings. The old separate default-model
management page remains removed. Agent/Skill/Policy manage verified extension versions and trust,
execution permissions and history retention. Script permission defaults off and never bypasses sandbox preflight.
Local CLI discovery reads allowlisted metadata, not auth files,
helpers, hooks or arbitrary config; selecting a provider is not proof it is available or authorized.

## Built-ins and approved execution

[`BuiltinBundles`](ai-platform-registry/src/main/kotlin/com/androidperformancestudio/aiplatform/registry/BuiltinBundles.kt)
installs seven application-owned packages:

- Agents: `aps.main`, `aps.simpleperf`, `aps.source-evidence`, `aps.report`.
- Skills: `aps.evidence-summary`, `aps.source-candidates`, `aps.report-artifact`.

[`FrozenAgentGraph`](ai-platform-runtime/src/main/kotlin/com/androidperformancestudio/aiplatform/runtime/FrozenAgentGraph.kt)
freezes a trusted manifest graph, including imported Agents and declared script Skills. It rejects
cycles, excessive depth/calls, changed content, untrusted packages and unsupported privileges.
Every selected child makes its own provider request with a distinct run/session and parent-linked
conversation; audit references identify the exact child run. Each child receives only its intersected
evidence/source permissions. Imported prompts are bounded untrusted user context, never system overrides.

Validated, redacted child conclusions and script outputs feed the parent as explicitly untrusted
derived analysis, not new performance evidence or source locations. Package/run/input/content hashes
identify producers. The transformation policy is approval-bound. Derived context exceeding the
4096-character question boundary fails before the parent request; it is not silently truncated.

The host prepares a frozen `AnalysisRequest` and displays payload size, evidence/candidate counts,
provider/model, planned model/script counts and session before explicit approval. The private pending-run registry binds approval
to the context, plan/package hashes, permissions, provider/model and generation; an `ApprovalGrant`
DTO alone grants nothing. Execution rechecks content/trust and live source-snippet authorization.
Stale or cancelled output cannot replace the active run. Scripts additionally require an explicit
host grant, frozen path/hash, an available qualified runtime, bounded structured input/output and
audit. Qualification happens during preflight, before provider dispatch. Provider tool calls and
device writes remain unavailable; trust alone never grants script execution.

One budget ledger covers the root, child/Skill steps, provider and artifact publication, bounding
invocations, depth, input/output and elapsed time. Its CPU allowance is conservatively spent as
elapsed time, not process-tree CPU metering. JVM memory and gateway-internal raw response allocations
are not constrained by that ledger. Native script CPU/address-space limits are a separate OS boundary.

## Profiler integration

`ui-components` exposes neutral `UiAiContext`/`UiAiEvidence`, `AiContextHost` and `PublishAiContext`.
Active profiler pages publish bounded facts; the desktop converts them to immutable `BusinessContext`
requests without granting provider, filesystem or source-upload authority.

- Layout and Simpleperf publish current selection/report evidence. Trace/Perfetto publishes
  artifact-bound numeric diagnostic results, not arbitrary SQL or raw trace content.
- Memory, Frame, Startup, Battery, Network, GPU and Benchmark Regression have page-owned adapters.
  They retain units, missing/partial coverage, explicit selection/summary scope and omission counts
  where applicable. Network excludes URLs/headers/bodies; Memory excludes runtime field values;
  GPU currently supplies artifact identity metadata, not GPU timing measurements.
- Source Workspace publishes verified selection metadata only: hashed snapshot identity, content
  hash, language, content state, size and line bounds. Missing or mismatched selection/content is
  unavailable. Paths and source bodies are excluded; this is not measured performance or upload permission.

Legacy Layout and Simpleperf actions now use
[`DesktopAiCoordinator`](../desktop-app/src/main/kotlin/com/androidperformancestudio/desktop/DesktopAiCoordinator.kt)
through `SourceAwareLayoutAiAnalysisClient` and `ProductionSimpleperfAiAnalysisClient`. They publish a
frozen request, open the same global host and wait for that exact run's explicitly approved result.
The coordinator does not execute a provider itself; cancellation by an older caller targets its run,
not a newer conversation. Optional source candidates/snippets still require resolver evidence,
workspace authorization and bounded payloads.

## Trust and storage

The default root is `~/.android-performance-studio/`; isolated test/profile roots are supported by
host configuration. Agents and Skills are separated into application-owned `builtin/<id>/<version>/` and
`imported/<id>/<version>/` namespaces. Conversations, artifacts, provider state and audit use their
own directories. See [ADR 0037](../../docs/adr/0037-ai-platform-trust-and-execution-boundaries.md)
for the full directory and upgrade contract.

Imports validate bounded manifests, IDs, paths, runtime declarations and checksums in staging before
atomic publication. Reject traversal, symlinks, undeclared files, hash mismatches and unsupported
schemas/runtimes. A failed import must not publish a partial package. Imported versions are immutable;
content changes require a new version and trust decision. Origin, trust, enabled and revoked state
come from host-owned metadata, never from assertions inside an imported manifest.

Default permissions are deny. Trust and enablement do not grant network, source snippets, scripts,
device writes or arbitrary filesystem access. Effective permissions also require host policy and
current context authorization. Provider credentials remain references: no secret values or raw
provider configuration are copied into platform data, prompts, history, logs or artifacts.

`ConversationRecord`/turns remain reference-only. Separately,
[`StoredAnalysisResult`](ai-platform-storage/src/main/kotlin/com/androidperformancestudio/aiplatform/storage/ConversationHistory.kt)
stores validated, bounded, redacted summaries/findings, model/session/run identity, question hashes,
parent-turn references and artifact hashes. Structured audits retain phase/subject/reference IDs,
budget counters and safe error codes, not prompts, raw responses or stdout/stderr. Successful turn
completion and its result projection are committed atomically; history can reconstruct findings,
but cannot reconstruct the original evidence, source or executable request. Restored evidence
references without canonical context are explicitly unavailable, never guessed or replayed.

`aps.report-artifact` creates an in-memory read-only report. Filesystem publication additionally
requires `WRITE_ARTIFACT`; the publisher escapes content into inert HTML by default, supports
Markdown through its API, and verifies content plus provenance hashes on read/preview. The current
global UI opens verified HTML in the browser; this is not a complete Markdown export/share workflow.
Redaction is a bounded projection policy, not a general anonymity guarantee.

## Provider and sandbox evidence boundaries

- OpenAI is wired through the existing `ai-core` Responses client/gateway. The platform integration
  tests use the real client/gateway with a fake HTTP transport only; they do not establish live
  credentials, network access or model quality.
- Claude has a controlled CLI adapter with version/required-flag checks, bare/no-tools mode, empty
  MCP configuration, no session persistence, stdin evidence and structured-result validation.
  Credential discovery currently requires an `ANTHROPIC_API_KEY` environment reference; managed
  subscription/OAuth login is not a fallback. Adapter tests use fake executables, not live Claude
  authentication or network requests.
- Codex has an explicit **Responses API** adapter using the configured API credential and exact
  requested Codex model. It sets `tool_choice=none`, empty tools, `store=false` and output limits;
  commentary, refusals, incomplete and tool-bearing output cannot masquerade as a final analysis.
  The separate CLI adapter remains `UNSUPPORTED_CAPABILITY`: the installed 0.166 loopback probe
  still exposed a tool with every feature switch disabled. No CLI login/cache or provider fallback is used.
- Linux arm64 Python and Node passed isolated native hostile and public-runtime API checks using
  bubblewrap, prlimit and the seccomp launcher. The packaged launcher is ELF/architecture/hash
  checked at a fixed distribution resource path. This is not native x86_64 or installed-package acceptance.
  macOS remains `E_MACOS_SANDBOX_UNQUALIFIED`, Windows `E_WINDOWS_SANDBOX_UNQUALIFIED`; neither
  falls back to an ordinary process. See [native contracts](../../docs/ai/native-sandbox-contracts.md).
- On 2026-10-31, explicit configured OpenAI and Codex live checks each made one transport attempt
  and timed out without an HTTP response. They are failed acceptance evidence, not successful inference.
  Claude live acceptance still requires its API-key reference. No real account or model quality is qualified.
- Desktop wiring and local/fake-provider/Compose tests are separate from a packaged application,
  real provider operation, OS-native qualification, hosted release, signing or notarization. No
  hosted release or signature acceptance is established by this document.

## Verification

Existing test sources cover registry/storage contracts, approval/generation/cancellation, real
built-in plans with fake providers, structured-result/audit restoration, inert artifacts, isolated
bootstrap/profile ownership, legacy coordination and Compose conversation/evidence/settings states.
Their existence is not a fresh pass result. Record new runs and counts only in TODO/status.

When validation is requested, run from `desktop-viewer`, serially with other composite-build work:

```sh
./gradlew :ai-platform:checkAll :ai-core:test --console=plain
./gradlew :desktop-app:compileKotlin --console=plain
```

`checkAll` includes the platform subprojects' checks/ktlint. The 2026-10-31 integration run also passed
desktop tests and local `createDistributable`; exact counts and evidence are in TODO/status. Remaining
product work includes successful live provider acceptance, macOS/Windows native sandbox support,
native x86_64/installed-package qualification, broader UI/export/retention workflows and real
hosted/signing/notarization acceptance. See the [live harness](../../docs/ai/live-provider-acceptance.md)
and [release gates](../../docs/ai/release-qualification.md); local fixture results do not qualify a release.

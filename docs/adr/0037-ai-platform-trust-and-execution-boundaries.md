# Freeze AI Platform trust, storage and execution boundaries

Status: Accepted — boundary decision; not a claim that every provider, sandbox or UI is available.

Date: 2026-10-31

Extends [ADR 0036](0036-separate-ai-business-layer-with-agents-skills-and-sandboxed-scripts.md)
and preserves the provider-neutral, explicit-trigger, source-authorization and structured-result
boundaries in ADR 0004, ADR 0005, ADR 0013 and ADR 0021.

## Context

Agent/Skill packages, local provider configuration and conversation history cross different trust
boundaries. A valid manifest does not authorize execution, a discovered credential does not authorize
network access, and a bounded child process is not necessarily a sandbox. The independent AI Platform
needs stable contracts before those capabilities are connected to desktop workflows.

## Decision

### Versioned contracts and dependency direction

- `ai-platform-model` owns the version-1 Agent/Skill manifest, provider event, permission, approval,
  conversation, run-state and audit contracts. The Kotlin models and their schema/codec tests are
  authoritative for field names, required values and supported enum members.
- Unsupported schema versions, unknown executable runtimes and malformed or ambiguous manifests
  fail closed. A parser must not interpret unknown permissions as granted, or silently convert an
  unsupported runtime to a supported one.
- `ai-core` remains a lower-level, UI-independent analysis contract and provider adapter. The
  dependency direction is `ai-platform → ai-core`, never `ai-core → ai-platform` or a profiler UI.
  Business context producers and platform contracts do not expose Codex/Claude SDK-specific types.
- Provider events and persisted run states distinguish progress, approval, retryable failure,
  permanent failure, cancellation and completion. Completion requires validation of the result;
  receiving provider text is not evidence of success.

### Application-owned directories and package installation

The default application root is `~/.android-performance-studio/`. Tests and explicitly configured
profiles can supply a separate root; a package cannot choose or expand the root.

```text
~/.android-performance-studio/
├── agents/
│   ├── builtin/<agent-id>/<version>/
│   └── imported/<agent-id>/<version>/
├── skills/
│   ├── builtin/<skill-id>/<version>/
│   └── imported/<skill-id>/<version>/
├── conversations/
├── artifacts/
├── provider-state/
└── audit/
```

- Stable IDs, versions and package-relative paths are validated before any destination is created.
  Absolute paths, traversal, symlinks and undeclared package files cannot grant access outside the
  package. Script count, script size, total bytes and manifest size have explicit limits.
- Package content is versioned and hash-verified. Import uses a staging directory on the destination
  filesystem, validates the manifest and every declared file, then publishes with an atomic move.
  If atomic publication is unavailable or validation fails, installation fails without publishing a
  partial package; it does not fall back to a visible copy-in-place.
- A published imported version is immutable. Re-importing the same identity with different bytes
  is a conflict, not a trusted update. A new version requires its own verification and trust decision.
- Builtin installation uses application-supplied package content and checksums. Upgrade and retry
  must preserve imported packages, existing conversations, settings and unrelated application data.
  Installation alone never invokes an Agent, provider, tool or script.
- Origin, verification/signature state, enabled state, trust and revocation are host-owned registry
  metadata. A package may describe its provenance, but cannot self-assert `builtin`, trusted or
  enabled status. Imported content is untrusted and disabled until an explicit host decision.
- Trust is bound to the verified package identity, version and content hash. Disable and revoke
  prevent future execution; revocation is not undone by scanning or reinstalling a manifest.
- Existing directories are inspected and validated, not recursively deleted or silently replaced.
  Unsupported/corrupt state is reported as unavailable. No migration scans provider secret files or
  replays unfinished work.

### Permissions and approvals

- Default permissions are deny: no arbitrary host filesystem access, network, source snippets,
  device mutation, script execution or artifact publication. Read-only evidence access is bounded
  to the explicit business context and its Evidence/Candidate allowlists.
- Effective permission is the intersection of the package declaration, host policy and the current
  context authorization. Trust and enablement do not widen that intersection.
- Approvals are host-issued decisions bound to the specific run, session, generation, action and
  permission scope. Imported prompts and provider events cannot manufacture approval authority.
  An expired, cancelled or mismatched approval cannot authorize a later turn.
- Source candidates still come from deterministic source resolution. An Agent/provider cannot
  create source paths, line numbers or authorization by returning them in text.

### Provider configuration and credentials

- Discovery reads only the metadata needed to identify an installed runtime, supported version,
  model/profile, capability and credential availability. Absent, unreadable, malformed and unknown
  configuration states remain explicit; discovery does not silently select a different provider.
- Store credential references, not credential values, in the AI Platform. API keys, OAuth tokens,
  cookies, secret environment values and raw provider configuration are not copied into the
  application root, manifests, prompts, history, audit records or artifacts.
- Only the authorized provider adapter resolves a credential reference at execution time. Agents,
  Skills, imported scripts and the UI do not receive the secret. Discovery is not permission to
  call the provider, and a successful connection test is not broad source-upload authorization.
- Errors and audit records use stable codes and bounded, redacted metadata. Raw process output,
  arbitrary exception text and complete environments are not safe audit payloads.

### Script execution fails closed without an actual sandbox

- Python/Node Skill installation and manifest validation do not imply execution support. A script
  runtime is unavailable until a platform-specific isolation mechanism can enforce the declared
  filesystem, credential, network and device boundary.
- Clearing environment variables, choosing a temporary working directory, using an argument list,
  checking a script hash or adding a timeout is useful defense in depth, but none is sufficient
  evidence of sandbox isolation. Do not launch the script as an unrestricted fallback.
- A supported runtime must provide read-only declared inputs, an isolated scratch/output area,
  resource and output limits, bounded process-tree termination, and audit provenance. Side-effect
  capabilities require separate host policy and approval, not a prompt instruction.

### One global conversation entry, immutable business context

- The intended product entry is one `AiConversationWindowHost` backed by the Main Agent. Profiler
  adapters publish bounded business context; they must not introduce independent provider/prompt
  implementations while the platform is adopted.
- A conversation binds an immutable Analysis Session and generation. Missing business context is
  shown as unavailable, not treated as permission for whole-project chat.
- Cancellation and newer generations invalidate late results. Recovery marks unfinished work
  interrupted and does not automatically replay provider calls, tools or scripts.
- Conversation/audit persistence retains structured references, provenance and bounded metadata,
  not credentials, full source bodies, complete profiles or unlimited provider responses.

## Consequences and implementation boundary

These decisions permit independent model, registry, storage and runtime tests before desktop
integration. They do not demonstrate Codex/Claude compatibility, a cross-platform sandbox, global
Chat availability, first-launch desktop installation or release qualification. Those claims need
their own adapter, native-runtime, Compose, packaging and platform evidence in
[`docs/ai/agent-platform-todo-status.md`](../ai/agent-platform-todo-status.md).

The adopted runtime preserves unavailable states for unqualified capabilities. As of 2026-10-31,
desktop bootstrap/settings/global Chat and the explicit Layout/Simpleperf migration are connected
and locally tested. Trusted manifest graphs now execute independent child model sessions and
qualified scripts, with immutable graph approvals, intersected privileges, exact child-run audits,
shared budgets and explicitly untrusted derived analysis. Provider-driven arbitrary tools remain closed.
Linux arm64 Python/Node have isolated native evidence; macOS/Windows backends, native x86_64,
successful live provider operation, installed-package and hosted/signing/notarization qualification
remain separate gates. The current implementation status and local evidence are recorded
in the linked TODO status document rather than inferred from this boundary decision.

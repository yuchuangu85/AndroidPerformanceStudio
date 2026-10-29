# Separate the AI Business Layer from profiler features

Status: Proposed

Supersedes the first-release scope limitation in [the AI source-workspace design](../../desktop-viewer/docs/design/2026-08-01-ai-source-workspace-design.md) that deferred Agent Runtime, MCP and conversational workflows. It does not supersede the evidence, privacy, provider-neutral, source-resolution or structured-output decisions in ADR 0001, ADR 0004, ADR 0005, ADR 0012, ADR 0013, ADR 0014, ADR 0020 and ADR 0021.

## Context

Android Performance Studio currently has evidence-bound AI analysis in profiler-owned clients and a provider-neutral `ai-core`. The product now requires a separate AI business layer that can be entered from any business workflow and can coordinate a main Agent, multiple sub-agents, built-in skills, external agents, external skills and controlled scripts.

The layer must support automatic business entrypoints and an interactive conversation window while preserving the existing rules: evidence must be reviewable, source access must be authorized, findings must reference known IDs, and AI must not silently modify code or execute arbitrary host commands.

## Decision

- Add an independent AI Business Layer above profiler adapters and below the desktop UI. Profiler modules publish typed business contexts through AI Entry Points; they do not call providers directly for product workflows.
- Use one Main Agent Orchestrator as the only planner-facing entry. It may select registered sub-agents and skills, validate permissions and budgets, merge typed outputs, and publish Markdown/HTML artifacts.
- Require every built-in or external Agent/Skill to publish a versioned manifest with schemas, capabilities, permissions, limits, provenance and trust state.
- Support Python and JavaScript/TypeScript through a sandboxed Script Runtime. Scripts receive declared read-only inputs and are denied host credentials, arbitrary paths, network and device mutation by default.
- Make HTML the default report format and Markdown an explicit export format. Every artifact includes provenance, Evidence/Candidate references, extension versions, partial-data warnings and failed-step information.
- Make the conversation window session-bound. A conversation inherits a business context and Analysis Session; it is not a project-wide free chat. Follow-up turns preserve parent relationships and allowlist references.
- Allow external Agents and Skills only through explicit registration, compatibility checks and user trust/permission decisions. No extension may load undeclared extensions or escalate permissions.
- Keep automatic entrypoints explicit: business data may be passed automatically to the Main Agent, but source upload, network access, script execution, device actions and expanded evidence require policy checks and, when applicable, user confirmation.

## Consequences

Positive:

- Profiler features share one AI entry, context, conversation and artifact model.
- New Profiler adapters, Skills and external extensions can be added without duplicating provider transport.
- Script-based analysis becomes reproducible and auditable instead of an implicit shell escape path.
- HTML reports can serve as the default application-facing output while Markdown remains review-friendly.

Costs and risks:

- The AI layer becomes a new cross-platform runtime and permission boundary.
- External extensions and scripts require registry, compatibility, cancellation, sandbox and audit infrastructure.
- The current single-call `AiAnalysisGateway` is insufficient for orchestration and must remain as a provider adapter behind the Main Agent.
- Existing AI docs and UI assumptions must be updated; Simpleperf and other profiler integrations cannot be considered complete until they publish the new entry contract.

## Rejected alternatives

- Letting every Profiler call OpenAI directly: duplicates policy and makes cross-business conversation impossible.
- Exposing a general-purpose shell to the Main Agent: creates an unacceptable credential, source and device mutation boundary.
- Treating MCP as the whole AI layer: MCP can transport selected tools, but it does not define Agent/Skill manifests, business context, output artifacts or script sandboxing.
- Storing only free-form chat text: loses Evidence/Candidate provenance and makes results non-reproducible.
- Allowing the model to generate source paths and line numbers: contradicts deterministic Source Resolver and build evidence rules.

## Follow-up

The implementation plan belongs in [`docs/ai/roadmap.md`](../ai/roadmap.md), the conversation UX in [`docs/ai/conversation-window.md`](../ai/conversation-window.md), and the proposed contracts in [`docs/ai/architecture.md`](../ai/architecture.md).

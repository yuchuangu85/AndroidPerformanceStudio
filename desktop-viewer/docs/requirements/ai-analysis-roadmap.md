# AI analysis roadmap

> Target architecture: [`../design/2026-08-01-ai-source-workspace-design.md`](../design/2026-08-01-ai-source-workspace-design.md)

## Current status

The first evidence-bound implementation is enabled for Layout Inspector and Simpleperf:

- `source-workspace` registers Local, GitHub.com, and AOSP Gitiles providers, resolves moving refs to
  immutable revisions, stores manifests and structural indexes in SQLite, and verifies content-addressed cache reads.
- The desktop Source Workspaces page configures providers and the OpenAI credential, displays background
  indexing progress, controls per-workspace source-upload authorization, browses cached files, and acts as the
  shared read-only Source Viewer.
- Layout Inspector and Simpleperf extract bounded performance evidence, resolve source candidates locally,
  show a payload preflight with a performance-data-only option, and call the provider-neutral `ai-core` gateway.
- The gateway validates every returned Evidence ID and Candidate ID before persisting a versioned Analysis Session.
- Findings navigate directly for a single candidate and expose candidate selection when resolution is ambiguous.

The Source Workspaces AI Settings surface stores model and endpoint preferences; they can still be overridden
with `AGENTPERF_AI_MODEL` and `OPENAI_BASE_URL` for development. Credentials are read from the system credential
store (macOS Keychain) or `OPENAI_API_KEY`.

## Follow-up hardening

- Add native Build ID/`llvm-symbolizer`, R8 `mapping.txt`, and Gradle build-evidence ingestion.
- Add Windows Credential Manager and Linux Secret Service implementations (non-macOS currently uses a process-local store).
- Add representative 100k-file performance benchmarks and cancellation-aware remote transport.
- Expand localized Compose UI and visual-golden coverage for every provider/error state.

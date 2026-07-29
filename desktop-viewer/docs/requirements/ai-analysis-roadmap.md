# AI analysis roadmap

## Current status

The AI analysis implementation is preserved, but its user-facing entry is hidden behind
`AI_ANALYSIS_ENTRY_VISIBLE = false` in `LayoutInspectorMainPage.kt`. Existing analysis models,
OpenAI client code, archive import/export support, and tests remain available for later work.

## Before re-enabling

- Finalize the product flow for credentials, consent, loading, cancellation, and retry.
- Add a settings surface for model and endpoint configuration instead of relying only on environment variables.
- Review redaction and payload-size behavior with representative captures.
- Add Compose UI coverage for entry visibility, progress, failures, and successful findings.
- Document network usage, privacy boundaries, and archive compatibility for users.

When these items are complete, set `AI_ANALYSIS_ENTRY_VISIBLE` to `true` and run the full desktop test suite.

# Bind analysis sessions to immutable source snapshots

Every analysis session is bound to immutable source identities rather than whichever revision is currently open. Local workspaces record their Git revision and dirty-content identity, while GitHub and AOSP workspaces use immutable revisions; if the original snapshot becomes unavailable, the product asks to re-resolve explicitly instead of silently navigating to newer code. This adds snapshot and cache lifecycle work but keeps historical findings reproducible.

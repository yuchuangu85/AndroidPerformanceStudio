# Model source access as a source-workspace module

Rename the current `import-core` composite build to `source-workspace` because the capability owns long-lived providers, immutable snapshots, caching, indexing, build evidence, and deterministic resolution rather than one-time file import. Ordinary profile, trace, heap, and archive imports remain in their feature modules, while `ai-core` remains responsible for provider-neutral AI infrastructure. This establishes the module coordinate and vocabulary before other profilers depend on the misleading generic importer API.

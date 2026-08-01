# Separate metadata, source content, and credentials

Workspace metadata, structural indexes, build-evidence metadata, job state, and analysis records are persisted in SQLite. Large immutable source and symbol objects use a content-addressed filesystem cache, while OpenAI and GitHub credentials remain only in the operating system credential store; report archives carry references rather than cached objects. This separation enables transactional metadata, deduplicated content, independent cache eviction, and credential isolation.

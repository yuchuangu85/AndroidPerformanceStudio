# Minimize and authorize source sent to cloud AI

Cloud analysis never receives a complete source workspace or source index. Deterministic local resolution selects the smallest relevant snippets, strips absolute paths, excludes ignored and sensitive files, and requires per-workspace authorization before source is sent; users may choose a performance-data-only mode. Request telemetry records metadata such as hashes, sizes, model, and status but never source bodies, credentials, or raw prompts.

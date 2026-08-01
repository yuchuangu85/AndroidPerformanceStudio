# Resolve source locations deterministically before AI ranking

Source navigation must be backed by candidates produced from verifiable performance and source identities. AI may rank and explain those candidates, but it may not invent file paths or line numbers; when no candidate is sufficiently supported, the product reports an unresolved location instead of offering a potentially false jump. This trades some apparent AI coverage for reproducible navigation and user trust.

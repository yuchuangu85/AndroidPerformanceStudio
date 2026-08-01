# Build source indexes incrementally and allow partial use

Source workspaces become usable after snapshot identity and the file manifest are known, while structural symbols are indexed incrementally in background jobs. Resolvers may use completed partitions immediately but must attach the index version and completeness state to candidates, and analysis preflight exposes any resulting confidence limitation. Local changes re-index affected files and modules, while immutable remote snapshots extend their index only as more content is materialized.

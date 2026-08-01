# Cache remote source snapshots locally by default

GitHub and AOSP sources are materialized as immutable local read-only snapshots by default so every provider can use the same indexing and navigation pipeline. Users may opt into online source discovery, but a remote search hit cannot become a persisted or navigable resolution candidate until its revision and content are fixed and verified locally. This accepts local storage cost in exchange for reproducibility, offline access, stable search behavior, and fewer provider API limits.

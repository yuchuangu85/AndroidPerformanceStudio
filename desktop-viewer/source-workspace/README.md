# Source Workspace

Shared, UI-independent source workspace, snapshot, provider, cache, index, and resolution infrastructure
for Android Performance Studio.

The module is an independent composite build published as:

```kotlin
implementation("com.androidperformancestudio:source-workspace:0.1.0-SNAPSHOT")
```

The public API lives in `com.androidperformancestudio.source`. It owns Local, GitHub, and AOSP
provider contracts, immutable source snapshots, content-addressed caching, structural symbol
indexing, deterministic resolution candidates, per-workspace AI upload authorization, and SQLite persistence.
Profiler evidence extraction,
AI prompts, Compose UI, and ordinary report imports remain in their owning modules.

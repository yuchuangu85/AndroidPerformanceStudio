# AI Core

Shared, UI-independent AI infrastructure for Android Performance Studio.

The module is an independent composite build published as:

```kotlin
implementation("com.androidperformancestudio:ai-core:0.1.0-SNAPSHOT")
```

It owns provider transport, authentication headers, evidence-bound analysis contracts, strict structured-output
validation, versioned Analysis Session persistence, and credential-store abstractions. Profiler-specific evidence
adapters and Compose rendering stay in their owning feature modules; the desktop application layer coordinates
them with `source-workspace`.

The public API lives in `com.androidperformancestudio.ai` and does not depend on Compose or profiler
domain models.

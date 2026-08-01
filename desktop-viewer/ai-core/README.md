# AI Core

Shared, UI-independent AI infrastructure for Android Performance Studio.

The module is an independent composite build published as:

```kotlin
implementation("com.androidperformancestudio:ai-core:0.1.0-SNAPSHOT")
```

It owns provider transport, authentication headers, structured-response request generation, and
response text extraction. Profiler-specific prompts, schemas, DTO mapping, and domain decisions stay
in their owning feature modules.

The public API lives in `com.androidperformancestudio.ai` and does not depend on Compose or profiler
domain models.

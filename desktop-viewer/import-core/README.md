# Import Core

Shared, UI-independent import contracts and source validation for Android Performance Studio.

The module is an independent composite build published as:

```kotlin
implementation("com.androidperformancestudio:import-core:0.1.0-SNAPSHOT")
```

The public API lives in `com.androidperformancestudio.importing`. It owns reusable import outcomes,
warnings, importer contracts, and filesystem source validation. Format-specific parsing, domain
mapping, persistence, and UI file selection remain in their owning feature modules.

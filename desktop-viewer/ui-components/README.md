# UI Components

Shared Jetpack Compose Desktop controls for Android Performance Studio.

The module is an independent composite build published to the local Gradle composite as:

```kotlin
implementation("com.androidperformancestudio:ui-components:0.1.0-SNAPSHOT")
```

Public controls live in the `com.androidperformancestudio.ui` package. Application and
presentation modules should consume these controls instead of defining profiler-wide variants.
Domain, capture, parsing, storage, and analysis modules remain UI-independent.

Localized controls accept `UiLanguage` rather than language-specific Boolean flags:

```kotlin
localizedStringResource(Res.string.title, UiLanguage.SIMPLIFIED_CHINESE)
```

Add future languages by extending `UiLanguage` and providing the corresponding Compose resource
qualifier directory; existing control signatures do not need to change.

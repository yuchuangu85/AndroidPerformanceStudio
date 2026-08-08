# `com.android.tools.agent.appinspection` Package

This package exists to satisfy the reflection contract expected by the **Compose Inspector** (`androidx.compose.ui.inspection.XrHelper`). Since we are re-using the Compose Inspector used by Layout Inspector we need to satisfy this requirement.

## Reflection Contract
The Compose Inspector uses JVMTI `artTooling` and reflection to locate the view inspector agent and query XR views using the following hardcoded names and signatures:

1. **`com.android.tools.agent.appinspection.ViewLayoutInspector`**:
   Compose Inspector calls `artTooling.findInstances(Inspector::class.java)` filtering for `javaClass.name == "com.android.tools.agent.appinspection.ViewLayoutInspector"` to obtain the View Inspector's `ClassLoader`.
2. **`com.android.tools.agent.appinspection.XrHelper`**:
   Compose Inspector loads `com.android.tools.agent.appinspection.XrHelper` via `classLoader.loadClass(...)` and reflects method `getXrViews(): List<View>` to collect panel root views in XR applications.

> **Warning**: Do not rename, move, or modify the class names or `getXrViews()` method signature in this package without coordinating updates with the Compose Inspector codebase (`androidx/compose/ui/inspection/XrHelper.kt`).

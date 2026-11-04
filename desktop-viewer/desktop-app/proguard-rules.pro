# Suppress known ProGuard warnings from transitive dependency version mismatches.
# These references are guarded at runtime (reflection / optional deps) and never
# actually invoked, but ProGuard treats missing members as hard errors.

-dontwarn dev.chrisbanes.haze.RenderEffect_skikoKt
-dontwarn io.github.composefluent.component.FluentTextContextMenu
-dontwarn org.sqlite.util.LoggerFactory$SLF4JLogger
-dontwarn org.slf4j.**

# JBR binds service interfaces and their methods by name and runtime annotations.
-keep class com.jetbrains.** { *; }
-keepattributes *Annotation*,InnerClasses,EnclosingMethod

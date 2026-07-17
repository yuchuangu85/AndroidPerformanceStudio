package com.androidperformancestudio.visualization

import com.androidperformancestudio.profileanalysis.CallStackFrame
import java.util.Locale

enum class FlameTheme {
    LIGHT,
    DARK,
}

enum class FlameCategoryRole {
    SYSTEM,
    KERNEL,
    NATIVE,
    MANAGED,
    GRAPHICS,
    IO,
    NETWORK,
    OTHER,
}

data class FlameNodeVisualState(
    val selected: Boolean = false,
    val hovered: Boolean = false,
    val context: Boolean = false,
)

@JvmInline
value class FlameGraphColor(
    val argb: Int,
)

data class FlameNodeColors(
    val fill: FlameGraphColor,
    val foreground: FlameGraphColor,
    val outline: FlameGraphColor?,
)

object FlameGraphPalette {
    fun categoryRole(category: String?): FlameCategoryRole {
        val normalized = category?.trim()?.lowercase(Locale.ROOT).orEmpty()
        return when {
            normalized == "io" -> FlameCategoryRole.IO
            normalized.containsAny("kernel", "linux") -> FlameCategoryRole.KERNEL
            normalized.containsAny("java", "kotlin", "managed", "dalvik", "art") -> FlameCategoryRole.MANAGED
            normalized.containsAny("render", "graphic", "gpu", "skia") -> FlameCategoryRole.GRAPHICS
            normalized.containsAny("network", "socket", "http") -> FlameCategoryRole.NETWORK
            normalized.containsAny("disk", "file", "i/o", "storage") -> FlameCategoryRole.IO
            normalized.containsAny("native", "jni", "c++") -> FlameCategoryRole.NATIVE
            normalized.containsAny("system", "runtime", "android") -> FlameCategoryRole.SYSTEM
            else -> FlameCategoryRole.OTHER
        }
    }

    fun categoryRole(
        category: String?,
        frame: CallStackFrame?,
    ): FlameCategoryRole =
        if (category.isNullOrBlank()) {
            frame?.firefoxSimpleperfCategoryRole() ?: FlameCategoryRole.OTHER
        } else {
            categoryRole(category)
        }

    fun colors(
        category: String?,
        theme: FlameTheme,
        state: FlameNodeVisualState = FlameNodeVisualState(),
    ): FlameNodeColors = FirefoxFlameGraphStyle.resolve(theme).nodeColors(category, state)
}

private fun CallStackFrame.firefoxSimpleperfCategoryRole(): FlameCategoryRole {
    val fileInSystem =
        resource.startsWith("/apex/") ||
            resource.startsWith("/system/") ||
            resource.startsWith("/vendor/")
    return when {
        resource == "[kernel.kallsyms]" || resource.endsWith(".ko") -> FlameCategoryRole.KERNEL
        resource.endsWith(".so") -> if (fileInSystem) FlameCategoryRole.SYSTEM else FlameCategoryRole.NATIVE
        resource == "[JIT app cache]" || resource.hasManagedCodeExtension() -> {
            if (fileInSystem || symbolName.isAndroidOrJavaMethod()) {
                FlameCategoryRole.SYSTEM
            } else {
                FlameCategoryRole.MANAGED
            }
        }
        else -> FlameCategoryRole.OTHER
    }
}

private fun String.hasManagedCodeExtension(): Boolean =
    endsWith(".vdex") ||
        endsWith(".apk") ||
        endsWith(".jar") ||
        endsWith(".oat") ||
        endsWith(".odex")

private fun String.isAndroidOrJavaMethod(): Boolean =
    startsWith("java.") ||
        startsWith("javax.") ||
        startsWith("kotlin.") ||
        startsWith("kotlinx.") ||
        startsWith("dalvik.") ||
        startsWith("android.") ||
        startsWith("com.android.") ||
        startsWith("androidx.") ||
        startsWith("libcore.")

private fun String.containsAny(vararg candidates: String): Boolean = candidates.any(::contains)

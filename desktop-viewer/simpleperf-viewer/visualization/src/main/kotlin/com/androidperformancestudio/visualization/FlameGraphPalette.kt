package com.androidperformancestudio.visualization

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

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

    fun colors(
        category: String?,
        theme: FlameTheme,
        state: FlameNodeVisualState = FlameNodeVisualState(),
    ): FlameNodeColors {
        var fill = baseColor(categoryRole(category), theme)
        if (state.selected) fill = blend(fill, selectedOverlay(theme), SELECTED_OVERLAY_ALPHA)
        if (state.hovered) fill = blend(fill, hoverOverlay(theme), HOVER_OVERLAY_ALPHA)
        if (state.context) fill = blend(fill, contextOverlay(theme), CONTEXT_OVERLAY_ALPHA)
        val outline = contextOverlay(theme).takeIf { state.context }
        return FlameNodeColors(fill, contrastingForeground(fill), outline)
    }

    fun contrastingForeground(background: FlameGraphColor): FlameGraphColor =
        if (contrastRatio(background, BLACK) >= contrastRatio(background, WHITE)) BLACK else WHITE

    fun contrastRatio(
        first: FlameGraphColor,
        second: FlameGraphColor,
    ): Double {
        val lighter = max(relativeLuminance(first), relativeLuminance(second))
        val darker = min(relativeLuminance(first), relativeLuminance(second))
        return (lighter + LUMINANCE_OFFSET) / (darker + LUMINANCE_OFFSET)
    }
}

private fun String.containsAny(vararg candidates: String): Boolean = candidates.any(::contains)

private fun baseColor(
    role: FlameCategoryRole,
    theme: FlameTheme,
): FlameGraphColor = (if (theme == FlameTheme.LIGHT) LIGHT_COLORS else DARK_COLORS)[role.ordinal]

@Suppress("MagicNumber")
private fun selectedOverlay(theme: FlameTheme): FlameGraphColor =
    when (theme) {
        FlameTheme.LIGHT -> FlameGraphColor(0xFF1565C0.toInt())
        FlameTheme.DARK -> FlameGraphColor(0xFF64B5F6.toInt())
    }

@Suppress("MagicNumber")
private fun hoverOverlay(theme: FlameTheme): FlameGraphColor =
    when (theme) {
        FlameTheme.LIGHT -> WHITE
        FlameTheme.DARK -> FlameGraphColor(0xFFCFD8DC.toInt())
    }

@Suppress("MagicNumber")
private fun contextOverlay(theme: FlameTheme): FlameGraphColor =
    when (theme) {
        FlameTheme.LIGHT -> FlameGraphColor(0xFFAD1457.toInt())
        FlameTheme.DARK -> FlameGraphColor(0xFFF06292.toInt())
    }

private fun blend(
    base: FlameGraphColor,
    overlay: FlameGraphColor,
    overlayAlpha: Double,
): FlameGraphColor {
    fun channel(shift: Int): Int {
        val baseChannel = base.argb ushr shift and CHANNEL_MASK
        val overlayChannel = overlay.argb ushr shift and CHANNEL_MASK
        return (baseChannel * (1.0 - overlayAlpha) + overlayChannel * overlayAlpha).toInt().coerceIn(0, CHANNEL_MASK)
    }
    return FlameGraphColor(
        ALPHA_MASK or (channel(RED_SHIFT) shl RED_SHIFT) or
            (channel(GREEN_SHIFT) shl GREEN_SHIFT) or channel(BLUE_SHIFT),
    )
}

private fun relativeLuminance(color: FlameGraphColor): Double {
    fun linearized(shift: Int): Double {
        val component = (color.argb ushr shift and CHANNEL_MASK) / CHANNEL_MASK.toDouble()
        return if (component <= SRGB_LINEAR_THRESHOLD) {
            component / SRGB_LINEAR_DIVISOR
        } else {
            ((component + SRGB_OFFSET) / SRGB_SCALE).pow(SRGB_EXPONENT)
        }
    }
    return RED_LUMINANCE * linearized(RED_SHIFT) +
        GREEN_LUMINANCE * linearized(GREEN_SHIFT) +
        BLUE_LUMINANCE * linearized(BLUE_SHIFT)
}

private val BLACK = FlameGraphColor(0xFF000000.toInt())
private val WHITE = FlameGraphColor(0xFFFFFFFF.toInt())

@Suppress("MagicNumber")
private val LIGHT_COLORS =
    arrayOf(
        FlameGraphColor(0xFF7986CB.toInt()),
        FlameGraphColor(0xFF9575CD.toInt()),
        FlameGraphColor(0xFFE57373.toInt()),
        FlameGraphColor(0xFF64B5F6.toInt()),
        FlameGraphColor(0xFF4DB6AC.toInt()),
        FlameGraphColor(0xFFFFB74D.toInt()),
        FlameGraphColor(0xFF81C784.toInt()),
        FlameGraphColor(0xFF90A4AE.toInt()),
    )

@Suppress("MagicNumber")
private val DARK_COLORS =
    arrayOf(
        FlameGraphColor(0xFF3949AB.toInt()),
        FlameGraphColor(0xFF5E35B1.toInt()),
        FlameGraphColor(0xFFC62828.toInt()),
        FlameGraphColor(0xFF1565C0.toInt()),
        FlameGraphColor(0xFF00796B.toInt()),
        FlameGraphColor(0xFFEF6C00.toInt()),
        FlameGraphColor(0xFF2E7D32.toInt()),
        FlameGraphColor(0xFF455A64.toInt()),
    )
private const val ALPHA_MASK = -0x1000000
private const val CHANNEL_MASK = 0xFF
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val BLUE_SHIFT = 0
private const val SELECTED_OVERLAY_ALPHA = 0.45
private const val HOVER_OVERLAY_ALPHA = 0.18
private const val CONTEXT_OVERLAY_ALPHA = 0.20
private const val LUMINANCE_OFFSET = 0.05
private const val SRGB_LINEAR_THRESHOLD = 0.04045
private const val SRGB_LINEAR_DIVISOR = 12.92
private const val SRGB_OFFSET = 0.055
private const val SRGB_SCALE = 1.055
private const val SRGB_EXPONENT = 2.4
private const val RED_LUMINANCE = 0.2126
private const val GREEN_LUMINANCE = 0.7152
private const val BLUE_LUMINANCE = 0.0722

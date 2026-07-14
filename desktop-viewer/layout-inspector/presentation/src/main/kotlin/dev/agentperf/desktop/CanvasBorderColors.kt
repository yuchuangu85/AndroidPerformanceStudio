package dev.agentperf.desktop

import androidx.compose.ui.graphics.Color
import java.util.Locale
import java.util.prefs.Preferences

@JvmInline
internal value class CanvasArgb(val value: Long) {
    fun toComposeColor(): Color = Color(value)
    fun toHex(): String = String.format(Locale.US, "#%08X", value)

    companion object {
        fun parse(value: String?): CanvasArgb? {
            val digits = value?.trim()?.removePrefix("#") ?: return null
            val argb = when (digits.length) {
                6 -> "FF$digits"
                8 -> digits
                else -> return null
            }
            if (!argb.matches(Regex("[0-9A-Fa-f]{8}"))) return null
            return CanvasArgb(argb.toLong(16))
        }
    }
}

internal data class CanvasBorderColors(
    val normal: CanvasArgb = CanvasArgb(0xFF7DD3FC),
    val hovered: CanvasArgb = CanvasArgb(0xFFF59E0B),
    val selected: CanvasArgb = CanvasArgb(0xFFEF4444),
)

internal val canvasColorPresets = listOf(
    CanvasArgb(0xFF7DD3FC),
    CanvasArgb(0xFFF59E0B),
    CanvasArgb(0xFFEF4444),
    CanvasArgb(0xFF22C55E),
    CanvasArgb(0xFFA855F7),
    CanvasArgb(0xFFFFFFFF),
)

internal class CanvasBorderColorStore(
    private val read: (String) -> String?,
    private val write: (String, String) -> Unit,
) {
    fun load(): CanvasBorderColors {
        val defaults = CanvasBorderColors()
        return CanvasBorderColors(
            normal = CanvasArgb.parse(read(NORMAL)) ?: defaults.normal,
            hovered = CanvasArgb.parse(read(HOVERED)) ?: defaults.hovered,
            selected = CanvasArgb.parse(read(SELECTED)) ?: defaults.selected,
        )
    }

    fun save(colors: CanvasBorderColors) {
        write(NORMAL, colors.normal.toHex())
        write(HOVERED, colors.hovered.toHex())
        write(SELECTED, colors.selected.toHex())
    }

    companion object {
        private const val NORMAL = "canvas.bounds.normal"
        private const val HOVERED = "canvas.bounds.hovered"
        private const val SELECTED = "canvas.bounds.selected"

        fun desktop(): CanvasBorderColorStore {
            val preferences = runCatching {
                Preferences.userNodeForPackage(CanvasBorderColorStore::class.java)
            }.getOrNull()
            return CanvasBorderColorStore(
                read = { key -> runCatching { preferences?.get(key, null) }.getOrNull() },
                write = { key, value -> runCatching { preferences?.put(key, value) }; Unit },
            )
        }
    }
}

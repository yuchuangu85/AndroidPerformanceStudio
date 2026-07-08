package dev.agentperf.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HeaderControlTest {
    @Test
    fun `manual refresh control is a labeled text button without an icon`() {
        assertTrue(ManualRefreshButtonStyle.WIDTH_DP >= 54)
        assertEquals(22, ManualRefreshButtonStyle.HEIGHT_DP)
        assertEquals("Refresh", ViewerStrings.forLanguage(ViewerLanguage.ENGLISH).refresh)
        assertEquals("刷新", ViewerStrings.forLanguage(ViewerLanguage.SIMPLIFIED_CHINESE).refresh)

        val source = Files.readString(
            Path.of("src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt"),
        )
        val manualRefreshButton = source
            .substringAfter("private fun ManualRefreshButton(")
            .substringBefore("internal object ManualRefreshButtonStyle")

        assertFalse(manualRefreshButton.contains("RefreshGlyph("))
        assertFalse(manualRefreshButton.contains("Canvas("))
    }

    @Test
    fun `header does not show a theme shortcut after auto scan`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt"),
        )
        val header = source
            .substringAfter("private fun Header(")
            .substringBefore("private fun WindowSelector(")

        assertFalse(header.contains("ThemeToggleButton("))
        assertFalse(header.contains("onToggleTheme"))
    }

    @Test
    fun `settings theme labels remain localized`() {
        val english = ViewerStrings.forLanguage(ViewerLanguage.ENGLISH)
        val chinese = ViewerStrings.forLanguage(ViewerLanguage.SIMPLIFIED_CHINESE)

        assertEquals("Light", english.themePreferenceName(ThemePreference.LIGHT))
        assertEquals("Dark", english.themePreferenceName(ThemePreference.DARK))
        assertEquals("亮色主题", chinese.themePreferenceName(ThemePreference.LIGHT))
        assertEquals("暗色主题", chinese.themePreferenceName(ThemePreference.DARK))
    }
}

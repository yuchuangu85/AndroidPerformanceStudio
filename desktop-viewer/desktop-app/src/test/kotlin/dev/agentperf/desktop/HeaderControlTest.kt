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
    fun `manual refresh appears before auto scan in the header`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt"),
        )
        val header = source
            .substringAfter("private fun Header(")
            .substringBefore("private fun WindowSelector(")

        assertTrue(
            header.indexOf("ManualRefreshButton(") < header.indexOf("AutoScanSwitch("),
            "ManualRefreshButton should be rendered before AutoScanSwitch",
        )
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
    @Test
    fun `device selector appears before window selector in the header`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt"),
        )
        val header = source
            .substringAfter("private fun Header(")
            .substringBefore("private fun DeviceSelector(")

        assertTrue(
            header.indexOf("DeviceSelector(") < header.indexOf("WindowSelector("),
            "DeviceSelector should be rendered before WindowSelector",
        )
    }

    @Test
    fun `auto device label is localized`() {
        assertEquals("Auto device", ViewerStrings.forLanguage(ViewerLanguage.ENGLISH).autoDevice)
        assertEquals("自动设备", ViewerStrings.forLanguage(ViewerLanguage.SIMPLIFIED_CHINESE).autoDevice)
    }

    @Test
    fun `window selector is explicit and only shown for multiple windows`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/dev/agentperf/desktop/DesktopViewerApp.kt"),
        )
        val header = source
            .substringAfter("private fun Header(")
            .substringBefore("private fun DeviceSelector(")
        val selector = source
            .substringAfter("private fun WindowSelector(")
            .substringBefore("private fun ExportResultDialog(")

        assertTrue(header.contains("if (model.windows.size > 1)"))
        assertTrue(selector.contains("strings.window"))
        assertTrue(selector.contains("strings.selectWindow"))
        assertTrue(selector.contains(".border("))
        assertEquals("Window", ViewerStrings.forLanguage(ViewerLanguage.ENGLISH).window)
        assertEquals("窗口", ViewerStrings.forLanguage(ViewerLanguage.SIMPLIFIED_CHINESE).window)
        assertEquals(
            "Select window",
            ViewerStrings.forLanguage(ViewerLanguage.ENGLISH).selectWindow,
        )
        assertEquals(
            "选择窗口",
            ViewerStrings.forLanguage(ViewerLanguage.SIMPLIFIED_CHINESE).selectWindow,
        )
    }

}

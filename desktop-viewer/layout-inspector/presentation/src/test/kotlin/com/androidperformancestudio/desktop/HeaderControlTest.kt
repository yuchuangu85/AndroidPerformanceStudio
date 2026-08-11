package com.androidperformancestudio.desktop

import com.androidperformancestudio.presentation.generated.resources.Res
import com.androidperformancestudio.presentation.generated.resources.*
import com.androidperformancestudio.ui.UiLanguage
import com.androidperformancestudio.ui.localizedStringResource
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HeaderControlTest {
    @Test
    fun `home button is the first header control and exposes a localized description`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"),
        )
        val header = source
            .substringAfter("HeaderToolbar(")
            .substringBefore("private fun CaptureTargetSelector(")
        val sharedHeader = Files.readString(
            Path.of("../../ui-components/src/main/kotlin/com/androidperformancestudio/ui/HeaderToolbar.kt"),
        )

        assertTrue(source.indexOf("HeaderToolbar(") < source.indexOf("Text(packageName"))
        assertTrue(header.contains("onNavigateHome = onNavigateHome"))
        assertTrue(sharedHeader.contains("if (onNavigateHome != null)"))
        assertTrue(sharedHeader.contains("HomeButton("))
        assertTrue(sharedHeader.contains("Res.string.back_to_home"))
        assertTrue(sharedHeader.contains("onClick = onNavigateHome"))
        assertEquals(
            "Back to home",
            localizedStringResource(Res.string.back_to_home, UiLanguage.ENGLISH),
        )
        assertEquals(
            "返回主页",
            localizedStringResource(Res.string.back_to_home, UiLanguage.SIMPLIFIED_CHINESE),
        )
    }

    @Test
    fun `manual refresh control is a labeled text button without an icon`() {
        assertEquals("Refresh", localizedStringResource(Res.string.refresh, UiLanguage.ENGLISH))
        assertEquals("刷新", localizedStringResource(Res.string.refresh, UiLanguage.SIMPLIFIED_CHINESE))

        val source = Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"),
        )
        val manualRefreshButton = source
            .substringAfter("private fun ManualRefreshButton(")
            .substringBefore("private enum class PanelPosition")

        assertTrue(manualRefreshButton.contains("ProfilerCompactButton("))
        assertTrue(manualRefreshButton.contains(".width(56.dp)"))
        assertFalse(manualRefreshButton.contains("RefreshGlyph("))
        assertFalse(manualRefreshButton.contains("Canvas("))
    }

    @Test
    fun `manual refresh appears before auto scan in the header`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"),
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
            Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"),
        )
        val header = source
            .substringAfter("private fun Header(")
            .substringBefore("private fun WindowSelector(")

        assertFalse(header.contains("ThemeToggleButton("))
        assertFalse(header.contains("onToggleTheme"))
    }


    @Test
    fun `device selector appears before window selector in the header`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"),
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
        assertEquals("Auto device", localizedStringResource(Res.string.auto_device, UiLanguage.ENGLISH))
        assertEquals("自动设备", localizedStringResource(Res.string.auto_device, UiLanguage.SIMPLIFIED_CHINESE))
    }

    @Test
    fun `device and capture target use the shared dropdown selector`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"),
        )
        val targetSelector = source
            .substringAfter("private fun CaptureTargetSelector(")
            .substringBefore("private fun DeviceSelector(")
        val deviceSelector = source
            .substringAfter("private fun DeviceSelector(")
            .substringBefore("private fun WindowSelector(")

        assertTrue(targetSelector.contains("DropdownSelector("))
        assertTrue(deviceSelector.contains("DropdownSelector("))
    }

    @Test
    fun `window selector is explicit and only shown for multiple windows`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"),
        )
        val header = source
            .substringAfter("private fun Header(")
            .substringBefore("private fun DeviceSelector(")
        val selector = source
            .substringAfter("private fun WindowSelector(")
            .substringBefore("private fun ExportResultDialog(")

        assertTrue(header.contains("if (model.windows.size > 1)"))
        assertTrue(selector.contains("Res.string.window"))
        assertTrue(selector.contains("Res.string.select_window"))
        assertTrue(selector.contains("DropdownSelector("))
        assertTrue(selector.contains("selectorDescription"))
        assertEquals("Window", localizedStringResource(Res.string.window, UiLanguage.ENGLISH))
        assertEquals("窗口", localizedStringResource(Res.string.window, UiLanguage.SIMPLIFIED_CHINESE))
        assertEquals(
            "Select window",
            localizedStringResource(Res.string.select_window, UiLanguage.ENGLISH),
        )
        assertEquals(
            "选择窗口",
            localizedStringResource(Res.string.select_window, UiLanguage.SIMPLIFIED_CHINESE),
        )
    }

}

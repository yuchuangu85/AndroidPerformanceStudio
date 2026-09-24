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

        assertTrue(source.indexOf("HeaderToolbar(") < source.indexOf("DeviceSelector("))
        assertFalse(header.contains("Text(packageName"))
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
    fun `bottom status bar omits panels while header restores ordered panel controls`() {
        assertEquals("Package", localizedStringResource(Res.string.package_name, UiLanguage.ENGLISH))
        assertEquals("包名", localizedStringResource(Res.string.package_name, UiLanguage.SIMPLIFIED_CHINESE))
        assertEquals("Metrics", localizedStringResource(Res.string.metrics, UiLanguage.ENGLISH))
        assertEquals("指标", localizedStringResource(Res.string.metrics, UiLanguage.SIMPLIFIED_CHINESE))
        assertEquals("Timeline", localizedStringResource(Res.string.timeline, UiLanguage.ENGLISH))
        assertEquals("时间线", localizedStringResource(Res.string.timeline, UiLanguage.SIMPLIFIED_CHINESE))

        val source = Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"),
        )
        val header = source
            .substringAfter("HeaderToolbar(")
            .substringBefore("correlationHint?.let")
        val statusBar = source
            .substringAfter("private fun LayoutInspectorStatusBar(")
            .substringBefore("private enum class PanelPosition")

        assertTrue(header.contains("ScanModeButtons("))
        assertFalse(header.contains("model.metricsText"))
        assertTrue(statusBar.contains(".height(ViewerDimensions.footerHeight)"))
        assertTrue(statusBar.contains("localizedStringResource(Res.string.package_name, language)"))
        assertTrue(statusBar.contains("localizedStringResource(Res.string.metrics, language)"))
        assertTrue(statusBar.contains("localizedStringResource(Res.string.timeline, language)"))
        assertFalse(statusBar.contains("Res.string.panels"))
        assertFalse(statusBar.contains("PanelToggleButton("))
        assertTrue(statusBar.contains("ProfilerCompactButton("))
        assertEquals(3, header.split("PanelToggleButton(").size - 1)
        assertTrue(header.indexOf("ScanModeButtons(") < header.indexOf("Res.drawable.ic_toggle_left"))
        assertTrue(
            header.indexOf("Res.drawable.ic_toggle_left") <
                header.indexOf("Res.drawable.ic_toggle_bottom"),
        )
        assertTrue(
            header.indexOf("Res.drawable.ic_toggle_bottom") <
                header.indexOf("Res.drawable.ic_toggle_right"),
        )
        assertTrue(header.contains("contentDescription = localizedStringResource(Res.string.toggle_hierarchy, language)"))
    }

    @Test
    fun `findings ends its vertically centered header with the Refresh button shape`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"),
        )
        val findingsHeader = source
            .substringAfter("private fun FindingsPane(")
            .substringAfter("Column(modifier.background(colors.panel)) {")
            .substringBefore("if (showTimeline) {")

        assertTrue(findingsHeader.contains("verticalAlignment = Alignment.CenterVertically"))
        assertFalse(findingsHeader.contains("TextButton("))
        assertTrue(findingsHeader.contains("ProfilerCompactButton("))
        assertTrue(findingsHeader.contains("selected = aiAnalysisUiState is AiAnalysisUiState.Working"))
        assertTrue(
            findingsHeader.indexOf("Res.string.timeline_live_capture") < findingsHeader.indexOf("ProfilerCompactButton("),
        )
    }

    @Test
    fun `header and panel toggle icons use the active theme color`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"),
        )
        val sharedHeader = Files.readString(
            Path.of("../../ui-components/src/main/kotlin/com/androidperformancestudio/ui/HeaderToolbar.kt"),
        )
        val sharedSearch = Files.readString(
            Path.of("../../ui-components/src/main/kotlin/com/androidperformancestudio/ui/search/CompactSearchField.kt"),
        )
        val panelToggle = source
            .substringAfter("private fun PanelToggleButton(")
            .substringBefore("private fun HierarchyPane(")

        assertTrue(sharedHeader.contains("val colors = LocalViewerColors.current"))
        assertTrue(sharedHeader.contains("colors = colors"))
        assertTrue(panelToggle.contains("val iconColor = colors.accent"))
        assertFalse(panelToggle.contains("colors.mutedText"))
        assertTrue(sharedSearch.contains("colors.accent"))
        assertTrue(sharedSearch.contains("CompactSearchNavigationButton"))
    }

    @Test
    fun `layout inspector custom action buttons use the active theme color`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"),
        )

        listOf(
            "private fun PreviewZoomButton(",
            "private fun LayerVisibilityButton(",
            "private fun HitTestOrderToggle(",
            "private fun CanvasModeToggle(",
            "private fun TimelineScrollButton(",
        ).forEach { function ->
            val control = source.substringAfter(function)
            assertTrue(control.contains("colors.accent"), "$function should use the active accent")
        }
    }

    @Test
    fun `scan controls use a mutually exclusive shared segmented selector`() {
        assertEquals("Auto scan", localizedStringResource(Res.string.auto_scan, UiLanguage.ENGLISH))
        assertEquals("自动扫描", localizedStringResource(Res.string.auto_scan, UiLanguage.SIMPLIFIED_CHINESE))
        assertEquals("Refresh", localizedStringResource(Res.string.refresh, UiLanguage.ENGLISH))
        assertEquals("刷新", localizedStringResource(Res.string.refresh, UiLanguage.SIMPLIFIED_CHINESE))

        val source = Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"),
        )
        val scanControls = source
            .substringAfter("private fun ScanModeButtons(")
            .substringBefore("private fun LayoutInspectorStatusBar(")
        val selector =
            Files.readString(
                Path.of(
                    "../../ui-components/src/main/kotlin/com/androidperformancestudio/ui/SegmentedSelector.kt",
                ),
            )
        val dimensions =
            Files.readString(
                Path.of("../../ui-components/src/main/kotlin/com/androidperformancestudio/ui/ViewerTheme.kt"),
            )
        val compactButton =
            Files.readString(
                Path.of("../../ui-components/src/main/kotlin/com/androidperformancestudio/ui/ProfilerMacOsControls.kt"),
            )

        assertEquals(1, Regex("SegmentedSelector\\(").findAll(scanControls).count())
        assertTrue(scanControls.contains("items = listOf(ScanMode.MANUAL, ScanMode.AUTOMATIC)"))
        assertTrue(scanControls.contains("selectedItem = scanControlState.selectedMode"))
        assertTrue(scanControls.contains("style = SegmentedSelectorStyle.COMPACT_OUTLINED"))
        assertTrue(scanControls.contains("onSelectedItemClick"))
        assertTrue(scanControls.contains("onAutoScanSelected()"))
        assertTrue(scanControls.contains("onManualRefreshSelected()"))
        assertTrue(selector.contains("SegmentedSelectorStyle.COMPACT_OUTLINED"))
        assertTrue(selector.contains("ViewerDimensions.compactControlHeight"))
        assertTrue(selector.contains("ViewerDimensions.compactControlRadius"))
        assertTrue(selector.contains("colors.accent"))
        assertTrue(dimensions.contains("compactControlHeight = 24.dp"))
        assertTrue(dimensions.contains("compactControlRadius = 4.dp"))
        assertTrue(compactButton.contains("ViewerDimensions.compactControlHeight"))
        assertTrue(compactButton.contains("ViewerDimensions.compactControlRadius"))
        assertFalse(source.contains("MacOSChoiceChip("))
        assertFalse(source.contains("private fun AutoScanSwitch("))
        assertFalse(source.contains("private fun ManualRefreshButton("))
    }

    @Test
    fun `manual refresh button disables automatic scanning before it requests a refresh`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"),
        )
        val header = source
            .substringAfter("private fun Header(")
            .substringBefore("private fun WindowSelector(")

        assertTrue(header.contains("ScanModeButtons("))
        assertTrue(header.contains("if (!autoScanEnabled && archiveUiState !is CaptureArchiveUiState.Working)"))
        assertTrue(header.contains("!fullComposeEnabled"))
        assertTrue(header.contains("!manualRefreshInProgress"))
        assertTrue(header.contains("if (autoScanEnabled) performAction(ViewerAction.TOGGLE_AUTO_SCAN)"))
        assertTrue(header.contains("manualRefreshRequest += 1"))
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

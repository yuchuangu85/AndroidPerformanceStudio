package com.androidperformancestudio.desktop

import com.androidperformancestudio.ui.UiLanguage
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeViewerRecentMenuTest {
    @Test
    fun `file menu exposes localized recent archives and disambiguates duplicate names`() {
        val first = Path.of("/captures/first/layout.apinspect")
        val second = Path.of("/captures/second/layout.apinspect")
        val model = NativeViewerMenuModel(
            language = UiLanguage.SIMPLIFIED_CHINESE,
            selectedNodeId = null,
            autoScanEnabled = false,
            panelVisibility = PanelVisibility(),
            archiveOperationInProgress = false,
            canExportArchive = false,
            canImportScreenshot = false,
            recentArchives = listOf(first, second),
            isMacOs = true,
        )

        assertEquals("最近打开", model.openRecentTitle)
        assertEquals("暂无最近归档", model.noRecentLabel)
        assertEquals("清除菜单", model.clearRecentLabel)
        assertEquals(
            listOf(first, second).map { it.toAbsolutePath().normalize().toString() },
            model.recentItems.map { it.label },
        )
        assertTrue(model.recentEnabled)
    }

    @Test
    fun `recent archive menu is disabled during archive operations`() {
        val model = NativeViewerMenuModel(
            language = UiLanguage.ENGLISH,
            selectedNodeId = null,
            autoScanEnabled = false,
            panelVisibility = PanelVisibility(),
            archiveOperationInProgress = true,
            canExportArchive = true,
            canImportScreenshot = true,
            recentArchives = listOf(Path.of("/captures/layout.apinspect")),
            isMacOs = false,
        )

        assertEquals("Open Recent", model.openRecentTitle)
        assertFalse(model.recentEnabled)
    }

    @Test
    fun `native file menu wires recent archive items and clear action`() {
        val source = Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/desktop/NativeViewerMenuBar.kt"),
        )

        assertTrue(source.contains("Menu(model.openRecentTitle, enabled = model.recentEnabled)"))
        assertTrue(source.contains("onOpenRecentArchive: (Path) -> Unit"))
        assertTrue(source.contains("onClick = { onOpenRecentArchive(item.path) }"))
        assertTrue(source.contains("onClick = onClearRecentArchives"))
    }
}

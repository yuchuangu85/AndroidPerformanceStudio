package com.androidperformancestudio.desktop

import androidx.compose.ui.input.key.Key
import com.androidperformancestudio.ui.UiLanguage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeViewerMenuBarTest {
    @Test
    fun `visible view bounds menu item is checked by default`() {
        val model =
            NativeViewerMenuModel(
                language = UiLanguage.SIMPLIFIED_CHINESE,
                selectedNodeId = null,
                autoScanEnabled = false,
                panelVisibility = PanelVisibility(),
                viewDisplayOptions = ViewDisplayOptions(),
                archiveOperationInProgress = false,
                canExportArchive = false,
                canImportScreenshot = false,
                isMacOs = true,
            )

        assertTrue(
            model.viewItems
                .single { it.option == ViewDisplayOption.SHOW_VISIBLE_VIEW_BOUNDS }
                .checked,
        )
    }

    @Test
    fun `native menu mirrors action ordering labels and groups`() {
        val language = UiLanguage.SIMPLIFIED_CHINESE
        val model = NativeViewerMenuModel(
            language = language,
            selectedNodeId = "root",
            autoScanEnabled = true,
            panelVisibility = PanelVisibility(showFindings = false),
            viewDisplayOptions = ViewDisplayOptions(
                hideInvisibleHierarchyViews = true,
                hideInvisibleFindings = false,
                hideHierarchyIndices = true,
                showHierarchyIds = false,
                showHierarchyLayerVisibilityButtons = true,
                showVisibleViewBounds = true,
            ),
            archiveOperationInProgress = false,
            canExportArchive = true,
            canImportScreenshot = true,
            isMacOs = true,
        )

        assertEquals(
            ViewerActionMenu.items(language).map { it.action },
            model.actions.map { it.action },
        )
        assertEquals(
            ViewerActionMenu.items(language).map { it.group },
            model.actions.map { it.group },
        )
        assertTrue(model.actions.first().checked)
        assertTrue(
            model.actions.single { it.action == ViewerAction.TOGGLE_FINDINGS }.checked,
        )
        assertFalse(
            model.actions.single { it.action == ViewerAction.TOGGLE_HIERARCHY_IDS }.checked,
        )
        assertEquals(
            "显示布局 ID",
            model.actions.single { it.action == ViewerAction.TOGGLE_HIERARCHY_IDS }.label,
        )
        assertEquals(
            listOf(
                ViewDisplayOption.HIDE_INVISIBLE_HIERARCHY_VIEWS,
                ViewDisplayOption.HIDE_INVISIBLE_FINDINGS,
                ViewDisplayOption.HIDE_HIERARCHY_INDICES,
                ViewDisplayOption.SHOW_HIERARCHY_LAYER_VISIBILITY_BUTTONS,
                ViewDisplayOption.SHOW_VISIBLE_VIEW_BOUNDS,
            ),
            model.viewItems.map { it.option },
        )
        assertEquals(
            listOf(
                "隐藏层级结构中的不可见视图",
                "隐藏问题列表中的不可见视图内容",
                "隐藏层级索引",
                "显示层级结构中的显示按钮",
                "显示全部可见视图边缘",
            ),
            model.viewItems.map { it.label },
        )
        assertEquals(listOf(0, 0, 0, 1, 2), model.viewItems.map { it.group })
        assertEquals(listOf(true, false, true, true, true), model.viewItems.map { it.checked })
        assertEquals("截图…", model.importScreenshotLabel)
        assertEquals("采集归档…", model.exportArchiveLabel)
        assertTrue(model.openEnabled)
        assertTrue(model.importScreenshotEnabled)
        assertTrue(model.exportEnabled)
    }

    @Test
    fun `native command shortcuts use the host primary modifier`() {
        assertEquals(
            NativeMenuShortcut(Key.R, ctrl = false, meta = true),
            viewerActionNativeShortcut(ViewerAction.TOGGLE_AUTO_SCAN, isMacOs = true),
        )
        assertEquals(
            NativeMenuShortcut(Key.R, ctrl = true, meta = false),
            viewerActionNativeShortcut(ViewerAction.TOGGLE_AUTO_SCAN, isMacOs = false),
        )
        assertNull(
            viewerActionNativeShortcut(ViewerAction.NEXT_NODE, isMacOs = true),
        )
    }

    @Test
    fun `native file actions are disabled while an archive operation is active`() {
        val model = NativeViewerMenuModel(
            language = UiLanguage.ENGLISH,
            selectedNodeId = null,
            autoScanEnabled = false,
            panelVisibility = PanelVisibility(),
            viewDisplayOptions = ViewDisplayOptions(),
            archiveOperationInProgress = true,
            canExportArchive = true,
            canImportScreenshot = true,
            isMacOs = false,
        )

        assertFalse(model.openEnabled)
        assertFalse(model.importScreenshotEnabled)
        assertFalse(model.exportEnabled)
    }

    @Test
    fun `native export requires a loaded capture while import remains enabled`() {
        val model = NativeViewerMenuModel(
            language = UiLanguage.ENGLISH,
            selectedNodeId = null,
            autoScanEnabled = false,
            panelVisibility = PanelVisibility(),
            viewDisplayOptions = ViewDisplayOptions(),
            archiveOperationInProgress = false,
            canExportArchive = false,
            canImportScreenshot = false,
            isMacOs = false,
        )

        assertTrue(model.openEnabled)
        assertFalse(model.importScreenshotEnabled)
        assertFalse(model.exportEnabled)
    }

    @Test
    fun `panel toggles live in View and inspector actions in Tools`() {
        val source = java.nio.file.Files.readString(
            java.nio.file.Path.of("src/main/kotlin/com/androidperformancestudio/desktop/NativeViewerMenuBar.kt"),
        )

        assertTrue(source.contains("ActivePageMenu("))
        assertTrue(source.contains("onOpen = onImportArchive"))
        assertTrue(source.contains("panelActions.forEach { item -> ActionMenuItem(item, onAction) }"))
        assertTrue(source.contains("it.action != ViewerAction.OPEN_SETTINGS"))
        // Open uses the window's shared Cmd/Ctrl+O; the old Cmd/Ctrl+I import shortcut is gone.
        assertFalse(source.contains("Key.I"))
    }
}

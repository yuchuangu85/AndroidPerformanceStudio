package dev.agentperf.desktop

import androidx.compose.ui.input.key.Key
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class NativeViewerMenuBarTest {
    @Test
    fun `native menu mirrors action ordering labels and groups`() {
        val strings = ViewerStrings.forLanguage(ViewerLanguage.SIMPLIFIED_CHINESE)
        val model = NativeViewerMenuModel(
            strings = strings,
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
            isMacOs = true,
        )

        assertEquals("操作", model.actionsTitle)
        assertEquals(
            ViewerActionMenu.items(strings).map { it.action },
            model.actions.map { it.action },
        )
        assertEquals(
            ViewerActionMenu.items(strings).map { it.group },
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
        assertEquals("视图", model.viewTitle)
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
        assertEquals("文件", model.fileTitle)
        assertEquals("导入", model.importLabel)
        assertEquals("导出", model.exportLabel)
        assertTrue(model.importEnabled)
        assertTrue(model.exportEnabled)
    }

    @Test
    fun `native file menu pads short localized labels to a minimum width`() {
        val model = NativeViewerMenuModel(
            strings = ViewerStrings.forLanguage(ViewerLanguage.SIMPLIFIED_CHINESE),
            selectedNodeId = null,
            autoScanEnabled = false,
            panelVisibility = PanelVisibility(),
            viewDisplayOptions = ViewDisplayOptions(),
            archiveOperationInProgress = false,
            canExportArchive = true,
            isMacOs = false,
        )

        assertTrue(model.importMenuText.length >= NATIVE_MENU_ITEM_MIN_TEXT_COLUMNS)
        assertTrue(model.exportMenuText.length >= NATIVE_MENU_ITEM_MIN_TEXT_COLUMNS)
        assertTrue(model.importMenuText.startsWith(model.importLabel))
        assertTrue(model.exportMenuText.startsWith(model.exportLabel))
    }

    @Test
    fun `native file shortcuts use the host primary modifier`() {
        val macModel = NativeViewerMenuModel(
            strings = ViewerStrings.forLanguage(ViewerLanguage.ENGLISH),
            selectedNodeId = null,
            autoScanEnabled = false,
            panelVisibility = PanelVisibility(),
            viewDisplayOptions = ViewDisplayOptions(),
            archiveOperationInProgress = false,
            canExportArchive = true,
            isMacOs = true,
        )
        val windowsModel = NativeViewerMenuModel(
            strings = ViewerStrings.forLanguage(ViewerLanguage.ENGLISH),
            selectedNodeId = null,
            autoScanEnabled = false,
            panelVisibility = PanelVisibility(),
            viewDisplayOptions = ViewDisplayOptions(),
            archiveOperationInProgress = false,
            canExportArchive = true,
            isMacOs = false,
        )

        assertEquals(NativeMenuShortcut(Key.I, ctrl = false, meta = true), macModel.importShortcut)
        assertEquals(NativeMenuShortcut(Key.E, ctrl = false, meta = true), macModel.exportShortcut)
        assertEquals(NativeMenuShortcut(Key.I, ctrl = true, meta = false), windowsModel.importShortcut)
        assertEquals(NativeMenuShortcut(Key.E, ctrl = true, meta = false), windowsModel.exportShortcut)
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
            strings = ViewerStrings.forLanguage(ViewerLanguage.ENGLISH),
            selectedNodeId = null,
            autoScanEnabled = false,
            panelVisibility = PanelVisibility(),
            viewDisplayOptions = ViewDisplayOptions(),
            archiveOperationInProgress = true,
            canExportArchive = true,
            isMacOs = false,
        )

        assertFalse(model.importEnabled)
        assertFalse(model.exportEnabled)
    }

    @Test
    fun `native export requires a loaded capture while import remains enabled`() {
        val model = NativeViewerMenuModel(
            strings = ViewerStrings.forLanguage(ViewerLanguage.ENGLISH),
            selectedNodeId = null,
            autoScanEnabled = false,
            panelVisibility = PanelVisibility(),
            viewDisplayOptions = ViewDisplayOptions(),
            archiveOperationInProgress = false,
            canExportArchive = false,
            isMacOs = false,
        )

        assertTrue(model.importEnabled)
        assertFalse(model.exportEnabled)
    }
}

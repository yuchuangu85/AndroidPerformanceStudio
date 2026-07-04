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
            exportInProgress = false,
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
        assertFalse(
            model.actions.single { it.action == ViewerAction.TOGGLE_FINDINGS }.checked,
        )
        assertEquals("高级", model.advancedTitle)
        assertEquals("导出 Visible Window Views…", model.exportLabel)
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
    fun `native advanced export is disabled while exporting`() {
        val model = NativeViewerMenuModel(
            strings = ViewerStrings.forLanguage(ViewerLanguage.ENGLISH),
            selectedNodeId = null,
            autoScanEnabled = false,
            panelVisibility = PanelVisibility(),
            exportInProgress = true,
            isMacOs = false,
        )

        assertFalse(model.exportEnabled)
    }
}

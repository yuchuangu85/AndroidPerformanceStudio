package com.androidperformancestudio.desktop

import androidx.compose.ui.input.key.Key
import com.androidperformancestudio.ui.UiLanguage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ViewerActionMenuTest {
    @Test
    fun `menu exposes operations with their shortcuts in stable groups`() {
        val language = UiLanguage.SIMPLIFIED_CHINESE

        assertEquals(
            listOf(
                Triple(ViewerAction.TOGGLE_AUTO_SCAN, "自动扫描", "⌘R / Ctrl+R"),
                Triple(ViewerAction.PREVIOUS_NODE, "上一个节点", "↑"),
                Triple(ViewerAction.NEXT_NODE, "下一个节点", "↓"),
                Triple(ViewerAction.TOGGLE_SELECTED_NODE, "折叠/展开节点", "Enter"),
                Triple(ViewerAction.TOGGLE_HIERARCHY, "隐藏左侧栏", "⌘1 / Ctrl+1"),
                Triple(ViewerAction.TOGGLE_FINDINGS, "隐藏底部栏", "⌘2 / Ctrl+2"),
                Triple(ViewerAction.TOGGLE_DETAILS, "隐藏右侧栏", "⌘3 / Ctrl+3"),
                Triple(ViewerAction.TOGGLE_HIERARCHY_IDS, "显示布局 ID", ""),
                Triple(ViewerAction.OPEN_SETTINGS, "设置", "⌘, / Ctrl+,"),
            ),
            ViewerActionMenu.items(language).map { Triple(it.action, it.label, it.shortcutLabel) },
        )
        assertEquals(
            listOf(0, 1, 1, 1, 2, 2, 2, 3, 4),
            ViewerActionMenu.items(language).map { it.group },
        )
    }

    @Test
    fun `command shortcuts map to the same menu actions`() {
        assertEquals(ViewerAction.TOGGLE_AUTO_SCAN, ViewerActionMenu.commandAction(Key.R, commandPressed = true))
        assertEquals(ViewerAction.TOGGLE_HIERARCHY, ViewerActionMenu.commandAction(Key.One, commandPressed = true))
        assertEquals(ViewerAction.TOGGLE_FINDINGS, ViewerActionMenu.commandAction(Key.Two, commandPressed = true))
        assertEquals(ViewerAction.TOGGLE_DETAILS, ViewerActionMenu.commandAction(Key.Three, commandPressed = true))
        assertEquals(ViewerAction.OPEN_SETTINGS, ViewerActionMenu.commandAction(Key.Comma, commandPressed = true))
        assertNull(ViewerActionMenu.commandAction(Key.R, commandPressed = false))
    }
}

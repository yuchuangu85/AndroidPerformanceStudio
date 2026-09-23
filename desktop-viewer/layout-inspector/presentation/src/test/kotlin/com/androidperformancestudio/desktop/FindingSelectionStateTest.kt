package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class FindingSelectionStateTest {
    @Test
    fun `starts without a selected finding`() {
        val state = FindingSelectionState()

        assertFalse(state.isSelected("finding-a"))
    }

    @Test
    fun `selecting a finding replaces the previous selection`() {
        val state = FindingSelectionState()
            .select("finding-a")
            .select("finding-b")

        assertFalse(state.isSelected("finding-a"))
        assertTrue(state.isSelected("finding-b"))
    }

    @Test
    fun `single click selects the finding node while double click retains source navigation`() {
        val source = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"))
        val rowBinding = source.substringAfter("FindingRow(").substringBefore("onDoubleClick =")
        val pointerBinding = source.substringAfter("private fun FindingRow(").substringBefore("SelectionContainer")

        assertTrue(rowBinding.contains("onSelectNode(finding.nodeId)"))
        assertTrue(pointerBinding.contains("1 -> onClick()"))
        assertTrue(pointerBinding.contains("2 -> onDoubleClick()"))
        assertTrue(pointerBinding.contains("onClick { onClick(); true }"))
        assertTrue(pointerBinding.contains("event.key == Key.Enter"))
    }
}

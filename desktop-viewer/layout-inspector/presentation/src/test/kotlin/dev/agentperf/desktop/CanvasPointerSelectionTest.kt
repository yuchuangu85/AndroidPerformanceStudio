package dev.agentperf.desktop

import androidx.compose.ui.geometry.Offset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CanvasPointerSelectionTest {
    @Test
    fun `repeated clicks walk from child to ancestors`() {
        val selection = CanvasPointerSelection()
        val path = listOf("leaf", "parent", "root")
        val point = Offset(10f, 10f)

        assertEquals("leaf", selection.click(point, path))
        assertEquals("parent", selection.click(point, path))
        assertEquals("root", selection.click(point, path))
        assertEquals("leaf", selection.click(point, path))
    }
}

package dev.agentperf.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PaneLayoutTest {
    @Test
    fun `dragging separators changes only the adjacent side pane`() {
        val initial = PaneWidths()
        assertEquals(
            PaneWidths(hierarchy = 380f, properties = 300f),
            PaneLayout.dragHierarchy(initial, deltaDp = 80f, availableWidthDp = 1200f),
        )
        assertEquals(
            PaneWidths(hierarchy = 300f, properties = 260f),
            PaneLayout.dragProperties(initial, deltaDp = 40f, availableWidthDp = 1200f),
        )
    }

    @Test
    fun `dragging clamps side panes and preserves canvas minimum width`() {
        val initial = PaneWidths()
        assertEquals(PaneWidths(180f, 300f), PaneLayout.dragHierarchy(initial, -1000f, 1100f))
        assertEquals(PaneWidths(466f, 300f), PaneLayout.dragHierarchy(initial, 1000f, 1100f))
        assertEquals(PaneWidths(300f, 240f), PaneLayout.dragProperties(initial, 1000f, 1100f))
        assertEquals(PaneWidths(300f, 466f), PaneLayout.dragProperties(initial, -1000f, 1100f))
        val hierarchyMaximum = PaneLayout.dragHierarchy(initial, 1000f, 1100f)
        assertEquals(hierarchyMaximum, PaneLayout.dragHierarchy(hierarchyMaximum, 1000f, 1100f))
    }
}

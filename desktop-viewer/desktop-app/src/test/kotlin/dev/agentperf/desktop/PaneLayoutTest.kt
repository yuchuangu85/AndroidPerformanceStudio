package dev.agentperf.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PaneLayoutTest {
    @Test
    fun `default hierarchy pane width is twenty five percent wider`() {
        assertEquals(375f, PaneWidths().hierarchy)
    }

    @Test
    fun `dragging separators changes only the adjacent side pane`() {
        val initial = PaneWidths()
        assertEquals(
            PaneWidths(hierarchy = 455f, properties = 300f),
            PaneLayout.dragHierarchy(initial, deltaDp = 80f, availableWidthDp = 1200f),
        )
        assertEquals(
            PaneWidths(hierarchy = 375f, properties = 260f),
            PaneLayout.dragProperties(initial, deltaDp = 40f, availableWidthDp = 1200f),
        )
    }

    @Test
    fun `dragging clamps side panes and preserves canvas minimum width`() {
        val initial = PaneWidths()
        assertEquals(PaneWidths(180f, 300f), PaneLayout.dragHierarchy(initial, -1000f, 1100f))
        assertEquals(PaneWidths(466f, 300f), PaneLayout.dragHierarchy(initial, 1000f, 1100f))
        assertEquals(PaneWidths(375f, 240f), PaneLayout.dragProperties(initial, 1000f, 1100f))
        assertEquals(PaneWidths(375f, 391f), PaneLayout.dragProperties(initial, -1000f, 1100f))
        val hierarchyMaximum = PaneLayout.dragHierarchy(initial, 1000f, 1100f)
        assertEquals(hierarchyMaximum, PaneLayout.dragHierarchy(hierarchyMaximum, 1000f, 1100f))
    }

    @Test
    fun `fitting remembered widths preserves canvas minimum after window shrinks`() {
        val fitted = PaneLayout.fit(
            widths = PaneWidths(hierarchy = 500f, properties = 500f),
            availableWidthDp = 1100f,
        )

        assertEquals(PaneWidths(hierarchy = 500f, properties = 266f), fitted)
        assertEquals(
            PaneLayout.CANVAS_MIN_WIDTH_DP,
            1100f -
                fitted.hierarchy -
                fitted.properties -
                PaneLayout.SPLITTER_WIDTH_DP * 2,
        )
    }
}

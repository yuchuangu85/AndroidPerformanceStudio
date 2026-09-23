package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class PaneLayoutTest {
    @Test
    fun `default hierarchy pane fits the commercial workspace range`() {
        assertEquals(320f, PaneWidths().hierarchy)
    }

    @Test
    fun `dragging separators changes only the adjacent side pane`() {
        val initial = PaneWidths()
        assertEquals(
            PaneWidths(hierarchy = 360f, properties = 300f),
            PaneLayout.dragHierarchy(initial, deltaDp = 80f, availableWidthDp = 1200f),
        )
        assertEquals(
            PaneWidths(hierarchy = 320f, properties = 260f),
            PaneLayout.dragProperties(initial, deltaDp = 40f, availableWidthDp = 1200f),
        )
    }

    @Test
    fun `dragging clamps side panes and preserves canvas minimum width`() {
        val initial = PaneWidths()
        assertEquals(PaneWidths(280f, 300f), PaneLayout.dragHierarchy(initial, -1000f, 1100f))
        assertEquals(PaneWidths(360f, 300f), PaneLayout.dragHierarchy(initial, 1000f, 1100f))
        assertEquals(PaneWidths(320f, 240f), PaneLayout.dragProperties(initial, 1000f, 1100f))
        assertEquals(PaneWidths(320f, 446f), PaneLayout.dragProperties(initial, -1000f, 1100f))
        val hierarchyMaximum = PaneLayout.dragHierarchy(initial, 1000f, 1100f)
        assertEquals(hierarchyMaximum, PaneLayout.dragHierarchy(hierarchyMaximum, 1000f, 1100f))
    }

    @Test
    fun `fitting remembered widths preserves canvas minimum after window shrinks`() {
        val fitted = PaneLayout.fit(
            widths = PaneWidths(hierarchy = 500f, properties = 500f),
            availableWidthDp = 1100f,
        )

        assertEquals(PaneWidths(hierarchy = 360f, properties = 406f), fitted)
        assertEquals(
            PaneLayout.CANVAS_MIN_WIDTH_DP,
            1100f -
                fitted.hierarchy -
                fitted.properties -
                PaneLayout.SPLITTER_WIDTH_DP * 2,
        )
    }

    @Test
    fun `compact windows keep canvas usable without changing normal pane defaults`() {
        val fitted = PaneLayout.fit(PaneWidths(), 800f)

        assertEquals(PaneWidths(hierarchy = 306f, properties = 200f), fitted)
        assertEquals(280f, 800f - fitted.hierarchy - fitted.properties - PaneLayout.SPLITTER_WIDTH_DP * 2)
        assertEquals(PaneWidths(hierarchy = 180f, properties = 200f), PaneLayout.dragHierarchy(fitted, -1000f, 800f))
    }

    @Test
    fun `temporary window shrink does not overwrite the user's preferred widths`() {
        val preferred = PaneWidths(hierarchy = 350f, properties = 380f)
        assertEquals(PaneWidths(306f, 200f), PaneLayout.fit(preferred, 800f))
        assertEquals(preferred, PaneLayout.fit(preferred, 1200f))

        val workspace = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"))
        assertFalse(workspace.contains("paneWidths = normalizedPaneWidths"))
    }
}

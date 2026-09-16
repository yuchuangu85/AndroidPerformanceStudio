package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SourceWorkspacePaneLayoutTest {
    @Test
    fun `default widths preserve the established source workspace proportions`() {
        assertEquals(360f, SourceWorkspacePaneWidths().workspaces)
        assertEquals(320f, SourceWorkspacePaneWidths().files)
    }

    @Test
    fun `dragging each splitter changes its adjacent pane only`() {
        val initial = SourceWorkspacePaneWidths()

        assertEquals(
            SourceWorkspacePaneWidths(workspaces = 440f, files = 320f),
            SourceWorkspacePaneLayout.dragWorkspaces(initial, deltaDp = 80f, availableWidthDp = 1400f),
        )
        assertEquals(
            SourceWorkspacePaneWidths(workspaces = 360f, files = 260f),
            SourceWorkspacePaneLayout.dragFiles(initial, deltaDp = -60f, availableWidthDp = 1400f),
        )
    }

    @Test
    fun `pane resizing clamps both lists while preserving the source pane minimum`() {
        val initial = SourceWorkspacePaneWidths()

        assertEquals(
            SourceWorkspacePaneWidths(workspaces = 220f, files = 320f),
            SourceWorkspacePaneLayout.dragWorkspaces(initial, deltaDp = -1000f, availableWidthDp = 1100f),
        )
        assertEquals(
            SourceWorkspacePaneWidths(workspaces = 406f, files = 320f),
            SourceWorkspacePaneLayout.dragWorkspaces(initial, deltaDp = 1000f, availableWidthDp = 1100f),
        )
        assertEquals(
            SourceWorkspacePaneWidths(workspaces = 360f, files = 220f),
            SourceWorkspacePaneLayout.dragFiles(initial, deltaDp = -1000f, availableWidthDp = 1100f),
        )
        assertEquals(
            SourceWorkspacePaneWidths(workspaces = 360f, files = 366f),
            SourceWorkspacePaneLayout.dragFiles(initial, deltaDp = 1000f, availableWidthDp = 1100f),
        )
    }

    @Test
    fun `fitting remembered widths preserves a usable source pane after a window shrink`() {
        val fitted = SourceWorkspacePaneLayout.fit(
            widths = SourceWorkspacePaneWidths(workspaces = 600f, files = 500f),
            availableWidthDp = 1100f,
        )

        assertEquals(SourceWorkspacePaneWidths(workspaces = 506f, files = 220f), fitted)
        assertEquals(
            SourceWorkspacePaneLayout.SOURCE_MIN_WIDTH_DP,
            1100f - fitted.workspaces - fitted.files - SourceWorkspacePaneLayout.SPLITTER_WIDTH_DP * 2,
        )
    }
}

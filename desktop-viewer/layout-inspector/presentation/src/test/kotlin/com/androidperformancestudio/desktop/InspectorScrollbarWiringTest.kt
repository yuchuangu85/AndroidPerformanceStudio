package com.androidperformancestudio.desktop

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class InspectorScrollbarWiringTest {
    private val source = Files.readString(
        Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"),
    )

    @Test
    fun `hierarchy content exposes its horizontal scroll position`() {
        val hierarchy = source
            .substringAfter("private fun HierarchyPane(")
            .substringBefore("@Composable\nprivate fun HierarchyDisclosure(")

        assertTrue(hierarchy.contains("HorizontalScrollbar("))
        assertTrue(hierarchy.contains("rememberScrollbarAdapter(horizontalScrollState)"))
    }

    @Test
    fun `timeline exposes its horizontal scroll position`() {
        val timeline = source
            .substringAfter("private fun TimelineStrip(")
            .substringBefore("@Composable\nprivate fun TimelineScrollButton(")

        assertTrue(timeline.contains("HorizontalScrollbar("))
        assertTrue(timeline.contains("rememberScrollbarAdapter(listState)"))
        assertTrue(timeline.contains("onCloseTimelineFrame"))
        assertTrue(timeline.contains("Res.string.remove_timeline_frame"))
        assertTrue(timeline.contains("colors.detailRowDeep"))
    }

    @Test
    fun `preview exposes horizontal and vertical pan positions`() {
        val preview = source
            .substringAfter("private fun PreviewPane(")
            .substringBefore("@Composable\nprivate fun PreviewZoomControls(")

        assertTrue(preview.contains("PreviewScrollbarAdapter("))
        assertTrue(preview.contains("HorizontalScrollbar("))
        assertTrue(preview.contains("VerticalScrollbar("))
    }
}

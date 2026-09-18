package com.androidperformancestudio.memory.presentation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryProfilerClassListScrollbarWiringTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/memory/presentation/MemoryProfilerClassListView.kt"),
        )

    @Test
    fun `vertical scrollbar stays in classifier viewport instead of horizontal content`() {
        val pane =
            source
                .substringAfter("public fun MemoryProfilerClassListPane(")
                .substringBefore("@Composable\nprivate fun FilterBar(")
        val horizontalScrollContent = pane.substringAfter(".horizontalScroll(classifierHorizontalScrollState)")
        val horizontalScrollbarIndex = horizontalScrollContent.indexOf("HorizontalScrollbar(")
        val verticalScrollbarIndex = horizontalScrollContent.indexOf("VerticalScrollbar(")

        assertTrue(horizontalScrollbarIndex >= 0)
        assertTrue(verticalScrollbarIndex > horizontalScrollbarIndex)
        assertFalse(
            horizontalScrollContent
                .substringBefore("HorizontalScrollbar(")
                .contains("rememberScrollbarAdapter(classifierListState)"),
        )
        assertTrue(horizontalScrollContent.contains("rememberScrollbarAdapter(classifierListState)"))
        assertTrue(pane.contains("padding(end = scrollbarThickness, bottom = scrollbarThickness)"))
        assertTrue(horizontalScrollContent.contains("padding(end = scrollbarThickness)"))
        assertTrue(
            horizontalScrollContent.contains(
                "padding(top = CLASSIFIER_HEADER_HEIGHT, bottom = scrollbarThickness)",
            ),
        )
        assertTrue(pane.contains("modifier = Modifier.height(CLASSIFIER_HEADER_HEIGHT)"))
    }
}

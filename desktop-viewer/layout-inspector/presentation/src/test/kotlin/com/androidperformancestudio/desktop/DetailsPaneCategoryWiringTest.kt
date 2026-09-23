package com.androidperformancestudio.desktop

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DetailsPaneCategoryWiringTest {
    @Test
    fun `properties tabs show only categories backed by sections and retain lazy rows`() {
        val source = Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/desktop/LayoutInspectorMainPage.kt"))
        val pane = source.substringAfter("private fun DetailsPane(").substringBefore("private fun DetailSection(")

        assertTrue(pane.contains("DetailCategory.entries.filter { category -> details.sections.any { it.category == category } }"))
        assertTrue(pane.contains("details.sections.filter { it.category == activeCategory }"))
        assertTrue(pane.contains("LazyColumn(state = detailsListState"))
        assertTrue(pane.contains("onOpenMemoryProfiler(className)"))
        assertTrue(pane.contains("onOpenComposeSource("))
    }
}

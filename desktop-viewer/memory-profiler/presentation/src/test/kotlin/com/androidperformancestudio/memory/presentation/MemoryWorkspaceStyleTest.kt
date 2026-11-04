package com.androidperformancestudio.memory.presentation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryWorkspaceStyleTest {
    @Test
    fun `all memory modes omit the decorative banner before snapshot evidence`() {
        val source = source("MemoryProfilerScreen.kt")
        assertFalse(source.contains("StudioWorkspaceHeader("))
        assertTrue(source.indexOf("if (presentedState.snapshotSummary") >= 0)
        assertTrue(source.indexOf("when (presentedState.viewMode)") >= 0)
        assertTrue(source.indexOf("when (presentedState.viewMode)") < source.indexOf("MemoryProfilerStatusBar("))
        assertTrue(source.contains("StudioTokens.workspacePadding"))
    }

    @Test
    fun `nested views retain evidence without duplicating workspace headers`() {
        listOf(
            "MemoryProfilerBitmapDumpPage.kt",
            "MemoryProfilerNativeHeapPage.kt",
            "MemoryProfilerLeakCanaryPage.kt",
        ).forEach { file ->
            assertFalse(source(file).contains("StudioWorkspaceHeader("), file)
        }
        val source = source("MemoryProfilerScreen.kt")
        assertTrue(source.contains("MemoryInvestigationWorkspace("))
        assertTrue(source.contains("MemoryClassesPane("))
        assertTrue(source.contains("MemoryProfilerDominatorPane("))
        assertTrue(source.contains("MemorySnapshotComparison("))
        assertTrue(source.contains("MemoryProfilerBitmapDumpPage("))
        assertTrue(source.contains("MemoryProfilerNativeHeapPage("))
        assertTrue(source.contains("MemoryProfilerLeakCanaryPage("))
    }

    @Test
    fun `card workspaces use uniform four dp exterior spacing without shrinking card content`() {
        val workspace = source("MemoryInvestigationWorkspace.kt")
        assertTrue(workspace.contains("modifier.padding(StudioTokens.workspacePadding)"))
        assertTrue(workspace.contains("Arrangement.spacedBy(StudioTokens.cardGap)"))
        assertTrue(workspace.contains("contentPadding = 4.dp"))
        assertTrue(
            workspace.contains("padding(StudioTokens.compactContentPadding).testTag(\"memory-heap-distribution\")"),
        )
        val bitmap = source("MemoryProfilerBitmapDumpPage.kt")
        assertTrue(bitmap.contains("padding(StudioTokens.workspacePadding)"))
        assertTrue(bitmap.contains("horizontalArrangement = Arrangement.spacedBy(StudioTokens.cardGap)"))
        val gallery =
            bitmap
                .substringAfter("private fun BitmapDumpGalleryContent(")
                .substringBefore("private fun BitmapDumpSummaryMetrics(")
        assertTrue(gallery.contains(".padding(StudioTokens.workspacePadding)"))
        assertTrue(bitmap.contains("contentSpacing = 4.dp"))
        val overview =
            source("MemoryProfilerScreen.kt")
                .substringAfter("private fun Overview(")
                .substringBefore("private fun MetricCard(")
        assertFalse(overview.contains(".padding("), "Overview metrics must align with the surrounding histogram")
        assertTrue(overview.contains("Arrangement.spacedBy(StudioTokens.cardGap)"))
        assertTrue(overview.contains("Modifier.fillMaxWidth()"))
    }

    @Test
    fun `leak analysis keeps compact chrome and horizontally reachable independent panes`() {
        val page = source("MemoryProfilerLeakCanaryPage.kt")
        assertTrue(page.contains(".padding(StudioTokens.workspacePadding)"))
        assertTrue(page.contains("verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap)"))
        assertTrue(page.contains("maxOf(maxWidth, 1120.dp)"))
        assertTrue(page.contains(".horizontalScroll(scroll)"))
        assertTrue(page.contains(".requiredWidth(contentWidth)"))
        assertTrue(page.contains("Modifier.width(280.dp).fillMaxHeight()"))
        assertTrue(page.contains("Modifier.width(330.dp).fillMaxHeight()"))
        val panes = source("MemoryLeakInvestigationPanes.kt")
        assertTrue(panes.contains("LazyColumn("))
        assertTrue(panes.contains("VerticalScrollbar("))
        assertFalse(page.contains("verticalScroll("))
        assertFalse(page.contains("tonalElevation"))
    }

    private fun source(file: String): String =
        Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/memory/presentation", file))
}

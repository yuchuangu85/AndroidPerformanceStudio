package com.androidperformancestudio.startup.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupProfilerWorkspaceSourceTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/startup/app/StartupProfilerMainPage.kt"),
        )

    @Test
    fun `workspace separates actions from experiment configuration without changing startup screen`() {
        assertTrue(source.contains("ProfilerMacOsToolbar"))
        assertTrue(source.contains("ProfilerMacOsSecondaryToolbar"))
        assertTrue(source.contains("ProfilerCompactSelector"))
        assertTrue(source.contains("ProfilerToolbarStatus"))
        assertFalse(source.contains("private fun Selector("))
        assertFalse(source.contains("import androidx.compose.material3.OutlinedButton"))
        assertFalse(source.contains("import androidx.compose.material3.Button"))
        assertTrue(source.contains("StartupProfilerScreen("))
        assertTrue(source.contains("controller.runExperiment()"))
    }

    @Test
    fun `stacked toolbar rows have ordered one dp outline separators before progress and content`() {
        val primaryToolbar = source.indexOf("ProfilerMacOsToolbar {")
        val secondaryToolbar = source.indexOf("ProfilerMacOsSecondaryToolbar {")
        val progressCondition = source.indexOf("if (state.isRunning && state.totalRuns > 0)")
        val progressBlockRange = source.blockRangeStartingAt(progressCondition)
        val progressBlock = source.substring(progressBlockRange)
        val progress = progressBlock.indexOf("LinearProgressIndicator(")
        val screen = source.indexOf("StartupProfilerScreen(")
        val dividers = outlineDivider.findAll(source).map { it.range.first }.toList()
        val conditionalDividers = outlineDivider.findAll(progressBlock).map { it.range.first }.toList()
        val contentBoundary = source.substring(progressBlockRange.last + 1, screen)

        assertEquals(3, dividers.size)
        assertTrue(primaryToolbar < dividers[0])
        assertTrue(dividers[0] < secondaryToolbar)
        assertTrue(secondaryToolbar < progressCondition)
        assertEquals(1, conditionalDividers.size)
        assertTrue(conditionalDividers.single() < progress)
        assertEquals(1, outlineDivider.findAll(contentBoundary).count())
        assertTrue(dividers[2] < screen)
    }

    private fun String.blockRangeStartingAt(startIndex: Int): IntRange {
        val openingBrace = indexOf('{', startIndex)
        var depth = 0
        for (index in openingBrace..lastIndex) {
            when (this[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return startIndex..index
                }
            }
        }
        error("Unclosed block at $startIndex")
    }

    private companion object {
        val outlineDivider =
            Regex(
                """HorizontalDivider\(\s*thickness = 1\.dp,\s*color = MaterialTheme\.colorScheme\.outline,\s*\)""",
            )
    }
}

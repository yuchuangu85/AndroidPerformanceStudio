package com.androidperformancestudio.battery.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BatteryProfilerWorkspaceSourceTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/battery/app/BatteryProfilerMainPage.kt"),
        )

    @Test
    fun `workspace uses layered shared compact chrome without changing battery flows`() {
        assertTrue(source.contains("HeaderToolbar("))
        assertTrue(source.contains("BatteryProfilerMenuBar("))
        assertTrue(source.contains("DropdownSelector"))
        assertFalse(source.contains("ProfilerCompactSelector"))
        assertTrue(source.contains("ProfilerCompactButton"))
        assertTrue(source.contains("ProfilerToolbarStatus"))
        assertFalse(source.contains("private fun Selector("))
        val workspaceChrome = source.substringBefore("if (confirmReset)")
        assertFalse(workspaceChrome.contains("\n                OutlinedButton("))
        assertFalse(workspaceChrome.contains("\n                Button("))
        assertTrue(source.contains("BatteryProfilerScreen("))
        assertTrue(source.contains("confirmBugreport = true"))
    }

    @Test
    fun `file and advanced actions are removed from the page toolbar`() {
        val primaryToolbar = source.headerToolbar()

        assertFalse(primaryToolbar.contains("Res.string.export_json"))
        assertFalse(primaryToolbar.contains("Res.string.export_csv"))
        assertFalse(primaryToolbar.contains("Res.string.export_raw_bundle"))
        assertFalse(primaryToolbar.contains("Res.string.advanced_reset_stats"))
    }

    @Test
    fun `battery historian is aligned to the far right of the primary toolbar`() {
        val primaryToolbar = source.headerToolbar()
        val runExperiment = primaryToolbar.indexOf("Res.string.run_experiment")
        val spacer = primaryToolbar.indexOf("Spacer(Modifier.weight(1f))")
        val historian = primaryToolbar.indexOf("Res.string.battery_historian")

        assertTrue(runExperiment in 0..<spacer)
        assertTrue(spacer < historian)
    }

    @Test
    fun `duration polling and runs selectors show descriptions with selected values`() {
        assertTrue(source.contains("Res.string.duration_value"))
        assertTrue(source.contains("Res.string.polling_value"))
        assertTrue(source.contains("Res.string.runs_value"))
    }

    @Test
    fun `single header toolbar has ordered one dp outline separators before progress and content`() {
        val primaryToolbar = source.indexOf("HeaderToolbar(")
        val progressCondition = source.indexOf("if (state.isRunning &&")
        val progressBlockRange = source.blockRangeStartingAt(progressCondition)
        val progressBlock = source.substring(progressBlockRange)
        val progress = progressBlock.indexOf("LinearProgressIndicator(")
        val screen = source.indexOf("BatteryProfilerScreen(")
        val dividers = outlineDivider.findAll(source).map { it.range.first }.toList()
        val conditionalDividers = outlineDivider.findAll(progressBlock).map { it.range.first }.toList()
        val contentBoundary = source.substring(progressBlockRange.last + 1, screen)

        assertEquals(3, dividers.size)
        assertTrue(primaryToolbar < dividers[0])
        assertTrue(dividers[0] < progressCondition)
        assertEquals(1, conditionalDividers.size)
        assertTrue(conditionalDividers.single() < progress)
        assertEquals(1, outlineDivider.findAll(contentBoundary).count())
        assertTrue(dividers[2] < screen)
    }

    private fun String.blockStartingAt(startIndex: Int): String = substring(blockRangeStartingAt(startIndex))

    private fun String.headerToolbar(): String {
        val start = indexOf("HeaderToolbar(")
        return substring(start, indexOf("HorizontalDivider(", start))
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

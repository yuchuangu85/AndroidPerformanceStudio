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
            Path.of("src/main/kotlin/com/androidperformancestudio/battery/app/BatteryProfilerWorkspace.kt"),
        )

    @Test
    fun `workspace uses layered shared compact chrome without changing battery flows`() {
        assertTrue(source.contains("ProfilerMacOsToolbar"))
        assertTrue(source.contains("ProfilerMacOsSecondaryToolbar"))
        assertTrue(source.contains("ProfilerCompactSelector"))
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
    fun `stacked toolbar rows have ordered one dp outline separators before progress and content`() {
        val primaryToolbar = source.indexOf("ProfilerMacOsToolbar {")
        val secondaryToolbars =
            Regex("""ProfilerMacOsSecondaryToolbar \{""")
                .findAll(source)
                .map { it.range.first }
                .toList()
        val progress = source.indexOf("if (state.isRunning &&")
        val screen = source.indexOf("BatteryProfilerScreen(")
        val dividers = outlineDivider.findAll(source).map { it.range.first }.toList()

        assertEquals(2, secondaryToolbars.size)
        assertEquals(4, dividers.size)
        assertTrue(primaryToolbar < dividers[0])
        assertTrue(dividers[0] < secondaryToolbars[0])
        assertTrue(secondaryToolbars[0] < dividers[1])
        assertTrue(dividers[1] < secondaryToolbars[1])
        assertTrue(secondaryToolbars[1] < dividers[2])
        assertTrue(dividers[2] < progress)
        assertTrue(progress < dividers[3])
        assertTrue(dividers[3] < screen)
    }

    private companion object {
        val outlineDivider =
            Regex(
                """HorizontalDivider\(\s*thickness = 1\.dp,\s*color = MaterialTheme\.colorScheme\.outline,\s*\)""",
            )
    }
}

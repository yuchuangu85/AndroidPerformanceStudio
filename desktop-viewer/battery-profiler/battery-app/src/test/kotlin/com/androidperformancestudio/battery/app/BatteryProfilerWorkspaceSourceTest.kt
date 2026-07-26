package com.androidperformancestudio.battery.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
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
}

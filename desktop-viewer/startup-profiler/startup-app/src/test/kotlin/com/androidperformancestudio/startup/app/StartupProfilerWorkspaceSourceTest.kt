package com.androidperformancestudio.startup.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupProfilerWorkspaceSourceTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/startup/app/StartupProfilerWorkspace.kt"),
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
}

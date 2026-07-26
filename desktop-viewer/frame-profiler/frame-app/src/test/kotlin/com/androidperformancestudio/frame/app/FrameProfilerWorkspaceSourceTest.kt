package com.androidperformancestudio.frame.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrameProfilerWorkspaceSourceTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/frame/app/FrameProfilerWorkspace.kt"),
        )

    @Test
    fun `workspace uses shared compact chrome without changing frame screen`() {
        assertTrue(source.contains("ProfilerMacOsToolbar"))
        assertTrue(source.contains("ProfilerCompactSelector"))
        assertTrue(source.contains("ProfilerToolbarStatus"))
        assertFalse(source.contains("private fun TargetSelector("))
        assertFalse(source.contains("import androidx.compose.material3.OutlinedButton"))
        assertFalse(source.contains("import androidx.compose.material3.Button"))
        assertTrue(source.contains("FrameProfilerScreen("))
    }
}

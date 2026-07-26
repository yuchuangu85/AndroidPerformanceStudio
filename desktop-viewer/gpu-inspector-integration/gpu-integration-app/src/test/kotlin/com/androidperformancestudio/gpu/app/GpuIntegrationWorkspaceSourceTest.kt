package com.androidperformancestudio.gpu.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GpuIntegrationWorkspaceSourceTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/gpu/app/GpuIntegrationWorkspace.kt"),
        )

    @Test
    fun `workspace uses shared compact chrome without changing artifact screen`() {
        assertTrue(source.contains("ProfilerMacOsToolbar"))
        assertTrue(source.contains("ProfilerCompactButton"))
        assertTrue(source.contains("GpuIntegrationScreen("))
        assertFalse(source.contains("import androidx.compose.material3.OutlinedButton"))
        assertFalse(source.contains("import androidx.compose.material3.Button"))
    }
}

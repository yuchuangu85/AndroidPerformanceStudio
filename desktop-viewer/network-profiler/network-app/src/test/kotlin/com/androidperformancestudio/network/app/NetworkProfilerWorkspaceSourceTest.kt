package com.androidperformancestudio.network.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NetworkProfilerWorkspaceSourceTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/network/app/NetworkProfilerWorkspace.kt"),
        )

    @Test
    fun `workspace uses shared compact chrome without changing network screen`() {
        assertTrue(source.contains("ProfilerMacOsToolbar"))
        assertTrue(source.contains("ProfilerCompactTextField"))
        assertTrue(source.contains("ProfilerToolbarStatus"))
        assertFalse(source.contains("import androidx.compose.material3.OutlinedTextField"))
        assertFalse(source.contains("import androidx.compose.material3.OutlinedButton"))
        assertFalse(source.contains("import androidx.compose.material3.Button"))
        assertTrue(source.contains("NetworkProfilerScreen("))
    }
}

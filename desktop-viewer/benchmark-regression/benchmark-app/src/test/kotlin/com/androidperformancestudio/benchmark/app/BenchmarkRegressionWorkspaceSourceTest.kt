package com.androidperformancestudio.benchmark.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BenchmarkRegressionWorkspaceSourceTest {
    private val source =
        Files.readString(
            Path.of(
                "src/main/kotlin/com/androidperformancestudio/benchmark/app/BenchmarkRegressionWorkspace.kt",
            ),
        )

    @Test
    fun `workspace uses shared compact chrome without changing comparison screen`() {
        assertTrue(source.contains("ProfilerMacOsToolbar"))
        assertTrue(source.contains("ProfilerCompactButton"))
        assertTrue(source.contains("BenchmarkRegressionScreen("))
        assertFalse(source.contains("import androidx.compose.material3.OutlinedButton"))
        assertFalse(source.contains("import androidx.compose.material3.Button"))
    }
}

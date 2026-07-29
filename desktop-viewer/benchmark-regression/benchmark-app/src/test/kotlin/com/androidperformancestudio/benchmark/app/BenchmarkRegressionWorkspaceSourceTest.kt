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
                "src/main/kotlin/com/androidperformancestudio/benchmark/app/BenchmarkRegressionMainPage.kt",
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

    @Test
    fun `toolbar preserves imports exporter and trace navigation with enabled predicates`() {
        assertButtonContains(
            "text = if (chinese) \"导入当前结果\" else \"Import Current\"",
            "chooseJson(window)",
            "import(it, false)",
        )
        assertButtonContains(
            "text = if (chinese) \"导入基线\" else \"Import Baseline\"",
            "chooseJson(window)",
            "import(it, true)",
        )
        assertButtonContains(
            "text = if (chinese) \"导出报告\" else \"Export Report\"",
            "enabled = state.report != null",
            "exporter.writeJson(requireNotNull(state.report), it.toPath())",
        )
        assertButtonContains(
            "text = if (chinese) \"在 Perfetto 打开 Trace\" else \"Open Trace in Perfetto\"",
            "enabled = state.current?.cases?.any { it.traceArtifacts.isNotEmpty() } == true",
            "?.flatMap { it.traceArtifacts }",
            "?.firstOrNull()",
            "?.let(onOpenTrace)",
        )
    }

    @Test
    fun `home action preserves direct workspace navigation`() {
        val homeBlock =
            source.substring(
                source.indexOf("ProfilerHomeButton("),
                source.indexOf("ProfilerCompactButton("),
            )
        assertTrue(homeBlock.contains("onClick = onBack"))
    }

    private fun assertButtonContains(
        anchor: String,
        vararg invariants: String,
    ) {
        val anchorIndex = source.indexOf(anchor)
        assertTrue(anchorIndex >= 0, "Missing anchor: $anchor")
        val blockStart = source.lastIndexOf("ProfilerCompactButton(", anchorIndex)
        val blockEnd = source.indexOf("ProfilerCompactButton(", anchorIndex + anchor.length).let {
            if (it >= 0) it else source.indexOf("Spacer(", anchorIndex)
        }
        val block = source.substring(blockStart, blockEnd)

        invariants.forEach { invariant ->
            assertTrue(block.contains(invariant), "Missing `$invariant` near `$anchor`")
        }
    }
}

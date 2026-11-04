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
    fun `workspace hosts the commercial reports screen behind the shared toolbar`() {
        assertTrue(source.contains("HeaderToolbar("))
        assertFalse(source.contains("ProfilerMacOsToolbar"))
        assertTrue(source.contains("BenchmarkReportsScreen("))
        assertTrue(source.contains("BenchmarkReportsActions("))
        assertTrue(source.contains("onNavigateHome = onBack"))
        val header = source.substringAfter("HeaderToolbar(").substringBefore("HorizontalDivider(")
        assertTrue(header.contains("BenchmarkHeaderActions("))
        assertTrue(header.contains("Res.string.open_trace_in_perfetto"))
        assertFalse(source.contains("import androidx.compose.material3.OutlinedButton"))
        assertFalse(source.contains("import androidx.compose.material3.Button"))
    }

    @Test
    fun `workspace wires real evidence sources for comparison trend gate and exports`() {
        assertTrue(source.contains("SqliteBenchmarkStore.open(storePath)"))
        assertTrue(source.contains("store.metricHistory("))
        assertTrue(source.contains("RegressionAnalyzer()"))
        assertTrue(source.contains("ReleaseGateEvaluator()"))
        assertTrue(source.contains("pdfExporter.write(requireNotNull(state.report), requireNotNull(state.gate), target)"))
        assertTrue(source.contains("exporter.writeCsv(requireNotNull(state.report), requireNotNull(state.gate), target)"))
        assertTrue(source.contains("BenchmarkReportIndexStore("))
        assertTrue(source.contains("BenchmarkCompareSettingsDialog("))
    }

    @Test
    fun `import keeps using the AndroidX benchmark JSON parser and the history store`() {
        assertTrue(source.contains("chooseBenchmarkJson(window, language)"))
        assertTrue(source.contains("parser.parse(file.toPath())"))
        assertTrue(source.contains("it.save(run)"))
    }
}

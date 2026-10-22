package com.androidperformancestudio.benchmark.presentation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BenchmarkWorkspaceStyleTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/benchmark/presentation/BenchmarkReportsScreen.kt"),
        )

    @Test
    fun `baseline header actions are hosted above a workspace that starts with metrics`() {
        assertFalse(source.contains("StudioWorkspaceHeader("))
        assertTrue(source.contains("public fun BenchmarkHeaderActions("))
        val workspace = source.substringAfter("public fun BenchmarkReportsScreen(")
        assertFalse(workspace.contains("BenchmarkToolbar("))
        assertTrue(workspace.indexOf("BenchmarkSummaryRow(state, language)") >= 0)
    }

    @Test
    fun `benchmark uses compact shared spacing without replacing comparison trend or report layouts`() {
        val workspace = source.substringAfter("public fun BenchmarkReportsScreen(")
        assertTrue(workspace.contains(".padding(StudioTokens.compactContentPadding)"))
        assertTrue(workspace.contains(".background(colors.workspace)"))
        assertTrue(workspace.contains("BenchmarkComparisonTable("))
        assertTrue(workspace.contains("BenchmarkTrendPanel("))
        assertTrue(workspace.contains("modifier = Modifier.height(210.dp)"))
        assertTrue(workspace.contains("modifier = Modifier.width(320.dp)"))
        assertEquals(4, Regex("StudioMetricCard\\(").findAll(source).count())
        assertFalse(source.contains("CompositionLocalProvider("))
        assertFalse(source.contains("ViewerTheme("))
    }

    @Test
    fun `benchmark preserves filters evidence actions and unavailable gate state`() {
        listOf(
            "onSelectBaseline", "onSelectCurrent", "onCompare", "onImportRun", "onOrchestrateRun", "onExportPdf",
            "onExportCsv", "onShareReport", "onCompareSettings", "onSelectMetric", "onQueryChange",
            "onSeverityFilterChange", "onClassificationFilterChange", "onSelectComparison", "onOpenReport", "onGenerateReport",
        ).forEach { action -> assertTrue(source.contains("actions.$action")) }
        assertTrue(source.contains("gate?.decision?.displayName(language) ?:"))
        assertTrue(source.contains("enabled = state.baselineId != null && state.currentId != null && !state.busy"))
        assertTrue(source.contains("enabled = state.report != null"))
    }

    @Test
    fun `shared metric tones follow actual counts and gate decisions`() {
        val summary = source.substringAfter("private fun BenchmarkSummaryRow(").substringBefore("private fun BenchmarkReportColumn(")
        assertTrue(summary.contains("tone = if (state.regressionCount > 0)"))
        assertTrue(summary.contains("tone = if (state.improvementCount > 0)"))
        assertTrue(summary.contains("when (gate?.decision)"))
        assertTrue(summary.contains("GateDecision.PASS -> colors.success"))
        assertTrue(summary.contains("GateDecision.FAIL -> MaterialTheme.colorScheme.error"))
        assertTrue(summary.contains("GateDecision.INCONCLUSIVE -> colors.warning"))
        assertTrue(summary.contains("null -> colors.secondaryText"))
    }
}

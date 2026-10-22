package com.androidperformancestudio.frame.presentation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrameWorkspaceStyleTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/frame/presentation/FrameProfilerScreen.kt"),
        )

    @Test
    fun `empty and analyzed workspaces share the same analysis layout`() {
        val workspace = source.substringAfter("public fun FrameProfilerScreen(").substringBefore("private fun AnalysisContent(")
        assertFalse(source.contains("StudioWorkspaceHeader("))
        assertTrue(workspace.contains("analysis = state.analysis ?: EMPTY_FRAME_ANALYSIS"))
        assertTrue(source.contains("if (state.analysis == null)"))
        assertTrue(source.contains("private val EMPTY_FRAME_ANALYSIS = FrameAnalysisResult(emptyList(), FrameSummary(), emptyList())"))
    }

    @Test
    fun `compact cards retain the three analysis panes and frame controls`() {
        assertTrue(source.contains(".padding(StudioTokens.workspacePadding)"))
        listOf(
            "frame-timeline-panel",
            "frame-clusters-panel",
            "frame-problems-panel",
            "frame-detail-panel",
            "frame-context-panel",
        ).forEach { tag ->
            assertTrue(source.contains("testTag(\"$tag\")"))
        }
        val workspace = source.substringAfter("private fun AnalysisContent(").substringBefore("private fun FrameFilterControls(")
        assertTrue(workspace.indexOf("SummaryCards(") < workspace.indexOf("frame-timeline-panel"))
        assertTrue(workspace.indexOf("frame-timeline-panel") < workspace.indexOf("FrameFilterControls("))
        assertTrue(workspace.indexOf("FrameFilterControls(") < workspace.indexOf("frame-clusters-panel"))
        assertTrue(workspace.indexOf("frame-detail-panel") < workspace.indexOf("frame-context-panel"))
        val contextPanel = workspace.substringAfter("frame-context-panel")
        assertTrue(workspace.contains("if (filteredAnalysis.attributions.isNotEmpty() || state.warnings.isNotEmpty())"))
        assertTrue(contextPanel.contains("SelectionContainer {"))
        assertTrue(contextPanel.contains("FrameAttributionSection("))
        assertTrue(contextPanel.contains("state.warnings.forEach"))
        assertFalse(contextPanel.contains("FrameFilterControls("))
        val attribution = source.substringAfter("private fun FrameAttributionSection(").substringBefore("private fun FrameMetricCard(")
        assertTrue(attribution.contains("StudioPanelHeader("))
        assertTrue(attribution.contains("attributions.take(5).forEachIndexed"))
        assertFalse(attribution.contains("TextButton("))
        assertFalse(attribution.contains("expanded"))
        assertTrue(source.contains("modifier = Modifier.weight(0.85f)"))
        assertTrue(source.contains("modifier = Modifier.weight(1.45f)"))
        val detail = source.substringAfter("private fun FrameDetail(").substringBefore("private fun FrameLayoutPreview(")
        assertFalse(detail.contains("Modifier.fillMaxSize().padding(8.dp)"), "Panel padding must not be applied twice")
        listOf("onInspectLayout", "onOpenTrace", "onCopyEvidence", "previousFrameId", "nextFrameId").forEach { action ->
            assertTrue(detail.contains(action))
        }
        assertTrue(Regex("ProfilerCompactButton\\(").findAll(detail).count() == 2)
        assertFalse(detail.contains("OutlinedButton("))
        assertTrue(source.contains(".testTag(\"frame-source-filter\")"))
        assertTrue(source.contains(".testTag(\"frame-result-filter\")"))
    }

    @Test
    fun `single frame metrics and detail rows use uniform compact text and row dividers`() {
        val metric = source.substringAfter("private fun DetailMetric(").substringBefore("private fun FrameStageRow(")
        assertTrue(Regex("style = ViewerTypography\\.bodyCompact\\b").findAll(metric).count() == 2)
        assertFalse(metric.contains("ViewerTypography.metric"))
        val stage = source.substringAfter("private fun FrameStageRow(").substringBefore("private fun DetailRow(")
        assertTrue(Regex("style = ViewerTypography\\.bodyCompact\\b").findAll(stage).count() == 2)
        assertTrue(stage.contains("HorizontalDivider("))
        val row = source.substringAfter("private fun DetailRow(").substringBefore("private fun ClusterList(")
        assertTrue(Regex("style = ViewerTypography\\.bodyCompact\\b").findAll(row).count() == 2)
        assertFalse(Regex("ViewerTypography\\.body\\b").containsMatchIn(metric + stage + row))
        assertTrue(row.contains("HorizontalDivider("))
        assertTrue(source.contains("testTag(\"frame-detail-metrics-divider\")"))
    }

    @Test
    fun `all seven summary metrics use the shared metric card in one row`() {
        val summary = source.substringAfter("private fun SummaryCards(").substringBefore("private fun FrameAttributionSection(")
        assertTrue(Regex("FrameMetricCard\\(").findAll(summary).count() == 7)
        assertTrue(Regex("Modifier\\.weight\\(1f\\)").findAll(summary).count() == 7)
        assertTrue(summary.contains("Res.string.platform_jank_rate"))
        assertTrue(summary.contains("Res.string.refresh_rate"))
        assertFalse(Regex("\\bText\\(").containsMatchIn(summary))
    }

    @Test
    fun `metric tones use the shared card without introducing a page theme`() {
        val metric = source.substringAfter("private fun FrameMetricCard(").substringBefore("private fun MetricSparkline(")
        assertTrue(metric.contains("StudioCard("))
        assertTrue(metric.contains("containerColor = color.copy(alpha = 0.10f)"))
        assertTrue(metric.contains("borderColor = color.copy(alpha = 0.5f)"))
        assertTrue(metric.contains("MetricSparkline(samples, color)"))
        assertFalse(source.contains("CompositionLocalProvider("))
        assertFalse(source.contains("ViewerTheme("))
    }
}

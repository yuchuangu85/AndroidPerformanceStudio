package com.androidperformancestudio.frame.presentation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrameWorkspaceStyleTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/frame/presentation/FrameProfilerScreen.kt"),
        )
    private val workspaceSource =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/frame/presentation/FrameAnalysisWorkspace.kt"),
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
    fun `unified analysis card retains the three unframed panes and frame controls`() {
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
        assertEquals(1, Regex("FrameAnalysisWorkspace\\(").findAll(workspace).count())
        assertFalse(workspace.contains("StudioPanel("))
        assertFalse(workspace.contains("StudioCard("))
        assertTrue(workspace.indexOf("SummaryCards(") < workspace.indexOf("FrameAnalysisWorkspace("))
        val filters =
            workspace
                .substringAfter("val filters: @Composable () -> Unit = {")
                .substringBefore("Column(Modifier.fillMaxSize()")
        assertTrue(filters.contains("FrameFilterControls("))
        assertTrue(filters.contains("onSourceSelected = { sourceFilter = it }"))
        assertTrue(filters.contains("onFilterSelected = { frameFilter = it }"))
        val timeline =
            workspace
                .substringAfter("timeline = { paneModifier ->")
                .substringBefore("clusters = { paneModifier ->")
        assertTrue(timeline.contains("FrameTimelineHeading(language = language, filters = filters)"))
        assertEquals(2, Regex("filters = filters\\b").findAll(timeline).count())
        assertTrue(workspace.indexOf("frame-detail-panel") < workspace.indexOf("frame-context-panel"))
        val contextPanel = workspace.substringAfter("frame-context-panel")
        assertTrue(
            workspace.contains("showContext = filteredAnalysis.attributions.isNotEmpty() || state.warnings.isNotEmpty()"),
        )
        assertTrue(contextPanel.contains("SelectionContainer {"))
        assertTrue(contextPanel.contains("FrameAttributionSection("))
        assertTrue(contextPanel.contains("state.warnings.forEach"))
        assertFalse(contextPanel.contains("FrameFilterControls("))
        val attribution = source.substringAfter("private fun FrameAttributionSection(").substringBefore("private fun FrameMetricCard(")
        assertTrue(attribution.contains("StudioPanelHeader("))
        assertTrue(attribution.contains("attributions.take(5).forEachIndexed"))
        assertFalse(attribution.contains("TextButton("))
        assertFalse(attribution.contains("expanded"))
        listOf("timeline", "clusters", "problems", "details", "context").forEach { slot ->
            assertTrue(workspace.contains("$slot = { paneModifier ->"))
        }
        assertTrue(source.contains(".testTag(\"frame-source-filter\")"))
        assertTrue(source.contains(".testTag(\"frame-result-filter\")"))
    }

    @Test
    fun `single frame details remain unframed compact and preserve action wiring`() {
        val detail = source.substringAfter("private fun FrameDetail(").substringBefore("private fun FrameLayoutPreview(")
        assertFalse(detail.contains("Modifier.fillMaxSize().padding(8.dp)"), "Panel padding must not be applied twice")
        val detailPaddingCount =
            Regex("modifier\\.padding\\(StudioTokens\\.compactContentPadding\\)").findAll(detail).count()
        assertEquals(1, detailPaddingCount)
        assertFalse(detail.contains("StudioPanel("))
        assertFalse(detail.contains("StudioCard("))
        listOf("onInspectLayout", "onOpenTrace", "onCopyEvidence", "previousFrameId", "nextFrameId").forEach { action ->
            assertTrue(detail.contains(action))
        }
        assertTrue(Regex("ProfilerCompactButton\\(").findAll(detail).count() == 2)
        assertFalse(detail.contains("OutlinedButton("))
    }

    @Test
    fun `analysis workspace owns one zero padding card and resizable unframed slots`() {
        assertEquals(1, Regex("StudioCard\\(").findAll(workspaceSource).count())
        assertTrue(workspaceSource.contains("testTag(\"frame-analysis-card\")"))
        assertTrue(workspaceSource.contains("contentPadding = 0.dp"))
        assertTrue(workspaceSource.contains("contentSpacing = 0.dp"))
        assertFalse(workspaceSource.contains("StudioPanel("))
        assertFalse(workspaceSource.contains("StudioDataTable("))
        listOf("timeline", "body", "context", "clusters", "problems", "details").forEach { slot ->
            assertTrue(workspaceSource.contains("testTag(\"frame-workspace-$slot\")"))
        }
        listOf("timeline-height", "context-height", "clusters-width", "details-width").forEach { divider ->
            assertTrue(workspaceSource.contains("tag = \"frame-$divider-resizer\""))
        }
        assertTrue(workspaceSource.contains("FRAME_SPLITTER_SIZE = 7.dp"))
        assertTrue(workspaceSource.contains("contentAlignment = Alignment.Center"))
        assertTrue(workspaceSource.contains("Modifier.width(1.dp).fillMaxHeight()"))
        assertTrue(workspaceSource.contains("Modifier.fillMaxWidth().height(1.dp)"))
        assertTrue(workspaceSource.contains("detectHorizontalDragGestures"))
        assertTrue(workspaceSource.contains("detectVerticalDragGestures"))
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

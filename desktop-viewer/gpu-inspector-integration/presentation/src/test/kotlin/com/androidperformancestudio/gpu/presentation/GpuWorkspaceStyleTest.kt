package com.androidperformancestudio.gpu.presentation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Source contracts for the page 09 workspace. The hub keeps the reference dashboard structure while
 * rendering only indexed-artifact, capability and device facts.
 */
class GpuWorkspaceStyleTest {
    private val hub = source("GpuAnalysisHubScreen.kt")
    private val artifacts = source("GpuAnalysisHubArtifacts.kt")
    private val model = source("GpuHubModel.kt")

    @Test
    fun `hub renders no decorative banner and installs no page local theme`() {
        assertFalse(hub.contains("StudioWorkspaceHeader("))
        assertFalse(hub.contains("Brush.horizontalGradient("))
        assertFalse(hub.contains("CompositionLocalProvider("))
        assertFalse(hub.contains("ViewerTheme("))
        assertTrue(hub.contains(".background(colors.workspace)"))
        assertTrue(hub.contains(".padding(StudioTokens.compactContentPadding)"))
    }

    @Test
    fun `workspace keeps the reference toolbar, summary, rail, tabs and insight structure`() {
        listOf(
            "gpu-analysis-toolbar",
            "gpu-summary-",
            "gpu-hub-tabs",
            "gpu-capability-rail",
            "gpu-open-with-rail",
        ).forEach { tag -> assertTrue(hub.contains(tag), tag) }
        listOf(
            "gpu-artifact-rail",
            "gpu-artifact-table",
            "gpu-artifact-preview",
            "gpu-related-evidence",
            "gpu-tool-capability",
        ).forEach { tag -> assertTrue(artifacts.contains(tag), tag) }
        assertTrue(hub.contains("maxWidth >= GPU_WIDE_WORKSPACE_MIN_WIDTH"))
        assertTrue(hub.contains("maxWidth < GPU_TWO_ROW_SUMMARY_MAX_WIDTH"))
    }

    @Test
    fun `analysis toolbar is hosted by the page header instead of the hub body`() {
        assertTrue(hub.contains("public fun GpuAnalysisToolbar("))
        val hubBody =
            hub
                .substringAfter("public fun GpuAnalysisHubScreen(")
                .substringBefore("public fun GpuAnalysisToolbar(")
        assertFalse(hubBody.contains("GpuAnalysisToolbar("))
        assertTrue(hubBody.contains("GpuSummaryRow("))
    }

    @Test
    fun `hub reuses shared studio controls instead of new card, table or tab styles`() {
        listOf(
            "StudioMetricCard(",
            "StudioPanel(",
            "StudioPanelHeader(",
            "StudioStatusChip(",
        ).forEach { shared -> assertTrue(hub.contains(shared), shared) }
        listOf(
            "StudioDataTable(",
            "StudioCard(",
            "CompactSearchField(",
            "SelectionContainer",
            "ProfilerCompactButton(",
        ).forEach { shared -> assertTrue(artifacts.contains(shared), shared) }
        assertTrue(hub.contains("SegmentedSelector("))
        assertTrue(hub.contains("DropdownSelector("))
        assertTrue(hub.contains("style = SegmentedSelectorStyle.COMPACT_OUTLINED"))
        assertFalse(hub.contains("import androidx.compose.material3.Button"))
        assertFalse(artifacts.contains("import androidx.compose.material3.Button"))
    }

    @Test
    fun `summary row renders exactly the four indexed-artifact facts`() {
        assertEquals(4, Regex("id = \"(indexed|available|attention|capability)\"").findAll(hub).count())
        assertTrue(hub.contains("val summary = state.hubSummary()"))
        assertTrue(hub.contains("icon = UiComponentsRes.drawable.ic_stat_verified"))
        assertTrue(hub.contains("icon = UiComponentsRes.drawable.ic_stat_attention"))
        assertTrue(hub.contains("icon = UiComponentsRes.drawable.ic_stat_capability"))
        assertTrue(hub.contains("summary.attentionCount > 0"))
        assertTrue(hub.contains("state.capability?.launchSupported == true"))
    }

    @Test
    fun `artifact table keeps the documented columns in order`() {
        val columns = artifacts.substringAfter("private fun artifactTableColumns(").substringBefore("private fun GpuArtifactTableRow(")
        val order =
            listOf(
                "artifact_column_kind",
                "artifact_column_file",
                "artifact_column_device",
                "artifact_column_package",
                "artifact_column_api",
                "artifact_column_captured",
                "artifact_column_status",
                "artifact_column_action",
            )
        var cursor = -1
        order.forEach { column ->
            val index = columns.indexOf(column)
            assertTrue(index > cursor, "column $column must follow the previous column")
            cursor = index
        }
    }

    @Test
    fun `every rendering action keeps its guard and dispatches the selected artifact`() {
        assertTrue(hub.contains("onClick = actions.onLaunchAgi"))
        assertTrue(hub.contains("enabled = !state.isBusy && state.capability?.launchSupported == true"))
        assertTrue(hub.contains("onClick = actions.onImportArtifact"))
        assertTrue(hub.contains("onClick = actions.onOpenTraceAnalyzer"))
        assertTrue(hub.contains("onItemSelected = actions.onSelectHubTab"))
        assertTrue(artifacts.contains("onValueChange = actions.onFilterArtifacts"))
        assertTrue(hub.contains("onClick = actions.onRefreshDevices"))
        assertTrue(hub.contains("onItemSelected = { it.serial?.let(actions.onSelectDevice) }"))
        assertTrue(hub.contains("onItemSelected = { actions.onSelectArtifact(it.id) }"))
        listOf("onOpenArtifact", "onVerifyArtifact", "onRevealArtifact", "onRelocateArtifact").forEach { action ->
            assertTrue(hub.contains("actions.$action") || artifacts.contains("actions.$action"), action)
        }
        assertTrue(artifacts.contains("enabled = !isBusy && availability?.canOpen == true"))
        assertTrue(hub.contains("enabled = !state.isBusy && availability?.canOpen == true"))
    }

    @Test
    fun `forbidden AGI frame panels appear only as a declared closed list`() {
        val beforeClosedList = artifacts.substringBefore("private fun closedPanels(")
        listOf(
            "closed_panel_gpu_counters",
            "closed_panel_shader_hotspots",
            "closed_panel_render_passes",
            "closed_panel_draw_calls",
            "closed_panel_overdraw",
        ).forEach { key -> assertFalse(beforeClosedList.contains(key), key) }
        val closedList = artifacts.substringAfter("private fun closedPanels(").substringBefore("internal fun ArtifactLocationStatus.studioTone")
        listOf(
            "closed_panel_gpu_counters",
            "closed_panel_shader_hotspots",
            "closed_panel_render_passes",
            "closed_panel_draw_calls",
            "closed_panel_overdraw",
        ).forEach { key -> assertTrue(closedList.contains(key), key) }
        listOf("fps_average", "gpu_busy", "frame_time", "overdraw_risk").forEach { fabricated ->
            assertFalse(hub.contains(fabricated), fabricated)
            assertFalse(artifacts.contains(fabricated), fabricated)
        }
    }

    @Test
    fun `hub facts are derived without composables or platform IO`() {
        assertFalse(model.contains("@Composable"))
        assertTrue(model.contains("internal fun GpuIntegrationState.hubSummary()"))
        assertTrue(model.contains("internal fun GpuIntegrationState.relatedArtifacts("))
        assertTrue(model.contains("internal fun filterArtifacts("))
        assertFalse(model.contains("System.getProperty"))
    }

    private fun source(file: String): String =
        Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/gpu/presentation", file))
}

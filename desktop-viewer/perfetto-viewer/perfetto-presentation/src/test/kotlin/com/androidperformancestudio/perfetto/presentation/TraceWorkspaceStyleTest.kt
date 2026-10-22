package com.androidperformancestudio.perfetto.presentation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TraceWorkspaceStyleTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/perfetto/presentation/TraceAnalyzerWorkspace.kt"),
        )

    @Test
    fun `wide and narrow workspaces omit the decorative banner`() {
        assertFalse(source.contains("StudioWorkspaceHeader("))
        listOf("WideTraceWorkspace", "NarrowTraceWorkspace").forEach { name ->
            assertFalse(functionSource(name).contains("TraceWorkspaceBanner(language)"))
        }
    }

    @Test
    fun `workspace retains independent sessions viewer capture and evidence layouts`() {
        val wide = functionSource("WideTraceWorkspace")
        assertTrue(wide.contains(".padding(StudioTokens.workspacePadding)"))
        assertTrue(wide.contains("Modifier.width(232.dp).fillMaxHeight()"))
        assertTrue(wide.contains("Modifier.width(312.dp).fillMaxHeight()"))
        val narrow = functionSource("NarrowTraceWorkspace")
        assertTrue(narrow.contains(".verticalScroll(rememberScrollState())"))
        listOf("280", "360", "420").forEach { height -> assertTrue(narrow.contains(".height($height.dp)")) }
        val panel = functionSource("WorkspaceCard")
        assertTrue(panel.contains("StudioPanelHeader("))
        assertTrue(panel.contains("contentPadding = 0.dp"))
        assertTrue(panel.contains("contentSpacing = 0.dp"))
        assertFalse(source.contains("CompositionLocalProvider("))
        assertFalse(source.contains("ViewerTheme("))
    }

    @Test
    fun `workspace and nested card groups use four dp external spacing without changing card contents`() {
        listOf("WideTraceWorkspace", "NarrowTraceWorkspace").forEach { name ->
            val workspace = functionSource(name)
            assertTrue(workspace.contains(".padding(StudioTokens.workspacePadding)"))
            assertTrue(workspace.contains("Arrangement.spacedBy(StudioTokens.cardGap)"))
            assertFalse(workspace.contains("Arrangement.spacedBy(StudioTokens.panelGap)"))
        }
        val grid = functionSource("TraceSummaryGrid")
        assertEquals(3, Regex("Arrangement\\.spacedBy\\(StudioTokens\\.cardGap\\)").findAll(grid).count())
        listOf("TraceSessionsPanel", "TraceInsightsPanel", "TraceViewerPanel").forEach { name ->
            assertTrue(functionSource(name).contains(".padding(StudioTokens.workspacePadding)"))
        }
        assertTrue(functionSource("TraceViewerPanel").contains("verticalArrangement = Arrangement.spacedBy(StudioTokens.panelGap)"))
        assertTrue(functionSource("TraceSessionsPanel").contains("Arrangement.spacedBy(StudioTokens.cardGap)"))
        assertTrue(functionSource("TraceInsightsPanel").contains("Arrangement.spacedBy(StudioTokens.cardGap)"))
        assertTrue(functionSource("TraceSessionRow").contains("contentSpacing = 4.dp"))
        assertTrue(functionSource("TraceSessionRow").contains("Row(horizontalArrangement = Arrangement.spacedBy(6.dp))"))
        assertTrue(functionSource("TraceInsightCard").contains("contentSpacing = 5.dp"))
        assertTrue(functionSource("WorkspaceCard").contains("Modifier.padding(horizontal = StudioTokens.compactContentPadding)"))
        val bookmarks =
            Files.readString(
                Path.of("src/main/kotlin/com/androidperformancestudio/perfetto/presentation/TraceAnalyzerBookmarks.kt"),
            )
        val panel =
            bookmarks.substringAfter("internal fun TraceBookmarksPanel(").substringBefore("internal fun TraceEvidenceAnchors(")
        assertTrue(panel.contains(".padding(StudioTokens.workspacePadding)"))
        assertTrue(panel.contains("verticalArrangement = Arrangement.spacedBy(StudioTokens.cardGap)"))
        assertTrue(panel.contains("contentSpacing = 3.dp"))
        assertTrue(panel.contains("horizontalArrangement = Arrangement.spacedBy(6.dp)"))
    }

    @Test
    fun `summary cards preserve real unavailable metrics and compact tone styling`() {
        val summary = functionSource("TraceSummaryCard")
        assertTrue(summary.contains("summary.value ?: localizedStringResource(Res.string.trace_workspace_unavailable, language)"))
        assertTrue(summary.contains(".height(72.dp)"))
        assertTrue(summary.contains("accent.copy(alpha = 0.10f)"))
        assertTrue(summary.contains(".padding(StudioTokens.compactContentPadding)"))
        assertTrue(summary.contains("verticalArrangement = Arrangement.SpaceBetween"))
    }

    private fun functionSource(name: String): String = source.substringAfter("fun $name(").substringBefore("\n@Composable")
}

package com.androidperformancestudio.perfetto.presentation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PerfettoCardSourceTest {
    @Test
    fun `capture and trace cards delegate surface drawing to StudioCard`() {
        mapOf(
            "PerfettoCapturePage.kt" to listOf("PerfettoDataSourceGroupCard", "PerfettoTemplateOption"),
            "TraceAnalyzerWorkspace.kt" to
                listOf("TraceSummaryCard", "TraceSessionRow", "TraceInsightCard", "WorkspaceCard"),
        ).forEach { (file, functions) ->
            val source = source(file)
            assertTrue(source.contains("import com.androidperformancestudio.ui.studio.StudioCard"))
            functions.forEach { name ->
                val body = functionSource(source, name)
                assertTrue(body.contains("StudioCard("), "$name must use the shared card")
                assertFalse(body.contains(".border("), "$name must not draw a duplicate border")
                if (name != "WorkspaceCard") {
                    assertFalse(body.contains(".background("), "$name must not draw a duplicate background")
                }
            }
        }
    }

    @Test
    fun `trace cards preserve selection actions sizing and unpadded panel headers`() {
        val source = source("TraceAnalyzerWorkspace.kt")
        val summary = functionSource(source, "TraceSummaryCard")
        assertTrue(summary.contains(".height(72.dp)"))
        assertTrue(summary.contains("verticalArrangement = Arrangement.SpaceBetween"))
        assertTrue(summary.contains("contentPadding = 0.dp"))
        assertTrue(summary.contains(".padding(StudioTokens.compactContentPadding)"))
        val session = functionSource(source, "TraceSessionRow")
        assertTrue(session.contains(".clickable { actions.onSelectSession(session.sessionId) }"))
        assertTrue(session.contains("actions.onOpenSession(session.sessionId)"))
        assertTrue(session.contains("actions.onDeleteSession(session.sessionId)"))
        val insight = functionSource(source, "TraceInsightCard")
        assertTrue(insight.contains("borderColor = if (selected) accent else MaterialTheme.colorScheme.outlineVariant"))
        assertTrue(insight.contains("canOpenInsightInTimeline(insight, activeTrace)"))
        assertTrue(insight.contains(".horizontalScroll(rememberScrollState())"))
        val panel = functionSource(source, "WorkspaceCard")
        assertTrue(panel.contains("contentPadding = 0.dp"))
        assertTrue(panel.contains("contentSpacing = 0.dp"))
        assertTrue(panel.contains("StudioPanelHeader("))
        assertTrue(panel.contains("HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)"))
        val viewer = functionSource(source, "TraceViewerPanel")
        assertTrue(viewer.contains("StudioCard("))
        assertTrue(viewer.contains(".testTag(\"perfetto-source-of-truth\")"))
        assertTrue(viewer.contains("containerColor = LocalViewerColors.current.panel"))
        assertTrue(viewer.contains("borderColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)"))
    }

    @Test
    fun `controls and workspace primitives retain their own layouts`() {
        val controls = source("PerfettoMacOsUi.kt")
        assertFalse(controls.contains("StudioCard("))
        assertTrue(controls.contains("BasicTextField("))
        assertFalse(source("TraceAnalyzerWorkspace.kt").contains("StudioWorkspaceHeader("))
        val template = functionSource(source("PerfettoCapturePage.kt"), "PerfettoTemplateOption")
        assertTrue(template.contains(".clickable(onClick = onClick)"))
        assertTrue(template.contains(".padding(horizontal = 8.dp, vertical = 7.dp)"))
        assertTrue(template.contains("contentPadding = 0.dp"))
    }

    private fun source(file: String): String =
        Files.readString(Path.of("src/main/kotlin/com/androidperformancestudio/perfetto/presentation", file))

    private fun functionSource(
        source: String,
        name: String,
    ): String {
        val marker = "fun $name("
        assertTrue(source.contains(marker), "Missing function: $name")
        return source.substringAfter(marker).substringBefore("\n@Composable")
    }
}

package com.androidperformancestudio.perfetto.app

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PerfettoWorkspaceLayoutTest {
    private val source =
        Files.readString(
            Path.of("src/main/kotlin/com/androidperformancestudio/perfetto/app/PerfettoMainPage.kt"),
        )

    @Test
    fun `workspace uses compact inspector chrome without a page title`() {
        assertFalse(source.contains("Text(\"Perfetto Trace Analyzer\""))
        assertTrue(source.contains("private fun PerfettoToolbar("))
        assertTrue(source.contains(".height(40.dp)"))
        assertTrue(source.contains("PerfettoHomeButton("))
        assertTrue(source.contains("adbPath = adbPath"))
    }

    @Test
    fun `workspace composes sessions and diagnostics around the capture panels`() {
        assertTrue(source.contains("RecentSessionsPanel("))
        assertTrue(source.contains("TraceDiagnosticsWorkspacePanel("))
        assertTrue(source.contains("PerfettoWorkspacePanel("))
        assertTrue(source.contains("PerfettoCapturePage("))
        assertTrue(source.contains("Modifier.width(320.dp)"))
    }

    @Test
    fun `workspace avoids large material page components`() {
        assertTrue(source.contains("private fun InitialTraceNotice("))
        assertFalse(source.contains("Card("))
        assertFalse(source.contains("OutlinedButton("))
    }

    @Test
    fun `trace diagnostics uses vertical navigation and a separate content pane`() {
        assertTrue(source.contains("private fun TraceDiagnosticNavigation("))
        assertTrue(source.contains("private fun TraceDiagnosticContent("))
        assertTrue(source.contains("Modifier.width(240.dp)"))
        assertTrue(source.contains("Res.string.select_a_diagnostic_on_the_left_to_view_its_result"))
        assertFalse(source.contains("chunked(3)"))
    }
}

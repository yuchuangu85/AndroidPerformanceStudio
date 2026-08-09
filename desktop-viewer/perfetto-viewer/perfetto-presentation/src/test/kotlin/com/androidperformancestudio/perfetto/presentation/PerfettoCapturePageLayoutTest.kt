package com.androidperformancestudio.perfetto.presentation

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PerfettoCapturePageLayoutTest {
    private val source =
        Files.readString(
            Path.of(
                "src/main/kotlin/com/androidperformancestudio/perfetto/presentation/PerfettoCapturePage.kt",
            ),
        )

    @Test
    fun `capture page is split into template data source and configuration panels`() {
        assertTrue(source.contains("private fun PerfettoTemplatePanel("))
        assertTrue(source.contains("private fun PerfettoDataSourcesPanel("))
        assertTrue(source.contains("private fun PerfettoConfigurationPanel("))
        assertTrue(source.contains("Modifier.width(300.dp)"))
        assertTrue(source.contains("Checkbox("))
        assertTrue(source.contains("PerfettoCompactTextField("))
        assertTrue(source.contains("PerfettoCompactButton("))
    }

    @Test
    fun `capture page leaves adb and device selection to the workspace toolbar`() {
        assertFalse(source.contains("\"ADB Path\""))
        assertFalse(source.contains("\"Device:\""))
        assertFalse(source.contains("Card("))
        assertFalse(source.contains("RadioButton("))
        assertFalse(source.contains("OutlinedTextField("))
    }
}
